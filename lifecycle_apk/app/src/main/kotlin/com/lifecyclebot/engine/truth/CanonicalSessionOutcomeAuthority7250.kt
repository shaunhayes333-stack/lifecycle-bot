package com.lifecyclebot.engine.truth

/**
 * Read-only UI projection of canonical closes from the current bot session.
 * It is intentionally separate from BehaviorAI's MEME-only tilt counters:
 * QUALITY, PROJECT_SNIPER, CRYPTO_ALT and every other lane are real session
 * closes, but they must not contaminate the legacy meme tilt state.
 */
object CanonicalSessionOutcomeAuthority7250 {
    data class Snapshot(val trades: Int, val wins: Int, val losses: Int, val scratches: Int, val bigWins: Int)

    @Volatile private var cachedAtMs = 0L
    @Volatile private var cached = Snapshot(0, 0, 0, 0, 0)

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (nowMs - cachedAtMs < 1_000L) return cached
        val startedAt = try { com.lifecyclebot.engine.PipelineHealthCollector.sessionStartedAtMs7250() } catch (_: Throwable) { 0L }
        if (startedAt <= 0L) return cached
        var wins = 0
        var losses = 0
        var scratches = 0
        var bigWins = 0
        val closes = CanonicalPositionAuthority6441.closedPositions()
            .filter { it.lastMutationMs >= startedAt }
        for (position in closes) {
            when (CanonicalOutcomeClassifier6576.classifyPnl(position.realizedPnlSol, position.entryCostSol)) {
                CanonicalOutcomeClassifier6576.Class.WIN -> wins++
                CanonicalOutcomeClassifier6576.Class.LOSS -> losses++
                CanonicalOutcomeClassifier6576.Class.BREAKEVEN -> scratches++
            }
            val pct = if (position.entryCostSol > 0.0) position.realizedPnlSol / position.entryCostSol * 100.0 else 0.0
            if (pct.isFinite() && pct >= 50.0) bigWins++
        }
        return Snapshot(closes.size, wins, losses, scratches, bigWins).also {
            cached = it
            cachedAtMs = nowMs
        }
    }
}
