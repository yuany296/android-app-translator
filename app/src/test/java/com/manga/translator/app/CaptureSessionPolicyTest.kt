package com.manga.translator.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionPolicyTest {
    @Test
    fun shouldRequestProjection_onlyForStoppedOrReauthRequired() {
        assertTrue(CaptureSessionPolicy.shouldRequestProjection(CaptureSessionState.STOPPED))
        assertTrue(CaptureSessionPolicy.shouldRequestProjection(CaptureSessionState.REAUTH_REQUIRED))
        assertFalse(CaptureSessionPolicy.shouldRequestProjection(CaptureSessionState.ACTIVE))
        assertFalse(CaptureSessionPolicy.shouldRequestProjection(CaptureSessionState.UNAVAILABLE))
    }
}
