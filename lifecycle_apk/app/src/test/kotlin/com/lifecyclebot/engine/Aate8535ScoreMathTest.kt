package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.MetaCognitionAI as Meta
import com.lifecyclebot.v3.scoring.ScoreComponent
import com.lifecyclebot.v3.scoring.SignedCap8535
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8535 — audit C01 (soft caps erased negative votes through per-vote truncation) and C02 (MetaCognition clamped the
 * whole 44-layer aggregate to ±100 and booked the difference as a vote no layer cast).
 */
class Aate8535ScoreMathTest {
    private var oldMood = "NEUTRAL"
    @Before fun setup() { Meta.reset(); oldMood = SymbolicContext.emotionalState; SymbolicContext.emotionalState = "NEUTRAL" }
    @After fun cleanup() { Meta.reset(); SymbolicContext.emotionalState = oldMood }

    private fun c(name: String, v: Int) = ScoreComponent(name, v, "fixture")

    // ── C01 ──

    @Test fun fourteen_outer_votes_of_minus_three_cap_to_exactly_minus_ten() {
        val votes = List(14) { c("outer$it", -3) }
        val capped = SignedCap8535.capNegatives(votes, -10, "OUTERCAP") { true }
        assertEquals(-10, capped.sumOf { it.value })
        assertTrue("no vote flips sign or passes zero", capped.all { it.value <= 0 && it.value >= -3 })
        assertEquals("ten votes carry -1, four carry 0", 10, capped.count { it.value == -1 })
        // superseded behaviour: (-3 * 10 / 42).toInt() == 0 for every vote -> aggregate 0
        assertEquals(0, votes.sumOf { (it.value * (10.0 / 42.0)).toInt() })
    }

    @Test fun mixed_magnitudes_cap_to_the_exact_budget_in_any_order() {
        val base = listOf(-12, -6, -3, -3)
        for (perm in listOf(base, base.reversed(), listOf(-3, -12, -3, -6))) {
            val out = SignedCap8535.apportion(perm, -10)
            assertEquals(perm.toString(), -10, out.sum())
            assertTrue(out.all { it <= 0 })
            out.indices.forEach { assertTrue("never past its own value", kotlin.math.abs(out[it]) <= kotlin.math.abs(perm[it])) }
        }
        // the largest vote carries the rounding: -12/-6/-3/-3 at 10/24 -> -5, -3, -1, -1
        assertEquals(listOf(-5, -3, -1, -1), SignedCap8535.apportion(base, -10))
    }

    @Test fun positives_zeros_and_an_under_cap_group_are_untouched() {
        val votes = listOf(c("holders", -2), c("social", -2), c("entry", 8), c("narrative", 0))
        assertEquals(votes, SignedCap8535.capNegatives(votes, -5, null) { it.name in setOf("holders", "social", "narrative") })
        val over = listOf(c("holders", -4), c("social", -4), c("entry", 8))
        val capped = SignedCap8535.capNegatives(over, -5, null) { it.name != "entry" }
        assertEquals(8, capped.first { it.name == "entry" }.value)
        assertEquals(-5, capped.filter { it.name != "entry" }.sumOf { it.value })
        assertEquals(votes, SignedCap8535.capNegatives(votes, 5, null) { true })   // a non-negative cap is no cap
    }

    @Test fun a_group_scale_keeps_the_aggregate_to_the_unit() {
        // bootstrap ramp 0.15 on six -3 votes: aggregate -18 * 0.15 = -2.7 -> -3 (was 0: each (-3*0.15).toInt() == 0)
        val votes = List(6) { c("outer$it", -3) } + listOf(c("entry", 10))
        val scaled = SignedCap8535.scaleGroup(votes, 0.15, "FLUID") { it.name.startsWith("outer") }
        assertEquals(-3, scaled.filter { it.name.startsWith("outer") }.sumOf { it.value })
        assertEquals(10, scaled.first { it.name == "entry" }.value)
        assertEquals(votes, SignedCap8535.scaleGroup(votes, 1.0, "FLUID") { true })
        // positives and negatives are scaled as separate groups
        val mixed = listOf(c("a", 7), c("b", -7), c("c", 3))
        val half = SignedCap8535.scaleGroup(mixed, 0.5, null) { true }
        assertEquals(5, half.filter { it.value > 0 }.sumOf { it.value })
        assertEquals(-4, half.first { it.name == "b" }.value)   // -3.5 rounds to -4, not truncated to -3
    }

    // ── C02 ──

    private fun votes(score: Int) = Meta.predictionsFromComponents8527(listOf(ScoreComponent("momentum", score, "observed")))

    @Test fun the_metacognition_correction_is_a_bounded_delta_and_never_a_clamp_of_the_aggregate() {
        Meta.loadFromJson(Meta.saveToJson().put("totalTradesAnalyzed", 30))
        val neutral = votes(0)
        for (base in listOf(80, 120, 180, 250)) assertEquals("neutral forecast leaves $base alone", base, Meta.adjustScore(base, neutral))
        val bull = votes(25)
        val adjusted = listOf(80, 120, 180, 250).map { Meta.adjustScore(it, bull) }
        assertEquals("monotonic", adjusted, adjusted.sorted())
        assertTrue("distinct aggregates stay distinct", adjusted.toSet().size == 4)
        adjusted.zip(listOf(80, 120, 180, 250)).forEach { (a, b) -> assertTrue(kotlin.math.abs(a - b) <= Meta.META_DELTA_CAP_8535) }
    }

    @Test fun the_delta_bound_is_symmetric_and_rounds() {
        assertEquals(40, Meta.deltaOf8535(100, 190.0))
        assertEquals(-40, Meta.deltaOf8535(100, 10.0))
        assertEquals(12, Meta.deltaOf8535(50, 62.4))
        assertEquals(0, Meta.deltaOf8535(50, Double.NaN))
        assertEquals(0, Meta.metaDelta8535(180, emptyList()))
    }
}
