package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchCohort8173
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8174 — a price-derived reserve value cannot authorize a live entry as if it were confirmed. */
class Aate8174LiquidityProofTest {
    @Test fun priceImpliedCurveLiquidityIsNeverAnExecutableReserveWitness() {
        val c = LaunchCohort8173
        val observed = c.CurveRead8173(3.0, c.CurveSource8173.RESERVES)
        val inferred = c.CurveRead8173(55.0, c.CurveSource8173.PRICE_IMPLIED)
        assertEquals(3.0, c.executableReserve8174(observed, 1_000L)!!, 1e-9)
        assertNull(c.executableReserve8174(inferred, 1_000L))
        assertNull(c.executableReserve8174(observed, 31_000L))
        assertFalse(c.reserveFresh8174(1_000L, 35_000L))
        assertTrue(c.reserveFresh8174(10_000L, 39_000L))
    }

    @Test fun theFinalBuyDoorRequestsFreshReservesWhenItsCurveEvidenceAgesOut() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(s.contains("CURVE_RESERVES_STALE_DEFER_8174"))
        assertTrue(s.contains("LaunchCohort8173.reserveFresh8174(refreshedAt8174"))
        assertTrue(s.contains("needCurve8039(ts.mint, nowMs)"))
    }
}
