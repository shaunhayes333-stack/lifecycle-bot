package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.TrajectoryGrades8851
import com.lifecyclebot.engine.truth.TrajectoryVerdict8855
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8855 — one outcome per followed decision, revised at each read; the model's refusal and the watchlist order read
 * it per stage; the paper book cannot book a refuted entry as a win; a crypto price is stale against its own feed.
 */
class Aate8855TrajectoryVerdictTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aDecisionHoldsOneOutcomeUnderTheLanesStop() {
        // 15 % stop, 3 % round trip: the stop is 18 % under entry, a fall through 36 % was never filled at the stop
        var (y, stopped) = TrajectoryVerdict8855.pathOutcome8855(false, Double.NaN, 10.0, 15.0, 3.0)
        assertEquals(10.0, y, 1e-9); assertFalse(stopped)
        TrajectoryVerdict8855.pathOutcome8855(stopped, y, 250.0, 15.0, 3.0).let { y = it.first; stopped = it.second }
        assertEquals("a later read revises it", 250.0, y, 1e-9); assertFalse(stopped)
        TrajectoryVerdict8855.pathOutcome8855(stopped, y, -20.0, 15.0, 3.0).let { y = it.first; stopped = it.second }
        assertEquals(-18.0, y, 1e-9); assertTrue(stopped)
        TrajectoryVerdict8855.pathOutcome8855(stopped, y, 300.0, 15.0, 3.0).let { y = it.first; stopped = it.second }
        assertEquals("a stopped position never recovers", -18.0, y, 1e-9); assertTrue(stopped)
        assertEquals("a gap counts as it printed", -40.0, TrajectoryVerdict8855.pathOutcome8855(false, 5.0, -40.0, 15.0, 3.0).first, 1e-9)
        assertEquals("an unobserved read changes nothing", 5.0, TrajectoryVerdict8855.pathOutcome8855(false, 5.0, Double.NaN, 15.0, 3.0).first, 1e-9)
    }

    @Test fun aLaneStageDecidesTheRefusalOnceItHasItsOwnRecord() {
        TrajectoryVerdict8855.resetForTest8855()
        repeat(29) { TrajectoryVerdict8855.revise8855("MOONSHOT", "FRESH_LAUNCH", -5.0, Double.NaN, -18.0) }
        assertNull("29 refused: the lane record still decides", TrajectoryVerdict8855.authority8855("MOONSHOT", "FRESH_LAUNCH"))
        TrajectoryVerdict8855.revise8855("MOONSHOT", "FRESH_LAUNCH", -5.0, Double.NaN, -18.0)
        assertEquals(true, TrajectoryVerdict8855.authority8855("moonshot", "fresh_launch"))
        // the same lane's BASE coins the model predicted to lose went on to pay: the refusal stands down there
        repeat(40) { i -> TrajectoryVerdict8855.revise8855("MOONSHOT", "BASE_START", -5.0, Double.NaN, if (i % 4 == 0) 60.0 else -6.0) }
        assertEquals(false, TrajectoryVerdict8855.authority8855("MOONSHOT", "BASE_START"))
        // a decision is counted once: its first read lost, the 1-hour read revises the SAME decision
        TrajectoryVerdict8855.resetForTest8855()
        repeat(30) { TrajectoryVerdict8855.revise8855("CORE", "BASE_START", -2.0, Double.NaN, -5.0) }
        assertEquals(true, TrajectoryVerdict8855.authority8855("CORE", "BASE_START"))
        repeat(30) { TrajectoryVerdict8855.revise8855("CORE", "BASE_START", -2.0, -5.0, 40.0) }
        assertEquals("still 30 decisions, now at their later read", false, TrajectoryVerdict8855.authority8855("CORE", "BASE_START"))
        assertTrue(TrajectoryVerdict8855.statusLine8855().contains("CORE|BASE_START[refused=n30/+40.0%"))
        // kept predictions never feed the refused cohort; an unknown stage is never graded
        repeat(50) { TrajectoryVerdict8855.revise8855("EXPRESS", "DUMPING", +3.0, Double.NaN, -18.0) }
        assertNull(TrajectoryVerdict8855.authority8855("EXPRESS", "DUMPING"))
        repeat(50) { TrajectoryVerdict8855.revise8855("EXPRESS", "UNKNOWN", -3.0, Double.NaN, -18.0) }
        assertNull(TrajectoryVerdict8855.authority8855("EXPRESS", "UNKNOWN"))
        // the record survives a restart
        val saved = TrajectoryVerdict8855.encode8855()
        TrajectoryVerdict8855.resetForTest8855()
        assertNull(TrajectoryVerdict8855.authority8855("CORE", "BASE_START"))
        TrajectoryVerdict8855.restore8855(saved)
        assertEquals(false, TrajectoryVerdict8855.authority8855("CORE", "BASE_START"))
        TrajectoryVerdict8855.resetForTest8855()
    }

    @Test fun theWatchlistSpanFollowsTheStageRecord() {
        val means = mapOf("BASE_START" to 3.0, "FRESH_LAUNCH" to -20.0, "DUMPING" to -15.0)
        assertEquals(1.0, TrajectoryVerdict8855.shareOf8855(means, "base_start")!!, 1e-12)
        assertEquals(0.5, TrajectoryVerdict8855.shareOf8855(means, "DUMPING")!!, 1e-12)
        assertEquals(0.0, TrajectoryVerdict8855.shareOf8855(means, "FRESH_LAUNCH")!!, 1e-12)
        assertEquals("an ungraded stage sits in the middle", 0.5, TrajectoryVerdict8855.shareOf8855(means, "PEAK_EXHAUSTION")!!, 1e-12)
        assertNull("one graded stage ranks nothing: the hand boosts stand", TrajectoryVerdict8855.shareOf8855(mapOf("BASE_START" to 3.0), "BASE_START"))
        assertEquals(350.0 + 160.0, TrajectoryVerdict8855.PRIORITY_SPAN_8855, 0.0)
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("priority += if (stageShare8855 != null) stageShare8855 * com.lifecyclebot.engine.truth.TrajectoryVerdict8855.PRIORITY_SPAN_8855"))
        assertTrue(bot.contains("else freshBoost + sourceBoost"))
    }

    @Test fun everyObservedReadReachesTheRecord() {
        val book = TrajectoryGrades8851.Book()
        val done = HashSet<Long>()
        val at = 1_000_000L
        val seen = book.observe("MOONSHOT", "BASE_START", "S", "B", done, at, at, at + 61_000L, at + 62_000L, true, 4.0, false) { 30_000L }
        assertEquals(listOf(TrajectoryGrades8851.ONE_MIN), seen)
        assertTrue("the same read is never returned twice", book.observe("MOONSHOT", "BASE_START", "S", "B", done, at, at, at + 61_000L, at + 63_000L, true, 4.0, false) { 30_000L }.isEmpty())
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("TrajectoryVerdict8855.revise8855(o.lane, stage8851, o.pred8855, o.pathNet8855, now8855)"))
        assertTrue(lab.contains(".putString(\"trajectory_verdict_8855\", TrajectoryVerdict8855.encode8855())"))
        assertTrue(lab.contains("it.pred8855 = it.edge8537?.pred ?: Double.NaN"))
        assertTrue(src("engine/truth/EdgeModel8537.kt").contains("refuses8715 = p.isFinite() && (stageAuthority8855 ?: (o != null && refusalAuthority8715("))
        val rep = src("engine/PipelineHealthCollector.kt")
        assertTrue(rep.contains("Trajectory verdict (§8855): ") && rep.contains("Paper book repair (§8855): "))
    }

    @Test fun thePaperBookCannotBookARefutedEntry() {
        // entry 1.0, never marked; feed A proposed 95, feed B now reads 100: two feeds agree, the entry is what is wrong
        assertTrue(MemePaperTrader8544.entryBasisRefuted8855(false, 1.0, 100.0, 95.0, "A", "B"))
        assertFalse("a position a mark already confirmed keeps a real jump", MemePaperTrader8544.entryBasisRefuted8855(true, 1.0, 100.0, 95.0, "A", "B"))
        assertFalse("one feed cannot refute", MemePaperTrader8544.entryBasisRefuted8855(false, 1.0, 100.0, 95.0, "A", "A"))
        assertFalse("nothing held yet", MemePaperTrader8544.entryBasisRefuted8855(false, 1.0, 100.0, null, null, "B"))
        assertFalse("a plausible move", MemePaperTrader8544.entryBasisRefuted8855(false, 1.0, 5.0, 4.8, "A", "B"))
        // the 5.0.8853 CORE row (n10, mean +24,099,805.8 %) against a real losing and a real winning row
        assertTrue(MemePaperTrader8544.rowImpossible8855(doubleArrayOf(10.0, 4.0, 240_998_058.0, 240_998.058, 0.0, 0.0, 0.0)))
        assertFalse(MemePaperTrader8544.rowImpossible8855(doubleArrayOf(57.0, 12.0, -752.4, -0.751, 0.0, 0.0, 0.0)))
        assertFalse("a lane averaging +1,000 % is a great lane, not an error", MemePaperTrader8544.rowImpossible8855(doubleArrayOf(5.0, 5.0, 5_000.0, 0.5, 0.0, 0.0, 0.0)))
        val paper = src("engine/truth/MemePaperTrader8544.kt")
        assertTrue(paper.contains("repair8855()   // V5.0.8855") && paper.contains("{ void8855(p, nowMs); return }"))
    }

    @Test fun aCryptoPriceIsStaleAgainstItsOwnFeed() {
        assertEquals(60_000L, CandidateAdmission8136.feedPeriodMs8855("crypto_alt"))
        assertEquals(60_000L, CandidateAdmission8136.feedPeriodMs8855("CRYPTO_MOONSHOT"))
        assertEquals(0L, CandidateAdmission8136.feedPeriodMs8855("MOONSHOT"))
        assertTrue("the meme bar is unchanged", CandidateAdmission8136.priceStale8136(45_000L, false, true))
        assertFalse(CandidateAdmission8136.priceStale8136(45_000L, false, true, 60_000L))
        assertTrue(CandidateAdmission8136.priceStale8136(95_000L, false, true, 60_000L))
        assertTrue("an unknown age is stale for every feed", CandidateAdmission8136.priceStale8136(-1L, false, true, 60_000L))
        assertEquals(3, Regex("feedPeriodMs8855\\(c\\.lane\\)").findAll(src("engine/truth/CandidateAdmission8136.kt")).count())
    }
}
