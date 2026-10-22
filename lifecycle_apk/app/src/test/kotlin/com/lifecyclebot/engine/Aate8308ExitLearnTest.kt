package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.sell.EmergencySellRoute8308
import com.lifecyclebot.engine.sell.EmergencySellRoute8308.Row8308
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.ExactStrategyPerformance7429
import com.lifecyclebot.engine.truth.LearningQuarantineGate6470
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.ProtectiveBasisProof8308
import com.lifecyclebot.engine.truth.RuntimeBasisSync8308
import com.lifecyclebot.network.SignedTxLedger8085
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8308 — emergency sell routing, POST_MIGRATION_HOLD admission proof, learning from chain-proven losses, and the
 * basis the creed / exits read. Pure rules with injected rows plus source wiring checks (no device numbers).
 */
class Aate8308ExitLearnTest {

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()
    private val now = 10_000_000L

    @Before fun setUp() {
        SignedTxLedger8085.resetForTest()
        EmergencySellRoute8308.resetForTest8308()
        ProtectiveBasisProof8308.resetForTest8308()
        RuntimeBasisSync8308.resetForTest8308()
        LearningQuarantineGate6470.resetForTest()
    }

    private fun row(route: String, state: String, side: String = "BUY", mint: String = "M", ageMs: Long = 60_000L) =
        Row8308(side, route, state, mint, now - ageMs, now - ageMs + 5_000L)

    // ── 1. emergency sell routing ──

    @Test fun pumpportal_at_40pct_land_rate_is_not_landing_while_jupiter_is() {
        val rows = List(2) { row("PUMPPORTAL", "CONFIRMED") } + List(3) { row("PUMPPORTAL", "EXPIRED_PROVEN") } +
            List(5) { row("JUPITER_ULTRA", "CONFIRMED") } + listOf(row("PUMPPORTAL", "ACCEPTED"))
        val pp = EmergencySellRoute8308.landRate8308(rows, "PUMPPORTAL", now)
        assertEquals(5, pp.resolved); assertEquals(40, pp.pct)
        assertTrue(EmergencySellRoute8308.poorLander8308(pp))
        val jup = EmergencySellRoute8308.landRate8308(rows, "JUPITER", now)
        assertEquals(5, jup.resolved); assertFalse(EmergencySellRoute8308.poorLander8308(jup))
        // too few resolved sends prove nothing
        assertFalse(EmergencySellRoute8308.poorLander8308(EmergencySellRoute8308.Rate8308(3, 0)))
        // rows outside the window do not count
        assertEquals(0, EmergencySellRoute8308.landRate8308(listOf(row("PUMPPORTAL", "EXPIRED_PROVEN", ageMs = 30L * 60_000L)), "PUMPPORTAL", now).resolved)
    }

    @Test fun only_emergencies_route_away_and_a_just_expired_sell_counts_for_its_mint() {
        val poor = List(1) { row("PUMPPORTAL", "CONFIRMED") } + List(4) { row("PUMPPORTAL", "EXPIRED_PROVEN") }
        assertNotNull(EmergencySellRoute8308.avoidWhy8308(poor, "X", "PUMPPORTAL", "CATASTROPHIC_HARD_BACKSTOP_-60", now))
        assertNotNull(EmergencySellRoute8308.avoidWhy8308(poor, "X", "PUMPPORTAL", "HARD_STOP", now))
        assertNull(EmergencySellRoute8308.avoidWhy8308(poor, "X", "PUMPPORTAL", "TRAILING_STOP", now))
        assertNull(EmergencySellRoute8308.avoidWhy8308(poor, "X", "PUMPPORTAL", "TAKE_PROFIT_T1", now))
        val expired = listOf(row("PUMPPORTAL", "EXPIRED_PROVEN", side = "SELL", mint = "R", ageMs = 40_000L))
        assertEquals("JUST_EXPIRED", EmergencySellRoute8308.avoidWhy8308(expired, "R", "PUMPPORTAL", "RUG_DRAIN", now))
        assertNull(EmergencySellRoute8308.avoidWhy8308(expired, "OTHER", "PUMPPORTAL", "RUG_DRAIN", now))
        assertTrue(EmergencySellRoute8308.justExpired8308(expired, "R", "PUMPPORTAL", now))
        assertFalse(EmergencySellRoute8308.justExpired8308(expired, "R", "JUPITER", now))
    }

    @Test fun an_expired_emergency_resends_via_jupiter_only_with_nothing_in_flight_and_no_quote_in_hand() {
        assertTrue(EmergencySellRoute8308.resendViaJupiter8308("CATASTROPHIC_EXIT", false, true, false))
        assertFalse(EmergencySellRoute8308.resendViaJupiter8308("CATASTROPHIC_EXIT", true, true, false))   // the ladder runs
        assertFalse(EmergencySellRoute8308.resendViaJupiter8308("CATASTROPHIC_EXIT", false, true, true))   // one signed sell in flight
        assertFalse(EmergencySellRoute8308.resendViaJupiter8308("CATASTROPHIC_EXIT", false, false, false))
        assertFalse(EmergencySellRoute8308.resendViaJupiter8308("TRAILING_STOP", false, true, false))
        assertEquals("JUPITER", EmergencySellRoute8308.family8308("JUPITER_ULTRA"))
        assertEquals("PUMPPORTAL", EmergencySellRoute8308.family8308("PumpPortal"))
    }

    @Test fun the_ledger_drives_the_live_pumpportal_skip_and_rescue_stays_allowed() {
        val m = "MINT8308"
        for (i in 0 until 5) {
            val sig = "PP_BUY_$i"
            SignedTxLedger8085.record(sig, "B64", "BH", SignedTxLedger8085.Intent("Q$i", "BUY", route = "PUMPPORTAL"))
            SignedTxLedger8085.transition(sig, if (i < 2) SignedTxLedger8085.State.CONFIRMED else SignedTxLedger8085.State.EXPIRED_PROVEN)
        }
        assertTrue(EmergencySellRoute8308.skipPumpPortalSell8308(m, "CATASTROPHIC_EXIT", "EXIT-DRAIN-EMERGENCY_7807"))
        assertTrue(EmergencySellRoute8308.keepAggregator8308(m, "HARD_STOP"))
        assertFalse(EmergencySellRoute8308.skipPumpPortalSell8308(m, "CATASTROPHIC_EXIT", "EXIT-DRAIN-RESCUE"))
        assertFalse(EmergencySellRoute8308.skipPumpPortalSell8308(m, "TAKE_PROFIT_T1", "EXIT"))
        assertTrue(EmergencySellRoute8308.statusLine8308().contains("PUMPPORTAL=2/5(NOT_LANDING)"))
    }

    @Test fun per_route_broadcast_to_confirm_is_measured_from_ledger_transitions() {
        assertEquals(3_000L to 4_000L, EmergencySellRoute8308.legs8308(1_000L, 3_500L, 4_000L, 8_000L))
        assertEquals(-1L to 4_500L, EmergencySellRoute8308.legs8308(0L, 3_500L, 0L, 8_000L))
        SignedTxLedger8085.record("J_SELL", "B64", "BH", SignedTxLedger8085.Intent("JM", "SELL", 5L, route = "JUPITER"))
        SignedTxLedger8085.transition("J_SELL", SignedTxLedger8085.State.SUBMIT_ATTEMPTED)
        SignedTxLedger8085.transition("J_SELL", SignedTxLedger8085.State.CONFIRMED)
        val line = EmergencySellRoute8308.statusLine8308()
        assertTrue(line, line.contains("NORM|JUPITER|b2c=n1"))
        assertTrue(line, line.contains("NORM|JUPITER|CONFIRMED=1"))
    }

    @Test fun executor_wires_the_emergency_route_fresh_blockhash_and_expired_resend() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("EmergencySellRoute8308.skipPumpPortalSell8308(ts.mint, exitReason8168, labelTag)) return null"))
        assertTrue(exec.contains("EmergencySellRoute8308.keepAggregator8308(ts.mint, reason)) return false"))
        assertTrue(exec.contains("val sellTx8308 = PumpLanding8288.freshen8288(wallet, built.txBase64, \"SELL\")"))
        assertTrue(exec.contains("pumpEnv7311?.txBase64 ?: sellTx8308,"))
        assertTrue(exec.contains("if (sig == null) sig = raceOrResend8308(ts, wallet, c, race8083, reason, quote?.outAmount, fastLand7980, sellTradeKey, confirmedSellUiQty)"))
        assertTrue(src("network/SignedTxLedger8085.kt").contains("EmergencySellRoute8308.onSellState8308("))
        assertTrue(src("engine/sell/StopRoute8189.kt").contains("EmergencySellRoute8308.statusLine8308()"))
    }

    // ── 2. POST_MIGRATION_HOLD needs migration + depth + sell-side proof ──

    private fun pmh(migrated: Boolean = true, pxPeak: Double = 0.85, dd: Double = 12.0, top: Double = 22.0,
                    devSold: Double = 0.0, largest: Double = 20.0, rugger: Boolean = false, rugRate: Double = Double.NaN) =
        LanePlaybook7907.F(
            age = 25.0, runup = 40.0, pxPeak = pxPeak, dd = dd, bp = 62.0, liq = 18_000.0, mcap = 23_000.0, top = top,
            chg5m = 2.0, chg1h = 5.0, holderGrowth = Double.NaN, volAccel = Double.NaN, planSetup = "", launchPhase = "",
            tapeDevSold = devSold, tapeLargest = largest, devKnownRugger = rugger, devRugRate = rugRate, migrated8308 = migrated,
        )

    private fun fires(f: LanePlaybook7907.F) = LanePlaybook7907.matches("MOONSHOT", f)!!.any { it.id == "POST_MIGRATION_HOLD" }

    @Test fun post_migration_hold_fires_only_with_proof() {
        assertTrue(fires(pmh()))
        assertTrue(LanePlaybook7907.postMigrationProven8308(pmh()))
        assertFalse("not migrated", fires(pmh(migrated = false)))
        assertFalse("collapsing from the migration peak (2bNrYy shape)", fires(pmh(pxPeak = 0.35, dd = 65.0)))
        assertFalse("concentrated holders", fires(pmh(top = 45.0)))
        assertFalse("dev sold", fires(pmh(devSold = 1.0)))
        assertFalse("one buyer dominates", fires(pmh(largest = 50.0)))
        assertFalse("known rugger", fires(pmh(rugger = true)))
        assertFalse("rugging creator", fires(pmh(rugRate = 0.6)))
        // unknown is not proof: no tape (PumpSwap data refused) / no holder read fails closed
        assertFalse(fires(pmh(devSold = Double.NaN)))
        assertFalse(fires(pmh(top = Double.NaN)))
        assertFalse(fires(pmh(pxPeak = Double.NaN)))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("migrated8308 = try { ts.tokenMap.migratedOrGraduated }"))
    }

    // ── 3. chain-proven losses are learned ──

    private fun protective(remaining: Long = 1_000_000L, original: Long = 1_000_000L, sold: Double = 0.0,
                           reason: String = "BASIS_UNCERTAIN_7807:TX_TRUTH_DEFERRED:x",
                           life: CanonicalPositionAuthority6441.Lifecycle = CanonicalPositionAuthority6441.Lifecycle.QUARANTINED) =
        CanonicalPositionAuthority6441.Position(
            positionId = "P8308", mode = "live", mint = "M", symbol = "S", lane = "MOONSHOT", runId = "r", openedAtMs = 1L,
            entryCostSol = 0.0, remainingQtyRaw = BigInteger.valueOf(remaining), originalQtyRaw = BigInteger.valueOf(original),
            soldCostBasisSol = sold, realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = 0.0, tokenDecimals = 6,
            lifecycle = life, lastMutationMs = 1L, quarantineReason = reason,
        )

    @Test fun a_late_buy_proof_yields_tx_meta_economics_and_only_never_sold_rows_promote() {
        val e = ProtectiveBasisProof8308.buyEconomics8308(true, BigInteger.valueOf(1_000_000L), 6, 31_000_000L, 1_000_000L, 1.0, 200.0)!!
        assertEquals(0.030, e.costSol, 1e-12)
        assertEquals(0.001, e.feeSol, 1e-12)
        assertEquals(6.0, e.entryUsd, 1e-9)          // 0.03 SOL / 1 token x $200
        assertNull(ProtectiveBasisProof8308.buyEconomics8308(false, BigInteger.valueOf(1_000_000L), 6, 31_000_000L, 1_000_000L, 1.0, 200.0))
        assertNull(ProtectiveBasisProof8308.buyEconomics8308(true, BigInteger.ZERO, 6, 31_000_000L, 1_000_000L, 1.0, 200.0))
        assertNull("no observed SOL/USD, no entry price", ProtectiveBasisProof8308.buyEconomics8308(true, BigInteger.valueOf(1_000_000L), 6, 31_000_000L, 1_000_000L, 1.0, 0.0))
        assertTrue(ProtectiveBasisProof8308.promotable8308(protective()))
        assertFalse("partly sold: slice proceeds lost", ProtectiveBasisProof8308.promotable8308(protective(remaining = 400_000L)))
        assertFalse(ProtectiveBasisProof8308.promotable8308(protective(sold = 0.01)))
        assertFalse(ProtectiveBasisProof8308.promotable8308(protective(reason = "FROZEN_TOKEN_ACCOUNT_7253")))
        assertFalse(ProtectiveBasisProof8308.promotable8308(protective(life = CanonicalPositionAuthority6441.Lifecycle.CLOSED)))
        assertTrue(ProtectiveBasisProof8308.sameInventory8308(protective(), e))
        assertFalse(ProtectiveBasisProof8308.sameInventory8308(protective(remaining = 1_500_000L, original = 1_500_000L), e))
        assertEquals(0, ProtectiveBasisProof8308.pendingCount8308())
    }

    @Test fun only_a_proven_basis_uncertain_learning_hold_is_released() {
        LearningQuarantineGate6470.quarantinePositionId("PB", "BASIS_UNCERTAIN_7807:TX_TRUTH_DEFERRED")
        LearningQuarantineGate6470.quarantinePositionId("PC", "LIVE_SELL_CANONICAL_POSITION_REJECTED_6486")
        assertTrue(LearningQuarantineGate6470.releaseProvenBasis8308("PB"))
        assertFalse(LearningQuarantineGate6470.isQuarantined("PB", null))
        assertFalse(LearningQuarantineGate6470.releaseProvenBasis8308("PC"))
        assertTrue(LearningQuarantineGate6470.isQuarantined("PC", null))
    }

    @Test fun protective_buys_keep_identity_and_get_proved_before_a_no_economics_close() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("protectiveIdentity8308(ts, positionIdHint, signature, intent8308, wallet8308)"))
        assertTrue(exec.contains("verifySig, \"TX_TRUTH_DEFERRED:\$stage\", sealedIntent7835, verifyWallet)"))
        assertTrue(exec.contains("ProtectiveBasisProof8308.note8308(positionIdHint, ts.mint, signature, wallet, intent.candidateVersion)"))
        val sfc = src("engine/sell/SellFinalizationCoordinator.kt")
        val proof = sfc.indexOf("ProtectiveBasisProof8308.beforeProtectiveSell8308(intent.mint)")
        val close = sfc.indexOf("applyProtectiveSellFill7807(intent.mint")
        assertTrue(proof in 1 until close)
        assertTrue(src("engine/truth/FinalizedBusConsumerBridge6465.kt").contains("retired8308=[\$retired8308]"))
    }

    @Test fun exact_strategy_refusals_are_counted_by_cause() {
        val env = CanonicalFinalizedTradeBus6464.Envelope(tradeId = "T8308", atMs = 1L, realizedPnlSol = -0.02, realizedReturnPct = -80.0,
            mint = "M", lane = "MOONSHOT", terminal = false)
        assertFalse(ExactStrategyPerformance7429.record(env))
        assertTrue(ExactStrategyPerformance7429.refusalLine8308().contains("NOT_TERMINAL_OR_INELIGIBLE"))
    }

    // ── 4. basis ──

    @Test fun the_creed_scoreboard_reads_marked_wallet_equity_not_free_cash() {
        assertEquals(0.3481, LiveRiskPolicy7807.pickScoreboardEquity8308(0.3481, 0.1493), 1e-12)
        assertEquals(0.1493, LiveRiskPolicy7807.pickScoreboardEquity8308(Double.NaN, 0.1493), 1e-12)
        assertEquals(0.1493, LiveRiskPolicy7807.pickScoreboardEquity8308(0.0, 0.1493), 1e-12)
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("noteEquity8057(scoreboardEquity8308(equity), nowMs)"))
    }

    @Test fun exits_take_the_canonical_fill_entry_when_runtime_drifts() {
        assertEquals(6.72e-4, RuntimeBasisSync8308.repairedEntry8308(5.93e-4, 6.72e-4, true)!!, 1e-15)
        assertNull("within 2%", RuntimeBasisSync8308.repairedEntry8308(6.70e-4, 6.72e-4, true))
        assertNull("no clean chain basis: untouched", RuntimeBasisSync8308.repairedEntry8308(5.93e-4, 6.72e-4, false))
        assertEquals(6.72e-4, RuntimeBasisSync8308.repairedEntry8308(0.0, 6.72e-4, true)!!, 1e-15)
        assertTrue(RuntimeBasisSync8308.DIVERGENCE_FRAC_8308 > 0.0)
        assertTrue(src("engine/Executor.kt").contains("RuntimeBasisSync8308.sync8308(ts)"))
    }
}
