package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelFeed8291
import com.lifecyclebot.engine.cortex.CortexOutcomeProof8395
import com.lifecyclebot.engine.cortex.CortexForwardEpoch8521
import com.lifecyclebot.engine.cortex.ForwardCheckpoint8401
import com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404
import com.lifecyclebot.engine.truth.DowntimeRecovery8430
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelBasis8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test

/** Production queue and checkpoint paths, including the same evidence on both sides of a decision. */
class Aate8521ForwardLifecycleTest {
    private val runtime = ForwardReturnLabeler7731
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun get(owner: Any, name: String): Any? = field(owner, name).get(owner)
    private fun set(owner: Any, name: String, value: Any?) = field(owner, name).set(owner, value)
    private fun call(name: String, vararg args: Any?): Any? = runtime.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(runtime, *args)
    private fun mark(mint: String, at: Long, price: Double = 1.0) =
        LabelMark8386(mint, "TEST", price, at, at, "CURVE_ACCOUNT", decimals = 6, independentBasis = true)
    private fun observation(mint: String, lane: String, admitted: Boolean, at: Long, cell: String = "TEST_FORWARD_8521"): Any {
        val obs = Class.forName("${runtime.javaClass.name}\$Obs").declaredConstructors
            .single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(mint, "TEST", cell, "TEST", lane, admitted,
                50, "TEST", "TEST", "TEST", 1.0, 3.0, at)
        val basis = LabelBasis8386(mark(mint, at), 100_000.0)
        set(obs, "basis8386", basis); set(obs, "decisionBasis8386", basis)
        return obs
    }

    @Test fun decisionMarkDoesNotSuppressTheExecutableFillReadForAnyLaneOrVerdict() {
        val at = 1_000_000L
        for (lane in lanes) for (take in listOf(false, true)) {
            val mint = "fill-8521-$lane-$take"
            val obs = observation(mint, lane, take, at)
            set(obs, "lastPx", 1.0); set(obs, "lastPxAtMs", at)
            val due = ArrayList<Pair<String, Long>>()
            call("queueIndependentRefresh8521", obs, mark(mint, at), at + 55_000L, due)
            assertTrue("$lane/$take needs a reachable fill even after seeing its decision price", due.any { it.first == mint })
            assertEquals(false, get(obs, "rebased8076"))
            assertEquals(at, (get(obs, "basis8386") as LabelBasis8386).mark.observedAtMs)
        }
    }

    @Test fun freshPreHorizonMarketMarkStillRequestsTheActualTargetAndDoesNotChangeIt() {
        val at = 1_000_000L
        for (lane in lanes) for (take in listOf(false, true)) {
            val mint = "target-8521-$lane-$take"
            val obs = observation(mint, lane, take, at)
            set(obs, "rebased8076", true); set(obs, "done15", true)
            val before = mark(mint, at + 299_999L, 1.2)
            val due = ArrayList<Pair<String, Long>>()
            call("queueIndependentRefresh8521", obs, before, at + 300_000L, due)
            assertTrue("$lane/$take a one-ms old price can still precede the target", due.any { it.first == mint })
            assertEquals(false, get(obs, "done60"))
            assertTrue((get(obs, "bookedNet7997") as Double).isNaN())
            due.clear()
            call("queueIndependentRefresh8521", obs, before.copy(observedAtMs = at + 300_000L, readAtMs = at + 300_000L), at + 300_000L, due)
            assertTrue("a real on-time market response already covers the target", due.isEmpty())
        }
    }

    @Test fun requiredSourceClockTracksOnlyOpenTargetsWithoutReopeningExpiredWindows() {
        val at = 1_000_000L
        val horizons = longArrayOf(60_000L, 300_000L, 3_600_000L)
        val graces = longArrayOf(60_000L, 150_000L, 600_000L)
        assertEquals(0L, CortexLabelFeed8291.requiredObservationAt8521(at, at + 59_999L, horizons, graces))
        for (h in horizons) assertEquals(at + h, CortexLabelFeed8291.requiredObservationAt8521(at, at + h, horizons, graces))
        assertEquals(0L, CortexLabelFeed8291.requiredObservationAt8521(at, at + 450_001L, horizons, graces))
        assertEquals(0L, CortexLabelFeed8291.requiredObservationAt8521(at, at + 4_200_001L, horizons, graces))
        assertEquals(at + 300_000L, CortexLabelFeed8291.requiredObservationAt8521(at, at + 300_000L,
            longArrayOf(-1L, 300_000L), longArrayOf(0L, 150_000L)))
    }

    @Test fun hourTargetHasEqualPriorityAndDeadlineOrderingWithTheFiveMinuteTarget() {
        val now = 10_000_000L
        val hour = observation("hour-priority-8521", "PROJECT_SNIPER", false, now - 3_600_000L)
        set(hour, "done15", true); set(hour, "done60", true); set(hour, "rebased8076", true)
        val short = observation("scalp-priority-8521", "TREASURY", true, now - 300_000L)
        set(short, "done15", true); set(short, "rebased8076", true)
        assertEquals(0, call("labelOffWatchRank8521", hour, 3_600_000L, false))
        assertEquals(0, call("labelOffWatchRank8521", short, 300_000L, false))
        val hourDue = ArrayList<Pair<String, Long>>()
        val shortDue = ArrayList<Pair<String, Long>>()
        call("queueIndependentRefresh8521", hour, mark("hour-priority-8521", now - 1), now, hourDue)
        call("queueIndependentRefresh8521", short, mark("scalp-priority-8521", now - 1), now, shortDue)
        assertTrue("both unobserved targets request prices", hourDue.isNotEmpty() && shortDue.isNotEmpty())
        assertTrue("the target expiring sooner is first, independent of original decision age", shortDue.single().second < hourDue.single().second)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun missingInterimMarkCannotCancelAnyLanesFrozenHourTarget() {
        val pending = get(runtime, "pending") as MutableMap<String, Any>
        val at = System.currentTimeMillis() - 600_000L
        val inserted = ArrayList<String>()
        try {
            for (lane in lanes) for (take in listOf(false, true)) {
                val horizon = CortexForwardEpoch8521.targetHorizon(CortexForwardEpoch8521.targetKind(lane))
                if (horizon != 3_600_000L) continue
                val mint = "interim-8521-$lane-$take"; val key = "$mint|$lane"
                val obs = observation(mint, lane, take, at)
                set(obs, "targetHorizon8521", horizon); set(obs, "rebased8076", true)
                pending[key] = obs; inserted += key
                assertEquals(false, call("expired8395", key, obs, null, at + 450_000L))
                assertSame("$lane/$take still owns its original hour target", obs, pending[key])
                assertEquals(true, get(obs, "done60"))
                assertEquals(true, get(obs, "interimUnavailable8521"))
                assertTrue("no interim return is fabricated", (get(obs, "bookedNet7997") as Double).isNaN())
                assertEquals(at + 4_200_000L, call("deadline8395", obs))
                val hour = mark(mint, at + 3_605_000L, 2.0)
                set(obs, "lastMark8386", hour)
                val proof = call("outcomeProof8395", obs, 97.0, 100.0,
                    ForwardReturnLabeler7731.revisionHorizon8521(hour.observedAtMs - at)) as CortexOutcomeProof8395
                assertEquals(3_600_000L, proof.horizonMs)
                assertEquals(at + 3_605_000L, proof.observedAtMs)
                assertEquals("$key|$at", proof.decisionId)
            }
            assertTrue("the real lane policy must include patient targets", inserted.isNotEmpty())
        } finally { inserted.forEach { pending.remove(it) } }
    }

    @Test fun hourProofKeepsItsPromisedDurationAndFirstValueThroughDeliveryFailures() {
        assertEquals(3_600_000L, ForwardReturnLabeler7731.revisionHorizon8521(3_605_000L))
        assertEquals(900_000L, ForwardReturnLabeler7731.revisionHorizon8521(905_000L))
        assertEquals(14_400_000L, ForwardReturnLabeler7731.revisionHorizon8521(14_405_000L))
        val q = ForwardOutcomeOutbox8404(8)
        val episode = "runner-8521|MOONSHOT|1000"
        val first = "$episode|R|3606000|3600000"
        val late = "$episode|R|14406000|14400000"
        assertFalse(q.stage(first, { "first-hour-value" }) { _, _ -> false })
        assertEquals(first to "first-hour-value", q.firstRevisionForHorizon8521(episode, 3_600_000L))
        assertEquals(0, q.supersedeRevisions8418("$episode|R|", late, protectedHorizonMs = 3_600_000L) { true })
        assertTrue(q.stage(first, { "different-later-price" }) { _, raw -> raw == "first-hour-value" })
        assertTrue(q.stage(late, { "later-checkpoint" }) { _, _ -> true })
        assertEquals("first-hour-value", q.payload(first))
        val saved = linkedMapOf(first to q.payload(first)!!, late to q.payload(late)!!)
        val restored = ForwardOutcomeOutbox8404(8).also { it.restore(saved) }
        assertEquals(first to "first-hour-value", restored.firstRevisionForHorizon8521(episode, 3_600_000L))
        val consumed = ArrayList<String>()
        restored.drain(8, { _, _ -> true }, { consumed += it; true }, { false })
        restored.drain(8, { _, _ -> true }, { fail("acknowledged payload cannot train twice"); false }, { true })
        assertEquals(1, consumed.count { it == "first-hour-value" })
        assertEquals(0, restored.size())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun entryLearnersUseTheFrozenLaneTargetOnceAndLaterSurvivorReadsCannotRewriteIt() {
        val cortex = com.lifecyclebot.engine.cortex.Cortex7885
        val oldPrefs = get(runtime, "prefs"); val oldStore = get(runtime, "rowStore8409"); val oldLoaded = get(cortex, "loaded")
        val prefs = org.mockito.Mockito.mock(android.content.SharedPreferences::class.java)
        val editor = org.mockito.Mockito.mock(android.content.SharedPreferences.Editor::class.java, org.mockito.Mockito.RETURNS_SELF)
        org.mockito.Mockito.`when`(prefs.edit()).thenReturn(editor)
        org.mockito.Mockito.`when`(editor.commit()).thenReturn(true)
        val cells = get(runtime, "cells") as MutableMap<String, ForwardReturnLabeler7731.Tally>
        val fresh = get(runtime, "fresh8169") as MutableMap<String, ForwardReturnLabeler7731.Tally>
        val nodes = get(CellAllocator7962, "nodes") as MutableMap<String, CellAllocator7962.Stat7962>
        val oldCells = HashMap(cells); val oldFresh = HashMap(fresh); val oldNodes = HashMap(nodes)
        val fine = get(ForwardOutcomeModel, "fine") as MutableMap<String, Any>
        val coarse = get(ForwardOutcomeModel, "coarse") as MutableMap<String, Any>
        val oldFine = HashMap(fine); val oldCoarse = HashMap(coarse)
        val outbox = get(runtime, "forwardOutbox8404")!!
        val rows = get(outbox, "rows") as MutableMap<String, String>
        val prefix = "frozen-entry-8521-"
        try {
            set(runtime, "prefs", prefs); set(runtime, "rowStore8409", null); set(cortex, "loaded", true)
            cells.clear(); fresh.clear(); nodes.clear(); fine.clear(); coarse.clear()
            val at = System.currentTimeMillis() - 14_400_000L
            for (lane in lanes) for (take in listOf(false, true)) {
                val mint = "$prefix$lane-$take"
                val cell = "TEST_FROZEN_8521|$lane|$take"
                // Each side has its own cell so a shared lane tally cannot hide an early contribution.
                val obs = observation(mint, lane, take, at, cell)
                val target = CortexForwardEpoch8521.targetHorizon(CortexForwardEpoch8521.targetKind(lane))
                set(obs, "targetHorizon8521", target); set(obs, "rebased8076", true)
                set(obs, "basis8386", LabelBasis8386(mark(mint, at + 5_000L), 100_000.0))
                set(obs, "lastMark8386", mark(mint, at + 300_000L, 1.2))
                assertEquals(true, call("bookSixty7944", obs, 17.0, 20.0, at + 300_000L))
                set(obs, "done60", true)
                if (target == 3_600_000L) {
                    assertTrue("$lane/$take has no five-minute entry contribution", (get(obs, "bookedNet7997") as Double).isNaN())
                    assertEquals(0, cells[cell]?.n60 ?: 0)
                    assertNull(nodes["L8527|CELL|$cell"])
                    set(obs, "lastMark8386", mark(mint, at + 3_605_000L, 1.7))
                    assertEquals(true, call("bookTwoForty7944", obs, 67.0, 70.0, true))
                }
                val fixed = get(obs, "bookedNet7997") as Double
                assertTrue("$lane/$take has its real target", fixed.isFinite())
                assertEquals(1, cells.getValue(cell).n60)
                assertEquals(fixed, cells.getValue(cell).sum60, 0.0)
                val alloc = nodes.getValue("L8527|CELL|$cell")
                assertEquals(1.0, alloc.w, 0.0)
                assertEquals(target / 60_000.0, alloc.holdSum / alloc.holdW, 0.0)
                set(obs, "lastMark8386", mark(mint, at + 14_400_000L, 3.0))
                call("checkpoint7997", obs, 197.0, 200.0, 14_400_000L, at + 14_400_000L)
                call("bookEntryTarget8521", obs, 197.0, 200.0, false, at + 14_400_000L)
                assertEquals("a later survivor is not the original entry target", fixed, get(obs, "bookedNet7997") as Double, 0.0)
                assertEquals(1, cells.getValue(cell).n60)
                assertEquals(fixed, cells.getValue(cell).sum60, 0.0)
                assertEquals(1.0, alloc.w, 0.0)
                assertEquals(fixed.coerceIn(-100.0, 300.0), alloc.sx, 0.0)
            }
            val missingMint = "${prefix}missing-interim"
            val missingCell = "TEST_FROZEN_8521|MOONSHOT|MISSING"
            val missing = observation(missingMint, "MOONSHOT", false, at, missingCell)
            set(missing, "targetHorizon8521", 3_600_000L); set(missing, "done60", true)
            set(missing, "interimUnavailable8521", true); set(missing, "rebased8076", true)
            set(missing, "basis8386", LabelBasis8386(mark(missingMint, at + 5_000L), 100_000.0))
            set(missing, "lastMark8386", mark(missingMint, at + 3_605_000L, 1.7))
            assertEquals(true, call("bookTwoForty7944", missing, 67.0, 70.0, true))
            assertTrue("the first hour can teach without inventing an intermediate outcome", (get(missing, "bookedNet7997") as Double).isFinite())
            assertEquals(1, cells.getValue(missingCell).n60)
            assertEquals(1.0, nodes.getValue("L8527|CELL|$missingCell").w, 0.0)
            assertFalse(get(missing, "firstReadValid8521") as Boolean)
            val noFillMint = "${prefix}no-fill"
            val noFillCell = "TEST_FROZEN_8521|TREASURY|NO_FILL"
            val noFill = observation(noFillMint, "TREASURY", false, at, noFillCell)
            // superseded by V5.0.8568 — the helper's decision mark is a 0 s-old CURVE_ACCOUNT print, which IS a buyable fill
            // (decisionChainFill8567). The no-fill case is now a decision priced by an aggregator with no rebase read.
            val apiBasis8568 = LabelBasis8386(LabelMark8386(noFillMint, "TEST", 1.0, at, at, "DEXSCREENER", decimals = 6), 100_000.0)
            set(noFill, "basis8386", apiBasis8568); set(noFill, "decisionBasis8386", apiBasis8568)
            set(noFill, "lastMark8386", mark(noFillMint, at + 300_000L, 2.0))
            assertEquals(true, call("bookSixty7944", noFill, 97.0, 100.0, at + 300_000L))
            assertTrue("a recovered outcome cannot invent an earlier executable fill", (get(noFill, "bookedNet7997") as Double).isNaN())
            assertEquals(0, cells[noFillCell]?.n60 ?: 0)
            assertNull(nodes["L8527|CELL|$noFillCell"])
        } finally {
            cells.clear(); cells.putAll(oldCells); fresh.clear(); fresh.putAll(oldFresh); nodes.clear(); nodes.putAll(oldNodes)
            fine.clear(); fine.putAll(oldFine); coarse.clear(); coarse.putAll(oldCoarse)
            set(runtime, "prefs", oldPrefs); set(runtime, "rowStore8409", oldStore); set(cortex, "loaded", oldLoaded)
            rows.keys.removeAll { it.startsWith(prefix) }
            for (name in listOf("durable", "acknowledged")) {
                (get(outbox, name) as MutableSet<String>).removeAll { it.startsWith(prefix) }
            }
        }
    }

    @Test fun allocatorMigrationPreservesActualLiveAndPaperNodesWithoutOldCounterfactualLabels() {
        val actualLive = "V|CELL|live=3.0,12.0,48.0,1.0,61.0,-0.2"
        val actualPaper = "P|LANE|TREASURY=2.0,-4.0,8.0,1.0,7.0,0.0"
        val oldForward = "L|LANE|MOONSHOT=100.0,9000.0,810000.0,0.0,0.0,0.0"
        assertEquals("$actualLive;$actualPaper", CellAllocator7962.realisedNodes8521("$oldForward;$actualLive;$actualPaper"))
        assertEquals("", CellAllocator7962.realisedNodes8521(oldForward))
        assertEquals(60.0, CellAllocator7962.labelHold8521("MOONSHOT"), 0.0)
        assertEquals(5.0, CellAllocator7962.labelHold8521("TREASURY"), 0.0)
    }

    @Test fun recoveredCandleCloseUsesTheActualEndOfTheBarWithoutClippingOrLookahead() {
        val boot = 1_800_000_000_000L
        val target = boot - 600_000L
        fun candle(at: Long, px: Double) = com.lifecyclebot.data.Candle(at, px, 0.0, 0.0, 0.0)
        val before = candle(target - 60_001L, 4.0)
        val closingAtTarget = candle(target - 60_000L, 5.0)
        val closingAfterTarget = candle(target - 30_000L, 6.0)
        assertNull(DowntimeRecovery8430.pick8430(listOf(before), target, target, target + 150_000L))
        assertNull(DowntimeRecovery8430.pick8430(listOf(closingAtTarget.copy(synthetic = true)), target, target, target + 150_000L))
        assertEquals(5.0, DowntimeRecovery8430.pick8430(listOf(closingAfterTarget, closingAtTarget), target,
            target, target + 150_000L)!!.priceUsd, 0.0)
        val mint = "candle-clock-8521"
        DowntimeRecovery8430.seedForTest8430(mint, listOf(closingAfterTarget))
        try {
            val read = DowntimeRecovery8430.candleMark8430(mint, target, target, target + 150_000L, boot, boot + 1_000L)
            assertFalse(read.waiting)
            assertEquals(target + 30_000L, read.mark!!.observedAtMs)
            assertEquals(6.0, read.mark!!.usd, 0.0)
            assertNull(DowntimeRecovery8430.pick8430(listOf(closingAfterTarget), target, target, target + 29_999L))
        } finally { DowntimeRecovery8430.seedForTest8430(mint, emptyList()) }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun restartPreservesEntryExitPathAndExactStagedProofInEveryLane() {
        val pending = get(runtime, "pending") as MutableMap<String, Any>
        val seen = get(runtime, "lastSeenAt") as MutableMap<String, Long>
        val late = get(runtime, "lateFirst8153") as MutableMap<String, Long>
        val tracking = get(runtime, "pendingMints8475") as MutableMap<String, Long>
        val saved = HashMap(pending); val savedSeen = HashMap(seen)
        val savedLate = HashMap(late); val savedTracking = HashMap(tracking)
        val now = System.currentTimeMillis(); val at = now - 330_000L
        try {
            pending.clear()
            for (lane in lanes) for (take in listOf(false, true)) {
                val mint = "restore-8521-$lane-$take"
                val obs = observation(mint, lane, take, at)
                set(obs, "rebased8076", true); set(obs, "honest8366", true); set(obs, "lateEntry8521", !take)
                set(obs, "targetHorizon8521", CortexForwardEpoch8521.targetHorizon(CortexForwardEpoch8521.targetKind(lane)))
                set(obs, "interimUnavailable8521", !take)
                set(obs, "firstReadValid8521", take)
                set(obs, "chase8094", 1.5); set(obs, "heldPeak8366", 36.0); set(obs, "heldPeak8071", 30.0)
                set(obs, "windowEnd8379", at + 100_000L); set(obs, "windowReleasedAtMs8418", at + 80_000L)
                set(obs, "windowReleaseGross8418", -12.0); set(obs, "bookedLongNet8080", 37.0); set(obs, "bookedLongGross8080", 40.0)
                set(obs, "nativeTrigger8468", true); set(obs, "gate8418", "TEST_GATE_8521")
                set(obs, "minPct7962", -20.0); set(obs, "dipBeforePeak7962", -20.0); set(obs, "peakPct", 40.0)
                (get(obs, "lows8366") as MutableList<DoubleArray>).add(doubleArrayOf((at + 70_000L).toDouble(), -20.0, (at + 60_000L).toDouble(), -10.0))
                (get(obs, "postLows8379") as MutableList<DoubleArray>).add(doubleArrayOf((at + 110_000L).toDouble(), -22.0, (at + 100_000L).toDouble(), -10.0))
                (get(obs, "recent8366") as ArrayDeque<DoubleArray>).addLast(doubleArrayOf((at + 310_000L).toDouble(), 40.0))
                val proofMark = mark(mint, at + 300_000L, 1.4)
                val proof = CortexOutcomeProof8395("$mint|$lane|$at", 300_000L, proofMark.observedAtMs, 37.0, 40.0,
                    mark(mint, at), proofMark)
                set(obs, "staged8418", doubleArrayOf(37.0, 40.0, 0.0, 1.0, 37.0, 40.0, 37.0, 40.0))
                set(obs, "stagedProof8521", proof)
                // The latest cached mark may change during retry. The first booked value retains its own older proof.
                set(obs, "lastMark8386", mark(mint, at + 320_000L, 0.9))
                pending["$mint|$lane"] = obs
            }
            val original = HashMap(pending)
            val encoded = call("encodePending7735", now, pending.values.toList()) as String
            assertTrue(encoded.startsWith("gzip8401:"))
            assertTrue(ForwardCheckpoint8401.unpack(encoded).contains("stagedProof8521"))
            pending.clear()
            assertEquals(lanes.size * 2, call("restorePending7735", encoded, now))
            for ((key, before) in original) {
                val after = requireNotNull(pending[key])
                for (name in listOf("rebased8076", "honest8366", "lateEntry8521", "targetHorizon8521", "interimUnavailable8521", "firstReadValid8521", "chase8094", "heldPeak8366", "heldPeak8071",
                    "windowEnd8379", "windowReleasedAtMs8418", "windowReleaseGross8418", "bookedLongNet8080", "bookedLongGross8080", "nativeTrigger8468", "gate8418")) {
                    assertEquals("$key preserves $name", get(before, name), get(after, name))
                }
                assertEquals(get(before, "basis8386"), get(after, "basis8386"))
                assertEquals(get(before, "stagedProof8521"), get(after, "stagedProof8521"))
                assertArrayEquals((get(before, "lows8366") as List<DoubleArray>).single(), (get(after, "lows8366") as List<DoubleArray>).single(), 0.0)
                assertArrayEquals((get(before, "postLows8379") as List<DoubleArray>).single(), (get(after, "postLows8379") as List<DoubleArray>).single(), 0.0)
                assertArrayEquals((get(before, "staged8418") as DoubleArray), (get(after, "staged8418") as DoubleArray), 0.0)
                val ladder: (Double, Double) -> Double = { _, rest -> rest }
                assertEquals("restart must reproduce the same executable stop result", call("playGross8366", before, 10.0, 40.0, ladder),
                    call("playGross8366", after, 10.0, 40.0, ladder))
                assertTrue("the restored coin remains subscribed to its own tape", ForwardReturnLabeler7731.isPendingMint8475(key.substringBefore('|'), now))
            }
        } finally {
            pending.clear(); pending.putAll(saved); seen.clear(); seen.putAll(savedSeen)
            late.clear(); late.putAll(savedLate); tracking.clear(); tracking.putAll(savedTracking)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun checkpointRetainsTheWholeAdmittedBookInsteadOfDroppingRowsAfterTwoThousand() {
        val pending = get(runtime, "pending") as MutableMap<String, Any>
        val saved = HashMap(pending)
        val now = System.currentTimeMillis()
        try {
            pending.clear()
            repeat(2_100) { i ->
                val lane = lanes[i % lanes.size]; val mint = "capacity-8521-$i"
                pending["$mint|$lane"] = observation(mint, lane, i % 2 == 0, now - 100_000L - i)
            }
            val expiredMint = "capacity-8521-expired-unacknowledged"
            val expired = observation(expiredMint, "MOONSHOT", false, now - 2 * 3_600_000L)
            set(expired, "targetHorizon8521", 3_600_000L); set(expired, "done60", true)
            pending["$expiredMint|MOONSHOT"] = expired
            val selected = call("selectedPending8401", now) as List<*>
            assertEquals(2_101, selected.size)
            assertTrue("expiry without a durable disposition must not erase ownership", selected.contains(expired))
            val encoded = call("encodePending7735", now, selected) as String
            val rowSep = get(runtime, "ROW_SEP_7735") as Char
            assertEquals(2_101, ForwardCheckpoint8401.unpack(encoded).split(rowSep).size)
            val expiredOnly = call("encodePending7735", now, listOf(expired)) as String
            pending.remove("$expiredMint|MOONSHOT")
            assertEquals(1, call("restorePending7735", expiredOnly, now))
            assertEquals(false, get(requireNotNull(pending["$expiredMint|MOONSHOT"]), "done240"))
            (get(runtime, "lastSeenAt") as MutableMap<String, Long>).remove("$expiredMint|MOONSHOT")
            (get(runtime, "pendingMints8475") as MutableMap<String, Long>).remove(expiredMint)
        } finally { pending.clear(); pending.putAll(saved) }
    }
}

