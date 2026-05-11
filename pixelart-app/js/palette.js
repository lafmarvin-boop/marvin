// palette.js - palette and recent colors UI
const Palette = {
  paletteEl: null,
  recentEl: null,

  init() {
    this.paletteEl = document.getElementById('palette');
    this.recentEl = document.getElementById('recent-colors');
    this.render();
  },

  render() {
    this.paletteEl.innerHTML = '';
    State.palette.forEach(hex => {
      const sw = document.createElement('div');
      sw.className = 'swatch';
      sw.style.background = hex;
      sw.title = hex;
      sw.dataset.hex = hex;
      if (hex.toLowerCase() === State.color.toLowerCase()) sw.classList.add('selected');
      sw.addEventListener('click', () => {
        State.setColor(hex);
        Palette.refresh();
        UI.refreshCurrentColor();
      });
      this.paletteEl.appendChild(sw);
    });
    this.renderRecent();
  },

  renderRecent() {
    this.recentEl.innerHTML = '';
    State.recentColors.forEach(hex => {
      const sw = document.createElement('div');
      sw.className = 'swatch';
      sw.style.background = hex;
      sw.title = hex;
      sw.dataset.hex = hex;
      if (hex.toLowerCase() === State.color.toLowerCase()) sw.classList.add('selected');
      sw.addEventListener('click', () => {
        State.setColor(hex);
        Palette.refresh();
        UI.refreshCurrentColor();
      });
      this.recentEl.appendChild(sw);
    });
  },

  refresh() {
    document.querySelectorAll('#palette .swatch').forEach(el => {
      el.classList.toggle('selected', el.dataset.hex?.toLowerCase() === State.color.toLowerCase());
    });
    this.renderRecent();
  }
};
