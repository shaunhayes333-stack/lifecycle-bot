package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.StopAuthority7887
import com.lifecyclebot.engine.sell.ExitHotPath7809
import com.lifecyclebot.engine.sell.ProfitEscalation8189
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144
import com.lifecyclebot.engine.sell.StopRoute8189
import com.lifecyclebot.engine.truth.HeldExitQuote8189
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.8189 — exits bank: the lifecycle hold needs runner evidence, held marks come from a held sell quote, every position is proven. */
class Aate8189ExitBankingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun bi(v: Long) = BigInteger.valueOf(v)

    private fun live(mint: String, style: String, lane: String = "MOONSHOT", price: Double = 0.85, peak: Double = 0.0) =
        TokenState(mint = mint, position = Position(qtyToken = 1.0, entryPrice = 1.0, entryTime = 1L, tradingMode = lane,
            isPaperPosition = false, entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT")).also {
            it.lastPrice = price; it.lastPriceUpdate = System.currentTimeMillis(); it.position.peakGainPct = peak
        }

    // ── 1. the lifecycle hold needs runner evidence ──

    @Test fun evidenceIsASealedRunnerWithAProvenSetupOrACorroboratedDouble() {
        val e = LifecycleEvidence8189
        assertEquals(LifecycleEvidence8189.Evidence8189.UNPROVEN, e.evidence8189(true, false, 60.0))
        assertEquals(LifecycleEvidence8189.Evidence8189.UNPROVEN, e.evidence8189(false, true, 99.9))
        assertEquals(LifecycleEvidence8189.Evidence8189.SEALED_RUNNER_PROVEN_SETUP, e.evidence8189(true, true, 0.0))
        assertEquals(LifecycleEvidence8189.Evidence8189.PROVEN_PEAK, e.evidence8189(false, false, 100.0))
        // a broken basis is never a promotion
        assertEquals(LifecycleEvidence8189.Evidence8189.UNPROVEN, e.evidence8189(false, false, 200_000.0))
        assertFalse(e.holds8189(LifecycleEvidence8189.Evidence8189.UNPROVEN))
        assertTrue(e.holds8189(LifecycleEvidence8189.Evidence8189.PROVEN_PEAK))
        assertEquals("LIFECYCLE_HOLD_8189_PROVEN_PEAK_HARD_RISK",
            e.deferralReason8189(LifecycleEvidence8189.Evidence8189.PROVEN_PEAK, "STRICT_SL_-15"))
        assertEquals("LIFECYCLE_HOLD_8189_SEALED_RUNNER_PROVEN_SETUP_PROFIT_LOCK",
            e.deferralReason8189(LifecycleEvidence8189.Evidence8189.SEALED_RUNNER_PROVEN_SETUP, "TREASURY_TAKE_PROFIT_SWEEP"))
        assertTrue(e.regret8189(-12.0, -40.0))
        assertFalse(e.regret8189(-12.0, -12.5))
        assertFalse(e.regret8189(-12.0, 30.0))
        assertFalse(e.regret8189(Double.NaN, -40.0))
    }

    @Test fun unprovenLoserTakesItsLaneStopNeverTheFortyPercentFloor() {
        LifecycleEvidence8189.resetForTest8189()
        val ts = live("unproven8189", "DIAMOND_HANDS_RUNNER", price = 0.85, peak = 10.0)
        LifecycleEvidence8189.noteSetupProven8189(ts, false)
        assertFalse(LifecycleHold8065.holding8065(ts))
        for (r in listOf("STRICT_SL_-15", "TREASURY_STOP_LOSS_SWEEP", "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", "TREASURY_TAKE_PROFIT_SWEEP"))
            assertNull(r, LifecycleHold8065.defer8065(ts, r))
        assertFalse(LifecycleHold8065.partialHeld8065(ts, "SPIKE_CAPTURE_7943_T1_45PCT"))
        val stop = StopAuthority7887.stopMagFor(ts)
        assertTrue("lane doctrine stop, not the lifecycle floor: $stop", stop in 4.0..25.0)
        assertTrue(LifecycleEvidence8189.statusLine8189().contains("unprovenNotHeld="))
    }

    @Test fun provenRunnerKeepsItsHoldTierOneIsNeverHeldAndRegretIsCounted() {
        LifecycleEvidence8189.resetForTest8189()
        // superseded by V5.0.8363 — a +120 % peak now arms the green floor (max(net BE, 50 % of the peak) = +60 %): the
        // hold no longer defers a -15 % stop after it (the 8360 audit: +180 % peaks realised at -13 %).
        val peaked = live("peaked8363", "DIAMOND_HANDS_RUNNER", price = 0.85, peak = 120.0)
        assertTrue(LifecycleHold8065.holding8065(peaked))
        assertNull(LifecycleHold8065.defer8065(peaked, "STRICT_SL_-15"))
        // superseded by V5.0.8363 — with the green floor, the evidence hold defers only exits ABOVE the armed floor (a peak
        // under +25 % is an unproven loser and released by 8109; any peak at or above +20 % arms the floor). So the hold is
        // exercised at +70 % after a +120 % peak (floor +60 %): an ordinary exit there is still held and named.
        // V5.0.8370 — owner restored the 8114 trail: the floor at a +120 % peak is max(3a +48, trail +43) = +48 %; +70 % is held.
        val ts = live("proven8189", "DIAMOND_HANDS_RUNNER", price = 1.70, peak = 120.0)
        assertTrue(LifecycleHold8065.holding8065(ts))
        val named = LifecycleHold8065.defer8065(ts, "CORTEX_EXIT_7897_MOONSHOT_FWD-7PCT")
        assertNotNull(named)
        assertTrue(named!!, named.startsWith("LIFECYCLE_HOLD_8189_PROVEN_PEAK_"))
        assertEquals(LifecycleHold8065.FLOOR_PCT, StopAuthority7887.stopMagFor(ts), 0.0)
        for (r in listOf("RUG_DETECTED", "LIQUIDITY_DRAIN_EXIT", "RAPID_CATASTROPHE_STOP", "MANUAL_SELL", "FREEZE_AUTHORITY_FROZEN_EXIT"))
            assertNull(r, LifecycleHold8065.defer8065(ts, r))
        // the tier-1 passes above settled the episode (no regret); defer again, then release once price falls through the floor
        assertNotNull(LifecycleHold8065.defer8065(ts, "CORTEX_EXIT_7897_MOONSHOT_FWD-7PCT"))
        assertEquals(1, LifecycleEvidence8189.deferralOf8189("proven8189|1")?.first)
        ts.lastPrice = 0.55
        assertNull(LifecycleHold8065.defer8065(ts, "STRICT_SL_-45"))
        assertNull(LifecycleEvidence8189.deferralOf8189("proven8189|1"))
        assertTrue(LifecycleEvidence8189.statusLine8189(), LifecycleEvidence8189.statusLine8189().contains("regret=1"))
    }

    @Test fun sealedRunnerWithAProvenSetupIsHeldWithoutItThePositionTradesItsLane() {
        LifecycleEvidence8189.resetForTest8189()
        val ts = live("sealed8189", "DIAMOND_HANDS_RUNNER", price = 1.10, peak = 30.0)
        LifecycleEvidence8189.noteSetupProven8189(ts, false)
        assertFalse(LifecycleHold8065.holding8065(ts))
        LifecycleEvidence8189.noteSetupProven8189(ts, true)
        assertTrue(LifecycleHold8065.holding8065(ts))
        val quick = live("quick8189", "QUICK_FLIP", peak = 150.0)
        assertFalse("a quick contract never becomes a lifecycle hold", LifecycleHold8065.holding8065(quick))
    }

    @Test fun holdGateIsWiredBeforeEveryHoldingAuthority() {
        val l = src("engine/LifecycleHold8065.kt")
        val holding = l.substringAfter("internal fun holding8065(").substringBefore("fun keepOnStop8066(")
        val gate = holding.indexOf("LifecycleEvidence8189.holds8189(LifecycleEvidence8189.evidenceFor8189(ts, laneOf(ts)))")
        assertTrue(gate > 0)
        assertTrue("evidence before lane / ticket / once-held", gate < holding.indexOf("lifecycleLane8065(laneOf(ts))"))
        assertTrue(gate < holding.indexOf("RunnerTicket8073.isTicket8073(ts.mint)"))
        val defer = l.substringAfter("fun defer8065(").substringBefore("fun partialHeld8065(")
        assertTrue(defer.contains("LifecycleEvidence8189.noteDeferral8189(key(ts), named8189, g)"))
        assertTrue(defer.contains("LifecycleEvidence8189.noteRelease8189(key(ts), gross(ts, null))"))
        assertTrue(l.contains("\" · \" + LifecycleEvidence8189.statusLine8189()"))
    }

    // ── 2. profit banking ──

    @Test fun anUneconomicSliceEscalatesToAWholeProfitableExit() {
        val p = ProfitEscalation8189
        assertEquals(ProfitEscalation8189.Verdict8189.SLICE, p.verdict8189(false, 0.035, 0.029, 1.5, 0.0002, false))
        assertEquals(ProfitEscalation8189.Verdict8189.ESCALATE_FULL, p.verdict8189(true, 0.035, 0.029, 1.5, 0.0002, false))
        assertEquals("a runner keeps its residual", ProfitEscalation8189.Verdict8189.KEEP, p.verdict8189(true, 0.035, 0.029, 1.5, 0.0002, true))
        assertEquals("the whole position is not profitable after costs", ProfitEscalation8189.Verdict8189.KEEP,
            p.verdict8189(true, 0.0293, 0.029, 1.5, 0.0002, false))
        assertEquals(ProfitEscalation8189.Verdict8189.KEEP, p.verdict8189(true, Double.NaN, 0.029, 1.5, 0.0002, false))
        val reason = p.reason8189("SPIKE_CAPTURE_7943_T1_45PCT")
        assertTrue(reason.startsWith(ProfitEscalation8189.REASON_PREFIX_8189))
        assertTrue("the full exit is still checked on its own executable quote", ProfitExitTruth8163.profitTaking(reason))
        // superseded by V5.0.8337 — the escalated full exit reports applied=true / terminal=false under ESCALATED_FULL_8189
        // (owner's fix block 9b) so the spike rung is not re-armed; the escalation door itself is unchanged.
        assertTrue(src("engine/Executor.kt").contains("if (profitEscalated8189(ts, reason, wallet, walletBalance)) { receipt6566?.invoke(PartialSellReceipt6566(applied = true, terminal = false, authorityResult = \"ESCALATED_FULL_8189\")); return }"))
    }

    @Test fun profitLockBanksOnAHeldSellQuoteRepair() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("src7392 == com.lifecyclebot.engine.truth.HeldExitQuote8189.SOURCE_8189"))
    }

    // ── 3. marks for held positions ──

    @Test fun heldSellQuoteIsSizedOnWhatIsReallyHeldAndUsedOnlyWhileFresh() {
        val q = HeldExitQuote8189
        assertEquals(bi(800), q.heldQty8189(bi(1_000), bi(800), true))
        assertEquals(bi(1_000), q.heldQty8189(bi(1_000), bi(5_000), true))
        assertEquals("a wallet read at another scale never sizes it", bi(1_000), q.heldQty8189(bi(1_000), bi(800), false))
        assertNull(q.heldQty8189(BigInteger.ZERO, bi(800), true))
        assertNull(q.heldQty8189(BigInteger.ONE.shiftLeft(70), null, true))
        val now = 1_000_000L
        assertTrue(q.usable8189(HeldExitQuote8189.Quote8189(0.001, now - 3_000L, bi(5)), now))
        assertTrue(q.usable8189(HeldExitQuote8189.Quote8189(0.001, now - 16_000L, bi(5)), now))   // V5.0.8262: 8261's 16 s Fartcoin quote is a mark
        assertFalse(q.usable8189(HeldExitQuote8189.Quote8189(0.001, now - HeldExitQuote8189.QUOTE_FRESH_MS_8189 - 1L, bi(5)), now))
        assertFalse(q.usable8189(HeldExitQuote8189.Quote8189(0.0, now, bi(5)), now))
        assertFalse(q.usable8189(null, now))
        assertTrue(q.due8189(0L, now, false))
        assertFalse(q.due8189(now - 1_000L, now, false))
        assertFalse(q.due8189(0L, now, true))
        q.resetForTest8189()
        q.putForTest8189("m8189", HeldExitQuote8189.Quote8189(0.002, System.currentTimeMillis(), bi(10)))
        assertEquals(0.002, q.fresh8189("m8189")!!.priceUsd, 0.0)
    }

    @Test fun heldMarkChainUsesTheHeldQuoteWhenFeedsAndTheBatchQuoteFail() {
        val hot = src("engine/truth/HeldHotMarkAuthority7419.kt")
        assertTrue(hot.contains("if (ex?.price == null) HeldExitQuote8189.take8189(p.mint, p.remainingQtyRaw, p.quantityScale, now)?.let {"))
        // the held quote keeps the executable source the registry already verifies (no new trust path)
        assertTrue(hot.contains("px = it.priceUsd; source = EXEC_SOURCE_7876; observedAt8077 = it.observedAtMs"))
        val rep = src("engine/truth/MarkIdentityRepairAuthority7236.kt")
        assertTrue(rep.contains("} else if (heldQuoteRepair8189(mint)) {"))
        assertTrue(rep.contains("cache[mint] = Repaired(q.priceUsd, HeldExitQuote8189.SOURCE_8189, q.observedAtMs)"))
        // stamped at request time, never completion time; never writes a peak
        val hq = src("engine/truth/HeldExitQuote8189.kt")
        assertTrue(hq.contains("val q = Quote8189(estimate.priceUsd, minOf(askedAtMs, estimate.observedAtMs), qty, estimate.proceedsSol, estimate.source)"))
        assertFalse(hq.contains("peakGainPct"))
    }

    // ── 4. armed exits are not stalled by a wedged unit ──

    @Test fun aWedgedCloseWithNothingSignedIsReplaced() {
        assertTrue(ExitHotPath7809.wedgedClose8189(120_000L, false))
        assertFalse(ExitHotPath7809.wedgedClose8189(119_999L, false))
        assertFalse("a pending signature owns the unit", ExitHotPath7809.wedgedClose8189(600_000L, true))
        ExitHotPath7809.resetForTest()
        val k = "wedged8189"
        assertTrue(ExitHotPath7809.tryBegin(k, 1_000L))
        assertFalse(ExitHotPath7809.tryBegin(k, 60_000L, true, false))
        assertTrue(ExitHotPath7809.tryBegin(k, 121_000L, true, false))
        assertFalse(ExitHotPath7809.tryBegin(k, 400_000L, true, true))
        assertTrue(ExitHotPath7809.statusLine().contains("wedgedReplaced8189=1"))
        ExitHotPath7809.resetForTest()
    }

    // ── 5. per-position proof ──

    @Test fun everyCanonicalPositionIsComparedAndNamed() {
        val pp = PositionProof8189
        val chain = mapOf("a" to bi(100), "b" to bi(50))
        val out = pp.augmentChain8189(chain, setOf("a", "b", "c", "d"), mapOf("a" to bi(999), "c" to bi(70), "d" to BigInteger.ZERO))!!
        assertEquals("never overwrites the snapshot", bi(100), out["a"])
        assertEquals(bi(70), out["c"])
        assertNull("a missing read is never zero", out["d"])
        assertEquals(chain, pp.augmentChain8189(chain, setOf("a"), emptyMap()))
        assertEquals(mapOf("c" to bi(70)), pp.augmentChain8189(null, setOf("c"), mapOf("c" to bi(70))))
        assertEquals(PositionProof8189.Bucket8189.MATCHED, pp.bucket8189(bi(1_000), bi(1_000), true, false, false))
        assertEquals(PositionProof8189.Bucket8189.QTY_MISMATCH, pp.bucket8189(bi(1_000), bi(500), true, false, false))
        assertEquals(PositionProof8189.Bucket8189.NOT_ON_CHAIN, pp.bucket8189(bi(1_000), null, true, false, false))
        assertEquals(PositionProof8189.Bucket8189.CHAIN_UNREAD, pp.bucket8189(bi(1_000), null, false, false, false))
        assertEquals(PositionProof8189.Bucket8189.FROZEN, pp.bucket8189(bi(1_000), bi(1_000), true, true, false))
        assertEquals(PositionProof8189.Bucket8189.PENDING_TX, pp.bucket8189(bi(1_000), null, false, false, true))
        assertEquals(4 to 6, pp.coverage8189(List(4) { PositionProof8189.Bucket8189.MATCHED } + List(2) { PositionProof8189.Bucket8189.CHAIN_UNREAD }))
        assertEquals("FROZEN_UNSELLABLE", pp.readiness8189(true, true, true, 1L, true))
        assertEquals("SIGNED_SELL_PENDING", pp.readiness8189(false, true, true, 1L, true))
        assertEquals("SELL_IN_FLIGHT", pp.readiness8189(false, false, true, 1L, true))
        assertEquals("READY_NEEDS_BALANCE_READ", pp.readiness8189(false, false, false, null, true))
        assertEquals("READY_VENUE_UNKNOWN_LEGACY_ORDER", pp.readiness8189(false, false, false, 1_000L, false))
        assertEquals("READY", pp.readiness8189(false, false, false, 1_000L, true))
    }

    @Test fun proofTableIsPrintedThroughTheReconcileLineNotDumpText() {
        val r = src("engine/ReconcileRunner8144.kt")
        assertTrue(r.contains("PositionProof8189.chainFor8189(snap?.mapValues { it.value.raw }, lots.mapTo(HashSet()) { it.mint }, complete, nowMs)"))
        assertTrue(r.contains("PositionProof8189.block8189(nowMs)"))
        assertFalse(src("engine/PipelineHealthCollector.kt").contains("PositionProof8189"))
        val p = src("engine/PositionProof8189.kt")
        for (field in listOf(" wallet=", " canon=", " recon=", " cost=", " lineage=", " mark=", " hold=", " protect=", " route=", "coverage compared="))
            assertTrue(field, p.contains(field))
        assertTrue(src("engine/truth/OnChainCost8024.kt").contains("fun cachedVerdict8189(mint: String): String?"))
        assertNotNull(PositionProof8189.block8189())
    }

    // ── follow-up: realistic expected slippage in the profit truth ──

    @Test fun profitTruthExpectsRealisticSlippageNotTheMinOutCap() {
        val t = ProfitExitTruth8163
        assertEquals(100, t.expectedSlippageBps8189(null, 0, 500))
        assertEquals("measured lane slippage with n >= 5", 250, t.expectedSlippageBps8189(2.5, 6, 500))
        assertEquals("too few legs: 1.0%", 100, t.expectedSlippageBps8189(2.5, 4, 500))
        assertEquals("floor 0.5%", 50, t.expectedSlippageBps8189(0.1, 10, 500))
        assertEquals("never above the cap", 500, t.expectedSlippageBps8189(9.0, 10, 500))
        assertEquals(30, t.expectedSlippageBps8189(0.1, 10, 30))
        // a 0.0174 SOL slice quoted at 0.0186 SOL (+6.9% after impact), fixed fee 0.000205 SOL
        val slice = 0.0174
        val out = (0.0186 * 1e9).toLong()
        assertFalse("the 5% cap as the expectation refused it", t.clears(slice, out, 500, 0.000205))
        assertTrue(t.clears(slice, out, t.expectedSlippageBps8189(null, 0, 500), 0.000205))
        assertTrue(t.clears(slice, out, t.expectedSlippageBps8189(2.5, 6, 500), 0.000205))
        assertFalse("a quote that does not clear cost still refuses", t.clears(slice, (0.0176 * 1e9).toLong(), t.expectedSlippageBps8189(null, 0, 500), 0.000205))
        val p = src("engine/ProfitExitTruth8163.kt")
        assertEquals(3, p.split("expectedSlip8189(ts, slip)").size - 1)
        assertTrue("the quote / transaction minOut stays at the cap", p.contains("amountRaw = raw, slippageBps = slip)"))
        assertTrue(src("engine/truth/CostLedger7962.kt").contains("fun sellSidePct8189(laneRaw: String?): Pair<Double, Int>?"))
    }

    // ── follow-up: the stop route ──

    @Test fun aStopAsksJupiterFirstAndNeverRebuildsAFailedVenue() {
        val r = StopRoute8189
        for (x in listOf("TRAILING_STOP", "HARD_STOP_LOSS", "RAPID_CATASTROPHE_STOP", "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", "RUG_DETECTED"))
            assertTrue(x, r.stopExit8189(x))
        for (x in listOf("SPIKE_CAPTURE_7943_T1_45PCT", "FALLBACK_SHITCOIN_TIME_EXIT", ""))
            assertFalse(x, r.stopExit8189(x))
        assertTrue(r.jupiterFirst8189("TRAILING_STOP", true))
        assertFalse("a blocked aggregator keeps the native order", r.jupiterFirst8189("TRAILING_STOP", false))
        val now = 5_000_000L
        assertTrue(r.failureActive8189(now - 1_000L, now))
        assertFalse(r.failureActive8189(now - 90_000L, now))
        assertFalse(r.failureActive8189(null, now))
        assertFalse(r.raydiumViable8189(SellRouteEndpoint8144.Venue8144.PUMP_CURVE))
        assertTrue(r.raydiumViable8189(SellRouteEndpoint8144.Venue8144.PUMPSWAP))
        assertTrue(r.pastBudget8189(true, 1_000L, 0L, 11_001L))
        assertFalse(r.pastBudget8189(true, 1_000L, 0L, 11_000L))
        assertFalse("already broadcast", r.pastBudget8189(true, 1_000L, 2_000L, 60_000L))
        assertFalse("not a stop", r.pastBudget8189(false, 1_000L, 0L, 60_000L))
        r.resetForTest8189()
        r.noteVenueFailure8189("mintR8189", SellRouteEndpoint8144.Route8144.RAYDIUM, now)
        assertTrue(r.venueBlocked8189("mintR8189", SellRouteEndpoint8144.Route8144.RAYDIUM, now + 1_000L))
        assertFalse(r.venueBlocked8189("mintR8189", SellRouteEndpoint8144.Route8144.RAYDIUM, now + 90_000L))
        assertFalse(r.venueBlocked8189("mintR8189", SellRouteEndpoint8144.Route8144.JUPITER, now + 1_000L))
        assertTrue(r.skipRaydium8189("mintR8189", "TRAILING_STOP", now + 1_000L))
        r.resetForTest8189()
    }

    @Test fun stopRouteIsWiredIntoTheLiveSell() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("val exitPumpFirst6099 = exitPumpFirst8189(ts, reason)"))
        assertTrue(ex.contains("if (jupiterFirstFor8189(ts, reason)) return false  // V5.0.8189"))
        assertEquals(2, ex.split("StopRoute8189.skipRaydium8189(ts.mint, reason)").size - 1)
        assertEquals(2, ex.split("StopRoute8189.noteVenueFailure8189(ts.mint, com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Route8144.RAYDIUM)").size - 1)
    }
}
