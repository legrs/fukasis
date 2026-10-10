// SPDX-License-Identifier: MIT
package com.example.ssa;

// 波長校正データから, csv 画面でどの波長の範囲が出力されるかを確かめる.
// 計算は core/Sources/FukasisCoreC/wavelength_calib.h (makecsv が使う) と同じ. 変えるときは両方直すこと
public class CalibrationValidator {

    public static final int OK = 0;
    // 波長が途中で折り返すため, 出力が 400 - 700 nm の途中で打ち切られる
    public static final int TRUNCATED = 1;
    // 出力される画素が 1 つも無い
    public static final int NO_OUTPUT = 2;

    static final int MAX_DEGREE = 3;
    // makecsv が出力する波長の範囲 (nm)
    static final double WAVELENGTH_MIN = 400;
    static final double WAVELENGTH_MAX = 700;

    public static class Result {
        public final int status;
        // 出力される波長の範囲 (nm). NO_OUTPUT のときは 0
        public final double wavelengthMin;
        public final double wavelengthMax;

        Result(int status, double wavelengthMin, double wavelengthMax) {
            this.status = status;
            this.wavelengthMin = wavelengthMin;
            this.wavelengthMax = wavelengthMax;
        }
    }

    // 校正点への多項式の当てはめ. 桁落ちを避けるため, t を u = (t - mean) / scale に直した多項式として持つ
    public static class Poly {
        final int degree;
        final double mean;
        final double scale;
        final double[] co;

        Poly(int degree, double mean, double scale, double[] co) {
            this.degree = degree;
            this.mean = mean;
            this.scale = scale;
            this.co = co;
        }

        // t は 0次光からの距離 (px)
        public double at(double t) {
            double u = (t - mean) / scale;
            double v = 0;
            for (int i = degree; i >= 0; i--) {
                v = v * u + co[i];
            }
            return v;
        }
    }

    // 校正点 (t, c) に多項式を最小二乗で当てはめる. 当てはめられなければ null.
    // 次数は 3 (位置の異なる校正点が 4 つ未満なら, その数 - 1). 校正点がちょうど 4 つなら,
    // その 4 点を通る 3 次式 (従来の Lagrange 補間と同じもの) になる
    public static Poly fit(double[] t, double[] c) {
        int n = t.length;
        if (n < 2 || c.length != n) {
            return null;
        }
        int distinct = 0;
        double lo = t[0];
        double hi = t[0];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (t[i] == t[j]) {
                    dup = true;
                }
            }
            if (!dup) {
                distinct++;
            }
            lo = Math.min(lo, t[i]);
            hi = Math.max(hi, t[i]);
            sum += t[i];
        }
        if (distinct < 2 || !(lo < hi)) {
            return null;
        }
        int degree = Math.min(MAX_DEGREE, distinct - 1);
        double mean = sum / n;
        double scale = (hi - lo) / 2;

        // 正規方程式 a * co = b を作る (m は係数の数)
        int m = degree + 1;
        double[][] a = new double[m][m + 1];
        double[] pw = new double[2 * degree + 1];
        for (int k = 0; k < n; k++) {
            double u = (t[k] - mean) / scale;
            pw[0] = 1;
            for (int i = 1; i <= 2 * degree; i++) {
                pw[i] = pw[i - 1] * u;
            }
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    a[i][j] += pw[i + j];
                }
                a[i][m] += c[k] * pw[i];
            }
        }
        // 部分ピボット選択つきの Gauss の消去法
        for (int col = 0; col < m; col++) {
            int pivot = col;
            for (int row = col + 1; row < m; row++) {
                if (Math.abs(a[pivot][col]) < Math.abs(a[row][col])) {
                    pivot = row;
                }
            }
            if (!(Math.abs(a[pivot][col]) > 1e-12)) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int row = col + 1; row < m; row++) {
                double factor = a[row][col] / a[col][col];
                for (int j = col; j <= m; j++) {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
        double[] co = new double[m];
        for (int i = m - 1; i >= 0; i--) {
            double v = a[i][m];
            for (int j = i + 1; j < m; j++) {
                v -= a[i][j] * co[j];
            }
            co[i] = v / a[i][i];
        }
        return new Poly(degree, mean, scale, co);
    }

    // スペクトルとして出力される t の範囲 {lo, hi, cutLow, cutHigh} を返す. t は 1 .. size-2 を動く.
    // 校正点の真ん中から両側へ, 波長が単調に変化している間だけ広げ,
    // そのうち波長が (WAVELENGTH_MIN, WAVELENGTH_MAX) に入る部分. 出力が無ければ null.
    // cutLow / cutHigh は, 波長が折り返すせいで端に届く前に打ち切られたとき 1
    static int[] outputRange(Poly f, double[] tRef, int size) {
        if (f == null || tRef.length == 0 || size < 3) {
            return null;
        }
        double tA = tRef[0];
        double tB = tRef[0];
        for (double v : tRef) {
            tA = Math.min(tA, v);
            tB = Math.max(tB, v);
        }
        double diff = f.at(tB) - f.at(tA);
        if (!(diff > 0) && !(diff < 0)) {
            return null;
        }
        double sign = (diff > 0) ? 1 : -1;
        int tLast = size - 2;
        int start = Math.max(1, Math.min(tLast, (int) Math.round((tA + tB) / 2)));

        int monoLo = start;
        while (monoLo > 1 && sign * (f.at(monoLo) - f.at(monoLo - 1)) > 0) {
            monoLo--;
        }
        int monoHi = start;
        while (monoHi < tLast && sign * (f.at(monoHi + 1) - f.at(monoHi)) > 0) {
            monoHi++;
        }

        int lo = -1;
        int hi = -1;
        for (int t = monoLo; t <= monoHi; t++) {
            double w = f.at(t);
            if (WAVELENGTH_MIN < w && w < WAVELENGTH_MAX) {
                if (lo < 0) {
                    lo = t;
                }
                hi = t;
            }
        }
        if (lo < 0) {
            return null;
        }
        // 単調な区間の端まで使い切っていて, そこが画像の端でもなければ, 折り返しで打ち切られている
        int cutLow = (lo == monoLo && monoLo > 1) ? 1 : 0;
        int cutHigh = (hi == monoHi && monoHi < tLast) ? 1 : 0;
        return new int[] { lo, hi, cutLow, cutHigh };
    }

    // tRef: 0次光からの距離 (px), cRef: 波長 (nm), size: 0次光の位置 (0次光から画像の端までの画素数)
    public static Result validate(double[] tRef, double[] cRef, int size) {
        Poly f = fit(tRef, cRef);
        int[] range = outputRange(f, tRef, size);
        if (range == null) {
            return new Result(NO_OUTPUT, 0, 0);
        }
        double w1 = f.at(range[0]);
        double w2 = f.at(range[1]);
        int status = (range[2] != 0 || range[3] != 0) ? TRUNCATED : OK;
        return new Result(status, Math.min(w1, w2), Math.max(w1, w2));
    }
}
