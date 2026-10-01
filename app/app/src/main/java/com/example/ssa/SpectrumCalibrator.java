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
 * 画像のデコードと1次元プロファイルの抽出だけをネイティブ (auto_calib.cpp) で行い、
 * それ以外は pure Java なので host 側の unit test で検証できる。
 * </p>
 * <p>
 * 座標系: x は画像の列, fol は 0次光の列, 距離 d = fol - x (makecsv の t と同じ).
 * スライダーの値は imgWidth - x.
 * </p>
 */
public final class SpectrumCalibrator {

    private SpectrumCalibrator() {
    }

    /**
     * 三波長型蛍光灯の輝線 (nm). CalibActivity の初期値と同じ.
     * 588.0 はオレンジの輝線2本のうち長波長側 (README 参照).
     */
    public static final double[] DEFAULT_CATALOG = {435.8, 546.1, 588.0, 611.6};

    /** 代替: 太陽 Fraunhofer 由来の代表線 */
    public static final double[] SOLAR_CATALOG = {430.8, 486.1, 589.3, 656.3};

    /** 分散 (nm/pixel) の探索範囲. makecsv は距離 1800..2800px を 400..700nm (約 0.3 nm/px) と想定している */
    public static final double MIN_NM_PER_PX = 0.2;
    public static final double MAX_NM_PER_PX = 0.45;
    /** カタログとの対応付けで許す直線フィットの残差 (RMS, nm) */
    public static final double MAX_FIT_RMS_NM = 4.0;
    /** ピーク検出の相対閾値, ピーク間の最小距離, 平滑化の半径 (pixel) */
    public static final double PEAK_THRESHOLD = 0.05;
    public static final int PEAK_MIN_DISTANCE = 12;
    public static final int SMOOTH_RADIUS = 2;
    /** カタログ対応付けで組合せを試すピーク数の上限 (強度上位) */
    public static final int MAX_CANDIDATES = 12;

    /** ユーザに理由を見せるための例外 */
    public static final class CalibrationException extends Exception {
        public CalibrationException(String message) {
            super(message);
        }
    }

    /**
     * Lagrange 3次補間の分母 i_deno を計算.
     * native-lib.cpp (makecsv) と同等.
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
     * native-lib.cpp (makecsv) と同等.
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

    /** 半径 radius の移動平均. Bayer 配列による1画素ごとの段差をならす */
    public static double[] smooth(double[] s, int radius) {
        double[] out = new double[s.length];
        for (int i = 0; i < s.length; i++) {
            int lo = Math.max(0, i - radius);
            int hi = Math.min(s.length - 1, i + radius);
            double sum = 0;
            for (int j = lo; j <= hi; j++) {
                sum += s[j];
            }
            out[i] = sum / (hi - lo + 1);
        }
        return out;
    }

    /**
     * 1次元スペクトルのピーク検出.
     * 単純な極大 + 閾値 + 最小距離制約 (強い方を残す NMS).
     *
     * @param spectrum 画素ごとの強度
     * @param threshold 相対閾値 0..1 (最大値に対する割合)
     * @param minDistance ピーク間最小距離 (pixel)
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
            // 平坦な頂上 (cur == next) でも1点は拾えるよう右側は >=
            if (cur > prev && cur >= next && cur > absThresh) {
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

    /** 検出したピーク: fol からの距離 (昇順) と強度 */
    public static final class Peaks {
        public final double[] distances;
        public final double[] intensities;

        Peaks(double[] distances, double[] intensities) {
            this.distances = distances;
            this.intensities = intensities;
        }
    }

    /**
     * fol からの距離 [minDist, maxDist] の範囲だけでピークを探す.
     * 0次光そのものや範囲外の線に閾値を引っぱられないよう, 範囲内の最小値を背景として引いてから検出する.
     */
    public static Peaks findPeaksInWindow(double[] profile, int fol, int minDist, int maxDist) {
        int lo = Math.max(1, minDist);
        int hi = Math.min(maxDist, fol);
        if (profile == null || fol >= profile.length || hi - lo < 3) {
            return new Peaks(new double[0], new double[0]);
        }
        double[] s = new double[hi - lo + 1];
        for (int d = lo; d <= hi; d++) {
            s[d - lo] = profile[fol - d];
        }
        s = smooth(s, SMOOTH_RADIUS);
        double min = Double.POSITIVE_INFINITY;
        for (double v : s) {
            min = Math.min(min, v);
        }
        for (int i = 0; i < s.length; i++) {
            s[i] -= min;
        }
        int[] idx = detectPeaks(s, PEAK_THRESHOLD, PEAK_MIN_DISTANCE);
        double[] distances = new double[idx.length];
        double[] intensities = new double[idx.length];
        for (int i = 0; i < idx.length; i++) {
            distances[i] = lo + idx[i];
            intensities[i] = s[idx[i]];
        }
        return new Peaks(distances, intensities);
    }

    /** カタログとの対応付けの結果 */
    public static final class CatalogMatch {
        /** catalog[i] の輝線の fol からの距離 (catalog と同じ順) */
        public final double[] distances;
        /** 直線フィット 波長 = offsetNm + nmPerPx * 距離 */
        public final double nmPerPx;
        public final double offsetNm;
        /** フィットの残差 (RMS, nm) */
        public final double rmsNm;

        CatalogMatch(double[] distances, double nmPerPx, double offsetNm, double rmsNm) {
            this.distances = distances;
            this.nmPerPx = nmPerPx;
            this.offsetNm = offsetNm;
            this.rmsNm = rmsNm;
        }
    }

    /**
     * 検出ピークをカタログ波長に自動マッチング.
     * <p>
     * 波長は 0次光からの距離にほぼ比例する (長波長ほど遠い). 強度上位のピークから catalog.length 本を
     * 距離の昇順に選ぶ全組合せについて, 波長の昇順と対応させて直線フィットし,
     * 分散が [minNmPerPx, maxNmPerPx] に入る中で残差が最小の組を選ぶ.
     * 輝線の間隔の比で決まるので, 余分な線 (蛍光灯の Tb/Eu の線など) が混じっていても正しい組を選べる.
     * </p>
     *
     * @param peakDistances   検出ピークの fol からの距離
     * @param peakIntensities 対応する強度 (null 可. null なら全ピークを候補にする)
     * @param catalog         波長カタログ (順不同可)
     * @return 対応付け. 条件を満たす組がなければ null
     */
    public static CatalogMatch matchCatalog(double[] peakDistances, double[] peakIntensities, double[] catalog,
                                            double minNmPerPx, double maxNmPerPx, double maxRmsNm) {
        int n = catalog == null ? 0 : catalog.length;
        if (n < 2 || peakDistances == null || peakDistances.length < n) {
            return null;
        }
        // カタログを波長順に (重複があれば対応付けできない)
        Integer[] catOrder = new Integer[n];
        for (int i = 0; i < n; i++) {
            catOrder[i] = i;
        }
        Arrays.sort(catOrder, (a, b) -> Double.compare(catalog[a], catalog[b]));
        double[] wl = new double[n];
        for (int i = 0; i < n; i++) {
            wl[i] = catalog[catOrder[i]];
            if (i > 0 && wl[i] == wl[i - 1]) {
                return null;
            }
        }

        // 候補: 強度上位 MAX_CANDIDATES 本を距離順に
        Integer[] order = new Integer[peakDistances.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        if (peakIntensities != null && peakIntensities.length == peakDistances.length) {
            Arrays.sort(order, (a, b) -> Double.compare(peakIntensities[b], peakIntensities[a]));
        }
        int m = Math.min(order.length, Math.max(MAX_CANDIDATES, n));
        double[] cand = new double[m];
        double[] candIntensity = new double[m];
        Integer[] byDistance = Arrays.copyOf(order, m);
        Arrays.sort(byDistance, (a, b) -> Double.compare(peakDistances[a], peakDistances[b]));
        for (int i = 0; i < m; i++) {
            cand[i] = peakDistances[byDistance[i]];
            candIntensity[i] = peakIntensities != null && peakIntensities.length == peakDistances.length
                    ? peakIntensities[byDistance[i]] : 0;
        }

        int[] pick = new int[n];
        double[] best = null; // {rms, slope, offset, intensitySum}
        int[] bestPick = null;
        // 距離の昇順に n 本選ぶ組合せを列挙
        for (int i = 0; i < n; i++) {
            pick[i] = i;
        }
        while (true) {
            double[] fit = fitLine(cand, pick, wl);
            if (fit != null && fit[1] >= minNmPerPx && fit[1] <= maxNmPerPx && fit[0] <= maxRmsNm) {
                double intensitySum = 0;
                for (int p : pick) {
                    intensitySum += candIntensity[p];
                }
                boolean better = best == null
                        || fit[0] < best[0] - 1e-9
                        || (Math.abs(fit[0] - best[0]) <= 1e-9 && intensitySum > best[3]);
                if (better) {
                    best = new double[]{fit[0], fit[1], fit[2], intensitySum};
                    bestPick = pick.clone();
                }
            }
            // 次の組合せ
            int k = n - 1;
            while (k >= 0 && pick[k] == m - n + k) {
                k--;
            }
            if (k < 0) {
                break;
            }
            pick[k]++;
            for (int j = k + 1; j < n; j++) {
                pick[j] = pick[j - 1] + 1;
            }
        }
        if (bestPick == null) {
            return null;
        }
        double[] distances = new double[n];
        for (int i = 0; i < n; i++) {
            distances[catOrder[i]] = cand[bestPick[i]];
        }
        return new CatalogMatch(distances, best[1], best[2], best[0]);
    }

    /** wl[i] = offset + slope * x[pick[i]] の最小二乗. {rms, slope, offset} を返す */
    private static double[] fitLine(double[] x, int[] pick, double[] wl) {
        int n = pick.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double xi = x[pick[i]];
            sx += xi;
            sy += wl[i];
            sxx += xi * xi;
            sxy += xi * wl[i];
        }
        double den = n * sxx - sx * sx;
        if (den == 0) {
            return null;
        }
        double slope = (n * sxy - sx * sy) / den;
        double offset = (sy - slope * sx) / n;
        double ss = 0;
        for (int i = 0; i < n; i++) {
            double r = wl[i] - (offset + slope * x[pick[i]]);
            ss += r * r;
        }
        return new double[]{Math.sqrt(ss / n), slope, offset};
    }

    /**
     * 校正データの妥当性検証.
     * tRef に重複がなく、cRef が 350-750nm 内、deno が 0 でないことを確認.
     */
    public static boolean validateCalibration(double[] tRef, double[] cRef) {
        if (tRef == null || cRef == null || tRef.length != cRef.length || tRef.length < 2) {
            return false;
        }
        for (int i = 0; i < tRef.length; i++) {
            for (int k = i + 1; k < tRef.length; k++) {
                if (tRef[i] == tRef[k]) {
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
     * 列ごとの値から 0次光位置(fol)を推定.
     * 画像右端付近で最大となる列を探す.
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
        return estimateFolInRange(colSum, start, w - 1);
    }

    /** x ∈ [xMin, xMax] で値が最大の列を返す. 範囲が空なら -1 */
    public static int estimateFolInRange(double[] colSum, int xMin, int xMax) {
        if (colSum == null || colSum.length == 0) {
            return -1;
        }
        int lo = Math.max(0, xMin);
        int hi = Math.min(colSum.length - 1, xMax);
        if (lo > hi) {
            return -1;
        }
        int bestIdx = lo;
        for (int x = lo + 1; x <= hi; x++) {
            if (colSum[x] > colSum[bestIdx]) {
                bestIdx = x;
            }
        }
        return bestIdx;
    }

    /** 画像の列プロファイル (analyzeImageNative の結果) */
    public static final class ImageProfile {
        public final int width;
        public final int height;
        /** スペクトルの帯が写っている行. 不明なら -1 */
        public final int bandCenterY;
        /** profile[x] = makecsv と同じ中央 80px 帯の列 x の値 */
        public final double[] profile;

        public ImageProfile(int width, int height, int bandCenterY, double[] profile) {
            this.width = width;
            this.height = height;
            this.bandCenterY = bandCenterY;
            this.profile = profile;
        }

        /** analyzeImageNative の戻り値 [width, height, bandCenterY, profile...] を解釈する. 不正なら null */
        public static ImageProfile fromNative(double[] raw) {
            if (raw == null || raw.length < 3) {
                return null;
            }
            int w = (int) raw[0];
            int h = (int) raw[1];
            if (w <= 0 || h <= 0 || raw.length != 3 + w) {
                return null;
            }
            return new ImageProfile(w, h, (int) raw[2], Arrays.copyOfRange(raw, 3, raw.length));
        }
    }

    /** makecsv がスペクトルを積算する帯 (画像中央 80px) の半分の幅 */
    public static final int BAND_HALF_WIDTH = 40;

    /**
     * スペクトルの帯が makecsv の積算する中央の帯から外れていれば警告文を返す. 問題なければ null.
     * 分光器が傾いている/ずれていると, 何も言われずに暗いスペクトルが出力されてしまうため.
     */
    public static String bandOffsetWarning(ImageProfile img) {
        if (img == null || img.bandCenterY < 0) {
            return null;
        }
        int offset = img.bandCenterY - img.height / 2;
        // 帯の中心が積算範囲の 3/4 より外にあれば, 帯の大部分が積算から漏れている
        if (Math.abs(offset) <= BAND_HALF_WIDTH * 3 / 4) {
            return null;
        }
        return "スペクトルの帯が画像の中央から" + (offset > 0 ? "下" : "上") + "に " + Math.abs(offset)
                + " px ずれています (スペクトル出力は中央の " + (2 * BAND_HALF_WIDTH)
                + " px を使います). 分光器の取り付けを確認してください";
    }

    /**
     * 0次光の位置を, スライダーで表せる範囲 (progress ∈ [progMin, progMax], progress = imgWidth - x) から探す.
     *
     * @return スライダーの値
     */
    public static int detectFolProgress(ImageProfile img, int imgWidth, int progMin, int progMax)
            throws CalibrationException {
        if (img.width != imgWidth) {
            throw new CalibrationException("tif (幅 " + img.width + ") と表示中の画像 (幅 " + imgWidth
                    + ") の大きさが違います");
        }
        int fol = estimateFolInRange(img.profile, imgWidth - progMax, imgWidth - progMin);
        if (fol < 0) {
            throw new CalibrationException("0次光を探す範囲が画像の外です");
        }
        // 0次光は周りより十分明るいはず. 背景 (中央値) と区別できなければ失敗とする
        double[] sorted = img.profile.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double peak = img.profile[fol];
        if (!(peak > 0) || peak <= 2 * Math.max(median, 0)) {
            throw new CalibrationException("0次光が見つかりません (スライダーの範囲内に明るい点がありません)");
        }
        return imgWidth - fol;
    }

    /** 自動校正の結果 (スライダーの値) */
    public static final class CalibrationResult {
        public final int folProgress;
        /** catalog[i] の輝線のスライダー値 (catalog と同じ順) */
        public final int[] peakProgress;
        public final CatalogMatch match;
        public final int peakCount;

        CalibrationResult(int folProgress, int[] peakProgress, CatalogMatch match, int peakCount) {
            this.folProgress = folProgress;
            this.peakProgress = peakProgress;
            this.match = match;
            this.peakCount = peakCount;
        }
    }

    /**
     * プロファイルから 0次光と輝線を検出し, catalog の各波長に対応するスライダー値を求める.
     *
     * @param folProgMin  0次光スライダーの範囲
     * @param peakProgMin 輝線スライダーの範囲
     */
    public static CalibrationResult calibrate(ImageProfile img, int imgWidth, int folProgMin, int folProgMax,
                                              int peakProgMin, int peakProgMax, double[] catalog)
            throws CalibrationException {
        int folProgress = detectFolProgress(img, imgWidth, folProgMin, folProgMax);
        int fol = imgWidth - folProgress;
        // 輝線スライダーで表せる距離の範囲 (d = progress - folProgress)
        Peaks peaks = findPeaksInWindow(img.profile, fol, peakProgMin - folProgress, peakProgMax - folProgress);
        CatalogMatch match = matchCatalog(peaks.distances, peaks.intensities, catalog,
                MIN_NM_PER_PX, MAX_NM_PER_PX, MAX_FIT_RMS_NM);
        if (match == null) {
            throw new CalibrationException("輝線をカタログの波長に対応付けられません (検出 " + peaks.distances.length
                    + " 本). 波長の値を確認するか, 手動で合わせてください");
        }
        if (!validateCalibration(match.distances, catalog)) {
            throw new CalibrationException("校正データが不正です (波長は 350-750nm で, 重複のないように)");
        }
        int[] progress = new int[catalog.length];
        for (int i = 0; i < catalog.length; i++) {
            progress[i] = folProgress + (int) Math.round(match.distances[i]);
        }
        return new CalibrationResult(folProgress, progress, match, peaks.distances.length);
    }

    static {
        try {
            System.loadLibrary("ssa");
        } catch (UnsatisfiedLinkError ignored) {
            // unit test 環境ではネイティブが無い場合がある
        }
    }

    /**
     * 画像を解析して [width, height, bandCenterY, profile[0..width-1]] を返すネイティブ実装 (auto_calib.cpp).
     * {@link ImageProfile#fromNative} で解釈する.
     *
     * @param fd 画像ファイル (stacked.tif / darked.tif) の file descriptor
     * @return 失敗時 null
     */
    public static native double[] analyzeImageNative(int fd);
}
