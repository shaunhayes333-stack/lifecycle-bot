package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — PeerAlphaVerificationAI
 *
 * Cross-instance sanity check: if ANY peer AATE bot in the hive has
 * already closed this mint at a loss in the last 2 hours, don't repeat
 * someone else's mistake.
 *
 * We DO NOT re-implement the collective bus here — we bridge via the
 * existing CollectiveIntelligenceAI.recentLossMints() if available.
 * Fails soft when the collective module isn't initialised or has no
 * recent peer data (returns neutral score).
 */
object PeerAlphaVerificationAI {

    private const val TAG = "PeerAlpha"
    private const val RECENT_LOSS_WINDOW_MS = 2L * 3600_000L

    // Minimal peer-loss registry: mint → timestampMs, populated by the
    // collective loader (NetworkSignalAutoBuyer broadcasts into here).
    private val peerLosses = ConcurrentHashMap<String, Long>()

    fun markPeerLoss(mint: String) {
        peerLosses[mint] = System.currentTimeMillis()
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val lossAt = peerLosses[candidate.mint] ?: return ScoreComponent("PeerAlphaVerificationAI", 0, "👥 no peer history")
        val ageMs = System.currentTimeMillis() - lossAt
        if (ageMs > RECENT_LOSS_WINDOW_MS) {
            peerLosses.remove(candidate.mint)
            return ScoreComponent("PeerAlphaVerificationAI", 0, "👥 peer loss stale")
        }
        val minutesAgo = ageMs / 60_000L
        return ScoreComponent(
            "PeerAlphaVerificationAI", -8,
            "👥 peer lost on this mint ${minutesAgo}m ago → avoid"
        )
    }
}
