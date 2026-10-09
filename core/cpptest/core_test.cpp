// SPDX-License-Identifier: MIT
// 共通コア (spectrum / tiff / C API) を合成データで検証する. OpenCV 不要.
// CI の native-test ジョブから実行される:
//
//   c++ -std=c++17 -I ../Sources/FukasisCoreC -I ../Sources/FukasisCoreC/include ../Sources/FukasisCoreC/*.cpp core_test.cpp
#include "spectrum.hpp"
#include "tiff.hpp"
#include "fukasis_core.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <sstream>
#include <string>
#include <vector>

using namespace std;

static int failures = 0;

#define CHECK(cond)                                                                   \
    do                                                                                \
    {                                                                                 \
        if (!(cond))                                                                  \
        {                                                                             \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                               \
        }                                                                             \
    } while (0)

// ---- 合成データ (app/app/src/test/cpp/native_lib_test.cpp と同じ) ---------------

const int W = 3300;
const int H = 200;
const int FOL = 3200;
// fol からの距離 i -> 波長 λ = 430 + (i - 1900) * 0.3 (線形分散)
const string CALIB = "1900,2100,2300,2500\n430.000000,490.000000,550.000000,610.000000";
const string META = "test, 2026-10-01T00:00:00Z,  ISO 3200, fd 1.000000, 100 msec * 1 ";

static double wavelengthAt(int i)
{
    return 430 + (i - 1900) * 0.3;
}

struct Image
{
    int w, h;
    vector<float> px;
    fk::ImageView view() const
    {
        fk::ImageView v;
        v.data = px.data();
        v.width = w;
        v.height = h;
        v.stride = (size_t)w;
        return v;
    }
};

// value(i) で各列の値を決めた画像 (i = FOL - x)
static Image makeImage(const function<double(int)> &value)
{
    Image m{W, H, vector<float>((size_t)W * H)};
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++)
            m.px[(size_t)y * W + x] = (float)value(FOL - x);
    return m;
}

static string makeSensit(int from, int to, int step, const function<double(double)> &sum)
{
    stringstream ss;
    ss << "sensitivity\nwavelength,b,g,r\n";
    for (int l = from; l <= to; l += step)
    {
        double s = sum(l);
        ss << l << "," << s * 0.2 << "," << s * 0.3 << "," << s * 0.5 << "\n";
    }
    return ss.str();
}

struct Row
{
    double wl;
    double val;
};

static bool parseSpectrum(const string &text, vector<string> &head, vector<Row> &rows)
{
    stringstream ss(text);
    string line;
    head.clear();
    rows.clear();
    for (int i = 0; i < 2 && getline(ss, line); i++)
        head.push_back(line);
    vector<double> v;
    while (getline(ss, line))
    {
        if (!fk::parseNumbers(line, v) || v.size() != 2)
            return false;
        rows.push_back({v[0], v[1]});
    }
    return head.size() == 2;
}

static const Row &nearestRow(const vector<Row> &rows, double wl)
{
    size_t best = 0;
    for (size_t i = 1; i < rows.size(); i++)
        if (fabs(rows[i].wl - wl) < fabs(rows[best].wl - wl))
            best = i;
    return rows[best];
}

// ---- tests -------------------------------------------------------------

static void testCfa()
{
    fk::Cfa c;
    CHECK(fk::parseCfa("gbrg", c) && c == fk::Cfa::GBRG);
    CHECK(fk::parseCfa("MONO", c) && c == fk::Cfa::Mono);
    CHECK(!fk::parseCfa("RGBG", c));
    // 以前の native-lib.cpp の割り当て (GBRG) と同じ
    CHECK(fk::bayerChannel(fk::Cfa::GBRG, 0, 0) == 1);
    CHECK(fk::bayerChannel(fk::Cfa::GBRG, 1, 0) == 0);
    CHECK(fk::bayerChannel(fk::Cfa::GBRG, 0, 1) == 2);
    CHECK(fk::bayerChannel(fk::Cfa::GBRG, 1, 1) == 1);
    CHECK(fk::bayerChannel(fk::Cfa::RGGB, 0, 0) == 2);
    CHECK(fk::bayerChannel(fk::Cfa::RGGB, 1, 1) == 0);
    CHECK(fk::bayerChannel(fk::Cfa::BGGR, 2, 2) == 0);
    CHECK(fk::bayerChannel(fk::Cfa::GRBG, 3, 0) == 2);
    CHECK(fk::bayerChannel(fk::Cfa::Mono, 1, 0) == 1);

    CHECK(fk::cfaFromMetadata("seq, 2026, ISO 100, fd 1, 100 msec * 3 , cfa RGGB, device SM-S901", c) && c == fk::Cfa::RGGB);
    CHECK(!fk::cfaFromMetadata(META, c));
    CHECK(!fk::cfaFromMetadata("x, cfa XYZW", c));
    CHECK(fk_cfa_parse("BGGR") == FK_CFA_BGGR && fk_cfa_parse("??") == -1);
    CHECK(string(fk_cfa_name(FK_CFA_GRBG)) == "GRBG");
}

static void testParseNumbers()
{
    vector<double> v;
    CHECK(fk::parseNumbers("1, 2.5,3e2", v) && v.size() == 3 && v[2] == 300);
    CHECK(!fk::parseNumbers("1,abc", v));
    CHECK(!fk::parseNumbers("", v));
    CHECK(!fk::parseNumbers("1,,2", v));
}

static string spectrumOf(const Image &img, const string &calib, const string &meta, const string &sensit,
                         int fol, string &csv, fk::SpectrumParams p = fk::SpectrumParams())
{
    return fk::makeSpectrum(img.view(), fol, calib, meta, sensit, p, csv);
}

static void testSpectrumPeak()
{
    Image img = makeImage([](int i) { return 10 + 1000 * exp(-pow(i - 2200, 2) / (2 * 9.0)); });
    string sensit = makeSensit(350, 800, 10, [](double) { return 1.0; });
    string crlf;
    for (char c : sensit)
        crlf += (c == '\n') ? string("\r\n") : string(1, c);
    string csv;
    string err = spectrumOf(img, CALIB, META, crlf + "\r\n\r\n", FOL, csv);
    CHECK(err.empty());
    if (!err.empty())
        fprintf(stderr, "makeSpectrum: %s\n", err.c_str());

    vector<string> head;
    vector<Row> rows;
    CHECK(parseSpectrum(csv, head, rows));
    CHECK(head.size() == 2 && head[0] == META);
    CHECK(rows.size() == 999); // 1800 < i < 2800
    const Row *peak = nullptr;
    for (const Row &r : rows)
    {
        CHECK(r.wl > 400 && r.wl < 700);
        if (!peak || r.val > peak->val)
            peak = &r;
    }
    CHECK(peak && fabs(peak->val - 1.0) < 1e-6);
    CHECK(peak && fabs(peak->wl - 520.0) < 0.5);
}

static void testSpectrumParams()
{
    Image img = makeImage([](int i) { return 10 + 1000 * exp(-pow(i - 2200, 2) / (2 * 9.0)); });
    string sensit = makeSensit(350, 800, 10, [](double) { return 1.0; });
    string csv;
    vector<string> head;
    vector<Row> rows;

    // 切り出す範囲を狭めると出力も減る
    fk::SpectrumParams p;
    p.tMin = 2000;
    p.tMax = 2400;
    CHECK(spectrumOf(img, CALIB, META, sensit, FOL, csv, p).empty());
    CHECK(parseSpectrum(csv, head, rows) && rows.size() == 399);

    // 波長の範囲
    p = fk::SpectrumParams();
    p.wlMin = 500;
    p.wlMax = 600;
    CHECK(spectrumOf(img, CALIB, META, sensit, FOL, csv, p).empty());
    CHECK(parseSpectrum(csv, head, rows) && !rows.empty());
    for (const Row &r : rows)
        CHECK(r.wl > 500 && r.wl < 600);

    // どの Bayer 配列でも一様な画像のピーク位置は同じ
    for (fk::Cfa cfa : {fk::Cfa::RGGB, fk::Cfa::GRBG, fk::Cfa::BGGR, fk::Cfa::Mono})
    {
        p = fk::SpectrumParams();
        p.cfa = cfa;
        CHECK(spectrumOf(img, CALIB, META, sensit, FOL, csv, p).empty());
        CHECK(parseSpectrum(csv, head, rows) && rows.size() == 999);
        const Row *peak = nullptr;
        for (const Row &r : rows)
            if (!peak || r.val > peak->val)
                peak = &r;
        CHECK(peak && fabs(peak->wl - 520.0) < 0.5);
    }

    // 帯の位置: 下の方にだけ光がある画像は, 帯を下にずらすと読める
    Image low = makeImage([](int) { return 0.0; });
    for (int y = 150; y < 190; y++)
        for (int x = 0; x < W; x++)
            low.px[(size_t)y * W + x] = (float)(10 + 1000 * exp(-pow((FOL - x) - 2200, 2) / (2 * 9.0)));
    p = fk::SpectrumParams();
    CHECK(!spectrumOf(low, CALIB, META, sensit, FOL, csv, p).empty()); // 中央には何も写っていない
    p.bandCenter = 170.0 / H;
    p.bandWidth = 30;
    CHECK(spectrumOf(low, CALIB, META, sensit, FOL, csv, p).empty());
    CHECK(fk::detectBandCenter(low.view()) >= 150 && fk::detectBandCenter(low.view()) < 190);

    // 不正な範囲
    p = fk::SpectrumParams();
    p.tMax = p.tMin;
    CHECK(!spectrumOf(img, CALIB, META, sensit, FOL, csv, p).empty());
}

static void testSpectrumSensitivityInterpolation()
{
    Image img = makeImage([](int i) { return max(0, i - 1800); });
    string csv;
    CHECK(spectrumOf(img, CALIB, META, makeSensit(350, 800, 50, [](double l) { return l / 100.0; }), FOL, csv).empty());
    vector<string> head;
    vector<Row> rows;
    CHECK(parseSpectrum(csv, head, rows));
    if (rows.empty())
        return;
    auto expected = [](int i) { return 3.0 * (i - 1801) / (wavelengthAt(i) / 100.0); };
    const Row &a = nearestRow(rows, wavelengthAt(2000));
    const Row &b = nearestRow(rows, wavelengthAt(2600));
    CHECK(fabs((a.val / b.val) / (expected(2000) / expected(2600)) - 1.0) < 1e-3);
}

static void testSpectrumErrors()
{
    Image img = makeImage([](int i) { return max(0, i - 1800); });
    string sensit = makeSensit(350, 800, 10, [](double) { return 1.0; });
    string csv;
    CHECK(!spectrumOf(img, "1900,1900,2300,2500\n430,490,550,610", META, sensit, FOL, csv).empty());
    CHECK(!spectrumOf(img, "1900,2100,2300,2500\n", META, sensit, FOL, csv).empty());
    CHECK(!spectrumOf(img, CALIB, META, sensit, W, csv).empty());
    CHECK(!spectrumOf(img, CALIB, META, sensit, 0, csv).empty());
    CHECK(!spectrumOf(img, CALIB, META, "h\nh\n400,1,1,1\n410,x,1,1\n", FOL, csv).empty());
    CHECK(spectrumOf(img, CALIB, META, "h\nh\n350,1,1,1,9,9,9\n800,1,1,1,9,9,9\n", FOL, csv).empty());
    CHECK(!spectrumOf(img, CALIB, "", sensit, FOL, csv).empty());
    Image empty{0, 0, {}};
    CHECK(!fk::makeSpectrum(empty.view(), FOL, CALIB, META, sensit, fk::SpectrumParams(), csv).empty());
}

static void testTiff()
{
    // 32bit float の往復
    const int w = 7, h = 5;
    vector<float> px(w * h);
    for (int i = 0; i < w * h; i++)
        px[i] = (float)(i * 1.5 - 3.25);
    vector<uint8_t> bytes;
    fk::encodeTiffF32(px.data(), w, h, bytes);
    vector<float> back;
    int bw, bh;
    CHECK(fk::decodeTiff(bytes.data(), bytes.size(), back, bw, bh).empty());
    CHECK(bw == w && bh == h && back == px);

    // 壊れたファイル
    CHECK(!fk::decodeTiff(bytes.data(), 20, back, bw, bh).empty());
    CHECK(!fk::decodeTiff(nullptr, 0, back, bw, bh).empty());
    vector<uint8_t> notTiff = {'P', 'K', 3, 4, 0, 0, 0, 0};
    CHECK(!fk::decodeTiff(notTiff.data(), notTiff.size(), back, bw, bh).empty());

    // ビッグエンディアン, 16bit, 2 strip (OpenCV / libtiff が書きうる形)
    vector<uint8_t> be = {'M', 'M', 0, 42, 0, 0, 0, 8};
    auto push16 = [&](uint16_t v) { be.push_back(v >> 8); be.push_back(v & 0xff); };
    auto push32 = [&](uint32_t v) { for (int i = 3; i >= 0; i--) be.push_back((v >> (8 * i)) & 0xff); };
    const uint16_t entries = 9;
    const uint32_t ifdEnd = 8 + 2 + entries * 12 + 4;
    const uint32_t offsetsPos = ifdEnd, countsPos = ifdEnd + 8, pixelPos = ifdEnd + 16;
    push16(entries);
    auto entry = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t value) {
        push16(tag);
        push16(type);
        push32(count);
        if (type == 3 && count == 1)
        {
            push16((uint16_t)value);
            push16(0);
        }
        else
            push32(value);
    };
    entry(256, 3, 1, 3);  // width 3
    entry(257, 3, 1, 2);  // height 2
    entry(258, 3, 1, 16); // 16bit
    entry(259, 3, 1, 1);
    entry(262, 3, 1, 1);
    entry(273, 4, 2, offsetsPos); // 2 strips
    entry(277, 3, 1, 1);
    entry(278, 3, 1, 1); // 1行ずつ
    entry(279, 4, 2, countsPos);
    push32(0);
    push32(pixelPos);
    push32(pixelPos + 6);
    push32(6);
    push32(6);
    for (uint16_t v : {1, 2, 3, 400, 500, 65535})
        push16(v);
    CHECK(fk::decodeTiff(be.data(), be.size(), back, bw, bh).empty());
    CHECK(bw == 3 && bh == 2 && back.size() == 6 && back[3] == 400.0f && back[5] == 65535.0f);

    // C API
    uint8_t *enc = nullptr;
    size_t encSize = 0;
    fk_tiff_encode_f32(px.data(), w, h, &enc, &encSize);
    CHECK(enc != nullptr && encSize == bytes.size());
    float *dec = nullptr;
    int dw = 0, dh = 0;
    char *err = fk_tiff_decode(enc, encSize, &dec, &dw, &dh);
    CHECK(err == nullptr && dw == w && dh == h && dec != nullptr && dec[5] == px[5]);
    fk_free(err);
    fk_free(dec);
    err = fk_tiff_decode(enc, 10, &dec, &dw, &dh);
    CHECK(err != nullptr && dec == nullptr);
    fk_free(err);
    fk_free(enc);
}

static void testStacker()
{
    fk::Stacker s;
    s.reset(4, 3);
    vector<float> mean;
    CHECK(!s.mean(mean));
    // 行の後ろに余白がある (rowStride > width * 2) バッファ
    const size_t stride = 4 * 2 + 4;
    for (uint16_t value : {100, 300})
    {
        vector<uint8_t> buf(stride * 3, 0xee);
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 4; x++)
            {
                uint16_t v = (uint16_t)(value + x);
                memcpy(&buf[y * stride + x * 2], &v, 2);
            }
        CHECK(s.addU16(buf.data(), stride, buf.size()).empty());
    }
    CHECK(s.count() == 2);
    CHECK(s.mean(mean) && mean.size() == 12 && mean[0] == 200.0f && mean[3] == 203.0f && mean[11] == 203.0f);
    // 小さすぎるバッファ / null
    vector<uint8_t> small(4);
    CHECK(!s.addU16(small.data(), stride, small.size()).empty());
    CHECK(!s.addU16(nullptr, stride, 100).empty());
    CHECK(s.count() == 2);

    fk_stacker *c = fk_stacker_new(4, 3);
    vector<uint16_t> raw(12, 7);
    char *err = fk_stacker_add_u16(c, (const uint8_t *)raw.data(), 8, 24);
    CHECK(err == nullptr && fk_stacker_count(c) == 1);
    fk_free(err);
    vector<float> out(12);
    CHECK(fk_stacker_mean(c, out.data()) == 1 && out[4] == 7.0f);
    fk_stacker_free(c);
}

static void testProfileAndPreview()
{
    Image img = makeImage([](int i) { return 10 + 1000 * exp(-pow(i - 2200, 2) / (2 * 9.0)); });
    fk::SpectrumParams p;
    vector<double> prof = fk::columnProfile(img.view(), p);
    CHECK((int)prof.size() == W);
    int best = 0;
    for (int x = 0; x < W; x++)
        if (prof[x] > prof[best])
            best = x;
    CHECK(best == FOL - 2200);

    fk_spectrum_params cp = fk_default_spectrum_params();
    CHECK(cp.t_min == 1800 && cp.t_max == 2800 && cp.band_width == 80 && cp.cfa == FK_CFA_GBRG);
    vector<double> prof2(W);
    fk_column_profile(img.px.data(), W, H, &cp, prof2.data());
    CHECK(prof2 == prof);

    // プレビュー: GBRG の B 画素 (1,0) だけ明るい画像は 2x2 ごと青くなる
    vector<float> px(4 * 4, 0.0f);
    px[1] = 100.0f;
    vector<uint8_t> rgb(4 * 4 * 3);
    fk_preview_rgb8(px.data(), 4, 4, FK_CFA_GBRG, rgb.data());
    CHECK(rgb[0] == 0 && rgb[1] == 0 && rgb[2] == 255);    // (0,0): R G B
    CHECK(rgb[3 * 5 + 2] == 255);                          // (1,1) も同じかたまり
    CHECK(rgb[3 * 2 + 2] == 0);                            // 隣のかたまり
    fk_preview_rgb8(px.data(), 4, 4, FK_CFA_MONO, rgb.data());
    CHECK(rgb[3] == 255 && rgb[4] == 255 && rgb[0] == 0); // (1,0) が白
}

static void testCApiSpectrum()
{
    Image img = makeImage([](int i) { return 10 + 1000 * exp(-pow(i - 2200, 2) / (2 * 9.0)); });
    string sensit = makeSensit(350, 800, 10, [](double) { return 1.0; });
    fk_spectrum_params p = fk_default_spectrum_params();
    char *csv = nullptr;
    char *err = fk_make_spectrum(img.px.data(), W, H, FOL, CALIB.c_str(), META.c_str(), sensit.c_str(), &p, &csv);
    CHECK(err == nullptr && csv != nullptr);
    string expected;
    CHECK(fk::makeSpectrum(img.view(), FOL, CALIB, META, sensit, fk::SpectrumParams(), expected).empty());
    CHECK(csv != nullptr && expected == csv);
    fk_free(csv);
    fk_free(err);

    err = fk_make_spectrum(img.px.data(), W, H, 0, CALIB.c_str(), META.c_str(), sensit.c_str(), &p, &csv);
    CHECK(err != nullptr && csv == nullptr);
    fk_free(err);
    err = fk_make_spectrum(nullptr, W, H, FOL, nullptr, nullptr, nullptr, nullptr, &csv);
    CHECK(err != nullptr);
    fk_free(err);
}

int main()
{
    testCfa();
    testParseNumbers();
    testSpectrumPeak();
    testSpectrumParams();
    testSpectrumSensitivityInterpolation();
    testSpectrumErrors();
    testTiff();
    testStacker();
    testProfileAndPreview();
    testCApiSpectrum();

    if (failures)
    {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("all core tests passed\n");
    return 0;
}
