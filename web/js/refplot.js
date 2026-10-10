// SPDX-License-Identifier: MIT
// 参照データ (波長, 強度) のグラフ. 波長校正のときに, 画像のプロファイルと見比べるために使う.
// 画像では 0次光が右にあり, 左へ行くほど波長が長くなるので, こちらも長い波長を左にして向きをそろえる.
(function () {
  'use strict';

  const core = window.FukasisCore;

  function create(container, handlers) {
    handlers = handlers || {};
    container.classList.add('refplot');
    const scroll = document.createElement('div');
    scroll.className = 'viewer-scroll';
    const inner = document.createElement('div');
    inner.className = 'viewer-inner';
    const canvas = document.createElement('canvas');
    canvas.className = 'refplot-canvas';
    inner.append(canvas);
    scroll.append(inner);
    container.append(scroll);

    let data = null; // {x, y} 波長の小さい順
    let markers = []; // [{nm, color, label, active}] 校正に使っている輝線の波長
    let range = null; // 表示する波長の範囲 [min, max]

    const cssVar = (name) => getComputedStyle(container).getPropertyValue(name).trim();
    // 'var(--series-1)' の形で渡された色を, canvas で使える値にする
    const colorOf = (color) => {
      const m = /^var\((--[\w-]+)\)$/.exec(color);
      return m ? cssVar(m[1]) : color;
    };

    // 波長 -> 0..1 (長い波長が左)
    const fraction = (nm) => (range[1] - nm) / (range[1] - range[0]);

    function render() {
      const rect = canvas.getBoundingClientRect();
      if (!data || !range || rect.width < 10) return;
      const dpr = window.devicePixelRatio || 1;
      // canvas の大きさには上限があるので, 拡大時は解像度を抑える
      const scale = Math.min(dpr, 16000 / rect.width);
      canvas.width = Math.round(rect.width * scale);
      canvas.height = Math.round(rect.height * scale);
      const ctx = canvas.getContext('2d');
      ctx.setTransform(scale, 0, 0, scale, 0, 0);
      const W = rect.width;
      const H = rect.height;
      ctx.clearRect(0, 0, W, H);
      const top = 18;
      const bottom = H - 18;
      const sx = (nm) => fraction(nm) * W;

      // 表示する範囲の値で縦をそろえる
      let min = Infinity;
      let max = -Infinity;
      for (let i = 0; i < data.x.length; i++) {
        if (data.x[i] < range[0] || data.x[i] > range[1]) continue;
        min = Math.min(min, data.y[i]);
        max = Math.max(max, data.y[i]);
      }
      if (!(max > min)) max = min + 1;
      const sy = (v) => bottom - ((v - min) / (max - min)) * (bottom - top);

      // 校正に使っている輝線の波長
      ctx.font = '11px system-ui, sans-serif';
      ctx.textBaseline = 'top';
      ctx.textAlign = 'left';
      for (const m of markers) {
        if (!Number.isFinite(m.nm) || m.nm < range[0] || m.nm > range[1]) continue;
        const px = Math.round(sx(m.nm)) + 0.5;
        ctx.strokeStyle = colorOf(m.color);
        ctx.lineWidth = m.active ? 3 : 1;
        ctx.beginPath();
        ctx.moveTo(px, 0);
        ctx.lineTo(px, bottom);
        ctx.stroke();
        ctx.fillStyle = colorOf(m.color);
        ctx.fillText(m.label, px + 4, 2);
      }

      // データ. 1 px の中に点がいくつも入るときは, その中の最小と最大を縦線で結ぶ (細い吸収線が消えないように)
      ctx.strokeStyle = cssVar('--accent');
      ctx.lineWidth = 1.5;
      ctx.lineJoin = 'round';
      ctx.beginPath();
      const columns = Math.max(1, Math.ceil(W));
      let i = 0;
      while (i < data.x.length && data.x[i] < range[0]) i++;
      let started = false;
      let lastColumn = -1;
      let lo = 0;
      let hi = 0;
      const flush = () => {
        if (lastColumn < 0) return;
        const px = ((lastColumn + 0.5) / columns) * W;
        if (!started) ctx.moveTo(px, sy(lo));
        else ctx.lineTo(px, sy(lo));
        if (hi !== lo) ctx.lineTo(px, sy(hi));
        started = true;
      };
      for (; i < data.x.length && data.x[i] <= range[1]; i++) {
        const column = Math.min(columns - 1, Math.floor(fraction(data.x[i]) * columns));
        if (column !== lastColumn) {
          flush();
          lastColumn = column;
          lo = hi = data.y[i];
        } else {
          lo = Math.min(lo, data.y[i]);
          hi = Math.max(hi, data.y[i]);
        }
      }
      flush();
      ctx.stroke();

      // 波長の目盛り
      ctx.strokeStyle = cssVar('--axis');
      ctx.fillStyle = cssVar('--text-muted');
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.lineWidth = 1;
      const span = range[1] - range[0];
      const zoom = W / Math.max(1, scroll.clientWidth);
      const step = [1, 2, 5, 10, 25, 50, 100].find((s) => span / s <= 8 * zoom) || 100;
      for (let nm = Math.ceil(range[0] / step) * step; nm <= range[1]; nm += step) {
        const px = Math.round(sx(nm)) + 0.5;
        ctx.beginPath();
        ctx.moveTo(px, bottom);
        ctx.lineTo(px, bottom + 4);
        ctx.stroke();
        ctx.fillText(String(nm), px, H - 1);
      }
    }

    // ポインタの位置を波長にする
    function nmFromEvent(e) {
      const rect = inner.getBoundingClientRect();
      const f = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      return range[1] - f * (range[1] - range[0]);
    }

    // クリックした位置の近く (左右 4 px) に山か谷の頂点があれば, その波長に寄せる.
    // 画面の 1 px は 0.数 nm あるので, 輝線や吸収線の波長をそのまま拾えるようにするため
    function snap(nm) {
      const reach = (4 / inner.getBoundingClientRect().width) * (range[1] - range[0]);
      let best = null;
      for (let i = 1; i < data.x.length - 1; i++) {
        if (data.x[i] < nm - reach) continue;
        if (data.x[i] > nm + reach) break;
        const a = data.y[i] - data.y[i - 1];
        const b = data.y[i] - data.y[i + 1];
        // 両隣より高い (山) か, 両隣より低い (谷)
        const extremum = (a > 0 && b >= 0) || (a >= 0 && b > 0) || (a < 0 && b <= 0) || (a <= 0 && b < 0);
        if (extremum && (best === null || Math.abs(data.x[i] - nm) < Math.abs(best - nm))) best = data.x[i];
      }
      return best === null ? nm : best;
    }

    inner.addEventListener('click', (e) => {
      if (data && handlers.onPick) handlers.onPick(snap(nmFromEvent(e)));
    });
    inner.addEventListener('pointermove', (e) => {
      if (!data || !handlers.onHover) return;
      const nm = nmFromEvent(e);
      handlers.onHover(nm, core.interpolate(data.x, data.y, nm));
    });
    inner.addEventListener('pointerleave', () => {
      if (handlers.onHover) handlers.onHover(null, NaN);
    });

    if (window.ResizeObserver) new ResizeObserver(render).observe(scroll);
    window.addEventListener('resize', render);

    return {
      // next は {x, y} (波長の小さい順) か null. 表示するのは可視光のあたり (380 - 720 nm) に入る部分
      setData(next) {
        data = next;
        range = null;
        if (data) {
          const lo = Math.max(380, data.x[0]);
          const hi = Math.min(720, data.x[data.x.length - 1]);
          // 可視光に掛からないデータは, そのまま全部出す
          range = hi > lo ? [lo, hi] : [data.x[0], data.x[data.x.length - 1]];
        }
        render();
      },
      getRange: () => range,
      setMarkers(next) {
        markers = next || [];
        render();
      },
      setZoom(zoom) {
        const center = (scroll.scrollLeft + scroll.clientWidth / 2) / inner.clientWidth;
        inner.style.width = zoom * 100 + '%';
        scroll.scrollLeft = center * inner.clientWidth - scroll.clientWidth / 2;
        render();
      },
      redraw: render,
    };
  }

  window.FukasisRefPlot = { create };
})();
