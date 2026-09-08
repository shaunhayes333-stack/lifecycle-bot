package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6695RuntimeAuthorityRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun core_is_explicit_sol_specialist() {
        val s = src("engine/truth/AssetClass.kt")
        val solanaBlock = s.substringAfter("Recognised Solana meme/shitcoin/bluechip lanes").substringBefore("else -> UNKNOWN")
        assertTrue(solanaBlock.contains("\"CORE\""))
    }

    @Test fun shitcoin_sizes_with_mint_seal_not_legacy_blank_mint_convenience() {
        val s = src("v3/scoring/ShitCoinTraderAI.kt")
        val block = s.substringAfter("val _shitCoinFinalSol").substringBefore("return ShitCoinSignal")
        assertTrue(block.contains("TraderSizingBridge6444.resolveForLane("))
        assertTrue(block.contains("mintForSeal = mint"))
        assertTrue(block.contains("bridged.finalSizeSol"))
        assertFalse(block.contains("TraderSizingBridge6444.sizeForLane("))
    }

    @Test fun frozen_restore_accepts_valid_canonical_execution_intent() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("EXEC_FROZEN_CANONICAL_INTENT_RECOVERED_6695"))
        assertTrue(s.contains("validSealedDecision6613(canonicalIntent6695)"))
        assertTrue(s.contains("snapshotIntentAuthoritative6695 || canonicalIntentAuthoritative6695"))
    }

    @Test fun crypto_backlog_is_progress_not_terminal_disposition() {
        val s = src("perps/CryptoAltTrader.kt")
        val block = s.substringAfter("uniqueDynSignals6567.filterNot { it in topDyn }").substringBefore("for ((signalIndex6567, sig) in topDyn.withIndex())")
        assertTrue(block.contains("markEvaluationProgress6570"))
        assertFalse(block.contains("markEvaluationDisposition6567"))
    }

    @Test fun qty_reconcile_pairs_atomic_journal_side() {
        val s = src("engine/TradeHistoryStore.kt")
        val block = s.substringAfter("private fun stampDurableJournalCommit6641").substringBefore("private fun")
        assertTrue(block.contains("\"QTY_RECONCILE\""))
        assertTrue(block.contains("trade.canonicalConsumedRaw > java.math.BigInteger.ZERO"))
    }
}
