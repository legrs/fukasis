// SPDX-License-Identifier: MIT
// スペクトル用の折れ線グラフ (canvas). 依存ライブラリなし.
//   - 横にドラッグで拡大, ダブルクリックで解除
//   - ポインタを載せると, いちばん近いデータ点の波長と各系列の値を表示
(function () {
  'use strict';

  const core = window.FukasisCore;
  const MAX_SERIES = 8;
  const MARGIN = { left: 60, right: 16, top: 14, bottom: 42 };

  // 昇順の配列 xs の中で, x にいちばん近い要素の添字
  function nearestIndex(xs, x) {
    let lo = 0;
    let hi = xs.length - 1;
    if (hi < 0) return -1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (xs[mid] < x) lo = mid;
      else hi = mid;
    }
    return Math.abs(xs[lo] - x) <= Math.abs(xs[hi] - x) ? lo : hi;
  }

  function create(container) {
    const legend = document.createElement('div');
    legend.className = 'chart-legend';
    const canvas = document.createElement('canvas');
    canvas.tabIndex = 0;
    const tooltip = document.createElement('div');
    tooltip.className = 'chart-tooltip';
    tooltip.hidden = true;
    container.append(legend, canvas, tooltip);
    const ctx = canvas.getContext('2d');

    let series = []; // {name, x, y, colorIndex}. x は昇順
    let refLines = null; // [{nm, label}]
    let zoom = null; // [xMin, xMax]
    let hoverX = null;
    let drag = null; // {start, current} (canvas 内の x 座標, CSS px)
    let view = null; // 直近の描画で使った座標変換

    const cssVar = (name) => getComputedStyle(container).getPropertyValue(name).trim();
    const colorOf = (s) => cssVar('--series-' + ((s.colorIndex % MAX_SERIES) + 1));

    function fullDomain() {
      let min = Infinity;
      let max = -Infinity;
      for (const s of series) {
        if (!s.x.length) continue;
        min = Math.min(min, s.x[0]);
        max = Math.max(max, s.x[s.x.length - 1]);
      }
      return min < max ? [min, max] : [min - 1, min + 1];
    }

    function renderLegend() {
      legend.textContent = '';
      // 系列が 1 本のときは凡例を出さない (何を描いているかは見出しで分かる)
      if (series.length < 2) return;
      for (const s of series) {
        const item = document.createElement('span');
        const key = document.createElement('span');
        key.className = 'key';
        key.style.setProperty('--key-color', colorOf(s));
        item.append(key, document.createTextNode(s.name));
        legend.append(item);
      }
    }

    function draw() {
      const rect = canvas.getBoundingClientRect();
      if (rect.width < 10 || rect.height < 10) return;
      const dpr = window.devicePixelRatio || 1;
      if (canvas.width !== Math.round(rect.width * dpr) || canvas.height !== Math.round(rect.height * dpr)) {
        canvas.width = Math.round(rect.width * dpr);
        canvas.height = Math.round(rect.height * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const W = rect.width;
      const H = rect.height;
      const plot = { x: MARGIN.left, y: MARGIN.top, w: W - MARGIN.left - MARGIN.right, h: H - MARGIN.top - MARGIN.bottom };

      ctx.fillStyle = cssVar('--surface');
      ctx.fillRect(0, 0, W, H);
      view = null;
      if (!series.some((s) => s.x.length)) return;

      const [xMin, xMax] = zoom || fullDomain();
      // 縦軸は, 見えている範囲のデータに合わせる
      let yMin = 0;
      let yMax = -Infinity;
      for (const s of series) {
        for (let i = 0; i < s.x.length; i++) {
          if (s.x[i] < xMin || s.x[i] > xMax) continue;
          yMin = Math.min(yMin, s.y[i]);
          yMax = Math.max(yMax, s.y[i]);
        }
      }
      if (!(yMax > yMin)) yMax = yMin + 1;
      const yStep = core.niceTicks(yMin, yMax).step;
      yMin = Math.floor(yMin / yStep) * yStep;
      yMax = Math.ceil(yMax / yStep) * yStep;
      const yTicks = core.niceTicks(yMin, yMax);
      const xTicks = core.niceTicks(xMin, xMax, Math.max(3, Math.floor(plot.w / 110)));

      const sx = (x) => plot.x + ((x - xMin) / (xMax - xMin)) * plot.w;
      const sy = (y) => plot.y + plot.h - ((y - yMin) / (yMax - yMin)) * plot.h;
      view = { plot, xMin, xMax, sx, sy };

      const muted = cssVar('--text-muted');
      const secondary = cssVar('--text-secondary');
      ctx.lineWidth = 1;
      ctx.font = '11px system-ui, sans-serif';

      // 横の目盛り線と縦軸の数値
      ctx.strokeStyle = cssVar('--border');
      ctx.fillStyle = muted;
      ctx.textAlign = 'right';
      ctx.textBaseline = 'middle';
      for (const t of yTicks.ticks) {
        const y = Math.round(sy(t)) + 0.5;
        ctx.beginPath();
        ctx.moveTo(plot.x, y);
        ctx.lineTo(plot.x + plot.w, y);
        ctx.stroke();
        ctx.fillText(core.tickLabel(t, yTicks.step), plot.x - 8, y);
      }
      // 横軸
      ctx.strokeStyle = cssVar('--axis');
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';
      for (const t of xTicks.ticks) {
        const x = Math.round(sx(t)) + 0.5;
        ctx.beginPath();
        ctx.moveTo(x, plot.y + plot.h);
        ctx.lineTo(x, plot.y + plot.h + 5);
        ctx.stroke();
        ctx.fillText(core.tickLabel(t, xTicks.step), x, plot.y + plot.h + 8);
      }
      ctx.beginPath();
      ctx.moveTo(plot.x, plot.y + plot.h + 0.5);
      ctx.lineTo(plot.x + plot.w, plot.y + plot.h + 0.5);
      ctx.stroke();

      // 軸の名前
      ctx.fillStyle = secondary;
      ctx.font = '12px system-ui, sans-serif';
      ctx.fillText(window.I18n.t('chart.x'), plot.x + plot.w / 2, H - 16);
      ctx.save();
      ctx.translate(14, plot.y + plot.h / 2);
      ctx.rotate(-Math.PI / 2);
      ctx.textBaseline = 'middle';
      ctx.fillText(window.I18n.t('chart.y'), 0, 0);
      ctx.restore();

      ctx.save();
      ctx.beginPath();
      ctx.rect(plot.x, plot.y, plot.w, plot.h);
      ctx.clip();

      // 参照用の輝線・吸収線
      if (refLines) {
        ctx.font = '10px system-ui, sans-serif';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'top';
        // 文字が重ならないよう, 空いている段に置く (3 段とも埋まっていたら文字は出さない)
        const rowEnd = [-Infinity, -Infinity, -Infinity];
        for (const line of refLines) {
          if (line.nm < xMin || line.nm > xMax) continue;
          const x = Math.round(sx(line.nm)) + 0.5;
          ctx.strokeStyle = cssVar('--axis');
          ctx.beginPath();
          ctx.moveTo(x, plot.y);
          ctx.lineTo(x, plot.y + plot.h);
          ctx.stroke();
          const row = rowEnd.findIndex((end) => end + 6 < x);
          if (row < 0) continue;
          rowEnd[row] = x + 3 + ctx.measureText(line.label).width;
          ctx.fillStyle = muted;
          ctx.fillText(line.label, x + 3, plot.y + 2 + row * 12);
        }
      }

      // データ
      ctx.lineWidth = 2;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      for (const s of series) {
        ctx.strokeStyle = colorOf(s);
        ctx.beginPath();
        for (let i = 0; i < s.x.length; i++) {
          if (i === 0) ctx.moveTo(sx(s.x[i]), sy(s.y[i]));
          else ctx.lineTo(sx(s.x[i]), sy(s.y[i]));
        }
        ctx.stroke();
      }

      // ポインタの位置
      if (hoverX !== null && hoverX >= xMin && hoverX <= xMax) {
        const x = Math.round(sx(hoverX)) + 0.5;
        ctx.lineWidth = 1;
        ctx.strokeStyle = muted;
        ctx.beginPath();
        ctx.moveTo(x, plot.y);
        ctx.lineTo(x, plot.y + plot.h);
        ctx.stroke();
        for (const s of series) {
          const i = pointNear(s, hoverX);
          if (i < 0) continue;
          // 線と重なっても見えるよう, 地の色の輪で囲む
          ctx.fillStyle = cssVar('--surface');
          ctx.beginPath();
          ctx.arc(sx(s.x[i]), sy(s.y[i]), 6, 0, Math.PI * 2);
          ctx.fill();
          ctx.fillStyle = colorOf(s);
          ctx.beginPath();
          ctx.arc(sx(s.x[i]), sy(s.y[i]), 4, 0, Math.PI * 2);
          ctx.fill();
        }
      }
      if (drag) {
        ctx.fillStyle = cssVar('--accent');
        ctx.globalAlpha = 0.15;
        ctx.fillRect(Math.min(drag.start, drag.current), plot.y, Math.abs(drag.current - drag.start), plot.h);
        ctx.globalAlpha = 1;
      }
      ctx.restore();
    }

    // 系列 s の中で, x に十分近い点の添字. 近い点が無ければ -1
    function pointNear(s, x) {
      const i = nearestIndex(s.x, x);
      if (i < 0 || !view) return -1;
      return Math.abs(view.sx(s.x[i]) - view.sx(x)) <= 8 ? i : -1;
    }

    function updateTooltip(pointerX) {
      if (hoverX === null || !view) {
        tooltip.hidden = true;
        return;
      }
      tooltip.textContent = '';
      const head = document.createElement('div');
      head.className = 'x';
      head.textContent = window.I18n.t('chart.wavelength', hoverX.toFixed(2));
      tooltip.append(head);
      for (const s of series) {
        const i = pointNear(s, hoverX);
        if (i < 0) continue;
        const row = document.createElement('div');
        const key = document.createElement('span');
        key.className = 'key';
        key.style.setProperty('--key-color', colorOf(s));
        const value = document.createElement('strong');
        value.textContent = core.fmtG(s.y[i]);
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = s.name;
        row.append(key, value, name);
        tooltip.append(row);
      }
      tooltip.hidden = false;
      // ポインタの右に出し, はみ出すなら左に出す
      const width = tooltip.offsetWidth;
      const left = pointerX + 14 + width > container.clientWidth ? pointerX - 14 - width : pointerX + 14;
      tooltip.style.left = Math.max(0, left) + 'px';
      tooltip.style.top = canvas.offsetTop + MARGIN.top + 4 + 'px';
    }

    // ポインタにいちばん近いデータ点の波長
    function snap(valueX) {
      let best = null;
      for (const s of series) {
        const i = nearestIndex(s.x, valueX);
        if (i < 0) continue;
        if (best === null || Math.abs(s.x[i] - valueX) < Math.abs(best - valueX)) best = s.x[i];
      }
      return best;
    }

    function pointerValue(e) {
      const rect = canvas.getBoundingClientRect();
      const px = Math.max(view.plot.x, Math.min(view.plot.x + view.plot.w, e.clientX - rect.left));
      return { px, value: view.xMin + ((px - view.plot.x) / view.plot.w) * (view.xMax - view.xMin) };
    }

    canvas.addEventListener('pointermove', (e) => {
      if (!view) return;
      const p = pointerValue(e);
      if (drag) drag.current = p.px;
      hoverX = snap(p.value);
      draw();
      updateTooltip(hoverX === null ? p.px : view.sx(hoverX));
    });
    canvas.addEventListener('pointerdown', (e) => {
      if (!view || e.button !== 0) return;
      canvas.setPointerCapture(e.pointerId);
      const p = pointerValue(e);
      drag = { start: p.px, current: p.px };
    });
    canvas.addEventListener('pointerup', () => {
      if (!drag || !view) return;
      const a = Math.min(drag.start, drag.current);
      const b = Math.max(drag.start, drag.current);
      const toValue = (px) => view.xMin + ((px - view.plot.x) / view.plot.w) * (view.xMax - view.xMin);
      drag = null;
      // 少し動かしただけのときは拡大しない
      if (b - a >= 6) zoom = [toValue(a), toValue(b)];
      draw();
    });
    canvas.addEventListener('pointerleave', () => {
      if (drag) return;
      hoverX = null;
      tooltip.hidden = true;
      draw();
    });
    canvas.addEventListener('dblclick', () => {
      zoom = null;
      draw();
    });
    // キーボードでも同じ情報が読めるよう, 左右キーでデータ点を移動する
    canvas.addEventListener('keydown', (e) => {
      if (!view || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return;
      const s = series.find((item) => item.x.length);
      if (!s) return;
      e.preventDefault();
      const current = hoverX === null ? (view.xMin + view.xMax) / 2 : hoverX;
      const i = nearestIndex(s.x, current) + (hoverX === null ? 0 : e.key === 'ArrowLeft' ? -1 : 1);
      hoverX = s.x[Math.max(0, Math.min(s.x.length - 1, i))];
      draw();
      updateTooltip(view.sx(hoverX));
    });
    canvas.addEventListener('blur', () => {
      hoverX = null;
      tooltip.hidden = true;
      draw();
    });

    if (window.ResizeObserver) new ResizeObserver(draw).observe(canvas);
    window.addEventListener('resize', draw);

    return {
      MAX_SERIES,
      setSeries(next) {
        series = next;
        zoom = null;
        hoverX = null;
        tooltip.hidden = true;
        renderLegend();
        draw();
      },
      setReferenceLines(lines) {
        refLines = lines;
        draw();
      },
      resetZoom() {
        zoom = null;
        draw();
      },
      redraw() {
        renderLegend();
        draw();
      },
      toBlob(callback) {
        hoverX = null;
        draw();
        canvas.toBlob(callback, 'image/png');
      },
    };
  }

  window.FukasisChart = { create, MAX_SERIES };
})();
