// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// stacked.tif / darked.tif (1ch, 32bit float, 無圧縮) を読み書きする最小限の TIFF.
// Android 版は OpenCV で読み書きしているが, iPhone 版には OpenCV を入れないのでこちらを使う.
// OpenCV (IMWRITE_TIFF_COMPRESSION = 1) が書いたファイルも読める.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fk
{
    // 1ch float32 の無圧縮 TIFF (リトルエンディアン) にする
    void encodeTiffF32(const float *data, int width, int height, std::vector<uint8_t> &out);

    // 1ch の無圧縮 TIFF (8/16/32bit 整数, 32/64bit 浮動小数) を float にして読む.
    // 成功時は空文字列, 失敗時はエラーメッセージ
    std::string decodeTiff(const uint8_t *data, size_t size, std::vector<float> &out, int &width, int &height);
}
