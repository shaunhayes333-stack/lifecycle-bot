package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForensicReconciliation6635
import com.lifecyclebot.engine.truth.PaperAccountLedger6430 as Ledger
import com.lifecyclebot.engine.truth.PaperEconomicAtomicCommit6632 as Receipts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8685 — the 8681 / 8683 Runtime Smoke accounting failures: cash-only split, realized and open cost exact. The
 * reconciler read ledger cash and the paper treasury through separate locks, so a treasury move between the two reads
 * was counted twice or not at all.
 */
class Aate8685ReconcileTearTest {
    @Before fun setup() {
        Receipts.resetForTest()
        Ledger.initialize(10.0)
        assertTrue(Ledger.onBuy(0.25))
    }

    @After fun cleanup() {
        Receipts.resetForTest()
        Ledger.resetForTest()
    }

    @Test fun cashPlusTreasuryFromOneSnapshotNeverTearsAcrossTreasuryMoves() {
        val expected = 10.0 - 0.25
        val stop = AtomicBoolean(false)
        val mover = Thread {
            while (!stop.get()) {
                Ledger.moveCashToTreasury7294(0.125, "test")
                Ledger.moveTreasuryToCash7294(0.125, "test")
            }
        }
        mover.start()
        try {
            repeat(20_000) {
                val seen = ForensicReconciliation6635.ledgerCashWithTreasury8685(Ledger.snapshotAtomic6643())
                assertEquals("cash + treasury is one ledger state", expected, seen, 1e-9)
            }
        } finally { stop.set(true); mover.join(5_000) }
    }

    @Test fun reconcilerAndConservationAuditsReadTheLedgerOnce() {
        val recon = src("engine/truth/ForensicReconciliation6635.kt")
        assertFalse(recon.contains("PaperCapitalAuthority6577.cashSol() + PaperCapitalAuthority6577.treasurySol7294()"))
        assertFalse(recon.contains("PaperCapitalAuthority6577.realizedPnlSol()"))
        assertFalse(recon.contains("PaperCapitalAuthority6577.openCostBasisSol()"))
        assertTrue(recon.contains("val ledger8685 = try { PaperAccountLedger6430.snapshotAtomic6643() }"))
        val guards = src("engine/truth/CanonicalIntegrityGuards6449.kt")
        assertTrue(guards.contains("val s8685 = PaperAccountLedger6430.snapshotAtomic6643()"))
        assertFalse(guards.contains("PaperCapitalAuthority6577.treasurySol7294()"))
        val capital = src("engine/truth/CanonicalCapitalAuthority6450.kt")
        assertTrue(capital.contains("val ledger8685 = PaperAccountLedger6430.snapshotAtomic6643()"))
        assertFalse(capital.contains("val cash = PaperCapitalAuthority6577.cashSol()"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
