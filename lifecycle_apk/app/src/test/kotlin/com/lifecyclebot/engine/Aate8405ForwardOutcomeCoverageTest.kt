package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.8405 §PRICE_THE_OUTCOME_YOU_PROMISED — 5.0.8404: CortexAuthority8366 INCOMPLETE_OUTCOMES on every lane (CORE matured=925
 * missing=823, coverageRest=0.11) because the off-watch mark fetch (50 mints / 20 s, Jupiter-led, 90 s re-ask) could not price
 * ~370 maturing observations a minute. The fetch is now DexScreener-led over the whole batch, 90 mints / 10 s, re-ask at 30 s.
 */
class Aate8405ForwardOutcomeCoverageTest {
    private val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()

    @Test fun capacity_covers_the_horizon_demand() {
        assertTrue(lab.contains("private const val OFFWATCH_FETCH_GAP_MS_7737 = 10_000L"))
        assertTrue(lab.contains("private const val OFFWATCH_BATCH_7737 = 90"))
        assertTrue(lab.contains("private const val OFFWATCH_DEX_CHUNK_8405 = 30"))
        // a missed mint is re-asked inside the 1-minute horizon's grace, not after it
        assertTrue(lab.contains("private const val OFFWATCH_RETRY_MS_7809 = 30_000L"))
        assertTrue(lab.contains("private const val H15_MS_7731 = 1L * 60_000L"))
    }

    @Test fun rungs_run_healthiest_first_and_jupiter_only_on_the_misses() {
        val body = lab.substringAfter("private fun fetchOffWatchMarks7737(").substringBefore("private const val TERMINAL_PREFIX_8395")
        // superseded by V5.0.8418 — on-curve pump mints are curve-read FIRST (chain-exact), then DexScreener for the rest and
        // the curve's misses, the curve again for off-curve pump misses, Jupiter last (was dex `batch.chunked` before any curve read).
        val curveFirst = body.indexOf("ParallelMarkFanout7088.curvePrices7392(onCurve8418)")
        val dex = body.indexOf("for (chunk in dexBatch8418.chunked(OFFWATCH_DEX_CHUNK_8405)) {")
        val curve = body.indexOf("ParallelMarkFanout7088.curvePrices7392(missed7753)")
        val jup = body.indexOf("PriceResolverFallback.jupiterBatchPrices7737(missedJup8405)")
        assertTrue(curveFirst in 0 until dex && dex < curve && curve < jup)
        assertFalse(body.contains("jupiterBatchPrices7737(batch)"))
        assertTrue(body.contains("val missedJup8405 = batch.filter { it !in got }.take(50)"))
        assertTrue(body.contains("FORWARD_LABEL_OFFWATCH_JUP_PRICED_8405"))
        // every rung stays on the background thread the fetch already owned
        assertTrue(body.indexOf("Thread({") in 0 until curveFirst)
    }

    @Test fun discovery_capture_is_reported_above_the_counter_cut() {
        val phc = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("Discovery capture (§8400): "))
        assertTrue(phc.contains("DiscoveryCapture8400.statusLine()"))
        val dc = File("src/main/kotlin/com/lifecyclebot/engine/truth/DiscoveryCapture8400.kt").readText()
        assertTrue(dc.contains("uncappedBySource{"))
        assertTrue(dc.contains("missingBySource{"))
    }
}
