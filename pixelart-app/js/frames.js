// frames.js - frames list & thumbnails
const Frames = {
  listEl: null,
  thumbCanvases: new Map(),

  init() {
    this.listEl = document.getElementById('frames-list');
  },

  render() {
    this.listEl.innerHTML = '';
    this.thumbCanvases.clear();
    State.frames.forEach((frame, idx) => {
      const item = document.createElement('div');
      item.className = 'frame-item' + (idx === State.currentFrame ? ' active' : '');
      item.dataset.idx = idx;

      const thumb = document.createElement('canvas');
      thumb.className = 'frame-thumb';
      thumb.width = State.width;
      thumb.height = State.height;
      this.thumbCanvases.set(idx, thumb);
      this.paintThumb(idx);

      const info = document.createElement('div');
      info.className = 'frame-info';
      info.textContent = `#${idx+1}`;

      const actions = document.createElement('div');
      actions.className = 'frame-actions';
      const upBtn = document.createElement('button');
      upBtn.textContent = '▲';
      upBtn.title = 'Monter';
      upBtn.addEventListener('click', e => { e.stopPropagation(); this.move(idx, -1); });
      const dnBtn = document.createElement('button');
      dnBtn.textContent = '▼';
      dnBtn.title = 'Descendre';
      dnBtn.addEventListener('click', e => { e.stopPropagation(); this.move(idx, +1); });
      actions.append(upBtn, dnBtn);

      item.append(thumb, info, actions);
      item.addEventListener('click', () => this.select(idx));
      this.listEl.appendChild(item);
    });
  },

  paintThumb(idx) {
    const c = this.thumbCanvases.get(idx);
    if (!c) return;
    const frame = State.frames[idx];
    if (!frame) return;
    const ctx = c.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, c.width, c.height);
    const img = new ImageData(new Uint8ClampedArray(frame.data), State.width, State.height);
    ctx.putImageData(img, 0, 0);
  },

  updateThumb(idx) {
    this.paintThumb(idx);
  },

  select(idx) {
    if (idx < 0 || idx >= State.frames.length) return;
    State.currentFrame = idx;
    CanvasMgr.renderAll();
    this.render();
  },

  add() {
    State.frames.push(State.newFrame());
    State.currentFrame = State.frames.length - 1;
    this.render();
    CanvasMgr.renderAll();
  },

  duplicate() {
    const f = State.frames[State.currentFrame];
    if (!f) return;
    const copy = { id: 'f_' + Math.random().toString(36).slice(2,9), data: new Uint8ClampedArray(f.data) };
    State.frames.splice(State.currentFrame + 1, 0, copy);
    State.currentFrame++;
    this.render();
    CanvasMgr.renderAll();
  },

  remove() {
    if (State.frames.length <= 1) {
      Util.toast('Au moins une frame requise');
      return;
    }
    State.frames.splice(State.currentFrame, 1);
    State.currentFrame = Math.max(0, State.currentFrame - 1);
    this.render();
    CanvasMgr.renderAll();
  },

  move(idx, dir) {
    const newIdx = idx + dir;
    if (newIdx < 0 || newIdx >= State.frames.length) return;
    const [item] = State.frames.splice(idx, 1);
    State.frames.splice(newIdx, 0, item);
    if (State.currentFrame === idx) State.currentFrame = newIdx;
    else if (State.currentFrame === newIdx) State.currentFrame = idx;
    this.render();
    CanvasMgr.renderAll();
  }
};
