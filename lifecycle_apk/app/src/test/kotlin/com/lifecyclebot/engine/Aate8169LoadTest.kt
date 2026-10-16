package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8169 §COMPUTE_LOAD — fan-out is measured per candidate cycle, lane-gate skips are counted by name, Helius calls
 * are attributed to their caller with repeated reads flagged, and the per-mint caches nobody pruned are bounded to
 * what a reader can still be served (behaviour-preserving) and counted in the memory census.
 */
class Aate8169LoadTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── lane fan-out ──

    @Test fun fanoutIsPerCandidateCycleNotPerIntake() {
        // 5.0.8168: LANE_EVAL=7,439 SCAN_CB=7,230 INTAKE=388 -> 1.03 lanes a cycle (the report said 19.00).
        val perCycle = LoadTelemetry8169.lanesPerCandidateCycle8169(7_439, 7_230, 388)
        assertEquals(1.03, perCycle, 0.01)
        assertEquals(18.6, LoadTelemetry8169.cyclesPerIntake8169(7_230, 388), 0.1)
        // No cycle counter (old snapshots, tests): the intake denominator, as before.
        assertEquals(13.0, LoadTelemetry8169.lanesPerCandidateCycle8169(130, 0, 10), 1e-9)
        assertEquals(0.0, LoadTelemetry8169.lanesPerCandidateCycle8169(130, 0, 0), 1e-9)
        assertEquals(0.0, LoadTelemetry8169.cyclesPerIntake8169(10, 0), 1e-9)
        // A real explosion still reads as one: 12 lanes on every cycle.
        assertTrue(LoadTelemetry8169.lanesPerCandidateCycle8169(12_000, 1_000, 50) > 11.9)
    }

    @Test fun laneSkipsAreNamed() {
        assertEquals("LANE_QUARANTINE_6002", LoadTelemetry8169.laneSkipReason8169("LANE_QUARANTINE_6002", "MANIPULATED", "SHITCOIN"))
        assertEquals("SNIPER_NOT_DESIGNATED_POOL_6599",
            LoadTelemetry8169.laneSkipReason8169("SNIPER_NOT_DESIGNATED_POOL_6599:NO_DESK_HYPOTHESIS", "PROJECT_SNIPER", "MOONSHOT"))
        assertEquals("PRIMARY_GATED_UNNAMED", LoadTelemetry8169.laneSkipReason8169(null, "moonshot", "MOONSHOT"))
        assertEquals("NON_OWNER_NOT_RESCUE", LoadTelemetry8169.laneSkipReason8169("  ", "QUALITY", "MOONSHOT"))
    }

    @Test fun laneGateTimingNeverChangesTheVerdict() {
        assertTrue(LoadTelemetry8169.timedLaneGate8169("mintA", "MOONSHOT", "MOONSHOT") { true })
        assertFalse(LoadTelemetry8169.timedLaneGate8169("mintA", "QUALITY", "MOONSHOT") { false })
        val line = LoadTelemetry8169.statusLine8169()
        assertTrue(line, line.contains("lanes8169[perCycle="))
        assertTrue(line, line.contains("skippedBy{"))
        assertTrue(line, line.contains("helius8169 session["))
    }

    // ── Helius by caller ──

    @Test fun callerIsTheFirstAppFrameBelowTheHttpPlumbing() {
        val frames = listOf(
            "java.lang.Thread", "com.lifecyclebot.engine.LoadTelemetry8169",
            "com.lifecyclebot.network.HeliusCreditMeterInterceptor7881", "okhttp3.internal.http.RealInterceptorChain",
            "com.lifecyclebot.network.SharedHttpClient\$base\$1", "com.lifecyclebot.network.HostCircuitInterceptor",
            "okhttp3.internal.connection.RealCall", "com.lifecyclebot.engine.HealthAwareHttp",
            "com.lifecyclebot.engine.truth.OnChainHolderConcentration7379\$rpc\$1", "com.lifecyclebot.engine.TokenSafetyChecker",
        )
        assertEquals("OnChainHolderConcentration7379", LoadTelemetry8169.callerOf8169(frames, "safety-1"))
        assertEquals("async:OkHttpDispatcher", LoadTelemetry8169.callerOf8169(listOf("okhttp3.internal.connection.RealCall\$AsyncCall"), "OkHttp Dispatcher-3"))
        assertEquals("async:thread", LoadTelemetry8169.callerOf8169(emptyList(), "42"))
    }

    @Test fun readKeysIdentifyOneMethodOnOneAccount() {
        val mint = "So11111111111111111111111111111111111111112"
        assertEquals("v0tx:$mint", LoadTelemetry8169.readKey8169("/v0/addresses/$mint/transactions", emptyList(), ""))
        assertEquals("getTokenSupply:$mint", LoadTelemetry8169.readKey8169("/", listOf("getTokenSupply"),
            """{"jsonrpc":"2.0","id":1,"method":"getTokenSupply","params":["$mint",{"commitment":"confirmed"}]}"""))
        assertNull(LoadTelemetry8169.readKey8169("/", listOf("getTokenSupply", "getBalance"), "{}"))   // batch
        assertNull(LoadTelemetry8169.readKey8169("/", listOf("sendTransaction"), """{"method":"sendTransaction","params":["$mint"]}"""))
        assertNull(LoadTelemetry8169.readKey8169("/", listOf("getLatestBlockhash"), """{"method":"getLatestBlockhash","params":[]}"""))
        assertNull(LoadTelemetry8169.readKey8169("/", listOf("getMultipleAccounts"), """{"method":"getMultipleAccounts","params":[["$mint"]]}"""))
        assertTrue(LoadTelemetry8169.isDuplicate8169(1_000L, 9_000L))
        assertFalse(LoadTelemetry8169.isDuplicate8169(1_000L, 12_000L))
        assertFalse(LoadTelemetry8169.isDuplicate8169(null, 1_000L))
        assertFalse(LoadTelemetry8169.isDuplicate8169(5_000L, 1_000L))
    }

    @Test fun heliusMeterRecordsSessionCallsByCaller() {
        LoadTelemetry8169.noteHelius8169("/v0/addresses/Abc111111111111111111111111111111111/transactions", emptyList(), "", 100.0, 1_000L)
        LoadTelemetry8169.noteHelius8169("/v0/addresses/Abc111111111111111111111111111111111/transactions", emptyList(), "", 100.0, 2_000L)
        val line = LoadTelemetry8169.statusLine8169()
        assertTrue(line, line.contains("Aate8169LoadTest="))
        assertTrue(line, line.contains("dupReads{") && line.contains("v0tx="))
    }

    // ── memory ──

    @Test fun nativeSnapshotsArePrunedOnlyWhenNoReaderCanServeThem() {
        assertTrue(SpecialistBrainBridge7542.snapshotReadable8169(10_000L, 15_000L))
        assertFalse(SpecialistBrainBridge7542.snapshotReadable8169(10_000L, 15_001L))
        assertTrue(SpecialistBrainBridge7542.pruneUnreadable8169(System.currentTimeMillis()) >= 0)
        val b = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("if(snapshotReadable8169(c.evaluatedAtMs,System.currentTimeMillis()))c else null"))
        assertTrue(b.contains("cache[ts.mint]=Cached(it);pruneOnInsert8169(now)"))
    }

    @Test fun deskSheetsOfResidentCoinsAreNeverDropped() {
        assertFalse(ToolkitSignalSheet.sheetPrunable8169(10L * 60 * 60_000L, resident = true))
        assertFalse(ToolkitSignalSheet.sheetPrunable8169(29L * 60_000L, resident = false))
        assertTrue(ToolkitSignalSheet.sheetPrunable8169(31L * 60_000L, resident = false))
    }

    @Test fun bundleAnalysesSurviveARestartOnlyInsideTheirWindow() {
        val now = 100L * 60 * 60_000L
        assertTrue(BundleDetector.persistable8169(BundleDetector.BundleRisk.LOW, now - 60_000L, now))
        assertTrue(BundleDetector.persistable8169(BundleDetector.BundleRisk.HIGH, now - 6L * 60 * 60_000L, now))
        assertFalse(BundleDetector.persistable8169(BundleDetector.BundleRisk.MEDIUM, now - 6L * 60 * 60_000L - 1L, now))
        assertFalse(BundleDetector.persistable8169(BundleDetector.BundleRisk.UNKNOWN, now - 60_000L, now))
        assertFalse(BundleDetector.persistable8169(BundleDetector.BundleRisk.LOW, now + 1L, now))
        val a = BundleDetector.BundleAnalysis(
            mint = "MintX", symbol = "X", analyzedAt = 123_456L, hasBundles = true,
            bundleType = BundleDetector.BundleType.SNIPER_BUNDLE, bundleRisk = BundleDetector.BundleRisk.MEDIUM,
            firstBlockBuyers = 5, firstBlockSupplyPct = 61.5, largestBundlePct = 22.25, uniqueWalletsFirst10 = 7,
            bundledWalletsSold = 3, bundledWalletsHolding = 2, avgHoldTimeMinutes = 4.5,
            isLikelyRug = false, isLikelyPump = false, isLikelySnipers = true,
            recommendation = "CAUTION", reason = "Clustered early entrants",
        )
        val back = BundleDetector.fromJson8169(BundleDetector.toJson8169(a))
        assertNotNull(back)
        assertEquals(a, back)
        assertNull(BundleDetector.fromJson8169(org.json.JSONObject().put("m", "")))
        assertNull(BundleDetector.fromJson8169(BundleDetector.toJson8169(a).put("br", "NOT_A_RISK")))
    }

    @Test fun unattributedHeapIsUsedMinusCensus() {
        assertEquals(451L, MemoryGuard7977.unattributedMb8169(511L, 60L shl 20))
        assertEquals(0L, MemoryGuard7977.unattributedMb8169(10L, 60L shl 20))
        assertEquals(511L, MemoryGuard7977.unattributedMb8169(511L, -1L))
    }

    // ── wiring ──

    @Test fun wiring() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("LoadTelemetry8169.timedLaneGate8169(ts.mint, lane, primaryLane) { laneEvaluationAllowed7951(ts, lane, primaryLane) }"))
        val guardian = src("engine/InvariantGuardian.kt")
        assertTrue(guardian.contains("LoadTelemetry8169.lanesPerCandidateCycle8169(s.laneEval, s.scanCb, s.intake)"))
        assertTrue(guardian.contains("laneRatio > 18.0") && guardian.contains("laneRatio > 12.0 && !productiveFanout6019"))
        assertTrue(src("engine/RuntimeStateSnapshot.kt").contains("scanCb = pipe.phaseCounts[\"SCAN_CB\"]"))
        assertTrue(src("network/HeliusCreditMeterInterceptor7881.kt").contains("LoadTelemetry8169.noteHelius8169("))
        val guard = src("engine/MemoryGuard7977.kt")
        for (s in listOf("\"nativeSnapshots\"", "\"deskSheets\"", "\"bundleAnalyses\"", "attributed8169=[")) assertTrue(s, guard.contains(s))
        val stores = MemoryGuard7977.registeredStores8144()
        for (s in listOf("nativeSnapshots", "deskSheets", "bundleAnalyses")) assertTrue(s, s in stores)
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Compute load (§8169)"))
        val bundle = src("engine/BundleDetector.kt")
        assertTrue(bundle.contains("afterInsert8169(System.currentTimeMillis())") && bundle.contains("restoreOnce8169()"))
    }
}
