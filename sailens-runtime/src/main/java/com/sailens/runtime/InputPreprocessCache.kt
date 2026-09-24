package com.sailens.runtime

import com.sailens.core.frame.ImageFrame

/**
 * Same-frame sharing of the YUV-to-tensor conversion between models that want the identical input.
 *
 * Guidance starts sem and det on the same frame at the same time, so a plain "look it up, else
 * compute" cache never hits: both look before either has stored. [awaitOrClaim] makes the
 * conversion happen once per frame instead: the first caller claims the key and converts; a second
 * caller with the same key waits (briefly, bounded by [DEFAULT_WAIT_MS]) and copies the result.
 * Waiting does not cost the second model latency it would not otherwise spend: it would be doing
 * the same conversion itself for that time.
 */
class InputPreprocessCache {
    // Same-frame cache only: entries keep provider-owned reusable buffers and are valid until
    // that provider writes the next frame. Consumers must copy values out through copy*Input().
    private var floatEntry: FloatEntry? = null
    private var int8Entry: Int8Entry? = null

    /** The key someone is converting right now, and who. */
    private var claimedKey: Key? = null
    private var claimOwner: Thread? = null

    /**
     * Returns what [tryCopy] finds for [key] -- waiting up to [waitMs] if another caller is
     * converting that key right now -- or null, in which case the caller converts it itself and
     * must call [releaseClaim] afterwards (in a `finally`), whether or not it stored a result.
     */
    fun <T : Any> awaitOrClaim(
        key: Key,
        waitMs: Long = DEFAULT_WAIT_MS,
        tryCopy: () -> T?,
    ): T? = synchronized(this) {
        tryCopy()?.let { return it }
        if (claimedKey == key && claimOwner !== Thread.currentThread()) {
            val deadline = System.nanoTime() + waitMs * 1_000_000L
            while (claimedKey == key) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) break
                (this as Object).wait(remainingMs)
            }
            // Either the producer stored it, or it gave up / took too long and this caller
            // converts on its own without claiming.
            return tryCopy()
        }
        claimedKey = key
        claimOwner = Thread.currentThread()
        null
    }

    /** Ends the calling thread's claim on [key], waking anyone waiting for it. */
    fun releaseClaim(key: Key) = synchronized(this) {
        if (claimedKey == key && claimOwner === Thread.currentThread()) {
            claimedKey = null
            claimOwner = null
            (this as Object).notifyAll()
        }
    }

    @Synchronized
    fun copyFloatInput(
        key: Key,
        outputArray: FloatArray,
    ): InputPreprocessBackend? {
        val entry = floatEntry ?: return null
        if (entry.key != key || entry.values.size != outputArray.size) return null

        entry.values.copyInto(outputArray)
        return entry.backend.asSharedCacheHit()
    }

    @Synchronized
    fun storeFloatInput(
        key: Key,
        inputArray: FloatArray,
        backend: InputPreprocessBackend,
    ) {
        floatEntry = FloatEntry(
            key = key,
            values = inputArray,
            backend = backend,
        )
    }

    @Synchronized
    fun copyInt8Input(
        key: Key,
        quantization: ModelInputQuantization,
        outputArray: ByteArray,
    ): InputPreprocessBackend? {
        val entry = int8Entry ?: return null
        if (
            entry.key != key ||
            entry.quantization != quantization ||
            entry.values.size != outputArray.size
        ) {
            return null
        }

        entry.values.copyInto(outputArray)
        return when (entry.backend) {
            InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV,
            InputPreprocessBackend.SHARED_QUANTIZED_OPENCV_FALLBACK -> entry.backend
            else -> entry.backend.asSharedCacheHit()
        }
    }

    @Synchronized
    fun storeInt8Input(
        key: Key,
        quantization: ModelInputQuantization,
        inputArray: ByteArray,
        backend: InputPreprocessBackend,
    ) {
        int8Entry = Int8Entry(
            key = key,
            quantization = quantization,
            values = inputArray,
            backend = backend,
        )
    }

    @Synchronized
    fun clear() {
        floatEntry = null
        int8Entry = null
    }

    companion object {
        /** Longer than one 640x640 conversion on a phone, short enough never to stall a frame. */
        const val DEFAULT_WAIT_MS: Long = 50L
    }

    data class Key(
        val sequenceNumber: Long,
        val timestamp: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val pixelFormat: String,
        val rotationDegrees: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val inputLayout: ImageTensorLayout,
        val mean: Triple<Float, Float, Float>,
        val std: Triple<Float, Float, Float>,
        val resizeFilter: ResizeFilter,
    ) {
        companion object {
            fun from(
                frame: ImageFrame,
                rotationDegrees: Int,
                config: ModelTensorConfig,
            ): Key {
                return Key(
                    sequenceNumber = frame.sequenceNumber,
                    timestamp = frame.timestamp,
                    sourceWidth = frame.width,
                    sourceHeight = frame.height,
                    pixelFormat = frame.pixelFormat.name,
                    rotationDegrees = rotationDegrees,
                    targetWidth = config.inputWidth,
                    targetHeight = config.inputHeight,
                    inputLayout = config.inputLayout,
                    mean = config.mean,
                    std = config.std,
                    resizeFilter = config.resizeFilter,
                )
            }
        }
    }

    private data class FloatEntry(
        val key: Key,
        val values: FloatArray,
        val backend: InputPreprocessBackend,
    )

    private data class Int8Entry(
        val key: Key,
        val quantization: ModelInputQuantization,
        val values: ByteArray,
        val backend: InputPreprocessBackend,
    )
}
