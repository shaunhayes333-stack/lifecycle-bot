package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8524 — (1) the Cortex pending book is capped under HARD / CRITICAL heap pressure (non-selected oldest first, every
 * dropped decision retained as a MISSING outcome); (2) a forward label's basis is the price the gate ruled on (token-state
 * mark with its own observation time) and, past the adopt window, the first typed mark — so marks are no longer thrown
 * away as OBSERVED_ONLY_UNKNOWN_BASIS.
 */
class Aate8524HeapAndLabelBasisTest {
    @Test fun thePendingCapFollowsPressure() {
        assertEquals(1_200, Cortex7885.pendingCapFor8524(RetentionPolicy8136.Pressure.CRITICAL))
        assertEquals(2_400, Cortex7885.pendingCapFor8524(RetentionPolicy8136.Pressure.HARD))
        assertTrue(Cortex7885.pendingCapFor8524(RetentionPolicy8136.Pressure.SOFT) >= 8_000)
        assertTrue(Cortex7885.pendingCapFor8524(RetentionPolicy8136.Pressure.NORMAL) >= 8_000)
        // an empty / under-cap book evicts nothing
        assertEquals(0, Cortex7885.capPendingUnderPressure8524(1_200))
        assertEquals(0, Cortex7885.capPendingUnderPressure8524(0))
    }

    @Test fun theTrimAppliesTheCapOnlyUnderHardOrCriticalAndDropsNonSelectedOldestFirst() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val trim = src.substringAfter("fun trim8144(").substringBefore("fun pendingCapFor8524(")
        assertTrue(trim.contains("Pressure.HARD || p == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.CRITICAL"))
        assertTrue(trim.contains("capPendingUnderPressure8524(pendingCapFor8524(p))"))
        val cap = src.substringAfter("fun capPendingUnderPressure8524(").substringBefore("private val pressureEvictedPending8524")
        assertTrue(cap.contains("filter { it.value.selected8153.isBlank() }.sortedBy { it.value.atMs }"))
        assertTrue(cap.contains("retainMissingOutcome8521(\"\$key|\${pd.atMs}\", pd)"))
    }

    @Test fun aLabelBasisIsTheDecisionPriceWithItsObservationTimeAndLateMarksAreAdopted() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val observe = src.substringAfter("fun observe(ts: TokenState").substringBefore("lastSeenAt[key] = nowMs")
        // 8527: freeze price + source clock before any ancillary call; a later token update cannot erase the basis.
        assertTrue(observe.contains("val stateMark8527 = LabelMark8386(ts.mint, ts.lastPriceDex, ts.lastPrice, ts.lastPriceUpdate, nowMs, \"TOKEN_STATE\")"))
        // superseded by V5.0.8571 — the basis is the selected mark or the fresh chain mark that outranks it (preferChainEntry8571).
        assertTrue(observe.contains("it.basis8386 = LabelBasis8386(basisMark8571, it.entryMcap)"))
        assertTrue(observe.indexOf("val entryMark8527 =") < observe.indexOf("val liq ="))
        assertFalse(observe.contains("takeIf { m -> m.usd == px }"))
        val tick = src.substringAfter("if (adoptBasis8418(o.basis8386.mark.known(), o.rebased8076, age, mark8386)) {")
            .substringBefore("// Validate against the original typed basis")
        assertTrue(tick.contains("if (adoptLateBasis8524(o.basis8386.mark.known(), age, mark8386)) {"))
        // the late first price is recorded by the 8076 block AFTER the curve / step guards (8167 ordering pin), not here
        assertFalse(tick.contains("o.rebased8076 = true"))
        assertTrue(src.substringAfter("if (!o.rebased8076 && age >= REBASE_MIN_MS_8076) {").contains("o.lateEntry8521 = true"))
    }
}
