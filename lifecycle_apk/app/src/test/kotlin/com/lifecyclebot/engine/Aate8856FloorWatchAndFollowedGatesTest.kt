package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FloorWatch8856
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.engine.truth.TrajectoryVerdict8855
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8856 — probation watches the floor (the cross seats a coin and heads the cycle), every gate's record is graded on
 * the followed clock, and the watched launch rule earns its proof on launches born after it was named.
 */
class Aate8856FloorWatchAndFollowedGatesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val min = 60_000L

    @Test fun theLivePrintAtTheFloorIsTheCross() {
        assertEquals(5_000.0, FloorWatch8856.FLOOR_CAP_USD_8856, 0.0)
        assertTrue(FloorWatch8856.crosses8856(0L, 5_000.0, true))
        assertFalse(FloorWatch8856.crosses8856(0L, 4_999.0, true))
        assertFalse("a coin crosses once", FloorWatch8856.crosses8856(1L, 9_000.0, true))
        assertFalse("a backfilled print never crosses", FloorWatch8856.crosses8856(0L, 9_000.0, false))
        assertFalse(FloorWatch8856.crosses8856(0L, Double.NaN, true))
    }

    @Test fun aCrosserKeepsItsSeatWhileItHoldsTheFloorAndPrints() {
        val now = 100 * min
        assertTrue(FloorWatch8856.holds8856(1L, 7_000.0, now - 10 * min, now))
        assertFalse("silent for a whole label", FloorWatch8856.holds8856(1L, 7_000.0, now - 16 * min, now))
        assertFalse("back under the floor", FloorWatch8856.holds8856(1L, 4_000.0, now - 1_000L, now))
        assertFalse("never crossed", FloorWatch8856.holds8856(0L, 7_000.0, now - 1_000L, now))
        FloorWatch8856.resetForTest8856()
        FloorWatch8856.followForTest8856("OLD", now - 30 * min, 8_000.0, now - 1_000L, crossedAtMs = now - 20 * min, dueUntilMs = now + min)
        FloorWatch8856.followForTest8856("NEW", now - 5 * min, 6_000.0, now - 1_000L, crossedAtMs = now - 2 * min, dueUntilMs = now + min)
        FloorWatch8856.followForTest8856("SPENT", now - 30 * min, 6_000.0, now - 1_000L, crossedAtMs = now - 9 * min, dueUntilMs = now - 1L)
        FloorWatch8856.followForTest8856("UNDER", now - 5 * min, 3_000.0, now - 1_000L, crossedAtMs = 0L)
        assertEquals("newest cross first, only live claims", listOf("NEW", "OLD"),
            FloorWatch8856.dueCrossers8856(listOf("OLD", "UNDER", "SPENT", "NEW", "ABSENT"), now))
        assertTrue(FloorWatch8856.holding8856("OLD", now) && !FloorWatch8856.holding8856("UNDER", now))
        assertTrue(FloorWatch8856.underFloor8856("UNDER", now) && !FloorWatch8856.underFloor8856("NEW", now))
        assertFalse("past its first hour it is no longer followed", FloorWatch8856.underFloor8856("UNDER", now + 61 * min))
        assertEquals(3.0, FloorWatch8856.quantile8856(listOf(5.0, 1.0, 3.0), 0.5)!!, 0.0)
        assertNull(FloorWatch8856.quantile8856(emptyList(), 0.5))
        FloorWatch8856.resetForTest8856()
    }

    @Test fun aCrosserTakesTheSeatOfAnUnderFloorResident() {
        val now = 100 * min
        fun row(m: String, addedMin: Long, evals: Int, open: Boolean = false) = WatchlistEviction8398.Row(m, "S", now - addedMin * min, now - 1_000L, evals, open)
        val rows = listOf(row("OPEN", 90, 40, open = true), row("HOLDER", 80, 40), row("WAITING", 70, 40), row("UNDER_A", 2, 0), row("UNDER_B", 4, 1),
            row("VETERAN", 60, 40), row("UNDECIDED", 1, 0))
        fun pick(under: Set<String>) = WatchlistEviction8398.seatVictim8856(rows, now, "INCOMING", { it in under }, { it == "HOLDER" }, { it == "WAITING" })?.mint
        assertEquals("the oldest under-floor resident, however recently it was touched", "UNDER_B", pick(setOf("UNDER_A", "UNDER_B")))
        assertEquals("none under the floor: the oldest decided resident", "VETERAN", pick(emptySet()))
        val protectedOnly = listOf(row("OPEN", 90, 40, open = true), row("HOLDER", 80, 40), row("UNDECIDED", 1, 0))
        assertNull(WatchlistEviction8398.seatVictim8856(protectedOnly, now, "INCOMING", { false }, { it == "HOLDER" }, { false }))
        val reg = src("engine/GlobalTradeRegistry.kt")
        assertTrue(reg.contains("fun seatFloorCross8856(mint: String, capUsd: Double, nowMs: Long = System.currentTimeMillis()): String"))
        assertTrue(reg.contains("if (elapsed >= PROBATION_MAX_TIME_MS && floorWatched8856(entry, mint, now)) continue"))
        assertTrue(reg.contains("FloorWatch8856.holding8856(m, now)"))
        assertTrue(src("engine/truth/LaunchSniper8748.kt").contains("FloorWatch8856.onPrint8856(mint, eventMs, recvMs, priceSol, isBuy, live)"))
        val hunt = src("engine/HunterWindow8293.kt")
        assertTrue(hunt.contains("HandoffPriority8190.dueTargets8190(otherMints, nowMs) + crossers + movers + leaders"))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("FloorWatch8856.onDecision8856(ts.mint, admitted, reason, nowMs)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Floor watch (§8856): "))
    }

    @Test fun aGateIsJudgedOnTheFollowedRecordOnceItHasOne() {
        GateOutcome8418.resetForTests8418(); TrajectoryVerdict8855.resetForTest8855()
        val gate = "TEST_GATE_8856"; val now = 1_000_000_000L
        // the first label: 50 refused coins, every one -10 % at its 15 minutes. The gate protects.
        repeat(50) { GateOutcome8418.book8418(gate, "MOONSHOT", -10.0, false, "first$it", now) }
        assertEquals(-10.0, GateOutcome8418.stat8418(gate)!!.mean(), 1e-9)
        assertTrue(GateOutcome8418.protects8715(GateOutcome8418.stat8418(gate)))
        // 39 followed decisions: still the first-grade book
        val flags = IntArray(40)
        repeat(39) { flags[it] = GateOutcome8418.follow8856(gate, "", "MOONSHOT", "coin$it", Double.NaN, -10.0, 0, now) }
        assertEquals(3, flags[0]); assertEquals(50.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        flags[39] = GateOutcome8418.follow8856(gate, "", "MOONSHOT", "coin39", Double.NaN, -10.0, 0, now)
        assertEquals("40 followed: this record is the one read", 40.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        assertTrue(GateOutcome8418.protects8715(GateOutcome8418.stat8418(gate)))
        // the hour reads arrive: the same 40 decisions, 30 of them now at +60 %. Nothing is added; the record moves.
        repeat(30) { flags[it] = GateOutcome8418.follow8856(gate, "", "MOONSHOT", "coin$it", -10.0, 60.0, flags[it], now + 3_600_000L) }
        val st = GateOutcome8418.stat8418(gate)!!
        assertEquals(40.0, st.n, 0.0); assertEquals((30 * 60.0 - 10 * 10.0) / 40.0, st.mean(), 1e-9); assertEquals(30.0, st.wins, 0.0)
        assertFalse("a gate whose refused coins went on to pay no longer protects", GateOutcome8418.protects8715(st))
        // the same coin judged in a second lane a moment later reaches that lane's record, not the all-lanes one twice
        assertEquals(1, GateOutcome8418.follow8856(gate, "", "EXPRESS", "coin0", Double.NaN, 60.0, 0, now + 1_000L))
        assertEquals(40.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        // the record survives a restart
        val saved = TrajectoryVerdict8855.encode8855()
        TrajectoryVerdict8855.resetForTest8855()
        assertEquals("without it the first-grade book answers", 50.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        TrajectoryVerdict8855.restore8855(saved)
        assertEquals(40.0, GateOutcome8418.stat8418(gate)!!.n, 0.0)
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("o.gateFlags8856 = GateOutcome8418.follow8856(o.gate8418, o.advisoryGate8715, o.lane, o.mint, o.gateNet8856, o.pathNet8855, o.gateFlags8856, nowMs)"))
        assertTrue(lab.contains("nowMs - o.atMs >= GateOutcome8418.ATTACH_MAX_AGE_MS_8418 && o.gateNet8856 != o.pathNet8855"))
        GateOutcome8418.resetForTests8418(); TrajectoryVerdict8855.resetForTest8855()
    }

    /** A launch born at [bornMs]: a live buy a second, flat at a $6k cap ($100 SOL) for 20 s, then a climb to 3x. */
    private fun launch(bornMs: Long): LaunchReplay8744.Path8748 {
        val n = 80
        val p = DoubleArray(n) { i -> 6.0e-8 * (if (i <= 20) 1.0 else minOf(3.0, 1.0 + (i - 20) * 0.1)) }
        return LaunchReplay8744.Path8748(LongArray(n) { it * 1_000L }, p, BooleanArray(n) { true }, BooleanArray(n) { true }, 0, bornMs = bornMs)
    }

    @Test fun theWatchedRuleIsGradedOnLaunchesBornAfterItWasNamed() {
        LaunchReplay8744.resetForwardForTest8856()
        val rule = Rule(5_000.0, 1, 2.0, 35.0, Ladder.DOCTRINE, 10_000L)
        val hour = 3_600_000L; val named = 100 * hour
        LaunchReplay8744.nominate8856(rule, named)
        val expected = LaunchReplay8744.simulateRule8755(launch(0L), rule, 100.0, 3.0)!!
        assertTrue(expected > 0.0)
        val before = launch(named - 1L); val a = launch(named + 1_000L); val b = launch(named + 2 * hour)
        // one hour and a bit after the nomination: `a` is complete, `b` is not born into a complete hour yet
        LaunchReplay8744.gradeForward8856(listOf(b, a, before), 100.0, 3.0, named + hour + 2_000L)
        assertTrue(LaunchReplay8744.forwardLine8856().contains(" n1 mean"))
        LaunchReplay8744.gradeForward8856(listOf(b, a, before), 100.0, 3.0, named + hour + 3_000L)
        assertTrue("a launch is graded once", LaunchReplay8744.forwardLine8856().contains(" n1 mean"))
        LaunchReplay8744.gradeForward8856(listOf(b, a, before), 100.0, 3.0, named + 3 * hour + 1L)
        assertTrue(LaunchReplay8744.forwardLine8856().contains(" n2 mean" + "%+.1f".format(expected)))
        // naming it again keeps its record; the record survives a restart
        LaunchReplay8744.nominate8856(rule, named + 5 * hour)
        val saved = LaunchReplay8744.encodeForward8856()
        LaunchReplay8744.resetForwardForTest8856()
        assertEquals("forward8856{-}", LaunchReplay8744.forwardLine8856())
        LaunchReplay8744.decodeForward8856(saved)
        assertTrue(LaunchReplay8744.forwardLine8856().contains(" n2 mean"))
        assertNull("two entries prove nothing", LaunchReplay8744.forwardProven8856())
        LaunchReplay8744.resetForwardForTest8856()
    }

    @Test fun theForwardRecordProvesARunnerRuleAndNotALuckyOne() {
        fun record(rule: Rule, values: List<Double>) = LaunchReplay8744.Forward8856(rule, 0L).also { f -> values.forEach { f.add(it) } }
        val live = Rule(10_000.0, 3, 2.0, 20.0, Ladder.DOCTRINE, 10_000L)
        // 100 small losses and 20 runners: mean +16.7 %, the profit is the runners, and it stands without the best of them
        val runners = List(100) { -10.0 } + List(20) { 150.0 }
        val f = record(live, runners)
        assertEquals(2_000.0 / 120.0, f.mean, 1e-9); assertEquals((2_000.0 - 150.0) / 119.0, f.exBest, 1e-9)
        assertTrue(f.mean - 2.0 * f.se > 0.0)
        assertTrue(LaunchReplay8744.forwardProves8856(f))
        assertFalse("under 100 entries", LaunchReplay8744.forwardProves8856(record(live, runners.take(99))))
        assertFalse("one coin is not a strategy", LaunchReplay8744.forwardProves8856(record(live, List(119) { -1.0 } + 5_000.0)))
        assertFalse("a mean inside two standard errors of zero", LaunchReplay8744.forwardProves8856(record(live, List(110) { -10.0 } + List(10) { 150.0 })))
        assertFalse("a faster rule than the bot can meet", LaunchReplay8744.forwardProves8856(f, minDelayMs = 20_000L))
        assertFalse("only the ladder live runs", LaunchReplay8744.forwardProves8856(record(live.copy(ladder = Ladder.HALF_TRAIL), runners)))
        // The live publication retains this forward proof and reads it at the current latency.
        val published = LaunchReplay8744.ProofSet9047(1L, emptyList(), listOf(f))
        assertEquals(live, published.provenAt(10_000L)!!.rule)
        assertEquals(f.exBest, published.provenAt(10_000L)!!.exTop3, 0.0)
        assertNull("publication cannot lend a 10s grade to a 20s runtime", published.provenAt(20_000L))
        val replay = src("engine/truth/LaunchReplay8744.kt")
        val nominated = replay.indexOf("watched?.rule?.let { nominate8856(it, nowMs) }")
        val graded = replay.indexOf("gradeForward8856(set, solUsd, cost, nowMs)", nominated.coerceAtLeast(0))
        val saved = replay.indexOf("saveForward8856()", graded.coerceAtLeast(0))
        val emitted = replay.indexOf("publishProofs9047(res)", saved.coerceAtLeast(0))
        assertTrue("the completed forward grade is published after nomination, grading and save",
            nominated >= 0 && graded > nominated && saved > graded && emitted > saved)
        assertTrue(replay.contains("fun proven8748(minDelayMs: Long = LaunchSniper8748.requiredDelayMs8749()): Result? = publishedProofs9047.provenAt(minDelayMs)"))
    }
}
