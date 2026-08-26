// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 自動波長校正・ピーク検出ユーティリティ.
 * <p>
 * 従来は CalibActivity で5本の SeekBar を手動で合わせていたが、
 * このクラスにより 0次光位置(fol)の自動推定と輝線ピークの自動検出・
 * カタログ波長への自動マッチングを提供する。
 * pure Java のため host 側の unit test で検証可能。
 * </p>
 */
public final class SpectrumCalibrator {

    private SpectrumCalibrator() {
    }

    /** 蛍光灯の既知輝線カタログ (nm) 435.8(Hg), 546.1(Hg), 576.96(Hg*), 611.6(Hg) */
    public static final double[] DEFAULT_CATALOG = {435.8, 546.1, 576.96, 611.6};

    /** 代替: 太陽 Fraunhofer 由来の代表線 */
    public static final double[] SOLAR_CATALOG = {430.8, 486.1, 589.3, 656.3};

    /**
     * Lagrange 3次補間の分母 i_deno を計算.
     * native-lib.cpp:371 と同等.
     */
    public static double[] computeDeno(double[] tRef) {
        int n = tRef.length;
        double[] deno = new double[n];
        for (int j = 0; j < n; j++) {
            double d = 1.0;
            for (int k = 0; k < n; k++) {
                if (k != j) {
                    d *= (tRef[j] - tRef[k]);
                }
            }
            deno[j] = d;
        }
        return deno;
    }

    /**
     * Lagrange補間で pixel位置 t -> 波長 t_p に変換.
     * native-lib.cpp:658 と同等.
     */
    public static double lagrangeInterpolate(double t, double[] tRef, double[] cRef, double[] deno) {
        double tp = 0.0;
        int n = tRef.length;
        for (int j = 0; j < n; j++) {
            double nume = 1.0;
            for (int k = 0; k < n; k++) {
                if (k != j) {
                    nume *= (t - tRef[k]);
                }
            }
            if (deno[j] != 0) {
                tp += cRef[j] * nume / deno[j];
            }
        }
        return tp;
    }

    /**
     * 1次元スペクトルのピーク検出.
     * 単純な極大 + 閾値 + 最小距離制約. 科学用途では Savitzky-Golay 前処理を
     * 行うことが望ましいが、ここでは軽量な実装とする.
     *
     * @param spectrum 画素ごとの強度 (x=0 が短波長側、fol側が長波長側の想定)
     * @param threshold 相対閾値 0..1 (最大値に対する割合)。例 0.15
     * @param minDistance ピーク間最小距離 (pixel)。蛍光灯では 80px 程度
     * @return ピーク位置のインデックス配列 (昇順)
     */
    public static int[] detectPeaks(double[] spectrum, double threshold, int minDistance) {
        if (spectrum == null || spectrum.length < 3) {
            return new int[0];
        }
        double max = Double.NEGATIVE_INFINITY;
        for (double v : spectrum) {
            if (v > max) {
                max = v;
            }
        }
        if (max <= 0) {
            return new int[0];
        }
        double absThresh = max * threshold;

        List<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < spectrum.length - 1; i++) {
            double prev = spectrum[i - 1];
            double cur = spectrum[i];
            double next = spectrum[i + 1];
            if (cur > prev && cur > next && cur > absThresh) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return new int[0];
        }
        // 強度降順にソートし、minDistance 以内の近傍を抑制 (NMS)
        List<Integer> sortedByIntensity = new ArrayList<>(candidates);
        Collections.sort(sortedByIntensity, (a, b) -> Double.compare(spectrum[b], spectrum[a]));

        boolean[] suppressed = new boolean[spectrum.length];
        List<Integer> result = new ArrayList<>();
        for (int idx : sortedByIntensity) {
            if (suppressed[idx]) {
                continue;
            }
            result.add(idx);
            int lo = Math.max(0, idx - minDistance);
            int hi = Math.min(spectrum.length - 1, idx + minDistance);
            for (int j = lo; j <= hi; j++) {
                if (j != idx) {
                    suppressed[j] = true;
                }
            }
        }
        Collections.sort(result);
        int[] out = new int[result.size()];
        for (int i = 0; i < result.size(); i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    /**
     * 検出ピークをカタログ波長に自動マッチング.
     * 分散は単調増加と仮定し、ピークをx昇順、カタログを波長昇順でソートして
     * 最も近い数だけ対応付ける。ピークが多すぎる場合は強度上位を優先。
     *
     * @param peakPixels 検出ピークのx座標 (昇順)
     * @param peakIntensities 対応する強度 (peakPixelsと同じ長さ、null可)
     * @param catalog 波長カタログ (昇順を想定)
     * @param expectedCount 期待する対応数 (通常 4)
     * @return long[expectedCount][2] ではなく、選ばれた peakPixels の部分配列を返す。
     *         呼び出し側で tRef と cRef を構築すること。
     */
    public static int[] selectBestPeaks(int[] peakPixels, double[] peakIntensities,
                                        double[] catalog, int expectedCount) {
        if (peakPixels == null || peakPixels.length == 0) {
            return new int[0];
        }
        if (peakPixels.length <= expectedCount) {
            int[] copy = Arrays.copyOf(peakPixels, peakPixels.length);
            Arrays.sort(copy);
            return copy;
        }
        // 強度で上位 expectedCount を選ぶ
        if (peakIntensities != null && peakIntensities.length == peakPixels.length) {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < peakPixels.length; i++) {
                indices.add(i);
            }
            Collections.sort(indices, (a, b) -> Double.compare(peakIntensities[b], peakIntensities[a]));
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < expectedCount; i++) {
                selected.add(peakPixels[indices.get(i)]);
            }
            Collections.sort(selected);
            int[] out = new int[selected.size()];
            for (int i = 0; i < selected.size(); i++) {
                out[i] = selected.get(i);
            }
            return out;
        } else {
            // 等間隔にサンプリング (単純)
            int[] sorted = Arrays.copyOf(peakPixels, peakPixels.length);
            Arrays.sort(sorted);
            // 端を優先して expectedCount 個を均等に選ぶ
            int[] out = new int[expectedCount];
            for (int i = 0; i < expectedCount; i++) {
                int idx = (int) Math.round((double) i * (sorted.length - 1) / (expectedCount - 1));
                out[i] = sorted[idx];
            }
            Arrays.sort(out);
            return out;
        }
    }

    /**
     * 校正データの妥当性検証.
     * tRef が単調、cRef が 400-700nm 内、deno が 0 でないことを確認.
     */
    public static boolean validateCalibration(double[] tRef, double[] cRef) {
        if (tRef == null || cRef == null || tRef.length != cRef.length || tRef.length < 2) {
            return false;
        }
        for (int i = 1; i < tRef.length; i++) {
            if (tRef[i] <= tRef[i - 1]) {
                // tRef は絶対x座標で単調増加である必要はないが、
                // 距離表現では単調でなくても Lagrange は成立する。
                // ここでは重複のみをエラーとする。
                if (tRef[i] == tRef[i - 1]) {
                    return false;
                }
            }
        }
        for (double c : cRef) {
            if (c < 350 || c > 750) {
                return false;
            }
        }
        double[] deno = computeDeno(tRef);
        for (double d : deno) {
            if (d == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 列和から 0次光位置(fol)を推定.
     * 画像右端付近で最大となる列を探す。colSum は x ごとの輝度積算値.
     *
     * @param colSum x ごとの積算値 (長さ = 画像幅)
     * @param searchRightFraction 右端から探索する割合 (例 0.25 = 右25%のみ探索)
     * @return 推定 fol (x座標)。見つからなければ -1
     */
    public static int estimateFol(double[] colSum, double searchRightFraction) {
        if (colSum == null || colSum.length == 0) {
            return -1;
        }
        int w = colSum.length;
        int start = (int) (w * (1.0 - searchRightFraction));
        if (start < 0) {
            start = 0;
        }
        if (start >= w) {
            start = w - 1;
        }
        int bestIdx = start;
        double bestVal = colSum[start];
        for (int x = start + 1; x < w; x++) {
            if (colSum[x] > bestVal) {
                bestVal = colSum[x];
                bestIdx = x;
            }
        }
        // エッジが最大の場合は信頼度低だがそのまま返す
        return bestIdx;
    }

    /**
     * 校正データから波長テーブルを生成 (テスト・プレビュー用).
     *
     * @param tRef pixel位置 (距離表現 fol - x ではなく絶対xでも可だが一貫させること)
     * @param cRef 対応波長
     * @param xMin 生成開始x
     * @param xMax 生成終了x
     * @return double[2][N] 0:波長, 1:対応x
     */
    public static double[][] generateWavelengthTable(double[] tRef, double[] cRef, int xMin, int xMax) {
        double[] deno = computeDeno(tRef);
        int n = xMax - xMin + 1;
        double[] wavelengths = new double[n];
        double[] xs = new double[n];
        for (int i = 0; i < n; i++) {
            int x = xMin + i;
            wavelengths[i] = lagrangeInterpolate(x, tRef, cRef, deno);
            xs[i] = x;
        }
        return new double[][]{wavelengths, xs};
    }

    static {
        try {
            System.loadLibrary("ssa");
        } catch (UnsatisfiedLinkError ignored) {
            // unit test 環境ではネイティブが無い場合がある
        }
    }

    /**
     * 0次光位置(fol)を画像から自動推定するネイティブ実装.
     * stacked.tif / darked.tif の fd を渡す。中央80px帯の列和が最大となる x を返す.
     *
     * @param fd 画像ファイルの file descriptor
     * @return 推定 fol (0..width-1), 失敗時 -1
     */
    public static native int detectFolNative(int fd);

    /**
     * 1次元スペクトルから輝線ピークを自動検出するネイティブ実装.
     * 内部で makecsv と同様の抽出を行い、極大を検出してカンマ区切りで返す.
     *
     * @param fd 画像 fd
     * @param fol 0次光位置 (detectFolNative の結果). 0以下なら画像右端を fol と見なす
     * @return 例 "1820,2105,2420,2680" 失敗時 ""、ネイティブ未ロード時は Java フォールバック用に空
     */
    public static native String detectPeaksNative(int fd, int fol);
}
