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
        assertTrue("written on open", s.contains("open[key] = pos8355") && s.contains("persistOpen8355(pos8355, nowMs)"))
        assertTrue("a peak change is a persisted change", s.contains("if (g > p.peakPct) { p.peakPct = g; p.dirty8355 = true }"))
        assertTrue("a tier change is a persisted change", s.contains("p.tiersHit = maxOf(p.tiersHit, p.sellTier8394)") && s.contains("persistOpen8355(p, nowMs)"))
        assertTrue("coalesced on the tick", s.contains("if (ShadowPersist8355.writeDue8355(p.dirty8355, p.lastWriteMs8355, nowMs)) persistOpen8355(p, nowMs)"))
        assertTrue("removed on close", s.contains("done.forEach { key -> open.remove(key)?.let { forgetOpen8355(it.storageKey) } }"))
        val init = s.substringAfter("fun init8344(").substringBefore("\n    }")
        assertTrue("restored in retryable serialized init", init.contains("synchronized(initLock8372)") &&
            init.contains("if (!openRestored8372) openRestored8372 = restoreOpen8355(ctx, nowMs)") &&
            init.contains("initReady8372()"))
        assertTrue("a restored position keeps its real marks and clock", s.contains("Pos8344(r.mint, r.lane, r.entryPx, r.atMs, r.costPct, r.stopPct, r.armPct, r.timeoutMs, r.advisoryEntry8393, r.contractVersion8394)") &&
            s.contains("pos.lastPx = r.lastPx; pos.lastPxAtMs = r.lastPxAtMs"))
        val restore = s.substringAfter("private fun restoreOpen8355(").substringBefore("fun restoreCounts8355()")
        assertTrue("the restore honours the cap and the age rule", restore.contains("ShadowPersist8355.expiredOnRestore8355(r.atMs, r.timeoutMs, nowMs)") &&
            restore.contains("if (open.size >= MAX_OPEN_8344) { drop += k; continue }"))
        assertTrue("partial-init retry preserves an existing episode before testing old persisted expiry",
            restore.indexOf("if (open.containsKey(episodeKey)) continue") in 0 until restore.indexOf("ShadowPersist8355.expiredOnRestore8355("))
        assertTrue("a concurrent or newer in-memory episode is neither overwritten nor deleted",
            restore.contains("if (open.putIfAbsent(episodeKey, pos) == null)") &&
                restore.contains("p.write(drop.filter { open.values.none { pos -> pos.storageKey == it } }.associateWith { null as String? })"))
        assertTrue("a failed deletion batch leaves restore retryable",
            restore.contains("if (drop.isNotEmpty() && !p.write(drop.filter { open.values.none { pos -> pos.storageKey == it } }.associateWith { null as String? })) throw ShadowStorageUnavailable8397()"))
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
        val close = s.substringAfter("private fun close8347(", "").substringBefore("private fun bookKeys8397(", "")
        val unavailable = close.substringAfter("if (!executable) {", "").substringBefore("when (t.kind8360)", "")
        assertTrue("an unavailable leg close forces a write", unavailable.contains("leg.dead = true; t.force8355 = true"))
        val booking = close.indexOf("val booked8397 = bookKeys8397(")
        val terminal = close.indexOf("leg.dead = true; t.force8355 = true; t.cortex8372 = outcome8397")
        assertTrue("an executable leg closes and forces a write only after its durable receipt", booking in 0 until terminal)
        assertTrue("crash replay also persists the terminal before skipping repeated side effects",
            terminal in 0 until close.indexOf("if (!booked8397.inserted) return"))
        assertTrue("tier moves are coalesced", s.contains("leg.tiers = maxOf(leg.tiers, leg.sellTier8394)") && s.contains("t.force8355 = true"))
        assertTrue("peak moves are coalesced", s.contains("if (g > leg.peak) { leg.peak = g; t.dirty8355 = true }"))
        assertTrue("coalesced on the tick", s.contains("else if (ShadowPersist8355.writeDue8355(t.dirty8355, t.lastWriteMs8355, nowMs, t.force8355)) {"))
        // V5.0.8372 retains a dead ticket until its learner receipt is ACKed, and cleanup may remove
        // only that exact episode: a replacement under the same lane/mint key must survive.
        assertTrue("terminal tickets wait for their learning receipt", s.contains("if (t.legs.all { it.dead } && !t.cortex8372.awaiting8372()) {"))
        val cleanup = s.substringAfter("for ((key, completed) in done) tickets.computeIfPresent(key)")
            .substringBefore("recountLanes8360()")
        assertTrue(cleanup.contains("if (current !== completed || !current.legs.all { it.dead } || current.cortex8372.awaiting8372()) current"))
        assertTrue("persisted ticket removed before the map entry", cleanup.indexOf("forgetTicket8355(key)") in
            0 until cleanup.indexOf("\n                    null"))
        assertTrue("the waits map is flushed from the tick", s.contains("flushWaits8355(nowMs)") && s.contains("waits.remove(key); waitsDirty8355 = true"))
        val init = s.substringAfter("fun init8347(").substringBefore("\n    }")
        assertTrue("restored in retryable serialized init", init.contains("synchronized(initLock8372)") &&
            init.contains("if (!openRestored8372) openRestored8372 = restoreOpen8355(ctx, nowMs)") &&
            init.contains("initReady8372()"))
        assertTrue("a restored leg keeps its real entry", s.contains("leg.active = l.active; leg.dead = l.dead; leg.entryPx = l.entryPx; leg.entryAtMs = l.entryAtMs"))
        val restore = s.substringAfter("private fun restoreOpen8355(").substringBefore("fun restoreCounts8355()")
        assertTrue("the restore honours both caps and the age rule", restore.replace(Regex("\\s+"), "").contains(
            "if(tickets.size>=MAX_TICKETS_8347||laneCount8360(t.lane)>=MAX_TICKETS_PER_LANE_8351){drop+=k;return@synchronizedfalse}") &&
            restore.contains("ShadowPersist8355.expiredOnRestore8355(r.atMs, window, nowMs, LOST_MARK_MS_8347)"))
        assertTrue("capacity never silently drops an enrolled decision", restore.contains("if (!t.cortex8372.pending8372()) {") &&
            restore.contains("t.cortex8372.unavailable8372(\"RESTORE_CAPACITY_8372\", nowMs)"))
        assertTrue(s.contains("restored8355=\${restored8355.get()} expiredOnRestore8355=\${expiredOnRestore8355.get()}"))
        val writer = s.substringAfter("private fun persistTicket8355(").substringBefore("private fun forgetTicket8355(")
        // V5.0.8377 — ordinary writes stay asynchronous; durable receipts commit once per tick slice, never once per ticket.
        // V5.0.8386 — ordinary writes are only staged (coalesced, bounded); one ordered writer commits per flush.
        val ordinary = writer.substringBefore("private fun flushOpen8386(")
        assertTrue("ordinary open/tier/peak writes are staged, never written inline", ordinary.contains("openWrites8386.stage(") &&
            !ordinary.contains("commit()") && !ordinary.contains(".apply()"))
        assertEquals("no whole-file preference commits remain", 0, Regex("\\.commit\\(\\)").findAll(s).count())
        assertTrue("the ordered writer commits per-key SQL batches", s.contains("p.write(batch.mapValues { it.value.value })"))
        val pendingReceipt = s.substringAfter("if (t.cortex8372.pending8372()) {")
            .substringBefore("if (t.legs.all { it.dead } && !t.cortex8372.awaiting8372())")
        assertTrue("an undurable receipt is staged, never delivered in the same pass", pendingReceipt.contains("if (!t.receiptDurable8372) {") &&
            pendingReceipt.contains("receipts8377 += key to t") &&
            pendingReceipt.indexOf("continue") in 0 until pendingReceipt.indexOf("deliverCortex8372(t)"))
        val batch = s.substringAfter("if (batch8377 != null && (staged8377.isNotEmpty() || receipts8377.isNotEmpty())) {").substringBefore("flushBooks8377(nowMs)")
        assertTrue("the batch commits before any staged receipt is delivered", batch.indexOf("val written = flushOpen8386(nowMs, durable = receipts8377.isNotEmpty())") in 0 until batch.indexOf("deliverCortex8372(t)") &&
            batch.contains("if (!written) { t.force8355 = true; cortexRetry8372.incrementAndGet(); continue }"))
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
