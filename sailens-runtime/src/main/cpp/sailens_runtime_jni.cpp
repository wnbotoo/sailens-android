#include <jni.h>

#include <android/log.h>
#include <time.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <memory>
#include <new>
#include <type_traits>
#include <vector>

#include "tensor_layout.h"

constexpr const char* kLogTag = "SailensRuntimeJni";

namespace {

constexpr int kRgbChannels = 3;

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
    // Preserve the scalar kernel's rounding when this is inlined into different output layouts.
#pragma clang fp contract(off) reassociate(off)
    const float c = std::max(yValue - 16.0f, 0.0f);
    const float d = uValue - 128.0f;
    const float e = vValue - 128.0f;
    const float luma = 1.164f * c;
    const float greenChroma = std::fma(-0.392f, d, -0.813f * e);
    return {
            std::clamp(std::fma(1.596f, e, luma), 0.0f, 255.0f),
            std::clamp(luma + greenChroma, 0.0f, 255.0f),
            std::clamp(std::fma(2.017f, d, luma), 0.0f, 255.0f),
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

struct NearestOffsets {
    jlong y;
    jlong u;
    jlong v;
};

static jlong nearestAxisOffset(const YuvPlane& plane, float coordinate, bool sourceRow) {
    const int extent = sourceRow ? plane.height : plane.width;
    const int stride = sourceRow ? plane.rowStride : plane.pixelStride;
    if (extent <= 0) return plane.size;
    const int index = std::clamp(static_cast<int>(coordinate + 0.5f), 0, extent - 1);
    return static_cast<jlong>(index) * stride;
}

static void fillNearestOffsets(
        NearestOffsets* offsets,
        int first,
        int end,
        float pad,
        float scale,
        bool sourceRow,
        bool reverse,
        const YuvPlane& yPlane,
        const YuvPlane& uPlane,
        const YuvPlane& vPlane) {
    const float last = static_cast<float>((sourceRow ? yPlane.height : yPlane.width) - 1);
    for (int i = first; i < end; ++i) {
        const float rotated = (static_cast<float>(i) - pad + 0.5f) / scale - 0.5f;
        const float source = reverse ? last - rotated : rotated;
        // Chroma is rounded from its own source coordinate.
        const float chroma = source * 0.5f;
        offsets[i - first] = {
                nearestAxisOffset(yPlane, source, sourceRow),
                nearestAxisOffset(uPlane, chroma, sourceRow),
                nearestAxisOffset(vPlane, chroma, sourceRow),
        };
    }
}

static float sampleAtOffset(const YuvPlane& plane, jlong offset) {
    return offset < plane.size
            ? static_cast<float>(static_cast<unsigned char>(plane.data[offset]))
            : static_cast<float>(plane.defaultValue);
}

template <bool Planar = false, bool IndexedNearest = false, typename Writer>
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
        float (&mapping)[7],
        Writer writer,
        NearestOffsets* offsets = nullptr) {
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
    mapping[0] = scale;
    mapping[1] = padX;
    mapping[2] = padY;
    mapping[3] = static_cast<float>(activeStartX);
    mapping[4] = static_cast<float>(activeEndX);
    mapping[5] = static_cast<float>(activeStartY);
    mapping[6] = static_cast<float>(activeEndY);
    const RgbPixel pad{(0.0f - meanR) / stdR, (0.0f - meanG) / stdG, (0.0f - meanB) / stdB};
    const bool useNearestResize = resizeFilter == 0;
    NearestOffsets* rows = nullptr;
    if constexpr (IndexedNearest) {
        rows = offsets + (activeEndX - activeStartX);
        const bool reverseX = normalizedRotation == 90 || normalizedRotation == 180;
        const bool reverseY = normalizedRotation == 180 || normalizedRotation == 270;
        fillNearestOffsets(offsets, activeStartX, activeEndX, padX, scale, rotated, reverseX, yPlane, uPlane, vPlane);
        fillNearestOffsets(rows, activeStartY, activeEndY, padY, scale, !rotated, reverseY, yPlane, uPlane, vPlane);
    }

    constexpr int pixelStride = Planar ? 1 : kRgbChannels;
    const int channelStride = Planar ? targetWidth * targetHeight : 1;
    const auto writePixel = [&writer, channelStride](int& outIndex, const RgbPixel& rgb) {
        writer(outIndex, rgb.r);
        writer(outIndex + channelStride, rgb.g);
        writer(outIndex + 2 * channelStride, rgb.b);
        outIndex += pixelStride;
    };

    for (int y = 0; y < targetHeight; ++y) {
        int outIndex = y * targetWidth * pixelStride;

        if (y < activeStartY || y >= activeEndY) {
            for (int x = 0; x < targetWidth; ++x) {
                writePixel(outIndex, pad);
            }
            continue;
        }

        for (int x = 0; x < activeStartX; ++x) {
            writePixel(outIndex, pad);
        }

        const float rotatedY = (static_cast<float>(y) - padY + 0.5f) / scale - 0.5f;
        for (int x = activeStartX; x < activeEndX; ++x) {
            RgbPixel pixel;
            if constexpr (IndexedNearest) {
                const NearestOffsets& row = rows[y - activeStartY];
                const NearestOffsets& column = offsets[x - activeStartX];
                pixel = yuvToRgb(
                        sampleAtOffset(yPlane, row.y + column.y),
                        sampleAtOffset(uPlane, row.u + column.u),
                        sampleAtOffset(vPlane, row.v + column.v));
            } else {
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
                pixel = useNearestResize
                        ? sampleYuvAsRgbNearest(yPlane, uPlane, vPlane, sourceX, sourceY)
                        : sampleYuvAsRgb(yPlane, uPlane, vPlane, sourceX, sourceY);
            }
            writePixel(outIndex, {
                    (pixel.r / 255.0f - meanR) / stdR,
                    (pixel.g / 255.0f - meanG) / stdG,
                    (pixel.b / 255.0f - meanB) / stdB,
            });
        }

        for (int x = activeEndX; x < targetWidth; ++x) {
            writePixel(outIndex, pad);
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

class YuvPreprocessTiming {
public:
    YuvPreprocessTiming(JNIEnv* env, jlongArray destination)
        : destination_(destination != nullptr && env->GetArrayLength(destination) >= kSampleSize
                      ? destination : nullptr) {}

    jlong now() const {
        if (destination_ == nullptr) return 0;
        timespec time{};
        clock_gettime(CLOCK_BOOTTIME, &time);
        return static_cast<jlong>(time.tv_sec) * 1'000'000'000 + time.tv_nsec;
    }

    jboolean* isCopy(int index) {
        return destination_ == nullptr ? nullptr : &copied_[index];
    }

    void record(JNIEnv* env, jlong getNanos, jlong releaseNanos, jlong bodyNanos) const {
        if (destination_ == nullptr) return;
        // The flags follow the acquisitions in Y, U, V, output order.
        const jlong copyMask = copied_[0] | (copied_[1] << 1) | (copied_[2] << 2) | (copied_[3] << 3);
        const jlong sample[] = {getNanos, releaseNanos, bodyNanos, copyMask};
        env->SetLongArrayRegion(destination_, 0, kSampleSize, sample);
    }

private:
    static constexpr jsize kSampleSize = 4;
    jlongArray destination_;
    jboolean copied_[4]{};
};

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
        jint outputLayout,
        jboolean precomputeNearestIndices,
        jfloatArray output,
        jfloatArray mapping,
        jlongArray timingNanos) {
    if (y == nullptr || u == nullptr || v == nullptr || output == nullptr ||
        mapping == nullptr || env->GetArrayLength(mapping) != 7 ||
        yRowStride <= 0 || yPixelStride <= 0 ||
        uRowStride <= 0 || uPixelStride <= 0 ||
        vRowStride <= 0 || vPixelStride <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        targetWidth <= 0 || targetHeight <= 0 ||
        !sailens::isValidTensorLayout(outputLayout) ||
        (outputLayout == sailens::kTensorLayoutNchw && resizeFilter != 0)) {
        return JNI_FALSE;
    }
    const jlong targetPixels = static_cast<jlong>(targetWidth) * targetHeight;
    if (targetPixels > std::numeric_limits<jsize>::max() / kRgbChannels ||
        env->GetArrayLength(output) != targetPixels * kRgbChannels) {
        return JNI_FALSE;
    }

    YuvPreprocessTiming timing(env, timingNanos);
    const jlong getting = timing.now();
    jbyte* yData = env->GetByteArrayElements(y, timing.isCopy(0));
    jbyte* uData = env->GetByteArrayElements(u, timing.isCopy(1));
    jbyte* vData = env->GetByteArrayElements(v, timing.isCopy(2));
    jfloat* outputData = env->GetFloatArrayElements(output, timing.isCopy(3));
    const jlong getNanos = timing.now() - getting;
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

    float imageMapping[7];
    const jlong bodyStarted = timing.now();
    std::unique_ptr<NearestOffsets[]> offsets;
    if (precomputeNearestIndices && resizeFilter == 0) {
        offsets.reset(new (std::nothrow) NearestOffsets[static_cast<size_t>(targetWidth) + targetHeight]);
    }
    const auto preprocess = [&](auto planar, auto indexed) {
        return preprocessYuv<decltype(planar)::value, decltype(indexed)::value>(
                yPlane, uPlane, vPlane, sourceWidth, sourceHeight, rotationDegrees,
                targetWidth, targetHeight, meanR, meanG, meanB, stdR, stdG, stdB,
                resizeFilter, imageMapping,
                [outputData](int index, float value) { outputData[index] = value; }, offsets.get());
    };
    const auto preprocessLayout = [&](auto planar) {
        return offsets ? preprocess(planar, std::true_type{}) : preprocess(planar, std::false_type{});
    };
    const bool success = outputLayout == sailens::kTensorLayoutNchw
            ? preprocessLayout(std::true_type{})
            : preprocessLayout(std::false_type{});

    const jlong releasing = timing.now();
    env->ReleaseFloatArrayElements(output, outputData, success ? 0 : JNI_ABORT);
    env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
    env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
    if (success) {
        timing.record(env, getNanos, timing.now() - releasing, releasing - bodyStarted);
        env->SetFloatArrayRegion(mapping, 0, 7, imageMapping);
    }
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
        jbyteArray output,
        jfloatArray mapping,
        jlongArray timingNanos) {
    if (y == nullptr || u == nullptr || v == nullptr || output == nullptr ||
        mapping == nullptr || env->GetArrayLength(mapping) != 7 ||
        yRowStride <= 0 || yPixelStride <= 0 ||
        uRowStride <= 0 || uPixelStride <= 0 ||
        vRowStride <= 0 || vPixelStride <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        targetWidth <= 0 || targetHeight <= 0 ||
        quantScale <= 0.0f ||
        env->GetArrayLength(output) != targetWidth * targetHeight * 3) {
        return JNI_FALSE;
    }

    YuvPreprocessTiming timing(env, timingNanos);
    const jlong getting = timing.now();
    jbyte* yData = env->GetByteArrayElements(y, timing.isCopy(0));
    jbyte* uData = env->GetByteArrayElements(u, timing.isCopy(1));
    jbyte* vData = env->GetByteArrayElements(v, timing.isCopy(2));
    jbyte* outputData = env->GetByteArrayElements(output, timing.isCopy(3));
    const jlong getNanos = timing.now() - getting;
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
    float imageMapping[7];
    const jlong bodyStarted = timing.now();
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
            imageMapping,
            [outputData, inverseQuantScale, quantZeroPoint](int index, float value) {
                const int quantized = static_cast<int>(std::lround(value * inverseQuantScale + quantZeroPoint));
                outputData[index] = static_cast<jbyte>(std::clamp(quantized, -128, 127));
            });

    const jlong releasing = timing.now();
    env->ReleaseByteArrayElements(output, outputData, success ? 0 : JNI_ABORT);
    env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
    env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
    if (success) {
        timing.record(env, getNanos, timing.now() - releasing, releasing - bodyStarted);
        env->SetFloatArrayRegion(mapping, 0, 7, imageMapping);
    }
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
         "([B[B[BIIIIIIIIIIIFFFFFFIIZ[F[F[J)Z",
         reinterpret_cast<void*>(nativePreprocessYuvToFloat)},
        {"nativePreprocessYuvToInt8",
         "([B[B[BIIIIIIIIIIIFFFFFFIFI[B[F[J)Z",
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
