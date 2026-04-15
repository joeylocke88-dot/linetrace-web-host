export class EddyDetector {
  detect(points) {
    const eddies = [];

    for (let i = 2; i < points.length; i++) {
      const a = points[i - 2];
      const b = points[i - 1];
      const c = points[i];

      const ab = { x: b.x - a.x, y: b.y - a.y, z: b.z - a.z };
      const bc = { x: c.x - b.x, y: c.y - b.y, z: c.z - b.z };

      const cross =
        ab.x * bc.y - ab.y * bc.x;

      const curvature = Math.abs(cross);

      if (curvature > 0.02) {
        eddies.push({
          position: b,
          intensity: curvature
        });
      }
    }

    return eddies;
  }
}
