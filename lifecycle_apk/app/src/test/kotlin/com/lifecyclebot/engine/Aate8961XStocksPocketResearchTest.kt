package com.lifecyclebot.engine

import com.lifecyclebot.hyperliquid.HlBrain
import com.lifecyclebot.xstocks.XsPocketResearch8961
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class Aate8961XStocksPocketResearchTest {
    private val days = 420
    private val now = 2_000_000L
    private val trend20 = HlBrain.Strategy(HlBrain.Family.TREND, 20, false)
    private val trend40 = HlBrain.Strategy(HlBrain.Family.TREND, 40, false)

    private fun panel(drifts: List<Double>, cost: Double = 0.005): HlBrain.Panel {
        val close = Array(drifts.size) { c -> DoubleArray(days).also { a ->
            a[0] = 100.0
            for (d in 1 until days) a[d] = a[d - 1] * (1.0 + drifts[c] + sin(d * 0.47) * 0.002)
        } }
        fun unknown() = Array(drifts.size) { DoubleArray(days) { Double.NaN } }
        return HlBrain.Panel(drifts.indices.map { "xs:S$it" }, 20_000L, close,
            Array(drifts.size) { DoubleArray(days) { 1e6 } }, unknown(), unknown(), unknown(),
            DoubleArray(drifts.size) { cost })
    }

    private fun paths(coins: Int) = linkedMapOf(
        trend20 to Array(days) { DoubleArray(coins) { 1.0 / coins } },
        trend40 to Array(days) { d -> DoubleArray(coins) { if (d % 6 < 3) 1.0 / coins else 0.0 } },
    )

    private fun clocks(p: HlBrain.Panel) = p.coins.associate { it.removePrefix("xs:") to now - 1000L }

    @Test fun negativeAggregateDoesNotHideAnIndividuallyPositiveInstrument() {
        val p = panel(listOf(0.004, -0.009))
        val rules = paths(2)
        val aggregate = HlBrain.grade("aggregate", HlBrain.returns(p, rules.getValue(trend20), days / 2, days).first)!!
        assertTrue(aggregate.meanPct < 0.0)
        val report = XsPocketResearch8961.evaluate(p, rules, clocks(p), 2, now)
        assertTrue(report.rows.single { it.symbol == "S0" }.supported)
        assertFalse(report.rows.single { it.symbol == "S1" }.supported)
        assertEquals("S0", report.rows.first().symbol)
        assertTrue(report.summary().contains("researchOnly=true"))
        assertTrue(report.summary().contains("noLiveAuthority"))
    }

    @Test fun changingOnlyFutureReturnsCannotChangeTheChosenTrainingVariant() {
        val p = panel(listOf(0.004))
        val before = XsPocketResearch8961.evaluate(p, paths(1), clocks(p), 1, now).rows.single()
        for (d in days / 2 until days) p.close[0][d] = p.close[0][d - 1] * 0.98
        val after = XsPocketResearch8961.evaluate(p, paths(1), clocks(p), 1, now).rows.single()
        assertEquals(before.strategy, after.strategy)
        assertEquals(before.train.record, after.train.record)
        assertTrue(before.test.record!!.meanPct > after.test.record!!.meanPct)
        assertFalse(after.supported)
    }

    @Test fun learnedModelsCannotFitTheBoundaryOrAnyHoldoutOutcome() {
        val p = panel(listOf(0.004, -0.006, 0.001))
        val original = HlBrain.design(p)
        val frozen = XsPocketResearch8961.trainingOnlyDesign(original, days / 2)
        for (d in 0 until days / 2 - 1) assertArrayEquals(original.y[d], frozen.y[d], 0.0)
        for (d in days / 2 - 1 until days) assertTrue(frozen.y[d].all { it.isNaN() })
        val learned = HlBrain.GRID.getValue(HlBrain.Family.LEARNED).first()
        val before = HlBrain.path(p, learned, frozen)
        for (d in days / 2 - 1 until days) original.y[d].fill(999.0)
        val after = HlBrain.path(p, learned, XsPocketResearch8961.trainingOnlyDesign(original, days / 2))
        for (d in before.indices) assertArrayEquals(before[d], after[d], 0.0)
    }

    @Test fun unknownStaleAndFutureCostClocksNeverBecomeSupportedPockets() {
        val p = panel(listOf(0.004))
        for (clock in listOf(0L, now + 1L, now - 25L * 3_600_000L)) {
            val row = XsPocketResearch8961.evaluate(p, paths(1), mapOf("S0" to clock), 1, now).rows.single()
            assertEquals("COST_UNOBSERVED", row.state)
            assertFalse(row.supported)
        }
    }

    @Test fun realCostsAndMissingHeldIntervalsWithdrawResearchSupport() {
        val cheap = panel(listOf(0.004))
        assertTrue(XsPocketResearch8961.evaluate(cheap, paths(1), clocks(cheap), 1, now).rows.single().supported)
        val costly = panel(listOf(0.004), cost = 0.5)
        assertFalse(XsPocketResearch8961.evaluate(costly, paths(1), clocks(costly), 1, now).rows.single().supported)
        cheap.close[0][300] = Double.NaN
        val gap = XsPocketResearch8961.evaluate(cheap, paths(1), clocks(cheap), 1, now).rows.single()
        assertEquals("HISTORY_GAPS", gap.state)
        assertEquals(2, gap.test.missingHeldDays)
        assertFalse(gap.supported)
    }

    @Test fun aLargerSearchedUniverseIncreasesThePenaltyWithoutChoosingOnHoldout() {
        val p = panel(listOf(0.004))
        val small = XsPocketResearch8961.evaluate(p, paths(1), clocks(p), 1, now)
        val full = XsPocketResearch8961.evaluate(p, paths(1), clocks(p), 35, now)
        assertTrue(full.z > small.z)
        assertEquals(70, full.testedVariants)
        assertEquals(small.rows.single().strategy, full.rows.single().strategy)
        assertTrue(full.rows.single().lowerPct < small.rows.single().lowerPct)
    }

    @Test fun zeroExposureAndThinHistoryCannotBeCalledSupported() {
        val p = panel(listOf(0.004))
        val emptyPath = mapOf(trend20 to Array(days) { DoubleArray(1) })
        val flat = XsPocketResearch8961.evaluate(p, emptyPath, clocks(p), 1, now).rows.single()
        assertEquals("THIN", flat.state)
        assertEquals(0, flat.test.activeDays)
        for (d in 0 until 180) p.close[0][d] = Double.NaN
        val short = XsPocketResearch8961.evaluate(p, paths(1), clocks(p), 1, now).rows.single()
        assertFalse(short.supported)
        assertTrue((short.train.record?.days ?: 0) < HlBrain.MIN_TEST_DAYS)
    }

    @Test fun theActualFamilyGridIsExaminedAndFullResearchIsSerializable() {
        val p = panel(listOf(0.004, -0.006, 0.001))
        var chunks = 0
        val report = XsPocketResearch8961.analyze(p, clocks(p), 35, now) { chunks++ }
        assertEquals(p.coins.size * HlBrain.Family.values().size, report.rows.size)
        assertEquals(35 * HlBrain.GRID.values.sumOf { it.size }, report.testedVariants)
        assertEquals(HlBrain.GRID.values.sumOf { it.size } + p.coins.size, chunks)
        val saved = JSONObject(report.json().toString())
        assertTrue(saved.getBoolean("researchOnly"))
        assertEquals(report.rows.size, saved.getJSONArray("rows").length())
        assertEquals(20_210L, saved.getLong("splitDay"))
        assertTrue(saved.getString("costBasis").contains("not historical quote reconstruction"))
    }
}
