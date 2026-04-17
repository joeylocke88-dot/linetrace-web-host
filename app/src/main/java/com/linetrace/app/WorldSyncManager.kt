package com.linetrace.app

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString

data class WorldDelta(
    val senderId: UUID,
    val timestamp: Long,
    val surfelData: ByteBuffer // 64-byte Flowstate V12 format
)

class WorldSyncManager(
    private val context: Context,
    private val streamer: WorldStreamer,
    private val localId: UUID = UUID.randomUUID()
) {

    interface SyncTransport {
        fun broadcastDelta(delta: WorldDelta)
        fun onDeltaReceived(callback: (WorldDelta) -> Unit)
    }

    private var transport: SyncTransport? = null
    private var webSocket: WebSocket? = null
    private var remotePathCallback: ((Float, Float, Float, Float) -> Unit)? = null

    fun setRemotePathCallback(callback: (Float, Float, Float, Float) -> Unit) {
        this.remotePathCallback = callback
    }

    // === WebSocket Transport (Render) ===
    // Deprecated: Use ImuNetworkBridge as SyncTransport instead
    inner class WebSocketTransport : SyncTransport {
        val client = OkHttpClient.Builder()
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS) // Heartbeat to prevent Render spin-down
            .build()
        private var callback: ((WorldDelta) -> Unit)? = null

        fun connect(room: String = "default") {
            val url = "wss://linetrace-web-host.onrender.com?room=$room&user=android_${localId.toString().take(8)}"
            Log.d("WorldSync", "Connecting to WebSocket: $url")
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i("WorldSync", "WebSocket Connected Successfully")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = org.json.JSONObject(text)
                        if (json.has("type") && json.getString("type") == "world_delta") {
                            // Extract data from JSON
                            val senderIdStr = json.getString("senderId")
                            val timestamp = json.getLong("timestamp")
                            val surfelDataBase64 = json.optString("surfelData", "")
                            
                            if (surfelDataBase64.isNotEmpty()) {
                                val data = android.util.Base64.decode(surfelDataBase64, android.util.Base64.NO_WRAP)
                                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                                
                                callback?.invoke(WorldDelta(
                                    senderId = UUID.fromString(senderIdStr),
                                    timestamp = timestamp,
                                    surfelData = buffer
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WorldSync", "Error parsing WebSocket message", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WorldSync", "WebSocket failed. Code: ${response?.code}, Message: ${response?.message}", t)
                }
            })
        }

        override fun broadcastDelta(delta: WorldDelta) {
            val ws = webSocket
            if (ws == null) {
                if (System.currentTimeMillis() % 5000 < 100) {
                    Log.w("WorldSync", "Cannot broadcast: WebSocket is null. Reconnecting...")
                    connect() 
                }
                return
            }
            try {
                ws.send(delta.toJsonString())
            } catch (e: Exception) {
                Log.e("WorldSync", "Broadcast failed", e)
            }
        }

        override fun onDeltaReceived(callback: (WorldDelta) -> Unit) {
            this.callback = callback
        }
    }

    // Helper to convert WorldDelta to JSON for WebSocket
    private fun WorldDelta.toJsonString(): String {
        val json = org.json.JSONObject()
        json.put("type", "world_delta")
        json.put("senderId", senderId.toString())
        json.put("timestamp", timestamp)
        
        val bytes = ByteArray(surfelData.remaining())
        surfelData.duplicate().get(bytes)
        val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        json.put("surfelData", base64Data)
        
        return json.toString()
    }

    class NearbyTransport(
        private val context: Context,
        private val serviceId: String = "com.linetrace.WORLD_SYNC"
    ) : SyncTransport {
        private val connectionsClient = Nearby.getConnectionsClient(context)
        private val connectedEndpoints = mutableSetOf<String>()
        private var deltaCallback: ((WorldDelta) -> Unit)? = null

        private val payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                payload.asBytes()?.let { bytes ->
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val senderIdMSB = buffer.long
                    val senderIdLSB = buffer.long
                    val timestamp = buffer.long
                    val surfelData = buffer.slice()
                    
                    deltaCallback?.invoke(WorldDelta(
                        senderId = UUID(senderIdMSB, senderIdLSB),
                        timestamp = timestamp,
                        surfelData = surfelData
                    ))
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }

        private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    connectedEndpoints.add(endpointId)
                }
            }

            override fun onDisconnected(endpointId: String) {
                connectedEndpoints.remove(endpointId)
            }
        }

        fun start() {
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            connectionsClient.startAdvertising("Linetrace-${UUID.randomUUID().toString().take(4)}", 
                serviceId, connectionLifecycleCallback, options)
            
            val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            connectionsClient.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    connectionsClient.requestConnection("Linetrace-Client", endpointId, connectionLifecycleCallback)
                }
                override fun onEndpointLost(endpointId: String) {}
            }, discoveryOptions)
        }

        override fun broadcastDelta(delta: WorldDelta) {
            if (connectedEndpoints.isEmpty()) return
            
            val size = 16 + 8 + delta.surfelData.remaining()
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putLong(delta.senderId.mostSignificantBits)
            buffer.putLong(delta.senderId.leastSignificantBits)
            buffer.putLong(delta.timestamp)
            buffer.put(delta.surfelData.duplicate())
            
            val payload = Payload.fromBytes(buffer.array())
            connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
        }

        override fun onDeltaReceived(callback: (WorldDelta) -> Unit) {
            this.deltaCallback = callback
        }
    }

    fun setTransport(transport: SyncTransport) {
        this.transport = transport
        transport.onDeltaReceived { delta ->
            if (delta.senderId != localId) {
                val data = delta.surfelData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val remaining = data.remaining()
                
                if (remaining == 16) {
                    // Path Point: x, y, z, stability
                    // Protocol Alignment: These are already negated by the sender (ImuNetworkBridge)
                    val x = data.float
                    val y = data.float
                    val z = data.float
                    val s = data.float
                    remotePathCallback?.invoke(x, y, z, s)
                } else if (remaining > 0 && remaining % 64 == 0) {
                    // World Surfel Chunk (64-byte blocks)
                    Log.d("WorldSync", "Received world delta ($remaining bytes) from ${delta.senderId}")
                    streamer.addSurfelsAsync(delta.surfelData)
                } else {
                    Log.w("WorldSync", "Received malformed delta ($remaining bytes) - Discarding")
                }
            }
        }
    }

    fun connectToWebHost(room: String = "default") {
        val wsTransport = WebSocketTransport()
        wsTransport.connect(room)
        setTransport(wsTransport)
        Log.i("WorldSync", "Connected to Render Web Host (room: $room)")
    }

    fun syncLocalBatch(rawData: ByteBuffer) {
        val transport = this.transport ?: return
        
        val delta = WorldDelta(
            senderId = localId,
            timestamp = System.currentTimeMillis(),
            surfelData = rawData
        )
        transport.broadcastDelta(delta)
    }

    fun syncTelemetry(distance: Float, durationMs: Long, avgSpeed: Float, points: Int) {
        val client = (transport as? WebSocketTransport)?.client ?: OkHttpClient()
        val json = org.json.JSONObject().apply {
            put("type", "telemetry")
            put("senderId", localId.toString())
            put("distance", distance.toDouble())
            put("durationMs", durationMs)
            put("avgSpeed", avgSpeed.toDouble())
            put("points", points)
            put("device", android.os.Build.MODEL)
        }

        val request = Request.Builder()
            .url("https://linetrace-web-host.onrender.com/telemetry")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e("WorldSync", "Failed to sync telemetry", e)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.i("WorldSync", "Telemetry synced: ${response.code}")
                response.close()
            }
        })
    }

    fun broadcastChunk(chunk: WorldChunk) {
        val data = chunk.surfelData ?: return
        val buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        syncLocalBatch(buffer)
    }

    fun broadcastDelta(delta: WorldDelta) {
        transport?.broadcastDelta(delta)
    }

    fun close() {
        webSocket?.close(1000, "App closing")
        webSocket = null
        
        (transport as? WebSocketTransport)?.client?.dispatcher?.executorService?.shutdown()
        (transport as? WebSocketTransport)?.client?.connectionPool?.evictAll()
        
        (transport as? NearbyTransport)?.let { nearby ->
            Nearby.getConnectionsClient(context).stopAllEndpoints()
            Nearby.getConnectionsClient(context).stopAdvertising()
            Nearby.getConnectionsClient(context).stopDiscovery()
        }

        transport = null
    }
}
