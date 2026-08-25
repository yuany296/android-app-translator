package com.manga.translator.app

internal enum class OverlayAccessibilityAction {
    NONE,
    CLEAR_HOME,
    CLEAR_PACKAGE_CHANGED,
    CLEAR_PAGE_CHANGED
}

internal object OverlayAccessibilityPolicy {
    fun decide(
        packageName: String,
        targetPackage: String?,
        isHomePackage: Boolean,
        isContentChange: Boolean
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
        // 页面内容变化时，即使处于锁定态也应清理旧译文，避免翻页/滚动后“译点不动”。
        return OverlayAccessibilityAction.CLEAR_PAGE_CHANGED
    }
}
