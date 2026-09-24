#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "litert_zero_copy.h"
#include "tensor_layout.h"

constexpr const char* kLogTag = "SailensVisionJni";

namespace {

using sailens::isValidTensorLayout;
using sailens::lockTensorBufferForRead;
using sailens::semanticScoreOffset;
using sailens::unlockTensorBuffer;

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

static void sortByConfidence(std::vector<Candidate>& candidates) {
    std::sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
        return a.confidence > b.confidence;
    });
}


static float intersectionOverUnion(const Rect& a, const Rect& b) {
    const float left = std::max(a.x, b.x);
    const float top = std::max(a.y, b.y);
    const float right = std::min(a.x + a.width, b.x + b.width);
    const float bottom = std::min(a.y + a.height, b.y + b.height);
    const float intersectionWidth = std::max(0.0f, right - left);
    const float intersectionHeight = std::max(0.0f, bottom - top);
    const float intersectionArea = intersectionWidth * intersectionHeight;
    if (intersectionArea <= 0.0f) {
        return 0.0f;
    }

    const float unionArea = a.width * a.height + b.width * b.height - intersectionArea;
    return unionArea > 0.0f ? intersectionArea / unionArea : 0.0f;
}

static std::vector<Candidate> applyClassAwareNms(
        const std::vector<Candidate>& sortedCandidates,
        int maxDetections,
        float iouThreshold) {
    std::vector<Candidate> selected;
    selected.reserve(maxDetections);

    for (const Candidate& candidate : sortedCandidates) {
        if (static_cast<int>(selected.size()) >= maxDetections) {
            break;
        }

        bool suppressed = false;
        for (const Candidate& existing : selected) {
            if (existing.classId == candidate.classId &&
                intersectionOverUnion(existing.rect, candidate.rect) > iouThreshold) {
                suppressed = true;
                break;
            }
        }

        if (!suppressed) {
            selected.push_back(candidate);
        }
    }

    return selected;
}

template <typename ScoreAt>
static std::vector<Candidate> decodeRawDetections(
        ScoreAt scoreAt,
        int dataSize,
        const LetterboxGeometry& geometry,
        int inputSize,
        int attributesPerDetection,
        float confidenceThreshold,
        int maxDetections,
        const std::vector<int>& allowedClassIds) {
    constexpr int kRawBoxAttributes = 4;
    constexpr float kNmsIouThreshold = 0.45f;
    constexpr int kMaxNmsCandidates = 300;

    if (attributesPerDetection <= kRawBoxAttributes ||
        dataSize <= 0 ||
        dataSize % attributesPerDetection != 0) {
        return {};
    }

    const int detectionCount = dataSize / attributesPerDetection;
    std::vector<Candidate> candidates;
    candidates.reserve(std::min(detectionCount, kMaxNmsCandidates));

    for (int detectionIndex = 0; detectionIndex < detectionCount; ++detectionIndex) {
        int bestClassId = -1;
        float bestConfidence = confidenceThreshold;

        for (int classId : allowedClassIds) {
            if (classId < 0 || classId >= attributesPerDetection - kRawBoxAttributes) {
                continue;
            }

            const int scoreIndex = (kRawBoxAttributes + classId) * detectionCount + detectionIndex;
            const float confidence = scoreAt(scoreIndex);
            if (confidence > bestConfidence) {
                bestConfidence = confidence;
                bestClassId = classId;
            }
        }

        if (bestClassId < 0) {
            continue;
        }

        const float cx = toModelPixels(scoreAt(detectionIndex), inputSize);
        const float cy = toModelPixels(scoreAt(detectionCount + detectionIndex), inputSize);
        const float width = toModelPixels(scoreAt(detectionCount * 2 + detectionIndex), inputSize);
        const float height = toModelPixels(scoreAt(detectionCount * 3 + detectionIndex), inputSize);

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

        candidates.push_back({bestClassId, bestConfidence, rect});
    }

    sortByConfidence(candidates);
    if (static_cast<int>(candidates.size()) > kMaxNmsCandidates) {
        candidates.resize(kMaxNmsCandidates);
    }
    return applyClassAwareNms(candidates, maxDetections, kNmsIouThreshold);
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

// ---------------------------------------------------------------------------
// JNI entry points.
//
// These deliberately stay inside the anonymous namespace: they have internal
// linkage, so no Java_<mangled> symbol is exported and the runtime cannot bind
// them by name. The only binding is the RegisterNatives table at the bottom of
// this file, which fails the library load when a class, method name or
// signature does not match.
// ---------------------------------------------------------------------------

jfloatArray JNICALL
nativePostProcessRawFloat(
        JNIEnv* env,
        jobject,
        jfloatArray rawDetections,
        jint frameWidth,
        jint frameHeight,
        jint rotationDegrees,
        jint inputSize,
        jint attributesPerDetection,
        jfloat confidenceThreshold,
        jint maxDetections,
        jintArray allowedClassIds) {
    if (rawDetections == nullptr ||
        frameWidth <= 0 ||
        frameHeight <= 0 ||
        inputSize <= 0 ||
        attributesPerDetection <= 4 ||
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

    const LetterboxGeometry geometry = createGeometry(frameWidth, frameHeight, rotationDegrees, inputSize);

    std::vector<Candidate> detections;
    detections = decodeRawDetections(
            [raw](int index) {
                return raw[index];
            },
            rawSize,
            geometry,
            inputSize,
            attributesPerDetection,
            confidenceThreshold,
            maxDetections,
            allowedIds);

    env->ReleaseFloatArrayElements(rawDetections, raw, JNI_ABORT);
    return toJniArray(env, detections);
}

jfloatArray JNICALL
nativePostProcessRawInt8(
        JNIEnv* env,
        jobject,
        jbyteArray rawDetections,
        jint frameWidth,
        jint frameHeight,
        jint rotationDegrees,
        jint inputSize,
        jint attributesPerDetection,
        jfloat quantScale,
        jint quantZeroPoint,
        jfloat confidenceThreshold,
        jint maxDetections,
        jintArray allowedClassIds) {
    if (rawDetections == nullptr ||
        frameWidth <= 0 ||
        frameHeight <= 0 ||
        inputSize <= 0 ||
        attributesPerDetection <= 4 ||
        quantScale == 0.0f ||
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

    jbyte* raw = env->GetByteArrayElements(rawDetections, nullptr);
    if (raw == nullptr) {
        return env->NewFloatArray(0);
    }

    const LetterboxGeometry geometry = createGeometry(frameWidth, frameHeight, rotationDegrees, inputSize);
    std::vector<Candidate> detections = decodeRawDetections(
            [raw, quantScale, quantZeroPoint](int index) {
                return (static_cast<int>(raw[index]) - quantZeroPoint) * quantScale;
            },
            rawSize,
            geometry,
            inputSize,
            attributesPerDetection,
            confidenceThreshold,
            maxDetections,
            allowedIds);

    env->ReleaseByteArrayElements(rawDetections, raw, JNI_ABORT);
    return toJniArray(env, detections);
}

jboolean JNICALL
nativeArgmaxScores(
        JNIEnv* env,
        jobject,
        jfloatArray scores,
        jintArray resultMask,
        jint width,
        jint height,
        jint channels,
        jint scoreLayout) {
    if (scores == nullptr ||
        resultMask == nullptr ||
        width <= 0 ||
        height <= 0 ||
        channels <= 0 ||
        !isValidTensorLayout(scoreLayout)) {
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
        float bestScore = scoreData[semanticScoreOffset(base, pixelCount, channels, scoreLayout)];

        for (int channel = 1; channel < channels; ++channel) {
            const float value = scoreData[
                    semanticScoreOffset(base + channel, pixelCount, channels, scoreLayout)];
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


jfloatArray JNICALL
nativePostProcessRawFloatFromHandle(
        JNIEnv* env,
        jobject,
        jlong tensorBufferHandle,
        jint rawElementCount,
        jint frameWidth,
        jint frameHeight,
        jint rotationDegrees,
        jint inputSize,
        jint attributesPerDetection,
        jfloat confidenceThreshold,
        jint maxDetections,
        jintArray allowedClassIds) {
    if (!sailens::liteRtZeroCopyAvailable()) {
        return nullptr;
    }
    if (tensorBufferHandle == 0 ||
        rawElementCount <= 0 ||
        frameWidth <= 0 ||
        frameHeight <= 0 ||
        inputSize <= 0 ||
        attributesPerDetection <= 4 ||
        maxDetections <= 0) {
        return nullptr;
    }

    const std::vector<int> allowedIds = readAllowedClassIds(env, allowedClassIds);
    if (allowedIds.empty()) {
        return nullptr;
    }

    void* hostPtr = lockTensorBufferForRead(
            tensorBufferHandle, static_cast<size_t>(rawElementCount) * sizeof(jfloat));
    if (hostPtr == nullptr) {
        return nullptr;
    }
    const jfloat* raw = static_cast<const jfloat*>(hostPtr);

    const LetterboxGeometry geometry = createGeometry(frameWidth, frameHeight, rotationDegrees, inputSize);
    std::vector<Candidate> detections = decodeRawDetections(
            [raw](int index) {
                return raw[index];
            },
            rawElementCount,
            geometry,
            inputSize,
            attributesPerDetection,
            confidenceThreshold,
            maxDetections,
            allowedIds);

    unlockTensorBuffer(tensorBufferHandle);
    return toJniArray(env, detections);
}

// Zero-copy INT8 obstacle postprocess: reads the raw detection tensor directly from the native
// LiteRtTensorBuffer* via LiteRtLockTensorBuffer, avoiding the ~1 MB ByteArray that
// TensorBuffer.readInt8() allocates per frame for [1, 116, 8400] / [1, 84, 8400] int8 outputs.
// rawElementCount is the flattened tensor size (attributesPerDetection * detectionCount); it cannot
// be derived from the handle, so the caller passes it.
jfloatArray JNICALL
nativePostProcessRawInt8FromHandle(
        JNIEnv* env,
        jobject,
        jlong tensorBufferHandle,
        jint rawElementCount,
        jint frameWidth,
        jint frameHeight,
        jint rotationDegrees,
        jint inputSize,
        jint attributesPerDetection,
        jfloat quantScale,
        jint quantZeroPoint,
        jfloat confidenceThreshold,
        jint maxDetections,
        jintArray allowedClassIds) {
    if (!sailens::liteRtZeroCopyAvailable()) {
        return nullptr;
    }
    if (tensorBufferHandle == 0 ||
        rawElementCount <= 0 ||
        frameWidth <= 0 ||
        frameHeight <= 0 ||
        inputSize <= 0 ||
        attributesPerDetection <= 4 ||
        quantScale == 0.0f ||
        maxDetections <= 0) {
        return nullptr;
    }

    const std::vector<int> allowedIds = readAllowedClassIds(env, allowedClassIds);
    if (allowedIds.empty()) {
        return nullptr;
    }

    void* hostPtr = lockTensorBufferForRead(
            tensorBufferHandle, static_cast<size_t>(rawElementCount) * sizeof(jbyte));
    if (hostPtr == nullptr) {
        return nullptr;
    }
    const jbyte* raw = static_cast<const jbyte*>(hostPtr);

    const LetterboxGeometry geometry = createGeometry(frameWidth, frameHeight, rotationDegrees, inputSize);
    std::vector<Candidate> detections = decodeRawDetections(
            [raw, quantScale, quantZeroPoint](int index) {
                return (static_cast<int>(raw[index]) - quantZeroPoint) * quantScale;
            },
            rawElementCount,
            geometry,
            inputSize,
            attributesPerDetection,
            confidenceThreshold,
            maxDetections,
            allowedIds);

    unlockTensorBuffer(tensorBufferHandle);
    return toJniArray(env, detections);
}

// ---------------------------------------------------------------------------
// Registration tables.
//
// One row per Kotlin `external fun`. RegisterNatives resolves each row against
// the class by name *and* JNI signature, so a typo here is a load failure on
// the device, not an UnsatisfiedLinkError thrown from a rare code path months
// later. The class names are the R8-kept names; see
// sailens-vision/consumer-rules.pro.
// ---------------------------------------------------------------------------

const JNINativeMethod kSemanticArgmaxPostprocessorMethods[] = {
        {"nativeArgmaxScores",
         "([F[IIIII)Z",
         reinterpret_cast<void*>(nativeArgmaxScores)},
};

const JNINativeMethod kDetectionPostProcessorMethods[] = {
        {"nativePostProcessRawFloat",
         "([FIIIIIFI[I)[F",
         reinterpret_cast<void*>(nativePostProcessRawFloat)},
        {"nativePostProcessRawInt8",
         "([BIIIIIFIFI[I)[F",
         reinterpret_cast<void*>(nativePostProcessRawInt8)},
        {"nativePostProcessRawFloatFromHandle",
         "(JIIIIIIFI[I)[F",
         reinterpret_cast<void*>(nativePostProcessRawFloatFromHandle)},
        {"nativePostProcessRawInt8FromHandle",
         "(JIIIIIIFIFI[I)[F",
         reinterpret_cast<void*>(nativePostProcessRawInt8FromHandle)},
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
        {"com/sailens/vision/semantic/NativeSemanticArgmaxPostprocessor",
         kSemanticArgmaxPostprocessorMethods,
         methodCountOf(kSemanticArgmaxPostprocessorMethods)},
        {"com/sailens/vision/detection/NativeDetectionPostProcessor",
         kDetectionPostProcessorMethods,
         methodCountOf(kDetectionPostProcessorMethods)},
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
