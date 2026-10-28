package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/** V5.0.8410 — a revision owns only the weight its booked label still carries after the window has faded it. */
class Aate8410WeightedRevisionTest {
    @Test fun the_remaining_weight_is_the_decay_since_booking_and_counts_carry_their_fraction() {
        assertEquals(1.0, ForwardReturnLabeler7731.revisionWeight8410(0.0, 0.0), 1e-12)
        assertEquals(exp(-0.4), ForwardReturnLabeler7731.revisionWeight8410(-0.1, -0.5), 1e-12)   // faded since booking (scale fell 0.4)
        assertEquals(1.0, ForwardReturnLabeler7731.revisionWeight8410(-0.5, -0.1), 1e-12)        // never above one
        assertEquals(1.0, ForwardReturnLabeler7731.revisionWeight8410(Double.NaN, -0.3), 1e-12)
        assertEquals(0 to 0.7, ForwardReturnLabeler7731.carry8410(0.7).let { it.first to it.second })
        val c = ForwardReturnLabeler7731.carry8410(1.3); assertEquals(1, c.first); assertEquals(0.3, c.second, 1e-12)
        val d = ForwardReturnLabeler7731.carry8410(-1.3); assertEquals(-1, d.first); assertEquals(-0.3, d.second, 1e-12)
    }

    @Test fun allocator_book_decay_revise_lands_the_corrected_value_exactly_across_many_decay_cycles() {
        val s = CellAllocator7962.Stat7962()
        // fill to the cap, book the label of interest at +10, then push 400 more labels of 0 through the window
        repeat(400) { s.add(0.0, 1.0) }
        val scaleAtBook = s.logScale
        s.add(10.0, 1.0)
        repeat(400) { s.add(0.0, 1.0) }
        val weight = exp(s.logScale - scaleAtBook)
        assertTrue(weight < 0.9 && weight > 0.0)
        val before = s.sx
        // the label is revised to 0: the book must lose exactly what the label still weighs, not its full 10
        s.revise(10.0, 0.0, weight)
        assertEquals(before - 10.0 * weight, s.sx, 1e-9)
        assertEquals(0.0, s.sx, 1e-6)   // every outcome is now zero: zero weighted return remains
        // persisted and restored with its forgetting
        val f = s.encode().split(',')
        assertEquals(6, f.size); assertEquals(s.logScale, f[5].toDouble(), 1e-12)
    }

    @Test fun a_full_value_revision_would_have_over_corrected() {
        val s = CellAllocator7962.Stat7962()
        repeat(400) { s.add(0.0, 1.0) }
        s.add(10.0, 1.0); repeat(400) { s.add(0.0, 1.0) }
        val sx = s.sx
        s.revise(10.0, 0.0, 1.0)
        assertTrue(s.sx < -1.0)   // the legacy arithmetic drives a book of zeros negative
        assertTrue(sx > 0.0)
    }

    @Test fun the_tally_logs_its_decay_and_the_labeler_persists_the_booking_scales() {
        val t = ForwardReturnLabeler7731.Tally()
        repeat(ForwardReturnLabeler7731.CELL_WINDOW_8014 + 50) { t.n60 += 1; t.sum60 += 1.0; t.window8014(ForwardReturnLabeler7731.CELL_WINDOW_8014) }
        assertTrue(t.logScale60 < 0.0)
        val w = ForwardReturnLabeler7731.CELL_WINDOW_8014.toDouble()
        assertEquals((0 until 50).sumOf { ln(w / (w + 1)) }, t.logScale60, 1e-9)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(src.contains("if (horizon == 60) o.tallyScales8410 = scales8410"))
        assertTrue(src.contains("val wt = revisionWeight8410(o.tallyScales8410[i], t.logScale60)"))
        assertTrue(src.contains("o.allocScales8410 = com.lifecyclebot.engine.CellAllocator7962.onLabel7962("))
        assertTrue(src.contains("CellAllocator7962.reviseLabel7997(o.cell, o.lane, o.setup7955, oldNet, newNet, o.allocScales8410)"))
        assertTrue(src.contains("if (f.size !in 17..36) return@forEach"))
        assertTrue(src.contains("""PipelineHealthCollector.labelInc("REVISION_WEIGHT_UNKNOWN_8410_TALLY")"""))
        val miner = File("src/main/kotlin/com/lifecyclebot/engine/truth/SpecialistMiner7972.kt").readText()
        assertTrue(miner.contains("if (decayed8410 || n >= STAT_WINDOW_8014) { decayedRevisionSkipped8410.incrementAndGet(); return }"))
    }
}
