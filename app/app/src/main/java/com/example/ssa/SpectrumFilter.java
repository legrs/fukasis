// SPDX-License-Identifier: MIT
package com.example.ssa;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

// スペクトル (波長, 強度) をグラフに出せる形に整える. 明らかな異常値の除外もここで行う
public class SpectrumFilter {

    // 孤立したスパイクの判定に使う, 片側の近傍点数
    static final int SPIKE_WINDOW = 5;
    // これより長く続く山は輝線などの本物の構造とみなして残す
    static final int SPIKE_MAX_RUN = 2;
    // 近傍の中央値からのずれがこの倍率 (近傍のばらつき比) を超えたらスパイク候補
    static final double SPIKE_SIGMA = 12.0;
    // 同じく, 全体の値域に対する割合
    static final double SPIKE_RANGE_RATIO = 0.15;
    // スパイクの隣がこの割合以上持ち上がっていたら, 裾のある本物のピークとみなして残す
    static final double SPIKE_SHOULDER_RATIO = 0.2;
    static final int SPIKE_MAX_PASS = 3;

    public static class Result {
        // 波長の昇順
        public final float[] wavelength;
        public final float[] intensity;
        // 表示から外した点数
        public final int excluded;

        Result(float[] wavelength, float[] intensity, int excluded) {
            this.wavelength = wavelength;
            this.intensity = intensity;
            this.excluded = excluded;
        }
    }

    // C++ が書き出す "nan" や "inf" も読めるようにした parseFloat. 数値でなければ NumberFormatException
    public static float parseValue(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        String unsigned = (v.startsWith("-") || v.startsWith("+")) ? v.substring(1) : v;
        if (unsigned.equals("nan")) {
            return Float.NaN;
        }
        if (unsigned.equals("inf") || unsigned.equals("infinity")) {
            return v.startsWith("-") ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        return Float.parseFloat(v);
    }

    // wavelength, intensity はファイルに書かれていた順.
    // NaN や無限大は常に外す. excludeOutliers のときはさらに
    //   - 波長の並びが折り返している部分 (最も長い単調な区間だけ残す)
    //   - 負の強度
    //   - 孤立したスパイク
    // を外す
    public static Result filter(float[] wavelength, float[] intensity, boolean excludeOutliers) {
        int n = Math.min(wavelength.length, intensity.length);
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            keep[i] = isFinite(wavelength[i]) && isFinite(intensity[i]);
        }

        if (excludeOutliers) {
            keepLongestMonotonicRun(wavelength, keep);
            for (int i = 0; i < n; i++) {
                if (intensity[i] < 0) {
                    keep[i] = false;
                }
            }
            for (int pass = 0; pass < SPIKE_MAX_PASS; pass++) {
                if (!dropIsolatedSpikes(intensity, keep)) {
                    break;
                }
            }
        }

        final Integer[] order = indicesOf(keep);
        final float[] wl = wavelength;
        // グラフは波長の昇順でないと描けない (Arrays.sort は安定ソート)
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(wl[a], wl[b]);
            }
        });
        float[] x = new float[order.length];
        float[] y = new float[order.length];
        for (int i = 0; i < order.length; i++) {
            x[i] = wavelength[order[i]];
            y[i] = intensity[order[i]];
        }
        return new Result(x, y, n - order.length);
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static Integer[] indicesOf(boolean[] keep) {
        int count = 0;
        for (boolean k : keep) {
            if (k) {
                count++;
            }
        }
        Integer[] indices = new Integer[count];
        int j = 0;
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                indices[j++] = i;
            }
        }
        return indices;
    }

    // 波長がファイル順で単調に変化している区間のうち, 最も長いものだけを残す
    private static void keepLongestMonotonicRun(float[] wavelength, boolean[] keep) {
        Integer[] idx = indicesOf(keep);
        int m = idx.length;
        if (m < 3) {
            return;
        }
        int bestStart = 0;
        int bestEnd = 0; // 含む
        int start = 0;
        int dir = 0;
        for (int k = 1; k < m; k++) {
            int d = Float.compare(wavelength[idx[k]], wavelength[idx[k - 1]]);
            if (d != 0 && dir != 0 && d != dir) {
                // 向きが変わった. 折り返し点は次の区間の先頭にもなる
                if (bestEnd - bestStart < (k - 1) - start) {
                    bestStart = start;
                    bestEnd = k - 1;
                }
                start = k - 1;
                dir = d;
            } else if (dir == 0) {
                dir = d;
            }
        }
        if (bestEnd - bestStart < (m - 1) - start) {
            bestStart = start;
            bestEnd = m - 1;
        }
        for (int k = 0; k < m; k++) {
            if (k < bestStart || bestEnd < k) {
                keep[idx[k]] = false;
            }
        }
    }

    // 近傍の中央値から大きく外れた, 1 - 2 点だけで裾も無い孤立したスパイクを外す. 外したものがあれば true
    private static boolean dropIsolatedSpikes(float[] intensity, boolean[] keep) {
        Integer[] idx = indicesOf(keep);
        int m = idx.length;
        if (m < 2 * SPIKE_WINDOW + 1) {
            return false;
        }
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < m; k++) {
            min = Math.min(min, intensity[idx[k]]);
            max = Math.max(max, intensity[idx[k]]);
        }
        double range = (double) max - min;
        if (range <= 0) {
            return false;
        }

        boolean[] spike = new boolean[m];
        double[] deviation = new double[m]; // 近傍の中央値からのずれ
        double[] neighbors = new double[2 * SPIKE_WINDOW];
        for (int k = 0; k < m; k++) {
            int count = 0;
            for (int j = Math.max(0, k - SPIKE_WINDOW); j <= Math.min(m - 1, k + SPIKE_WINDOW); j++) {
                if (j != k) {
                    neighbors[count++] = intensity[idx[j]];
                }
            }
            double median = median(neighbors, count);
            for (int j = 0; j < count; j++) {
                neighbors[j] = Math.abs(neighbors[j] - median);
            }
            // 正規分布なら標準偏差に相当する値
            double sigma = 1.4826 * median(neighbors, count);
            double threshold = Math.max(SPIKE_SIGMA * sigma, SPIKE_RANGE_RATIO * range);
            deviation[k] = intensity[idx[k]] - median;
            spike[k] = threshold < Math.abs(deviation[k]);
        }

        boolean dropped = false;
        int k = 0;
        while (k < m) {
            if (!spike[k]) {
                k++;
                continue;
            }
            int end = k;
            while (end + 1 < m && spike[end + 1]) {
                end++;
            }
            if (end - k + 1 <= SPIKE_MAX_RUN && !hasShoulder(deviation, k, end)) {
                for (int j = k; j <= end; j++) {
                    keep[idx[j]] = false;
                }
                dropped = true;
            }
            k = end + 1;
        }
        return dropped;
    }

    // スパイク [start, end] の両隣が, スパイクと同じ向きに持ち上がっているか
    private static boolean hasShoulder(double[] deviation, int start, int end) {
        double peak = deviation[start];
        for (int j = start; j <= end; j++) {
            if (Math.abs(peak) < Math.abs(deviation[j])) {
                peak = deviation[j];
            }
        }
        double sign = Math.signum(peak);
        double limit = SPIKE_SHOULDER_RATIO * Math.abs(peak);
        if (0 < start && limit <= sign * deviation[start - 1]) {
            return true;
        }
        return end + 1 < deviation.length && limit <= sign * deviation[end + 1];
    }

    private static double median(double[] values, int count) {
        double[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        if (count % 2 == 1) {
            return sorted[count / 2];
        }
        return (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0;
    }
}
