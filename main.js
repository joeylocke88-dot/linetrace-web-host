import { Renderer } from './core/renderer.js';
import { TraceBuffer } from './core/traceBuffer.js';
import { CoordinateSystem } from './core/coordinateSystem.js';
import { Smoother } from './core/smoothing.js';
import { connect } from './network/websocket.js';
import { GridShader } from './core/gridShader.js';
import { Flow } from './core/flow.js';

const renderer = new Renderer();
const buffer = new TraceBuffer();
const coord = new CoordinateSystem();
const smoother = new Smoother(0.2);
const flow = new Flow();

// 🔥 GPU FIELD
const grid = new GridShader(renderer.scene);

// WORLD STATE
let anchor = { x: 0, y: 0, z: 0 };

// =========================
// SERVER CONNECTION
// =========================
const ws = connect(handleServerMessage);

// =========================
// SERVER MESSAGE ROUTER
// =========================
function handleServerMessage(msg) {

  if (!msg) return;

  // =========================
  // 1. IMU STREAM (DRIVES FIELD + MOTION)
  // =========================
  if (msg.type === "imu") {

    buffer.add({
      x: msg.data.x,
      y: msg.data.y,
      z: msg.data.z
    });

    // 🔥 GRID RESPONSE
    const intensity =
      Math.abs(msg.data.x) +
      Math.abs(msg.data.y) +
      Math.abs(msg.data.z);

    grid.setIntensity(0.6 + intensity * 0.2);
    grid.setDistortion(intensity * 0.05);

    return;
  }

  // =========================
  // 2. PATH STREAM (TRACE HISTORY)
  // =========================
  if (msg.type === "path_point") {

    buffer.add({
      x: msg.x,
      y: msg.y,
      z: msg.z
    });

    grid.setDistortion(0.12);

    return;
  }

  // =========================
  // 3. ANCHOR (WORLD LOCK)
  // =========================
  if (msg.type === "anchor") {

    anchor = msg.anchor || anchor;
    coord.setAnchor(anchor);

    // stabilize entire system
    grid.setDistortion(0.0);
    grid.setIntensity(1.0);

    return;
  }
}

// =========================
// RENDER PIPELINE LOOP
// =========================
let last = performance.now();

function loop() {

  const now = performance.now();
  const dt = (now - last) / 1000;
  last = now;

  // 🔥 GPU FIELD UPDATE
  grid.update(dt);

  // =========================
  // VECTOR PIPELINE
  // =========================
  const rawPoints = buffer.getPositions();

  if (rawPoints.length > 1) {

    const worldPoints = coord.transformArray(rawPoints);
    const smoothed = worldPoints.map(p => smoother.update(p));

    for (let i = 1; i < smoothed.length; i++) {
      const v = flow.addMotion(smoothed[i - 1], smoothed[i]);
      renderer.addVector(v);
    }
  }

  renderer.render();
  requestAnimationFrame(loop);
}

loop();
