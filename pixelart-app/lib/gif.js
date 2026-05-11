/*
 * Minimal animated GIF89a encoder.
 * Public API:
 *   const gif = new GIF({ width, height, transparent: 0x00000000 });
 *   gif.addFrame(canvasOrImageDataLike, { delay: ms });
 *   gif.on('finished', blob => ...);
 *   gif.render();
 *
 * Supports indexed palette built from all unique colors across frames (max 256,
 * with simple bucket quantization if exceeded). Index 0 reserved for transparency.
 */
(function (global) {
  'use strict';

  function GIF(opts) {
    this.width = opts.width;
    this.height = opts.height;
    this.frames = []; // { delay, imageData (Uint8ClampedArray RGBA) }
    this._listeners = {};
  }

  GIF.prototype.on = function (evt, cb) {
    (this._listeners[evt] = this._listeners[evt] || []).push(cb);
  };

  GIF.prototype._emit = function (evt, arg) {
    (this._listeners[evt] || []).forEach(cb => cb(arg));
  };

  GIF.prototype.addFrame = function (source, opts) {
    opts = opts || {};
    let imageData;
    if (source instanceof HTMLCanvasElement) {
      const ctx = source.getContext('2d');
      imageData = ctx.getImageData(0, 0, this.width, this.height).data;
    } else if (source.data) {
      imageData = source.data;
    } else {
      imageData = source;
    }
    this.frames.push({
      delay: Math.max(20, Math.round((opts.delay || 100))),
      data: new Uint8ClampedArray(imageData)
    });
  };

  GIF.prototype.render = function () {
    try {
      const blob = this._encode();
      this._emit('finished', blob);
    } catch (e) {
      console.error(e);
      this._emit('error', e);
    }
  };

  // ---- Encoding internals ----

  GIF.prototype._buildPalette = function () {
    // Index 0 = transparent (alpha < 128 maps to 0)
    // Collect unique RGB from opaque pixels across all frames
    const set = new Map();
    for (const f of this.frames) {
      const d = f.data;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i+3] < 128) continue;
        const key = (d[i] << 16) | (d[i+1] << 8) | d[i+2];
        if (!set.has(key)) set.set(key, [d[i], d[i+1], d[i+2]]);
      }
    }
    let colors = [...set.values()];
    if (colors.length > 255) {
      // Simple bucket quantization: reduce by binning to 5 bits per channel (32^3=32768),
      // then further reduce by counts until <=255.
      colors = quantize(this.frames, 255);
    }
    // Build 256-entry palette table; index 0 = arbitrary (transparent)
    const palette = new Uint8Array(256 * 3);
    // index 0: black (transparent)
    palette[0] = 0; palette[1] = 0; palette[2] = 0;
    for (let i = 0; i < colors.length && i < 255; i++) {
      const c = colors[i];
      palette[(i+1)*3] = c[0];
      palette[(i+1)*3+1] = c[1];
      palette[(i+1)*3+2] = c[2];
    }
    // Build lookup map: rgbKey -> palette index
    const lookup = new Map();
    for (let i = 0; i < colors.length && i < 255; i++) {
      const c = colors[i];
      const key = (c[0] << 16) | (c[1] << 8) | c[2];
      lookup.set(key, i + 1);
    }
    return { palette, lookup, colors };
  };

  function quantize(frames, maxColors) {
    // Bin by 5-bit per channel
    const bins = new Map();
    for (const f of frames) {
      const d = f.data;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i+3] < 128) continue;
        const r = d[i] >> 3, g = d[i+1] >> 3, b = d[i+2] >> 3;
        const key = (r << 10) | (g << 5) | b;
        const e = bins.get(key);
        if (e) { e[0]++; e[1]+=d[i]; e[2]+=d[i+1]; e[3]+=d[i+2]; }
        else bins.set(key, [1, d[i], d[i+1], d[i+2]]);
      }
    }
    const arr = [...bins.values()].sort((a,b) => b[0]-a[0]).slice(0, maxColors);
    return arr.map(e => [Math.round(e[1]/e[0]), Math.round(e[2]/e[0]), Math.round(e[3]/e[0])]);
  }

  function nearestIndex(r, g, b, colors) {
    // colors: [[r,g,b], ...] starting at palette index 1
    let best = 0, bestDist = Infinity;
    for (let i = 0; i < colors.length; i++) {
      const c = colors[i];
      const dr = r - c[0], dg = g - c[1], db = b - c[2];
      const d = dr*dr + dg*dg + db*db;
      if (d < bestDist) { bestDist = d; best = i + 1; }
    }
    return best;
  }

  GIF.prototype._mapPixels = function (frame, paletteInfo) {
    const W = this.width, H = this.height;
    const out = new Uint8Array(W * H);
    const d = frame.data;
    const { lookup, colors } = paletteInfo;
    for (let i = 0, p = 0; i < d.length; i += 4, p++) {
      if (d[i+3] < 128) { out[p] = 0; continue; }
      const key = (d[i] << 16) | (d[i+1] << 8) | d[i+2];
      const idx = lookup.get(key);
      if (idx !== undefined) {
        out[p] = idx;
      } else {
        out[p] = nearestIndex(d[i], d[i+1], d[i+2], colors);
      }
    }
    return out;
  };

  GIF.prototype._encode = function () {
    const W = this.width, H = this.height;
    const paletteInfo = this._buildPalette();
    const palette = paletteInfo.palette;

    const chunks = [];
    // Header
    chunks.push(new Uint8Array([0x47,0x49,0x46,0x38,0x39,0x61])); // GIF89a
    // Logical Screen Descriptor
    const lsd = new Uint8Array(7);
    lsd[0] = W & 0xff; lsd[1] = (W >> 8) & 0xff;
    lsd[2] = H & 0xff; lsd[3] = (H >> 8) & 0xff;
    lsd[4] = 0xF7; // GCT present, 256 entries, 8 bit per pixel
    lsd[5] = 0;    // background color index
    lsd[6] = 0;    // pixel aspect ratio
    chunks.push(lsd);
    // GCT (256*3 = 768 bytes)
    chunks.push(palette);

    // NETSCAPE2.0 looping extension (loop forever)
    const netscape = new Uint8Array([
      0x21, 0xFF, 0x0B,
      0x4E,0x45,0x54,0x53,0x43,0x41,0x50,0x45, // NETSCAPE
      0x32,0x2E,0x30, // 2.0
      0x03, 0x01, 0x00, 0x00, 0x00
    ]);
    chunks.push(netscape);

    for (const frame of this.frames) {
      // Graphics Control Extension
      const delay = Math.round(frame.delay / 10); // 1/100s
      const gce = new Uint8Array([
        0x21, 0xF9, 0x04,
        0x09, // packed: transparent flag + disposal=2 (restore to background)? Use disposal=2: 0x08 | 0x01 = 0x09
        delay & 0xff, (delay >> 8) & 0xff,
        0x00, // transparent color index = 0
        0x00  // terminator
      ]);
      chunks.push(gce);
      // Image Descriptor
      const id = new Uint8Array(10);
      id[0] = 0x2C;
      id[1] = 0; id[2] = 0; // left
      id[3] = 0; id[4] = 0; // top
      id[5] = W & 0xff; id[6] = (W >> 8) & 0xff;
      id[7] = H & 0xff; id[8] = (H >> 8) & 0xff;
      id[9] = 0; // no local color table, no interlace
      chunks.push(id);

      // LZW-encoded image data
      const pixels = this._mapPixels(frame, paletteInfo);
      const lzw = lzwEncode(pixels, 8);
      chunks.push(new Uint8Array([8])); // LZW min code size
      // Sub-blocks
      for (let p = 0; p < lzw.length;) {
        const chunkLen = Math.min(255, lzw.length - p);
        chunks.push(new Uint8Array([chunkLen]));
        chunks.push(lzw.subarray(p, p + chunkLen));
        p += chunkLen;
      }
      chunks.push(new Uint8Array([0])); // block terminator
    }
    // Trailer
    chunks.push(new Uint8Array([0x3B]));
    return new Blob(chunks, { type: 'image/gif' });
  };

  // ---- LZW encoder ----
  function lzwEncode(pixels, minCodeSize) {
    const clearCode = 1 << minCodeSize;     // 256
    const eoiCode = clearCode + 1;          // 257
    let codeSize = minCodeSize + 1;         // 9
    let nextCode = eoiCode + 1;             // 258

    // Dictionary: map of "currentString" -> code
    let dict = new Map();
    const resetDict = () => {
      dict = new Map();
      for (let i = 0; i < clearCode; i++) dict.set(String.fromCharCode(i), i);
      codeSize = minCodeSize + 1;
      nextCode = eoiCode + 1;
    };
    resetDict();

    const out = [];
    let buffer = 0, bufferBits = 0;
    const writeCode = (code) => {
      buffer |= code << bufferBits;
      bufferBits += codeSize;
      while (bufferBits >= 8) {
        out.push(buffer & 0xff);
        buffer >>>= 8;
        bufferBits -= 8;
      }
    };

    writeCode(clearCode);

    let current = '';
    for (let i = 0; i < pixels.length; i++) {
      const c = String.fromCharCode(pixels[i]);
      const next = current + c;
      if (dict.has(next)) {
        current = next;
      } else {
        writeCode(dict.get(current));
        if (nextCode < 4096) {
          dict.set(next, nextCode++);
          if (nextCode > (1 << codeSize) && codeSize < 12) codeSize++;
        } else {
          writeCode(clearCode);
          resetDict();
        }
        current = c;
      }
    }
    if (current) writeCode(dict.get(current));
    writeCode(eoiCode);
    // Flush
    if (bufferBits > 0) {
      out.push(buffer & 0xff);
    }
    return new Uint8Array(out);
  }

  global.GIF = GIF;
})(typeof window !== 'undefined' ? window : self);
