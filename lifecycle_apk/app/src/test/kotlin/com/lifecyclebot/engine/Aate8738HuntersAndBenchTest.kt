package com.lifecyclebot.engine

import com.lifecyclebot.network.ChainLaunchFeed8179
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8738 — LaunchLab births heard (real program id), launch streams sized by the plan, the bench ranked. */
class Aate8738HuntersAndBenchTest {
    @Test fun launchLabIsTheRealProgram() {
        assertEquals("LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eADduJ3uj", ChainLaunchFeed8179.RAYDIUM_LAUNCHLAB_8179)
        val src = File("src/main/kotlin/com/lifecyclebot/network/ChainLaunchFeed8179.kt").readText()
        assertTrue(!src.contains("RAYDIUM_LAUNCHLAB_8179 = \"LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eAd3gNb7J\""))
    }

    @Test fun theLaunchGovernorScalesWithThePlan() {
        assertEquals(40, ChainLaunchFeed8179.governorMax8738(400_000.0))
        assertEquals(283, ChainLaunchFeed8179.governorMax8738(2_833_333.0))
        assertEquals(40, ChainLaunchFeed8179.governorMax8738(Double.NaN))
        assertEquals(400, ChainLaunchFeed8179.governorMax8738(1.0e9))
    }

    @Test fun theBenchEvictsTheLowestRated() {
        val now = 10_000_000L
        fun row(m: String, age: Long) = WatchlistEviction8398.Row(m, "PUMP_FUN_NEW", now - age, 0L, 5, false)
        val pool = WatchlistEviction8398.Pool(listOf(row("old", 900_000), row("mid", 600_000), row("new", 200_000)), 0)
        val ranks = mapOf("old" to 5.0, "mid" to -12.0, "new" to 1.0)
        assertEquals("mid", WatchlistEviction8398.victim8738(pool, now, false, { true }) { ranks[it] }?.mint)
        assertEquals("old", WatchlistEviction8398.victim8738(pool, now, false, { true }) { null }?.mint)   // untrained: oldest
        val unrated = mapOf("mid" to -12.0, "new" to 1.0)
        assertEquals("old", WatchlistEviction8398.victim8738(pool, now, false, { true }) { unrated[it] }?.mint)  // no features after 5 min
    }
}
