// SPDX-License-Identifier: MIT
package com.example.ssa;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// RAW 画像 (Bayer, 16bit) の一次光領域が白飛びしていないかをチャンネルごとに調べる
public class SaturationChecker {

    // CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT と同じ値
    public static final int CFA_RGGB = 0;
    public static final int CFA_GRBG = 1;
    public static final int CFA_GBRG = 2;
    public static final int CFA_BGGR = 3;

    public static final int R = 0;
    public static final int G = 1;
    public static final int B = 2;

    // 白レベルのこの割合以上を白飛びとみなす
    static final double SATURATION_RATIO = 0.98;
    // ホットピクセルを白飛びと誤判定しないための最小画素数
    static final int MIN_SATURATED_PIXELS = 4;

    // 一次光領域の定義. csv 画面 (makecsv) がスペクトルを読む範囲と合わせてある
    static final int BAND_HEIGHT = 80; // 画像中央の横帯の幅 (makecsv の width)
    static final int FOL_OFS_MIN = 300; // csv 画面の 0次光スライダーの範囲
    static final int FOL_OFS_MAX = 550;
    static final int T_MIN = 1400; // 400 - 700 nm が写る, 0次光からの距離のおおよその範囲
    static final int T_MAX = 2800;

    // [配列][(y%2)*2 + x%2] -> チャンネル
    private static final int[][] CFA_TABLE = {
            { R, G, G, B },
            { G, R, B, G },
            { G, B, R, G },
            { B, G, G, R },
    };

    public static class Result {
        public final int whiteLevel;
        public final int[] peak = new int[3];
        public final int[] saturatedPixels = new int[3];
        public final int[] pixels = new int[3];

        Result(int whiteLevel) {
            this.whiteLevel = whiteLevel;
        }

        // 白レベルを 100 としたピーク値
        public int peakPercent(int ch) {
            return (int) Math.round(100.0 * peak[ch] / whiteLevel);
        }

        public boolean isSaturated(int ch) {
            return saturatedPixels[ch] >= MIN_SATURATED_PIXELS;
        }

        public boolean anySaturated() {
            return isSaturated(R) || isSaturated(G) || isSaturated(B);
        }
    }

    // 一次光が写る領域 {x1, y1, x2, y2} (x2, y2 は含まない)
    public static int[] firstOrderRegion(int width, int height) {
        int x1 = Math.max(0, width - FOL_OFS_MAX - T_MAX);
        int x2 = Math.min(width, width - FOL_OFS_MIN - T_MIN);
        if (x2 <= x1) {
            // 想定より小さいセンサでは横方向は全域を見る
            x1 = 0;
            x2 = width;
        }
        int y1 = Math.max(0, height / 2 - BAND_HEIGHT / 2);
        int y2 = Math.min(height, height / 2 + BAND_HEIGHT / 2);
        return new int[] { x1, y1, x2, y2 };
    }

    public static Result analyze(ByteBuffer buff, int rowStride, int width, int height, int whiteLevel, int cfa) {
        int[] region = firstOrderRegion(width, height);
        return analyze(buff, rowStride, whiteLevel, cfa, region[0], region[1], region[2], region[3]);
    }

    public static Result analyze(ByteBuffer buff, int rowStride, int whiteLevel, int cfa,
            int x1, int y1, int x2, int y2) {
        if (cfa < 0 || cfa >= CFA_TABLE.length) {
            // 不明な配列は makecsv の想定に合わせる
            cfa = CFA_GBRG;
        }
        int[] table = CFA_TABLE[cfa];
        // 位置や byte order を元のバッファに影響させない
        ByteBuffer b = buff.duplicate().order(ByteOrder.nativeOrder());
        int capacity = b.capacity();
        int threshold = (int) Math.ceil(whiteLevel * SATURATION_RATIO);

        Result result = new Result(whiteLevel);
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int index = y * rowStride + x * 2;
                if (index < 0 || index + 1 >= capacity) {
                    continue;
                }
                int val = b.getShort(index) & 0xFFFF;
                int ch = table[(y % 2) * 2 + x % 2];
                result.pixels[ch]++;
                if (result.peak[ch] < val) {
                    result.peak[ch] = val;
                }
                if (threshold <= val) {
                    result.saturatedPixels[ch]++;
                }
            }
        }
        return result;
    }
}
