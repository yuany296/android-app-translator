package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayAccessibilityPolicyTest {
    @Test
    fun decide_ignoresLockedContentChangeForSamePackage() {
        val action = OverlayAccessibilityPolicy.decide(
            packageName = "reader.app",
            targetPackage = "reader.app",
            isHomePackage = false,
            isContentChange = true,
            isTranslationLocked = true
        )

        assertEquals(OverlayAccessibilityAction.IGNORE_LOCKED_CONTENT_CHANGE, action)
    }

    @Test
    fun decide_clearsOnHomeEvenWhenLocked() {
        val action = OverlayAccessibilityPolicy.decide(
            packageName = "com.android.launcher",
            targetPackage = "reader.app",
            isHomePackage = true,
            isContentChange = true,
            isTranslationLocked = true
        )

        assertEquals(OverlayAccessibilityAction.CLEAR_HOME, action)
    }

    @Test
    fun decide_clearsOnPackageChangedEvenWhenLocked() {
        val action = OverlayAccessibilityPolicy.decide(
            packageName = "other.app",
            targetPackage = "reader.app",
            isHomePackage = false,
            isContentChange = true,
            isTranslationLocked = true
        )

        assertEquals(OverlayAccessibilityAction.CLEAR_PACKAGE_CHANGED, action)
    }
}
