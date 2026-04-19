package com.linetrace.app.feature.sync
import com.linetrace.app.feature.mapping.PoseEdge
import com.linetrace.app.core.Point

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import net.jpountz.lz4.LZ4Factory
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.security.SecureRandom

class ImuNetworkBridge(
    val serverUrl: String,
    val room: String,
    val user: String
) : WorldSyncManager.SyncTransport {

    interface MessageListener {
        fun onPoseReceived(timestamp: Long, pos: FloatArray, rot: FloatArray)
        fun onComputeResult(taskId: String, data: JSONObject)
    }

    var messageListener: MessageListener? = null
    private var worldDeltaCallback: ((WorldDelta) -> Unit)? = null
    private var client: WebSocketClient? = null
    private var lastSendTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ImuNetworkBridge-Worker").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private var isClosedIntentionally = false
    private var isConnecting = false
    private var currentServerUrl: String = ""

    init {
        currentServerUrl = normalizeUrl(serverUrl)
    }

    private fun normalizeUrl(url: String): String {
        val clean = url.trim().lowercase().removeSuffix("/")
        return when {
            clean.startsWith("ws://") || clean.startsWith("wss://") -> clean
            clean.startsWith("https://") -> clean.replace("https://", "wss://")
            clean.startsWith("http://") -> clean.replace("http://", "ws://")
            clean.contains("onrender.com") -> "wss://$clean"
            else -> "ws://$clean:10000"
        }
    }

    val isConnected: Boolean
        get() = client?.isOpen == true

    private val reconnectRunnable = Runnable {
        if (!isClosedIntentionally) {
            Log.i("ImuNetworkBridge", "Reconnection timer fired, connecting...")
            connect()
        }
    }

    // Cayley State Tracking
    private var lastImuPos: FloatArray? = null
    private var lastPathPos: FloatArray? = null
    
    // Pre-allocated buffers for zero-allocation streaming
    private val surfelTransferArray = ByteArray(2000 * 64 + 9)
    private val surfelTransferBuffer = ByteBuffer.wrap(surfelTransferArray).order(ByteOrder.LITTLE_ENDIAN)
    
    private val pcTransferArray = ByteArray(65536 * 16 + 9)
    private val pcTransferBuffer = ByteBuffer.wrap(pcTransferArray).order(ByteOrder.LITTLE_ENDIAN)

    private val compressArray = ByteArray(65536 * 16 + 9)
    private val lz4Compressor = LZ4Factory.fastestInstance().fastCompressor()

    private val poseTransferArray = ByteArray(1 + 8 + 64) 
    private val poseTransferBuffer = ByteBuffer.wrap(poseTransferArray).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        private const val TYPE_POINT_CLOUD: Byte = 0x01
        private const val TYPE_WORLD_DELTA: Byte = 0x02
        private const val TYPE_CAMERA_POSE: Byte = 0x03
        private const val TYPE_COMPRESSED_PC: Byte = 0x04
    }

    fun sendPointCloud(pcData: FloatBuffer) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return

        val remaining = pcData.remaining()
        if (remaining <= 0) return

        val byteSize = remaining * 4
        
        // Use Compressed Path (LZ4)
        try {
            val sourceBuffer = ByteBuffer.allocate(byteSize).order(ByteOrder.LITTLE_ENDIAN)
            sourceBuffer.asFloatBuffer().put(pcData.duplicate())
            val sourceArray = sourceBuffer.array()
            
            val maxCompressedLength = lz4Compressor.maxCompressedLength(byteSize)
            val compressed = ByteArray(maxCompressedLength)
            val compressedLength = lz4Compressor.compress(sourceArray, 0, byteSize, compressed, 0, maxCompressedLength)
            
            val outBuffer = ByteBuffer.allocate(1 + 8 + 4 + compressedLength).order(ByteOrder.LITTLE_ENDIAN)
            outBuffer.put(TYPE_COMPRESSED_PC)
            outBuffer.putLong(System.currentTimeMillis())
            outBuffer.putInt(byteSize) // Original size for decompressor
            outBuffer.put(compressed, 0, compressedLength)
            outBuffer.flip()
            
            currentClient.send(outBuffer)
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Failed to send compressed point cloud", e)
        }
    }

    fun sendCameraPose(matrix: FloatArray, timestamp: Long) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return

        poseTransferBuffer.clear()
        poseTransferBuffer.put(TYPE_CAMERA_POSE)
        poseTransferBuffer.putLong(timestamp)
        
        for (v in matrix) poseTransferBuffer.putFloat(v)

        try {
            poseTransferBuffer.flip()
            currentClient.send(poseTransferBuffer)
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Failed to send binary camera pose", e)
        }
    }

    fun sendComputeTask(taskId: String, taskData: JSONObject) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return

        val json = JSONObject()
        json.put("type", "compute_task")
        json.put("taskId", taskId)
        json.put("user", user)
        json.put("data", taskData)
        json.put("timestamp", System.currentTimeMillis())

        try {
            currentClient.send(json.toString())
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Failed to send compute task", e)
        }
    }

    fun updateServerUrl(newUrl: String) {
        val cleanUrl = normalizeUrl(newUrl)
        if (currentServerUrl != cleanUrl) {
            Log.i("ImuNetworkBridge", "Updating server URL from $currentServerUrl to $cleanUrl")
            currentServerUrl = cleanUrl
            isClosedIntentionally = false
            
            // Force a reset of the connection state
            isConnecting = false
            cleanup()
            // connect() // Removed auto-connect to let MainActivity control lifecycle
        }
    }

    private fun cleanup() {
        handler.removeCallbacks(reconnectRunnable)
        client?.let {
            try {
                Log.d("ImuNetworkBridge", "Cleaning up old client")
                it.close()
            } catch (e: Exception) {
                Log.e("ImuNetworkBridge", "Error closing old client", e)
            }
        }
        client = null
    }

    fun onConnectionStatusChanged(callback: (Boolean, String?) -> Unit) {
        this.statusCallback = callback
    }
    
    private var statusCallback: ((Boolean, String?) -> Unit)? = null

    private fun updateStatus(connected: Boolean, message: String? = null) {
        handler.post {
            statusCallback?.invoke(connected, message)
        }
    }

    fun connect() {
        if (isConnecting) return
        
        // Cancel any pending reconnection tasks
        handler.removeCallbacks(reconnectRunnable)

        // Close and cleanup previous client if it exists
        if (client?.isOpen == true) {
            Log.d("ImuNetworkBridge", "Closing existing connection before new attempt")
            client?.close()
        }
        client = null
        isConnecting = true

        val uriStr = if (currentServerUrl.contains("?")) {
            "$currentServerUrl&room=$room&user=$user"
        } else {
            "$currentServerUrl/?room=$room&user=$user"
        }
        
        Log.i("ImuNetworkBridge", "Connecting to: $uriStr")
        
        try {
            val uri = URI(uriStr)
            val newClient = object : WebSocketClient(uri) {
                init {
                    // Timeout-Override-Success: Increased timeout and heartbeat for Render stability
                    connectionLostTimeout = 60
                    
                    if (uriStr.startsWith("wss://", ignoreCase = true)) {
                        setupSsl()
                    }
                }

                private fun setupSsl() {
                    try {
                        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        })

                        val sc = SSLContext.getInstance("TLS")
                        sc.init(null, trustAllCerts, SecureRandom())
                        setSocketFactory(sc.socketFactory)
                        Log.d("ImuNetworkBridge", "SSL configured for wss:// (TrustAll)")
                    } catch (e: Exception) {
                        Log.e("ImuNetworkBridge", "Failed to setup SSL", e)
                    }
                }

                override fun onOpen(handshakedata: ServerHandshake?) {
                    isConnecting = false
                    if (this != client) return
                    Log.i("ImuNetworkBridge", "SUCCESS: Connected to $uriStr")
                    updateStatus(true, "Connected to $room")
                    isClosedIntentionally = false
                    flushPending()
                }

                override fun onMessage(message: String?) {
                    if (this != client) return
                    try {
                        val json = JSONObject(message ?: return)
                        val type = json.optString("type")
                        
                        when (type) {
                            "pose", "path_point" -> {
                                val state = json.optJSONObject("state")
                                val posObj = state?.optJSONObject("pos")
                                val rotObj = state?.optJSONObject("rot")
                                
                                if (posObj != null) {
                                    // Protocol Alignment: Use raw coordinates (Relative to Anchor)
                                    val pos = floatArrayOf(
                                        posObj.optDouble("x").toFloat(),
                                        posObj.optDouble("y").toFloat(),
                                        posObj.optDouble("z").toFloat()
                                    )
                                    val rot = if (rotObj != null) {
                                        floatArrayOf(
                                            rotObj.optDouble("x").toFloat(),
                                            rotObj.optDouble("y").toFloat(),
                                            rotObj.optDouble("z").toFloat(),
                                            rotObj.optDouble("w").toFloat()
                                        )
                                    } else floatArrayOf(0f, 0f, 0f, 1f)
                                    
                                    val ts = json.optLong("timestamp", System.currentTimeMillis())
                                    messageListener?.onPoseReceived(ts, pos, rot)
                                }
                            }
                            "world_delta" -> {
                                val senderIdStr = json.getString("senderId")
                                val timestamp = json.getLong("timestamp")
                                val surfelDataBase64 = json.optString("surfelData", "")
                                
                                if (surfelDataBase64.isNotEmpty()) {
                                    val data = android.util.Base64.decode(surfelDataBase64, android.util.Base64.NO_WRAP)
                                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                                    
                                    worldDeltaCallback?.invoke(WorldDelta(
                                        senderId = java.util.UUID.fromString(senderIdStr),
                                        timestamp = timestamp,
                                        surfelData = buffer
                                    ))
                                }
                            }
                            "compute_result" -> {
                                val taskId = json.optString("taskId")
                                val resultData = json.optJSONObject("data")
                                if (taskId.isNotEmpty() && resultData != null) {
                                    messageListener?.onComputeResult(taskId, resultData)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore malformed messages
                    }
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    if (this != client || bytes == null) return
                    try {
                        bytes.order(ByteOrder.LITTLE_ENDIAN)
                        val type = bytes.get()
                        when (type) {
                            TYPE_WORLD_DELTA -> {
                                val timestamp = bytes.long
                                val msb = bytes.long
                                val lsb = bytes.long
                                val senderId = java.util.UUID(msb, lsb)
                                
                                val surfelData = bytes.slice().order(ByteOrder.LITTLE_ENDIAN)
                                worldDeltaCallback?.invoke(WorldDelta(senderId, timestamp, surfelData))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ImuNetworkBridge", "Error parsing binary message", e)
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    isConnecting = false
                    if (this != client) return
                    Log.w("ImuNetworkBridge", "CLOSED: code=$code, reason='$reason', remote=$remote")
                    updateStatus(false, "Disconnected: $reason")
                    if (!isClosedIntentionally) {
                        scheduleReconnect(5000)
                    }
                }

                override fun onError(ex: Exception?) {
                    isConnecting = false
                    if (this != client) return
                    Log.e("ImuNetworkBridge", "ERROR: ${ex?.message}")
                    updateStatus(false, "Error: ${ex?.message}")
                    ex?.printStackTrace()
                }
            }
            client = newClient
            newClient.connect()
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Initial connection setup failed", e)
            scheduleReconnect(5000)
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    override fun broadcastDelta(delta: WorldDelta) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return
        
        val remaining = delta.surfelData.remaining()
        if (remaining <= 0) return

        if (remaining % 64 != 0) {
            Log.e("ImuNetworkBridge", "REJECTED: Malformed delta ($remaining bytes)")
            return
        }

        // Zero-Allocation Binary Path for Surfel Deltas
        surfelTransferBuffer.clear()
        surfelTransferBuffer.put(TYPE_WORLD_DELTA)
        surfelTransferBuffer.putLong(delta.timestamp)
        
        val dup = delta.surfelData.duplicate()
        surfelTransferBuffer.put(dup)
        
        try {
            surfelTransferBuffer.position(0)
            surfelTransferBuffer.limit(1 + 8 + remaining)
            currentClient.send(surfelTransferBuffer)
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Failed to send binary world_delta", e)
        }
    }

    override fun onDeltaReceived(callback: (WorldDelta) -> Unit) {
        this.worldDeltaCallback = callback
    }

    /**
     * 🔥 Send CORE STATE (Enriched Cayley Graph Node)
     * NEGATES anchor updates in rawData if present for Web Visualizer centering
     */
    fun sendCoreState(
        type: String,
        pos: FloatArray? = null,
        vel: FloatArray? = null,
        rot: FloatArray? = null,
        forward: FloatArray? = floatArrayOf(0f, 0f, 1f),
        right: FloatArray? = floatArrayOf(1f, 0f, 0f),
        up: FloatArray? = floatArrayOf(0f, 1f, 0f),
        edges: List<PoseEdge>? = null,
        timestamp: Long = System.currentTimeMillis(),
        rawData: JSONObject? = null
    ) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return

        val json = JSONObject()
        json.put("type", type)
        json.put("node", user)
        json.put("timestamp", timestamp)

        // Log one in every 100 messages to verify data flow without flooding
        if (Math.random() < 0.01) {
            Log.d("ImuNetworkBridge", "Outgoing: $type at $timestamp | isConnected: ${client?.isOpen}")
        }

        // 1. STATE (Pos, Vel, Rot)
        // Protocol Alignment: Send raw coordinates (Visualizer uses Position - Anchor)
        val stateObj = JSONObject()
        pos?.let { stateObj.put("pos", JSONObject().apply { put("x", it[0]); put("y", it[1]); put("z", it[2]) }) }
        vel?.let { stateObj.put("vel", JSONObject().apply { put("x", it[0]); put("y", it[1]); put("z", it[2]) }) }
        rot?.let { stateObj.put("rot", JSONObject().apply { put("x", it[0]); put("y", it[1]); put("z", it[2]); put("w", it[3]) }) }
        json.put("state", stateObj)

        // 2. BASIS
        // Use raw basis vectors
        val basisObj = JSONObject()
        basisObj.put("forward", JSONArray().apply { (forward ?: floatArrayOf(0f,0f,1f)).forEach { put(it) } })
        basisObj.put("right", JSONArray().apply { (right ?: floatArrayOf(1f,0f,0f)).forEach { put(it) } })
        basisObj.put("up", JSONArray().apply { (up ?: floatArrayOf(0f,1f,0f)).forEach { put(it) } })
        json.put("basis", basisObj)

        // 3. EDGES (Cayley Temporal Continuity)
        val edgesArray = JSONArray()
        
        // Automatic Temporal Edge (Euclidean Delta)
        if (type == "imu" && pos != null) {
            lastImuPos?.let { prev ->
                val edgeObj = JSONObject()
                edgeObj.put("to", "prev")
                edgeObj.put("transform", "delta")
                edgeObj.put("delta", JSONObject().apply {
                    put("dx", pos[0] - prev[0])
                    put("dy", pos[1] - prev[1])
                    put("dz", pos[2] - prev[2])
                })
                edgesArray.put(edgeObj)
            }
            lastImuPos = pos.clone()
        } else if (type == "path_point" && pos != null) {
            lastPathPos?.let { prev ->
                val edgeObj = JSONObject()
                edgeObj.put("to", "prev_path")
                edgeObj.put("transform", "euclidean_delta")
                edgeObj.put("delta", JSONObject().apply {
                    put("dx", pos[0] - prev[0])
                    put("dy", pos[1] - prev[1])
                    put("dz", pos[2] - prev[2])
                })
                edgesArray.put(edgeObj)
            }
            lastPathPos = pos.clone()
        }

        // Manual Graph Edges (from PoseGraph)
        edges?.forEach { edge ->
            val edgeObj = JSONObject()
            edgeObj.put("to", "node_${edge.to}")
            edgeObj.put("transform", "SE3")
            edgeObj.put("delta", JSONObject().apply {
                put("dx", edge.transform[12])
                put("dy", edge.transform[13])
                put("dz", edge.transform[14])
            })
            edgesArray.put(edgeObj)
        }
        
        json.put("edges", edgesArray)

        if (rawData != null) {
            json.put("data", rawData)
        }

        currentClient.send(json.toString())
    }

    fun sendIMU(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return
        
        val now = System.currentTimeMillis()
        if (now - lastSendTime < 10) return // ~100Hz
        lastSendTime = now

        // Use raw IMU data
        val data = JSONObject().apply {
            put("ax", ax); put("ay", ay); put("az", az)
            put("gx", gx); put("gy", gy); put("gz", gz)
        }
        sendCoreState(type = "imu", rawData = data)
        
        // Legacy support
        try {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                val legacy = JSONObject().apply {
                    put("type", "imu")
                    put("data", JSONObject().apply {
                        put("x", ax); put("y", ay); put("z", az)
                    })
                }
                currentClient.send(legacy.toString())
            }
        } catch (_: Exception) {}
    }

    /**
     * 📍 Send Pose (Matches WebSocketManager)
     */
    fun sendPose(timestamp: Long, pos: FloatArray, rot: FloatArray) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return

        sendCoreState(
            type = "pose",
            pos = pos,
            rot = rot,
            timestamp = timestamp
        )
        
        // Legacy support (path_point is usually enough for visualizer)
        sendPathPoint(pos[0], pos[1], pos[2])
    }

    /**
     * 📍 Send path data
     * Protocol Alignment: Negates x, y, z for Web Visualizer centering
     */
    fun sendPathPoint(x: Float, y: Float, z: Float) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) {
            if (Math.random() < 0.01) Log.w("ImuNetworkBridge", "Cannot send path_point: Client NULL or CLOSED")
            return
        }

        sendCoreState(type = "path_point", pos = floatArrayOf(x, y, z))
        
        // Legacy support for Three.js visualizer
        try {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                val legacy = JSONObject().apply {
                    put("type", "path_point")
                    put("x", x)
                    put("y", y)
                    put("z", z)
                    put("user", user)
                }
                currentClient.send(legacy.toString())
            }
        } catch (_: Exception) {}
    }

    /**
     * 📍 Send AR anchor (Matches Server: msg.type === "ar_anchor")
     * NEGATES anchor coordinates for Web Visualizer centering (-x, -y, -z)
     */
    fun sendAnchor(x: Float, y: Float, z: Float) {
        val data = JSONObject().apply {
            put("anchor", JSONObject().apply { 
                put("x", -x) 
                put("y", -y) 
                put("z", -z) 
            })
        }
        sendCoreState(type = "ar_anchor", pos = floatArrayOf(x, y, z), rawData = data)

        // Legacy support
        try {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                val legacy = JSONObject().apply {
                    put("type", "ar_anchor")
                    put("anchor", JSONObject().apply {
                        put("x", -x); put("y", -y); put("z", -z)
                    })
                }
                currentClient.send(legacy.toString())
            }
        } catch (_: Exception) {}
    }

    /**
     * 📍 Send Vertical Plane
     * Protocol Alignment: Negates x, y, z for Web Visualizer centering
     */
    fun sendVerticalPlane(x: Float, y: Float, z: Float, height: Float, alpha: Float) {
        val data = JSONObject().apply {
            put("pos", JSONObject().apply { put("x", x); put("y", y); put("z", z) })
            put("height", height)
            put("alpha", alpha)
        }
        sendCoreState(type = "ar_vertical_plane", pos = floatArrayOf(x, y, z), rawData = data)

        // Legacy support
        try {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                val legacy = JSONObject().apply {
                    put("type", "ar_vertical_plane")
                    put("pos", JSONObject().apply {
                        put("x", x); put("y", y); put("z", z)
                    })
                    put("height", height)
                    put("alpha", alpha)
                }
                currentClient.send(legacy.toString())
            }
        } catch (_: Exception) {}
    }

    /**
     * 📍 Send POI (Point of Interest)
     * Protocol Alignment: Negates x, y, z for Web Visualizer centering
     */
    fun sendPoi(x: Float, y: Float, z: Float) {
        val data = JSONObject().apply {
            put("x", x); put("y", y); put("z", z)
            put("user", user)
        }
        sendCoreState(type = "poi", pos = floatArrayOf(x, y, z), rawData = data)

        // Legacy support
        try {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                val legacy = JSONObject().apply {
                    put("type", "poi")
                    put("x", x); put("y", y); put("z", z)
                    put("user", user)
                }
                currentClient.send(legacy.toString())
            }
        } catch (_: Exception) {}
    }

    /**
     * 🌡️ Send Thermal Heartbeat
     */
    fun sendThermal(temp: Float) {
        val data = JSONObject().apply {
            put("celsius", temp)
            put("status", when {
                temp > 45f -> "CRITICAL"
                temp > 40f -> "HOT"
                else -> "NOMINAL"
            })
        }
        sendCoreState(type = "thermal_heartbeat", rawData = data)
    }

    private val pendingMessages = mutableListOf<String>()

    /**
     * 🔄 Clear World State on Server
     */
    fun sendReset() {
        val json = JSONObject().apply {
            put("type", "reset_world")
            put("user", user)
            put("room", room)
        }
        enqueueOrSend(json.toString())
        
        // Also send as core state for graph consistency
        sendCoreState(type = "reset_world")
    }

    private fun enqueueOrSend(message: String) {
        networkExecutor.execute {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                try {
                    currentClient.send(message)
                } catch (e: Exception) {
                    Log.e("ImuNetworkBridge", "Failed to send: ${e.message}")
                    synchronized(pendingMessages) { pendingMessages.add(message) }
                }
            } else {
                Log.d("ImuNetworkBridge", "Queueing message (Socket not open)")
                synchronized(pendingMessages) {
                    if (pendingMessages.size > 100) pendingMessages.removeAt(0)
                    pendingMessages.add(message)
                }
            }
        }
    }

    private fun flushPending() {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return
        
        synchronized(pendingMessages) {
            val iterator = pendingMessages.iterator()
            while (iterator.hasNext()) {
                try {
                    currentClient.send(iterator.next())
                    iterator.remove()
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun close() {
        isClosedIntentionally = true
        handler.removeCallbacks(reconnectRunnable)
        client?.close()
    }
}
