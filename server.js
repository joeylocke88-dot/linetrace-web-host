const fs = require("fs");
const path = require("path");
const http = require("http");
const WebSocket = require("ws");

const PORT = process.env.PORT || 10000;
const WORLD_FILE = "./world_state.json";

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
  // Root → serve index.html
  if (req.url === "/" || req.url === "/index.html") {
    const filePath = path.join(__dirname, "public", "index.html");
    fs.readFile(filePath, (err, data) => {
      if (err) {
        res.writeHead(500);
        res.end("Missing index.html in /public folder");
        return;
      }
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(data);
    });
    return;
  }

  // Static files (JS, CSS, GLSL shaders, etc.)
  let filePath = path.join(__dirname, "public", req.url);
  
  // Security: prevent directory traversal
  if (!filePath.startsWith(path.join(__dirname, "public"))) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end("Not found");
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

  // Send current authoritative anchor
  ws.send(
    JSON.stringify({
      type: "anchor",
      anchor: worldState.anchor,
      version: worldState.version,
    })
  );

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return;
    }
    if (!msg || typeof msg !== "object") return;

    msg.user = user;
    msg.room = room;
    msg.timestamp = Date.now();

    if (msg.type === "ar_anchor") {
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
      });
      return;
    }

    if (msg.type === "imu" || msg.type === "path_point") {
      broadcast(room, msg); // Real-time forwarding
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
function broadcast(room, msg) {
  const clients = rooms.get(room);
  if (!clients) return;

  const payload = JSON.stringify(msg);
  for (const client of clients.values()) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  }
}

// =========================
// START SERVER
// =========================
server.listen(PORT, () => {
  console.log(`🚀 LineTrace server running on http://localhost:${PORT}`);
  console.log(`   WebSocket ready for AR shared world`);
});

// Cleanup interval on shutdown
process.on("SIGTERM", () => clearInterval(interval));
process.on("SIGINT", () => clearInterval(interval));
