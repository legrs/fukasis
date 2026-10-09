// SPDX-License-Identifier: MIT
//! アプリの C++ (makecsv) をそのまま動かして作った正解データ (testdata/) と,
//! この実装の出力が一致することを確かめる.

#![allow(clippy::manual_is_multiple_of)]

use fukasis::image::{self, Image};
use fukasis::{calib, spectrum, tiff};

const W: usize = 420;
const H: usize = 96;
const FOL: usize = 390;

struct Lcg(u32);

impl Lcg {
    fn next(&mut self) -> u32 {
        self.0 = self.0.wrapping_mul(1664525).wrapping_add(1013904223);
        self.0 >> 16
    }
}

fn tri(peak: i32, slope: i32, d: i32) -> i32 {
    (peak - slope * d.abs()).max(0)
}

/// 合成画像. 整数演算だけで作るので, 正解データを作った C++ 版と同じ値になる
fn synth(seed: u32, light: bool) -> Image {
    let mut rng = Lcg(seed);
    let center = [80, 190, 290]; // b, g, r の連続光の中心 (0次光からの距離)
    let lines = [51, 189, 241, 271]; // 輝線
    let mut img = Image::new(W, H);
    for y in 0..H {
        for x in 0..W {
            let ch = if x % 2 != 0 && y % 2 == 0 {
                0
            } else if x % 2 == 0 && y % 2 != 0 {
                2
            } else {
                1
            };
            let mut v = 64 + (rng.next() % 9) as i32;
            let hot = rng.next() % 499 == 0;
            if light {
                let t = FOL as i32 - x as i32;
                v += tri(400, 3, t - center[ch]);
                for line in lines {
                    v += tri(300, 80, t - line);
                }
                if (x as i32 - FOL as i32).abs() <= 5 {
                    v = 1023;
                }
            }
            if hot {
                v += 700;
            }
            img.data[y * W + x] = v as f32;
        }
    }
    img
}

fn testdata(name: &str) -> String {
    let path = format!("{}/../testdata/{}", env!("CARGO_MANIFEST_DIR"), name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {}", path, e))
}

fn darked() -> Image {
    image::subtract(&synth(12345, true), &synth(777, false)).unwrap()
}

fn assert_matches_golden(calibration: &str, expected: &str) {
    let cal = calib::parse_calibration(&testdata(calibration)).unwrap();
    let sensitivity = spectrum::parse_sensitivity(&testdata("sensitivity.csv")).unwrap();
    let result = spectrum::extract(&darked(), &cal, &sensitivity, FOL).unwrap();
    let actual = spectrum::to_csv(&result, &testdata("metadata.csv"));

    let expected = testdata(expected);
    let (actual_lines, expected_lines): (Vec<&str>, Vec<&str>) =
        (actual.lines().collect(), expected.lines().collect());
    assert_eq!(actual_lines.len(), expected_lines.len(), "行数が違う");
    // ヘッダーの 2 行は完全に一致する
    assert_eq!(actual_lines[..2], expected_lines[..2]);
    for (a, e) in actual_lines.iter().zip(&expected_lines).skip(2) {
        let (aw, ai) = a.split_once(',').unwrap();
        let (ew, ei) = e.split_once(',').unwrap();
        for (a, e) in [(aw, ew), (ai, ei)] {
            let (a, e): (f64, f64) = (a.parse().unwrap(), e.parse().unwrap());
            // 有効 6 桁で書かれているので, 最後の桁の丸めの差だけ許す
            assert!((a - e).abs() <= 2e-6 * e.abs().max(1e-6), "{} != {}", a, e);
        }
    }
}

#[test]
fn synthetic_image_is_same_as_reference() {
    // 正解データを作ったときの darked 画像の値
    let img = darked();
    assert_eq!(img.data.iter().map(|&v| v as f64).sum::<f64>(), 6474625.0);
    assert_eq!(img.at(10, 20), -1.0);
    assert_eq!(img.at(50, 339), 614.0);
}

#[test]
fn four_point_calibration() {
    assert_matches_golden("calib_4pt.csv", "expected_spectrum_4pt.csv");
}

#[test]
fn six_point_calibration() {
    assert_matches_golden("calib_6pt.csv", "expected_spectrum_6pt.csv");
}

#[test]
fn truncated_calibration() {
    // 輝線の位置を読み違えて, 3 次式が 400 - 700 nm の途中で折り返す校正データ.
    // 折り返した先は出力されない
    let cal = calib::parse_calibration(&testdata("calib_truncated.csv")).unwrap();
    let range = calib::output_range(&calib::fit(&cal.t, &cal.c), &cal.t, FOL).unwrap();
    assert!(range.cut_low && range.cut_high);
    assert_matches_golden("calib_truncated.csv", "expected_spectrum_truncated.csv");
}

#[test]
fn spectrum_survives_tiff_roundtrip() {
    // TIFF に書いて読み直しても同じ画像になる
    let img = darked();
    assert_eq!(tiff::decode(&tiff::encode(&img)).unwrap(), img);
}

#[test]
fn reads_strip_tiff_fixtures() {
    for name in ["strips_le.tif", "strips_be.tif"] {
        let path = format!("{}/../testdata/{}", env!("CARGO_MANIFEST_DIR"), name);
        let img = tiff::decode(&std::fs::read(&path).unwrap()).unwrap();
        assert_eq!((img.width, img.height), (8, 4), "{}", name);
        for y in 0..4 {
            for x in 0..8 {
                assert_eq!(
                    img.at(y, x),
                    (y * 10 + x) as f32 + 0.5,
                    "{} ({}, {})",
                    name,
                    x,
                    y
                );
            }
        }
    }
}

/// コマンドを実際に動かして, dark → csv → graph の流れが通ることを確かめる
#[test]
fn command_line_end_to_end() {
    use std::process::Command;
    let dir = std::env::temp_dir().join(format!("fukasis-cli-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = |name: &str| dir.join(name).to_string_lossy().into_owned();
    let data = |name: &str| format!("{}/../testdata/{}", env!("CARGO_MANIFEST_DIR"), name);
    std::fs::write(path("light.tif"), tiff::encode(&synth(12345, true))).unwrap();
    std::fs::write(path("dark.tif"), tiff::encode(&synth(777, false))).unwrap();
    let run = |args: &[&str]| {
        let out = Command::new(env!("CARGO_BIN_EXE_fukasis"))
            .args(args)
            .output()
            .unwrap();
        assert!(
            out.status.success(),
            "{:?}: {}",
            args,
            String::from_utf8_lossy(&out.stderr)
        );
        (
            String::from_utf8(out.stdout).unwrap(),
            String::from_utf8(out.stderr).unwrap(),
        )
    };

    // dark: 既定では light と同じフォルダの darked.tif に書く
    run(&["dark", &path("light.tif"), &path("dark.tif")]);
    assert_eq!(
        tiff::decode(&std::fs::read(path("darked.tif")).unwrap()).unwrap(),
        darked()
    );

    // info: 0次光の位置を見つける
    let (info, _) = run(&["info", &path("light.tif")]);
    assert!(info.contains("x = 390"), "{}", info);

    // calib: 画像上の位置から校正データを作る. testdata の校正データと同じものになる
    let (calibration, _) = run(&[
        "calib",
        "--fol",
        "390",
        "--line",
        "339=435.8",
        "--line",
        "201=546.1",
        "--line",
        "149=588",
        "--line",
        "119=611.6",
    ]);
    assert_eq!(calibration.trim_end(), testdata("calib_4pt.csv"));

    // csv: 0次光は自動で推定させる
    run(&[
        "csv",
        "--image",
        &path("darked.tif"),
        "--calib",
        &data("calib_4pt.csv"),
        "--sensitivity",
        &data("sensitivity.csv"),
        "--metadata",
        &data("metadata.csv"),
        "-o",
        &path("spectrum.csv"),
    ]);
    let spectrum_csv = std::fs::read_to_string(path("spectrum.csv")).unwrap();
    assert_eq!(
        spectrum_csv.lines().count(),
        testdata("expected_spectrum_4pt.csv").lines().count()
    );

    // graph
    let (_, log) = run(&[
        "graph",
        &path("spectrum.csv"),
        &data("expected_spectrum_6pt.csv"),
        "-o",
        &path("out.svg"),
    ]);
    assert!(log.contains("0 点を除外"), "{}", log);
    let svg = std::fs::read_to_string(path("out.svg")).unwrap();
    assert_eq!(svg.matches("<path").count(), 2);

    // エラーは終了コードで分かる
    let bad = Command::new(env!("CARGO_BIN_EXE_fukasis"))
        .args(["csv", "--image", &path("nothing.tif")])
        .output()
        .unwrap();
    assert!(!bad.status.success());

    std::fs::remove_dir_all(&dir).unwrap();
}
