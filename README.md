# LineTrace AR: High-Speed Tactical Navigation

LineTrace AR is a high-performance Augmented Reality (AR) recording and navigation system built for high-speed maneuvers, low-light environments, and mission-critical reliability. It enables users to record, visualize, and follow complex paths with sub-meter precision even under extreme physical stress.
Mission

Make navigation invisible at any speed, so the person moving never has to choose between looking at a screen and looking at the world.


What we're building

LineTrace is an AR path recording and replay system that lets anyone capture a complex route and follow it back as a heads-up augmented reality overlay, at high speed, in low light, under extreme physical stress. Built for operators, racers, and anyone who can't afford to look down.


Where we're headed

A world where the path is always visible. Where a search and rescue team runs a night extraction on a route they walked once in daylight. Where a racer sees the perfect line burned into the track ahead of them. Where no one dies because they looked at a GPS screen instead of the road. LineTrace becomes the standard interface between a human in motion and the route they need to follow, everywhere the stakes are real.

## 🚀 Real-World Use Cases

### 1. Motorsports "Perfect Line" Training
Professional drivers can record a "qualifying lap" and use the AR HUD to visualize the ideal racing line directly on the track.
*   **The AR Advantage:** The **Predictive Lead Marker ("Rabbit")** stays 1.5s ahead, providing a visual pacer for braking points and apexes.
*   **High-Speed Stability:** Speed-adaptive line widths ensure the trace remains visible at 100mph+.

### 2. Tactical Extraction (GPS-Denied)
Navigate safely in environments with active GPS jamming or inside tunnels/garages.
*   **Resilience:** The **VIO+IMU Fusion** engine increases IMU trust in low light (< 10 lux), maintaining path integrity when visual tracking fails.
*   **Recovery:** The **Lazarus Protocol** monitors system health every 100ms, triggering instant ARCore/GL re-initialization if a stall is detected.

### 3. Search and Rescue (SAR) in Debris Fields
Responders can mark "Safe/Clear" paths through post-disaster zones where landmarks have been destroyed.
*   **Thermal Visibility:** Custom shaders ensure the path is visible through smoke or dust.
*   **Drift Correction:** **Manual Anchor Re-sync** allows teams to "zero out" accumulated VIO drift against known physical landmarks.

---

## 🛠 Key Technology

- **Fusion Engine:** Adaptive Kalman filter that fuses ARCore VIO with high-frequency IMU data.
- **Cayley Temporal Continuity:** Maintains structural path integrity using a Cayley-graph based enrichment for zero-latency node synchronization over the network.
- **LineCrawler:** A specialized structural scanner that implements medical-grade Gaussian Surfel splatting with vertical infill for dense geometry capture.
- **Lazarus Protocol:** A 100ms heartbeat monitor that ensures the GL/Native context "resurrects" instantly after a crash or stall.
- **Rectifier System:** A multi-layered diagnostic engine (Perception, Connectivity, Infrastructure) that performs "Flash Scans" to detect and fix system-level desyncs.
- **Pose Stabilizer:** Implements the "Mirror Shield" protocol to provide jitter-free tracking and maintain pose consistency during transient ARCore tracking loss.
- **Drift Dampener:** An intelligent integration engine that filters out low-frequency VIO drift using IMU bias estimation and stationary period detection.
- **Tactical HUD:** Speed-adaptive line widths (14f to 28f) and perspective-aware depth bias to prevent Z-fighting.
- **Thermal Immunity:** Removes all software-level thermal throttling (sensor rates, marching steps) and implements thermal-aware segmented marching to maintain peak performance under load.
- **GPU Boost:** Offloads expensive Surfel Fusion and PGO to a high-performance PC server via a zero-allocation binary pipeline.
- **LZ4 Compression:** High-speed point cloud compression using `lz4-java` (JNI + Safe/Unsafe Java) to minimize bandwidth and latency during real-time streaming.

---

## 🌐 Remote Visualization (Digital Twin)

LineTrace AR supports real-time remote monitoring and "Digital Twin" visualization via a Node.js WebSocket host.

### 1. Live Surfel Cloud
The Android app streams a high-density surfel map (fused AR points + RGB) to the web host.
*   **Protocol:** Custom `world_delta` binary packets encoded in Base64 JSON.
*   **Visualizer:** Built with Three.js for real-time 3D rendering in any browser.

### 2. Supported Messages (WebSocket)
The host (`linetrace-web-host`) acts as a transparent relay for:
- `type: "anchor"` / `"ar_anchor"` → Shared world origin synchronization.
- `type: "world_delta"` → Dense surfel point cloud updates.
- `type: "path_point"` → Real-time path tracing.
- `type: "pose"` → Device position and orientation for 3D monitoring.
- `type: "ar_vertical_plane"` → Visualization of detected geometry.

---

## 🏗 Build & Setup

### Android App
1.  Open the `LineTraceAR` folder in Android Studio.
2.  Sync Gradle dependencies.
3.  Build and Run:
    ```bash
    ./gradlew assembleDebug
    ```

### Web Host (Local Development)
1.  Navigate to `linetrace-web-host/`.
2.  Install dependencies: `npm install`.
3.  Start server: `npm start`.
4.  Access at [https://linetrace-web.onrender.com](https://linetrace-web.onrender.com) or `http://localhost:10000`.

---

## 🛠 Project Structure

*   **`/app`**: Android source code (Kotlin + OpenGL ES 3.0).
*   **`/linetrace-web-host`**: Node.js WebSocket server and Three.js visualizer.
*   **`/scripts`**: Telemetry analysis and CSV processing tools.

---

### Notes
- Requires an ARCore-supported Android device.
- Optimized for high-vibration and high-velocity mounting (e.g., vehicle dashboards).
- CSV export hooks included for telemetry analysis.
