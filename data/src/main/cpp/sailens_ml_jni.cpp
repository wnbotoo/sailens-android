#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <dlfcn.h>
#include <mutex>
#include <vector>

constexpr const char* kLogTag = "SailensMlJni";

// LiteRT C API function pointers, loaded once via dlsym.
// libLiteRt.so is already loaded by the Kotlin layer (System.loadLibrary("LiteRt")),
// so RTLD_DEFAULT resolves these symbols without an explicit dlopen.
using FnLiteRtLockTensorBuffer   = int(*)(void* buffer, int lock_mode, void** host_mem_addr);
using FnLiteRtUnlockTensorBuffer = int(*)(void* buffer);

static FnLiteRtLockTensorBuffer   g_liteRtLock   = nullptr;
static FnLiteRtUnlockTensorBuffer g_liteRtUnlock = nullptr;
static std::once_flag             g_liteRtInitFlag;

static void initLiteRtApi() {
    std::call_once(g_liteRtInitFlag, []() {
        g_liteRtLock   = reinterpret_cast<FnLiteRtLockTensorBuffer>(
                dlsym(RTLD_DEFAULT, "LiteRtLockTensorBuffer"));
        g_liteRtUnlock = reinterpret_cast<FnLiteRtUnlockTensorBuffer>(
                dlsym(RTLD_DEFAULT, "LiteRtUnlockTensorBuffer"));
    });
}

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

static void setPackedBit(jlong* words, int bitIndex) {
    const int wordIndex = bitIndex >> 6;
    const int bitOffset = bitIndex & 63;
    const auto current = static_cast<unsigned long long>(words[wordIndex]);
    words[wordIndex] = static_cast<jlong>(current | (1ULL << bitOffset));
}

static bool isPackedBitSet(const jlong* words, int bitIndex) {
    const int wordIndex = bitIndex >> 6;
    const int bitOffset = bitIndex & 63;
    const auto current = static_cast<unsigned long long>(words[wordIndex]);
    return (current & (1ULL << bitOffset)) != 0;
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

constexpr int kSemanticOutputCount = 13;
constexpr int kOutPassablePixelCount = 0;
constexpr int kOutObstaclePixelCount = 1;
constexpr int kOutRoadPixelCount = 2;
constexpr int kOutHasTrafficLight = 3;
constexpr int kOutBottomCenterRoadPixels = 4;
constexpr int kOutBottomCenterTotalPixels = 5;
constexpr int kOutNavigationPassablePixels = 6;
constexpr int kOutNavigationTotalPixels = 7;
constexpr int kOutBottomTruePixels = 8;
constexpr int kOutMaxRunWidth = 9;
constexpr int kOutMaxRunRow = 10;
constexpr int kOutMaxRunStart = 11;
constexpr int kOutMaxRunEnd = 12;
constexpr int kTensorLayoutNhwc = 0;
constexpr int kTensorLayoutNchw = 1;

static bool isValidTensorLayout(int layout) {
    return layout == kTensorLayoutNhwc || layout == kTensorLayoutNchw;
}

static int semanticScoreOffset(
        int packedHwcIndex,
        int pixelCount,
        int channels,
        int layout) {
    if (layout == kTensorLayoutNchw) {
        const int pixelIndex = packedHwcIndex / channels;
        const int channel = packedHwcIndex - pixelIndex * channels;
        return channel * pixelCount + pixelIndex;
    }
    return packedHwcIndex;
}

template <typename ScoreAt>
static void postprocessSemanticScoresCore(
        ScoreAt scoreAt,
        jint* resultData,
        int width,
        int height,
        int channels,
        const jboolean* passableData,
        const jboolean* obstacleData,
        const jboolean* roadData,
        const jboolean* trafficLightData,
        const jint* groundTypeData,
        float bottomRatio,
        float centerRatio,
        float navigationRegionRatio,
        jlong* passableWordData,
        jlong* obstacleWordData,
        jint* classCountData,
        int classCount,
        jint* groundTypeCountData,
        int groundTypeCount,
        jint* outputData) {
    const int pixelCount = width * height;
    const int wordCount = (pixelCount + 63) / 64;

    std::fill(passableWordData, passableWordData + wordCount, 0);
    std::fill(obstacleWordData, obstacleWordData + wordCount, 0);
    std::fill(classCountData, classCountData + classCount, 0);
    std::fill(groundTypeCountData, groundTypeCountData + groundTypeCount, 0);
    std::fill(outputData, outputData + kSemanticOutputCount, 0);

    const int bottomStartY = std::clamp(
            static_cast<int>((1.0f - bottomRatio) * height),
            0,
            height);
    const int navigationStartY = std::clamp(
            static_cast<int>((1.0f - navigationRegionRatio) * height),
            0,
            height);
    const int centerStartX = std::clamp(
            static_cast<int>(((1.0f - centerRatio) * 0.5f) * width),
            0,
            width);
    const int centerEndX = std::clamp(
            static_cast<int>(((1.0f + centerRatio) * 0.5f) * width),
            centerStartX,
            width);

    outputData[kOutMaxRunRow] = bottomStartY;

    for (int y = 0; y < height; ++y) {
        int currentRunStart = -1;

        for (int x = 0; x < width; ++x) {
            const int pixelIndex = y * width + x;
            const int base = pixelIndex * channels;
            int classId = 0;
            auto bestScore = scoreAt(base);

            for (int channel = 1; channel < channels; ++channel) {
                const auto value = scoreAt(base + channel);
                if (value > bestScore) {
                    bestScore = value;
                    classId = channel;
                }
            }

            resultData[pixelIndex] = classId;

            const bool validClass = classId >= 0 && classId < classCount;
            const bool isPassable = validClass && passableData[classId] == JNI_TRUE;
            const bool isObstacle = validClass && obstacleData[classId] == JNI_TRUE;
            const bool isRoad = validClass && roadData[classId] == JNI_TRUE;
            const bool isTrafficLight = validClass && trafficLightData[classId] == JNI_TRUE;
            const int groundType = validClass ? groundTypeData[classId] : -1;

            if (validClass) {
                classCountData[classId]++;
            }
            if (isPassable) {
                setPackedBit(passableWordData, pixelIndex);
                outputData[kOutPassablePixelCount]++;
            }
            if (isObstacle) {
                setPackedBit(obstacleWordData, pixelIndex);
                outputData[kOutObstaclePixelCount]++;
            }
            if (isRoad) {
                outputData[kOutRoadPixelCount]++;
            }
            if (isTrafficLight) {
                outputData[kOutHasTrafficLight] = 1;
            }
            if (y >= navigationStartY) {
                outputData[kOutNavigationTotalPixels]++;
                if (isPassable) {
                    outputData[kOutNavigationPassablePixels]++;
                }
            }

            if (y >= bottomStartY) {
                if (isPassable) {
                    outputData[kOutBottomTruePixels]++;
                }
                if (x >= centerStartX && x < centerEndX) {
                    outputData[kOutBottomCenterTotalPixels]++;
                    if (groundType >= 0 && groundType < groundTypeCount) {
                        groundTypeCountData[groundType]++;
                    }
                    if (isRoad) {
                        outputData[kOutBottomCenterRoadPixels]++;
                    }
                }
                if (isPassable && currentRunStart == -1) {
                    currentRunStart = x;
                } else if (!isPassable && currentRunStart != -1) {
                    const int runWidth = x - currentRunStart;
                    if (runWidth > outputData[kOutMaxRunWidth]) {
                        outputData[kOutMaxRunWidth] = runWidth;
                        outputData[kOutMaxRunRow] = y;
                        outputData[kOutMaxRunStart] = currentRunStart;
                        outputData[kOutMaxRunEnd] = x - 1;
                    }
                    currentRunStart = -1;
                }
            }
        }

        if (y >= bottomStartY && currentRunStart != -1) {
            const int runWidth = width - currentRunStart;
            if (runWidth > outputData[kOutMaxRunWidth]) {
                outputData[kOutMaxRunWidth] = runWidth;
                outputData[kOutMaxRunRow] = y;
                outputData[kOutMaxRunStart] = currentRunStart;
                outputData[kOutMaxRunEnd] = width - 1;
            }
        }
    }
}



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

jboolean JNICALL
nativePostprocessScores(
        JNIEnv* env,
        jobject,
        jfloatArray scores,
        jintArray resultMask,
        jint width,
        jint height,
        jint channels,
        jint scoreLayout,
        jbooleanArray passableLookup,
        jbooleanArray obstacleLookup,
        jbooleanArray roadLookup,
        jbooleanArray trafficLightLookup,
        jintArray groundTypeLookup,
        jfloat bottomRatio,
        jfloat centerRatio,
        jfloat navigationRegionRatio,
        jlongArray passableWords,
        jlongArray obstacleWords,
        jintArray classCounts,
        jintArray groundTypeCounts,
        jintArray intOutputs) {
    if (scores == nullptr ||
        resultMask == nullptr ||
        passableLookup == nullptr ||
        obstacleLookup == nullptr ||
        roadLookup == nullptr ||
        trafficLightLookup == nullptr ||
        groundTypeLookup == nullptr ||
        passableWords == nullptr ||
        obstacleWords == nullptr ||
        classCounts == nullptr ||
        groundTypeCounts == nullptr ||
        intOutputs == nullptr ||
        width <= 0 ||
        height <= 0 ||
        channels <= 0 ||
        bottomRatio < 0.0f ||
        bottomRatio > 1.0f ||
        centerRatio < 0.0f ||
        centerRatio > 1.0f ||
        navigationRegionRatio < 0.0f ||
        navigationRegionRatio > 1.0f ||
        !isValidTensorLayout(scoreLayout)) {
        return JNI_FALSE;
    }

    const int pixelCount = width * height;
    const int expectedScoreCount = pixelCount * channels;
    const int wordCount = (pixelCount + 63) / 64;
    const int classCount = env->GetArrayLength(classCounts);
    const int groundTypeCount = env->GetArrayLength(groundTypeCounts);

    if (env->GetArrayLength(scores) != expectedScoreCount ||
        env->GetArrayLength(resultMask) != pixelCount ||
        env->GetArrayLength(passableWords) < wordCount ||
        env->GetArrayLength(obstacleWords) < wordCount ||
        env->GetArrayLength(passableLookup) < classCount ||
        env->GetArrayLength(obstacleLookup) < classCount ||
        env->GetArrayLength(roadLookup) < classCount ||
        env->GetArrayLength(trafficLightLookup) < classCount ||
        env->GetArrayLength(groundTypeLookup) < classCount ||
        env->GetArrayLength(intOutputs) < kSemanticOutputCount ||
        classCount <= 0 ||
        channels > classCount ||
        groundTypeCount <= 0) {
        return JNI_FALSE;
    }

    jfloat* scoreData = env->GetFloatArrayElements(scores, nullptr);
    jint* resultData = env->GetIntArrayElements(resultMask, nullptr);
    jboolean* passableData = env->GetBooleanArrayElements(passableLookup, nullptr);
    jboolean* obstacleData = env->GetBooleanArrayElements(obstacleLookup, nullptr);
    jboolean* roadData = env->GetBooleanArrayElements(roadLookup, nullptr);
    jboolean* trafficLightData = env->GetBooleanArrayElements(trafficLightLookup, nullptr);
    jint* groundTypeData = env->GetIntArrayElements(groundTypeLookup, nullptr);
    jlong* passableWordData = env->GetLongArrayElements(passableWords, nullptr);
    jlong* obstacleWordData = env->GetLongArrayElements(obstacleWords, nullptr);
    jint* classCountData = env->GetIntArrayElements(classCounts, nullptr);
    jint* groundTypeCountData = env->GetIntArrayElements(groundTypeCounts, nullptr);
    jint* outputData = env->GetIntArrayElements(intOutputs, nullptr);

    if (scoreData == nullptr ||
        resultData == nullptr ||
        passableData == nullptr ||
        obstacleData == nullptr ||
        roadData == nullptr ||
        trafficLightData == nullptr ||
        groundTypeData == nullptr ||
        passableWordData == nullptr ||
        obstacleWordData == nullptr ||
        classCountData == nullptr ||
        groundTypeCountData == nullptr ||
        outputData == nullptr) {
        if (scoreData != nullptr) env->ReleaseFloatArrayElements(scores, scoreData, JNI_ABORT);
        if (resultData != nullptr) env->ReleaseIntArrayElements(resultMask, resultData, JNI_ABORT);
        if (passableData != nullptr) env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
        if (obstacleData != nullptr) env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
        if (roadData != nullptr) env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
        if (trafficLightData != nullptr) env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
        if (groundTypeData != nullptr) env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
        if (passableWordData != nullptr) env->ReleaseLongArrayElements(passableWords, passableWordData, JNI_ABORT);
        if (obstacleWordData != nullptr) env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, JNI_ABORT);
        if (classCountData != nullptr) env->ReleaseIntArrayElements(classCounts, classCountData, JNI_ABORT);
        if (groundTypeCountData != nullptr) env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, JNI_ABORT);
        if (outputData != nullptr) env->ReleaseIntArrayElements(intOutputs, outputData, JNI_ABORT);
        return JNI_FALSE;
    }

    postprocessSemanticScoresCore(
            [scoreData, pixelCount, channels, scoreLayout](int index) {
                return scoreData[semanticScoreOffset(index, pixelCount, channels, scoreLayout)];
            },
            resultData,
            width,
            height,
            channels,
            passableData,
            obstacleData,
            roadData,
            trafficLightData,
            groundTypeData,
            bottomRatio,
            centerRatio,
            navigationRegionRatio,
            passableWordData,
            obstacleWordData,
            classCountData,
            classCount,
            groundTypeCountData,
            groundTypeCount,
            outputData);

    env->ReleaseFloatArrayElements(scores, scoreData, JNI_ABORT);
    env->ReleaseIntArrayElements(resultMask, resultData, 0);
    env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
    env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
    env->ReleaseLongArrayElements(passableWords, passableWordData, 0);
    env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, 0);
    env->ReleaseIntArrayElements(classCounts, classCountData, 0);
    env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, 0);
    env->ReleaseIntArrayElements(intOutputs, outputData, 0);
    return JNI_TRUE;
}

jboolean JNICALL
nativePostprocessInt8Scores(
        JNIEnv* env,
        jobject,
        jbyteArray scores,
        jintArray resultMask,
        jint width,
        jint height,
        jint channels,
        jint scoreLayout,
        jbooleanArray passableLookup,
        jbooleanArray obstacleLookup,
        jbooleanArray roadLookup,
        jbooleanArray trafficLightLookup,
        jintArray groundTypeLookup,
        jfloat bottomRatio,
        jfloat centerRatio,
        jfloat navigationRegionRatio,
        jlongArray passableWords,
        jlongArray obstacleWords,
        jintArray classCounts,
        jintArray groundTypeCounts,
        jintArray intOutputs) {
    if (scores == nullptr ||
        resultMask == nullptr ||
        passableLookup == nullptr ||
        obstacleLookup == nullptr ||
        roadLookup == nullptr ||
        trafficLightLookup == nullptr ||
        groundTypeLookup == nullptr ||
        passableWords == nullptr ||
        obstacleWords == nullptr ||
        classCounts == nullptr ||
        groundTypeCounts == nullptr ||
        intOutputs == nullptr ||
        width <= 0 ||
        height <= 0 ||
        channels <= 0 ||
        bottomRatio < 0.0f ||
        bottomRatio > 1.0f ||
        centerRatio < 0.0f ||
        centerRatio > 1.0f ||
        navigationRegionRatio < 0.0f ||
        navigationRegionRatio > 1.0f ||
        !isValidTensorLayout(scoreLayout)) {
        return JNI_FALSE;
    }

    const int pixelCount = width * height;
    const int expectedScoreCount = pixelCount * channels;
    const int wordCount = (pixelCount + 63) / 64;
    const int classCount = env->GetArrayLength(classCounts);
    const int groundTypeCount = env->GetArrayLength(groundTypeCounts);

    if (env->GetArrayLength(scores) != expectedScoreCount ||
        env->GetArrayLength(resultMask) != pixelCount ||
        env->GetArrayLength(passableWords) < wordCount ||
        env->GetArrayLength(obstacleWords) < wordCount ||
        env->GetArrayLength(passableLookup) < classCount ||
        env->GetArrayLength(obstacleLookup) < classCount ||
        env->GetArrayLength(roadLookup) < classCount ||
        env->GetArrayLength(trafficLightLookup) < classCount ||
        env->GetArrayLength(groundTypeLookup) < classCount ||
        env->GetArrayLength(intOutputs) < kSemanticOutputCount ||
        classCount <= 0 ||
        channels > classCount ||
        groundTypeCount <= 0) {
        return JNI_FALSE;
    }

    jbyte* scoreData = env->GetByteArrayElements(scores, nullptr);
    jint* resultData = env->GetIntArrayElements(resultMask, nullptr);
    jboolean* passableData = env->GetBooleanArrayElements(passableLookup, nullptr);
    jboolean* obstacleData = env->GetBooleanArrayElements(obstacleLookup, nullptr);
    jboolean* roadData = env->GetBooleanArrayElements(roadLookup, nullptr);
    jboolean* trafficLightData = env->GetBooleanArrayElements(trafficLightLookup, nullptr);
    jint* groundTypeData = env->GetIntArrayElements(groundTypeLookup, nullptr);
    jlong* passableWordData = env->GetLongArrayElements(passableWords, nullptr);
    jlong* obstacleWordData = env->GetLongArrayElements(obstacleWords, nullptr);
    jint* classCountData = env->GetIntArrayElements(classCounts, nullptr);
    jint* groundTypeCountData = env->GetIntArrayElements(groundTypeCounts, nullptr);
    jint* outputData = env->GetIntArrayElements(intOutputs, nullptr);

    if (scoreData == nullptr ||
        resultData == nullptr ||
        passableData == nullptr ||
        obstacleData == nullptr ||
        roadData == nullptr ||
        trafficLightData == nullptr ||
        groundTypeData == nullptr ||
        passableWordData == nullptr ||
        obstacleWordData == nullptr ||
        classCountData == nullptr ||
        groundTypeCountData == nullptr ||
        outputData == nullptr) {
        if (scoreData != nullptr) env->ReleaseByteArrayElements(scores, scoreData, JNI_ABORT);
        if (resultData != nullptr) env->ReleaseIntArrayElements(resultMask, resultData, JNI_ABORT);
        if (passableData != nullptr) env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
        if (obstacleData != nullptr) env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
        if (roadData != nullptr) env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
        if (trafficLightData != nullptr) env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
        if (groundTypeData != nullptr) env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
        if (passableWordData != nullptr) env->ReleaseLongArrayElements(passableWords, passableWordData, JNI_ABORT);
        if (obstacleWordData != nullptr) env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, JNI_ABORT);
        if (classCountData != nullptr) env->ReleaseIntArrayElements(classCounts, classCountData, JNI_ABORT);
        if (groundTypeCountData != nullptr) env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, JNI_ABORT);
        if (outputData != nullptr) env->ReleaseIntArrayElements(intOutputs, outputData, JNI_ABORT);
        return JNI_FALSE;
    }

    postprocessSemanticScoresCore(
            [scoreData, pixelCount, channels, scoreLayout](int index) {
                return static_cast<int>(
                        scoreData[semanticScoreOffset(index, pixelCount, channels, scoreLayout)]);
            },
            resultData,
            width,
            height,
            channels,
            passableData,
            obstacleData,
            roadData,
            trafficLightData,
            groundTypeData,
            bottomRatio,
            centerRatio,
            navigationRegionRatio,
            passableWordData,
            obstacleWordData,
            classCountData,
            classCount,
            groundTypeCountData,
            groundTypeCount,
            outputData);

    env->ReleaseByteArrayElements(scores, scoreData, JNI_ABORT);
    env->ReleaseIntArrayElements(resultMask, resultData, 0);
    env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
    env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
    env->ReleaseLongArrayElements(passableWords, passableWordData, 0);
    env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, 0);
    env->ReleaseIntArrayElements(classCounts, classCountData, 0);
    env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, 0);
    env->ReleaseIntArrayElements(intOutputs, outputData, 0);
    return JNI_TRUE;
}

// Zero-copy FLOAT32 variant: reads model output directly from the native LiteRtTensorBuffer*
// via LiteRtLockTensorBuffer, avoiding the 30 MB FloatArray allocation that
// TensorBuffer.readFloat() would trigger for a 640x640x19 semantic output.
// Only call this when outputElementType == FLOAT32; for INT8 use
// nativePostprocessInt8ScoresFromHandle instead.
jboolean JNICALL
nativePostprocessScoresFromHandle(
        JNIEnv* env,
        jobject,
        jlong tensorBufferHandle,
        jintArray resultMask,
        jint width,
        jint height,
        jint channels,
        jint scoreLayout,
        jbooleanArray passableLookup,
        jbooleanArray obstacleLookup,
        jbooleanArray roadLookup,
        jbooleanArray trafficLightLookup,
        jintArray groundTypeLookup,
        jfloat bottomRatio,
        jfloat centerRatio,
        jfloat navigationRegionRatio,
        jlongArray passableWords,
        jlongArray obstacleWords,
        jintArray classCounts,
        jintArray groundTypeCounts,
        jintArray intOutputs) {
    initLiteRtApi();
    if (g_liteRtLock == nullptr || g_liteRtUnlock == nullptr) return JNI_FALSE;
    if (tensorBufferHandle == 0 ||
        resultMask == nullptr ||
        passableLookup == nullptr ||
        obstacleLookup == nullptr ||
        roadLookup == nullptr ||
        trafficLightLookup == nullptr ||
        groundTypeLookup == nullptr ||
        passableWords == nullptr ||
        obstacleWords == nullptr ||
        classCounts == nullptr ||
        groundTypeCounts == nullptr ||
        intOutputs == nullptr ||
        width <= 0 ||
        height <= 0 ||
        channels <= 0 ||
        !isValidTensorLayout(scoreLayout)) {
        return JNI_FALSE;
    }

    // Lock the LiteRT tensor buffer to obtain a CPU-accessible float pointer.
    // kLiteRtTensorBufferLockModeRead = 0; works for CPU, GPU, and NPU outputs.
    void* hostPtr = nullptr;
    const int kLiteRtTensorBufferLockModeRead = 0;
    const int kLiteRtStatusOk = 0;
    if (g_liteRtLock(reinterpret_cast<void*>(tensorBufferHandle),
                     kLiteRtTensorBufferLockModeRead, &hostPtr) != kLiteRtStatusOk
        || hostPtr == nullptr) {
        return JNI_FALSE;
    }
    const jfloat* scoreData = static_cast<const jfloat*>(hostPtr);

    const int pixelCount = width * height;
    const int wordCount = (pixelCount + 63) / 64;
    const int classCount = env->GetArrayLength(classCounts);
    const int groundTypeCount = env->GetArrayLength(groundTypeCounts);

    if (env->GetArrayLength(resultMask) != pixelCount ||
        env->GetArrayLength(passableWords) < wordCount ||
        env->GetArrayLength(obstacleWords) < wordCount ||
        env->GetArrayLength(passableLookup) < classCount ||
        env->GetArrayLength(obstacleLookup) < classCount ||
        env->GetArrayLength(roadLookup) < classCount ||
        env->GetArrayLength(trafficLightLookup) < classCount ||
        env->GetArrayLength(groundTypeLookup) < classCount ||
        env->GetArrayLength(intOutputs) < kSemanticOutputCount ||
        classCount <= 0 ||
        channels > classCount ||
        groundTypeCount <= 0) {
        g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));
        return JNI_FALSE;
    }

    jint*     resultData        = env->GetIntArrayElements(resultMask, nullptr);
    jboolean* passableData      = env->GetBooleanArrayElements(passableLookup, nullptr);
    jboolean* obstacleData      = env->GetBooleanArrayElements(obstacleLookup, nullptr);
    jboolean* roadData          = env->GetBooleanArrayElements(roadLookup, nullptr);
    jboolean* trafficLightData  = env->GetBooleanArrayElements(trafficLightLookup, nullptr);
    jint*     groundTypeData    = env->GetIntArrayElements(groundTypeLookup, nullptr);
    jlong*    passableWordData  = env->GetLongArrayElements(passableWords, nullptr);
    jlong*    obstacleWordData  = env->GetLongArrayElements(obstacleWords, nullptr);
    jint*     classCountData    = env->GetIntArrayElements(classCounts, nullptr);
    jint*     groundTypeCountData = env->GetIntArrayElements(groundTypeCounts, nullptr);
    jint*     outputData        = env->GetIntArrayElements(intOutputs, nullptr);

    if (resultData == nullptr || passableData == nullptr || obstacleData == nullptr ||
        roadData == nullptr || trafficLightData == nullptr || groundTypeData == nullptr ||
        passableWordData == nullptr || obstacleWordData == nullptr ||
        classCountData == nullptr || groundTypeCountData == nullptr || outputData == nullptr) {
        if (resultData)         env->ReleaseIntArrayElements(resultMask, resultData, JNI_ABORT);
        if (passableData)       env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
        if (obstacleData)       env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
        if (roadData)           env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
        if (trafficLightData)   env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
        if (groundTypeData)     env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
        if (passableWordData)   env->ReleaseLongArrayElements(passableWords, passableWordData, JNI_ABORT);
        if (obstacleWordData)   env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, JNI_ABORT);
        if (classCountData)     env->ReleaseIntArrayElements(classCounts, classCountData, JNI_ABORT);
        if (groundTypeCountData) env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, JNI_ABORT);
        if (outputData)         env->ReleaseIntArrayElements(intOutputs, outputData, JNI_ABORT);
        g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));
        return JNI_FALSE;
    }

    postprocessSemanticScoresCore(
            [scoreData, pixelCount, channels, scoreLayout](int index) {
                return scoreData[semanticScoreOffset(index, pixelCount, channels, scoreLayout)];
            },
            resultData, width, height, channels,
            passableData, obstacleData, roadData, trafficLightData, groundTypeData,
            bottomRatio, centerRatio, navigationRegionRatio,
            passableWordData, obstacleWordData,
            classCountData, classCount, groundTypeCountData, groundTypeCount,
            outputData);

    g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));

    env->ReleaseIntArrayElements(resultMask, resultData, 0);
    env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
    env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
    env->ReleaseLongArrayElements(passableWords, passableWordData, 0);
    env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, 0);
    env->ReleaseIntArrayElements(classCounts, classCountData, 0);
    env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, 0);
    env->ReleaseIntArrayElements(intOutputs, outputData, 0);
    return JNI_TRUE;
}

// Zero-copy INT8 variant: same as nativePostprocessScoresFromHandle but interprets
// the locked buffer as int8_t* to match full-integer-quant semantic models.
// Argmax over signed bytes is order-preserving (scale is always positive), so no
// dequantization is needed -- the winner class is identical to the float argmax.
jboolean JNICALL
nativePostprocessInt8ScoresFromHandle(
        JNIEnv* env,
        jobject,
        jlong tensorBufferHandle,
        jintArray resultMask,
        jint width,
        jint height,
        jint channels,
        jint scoreLayout,
        jbooleanArray passableLookup,
        jbooleanArray obstacleLookup,
        jbooleanArray roadLookup,
        jbooleanArray trafficLightLookup,
        jintArray groundTypeLookup,
        jfloat bottomRatio,
        jfloat centerRatio,
        jfloat navigationRegionRatio,
        jlongArray passableWords,
        jlongArray obstacleWords,
        jintArray classCounts,
        jintArray groundTypeCounts,
        jintArray intOutputs) {
    initLiteRtApi();
    if (g_liteRtLock == nullptr || g_liteRtUnlock == nullptr) return JNI_FALSE;
    if (tensorBufferHandle == 0 ||
        resultMask == nullptr ||
        passableLookup == nullptr ||
        obstacleLookup == nullptr ||
        roadLookup == nullptr ||
        trafficLightLookup == nullptr ||
        groundTypeLookup == nullptr ||
        passableWords == nullptr ||
        obstacleWords == nullptr ||
        classCounts == nullptr ||
        groundTypeCounts == nullptr ||
        intOutputs == nullptr ||
        width <= 0 ||
        height <= 0 ||
        channels <= 0 ||
        !isValidTensorLayout(scoreLayout)) {
        return JNI_FALSE;
    }

    void* hostPtr = nullptr;
    const int kLiteRtTensorBufferLockModeRead = 0;
    const int kLiteRtStatusOk = 0;
    if (g_liteRtLock(reinterpret_cast<void*>(tensorBufferHandle),
                     kLiteRtTensorBufferLockModeRead, &hostPtr) != kLiteRtStatusOk
        || hostPtr == nullptr) {
        return JNI_FALSE;
    }
    const jbyte* scoreData = static_cast<const jbyte*>(hostPtr);

    const int pixelCount = width * height;
    const int wordCount = (pixelCount + 63) / 64;
    const int classCount = env->GetArrayLength(classCounts);
    const int groundTypeCount = env->GetArrayLength(groundTypeCounts);

    if (env->GetArrayLength(resultMask) != pixelCount ||
        env->GetArrayLength(passableWords) < wordCount ||
        env->GetArrayLength(obstacleWords) < wordCount ||
        env->GetArrayLength(passableLookup) < classCount ||
        env->GetArrayLength(obstacleLookup) < classCount ||
        env->GetArrayLength(roadLookup) < classCount ||
        env->GetArrayLength(trafficLightLookup) < classCount ||
        env->GetArrayLength(groundTypeLookup) < classCount ||
        env->GetArrayLength(intOutputs) < kSemanticOutputCount ||
        classCount <= 0 ||
        channels > classCount ||
        groundTypeCount <= 0) {
        g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));
        return JNI_FALSE;
    }

    jint*     resultData        = env->GetIntArrayElements(resultMask, nullptr);
    jboolean* passableData      = env->GetBooleanArrayElements(passableLookup, nullptr);
    jboolean* obstacleData      = env->GetBooleanArrayElements(obstacleLookup, nullptr);
    jboolean* roadData          = env->GetBooleanArrayElements(roadLookup, nullptr);
    jboolean* trafficLightData  = env->GetBooleanArrayElements(trafficLightLookup, nullptr);
    jint*     groundTypeData    = env->GetIntArrayElements(groundTypeLookup, nullptr);
    jlong*    passableWordData  = env->GetLongArrayElements(passableWords, nullptr);
    jlong*    obstacleWordData  = env->GetLongArrayElements(obstacleWords, nullptr);
    jint*     classCountData    = env->GetIntArrayElements(classCounts, nullptr);
    jint*     groundTypeCountData = env->GetIntArrayElements(groundTypeCounts, nullptr);
    jint*     outputData        = env->GetIntArrayElements(intOutputs, nullptr);

    if (resultData == nullptr || passableData == nullptr || obstacleData == nullptr ||
        roadData == nullptr || trafficLightData == nullptr || groundTypeData == nullptr ||
        passableWordData == nullptr || obstacleWordData == nullptr ||
        classCountData == nullptr || groundTypeCountData == nullptr || outputData == nullptr) {
        if (resultData)         env->ReleaseIntArrayElements(resultMask, resultData, JNI_ABORT);
        if (passableData)       env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
        if (obstacleData)       env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
        if (roadData)           env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
        if (trafficLightData)   env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
        if (groundTypeData)     env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
        if (passableWordData)   env->ReleaseLongArrayElements(passableWords, passableWordData, JNI_ABORT);
        if (obstacleWordData)   env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, JNI_ABORT);
        if (classCountData)     env->ReleaseIntArrayElements(classCounts, classCountData, JNI_ABORT);
        if (groundTypeCountData) env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, JNI_ABORT);
        if (outputData)         env->ReleaseIntArrayElements(intOutputs, outputData, JNI_ABORT);
        g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));
        return JNI_FALSE;
    }

    postprocessSemanticScoresCore(
            [scoreData, pixelCount, channels, scoreLayout](int index) {
                return static_cast<int>(
                        scoreData[semanticScoreOffset(index, pixelCount, channels, scoreLayout)]);
            },
            resultData, width, height, channels,
            passableData, obstacleData, roadData, trafficLightData, groundTypeData,
            bottomRatio, centerRatio, navigationRegionRatio,
            passableWordData, obstacleWordData,
            classCountData, classCount, groundTypeCountData, groundTypeCount,
            outputData);

    g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));

    env->ReleaseIntArrayElements(resultMask, resultData, 0);
    env->ReleaseBooleanArrayElements(passableLookup, passableData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(obstacleLookup, obstacleData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(roadLookup, roadData, JNI_ABORT);
    env->ReleaseBooleanArrayElements(trafficLightLookup, trafficLightData, JNI_ABORT);
    env->ReleaseIntArrayElements(groundTypeLookup, groundTypeData, JNI_ABORT);
    env->ReleaseLongArrayElements(passableWords, passableWordData, 0);
    env->ReleaseLongArrayElements(obstacleWords, obstacleWordData, 0);
    env->ReleaseIntArrayElements(classCounts, classCountData, 0);
    env->ReleaseIntArrayElements(groundTypeCounts, groundTypeCountData, 0);
    env->ReleaseIntArrayElements(intOutputs, outputData, 0);
    return JNI_TRUE;
}

jboolean JNICALL
nativeExtractConnectivityStats(
        JNIEnv* env,
        jobject,
        jlongArray passableWords,
        jint width,
        jint height,
        jfloatArray sampleLayerRatios,
        jfloat minRunWidthRatio,
        jfloat bottomRatio,
        jfloat floodWindowTopRatio,
        jint maxFloodNodes,
        jfloat floodEarlyStopReachRatio,
        jfloat floodEarlyStopWidthRetention,
        jfloat directionBiasThreshold,
        jintArray intOutputs,
        jfloatArray floatOutputs) {
    constexpr int intOutputCount = 3;
    constexpr int outValidLayers = 0;
    constexpr int outTotalLayers = 1;
    constexpr int outBiasCode = 2;

    constexpr int floatOutputCount = 6;
    constexpr int outWidthRetentionAvg = 0;
    constexpr int outWidthRetentionP25 = 1;
    constexpr int outWidthSlope = 2;
    constexpr int outFloodReachRatio = 3;
    constexpr int outFloodWidthP25 = 4;
    constexpr int outFloodVisitedRatio = 5;

    if (passableWords == nullptr ||
        sampleLayerRatios == nullptr ||
        intOutputs == nullptr ||
        floatOutputs == nullptr ||
        width <= 0 ||
        height <= 0 ||
        minRunWidthRatio < 0.0f ||
        bottomRatio < 0.0f ||
        bottomRatio > 1.0f ||
        floodWindowTopRatio < 0.0f ||
        floodWindowTopRatio > 1.0f ||
        maxFloodNodes <= 0) {
        return JNI_FALSE;
    }

    const int pixelCount = width * height;
    const int wordCount = (pixelCount + 63) / 64;
    const int layerCount = env->GetArrayLength(sampleLayerRatios);
    if (env->GetArrayLength(passableWords) < wordCount ||
        env->GetArrayLength(intOutputs) < intOutputCount ||
        env->GetArrayLength(floatOutputs) < floatOutputCount ||
        layerCount <= 0) {
        return JNI_FALSE;
    }

    jlong* wordData = env->GetLongArrayElements(passableWords, nullptr);
    jfloat* ratioData = env->GetFloatArrayElements(sampleLayerRatios, nullptr);
    jint* intData = env->GetIntArrayElements(intOutputs, nullptr);
    jfloat* floatData = env->GetFloatArrayElements(floatOutputs, nullptr);
    if (wordData == nullptr || ratioData == nullptr || intData == nullptr || floatData == nullptr) {
        if (wordData != nullptr) env->ReleaseLongArrayElements(passableWords, wordData, JNI_ABORT);
        if (ratioData != nullptr) env->ReleaseFloatArrayElements(sampleLayerRatios, ratioData, JNI_ABORT);
        if (intData != nullptr) env->ReleaseIntArrayElements(intOutputs, intData, JNI_ABORT);
        if (floatData != nullptr) env->ReleaseFloatArrayElements(floatOutputs, floatData, JNI_ABORT);
        return JNI_FALSE;
    }

    std::fill(intData, intData + intOutputCount, 0);
    std::fill(floatData, floatData + floatOutputCount, 0.0f);
    intData[outTotalLayers] = layerCount;

    auto getMask = [wordData, width, height](int x, int y) -> bool {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }
        return isPackedBitSet(wordData, y * width + x);
    };

    auto maxRunOnRow = [width, &getMask](int row, int& outStart, int& outEnd) -> int {
        int maxRunWidth = 0;
        int currentRunStart = -1;
        outStart = 0;
        outEnd = 0;

        for (int x = 0; x < width; ++x) {
            const bool passable = getMask(x, row);
            if (passable && currentRunStart == -1) {
                currentRunStart = x;
            } else if (!passable && currentRunStart != -1) {
                const int runWidth = x - currentRunStart;
                if (runWidth > maxRunWidth) {
                    maxRunWidth = runWidth;
                    outStart = currentRunStart;
                    outEnd = x - 1;
                }
                currentRunStart = -1;
            }
        }

        if (currentRunStart != -1) {
            const int runWidth = width - currentRunStart;
            if (runWidth > maxRunWidth) {
                maxRunWidth = runWidth;
                outStart = currentRunStart;
                outEnd = width - 1;
            }
        }

        return maxRunWidth;
    };

    struct BottomStatsNative {
        int maxRunWidth;
        int maxRunRow;
        int maxRunStart;
        int maxRunEnd;
    };

    const int bottomStartRow = std::clamp(static_cast<int>((1.0f - bottomRatio) * height), 0, height);
    BottomStatsNative bottomStats{0, bottomStartRow, 0, 0};
    for (int row = bottomStartRow; row < height; ++row) {
        int runStart = 0;
        int runEnd = 0;
        const int runWidth = maxRunOnRow(row, runStart, runEnd);
        if (runWidth > bottomStats.maxRunWidth) {
            bottomStats.maxRunWidth = runWidth;
            bottomStats.maxRunRow = row;
            bottomStats.maxRunStart = runStart;
            bottomStats.maxRunEnd = runEnd;
        }
    }

    struct LayerNative {
        int maxRunWidth;
        float maxRunCenter;
        bool isValid;
    };

    std::vector<LayerNative> layers;
    layers.reserve(layerCount);
    int validLayers = 0;

    for (int index = 0; index < layerCount; ++index) {
        const int row = std::clamp(static_cast<int>(ratioData[index] * height), 0, height - 1);
        int maxRunWidth = 0;
        float maxRunCenter = 0.5f;
        const int startRow = std::max(0, row - 1);
        const int endRow = std::min(height - 1, row + 1);

        for (int scanRow = startRow; scanRow <= endRow; ++scanRow) {
            int runStart = 0;
            int runEnd = 0;
            const int runWidth = maxRunOnRow(scanRow, runStart, runEnd);
            if (runWidth > maxRunWidth) {
                maxRunWidth = runWidth;
                maxRunCenter = (runStart + runEnd) / 2.0f / width;
            }
        }

        const float widthRatio = maxRunWidth / static_cast<float>(width);
        const bool isValid = widthRatio >= minRunWidthRatio;
        if (isValid) {
            validLayers++;
        }
        layers.push_back({maxRunWidth, maxRunCenter, isValid});
    }

    intData[outValidLayers] = validLayers;

    if (bottomStats.maxRunWidth >= 1) {
        std::vector<float> retentions;
        retentions.reserve(validLayers);
        for (const LayerNative& layer : layers) {
            if (layer.isValid) {
                retentions.push_back(layer.maxRunWidth / static_cast<float>(bottomStats.maxRunWidth));
            }
        }

        if (!retentions.empty()) {
            float sum = 0.0f;
            for (float value : retentions) {
                sum += value;
            }
            std::sort(retentions.begin(), retentions.end());
            const int p25Index = std::clamp(
                    static_cast<int>(retentions.size() * 0.25f),
                    0,
                    static_cast<int>(retentions.size()) - 1);
            floatData[outWidthRetentionAvg] = sum / retentions.size();
            floatData[outWidthRetentionP25] = retentions[p25Index];

            if (!layers.empty()) {
                const float topRetention = layers.back().maxRunWidth / static_cast<float>(bottomStats.maxRunWidth);
                floatData[outWidthSlope] = topRetention - 1.0f;
            }
        }
    }

    float leftWeight = 0.0f;
    float rightWeight = 0.0f;
    for (int index = 0; index < static_cast<int>(layers.size()); ++index) {
        const LayerNative& layer = layers[index];
        if (!layer.isValid) {
            continue;
        }

        const float offset = layer.maxRunCenter - 0.5f;
        const float weight = 1.0f + index * 0.5f;
        if (offset < -0.1f) {
            leftWeight += std::abs(offset) * weight;
        } else if (offset > 0.1f) {
            rightWeight += std::abs(offset) * weight;
        }
    }

    if (leftWeight > rightWeight + directionBiasThreshold) {
        intData[outBiasCode] = -1;
    } else if (rightWeight > leftWeight + directionBiasThreshold) {
        intData[outBiasCode] = 1;
    }

    if (bottomStats.maxRunWidth >= width * minRunWidthRatio) {
        const int windowTop = static_cast<int>(floodWindowTopRatio * height);
        const int windowBottom = height - 1;
        const int windowHeight = windowBottom - windowTop;

        if (windowHeight > 0) {
            const int seedY = std::clamp(bottomStats.maxRunRow, windowTop, windowBottom);
            const int seedStartX = bottomStats.maxRunStart;
            const int seedEndX = bottomStats.maxRunEnd;
            const int reachableWindowHeight = std::max(1, seedY - windowTop);
            const int seedCount = std::min(32, seedEndX - seedStartX + 1);
            const int seedStep = std::max(1, (seedEndX - seedStartX) / seedCount);

            struct Point {
                int x;
                int y;
            };

            std::vector<Point> queue;
            queue.reserve(std::min(maxFloodNodes * 2, pixelCount));
            int x = seedStartX;
            int generatedSeeds = 0;
            while (x <= seedEndX && generatedSeeds < seedCount) {
                if (getMask(x, seedY)) {
                    queue.push_back({x, seedY});
                    generatedSeeds++;
                }
                x += seedStep;
            }

            if (!queue.empty()) {
                std::vector<unsigned long long> visitedWords(wordCount, 0ULL);
                std::vector<int> rowWidths(height, 0);
                int visitedCount = 0;
                int minYReached = seedY;
                size_t queueIndex = 0;

                constexpr int dx[8] = {0, 1, 0, -1, 1, 1, -1, -1};
                constexpr int dy[8] = {-1, 0, 1, 0, -1, 1, 1, -1};

                auto isVisited = [&visitedWords](int bitIndex) -> bool {
                    const int wordIndex = bitIndex >> 6;
                    const int bitOffset = bitIndex & 63;
                    return (visitedWords[wordIndex] & (1ULL << bitOffset)) != 0;
                };
                auto setVisited = [&visitedWords](int bitIndex) {
                    const int wordIndex = bitIndex >> 6;
                    const int bitOffset = bitIndex & 63;
                    visitedWords[wordIndex] |= (1ULL << bitOffset);
                };

                while (queueIndex < queue.size() && visitedCount < maxFloodNodes) {
                    const Point point = queue[queueIndex++];
                    const int cx = point.x;
                    const int cy = point.y;

                    if (cx < 0 || cx >= width || cy < windowTop || cy > windowBottom) {
                        continue;
                    }
                    const int bitIndex = cy * width + cx;
                    if (isVisited(bitIndex) || !getMask(cx, cy)) {
                        continue;
                    }

                    setVisited(bitIndex);
                    visitedCount++;
                    minYReached = std::min(minYReached, cy);
                    rowWidths[cy]++;

                    for (int i = 0; i < 8; ++i) {
                        const int nextX = cx + dx[i];
                        const int nextY = cy + dy[i];
                        queue.push_back({nextX, nextY});

                        if (i > 3) {
                            continue;
                        }

                        const int bridgeX = cx + dx[i] * 2;
                        const int bridgeY = cy + dy[i] * 2;
                        if (bridgeX < 0 || bridgeX >= width || bridgeY < windowTop || bridgeY > windowBottom) {
                            continue;
                        }
                        if (getMask(nextX, nextY) || !getMask(bridgeX, bridgeY)) {
                            continue;
                        }

                        queue.push_back({bridgeX, bridgeY});
                    }

                    const float currentReach = (seedY - minYReached) / static_cast<float>(reachableWindowHeight);
                    if (currentReach >= floodEarlyStopReachRatio) {
                        int totalRowWidth = 0;
                        int activeRows = 0;
                        for (int row = windowTop; row <= windowBottom; ++row) {
                            const int rowWidth = rowWidths[row];
                            if (rowWidth > 0) {
                                totalRowWidth += rowWidth;
                                activeRows++;
                            }
                        }
                        const float avgRowWidth = activeRows > 0
                                ? totalRowWidth / static_cast<float>(activeRows)
                                : 0.0f;
                        const float retention = avgRowWidth / bottomStats.maxRunWidth;
                        if (retention >= floodEarlyStopWidthRetention) {
                            break;
                        }
                    }
                }

                floatData[outFloodReachRatio] = (seedY - minYReached) / static_cast<float>(reachableWindowHeight);
                const int windowArea = windowHeight * width;
                floatData[outFloodVisitedRatio] = windowArea > 0
                        ? visitedCount / static_cast<float>(windowArea)
                        : 0.0f;

                std::vector<float> widthRetentions;
                widthRetentions.reserve(height);
                for (int row = windowTop; row <= windowBottom; ++row) {
                    const int rowWidth = rowWidths[row];
                    if (rowWidth > 0) {
                        widthRetentions.push_back(rowWidth / static_cast<float>(bottomStats.maxRunWidth));
                    }
                }

                if (!widthRetentions.empty()) {
                    std::sort(widthRetentions.begin(), widthRetentions.end());
                    const int p25Index = std::clamp(
                            static_cast<int>(widthRetentions.size() * 0.25f),
                            0,
                            static_cast<int>(widthRetentions.size()) - 1);
                    floatData[outFloodWidthP25] = widthRetentions[p25Index];
                }
            }
        }
    }

    env->ReleaseLongArrayElements(passableWords, wordData, JNI_ABORT);
    env->ReleaseFloatArrayElements(sampleLayerRatios, ratioData, JNI_ABORT);
    env->ReleaseIntArrayElements(intOutputs, intData, 0);
    env->ReleaseFloatArrayElements(floatOutputs, floatData, 0);
    return JNI_TRUE;
}

// Zero-copy FLOAT32 obstacle postprocess: reads the raw detection tensor directly from the native
// LiteRtTensorBuffer* via LiteRtLockTensorBuffer, avoiding the multi-MB FloatArray that
// TensorBuffer.readFloat() allocates per frame for [1, 116, 8400] / [1, 84, 8400] outputs.
// rawElementCount is the flattened tensor size (attributesPerDetection * detectionCount); it cannot
// be derived from the handle, so the caller passes it.
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
    initLiteRtApi();
    if (g_liteRtLock == nullptr || g_liteRtUnlock == nullptr) {
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

    void* hostPtr = nullptr;
    const int kLiteRtTensorBufferLockModeRead = 0;
    const int kLiteRtStatusOk = 0;
    if (g_liteRtLock(reinterpret_cast<void*>(tensorBufferHandle),
                     kLiteRtTensorBufferLockModeRead, &hostPtr) != kLiteRtStatusOk
        || hostPtr == nullptr) {
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

    g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));
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
    initLiteRtApi();
    if (g_liteRtLock == nullptr || g_liteRtUnlock == nullptr) {
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

    void* hostPtr = nullptr;
    const int kLiteRtTensorBufferLockModeRead = 0;
    const int kLiteRtStatusOk = 0;
    if (g_liteRtLock(reinterpret_cast<void*>(tensorBufferHandle),
                     kLiteRtTensorBufferLockModeRead, &hostPtr) != kLiteRtStatusOk
        || hostPtr == nullptr) {
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

    g_liteRtUnlock(reinterpret_cast<void*>(tensorBufferHandle));
    return toJniArray(env, detections);
}

const JNINativeMethod kSemanticArgmaxPostprocessorMethods[] = {
        {"nativeArgmaxScores",
         "([F[IIIII)Z",
         reinterpret_cast<void*>(nativeArgmaxScores)},
};

const JNINativeMethod kSemanticScorePostprocessorMethods[] = {
        {"nativePostprocessScores",
         "([F[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
         reinterpret_cast<void*>(nativePostprocessScores)},
        {"nativePostprocessInt8Scores",
         "([B[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
         reinterpret_cast<void*>(nativePostprocessInt8Scores)},
        {"nativePostprocessScoresFromHandle",
         "(J[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
         reinterpret_cast<void*>(nativePostprocessScoresFromHandle)},
        {"nativePostprocessInt8ScoresFromHandle",
         "(J[IIIII[Z[Z[Z[Z[IFFF[J[J[I[I[I)Z",
         reinterpret_cast<void*>(nativePostprocessInt8ScoresFromHandle)},
};

const JNINativeMethod kObstaclePostProcessorMethods[] = {
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

const JNINativeMethod kConnectivityStatsExtractorMethods[] = {
        {"nativeExtractConnectivityStats",
         "([JII[FFFFIFFF[I[F)Z",
         reinterpret_cast<void*>(nativeExtractConnectivityStats)},
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
        {"com/sailens/data/source/ml/semantic/NativeSemanticArgmaxPostprocessor",
         kSemanticArgmaxPostprocessorMethods,
         methodCountOf(kSemanticArgmaxPostprocessorMethods)},
        {"com/sailens/data/source/ml/semantic/NativeSemanticScorePostprocessor",
         kSemanticScorePostprocessorMethods,
         methodCountOf(kSemanticScorePostprocessorMethods)},
        {"com/sailens/data/source/ml/obstacle/ObstacleNativePostProcessor",
         kObstaclePostProcessorMethods,
         methodCountOf(kObstaclePostProcessorMethods)},
        {"com/sailens/data/source/ml/analysis/NativeConnectivityStatsExtractor",
         kConnectivityStatsExtractorMethods,
         methodCountOf(kConnectivityStatsExtractorMethods)},
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
