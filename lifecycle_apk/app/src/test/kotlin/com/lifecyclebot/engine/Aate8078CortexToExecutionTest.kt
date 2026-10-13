package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8078 — earned full Cortex authority reaches execution; provider errors are not absence; sizing diagnostics stay mode-true. */
class Aate8078CortexToExecutionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun fullAuthorityIsEarnedRevocableAndNeverPartial() {
        assertEquals(1.0, CortexScoreboard7885.matureAuthority8025(1.0, CortexScoreboard7885.FULL_AUTHORITY_MIN_LIFETIME_8078), 0.0)
        assertTrue(CortexScoreboard7885.matureAuthority8025(1.0, CortexScoreboard7885.FULL_AUTHORITY_MIN_LIFETIME_8078 - 1.0) < 1.0)
        assertTrue(CortexScoreboard7885.matureAuthority8025(0.99, 1e9) < 1.0)
        assertEquals(0.0, CortexScoreboard7885.matureAuthority8025(0.0, 1e9), 0.0)
    }

    @Test fun liveLossBookYieldsOnlyToFullCortexAuthority() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        val c = src("engine/cortex/Cortex7885.kt")
        // V5.0.8083 — the lane-wide book is a prior now; full authority still guards the candidate's own proven-losing setup.
        assertFalse(g.contains("LiveTruth8064.refusal8064("))
        assertTrue(g.contains("LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs)"))
        assertTrue(g.contains("Cortex7885.fullAuthorityOverrules8078(ts, l8079, r)"))
        val fn = c.substring(c.indexOf("fun fullAuthorityOverrules8078("), c.indexOf("fun firstSight8026(").let { if (it < 0) c.length else it })
        assertTrue(fn.contains("if (fraction < 1.0)"))
        assertTrue(fn.contains("softBlock(refusal, false)"))
        assertTrue(fn.contains("MayhemMode7943.liveRefusal(ts)"))
        assertTrue(fn.contains("SafetyTier.HARD_BLOCK"))
        assertFalse(fn.contains("firstSight8026("))
    }

    @Test fun ohlcvProviderErrorsAreNeverNegativeCachedAsAbsence() {
        val f = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(f.contains("if (!resp.isSuccessful) return Fetched6982(null, askedProvider = true, failed8078 = true)"))
        assertTrue(f.contains("if (body.isNullOrBlank()) return Fetched6982(null, askedProvider = true, failed8078 = true)"))
        assertTrue(f.contains("if (lookup6982.askedProvider && lookup6982.failed8078) {"))
        val branch = f.substring(f.indexOf("if (lookup6982.askedProvider && lookup6982.failed8078) {"), f.indexOf("} else if (lookup6982.askedProvider) {"))
        assertFalse(branch.contains("negativeCache[mint]"))
        assertTrue(f.contains("providerErrors8078="))
    }

    @Test fun earlySizingRefusalStampsItsOwnAccount() {
        val r = src("engine/truth/OrderSizeResolver6441.kt")
        val i = r.indexOf("lastResolution.set(blocked6758)")
        assertTrue(i > 0)
        assertTrue(r.indexOf("noteResolveAccount7217(paperMode", i) in i..(i + 400))
    }
}
