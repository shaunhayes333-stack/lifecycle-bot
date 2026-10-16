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
        val s = source("engine/Executor.kt")
        assertTrue(s.contains("rayPlan7311.rawAmount, built7311.outLamports, raySlip7311"))
        assertTrue(s.contains("race.rawAmount, built.outLamports"))
        assertTrue(s.contains("tokenUnits ?: 0L, jupiter, releaseLocks = false"))
    }
}
