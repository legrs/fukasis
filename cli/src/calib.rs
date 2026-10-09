// SPDX-License-Identifier: MIT
//! 波長校正: 0次光からの距離 t (px) から波長 (nm) への対応と, スペクトルとして出力する t の範囲.
//! アプリの cpp/wavelength_calib.h と同じ計算.

pub const MAX_DEGREE: usize = 3;
/// スペクトルとして出力する波長の範囲 (nm)
pub const WAVELENGTH_MIN: f64 = 400.0;
pub const WAVELENGTH_MAX: f64 = 700.0;

/// 桁落ちを避けるため, t を u = (t - mean) / scale に直した多項式として持つ
#[derive(Clone, Debug, Default)]
pub struct Poly {
    pub ok: bool,
    pub degree: usize,
    pub mean: f64,
    pub scale: f64,
    pub co: [f64; MAX_DEGREE + 1],
}

impl Poly {
    pub fn at(&self, t: f64) -> f64 {
        let scale = if self.ok { self.scale } else { 1.0 };
        let u = (t - self.mean) / scale;
        let mut v = 0.0;
        for i in (0..=self.degree).rev() {
            v = v * u + self.co[i];
        }
        v
    }
}

/// 出力する t の範囲 [lo, hi]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Range {
    pub lo: usize,
    pub hi: usize,
    /// 波長が折り返すせいで, 端に届く前に打ち切られた
    pub cut_low: bool,
    pub cut_high: bool,
}

/// 波長校正データ. t は 0次光からの距離 (px), c はそこでの波長 (nm)
#[derive(Clone, Debug, PartialEq)]
pub struct Calibration {
    pub t: Vec<f64>,
    pub c: Vec<f64>,
}

/// "1632,1986,2126" のような 1 行を数値の列にする (数値でないものが出たらそこで止める)
pub fn parse_line(line: &str) -> Vec<f64> {
    let mut values = Vec::new();
    for field in line.trim_end_matches(['\r', '\n']).split(',') {
        match field.trim().parse::<f64>() {
            Ok(v) => values.push(v),
            Err(_) => break,
        }
    }
    values
}

/// アプリの校正データ (1 行目が位置, 2 行目が波長) を読む
pub fn parse_calibration(text: &str) -> Result<Calibration, String> {
    let mut lines = text.lines();
    let t = parse_line(lines.next().ok_or("校正データが空です")?);
    let c = parse_line(
        lines
            .next()
            .ok_or("校正データに 2 行目 (波長) がありません")?,
    );
    if t.len() != c.len() || t.len() < 2 {
        return Err(format!(
            "校正データの列数が合いません (位置 {} 個, 波長 {} 個)",
            t.len(),
            c.len()
        ));
    }
    Ok(Calibration { t, c })
}

/// アプリと同じ形式で書き出す
pub fn format_calibration(cal: &Calibration) -> String {
    let t: Vec<String> = cal
        .t
        .iter()
        .map(|v| format!("{}", v.round() as i64))
        .collect();
    let c: Vec<String> = cal.c.iter().map(|v| format!("{:.6}", v)).collect();
    format!("{}\n{}", t.join(","), c.join(","))
}

/// 校正点に多項式を最小二乗で当てはめる.
/// 次数は 3 (位置の異なる校正点が 4 つ未満なら, その数 - 1). 校正点がちょうど 4 つなら,
/// その 4 点を通る 3 次式になる
pub fn fit(t: &[f64], c: &[f64]) -> Poly {
    let mut f = Poly::default();
    let n = t.len();
    if n < 2 || c.len() != n {
        return f;
    }
    let mut distinct = 0;
    let (mut lo, mut hi, mut sum) = (t[0], t[0], 0.0);
    for i in 0..n {
        if !t[..i].contains(&t[i]) {
            distinct += 1;
        }
        lo = lo.min(t[i]);
        hi = hi.max(t[i]);
        sum += t[i];
    }
    if distinct < 2 || !(lo < hi) {
        return f;
    }
    f.degree = MAX_DEGREE.min(distinct - 1);
    f.mean = sum / n as f64;
    f.scale = (hi - lo) / 2.0;

    // 正規方程式 a * co = b を作る (m は係数の数)
    let m = f.degree + 1;
    let mut a = [[0.0f64; MAX_DEGREE + 2]; MAX_DEGREE + 1];
    for k in 0..n {
        let u = (t[k] - f.mean) / f.scale;
        let mut pw = [1.0f64; 2 * MAX_DEGREE + 1];
        for i in 1..=2 * f.degree {
            pw[i] = pw[i - 1] * u;
        }
        for i in 0..m {
            for j in 0..m {
                a[i][j] += pw[i + j];
            }
            a[i][m] += c[k] * pw[i];
        }
    }
    // 部分ピボット選択つきの Gauss の消去法
    for col in 0..m {
        let mut pivot = col;
        for row in col + 1..m {
            if a[pivot][col].abs() < a[row][col].abs() {
                pivot = row;
            }
        }
        if !(a[pivot][col].abs() > 1e-12) {
            return f;
        }
        a.swap(col, pivot);
        for row in col + 1..m {
            let factor = a[row][col] / a[col][col];
            for j in col..=m {
                a[row][j] -= factor * a[col][j];
            }
        }
    }
    for i in (0..m).rev() {
        let mut v = a[i][m];
        for j in i + 1..m {
            v -= a[i][j] * f.co[j];
        }
        f.co[i] = v / a[i][i];
    }
    f.ok = true;
    f
}

/// スペクトルとして出力する t の範囲を決める. t は 1 ..= size-2 を動く.
/// 校正点の真ん中から両側へ, 波長が単調に変化している間だけ広げ,
/// そのうち波長が (WAVELENGTH_MIN, WAVELENGTH_MAX) に入る部分を返す. 出力するものが無ければ None
pub fn output_range(f: &Poly, t_ref: &[f64], size: usize) -> Option<Range> {
    if !f.ok || t_ref.is_empty() || size < 3 {
        return None;
    }
    let t_a = t_ref.iter().cloned().fold(f64::INFINITY, f64::min);
    let t_b = t_ref.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    let diff = f.at(t_b) - f.at(t_a);
    if !(diff > 0.0) && !(diff < 0.0) {
        return None;
    }
    let sign = if diff > 0.0 { 1.0 } else { -1.0 };
    let t_last = size - 2;
    let start = (((t_a + t_b) / 2.0).round().max(1.0) as usize).min(t_last);
    let at = |t: usize| f.at(t as f64);

    let mut mono_lo = start;
    while mono_lo > 1 && sign * (at(mono_lo) - at(mono_lo - 1)) > 0.0 {
        mono_lo -= 1;
    }
    let mut mono_hi = start;
    while mono_hi < t_last && sign * (at(mono_hi + 1) - at(mono_hi)) > 0.0 {
        mono_hi += 1;
    }

    let mut range: Option<(usize, usize)> = None;
    for t in mono_lo..=mono_hi {
        let w = at(t);
        if WAVELENGTH_MIN < w && w < WAVELENGTH_MAX {
            range = Some((range.map_or(t, |r| r.0), t));
        }
    }
    let (lo, hi) = range?;
    // 単調な区間の端まで使い切っていて, そこが画像の端でもなければ, 折り返しで打ち切られている
    Some(Range {
        lo,
        hi,
        cut_low: lo == mono_lo && mono_lo > 1,
        cut_high: hi == mono_hi && mono_hi < t_last,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // README の calibration 画面の例に相当する値
    const T_REF: [f64; 4] = [1632.0, 1986.0, 2126.0, 2210.0];
    const C_REF: [f64; 4] = [435.8, 546.1, 588.0, 611.6];
    const SIZE: usize = 3590;

    fn lagrange(t_ref: &[f64], c_ref: &[f64], t: f64) -> f64 {
        let mut w = 0.0;
        for j in 0..4 {
            let (mut nume, mut deno) = (1.0, 1.0);
            for k in 0..4 {
                if k != j {
                    nume *= t - t_ref[k];
                    deno *= t_ref[j] - t_ref[k];
                }
            }
            w += c_ref[j] * nume / deno;
        }
        w
    }

    #[test]
    fn four_points_give_same_curve_as_lagrange() {
        let f = fit(&T_REF, &C_REF);
        assert!(f.ok);
        assert_eq!(f.degree, 3);
        for t in 1..SIZE {
            assert!((f.at(t as f64) - lagrange(&T_REF, &C_REF, t as f64)).abs() < 1e-6);
        }
    }

    #[test]
    fn typical_calibration_covers_whole_band() {
        let f = fit(&T_REF, &C_REF);
        let r = output_range(&f, &T_REF, SIZE).unwrap();
        assert_eq!(
            r,
            Range {
                lo: 1511,
                hi: 2639,
                cut_low: false,
                cut_high: false
            }
        );
        // 出力される範囲では波長が単調
        for t in r.lo + 1..=r.hi {
            assert!(f.at(t as f64 - 1.0) < f.at(t as f64));
        }
    }

    #[test]
    fn swapped_wavelengths_are_truncated() {
        let t = [1900.0, 2100.0, 2300.0, 2500.0];
        let r = output_range(&fit(&t, &[430.0, 550.0, 490.0, 610.0]), &t, SIZE).unwrap();
        assert!(r.cut_low || r.cut_high);
    }

    #[test]
    fn same_position_falls_back_to_quadratic() {
        let t = [1632.0, 1986.0, 1986.0, 2210.0];
        let f = fit(&t, &[435.8, 546.1, 546.1, 611.6]);
        assert!(f.ok);
        assert_eq!(f.degree, 2);
        assert!((f.at(1986.0) - 546.1).abs() < 1e-6);
    }

    #[test]
    fn no_fit_and_no_output() {
        assert!(!fit(&[2000.0; 4], &[500.0; 4]).ok);
        let t = [1900.0, 2100.0, 2300.0, 2500.0];
        assert_eq!(
            output_range(&fit(&t, &[1430.0, 1490.0, 1550.0, 1610.0]), &t, SIZE),
            None
        );
    }

    #[test]
    fn six_points_are_fitted_by_least_squares() {
        let t = [1525.0, 1632.0, 1986.0, 2126.0, 2210.0, 2281.0];
        let c = [404.7, 435.8, 546.1, 588.0, 611.6, 631.1];
        let f = fit(&t, &c);
        assert_eq!(f.degree, 3);
        for i in 0..6 {
            assert!((f.at(t[i]) - c[i]).abs() < 0.5);
        }
        let r = output_range(&f, &t, SIZE).unwrap();
        assert_eq!((r.lo, r.hi), (1509, 2615));
    }

    #[test]
    fn calibration_text_roundtrip() {
        let cal = parse_calibration("51,189,241,271\n435.800000,546.100000,588.000000,611.600000")
            .unwrap();
        assert_eq!(cal.t, vec![51.0, 189.0, 241.0, 271.0]);
        assert_eq!(
            format_calibration(&cal),
            "51,189,241,271\n435.800000,546.100000,588.000000,611.600000"
        );
        assert!(parse_calibration("1,2,3\n1,2").is_err());
        assert!(parse_calibration("").is_err());
    }
}
