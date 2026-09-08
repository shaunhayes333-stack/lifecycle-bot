package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6689 — regression seals for the operator-observed 200+ Meme inventory
 * accumulation and stale/local paper bankroll shaping.
 */
class Aate6689InventoryTurnoverCompoundingTest {

    @Test
    fun `static meme position ceiling is retired in favor of shared capital authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()

        assertFalse(src.contains("MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24"))
        assertTrue(src.contains("memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE"))
        assertTrue(src.contains("memeTurnoverCap=SHARED_CAPITAL_6692"))
        val defer = src.substringAfter("fun shouldDeferBuy").substringBefore("fun snapshotLine")
        assertFalse("static inventory count must not return a hard defer", defer.contains("MEME_TURNOVER_CAP="))
        assertTrue(defer.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
    }

    @Test
    fun `paper specialist sizing binds compounding ladder to shared account cash`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt").readText()

        assertTrue(src.contains("PaperCapitalAuthority6577.cashSol()"))
        assertTrue(src.contains("PAPER_SIZING_SHARED_CASH_BOUND_6689"))
        assertTrue(src.contains("PAPER_SIZING_CALLER_CASH_DIVERGENCE_6689"))

        val resolver = src.indexOf("val res = OrderSizeResolver6441.resolve(")
        val afterResolver = src.indexOf("CANONICAL_SIZING_BRIDGE_6532|CLASS=", resolver)
        assertTrue(resolver >= 0 && afterResolver > resolver)
        val block = src.substring(resolver, afterResolver)
        assertTrue("canonical paper/live wallet selection must feed the mandatory resolver",
            block.contains("walletSol = effectiveWalletSol6689"))
        assertFalse("raw caller wallet must not be passed directly into the resolver",
            block.contains("walletSol = walletSol"))
    }

    @Test
    fun `confirmed paper sell returns proceeds to same canonical ledger before finalization`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val positionMutation = src.indexOf("val positionApplied6486")
        val sharedLedgerCredit = src.indexOf("PaperAccountLedger6430.onSellAtomic6632", positionMutation)
        val grantedReturn = src.indexOf("reason = \"GRANTED\"", sharedLedgerCredit)

        assertTrue("canonical position must mutate before shared cash is credited",
            positionMutation >= 0 && sharedLedgerCredit > positionMutation)
        assertTrue("shared-ledger credit must occur before confirmed terminal return",
            sharedLedgerCredit >= 0 && grantedReturn > sharedLedgerCredit)
    }

    @Test
    fun `exit priority is advisory and cannot globally amputate meme entries`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertTrue(src.contains("ENTRY_SOFT_CAP = 12"))
        assertTrue(src.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
        val defer = src.substringAfter("fun shouldDeferBuy").substringBefore("fun snapshotLine")
        assertFalse(defer.contains("EXITS_PRIORITY sellJobsActive="))
    }

    @Test
    fun `executor canonical writer counts open plus pending atomically`() {
        val mirror = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt").readText()
        assertTrue(mirror.contains("@Synchronized\n    fun mirrorBuyAttempt"))
        assertTrue(mirror.contains("pendingEntryPositions6461()"))
        assertTrue(mirror.contains("val totalReserved6689 = open6689 + pending6689"))
        assertTrue(mirror.contains("MEME_CANONICAL_ADMISSION_CAP_6689"))
        assertTrue(mirror.contains("SlotHealthGate.memeTurnoverAbsoluteCap6689()"))
    }

    @Test
    fun `duplicate refunds journal the exact terminal receipt and leveraged losses respect limited liability`() {
        val tx = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        assertTrue(tx.contains("val economicallyCappedExpected6569 = expected6569?.coerceAtLeast(-basis)?.minus(sellFeeSol)"))
        assertTrue(tx.contains("LEVERAGED_TERMINAL_LIMITED_LIABILITY_CAP_6689"))
        assertTrue(tx.contains("recordCloseProjection6659(pos, result, \"DUPLICATE_SAME_MINT_REFUND_6490\", terminal = true)"))
        assertTrue(tx.contains("CanonicalMintOccupancyRegistry6464.markClosed(\"paper\", pos.mint)"))
        assertTrue(tx.contains("DUPLICATE_REFUND_JOURNAL_COMMITTED_6689"))
    }
}
