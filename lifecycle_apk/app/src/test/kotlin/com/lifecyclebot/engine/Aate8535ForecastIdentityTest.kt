package com.lifecyclebot.engine

import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import com.lifecyclebot.v3.scoring.EntryAI
import com.lifecyclebot.v3.scoring.MetaCognitionAI as Meta
import com.lifecyclebot.v3.scoring.MomentumAI
import com.lifecyclebot.v3.scoring.ScoreComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8535 — audit C03 (MetaCognition forecasts keyed by mint: a paper scan, a rescore, a second position or a shadow
 * close could consume another decision's votes) and C04 (one buy-pressure reading scored as two independent layers).
 */
class Aate8535ForecastIdentityTest {
    @Before fun setup() { Meta.reset() }
    @After fun cleanup() { Meta.reset() }

    private fun analysed() = Meta.saveToJson().optInt("totalTradesAnalyzed", -1)
    private val mint = "FORECAST8535pumpAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    // ── C03 ──

    @Test fun staging_and_settlement_identities() {
        assertEquals("live|m", Meta.stagingKey8535("", "m"))
        assertEquals("paper|m", Meta.stagingKey8535("PAPER", " m "))
        assertEquals("P|pos1", Meta.settlementKey8535("pos1", "t1", "m", 5L))
        assertEquals("T|t1", Meta.settlementKey8535("", "t1", "m", 5L))
        assertEquals("M|m|5", Meta.settlementKey8535("", "", "m", 5L))
    }

    @Test fun a_paper_settlement_cannot_consume_the_live_forecast() {
        Meta.recordFromScoreCard(mint, "X", listOf(ScoreComponent("momentum", 20, "obs")), mode8535 = "live")
        Meta.recordFromScoreCard(mint, "X", listOf(ScoreComponent("momentum", -20, "obs")), mode8535 = "paper")
        val before = analysed()
        Meta.recordTradeOutcome(mint, "X", 5.0, 60_000L, "tp", mode8535 = "paper")
        assertEquals("the paper close learnt from the paper slot", before + 1, analysed())
        Meta.recordTradeOutcome(mint, "X", -5.0, 60_000L, "sl", mode8535 = "live")
        assertEquals("the live slot was still there for the live close", before + 2, analysed())
        Meta.recordTradeOutcome(mint, "X", -5.0, 60_000L, "sl", mode8535 = "live")
        assertEquals("nothing left to learn from", before + 2, analysed())
    }

    @Test fun one_position_is_learnt_from_once_and_a_shadow_close_never_takes_a_live_slot() {
        Meta.recordFromScoreCard(mint, "X", listOf(ScoreComponent("momentum", 20, "obs")), mode8535 = "live")
        val before = analysed()
        // a shadow close of the same mint: its own cohort, no live slot consumed
        Meta.recordTradeOutcome(mint, "X", 9.0, 60_000L, "shadow_long", mode8535 = "shadow", tradeId8535 = "shadow|1")
        assertEquals(before, analysed())
        // the live position (no sealed snapshot in a unit test -> the live staging slot) settles once
        Meta.recordTradeOutcome(mint, "X", 3.0, 60_000L, "tp", positionId8535 = "LIVE:p1", mode8535 = "live")
        assertEquals(before + 1, analysed())
        // the same position id reported again (partial / replay / restart) does not learn twice
        Meta.recordTradeOutcome(mint, "X", 3.0, 60_000L, "tp", positionId8535 = "LIVE:p1", mode8535 = "live")
        assertEquals(before + 1, analysed())
        assertNull("no sealed snapshot in a unit test", Meta.sealedForecast8535("LIVE:p1", mint, "live"))
    }

    // ── C04 ──

    private val ctx = TradingContext(TradingConfigV3(), V3BotMode.PAPER)
    private fun cand(bp: Double, extra: Map<String, Any?>) = CandidateSnapshot(
        mint = mint, symbol = "X", source = SourceType.DEX_TRENDING, discoveredAtMs = 1_000L, ageMinutes = 30.0,
        liquidityUsd = 20_000.0, marketCapUsd = 100_000.0, buyPressurePct = bp, volume1mUsd = 100.0, volume5mUsd = 500.0, extra = extra,
    )

    @Test fun buy_pressure_alone_is_one_vote_not_two() {
        val c = cand(75.0, mapOf("buyPressureKnown" to true, "momentumKnown" to false))
        assertEquals(8, EntryAI().score(c, ctx).value)
        assertEquals("no observed price momentum: no momentum vote", 0, MomentumAI().score(c, ctx).value)
    }

    @Test fun momentum_is_graded_on_observed_price_momentum_with_buyers_as_confirmation_only() {
        val strong = cand(75.0, mapOf("buyPressureKnown" to true, "momentumKnown" to true, "momentumScore" to 80.0))
        assertEquals(10, MomentumAI().score(strong, ctx).value)   // 8 price + 2 buyer confirmation
        val strongNoBuyers = cand(50.0, mapOf("buyPressureKnown" to false, "momentumKnown" to true, "momentumScore" to 80.0))
        assertEquals(8, MomentumAI().score(strongNoBuyers, ctx).value)
        val weak = cand(75.0, mapOf("buyPressureKnown" to true, "momentumKnown" to true, "momentumScore" to 20.0))
        assertEquals("strong buyers cannot turn a falling price into momentum", -7, MomentumAI().score(weak, ctx).value)
        val flat = cand(75.0, mapOf("buyPressureKnown" to true, "momentumKnown" to true, "momentumScore" to 50.0))
        assertEquals(0, MomentumAI().score(flat, ctx).value)
        val shortHistory = cand(75.0, mapOf("buyPressureKnown" to true, "momentumKnown" to true, "momentumUp" to true))
        assertTrue(MomentumAI().score(shortHistory, ctx).value in 2..4)
    }
}
