// SPDX-License-Identifier: MIT
//! スペクトルの出力. アプリの csv 画面 (native-lib.cpp の makecsv) と同じ計算.

use crate::calib::{self, Calibration, Poly, Range, WAVELENGTH_MAX, WAVELENGTH_MIN};
use crate::image::{band_rows, Image};
use crate::util::{fmt_g, parse_value};

/// 感度データ. 波長ごとの b, g, r の感度と, その合計
#[derive(Clone, Debug, Default)]
pub struct Sensitivity {
    pub wavelength: Vec<f64>,
    pub channel: [Vec<f64>; 4],
}

/// 感度データの csv を読む. 最初の 2 行はヘッダー, 以降は「波長, b, g, r」
pub fn parse_sensitivity(text: &str) -> Result<Sensitivity, String> {
    let mut s = Sensitivity::default();
    for (number, line) in text.lines().enumerate().skip(2) {
        if line.trim().is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split(',').collect();
        if fields.len() < 4 {
            return Err(format!("感度データの {} 行目の列が足りません", number + 1));
        }
        let mut row = [0.0f64; 4];
        for (i, field) in fields.iter().take(4).enumerate() {
            // アプリは単精度 (stof) で読んでいるので合わせる
            let v: f32 = field
                .trim()
                .parse()
                .map_err(|_| format!("感度データの {} 行目が数値ではありません", number + 1))?;
            row[i] = v as f64;
        }
        s.wavelength.push(row[0]);
        s.channel[0].push(row[1]);
        s.channel[1].push(row[2]);
        s.channel[2].push(row[3]);
        s.channel[3].push(row[1] + row[2] + row[3]);
    }
    if s.wavelength.len() < 2 {
        return Err("感度データが 2 行未満です".to_string());
    }
    Ok(s)
}

#[derive(Clone, Debug)]
pub struct Spectrum {
    pub wavelength: Vec<f64>,
    /// 最大値が 1 になるように正規化した相対強度
    pub intensity: Vec<f64>,
    pub fit: Poly,
    /// 出力した 0次光からの距離の範囲. 出力が無ければ None
    pub range: Option<Range>,
}

/// (x, y) の画素のチャンネル. 0: b, 1: g, 2: r
fn channel_of(x: usize, y: usize) -> usize {
    if x % 2 != 0 && y % 2 == 0 {
        0
    } else if x % 2 == 0 && y % 2 != 0 {
        2
    } else {
        1
    }
}

/// 画像からスペクトルを取り出す. fol は 0次光の位置 (画像の x 座標, px)
pub fn extract(
    img: &Image,
    cal: &Calibration,
    sensitivity: &Sensitivity,
    fol: usize,
) -> Result<Spectrum, String> {
    if fol < 1 || fol >= img.width {
        return Err(format!(
            "0次光の位置 {} が画像の幅 {} の外です",
            fol, img.width
        ));
    }
    let (y1, y2) = band_rows(img.height);
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
        let mut pixel = [0.0f64; 3];
        for y in y1..y2 {
            let ch = channel_of(x, y);
            mean[ch] += img.at(y, x) as f64;
            count[ch] += 1;
        }
        for ch in 0..3 {
            mean[ch] /= count[ch] as f64;
        }
        for y in y1..y2 {
            let ch = channel_of(x, y);
            let d = img.at(y, x) as f64 - mean[ch];
            sigma[ch] += d * d;
        }
        for ch in 0..3 {
            sigma[ch] = (sigma[ch] / count[ch] as f64).sqrt();
        }
        for y in y1..y2 {
            let ch = channel_of(x, y);
            let val = img.at(y, x) as f64;
            if SIGMA_THRES * sigma[ch] < (val - mean[ch]).abs() {
                count[ch] -= 1;
            } else {
                pixel[ch] += if val < 0.0 { 0.0 } else { val };
            }
        }
        for ch in 0..3 {
            pure[ch].push(pixel[ch] / count[ch].max(1) as f64);
        }
    }
    let size = pure[0].len();

    let fit = calib::fit(&cal.t, &cal.c);
    let range = calib::output_range(&fit, &cal.t, size);
    let in_range = |i: usize| range.is_some_and(|r| r.lo <= i && i <= r.hi);

    // Bayer 配列で b と r は 1 列おきにしか無いので, 欠けている列を両隣から補う
    let mut min = [65536.0f64; 3];
    let mut max = 0.0f64;
    for i in 1..size.saturating_sub(1) {
        let mut bgr = 0.0;
        if pure[0][i] == 0.0 {
            pure[0][i] = pure[0][i - 1] + (pure[0][i + 1] - pure[0][i - 1]) / 2.0;
            bgr += pure[0][i];
        }
        bgr += pure[1][i];
        if pure[2][i] == 0.0 {
            pure[2][i] = pure[2][i - 1] + (pure[2][i + 1] - pure[2][i - 1]) / 2.0;
            bgr += pure[2][i];
        }
        if in_range(i) {
            if max < bgr {
                max = bgr;
            }
            for c in 0..3 {
                if pure[c][i] < min[c] {
                    min[c] = pure[c][i];
                }
            }
        }
    }

    // 波長と感度の校正
    let n = sensitivity.wavelength.len();
    let (mut wavelength, mut intensity) = (Vec::new(), Vec::new());
    for i in 0..size {
        let t_p = fit.at(i as f64);
        let mut total_sensitivity = 1.0;
        if n >= 2 {
            let wl = &sensitivity.wavelength;
            let mut vi = 1;
            while vi < n - 1 && wl[vi] < t_p {
                vi += 1;
            }
            let s = &sensitivity.channel[3];
            total_sensitivity =
                s[vi - 1] + (t_p - wl[vi]) * (s[vi] - s[vi - 1]) / (wl[vi] - wl[vi - 1]);
        }
        if in_range(i) && WAVELENGTH_MIN < t_p && t_p < WAVELENGTH_MAX {
            let mut bgr = 0.0;
            for c in 0..3 {
                pure[c][i] -= min[c];
                if pure[c][i] <= 0.0 {
                    pure[c][i] = 0.0;
                }
                bgr += pure[c][i];
            }
            bgr /= total_sensitivity;
            if max < bgr {
                max = bgr;
            }
            wavelength.push(t_p);
            intensity.push(bgr);
        }
    }
    if max <= 0.0 {
        max = 1.0;
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
    fn csv_parsing_skips_headers() {
        let (x, y) = parse_csv("seq, 2026-10-08T00:00:00Z, ISO 800\nwavelength/nm,relative intensity(0.0 -- 1.0)\n400.5,0.25\n401,nan\n");
        assert_eq!(x, vec![400.5, 401.0]);
        assert_eq!(y[0], 0.25);
        assert!(y[1].is_nan());
    }
}
