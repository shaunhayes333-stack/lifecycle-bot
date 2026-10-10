package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusSolanaScope7819
import com.lifecyclebot.network.HeliusSubscriptionTelemetry7807
import com.lifecyclebot.network.HeliusWebSocket
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7819 HeliusScope — runtime 5.0.7813 (paper, 88 positions, mostly tokenized
 * stocks): pinnedHeld=100 of 128 Helius slots were stock tickers, evictions=3214,
 * reconnectResubscribes=1823. Helius is a Solana feed only.
 */
class Aate7819HeliusScopeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    private fun newWs(): HeliusWebSocket = HeliusWebSocket(
        apiKey = "",
        onSwap = { _, _, _, _, _, _ -> },
        onLargeWalletMove = { _, _, _, _ -> },
        onLog = { },
    ).apply { setMintEligibility7819 { HeliusSolanaScope7819.isSolanaMint7819(it) } }

    @Suppress("UNCHECKED_CAST")
    private fun desired(ws: HeliusWebSocket): Map<String, Int?> =
        HeliusWebSocket::class.java.getDeclaredField("subscriptions").apply { isAccessible = true }.get(ws) as Map<String, Int?>

    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** A syntactically valid 44-char base58 mint, distinct per [i]. */
    private fun mint(i: Int): String {
        val sb = StringBuilder()
        var n = i
        do { sb.append(alphabet[n % alphabet.length]); n /= alphabet.length } while (n > 0)
        return ("D" + sb.toString()).padEnd(44, '1')
    }

    @Test fun base58BackstopRejectsEveryNonSolanaIdentity() {
        assertTrue(HeliusSolanaScope7819.isSolanaMint7819("So11111111111111111111111111111111111111112"))
        assertTrue(HeliusSolanaScope7819.isSolanaMint7819(mint(7)))
        listOf("SPOT", "KO", "UNH", "STOCK_SPOT", "EURUSD", "XAU", "BTC-PERP", "cg:bitcoin",
            "solana|So11111111111111111111111111111111111111112",
            "0x6b175474e89094c44da98b954eedeac495271d0f", "", "static:ETH",
        ).forEach { assertFalse(it, HeliusSolanaScope7819.isSolanaMint7819(it)) }
        val before = HeliusSubscriptionTelemetry7807.nonSolanaRejected7819.get()
        assertEquals(listOf(mint(1)), HeliusSolanaScope7819.solanaMintsOnly7819(listOf("KO", mint(1), "SPOT")))
        assertEquals(before + 2, HeliusSubscriptionTelemetry7807.nonSolanaRejected7819.get())
    }

    @Test fun stockTickerNeverTakesASubscriptionSlot() {
        val ws = newWs()
        val before = HeliusSubscriptionTelemetry7807.nonSolanaRejected7819.get()
        ws.subscribeToken("SPOT")
        ws.subscribeToken("KO")
        ws.subscribeToken(mint(3))
        assertFalse(desired(ws).containsKey("SPOT"))
        assertFalse(desired(ws).containsKey("KO"))
        assertTrue(desired(ws).containsKey(mint(3)))
        assertEquals(before + 2, HeliusSubscriptionTelemetry7807.nonSolanaRejected7819.get())
    }

    @Test fun pinnedStocksNoLongerEvictMemeDiscovery() {
        val ws = newWs()
        val held = LinkedHashSet<String>()
        for (i in 0 until 100) held.add("STK$i")
        held.add(mint(9999))
        ws.setPinnedMintsProvider7807 { held.toSet() }
        for (i in 0 until 64) ws.subscribeToken(mint(i))
        assertEquals(64, desired(ws).size)
        val evBefore = HeliusSubscriptionTelemetry7807.evictions.get()
        ws.ensurePinnedSubscriptions7807()
        // Only the one real Solana held mint is pinned and re-armed: exactly one eviction.
        assertEquals(1, HeliusSubscriptionTelemetry7807.pinnedHeld)
        assertEquals(evBefore + 1, HeliusSubscriptionTelemetry7807.evictions.get())
        assertTrue(desired(ws).containsKey(mint(9999)))
        assertTrue(desired(ws).keys.none { it.startsWith("STK") })
        // A second housekeeping tick is a no-op (old behaviour: 100 re-arms every 10 s).
        ws.ensurePinnedSubscriptions7807()
        assertEquals(evBefore + 1, HeliusSubscriptionTelemetry7807.evictions.get())
        assertEquals(64, desired(ws).size)
    }

    @Test fun telemetryLineCarriesRejectionsAndReconnectReasons() {
        val ws = newWs()
        ws.reconnectIfStale7819("TEST_REASON_7819")
        val line = HeliusSubscriptionTelemetry7807.line7807()
        listOf("nonSolanaRejected=", "pinnedExcludedNonSolana=", "reconnects=", "reconnectReasons=[",
            "TEST_REASON_7819=", "desiredTokenSubs=", "reconnectResubscribes=",
        ).forEach { assertTrue(it, line.contains(it)) }
    }

    @Test fun sourceContractsForOwnerAndReconnectCauses() {
        val h = src("network/HeliusWebSocket.kt")
        assertTrue(h.contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 64"))
        assertTrue(h.contains("override fun onClosing("))
        assertTrue(h.contains("noteReconnect7819(\"SERVER_CLOSE_\$code\")"))
        assertTrue(h.contains("if (!admitMint7819(mint)) return"))
        val o = src("engine/DataOrchestrator.kt")
        assertTrue(o.contains("heliusWs?.setMintEligibility7819 { m -> heliusMintEligible7819(m) }"))
        assertTrue(o.contains("heliusWs?.reconnectIfStale7819(\"EXTERNAL_STREAM_RECONNECT\")"))
        assertFalse(o.contains("heliusWs?.disconnect(); delay(1_000); heliusWs?.connect()"))
        assertTrue(o.contains("p.assetClass == com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN"))
        val f = src("network/ParallelMarkFanout7088.kt")
        assertTrue(f.contains("solMints7819.chunked(100)"))
        assertTrue(f.contains("HeliusSolanaScope7819.solanaMintsOnly7819(mints).mapNotNull"))
    }
}
