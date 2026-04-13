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

// =========================
// GRID SHADER (NOW ACTIVE)
// =========================
const grid = new GridShader(renderer.scene);

// current world anchor
let anchor = { x: 0, y: 0, z: 0 };

// =========================
// STREAM CONNECT
// =========================
connect((msg) => {

  if (!msg) return;

  // =========================
  // IMU STREAM (drives field distortion)
  // =========================
  if (msg.type === "imu" && msg.data) {

    buffer.add({
      x: msg.data.x,
      y: msg.data.y,
      z: msg.data.z
    });

    // 🔥 FEED GRID DISTORTION
    const intensity = Math.min(
      1.0,
      Math.abs(msg.data.x) + Math.abs(msg.data.y) + Math.abs(msg.data.z)
    );

    grid.setIntensity(0.6 + intensity * 0.4);
    grid.setDistortion(intensity * 0.08);
  }

  // =========================
  // PATH POINT STREAM
  // =========================
  if (msg.type === "path_point") {
    buffer.add({
      x: msg.x,
      y: msg.y,
      z: msg.z
    });

    // subtle spatial ripple
    grid.setDistortion(0.15);
  }

  // =========================
  // ANCHOR UPDATE
  // =========================
  if (msg.type === "anchor") {
    anchor = msg.anchor || anchor;
    coord.setAnchor(anchor);

    // stabilize field on anchor lock
    grid.setDistortion(0.0);
    grid.setIntensity(1.0);

    return;
  }

  // =========================
  // RENDER PIPELINE
  // =========================
  const rawPoints = buffer.getPositions();
  if (rawPoints.length < 2) return;

  const worldPoints = coord.transformArray(rawPoints);
  const smoothed = worldPoints.map(p => smoother.update(p));

  for (let i = 1; i < smoothed.length; i++) {
    const v = flow.addMotion(smoothed[i - 1], smoothed[i]);
    renderer.addVector(v);
  }
});

// =========================
// RENDER LOOP (GRID UPDATE ADDED)
// =========================
let last = performance.now();

function loop() {
  const now = performance.now();
  const dt = (now - last) / 1000;
  last = now;

  // 🔥 UPDATE GRID SHADER
  grid.update(dt);

  renderer.render();
  requestAnimationFrame(loop);
}

loop();
