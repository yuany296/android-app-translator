package com.manga.translator.app

import android.app.ActivityManager
import android.content.Context

object CaptureSessionGate {
    fun normalizeStoredState(storedState: CaptureSessionState, serviceRunning: Boolean): CaptureSessionState {
        if (serviceRunning) return storedState
        return when (storedState) {
            CaptureSessionState.ACTIVE,
            CaptureSessionState.UNAVAILABLE -> CaptureSessionState.STOPPED

            CaptureSessionState.STOPPED,
            CaptureSessionState.REAUTH_REQUIRED -> storedState
        }
    }

    fun isCaptureServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == CaptureForegroundService::class.java.name &&
                info.service.packageName == context.packageName
        }
    }
}
