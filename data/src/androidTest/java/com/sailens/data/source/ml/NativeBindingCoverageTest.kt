package com.sailens.data.source.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.data.source.mapper.CityscapesClassMapper
import com.sailens.data.source.mapper.CocoClassMapper
import com.sailens.data.source.ml.analysis.NativeConnectivityStatsExtractor
import com.sailens.data.source.ml.obstacle.ObstacleNativePostProcessor
import com.sailens.data.source.ml.semantic.NativeSemanticArgmaxPostprocessor
import com.sailens.data.source.ml.semantic.NativeSemanticScorePostprocessor
import com.sailens.domain.config.AnalysisConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Layer A of the native verification plan (docs/architecture.md §12.2): binding coverage.
 *
 * `libsailens_ml.so` binds its methods in `JNI_OnLoad` with `RegisterNatives` and exports no
 * `Java_<mangled>` symbols, so name-based binding is not merely unused, it is impossible:
 *
 * ```
 * $ llvm-nm -D --defined-only libsailens_ml.so | grep -E 'Java_|JNI_OnLoad'
 * 0000000000008468 T JNI_OnLoad
 * ```
 *
 * That leaves exactly two ways a binding can be wrong, and this class covers both:
 *
 *  - a row in the native table names a class, method or signature that does not exist. The
 *    library then fails to load, [NativeMlLibrary.isAvailable] is false and every test here
 *    fails with the load error attached.
 *  - a Kotlin `external fun` has no row in the native table. It loads fine but throws
 *    [UnsatisfiedLinkError] the first time it is called, which is exactly the migration hazard
 *    RegisterNatives is here to remove. [everyDeclaredNativeMethodIsBound] calls all of them.
 *
 * These are instrumentation tests: the project builds arm64-v8a only, so they need a physical
 * arm64 device and are not part of the 171-test JVM baseline.
 *
 *     ./gradlew.bat :data:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NativeBindingCoverageTest {

    /**
     * The Kotlin-side copy of the RegisterNatives tables in `data/src/main/cpp/sailens_ml_jni.cpp`.
     *
     * A new `external fun` must be added here *and* there; adding it in only one place fails
     * [declaredNativeMethodsMatchTheRegistrationTable] or [everyDeclaredNativeMethodIsBound].
     */
    private val expectedBindings: List<NativeBinding> = listOf(
        NativeBinding(
            NativeYuvInputPreprocessor::class.java,
            "nativePreprocessYuvToFloat",
            "([B[B[BIIIIIIIIIIIFFFFFFI[F)Z",
        ),
        NativeBinding(
            NativeYuvInputPreprocessor::class.java,
            "nativePreprocessYuvToInt8",
            "([B[B[BIIIIIIIIIIIFFFFFFIFI[B)Z",
        ),
        NativeBinding(
            NativeYuvInputPreprocessor::class.java,
            "nativeQuantizeFloatToInt8",
            "([F[BFI)Z",
        ),
        NativeBinding(
            NativeSemanticArgmaxPostprocessor::class.java,
            "nativeArgmaxScores",
            "([F[IIIII)Z",
        ),
        NativeBinding(
            NativeSemanticScorePostprocessor::class.java,
            "nativePostprocessScores",
            "([F[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            NativeSemanticScorePostprocessor::class.java,
            "nativePostprocessInt8Scores",
            "([B[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            NativeSemanticScorePostprocessor::class.java,
            "nativePostprocessScoresFromHandle",
            "(J[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            NativeSemanticScorePostprocessor::class.java,
            "nativePostprocessInt8ScoresFromHandle",
            "(J[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            ObstacleNativePostProcessor::class.java,
            "nativePostProcessRawFloat",
            "([FIIIIIFI[I)[F",
        ),
        NativeBinding(
            ObstacleNativePostProcessor::class.java,
            "nativePostProcessRawInt8",
            "([BIIIIIFIFI[I)[F",
        ),
        NativeBinding(
            ObstacleNativePostProcessor::class.java,
            "nativePostProcessRawFloatFromHandle",
            "(JIIIIIIFI[I)[F",
        ),
        NativeBinding(
            ObstacleNativePostProcessor::class.java,
            "nativePostProcessRawInt8FromHandle",
            "(JIIIIIIFIFI[I)[F",
        ),
        NativeBinding(
            NativeConnectivityStatsExtractor::class.java,
            "nativeExtractConnectivityStats",
            "([JII[FFFFIFFF[I[F)Z",
        ),
    )

    @Test
    fun nativeLibraryLoads() {
        assertTrue(
            "libsailens_ml.so did not load. JNI_OnLoad returns JNI_ERR when a class, method name " +
                "or signature in the RegisterNatives table does not match the Kotlin declarations; " +
                "logcat tag SailensMlJni names the offending class. Cause: " +
                "${NativeMlLibrary.loadError}",
            NativeMlLibrary.isAvailable,
        )
    }

    @Test
    fun declaredNativeMethodsMatchTheRegistrationTable() {
        val declared = expectedBindings
            .map { it.owner }
            .distinct()
            .flatMap { owner -> owner.declaredNativeMethods().map { NativeBinding(owner, it) } }
            .sortedBy { it.key }

        assertEquals(
            "The 13 JNI entry points in docs/architecture.md §12.2 are the migration baseline. " +
                "A changed declaration must be mirrored in the RegisterNatives table in " +
                "data/src/main/cpp/sailens_ml_jni.cpp and in expectedBindings here.",
            expectedBindings.sortedBy { it.key }.map { it.key },
            declared.map { it.key },
        )
    }

    @Test
    fun everyDeclaredNativeMethodIsBound() {
        nativeLibraryLoads()

        for (binding in expectedBindings) {
            val method = binding.owner.declaredNativeMethods()
                .single { it.name == binding.methodName }
            method.isAccessible = true

            // Every kernel rejects null arrays, a zero tensor-buffer handle and zero dimensions in
            // its first guard clause, so this reaches the binding without reaching any real work --
            // including the four handle paths, which A cannot feed a valid LiteRT buffer.
            try {
                method.invoke(binding.receiver(), *method.rejectingArguments())
            } catch (invocation: InvocationTargetException) {
                val cause = invocation.cause
                if (cause is UnsatisfiedLinkError) {
                    fail(
                        "${binding.key} is declared in Kotlin but not registered in " +
                            "data/src/main/cpp/sailens_ml_jni.cpp: $cause",
                    )
                }
                fail("${binding.key} rejected its guard-clause arguments instead of returning: $cause")
            }
        }
    }

    private data class NativeBinding(
        val owner: Class<*>,
        val methodName: String,
        val descriptor: String,
    ) {
        constructor(owner: Class<*>, method: Method) : this(
            owner,
            method.name,
            method.jniDescriptor(),
        )

        /** Stable identity for diffing: `com/sailens/.../Owner#method(descriptor)`. */
        val key: String get() = "${owner.name.replace('.', '/')}#$methodName$descriptor"

        fun receiver(): Any = when (owner) {
            NativeYuvInputPreprocessor::class.java -> NativeYuvInputPreprocessor(
                config = tensorConfig,
                inputQuantization = ModelInputQuantization(),
            )

            NativeSemanticArgmaxPostprocessor::class.java ->
                NativeSemanticArgmaxPostprocessor(config = tensorConfig)

            NativeSemanticScorePostprocessor::class.java -> NativeSemanticScorePostprocessor(
                config = AnalysisConfig(),
                classMapper = CityscapesClassMapper(),
                logService = SilentLogService,
            )

            ObstacleNativePostProcessor::class.java -> ObstacleNativePostProcessor(
                classMapper = CocoClassMapper(),
                inputSize = 640,
                classCount = 80,
                confidenceThreshold = 0.25f,
                maxDetections = 10,
            )

            NativeConnectivityStatsExtractor::class.java -> NativeConnectivityStatsExtractor(
                config = AnalysisConfig(),
                logService = SilentLogService,
            )

            else -> error("No receiver configured for ${owner.name}")
        }
    }

    private companion object {
        val tensorConfig = ModelTensorConfig(
            inputWidth = 8,
            inputHeight = 8,
            outputWidth = 8,
            outputHeight = 8,
            outputChannels = 4,
            mean = Triple(0f, 0f, 0f),
            std = Triple(1f, 1f, 1f),
        )

        fun Class<*>.declaredNativeMethods(): List<Method> =
            declaredMethods.filter { Modifier.isNative(it.modifiers) }.sortedBy { it.name }

        fun Method.jniDescriptor(): String =
            parameterTypes.joinToString(
                separator = "",
                prefix = "(",
                postfix = ")${returnType.typeDescriptor()}",
            ) { it.typeDescriptor() }

        fun Class<*>.typeDescriptor(): String = when (this) {
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Byte.TYPE -> "B"
            java.lang.Character.TYPE -> "C"
            java.lang.Short.TYPE -> "S"
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            java.lang.Double.TYPE -> "D"
            java.lang.Void.TYPE -> "V"
            else -> componentType?.let { "[${it.typeDescriptor()}" }
                ?: "L${name.replace('.', '/')};"
        }

        fun Method.rejectingArguments(): Array<Any?> = parameterTypes.map { type ->
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> 0.toChar()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }.toTypedArray()
    }
}
