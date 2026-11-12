package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8627 — 8622 edge export: the runner lanes' decisions on coins over a day old made two 2x moves in 1,541 while LIVE
 * bought 202 of them; and 13 fresh launches booked +2,600..+2,930 % on an intake seed price used as the label's basis.
 */
class Aate8627StaleCoinAndSeedBasisTest {
    @Test fun a_runner_lane_does_not_buy_a_coin_more_than_a_day_old() {
        val day = LaneAuthority8577.RUNNER_MAX_AGE_MS_8627
        for (lane in listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "manipulated")) {
            assertTrue(lane, LaneAuthority8577.runnerCoinStale8627(lane, day + 1L))
            assertFalse(lane, LaneAuthority8577.runnerCoinStale8627(lane, day))
            assertFalse("an unknown age never refuses", LaneAuthority8577.runnerCoinStale8627(lane, null))
        }
        for (lane in listOf("QUALITY", "BLUECHIP", "TREASURY", "DIP_HUNTER", "CORE"))
            assertFalse(lane, LaneAuthority8577.runnerCoinStale8627(lane, 10L * day))
        assertTrue("it binds at the live gate", LaneAuthority8577.binding8577(LaneAuthority8577.LIVE_RUNNER_STALE_COIN_8627))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        // V5.0.8696 — the entry-state doors live in memeEntryState8696; memeEntryRefusal8626 applies the one retirement rule to them.
        assertTrue(src.substringAfter("fun memeEntryRefusal8626(").substringBefore("\n    }\n").contains("memeEntryState8696(ts, lane, stage, nowMs) ?: return null"))   // (8724: bound to first8724, same call)
        val fn = src.substringAfter("private fun memeEntryState8696(").substringBefore("\n    }\n")
        assertTrue(fn.indexOf("runnerCoinStale8627(l, age8627)") in 0 until fn.indexOf("if (l !in MEME_LANES_8582) return null"))
    }

    @Test fun an_intake_seed_price_is_not_a_label_basis() {
        val state = LabelMark8386("M8627", "", 2.5e-6, 1_000L, 1_000L, "TOKEN_STATE")
        val seeded = ForwardReturnLabeler7731.seedBasis8627(state, state, "PUMP_FUN_BC_SYNTHETIC")
        assertEquals("UNKNOWN", seeded.source)
        assertFalse("the first traded mark in the window becomes the basis", seeded.known())
        assertSame("a traded price stays the basis", state, ForwardReturnLabeler7731.seedBasis8627(state, state, "PUMP_PORTAL_TRADE_WS_7278"))
        val chain = state.copy(source = "CURVE_ACCOUNT")
        assertSame("a chain mark chosen over the seed stays", chain, ForwardReturnLabeler7731.seedBasis8627(chain, state, "PUMP_FUN_BC_SYNTHETIC"))
        assertTrue(ForwardReturnLabeler7731.seedBasisUnknownCount8627() >= 1L)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(src.contains("val basisMark8571 = seedBasis8627(basisChosen8571, stateMark8527, ts.lastPriceSource)"))
    }
}
