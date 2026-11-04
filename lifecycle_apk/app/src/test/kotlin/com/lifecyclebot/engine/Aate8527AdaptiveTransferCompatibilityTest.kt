package com.lifecyclebot.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527AdaptiveTransferCompatibilityTest {
    private val now = 1_800_000_000_000L
    private val ttl = 7L * 24L * 60L * 60L * 1000L
    private val wire = AdaptiveWeightTransfer8527
    private fun local(
        n: Int = 100, featureN: Int = n,
        economics: AdaptiveWeightTransfer8527.TerminalEconomics? = null,
    ) = wire.localModel8527(mapOf("mcap" to 2.0), mapOf("mcap" to featureN), n, economics)
    private fun goodEconomics(n: Int = 100) =
        AdaptiveWeightTransfer8527.TerminalEconomics(n, 1.0, 2.0, 1.0)
    private fun root(model: AdaptiveWeightTransfer8527.LocalModel) =
        JSONObject().put(wire.SCHEMA_KEY, wire.encodeLocal8527(model))

    @Test fun ExactSchemaAndLiveModeAreRequiredAndNumericVersionMarkersAreNotEvidence() {
        val payload = root(local())
        assertNotNull(wire.decodeLocal8527(payload))
        for (wrong in listOf(
            wire.SCHEMA_KEY.replace("_LIVE_", "_PAPER_"),
            wire.SCHEMA_KEY.replace("_LIVE_", "_SHADOW_"),
            wire.SCHEMA_KEY.replace("8527", "8526"),
            "ALE8527", "LIVE",
        )) assertNull(wire.decodeLocal8527(JSONObject().put(wrong, wire.encodeLocal8527(local()))))
        assertNull(wire.decodeLocal8527(JSONObject().put("version", 8527).put("mcap", 2.0)))
        assertNull(wire.decodeLocal8527(JSONObject().put(wire.SCHEMA_KEY, 1.0)))
        assertNull(wire.cloudContribution8527("peer", "mcap", 2.0, 100, now))
        assertNull(wire.cloudContribution8527("peer", "LYR:EntryAI", 0.9, 100, now))
        assertNull(wire.cloudContribution8527("peer",
            wire.WIRE_PREFIX.replace("_LIVE_", "_PAPER_") + "mcap", 2.0, 100, now))
    }

    @Test fun SparseFeaturesCannotBorrowModelGlobalOrBacktestCounts() {
        val model = wire.localModel8527(
            mapOf("mcap" to 2.0, "buyRatio" to 2.0, "unknown" to 1.0),
            mapOf("mcap" to 1, "buyRatio" to 100, "unknown" to 100), 999_999)
        assertFalse(model.weights.containsKey("unknown"))
        assertEquals(1, wire.cloudSampleCount8527(model, wire.WIRE_PREFIX + "mcap"))
        assertEquals(100, wire.cloudSampleCount8527(model, wire.WIRE_PREFIX + "buyRatio"))
        val merged = wire.aggregate8527(wire.contributions8527("peer", model, now), now, ttl)
        assertFalse(wire.importableWeights8527(merged).containsKey("mcap"))
        assertTrue(wire.importableWeights8527(merged).containsKey("buyRatio"))
        assertEquals(1, merged.features.getValue("mcap").samples)
        assertEquals(1, merged.features.getValue("mcap").contributors)
        assertTrue(wire.localModel8527(mapOf("mcap" to 1.0), emptyMap(), 999_999).weights.isEmpty())
    }

    @Test fun CountAndValueDomainsAreValidatedBeforeExportAndAfterJsonRestore() {
        for (n in listOf(-1, 0, 101, 301)) {
            assertTrue(wire.localModel8527(mapOf("mcap" to 1.0), mapOf("mcap" to n), 100).weights.isEmpty())
        }
        for (w in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.29, 2.51)) {
            assertTrue(wire.localModel8527(mapOf("mcap" to w), mapOf("mcap" to 100), 100).weights.isEmpty())
        }
        val fractional = root(local())
        fractional.getJSONObject(wire.SCHEMA_KEY).getJSONObject("features").getJSONObject("mcap").put("samples", 99.5)
        assertNull(wire.decodeLocal8527(fractional))
        val missing = root(local())
        missing.getJSONObject(wire.SCHEMA_KEY).getJSONObject("features").getJSONObject("mcap").remove("samples")
        assertNull(wire.decodeLocal8527(missing))
        assertFalse(wire.exportReady8527(local(19)))
        assertTrue(wire.exportReady8527(local(20)))
        assertFalse(wire.exportReady8527(AdaptiveWeightTransfer8527.LocalModel(mapOf("mcap" to 2.0), emptyMap(), 1_000_000)))
    }

    @Test fun AggregationCountsOnlyActualLatestCompatibleContributorsPerFeature() {
        val rows = listOf(
            AdaptiveWeightTransfer8527.Contribution("a", "mcap", 1.0, 10, now),
            AdaptiveWeightTransfer8527.Contribution("a", "mcap", 2.5, 300, now - 1),
            AdaptiveWeightTransfer8527.Contribution("b", "mcap", 2.0, 30, now),
            AdaptiveWeightTransfer8527.Contribution("a", "age", 1.5, 3, now),
            AdaptiveWeightTransfer8527.Contribution("own", "mcap", 2.5, 300, now),
            AdaptiveWeightTransfer8527.Contribution("future", "mcap", 2.5, 300, now + 1),
            AdaptiveWeightTransfer8527.Contribution("stale", "mcap", 2.5, 300, now - ttl - 1),
            AdaptiveWeightTransfer8527.Contribution("legacy", "LYR:EntryAI", 0.9, 300, now),
        )
        val model = wire.aggregate8527(rows, now, ttl, "own")
        val mcap = model.features.getValue("mcap")
        assertEquals(1.75, mcap.weight, 1e-12)
        assertEquals(40, mcap.samples)
        assertEquals(2, mcap.contributors)
        assertEquals(3, model.features.getValue("age").samples)
        assertEquals(1, model.features.getValue("age").contributors)
        assertEquals(setOf("mcap"), wire.importableWeights8527(model).keys)
        val many = (1..40).map { AdaptiveWeightTransfer8527.Contribution("peer" + it, "mcap", 1.0, 300, now) }
        val capped = wire.aggregate8527(many, now, ttl).features.getValue("mcap")
        assertEquals(25, capped.contributors)
        assertEquals(7_500, capped.samples)
    }

    @Test fun HiveUsesActualPositiveSolAndPayoffNotWinRateOrPooledHistory() {
        val lowWinRateRunner = root(local(economics = goodEconomics())).put("win_rate_pct", 10.0)
        assertTrue(wire.hiveEligible8527(wire.decodeLocal8527(lowWinRateRunner)!!))
        val highWinRateLoser = local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(100, -1.0, 1.0, 2.0))
        assertFalse(wire.hiveEligible8527(highWinRateLoser))
        assertFalse(wire.hiveEligible8527(local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(100, 0.09, 1.09, 1.0))))
        assertTrue(wire.hiveEligible8527(local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(100, 0.1, 1.1, 1.0))))
        assertTrue(wire.hiveEligible8527(local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(100, 1.0, 1.0, 0.0))))
        assertFalse(wire.hiveEligible8527(local()))
        assertFalse(wire.hiveEligible8527(local(economics = goodEconomics(99))))
        assertFalse(wire.hiveEligible8527(local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(100, 50.0, 2.0, 1.0))))
        assertFalse(wire.hiveEligible8527(local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(100, Double.NaN, 2.0, 1.0))))
        assertFalse(wire.hiveEligible8527(local(economics =
            AdaptiveWeightTransfer8527.TerminalEconomics(101, 1.0, 2.0, 1.0))))
        val restored = wire.decodeLocal8527(JSONObject(lowWinRateRunner.toString()))!!
        assertEquals(goodEconomics(), restored.terminalEconomics)
    }

    @Test fun ImportedSupportCannotBeInventedByLegacyCacheOrMalformedContributorCounts() {
        assertTrue(wire.decodeCompatible8527(JSONObject().put("mcap", 2.0)).features.isEmpty())
        val valid = wire.aggregate8527(wire.contributions8527("peer", local(), now), now, ttl)
        assertEquals(valid, wire.decodeCompatible8527(JSONObject(wire.encodeCompatible8527(valid).toString())))
        for (bad in listOf(
            AdaptiveWeightTransfer8527.FeatureEvidence(2.0, 100, 0),
            AdaptiveWeightTransfer8527.FeatureEvidence(2.0, 100, 26),
            AdaptiveWeightTransfer8527.FeatureEvidence(2.0, 301, 1),
            AdaptiveWeightTransfer8527.FeatureEvidence(Double.NaN, 100, 1),
        )) {
            assertTrue(wire.importableWeights8527(AdaptiveWeightTransfer8527.CompatibleModel(mapOf("mcap" to bad))).isEmpty())
        }
        val current = mapOf("mcap" to 1.0, "holderCount" to 1.0)
        val cold = AdaptiveWeightTransfer8527.CompatibleModel()
        assertEquals(current, wire.communityBlend8527(current, cold, 100_000))
        assertEquals(current, wire.hiveNudge8527(current, cold, 100_000))
        assertEquals(1.14, wire.hiveNudge8527(current, valid, 0).getValue("mcap"), 1e-12)
        assertEquals(1.07, wire.hiveNudge8527(current, valid, 500).getValue("mcap"), 1e-12)
        assertEquals(1.7, wire.communityBlend8527(current, valid, 0).getValue("mcap"), 1e-12)
        assertEquals(1.0, wire.communityBlend8527(current, valid, 0).getValue("holderCount"), 0.0)
    }

    @Test fun DisabledOrUnverifiableCachedCommunityCannotReapply() = runBlocking {
        val field = CloudLearningSync::class.java.getDeclaredField("communityWeights").apply { isAccessible = true }
        val saved = field.get(null)
        val enabled = CloudLearningSync.isUsingCommunityWeights()
        try {
            val t = System.currentTimeMillis()
            val compatible = wire.aggregate8527(wire.contributions8527("peer", local(), t), t, ttl)
            val cache = CloudLearningSync.CommunityWeights(
                mapOf("mcap" to 2.0), emptyMap(), 1, 100, t, compatible)
            field.set(null, cache)
            CloudLearningSync.setUseCommunityWeights(false)
            assertNull(CloudLearningSync.downloadCommunityWeights())
            assertEquals(mapOf("mcap" to 1.0), CloudLearningSync.blendWeights(mapOf("mcap" to 1.0), 999_999))
            CloudLearningSync.setUseCommunityWeights(true)
            assertNotNull(CloudLearningSync.downloadCommunityWeights())
            field.set(null, cache.copy(lastUpdated = t - ttl - 1_000L))
            assertNull(CloudLearningSync.downloadCommunityWeights())
            field.set(null, cache.copy(lastUpdated = t + 60_000L))
            assertNull(CloudLearningSync.downloadCommunityWeights())
            field.set(null, cache.copy(adaptive8527 = AdaptiveWeightTransfer8527.CompatibleModel()))
            assertEquals(mapOf("mcap" to 1.0), CloudLearningSync.blendWeights(mapOf("mcap" to 1.0), 999_999))
        } finally {
            field.set(null, saved)
            CloudLearningSync.setUseCommunityWeights(enabled)
        }
    }

    @Test fun TransferWiringUsesScopedCountsAndDoesNotResetSharedDatabase() {
        fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()
        val cloud = src("engine/CloudLearningSync.kt")
        assertFalse(cloud.contains("DROP TABLE"))
        assertTrue(cloud.contains("private const val CURRENT_SCHEMA_VERSION = 2"))
        assertTrue(cloud.contains("private const val SECURE_HIVE_GATEWAY_READY_6637 = false"))
        assertTrue(cloud.contains("payload.adaptiveFeatureSamples8527.getValue(feature)"))
        assertTrue(cloud.contains("substr(feature_name, 1, ?) = ?"))
        val download = cloud.substring(cloud.indexOf("suspend fun downloadCommunityWeights"))
        assertTrue(download.indexOf("if (!usingCommunityWeights) return null") < download.indexOf("if (!isConfigured())"))
        val hive = src("collective/CollectiveLearning.kt")
        val query = hive.substring(hive.indexOf("val adaptiveRows8527 ="))
            .substringBefore("val adaptiveContributions8527")
        for (legacy in listOf("win_rate_pct", "net_pnl_sol", "profit_factor", "total_trades")) assertFalse(query.contains(legacy))
        assertTrue(query.contains("ORDER BY updated_at DESC"))
        assertTrue(hive.contains("if (!AdaptiveWeightTransfer8527.hiveEligible8527(model)) continue"))
        assertTrue(hive.contains("AND win_rate_pct >= 55.0 AND net_pnl_sol > 0.0 AND profit_factor >= 1.10"))
        assertTrue(hive.contains("if (!legacyGenomeReady8527 && !adaptiveGenomeReady8527) return false"))
        val bot = src("engine/BotService.kt")
        assertEquals(4, Regex("compatible8527 = (weights|communityWeights|genome|genome6865)\\.adaptive8527").findAll(bot).count())
        assertEquals(3, Regex("adaptive8527 = adaptiveTransfer8527").findAll(bot).count())
        assertFalse(bot.contains("val genomeWeights6865 = AdaptiveLearningEngine.getDetailedWeights() + layerAcc6880"))
    }
}
