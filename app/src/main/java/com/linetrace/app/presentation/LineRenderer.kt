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
import com.linetrace.app.R

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.*
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

    private var program = 0
    private var positionHandle = -1
    private var mvpHandle = -1
    private var colorHandle = -1
    private var depthBiasHandle = -1
    private var cameraDepthHandle = -1
    private var screenSizeHandle = -1
    private var timeHandle = -1

    private var backgroundProgram = 0
    private var backgroundPositionHandle = -1
    private var backgroundTextureHandle = -1
    private var backgroundSamplerHandle = -1
    private var backgroundTextureId = -1
    
    private var depthUvHandle = -1
    private var ribbonDepthUvHandle = -1

    private var _session: Session? = null
    var session: Session?
        get() = synchronized(renderLock) { _session }
        set(value) {
            synchronized(renderLock) {
                _session = value
                if (value == null) {
                    latestFrame = null
                } else {
                    // ARCore 1.53.0 requires explicit texture binding before update()
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

    private val poiList = mutableListOf<Point>()

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    
    // Pre-allocated matrices for zero-allocation render loop
    private val frameCameraMatrix = FloatArray(16)
    private val surfelFusionCameraMatrix = FloatArray(16)

    private val origin = FloatArray(3)
    private val originOffset = FloatArray(3)
    private var originSet = false
    private var startAnchor: Anchor? = null

    private val planeVertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(2000 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var planeExtrudedBuffer: FloatBuffer = ByteBuffer.allocateDirect(2000 * 2 * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

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
    
    // Remote Anchor Synchronization
    private var lastAnchorTime = 0L
    private val anchorThrottleMs = 1000L
    
    private var cameraDepthTextureId = -1
    private var frameCount = 0
    private var lastFrameNanos = 0L

    var isDiagnosticOverlayEnabled = false
    var isHypervisor = false
    private var currentThermalTemp = 0f

    fun updateThermalState(temp: Float) {
        currentThermalTemp = temp
    }

    private var diagnosticProgram = 0
    private var diagnosticPositionHandle = -1
    private var diagnosticMvpHandle = -1
    private var diagnosticCamDepthHandle = -1
    private var diagnosticScreenSizeHandle = -1
    private var diagnosticInvVpHandle = -1
    private var diagnosticZParamsHandle = -1
    private var diagnosticStalledHandle = -1


    var wallHeight = 2.0f
    var wallAlpha = 0.4f
    var planeAlpha = 0.15f
    private var ribbonProgram: Int = -1
    private var ribbonPositionHandle: Int = -1
    private var ribbonStabilityHandle: Int = -1
    private var ribbonMvpHandle: Int = -1
    private var ribbonColorHandle: Int = -1
    private var ribbonDepthBiasHandle: Int = -1
    private var ribbonCamDepthHandle: Int = -1
    private var ribbonScreenSizeHandle: Int = -1
    private var ribbonWallHeightHandle: Int = -1
    private var ribbonTimeHandle: Int = -1
    private var ribbonThermalTempHandle: Int = -1

    private val quadBuffer = ByteBuffer.allocateDirect(4 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f))
        flip()
    }

    private val displayUvBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        flip()
    }
    private val transformedDisplayUvBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    
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

    init {
        initializeWorld()
    }

    private fun initializeWorld() {
        // Handled in MainActivity
    }

    fun onResume() {
        Log.i("LineRenderer", "Renderer resumed")
        worldStreamer.restart()
        // imuNetworkBridge connection is managed in MainActivity
    }

    fun onPause() {
        worldStreamer.shutdown()
        // imuNetworkBridge closure is managed in MainActivity
        worldSync.close()
        Log.i("LineRenderer", "Renderer paused")
    }

    fun onDestroy() {
        // 1. Close high-level subsystems
        worldSync.close()
        worldStreamer.shutdown()
        gpuSolver.onDestroy()
        
        // 2. Explicitly delete GL programs and textures
        synchronized(renderLock) {
            if (program != 0) {
                GLES30.glDeleteProgram(program)
                program = 0
            }
            if (backgroundProgram != 0) {
                GLES30.glDeleteProgram(backgroundProgram)
                backgroundProgram = 0
            }
            if (ribbonProgram != 0) {
                GLES30.glDeleteProgram(ribbonProgram)
                ribbonProgram = 0
            }
            if (diagnosticProgram != 0) {
                GLES30.glDeleteProgram(diagnosticProgram)
                diagnosticProgram = 0
            }
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
        // If tracking but no frame for > 500ms, trigger stall recovery
        if (isTracking && (now - lastFrameNanos) > 500_000_000L && lastFrameNanos != 0L) {
            Log.w("LineRenderer", "Lazarus: Renderer stall detected!")
            frameCallback?.onRendererStalled()
            lastFrameNanos = now // Prevent double-triggering immediately
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i("LineRenderer", "onSurfaceCreated")
        
        gpuSolver.init()

        GLES30.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.0f, 1.0f)

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES30.glGetUniformLocation(program, "uMvpMatrix")
        colorHandle = GLES30.glGetUniformLocation(program, "uColor")
        depthBiasHandle = GLES30.glGetUniformLocation(program, "uDepthBias")
        screenSizeHandle = GLES30.glGetUniformLocation(program, "uScreenSize")
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        cameraDepthHandle = GLES30.glGetUniformLocation(program, "uCameraDepth")
        depthUvHandle = GLES30.glGetUniformLocation(program, "uDepthUvMatrix")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(cameraDepthHandle, 1)

        diagnosticProgram = buildProgram(DIAGNOSTIC_VERTEX_SHADER, DIAGNOSTIC_FRAGMENT_SHADER)
        diagnosticPositionHandle = GLES30.glGetAttribLocation(diagnosticProgram, "aPosition")
        diagnosticMvpHandle = GLES30.glGetUniformLocation(diagnosticProgram, "uMvpMatrix")
        diagnosticCamDepthHandle = GLES30.glGetUniformLocation(diagnosticProgram, "uCameraDepth")
        diagnosticScreenSizeHandle = GLES30.glGetUniformLocation(diagnosticProgram, "uScreenSize")
        diagnosticInvVpHandle = GLES30.glGetUniformLocation(diagnosticProgram, "uInvVpMatrix")
        diagnosticZParamsHandle = GLES30.glGetUniformLocation(diagnosticProgram, "uZParams")
        diagnosticStalledHandle = GLES30.glGetUniformLocation(diagnosticProgram, "uStalled")

        backgroundProgram = buildProgram(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER)
        backgroundPositionHandle = GLES30.glGetAttribLocation(backgroundProgram, "aPosition")
        backgroundTextureHandle = GLES30.glGetAttribLocation(backgroundProgram, "aTexCoord")
        backgroundSamplerHandle = GLES30.glGetUniformLocation(backgroundProgram, "sTexture")

        ribbonProgram = buildProgram(RIBBON_VERTEX_SHADER, RIBBON_FRAGMENT_SHADER)
        ribbonPositionHandle = GLES30.glGetAttribLocation(ribbonProgram, "aPosition")
        ribbonStabilityHandle = GLES30.glGetAttribLocation(ribbonProgram, "aStability")
        ribbonMvpHandle = GLES30.glGetUniformLocation(ribbonProgram, "uMvpMatrix")
        ribbonColorHandle = GLES30.glGetUniformLocation(ribbonProgram, "uColor")
        ribbonDepthBiasHandle = GLES30.glGetUniformLocation(ribbonProgram, "uDepthBias")
        ribbonCamDepthHandle = GLES30.glGetUniformLocation(ribbonProgram, "uCameraDepth")
        ribbonScreenSizeHandle = GLES30.glGetUniformLocation(ribbonProgram, "uScreenSize")
        ribbonWallHeightHandle = GLES30.glGetUniformLocation(ribbonProgram, "uWallHeight")
        ribbonTimeHandle = GLES30.glGetUniformLocation(ribbonProgram, "uTime")
        ribbonThermalTempHandle = GLES30.glGetUniformLocation(ribbonProgram, "uThermalTemp")
        ribbonDepthUvHandle = GLES30.glGetUniformLocation(ribbonProgram, "uDepthUvMatrix")
        GLES30.glUseProgram(ribbonProgram)
        GLES30.glUniform1i(ribbonCamDepthHandle, 1)

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        
        // Background Camera Texture (External OES)
        backgroundTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Depth Texture
        cameraDepthTextureId = textures[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

        // Ensure session is updated with the new texture ID if it exists
        _session?.let {
            it.setCameraTextureNames(intArrayOf(backgroundTextureId))
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            Log.w("LineRenderer", "onSurfaceChanged: Invalid dimensions ($width x $height)")
            return
        }
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        _session?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val isStalled = (now - lastFrameNanos) > 100_000_000L // 100ms
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
        }

        if (frame != null) {
            val camera = frame.camera
            isTracking = camera.trackingState == TrackingState.TRACKING
            val pointCloud = try { frame.acquirePointCloud() } catch (e: Exception) { null }

            // 🧬 SURFEL FUSION & BROADCAST
            pointCloud?.let { pc ->
                if (isTracking && frameCount % 10 == 0) {
                    val cameraPose = camera.displayOrientedPose
                    cameraPose.toMatrix(surfelFusionCameraMatrix, 0)
                    
                    val prevCount = gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())
                    gpuSolver.fuseSurfels(pc.points, surfelFusionCameraMatrix, frame.timestamp)
                    val newCount = gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())
                    
                    Log.v("FusionDebug", "pc points: ${pc.points.remaining() / 4} | count: $prevCount -> $newCount")

                    // Broadcast the new surfels added this frame BEFORE building spatial index
                    // because buildSpatialIndex reorders the buffer via radix sort.
                    val deltaCount = newCount - prevCount
                    if (deltaCount > 0) {
                        val deltaData = gpuSolver.downloadSurfelDelta(prevCount, deltaCount)
                        if (deltaData != null) {
                            deltaData.rewind() // Ensure buffer is ready for reading
                            val senderId = java.util.UUID.nameUUIDFromBytes(wsManager.user.toByteArray())
                            
                            // 🚀 Throttle surfel sync to avoid saturating Render's WebSocket connection
                            if (frameCount % 3 == 0) {
                                worldSync.broadcastDelta(WorldDelta(senderId, frame.timestamp, deltaData))
                            }
                            
                            // 📊 Fusion Rate Monitoring
                            if (frameCount % 60 == 0) {
                                Log.i("FusionMonitor", "Active Fusion: +$deltaCount surfels | Total: $newCount | Sync: OK")
                            }
                        }
                    }

                    // Spatial indexing for raymarching (performs radix sort, reordering buffer)
                    gpuSolver.buildSpatialIndex(gpuSolver.getActiveBufferIndex(), floatArrayOf(origin[0]-50f, origin[1]-50f, origin[2]-50f), 1.0f)
                }
                pc.release()
            }

            synchronized(renderLock) {
                val cameraPose = camera.displayOrientedPose
                cameraPose.toMatrix(frameCameraMatrix, 0)
                
                // Mirror Shield Stall Detection & Handling
                val stabilizedPose = if (isStalled && poseStabilizer.initialized) {
                    // In stalled mode, use the last good pose to prevent view jumps
                    poseStabilizer.lastPose
                } else {
                    poseStabilizer.update(frameCameraMatrix)
                }

                // 1. Update World Origin if not set
                if (!originSet && isTracking) {
                    origin[0] = stabilizedPose[12]
                    origin[1] = stabilizedPose[13]
                    origin[2] = stabilizedPose[14]
                    originSet = true
                    
                    // Create start anchor for adaptive correction
                    val anchor = try {
                        arSession.createAnchor(camera.displayOrientedPose)
                    } catch (e: Exception) {
                        null
                    }
                    startAnchor = anchor
                    
                    recorder.start()
                    Log.i("LineRenderer", "Origin set: ${origin.joinToString()} with anchor: ${anchor != null}")
                    
                    // Sync origin to visualizer
                    wsManager.sendAnchor(origin[0], origin[1], origin[2])
                }

                // 2. Coordinate System Sync (Local to World)
                if (originSet) {
                    val camPos = floatArrayOf(stabilizedPose[12], stabilizedPose[13], stabilizedPose[14])
                    
                    // Adaptive Anchor Check
                    if (frameCount++ % 60 == 0) {
                        val dist = sqrt((camPos[0]-origin[0])*(camPos[0]-origin[0]) + (camPos[2]-origin[2])*(camPos[2]-origin[2]))
                        if (dist > 5.0f) {
                            performPlaneSync()
                        }

                        gpuSolver.processWorld(camPos, worldStreamer, object : GpuPoseSolver.WorldCallback {
                            override fun onChunkCompressed(chunk: WorldChunk) {
                                worldStreamer.saveChunkSync(chunk)
                                worldSync.broadcastChunk(chunk)
                            }
                        })

                        // 2. Page IN: Load nearby chunks from disk
                        val nearbyChunks = worldStreamer.getChunksInRegion(camPos[0], camPos[1], camPos[2], 8.0f)
                        for (chunk in nearbyChunks) {
                            chunk.surfelData?.let { data ->
                                gpuSolver.uploadSurfels(data)
                            }
                        }
                    }

                    // 3. Evict old chunks from RAM
                    if (!worldStreamer.isShutdown()) {
                        worldStreamer.evictFarChunksAsync(camPos[0], camPos[1], camPos[2], 20.0f)
                    }

                    // Update Mirror Shield (Hot Mirror)
                    gpuSolver.updateMirrorShield()
                }

                // Pass thermal state to shaders if needed via a uniform or simply log if high
                if (frameCount % 300 == 0) { // ~5 seconds at 60fps
                    wsManager.sendThermal(currentThermalTemp)
                }

                // 4. Render Background
                drawBackground(frame)

                Matrix.invertM(view, 0, stabilizedPose, 0)
                camera.getProjectionMatrix(projection, 0, 0.1f, 100.0f)
                Matrix.multiplyMM(vpMatrix, 0, projection, 0, view, 0)

                // Calculate Depth UV Matrix for proper occlusion sampling
                viewCoordsBuffer.rewind()
                texCoordsBuffer.rewind()
                frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, viewCoordsBuffer, Coordinates2d.TEXTURE_NORMALIZED, texCoordsBuffer)
                
                // Basic affine reconstruction for 3x3 matrix: u = m0*x + m1*y + m2; v = m3*x + m4*y + m5
                val m0 = texCoordsBuffer.get(2) - texCoordsBuffer.get(0)
                val m1 = texCoordsBuffer.get(4) - texCoordsBuffer.get(0)
                val m2 = texCoordsBuffer.get(0)
                val m3 = texCoordsBuffer.get(3) - texCoordsBuffer.get(1)
                val m4 = texCoordsBuffer.get(5) - texCoordsBuffer.get(1)
                val m5 = texCoordsBuffer.get(1)
                depthUvMatrix[0] = m0; depthUvMatrix[3] = m1; depthUvMatrix[6] = m2
                depthUvMatrix[1] = m3; depthUvMatrix[4] = m4; depthUvMatrix[7] = m5
                depthUvMatrix[2] = 0f; depthUvMatrix[5] = 0f; depthUvMatrix[8] = 1f

                // Adaptive Anchoring: Periodically check if the start anchor has been refined
                if (tracingState == TracingState.TRACING) {
                    startAnchor?.let { anchor ->
                        if (anchor.trackingState == TrackingState.TRACKING) {
                            adaptToAnchor(anchor)
                        }
                    }
                }

                if (isTracking) {
                    fusion.isArTracking = true
                    val translation = floatArrayOf(stabilizedPose[12], stabilizedPose[13], stabilizedPose[14])
                    val cameraPose_ = Pose.makeTranslation(translation[0], translation[1], translation[2])
                    fusion.updateFromAR(cameraPose_, frame.timestamp)
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
                    frameCallback?.onFusedState(f)
                    
                    // Throttle Pose updates to server (Matches Three.js frequency)
                    if (frameCount % 5 == 0) {
                        val cameraPose_ = latestFrame?.camera?.displayOrientedPose
                        val rot = FloatArray(4)
                        if (cameraPose_ != null) {
                            cameraPose_.getRotationQuaternion(rot, 0)
                        } else {
                            rot[0] = 0f; rot[1] = 0f; rot[2] = 0f; rot[3] = 1f
                        }
                        wsManager.sendPose(f.timestamp, floatArrayOf(f.x, f.y, f.z), rot)
                    }
                }

                drawEnvironment(arSession)
                
                if (isTracking || tracingState != TracingState.IDLE) {
                    val currentTime = (System.nanoTime() / 1_000_000_000.0).toFloat()
                    
                    // Update Depth Texture
                    try {
                        val depthImage = frame.acquireDepthImage16Bits()
                        updateDepthTexture(depthImage)
                        depthImage.close()
                    } catch (e: Exception) {}

                    // 1. Path Render Pass (Lines & POIs)
                    GLES30.glUseProgram(program)
                    GLES30.glUniformMatrix4fv(mvpHandle, 1, false, vpMatrix, 0)
                    GLES30.glUniformMatrix3fv(depthUvHandle, 1, false, depthUvMatrix, 0)
                    GLES30.glUniform2f(screenSizeHandle, viewportWidth.toFloat(), viewportHeight.toFloat())
                    GLES30.glUniform1f(timeHandle, currentTime)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)

                    // Ghost Path
                    GLES30.glLineWidth(2.0f)
                    GLES30.glUniform4f(colorHandle, 0.0f, 1.0f, 0.5f, 0.6f)
                    GLES30.glUniform1f(depthBiasHandle, -0.001f)
                    ghostPath.draw(positionHandle)
                    
                    // Live Path
                    if (tracingState == TracingState.TRACING && (fusion.hasPose || isTracking)) {
                        val f = fusion.fusedState()
                        livePath.setTemporaryPoint(f.x, f.y, f.z, f.visualQuality)
                        
                        // Periodic path sync to visualizer (Throttled more to save memory)
                        if (frameCount % 10 == 0) {
                            wsManager.sendPathPoint(f.x, f.y, f.z)
                        }
                    } else {
                        livePath.clearTemporaryPoint()
                    }

                    GLES30.glLineWidth(3.0f) 
                    GLES30.glUniform4f(colorHandle, 0.4f, 0.8f, 1.0f, 1.0f)
                    GLES30.glUniform1f(depthBiasHandle, -0.002f)
                    livePath.draw(positionHandle)

                    drawPois()

                    // 2. Wall Render Pass (Ribbons)
                    GLES30.glUseProgram(ribbonProgram)
                    GLES30.glUniformMatrix4fv(ribbonMvpHandle, 1, false, vpMatrix, 0)
                    GLES30.glUniformMatrix3fv(ribbonDepthUvHandle, 1, false, depthUvMatrix, 0)
                    GLES30.glUniform2f(ribbonScreenSizeHandle, viewportWidth.toFloat(), viewportHeight.toFloat())
                    GLES30.glUniform1f(ribbonWallHeightHandle, wallHeight)
                    GLES30.glUniform1f(ribbonDepthBiasHandle, -0.001f)
                    GLES30.glUniform1f(ribbonTimeHandle, currentTime)
                    GLES30.glUniform1f(ribbonThermalTempHandle, currentThermalTemp)
                    
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
                    GLES30.glUniform1i(ribbonCamDepthHandle, 1)

                    GLES30.glUniform4f(ribbonColorHandle, 0.0f, 1.0f, 0.5f, 0.3f)
                    ghostPath.drawRibbon(ribbonPositionHandle, ribbonStabilityHandle)

                    GLES30.glUniform4f(ribbonColorHandle, 0.4f, 0.8f, 1.0f, 0.6f)
                    livePath.drawRibbon(ribbonPositionHandle, ribbonStabilityHandle)

                    // 🌐 DIAGNOSTIC / VOLUMETRIC RENDER PASS
                    if (isDiagnosticOverlayEnabled) {
                        GLES30.glUseProgram(diagnosticProgram)
                        GLES30.glUniformMatrix4fv(diagnosticMvpHandle, 1, false, vpMatrix, 0)
                        GLES30.glUniform2f(diagnosticScreenSizeHandle, viewportWidth.toFloat(), viewportHeight.toFloat())
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(diagnosticProgram, "uTime"), currentTime)
                        GLES30.glUniform1i(diagnosticStalledHandle, if (isStalled) 1 else 0)
                        
                        val invVp = FloatArray(16)
                        if (Matrix.invertM(invVp, 0, vpMatrix, 0)) {
                            GLES30.glUniformMatrix4fv(diagnosticInvVpHandle, 1, false, invVp, 0)
                        }
                        GLES30.glUniform2f(diagnosticZParamsHandle, projection[10], projection[14])
                        GLES30.glUniform3f(GLES30.glGetUniformLocation(diagnosticProgram, "uWorldMin"), origin[0] - 50f, origin[1] - 50f, origin[2] - 50f)
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(diagnosticProgram, "uCellSize"), 1.0f)

                        // Bind Surfel SSBO from GpuPoseSolver
                        val surfelSSBO = if (isStalled) gpuSolver.getMirrorSurfelSSBO() else gpuSolver.getSurfelSSBO(gpuSolver.getActiveBufferIndex())
                        val gridSSBO = if (isStalled) gpuSolver.getMirrorGridSSBO() else gpuSolver.getGridSSBO()
                        val surfelCount = gpuSolver.getSurfelCount(gpuSolver.getActiveBufferIndex())
                        
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(diagnosticProgram, "uSurfelCount"), surfelCount)
                        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, surfelSSBO)
                        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridSSBO)

                        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
                        GLES30.glUniform1i(diagnosticCamDepthHandle, 1)

                        GLES30.glEnable(GLES30.GL_BLEND)
                        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
                        
                        GLES30.glVertexAttribPointer(diagnosticPositionHandle, 3, GLES30.GL_FLOAT, false, 0, quadBuffer)
                        GLES30.glEnableVertexAttribArray(diagnosticPositionHandle)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                        GLES30.glDisable(GLES30.GL_BLEND)
                    }
                    GLES30.glUseProgram(program)
                }
                GLES30.glLineWidth(1.0f)
            }
        }
    }

    private fun updateDepthTexture(image: android.media.Image) {
        val plane = image.planes[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTextureId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, image.width, image.height, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, plane.buffer)
    }

    private fun drawPois() {
        val currentPois = synchronized(poiList) { if (poiList.isEmpty()) return; poiList.toList() }
        GLES30.glUseProgram(program)
        GLES30.glUniform4f(colorHandle, 1.0f, 0.2f, 0.2f, 1.0f)
        val poiBuffer = ByteBuffer.allocateDirect(currentPois.size * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (poi in currentPois) {
            poiBuffer.put(poi.x); poiBuffer.put(poi.y); poiBuffer.put(poi.z)
        }
        poiBuffer.flip()
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, poiBuffer)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, currentPois.size)
    }

    private fun drawPlanes(session: Session) {
        val planes = session.getAllTrackables(Plane::class.java)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(depthBiasHandle, 0.001f)
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val isVertical = plane.type == Plane.Type.VERTICAL
            val polygon = plane.polygon
            val numVertices = polygon.remaining() / 2
            if (numVertices == 0) continue
            
            planeVertexBuffer.clear()
            for (i in 0 until numVertices) {
                planeVertexBuffer.put(polygon.get())
                planeVertexBuffer.put(0f)
                planeVertexBuffer.put(polygon.get())
            }
            planeVertexBuffer.flip()
            
            val centerPose = plane.centerPose
            centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, planeVertexBuffer)
            GLES30.glEnableVertexAttribArray(positionHandle)
            
            if (isVertical) {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthMask(true)
                GLES30.glUniform4f(colorHandle, 0.8f, 0.0f, 0.8f, wallAlpha)
                
                val verticesNeeded = numVertices * 2
                if (planeExtrudedBuffer.capacity() < verticesNeeded * 3) {
                    planeExtrudedBuffer = ByteBuffer.allocateDirect(verticesNeeded * 3 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                }
                planeExtrudedBuffer.clear()
                planeVertexBuffer.rewind()
                for (i in 0 until numVertices) {
                    val vx = planeVertexBuffer.get()
                    val vy = planeVertexBuffer.get() 
                    val vz = planeVertexBuffer.get()
                    planeExtrudedBuffer.put(vx).put(0f).put(vz)
                    planeExtrudedBuffer.put(vx).put(wallHeight).put(vz)
                }
                planeExtrudedBuffer.flip()
                GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, planeExtrudedBuffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, verticesNeeded)

                // Sync the vertical plane as an anchor to the visualizer (Throttled)
                val now = System.currentTimeMillis()
                if (now - lastAnchorTime > anchorThrottleMs) {
                    wsManager.sendVerticalPlane(centerPose.tx(), centerPose.ty(), centerPose.tz(), wallHeight, wallAlpha)
                    lastAnchorTime = now
                }
            } else {
                GLES30.glUniform4f(colorHandle, 0.0f, 0.8f, 0.8f, planeAlpha)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, numVertices)
            }
        }
    }

    private fun drawEnvironment(session: Session) {
        drawPlanes(session)
    }

    private fun drawBackground(frame: Frame) {
        if (backgroundTextureId == -1) return
        
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(backgroundProgram)
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES30.glUniform1i(backgroundSamplerHandle, 0)

        transformedDisplayUvBuffer.rewind()
        frame.transformCoordinates2d(
            Coordinates2d.VIEW_NORMALIZED,
            displayUvBuffer,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedDisplayUvBuffer
        )
        GLES30.glVertexAttribPointer(backgroundPositionHandle, 3, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glEnableVertexAttribArray(backgroundPositionHandle)
        GLES30.glVertexAttribPointer(backgroundTextureHandle, 2, GLES30.GL_FLOAT, false, 0, transformedDisplayUvBuffer)
        GLES30.glEnableVertexAttribArray(backgroundTextureHandle)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun buildProgram(vss: String, fss: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vss)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fss)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("LineRenderer", "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    fun uploadRemoteSurfels(data: ByteBuffer) {
        synchronized(renderLock) {
            gpuSolver.uploadSurfels(data)
        }
    }

    fun recordPoint(x: Float, y: Float, z: Float, t: Long, s: Float, type: PointType = PointType.NORMAL) {
        synchronized(renderLock) {
            livePath.addPoint(x, y, z, s)
            if (isRecording) {
                recorder.record(x, y, z, t, s, type)
            }
        }
    }

    fun adaptToAnchor(anchor: Anchor) {
        synchronized(renderLock) {
            val anchorPose = anchor.pose
            val dx = anchorPose.tx() - origin[0]
            val dy = anchorPose.ty() - origin[1]
            val dz = anchorPose.tz() - origin[2]
            
            // Minimal threshold to avoid jitter
            if (dx*dx + dy*dy + dz*dz > 0.0001f) {
                origin[0] = anchorPose.tx()
                origin[1] = anchorPose.ty()
                origin[2] = anchorPose.tz()
                
                livePath.offsetPoints(dx, dy, dz)
                ghostPath.offsetPoints(dx, dy, dz)
                recorder.offsetPoints(dx, dy, dz)

                synchronized(poiList) {
                    for (i in poiList.indices) {
                        val p = poiList[i]
                        poiList[i] = Point(p.x + dx, p.y + dy, p.z + dz, p.tNanos, p.stability, p.type)
                    }
                }
                Log.d("LineRenderer", "Adaptive anchoring applied: $dx, $dy, $dz")
            }
        }
    }

    fun startTracing(x: Float, y: Float, z: Float, anchor: Anchor? = null) {
        synchronized(renderLock) {
            resetTracing()
            origin[0] = x
            origin[1] = y
            origin[2] = z
            originSet = true
            startAnchor = anchor
            tracingState = TracingState.TRACING
            isRecording = true
            recorder.start()
            Log.i("LineRenderer", "Tracing started at $x, $y, $z with anchor: ${anchor != null}")
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
        synchronized(renderLock) {
            pendingReset = true
        }
    }

    private fun performResetInternal() {
        // Must be called from GL thread
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
        poiList.clear()
        originOffset[0] = 0f
        originOffset[1] = 0f
        originOffset[2] = 0f
        
        // Notify server to clear visualizer state
        wsManager.sendReset()
        Log.i("LineRenderer", "Reset Complete (GL Thread)")
    }

    fun addPoi(x: Float, y: Float, z: Float) {
        synchronized(poiList) {
            poiList.add(Point(x, y, z, System.nanoTime(), 1.0f, PointType.POI))
        }
        recorder.record(x, y, z, System.nanoTime(), 1.0f, PointType.POI)
        
        // Sync to server
        wsManager.sendPoi(x, y, z)
    }

    fun loadGhost(points: List<Point>) {
        synchronized(renderLock) {
            ghostPath.clear()
            for (p in points) {
                ghostPath.addPoint(p.x, p.y, p.z, p.stability)
            }
            Log.i("LineRenderer", "Ghost path loaded with ${points.size} points")
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
            synchronized(poiList) {
                for (i in poiList.indices) {
                    val p = poiList[i]
                    poiList[i] = Point(p.x + dx, p.y + dy, p.z + dz, p.tNanos, p.stability, p.type)
                }
            }
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix; 
            uniform float uDepthBias; 
            uniform float uTime;
            attribute vec4 aPosition; 
            varying float vDepth;
            varying vec2 vUv;
            varying float vQuality;
            varying float vTime;
            void main() { 
                float quality = aPosition.w;
                vec3 posOffset = aPosition.xyz;
                if (quality < -0.5) {
                    float jitter = sin(aPosition.x * 100.0 + uTime * 30.0) * 0.05;
                    posOffset += vec3(jitter, jitter, jitter);
                }
                vec4 pos = uMvpMatrix * vec4(posOffset, 1.0); 
                pos.z += uDepthBias * pos.w; 
                gl_Position = pos; 
                vDepth = gl_Position.z / gl_Position.w;
                vUv = (gl_Position.xy / gl_Position.w) * 0.5 + 0.5;
                vQuality = quality;
                vTime = uTime;
                gl_PointSize = 10.0; 
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float; 
            uniform vec4 uColor; 
            uniform sampler2D uCameraDepth;
            uniform vec2 uScreenSize;
            uniform float uTime;
            varying float vDepth;
            varying vec2 vUv;
            varying float vQuality;
            varying float vTime;
            float getDepth(vec2 uv) {
                vec2 packedDepth = texture2D(uCameraDepth, uv).rg;
                return (packedDepth.r * 255.0 + packedDepth.g * 255.0 * 256.0) / 1000.0;
            }
            uniform mat3 uDepthUvMatrix;
            void main() { 
                vec2 depthUv = (uDepthUvMatrix * vec3(vUv, 1.0)).xy;
                float realDepth = getDepth(depthUv);
                vec3 finalRgb;
                float finalAlpha = uColor.a;
                if (vQuality < -0.5) {
                    float flicker = step(0.5, fract(vDepth * 10.0 + vTime * 20.0)); 
                    float pulse = 0.8 + 0.2 * sin(vTime * 30.0);
                    finalRgb = vec3(1.0, 0.0, 0.0) * pulse;
                    finalAlpha = 1.0 * flicker;
                } else {
                    vec3 lowQualityColor = vec3(1.0, 0.5, 0.0);
                    vec3 highQualityColor = uColor.rgb;        
                    finalRgb = mix(lowQualityColor, highQualityColor, clamp(vQuality, 0.0, 1.0));
                }
                if (vDepth > realDepth + 0.05) {
                    if (vQuality < -0.5) {
                        gl_FragColor = vec4(1.0, 0.0, 0.0, finalAlpha * 0.3);
                    } else {
                        gl_FragColor = vec4(finalRgb * 0.2, finalAlpha * 0.2); 
                    }
                } else {
                    gl_FragColor = vec4(finalRgb, finalAlpha);
                }
            }
        """
        private const val BACKGROUND_VERTEX_SHADER = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }"
        private const val BACKGROUND_FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }"
        
        private const val RIBBON_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform float uDepthBias;
            uniform float uWallHeight;
            attribute vec4 aPosition;
            attribute float aStability;
            varying float vDepth;
            varying vec2 vUv;
            varying float vHeightFactor;
            varying float vStability;
            uniform float uTime;
            void main() {
                vec3 pos = aPosition.xyz;
                float stability = aStability;
                if (stability < -0.5) {
                    pos += sin(pos.x * 50.0 + uTime * 30.0) * 0.05;
                }
                pos.y += aPosition.w * uWallHeight;
                vec4 clipPos = uMvpMatrix * vec4(pos, 1.0);
                clipPos.z += uDepthBias * clipPos.w;
                gl_Position = clipPos;
                vDepth = gl_Position.z / gl_Position.w;
                vUv = gl_Position.xy * 0.5 + 0.5;
                vHeightFactor = aPosition.w; 
                vStability = stability;
                if (uTime < 0.0) vStability = -1.0; // Dummy check
            }
        """

        private const val RIBBON_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform sampler2D uCameraDepth;
            uniform vec2 uScreenSize;
            varying float vDepth;
            varying vec2 vUv;
            varying float vHeightFactor;
            varying float vStability;
            uniform float uTime;
            uniform float uThermalTemp;
            float getDepth(vec2 uv) {
                vec2 packedDepth = texture2D(uCameraDepth, uv).rg;
                return (packedDepth.r * 255.0 + packedDepth.g * 255.0 * 256.0) / 1000.0;
            }
            uniform mat3 uDepthUvMatrix;
            void main() {
                vec2 depthUv = (uDepthUvMatrix * vec3(vUv, 1.0)).xy;
                float realDepth = getDepth(depthUv);
                vec3 color = uColor.rgb;
                float alpha = uColor.a;
                
                // Thermal Warning Effect: Red pulse if > 42C
                if (uThermalTemp > 42.0) {
                    float pulse = sin(uTime * 10.0) * 0.5 + 0.5;
                    color = mix(color, vec3(1.0, 0.2, 0.0), pulse * 0.3);
                }

                if (vStability < -0.5) {
                    float flicker = step(0.5, fract(vDepth * 10.0 + uTime * 20.0));
                    color = vec3(1.0, 0.0, 0.0);
                    alpha *= flicker * 2.0;
                } else {
                    alpha *= (1.0 - vHeightFactor * 0.7);
                    float scanline = sin(vHeightFactor * 30.0 - vDepth * 5.0) * 0.1;
                    color += scanline;
                }
                if (vDepth > realDepth + 0.05) {
                    gl_FragColor = vec4(color * 0.2, alpha * 0.3);
                } else {
                    gl_FragColor = vec4(color, alpha);
                }
            }
        """

        private const val DIAGNOSTIC_VERTEX_SHADER = """#version 310 es
            in vec4 aPosition;
            out vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = aPosition.xy * 0.5 + 0.5;
            }
        """
        private const val DIAGNOSTIC_FRAGMENT_SHADER = """#version 310 es
            precision highp float;
            uniform sampler2D uCameraDepth;
            uniform vec2 uScreenSize;
            uniform mat4 uInvVpMatrix;
            uniform vec2 uZParams;
            uniform float uTime;
            uniform int uSurfelCount;
            uniform vec3 uWorldMin;
            uniform float uCellSize;
            uniform int uStalled;
            in vec2 vUv;
            out vec4 outColor;

            struct Surfel {
                vec4 posRadius;   // 0-15: xyz, radius
                vec4 normalConf;  // 16-31: xyz, confidence
                vec4 color;       // 32-47: rgb, unused
                uvec2 id;         // 48-55: 64-bit ID (low, high)
                uvec2 timestamp;  // 56-63: 64-bit Timestamp (low, high)
            };

            layout(std430, binding = 0) readonly buffer SurfelBuffer {
                Surfel surfels[];
            };

            layout(std430, binding = 1) readonly buffer GridBuffer {
                uint gridOffsets[]; // Start index of each cell
            };

            float getDepth(vec2 uv) {
                vec2 packedDepth = texture(uCameraDepth, uv).rg;
                return (packedDepth.r * 255.0 + packedDepth.g * 255.0 * 256.0) / 1000.0;
            }

            vec3 worldFromDepth(vec2 uv, float d) {
                float clipZ = (uZParams.x * (-d) + uZParams.y) / d;
                vec4 clipPos = vec4(uv * 2.0 - 1.0, clipZ, 1.0);
                vec4 worldPos = uInvVpMatrix * clipPos;
                return worldPos.xyz / worldPos.w;
            }

            uint expandBits(uint v) {
                v = (v * 0x00010001u) & 0xFF0000FFu;
                v = (v * 0x00000101u) & 0x0F00F00Fu;
                v = (v * 0x00000011u) & 0xC30C30C3u;
                v = (v * 0x00000005u) & 0x49249249u;
                return v;
            }

            uint morton3(vec3 p) {
                uvec3 ip = uvec3(clamp(p, 0.0, 1023.0));
                return expandBits(ip.x) | (expandBits(ip.y) << 1) | (expandBits(ip.z) << 2);
            }

            float surfelSDF(vec3 p, Surfel s) {
                vec3 diff = p - s.posRadius.xyz;
                vec3 normal = s.normalConf.xyz;
                float distPlane = dot(diff, normal);
                vec3 proj = diff - distPlane * normal;
                float distInPlane = length(proj);
                float radius = s.posRadius.w;
                return max(abs(distPlane) - 0.005, distInPlane - radius);
            }

            void main() {
                vec2 uv = vUv;
                float d = getDepth(uv);
                if (d <= 0.0) discard;

                vec3 worldPos = worldFromDepth(uv, d);
                vec3 camPos = (uInvVpMatrix * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
                vec3 rayDir = normalize(worldPos - camPos);
                
                float t = 0.1; 
                float accum = 0.0;
                float maxT = length(worldPos - camPos);
                float stepSize = maxT / 32.0;
                
                for(int i = 0; i < 32; i++) {
                    if (t >= maxT || accum >= 1.0) break;
                    vec3 p = camPos + rayDir * t;
                    
                    // Spatial Lookup
                    vec3 gridPos = floor((p - uWorldMin) / uCellSize);
                    uint key = morton3(gridPos);
                    uint startIdx = gridOffsets[key];
                    uint endIdx = (key < 1048575u) ? gridOffsets[key + 1u] : uint(uSurfelCount);
                    
                    float minSdf = 1.0; // Pruning radius
                    for(uint j = startIdx; j < endIdx; j++) {
                        if (j >= uint(uSurfelCount)) break;
                        float sdf = surfelSDF(p, surfels[j]);
                        minSdf = min(minSdf, sdf);
                    }
                    
                    float field = exp(-max(0.0, minSdf) * 60.0);
                    field *= 0.8 + 0.2 * sin(t * 10.0 - uTime * 5.0);
                    accum += field * 0.25;
                    
                    if (minSdf < 0.01) t += 0.005; // Tighten step near surface
                    else t += stepSize;
                }

                accum = clamp(accum, 0.0, 1.0);
                vec3 finalRgb = vec3(0.0, 0.6, 1.0);
                if (uStalled == 1) {
                    finalRgb = vec3(1.0, 0.3, 0.0); // Orange warning for Mirror Shield mode
                    accum *= (0.8 + 0.2 * sin(uTime * 15.0)); // Stronger pulse
                }
                outColor = vec4(finalRgb * accum, accum * 0.7);
            }
        """
    }
}
