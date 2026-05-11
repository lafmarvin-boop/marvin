// exporter.js - PNG, sprite sheet, GIF export
const Exporter = {
  exportCurrentPNG(scale = 1) {
    const c = document.createElement('canvas');
    c.width = State.width * scale;
    c.height = State.height * scale;
    const ctx = c.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    const tmp = document.createElement('canvas');
    tmp.width = State.width; tmp.height = State.height;
    const tctx = tmp.getContext('2d');
    tctx.putImageData(new ImageData(new Uint8ClampedArray(State.currentFrameData()), State.width, State.height), 0, 0);
    ctx.drawImage(tmp, 0, 0, c.width, c.height);
    c.toBlob(b => Util.download(b, `frame_${State.currentFrame+1}.png`), 'image/png');
  },

  exportSpriteSheet(scale = 1) {
    if (!State.frames.length) return;
    const cols = Math.ceil(Math.sqrt(State.frames.length));
    const rows = Math.ceil(State.frames.length / cols);
    const fw = State.width, fh = State.height;
    const c = document.createElement('canvas');
    c.width = cols * fw * scale;
    c.height = rows * fh * scale;
    const ctx = c.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    State.frames.forEach((f, i) => {
      const tmp = document.createElement('canvas');
      tmp.width = fw; tmp.height = fh;
      tmp.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(f.data), fw, fh), 0, 0);
      const cx = (i % cols) * fw * scale;
      const cy = Math.floor(i / cols) * fh * scale;
      ctx.drawImage(tmp, cx, cy, fw*scale, fh*scale);
    });
    c.toBlob(b => Util.download(b, 'spritesheet.png'), 'image/png');
  },

  exportGIF() {
    if (typeof GIF === 'undefined') {
      Util.toast('GIF non disponible');
      return;
    }
    if (State.frames.length < 1) return;
    Util.toast('Génération du GIF...');
    const gif = new GIF({
      workers: 0,
      quality: 10,
      width: State.width,
      height: State.height,
      transparent: 0x00000000
    });
    const delay = Math.round(1000 / Util.clamp(State.fps, 1, 60));
    State.frames.forEach(f => {
      const tmp = document.createElement('canvas');
      tmp.width = State.width; tmp.height = State.height;
      tmp.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(f.data), State.width, State.height), 0, 0);
      gif.addFrame(tmp, { delay });
    });
    gif.on('finished', blob => {
      Util.download(blob, 'animation.gif');
      Util.toast('GIF prêt');
    });
    gif.render();
  }
};
