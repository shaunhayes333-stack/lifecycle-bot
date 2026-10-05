package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7819 — PaperSizingWallet.
 *
 * 1. A LIVE-era SIZE_NO_WALLET refusal must not be reported on PAPER lanes
 *    (the service is recreated in the same process on a mode switch).
 * 2. In paper the resolver names an empty paper ledger, never "NO_WALLET".
 * 3. Bot holdings in the LIVE wallet get a protective owner from tracker
 *    evidence when the live wallet cache is cold (PAPER), and the report says
 *    loudly that live exits do not run in PAPER.
 */
class Aate7819PaperSizingWalletTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @After fun tearDown() {
        SpecialistCausalFunnel6625.resetForTest()
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
    }

    private fun stampToMark(lane: String, mint: String, mode: String) {
        val key = SpecialistCausalFunnel6625.CausalKey("1", mode, mint, lane, 6551L, "$mint:7819:$lane")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.QUALIFY, "QUALIFIED")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.OWNER, "OWNER_SELECTED")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FDG, "FDG_ALLOW")
        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
    }

    private fun livenessRow(lane: String): String =
        ToolkitSignalSheet.designatedRoleLivenessReport6599().lines().first { it.startsWith("$lane runtimeAlive=") }

    @Test fun liveEraNoWalletRefusalIsNotReportedOnPaperLanes() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = false)
        ToolkitSignalSheet.recordPreSizeRefusal7809("TREASURY", "SIZE_NO_WALLET")

        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
        stampToMark("TREASURY", "mint7819paper", "PAPER")
        val paperRow = livenessRow("TREASURY")
        assertFalse(paperRow, paperRow.contains("SIZE_NO_WALLET"))
        assertFalse(paperRow, paperRow.contains("status=REFUSED_BEFORE_SIZE_7809"))

        // The same refusal is still the truth for the LIVE runtime.
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = false)
        val liveRow = livenessRow("TREASURY")
        assertTrue(liveRow, liveRow.contains("SIZE_NO_WALLET=1"))
    }

    @Test fun paperRefusalsStillReportInPaper() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
        stampToMark("MANIPULATED", "mint7819manip", "PAPER")
        ToolkitSignalSheet.recordPreSizeRefusal7809("MANIPULATED", "SIZE_PAPER_CASH_EMPTY_7819")
        val row = livenessRow("MANIPULATED")
        assertTrue(row, row.contains("status=REFUSED_BEFORE_SIZE_7809"))
        assertTrue(row, row.contains("SIZE_PAPER_CASH_EMPTY_7819=1"))
    }

    @Test fun resolverNamesEmptyPaperLedgerAndKeepsLiveNoWallet() {
        val r = src("engine/truth/OrderSizeResolver6441.kt")
        assertTrue(r.contains("!executable && authoritativeCash <= 0.0 -> if (paperMode) \"PAPER_CASH_EMPTY_7819\" else \"NO_WALLET\""))
        // Paper affordability still reads the paper capital authority, live the wallet.
        assertTrue(r.contains("val authoritativeCash = if (paperMode) PaperCapitalAuthority6577.cashSol().coerceAtLeast(0.0) else walletSol"))
        // The capital-refusal counter still sees both.
        assertTrue(r.contains("reason == \"NO_WALLET\" || reason == \"PAPER_CASH_EMPTY_7819\""))
    }

    @Test fun paperAlarmNamesUnmanagedAndIdleProtectedLiveHoldings() {
        val none = PipelineHealthCollector.paperModeLiveHoldingsAlarm7819(emptyList(), emptyList())
        assertEquals("", none)

        val unmanaged = PipelineHealthCollector.paperModeLiveHoldingsAlarm7819(
            listOf("BaDjVCpAmint", "gotMd6mpmint"), emptyList(),
        )
        assertTrue(unmanaged, unmanaged.contains("LIVE WALLET HAS 2 UNMANAGED BOT HOLDINGS"))
        assertTrue(unmanaged, unmanaged.contains("switch to live or sell manually: BaDjVCpAmint,gotMd6mpmint"))

        val protectedIdle = PipelineHealthCollector.paperModeLiveHoldingsAlarm7819(
            listOf("BaDjVCpAmint"), listOf("BaDjVCpAmint", "gotMd6mpmint"),
        )
        assertTrue(protectedIdle, protectedIdle.contains("LIVE WALLET HAS 1 UNMANAGED BOT HOLDINGS"))
        assertTrue(protectedIdle, protectedIdle.contains("LIVE WALLET HAS 1 BOT HOLDINGS UNDER PROTECTIVE LIVE OWNERSHIP_7819"))
        assertTrue(protectedIdle, protectedIdle.contains("sell manually: gotMd6mpmint"))
    }

    @Test fun healProtectsFromTrackerEvidenceWhenTheLiveCacheIsCold() {
        val rec = src("engine/LiveCanonicalRecovery6686.kt")
        val heal = rec.substringAfter("fun requestAdoptionAsync7718(").substringBefore("internal fun protectUnadoptedBotHoldings7807(")
        assertTrue(heal.contains("trackerHoldings7819(due.filter { it !in subset.keys })"))
        assertTrue(heal.contains("protectUnadoptedBotHoldings7807(subset) + protectUnadoptedBotHoldings7807(trackerOnly7819)"))
        // Priced adoption stays on the on-chain read; tracker evidence is protective only.
        assertTrue(heal.contains("val n = recoverWalletSnapshot(BotService.status, subset)"))
        assertFalse(heal.contains("recoverWalletSnapshot(BotService.status, trackerOnly7819)"))
        // A complete fresh read that omits a mint proves it gone.
        assertTrue(heal.contains("if (completeRead7819) emptyMap<String, CanonicalTokenAmount>() else"))
        // No buy, no paper ledger, no sell is reached from the heal.
        assertFalse(heal.contains("PaperAccountLedger6430"))
        assertFalse(heal.contains("liveBuy"))
    }

    @Test fun coverageSectionLeftDumpTextAndAlarmsOnlyInPaper() {
        val phc = src("engine/PipelineHealthCollector.kt")
        assertTrue(phc.contains("try { sb.append(botBuyCoverageSection7819()) } catch (_: Throwable) {}"))
        val section = phc.substringAfter("private fun botBuyCoverageSection7819(): String {").substringBefore("internal fun paperModeLiveHoldingsAlarm7819(")
        assertTrue(section.contains("if (paper7819) sb.append(paperModeLiveHoldingsAlarm7819("))
        assertTrue(section.contains("protectiveInventory7807(\"live\")"))
    }
}
