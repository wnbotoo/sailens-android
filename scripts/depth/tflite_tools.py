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


def summary(err: np.ndarray) -> str:
    return f"median relative error {np.median(err):.2e}, p99 {np.percentile(err, 99):.2e}"


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
