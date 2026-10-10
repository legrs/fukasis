// SPDX-License-Identifier: MIT
// 波長校正: 0次光からの距離 t (px) から波長 (nm) への対応と, スペクトルとして出力する t の範囲.
// OpenCV にも OS にも依存しない. spectrum.cpp (makeSpectrum) が使う.
// 同じ計算が CalibrationValidator.java, cli/src/calib.rs, web/js/core.js にあるので, 変えるときは全部直すこと
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <vector>

namespace wlcalib
{
    const int MAX_DEGREE = 3;

    struct Poly
    {
        bool ok = false;
        int degree = 0;
        // 桁落ちを避けるため, t を u = (t - mean) / scale に直した多項式として持つ
        double mean = 0;
        double scale = 1;
        double co[MAX_DEGREE + 1] = {0, 0, 0, 0};

        double at(double t) const
        {
            const double u = (t - mean) / scale;
            double v = 0;
            for (int i = degree; i >= 0; i--)
            {
                v = v * u + co[i];
            }
            return v;
        }
    };

    struct Range
    {
        // 出力する t の範囲 [lo, hi]. lo > hi なら出力するものが無い
        int lo = 0;
        int hi = -1;
        // 波長が折り返すせいで, wlMin / wlMax に届く前に打ち切られた
        bool cutLow = false;
        bool cutHigh = false;
    };

    // "1632,1986,2126" のような 1 行を数値の列にする
    inline std::vector<double> parseLine(const char *line)
    {
        std::vector<double> values;
        const char *p = line;
        while (*p != '\0')
        {
            char *end;
            const double v = strtod(p, &end);
            if (end == p)
            {
                break;
            }
            values.push_back(v);
            p = end;
            while (*p == ' ' || *p == '\t')
            {
                p++;
            }
            if (*p != ',')
            {
                break;
            }
            p++;
        }
        return values;
    }

    // 校正点 (t, c) に多項式を最小二乗で当てはめる.
    // 次数は 3 (位置の異なる校正点が 4 つ未満なら, その数 - 1). 校正点がちょうど 4 つなら,
    // その 4 点を通る 3 次式 (従来の Lagrange 補間と同じもの) になる
    inline Poly fit(const std::vector<double> &t, const std::vector<double> &c)
    {
        Poly f;
        const int n = (int)t.size();
        if (n < 2 || c.size() != t.size())
        {
            return f;
        }
        int distinct = 0;
        double lo = t[0], hi = t[0], sum = 0;
        for (int i = 0; i < n; i++)
        {
            bool dup = false;
            for (int j = 0; j < i; j++)
            {
                if (t[i] == t[j])
                {
                    dup = true;
                }
            }
            if (!dup)
            {
                distinct++;
            }
            lo = std::min(lo, t[i]);
            hi = std::max(hi, t[i]);
            sum += t[i];
        }
        if (distinct < 2 || !(lo < hi))
        {
            return f;
        }
        f.degree = std::min(MAX_DEGREE, distinct - 1);
        f.mean = sum / n;
        f.scale = (hi - lo) / 2;

        // 正規方程式 a * co = b を作る (m は係数の数)
        const int m = f.degree + 1;
        double a[MAX_DEGREE + 1][MAX_DEGREE + 2] = {};
        for (int k = 0; k < n; k++)
        {
            const double u = (t[k] - f.mean) / f.scale;
            double pw[2 * MAX_DEGREE + 1];
            pw[0] = 1;
            for (int i = 1; i <= 2 * f.degree; i++)
            {
                pw[i] = pw[i - 1] * u;
            }
            for (int i = 0; i < m; i++)
            {
                for (int j = 0; j < m; j++)
                {
                    a[i][j] += pw[i + j];
                }
                a[i][m] += c[k] * pw[i];
            }
        }
        // 部分ピボット選択つきの Gauss の消去法
        for (int col = 0; col < m; col++)
        {
            int pivot = col;
            for (int row = col + 1; row < m; row++)
            {
                if (std::fabs(a[pivot][col]) < std::fabs(a[row][col]))
                {
                    pivot = row;
                }
            }
            if (!(std::fabs(a[pivot][col]) > 1e-12))
            {
                return f;
            }
            for (int j = 0; j <= m; j++)
            {
                std::swap(a[col][j], a[pivot][j]);
            }
            for (int row = col + 1; row < m; row++)
            {
                const double factor = a[row][col] / a[col][col];
                for (int j = col; j <= m; j++)
                {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
        for (int i = m - 1; i >= 0; i--)
        {
            double v = a[i][m];
            for (int j = i + 1; j < m; j++)
            {
                v -= a[i][j] * f.co[j];
            }
            f.co[i] = v / a[i][i];
        }
        f.ok = true;
        return f;
    }

    // スペクトルとして出力する t の範囲を決める. t は 1 .. size-2 を動く.
    // 校正点の真ん中から両側へ, 波長が単調に変化している間だけ広げ,
    // そのうち波長が (wlMin, wlMax) に入る部分を返す. こうすると波長が逆行した行は出力されない
    inline Range outputRange(const Poly &f, const std::vector<double> &tRef, int size, double wlMin, double wlMax)
    {
        Range r;
        if (!f.ok || tRef.empty() || size < 3)
        {
            return r;
        }
        const double tA = *std::min_element(tRef.begin(), tRef.end());
        const double tB = *std::max_element(tRef.begin(), tRef.end());
        const double diff = f.at(tB) - f.at(tA);
        if (!(diff > 0) && !(diff < 0))
        {
            return r;
        }
        const double sign = (diff > 0) ? 1 : -1;
        const int tLast = size - 2;
        const int start = std::max(1, std::min(tLast, (int)std::lround((tA + tB) / 2)));

        int monoLo = start;
        while (monoLo > 1 && sign * (f.at(monoLo) - f.at(monoLo - 1)) > 0)
        {
            monoLo--;
        }
        int monoHi = start;
        while (monoHi < tLast && sign * (f.at(monoHi + 1) - f.at(monoHi)) > 0)
        {
            monoHi++;
        }

        int lo = -1, hi = -1;
        for (int t = monoLo; t <= monoHi; t++)
        {
            const double w = f.at(t);
            if (wlMin < w && w < wlMax)
            {
                if (lo < 0)
                {
                    lo = t;
                }
                hi = t;
            }
        }
        if (lo < 0)
        {
            return r;
        }
        r.lo = lo;
        r.hi = hi;
        // 単調な区間の端まで使い切っていて, そこが画像の端でもなければ, 折り返しで打ち切られている
        r.cutLow = (lo == monoLo && monoLo > 1);
        r.cutHigh = (hi == monoHi && monoHi < tLast);
        return r;
    }
}
