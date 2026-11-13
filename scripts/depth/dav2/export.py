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
4. With --fp16 only: the weights stored as float16 (../tflite_tools.py), which halves the file. The GPU,
   which computes in FP16 at its default precision, reads float16 weights either way.
Then it checks, on the CPU and on two inputs (uniform noise and a fixed picture-like image), that the fold
changed nothing (torch, folded against stock with external normalization), that the float32 .tflite matches
torch and, with --fp16, that storing the weights as float16 moved the output by no more than measured bounds:
every value finite, and the median, the 99th percentile and each region of the output within its bound
(../tflite_tools.py check).

Needs torch, transformers, litert-torch and ai-edge-litert; --fp16 also ai-edge-quantizer.
    python export.py 392 224 out/depth.tflite        # height width, both multiples of 14
    python export.py 392 224 out/depth.tflite --fp16
"""
import os
import sys
import tempfile
import types

import litert_torch
import torch
from ai_edge_litert.interpreter import Interpreter
from transformers import AutoModelForDepthEstimation

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import patch_rank3  # noqa: E402
import tflite_tools as tt  # noqa: E402

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


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--fp16"]
    fp16 = len(args) != len(sys.argv) - 1
    h, w, out = int(args[0]), int(args[1]), args[2]
    assert h % 14 == 0 and w % 14 == 0, "DINOv2 patch size is 14"
    model = AutoModelForDepthEstimation.from_pretrained(REPO, revision=REVISION).eval()
    torch.manual_seed(0)
    x01 = torch.rand(1, 3, h, w)
    inputs = {"random": x01, "picture": torch.from_numpy(tt.picture(h, w))}
    with torch.no_grad():
        stock = {k: Wrapper(model)((x - MEAN.view(1, 3, 1, 1)) / STD.view(1, 3, 1, 1)).numpy()
                 for k, x in inputs.items()}
    fold_normalization(model)
    fold_embeddings(model, h, w)
    for k, x in inputs.items():
        with torch.no_grad():
            folded = Wrapper(model)(x).numpy()
        tt.check(f"torch folded vs stock, {k}", folded, stock[k], median=1e-5, p99=1e-4, cell=1e-5, largest=1e-3)

    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "raw.tflite")
        litert_torch.convert(Wrapper(model), (x01,)).export(raw)
        patched, reshapes, fcs = patch_rank3.patch(open(raw, "rb").read())
    print(f"lifted {reshapes} RESHAPE, {fcs} FULLY_CONNECTED")
    lite32 = {k: tt.run_cpu(patched, x.numpy()) for k, x in inputs.items()}
    for k in inputs:
        tt.check(f"tflite float32 (CPU) vs torch stock, {k}", lite32[k], stock[k], median=1e-4, p99=1e-4, cell=1e-4, largest=1e-3)
    if fp16:
        patched = tt.cast_weights_fp16(patched)
        types_, ops = tt.contents(patched)
        print(f"float16 weights: tensors {types_}, {ops.get('DEQUANTIZE', 0)} DEQUANTIZE")
        # Measured at 392x224: median 1.3e-4 / 2.8e-5, p99 3.6e-4 / 3.5e-3, worst cell 3.6e-4 / 8.0e-4, largest 3.9e-4 /
        # 2.0e-2 (random / picture).
        for k, x in inputs.items():
            tt.check(f"tflite float16 weights (CPU) vs float32, {k}", tt.run_cpu(patched, x.numpy()), lite32[k],
                     median=1e-3, p99=1e-2, cell=3e-3, largest=0.1)
    open(out, "wb").write(patched)
    shapes = Interpreter(model_path=out)
    print(f"input {shapes.get_input_details()[0]['shape']} -> output {shapes.get_output_details()[0]['shape']}: {out}")
