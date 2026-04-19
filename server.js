const fs = require("fs");
const path = require("path");
const http = require("http");
const WebSocket = require("ws");
const { Bonjour } = require("bonjour-service");

const PORT = process.env.PORT || 10000;
const bonjour = new Bonjour();

// Advertise the LineTrace service on mDNS (Conditional for local environments)
if (!process.env.RENDER) {
  bonjour.publish({ name: "LineTraceServer", type: "linetrace", port: PORT });
  console.log(`📡 mDNS Service advertised: LineTraceServer on port ${PORT}`);
}

const PUBLIC_DIR = path.join(__dirname, "public");
const WORLD_FILE = process.env.RENDER ? "/tmp/world_state.json" : path.join(__dirname, "world_state.json");
const MAP_FILE = process.env.RENDER ? "/tmp/lazarus_map.bin" : path.join(__dirname, "lazarus_map.bin");

// =========================
// SHARED WORLD STATE (SGS + LAZARUS MAP)
// =========================
class VoxelGridDecimator {
  constructor(voxelSize) {
    this.voxelSize = voxelSize;
    this.grid = new Map(); // voxelKey -> {data: Buffer, conf: float}
  }

  getKey(x, y, z) {
    return `${Math.floor(x / this.voxelSize)},${Math.floor(y / this.voxelSize)},${Math.floor(z / this.voxelSize)}`;
  }

  processSurfel(data, offset = 0) {
    const x = data.readFloatLE(offset);
    const y = data.readFloatLE(offset + 4);
    const z = data.readFloatLE(offset + 8);
    const conf = data.readFloatLE(offset + 28); // 64-byte V12 offset for confidence

    const key = this.getKey(x, y, z);
    const existing = this.grid.get(key);

    if (!existing || conf > existing.conf) {
      this.grid.set(key, { data: data.slice(offset, offset + 64), conf });
      return true;
    }
    return false;
  }

  get buffer() {
    return Buffer.concat(Array.from(this.grid.values()).map(v => v.data));
  }

  get size() {
    return this.grid.size;
  }

  clear() {
    this.grid.clear();
  }
}

const decimator = new VoxelGridDecimator(0.04);

function loadWorldState() {
  // Load Lazarus Map (Binary Surfel Index)
  try {
    if (fs.existsSync(MAP_FILE)) {
      const data = fs.readFileSync(MAP_FILE);
      for (let i = 0; i < data.length; i += 64) {
        if (i + 64 > data.length) break;
        decimator.processSurfel(data, i);
      }
      console.log(`📂 Lazarus Map Loaded: ${decimator.size} surfels`);
    }
  } catch (e) {
    console.error("Lazarus Map load failed:", e.message);
  }
  // ... rest of function

  try {
    if (fs.existsSync(WORLD_FILE)) {
      return JSON.parse(fs.readFileSync(WORLD_FILE, "utf8"));
    }
  } catch (e) {
    console.error("World load failed, using default:", e.message);
  }
  return {
    anchor: { x: 0, y: 0, z: 0 },
    version: 1,
  };
}

const worldState = loadWorldState();

function saveWorldState() {
  try {
    fs.writeFileSync(WORLD_FILE, JSON.stringify(worldState, null, 2));

    // Commit Importance Sampled Map to Binary
    fs.writeFileSync(MAP_FILE, decimator.buffer);

    console.log(`💾 World state and Lazarus Map (${decimator.size} pts) saved`);
  } catch (e) {
    console.error("World save failed:", e.message);
  }
}

// Graceful shutdown
function gracefulShutdown() {
  console.log("🛑 Shutting down server...");
  saveWorldState();
  wss.clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.close(1001, "Server shutting down");
    }
  });
  server.close(() => {
    console.log("✅ Server closed cleanly");
    process.exit(0);
  });
}

process.on("SIGTERM", gracefulShutdown);
process.on("SIGINT", gracefulShutdown);

// =========================
// HTTP SERVER + STATIC FILES
// =========================
const server = http.createServer((req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  const urlPath = decodeURIComponent(url.pathname);

  // Handle Telemetry POST
  if (req.method === "POST" && urlPath === "/telemetry") {
    let body = "";
    req.on("data", chunk => { body += chunk; });
    req.on("end", () => {
      try {
        const telemetry = JSON.parse(body);
        console.log(`°ƒôê Telemetry from ${telemetry.senderId || 'unknown'}: ${telemetry.distance}m, ${telemetry.points} pts`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok" }));
      } catch (e) {
        res.writeHead(400);
        res.end("Invalid JSON");
      }
    });
    return;
  }

  let filePath = path.join(PUBLIC_DIR, urlPath === "/" ? "index.html" : urlPath);
  filePath = path.resolve(filePath);

  if (!filePath.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404);
      res.end(`Not found: ${req.url}`);
      return;
    }

    fs.readFile(filePath, (readErr, data) => {
      if (readErr) {
        res.writeHead(500);
        res.end(`Server error reading ${req.url}`);
        return;
      }

      const ext = path.extname(filePath).toLowerCase();
      const mimeTypes = {
        ".js": "application/javascript",
        ".css": "text/css",
        ".html": "text/html",
        ".json": "application/json",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".svg": "image/svg+xml",
      };

      res.writeHead(200, { "Content-Type": mimeTypes[ext] || "application/octet-stream" });
      res.end(data);
    });
  });
});

// =========================
// WEB SOCKET SERVER
// =========================
const wss = new WebSocket.Server({
  server,
  clientTracking: true,
  perMessageDeflate: false // Often causes issues on free-tier Render or mobile clients
});
const rooms = new Map(); // roomName → Map<user, ws>

function heartbeat() { this.isAlive = true; }

const interval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on("connection", (ws, req) => {
  ws.isAlive = true;
  ws.on("pong", heartbeat);

  const url = new URL(req.url, `http://${req.headers.host}`);
  const room = url.searchParams.get("room") || "default";
  let user = url.searchParams.get("user") || `web_${Math.random().toString(36).slice(2, 10)}`;

  if (!rooms.has(room)) rooms.set(room, new Map());
  const clients = rooms.get(room);

  if (clients.has(user)) {
    user = `${user}_${Date.now().toString(36)}`;
  }

  ws.user = user;
  ws.room = room;
  clients.set(user, ws);

  console.log(`✅ [${room}] ${user} connected (${clients.size} total)`);

  // Initial Sync: Anchor & Version
  ws.send(JSON.stringify({
    type: "anchor",
    anchor: worldState.anchor,
    version: worldState.version,
  }));

  // Initial Sync: Lazarus Map (Surfels) - Chunked for stability
  const surfelBuffer = decimator.buffer;
  const CHUNK_SIZE = 500 * 64; // 500 surfels per packet (~32KB)
  for (let i = 0; i < surfelBuffer.length; i += CHUNK_SIZE) {
    const chunk = surfelBuffer.slice(i, Math.min(i + CHUNK_SIZE, surfelBuffer.length));
    ws.send(JSON.stringify({
      type: "world_delta",
      senderId: "SERVER_INIT",
      timestamp: Date.now(),
      surfelData: chunk.toString('base64')
    }));
  }

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      console.error(`[${room}] Invalid JSON from ${user}:`, raw.toString().substring(0, 100));
      return;
    }
    if (!msg || typeof msg !== "object") return;

    // Verbose logging for debugging telemetry issues
    if (msg.type === "imu" || msg.type === "pose") {
        // Log every 100th message or so to avoid flooding, or just log high level
        if (Math.random() < 0.01) console.log(`[${room}] Telemetry heartbeat from ${user}: ${msg.type}`);
    } else {
        console.log(`[${room}] Received ${msg.type} from ${user}`);
    }

    // Preserve original fields if present (from Android Core State)
    msg.node = msg.node || user;
    msg.room = room;
    if (!msg.timestamp) msg.timestamp = Date.now();

    // 1. Authoritative Anchor Updates
    if (msg.type === "anchor" || msg.type === "ar_anchor") {
      const newAnchor = msg.anchor || (msg.data && msg.data.anchor);
      if (newAnchor) {
        worldState.anchor = newAnchor;
        worldState.version++;
        saveWorldState();

        broadcast(room, {
          type: "anchor",
          anchor: worldState.anchor,
          version: worldState.version,
          sender: user
        }, ws);
      }
      return;
    }

    // 3. Reset Command
    if (msg.type === "reset_world") {
      console.log(`🔄 World reset triggered by ${user} in room ${room}`);
      worldState.anchor = { x: 0, y: 0, z: 0 };
      worldState.version = 1;
      decimator.clear();
      saveWorldState();
      broadcast(room, { type: "anchor", anchor: worldState.anchor, version: worldState.version, status: "RESET" });
      return;
    }

    // 2. Core State Forwarding (Enriched Cayley Graph Nodes)
    // Matches: imu, path_point, pose, ar_vertical_plane, thermal_heartbeat, world_delta
    if (msg.type === "compute_pc" && msg.data) {
        // High-bandwidth point cloud for GPU Boost
        if (Math.random() < 0.05) console.log(`[${room}] Received compute_pc (${msg.data.length} bytes) from ${user}`);
    }

    if (msg.type === "compute_task" && msg.taskId) {
        console.log(`[${room}] Remote Compute Task: ${msg.taskId} from ${user}`);

        if (msg.taskId === "pose_graph_solve" && msg.data) {
          const { nodes, factors, iterations } = msg.data;
          // SERVER-SIDE POSE GRAPH OPTIMIZATION (Simplified PGO)
          // In a production environment, we'd use G2O or Ceres here via a WASM module.
          // For now, we perform a weighted SE(3) averaging similar to the Android CPU path,
          // but with more iterations or higher precision if needed.

          const nodesList = nodes.nodes || [];
          const edgesList = factors.edges || [];
          const nodeMap = new Map();
          nodesList.forEach(n => nodeMap.set(n.id, { ...n, pose: [...n.pose] }));

          for (let it = 0; it < (iterations || 10); it++) {
            edgesList.forEach(edge => {
              const nodeA = nodeMap.get(edge.from);
              const nodeB = nodeMap.get(edge.to);
              if (!nodeA || !nodeB) return;

              // Simplified translation-only correction for the demo
              // In reality, we'd do full 4x4 matrix multiplication
              const alpha = 0.3;
              for (let i = 12; i < 15; i++) {
                const target = nodeA.pose[i] + edge.transform[i];
                nodeB.pose[i] = nodeB.pose[i] * (1 - alpha) + target * alpha;
              }
            });
          }

          // Return corrections
          const corrections = Array.from(nodeMap.values()).map(n => ({
            id: n.id,
            pose: n.pose
          }));

          ws.send(JSON.stringify({
            type: "compute_result",
            taskId: msg.taskId,
            data: { corrections },
            timestamp: Date.now()
          }));
        } else if (msg.taskId === "surfel_fusion" && msg.data) {
          // SERVER-SIDE SURFEL FUSION (Demo Loopback)
          // In a full implementation, we'd store the point cloud from compute_pc
          // and merge it into the Lazarus Map here.
          // For now, we just acknowledge receipt to verify the protocol.
          ws.send(JSON.stringify({
            type: "compute_ack",
            taskId: msg.taskId,
            timestamp: Date.now()
          }));
        } else {
          ws.send(JSON.stringify({
    }

    if (msg.type === "world_delta" && msg.surfelData) {
        // Handle both binary (Buffer/Uint8Array) and Base64 string from WebSocket
        const buffer = typeof msg.surfelData === 'string'
            ? Buffer.from(msg.surfelData, 'base64')
            : Buffer.from(msg.surfelData);

        let newImportancePoints = 0;

        for (let i = 0; i < buffer.length; i += 64) {
            if (i + 64 > buffer.length) break;
            if (decimator.processSurfel(buffer, i)) {
                newImportancePoints++;
            }
        }

        if (newImportancePoints > 0) {
            // Re-encode to ensure the broadcast uses the same format (base64)
            msg.surfelData = buffer.toString('base64');
            if (Math.random() < 0.01) {
                console.log(`📈 Lazarus Map updated: +${newImportancePoints} points (Total: ${decimator.size})`);
            }
        }
    }

    broadcast(room, msg, ws);
  });

  ws.on("close", () => {
    clients.delete(user);
    if (clients.size === 0) rooms.delete(room);
    console.log(`❌ [${room}] ${user} disconnected (${clients.size} remaining)`);
  });

  ws.on("error", (err) => {
    console.error(`⚠️ WS error for ${user}:`, err.message);
    clients.delete(user);
  });
});

function broadcast(room, msg, skipWs = null) {
  const clients = rooms.get(room);
  if (!clients) return;

  const payload = JSON.stringify(msg);
  for (const client of clients.values()) {
    if (client !== skipWs && client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  }
}

server.listen(PORT, () => {
  console.log(`🚀 LineTrace server running on http://localhost:${PORT}`);
});

process.on("SIGTERM", () => clearInterval(interval));
process.on("SIGINT", () => clearInterval(interval));
