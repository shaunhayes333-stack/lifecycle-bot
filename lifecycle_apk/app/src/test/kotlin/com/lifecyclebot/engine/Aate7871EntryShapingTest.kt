package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import com.lifecyclebot.engine.truth.LaunchEntryShaping7871
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7871EntryShapingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun frozen() = EntryStrategySnapshot6450.Snapshot(
        positionId = "", mint = "MINT_P", entryLane = "MOONSHOT", entryStrategyPid = "",
        entryTactic = "UNKNOWN", entryRiskProfile = "", entryExitProfile = "", entrySource = "PUMP",
        entryScore = 61, entryLiquiditySol = 0.0, entryMarketCapUsd = 9_000.0,
        entryTimestampMs = 1L, entryThresholdSnapshot = "", entryPriceUsd = 0.00001,
    )

    @Test fun promotedSnapshotIsReKeyedToThePromotedPosition() {
        val s = promotedEntrySnapshot7871(frozen(), "LIVE:MINT_P:run", 0.00002)
        assertEquals("LIVE:MINT_P:run", s?.positionId)
        assertEquals("MOONSHOT", s?.entryLane)
        assertEquals(0.00002, s!!.entryPriceUsd, 1e-12)
        assertEquals(0.00001, promotedEntrySnapshot7871(frozen(), "PID", Double.NaN)!!.entryPriceUsd, 1e-12)
        assertNull("nothing is inferred without a frozen snapshot", promotedEntrySnapshot7871(null, "PID", 1.0))
        assertNull(promotedEntrySnapshot7871(frozen(), "", 1.0))
    }

    @Test fun pendingBindCarriesTheFrozenSnapshot() {
        LivePendingAttempt7868.bind("MINT_S", "attempt-9", "CASHGEN", nowMs = 1_000L, entry7871 = frozen())
        assertEquals("MINT_P", LivePendingAttempt7868.take("MINT_S", nowMs = 2_000L)?.entry7871?.mint)
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("entry7871 = pendingEntrySnapshot7871(intent, lane7871)"))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt").contains("ENTRY_SNAPSHOT_FROM_WALLET_PROMOTION_7871"))
    }

    @Test fun confirmedLiveSellStampsTheSellStage() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("if (result == SellResult.CONFIRMED) liveSellConfirmedStage7871(ts.mint)"))
        val body = exec.substringAfter("private fun liveSellConfirmedStage7871").substringBefore("private val liveSellReservedPid7317")
        assertTrue(body.contains("\"SELL_CONFIRMED\""))
    }

    @Test fun cellShapingIsBoundedAndWeightedByEvidence() {
        assertEquals(1.0, LaunchEntryShaping7871.cellSizeMult(40.0, 5), 1e-9)
        // EXPANDING|FLOW_OK|CONC_ONE n=15 ev15 +11%: modest size-up.
        val up = LaunchEntryShaping7871.cellSizeMult(11.0, 15)
        assertTrue(up > 1.0 && up < 1.10)
        // A -15% cell at full weight sizes down, never under the floor.
        val down = LaunchEntryShaping7871.cellSizeMult(-15.0, 40)
        assertTrue(down < 0.75 && down >= LaunchEntryShaping7871.MIN_MULT)
        assertEquals(LaunchEntryShaping7871.MAX_MULT, LaunchEntryShaping7871.cellSizeMult(200.0, 100), 1e-9)
        assertEquals(LaunchEntryShaping7871.MIN_MULT, LaunchEntryShaping7871.combine(0.5, 0.8, true), 1e-9)
        assertEquals(0.8, LaunchEntryShaping7871.flowSizeMult("FLOW_WEAK"), 1e-9)
        assertEquals(1.0, LaunchEntryShaping7871.flowSizeMult("FLOW_OK"), 1e-9)
    }

    @Test fun sizeDownNeverTakesAnExecutableRequestUnderTheMinimum() {
        assertEquals(0.05, LaunchEntryShaping7871.shapedSize(0.05, 0.5, 0.05), 1e-12)
        assertEquals(0.07, LaunchEntryShaping7871.shapedSize(0.10, 0.7, 0.05), 1e-12)
        assertEquals(0.12, LaunchEntryShaping7871.shapedSize(0.10, 1.2, 0.05), 1e-12)
        assertTrue(src("engine/FinalDecisionGate.kt").contains("var riskSized = minOf(shaped7871, depthCap, curveCap)"))
    }

    @Test fun tooFewBarsAdmitsOnlyWhenEveryQualityReadIsClean() {
        assertNull(LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 0, "FLOW_OK", "CONC_BROAD", 60, false))
        assertEquals("WAITS_NEGATIVE", LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 0, "FLOW_OK", "CONC_BROAD", 60, true))
        assertEquals("LIQ", LaunchEntryShaping7871.tooFewBarsRefusal(2_000.0, 0, "FLOW_OK", "CONC_BROAD", 60, false))
        assertEquals("SAFETY", LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 1, "FLOW_OK", "CONC_BROAD", 60, false))
        assertEquals("FLOW", LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 0, "FLOW_SELL", "CONC_BROAD", 60, false))
        assertEquals("FLOW", LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 0, null, "CONC_BROAD", 60, false))
        assertEquals("CONCENTRATION", LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 0, "FLOW_OK", "CONC_ONE", 60, false))
        assertEquals("CONFIDENCE", LaunchEntryShaping7871.tooFewBarsRefusal(8_000.0, 0, "FLOW_OK", "CONC_BROAD", null, false))
        assertTrue(src("engine/truth/TradePlan7739.kt").contains("read.why == \"TOO_FEW_BARS\" && LaunchEntryShaping7871.admitTooFewBars(ts, nowMs)"))
    }

    @Test fun specialistSealReusesTheFdgIntentAndNamesFailures() {
        val seal = src("engine/SpecialistPreauthSeal7834.kt")
        val ensure = seal.substringAfter("fun ensure(").substringBefore("fun sealOnce7840")
        assertTrue(ensure.contains("ExecutableOpenGate.reuseSealedIntent7871("))
        val reuse = src("engine/ExecutableOpenGate.kt").substringAfter("internal fun reuseSealedIntent7871").substringBefore("private fun noteFdgAllowWithoutOwnIntent7868")
        assertTrue(reuse.contains("it.resolvedSize <= maxSizeSol + 1e-9"))
        assertTrue(reuse.contains("st.hardNoReasons.isNotEmpty()"))
        assertTrue(src("engine/TradeAuthorizer.kt").contains("TRADE_AUTH_SEAL_FAILED_REASON_7871_"))
    }
}
