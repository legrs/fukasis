// SPDX-License-Identifier: MIT
// アプリのスペクトル出力 (共通コアの fk::makeSpectrum) を PC 上でそのまま動かして, テストの正解データを作る.
// 使い方: harness <calib.csv> <metadata.csv> <sensitivity.csv> <出力.csv> <0次光の位置>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "spectrum.hpp"

using namespace std;

static string readFile(const char *path)
{
    ifstream in(path, ios::binary);
    stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

// ---- 合成画像. 整数演算だけで作るので, どの言語でも同じ値になる
static uint32_t lcg_state;
static uint32_t lcg()
{
    lcg_state = lcg_state * 1664525u + 1013904223u;
    return lcg_state >> 16;
}
static int tri(int peak, int slope, int d)
{
    int v = peak - slope * abs(d);
    return v > 0 ? v : 0;
}
static vector<float> synth(int w, int h, int fol, uint32_t seed, bool light)
{
    vector<float> m((size_t)w * h);
    lcg_state = seed;
    const int center[3] = {80, 190, 290};     // B, G, R の連続光の中心 (0次光からの距離)
    const int lines[4] = {51, 189, 241, 271}; // 輝線
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            // 色の並びは GBRG (アプリの既定)
            int ch = 1;
            if (x % 2 != 0 && y % 2 == 0)
                ch = 0;
            else if (x % 2 == 0 && y % 2 != 0)
                ch = 2;
            int v = 64 + (int)(lcg() % 9);
            bool hot = (lcg() % 499) == 0;
            if (light)
            {
                int t = fol - x;
                v += tri(400, 3, t - center[ch]);
                for (int k = 0; k < 4; k++)
                    v += tri(300, 80, t - lines[k]);
                if (abs(x - fol) <= 5)
                    v = 1023;
            }
            if (hot)
                v += 700;
            m[(size_t)y * w + x] = (float)v;
        }
    return m;
}

int main(int argc, char **argv)
{
    if (argc < 6)
    {
        fprintf(stderr, "usage: harness <calib.csv> <metadata.csv> <sensitivity.csv> <out.csv> <fol>\n");
        return 2;
    }
    const int W = 420, H = 96;
    vector<float> light = synth(W, H, 390, 12345u, true), dark = synth(W, H, 390, 777u, false);
    vector<float> darked(light.size());
    fk::subtract(light.data(), dark.data(), darked.data(), darked.size());

    fk::ImageView view;
    view.data = darked.data();
    view.width = W;
    view.height = H;
    view.stride = W;

    string csv;
    string err = fk::makeSpectrum(view, atoi(argv[5]), readFile(argv[1]), readFile(argv[2]), readFile(argv[3]),
                                  fk::SpectrumParams(), csv);
    if (!err.empty())
    {
        fprintf(stderr, "makeSpectrum: %s\n", err.c_str());
        return 1;
    }
    ofstream(argv[4], ios::binary) << csv;
    return 0;
}
