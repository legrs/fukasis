# fukasis (コマンドライン版)

FUKASIS-app で撮影したデータを PC で処理するコマンドラインツールです。
アプリの **dark / calibration / csv / view** の 4 画面に相当するコマンドがあります。
まとめて処理したいときや、スクリプトから呼びたいときに使います。画像を見ながら作業するなら [web 版](../web/README.md) が向いています。

計算はアプリ (`app/app/src/main/cpp`) と同じ結果になるようにしてあります。

## ビルド

[Rust](https://www.rust-lang.org/ja/tools/install) が必要です。外部クレートには依存していません。

```bash
cd cli
cargo build --release
```

`cli/target/release/fukasis` (Windows では `fukasis.exe`) ができます。
`cargo install --path cli` (リポジトリの直下で実行) とすると、`fukasis` コマンドとしてインストールされます。
GitHub Actions の「PC tools」が動いているリポジトリでは、その実行結果からビルド済みの実行ファイル (Linux / macOS / Windows) も取得できます。

## 使い方

スマートフォンの `Internal_Storage/Documents/FUKASIS-app/` を PC にコピーして使います。

### dark: ダーク減算

```bash
fukasis dark imgs/alphaOri_260314_1/stacked.tif imgs/dark_20260314/stacked.tif
```

ライトフレームと同じフォルダに `darked.tif` を書きます。`-o` で出力先を変えられます。

### info: 画像の情報

```bash
fukasis info imgs/fluorescent_260314_1/stacked.tif
```

画像の大きさ、0次光の位置の推定、輝線の候補 (明るい順) を表示します。
次の `calib` に渡す位置を調べるのに使えます。どちらも推定なので、おかしければ web 版で画像を見て確かめてください。

### calib: 波長校正データを作る

```bash
fukasis calib --fol 3590 --line 1958=435.8 --line 1604=546.1 --line 1464=588.0 --line 1380=611.6 -o calibdata/fluorescent.csv
```

`--fol` は 0次光の位置、`--line` は「輝線の位置 = 波長 (nm)」です。位置はどちらも画像上の x 座標 (px) です。
校正点は 4 本以上を推奨します。4 本ならその 4 点を通る 3 次式、5 本以上なら 3 次の最小二乗で当てはめます。
当てはめとの差と、出力される波長の範囲が表示されます。`-o` を省くと校正データを標準出力に書きます。

既存の校正データを確かめるだけなら:

```bash
fukasis calib --check calibdata/fluorescent.csv --fol 3590
```

### csv: スペクトルを出力

```bash
fukasis csv --image imgs/alphaOri_260314_1/darked.tif \
            --calib calibdata/fluorescent.csv \
            --sensitivity sensit_distr.csv \
            --metadata imgs/alphaOri_260314_1/metadata.csv \
            -o spectrum/alphaOri_260314_1.csv
```

- `--fol` を省くと 0次光の位置を自動で推定します (推定した値は表示されます)。ずれている場合は `--fol <x>` で指定してください。
- `--metadata` は任意です。省くと 1 行目には画像のファイル名が入ります。
- `-o` を省くと標準出力に書きます。

### graph: グラフを SVG に出力

```bash
fukasis graph spectrum/alphaOri_260314_1.csv spectrum/betaOri_260314_1.csv -o compare.svg
```

8 本まで重ねて描けます。既定では明らかな異常値 (波長が折り返している部分・負の強度・孤立したスパイク) を除いて描きます。
`--keep-outliers` を付けると除かずに描きます。`--title` で見出しを変えられます。

## 対応している画像

TIFF は、アプリが書き出す形式 (無圧縮・1 チャンネル・ストリップ形式。32 bit float のほか 8 / 16 / 32 bit 整数、64 bit float) に対応しています。
圧縮された TIFF やタイル形式には対応していません。

## テスト

```bash
cargo test
```

アプリの C++ をそのまま動かして作った正解データ (`testdata/`) と出力が一致することを確かめています。

## リリース

GitHub Releases・crates.io (`fukasis`)・npm (`fukasis`) で配布できる形にしてあります。手順は [docs/releasing.md](../docs/releasing.md) にあります。
公開後は、次のどれでも入れられます。

```bash
cargo install fukasis
```

```bash
npm install -g fukasis
```
