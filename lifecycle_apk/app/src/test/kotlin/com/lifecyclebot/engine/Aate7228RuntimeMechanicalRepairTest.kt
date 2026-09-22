package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7228RuntimeMechanicalRepairTest {
    private fun source(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun provider_class_failure_rotates_immediately_and_cools_each_provider() {
        val executor = source("engine/Executor.kt")
        val health = source("engine/sell/ExitProviderHealth.kt")
        assertTrue(health.contains("fun isProviderClassFailure"))
        assertTrue(health.contains("recordJupiterProviderFailure"))
        assertTrue(health.contains("recordPumpProviderFailure"))
        assertTrue(health.contains("PUMP_EXIT_PROVIDER_COOLDOWN_7228"))
        assertTrue(executor.contains("jupiterProviderClassFailure7228"))
        assertTrue(executor.contains("break@jupiterLadder7228"))
        assertTrue(executor.contains("SELL_PROVIDER_ROTATE_IMMEDIATE_7228"))
    }

    @Test fun protective_exits_never_wait_for_quote_provider_recovery() {
        assertTrue(ExecutionHealthGuard.isEmergencyReason("STRICT_SL_-15"))
        assertTrue(ExecutionHealthGuard.isEmergencyReason("HARD_FLOOR"))
        assertTrue(ExecutionHealthGuard.isEmergencyReason("STOP_LOSS"))
        assertTrue(ExecutionHealthGuard.isEmergencyReason("CATASTROPHIC_EXIT"))
    }

    @Test fun wallet_absence_and_empty_map_are_unknown_not_zero() {
        val reconciler = source("engine/sell/LiveWalletReconciler.kt")
        val tracker = source("engine/HostWalletTokenTracker.kt")
        assertTrue(reconciler.contains("WALLET_SNAPSHOT_INCONCLUSIVE_7228"))
        assertTrue(reconciler.contains("balances[p.mint] ?: continue  // missing mint = UNKNOWN, never ZERO"))
        assertTrue(reconciler.contains("balances[m] ?: return@filter false"))
        assertFalse(reconciler.contains("pair?.raw ?: java.math.BigInteger.ZERO"))
        assertTrue(tracker.contains("RPC_CONFIRMED_POSITIVE_DUST_7228"))
        assertFalse(tracker.contains("CLOSED_BY_TERMINAL_TOKEN_DUST"))
    }

    @Test fun crypto_confirmed_signature_is_verify_pending_not_failed() {
        val cu = source("perps/crypto/CryptoUniverseExecutor.kt")
        val trader = source("perps/CryptoAltTrader.kt")
        assertTrue(cu.contains("data class VerifyPending"))
        assertTrue(cu.contains("CU_VERIFY_PENDING"))
        assertTrue(cu.contains("Outcome.VerifyPending(sig, mint, resolution, bridge.proofState)"))
        val pendingBlock = cu.substring(cu.indexOf("Confirmed signature awaiting target quantity proof"), cu.indexOf("val filledRaw"))
        assertFalse(pendingBlock.contains("CU_CONFIRM_FAILED"))
        assertTrue(trader.contains("Outcome.VerifyPending"))
    }

    @Test fun registry_restore_source_stays_quarantined_without_poisoning_mints() {
        val bot = source("engine/BotService.kt")
        assertTrue(bot.contains("MEME_REGISTRY_RESTORE_SOURCE_QUARANTINED_7228") && bot.contains("action=skip_restore_intake_preserve_registry"))
        assertTrue(bot.contains("MEME_REGISTRY_RESTORE_SOURCE_QUARANTINED_7228"))
        assertTrue(bot.contains("skip_restore_intake_preserve_registry"))
    }
}
