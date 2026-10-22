package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8308 — EXITS READ THE CANONICAL FILL BASIS.
 *
 * 5.0.8306 live: open 6mYfg1 carried runtime entry 5.93e-4 against its canonical (chain-fill) entry 6.72e-4, a ~13 %
 * gap. The canonical position's entry USD/token is locked from the buy's tx-meta proof (SOL spent / tokens received x
 * the event-time SOL/USD) when the fill commits; PositionDisplayTruth8078 already shows that basis and logs
 * DISPLAY_RUNTIME_BASIS_DIVERGES_8078 "so the engine basis can be repaired at source" — but nothing repaired it, and the
 * exit engine (STRICT_SL, partial / profit-lock levels, the risk clock, peak arming) computes P&L from
 * TokenState.position.entryPrice. A runtime entry 12 % under the fill makes every price stop fire ~12 points late.
 *
 * Rule: for an open LIVE position whose canonical row (same positionId, OPEN / PARTIALLY_CLOSED, live, positive entry
 * and cost) carries a clean chain basis (CanonicalTradeFinalizedBus6450.learningUncleanReason7807 == null: not
 * quarantined, not wallet-adopted / observed-mark / unknown basis), a runtime entry more than [DIVERGENCE_FRAC_8308] off
 * the canonical entry is set to the canonical entry. Only the entry price moves; quantity, cost and the market high /
 * low stay as they are. A position without a clean canonical basis is left alone (nothing is invented).
 */
object RuntimeBasisSync8308 {

    const val DIVERGENCE_FRAC_8308 = 0.02

    private val synced = AtomicLong(0)
    private val lastLogged = ConcurrentHashMap<String, Long>()

    /** Pure: the entry the exit engine should use, or null to keep [runtimeEntry]. */
    fun repairedEntry8308(runtimeEntry: Double, canonicalEntry: Double, canonicalClean: Boolean): Double? {
        if (!canonicalClean || !canonicalEntry.isFinite() || canonicalEntry <= 0.0) return null
        if (!runtimeEntry.isFinite() || runtimeEntry <= 0.0) return canonicalEntry
        return if (kotlin.math.abs(runtimeEntry / canonicalEntry - 1.0) > DIVERGENCE_FRAC_8308) canonicalEntry else null
    }

    /** Executor.manageableForExit7146 (every manage tick): align the runtime entry with the canonical fill basis. */
    fun sync8308(ts: TokenState): Boolean {
        val pos = ts.position
        if (pos.isPaperPosition || !pos.isOpen) return false
        return try {
            val pid = pos.positionId.ifBlank { ExecutorCanonicalMirror6442.positionIdOf(ts.mint, false) }
            val canon = CanonicalPositionAuthority6441.getPosition(pid) ?: return false
            val open = canon.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN ||
                canon.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED
            if (!open || canon.mint != ts.mint || !canon.mode.equals("live", true) || canon.entryCostSol <= 0.0) return false
            val clean = CanonicalTradeFinalizedBus6450.learningUncleanReason7807(canon.positionId, canon.mint) == null
            val fixed = repairedEntry8308(pos.entryPrice, canon.entryPriceUsd, clean) ?: return false
            val old = pos.entryPrice
            ts.position = pos.copy(entryPrice = fixed)
            synced.incrementAndGet()
            val now = System.currentTimeMillis()
            if (now - (lastLogged[ts.mint] ?: 0L) > 60_000L) {
                lastLogged[ts.mint] = now
                if (lastLogged.size > 500) lastLogged.entries.removeIf { now - it.value > 600_000L }
                PipelineHealthCollector.labelInc("RUNTIME_ENTRY_SYNCED_TO_CANONICAL_FILL_8308")
                ForensicLogger.lifecycle("RUNTIME_ENTRY_SYNCED_TO_CANONICAL_FILL_8308",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} positionId=${canon.positionId.take(24)} runtimeEntry=$old canonicalEntry=$fixed " +
                        "gapPct=${if (fixed > 0.0 && old > 0.0) "%.1f".format((old / fixed - 1.0) * 100.0) else "-"} source=${canon.entryPriceSource.take(40)}")
            }
            true
        } catch (_: Throwable) { false }
    }

    fun statusLine8308(): String = "runtimeBasisSync8308[synced=${synced.get()}]"

    internal fun resetForTest8308() { synced.set(0); lastLogged.clear() }
}
