package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.network.HeliusCurveTape8354
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8829 — rules graded at the latency the bot has; the program tape in compact frames, each transport measured. */
class Aate8829TapeAndLatencyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val grid = setOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

    @Test fun aLatencyBeyondTheGridIsGradedRoundedUpToFiveSeconds() {
        assertEquals(40_000L, LaunchReplay8744.atLatency8829(grid, 38_000L))
        assertEquals(15_000L, LaunchReplay8744.atLatency8829(grid, 12_300L))
        assertNull("already in the grid", LaunchReplay8744.atLatency8829(grid, 30_000L))
        assertNull(LaunchReplay8744.atLatency8829(grid, 0L))
        val rules = LaunchReplay8744.rulesFor8829(38_000L)
        val extra = rules.drop(LaunchReplay8744.GRID.size)
        // V5.0.8854: the 8096 LIFECYCLE exit is read at the required latency too (still only DOCTRINE can be proven)
        assertTrue(extra.isNotEmpty() && extra.all { it.delayMs == 40_000L && (it.ladder == Ladder.DOCTRINE || it.ladder == Ladder.LIFECYCLE) })
        assertTrue(extra.any { it.ladder == Ladder.LIFECYCLE })
        assertEquals(extra.size, extra.toSet().size)
        assertEquals(LaunchReplay8744.GRID, LaunchReplay8744.rulesFor8829(10_000L))
    }

    @Test fun onlyARuleGradedAtTheLatencyCanBeProven() {
        fun res(d: Long) = LaunchReplay8744.Result(Rule(10_000.0, 3, 2.0, 35.0, Ladder.DOCTRINE, d), 150, 6.0, 1.0, 0.4, 0.1, 0.0, 0.0, 5.0, 1.0)
        assertNull("30 s cannot meet a 38 s latency", LaunchReplay8744.provenOf8748(listOf(res(30_000L)), 38_000L))
        assertNotNull(LaunchReplay8744.provenOf8748(listOf(res(40_000L)), 38_000L))
        val rp = src("engine/truth/LaunchReplay8744.kt")
        assertTrue(rp.contains("val res = evaluate8748(set, solUsd, cost, rulesFor8829(need))"))
        assertTrue(src("engine/truth/BaseFamily8754.kt").contains("LaunchReplay8744.atLatency8829(GRID.map { it.delayMs }.toSet(), needDelayMs)"))
    }

    @Test fun theProgramTapeAsksForCompactFramesAndMeasuresEachTransport() {
        assertEquals("enhanced=-", HeliusCurveTape8354.transportLine8829(0L, 0L, 0L, "enhanced"))
        assertEquals("logs=n100/3500B/failed67%", HeliusCurveTape8354.transportLine8829(100L, 350_000L, 67L, "logs"))
        val ws = src("network/HeliusEnhancedWS.kt")
        val curve = ws.substringAfter("private fun sendCurveSubscribe8354(").substringBefore("// OkHttp returns false")
        assertTrue(curve.contains("put(\"encoding\", \"base64\")") && curve.contains(".put(\"failed\", false)"))
        val tape = src("network/HeliusCurveTape8354.kt")
        assertTrue(tape.contains("level = if (try { HeliusEnhancedWS.enhancedOk8354() } catch (_: Throwable) { false }) LEVEL_ENHANCED_8354 else LEVEL_LOGS_8354"))
        assertTrue(tape.contains("noteBytes8354(text.length, viaLogs = true)"))
    }
}
