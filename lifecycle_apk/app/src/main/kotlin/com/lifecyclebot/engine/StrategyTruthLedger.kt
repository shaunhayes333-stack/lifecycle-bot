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

    // V5.0.6358 — TTL cache in front of clean(). Operator's V5.0.6308
    // emergency dump showed STRATEGY_CLEAN_TERMINAL_ROWS = 624,180 in
    // 3243s uptime (~192/sec). Each call sorts/iterates all raw rows
    // and dedup-tests every one. Two callers (LiveProbabilityEngine,
    // leaderboard) hit this per lane_eval; a third-party learning
    // aggregator loops through it too. Adding a 3s TTL cache keyed by
    // (rawRows.size | newest ts | limit) makes the second and third
    // reader in a short window return the same Result instance instead
    // of redoing 200-row terminal-dedup work, without breaking
    // correctness — the cache invalidates as soon as a new SELL row
    // lands in the journal.
    // V5.0.6378 — bump TTL 3s→10s. Operator's V5.0.6308-format emergency dump
    // showed STRATEGY_CLEAN_TERMINAL_ROWS = 433,607 in 40 min with cache
    // hits=1326 / misses=1104 (only ~55% hit rate). The cache fingerprint
    // (rawRows.size | newestTs | limit) already invalidates on every new SELL
    // row landing, so extending the TTL to 10s cannot serve stale data — it
    // only prevents the second-and-third readers in the SAME 10-second window
    // (LiveProbabilityEngine + leaderboard + strategy aggregator) from
    // redoing the O(N log N) sort + dedupe pass 3× per journal state.
    // V5.0.7164 — bound on the unreconcilable-row forensic sample. The
    // mismatch fires thousands of times a session; twelve full value vectors
    // are enough to name the cause and will not flood the log.
    private val unreconciledSamples7164 = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * V5.0.7171 — the samples were written where nobody could read them.
     *
     * 7164 logged twelve full value vectors for the unreconcilable rows and
     * the operator's next three snapshots all carried
     * PNL_PCT_UNRECONCILABLE_SAMPLE_7164=12 — the counter proving they were
     * written — with no way to see one. ForensicLogger goes to logcat; the
     * pipeline snapshot is what actually gets read, and this is the one
     * diagnosis that cannot be made from a count. Keep the same twelve in
     * memory and print them where the exclusion is reported.
     */
    private val unreconciledVectors7171 = java.util.concurrent.CopyOnWriteArrayList<String>()

    private const val CLEAN_CACHE_TTL_MS: Long = 10_000L
    private val cleanCacheLock = Any()
    // V5.0.7319 — one slot per input, not one slot for everyone. Callers pass
    // different journals (live/paper/all) and limits; a single slot made them
    // evict each other (2,066 misses in 10 minutes, each re-scoring every row:
    // PNL_PCT_RECONCILED_ON_SOLD_COST_7164 = 684,453). Bounded at 16 keys.
    private class CleanCacheEntry7319(val value: Result, val stampMs: Long)
    private val cleanCache7319 = LinkedHashMap<String, CleanCacheEntry7319>()

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

        // V5.0.6378 — TTL cache check. Fingerprint the input by size, newest
        // row timestamp and the requested limit; if the fingerprint matches
        // a fresh cache stamp, return the cached Result. Never blocks or
        // fails hot path — synchronized block is O(1) and the fallback
        // (cache miss) is identical to the pre-6358 behaviour.
        //
        // V5.0.6379 — CACHE BUCKETING to survive rapid-fire new-row churn.
        // Operator's second V5.0.6308-format emergency dump showed the
        // fingerprint invalidating on EVERY new SELL row landing:
        //   STRATEGY_CLEAN_TERMINAL_ROWS = 2,099,412 in 3.5h uptime
        //   MISS=4015 / HIT=3216  (55% miss rate)
        // With ~2M output-row emissions and only 55% hit rate the cache
        // was doing nothing because `newestTs` bumps on every incoming row.
        // Bucket the fingerprint to `size / 10` and `newestTs / 30_000`
        // (30-second buckets) so back-to-back callers within the SAME
        // 10-row batch and 30-second window all hit the same cache slot.
        // Correctness envelope: outputs may lag a real journal by at most
        // 10 rows OR 30s (whichever comes first) — well inside the tolerances
        // strategy learning already runs at (TRIAL_WINDOW=25, PERSIST=40).
        val now = System.currentTimeMillis()
        val newestTs = rawRows.firstOrNull()?.ts ?: 0L
        val oldestTs7319 = rawRows.lastOrNull()?.ts ?: 0L
        val key = "${rawRows.size / 10}|${newestTs / 30_000}|$limit|$oldestTs7319"
        val cached = synchronized(cleanCacheLock) {
            cleanCache7319[key]?.takeIf { now - it.stampMs < CLEAN_CACHE_TTL_MS }?.value
        }
        if (cached != null) {
            try { PipelineHealthCollector.labelInc("STRATEGY_CLEAN_CACHE_HIT_6358") } catch (_: Throwable) {}
            return cached
        }
        try { PipelineHealthCollector.labelInc("STRATEGY_CLEAN_CACHE_MISS_6358") } catch (_: Throwable) {}

        val newestFirst = rawRows.sortedByDescending { it.ts }
        val partialsByPosition7333 = partialLegsByPosition7333(newestFirst)
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
            val forensicReject = forensicVerdictOnce7344(row)
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
                        positionId = null, mint = row.mint,
                    )
                } catch (_: Throwable) { false }
                if (historicalQuarantined) {
                    forensic++
                    inc("STRATEGY_HISTORICAL_QUARANTINED_6501")
                    continue
                }
                // V5.0.7274 — the third leg of the 6504 purity gate. 6501 wired
                // the quantity-invariant and historical quarantines into this
                // filter; the gate's own untrusted set (marked by the 7271 gain
                // door, the 6692 stale-quote exit and the 7274 dead-token door)
                // was read by the reward bridge and the paper eligibility gate
                // but not here, so a mint every learner on the finalized bus
                // refused still counted in the leaderboard the damper, the
                // regime and the admission floor read.
                val economicUntrusted7274 = try {
                    com.lifecyclebot.engine.truth.EconomicPurityGate6504.shouldExcludeFromAnalytics(row.mint)
                } catch (_: Throwable) { false }
                if (economicUntrusted7274) {
                    forensic++
                    inc("STRATEGY_ECONOMIC_UNTRUSTED_EXCLUDED_7274")
                    continue
                }
            }

            val terminalKey = terminalKey(row)
            val generationKey = generationKey(row)
            val mintWindowKey = mintCloseWindowKey(row)
            val priorCloseTs = seenMintCloseWindows[mintWindowKey]
            val sameMintCloseDuplicate = priorCloseTs != null && row.ts > 0L && kotlin.math.abs(priorCloseTs - row.ts) <= SAME_MINT_TERMINAL_DEDUP_WINDOW_MS
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
            out += foldPartialLegs7333(normalizedStrategyRow(row), partialsByPosition7333)
        }
        val result = Result(out, Audit(out.size, deduped, recovery, partial, badEntry, forensic))
        // V5.0.6358 — publish to cache. Overwrite is unconditional under lock
        // so races produce identical Result contents for the same key.
        synchronized(cleanCacheLock) {
            cleanCache7319[key] = CleanCacheEntry7319(result, System.currentTimeMillis())
            if (cleanCache7319.size > 16) {
                val stale = cleanCache7319.keys.first()
                cleanCache7319.remove(stale)
            }
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
    /**
     * V5.0.7344 §A_JOURNAL_ROW_IS_JUDGED_ONCE.
     *
     * Operator: "we do this a lot. there's an extreme amount of data wastage."
     * The forensic verdict is a pure function of the row, and a journal row
     * never changes after it is written (an in-place repair changes its
     * economics, and so its key below). clean() still re-judged every row on
     * every cache miss: PNL_PCT_RECONCILED_ON_SOLD_COST_7164 = 571,693 in 30
     * minutes against ~400 closes — each row judged ~1,400 times, with a label
     * string built and counted each time. The verdict is now kept per row, so a
     * row costs one judgement for its lifetime and the 7164 counters count rows,
     * not repetitions. Bounded LRU; an evicted row is simply judged again.
     */
    private const val FORENSIC_VERDICT_CAP_7344 = 8_192
    private const val VERDICT_CLEAN_7344 = "\u0000CLEAN"
    private val forensicVerdicts7344 = object : java.util.LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > FORENSIC_VERDICT_CAP_7344
    }
    private val forensicVerdictHits7344 = java.util.concurrent.atomic.AtomicLong(0L)

    private fun forensicVerdictKey7344(t: Trade): String =
        "${t.mode}|${t.positionId}|${t.sig}|${t.ts}|${t.side}|${t.reason}|${t.sol}|${t.pnlSol}|${t.netPnlSol}|" +
            "${t.pnlPct}|${t.feeSol}|${t.entryCostSol}|${t.soldCostBasisSol}|${t.grossProceedsSol}|${t.proofState}|${t.economicEventId}"

    private fun forensicVerdictOnce7344(t: Trade): String? {
        val key = forensicVerdictKey7344(t)
        val known = synchronized(forensicVerdicts7344) { forensicVerdicts7344[key] }
        if (known != null) {
            forensicVerdictHits7344.incrementAndGet()
            return if (known == VERDICT_CLEAN_7344) null else known
        }
        val verdict = forensicRejectReason(t)
        synchronized(forensicVerdicts7344) { forensicVerdicts7344[key] = verdict ?: VERDICT_CLEAN_7344 }
        return verdict
    }

    private fun forensicRejectReason(t: Trade): String? {
        val side = t.side.trim().uppercase()
        if (side != "SELL" && side != "PARTIAL_SELL") return null
        val terminalReason = t.reason.trim().uppercase()
        if (terminalReason.contains("STALE_FEED") || terminalReason.contains("DATA_QUALITY")) {
            return "DATA_QUALITY_EXIT"
        }
        val mode = t.mode.trim().uppercase()
        val live = mode == "LIVE"
        // V5.0.7274 — a paper DEAD_TOKEN_NO_PRICE_EXIT is a fill booked at the
        // entry price because no feed was consulted or none answered for
        // fifteen minutes (Executor V5.9.723). Its P&L is the fee model, not the
        // market; the token's real outcome is unknown. A live close under that
        // reason is a real market sell and stays. AdaptiveLearningEngine has
        // skipped this reason since V5.9.723; the clean leaderboard — which
        // LaneExpectancyDamper, RegimeDetector and the 7266 floor read — did
        // not, and on 5.0.7273 fifteen such closes were a third of the losses
        // that raised every lane's admission floor.
        if (!live && terminalReason.contains("DEAD_TOKEN_NO_PRICE")) {
            return "UNOBSERVED_PAPER_FILL_7274"
        }
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
        // V5.0.7158 §THE NUMERATOR AND THE DENOMINATOR DESCRIBED DIFFERENT
        // POPULATIONS. This comparison excludes more closes from strategy
        // learning than any other rule in this file — 978 on the operator's
        // 5.0.7155 against clean=147, 1,920 on 5.0.7161 — so which cost basis
        // it divides by decides what the learners are allowed to see. 7158
        // routed it to soldCostBasisSol and instrumented the split to find
        // out whether that was right. It was not; see below.
        //
        // V5.0.7164 §THE 7158 SPLIT COUNTER SAID THE 7158 FIX WAS WRONG.
        //
        // 7158 assumed the mismatch was partials divided by the whole
        // position's cost, and routed them to soldCostBasisSol. The operator's
        // 5.0.7161 snapshot answered:
        //
        //   PNL_PCT_MISMATCH_ON_FULL_BASIS_7158 : 1920
        //   PNL_PCT_MISMATCH_ON_SOLD_BASIS_7158 : (absent)
        //   STRATEGY_FORENSIC_EXCLUDED_PNL_SOL_PERCENT_MISMATCH : 1920
        //
        // Every single exclusion took the FULL-basis path, which is to say
        // soldCostBasisSol was never populated on any of them and the new
        // denominator never once engaged. The partial hypothesis is dead.
        //
        // What the code actually says, read rather than assumed:
        // Executor:3998 sets a PARTIAL_SELL row's percentage from
        // `tradeWithMint.sol` — "partial SELL rows store the sold-leg cost in
        // sol" — while soldCostBasisSol is only validated for canonical PAPER
        // rows at :286 and is plain 0.0 everywhere else. So the writer
        // divides by `sol` and this reader divides by entryCostSol, and the
        // two disagree by exactly the fraction of the position that was sold.
        //
        // Rather than guess a third denominator: a row is coherent if ANY of
        // the three cost bases its own writers use reproduces the reported
        // percentage. That is a closed list read off the emitters, not a
        // loosened tolerance — a genuinely corrupt row still reconciles
        // against none of them and is still excluded, now with its numbers
        // written down instead of a bare counter.
        val bases7164 = listOf(
            "SOLD_COST" to t.soldCostBasisSol,
            "FULL_ENTRY" to basis,
            "SOLD_LEG_SOL" to t.sol,
        )
        // V5.0.7177 §I_ENUMERATED_THE_DENOMINATORS_AND_FORGOT_THE_NUMERATOR.
        //
        // 7164 built a closed list of the three cost bases the writers use and
        // divided `realized` by each. Every one of those divides the SAME
        // numerator — realized, which is NET of fees — while the emitter
        // computes pnlPct GROSS of fees. So the whole list could only ever
        // reconcile rows whose fee was small enough to hide inside the 50pp
        // tolerance, and it systematically failed the biggest winners, because
        // the fee scales with proceeds while the basis does not.
        //
        // Operator 5.0.7176 prints the proof in its own sample line:
        //
        //   reportedPct = 853.1421   fullPct = 783.5685
        //   realized = 0.48267820    feeSol = 0.04285732   entryCost = 0.06160000
        //
        //   (realized + fee) / entryCost = 0.52553552 / 0.0616 = 853.14%  <- exact
        //
        // Not a rounding drift: it reproduces the reported number to four
        // decimals. 5,473 rows were excluded from the strategy ledger as
        // PNL_SOL_PERCENT_MISMATCH for being correct, and because the gap
        // grows with the size of the win, the exclusion was biased against
        // winners — the learners were being fed a book with its best trades
        // filtered out.
        //
        // The numerator is enumerated the same way the denominators were: off
        // the emitters, as a closed list, with the tolerance untouched. A
        // genuinely corrupt row still matches neither numerator against any
        // basis and is still excluded.
        val grossRealized7177 = realized + (if (t.feeSol.isFinite()) t.feeSol else 0.0)
        val numerators7177 = listOf("" to realized, "_GROSS" to grossRealized7177)
        var reconciled7164: String? = null
        outer7177@ for ((basisName, b) in bases7164) {
            if (!b.isFinite() || b <= 0.0) continue
            for ((numSuffix, n) in numerators7177) {
                val pct = (n / b) * 100.0
                if (pct.isFinite() && kotlin.math.abs(pct - t.pnlPct) <= 50.0) {
                    reconciled7164 = basisName + numSuffix
                    break@outer7177
                }
            }
        }
        if (reconciled7164 == null) {
            val pctFull7164 = if (basis > 0.0) (realized / basis) * 100.0 else Double.NaN
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PNL_PCT_UNRECONCILABLE_7164")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    when {
                        t.pnlPct == 0.0 -> "PNL_PCT_UNRECONCILABLE_REPORTED_ZERO_7164"
                        !pctFull7164.isFinite() -> "PNL_PCT_UNRECONCILABLE_NO_BASIS_7164"
                        pctFull7164 * t.pnlPct < 0.0 -> "PNL_PCT_UNRECONCILABLE_SIGN_FLIP_7164"
                        else -> "PNL_PCT_UNRECONCILABLE_MAGNITUDE_7164"
                    },
                )
                // Bounded forensic sample. A counter told us the 7158 fix
                // missed; only the values can say why the next one would.
                if (unreconciledSamples7164.getAndIncrement() < 12) {
                    val vector7171 =
                        "mint=${t.mint.take(10)} side=$side mode=$mode reason=${t.reason.take(40)} " +
                            "reportedPct=${"%.4f".format(t.pnlPct)} fullPct=${"%.4f".format(pctFull7164)} " +
                            "realized=${"%.8f".format(realized)} pnlSol=${"%.8f".format(t.pnlSol)} " +
                            "netPnlSol=${"%.8f".format(t.netPnlSol)} feeSol=${"%.8f".format(t.feeSol)} " +
                            "entryCost=${"%.8f".format(basis)} soldCost=${"%.8f".format(t.soldCostBasisSol)} " +
                            "sol=${"%.8f".format(t.sol)} gross=${"%.8f".format(t.grossProceedsSol)} " +
                            "soldQty=${"%.6f".format(t.soldQtyToken)}"
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "PNL_PCT_UNRECONCILABLE_SAMPLE_7164", vector7171,
                    )
                    // V5.0.7171 — and keep it where the snapshot can print it.
                    if (unreconciledVectors7171.size < 12) unreconciledVectors7171.add(vector7171)
                }
            } catch (_: Throwable) {}
            return "PNL_SOL_PERCENT_MISMATCH"
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PNL_PCT_RECONCILED_ON_${reconciled7164}_7164")
        } catch (_: Throwable) {}
        if (live && proof.isBlank()) return "MISSING_LIVE_PROOF"
        val largePnl = kotlin.math.abs(realized) >= 0.25 || kotlin.math.abs(t.pnlPct) >= 1000.0
        val walletFinal = proof.contains("FINAL") || proof.contains("BALANCE") || proof.contains("TX_PARSE") || proof.contains("OWNER_DELTA")
        if (live && largePnl && !walletFinal) return "LARGE_PNL_NOT_WALLET_FINAL"
        return null
    }

    // V5.0.7349b §A_RUNNER_IS_CREDITED_TO_THE_LANE_THAT_BOUGHT_IT.
    //
    // The terminal row carries the lane at CLOSE. HoldingLogicLayer
    // (LONG_HOLD / DIAMOND_HANDS) and LaneTransitionManager rewrite a position's
    // lane mid-hold, so a MOONSHOT that ran was credited to whichever lane held
    // it last (5.0.7347: promotionLeaks=119). Lane expectancy is a verdict on the
    // lane's ENTRY decision, so the entry lane recorded at open wins when it is
    // known; restored positions from an earlier process fall back to the row.
    fun strategyLaneFor(t: Trade): String = if (isRecoveryInventory(t)) {
        "RECOVERY_INVENTORY"
    } else try {
        val entryLane7349 = t.positionId.takeIf { it.isNotBlank() }?.let {
            com.lifecyclebot.engine.truth.LaneAttributionLedger6427.getEntryLane(it)
        }?.takeIf { it.isNotBlank() }
        TradeHistoryStore.normalizeTradeModeName(entryLane7349 ?: t.tradingMode).ifBlank { "STANDARD" }
    } catch (_: Throwable) {
        t.tradingMode.ifBlank { "STANDARD" }.uppercase()
    }

    private class PartialLegs7333(var pnlSol: Double = 0.0, var costSol: Double = 0.0, var n: Int = 0)

    /**
     * V5.0.7333 — the terminal SELL row prices only the RUNNER leg (its cost is
     * the remaining cost after partials). A position that banked +40% on its
     * rungs and stopped the runner at -8% was a LOSS to every learner that
     * reads this ledger (5.0.7324: blended WR 7.9% against 37.9% per
     * position; PROJECT_SNIPER 1W/17L here, 7W/17L on the per-position book).
     * Non-terminal partial legs are summed per positionId so the terminal row
     * can carry the whole position's result. Each leg's cost is
     * proceeds - realized PnL (fees land in the cost, a conservative read).
     */
    private fun partialLegsByPosition7333(rows: List<Trade>): Map<String, PartialLegs7333> {
        val out = HashMap<String, PartialLegs7333>()
        val seen = HashSet<String>()
        for (r in rows) {
            if (!r.side.trim().equals("PARTIAL_SELL", true)) continue
            if (r.remainingQtyToken <= 0.000000001) continue
            val pid = r.positionId.trim()
            if (pid.isEmpty()) continue
            val cost = r.sol - r.pnlSol
            if (!cost.isFinite() || cost <= 0.0 || !r.pnlSol.isFinite()) continue
            if (!seen.add("$pid|${r.ts}|${r.sol}|${r.sig}")) continue
            val legs = out.getOrPut(pid) { PartialLegs7333() }
            legs.pnlSol += r.pnlSol
            legs.costSol += cost
            legs.n++
        }
        return out
    }

    private fun foldPartialLegs7333(t: Trade, legs: Map<String, PartialLegs7333>): Trade {
        val l = legs[t.positionId.trim()] ?: return t
        if (l.n <= 0) return t
        val runnerCost = t.sol - t.pnlSol
        if (!runnerCost.isFinite() || runnerCost < 0.0) return t
        val positionCost = runnerCost + l.costSol
        if (positionCost <= 0.0) return t
        val positionPnl = t.pnlSol + l.pnlSol
        inc("STRATEGY_TERMINAL_FOLDED_PARTIALS_7333")
        return t.copy(
            pnlSol = positionPnl,
            netPnlSol = positionPnl,
            pnlPct = positionPnl * 100.0 / positionCost,
            entryCostSol = positionCost,
        )
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
        val head7171 =
            "StrategyTruthLedger: clean=${result.audit.cleaned} deduped=${result.audit.deduped} recovered=${result.audit.recoveryExcluded} partialNonTerminal=${result.audit.partialNotTerminal} badEntry=${result.audit.badEntryExcluded} inventory=${inv.size} inventoryPnl=${"%+.4f".format(invPnl)} rowsJudgedOnce7344=${synchronized(forensicVerdicts7344) { forensicVerdicts7344.size }} verdictReuse7344=${forensicVerdictHits7344.get()} ${TradeHistoryStore.validRowsStatus7346()}"
        // V5.0.7171 — print the rows the percentage check threw out. This is
        // the largest single exclusion in the file and the only one whose
        // cause cannot be read off a counter.
        val vectors7171 = unreconciledVectors7171.toList()
        if (vectors7171.isEmpty()) head7171
        else head7171 + "\n  PNL_PCT_UNRECONCILABLE_7164 samples (first ${vectors7171.size}):\n" +
            vectors7171.joinToString("\n") { "    $it" }
    } catch (_: Throwable) { "StrategyTruthLedger: unavailable" }
}
