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
Then it checks, on the CPU, that the fold changed nothing (torch, folded against stock with external
normalization) and that the .tflite matches torch.

Needs torch, transformers, litert-torch and ai-edge-litert.
    python export.py 392 224 out/depth.tflite        # height width, both multiples of 14
"""
import os
import sys
import tempfile
import types

import litert_torch
import numpy as np
import torch
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


def relative_error(a, b):
    a, b = np.asarray(a, np.float64).ravel(), np.asarray(b, np.float64).ravel()
    den = np.maximum(np.abs(b), 0.05 * np.abs(b).max())
    return np.abs(a - b) / den


if __name__ == "__main__":
    h, w, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
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
    open(out, "wb").write(patched)
    print(f"lifted {reshapes} RESHAPE, {fcs} FULLY_CONNECTED")

    from ai_edge_litert.interpreter import Interpreter
    interpreter = Interpreter(model_path=out)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], x01.numpy())
    interpreter.invoke()
    lite = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
    lite_err = relative_error(lite, stock)
    print(f"tflite (CPU) vs torch stock: median relative error {np.median(lite_err):.2e}, p99 {np.percentile(lite_err, 99):.2e}")
    assert np.median(lite_err) <= 1e-4, "the .tflite does not compute what torch does"
    print(f"input {interpreter.get_input_details()[0]['shape']} -> output {interpreter.get_output_details()[0]['shape']}: {out}")
