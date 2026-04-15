# LineTrace Web Host

A lightweight Node.js host for a shared AR world that receives real-time `path_point`, `imu`, and `anchor` messages over WebSocket.

## Quick start

1. Install dependencies:
   ```bash
   npm install
   ```
2. Start the server:
   ```bash
   npm start
   ```
3. Open the browser:
   ```
   http://localhost:10000
   ```

## How it works

- `server.js` serves static files from `public/`.
- It also creates a WebSocket server that handles multiple rooms.
- Messages supported from clients:
  - `type: "anchor"` → updates the shared world anchor
  - `type: "imu"` → forwards IMU data to room clients
  - `type: "path_point"` → forwards traced path points to room clients
- `type: "pose"` → forwards device pose updates for rendering
- `type: "ar_vertical_plane"` → forwards plane placement info for visualization
The Android app should connect to the WebSocket with a query string like:

```text
ws://<host>:10000/?room=default&user=android_<id>
```

Then send JSON messages matching the server message types.

## Notes

- The current UI is in `public/index.html`.
- `public/index.html` uses Three.js to render incoming path points.
- The server persists anchor state in `world_state.json`.
