export function connect(onData) {
  const ws = new WebSocket("ws://YOUR_PHONE_IP:8080");

  ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    onData(data);
  };
}
