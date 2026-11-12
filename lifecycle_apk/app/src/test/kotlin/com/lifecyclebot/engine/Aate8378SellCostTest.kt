package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** V5.0.8378 — owner-approved cost cuts: ordinary sells pay the ordinary Sender tip; withheld-fee Token-2022 rent is recovered. */
class Aate8378SellCostTest {
    private val c = CostTrim8151
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun protectiveSellsKeepTheFullTip() {
        assertTrue("urgent send", c.sellProtective8378("TAKE_PROFIT", urgent = true, previousSellUnlanded = false))
        assertTrue("retry after an unlanded sell pays to land", c.sellProtective8378("TAKE_PROFIT", urgent = false, previousSellUnlanded = true))
        assertTrue("stop loss", c.sellProtective8378("STOP_LOSS", urgent = false, previousSellUnlanded = false))
        assertTrue("hard floor", c.sellProtective8378("HARD_FLOOR_STOP", urgent = false, previousSellUnlanded = false))
        assertTrue("rug", c.sellProtective8378("RUG_DETECTED", urgent = false, previousSellUnlanded = false))
    }

    @Test fun ordinarySellsPayTheOrdinaryTip() {
        assertFalse(c.sellProtective8378("DUST_BUSTER", urgent = false, previousSellUnlanded = false))
        assertFalse(c.sellProtective8378("ORPHAN_SELL", urgent = false, previousSellUnlanded = false))
        assertEquals(CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151,
            c.senderTipLamports8151(CostTrim8151.Urgency8151.ORDINARY, 200_000L))
        assertEquals(200_000L, c.senderTipLamports8151(CostTrim8151.Urgency8151.URGENT, 200_000L))
    }

    @Test fun roundTripUsesTheMeasuredSellTip() {
        val m = CostMeter8151
        m.resetForTest8151()
        m.noteLeg8151(80_000L, 2_039_280L, 0L)
        m.noteTip8151(50_000L, 200_000L, entry = true)
        assertEquals(0.00041, m.roundTripFixedSol8151(), 1e-12)   // no sell tipped yet: legacy floor
        m.noteSellTip8378(50_000L, protective = false)
        // superseded by V5.0.8418 — the model prices the PROTECTIVE sell tip class (legacy floor until a protective sell is
        // tipped); a cheap ordinary sell tip no longer lowers the round trip / 1.5x entry bar. Was 0.00026 / 0.00013.
        assertEquals(0.00041, m.roundTripFixedSol8151(), 1e-12)
        assertEquals(0.00028, m.sellLegFixedSol8151(), 1e-12)
        assertTrue(m.statusLine8151().contains("sellTips8378[ordinary=1 protective=0"))
        m.resetForTest8151()
    }

    @Test fun everySellTipSiteIsClassified() {
        val exec = src("engine/Executor.kt")
        val body = exec.substringAfter("private fun liveSellInScope7314(").substringBefore("\n    private fun ")
        assertTrue("full sell", body.contains("senderTipLamports = sellTip8378(c, reason, ts, fastLand7980)"))
        assertEquals("both dust-buster legs", 2, Regex("dustTip8378\\(c, ts\\)").findAll(body).count())
        assertTrue("orphan sell", exec.contains("sellSenderTip8378(c, \"ORPHAN_SELL\", mint, urgent = false)"))
        assertTrue(exec.contains("sellSenderTip8378(c, reason, ts.mint, urgent = isDrainExit || spike7944)"))
        assertTrue(exec.contains("sellSenderTip8378(c, reason, ts.mint, urgent = isDrainExit)"))
    }

    @Test fun withheldFeeAccountsAreHarvestedThenClosed() {
        val w = src("network/SolanaWallet.kt").substringAfter("fun harvestAndCloseTokenAccount8378(").substringBefore("awaitConfirmation(txSig)")
        assertTrue("Token-2022 only", w.contains("require(account.programId == TOKEN_2022_PROGRAM_7927)"))
        assertTrue("harvest = TransferFeeExtension(26) + HarvestWithheldTokensToMint(4)", w.contains("write(byteArrayOf(0x02, 26, 4))"))
        assertTrue("harvest precedes close in one tx", w.indexOf("26, 4") in 0 until w.indexOf("0x01, 0x09"))
        val r = src("engine/RentReclaimer7927.kt")
        assertTrue(r.contains("if (harvestAndClose8378(wallet, batch[0])) return true"))
        assertTrue("only a failed harvest marks an account unclosable",
            r.indexOf("if (harvestAndClose8378(wallet, batch[0])) return true") in 0 until r.indexOf("unclosable8102.add(batch[0].address)"))
        assertTrue(r.contains("harvestClosed8378="))
    }
}
