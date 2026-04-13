// server.js - Render-safe stable WebSocket server

const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 10000;

// =========================
// HTTP SERVER (Render health check)
// =========================
const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url.startsWith('/?')) {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('LineTrace WebSocket Server Running\n');
    return;
  }

  res.writeHead(404);
  res.end();
});

// =========================
// WEBSOCKET SERVER (IMPORTANT: NO path restriction)
// =========================
const wss = new WebSocket.Server({ server });

// Room storage
const rooms = new Map();

// =========================
// HEARTBEAT (prevents silent disconnects)
// =========================
function heartbeat() {
  this.isAlive = true;
}

const heartbeatInterval = setInterval(() => {
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
// CONNECTION HANDLER
// =========================
wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.on('pong', heartbeat);

  // SAFE URL PARSING (Render-safe)
  const url = new URL(req.url, `http://${req.headers.host}`);

  const room = url.searchParams.get('room') || 'default';
  const user =
    url.searchParams.get('user') ||
    'web_' + Math.random().toString(36).slice(2);

  // Create room if needed
  if (!rooms.has(room)) {
    rooms.set(room, new Map());
  }

  const clients = rooms.get(room);

  ws.user = user;
  ws.room = room;

  clients.set(user, ws);

  console.log(`✅ [${room}] ${user} connected`);

  // =========================
  // MESSAGE HANDLING
  // =========================
  ws.on('message', (raw) => {
    let msg;

    try {
      msg = JSON.parse(raw);
    } catch (err) {
      return;
    }

    // Basic validation (prevents crashes)
    if (!msg || typeof msg !== 'object') return;
    if (!msg.point) return;

    msg.user = user;
    msg.room = room;
    msg.timestamp = Date.now();

    // Broadcast to room
    for (const [id, client] of clients.entries()) {
      if (client.readyState !== WebSocket.OPEN) continue;

      // Skip sender (prevents echo spam)
      if (id === user) continue;

      client.send(JSON.stringify(msg));
    }
  });

  // =========================
  // CLEANUP
  // =========================
  ws.on('close', () => {
    clients.delete(user);

    if (clients.size === 0) {
      rooms.delete(room);
    }

    console.log(`❌ [${room}] ${user} disconnected`);
  });

  ws.on('error', (err) => {
    console.log(`⚠️ WS error: ${err.message}`);
    clients.delete(user);
  });
});

// =========================
// START SERVER
// =========================
server.listen(PORT, () => {
  console.log(`🚀 LineTrace server running on port ${PORT}`);
});

// Cleanup interval on shutdown
process.on('SIGTERM', () => {
  clearInterval(heartbeatInterval);
  server.close(() => {
    console.log('Server shutdown cleanly');
  });
});
const globalAnchor = {
  x: 0,
  y: 0,
  z: 0,
  initialized: false
};
if (!globalAnchor.initialized) {
  globalAnchor.x = 0;
  globalAnchor.y = 0;
  globalAnchor.z = 0;
  globalAnchor.initialized = true;
}
ws.send(JSON.stringify({
  type: "anchor",
  anchor: globalAnchor
}));
