# LineTrace AR: High-Speed Tactical Navigation

LineTrace AR is a high-performance Augmented Reality (AR) recording and navigation system built for high-speed maneuvers, low-light environments, and mission-critical reliability. It enables users to record, visualize, and follow complex paths with sub-meter precision even under extreme physical stress.

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

### 4. Autonomous "Shadow" Testing
Tele-operators can compare a vehicle's "intended path" (Ghost) against its "actual path" (Live) in real-time.
*   **Visual Feedback:** The **Engagement Lock v2** tether provides a color-coded visual indicator of Cross-Track Error (CTE).

---

## 🛠 Key Technology

- **Fusion Engine:** Adaptive Kalman filter that fuses ARCore VIO with high-frequency IMU data.
- **Lazarus Protocol:** A 100ms heartbeat monitor that ensures the GL/Native context "resurrects" instantly after a crash or stall.
- **Tactical HUD:** Speed-adaptive line widths (14f to 28f) and perspective-aware depth bias to prevent Z-fighting.
- **Thermal Management:** Proactive throttling that drops sensor sampling frequency if battery temperature exceeds 42°C.
- **Async Staging:** Ghost path spline interpolation is offloaded to background threads to ensure 60fps UI performance.

---

## 🌐 Remote Visualization (Digital Twin)

LineTrace AR supports real-time remote monitoring and "Digital Twin" visualization via a Node.js WebSocket host.

### 1. Live Surfel Cloud
The Android app streams a high-density surfel map (fused AR points + RGB) to the web host.
*   **Protocol:** Custom `world_delta` binary packets encoded in Base64 JSON.
*   **Visualizer:** Built with Three.js for real-time 3D rendering in any browser.

### 2. Multi-User Rooms
Collaborators can join specific "Rooms" to see shared paths and environment scans.
*   **Default Room:** `default` (used for standard sessions).
*   **Web URL:** [https://linetrace-web.onrender.com](https://linetrace-web.onrender.com)

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
4.  Access at `http://localhost:10000`.

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
