// SPDX-License-Identifier: MIT

/// 1 チャンネルの 32bit float 画像 (アプリの stacked.tif / darked.tif と同じ形)
#[derive(Clone, Debug, PartialEq)]
pub struct Image {
    pub width: usize,
    pub height: usize,
    pub data: Vec<f32>,
}

impl Image {
    pub fn new(width: usize, height: usize) -> Image {
        Image {
            width,
            height,
            data: vec![0.0; width * height],
        }
    }

    pub fn at(&self, y: usize, x: usize) -> f32 {
        self.data[y * self.width + x]
    }
}

/// ダーク減算 (アプリの dark 画面と同じ, 画素ごとの light - dark)
pub fn subtract(light: &Image, dark: &Image) -> Result<Image, String> {
    if light.width != dark.width || light.height != dark.height {
        return Err(format!(
            "画像の大きさが違います: {}x{} と {}x{}",
            light.width, light.height, dark.width, dark.height
        ));
    }
    let data = light
        .data
        .iter()
        .zip(&dark.data)
        .map(|(l, d)| l - d)
        .collect();
    Ok(Image {
        width: light.width,
        height: light.height,
        data,
    })
}

/// スペクトルを読む帯 (画像中央の幅 80 px) の行の範囲 [y1, y2)
pub fn band_rows(height: usize) -> (usize, usize) {
    const BAND: usize = 80;
    let y1 = (height / 2).saturating_sub(BAND / 2);
    let y2 = (height / 2 + BAND / 2).min(height);
    (y1, y2)
}

/// 帯の中を縦に平均した, 横方向のプロファイル
pub fn band_profile(img: &Image) -> Vec<f64> {
    let (y1, y2) = band_rows(img.height);
    let rows = (y2 - y1).max(1) as f64;
    (0..img.width)
        .map(|x| (y1..y2).map(|y| img.at(y, x) as f64).sum::<f64>() / rows)
        .collect()
}

/// プロファイルを 1:2:1 でならす. Bayer 配列のせいで 1 列おきに値が上下するのを消す (山の位置は動かない)
pub fn smooth_profile(profile: &[f64]) -> Vec<f64> {
    let n = profile.len();
    (0..n)
        .map(|x| {
            (profile[x.saturating_sub(1)] + 2.0 * profile[x] + profile[(x + 1).min(n - 1)]) / 4.0
        })
        .collect()
}

/// 0次光の位置の推定. 帯のプロファイルが最大になる山の真ん中 (px)
pub fn guess_zeroth_order(profile: &[f64]) -> Option<usize> {
    let (mut best, mut max) = (0usize, f64::NEG_INFINITY);
    for (x, &v) in profile.iter().enumerate() {
        if v.is_finite() && v > max {
            max = v;
            best = x;
        }
    }
    if !max.is_finite() {
        return None;
    }
    let min = profile
        .iter()
        .cloned()
        .filter(|v| v.is_finite())
        .fold(f64::INFINITY, f64::min);
    // 最大値の近くで, 最大値の 9 割以上の高さが続いている範囲の重心
    let threshold = min + (max - min) * 0.9;
    let mut lo = best;
    while lo > 0 && profile[lo - 1] >= threshold {
        lo -= 1;
    }
    let mut hi = best;
    while hi + 1 < profile.len() && profile[hi + 1] >= threshold {
        hi += 1;
    }
    Some((lo + hi) / 2)
}

/// 輝線の候補. プロファイルの極大のうち, 周りより十分高いものを高い順に返す (位置, 高さ)
pub fn find_peaks(profile: &[f64], from: usize, to: usize, max_count: usize) -> Vec<(usize, f64)> {
    const HALF: usize = 12;
    let to = to.min(profile.len());
    if to <= from + 2 {
        return Vec::new();
    }
    let slice = &profile[from..to];
    let lo = slice.iter().cloned().fold(f64::INFINITY, f64::min);
    let hi = slice.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    let mut peaks = Vec::new();
    for x in from + 1..to - 1 {
        let v = profile[x];
        let a = x.saturating_sub(HALF).max(from);
        let b = (x + HALF + 1).min(to);
        let window = &profile[a..b];
        if window.iter().any(|&w| w > v) || profile[x - 1] == v {
            continue;
        }
        // 山の高さ (窓の中の最小値との差) が全体の値域の 5% 以上
        let base = window.iter().cloned().fold(f64::INFINITY, f64::min);
        if v - base >= (hi - lo) * 0.05 {
            peaks.push((x, v));
        }
    }
    peaks.sort_by(|p, q| q.1.partial_cmp(&p.1).unwrap_or(std::cmp::Ordering::Equal));
    peaks.truncate(max_count);
    peaks
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subtract_is_per_pixel() {
        let light = Image {
            width: 2,
            height: 1,
            data: vec![10.0, 3.0],
        };
        let dark = Image {
            width: 2,
            height: 1,
            data: vec![4.0, 5.0],
        };
        assert_eq!(subtract(&light, &dark).unwrap().data, vec![6.0, -2.0]);
        assert!(subtract(&light, &Image::new(1, 1)).is_err());
    }

    #[test]
    fn band_is_centered() {
        assert_eq!(band_rows(3060), (1490, 1570));
        assert_eq!(band_rows(96), (8, 88));
        assert_eq!(band_rows(40), (0, 40));
    }

    #[test]
    fn smoothing_removes_column_alternation() {
        assert_eq!(
            smooth_profile(&[0.0, 4.0, 0.0, 4.0, 0.0]),
            vec![1.0, 2.0, 2.0, 2.0, 1.0]
        );
    }

    #[test]
    fn zeroth_order_and_peaks() {
        let mut p = vec![1.0; 400];
        for x in 345..356 {
            p[x] = 100.0;
        }
        p[100] = 30.0;
        p[200] = 50.0;
        assert_eq!(guess_zeroth_order(&p), Some(350));
        assert_eq!(find_peaks(&p, 0, 330, 5), vec![(200, 50.0), (100, 30.0)]);
    }
}
