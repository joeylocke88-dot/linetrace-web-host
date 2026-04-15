export class FlowField {
  constructor() {
    this.vectors = new Map();
  }

  addMotion(p1, p2) {
    const dx = p2.x - p1.x;
    const dy = p2.y - p1.y;
    const dz = p2.z - p1.z;

    const speed = Math.sqrt(dx*dx + dy*dy + dz*dz);

    return {
      x: dx / (speed || 1),
      y: dy / (speed || 1),
      z: dz / (speed || 1),
      magnitude: speed
    };
  }
}
