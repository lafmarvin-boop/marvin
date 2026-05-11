// gen-mipmaps.js - generates ic_launcher.png and ic_launcher_round.png for all density buckets.
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

const ART = [
  "................",
  ".......oooo.....",
  "......o####o....",
  "......o*##*o....",
  "......o@##@o....",
  "......o####o....",
  ".......oooo.....",
  ".....=oooooo=...",
  "....=========...",
  "....=+======&...",
  "....=+======~...",
  ".....oooooo~....",
  ".....o####o~....",
  ".....o####o.....",
  ".....$o$o$......",
  "....$$.$$.......",
];

const COLORS = {
  '.': [0,0,0,0],
  'o': [26,20,40,255],
  '#': [242,192,155,255],
  '*': [255,217,184,255],
  '@': [26,20,40,255],
  '=': [85,102,255,255],
  '+': [136,153,255,255],
  '$': [58,42,34,255],
  '~': [224,229,240,255],
  '&': [255,195,71,255],
};

function makePng(width, height, getPixel) {
  const raw = Buffer.alloc(width * height * 4 + height);
  for (let y = 0, p = 0; y < height; y++) {
    raw[p++] = 0;
    for (let x = 0; x < width; x++) {
      const [r,g,b,a] = getPixel(x, y);
      raw[p++] = r; raw[p++] = g; raw[p++] = b; raw[p++] = a;
    }
  }
  const compressed = zlib.deflateSync(raw);
  const chunks = [];
  chunks.push(Buffer.from([0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A]));
  chunks.push(makeChunk('IHDR', (() => {
    const b = Buffer.alloc(13);
    b.writeUInt32BE(width, 0); b.writeUInt32BE(height, 4);
    b[8]=8; b[9]=6; b[10]=0; b[11]=0; b[12]=0;
    return b;
  })()));
  chunks.push(makeChunk('IDAT', compressed));
  chunks.push(makeChunk('IEND', Buffer.alloc(0)));
  return Buffer.concat(chunks);
}

function makeChunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const lenBuf = Buffer.alloc(4); lenBuf.writeUInt32BE(data.length, 0);
  const crcBuf = Buffer.alloc(4); crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0, 0);
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf]);
}

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function makeIcon(size, round) {
  const sprite = 16;
  // Background fills entire square
  const bg = [30, 30, 42, 255];
  const accent = [60, 60, 110, 255];
  // Foreground sprite scaled to 75% of size, centered
  const fgSize = Math.floor(size * 0.75);
  const tile = fgSize / sprite;
  const offset = (size - fgSize) / 2;
  const cx = size / 2, cy = size / 2;
  const radius = size / 2;

  return makePng(size, size, (x, y) => {
    // Round mask if requested
    if (round) {
      const dx = x - cx, dy = y - cy;
      if (dx*dx + dy*dy > radius*radius) return [0,0,0,0];
    }
    // Background gradient
    const dist = Math.hypot(x - cx, y - cy) / radius;
    const t = Math.min(1, dist);
    const r = Math.round(accent[0] + (bg[0] - accent[0]) * t);
    const g = Math.round(accent[1] + (bg[1] - accent[1]) * t);
    const b = Math.round(accent[2] + (bg[2] - accent[2]) * t);

    // Sprite
    const sx = Math.floor((x - offset) / tile);
    const sy = Math.floor((y - offset) / tile);
    if (sx >= 0 && sx < sprite && sy >= 0 && sy < sprite) {
      const ch = ART[sy][sx];
      const col = COLORS[ch];
      if (col && col[3] !== 0) return col;
    }
    return [r, g, b, 255];
  });
}

const densities = {
  'mipmap-mdpi':    48,
  'mipmap-hdpi':    72,
  'mipmap-xhdpi':   96,
  'mipmap-xxhdpi':  144,
  'mipmap-xxxhdpi': 192,
};

const base = path.join(__dirname, '..', 'app', 'src', 'main', 'res');
for (const [dir, size] of Object.entries(densities)) {
  const dest = path.join(base, dir);
  fs.mkdirSync(dest, { recursive: true });
  fs.writeFileSync(path.join(dest, 'ic_launcher.png'), makeIcon(size, false));
  fs.writeFileSync(path.join(dest, 'ic_launcher_round.png'), makeIcon(size, true));
  console.log('Wrote', dir);
}
