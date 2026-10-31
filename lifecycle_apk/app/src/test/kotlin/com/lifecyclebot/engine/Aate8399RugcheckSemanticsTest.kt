package com.lifecyclebot.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** V5.0.8399 — RugCheck's RISK score is normalised once into this app's SAFETY score; the cleanest launch is no longer a "confirmed rug". */
class Aate8399RugcheckSemanticsTest {
    private fun j(s: String) = JSONObject(s)

    @Test fun riskZeroIsTheCleanestTokenNotAConfirmedRug() {
        // superseded by V5.0.8471 — owner: RugCheck's number is low = bad token, high = good; the app safety equals it (was 0 -> 100, 5 -> 95, 30 -> 70, 60 -> 40: the number read as a risk).
        assertEquals(0, RugcheckScore8399.safety8399(j("""{"score_normalised":0}""")))
        assertEquals(5, RugcheckScore8399.safety8399(j("""{"score_normalised":5}""")))
        assertEquals(30, RugcheckScore8399.safety8399(j("""{"score_normalised":30}""")))
        assertEquals(60, RugcheckScore8399.safety8399(j("""{"score_normalised":60}""")))
    }

    @Test fun maximalRiskIsTheConfirmedRugAndOneIsNeverProduced() {
        assertEquals(99.0, RugcheckScore8399.CONFIRMED_RISK_8399, 0.0)
        assertEquals(100.0, RugcheckScore8399.RAW_SCORE_DIVISOR_8399, 0.0)       // raw 1000 ("Good" ceiling) -> risk 10
        // superseded by V5.0.8471 — owner: RugCheck's number is low = bad token, high = good; the app safety equals it: the top is the cleanest, the bottom (0 / 1) the confirmed floor (was 100 -> 0, 99 -> 0, 98 -> 2).
        assertEquals(100, RugcheckScore8399.safety8399(j("""{"score_normalised":100}""")))
        assertEquals(99, RugcheckScore8399.safety8399(j("""{"score_normalised":99}""")))
        assertEquals(0, RugcheckScore8399.safety8399(j("""{"score_normalised":1}""")))      // 1 stays the app's pending marker
        assertEquals(2, RugcheckScore8399.safety8399(j("""{"score_normalised":2}""")))
        for (r in 0..100) assertNotEquals("risk $r must not read as pending", 1, RugcheckScore8399.safety8399(r.toDouble()))
    }

    @Test fun unfetchedIsPendingNeverARefusal() {
        assertEquals(-1, RugcheckScore8399.safety8399(null as JSONObject?))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"risks":[]}""")))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score_normalised":-1}""")))
    }

    @Test fun rawScoreFallsBackThroughTheGoodBandAndRuggedIsHard() {
        // superseded by V5.0.8418 — the raw score is an uncapped sum; raw-only reads very risky (2), never the confirmed ceiling.
        // superseded by V5.0.8471 — a raw-only score has no stated range under the owner's reading: UNKNOWN (was 500 -> 95, 20000 -> 2).
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":500}""")))
        assertEquals(-1, RugcheckScore8399.safety8399(j("""{"score":20000}""")))
        assertTrue(RugcheckScore8399.ruggedFlag8399(j("""{"score_normalised":0,"rugged":true}""")))
        assertFalse(RugcheckScore8399.ruggedFlag8399(j("""{"score_normalised":0}""")))
    }

    @Test fun everyIngestionPointUsesTheOneNormaliser() {
        val root = java.io.File("src/main/kotlin/com/lifecyclebot/engine")
        val checker = root.resolve("TokenSafetyChecker.kt").readText()
        // superseded by V5.0.8418 — one report-level normaliser (rugged, mint echo, complete inventory) replaces the inline expression.
        assertTrue(checker.contains("val rcScore = RugcheckScore8399.reportSafety8418(rugcheck, mint)"))
        assertFalse(checker.contains("""rugcheck?.optInt("score_normalised""""))
        val pipeline = root.resolve("DataPipeline.kt").readText()
        assertTrue(pipeline.contains("return RugcheckScore8399.safety8399(rugData).let { if (it < 0) 50 else it }"))
        val scanner = root.resolve("SolanaMarketScanner.kt").readText()
        assertTrue(scanner.contains("val scoreNormalized = RugcheckScore8399.safety8399(json).let { if (it < 0) 50 else it }"))
        // No other production file parses the provider's score field directly.
        val offenders = java.io.File("src/main/kotlin/com/lifecyclebot").walkTopDown().filter { it.extension == "kt" && it.name != "RugcheckScore8399.kt" }
            .filter { f -> f.readText().let { it.contains("optInt(\"score_normalised\"") || it.contains("optDouble(\"score_normalised\"") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
