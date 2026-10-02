package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Aate7725PredictiveSourceEvidenceTest {
    @Test fun source_family_evidence_resolves_scanner_names_and_survives_restart_by_mode() {
        val scorecard = SourceFamilyOpportunityScorecard
        scorecard.reset()
        scorecard.recordClosed(
            "PUMP_PORTAL_WS",
            Trade(side = "PARTIAL_SELL", mode = "live", sol = 0.01, price = 1.0, ts = 100L,
                pnlSol = 0.01, netPnlSol = 0.01, entryCostSol = 0.10),
        )
        assertNull("partial exits are not independent terminal outcomes", scorecard.expectancyFor6915("PUMP_PORTAL_WS", true))
        scorecard.recordClosed(
            "PUMP_PORTAL_WS",
            Trade(side = "SELL", mode = "live", sol = 0.11, price = 1.0, ts = 200L,
                pnlSol = 0.01, netPnlSol = 0.01, entryCostSol = 0.10),
        )
        assertEquals(1, scorecard.expectancyFor6915("PUMP_PORTAL_WS", true)!!.closed)
        scorecard.reset()
        // Seed outcomes through the same terminal-trade path production uses,
        // then verify the persisted mode-separated source families.
        fun closedTrade(mode: String, pnl: Double, basis: Double) = Trade(
            side = "SELL", mode = mode, sol = basis + pnl, price = 1.0, ts = 200L,
            pnlSol = pnl, netPnlSol = pnl, entryCostSol = basis,
        )
        repeat(2) { scorecard.recordClosed("PUMP_PORTAL_WS", closedTrade("live", 0.01, 0.10)) }
        scorecard.recordClosed("PUMP_PORTAL_WS", closedTrade("live", 0.01, 0.10))
        scorecard.recordClosed("DEXSCREENER_PAIR_P", closedTrade("live", 0.01, 0.10))
        scorecard.recordClosed("DEXSCREENER_PAIR_P", closedTrade("live", -0.03, 0.10))
        repeat(20) { scorecard.recordClosed("PUMP_PORTAL_WS", closedTrade("paper", 0.02, 0.01)) }

        val live = scorecard.expectancyFor6915("PUMP_PORTAL_WS,DEXSCREENER_PAIR_P", true)
        assertNotNull(live)
        assertEquals(5, live!!.closed)
        assertEquals(4, live.wins)
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
