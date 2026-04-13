import { Renderer } from './core/renderer.js';
import { TraceBuffer } from './core/traceBuffer.js';
import { CoordinateSystem } from './core/coordinateSystem.js';
import { Smoother } from './core/smoothing.js';
import { connect } from './network/websocket.js';

const renderer = new Renderer();
const buffer = new TraceBuffer();
const coord = new CoordinateSystem();
const smoother = new Smoother(0.2);

// 🧠 SINGLE STREAM PIPELINE
connect((sample) => {

  // 1. Store raw data
  buffer.add(sample);

  // 2. Convert to array of points
  const rawPoints = buffer.getPositions();

  // 3. Coordinate transform
  const worldPoints = coord.transformArray(rawPoints);

  // 4. Smooth in streaming-safe way
  const smoothed = worldPoints.map(p => smoother.update(p));

  // 5. Render
  for (let i = 1; i < smoothed.length; i++) {
  const v = flow.addMotion(smoothed[i-1], smoothed[i]);
  renderer.addVector(v);
}
});

// Start render loop
renderer.render();
