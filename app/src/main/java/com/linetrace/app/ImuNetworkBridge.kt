package com.linetrace.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImuNetworkBridge(
    val serverUrl: String,
    val room: String,
    val user: String
) : WorldSyncManager.SyncTransport {

    interface MessageListener {
        fun onPoseReceived(timestamp: Long, pos: FloatArray, rot: FloatArray)
    }

    var messageListener: MessageListener? = null
    private var worldDeltaCallback: ((WorldDelta) -> Unit)? = null
    private var client: WebSocketClient? = null
    private var lastSendTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var isClosedIntentionally = false
    private var isConnecting = false
    private var currentServerUrl = serverUrl

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
    private val surfelTransferArray = ByteArray(2000 * 64)

    init {
        connect()
    }

    fun updateServerUrl(newUrl: String) {
        val cleanUrl = newUrl.removeSuffix("/")
        if (currentServerUrl != cleanUrl) {
            Log.i("ImuNetworkBridge", "Updating server URL from $currentServerUrl to $cleanUrl")
            currentServerUrl = cleanUrl
            isClosedIntentionally = false
            
            // Force a reset of the connection state
            isConnecting = false
            cleanup()
            connect()
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

    private fun connect() {
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

        val baseUrl = currentServerUrl.removeSuffix("/")
        val uriStr = if (baseUrl.startsWith("ws://") || baseUrl.startsWith("wss://")) {
            if (baseUrl.contains("onrender.com")) {
                "$baseUrl/?room=$room&user=$user"
            } else {
                "$baseUrl/?room=$room&user=$user"
            }
        } else {
            "ws://$baseUrl:10000/?room=$room&user=$user"
        }
        
        Log.i("ImuNetworkBridge", "Connecting to: $uriStr")
        
        try {
            val newClient = object : WebSocketClient(URI(uriStr)) {
                init {
                    // Detect stale connections (Render often drops idle sockets)
                    connectionLostTimeout = 20 
                }
                override fun onOpen(handshakedata: ServerHandshake?) {
                    isConnecting = false
                    if (this != client) return
                    Log.i("ImuNetworkBridge", "SUCCESS: Connected to $uriStr")
                    isClosedIntentionally = false
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
                        }
                    } catch (e: Exception) {
                        // Ignore malformed messages
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    isConnecting = false
                    if (this != client) return
                    Log.w("ImuNetworkBridge", "CLOSED: code=$code, reason='$reason', remote=$remote")
                    if (!isClosedIntentionally) {
                        scheduleReconnect(5000)
                    }
                }

                override fun onError(ex: Exception?) {
                    isConnecting = false
                    if (this != client) return
                    Log.e("ImuNetworkBridge", "ERROR: ${ex?.message}")
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
            Log.w("ImuNetworkBridge", "Broadcasting malformed delta: $remaining bytes (not multiple of 64)")
        }

        val json = JSONObject()
        json.put("type", "world_delta")
        json.put("senderId", delta.senderId.toString())
        json.put("timestamp", delta.timestamp)
        
        // Reuse pre-allocated array if possible to avoid GC churn
        val bytes = if (remaining <= surfelTransferArray.size) {
            delta.surfelData.duplicate().get(surfelTransferArray, 0, remaining)
            surfelTransferArray
        } else {
            Log.w("ImuNetworkBridge", "Delta size $remaining exceeds reusable buffer, falling back to allocation")
            val b = ByteArray(remaining)
            delta.surfelData.duplicate().get(b)
            b
        }
        
        val base64Data = android.util.Base64.encodeToString(bytes, 0, remaining, android.util.Base64.NO_WRAP)
        if (base64Data.isNullOrEmpty()) {
            Log.w("ImuNetworkBridge", "Base64 encoding failed for delta of size $remaining")
            return
        }
        json.put("surfelData", base64Data)
        
        try {
            currentClient.send(json.toString())
            // Log.v("ImuNetworkBridge", "Sent world_delta: $remaining bytes")
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Failed to send world_delta", e)
        }
    }

    override fun onDeltaReceived(callback: (WorldDelta) -> Unit) {
        this.worldDeltaCallback = callback
    }

    /**
     * 🔥 Send CORE STATE (Enriched Cayley Graph Node)
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
            Log.d("ImuNetworkBridge", "Outgoing: $type at $timestamp")
        }

        // 1. STATE (Pos, Vel, Rot)
        val stateObj = JSONObject()
        pos?.let { stateObj.put("pos", JSONObject().apply { put("x", it[0]); put("y", it[1]); put("z", it[2]) }) }
        vel?.let { stateObj.put("vel", JSONObject().apply { put("x", it[0]); put("y", it[1]); put("z", it[2]) }) }
        rot?.let { stateObj.put("rot", JSONObject().apply { put("x", it[0]); put("y", it[1]); put("z", it[2]) }) }
        json.put("state", stateObj)

        // 2. BASIS
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
     */
    fun sendPathPoint(x: Float, y: Float, z: Float) {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) return

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
     */
    fun sendAnchor(x: Float, y: Float, z: Float) {
        val data = JSONObject().apply {
            put("anchor", JSONObject().apply { put("x", x); put("y", y); put("z", z) })
        }
        sendCoreState(type = "ar_anchor", rawData = data)

        // Legacy support
        try {
            val currentClient = client
            if (currentClient != null && currentClient.isOpen) {
                val legacy = JSONObject().apply {
                    put("type", "ar_anchor")
                    put("anchor", JSONObject().apply {
                        put("x", x); put("y", y); put("z", z)
                    })
                }
                currentClient.send(legacy.toString())
            }
        } catch (_: Exception) {}
    }

    /**
     * 📍 Send Vertical Plane
     */
    fun sendVerticalPlane(x: Float, y: Float, z: Float, height: Float, alpha: Float) {
        val data = JSONObject().apply {
            put("pos", JSONObject().apply { put("x", x); put("y", y); put("z", z) })
            put("height", height)
            put("alpha", alpha)
        }
        sendCoreState(type = "ar_vertical_plane", rawData = data)

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

    /**
     * 🔄 Clear World State on Server
     */
    fun sendReset() {
        val currentClient = client
        if (currentClient == null || !currentClient.isOpen) {
            Log.w("ImuNetworkBridge", "Cannot send reset: WebSocket not connected")
            return
        }

        val json = JSONObject().apply {
            put("type", "reset_world")
            put("user", user)
        }
        try {
            currentClient.send(json.toString())
        } catch (e: Exception) {
            Log.e("ImuNetworkBridge", "Failed to send reset: ${e.message}")
        }
        
        // Also send as core state for graph consistency
        sendCoreState(type = "reset_world")
    }

    fun close() {
        isClosedIntentionally = true
        handler.removeCallbacks(reconnectRunnable)
        client?.close()
    }
}
