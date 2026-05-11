// ui.js - UI wiring (buttons, modals, drawer)
const UI = {
  init() {
    // Tools
    document.querySelectorAll('.tool[data-tool]').forEach(btn => {
      btn.addEventListener('click', () => {
        State.tool = btn.dataset.tool;
        document.querySelectorAll('.tool[data-tool]').forEach(b => b.classList.toggle('active', b === btn));
      });
    });

    // Clear button
    document.getElementById('btn-clear').addEventListener('click', () => {
      if (!confirm('Effacer toute la frame actuelle ?')) return;
      State.pushUndo();
      CanvasMgr.clearFrame();
      Frames.updateThumb(State.currentFrame);
    });

    // Grid toggle
    document.getElementById('btn-grid-toggle').addEventListener('click', (e) => {
      State.showGrid = !State.showGrid;
      e.currentTarget.dataset.active = State.showGrid;
      CanvasMgr.drawGrid();
    });

    // Top bar buttons
    document.getElementById('btn-menu').addEventListener('click', () => this.openDrawer());
    document.getElementById('btn-undo').addEventListener('click', () => {
      if (State.undo()) { CanvasMgr.renderAll(); Frames.render(); }
    });
    document.getElementById('btn-redo').addEventListener('click', () => {
      if (State.redo()) { CanvasMgr.renderAll(); Frames.render(); }
    });
    document.getElementById('btn-play').addEventListener('click', () => Animation.toggle());
    document.getElementById('btn-export').addEventListener('click', () => Exporter.exportCurrentPNG(8));

    // Drawer close
    document.querySelectorAll('[data-close-drawer]').forEach(el => {
      el.addEventListener('click', () => this.closeDrawer());
    });
    document.querySelectorAll('[data-close-modal]').forEach(el => {
      el.addEventListener('click', e => {
        e.target.closest('.modal').hidden = true;
      });
    });

    // Drawer menu actions
    document.getElementById('menu-new').addEventListener('click', () => { this.closeDrawer(); this.openNewProject(); });
    document.getElementById('menu-save').addEventListener('click', () => {
      const name = prompt('Nom du projet :', State.projectName);
      if (name === null) return;
      if (Storage.save(name)) { Util.toast('Projet sauvegardé'); this.closeDrawer(); }
      else Util.toast('Erreur de sauvegarde');
    });
    document.getElementById('menu-load').addEventListener('click', () => { this.closeDrawer(); this.openLoadModal(); });
    document.getElementById('menu-export-png').addEventListener('click', () => { this.closeDrawer(); Exporter.exportCurrentPNG(8); });
    document.getElementById('menu-export-sheet').addEventListener('click', () => { this.closeDrawer(); Exporter.exportSpriteSheet(4); });
    document.getElementById('menu-export-gif').addEventListener('click', () => { this.closeDrawer(); Exporter.exportGIF(); });
    document.getElementById('menu-resize').addEventListener('click', () => { this.closeDrawer(); this.openResizeModal(); });

    // New project modal
    document.querySelectorAll('#modal-new .preset').forEach(btn => {
      btn.addEventListener('click', () => {
        document.getElementById('new-w').value = btn.dataset.w;
        document.getElementById('new-h').value = btn.dataset.h;
        document.querySelectorAll('#modal-new .preset').forEach(b => b.classList.toggle('active', b===btn));
      });
    });
    document.getElementById('btn-create-project').addEventListener('click', () => {
      const w = parseInt(document.getElementById('new-w').value);
      const h = parseInt(document.getElementById('new-h').value);
      if (!w || !h || w > 512 || h > 512) {
        Util.toast('Dimensions invalides (1-512)');
        return;
      }
      App.newProject(w, h);
      document.getElementById('modal-new').hidden = true;
    });

    // Resize modal
    document.getElementById('btn-do-resize').addEventListener('click', () => {
      const w = parseInt(document.getElementById('resize-w').value);
      const h = parseInt(document.getElementById('resize-h').value);
      if (!w || !h || w > 512 || h > 512) {
        Util.toast('Dimensions invalides (1-512)');
        return;
      }
      App.resizeProject(w, h);
      document.getElementById('modal-resize').hidden = true;
    });

    // Color picker
    const cp = document.getElementById('color-picker');
    cp.addEventListener('input', () => {
      State.setColor(cp.value);
      this.refreshCurrentColor();
      Palette.refresh();
    });

    // Background image
    document.getElementById('bg-input').addEventListener('change', (e) => {
      const file = e.target.files[0];
      if (!file) return;
      const img = new Image();
      img.onload = () => {
        State.bgImage = img;
        CanvasMgr.drawBg();
      };
      img.src = URL.createObjectURL(file);
    });
    document.getElementById('bg-clear').addEventListener('click', () => {
      State.bgImage = null;
      CanvasMgr.drawBg();
      document.getElementById('bg-input').value = '';
    });
    const bgOp = document.getElementById('bg-opacity');
    bgOp.addEventListener('input', () => {
      State.bgOpacity = bgOp.value / 100;
      document.getElementById('bg-opacity-val').textContent = bgOp.value + '%';
      CanvasMgr.drawBg();
    });
    const onOp = document.getElementById('onion-opacity');
    onOp.addEventListener('input', () => {
      State.onionOpacity = onOp.value / 100;
      State.onionEnabled = onOp.value > 0;
      document.getElementById('onion-opacity-val').textContent = onOp.value + '%';
      CanvasMgr.drawOnion();
    });

    // FPS
    document.getElementById('fps-input').addEventListener('change', (e) => {
      State.fps = Util.clamp(parseInt(e.target.value) || 8, 1, 60);
      e.target.value = State.fps;
    });

    // Frames buttons
    document.getElementById('btn-frame-add').addEventListener('click', () => Frames.add());
    document.getElementById('btn-frame-dup').addEventListener('click', () => Frames.duplicate());
    document.getElementById('btn-frame-del').addEventListener('click', () => {
      if (!confirm('Supprimer cette frame ?')) return;
      Frames.remove();
    });

    // Keyboard shortcuts
    document.addEventListener('keydown', e => {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
      if (e.ctrlKey || e.metaKey) {
        if (e.key === 'z' && !e.shiftKey) { e.preventDefault(); document.getElementById('btn-undo').click(); }
        else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') { e.preventDefault(); document.getElementById('btn-redo').click(); }
        else if (e.key === 's') { e.preventDefault(); document.getElementById('menu-save').click(); }
        return;
      }
      const map = { p: 'pencil', e: 'eraser', b: 'fill', i: 'eyedropper', l: 'line', r: 'rect', m: 'move' };
      const t = map[e.key.toLowerCase()];
      if (t) {
        document.querySelector(`.tool[data-tool="${t}"]`)?.click();
      }
      if (e.key === ' ') {
        document.querySelector('.tool[data-tool="move"]')?.click();
      }
    });

    this.refreshCurrentColor();
  },

  refreshCurrentColor() {
    document.getElementById('color-picker').value = State.color.slice(0, 7);
    document.getElementById('current-color-swatch').style.background = State.color;
    document.getElementById('current-color-hex').textContent = State.color;
  },

  updateStatus() {
    document.getElementById('status-size').textContent = `${State.width}×${State.height}`;
    document.getElementById('status-zoom').textContent = Math.round(State.zoom * 100) + '%';
  },

  openDrawer() { document.getElementById('drawer').hidden = false; },
  closeDrawer() { document.getElementById('drawer').hidden = true; },

  openNewProject() {
    document.getElementById('modal-new').hidden = false;
  },

  openResizeModal() {
    document.getElementById('resize-w').value = State.width;
    document.getElementById('resize-h').value = State.height;
    document.getElementById('modal-resize').hidden = false;
  },

  openLoadModal() {
    const list = document.getElementById('saved-list');
    list.innerHTML = '';
    const projects = Storage.list();
    if (!projects.length) {
      list.innerHTML = '<p class="small-note">Aucun projet sauvegardé.</p>';
    } else {
      projects.forEach(p => {
        const item = document.createElement('div');
        item.className = 'saved-item';
        item.innerHTML = `
          <div class="saved-item-info">
            <div class="saved-item-name"></div>
            <div class="saved-item-meta"></div>
          </div>
          <div class="saved-item-actions">
            <button class="btn small">Ouvrir</button>
            <button class="btn small danger">Suppr.</button>
          </div>
        `;
        item.querySelector('.saved-item-name').textContent = p.name;
        item.querySelector('.saved-item-meta').textContent =
          `${p.width}×${p.height} • ${p.frames.length} frame(s) • ${Util.formatDate(p.updatedAt)}`;
        const [openBtn, delBtn] = item.querySelectorAll('button');
        openBtn.addEventListener('click', () => {
          if (Storage.load(p.id)) {
            App.afterLoad();
            document.getElementById('modal-load').hidden = true;
            Util.toast('Projet chargé');
          }
        });
        delBtn.addEventListener('click', () => {
          if (!confirm(`Supprimer "${p.name}" ?`)) return;
          Storage.delete(p.id);
          UI.openLoadModal();
        });
        list.appendChild(item);
      });
    }
    document.getElementById('modal-load').hidden = false;
  }
};
