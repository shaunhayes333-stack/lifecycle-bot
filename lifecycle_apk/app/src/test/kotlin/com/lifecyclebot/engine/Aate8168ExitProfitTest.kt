package com.lifecyclebot.engine

import java.io.File
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class Aate8168ExitProfitTest {
    @Test fun quoteMustCoverExactSliceEvenWhenMarkWasProfitable() {
        val cost = ProfitExitTruth8163.sliceCost(0.0291, BigInteger.valueOf(1000), BigInteger.valueOf(250))
        assertEquals(0.007275, cost!!, 1e-12)
        assertFalse(ProfitExitTruth8163.clears(cost, 7_282_000, 500))
        assertTrue(ProfitExitTruth8163.clears(cost, 8_500_000, 500))
        assertFalse(ProfitExitTruth8163.clears(cost, 7_500_000, 500))
        assertFalse(ProfitExitTruth8163.clears(null, 100_000_000, 500))
    }

    @Test fun profitMilestonesAreCoveredWithoutGatingRiskExits() {
        for (reason in listOf("PARTIAL_TAKE_PROFIT", "RUNNER_MILESTONE_8136_T2_70PCT",
            "TAIL_MILESTONE_400", "LIFECYCLE_PROFIT_TAKE2", "PEAK_CAPTURE"))
            assertTrue(reason, ProfitExitTruth8163.profitTaking(reason))
        for (reason in listOf("PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", "RAPID_CATASTROPHE_STOP",
            "HARD_STOP", "THIN_LIQ_EARLY_RUG_BACKSTOP_-10", "MANUAL", "ORPHAN_SWEEP"))
            assertFalse(reason, ProfitExitTruth8163.profitTaking(reason))
    }

    @Test fun reportKeepsUnknownEvidenceExplicitAndBoundsCounterDetails() {
        assertEquals("OUTSIDE_NATIVE_CAP", BotImprovementReport8168.reasonClass("NATIVE_MCAP_TOO_LOW"))
        assertEquals("DATA_MISSING_OR_STALE", BotImprovementReport8168.reasonClass("NATIVE_BIRTH_METADATA_HYDRATING"))
        assertEquals("UNSPECIFIED", BotImprovementReport8168.reasonClass(null))
        assertTrue(BotImprovementReport8168.counterSection(emptyMap(), listOf("EXIT_QUEUE_CAUSE_")).contains("not proof"))
        val labels = (1..30).associate { "LIFECYCLE/EXIT_QUEUE_CAUSE_$it" to it.toLong() }
        val rendered = BotImprovementReport8168.counterSection(labels, listOf("EXIT_QUEUE_CAUSE_"))
        assertTrue(rendered.startsWith("LIFECYCLE/EXIT_QUEUE_CAUSE_30=30"))
        assertTrue(rendered.contains("omittedKeys=6"))
    }

    @Test fun bluechipHunterDoesNotExcludeGraduatedTokensByAddressSuffix() {
        val row = com.lifecyclebot.engine.market.MarketSweep7297.Row(
            mint = "graduatedpump", symbol = "GRAD", name = "Graduated", priceUsd = 0.01,
            mcapUsd = 2_000_000.0, liquidityUsd = 200_000.0, volumeH1Usd = 100_000.0,
            volumeH24Usd = 1_000_000.0, priceChangeH1Pct = 5.0, txCountH1 = 200,
            holders = 2000, organicScore = 80.0, verified = true, ageHours = 48.0, providers = setOf("TEST"))
        val profile = com.lifecyclebot.engine.market.LaneHunter7297.profiles.first { it.lane == "BLUECHIP" }
        assertTrue(row.mcapUsd in profile.baseMin..profile.baseMax)
        assertTrue(profile.fits(row))
        assertTrue(profile.fits(row.copy(mint = "otherAddress")))
        assertFalse(profile.fits(row.copy(liquidityUsd = 0.0)))
        assertFalse(profile.fits(row.copy(liquidityUsd = Double.NaN)))
        assertFalse(5000.0 in profile.baseMin..profile.baseMax)
    }

    @Test fun staleFullHistoryCanBeRepairedButFreshOrInvalidDataCannotReplaceIt() {
        val now = 10_000_000L
        fun bars(end: Long) = (0..11).map { com.lifecyclebot.data.Candle(
            end - (11 - it) * 60_000L, 1.0 + it * 0.01, 1000.0, 100.0, 1000.0) }
        val stale = bars(now - 3_600_000L)
        val fresh = bars(now)
        assertTrue(SetupHistoryRepair8168.canReplace(stale, fresh, now))
        assertFalse(SetupHistoryRepair8168.canReplace(fresh, fresh, now))
        assertFalse(SetupHistoryRepair8168.canReplace(stale, stale, now))
        assertFalse(SetupHistoryRepair8168.canReplace(stale, fresh.reversed(), now))
        assertFalse(SetupHistoryRepair8168.canReplace(stale, fresh.map { it.copy(synthetic = true) }, now))
        assertFalse(SetupHistoryRepair8168.canReplace(stale, fresh.take(2), now))
        assertTrue(source("engine/DataOrchestrator.kt").contains("repairShape8168 = true"))
    }

    @Test fun aResidentClaimCannotRemoveOtherNativeReaders() {
        for (cap in listOf(5000.0, 100_000.0, 2_000_000.0, Double.NaN)) {
            val baseline = SpecialistBrainBridge7542.nativeScope8168(cap, emptySet())
            val scoped = SpecialistBrainBridge7542.nativeScope8168(cap, setOf("CYCLIC", "UNKNOWN_LANE"))
            assertTrue(scoped.containsAll(baseline))
            assertTrue("CYCLIC" in scoped)
            assertFalse("UNKNOWN_LANE" in scoped)
        }
        val all = com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.LANES
        assertEquals(all, SpecialistBrainBridge7542.nativeScope8168(100_000.0, all))
        val hunters = com.lifecyclebot.engine.market.LaneHunter7297.profiles.map { it.lane }.toSet()
        assertEquals(all, hunters)
    }

    private fun source(path: String): String = listOf("src/main/kotlin/com/lifecyclebot", "app/src/main/kotlin/com/lifecyclebot",
        "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot").map { File(it, path) }.first { it.exists() }.readText()

    @Test fun liveJupiterPathsCheckEconomicsBeforeBuilding() {
        val s = source("engine/Executor.kt")
        for ((start, end) in listOf("fun checkPartialSell(" to "private fun acquirePartialSellLock",
            "fun requestPartialSell(" to "private fun liveSell(",
            "private fun liveSellInScope7314(" to "fun sellOrphanedToken(")) {
            val path = s.substring(s.indexOf(start), s.indexOf(end, s.indexOf(start)))
            val guard = path.indexOf("ProfitExitTruth8163.acceptQuote(")
            assertTrue(start, guard >= 0)
            assertTrue(start, guard < path.indexOf("buildTxWithRetry("))
        }
    }

    @Test fun proofPollingKeepsUrgencyAndRaydiumUsesItsOwnProceeds() {
        assertTrue(source("engine/sell/BalanceProofPoller.kt").contains(
            "SellAmountAuthority.resolveForExit(entry.mint, w, entry.desiredExitReason)"))
        // Position-scoped proceeds and original quote identity now live in shared exit authority.
        val p = source("engine/ProfitExitTruth8163.kt")
        assertTrue(p.contains("if (!clearsRoute8239(ts, raw, outLamports, slip, route))"))
        assertTrue(p.contains("captureQuote8239(ts, raw, outLamports, route, observedAtMs)"))
        assertTrue(p.contains("quote.inputMint != ts.mint || quote.outputMint != JupiterApi.SOL_MINT"))
    }
}
