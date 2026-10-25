package com.lifecyclebot.engine

import android.content.SharedPreferences
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class Aate8384SharedSpecialistLifecycleTest {
    private val ensembles = listOf("CORE", "TREASURY", "CASHGEN", "CYCLIC")
    private fun h(lane: String, score: Double, setup: ToolkitSignalSheet.Setup = ToolkitSignalSheet.Setup.CHART_PULLBACK_RECLAIM) =
        ToolkitSignalSheet.DeskHypothesis(lane, setup, score, "reclaim", "runner", 1.7, 0.8, 1.3, "observed")
    private fun sheet(h: ToolkitSignalSheet.DeskHypothesis? = null) = ToolkitSignalSheet.Sheet(
        ToolkitSignalSheet.Setup.CASHFLOW_SCALP, 90.0, "base", "global", "quick", 0.55, 0.82, 0.78,
        setOf("CASHGEN"), h?.let { mapOf(it.lane to it) }.orEmpty(), setOf("SCALP"), listOf("regime=RANGE"))

    @Test fun allEnsembleDesksCanEvaluateEveryExistingSpecialistPredicate() {
        val all = LanePlaybook7907.menuLanes8383().flatMap { LanePlaybook7907.menuIds(it) }.toSet()
        for (lane in ensembles) {
            val own = LanePlaybook7907.menuIds(lane)
            assertTrue("$lane missing ${all - own.toSet()}", own.containsAll(all))
            assertEquals(lane, own.size, own.distinct().size)
        }
        assertFalse(LanePlaybook7907.isEnsemble8384("MOONSHOT"))
    }
    @Test fun everyPriorReleaseBinAndNoTriggerIdentitySurvivesExpansion() {
        val rows = javaClass.getResourceAsStream("/setup8383_bins.tsv")!!.bufferedReader().readLines()
        assertEquals(433, rows.size)
        for (line in rows) {
            val (lane, id, bin) = line.split('\t')
            assertEquals("$lane/$id", bin.toDouble(), LanePlaybook7907.setupIndexFor8076(lane, id), 0.0)
        }
        for (lane in LanePlaybook7907.menuLanes8383()) {
            val bins = (LanePlaybook7907.menuIds(lane) + LanePlaybook7907.NO_TRIGGER).map { LanePlaybook7907.setupIndexFor8076(lane, it) }
            assertEquals(lane, bins.size, bins.distinct().size)
        }
    }
    @Test fun sharedStylesDoNotBecomeCrossLaneOwnershipOrUniversalSpecialistPermission() {
        for (lane in ensembles) for (style in AgenticStyleRouter.Style.values())
            assertTrue("$lane/${style.name}", AgenticStyleRouter.toolkitStyleFitsLane8080(style.lanes, lane))
        assertFalse(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("CASHGEN"), "MOONSHOT"))
    }
    @Test fun receivingDeskKeepsItsObservedEntryExitHoldBundle() {
        for (lane in ensembles) {
            val own = h(lane, 81.0)
            val local = ToolkitSignalSheet.forDesk8384(sheet(own), lane)
            assertEquals(own.setup, local.setup); assertEquals(own.entryStyle, local.entryStyle)
            assertEquals(own.exitStyle, local.exitStyle); assertEquals(own.holdMult, local.holdMult, 0.0)
            assertEquals(own.tpMult, local.tpMult, 0.0)
            assertEquals(own.setup.name, AgenticStyleRouter.sealedSetup8080(TokenState("mint8384"), local, lane))
            val chosen = ToolkitSignalSheet.preferDesk8384(own, h(lane, 70.0, ToolkitSignalSheet.Setup.CASHFLOW_SCALP))
            assertEquals(own.setup, chosen.setup); assertTrue(chosen.reason.contains("native_role_ok"))
        }
    }
    @Test fun missingOwnHypothesisDoesNotBorrowTokenWideDonorAuthority() {
        val donor = sheet()
        for (lane in ensembles) {
            val local = ToolkitSignalSheet.forDesk8384(donor, lane)
            assertEquals(ToolkitSignalSheet.Setup.NONE, local.setup); assertEquals(0.0, local.confidence, 0.0)
            assertFalse(AgenticStyleRouter.toolkitFitsLane8080(donor, lane))
        }
        assertSame(donor, ToolkitSignalSheet.forDesk8384(donor, "MOONSHOT"))
        assertEquals("CORE", ToolkitSignalSheet.preferDesk8384(h("CASHGEN", 99.0), h("CORE", 60.0)).lane)
    }
    @Test fun namedSpecialistHoldsUseTheirFieldGuideNotSilentStandardFallback() {
        for (lane in LanePlaybook7907.menuLanes8383()) {
            val p = HoldingLogicLayer.getHoldParams(lane)
            assertEquals(lane, p.mode); assertTrue(lane, p.stopLossPct < 0.0)
        }
        assertTrue(HoldingLogicLayer.getHoldParams("TREASURY").maxHoldTimeMs < HoldingLogicLayer.getHoldParams("STANDARD").maxHoldTimeMs)
        for (lane in ensembles) assertTrue(HoldingLogicLayer.shouldCheckModeSwitch(Position(tradingMode = lane)))
    }
    @Test fun sharedSwitchesCanCutButCannotLaunderLosersIntoPatientRunners() {
        assertTrue(HeldPositionPivotArbiter.switchAllowed8384("CORE", "CORE", "MARKET_MAKER", -8.0, 2.0, false))
        assertFalse(HeldPositionPivotArbiter.switchAllowed8384("MOONSHOT", "MOONSHOT", "MARKET_MAKER", 12.0, 14.0, true))
        for (lane in ensembles) {
            assertFalse(HeldPositionPivotArbiter.switchAllowed8384(lane, "PUMP_SNIPER", "MOONSHOT", -8.0, 2.0, true))
            assertFalse(HeldPositionPivotArbiter.switchAllowed8384(lane, "PUMP_SNIPER", "MOONSHOT", 12.0, 14.0, false))
            assertTrue(HeldPositionPivotArbiter.switchAllowed8384(lane, "PUMP_SNIPER", "MOONSHOT", 12.0, 14.0, true))
            assertFalse(HeldPositionPivotArbiter.switchAllowed8384(lane, "CORE", "UNKNOWN", 12.0, 14.0, true))
        }
        assertFalse(HeldPositionPivotArbiter.switchSlotFree8384(1_000, 1_001))
        assertTrue(HeldPositionPivotArbiter.switchSlotFree8384(1_000, 46_000))
        assertFalse(HeldPositionPivotArbiter.switchSlotFree8384(50_000, 1_000))
    }
    private fun sealedToken(): TokenState {
        val pid = "PAPER:8384:${java.util.UUID.randomUUID()}"
        val ts = TokenState("mint-$pid", position = Position(qtyToken = 1.0, entryPrice = 1.0,
            entryTime = 1_000, costSol = 1.0, tradingMode = "MOONSHOT", positionId = pid))
        val snap = EntryStrategySnapshot6450.Snapshot(pid, ts.mint, "CORE", "pid8384", "BREAKOUT", "risk", "exit",
            "HELIUS", 75, 10.0, 100_000.0, 1_000, "sealed", mode8090 = "PAPER")
        val field = EntryStrategySnapshot6450::class.java.getDeclaredField("snapshots").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(EntryStrategySnapshot6450) as MutableMap<String, EntryStrategySnapshot6450.Snapshot>)[pid] = snap
        return ts
    }
    @Test fun allThreeSwitchProducersShareOneAtomicPositionScopedCommit() {
        val ts = sealedToken()
        val before = EntryStrategySnapshot6450.cachedManagement8384(ts.position.positionId)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = pool.invokeAll((1..6).map { Callable {
                HeldPositionPivotArbiter.applyMode8384(ts, "MARKET_MAKER", 5.0, 8.0, false, "test", 100_000)
            } }).map { it.get() }
            assertEquals(1, results.count { it }); assertEquals("MOONSHOT>MARKET_MAKER", ts.position.modeHistory)
            assertEquals(100_000L, ts.position.lastManagementSwitchAtMs8384)
            assertSame(before, EntryStrategySnapshot6450.cachedManagement8384(ts.position.positionId))
            assertEquals("CORE", HeldPositionPivotArbiter.entryOwner8384(ts))
            ts.position = ts.position.copy(isPaperPosition = false)
            assertEquals("", HeldPositionPivotArbiter.entryOwner8384(ts))
        } finally { pool.shutdownNow() }
    }
    @Test fun liveAndPaperTuningCachesRemainSeparateAcrossUiModeChangesAndExpiry() {
        val c = LiveStrategyTuner.ModeCache8384<String>(100)
        var loads = 0
        assertEquals("LIVE", c.read(false, 1_000) { loads++; "LIVE" })
        assertEquals("PAPER", c.read(true, 1_001) { loads++; "PAPER" })
        assertEquals("LIVE", c.read(false, 1_002) { error("must reuse LIVE, not PAPER") })
        assertEquals("LIVE2", c.read(false, 1_101) { loads++; "LIVE2" })
        assertEquals("PAPER2", c.read(true, 1_102) { loads++; "PAPER2" }); assertEquals(4, loads)
    }
    @Test fun newEvidenceSetupsKeepTheirActualTacticFamily() {
        assertEquals(TacticSwitcher.Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("TREASURY", "RUNUP_HOLD_8383"))
        assertEquals(TacticSwitcher.Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("CORE", "SWEET_SPOT_8383"))
        assertEquals(TacticSwitcher.Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("CYCLIC", "DEEP_RATIO_8383"))
    }
    @Test fun persistedPositionCodecRetainsManagementCooldownAndImmutableLineage() {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putLong", "putInt" -> { values[args!![0] as String] = args[1]; proxy }
                "apply" -> null
                "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString", "getLong", "getInt" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
        val f = PositionPersistence::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val original = f.get(PositionPersistence)
        try {
            f.set(PositionPersistence, prefs)
            val ts = sealedToken()
            ts.position.lastManagementSwitchAtMs8384 = 90_000; ts.position.modeHistory = "CORE>MOONSHOT"
            ts.position.entryPolicySnapshot = "sealed-policy8384"; ts.styleHoldMult = 1.8; ts.styleTpMult = 1.4
            PositionPersistence.savePosition(ts)
            val raw = values.values.filterIsInstance<String>().first { it.startsWith("[") }
            val j = JSONArray(raw).getJSONObject(0)
            assertEquals(ts.position.positionId, j.getString("positionId8384"))
            assertEquals(90_000L, j.getLong("lastManagementSwitchAtMs8384"))
            val load = PositionPersistence::class.java.getDeclaredMethod("loadPositionsInternal").apply { isAccessible = true }
            val rows = load.invoke(PositionPersistence) as Map<*, *>
            val row = rows[ts.mint]!!
            fun get(name: String) = row.javaClass.getMethod(name).invoke(row)
            assertEquals(ts.position.positionId, get("getPositionId8384"))
            assertEquals("sealed-policy8384", get("getEntryPolicySnapshot8384"))
            assertEquals(90_000L, get("getLastManagementSwitchAtMs8384"))
            assertEquals(1.8, get("getStyleHoldMult8384") as Double, 0.0)
            assertEquals(1.4, get("getStyleTpMult8384") as Double, 0.0)
        } finally { f.set(PositionPersistence, original) }
    }
}
