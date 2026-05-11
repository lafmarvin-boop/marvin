// gen-launcher-icon.js - generates ic_launcher_foreground.xml from ASCII pixel art.
// Produces a vector drawable with one <path> per color (compact).
const fs = require('fs');
const path = require('path');

// 16x16 pixel hero (chibi). Legend below.
// o = outline (dark)
// # = body skin (peach)
// * = body highlight
// @ = eyes (very dark)
// = = shirt (purple/indigo)
// + = shirt highlight
// $ = shoes
// ~ = sword blade (white/silver)
// & = sword grip (gold)
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

// Palette (RGB hex without #)
const COLORS = {
  'o': '1A1428', // outline
  '#': 'F2C09B', // skin
  '*': 'FFD9B8', // skin highlight
  '@': '1A1428', // eyes (same as outline)
  '=': '5566FF', // shirt
  '+': '8899FF', // shirt highlight
  '$': '3A2A22', // shoes
  '~': 'E0E5F0', // sword blade
  '&': 'FFC347', // sword pommel
};

const SIZE = 16;
const VIEWPORT = 108;
const SAFE = 72; // recommended safe area for adaptive icons
const PIXEL = SAFE / SIZE; // 4.5
const OFFSET = (VIEWPORT - SAFE) / 2; // 18

// Group pixels by color, build path data using rectangles
const groups = {};
for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    const ch = ART[y][x];
    if (ch === '.' || ch === ' ') continue;
    const color = COLORS[ch];
    if (!color) continue;
    (groups[color] ||= []).push([x, y]);
  }
}

let paths = '';
for (const [color, cells] of Object.entries(groups)) {
  // Build path data: for each cell, "M x y h w v h h -w z"
  let d = '';
  for (const [x, y] of cells) {
    const px = (OFFSET + x * PIXEL).toFixed(3);
    const py = (OFFSET + y * PIXEL).toFixed(3);
    const pw = PIXEL.toFixed(3);
    d += `M${px},${py}h${pw}v${pw}h-${pw}z `;
  }
  paths += `    <path android:fillColor="#${color}" android:pathData="${d.trim()}" />\n`;
}

const xml = `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${paths}</vector>
`;

const outPath = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'drawable', 'ic_launcher_foreground.xml');
fs.writeFileSync(outPath, xml);
console.log('Written:', outPath, '(' + xml.length + ' bytes)');
