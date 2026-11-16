package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BookPrice8821
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.8821 — a paper-book coin no other source observes is priced by Jupiter instead of closing LOST_MARK. */
class Aate8821BookPriceTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val asked = ArrayList<List<String>>()

    @Before fun setUp() {
        BookPrice8821.resetForTest8821()
        BookPrice8821.fetch8821 = { mints -> asked += mints; mapOf("A" to 0.0123, "B" to Double.NaN) }
    }
    @After fun tearDown() { BookPrice8821.resetForTest8821(); BookPrice8821.fetch8821 = { emptyMap() } }

    @Test fun onlyUnobservedCoinsAreAskedAndAnAnswerIsAnObservation() {
        val t0 = 1_000_000L
        for (m in listOf("A", "B", "C")) BookPrice8821.want8821(m, t0 + 600_000L)
        assertEquals(2, BookPrice8821.pass8821(t0) { it == "C" })          // C has a fresh tick / tape / curve mark
        assertEquals(setOf("A", "B"), asked.single().toSet())
        val obs = BookPrice8821.observation8821("A", t0 + 10_000L, 60_000L)!!
        assertEquals(0.0123, obs.priceUsd, 1e-12); assertEquals(t0, obs.observedAtMs); assertEquals(BookPrice8821.SRC_JUP_8821, obs.source)
        assertNull("aged like every observation", BookPrice8821.observation8821("A", t0 + 61_000L, 60_000L))
        assertNull("an invalid price is no mark", BookPrice8821.observation8821("B", t0, 60_000L))
        // A's mark is under the stale bar: only B is asked again
        assertEquals(1, BookPrice8821.pass8821(t0 + 10_000L) { it == "C" })
        assertEquals(listOf("B"), asked.last())
        // wants expire with the book's lost-mark window
        assertEquals(0, BookPrice8821.pass8821(t0 + 700_000L) { false })
        assertTrue(BookPrice8821.statusLine8821().contains("wanted=0"))
    }

    @Test fun theBookAsksAndEveryMarkReadSeesIt() {
        assertTrue(src("engine/truth/MemePaperTrader8544.kt").contains("BookPrice8821.want8821(mint, until)"))
        val sm = src("engine/truth/ShadowMark8360.kt").substringAfter("fun observedTimed8372(")
        assertTrue(sm.indexOf("BookPrice8821.observation8821(mint, nowMs, MAX_AGE_MS_8360)") in 0 until sm.indexOf("val best = candidates.filter"))
        assertTrue(src("engine/DataPipeline8191.kt").contains("BookPrice8821.statusLine8821()"))
        val log = src("engine/ErrorLogger.kt")
        assertTrue(log.contains("if (pendingWrites.get() > HARD_MAX_PENDING_8821 && level != Level.CRASH) {"))
        assertTrue(log.contains("top8821="))
    }
}
