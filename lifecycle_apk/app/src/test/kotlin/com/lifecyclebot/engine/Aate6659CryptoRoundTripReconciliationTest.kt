package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6659/6660 — source-level acceptance locks for cross-asset paper
 * round trips and account reconciliation.
 *
 * The broken path debited/credited the canonical ledger and mutated the
 * canonical position, but did not project the same immutable event into the
 * journal. User-stop exits returned before the legacy SELL writer as well.
 * Journal replay therefore could not reconstruct cash or lots and correctly
 * failed the account closed with ACCOUNT_UNAVAILABLE.
 */
class Aate6659CryptoRoundTripReconciliationTest {

    private val transaction = File(
        "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt"
    ).readText()
    private val crypto = File(
        "src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt"
    ).readText()
    private val journal = File(
        "src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt"
    ).readText()
    private val replay = File(
        "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt"
    ).readText()

    @Test
    fun `cross asset paper buy projects one stable event to fill lot and journal`() {
        assertTrue(transaction.contains("fun ensureOpenProjection6659(position:"))
        assertTrue(transaction.contains("val eventId = \"PAPER6486:OPEN:\${position.positionId}\""))
        assertTrue(transaction.contains("FillLotLedger6504.recordBuyFill("))
        assertTrue(transaction.contains("lotId = eventId"))
        assertTrue(transaction.contains("economicEventId = eventId"))
        assertTrue(transaction.contains("positionId = position.positionId"))
        assertTrue(transaction.contains("entryRawQty = position.originalQtyRaw"))
        assertTrue(transaction.contains("PaperAccountLedger6430.onBuyAtomic6632(costSol, feeSol, mint, idem)"))
        assertTrue(transaction.contains("PaperEconomicAtomicCommit6632.stampLedger("))
        assertTrue(transaction.contains("CanonicalPaperTransaction6486.openProjection6659"))
        assertTrue(journal.contains("CanonicalEconomicEvent6635.Store.JOURNAL"))
        assertTrue(
            "generic open must project only after the canonical position commits",
            transaction.indexOf("CanonicalPositionAuthority6441.getPosition(positionId)?.let { ensureOpenProjection6659(it) }") >
                transaction.indexOf("if (opened != CanonicalPositionAuthority6441.MutateResult.APPLIED)"),
        )
    }

    @Test
    fun `legacy cross asset positions are repaired idempotently during rehydrate`() {
        val rehydrate = crypto.substringAfter("private fun rehydrateCanonicalPositions6647")
            .substringBefore("private fun persistAltPositions")
        assertTrue(rehydrate.contains("CanonicalPaperTransaction6486.ensureOpenProjection6659(cp)"))
        assertTrue(rehydrate.contains("if (cp.mode.equals(\"paper\", true))"))
        assertTrue(rehydrate.contains("CanonicalPaperTransaction6486.repairCryptoHistory6659()"))
        assertTrue(transaction.contains("EconomicEventSchema6464.snapshot()"))
        assertTrue(transaction.contains("it.assetClass != AssetClass.SOLANA_TOKEN"))
        assertTrue(transaction.contains("CROSS_ASSET_HISTORY_REPAIR_6660"))
        assertTrue(transaction.contains("CROSS_ASSET_HISTORY_REPROJECTED_6660"))
        assertTrue(replay.contains("CRYPTO_LEGACY_DISPLAY_ROW_SUPERSEDED_6659"))
    }

    @Test
fun `paper close journal belongs only to canonical reducer before stop return`() {
    listOf(
        "economicEventId = r.economicEventId",
        "grossProceedsSol = r.grossProceedsSol",
        "soldCostBasisSol = r.soldCostBasisSol",
        "canonicalConsumedRaw = r.canonicalConsumedRaw",
        "postRemainingRaw = r.postRemainingRaw",
    ).forEach { field -> assertTrue("canonical close result must preserve $field", transaction.contains(field)) }

    val reducerClose = transaction.substringAfter("fun close(positionId: String")
        .substringBefore("fun refund(positionId: String")
    val terminalMutation = reducerClose.indexOf("CanonicalPaperTerminalBridge6469.finalizeSell(")
    val canonicalJournal = reducerClose.indexOf("recordCloseProjection6659(pos, r, exitReason, terminal)")
    val reducerReturn = reducerClose.indexOf("applied = true", canonicalJournal)
    assertTrue("canonical reducer must journal after terminal mutation", terminalMutation in 1 until canonicalJournal)
    assertTrue("canonical reducer must journal before returning to any trader", canonicalJournal in 1 until reducerReturn)
    assertTrue(reducerClose.contains("economicEventId = receipt.economicEventId"))
    assertTrue(reducerClose.contains("grossProceedsSol = gross"))

    val close = crypto.substringAfter("private fun closePosition(positionId: String, reason: String)")
    val localCleanup = close.indexOf("positions.remove(positionId)")
    val canonicalClose = close.indexOf("CanonicalPaperTransaction6486.close(")
    val fastStop = close.indexOf("if (reason == \"USER_STOP\"")
    assertTrue("canonical close must finish before local cleanup", canonicalClose in 1 until localCleanup)
    assertTrue("canonical close must finish before USER_STOP can return", canonicalClose in 1 until fastStop)
    val paperPrefix = close.substring(0, localCleanup)
    assertFalse("CryptoAlt caller must not write a second paper journal row", paperPrefix.contains("TradeHistoryStore.recordTrade("))
    assertFalse(crypto.contains("CRYPTO_ROUND_TRIP_JOURNAL_COMMITTED_6659"))
    assertFalse(crypto.contains("canonicalCloseReceipt6659"))
}

    @Test
fun `paper close has one canonical terminal publisher and live journal stays live only`() {
    assertTrue(crypto.contains("if (!paper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit("))
    assertTrue(crypto.contains("if (!paper) try {\n            TradeHistoryStore.recordTrade(Trade("))
    val close = crypto.substringAfter("private fun closePosition(positionId: String, reason: String)")
    val paperPrefix = close.substringBefore("positions.remove(positionId)")
    assertTrue(paperPrefix.contains("CanonicalPaperTransaction6486.close("))
    assertFalse(paperPrefix.contains("TradeHistoryStore.recordTrade("))
}

    @Test
    fun `typed crypto close bypasses only the Solana mint quarantine domain`() {
        val bridge = File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt"
        ).readText()
        val ledger = File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt"
        ).readText()
        assertTrue(ledger.contains("enforceSolanaMintQuarantine: Boolean = true"))
        assertTrue(ledger.contains("mint.isNotBlank() && enforceSolanaMintQuarantine"))
        assertTrue(bridge.contains("enforceSolanaMintQuarantine = canonicalBefore6522?.assetClass == AssetClass.SOLANA_TOKEN"))
    }
}
