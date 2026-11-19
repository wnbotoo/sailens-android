#pragma once

#include <jni.h>

namespace sailens {

jboolean JNICALL fillWorldPassableTable(
        JNIEnv* env, jobject receiver, jint width, jint height, jintArray labels,
        jlongArray effectiveWords, jbooleanArray passable, jintArray sums);

jint JNICALL classifyWorldGrid(
        JNIEnv* env, jobject receiver, jdoubleArray rows, jdoubleArray edges, jdoubleArray rawBounds,
        jint rawCount, jdoubleArray axes, jint width, jint height, jintArray sums, jlongArray contacts,
        jlongArray conflicts, jbooleanArray surface);

}  // namespace sailens
