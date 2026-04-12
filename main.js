import { Renderer } from './core/renderer.js';
import { TraceBuffer } from './core/traceBuffer.js';
import { smooth } from './core/smoothing.js';
import { connect } from './network/websocket.js';

const renderer = new Renderer();
const buffer = new TraceBuffer();

connect((sample) => {
  buffer.add(sample);

  import { CoordinateSystem } from './core/coordinateSystem.js';

const coord = new CoordinateSystem();

connect((sample) => {
  buffer.add(sample);

  const rawPoints = buffer.getPositions();

  const transformed = coord.transformArray(rawPoints);

  const smoothed = smooth(transformed);

  renderer.drawTrace(smoothed);
});
});

renderer.render();
