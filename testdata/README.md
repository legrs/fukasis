# testdata

`cli/` (Rust) と `web/` (JavaScript) のテストで共通に使うデータです。

- `expected_spectrum_*.csv` は、アプリの C++ (`app/app/src/main/cpp/native-lib.cpp` の `makecsv`) を
  そのまま PC 上で動かして作った正解データです。入力は合成画像 (light − dark) と、このフォルダの
  `calib_*.csv` (`calib_truncated.csv` は 3 次式が途中で折り返す例) / `sensitivity.csv` / `metadata.csv`、0次光の位置は x = 390 です。
- 合成画像は整数演算だけで作るので、どの言語でも同じ値になります。作り方は
  `cli/tests/golden.rs` の `synth` を参照してください。
- `strips_le.tif` / `strips_be.tif` は 8×4 の 32bit float の TIFF です (値は `y * 10 + x + 0.5`)。
  1 行 1 ストリップで、IFD がデータの後ろにある形にしてあります。

## 正解データの作り直し

`makecsv` の処理を変えたときは、次のコマンドで `expected_spectrum_*.csv` を作り直し、`cli/` と `web/` のテストが通るように両方の実装を直します。

```bash
sh testdata/tools/regenerate.sh
```

`tools/harness.cpp` が、JNI と OpenCV を最小限のスタブに置き換えて `makecsv` をそのまま呼び出します。
出力範囲を波長で決める版の `makecsv` (`wavelength_calib.h` を使うもの) が必要です。アプリの C++ が別の場所にある場合は `FUKASIS_CPP_DIR` で指定します。
