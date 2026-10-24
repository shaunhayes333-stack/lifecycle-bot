package com.lifecyclebot.engine

import com.lifecyclebot.engine.HeliusCadence8361.AlphaCandidate8361
import com.lifecyclebot.perps.WhaleListClean8362
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8362 — alpha list ranked by a lower confidence bound; the seeded whale list keeps trading wallets only. */
class Aate8362AlphaWhaleTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── alpha ranking by confidence ──

    @Test fun lcbIsMeanMinusOneStandardError() {
        assertEquals(10.0 - 20.0 / 2.0, HeliusCadence8361.lcbOf8362(10.0, 20.0, 4), 1e-9)
        assertEquals(1.0, HeliusCadence8361.LCB_Z_8362, 0.0)
        assertTrue(HeliusCadence8361.lcbOf8362(10.0, 1.0, 0).isNaN())
        assertTrue(HeliusCadence8361.lcbOf8362(Double.NaN, 1.0, 5).isNaN())
    }

    @Test fun candidateRowCarriesClippedSampleSd() {
        val r = HeliusCadence8361.candidateOf8361("W", listOf(5_000.0, -50.0, 10.0), 0)
        val xs = listOf(300.0, -50.0, 10.0)
        val m = xs.sum() / 3.0
        val sd = Math.sqrt(xs.sumOf { (it - m) * (it - m) } / 2.0)
        assertEquals(m, r.meanNetPct, 1e-9)
        assertEquals(sd, r.sdNetPct, 1e-9)
        assertEquals(m - sd / Math.sqrt(3.0), r.lcbNetPct, 1e-9)
        assertEquals(0.0, HeliusCadence8361.candidateOf8361("W", listOf(12.0), 0).sdNetPct, 0.0)
    }

    @Test fun smallPerfectSampleDoesNotBeatLongPositiveRecord() {
        // 5-for-5 with one +300 % outlier (clipped) — a perfect win rate on a small sample
        val small = HeliusCadence8361.candidateOf8361("SMALL", listOf(400.0, 5.0, 5.0, 5.0, 5.0), 0)
        // 70 % over 200 trips: 140 x +30 %, 60 x -20 %  (mean +15 %)
        val long = HeliusCadence8361.candidateOf8361("LONG", List(140) { 30.0 } + List(60) { -20.0 }, 0)
        assertEquals(1.0, small.winRate, 0.0)
        assertEquals(0.7, long.winRate, 1e-9)
        assertTrue(long.meanNetPct > 0.0)
        assertTrue(HeliusCadence8361.qualifies8362(small))
        assertTrue(HeliusCadence8361.qualifies8362(long))
        assertTrue(long.lcbNetPct > small.lcbNetPct)
        assertEquals(listOf("LONG", "SMALL"), HeliusCadence8361.rankAlpha8362(listOf(small, long)).map { it.wallet })
    }

    private fun c(w: String, n: Int, mean: Double, sd: Double) = AlphaCandidate8361(w, n, n / 2, mean, 0, sd)

    @Test fun qualificationNeedsFiveTripsAndAPositiveBound() {
        assertFalse(HeliusCadence8361.qualifies8362(c("A", 4, 50.0, 0.0)))          // n < 5
        assertTrue(HeliusCadence8361.qualifies8362(c("B", 5, 50.0, 0.0)))
        assertFalse(HeliusCadence8361.qualifies8362(c("C", 25, 10.0, 60.0)))        // mean > 0 but lcb = 10 - 12 < 0
        assertFalse(HeliusCadence8361.qualifies8362(c("D", 25, 10.0, 50.0)))        // lcb exactly 0 -> out
        assertTrue(HeliusCadence8361.qualifies8362(c("E", 25, 10.0, 40.0)))         // lcb = 2
        assertFalse(HeliusCadence8361.qualifies8362(c("", 25, 10.0, 0.0)))
        assertFalse(HeliusCadence8361.qualifies8362(c("F", 25, Double.NaN, 0.0)))
    }

    @Test fun tiesGoToMoreTripsThenHigherMeanAndTopTwentyFiveKept() {
        val ranked = HeliusCadence8361.rankAlpha8362(listOf(
            c("X", 25, 12.0, 10.0),   // lcb 10
            c("Y", 100, 15.0, 50.0),  // lcb 10, more trips -> before X
            c("Z", 25, 10.0, 0.0),    // lcb 10, n 25, lower mean -> after X
        )).map { it.wallet }
        assertEquals(listOf("Y", "X", "Z"), ranked)
        val many = (1..40).map { c("W%02d".format(it), 10, it.toDouble(), 0.0) }
        assertEquals(25, HeliusCadence8361.rankAlpha8362(many).size)
        assertEquals(HeliusCadence8361.ALPHA_MAX_8361, 25)
    }

    @Test fun statusShowsRankLcbAndTopFiveRows() {
        val rows = (1..7).map { c("Wallet$it" + "x".repeat(30), 10, 20.0 + it, 10.0) }
        val top = HeliusCadence8361.topLine8362(rows, 5)
        assertEquals(5, top.split(",").size)
        assertTrue(top, top.startsWith("Wallet:n10/m+21.0/lcb+17.8"))
        assertEquals("-", HeliusCadence8361.topLine8362(emptyList(), 5))
        val line = HeliusCadence8361.statusLine8361()
        assertTrue(line, line.contains(" rank=lcb "))
        assertTrue(line, line.contains(" top="))
        assertTrue(line, line.contains("whales8361[watched=") && line.contains(" removed="))
    }

    @Test fun storedRowsRoundTripAndRebuildUsesTheBound() {
        val rows = listOf(AlphaCandidate8361("A1", 12, 8, 14.5, 3, 9.25))
        val back = ExpertWallets7962.decodeAlphaRows8362(JSONArray(ExpertWallets7962.encodeAlphaRows8362(rows).toString()))
        assertEquals(rows, back)
        val ew = src("engine/ExpertWallets7962.kt")
        assertTrue(ew.contains("val rows8362 = HeliusCadence8361.rankAlpha8362(alphaCandidates8361())"))
        assertFalse(ew.contains("rankAlpha8361("))
        // copy stays behind its proof bar
        assertTrue(src("engine/truth/SignalSourceProof7291.kt").contains("else -> \"PAPER_ONLY\""))
    }

    // ── whale list: trading wallets only ──

    @Test fun mintAndTreasuryClassificationFromLabels() {
        assertEquals("MINT", WhaleListClean8362.classifyByLabel8362("MELANIA Token Mint", "Official MELANIA meme coin mint address"))
        assertEquals("TREASURY", WhaleListClean8362.classifyByLabel8362("TRUMP Token Treasury", "Official TRUMP meme coin treasury"))
        assertEquals("TREASURY", WhaleListClean8362.classifyByLabel8362("Trump CIC Digital Treasury", "CIC Digital LLC TRUMP token treasury wallet"))
        assertEquals("TREASURY", WhaleListClean8362.classifyByLabel8362("Team", "token vesting account"))
        assertEquals("TREASURY", WhaleListClean8362.classifyByLabel8362("DAO Multisig", ""))
        assertNull(WhaleListClean8362.classifyByLabel8362("Ansem", "Top Solana alpha caller (@blknoiz06)"))
        assertNull(WhaleListClean8362.classifyByLabel8362("TRUMP Deployer", "TRUMP token deployment wallet"))
        assertNull(WhaleListClean8362.classifyByLabel8362("Minty Fresh Trader", "trades memes"))
    }

    @Test fun mintClassificationFromAccountOwner() {
        assertEquals("MINT", WhaleListClean8362.classifyByAccount8362(WhaleListClean8362.TOKEN_PROGRAM_8362, "mint", 82))
        assertEquals("MINT", WhaleListClean8362.classifyByAccount8362(WhaleListClean8362.TOKEN_2022_PROGRAM_8362, "mint", 250))
        assertEquals("MINT", WhaleListClean8362.classifyByAccount8362(WhaleListClean8362.TOKEN_PROGRAM_8362, null, 82))
        assertNull(WhaleListClean8362.classifyByAccount8362(WhaleListClean8362.TOKEN_PROGRAM_8362, "account", 165))   // a token account
        assertNull(WhaleListClean8362.classifyByAccount8362("11111111111111111111111111111111", null, 0))           // a system wallet
        assertNull(WhaleListClean8362.classifyByAccount8362(null, null, -1))
        val res = JSONObject().put("value", JSONObject().put("owner", WhaleListClean8362.TOKEN_PROGRAM_8362).put("space", 82)
            .put("data", JSONObject().put("program", "spl-token").put("parsed", JSONObject().put("type", "mint"))))
        assertEquals("MINT", WhaleListClean8362.classifyAccountResult8362(res))
        assertNull(WhaleListClean8362.classifyAccountResult8362(JSONObject().put("value", JSONObject.NULL)))
        val wallet = JSONObject().put("value", JSONObject().put("owner", "11111111111111111111111111111111").put("space", 0)
            .put("data", JSONArray().put("").put("base64")))
        assertNull(WhaleListClean8362.classifyAccountResult8362(wallet))
    }

    @Test fun removedListIsPersistedAndSurvivesAReseed() {
        val removed = mapOf("FUAfBo2jgks6gB4Z4LfZkqSZgzNucisEHqnNebaRxM1P" to "MINT", "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN" to "TREASURY")
        val back = WhaleListClean8362.decodeRemoved8362(WhaleListClean8362.encodeRemoved8362(removed))
        assertEquals(removed, back)
        assertTrue(WhaleListClean8362.decodeRemoved8362(null).isEmpty())
        assertTrue(WhaleListClean8362.decodeRemoved8362("not json").isEmpty())
        val seeds = listOf("Gi5UgFBFgjdLpiLvigPYMJkGxbUJNz6QDtmm5mzH9tD5", "FUAfBo2jgks6gB4Z4LfZkqSZgzNucisEHqnNebaRxM1P", "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN")
        assertEquals(listOf("Gi5UgFBFgjdLpiLvigPYMJkGxbUJNz6QDtmm5mzH9tD5"), WhaleListClean8362.seedsAfterRemoval8362(seeds, back))
    }

    @Test fun trackerSeedsThroughTheCleanerAndBigMoveRuleUnchanged() {
        val t = src("perps/InsiderWalletTracker.kt")
        assertTrue(t.contains("val kept8362 = cleanSeeds8362(defaults)"))
        assertTrue(t.contains("if (removed8362.containsKey(w.address)) { insiderWallets.remove(w.address); continue }"))
        assertTrue(t.contains("\"WHALE_LIST_REMOVED_8362_\$reason\""))
        assertTrue(t.contains("KeylessRpc8359.post8359(client, \"getAccountInfo\""))
        assertEquals(25.0, HeliusCadence8361.WHALE_BIG_SOL_8361, 0.0)
        assertEquals(5_000.0, HeliusCadence8361.WHALE_BIG_USD_8361, 0.0)
    }
}
