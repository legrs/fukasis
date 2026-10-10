// SPDX-License-Identifier: MIT
//! スペクトル (波長, 強度) をグラフに出せる形に整える. 明らかな異常値の除外もここで行う.
//! アプリの SpectrumFilter.java と同じ計算.

/// 孤立したスパイクの判定に使う, 片側の近傍点数
const SPIKE_WINDOW: usize = 5;
/// これより長く続く山は輝線などの本物の構造とみなして残す
const SPIKE_MAX_RUN: usize = 2;
/// 近傍の中央値からのずれがこの倍率 (近傍のばらつき比) を超えたらスパイク候補
const SPIKE_SIGMA: f64 = 12.0;
/// 同じく, 全体の値域に対する割合
const SPIKE_RANGE_RATIO: f64 = 0.15;
/// スパイクの隣がこの割合以上持ち上がっていたら, 裾のある本物のピークとみなして残す
const SPIKE_SHOULDER_RATIO: f64 = 0.2;
const SPIKE_MAX_PASS: usize = 3;

#[derive(Clone, Debug, PartialEq)]
pub struct Filtered {
    /// 波長の昇順
    pub wavelength: Vec<f64>,
    pub intensity: Vec<f64>,
    /// 表示から外した点数
    pub excluded: usize,
}

/// wavelength, intensity はファイルに書かれていた順.
/// NaN や無限大は常に外す. exclude_outliers のときはさらに次のものを外す.
///
/// - 波長の並びが折り返している部分 (最も長い単調な区間だけ残す)
/// - 負の強度
/// - 孤立したスパイク
pub fn filter(wavelength: &[f64], intensity: &[f64], exclude_outliers: bool) -> Filtered {
    let n = wavelength.len().min(intensity.len());
    let mut keep: Vec<bool> = (0..n)
        .map(|i| wavelength[i].is_finite() && intensity[i].is_finite())
        .collect();

    if exclude_outliers {
        keep_longest_monotonic_run(wavelength, &mut keep);
        for i in 0..n {
            if intensity[i] < 0.0 {
                keep[i] = false;
            }
        }
        for _ in 0..SPIKE_MAX_PASS {
            if !drop_isolated_spikes(intensity, &mut keep) {
                break;
            }
        }
    }

    let mut order = indices_of(&keep);
    // グラフは波長の昇順でないと描けない (sort_by は安定ソート)
    order.sort_by(|&a, &b| wavelength[a].partial_cmp(&wavelength[b]).unwrap());
    Filtered {
        wavelength: order.iter().map(|&i| wavelength[i]).collect(),
        intensity: order.iter().map(|&i| intensity[i]).collect(),
        excluded: n - order.len(),
    }
}

fn indices_of(keep: &[bool]) -> Vec<usize> {
    (0..keep.len()).filter(|&i| keep[i]).collect()
}

/// 波長がファイル順で単調に変化している区間のうち, 最も長いものだけを残す
fn keep_longest_monotonic_run(wavelength: &[f64], keep: &mut [bool]) {
    let idx = indices_of(keep);
    let m = idx.len();
    if m < 3 {
        return;
    }
    let (mut best_start, mut best_end) = (0usize, 0usize);
    let mut start = 0usize;
    let mut dir = std::cmp::Ordering::Equal;
    for k in 1..m {
        let d = wavelength[idx[k]]
            .partial_cmp(&wavelength[idx[k - 1]])
            .unwrap();
        if d != std::cmp::Ordering::Equal && dir != std::cmp::Ordering::Equal && d != dir {
            // 向きが変わった. 折り返し点は次の区間の先頭にもなる
            if best_end - best_start < (k - 1) - start {
                best_start = start;
                best_end = k - 1;
            }
            start = k - 1;
            dir = d;
        } else if dir == std::cmp::Ordering::Equal {
            dir = d;
        }
    }
    if best_end - best_start < (m - 1) - start {
        best_start = start;
        best_end = m - 1;
    }
    for k in 0..m {
        if k < best_start || best_end < k {
            keep[idx[k]] = false;
        }
    }
}

/// 近傍の中央値から大きく外れた, 1 - 2 点だけで裾も無い孤立したスパイクを外す. 外したものがあれば true
fn drop_isolated_spikes(intensity: &[f64], keep: &mut [bool]) -> bool {
    let idx = indices_of(keep);
    let m = idx.len();
    if m < 2 * SPIKE_WINDOW + 1 {
        return false;
    }
    let min = idx
        .iter()
        .map(|&i| intensity[i])
        .fold(f64::INFINITY, f64::min);
    let max = idx
        .iter()
        .map(|&i| intensity[i])
        .fold(f64::NEG_INFINITY, f64::max);
    let range = max - min;
    if range <= 0.0 {
        return false;
    }

    let mut spike = vec![false; m];
    let mut deviation = vec![0.0f64; m]; // 近傍の中央値からのずれ
    for k in 0..m {
        let from = k.saturating_sub(SPIKE_WINDOW);
        let to = (k + SPIKE_WINDOW).min(m - 1);
        let mut neighbors: Vec<f64> = (from..=to)
            .filter(|&j| j != k)
            .map(|j| intensity[idx[j]])
            .collect();
        let med = median(&mut neighbors);
        for v in neighbors.iter_mut() {
            *v = (*v - med).abs();
        }
        // 正規分布なら標準偏差に相当する値
        let sigma = 1.4826 * median(&mut neighbors);
        let threshold = (SPIKE_SIGMA * sigma).max(SPIKE_RANGE_RATIO * range);
        deviation[k] = intensity[idx[k]] - med;
        spike[k] = threshold < deviation[k].abs();
    }

    let mut dropped = false;
    let mut k = 0;
    while k < m {
        if !spike[k] {
            k += 1;
            continue;
        }
        let mut end = k;
        while end + 1 < m && spike[end + 1] {
            end += 1;
        }
        if end - k < SPIKE_MAX_RUN && !has_shoulder(&deviation, k, end) {
            for j in k..=end {
                keep[idx[j]] = false;
            }
            dropped = true;
        }
        k = end + 1;
    }
    dropped
}

/// スパイク [start, end] の両隣が, スパイクと同じ向きに持ち上がっているか
fn has_shoulder(deviation: &[f64], start: usize, end: usize) -> bool {
    let mut peak = deviation[start];
    for &d in &deviation[start..=end] {
        if peak.abs() < d.abs() {
            peak = d;
        }
    }
    let sign = peak.signum();
    let limit = SPIKE_SHOULDER_RATIO * peak.abs();
    if start > 0 && limit <= sign * deviation[start - 1] {
        return true;
    }
    end + 1 < deviation.len() && limit <= sign * deviation[end + 1]
}

fn median(values: &mut [f64]) -> f64 {
    values.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = values.len();
    if n % 2 == 1 {
        values[n / 2]
    } else {
        (values[n / 2 - 1] + values[n / 2]) / 2.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const N: usize = 200;

    fn wavelengths() -> Vec<f64> {
        (0..N).map(|i| 400.0 + 0.3 * i as f64).collect()
    }

    // なだらかな連続光に輝線を 2 本載せたもの
    fn intensities() -> Vec<f64> {
        let mut y: Vec<f64> = (0..N)
            .map(|i| 0.1 + 0.05 * (i as f64 / 20.0).sin())
            .collect();
        y[50] = 0.6;
        y[51] = 1.0;
        y[52] = 0.6;
        y[120] = 0.3;
        y[121] = 0.9;
        y[122] = 0.3;
        y
    }

    fn max(v: &[f64]) -> f64 {
        v.iter().cloned().fold(f64::NEG_INFINITY, f64::max)
    }

    #[test]
    fn emission_lines_are_kept() {
        let r = filter(&wavelengths(), &intensities(), true);
        assert_eq!(r.excluded, 0);
        assert_eq!(max(&r.intensity), 1.0);
    }

    #[test]
    fn obvious_outliers_are_excluded() {
        let mut y = intensities();
        y[80] = 50.0; // 孤立したスパイク
        y[150] = 30.0; // 2 点続くスパイク
        y[151] = 28.0;
        y[10] = -3.0; // 負の強度
        y[20] = f64::NAN;
        let on = filter(&wavelengths(), &y, true);
        assert_eq!(on.excluded, 5);
        assert_eq!(max(&on.intensity), 1.0);
        // 切替をオフにしても NaN だけは描けないので外す
        let off = filter(&wavelengths(), &y, false);
        assert_eq!(off.excluded, 1);
        assert_eq!(max(&off.intensity), 50.0);
    }

    #[test]
    fn folded_wavelengths_are_excluded() {
        let fold = 30;
        let mut x = wavelengths();
        let mut y = intensities();
        for i in 0..fold {
            x.push(x[N - 1] - 0.3 * (i + 1) as f64);
            y.push(0.1);
        }
        let on = filter(&x, &y, true);
        assert_eq!(on.excluded, fold);
        assert!(on.wavelength.windows(2).all(|w| w[0] <= w[1]));
        let off = filter(&x, &y, false);
        assert_eq!(off.excluded, 0);
        assert!(off.wavelength.windows(2).all(|w| w[0] <= w[1]));
    }

    #[test]
    fn descending_file_is_sorted() {
        let x: Vec<f64> = wavelengths().into_iter().rev().collect();
        let y: Vec<f64> = intensities().into_iter().rev().collect();
        let r = filter(&x, &y, true);
        assert_eq!(r.excluded, 0);
        assert_eq!(r.wavelength[0], 400.0);
    }
}
