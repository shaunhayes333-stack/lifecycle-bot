package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.SetupAuthority8535
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8672EconomicLearningTest {
    @Before fun start() = SemanticPatternGraph.reset()
    @After fun finish() = SemanticPatternGraph.reset()

    private fun record(id: Int, pnl: Double, peak: Double, book: String = "LIVE") =
        SemanticPatternGraph.recordOutcome("SHITCOIN", "LAUNCH", "breakout retest", pnlPct = pnl,
            peakGainPct = peak, positionId = "position-$id", mode = book)

    private fun bias(book: String = "LIVE") =
        SemanticPatternGraph.entryDnaBias("breakout retest", "SHITCOIN", mode = book)

    @Test fun changingOnlyTheUnrealisedPeakCannotIncreaseRisk() {
        repeat(10) { record(it, -20.0, 0.0) }
        val control = bias()
        SemanticPatternGraph.reset()
        repeat(10) { record(it, -20.0, if (it < 4) 120.0 else 0.0) }
        assertEquals(-4, control.scoreDelta)
        assertEquals(0.94, control.sizeMult, 0.0)
        assertEquals(control, bias())
    }

    @Test fun actualWinnersReceiveCreditOnlyInTheirBookIncludingAfterRestore() {
        repeat(10) { record(it, -20.0, 120.0); record(it, 85.0, 95.0, "PAPER") }
        val n = SemanticPatternGraph.nodeCount6512()
        val edges = SemanticPatternGraph.edgeCount6512()
        record(0, -20.0, 120.0)
        assertEquals(n, SemanticPatternGraph.nodeCount6512())
        assertEquals(edges, SemanticPatternGraph.edgeCount6512())
        val state = SemanticPatternGraph.exportState()
        SemanticPatternGraph.reset()
        SemanticPatternGraph.importState(state)
        assertEquals(4, bias("PAPER").scoreDelta)
        assertEquals(-4, bias().scoreDelta)
    }

    @Test fun unscopedHistoricalNodesRemainVisibleForResearchOnly() {
        SemanticPatternGraph.importState("""{"nodes":[{"id":"legacy","lane":"SHITCOIN","setup":"breakout retest","pnlPct":500}]}""")
        assertEquals(1, SemanticPatternGraph.querySimilar("breakout retest").size)
        assertEquals(0, bias().scoreDelta)
        assertEquals(0, bias("PAPER").scoreDelta)
    }

    @Test fun missingFeaturesKeepQualityAndUnwitnessedBreakoutsInObservation() {
        for (id in SetupAuthority8535.QUALITY_ONLY_8535 + SetupAuthority8535.NEAR_HIGH_8535 +
            SetupAuthority8535.PARTIAL_ON_UNKNOWN_BP_8535) assertFalse(id, SetupAuthority8535.executable8535(id, null))
        assertTrue(SetupAuthority8535.executable8535("NATIVE_INDEPENDENT_EVENT", null))
    }

    @Test fun allRegisteredMenusEvaluateTheSameRecordedLaunchFrame() {
        // Decision 1794331445002, fields available in the edge export only.
        val frame = LanePlaybook7907.F(age=52.05,runup=1.87,pxPeak=0.994927,dd=0.507279,bp=50.2168,liq=26302.6,mcap=142015.0,top=5.9864,
            chg5m=1.87,chg1h=321.0,holderGrowth=Double.NaN,volAccel=Double.NaN,planSetup="",launchPhase="MATURE",
            crowdForming=false,expertEntry7962=false,structureHlReclaim=false,secondWave7974=false,holderVel7978=Double.NaN,
            turnover7978=0.311738,curve7978=1.0,live7978=false,beta7978=false,migrated8308=true,
            txVelocity=553.6,newBuyers15s8666=Double.NaN)
        val lanes = LanePlaybook7907.menuLanes8383()
        assertEquals(12, lanes.size)
        for (lane in lanes) {
            val (matched, errors) = requireNotNull(LanePlaybook7907.matchDetailed8084(lane, frame))
            assertTrue("$lane: $errors", errors.isEmpty())
            assertTrue(LanePlaybook7907.menuIds(lane).containsAll(matched))
            if (lane == "TREASURY") {
                assertTrue(matched.contains("YOUNG_LIQUID_8383"))
                assertFalse(SetupAuthority8535.executable8535("YOUNG_LIQUID_8383", frame))
                assertTrue(matched.contains("FLAG_CONTINUATION"))
                assertTrue(SetupAuthority8535.executable8535("FLAG_CONTINUATION", frame))
            }
        }
    }
}
