package com.manga.translator.app

internal enum class OverlayAccessibilityAction {
    NONE,
    CLEAR_HOME,
    CLEAR_PACKAGE_CHANGED,
    CLEAR_PAGE_CHANGED,
    IGNORE_LOCKED_CONTENT_CHANGE
}

internal object OverlayAccessibilityPolicy {
    fun decide(
        packageName: String,
        targetPackage: String?,
        isHomePackage: Boolean,
        isContentChange: Boolean,
        isTranslationLocked: Boolean
    ): OverlayAccessibilityAction {
        if (isHomePackage) {
            return OverlayAccessibilityAction.CLEAR_HOME
        }
        if (targetPackage.isNullOrBlank()) {
            return OverlayAccessibilityAction.NONE
        }
        if (packageName != targetPackage) {
            return OverlayAccessibilityAction.CLEAR_PACKAGE_CHANGED
        }
        if (!isContentChange) {
            return OverlayAccessibilityAction.NONE
        }
        if (isTranslationLocked) {
            return OverlayAccessibilityAction.IGNORE_LOCKED_CONTENT_CHANGE
        }
        return OverlayAccessibilityAction.CLEAR_PAGE_CHANGED
    }
}
