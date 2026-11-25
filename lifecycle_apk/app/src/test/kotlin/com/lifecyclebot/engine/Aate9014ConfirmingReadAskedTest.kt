package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.QuietCurve8750
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9014 — a quiet curve's standing print needs its own curve account read to confirm it (8766, unchanged); when that
 * read is missing it is now asked for, so the label's next tick can price the coin instead of losing the read.
 */
class Aate9014ConfirmingReadAskedTest {
    @Test fun aMissingOrOlderReadIsAskedForAndADisagreeingOneIsNot() {
        assertTrue("no read held", QuietCurve8750.needsRead9014(null, 1_000L))
        assertTrue("read before the print", QuietCurve8750.needsRead9014(900L, 1_000L))
        assertFalse("a read at the print exists: a disagreement is a contradiction, not a missing read", QuietCurve8750.needsRead9014(1_000L, 1_000L))
        assertFalse(QuietCurve8750.needsRead9014(1_500L, 1_000L))
        // 8766's own confirmation is the rule it was
        assertTrue(QuietCurve8750.accountConfirms8766(1_000L, 1.00e-7, 1_200L, 1.005e-7))
        assertFalse(QuietCurve8750.accountConfirms8766(1_000L, 1.00e-7, 900L, 1.00e-7))
        assertFalse(QuietCurve8750.accountConfirms8766(1_000L, 1.00e-7, 1_200L, 1.05e-7))
    }

    @Test fun theRequestGoesThroughTheBoundedCurveRefreshQueue() {
        val q = File("src/main/kotlin/com/lifecyclebot/engine/truth/QuietCurve8750.kt").readText()
        val fn = q.substringAfter("fun mark8750(").substringBefore("served.incrementAndGet()")
        assertTrue(fn.contains("if (needsRead9014(acct?.observedAtMs, tp.second)) try { OnChainDepth8038.needCurve8039(mint, nowMs); asked9014.incrementAndGet() }"))
        assertTrue("nothing stands unconfirmed", fn.contains("unconfirmed8766.incrementAndGet()") && fn.trim().endsWith("}"))
        assertTrue(QuietCurve8750.statusLine8750().contains("readsAsked9014="))
        val d = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(d.contains("if (!approved && successfullyObservedAt[mint]?.let { nowMs - it in 0L until 60_000L } == true) return@synchronized"))
        assertTrue(d.contains("if (pending.size >= 2_000) return@synchronized"))
    }
}
