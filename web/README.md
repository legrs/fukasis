# FUKASIS for PC (web 版)

FUKASIS-app で撮影したデータを、PC のブラウザで処理するためのページです。
アプリの **dark / calibration / csv / view** の 4 画面に相当する機能があります (撮影はできません)。

- インストールもビルドも不要です。`web/index.html` をブラウザで開くだけで動きます。
- 選んだファイルはブラウザの中だけで処理され、どこにも送信されません。
- 計算はアプリ (`app/app/src/main/cpp`) と同じ結果になるようにしてあります。

右上の **サンプルデータで試す** を押すと、合成した小さな画像が各タブに入り、手元にデータが無くても一通り試せます。

## 使うファイル

スマートフォンの `Internal_Storage/Documents/FUKASIS-app/` を PC にコピーして使います。

| ファイル | 場所 | 使うタブ |
|---|---|---|
| `stacked.tif` | `imgs/<観測の名前>/` | Dark, Calibration, CSV |
| `stacked.jpg` | `imgs/<観測の名前>/` | Calibration (位置合わせだけならこちらでも可) |
| `metadata.csv` | `imgs/<観測の名前>/` | CSV (任意。出力の 1 行目に入る) |
| 波長校正データ | `csv/calibdata/` | CSV |
| `sensit_distr.csv` | Releases の `release.zip` | CSV (一度選ぶとブラウザが覚えます) |
| スペクトル | `csv/spectrum/` | Graph |

## タブ

### Dark

ライトフレームとダークフレームの `stacked.tif` を選んで **ダーク減算** を押します。
結果は `darked.tif` として保存するか、そのまま CSV タブに渡せます。

### Calibration

蛍光灯などを撮った画像を開き、0次光と輝線の位置を合わせて波長校正データを作ります。

- 上が画像 (中央の帯のあたり)、下がその帯を縦に平均したプロファイルです。細い横線 2 本の間が、スペクトルとして読まれる帯 (幅 80 px) です。
- 表の行を選んでから画像をクリックするか、線をドラッグして位置を合わせます。数値を直接入力することもできます。
- **近くの山へ** は、線をいちばん近い山の頂上 (±15 px 以内) に寄せます。プロファイルの上の小さな印は輝線の候補です。
- 輝線は何本でも追加できます。4 本ならその 4 点を通る 3 次式、5 本以上なら 3 次の最小二乗で当てはめます。
- 右側に、出力される波長の範囲と、当てはめとの差が出ます。プロファイルの下には校正から求めた波長の目盛りが出るので、輝線と合っているかを目で確かめられます。
- 保存した校正データはアプリと同じ形式です (アプリの csv 画面でもそのまま使えます)。

### CSV

画像 (`darked.tif`、無ければ `stacked.tif`)、波長校正データ、感度データを選び、0次光の位置を合わせて **スペクトルを出力** を押します。
0次光の位置は自動で推定されますが、ずれていたら線をドラッグするか数値で直してください。

出力される csv はアプリと同じ形式です (1 行目が観測の情報、2 行目がラベル、以降が波長と相対強度)。

### Graph

スペクトルの csv を開いてグラフにします。8 本まで重ねて比べられます。

- 横にドラッグするとその範囲を拡大し、ダブルクリックで元に戻ります。
- ポインタを載せると、その波長での各スペクトルの値が出ます (グラフを選んで左右キーでも動かせます)。
- **異常値を除外** は、波長が折り返している部分・負の強度・孤立したスパイクを表示から外します (csv は書き換えません)。
- **主な輝線・吸収線を表示** は、Hα や Na D などの位置に目印を出します。
- **PNG で保存**、**表で見る** もあります。

## 対応している画像

- TIFF は、アプリが書き出す形式 (無圧縮・1 チャンネル・ストリップ形式。32 bit float のほか 8 / 16 / 32 bit 整数、64 bit float) に対応しています。圧縮された TIFF やタイル形式には対応していません。
- jpg / png は Calibration タブでの位置合わせにだけ使えます (値が 8 bit に丸められているので、スペクトルの出力には使えません)。

## サーバーを立てて開く

`index.html` を直接開く代わりに、この PC の中だけで配信して開くこともできます ([Node.js](https://nodejs.org/) 18 以上)。
`127.0.0.1` だけで待ち受けるので、ほかの PC からはつながりません。

```bash
node web/bin/fukasis-web.js --open
```

`--port <番号>` でポートを指定できます (既定は 8377。使われていれば空いているポートを選びます)。

## Node.js から使う

`js/core.js` は Node.js からも読み込めます。校正データの当てはめやスペクトルの出力を、自分のスクリプトから呼べます。

```js
const core = require('./web/js/core.js');
const fs = require('node:fs');

// decodeTiff には ArrayBuffer を渡す
const bytes = fs.readFileSync('darked.tif');
const image = core.decodeTiff(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
const calibration = core.parseCalibration(fs.readFileSync('calib.csv', 'utf8'));
const sensitivity = core.parseSensitivity(fs.readFileSync('sensit_distr.csv', 'utf8'));
// 0次光の位置 (px). 分かっていればその値を渡す
const fol = core.guessZerothOrder(core.bandProfile(image));
const spectrum = core.extract(image, calibration, sensitivity, fol);
fs.writeFileSync('spectrum.csv', core.toCsv(spectrum, ''));
```

## 開発

依存パッケージはありません。`js/core.js` が計算の本体で、ブラウザと Node.js の両方で動きます。

```bash
node --test web/test/core.test.js web/test/server.test.js
```

リリースでは web 版一式を zip にして GitHub Releases に置きます。手順は [docs/releasing.md](../docs/releasing.md) にあります。

テストでは、アプリの C++ をそのまま動かして作った正解データ (`testdata/`) と出力が一致することを確かめています。
