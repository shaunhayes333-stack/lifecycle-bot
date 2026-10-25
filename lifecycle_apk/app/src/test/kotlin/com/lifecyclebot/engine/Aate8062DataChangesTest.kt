package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8062 — the five changes the 8059 data asked for. */
class Aate8062DataChangesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun provenCellsCompound() {
        // A proven cell's record (n33, mean +474%, SE ~104%) earns a positive compounding share.
        val f = LiveRiskPolicy7807.kellyFrac8057(474.0, 104.0 * 104.0 * 33.0, 33.0)
        assertTrue(f > 0.0 && f <= 0.30)
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("LiveEdgeGate7877.cellProven8060(c.n60, c.meanNet60Pct, c.stderr60Pct)"))
    }

    @Test fun expertCopyIsMeasuredEdge() {
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("SignalSourceProof7291.copyEdgeAtGap8062("))
        assertTrue(src("engine/BotService.kt").contains("SignalSourceProof7291.copyEdgeAtGap8062("))
    }

    @Test fun priceCoverage() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("CanonicalMarkPurpose6570.values().mapNotNull { CanonicalPriceMarkRegistry6522.get(mint, it) }"))
        assertTrue(f.contains("CurveReserves7951.activeExitObservation(mint, nowMs, MARK_MAX_AGE_MS_7731)"))
        val p = src("network/ParallelMarkFanout7088.kt")
        assertTrue(p.contains("\"dataSlice\""))
        assertTrue(p.contains("return \"helius_curve\""))
    }

    @Test fun buysDontWidenSlippage() {
        val j = src("network/JupiterApi.kt")
        assertTrue(j.contains("val buy8062 = inputMint == SOL_MINT"))
        assertTrue(j.contains("requestedBps.coerceIn(50, 500)"))
    }

    @Test fun decisionBookPersists() {
        assertTrue(src("engine/DecisionReasons8031.kt").contains("LearningPersistence.save(PERSIST_KEY_8062, body)"))
    }
}
