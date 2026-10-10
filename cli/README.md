# fukasis (コマンドライン版)

FUKASIS-app で撮影したデータを PC で処理するコマンドラインツールです。
アプリの **dark / calibration / csv / view** の 4 画面に相当するコマンドがあります。
まとめて処理したいときや、スクリプトから呼びたいときに使います。画像を見ながら作業するなら [web 版](https://igarinpiano.github.io/fukasis/) が向いています。

計算はアプリ (共通コア `core/`) と同じ結果になるようにしてあります。

## インストール

次のどれかで入れられます。入れると `fukasis` コマンドが使えるようになります。

**npm** ([Node.js](https://nodejs.org/) が入っている場合。ビルド済みの実行ファイルが入ります)

```bash
npm install -g fukasis
```

**cargo** ([Rust](https://www.rust-lang.org/ja/tools/install) が入っている場合。手元でビルドされます)

```bash
cargo install fukasis
```

**実行ファイルをそのまま使う**

[GitHub Releases](https://github.com/igarinpiano/fukasis/releases) の `pc-v<バージョン>` から、自分の機種の `fukasis-<バージョン>-<ターゲット>` を取って展開します。

| 機種 | ターゲット |
|---|---|
| Windows (64 bit) | `x86_64-pc-windows-msvc` |
| Windows (ARM) | `aarch64-pc-windows-msvc` |
| macOS (Apple シリコン) | `aarch64-apple-darwin` |
| macOS (Intel) | `x86_64-apple-darwin` |
| Linux (64 bit) | `x86_64-unknown-linux-gnu` (古い環境や Alpine では `-musl`) |
| Linux (ARM 64 bit, Raspberry Pi など) | `aarch64-unknown-linux-gnu` |

ほかに 32 bit の Windows / Linux、ARMv5〜v7、RISC-V、PowerPC、s390x、LoongArch、Android (Termux)、FreeBSD、NetBSD、illumos、WebAssembly (WASI) 向けもあります。
npm で入るのはこのうち 28 機種で、それ以外は GitHub Releases から取ってください。

## ソースからビルド

[Rust](https://www.rust-lang.org/ja/tools/install) が必要です。外部クレートには依存していません。

```bash
cd cli
cargo build --release
```

`cli/target/release/fukasis` (Windows では `fukasis.exe`) ができます。
`cargo install --path cli` (リポジトリの直下で実行) とすると、`fukasis` コマンドとしてインストールされます。

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
- `--sensitivity` の代わりに `--no-sensitivity` を付けると、感度校正をせずに出力します (感度データは要りません)。あとで区別できるよう、1 行目の末尾に `, sensitivity none` が付きます。
- `-o` を省くと標準出力に書きます。
- カラーフィルタ配列は、metadata に記録されていればそれを使います (無ければ GBRG)。`--cfa RGGB` のように指定もできます。
- スペクトルを読む帯は、既定では画像の中央の幅 80 px です。機種によって違う場合は `--band-width <px>` と `--band-center <0〜1>` で変えられます。
- 出力する範囲は校正データから波長 (400〜700 nm) で決まります。出力できる点が無いときは、理由を表示して終了します。

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

[GitHub Releases](https://github.com/igarinpiano/fukasis/releases)・[crates.io](https://crates.io/crates/fukasis)・[npm](https://www.npmjs.com/package/fukasis) で配布しています。
新しいバージョンを出す手順は [docs/releasing.md](https://github.com/igarinpiano/fukasis/blob/release/docs/releasing.md) にあります。
