package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 — 8418 phone run: PROJECT_SNIPER discovered=0 / trafficSeen=false with 22 hunter claims and residents; and
 * MARK_QUOTE_7060_UNAVAILABLE_NO_TOKEN_STATE = 2,700 asks for paper mints that hold no TokenState.
 */
class Aate8418SniperPoolAndMarkTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun theSnipersOwnHuntPickIsItsDiscovery() {
        assertTrue(LaneDispatch8418.poolAtHunt8418("PROJECT_SNIPER"))
        assertTrue(LaneDispatch8418.poolAtHunt8418(" project_sniper "))
        assertFalse(LaneDispatch8418.poolAtHunt8418("MOONSHOT"))
        // published right where the hunter claims the coin and files it in the lane's resident book
        val h = src("engine/market/LaneHunter7297.kt")
        val pub = h.indexOf("SpecialistCandidateBooks7803.publishHunt(lane, r.mint, r.symbol, SOURCE_PREFIX + lane)")
        val pool = h.indexOf("LaneDispatch8418.notePoolAtHunt8418(lane, r.mint)")
        assertTrue(pub > 0 && pool > pub && pool - pub < 300)
        // the same causal id the sheet uses (mint:candidateVersion), so the later sheet stamp dedupes
        assertTrue(src("engine/LaneDispatch8418.kt").contains("ToolkitSignalSheet.recordDeskStage(lane.trim().uppercase(), \"POOL\", \"\$mint:\$v\")"))
    }

    @Test fun aMintWithNoTokenStateIsNotAskedForAQuote() {
        val held = setOf("HeldMint")
        assertFalse(CanonicalCapitalAuthority6450.quoteAskable8418("PaperOnly", { it in held }))
        assertTrue(CanonicalCapitalAuthority6450.quoteAskable8418("HeldMint", { it in held }))
        assertTrue("no predicate installed: ask as before", CanonicalCapitalAuthority6450.quoteAskable8418("X", null))
        assertTrue("a throwing predicate never hides a quote", CanonicalCapitalAuthority6450.quoteAskable8418("X", { throw IllegalStateException() }))
        assertTrue(src("engine/BotService.kt").contains("installMarkQuoteStatePredicate8418 { status.tokens.containsKey(it) }"))
    }
}
