package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Authority
import com.lifecyclebot.engine.truth.CanonicalUiPositionProjection6686 as Projection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

class Aate7253WalletIdentityAndLlmRoutingTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun frozen_rpc_state_is_excluded_before_wallet_recovery() {
        val wallet = src("network/SolanaWallet.kt")
        val authority = src("engine/truth/WalletAssetClassification6387.kt")
        assertTrue(wallet.contains("optString(\"state\", \"\").equals(\"frozen\""))
        assertTrue(wallet.contains("WALLET_FROZEN_TOKEN_IGNORED_7253"))
        assertTrue(wallet.contains("frozenMints7253.forEach(out::remove)"))
        assertTrue(authority.contains("object WalletTokenAccountStateAuthority7253"))
    }

    @Test fun open_panel_no_longer_synthesizes_host_wallet_rows() {
        val main = src("ui/MainActivity.kt")
            .substringAfter("private fun buildUnifiedOpenPositions")
            .substringBefore("private fun countLaneHeldPositions")
        val projection = src("engine/truth/CanonicalUiPositionProjection6686.kt")
        assertFalse(main.contains("OPEN_PANEL_HOST_TRACKER_SYNTH_6040"))
        assertFalse(main.contains("OPEN_PANEL_HELD_SHOWN_WITHOUT_CANONICAL_7135"))
        assertTrue(main.contains("canonical7253 &&"))
        assertTrue(projection.contains("?: false"))
        assertTrue(projection.contains("distinctBy { \"${'$'}{it.mode.lowercase()}:${'$'}{it.positionId}\" }"))

        // Canonical lots are the source. A token/wallet cache entry creates no
        // row, while distinct same-mint lots and modes must remain visible.
        val first = Authority.Position(positionId = "paper-first", mode = "paper", mint = "same-mint",
            symbol = "SAME", lane = "MOONSHOT", runId = "test", openedAtMs = 1L, entryCostSol = 0.1,
            remainingQtyRaw = BigInteger.ONE, originalQtyRaw = BigInteger.ONE, soldCostBasisSol = 0.0,
            realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = 0.0, tokenDecimals = 0,
            lifecycle = Authority.Lifecycle.OPEN, lastMutationMs = 1L, quarantineReason = "")
        val second = first.copy(positionId = "paper-second")
        val live = first.copy(positionId = "live-lot", mode = "live")
        val closed = first.copy(positionId = "closed-lot", lifecycle = Authority.Lifecycle.CLOSED)
        val walletOnly = TokenState("wallet-only")
        val tokenCache = mapOf(walletOnly.mint to walletOnly)
        assertTrue(Projection.projectRows8521(emptyList(), tokenCache) { false }.isEmpty())
        val rows = Projection.projectRows8521(listOf(first, first, second, live, closed), tokenCache) { false }
        assertEquals(3, rows.size)
        assertEquals(setOf("paper-first", "paper-second", "live-lot"), rows.map { it.position.positionId }.toSet())
        assertEquals(2, rows.count { it.position.isPaperPosition })
        assertFalse(rows.any { it.mint == walletOnly.mint })
    }

    @Test fun verified_buy_journal_can_repair_lost_canonical_lineage() {
        val recovery = src("engine/LiveCanonicalRecovery6686.kt")
        assertTrue(recovery.contains("journalBasis7253(mint, amount)"))
        assertTrue(recovery.contains("proofState.equals(\"LIVE_FINALIZED\""))
        assertTrue(recovery.contains("LIVE_BASIS_REBUILT_FROM_FINALIZED_JOURNAL_7253"))
        assertTrue(recovery.contains("laterTerminalSell"))
    }

    @Test fun saved_provider_keys_hot_apply_without_gemini_gate() {
        val service = src("engine/BotService.kt")
            .substringAfter("V5.0.7253 — configure the provider chain")
            .substringBefore("V5.9.129: Start the Sentience loop")
        val vm = src("ui/BotViewModel.kt")
            .substringAfter("fun saveConfig(")
            .substringBefore("fun connectWallet(")
        assertTrue(service.indexOf("GeminiCopilot.configureFallbackApis") > service.indexOf("if (cfg.geminiApiKey.isNotBlank())"))
        assertTrue(vm.contains("cfg.openRouterApiKey.trim() != currentCfg.openRouterApiKey.trim()"))
        assertTrue(vm.contains("GeminiCopilot.configureFallbackApis("))
        assertTrue(vm.contains("KeylessLlmClient.setOperatorKeys("))
    }

    @Test fun provider_failure_is_transparent_not_scripted_personality() {
        val sentient = src("engine/SentientPersonality.kt")
        assertFalse(sentient.contains("private fun fallbackReply("))
        assertFalse(sentient.contains("Heard. Carrying that into the next scan."))
        assertFalse(sentient.contains("Can do. Waiting for your green light."))
        assertTrue(sentient.contains("No language model answered this turn"))
        assertTrue(sentient.contains("no model-authored reply or instruction was applied"))
    }
}
