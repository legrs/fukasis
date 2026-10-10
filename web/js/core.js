// SPDX-License-Identifier: MIT
// FUKASIS-app の dark / calibration / csv / view 画面に相当する処理.
// 計算はアプリ (共通コア core/) や cli/ (Rust) と同じ結果になるようにしてある.
// ブラウザでは window.FukasisCore, Node では require() で使える.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.FukasisCore = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ---------------------------------------------------------------- 数値の表記

  // C++ の `ostream << double` (有効 6 桁, printf の %g) と同じ表記にする
  function fmtG(x) {
    if (Number.isNaN(x)) return 'nan';
    if (!Number.isFinite(x)) return x > 0 ? 'inf' : '-inf';
    if (x === 0) return '0';
    const [mantissa, expText] = x.toExponential(5).split('e');
    const exp = Number(expText);
    if (exp < -4 || exp >= 6) {
      return trimZeros(mantissa) + 'e' + (exp < 0 ? '-' : '+') + String(Math.abs(exp)).padStart(2, '0');
    }
    return trimZeros(x.toFixed(Math.max(5 - exp, 0)));
  }

  function trimZeros(s) {
    return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s;
  }

  // "nan" や "inf" も読める数値の読み取り. 数値でなければ null
  function parseValue(s) {
    const v = s.trim().toLowerCase();
    const unsigned = v.startsWith('-') || v.startsWith('+') ? v.slice(1) : v;
    if (unsigned === 'nan') return NaN;
    if (unsigned === 'inf' || unsigned === 'infinity') return v.startsWith('-') ? -Infinity : Infinity;
    // 数字で始まらないもの (ヘッダーなど) は数値として扱わない
    if (!/^[0-9.]/.test(unsigned)) return null;
    const n = Number(v);
    return Number.isNaN(n) ? null : n;
  }

  // ---------------------------------------------------------------- TIFF
  // 対応するのは無圧縮・1 チャンネル・ストリップ形式だけ (OpenCV が 32bit float を書き出すときの形式)

  function decodeTiff(buffer) {
    const view = new DataView(buffer);
    const fail = (message) => {
      throw new Error(message);
    };
    if (view.byteLength < 8) fail('TIFF ではありません');
    const order = String.fromCharCode(view.getUint8(0), view.getUint8(1));
    if (order !== 'II' && order !== 'MM') fail('TIFF ではありません');
    const little = order === 'II';
    const magic = view.getUint16(2, little);
    if (magic === 43) fail('BigTIFF には対応していません');
    if (magic !== 42) fail('TIFF ではありません');
    const u16 = (o) => view.getUint16(o, little);
    const u32 = (o) => view.getUint32(o, little);

    // IFD の 1 項目 (12 バイト) の値を整数の列として読む
    const entryValues = (entry) => {
      const kind = u16(entry + 2);
      const count = u32(entry + 4);
      const size = { 1: 1, 6: 1, 3: 2, 8: 2, 4: 4, 9: 4 }[kind];
      if (!size) fail('TIFF のタグの型 ' + kind + ' には対応していません');
      // 4 バイトに収まる値は項目の中に直接入っている
      const start = size * count <= 4 ? entry + 8 : u32(entry + 8);
      const values = [];
      for (let i = 0; i < count; i++) {
        const at = start + i * size;
        values.push(size === 1 ? view.getUint8(at) : size === 2 ? u16(at) : u32(at));
      }
      return values;
    };

    try {
      const ifd = u32(4);
      const entries = u16(ifd);
      let width = 0;
      let height = 0;
      let bits = 1;
      let compression = 1;
      let samples = 1;
      let format = 1;
      let rowsPerStrip = Infinity;
      let offsets = [];
      for (let i = 0; i < entries; i++) {
        const entry = ifd + 2 + i * 12;
        const tag = u16(entry);
        // 使わないタグは型を問わず読み飛ばす
        if (![256, 257, 258, 259, 273, 277, 278, 322, 339].includes(tag)) continue;
        const values = entryValues(entry);
        const first = values.length ? values[0] : 0;
        if (tag === 256) width = first;
        else if (tag === 257) height = first;
        else if (tag === 258) bits = first;
        else if (tag === 259) compression = first;
        else if (tag === 273) offsets = values;
        else if (tag === 277) samples = first;
        else if (tag === 278) rowsPerStrip = first;
        else if (tag === 322) fail('タイル形式の TIFF には対応していません');
        else if (tag === 339) format = first;
      }
      if (!width || !height) fail('TIFF に画像の大きさが書かれていません');
      if (compression !== 1) {
        fail('圧縮された TIFF (compression=' + compression + ') には対応していません。無圧縮で保存し直してください');
      }
      if (samples !== 1) fail(samples + ' チャンネルの TIFF には対応していません (1 チャンネルのみ)');
      const readers = {
        '8,1': (o) => view.getUint8(o),
        '16,1': (o) => view.getUint16(o, little),
        '16,2': (o) => view.getInt16(o, little),
        '32,1': (o) => view.getUint32(o, little),
        '32,3': (o) => view.getFloat32(o, little),
        '64,3': (o) => view.getFloat64(o, little),
      };
      const read = readers[bits + ',' + format];
      if (!read) fail(bits + ' bit (sample format ' + format + ') の TIFF には対応していません');
      const bytesPerSample = bits / 8;

      rowsPerStrip = Math.max(1, Math.min(rowsPerStrip, height));
      const strips = Math.ceil(height / rowsPerStrip);
      if (offsets.length < strips) fail('TIFF のストリップの数が足りません');
      const data = new Float32Array(width * height);
      let p = 0;
      for (let s = 0; s < strips; s++) {
        const rows = Math.min(rowsPerStrip, height - s * rowsPerStrip);
        let o = offsets[s];
        for (let i = 0; i < rows * width; i++, o += bytesPerSample) {
          data[p++] = read(o);
        }
      }
      return { width, height, data };
    } catch (e) {
      // DataView は範囲外を読むと RangeError を投げる
      if (e instanceof RangeError) fail('TIFF が途中で切れています');
      throw e;
    }
  }

  // 32bit float・無圧縮・1 ストリップの TIFF として書き出す (リトルエンディアン)
  function encodeTiff(img) {
    const dataLen = img.data.length * 4;
    const ifdOffset = 8 + dataLen + (dataLen % 2);
    const SHORT = 3;
    const LONG = 4;
    // タグは番号の昇順に並べる決まり
    const entries = [
      [256, LONG, img.width], // ImageWidth
      [257, LONG, img.height], // ImageLength
      [258, SHORT, 32], // BitsPerSample
      [259, SHORT, 1], // Compression: なし
      [262, SHORT, 1], // PhotometricInterpretation: BlackIsZero
      [273, LONG, 8], // StripOffsets
      [277, SHORT, 1], // SamplesPerPixel
      [278, LONG, img.height], // RowsPerStrip
      [279, LONG, dataLen], // StripByteCounts
      [339, SHORT, 3], // SampleFormat: IEEE float
    ];
    const out = new Uint8Array(ifdOffset + 2 + entries.length * 12 + 4);
    const view = new DataView(out.buffer);
    out[0] = out[1] = 0x49; // "II"
    view.setUint16(2, 42, true);
    view.setUint32(4, ifdOffset, true);
    for (let i = 0; i < img.data.length; i++) {
      view.setFloat32(8 + i * 4, img.data[i], true);
    }
    view.setUint16(ifdOffset, entries.length, true);
    entries.forEach(([tag, kind, value], i) => {
      const o = ifdOffset + 2 + i * 12;
      view.setUint16(o, tag, true);
      view.setUint16(o + 2, kind, true);
      view.setUint32(o + 4, 1, true);
      view.setUint32(o + 8, value, true);
    });
    return out;
  }

  // ---------------------------------------------------------------- 画像

  // ダーク減算 (アプリの dark 画面と同じ, 画素ごとの light - dark)
  function subtract(light, dark) {
    if (light.width !== dark.width || light.height !== dark.height) {
      throw new Error(
        '画像の大きさが違います: ' + light.width + 'x' + light.height + ' と ' + dark.width + 'x' + dark.height
      );
    }
    const data = new Float32Array(light.data.length);
    for (let i = 0; i < data.length; i++) {
      data[i] = light.data[i] - dark.data[i];
    }
    return { width: light.width, height: light.height, data };
  }

  // スペクトルを読む帯の行の範囲 [y1, y2). 既定は画像中央の幅 80 px.
  // width は帯の幅 (px), center は帯の中心 (画像の高さに対する割合)
  function bandRows(height, width, center) {
    const band = width === undefined ? 80 : width;
    const mid = Math.max(0, Math.floor(height * (center === undefined ? 0.5 : center)));
    const half = Math.floor(band / 2);
    const y1 = Math.max(0, mid - half);
    return [y1, Math.max(y1, Math.min(height, mid + half))];
  }

  // 帯の中を縦に平均した, 横方向のプロファイル
  function bandProfile(img) {
    const [y1, y2] = bandRows(img.height);
    const rows = Math.max(1, y2 - y1);
    const profile = new Float64Array(img.width);
    for (let y = y1; y < y2; y++) {
      const row = y * img.width;
      for (let x = 0; x < img.width; x++) {
        profile[x] += img.data[row + x];
      }
    }
    for (let x = 0; x < img.width; x++) {
      profile[x] /= rows;
    }
    return profile;
  }

  // プロファイルを 1:2:1 でならす. Bayer 配列のせいで 1 列おきに値が上下するのを消す (山の位置は動かない)
  function smoothProfile(profile) {
    const n = profile.length;
    const out = new Float64Array(n);
    for (let x = 0; x < n; x++) {
      const a = profile[Math.max(0, x - 1)];
      const b = profile[Math.min(n - 1, x + 1)];
      out[x] = (a + 2 * profile[x] + b) / 4;
    }
    return out;
  }

  // 0次光の位置の推定. 帯のプロファイルが最大になる山の真ん中 (px). 推定できなければ null
  function guessZerothOrder(profile) {
    let best = 0;
    let max = -Infinity;
    let min = Infinity;
    for (let x = 0; x < profile.length; x++) {
      const v = profile[x];
      if (!Number.isFinite(v)) continue;
      if (v > max) {
        max = v;
        best = x;
      }
      if (v < min) min = v;
    }
    if (!Number.isFinite(max)) return null;
    // 最大値の近くで, 最大値の 9 割以上の高さが続いている範囲の真ん中
    const threshold = min + (max - min) * 0.9;
    let lo = best;
    while (lo > 0 && profile[lo - 1] >= threshold) lo--;
    let hi = best;
    while (hi + 1 < profile.length && profile[hi + 1] >= threshold) hi++;
    return Math.floor((lo + hi) / 2);
  }

  // 輝線の候補. プロファイルの極大のうち, 周りより十分高いものを高い順に返す [[位置, 高さ], ...]
  function findPeaks(profile, from, to, maxCount) {
    const HALF = 12;
    to = Math.min(to, profile.length);
    if (to <= from + 2) return [];
    let lo = Infinity;
    let hi = -Infinity;
    for (let x = from; x < to; x++) {
      lo = Math.min(lo, profile[x]);
      hi = Math.max(hi, profile[x]);
    }
    const peaks = [];
    for (let x = from + 1; x < to - 1; x++) {
      const v = profile[x];
      if (profile[x - 1] === v) continue;
      const a = Math.max(from, x - HALF);
      const b = Math.min(to, x + HALF + 1);
      let isMax = true;
      let base = Infinity;
      for (let j = a; j < b; j++) {
        if (profile[j] > v) isMax = false;
        base = Math.min(base, profile[j]);
      }
      // 山の高さ (窓の中の最小値との差) が全体の値域の 5% 以上
      if (isMax && v - base >= (hi - lo) * 0.05) peaks.push([x, v]);
    }
    peaks.sort((p, q) => q[1] - p[1]);
    return peaks.slice(0, maxCount);
  }

  // ---------------------------------------------------------------- 波長校正
  // アプリの core/Sources/FukasisCoreC/wavelength_calib.h と同じ計算

  const MAX_DEGREE = 3;
  // スペクトルとして出力する波長の範囲 (nm)
  const WAVELENGTH_MIN = 400;
  const WAVELENGTH_MAX = 700;

  // "1632,1986,2126" のような 1 行を数値の列にする (数値でないものが出たらそこで止める)
  function parseLine(line) {
    const values = [];
    for (const field of line.replace(/[\r\n]+$/, '').split(',')) {
      const text = field.trim();
      const v = Number(text);
      if (text === '' || Number.isNaN(v)) break;
      values.push(v);
    }
    return values;
  }

  // アプリの校正データ (1 行目が位置, 2 行目が波長) を読む. t は 0次光からの距離 (px), c は波長 (nm)
  function parseCalibration(text) {
    const lines = text.split(/\r?\n/);
    if (!lines[0] || !lines[0].trim()) throw new Error('校正データが空です');
    if (lines.length < 2) throw new Error('校正データに 2 行目 (波長) がありません');
    const t = parseLine(lines[0]);
    const c = parseLine(lines[1]);
    if (t.length !== c.length || t.length < 2) {
      throw new Error('校正データの列数が合いません (位置 ' + t.length + ' 個, 波長 ' + c.length + ' 個)');
    }
    return { t, c };
  }

  // アプリと同じ形式で書き出す
  function formatCalibration(cal) {
    return cal.t.map((v) => String(Math.round(v))).join(',') + '\n' + cal.c.map((v) => v.toFixed(6)).join(',');
  }

  // 桁落ちを避けるため, t を u = (t - mean) / scale に直した多項式として持つ
  function polyAt(f, t) {
    const u = (t - f.mean) / (f.ok ? f.scale : 1);
    let v = 0;
    for (let i = f.degree; i >= 0; i--) {
      v = v * u + f.co[i];
    }
    return v;
  }

  // 校正点に多項式を最小二乗で当てはめる.
  // 次数は 3 (位置の異なる校正点が 4 つ未満なら, その数 - 1). 校正点がちょうど 4 つなら,
  // その 4 点を通る 3 次式になる
  function fit(t, c) {
    const f = { ok: false, degree: 0, mean: 0, scale: 1, co: [0, 0, 0, 0] };
    const n = t.length;
    if (n < 2 || c.length !== n) return f;
    let distinct = 0;
    let lo = t[0];
    let hi = t[0];
    let sum = 0;
    for (let i = 0; i < n; i++) {
      if (t.indexOf(t[i]) === i) distinct++;
      lo = Math.min(lo, t[i]);
      hi = Math.max(hi, t[i]);
      sum += t[i];
    }
    if (distinct < 2 || !(lo < hi)) return f;
    f.degree = Math.min(MAX_DEGREE, distinct - 1);
    f.mean = sum / n;
    f.scale = (hi - lo) / 2;

    // 正規方程式 a * co = b を作る (m は係数の数)
    const m = f.degree + 1;
    const a = [];
    for (let i = 0; i < m; i++) a.push(new Array(m + 1).fill(0));
    const pw = new Array(2 * f.degree + 1);
    for (let k = 0; k < n; k++) {
      const u = (t[k] - f.mean) / f.scale;
      pw[0] = 1;
      for (let i = 1; i <= 2 * f.degree; i++) pw[i] = pw[i - 1] * u;
      for (let i = 0; i < m; i++) {
        for (let j = 0; j < m; j++) a[i][j] += pw[i + j];
        a[i][m] += c[k] * pw[i];
      }
    }
    // 部分ピボット選択つきの Gauss の消去法
    for (let col = 0; col < m; col++) {
      let pivot = col;
      for (let row = col + 1; row < m; row++) {
        if (Math.abs(a[pivot][col]) < Math.abs(a[row][col])) pivot = row;
      }
      if (!(Math.abs(a[pivot][col]) > 1e-12)) return f;
      const tmp = a[col];
      a[col] = a[pivot];
      a[pivot] = tmp;
      for (let row = col + 1; row < m; row++) {
        const factor = a[row][col] / a[col][col];
        for (let j = col; j <= m; j++) a[row][j] -= factor * a[col][j];
      }
    }
    for (let i = m - 1; i >= 0; i--) {
      let v = a[i][m];
      for (let j = i + 1; j < m; j++) v -= a[i][j] * f.co[j];
      f.co[i] = v / a[i][i];
    }
    f.ok = true;
    return f;
  }

  // 校正式 f で波長が nm になる, 0次光からの距離 t (px, 小数). 出力範囲 range の中に無ければ null.
  // range の中では波長は単調なので, 見つかるのは 1 か所だけ
  function positionOfWavelength(f, range, nm) {
    if (!f || !f.ok || !range) return null;
    for (let t = range.lo; t < range.hi; t++) {
      const a = polyAt(f, t) - nm;
      const b = polyAt(f, t + 1) - nm;
      if (a === 0) return t;
      if (a * b < 0) return t + a / (a - b);
    }
    return polyAt(f, range.hi) === nm ? range.hi : null;
  }

  // スペクトルとして出力する t の範囲 {lo, hi, cutLow, cutHigh} を決める. t は 1 .. size-2 を動く.
  // 校正点の真ん中から両側へ, 波長が単調に変化している間だけ広げ,
  // そのうち波長が (WAVELENGTH_MIN, WAVELENGTH_MAX) に入る部分を返す. 出力するものが無ければ null.
  // cutLow / cutHigh は, 波長が折り返すせいで端に届く前に打ち切られたとき true
  function outputRange(f, tRef, size) {
    if (!f.ok || tRef.length === 0 || size < 3) return null;
    const tA = Math.min(...tRef);
    const tB = Math.max(...tRef);
    const diff = polyAt(f, tB) - polyAt(f, tA);
    if (!(diff > 0) && !(diff < 0)) return null;
    const sign = diff > 0 ? 1 : -1;
    const tLast = size - 2;
    const start = Math.max(1, Math.min(tLast, Math.round((tA + tB) / 2)));

    let monoLo = start;
    while (monoLo > 1 && sign * (polyAt(f, monoLo) - polyAt(f, monoLo - 1)) > 0) monoLo--;
    let monoHi = start;
    while (monoHi < tLast && sign * (polyAt(f, monoHi + 1) - polyAt(f, monoHi)) > 0) monoHi++;

    let lo = -1;
    let hi = -1;
    for (let t = monoLo; t <= monoHi; t++) {
      const w = polyAt(f, t);
      if (WAVELENGTH_MIN < w && w < WAVELENGTH_MAX) {
        if (lo < 0) lo = t;
        hi = t;
      }
    }
    if (lo < 0) return null;
    // 単調な区間の端まで使い切っていて, そこが画像の端でもなければ, 折り返しで打ち切られている
    return { lo, hi, cutLow: lo === monoLo && monoLo > 1, cutHigh: hi === monoHi && monoHi < tLast };
  }

  // ---------------------------------------------------------------- スペクトルの出力
  // アプリの csv 画面 (共通コア core/ の makeSpectrum) と同じ計算

  // 感度データの csv を読む. 最初の 2 行はヘッダー, 以降は「波長, b, g, r」(5 列目以降は使わない).
  // 波長の小さい順に並べて返す
  function parseSensitivity(text) {
    const rows = [];
    const lines = text.split(/\r?\n/);
    for (let number = 2; number < lines.length; number++) {
      if (!lines[number].trim()) continue;
      const row = [];
      for (const field of lines[number].split(',')) {
        const text = field.trim();
        const v = Number(text);
        if (text === '' || !Number.isFinite(v)) throw new Error('感度データの ' + (number + 1) + ' 行目が数値ではありません');
        row.push(v);
      }
      if (row.length < 4) throw new Error('感度データの ' + (number + 1) + ' 行目の列が足りません');
      rows.push(row);
    }
    if (rows.length < 2) throw new Error('感度データが 2 行未満です');
    rows.sort((a, b) => a[0] - b[0]);
    const s = { wavelength: [], channel: [[], [], [], []] };
    for (const row of rows) {
      s.wavelength.push(row[0]);
      s.channel[0].push(row[1]);
      s.channel[1].push(row[2]);
      s.channel[2].push(row[3]);
      s.channel[3].push(row[1] + row[2] + row[3]);
    }
    return s;
  }

  // 波長 wl での感度の合計 (線形補間. 表の範囲外は端の値)
  function sensitivityAt(sensitivity, wl) {
    const w = sensitivity.wavelength;
    const total = sensitivity.channel[3];
    let hi = 0;
    while (hi < w.length && w[hi] <= wl) hi++;
    if (hi === 0) return total[0];
    if (hi === w.length) return total[w.length - 1];
    const lo = hi - 1;
    return total[lo] + ((wl - w[lo]) * (total[hi] - total[lo])) / (w[hi] - w[lo]);
  }

  // カラーフィルタ配列. 左上 2x2 を読み順に並べた名前
  const CFA_NAMES = ['RGGB', 'GRBG', 'GBRG', 'BGGR', 'MONO'];

  // "rggb" などを 'RGGB' の形にそろえる. 知らない名前なら null
  function parseCfa(name) {
    const upper = String(name).toUpperCase();
    return CFA_NAMES.includes(upper) ? upper : null;
  }

  // metadata の 1 行目に ", cfa XXXX" があれば, 撮影した端末のカラーフィルタ配列として返す. 無ければ null
  function cfaFromMetadata(header) {
    const line = (header || '').split(/\r?\n/)[0];
    const pos = line.lastIndexOf(', cfa ');
    if (pos < 0) return null;
    return parseCfa(/^[A-Za-z]*/.exec(line.slice(pos + 6))[0]);
  }

  // (x, y) の画素のチャンネル. 0: b, 1: g, 2: r (MONO は常に 1). cfa を省くと GBRG (アプリの既定)
  function channelOf(x, y, cfa) {
    const pattern = cfa || 'GBRG';
    if (pattern === 'MONO') return 1;
    const color = pattern[(y & 1) * 2 + (x & 1)];
    return color === 'B' ? 0 : color === 'R' ? 2 : 1;
  }

  // 画像からスペクトルを取り出す. fol は 0次光の位置 (画像の x 座標, px).
  // options は {bandWidth, bandCenter, cfa, noSensitivity} (どれも省略できる. 既定は 80 px, 0.5, 'GBRG', false).
  // noSensitivity が true なら感度校正をしない (sensitivity は使わないので null でよい).
  // 返すのは {wavelength, intensity, fit, range}. intensity は最大値が 1 になるように正規化した相対強度.
  // 出力できないときはエラーを投げる
  function extract(img, cal, sensitivity, fol, options) {
    const opts = options || {};
    if (!Number.isInteger(fol) || fol < 1 || fol >= img.width) {
      throw new Error('0次光の位置 ' + fol + ' が画像の幅 ' + img.width + ' の外です');
    }
    if (cal.t.length !== cal.c.length || cal.t.length < 2) throw new Error('校正データの距離と波長の数が合いません');
    for (let j = 0; j < cal.t.length; j++) {
      for (let k = j + 1; k < cal.t.length; k++) {
        if (cal.t[j] === cal.t[k]) throw new Error('校正データの画素位置が重複しています');
      }
    }
    // t -> 波長 の対応. 4 点ならその 4 点を通る 3 次式, 5 点以上なら 3 次の最小二乗
    const f = fit(cal.t, cal.c);
    if (!f.ok) throw new Error('校正データから波長を求められません');
    const useSensitivity = !opts.noSensitivity;
    if (useSensitivity && (!sensitivity || sensitivity.wavelength.length < 2)) throw new Error('感度データが足りません');

    const cfa = opts.cfa ? parseCfa(opts.cfa) : 'GBRG';
    if (cfa === null) throw new Error('カラーフィルタ配列 ' + opts.cfa + ' は分かりません');
    const [y1, y2] = bandRows(img.height, opts.bandWidth, opts.bandCenter);
    const SIGMA_THRES = 3.0;
    const at = (y, x) => img.data[y * img.width + x];

    // 縦方向の積算. 帯の中で, チャンネルごとに 3 シグマから外れた画素を除いて平均する
    const pure = [[], [], []];
    for (let x = fol; x > 0; x--) {
      const count = [0, 0, 0];
      const mean = [0, 0, 0];
      const sigma = [0, 0, 0];
      const sum = [0, 0, 0];
      for (let y = y1; y < y2; y++) {
        const ch = channelOf(x, y, cfa);
        mean[ch] += at(y, x);
        count[ch]++;
      }
      for (let ch = 0; ch < 3; ch++) if (count[ch] > 0) mean[ch] /= count[ch];
      for (let y = y1; y < y2; y++) {
        const ch = channelOf(x, y, cfa);
        const d = at(y, x) - mean[ch];
        sigma[ch] += d * d;
      }
      for (let ch = 0; ch < 3; ch++) if (count[ch] > 0) sigma[ch] = Math.sqrt(sigma[ch] / count[ch]);
      for (let y = y1; y < y2; y++) {
        const ch = channelOf(x, y, cfa);
        const val = at(y, x);
        if (SIGMA_THRES * sigma[ch] < Math.abs(val - mean[ch])) {
          count[ch]--;
        } else {
          sum[ch] += val < 0 ? 0 : val;
        }
      }
      for (let ch = 0; ch < 3; ch++) pure[ch].push(sum[ch] / Math.max(count[ch], 1));
    }
    const size = pure[0].length;

    // 出力する範囲. 画素の固定範囲ではなく波長で決める. 波長が逆行する部分は含めない
    const noOutput = WAVELENGTH_MIN + '-' + WAVELENGTH_MAX + 'nm に入る点がありません (校正データと 0次光の位置を確認してください)';
    const range = outputRange(f, cal.t, size);
    if (range === null) throw new Error(noOutput);

    // Bayer 配列で b と r は 1 列おきにしか無いので, 欠けている列を両隣から補う
    const min = [Number.MAX_VALUE, Number.MAX_VALUE, Number.MAX_VALUE];
    for (let i = 1; i < size - 1; i++) {
      for (const c of [0, 2]) {
        if (pure[c][i] === 0) pure[c][i] = (pure[c][i - 1] + pure[c][i + 1]) / 2;
      }
      if (range.lo <= i && i <= range.hi) {
        for (let c = 0; c < 3; c++) {
          if (pure[c][i] < min[c]) min[c] = pure[c][i];
        }
      }
    }

    // 波長と感度の校正
    const wavelength = [];
    const intensity = [];
    let max = 0;
    for (let i = range.lo; i <= range.hi; i++) {
      const tp = polyAt(f, i);
      if (!(WAVELENGTH_MIN < tp && tp < WAVELENGTH_MAX)) continue;
      const s = useSensitivity ? sensitivityAt(sensitivity, tp) : 1;
      if (!(s > 0)) continue; // 感度 0 の波長は補正できない
      let bgr = 0;
      for (let c = 0; c < 3; c++) {
        const v = pure[c][i] - min[c];
        if (v > 0) bgr += v;
      }
      bgr /= s;
      if (max < bgr) max = bgr;
      wavelength.push(tp);
      intensity.push(bgr);
    }
    if (wavelength.length === 0) throw new Error(noOutput);
    if (!(max > 0)) throw new Error('スペクトルの強度が 0 です');
    for (let i = 0; i < intensity.length; i++) intensity[i] /= max;
    return { wavelength, intensity, fit: f, range };
  }

  // 感度校正をしなかったスペクトルの 1 行目 (観測の情報) に付ける印
  const NO_SENSITIVITY_MARK = ', sensitivity none';

  // ---------------------------------------------------------------- 参照データ (波長校正の手がかり)

  // 参照用のスペクトル (波長, 強度) を読む. 太陽のスペクトルなど, ほかで手に入れたデータを想定している.
  //   - 区切りはカンマ / タブ / 空白 / セミコロン. 数値で始まらない行 (ヘッダーやコメント) は読み飛ばす
  //   - 波長の単位は nm. 値の大きさから Å や µm と分かるときは nm に直す
  //   - 波長の小さい順に並べ, 同じ波長は最初のものだけ残す
  // 返すのは {x, y, unit}. unit は読み取った単位 ('nm' / 'Å' / 'µm')
  function parseReference(text) {
    const points = [];
    for (const line of text.split(/\r?\n/)) {
      const fields = line.trim().split(/[,;\t ]+/);
      if (fields.length < 2) continue;
      const a = parseValue(fields[0]);
      const b = parseValue(fields[1]);
      if (a === null || b === null || !Number.isFinite(a) || !Number.isFinite(b)) continue;
      points.push([a, b]);
    }
    if (points.length < 2) throw new Error('参照データに数値の行が 2 行以上ありません (波長, 強度 の 2 列が必要です)');
    points.sort((p, q) => p[0] - q[0]);
    // 可視光は 380 - 780 nm = 3800 - 7800 Å = 0.38 - 0.78 µm
    const last = points[points.length - 1][0];
    let unit = 'nm';
    let scale = 1;
    if (last > 2000) {
      unit = 'Å';
      scale = 0.1;
    } else if (last < 20) {
      unit = 'µm';
      scale = 1000;
    }
    const x = [];
    const y = [];
    for (const [a, b] of points) {
      const nm = a * scale;
      if (x.length && nm === x[x.length - 1]) continue;
      x.push(nm);
      y.push(b);
    }
    if (x.length < 2) throw new Error('参照データの波長が 1 種類しかありません');
    return { x, y, unit };
  }

  // 小さい順に並んだ xs の上で, x での値を線形補間する. xs の範囲の外なら NaN
  function interpolate(xs, ys, x) {
    const n = xs.length;
    if (n === 0 || !(x >= xs[0] && x <= xs[n - 1])) return NaN;
    let lo = 0;
    let hi = n - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (xs[mid] <= x) lo = mid;
      else hi = mid;
    }
    if (hi === lo || xs[hi] === xs[lo]) return ys[lo];
    return ys[lo] + ((x - xs[lo]) * (ys[hi] - ys[lo])) / (xs[hi] - xs[lo]);
  }

  const LABEL_LINE = 'wavelength/nm,relative intensity(0.0 -- 1.0)';

  // アプリと同じ形式の csv にする. 1 行目は観測の情報 (metadata.csv の 1 行目), 2 行目はラベル
  function toCsv(spectrum, header) {
    let out = (header || '').split(/\r?\n/)[0] + '\n' + LABEL_LINE + '\n';
    for (let i = 0; i < spectrum.wavelength.length; i++) {
      out += fmtG(spectrum.wavelength[i]) + ',' + fmtG(spectrum.intensity[i]) + '\n';
    }
    return out;
  }

  // スペクトルの csv を読む. 数値でない行 (ヘッダー) は読み飛ばす. {x, y, header} をファイルの順で返す
  function parseCsv(text) {
    const x = [];
    const y = [];
    const lines = text.split(/\r?\n/);
    for (const line of lines) {
      const fields = line.split(',');
      if (fields.length < 2) continue;
      const a = parseValue(fields[0]);
      const b = parseValue(fields[1]);
      if (a === null || b === null) continue;
      x.push(a);
      y.push(b);
    }
    return { x, y, header: lines.length ? lines[0] : '' };
  }

  // ---------------------------------------------------------------- 異常値の除外
  // アプリの SpectrumFilter.java と同じ計算

  const SPIKE_WINDOW = 5; // 孤立したスパイクの判定に使う, 片側の近傍点数
  const SPIKE_MAX_RUN = 2; // これより長く続く山は輝線などの本物の構造とみなして残す
  const SPIKE_SIGMA = 12.0; // 近傍の中央値からのずれがこの倍率 (近傍のばらつき比) を超えたらスパイク候補
  const SPIKE_RANGE_RATIO = 0.15; // 同じく, 全体の値域に対する割合
  const SPIKE_SHOULDER_RATIO = 0.2; // スパイクの隣がこの割合以上持ち上がっていたら本物のピークとみなす
  const SPIKE_MAX_PASS = 3;

  // wavelength, intensity はファイルに書かれていた順.
  // NaN や無限大は常に外す. excludeOutliers のときはさらに
  //   - 波長の並びが折り返している部分 (最も長い単調な区間だけ残す)
  //   - 負の強度
  //   - 孤立したスパイク
  // を外す. 返すのは {wavelength, intensity, excluded} で, 波長の昇順
  function filter(wavelength, intensity, excludeOutliers) {
    const n = Math.min(wavelength.length, intensity.length);
    const keep = [];
    for (let i = 0; i < n; i++) keep.push(Number.isFinite(wavelength[i]) && Number.isFinite(intensity[i]));

    if (excludeOutliers) {
      keepLongestMonotonicRun(wavelength, keep);
      for (let i = 0; i < n; i++) {
        if (intensity[i] < 0) keep[i] = false;
      }
      for (let pass = 0; pass < SPIKE_MAX_PASS; pass++) {
        if (!dropIsolatedSpikes(intensity, keep)) break;
      }
    }

    const order = indicesOf(keep);
    // グラフは波長の昇順でないと描けない (Array.prototype.sort は安定ソート)
    order.sort((a, b) => wavelength[a] - wavelength[b]);
    return {
      wavelength: order.map((i) => wavelength[i]),
      intensity: order.map((i) => intensity[i]),
      excluded: n - order.length,
    };
  }

  function indicesOf(keep) {
    const indices = [];
    for (let i = 0; i < keep.length; i++) {
      if (keep[i]) indices.push(i);
    }
    return indices;
  }

  // 波長がファイル順で単調に変化している区間のうち, 最も長いものだけを残す
  function keepLongestMonotonicRun(wavelength, keep) {
    const idx = indicesOf(keep);
    const m = idx.length;
    if (m < 3) return;
    let bestStart = 0;
    let bestEnd = 0; // 含む
    let start = 0;
    let dir = 0;
    for (let k = 1; k < m; k++) {
      const d = Math.sign(wavelength[idx[k]] - wavelength[idx[k - 1]]);
      if (d !== 0 && dir !== 0 && d !== dir) {
        // 向きが変わった. 折り返し点は次の区間の先頭にもなる
        if (bestEnd - bestStart < k - 1 - start) {
          bestStart = start;
          bestEnd = k - 1;
        }
        start = k - 1;
        dir = d;
      } else if (dir === 0) {
        dir = d;
      }
    }
    if (bestEnd - bestStart < m - 1 - start) {
      bestStart = start;
      bestEnd = m - 1;
    }
    for (let k = 0; k < m; k++) {
      if (k < bestStart || bestEnd < k) keep[idx[k]] = false;
    }
  }

  // 近傍の中央値から大きく外れた, 1 - 2 点だけで裾も無い孤立したスパイクを外す. 外したものがあれば true
  function dropIsolatedSpikes(intensity, keep) {
    const idx = indicesOf(keep);
    const m = idx.length;
    if (m < 2 * SPIKE_WINDOW + 1) return false;
    let min = Infinity;
    let max = -Infinity;
    for (let k = 0; k < m; k++) {
      min = Math.min(min, intensity[idx[k]]);
      max = Math.max(max, intensity[idx[k]]);
    }
    const range = max - min;
    if (range <= 0) return false;

    const spike = new Array(m).fill(false);
    const deviation = new Array(m).fill(0); // 近傍の中央値からのずれ
    for (let k = 0; k < m; k++) {
      const neighbors = [];
      for (let j = Math.max(0, k - SPIKE_WINDOW); j <= Math.min(m - 1, k + SPIKE_WINDOW); j++) {
        if (j !== k) neighbors.push(intensity[idx[j]]);
      }
      const med = median(neighbors);
      // 正規分布なら標準偏差に相当する値
      const sigma = 1.4826 * median(neighbors.map((v) => Math.abs(v - med)));
      const threshold = Math.max(SPIKE_SIGMA * sigma, SPIKE_RANGE_RATIO * range);
      deviation[k] = intensity[idx[k]] - med;
      spike[k] = threshold < Math.abs(deviation[k]);
    }

    let dropped = false;
    let k = 0;
    while (k < m) {
      if (!spike[k]) {
        k++;
        continue;
      }
      let end = k;
      while (end + 1 < m && spike[end + 1]) end++;
      if (end - k + 1 <= SPIKE_MAX_RUN && !hasShoulder(deviation, k, end)) {
        for (let j = k; j <= end; j++) keep[idx[j]] = false;
        dropped = true;
      }
      k = end + 1;
    }
    return dropped;
  }

  // スパイク [start, end] の両隣が, スパイクと同じ向きに持ち上がっているか
  function hasShoulder(deviation, start, end) {
    let peak = deviation[start];
    for (let j = start; j <= end; j++) {
      if (Math.abs(peak) < Math.abs(deviation[j])) peak = deviation[j];
    }
    const sign = Math.sign(peak);
    const limit = SPIKE_SHOULDER_RATIO * Math.abs(peak);
    if (start > 0 && limit <= sign * deviation[start - 1]) return true;
    return end + 1 < deviation.length && limit <= sign * deviation[end + 1];
  }

  function median(values) {
    const sorted = values.slice().sort((a, b) => a - b);
    const n = sorted.length;
    return n % 2 === 1 ? sorted[(n - 1) / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  }

  // ---------------------------------------------------------------- グラフの目盛り

  // 軸の目盛り. きりのよい間隔 (1, 2, 5 × 10^n) で 5 本前後. {ticks, step} を返す
  function niceTicks(min, max, target) {
    if (!(max > min)) return { ticks: [min], step: 1 };
    const raw = (max - min) / (target || 5);
    const magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
    const r = raw / magnitude;
    const step = (r < 1.5 ? 1 : r < 3.5 ? 2 : r < 7.5 ? 5 : 10) * magnitude;
    const ticks = [];
    for (let i = Math.ceil(min / step); i * step <= max + step * 1e-9; i++) {
      ticks.push(i * step + 0); // + 0 は -0 を 0 にするため
    }
    return { ticks, step };
  }

  function tickLabel(v, step) {
    return v.toFixed(step >= 1 ? 0 : -Math.floor(Math.log10(step)));
  }

  // ---------------------------------------------------------------- サンプルデータ

  // 動作確認用の合成画像. 整数演算だけで作るので, テストの正解データ (testdata/) を作った C++ 版と同じ値になる.
  // light が true なら 0次光・連続光・輝線つき, false ならダークフレーム
  function synthImage(seed, light) {
    const W = 420;
    const H = 96;
    const FOL = 390;
    const center = [80, 190, 290]; // b, g, r の連続光の中心 (0次光からの距離)
    const lines = [51, 189, 241, 271]; // 輝線
    const tri = (peak, slope, d) => Math.max(0, peak - slope * Math.abs(d));
    let state = seed >>> 0;
    const next = () => {
      state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
      return state >>> 16;
    };
    const data = new Float32Array(W * H);
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const ch = channelOf(x, y);
        let v = 64 + (next() % 9);
        const hot = next() % 499 === 0;
        if (light) {
          const t = FOL - x;
          v += tri(400, 3, t - center[ch]);
          for (const line of lines) v += tri(300, 80, t - line);
          if (Math.abs(x - FOL) <= 5) v = 1023;
        }
        if (hot) v += 700;
        data[y * W + x] = v;
      }
    }
    return { width: W, height: H, data };
  }

  return {
    WAVELENGTH_MIN,
    WAVELENGTH_MAX,
    LABEL_LINE,
    fmtG,
    parseValue,
    decodeTiff,
    encodeTiff,
    subtract,
    bandRows,
    bandProfile,
    smoothProfile,
    guessZerothOrder,
    findPeaks,
    parseLine,
    parseCalibration,
    formatCalibration,
    polyAt,
    fit,
    outputRange,
    positionOfWavelength,
    parseSensitivity,
    sensitivityAt,
    parseCfa,
    cfaFromMetadata,
    channelOf,
    extract,
    toCsv,
    NO_SENSITIVITY_MARK,
    parseReference,
    interpolate,
    parseCsv,
    filter,
    niceTicks,
    tickLabel,
    synthImage,
  };
});
