#!/bin/sh
# SPDX-License-Identifier: MIT
# testdata/expected_spectrum_*.csv を, アプリのスペクトル出力 (共通コアの fk::makeSpectrum) から作り直す.
# コアの処理を変えたときに実行し, cli/ と web/ のテストが通るように両方の実装を直す.
#
#   sh testdata/tools/regenerate.sh
#
# C++17 のコンパイラ (c++) が必要.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
data=$(cd "$here/.." && pwd)
core=$data/../core/Sources/FukasisCoreC
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

c++ -std=c++17 -O1 -Wall -Wextra -I "$core" "$here/harness.cpp" "$core/spectrum.cpp" -o "$work/harness"

# 名前, 校正データ, metadata
for case in "4pt calib_4pt metadata" "6pt calib_6pt metadata" "truncated calib_truncated metadata" "rggb calib_4pt metadata_rggb"; do
    set -- $case
    "$work/harness" "$data/$2.csv" "$data/$3.csv" "$data/sensitivity.csv" "$data/expected_spectrum_$1.csv" 390
    echo "expected_spectrum_$1.csv: $(wc -l < "$data/expected_spectrum_$1.csv") lines"
done
