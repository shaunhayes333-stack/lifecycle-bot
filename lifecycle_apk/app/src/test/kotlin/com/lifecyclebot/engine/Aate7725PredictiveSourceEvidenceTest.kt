package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Aate7725PredictiveSourceEvidenceTest {
    @Test fun source_family_evidence_resolves_scanner_names_and_survives_restart_by_mode() {
        val scorecard = SourceFamilyOpportunityScorecard
        scorecard.reset()
        scorecard.importState(
            """{"schema":2,"legacy":[],"live":[
                {"k":"PUMP_FAMILY","c":3,"w":2,"p":0.03,"cost":0.30},
                {"k":"DEX","c":2,"w":1,"p":-0.02,"cost":0.20}],
                "paper":[{"k":"PUMP_FAMILY","c":20,"w":18,"p":0.40,"cost":0.20}]}"""
        )

        val live = scorecard.expectancyFor6915("PUMP_PORTAL_WS,DEXSCREENER_PAIR_P", true)
        assertNotNull(live)
        assertEquals(5, live!!.closed)
        assertEquals(3, live.wins)
        assertEquals(2.0, live.meanPnlPct, 0.0001)
        assertEquals(20, scorecard.expectancyFor6915("PUMP_PORTAL_WS", false)!!.closed)

        val durable = scorecard.exportState()
        scorecard.reset()
        scorecard.importState(durable)
        assertEquals(5, scorecard.expectancyFor6915("PUMP_PORTAL_WS,DEXSCREENER_PAIR_P", true)!!.closed)
        assertEquals(20, scorecard.expectancyFor6915("PUMP_PORTAL_WS", false)!!.closed)

        // A legacy pooled record cannot be assumed to be a live record.
        scorecard.importState("""[{"k":"PUMP_FAMILY","c":99,"w":99,"p":9.9,"cost":1.0}]""")
        assertNull(scorecard.expectancyFor6915("PUMP_PORTAL_WS", true))
        assertNull(scorecard.expectancyFor6915("PUMP_PORTAL_WS", false))
    }
}
