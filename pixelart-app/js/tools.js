// tools.js - drawing tool implementations
const Tools = {
  startX: 0, startY: 0,
  lastX: 0, lastY: 0,
  active: null,

  start(x, y, e) {
    this.active = State.tool;
    this.startX = x; this.startY = y;
    this.lastX = x; this.lastY = y;
    const rgba = Util.hexToRgba(State.color);
    rgba[3] = State.alpha;

    switch (State.tool) {
      case 'pencil':
        State.pushUndo();
        CanvasMgr.applyBrush(x, y, rgba);
        CanvasMgr.drawMain();
        break;
      case 'eraser':
        State.pushUndo();
        CanvasMgr.applyBrush(x, y, [0,0,0,0]);
        CanvasMgr.drawMain();
        break;
      case 'fill':
        State.pushUndo();
        CanvasMgr.floodFill(x, y, rgba);
        CanvasMgr.drawMain();
        break;
      case 'eyedropper':
        this.pick(x, y);
        break;
      case 'line':
      case 'rect':
      case 'rectfill':
        CanvasMgr.clearPreview();
        this.previewShape(this.active, this.startX, this.startY, x, y, rgba);
        break;
    }
  },

  move(x, y, e) {
    if (x === this.lastX && y === this.lastY) return;
    const rgba = Util.hexToRgba(State.color);
    rgba[3] = State.alpha;
    switch (this.active) {
      case 'pencil':
        for (const [px, py] of Util.bresenhamLine(this.lastX, this.lastY, x, y)) {
          CanvasMgr.applyBrush(px, py, rgba);
        }
        CanvasMgr.drawMain();
        break;
      case 'eraser':
        for (const [px, py] of Util.bresenhamLine(this.lastX, this.lastY, x, y)) {
          CanvasMgr.applyBrush(px, py, [0,0,0,0]);
        }
        CanvasMgr.drawMain();
        break;
      case 'eyedropper':
        this.pick(x, y);
        break;
      case 'line':
      case 'rect':
      case 'rectfill':
        CanvasMgr.clearPreview();
        this.previewShape(this.active, this.startX, this.startY, x, y, rgba);
        break;
    }
    this.lastX = x; this.lastY = y;
  },

  end(e) {
    const rgba = Util.hexToRgba(State.color);
    rgba[3] = State.alpha;
    switch (this.active) {
      case 'line':
        State.pushUndo();
        for (const [px, py] of Util.bresenhamLine(this.startX, this.startY, this.lastX, this.lastY)) {
          CanvasMgr.applyBrush(px, py, rgba);
        }
        CanvasMgr.drawMain();
        CanvasMgr.clearPreview();
        break;
      case 'rect':
        State.pushUndo();
        this.drawRect(this.startX, this.startY, this.lastX, this.lastY, rgba, false);
        CanvasMgr.drawMain();
        CanvasMgr.clearPreview();
        break;
      case 'rectfill':
        State.pushUndo();
        this.drawRect(this.startX, this.startY, this.lastX, this.lastY, rgba, true);
        CanvasMgr.drawMain();
        CanvasMgr.clearPreview();
        break;
    }
    this.active = null;
    // Refresh frame thumbnail
    Frames.updateThumb(State.currentFrame);
  },

  pick(x, y) {
    const [r,g,b,a] = CanvasMgr.getPixel(x, y);
    if (a === 0) return;
    const hex = Util.rgbaToHex([r,g,b]);
    State.setColor(hex);
    Palette.refresh();
    UI.refreshCurrentColor();
  },

  previewShape(tool, x0, y0, x1, y1, rgba) {
    if (tool === 'line') {
      for (const [px, py] of Util.bresenhamLine(x0, y0, x1, y1)) {
        CanvasMgr.drawPreviewPixel(px, py, rgba);
      }
    } else if (tool === 'rect' || tool === 'rectfill') {
      const xmin = Math.min(x0,x1), xmax = Math.max(x0,x1);
      const ymin = Math.min(y0,y1), ymax = Math.max(y0,y1);
      if (tool === 'rectfill') {
        for (let y = ymin; y <= ymax; y++)
          for (let x = xmin; x <= xmax; x++)
            CanvasMgr.drawPreviewPixel(x, y, rgba);
      } else {
        for (let x = xmin; x <= xmax; x++) {
          CanvasMgr.drawPreviewPixel(x, ymin, rgba);
          CanvasMgr.drawPreviewPixel(x, ymax, rgba);
        }
        for (let y = ymin; y <= ymax; y++) {
          CanvasMgr.drawPreviewPixel(xmin, y, rgba);
          CanvasMgr.drawPreviewPixel(xmax, y, rgba);
        }
      }
    }
  },

  drawRect(x0, y0, x1, y1, rgba, fill) {
    const xmin = Math.min(x0,x1), xmax = Math.max(x0,x1);
    const ymin = Math.min(y0,y1), ymax = Math.max(y0,y1);
    if (fill) {
      for (let y = ymin; y <= ymax; y++)
        for (let x = xmin; x <= xmax; x++)
          CanvasMgr.setPixel(x, y, rgba[0], rgba[1], rgba[2], rgba[3]);
    } else {
      for (let x = xmin; x <= xmax; x++) {
        CanvasMgr.setPixel(x, ymin, rgba[0], rgba[1], rgba[2], rgba[3]);
        CanvasMgr.setPixel(x, ymax, rgba[0], rgba[1], rgba[2], rgba[3]);
      }
      for (let y = ymin; y <= ymax; y++) {
        CanvasMgr.setPixel(xmin, y, rgba[0], rgba[1], rgba[2], rgba[3]);
        CanvasMgr.setPixel(xmax, y, rgba[0], rgba[1], rgba[2], rgba[3]);
      }
    }
  }
};
