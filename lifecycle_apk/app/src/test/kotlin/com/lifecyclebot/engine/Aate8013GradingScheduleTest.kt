package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8013GradingScheduleTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val h = 3_600_000L

    @Test fun theOwnersScheduleAndBeyond() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        // V5.0.8040 — owner's schedule: 1 m / 5 m labels, then 1 h, 4 h, 24 h and beyond for coins worth watching.
        // superseded by V5.0.8357 — owner's schedule restores the 15-minute checkpoint ahead of the hour (1m, 5m, 15m, 1h, 4h, 24h, then 48h/72h/7d on tokenomics)
        // superseded by V5.0.8416 — owner: 5m, 15m, 30m, 1h, 4h, 24h; from 30m only coins worth following. Indices 0=15m 1=30m 2=1h 3=4h 4=24h
        assertTrue(f.contains("longArrayOf(15L * 60_000L, 30L * 60_000L, 60L * 60_000L, 4L * 3_600_000L, 24L * 3_600_000L)"))
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(0, 1 * h))
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(4, 20 * h))      // the 4 h checkpoint passed, 24 h next
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(5, 2 * h))      // schedule finished
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(4, 30 * h))     // past the 24 h checkpoint's grace
    }

    @Test fun followedCoinsSurviveRestartsAndLeavingTheWatchlist() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        // 8521: age alone cannot remove unfinished work. Followed coins have persistence priority;
        // only a durable terminal disposition transfers ownership away from the pending snapshot.
        val selectionStart = f.indexOf("private fun selectedPending8401(")
        val encodingStart = f.indexOf("private fun encodePending7735(", selectionStart)
        assertTrue(selectionStart >= 0 && encodingStart > selectionStart)
        val selection = f.substring(selectionStart, encodingStart)
        assertTrue(selection.contains(".filterNot { terminalOutbox8395.containsKey(terminalKey8395(it)) }"))
        assertTrue(selection.contains("compareByDescending<Obs> { followed8013(it, nowMs) }.thenByDescending { it.atMs }"))
        assertFalse("pending ownership cannot expire just because a checkpoint is being saved", selection.contains("H240_MS_7731"))
        assertTrue(f.contains("val selected = selectedPending8401(now)"))
        assertTrue(f.contains("encodePending7735(now, selected)"))
        assertTrue(f.contains("if (f[15] == \"1\" && nowMs - atMs > H240_MS_7731 + LOST_GRACE_MS_7731 && !scheduleOpen8013(ck8013, nowMs - atMs)) return@forEach"))
        // superseded by V5.0.8291 — the checkpoint still asks for an off-watch mark, its due time now carries the off-watch rank.
        assertTrue(f.contains("else if (checkpointDue8013(o, age)) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(rank8291, o.atMs + CK_MS_7997[o.ck7997]))"))
        // superseded by V5.0.8395: a followed coin's bounded deadline is its last checkpoint (R2), and a 240 booking keeps it while followed.
        // superseded by V5.0.8397 — the 240 booking moved into completeHour8397 (recovered and on-time hours share continuation
        // rules); restored V5.0.8418 (42d5f61fd had turned the 8395 note into a plain comment).
        assertTrue(f.contains("else -> CK_MS_7997.last() + LOST_GRACE_MS_7731"))
        val recoveryStart = f.indexOf("private fun recoveredHorizon8397(")
        val hourStart = f.indexOf("private fun completeHour8397(")
        val expiryStart = f.indexOf("private fun expired8395(")
        assertTrue(recoveryStart >= 0 && hourStart > recoveryStart && expiryStart > hourStart)
        val recovery = f.substring(recoveryStart, hourStart)
        val hour = f.substring(hourStart, expiryStart)
        assertTrue(f.contains("recoveredHorizon8397(key, o, recovered, nowMs)"))
        assertTrue(recovery.contains("completeHour8397(key, o, net, gross, stop, bookable = true)"))
        assertTrue(f.contains("completeHour8397(key, o, net, gross, stop7997, horizonOpen7809(observedAge8521, hourTargetMs8592))"))   // V5.0.8592 — hour lanes complete at their 15m target
        assertTrue(hour.contains("val follow = !stop && keepRunning7997(o, gross)"))
        assertTrue(hour.contains("bookTwoForty7944(o, net, gross, deferExit8006 = follow)"))
        assertTrue(hour.contains("if (!follow) pending.remove(key, o) else extended7997.incrementAndGet()"))
        assertTrue(f.contains("o.feats7972 = f[30].split('\\u0002').filter { it.isNotBlank() }"))
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("append(\",\\\"g8013\\\":\").append(garr.append(']'))"))
        assertTrue(c.contains("restoreGraded8013(root, dict, nowMs)"))
    }
}
