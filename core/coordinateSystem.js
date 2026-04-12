// /core/coordinateSystem.js

export class CoordinateSystem {
  constructor() {
    this.origin = null;
    this.scale = 1.0;

    this.initialized = false;

    // stability filter for origin selection
    this.originCandidates = [];
    this.originSampleSize = 10;
  }

  /**
   * Feed candidate origin points first
   * (prevents noisy first-frame locking)
   */
  observeOriginCandidate(point) {
    if (this.initialized) return;

    this.originCandidates.push(point);

    if (this.originCandidates.length >= this.originSampleSize) {
      this._lockOrigin();
    }
  }

  /**
   * Lock origin using average of samples (noise reduction)
   */
  _lockOrigin() {
    const avg = this.originCandidates.reduce(
      (acc, p) => {
        acc.x += p.x;
        acc.y += p.y;
        acc.z += p.z;
        return acc;
      },
      { x: 0, y: 0, z: 0 }
    );

    avg.x /= this.originCandidates.length;
    avg.y /= this.originCandidates.length;
    avg.z /= this.originCandidates.length;

    this.origin = avg;
    this.initialized = true;
  }

  /**
   * Transform into world/render space
   */
  transform(raw) {
    // still collecting origin
    if (!this.initialized) {
      this.observeOriginCandidate(raw);
      return { x: 0, y: 0, z: 0 };
    }

    let x = raw.x - this.origin.x;
    let y = raw.y - this.origin.y;
    let z = raw.z - this.origin.z;

    // Android → Three.js axis correction
    z = -z;

    return {
      x: x * this.scale,
      y: y * this.scale,
      z: z * this.scale
    };
  }

  /**
   * Batch transform
   */
  transformArray(points) {
    return points.map(p => this.transform(p));
  }

  /**
   * Reset full system
   */
  reset() {
    this.origin = null;
    this.initialized = false;
    this.originCandidates = [];
  }

  setScale(scale) {
    this.scale = scale;
  }
}
