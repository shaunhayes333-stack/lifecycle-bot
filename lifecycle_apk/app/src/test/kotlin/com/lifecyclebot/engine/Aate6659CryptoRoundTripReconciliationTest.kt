package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6659 — source-level acceptance locks for Crypto Universe paper
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
        assertTrue(journal.contains("CanonicalEconomicEvent6635.Store.JOURNAL"))
        assertTrue(
            "generic open must project only after the canonical position commits",
            transaction.indexOf("CanonicalPositionAuthority6441.getPosition(positionId)?.let { ensureOpenProjection6659(it) }") >
                transaction.indexOf("if (opened != CanonicalPositionAuthority6441.MutateResult.APPLIED)"),
        )
    }

    @Test
    fun `legacy crypto positions are repaired idempotently during rehydrate`() {
        val rehydrate = crypto.substringAfter("private fun rehydrateCanonicalPositions6647")
            .substringBefore("private fun persistAltPositions")
        assertTrue(rehydrate.contains("CanonicalPaperTransaction6486.ensureOpenProjection6659(cp)"))
        assertTrue(rehydrate.contains("if (cp.mode.equals(\"paper\", true))"))
        assertTrue(rehydrate.contains("CanonicalPaperTransaction6486.repairCryptoHistory6659()"))
        assertTrue(transaction.contains("EconomicEventSchema6464.snapshot()"))
        assertTrue(transaction.contains("CRYPTO_ALT_HISTORY_REPAIR_6659"))
        assertTrue(replay.contains("CRYPTO_LEGACY_DISPLAY_ROW_SUPERSEDED_6659"))
    }

    @Test
    fun `paper close carries exact canonical receipt into journal before stop return`() {
        listOf(
            "economicEventId = r.economicEventId",
            "grossProceedsSol = r.grossProceedsSol",
            "soldCostBasisSol = r.soldCostBasisSol",
            "canonicalConsumedRaw = r.canonicalConsumedRaw",
            "postRemainingRaw = r.postRemainingRaw",
        ).forEach { field -> assertTrue("canonical close result must preserve $field", transaction.contains(field)) }

        val close = crypto.substringAfter("private fun closePosition(positionId: String, reason: String)")
        val exactJournal = close.indexOf("CRYPTO_ROUND_TRIP_JOURNAL_COMMITTED_6659")
        val fastStop = close.indexOf("if (reason == \"USER_STOP\"")
        assertTrue("exact SELL journal must run before USER_STOP can return", exactJournal in 1 until fastStop)
        assertTrue(close.contains("economicEventId = receipt.economicEventId"))
        assertTrue(close.contains("canonicalConsumedRaw = receipt.canonicalConsumedRaw"))
        assertTrue(close.contains("soldCostBasisSol = basis, grossProceedsSol = gross"))
        assertTrue(close.contains("positionId = pos.id"))
    }

    @Test
    fun `paper close has one terminal publisher and no legacy symbol keyed sell`() {
        assertTrue(crypto.contains("if (!paper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit("))
        assertTrue(crypto.contains("if (!paper) try {\n            TradeHistoryStore.recordTrade(Trade("))
        val exactPaperBlock = crypto.substringAfter("if (pos.isPaper) {\n            val receipt = canonicalCloseReceipt6659")
            .substringBefore("positions.remove(positionId)")
        assertTrue(exactPaperBlock.contains("mint = pos.canonicalAssetKey"))
        assertFalse(exactPaperBlock.contains("mint = mktSym"))
    }
}
