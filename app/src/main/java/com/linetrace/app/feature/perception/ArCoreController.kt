package com.linetrace.app.feature.perception
import com.linetrace.app.core.Point

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Surface
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Controller for managing the ARCore Session lifecycle and configuration.
 * Implements VO Steady and Lazarus protocols for maximum tracking stability.
 */
class ArCoreController(private val activity: Activity) {

    companion object {
        private const val TAG = "ArCoreController"
    }

    var session: Session? = null
        private set

    private var installRequested = false
    private val controllerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Ensures ARCore is installed and creates a new ARCore session.
     */
    fun ensureInstalledAndCreateSession(): Session? {
        session?.let { return it }

        if (handleArCoreInstallation()) return null

        return try {
            val newSession = Session(activity)
            configureSession(newSession)
            session = newSession
            Log.i(TAG, "ARCore Session successfully created and configured")
            newSession
        } catch (e: Exception) {
            Log.e(TAG, "Exception during session creation", e)
            null
        }
    }

    /**
     * Handles ARCore APK installation logic.
     * Returns true if installation was requested.
     */
    private fun handleArCoreInstallation(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        Log.d(TAG, "ARCore Availability: $availability")

        if (availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        ) {
            val status = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return true
            }
        }
        return false
    }

    private fun configureSession(session: Session) {
        val config = Config(session)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.focusMode = Config.FocusMode.AUTO
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            Log.i(TAG, "Lazarus: Depth Mode ENABLED (Automatic)")
        } else {
            config.depthMode = Config.DepthMode.DISABLED
            Log.w(TAG, "Lazarus: Depth Mode NOT SUPPORTED on this device")
        }
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        
        // Lazarus: Semantic Mode is heavy, disable for core stability check
        config.semanticMode = Config.SemanticMode.DISABLED

        session.configure(config)
    }

    /**
     * Resolves the current display rotation in a version-safe manner.
     */
    private fun getCurrentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
    }

    /**
     * Hosts an anchor in the cloud. This is a suspend function that waits for completion.
     */
    suspend fun hostCloudAnchor(anchor: com.google.ar.core.Anchor): String? = withContext(Dispatchers.Default) {
        val arSession = session ?: return@withContext null

        val cloudAnchor = try {
            arSession.hostCloudAnchor(anchor)
        } catch (e: Exception) {
            Log.e(TAG, "Lazarus: hostCloudAnchor failed", e)
            return@withContext null
        }
        
        var iters = 0
        while (cloudAnchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.TASK_IN_PROGRESS && iters < 60) {
            delay(500)
            iters++
        }
        
        if (cloudAnchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
            cloudAnchor.cloudAnchorId
        } else {
            Log.e(TAG, "Cloud Anchor hosting failed: ${cloudAnchor.cloudAnchorState}")
            null
        }
    }

    /**
     * Resolves a cloud anchor from a given ID.
     */
    suspend fun resolveCloudAnchor(cloudAnchorId: String): com.google.ar.core.Anchor? = withContext(Dispatchers.Default) {
        val arSession = session ?: return@withContext null

        val cloudAnchor = try {
            arSession.resolveCloudAnchor(cloudAnchorId)
        } catch (e: Exception) {
            Log.e(TAG, "Lazarus: resolveCloudAnchor failed", e)
            return@withContext null
        }
        
        var iters = 0
        while (cloudAnchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.TASK_IN_PROGRESS && iters < 60) {
            delay(500)
            iters++
        }
        
        if (cloudAnchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
            cloudAnchor
        } else {
            Log.e(TAG, "Cloud Anchor resolution failed: ${cloudAnchor.cloudAnchorState}")
            null
        }
    }

    /**
     * Places a contact anchor in the world using hit-testing.
     * Uses the current session frame to find a suitable surface.
     */
    fun placeContact(frame: com.google.ar.core.Frame, x: Float, y: Float): com.google.ar.core.Anchor? {
        val arSession = session ?: return null
        
        return try {
            val hitResults = frame.hitTest(x, y)
            
            // Priority 1: Existing Planes (Solid Surfaces)
            // Priority 2: Instant Placement (Rough Estimate)
            // Priority 3: Feature Points (PointCloud)
            val hit = hitResults.firstOrNull { hit ->
                val trackable = hit.trackable
                (trackable is com.google.ar.core.Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                (trackable is com.google.ar.core.Point && trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
            } ?: hitResults.firstOrNull() // Fallback to any hit

            hit?.createAnchor() ?: run {
                // If no hit, we fallback to a "Plane Sync Projection" (1m in front of camera)
                // This ensures the "Seamless AR" promise even in low-data environments.
                val cameraPose = frame.camera.displayOrientedPose
                val translation = floatArrayOf(0f, 0f, -1.0f) // 1 meter forward
                val rotation = floatArrayOf(0f, 0f, 0f, 1f)
                val relativePose = com.google.ar.core.Pose.makeTranslation(translation[0], translation[1], translation[2])
                val worldPose = cameraPose.compose(relativePose)
                arSession.createAnchor(worldPose)
            }
        } catch (e: com.google.ar.core.exceptions.SessionPausedException) {
            Log.w(TAG, "Lazarus: placeContact aborted - Session is paused")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Lazarus: placeContact failed with unexpected error", e)
            null
        }
    }

    fun close() {
        controllerScope.cancel()
        session?.close()
        session = null
    }
}
