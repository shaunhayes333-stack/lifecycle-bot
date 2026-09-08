#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(rel: str) -> Path:
    return ROOT / rel

def replace_once(rel: str, old: str, new: str):
    path = p(rel)
    s = path.read_text()
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{rel}: expected exactly one match, got {n}\nneedle={old[:160]!r}")
    path.write_text(s.replace(old, new, 1))
    print(f"patched {rel}")

# 1) CORE was omitted from the explicit Solana specialist whitelist. Because
# unknown lanes are intentionally NOT coerced to SOLANA_TOKEN, CORE was being
# auto-rerouted through sizing as AssetClass.UNKNOWN and lost canonical Solana
# causal sizing/ticket provenance.
replace_once(
    "app/src/main/kotlin/com/lifecyclebot/engine/truth/AssetClass.kt",
    '            "CYCLIC", "LAB", "RECOVERED_CARRY_6492" -> SOLANA_TOKEN',
    '            "CYCLIC", "CORE", "LAB", "RECOVERED_CARRY_6492" -> SOLANA_TOKEN',
)

# 2) SHITCOIN still used the legacy convenience sizing API. sizeForLane()
# intentionally has no mintForSeal parameter, so an otherwise executable size
# could never be sealed to the mint/candidate causal record. Route the specialist
# through resolveForLane with the real mint and return the canonical final size.
replace_once(
    "app/src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt",
    '''            val bridged = com.lifecyclebot.engine.truth.TraderSizingBridge6444.sizeForLane(
                laneName = "SHITCOIN",
                requestedSol = positionSol,
                walletSol = walletSolProxy,
                paperMode = isPaperMode,
            )
            bridged''',
    '''            val bridged = com.lifecyclebot.engine.truth.TraderSizingBridge6444.resolveForLane(
                laneName = "SHITCOIN",
                requestedSol = positionSol,
                walletSol = walletSolProxy,
                paperMode = isPaperMode,
                mintForSeal = mint,
            )
            bridged.finalSizeSol''',
)

# 3) Frozen execution restore was consulting only ExecutionSnapshotAuthority.
# The canonical FDG ExecutionIntent could still be present and valid, yet a
# missing/lagging snapshot caused 147 DROP_AND_REVALIDATE events in 6694. Accept
# either immutable authority; true safety/fatal checks remain unchanged.
replace_once(
    "app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt",
    '''                    val intentAuthoritative6627 = sealedIntent6627 != null &&
                        sealedIntent6627.fdgVerdict.uppercase() in setOf("BUY", "PROBE_ONLY") &&
                        sealedIntent6627.executionAction.isNotBlank() &&
                        !sealedIntent6627.executionAction.equals("UNKNOWN", true)
                    if (!intentAuthoritative6627) {''',
    '''                    val canonicalIntent6695 = try {
                        activeExecutionIntent6519(mode, mint, currentVersion)
                            ?: activeExecutionIntent6519(mode, mint, candidateVersion)
                    } catch (_: Throwable) { null }
                    val snapshotIntentAuthoritative6695 = sealedIntent6627 != null &&
                        sealedIntent6627.fdgVerdict.uppercase() in setOf("BUY", "PROBE_ONLY") &&
                        sealedIntent6627.executionAction.isNotBlank() &&
                        !sealedIntent6627.executionAction.equals("UNKNOWN", true)
                    val canonicalIntentAuthoritative6695 = canonicalIntent6695 != null &&
                        validSealedDecision6613(canonicalIntent6695)
                    val intentAuthoritative6627 = snapshotIntentAuthoritative6695 || canonicalIntentAuthoritative6695
                    if (canonicalIntentAuthoritative6695 && !snapshotIntentAuthoritative6695) {
                        try {
                            PipelineHealthCollector.labelInc("EXEC_FROZEN_CANONICAL_INTENT_RECOVERED_6695")
                            ForensicLogger.lifecycle(
                                "EXEC_FROZEN_CANONICAL_INTENT_RECOVERED_6695",
                                "mint=${mint.take(10)} lane=$selected candidate=${canonicalIntent6695?.candidateVersion} action=preserve_sealed_fdg_authority",
                            )
                        } catch (_: Throwable) {}
                    }
                    if (!intentAuthoritative6627) {''',
)

# 4) Crypto Universe's bounded top-N scheduler was terminalizing every valid
# candidate outside the current top 25 as SHARED_INTELLIGENCE_BACKLOG_COALESCED.
# That is a queue/defer condition, not a terminal economic disposition. Keep it
# as progress; the existing adaptive evidence TTL will reap genuinely stuck work.
replace_once(
    "app/src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt",
    '                DynamicAltTokenRegistry.markEvaluationDisposition6567(observedTok6569, "SHARED_INTELLIGENCE_BACKLOG_COALESCED")',
    '                DynamicAltTokenRegistry.markEvaluationProgress6570(observedTok6569, "SHARED_INTELLIGENCE_BACKLOG_COALESCED")',
)

# 5) QTY_RECONCILE rows were explicitly replayable, but the durable journal
# witness ignored that side entirely. CanonicalPaperTransaction stamped the
# ledger half first, so each repair could age into LEDGER_ONLY. Pair the durable
# journal stamp using the raw quantity direction already stored on the row.
replace_once(
    "app/src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt",
    '''            "PARTIAL_SELL" -> com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632.Side.PARTIAL_SELL
            else -> return''',
    '''            "PARTIAL_SELL" -> com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632.Side.PARTIAL_SELL
            "QTY_RECONCILE" -> if (trade.canonicalConsumedRaw > java.math.BigInteger.ZERO)
                com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632.Side.SELL
            else
                com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632.Side.BUY
            else -> return''',
)

# Regression lock: source semantics, not just counters/tests that can be aligned
# to stale behavior.
test = p("app/src/test/kotlin/com/lifecyclebot/engine/Aate6695RuntimeAuthorityRepairTest.kt")
test.write_text(r'''package com.lifecyclebot.engine

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
''')
print(f"wrote {test.relative_to(ROOT)}")

# Fail the transform itself if any expected post-condition is absent.
checks = {
    "AssetClass CORE": '"CYCLIC", "CORE", "LAB"' in p("app/src/main/kotlin/com/lifecyclebot/engine/truth/AssetClass.kt").read_text(),
    "SHITCOIN mint seal": "mintForSeal = mint" in p("app/src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").read_text(),
    "Frozen intent recovery": "EXEC_FROZEN_CANONICAL_INTENT_RECOVERED_6695" in p("app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").read_text(),
    "Crypto backlog progress": 'markEvaluationProgress6570(observedTok6569, "SHARED_INTELLIGENCE_BACKLOG_COALESCED")' in p("app/src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").read_text(),
    "QTY journal pairing": '"QTY_RECONCILE" -> if (trade.canonicalConsumedRaw' in p("app/src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").read_text(),
}
failed = [k for k, ok in checks.items() if not ok]
if failed:
    raise SystemExit("post-condition failure: " + ", ".join(failed))
print("V5.0.6695 runtime authority transform complete")
