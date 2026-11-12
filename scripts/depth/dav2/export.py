"""Export Depth Anything V2 Small (Apache-2.0 code and weights) as a `depth` model (docs/models.md).

The result is a local artefact: never commit it. Drop it at app/src/main/assets/depth.tflite
(git-ignored) and run the debug Model consistency check on the target device.

What the export does to fit the contract and the LiteRT GPU, all with the model's own weights:
1. ImageNet normalization folded into the patch-embedding convolution (14x14, stride 14, no padding,
   so the fold is exact): the model takes RGB in [0, 1] like every other model here. The letterbox's
   black padding, 0 before the fold, still meets the convolution as normalized black.
2. The interpolated position embedding and the CLS token folded into constants for the fixed input
   size: left in the graph they need GATHER_ND and BROADCAST_TO, which the GPU accelerator does not
   compile.
3. Token activations kept rank 3 around FULLY_CONNECTED (../patch_rank3.py): otherwise the GPU runs the
   graph without an error and computes it wrongly.
4. With --fp16 only: the weights of FULLY_CONNECTED, CONV_2D, DEPTHWISE_CONV_2D and TRANSPOSE_CONV
   stored as float16, each behind an explicit DEQUANTIZE (ai-edge-quantizer's float casting), which
   halves the file. Activations and every other constant stay float32; the GPU, which computes in FP16
   at its default precision, reads float16 weights either way.
Then it checks, on the CPU, that the fold changed nothing (torch, folded against stock with external
normalization), that the float32 .tflite matches torch and, with --fp16, that storing the weights as
float16 moved the output by no more than their rounding.

Needs torch, transformers, litert-torch and ai-edge-litert; --fp16 also ai-edge-quantizer.
    python export.py 392 224 out/depth.tflite        # height width, both multiples of 14
    python export.py 392 224 out/depth.tflite --fp16
"""
import os
import sys
import tempfile
import types

import litert_torch
import numpy as np
import torch
from ai_edge_litert.interpreter import Interpreter
from transformers import AutoModelForDepthEstimation

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import patch_rank3  # noqa: E402

REPO = "depth-anything/Depth-Anything-V2-Small-hf"
REVISION = "5426e4f0f36572d16453bbda7a8389317b1bef99"
MEAN = torch.tensor([0.485, 0.456, 0.406])
STD = torch.tensor([0.229, 0.224, 0.225])


class Wrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return self.model(pixel_values=x).predicted_depth


def fold_normalization(model):
    """conv(W, b)((x - mean) / std) == conv(W / std, b - sum(W * mean / std))(x), per input channel."""
    conv = model.backbone.embeddings.patch_embeddings.projection
    assert conv.padding in ((0, 0), 0) and conv.in_channels == 3, "the fold is exact only without padding"
    with torch.no_grad():
        w = conv.weight
        scale = (1 / STD).view(1, 3, 1, 1)
        shift = (MEAN / STD).view(1, 3, 1, 1)
        conv.bias -= (w * shift).sum(dim=(1, 2, 3))
        conv.weight *= scale


def fold_embeddings(model, h, w):
    emb = model.backbone.embeddings
    with torch.no_grad():
        n = (h // emb.patch_size) * (w // emb.patch_size)
        dummy = torch.zeros(1, n + 1, emb.cls_token.shape[-1])
        pos = emb.interpolate_pos_encoding(dummy, h, w).detach().clone()
    emb.register_buffer("folded_pos", pos)
    emb.register_buffer("folded_cls", emb.cls_token.detach().clone())

    def forward(self, pixel_values, bool_masked_pos=None):
        assert pixel_values.shape[0] == 1 and bool_masked_pos is None
        x = self.patch_embeddings(pixel_values)
        x = torch.cat((self.folded_cls, x), dim=1)
        return self.dropout(x + self.folded_pos)

    emb.forward = types.MethodType(forward, emb)


def cast_weights_fp16(buf):
    """The model with its FC / convolution weights stored as float16 behind explicit DEQUANTIZE ops."""
    from ai_edge_quantizer import qtyping, quantizer

    q = quantizer.Quantizer(buf)
    q.update_quantization_recipe(
        regex=".*",
        operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
        op_config=qtyping.OpQuantizationConfig(
            weight_tensor_config=qtyping.TensorQuantizationConfig(num_bits=16, dtype=qtyping.TensorDataType.FLOAT),
            compute_precision=qtyping.ComputePrecision.FLOAT,
            explicit_dequantize=True,
        ),
        algorithm_key="float_casting",
    )
    return bytes(q.quantize().quantized_model)


def run_cpu(buf, x):
    """The model's output for one input, on the CPU."""
    interpreter = Interpreter(model_content=buf)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], x.numpy())
    interpreter.invoke()
    return interpreter.get_tensor(interpreter.get_output_details()[0]["index"])


def tensor_types(buf):
    """How many tensors of each type the model holds, and the operator counts, for the printout."""
    from ai_edge_litert import schema_py_generated as S

    m = S.ModelT.InitFromPackedBuf(buf, 0)
    g = m.subgraphs[0]
    names = {v: k for k, v in vars(S.TensorType).items() if not k.startswith("_")}
    ops = {v: k for k, v in vars(S.BuiltinOperator).items() if not k.startswith("_")}
    types_, codes = {}, {}
    for t in g.tensors:
        types_[names[t.type]] = types_.get(names[t.type], 0) + 1
    for op in g.operators:
        oc = m.operatorCodes[op.opcodeIndex]
        name = ops[max(oc.builtinCode, oc.deprecatedBuiltinCode)]
        codes[name] = codes.get(name, 0) + 1
    return types_, codes


def relative_error(a, b):
    a, b = np.asarray(a, np.float64).ravel(), np.asarray(b, np.float64).ravel()
    den = np.maximum(np.abs(b), 0.05 * np.abs(b).max())
    return np.abs(a - b) / den


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--fp16"]
    fp16 = len(args) != len(sys.argv) - 1
    h, w, out = int(args[0]), int(args[1]), args[2]
    assert h % 14 == 0 and w % 14 == 0, "DINOv2 patch size is 14"
    model = AutoModelForDepthEstimation.from_pretrained(REPO, revision=REVISION).eval()
    torch.manual_seed(0)
    x01 = torch.rand(1, 3, h, w)
    with torch.no_grad():
        stock = Wrapper(model)((x01 - MEAN.view(1, 3, 1, 1)) / STD.view(1, 3, 1, 1)).numpy()
    fold_normalization(model)
    fold_embeddings(model, h, w)
    with torch.no_grad():
        folded = Wrapper(model)(x01).numpy()
    fold_err = relative_error(folded, stock)
    print(f"torch folded vs stock: median relative error {np.median(fold_err):.2e}, max {fold_err.max():.2e}")
    assert np.median(fold_err) <= 1e-5, "the fold changed the output"

    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "raw.tflite")
        litert_torch.convert(Wrapper(model), (x01,)).export(raw)
        patched, reshapes, fcs = patch_rank3.patch(open(raw, "rb").read())
    print(f"lifted {reshapes} RESHAPE, {fcs} FULLY_CONNECTED")
    lite32 = run_cpu(patched, x01)
    lite_err = relative_error(lite32, stock)
    print(f"tflite float32 (CPU) vs torch stock: median relative error {np.median(lite_err):.2e}, p99 {np.percentile(lite_err, 99):.2e}")
    assert np.median(lite_err) <= 1e-4, "the .tflite does not compute what torch does"
    if fp16:
        patched = cast_weights_fp16(patched)
        types_, codes = tensor_types(patched)
        print(f"float16 weights: tensors {types_}, {codes.get('DEQUANTIZE', 0)} DEQUANTIZE")
        lite16 = run_cpu(patched, x01)
        cast_err = relative_error(lite16, lite32)
        print(f"tflite float16 weights (CPU) vs float32: median relative error {np.median(cast_err):.2e}, "
              f"p99 {np.percentile(cast_err, 99):.2e}")
        # Rounding to float16 moves a weight by at most 2^-11 (4.9e-4) of itself; at 392x224 the output
        # moved about a quarter of that. A cast that broke a tensor moves it by far more.
        assert np.median(cast_err) <= 1e-3, "storing the weights as float16 changed more than their rounding"
    open(out, "wb").write(patched)
    shapes = Interpreter(model_path=out)
    print(f"input {shapes.get_input_details()[0]['shape']} -> output {shapes.get_output_details()[0]['shape']}: {out}")
