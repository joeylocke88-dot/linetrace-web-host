export function smooth(points, alpha = 0.2) {
  let smoothed = [];

  for (let i = 0; i < points.length; i++) {
    if (i === 0) {
      smoothed.push(points[i]);
      continue;
    }

    smoothed.push({
      x: alpha * points[i].x + (1 - alpha) * smoothed[i - 1].x,
      y: alpha * points[i].y + (1 - alpha) * smoothed[i - 1].y,
      z: alpha * points[i].z + (1 - alpha) * smoothed[i - 1].z,
    });
  }

  return smoothed;
}
