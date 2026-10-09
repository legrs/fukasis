// host テスト用の最小限の JNI スタブ (native-lib.cpp が使う分だけ)
#pragma once
#include <cstdint>
#include <deque>
#include <string>

#define JNIEXPORT
#define JNICALL

typedef int32_t jint;
typedef int64_t jlong;
typedef double jdouble;
typedef uint8_t jboolean;
typedef int32_t jsize;

struct _jobject
{
    std::string str;
    void *buffer = nullptr;
};
typedef _jobject *jobject;
typedef jobject jclass;
typedef jobject jstring;

struct JNIEnv
{
    // 返した jstring はこの JNIEnv が持つ (テスト中は解放しない. 終了時にまとめて解放)
    std::deque<_jobject> strings;

    jstring NewStringUTF(const char *s)
    {
        strings.emplace_back();
        strings.back().str = s;
        return &strings.back();
    }
    void *GetDirectBufferAddress(jobject o)
    {
        return o ? o->buffer : nullptr;
    }
};
