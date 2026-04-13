const fs = require("fs");

const WORLD_FILE = "./world_state.json";
function loadWorldState() {
  try {
    if (fs.existsSync(WORLD_FILE)) {
      const data = JSON.parse(fs.readFileSync(WORLD_FILE, "utf8"));
      return data;
    }
  } catch (e) {
    console.log("World load failed, using default.");
  }

  return {
    anchor: { x: 0, y: 0, z: 0 },
    version: 1
  };
}
const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 10000;

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url.startsWith('/?')) {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('LineTrace WebSocket Server Running\n');
    return;
  }

  res.writeHead(404);
  res.end();
});

const wss = new WebSocket.Server({ server });

const rooms = new Map();

// =========================
// SHARED WORLD STATE
// =========================
const worldState = loadWorldState();
function saveWorldState() {
  try {
    fs.writeFileSync(
      WORLD_FILE,
      JSON.stringify(worldState, null, 2)
    );
  } catch (e) {
    console.log("World save failed:", e.message);
  }
}

// =========================
// HEARTBEAT
// =========================
function heartbeat() {
  this.isAlive = true;
}

setInterval(() => {
  for (const room of rooms.values()) {
    for (const client of room.values()) {
      if (!client.isAlive) {
        client.terminate();
        room.delete(client.user);
        continue;
      }

      client.isAlive = false;
      client.ping();
    }
  }
}, 30000);

// =========================
// CONNECTION
// =========================
wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.on('pong', heartbeat);

  const url = new URL(req.url, `http://${req.headers.host}`);

  const room = url.searchParams.get('room') || 'default';
  const user =
    url.searchParams.get('user') ||
    'web_' + Math.random().toString(36).slice(2);

  if (!rooms.has(room)) rooms.set(room, new Map());
  const clients = rooms.get(room);

  ws.user = user;
  ws.room = room;

  clients.set(user, ws);

  console.log(`✅ [${room}] ${user} connected`);

  // Send initial anchor
  ws.send(JSON.stringify({
    type: "anchor",
    anchor: worldState.anchor,
    version: worldState.version
  }));

  // =========================
  // MESSAGE HANDLER (FIXED)
  // =========================
  ws.on('message', (raw) => {
    let msg;

    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    if (!msg || typeof msg !== 'object') return;

    msg.user = user;
    msg.room = room;
    msg.timestamp = Date.now();

    // =========================
    // AR ANCHOR (FIXED)
    // =========================
    if (msg.type === "ar_anchor") {

      // update authoritative world anchor
      if (msg.anchor) {
        worldState.anchor = msg.anchor;
        worldState.version++;
      }

       saveWorldState(); // 🔥 PERSIST HERE
      process.on("SIGTERM", () => {
  saveWorldState();
  server.close(() => {
    console.log("Server shutdown cleanly");
  });
});
      
      broadcast(room, {
        type: "anchor",
        anchor: worldState.anchor,
        version: worldState.version
      });

      return;
    }

    // =========================
    // IMU STREAM
    // =========================
    if (msg.type === "imu") {
      broadcast(room, msg);
      return;
    }

    // =========================
    // PATH DATA
    // =========================
    if (msg.type === "path_point") {
      broadcast(room, msg);
      return;
    }
  });

  ws.on('close', () => {
    clients.delete(user);
    if (clients.size === 0) rooms.delete(room);
    console.log(`❌ [${room}] ${user} disconnected`);
  });

  ws.on('error', (err) => {
    console.log(`⚠️ WS error: ${err.message}`);
    clients.delete(user);
  });
});

// =========================
// BROADCAST HELPER
// =========================
function broadcast(room, msg) {
  const clients = rooms.get(room);
  if (!clients) return;

  for (const client of clients.values()) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(JSON.stringify(msg));
    }
  }
}

// =========================
// START
// =========================
server.listen(PORT, () => {
  console.log(`🚀 LineTrace server running on port ${PORT}`);
});
