package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HiveEdge8000
import com.lifecyclebot.engine.truth.SignalSourceProof7291
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Historical hypothetical runner credit must not re-enter current learning through another book. */
class Aate8527DownstreamLabelEpochTest {
    private lateinit var originalMode: RuntimeModeAuthority.Snapshot
    private lateinit var originalExpert: String
    private lateinit var originalForward: String
    private lateinit var originalSignals: Map<String, String>

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Suppress("UNCHECKED_CAST")
    private fun savedSignalBooks(): Map<String, String> {
        fun field(name: String): Any = SignalSourceProof7291.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(SignalSourceProof7291)
        val actual = field("tallies") as Map<SignalSourceProof7291.Source, Any>
        val labels = field("labeled7731") as Map<SignalSourceProof7291.Source, Any>
        val squares = field("labeledSq7962") as Map<SignalSourceProof7291.Source, DoubleArray>
        fun encode(value: Any) = value.javaClass.getDeclaredMethod("encode").apply {
            isAccessible = true
        }.invoke(value) as String
        return buildMap {
            SignalSourceProof7291.Source.values().forEach { source ->
                put(source.name + "_LIVE_7835", encode(actual.getValue(source)))
                put(source.name + "_LABELED_8527", encode(labels.getValue(source)))
                put(source.name + "_LABELED_SQ_8527", squares.getValue(source).joinToString(","))
            }
        }
    }

    @Before fun isolate() {
        originalMode = RuntimeModeAuthority.current()
        originalExpert = ExpertTraderKnowledge7813.exportState()
        originalForward = ForwardOutcomeModel.exportState()
        originalSignals = savedSignalBooks()
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = false)
        ExpertTraderKnowledge7813.reset()
        ForwardOutcomeModel.reset()
        SignalSourceProof7291.restoreBooks8527 { null }
    }

    @After fun restore() {
        ExpertTraderKnowledge7813.reset()
        ExpertTraderKnowledge7813.importState(originalExpert)
        ForwardOutcomeModel.reset()
        ForwardOutcomeModel.importState(originalForward)
        SignalSourceProof7291.restoreBooks8527 { originalSignals[it] }
        RuntimeModeAuthority.publishConfig(
            originalMode.authority == RuntimeModeAuthority.Mode.PAPER, originalMode.autoTrade)
    }

    private fun forecast(mode: String) = LearningEnvironment7835.withMode(mode) {
        ForwardOutcomeModel.forecast("MOONSHOT", 65, "A", "BULL", "EARLY")
    }

    private fun cell(mean: Double, n: Long = 20L) = JSONObject()
        .put("n", n).put("mean", mean).put("m2", 0.0).put("w", n).put("r", 0L)

    @Test(timeout = 5_000L) fun archivedModelledForecastCannotSeedPaperOrCountAsActualCloses() {
        val old = "S8521|MOONSHOT|S60|A|BULL|EARLY"
        ForwardOutcomeModel.importState(JSONObject().put("fine", JSONObject().put(old, cell(500.0))).toString())
        assertEquals("bootstrap", forecast("PAPER").source)
        assertEquals("bootstrap", forecast("LIVE").source)
        assertEquals(0 to 0, ForwardOutcomeModel.modeCensus6988())
        val exported = JSONObject(ForwardOutcomeModel.exportState()).getJSONObject("fine")
        assertEquals(500.0, exported.getJSONObject(old).getDouble("mean"), 0.0)
        assertFalse(ForwardOutcomeModel.formatForPipelineDump().contains(old))
    }

    @Test(timeout = 5_000L) fun newObservedLabelsSeedOnlyTheExistingPaperPriorAndNeverLiveTerminalEvidence() {
        val id = "observed-label-8527-" + System.nanoTime()
        repeat(12) { i ->
            ForwardOutcomeModel.recordLabel7734("MOONSHOT", 65, "A", "BULL", "EARLY", 12.0, id + i)
        }
        val paper = forecast("PAPER")
        assertTrue(paper.source.startsWith("fine_label_prior"))
        assertEquals(12L, paper.samples)
        assertFalse(ForwardOutcomeModel.hasTerminalEvidence7838(paper))
        assertEquals("bootstrap", forecast("LIVE").source)
        assertTrue(JSONObject(ForwardOutcomeModel.exportState()).getJSONObject("fine")
            .has("S8527|MOONSHOT|S60|A|BULL|EARLY"))
    }

    @Test(timeout = 5_000L) fun realizedLiveAndPaperCellsSurviveTheLabelEpochIndependently() {
        val liveKey = "L|MOONSHOT|S60|A|BULL|EARLY"
        val paperKey = "P|MOONSHOT|S60|A|BULL|EARLY"
        ForwardOutcomeModel.importState(JSONObject().put("fine", JSONObject()
            .put(liveKey, cell(7.0)).put(paperKey, cell(21.0))
            .put("S8521|MOONSHOT|S60|A|BULL|EARLY", cell(900.0))).toString())
        assertEquals(7.0, forecast("LIVE").expectedPnl, 0.0)
        assertEquals(21.0, forecast("PAPER").expectedPnl, 0.0)
        assertTrue(ForwardOutcomeModel.hasTerminalEvidence7838(forecast("LIVE")))
        val state = ForwardOutcomeModel.exportState()
        ForwardOutcomeModel.reset()
        ForwardOutcomeModel.importState(state)
        assertEquals(1 to 1, ForwardOutcomeModel.modeCensus6988())
        assertEquals(7.0, forecast("LIVE").expectedPnl, 0.0)
        assertEquals(21.0, forecast("PAPER").expectedPnl, 0.0)
    }

    @Test(timeout = 5_000L) fun oldPositiveSignalLabelsCannotEarnLiveOrProviderBudget() {
        val old = mapOf("COPY_LABELED_8076" to "100,100,100,100,0",
            "COPY_LABELED_SQ_8076" to "100,100,100")
        SignalSourceProof7291.restoreBooks8527 { old[it] }
        assertFalse(SignalSourceProof7291.liveEligible7962(SignalSourceProof7291.Source.COPY))
        assertFalse(SignalSourceProof7291.copyEdgeAtGap8062(0.1))
        assertEquals(0.5, SignalSourceProof7291.economicValue7881(SignalSourceProof7291.Source.COPY), 0.0)
        assertEquals("100,100,100,100,0", old["COPY_LABELED_8076"])
    }

    @Test(timeout = 5_000L) fun correctedSignalLabelsCanEarnExistingAuthorityButRealLossesStillOverrule() {
        val current = mutableMapOf("COPY_LABELED_8527" to "100,100,100,100,0",
            "COPY_LABELED_SQ_8527" to "100,100,100")
        SignalSourceProof7291.restoreBooks8527 { current[it] }
        assertTrue(SignalSourceProof7291.liveEligible7962(SignalSourceProof7291.Source.COPY))
        assertFalse(SignalSourceProof7291.isProven(SignalSourceProof7291.Source.COPY))
        current["COPY_LIVE_7835"] = "20,0,-2,0,2"
        SignalSourceProof7291.restoreBooks8527 { current[it] }
        assertFalse(SignalSourceProof7291.liveEligible7962(SignalSourceProof7291.Source.COPY))
        assertFalse(SignalSourceProof7291.copyEdgeAtGap8062(0.0))
        SignalSourceProof7291.restoreBooks8527 { if (it == "COPY_LIVE_7835") "20,20,2,2,0" else null }
        assertTrue(SignalSourceProof7291.isProven(SignalSourceProof7291.Source.COPY))
        assertTrue(SignalSourceProof7291.liveEligible7962(SignalSourceProof7291.Source.COPY))
    }

    private val expertKey = "LIVE|MOONSHOT|BULL|IGNITION|POS"
    private fun expertRow(weight: Double, sum: Double) = JSONObject().put("k", expertKey)
        .put("w", weight).put("sum", sum).put("wins", weight).put("runners", 0.0)
    private fun pending(mint: String, at: Long) = JSONObject().put("mint", mint).put("lane", "MOONSHOT")
        .put("mode", "LIVE").put("source", "test8527").put("regime", "BULL")
        .put("score", 65).put("confidence", 0.8).put("at", at)
        .put("v", JSONObject().put("IGNITION", 1.0))
    private fun row(rows: JSONArray, key: String): JSONObject =
        (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("k") == key }

    @Test(timeout = 5_000L) fun inseparableLegacyExpertTotalsAreArchivedNotRelabelledAsRealized() {
        val mint = "expert-legacy-8527-" + System.nanoTime()
        val now = System.currentTimeMillis()
        ExpertTraderKnowledge7813.importState(JSONObject().put("version", 7813)
            .put("stats", JSONArray().put(expertRow(100.0, 50_000.0)))
            .put("pending", JSONArray().put(pending(mint, now))).toString())
        val prior = ExpertTraderKnowledge7813.peekPrior7813(mint, "MOONSHOT")
        assertEquals(0.25, prior.confidence, 0.0)
        assertTrue(prior.contributions.isEmpty())
        val state = JSONObject(ExpertTraderKnowledge7813.exportState())
        assertEquals(0, state.getJSONArray("stats").length())
        assertEquals(0, state.getJSONArray("forward8527").length())
        assertEquals(50_000.0, row(state.getJSONArray("legacyMixed8527"), expertKey).getDouble("sum"), 0.0)
        ExpertTraderKnowledge7813.reset()
        ExpertTraderKnowledge7813.importState(state.toString())
        assertTrue(ExpertTraderKnowledge7813.peekPrior7813(mint, "MOONSHOT").contributions.isEmpty())
        assertEquals(50_000.0, row(JSONObject(ExpertTraderKnowledge7813.exportState())
            .getJSONArray("legacyMixed8527"), expertKey).getDouble("sum"), 0.0)
    }

    @Test(timeout = 5_000L) fun newForwardUpdatesNeverOverwriteOrMasqueradeAsFinalizedExpertOutcomes() {
        val mint = "expert-current-8527-" + System.nanoTime()
        val now = System.currentTimeMillis()
        ExpertTraderKnowledge7813.importState(JSONObject().put("version", 8527)
            .put("stats", JSONArray().put(expertRow(10.0, 50.0)))
            .put("pending", JSONArray().put(pending(mint, now))).toString())
        val before = row(JSONObject(ExpertTraderKnowledge7813.exportState()).getJSONArray("stats"), expertKey)
        ExpertTraderKnowledge7813.recordForwardOutcome7813(mint, "MOONSHOT", 100.0, true, now)
        val after = JSONObject(ExpertTraderKnowledge7813.exportState())
        assertEquals(before.toString(), row(after.getJSONArray("stats"), expertKey).toString())
        val forward = row(after.getJSONArray("forward8527"), expertKey)
        assertEquals(0.2, forward.getDouble("w"), 0.0)
        assertEquals(20.0, forward.getDouble("sum"), 0.0)
        ExpertTraderKnowledge7813.reset()
        ExpertTraderKnowledge7813.importState(after.toString())
        val restored = JSONObject(ExpertTraderKnowledge7813.exportState())
        assertEquals(50.0, row(restored.getJSONArray("stats"), expertKey).getDouble("sum"), 0.0)
        assertEquals(20.0, row(restored.getJSONArray("forward8527"), expertKey).getDouble("sum"), 0.0)
        assertTrue(ExpertTraderKnowledge7813.peekPrior7813(mint, "MOONSHOT").contributions.isNotEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    @Test(timeout = 5_000L) fun hiveCurrentNamespaceCannotReadLegacyPeersAndOtherNamespacesRemainIndependent() {
        val field = HiveEdge8000.javaClass.getDeclaredField("aggregate").apply { isAccessible = true }
        val aggregate = field.get(HiveEdge8000) as ConcurrentHashMap<String, DoubleArray>
        val unique = "epoch8527-" + System.nanoTime()
        val old = "FRL8521|" + unique
        val current = "FRL8527|" + unique
        val actual = "EXP|" + unique
        try {
            aggregate[old] = doubleArrayOf(100.0, 90_000.0, 81_000_000.0, 100.0, 100.0, 900.0)
            aggregate[actual] = doubleArrayOf(20.0, 20.0, 20.0, 20.0, 0.0, 1.0)
            assertNull(HiveEdge8000.net8000(current))
            assertNotNull(HiveEdge8000.net8000(actual))
            aggregate[current] = doubleArrayOf(12.0, 60.0, 300.0, 12.0, 0.0, 5.0)
            assertEquals(60.0, HiveEdge8000.net8000(current)!![1], 0.0)
            assertEquals(90_000.0, aggregate.getValue(old)[1], 0.0)
        } finally { aggregate.remove(old); aggregate.remove(current); aggregate.remove(actual) }
    }

    @Test(timeout = 5_000L) fun allCurrentForwardConsumersShareTheObservedEpochAndActualKeysDoNotMove() {
        val hive = src("engine/truth/HiveEdge8000.kt")
        val miner = src("engine/truth/SpecialistMiner7972.kt")
        for (prefix in listOf("FRL8527|", "SPEC8663|", "DEV8663|")) assertTrue(prefix, hive.contains(prefix))
        assertTrue(miner.contains("\"specialists8663.bin\""))
        assertTrue(miner.contains("SPEC8663|"))
        assertTrue(miner.contains("DEV8663|"))
        assertFalse(miner.contains("\"specialists8521.bin\""))
        val reset = src("engine/truth/LearningResetSweep7781.kt")
        assertTrue(reset.contains("\"specialists8521.bin\""))
        assertTrue(reset.contains("\"specialists8527.bin\""))
        assertTrue(reset.contains("\"specialists8663.bin\""))
        val proof = src("engine/truth/SignalSourceProof7291.kt")
        assertTrue(proof.contains("_LIVE_7835"))
        assertTrue(proof.contains("_LABELED_8527"))
        assertTrue(proof.contains("_LABELED_SQ_8527"))
        assertFalse(proof.contains("_LABELED_8076"))
        assertFalse(proof.contains("_LABELED_SQ_8076"))
        val expert = src("engine/ExpertTraderKnowledge7813.kt")
        assertTrue(expert.contains("updateStats(s, e.netReturnPct, 1.0)"))
        assertTrue(expert.contains("updateStats(s, netPct, COUNTERFACTUAL_WEIGHT, forwardStats8527)"))
    }
}
