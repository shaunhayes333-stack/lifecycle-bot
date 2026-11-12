package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterImpact8730
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8730 — Jupiter impact read in Jupiter's documented units; a chain label alone does not prove a pool-bought bag's price. */
class Aate8730PriceTruthTest {
    @Test fun impactIsPercentagePointsWhicheverApiAnswered() {
        // Metis /swap/v1/quote: priceImpactPct is a fraction string (0.05 = 5 %)
        assertEquals(5.0, JupiterImpact8730.pctPoints8730(JSONObject().put("priceImpactPct", "0.05")), 1e-9)
        assertEquals(0.01, JupiterImpact8730.pctPoints8730(JSONObject().put("priceImpactPct", "0.0001")), 1e-9)
        // /order: priceImpact is signed percentage points (-0.1 = -0.1 %); it wins over the deprecated fraction
        assertEquals(2.5, JupiterImpact8730.pctPoints8730(JSONObject().put("priceImpact", -2.5).put("priceImpactPct", "0.025")), 1e-9)
        assertEquals(0.1, JupiterImpact8730.pctPoints8730(JSONObject().put("priceImpact", "-0.1")), 1e-9)
        // absent stays absent (0.0 for SwapQuote, NaN for the cost reader)
        assertEquals(0.0, JupiterImpact8730.pctPoints8730(JSONObject()), 0.0)
        assertTrue(JupiterImpact8730.pctPoints8730(JSONObject(), Double.NaN).isNaN())
        assertTrue(JupiterImpact8730.pctPoints8730(JSONObject().put("priceImpactPct", "abc"), Double.NaN).isNaN())
        val api = File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertFalse("no raw read of the fraction is left", api.contains("parseDoubleSafely(json.opt(\"priceImpactPct\"))"))
        assertEquals(3, Regex(Regex.escape("JupiterImpact8730.pctPoints8730(json)")).findAll(api).count())
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/RoundTrip8418.kt").readText()
            .contains("JupiterImpact8730.pctPoints8730(json, absent = Double.NaN)"))
    }

    @Test fun aChainLabelWithNoPoolDoesNotProveAPoolBoughtBag() {
        val poolA = "ASkgoEzt6Pp6LPMwajqQh1QrXTAxZfdfCiAxnE3PBahW"
        // a position filled on a known pool: a chain-labelled mark that names no pool is unproven
        assertFalse(StopMarkConfirm8418.onBasis8418("PUMP_PORTAL_TRADE_WS_7278", "", poolA))
        assertTrue(StopMarkConfirm8418.offBasis8419(false, "PUMPSWAP_VAULT_WS_8018", "", poolA))
        // the same mark naming the bag's pool is the bag's price; an executable quote prices the bag wherever it routes
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMPSWAP_VAULT_WS_8018", poolA, poolA))
        assertTrue(StopMarkConfirm8418.onBasis8418("EXEC_QUOTE_HELD", "", poolA))
        // curve (mint-route) entries and unknown entry pools are unchanged
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMP_PORTAL_TRADE_WS_7278", "", "MINT_ROUTE:x"))
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMP_CURVE_RPC", "", ""))
    }
}
