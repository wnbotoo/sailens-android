"""Keep every token activation rank 3 ([1, N, C]) around FULLY_CONNECTED ops in a .tflite.

The LiteRT 2.1.5 GPU accelerator runs a graph that mixes rank-2 [N, C] and rank-3 [1, N, C] token
tensors (a RESHAPE to [N, C] feeding a FULLY_CONNECTED whose result is added back to [1, N, C]) without
an error and computes it wrongly: it treats N as a batch. logcat shows one
"Shape mismatch ... conv_wave_memory" line per transformer block. Keeping the activations rank 3
throughout fixes it; on the CPU the arithmetic is unchanged, only declared shapes change
(docs/models.md, "The `depth` contract").

1. A RESHAPE whose rank-2 output feeds only FULLY_CONNECTED ops outputs [1, N, C] instead (its shape
   operand and new_shape option follow).
2. A FULLY_CONNECTED with a rank-3 input and a rank-2 output gets keep_num_dims and outputs [1, N, C].

    python patch_rank3.py in.tflite out.tflite
"""
import sys

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as S


def patch(buf: bytes) -> tuple[bytes, int, int]:
    m = S.ModelT.InitFromPackedBuf(buf, 0)

    def code(op):
        oc = m.operatorCodes[op.opcodeIndex]
        return max(oc.builtinCode, oc.deprecatedBuiltinCode)

    g = m.subgraphs[0]
    consumers = {}
    for op in g.operators:
        for i in op.inputs:
            consumers.setdefault(i, []).append(op)

    lifted_reshape = 0
    for op in g.operators:
        if code(op) != S.BuiltinOperator.RESHAPE:
            continue
        out = g.tensors[op.outputs[0]]
        cs = consumers.get(op.outputs[0], [])
        if not (len(out.shape) == 2 and cs and all(code(c) == S.BuiltinOperator.FULLY_CONNECTED for c in cs)):
            continue
        new = [1, int(out.shape[0]), int(out.shape[1])]
        out.shape = new
        if out.shapeSignature is not None and len(out.shapeSignature) == 2:
            out.shapeSignature = [1] + [int(x) for x in out.shapeSignature]
        if op.builtinOptions is not None and getattr(op.builtinOptions, "newShape", None) is not None:
            op.builtinOptions.newShape = new
        if len(op.inputs) > 1 and op.inputs[1] >= 0:
            shape_tensor = g.tensors[op.inputs[1]]
            # The shape operand is shared only with other RESHAPEs, or a new one would change them too.
            assert all(code(c) == S.BuiltinOperator.RESHAPE for c in consumers[op.inputs[1]])
            if len(shape_tensor.shape) == 1 and shape_tensor.shape[0] == 2:
                new_buffer = S.BufferT()
                new_buffer.data = np.array(new, "<i4").tobytes()
                m.buffers.append(new_buffer)
                t = S.TensorT()
                t.shape = [3]
                t.type = shape_tensor.type
                t.buffer = len(m.buffers) - 1
                t.name = (shape_tensor.name or b"") + b"_rank3_%d" % lifted_reshape
                g.tensors.append(t)
                op.inputs = [int(x) for x in op.inputs]
                op.inputs[1] = len(g.tensors) - 1
        lifted_reshape += 1

    lifted_fc = 0
    for op in g.operators:
        if code(op) != S.BuiltinOperator.FULLY_CONNECTED:
            continue
        tin, tout = g.tensors[op.inputs[0]], g.tensors[op.outputs[0]]
        if len(tin.shape) == 3 and len(tout.shape) == 2:
            tout.shape = [1, int(tout.shape[0]), int(tout.shape[1])]
            if tout.shapeSignature is not None and len(tout.shapeSignature) == 2:
                tout.shapeSignature = [1] + [int(x) for x in tout.shapeSignature]
            op.builtinOptions.keepNumDims = True
            lifted_fc += 1

    builder = flatbuffers.Builder(len(buf) + (1 << 20))
    builder.Finish(m.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output()), lifted_reshape, lifted_fc


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    out, reshapes, fcs = patch(open(src, "rb").read())
    open(dst, "wb").write(out)
    print(f"lifted {reshapes} RESHAPE, {fcs} FULLY_CONNECTED -> {dst}")
