#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "litert_zero_copy.h"
#include "tensor_layout.h"

constexpr const char* kLogTag = "SailensGuidanceJni";


namespace {

using sailens::isValidTensorLayout;
using sailens::lockTensorBufferForRead;
using sailens::semanticScoreOffset;
using sailens::unlockTensorBuffer;

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
    if (!sailens::liteRtZeroCopyAvailable()) return JNI_FALSE;
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
    void* hostPtr = lockTensorBufferForRead(tensorBufferHandle);
    if (hostPtr == nullptr) {
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
        unlockTensorBuffer(tensorBufferHandle);
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
        unlockTensorBuffer(tensorBufferHandle);
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

    unlockTensorBuffer(tensorBufferHandle);

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
    if (!sailens::liteRtZeroCopyAvailable()) return JNI_FALSE;
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

    void* hostPtr = lockTensorBufferForRead(tensorBufferHandle);
    if (hostPtr == nullptr) {
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
        unlockTensorBuffer(tensorBufferHandle);
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
        unlockTensorBuffer(tensorBufferHandle);
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

    unlockTensorBuffer(tensorBufferHandle);

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
        {"com/sailens/guidance/kernel/NavigationScorePostprocessor",
         kSemanticScorePostprocessorMethods,
         methodCountOf(kSemanticScorePostprocessorMethods)},
        {"com/sailens/guidance/kernel/NativeConnectivityStatsExtractor",
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
