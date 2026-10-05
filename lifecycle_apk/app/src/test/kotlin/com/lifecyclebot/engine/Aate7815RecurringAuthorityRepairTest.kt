package com.lifecyclebot.engine

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Aate7815RecurringAuthorityRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun standalone_specialists_stamp_ticket_before_executor() {
        val bot = src("engine/BotService.kt")
        fun check(lane: String, attempt: String, execCall: String) {
            val ticket = bot.indexOf("recordDeskStage(\"$lane\", \"TICKET\", $attempt)")
            val exec = bot.indexOf(execCall)
            assertTrue(ticket >= 0, "missing ticket stamp for $lane")
            assertTrue(exec > ticket, "$lane ticket/predecessors must be stamped before Executor")
        }
        check("SHITCOIN", "shitcoinAttemptId", "val shitCoinOpened = executor.shitCoinBuy(")
        check("EXPRESS", "expressAttemptId", "val expressOpened = executor.shitCoinBuy(")
        check("PROJECT_SNIPER", "projectSniperAttemptId", "val sniperOpened = executor.shitCoinBuy(")
    }

    @Test fun timeCriticalExitUsesOneUrgencyVocabularyAndReachableQuantityAuthority() {
        val sell = src("engine/sell/SellAmountAuthority.kt")
        assertTrue(sell.contains("ProtectiveExitClass7807.isEmergency(reason)"))
        assertTrue(sell.contains("ProtectiveExitClass7807.Priority.PROFIT_PROTECTION"))
        assertTrue(sell.contains("EMERGENCY_OWNER_DELTA_AMOUNT_RESTORED_7815"))
        assertTrue(sell.contains("return Resolution.Confirmed(cached.rawAmount, cached.decimals, Source.TX_META_OWNER_DELTA)"))
        assertTrue(sell.contains("canBroadcastLiveOrEmergency"))
    }

    @Test fun cryptoAcceptanceCountsTerminalFailuresAsCausallyComplete() {
        val authority = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        val acceptance = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(authority.contains("dispatchAccountingForWindow7815"))
        assertTrue(acceptance.contains("cryptoTerminalResults"))
        assertTrue(acceptance.contains("dispatchAccountingForWindow7815"))
        assertFalse(acceptance.contains("f += \"CRYPTO_DISPATCH_WITHOUT_OPEN_OR_PENDING\""))
    }

    @Test fun specialistDiagnosticsSeparateGlobalBrainFanoutFromResidentOwnership() {
        val sheet = src("engine/ToolkitSignalSheet.kt")
        assertTrue(sheet.contains("nativeScope7815=GLOBAL_FANOUT_LAST_TOKEN"))
        assertTrue(sheet.contains("residentOwnLane7815"))
        assertTrue(sheet.contains("residentReady7815"))
    }

    @Test fun disabledLaneOwnershipIsCentralized() {
        val lane = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(lane.contains("enabledOwnerTrader7815"))
        assertTrue(lane.contains("LANE_OWNER_DISABLED_BY_AUTHORITY_7815"))
        assertTrue(lane.contains("EnabledTraderAuthority.isEnabled"))
    }
}
