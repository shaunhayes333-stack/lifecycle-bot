package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.ExploreLive8733
import com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8928 — the agreement pocket may be bought while its LP is still unread, only carefully: $10k-$20k, RugCheck reported,
 * a classic SPL mint, buying rising, holders growing and more positive signs than negative; one at a time.
 */
class Aate8928PocketCarefullyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun v(buyTx15: Int = 9, prev15: Int = 4, accel: Double = 62.0, share: Double = 64.0, net: Double = 1.5,
                  new15: Int = 3, sustained: Boolean = false) = BuyerFlow8666.View(atMs = 1L, distinctBuyers60s = 12,
        newBuyers15s = new15, buyersPerMin = 12.0, buySharePct = share, netSol60s = net, buyTx15s = buyTx15, buyTxPrev15s = prev15,
        repeatBuyers60s = 1, txPerMin = 20.0, volumeAccelScore = accel, sustainedDemand = sustained)

    @Test fun itNeedsRisingBuyingGrowingHoldersAndMorePositiveThanNegative() {
        assertEquals(6 to 0, ExploreLive8733.signs8928(v(), 4.0))
        assertTrue(ExploreLive8733.flowPasses8928(v(), 4.0))
        assertFalse("holders not growing", ExploreLive8733.flowPasses8928(v(), 0.0))
        assertFalse("holders unread", ExploreLive8733.flowPasses8928(v(), null))
        assertFalse("no flow read", ExploreLive8733.flowPasses8928(null, 4.0))
        assertFalse("buying not rising", ExploreLive8733.flowPasses8928(v(buyTx15 = 3, prev15 = 5, accel = 50.0), 4.0))
        // rising but the rest leans negative: 3 negative (share, net, no new buyers) against 2 positive (tx, holders)
        assertEquals(2 to 3, ExploreLive8733.signs8928(v(accel = 50.0, share = 40.0, net = -0.5, new15 = 0), 4.0))
        assertFalse(ExploreLive8733.flowPasses8928(v(accel = 50.0, share = 40.0, net = -0.5, new15 = 0), 4.0))
        assertEquals(1, ExploreLive8733.MAX_OPEN_8733)
        assertEquals(10_000.0, ExploreLive8733.CAP_LO_USD_8928, 0.0)
    }

    @Test fun onlyAClassicSplMintIsReadAsOne() {
        fun acct(owner: String) = JSONObject("""{"owner":"$owner","data":{"parsed":{"type":"mint","info":{"mintAuthority":null,"freezeAuthority":null}}}}""")
        assertTrue(OnChainMintAuthorityProof7248.parseAccountProof8253(acct("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"), "t")!!.classicToken8928)
        assertFalse(OnChainMintAuthorityProof7248.parseAccountProof8253(acct("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"), "t")!!.classicToken8928)
    }

    @Test fun rugcheckStaysRequiredAndUnlockedStaysBlocked() {
        val gate = src("engine/PreTradeHardGate.kt")
        assertFalse(gate.contains("ownership8522.status == LiquidityOwnership8522.Status.PROVEN || explore8733)"))
        assertTrue(gate.indexOf("Status.BLOCKED -> return block") < gate.indexOf("val explore8733"))
        assertTrue(ExploreLive8733.relabel8733(listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW", "LP_OWNERSHIP_PROOF_PENDING_8522:x"))
            .any { it.startsWith("LP_OWNERSHIP_PROOF_PENDING_8522") })
        assertTrue(ExploreLive8733.statusLine8733().contains("rule=8928:"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 116. The pocket, carefully (8928)"))
    }
}
