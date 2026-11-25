package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9000 — the thin-pool early-rug backstop guards against an LP pull; a coin on an active pump.fun curve has no LP to
 * pull, so it is left to its own stop and the -25 % catastrophic backstop. Graduated and off-pump coins keep the guard.
 */
class Aate9000CurveHasNoLpToPullTest {
    private val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test fun anActiveCurveIsLeftToItsOwnStop() {
        assertTrue(src.contains("val on = try { com.lifecyclebot.engine.truth.CurveReserves7951.realSolIfActive8046(mint) != null } catch (_: Throwable) { false }"))
        assertTrue(src.contains("if ((freshThinLiq || liqDrainRug) && !onActiveCurve9000(ts.mint)) {"))
        assertTrue(src.contains("THIN_LIQ_BACKSTOP_LEFT_TO_CURVE_STOP_9000"))
        // the guard still sells, venue-checked, for everything else, and the catastrophic backstop is untouched
        assertTrue(src.contains("doSell(ts, \"THIN_LIQ_EARLY_RUG_BACKSTOP_-\${if (freshThinLiq) \"10\" else \"15\"}\", wallet, walletSol)"))
        assertTrue(src.contains("exitReason = \"CATASTROPHIC_HARD_BACKSTOP_-25\","))
        // the curve check is the active-curve read: a graduated or stale curve answers null
        val curve = File("src/main/kotlin/com/lifecyclebot/engine/truth/CurveReserves7951.kt").readText()
        assertTrue(curve.contains("fun realSolIfActive8046(mint: String, nowMs: Long = System.currentTimeMillis()): Double? {"))
    }

    @Test fun wired() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 185. A curve has no LP to pull (9000)"))
    }
}
