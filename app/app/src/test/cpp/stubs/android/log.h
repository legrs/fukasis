// host テスト用スタブ: ログは stderr に出す
#pragma once
#include <cstdarg>
#include <cstdio>

enum
{
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_ERROR = 6
};

inline int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    if (prio < ANDROID_LOG_ERROR)
        return 0;
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "[%s] ", tag);
    int n = vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
    return n;
}
