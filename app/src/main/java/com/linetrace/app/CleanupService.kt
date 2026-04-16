package com.linetrace.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class CleanupService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("CleanupService", "App swiped away. Executing Lazarus Exit Strategy...")
        
        // Ensure the process is killed to stop all background threads/WebSocket clients
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
