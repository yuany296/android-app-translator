package com.manga.translator.app

import android.content.Context

class CaptureSessionStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getSnapshot(): CaptureSessionSnapshot {
        return CaptureSessionSnapshot(
            state = CaptureSessionState.fromId(prefs.getString(KEY_STATE, CaptureSessionState.STOPPED.id).orEmpty()),
            reason = prefs.getString(KEY_REASON, "")?.trim().orEmpty(),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT_MS, 0L)
        )
    }

    fun getState(): CaptureSessionState = getSnapshot().state

    fun update(state: CaptureSessionState, reason: String) {
        prefs.edit()
            .putString(KEY_STATE, state.id)
            .putString(KEY_REASON, reason.trim())
            .putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val PREF_NAME = "mt_capture_session"
        private const val KEY_STATE = "state"
        private const val KEY_REASON = "reason"
        private const val KEY_UPDATED_AT_MS = "updated_at_ms"
    }
}
