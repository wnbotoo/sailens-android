"""Export ZipDepth as a `depth` model (docs/models.md).

ZipDepth (github.com/fabiotosi92/ZipDepth, MIT) is a CNN of about 6.1 M parameters. Its forward() normalizes
the RGB input itself and outputs affine-invariant inverse depth, so it fits the contract as it is:
[1,3,H,W] in [0, 1] -> [1,1,H,W], H and W multiples of 32. Its code is not in this repository: pass a
checkout of it, with its checkpoints. The result is a local artefact: never commit it. Drop it at
app/src/main/assets/depth.tflite (git-ignored) and run the debug Model consistency check on the target device.

It uses the checkpoint without unfold in the upsampler (zipdepth_base_npu.pth, checked by its sha256), whose
graph is ordinary operators. What the export does, all with the model's own weights:
1. The reparameterizable branches fused for inference (the model's own fuse_for_inference()).
2. Each grouped 1x1 convolution split into one ordinary 1x1 convolution per group: litert-torch lowers a
   grouped pointwise convolution to a rank-4 BATCH_MATMUL that its converter then rejects.
3. Each 1x1 convolution that sees a 1x1 map at this input size (squeeze-excitation) written as the linear
   layer it is.
4. With --fp16 only: the weights stored as float16 (../tflite_tools.py), which halves the file.
Then it checks, on the CPU, that steps 1-3 changed nothing (torch against the stock model), that the float32
.tflite matches torch and, with --fp16, that storing the weights as float16 moved the output by no more than
their rounding.

Needs torch, litert-torch and ai-edge-litert; --fp16 also ai-edge-quantizer.
    python export.py <ZipDepth checkout> 512 288 out/depth.tflite        # height width
    python export.py <ZipDepth checkout> 512 288 out/depth.tflite --fp16
"""
import copy
import hashlib
import os
import sys
import tempfile

import litert_torch
import numpy as np
import torch
from ai_edge_litert.interpreter import Interpreter

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import tflite_tools as tt  # noqa: E402

CHECKPOINT = "checkpoints/zipdepth_base_npu.pth"
CHECKPOINT_SHA256 = "627c04fda584133ead4310074884a4a037061b4c01ba86e73e492ea30fab570d"


class SplitGroupedPointwise(torch.nn.Module):
    """A grouped 1x1 convolution as one ordinary 1x1 convolution per group: the same arithmetic."""

    def __init__(self, conv):
        super().__init__()
        g = conv.groups
        cin, cout = conv.in_channels // g, conv.out_channels // g
        self.cin = cin
        self.parts = torch.nn.ModuleList()
        for i in range(g):
            part = torch.nn.Conv2d(cin, cout, 1, bias=conv.bias is not None)
            with torch.no_grad():
                part.weight.copy_(conv.weight[i * cout:(i + 1) * cout])
                if conv.bias is not None:
                    part.bias.copy_(conv.bias[i * cout:(i + 1) * cout])
            self.parts.append(part)

    def forward(self, x):
        return torch.cat([p(x[:, i * self.cin:(i + 1) * self.cin]) for i, p in enumerate(self.parts)], dim=1)


class PointwiseAsLinear(torch.nn.Module):
    """A 1x1 convolution applied to a 1x1 map as the linear layer it is."""

    def __init__(self, conv):
        super().__init__()
        self.linear = torch.nn.Linear(conv.in_channels, conv.out_channels, bias=conv.bias is not None)
        with torch.no_grad():
            self.linear.weight.copy_(conv.weight[:, :, 0, 0])
            if conv.bias is not None:
                self.linear.bias.copy_(conv.bias)

    def forward(self, x):
        b, c = x.shape[0], x.shape[1]
        return self.linear(x.reshape(b, c)).reshape(b, -1, 1, 1)


def split_grouped_pointwise(module):
    n = 0
    for name, child in list(module.named_children()):
        if isinstance(child, torch.nn.Conv2d) and child.kernel_size == (1, 1) and 1 < child.groups < child.in_channels:
            setattr(module, name, SplitGroupedPointwise(child))
            n += 1
        else:
            n += split_grouped_pointwise(child)
    return n


def pointwise_on_1x1_as_linear(model, x):
    """Replaces the 1x1 convolutions that see a 1x1 map for input x."""
    seen = {}
    hooks = [m.register_forward_hook(lambda mm, i, o, key=id(m): seen.__setitem__(key, tuple(i[0].shape[2:])))
             for m in model.modules() if isinstance(m, torch.nn.Conv2d)]
    with torch.no_grad():
        model(x)
    for hook in hooks:
        hook.remove()

    def walk(module):
        n = 0
        for name, child in list(module.named_children()):
            if (isinstance(child, torch.nn.Conv2d) and child.kernel_size == (1, 1) and child.groups == 1
                    and seen.get(id(child)) == (1, 1)):
                setattr(module, name, PointwiseAsLinear(child))
                n += 1
            else:
                n += walk(child)
        return n

    return walk(model)


class Wrapper(torch.nn.Module):
    def __init__(self, model, h, w):
        super().__init__()
        self.model, self.h, self.w = model, h, w

    def forward(self, x):
        y = self.model(x)
        y = y[0] if isinstance(y, (tuple, list)) else y
        return y.reshape(1, 1, self.h, self.w)


def load(root):
    sys.path.insert(0, root)
    from zipdepth.model.architecture import create_model
    from zipdepth.utils.model_utils import strip_state_dict_prefixes

    path = os.path.join(root, CHECKPOINT)
    with open(path, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    assert digest == CHECKPOINT_SHA256, f"{path}: sha256 {digest}, expected {CHECKPOINT_SHA256}"
    model = create_model(variant="base", upsample_unfold=False)
    state = torch.load(path, map_location="cpu", weights_only=True)
    missing, _ = model.load_state_dict(strip_state_dict_prefixes(state.get("model_state_dict", state)), strict=False)
    assert not missing, f"weights missing from the checkpoint: {missing}"
    return model.eval()


def torch_output(model, x, h, w):
    with torch.no_grad():
        return Wrapper(model, h, w)(x).numpy()


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--fp16"]
    fp16 = len(args) != len(sys.argv) - 1
    root, h, w, out = args[0], int(args[1]), int(args[2]), args[3]
    assert h % 32 == 0 and w % 32 == 0, "the encoder downsamples by 32"
    model = load(root)
    torch.manual_seed(0)
    x01 = torch.rand(1, 3, h, w)
    stock = torch_output(model, x01, h, w)
    model = copy.deepcopy(model).fuse_for_inference()
    fused_err = tt.relative_error(torch_output(model, x01, h, w), stock)
    print(f"torch fused vs stock: {tt.summary(fused_err)}")
    splits = split_grouped_pointwise(model)
    linears = pointwise_on_1x1_as_linear(model, x01)
    rewritten = torch_output(model, x01, h, w)
    rewrite_err = tt.relative_error(rewritten, stock)
    print(f"split {splits} grouped 1x1 convolutions, {linears} 1x1 convolutions on a 1x1 map as linear; "
          f"torch rewritten vs stock: {tt.summary(rewrite_err)}")
    assert np.median(rewrite_err) <= 1e-5, "fusing or rewriting changed the output"

    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "raw.tflite")
        litert_torch.convert(Wrapper(model, h, w), (x01,)).export(raw)
        model_bytes = open(raw, "rb").read()
    lite32 = tt.run_cpu(model_bytes, x01.numpy())
    lite_err = tt.relative_error(lite32, stock)
    print(f"tflite float32 (CPU) vs torch stock: {tt.summary(lite_err)}")
    assert np.median(lite_err) <= 1e-4, "the .tflite does not compute what torch does"
    if fp16:
        model_bytes = tt.cast_weights_fp16(model_bytes)
        types_, ops = tt.contents(model_bytes)
        print(f"float16 weights: tensors {types_}, {ops.get('DEQUANTIZE', 0)} DEQUANTIZE")
        cast_err = tt.relative_error(tt.run_cpu(model_bytes, x01.numpy()), lite32)
        print(f"tflite float16 weights (CPU) vs float32: {tt.summary(cast_err)}")
        # At 512x288 the output moved 9.4e-4 (median), p99 5.0e-3: twice the bound for one weight, the rounding
        # of many fused layers adding up. A cast that broke a tensor moves it by far more.
        assert np.median(cast_err) <= 5e-3, "storing the weights as float16 changed more than their rounding"
    open(out, "wb").write(model_bytes)
    shapes = Interpreter(model_path=out)
    print(f"input {shapes.get_input_details()[0]['shape']} -> output {shapes.get_output_details()[0]['shape']}: {out}")
