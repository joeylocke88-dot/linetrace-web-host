export class Smoother {
  constructor(alpha = 0.2) {
    this.alpha = alpha;
    this.initialized = false;

    this.prev = { x: 0, y: 0, z: 0 };
  }

  /**
   * Stream one point at a time (IMPORTANT)
   */
  update(point) {
    if (!this.initialized) {
      this.prev = { ...point };
      this.initialized = true;
      return this.prev;
    }

    const a = this.alpha;

    this.prev = {
      x: a * point.x + (1 - a) * this.prev.x,
      y: a * point.y + (1 - a) * this.prev.y,
      z: a * point.z + (1 - a) * this.prev.z
    };

    return this.prev;
  }

  /**
   * Optional batch fallback (for replay mode)
   */
  process(points) {
    return points.map(p => this.update(p));
  }

  reset() {
    this.initialized = false;
    this.prev = { x: 0, y: 0, z: 0 };
  }

  setAlpha(alpha) {
    this.alpha = alpha;
  }
}
