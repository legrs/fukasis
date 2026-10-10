// SPDX-License-Identifier: MIT
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use fukasis::calib::{self, Calibration};
use fukasis::plot::{self, Series};
use fukasis::{filter, image, spectrum, tiff};

const USAGE: &str = "\
fukasis - FUKASIS-app で撮影したデータを PC で処理する

使い方:
  fukasis dark <light.tif> <dark.tif> [-o darked.tif]
      ダーク減算 (アプリの dark 画面). 既定の出力先は light と同じフォルダの darked.tif

  fukasis info <image.tif>
      画像の大きさ, 0次光の位置の推定, 輝線の候補を表示する

  fukasis calib --fol <x> --line <x>=<nm> [--line <x>=<nm> ...] [-o calib.csv]
      波長校正データを作る (アプリの calibration 画面). x は画像上の位置 (px).
      --fol は 0次光の位置. 校正点は 4 本以上を推奨

  fukasis calib --check <calib.csv> [--fol <x>]
      既存の校正データで出力される波長の範囲を確かめる

  fukasis csv --image <stacked.tif|darked.tif> --calib <calib.csv>
              (--sensitivity <sensit.csv> | --no-sensitivity)
              [--fol <x>|auto] [--metadata <metadata.csv>] [-o spectrum.csv]
              [--band-width <px>] [--band-center <0-1>] [--cfa <RGGB|GRBG|GBRG|BGGR|MONO>]
      スペクトルを csv に出力する (アプリの csv 画面). -o を省くと標準出力に書く.
      --fol を省くか auto にすると 0次光の位置を自動で推定する.
      --band-width / --band-center は読む帯の幅と中心 (既定は 80 px, 画像の中央),
      --cfa はカラーフィルタ配列 (既定は metadata に記録されたもの, 無ければ GBRG).
      --no-sensitivity を付けると感度校正をしない (感度データは要らない)

  fukasis graph <spectrum.csv> [<spectrum.csv> ...] [-o spectrum.svg] [--title <text>] [--keep-outliers]
      スペクトルのグラフを SVG に出力する (アプリの view 画面). 8 本まで重ねて描ける.
      既定では明らかな異常値を除く. --keep-outliers で除かずに描く

  fukasis help | --version
";

struct Args {
    positional: Vec<String>,
    options: Vec<(String, String)>,
    flags: Vec<String>,
}

impl Args {
    fn parse(
        args: &[String],
        value_options: &[&str],
        flag_options: &[&str],
    ) -> Result<Args, String> {
        let mut parsed = Args {
            positional: Vec::new(),
            options: Vec::new(),
            flags: Vec::new(),
        };
        let mut i = 0;
        while i < args.len() {
            let arg = args[i].as_str();
            let name = if arg == "-o" { "--output" } else { arg };
            if value_options.contains(&name) {
                let value = args
                    .get(i + 1)
                    .ok_or_else(|| format!("{} に値がありません", arg))?;
                parsed.options.push((name.to_string(), value.clone()));
                i += 2;
            } else if flag_options.contains(&name) {
                parsed.flags.push(name.to_string());
                i += 1;
            } else if arg.starts_with('-') && arg.len() > 1 && arg.parse::<f64>().is_err() {
                return Err(format!("不明なオプション: {}", arg));
            } else {
                parsed.positional.push(arg.to_string());
                i += 1;
            }
        }
        Ok(parsed)
    }

    fn get(&self, name: &str) -> Option<&str> {
        self.options
            .iter()
            .rev()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }

    fn all(&self, name: &str) -> Vec<&str> {
        self.options
            .iter()
            .filter(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
            .collect()
    }

    fn require(&self, name: &str) -> Result<&str, String> {
        self.get(name)
            .ok_or_else(|| format!("{} を指定してください", name))
    }

    fn has(&self, name: &str) -> bool {
        self.flags.iter().any(|f| f == name)
    }
}

fn read_image(path: &str) -> Result<image::Image, String> {
    let bytes = fs::read(path).map_err(|e| format!("{} を開けません: {}", path, e))?;
    tiff::decode(&bytes).map_err(|e| format!("{}: {}", path, e))
}

fn read_text(path: &str) -> Result<String, String> {
    fs::read_to_string(path).map_err(|e| format!("{} を開けません: {}", path, e))
}

fn write_file(path: &Path, contents: &[u8]) -> Result<(), String> {
    fs::write(path, contents).map_err(|e| format!("{} に書けません: {}", path.display(), e))
}

fn parse_position(text: &str, what: &str) -> Result<f64, String> {
    text.trim()
        .parse::<f64>()
        .map_err(|_| format!("{} の値 \"{}\" が数値ではありません", what, text))
}

fn cmd_dark(args: &[String]) -> Result<(), String> {
    let a = Args::parse(args, &["--output"], &[])?;
    let [light_path, dark_path] = a.positional.as_slice() else {
        return Err("light と dark の 2 つの画像を指定してください".to_string());
    };
    let light = read_image(light_path)?;
    let dark = read_image(dark_path)?;
    let result = image::subtract(&light, &dark)?;
    let output = match a.get("--output") {
        Some(o) => PathBuf::from(o),
        None => Path::new(light_path).with_file_name("darked.tif"),
    };
    write_file(&output, &tiff::encode(&result))?;
    eprintln!(
        "ダーク減算しました: {} ({}x{})",
        output.display(),
        result.width,
        result.height
    );
    Ok(())
}

fn cmd_info(args: &[String]) -> Result<(), String> {
    let a = Args::parse(args, &[], &[])?;
    let [path] = a.positional.as_slice() else {
        return Err("画像を 1 つ指定してください".to_string());
    };
    let img = read_image(path)?;
    let finite = || img.data.iter().cloned().filter(|v| v.is_finite());
    let min = finite().fold(f32::INFINITY, f32::min);
    let max = finite().fold(f32::NEG_INFINITY, f32::max);
    let (y1, y2) = image::band_rows(img.height);
    println!("大きさ: {} x {}", img.width, img.height);
    println!("値の範囲: {} .. {}", min, max);
    println!("スペクトルを読む帯: y = {} .. {}", y1, y2 - 1);

    let profile = image::smooth_profile(&image::band_profile(&img));
    let Some(fol) = image::guess_zeroth_order(&profile) else {
        return Ok(());
    };
    println!("0次光の位置 (推定): x = {}", fol);
    println!("輝線の候補 (推定, 明るい順). x は画像上の位置, t は 0次光からの距離:");
    // 0次光のにじみを避けて, 少し離れたところから探す
    for (x, v) in image::find_peaks(&profile, 0, fol.saturating_sub(40), 12) {
        println!("  x = {:5}  t = {:5}  高さ {:.1}", x, fol - x, v);
    }
    Ok(())
}

fn report_calibration(cal: &Calibration, size: usize) {
    let f = calib::fit(&cal.t, &cal.c);
    if !f.ok {
        eprintln!("警告: 位置の異なる校正点が 2 つ未満のため, 波長を計算できません");
        return;
    }
    eprintln!("校正点 {} 個, {} 次式", cal.t.len(), f.degree);
    for (t, c) in cal.t.iter().zip(&cal.c) {
        eprintln!(
            "  t = {:6.0}  {:8.2} nm  (当てはめとの差 {:+.3} nm)",
            t,
            c,
            f.at(*t) - c
        );
    }
    match calib::output_range(&f, &cal.t, size) {
        None => eprintln!("警告: この校正データでは 400 - 700 nm に対応する画素がありません"),
        Some(r) => {
            let (w1, w2) = (f.at(r.lo as f64), f.at(r.hi as f64));
            eprintln!(
                "出力される波長: {:.1} - {:.1} nm (t = {} .. {})",
                w1.min(w2),
                w1.max(w2),
                r.lo,
                r.hi
            );
            if r.cut_low || r.cut_high {
                eprintln!("警告: 波長が途中で折り返すため, 出力される範囲が欠けています。校正点の位置と波長を確認するか, 校正点を追加してください");
            }
        }
    }
}

fn cmd_calib(args: &[String]) -> Result<(), String> {
    let a = Args::parse(args, &["--fol", "--line", "--check", "--output"], &[])?;
    if let Some(path) = a.get("--check") {
        let cal = calib::parse_calibration(&read_text(path)?)?;
        // 0次光の位置が分からないときは, 校正点より十分遠くまで調べる
        let size = match a.get("--fol") {
            Some(fol) => parse_position(fol, "--fol")?.round() as usize,
            None => (cal.t.iter().cloned().fold(0.0, f64::max) * 2.0) as usize,
        };
        report_calibration(&cal, size);
        return Ok(());
    }

    let fol = parse_position(a.require("--fol")?, "--fol")?;
    let mut cal = Calibration {
        t: Vec::new(),
        c: Vec::new(),
    };
    for line in a.all("--line") {
        let (x, nm) = line
            .split_once('=')
            .ok_or_else(|| format!("--line は <x>=<nm> の形で指定してください: {}", line))?;
        cal.t.push((fol - parse_position(x, "--line")?).round());
        cal.c.push(parse_position(nm, "--line")?);
    }
    if cal.t.len() < 2 {
        return Err("--line を 2 つ以上 (推奨は 4 つ以上) 指定してください".to_string());
    }
    report_calibration(&cal, fol.round() as usize);
    let text = calib::format_calibration(&cal);
    match a.get("--output") {
        Some(o) => {
            write_file(Path::new(o), text.as_bytes())?;
            eprintln!("校正データを保存しました: {}", o);
        }
        None => println!("{}", text),
    }
    Ok(())
}

fn cmd_csv(args: &[String]) -> Result<(), String> {
    let a = Args::parse(
        args,
        &[
            "--image",
            "--calib",
            "--sensitivity",
            "--fol",
            "--metadata",
            "--band-width",
            "--band-center",
            "--cfa",
            "--output",
        ],
        &["--no-sensitivity"],
    )?;
    let image_path = a.require("--image")?;
    let img = read_image(image_path)?;
    let cal = calib::parse_calibration(&read_text(a.require("--calib")?)?)?;
    // --no-sensitivity のときは感度校正をしないので, 感度データは要らない
    let no_sensitivity = a.has("--no-sensitivity");
    if no_sensitivity && a.get("--sensitivity").is_some() {
        return Err("--no-sensitivity と --sensitivity は同時に指定できません".to_string());
    }
    let sensitivity = if no_sensitivity {
        spectrum::Sensitivity::default()
    } else {
        spectrum::parse_sensitivity(&read_text(a.require("--sensitivity")?)?)?
    };
    let fol = match a.get("--fol") {
        Some(v) if v != "auto" => parse_position(v, "--fol")?.round() as usize,
        _ => {
            let fol = image::guess_zeroth_order(&image::smooth_profile(&image::band_profile(&img)))
                .ok_or("0次光の位置を推定できません")?;
            eprintln!(
                "0次光の位置を x = {} と推定しました (違う場合は --fol で指定してください)",
                fol
            );
            fol
        }
    };
    // 1 行目は観測の情報. metadata.csv が無ければ画像のファイル名を入れておく
    let header = match a.get("--metadata") {
        Some(path) => read_text(path)?,
        None => image_path.to_string(),
    };

    let mut options = spectrum::Options {
        no_sensitivity,
        ..spectrum::Options::default()
    };
    if let Some(v) = a.get("--band-width") {
        options.band_width = v
            .parse()
            .ok()
            .filter(|w| *w >= 2)
            .ok_or("--band-width には 2 以上の整数を指定してください")?;
    }
    if let Some(v) = a.get("--band-center") {
        options.band_center = v
            .parse()
            .ok()
            .filter(|c| (0.0..=1.0).contains(c))
            .ok_or("--band-center には 0 から 1 の数を指定してください")?;
    }
    // カラーフィルタ配列: 指定があればそれ, 無ければ metadata に記録されたもの, それも無ければ GBRG
    match a.get("--cfa") {
        Some(v) => {
            options.cfa = spectrum::Cfa::parse(v)
                .ok_or("--cfa には RGGB / GRBG / GBRG / BGGR / MONO のどれかを指定してください")?;
        }
        None => {
            if let Some(cfa) = spectrum::cfa_from_metadata(&header) {
                options.cfa = cfa;
            }
        }
    }

    report_calibration(&cal, fol);
    let result = spectrum::extract_with(&img, &cal, &sensitivity, fol, &options)?;
    // 感度校正をしていないことは, あとで分かるように csv の 1 行目に残す
    let mut first_line = header.lines().next().unwrap_or("").to_string();
    if no_sensitivity {
        first_line.push_str(spectrum::NO_SENSITIVITY_MARK);
        eprintln!("感度校正はしていません (--no-sensitivity)");
    }
    let text = spectrum::to_csv(&result, &first_line);
    match a.get("--output") {
        Some(o) => {
            write_file(Path::new(o), text.as_bytes())?;
            eprintln!(
                "スペクトルを保存しました: {} ({} 点)",
                o,
                result.wavelength.len()
            );
        }
        None => print!("{}", text),
    }
    Ok(())
}

fn cmd_graph(args: &[String]) -> Result<(), String> {
    let a = Args::parse(args, &["--output", "--title"], &["--keep-outliers"])?;
    if a.positional.is_empty() {
        return Err("スペクトルの csv を 1 つ以上指定してください".to_string());
    }
    let mut series = Vec::new();
    for path in &a.positional {
        let (x, y) = spectrum::parse_csv(&read_text(path)?);
        let filtered = filter::filter(&x, &y, !a.has("--keep-outliers"));
        eprintln!(
            "{}: {} 点を表示, {} 点を除外",
            path,
            filtered.wavelength.len(),
            filtered.excluded
        );
        let name = Path::new(path)
            .file_stem()
            .map_or(path.clone(), |s| s.to_string_lossy().into_owned());
        series.push(Series {
            name,
            x: filtered.wavelength,
            y: filtered.intensity,
        });
    }
    let title = match a.get("--title") {
        Some(t) => t.to_string(),
        None if series.len() == 1 => series[0].name.clone(),
        None => "Spectrum".to_string(),
    };
    let svg = plot::render_svg(&series, &title)?;
    let output = PathBuf::from(a.get("--output").unwrap_or("spectrum.svg"));
    write_file(&output, svg.as_bytes())?;
    eprintln!("グラフを保存しました: {}", output.display());
    Ok(())
}

fn run(args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        print!("{}", USAGE);
        return Ok(());
    };
    let rest = &args[1..];
    match command.as_str() {
        "dark" => cmd_dark(rest),
        "info" => cmd_info(rest),
        "calib" => cmd_calib(rest),
        "csv" => cmd_csv(rest),
        "graph" => cmd_graph(rest),
        "help" | "--help" | "-h" => {
            print!("{}", USAGE);
            Ok(())
        }
        "--version" | "-V" => {
            println!("fukasis {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        other => Err(format!("不明なコマンド: {}\n\n{}", other, USAGE)),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("エラー: {}", message);
            ExitCode::FAILURE
        }
    }
}
