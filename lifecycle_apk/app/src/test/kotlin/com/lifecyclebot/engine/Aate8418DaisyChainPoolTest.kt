package com.lifecyclebot.engine

import com.lifecyclebot.network.CapacityBucket8418
import com.lifecyclebot.network.CapacityBucket8418.Outcome8418
import com.lifecyclebot.network.LlmCapacityPool8418
import com.lifecyclebot.network.LlmDaisyChain8418
import com.lifecyclebot.network.LlmProviderPresets8418
import com.lifecyclebot.network.LlmResponseCache8418
import com.lifecyclebot.network.NewsHeadlinePool8418
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** V5.0.8418 — providers daisy-chain into one pool: own budgets, failover down the chain, no cancel, shared cache. */
class Aate8418DaisyChainPoolTest {

    @Before fun reset() {
        LlmCapacityPool8418.resetForTests8418()
        LlmResponseCache8418.resetForTests8418()
        NewsHeadlinePool8418.resetForTests8418()
    }

    @After fun clean() {
        LlmCapacityPool8418.resetForTests8418()
        LlmResponseCache8418.resetForTests8418()
        NewsHeadlinePool8418.resetForTests8418()
    }

    private val noRefusal: (Any?) -> Boolean = { false }
    private val notBenched: (String) -> Boolean = { false }

    // ── the bucket ────────────────────────────────────────────────────────

    @Test fun `a bucket spends its own minute and refills at its own rate`() {
        val b = CapacityBucket8418("t", rpm = 6, maxInFlight = 4)
        assertTrue(b.tryAcquire(0L))
        b.release(1L, Outcome8418.OK, 100L)
        assertFalse("burst of one at 6 rpm", b.tryAcquire(2L))
        assertTrue("one request every 10 s", b.tryAcquire(10_002L))
        assertEquals(2, b.usedLastMinute(10_002L))
        assertEquals(4, b.availableThisMinute(10_002L))
        assertEquals(0, b.usedLastMinute(80_000L))
    }

    @Test fun `the in-flight limit refuses without waiting`() {
        val b = CapacityBucket8418("t", rpm = 600, maxInFlight = 1)
        assertTrue(b.tryAcquire(0L))
        assertFalse(b.tryAcquire(1L))
        b.release(2L, Outcome8418.EMPTY, 5L)
        assertTrue(b.tryAcquire(3L))
    }

    @Test fun `a 429 pauses only the bucket that earned it`() {
        val groq = LlmCapacityPool8418.bucketOf8418("groq")
        val cerebras = LlmCapacityPool8418.bucketOf8418("cerebras")
        assertTrue(LlmCapacityPool8418.tryAcquire8418("groq", 0L))
        LlmCapacityPool8418.release8418("groq", 10L, Outcome8418.RATE, 50L, retryAfterMs = 30_000L)
        assertFalse(groq.hasCapacity(1_000L))
        assertEquals(0, groq.availableThisMinute(1_000L))
        assertTrue("a sibling keeps its whole budget", cerebras.hasCapacity(1_000L))
        assertEquals(30, cerebras.availableThisMinute(1_000L))
        assertTrue("the pause ends at the provider's stated wait", groq.hasCapacity(30_100L))
    }

    @Test fun `our own refusal refunds the slot and is not a health sample`() {
        val b = CapacityBucket8418("t", rpm = 6, maxInFlight = 2)
        assertTrue(b.tryAcquire(0L))
        b.release(1L, Outcome8418.OWN_REFUSAL, 0L)
        assertEquals(0, b.usedLastMinute(2L))
        assertEquals(0L, b.samples)
        assertTrue(b.tryAcquire(2L))
    }

    // ── the pool ──────────────────────────────────────────────────────────

    @Test fun `pool capacity is the sum of its providers and the report shows each`() {
        for (m in listOf("groq", "cerebras", "llm7", "pollinations", "pollinations_get", "ovh_keyless_0", "ovh_keyless_1")) {
            LlmCapacityPool8418.bucketOf8418(m)
        }
        assertTrue(LlmCapacityPool8418.tryAcquire8418("groq", 0L))
        val (rpm, avail, used) = LlmCapacityPool8418.totals8418(1L)
        assertEquals("groq 30 + cerebras 30 + llm7 30 + pollinations 2 (shared) + ovh 6 (shared)", 98, rpm)
        assertEquals(1, used)
        assertEquals(97, avail)
        val line = LlmCapacityPool8418.capacityLine8418(1L)
        assertTrue(line, line.startsWith("llmPool8418: total avail=97/98 rpm used=1"))
        assertTrue(line, line.contains("groq 29/30 used=1 inflight=1"))
        assertTrue(line, line.contains("llm7 30/30 used=0"))
        assertTrue(line, line.contains("pollinations 2/2"))
    }

    @Test fun `routing prefers the healthy fast provider and reaches distinct providers first`() {
        val now = 0L
        repeat(2) { LlmCapacityPool8418.tryAcquire8418("x_bad", now); LlmCapacityPool8418.release8418("x_bad", now, Outcome8418.ERROR, 9_000L) }
        repeat(2) { LlmCapacityPool8418.tryAcquire8418("x_good", now); LlmCapacityPool8418.release8418("x_good", now, Outcome8418.OK, 300L) }
        val order = LlmCapacityPool8418.order8418(listOf("x_bad", "ovh_keyless_0", "ovh_keyless_1", "x_good"), { it }, now + 1)
        assertTrue("measured healthy beats measured failing", order.indexOf("x_good") < order.indexOf("x_bad"))
        assertTrue(order.indexOf("x_bad") > order.indexOf("ovh_keyless_0"))
        assertEquals("the second OVH slot waits behind every distinct provider", "ovh_keyless_1", order.last())
    }

    @Test fun `copilot providers draw on the same bucket as their council member`() {
        assertEquals("gemini", LlmCapacityPool8418.copilotBucket8418("gemini_direct"))
        assertEquals("groq", LlmCapacityPool8418.copilotBucket8418("groq"))
        assertNull(LlmCapacityPool8418.copilotBucket8418("keyless_fallback"))
        assertTrue(LlmCapacityPool8418.tryAcquire8418("groq", System.currentTimeMillis()))
        val out = LlmCapacityPool8418.pooled8418("groq") { "hi" }
        assertEquals("hi", out)
        val g = LlmCapacityPool8418.bucketOf8418("groq")
        assertEquals(0, g.inFlightNow())
        assertEquals(1L, g.samples)
        assertEquals(Outcome8418.OK, g.lastOutcome)
    }

    // ── the chain ─────────────────────────────────────────────────────────

    @Test fun `a failure launches the next member down the chain`() {
        val calls = AtomicInteger(0)
        val members = listOf(
            LlmDaisyChain8418.Member8418("x_a") { calls.incrementAndGet(); throw java.io.IOException("boom") },
            LlmDaisyChain8418.Member8418("x_b") { calls.incrementAndGet(); null },
            LlmDaisyChain8418.Member8418("x_c") { calls.incrementAndGet(); "answer" },
        )
        val r = LlmDaisyChain8418.run8418(members, 1, 1, 5_000L, noRefusal, notBenched, { _, _, _, _ -> })
        assertEquals(listOf("x_c" to "answer"), r.answers)
        assertEquals(3, r.asked)
        assertEquals(1, r.errored)
        assertEquals(1, r.empty)
        assertEquals(3, calls.get())
    }

    @Test fun `a rate-limited provider is skipped, not asked, and does not stall the turn`() {
        LlmCapacityPool8418.tryAcquire8418("groq", System.currentTimeMillis())
        LlmCapacityPool8418.release8418("groq", System.currentTimeMillis(), Outcome8418.RATE, 10L, 60_000L)
        val groqAsked = AtomicBoolean(false)
        val members = listOf(
            LlmDaisyChain8418.Member8418("groq") { groqAsked.set(true); "never" },
            LlmDaisyChain8418.Member8418("cerebras") { "from cerebras" },
        )
        val t0 = System.currentTimeMillis()
        val r = LlmDaisyChain8418.run8418(members, 1, 2, 5_000L, noRefusal, notBenched, { _, _, _, _ -> })
        assertEquals("from cerebras", r.answers.single().second)
        assertFalse(groqAsked.get())
        assertEquals(1, r.noCapacity)
        assertTrue(System.currentTimeMillis() - t0 < 2_000L)
    }

    @Test fun `members still running after the first answer finish into the cache instead of being cancelled`() {
        val lateSeen = CountDownLatch(1)
        val lateFlag = AtomicBoolean(false)
        val lateOutcome = arrayOfNulls<Outcome8418>(1)
        val members = listOf(
            LlmDaisyChain8418.Member8418("x_fast") { "fast" },
            LlmDaisyChain8418.Member8418("x_slow") { Thread.sleep(300L); "slow" },
        )
        val r = LlmDaisyChain8418.run8418(members, 1, 2, 5_000L, noRefusal, notBenched, { name, out, outcome, late ->
            if (name == "x_slow") {
                lateFlag.set(late)
                lateOutcome[0] = outcome
                if (out is String) LlmResponseCache8418.put8418("k", out, name, System.currentTimeMillis(), late)
                lateSeen.countDown()
            }
        })
        assertEquals("fast", r.answers.single().second)
        assertEquals(1, r.stillRunning)
        assertTrue("the slow member was not cancelled", lateSeen.await(3, TimeUnit.SECONDS))
        assertTrue(lateFlag.get())
        assertEquals(Outcome8418.OK, lateOutcome[0])
        assertEquals("slow", LlmResponseCache8418.get8418("k", System.currentTimeMillis()))
        assertTrue(LlmResponseCache8418.statusLine8418().contains("lateFills=1"))
        assertEquals("its bucket was released with its real outcome", 1L, LlmCapacityPool8418.bucketOf8418("x_slow").samples)
    }

    @Test fun `own-backoff refusals are not counted as asked and pull in the next member`() {
        val refusal = Any()
        val members = listOf(
            LlmDaisyChain8418.Member8418("x_r") { refusal },
            LlmDaisyChain8418.Member8418("x_ok") { "ok" },
        )
        val r = LlmDaisyChain8418.run8418(members, 1, 1, 5_000L, { it === refusal }, notBenched, { _, _, _, _ -> })
        assertEquals("ok", r.answers.single().second)
        assertEquals(1, r.asked)
        assertEquals(1, r.ownBackoff)
        assertEquals(0L, LlmCapacityPool8418.bucketOf8418("x_r").samples)
    }

    @Test fun `classification reads a bench as a rate limit and a socket timeout as a timeout`() {
        assertEquals(Outcome8418.OK, LlmDaisyChain8418.classify8418("text", benched = true))
        assertEquals(Outcome8418.RATE, LlmDaisyChain8418.classify8418(null, benched = true))
        assertEquals(Outcome8418.TIMEOUT, LlmDaisyChain8418.classify8418(java.net.SocketTimeoutException("t"), false))
        assertEquals(Outcome8418.ERROR, LlmDaisyChain8418.classify8418(IllegalStateException("x"), false))
        assertEquals(Outcome8418.EMPTY, LlmDaisyChain8418.classify8418("  ", false))
    }

    // ── the cache ─────────────────────────────────────────────────────────

    @Test fun `the cache is keyed by the exact prompt and expires`() {
        val k = LlmResponseCache8418.key8418("sys", "user", 64)
        assertEquals(k, LlmResponseCache8418.key8418("sys", "user", 64))
        assertNotEquals(k, LlmResponseCache8418.key8418("sys", "user", 65))
        assertNotEquals(k, LlmResponseCache8418.key8418("sys", "user2", 64))
        assertEquals(64, k.length)
        assertNull(LlmResponseCache8418.get8418(k, 0L))
        LlmResponseCache8418.put8418(k, "first", "a", 1_000L)
        LlmResponseCache8418.put8418(k, "second", "b", 2_000L)
        assertEquals("a live answer is not overwritten", "first", LlmResponseCache8418.get8418(k, 3_000L))
        assertNull(LlmResponseCache8418.get8418(k, 1_000L + LlmResponseCache8418.TTL_MS_8418))
    }

    // ── presets ───────────────────────────────────────────────────────────

    @Test fun `a known free provider needs only its name and the operator's key`() {
        val s = LlmProviderPresets8418.parse8418("sambanova|abc")
        assertNotNull(s)
        assertEquals("https://api.sambanova.ai/v1", s!!.baseUrl)
        assertEquals("abc", s.key)
        val l = LlmProviderPresets8418.parse8418("llm7||tok")
        assertEquals(LlmProviderPresets8418.LLM7_BASE_8418, l!!.baseUrl)
        assertEquals("tok", l.key)
        val custom = LlmProviderPresets8418.parse8418("My Gate|https://gw.example/v1|k")
        assertEquals("my_gate", custom!!.name)
        assertEquals("https://gw.example/v1", custom.baseUrl)
        assertNull("unknown name without a URL", LlmProviderPresets8418.parse8418("nobody|key"))
        assertNull("plain http is refused", LlmProviderPresets8418.parse8418("x|http://insecure/v1|k"))
        assertEquals(20, LlmCapacityPool8418.budgetFor8418("x_sambanova").rpm)
        assertEquals(30, LlmCapacityPool8418.budgetFor8418("llm7").rpm)
    }

    // ── keyless news pool ─────────────────────────────────────────────────

    private fun rss(vararg titles: String): String =
        "<rss><channel><title>Feed name</title>" +
            titles.joinToString("") { "<item><title><![CDATA[$it]]></title><link>x</link></item>" } +
            "</channel></rss>"

    @Test fun `rss titles are read without the channel title`() {
        val t = NewsHeadlinePool8418.parseTitles8418(rss("Bitcoin &amp; Solana rally", "Exchange hacked"))
        assertEquals(listOf("Bitcoin & Solana rally", "Exchange hacked"), t)
    }

    @Test fun `too few scored headlines is UNKNOWN and words match whole`() {
        val few = NewsHeadlinePool8418.score8418(listOf("Bitcoin rallies", "Ether gains"), listOf("a"), 1L)
        assertEquals(NewsHeadlinePool8418.Mood8418.UNKNOWN, few.mood)
        val banks = NewsHeadlinePool8418.score8418(
            listOf("Banks eye stablecoins", "Drug cartel story", "Urban crypto meetups", "Bankless podcast"), listOf("a"), 1L,
        )
        assertEquals(0, banks.bearish)
        val bear = NewsHeadlinePool8418.score8418(
            listOf("Protocol hacked for 40m", "SEC sues exchange", "Solana outage halts blocks", "Token delisted", "BTC gains"),
            listOf("a"), 1L,
        )
        assertEquals(NewsHeadlinePool8418.Mood8418.BEARISH, bear.mood)
        assertEquals(4, bear.bearish)
        assertEquals(1, bear.solanaHeadlines)
    }

    @Test fun `news feeds pool their budgets and one dead feed does not stop the others`() {
        val titles = arrayOf("Solana ETF approval nears", "Bitcoin rallies", "Ether gains", "Inflows jump", "Fund adopts SOL")
        val r = NewsHeadlinePool8418.refresh8418(1_000L) { f ->
            when (f.name) {
                "coindesk" -> throw java.io.IOException("down")
                "cointelegraph" -> rss(*titles)
                else -> rss(titles[0], "Market wrap")
            }
        }
        assertEquals(listOf("cointelegraph", "decrypt").sorted(), r.sources.sorted())
        assertEquals("merged and de-duplicated", 6, r.headlines)
        assertEquals(NewsHeadlinePool8418.Mood8418.BULLISH, r.mood)
        val again = NewsHeadlinePool8418.refresh8418(1_500L) { rss("x") }
        assertEquals("every feed spent its own budget: nothing asked", 0, again.headlines)
        assertEquals("the last real reading is kept", NewsHeadlinePool8418.Mood8418.BULLISH, NewsHeadlinePool8418.latest8418(2_000L).mood)
        assertEquals(NewsHeadlinePool8418.Mood8418.UNKNOWN, NewsHeadlinePool8418.latest8418(1_000L + NewsHeadlinePool8418.STALE_MS_8418 + 1L).mood)
    }
}
