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

// =========================
// SHARED WORLD STATE
// =========================

function loadWorldState() {
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
    console.log("💾 World state saved");
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
        console.log(`📊 Telemetry from ${telemetry.senderId || 'unknown'}: ${telemetry.distance}m, ${telemetry.points} pts`);
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

const wss = new WebSocket.Server({ server });
const rooms = new Map(); // roomName → Map<user, ws>

function heartbeat() {
  this.isAlive = true;
}

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

  // Initial Sync
  ws.send(JSON.stringify({
    type: "anchor",
    anchor: worldState.anchor,
    version: worldState.version,
  }));

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return;
    }

    if (!msg || typeof msg !== "object") return;

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
        broadcast(room, { type: "anchor", anchor: worldState.anchor, version: worldState.version, sender: user }, ws);
      }
      return;
    }

    // 3. Reset Command
    if (msg.type === "reset_world") {
      console.log(`🔄 World reset triggered by ${user} in room ${room}`);
      worldState.anchor = { x: 0, y: 0, z: 0 };
      worldState.version = 1;
      saveWorldState();
      broadcast(room, { type: "anchor", anchor: worldState.anchor, version: worldState.version, status: "RESET" });
      return;
    }

    // 2. Core State Forwarding (Enriched Cayley Graph Nodes)
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
