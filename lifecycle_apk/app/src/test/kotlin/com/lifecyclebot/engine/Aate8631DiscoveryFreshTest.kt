package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8631 — discovery offers fresh, real candidates (audit_8629/audit_discovery_now.md D1, D3, D5): a re-sighting no
 * longer resets the watchlist admission time, the runner lanes skip coins past their age cap, and the PumpPortal fast lane
 * fires on a witnessed create instead of an estimated liquidity a fresh create never has.
 */
class Aate8631DiscoveryFreshTest {

    private fun src(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L

    // ── D1: addedAt is the first admission ──

    @Test fun aResightingRecordsLastSightedAtAndKeepsTheFirstAdmission() {
        val e = GlobalTradeRegistry.WatchlistEntry(
            mint = "Mint8631xxxxxxxxxxxxxxxxxxxxxxxxxxxx", symbol = "OLD", addedAt = now - 30 * minute,
            addedBy = "MARKET_HUNT_MOONSHOT", source = "MARKET_HUNT_MOONSHOT", initialMcap = 900_000.0,
        )
        assertEquals("a new entry was last sighted at its admission", now - 30 * minute, e.lastSightedAt)
        GlobalTradeRegistry.noteResighting8631(e, now)
        assertEquals("the admission time never moves", now - 30 * minute, e.addedAt)
        assertEquals(now, e.lastSightedAt)
        GlobalTradeRegistry.noteResighting8631(e, now - minute)
        assertEquals("an out-of-order sighting does not move it back", now, e.lastSightedAt)
    }

    @Test fun aResightedOldCoinIsNotFreshAndIsTheEvictionVictim() {
        // Re-served by a trending list a second ago, first admitted 30 minutes ago: not in the 120 s fresh tier.
        assertFalse(DiscoveryFresh8631.freshTier8631(now - 30 * minute, now, 120_000L, null))
        assertTrue(DiscoveryFresh8631.freshTier8631(now - 30_000L, now, 120_000L, null))
        assertTrue("an unknown age never blocks", DiscoveryFresh8631.freshTier8631(now - 30_000L, now, 120_000L, null))
        assertTrue(DiscoveryFresh8631.freshTier8631(now - 30_000L, now, 120_000L, DiscoveryFresh8631.MAX_RUNNER_AGE_MS_8631))
        assertFalse("a coin past every runner cap is not fresh",
            DiscoveryFresh8631.freshTier8631(now - 30_000L, now, 120_000L, DiscoveryFresh8631.MAX_RUNNER_AGE_MS_8631 + 1L))
        // Eviction orders by the (now immutable) admission time: the re-sighted veteran goes before a one-off launch.
        val veteran = WatchlistEviction8398.Row("vet", "DEX_BOOSTED", now - 30 * minute, now - 40_000L, 40, false)
        val launch = WatchlistEviction8398.Row("new", "PUMP_PORTAL_WS", now - 6 * minute, now - 40_000L, 5, false)
        val pool = WatchlistEviction8398.pool8398(listOf(launch, veteran), now, "incoming")
        assertEquals("vet", WatchlistEviction8398.victim8398(pool, false) { false }?.mint)
    }

    // ── D3: runner-lane age caps ──

    @Test fun runnerLanesHaveOneAgeTable() {
        assertEquals(6 * hour, DiscoveryFresh8631.SHITCOIN_MAX_AGE_MS_8631)
        assertEquals(3 * hour, DiscoveryFresh8631.MOONSHOT_MAX_AGE_MS_8631)
        assertEquals(hour, DiscoveryFresh8631.EXPRESS_MAX_AGE_MS_8631)
        assertEquals(30 * minute, DiscoveryFresh8631.SNIPER_MAX_AGE_MS_8631)
        assertEquals(30 * minute, DiscoveryFresh8631.MANIPULATED_MAX_AGE_MS_8631)
        assertEquals(6 * hour, DiscoveryFresh8631.MAX_RUNNER_AGE_MS_8631)
        assertEquals(setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED"),
            DiscoveryFresh8631.RUNNER_LANE_MAX_AGE_MS_8631.keys)
        assertEquals(hour, DiscoveryFresh8631.runnerAgeCapMs8631("express"))
        for (lane in listOf("QUALITY", "BLUECHIP", "TREASURY", "DIP_HUNTER", "CORE", "CYCLIC", ""))
            assertNull(lane, DiscoveryFresh8631.runnerAgeCapMs8631(lane))
        // LaneAuthority's 24 h live refusal stays the outer bound over every lane cap.
        for ((lane, cap) in DiscoveryFresh8631.RUNNER_LANE_MAX_AGE_MS_8631) {
            assertTrue(lane, cap <= LaneAuthority8577.RUNNER_MAX_AGE_MS_8627)
            assertTrue(lane, LaneAuthority8577.runnerCoinStale8627(lane, LaneAuthority8577.RUNNER_MAX_AGE_MS_8627 + 1L))
        }
    }

    @Test fun aRunnerLaneSkipsACoinPastItsCapAndUnknownNeverBlocks() {
        for ((lane, cap) in DiscoveryFresh8631.RUNNER_LANE_MAX_AGE_MS_8631) {
            assertTrue(lane, DiscoveryFresh8631.runnerLaneTooOld8631(lane, cap + 1L))
            assertFalse(lane, DiscoveryFresh8631.runnerLaneTooOld8631(lane, cap))
            assertFalse(lane, DiscoveryFresh8631.runnerLaneTooOld8631(lane, 0L))
            assertFalse("$lane: an unknown age never blocks", DiscoveryFresh8631.runnerLaneTooOld8631(lane, null))
        }
        assertTrue(DiscoveryFresh8631.runnerLaneTooOld8631("moonshot", 18 * hour))   // MH_MOONSHOT median first-decision age
        assertFalse(DiscoveryFresh8631.runnerLaneTooOld8631("SHITCOIN", 5 * hour))
        for (lane in listOf("QUALITY", "BLUECHIP", "TREASURY", "DIP_HUNTER", "CORE"))
            assertFalse(lane, DiscoveryFresh8631.runnerLaneTooOld8631(lane, 400L * 24L * hour))
    }

    // ── D5: the fresh-launch fast lane ──

    @Test fun theFastLaneFiresOnAWitnessedCreateNotOnEstimatedLiquidity() {
        val pp = FreshLaunchIntake8303.SOURCE_8303
        assertTrue(DiscoveryFresh8631.fastLaneTrigger8631(pp, now - 2_000L, now, watched = true))
        assertFalse("a parked create is not fast-laned", DiscoveryFresh8631.fastLaneTrigger8631(pp, now - 2_000L, now, watched = false))
        assertFalse("past the sniper window", DiscoveryFresh8631.fastLaneTrigger8631(pp, now - 180_001L, now, watched = true))
        assertFalse("no witnessed birth", DiscoveryFresh8631.fastLaneTrigger8631(pp, null, now, watched = true))
        assertFalse("not a PumpPortal create", DiscoveryFresh8631.fastLaneTrigger8631("DEX_TRENDING", now - 2_000L, now, watched = true))
    }

    @Test fun aHotFreshCreateIsExemptFromThePumpShare() {
        assertTrue(FreshLaunchIntake8303.pumpShareExempt8631(now - 1_000L, now))
        assertTrue(FreshLaunchIntake8303.pumpShareExempt8631(now - 180_000L, now))
        assertFalse(FreshLaunchIntake8303.pumpShareExempt8631(now - 180_001L, now))
        assertFalse(FreshLaunchIntake8303.pumpShareExempt8631(null, now))
        assertFalse(FreshLaunchIntake8303.pumpShareExempt8631(0L, now))
        assertFalse("never seen hot", FreshLaunchIntake8303.hotCreate8631("NeverHot8631xxxxxxxxxxxxxxxxxxxxxxxxpump"))
        // A witnessed create that goes hot through the 8303 park is the one the share exempts (unless the budget parked it).
        val mint = "HotCreate8631xxxxxxxxxxxxxxxxxxxxxxxpump"
        AdmissionBirth8144.noteCreateFrame8144(mint, System.currentTimeMillis())
        val parked = FreshLaunchIntake8303.park8303(true, false, FreshLaunchIntake8303.SOURCE_8303, mint)
        assertEquals(!parked, FreshLaunchIntake8303.hotCreate8631(mint))
    }

    // ── wiring pins ──

    @Test fun theWiringIsAtTheSource() {
        val reg = src("engine/GlobalTradeRegistry.kt")
        assertTrue(reg.contains("        val addedAt: Long,\n"))
        assertTrue(reg.contains("        var lastSightedAt: Long = addedAt,\n"))
        assertTrue(reg.contains("noteResighting8631(existing, now)\n            duplicatesBlocked.incrementAndGet()"))
        assertFalse("no re-sighting writes the admission time", reg.contains("existing.addedAt = now\n"))
        assertTrue(reg.contains("shouldDivertPumpToProbation(addedBy, source, initialMcap, laneAffinity, toolAffinity) &&\n            !freshCreatePumpShareExempt8631(mint, now)) {"))
        assertTrue(src("engine/FreshLaunchIntake8303.kt").substringAfter("fun park8303(").contains("noteHot8631(mint, nowMs)"))
        // superseded by V5.0.8633 — D8 drops the high-touch watchlist layer from the warmup entirely, so no watchlist row's time is read there any more; was: assertTrue(src("engine/HotConvictionWarmup.kt").contains("lastSeenMs = r.lastSightedAt,"))
        assertFalse(src("engine/HotConvictionWarmup.kt").contains("source = \"WATCHLIST_HIGH_TOUCH\""))
        assertTrue(src("engine/truth/LaneAuthority8577.kt").contains(
            "private val RUNNER_LANES_8627: Set<String> = com.lifecyclebot.engine.DiscoveryFresh8631.RUNNER_LANE_MAX_AGE_MS_8631.keys"))

        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("freshTier8631(mint, entry.addedAt, nowMs, FRESH_WINDOW_MS) -> fresh.add(mint)"))
        assertFalse(bot.contains("(nowMs - entry.addedAt) < FRESH_WINDOW_MS -> fresh.add(mint)"))
        val gate = bot.substringAfter("private fun laneEvaluationAllowed7951(")
        val skip = gate.indexOf("if (runnerLaneAgeSkip8631(ts, l)) return false")
        assertTrue(skip in 0 until gate.indexOf("val ownBrainReady8429"))
        assertTrue(bot.substringAfter("private fun runnerLaneAgeSkip8631(").contains("CanonicalTokenBirthTime7440.launchAgeMs7767(ts, System.currentTimeMillis())"))
        assertTrue(bot.substringAfter("private fun runnerLaneAgeSkip8631(").contains("laneGateCount(\"RUNNER_AGE_SCOPE_8631\", lane)"))
        val fast = bot.indexOf("if (pumpFastLaneDue8631(mint)) {")
        assertTrue(fast > 0 && fast < bot.indexOf("fastLaneEvaluate7277(mint, liveCfg, \"PUMP_PORTAL_WS\")"))
        assertFalse(bot.contains("if (estLiq >= 2_000.0) {"))
        assertTrue(bot.contains("val candidates6968 = pumpEntries.filter { !FreshLaunchIntake8303.hotCreate8631(it.mint) }.take(excess)"))
    }
}
