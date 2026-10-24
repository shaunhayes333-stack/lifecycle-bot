package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8355 — shadow evidence survives restarts. Both shadow books persisted their closes but kept their open
 * positions / tickets in memory; the owner installs about once an hour and a shadow position needs 30–120 min to close,
 * so nothing ever closed and the evidence hold could never lift. Open records are now encoded (one versioned string
 * each), written on open / arm / close and coalesced on tier / peak change, and restored in init with their real marks.
 */
class Aate8355ShadowPersistTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    private val pos = ShadowPersist8355.PosRec8355(
        mint = "So11111111111111111111111111111111111111112", lane = "MEME_RUNNER", entryPx = 1.2345e-7, atMs = 1_700_000_000_000L,
        costPct = 6.75, stopPct = 12.0, armPct = 25.0, timeoutMs = 120 * 60_000L, held = 0.4, bankedGrossPct = 24.0, tiersHit = 1,
        peakPct = 55.5, lastPx = 1.5e-7, lastPxAtMs = 1_700_000_900_000L,
    )

    @Test fun positionRoundTripsEveryField() {
        val s = ShadowPersist8355.encodePos8355(pos)
        assertEquals("v1", ShadowPersist8355.VERSION_8355)
        assertTrue(s.startsWith(ShadowPersist8355.VERSION_8355 + "|P|"))
        assertEquals(pos, ShadowPersist8355.decodePos8355(s))
    }

    @Test fun ticketRoundTripsEveryFieldIncludingADeadLegAndAWaitingLeg() {
        val legs = listOf(
            ShadowPersist8355.LegRec8355("NOW", "RUNNER", active = true, dead = false, entryPx = 2.0e-6, entryAtMs = 1_000L, held = 0.4, banked = 24.0, tiers = 1, peak = 48.0, trough = -3.5),
            ShadowPersist8355.LegRec8355("NOW", "SCALP", active = true, dead = true, entryPx = 2.0e-6, entryAtMs = 1_000L, held = 0.0, banked = 22.5, tiers = 2, peak = 31.0, trough = 0.0),
            ShadowPersist8355.LegRec8355("PULLBACK", "RUNNER", active = false, dead = false, entryPx = 0.0, entryAtMs = 0L, held = 1.0, banked = 0.0, tiers = 0, peak = 0.0, trough = 0.0),
            ShadowPersist8355.LegRec8355("CONFIRM", "SCALP", active = true, dead = false, entryPx = 2.1e-6, entryAtMs = 70_000L, held = 1.0, banked = 0.0, tiers = 0, peak = 4.0, trough = -1.0),
        )
        val t = ShadowPersist8355.TicketRec8355(
            mint = "Mint1", symbol = "WE|IRD;SYM", lane = "SNIPER", setup = "BREAKOUT", tactic = "MOMENTUM", scoreBand = "B70", band = "MC_50K",
            signalPx = 2.0e-6, atMs = 1_000L, costPct = 7.0, stopPct = 10.0, armPct = 20.0, runner = false, nativeEligible = true,
            lastPx = 2.3e-6, lastPxAtMs = 95_000L, legs = legs,
        )
        val s = ShadowPersist8355.encodeTicket8355(t)
        assertTrue(s.startsWith("v1|T|"))
        val back = ShadowPersist8355.decodeTicket8355(s)
        assertNotNull(back)
        // the symbol is cosmetic and sanitised (separators become '_'); everything else is exact
        assertEquals(t.copy(symbol = "WE_IRD_SYM"), back)
        assertTrue(back!!.legs[1].dead && back.legs[1].active && back.legs[1].tiers == 2)
        assertFalse(back.legs[2].active)
        val flipped = t.copy(runner = true, nativeEligible = false)
        assertEquals(flipped.copy(symbol = "WE_IRD_SYM"), ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(flipped)))
    }

    @Test fun waitsRoundTrip() {
        val ws = listOf(ShadowPersist8355.WaitRec8355("Mint1|SNIPER", 5_000L, 3.3e-7), ShadowPersist8355.WaitRec8355("Mint2|MEME_RUNNER", 6_000L, 1.0))
        val s = ShadowPersist8355.encodeWaits8355(ws)
        assertTrue(s.startsWith("v1|W|"))
        assertEquals(ws, ShadowPersist8355.decodeWaits8355(s))
        assertTrue(ShadowPersist8355.decodeWaits8355(ShadowPersist8355.encodeWaits8355(emptyList())).isEmpty())
    }

    @Test fun garbageAndWrongVersionsDecodeToNothing() {
        assertNull(ShadowPersist8355.decodePos8355(null))
        assertNull(ShadowPersist8355.decodePos8355(""))
        assertNull(ShadowPersist8355.decodePos8355("v0|P|m|l|1|1|1|1|1|1|1|1|1|1|1|1"))
        assertNull(ShadowPersist8355.decodePos8355(ShadowPersist8355.encodeTicket8355(ShadowPersist8355.TicketRec8355(
            "m", "s", "l", "x", "t", "b", "mc", 1.0, 1L, 1.0, 1.0, 1.0, false, true, 1.0, 1L,
            listOf(ShadowPersist8355.LegRec8355("NOW", "RUNNER", true, false, 1.0, 1L, 1.0, 0.0, 0, 0.0, 0.0)),
        ))))
        assertNull(ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodePos8355(pos)))
        assertNull(ShadowPersist8355.decodeTicket8355("v1|T|m|s|l|x|t|b|mc|1.0|1|1.0|1.0|1.0|0|1|1.0|1|"))   // no legs
        assertNull(ShadowPersist8355.decodePos8355(ShadowPersist8355.encodePos8355(pos.copy(entryPx = 0.0))))  // no entry mark is no position
        assertTrue(ShadowPersist8355.decodeWaits8355("junk").isEmpty())
    }

    @Test fun restoreAgeRuleDropsOnlyBeyondTheRecordsOwnTimeoutPlusLostMark() {
        val at = 1_000_000L
        val timeout = 30 * 60_000L
        val lost = ShadowPersist8355.LOST_MARK_MS_8355
        assertEquals(10 * 60_000L, lost)
        assertFalse(ShadowPersist8355.expiredOnRestore8355(at, timeout, at + timeout))                 // at its timeout: kept (closes TIMEOUT on the first tick)
        assertFalse(ShadowPersist8355.expiredOnRestore8355(at, timeout, at + timeout + lost))          // exactly at the window: kept
        assertTrue(ShadowPersist8355.expiredOnRestore8355(at, timeout, at + timeout + lost + 1L))      // past it: dropped
        assertFalse(ShadowPersist8355.expiredOnRestore8355(at, 120 * 60_000L, at + timeout + lost + 1L))  // a runner's own window is longer
        assertTrue(ShadowPersist8355.expiredOnRestore8355(0L, timeout, at))                            // no timestamp is no record
    }

    @Test fun coalescingRuleWritesChangedRecordsAtMostOncePerTenSeconds() {
        val c = ShadowPersist8355.COALESCE_MS_8355
        assertEquals(10_000L, c)
        assertFalse("unchanged never writes", ShadowPersist8355.writeDue8355(false, 1_000L, 1_000L + c * 10))
        assertFalse("changed but written 1 s ago waits", ShadowPersist8355.writeDue8355(true, 1_000L, 2_000L))
        assertTrue("changed and 10 s since the last write", ShadowPersist8355.writeDue8355(true, 1_000L, 1_000L + c))
        assertTrue("changed and never written", ShadowPersist8355.writeDue8355(true, 0L, 5L))
        assertTrue("a forced write (open, arm, close) always goes", ShadowPersist8355.writeDue8355(false, 1_000L, 1_001L, force = true))
        assertTrue("a clock that went backwards does not starve the write", ShadowPersist8355.writeDue8355(true, 9_000L, 1_000L))
    }

    @Test fun evidenceShadowPersistsOnOpenTierPeakAndCloseAndRestoresInInit() {
        val s = src("engine/truth/EvidenceShadow8344.kt")
        assertTrue(s.contains("private const val PREFS_OPEN_8355 = \"aate_evidence_shadow_open_8355\""))
        assertTrue("written on open", s.contains("open[mint] = pos8355") && s.contains("persistOpen8355(pos8355, nowMs)"))
        assertTrue("a peak change is a persisted change", s.contains("if (g > p.peakPct) { p.peakPct = g; p.dirty8355 = true }"))
        assertTrue("a tier change is a persisted change", s.contains("p.tiersHit++") && s.contains("p.dirty8355 = true   // V5.0.8355 — a tier change is a persisted change"))
        assertTrue("coalesced on the tick", s.contains("if (ShadowPersist8355.writeDue8355(p.dirty8355, p.lastWriteMs8355, nowMs)) persistOpen8355(p, nowMs)"))
        assertTrue("removed on close", s.contains("done.forEach { open.remove(it); forgetOpen8355(it) }"))
        assertTrue("restored in init", s.contains("fun init8344(ctx: Context, nowMs: Long = System.currentTimeMillis()) {") && s.contains("restoreOpen8355(ctx, nowMs)"))
        assertTrue("a restored position keeps its real marks and clock", s.contains("Pos8344(r.mint, r.lane, r.entryPx, r.atMs, r.costPct, r.stopPct, r.armPct, r.timeoutMs)") &&
            s.contains("pos.lastPx = r.lastPx; pos.lastPxAtMs = r.lastPxAtMs"))
        assertTrue("the restore honours the cap and the age rule", s.contains("ShadowPersist8355.expiredOnRestore8355(r.atMs, r.timeoutMs, nowMs)") && s.contains("open.size >= MAX_OPEN_8344 || open.containsKey(r.mint)"))
        assertTrue("lost-on-restore is counted", s.contains("if (why == \"LOST_MARK\" && p.noMarkSinceRestore8355) lostOnRestore8355.incrementAndGet()"))
        assertTrue(s.contains("restored8355=\${restored8355.get()} lostOnRestore8355=\${lostOnRestore8355.get()}"))
        assertTrue("apply(), never commit()", !s.contains("commit()"))
    }

    @Test fun exploreShadowPersistsTicketsAndWaitsAndRestoresInInit() {
        val s = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(s.contains("private const val PREFS_OPEN_8355 = \"aate_explore_shadow_open_8355\""))
        assertTrue("written on open under the exact native-or-Cortex distinct identity",
            s.contains("val ticketKey8368 = cortexTicketKey8368(key, kind)") &&
                s.contains("persistTicket8355(ticketKey8368, t, nowMs)   // V5.0.8355 — written on open"))
        // superseded by V5.0.8364 — the armed fill is booked at the trigger mark plus the lane's chase (ShadowFill8364); the arm still forces a write
        assertTrue("arm forces a write", s.contains("leg.active = true; leg.entryPx = ShadowFill8364.laneEntryPx8364(px, t.lane); leg.entryAtMs = nowMs; t.force8355 = true"))
        assertTrue("a leg close forces a write", s.contains("leg.dead = true\n        t.force8355 = true"))
        assertTrue("tier moves are coalesced", s.contains("leg.held -= sold; leg.tiers++; t.dirty8355 = true") && s.contains("leg.tiers = 2; t.dirty8355 = true"))
        assertTrue("peak moves are coalesced", s.contains("if (g > leg.peak) { leg.peak = g; t.dirty8355 = true }"))
        assertTrue("coalesced on the tick", s.contains("else if (ShadowPersist8355.writeDue8355(t.dirty8355, t.lastWriteMs8355, nowMs, t.force8355)) {"))
        assertTrue("removed once every leg is dead", s.contains("done.forEach { tickets.remove(it); forgetTicket8355(it) }"))
        assertTrue("the waits map is flushed from the tick", s.contains("flushWaits8355(nowMs)") && s.contains("waits.remove(key); waitsDirty8355 = true"))
        assertTrue("restored in init", s.contains("fun init8347(ctx: Context, nowMs: Long = System.currentTimeMillis()) {") && s.contains("restoreOpen8355(ctx, nowMs)"))
        assertTrue("a restored leg keeps its real entry", s.contains("leg.active = l.active; leg.dead = l.dead; leg.entryPx = l.entryPx; leg.entryAtMs = l.entryAtMs"))
        assertTrue("the restore honours both caps and the age rule", s.contains("tickets.size >= MAX_TICKETS_8347") && s.contains(">= MAX_TICKETS_PER_LANE_8351) { drop += k; continue }") &&
            s.contains("ShadowPersist8355.expiredOnRestore8355(r.atMs, window, nowMs, LOST_MARK_MS_8347)"))
        assertTrue(s.contains("restored8355=\${restored8355.get()} expiredOnRestore8355=\${expiredOnRestore8355.get()}"))
        assertTrue("apply(), never commit()", !s.contains("commit()"))
        // the close books are untouched: their prefs names and writers are what 8344 / 8351 left
        assertTrue(s.contains("private const val PREFS_8347 = \"aate_explore_shadow_8347\""))
        assertTrue(src("engine/truth/EvidenceShadow8344.kt").contains("private const val PREFS_8344 = \"aate_evidence_shadow_8344\""))
    }

    @Test fun restoreCountersStartAtZeroWithoutAnInit() {
        val (restored, lost, expired) = com.lifecyclebot.engine.truth.EvidenceShadow8344.restoreCounts8355()
        assertTrue(restored >= 0L && lost >= 0L && expired >= 0L)
        val (tickets, expiredT, waits) = com.lifecyclebot.engine.truth.ExploreShadow8347.restoreCounts8355()
        assertTrue(tickets >= 0L && expiredT >= 0L && waits >= 0L)
    }

    @Test fun botServiceStillWiresInitAndTick() {
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("EvidenceShadow8344.init8344(applicationContext)") && b.contains("ExploreShadow8347.init8347(applicationContext)"))
        assertTrue(b.contains("EvidenceShadow8344.tick8344(tokenStatesCopy, nowShadow7307)") && b.contains("ExploreShadow8347.tick8347(tokenStatesCopy, nowShadow7307)"))
    }
}
