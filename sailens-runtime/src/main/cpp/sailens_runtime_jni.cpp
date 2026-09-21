#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

constexpr const char* kLogTag = "SailensRuntimeJni";

namespace {

struct YuvPlane {
    const jbyte* data;
    int size;
    int rowStride;
    int pixelStride;
    int width;
    int height;
    int defaultValue;
};

struct RgbPixel {
    float r;
    float g;
    float b;
};


static int unsignedByteAt(const YuvPlane& plane, int x, int y) {
    if (plane.data == nullptr || x < 0 || y < 0 || x >= plane.width || y >= plane.height) {
        return plane.defaultValue;
    }
    const int index = y * plane.rowStride + x * plane.pixelStride;
    if (index < 0 || index >= plane.size) {
        return plane.defaultValue;
    }
    return static_cast<int>(static_cast<unsigned char>(plane.data[index]));
}

static float samplePlaneBilinear(const YuvPlane& plane, float x, float y) {
    if (plane.width <= 0 || plane.height <= 0) {
        return static_cast<float>(plane.defaultValue);
    }

    const float clampedX = std::clamp(x, 0.0f, static_cast<float>(plane.width - 1));
    const float clampedY = std::clamp(y, 0.0f, static_cast<float>(plane.height - 1));
    const int x0 = static_cast<int>(std::floor(clampedX));
    const int y0 = static_cast<int>(std::floor(clampedY));
    const int x1 = std::min(x0 + 1, plane.width - 1);
    const int y1 = std::min(y0 + 1, plane.height - 1);
    const float dx = clampedX - x0;
    const float dy = clampedY - y0;

    const float v00 = static_cast<float>(unsignedByteAt(plane, x0, y0));
    const float v10 = static_cast<float>(unsignedByteAt(plane, x1, y0));
    const float v01 = static_cast<float>(unsignedByteAt(plane, x0, y1));
    const float v11 = static_cast<float>(unsignedByteAt(plane, x1, y1));
    const float top = v00 + (v10 - v00) * dx;
    const float bottom = v01 + (v11 - v01) * dx;
    return top + (bottom - top) * dy;
}

static float samplePlaneNearest(const YuvPlane& plane, float x, float y) {
    if (plane.width <= 0 || plane.height <= 0) {
        return static_cast<float>(plane.defaultValue);
    }

    const int nearestX = std::clamp(
            static_cast<int>(x + 0.5f),
            0,
            plane.width - 1);
    const int nearestY = std::clamp(
            static_cast<int>(y + 0.5f),
            0,
            plane.height - 1);
    return static_cast<float>(unsignedByteAt(plane, nearestX, nearestY));
}

static RgbPixel yuvToRgb(float yValue, float uValue, float vValue) {
    const float c = std::max(yValue - 16.0f, 0.0f);
    const float d = uValue - 128.0f;
    const float e = vValue - 128.0f;
    return {
            std::clamp(1.164f * c + 1.596f * e, 0.0f, 255.0f),
            std::clamp(1.164f * c - 0.392f * d - 0.813f * e, 0.0f, 255.0f),
            std::clamp(1.164f * c + 2.017f * d, 0.0f, 255.0f),
    };
}

static RgbPixel sampleYuvAsRgb(
        const YuvPlane& yPlane,
        const YuvPlane& uPlane,
        const YuvPlane& vPlane,
        float sourceX,
        float sourceY) {
    const float yValue = samplePlaneBilinear(yPlane, sourceX, sourceY);
    const float uValue = samplePlaneBilinear(uPlane, sourceX * 0.5f, sourceY * 0.5f);
    const float vValue = samplePlaneBilinear(vPlane, sourceX * 0.5f, sourceY * 0.5f);
    return yuvToRgb(yValue, uValue, vValue);
}

static RgbPixel sampleYuvAsRgbNearest(
        const YuvPlane& yPlane,
        const YuvPlane& uPlane,
        const YuvPlane& vPlane,
        float sourceX,
        float sourceY) {
    const float yValue = samplePlaneNearest(yPlane, sourceX, sourceY);
    const float uValue = samplePlaneNearest(uPlane, sourceX * 0.5f, sourceY * 0.5f);
    const float vValue = samplePlaneNearest(vPlane, sourceX * 0.5f, sourceY * 0.5f);
    return yuvToRgb(yValue, uValue, vValue);
}

template <typename Writer>
static bool preprocessYuv(
        const YuvPlane& yPlane,
        const YuvPlane& uPlane,
        const YuvPlane& vPlane,
        int sourceWidth,
        int sourceHeight,
        int rotationDegrees,
        int targetWidth,
        int targetHeight,
        float meanR,
        float meanG,
        float meanB,
        float stdR,
        float stdG,
        float stdB,
        int resizeFilter,
        Writer writer) {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0 ||
        stdR == 0.0f || stdG == 0.0f || stdB == 0.0f) {
        return false;
    }

    const int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
    const bool rotated = normalizedRotation == 90 || normalizedRotation == 270;
    const int rotatedWidth = rotated ? sourceHeight : sourceWidth;
    const int rotatedHeight = rotated ? sourceWidth : sourceHeight;
    const float scale = std::min(
            targetWidth / static_cast<float>(rotatedWidth),
            targetHeight / static_cast<float>(rotatedHeight));
    const float resizedWidth = rotatedWidth * scale;
    const float resizedHeight = rotatedHeight * scale;
    const float padX = (targetWidth - resizedWidth) * 0.5f;
    const float padY = (targetHeight - resizedHeight) * 0.5f;

    const int activeStartX = std::clamp(static_cast<int>(std::ceil(padX)), 0, targetWidth);
    const int activeEndX = std::clamp(static_cast<int>(std::ceil(padX + resizedWidth)), activeStartX, targetWidth);
    const int activeStartY = std::clamp(static_cast<int>(std::ceil(padY)), 0, targetHeight);
    const int activeEndY = std::clamp(static_cast<int>(std::ceil(padY + resizedHeight)), activeStartY, targetHeight);
    const float padR = (0.0f - meanR) / stdR;
    const float padG = (0.0f - meanG) / stdG;
    const float padB = (0.0f - meanB) / stdB;
    const bool useNearestResize = resizeFilter == 0;

    auto writePadPixel = [&writer, padR, padG, padB](int& outIndex) {
        writer(outIndex++, padR);
        writer(outIndex++, padG);
        writer(outIndex++, padB);
    };

    for (int y = 0; y < targetHeight; ++y) {
        int outIndex = y * targetWidth * 3;

        if (y < activeStartY || y >= activeEndY) {
            for (int x = 0; x < targetWidth; ++x) {
                writePadPixel(outIndex);
            }
            continue;
        }

        for (int x = 0; x < activeStartX; ++x) {
            writePadPixel(outIndex);
        }

        const float rotatedY = (static_cast<float>(y) - padY + 0.5f) / scale - 0.5f;
        for (int x = activeStartX; x < activeEndX; ++x) {
            const float rotatedX = (static_cast<float>(x) - padX + 0.5f) / scale - 0.5f;
            float sourceX = 0.0f;
            float sourceY = 0.0f;
            switch (normalizedRotation) {
                case 90:
                    sourceX = rotatedY;
                    sourceY = static_cast<float>(sourceHeight - 1) - rotatedX;
                    break;
                case 180:
                    sourceX = static_cast<float>(sourceWidth - 1) - rotatedX;
                    sourceY = static_cast<float>(sourceHeight - 1) - rotatedY;
                    break;
                case 270:
                    sourceX = static_cast<float>(sourceWidth - 1) - rotatedY;
                    sourceY = rotatedX;
                    break;
                default:
                    sourceX = rotatedX;
                    sourceY = rotatedY;
                    break;
            }
            const RgbPixel pixel = useNearestResize
                    ? sampleYuvAsRgbNearest(yPlane, uPlane, vPlane, sourceX, sourceY)
                    : sampleYuvAsRgb(yPlane, uPlane, vPlane, sourceX, sourceY);
            writer(outIndex++, (pixel.r / 255.0f - meanR) / stdR);
            writer(outIndex++, (pixel.g / 255.0f - meanG) / stdG);
            writer(outIndex++, (pixel.b / 255.0f - meanB) / stdB);
        }

        for (int x = activeEndX; x < targetWidth; ++x) {
            writePadPixel(outIndex);
        }
    }

    return true;
}

// ---------------------------------------------------------------------------
// JNI entry points.
//
// These deliberately stay inside the anonymous namespace: they have internal
// linkage, so no Java_<mangled> symbol is exported and the runtime cannot bind
// them by name. The only binding is the RegisterNatives table at the bottom of
// this file, which fails the library load when a class, method name or
// signature does not match. That is what lets Kotlin classes move packages
// without a rename silently surviving until the method is first called.
// ---------------------------------------------------------------------------

jboolean JNICALL
nativePreprocessYuvToFloat(
        JNIEnv* env,
        jobject,
        jbyteArray y,
        jbyteArray u,
        jbyteArray v,
        jint yRowStride,
        jint yPixelStride,
        jint uRowStride,
        jint uPixelStride,
        jint vRowStride,
        jint vPixelStride,
        jint sourceWidth,
        jint sourceHeight,
        jint rotationDegrees,
        jint targetWidth,
        jint targetHeight,
        jfloat meanR,
        jfloat meanG,
        jfloat meanB,
        jfloat stdR,
        jfloat stdG,
        jfloat stdB,
        jint resizeFilter,
        jfloatArray output) {
    if (y == nullptr || u == nullptr || v == nullptr || output == nullptr ||
        yRowStride <= 0 || yPixelStride <= 0 ||
        uRowStride <= 0 || uPixelStride <= 0 ||
        vRowStride <= 0 || vPixelStride <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        targetWidth <= 0 || targetHeight <= 0 ||
        env->GetArrayLength(output) != targetWidth * targetHeight * 3) {
        return JNI_FALSE;
    }

    jbyte* yData = env->GetByteArrayElements(y, nullptr);
    jbyte* uData = env->GetByteArrayElements(u, nullptr);
    jbyte* vData = env->GetByteArrayElements(v, nullptr);
    jfloat* outputData = env->GetFloatArrayElements(output, nullptr);
    if (yData == nullptr || uData == nullptr || vData == nullptr || outputData == nullptr) {
        if (yData != nullptr) env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
        if (uData != nullptr) env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
        if (vData != nullptr) env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
        if (outputData != nullptr) env->ReleaseFloatArrayElements(output, outputData, JNI_ABORT);
        return JNI_FALSE;
    }

    const YuvPlane yPlane = {
            yData,
            env->GetArrayLength(y),
            yRowStride,
            yPixelStride,
            sourceWidth,
            sourceHeight,
            16,
    };
    const YuvPlane uPlane = {
            uData,
            env->GetArrayLength(u),
            uRowStride,
            uPixelStride,
            (sourceWidth + 1) / 2,
            (sourceHeight + 1) / 2,
            128,
    };
    const YuvPlane vPlane = {
            vData,
            env->GetArrayLength(v),
            vRowStride,
            vPixelStride,
            (sourceWidth + 1) / 2,
            (sourceHeight + 1) / 2,
            128,
    };

    const bool success = preprocessYuv(
            yPlane,
            uPlane,
            vPlane,
            sourceWidth,
            sourceHeight,
            rotationDegrees,
            targetWidth,
            targetHeight,
            meanR,
            meanG,
            meanB,
            stdR,
            stdG,
            stdB,
            resizeFilter,
            [outputData](int index, float value) {
                outputData[index] = value;
            });

    env->ReleaseFloatArrayElements(output, outputData, success ? 0 : JNI_ABORT);
    env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
    env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

jboolean JNICALL
nativePreprocessYuvToInt8(
        JNIEnv* env,
        jobject,
        jbyteArray y,
        jbyteArray u,
        jbyteArray v,
        jint yRowStride,
        jint yPixelStride,
        jint uRowStride,
        jint uPixelStride,
        jint vRowStride,
        jint vPixelStride,
        jint sourceWidth,
        jint sourceHeight,
        jint rotationDegrees,
        jint targetWidth,
        jint targetHeight,
        jfloat meanR,
        jfloat meanG,
        jfloat meanB,
        jfloat stdR,
        jfloat stdG,
        jfloat stdB,
        jint resizeFilter,
        jfloat quantScale,
        jint quantZeroPoint,
        jbyteArray output) {
    if (y == nullptr || u == nullptr || v == nullptr || output == nullptr ||
        yRowStride <= 0 || yPixelStride <= 0 ||
        uRowStride <= 0 || uPixelStride <= 0 ||
        vRowStride <= 0 || vPixelStride <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        targetWidth <= 0 || targetHeight <= 0 ||
        quantScale <= 0.0f ||
        env->GetArrayLength(output) != targetWidth * targetHeight * 3) {
        return JNI_FALSE;
    }

    jbyte* yData = env->GetByteArrayElements(y, nullptr);
    jbyte* uData = env->GetByteArrayElements(u, nullptr);
    jbyte* vData = env->GetByteArrayElements(v, nullptr);
    jbyte* outputData = env->GetByteArrayElements(output, nullptr);
    if (yData == nullptr || uData == nullptr || vData == nullptr || outputData == nullptr) {
        if (yData != nullptr) env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
        if (uData != nullptr) env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
        if (vData != nullptr) env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
        if (outputData != nullptr) env->ReleaseByteArrayElements(output, outputData, JNI_ABORT);
        return JNI_FALSE;
    }

    const YuvPlane yPlane = {
            yData,
            env->GetArrayLength(y),
            yRowStride,
            yPixelStride,
            sourceWidth,
            sourceHeight,
            16,
    };
    const YuvPlane uPlane = {
            uData,
            env->GetArrayLength(u),
            uRowStride,
            uPixelStride,
            (sourceWidth + 1) / 2,
            (sourceHeight + 1) / 2,
            128,
    };
    const YuvPlane vPlane = {
            vData,
            env->GetArrayLength(v),
            vRowStride,
            vPixelStride,
            (sourceWidth + 1) / 2,
            (sourceHeight + 1) / 2,
            128,
    };

    const float inverseQuantScale = 1.0f / quantScale;
    const bool success = preprocessYuv(
            yPlane,
            uPlane,
            vPlane,
            sourceWidth,
            sourceHeight,
            rotationDegrees,
            targetWidth,
            targetHeight,
            meanR,
            meanG,
            meanB,
            stdR,
            stdG,
            stdB,
            resizeFilter,
            [outputData, inverseQuantScale, quantZeroPoint](int index, float value) {
                const int quantized = static_cast<int>(std::lround(value * inverseQuantScale + quantZeroPoint));
                outputData[index] = static_cast<jbyte>(std::clamp(quantized, -128, 127));
            });

    env->ReleaseByteArrayElements(output, outputData, success ? 0 : JNI_ABORT);
    env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
    env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}


// Native float -> int8 quantization (q = round(value / scale + zeroPoint), clamped). Replaces a hot
// Kotlin per-element loop on the shared-preprocess-cache path (cached FLOAT input reused as INT8).
jboolean JNICALL
nativeQuantizeFloatToInt8(
        JNIEnv* env,
        jobject,
        jfloatArray input,
        jbyteArray output,
        jfloat quantScale,
        jint quantZeroPoint) {
    if (input == nullptr || output == nullptr || quantScale <= 0.0f) {
        return JNI_FALSE;
    }
    const jsize size = env->GetArrayLength(input);
    if (size <= 0 || env->GetArrayLength(output) != size) {
        return JNI_FALSE;
    }

    jfloat* in = env->GetFloatArrayElements(input, nullptr);
    jbyte* out = env->GetByteArrayElements(output, nullptr);
    if (in == nullptr || out == nullptr) {
        if (in != nullptr) env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
        if (out != nullptr) env->ReleaseByteArrayElements(output, out, JNI_ABORT);
        return JNI_FALSE;
    }

    const float inverseScale = 1.0f / quantScale;
    for (jsize i = 0; i < size; ++i) {
        const int quantized = static_cast<int>(std::lround(in[i] * inverseScale + quantZeroPoint));
        out[i] = static_cast<jbyte>(std::clamp(quantized, -128, 127));
    }

    env->ReleaseFloatArrayElements(input, in, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out, 0);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// Registration table.
//
// One row per Kotlin `external fun`. RegisterNatives resolves each row against
// the class by name *and* JNI signature, so a typo here is a load failure on
// the device, not an UnsatisfiedLinkError thrown from a rare code path months
// later. The class name is the R8-kept name; see
// sailens-runtime/consumer-rules.pro.
// ---------------------------------------------------------------------------

const JNINativeMethod kYuvInputPreprocessorMethods[] = {
        {"nativePreprocessYuvToFloat",
         "([B[B[BIIIIIIIIIIIFFFFFFI[F)Z",
         reinterpret_cast<void*>(nativePreprocessYuvToFloat)},
        {"nativePreprocessYuvToInt8",
         "([B[B[BIIIIIIIIIIIFFFFFFIFI[B)Z",
         reinterpret_cast<void*>(nativePreprocessYuvToInt8)},
        {"nativeQuantizeFloatToInt8",
         "([F[BFI)Z",
         reinterpret_cast<void*>(nativeQuantizeFloatToInt8)},
};

struct NativeClassBinding {
    const char* className;
    const JNINativeMethod* methods;
    jint methodCount;
};

template <typename T, std::size_t N>
constexpr jint methodCountOf(const T (&)[N]) {
    return static_cast<jint>(N);
}

const NativeClassBinding kNativeClassBindings[] = {
        {"com/sailens/runtime/NativeYuvInputPreprocessor",
         kYuvInputPreprocessorMethods,
         methodCountOf(kYuvInputPreprocessorMethods)},
};

bool registerClassNatives(JNIEnv* env, const NativeClassBinding& binding) {
    jclass clazz = env->FindClass(binding.className);
    if (clazz == nullptr) {
        // FindClass left a pending NoClassDefFoundError. Clear it so the caller
        // sees the JNI_ERR from JNI_OnLoad rather than a stale exception.
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "JNI registration failed: class not found: %s",
                            binding.className);
        return false;
    }

    const jint result = env->RegisterNatives(clazz, binding.methods, binding.methodCount);
    env->DeleteLocalRef(clazz);
    if (result != JNI_OK) {
        // RegisterNatives raises NoSuchMethodError for an unknown name or a
        // mismatched signature.
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "JNI registration failed for %s (%d methods), result %d",
                            binding.className, binding.methodCount, result);
        return false;
    }
    return true;
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "JNI_OnLoad: no JNIEnv for JNI 1.6");
        return JNI_ERR;
    }

    for (const NativeClassBinding& binding : kNativeClassBindings) {
        if (!registerClassNatives(env, binding)) {
            // Fail the whole load. A partially bound library would leave the
            // unbound methods to throw UnsatisfiedLinkError at their first call,
            // which is exactly the failure mode RegisterNatives exists to avoid.
            return JNI_ERR;
        }
    }
    return JNI_VERSION_1_6;
}
