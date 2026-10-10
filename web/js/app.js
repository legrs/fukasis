// SPDX-License-Identifier: MIT
// 画面の組み立て. 計算は core.js, グラフは chart.js, 画像の表示は viewer.js
(function () {
  'use strict';

  const core = window.FukasisCore;
  const t = window.I18n.t;
  const $ = (id) => document.getElementById(id);

  // ---------------------------------------------------------------- 共通の小道具

  let toastTimer = null;
  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 3500);
  }

  function download(name, data, mime) {
    const url = URL.createObjectURL(data instanceof Blob ? data : new Blob([data], { type: mime }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // kind: 'warning' | 'error'. texts が空なら消す
  function setMessages(el, kind, texts) {
    el.textContent = '';
    for (const text of texts) {
      const p = document.createElement('p');
      p.className = 'message ' + kind;
      p.textContent = text;
      el.append(p);
    }
  }

  function baseName(fileName) {
    return fileName.replace(/\.[^.]*$/, '');
  }

  // 画像ファイルを読む. tif は値そのまま, jpg / png は輝度にして読む (位置合わせ用)
  async function loadImageFile(file) {
    if (/\.tiff?$/i.test(file.name)) {
      return core.decodeTiff(await file.arrayBuffer());
    }
    const bitmap = await createImageBitmap(file);
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(bitmap, 0, 0);
    const rgba = ctx.getImageData(0, 0, bitmap.width, bitmap.height).data;
    const data = new Float32Array(bitmap.width * bitmap.height);
    for (let i = 0; i < data.length; i++) {
      data[i] = rgba[i * 4] + rgba[i * 4 + 1] + rgba[i * 4 + 2];
    }
    return { width: bitmap.width, height: bitmap.height, data, rgba };
  }

  // 値をそのまま使う処理 (ダーク減算, スペクトル出力) 用. tif 以外は受け付けない
  async function loadTiffFile(file) {
    const img = await loadImageFile(file);
    if (img.rgba) throw new Error(t('common.needTiff', file.name));
    return img;
  }

  // input[type=file] が変わったら読み込んで渡す. 失敗したらトーストで知らせる
  function onFile(input, load, done) {
    input.addEventListener('change', async () => {
      const files = Array.from(input.files);
      for (const file of files) {
        try {
          done(await load(file), file.name);
        } catch (e) {
          toast(t('common.readError', file.name, e.message));
        }
      }
      // 同じファイルを選び直しても change が出るようにしておく
      if (input.multiple) input.value = '';
    });
  }

  function imageRange(img) {
    let min = Infinity;
    let max = -Infinity;
    for (let i = 0; i < img.data.length; i++) {
      const v = img.data[i];
      if (!Number.isFinite(v)) continue;
      if (v < min) min = v;
      if (v > max) max = v;
    }
    return [min, max];
  }

  const gainOf = (slider) => Math.pow(2, Number(slider.value) / 10);
  const loadedText = (item) => (item ? t('common.loaded', item.name, item.img.width, item.img.height) : '');

  // ---------------------------------------------------------------- タブ

  const tabs = ['dark', 'calib', 'csv', 'graph'];
  const redrawers = {}; // タブを開いたときに描き直すもの (隠れている間は大きさが 0 で描けない)

  function showTab(name) {
    for (const tab of tabs) {
      $('tab-' + tab).hidden = tab !== name;
    }
    document.querySelectorAll('.tabs button').forEach((b) => {
      b.setAttribute('aria-selected', String(b.dataset.tab === name));
    });
    if (redrawers[name]) redrawers[name]();
  }
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

  // ---------------------------------------------------------------- dark

  const dark = { light: null, dark: null, result: null };
  const darkViewer = window.FukasisViewer.create($('dark-viewer'));

  function refreshDark() {
    $('dark-light-info').textContent = loadedText(dark.light);
    $('dark-dark-info').textContent = loadedText(dark.dark);
    $('dark-run').disabled = !(dark.light && dark.dark);
    $('dark-result').hidden = !dark.result;
    if (dark.result) {
      const [min, max] = imageRange(dark.result);
      $('dark-stats').textContent = t('dark.stats', dark.result.width, dark.result.height, core.fmtG(min), core.fmtG(max));
    }
  }

  function runDark() {
    try {
      dark.result = core.subtract(dark.light.img, dark.dark.img);
      setMessages($('dark-message'), 'error', []);
    } catch (e) {
      dark.result = null;
      setMessages($('dark-message'), 'error', [e.message]);
    }
    refreshDark();
    if (dark.result) darkViewer.setImage(dark.result);
  }

  onFile($('dark-light-file'), loadTiffFile, (img, name) => {
    dark.light = { img, name };
    dark.result = null;
    refreshDark();
  });
  onFile($('dark-dark-file'), loadTiffFile, (img, name) => {
    dark.dark = { img, name };
    dark.result = null;
    refreshDark();
  });
  $('dark-run').addEventListener('click', runDark);
  $('dark-save').addEventListener('click', () => download('darked.tif', core.encodeTiff(dark.result), 'image/tiff'));
  $('dark-to-csv').addEventListener('click', () => {
    setCsvImage(dark.result, 'darked.tif');
    toast(t('common.sentToCsv'));
    showTab('csv');
  });
  redrawers.dark = () => darkViewer.redraw();

  // ---------------------------------------------------------------- calibration

  // 主な輝線・吸収線 (nm). 同定の手がかり用
  const REFERENCE_LINES = [
    { nm: 410.2, label: 'Hδ' },
    { nm: 434.0, label: 'Hγ' },
    { nm: 435.8, label: 'Hg' },
    { nm: 486.1, label: 'Hβ' },
    { nm: 495.9, label: '[O III]' },
    { nm: 500.7, label: '[O III]' },
    { nm: 517.3, label: 'Mg b' },
    { nm: 546.1, label: 'Hg' },
    { nm: 589.3, label: 'Na D' },
    { nm: 611.6, label: 'Eu' },
    { nm: 630.0, label: '[O I]' },
    { nm: 656.3, label: 'Hα' },
    { nm: 686.7, label: 'O₂ B' },
  ];

  // 3 波長型の蛍光灯の輝線 (nm). アプリの calibration 画面にある 6 本
  const LAMP_LINES = [
    { nm: 404.7, label: 'Hg' },
    { nm: 435.8, label: 'Hg' },
    { nm: 546.1, label: 'Hg' },
    { nm: 588.0, label: '' },
    { nm: 611.6, label: 'Eu' },
    { nm: 631.1, label: '' },
  ];

  // 太陽の吸収線 (フラウンホーファー線, nm). 太陽光で波長校正をするときの目印
  const SOLAR_LINES = [
    { nm: 410.2, label: 'h Hδ' },
    { nm: 430.8, label: 'G' },
    { nm: 486.1, label: 'F Hβ' },
    { nm: 517.3, label: 'b Mg' },
    { nm: 527.0, label: 'E Fe' },
    { nm: 589.3, label: 'D Na' },
    { nm: 656.3, label: 'C Hα' },
    { nm: 686.7, label: 'B O₂' },
  ];

  // calibration タブに出す参照線の組. 'all' は全部 (同じ波長は 1 本にまとめる)
  function referenceSet(mode) {
    if (mode === 'lamp') return LAMP_LINES;
    if (mode === 'sky') return REFERENCE_LINES;
    if (mode === 'sun') return SOLAR_LINES;
    if (mode !== 'all') return [];
    const seen = new Set();
    return LAMP_LINES.concat(REFERENCE_LINES, SOLAR_LINES)
      .filter((line) => !seen.has(line.nm) && seen.add(line.nm))
      .sort((a, b) => a.nm - b.nm);
  }

  // 輝線の既定値は 3 波長型の蛍光灯のもの (アプリと同じ)
  const DEFAULT_LINES = [435.8, 546.1, 588.0, 611.6];
  const calib = {
    image: null, // {img, name}
    fol: null,
    lines: DEFAULT_LINES.map((nm) => ({ x: null, nm })),
    active: 'fol', // 'fol' か, lines の添字
    fit: null, // 今の校正式と出力範囲 {f, range}. まだ無ければ null
    reference: null, // 参照データ {x, y, unit, name}. 波長の小さい順
  };
  const REFS_KEY = 'fukasis.calibRefs';
  const REF_DATA_KEY = 'fukasis.calibReference';
  // これより大きい参照データはブラウザに覚えさせない (保存できる量に限りがあるため)
  const REF_DATA_MAX_CHARS = 1000000;
  const lineColor = (i) => 'var(--series-' + ((i % 7) + 1) + ')';
  const FOL_COLOR = 'var(--series-8)';

  const calibViewer = window.FukasisViewer.create($('calib-viewer'), {
    onMove(id, x) {
      setCalibPosition(id, x);
    },
    onPick(x) {
      setCalibPosition(calib.active, x);
    },
    onSelect(id) {
      calib.active = id;
      refreshCalib();
    },
    // ポインタの位置と, 校正ができていればその波長を出す
    onHover(x) {
      let text = '';
      if (x !== null) {
        const tt = calib.fol === null ? null : calib.fol - x;
        const inRange = calib.fit && tt !== null && tt >= calib.fit.range.lo && tt <= calib.fit.range.hi;
        text = inRange ? t('calib.readoutNm', x, core.polyAt(calib.fit.f, tt).toFixed(1)) : t('calib.readout', x);
      }
      $('calib-readout').textContent = text;
    },
  });

  // 参照データのグラフ. クリックした波長を, 選んでいる輝線に入れる
  const refPlot = window.FukasisRefPlot.create($('calib-ref-plot'), {
    onPick(nm) {
      const line = calib.active === 'fol' ? null : calib.lines[calib.active];
      if (!line) {
        toast(t('calib.refPickNeedsLine'));
        return;
      }
      line.nm = Math.round(nm * 10) / 10;
      renderCalibTable();
      refreshCalib();
    },
    onHover(nm, value) {
      $('calib-ref-readout').textContent =
        nm === null ? '' : t('calib.refReadout', nm.toFixed(1), Number.isFinite(value) ? core.fmtG(value) : '—');
    },
  });

  // 参照データを, 今の校正式で画像の列ごとの値に直す (プロファイルに重ねる用). 校正がまだ無ければ null
  function referenceOverlay() {
    if (!calib.reference || !calib.fit || !calib.image || !$('calib-ref-overlay').checked) return null;
    const { f, range } = calib.fit;
    const width = calib.image.img.width;
    const values = new Float64Array(width).fill(NaN);
    for (let x = 0; x < width; x++) {
      const tt = calib.fol - x;
      if (tt < range.lo || tt > range.hi) continue;
      values[x] = core.interpolate(calib.reference.x, calib.reference.y, core.polyAt(f, tt));
    }
    return values;
  }

  function setReference(text, name, remembered) {
    calib.reference = Object.assign({ name }, core.parseReference(text));
    refPlot.setData(calib.reference);
    if (!remembered) {
      try {
        // 毎回選び直さなくて済むよう, 大きすぎなければブラウザに覚えさせておく
        if (text.length <= REF_DATA_MAX_CHARS) localStorage.setItem(REF_DATA_KEY, JSON.stringify({ name, text }));
        else localStorage.removeItem(REF_DATA_KEY);
      } catch (e) {
        // 覚えられなくても動作には影響しない
      }
    }
  }

  function setCalibPosition(id, x) {
    if (id === 'fol') calib.fol = x;
    else if (calib.lines[id]) calib.lines[id].x = x;
    refreshCalib();
  }

  // 置かれている輝線から校正データ (0次光からの距離, 波長) を作る. まだ作れなければ null
  function currentCalibration() {
    if (calib.fol === null) return null;
    const placed = calib.lines.filter((l) => l.x !== null && Number.isFinite(l.nm));
    if (placed.length < 2) return null;
    return { t: placed.map((l) => calib.fol - l.x), c: placed.map((l) => l.nm) };
  }

  // 校正式から, 画像の上に波長の目盛り (50 nm ごと) を出す
  function wavelengthTicks(f, range, fol) {
    const ticks = [];
    for (let nm = 400; nm <= 700; nm += 50) {
      const tt = core.positionOfWavelength(f, range, nm);
      if (tt !== null) ticks.push({ x: fol - tt, label: String(nm) });
    }
    return ticks;
  }

  // 校正式から, 既知の輝線が来るはずの位置を求める (出力範囲の外のものは出さない)
  function referenceMarks(f, range, fol, lines) {
    const marks = [];
    for (const line of lines) {
      const tt = core.positionOfWavelength(f, range, line.nm);
      if (tt !== null) marks.push({ x: fol - tt, label: (line.label ? line.label + ' ' : '') + line.nm.toFixed(1) });
    }
    return marks;
  }

  function renderCalibTable() {
    const body = $('calib-lines');
    body.textContent = '';
    const addRow = (id, color, label, line) => {
      const tr = document.createElement('tr');
      tr.dataset.id = String(id);
      const name = document.createElement('td');
      const swatch = document.createElement('span');
      swatch.className = 'swatch';
      swatch.style.background = color;
      name.append(swatch, document.createTextNode(label));
      const xCell = document.createElement('td');
      const xInput = document.createElement('input');
      xInput.type = 'number';
      xInput.step = '1';
      xInput.dataset.role = 'x';
      xInput.addEventListener('input', () => {
        const v = xInput.value === '' ? null : Math.round(Number(xInput.value));
        setCalibPosition(id, v);
      });
      xCell.append(xInput);
      const nmCell = document.createElement('td');
      // 当てはめた式との差 (この線の位置での波長 - 入力した波長)
      const residualCell = document.createElement('td');
      residualCell.dataset.role = 'residual';
      residualCell.className = 'note';
      const tools = document.createElement('td');
      if (line) {
        const nmInput = document.createElement('input');
        nmInput.type = 'number';
        nmInput.step = '0.1';
        nmInput.setAttribute('list', 'calib-nm-list');
        nmInput.value = String(line.nm);
        nmInput.addEventListener('input', () => {
          line.nm = nmInput.value === '' ? NaN : Number(nmInput.value);
          refreshCalib();
        });
        nmCell.append(nmInput);
        const snap = document.createElement('button');
        snap.className = 'small';
        snap.textContent = t('calib.snap');
        snap.addEventListener('click', () => snapToPeak(id));
        const remove = document.createElement('button');
        remove.className = 'small';
        remove.textContent = t('calib.remove');
        remove.addEventListener('click', () => {
          calib.lines.splice(id, 1);
          calib.active = 'fol';
          renderCalibTable();
          refreshCalib();
        });
        tools.append(snap, ' ', remove);
      }
      tr.append(name, xCell, nmCell, residualCell, tools);
      tr.addEventListener('focusin', () => selectCalibRow(id));
      tr.addEventListener('click', () => selectCalibRow(id));
      body.append(tr);
    };
    addRow('fol', FOL_COLOR, t('calib.zeroth'), null);
    calib.lines.forEach((line, i) => addRow(i, lineColor(i), t('calib.line', i + 1), line));
  }

  function selectCalibRow(id) {
    if (calib.active === id) return;
    calib.active = id;
    refreshCalib();
  }

  // 輝線をいちばん近い山 (±15 px の中の最大) に寄せる
  function snapToPeak(i) {
    const profile = calibViewer.getProfile();
    const line = calib.lines[i];
    if (!profile || line.x === null) return;
    let best = line.x;
    for (let x = Math.max(0, line.x - 15); x <= Math.min(profile.length - 1, line.x + 15); x++) {
      if (profile[x] > profile[best]) best = x;
    }
    setCalibPosition(i, best);
  }

  function refreshCalib() {
    const has = !!calib.image;
    $('calib-info').textContent = loadedText(calib.image);
    $('calib-work').hidden = !has;
    $('calib-gain-text').textContent = '×' + gainOf($('calib-gain')).toFixed(1);
    if (!has) return;

    // 表 (入力中の欄は書き換えない)
    $('calib-lines')
      .querySelectorAll('tr')
      .forEach((tr) => {
        const id = tr.dataset.id === 'fol' ? 'fol' : Number(tr.dataset.id);
        tr.classList.toggle('active', id === calib.active);
        const input = tr.querySelector('input[data-role="x"]');
        const x = id === 'fol' ? calib.fol : calib.lines[id].x;
        if (document.activeElement !== input) input.value = x === null ? '' : String(x);
      });

    const markers = [{ id: 'fol', x: calib.fol, color: FOL_COLOR, label: '0', active: calib.active === 'fol' }];
    calib.lines.forEach((line, i) => {
      markers.push({ id: i, x: line.x, color: lineColor(i), label: String(i + 1), active: calib.active === i });
    });
    calibViewer.setMarkers(markers);
    calibViewer.setProfileLimit(calib.fol === null ? null : calib.fol - 30);

    const profile = calibViewer.getProfile();
    const limit = calib.fol === null ? calib.image.img.width : calib.fol - 30;
    calibViewer.setPeaks($('calib-peaks').checked ? core.findPeaks(profile, 0, limit, 12).map((p) => p[0]) : []);

    // 校正の結果
    const cal = currentCalibration();
    const warnings = [];
    const refMode = $('calib-refs').value;
    let ticks = [];
    let refs = [];
    let fitted = null;
    calib.fit = null;
    let summary = t('calib.fitNone');
    if (cal) {
      if (cal.t.some((v) => v <= 0)) warnings.push(t('calib.warnSide'));
      const f = core.fit(cal.t, cal.c);
      if (f.ok) {
        fitted = f;
        const range = core.outputRange(f, cal.t, calib.fol);
        if (range) {
          calib.fit = { f, range };
          refs = referenceMarks(f, range, calib.fol, referenceSet(refMode));
          const w1 = core.polyAt(f, range.lo);
          const w2 = core.polyAt(f, range.hi);
          const residual = Math.max(...cal.t.map((tt, i) => Math.abs(core.polyAt(f, tt) - cal.c[i])));
          summary = t(
            'calib.fitSummary',
            cal.t.length,
            f.degree,
            Math.min(w1, w2).toFixed(1),
            Math.max(w1, w2).toFixed(1),
            residual.toFixed(3)
          );
          if (range.cutLow || range.cutHigh) warnings.push(t('calib.warnTruncated'));
          ticks = wavelengthTicks(f, range, calib.fol);
        } else {
          summary = t('calib.fitNoOutput', cal.t.length, f.degree);
        }
        if (cal.t.length < 4) warnings.push(t('calib.warnFew'));
      }
    }
    $('calib-fit').textContent = summary;
    if (refMode !== 'none' && !calib.fit) warnings.push(t('calib.refsNeedFit'));
    setMessages($('calib-message'), 'warning', warnings);
    calibViewer.setScaleTicks(ticks);
    calibViewer.setReferenceLines(refs);

    // 参照データ
    const ref = calib.reference;
    $('calib-ref-work').hidden = !ref;
    $('calib-ref-clear').hidden = !ref;
    $('calib-ref-overlay-label').hidden = !ref;
    $('calib-ref-info').textContent = !ref
      ? ''
      : t(
          ref.unit === 'nm' ? 'calib.refInfo' : 'calib.refInfoUnit',
          ref.name,
          ref.x.length,
          ref.x[0].toFixed(1),
          ref.x[ref.x.length - 1].toFixed(1),
          ref.unit
        );
    refPlot.setMarkers(
      calib.lines.map((line, i) => ({ nm: line.nm, color: lineColor(i), label: String(i + 1), active: calib.active === i }))
    );
    calibViewer.setOverlay(referenceOverlay());

    // 線ごとの, 当てはめとの差. 1 本だけ大きければ, その線の位置か波長が違っている
    $('calib-lines')
      .querySelectorAll('tr')
      .forEach((tr) => {
        const cell = tr.querySelector('td[data-role="residual"]');
        const line = tr.dataset.id === 'fol' ? null : calib.lines[Number(tr.dataset.id)];
        let text = '';
        if (fitted && line && line.x !== null && Number.isFinite(line.nm)) {
          const d = core.polyAt(fitted, calib.fol - line.x) - line.nm;
          text = (d >= 0 ? '+' : '−') + Math.abs(d).toFixed(2);
        }
        cell.textContent = text;
      });
    $('calib-save').disabled = !cal;
    $('calib-to-csv').disabled = !cal;
  }

  function setCalibImage(img, name) {
    calib.image = { img, name };
    if (!$('calib-name').value) $('calib-name').value = baseName(name);
    calibViewer.setImage(img);
    calibViewer.setGain(gainOf($('calib-gain')));
    if (calib.fol === null || calib.fol >= img.width) {
      calib.fol = core.guessZerothOrder(calibViewer.getProfile());
    }
    refreshCalib();
  }

  onFile($('calib-file'), loadImageFile, setCalibImage);
  $('calib-gain').addEventListener('input', () => {
    calibViewer.setGain(gainOf($('calib-gain')));
    refreshCalib();
  });
  $('calib-zoom').addEventListener('change', () => {
    calibViewer.setZoom(Number($('calib-zoom').value));
    refPlot.setZoom(Number($('calib-zoom').value));
  });
  $('calib-peaks').addEventListener('change', refreshCalib);
  onFile(
    $('calib-ref-file'),
    (file) => file.text(),
    (text, name) => {
      setReference(text, name, false);
      refreshCalib();
    }
  );
  $('calib-ref-clear').addEventListener('click', () => {
    calib.reference = null;
    refPlot.setData(null);
    $('calib-ref-file').value = '';
    try {
      localStorage.removeItem(REF_DATA_KEY);
    } catch (e) {
      // 覚えていなければ何もしない
    }
    refreshCalib();
  });
  $('calib-ref-overlay').addEventListener('change', refreshCalib);
  try {
    const remembered = JSON.parse(localStorage.getItem(REF_DATA_KEY) || 'null');
    if (remembered) setReference(remembered.text, remembered.name, true);
  } catch (e) {
    // 覚えていたものが壊れていたら無視する
  }
  // 参照線の選択は次に開いたときも覚えておく
  try {
    const remembered = localStorage.getItem(REFS_KEY);
    if (['lamp', 'sky', 'sun', 'all'].includes(remembered)) $('calib-refs').value = remembered;
  } catch (e) {
    // 覚えられない環境では毎回「表示しない」から始まる
  }
  $('calib-refs').addEventListener('change', () => {
    try {
      localStorage.setItem(REFS_KEY, $('calib-refs').value);
    } catch (e) {
      // 覚えられなくても表示はできる
    }
    refreshCalib();
  });
  // 波長の欄の候補
  referenceSet('all').forEach((line) => {
    const option = document.createElement('option');
    option.value = String(line.nm);
    if (line.label) option.label = line.label + ' ' + line.nm;
    $('calib-nm-list').append(option);
  });
  $('calib-add').addEventListener('click', () => {
    calib.lines.push({ x: null, nm: NaN });
    calib.active = calib.lines.length - 1;
    renderCalibTable();
    refreshCalib();
  });
  $('calib-auto-fol').addEventListener('click', () => {
    setCalibPosition('fol', core.guessZerothOrder(calibViewer.getProfile()));
    calibViewer.reveal(calib.fol);
  });
  $('calib-save').addEventListener('click', () => {
    const name = ($('calib-name').value || 'calibration').trim();
    download(name + '.csv', core.formatCalibration(currentCalibration()), 'text/csv');
  });
  $('calib-to-csv').addEventListener('click', () => {
    csv.calibration = Object.assign({ name: t('calib.fromTab') }, currentCalibration());
    // CSV タブに画像がまだ無ければ, 同じ画像と 0次光の位置も渡す (tif のときだけ)
    if (!csv.image && !calib.image.img.rgba) {
      setCsvImage(calib.image.img, calib.image.name);
      csv.fol = calib.fol;
    }
    refreshCsv();
    toast(t('calib.sentToCsv'));
    showTab('csv');
  });
  onFile(
    $('calib-load'),
    (file) => file.text(),
    (text) => {
      if (calib.fol === null) {
        toast(t('calib.loadNeedsFol'));
        return;
      }
      const cal = core.parseCalibration(text);
      calib.lines = cal.t.map((tt, i) => ({ x: calib.fol - tt, nm: cal.c[i] }));
      calib.active = 'fol';
      renderCalibTable();
      refreshCalib();
    }
  );
  redrawers.calib = () => {
    calibViewer.redraw();
    refPlot.redraw();
    refreshCalib();
  };

  // ---------------------------------------------------------------- csv

  const csv = {
    image: null, // {img, name}
    fol: null,
    calibration: null, // {t, c, name}
    sensitivity: null, // {parsed, name, remembered}
    metadata: null, // {text, name}
    result: null,
    text: null,
    title: null,
  };
  const SENS_KEY = 'fukasis.sensitivity';
  const NO_SENS_KEY = 'fukasis.noSensitivity';

  const csvViewer = window.FukasisViewer.create($('csv-viewer'), {
    onMove(id, x) {
      csv.fol = x;
      refreshCsv();
    },
    onPick(x) {
      csv.fol = x;
      refreshCsv();
    },
  });
  const csvChart = window.FukasisChart.create($('csv-chart'));

  function setCsvImage(img, name) {
    csv.image = { img, name };
    csv.result = null;
    csvViewer.setImage(img);
    csvViewer.setGain(gainOf($('csv-gain')));
    csv.fol = core.guessZerothOrder(csvViewer.getProfile());
    refreshCsv();
  }

  function setSensitivity(text, name, remembered) {
    csv.sensitivity = { parsed: core.parseSensitivity(text), name, remembered };
    if (!remembered) {
      try {
        // 毎回選び直さなくて済むよう, ブラウザに覚えさせておく
        localStorage.setItem(SENS_KEY, JSON.stringify({ name, text }));
      } catch (e) {
        // 覚えられなくても動作には影響しない
      }
    }
  }

  function refreshCsv() {
    $('csv-image-info').textContent = loadedText(csv.image);
    $('csv-calib-info').textContent = csv.calibration ? t('csv.calibInfo', csv.calibration.name, csv.calibration.t.length) : '';
    const noSens = $('csv-no-sens').checked;
    $('csv-sens-file').disabled = noSens;
    $('csv-sens-info').textContent = noSens
      ? t('csv.noSensInfo')
      : csv.sensitivity
        ? t(csv.sensitivity.remembered ? 'csv.sensRemembered' : 'csv.sensInfo', csv.sensitivity.name, csv.sensitivity.parsed.wavelength.length)
        : '';
    $('csv-meta-info').textContent = csv.metadata ? csv.metadata.name : '';
    $('csv-work').hidden = !csv.image;
    if (csv.image) {
      const fol = $('csv-fol');
      if (document.activeElement !== fol) fol.value = csv.fol === null ? '' : String(csv.fol);
      csvViewer.setMarkers([{ id: 'fol', x: csv.fol, color: FOL_COLOR, label: t('csv.folMarker'), active: true }]);
      csvViewer.setProfileLimit(csv.fol === null ? null : csv.fol - 30);
    }
    const missing = [];
    if (!csv.image) missing.push(t('csv.missingImage'));
    if (!csv.calibration) missing.push(t('csv.missingCalib'));
    if (!csv.sensitivity && !noSens) missing.push(t('csv.missingSens'));
    if (csv.image && csv.fol === null) missing.push(t('csv.missingFol'));
    $('csv-run').disabled = missing.length > 0;
    $('csv-missing').textContent = missing.length ? t('csv.missing', missing.join(', ')) : '';

    $('csv-result').hidden = !csv.result;
    if (csv.result) {
      const w = csv.result.wavelength;
      $('csv-stats').textContent = w.length
        ? t(
            csv.result.noSensitivity ? 'csv.statsNoSens' : 'csv.stats',
            w.length,
            Math.min(w[0], w[w.length - 1]).toFixed(1),
            Math.max(w[0], w[w.length - 1]).toFixed(1)
          )
        : '';
    }
  }

  function runCsv() {
    const warnings = [];
    // 1 行目は観測の情報. metadata.csv が無ければ画像のファイル名を入れておく
    let header = (csv.metadata ? csv.metadata.text : csv.image.name).split(/\r?\n/)[0];
    const noSensitivity = $('csv-no-sens').checked;
    try {
      // アプリと同じく, metadata に撮影した端末のカラーフィルタ配列が記録されていればそれを使う
      const options = { cfa: (csv.metadata && core.cfaFromMetadata(header)) || undefined, noSensitivity };
      const sensitivity = noSensitivity ? null : csv.sensitivity.parsed;
      csv.result = core.extract(csv.image.img, csv.calibration, sensitivity, csv.fol, options);
      csv.result.noSensitivity = noSensitivity;
    } catch (e) {
      csv.result = null;
      setMessages($('csv-message'), 'error', [e.message]);
      refreshCsv();
      return;
    }
    csv.title = csv.metadata ? header.split(',')[0].trim() || baseName(csv.image.name) : baseName(csv.image.name);
    // 感度校正をしていないことは, あとで分かるように csv の 1 行目に残す
    if (noSensitivity) header += core.NO_SENSITIVITY_MARK;
    csv.text = core.toCsv(csv.result, header);
    const range = csv.result.range;
    if (range && (range.cutLow || range.cutHigh)) warnings.push(t('calib.warnTruncated'));
    setMessages($('csv-message'), 'warning', warnings);
    refreshCsv();
    const shown = core.filter(csv.result.wavelength, csv.result.intensity, false);
    csvChart.setSeries([{ name: csv.title, x: shown.wavelength, y: shown.intensity, colorIndex: 0 }]);
  }

  onFile($('csv-image-file'), loadTiffFile, setCsvImage);
  onFile(
    $('csv-calib-file'),
    (file) => file.text(),
    (text, name) => {
      csv.calibration = Object.assign({ name }, core.parseCalibration(text));
      refreshCsv();
    }
  );
  onFile(
    $('csv-sens-file'),
    (file) => file.text(),
    (text, name) => {
      setSensitivity(text, name, false);
      refreshCsv();
    }
  );
  onFile(
    $('csv-meta-file'),
    (file) => file.text(),
    (text, name) => {
      csv.metadata = { text, name };
      refreshCsv();
    }
  );
  $('csv-fol').addEventListener('input', () => {
    const v = $('csv-fol').value;
    csv.fol = v === '' ? null : Math.round(Number(v));
    refreshCsv();
  });
  $('csv-auto-fol').addEventListener('click', () => {
    csv.fol = core.guessZerothOrder(csvViewer.getProfile());
    refreshCsv();
    csvViewer.reveal(csv.fol);
  });
  $('csv-gain').addEventListener('input', () => csvViewer.setGain(gainOf($('csv-gain'))));
  $('csv-zoom').addEventListener('change', () => csvViewer.setZoom(Number($('csv-zoom').value)));
  $('csv-run').addEventListener('click', runCsv);
  // 感度校正をするかどうかは次に開いたときも覚えておく
  try {
    $('csv-no-sens').checked = localStorage.getItem(NO_SENS_KEY) === '1';
  } catch (e) {
    // 覚えられない環境では毎回「感度校正をする」から始まる
  }
  $('csv-no-sens').addEventListener('change', () => {
    try {
      localStorage.setItem(NO_SENS_KEY, $('csv-no-sens').checked ? '1' : '0');
    } catch (e) {
      // 覚えられなくても動作には影響しない
    }
    // 条件が変わったので, 前の結果は消す
    csv.result = null;
    setMessages($('csv-message'), 'warning', []);
    refreshCsv();
  });
  $('csv-save').addEventListener('click', () => download(csv.title + '.csv', csv.text, 'text/csv'));
  $('csv-to-graph').addEventListener('click', () => {
    if (addGraphFile(csv.title, csv.text)) showTab('graph');
  });
  redrawers.csv = () => {
    csvViewer.redraw();
    csvChart.redraw();
  };

  try {
    const remembered = JSON.parse(localStorage.getItem(SENS_KEY) || 'null');
    if (remembered) setSensitivity(remembered.text, remembered.name, true);
  } catch (e) {
    // 覚えていたものが壊れていたら無視する
  }

  // ---------------------------------------------------------------- graph

  const TABLE_ROWS = 1000;
  const graph = { files: [] }; // {name, x, y, colorIndex}. x, y はファイルに書かれていた順
  const graphChart = window.FukasisChart.create($('graph-chart'));
  let graphShown = []; // 異常値を除いたあとの, 実際に描いている系列

  function addGraphFile(name, text) {
    if (graph.files.length >= graphChart.MAX_SERIES) {
      toast(t('graph.tooMany', graphChart.MAX_SERIES));
      return false;
    }
    const parsed = core.parseCsv(text);
    if (!parsed.x.length) {
      toast(t('graph.noData', name));
      return false;
    }
    // 色は系列ごとに固定する (ほかの系列を消しても色が変わらないように, 空いている番号を使う)
    let colorIndex = 0;
    while (graph.files.some((f) => f.colorIndex === colorIndex)) colorIndex++;
    graph.files.push({ name, x: parsed.x, y: parsed.y, colorIndex });
    refreshGraph();
    return true;
  }

  function refreshGraph() {
    const exclude = $('graph-outliers').checked;
    const info = $('graph-info');
    info.textContent = '';
    graphShown = [];
    graph.files.forEach((file, i) => {
      const shown = core.filter(file.x, file.y, exclude);
      graphShown.push({ name: file.name, x: shown.wavelength, y: shown.intensity, colorIndex: file.colorIndex });
      const line = document.createElement('div');
      const key = document.createElement('span');
      key.className = 'swatch';
      key.style.background = 'var(--series-' + (file.colorIndex + 1) + ')';
      const remove = document.createElement('button');
      remove.className = 'small';
      remove.textContent = t('graph.remove');
      remove.addEventListener('click', () => {
        graph.files.splice(i, 1);
        refreshGraph();
      });
      line.append(key, document.createTextNode(t('graph.info', file.name, shown.wavelength.length, shown.excluded) + ' '), remove);
      info.append(line);
    });
    $('graph-card').hidden = graph.files.length === 0;
    graphChart.setSeries(graphShown);
    graphChart.setReferenceLines($('graph-lines').checked ? REFERENCE_LINES : null);

    const select = $('graph-table-series');
    const selected = Math.min(Math.max(select.selectedIndex, 0), graphShown.length - 1);
    select.textContent = '';
    graphShown.forEach((s) => {
      const option = document.createElement('option');
      option.textContent = s.name;
      select.append(option);
    });
    select.selectedIndex = selected;
    select.hidden = graphShown.length < 2;
    renderGraphTable();
  }

  function renderGraphTable() {
    const table = $('graph-table');
    table.textContent = '';
    const s = graphShown[$('graph-table-series').selectedIndex] || graphShown[0];
    if (!s) return;
    const head = table.insertRow();
    for (const text of [t('chart.x'), t('chart.y')]) {
      const th = document.createElement('th');
      th.textContent = text;
      head.append(th);
    }
    const rows = Math.min(s.x.length, TABLE_ROWS);
    for (let i = 0; i < rows; i++) {
      const tr = table.insertRow();
      tr.insertCell().textContent = core.fmtG(s.x[i]);
      tr.insertCell().textContent = core.fmtG(s.y[i]);
    }
    if (s.x.length > rows) {
      const cell = table.insertRow().insertCell();
      cell.colSpan = 2;
      cell.textContent = t('graph.tableMore', rows);
    }
  }

  onFile(
    $('graph-file'),
    (file) => file.text(),
    (text, name) => addGraphFile(baseName(name), text)
  );
  $('graph-outliers').addEventListener('change', refreshGraph);
  $('graph-lines').addEventListener('change', () => {
    graphChart.setReferenceLines($('graph-lines').checked ? REFERENCE_LINES : null);
  });
  $('graph-reset').addEventListener('click', () => graphChart.resetZoom());
  $('graph-png').addEventListener('click', () => {
    if (!graph.files.length) return;
    graphChart.toBlob((blob) => download('spectrum.png', blob));
  });
  $('graph-table-series').addEventListener('change', renderGraphTable);
  redrawers.graph = () => graphChart.redraw();

  // ---------------------------------------------------------------- サンプルデータ

  function demoSensitivityCsv() {
    const rows = ['# synthetic sensitivity curve', 'wavelength,b,g,r'];
    for (let wl = 380; wl < 724; wl += 4) {
      const b = 10 + Math.max(0, 90 - Math.abs(wl - 460) * 0.9);
      const g = 10 + Math.max(0, 90 - Math.abs(wl - 545) * 0.8);
      const r = 10 + Math.max(0, 90 - Math.abs(wl - 625) * 0.7);
      rows.push(wl + ',' + b.toFixed(3) + ',' + g.toFixed(3) + ',' + r.toFixed(3));
    }
    return rows.join('\n') + '\n';
  }

  // 合成した小さな画像を各タブに入れて, データが手元に無くても一通り試せるようにする
  function loadDemo() {
    const FOL = 390;
    dark.light = { img: core.synthImage(12345, true), name: 'sample light (stacked.tif)' };
    dark.dark = { img: core.synthImage(777, false), name: 'sample dark (stacked.tif)' };
    runDark();

    calib.lines = [51, 189, 241, 271].map((tt, i) => ({ x: FOL - tt, nm: DEFAULT_LINES[i] }));
    calib.fol = FOL;
    calib.active = 'fol';
    $('calib-name').value = 'sample';
    renderCalibTable();
    // 参照データ: サンプル画像と同じ波長に山のある, 合成したスペクトル
    let reference = 'wavelength/nm,intensity\n';
    for (let nm = 400; nm <= 700; nm += 0.5) {
      let v = 0.15 + 0.1 * Math.exp(-Math.pow((nm - 550) / 90, 2));
      for (const line of LAMP_LINES) v += Math.exp(-Math.pow((nm - line.nm) / 1.6, 2));
      reference += nm + ',' + v.toFixed(4) + '\n';
    }
    setReference(reference, t('calib.refSample'), true);
    setCalibImage(dark.light.img, dark.light.name);

    setCsvImage(dark.result, 'darked.tif');
    csv.calibration = Object.assign({ name: t('calib.fromTab') }, currentCalibration());
    csv.sensitivity = { parsed: core.parseSensitivity(demoSensitivityCsv()), name: 'sample sensitivity', remembered: false };
    csv.metadata = { text: 'sample, 2026-10-08T00:00:00Z,  ISO 800, fd -1.700000, 100 msec * 3 ', name: 'sample metadata' };
    refreshCsv();
    toast(t('app.demoLoaded'));
  }
  $('demo-btn').addEventListener('click', loadDemo);

  // ---------------------------------------------------------------- 言語とテーマ

  function refreshAll() {
    $('lang-btn').textContent = window.I18n.getLang() === 'ja' ? 'English' : '日本語';
    renderCalibTable();
    refreshDark();
    refreshCalib();
    refreshCsv();
    refreshGraph();
    csvChart.redraw();
  }

  $('lang-btn').addEventListener('click', () => window.I18n.setLang(window.I18n.getLang() === 'ja' ? 'en' : 'ja'));
  window.I18n.onChange(refreshAll);

  const THEME_KEY = 'fukasis.theme';
  function applyTheme(theme) {
    if (theme) document.documentElement.dataset.theme = theme;
    [darkViewer, calibViewer, csvViewer].forEach((v) => v.redraw());
    [csvChart, graphChart].forEach((c) => c.redraw());
  }
  $('theme-btn').addEventListener('click', () => {
    const current =
      document.documentElement.dataset.theme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    const next = current === 'dark' ? 'light' : 'dark';
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch (e) {
      // 保存できなくても切り替えはする
    }
    applyTheme(next);
  });
  try {
    applyTheme(localStorage.getItem(THEME_KEY));
  } catch (e) {
    // 既定 (OS の設定) のまま
  }

  window.I18n.apply();
  refreshAll();
  showTab('dark');
})();
