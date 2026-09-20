package com.sailens.guidance.kernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.guidance.semantics.CocoNavigationSemantics
import com.sailens.guidance.config.AnalysisConfig
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
 * The native code split three ways in gate G3, so layer A split with it: this covers the vision
 * and Guidance kernels that stayed in :data, and NativeRuntimeBindingCoverageTest in
 * sailens-runtime covers the preprocessing kernels that moved. Both libraries keep the same
 * guarantee.
 *
 * `libsailens_guidance.so` binds its methods in `JNI_OnLoad` with `RegisterNatives` and exports no
 * `Java_<mangled>` symbols, so name-based binding is not merely unused, it is impossible:
 *
 * ```
 * $ llvm-nm -D --defined-only libsailens_guidance.so | grep -E 'Java_|JNI_OnLoad'
 * 0000000000008468 T JNI_OnLoad
 * ```
 *
 * That leaves exactly two ways a binding can be wrong, and this class covers both:
 *
 *  - a row in the native table names a class, method or signature that does not exist. The
 *    library then fails to load, [NativeGuidanceLibrary.isAvailable] is false and every test here
 *    fails with the load error attached.
 *  - a Kotlin `external fun` has no row in the native table. It loads fine but throws
 *    [UnsatisfiedLinkError] the first time it is called, which is exactly the migration hazard
 *    RegisterNatives is here to remove. [everyDeclaredNativeMethodIsBound] calls all of them.
 *
 * These are instrumentation tests: the project builds arm64-v8a only, so they need a physical
 * arm64 device and are not part of the 171-test JVM baseline.
 *
 *     ./gradlew.bat :sailens-guidance:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NativeGuidanceBindingCoverageTest {

    /**
     * The Kotlin-side copy of the RegisterNatives tables in `sailens-guidance/src/main/cpp/sailens_guidance_jni.cpp`.
     *
     * A new `external fun` must be added here *and* there; adding it in only one place fails
     * [declaredNativeMethodsMatchTheRegistrationTable] or [everyDeclaredNativeMethodIsBound].
     */
    private val expectedBindings: List<NativeBinding> = listOf(
        NativeBinding(
            NavigationScorePostprocessor::class.java,
            "nativePostprocessScores",
            "([F[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            NavigationScorePostprocessor::class.java,
            "nativePostprocessInt8Scores",
            "([B[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            NavigationScorePostprocessor::class.java,
            "nativePostprocessScoresFromHandle",
            "(J[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
        ),
        NativeBinding(
            NavigationScorePostprocessor::class.java,
            "nativePostprocessInt8ScoresFromHandle",
            "(J[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
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
            "libsailens_guidance.so did not load. JNI_OnLoad returns JNI_ERR when a class, method name " +
                "or signature in the RegisterNatives table does not match the Kotlin declarations; " +
                "logcat tag SailensGuidanceJni names the offending class. Cause: " +
                "${NativeGuidanceLibrary.loadError}",
            NativeGuidanceLibrary.isAvailable,
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
            "These are the 5 of the migration baseline's 13 JNI entry points still in :data after G3 " +
                "split the preprocessing kernels into sailens-runtime and the vision kernels into " +
                "sailens-vision. A changed " +
                "declaration must be mirrored in the RegisterNatives table in " +
                "sailens-guidance/src/main/cpp/sailens_guidance_jni.cpp and in expectedBindings here.",
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
                            "sailens-guidance/src/main/cpp/sailens_guidance_jni.cpp: $cause",
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
            NavigationScorePostprocessor::class.java -> NavigationScorePostprocessor(
                config = AnalysisConfig(),
                navigationSemantics = CityscapesNavigationSemantics,
                logService = SilentLogService,
            )

            NativeConnectivityStatsExtractor::class.java -> NativeConnectivityStatsExtractor(
                config = AnalysisConfig(),
                logService = SilentLogService,
            )

            else -> error("No receiver configured for ${owner.name}")
        }
    }

    private companion object {
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
