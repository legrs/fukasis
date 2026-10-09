// SPDX-License-Identifier: MIT
// アプリの makecsv (native-lib.cpp) を PC 上でそのまま動かして, テストの正解データを作る.
// JNI と OpenCV は, makecsv が使う分だけを下のスタブで置き換えている.
// makecsv_body.inc は regenerate.sh が native-lib.cpp から抜き出して作る.
// 使い方: harness <calib.csv> <metadata.csv> <sensitivity.csv> <出力.csv> <0次光の位置>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "wavelength_calib.h"
#define LOGI(...) do {} while (0)
#define LOGE(...) do {} while (0)
#define JNIEXPORT
#define JNICALL
#define CV_32FC1 5
#define IMREAD_UNCHANGED -1
using namespace std;
typedef int jint; typedef void *jobject; typedef const char *jstring;
struct JNIEnv { jstring NewStringUTF(const char *s) { return s; } };
struct Mat {
    int rows = 0, cols = 0; vector<float> d;
    Mat() {} Mat(int, size_t, int, void *) {}
    template <class T> T &at(int y, int x) { return d[(size_t)y * cols + x]; }
};
static Mat g_img;
static Mat imdecode(const Mat &, int) { return g_img; }

#include "makecsv_body.inc"

// ---- 合成画像. 整数演算だけで作るので, どの言語でも同じ値になる
static uint32_t lcg_state;
static uint32_t lcg() { lcg_state = lcg_state * 1664525u + 1013904223u; return lcg_state >> 16; }
static int tri(int peak, int slope, int d) { int v = peak - slope * abs(d); return v > 0 ? v : 0; }
static Mat synth(int w, int h, int fol, uint32_t seed, bool light) {
    Mat m; m.rows = h; m.cols = w; m.d.resize((size_t)w * h);
    lcg_state = seed;
    const int center[3] = {80, 190, 290};           // B, G, R の連続光の中心 (0次光からの距離)
    const int lines[4] = {51, 189, 241, 271};       // 輝線
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        int ch = 1;
        if (x % 2 != 0 && y % 2 == 0) ch = 0; else if (x % 2 == 0 && y % 2 != 0) ch = 2;
        int v = 64 + (int)(lcg() % 9);
        bool hot = (lcg() % 499) == 0;
        if (light) {
            int t = fol - x;
            v += tri(400, 3, t - center[ch]);
            for (int k = 0; k < 4; k++) v += tri(300, 80, t - lines[k]);
            if (abs(x - fol) <= 5) v = 1023;
        }
        if (hot) v += 700;
        m.at<float>(y, x) = (float)v;
    }
    return m;
}
int main(int argc, char **argv) {
    // harness <calib.csv> <metadata.csv> <sensit.csv> <out.csv> <fol>
    const int W = 420, H = 96;
    Mat light = synth(W, H, 390, 12345u, true), dark = synth(W, H, 390, 777u, false);
    g_img = light;
    for (size_t i = 0; i < g_img.d.size(); i++) g_img.d[i] = light.d[i] - dark.d[i];
    int fd1 = open(argv[1], O_RDONLY);  // 中身は使われない (imdecode がスタブ) が, mmap できる必要がある
    int fd2 = open(argv[1], O_RDONLY), fd3 = open(argv[2], O_RDONLY), fd4 = open(argv[3], O_RDONLY);
    int fd5 = open(argv[4], O_WRONLY | O_CREAT | O_TRUNC, 0644);
    JNIEnv env;
    Java_com_example_ssa_CsvActivity_makecsv(&env, nullptr, fd1, fd2, fd3, fd4, fd5, atoi(argv[5]));
    close(fd5);
    if (argc > 6) {  // 合成画像がほかの言語の実装と同じかを確かめるための値
        double sum = 0;
        for (float v : g_img.d) sum += v;
        fprintf(stderr, "darked: sum=%.1f at(10,20)=%.1f at(50,339)=%.1f\n", sum, g_img.at<float>(10, 20), g_img.at<float>(50, 339));
    }
    return 0;
}
