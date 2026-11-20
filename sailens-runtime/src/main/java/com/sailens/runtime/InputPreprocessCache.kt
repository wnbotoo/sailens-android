package com.sailens.runtime

import com.sailens.core.frame.ImageFrame
import java.lang.ref.WeakReference

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
    private var reservedProducer: PreferredProducer? = null

    /** Same cache instance and identical resolved float preprocessing for one frame. */
    data class SharedFloatInput(
        private val cache: InputPreprocessCache,
        private val key: Key,
        private val preferNativeYuv: Boolean,
    ) {
        fun reserveProducer(): PreferredProducer? = cache.reserveProducer(key)
    }

    class PreferredProducer internal constructor(
        private val cache: InputPreprocessCache,
        internal val key: Key,
    ) : AutoCloseable {
        override fun close() = cache.releaseReservation(this)
    }

    @Synchronized
    private fun reserveProducer(key: Key): PreferredProducer? {
        if (reservedProducer != null || claimedKey != null) return null
        return PreferredProducer(this, key).also { reservedProducer = it }
    }

    private fun releaseReservation(producer: PreferredProducer) = synchronized(this) {
        if (reservedProducer === producer) {
            reservedProducer = null
            (this as Object).notifyAll()
        }
    }

    /**
     * Returns what [tryCopy] finds for [key] -- waiting up to [waitMs] if another caller is
     * converting or has a reservation for that key -- or null, in which case the caller converts
     * it itself and must call [releaseClaim] in a `finally`, whether or not it stored a result.
     */
    fun <T : Any> awaitOrClaim(
        key: Key,
        waitMs: Long = DEFAULT_WAIT_MS,
        timing: InputPreprocessTiming? = null,
        preferredProducer: PreferredProducer? = null,
        tryCopy: () -> T?,
    ): T? {
        val entering = timing?.now() ?: 0L
        return synchronized(this) {
            timing?.waitedSince(entering)
            if (preferredProducer != null && preferredProducer.key == key) {
                // The owner is here. Handoff to the ordinary claim happens under the same lock.
                releaseReservation(preferredProducer)
            }
            tryCopy()?.let { return it }
            if (reservedProducer?.key == key || (claimedKey == key && claimOwner !== Thread.currentThread())) {
                val deadline = System.nanoTime() + waitMs * 1_000_000L
                val waiting = timing?.now() ?: 0L
                try {
                    while (claimedKey == key || reservedProducer?.key == key) {
                        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0) break
                        (this as Object).wait(remainingMs)
                    }
                } finally {
                    timing?.waitedSince(waiting)
                }
                // The producer stored it, gave up, or ran out of time.
                return tryCopy()
            }
            claimedKey = key
            claimOwner = Thread.currentThread()
            null
        }
    }

    /** Ends the calling thread's claim on [key], waking anyone waiting for it. */
    fun releaseClaim(key: Key) = synchronized(this) {
        if (claimedKey == key && claimOwner === Thread.currentThread()) {
            claimedKey = null
            claimOwner = null
            (this as Object).notifyAll()
        }
    }

    fun copyFloatInput(
        key: Key,
        outputArray: FloatArray,
    ): InputPreprocessBackend? = copyFloatWithMapping(key, outputArray, null)?.backend

    @Synchronized
    internal fun copyFloatWithMapping(
        key: Key,
        outputArray: FloatArray,
        frame: ImageFrame?,
        timing: InputPreprocessTiming? = null,
    ): PreparedImage? {
        val entry = floatEntry ?: return null
        if (entry.key != key || entry.values.size != outputArray.size) return null

        val copying = timing?.now() ?: 0L
        entry.values.copyInto(outputArray)
        timing?.copiedSince(copying)
        return PreparedImage(
            entry.backend.asSharedCacheHit(),
            entry.mapping.takeIf { entry.frame?.get() === frame && frame != null },
        )
    }

    @Synchronized
    fun storeFloatInput(
        key: Key,
        inputArray: FloatArray,
        backend: InputPreprocessBackend,
        mapping: InputImageMapping? = null,
        frame: ImageFrame? = null,
    ) {
        floatEntry = FloatEntry(
            key = key,
            values = inputArray,
            backend = backend,
            mapping = mapping,
            frame = frame?.let(::WeakReference),
        )
    }

    fun copyInt8Input(
        key: Key,
        quantization: ModelInputQuantization,
        outputArray: ByteArray,
    ): InputPreprocessBackend? = copyInt8WithMapping(key, quantization, outputArray, null)?.backend

    @Synchronized
    internal fun copyInt8WithMapping(
        key: Key,
        quantization: ModelInputQuantization,
        outputArray: ByteArray,
        frame: ImageFrame?,
        timing: InputPreprocessTiming? = null,
    ): PreparedImage? {
        val entry = int8Entry ?: return null
        if (
            entry.key != key ||
            entry.quantization != quantization ||
            entry.values.size != outputArray.size
        ) {
            return null
        }

        val copying = timing?.now() ?: 0L
        entry.values.copyInto(outputArray)
        timing?.copiedSince(copying)
        val backend = when (entry.backend) {
            InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV,
            InputPreprocessBackend.SHARED_QUANTIZED_OPENCV_FALLBACK -> entry.backend
            else -> entry.backend.asSharedCacheHit()
        }
        return PreparedImage(backend, entry.mapping.takeIf { entry.frame?.get() === frame && frame != null })
    }

    @Synchronized
    fun storeInt8Input(
        key: Key,
        quantization: ModelInputQuantization,
        inputArray: ByteArray,
        backend: InputPreprocessBackend,
        mapping: InputImageMapping? = null,
        frame: ImageFrame? = null,
    ) {
        int8Entry = Int8Entry(
            key = key,
            quantization = quantization,
            values = inputArray,
            backend = backend,
            mapping = mapping,
            frame = frame?.let(::WeakReference),
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
        val mapping: InputImageMapping?,
        val frame: WeakReference<ImageFrame>?,
    )

    private data class Int8Entry(
        val key: Key,
        val quantization: ModelInputQuantization,
        val values: ByteArray,
        val backend: InputPreprocessBackend,
        val mapping: InputImageMapping?,
        val frame: WeakReference<ImageFrame>?,
    )
}
