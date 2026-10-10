// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// Android (JNI) と iPhone (Swift) で共有するスペクトル処理の本体.
// OpenCV にも OS にも依存しない (画像は float の配列として受け取る).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fk
{
    // カラーフィルタ配列. 左上 2x2 を読み順に並べた名前 (Android の SENSOR_INFO_COLOR_FILTER_ARRANGEMENT と同じ順)
    enum class Cfa
    {
        RGGB = 0,
        GRBG = 1,
        GBRG = 2,
        BGGR = 3,
        Mono = 4,
    };

    // "RGGB" などを解釈する. 大文字小文字は区別しない. 不明なら false
    bool parseCfa(const std::string &name, Cfa &out);
    const char *cfaName(Cfa cfa);

    // 画素 (x, y) の色. 0 = B, 1 = G, 2 = R (Mono は常に 1)
    int bayerChannel(Cfa cfa, int x, int y);

    // 1ch の float 画像への参照 (コピーしない). stride は1行あたりの要素数
    struct ImageView
    {
        const float *data = nullptr;
        int width = 0;
        int height = 0;
        size_t stride = 0;

        float at(int x, int y) const { return data[(size_t)y * stride + (size_t)x]; }
    };

    // 機種ごとに変わりうるスペクトル切り出しのパラメータ (既定値は Galaxy S22 で使ってきた値)
    struct SpectrumParams
    {
        // 一次光が写るおおよその範囲 (0次光からの距離, pixel). 機種の目安として持っているだけで,
        // makeSpectrum は使わない (出力する範囲は校正データから波長で決める. wavelength_calib.h)
        int tMin = 1800;
        int tMax = 2800;
        // 縦に積算する帯の幅 (pixel) と中心 (画像の高さに対する割合)
        int bandWidth = 80;
        double bandCenter = 0.5;
        Cfa cfa = Cfa::GBRG;
        // 出力する波長の範囲 (nm)
        double wlMin = 400;
        double wlMax = 700;
    };

    // 積算する行の範囲 [y1, y2)
    void bandRows(const SpectrumParams &p, int height, int &y1, int &y2);

    // 列 x の帯 [y1, y2) をチャネルごとに sigma clipping して平均する. 写っていないチャネルは 0
    void columnChannels(const ImageView &img, int x, int y1, int y2, Cfa cfa, double out[3]);

    // 全列の (B + G + R) の値. 自動校正で 0次光や輝線を探すのに使う
    std::vector<double> columnProfile(const ImageView &img, const SpectrumParams &p);

    // スペクトルの帯が写っている行 (左 75% の行和が最大の行). 見つからなければ -1
    int detectBandCenter(const ImageView &img);

    // metadata の1行目に ", cfa XXXX" があれば撮影した端末のカラーフィルタ配列として返す
    bool cfaFromMetadata(const std::string &header, Cfa &out);

    // スペクトルを計算して CSV にする. 成功時は空文字列, 失敗時はエラーメッセージを返す.
    //   fol: 0次光の列, calibText: 校正データ (1行目 距離, 2行目 波長. 列の数が校正点の数で, 4 個以上を想定),
    //   metadataText: 1行目を CSV のヘッダにする, sensitivityText: 先頭2行ヘッダ, 各行 波長,b,g,r
    std::string makeSpectrum(const ImageView &img, int fol, const std::string &calibText,
                             const std::string &metadataText, const std::string &sensitivityText,
                             SpectrumParams params, std::string &csvOut);

    // 撮影した RAW (16bit) を足し合わせる
    class Stacker
    {
    public:
        void reset(int width, int height);
        // rowStrideBytes: 1行のバイト数, byteSize: バッファ全体のバイト数. 成功時は空文字列
        std::string addU16(const uint8_t *data, size_t rowStrideBytes, size_t byteSize);
        int count() const { return count_; }
        int width() const { return width_; }
        int height() const { return height_; }
        // 平均画像. 1枚も足していなければ false
        bool mean(std::vector<float> &out) const;

    private:
        int width_ = 0;
        int height_ = 0;
        int count_ = 0;
        std::vector<double> sum_;
    };

    // a - b を out に書く (n 要素)
    void subtract(const float *a, const float *b, float *out, size_t n);

    // 確認用のプレビュー. 最小..最大を 0..255 にして, 2x2 ごとに色をつける (out は width*height*3, RGB)
    void previewRgb8(const ImageView &img, Cfa cfa, uint8_t *out);

    // ---- テキスト処理 (テストからも使う) ----
    std::vector<std::string> splitLines(const std::string &text);
    bool isBlank(const std::string &s);
    // カンマ区切りの数値を全部パースする. 1つでも数値でなければ false
    bool parseNumbers(const std::string &line, std::vector<double> &out);
}
