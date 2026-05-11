// util.js - small helpers
const Util = {
  clamp(v, min, max) { return Math.max(min, Math.min(max, v)); },

  hexToRgba(hex) {
    if (!hex) return [0,0,0,0];
    let h = hex.replace('#','');
    if (h.length === 3) h = h.split('').map(c=>c+c).join('');
    if (h.length === 6) h += 'ff';
    const r = parseInt(h.substring(0,2),16);
    const g = parseInt(h.substring(2,4),16);
    const b = parseInt(h.substring(4,6),16);
    const a = parseInt(h.substring(6,8),16);
    return [r,g,b,a];
  },

  rgbaToHex([r,g,b,a]) {
    const c = n => n.toString(16).padStart(2,'0');
    return '#' + c(r) + c(g) + c(b) + (a !== undefined && a < 255 ? c(a) : '');
  },

  packRgba(r,g,b,a) { return (a<<24) | (b<<16) | (g<<8) | r; },

  toast(msg, duration = 2200) {
    const el = document.getElementById('toast');
    if (!el) return;
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(el._timer);
    el._timer = setTimeout(()=> { el.hidden = true; }, duration);
  },

  // Bresenham line for pixel drawing
  *bresenhamLine(x0, y0, x1, y1) {
    x0 |= 0; y0 |= 0; x1 |= 0; y1 |= 0;
    const dx = Math.abs(x1-x0), dy = Math.abs(y1-y0);
    const sx = x0 < x1 ? 1 : -1;
    const sy = y0 < y1 ? 1 : -1;
    let err = dx - dy;
    while (true) {
      yield [x0, y0];
      if (x0 === x1 && y0 === y1) break;
      const e2 = err * 2;
      if (e2 > -dy) { err -= dy; x0 += sx; }
      if (e2 < dx)  { err += dx; y0 += sy; }
    }
  },

  download(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 100);
  },

  formatDate(ts) {
    const d = new Date(ts);
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
  }
};
