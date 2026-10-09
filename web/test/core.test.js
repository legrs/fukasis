// SPDX-License-Identifier: MIT
// 実行: node --test web/test/core.test.js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const core = require('../js/core.js');

const testdata = (name) => fs.readFileSync(path.join(__dirname, '..', '..', 'testdata', name), 'utf8');
const FOL = 390;
const darked = () => core.subtract(core.synthImage(12345, true), core.synthImage(777, false));

// アプリの C++ (makecsv) をそのまま動かして作った正解データと一致することを確かめる
function assertMatchesGolden(calibration, expected) {
  const cal = core.parseCalibration(testdata(calibration));
  const sensitivity = core.parseSensitivity(testdata('sensitivity.csv'));
  const result = core.extract(darked(), cal, sensitivity, FOL);
  const actual = core.toCsv(result, testdata('metadata.csv')).trimEnd().split('\n');
  const want = testdata(expected).trimEnd().split('\n');
  assert.equal(actual.length, want.length, '行数が違う');
  // ヘッダーの 2 行は完全に一致する
  assert.deepEqual(actual.slice(0, 2), want.slice(0, 2));
  for (let i = 2; i < want.length; i++) {
    const a = actual[i].split(',').map(Number);
    const e = want[i].split(',').map(Number);
    for (let k = 0; k < 2; k++) {
      // 有効 6 桁で書かれているので, 最後の桁の丸めの差だけ許す
      assert.ok(Math.abs(a[k] - e[k]) <= 2e-6 * Math.max(Math.abs(e[k]), 1e-6), `${i + 1} 行目: ${a[k]} != ${e[k]}`);
    }
  }
}

test('合成画像が正解データを作ったときと同じ値になる', () => {
  const img = darked();
  assert.equal(img.data.reduce((s, v) => s + v, 0), 6474625);
  assert.equal(img.data[10 * img.width + 20], -1);
  assert.equal(img.data[50 * img.width + 339], 614);
});

test('スペクトル出力: 校正点 4 個', () => assertMatchesGolden('calib_4pt.csv', 'expected_spectrum_4pt.csv'));
test('スペクトル出力: 校正点 6 個', () => assertMatchesGolden('calib_6pt.csv', 'expected_spectrum_6pt.csv'));
test('スペクトル出力: 3 次式が途中で折り返す校正データ', () => {
  const cal = core.parseCalibration(testdata('calib_truncated.csv'));
  const range = core.outputRange(core.fit(cal.t, cal.c), cal.t, FOL);
  assert.ok(range.cutLow && range.cutHigh);
  assertMatchesGolden('calib_truncated.csv', 'expected_spectrum_truncated.csv');
});

test('TIFF: 書いて読み直すと同じ画像になる', () => {
  const img = darked();
  const back = core.decodeTiff(core.encodeTiff(img).buffer);
  assert.equal(back.width, img.width);
  assert.equal(back.height, img.height);
  assert.deepEqual(back.data, img.data);
});

test('TIFF: 1 行 1 ストリップ・IFD が後ろにある形式 (リトル / ビッグエンディアン)', () => {
  for (const name of ['strips_le.tif', 'strips_be.tif']) {
    const bytes = fs.readFileSync(path.join(__dirname, '..', '..', 'testdata', name));
    const img = core.decodeTiff(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    assert.deepEqual([img.width, img.height], [8, 4], name);
    for (let y = 0; y < 4; y++) {
      for (let x = 0; x < 8; x++) {
        assert.equal(img.data[y * 8 + x], y * 10 + x + 0.5, name);
      }
    }
  }
});

test('TIFF: 読めないものはエラーにする', () => {
  assert.throws(() => core.decodeTiff(new TextEncoder().encode('not a tiff').buffer), /TIFF ではありません/);
  assert.throws(() => core.decodeTiff(new ArrayBuffer(0)), /TIFF ではありません/);
  const truncated = core.encodeTiff(darked()).buffer.slice(0, 1000);
  assert.throws(() => core.decodeTiff(truncated), /途中で切れています/);
});

test('数値の表記が C++ の ostream と同じ', () => {
  const cases = [
    [400.32, '400.32'],
    [401.0331234, '401.033'],
    [0.0239052499, '0.0239052'],
    [0.000781028, '0.000781028'],
    [0.0000123456789, '1.23457e-05'],
    [1234567, '1.23457e+06'],
    [999999.5, '1e+06'],
    [1, '1'],
    [0.5, '0.5'],
    [-12.5, '-12.5'],
    [0, '0'],
    [NaN, 'nan'],
  ];
  for (const [value, text] of cases) assert.equal(core.fmtG(value), text);
});

test('nan や inf を含む値の読み取り', () => {
  assert.equal(core.parseValue(' 1.5 '), 1.5);
  assert.equal(core.parseValue('-2e-3'), -0.002);
  assert.ok(Number.isNaN(core.parseValue('nan')));
  assert.ok(Number.isNaN(core.parseValue('-nan')));
  assert.equal(core.parseValue('inf'), Infinity);
  assert.equal(core.parseValue('-inf'), -Infinity);
  assert.equal(core.parseValue('wavelength/nm'), null);
  assert.equal(core.parseValue('testseq'), null);
  assert.equal(core.parseValue(''), null);
});

// README の calibration 画面の例に相当する値
const T_REF = [1632, 1986, 2126, 2210];
const C_REF = [435.8, 546.1, 588.0, 611.6];
const SIZE = 3590;

test('校正: 4 点なら 4 点を通る 3 次式 (Lagrange 補間と同じ)', () => {
  const lagrange = (t) => {
    let w = 0;
    for (let j = 0; j < 4; j++) {
      let nume = 1;
      let deno = 1;
      for (let k = 0; k < 4; k++) {
        if (k !== j) {
          nume *= t - T_REF[k];
          deno *= T_REF[j] - T_REF[k];
        }
      }
      w += (C_REF[j] * nume) / deno;
    }
    return w;
  };
  const f = core.fit(T_REF, C_REF);
  assert.ok(f.ok);
  assert.equal(f.degree, 3);
  for (let t = 1; t < SIZE; t++) assert.ok(Math.abs(core.polyAt(f, t) - lagrange(t)) < 1e-6);
});

test('校正: 出力範囲は波長で決まり, 逆行しない', () => {
  const f = core.fit(T_REF, C_REF);
  const r = core.outputRange(f, T_REF, SIZE);
  assert.deepEqual(r, { lo: 1511, hi: 2639, cutLow: false, cutHigh: false });
  for (let t = r.lo + 1; t <= r.hi; t++) assert.ok(core.polyAt(f, t - 1) < core.polyAt(f, t));
});

test('校正: 同じ位置の点があれば次数を下げる / 当てはめられなければ出力なし', () => {
  const f = core.fit([1632, 1986, 1986, 2210], [435.8, 546.1, 546.1, 611.6]);
  assert.ok(f.ok);
  assert.equal(f.degree, 2);
  assert.ok(Math.abs(core.polyAt(f, 1986) - 546.1) < 1e-6);
  assert.equal(core.fit([2000, 2000, 2000, 2000], [500, 500, 500, 500]).ok, false);
  const t = [1900, 2100, 2300, 2500];
  assert.equal(core.outputRange(core.fit(t, [1430, 1490, 1550, 1610]), t, SIZE), null);
});

test('校正: 6 点は 3 次の最小二乗', () => {
  const t = [1525, 1632, 1986, 2126, 2210, 2281];
  const c = [404.7, 435.8, 546.1, 588.0, 611.6, 631.1];
  const f = core.fit(t, c);
  assert.equal(f.degree, 3);
  t.forEach((ti, i) => assert.ok(Math.abs(core.polyAt(f, ti) - c[i]) < 0.5));
  const r = core.outputRange(f, t, SIZE);
  assert.deepEqual([r.lo, r.hi], [1509, 2615]);
});

test('校正データの読み書き', () => {
  const text = '51,189,241,271\n435.800000,546.100000,588.000000,611.600000';
  const cal = core.parseCalibration(text);
  assert.deepEqual(cal.t, [51, 189, 241, 271]);
  assert.equal(core.formatCalibration(cal), text);
  assert.throws(() => core.parseCalibration('1,2,3\n1,2'));
  assert.throws(() => core.parseCalibration(''));
});

test('感度データ: ヘッダー 2 行を読み飛ばす', () => {
  const s = core.parseSensitivity('title\nwavelength,b,g,r\n400,1,2,3\n\n410,2,3,4.5\n');
  assert.deepEqual(s.wavelength, [400, 410]);
  assert.deepEqual(s.channel[3], [6, 9.5]);
  assert.throws(() => core.parseSensitivity('a\nb\n400,1,2\n'));
  assert.throws(() => core.parseSensitivity('a\nb\n'));
});

test('スペクトルの csv の読み取り: ヘッダーは読み飛ばす', () => {
  const { x, y } = core.parseCsv(
    'seq, 2026-10-08T00:00:00Z, ISO 800\nwavelength/nm,relative intensity(0.0 -- 1.0)\n400.5,0.25\n401,nan\n'
  );
  assert.deepEqual(x, [400.5, 401]);
  assert.equal(y[0], 0.25);
  assert.ok(Number.isNaN(y[1]));
});

const N = 200;
const wavelengths = () => Array.from({ length: N }, (_, i) => 400 + 0.3 * i);
// なだらかな連続光に輝線を 2 本載せたもの
const intensities = () => {
  const y = Array.from({ length: N }, (_, i) => 0.1 + 0.05 * Math.sin(i / 20));
  [y[50], y[51], y[52]] = [0.6, 1.0, 0.6];
  [y[120], y[121], y[122]] = [0.3, 0.9, 0.3];
  return y;
};
const ascending = (v) => v.every((x, i) => i === 0 || v[i - 1] <= x);

test('異常値の除外: 輝線は残る', () => {
  const r = core.filter(wavelengths(), intensities(), true);
  assert.equal(r.excluded, 0);
  assert.equal(Math.max(...r.intensity), 1);
});

test('異常値の除外: スパイク・負の値・NaN', () => {
  const y = intensities();
  y[80] = 50; // 孤立したスパイク
  y[150] = 30; // 2 点続くスパイク
  y[151] = 28;
  y[10] = -3; // 負の強度
  y[20] = NaN;
  const on = core.filter(wavelengths(), y, true);
  assert.equal(on.excluded, 5);
  assert.equal(Math.max(...on.intensity), 1);
  // 切替をオフにしても NaN だけは描けないので外す
  const off = core.filter(wavelengths(), y, false);
  assert.equal(off.excluded, 1);
  assert.equal(Math.max(...off.intensity), 50);
});

test('異常値の除外: 波長が折り返している部分', () => {
  const fold = 30;
  const x = wavelengths();
  const y = intensities();
  for (let i = 0; i < fold; i++) {
    x.push(x[N - 1] - 0.3 * (i + 1));
    y.push(0.1);
  }
  const on = core.filter(x, y, true);
  assert.equal(on.excluded, fold);
  assert.ok(ascending(on.wavelength));
  const off = core.filter(x, y, false);
  assert.equal(off.excluded, 0);
  assert.ok(ascending(off.wavelength));
});

test('画像: 帯・0次光・輝線の候補', () => {
  assert.deepEqual(core.bandRows(3060), [1490, 1570]);
  assert.deepEqual(core.bandRows(96), [8, 88]);
  assert.deepEqual(core.bandRows(40), [0, 40]);
  assert.deepEqual(Array.from(core.smoothProfile([0, 4, 0, 4, 0])), [1, 2, 2, 2, 1]);
  const profile = core.smoothProfile(core.bandProfile(core.synthImage(12345, true)));
  assert.equal(core.guessZerothOrder(profile), FOL);
  // 合成画像の輝線は 0次光から 51, 189, 241, 271 px. 明るい順に 4 つ取るとちょうどその 4 本になる
  const peaks = core.findPeaks(profile, 0, FOL - 40, 4).map(([x]) => FOL - x).sort((a, b) => a - b);
  assert.deepEqual(peaks, [51, 189, 241, 271]);
});

test('目盛りはきりのよい数になる', () => {
  assert.deepEqual(core.niceTicks(400, 700).ticks, [400, 450, 500, 550, 600, 650, 700]);
  const y = core.niceTicks(0, 1);
  assert.deepEqual(y.ticks.map((t) => core.tickLabel(t, y.step)), ['0.0', '0.2', '0.4', '0.6', '0.8', '1.0']);
});
