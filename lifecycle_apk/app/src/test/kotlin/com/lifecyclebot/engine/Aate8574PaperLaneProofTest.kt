package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.MemeStrategies8546
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8574 — a meme lane proven in the paper book (post-guard closes only) is a LIVE edge for that lane. 8562: paper
 * SHITCOIN closed n138 +44.7%±31.5 PF 4.14, but only strategy families could carry a paper proof to LIVE.
 */
class Aate8574PaperLaneProofTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    /** stats row: n, wins, sum, pnl, sumSq, winSum, lossSum (MemePaperTrader8544 STAT_FIELDS_8546). */
    private fun row(nets: List<Double>): DoubleArray {
        val v = DoubleArray(7)
        for (x in nets) { v[0] += 1.0; if (x > 0) v[1] += 1.0; v[2] += x; v[4] += x * x; if (x > 0) v[5] += x else v[6] += -x }
        return v
    }

    @Test fun the_owner_bar_decides_and_a_runner_tail_book_can_pass_it() {
        // 30 closes: 5 runners at +300 %, 25 losers at -8 % -> mean +43.3 %, se ~ 21, PF 7.5
        val tail = row(List(5) { 300.0 } + List(25) { -8.0 })
        val p = MemePaperTrader8544.proofOf8546(tail)
        assertEquals(30.0, p[0], 0.0)
        assertTrue(MemeStrategies8546.provenRecord8546(p[0].toInt(), p[1], p[2], p[3]))
        // too few closes, or a losing book, never pass
        val few = MemePaperTrader8544.proofOf8546(row(List(3) { 300.0 } + List(10) { -8.0 }))
        assertFalse(MemeStrategies8546.provenRecord8546(few[0].toInt(), few[1], few[2], few[3]))
        val losing = MemePaperTrader8544.proofOf8546(row(List(3) { 20.0 } + List(40) { -8.0 }))
        assertFalse(MemeStrategies8546.provenRecord8546(losing[0].toInt(), losing[1], losing[2], losing[3]))
    }

    @Test fun only_post_guard_closes_prove_a_lane_and_the_proof_is_a_live_edge() {
        val paper = src("engine/truth/MemePaperTrader8544.kt")
        val note = paper.substringAfter("private fun noteLane8544(").substringBefore("private fun addStat8572(")
        assertTrue(note.contains("addStat8572(c.lane, c)"))
        assertTrue(note.contains("if (!c.lane.startsWith(FAMILY_PREFIX_8546)) addStat8572(\"\$PROOF_PREFIX_8572\${c.lane}\", c)"))
        // V5.0.8623 — the lane is proven on its priced record (P8623|), never on lost-mark closes
        assertTrue(paper.contains("laneStats[\"\$PRICED_PREFIX_8623\${lane.trim().uppercase()}\"]"))
        assertTrue(note.contains("if (c.why == LOST_MARK_8623) { addStat8572(\"\$UNPRICED_PREFIX_8623\${c.lane}\", c); return }"))
        assertEquals("P8572|", MemePaperTrader8544.PROOF_PREFIX_8572)
        // V5.0.8687 — a paper lane record is no longer a LIVE edge
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        val w = gate.substringAfter("fun edge8114Live8538(").substringBefore("/** Pure (V5.0.8536)")
        assertFalse(w.contains("MemePaperTrader8544"))
        assertFalse(w.contains("MemeStrategies8546"))
    }
}
