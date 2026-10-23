package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.ShadowMark8360
import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8360 — the shadow explorer names every trade it can: every lane (eligible or a non-safety refusal), every fired
 * setup (up to 3 per lane), priced on any OBSERVED mark under 60 s; only hard safety and "no observed mark" stop a ticket.
 * Capacity 3,000 / 300 per lane, the oldest waiting ticket evicted when full, at most 600 tickets marked per loop.
 */
class Aate8360ExploreStackTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun eligibleAndNonSafetyRejectsOpenSafetyNever() {
        val k = ExploreShadow8347
        assertEquals(k.KIND_NATIVE_8360, k.openKind8360(true, "SHITCOIN_NATIVE_ALLOW", false))
        // mandate / fit / threshold / momentum / data-class refusals open as REJECT tickets
        for (r in listOf("MOONSHOT_MCAP_OUT_OF_BAND", "SCORE_BELOW_FLUID_THRESHOLD", "NO_CONTINUATION", "UNHYDRATED_DEFER_8236_MCAP",
            "TREASURY_ROLE_FLOOR_8031", "LIQ_TOO_LOW_FOR_LANE", "NET_EDGE_BELOW_COST")) {
            assertEquals(r, k.KIND_REJECT_8360, k.openKind8360(false, r, false))
        }
        // hard safety never opens
        for (r in listOf("RUG_RISK_HIGH", "HONEYPOT", "FREEZE_AUTHORITY_ACTIVE", "MINT_AUTHORITY_LIVE", "TOKEN_2022_HOSTILE_EXT", "UNSELLABLE_ROUTE")) {
            assertEquals(r, k.SKIP_SAFETY_REASON_8360, k.openKind8360(false, r, false))
            assertFalse(k.opens8360(k.openKind8360(false, r, false)))
        }
        // the token's own hard-safety tier stops even an eligible opinion; a lane that errored has no opinion to explore
        assertEquals(k.SKIP_SAFETY_TOKEN_8360, k.openKind8360(true, "OK", true))
        assertEquals(k.SKIP_LANE_ERROR_8360, k.openKind8360(false, "BRAIN_ERROR_IllegalStateException", false))
        assertEquals(k.SKIP_NOT_SAMPLED_8360, k.openKind8360(false, "MCAP_OUT_OF_BAND", false, sampled = false))
        assertTrue(k.opens8360(k.KIND_SETUP_8360))
        assertEquals(100, k.REJECT_SAMPLE_PCT_8347)
    }

    @Test fun setupTicketsAreTheOtherFiredSetupsAtMostThreePerLaneKeyedBySetup() {
        val k = ExploreShadow8347
        assertEquals(listOf("IGNITION_5M", "BUYERS_DOMINATING", "RECLAIM"),
            k.setupTickets8360("VOLUME_IGNITION_SCALP", listOf("IGNITION_5M", "NO_TRIGGER", "BUYERS_DOMINATING", "ignition_5m", "RECLAIM", "LATE_RUN")))
        assertEquals(listOf("RECLAIM"), k.setupTickets8360("IGNITION_5M", listOf("ignition_5m", "RECLAIM", "NONE", "")))
        assertTrue(k.setupTickets8360("X", emptyList()).isEmpty())
        assertEquals(1, k.setupTickets8360(null, listOf("A", "B"), max = 1).size)
        assertEquals("M|SHITCOIN|IGNITION_5M", k.ticketKey8360("M", "SHITCOIN", "IGNITION_5M"))
        assertFalse(k.ticketKey8360("M", "SHITCOIN", "A") == k.ticketKey8360("M", "SHITCOIN", "B"))
        assertEquals(6, k.legsFor8360(k.KIND_NATIVE_8360).size)
        assertEquals(6, k.legsFor8360(k.KIND_SETUP_8360).size)
        assertEquals(listOf(ExploreShadow8347.Entry8347.NOW to ExploreShadow8347.Exit8347.RUNNER, ExploreShadow8347.Entry8347.NOW to ExploreShadow8347.Exit8347.SCALP),
            k.legsFor8360(k.KIND_REJECT_8360))
    }

    @Test fun markSourceIsTheFirstObservedMarkUnderSixtySeconds() {
        val now = 1_700_000_000_000L
        val m = ShadowMark8360
        assertEquals(0.5 to m.SRC_TICK_8360, m.pick8360(0.5, now - 59_000L, 0.6, 0.7, 0.8, now - 1_000L, now))
        assertEquals(0.6 to m.SRC_LABELER_8360, m.pick8360(0.5, now - 61_000L, 0.6, 0.7, 0.8, now - 1_000L, now))
        assertEquals(0.7 to m.SRC_CURVE_8360, m.pick8360(0.0, 0L, null, 0.7, 0.8, now - 1_000L, now))
        assertEquals(0.8 to m.SRC_TAPE_8360, m.pick8360(Double.NaN, now, null, null, 0.8, now - 30_000L, now))
        assertNull("a tape print older than 60 s is not a mark", m.pick8360(0.0, 0L, null, null, 0.8, now - 61_000L, now))
        assertNull("a future-stamped tick is not a mark", m.pick8360(0.5, now + 5_000L, null, null, null, 0L, now))
        assertNull(m.pick8360(0.5, now - 120_000L, 0.0, -1.0, null, 0L, now))
        assertEquals(60_000L, m.MAX_AGE_MS_8360)
        assertNull("no mint, no mark", m.observed8360(null, "", now))
    }

    @Test fun evictionTakesTheOldestTicketWithNoActiveLeg() {
        val k = ExploreShadow8347
        val c = listOf(Triple("a", 100L, true), Triple("b", 300L, false), Triple("c", 200L, false), Triple("d", 50L, true))
        assertEquals("c", k.evictKey8360(c))
        assertNull("every ticket has an active leg: nothing is evicted, the open is refused", k.evictKey8360(listOf(Triple("a", 1L, true))))
        assertNull(k.evictKey8360(emptyList()))
    }

    @Test fun roundRobinTicksAtMostTheBudgetAndWraps() {
        val k = ExploreShadow8347
        assertEquals(600, k.TICK_PER_LOOP_8360)
        val (a, next) = k.rrSlice8360(3_000, 0)
        assertEquals(600, a.size); assertEquals(0, a.first()); assertEquals(599, a.last()); assertEquals(600, next)
        val (b, next2) = k.rrSlice8360(1_000, 900)
        assertEquals(600, b.size); assertEquals(900, b.first()); assertEquals(499, b.last()); assertEquals(500, next2)
        val (c, next3) = k.rrSlice8360(10, 7)
        assertEquals(10, c.size); assertEquals(10, c.toSet().size); assertEquals(7, next3)
        assertTrue(k.rrSlice8360(0, 5).first.isEmpty())
        // every ticket is visited within ceil(n / budget) loops
        var cur = 0; val seen = HashSet<Int>()
        repeat(5) { val s = k.rrSlice8360(3_000, cur); seen.addAll(s.first); cur = s.second }
        assertEquals(3_000, seen.size)
    }

    @Test fun capacityAndPersistedKind() {
        assertEquals(3_000, ExploreShadow8347.MAX_TICKETS_8347)
        assertEquals(300, ExploreShadow8347.MAX_TICKETS_PER_LANE_8351)
        val legs = listOf(ShadowPersist8355.LegRec8355("NOW", "RUNNER", true, false, 1.0, 1L, 1.0, 0.0, 0, 0.0, 0.0))
        val t = ShadowPersist8355.TicketRec8355("m", "s", "SHITCOIN", "IGNITION_5M", "MOMENTUM", "B70", "MC_LT10K", 1.0, 1L, 7.0, 10.0, 20.0,
            false, true, 1.0, 1L, legs, kind8360 = "SETUP", markSrc8360 = "TAPE")
        assertEquals(t, ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(t)))
        // a pre-8360 record (19 fields) still decodes, with no kind (derived from nativeEligible on restore)
        val old = ShadowPersist8355.encodeTicket8355(t.copy(kind8360 = "", markSrc8360 = "")).split('|').take(19).joinToString("|")
        assertEquals("", ShadowPersist8355.decodeTicket8355(old)?.kind8360)
    }

    @Test fun hookSitesArePinned() {
        val s = src("engine/truth/ExploreShadow8347.kt")
        assertTrue("the open prices on any observed mark", s.contains("val mark = ShadowMark8360.observed8360(ts, ts.mint, nowMs)"))
        assertFalse("no 45 s tick-freshness test left", s.contains("nowMs - ts.lastPriceUpdate > 45_000L") || s.contains("nowMs - it.lastPriceUpdate <= 45_000L"))
        assertTrue("no mark is counted", s.contains("skip8360(SKIP_NO_MARK_8360, opinions.size.toLong())"))
        assertTrue("every opinion is classified", s.contains("val kind = openKind8360(o.eligible, o.reason, blocked, o.eligible || sampled8347(ts.mint, lane))"))
        assertTrue("setup tickets per lane", s.contains("for (setup in firedSetups8360(ts, lane, primary, nowMs)) open8347(ts, lane, o, mark.first, nowMs, KIND_SETUP_8360, setup, mark.second)"))
        assertTrue("fired setups from the playbook", s.contains("LanePlaybook7907.matchedSetups8083(ts, lane, nowMs)"))
        assertTrue("keyed by setup", s.contains("val key = ticketKey8360(ts.mint, lane, setup)"))
        assertTrue("evict before refusing", s.contains("tickets.size >= MAX_TICKETS_8347 && !evict8360(null, nowMs)") &&
            s.contains("laneCount8360(lane) >= MAX_TICKETS_PER_LANE_8351 && !evict8360(lane, nowMs)"))
        assertTrue("round-robin tick", s.contains("val slice = rrSlice8360(keys.size, rrCursor8360)"))
        assertTrue("the tick reads the same observed mark", s.contains("ShadowMark8360.observed8360(tokens[t.mint], t.mint, nowMs)?.first"))
        assertTrue("marks kept flowing", s.contains("want8360(t.mint, nowMs)") && s.contains("want8360(ts.mint, nowMs)") &&
            s.contains("try { KeylessMarks8359.noteWanted8359(mint, untilMs) } catch (_: Throwable) {}") &&   // 8359 merged: the keyless feed is asked
            s.contains("com.lifecyclebot.engine.DataPipeline8191.noteNearDecision8191(mint, System.currentTimeMillis())"))
        assertTrue("tactic cohort", s.contains("bookKey8351(\"C|\$v|T:\${t.tactic}\", net, nowMs)"))
        assertTrue("reject cohort kept for FILTER_INVERTED", s.contains("KIND_REJECT_8360 -> bookKey8351(\"C|\$v|R:REJECT\", net, nowMs)"))
        assertTrue("setup cohort", s.contains("KIND_SETUP_8360 -> bookKey8351(\"C|\$v|\${if (t.nativeEligible) \"S\" else \"SR\"}:\${t.setup}\", net, nowMs)"))
        assertTrue("tactics learn from native tickets only", s.contains("if (t.kind8360 == KIND_NATIVE_8360 && t.nativeEligible && leg.entry == Entry8347.NOW"))
        assertTrue("diagnostic cohorts never become a live niche", s.contains("if (dim != \"S\" && dim != \"M\") continue"))
        assertTrue("the status line", s.contains("byKind{native=") && s.contains("skipped{\${countLine8360(skipped)}} evicted=") &&
            s.contains("ticked/loop=\$lastTicked8360/\$TICK_PER_LOOP_8360") && s.contains("best{\$cohorts}"))
        val e = src("engine/truth/EvidenceShadow8344.kt")
        assertTrue("the live mirror falls back to every observed mark", e.contains("ShadowMark8360.observed8360(ts, mint, nowMs)") &&
            e.contains("entryMark8360{"))
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("fun lastPrint8360(mint: String): Pair<Double, Long>?"))
        assertTrue("the bridge hook is unchanged", src("engine/SpecialistBrainBridge7542.kt").contains("ExploreShadow8347.onOpinions8347(ts, out.toMap(), now)"))
    }
}
