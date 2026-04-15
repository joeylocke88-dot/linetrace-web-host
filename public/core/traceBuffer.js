export class TraceBuffer {
  constructor() {
    this.points = [];
  }

  add(sample) {
    this.points.push(sample);

    if (this.points.length > 5000) {
      this.points.shift();
    }
  }

  getPositions() {
    return this.points.map(p => ({
      x: p.pos[0],
      y: p.pos[1],
      z: p.pos[2]
    }));
  }
}
