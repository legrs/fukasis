// SPDX-License-Identifier: MIT
// スペクトル画像のビューア. 画像中央の帯のあたりと, その横方向のプロファイルを上下に並べ,
// 位置合わせ用の線 (0次光, 輝線) を重ねて表示する.
(function () {
  'use strict';

  const core = window.FukasisCore;
  // 表示する行数の上限 (スペクトルを読む帯は中央の 80 行)
  const MAX_ROWS = 160;

  function create(container, handlers) {
    handlers = handlers || {};
    container.classList.add('viewer');
    const scroll = document.createElement('div');
    scroll.className = 'viewer-scroll';
    const inner = document.createElement('div');
    inner.className = 'viewer-inner';
    const imageCanvas = document.createElement('canvas');
    imageCanvas.className = 'viewer-image';
    const bandTop = document.createElement('div');
    bandTop.className = 'viewer-band';
    const bandBottom = document.createElement('div');
    bandBottom.className = 'viewer-band';
    const profileCanvas = document.createElement('canvas');
    profileCanvas.className = 'viewer-profile';
    const markerLayer = document.createElement('div');
    inner.append(imageCanvas, bandTop, bandBottom, profileCanvas, markerLayer);
    scroll.append(inner);
    container.append(scroll);

    let img = null; // {width, height, data, rgba?}
    let profile = null;
    let gain = 1;
    let markers = []; // {id, x, color, label, active}
    let peaks = []; // [x, ...]
    let scaleTicks = []; // [{x, label}]
    let profileLimit = null; // この x より左だけを見て, プロファイルの縦の範囲を決める (0次光で潰れないように)

    const cssVar = (name) => getComputedStyle(container).getPropertyValue(name).trim();

    function renderImage() {
      if (!img) return;
      const rows = Math.min(img.height, MAX_ROWS);
      const y0 = Math.max(0, Math.min(img.height - rows, Math.floor(img.height / 2) - rows / 2));
      const W = img.width;
      imageCanvas.width = W;
      imageCanvas.height = rows;
      const ctx = imageCanvas.getContext('2d');
      const out = ctx.createImageData(W, rows);

      if (img.rgba) {
        // jpg / png はそのままの色で出す
        for (let y = 0; y < rows; y++) {
          for (let x = 0; x < W; x++) {
            const src = ((y0 + y) * W + x) * 4;
            const dst = (y * W + x) * 4;
            out.data[dst] = img.rgba[src] * gain;
            out.data[dst + 1] = img.rgba[src + 1] * gain;
            out.data[dst + 2] = img.rgba[src + 2] * gain;
            out.data[dst + 3] = 255;
          }
        }
      } else {
        // 表示の白黒の基準を, 表示する範囲の値の分布から決める (0次光の白飛びに引きずられないように)
        const samples = [];
        const step = Math.max(1, Math.floor((W * rows) / 100000));
        for (let i = y0 * W; i < (y0 + rows) * W; i += step) {
          if (Number.isFinite(img.data[i])) samples.push(img.data[i]);
        }
        samples.sort((a, b) => a - b);
        const lo = samples.length ? samples[Math.floor(samples.length * 0.02)] : 0;
        const hi = samples.length ? samples[Math.floor(samples.length * 0.995)] : 1;
        const span = hi > lo ? hi - lo : 1;
        const at = (x, y) => img.data[Math.min(img.height - 1, y) * W + Math.min(W - 1, x)];
        const level = (v) => Math.sqrt(Math.max(0, Math.min(1, ((v - lo) / span) * gain))) * 255;
        for (let y = 0; y < rows; y++) {
          // Bayer 配列 (GBRG) の 2x2 の組から色を作る
          const by = (y0 + y) & ~1;
          for (let x = 0; x < W; x++) {
            const bx = x & ~1;
            const dst = (y * W + x) * 4;
            out.data[dst] = level(at(bx, by + 1));
            out.data[dst + 1] = level((at(bx, by) + at(bx + 1, by + 1)) / 2);
            out.data[dst + 2] = level(at(bx + 1, by));
            out.data[dst + 3] = 255;
          }
        }
      }
      ctx.putImageData(out, 0, 0);

      // スペクトルを読む帯の上下の端
      const [b1, b2] = core.bandRows(img.height);
      const height = imageCanvas.clientHeight || 110;
      bandTop.style.top = ((b1 - y0) / rows) * height + 'px';
      bandBottom.style.top = ((b2 - y0) / rows) * height + 'px';
      bandTop.hidden = b1 <= y0;
      bandBottom.hidden = b2 >= y0 + rows;
    }

    function renderProfile() {
      const rect = profileCanvas.getBoundingClientRect();
      if (!img || !profile || rect.width < 10) return;
      const dpr = window.devicePixelRatio || 1;
      // canvas の大きさには上限があるので, 拡大時は解像度を抑える
      const scale = Math.min(dpr, 16000 / rect.width);
      profileCanvas.width = Math.round(rect.width * scale);
      profileCanvas.height = Math.round(rect.height * scale);
      const ctx = profileCanvas.getContext('2d');
      ctx.setTransform(scale, 0, 0, scale, 0, 0);
      const W = rect.width;
      const H = rect.height;
      ctx.clearRect(0, 0, W, H);

      const limit = profileLimit === null ? img.width : Math.max(2, Math.min(img.width, profileLimit));
      let min = Infinity;
      let max = -Infinity;
      for (let x = 0; x < limit; x++) {
        if (!Number.isFinite(profile[x])) continue;
        min = Math.min(min, profile[x]);
        max = Math.max(max, profile[x]);
      }
      if (!(max > min)) max = min + 1;
      const top = 18;
      const bottom = H - 18;
      const sy = (v) => bottom - Math.max(0, Math.min(1.05, (v - min) / (max - min))) * (bottom - top);
      const sx = (x) => ((x + 0.5) / img.width) * W;

      // 輝線の候補
      ctx.fillStyle = cssVar('--accent');
      for (const x of peaks) {
        ctx.fillRect(sx(x) - 1, top - 8, 2, 6);
      }

      // プロファイル. 1 px の中に複数の列が入るときは最大値をとる (細い輝線が消えないように)
      ctx.strokeStyle = cssVar('--text-secondary');
      ctx.lineWidth = 1.5;
      ctx.lineJoin = 'round';
      ctx.beginPath();
      const columns = Math.min(img.width, Math.ceil(W));
      for (let c = 0; c < columns; c++) {
        const a = Math.floor((c / columns) * img.width);
        const b = Math.max(a + 1, Math.floor(((c + 1) / columns) * img.width));
        let v = -Infinity;
        for (let x = a; x < b; x++) v = Math.max(v, profile[x]);
        const px = ((c + 0.5) / columns) * W;
        if (c === 0) ctx.moveTo(px, sy(v));
        else ctx.lineTo(px, sy(v));
      }
      ctx.stroke();

      // 校正から求めた波長の目盛り
      ctx.strokeStyle = cssVar('--axis');
      ctx.fillStyle = cssVar('--text-muted');
      ctx.font = '11px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.lineWidth = 1;
      for (const tick of scaleTicks) {
        const px = Math.round(sx(tick.x)) + 0.5;
        ctx.beginPath();
        ctx.moveTo(px, bottom);
        ctx.lineTo(px, bottom + 4);
        ctx.stroke();
        ctx.fillText(tick.label, px, H - 1);
      }
    }

    function renderMarkers() {
      markerLayer.textContent = '';
      if (!img) return;
      for (const m of markers) {
        if (m.x === null || m.x === undefined || !Number.isFinite(m.x)) continue;
        const el = document.createElement('div');
        el.className = 'marker' + (m.active ? ' active' : '');
        el.style.setProperty('--marker-color', m.color);
        el.style.left = ((m.x + 0.5) / img.width) * 100 + '%';
        const label = document.createElement('span');
        label.textContent = m.label;
        el.append(label);
        el.addEventListener('pointerdown', (e) => {
          e.preventDefault();
          startDrag(m.id);
        });
        markerLayer.append(el);
      }
    }

    // 線のドラッグ. 動かすたびに線の要素は作り直されるので, 要素ではなく window でポインタを追う
    let dragging = false;
    function startDrag(id) {
      dragging = true;
      if (handlers.onSelect) handlers.onSelect(id);
      const move = (e) => {
        if (handlers.onMove) handlers.onMove(id, xFromEvent(e));
      };
      const up = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        window.removeEventListener('pointercancel', up);
        // ドラッグの終わりに出る click で, 選択中の線が動かないようにする
        setTimeout(() => {
          dragging = false;
        }, 0);
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
      window.addEventListener('pointercancel', up);
    }

    // ポインタの位置を画像の x 座標 (px) にする
    function xFromEvent(e) {
      const rect = inner.getBoundingClientRect();
      const x = Math.floor(((e.clientX - rect.left) / rect.width) * img.width);
      return Math.max(0, Math.min(img.width - 1, x));
    }

    inner.addEventListener('click', (e) => {
      if (!img || dragging || e.target.closest('.marker')) return;
      if (handlers.onPick) handlers.onPick(xFromEvent(e));
    });

    if (window.ResizeObserver) new ResizeObserver(renderProfile).observe(scroll);
    window.addEventListener('resize', renderProfile);

    return {
      setImage(next) {
        img = next;
        profile = img ? core.smoothProfile(core.bandProfile(img)) : null;
        renderImage();
        renderProfile();
        renderMarkers();
      },
      getProfile: () => profile,
      // 明るさの倍率 (表示だけ)
      setGain(next) {
        gain = next;
        renderImage();
      },
      setZoom(zoom) {
        // 見ている場所の中心を保ったまま拡大する
        const center = (scroll.scrollLeft + scroll.clientWidth / 2) / inner.clientWidth;
        inner.style.width = zoom * 100 + '%';
        scroll.scrollLeft = center * inner.clientWidth - scroll.clientWidth / 2;
        renderProfile();
      },
      setMarkers(next) {
        markers = next;
        renderMarkers();
      },
      setPeaks(next) {
        peaks = next;
        renderProfile();
      },
      setScaleTicks(next) {
        scaleTicks = next;
        renderProfile();
      },
      setProfileLimit(next) {
        profileLimit = next;
        renderProfile();
      },
      // x の位置が見えるように横スクロールする
      reveal(x) {
        if (!img) return;
        const px = (x / img.width) * inner.clientWidth;
        if (px < scroll.scrollLeft || px > scroll.scrollLeft + scroll.clientWidth) {
          scroll.scrollLeft = px - scroll.clientWidth / 2;
        }
      },
      redraw() {
        renderImage();
        renderProfile();
        renderMarkers();
      },
    };
  }

  window.FukasisViewer = { create };
})();
