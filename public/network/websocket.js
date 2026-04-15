export function connect(onData) {
  const ws = new WebSocket("ws://YOUR_PHONE_IP:8080");

  ws.onopen = () => {
    console.log("✅ WebSocket connected");
  };

  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);
      console.log(`📥 [${msg.type || 'unknown'}]`, msg);
      onData(msg);
    } catch (err) {
      console.warn("Bad WebSocket message:", err, event.data);
    }
  };

  ws.onerror = (error) => {
    console.warn("WebSocket error:", error);
  };

  ws.onclose = () => {
    console.log("⚠️ WebSocket disconnected");
  };

  return ws;
}
