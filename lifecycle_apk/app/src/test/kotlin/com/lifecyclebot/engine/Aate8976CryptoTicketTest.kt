package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.perps.CryptoAltTrader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8976 — the crypto ticket can clear its fixed legs (0.05 SOL), asked for only when the wallet and the evidence hold it. */
class Aate8976CryptoTicketTest {
    private val t = CryptoAltTrader

    @Test fun aNormalCryptoAskIsLiftedToTheMinimumWhenTheWalletHoldsIt() {
        assertEquals(0.05, NetEdge8030.CRYPTO_MIN_TICKET_SOL_8976, 0.0)
        assertEquals("6 % of a 0.26 SOL wallet", 0.05, t.cryptoTicket8976(0.0156, 0.26), 1e-12)
        assertEquals("a quarter of the minimum is still lifted", 0.05, t.cryptoTicket8976(0.0125, 0.26), 1e-12)
    }

    @Test fun itStaysSizingAware() {
        assertEquals("a larger ask is asked as sized", 0.08, t.cryptoTicket8976(0.08, 0.26), 1e-12)
        assertEquals("evidence shrank it under a quarter: left small (the resolver's minimum skips it)", 0.006, t.cryptoTicket8976(0.006, 0.26), 1e-12)
        assertEquals("a wallet under four tickets: sized as asked", 0.016, t.cryptoTicket8976(0.016, 0.19), 1e-12)
        assertEquals(0.0, t.cryptoTicket8976(0.0, 0.26), 0.0)
        assertTrue(t.cryptoTicket8976(Double.NaN, 0.26).isNaN())
    }

    @Test fun theMinimumAndTheCostAreTheCryptoDesksOwn() {
        assertEquals(0.05, NetEdge8030.cryptoMinTicketSol8976(true), 0.0)
        assertEquals(0.0, NetEdge8030.cryptoMinTicketSol8976(false), 0.0)
        assertTrue(NetEdge8030.cryptoLane8976("CRYPTO_CORE") && NetEdge8030.cryptoLane8976("crypto_alt") && !NetEdge8030.cryptoLane8976("CORE"))
        val ne = File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText()
        assertTrue(ne.contains("val size = if (cryptoLane8976(lane)) maxOf(size0, CRYPTO_MIN_TICKET_SOL_8976) else size0"))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt").readText()
        assertTrue(bridge.contains("NetEdge8030.cryptoMinTicketSol8976(assetClass == AssetClass.CRYPTO_ALT))"))
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(trader.indexOf("sizeSol = cryptoTicket8976(sizeSol, balance)") > trader.indexOf("sizeSol *= cryptoBrainSize7244"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 161. A crypto ticket that can clear its fees (8976)"))
    }
}
