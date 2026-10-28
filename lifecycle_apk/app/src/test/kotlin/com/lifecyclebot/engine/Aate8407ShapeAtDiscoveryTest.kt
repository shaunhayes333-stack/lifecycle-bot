package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import com.lifecyclebot.engine.truth.PairFlow8169
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8407 — shape at discovery from the exchange's reported 5m / 1h changes; nothing is guessed. */
class Aate8407ShapeAtDiscoveryTest {
    @Test fun the_implied_price_is_exact_arithmetic_on_the_reported_change() {
        assertEquals(100.0, DiscoveryCapture8400.impliedPriceBefore8407(125.0, 25.0), 1e-9)
        assertEquals(200.0, DiscoveryCapture8400.impliedPriceBefore8407(100.0, -50.0), 1e-9)
        assertTrue(DiscoveryCapture8400.impliedPriceBefore8407(100.0, -100.0).isNaN())   // a dead pair derives nothing
        assertTrue(DiscoveryCapture8400.impliedPriceBefore8407(Double.NaN, 5.0).isNaN())
        assertTrue(DiscoveryCapture8400.impliedPriceBefore8407(100.0, Double.NaN).isNaN())
    }

    @Test fun a_fresh_token_with_a_pair_row_gets_two_points_and_the_shape_read_sees_three() {
        val now = 1_793_000_000_000L
        val ts = TokenState(mint = "Shape8407Mint1111111111111111111111111111111", symbol = "S").apply {
            lastPrice = 120.0
            history.add(Candle(ts = now, priceUsd = 120.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0))
        }
        PairFlow8169.note8169(ts.mint, PairFlow8169.Flow8169(10, 5, 60, 40, 20.0, -40.0, 5000.0, now,
            priceUsd8417 = 120.0, pairCreatedAtMs8417 = now - 7_200_000L))
        assertEquals(2, DiscoveryCapture8400.seedShape8407(ts, now))
        assertEquals(3, DiscoveryCapture8400.distinctPoints8407(ts.history.toList()))
        val pts = ts.history.toList()
        assertEquals(now - 3_600_000L, pts[0].ts); assertEquals(200.0, pts[0].priceUsd, 1e-9)   // -40% over the hour
        assertEquals(now - 300_000L, pts[1].ts); assertEquals(100.0, pts[1].priceUsd, 1e-9)     // +20% over 5 minutes
        assertTrue(pts[0].synthetic && pts[1].synthetic && !pts[2].synthetic)
        assertEquals(LanePlaybook7907.Obs8084.OBSERVED, LanePlaybook7907.shapeObs8084(ts.history.toList(), now))
        assertEquals(0, DiscoveryCapture8400.seedShape8407(ts, now))   // idempotent
    }

    @Test fun no_flow_or_a_stale_flow_seeds_nothing() {
        val now = 1_793_000_000_000L
        val none = TokenState(mint = "NoFlow8407Mint111111111111111111111111111111", symbol = "N").apply { lastPrice = 1.0 }
        assertEquals(0, DiscoveryCapture8400.seedShape8407(none, now))
        val stale = TokenState(mint = "Stale8407Mint1111111111111111111111111111111", symbol = "T").apply { lastPrice = 1.0 }
        PairFlow8169.note8169(stale.mint, PairFlow8169.Flow8169(1, 1, 1, 1, 5.0, 5.0, 100.0, now - 11 * 60_000L))
        assertEquals(0, DiscoveryCapture8400.seedShape8407(stale, now))
    }

    @Test fun it_is_wired_at_intake_and_before_the_shape_read() {
        val cap = File("src/main/kotlin/com/lifecyclebot/engine/truth/DiscoveryCapture8400.kt").readText()
        assertTrue(cap.substringAfter("fun captureAtIntake8400(").substringBefore("SHAPE_FROM_THE_EXCHANGE").contains("seedShape8407(ts)"))
        val pb = File("src/main/kotlin/com/lifecyclebot/engine/cortex/LanePlaybook7907.kt").readText()
        val seed = pb.indexOf("DiscoveryCapture8400.seedShape8407(ts, nowMs)")
        val read = pb.indexOf("val shapeObs = try { shapeObs8084(ts.history.toList(), nowMs) }")
        assertTrue(seed in 1 until read)
    }
}
