package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateMarkFresh8094
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8459 — owner: "there isn't meant to be ... a stale price, a stale mark, nothing". 8457: identity
 * unitBreak{OTHER@JUPITER_CANDIDATE_REFRESH_8094=155} — DexScreener pair-lane prices were written under the Jupiter name,
 * and either aggregator replaced a candidate's own curve / pool print whenever that print was over 1 s old.
 */
class Aate8459MarkProvenanceTest {
    private val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateMarkFresh8094.kt").readText()

    @Test fun thePairLaneAnswerIsFiledAsDexScreener() {
        assertEquals("DEXSCREENER_LANE_8287", CandidateMarkFresh8094.DEX_LANE_SOURCE_8459)
        val lane = src.substringAfter("fun onLaneAnswer8287(").substringBefore("val unpriced = ")
        assertTrue(lane.contains("apply8094(priced, DEX_LANE_SOURCE_8459)"))
        assertFalse(lane.contains("apply8094(priced)"))
    }

    @Test fun aRecentChainPrintIsNotReplacedByAnAggregator() {
        val now = 50_000_000L
        assertTrue(CandidateMarkFresh8094.chainHeld8459("PUMP_PORTAL_TRADE_WS_7278", now - 5_000L, now))
        assertTrue(CandidateMarkFresh8094.chainHeld8459("POOL_VAULT_WS_8418_PUMPSWAP", now - 29_000L, now))
        assertFalse("a chain print 30 s old may be refreshed", CandidateMarkFresh8094.chainHeld8459("PUMP_CURVE_RPC", now - 30_000L, now))
        assertFalse("an aggregator price is not held", CandidateMarkFresh8094.chainHeld8459("DEXSCREENER_LANE_8287", now - 2_000L, now))
        assertFalse(CandidateMarkFresh8094.chainHeld8459("PUMP_CURVE_RPC", 0L, now))
        val apply = src.substringAfter("private fun apply8094(").substringBefore("refreshed.incrementAndGet()")
        assertTrue("a chain-read batch (applyMarks8359) may still replace an older chain print",
            apply.contains("!com.lifecyclebot.engine.StopFeed8327.chainMark8327(source8359) &&"))
        assertTrue(apply.contains("if (ts.position.isOpen || fresher || suspect || chainHeld) false else {"))
    }
}
