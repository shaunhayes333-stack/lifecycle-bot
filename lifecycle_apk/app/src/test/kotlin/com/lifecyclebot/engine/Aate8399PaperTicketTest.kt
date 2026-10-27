package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.*
import org.junit.Test

/** V5.0.8399 — paper admission cost is previewed at a wallet-percent share of the PAPER ledger, never at the live wallet's dust ticket. */
class Aate8399PaperTicketTest {
    @Test fun paperTicketIsAWalletPercentOfThePaperLedger() {
        assertEquals(5.0, NetEdge8030.PAPER_TICKET_WALLET_PCT_8399, 0.0)
        assertEquals(0.5, NetEdge8030.paperTicket8399(paperWalletSol = 10.0, solUsd = Double.NaN), 1e-9)
        assertEquals(5.0, NetEdge8030.paperTicket8399(paperWalletSol = 100.0, solUsd = Double.NaN), 1e-9)
    }

    @Test fun tinyOrUnknownPaperLedgerFallsBackToTheDefaultTicketNeverBelow() {
        assertEquals(NetEdge8030.DEFAULT_TICKET_SOL, NetEdge8030.paperTicket8399(paperWalletSol = 0.1, solUsd = Double.NaN), 1e-9)
        assertEquals(NetEdge8030.DEFAULT_TICKET_SOL, NetEdge8030.paperTicket8399(paperWalletSol = Double.NaN, solUsd = Double.NaN), 1e-9)
        assertEquals(NetEdge8030.DEFAULT_TICKET_SOL, NetEdge8030.paperTicket8399(paperWalletSol = 0.0, solUsd = 150.0), 1e-9)
    }

    @Test fun admissionAndCostPreviewUseThePaperTicketInPaperMode() {
        val root = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth")
        val netEdge = root.resolve("NetEdge8030.kt").readText()
        assertTrue(netEdge.contains("if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) paperTicket8399()"))
        val admission = root.resolve("CandidateAdmission8136.kt").readText()
        assertTrue(admission.contains("""else if (mode == "PAPER" && !(proposedSizeSol.isFinite() && proposedSizeSol > 0.0)) try { com.lifecyclebot.engine.truth.NetEdge8030.paperTicket8399() }"""))
    }
}
