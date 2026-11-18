package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.TrajectoryGrades8851
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exact source-timestamp labels, qualified 1h/4h/24h follow-up and coherent live WS facts. */
class Aate8851MultiHorizonAndWsDataTest {
    private val at = 1_800_000_000_000L
    private val t = TrajectoryGrades8851

    @Test fun oneTokenHasIndependentOneFiveFifteenMinuteGrades() {
        val book = TrajectoryGrades8851.Book()
        val done = mutableSetOf<Long>()
        fun read(h: Long, ret: Double, now: Long = at + h) = book.observe(
            "MOONSHOT", "EXPANDING", "EARLY_FLOW_RECLAIM", "IGNITION", done,
            at, at + 5_000L, at + h, now, true, ret, false,
            ForwardReturnLabeler7731::graceFor7946)
        read(t.ONE_MIN, 12.0)
        read(t.ONE_MIN, 9_999.0) // a repeated labeler tick does not grade the same episode twice
        read(t.FIVE_MIN, -2.0)
        read(t.FIFTEEN_MIN, -8.0)
        assertEquals(setOf(t.ONE_MIN, t.FIVE_MIN, t.FIFTEEN_MIN), done)
        assertEquals(3L, book.observed)
        assertEquals(0L, book.unavailable)
        assertTrue(book.line().contains("graded1m=1 graded5m=1 graded15m=1"))
        assertTrue(book.line().contains("qualified1h=0"))
        assertEquals(0.0, book.adjustment("MOONSHOT", "EXPANDING", "EARLY_FLOW_RECLAIM", "IGNITION"), 0.0)
        val restored = TrajectoryGrades8851.Book().also { it.restore(book.encode()) }
        assertEquals(book.line(), restored.line())
    }

    @Test fun qualifiedSurvivorGetsOneHourFourHourAndDayChecksWithoutPollutingUniversalCohort() {
        val book = TrajectoryGrades8851.Book()
        val done = mutableSetOf<Long>()
        fun mark(h: Long, qualified: Boolean) = book.observe("SHITCOIN", "EXPANDING", "BREAKOUT_RUNNER", "BASE",
            done, at, at + 5_000L, at + h, at + h, true, 34.0, qualified,
            ForwardReturnLabeler7731::graceFor7946)
        mark(t.ONE_MIN, false)
        mark(t.FIVE_MIN, false)
        mark(t.FIFTEEN_MIN, false)
        mark(t.ONE_HOUR, false)
        assertFalse(t.ONE_HOUR in done) // unqualified coins do not create biased long-horizon labels
        mark(t.ONE_HOUR, true)
        mark(t.FOUR_HOURS, true)
        mark(t.TWENTY_FOUR_HOURS, true)
        assertTrue(t.ONE_HOUR in done)
        assertTrue(t.FOUR_HOURS in done)
        assertTrue(t.TWENTY_FOUR_HOURS in done)
        assertTrue(book.line().contains("qualified1h=1 qualified4h=1 qualified24h=1"))
        assertEquals(0L, book.unavailable) // no fake -100% labels from the unqualified path
    }

    @Test fun aLateObservationCannotBeAppliedToAnEarlierTimestamp() {
        val book = TrajectoryGrades8851.Book(); val done = mutableSetOf<Long>()
        val late = at + t.FIVE_MIN + 90_000L
        book.observe("CORE", "EXPANDING", "REACCUMULATION", "BASE", done, at,
            at + 5_000L, late, late, true, 500.0, false,
            ForwardReturnLabeler7731::graceFor7946)
        assertTrue(t.ONE_MIN in done)
        assertTrue(t.FIVE_MIN in done) // this 6.5m mark is inside the distinct 5m+2.5m window
        assertEquals(1L, book.observed)
        assertEquals(1L, book.unavailable)
        assertFalse(t.FIFTEEN_MIN in done)
    }

    @Test fun aFreshLongHorizonNeverBackfillsTheOldThirtyMinuteCheck() {
        val base = at
        for (h in listOf(t.ONE_HOUR, t.FOUR_HOURS, t.TWENTY_FOUR_HOURS)) {
            val now = base + h + 5_000L
            val expected = base + h + ForwardReturnLabeler7731.graceFor7946(h)
            assertNull(ForwardReturnLabeler7731.horizonDeadline8650(base, now, true, true, true, true, t.FIFTEEN_MIN))
            assertEquals(expected, ForwardReturnLabeler7731.horizonDeadline8650(
                base, now, true, true, true, true, t.FIFTEEN_MIN, h))
        }
    }

    @Test fun runnerTargetCompletionIsFifteenMinutesNotTheOldHour() {
        val runner = t.FIFTEEN_MIN
        val scalp = t.FIVE_MIN
        assertTrue(ForwardReturnLabeler7731.interimFiveMinute8851(runner))
        assertFalse(ForwardReturnLabeler7731.interimFiveMinute8851(scalp))
        assertEquals(runner, ForwardReturnLabeler7731.completionHorizon8851(runner))
        assertEquals(t.ONE_HOUR, ForwardReturnLabeler7731.completionHorizon8851(scalp))
        assertEquals(at + runner + ForwardReturnLabeler7731.graceFor7946(runner),
            ForwardReturnLabeler7731.completionDeadline8851(at, runner))
        assertEquals(at + t.ONE_HOUR + ForwardReturnLabeler7731.graceFor7946(t.ONE_HOUR),
            ForwardReturnLabeler7731.completionDeadline8851(at, scalp))
    }

    @Test fun oldFiveMinuteRunnerStageAndRejectedWsMapOverwriteCannotRecur() {
        val labeler = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val first = labeler.substringAfter("private fun bookSixty7944(").substringBefore("private fun timingHorizons8528(")
        assertTrue(first.indexOf("interimFiveMinute8851(o.targetHorizon8521)") in 0 until first.indexOf("stageForwardOutcome8404(o"))
        val source = File("src/main/kotlin/com/lifecyclebot/engine/DataOrchestrator.kt").readText()
        val callback = source.substringAfter("private fun startDexScreenerWebSocket()")
            .substringBefore("private fun", missingDelimiterValue = source)
        assertTrue(callback.contains("val acceptedPrice8851 ="))
        assertTrue(callback.contains("if (acceptedPrice8851) try {"))
        assertTrue(callback.contains("LocalCandleSynthesis7055.note(ts, ts.lastPrice"))
        assertTrue(callback.contains("if (acceptedPrice8851) try {\n                    com.lifecyclebot.engine.market.MarketSweep7297.recordRealtime7777"))
        val binner = File("src/main/kotlin/com/lifecyclebot/engine/truth/LocalCandleSynthesis7055.kt").readText()
        assertTrue(binner.contains("MarkBars7948.note7948(mint, priceUsd, atMs)"))
        assertTrue(callback.contains("if (acceptedPrice8851) {"))
        assertFalse(callback.contains("ts.tokenMap.priceUsd = priceUsd"))
    }

    @Test fun multiTimestampRankingCannotTurnPaperLabelsIntoTradeAuthority() {
        val b = TrajectoryGrades8851.Book()
        repeat(35) {
            b.recordForTest("CORE", "EXPANDING", "REACCUMULATION", "BASE", t.FIVE_MIN, 6.0)
            b.recordForTest("CORE", "EXPANDING", "REACCUMULATION", "BASE", t.FIFTEEN_MIN, 9.0)
            b.recordForTest("CASHGEN", "POST_PUMP_FADE", "QUICK_FLIP", "CHASE", t.FIVE_MIN, -8.0)
            b.recordForTest("CASHGEN", "POST_PUMP_FADE", "QUICK_FLIP", "CHASE", t.FIFTEEN_MIN, -16.0)
        }
        assertEquals(2.0, b.adjustment("CORE", "EXPANDING", "REACCUMULATION", "BASE"), 0.0)
        assertEquals(-2.0, b.adjustment("CASHGEN", "POST_PUMP_FADE", "QUICK_FLIP", "CHASE"), 0.0)
        assertEquals(0.0, b.adjustment("CASHGEN", "EXPANDING", "QUICK_FLIP", "CHASE"), 0.0)
        assertEquals(0.0, b.adjustment("CORE", "EXPANDING", "UNTRAINED", "BASE"), 0.0)
    }

    @Test fun verifiedFirmingRunnerCanOutliveAScalpButNotItsReversal() {
        val hold = LifecycleEvidence8189::firmingRunner8851
        assertTrue(hold(true, true, 35.0, 32.0, 800L))
        assertFalse(hold(true, false, 35.0, 32.0, 800L)) // structure failed
        assertFalse(hold(false, true, 35.0, 32.0, 800L)) // never sealed as runner
        assertFalse(hold(true, true, 35.0, -1.0, 800L)) // loser remains unheld
        assertFalse(hold(true, true, 35.0, 15.0, 800L)) // gave back the verified lift
        assertFalse(hold(true, true, 35.0, 32.0, 10_000L)) // stale, not a live continuation
        assertFalse(hold(true, true, 12.0, 12.0, 800L)) // no verified early runner peak
        assertEquals(LifecycleEvidence8189.Evidence8189.UNPROVEN,
            LifecycleEvidence8189.evidence8189(true, false, 35.0))
    }

    @Test fun qualifiedNativeCoreConsensusCanOwnInsteadOfBeingShadowed() {
        fun owner(reason: String = "CORE_ENSEMBLE_MOONSHOT+QUALITY", age: Long = 800L,
                  eligible: Boolean = true, namedSetup: Boolean = true, allowed: Boolean = true) =
            CoreNativeDispatch8527.nativeConsensusOwner8851(true, age, eligible, true, reason, namedSetup, allowed)
        assertTrue(owner())
        assertFalse(owner(reason = "CORE_GENERALIST_NO_SPECIALIST_FIT")) // independent fallback needs its own election
        assertFalse(owner(age = 7_000L)) // stale snapshot cannot elect
        assertFalse(owner(eligible = false))
        assertFalse(owner(namedSetup = false))
        assertFalse(owner(allowed = false))
        assertFalse(CoreNativeDispatch8527.nativeConsensusOwner8851(false, 800L, true, true,
            "CORE_ENSEMBLE_MOONSHOT+QUALITY", true, true))
        val service = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val election = service.substringAfter("val nativeCoreOwner8851 =")
            .substringBefore("val scoreForPivot4524")
        assertTrue(election.contains("nativeCoreOwner8851 -> \"CORE\""))
        assertTrue(election.contains("LaneEntryContract6342.isLaneIdentityEligible7252(ts, \"CORE\")"))
    }
}
