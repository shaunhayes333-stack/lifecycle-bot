package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybookCheckpoint8372
import com.lifecyclebot.engine.cortex.LaneSetupEpisodes8372
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8372LanePlaybookEpisodeTest {
    @Test fun exactOldTokenEpisodeDoesNotConsumeNewerPendingOrOtherLane() {
        val episodes = LaneSetupEpisodes8372()
        assertTrue(episodes.capture("token", "EXPRESS", 1_000L, "EXPRESS", "FIRST#V=1", "EARLY"))
        assertTrue(episodes.capture("token", "EXPRESS", 20_000L, "EXPRESS", "SECOND#V=2", "LATE"))
        assertTrue(episodes.capture("token", "QUALITY", 1_000L, "QUALITY", "QUALITY#V=3", "MID"))
        assertEquals("FIRST#V=1", episodes.take("token", "EXPRESS", 1_000L)!!.tag)
        assertEquals("SECOND#V=2", episodes.latestTag("token", "EXPRESS"))
        assertEquals("QUALITY#V=3", episodes.take("token", "QUALITY", 1_000L)!!.tag)
        assertNull(episodes.take("token", "EXPRESS", 0L))
        assertNull(episodes.take("token", "EXPRESS", 1_000L))
        assertEquals(1, episodes.pendingCount())
    }

    @Test fun selectedExitCorrectsOriginalScalpAndTimingContributionNotOtherSetup() {
        val episodes = LaneSetupEpisodes8372()
        episodes.capture("token", "EXPRESS", 1_000L, "EXPRESS", "SCALP;RUNNER#V=1", "EARLY")
        val primary = CortexLedger7885.Stat(); val other = CortexLedger7885.Stat(); val timing = CortexLedger7885.Stat()
        val a = primary.add(8.0, false); val b = other.add(50.0, true); val t = timing.add(8.0, false)
        val decision = episodes.take("token", "EXPRESS", 1_000L)!!
        assertTrue(episodes.record(decision, mapOf(
            "SCALP" to LaneSetupEpisodes8372.Contribution(a, 8.0, 10.0, t),
            "RUNNER" to LaneSetupEpisodes8372.Contribution(b, 50.0, 55.0),
        )))
        primary.scale(0.8); timing.scale(0.7)
        assertTrue(episodes.revise("token", "EXPRESS", 1_000L, -5.0, -2.0, true, { true },
            { _, setup -> if (setup == "SCALP") primary else other }, { _, _, _ -> timing }))
        assertEquals(-4.0, primary.sum, 1e-9)
        assertEquals(0.8, primary.n, 1e-9)
        assertEquals(-3.5, timing.sum, 1e-9)
        assertEquals(0.7, timing.n, 1e-9)
        assertEquals(50.0, other.sum, 0.0)
        assertFalse(episodes.revise("token", "EXPRESS", 1_000L, 1_000.0, 1_003.0, false, { true },
            { _, setup -> if (setup == "SCALP") primary else other }, { _, _, _ -> timing }))
        assertTrue(episodes.revise("token", "EXPRESS", 1_000L, -5.0, -2.0, true, { true },
            { _, setup -> if (setup == "SCALP") primary else other }, { _, _, _ -> timing }))
        assertEquals(-4.0, primary.sum, 1e-9)
        assertEquals(50.0, other.sum, 0.0)
    }

    @Test fun allCompactExactTracesAndTerminalFlagSurviveRestart() {
        val episodes = LaneSetupEpisodes8372()
        val book = CortexLedger7885.Stat()
        repeat(1_600) { i ->
            val at = 1_000L + i
            episodes.capture("mint-$i", "MOONSHOT", at, "MOONSHOT", "IGNITION#V=$i", "EARLY")
            val stamp = book.add(-10.0, false)
            episodes.record(episodes.take("mint-$i", "MOONSHOT", at)!!,
                mapOf("IGNITION" to LaneSetupEpisodes8372.Contribution(stamp, -10.0, -7.0)))
        }
        assertTrue(episodes.revise("mint-0", "MOONSHOT", 1_000L, 30.0, 33.0, true, { true }, { _, _ -> book }, { _, _, _ -> null }))
        episodes.capture("pending", "QUALITY", 5_000L, "QUALITY", "DIP#V=20", "MID")
        val grades = episodes.gradesSnapshot().toString()
        val restored = LaneSetupEpisodes8372()
        restored.restoreGrades(org.json.JSONArray(grades), 10_000L)
        restored.restorePending(JSONObject(episodes.pendingSnapshot().toString()), 10_000L)
        assertEquals(1_600, restored.gradeCount())
        assertEquals("MID", restored.pending("pending", "QUALITY", 5_000L)!!.timing)
        assertTrue(restored.grade("mint-0", "MOONSHOT", 1_000L)!!.terminal)
        assertEquals(30.0, restored.grade("mint-0", "MOONSHOT", 1_000L)!!.contributions["IGNITION"]!!.net, 0.0)
        assertEquals(-10.0, restored.grade("mint-1599", "MOONSHOT", 2_599L)!!.contributions["IGNITION"]!!.net, 0.0)
    }

    @Test fun sidecarDoesNotConsumeRewriteOrInferIdsFromLegacyLearningBlob() {
        val legacy = JSONObject().put("EXPRESS", JSONObject().put("IGNITION", "79,321.25,5138.0"))
            .put("_grades8077", org.json.JSONArray().put(JSONObject().put("k", "token|EXPRESS").put("at", 40_000L)))
        val pending = JSONObject().put("token|EXPRESS", "EXPRESS|IGNITION;BREAKOUT#V=old")
        val beforeBooks = legacy.toString(); val beforePending = pending.toString()
        val episodes = LaneSetupEpisodes8372()
        episodes.restorePending(pending, 100_000L)
        episodes.restoreGrades(legacy.optJSONArray("_grades8077"), 100_000L)
        assertEquals(beforeBooks, legacy.toString())
        assertEquals(beforePending, pending.toString())
        assertEquals(0, episodes.gradeCount())
        assertEquals(0, episodes.pendingCount())
        assertEquals(1L, episodes.ignoredLegacy)
        assertNull("Legacy grade time is not an invented decision identity", episodes.grade("token", "EXPRESS", 40_000L))
    }

    @Test fun forwardRevisionTouchesOnlyOriginalLongSetupWithStoredFamilyValue() {
        val episodes = LaneSetupEpisodes8372()
        val longBook = CortexLedger7885.Stat(); val scalpBook = CortexLedger7885.Stat()
        episodes.capture("token", "MOONSHOT", 1_000L, "MOONSHOT", "LONG;SCALP#V=1", "EARLY")
        val ls = longBook.add(35.0, true); val ss = scalpBook.add(-5.0, false)
        episodes.record(episodes.take("token", "MOONSHOT", 1_000L)!!, mapOf(
            "LONG" to LaneSetupEpisodes8372.Contribution(ls, 35.0, 38.0),
            "SCALP" to LaneSetupEpisodes8372.Contribution(ss, -5.0, -2.0),
        ))
        assertTrue(episodes.revise("token", "MOONSHOT", 1_000L, 60.0, 63.0, false, { it == "LONG" },
            { _, setup -> if (setup == "LONG") longBook else scalpBook }, { _, _, _ -> null }))
        assertEquals(60.0, longBook.sum, 0.0)
        assertEquals(-5.0, scalpBook.sum, 0.0)
        assertEquals(1.0, longBook.n, 0.0)
        assertFalse(episodes.revise("token", "MOONSHOT", 2_000L, -90.0, -87.0, false, { true },
            { _, _ -> longBook }, { _, _, _ -> null }))
        assertEquals(60.0, longBook.sum, 0.0)
    }

    @Test fun registeredUncapturedShadowHasNamedDurableDispositionWithoutAnyReward() {
        val episodes = LaneSetupEpisodes8372()
        assertFalse("Unknown delivery must retry", episodes.disposeUncaptured("token", "EXPRESS", 1_000L, "NO_SETUP_CAPTURE_8372"))
        assertTrue(episodes.observe("token", "EXPRESS", 1_000L))
        assertTrue(episodes.disposeUncaptured("token", "EXPRESS", 1_000L, "NO_SETUP_CAPTURE_8372"))
        assertEquals(0, episodes.gradeCount())
        assertFalse("Never reconstruct a setup after its outcome", episodes.capture("token", "EXPRESS", 1_000L,
            "EXPRESS", "WINNER_IN_HINDSIGHT", "LATE"))
        val restored = LaneSetupEpisodes8372()
        restored.restoreObservations(org.json.JSONArray(episodes.observationsSnapshot().toString()), 10_000L)
        assertEquals("NO_SETUP_CAPTURE_8372", restored.disposition("token", "EXPRESS", 1_000L))
        assertEquals(0, restored.gradeCount())
    }

    @Test fun inFlightForwardGradeCannotBeAcknowledgedAsMissingCaptureAndRestoresPending() {
        val episodes = LaneSetupEpisodes8372()
        episodes.capture("token", "MOONSHOT", 1_000L, "MOONSHOT", "IGNITION#V=1", "EARLY")
        val p = episodes.take("token", "MOONSHOT", 1_000L)!!
        assertTrue(episodes.inFlight("token", "MOONSHOT", 1_000L))
        assertFalse(episodes.disposeUncaptured("token", "MOONSHOT", 1_000L, "NO_SETUP_CAPTURE_8372"))
        val restored = LaneSetupEpisodes8372()
        restored.restorePending(JSONObject(episodes.pendingSnapshot().toString()), 10_000L)
        assertEquals(p, restored.pending("token", "MOONSHOT", 1_000L))
        assertFalse(restored.inFlight("token", "MOONSHOT", 1_000L))
        assertNull(restored.disposition("token", "MOONSHOT", 1_000L))
    }

    @Test fun failedCommitNeverAcknowledgesAndRetryKeepsSingleOriginalContribution() {
        val episodes = LaneSetupEpisodes8372()
        val checkpoint = LanePlaybookCheckpoint8372()
        val book = CortexLedger7885.Stat()
        episodes.capture("token", "EXPRESS", 1_000L, "EXPRESS", "SCALP#V=1", "EARLY")
        episodes.record(episodes.take("token", "EXPRESS", 1_000L)!!,
            mapOf("SCALP" to LaneSetupEpisodes8372.Contribution(book.add(10.0, false), 10.0, 13.0)))
        val durable = HashMap<String, String>()
        var failCommit = true
        var changed = 0
        val receipt = "receipt|token|EXPRESS|1000"
        fun deliver(): Boolean = checkpoint.terminal(receipt,
            { if (durable.containsKey(it)) LanePlaybookCheckpoint8372.Receipt.COMMITTED else LanePlaybookCheckpoint8372.Receipt.MISSING },
            {
                if (!episodes.grade("token", "EXPRESS", 1_000L)!!.terminal) changed++
                episodes.revise("token", "EXPRESS", 1_000L, -5.0, -2.0, true,
                    { true }, { _, _ -> book }, { _, _, _ -> null })
            },
            { mapOf("book" to book.encode(), "episodes" to episodes.gradesSnapshot().toString()) },
            { key, blobs -> if (failCommit) false else { durable.putAll(blobs); durable[key] = "committed"; true } })
        assertFalse(deliver())
        assertTrue(episodes.grade("token", "EXPRESS", 1_000L)!!.terminal)
        assertTrue(durable.isEmpty())
        assertEquals(1.0, book.n, 0.0)
        assertEquals(-5.0, book.sum, 0.0)
        failCommit = false
        assertTrue(deliver())
        assertTrue(deliver())
        assertEquals(1, changed)
        assertEquals(1.0, book.n, 0.0)
        val restored = LaneSetupEpisodes8372()
        restored.restoreGrades(org.json.JSONArray(durable.getValue("episodes")), 2_000L)
        assertTrue(restored.grade("token", "EXPRESS", 1_000L)!!.terminal)
        assertEquals(-5.0, restored.grade("token", "EXPRESS", 1_000L)!!.contributions.getValue("SCALP").net, 0.0)
        val restarted = LanePlaybookCheckpoint8372()
        assertTrue(restarted.terminal(receipt, { LanePlaybookCheckpoint8372.Receipt.COMMITTED },
            { error("A durable receipt must not apply the reward again") }, { emptyMap() }, { _, _ -> false }))
    }

    @Test fun ordinarySaveSnapshotsAfterTerminalCommitNotBeforeIt() {
        val checkpoint = LanePlaybookCheckpoint8372()
        val commitEntered = java.util.concurrent.CountDownLatch(1)
        val releaseCommit = java.util.concurrent.CountDownLatch(1)
        val snapshotRead = java.util.concurrent.CountDownLatch(1)
        val state = java.util.concurrent.atomic.AtomicInteger(1)
        val writes = java.util.Collections.synchronizedList(ArrayList<Int>())
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val terminal = Thread {
            try {
                assertTrue(checkpoint.terminal("exact-receipt", { LanePlaybookCheckpoint8372.Receipt.MISSING },
                    { true }, { mapOf("state" to state.get().toString()) }, { _, blobs ->
                        commitEntered.countDown()
                        assertTrue(releaseCommit.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        writes.add(blobs.getValue("state").toInt()); true
                    }))
            } catch (e: Throwable) { failure.set(e) }
        }
        val ordinary = Thread {
            try { checkpoint.save({ snapshotRead.countDown(); mapOf("state" to state.get().toString()) }) {
                writes.add(it.getValue("state").toInt())
            } } catch (e: Throwable) { failure.set(e) }
        }
        terminal.start()
        assertTrue(commitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        ordinary.start()
        try {
            assertFalse(snapshotRead.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            state.set(2)
        } finally { releaseCommit.countDown() }
        terminal.join(5_000L); ordinary.join(5_000L)
        assertFalse(terminal.isAlive); assertFalse(ordinary.isAlive)
        failure.get()?.let { throw AssertionError("Checkpoint thread failed", it) }
        assertEquals(listOf(1, 2), writes.toList())
    }

    @Test fun unreadReceiptOrEmptyCheckpointNeverAcknowledges() {
        val checkpoint = LanePlaybookCheckpoint8372()
        assertFalse(checkpoint.terminal("receipt", { LanePlaybookCheckpoint8372.Receipt.RETRY },
            { error("Unread receipt cannot mutate the model") }, { emptyMap() }, { _, _ -> true }))
        assertFalse(checkpoint.terminal("receipt", { LanePlaybookCheckpoint8372.Receipt.MISSING },
            { true }, { emptyMap() }, { _, _ -> error("Missing model state cannot commit a receipt") }))
    }
}
