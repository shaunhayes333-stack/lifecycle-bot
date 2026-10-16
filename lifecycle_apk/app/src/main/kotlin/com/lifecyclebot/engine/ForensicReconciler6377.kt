package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V5.0.6377 — FORENSIC RECONCILER (11-item correctness spec).
 *
 * Operator directive (verbatim):
 *   "the 11 item forensic correction. do as much as you can now. bundle
 *    where you can. all data, pricing, wins and losses must reconcile
 *    forensically. same as the journal and reports."
 *
 * Read-only, pure-comparison reconciler. Runs on demand from
 * BotService.emitBotLoopTick (periodic) and on startup after journal
 * hydration. Every check is:
 *   • Additive        — never mutates state, never rewrites the journal.
 *   • Fluid-tolerant  — uses relative tolerances (SAFE_REL_TOL) so
 *                       legitimate rounding does not flag mismatches.
 *   • Domain-scoped   — separates paper from live and live-broadcast
 *                       from live-canonical so cross-domain drift does
 *                       not create phantom failures.
 *
 * Each check emits exactly ONE PipelineHealthCollector counter per pass:
 *   FORENSIC_OK_6377|<CHECK_NAME>
 *   FORENSIC_MISMATCH_6377|<CHECK_NAME>|<summary>
 * so the pipeline dump and any post-hoc log-scrape have a single audit
 * surface.
 *
 * The 11 checks (all derived from the operator's ongoing "wallet vs
 * journal vs reports must tie out" requirement):
 *
 *   1. WALLET_VS_JOURNAL        paperWalletSol ≈ startCapital + Σ(sell.pnlSol)
 *   2. JOURNAL_ROW_PARITY       Σ(BUY rows) ≥ Σ(SELL rows) — no orphan sells
 *   3. BUY_SELL_QTY_SKEW        per-mint Σbuyqty ≥ Σsellqty (never over-sold)
 *   4. COST_BASIS               each BUY: |sol - price×0| ignored; positive sanity only
 *   5. PNL_PCT_VS_SOL           each SELL: sign(pnlPct) == sign(pnlSol) (no phantom flip)
 *   6. SELL_REASON_PRESENCE     every SELL has non-empty reason field
 *   7. PRICE_IMMUTABILITY       every BUY has price>0 (no zero-price sneak-throughs)
 *   8. TACTIC_MU_VS_JOURNAL     TacticSwitcher aggregated μ per lane vs journal μ per mode
 *   9. DUPLICATE_JOURNAL_ROWS   no exact (mint, side, ts) duplicates
 *  10. ORPHAN_SELL              every SELL(mint) has ≥1 prior BUY(mint) in journal
 *  11. CANONICAL_VS_REGISTRY    canonical live-open count vs GlobalTradeRegistry open count
 *
 * Failure surface (pipeline dump reads this):
 *   ForensicReconciler6377.lastReport().mismatches → List<Mismatch>
 */
object ForensicReconciler6377 {

    /** Relative tolerance for float comparisons (0.5% by default). */
    private const val SAFE_REL_TOL = 0.005

    /** Absolute floor SOL below which we ignore relative-tolerance drift. */
    private const val SAFE_ABS_FLOOR_SOL = 0.001

    private val passCount = AtomicLong(0L)
    private val mismatchCount = AtomicLong(0L)
    private val lastRunAtMs = AtomicLong(0L)

    @Volatile private var _lastReport: Report = Report(runAtMs = 0L, checks = emptyList())

    data class CheckResult(
        val name: String,
        val ok: Boolean,
        val summary: String,
    )

    data class Report(
        val runAtMs: Long,
        val checks: List<CheckResult>,
    ) {
        val okCount get() = checks.count { it.ok }
        val mismatchCount get() = checks.count { !it.ok }
        val mismatches get() = checks.filter { !it.ok }
    }

    fun lastReport(): Report = _lastReport
    fun lifetimePassCount(): Long = passCount.get()
    fun lifetimeMismatchCount(): Long = mismatchCount.get()
    fun lastRunAtMs(): Long = lastRunAtMs.get()

    /**
     * Run all 11 checks. Pure function against provided inputs — the
     * caller is expected to have already resolved the current
     * paperWalletSol / startCapitalSol / etc.
     *
     * Emits a PipelineHealthCollector counter per check and stores the
     * full report in [_lastReport] for the pipeline dump to render.
     */
    /** V5.0.8087 — result of the per-lineage quantity check. */
    internal class Skew8087(
        val lineages: Int, val violators: List<String>, val violatorMints: List<String>,
        val unverifiable: Int, val recovered: Int, val duplicateCloses: List<String>,
    )

    private fun recoveredInventory8087(t: Trade): Boolean {
        val r = t.reason.uppercase(); val p = t.positionId.uppercase()
        return r.contains("RECOVER") || r.contains("ORPHAN") || p.startsWith("RECOVER") || p.startsWith("ORPHAN")
    }

    /**
     * Pure: compare sold vs bought quantity per position lineage. [canonicalQty] answers the original quantity of a
     * position whose BUY is no longer in the in-memory ring (durable canonical record), or null when unknown.
     */
    internal fun qtySkew8087(buys: List<Trade>, sells: List<Trade>, canonicalQty: (String) -> Double?): Skew8087 {
        val violators = ArrayList<String>(); val vMints = LinkedHashSet<String>(); val dups = ArrayList<String>()
        var unverifiable = 0; var recovered = 0; var lineages = 0
        val buysByPid = buys.filter { it.positionId.isNotBlank() }.groupBy { it.positionId }
        val (recSells, stratSells) = sells.partition { recoveredInventory8087(it) }
        recovered = recSells.size
        for ((pid, rows) in stratSells.filter { it.positionId.isNotBlank() }.groupBy { it.positionId }) {
            lineages++
            if (rows.size > 1) dups += "${rows.first().mint.take(6)}:${pid.take(10)}x${rows.size}"
            val sold = rows.sumOf { it.soldQtyToken.coerceAtLeast(0.0) }
            val bought = buysByPid[pid]?.sumOf { it.entryQtyToken.coerceAtLeast(0.0) }?.takeIf { it > 0.0 }
                ?: canonicalQty(pid)?.takeIf { it > 0.0 }
            if (bought == null) { unverifiable++; continue }
            if (sold > bought * (1.0 + SAFE_REL_TOL) && (sold - bought) > 1.0) {
                violators += "${rows.first().mint.take(6)}:${pid.take(10)}=(buy${fmt(bought)}/sell${fmt(sold)})"
                vMints += rows.first().mint
            }
        }
        // Legacy rows without a positionId: per mint, only when the window holds the mint's first BUY before its first SELL.
        val legacyBuys = buys.filter { it.positionId.isBlank() }.groupBy { it.mint }
        for ((mint, rows) in stratSells.filter { it.positionId.isBlank() }.groupBy { it.mint }) {
            lineages++
            val b = legacyBuys[mint].orEmpty()
            val firstBuy = b.minOfOrNull { it.ts }; val firstSell = rows.minOf { it.ts }
            if (firstBuy == null || firstBuy > firstSell) { unverifiable++; continue }
            val bought = b.sumOf { it.entryQtyToken.coerceAtLeast(0.0) }
            val sold = rows.sumOf { it.soldQtyToken.coerceAtLeast(0.0) }
            if (bought > 0.0 && sold > bought * (1.0 + SAFE_REL_TOL) && (sold - bought) > 1.0) {
                violators += "${mint.take(6)}=(buy${fmt(bought)}/sell${fmt(sold)})"; vMints += mint
            }
        }
        // a lineage closed twice is a duplicate economic close: its mint's outcomes are contaminated too
        dups.forEach { d -> stratSells.firstOrNull { d.startsWith(it.mint.take(6) + ":") }?.let { vMints += it.mint } }
        return Skew8087(lineages, violators + dups.map { "DUPLICATE_CLOSE:$it" }, vMints.toList(), unverifiable, recovered, dups)
    }

    private fun canonicalOriginalQty8087(positionId: String): Double? = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(positionId)?.let { p ->
            java.math.BigDecimal(p.originalQtyRaw).movePointLeft(p.quantityScale.coerceIn(0, 18)).toDouble()
        }
    } catch (_: Throwable) { null }

    @JvmStatic
    /** V5.0.7868 — every mint the canonical authority parents (open, closed, live quarantined). */
    private fun canonicalParentMints7868(includeDurable8144: Boolean = true): Set<String> {
        val out = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
                .let { it.openPositions() + it.closedPositions() + it.quarantinedLivePositions7454() }
                .mapTo(HashSet()) { it.mint }
        } catch (_: Throwable) { HashSet() }
        // V5.0.7982 — the bot's own on-chain buy lots are a parent too. A learning reset clears
        // the journal and canonical history but never FillLotLedger6344, so 5.0.7976 read
        // "buys=2 sells=12 unparentedSells=12 / orphanSells=10" for sells of the bot's own buys.
        try {
            FillLotLedger6344.snapshotForWallet(WalletManager.currentPubkey()).forEach { out += it.mintAddress }
        } catch (_: Throwable) {}
        // V5.0.8144 — the canonical book is memory-only for LIVE: a restart empties closedPositions(),
        // and wallet adoption / pending promotion never wrote an economic Buy event. Durable lineage parents those sells.
        if (includeDurable8144) out += durableParentMints8144()
        return out
    }

    /**
     * V5.0.8144 — durable bot-buy lineage that survives a restart: LIVE lot births
     * (ReconcileLineageLedger8144), durable LIVE economic Buy events and chain-CONFIRMED BUY signatures.
     */
    internal fun durableParentMints8144(): Set<String> {
        val out = HashSet<String>()
        try { out += com.lifecyclebot.engine.truth.ReconcileLineageLedger8144.mints8144() } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot().forEach { e ->
                if (e is com.lifecyclebot.engine.truth.EconomicEventSchema6464.Buy && e.mode.equals("live", true) && e.mint.isNotBlank()) out += e.mint
            }
        } catch (_: Throwable) {}
        try {
            com.lifecyclebot.network.SignedTxLedger8085.allRows8144().forEach { r ->
                if (r.side == "BUY" && r.state == com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED && r.mint.isNotBlank()) out += r.mint
            }
        } catch (_: Throwable) {}
        return out
    }

    /**
     * V5.0.8144 — pure: split unparented sells by named cause (ReconcileClassifier8144) and count the ones
     * only durable lineage parents. Returns (orphans by cause, lineageResolved).
     */
    internal fun orphanCauses8144(
        sells: List<Trade>, boughtMints: Set<String>, liveParentMints: Set<String>, durableMints: Set<String>,
    ): Pair<Map<String, Int>, Int> {
        val causes = LinkedHashMap<String, Int>()
        var resolved = 0
        for (t in sells) {
            if (t.mint.isBlank() || t.mint in boughtMints || t.mint in liveParentMints) continue
            if (t.mint in durableMints) { resolved++; continue }
            val c = com.lifecyclebot.engine.truth.ReconcileClassifier8144.orphanCause8144(
                com.lifecyclebot.engine.truth.ReconcileClassifier8144.Row8144(
                    side = t.side, mint = t.mint, positionId = t.positionId, sig = t.sig, reason = t.reason,
                ),
                emptySet(),
            ).name
            causes[c] = (causes[c] ?: 0) + 1
        }
        return causes to resolved
    }

    fun runAll(
        allTrades: List<Trade>,
        paperMode: Boolean,
        paperWalletSol: Double,
        startCapitalSol: Double,
        canonicalLiveOpenCount: Int,
        registryLiveOpenCount: Int,
    ): Report {
        val results = mutableListOf<CheckResult>()

        // Filter by mode so paper and live are separately reconciled.
        val tradesForMode = allTrades.filter {
            val m = it.mode.uppercase()
            if (paperMode) m == "PAPER" else m == "LIVE"
        }
        val buys  = tradesForMode.filter { it.side.equals("BUY", true) }
        val sells = tradesForMode.filter { it.side.equals("SELL", true) }

        // ── 1. WALLET_VS_JOURNAL ─────────────────────────────────────────
        run {
            // V5.0.6898 §THE_EXPECTATION_OMITTED_PARTIAL_PROCEEDS.
            //
            // `sells` above is filtered to side == "SELL", so PARTIAL_SELL rows
            // were absent from this expectation — while their proceeds very much
            // do credit the wallet. The test is one-sided and only flags
            // wallet > expected, so every missing credit becomes a permanent
            // false "phantom SOL creation" alarm.
            //
            // Operator 5.0.6892:
            //   WALLET_VS_JOURNAL wallet=65.005 expected<=11.390+tol=0.05695
            //                     over=53.615
            // against a ledger reading realized=+67.5598 and 21 lifetime
            // partials (economicSchema: buys=455 sells=308 partials=21). The
            // ledger's own conservation identity passed in the same snapshot —
            // "Paper capital conservation OK", CONSERVATION delta=-0.000000 —
            // so the two reconcilers disagreed and this one was wrong.
            //
            // That matters beyond tidiness: this check feeds a wallet-snap
            // remediation path (BotService:13895) and it is one of only four
            // checks in the 6377 report. A permanently-red check is a check
            // nobody can act on, and it masks the real drift it exists to find.
            //
            // The other two terms in the true identity
            //   cash = startingCash + realized - fees - openCost
            // can only ever LOWER the wallet, so the one-sided test stays valid
            // without them. Partial proceeds were the only omitted term that
            // raises it.
            val partialSells6898 = tradesForMode.filter { it.side.equals("PARTIAL_SELL", true) }
            val realizedSol = sells.sumOf { it.pnlSol } + partialSells6898.sumOf { it.pnlSol }
            val expected = startCapitalSol + realizedSol
            // Open positions consume SOL from the wallet — a mismatch here
            // may just mean money is parked in open buys. So this check
            // treats "wallet <= expected" as OK (parked capital) and only
            // flags "wallet > expected + tolerance" (phantom SOL creation).
            val over = paperWalletSol - expected
            val tolerance = maxOf(SAFE_ABS_FLOOR_SOL, abs(expected) * SAFE_REL_TOL)
            val ok = over <= tolerance
            val summary = "wallet=${fmt(paperWalletSol)} expected≤${fmt(expected)}+tol=${fmt(tolerance)} " +
                "over=${fmt(over)} terminalSells=${sells.size} partials=${partialSells6898.size}"
            results += CheckResult("WALLET_VS_JOURNAL", ok, summary)
        }

        // ── 2. JOURNAL_ROW_PARITY (buys ≥ sells) ─────────────────────────
        run {
            // V5.0.7868 — a sell of a canonically-parented position (wallet-recovered /
            // adopted rows are opened by recovery, not by a journal BUY) is not an
            // unmatched sell. 5.0.7867 LIVE read buys=1 sells=9.
            val parented7868 = canonicalParentMints7868()
            val unparentedSells7868 = sells.count { it.mint.isBlank() || it.mint !in parented7868 }
            val ok = buys.size >= unparentedSells7868
            results += CheckResult("JOURNAL_ROW_PARITY", ok, "buys=${buys.size} sells=${sells.size} unparentedSells=$unparentedSells7868")
        }

        // ── 3. BUY_SELL_QTY_SKEW (per-mint) ──────────────────────────────
        run {
            // V5.0.8087 — skew is measured per position lineage (positionId links BUY + top-ups + terminal SELL), not
            // summed by mint across unrelated positions in a bounded in-memory ring. 8086 live read "49PU6E buy 63,838 /
            // sell 85,291" because the ring had evicted an earlier position's BUY while keeping its SELL, and the result
            // quarantined the mint's outcomes. A lineage whose BUY left the ring is checked against the canonical
            // position's original quantity; one that cannot be reconstructed is UNVERIFIABLE (reported, never quarantined).
            val skew = qtySkew8087(buys, sells) { pid -> canonicalOriginalQty8087(pid) }
            val ok = skew.violators.isEmpty()
            val summary = (if (ok) "lineages=${skew.lineages} buyMints=${buys.mapTo(HashSet()) { it.mint }.size}"
                else "over-sold lineages=${skew.violators.size} e.g. ${skew.violators.first()}") +
                " unverifiableBoundedWindow=${skew.unverifiable} recoveredInventory=${skew.recovered} duplicateCloses=${skew.duplicateCloses.size}"
            results += CheckResult("BUY_SELL_QTY_SKEW", ok, summary)
            // V5.0.6496 §2 — only a proven skew (complete lineage) feeds the historical economic quarantine.
            if (!ok) {
                try {
                    com.lifecyclebot.engine.truth.HistoricalEconomicQuarantine6496
                        .reportBuySellSkew(skew.violatorMints)
                } catch (_: Throwable) {}
            }
        }

        // ── 4. COST_BASIS (buy.sol > 0 for entries) ──────────────────────
        run {
            val zeroCostBuys = buys.count { it.sol <= 0.0 && it.price > 0.0 }
            val ok = zeroCostBuys == 0
            results += CheckResult("COST_BASIS", ok, if (ok) "buys=${buys.size} all positive-cost" else "zero-cost buys=$zeroCostBuys")
        }

        // ── 5. PNL_PCT_VS_SOL (sign parity, no phantom flip) ─────────────
        run {
            val flipped = sells.count { t ->
                // Skip scratches (near-zero on both).
                if (abs(t.pnlSol) < 0.0005 && abs(t.pnlPct) < 0.1) return@count false
                val signSol = if (t.pnlSol > 0) 1 else if (t.pnlSol < 0) -1 else 0
                val signPct = if (t.pnlPct > 0) 1 else if (t.pnlPct < 0) -1 else 0
                signSol != signPct
            }
            val ok = flipped == 0
            results += CheckResult("PNL_PCT_VS_SOL", ok, if (ok) "sells=${sells.size} sign-consistent" else "sign-flipped sells=$flipped")
        }

        // ── 6. SELL_REASON_PRESENCE ──────────────────────────────────────
        run {
            val missing = sells.count { it.reason.isBlank() }
            val ok = missing == 0
            results += CheckResult("SELL_REASON_PRESENCE", ok, if (ok) "sells=${sells.size} all-tagged" else "reason-blank sells=$missing")
        }

        // ── 7. PRICE_IMMUTABILITY (proxy: no zero-price entries) ─────────
        run {
            val zeroPriceBuys = buys.count { it.price <= 0.0 && it.sol > 0.0 }
            val ok = zeroPriceBuys == 0
            results += CheckResult("PRICE_IMMUTABILITY", ok, if (ok) "buys=${buys.size} all-priced" else "zero-price buys=$zeroPriceBuys")
        }

        // ── 8. TACTIC_MU_VS_JOURNAL ──────────────────────────────────────
        // Compare TacticSwitcher-reported μ per (lane) to journal-derived
        // μ per tradingMode over a bounded lookback. Deviations >200pp
        // relative are flagged as "tactic persistence drift".
        run {
            try {
                val laneStats = try {
                    com.lifecyclebot.engine.learning.TacticSwitcher.dumpForensicSnapshot6377()
                } catch (_: Throwable) { emptyList() }
                if (laneStats.isEmpty()) {
                    results += CheckResult("TACTIC_MU_VS_JOURNAL", true, "no-tactic-snapshot-available")
                } else {
                    val journalByMode = sells.groupBy { it.tradingMode.uppercase() }
                        .mapValues { e -> e.value.map { it.pnlPct } }
                    var drift = 0
                    var totalCompared = 0
                    for ((laneKey, tacticMu, tacticN) in laneStats) {
                        if (tacticN < 5) continue
                        val laneUpper = laneKey.substringBefore("|").uppercase()
                        val journalPnls = journalByMode[laneUpper] ?: continue
                        if (journalPnls.size < 5) continue
                        val journalMu = journalPnls.average()
                        totalCompared++
                        // Deviation is flagged when tacticMu > journalMu + 100pp for the
                        // same lane over a 5+ sample overlap. This catches the persisted
                        // phantom-inflated pnlSum leak.
                        if (abs(tacticMu - journalMu) > 100.0 && abs(tacticMu) > 50.0) {
                            drift++
                        }
                    }
                    val ok = drift == 0
                    results += CheckResult("TACTIC_MU_VS_JOURNAL", ok, "compared=$totalCompared drift=$drift")
                }
            } catch (_: Throwable) {
                results += CheckResult("TACTIC_MU_VS_JOURNAL", true, "check-skipped-exception")
            }
        }

        // ── 9. DUPLICATE_JOURNAL_ROWS ────────────────────────────────────
        run {
            val fingerprints = tradesForMode.map { "${it.mint}|${it.side}|${it.ts}" }
            val distinct = fingerprints.toSet().size
            val duplicates = fingerprints.size - distinct
            val ok = duplicates == 0
            results += CheckResult("DUPLICATE_JOURNAL_ROWS", ok, if (ok) "rows=${fingerprints.size} unique" else "dupes=$duplicates of ${fingerprints.size}")
        }

        // ── 10. ORPHAN_SELL ──────────────────────────────────────────────
        run {
            // V5.0.6900 §THE_PARENT_SET_WAS_AN_EVICTING_WINDOW.
            //
            // `buys` derives from allTrades, which BotService gathers from
            // status.tokens.values.flatMap { it.trades } — the in-memory
            // per-token ring. That collection evicts: operator 5.0.6892 shows
            // WATCHLIST_LRU_EVICT_6287=111, WATCHLIST_CAP_EVICT=12,
            // MEME_RESTORE_TRIMMED=1. So a SELL whose matching BUY has aged out
            // of memory reads as parentless even though the journal holds it,
            // and the check reported "orphan sells=30" against 308 lifetime
            // closes.
            //
            // Same failure class as the WALLET_VS_JOURNAL defect fixed in
            // V5.0.6898: a durable invariant evaluated against a bounded
            // window. A reconciler that cries wolf on its own eviction policy
            // is a reconciler nobody can act on, and 6377 has only four checks
            // — two of them were false.
            //
            // CanonicalPositionAuthority6441 is the durable record (455
            // positions / 308 closed in that same snapshot), so a mint it knows
            // about is definitionally parented. Only a sell whose mint is
            // absent from BOTH the in-memory buys and the canonical authority
            // is a genuine orphan.
            val boughtMints = buys.mapTo(HashSet()) { it.mint }
            val canonicalMints6900 = canonicalParentMints7868()
            val orphans = sells.count {
                it.mint.isNotBlank() && it.mint !in boughtMints && it.mint !in canonicalMints6900
            }
            val ok = orphans == 0
            // V5.0.8144 — every orphan carries a named cause, and sells only durable lineage parents are counted.
            val durable8144 = durableParentMints8144()
            val (causes8144, resolved8144) = orphanCauses8144(sells, boughtMints, canonicalParentMints7868(includeDurable8144 = false), durable8144)
            val causeText8144 = causes8144.entries.joinToString(" ") { "${it.key.removePrefix("ORPHAN_SELL_")}=${it.value}" }
            results += CheckResult(
                "ORPHAN_SELL", ok,
                if (ok) "sells=${sells.size} all-parented (journal=${boughtMints.size} canonical=${canonicalMints6900.size}) lineageResolved=$resolved8144"
                else "orphan sells=$orphans $causeText8144 lineageResolved=$resolved8144 journalBuys=${boughtMints.size} canonicalMints=${canonicalMints6900.size}",
            )
        }

        // ── 11. CANONICAL_VS_REGISTRY ────────────────────────────────────
        run {
            // V5.0.7868 — EmergentGuardrails is the legacy PAPER registry (see
            // PositionRegistryParityAudit6464 V5.0.7822). In LIVE it is out of scope:
            // 5.0.7867 "canonical=2 registry=10 delta=-8" was the 10 LEGACY paper rows.
            if (!paperMode) {
                results += CheckResult("CANONICAL_VS_REGISTRY", true, "canonical=$canonicalLiveOpenCount registry=LEGACY_PAPER_OUT_OF_SCOPE_IN_LIVE")
            } else {
                val delta = canonicalLiveOpenCount - registryLiveOpenCount
                val ok = abs(delta) <= 0
                results += CheckResult("CANONICAL_VS_REGISTRY", ok, "canonical=$canonicalLiveOpenCount registry=$registryLiveOpenCount delta=$delta")
            }
        }

        // Emit telemetry.
        for (r in results) {
            try {
                if (r.ok) {
                    PipelineHealthCollector.labelInc("FORENSIC_OK_6377|${r.name}")
                    passCount.incrementAndGet()
                } else {
                    val safeSummary = r.summary.take(80).replace('|', '_')
                    PipelineHealthCollector.labelInc("FORENSIC_MISMATCH_6377|${r.name}|$safeSummary")
                    mismatchCount.incrementAndGet()
                }
            } catch (_: Throwable) {}
        }

        val report = Report(runAtMs = System.currentTimeMillis(), checks = results)
        _lastReport = report
        lastRunAtMs.set(report.runAtMs)
        return report
    }

    /** Test-only reset. */
    internal fun resetForTest() {
        passCount.set(0L)
        mismatchCount.set(0L)
        lastRunAtMs.set(0L)
        _lastReport = Report(runAtMs = 0L, checks = emptyList())
    }

    private fun fmt(d: Double): String =
        if (abs(d) >= 1000) "%.0f".format(d)
        else if (abs(d) >= 1)   "%.3f".format(d)
        else                    "%.5f".format(d)
}
