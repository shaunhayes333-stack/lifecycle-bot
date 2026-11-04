package com.lifecyclebot.engine

import android.content.SharedPreferences
import com.lifecyclebot.engine.truth.AdaptiveEntryWitness8527
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.util.UUID

class Aate8527AdaptiveFeaturePresenceTest {
    private var oldPrefs: Any? = null
    private val installedSnapshots = mutableMapOf<String, EntryStrategySnapshot6450.Snapshot?>()
    @Suppress("UNCHECKED_CAST")
    private fun snapshots() = EntryStrategySnapshot6450.javaClass.getDeclaredField("snapshots").apply { isAccessible = true }
        .get(EntryStrategySnapshot6450) as MutableMap<String, EntryStrategySnapshot6450.Snapshot>
    private fun field(name: String) = AdaptiveLearningEngine.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun buffer() = field("featureBuffer").get(AdaptiveLearningEngine) as List<AdaptiveLearningEngine.TradeFeatures>
    @Suppress("UNCHECKED_CAST")
    private fun good() = field("goodPatterns").get(AdaptiveLearningEngine) as List<AdaptiveLearningEngine.LearnedPattern>
    private fun invoke(name: String, types: Array<Class<*>>, vararg args: Any?): Any? =
        AdaptiveLearningEngine.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(AdaptiveLearningEngine, *args)

    @Before fun before() {
        oldPrefs = field("prefs").get(AdaptiveLearningEngine)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        field("prefs").set(AdaptiveLearningEngine, prefs)
        AdaptiveLearningEngine.reset()
    }
    @After fun after() {
        installedSnapshots.forEach { (id, old) -> if (old == null) snapshots().remove(id) else snapshots()[id] = old }
        installedSnapshots.clear()
        AdaptiveLearningEngine.reset()
        field("prefs").set(AdaptiveLearningEngine, oldPrefs)
    }

    private fun partial(mcap: Double = Double.NaN, pnl: Double = 25.0) = AdaptiveLearningEngine.captureFeatures(
        entryMcapUsd = mcap, tokenAgeMinutes = Double.NaN, buyRatioPct = Double.NaN,
        volumeUsd = Double.NaN, liquidityUsd = Double.NaN, holderCount = -1, topHolderPct = Double.NaN,
        holderGrowthRate = Double.NaN, devWalletPct = Double.NaN, bondingCurveProgress = Double.NaN,
        rugcheckScore = Double.NaN, emaFanState = "", entryScore = Double.NaN, priceFromAth = Double.NaN,
        pnlPct = pnl, maxGainPct = Double.NaN, maxDrawdownPct = Double.NaN, timeToPeakMins = Double.NaN,
        holdTimeMins = 10.0, exitReason = "TAKE_PROFIT", stableTradeKey = "8527-" + UUID.randomUUID(),
        source8527 = "DEX_TRENDING", lane8527 = "MOONSHOT", strategy8527 = "RUNNER>BREAKOUT", environment8527 = "LIVE",
    )

    private fun score(rug: Double = Double.NaN, dev: Double = Double.NaN) = AdaptiveLearningEngine.calculateAdaptiveScore(
        mcapUsd = Double.NaN, tokenAgeMinutes = Double.NaN, buyRatioPct = Double.NaN, volumeUsd = Double.NaN,
        liquidityUsd = Double.NaN, holderCount = -1, topHolderPct = Double.NaN, holderGrowthRate = Double.NaN,
        devWalletPct = dev, bondingCurveProgress = Double.NaN, rugcheckScore = rug, emaFanState = "",
        baseEntryScore = 50.0,
    )

    @Test fun absentInputsLeaveBaseScoreUnchangedButMeasuredZeroRetainsItsMeaning() {
        assertEquals(50.0, score().score, 0.0)
        assertEquals(50.0, score(rug = -1.0).score, 0.0)
        assertEquals("pending1 is not a confirmed low safety score", 50.0, score(rug = 1.0).score, 0.0)
        assertTrue(score(rug = 0.0).score < 50.0)
        assertTrue(score(dev = 0.0).score > 50.0)
        assertEquals(50.0, score(dev = -1.0).score, 0.0)
    }

    @Test fun partialOutcomeUpdatesOnlyObservedEntryAndOutcomeTerms() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.learnFromTrade(partial(20_000.0), TradeEnvironment.LIVE)
        val after = AdaptiveLearningEngine.getDetailedWeights()
        assertTrue(after.getValue("mcap") > before.getValue("mcap"))
        assertTrue(after.getValue("holdTime") > before.getValue("holdTime"))
        assertTrue(after.getValue("exitQuality") > before.getValue("exitQuality"))
        for (key in listOf("age", "buyRatio", "holderConc", "holderGrowth", "emaFan", "volLiqRatio", "maxGainFollow")) {
            assertEquals(key, before.getValue(key), after.getValue(key), 0.0)
        }
        assertEquals(1, buffer().size)
        assertFalse("devWallet" in buffer().single().observedFeatures8527)
    }

    @Test fun unpricedPnlCannotTurnIntoATrainingScratch() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        val f = partial(20_000.0, Double.NaN)
        assertFalse(f.outcomeObserved8527)
        AdaptiveLearningEngine.learnFromTrade(f, TradeEnvironment.LIVE)
        assertEquals(before, AdaptiveLearningEngine.getDetailedWeights())
        assertTrue(buffer().isEmpty())
    }

    private fun outcome(features: Map<String, Double> = emptyMap(), environment: TradeEnvironment = TradeEnvironment.LIVE): CanonicalFinalizedTradeBus6464.Envelope {
        val id = "canonical8527-" + UUID.randomUUID()
        if (features.isNotEmpty()) {
            val raw = AdaptiveEntryWitness8527.encode("ObservedEntry8527", 900L,
                features.mapKeys { it.key.removePrefix(AdaptiveEntryWitness8527.PREFIX) })
            val snapshot = EntryStrategySnapshot6450.Snapshot(positionId = id, mint = "ObservedEntry8527",
                entryLane = "MOONSHOT", entryStrategyPid = "P", entryTactic = "HOLD", entryRiskProfile = "R", entryExitProfile = "E",
                entrySource = "DEX_TRENDING", entryScore = 70, entryLiquiditySol = 1.0, entryMarketCapUsd = 0.0,
                entryTimestampMs = 1_000L, entryThresholdSnapshot = "", mode8090 = environment.name,
                adaptiveEntry8527 = raw)
            installedSnapshots[id] = snapshots().put(id, snapshot)
        }
        return CanonicalFinalizedTradeBus6464.Envelope(tradeId = id, positionId = id, mint = "ObservedEntry8527",
            lane = "MOONSHOT", atMs = 601_000L, mode = environment.name, proofState = "LIVE_FINALIZED",
            realizedPnlSol = 0.25, realizedReturnPct = 25.0, holdingTimeMs = 600_000L,
            exitReason = "TAKE_PROFIT", entrySource = "DEX_TRENDING", entrySetup = "BREAKOUT")
    }

    @Test fun canonicalConsumerUsesExactWitnessUnitsNotCloseBucketsOrProxies() {
        val prefix = AdaptiveEntryWitness8527.PREFIX
        val entry = mapOf(prefix + "mcap" to 123_456.0, prefix + "liquidity" to 40_000.0,
            prefix + "volume24hUsd" to 120_000.0, prefix + "tokenAge" to 73.0,
            prefix + "emaFan" to 2.0, prefix + "bondingCurve" to 55.0)
        AdaptiveLearningEngine.onCanonicalTerminal8527(outcome(entry))
        val f = buffer().single()
        assertEquals(123_456.0, f.entryMcapUsd, 0.0)
        assertEquals(73.0, f.tokenAgeMinutes, 0.0)
        assertEquals(120_000.0, f.volumeUsd, 0.0)
        assertEquals(40_000.0, f.liquidityUsd, 0.0)
        assertEquals(3.0, f.volumeLiquidityRatio, 0.0)
        assertEquals(55.0, f.bondingCurveProgress, 0.0)
        assertEquals("BULL_FAN", f.emaFanState)
        for (key in listOf("buyRatio", "devWallet", "rugcheck", "holderCount", "holderGrowth", "maxGain", "maxDrawdown", "timeToPeak")) {
            assertFalse(key, key in f.observedFeatures8527)
        }
        assertEquals("MOONSHOT", f.lane8527)
        assertEquals("LIVE", f.environment8527)
    }

    @Test fun missingEntryWitnessStillLearnsRealOutcomeWithoutTeachingInventedYoungCleanCoins() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.onCanonicalTerminal8527(outcome())
        val after = AdaptiveLearningEngine.getDetailedWeights()
        for (key in listOf("mcap", "age", "buyRatio", "holderConc", "holderGrowth", "emaFan", "volLiqRatio", "maxGainFollow")) {
            assertEquals(key, before.getValue(key), after.getValue(key), 0.0)
        }
        assertTrue(after.getValue("holdTime") > before.getValue("holdTime"))
        assertEquals(1, buffer().size)
        assertTrue(buffer().single().observedFeatures8527.containsAll(setOf("pnl", "holdTime", "exitReason")))
        assertFalse("tokenAge" in buffer().single().observedFeatures8527)
    }

    @Test fun canonicalPaperAndShadowNeverUpdateLiveModel() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.onCanonicalTerminal8527(outcome(environment = TradeEnvironment.PAPER))
        AdaptiveLearningEngine.onCanonicalTerminal8527(outcome(environment = TradeEnvironment.SHADOW))
        assertEquals(before, AdaptiveLearningEngine.getDetailedWeights())
        assertTrue(buffer().isEmpty())
    }

    @Test fun finiteSerializationRoundTripsPresenceAndIdentityWithoutRevivingLegacyUnknowns() {
        val f = partial(20_000.0)
        val j = invoke("featuresToJson", arrayOf(AdaptiveLearningEngine.TradeFeatures::class.java), f) as JSONObject
        assertFalse(j.toString().contains("NaN"))
        assertFalse(j.toString().contains("Infinity"))
        val restored = invoke("featuresFromJson", arrayOf(JSONObject::class.java), j) as AdaptiveLearningEngine.TradeFeatures
        assertEquals(f.observedFeatures8527, restored.observedFeatures8527)
        assertEquals(f.stableTradeKey, restored.stableTradeKey)
        assertEquals(f.source8527, restored.source8527)
        assertEquals(f.lane8527, restored.lane8527)
        assertEquals(f.strategy8527, restored.strategy8527)
        assertEquals(f.environment8527, restored.environment8527)
        j.remove("schema8527")
        val legacy = invoke("featuresFromJson", arrayOf(JSONObject::class.java), j) as AdaptiveLearningEngine.TradeFeatures
        assertTrue(legacy.observedFeatures8527.isEmpty())
        j.put("schema8527", 8527).remove("mc")
        val missing = invoke("featuresFromJson", arrayOf(JSONObject::class.java), j) as AdaptiveLearningEngine.TradeFeatures
        assertFalse("mcap" in missing.observedFeatures8527)
        j.remove("pnl")
        val noPnl = invoke("featuresFromJson", arrayOf(JSONObject::class.java), j) as AdaptiveLearningEngine.TradeFeatures
        assertFalse(noPnl.outcomeObserved8527)
        j.put("mc", "20000").put("pnl", "25")
        val wrongTypes = invoke("featuresFromJson", arrayOf(JSONObject::class.java), j) as AdaptiveLearningEngine.TradeFeatures
        assertFalse("mcap" in wrongTypes.observedFeatures8527)
        assertFalse(wrongTypes.outcomeObserved8527)
        assertFalse(j.has("realizedPnlSol8527"))
        j.put("realizedPnlSol8527", "0.25")
        val wrongSolType = invoke("featuresFromJson", arrayOf(JSONObject::class.java), j) as AdaptiveLearningEngine.TradeFeatures
        assertTrue(wrongSolType.realizedPnlSol8527.isNaN())
    }

    @Test fun patternsNeedObservedPerDimensionSamplesAndActualCoverage() {
        val trades = List(25) { i -> partial(if (i < 5) 20_000.0 else Double.NaN) }
        AdaptiveLearningEngine.extractPatterns(trades)
        val pattern = good().single { "mcap" in it.featureRanges }
        assertEquals(5, pattern.sampleCount)
        assertEquals(0.2, pattern.confidence, 0.0)
        assertFalse("devWallet" in pattern.featureRanges)
        assertFalse("bondingCurve" in pattern.featureRanges)
        assertFalse("maxGain" in pattern.featureRanges)
        val required = AdaptiveLearningEngine.LearnedPattern("observed",
            mapOf("mcap" to (1.0 to 10.0), "liquidity" to (1.0 to 10.0), "buyRatio" to (50.0 to 80.0)),
            1.0, 10, 1.0)
        fun matches(values: Map<String, Double>) = invoke("matchesPattern",
            arrayOf(Map::class.java, AdaptiveLearningEngine.LearnedPattern::class.java), values, required) as Boolean
        assertFalse(matches(mapOf("mcap" to 5.0)))
        assertTrue(matches(mapOf("mcap" to 5.0, "liquidity" to 5.0)))
        assertFalse(matches(mapOf("mcap" to 5.0, "liquidity" to Double.NaN)))
    }

    @Test fun oldProxyModelRemainsArchivedAndCannotLoadAsObservedModel() {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getFloat("learning_rate", 0.1f)).thenReturn(0.1f)
        `when`(prefs.getInt("trade_count", 0)).thenReturn(137)
        `when`(prefs.getString("feature_weights", null)).thenReturn(JSONObject().put("mcap", 2.5).toString())
        `when`(prefs.getString("feature_buffer_v2", "[]")).thenReturn("[{\"mc\":0,\"ag\":0,\"pnl\":25}]")
        field("prefs").set(AdaptiveLearningEngine, prefs)
        invoke("loadState", emptyArray())
        assertEquals(1.0, AdaptiveLearningEngine.getDetailedWeights().getValue("mcap"), 0.0)
        assertTrue(buffer().isEmpty())
        assertEquals("legacy trade_count cannot prove maturity of the new observed model", 0, AdaptiveLearningEngine.getTradeCount())
        verify(prefs).getInt("terminal_count_observed_8527", 0)
        AdaptiveLearningEngine.learnFromTrade(partial(20_000.0), TradeEnvironment.LIVE)
        verify(editor, never()).putInt(eq("trade_count"), anyInt())
        verify(editor, never()).putString(eq("feature_weights"), anyString())
        verify(editor, never()).putString(eq("feature_buffer_v2"), anyString())
        verify(editor).putString(eq("feature_weights_observed_8527"), anyString())
        verify(editor).putString(eq("feature_buffer_observed_8527"), anyString())
    }
}
