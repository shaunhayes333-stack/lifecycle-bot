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
    fun `meme turnover ceiling is canonical and precedes every soft fail open`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()

        assertTrue(src.contains("MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24"))
        assertTrue(src.contains("activeMintProjections6490(mode)"))
        assertTrue(src.contains("MEME_INVENTORY_TURNOVER_CAP_6689"))

        val hardCap = src.indexOf("if (effectiveMemeOpen6689 >= MEME_TURNOVER_ABSOLUTE_CAP_6689)")
        val staleFailOpen = src.indexOf("stale_snapshot_fail_open")
        val paperForcedFailOpen = src.indexOf("PAPER_FORCED_OPEN_FAIL_OPEN")
        assertTrue("turnover cap must execute before stale telemetry fail-open", hardCap >= 0 && hardCap < staleFailOpen)
        assertTrue("turnover cap must execute before PAPER forced-open fail-open", hardCap >= 0 && hardCap < paperForcedFailOpen)

        val hardBlock = src.substring(hardCap, staleFailOpen)
        assertFalse("high-edge candidates must not bypass the absolute turnover ceiling",
            hardBlock.contains("candidateConfirmedHighEdge"))
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
    fun `soft exit priority remains below absolute turnover ceiling`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertTrue(src.contains("ENTRY_SOFT_CAP = 12"))
        assertTrue(src.contains("activeSellJobs > 0 && openPositionCount.get() >= ENTRY_SOFT_CAP"))
        assertTrue(src.contains("memeTurnoverCap=\$MEME_TURNOVER_ABSOLUTE_CAP_6689"))
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
