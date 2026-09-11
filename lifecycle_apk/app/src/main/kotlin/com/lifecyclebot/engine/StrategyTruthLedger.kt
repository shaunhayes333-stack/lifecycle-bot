package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import kotlin.math.abs

/**
 * V5.0.4151 — STRATEGY TRUTH LEDGER / DEDUPER.
 *
 * Produces one clean terminal strategy outcome per real bot-opened position.
 * This is the canonical strategy-learning read filter: inventory recovery,
 * duplicate terminal rows, partial exits, zero-basis rows and bad entry rows are
 * excluded from strategy WR/PnL while still remaining available to raw journal /
 * inventory accounting surfaces.
 */
object StrategyTruthLedger {
    const val VERSION = "V5.0.4151_STRATEGY_TRUTH_LEDGER"

    // Cache only an exact immutable cohort snapshot. Count/time buckets can alias
    // different lanes or modes, and Trade.pnlPct is mutable. Quarantine state is
    // part of the key so both exclusions and releases invalidate immediately.
    private const val CLEAN_CACHE_TTL_MS: Long = 10_000L
    private val cleanCacheLock = Any()
    private data class CleanInput6737(
        val rows: List<Trade>, val limit: Int, val quarantined: List<Boolean>,
    )
    private var cleanCacheKey: CleanInput6737? = null
    private var cleanCacheValue: Result? = null
    private var cleanCacheStampMs: Long = 0L

    private fun quarantined6737(row: Trade): Boolean = try { row.mint.isNotBlank() && (
        com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.isQuarantined(row.mint) ||
            com.lifecyclebot.engine.truth.LearningQuarantineGate6470.isQuarantined(
                positionId = row.positionId.takeIf { it.isNotBlank() }, mint = row.mint,
            )
        ) } catch (_: Throwable) { true }

    private fun detached6737(value: Result): Result =
        value.copy(rows = value.rows.map { it.copy() })

    // V5.0.6404 §A — LIFETIME TERMINAL COUNTER DEDUPER.
    // Operator's V5.0.6404 emergency dump showed STRATEGY_CLEAN_TERMINAL_ROWS
    // = 1,531,885 (≈10,811/cycle, ≈111/sec) — the counter fired for every
    // emitted row on every clean() call, not per unique terminal. The
    // ForensicReconciler consumes this counter as "distinct terminals",
    // producing catastrophic overcounts and thousands of derived events
    // per second. Fix: gate the counter increment behind a lifetime
    // seen-once set keyed by canonical terminal identity. Bounded at
    // MAX_SEEN_TERMINAL_KEYS via LRU so the set cannot grow unbounded
    // over long uptimes.
    private const val MAX_SEEN_TERMINAL_KEYS = 8_192
    private val seenTerminalKeysLifetime = java.util.Collections.synchronizedSet(
        object : java.util.LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                if (added && size > MAX_SEEN_TERMINAL_KEYS) {
                    // Drop oldest to keep memory bounded — a rediscovered
                    // very-old terminal costs one extra counter tick, not
                    // a runaway.
                    val it = iterator()
                    if (it.hasNext()) { it.next(); it.remove() }
                }
                return added
            }
        }
    )

    data class Audit(
        val cleaned: Int,
        val deduped: Int,
        val recoveryExcluded: Int,
        val partialNotTerminal: Int,
        val badEntryExcluded: Int,
        val forensicExcluded: Int = 0,
    )

    data class Result(
        val rows: List<Trade>,
        val audit: Audit,
    )

    fun cleanedTerminalRows(rawRows: List<Trade>, limit: Int = rawRows.size): List<Trade> =
        clean(rawRows, limit).rows

    fun clean(rawRows: List<Trade>, limit: Int = rawRows.size): Result {
        if (rawRows.isEmpty()) return Result(emptyList(), Audit(0, 0, 0, 0, 0, 0))

        val now = System.currentTimeMillis()
        val inputRows6737 = rawRows.map { it.copy() }
        val key = CleanInput6737(inputRows6737, limit, inputRows6737.map { quarantined6737(it) })
        val cached = synchronized(cleanCacheLock) {
            cleanCacheValue?.takeIf {
                cleanCacheKey == key && now - cleanCacheStampMs in 0 until CLEAN_CACHE_TTL_MS
            }?.let { detached6737(it) }
        }
        if (cached != null) {
            try { PipelineHealthCollector.labelInc("STRATEGY_CLEAN_CACHE_HIT_6358") } catch (_: Throwable) {}
            return cached
        }
        try { PipelineHealthCollector.labelInc("STRATEGY_CLEAN_CACHE_MISS_6358") } catch (_: Throwable) {}

        val newestFirst = inputRows6737.sortedByDescending { it.ts }
        val seenTerminalKeys = LinkedHashSet<String>()
        val seenGenerationKeys = LinkedHashSet<String>()
        val seenMintCloseWindows = LinkedHashMap<String, Long>()
        val out = ArrayList<Trade>(limit.coerceAtLeast(1))
        var deduped = 0
        var recovery = 0
        var partial = 0
        var badEntry = 0
        var forensic = 0

        for (row in newestFirst) {
            if (out.size >= limit.coerceAtLeast(1)) break
            val side = row.side.trim().uppercase()
            if (side == "PARTIAL_SELL") {
                // A partial can be terminal only when wallet amount is zero. In
                // current schema remainingQtyToken is the best persisted proxy.
                if (row.remainingQtyToken > 0.000000001) {
                    partial++
                    // V5.0.6404 §A — gate STRATEGY_PARTIAL_NOT_TERMINAL by
                    // lifetime dedupe (was: fired every call for every
                    // unchanged partial). Reuses the same seen-set with a
                    // "PARTIAL:" prefix so partial and terminal keys never
                    // collide.
                    val partialKey = "PARTIAL:" + terminalKey(row)
                    if (seenTerminalKeysLifetime.add(partialKey)) {
                        inc("STRATEGY_PARTIAL_NOT_TERMINAL")
                    }
                    continue
                }
            } else if (side != "SELL") {
                continue
            }

            if (isRecoveryInventory(row)) {
                recovery++
                inc("STRATEGY_RECOVERY_EXCLUDED")
                continue
            }
            if (!hasValidEntryBasis(row)) {
                badEntry++
                inc("STRATEGY_BAD_ENTRY_EXCLUDED")
                continue
            }
            val forensicReject = forensicRejectReason(row)
            if (forensicReject != null) {
                forensic++
                inc("STRATEGY_FORENSIC_EXCLUDED_${forensicReject}")
                continue
            }
            // V5.0.6501 §1 — SOURCE-LEVEL QUARANTINE. StrategyTruthLedger
            // is the strategy learner's canonical read filter. Positions
            // with a quantity-invariant break (§6500) or historical
            // economic quarantine (§6496 §2) must be excluded from every
            // strategy μ / WR / PF / expectancy derivation — not just
            // the reward bridge.
            if (row.mint.isNotBlank()) {
                val invariantBroken = try {
                    com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.isQuarantined(row.mint)
                } catch (_: Throwable) { false }
                if (invariantBroken) {
                    forensic++
                    inc("STRATEGY_INVARIANT_QUARANTINED_6501")
                    continue
                }
                val historicalQuarantined = try {
                    com.lifecyclebot.engine.truth.LearningQuarantineGate6470.isQuarantined(
                        positionId = row.positionId.takeIf { it.isNotBlank() }, mint = row.mint,
                    )
                } catch (_: Throwable) { false }
                if (historicalQuarantined) {
                    forensic++
                    inc("STRATEGY_HISTORICAL_QUARANTINED_6501")
                    continue
                }
            }

            val terminalKey = terminalKey(row)
            val generationKey = generationKey(row)
            val mintWindowKey = mintCloseWindowKey(row)
            val priorCloseTs = seenMintCloseWindows[mintWindowKey]
            val sameMintCloseDuplicate = row.positionId.isBlank() && row.entryTsMs <= 0L && priorCloseTs != null && row.ts > 0L && kotlin.math.abs(priorCloseTs - row.ts) <= SAME_MINT_TERMINAL_DEDUP_WINDOW_MS
            if (!seenTerminalKeys.add(terminalKey) || !seenGenerationKeys.add(generationKey) || sameMintCloseDuplicate) {
                deduped++
                // V5.0.6404 §A — gate STRATEGY_TERMINAL_DEDUPED +
                // STRATEGY_MINT_CLOSE_WINDOW_DEDUPED_4494 by lifetime
                // dedupe. Reuses seenTerminalKeysLifetime with a "DEDUP:"
                // prefix. Prior code fired both counters on EVERY call for
                // every duplicate — 160k + 82k of the 2.4M storm.
                val dedupKey = "DEDUP:$terminalKey|${if (sameMintCloseDuplicate) 1 else 0}"
                if (seenTerminalKeysLifetime.add(dedupKey)) {
                    inc("STRATEGY_TERMINAL_DEDUPED")
                    if (sameMintCloseDuplicate) inc("STRATEGY_MINT_CLOSE_WINDOW_DEDUPED_4494")
                }
                continue
            }
            if (row.ts > 0L) seenMintCloseWindows[mintWindowKey] = row.ts

            // V5.0.6404 §A — increment STRATEGY_CLEAN_TERMINAL_ROWS ONLY
            // the first time we ever see this canonical terminal (per
            // process lifetime). Prior code incremented for every emission,
            // producing the 2.4M/6h storm. STRATEGY_PARTIAL_NOT_TERMINAL
            // and STRATEGY_TERMINAL_DEDUPED are similarly gated below.
            if (seenTerminalKeysLifetime.add(terminalKey)) {
                inc("STRATEGY_CLEAN_TERMINAL_ROWS")
            }
            out += normalizedStrategyRow(row)
        }
        val result = Result(out, Audit(out.size, deduped, recovery, partial, badEntry, forensic))
        // V5.0.6358 — publish to cache. Overwrite is unconditional under lock
        // so races produce identical Result contents for the same key.
        synchronized(cleanCacheLock) {
            cleanCacheKey = key
            cleanCacheValue = detached6737(result)
            cleanCacheStampMs = System.currentTimeMillis()
        }
        return result
    }

    fun isRecoveryInventory(t: Trade): Boolean {
        val hay = listOf(t.tradingMode, t.reason, t.proofState, t.entryPriceSource, t.positionId)
            .joinToString("|")
            .uppercase()
        return hay.contains("WALLET_RECOVERED") ||
            hay.contains("OPEN_RESTORED") ||
            hay.contains("ADOPTED_FROM_WALLET") ||
            hay.contains("RECOVERED_") ||
            hay.contains("RESTORED_") ||
            hay.contains("INVENTORY_RECON")
    }

    fun inventoryRecoveryRows(rawRows: List<Trade>): List<Trade> =
        rawRows.filter { isRecoveryInventory(it) }

    fun hasValidEntryBasis(t: Trade): Boolean {
        val entrySol = when {
            t.entryCostSol > 0.0 && t.entryCostSol.isFinite() -> t.entryCostSol
            t.sol > 0.0 && t.sol.isFinite() -> t.sol
            else -> 0.0
        }
        val entryPrice = when {
            t.entryPriceSnapshot > 0.0 && t.entryPriceSnapshot.isFinite() -> t.entryPriceSnapshot
            t.price > 0.0 && t.price.isFinite() -> t.price
            else -> 0.0
        }
        return entrySol > 0.0 && entryPrice > 0.0 && t.mint.isNotBlank()
    }

    // V5.0.4502 — forensic money contract. Strategy truth must not count
    // large live PnL rows unless price, SOL basis, and wallet/proof finality line
    // up. Raw rows stay in the journal; this only prevents unaudited money from
    // becoming strategy-clean PnL/WR and poisoning decisions.
    private fun forensicRejectReason(t: Trade): String? {
        val side = t.side.trim().uppercase()
        if (side != "SELL" && side != "PARTIAL_SELL") return null
        val terminalReason = t.reason.trim().uppercase()
        if (terminalReason.contains("STALE_FEED") || terminalReason.contains("DATA_QUALITY") ||
            terminalReason.contains("STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP")) {
            return "DATA_QUALITY_EXIT"
        }
        val mode = t.mode.trim().uppercase()
        val live = mode == "LIVE"
        val proof = t.proofState.trim().uppercase()
        val basis = t.entryCostSol.takeIf { it.isFinite() && it > 0.0 } ?: return "MISSING_ENTRY_COST_BASIS"
        if (mode == "PAPER" && t.economicEventId.isNotBlank()) {
            if (!t.soldCostBasisSol.isFinite() || t.soldCostBasisSol <= 0.0) return "MISSING_CANONICAL_SOLD_BASIS"
            if (!t.grossProceedsSol.isFinite() || t.grossProceedsSol < 0.0) return "MISSING_CANONICAL_GROSS_PROCEEDS"
            if (t.canonicalConsumedRaw <= java.math.BigInteger.ZERO) return "MISSING_CANONICAL_CONSUMED_RAW"
        }
        val realized = when {
            t.netPnlSol.isFinite() && t.netPnlSol != 0.0 -> t.netPnlSol
            t.pnlSol.isFinite() -> t.pnlSol
            else -> return "PNL_SOL_NAN"
        }
        val proceeds = basis + realized
        if (!proceeds.isFinite() || proceeds < -0.000001) return "NEGATIVE_PROCEEDS"
        val pctFromSol = (realized / basis) * 100.0
        if (!pctFromSol.isFinite() || kotlin.math.abs(pctFromSol - t.pnlPct) > 50.0) return "PNL_SOL_PERCENT_MISMATCH"
        if (live && proof.isBlank()) return "MISSING_LIVE_PROOF"
        val largePnl = kotlin.math.abs(realized) >= 0.25 || kotlin.math.abs(t.pnlPct) >= 1000.0
        val walletFinal = proof.contains("FINAL") || proof.contains("BALANCE") || proof.contains("TX_PARSE") || proof.contains("OWNER_DELTA")
        if (live && largePnl && !walletFinal) return "LARGE_PNL_NOT_WALLET_FINAL"
        return null
    }

    fun strategyLaneFor(t: Trade): String = if (isRecoveryInventory(t)) {
        "RECOVERY_INVENTORY"
    } else try {
        TradeHistoryStore.normalizeTradeModeName(t.tradingMode).ifBlank { "STANDARD" }
    } catch (_: Throwable) {
        t.tradingMode.ifBlank { "STANDARD" }.uppercase()
    }

    private fun normalizedStrategyRow(t: Trade): Trade {
        val lane = strategyLaneFor(t)
        return if (lane != t.tradingMode) t.copy(tradingMode = lane) else t
    }

    private fun terminalKey(t: Trade): String {
        val mode = t.mode.ifBlank { "unknown" }.uppercase()
        val mint = t.mint.ifBlank { "unknown" }
        val buySig = t.positionId.ifBlank { "pos:${t.entryTsMs.takeIf { it > 0L } ?: t.ts}" }
        val sellSig = t.sig.ifBlank { "" }
        return if (sellSig.isNotBlank()) {
            "$mode|$mint|$buySig|$sellSig"
        } else {
            val bucket = terminalCloseTimeBucket(t.ts)
            "$mode|$mint|$buySig|${t.reason.take(48)}|$bucket"
        }
    }

    private fun generationKey(t: Trade): String {
        val mode = t.mode.ifBlank { "unknown" }.uppercase()
        val mint = t.mint.ifBlank { "unknown" }
        val pos = t.positionId.ifBlank { "entry:${t.entryTsMs.takeIf { it > 0L } ?: t.ts}" }
        return "$mode|$mint|$pos"
    }

    private const val SAME_MINT_TERMINAL_DEDUP_WINDOW_MS = 5L * 60_000L

    private fun mintCloseWindowKey(t: Trade): String {
        val mode = t.mode.ifBlank { "unknown" }.uppercase()
        val mint = t.mint.ifBlank { "unknown" }
        return "$mode|$mint"
    }

    private fun terminalCloseTimeBucket(ts: Long): Long = if (ts > 0L) ts / 60_000L else 0L

    private fun inc(label: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    fun auditLine(limit: Int = 500): String = try {
        // V5.0.6378 — cap default 2500 → 500. auditLine is called from the
        // pipeline dump builder; at 2500 rows each call does a synchronized
        // journal read + O(N log N) sort + dedupe pass which pushed the full
        // report builder past the 20s watchdog (operator's 6308-format
        // emergency report). 500 rows still covers the strategy learning
        // window and lets the cache hit rate climb further.
        val raw = TradeHistoryStore.getRecentValidClosedTradesRaw(limit = limit, includePartials = true)
        val result = clean(raw, limit)
        val inv = inventoryRecoveryRows(raw)
        val invPnl = inv.sumOf { it.netPnlSol.takeIf { v -> abs(v) > 0.0 } ?: it.pnlSol }
        "StrategyTruthLedger: clean=${result.audit.cleaned} deduped=${result.audit.deduped} recovered=${result.audit.recoveryExcluded} partialNonTerminal=${result.audit.partialNotTerminal} badEntry=${result.audit.badEntryExcluded} inventory=${inv.size} inventoryPnl=${"%+.4f".format(invPnl)}"
    } catch (_: Throwable) { "StrategyTruthLedger: unavailable" }
}
