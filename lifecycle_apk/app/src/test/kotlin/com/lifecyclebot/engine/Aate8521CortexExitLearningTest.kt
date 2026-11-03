package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexExitTruth8521
import com.lifecyclebot.engine.cortex.LearningExitTruth8153
import com.lifecyclebot.engine.truth.LabelMark8386
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8521CortexExitLearningTest {
    private val due = 10_000_000L
    private val grace = 1_200_000L
    private val mint = "A11111111111111111111111111111111"

    private class Store {
        var raw: String? = null
        var fail = false
        var loseReply = false
        val receipts = HashSet<String>()
        fun commit(key: String, next: String): CortexExitTruth8521.Receipt = when {
            fail -> CortexExitTruth8521.Receipt.RETRY
            key in receipts -> CortexExitTruth8521.Receipt.ALREADY_COMMITTED
            else -> {
                raw = next; receipts.add(key)
                if (loseReply) { loseReply = false; error("commit reply lost") }
                CortexExitTruth8521.Receipt.COMMITTED
            }
        }
    }

    private fun pending(mode: String = "LIVE", pid: String = "p1") = CortexExitTruth8521.Pending(
        "$mode|$pid", mint, "MOONSHOT", mode, 1.0,
        listOf(LearningExitTruth8153.Slice8153(1.0, 1.2, 2.0, due - 120_000L)), due,
    )
    private fun mark(at: Long = due + 1L, px: Double = 4.0, source: String = "CURVE_FRAME") =
        LabelMark8386(mint, "pool", px, at, at, source, independentBasis = true)

    @Test fun confirmedBankedReturnLearnsImmediatelyAndPendingSurvivesRestart() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending()))
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(20.0, q.book("LIVE|MOONSHOT")!!.sumTotal, 1e-9)
        assertEquals(0, q.book("LIVE|MOONSHOT")!!.regretN)
        val restored = CortexExitTruth8521(load = { store.raw }, commit = store::commit).also { it.restore(store.raw!!) }
        assertEquals(q.pending(), restored.pending())
        assertEquals(1, restored.book("LIVE|MOONSHOT")!!.n)
        assertTrue(restored.complete("LIVE|p1", mark(), due + 1L, grace))
        assertEquals(1, restored.book("LIVE|MOONSHOT")!!.n)
        assertEquals(1, restored.book("LIVE|MOONSHOT")!!.regretN)
        assertEquals(120.0, restored.book("LIVE|MOONSHOT")!!.sumRegret, 1e-9)
    }

    @Test fun failedEnrollmentCannotAckOrMutateAndRetryTeachesOnce() {
        val store = Store().also { it.fail = true }
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertFalse(q.freeze(pending()))
        assertTrue(q.pending().isEmpty())
        assertNull(q.book("LIVE|MOONSHOT"))
        assertNull(store.raw)
        store.fail = false
        assertTrue(q.freeze(pending()))
        assertTrue(q.freeze(pending()))
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(1, store.receipts.size)
    }

    @Test fun failedFutureGradeKeepsQueueAndBankedCountUntilDurableRetry() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending()))
        store.fail = true
        assertFalse(q.complete("LIVE|p1", mark(), due + 1, grace))
        assertEquals(1, q.pending().size)
        assertEquals(0, q.book("LIVE|MOONSHOT")!!.regretN)
        store.fail = false
        assertTrue(q.complete("LIVE|p1", mark(), due + 1, grace))
        assertTrue(q.complete("LIVE|p1", mark(), due + 1, grace))
        assertTrue(q.freeze(pending())) // a finalized event replay after completion
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.regretN)
        assertTrue(q.pending().isEmpty())
        val restarted = CortexExitTruth8521(load = { store.raw }, commit = store::commit).also { it.restore(store.raw!!) }
        assertTrue(restarted.freeze(pending()))
        assertEquals(1, restarted.book("LIVE|MOONSHOT")!!.n)
        assertTrue(restarted.pending().isEmpty())
    }

    @Test fun samePositionIdCannotMixLiveAndPaperReturns() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending("LIVE")))
        assertTrue(q.freeze(pending("PAPER")))
        assertEquals(2, q.pending().size)
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(1, q.book("PAPER|MOONSHOT")!!.n)
        assertTrue(q.complete("LIVE|p1", mark(), due + 1, grace))
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.regretN)
        assertEquals(0, q.book("PAPER|MOONSHOT")!!.regretN)
    }

    @Test fun stalePreHorizonWrongAssetUnknownAndFutureMarksNeverGrade() {
        fun px(m: LabelMark8386?, now: Long = due + grace) = CortexExitTruth8521.horizonPrice(mint, due, m, now, grace)
        assertNull(px(null))
        assertNull(px(mark(due - 1)))
        assertNull(px(mark(due + grace + 1), due + grace + 1))
        assertNull(px(mark().copy(mint = "OTHER")))
        assertNull(px(mark().copy(source = "UNKNOWN")))
        assertNull(px(mark().copy(units = "SOL_PER_TOKEN")))
        assertNull(px(mark(due + 2), due + 1))
        assertEquals(4.0, px(mark())!!, 0.0)
        assertEquals(4.0, px(mark(source = "TAPE_SWAP_8475"))!!, 0.0)
        assertEquals(4.0, px(mark(source = "OFFWATCH"))!!, 0.0)
    }

    @Test fun missingHorizonDoesNotInventAZeroOrRecountBankedPnl() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending()))
        assertFalse(q.complete("LIVE|p1", null, due, grace))
        assertTrue(q.complete("LIVE|p1", null, due + grace + 1L, grace))
        assertTrue(q.pending().isEmpty())
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(0, q.book("LIVE|MOONSHOT")!!.regretN)
        assertTrue(q.book("LIVE|MOONSHOT")!!.meanRegret().isNaN())
    }

    @Test fun boundedQueueDoesNotEvictAcknowledgedPendingOutcomes() {
        val store = Store()
        val q = CortexExitTruth8521(capacity = 1, load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending(pid = "first")))
        assertFalse(q.freeze(pending(pid = "second")))
        assertEquals("LIVE|first", q.pending().single().key)
        assertTrue(q.complete("LIVE|first", mark(), due + 1, grace))
        assertTrue(q.freeze(pending(pid = "second")))
        assertEquals(2, q.book("LIVE|MOONSHOT")!!.n)
    }

    @Test fun historicalFinalityLearnsActualProceedsOnceWithoutAnImpossibleFutureMark() {
        val store = Store()
        val q = CortexExitTruth8521(capacity = 1, load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending(pid = "current")))
        // Full current-outcome capacity must not discard exact historical returns.
        assertTrue(q.freeze(pending(pid = "historical"), trackCounterfactual = false))
        assertEquals("LIVE|current", q.pending().single().key)
        assertEquals(2, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(40.0, q.book("LIVE|MOONSHOT")!!.sumTotal, 1e-9)
        assertEquals(0, q.book("LIVE|MOONSHOT")!!.regretN)
        assertTrue(q.freeze(pending(pid = "historical"), trackCounterfactual = false))
        val restored = CortexExitTruth8521(capacity = 1, load = { store.raw }, commit = store::commit)
            .also { it.restore(store.raw!!) }
        assertTrue(restored.freeze(pending(pid = "historical"), trackCounterfactual = false))
        assertEquals(2, restored.book("LIVE|MOONSHOT")!!.n)
        assertEquals("LIVE|current", restored.pending().single().key)
        assertEquals(0, restored.book("LIVE|MOONSHOT")!!.regretN)
    }

    @Test fun legacyRealizedBooksAreMigratedWithoutSyntheticPendingMarks() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        q.restoreLegacyBooks(JSONObject().put("LIVE|MOONSHOT", "6,-20.0,0,0.0,0.0,0"))
        assertEquals(6, q.book("LIVE|MOONSHOT")!!.n)
        assertTrue(q.pending().isEmpty())
        assertTrue(q.freeze(pending()))
        val restored = CortexExitTruth8521(load = { store.raw }, commit = store::commit).also { it.restore(store.raw!!) }
        assertEquals(7, restored.book("LIVE|MOONSHOT")!!.n)
        assertEquals(0, restored.book("LIVE|MOONSHOT")!!.regretN)
    }

    @Test fun durableConsumerAndSharedSourceAreWiredBeforeFallbackRetirement() {
        val base = File("src/main/kotlin/com/lifecyclebot/engine")
        val exit = File(base, "cortex/CortexExit7897.kt").readText()
        assertTrue(exit.contains("ForwardReturnLabeler7731.typedMark8386(mint, priceFor, nowMs)"))
        assertTrue(exit.contains("KeylessMarks8359.noteLabelDue8418(mint, dueMs + GRADE_GRACE_MS, nowMs)"))
        assertFalse(exit.contains("jupiterBatchPrices7737"))
        val close = exit.substringAfter("fun onCanonicalClose8082(").substringBefore("private const val TRUTH_KEY_8521")
        assertTrue(close.indexOf("if (!onClosePartials8153(env)) return false") in 0 until close.indexOf("commitOnce8090"))
        val bus = File(base, "truth/CanonicalFinalizedTradeBus6464.kt").readText()
        assertTrue(bus.contains("if (exitTruthSaved8521) try { com.lifecyclebot.engine.TailBag8034.onClose8034(env)"))
    }

    @Test fun committedButLostResponseReloadsBeforeAcknowledgementOrNextWrite() {
        val store = Store().also { it.loseReply = true }
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertTrue(runCatching { q.freeze(pending()) }.isFailure)
        assertTrue(q.pending().isEmpty())
        assertTrue(q.freeze(pending(pid = "unrelated"))) // cannot overwrite the first committed row with stale memory
        assertTrue(q.freeze(pending())) // ALREADY_COMMITTED restores the original, not an empty local book
        assertEquals(2, q.pending().size)
        assertEquals(2, q.book("LIVE|MOONSHOT")!!.n)
        store.loseReply = true
        assertTrue(runCatching { q.complete("LIVE|p1", mark(), due + 1, grace) }.isFailure)
        assertTrue(q.complete("LIVE|p1", mark(), due + 1, grace))
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.regretN)
        assertTrue(q.freeze(pending(pid = "second")))
        assertEquals(3, q.book("LIVE|MOONSHOT")!!.n)
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.regretN)
    }

    @Test fun delayedDeliveryOfHistoricalInWindowObservationIsNotFutureLeakage() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertTrue(q.freeze(pending()))
        val afterGrace = due + grace + 500_000L
        assertTrue(q.complete("LIVE|p1", mark(due + 50_000L).copy(readAtMs = afterGrace), afterGrace, grace))
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.regretN)
        assertEquals(1, q.book("LIVE|MOONSHOT")!!.n)
        assertNull(CortexExitTruth8521.horizonPrice(mint, due, mark(afterGrace), afterGrace, grace))
    }

    @Test fun invalidModeOrCostCannotCreateRealizedAuthority() {
        val store = Store()
        val q = CortexExitTruth8521(load = { store.raw }, commit = store::commit)
        assertFalse(q.freeze(pending().copy(mode = "UNKNOWN")))
        assertFalse(q.freeze(pending().copy(costSol = Double.NaN)))
        assertFalse(q.freeze(pending().copy(costSol = 0.0)))
        assertTrue(q.pending().isEmpty())
        assertTrue(store.receipts.isEmpty())
    }

    @Test fun unavailableOptionalSlicesDoNotStarveValidRealizedReturnAfterDurableReceipt() {
        var stored: String? = null
        fun record(result: CortexExitTruth8521.Receipt): Boolean = CortexExitTruth8521.recordUnavailableSlices(
            "LIVE", "p1", "confirmed-signature", mint, "EXPRESS", "LIVE_FINALIZED", 0.012, 12.0,
        ) { key, raw ->
            assertEquals("cortex_exit_no_slices_8521_LIVE_p1", key)
            stored = raw
            result
        }
        assertFalse(record(CortexExitTruth8521.Receipt.RETRY))
        assertTrue(record(CortexExitTruth8521.Receipt.COMMITTED))
        assertTrue(record(CortexExitTruth8521.Receipt.ALREADY_COMMITTED))
        val row = JSONObject(stored!!)
        assertEquals(0.012, row.getDouble("netSol"), 0.0)
        assertEquals(12.0, row.getDouble("returnPct"), 0.0)
        assertEquals("ACTUAL_WHOLE_POSITION_REALIZED", row.getString("target"))
        assertEquals("NO_CANONICAL_SELL_SLICES", row.getString("regretUnavailable"))
        assertFalse(row.has("costSol")); assertFalse(row.has("slices"))
        val exit = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CortexExit7897.kt").readText()
        val partials = exit.substringAfter("fun onClosePartials8153(").substringBefore("private fun gradeTruth8153(")
        assertTrue(partials.indexOf("EconomicEventSchema6464.isReady8521()") in 0 until partials.indexOf("EconomicEventSchema6464.snapshot()"))
        assertTrue(partials.contains("EXIT_TRUTH_SNAPSHOT_RETRY_8521\"); return false"))
        assertTrue(partials.contains("return CortexExitTruth8521.recordUnavailableSlices"))
    }
}
