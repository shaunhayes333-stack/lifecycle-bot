package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7809 — FunnelSizingIntent: SIZING_CHOKED / INTENT_CHOKED / DEAD /
 * phantom CYCLIC stages in the specialist causal funnel.
 */
class Aate7809FunnelSizingIntentTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @After fun tearDown() { SpecialistCausalFunnel6625.resetForTest() }

    private fun stampToMark(lane: String, mint: String, extra: List<Pair<SpecialistCausalFunnel6625.Stage, String>> = emptyList(), discover: Boolean = true) {
        val key = SpecialistCausalFunnel6625.CausalKey("1", "PAPER", mint, lane, 6551L, "$mint:7809:$lane")
        if (discover) SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.QUALIFY, "QUALIFIED")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.OWNER, "OWNER_SELECTED")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FDG, "FDG_ALLOW")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
        extra.forEach { (stage, outcome) -> SpecialistCausalFunnel6625.stamp6625(key, stage, outcome) }
    }

    private fun livenessRow(lane: String): String =
        ToolkitSignalSheet.designatedRoleLivenessReport6599().lines().first { it.startsWith("$lane runtimeAlive=") }

    @Test fun namedRefusalReplacesGenericSizingChoke() {
        stampToMark("CASHGEN", "mint7809refusal")
        ToolkitSignalSheet.recordPreSizeRefusal7809("CASH_GEN", "SIZE_BELOW_MIN_RISK_TOO_WIDE_7807")
        val row = livenessRow("CASHGEN")
        assertTrue(row, row.contains("rawStatus7809=SIZING_CHOKED"))
        assertTrue(row, row.contains("status=REFUSED_BEFORE_SIZE_7809"))
        assertTrue(row, row.contains("SIZE_BELOW_MIN_RISK_TOO_WIDE_7807=1"))
    }

    @Test fun sizeWithoutDiscoverIsALineageGapNotASizingChoke() {
        // A second, fully discovered candidate keeps the lane out of DEAD.
        stampToMark("DIP_HUNTER", "mint7809observed")
        stampToMark(
            "DIP_HUNTER", "mint7809lineage",
            extra = listOf(SpecialistCausalFunnel6625.Stage.SIZE to "SIZED_EXECUTABLE"),
            discover = false,
        )
        val row = livenessRow("DIP_HUNTER")
        assertTrue(row, row.contains("rawStatus7809=SIZING_CHOKED"))
        assertTrue(row, row.contains("status=SIZE_LINEAGE_INCOMPLETE_7809"))
    }

    @Test fun refusalReasonIsSanitisedToItsCode() {
        ToolkitSignalSheet.recordPreSizeRefusal7809("EXPRESS", "AUTH_FINALITY_EXEC_OPEN_BLOCKED:detail with spaces")
        stampToMark("EXPRESS", "mint7809express")
        val row = livenessRow("EXPRESS")
        assertTrue(row, row.contains("AUTH_FINALITY_EXEC_OPEN_BLOCKED=1"))
        assertFalse(row, row.contains("detail with spaces"))
    }

    @Test fun refusalProducersAreWired() {
        val auth = src("engine/TradeAuthorizer.kt")
        assertTrue(auth.contains("ToolkitSignalSheet.recordPreSizeRefusal7809(requestedBook.name, \"AUTH_\$reason\")"))
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("noteLivePreSizeRefusal7809(intent.canonicalLane, intent.attemptId, reason)"))
        val helper = exec.substringAfter("private fun noteLivePreSizeRefusal7809(").substringBefore("private fun stampLiveEntryMark7790(")
        assertTrue(helper.contains("\"SIZE_BELOW_MIN_RISK_TOO_WIDE_7807\""))
        assertTrue(helper.contains("ToolkitSignalSheet.recordDeskStage(lane, \"SIZE_REJECT\", attemptId)"))
        assertFalse(helper.contains("SIZED_EXECUTABLE"))
        val resolver = src("engine/truth/OrderSizeResolver6441.kt")
        assertTrue(resolver.contains("recordPreSizeRefusal7809(laneName, \"SIZE_\${res.reason}\")"))
    }

    @Test fun compounderLiveLegKeepsTheOwnerLane() {
        val exec = src("engine/Executor.kt")
        val treasury = exec.substringAfter("fun treasuryBuy(").substringBefore("fun blueChipBuy(")
        assertTrue(treasury.contains("layerTag = paperLayerTag,"))
        assertFalse(treasury.contains("layerTag = \"TREASURY\","))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("paperLayerTag = compounderLane7614,"))
    }

    @Test fun qualityAndBluechipReadTheHolderEvidenceChain() {
        val bot = src("engine/BotService.kt")
        val helper = bot.substringAfter("private fun laneTopHolderPct7809(").substringBefore("private fun cyclicPolicyAllows7809(")
        assertTrue(helper.contains("ts.safety.topHolderPct.takeIf { it >= 0.0 }"))
        assertTrue(helper.contains("ts.tokenMap.topHolderConcentrationPct"))
        // the conservative live fallback for genuinely unknown concentration stays
        assertTrue(helper.contains("isLive()) 50.0 else 20.0"))
        val quality = bot.substringAfter("QualityTraderAI.evaluate(").substringBefore("isMeme = false")
        assertTrue(quality.contains("topHolderPct = laneTopHolderPct7809(ts)"))
        val blue = bot.substringAfter("BlueChipTraderAI.evaluate(").substringBefore("volatility = ts.volatility")
        assertTrue(blue.contains("topHolderPct = laneTopHolderPct7809(ts)"))
    }

    @Test fun disabledCyclicIsNotElectedAsCyclePrimary() {
        val bot = src("engine/BotService.kt")
        val election = bot.substringAfter("private fun canonicalCycleLaneFor(").substringBefore("private fun sealedSpecialistAttempt7468(")
        assertEquals(6, Regex("cyclicPolicyAllows7809\\(").findAll(election).count())
        val helper = bot.substringAfter("private fun cyclicPolicyAllows7809(").substringBefore("private fun executionBookForLane6494(")
        assertTrue(helper.contains("CyclicTradeEngine.isEnabled()"))
        assertTrue(helper.contains("if (canon != \"CYCLIC\") return true"))
    }

    @Test fun holdingLaneIsNotReportedDeadAndSniperIdentityPredicateIsKept() {
        val t = src("engine/ToolkitSignalSheet.kt")
        val b = t.substringAfter("fun designatedRoleLivenessReport6599()")
        assertTrue(b.contains("status == \"DEAD\" && openOnLane7809 > 0 -> \"HOLDING_NO_FRESH_DISCOVERY_7809\""))
        assertTrue(b.contains("status=\$reportedStatus7809 rawStatus7809=\$status"))
        val contract = src("engine/LaneEntryContract6342.kt")
        assertTrue(contract.contains("fun sniperLaunchIdentityRefusal7807("))
    }
}
