package com.manga.translator.app

import android.os.SystemClock
import android.util.Log

class TranslationTimer(
    val traceId: String = SystemClock.elapsedRealtime().toString()
) {
    private val marks = linkedMapOf<String, Long>()

    fun mark(tag: String, extra: String? = null) {
        val now = SystemClock.elapsedRealtime()
        marks[tag] = now
        val elapsed = sinceStart(now)
        val suffix = extra?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        Log.d(TAG, "[$traceId] $tag: ${elapsed}ms$suffix")
    }

    private fun sinceStart(now: Long): Long {
        val start = marks["start"] ?: now
        return now - start
    }

    companion object {
        private const val TAG = "TranslationPerf"
    }
}
