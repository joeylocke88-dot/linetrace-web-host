import { Renderer } from './core/renderer.js';
import { TraceBuffer } from './core/traceBuffer.js';
import { smooth } from './core/smoothing.js';
import { connect } from './network/websocket.js';

const renderer = new Renderer();
const buffer = new TraceBuffer();

connect((sample) => {
  buffer.add(sample);

  const points = buffer.getPositions();
  const smoothed = smooth(points);

  renderer.drawTrace(smoothed);
});

renderer.render();
