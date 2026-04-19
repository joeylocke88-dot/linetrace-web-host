package com.linetrace.app.presentation

import com.linetrace.app.feature.sync.WorldDelta
import com.linetrace.app.feature.sync.WorldSyncManager
import com.linetrace.app.feature.perception.FusionEngine
import com.linetrace.app.feature.telemetry.PathBuffer
import com.linetrace.app.feature.perception.PoseStabilizer
import com.linetrace.app.feature.mapping.WorldStreamer
import com.linetrace.app.feature.perception.MotionTracker
import com.linetrace.app.feature.sync.ImuNetworkBridge
import com.linetrace.app.feature.mapping.GpuPoseSolver
import com.linetrace.app.feature.mapping.WorldChunk
import com.linetrace.app.feature.telemetry.SessionRecorder
import com.linetrace.app.core.FusedState
import com.linetrace.app.core.Point
import com.linetrace.app.core.PointType
import com.linetrace.app.render.*
import com.linetrace.app.R

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import com.google.ar.core.Coordinates2d

class LineRenderer(
    private val motionTracker: MotionTracker,
    private val fusion: FusionEngine,
    private val livePath: PathBuffer,
    private val ghostPath: PathBuffer,
    private val recorder: SessionRecorder,
    initialImuNetworkBridge: ImuNetworkBridge
) : GLSurfaceView.Renderer {

    private val renderLock = Any()
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var pendingReset = false

    private var backgroundTextureId = -1
    private var cameraDepthTextureId = -1
    
    private val backgroundRenderer = BackgroundRenderer()
    private val pathRenderer = PathRenderer()
    private val environmentRenderer = EnvironmentRenderer()
    private val surfelRenderer = SurfelRenderer()
    private val lineCrawler = LineCrawler(motionTracker.context)

    private var _session: Session? = null
    var session: Session?
        get() = synchronized(renderLock) { _session }
        set(value) {
            synchronized(renderLock) {
                _session = value
                if (value == null) {
                    latestFrame = null
                } else {
                    if (backgroundTextureId != -1) {
                        value.setCameraTextureNames(intArrayOf(backgroundTextureId))
                    }
                    value.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                }
            }
        }

    var latestFrame: Frame? = null
        private set

    var isTracking = false
        private set

    var tracingState = TracingState.IDLE
        private set(value) {
            field = value
            frameCallback?.onTracingStateChanged(value)
        }

    enum class TracingState { IDLE, TRACING, PAUSED, FINISHED }

    var isRecording = false
        private set

    var displayRotation: Int = 0

    private val poiChunks = mutableMapOf<Long, MutableList<Point>>()
    private val livePathChunks = mutableMapOf<Long, PathBuffer>()
    private val ghostPathChunks = mutableMapOf<Long, PathBuffer>()

    private fun getSpatialKey(x: Float, y: Float, z: Float): Long {
        val cx = (x / 2.0f).toInt()
        val cy = (y / 2.0f).toInt()
        val cz = (z / 2.0f).toInt()
        return (cx.toLong() shl 42) or ((cy.toLong() and 0x1FFFFF) shl 21) or (cz.toLong() and 0x1FFFFF)
    }

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    
    private val frameCameraMatrix = FloatArray(16)
    private val surfelFusionCameraMatrix = FloatArray(16)

    private val origin = FloatArray(3)
    private var originSet = false
    private var startAnchor: Anchor? = null

    private val poseStabilizer = PoseStabilizer()
    private val gpuSolver = GpuPoseSolver(motionTracker.context)
    val worldStreamer = WorldStreamer(File(motionTracker.context.filesDir, "world_cache"))
    val worldSync = WorldSyncManager(motionTracker.context, worldStreamer)
    var imuNetworkBridge: ImuNetworkBridge = initialImuNetworkBridge
        set(value) {
            field = value
            wsManager = value
        }
    private var wsManager = initialImuNetworkBridge
    
    private var frameCount = 0
    private var lastFrameNanos = 0L

    var isDiagnosticOverlayEnabled = false
    var isLineCrawlerEnabled = false
    var isHypervisor = false
    private var currentThermalTemp = 0f

    fun updateThermalState(temp: Float) {
        currentThermalTemp = temp
    }

    var wallHeight = 2.0f
    var wallAlpha = 0.4f
    var planeAlpha = 0.15f

    private val viewCoordsBuffer = ByteBuffer.allocateDirect(3 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
        flip()
    }
    private val texCoordsBuffer = ByteBuffer.allocateDirect(3 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val depthUvMatrix = FloatArray(9)

    var frameCallback: FrameCallback? = null
    interface FrameCallback {
        fun onCameraPoseAvailable(success: Boolean)
        fun onFusedState(state: FusedState)
        fun onRendererStalled()
        fun onTracingStateChanged(state: TracingState)
        fun onDepthUpdate(depth: Float)
    }

    fun onResume() {
        Log.i("LineRenderer", "Renderer resumed")
        worldStreamer.restart()
    }

    fun onPause() {
        worldStreamer.shutdown()
        worldSync.close()
        Log.i("LineRenderer", "Renderer paused")
    }

    fun onDestroy() {
        worldSync.close()
        worldStreamer.shutdown()
        gpuSolver.onDestroy()
        lineCrawler.onDestroy()
        backgroundRenderer.onDestroy()
        pathRenderer.onDestroy()
        environmentRenderer.onDestroy()
        surfelRenderer.onDestroy()
        
        synchronized(renderLock) {
            livePath.resetResources()
            ghostPath.resetResources()
            livePathChunks.values.forEach { it.resetResources() }
            ghostPathChunks.values.forEach { it.resetResources() }

            if (backgroundTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(backgroundTextureId), 0)
                backgroundTextureId = 0
            }
            if (cameraDepthTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(cameraDepthTextureId), 0)
                cameraDepthTextureId = 0
            }
        }
        Log.i("LineRenderer", "Renderer: Lazarus Cleanup Complete")
    }

    fun checkHealth() {
        if (_session == null) return
        val now = System.nanoTime()
        if (isTracking && (now - lastFrameNanos) > 500_000_000L && lastFrameNanos != 0L) {
            Log.w("LineRenderer", "Lazarus: Renderer stall detected!")
            frameCallback?.onRendererStalled()
            lastFrameNanos = now
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i("LineRenderer", "onSurfaceCreated")
        
        gpuSolver.init()
        lineCrawler.init()
        backgroundRenderer.init()
        pathRenderer.init()
        environmentRenderer.init()
        surfelRenderer.init()

        GLES30.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.0f, 1.0f)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        
        backgroundTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        cameraDepthTextureId = textures[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

        _session?.setCameraTextureNames(intArrayOf(backgroundTextureId))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        _session?.setDisplayGeometry(displayRotation, width, height)
    }

    @Volatile var latestFusedState: FusedState? = null
    @Volatile var currentCenterDepth: Float = 0f

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val isStalled = (now - lastFrameNanos) > 100_000_000L
        lastFrameNanos = now

        synchronized(renderLock) {
            if (pendingReset) {
                performResetInternal()
                pendingReset = false
            }
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val arSession = _session ?: return
        val frame = try {
            arSession.update().also { latestFrame = it }
        } catch (e: Exception) {
            null
        } ?: return

        val camera = frame.camera
        isTracking = camera.trackingState == TrackingState.TRACKING
        
        handleSurfelFusion(frame)

        synchronized(renderLock) {
            val cameraPose = camera.displayOrientedPose
            cameraPose.toMatrix(frameCameraMatrix, 0)
            val stabilizedPose = if (isStalled && poseStabilizer.initialized) {
                poseStabilizer.lastPose
            } else {
                poseStabilizer.update(frameCameraMatrix)
            }

            updateOriginAndAnchors(arSession, camera, stabilizedPose)
            
            val camPos = floatArrayOf(stabilizedPose[12], stabilizedPose[13], stabilizedPose[14])
            if (originSet) {
                handleWorldStreaming(camPos)
                gpuSolver.updateMirrorShield()
            }

            if (frameCount % 300 == 0) wsManager.sendThermal(currentThermalTemp)

            backgroundRenderer.draw(frame, backgroundTextureId)

            Matrix.invertM(view, 0, stabilizedPose, 0)
            camera.getProjectionMatrix(projection, 0, 0.1f, 100.0f)
            Matrix.multiplyMM(vpMatrix, 0, projection, 0, view, 0)

            updateDepthMatrices(frame)

            if (tracingState == TracingState.TRACING) {
                startAnchor?.let { if (it.trackingState == TrackingState.TRACKING) adaptToAnchor(it) }
            }

            updateFusionAndPose(frame, stabilizedPose)

            environmentRenderer.drawPlanes(arSession, vpMatrix, wallHeight, wallAlpha, planeAlpha, wsManager)
            
            if (isTracking || tracingState != TracingState.IDLE) {
                renderPathAndVolume(frame, camPos, isStalled)
            }
            GLES30.glLineWidth(1.0f)
        }
    }

    private fun handleSurfelFusion(frame: Frame) {
        val pointCloud = try { frame.acquirePointCloud() } catch (e: Exception) { null }
        pointCloud?.let { pc ->
            if (isTracking && frameCount % 10 == 0) {
                frame.camera.displayOrientedPose.toMatrix(surfelFusionCameraMatrix, 0)
                
                if (isHypervisor) {
                    // GPU Boost: Offload point cloud and camera pose for remote surfel fusion
                    val pcBuffer = pc.points
                    wsManager.sendPointCloud(pcBuffer)
                    val poseJson = JSONObject().apply {
                        val matrixArr = JSONArray()
                        for (v in surfelFusionCameraMatrix) matrixArr.put(v)
                        put("cameraPose", matrixArr)
                        put("timestamp", frame.timestamp)
                    }
                    wsManager.sendComputeTask("surfel_fusion", poseJson)
                } else {
                    val prevCount = gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())
                    gpuSolver.fuseSurfels(pc.points, surfelFusionCameraMatrix, frame.timestamp)
                    val newCount = gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())
                    
                    val deltaCount = newCount - prevCount
                    if (deltaCount > 0) {
                        val deltaData = gpuSolver.downloadSurfelDelta(prevCount, deltaCount)
                        if (deltaData != null) {
                            deltaData.rewind()
                            val senderId = java.util.UUID.nameUUIDFromBytes(wsManager.user.toByteArray())
                            if (frameCount % 3 == 0) {
                                worldSync.broadcastDelta(WorldDelta(senderId, frame.timestamp, deltaData))
                            }
                        }
                    }

                    val camX = surfelFusionCameraMatrix[12]
                    val camY = surfelFusionCameraMatrix[13]
                    val camZ = surfelFusionCameraMatrix[14]
                    val spatialMin = floatArrayOf(
                        kotlin.math.floor(camX - 512f),
                        kotlin.math.floor(camY - 512f),
                        kotlin.math.floor(camZ - 512f)
                    )
                    gpuSolver.buildSpatialIndex(gpuSolver.getActiveBufferIndex(), spatialMin, 1.0f)
                }
            }
            pc.release()
        }
    }

    private fun updateOriginAndAnchors(arSession: Session, camera: Camera, stabilizedPose: FloatArray) {
        if (!originSet && isTracking) {
            origin[0] = stabilizedPose[12]; origin[1] = stabilizedPose[13]; origin[2] = stabilizedPose[14]
            originSet = true
            startAnchor = try { arSession.createAnchor(camera.displayOrientedPose) } catch (e: Exception) { null }
            recorder.start()
            wsManager.sendAnchor(origin[0], origin[1], origin[2])
        }
    }

    private fun handleWorldStreaming(camPos: FloatArray) {
        if (frameCount++ % 60 == 0) {
            val dist = sqrt((camPos[0]-origin[0])*(camPos[0]-origin[0]) + (camPos[2]-origin[2])*(camPos[2]-origin[2]))
            if (dist > 5.0f) performPlaneSync()

            gpuSolver.processWorld(camPos, worldStreamer, object : GpuPoseSolver.WorldCallback {
                override fun onChunkCompressed(chunk: WorldChunk) {
                    worldStreamer.saveChunkSync(chunk)
                    worldSync.broadcastChunk(chunk)
                }
            })

            val nearbyChunks = worldStreamer.getChunksInRegion(camPos[0], camPos[1], camPos[2], 8.0f)
            for (chunk in nearbyChunks) {
                chunk.surfelData?.let { gpuSolver.uploadSurfels(it) }
                chunk.pathData?.let { data ->
                    val key = getSpatialKey(chunk.x * 2.0f, chunk.y * 2.0f, chunk.z * 2.0f)
                    synchronized(ghostPathChunks) {
                        if (!ghostPathChunks.containsKey(key)) {
                            val pb = PathBuffer(capacityPoints = 1000)
                            data.rewind()
                            val floats = FloatArray(data.remaining() / 4)
                            data.asFloatBuffer().get(floats)
                            pb.importHistory(floats, floats.size / 4)
                            ghostPathChunks[key] = pb
                        }
                    }
                }
            }
        }

        if (!worldStreamer.isShutdown()) {
            worldStreamer.evictFarChunksAsync(camPos[0], camPos[1], camPos[2], 20.0f)
            synchronized(ghostPathChunks) {
                val it = ghostPathChunks.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    val key = entry.key
                    val cx = (key shr 42).toInt() * 2.0f
                    val cy = ((key shr 21) and 0x1FFFFF).toInt() * 2.0f
                    val cz = (key and 0x1FFFFF).toInt() * 2.0f
                    if ((cx-camPos[0])*(cx-camPos[0]) + (cy-camPos[1])*(cy-camPos[1]) + (cz-camPos[2])*(cz-camPos[2]) > 400.0f) {
                        entry.value.resetResources()
                        it.remove()
                    }
                }
            }
        }
    }

    private fun updateDepthMatrices(frame: Frame) {
        viewCoordsBuffer.rewind(); texCoordsBuffer.rewind()
        frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, viewCoordsBuffer, Coordinates2d.TEXTURE_NORMALIZED, texCoordsBuffer)
        val m0 = texCoordsBuffer.get(2) - texCoordsBuffer.get(0)
        val m1 = texCoordsBuffer.get(4) - texCoordsBuffer.get(0)
        val m2 = texCoordsBuffer.get(0)
        val m3 = texCoordsBuffer.get(3) - texCoordsBuffer.get(1)
        val m4 = texCoordsBuffer.get(5) - texCoordsBuffer.get(1)
        val m5 = texCoordsBuffer.get(1)
        depthUvMatrix[0] = m0; depthUvMatrix[3] = m1; depthUvMatrix[6] = m2
        depthUvMatrix[1] = m3; depthUvMatrix[4] = m4; depthUvMatrix[7] = m5
        depthUvMatrix[2] = 0f; depthUvMatrix[5] = 0f; depthUvMatrix[8] = 1f
    }

    private fun updateFusionAndPose(frame: Frame, stabilizedPose: FloatArray) {
        val camera = frame.camera
        if (isTracking) {
            fusion.isArTracking = true
            val translation = floatArrayOf(stabilizedPose[12], stabilizedPose[13], stabilizedPose[14])
            fusion.updateFromAR(Pose.makeTranslation(translation[0], translation[1], translation[2]), frame.timestamp)
            frameCallback?.onCameraPoseAvailable(true)
        } else {
            fusion.isArTracking = false
            frameCallback?.onCameraPoseAvailable(false)
        }

        val samples = motionTracker.pollSamples()
        for (sample in samples) {
            if (sample.valid) {
                fusion.updateFromIMU(sample)
                if (tracingState == TracingState.TRACING && fusion.hasPose) {
                    val f = fusion.fusedState()
                    recordPoint(f.x, f.y, f.z, sample.timestampNanos, f.visualQuality, PointType.NORMAL)
                }
            }
        }

        if (fusion.hasPose) {
            val f = fusion.fusedState()
            latestFusedState = f
            frameCallback?.onFusedState(f)
            if (frameCount % 5 == 0) {
                val rot = FloatArray(4)
                latestFrame?.camera?.displayOrientedPose?.getRotationQuaternion(rot, 0) ?: run { rot[3] = 1f }
                wsManager.sendPose(f.timestamp, floatArrayOf(f.x, f.y, f.z), rot)
            }
        }
    }

    private fun renderPathAndVolume(frame: Frame, camPos: FloatArray, isStalled: Boolean) {
        val currentTime = (System.nanoTime() / 1_000_000_000.0).toFloat()
        try {
            val depthImage = frame.acquireDepthImage16Bits()
            updateDepthTexture(depthImage)
            val centerDepth = sampleCenterDepth(depthImage)
            currentCenterDepth = centerDepth
            frameCallback?.onDepthUpdate(centerDepth)
            depthImage.close()
        } catch (e: Exception) {}

        if (tracingState == TracingState.TRACING && (fusion.hasPose || isTracking)) {
            val f = fusion.fusedState()
            livePath.setTemporaryPoint(f.x, f.y, f.z, f.visualQuality)
            if (frameCount % 10 == 0) wsManager.sendPathPoint(f.x, f.y, f.z)
        } else {
            livePath.clearTemporaryPoint()
        }

        pathRenderer.drawPaths(
            vpMatrix, depthUvMatrix, cameraDepthTextureId, viewportWidth, viewportHeight,
            currentTime, livePath, livePathChunks, ghostPathChunks, camPos, wallHeight, currentThermalTemp
        )
        pathRenderer.drawPois(vpMatrix, camPos, poiChunks)

        if (isLineCrawlerEnabled || isDiagnosticOverlayEnabled) {
            val surfelSSBO = if (isStalled) gpuSolver.getMirrorSurfelSSBO() else gpuSolver.getSurfelSSBO(gpuSolver.getActiveBufferIndex())
            val gridSSBO = if (isStalled) gpuSolver.getMirrorGridSSBO() else gpuSolver.getGridSSBO()
            val surfelCount = gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())

            if (isLineCrawlerEnabled) {
                livePath.syncVboExternal()
                val spatialMin = floatArrayOf(kotlin.math.floor(camPos[0] - 512f), kotlin.math.floor(camPos[1] - 512f), kotlin.math.floor(camPos[2] - 512f))
                lineCrawler.draw(
                    vpMatrix, projection, cameraDepthTextureId, depthUvMatrix,
                    surfelSSBO, gridSSBO, surfelCount, spatialMin, origin,
                    livePath.getVboId(), livePath.size, currentTime, currentThermalTemp
                )
            } else {
                surfelRenderer.draw(vpMatrix, projection, cameraDepthTextureId, viewportWidth, viewportHeight, currentTime, isStalled, isHypervisor, surfelSSBO, gridSSBO, surfelCount, origin)
            }
        }
    }


    private fun updateDepthTexture(image: android.media.Image) {
        val plane = image.planes[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, image.width, image.height, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, plane.buffer)
    }

    private fun sampleCenterDepth(image: android.media.Image): Float {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val cx = width / 2
        val cy = height / 2
        var sum = 0f
        var count = 0
        for (y in (cy - 2)..(cy + 2)) {
            for (x in (cx - 2)..(cx + 2)) {
                if (x in 0 until width && y in 0 until height) {
                    val offset = y * rowStride + x * pixelStride
                    val d0 = buffer.get(offset).toInt() and 0xFF
                    val d1 = buffer.get(offset + 1).toInt() and 0xFF
                    val depthMm = d0 or (d1 shl 8)
                    if (depthMm > 0) {
                        sum += depthMm / 1000f
                        count++
                    }
                }
            }
        }
        return if (count > 0) sum / count else 0f
    }

    fun uploadRemoteSurfels(data: ByteBuffer) {
        synchronized(renderLock) { gpuSolver.uploadSurfels(data) }
    }

    fun applyRemoteCorrections(corrections: JSONArray) {
        synchronized(renderLock) {
            gpuSolver.applyCorrections(corrections)
        }
    }

    fun recordPoint(x: Float, y: Float, z: Float, t: Long, s: Float, type: PointType = PointType.NORMAL) {
        synchronized(renderLock) {
            livePath.addPoint(x, y, z, s)
            val key = getSpatialKey(x, y, z)
            livePathChunks.getOrPut(key) { PathBuffer(capacityPoints = 1000) }.addPoint(x, y, z, s)
            if (isRecording) recorder.record(x, y, z, t, s, type)
        }
    }

    fun adaptToAnchor(anchor: Anchor) {
        synchronized(renderLock) {
            val anchorPose = anchor.pose
            val dx = anchorPose.tx() - origin[0]
            val dy = anchorPose.ty() - origin[1]
            val dz = anchorPose.tz() - origin[2]
            if (dx*dx + dy*dy + dz*dz > 0.0001f) {
                nudgeOrigin(dx, dy, dz)
                Log.d("LineRenderer", "Adaptive anchoring applied: $dx, $dy, $dz")
            }
        }
    }

    fun getSurfelCount(): Int {
        return gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())
    }

    fun startTracing(x: Float, y: Float, z: Float, anchor: Anchor? = null) {
        synchronized(renderLock) {
            resetTracing()
            origin[0] = x; origin[1] = y; origin[2] = z
            originSet = true
            startAnchor = anchor
            tracingState = TracingState.TRACING
            isRecording = true
            recorder.start()
            Log.i("LineRenderer", "Tracing started at $x, $y, $z")
        }
    }

    fun finishTracing(x: Float, y: Float, z: Float) {
        synchronized(renderLock) {
            tracingState = TracingState.FINISHED
            isRecording = false
            Log.i("LineRenderer", "Tracing finished at $x, $y, $z")
        }
    }

    fun resetTracing() {
        synchronized(renderLock) { pendingReset = true }
    }

    private fun performResetInternal() {
        livePath.clear()
        ghostPath.clear()
        originSet = false
        startAnchor?.detach()
        startAnchor = null
        fusion.reset()
        poseStabilizer.reset()
        gpuSolver.clearSurfels()
        worldStreamer.clear()
        tracingState = TracingState.IDLE
        isRecording = false
        synchronized(poiChunks) { poiChunks.clear() }
        synchronized(livePathChunks) { livePathChunks.values.forEach { it.resetResources() }; livePathChunks.clear() }
        synchronized(ghostPathChunks) { ghostPathChunks.values.forEach { it.resetResources() }; ghostPathChunks.clear() }
        wsManager.sendReset()
        Log.i("LineRenderer", "Reset Complete (GL Thread)")
    }

    fun addPoi(x: Float, y: Float, z: Float) {
        val poi = Point(x, y, z, System.nanoTime(), 1.0f, PointType.POI)
        val key = getSpatialKey(x, y, z)
        synchronized(poiChunks) { poiChunks.getOrPut(key) { mutableListOf() }.add(poi) }
        recorder.record(x, y, z, poi.tNanos, 1.0f, PointType.POI)
        wsManager.sendPoi(x, y, z)
    }

    fun loadGhost(points: List<Point>) {
        synchronized(renderLock) {
            ghostPath.clear()
            synchronized(poiChunks) { poiChunks.clear() }
            synchronized(ghostPathChunks) {
                ghostPathChunks.values.forEach { it.resetResources() }
                ghostPathChunks.clear()
            }
            for (p in points) {
                val key = getSpatialKey(p.x, p.y, p.z)
                if (p.type == PointType.POI) {
                    synchronized(poiChunks) { poiChunks.getOrPut(key) { mutableListOf() }.add(p) }
                } else {
                    synchronized(ghostPathChunks) { ghostPathChunks.getOrPut(key) { PathBuffer(256, 512) }.addPoint(p.x, p.y, p.z, p.stability) }
                }
            }
            Log.i("LineRenderer", "Ghost path loaded and chunked with ${points.size} points")
        }
    }

    fun performPlaneSync() {
        val frame = latestFrame ?: return
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        val hitResults = frame.hitTest(centerX, centerY)
        val groundHit = hitResults.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        if (groundHit != null) {
            val hitPose = groundHit.hitPose
            val dy = hitPose.ty() - origin[1]
            nudgeOrigin(0f, dy, 0f)
            Log.i("LineRenderer", "Plane Sync: Aligned to Ground Plane at $dy m")
        }
    }

    fun nudgeOrigin(dx: Float, dy: Float, dz: Float) {
        synchronized(renderLock) {
            origin[0] += dx; origin[1] += dy; origin[2] += dz
            livePath.offsetPoints(dx, dy, dz); ghostPath.offsetPoints(dx, dy, dz); recorder.offsetPoints(dx, dy, dz)
            synchronized(poiChunks) {
                val newChunks = mutableMapOf<Long, MutableList<Point>>()
                for (chunk in poiChunks.values) {
                    for (p in chunk) {
                        val np = Point(p.x + dx, p.y + dy, p.z + dz, p.tNanos, p.stability, p.type)
                        val key = getSpatialKey(np.x, np.y, np.z)
                        newChunks.getOrPut(key) { mutableListOf() }.add(np)
                    }
                }
                poiChunks.clear(); poiChunks.putAll(newChunks)
            }
            synchronized(livePathChunks) { livePathChunks.values.forEach { it.offsetPoints(dx, dy, dz) } }
            synchronized(ghostPathChunks) { ghostPathChunks.values.forEach { it.offsetPoints(dx, dy, dz) } }
        }
    }
}
