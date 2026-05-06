package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.353 — Meme Loss-Streak Guard
 *
 * After 9 hours of running the user observed the bot rebuying the same
 * tokens through repeated losses (WLD: closed 1s ago after −35%, Scum
 * Ultman re-entered into a -8% stop). The 5 min cooldown alone wasn't
 * enough — what was missing is a "stop trying" rule per token.
 *
 * Rule: if a mint's last 3 closed trades were ALL losses, refuse any
 * new entry on that mint for 60 minutes. Win or scratch resets the
 * streak.
 */
object MemeLossStreakGuard {

    private const val STREAK_LIMIT = 3
    private const val BLOCK_DURATION_MS = 60L * 60_000L  // 1 hour

    private data class Entry(
        val recent: java.util.ArrayDeque<Boolean> = java.util.ArrayDeque(),  // true = loss
        @Volatile var blockUntilMs: Long = 0L,
    )

    private val state = ConcurrentHashMap<String, Entry>()

    /** Call when a meme position closes. isWin = true if PnL > 0. */
    fun recordOutcome(mint: String, isWin: Boolean) {
        if (mint.isBlank()) return
        val e = state.getOrPut(mint) { Entry() }
        synchronized(e) {
            // win/scratch resets streak; loss appends.
            if (isWin) {
                e.recent.clear()
                e.blockUntilMs = 0L
                return
            }
            e.recent.addLast(true)
            while (e.recent.size > STREAK_LIMIT) e.recent.pollFirst()
            if (e.recent.size >= STREAK_LIMIT && e.recent.all { it }) {
                e.blockUntilMs = System.currentTimeMillis() + BLOCK_DURATION_MS
                ErrorLogger.warn(
                    "MemeLossStreakGuard",
                    "🛑 ${mint.take(8)}… 3 losses in a row — blocking re-entry for ${BLOCK_DURATION_MS / 60_000} min"
                )
            }
        }
    }

    /** Returns blocked-until timestamp (0 if not blocked). */
    fun blockedUntilMs(mint: String): Long {
        // V5.9.408 — free-range mode bypasses meme-loss-streak blocks so
        // the bot keeps feeding the Treasury during maximum-learning window.
        if (FreeRangeMode.isWideOpen()) return 0L
        // V5.9.495z12 — operator mandate: gates must be soft early so the
        // bot can learn freely. In paper mode (which IS the learning lab)
        // a 60-min per-mint freeze starves the very dataset Adaptive,
        // Fluid, and Edge learning need to mature. Bypass the streak
        // guard whenever paper is active — capital is fake, signal is the
        // product. Live mode keeps the guard intact (real money safety).
        try {
            if (com.lifecyclebot.engine.KillSwitch.isPaperMode) return 0L
        } catch (_: Throwable) {}
        val e = state[mint] ?: return 0L
        val until = e.blockUntilMs
        if (until == 0L) return 0L
        if (System.currentTimeMillis() >= until) {
            // Window elapsed — clear the block but keep streak so next
            // close still has context.
            e.blockUntilMs = 0L
            return 0L
        }
        return until
    }

    /** Convenience: are we blocked right now? */
    fun isBlocked(mint: String): Boolean = blockedUntilMs(mint) > 0L

    /** V5.9.453: number of mints currently blocked by a 3-loss streak. */
    fun activeBlockCount(): Int {
        if (FreeRangeMode.isWideOpen()) return 0
        val now = System.currentTimeMillis()
        var n = 0
        for (e in state.values) {
            if (e.blockUntilMs > now) n++
        }
        return n
    }

    fun clear(mint: String) {
        state.remove(mint)
    }
}
