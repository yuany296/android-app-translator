package com.manga.translator.app

object CaptureSessionPolicy {
    fun shouldRequestProjection(state: CaptureSessionState): Boolean {
        return when (state) {
            CaptureSessionState.STOPPED,
            CaptureSessionState.REAUTH_REQUIRED -> true

            CaptureSessionState.ACTIVE,
            CaptureSessionState.UNAVAILABLE -> false
        }
    }
}
