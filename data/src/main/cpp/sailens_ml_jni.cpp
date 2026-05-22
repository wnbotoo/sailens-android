#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cfloat>
#include <vector>

namespace {

struct Rect {
    float x;
    float y;
    float width;
    float height;
};

struct LetterboxGeometry {
    int rotatedWidth;
    int rotatedHeight;
    float scale;
    float padX;
    float padY;
};

struct Candidate {
    int classId;
    float confidence;
    Rect rect;
};

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

static bool isAllowedClass(int classId, const std::vector<int>& allowedClassIds) {
    return std::find(allowedClassIds.begin(), allowedClassIds.end(), classId) != allowedClassIds.end();
}

static LetterboxGeometry createGeometry(int frameWidth, int frameHeight, int rotationDegrees, int inputSize) {
    const bool rotated = rotationDegrees == 90 || rotationDegrees == 270;
    const int rotatedWidth = rotated ? frameHeight : frameWidth;
    const int rotatedHeight = rotated ? frameWidth : frameHeight;
    const float scale = std::min(
            inputSize / static_cast<float>(rotatedWidth),
            inputSize / static_cast<float>(rotatedHeight));
    const float resizedWidth = rotatedWidth * scale;
    const float resizedHeight = rotatedHeight * scale;

    return {
            rotatedWidth,
            rotatedHeight,
            scale,
            (inputSize - resizedWidth) * 0.5f,
            (inputSize - resizedHeight) * 0.5f,
    };
}

static float toModelPixels(float value, int inputSize) {
    return value <= 2.0f ? value * inputSize : value;
}

static bool decodeModelRect(
        const LetterboxGeometry& geometry,
        int inputSize,
        float modelLeft,
        float modelTop,
        float modelRight,
        float modelBottom,
        Rect& outRect) {
    if (modelRight <= modelLeft || modelBottom <= modelTop) {
        return false;
    }

    const float unpaddedLeft = std::clamp(
            (modelLeft - geometry.padX) / geometry.scale,
            0.0f,
            static_cast<float>(geometry.rotatedWidth));
    const float unpaddedTop = std::clamp(
            (modelTop - geometry.padY) / geometry.scale,
            0.0f,
            static_cast<float>(geometry.rotatedHeight));
    const float unpaddedRight = std::clamp(
            (modelRight - geometry.padX) / geometry.scale,
            0.0f,
            static_cast<float>(geometry.rotatedWidth));
    const float unpaddedBottom = std::clamp(
            (modelBottom - geometry.padY) / geometry.scale,
            0.0f,
            static_cast<float>(geometry.rotatedHeight));

    if (unpaddedRight <= unpaddedLeft || unpaddedBottom <= unpaddedTop ||
        inputSize <= 0 || geometry.rotatedWidth <= 0 || geometry.rotatedHeight <= 0) {
        return false;
    }

    outRect = {
            std::clamp(unpaddedLeft / geometry.rotatedWidth, 0.0f, 1.0f),
            std::clamp(unpaddedTop / geometry.rotatedHeight, 0.0f, 1.0f),
            std::clamp((unpaddedRight - unpaddedLeft) / geometry.rotatedWidth, 0.0f, 1.0f),
            std::clamp((unpaddedBottom - unpaddedTop) / geometry.rotatedHeight, 0.0f, 1.0f),
    };
    return outRect.width > 0.0f && outRect.height > 0.0f;
}

static float valueAt(
        const float* data,
        int dataSize,
        bool attributeMajor,
        int detectionIndex,
        int attributeIndex,
        int attributesPerDetection) {
    const int detectionCount = dataSize / attributesPerDetection;
    const int index = attributeMajor
            ? attributeIndex * detectionCount + detectionIndex
            : detectionIndex * attributesPerDetection + attributeIndex;
    if (index < 0 || index >= dataSize) {
        return -FLT_MAX;
    }
    return data[index];
}

static float intersectionArea(const Rect& a, const Rect& b) {
    const float ax2 = a.x + a.width;
    const float ay2 = a.y + a.height;
    const float bx2 = b.x + b.width;
    const float by2 = b.y + b.height;

    const float interLeft = std::max(a.x, b.x);
    const float interTop = std::max(a.y, b.y);
    const float interRight = std::min(ax2, bx2);
    const float interBottom = std::min(ay2, by2);

    const float interWidth = std::max(0.0f, interRight - interLeft);
    const float interHeight = std::max(0.0f, interBottom - interTop);
    return interWidth * interHeight;
}

static float iou(const Rect& a, const Rect& b) {
    const float inter = intersectionArea(a, b);
    const float areaA = a.width * a.height;
    const float areaB = b.width * b.height;
    const float unionArea = areaA + areaB - inter;
    return unionArea > 0.0f ? inter / unionArea : 0.0f;
}

static void sortByConfidence(std::vector<Candidate>& candidates) {
    std::sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
        return a.confidence > b.confidence;
    });
}

static std::vector<Candidate> runClassAwareNms(
        std::vector<Candidate>& candidates,
        float nmsThreshold,
        int maxDetections) {
    sortByConfidence(candidates);

    std::vector<Candidate> kept;
    kept.reserve(std::min(static_cast<int>(candidates.size()), maxDetections));

    for (const Candidate& candidate : candidates) {
        bool overlaps = false;
        for (const Candidate& existing : kept) {
            if (existing.classId == candidate.classId && iou(existing.rect, candidate.rect) >= nmsThreshold) {
                overlaps = true;
                break;
            }
        }

        if (!overlaps) {
            kept.push_back(candidate);
            if (static_cast<int>(kept.size()) >= maxDetections) {
                break;
            }
        }
    }
    return kept;
}

static bool isPlausibleConfidence(float value, float confidenceThreshold) {
    return value >= confidenceThreshold && value <= 1.5f;
}

static int findBestClass(
        const float* data,
        int dataSize,
        bool attributeMajor,
        int detectionIndex,
        int attributesPerDetection,
        int classCount,
        float& outConfidence) {
    int bestClassId = -1;
    float bestConfidence = -FLT_MAX;

    for (int classOffset = 0; classOffset < classCount; ++classOffset) {
        const float confidence = valueAt(
                data,
                dataSize,
                attributeMajor,
                detectionIndex,
                4 + classOffset,
                attributesPerDetection);
        if (confidence > bestConfidence) {
            bestConfidence = confidence;
            bestClassId = classOffset;
        }
    }

    outConfidence = bestConfidence;
    return bestClassId;
}

static bool chooseAttributeMajorLayout(
        const float* data,
        int dataSize,
        int detectionCount,
        int attributesPerDetection,
        int classCount,
        float confidenceThreshold) {
    const int sampleSize = std::min(detectionCount, 64);
    int attributeMajorHits = 0;
    int detectionMajorHits = 0;

    for (int index = 0; index < sampleSize; ++index) {
        float confidence = -FLT_MAX;
        findBestClass(data, dataSize, true, index, attributesPerDetection, classCount, confidence);
        if (isPlausibleConfidence(confidence, confidenceThreshold)) {
            attributeMajorHits++;
        }

        findBestClass(data, dataSize, false, index, attributesPerDetection, classCount, confidence);
        if (isPlausibleConfidence(confidence, confidenceThreshold)) {
            detectionMajorHits++;
        }
    }

    return detectionMajorHits <= attributeMajorHits;
}

static std::vector<Candidate> decodeEndToEnd(
        const float* data,
        int dataSize,
        const LetterboxGeometry& geometry,
        int inputSize,
        int attributesPerDetection,
        float confidenceThreshold,
        int maxDetections,
        const std::vector<int>& allowedClassIds) {
    const int detectionCount = dataSize / attributesPerDetection;
    std::vector<Candidate> candidates;
    candidates.reserve(std::min(detectionCount, maxDetections * 4));

    for (int detectionIndex = 0; detectionIndex < detectionCount; ++detectionIndex) {
        const int base = detectionIndex * attributesPerDetection;
        const float confidence = data[base + 4];
        if (confidence < confidenceThreshold) {
            continue;
        }

        const int classId = static_cast<int>(std::round(data[base + 5]));
        if (!isAllowedClass(classId, allowedClassIds)) {
            continue;
        }

        const float x1 = toModelPixels(data[base], inputSize);
        const float y1 = toModelPixels(data[base + 1], inputSize);
        const float x2 = toModelPixels(data[base + 2], inputSize);
        const float y2 = toModelPixels(data[base + 3], inputSize);

        Rect rect{};
        if (!decodeModelRect(
                    geometry,
                    inputSize,
                    std::min(x1, x2),
                    std::min(y1, y2),
                    std::max(x1, x2),
                    std::max(y1, y2),
                    rect)) {
            continue;
        }

        candidates.push_back({classId, confidence, rect});
    }

    sortByConfidence(candidates);
    if (static_cast<int>(candidates.size()) > maxDetections) {
        candidates.resize(maxDetections);
    }
    return candidates;
}

static std::vector<Candidate> decodeLegacy(
        const float* data,
        int dataSize,
        const LetterboxGeometry& geometry,
        int inputSize,
        int attributesPerDetection,
        int classCount,
        float confidenceThreshold,
        float nmsThreshold,
        int maxDetections,
        const std::vector<int>& allowedClassIds) {
    const int detectionCount = dataSize / attributesPerDetection;
    const bool attributeMajor = chooseAttributeMajorLayout(
            data,
            dataSize,
            detectionCount,
            attributesPerDetection,
            classCount,
            confidenceThreshold);

    std::vector<Candidate> candidates;
    candidates.reserve(std::min(detectionCount, maxDetections * 8));

    for (int detectionIndex = 0; detectionIndex < detectionCount; ++detectionIndex) {
        float confidence = -FLT_MAX;
        const int classId = findBestClass(
                data,
                dataSize,
                attributeMajor,
                detectionIndex,
                attributesPerDetection,
                classCount,
                confidence);
        if (confidence < confidenceThreshold || !isAllowedClass(classId, allowedClassIds)) {
            continue;
        }

        const float cx = toModelPixels(valueAt(data, dataSize, attributeMajor, detectionIndex, 0, attributesPerDetection), inputSize);
        const float cy = toModelPixels(valueAt(data, dataSize, attributeMajor, detectionIndex, 1, attributesPerDetection), inputSize);
        const float width = toModelPixels(valueAt(data, dataSize, attributeMajor, detectionIndex, 2, attributesPerDetection), inputSize);
        const float height = toModelPixels(valueAt(data, dataSize, attributeMajor, detectionIndex, 3, attributesPerDetection), inputSize);
        if (width <= 0.0f || height <= 0.0f) {
            continue;
        }

        Rect rect{};
        if (!decodeModelRect(
                    geometry,
                    inputSize,
                    cx - width * 0.5f,
                    cy - height * 0.5f,
                    cx + width * 0.5f,
                    cy + height * 0.5f,
                    rect)) {
            continue;
        }

        candidates.push_back({classId, confidence, rect});
    }

    return runClassAwareNms(candidates, nmsThreshold, maxDetections);
}

static jfloatArray toJniArray(JNIEnv* env, const std::vector<Candidate>& detections) {
    constexpr int valuesPerDetection = 6;
    const int outputSize = static_cast<int>(detections.size()) * valuesPerDetection;
    jfloatArray output = env->NewFloatArray(outputSize);
    if (output == nullptr || outputSize == 0) {
        return output;
    }

    std::vector<float> flat;
    flat.reserve(outputSize);
    for (const Candidate& detection : detections) {
        flat.push_back(static_cast<float>(detection.classId));
        flat.push_back(detection.confidence);
        flat.push_back(detection.rect.x);
        flat.push_back(detection.rect.y);
        flat.push_back(detection.rect.width);
        flat.push_back(detection.rect.height);
    }

    env->SetFloatArrayRegion(output, 0, outputSize, flat.data());
    return output;
}

static std::vector<int> readAllowedClassIds(JNIEnv* env, jintArray allowedClassIds) {
    std::vector<int> values;
    if (allowedClassIds == nullptr) {
        return values;
    }

    const jsize size = env->GetArrayLength(allowedClassIds);
    values.resize(size);
    jint* ids = env->GetIntArrayElements(allowedClassIds, nullptr);
    if (ids == nullptr) {
        values.clear();
        return values;
    }

    for (jsize index = 0; index < size; ++index) {
        values[index] = static_cast<int>(ids[index]);
    }
    env->ReleaseIntArrayElements(allowedClassIds, ids, JNI_ABORT);
    return values;
}

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

static void rotatedToSource(
        float rotatedX,
        float rotatedY,
        int sourceWidth,
        int sourceHeight,
        int rotationDegrees,
        float& sourceX,
        float& sourceY) {
    const int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
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

    int outIndex = 0;
    for (int y = 0; y < targetHeight; ++y) {
        for (int x = 0; x < targetWidth; ++x) {
            RgbPixel pixel = {0.0f, 0.0f, 0.0f};
            if (x >= padX && y >= padY && x < padX + resizedWidth && y < padY + resizedHeight) {
                const float rotatedX = (static_cast<float>(x) - padX + 0.5f) / scale - 0.5f;
                const float rotatedY = (static_cast<float>(y) - padY + 0.5f) / scale - 0.5f;
                float sourceX = 0.0f;
                float sourceY = 0.0f;
                rotatedToSource(
                        rotatedX,
                        rotatedY,
                        sourceWidth,
                        sourceHeight,
                        normalizedRotation,
                        sourceX,
                        sourceY);
                pixel = sampleYuvAsRgb(yPlane, uPlane, vPlane, sourceX, sourceY);
            }

            writer(outIndex++, (pixel.r / 255.0f - meanR) / stdR);
            writer(outIndex++, (pixel.g / 255.0f - meanG) / stdG);
            writer(outIndex++, (pixel.b / 255.0f - meanB) / stdB);
        }
    }

    return true;
}

}  // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_friady_sailens_data_source_ml_NativeYuvInputPreprocessor_nativePreprocessYuvToFloat(
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
            [outputData](int index, float value) {
                outputData[index] = value;
            });

    env->ReleaseFloatArrayElements(output, outputData, success ? 0 : JNI_ABORT);
    env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
    env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_friady_sailens_data_source_ml_NativeYuvInputPreprocessor_nativePreprocessYuvToInt8(
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
            [outputData, quantScale, quantZeroPoint](int index, float value) {
                const int quantized = static_cast<int>(std::lround(value / quantScale + quantZeroPoint));
                outputData[index] = static_cast<jbyte>(std::clamp(quantized, -128, 127));
            });

    env->ReleaseByteArrayElements(output, outputData, success ? 0 : JNI_ABORT);
    env->ReleaseByteArrayElements(v, vData, JNI_ABORT);
    env->ReleaseByteArrayElements(u, uData, JNI_ABORT);
    env->ReleaseByteArrayElements(y, yData, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_friady_sailens_data_source_ml_instance_YOLO26SegNativePostProcessor_nativePostProcess(
        JNIEnv* env,
        jobject,
        jfloatArray rawDetections,
        jint frameWidth,
        jint frameHeight,
        jint rotationDegrees,
        jint inputSize,
        jint classCount,
        jint maskCoefficientCount,
        jfloat confidenceThreshold,
        jfloat nmsThreshold,
        jint maxDetections,
        jintArray allowedClassIds) {
    if (rawDetections == nullptr ||
        frameWidth <= 0 ||
        frameHeight <= 0 ||
        inputSize <= 0 ||
        classCount <= 0 ||
        maskCoefficientCount < 0 ||
        maxDetections <= 0) {
        return env->NewFloatArray(0);
    }

    const jsize rawSize = env->GetArrayLength(rawDetections);
    if (rawSize <= 0) {
        return env->NewFloatArray(0);
    }

    const std::vector<int> allowedIds = readAllowedClassIds(env, allowedClassIds);
    if (allowedIds.empty()) {
        return env->NewFloatArray(0);
    }

    jfloat* raw = env->GetFloatArrayElements(rawDetections, nullptr);
    if (raw == nullptr) {
        return env->NewFloatArray(0);
    }

    const int endToEndAttributes = 4 + 1 + 1 + maskCoefficientCount;
    const int legacyAttributes = 4 + classCount + maskCoefficientCount;
    const LetterboxGeometry geometry = createGeometry(frameWidth, frameHeight, rotationDegrees, inputSize);

    std::vector<Candidate> detections;
    if (endToEndAttributes > 0 && rawSize % endToEndAttributes == 0) {
        detections = decodeEndToEnd(
                raw,
                rawSize,
                geometry,
                inputSize,
                endToEndAttributes,
                confidenceThreshold,
                maxDetections,
                allowedIds);
    } else if (legacyAttributes > 0 && rawSize % legacyAttributes == 0) {
        detections = decodeLegacy(
                raw,
                rawSize,
                geometry,
                inputSize,
                legacyAttributes,
                classCount,
                confidenceThreshold,
                nmsThreshold,
                maxDetections,
                allowedIds);
    }

    env->ReleaseFloatArrayElements(rawDetections, raw, JNI_ABORT);
    return toJniArray(env, detections);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_friady_sailens_data_source_ml_semantic_YOLO26SemNativePostProcessor_nativeArgMax(
        JNIEnv* env,
        jobject,
        jfloatArray scores,
        jintArray resultMask,
        jint width,
        jint height,
        jint channels) {
    if (scores == nullptr ||
        resultMask == nullptr ||
        width <= 0 ||
        height <= 0 ||
        channels <= 0) {
        return JNI_FALSE;
    }

    const jsize pixelCount = width * height;
    const jsize expectedScoreCount = pixelCount * channels;
    if (env->GetArrayLength(scores) != expectedScoreCount ||
        env->GetArrayLength(resultMask) != pixelCount) {
        return JNI_FALSE;
    }

    jfloat* scoreData = env->GetFloatArrayElements(scores, nullptr);
    if (scoreData == nullptr) {
        return JNI_FALSE;
    }

    jint* maskData = env->GetIntArrayElements(resultMask, nullptr);
    if (maskData == nullptr) {
        env->ReleaseFloatArrayElements(scores, scoreData, JNI_ABORT);
        return JNI_FALSE;
    }

    for (int pixelIndex = 0; pixelIndex < pixelCount; ++pixelIndex) {
        const int base = pixelIndex * channels;
        int bestClass = 0;
        float bestScore = scoreData[base];

        for (int channel = 1; channel < channels; ++channel) {
            const float value = scoreData[base + channel];
            if (value > bestScore) {
                bestScore = value;
                bestClass = channel;
            }
        }

        maskData[pixelIndex] = bestClass;
    }

    env->ReleaseIntArrayElements(resultMask, maskData, 0);
    env->ReleaseFloatArrayElements(scores, scoreData, JNI_ABORT);
    return JNI_TRUE;
}
