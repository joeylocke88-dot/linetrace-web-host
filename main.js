import { Renderer } from './core/renderer.js';
import { TraceBuffer } from './core/traceBuffer.js';
import { CoordinateSystem } from './core/coordinateSystem.js';
import { Smoother } from './core/smoothing.js';
import { connect } from './network/websocket.js';
import { GridShader } from './core/gridShader.js';

// optional missing dependency fix
import { Flow } from './core/flow.js';

const renderer = new Renderer();
const buffer = new TraceBuffer();
const coord = new CoordinateSystem();
const smoother = new Smoother(0.2);
const flow = new Flow();

// current world anchor
let anchor = { x: 0, y: 0, z: 0 };

// =========================
// STREAM CONNECT
// =========================
connect((msg) => {

  if (!msg) return;

  // =========================
  // 1. IMU STREAM (motion drive)
  // =========================
  if (msg.type === "imu" && msg.data) {
    buffer.add({
      x: msg.data.x,
      y: msg.data.y,
      z: msg.data.z
    });
  }

  // =========================
  // 2. PATH POINT STREAM
  // =========================
  if (msg.type === "path_point") {
    buffer.add({
      x: msg.x,
      y: msg.y,
      z: msg.z
    });
  }

  // =========================
  // 3. ANCHOR UPDATE
  // =========================
  if (msg.type === "anchor") {
    anchor = msg.anchor || anchor;
    coord.setAnchor(anchor);
    return; // anchor doesn't render directly
  }

  // =========================
  // RENDER PIPELINE
  // =========================

  const rawPoints = buffer.getPositions();
  if (rawPoints.length < 2) return;

  // world transform
  const worldPoints = coord.transformArray(rawPoints);

  // smoothing
  const smoothed = worldPoints.map(p => smoother.update(p));

  // render vectors
  for (let i = 1; i < smoothed.length; i++) {
    const v = flow.addMotion(smoothed[i - 1], smoothed[i]);
    renderer.addVector(v);
  }
});

// =========================
// RENDER LOOP
// =========================
renderer.render();
