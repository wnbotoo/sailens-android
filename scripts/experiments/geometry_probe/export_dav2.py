"""Export Depth Anything V2 Small (Apache-2.0) to LiteRT at a fixed input size.

Needs torch, transformers and litert-torch (see docs/experiments/geometry-probe-2026-09.md).
The exported .tflite is a local artefact: never commit it.
    python export_dav2.py 266 350 out/dav2s_266x350.tflite
"""
import sys

import litert_torch
import torch
from transformers import AutoModelForDepthEstimation

REPO = "depth-anything/Depth-Anything-V2-Small-hf"
REVISION = "5426e4f0f36572d16453bbda7a8389317b1bef99"


class Wrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return self.model(pixel_values=x).predicted_depth


if __name__ == "__main__":
    h, w, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
    assert h % 14 == 0 and w % 14 == 0, "DINOv2 patch size is 14"
    model = AutoModelForDepthEstimation.from_pretrained(REPO, revision=REVISION).eval()
    litert_torch.convert(Wrapper(model), (torch.randn(1, 3, h, w),)).export(out)
