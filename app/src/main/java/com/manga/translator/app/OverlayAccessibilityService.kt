package com.manga.translator.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class OverlayAccessibilityService : AccessibilityService() {
    private var lastKnownPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType !in INTERESTING_EVENT_TYPES) return
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: lastKnownPackage
        if (!packageName.isNullOrBlank()) {
            lastKnownPackage = packageName
        }
        AccessibilityMonitor.publish(
            AccessibilityMonitorEvent(
                type = event.eventType,
                packageName = packageName,
                className = event.className?.toString(),
                eventTime = event.eventTime
            )
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "service_connected")
    }

    companion object {
        private const val TAG = "OverlayA11yService"
        private val INTERESTING_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
    }
}
