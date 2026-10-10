# 他の機種・iPhone で使うには

FUKASIS は Galaxy S22 の広角カメラ用に作られています。他の Android 機種や iPhone で使うのに必要なことと、そのための仕組みをまとめます。

## 機種によって変わるもの

| 項目 | 何で決まるか | どこで設定するか |
| ---- | ---- | ---- |
| 筐体 (カメラの位置・向き) | 機種ごとのカメラの位置 | `hardware/cad/main.FCStd` を作り直す |
| 使うカメラ | 機種 | 端末プロファイル `camera.id` (書かなければ自動) |
| 色の並び (Bayer 配列) | イメージセンサ | 端末プロファイル `camera.cfa` (書かなければカメラの報告を使う) |
| 0次光とスペクトルが写る位置 | 筐体・カメラの焦点距離・画素ピッチ・回折格子 | 端末プロファイル `spectrum` / `calibration` |
| 撮影画面のプレビューの見せ方 | 筐体・画面の大きさ | 端末プロファイル `preview` (Android のみ) |
| 感度データ (波長ごとの感度) | イメージセンサとレンズ | 機種ごとに測り直す |
| 露出時間・ISO の範囲 | カメラ | 自動 (カメラが受け付ける範囲に収める) |

ソフトウェア側で機種に依存する値は、すべて **端末プロファイル** (`profiles/device_profiles.json`) にまとめてあります。書かれていない項目は Galaxy S22 で使ってきた値になるので、未登録の機種でも今まで通り動きます。

## 他の Android 機種で使う

### 必要な条件

- Camera2 API で **RAW (RAW_SENSOR)** と **マニュアル露出 (MANUAL_SENSOR)** が使えること
  - アプリの「device setup」→「export device report」で書き出される JSON の `cameras[].raw` / `manual_sensor` が `true` のカメラがあれば OK
- Android 10 (API 29) 以上
- 筐体をその機種のカメラ位置に合わせて作り直すこと

### 手順

1. **端末情報を書き出す**: アプリの「device setup」→「export device report」。
   `Documents/FUKASIS-app/device/<機種名>_report.json` に、カメラごとの RAW の大きさ・色の並び・ISO/露出の範囲・焦点距離・画素ピッチなどが書き出されます。
2. **筐体を作る**: report の `focal_length_mm` と `pixel_pitch_um`、回折格子の格子数から、スペクトルが画面に収まる角度を決めます。0次光が **画像の右側** (右から 40% 以内) に写り、スペクトルがその左に伸びる向きに取り付けてください (アプリはこの向きを前提にしています)。
3. **蛍光灯を撮影する**: 三波長型蛍光灯を「capture」で撮影します (Sequence Name は例えば `fluorescent_setup`)。
4. **設定を推定する**: 「device setup」で手順 3 の Sequence Name を入れて「estimate from fluorescent image」。
   0次光の位置、分散 (nm/pixel)、400–700nm が写る範囲、帯の位置を推定し、この機種用のプロファイルの下書きを表示します。
5. **保存する**: 「use this as the profile of this device」。この端末のプロファイルとして使われるようになり、同じ内容が `Documents/FUKASIS-app/device/<機種名>_profile.json` にも書き出されます。
6. **確かめる**: いつも通り calibration → csv でスペクトルを出し、既知のスペクトル (太陽の Fraunhofer 線など) と比べます。
7. **感度データを測る**: センサの感度は機種ごとに違うので、Galaxy S22 用の感度データは使えません。分光感度の分かっている光源 (ハロゲンランプと黒体輻射の近似など) で測り直してください。
8. **共有する**: 手順 5 の JSON を `profiles/device_profiles.json` の `profiles` に追加して Pull Request を送ってください。`"verified": true` は実際に天体を観測して確かめてから付けます。

アプリを作り直さなくても、別の端末で作ったプロファイルの JSON を「import profile json」で読み込めます。`device_profiles.json` そのものを選んだ場合は、機種名が一致するものが使われます。

## iPhone で使う

`ios/` に SwiftUI で書いた iPhone 版があります。Android 版と同じ流れ (Capture → Dark → Calibration → CSV → グラフ、Device setup) で使えます。

> **注意**: iPhone 版は CI でビルドが通ることまでしか確かめていません。実機での撮影・観測はまだ試していません。

### Android 版との違い

- **1枚の露出時間の上限が短い**: iPhone ではカメラの形式ごとの上限 (`activeFormat.maxExposureDuration`。機種によりますが 1 秒程度まで) を超える露出はできません。暗い天体は枚数を増やして積算してください。撮影画面の上に、そのカメラで使える範囲が出ます。
- **RAW**: ProRAW ではなく Bayer RAW (DNG) を使います。色の並びは RAW の形式から自動で分かります。
- **ファイルの場所**: 「ファイル」アプリの「このiPhone内」→「FUKASIS」→「FUKASIS-app」。中の構成は Android 版の `Documents/FUKASIS-app/` と同じです。
- **感度データ**: CSV 画面の「感度データを追加…」で選ぶと、アプリの中にコピーされて次回からも選べます。
- **使うカメラ**: 端末プロファイルの `camera.id` に `wide` (既定) / `ultrawide` / `telephoto` を書きます。

### ビルドして iPhone に入れる

Mac と Xcode (無料の Apple ID で可) が必要です。

```bash
brew install xcodegen
```

```bash
cd ios && xcodegen generate && open Fukasis.xcodeproj
```

Xcode の「Signing & Capabilities」で自分の Team を選び、iPhone をつないで実行します。無料の Apple ID の場合、アプリは 7 日ごとに入れ直しが必要です。

## 仕組み

```
core/                 Android と iPhone で共有する処理
  Sources/FukasisCoreC/   スペクトル計算・RAW の積算・TIFF (C++. Android は JNI から, iPhone は Swift から呼ぶ)
  Sources/FukasisCore/    その Swift ラッパ, 自動校正 (SpectrumCalibrator.java と同じアルゴリズム), 端末プロファイル
  cpptest/                C++ 部分のテスト
  Tests/                  Swift 部分のテスト
profiles/device_profiles.json   端末プロファイル (Android は assets, iPhone はバンドルに入る)
app/                  Android 版
ios/                  iPhone 版 (XcodeGen の project.yml から Xcode プロジェクトを作る)
```

- スペクトルの計算 (`makecsv`) は `core/Sources/FukasisCoreC/spectrum.cpp` の1か所だけにあります。Android 版と iPhone 版で同じ画像から同じスペクトルが出ます。
- 自動校正は Android 版 (`SpectrumCalibrator.java`) と iPhone 版 (`SpectrumCalibrator.swift`) の2つがあり、同じアルゴリズムです。片方を直したらもう片方も直し、テストも両方に書いてください。
- 撮影した画像の `metadata.csv` の最後に `cfa GBRG, device SM-S901Q` のように色の並びと機種が書かれます。スペクトル出力はこの色の並びを使うので、別の端末で撮った画像も正しく処理できます。

## 端末プロファイルの項目

```json
{
  "id": "galaxy-s22",
  "name": "Galaxy S22 (広角カメラ)",
  "platform": "android",
  "models": ["SM-S901", "SC-51C", "SCG13"],
  "verified": true,
  "camera": { "id": "0", "cfa": "GBRG" },
  "spectrum": { "t_min": 1800, "t_max": 2800, "band_width": 80, "band_center": 0.5 },
  "calibration": { "fol_progress": [300, 600], "peak_progress": [1800, 2900], "nm_per_px": [0.2, 0.45] },
  "preview": { "keystone": 3.79668, "stretch": 1.05125, "focus_zoom_center_y": 0.4, "guide_line_y": 2050, "guide_line_y_pointing": 1800 }
}
```

| 項目 | 意味 |
| ---- | ---- |
| `platform` | `android` / `ios` (書かなければどちらでも) |
| `models` | 機種名の先頭。Android は `Build.MODEL` (例 `SM-S901Q`)、iPhone は機種 ID (例 `iPhone15,2`)。一番長く一致したものが使われる |
| `camera.id` | Android: カメラ ID、iPhone: `wide` / `ultrawide` / `telephoto` |
| `camera.cfa` | 色の並び `RGGB` / `GRBG` / `GBRG` / `BGGR` / `MONO` |
| `spectrum.t_min`, `t_max` | スペクトルとして切り出す、0次光からの距離 (pixel) |
| `spectrum.band_width`, `band_center` | 縦に積算する帯の幅 (pixel) と中心 (画像の高さに対する割合) |
| `calibration.fol_progress` | 0次光スライダーの範囲 (値は 画像の幅 − 列) |
| `calibration.peak_progress` | 輝線スライダーの範囲 |
| `calibration.nm_per_px` | 自動校正で許す分散の範囲 |
| `preview.*` | Android の撮影画面のプレビューの台形補正とガイド線の位置 |
| `verified` | 実際に観測して確かめたか |
