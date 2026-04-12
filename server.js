const WebSocket = require('ws');
const http = require('http');

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url.startsWith('/?')) {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('LineTrace WebSocket Server running\n');
    return;
  }

  res.writeHead(404);
  res.end();
});

const wss = new WebSocket.Server({
  server,
  path: '/'
});

const rooms = new Map();

// =========================
// HEARTBEAT (prevents zombie sockets)
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
      } else {
        client.isAlive = false;
        client.ping();
      }
    }
  }
}, 30000);

// =========================
// CONNECTION HANDLER
// =========================
wss.on('connection', (ws, req) => {

  ws.isAlive = true;
  ws.on('pong', heartbeat);

  const url = new URL(req.url, `http://${req.headers.host}`);

  const room = url.searchParams.get('room') || 'default';
  const user = url.searchParams.get('user') ||
    'web_' + Math.random().toString(36).slice(2);

  if (!rooms.has(room)) rooms.set(room, new Map());

  const clients = rooms.get(room);
  clients.set(user, ws);
  ws.user = user;

  console.log(`[${room}] ${user} joined`);

  // =========================
  // MESSAGE PIPELINE
  // =========================
  ws.on('message', (raw) => {
    let msg;

    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    // basic validation
    if (!msg || typeof msg !== 'object') return;
    if (!msg.point) return;

    msg.user = user;
    msg.room = room;
    msg.timestamp = Date.now();

    // broadcast
    for (const [id, client] of clients.entries()) {
      if (client.readyState !== WebSocket.OPEN) continue;

      // optional: skip echo back to sender
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
  });

  ws.on('error', () => {
    clients.delete(user);
  });
});

// =========================
// START SERVER
// =========================
const PORT = process.env.PORT || 10000;

server.listen(PORT, () => {
  console.log(`🚀 LineTrace server running on ${PORT}`);
});
