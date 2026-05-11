// canvas.js - rendering & input handling
const CanvasMgr = {
  // Element refs
  wrapper: null,
  area: null,
  bgCanvas: null, onionCanvas: null, mainCanvas: null, gridCanvas: null, previewCanvas: null,
  bgCtx: null, onionCtx: null, mainCtx: null, gridCtx: null, previewCtx: null,

  // Display sizing
  displayScale: 16, // CSS px per pixel art pixel - dynamic
  cssWidth: 0, cssHeight: 0,

  // Active pointers (for pinch)
  pointers: new Map(),
  isDrawing: false,
  drawingPointerId: null,
  startPinchDist: 0,
  startPinchZoom: 1,
  startPinchCenter: null,

  init() {
    this.area = document.getElementById('canvas-area');
    this.wrapper = document.getElementById('canvas-wrapper');
    this.bgCanvas = document.getElementById('bg-canvas');
    this.onionCanvas = document.getElementById('onion-canvas');
    this.mainCanvas = document.getElementById('main-canvas');
    this.gridCanvas = document.getElementById('grid-canvas');
    this.previewCanvas = document.getElementById('preview-canvas');

    this.bgCtx = this.bgCanvas.getContext('2d');
    this.onionCtx = this.onionCanvas.getContext('2d');
    this.mainCtx = this.mainCanvas.getContext('2d');
    this.gridCtx = this.gridCanvas.getContext('2d');
    this.previewCtx = this.previewCanvas.getContext('2d');

    [this.bgCtx, this.onionCtx, this.mainCtx, this.gridCtx, this.previewCtx].forEach(ctx => {
      ctx.imageSmoothingEnabled = false;
    });

    this.attachInput();
    window.addEventListener('resize', () => this.fitToView());
  },

  // Resize backing canvases to pixel-art size; CSS handles display scale
  setProjectSize(w, h) {
    [this.bgCanvas, this.onionCanvas, this.mainCanvas, this.gridCanvas, this.previewCanvas].forEach(c => {
      c.width = w; c.height = h;
    });
    this.fitToView();
  },

  fitToView() {
    const W = State.width, H = State.height;
    if (!W || !H) return;
    const rect = this.area.getBoundingClientRect();
    const pad = 24;
    const scale = Math.max(1, Math.floor(Math.min((rect.width - pad) / W, (rect.height - pad) / H)));
    this.displayScale = scale * State.zoom;
    this.cssWidth = W * this.displayScale;
    this.cssHeight = H * this.displayScale;
    this.wrapper.style.width = this.cssWidth + 'px';
    this.wrapper.style.height = this.cssHeight + 'px';
    // center via translate
    const tx = (rect.width - this.cssWidth)/2 + State.panX;
    const ty = (rect.height - this.cssHeight)/2 + State.panY;
    this.wrapper.style.transform = `translate(${tx}px, ${ty}px)`;
    this.drawGrid();
    this.renderAll();
    UI.updateStatus();
  },

  renderAll() {
    this.drawMain();
    this.drawOnion();
    this.drawBg();
  },

  drawMain() {
    const frame = State.frames[State.currentFrame];
    if (!frame) return;
    const img = new ImageData(frame.data, State.width, State.height);
    this.mainCtx.clearRect(0, 0, State.width, State.height);
    this.mainCtx.putImageData(img, 0, 0);
  },

  drawOnion() {
    this.onionCtx.clearRect(0, 0, State.width, State.height);
    if (!State.onionEnabled || State.onionOpacity <= 0 || State.frames.length < 2) return;
    const prev = State.frames[State.currentFrame - 1];
    if (prev) {
      const img = new ImageData(new Uint8ClampedArray(prev.data), State.width, State.height);
      // Tint blueish for visibility - keep as-is but rely on layer opacity
      this.onionCtx.putImageData(img, 0, 0);
    }
    this.onionCanvas.style.opacity = State.onionOpacity;
  },

  drawBg() {
    this.bgCtx.clearRect(0, 0, State.width, State.height);
    if (State.bgImage) {
      // Fit image to canvas keeping aspect
      const iw = State.bgImage.naturalWidth;
      const ih = State.bgImage.naturalHeight;
      const ratio = Math.min(State.width / iw, State.height / ih);
      const dw = iw * ratio;
      const dh = ih * ratio;
      const dx = (State.width - dw) / 2;
      const dy = (State.height - dh) / 2;
      this.bgCtx.drawImage(State.bgImage, dx, dy, dw, dh);
    }
    this.bgCanvas.style.opacity = State.bgImage ? State.bgOpacity : 0;
  },

  drawGrid() {
    const ctx = this.gridCtx;
    ctx.clearRect(0,0, State.width, State.height);
    if (!State.showGrid) return;
    // The grid is drawn at pixel resolution; per-pixel borders rendered via CSS scaling.
    // We draw the grid on a higher-resolution overlay so lines look crisp.
    // To stay simple and correct: skip drawing here, use CSS background grid via gridCanvas displayed scaled? We'll use CSS pseudo-grid via background on wrapper.
    // Alternative: only show grid above a zoom threshold using a separate hidden overlay.
    // We'll draw a real grid on the gridCanvas at higher res:
    const scale = Math.max(1, Math.floor(this.displayScale));
    if (scale < 6) {
      this.gridCanvas.style.display = 'none';
      return;
    }
    this.gridCanvas.style.display = '';
    // Upscale gridCanvas for crisp lines
    const w = State.width * scale;
    const h = State.height * scale;
    if (this.gridCanvas.width !== w || this.gridCanvas.height !== h) {
      this.gridCanvas.width = w;
      this.gridCanvas.height = h;
    }
    ctx.clearRect(0,0,w,h);
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let x = 0; x <= State.width; x++) {
      ctx.moveTo(x*scale + 0.5, 0);
      ctx.lineTo(x*scale + 0.5, h);
    }
    for (let y = 0; y <= State.height; y++) {
      ctx.moveTo(0, y*scale + 0.5);
      ctx.lineTo(w, y*scale + 0.5);
    }
    ctx.stroke();
    // Major grid every 8
    ctx.strokeStyle = 'rgba(165,180,255,0.18)';
    ctx.beginPath();
    for (let x = 0; x <= State.width; x += 8) {
      ctx.moveTo(x*scale + 0.5, 0);
      ctx.lineTo(x*scale + 0.5, h);
    }
    for (let y = 0; y <= State.height; y += 8) {
      ctx.moveTo(0, y*scale + 0.5);
      ctx.lineTo(w, y*scale + 0.5);
    }
    ctx.stroke();
  },

  // Convert client (x,y) to pixel coordinates
  clientToPixel(clientX, clientY) {
    const rect = this.wrapper.getBoundingClientRect();
    const px = Math.floor((clientX - rect.left) / this.displayScale);
    const py = Math.floor((clientY - rect.top) / this.displayScale);
    return [px, py];
  },

  attachInput() {
    const el = this.area;
    el.addEventListener('pointerdown', e => this.onPointerDown(e));
    el.addEventListener('pointermove', e => this.onPointerMove(e));
    el.addEventListener('pointerup', e => this.onPointerUp(e));
    el.addEventListener('pointercancel', e => this.onPointerUp(e));
    el.addEventListener('wheel', e => this.onWheel(e), { passive: false });
    // Prevent context menu on long press
    el.addEventListener('contextmenu', e => e.preventDefault());
  },

  onPointerDown(e) {
    if (!State.frames.length) return;
    e.preventDefault();
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (this.pointers.size === 2) {
      // Begin pinch
      this.isDrawing = false;
      const pts = [...this.pointers.values()];
      this.startPinchDist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      this.startPinchZoom = State.zoom;
      this.startPinchCenter = {
        x: (pts[0].x + pts[1].x) / 2,
        y: (pts[0].y + pts[1].y) / 2,
        panX: State.panX,
        panY: State.panY
      };
      return;
    }
    if (this.pointers.size === 1) {
      const [px, py] = this.clientToPixel(e.clientX, e.clientY);
      // If 'move' tool: pan only
      if (State.tool === 'move') {
        this.drawingPointerId = e.pointerId;
        this.panStart = { x: e.clientX, y: e.clientY, panX: State.panX, panY: State.panY };
        return;
      }
      this.drawingPointerId = e.pointerId;
      this.isDrawing = true;
      Tools.start(px, py, e);
    }
  },

  onPointerMove(e) {
    if (!this.pointers.has(e.pointerId)) return;
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

    if (this.pointers.size === 2 && this.startPinchCenter) {
      const pts = [...this.pointers.values()];
      const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      const factor = dist / this.startPinchDist;
      const newZoom = Util.clamp(this.startPinchZoom * factor, 0.25, 32);
      State.zoom = newZoom;
      // Pan from center movement
      const center = { x: (pts[0].x + pts[1].x)/2, y: (pts[0].y + pts[1].y)/2 };
      State.panX = this.startPinchCenter.panX + (center.x - this.startPinchCenter.x);
      State.panY = this.startPinchCenter.panY + (center.y - this.startPinchCenter.y);
      this.fitToView();
      return;
    }
    if (this.pointers.size === 1 && this.drawingPointerId === e.pointerId) {
      if (State.tool === 'move' && this.panStart) {
        State.panX = this.panStart.panX + (e.clientX - this.panStart.x);
        State.panY = this.panStart.panY + (e.clientY - this.panStart.y);
        this.fitToView();
        return;
      }
      if (this.isDrawing) {
        const [px, py] = this.clientToPixel(e.clientX, e.clientY);
        Tools.move(px, py, e);
      }
    }
  },

  onPointerUp(e) {
    this.pointers.delete(e.pointerId);
    if (this.drawingPointerId === e.pointerId) {
      if (this.isDrawing) {
        Tools.end(e);
      }
      this.isDrawing = false;
      this.drawingPointerId = null;
      this.panStart = null;
    }
    if (this.pointers.size < 2) {
      this.startPinchCenter = null;
    }
  },

  onWheel(e) {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.15 : 1/1.15;
    State.zoom = Util.clamp(State.zoom * factor, 0.25, 32);
    this.fitToView();
  },

  // Set a single pixel into current frame
  setPixel(x, y, r, g, b, a) {
    if (x < 0 || y < 0 || x >= State.width || y >= State.height) return;
    const data = State.currentFrameData();
    if (!data) return;
    const i = (y * State.width + x) * 4;
    data[i] = r; data[i+1] = g; data[i+2] = b; data[i+3] = a;
  },

  getPixel(x, y) {
    if (x < 0 || y < 0 || x >= State.width || y >= State.height) return [0,0,0,0];
    const data = State.currentFrameData();
    const i = (y * State.width + x) * 4;
    return [data[i], data[i+1], data[i+2], data[i+3]];
  },

  // Flood fill (4-connected)
  floodFill(x, y, fillRgba) {
    const data = State.currentFrameData();
    if (!data) return;
    const W = State.width, H = State.height;
    if (x < 0 || y < 0 || x >= W || y >= H) return;
    const idx0 = (y * W + x) * 4;
    const target = [data[idx0], data[idx0+1], data[idx0+2], data[idx0+3]];
    const fill = fillRgba;
    if (target[0]===fill[0] && target[1]===fill[1] && target[2]===fill[2] && target[3]===fill[3]) return;
    const stack = [[x, y]];
    while (stack.length) {
      const [cx, cy] = stack.pop();
      if (cx < 0 || cy < 0 || cx >= W || cy >= H) continue;
      const i = (cy * W + cx) * 4;
      if (data[i]===target[0] && data[i+1]===target[1] && data[i+2]===target[2] && data[i+3]===target[3]) {
        data[i] = fill[0]; data[i+1] = fill[1]; data[i+2] = fill[2]; data[i+3] = fill[3];
        stack.push([cx+1, cy], [cx-1, cy], [cx, cy+1], [cx, cy-1]);
      }
    }
  },

  // Draw a preview overlay (for line/rect tools)
  clearPreview() {
    this.previewCtx.clearRect(0, 0, State.width, State.height);
  },
  drawPreviewPixel(x, y, rgba) {
    if (x < 0 || y < 0 || x >= State.width || y >= State.height) return;
    this.previewCtx.fillStyle = `rgba(${rgba[0]},${rgba[1]},${rgba[2]},${rgba[3]/255})`;
    this.previewCtx.fillRect(x, y, 1, 1);
  },

  // Clear current frame
  clearFrame() {
    const data = State.currentFrameData();
    if (data) data.fill(0);
    this.drawMain();
  },

  // Apply pixels around brush
  applyBrush(x, y, rgba) {
    const size = State.brushSize;
    if (size <= 1) {
      this.setPixel(x, y, rgba[0], rgba[1], rgba[2], rgba[3]);
      return;
    }
    const r = Math.floor(size/2);
    for (let dy = -r; dy <= r; dy++) {
      for (let dx = -r; dx <= r; dx++) {
        this.setPixel(x+dx, y+dy, rgba[0], rgba[1], rgba[2], rgba[3]);
      }
    }
  }
};
