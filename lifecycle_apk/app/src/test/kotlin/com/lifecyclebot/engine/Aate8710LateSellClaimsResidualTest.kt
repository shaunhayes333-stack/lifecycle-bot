package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8710 — the Runtime Smoke cash-only split (ledger 12.195534 vs journal 12.154210 = 0.041323, the exact residual a
 * terminal SELL had just written off): a partial sell walked after its position's terminal sell found no lot, and the
 * replay debited cash for a "recovered" entry the position's own BUY row had already paid for.
 */
class Aate8710LateSellClaimsResidualTest {
    @Test fun aLateSellReopensTheWrittenOffResidualWithoutASecondCashDebit() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
        val claim = s.substringAfter("§A_LATE_SELL_CLAIMS_THE_RESIDUAL_IT_EXPLAINS").substringBefore("if (lot == null && recoverableSide7078) {")
        assertTrue(claim.contains("val claimed = writtenOffResidual8710[t.positionId]"))
        assertTrue(claim.contains("openCost += claimed.basisSol"))
        assertTrue("the write-off is withdrawn, not counted twice", claim.contains("residualBasisWrittenOff6868 -= claimed.basisSol"))
        assertTrue("no cash is debited for a lot the BUY row already paid for", !claim.contains("cash -="))
        assertTrue("the claim comes before an entry is rebuilt from a receipt",
            s.indexOf("val claimed = writtenOffResidual8710[t.positionId]") < s.indexOf("lot = Lot(basis, recoveredRaw, recoveredDisplay)"))
        assertTrue(s.contains("if (nextBasis > 1e-9) writtenOffResidual8710[t.positionId] = Lot(nextBasis, nextRaw.max(java.math.BigInteger.ZERO), nextDisplay.coerceAtLeast(0.0))"))
    }
}
