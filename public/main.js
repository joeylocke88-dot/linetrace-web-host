import * as THREE from 'three';
import { Renderer } from './core/renderer.js';
import { TraceBuffer } from './core/traceBuffer.js';
import { CoordinateSystem } from './core/coordinateSystem.js';
import { Smoother } from './core/smoothing.js';
import { FlowField } from './core/flowField.js';
import { connect } from './network/websocket.js';
import { GridShader } from './core/gridShader.js';

const renderer = new Renderer();
window.scene = renderer.scene;

const buffer = new TraceBuffer();
const coord = new CoordinateSystem();
const smoother = new Smoother(0.2);
const flow = new FlowField();
const graph = {
  nodes: new Map(),
  last: {
    imu: null,
    path: null
  }
};

// 🔥 GPU FIELD
const grid = new GridShader(renderer.scene);

// WORLD STATE
let anchor = { x: 0, y: 0, z: 0 };

// =========================
// SERVER CONNECTION
// =========================
connect(handleServerMessage);

// =========================
// LOGGING
// =========================
function log(...args) {
  console.log(...args);
}

function ensureWorldRoot() {
  if (window.worldRoot) return;

  window.worldRoot = new THREE.Group();
  const children = [...renderer.scene.children];

  children.forEach((child) => {
    renderer.scene.remove(child);
    window.worldRoot.add(child);
  });

  renderer.scene.add(window.worldRoot);
}

function handlePathPoint(msg) {
  const node = {
    type: 'path',
    pos: { x: msg.x, y: msg.y, z: msg.z },
    time: performance.now(),
    edges: []
  };

  if (graph.last.path) {
    node.edges.push({
      dx: msg.x - graph.last.path.x,
      dy: msg.y - graph.last.path.y,
      dz: msg.z - graph.last.path.z
    });
  }

  graph.last.path = { x: msg.x, y: msg.y, z: msg.z };
  graph.nodes.set(node.time, node);

  buffer.add({ pos: [node.pos.x, node.pos.y, node.pos.z] });
  grid.setDistortion(0.12);
}

function handleServerMessage(msg) {
  if (!msg) return;

  console.log(`📥 [${msg.type || 'unknown'}]`, msg);

  if (msg.type === 'imu') {
    const node = {
      type: 'imu',
      pos: msg.data,
      time: performance.now(),
      edges: []
    };

    if (graph.last.imu) {
      node.edges.push({
        dx: msg.data.x - graph.last.imu.x,
        dy: msg.data.y - graph.last.imu.y,
        dz: msg.data.z - graph.last.imu.z
      });
    }

    graph.last.imu = msg.data;
    graph.nodes.set(node.time, node);
    buffer.add({ pos: [msg.data.x, msg.data.y, msg.data.z] });

    const intensity =
      Math.abs(msg.data.x) +
      Math.abs(msg.data.y) +
      Math.abs(msg.data.z);

    grid.setIntensity(0.6 + intensity * 0.2);
    grid.setDistortion(intensity * 0.05);
    return;
  }

  if (msg.type === 'path_point') {
    handlePathPoint(msg);
    return;
  }

  if (msg.type === 'anchor') {
    if (msg.anchor) {
      ensureWorldRoot();
      window.worldRoot.position.set(
        msg.anchor.x || 0,
        msg.anchor.y || 0,
        msg.anchor.z || 0
      );
      anchor = msg.anchor;
      log(`World anchor updated → X:${anchor.x.toFixed(2)} Y:${anchor.y.toFixed(2)} Z:${anchor.z.toFixed(2)}`);
    }

    grid.setDistortion(0.0);
    grid.setIntensity(1.0);
    return;
  }

  console.log('Unhandled message type:', msg.type);
}

// =========================
// RENDER PIPELINE LOOP
// =========================
let last = performance.now();

function loop() {
  const now = performance.now();
  const dt = (now - last) / 1000;
  last = now;

  grid.update(dt);

  const rawPoints = buffer.getPositions();

  if (rawPoints.length > 1) {
    const worldPoints = coord.transformArray(rawPoints);
    const smoothed = worldPoints.map((p) => smoother.update(p));

    for (let i = 1; i < smoothed.length; i++) {
      const v = flow.addMotion(smoothed[i - 1], smoothed[i]);
      if (renderer.addVector) {
        renderer.addVector(v);
      }
    }
  }

  renderer.renderer.render(renderer.scene, renderer.camera);
  requestAnimationFrame(loop);
}

loop();
