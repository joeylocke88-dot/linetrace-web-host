const WebSocket = require('ws');
const http = require('http');

const server = http.createServer();
const wss = new WebSocket.Server({ server });

const rooms = new Map();

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const room = url.searchParams.get('room') || 'default';
  const user = url.searchParams.get('user') || 'web_' + Math.random().toString(36).slice(2);

  if (!rooms.has(room)) rooms.set(room, new Map());
  const clients = rooms.get(room);
  clients.set(user, ws);

  console.log(`[${room}] ${user} joined`);

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch(e) { return; }
    msg.user = user;
    msg.room = room;
    msg.timestamp = Date.now();

    clients.forEach((client) => {
      if (client.readyState === WebSocket.OPEN) {
        client.send(JSON.stringify(msg));
      }
    });
  });

  ws.on('close', () => {
    clients.delete(user);
    if (clients.size === 0) rooms.delete(room);
  });
});

const PORT = process.env.PORT || 10000;
server.listen(PORT, () => {
  console.log(`🚀 LineTrace Web Host running on port ${PORT}`);
});
