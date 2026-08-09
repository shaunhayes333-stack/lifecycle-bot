package com.lifecyclebot.engine.truth

/**
 * V5.0.6430 §AN — AUTOMATED INVARIANT SELF-CHECKS.
 *
 * OPERATOR (V5.0.6424 §AN):
 *   'REQUIRED AUTOMATED INVARIANT TESTS
 *      TEST 1 — duplicate final exit
 *      TEST 2 — partial concurrency
 *      TEST 3 — partial then final
 *      TEST 6 — PAPER/LIVE isolation
 *      TEST 7 — stale price
 *      TEST 8 — lane immutability
 *      TEST 9 — capital conservation
 *      TEST 10 — scanner dedup
 *      ...'
 *
 * DESIGN
 * ──────
 * A single object that exercises the by-construction guards from the
 * V5.0.6427/6430 patch series against in-memory scenarios and returns
 * a pass/fail report. Not tied to JUnit or the Android test framework;
 * the developer/operator can call InvariantSelfCheck6430.runAll() from
 * a diagnostic screen or an automated release-gate script.
 *
 * Every test uses resetForTest() on the module it targets so runs are
 * hermetic.
 */
object InvariantSelfCheck6430 {

    data class Result(val name: String, val passed: Boolean, val detail: String)
    data class Report(val results: List<Result>) {
        val passed: Boolean = results.all { it.passed }
        fun summary(): String {
            val ok = results.count { it.passed }
            val fail = results.count { !it.passed }
            return buildString {
                append("INVARIANT_SELF_CHECK_6430 pass=$ok fail=$fail total=${results.size}\n")
                for (r in results) {
                    append("  ").append(if (r.passed) "✅" else "❌").append(' ')
                    append(r.name).append(" — ").append(r.detail).append('\n')
                }
            }
        }
    }

    fun runAll(): Report = Report(
        listOf(
            test1DuplicateFinalExit(),
            test2PartialConcurrency(),
            test3PartialThenFinal(),
            test6PaperLiveIsolation(),
            test7StalePrice(),
            test8LaneImmutability(),
            test9CapitalConservation(),
            test10ScannerDedup(),
            test11ReconcilerWatchdog(),
            test12ZeroDenominatorRendering(),
        )
    )

    // ── TEST 1 — duplicate final exit ─────────────────────────────
    private fun test1DuplicateFinalExit(): Result {
        PositionStateLedger6427.resetForTest()
        val pid = "pos_test1"
        PositionStateLedger6427.registerOpen(pid)
        val d1 = PositionStateLedger6427.reserveTerminalSell(pid, 1L, "SELL", "mint1", "SYM1")
        val d2 = PositionStateLedger6427.reserveTerminalSell(pid, 1L, "SELL", "mint1", "SYM1")
        val d3 = PositionStateLedger6427.reserveTerminalSell(pid, 2L, "SELL", "mint1", "SYM1")
        PositionStateLedger6427.confirmTerminalSell(pid)
        val d4 = PositionStateLedger6427.reserveTerminalSell(pid, 3L, "SELL", "mint1", "SYM1")
        val ok = d1.allow && !d2.allow && !d3.allow && !d4.allow
        return Result("dupTerminalExit", ok, "d1=${d1.allow} d2=${d2.allow} d3=${d3.allow} d4=${d4.allow}")
    }

    // ── TEST 2 — partial concurrency (sell 60 twice on qty=100) ───
    private fun test2PartialConcurrency(): Result {
        SellQtyBoundaryClamp6427.resetForTest()
        val pid = "pos_test2"
        SellQtyBoundaryClamp6427.registerBuy(pid, 100.0)
        val s1 = SellQtyBoundaryClamp6427.clamp(pid, 60.0, "m", "S")
        val s2 = SellQtyBoundaryClamp6427.clamp(pid, 60.0, "m", "S")
        val totalSold = s1 + s2
        val ok = totalSold <= 100.0001
        return Result("partialConcurrency", ok, "s1=$s1 s2=$s2 total=$totalSold")
    }

    // ── TEST 3 — partial then final on qty=100 ────────────────────
    private fun test3PartialThenFinal(): Result {
        SellQtyBoundaryClamp6427.resetForTest()
        val pid = "pos_test3"
        SellQtyBoundaryClamp6427.registerBuy(pid, 100.0)
        val partial = SellQtyBoundaryClamp6427.clamp(pid, 20.0, "m", "S")
        val finalSell = SellQtyBoundaryClamp6427.clamp(pid, 100.0, "m", "S")
        val ok = partial == 20.0 && kotlin.math.abs(finalSell - 80.0) < 0.001
        return Result("partialThenFinal", ok, "partial=$partial final=$finalSell (expected 20+80)")
    }

    // ── TEST 6 — PAPER/LIVE isolation on RunnerAutoCompound ───────
    private fun test6PaperLiveIsolation(): Result {
        RunnerAutoCompound6422.resetForTest()
        // Feed only PAPER wins and expect live multiplier untouched.
        for (i in 1..5) {
            RunnerAutoCompound6422.onPaperClose(20.0, "MOONSHOT", "m$i", "SYM")
        }
        val paperMult = RunnerAutoCompound6422.paperStreakMultiplier()
        val liveMult = RunnerAutoCompound6422.liveStreakMultiplier()
        val ok = paperMult > 1.0 && liveMult == 1.0
        return Result("paperLiveIsolation", ok, "paperMult=$paperMult liveMult=$liveMult (live must remain 1.0)")
    }

    // ── TEST 7 — stale price cannot become paper fill ─────────────
    private fun test7StalePrice(): Result {
        val staleTerm = StalePriceFillGate6427.canRealize(
            StalePriceFillGate6427.FillKind.PAPER_TERMINAL_SELL,
            quoteAgeMs = 30_000L,
            priceSource = "DEXSCREENER",
            mint = "m", symbol = "S",
        )
        val staleSource = StalePriceFillGate6427.canRealize(
            StalePriceFillGate6427.FillKind.PAPER_PARTIAL_SELL,
            quoteAgeMs = 1_000L,
            priceSource = "QUALITY_STALE_PRICE",
            mint = "m", symbol = "S",
        )
        val fresh = StalePriceFillGate6427.canRealize(
            StalePriceFillGate6427.FillKind.PAPER_TERMINAL_SELL,
            quoteAgeMs = 2_000L,
            priceSource = "DEXSCREENER",
            mint = "m", symbol = "S",
        )
        val ok = !staleTerm.allow && !staleSource.allow && fresh.allow
        return Result("stalePrice", ok, "term>15s=${staleTerm.allow} staleSource=${staleSource.allow} fresh=${fresh.allow}")
    }

    // ── TEST 8 — lane immutability ────────────────────────────────
    private fun test8LaneImmutability(): Result {
        LaneAttributionLedger6427.resetForTest()
        val pid = "pos_test8"
        LaneAttributionLedger6427.recordEntry(pid, "COPYTRADE", "PRESALE_SNIPE")
        // Attempt to overwrite via exit-lane confusion:
        LaneAttributionLedger6427.recordEntry(pid, "MOONSHOT", "MOONSHOT_STOP_LOSS")
        LaneAttributionLedger6427.recordExitPolicy(pid, "MOONSHOT", "MOONSHOT_STOP_LOSS", "STOP_LOSS", "PAPER")
        val entryLane = LaneAttributionLedger6427.getEntryLane(pid)
        val exitLane = LaneAttributionLedger6427.getExitPolicy(pid)?.lane
        val ok = entryLane == "COPYTRADE" && exitLane == "MOONSHOT"
        return Result("laneImmutability", ok, "entry=$entryLane exit=$exitLane (entry must remain COPYTRADE)")
    }

    // ── TEST 9 — capital conservation ─────────────────────────────
    private fun test9CapitalConservation(): Result {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        // 3 buys, 2 sells
        PaperAccountLedger6430.onBuy(1.0)
        PaperAccountLedger6430.onBuy(0.5)
        PaperAccountLedger6430.onBuy(2.0)
        PaperAccountLedger6430.onSell(grossProceedsSol = 1.5, costBasisSoldSol = 1.0)
        PaperAccountLedger6430.onSell(grossProceedsSol = 0.4, costBasisSoldSol = 0.5)
        val err = PaperAccountLedger6430.assertInvariant()
        return Result("capitalConservation", err == null, err ?: "ok ${PaperAccountLedger6430.statusLine()}")
    }

    // ── TEST 10 — scanner dedup ───────────────────────────────────
    private fun test10ScannerDedup(): Result {
        CandidateAccumulator6430.resetForTest()
        val mint = "mintDedup"
        for (i in 1..500) {
            CandidateAccumulator6430.observe(mint, "PROVIDER_$i", liquidityUsd = 10_000.0)
        }
        val claim1 = CandidateAccumulator6430.tryClaimEvaluation(mint)
        val claim2 = CandidateAccumulator6430.tryClaimEvaluation(mint)
        CandidateAccumulator6430.releaseEvaluation(mint)
        val claim3 = CandidateAccumulator6430.tryClaimEvaluation(mint)  // TTL should block
        val ok = claim1 && !claim2 && !claim3
        return Result("scannerDedup", ok, "500 hits → claim1=$claim1 claim2=$claim2 (ttl)claim3=$claim3")
    }

    // ── TEST 11 — reconciler watchdog ─────────────────────────────
    private fun test11ReconcilerWatchdog(): Result {
        ReconcilerWatchdog6430.resetForTest()
        val cold = ReconcilerWatchdog6430.healthStatus()
        ReconcilerWatchdog6430.beforeAttempt()
        ReconcilerWatchdog6430.afterAttempt(true, 50L)
        val warm = ReconcilerWatchdog6430.healthStatus()
        ReconcilerWatchdog6430.beforeAttempt()
        ReconcilerWatchdog6430.afterAttempt(false, 60L, "boom")
        ReconcilerWatchdog6430.beforeAttempt()
        ReconcilerWatchdog6430.afterAttempt(false, 60L, "boom")
        val failed = ReconcilerWatchdog6430.healthStatus()
        val ok = cold == ReconcilerWatchdog6430.Status.UNKNOWN &&
            warm == ReconcilerWatchdog6430.Status.HEALTHY &&
            failed == ReconcilerWatchdog6430.Status.FAILED
        return Result("reconcilerWatchdog", ok, "cold=$cold warm=$warm failed=$failed")
    }

    // ── TEST 12 — zero-denominator rendering (§P) ─────────────────
    private fun test12ZeroDenominatorRendering(): Result {
        val wr0 = ForensicEventEnvelope6430.renderRate(0, 0)
        val wrX = ForensicEventEnvelope6430.renderRate(8, 2)
        val pfX = ForensicEventEnvelope6430.renderPF(2, 0, 5.0, 0.0)
        val expX = ForensicEventEnvelope6430.renderExpectancy(0, 5.0)
        val ok = wr0 == "N/A" && wrX == "80.0%" && pfX == "N/A" && expX == "N/A"
        return Result("zeroDenominatorRendering", ok, "wr0=$wr0 wr8/2=$wrX pf(loss=0)=$pfX exp(n=0)=$expX")
    }
}
