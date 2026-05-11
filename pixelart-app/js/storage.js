// storage.js - localStorage save/load
const Storage = {
  KEY: 'pixelhero_projects_v1',

  list() {
    try {
      return JSON.parse(localStorage.getItem(this.KEY) || '[]');
    } catch (e) { return []; }
  },

  save(name) {
    if (!State.frames.length) return false;
    const projects = this.list();
    const id = State.projectId || ('p_' + Date.now());
    const data = {
      id,
      name: name || State.projectName || 'Sans titre',
      width: State.width,
      height: State.height,
      fps: State.fps,
      palette: State.palette,
      recentColors: State.recentColors,
      // Frames: encode as base64-ish? Use array of numbers via base64 of uint8.
      frames: State.frames.map(f => ({
        id: f.id,
        data: this.encodeBytes(f.data)
      })),
      updatedAt: Date.now()
    };
    const idx = projects.findIndex(p => p.id === id);
    if (idx >= 0) projects[idx] = data;
    else projects.unshift(data);
    try {
      localStorage.setItem(this.KEY, JSON.stringify(projects));
      State.projectId = id;
      State.projectName = data.name;
      State.dirty = false;
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  },

  load(id) {
    const projects = this.list();
    const p = projects.find(x => x.id === id);
    if (!p) return false;
    State.width = p.width;
    State.height = p.height;
    State.fps = p.fps || 8;
    State.palette = p.palette || State.palette;
    State.recentColors = p.recentColors || [];
    State.frames = p.frames.map(f => ({
      id: f.id,
      data: this.decodeBytes(f.data, p.width * p.height * 4)
    }));
    State.currentFrame = 0;
    State.projectId = p.id;
    State.projectName = p.name;
    State.undoStack = [];
    State.redoStack = [];
    State.bgImage = null;
    return true;
  },

  delete(id) {
    const projects = this.list();
    const next = projects.filter(p => p.id !== id);
    localStorage.setItem(this.KEY, JSON.stringify(next));
  },

  encodeBytes(arr) {
    // Convert Uint8ClampedArray to base64
    let bin = '';
    const chunk = 0x8000;
    for (let i = 0; i < arr.length; i += chunk) {
      bin += String.fromCharCode.apply(null, arr.subarray(i, i + chunk));
    }
    return btoa(bin);
  },

  decodeBytes(b64, expectedLen) {
    const bin = atob(b64);
    const arr = new Uint8ClampedArray(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    if (expectedLen && arr.length !== expectedLen) {
      const fixed = new Uint8ClampedArray(expectedLen);
      fixed.set(arr.subarray(0, Math.min(arr.length, expectedLen)));
      return fixed;
    }
    return arr;
  }
};
