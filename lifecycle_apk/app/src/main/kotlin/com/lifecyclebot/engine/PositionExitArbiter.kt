/*
 * V5.9.791 — CanonicalPositionOutcomeBus + PositionExitArbiter
 * ─────────────────────────────────────────────────────────────────
 *
 * Operator audit (build 5.0.2729) — Item 1 + Item 2:
 *
 *   "duplicate terminal SELL outcomes" + "CASHGEN_STOP_LOSS /
 *    v8_exit_score / RAPID_CATASTROPHE_STOP / STRICT_SL cannot all
 *    close the same position. Later exits must log suppressed reason
 *    and not journal a SELL."
 *
 * Design:
 *   • Key (positionKey) = "<canonicalMint>_<entryTimeMs>". When the
 *     caller can't resolve an entryTimeMs we fall back to the mint
 *     alone + a wall-clock bucket (300s) so two exits within five
 *     minutes of each other on the same mint still collide.
 *   • One terminal SELL per positionKey is journalled. Subsequent
 *     calls return SUPPRESS and emit forensic
 *     EXIT_SUPPRESSED_DUPLICATE with the FIRST reason that won the
 *     arbiter — so the operator can see exactly which exit cascade
 *     was attempted.
 *   • PARTIAL_SELL is explicitly NOT terminal — it goes through a
 *     separate counter and does NOT lock the slot.
 *   • Hand-shake with CanonicalOutcomeBus: the bus calls into the
 *     arbiter inside publish() for any WIN/LOSS outcome. SUPPRESS
 *     short-circuits the fan-out so every learner stays in lockstep
 *     and HostWalletTokenTracker drift stops being polluted by the
 *     cascade fire pattern.
 *
 * Counters exposed via PositionArbiterCounters.snapshot():
 *   • terminalSells          — first-write-wins terminal exits
 *   • suppressedDuplicates   — later cascade firings rejected
 *   • partialSells           — explicit partial-sell calls
 *   • staleSlotEvictions     — entries dropped by the 24h TTL sweep
 *
 * Out of scope for this push: positionId is currently
 * synthesised from (mint, entryTimeMs|wall-clock bucket). A full
 * positionId schema lives on Item 5 (split liquidity fields) which
 * comes in a follow-up commit.
 */
package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object PositionArbiterCounters {
    val terminalSells = AtomicLong(0)
    val suppressedDuplicates = AtomicLong(0)
    val partialSells = AtomicLong(0)
    val staleSlotEvictions = AtomicLong(0)

    fun snapshot(): Map<String, Long> = mapOf(
        "terminalSells" to terminalSells.get(),
        "suppressedDuplicates" to suppressedDuplicates.get(),
        "partialSells" to partialSells.get(),
        "staleSlotEvictions" to staleSlotEvictions.get(),
    )

    fun reset() {
        terminalSells.set(0)
        suppressedDuplicates.set(0)
        partialSells.set(0)
        staleSlotEvictions.set(0)
    }
}

/**
 * Per-position dedupe authority. First terminal-SELL reason wins.
 * Callers MUST invoke [arbitrate] before journalling / publishing
 * a terminal SELL. PARTIAL_SELL paths invoke [recordPartial].
 */
object PositionExitArbiter {
    private const val TAG = "PositionExitArbiter"

    // 5-minute fallback bucket when the caller has no entryTimeMs.
    private const val WALL_CLOCK_BUCKET_MS = 5L * 60_000L

    // Drop completed slots after 24h to keep the map bounded.
    private const val SLOT_TTL_MS = 24L * 60L * 60_000L

    data class TerminalRecord(
        val positionKey: String,
        val firstReason: String,
        val firstAtMs: Long,
        val firstSig: String?,
        val firstEnv: String,    // PAPER / LIVE / SHADOW
    )

    enum class Decision { ALLOW, SUPPRESS }

    data class Verdict(
        val decision: Decision,
        val winningRecord: TerminalRecord,
    )

    private val records = ConcurrentHashMap<String, TerminalRecord>()

    /**
     * Build a positionKey from the canonical mint + entry timestamp.
     * When entryTimeMs <= 0 we synthesise one from the current wall
     * clock bucket so back-to-back duplicate cascades on the same
     * mint still collide.
     */
    fun positionKey(canonicalMint: String, entryTimeMs: Long): String {
        val pivot = if (entryTimeMs > 0L) entryTimeMs
                    else System.currentTimeMillis() / WALL_CLOCK_BUCKET_MS
        return "${canonicalMint}_$pivot"
    }

    /**
     * Arbitrate a terminal SELL. Returns ALLOW exactly once per
     * positionKey; later calls return SUPPRESS along with the
     * record that won the race. On SUPPRESS we emit a single
     * forensic EXIT_SUPPRESSED_DUPLICATE line so the operator can
     * trace the cascade that fired second.
     */
    fun arbitrate(
        positionKey: String,
        reason: String,
        env: String,
        sig: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Verdict {
        // Opportunistic stale-slot sweep — cheap because we only run it
        // when the map crosses a small threshold.
        if (records.size >= 1024) sweepStaleSlots(nowMs)

        var winning: TerminalRecord? = null
        val record = records.compute(positionKey) { _, existing ->
            if (existing != null) {
                winning = existing
                existing
            } else {
                val rec = TerminalRecord(
                    positionKey = positionKey,
                    firstReason = reason.ifBlank { "UNKNOWN_TERMINAL" },
                    firstAtMs = nowMs,
                    firstSig = sig?.takeIf { it.isNotBlank() },
                    firstEnv = env,
                )
                winning = rec
                rec
            }
        }!!

        return if (winning === record && record.firstAtMs == nowMs) {
            PositionArbiterCounters.terminalSells.incrementAndGet()
            Verdict(Decision.ALLOW, record)
        } else {
            PositionArbiterCounters.suppressedDuplicates.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXIT_SUPPRESSED_DUPLICATE",
                    "key=$positionKey attempt=$reason env=$env first=${record.firstReason} firstAtMs=${record.firstAtMs} ageMs=${nowMs - record.firstAtMs}",
                )
            } catch (_: Throwable) {}
            try {
                ErrorLogger.debug(TAG, "SUPPRESS positionKey=$positionKey attempt=$reason firstReason=${record.firstReason}")
            } catch (_: Throwable) {}
            Verdict(Decision.SUPPRESS, record)
        }
    }

    /** Convenience overload — derives positionKey from (mint, entryTimeMs). */
    fun arbitrate(
        canonicalMint: String,
        entryTimeMs: Long,
        reason: String,
        env: String,
        sig: String? = null,
    ): Verdict {
        return arbitrate(positionKey(canonicalMint, entryTimeMs), reason, env, sig)
    }

    /**
     * PARTIAL_SELL signalling — never locks a slot; just bumps the
     * counter so the dashboard can show "partials handled separately".
     */
    fun recordPartial(canonicalMint: String) {
        PositionArbiterCounters.partialSells.incrementAndGet()
        try {
            ForensicLogger.lifecycle("EXIT_PARTIAL_RECORDED", "mint=$canonicalMint")
        } catch (_: Throwable) {}
    }

    /**
     * For tests / hot-restart scenarios. NOT to be called from normal
     * runtime — the 24h TTL handles staleness.
     */
    fun clearAll() {
        records.clear()
        PositionArbiterCounters.reset()
    }

    /** Diagnostic — caller iterates to render the dashboard. */
    fun snapshot(): List<TerminalRecord> = records.values.toList()

    private fun sweepStaleSlots(nowMs: Long) {
        val cutoff = nowMs - SLOT_TTL_MS
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.firstAtMs < cutoff) {
                it.remove()
                PositionArbiterCounters.staleSlotEvictions.incrementAndGet()
            }
        }
    }
}
