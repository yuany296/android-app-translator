package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSessionGateTest {
    @Test
    fun normalizeStoredState_resetsActiveWhenServiceIsMissing() {
        assertEquals(
            CaptureSessionState.STOPPED,
            CaptureSessionGate.normalizeStoredState(
                storedState = CaptureSessionState.ACTIVE,
                serviceRunning = false
            )
        )
    }

    @Test
    fun normalizeStoredState_resetsUnavailableWhenServiceIsMissing() {
        assertEquals(
            CaptureSessionState.STOPPED,
            CaptureSessionGate.normalizeStoredState(
                storedState = CaptureSessionState.UNAVAILABLE,
                serviceRunning = false
            )
        )
    }

    @Test
    fun normalizeStoredState_keepsReauthRequiredWhenServiceIsMissing() {
        assertEquals(
            CaptureSessionState.REAUTH_REQUIRED,
            CaptureSessionGate.normalizeStoredState(
                storedState = CaptureSessionState.REAUTH_REQUIRED,
                serviceRunning = false
            )
        )
    }

    @Test
    fun normalizeStoredState_keepsActiveWhenServiceIsRunning() {
        assertEquals(
            CaptureSessionState.ACTIVE,
            CaptureSessionGate.normalizeStoredState(
                storedState = CaptureSessionState.ACTIVE,
                serviceRunning = true
            )
        )
    }
}
