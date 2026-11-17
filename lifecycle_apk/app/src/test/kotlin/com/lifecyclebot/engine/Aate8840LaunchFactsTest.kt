package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchFacts8840
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.HeliusCurveTape8354
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8840 — who is behind each launch, from the decoded pump.fun create and trade events. */
class Aate8840LaunchFactsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun borsh(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf((b.size and 0xFF).toByte(), ((b.size shr 8) and 0xFF).toByte(), 0, 0) + b
    }

    @Test fun theCreateEventIsDecodedFieldByField() {
        val disc = byteArrayOf(27, 114, 169.toByte(), 77, 222.toByte(), 235.toByte(), 99, 118)
        val mint = ByteArray(32) { (it + 1).toByte() }; val curve = ByteArray(32) { (it + 40).toByte() }; val user = ByteArray(32) { (it + 80).toByte() }
        val tail = ByteArray(8 * 5)   // timestamp and reserves (not read)
        val bytes = disc + borsh("Moon Cat") + borsh("MCAT") + borsh("https://ipfs.io/ipfs/Qm123") + mint + curve + user + tail
        val ev = ChainLaunchFeed8179.decodeCreateEvent8840(bytes)!!
        assertEquals("Moon Cat", ev.name); assertEquals("MCAT", ev.symbol); assertEquals("https://ipfs.io/ipfs/Qm123", ev.uri)
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(mint), ev.mint)
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(user), ev.user)
        assertNull("another event", ChainLaunchFeed8179.decodeCreateEvent8840(byteArrayOf(9) + bytes.copyOfRange(1, bytes.size)))
        assertNull("truncated", ChainLaunchFeed8179.decodeCreateEvent8840(bytes.copyOfRange(0, bytes.size - 40 - 20)))
        val line = "Program data: " + java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(line.startsWith(HeliusCurveTape8354.CREATE_LINE_PREFIX_8840))
        assertEquals(ev, ChainLaunchFeed8179.decodeCreateEventLine8840(line))
        // a TradeEvent line never matches the create prefix
        assertTrue(!("Program data: " + java.util.Base64.getEncoder().encodeToString(byteArrayOf(189.toByte(), 219.toByte(), 127, 211.toByte(), 78, 230.toByte(), 97, 238.toByte()) + ByteArray(120)))
            .startsWith(HeliusCurveTape8354.CREATE_LINE_PREFIX_8840))
    }

    @Test fun theFoldStatesTheCreatorBundleSnipersAndHolders() {
        val f = LaunchFacts8840.Fold8840("DEV", "SIGC")
        val p = 3.0e-8
        f.add(1_000_000L, p, 1.0, true, "DEV", "SIGC")           // the create transaction's own buy: 33.33 M tokens
        f.add(1_000_400L, p, 0.5, true, "A", "S1")               // same chain second: bundled
        f.add(1_001_500L, p, 0.3, true, "B", "S2")               // one second later: sniper, not bundle
        f.add(1_005_000L, p, 0.2, true, "C", "S3")               // five seconds: neither
        f.add(1_100_000L, p, 0.5, false, "DEV", "S4")            // the creator sells half, inside ten minutes
        val x = f.snapshot()
        val pct = { sol: Double -> 100.0 * (sol / p) / LaunchFacts8840.SUPPLY_TOKENS }
        assertTrue(x.creatorKnown)
        assertEquals(pct(1.0), x.devInitPct, 1e-9); assertEquals(pct(0.5), x.devHeldPct, 1e-9)
        assertEquals(0.5, x.devSoldFrac, 1e-12); assertEquals(0.5, x.devSold10mFrac, 1e-12)
        assertEquals(1, x.bundleBuyers); assertEquals(pct(0.5), x.bundlePct, 1e-9)
        assertEquals(2, x.sniperBuyers); assertEquals(pct(0.8), x.sniperPct, 1e-9)
        assertEquals(4, x.uniqueBuyers)
        assertEquals(pct(0.5), x.top1Pct, 1e-9); assertEquals(pct(1.5), x.top5Pct, 1e-9)
        // no creator known: the dev facts are unobserved, never 0
        val anon = LaunchFacts8840.Fold8840("", "").apply { add(1_000L, p, 1.0, true, "A", "S") }.snapshot()
        assertTrue(anon.devInitPct.isNaN() && anon.devHeldPct.isNaN() && !anon.creatorKnown)
    }

    @Test fun aCreatorsHistoryIsReadAsOfTheLaunch() {
        LaunchFacts8840.DevHistory8840.resetForTest8840()
        assertEquals(Triple(0, 0, 0), LaunchFacts8840.DevHistory8840.of("DEVX"))
        LaunchFacts8840.DevHistory8840.note("DEVX", runner = false, dump = true)
        LaunchFacts8840.DevHistory8840.note("DEVX", runner = true, dump = false)
        assertEquals(Triple(2, 1, 1), LaunchFacts8840.DevHistory8840.of("DEVX"))
        LaunchFacts8840.DevHistory8840.resetForTest8840()
    }

    @Test fun everyReaderIsWired() {
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("LaunchFacts8840.onPrint8840(mint, eventMs, priceSol, sol, isBuy, wallet, sig, recvMs)"))
        val tape = src("network/HeliusCurveTape8354.kt")
        assertEquals(2, Regex("creates8840\\(logs, sig, nowMs\\)").findAll(tape).count())
        assertTrue(src("network/PumpFunWS.kt").contains("LaunchFacts8840.onCreate8840("))
        assertTrue(LearnerColumns8798.NAMES.containsAll(listOf("devInitPct", "bundlePct", "sniperPct", "top5Pct", "devPriorRunnerRate")))
        val lh = src("engine/truth/LaunchHour8628.kt")
        assertTrue(lh.contains("CREATE TABLE IF NOT EXISTS creator(") && lh.contains("put(\"kind\", \"FACTS_8840\")"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("LaunchFacts8840.statusLine8840()"))
    }
}
