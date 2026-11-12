package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MarketCohort8701
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8701 — every judged Solana coin is also graded by where it was minted and how deep its pool is; a cohort is a LIVE
 * edge only on its own proven forward record (8689 dataset: pump-minted -4 .. -20 % in every band, other coins with a
 * $5k-$20k pool +8.8 % / +6.1 % net).
 */
class Aate8701MarketCohortTest {
    @Test fun aCoinIsFiledByOriginAndPoolDepth() {
        assertEquals("PUMP", MarketCohort8701.origin8701("2uqXwpNMXpiDzrJMGu2NVyszzteAbUHVx1GtFpBupump"))
        assertEquals("OTHER", MarketCohort8701.origin8701("3nN4GVY7eS9JwWiiTFwzJBMTCv6rD8a2gofADeq6Qm63"))
        assertEquals("POOL_LT5K", MarketCohort8701.poolBand8701(4_999.0))
        assertEquals("POOL_5K_20K", MarketCohort8701.poolBand8701(5_000.0))
        assertEquals("POOL_20K_100K", MarketCohort8701.poolBand8701(20_000.0))
        assertEquals("POOL_GE100K", MarketCohort8701.poolBand8701(100_000.0))
        assertEquals("an unread pool is not a thin pool", "POOL_UNREAD", MarketCohort8701.poolBand8701(Double.NaN))
        assertEquals("POOL_UNREAD", MarketCohort8701.poolBand8701(0.0))
        assertEquals("ORIGIN8701_OTHER_POOL_5K_20K", MarketCohort8701.cohort8701("3nN4GVY7eS9JwWiiTFwzJBMTCv6rD8a2gofADeq6Qm63", 9_124.0))
    }

    @Test fun aCoinIsFiledOncePerFifteenMinutes() {
        assertTrue(MarketCohort8701.due8701(null, 1_000L))
        assertFalse(MarketCohort8701.due8701(1_000L, 1_000L + 14 * 60_000L))
        assertTrue(MarketCohort8701.due8701(1_000L, 1_000L + 15 * 60_000L))
        assertTrue("a clock that went backwards does not silence a coin", MarketCohort8701.due8701(9_000L, 1_000L))
    }

    @Test fun theCohortIsARecordOfItsOwnAndEarnsLiveOnlyWhenProven() {
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        // V5.0.8709 — one observation, graded into the lane's books and the cohort's; never a second observation per coin.
        assertTrue(labeler.contains("it.origin8701 = (try { MarketCohort8701.claim8701(ts, nowMs) } catch (_: Throwable) { null }).orEmpty()"))
        assertTrue(labeler.contains("laneKeys7928(o).let { if (o.origin8701.isEmpty()) it else it + laneKey(o.origin8701) }"))
        assertFalse(labeler.contains("MarketCohort8701.observe8701("))
        val cohort = src("engine/truth/MarketCohort8701.kt")
        assertTrue(cohort.contains("return if (LiveEdgeGate7877.cohortProvenPositive7948(stat)) cohort else null"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("MarketCohort8701.measuredProven8701(ts) } catch (_: Throwable) { null }) != null -> \"ORIGIN_MEASURED_8701\""))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
