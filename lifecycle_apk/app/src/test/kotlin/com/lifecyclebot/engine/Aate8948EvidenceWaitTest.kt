package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EvidenceWait8948
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8948 — a LIVE coin carrying market-cell evidence that defers on data has its proofs requested directly (its own
 * pipeline pass now, an uncached safety report, price, authority) and keeps a cycle slot until the admission decides it.
 */
class Aate8948EvidenceWaitTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_800_000_000_000L
    private val direct = mutableListOf<Pair<String, Long>>()

    @Before fun setUp() { EvidenceWait8948.resetForTest8948(); direct.clear(); EvidenceWait8948.direct8948 = { m, slot -> direct += m to slot } }
    @After fun tearDown() { EvidenceWait8948.resetForTest8948(); EvidenceWait8948.direct8948 = null }

    @Test fun aDataWaitAsksDirectlyAndRidesUntilDecided() {
        val ts = TokenState(mint = "test-mint", symbol = "TEST")
        EvidenceWait8948.note8948(ts, "LANE_POCKET", "DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", listOf("PRICE_STALE"), now)
        assertEquals("the coin's own pass is requested at once", listOf("test-mint" to now / EvidenceWait8948.DIRECT_GAP_MS_8948), direct)
        assertEquals(listOf("test-mint" to now + EvidenceWait8948.WINDOW_MS_8948), EvidenceWait8948.targets8948(now))
        EvidenceWait8948.note8948(ts, "LANE_POCKET", "DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", listOf("PRICE_STALE"), now + 5_000L)
        assertEquals("at most one direct request per 20 s", 1, direct.size)
        EvidenceWait8948.note8948(ts, "LANE_POCKET", "DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", listOf("BUY_DOOR_PROOF_X"), now + 21_000L)
        assertEquals(2, direct.size)
        EvidenceWait8948.note8948(ts, "LANE_POCKET", "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", emptyList(), now + 30_000L)
        assertTrue("decided: no longer rides", EvidenceWait8948.targets8948(now + 30_000L).isEmpty())
        val line = EvidenceWait8948.statusLine8948(now + 30_000L)
        assertTrue(line, line.contains("admitted=1 meanWaitS=30") && line.contains("direct=2"))
    }

    @Test fun aWaitThatOutlivesItsWindowIsFiledByWhatItStillMissed() {
        val ts = TokenState(mint = "test-mint-2", symbol = "TEST")
        EvidenceWait8948.note8948(ts, "BASE_POCKET", "DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", listOf("PRICE_STALE", "BUY_DOOR_PROOF_Y"), now)
        assertTrue(EvidenceWait8948.riding8948(now, now + EvidenceWait8948.WINDOW_MS_8948))
        assertFalse(EvidenceWait8948.riding8948(now, now + EvidenceWait8948.WINDOW_MS_8948 + 1L))
        assertTrue(EvidenceWait8948.targets8948(now + EvidenceWait8948.WINDOW_MS_8948 + 1L).isEmpty())
        assertTrue(EvidenceWait8948.statusLine8948(now + EvidenceWait8948.WINDOW_MS_8948 + 1L).contains("expired{PRICE_STALE+BUY_DOOR_PROOF_Y=1}"))
        EvidenceWait8948.note8948(ts, "BASE_POCKET", "REFUSE", "SAFETY_REFUSED_8136", emptyList(), now + 1L)
        assertTrue("a refusal with no open wait files nothing", EvidenceWait8948.statusLine8948(now + 1L).contains("refused{-}"))
        assertTrue(EvidenceWait8948.direct8948(0L, now))
        assertFalse(EvidenceWait8948.direct8948(now, now + 19_999L))
    }

    @Test fun theWaitIsWiredWithoutAnyGateChanging() {
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains(
            "if (mode == \"LIVE\") candidate.marketCell8723?.kind?.let { k -> try { EvidenceWait8948.note8948(ts, k, it.terminal.name, it.reason, deferCauses8700(it.trace), nowMs) } catch (_: Throwable) {} }"))
        assertTrue(src("engine/HandoffThroughput8190.kt").contains("return tasks + yields + tickets8297 + proof8310 + hydrated8343 + evidence8948"))
        assertTrue(src("engine/BotService.kt").contains("fastLaneEvaluate7277(mint, ConfigStore.load(applicationContext), \"EVIDENCE_WAIT_8948\", onceKey = \"\$mint|8948|\$slot\")"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 136. A proven coin's data wait is answered directly (8948)"))
    }
}
