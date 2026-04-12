// /ui/overlay.js

export class Overlay {
  constructor() {
    this.container = document.createElement("div");

    this.container.style.position = "absolute";
    this.container.style.top = "10px";
    this.container.style.left = "10px";
    this.container.style.color = "#00ffcc";
    this.container.style.fontFamily = "monospace";
    this.container.style.fontSize = "12px";
    this.container.style.padding = "10px";
    this.container.style.background = "rgba(0,0,0,0.5)";
    this.container.style.border = "1px solid rgba(0,255,204,0.3)";
    this.container.style.borderRadius = "6px";
    this.container.style.pointerEvents = "none";

    document.body.appendChild(this.container);

    this.data = {
      points: 0,
      velocity: [0, 0, 0],
      fps: 0,
      status: "idle",
    };

    this.lastFrameTime = performance.now();
    this.frameCount = 0;
  }

  /**
   * Update runtime metrics
   */
  update(metrics = {}) {
    if (metrics.points !== undefined) {
      this.data.points = metrics.points;
    }

    if (metrics.velocity) {
      this.data.velocity = metrics.velocity;
    }

    if (metrics.status) {
      this.data.status = metrics.status;
    }

    this._updateFPS();
    this._render();
  }

  /**
   * Internal FPS calculator
   */
  _updateFPS() {
    const now = performance.now();
    this.frameCount++;

    if (now - this.lastFrameTime >= 1000) {
      this.data.fps = Math.round(
        (this.frameCount * 1000) / (now - this.lastFrameTime)
      );

      this.frameCount = 0;
      this.lastFrameTime = now;
    }
  }

  /**
   * Render overlay UI
   */
  _render() {
    const v = this.data.velocity;

    this.container.innerHTML = `
      <div>📍 LineTrace Web Host</div>
      <div>──────────────</div>
      <div>Points: ${this.data.points}</div>
      <div>FPS: ${this.data.fps}</div>
      <div>Status: ${this.data.status}</div>
      <div>Velocity:</div>
      <div>  x: ${v[0].toFixed(3)}</div>
      <div>  y: ${v[1].toFixed(3)}</div>
      <div>  z: ${v[2].toFixed(3)}</div>
    `;
  }

  /**
   * Show warning state (drift, disconnect, etc.)
   */
  setWarning(message) {
    this.container.style.border = "1px solid red";
    this.container.innerHTML += `<div style="color:red;">⚠ ${message}</div>`;
  }

  /**
   * Reset UI state
   */
  reset() {
    this.data = {
      points: 0,
      velocity: [0, 0, 0],
      fps: 0,
      status: "reset",
    };
  }
}
