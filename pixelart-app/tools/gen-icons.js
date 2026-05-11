// gen-icons.js - generates PNG icons for the PWA using only Node stdlib (zlib).
// Produces icons/icon-192.png and icons/icon-512.png with a pixel-art style.
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

function makePng(width, height, getPixel) {
  // Build raw pixel data with filter bytes
  const raw = Buffer.alloc(width * height * 4 + height);
  for (let y = 0, p = 0; y < height; y++) {
    raw[p++] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const [r,g,b,a] = getPixel(x, y);
      raw[p++] = r; raw[p++] = g; raw[p++] = b; raw[p++] = a;
    }
  }
  const compressed = zlib.deflateSync(raw);

  const chunks = [];
  // PNG signature
  chunks.push(Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]));
  chunks.push(makeChunk('IHDR', (() => {
    const b = Buffer.alloc(13);
    b.writeUInt32BE(width, 0);
    b.writeUInt32BE(height, 4);
    b[8] = 8;  // bit depth
    b[9] = 6;  // color type RGBA
    b[10] = 0; b[11] = 0; b[12] = 0;
    return b;
  })()));
  chunks.push(makeChunk('IDAT', compressed));
  chunks.push(makeChunk('IEND', Buffer.alloc(0)));
  return Buffer.concat(chunks);
}

function makeChunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const lengthBuf = Buffer.alloc(4);
  lengthBuf.writeUInt32BE(data.length, 0);
  const crcInput = Buffer.concat([typeBuf, data]);
  const crc = crc32(crcInput);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc >>> 0, 0);
  return Buffer.concat([lengthBuf, typeBuf, data, crcBuf]);
}

const CRC_TABLE = (() => {
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
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

// 16x16 pixel-art "P" hero icon definition
// Colors: . = transparent, # = primary, * = highlight, o = outline
// Background of tile = gradient/solid
const ART = [
  "................",
  "....oooooo......",
  "...o######o.....",
  "..o##****##o....",
  "..o#*####*#o....",
  "..o#*####*#o....",
  "..o##****##o....",
  "...o######o.....",
  ".....o##o.......",
  "....o####o......",
  "...o######o.....",
  "...o#@##@#o.....",
  "...o######o.....",
  "....oo..oo......",
  "....o....o......",
  "....oo..oo......",
];

const PALETTE = {
  '.': [0,0,0,0],
  '#': [255, 85, 119, 255],  // body pink
  '*': [255, 200, 220, 255], // highlight
  'o': [30, 20, 40, 255],    // outline
  '@': [30, 20, 40, 255],    // detail (eyes)
};

function makeIcon(size) {
  // We render the 16x16 art onto a `size`x`size` canvas with a rounded background.
  const tileSize = Math.floor(size / 16);
  const pad = Math.floor((size - tileSize * 16) / 2);
  // Background: rounded "maskable" friendly indigo square
  const bg = [30, 30, 42, 255];
  const accent = [85, 102, 255, 255];

  return makePng(size, size, (x, y) => {
    // Rounded background fill (full square for "any maskable" coverage; rounding done by OS mask).
    // Add a subtle gradient
    const cx = size/2, cy = size/2;
    const dx = x - cx, dy = y - cy;
    const dist = Math.sqrt(dx*dx + dy*dy) / (size/2);
    const t = Math.min(1, dist);
    const r = Math.round(bg[0] + (accent[0] - bg[0]) * (1 - t) * 0.4);
    const g = Math.round(bg[1] + (accent[1] - bg[1]) * (1 - t) * 0.4);
    const b = Math.round(bg[2] + (accent[2] - bg[2]) * (1 - t) * 0.4);

    const ax = Math.floor((x - pad) / tileSize);
    const ay = Math.floor((y - pad) / tileSize);
    if (ax >= 0 && ax < 16 && ay >= 0 && ay < 16) {
      const ch = ART[ay][ax];
      const col = PALETTE[ch];
      if (col && col[3] !== 0) {
        return col;
      }
    }
    return [r, g, b, 255];
  });
}

const outDir = path.join(__dirname, '..', 'icons');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'icon-192.png'), makeIcon(192));
fs.writeFileSync(path.join(outDir, 'icon-512.png'), makeIcon(512));
console.log('Icons written to', outDir);
