package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayAccessibilityPolicyTest {
    @Test
    fun decide_clearsContentChangeForSamePackage() {
        val action = OverlayAccessibilityPolicy.decide(
            packageName = "reader.app",
            targetPackage = "reader.app",
            isHomePackage = false,
            isContentChange = true
        )

        assertEquals(OverlayAccessibilityAction.CLEAR_PAGE_CHANGED, action)
    }

    @Test
    fun decide_clearsOnHome() {
        val action = OverlayAccessibilityPolicy.decide(
            packageName = "com.android.launcher",
            targetPackage = "reader.app",
            isHomePackage = true,
            isContentChange = true
        )

        assertEquals(OverlayAccessibilityAction.CLEAR_HOME, action)
    }

    @Test
    fun decide_clearsOnPackageChanged() {
        val action = OverlayAccessibilityPolicy.decide(
            packageName = "other.app",
            targetPackage = "reader.app",
            isHomePackage = false,
            isContentChange = true
        )

        assertEquals(OverlayAccessibilityAction.CLEAR_PACKAGE_CHANGED, action)
    }
}
