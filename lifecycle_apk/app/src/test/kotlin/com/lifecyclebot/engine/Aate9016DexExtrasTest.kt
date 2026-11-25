package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.TreeRanker8737
import com.lifecyclebot.network.DexExtras9016
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9016 — the rest of what DexScreener gives for free (trending narratives and the coins in them, paid ads, profile
 * edits, the pool's type tag, the push feeds) is read, and reaches the learner as columns appended after every older one.
 */
class Aate9016DexExtrasTest {
    private val mintA = "So11111111111111111111111111111111111111112"
    private val mintB = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    @Test fun trendingNarrativesAreReadAndTheHottestHourComesFirst() {
        val body = """[
          {"name":"AI Agents","slug":"ai-agents","tokenCount":412,"marketCapChange":{"m5":0.2,"h1":14.5,"h6":-3.0,"h24":40.1}},
          {"name":"Cats","slug":"cats","tokenCount":90,"marketCapChange":{"h1":-6.0,"h6":2.5}},
          {"name":"No change yet","slug":"new-one","tokenCount":3},
          {"name":"no slug","tokenCount":7}
        ]"""
        val metas = DexExtras9016.parseMetas9016(body, 1_000L)
        assertEquals(listOf("ai-agents", "cats", "new-one"), metas.map { it.slug })
        assertEquals(14.5, metas[0].capChgH1, 1e-9); assertEquals(-3.0, metas[0].capChgH6, 1e-9); assertEquals(412, metas[0].tokens)
        assertTrue("an unread change is NaN, never 0", metas[2].capChgH1.isNaN() && metas[2].capChgH6.isNaN())
        assertEquals(listOf("ai-agents", "cats"), DexExtras9016.hottest9016(metas, 2).map { it.slug })
        assertEquals("an unread change sorts last", "new-one", DexExtras9016.hottest9016(metas, 3).last().slug)
        // the coins of the fastest-falling narratives are fetched too, written first so the hotter narrative wins a shared coin
        assertEquals(listOf("cats", "ai-agents"), DexExtras9016.watched9016(metas, n = 1, cold = 1).map { it.slug })
        assertEquals("an unread change is neither hot nor cold", listOf("cats", "ai-agents"), DexExtras9016.watched9016(metas, n = 1, cold = 5).map { it.slug })
        assertTrue(DexExtras9016.parseMetas9016("not json", 1L).isEmpty()); assertTrue(DexExtras9016.parseMetas9016(null, 1L).isEmpty())
        assertEquals("the same list wrapped as the socket sends it", 3, DexExtras9016.parseMetas9016("""{"limit":90,"data":$body}""", 1L).size)
    }

    @Test fun aNarrativesSolanaCoinsAreItsMints() {
        val body = """{"name":"Cats","slug":"cats","pairs":[
          {"chainId":"solana","baseToken":{"address":"$mintA","symbol":"A"}},
          {"chainId":"solana","baseToken":{"address":"$mintA","symbol":"A"}},
          {"chainId":"base","baseToken":{"address":"0x4200000000000000000000000000000000000006"}},
          {"chainId":"solana","baseToken":{"address":"$mintB"}},
          {"chainId":"solana"}
        ]}"""
        assertEquals(listOf(mintA, mintB), DexExtras9016.parseMetaMints9016(body))
        assertTrue(DexExtras9016.parseMetaMints9016("""{"name":"x"}""").isEmpty()); assertTrue(DexExtras9016.parseMetaMints9016(null).isEmpty())
    }

    @Test fun adsAreReadFromThePollAndFromThePushInTheSameWay() {
        val rows = """[
          {"url":"u","chainId":"solana","tokenAddress":"$mintA","date":"2026-11-25T10:00:00Z","type":"tokenAd","durationHours":24,"impressions":150000},
          {"url":"u","chainId":"ethereum","tokenAddress":"0xdAC17F958D2ee523a2206206994597C13D831ec7","type":"tokenAd"},
          {"url":"u","chainId":"solana","tokenAddress":"$mintB","date":"2026-11-25T10:00:00Z","type":"trendingBarAd","durationHours":null,"impressions":null}
        ]"""
        val polled = DexExtras9016.parseAds9016(rows, 5_000L)
        assertEquals(setOf(mintA, mintB), polled.keys)
        val a = polled.getValue(mintA)
        assertEquals("tokenAd", a.type); assertEquals(24.0, a.durationHours, 1e-9); assertEquals(150_000.0, a.impressions, 1e-9)
        assertEquals(java.time.Instant.parse("2026-11-25T10:00:00Z").toEpochMilli(), a.dateMs)
        assertTrue("a null duration or impression count is unread", polled.getValue(mintB).durationHours.isNaN() && polled.getValue(mintB).impressions.isNaN())
        assertEquals(polled.keys, DexExtras9016.parseAds9016("""{"limit":90,"data":$rows}""", 5_000L).keys)
        // an ad runs for its own duration from its date; with neither, for a day from when it was seen
        assertTrue(DexExtras9016.adLive9016(a, a.dateMs + 23L * 3_600_000L))
        assertFalse(DexExtras9016.adLive9016(a, a.dateMs + 25L * 3_600_000L))
        val undated = DexExtras9016.Ad("tokenAd", Double.NaN, Double.NaN, 0L, 1_000L)
        assertTrue(DexExtras9016.adLive9016(undated, 1_000L + 3_600_000L)); assertFalse(DexExtras9016.adLive9016(undated, 1_000L + 25L * 3_600_000L))
    }

    @Test fun profileEditsAndPoolLabels() {
        val rows = """[{"chainId":"solana","tokenAddress":"$mintA","url":"u"},{"chainId":"bsc","tokenAddress":"0x55d398326f99059fF775485246999027B3197955"},{"chainId":"solana","tokenAddress":"$mintA"}]"""
        assertEquals(listOf(mintA), DexExtras9016.parseProfiles9016(rows))
        assertEquals(listOf(mintA), DexExtras9016.parseProfiles9016("""{"limit":90,"data":$rows}"""))
        assertTrue("never seen in the feed is unread", DexExtras9016.profileUpdAgeMin9016("9016NeverSeenMint11111111111111111111111111", 1_000L).isNaN())
        assertTrue(DexExtras9016.labelCode9016(null).isNaN()); assertTrue(DexExtras9016.labelCode9016(" ").isNaN())
        assertEquals(1.0, DexExtras9016.labelCode9016("CPMM"), 0.0); assertEquals(2.0, DexExtras9016.labelCode9016("clmm"), 0.0)
        assertEquals(3.0, DexExtras9016.labelCode9016("DLMM"), 0.0); assertEquals(9.0, DexExtras9016.labelCode9016("somethingNew"), 0.0)
        val m = "9016LabelMint1111111111111111111111111111111"
        assertTrue(DexExtras9016.labelOf9016(m).isNaN())
        DexExtras9016.noteLabels9016(m, JSONArray()); assertTrue("a pair with no label says nothing", DexExtras9016.labelOf9016(m).isNaN())
        DexExtras9016.noteLabels9016(m, JSONArray("[\"DLMM\"]")); assertEquals(3.0, DexExtras9016.labelOf9016(m), 0.0)
        assertNull(DexExtras9016.metaOf9016(m, 1_000L)); assertNull(DexExtras9016.adOf9016(m, 1_000L))
        assertTrue(DexExtras9016.statusLine9016().startsWith("dexExtras9016[metas="))
    }

    @Test fun theColumnsAreAppendedAfterEveryOlderOne() {
        val added = listOf("metaHeat1h", "metaHeat6h", "metaTokens", "dexAd", "dexAdImpr", "profileUpdAgeMin", "poolLabel")
        assertEquals("older columns keep their positions", added, LearnerColumns8798.NAMES.takeLast(added.size))
        assertEquals("devPriorDumpRate", LearnerColumns8798.NAMES[LearnerColumns8798.SIZE - added.size - 1])
        assertEquals(added, TreeRanker8737.FEATURES.takeLast(added.size))
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearnerColumns8798.kt").readText()
        val fn = s.substringAfter("fun extract(ts: TokenState").substringBefore("fun fromRow(")
        assertTrue(fn.contains("dx.tick9016(nowMs)"))
        assertTrue("no ad is 0 only while the ad list is fresh; otherwise unread",
            fn.contains("if (ad != null) { put(\"dexAd\", 1.0); put(\"dexAdImpr\", ad.impressions) } else if (dx.adsFresh9016(nowMs)) put(\"dexAd\", 0.0)"))
        for (c in added) assertTrue(c, fn.contains("put(\"$c\""))
    }

    @Test fun itNeverSharesAFailureWithThePriceLookups() {
        assertEquals("dexscreener_extras", DexExtras9016.HEALTH_HOST)
        val s = File("src/main/kotlin/com/lifecyclebot/network/DexExtras9016.kt").readText()
        assertTrue(s.contains("HealthAwareHttp.execute(http, req, host = HEALTH_HOST)"))
        assertTrue("the poll carries on while a socket is quiet", s.contains("nowMs - adsPushAtMs > WS_QUIET_MS") && s.contains("nowMs - updPushAtMs > WS_QUIET_MS"))
        assertTrue("a failed read is asked again at the same pace, never at once", s.contains("adsTriedMs = nowMs") && s.contains("updTriedMs = nowMs") && s.contains("metasTriedMs = nowMs"))
        assertTrue(s.contains("if (nowMs - wsTriedAtMs < wsRetryMs9016(wsFails)) return"))
        assertEquals("a socket is re-opened no sooner than a minute", 60_000L, DexExtras9016.wsRetryMs9016(0))
        assertEquals(240_000L, DexExtras9016.wsRetryMs9016(2))
        assertEquals("a feed that never answers is knocked on every 30 minutes at most", 30L * 60_000L, DexExtras9016.wsRetryMs9016(10))
        assertEquals(30L * 60_000L, DexExtras9016.wsRetryMs9016(99))
        assertTrue("a unit test has no network stack", s.contains("AATEApp.appContextOrNull()"))
        val api = File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        assertEquals("both pair readers note the pool's label", 2, Regex("DexExtras9016\\.noteLabels9016\\(mint, ").findAll(api).count())
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText().contains("DexExtras9016.statusLine9016()"))
    }
}
