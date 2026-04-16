const fs = require("fs");
const path = require("path");
const http = require("http");
const WebSocket = require("ws");
const { Bonjour } = require("bonjour-service");

const PORT = process.env.PORT || 10000;
const bonjour = new Bonjour();
let ad = null;

function startAdvertising() {
  if (ad) {
    ad.stop();
    console.log("🔄 Restarting mDNS advertisement...");
  }

  ad = bonjour.publish({
    name: "LineTraceServer",
    type: "linetrace",
    protocol: "tcp",
    port: PORT,
    txt: { room: "default" },
  });

  ad.on("up", () => {
    console.log(`📢 mDNS Service advertised: LineTraceServer on port ${PORT}`);
  });

  ad.on("error", (err) => {
    console.error("⚠️ mDNS Error:", err);
  });
}

// Watch for network interface changes (e.g., Ethernet disconnect)
const os = require("os");
let lastInterfaces = JSON.stringify(os.networkInterfaces());

setInterval(() => {
  const currentInterfaces = JSON.stringify(os.networkInterfaces());
  if (currentInterfaces !== lastInterfaces) {
    console.log("🌐 Network change detected!");
    lastInterfaces = currentInterfaces;
    startAdvertising();
  }
}, 5000);

startAdvertising();
const PUBLIC_DIR = path.join(__dirname, "public");
const WORLD_FILE = path.join(__dirname, "world_state.json");

// =========================
// SHARED WORLD STATE
// =========================
function loadWorldState() {
  try {
    if (fs.existsSync(WORLD_FILE)) {
      const data = JSON.parse(fs.readFileSync(WORLD_FILE, "utf8"));
      return data;
    }
  } catch (e) {
    console.error("World load failed, using default:", e.message);
  }
  return {
    anchor: { x: 0, y: 0, z: 0 },
    pois: [],
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
// HTTP SERVER + STATIC FILES (from public/)
// =========================
const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent(new URL(req.url || "/", `http://${req.headers.host}`).pathname);
  let filePath = path.join(PUBLIC_DIR, urlPath === "/" ? "index.html" : urlPath);
  filePath = path.resolve(filePath);

  if (!filePath.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      console.error(`❌ 404: ${req.url} → ${filePath}`);
      res.writeHead(404);
      res.end(`Not found: ${req.url}`);
      return;
    }

    fs.readFile(filePath, (readErr, data) => {
      if (readErr) {
        console.error(`❌ Read error: ${req.url} → ${filePath}`);
        res.writeHead(500);
        res.end(`Server error reading ${req.url}`);
        return;
      }

      const ext = path.extname(filePath).toLowerCase();
      const mimeTypes = {
        ".js": "application/javascript",
        ".mjs": "application/javascript",
        ".css": "text/css",
        ".html": "text/html",
        ".json": "application/json",
        ".glsl": "text/plain",
        ".vert": "text/plain",
        ".frag": "text/plain",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".svg": "image/svg+xml",
      };

      res.writeHead(200, {
        "Content-Type": mimeTypes[ext] || "application/octet-stream",
      });
      res.end(data);
    });
  });
});

// =========================
// WEB SOCKET SERVER
// =========================
const wss = new WebSocket.Server({ server });

const rooms = new Map(); // roomName → Map<user, ws>

// Heartbeat (prevent stale connections)
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

  // Avoid duplicate user in same room
  if (clients.has(user)) {
    user = `${user}_${Date.now().toString(36)}`;
  }

  ws.user = user;
  ws.room = room;
  clients.set(user, ws);

  console.log(`✅ [${room}] ${user} connected (${clients.size} total)`);

  // Send current authoritative state
  ws.send(
    JSON.stringify({
      type: "anchor",
      anchor: worldState.anchor,
      pois: worldState.pois || [],
      version: worldState.version,
    })
  );

  ws.on("message", (data) => {
    let msg;
    try {
      const raw = data.toString();
      msg = JSON.parse(raw);
    } catch (e) {
      console.error(`⚠️ Failed to parse message from ${user}:`, e.message);
      return;
    }
    if (!msg || typeof msg !== "object") return;

    msg.user = user;
    msg.room = room;
    msg.timestamp = Date.now();

    // Log telemetry activity occasionally
    if (msg.type === "pose" || msg.type === "path_point" || msg.type === "world_delta") {
      if (Math.random() < 0.05) { // 5% sample
        const info = msg.type === "world_delta" ? ` (${msg.surfelData?.length || 0} bytes)` : "";
        console.log(`🛰️ [${room}] Telemetry from ${user}: ${msg.type}${info}`);
      }
    }

    if (msg.type === "anchor" || msg.type === "ar_anchor" || msg.type === "reset_world") {
      console.log(`📩 [${room}] ${user} action: ${msg.type}`);
    }

    if (msg.type === "reset_world") {
      worldState.anchor = { x: 0, y: 0, z: 0 };
      worldState.pois = [];
      worldState.version++;
      saveWorldState();
      broadcast(room, msg, user);
      return;
    }

    if (msg.type === "poi") {
      if (!worldState.pois) worldState.pois = [];
      worldState.pois.push({
        x: msg.x,
        y: msg.y,
        z: msg.z,
        user: msg.user,
        timestamp: msg.timestamp
      });
      if (worldState.pois.length > 200) worldState.pois.shift();
      saveWorldState();
      broadcast(room, msg, user);
      return;
    }

    if (msg.type === "anchor" || msg.type === "ar_anchor") {
      if (msg.anchor) {
        worldState.anchor = msg.anchor;
        worldState.version++;
        saveWorldState(); // Persist immediately
      }

      // Broadcast updated anchor to everyone in room
      broadcast(room, {
        type: "anchor",
        anchor: worldState.anchor,
        version: worldState.version,
      }, user);
      return;
    }

    if (
      msg.type === "imu" ||
      msg.type === "path_point" ||
      msg.type === "pose" ||
      msg.type === "ar_vertical_plane" ||
      msg.type === "thermal_heartbeat" ||
      msg.type === "world_delta" ||
      msg.type === "poi"
    ) {
      broadcast(room, msg, user); // Real-time forwarding
      return;
    }

    // Add more message types here as needed (e.g. chat, user transform, etc.)
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

// Broadcast helper
function broadcast(room, msg, excludeUser = null) {
  const clients = rooms.get(room);
  if (!clients) return;

  const payload = JSON.stringify(msg);
  for (const [user, client] of clients.entries()) {
    if (user !== excludeUser && client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  }
}

// =========================
// START SERVER
// =========================
server.listen(PORT, "0.0.0.0", () => {
  console.log(`🚀 LineTrace server running on http://0.0.0.0:${PORT}`);
  console.log(`   WebSocket ready for AR shared world`);
});

// Cleanup interval on shutdown
process.on("SIGTERM", () => clearInterval(interval));
process.on("SIGINT", () => clearInterval(interval));
