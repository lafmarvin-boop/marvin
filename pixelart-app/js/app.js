// app.js - entry point and orchestration
const App = {
  init() {
    CanvasMgr.init();
    Palette.init();
    Frames.init();
    UI.init();

    // Start with a default 32x32 project
    this.newProject(32, 32, /*silent=*/true);

    // Show "New Project" modal on first launch nice prompt
    setTimeout(() => {
      if (!localStorage.getItem('pixelhero_seen')) {
        localStorage.setItem('pixelhero_seen', '1');
        UI.openNewProject();
      }
    }, 300);

    // Warn before unload if dirty
    window.addEventListener('beforeunload', e => {
      if (State.dirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    });
  },

  newProject(w, h, silent) {
    State.initProject(w, h);
    CanvasMgr.setProjectSize(w, h);
    CanvasMgr.renderAll();
    Frames.render();
    UI.updateStatus();
    const help = document.getElementById('canvas-help');
    if (help) help.hidden = true;
    if (!silent) Util.toast(`Nouveau projet ${w}×${h}`);
  },

  resizeProject(newW, newH) {
    const oldW = State.width, oldH = State.height;
    State.frames.forEach(f => {
      const newData = new Uint8ClampedArray(newW * newH * 4);
      const copyW = Math.min(oldW, newW);
      const copyH = Math.min(oldH, newH);
      for (let y = 0; y < copyH; y++) {
        for (let x = 0; x < copyW; x++) {
          const oldI = (y * oldW + x) * 4;
          const newI = (y * newW + x) * 4;
          newData[newI] = f.data[oldI];
          newData[newI+1] = f.data[oldI+1];
          newData[newI+2] = f.data[oldI+2];
          newData[newI+3] = f.data[oldI+3];
        }
      }
      f.data = newData;
    });
    State.width = newW;
    State.height = newH;
    State.undoStack = [];
    State.redoStack = [];
    CanvasMgr.setProjectSize(newW, newH);
    CanvasMgr.renderAll();
    Frames.render();
    UI.updateStatus();
    Util.toast(`Redimensionné à ${newW}×${newH}`);
  },

  afterLoad() {
    CanvasMgr.setProjectSize(State.width, State.height);
    CanvasMgr.renderAll();
    Frames.render();
    Palette.render();
    UI.refreshCurrentColor();
    UI.updateStatus();
    document.getElementById('fps-input').value = State.fps;
    const help = document.getElementById('canvas-help');
    if (help) help.hidden = true;
  }
};

document.addEventListener('DOMContentLoaded', () => App.init());
