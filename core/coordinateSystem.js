// /core/coordinateSystem.js

export class CoordinateSystem {
  constructor() {
    // World origin (anchored reference point)
    this.origin = { x: 0, y: 0, z: 0 };

    // Global visualization scale
    this.scale = 1.0;

    // Whether origin has been initialized
    this.initialized = false;
  }

  /**
   * Set the world origin using the first stable or chosen point.
   * This anchors all future coordinates.
   */
  setOrigin(point) {
    this.origin = {
      x: point.x,
      y: point.y,
      z: point.z,
    };
    this.initialized = true;
  }

  /**
   * Convert raw Android/world input into Three.js-compatible space.
   * Handles:
   * - origin offset
   * - axis correction
   * - scaling
   */
  transform(raw) {
    if (!this.initialized) {
      this.setOrigin(raw);
    }

    // Translate relative to origin
    let x = raw.x - this.origin.x;
    let y = raw.y - this.origin.y;
    let z = raw.z - this.origin.z;

    // Axis correction:
    // Android Z-forward → Three.js Z-backward
    z = -z;

    // Apply global scale
    x *= this.scale;
    y *= this.scale;
    z *= this.scale;

    return { x, y, z };
  }

  /**
   * Transform an array of points into render space.
   */
  transformArray(points) {
    return points.map(p => this.transform(p));
  }

  /**
   * Reset coordinate system (useful for new session / replay)
   */
  reset() {
    this.initialized = false;
    this.origin = { x: 0, y: 0, z: 0 };
  }

  /**
   * Adjust visualization scale dynamically.
   * Useful for zooming into traces or compressing space.
   */
  setScale(scale) {
    this.scale = scale;
  }
}
