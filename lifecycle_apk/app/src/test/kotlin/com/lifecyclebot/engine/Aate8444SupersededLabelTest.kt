package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8444 — a coin re-decided inside its 5-minute read keeps the earlier decision's label: 8439 dropped 641 priced labels
 * as LABEL_EPISODE_MISMATCH because the newer capture replaced the older one and marked it lost on the spot.
 */
class Aate8444SupersededLabelTest {
    private val cx = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()

    @Test fun theEarlierDecisionWaitsForItsOwnLabel() {
        val capture = cx.substring(cx.indexOf("pending[captureKey8372]?.takeIf { it.atMs != nowMs }?.let { old ->"))
            .substringBefore("pending[captureKey8372] = captured8372")
        assertTrue(capture.contains("keepSuperseded8443(captureKey8372, old, nowMs)"))
        assertFalse("not marked lost at supersession", capture.contains("gradeBook8144.markLost(oldId)"))
        val label = cx.substring(cx.indexOf("fun onLabel(mint: String, labelLane: String, horizonMin: Int"))
            .substringBefore("val id8372 = \"\$key|\${p.atMs}\"")
        assertTrue(label.contains("?: superseded8443[\"\$key|\$decisionAtMs8371\"] ?: pending[key]"))
        assertTrue(label.contains("!superseded8443.remove(\"\$key|\${captured.atMs}\", captured)"))
        val lost = cx.substring(cx.indexOf("fun onLabelLost8187(")).substringBefore("inc(\"LABEL_LOST_\${lostKey8441(reason)}_8187\")")
        assertTrue(lost.contains("superseded8443[\"\$key|\$decisionAtMs8371\"]"))
        assertTrue(Cortex7885.supersededLine8443().startsWith("superseded8443[kept="))
    }

    @Test fun evictionTakesTheStaleThenTheOldest() {
        val now = 10_000_000L
        val ids = mapOf("a" to now - 3 * 3_600_000L, "b" to now - 10_000L, "c" to now - 20_000L, "d" to now - 5_000L)
        assertEquals(listOf("a"), Cortex7885.evictSuperseded8443(ids, now, 10, 2 * 3_600_000L))
        assertEquals(listOf("a", "c"), Cortex7885.evictSuperseded8443(ids, now, 2, 2 * 3_600_000L))
        assertTrue(Cortex7885.evictSuperseded8443(emptyMap(), now, 2, 1L).isEmpty())
    }
}
