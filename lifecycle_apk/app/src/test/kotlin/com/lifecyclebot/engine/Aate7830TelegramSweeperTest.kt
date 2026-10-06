package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.TelegramCallSweeper7830
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7830 — public Telegram call sweeper. Root cause: TelegramScraper only
 * kept messages mentioning a token the bot already had, so called contract
 * addresses never became candidates.
 */
class Aate7830TelegramSweeperTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val popcat = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pumpMint = "2qEHjDLDLbuBgRYvsxhc5D6uDWAivNFZGan56P1tpump"
    private val pairAddr = "FRhB8L7Y9Qq41qZXYLtC2nw8An1RJfLLxRF2x9RwLLMo"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val sig = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"

    private val html = """
        <div class="tgme_widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post="AlphaCalls/101">
        <div class="tgme_widget_message_text js-message_text" dir="auto">🚀 NEW CALL<br/>CA: $popcat<br>aping now, join @SolGemsHub and @tipbot</div>
        <a class="tgme_widget_message_date" href="https://t.me/AlphaCalls/101"><time datetime="2026-10-06T10:00:00+00:00" class="time">10:00</time></a>
        </div></div>
        <div class="tgme_widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post="AlphaCalls/102">
        <div class="tgme_widget_message_forwarded_from accent_color">Forwarded from <a class="tgme_widget_message_forwarded_from_name" href="https://t.me/PumpInsiders/77">Pump Insiders</a></div>
        <div class="tgme_widget_message_text js-message_text" dir="auto">chart <a href="https://dexscreener.com/solana/$pairAddr">dex</a> and <a href="https://pump.fun/coin/$pumpMint">pump</a> also <a href="https://t.me/joinchat/AAAA">x</a> <a href="https://t.me/MoonRadar">radar</a> paid in $usdc</div>
        <a class="tgme_widget_message_date" href="https://t.me/AlphaCalls/102"><time datetime="2026-10-06T10:05:00+00:00" class="time">10:05</time></a>
        </div></div>
    """.trimIndent()

    @Test fun extracts_mints_from_text_and_links_and_drops_non_mints() {
        val ex = TelegramCallSweeper7830.extractMints7830(
            "CA: $popcat sig $sig usdc $usdc word abcdefghijkmnopqrstuvwxyzabcdefghijk",
            listOf("https://pump.fun/coin/$pumpMint", "https://dexscreener.com/solana/$pairAddr", "https://gmgn.ai/sol/token/ref_$popcat"),
        )
        assertEquals(88, sig.length)
        assertTrue(popcat in ex.mints)
        assertTrue(pumpMint in ex.mints)
        assertTrue(pairAddr in ex.pairLike)
        assertFalse(usdc in ex.mints)
        assertFalse(ex.mints.any { it.length > 44 })
        assertFalse(ex.mints.any { it.startsWith("abcdefghijk") })
        assertFalse(TelegramCallSweeper7830.isPlausibleMint7830("So11111111111111111111111111111111111111112"))
        assertFalse(TelegramCallSweeper7830.isPlausibleMint7830("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
        assertTrue(TelegramCallSweeper7830.isPlausibleMint7830(pumpMint))
    }

    @Test fun parses_preview_posts_ids_timestamps_and_forward_header() {
        val posts = TelegramCallSweeper7830.parsePreview7830(html)
        assertEquals(2, posts.size)
        assertEquals("alphacalls", posts[0].channel)
        assertEquals(101L, posts[0].postId)
        assertEquals(102L, posts[1].postId)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-06T10:00:00+00:00").toInstant().toEpochMilli(), posts[0].tsMs)
        assertTrue(posts[0].text.contains(popcat))
        assertTrue(TelegramCallSweeper7830.looksLikeCall7830(posts[0].text))
        assertFalse(TelegramCallSweeper7830.looksLikeCall7830("weekly market recap"))
        assertEquals("PumpInsiders", posts[1].forwardedFrom)
        val ex = TelegramCallSweeper7830.extractMints7830(posts[1].text, posts[1].hrefs)
        assertTrue(pumpMint in ex.mints)
        assertTrue(pairAddr in ex.pairLike)
    }

    @Test fun discovers_channels_from_mentions_links_and_forwards_only() {
        val posts = TelegramCallSweeper7830.parsePreview7830(html)
        val m0 = TelegramCallSweeper7830.channelMentions7830(posts[0])
        assertTrue("solgemshub" in m0)
        assertFalse("tipbot" in m0)          // bots are never channels
        assertFalse("alphacalls" in m0)      // a channel's own links are not discoveries
        val m1 = TelegramCallSweeper7830.channelMentions7830(posts[1])
        assertTrue("pumpinsiders" in m1)
        assertTrue("moonradar" in m1)
        assertFalse("joinchat" in m1)
        assertNull(TelegramCallSweeper7830.normalizeChannel7830("ab"))
        assertNull(TelegramCallSweeper7830.normalizeChannel7830("9badstart"))
        assertEquals("goodname", TelegramCallSweeper7830.normalizeChannel7830("@GoodName"))
        assertEquals(1, TelegramCallSweeper7830.offerChannels(listOf("@SomeCallers", "x", "pricebot")))
    }

    @Test fun only_public_previews_with_posts_are_admitted() {
        assertTrue(TelegramCallSweeper7830.isPublicPreview7830("/s/alphacalls", html))
        assertFalse(TelegramCallSweeper7830.isPublicPreview7830("/alphacalls", html)) // private -> redirected
        assertFalse(TelegramCallSweeper7830.isPublicPreview7830("/s/alphacalls", "<html>no posts</html>"))
    }

    @Test fun channel_score_orders_sweeps_and_is_neutral_until_proven() {
        assertEquals(0.5, TelegramCallSweeper7830.channelScore7830(2, 2, 300.0), 1e-9)
        val good = TelegramCallSweeper7830.channelScore7830(10, 7, 60.0)
        val bad = TelegramCallSweeper7830.channelScore7830(10, 0, -60.0)
        assertTrue(good > 0.5 && bad < 0.2)
        assertTrue(TelegramCallSweeper7830.sweepIntervalMs7830(good, 0) < TelegramCallSweeper7830.sweepIntervalMs7830(0.5, 0))
        assertTrue(TelegramCallSweeper7830.sweepIntervalMs7830(bad, 0) > TelegramCallSweeper7830.sweepIntervalMs7830(0.5, 0))
        assertTrue(TelegramCallSweeper7830.sweepIntervalMs7830(0.5, 3) > TelegramCallSweeper7830.sweepIntervalMs7830(0.5, 1))
        assertEquals(2.0, TelegramCallSweeper7830.median7830(listOf(5.0, 1.0, 2.0)), 1e-9)
        assertEquals(0.0, TelegramCallSweeper7830.median7830(emptyList()), 1e-9)
    }

    @Test fun channel_registry_rows_round_trip() {
        val c = TelegramCallSweeper7830.Channel7830("alphacalls", "MENTION:seed", 123L)
        c.lastPostId = 102L; c.calls = 4; c.booked60 = 3; c.hits60 = 2; c.firstCalls = 1
        c.rets60.addAll(listOf(25.5, -10.0, 40.0))
        val back = TelegramCallSweeper7830.decodeChannel7830(TelegramCallSweeper7830.encodeChannel7830(c))
        assertNotNull(back)
        assertEquals(102L, back!!.lastPostId)
        assertEquals(3L, back.booked60)
        assertEquals(listOf(25.5, -10.0, 40.0), back.rets60)
        assertEquals(c.score(), back.score(), 1e-9)
        assertNull(TelegramCallSweeper7830.decodeChannel7830("garbage"))
        assertTrue(TelegramCallSweeper7830.topChannels7830(5).size <= 5)
        assertTrue(TelegramCallSweeper7830.earliestCallers7830(3).size <= 3)
        TelegramCallSweeper7830.tickCalls7830(System.currentTimeMillis()) { null } // no tracked calls: no-op
        assertTrue(TelegramCallSweeper7830.statusLine().contains("authority=candidates_only"))
    }

    @Test fun calls_enter_the_normal_intake_with_no_bonus_or_bypass() {
        val sc = src("engine/SolanaMarketScanner.kt")
        assertTrue(sc.contains("TELEGRAM_CALL,"))
        assertTrue(sc.contains("TokenSource.TELEGRAM_CALL -> EfficiencyLayer.LiqSourceQuality.DEX_AGGREGATOR"))
        val fn = sc.substringAfter("suspend fun emitTelegramCall7830(").substringBefore("private fun stampLlmScout7830")
        assertTrue(fn.contains("buildScannedToken(mint, pair, TokenSource.TELEGRAM_CALL)"))
        assertTrue(fn.contains("passesFilter(token)"))
        assertTrue(fn.contains("emitWithRugcheck(token)"))
        assertTrue(fn.contains("ScannerHardRejectStore.isRejected(mint)"))
        val w = src("engine/market/TelegramCallSweeper7830.kt")
        assertTrue(w.contains("https://t.me/s/\$name"))
        assertFalse(w.contains("api.telegram.org"))   // no bot API, no login, no posting
        assertTrue(w.contains("dex.getBestPair("))     // every address verified before intake
        assertTrue(w.contains("TelegramScraper.DEFAULT_CHANNELS_7830"))
        assertTrue(w.contains("GroqTokenScout7830.discoveredTelegramChannels()"))
    }

    @Test fun bot_service_and_health_report_wire_the_sweeper() {
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("startTelegramCallSweeper7830()"))
        assertTrue(bs.contains("TelegramCallSweeper7830.stop(\"stopBot:\$source\")"))
        assertTrue(bs.contains("marketScanner?.emitTelegramCall7830(mint, pair)"))
        val ph = src("engine/PipelineHealthCollector.kt")
        assertTrue(ph.contains("TelegramCallSweeper7830.statusLine()"))
    }
}
