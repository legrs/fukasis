#!/bin/sh
# SPDX-License-Identifier: MIT
# testdata/expected_spectrum_*.csv を, アプリの C++ (makecsv) から作り直す.
# makecsv の処理を変えたときに実行し, cli/ と web/ のテストが通るように両方の実装を直す.
#
#   sh testdata/tools/regenerate.sh
#
# C++17 のコンパイラ (c++) と python3 が必要. FUKASIS_CPP_DIR でアプリの C++ の場所を変えられる.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
data=$(cd "$here/.." && pwd)
cpp=${FUKASIS_CPP_DIR:-$data/../app/app/src/main/cpp}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ ! -f "$cpp/wavelength_calib.h" ]; then
    echo "$cpp に wavelength_calib.h がありません (出力範囲を波長で決める版の makecsv が必要です)" >&2
    exit 1
fi

# makecsv の関数だけを native-lib.cpp から抜き出す (末尾の extern "C" の閉じ括弧は除く)
python3 - "$cpp/native-lib.cpp" "$work/makecsv_body.inc" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index('    JNIEXPORT jstring JNICALL\n    Java_com_example_ssa_CsvActivity_makecsv(')
open(sys.argv[2], 'w', encoding='utf-8').write(src[start:src.rindex('}')])
PY

c++ -std=c++17 -w -I "$cpp" -I "$work" "$here/harness.cpp" -o "$work/harness"

for name in 4pt 6pt truncated; do
    # makecsv は途中経過を標準出力に書くので捨てる
    "$work/harness" "$data/calib_$name.csv" "$data/metadata.csv" "$data/sensitivity.csv" \
        "$data/expected_spectrum_$name.csv" 390 > /dev/null
    echo "expected_spectrum_$name.csv: $(wc -l < "$data/expected_spectrum_$name.csv") lines"
done
