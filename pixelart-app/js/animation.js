// animation.js - playback preview
const Animation = {
  timer: null,
  playIndex: 0,
  savedFrame: 0,

  toggle() {
    if (State.isPlaying) this.stop();
    else this.play();
  },

  play() {
    if (State.frames.length < 2) {
      Util.toast('Ajoutez au moins 2 frames pour animer');
      return;
    }
    State.isPlaying = true;
    this.savedFrame = State.currentFrame;
    this.playIndex = 0;
    document.body.classList.add('playing');
    const tick = () => {
      State.currentFrame = this.playIndex;
      CanvasMgr.drawMain();
      this.playIndex = (this.playIndex + 1) % State.frames.length;
    };
    tick();
    const fps = Util.clamp(State.fps, 1, 60);
    this.timer = setInterval(tick, 1000 / fps);
  },

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    State.isPlaying = false;
    State.currentFrame = this.savedFrame;
    document.body.classList.remove('playing');
    CanvasMgr.renderAll();
    Frames.render();
  }
};
