package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881 as Economy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8452 — 8450's Helius day: ENRICHMENT (Enhanced v0 transactions at 100 credits a call — swap candles, bundle scans)
 * spent 155.8k of the 300k budget, the day's pace closed, and the 1-credit decision reads behind the safety proof, holder
 * distribution, supply and pools were refused (hardGate8066 refused 2,263; chain launches HELIUS_OVER_BUDGET): 26 FDG allows,
 * 23 tickets, 0 buys, all DEFERRED_DATA_SAFETY_PROOF_8270. Enrichment is capped at a quarter of the day; the decision
 * reserve is 15 %.
 */
class Aate8452HeliusBudgetTest {
    @Test fun enrichmentStopsAtAQuarterOfThePacedDay() {
        val budget = 300_000.0
        assertEquals(0.25, Economy.ENRICHMENT_WIRE_SHARE_8452, 0.0)
        assertEquals(0.15, Economy.DECISION_RESERVE_SHARE_8397, 0.0)
        // end of the day: 75k for enrichment in total
        assertFalse(Economy.enrichmentCapped8452(74_000.0, 100.0, budget, 1.0))
        assertTrue("8450's 155.8k is far past it", Economy.enrichmentCapped8452(155_800.0, 100.0, budget, 1.0))
        // paced: at a quarter of the day only ~22k
        assertTrue(Economy.enrichmentCapped8452(25_000.0, 100.0, budget, 0.25))
        assertFalse(Economy.enrichmentCapped8452(20_000.0, 100.0, budget, 0.25))
    }

    @Test fun theCapSitsAtTheWireDoorForEveryEnrichmentRequest() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/HeliusCreditEconomy7881.kt").readText()
        val wire = s.substring(s.indexOf("fun authorizeWire8397("), s.indexOf("fun budgetGated8066("))
        val cap = wire.indexOf("if (tier8452 == Tier.ENRICHMENT && enrichmentCapped8452(")
        assertTrue(cap > 0)
        assertTrue("checked before any reservation can admit it", cap < wire.indexOf("var reserved = reservation"))
        assertEquals(Economy.Tier.ENRICHMENT, Economy.tierFor("api.helius.xyz", "/v0/addresses/x/transactions", emptyList()))
        assertEquals(Economy.Tier.DECISION, Economy.tierFor("mainnet.helius-rpc.com", "/", listOf("getMultipleAccounts")))
    }
}
