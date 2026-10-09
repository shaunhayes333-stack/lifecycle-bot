package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.MemeMeta7973
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7973MemeMetaTest {

    @Test fun curveProgressFromMarketCapAlone() {
        val sol = 150.0
        assertEquals(0.0, MemeMeta7973.curveProgress7973(30.0 * 30.0 / 32.19 * sol, sol), 1e-6)      // launch
        assertEquals(0.5, MemeMeta7973.curveProgress7973(72.5 * 72.5 / 32.19 * sol, sol), 1e-6)      // halfway (vSol 72.5)
        assertEquals(1.0, MemeMeta7973.curveProgress7973(115.0 * 115.0 / 32.19 * sol, sol), 1e-6)    // graduates (~411 SOL mcap)
        assertTrue(MemeMeta7973.curveProgress7973(0.0, sol).isNaN())
        assertEquals("80_95", MemeMeta7973.gradBin7973(0.9))
        assertEquals("95_100", MemeMeta7973.gradBin7973(0.97))
        assertEquals("DONE", MemeMeta7973.gradBin7973(1.0))
        assertEquals("NA", MemeMeta7973.gradBin7973(Double.NaN))
    }

    @Test fun copycatThemesFromLeaders() {
        assertEquals(setOf("frog", "king"), MemeMeta7973.words7973("FROG", "The Frog King coin"))
        val now = 1_000_000_000L
        MemeMeta7973.noteLeader7973("LEADERMINT0000000000000000000000001", "FROG", "Frog King", now)
        assertTrue(MemeMeta7973.beta7973("COPYMINT00000000000000000000000002", "KFROG", "baby frog", now + 60_000L))
        assertFalse(MemeMeta7973.beta7973("OTHERMINT0000000000000000000000003", "DOGE", "dog wif hat", now + 60_000L))
        assertFalse(MemeMeta7973.beta7973("COPYMINT00000000000000000000000002", "KFROG", "baby frog", now + 7 * 3_600_000L))  // theme expired
        assertFalse(MemeMeta7973.beta7973("LEADERMINT0000000000000000000000001", "FROG", "Frog King", now))                 // the leader itself
    }

    @Test fun parsesLiveAndKothBodies() {
        val list = MemeMeta7973.coins7973("""[{"mint":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump","symbol":"AAA","usd_market_cap":45000.5},{"mint":"short"}]""")
        assertEquals(1, list.size)
        assertEquals(45000.5, list[0].third, 1e-9)
        val one = MemeMeta7973.coins7973("""{"mint":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBpump","symbol":"BBB","usd_market_cap":60000}""")
        assertEquals("BBB", one[0].second)
        assertTrue(MemeMeta7973.statusLine7973().contains("liveNow="))
    }
}
