package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SwapQuote
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8735 — an explore-approved unread LP is accepted at the route check too; unlocked and thin pools still refuse. */
class Aate8735ExploreRouteTest {
    private val now = 1_800_000_000_000L
    private val mint = "Route8735AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun report(vararg pools: LiquidityOwnership8522.Pool, unlocked: Boolean = false) = SafetyReport(
        checkedAt = now - 1_000L, evaluatedForPaper8522 = false,
        mintAuthorityDisabled = true, freezeAuthorityDisabled = true,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), unlocked, true),
    )
    private fun route(pool: String) = BuyRouteSafety8522.jupiter(SwapQuote(
        raw = JSONObject().put("routePlan", JSONArray().put(JSONObject().put("swapInfo",
            JSONObject().put("inputMint", JupiterApi.SOL_MINT).put("outputMint", mint).put("ammKey", pool)))),
        outAmount = 1_000_000L, priceImpactPct = 0.1, inputMint = JupiterApi.SOL_MINT,
        outputMint = mint, inAmount = 30_000_000L, router = "metis", isRfqRoute = false, receivedAtMs8521 = now - 500L,
    ))
    private fun assess(r: SafetyReport, pool: String, explore: Boolean) =
        BuyRouteSafety8522.assess(mint, r, route(pool), false, "", false, now, exploreLp8735 = explore)

    @Test fun anUnreadPoolPassesOnlyOnTheExploreVerdict() {
        val r = report()   // the selected pool has no ownership proof: PENDING
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(r, "fresh", explore = false).status)
        val ok = assess(r, "fresh", explore = true)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, ok.status)
        assertEquals("LP_UNREAD_EXPLORE_8733", ok.reason)
    }

    @Test fun unlockedAndThinPoolsStillRefuse() {
        assertEquals(LiquidityOwnership8522.Status.BLOCKED,
            assess(report(LiquidityOwnership8522.Pool("p", "raydium", 10.0, 20_000.0)), "p", explore = true).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(report(unlocked = true), "p", explore = true).status)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BuyRouteSafety8522.kt").readText()
        assertTrue(src.contains("exploreLp8735 = explore8735)"))
        assertTrue(src.contains("if (!(exploreLp8735 && proof.status == LiquidityOwnership8522.Status.PENDING)) return proof"))
    }
}
