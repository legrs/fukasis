// SPDX-License-Identifier: MIT
//! スペクトルの出力. アプリの csv 画面 (共通コア core/ の makeSpectrum) と同じ計算.

use crate::calib::{self, Calibration, Poly, Range, WAVELENGTH_MAX, WAVELENGTH_MIN};
use crate::image::{band_rows_with, Image, BAND_WIDTH};
use crate::util::{fmt_g, parse_value};

/// カラーフィルタ配列. 左上 2x2 を読み順に並べた名前
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Cfa {
    Rggb,
    Grbg,
    Gbrg,
    Bggr,
    Mono,
}

impl Cfa {
    /// "RGGB" などを解釈する. 大文字小文字は区別しない
    pub fn parse(name: &str) -> Option<Cfa> {
        match name.to_ascii_uppercase().as_str() {
            "RGGB" => Some(Cfa::Rggb),
            "GRBG" => Some(Cfa::Grbg),
            "GBRG" => Some(Cfa::Gbrg),
            "BGGR" => Some(Cfa::Bggr),
            "MONO" => Some(Cfa::Mono),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Cfa::Rggb => "RGGB",
            Cfa::Grbg => "GRBG",
            Cfa::Gbrg => "GBRG",
            Cfa::Bggr => "BGGR",
            Cfa::Mono => "MONO",
        }
    }

    /// (x, y) の画素のチャンネル. 0: b, 1: g, 2: r (Mono は常に 1)
    pub fn channel(self, x: usize, y: usize) -> usize {
        if self == Cfa::Mono {
            return 1;
        }
        match self.name().as_bytes()[(y & 1) * 2 + (x & 1)] {
            b'B' => 0,
            b'R' => 2,
            _ => 1,
        }
    }
}

/// metadata の 1 行目に ", cfa XXXX" があれば, 撮影した端末のカラーフィルタ配列として返す
pub fn cfa_from_metadata(header: &str) -> Option<Cfa> {
    const KEY: &str = ", cfa ";
    let line = header.lines().next().unwrap_or("");
    let rest = &line[line.rfind(KEY)? + KEY.len()..];
    let end = rest
        .find(|c: char| !c.is_ascii_alphabetic())
        .unwrap_or(rest.len());
    Cfa::parse(&rest[..end])
}

/// 機種ごとに変わりうる切り出しのパラメータ (既定値はアプリと同じ)
#[derive(Clone, Copy, Debug)]
pub struct Options {
    /// 縦に積算する帯の幅 (px) と中心 (画像の高さに対する割合)
    pub band_width: usize,
    pub band_center: f64,
    pub cfa: Cfa,
    /// true なら感度校正をしない (感度データは使わない)
    pub no_sensitivity: bool,
}

impl Default for Options {
    fn default() -> Options {
        Options {
            band_width: BAND_WIDTH,
            band_center: 0.5,
            cfa: Cfa::Gbrg,
            no_sensitivity: false,
        }
    }
}

/// 感度データ. 波長の小さい順に並べた, 波長ごとの b, g, r の感度とその合計
#[derive(Clone, Debug, Default)]
pub struct Sensitivity {
    pub wavelength: Vec<f64>,
    pub channel: [Vec<f64>; 4],
}

impl Sensitivity {
    /// 波長 wl での感度の合計 (線形補間. 表の範囲外は端の値)
    pub fn total_at(&self, wl: f64) -> f64 {
        let (w, s) = (&self.wavelength, &self.channel[3]);
        let hi = w.partition_point(|&v| v <= wl);
        if hi == 0 {
            s[0]
        } else if hi == w.len() {
            s[w.len() - 1]
        } else {
            let lo = hi - 1;
            s[lo] + (wl - w[lo]) * (s[hi] - s[lo]) / (w[hi] - w[lo])
        }
    }
}

/// 感度データの csv を読む. 最初の 2 行はヘッダー, 以降は「波長, b, g, r」(5 列目以降は使わない)
pub fn parse_sensitivity(text: &str) -> Result<Sensitivity, String> {
    let mut rows: Vec<[f64; 4]> = Vec::new();
    for (number, line) in text.lines().enumerate().skip(2) {
        if line.trim().is_empty() {
            continue;
        }
        let mut values = Vec::new();
        for field in line.split(',') {
            match field.trim().parse::<f64>() {
                Ok(v) if v.is_finite() => values.push(v),
                _ => {
                    return Err(format!(
                        "感度データの {} 行目が数値ではありません",
                        number + 1
                    ))
                }
            }
        }
        if values.len() < 4 {
            return Err(format!("感度データの {} 行目の列が足りません", number + 1));
        }
        rows.push([values[0], values[1], values[2], values[3]]);
    }
    if rows.len() < 2 {
        return Err("感度データが 2 行未満です".to_string());
    }
    rows.sort_by(|a, b| a[0].total_cmp(&b[0]));
    let mut s = Sensitivity::default();
    for row in rows {
        s.wavelength.push(row[0]);
        s.channel[0].push(row[1]);
        s.channel[1].push(row[2]);
        s.channel[2].push(row[3]);
        s.channel[3].push(row[1] + row[2] + row[3]);
    }
    Ok(s)
}

#[derive(Clone, Debug)]
pub struct Spectrum {
    pub wavelength: Vec<f64>,
    /// 最大値が 1 になるように正規化した相対強度
    pub intensity: Vec<f64>,
    pub fit: Poly,
    /// 出力した 0次光からの距離の範囲
    pub range: Range,
}

/// 画像からスペクトルを取り出す (既定のパラメータ). fol は 0次光の位置 (画像の x 座標, px)
pub fn extract(
    img: &Image,
    cal: &Calibration,
    sensitivity: &Sensitivity,
    fol: usize,
) -> Result<Spectrum, String> {
    extract_with(img, cal, sensitivity, fol, &Options::default())
}

/// 画像からスペクトルを取り出す
pub fn extract_with(
    img: &Image,
    cal: &Calibration,
    sensitivity: &Sensitivity,
    fol: usize,
    options: &Options,
) -> Result<Spectrum, String> {
    if fol < 1 || fol >= img.width {
        return Err(format!(
            "0次光の位置 {} が画像の幅 {} の外です",
            fol, img.width
        ));
    }
    if cal.t.len() != cal.c.len() || cal.t.len() < 2 {
        return Err("校正データの距離と波長の数が合いません".to_string());
    }
    for j in 0..cal.t.len() {
        for k in j + 1..cal.t.len() {
            if cal.t[j] == cal.t[k] {
                return Err("校正データの画素位置が重複しています".to_string());
            }
        }
    }
    // t -> 波長 の対応. 4 点ならその 4 点を通る 3 次式, 5 点以上なら 3 次の最小二乗
    let fit = calib::fit(&cal.t, &cal.c);
    if !fit.ok {
        return Err("校正データから波長を求められません".to_string());
    }
    if !options.no_sensitivity && sensitivity.wavelength.len() < 2 {
        return Err("感度データが足りません".to_string());
    }

    let (y1, y2) = band_rows_with(img.height, options.band_width, options.band_center);
    const SIGMA_THRES: f64 = 3.0;

    // 縦方向の積算. 帯の中で, チャンネルごとに 3 シグマから外れた画素を除いて平均する
    let mut pure: [Vec<f64>; 3] = [
        Vec::with_capacity(fol),
        Vec::with_capacity(fol),
        Vec::with_capacity(fol),
    ];
    for x in (1..=fol).rev() {
        let mut count = [0i64; 3];
        let mut mean = [0.0f64; 3];
        let mut sigma = [0.0f64; 3];
        let mut sum = [0.0f64; 3];
        for y in y1..y2 {
            let ch = options.cfa.channel(x, y);
            mean[ch] += img.at(y, x) as f64;
            count[ch] += 1;
        }
        for ch in 0..3 {
            if count[ch] > 0 {
                mean[ch] /= count[ch] as f64;
            }
        }
        for y in y1..y2 {
            let ch = options.cfa.channel(x, y);
            let d = img.at(y, x) as f64 - mean[ch];
            sigma[ch] += d * d;
        }
        for ch in 0..3 {
            if count[ch] > 0 {
                sigma[ch] = (sigma[ch] / count[ch] as f64).sqrt();
            }
        }
        for y in y1..y2 {
            let ch = options.cfa.channel(x, y);
            let val = img.at(y, x) as f64;
            if SIGMA_THRES * sigma[ch] < (val - mean[ch]).abs() {
                count[ch] -= 1;
            } else {
                sum[ch] += if val < 0.0 { 0.0 } else { val };
            }
        }
        for ch in 0..3 {
            pure[ch].push(sum[ch] / count[ch].max(1) as f64);
        }
    }
    let size = pure[0].len();

    // 出力する範囲. 画素の固定範囲ではなく波長で決める. 波長が逆行する部分は含めない
    let no_output = || {
        format!(
            "{}-{}nm に入る点がありません (校正データと 0次光の位置を確認してください)",
            WAVELENGTH_MIN, WAVELENGTH_MAX
        )
    };
    let range = calib::output_range(&fit, &cal.t, size).ok_or_else(no_output)?;

    // Bayer 配列で b と r は 1 列おきにしか無いので, 欠けている列を両隣から補う
    let mut min = [f64::MAX; 3];
    for i in 1..size.saturating_sub(1) {
        for c in [0, 2] {
            if pure[c][i] == 0.0 {
                pure[c][i] = (pure[c][i - 1] + pure[c][i + 1]) / 2.0;
            }
        }
        if range.lo <= i && i <= range.hi {
            for c in 0..3 {
                if pure[c][i] < min[c] {
                    min[c] = pure[c][i];
                }
            }
        }
    }

    // 波長と感度の校正
    let (mut wavelength, mut intensity) = (Vec::new(), Vec::new());
    let mut max = 0.0f64;
    for i in range.lo..=range.hi {
        let t_p = fit.at(i as f64);
        if !(WAVELENGTH_MIN < t_p && t_p < WAVELENGTH_MAX) {
            continue;
        }
        let s = if options.no_sensitivity {
            1.0
        } else {
            sensitivity.total_at(t_p)
        };
        if !(s > 0.0) {
            continue; // 感度 0 の波長は補正できない
        }
        let mut bgr = 0.0;
        for c in 0..3 {
            let v = pure[c][i] - min[c];
            if v > 0.0 {
                bgr += v;
            }
        }
        bgr /= s;
        if max < bgr {
            max = bgr;
        }
        wavelength.push(t_p);
        intensity.push(bgr);
    }
    if wavelength.is_empty() {
        return Err(no_output());
    }
    if !(max > 0.0) {
        return Err("スペクトルの強度が 0 です".to_string());
    }
    for v in intensity.iter_mut() {
        *v /= max;
    }
    Ok(Spectrum {
        wavelength,
        intensity,
        fit,
        range,
    })
}

pub const LABEL_LINE: &str = "wavelength/nm,relative intensity(0.0 -- 1.0)";
/// 感度校正をしなかったスペクトルの 1 行目 (観測の情報) に付ける印
pub const NO_SENSITIVITY_MARK: &str = ", sensitivity none";

/// アプリと同じ形式の csv にする. 1 行目は観測の情報 (metadata.csv の 1 行目), 2 行目はラベル
pub fn to_csv(spectrum: &Spectrum, header: &str) -> String {
    let mut out = String::new();
    out.push_str(header.lines().next().unwrap_or(""));
    out.push('\n');
    out.push_str(LABEL_LINE);
    out.push('\n');
    for (w, v) in spectrum.wavelength.iter().zip(&spectrum.intensity) {
        out.push_str(&fmt_g(*w));
        out.push(',');
        out.push_str(&fmt_g(*v));
        out.push('\n');
    }
    out
}

/// スペクトルの csv を読む. 数値でない行 (ヘッダー) は読み飛ばす. (波長, 強度) をファイルの順で返す
pub fn parse_csv(text: &str) -> (Vec<f64>, Vec<f64>) {
    let (mut x, mut y) = (Vec::new(), Vec::new());
    for line in text.lines() {
        let mut fields = line.split(',');
        if let (Some(a), Some(b)) = (fields.next(), fields.next()) {
            if let (Some(a), Some(b)) = (parse_value(a), parse_value(b)) {
                x.push(a);
                y.push(b);
            }
        }
    }
    (x, y)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sensitivity_skips_two_header_lines() {
        let s = parse_sensitivity("title\nwavelength,b,g,r\n400,1,2,3\n\n410,2,3,4.5\n").unwrap();
        assert_eq!(s.wavelength, vec![400.0, 410.0]);
        assert_eq!(s.channel[3], vec![6.0, 9.5]);
        assert!(parse_sensitivity("a\nb\n400,1,2\n").is_err());
        assert!(parse_sensitivity("a\nb\n").is_err());
    }

    #[test]
    fn sensitivity_is_sorted_and_interpolated() {
        // 行の順番がばらばらでも波長順に並べ, 間は線形補間, 範囲の外は端の値を使う
        let s = parse_sensitivity("a\nb\n500,2,2,2\n400,1,1,1,9,9\n600,4,4,4\n").unwrap();
        assert_eq!(s.wavelength, vec![400.0, 500.0, 600.0]);
        assert_eq!(s.total_at(450.0), 4.5);
        assert_eq!(s.total_at(500.0), 6.0);
        assert_eq!(s.total_at(550.0), 9.0);
        assert_eq!(s.total_at(300.0), 3.0);
        assert_eq!(s.total_at(900.0), 12.0);
        assert!(parse_sensitivity("a\nb\n400,1,1,1\n410,x,1,1\n").is_err());
        assert!(parse_sensitivity("a\nb\n400,1,1\n410,1,1\n").is_err());
    }

    #[test]
    fn color_filter_arrangements() {
        assert_eq!(Cfa::parse("gbrg"), Some(Cfa::Gbrg));
        assert_eq!(Cfa::parse("xyz"), None);
        // GBRG: 左上から G B / R G
        let at = |cfa: Cfa| {
            [
                cfa.channel(0, 0),
                cfa.channel(1, 0),
                cfa.channel(0, 1),
                cfa.channel(1, 1),
            ]
        };
        assert_eq!(at(Cfa::Gbrg), [1, 0, 2, 1]);
        assert_eq!(at(Cfa::Rggb), [2, 1, 1, 0]);
        assert_eq!(at(Cfa::Grbg), [1, 2, 0, 1]);
        assert_eq!(at(Cfa::Bggr), [0, 1, 1, 2]);
        assert_eq!(at(Cfa::Mono), [1, 1, 1, 1]);
        assert_eq!(
            cfa_from_metadata("seq, 2026-10-08T00:00:00Z,  ISO 800 , cfa RGGB, device X"),
            Some(Cfa::Rggb)
        );
        assert_eq!(
            cfa_from_metadata("seq, 2026-10-08T00:00:00Z,  ISO 800"),
            None
        );
    }

    #[test]
    fn extraction_reports_bad_input() {
        let img = Image::new(64, 8);
        let s = parse_sensitivity("a\nb\n400,1,1,1\n700,1,1,1\n").unwrap();
        let cal = |t: &[f64], c: &[f64]| Calibration {
            t: t.to_vec(),
            c: c.to_vec(),
        };
        let good = cal(&[10.0, 20.0, 30.0, 40.0], &[430.0, 490.0, 550.0, 610.0]);
        // 0次光の位置が画像の外
        assert!(extract(&img, &good, &s, 0).is_err());
        assert!(extract(&img, &good, &s, 64).is_err());
        // 同じ位置に 2 本
        let dup = cal(&[10.0, 10.0, 30.0, 40.0], &[430.0, 490.0, 550.0, 610.0]);
        assert!(extract(&img, &dup, &s, 60).unwrap_err().contains("重複"));
        // 真っ黒な画像は強度が 0
        assert!(extract(&img, &good, &s, 60).unwrap_err().contains("強度"));
        // 400 - 700 nm に入る画素が無い
        let far = cal(&[10.0, 20.0, 30.0, 40.0], &[1430.0, 1490.0, 1550.0, 1610.0]);
        assert!(extract(&img, &far, &s, 60)
            .unwrap_err()
            .contains("入る点がありません"));
    }

    #[test]
    fn csv_parsing_skips_headers() {
        let (x, y) = parse_csv("seq, 2026-10-08T00:00:00Z, ISO 800\nwavelength/nm,relative intensity(0.0 -- 1.0)\n400.5,0.25\n401,nan\n");
        assert_eq!(x, vec![400.5, 401.0]);
        assert_eq!(y[0], 0.25);
        assert!(y[1].is_nan());
    }
}
