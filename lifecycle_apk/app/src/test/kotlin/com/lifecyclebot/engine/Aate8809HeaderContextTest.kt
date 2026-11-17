package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.network.DexScreenerSocialSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8809 — owner: "I look for dex boosted coins ... with shout outs etc and chatter"; Dex Paid is on 14 of his 16 GMGN
 * screenshots. The dataset carried boost / socials on 147 of 60,792 rows. Socials and boosts now come from every pair the
 * bot is already served, and Dex Paid from DexScreener's orders endpoint for each coin a decision is made on.
 */
class Aate8809HeaderContextTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun dexPaidIsAnApprovedOrderAndItsEarliestPayment() {
        val paid = """[{"type":"tokenProfile","status":"approved","paymentTimestamp":1700000300000},
            {"type":"tokenAd","status":"processing","paymentTimestamp":1700000100000},
            {"type":"communityTakeover","status":"approved","paymentTimestamp":1700000200000}]"""
        assertEquals(1 to 1700000200000L, DexScreenerSocialSource.parseOrders8809(paid))
        assertEquals("an empty list is a real answer: not paid", 0 to 0L, DexScreenerSocialSource.parseOrders8809("[]"))
        assertEquals(0 to 0L, DexScreenerSocialSource.parseOrders8809("""[{"type":"tokenProfile","status":"rejected"}]"""))
        assertEquals(1 to 5L, DexScreenerSocialSource.parseOrders8809("""{"orders":[{"status":"approved","paymentTimestamp":5}]}"""))
        assertNull("an error page is not an answer", DexScreenerSocialSource.parseOrders8809("<html>429</html>"))
        assertNull(DexScreenerSocialSource.parseOrders8809(null))
    }

    @Test fun anAnswerIsReusedForAsLongAsItCanStillBeTrue() {
        val t = 10_000_000L
        assertTrue("paid stays paid for hours", DexScreenerSocialSource.ordersFresh8809(1, t, t + 5L * 60 * 60_000L))
        assertFalse("an unpaid coin can pay at any time", DexScreenerSocialSource.ordersFresh8809(0, t, t + 6L * 60_000L))
        assertTrue(DexScreenerSocialSource.ordersFresh8809(0, t, t + 60_000L))
        assertFalse("a failed ask is retried soon", DexScreenerSocialSource.ordersFresh8809(-1, t, t + 3L * 60_000L))
        assertFalse("never asked", DexScreenerSocialSource.ordersFresh8809(-1, 0L, t))
    }

    @Test fun aServedPairIsAnObservationOfItsSocialsAndBoosts() {
        val mint = "Hdr8809aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaapump"
        assertNull(DexScreenerSocialSource.peek7895(mint))
        DexScreenerSocialSource.observePair8809(mint, 0, 0.0)
        val none = DexScreenerSocialSource.peek7895(mint)!!
        assertEquals("no links is a reading of zero", 0, none.socialCount); assertEquals(0.0, none.boostTotal, 0.0)
        assertEquals("Dex Paid was never asked", -1, none.dexPaid)
        DexScreenerSocialSource.observePair8809(mint, 3, 50.0)
        DexScreenerSocialSource.observePair8809(mint, 1, 0.0)
        val seen = DexScreenerSocialSource.peek7895(mint)!!
        assertEquals(3, seen.socialCount); assertEquals(50.0, seen.boostTotal, 0.0)
        DexScreenerSocialSource.wantOrders8809(mint, 1L)                       // no running app in a unit test: queued, never fetched
        assertTrue(DexScreenerSocialSource.statusLine8809().contains("pairsObserved="))
    }

    @Test fun theCacheIsTrimmedInBoundedWork() {
        assertEquals(0, DexScreenerSocialSource.pruneCount8809(12_000, 12_000))
        assertEquals("over the cap it drops to three quarters, so the next trim is thousands of writes away", 3_001, DexScreenerSocialSource.pruneCount8809(12_001, 12_000))
        val source = src("network/DexScreenerSocialSource.kt")
        assertTrue("at most one trim per gap, never one per write", source.contains("if (now - last < PRUNE_GAP_MS_8809 || !lastPruneMs8809.compareAndSet(last, now)) return"))
        assertFalse(source.contains("if (cache.size > 4000) {"))
    }

    @Test fun theColumnsReachTheDatasetAndTheReport() {
        assertTrue(LearnerColumns8798.NAMES.containsAll(listOf("dexPaid", "dexPaidAgeMin", "boost", "socials")))
        // V5.0.8840 appends its launch-fact columns after these two: every earlier column still keeps its place
        val i = LearnerColumns8798.NAMES.indexOf("dexPaid")
        assertEquals("appended in order, earlier columns unmoved", listOf("dexPaid", "dexPaidAgeMin", "creatorKnown"), LearnerColumns8798.NAMES.subList(i, i + 3))
        assertTrue(src("network/DexscreenerApi.kt").contains("DexScreenerSocialSource.observePair8809(baseAddr7385, socialsList.size + websitesList.size,"))
        assertTrue(src("engine/truth/LearnerColumns8798.kt").contains("src.wantOrders8809(mint, nowMs)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("DexScreenerSocialSource.statusLine8809()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 16. Header context on every decision (8809)"))
    }
}
