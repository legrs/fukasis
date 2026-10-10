# testdata

`cli/` (Rust) と `web/` (JavaScript) のテストで共通に使うデータです。

- `expected_spectrum_*.csv` は、アプリのスペクトル出力 (共通コア `core/Sources/FukasisCoreC/spectrum.cpp` の `fk::makeSpectrum`) を
  そのまま PC 上で動かして作った正解データです。入力は合成画像 (light − dark) と、このフォルダの
  `calib_*.csv` (`calib_truncated.csv` は 3 次式が途中で折り返す例) / `sensitivity.csv` / `metadata.csv`、0次光の位置は x = 390 です。
  `expected_spectrum_rggb.csv` は `metadata_rggb.csv` (カラーフィルタ配列が RGGB と記録された例) を使ったものです。
- 合成画像は整数演算だけで作るので、どの言語でも同じ値になります。作り方は
  `cli/tests/golden.rs` の `synth` を参照してください。
- `strips_le.tif` / `strips_be.tif` は 8×4 の 32bit float の TIFF です (値は `y * 10 + x + 0.5`)。
  1 行 1 ストリップで、IFD がデータの後ろにある形にしてあります。

## 正解データの作り直し

コアの処理を変えたときは、次のコマンドで `expected_spectrum_*.csv` を作り直し、`cli/` と `web/` のテストが通るように両方の実装を直します。

```bash
sh testdata/tools/regenerate.sh
```

`tools/harness.cpp` が `fk::makeSpectrum` をそのまま呼び出します (コアは OpenCV にも OS にも依存しないので、C++17 のコンパイラだけで動きます)。
