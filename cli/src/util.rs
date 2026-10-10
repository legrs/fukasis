// SPDX-License-Identifier: MIT

/// C++ の `ostream << double` (有効 6 桁, printf の %g) と同じ表記にする.
/// アプリが書き出す csv と同じ見た目にするため
pub fn fmt_g(x: f64) -> String {
    if x.is_nan() {
        return "nan".to_string();
    }
    if x.is_infinite() {
        return if x > 0.0 { "inf" } else { "-inf" }.to_string();
    }
    if x == 0.0 {
        return "0".to_string();
    }
    // 有効 6 桁に丸めた仮数部と指数を得る
    let sci = format!("{:.5e}", x);
    let (mantissa, exp) = sci.split_once('e').unwrap();
    let exp: i32 = exp.parse().unwrap();
    if !(-4..6).contains(&exp) {
        let mantissa = trim_zeros(mantissa);
        let sign = if exp < 0 { '-' } else { '+' };
        return format!("{}e{}{:02}", mantissa, sign, exp.abs());
    }
    let decimals = (5 - exp).max(0) as usize;
    trim_zeros(&format!("{:.*}", decimals, x)).to_string()
}

fn trim_zeros(s: &str) -> &str {
    if s.contains('.') {
        s.trim_end_matches('0').trim_end_matches('.')
    } else {
        s
    }
}

/// "nan" や "inf" も読める数値の読み取り. 数値でなければ None
pub fn parse_value(s: &str) -> Option<f64> {
    let v = s.trim().to_ascii_lowercase();
    let unsigned = v
        .strip_prefix('-')
        .or_else(|| v.strip_prefix('+'))
        .unwrap_or(&v);
    match unsigned {
        "nan" => Some(f64::NAN),
        "inf" | "infinity" => Some(if v.starts_with('-') {
            f64::NEG_INFINITY
        } else {
            f64::INFINITY
        }),
        "" => None,
        _ => {
            // 数字で始まらないもの (ヘッダーなど) は数値として扱わない
            let first = unsigned.chars().next().unwrap();
            if !(first.is_ascii_digit() || first == '.') {
                return None;
            }
            v.parse::<f64>().ok()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fmt_g_matches_cpp_stream() {
        assert_eq!(fmt_g(400.32), "400.32");
        assert_eq!(fmt_g(401.0331234), "401.033");
        assert_eq!(fmt_g(0.0239052499), "0.0239052");
        assert_eq!(fmt_g(0.000781028), "0.000781028");
        assert_eq!(fmt_g(0.0000123456789), "1.23457e-05");
        assert_eq!(fmt_g(1234567.0), "1.23457e+06");
        assert_eq!(fmt_g(999999.5), "1e+06");
        assert_eq!(fmt_g(1.0), "1");
        assert_eq!(fmt_g(0.5), "0.5");
        assert_eq!(fmt_g(-12.5), "-12.5");
        assert_eq!(fmt_g(0.0), "0");
        assert_eq!(fmt_g(f64::NAN), "nan");
    }

    #[test]
    fn parse_value_accepts_nan_and_inf() {
        assert_eq!(parse_value(" 1.5 "), Some(1.5));
        assert_eq!(parse_value("-2e-3"), Some(-0.002));
        assert!(parse_value("nan").unwrap().is_nan());
        assert!(parse_value("-nan").unwrap().is_nan());
        assert_eq!(parse_value("inf"), Some(f64::INFINITY));
        assert_eq!(parse_value("-inf"), Some(f64::NEG_INFINITY));
        assert_eq!(parse_value("wavelength/nm"), None);
        assert_eq!(parse_value("testseq"), None);
        assert_eq!(parse_value(""), None);
    }
}
