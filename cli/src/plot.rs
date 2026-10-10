// SPDX-License-Identifier: MIT
//! スペクトルの折れ線グラフを SVG にする (アプリの view 画面に相当).

/// 系列の色. 順番を固定して割り当てる (色覚特性があっても隣同士を見分けやすい並び)
const SERIES_LIGHT: [&str; 8] = [
    "#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948",
];
const SERIES_DARK: [&str; 8] = [
    "#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767",
];

pub const MAX_SERIES: usize = SERIES_LIGHT.len();

pub struct Series {
    pub name: String,
    /// 波長の昇順
    pub x: Vec<f64>,
    pub y: Vec<f64>,
}

/// 軸の目盛り. きりのよい間隔 (1, 2, 5 × 10^n) で 5 本前後
pub fn nice_ticks(min: f64, max: f64) -> Vec<f64> {
    if !(max > min) {
        return vec![min];
    }
    let raw = (max - min) / 5.0;
    let magnitude = 10f64.powf(raw.log10().floor());
    let step = match raw / magnitude {
        r if r < 1.5 => 1.0,
        r if r < 3.5 => 2.0,
        r if r < 7.5 => 5.0,
        _ => 10.0,
    } * magnitude;
    let mut ticks = Vec::new();
    let mut i = (min / step).ceil();
    while i * step <= max + step * 1e-9 {
        // + 0.0 は -0 を 0 にするため
        ticks.push(i * step + 0.0);
        i += 1.0;
    }
    ticks
}

fn tick_label(v: f64, step: f64) -> String {
    let decimals = if step >= 1.0 {
        0
    } else {
        (-step.log10().floor()) as usize
    };
    format!("{:.*}", decimals, v)
}

fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

pub fn render_svg(series: &[Series], title: &str) -> Result<String, String> {
    if series.is_empty() || series.iter().all(|s| s.x.is_empty()) {
        return Err("表示できるデータがありません".to_string());
    }
    if series.len() > MAX_SERIES {
        return Err(format!("一度に描けるのは {} 本までです", MAX_SERIES));
    }
    const W: f64 = 960.0;
    const H: f64 = 540.0;
    let legend_rows = if series.len() >= 2 {
        series.len().div_ceil(4)
    } else {
        0
    };
    let (left, right, bottom) = (64.0, 24.0, 52.0);
    let top = 48.0 + legend_rows as f64 * 22.0;
    let (pw, ph) = (W - left - right, H - top - bottom);

    let all_x = || series.iter().flat_map(|s| s.x.iter().cloned());
    let all_y = || series.iter().flat_map(|s| s.y.iter().cloned());
    let (x_min, x_max) = (
        all_x().fold(f64::INFINITY, f64::min),
        all_x().fold(f64::NEG_INFINITY, f64::max),
    );
    let (mut y_min, mut y_max) = (
        all_y().fold(f64::INFINITY, f64::min).min(0.0),
        all_y().fold(f64::NEG_INFINITY, f64::max),
    );
    if !(y_max > y_min) {
        y_max = y_min + 1.0;
    }
    let y_ticks = nice_ticks(y_min, y_max);
    // 縦軸は目盛りの位置まで広げて, 線が枠に貼り付かないようにする
    let y_step = if y_ticks.len() > 1 {
        y_ticks[1] - y_ticks[0]
    } else {
        1.0
    };
    y_min = (y_min / y_step).floor() * y_step;
    y_max = (y_max / y_step).ceil() * y_step;
    let y_ticks = nice_ticks(y_min, y_max);
    let x_span = if x_max > x_min { x_max - x_min } else { 1.0 };
    let sx = |x: f64| left + (x - x_min) / x_span * pw;
    let sy = |y: f64| top + ph - (y - y_min) / (y_max - y_min) * ph;

    let mut s = String::new();
    s.push_str(&format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {W} {H}\" width=\"{W}\" height=\"{H}\" font-family=\"system-ui, -apple-system, 'Segoe UI', sans-serif\">\n"
    ));
    s.push_str("<style>\n");
    s.push_str(".surface{fill:#fcfcfb}.title{fill:#0b0b0b;font-size:16px;font-weight:600}.label{fill:#52514e;font-size:12px}.tick{fill:#898781;font-size:11px}.grid{stroke:#e1e0d9;stroke-width:1}.axis{stroke:#c3c2b7;stroke-width:1}.line{fill:none;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}\n");
    for (i, c) in SERIES_LIGHT.iter().enumerate() {
        s.push_str(&format!(".s{i}{{stroke:{c}}}"));
    }
    s.push_str("\n@media (prefers-color-scheme: dark){.surface{fill:#1a1a19}.title{fill:#ffffff}.label{fill:#c3c2b7}.grid{stroke:#2c2c2a}.axis{stroke:#383835}");
    for (i, c) in SERIES_DARK.iter().enumerate() {
        s.push_str(&format!(".s{i}{{stroke:{c}}}"));
    }
    s.push_str("}\n</style>\n");
    s.push_str(&format!(
        "<rect class=\"surface\" width=\"{W}\" height=\"{H}\"/>\n"
    ));
    s.push_str(&format!(
        "<text class=\"title\" x=\"{left}\" y=\"28\">{}</text>\n",
        escape(title)
    ));

    // 凡例 (系列が 2 本以上のときだけ)
    if series.len() >= 2 {
        for (i, sr) in series.iter().enumerate() {
            let x = left + (i % 4) as f64 * (pw / 4.0);
            let y = 50.0 + (i / 4) as f64 * 22.0;
            s.push_str(&format!(
                "<line class=\"line s{i}\" x1=\"{x}\" y1=\"{y}\" x2=\"{}\" y2=\"{y}\"/>",
                x + 18.0
            ));
            s.push_str(&format!(
                "<text class=\"label\" x=\"{}\" y=\"{}\">{}</text>\n",
                x + 24.0,
                y + 4.0,
                escape(&sr.name)
            ));
        }
    }

    for &t in &y_ticks {
        let y = sy(t);
        s.push_str(&format!(
            "<line class=\"grid\" x1=\"{left}\" y1=\"{y:.1}\" x2=\"{}\" y2=\"{y:.1}\"/>",
            left + pw
        ));
        s.push_str(&format!(
            "<text class=\"tick\" x=\"{}\" y=\"{:.1}\" text-anchor=\"end\">{}</text>\n",
            left - 8.0,
            y + 4.0,
            tick_label(t, y_step)
        ));
    }
    let x_ticks = nice_ticks(x_min, x_max);
    let x_step = if x_ticks.len() > 1 {
        x_ticks[1] - x_ticks[0]
    } else {
        1.0
    };
    for &t in &x_ticks {
        let x = sx(t);
        s.push_str(&format!(
            "<line class=\"axis\" x1=\"{x:.1}\" y1=\"{}\" x2=\"{x:.1}\" y2=\"{}\"/>",
            top + ph,
            top + ph + 5.0
        ));
        s.push_str(&format!(
            "<text class=\"tick\" x=\"{x:.1}\" y=\"{}\" text-anchor=\"middle\">{}</text>\n",
            top + ph + 18.0,
            tick_label(t, x_step)
        ));
    }
    s.push_str(&format!(
        "<line class=\"axis\" x1=\"{left}\" y1=\"{}\" x2=\"{}\" y2=\"{}\"/>\n",
        top + ph,
        left + pw,
        top + ph
    ));
    s.push_str(&format!(
        "<text class=\"label\" x=\"{}\" y=\"{}\" text-anchor=\"middle\">Wavelength (nm)</text>\n",
        left + pw / 2.0,
        H - 12.0
    ));
    s.push_str(&format!(
        "<text class=\"label\" transform=\"translate(16 {}) rotate(-90)\" text-anchor=\"middle\">Relative intensity</text>\n",
        top + ph / 2.0
    ));

    for (i, sr) in series.iter().enumerate() {
        let mut d = String::new();
        for (k, (x, y)) in sr.x.iter().zip(&sr.y).enumerate() {
            d.push_str(&format!(
                "{}{:.2},{:.2}",
                if k == 0 { "M" } else { "L" },
                sx(*x),
                sy(*y)
            ));
        }
        s.push_str(&format!(
            "<path class=\"line s{i}\" d=\"{d}\"><title>{}</title></path>\n",
            escape(&sr.name)
        ));
    }
    s.push_str("</svg>\n");
    Ok(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ticks_are_round_numbers() {
        assert_eq!(
            nice_ticks(400.0, 700.0),
            vec![400.0, 450.0, 500.0, 550.0, 600.0, 650.0, 700.0]
        );
        let labels: Vec<String> = nice_ticks(0.0, 1.0)
            .iter()
            .map(|&t| tick_label(t, 0.2))
            .collect();
        assert_eq!(labels, vec!["0.0", "0.2", "0.4", "0.6", "0.8", "1.0"]);
        assert_eq!(tick_label(450.0, 50.0), "450");
    }

    #[test]
    fn svg_contains_lines_and_legend() {
        let a = Series {
            name: "a<b".to_string(),
            x: vec![400.0, 500.0, 600.0],
            y: vec![0.0, 1.0, 0.5],
        };
        let b = Series {
            name: "b".to_string(),
            x: vec![400.0, 600.0],
            y: vec![0.2, 0.3],
        };
        let single = render_svg(std::slice::from_ref(&a), "t").unwrap();
        assert_eq!(single.matches("<path").count(), 1);
        // 系列が 1 本なら凡例は出さない (タイトルが名前を兼ねる)
        assert_eq!(single.matches("a&lt;b").count(), 1);
        let both = render_svg(&[a, b], "t").unwrap();
        assert_eq!(both.matches("<path").count(), 2);
        assert_eq!(both.matches("a&lt;b").count(), 2);
        assert!(render_svg(&[], "t").is_err());
    }
}
