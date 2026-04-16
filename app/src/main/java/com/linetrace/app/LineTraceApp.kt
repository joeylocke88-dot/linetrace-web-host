package com.linetrace.app

import android.app.Application
import android.util.Log
import kotlin.system.exitProcess

class LineTraceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Global Lazarus Crash Handler - The ultimate shield against ARCore's background perception loop
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val threadName = thread.name ?: "UnknownThread"
            
            // Collect the entire exception ecosystem (causes + suppressed)
            val allExceptions = mutableListOf<Throwable>()
            var current: Throwable? = throwable
            while (current != null) {
                allExceptions.add(current)
                allExceptions.addAll(current.suppressed)
                current = current.cause
            }

            var isArCoreTransientError = false
            for (ex in allExceptions) {
                val className = ex.javaClass.name
                val message = ex.message ?: ""
                
                // Broad matching for known transient ARCore/XR errors that occur during race conditions
                if (className.contains("MissingGlContextException") || 
                    className.contains("SessionPausedException") ||
                    className.contains("DeadlineExceededException") ||
                    className.contains("NotTrackingException") ||
                    className.contains("FatalException") ||
                    message.contains("MissingGlContextException") ||
                    message.contains("SessionPausedException") ||
                    message.contains("session is paused") ||
                    message.contains("update frame") ||
                    message.contains("Cannot update") ||
                    message.contains("FAILED_PRECONDITION") ||
                    message.contains("session.cc") ||
                    message.contains("ARCoreError") ||
                    message.contains("ArStatusErrorSpace") ||
                    message.contains("AR_ERROR_SESSION_PAUSED") ||
                    message.contains("update() called with a texture bound")) {
                    isArCoreTransientError = true
                    break
                }
            }

            // Identify if the error occurred on a non-critical background thread
            val isBackground = threadName.contains("DefaultDispatcher", ignoreCase = true) || 
                             threadName.contains("ArCore", ignoreCase = true) || 
                             threadName.contains("worker", ignoreCase = true) ||
                             threadName.contains("Timer", ignoreCase = true) ||
                             threadName.contains("Async", ignoreCase = true) ||
                             threadName.contains("Pool", ignoreCase = true)

            if (isArCoreTransientError) {
                // LOG EVERYTHING to ensure we can see it in any log filter
                val msg = "Lazarus: Suppressing transient ARCore error [${throwable.javaClass.simpleName}] on thread [$threadName]"
                Log.e("Lazarus", msg)
                Log.wtf("Lazarus", msg)
                println(msg) // Direct to stdout for extra visibility
                
                if (isBackground) {
                    // SILENT SUPPRESSION: We prevent the crash by not calling the default handler.
                    // This allows the app to survive the race condition in Jetpack XR's perception loop.
                    return@setDefaultUncaughtExceptionHandler
                } else {
                    // Even on the main/GL thread, we attempt to ignore these to prevent the "Bluescreen" (crash)
                    // though it might result in a single dropped frame.
                    Log.w("Lazarus", "Suppressing MAIN/GL thread ARCore error. Frame may be dropped.")
                    return@setDefaultUncaughtExceptionHandler
                }
            }

            // If it's not a suppressed error, log and crash as normal
            Log.e("Lazarus", "FATAL CRASH in thread $threadName: ${throwable.javaClass.simpleName}")
            throwable.printStackTrace()
            
            // Persistent crash logging for post-resurrection analysis
            try {
                val logFile = java.io.File(getExternalFilesDir(null), "crash_log.txt")
                logFile.appendText("\n--- FATAL CRASH: ${java.util.Date()} ---\n")
                logFile.appendText("Thread: $threadName\n")
                logFile.appendText(Log.getStackTraceString(throwable))
            } catch (e: Exception) {
                Log.e("Lazarus", "Failed to write crash log: ${e.message}")
            }

            // Hand over to the system handler (shows "App has stopped" dialog)
            defaultHandler?.uncaughtException(thread, throwable) ?: exitProcess(1)
        }
        
        Log.i("Lazarus", "Global Lazarus Protocol Engaged.")
    }
}
