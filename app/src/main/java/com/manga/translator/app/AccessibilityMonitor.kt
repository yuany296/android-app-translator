package com.manga.translator.app

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class AccessibilityMonitorEvent(
    val type: Int,
    val packageName: String?,
    val className: String?,
    val eventTime: Long
) {
    fun typeName(): String {
        return when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows_changed"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "view_scrolled"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
            else -> "unknown"
        }
    }

    fun isContentChange(): Boolean {
        return type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }
}

object AccessibilityMonitor {
    private val _events = MutableSharedFlow<AccessibilityMonitorEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    var currentForegroundPackage: String? = null
        private set

    val events: SharedFlow<AccessibilityMonitorEvent> = _events

    fun publish(event: AccessibilityMonitorEvent) {
        event.packageName?.takeIf { it.isNotBlank() }?.let {
            currentForegroundPackage = it
        }
        _events.tryEmit(event)
    }
}
