package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/** V5.0.4262 — shared meme-lane entry cross-talk bridge. */
object MemeCrossTalkEntryBridge {
    data class EntryShape(
        val lane: String,
        val confidenceFloor: Double,
        val sizeMultiplier: Double,
        val reason: String,
    )

    fun shapeLaneEntry(lane: String, ts: TokenState, confidenceFloor: Double, isOpenPosition: Boolean = false): EntryShape {
        val signal = try { AICrossTalk.analyzeCrossTalk(ts, isOpenPosition) } catch (_: Throwable) { null }
        if (signal == null) return EntryShape(lane.uppercase(), confidenceFloor, 1.0, "cross_talk_unavailable")
        val confDelta = signal.confidenceBoost.coerceIn(-8.0, 8.0)
        val shapedConfidence = (confidenceFloor + confDelta).coerceIn(0.0, 100.0)
        val sizeMult = signal.sizeMultiplier.coerceIn(0.72, 1.18)
        return EntryShape(
            lane = lane.uppercase().take(32),
            confidenceFloor = shapedConfidence,
            sizeMultiplier = sizeMult,
            reason = "type=${signal.signalType} confDelta=${fmt(confDelta)} sizeMult=${fmt(sizeMult)} why=${signal.reason.take(90)}",
        )
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}
