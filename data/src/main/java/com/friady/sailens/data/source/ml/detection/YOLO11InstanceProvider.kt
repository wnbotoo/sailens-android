package com.friady.sailens.data.source.ml.detection

//
//
///**
// * YOLO11 实例分割提供者
// */
//class YOLO11InstanceProvider(
//    private val context: Context,
//    private val modelPath: String = "yolo11n_seg.tflite",
//) : InstanceSegmentationProvider {
//
//    override val name: String = "YOLO11n-seg"
//
//    private var interpreter: Interpreter? = null
//    private var gpuDelegate: GpuDelegate? = null
//    private var inputBuffer: ByteBuffer? = null
//
//    private val classMapper = CocoClassMapper()
//
//    private var _isInitialized = false
//    override val isInitialized: Boolean get() = _isInitialized
//
//    companion object {
//        private const val INPUT_SIZE = 640
//        private const val CONFIDENCE_THRESHOLD = 0.4f
//        private const val IOU_THRESHOLD = 0.5f
//    }
//
//    override suspend fun initialize() {
//        if (_isInitialized) return
//
//        try {
//            val modelBuffer = loadModelFile(context, modelPath)
//
//            val options = Interpreter.Options()
//            options.setNumThreads(4)
//
//            try {
//                gpuDelegate = GpuDelegate()
//                options.addDelegate(gpuDelegate)
//            } catch (e: Exception) {
//                gpuDelegate = null
//            }
//
//            interpreter = Interpreter(modelBuffer, options)
//
//            inputBuffer = ByteBuffer.allocateDirect(
//                1 * INPUT_SIZE * INPUT_SIZE * 3 * 4
//            ).apply {
//                order(ByteOrder.nativeOrder())
//            }
//
//            _isInitialized = true
//        } catch (e: Exception) {
//            // 模型文件可能不存在，静默失败
//            _isInitialized = false
//        }
//    }
//
//    override suspend fun detect(frame: ImageFrame): List<DetectedInstance> {
//        if (!_isInitialized) return emptyList()
//
//        val interp = interpreter ?: return emptyList()
//        val input = inputBuffer ?: return emptyList()
//
//        // 预处理
//        preprocessFrame(frame, input)
//
//        // 推理
//        input.rewind()
//        val outputMap = HashMap<Int, Any>()
//        // YOLO 输出格式根据具体模型调整
//        val detectionOutput =
//            Array(1) { Array(25200) { FloatArray(85 + 32) } } // boxes + classes + masks
//        outputMap[0] = detectionOutput
//
//        interp.runForMultipleInputsOutputs(arrayOf(input), outputMap)
//
//        // 后处理
//        return postprocess(detectionOutput[0], frame.width, frame.height)
//    }
//
//    private fun preprocessFrame(frame: ImageFrame, buffer: ByteBuffer) {
//        buffer.rewind()
//
//        val pixels = frame.pixels
//        val scaleX = frame.width.toFloat() / INPUT_SIZE
//        val scaleY = frame.height.toFloat() / INPUT_SIZE
//
//        for (y in 0 until INPUT_SIZE) {
//            for (x in 0 until INPUT_SIZE) {
//                val srcX = (x * scaleX).toInt().coerceIn(0, frame.width - 1)
//                val srcY = (y * scaleY).toInt().coerceIn(0, frame.height - 1)
//                val srcIndex = (srcY * frame.width + srcX) * 4 // RGBA
//
//                val r = (pixels[srcIndex].toInt() and 0xFF) / 255.0f
//                val g = (pixels[srcIndex + 1].toInt() and 0xFF) / 255.0f
//                val b = (pixels[srcIndex + 2].toInt() and 0xFF) / 255.0f
//
//                buffer.putFloat(r)
//                buffer.putFloat(g)
//                buffer.putFloat(b)
//            }
//        }
//    }
//
//    private fun postprocess(
//        output: Array<FloatArray>,
//        originalWidth: Int,
//        originalHeight: Int,
//    ): List<DetectedInstance> {
//        val detections = mutableListOf<DetectedInstance>()
//
//        for (i in output.indices) {
//            val detection = output[i]
//            val confidence = detection[4]
//
//            if (confidence < CONFIDENCE_THRESHOLD) continue
//
//            // 找到最大类别概率
//            var maxClassProb = 0f
//            var maxClassId = 0
//            for (c in 5 until 85) {
//                val classProb = detection[c] * confidence
//                if (classProb > maxClassProb) {
//                    maxClassProb = classProb
//                    maxClassId = c - 5
//                }
//            }
//
//            if (maxClassProb < CONFIDENCE_THRESHOLD) continue
//
//            // 解析边界框 (center_x, center_y, width, height)
//            val cx = detection[0] / INPUT_SIZE
//            val cy = detection[1] / INPUT_SIZE
//            val w = detection[2] / INPUT_SIZE
//            val h = detection[3] / INPUT_SIZE
//
//            val boundingBox = NormalizedRect(
//                x = (cx - w / 2).coerceIn(0f, 1f),
//                y = (cy - h / 2).coerceIn(0f, 1f),
//                width = w.coerceIn(0f, 1f),
//                height = h.coerceIn(0f, 1f)
//            )
//
//            val category = classMapper.toObstacleCategory(maxClassId)
//
//            detections.add(
//                DetectedInstance(
//                    classId = maxClassId,
//                    className = classMapper.getClassName(maxClassId),
//                    confidence = maxClassProb,
//                    boundingBox = boundingBox,
//                    mask = null, // 简化版不解析 mask
//                    category = category
//                )
//            )
//        }
//
//        // NMS
//        return nonMaxSuppression(detections)
//    }
//
//    private fun nonMaxSuppression(detections: List<DetectedInstance>): List<DetectedInstance> {
//        val sorted = detections.sortedByDescending { it.confidence }
//        val result = mutableListOf<DetectedInstance>()
//
//        for (detection in sorted) {
//            var shouldAdd = true
//            for (existing in result) {
//                if (detection.boundingBox.iou(existing.boundingBox) > IOU_THRESHOLD) {
//                    shouldAdd = false
//                    break
//                }
//            }
//            if (shouldAdd) {
//                result.add(detection)
//            }
//        }
//
//        return result
//    }
//
//    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
//        val assetFileDescriptor = context.assets.openFd(filename)
//        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
//        val fileChannel = fileInputStream.channel
//        val startOffset = assetFileDescriptor.startOffset
//        val declaredLength = assetFileDescriptor.declaredLength
//        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
//    }
//
//    override fun release() {
//        interpreter?.close()
//        gpuDelegate?.close()
//        interpreter = null
//        gpuDelegate = null
//        inputBuffer = null
//        _isInitialized = false
//    }
//}
