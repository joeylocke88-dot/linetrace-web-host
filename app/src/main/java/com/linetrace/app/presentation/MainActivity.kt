package com.linetrace.app.presentation
import com.linetrace.app.feature.sync.WorldSyncManager
import com.linetrace.app.feature.perception.FusionEngine
import com.linetrace.app.feature.telemetry.PathBuffer
import com.linetrace.app.feature.sync.ServiceDiscovery
import com.linetrace.app.infra.CleanupService
import com.linetrace.app.feature.telemetry.TraceAnalyzer
import com.linetrace.app.feature.mapping.WorldStreamer
import com.linetrace.app.feature.perception.ArCoreController
import com.linetrace.app.feature.perception.MotionTracker
import com.linetrace.app.feature.telemetry.SegmentType
import com.linetrace.app.feature.perception.ComplementaryFusion
import com.linetrace.app.feature.sync.ImuNetworkBridge
import com.linetrace.app.feature.telemetry.SessionRecorder
import com.linetrace.app.feature.perception.DriftDampener
import com.linetrace.app.feature.perception.ArImuFusionEngine
import com.linetrace.app.core.FusedState
import com.linetrace.app.core.Point
import com.linetrace.app.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.File
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity(), LineRenderer.FrameCallback {

    private lateinit var root: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var dashboard: StabilityDashboard
    private lateinit var btnRecord: Button

    private lateinit var tracker: MotionTracker
    private lateinit var fusion: FusionEngine
    private lateinit var imuFusion: ComplementaryFusion
    private lateinit var drift: DriftDampener
    private lateinit var arImuFusion: ArImuFusionEngine
    private lateinit var livePath: PathBuffer
    private lateinit var ghostPath: PathBuffer
    private lateinit var recorder: SessionRecorder
    private lateinit var renderer: LineRenderer
    private lateinit var arController: ArCoreController
    private lateinit var imuBridge: ImuNetworkBridge
    private var serviceDiscovery: ServiceDiscovery? = null

    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("MainActivity", "Network Available. Triggering re-discovery...")
            runOnUiThread {
                serviceDiscovery?.startDiscovery(discoveryListener)
                if (this@MainActivity::imuBridge.isInitialized && !imuBridge.isConnected) {
                    imuBridge.updateServerUrl(imuBridge.serverUrl) // Force reconnect attempt
                }
            }
        }
    }

    private val discoveryListener = object : ServiceDiscovery.OnServiceFoundListener {
        override fun onServiceFound(ip: String, port: Int) {
            runOnUiThread {
                val editIp = findViewById<EditText>(R.id.editServerIp)
                editIp?.setText(ip)
                
                val serverUrl = "ws://$ip:$port"
                if (this@MainActivity::imuBridge.isInitialized) {
                    imuBridge.updateServerUrl(serverUrl)
                }
                
                if (!isInitialized) {
                    Toast.makeText(this@MainActivity, "Server discovered at $ip", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    
    private var currentTemperature = 0f
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (!isInitialized || !this@MainActivity::renderer.isInitialized) return
                intent?.let { intentInstance ->
                    when (intentInstance.action) {
                        Intent.ACTION_BATTERY_CHANGED -> {
                            val temp = intentInstance.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                            currentTemperature = temp / 10f // Convert to Celsius
                            if (this@MainActivity::renderer.isInitialized) {
                                renderer.updateThermalState(currentTemperature)
                            }
                            
                            // Proactive Thermal Throttling: Drop sensor rate if temp > 42C
                            if (currentTemperature > 42f && this@MainActivity::tracker.isInitialized) {
                                tracker.setPowerMode(false)
                            } else if (currentTemperature < 38f && this@MainActivity::tracker.isInitialized) {
                                tracker.setPowerMode(true)
                            }
                        }
                        "com.linetrace.app.SIMULATE_STALL" -> {
                        Log.w("MainActivity", "Lazarus: Simulating GL Stall...")
                            renderer.session = null
                        }
                        "com.linetrace.app.RAPIDPASS" -> {
                            val status = intentInstance.getBooleanExtra("hypervisor", false)
                            renderer.isHypervisor = status
                            Log.i("MainActivity", "RAPIDPASS: Hypervisor Status = $status")
                            runOnUiThread {
                                val msg = if (status) getString(R.string.msg_hypervisor_on) else getString(R.string.msg_hypervisor_off)
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val dashboardTick = object : Runnable {
        override fun run() {
            if (!isInitialized) return
            // "Lazarus" Protocol: Check if the renderer has stalled
            renderer.checkHealth()

            // Persistence: If the session failed to init (transient), retry here
            if (renderer.session == null && arSession == null) {
                try {
                    ensureArSession()
                    arSession?.let {
                        renderer.session = it
                        Log.i("MainActivity", "AR Session Resurrected via heartbeat")
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("ERROR_MAX_CAMERAS_IN_USE") == true) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, getString(R.string.msg_camera_in_use), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            
            // Re-post heartbeat
            uiHandler.postDelayed(this, 100L)
        }
    }

    private var arSession: Session? = null
    private val requestCodeCamera = 1001

    private var isInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Initialize bridge with last known IP or default ASAP to avoid race conditions
        val prefs = getSharedPreferences("LineTracePrefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("server_ip", "10.69.232.32") ?: "10.69.232.32"
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        imuBridge = ImuNetworkBridge(
            serverUrl = savedIp,
            room = "default",
            user = "android_$deviceId"
        )

        // 2. Start CleanupService when activity is active and in foreground
        // Moved to onResume to avoid BackgroundServiceStartNotAllowedException
        
        // 3. Recover from previous crash if needed
        val logFile = File(getExternalFilesDir(null), "crash_log.txt")
        if (logFile.exists()) {
            Log.e("Lazarus", "Found crash log from previous session. Sending to diagnostics...")
            uiHandler.postDelayed({
                Toast.makeText(this, "Lazarus: Recovery from crash successful", Toast.LENGTH_LONG).show()
                logFile.delete()
            }, 2000)
        }

        showStartScreen()
    }

    private fun showStartScreen() {
        setContentView(R.layout.activity_start)
        
        // Load saved IP
        val prefs = getSharedPreferences("LineTracePrefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("server_ip", "192.168.42.237")
        val editIp = findViewById<EditText>(R.id.editServerIp)
        editIp.setText(savedIp)

        // Start Auto-Discovery
        serviceDiscovery = ServiceDiscovery(this)
        serviceDiscovery?.startDiscovery(discoveryListener)
        
        findViewById<View>(R.id.tapToStart).setOnClickListener {
            val enteredIp = editIp.text.toString().trim()
            if (enteredIp.isNotEmpty()) {
                // Save IP for next time
                prefs.edit().putString("server_ip", enteredIp).apply()
                // Keep discovery running to handle future IP changes
                initializeMainUi(enteredIp)
            } else {
                Toast.makeText(this, "Please enter a Server IP", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnIpHome).setOnClickListener {
            editIp.setText("192.168.42.237")
        }

        findViewById<View>(R.id.btnIpLab).setOnClickListener {
            editIp.setText("10.69.232.32")
        }

        findViewById<View>(R.id.btnIpCloud).setOnClickListener {
            editIp.setText("wss://linetrace-web.onrender.com")
        }
    }

    private fun initializeMainUi(targetIp: String) {
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.rootContainer)
        glView = findViewById(R.id.glView)
        dashboard = findViewById(R.id.dashboard)
        btnRecord = findViewById(R.id.btnRecord)

        fusion = FusionEngine()
        imuFusion = ComplementaryFusion()
        drift = DriftDampener()
        arImuFusion = ArImuFusionEngine()
        tracker = MotionTracker(this)
        livePath = PathBuffer()
        ghostPath = PathBuffer()
        recorder = SessionRecorder(getExternalFilesDir("sessions"))
        arController = ArCoreController(this)

        imuBridge.updateServerUrl(targetIp)
        
        imuBridge.messageListener = object : ImuNetworkBridge.MessageListener {
            override fun onPoseReceived(timestamp: Long, pos: FloatArray, rot: FloatArray) {
                ghostPath.addPoint(pos[0], pos[1], pos[2], 1.0f)
            }
        }
        tracker.imuBridge = imuBridge

        renderer = LineRenderer(tracker, fusion, livePath, ghostPath, recorder, imuBridge)
        renderer.frameCallback = this

        // 🔗 Connect Surfel Data to GPU Pipeline
        imuBridge.onDeltaReceived { delta ->
            glView.queueEvent {
                renderer.uploadRemoteSurfels(delta.surfelData)
            }
        }

        // Connect to the shared web world
        renderer.worldSync.setTransport(imuBridge)
        renderer.worldSync.setRemotePathCallback { x, y, z, _ ->
             // Handled by imuBridge.messageListener if needed, 
             // but keeping this for WorldSyncManager specific deltas.
        }

        // Sensor Sampling Draw Data: Link the high-frequency IMU thread to the renderer
        // to provide low-latency draw data even if the ARCore frame loop jitters.
        tracker.setSensorDrawCallback { sample ->
            val fused = imuFusion.update(
                sample.accelX,
                sample.accelY,
                sample.accelZ,
                sample.gyroX,
                sample.gyroY,
                sample.gyroZ
            )

            drift.resetIfStill(
                abs(fused.linAx) + abs(fused.linAy) + abs(fused.linAz)
            )

            val velocity = drift.integrate(
                fused.linAx,
                fused.linAy,
                fused.linAz
            )

            val state = fusion.update(sample)

            if (renderer.isRecording) {
                val latestArPose = renderer.latestFrame?.camera?.displayOrientedPose
                if (latestArPose != null) {
                    val unified = arImuFusion.update(latestArPose, velocity)
                    livePath.setTemporaryPoint(
                        unified.x,
                        unified.y,
                        unified.z,
                        state.stability
                    )
                    
                    // 📍 Send real-time path data to server
                    imuBridge.sendPathPoint(unified.x, unified.y, unified.z)
                } else {
                    livePath.setTemporaryPoint(
                        state.x + velocity[0] * 0.2f,
                        state.y + velocity[1] * 0.2f,
                        state.z + velocity[2] * 0.2f,
                        state.stability
                    )
                }
            } else {
                livePath.clearTemporaryPoint()
            }

            // 🌐 NETWORK STREAM (MATCHES SERVER)
            // Send fused data: linear acceleration and orientation (pitch, roll, yaw)
            imuBridge.sendIMU(
                fused.linAx,
                fused.linAy,
                fused.linAz,
                fused.pitch,
                fused.roll,
                fused.yaw
            )
        }

        glView.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setupControls()
        
        isInitialized = true
        registerBatteryReceiver()
        resumeArSystems()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction("com.linetrace.app.SIMULATE_STALL")
            addAction("com.linetrace.app.RAPIDPASS")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(batteryReceiver, filter)
            }
        } catch (_: Exception) {}

        // Register Connectivity Callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        btnRecord.apply {
            text = getString(R.string.btn_start)
            setOnClickListener {
                when (renderer.tracingState) {
                    LineRenderer.TracingState.IDLE -> {
                        Toast.makeText(this@MainActivity, getString(R.string.msg_tap_to_start), Toast.LENGTH_SHORT).show()
                    }
                    LineRenderer.TracingState.TRACING -> {
                        val frame = renderer.latestFrame
                        if (frame != null) {
                            val cameraPose = frame.camera.displayOrientedPose
                            renderer.finishTracing(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
                            
                            val finalPoints = recorder.getAll()
                            val metrics = TraceAnalyzer.analyze(finalPoints)
                            
                            // Sync telemetry to server
                            renderer.worldSync.syncTelemetry(
                                distance = metrics.totalDistance,
                                durationMs = (metrics.durationSeconds * 1000).toLong(),
                                avgSpeed = metrics.averageSpeed,
                                points = metrics.totalPoints
                            )
                        }
                    }
                    LineRenderer.TracingState.FINISHED -> {
                        val finalPoints = recorder.getAll()
                        recorder.saveSessionAsync { json, csv ->
                            runOnUiThread {
                                if (json != null || csv != null) {
                                    Toast.makeText(this@MainActivity, getString(R.string.msg_session_saved), Toast.LENGTH_SHORT).show()
                                }
                                showAnalysisDialog(finalPoints)
                                cleanState()
                            }
                        }
                    }
                    LineRenderer.TracingState.PAUSED -> {
                        // Handle paused state if necessary
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            cleanState()
            if (::imuBridge.isInitialized) {
                imuBridge.sendReset()
            }
            Toast.makeText(this, getString(R.string.msg_session_reset), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAnalyzeHistory).setOnClickListener {
            val sessions = recorder.listSessions().filter { it.name.endsWith(".json") }
            if (sessions.isNotEmpty()) {
                SessionSelectorFragment(sessions, imuBridge) { file ->
                    uiHandler.post {
                        val points = recorder.loadSession(file)
                        showAnalysisDialog(points)
                        Toast.makeText(this@MainActivity, getString(R.string.msg_ghost_loaded, file.name), Toast.LENGTH_SHORT).show()
                    }
                }.show(supportFragmentManager, "SessionSelector")
            } else {
                Toast.makeText(this, "No saved sessions found", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnGhost).setOnClickListener {
            recorder.migrateLegacySessions { migrated ->
                runOnUiThread {
                    if (migrated > 0) Toast.makeText(this, getString(R.string.msg_migrated, migrated), Toast.LENGTH_SHORT).show()
                    val sessions = recorder.listSessions().filter { it.name.endsWith(".json") }
                    if (sessions.isNotEmpty()) {
                        SessionSelectorFragment(sessions, imuBridge) { file ->
                            uiHandler.post {
                                val points = recorder.loadSession(file)
                                renderer.loadGhost(points)
                                Toast.makeText(this@MainActivity, getString(R.string.msg_ghost_loaded, file.name), Toast.LENGTH_SHORT).show()
                            }
                        }.show(supportFragmentManager, "SessionSelector")
                    } else {
                        Toast.makeText(this, "No saved sessions found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnRecover).setOnClickListener {
            triggerRealityAlignment()
        }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            renderer.performPlaneSync()
            Toast.makeText(this, getString(R.string.msg_plane_sync), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCloseApp).setOnClickListener {
            finishAffinity()
        }

        findViewById<ToggleButton>(R.id.btnLock).setOnCheckedChangeListener { _, isChecked ->
            renderer.isDiagnosticOverlayEnabled = isChecked
            renderer.isHypervisor = isChecked // Link Lock to Hypervisor mode
            val msg = if (isChecked) "Lock Enabled | Hypervisor Online" else "Lock Released | Hypervisor Offline"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            
            // Log for verification
            Log.d("LineTrace", "btnLock toggled: $isChecked, isHypervisor: ${renderer.isHypervisor}")
        }

        val settingsOverlay = findViewById<View>(R.id.settingsOverlay)
        val sbWallHeight = findViewById<SeekBar>(R.id.sbWallHeight)
        val sbWallAlpha = findViewById<SeekBar>(R.id.sbWallAlpha)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            settingsOverlay.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnCloseSettings).setOnClickListener {
            settingsOverlay.visibility = View.GONE
        }

        sbWallHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map 0..100 to 0.0m .. 5.0m
                renderer.wallHeight = progress / 20f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbWallAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map 0..100 to 0.0 .. 1.0
                renderer.wallAlpha = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val sbPlaneAlpha = findViewById<SeekBar>(R.id.sbPlaneAlpha)
        sbPlaneAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderer.planeAlpha = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val tradeOverlay = findViewById<View>(R.id.tradeOverlay)
        val btnCloseTrade = findViewById<Button>(R.id.btnCloseTrade)
        val btnTradeAction = findViewById<Button>(R.id.btnTradeAction)
        // val tradeCredits = findViewById<TextView>(R.id.tradeCredits)
        
        btnCloseTrade.setOnClickListener { tradeOverlay.visibility = View.GONE }
        btnTradeAction.setOnClickListener {
            /*
            val alienSeed = renderer.getAlienSeed()
            if (alienSeed != -1) {
                val preferred = MarketGenerator().getPreferredSubstance(alienSeed)
                if (PlayerState.inventory.any { it.id == preferred.id && it.quantity > 0 }) {
                    val item = PlayerState.inventory.first { it.id == preferred.id }
                    item.quantity--
                    val bonusValue = preferred.baseValue * 2
                    PlayerState.addCredits(bonusValue)
                    tradeCredits.text = "Credits: ${PlayerState.credits}"
                    Toast.makeText(this, "Sold 1x ${preferred.name} (CRITICAL BONUS: +$bonusValue)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Required Substance [${preferred.name}] Not Found", Toast.LENGTH_SHORT).show()
                }
            }
            */
        }

        var lastNudgeX = 0f
        var lastNudgeY = 0f
        glView.setOnTouchListener { _, event ->
            // Reality Alignment: Check for two-finger "Nudge" first
            if (event.pointerCount == 2) {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        // Midpoint initialization
                        lastNudgeX = (event.getX(0) + event.getX(1)) / 2f
                        lastNudgeY = (event.getY(0) + event.getY(1)) / 2f
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val currentX = (event.getX(0) + event.getX(1)) / 2f
                        val currentY = (event.getY(0) + event.getY(1)) / 2f
                        
                        // Scale factors for fine-tuned reality adjustment
                        val dx = (currentX - lastNudgeX) * 0.002f
                        val dy = (currentY - lastNudgeY) * 0.002f
                        
                        // Realtime Perspective-Based Nudge: 
                        // Transform screen swipe into world coordinates using camera orientation.
                        val frame = renderer.latestFrame
                        if (frame != null) {
                            val cameraPose = frame.camera.displayOrientedPose
                            val transform = FloatArray(16)
                            cameraPose.toMatrix(transform, 0)
                            
                            // transform[0..2] is Right vector, [8..10] is Back vector
                            // We use these to make the nudge relative to the viewer's perspective.
                            val worldDx = (dx * transform[0] + dy * transform[8])
                            val worldDz = (dx * transform[2] + dy * transform[10])
                            
                            renderer.nudgeOrigin(worldDx, 0f, worldDz)
                        } else {
                            renderer.nudgeOrigin(dx, 0f, dy)
                        }

                        lastNudgeX = currentX
                        lastNudgeY = currentY
                        
                        // Subtle haptic feedback for alignment
                        maybeHaptic(0.2f, force = true)
                    }
                }
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_DOWN && renderer.isTracking) {
                val x = event.x
                val y = event.y
                val frame = renderer.latestFrame

                if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {

                    val anchor = arController.placeContact(frame, x, y)

                    if (anchor != null) {
                        val pose = anchor.pose

                        // Optional: smoothing
                        val px = pose.tx()
                        val py = pose.ty()
                        val pz = pose.tz()

                        when (renderer.tracingState) {
                            LineRenderer.TracingState.IDLE -> {
                                renderer.startTracing(px, py, pz, anchor)
                            }
                            LineRenderer.TracingState.TRACING -> {
                                renderer.addPoi(px, py, pz)
                                anchor.detach()
                                Toast.makeText(this, getString(R.string.msg_poi_placed), Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                anchor.detach()
                            }
                        }
                    }
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Start CleanupService when activity is active and in foreground
        try {
            startService(Intent(this, CleanupService::class.java))
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start CleanupService in onResume: ${e.message}")
        }

        if (isInitialized) {
            registerBatteryReceiver()
            resumeArSystems()
        }
    }

    private fun resumeArSystems() {
        uiHandler.removeCallbacks(dashboardTick)
        uiHandler.post(dashboardTick)

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), requestCodeCamera)
            return
        }

        try {
            ensureArSession()
            val session = arSession ?: return
            
            // Re-initialize network bridges only if absolutely necessary 
            // and ensure the old one is closed first.
            if (::imuBridge.isInitialized && !imuBridge.isConnected) {
                Log.d("MainActivity", "Bridge disconnected on resume, checking if we need to refresh...")
            }
            
            // Standard ARCore session management
            // FIX: Ensure GLSurfaceView is resumed BEFORE session.resume() to avoid width <= 0 race
            glView.onResume()
            session.resume()
            renderer.onResume()
            renderer.session = session
            tracker.start()
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            Toast.makeText(this, "AR Session Error: $msg", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    override fun onPause() {
        if (isInitialized) {
            // Lazarus Protocol: Set session to null BEFORE pausing the GLView.
            // This ensures the renderer thread sees the null session and exits 
            // the draw loop before the underlying ARCore session is paused.
            renderer.onPause()
            renderer.session = null
            tracker.stop()
            glView.onPause()
            arSession?.pause()
        }

        try {
            unregisterReceiver(batteryReceiver)
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}

        uiHandler.removeCallbacks(dashboardTick)

        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isInitialized) return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val rotation = display?.rotation ?: Surface.ROTATION_0
        renderer.displayRotation = rotation
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(dashboardTick)
        
        if (isInitialized) {
            // 1. Stop high-level systems
            tracker.stop()
            renderer.onPause() // Ensure GL state is stable
            
            // 2. Shut down background threads/executors
            renderer.worldStreamer.shutdown()
            renderer.worldSync.close()
            recorder.shutdown()
            
            // 3. Network cleanup
            if (::imuBridge.isInitialized) {
                imuBridge.close()
            }
            
            // 4. ARCore cleanup
            arSession?.close()
            arSession = null
            arController.close()
            
            // 5. Final renderer/GL cleanup
            renderer.onDestroy()
            
            // Critical: Force process termination to clean up any lingering threads
            // giving it a small window to finish the above shutdowns.
            uiHandler.postDelayed({
                Log.i("MainActivity", "Final process kill")
                Process.killProcess(Process.myPid())
            }, 500) // Increased to 500ms to ensure all threads finish
        }
        super.onDestroy()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ensureArSession() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        Log.d("AR", availability.toString())

        if (arSession != null) return
        if (availability.isTransient) return
        
        if (!availability.isSupported) {
            throw IllegalStateException("ARCore is not supported: $availability")
        }

        try {
            arSession = arController.ensureInstalledAndCreateSession()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to create AR session: ${e.message}", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodeCamera) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Volatile var latestFusedState: FusedState? = null

    override fun onFusedState(state: FusedState) {
        if (!isInitialized) return
        latestFusedState = state
        uiHandler.post {
            val tracking = renderer.isTracking
            val distance = recorder.getDistance()
            val points = recorder.getPointCount()
            val recording = renderer.isRecording
            val surfels = renderer.getSurfelCount()
            val depth = renderer.currentCenterDepth
            dashboard.updateState(state, tracking, distance, points, recording, false, surfels, depth)
            maybeHaptic(state.stability)
        }
    }

    override fun onCameraPoseAvailable(ready: Boolean) {
        if (!isInitialized) return
        uiHandler.post {
            btnRecord.isEnabled = ready || renderer.tracingState != LineRenderer.TracingState.IDLE
        }
    }

    private fun showAnalysisDialog(points: List<Point>) {
        val metrics = TraceAnalyzer.analyze(points)
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_analysis)
        
        dialog.findViewById<TextView>(R.id.tvDistance).text = String.format(Locale.US, "%.2f m", metrics.totalDistance)
        dialog.findViewById<TextView>(R.id.tvDuration).text = String.format(Locale.US, "%.1f s", metrics.durationSeconds)
        dialog.findViewById<TextView>(R.id.tvAvgSpeed).text = String.format(Locale.US, "%.2f m/s", metrics.averageSpeed)
        dialog.findViewById<TextView>(R.id.tvMaxSpeed).text = String.format(Locale.US, "%.2f m/s", metrics.maxSpeed)
        dialog.findViewById<TextView>(R.id.tvVertical).text = String.format(Locale.US, "%.2f m", metrics.verticalDisplacement)
        dialog.findViewById<TextView>(R.id.tvGainLoss).text = String.format(Locale.US, "+%.2f / -%.2f", metrics.elevationGain, metrics.elevationLoss)
        dialog.findViewById<TextView>(R.id.tvCurvature).text = String.format(Locale.US, "%.2f", metrics.curvatureIndex)
        dialog.findViewById<TextView>(R.id.tvStability).text = String.format(Locale.US, "%.1f%%", metrics.averageStability * 100)
        dialog.findViewById<TextView>(R.id.tvSmoothness).text = String.format(Locale.US, "%.1f%%", metrics.smoothnessScore * 100)
        dialog.findViewById<TextView>(R.id.tvJitters).text = metrics.jitters.toString()
        dialog.findViewById<TextView>(R.id.tvPoiCount).text = metrics.poiCount.toString()

        val straightCount = metrics.segments.count { it.type == SegmentType.STRAIGHT }
        val curveCount = metrics.segments.count { it.type == SegmentType.CURVE }
        dialog.findViewById<TextView>(R.id.tvSegments).text = getString(R.string.segments_format, straightCount, curveCount)

        if (metrics.loopClosureError != null) {
            dialog.findViewById<View>(R.id.rowLoop).visibility = View.VISIBLE
            dialog.findViewById<TextView>(R.id.tvLoopError).text = String.format(Locale.US, "%.2f m", metrics.loopClosureError)
        }
        if (metrics.areaEstimated != null) {
            dialog.findViewById<View>(R.id.rowArea).visibility = View.VISIBLE
            dialog.findViewById<TextView>(R.id.tvArea).text = String.format(Locale.US, "%.2f m²", metrics.areaEstimated)
        }

        if (metrics.isEnvironmentScan) {
            dialog.findViewById<View>(R.id.rowBBox).visibility = View.VISIBLE
            dialog.findViewById<View>(R.id.rowScanDim).visibility = View.VISIBLE
            dialog.findViewById<View>(R.id.tvScanBadge).visibility = View.VISIBLE
            
            dialog.findViewById<TextView>(R.id.tvBBoxArea).text = String.format(Locale.US, "%.2f m²", metrics.boundingBoxArea)
            dialog.findViewById<TextView>(R.id.tvScanDim).text = String.format(Locale.US, "%.1fx%.1fm", metrics.scanWidth, metrics.scanDepth)
        }

        // --- INTEGRITY DISPLAY ---
        val tvIntegrity = dialog.findViewById<TextView>(R.id.tvIntegrity)
        tvIntegrity.text = String.format(Locale.US, "%.1f%%", metrics.integrityScore * 100)
        if (metrics.integrityScore > 0.9f) tvIntegrity.setTextColor(Color.GREEN)
        else if (metrics.integrityScore > 0.7f) tvIntegrity.setTextColor(Color.YELLOW)
        else tvIntegrity.setTextColor(Color.RED)

        if (metrics.anomalies.isNotEmpty()) {
            dialog.findViewById<View>(R.id.rowAnomalies).visibility = View.VISIBLE
            dialog.findViewById<TextView>(R.id.tvAnomalies).text = getString(R.string.anomalies_detected, metrics.anomalies.size)
        }

        dialog.findViewById<Button>(R.id.btnAnalysisClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun cleanState() {
        renderer.resetTracing()
        recorder.clear()
    }

    override fun onRendererStalled() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.msg_stall), Toast.LENGTH_SHORT).show()
            triggerRealityAlignment()
        }
    }

    override fun onTracingStateChanged(state: LineRenderer.TracingState) {
        if (!isInitialized) return
        uiHandler.post {
            when (state) {
                LineRenderer.TracingState.IDLE -> {
                    btnRecord.text = getString(R.string.btn_start)
                    Toast.makeText(this, getString(R.string.msg_tap_to_start), Toast.LENGTH_LONG).show()
                }
                LineRenderer.TracingState.TRACING -> {
                    btnRecord.text = getString(R.string.btn_finish)
                    Toast.makeText(this, getString(R.string.msg_tracing_active), Toast.LENGTH_SHORT).show()
                }
                LineRenderer.TracingState.FINISHED -> {
                    btnRecord.text = getString(R.string.btn_reset)
                    Toast.makeText(this, getString(R.string.msg_tracing_complete), Toast.LENGTH_SHORT).show()
                }
                LineRenderer.TracingState.PAUSED -> {
                    btnRecord.text = getString(R.string.btn_resume)
                }
            }
        }
    }

    override fun onDepthUpdate(depth: Float) {
        // Center-screen depth data can be consumed here for UI feedback
    }

    private fun triggerRealityAlignment() {
        // Thermal Throttling: Prevent resurrection if device is overheating (> 45°C)
        // RapidPass: Bypass thermal safety in Hypervisor mode
        if (currentTemperature > 45f && !renderer.isHypervisor) {
            Toast.makeText(this, getString(R.string.msg_alignment_blocked, currentTemperature), Toast.LENGTH_LONG).show()
            return
        }

        runOnUiThread {
            Toast.makeText(this, getString(R.string.msg_alignment_init), Toast.LENGTH_SHORT).show()
            Toast.makeText(this, getString(R.string.msg_resurrection_try), Toast.LENGTH_SHORT).show()
            try {
                // Inform server of origin reset
                if (::imuBridge.isInitialized) {
                    imuBridge.sendAnchor(0f, 0f, 0f)
                }

                // Warm Resurrection
                val session = arSession
                if (session != null) {
                    try {
                        session.resume() // Ensure session is actually running
                        renderer.onResume()
                        renderer.session = session
                        Toast.makeText(this, getString(R.string.msg_warm_success), Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    } catch (_: Exception) {
                        // If resume fails, fall through to cold resurrection
                    }
                }

                // Cold Resurrection
                renderer.onPause()
                glView.onPause()
                renderer.session = null
                arController.close() 
                arSession = null
                
                arSession = arController.ensureInstalledAndCreateSession()
                glView.onResume() // Resume GL first
                arSession?.resume()
                renderer.onResume()
                renderer.session = arSession
                Toast.makeText(this, getString(R.string.msg_cold_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_lazarus_fail, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun maybeHaptic(stability: Float, force: Boolean = false) {
        if (!force && (stability >= 0.4f || !renderer.isRecording)) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10, 50))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10)
            }
        } catch (_: Exception) {}
    }
}
