// SPDX-License-Identifier: MIT
// 画面の文言 (日本語 / 英語). {0}, {1} ... は引数に置き換わる
(function () {
  'use strict';

  const messages = {
    ja: {
      'app.subtitle': 'PC 版',
      'app.demo': 'サンプルデータで試す',
      'app.theme': '明/暗',
      'app.demoLoaded': 'サンプルデータを読み込みました。各タブでそのまま試せます',
      'tab.dark': 'Dark',
      'tab.calib': 'Calibration',
      'tab.csv': 'CSV',
      'tab.graph': 'Graph',

      'common.brightness': '明るさ',
      'common.zoom': '拡大',
      'common.zoomFit': '全体',
      'common.autoFol': '0次光を自動で探す',
      'common.useInCsv': 'この画像でスペクトル出力へ',
      'common.loaded': '{0} ({1} × {2})',
      'common.readError': '{0} を読めません: {1}',
      'common.sentToCsv': 'CSV タブに画像を渡しました',
      'common.needTiff': '{0}: ここでは tif を選んでください (jpg / png は値が丸められているため使えません)',

      'dark.lead': 'ライトフレームからダークフレームを引きます (アプリの dark 画面)。どちらも stacked.tif を選びます。',
      'dark.light': 'ライトフレーム (stacked.tif)',
      'dark.dark': 'ダークフレーム (stacked.tif)',
      'dark.run': 'ダーク減算',
      'dark.result': '結果',
      'dark.stats': '大きさ {0} × {1}\n値の範囲 {2} 〜 {3}',
      'dark.save': 'darked.tif を保存',

      'calib.lead':
        '輝線の位置と波長を対応させて波長校正データを作ります (アプリの calibration 画面)。蛍光灯などを撮った stacked.tif (または stacked.jpg) を開きます。',
      'calib.image': '校正用の画像',
      'calib.hint': '表の行を選んでから画像をクリックするか、線をドラッグして位置を合わせます',
      'calib.colMarker': '線',
      'calib.colX': '位置 x (px)',
      'calib.colNm': '波長 (nm)',
      'calib.zeroth': '0次光',
      'calib.line': '輝線 {0}',
      'calib.add': '+ 輝線を追加',
      'calib.remove': '削除',
      'calib.snap': '近くの山へ',
      'calib.showPeaks': '輝線の候補を表示',
      'calib.fit': '校正の結果',
      'calib.fitNone': '0次光と、輝線を 2 本以上置くと結果が出ます (4 本以上を推奨)',
      'calib.fitSummary': '校正点 {0} 個、{1} 次式\n出力される波長: {2} 〜 {3} nm\n当てはめとの差の最大: {4} nm',
      'calib.fitNoOutput': '校正点 {0} 個、{1} 次式\nこの校正データでは 400〜700 nm に対応する画素がありません',
      'calib.warnTruncated':
        '波長が途中で折り返すため、出力される範囲が欠けています。線の位置と波長を確認するか、校正点を追加してください。',
      'calib.warnFew': '校正点が 4 本未満です。次数を下げて当てはめています。',
      'calib.warnSide': '輝線は 0次光より左 (x が小さい側) に置いてください。',
      'calib.name': '校正データの名前',
      'calib.save': '校正データを保存 (.csv)',
      'calib.useInCsv': 'この校正でスペクトル出力へ',
      'calib.load': '校正データを読み込む (.csv)',
      'calib.loadNeedsFol': '先に 0次光の位置を決めてください (校正データは 0次光からの距離で書かれています)',
      'calib.sentToCsv': 'CSV タブに校正データを渡しました',
      'calib.fromTab': 'calibration タブの校正',

      'csv.lead':
        '画像からスペクトルを取り出して csv にします (アプリの csv 画面)。画像は darked.tif (無ければ stacked.tif) を選びます。',
      'csv.image': '画像 (darked.tif / stacked.tif)',
      'csv.calib': '波長校正データ (.csv)',
      'csv.sensitivity': '感度データ (sensit_distr.csv)',
      'csv.metadata': 'metadata.csv (任意)',
      'csv.calibInfo': '{0}: 校正点 {1} 個',
      'csv.sensInfo': '{0}: {1} 行',
      'csv.sensRemembered': '{0}: {1} 行 (前回のものを使用)',
      'csv.fol': '0次光の位置 x (px)',
      'csv.folMarker': '0次光',
      'csv.run': 'スペクトルを出力',
      'csv.missing': '足りないもの: {0}',
      'csv.missingImage': '画像',
      'csv.missingCalib': '波長校正データ',
      'csv.missingSens': '感度データ',
      'csv.missingFol': '0次光の位置',
      'csv.result': 'スペクトル',
      'csv.stats': '{0} 点、{1} 〜 {2} nm',
      'csv.noRows': '出力される行がありません。校正データと 0次光の位置を確認してください。',
      'csv.save': 'csv を保存',
      'csv.toGraph': 'Graph タブで開く',

      'graph.lead': 'スペクトルの csv をグラフにします (アプリの view 画面)。8 本まで重ねて比べられます。',
      'graph.open': 'スペクトル (.csv) を開く',
      'graph.outliers': '異常値を除外',
      'graph.refLines': '主な輝線・吸収線を表示',
      'graph.resetZoom': '拡大を解除',
      'graph.png': 'PNG で保存',
      'graph.hint': '横にドラッグすると、その範囲を拡大します。ダブルクリックで元に戻ります。',
      'graph.table': '表で見る',
      'graph.info': '{0}: {1} 点を表示、{2} 点を除外',
      'graph.tooMany': '一度に重ねられるのは {0} 本までです',
      'graph.noData': '{0}: 表示できるデータがありません',
      'graph.tableMore': '(先頭の {0} 行を表示)',
      'graph.remove': '消す',

      'chart.x': '波長 (nm)',
      'chart.y': '相対強度',
      'chart.wavelength': '波長 {0} nm',
    },
    en: {
      'app.subtitle': 'for PC',
      'app.demo': 'Try with sample data',
      'app.theme': 'Light/Dark',
      'app.demoLoaded': 'Sample data loaded. You can try every tab as is.',
      'tab.dark': 'Dark',
      'tab.calib': 'Calibration',
      'tab.csv': 'CSV',
      'tab.graph': 'Graph',

      'common.brightness': 'Brightness',
      'common.zoom': 'Zoom',
      'common.zoomFit': 'Fit',
      'common.autoFol': 'Find the 0th order',
      'common.useInCsv': 'Use this image for the spectrum',
      'common.loaded': '{0} ({1} × {2})',
      'common.readError': 'Cannot read {0}: {1}',
      'common.sentToCsv': 'Image sent to the CSV tab',
      'common.needTiff': '{0}: choose a tif here (jpg / png values are rounded and cannot be used)',

      'dark.lead': 'Subtract a dark frame from a light frame (the dark screen of the app). Choose stacked.tif for both.',
      'dark.light': 'Light frame (stacked.tif)',
      'dark.dark': 'Dark frame (stacked.tif)',
      'dark.run': 'Subtract dark',
      'dark.result': 'Result',
      'dark.stats': 'Size {0} × {1}\nValues {2} to {3}',
      'dark.save': 'Save darked.tif',

      'calib.lead':
        'Match emission lines to wavelengths to make wavelength calibration data (the calibration screen of the app). Open the stacked.tif (or stacked.jpg) of a fluorescent lamp or similar.',
      'calib.image': 'Calibration image',
      'calib.hint': 'Select a row and click the image, or drag a line, to set its position',
      'calib.colMarker': 'Line',
      'calib.colX': 'Position x (px)',
      'calib.colNm': 'Wavelength (nm)',
      'calib.zeroth': '0th order',
      'calib.line': 'Line {0}',
      'calib.add': '+ Add a line',
      'calib.remove': 'Remove',
      'calib.snap': 'Snap to peak',
      'calib.showPeaks': 'Show line candidates',
      'calib.fit': 'Calibration result',
      'calib.fitNone': 'Place the 0th order and at least 2 lines to see the result (4 or more recommended)',
      'calib.fitSummary': '{0} points, degree {1}\nOutput range: {2} to {3} nm\nLargest residual: {4} nm',
      'calib.fitNoOutput': '{0} points, degree {1}\nNo pixel maps to 400–700 nm with this calibration',
      'calib.warnTruncated':
        'The wavelength turns back partway, so part of the range is missing. Check the positions and wavelengths, or add calibration lines.',
      'calib.warnFew': 'Fewer than 4 calibration points. A lower degree is used.',
      'calib.warnSide': 'Place the lines to the left of the 0th order (smaller x).',
      'calib.name': 'Calibration data name',
      'calib.save': 'Save calibration data (.csv)',
      'calib.useInCsv': 'Use this calibration for the spectrum',
      'calib.load': 'Load calibration data (.csv)',
      'calib.loadNeedsFol': 'Set the 0th order position first (calibration data is stored as distances from it)',
      'calib.sentToCsv': 'Calibration sent to the CSV tab',
      'calib.fromTab': 'calibration tab',

      'csv.lead':
        'Extract a spectrum from an image and save it as csv (the csv screen of the app). Choose darked.tif (or stacked.tif if you have no dark frame).',
      'csv.image': 'Image (darked.tif / stacked.tif)',
      'csv.calib': 'Wavelength calibration data (.csv)',
      'csv.sensitivity': 'Sensitivity data (sensit_distr.csv)',
      'csv.metadata': 'metadata.csv (optional)',
      'csv.calibInfo': '{0}: {1} points',
      'csv.sensInfo': '{0}: {1} rows',
      'csv.sensRemembered': '{0}: {1} rows (remembered from last time)',
      'csv.fol': '0th order position x (px)',
      'csv.folMarker': '0th',
      'csv.run': 'Extract spectrum',
      'csv.missing': 'Missing: {0}',
      'csv.missingImage': 'image',
      'csv.missingCalib': 'calibration data',
      'csv.missingSens': 'sensitivity data',
      'csv.missingFol': '0th order position',
      'csv.result': 'Spectrum',
      'csv.stats': '{0} points, {1} to {2} nm',
      'csv.noRows': 'No rows to output. Check the calibration data and the 0th order position.',
      'csv.save': 'Save csv',
      'csv.toGraph': 'Open in the Graph tab',

      'graph.lead': 'Plot spectrum csv files (the view screen of the app). Up to 8 spectra can be overlaid.',
      'graph.open': 'Open spectra (.csv)',
      'graph.outliers': 'Hide outliers',
      'graph.refLines': 'Show common lines',
      'graph.resetZoom': 'Reset zoom',
      'graph.png': 'Save as PNG',
      'graph.hint': 'Drag horizontally to zoom into a range. Double-click to reset.',
      'graph.table': 'View as a table',
      'graph.info': '{0}: {1} points shown, {2} excluded',
      'graph.tooMany': 'Up to {0} spectra can be overlaid',
      'graph.noData': '{0}: no valid data to plot',
      'graph.tableMore': '(first {0} rows)',
      'graph.remove': 'Remove',

      'chart.x': 'Wavelength (nm)',
      'chart.y': 'Relative intensity',
      'chart.wavelength': '{0} nm',
    },
  };

  const listeners = [];
  let lang = 'ja';
  try {
    lang = localStorage.getItem('fukasis.lang') || ((navigator.language || 'ja').startsWith('ja') ? 'ja' : 'en');
  } catch (e) {
    // localStorage が使えない環境 (file:// での制限など) では既定のまま
  }
  if (!messages[lang]) lang = 'ja';

  function t(key) {
    const text = messages[lang][key] !== undefined ? messages[lang][key] : messages.ja[key];
    if (text === undefined) return key;
    const args = Array.prototype.slice.call(arguments, 1);
    return text.replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)]));
  }

  // data-i18n 属性のついた要素に文言を入れる
  function apply(root) {
    (root || document).querySelectorAll('[data-i18n]').forEach((el) => {
      el.textContent = t(el.getAttribute('data-i18n'));
    });
    document.documentElement.lang = lang;
  }

  function setLang(next) {
    lang = messages[next] ? next : 'ja';
    try {
      localStorage.setItem('fukasis.lang', lang);
    } catch (e) {
      // 保存できなくても表示は切り替える
    }
    apply();
    listeners.forEach((fn) => fn(lang));
  }

  window.I18n = {
    t,
    apply,
    setLang,
    getLang: () => lang,
    onChange: (fn) => listeners.push(fn),
  };
})();
