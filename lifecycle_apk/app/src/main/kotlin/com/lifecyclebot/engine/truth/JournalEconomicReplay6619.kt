package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.data.Trade
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6619 §JOURNAL_DERIVED_HERO_AUTHORITY.
 * Durable paper journal replay is the economic source for paper hero surfaces.
 */
object JournalEconomicReplay6619 {

    data class ReplayResult(
        val cashSol: Double,
        val realizedPnlSol: Double,
        val openCostBasisSol: Double,
        val feesSol: Double,
        val equitySol: Double,
        val startingCashSol: Double,
        val paperRows: Int,
        val paperBuys: Int,
        val paperSells: Int,
        val paperPartialSells: Int,
        val emittedAtMs: Long,
        val reconciled: Boolean = true,
        /**
         * V5.0.6899 §RECONCILED_CONFLATED_TWO_DIFFERENT_FACTS.
         *
         * `reconciled` is `failures.isEmpty()` — it means "no event was
         * anomalous". Thirteen of the fourteen reject sites follow their
         * reject with `continue`, which skips the event's economics entirely,
         * so for those the totals really are incomplete. But V5.0.6868's
         * TERMINAL_SELL_INCOMPLETE_LOT path deliberately applies BOTH legs and
         * writes the residual off before rejecting, precisely so the totals
         * still balance while the anomaly stays visible. After 6868 the two
         * facts are separable and `reconciled` alone can no longer stand in
         * for "the numbers add up".
         *
         * That distinction was load-bearing in ways nothing made obvious.
         * ForensicReconciliation6635.allZero requires replay.reconciled, which
         * gates UnifiedAccountSnapshot6635's RECONCILED status, which gates an
         * early return at BotService:17091. Operator 5.0.6892 shows that early
         * return firing on every single cycle —
         * GROWTH_MILESTONE_BLOCKED_UNRECONCILED_OR_UNPRICED_6647=281 across
         * 281 bot cycles — and taking three systems down with it:
         *   * the growth ring never bumps           (no_ring_yet bumps=0)
         *   * AntiRewardHackingGuard6439 never arms (high24hSol=0.00000,
         *     highAgeMin=29827379 — an uninitialised timestamp reading 56
         *     years), so canExpandRisk returns true unconditionally and the
         *     guard that exists to stop learners expanding risk during a
         *     drawdown cannot veto anything
         *   * the four conservation invariants in the acceptance witness all
         *     fail together, because reconciledDelta() returns NaN whenever
         *     the reconciler is not RECONCILED
         *
         * And the numbers did add up: the same snapshot reports
         * paperReplay cashDelta=0.0000 realizedDelta=0.0000 with
         * journalOnlyCommits=0 ledgerOnlyCommits=0 duplicateJournal=0.
         *
         * `totalsComplete6899` is therefore the honest predicate for "every
         * event's economics were applied": false only when a rejection
         * actually skipped an event. `reconciled` keeps its original meaning
         * and every existing reader of it is untouched.
         */
        val totalsComplete6899: Boolean = true,
        val invariantFailures: List<String> = emptyList(),
        val openRawQtyByPosition: Map<String, java.math.BigInteger> = emptyMap(),
        /**
         * V5.0.6980 — per-position SELL economics, so a reconciler can name the
         * divergent row instead of only its scalar delta.
         *
         * The 6979 CI smoke run produced the shape that motivated this:
         *
         *     cashLedger=2.801693  cashJournal=2.856536  cashDelta=0.054842
         *     realizedLedger=-0.179132 realizedJournal=-0.124290 realizedDelta=0.054842
         *     openCostDelta=0.000000   quantityDeltaRaw=1000000000
         *
         * PaperAccountLedger6430.onSellAtomic6632 and this replay apply the
         * IDENTICAL formulas — cash += (gross - fee), openCost -= basis,
         * realized += (gross - basis), fees += fee — so the delta cannot come
         * from a formula split. With openCostDelta exactly 0 the basis flows
         * agree, and with cashDelta == realizedDelta the fee flows agree too.
         * Subtracting those leaves one possibility: the two sides applied a
         * different GROSS PROCEEDS for the same position, on the same basis.
         *
         * A scalar cannot say which one. These maps can.
         */
        val sellGrossByPosition6980: Map<String, Double> = emptyMap(),
        val sellBasisByPosition6980: Map<String, Double> = emptyMap(),
        val sellFeeByPosition6980: Map<String, Double> = emptyMap(),
        val sellCountByPosition6980: Map<String, Int> = emptyMap(),
        val openBasisByPosition: Map<String, Double> = emptyMap(),
    )

    private val replays = AtomicLong(0L)
    private val lastResult = AtomicReference<ReplayResult?>(null)
    private val ledgerDivergenceLast = AtomicReference<Double>(0.0)
    private val reportedInvariantFailures6653 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reportedEmbeddedEntryRecoveries6664 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // V5.0.6699 — replay is intentionally repeatable; telemetry for historical
    // rows must not be. Key by immutable replay identity so one old row cannot
    // create a new counter/log event every 5 seconds forever.
    private val reportedReplaySupersessions6699 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val quarantineScopeSignature7251 = AtomicReference("")

    fun replay(): ReplayResult {
        replays.incrementAndGet()
        val startingSol = try {
            PaperCapitalAuthority6577.startingCashSol().coerceAtLeast(0.0)
        } catch (_: Throwable) { 0.0 }

        val onMainThread = try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) { false }
        if (onMainThread) {
            try { PipelineHealthCollector.labelInc("JOURNAL_REPLAY_MAIN_THREAD_DEFERRED_6619") } catch (_: Throwable) {}
            val prior = lastResult.get()
            val fast = if (prior != null) {
                prior.copy(startingCashSol = startingSol, emittedAtMs = System.currentTimeMillis())
            } else {
                ReplayResult(
                    cashSol = startingSol,
                    realizedPnlSol = 0.0,
                    openCostBasisSol = 0.0,
                    feesSol = 0.0,
                    equitySol = startingSol,
                    startingCashSol = startingSol,
                    paperRows = 0, paperBuys = 0, paperSells = 0, paperPartialSells = 0,
                    emittedAtMs = System.currentTimeMillis(),
                    reconciled = false,
                    invariantFailures = listOf("MAIN_THREAD_REPLAY_DEFERRED"),
                )
            }
            lastResult.set(fast)
            return fast
        }

        data class Lot(var basisSol: Double, var rawQty: java.math.BigInteger, var displayQty: Double)
        var cash = startingSol
        var realized = 0.0
        var openCost = 0.0
        var fees = 0.0
        var buys = 0
        var sells = 0
        var partials = 0
        var totalRows = 0
        // V5.0.6980 — per-position SELL economics, so a divergence can be
        // attributed to a row rather than reported as a bare scalar.
        val sellGross6980 = mutableMapOf<String, Double>()
        val sellBasis6980 = mutableMapOf<String, Double>()
        val sellFee6980 = mutableMapOf<String, Double>()
        val sellCount6980 = mutableMapOf<String, Int>()
        // V5.0.6868 — basis that a terminal SELL left behind on its lot. Tracked so
        // the residual is a readable quantity instead of being silently carried as
        // open cost (or, before this fix, silently dropping the whole sell event).
        var residualBasisWrittenOff6868 = 0.0
        var residualLotCount6868 = 0
        // V5.0.7078 — the SOL that a skipped event took with it, per reason.
        //
        // V5.0.6899 added `skippedEvents6899`, a COUNT. A count cannot be
        // compared against a SOL delta, so when ForensicReconciliation6635
        // reported `ledger realized 15.1088 vs journal 7.8393 delta 7.2695`
        // there was no way to tell whether that 7.2695 was a real hole or the
        // arithmetic of events this replay refused to apply. These name it.
        val skippedCashByReason7078 = mutableMapOf<String, Double>()
        val skippedRealizedByReason7078 = mutableMapOf<String, Double>()
        val skippedCountByReason7078 = mutableMapOf<String, Int>()

        val quarantinedPositionIds7251 = try {
            CanonicalPositionAuthority6441.quarantinedPositionIds6635("paper")
        } catch (_: Throwable) { emptySet() }
        val allRows7251 = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
        } catch (_: Throwable) { emptyList() }
        val excludedQuarantineRows7251 = allRows7251.count {
            it.mode.equals("paper", true) && it.positionId.isNotBlank() && it.positionId in quarantinedPositionIds7251
        }
        val rows = allRows7251.asSequence()
            .filterNot { it.mode.equals("paper", true) && it.positionId.isNotBlank() && it.positionId in quarantinedPositionIds7251 }
            .sortedBy { it.ts }
            .toList()
        if (excludedQuarantineRows7251 > 0) {
            val signature7251 = "${quarantinedPositionIds7251.size}:$excludedQuarantineRows7251:${quarantinedPositionIds7251.sorted().hashCode()}"
            if (quarantineScopeSignature7251.getAndSet(signature7251) != signature7251) try {
                PipelineHealthCollector.labelInc("JOURNAL_QUARANTINED_ROWS_EXCLUDED_ACTIVE_ACCOUNT_7251")
                ForensicLogger.lifecycle(
                    "JOURNAL_QUARANTINED_ROWS_EXCLUDED_ACTIVE_ACCOUNT_7251",
                    "positions=${quarantinedPositionIds7251.size} rows=$excludedQuarantineRows7251 action=retained_history_excluded_from_active_account",
                )
            } catch (_: Throwable) {}
        }

        // V5.0.6697 — a historical 6659 repair projection is superseded when
        // the same position already has its native durable BUY.
        val nativeBuyPositions6697 = rows.asSequence()
            .filter { it.mode.equals("paper", true) && it.side.equals("BUY", true) }
            .filter { it.positionId.isNotBlank() }
            .filterNot { it.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) }
            .map { it.positionId }
            .toSet()

        val lots = mutableMapOf<String, Lot>()
        val seenEvents = mutableSetOf<String>()
        val seenFills = mutableSetOf<String>()
        val failures = mutableListOf<String>()

        fun displayToRaw(value: Double, decimals: Int): java.math.BigInteger {
            if (!value.isFinite() || value <= 0.0 || decimals !in 0..18) return java.math.BigInteger.ZERO
            return try {
                java.math.BigDecimal.valueOf(value)
                    .movePointRight(decimals)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .toBigIntegerExact()
            } catch (_: Throwable) { java.math.BigInteger.ZERO }
        }

        // V5.0.6899 — `skipped` records whether this rejection also abandoned
        // the event's economics. Every site that follows reject() with
        // `continue` leaves the totals short by that event and keeps the
        // default; the 6868 residual path applies both legs first and passes
        // skipped = false.
        var skippedEvents6899 = 0
        fun reject(t: com.lifecyclebot.data.Trade, eventId: String, reason: String, skipped: Boolean = true) {
            val identity = "$eventId:$reason"
            failures += identity
            if (skipped) {
                skippedEvents6899 += 1
                // V5.0.7078 — a skipped SELL is a credit this replay declined
                // to apply while the matching BUY's debit was already applied
                // earlier in the same walk. That asymmetry IS the ledger/journal
                // delta, so it is measured in the same units the delta is
                // reported in rather than left as a count to be guessed at.
                try {
                    val sideUp = t.side.uppercase()
                    if (sideUp == "SELL" || sideUp == "PARTIAL_SELL") {
                        val g = t.grossProceedsSol.takeIf { it.isFinite() && it > 0.0 } ?: t.sol
                        val b = t.soldCostBasisSol
                        val f = t.feeSol
                        if (g.isFinite() && g > 0.0) {
                            skippedCashByReason7078[reason] =
                                (skippedCashByReason7078[reason] ?: 0.0) + (g - (if (f.isFinite()) f else 0.0))
                            if (b.isFinite()) {
                                skippedRealizedByReason7078[reason] =
                                    (skippedRealizedByReason7078[reason] ?: 0.0) + (g - b)
                            }
                        }
                    }
                    skippedCountByReason7078[reason] = (skippedCountByReason7078[reason] ?: 0) + 1
                } catch (_: Throwable) {}
            }
            try {
                LearningQuarantineGate6470.quarantinePositionId("EVENT:$eventId", reason)
                if (t.positionId.isNotBlank()) LearningQuarantineGate6470.quarantinePositionId(t.positionId, "EVENT:$eventId:$reason")
                if (reportedInvariantFailures6653.add(identity)) {
                    PipelineHealthCollector.labelInc("JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647")
                    ForensicLogger.lifecycle(
                        "JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647",
                        "economicEventId=$eventId positionId=${t.positionId} side=${t.side} fillIndex=${t.partialSequence} reason=$reason action=quarantine_exact_event_once",
                    )
                }
            } catch (_: Throwable) {}
        }

        // V5.0.7085 §MY OWN INSTRUMENT HAD A BLIND SPOT WHERE THE MONEY WENT.
        //
        // V5.0.7078 tallied skipped economics from inside reject(), so it can
        // only see rows that reached the accounting body. TWO paths `continue`
        // BEFORE `totalRows++` — the 6659 CryptoAlt display-sell filter and the
        // 6697 cross-asset superseded-BUY filter — and those exclusions were
        // invisible to it. That is exactly where an unexplained remainder would
        // hide, and the operator's numbers say one is hiding:
        //
        //     canonical cash     179.360844   journal 113.282212   d 66.078632
        //     canonical realized 182.181055   journal 116.552986   d 65.628069
        //     canonical openCost                                   d  0.361095
        //     7078 skipped economics: 15 events, 9.212758 SOL
        //
        // Read the three deltas together. cashD ~= realizedD with openCostD
        // ~= 0 is the signature of positions whose BUY *and* SELL were BOTH
        // excluded: dropping the buy removes -(cost+fee), dropping the sell
        // removes +(gross-fee), so journal cash is short by the PnL and
        // openCost is left untouched. If only sells were dropped, openCostD
        // would carry their whole basis — and it is 0.36, not 66.
        //
        // Directive §6: "No event may simply be skipped." So every pre-accounting
        // exclusion now reports its economics in the same units as the delta,
        // signed the way the replay would have applied them. This does not change
        // the projection — it is the measurement that says whether these filters
        // account for the gap or whether something else does.
        fun noteExcluded7085(t: com.lifecyclebot.data.Trade, side: String, reason: String) {
            try {
                skippedCountByReason7078[reason] = (skippedCountByReason7078[reason] ?: 0) + 1
                val fee = if (t.feeSol.isFinite() && t.feeSol >= 0.0) t.feeSol else 0.0
                if (side == "BUY") {
                    val cost = t.sol
                    if (cost.isFinite() && cost > 0.0) {
                        // The buy would have DEBITED cash, so its absence leaves
                        // journal cash HIGH by that amount. Signed accordingly so
                        // the totals can be added rather than interpreted.
                        skippedCashByReason7078[reason] =
                            (skippedCashByReason7078[reason] ?: 0.0) - (cost + fee)
                    }
                } else {
                    val gross = t.grossProceedsSol.takeIf { it.isFinite() && it > 0.0 } ?: t.sol
                    val basis = t.soldCostBasisSol
                    if (gross.isFinite() && gross > 0.0) {
                        skippedCashByReason7078[reason] =
                            (skippedCashByReason7078[reason] ?: 0.0) + (gross - fee)
                        if (basis.isFinite()) {
                            skippedRealizedByReason7078[reason] =
                                (skippedRealizedByReason7078[reason] ?: 0.0) + (gross - basis)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        for (t in rows) {
            if (!t.mode.equals("paper", ignoreCase = true)) continue
            val side = t.side.uppercase()
            val eventId = t.economicEventId.ifBlank {
                "LEGACY:${t.positionId}:${t.ts}:$side:${t.partialSequence}"
            }

            // V5.0.6659 — display-only CryptoAlt terminal duplicates are ignored.
            if (t.economicEventId.isBlank() &&
                t.tradingMode.contains("CryptoAlt", ignoreCase = true) &&
                (side == "SELL" || side == "PARTIAL_SELL")
            ) {
                if (reportedReplaySupersessions6699.add("CRYPTO_DISPLAY:$eventId")) {
                    try { PipelineHealthCollector.labelInc("CRYPTO_LEGACY_DISPLAY_ROW_SUPERSEDED_6659") } catch (_: Throwable) {}
                }
                // V5.0.7085 — counted on EVERY replay, not once per event id.
                // The supersession LOG is deduplicated (6699, correctly — a
                // historical row must not emit forever), but the ECONOMICS are a
                // property of this pass and have to be totalled on this pass or
                // they cannot be compared against this pass's delta.
                noteExcluded7085(t, side, "PRE_ACCOUNTING_CRYPTOALT_DISPLAY_SELL_6659")
                continue
            }

            if (side == "BUY" &&
                t.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) &&
                t.positionId in nativeBuyPositions6697
            ) {
                // V5.0.6699 — the row remains economically superseded on every
                // replay, but the INCIDENT is historical and immutable. Emit it
                // once per event instead of hundreds of thousands of times.
                if (reportedReplaySupersessions6699.add("CROSS_ASSET:$eventId")) {
                    try {
                        PipelineHealthCollector.labelInc("JOURNAL_CROSS_ASSET_OPEN_SUPERSEDED_6697")
                        ForensicLogger.lifecycle(
                            "JOURNAL_CROSS_ASSET_OPEN_SUPERSEDED_6697",
                            "positionId=${t.positionId.take(24)} mint=${t.mint.take(10)} eventId=${eventId.take(40)} action=ignore_repair_projection_native_buy_exists_once_6699",
                        )
                    } catch (_: Throwable) {}
                }
                // V5.0.7085 — the other pre-accounting exclusion. This one drops
                // a BUY, so its absence leaves journal cash HIGH by cost+fee and
                // leaves openCost LOW by cost. If a position's buy is excluded
                // here and its sell is excluded by the 6659 filter above, the two
                // cancel in openCost and leave journal cash short by exactly the
                // position's PnL — which is the shape of the operator's numbers.
                noteExcluded7085(t, side, "PRE_ACCOUNTING_CROSS_ASSET_SUPERSEDED_BUY_6697")
                continue
            }

            totalRows++
            if (t.positionId.isBlank()) { reject(t, eventId, "MISSING_POSITION_ID"); continue }
            if (!seenEvents.add(eventId)) { reject(t, eventId, "DUPLICATE_EVENT_ID"); continue }
            val fillKey = if (side == "BUY" || side == "QTY_RECONCILE") eventId
                else "${t.positionId}:$side:${t.partialSequence}"
            if (!seenFills.add(fillKey)) { reject(t, eventId, "DUPLICATE_FILL_INDEX"); continue }

            when {
                side == "QTY_RECONCILE" -> {
                    val lot = lots[t.positionId]
                    if (lot == null) { reject(t, eventId, "QTY_RECONCILE_WITHOUT_LOT"); continue }
                    val subtract = t.canonicalConsumedRaw.coerceAtLeast(java.math.BigInteger.ZERO)
                    val add = t.entryRawQty.coerceAtLeast(java.math.BigInteger.ZERO)
                    if ((subtract == java.math.BigInteger.ZERO) == (add == java.math.BigInteger.ZERO)) {
                        reject(t, eventId, "QTY_RECONCILE_DIRECTION_INVALID"); continue
                    }
                    val nextRaw = lot.rawQty - subtract + add
                    if (nextRaw < java.math.BigInteger.ZERO) {
                        reject(t, eventId, "QTY_RECONCILE_NEGATIVE_LOT"); continue
                    }
                    lot.rawQty = nextRaw
                    try { PipelineHealthCollector.labelInc("JOURNAL_QTY_RECONCILED_TO_CANONICAL_6666") } catch (_: Throwable) {}
                }

                side == "BUY" -> {
                    val cost = t.sol
                    val fee = t.feeSol
                    if (!cost.isFinite() || cost <= 0.0 || !fee.isFinite() || fee < 0.0) {
                        reject(t, eventId, "INVALID_BUY_BASIS_OR_FEE"); continue
                    }
                    val raw = t.entryRawQty.takeIf { it > java.math.BigInteger.ZERO }
                        ?: displayToRaw(t.entryQtyToken, t.tokenDecimals.takeIf { it >= 0 } ?: t.entryDecimals)
                    val display = t.entryQtyToken.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val prior = lots[t.positionId]
                    if (prior == null) lots[t.positionId] = Lot(cost, raw, display)
                    else { prior.basisSol += cost; prior.rawQty += raw; prior.displayQty += display }
                    cash -= (cost + fee)
                    openCost += cost
                    fees += fee
                    buys++
                }

                side == "SELL" || side == "PARTIAL_SELL" -> {
                    val gross = if (t.economicEventId.isNotBlank()) t.grossProceedsSol
                        else t.grossProceedsSol.takeIf { it.isFinite() && it > 0.0 } ?: t.sol
                    val basis = t.soldCostBasisSol
                    val fee = t.feeSol
                    var lot = lots[t.positionId]

                    // V5.0.7078 §THE RECOVERY COVERED TERMINALS AND LEFT THE
                    // PARTIALS TO FALL THROUGH.
                    //
                    // V5.0.6664 built this so a terminal SELL whose BUY predates
                    // the journal window could rebuild its entry lot from its own
                    // receipt instead of being rejected as
                    // SELL_WITHOUT_MATCHING_BUY_LOT — a rejection that drops the
                    // sell's credit after the walk has already applied every
                    // matching buy's debit. It was gated to `side == "SELL"` and
                    // the `paper_full_` event prefix, so a PARTIAL_SELL in the
                    // same position hit `reject(); continue` and took its
                    // proceeds out of the journal totals.
                    //
                    // That is the shape of the operator's residual split: the
                    // 5.0.7072 device reconciles perfectly under the canonical
                    // replay (cashΔ 0.0000, realizedΔ 0.0000, qtyMismatch 0)
                    // while this walk reports ledger 19.2765 vs journal 11.9567.
                    // The two deltas differ by 0.0503 — cash misses gross-fee and
                    // realized misses gross-basis, so their difference is the
                    // skipped basis, which is the signature of dropped SELL
                    // credits and not of a cash hole.
                    //
                    // A partial has no fixed event prefix (6510 mints
                    // `positionId:sequence`), so the prefix test is replaced for
                    // that side by the check that was doing the real work
                    // anyway: the receipt must PROVE the entry — a positive sold
                    // basis, a resolvable raw quantity and an entry price
                    // snapshot. Nothing is reconstructed from a receipt that
                    // cannot evidence it.
                    val recoverableSide7078 = (side == "SELL" && t.economicEventId.startsWith("paper_full_")) ||
                        side == "PARTIAL_SELL"
                    if (lot == null && recoverableSide7078) {
                        val recoveredRaw = t.canonicalConsumedRaw.takeIf { it > java.math.BigInteger.ZERO }
                            ?: displayToRaw(t.soldQtyToken, t.tokenDecimals.takeIf { it >= 0 } ?: t.entryDecimals)
                        val recoveredDisplay = t.soldQtyToken.takeIf { it.isFinite() && it > 0.0 }
                            ?: t.entryQtyToken.takeIf { it.isFinite() && it > 0.0 }
                            ?: 0.0
                        val receiptProvesEntry = basis.isFinite() && basis > 0.0 &&
                            recoveredRaw > java.math.BigInteger.ZERO &&
                            t.entryPriceSnapshot.isFinite() && t.entryPriceSnapshot > 0.0
                        if (receiptProvesEntry) {
                            lot = Lot(basis, recoveredRaw, recoveredDisplay)
                            lots[t.positionId] = lot
                            cash -= basis
                            openCost += basis
                            try {
                                if (reportedEmbeddedEntryRecoveries6664.add(eventId)) {
                                    PipelineHealthCollector.labelInc("JOURNAL_EMBEDDED_ENTRY_RECOVERED_6664")
                                    // V5.0.7078 — counted separately so the
                                    // effect of widening the recovery to
                                    // partials is measurable on its own, and
                                    // reversible if it is not the cause.
                                    if (side == "PARTIAL_SELL") {
                                        PipelineHealthCollector.labelInc("JOURNAL_EMBEDDED_PARTIAL_ENTRY_RECOVERED_7078")
                                    }
                                    ForensicLogger.lifecycle(
                                        "JOURNAL_EMBEDDED_ENTRY_RECOVERED_6664",
                                        "economicEventId=${eventId.take(40)} positionId=${t.positionId.take(24)} " +
                                            "mint=${t.mint.take(10)} basis=${"%.6f".format(basis)} raw=$recoveredRaw " +
                                            "action=replay_sealed_terminal_entry_then_close",
                                    )
                                }
                            } catch (_: Throwable) {}
                        }
                    }

                    if (lot == null) { reject(t, eventId, "SELL_WITHOUT_MATCHING_BUY_LOT"); continue }
                    if (!basis.isFinite() || basis <= 0.0) { reject(t, eventId, "MISSING_OR_NEGATIVE_BASIS"); continue }
                    if (basis > lot.basisSol + 1e-9) { reject(t, eventId, "BASIS_EXCEEDS_REMAINING_LOT"); continue }
                    if (!gross.isFinite() || gross < 0.0 || !fee.isFinite() || fee < 0.0) { reject(t, eventId, "INVALID_PROCEEDS_OR_FEE"); continue }
                    val soldRaw = t.canonicalConsumedRaw.takeIf { it > java.math.BigInteger.ZERO }
                        ?: displayToRaw(t.soldQtyToken, t.tokenDecimals.takeIf { it >= 0 } ?: t.entryDecimals)
                    if (soldRaw > java.math.BigInteger.ZERO && lot.rawQty > java.math.BigInteger.ZERO && soldRaw > lot.rawQty) {
                        reject(t, eventId, "SELL_QTY_EXCEEDS_REMAINING_LOT"); continue
                    }
                    val soldDisplay = t.soldQtyToken.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val nextBasis = lot.basisSol - basis
                    val nextRaw = if (soldRaw > java.math.BigInteger.ZERO) lot.rawQty - soldRaw else lot.rawQty
                    val nextDisplay = if (soldDisplay > 0.0) lot.displayQty - soldDisplay else lot.displayQty
                    if (nextBasis < -1e-9 || nextRaw < java.math.BigInteger.ZERO || nextDisplay < -1e-9) {
                        reject(t, eventId, "NEGATIVE_REMAINING_LOT"); continue
                    }
                    // V5.0.6868 §A_REPLAY_MUST_NOT_APPLY_A_DEBIT_AND_REFUSE_ITS_CREDIT —
                    // this used to `reject(...); continue` on TERMINAL_SELL_INCOMPLETE_LOT,
                    // i.e. when a terminal SELL left residual basis or raw quantity on the
                    // lot. The `continue` landed AFTER the matching BUY had already done
                    // `cash -= (cost + fee)` earlier in the same replay, so the buy leg of
                    // the transaction was applied and the sell leg was silently discarded.
                    //
                    // That is where the operator's 7.4 SOL ledger/journal split comes from.
                    // The arithmetic in the 5.0.6846 dump matches it exactly:
                    // openCostDelta 1.176 + realizedDelta 6.126 ~= cashDelta 7.417 — the
                    // replay is missing the proceeds it refused to credit, and still
                    // carrying the basis it refused to release. No capital was lost; the
                    // audit tool was reporting a number that describes nothing, and an
                    // operator reading it cannot tell a bookkeeping artefact from a real
                    // 7.4 SOL hole.
                    //
                    // A replay's job is to reproduce what happened, then say where it
                    // disagrees. So: apply the event's real economics, force the lot
                    // closed, and account for the residual explicitly as a written-off
                    // basis rather than leaving it to masquerade as open cost. The
                    // anomaly stays fully visible — reject() still fires, so the event is
                    // still quarantined from learning and still counted in failures — but
                    // the totals now balance against the ledger and the residual is a
                    // quantity the operator can actually read.
                    val terminalResidual6868 = side == "SELL" && (kotlin.math.abs(nextBasis) > 1e-9 ||
                        (lot.rawQty > java.math.BigInteger.ZERO && nextRaw != java.math.BigInteger.ZERO))
                    if (terminalResidual6868) {
                        // V5.0.6899 — both legs ARE applied below and the
                        // residual is written off, so the totals stay whole.
                        reject(t, eventId, "TERMINAL_SELL_INCOMPLETE_LOT", skipped = false)
                        residualBasisWrittenOff6868 += nextBasis.coerceAtLeast(0.0)
                        residualLotCount6868 += 1
                        try {
                            ForensicLogger.lifecycle(
                                "JOURNAL_TERMINAL_SELL_RESIDUAL_WRITTEN_OFF_6868",
                                "economicEventId=$eventId positionId=${t.positionId} residualBasisSol=${"%.6f".format(nextBasis)} " +
                                    "residualRaw=$nextRaw gross=${"%.6f".format(gross)} basis=${"%.6f".format(basis)} " +
                                    "action=apply_both_legs_and_close_lot",
                            )
                            PipelineHealthCollector.labelInc("JOURNAL_TERMINAL_SELL_RESIDUAL_WRITTEN_OFF_6868")
                        } catch (_: Throwable) {}
                    }
                    cash += (gross - fee)
                    openCost -= basis
                    realized += (gross - basis)
                    fees += fee
                    // V5.0.6980 — record what this side actually applied.
                    sellGross6980[t.positionId] = (sellGross6980[t.positionId] ?: 0.0) + gross
                    sellBasis6980[t.positionId] = (sellBasis6980[t.positionId] ?: 0.0) + basis
                    sellFee6980[t.positionId] = (sellFee6980[t.positionId] ?: 0.0) + fee
                    sellCount6980[t.positionId] = (sellCount6980[t.positionId] ?: 0) + 1
                    lot.basisSol = nextBasis
                    lot.rawQty = nextRaw
                    lot.displayQty = nextDisplay
                    if (terminalResidual6868) {
                        // The position is terminally closed on the ledger, so its residual
                        // basis is not open cost any more. Release it here instead of
                        // carrying a phantom open position for the rest of the replay.
                        openCost -= nextBasis.coerceAtLeast(0.0)
                        lot.basisSol = 0.0
                        lot.rawQty = java.math.BigInteger.ZERO
                        lot.displayQty = 0.0
                    }
                    if (side == "SELL" || lot.basisSol <= 1e-9) lots.remove(t.positionId)
                    if (side == "SELL") sells++ else partials++
                }
            }
        }

        if (openCost < -1e-9) {
            failures += "GLOBAL:NEGATIVE_OPEN_BASIS"
            try { PipelineHealthCollector.labelInc("JOURNAL_NEGATIVE_BASIS_INVARIANT_6647") } catch (_: Throwable) {}
        }
        // V5.0.6868 — report the residual as one readable line. Previously this
        // quantity was invisible: the sells carrying it were dropped entirely, and
        // what the operator saw instead was an unexplained multi-SOL split between
        // the ledger and the journal.
        if (residualLotCount6868 > 0) {
            try {
                ForensicLogger.lifecycle(
                    "JOURNAL_TERMINAL_RESIDUAL_SUMMARY_6868",
                    "lots=$residualLotCount6868 residualBasisSol=${"%.6f".format(residualBasisWrittenOff6868)} " +
                        "note=terminal_sells_left_basis_on_lot_applied_and_written_off",
                )
                PipelineHealthCollector.labelInc("JOURNAL_TERMINAL_RESIDUAL_LOTS_6868")
            } catch (_: Throwable) {}
        }

        // V5.0.7078 — say where the skipped SOL went, by reason, in the same
        // units ForensicReconciliation6635 reports its delta in. The operator's
        // 5.0.7072 device showed this walk at `ledger 19.2765 vs journal 11.9567`
        // while the canonical replay reported cashΔ=0.0000 on the same instant.
        // Two replays over one fact disagreeing by 7.3198 SOL is not an
        // accounting hole until something says which events account for it —
        // and if these lines sum to the delta, the delta is this walk's own
        // refusals and not a missing 7.3 SOL.
        if (skippedCashByReason7078.isNotEmpty()) {
            try {
                val detail7078 = skippedCashByReason7078.entries
                    .sortedByDescending { kotlin.math.abs(it.value) }
                    .take(6)
                    .joinToString(" ") { (reason, cashSol) ->
                        "$reason[n=${skippedCountByReason7078[reason] ?: 0} " +
                            "cash=${"%.6f".format(cashSol)} " +
                            "realized=${"%.6f".format(skippedRealizedByReason7078[reason] ?: 0.0)}]"
                    }
                PipelineHealthCollector.labelInc("JOURNAL_SKIPPED_ECONOMICS_NAMED_7078")
                // V5.0.7085 — split the two populations, because they answer
                // different questions and summing them hides both.
                //   PRE_ACCOUNTING_*  rows excluded before the walk ever saw
                //                     them. 7078 could not see these at all.
                //   everything else   rows that entered the body and were
                //                     refused by an invariant.
                val preKeys7085 = skippedCashByReason7078.keys.filter { it.startsWith("PRE_ACCOUNTING_") }
                val preCash7085 = preKeys7085.sumOf { skippedCashByReason7078[it] ?: 0.0 }
                val preCount7085 = preKeys7085.sumOf { skippedCountByReason7078[it] ?: 0 }
                val inBodyCash7085 = skippedCashByReason7078.entries
                    .filterNot { it.key.startsWith("PRE_ACCOUNTING_") }
                    .sumOf { it.value }
                ForensicLogger.lifecycle(
                    "JOURNAL_SKIPPED_ECONOMICS_NAMED_7078",
                    "skippedEvents=$skippedEvents6899 " +
                        "skippedCashTotal=${"%.6f".format(skippedCashByReason7078.values.sum())} " +
                        "skippedRealizedTotal=${"%.6f".format(skippedRealizedByReason7078.values.sum())} " +
                        "preAccountingEvents=$preCount7085 " +
                        "preAccountingCash=${"%.6f".format(preCash7085)} " +
                        "inBodyCash=${"%.6f".format(inBodyCash7085)} " +
                        "byReason=$detail7078 " +
                        "read=preAccountingCash_plus_inBodyCash_should_equal_the_6635_ledger_minus_journal_cash_delta",
                )
            } catch (_: Throwable) {}
        }

        val equity = cash + openCost
        val result = ReplayResult(
            cashSol = cash,
            realizedPnlSol = realized,
            openCostBasisSol = openCost,
            feesSol = fees,
            equitySol = equity,
            startingCashSol = startingSol,
            paperRows = totalRows,
            paperBuys = buys,
            paperSells = sells,
            paperPartialSells = partials,
            emittedAtMs = System.currentTimeMillis(),
            reconciled = failures.isEmpty(),
            totalsComplete6899 = skippedEvents6899 == 0,
            invariantFailures = failures.toList(),
            openRawQtyByPosition = lots.mapValues { it.value.rawQty },
            openBasisByPosition = lots.mapValues { it.value.basisSol },
            sellGrossByPosition6980 = sellGross6980.toMap(),
            sellBasisByPosition6980 = sellBasis6980.toMap(),
            sellFeeByPosition6980 = sellFee6980.toMap(),
            sellCountByPosition6980 = sellCount6980.toMap(),
        )
        lastResult.set(result)

        try {
            val ledgerCash = PaperCapitalAuthority6577.cashSol()
            val delta = ledgerCash - cash
            ledgerDivergenceLast.set(delta)
            // V5.0.6751 §LEGACY_DIVERGENCE_SUPERSEDED_BY_CANONICAL — operator
            // diagnostic Feb 2026:
            //   > "Canonical correctness balances exactly: replay has
            //   >  cashΔ=0, realizedΔ=0, openCostΔ≈0. Yet the older
            //   >  forensic reconciler reports wallet 20.376 vs
            //   >  expected ≤15.263 ... 2,692 PAPER_LEDGER_VS_JOURNAL
            //   >  _DIVERGENCE events ... six execution attempts were
            //   >  still blocked by PAPER_LEDGER_DIVERGENCE_6731. So
            //   >  stale/noncanonical accounting diagnostics are still
            //   >  leaking into execution authority."
            // The V5.0.6619 journal replay is a whole-history walk; a
            // V5.0.6464 canonical replay is the authoritative same-
            // revision snapshot. When the canonical replay reports
            // clean (cash/realized/open-cost deltas all within tolerance
            // AND no revision race), the older whole-history divergence
            // is superseded — do NOT emit the divergence label or drive
            // the guard from it. Emit a dedicated superseded label so
            // the operator can measure the frequency.
            val canonicalSupersedes6751 = try {
                val p = com.lifecyclebot.engine.truth.CanonicalPaperReplay6464.lastParity()
                p != null && !p.revisionRaceObserved &&
                    kotlin.math.abs(p.cashDelta) <= 0.001 &&
                    kotlin.math.abs(p.realizedDelta) <= 0.001 &&
                    kotlin.math.abs(p.openCostDelta) <= 0.01
            } catch (_: Throwable) { false }
            if (kotlin.math.abs(delta) > 0.001 && !canonicalSupersedes6751) {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619")
                ForensicLogger.lifecycle(
                    "PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619",
                    "ledgerCash=${"%.6f".format(ledgerCash)} journalCash=${"%.6f".format(cash)} " +
                        "delta=${"%.6f".format(delta)} paperRows=$totalRows buys=$buys sells=$sells partials=$partials " +
                        "action=fail_closed_retain_last_reconciled_account",
                )
            } else if (kotlin.math.abs(delta) > 0.001 && canonicalSupersedes6751) {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_SUPERSEDED_BY_CANONICAL_6751")
            } else {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_JOURNAL_PARITY_HEALTHY_6619")
            }
        } catch (_: Throwable) {}

        return result
    }

    /**
     * V5.0.6662 — settle durable journal lots whose canonical position was
     * deliberately removed by an earlier Stop/restart implementation.
     */
    @Synchronized
    fun repairOrphanedOpenLots6662(): Int {
        val replay = replay()
        if (replay.openBasisByPosition.isEmpty()) return 0
        val allBuys6697 = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
                .asSequence()
                .filter { it.mode.equals("paper", true) && it.side.equals("BUY", true) }
                .filter { it.positionId.isNotBlank() }
                .sortedBy { it.ts }
                .toList()
        } catch (_: Throwable) { emptyList() }
        val nativeBuyPositions6697 = allBuys6697.asSequence()
            .filterNot { it.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) }
            .map { it.positionId }
            .toSet()
        val buys = allBuys6697
            .filterNot {
                it.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) &&
                    it.positionId in nativeBuyPositions6697
            }
            .groupBy { it.positionId }
        var repaired = 0
        replay.openBasisByPosition.forEach { (positionId, basis) ->
            if (!basis.isFinite() || basis <= 1e-9) return@forEach
            val canonical = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
            if (canonical != null) return@forEach
            val positionBuys = buys[positionId].orEmpty()
            val seed = positionBuys.firstOrNull() ?: return@forEach
            val newestBuyAt = positionBuys.maxOfOrNull { it.ts } ?: 0L
            if (System.currentTimeMillis() - newestBuyAt < 10_000L) return@forEach
            val eventId = "PAPER6619:ORPHAN_REFUND:$positionId"
            val raw = replay.openRawQtyByPosition[positionId] ?: java.math.BigInteger.ZERO
            val scale = seed.tokenDecimals.takeIf { it in 0..18 }
                ?: seed.entryDecimals.coerceIn(0, 18)
            val displayQty = try {
                raw.toBigDecimal().movePointLeft(scale).toDouble()
            } catch (_: Throwable) { 0.0 }

            // V5.0.7050 — honour the verdict. This orphan refund keys on a
            // deterministic eventId, so every replay re-attempted the identical
            // refund and relied on TradeHistoryStore's dedupe to absorb it.
            // See CanonicalPaperTransaction6486.quantityReconcile6666 for the
            // full note on why the duplicate counters read 1244 against 169.
            if (PaperEconomicAtomicCommit6632.stampLedger(
                    eventId, seed.mint, PaperEconomicAtomicCommit6632.Side.SELL,
                    "JournalEconomicReplay6619.orphanRefund6662",
                ) == PaperEconomicAtomicCommit6632.Verdict.DUPLICATE_IGNORED
            ) {
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ATOMIC_COMMIT_REPAIR_SKIPPED_ALREADY_STAMPED_7050") } catch (_: Throwable) {}
                return@forEach
            }
            TradeHistoryStore.recordTrade(Trade(
                side = "SELL", mode = "paper", sol = basis,
                price = seed.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 }
                    ?: seed.price.coerceAtLeast(0.000000000001),
                ts = System.currentTimeMillis(),
                reason = "ORPHANED_STOP_LOT_REFUND_6662",
                pnlSol = 0.0, pnlPct = 0.0, feeSol = 0.0, netPnlSol = 0.0,
                tradingMode = seed.tradingMode, tradingModeEmoji = seed.tradingModeEmoji,
                mint = seed.mint, proofState = "PAPER_SIMULATED",
                positionId = positionId, entryTsMs = seed.entryTsMs.takeIf { it > 0L } ?: seed.ts,
                entryPriceSnapshot = seed.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 }
                    ?: seed.price.coerceAtLeast(0.000000000001),
                entryQtyToken = positionBuys.sumOf { it.entryQtyToken.coerceAtLeast(0.0) },
                entryCostSol = basis, entryDecimals = scale,
                soldQtyToken = displayQty, remainingQtyToken = 0.0,
                entryRawQty = positionBuys.fold(java.math.BigInteger.ZERO) { acc, row -> acc + row.entryRawQty },
                canonicalConsumedRaw = raw, remainingRawQty = java.math.BigInteger.ZERO,
                tokenDecimals = scale, soldCostBasisSol = basis,
                grossProceedsSol = basis, economicEventId = eventId,
            ))
            repaired++
            try {
                PipelineHealthCollector.labelInc("JOURNAL_ORPHAN_LOT_REFUNDED_6662")
                ForensicLogger.lifecycle(
                    "JOURNAL_ORPHAN_LOT_REFUNDED_6662",
                    "positionId=${positionId.take(24)} mint=${seed.mint.take(10)} basis=${"%.6f".format(basis)} action=durable_zero_pnl_terminal",
                )
            } catch (_: Throwable) {}
        }
        return repaired
    }

    fun latest(): ReplayResult? = lastResult.get()

    fun latestLedgerDivergenceSol(): Double = ledgerDivergenceLast.get() ?: 0.0

    fun statusLine(): String {
        val r = lastResult.get()
        val div = ledgerDivergenceLast.get() ?: 0.0
        return "replays=${replays.get()} " +
            (if (r != null)
                "rows=${r.paperRows} buys=${r.paperBuys} sells=${r.paperSells} partials=${r.paperPartialSells} " +
                    "cash=${"%.4f".format(r.cashSol)} realized=${"%+.4f".format(r.realizedPnlSol)} " +
                    "openCost=${"%.4f".format(r.openCostBasisSol)} equity=${"%.4f".format(r.equitySol)} " +
                    "ledgerDelta=${"%+.4f".format(div)} supersessionIncidents=${reportedReplaySupersessions6699.size}"
             else "result=empty")
    }

    internal fun resetForTest() {
        replays.set(0L); lastResult.set(null); ledgerDivergenceLast.set(0.0)
        reportedInvariantFailures6653.clear()
        reportedEmbeddedEntryRecoveries6664.clear()
        reportedReplaySupersessions6699.clear()
        quarantineScopeSignature7251.set("")
    }
}
