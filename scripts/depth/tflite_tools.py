"""What every depth export recipe here does to and with its .tflite: store the weights as float16, run it on the
CPU, measure how far an output moved, count what the file holds.

Needs ai-edge-litert; cast_weights_fp16 also ai-edge-quantizer.
"""
import numpy as np
from ai_edge_litert.interpreter import Interpreter


def cast_weights_fp16(buf: bytes) -> bytes:
    """The model with the weights of FULLY_CONNECTED, CONV_2D, DEPTHWISE_CONV_2D and TRANSPOSE_CONV stored as
    float16, each behind an explicit DEQUANTIZE (ai-edge-quantizer's float casting, round to nearest even).
    Activations and every other constant stay float32. Rounding moves a weight by at most 2^-11 (4.9e-4) of itself."""
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


def run_cpu(buf: bytes, x: np.ndarray) -> np.ndarray:
    """The model's output for one input, on the CPU."""
    interpreter = Interpreter(model_content=buf)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], np.ascontiguousarray(x, np.float32))
    interpreter.invoke()
    return interpreter.get_tensor(interpreter.get_output_details()[0]["index"])


def relative_error(a, b) -> np.ndarray:
    """|a - b| per value, relative to b, with 5 % of b's largest magnitude as the floor of the denominator."""
    a, b = np.asarray(a, np.float64).ravel(), np.asarray(b, np.float64).ravel()
    den = np.maximum(np.abs(b), 0.05 * np.abs(b).max())
    return np.abs(a - b) / den


def picture(h: int, w: int) -> np.ndarray:
    """A fixed picture-like input, [1,3,h,w] in [0, 1]: a bright ramp in the upper part, a checkerboard floor in
    perspective below it, two flat boxes. It has the smooth areas, edges and fine texture that uniform noise
    lacks, so a check also sees the network on a second, different kind of input."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    y, x = y / (h - 1), x / (w - 1)
    depth = np.maximum(y - 0.45, 1e-3)
    checker = (np.floor((x - 0.5) / depth * 2) + np.floor(0.5 / depth)) % 2
    lum = np.where(y < 0.45, 0.85 - 0.5 * y, 0.35 + 0.25 * checker)
    lum[int(0.30 * h):int(0.70 * h), int(0.15 * w):int(0.35 * w)] = 0.15
    lum[int(0.40 * h):int(0.80 * h), int(0.60 * w):int(0.85 * w)] = 0.90
    rgb = np.stack([lum, 0.95 * lum, 0.85 * lum])
    return np.clip(rgb, 0, 1).astype(np.float32)[None]


def check(label: str, out, ref, median: float, p99: float, cell: float, largest: float, grid: int = 8) -> None:
    """Print how far `out` is from `ref`, and fail unless every value of `out` is finite and it stays near `ref`
    everywhere: the median relative error, its 99th percentile, the median within each cell of a grid x grid split
    of the output map, and the largest single error. The whole-output median alone passes an output with 40 % of
    its values off by a factor of 100; the percentile fails anything wrong on more than 1 % of the values, the
    cells a region that drifts further than the rest, and the largest error a few scattered values far off. What
    still passes is a drift inside every bound, such as one region 1 % off. The bounds are each recipe's measured
    values with a margin, not derived from how much float16 moves one weight."""
    out, ref = np.asarray(out), np.asarray(ref)
    assert out.shape == ref.shape, f"{label}: shape {out.shape}, expected {ref.shape}"
    bad = int(out.size - np.isfinite(out).sum())
    assert bad == 0, f"{label}: {bad} values are NaN or infinite"
    h, w = out.shape[-2:]
    err = relative_error(out, ref).reshape(-1, h, w)
    cells = [np.median(err[:, i * h // grid:(i + 1) * h // grid, j * w // grid:(j + 1) * w // grid])
             for i in range(grid) for j in range(grid)]
    stats = {"median": float(np.median(err)), "p99": float(np.percentile(err, 99)), "cell": float(max(cells)),
             "largest": float(err.max())}
    print(f"{label}: median relative error {stats['median']:.2e}, p99 {stats['p99']:.2e}, "
          f"worst {grid}x{grid} cell median {stats['cell']:.2e}, max {stats['largest']:.2e}")
    for name, bound in (("median", median), ("p99", p99), ("cell", cell), ("largest", largest)):
        assert stats[name] <= bound, f"{label}: {name} {stats[name]:.2e} over {bound:.0e}"


def contents(buf: bytes) -> tuple[dict, dict]:
    """How many tensors of each type the model holds, and how many operators of each kind, for the printout."""
    from ai_edge_litert import schema_py_generated as S

    m = S.ModelT.InitFromPackedBuf(buf, 0)
    g = m.subgraphs[0]
    type_names = {v: k for k, v in vars(S.TensorType).items() if not k.startswith("_")}
    op_names = {v: k for k, v in vars(S.BuiltinOperator).items() if not k.startswith("_")}
    types, ops = {}, {}
    for t in g.tensors:
        types[type_names[t.type]] = types.get(type_names[t.type], 0) + 1
    for op in g.operators:
        oc = m.operatorCodes[op.opcodeIndex]
        name = op_names[max(oc.builtinCode, oc.deprecatedBuiltinCode)]
        ops[name] = ops.get(name, 0) + 1
    return types, ops
