package com.manga.translator.app

enum class CaptureSessionState(val id: String) {
    STOPPED("stopped"),
    ACTIVE("active"),
    UNAVAILABLE("unavailable"),
    REAUTH_REQUIRED("reauth_required");

    companion object {
        fun fromId(value: String): CaptureSessionState {
            return entries.firstOrNull { it.id == value.trim().lowercase() } ?: STOPPED
        }
    }
}

data class CaptureSessionSnapshot(
    val state: CaptureSessionState,
    val reason: String,
    val updatedAtMs: Long
)
