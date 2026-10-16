package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8144 — Runtime: the service bootstrap no longer dies right after CORE_STORES_READY on the
 * 253-register BotConfig copy inside TreasuryWalletManager's synchronized methods; every phase is
 * timed and a stall prints the stuck call; the smoke names the failing step and rejected class;
 * the UI and the main-thread frame watcher read bounded snapshots instead of walking deques.
 */
class Aate8144RuntimeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── bootstrap phase timing + stall witness ─────────────────────────────────────────────

    @Test fun each_phase_records_the_step_that_just_ended() {
        val t = RuntimeBootstrapWatch8144.Tracker8144()
        val first = t.onPhase("CANONICAL_READY", 1_000L)
        assertEquals("", first.previous)
        assertEquals(0L, first.stepMs)
        val second = t.onPhase("CORE_STORES_READY", 1_500L)
        assertEquals("CANONICAL_READY", second.previous)
        assertEquals(500L, second.stepMs)
        assertEquals("CORE_STORES_READY", t.phase)
        assertFalse(t.terminal)
    }

    @Test fun stall_reports_at_20s_then_each_doubling_and_reset_on_progress() {
        val t = RuntimeBootstrapWatch8144.Tracker8144()
        t.onPhase("CORE_STORES_READY", 0L)
        assertNull(t.stallDue(19_999L))
        assertEquals(20_000L, t.stallDue(20_000L))
        assertNull(t.stallDue(25_000L))
        assertEquals(40_000L, t.stallDue(40_000L))
        assertNull(t.stallDue(79_999L))
        assertEquals(80_000L, t.stallDue(80_000L))
        t.onPhase("LEARNING_AND_EXECUTION_STORES_READY", 90_000L)
        assertNull(t.stallDue(100_000L))
        assertEquals(20_000L, t.stallDue(110_000L))
    }

    @Test fun terminal_phase_stops_stall_reports_and_failure_keeps_the_working_phase() {
        val t = RuntimeBootstrapWatch8144.Tracker8144()
        t.onPhase("CORE_STORES_READY", 0L)
        t.onPhase("SERVICE_BOOTSTRAP_FAILED", 12L)
        assertTrue(t.terminal)
        assertNull(t.stallDue(1_000_000L))
        assertEquals("CORE_STORES_READY", t.workingPhase)
        assertEquals(0L, t.workingPhaseAtMs)
        val ok = RuntimeBootstrapWatch8144.Tracker8144()
        ok.onPhase("TRADER_ENGINES_STARTED", 0L)
        ok.onPhase("AUXILIARY_FEEDS_READY", 5L)
        assertNull(ok.stallDue(1_000_000L))
    }

    @Test fun synthetic_8192_phase_input_stays_bounded_and_fast() {
        val t = RuntimeBootstrapWatch8144.Tracker8144()
        val started = System.nanoTime()
        var now = 0L
        for (i in 0 until 8192) {
            now += if (i == 8190) 9_000L else 3L
            t.onPhase("PHASE_$i", now)
            t.stallDue(now)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(RuntimeBootstrapWatch8144.MAX_STEPS_8144, t.steps().size)
        assertEquals("PHASE_8191", t.steps().last().phase)
        val slowest = t.slowest(1).single()
        assertEquals("PHASE_8190", slowest.phase)
        assertEquals(9_000L, slowest.stepMs)
        assertTrue("8192 phases took ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    @Test fun verifier_message_names_the_rejected_class() {
        val msg = "Verifier rejected class com.lifecyclebot.engine.TreasuryWalletManager: void " +
            "com.lifecyclebot.engine.TreasuryWalletManager.init(android.content.Context) failed to verify: " +
            "[0x5A] copy1 v3<-v257 type=Undefined cat=1"
        assertEquals("com.lifecyclebot.engine.TreasuryWalletManager", RuntimeBootstrapWatch8144.rejectedClass8144(msg))
        assertEquals("com.lifecyclebot.engine.TreasuryWalletManager",
            RuntimeBootstrapWatch8144.rejectedClass8144("com.lifecyclebot.engine.TreasuryWalletManager"))
        assertEquals("", RuntimeBootstrapWatch8144.rejectedClass8144(null))
        assertEquals("", RuntimeBootstrapWatch8144.rejectedClass8144("canonical bootstrap unavailable"))
    }

    @Test fun failure_and_stall_lines_name_the_step_and_app_frames_first() {
        val frames = listOf(
            "java.lang.Thread.run(Thread.java:923)",
            "com.lifecyclebot.engine.BotService\$onCreate\$2.invokeSuspend(BotService.kt:3930)",
            "kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)",
        )
        assertEquals(listOf(frames[1]), RuntimeBootstrapWatch8144.appFrames8144(frames))
        assertEquals(listOf("a", "b"), RuntimeBootstrapWatch8144.appFrames8144(listOf("a", "b", "c"), 2))
        val fail = RuntimeBootstrapWatch8144.failureLine8144(
            "CORE_STORES_READY", 40L, "VerifyError",
            "Verifier rejected class com.lifecyclebot.engine.TreasuryWalletManager: x", frames,
        )
        assertTrue(fail.startsWith("SERVICE_BOOTSTRAP_FAILED_STEP_8144 phase=CORE_STORES_READY sincePhaseMs=40 type=VerifyError"))
        assertTrue(fail.contains("rejected=com.lifecyclebot.engine.TreasuryWalletManager"))
        assertTrue(fail.contains("at=com.lifecyclebot.engine.BotService\$onCreate\$2.invokeSuspend(BotService.kt:3930)"))
        val stall = RuntimeBootstrapWatch8144.stallLine8144("CORE_STORES_READY", 20_004L, "AATE-Service 6647", "BLOCKED", frames)
        assertTrue(stall.startsWith("SERVICE_BOOTSTRAP_STALL_8144 phase=CORE_STORES_READY stalledMs=20004 thread=AATE-Service_6647 state=BLOCKED at="))
        val step = RuntimeBootstrapWatch8144.stepLine8144(RuntimeBootstrapWatch8144.Step8144("CORE_STORES_READY", "", 0L), "t")
        assertEquals("SERVICE_BOOTSTRAP_STEP_8144 phase=CORE_STORES_READY after=START stepMs=0 thread=t", step)
    }

    // ── root cause: no 253-register BotConfig copy under TreasuryWalletManager's monitor ──

    @Test fun treasury_key_write_lives_outside_the_synchronized_methods() {
        val treasury = src("engine/TreasuryWalletManager.kt")
        val store = src("engine/RuntimeTreasuryKeyStore8144.kt")
        assertFalse(treasury.contains(".copy("))
        assertTrue(treasury.contains("RuntimeTreasuryKeyStore8144.persist8144(ctx, cfg, keyB58)"))
        assertTrue(treasury.contains("RuntimeTreasuryKeyStore8144.persist8144(ctx, cfg, privateKeyB58)"))
        assertTrue(store.contains("ConfigStore.save(ctx, cfg.copy(treasuryPrivateKeyB58 = keyB58))"))
        assertFalse(store.contains("@Synchronized"))
    }

    @Test fun botconfig_copy_default_stays_within_the_255_register_call_limit() {
        val text = src("data/BotConfig.kt")
        val open = text.indexOf("data class BotConfig(") + "data class BotConfig(".length
        var depth = 1
        var i = open
        while (depth > 0) {
            when (text[i]) { '(' -> depth++; ')' -> depth-- }
            i++
        }
        val body = text.substring(open, i - 1)
            .replace(Regex("//[^\n]*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val types = Regex("\\b(?:val|var)\\s+\\w+\\s*:\\s*([\\w.]+)").findAll(body).map { it.groupValues[1] }.toList()
        val words = types.sumOf { if (it == "Double" || it == "Long") 2 else 1 }
        val masks = (types.size + 31) / 32
        val copyDefaultWords = 1 + words + masks + 1
        assertTrue("BotConfig.copy\$default needs $copyDefaultWords argument registers (hard limit 255)", copyDefaultWords <= 255)
    }

    // ── wiring ─────────────────────────────────────────────────────────────────────────────

    @Test fun every_phase_and_failure_reaches_the_watchdog_and_the_smoke_reads_it() {
        assertTrue(src("engine/CanonicalBootstrapTimeline7557.kt").contains("RuntimeBootstrapWatchdog8144.observe(phase)"))
        assertTrue(src("engine/BotService.kt").contains("RuntimeBootstrapWatchdog8144.failure(t)"))
        val smoke = File("../../ci/runtime-test.sh").readText()
        assertTrue(smoke.contains("runtime_bootstrap_witness.py\" failed"))
        assertTrue(smoke.contains("runtime_bootstrap_witness.py\" annotate"))
        assertTrue(smoke.contains("SERVICE_BOOTSTRAP_(STEP|STALL|FAILED_STEP|SLOWEST)_8144"))
    }

    // ── UI / forensic read cost ────────────────────────────────────────────────────────────

    @Test fun tick_ring_counts_a_window_like_the_30_sample_deque_did() {
        val ring = RuntimeTickRing8144(30)
        assertEquals(0, ring.countInWindow(0L, Long.MAX_VALUE))
        val all = (0 until 100).map { it * 250L }
        all.forEach { ring.record(it) }
        assertEquals(30, ring.size())
        val last30 = all.takeLast(30)
        for ((from, to) in listOf(24_000L to 24_750L, 0L to 10_000L, 17_000L to 30_000L, 20_100L to 20_400L)) {
            assertEquals(last30.count { it in from..to }, ring.countInWindow(from, to))
        }
        assertEquals(4, ring.countInWindow(24_000L, 24_750L))
    }

    @Test fun rate_gate_admits_once_per_interval_and_counts_the_rest() {
        val gate = RuntimeRateGate8144(10_000L)
        assertTrue(gate.admit(0L))
        assertFalse(gate.admit(5_000L))
        assertFalse(gate.admit(9_999L))
        assertTrue(gate.admit(10_000L))
        assertEquals(2L, gate.takeSuppressed())
        assertEquals(0L, gate.takeSuppressed())
    }

    @Test fun badges_read_by_key_and_name_the_tightest_funnel_step() {
        val phases = mapOf("SCAN_CB" to 0L, "EXEC" to 5L, "INTAKE" to 100L, "SAFETY" to 80L, "V3" to 40L, "LANE_EVAL" to 30L, "FDG" to 6L)
        val labels = mapOf("BOT_LOOP_TICK" to 77L, "TRADEJRNL_REC" to 3L)
        val b = RuntimePipelineBadges8144.read8144({ phases[it] ?: 0L }, { labels[it] ?: 0L }, 2, 900L)
        assertEquals(77L, b.loop)
        assertEquals(5L, b.exec)
        assertEquals(3L, b.journal)
        assertEquals(2, b.anrHints)
        assertEquals(900L, b.maxFrameGapMs)
        assertTrue(longArrayOf(100L, 80L, 40L, 30L, 6L, 5L).contentEquals(b.funnel))
        assertEquals(4 to 20.0, RuntimePipelineBadges8144.tightest8144(b.funnel))
        assertEquals(-1 to 101.0, RuntimePipelineBadges8144.tightest8144(LongArray(6)))
        val withScan = RuntimePipelineBadges8144.read8144({ if (it == "SCAN_CB") 12L else 0L }, { 99L }, 0, 0L)
        assertEquals(12L, withScan.loop)
        assertEquals(b, RuntimePipelineBadges8144.read8144({ phases[it] ?: 0L }, { labels[it] ?: 0L }, 2, 900L))
    }

    @Test fun periodic_readers_no_longer_copy_the_full_snapshot() {
        val collector = src("engine/PipelineHealthCollector.kt")
        assertFalse(collector.contains("stackRing.count {"))
        assertTrue(collector.contains("tickRing8144.countInWindow(gapStart, nowMs7109)"))
        val main = src("ui/MainActivity.kt")
        val tile = main.substringAfter("private val pipelineTileRefresh = object : Runnable").substringBefore("override fun onStop()")
        assertFalse(tile.contains("PipelineHealthCollector.snapshot()"))
        assertTrue(main.contains("SentienceOrchestrator.latestReflection8144()"))
        assertTrue(main.contains("composeLearningStats8144(paper)"))
        val health = src("ui/PipelineHealthActivity.kt")
        assertTrue(health.contains("PipelineHealthCollector.badges8144()"))
        assertTrue(health.contains("THREAD_PRIORITY_BACKGROUND"))
        assertTrue(src("engine/LiveLaneFanoutPressure.kt").contains("PipelineHealthCollector.phaseView8144(\"INTAKE\", \"LANE_EVAL\", \"EXEC\")"))
    }
}
