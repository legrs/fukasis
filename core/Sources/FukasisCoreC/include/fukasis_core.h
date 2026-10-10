// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// FUKASIS のスペクトル処理の C インターフェース (Swift などから呼ぶため).
// 中身は spectrum.hpp / tiff.hpp (C++). Android は C++ を直接使っている.
//
// 文字列を返す関数は, 成功時に NULL, 失敗時にエラーメッセージ (UTF-8) を返す.
// 返された文字列やバッファは fk_free で解放すること.
#ifndef FUKASIS_CORE_H
#define FUKASIS_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// カラーフィルタ配列 (左上 2x2 の読み順)
enum {
    FK_CFA_RGGB = 0,
    FK_CFA_GRBG = 1,
    FK_CFA_GBRG = 2,
    FK_CFA_BGGR = 3,
    FK_CFA_MONO = 4,
};

typedef struct fk_spectrum_params {
    int t_min;          // スペクトルを切り出す 0次光からの距離 (pixel)
    int t_max;
    int band_width;     // 縦に積算する帯の幅 (pixel)
    double band_center; // 帯の中心 (画像の高さに対する割合)
    int cfa;            // FK_CFA_*
    double wl_min;      // 出力する波長の範囲 (nm)
    double wl_max;
} fk_spectrum_params;

// Galaxy S22 で使ってきた既定値
fk_spectrum_params fk_default_spectrum_params(void);

// "RGGB" などを FK_CFA_* にする. 不明なら -1
int fk_cfa_parse(const char *name);
// FK_CFA_* の名前 (解放不要)
const char *fk_cfa_name(int cfa);

void fk_free(void *p);

// スペクトル CSV を作る. 成功時は NULL を返し, *csv_out に CSV (fk_free で解放) を入れる
char *fk_make_spectrum(const float *img, int width, int height, int fol,
                       const char *calib_text, const char *metadata_text, const char *sensitivity_text,
                       const fk_spectrum_params *params, char **csv_out);

// 列ごとの (B+G+R) を out[width] に書く. 戻り値はスペクトルの帯が写っている行 (不明なら -1)
int fk_column_profile(const float *img, int width, int height, const fk_spectrum_params *params, double *out);

// 1ch float32 の無圧縮 TIFF にする. *out は fk_free で解放
void fk_tiff_encode_f32(const float *img, int width, int height, uint8_t **out, size_t *out_size);
// 1ch の無圧縮 TIFF を float にして読む. 成功時は NULL を返し, *out (fk_free で解放) に width*height 個入れる
char *fk_tiff_decode(const uint8_t *data, size_t size, float **out, int *width, int *height);

// RAW (16bit) の積算
typedef struct fk_stacker fk_stacker;
fk_stacker *fk_stacker_new(int width, int height);
void fk_stacker_free(fk_stacker *s);
char *fk_stacker_add_u16(fk_stacker *s, const uint8_t *data, size_t row_stride_bytes, size_t byte_size);
int fk_stacker_count(const fk_stacker *s);
// 平均画像を out[width*height] に書く. 1枚も足していなければ 0 を返す
int fk_stacker_mean(const fk_stacker *s, float *out);

// out[i] = a[i] - b[i]
void fk_subtract(const float *a, const float *b, float *out, size_t n);

// 確認用の RGB プレビュー (out は width*height*3)
void fk_preview_rgb8(const float *img, int width, int height, int cfa, uint8_t *out);

#ifdef __cplusplus
}
#endif

#endif
