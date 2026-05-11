// state.js - global state and frame management
// A frame is { id, imageData: Uint8ClampedArray (w*h*4) }
// State holds frames array, current frame index, project metadata.

const State = {
  width: 32,
  height: 32,
  frames: [],
  currentFrame: 0,
  // Drawing
  color: '#ff5577',
  alpha: 255,
  tool: 'pencil',
  brushSize: 1,
  showGrid: true,
  // Onion skin
  onionOpacity: 0.3,
  onionEnabled: true,
  // Background reference
  bgImage: null, // HTMLImageElement
  bgOpacity: 0.5,
  // Camera (view)
  zoom: 1,
  panX: 0,
  panY: 0,
  // Animation
  fps: 8,
  isPlaying: false,
  // Undo/redo per project (stack of {frameIndex, snapshot})
  undoStack: [],
  redoStack: [],
  maxUndo: 80,
  // Palette
  palette: [
    '#000000','#ffffff','#7f7f7f','#bcbcbc',
    '#ff0000','#ff7f00','#ffff00','#7fff00',
    '#00ff00','#00ff7f','#00ffff','#007fff',
    '#0000ff','#7f00ff','#ff00ff','#ff007f',
    '#8b4513','#a0522d','#cd853f','#deb887',
    '#ff5577','#5566ff','#22cc88','#ffaa33'
  ],
  recentColors: [],
  // Selection/transient
  dirty: false,
  projectName: 'Sans titre',
  projectId: null,

  // ---- Frame helpers ----
  createBlankFrameData() {
    return new Uint8ClampedArray(this.width * this.height * 4);
  },
  newFrame() {
    return { id: 'f_' + Math.random().toString(36).slice(2,9), data: this.createBlankFrameData() };
  },
  currentFrameData() {
    return this.frames[this.currentFrame]?.data;
  },

  // ---- Init / reset ----
  initProject(w, h) {
    this.width = w;
    this.height = h;
    this.frames = [this.newFrame()];
    this.currentFrame = 0;
    this.undoStack = [];
    this.redoStack = [];
    this.bgImage = null;
    this.zoom = 1;
    this.panX = 0;
    this.panY = 0;
    this.projectId = 'p_' + Date.now();
    this.dirty = false;
  },

  // ---- Undo / Redo ----
  pushUndo() {
    if (!this.frames[this.currentFrame]) return;
    const snap = {
      frameIndex: this.currentFrame,
      data: new Uint8ClampedArray(this.frames[this.currentFrame].data)
    };
    this.undoStack.push(snap);
    if (this.undoStack.length > this.maxUndo) this.undoStack.shift();
    this.redoStack = [];
    this.dirty = true;
  },
  undo() {
    if (!this.undoStack.length) return false;
    const last = this.undoStack.pop();
    const frame = this.frames[last.frameIndex];
    if (!frame) return false;
    this.redoStack.push({ frameIndex: last.frameIndex, data: new Uint8ClampedArray(frame.data) });
    frame.data.set(last.data);
    this.currentFrame = last.frameIndex;
    return true;
  },
  redo() {
    if (!this.redoStack.length) return false;
    const last = this.redoStack.pop();
    const frame = this.frames[last.frameIndex];
    if (!frame) return false;
    this.undoStack.push({ frameIndex: last.frameIndex, data: new Uint8ClampedArray(frame.data) });
    frame.data.set(last.data);
    this.currentFrame = last.frameIndex;
    return true;
  },

  // ---- Color ----
  setColor(hex) {
    this.color = hex;
    // Track recent
    const idx = this.recentColors.indexOf(hex);
    if (idx !== -1) this.recentColors.splice(idx, 1);
    this.recentColors.unshift(hex);
    if (this.recentColors.length > 16) this.recentColors.pop();
  }
};
