package com.lifecyclebot.engine

import org.json.JSONObject

/**
 * Transfer contract for the observed-entry, LIVE, whole-position adaptive model.
 * Database layout/version is unrelated. Legacy proxy-trained weights and pooled counts
 * never become compatible merely because their feature names happen to match.
 */
object AdaptiveWeightTransfer8527 {
    const val SCHEMA_KEY = "ALE8527_LIVE_WHOLE_POSITION_OBSERVED_V1"
    const val WIRE_PREFIX = SCHEMA_KEY + ":"
    const val MAX_FEATURE_SAMPLES = 300
    private const val MAX_CONTRIBUTORS = 25

    data class LocalModel(
        val weights: Map<String, Double> = emptyMap(),
        val featureSamples: Map<String, Int> = emptyMap(),
        val trainedPositions: Int = 0,
        val terminalEconomics: TerminalEconomics? = null,
    )
    data class TerminalEconomics(
        val samples: Int, val netPnlSol: Double, val winningPnlSol: Double, val losingPnlSolAbs: Double,
    )
    data class FeatureEvidence(val weight: Double, val samples: Int, val contributors: Int)
    data class CompatibleModel(val features: Map<String, FeatureEvidence> = emptyMap())
    data class Contribution(
        val peer: String, val feature: String, val weight: Double, val samples: Int,
        val observedAtMs: Long,
    )

    private val bounds = mapOf(
        "mcap" to (0.3 to 2.5), "age" to (0.3 to 2.5), "buyRatio" to (0.5 to 3.0),
        "volume" to (0.3 to 2.5), "liquidity" to (0.3 to 2.5), "holderCount" to (0.3 to 2.5),
        "holderConc" to (0.5 to 3.0), "holderGrowth" to (0.5 to 2.5), "devWallet" to (0.5 to 3.0),
        "bondingCurve" to (0.3 to 2.5), "rugcheck" to (0.5 to 3.0), "emaFan" to (0.5 to 3.0),
        "volLiqRatio" to (0.3 to 2.5), "athDistance" to (0.3 to 2.5),
        "holdTime" to (0.4 to 2.5), "maxGainFollow" to (0.4 to 2.5), "exitQuality" to (0.4 to 2.5),
    )

    private fun validWeight(feature: String, weight: Double): Boolean =
        bounds[feature]?.let { weight.isFinite() && weight in it.first..it.second } == true

    fun localModel8527(
        weights: Map<String, Double>, featureSamples: Map<String, Int>, trainedPositions: Int,
        terminalEconomics: TerminalEconomics? = null,
    ): LocalModel {
        if (trainedPositions <= 0) return LocalModel()
        val accepted = weights.filter { (feature, weight) ->
            validWeight(feature, weight) &&
                featureSamples[feature]?.let { it in 1..MAX_FEATURE_SAMPLES && it <= trainedPositions } == true
        }
        return LocalModel(accepted.toMap(), accepted.keys.associateWith { featureSamples.getValue(it) },
            trainedPositions, terminalEconomics?.takeIf { validEconomics8527(it, trainedPositions) })
    }

    fun exportReady8527(model: LocalModel, minimumPositions: Int = 20): Boolean {
        val valid = localModel8527(model.weights, model.featureSamples, model.trainedPositions, model.terminalEconomics)
        return valid.trainedPositions >= minimumPositions && valid.weights.isNotEmpty()
    }

    /** Only namespaced SQL rows can carry this model; counts stay counts, never averaged weights. */
    fun cloudWeights8527(model: LocalModel): Map<String, Double> {
        val valid = localModel8527(model.weights, model.featureSamples, model.trainedPositions, model.terminalEconomics)
        return valid.weights.mapKeys { WIRE_PREFIX + it.key }
    }

    fun cloudSampleCount8527(model: LocalModel, wireName: String): Int? {
        if (!wireName.startsWith(WIRE_PREFIX)) return null
        val feature = wireName.removePrefix(WIRE_PREFIX)
        val valid = localModel8527(model.weights, model.featureSamples, model.trainedPositions, model.terminalEconomics)
        return valid.featureSamples[feature]
    }

    fun cloudContribution8527(
        peer: String, wireName: String, weight: Double, samples: Int, atMs: Long,
    ): Contribution? {
        if (!wireName.startsWith(WIRE_PREFIX)) return null
        val feature = wireName.removePrefix(WIRE_PREFIX)
        return Contribution(peer, feature, weight, samples, atMs)
            .takeIf { validContribution8527(it) }
    }

    fun encodeLocal8527(model: LocalModel): JSONObject {
        val valid = localModel8527(model.weights, model.featureSamples, model.trainedPositions, model.terminalEconomics)
        return JSONObject().apply {
            put("trainedPositions", valid.trainedPositions)
            valid.terminalEconomics?.let { e ->
                put("terminalEconomics", JSONObject().put("samples", e.samples).put("netPnlSol", e.netPnlSol)
                    .put("winningPnlSol", e.winningPnlSol).put("losingPnlSolAbs", e.losingPnlSolAbs))
            }
            put("features", JSONObject().apply {
                valid.weights.forEach { (feature, weight) ->
                    put(feature, JSONObject().put("weight", weight).put("samples", valid.featureSamples.getValue(feature)))
                }
            })
        }
    }

    /** The exact root key binds schema AND mode; no version-number averaging or legacy fallback. */
    fun decodeLocal8527(root: JSONObject): LocalModel? {
        val obj = root.optJSONObject(SCHEMA_KEY) ?: return null
        val trained = exactInt8527(obj.opt("trainedPositions")) ?: return null
        val features = obj.optJSONObject("features") ?: return null
        val weights = mutableMapOf<String, Double>()
        val counts = mutableMapOf<String, Int>()
        features.keys().forEach { feature ->
            val row = features.optJSONObject(feature) ?: return@forEach
            val weight = (row.opt("weight") as? Number)?.toDouble() ?: return@forEach
            val count = exactInt8527(row.opt("samples")) ?: return@forEach
            weights[feature] = weight
            counts[feature] = count
        }
        val economics = obj.optJSONObject("terminalEconomics")?.let { e ->
            val n = exactInt8527(e.opt("samples")) ?: return@let null
            val net = (e.opt("netPnlSol") as? Number)?.toDouble() ?: return@let null
            val wins = (e.opt("winningPnlSol") as? Number)?.toDouble() ?: return@let null
            val losses = (e.opt("losingPnlSolAbs") as? Number)?.toDouble() ?: return@let null
            TerminalEconomics(n, net, wins, losses)
        }
        return localModel8527(weights, counts, trained, economics).takeIf { it.weights.isNotEmpty() }
    }

    fun contributions8527(peer: String, model: LocalModel, atMs: Long): List<Contribution> {
        val valid = localModel8527(model.weights, model.featureSamples, model.trainedPositions, model.terminalEconomics)
        return valid.weights.map { (feature, weight) ->
            Contribution(peer, feature, weight, valid.featureSamples.getValue(feature), atMs)
        }.filter { validContribution8527(it) }
    }

    private fun validEconomics8527(e: TerminalEconomics, trainedPositions: Int): Boolean {
        if (e.samples !in 1..MAX_FEATURE_SAMPLES || e.samples > trainedPositions ||
            !e.netPnlSol.isFinite() || !e.winningPnlSol.isFinite() || !e.losingPnlSolAbs.isFinite() ||
            e.winningPnlSol < 0.0 || e.losingPnlSolAbs < 0.0) return false
        val reconstructed = e.winningPnlSol - e.losingPnlSolAbs
        val tolerance = 1e-9 * maxOf(1.0, kotlin.math.abs(e.netPnlSol), e.winningPnlSol, e.losingPnlSolAbs)
        return kotlin.math.abs(e.netPnlSol - reconstructed) <= tolerance
    }

    /** Current schema's actual LIVE SOL economics, not old pooled win rate or percentages. */
    fun hiveEligible8527(model: LocalModel): Boolean {
        val valid = localModel8527(model.weights, model.featureSamples, model.trainedPositions, model.terminalEconomics)
        val e = valid.terminalEconomics ?: return false
        if (valid.weights.isEmpty() || valid.trainedPositions < 100 || e.samples < 100 || e.netPnlSol <= 0.0) return false
        return if (e.losingPnlSolAbs == 0.0) e.winningPnlSol > 0.0
            else e.winningPnlSol / e.losingPnlSolAbs >= 1.10
    }

    private fun validContribution8527(c: Contribution): Boolean =
        c.peer.isNotBlank() && c.observedAtMs > 0L && validWeight(c.feature, c.weight) &&
            c.samples in 1..MAX_FEATURE_SAMPLES

    /** Each feature counts only peers that actually contributed that observed feature. */
    fun aggregate8527(
        contributions: List<Contribution>, nowMs: Long, maxAgeMs: Long, ownPeer: String = "",
    ): CompatibleModel {
        if (nowMs <= 0L || maxAgeMs < 0L) return CompatibleModel()
        val fresh = contributions.filter {
            validContribution8527(it) && it.peer != ownPeer &&
                it.observedAtMs <= nowMs && nowMs - it.observedAtMs <= maxAgeMs
        }
        val result = mutableMapOf<String, FeatureEvidence>()
        for ((feature, rows) in fresh.groupBy { it.feature }) {
            val peers = rows.sortedByDescending { it.observedAtMs }.distinctBy { it.peer }
                .sortedWith(compareByDescending<Contribution> { it.samples }.thenBy { it.peer })
                .take(MAX_CONTRIBUTORS)
            val samples = peers.sumOf { it.samples }
            if (samples <= 0) continue
            val weight = peers.sumOf { it.weight * it.samples } / samples
            if (validWeight(feature, weight))
                result[feature] = FeatureEvidence(weight, samples, peers.size)
        }
        return CompatibleModel(result)
    }

    fun importableWeights8527(model: CompatibleModel, minSamples: Int = 25): Map<String, Double> =
        model.features.filter { (feature, e) ->
            validWeight(feature, e.weight) && e.contributors in 1..MAX_CONTRIBUTORS &&
                e.samples >= maxOf(minSamples, e.contributors) &&
                e.samples <= e.contributors * MAX_FEATURE_SAMPLES
        }.mapValues { it.value.weight }

    /** Keep the existing community-owned feature set and blend ranges, but require real support. */
    fun communityBlend8527(
        current: Map<String, Double>, model: CompatibleModel, localTradeCount: Int,
    ): Map<String, Double> {
        val owned = setOf("mcap", "age", "buyRatio", "volume", "liquidity", "holderConc", "holderGrowth", "emaFan", "volLiqRatio")
        val targets = importableWeights8527(model, 25).filterKeys { it in owned }
        if (targets.isEmpty()) return current
        val localRatio = when { localTradeCount < 100 -> 0.3; localTradeCount < 500 -> 0.5; else -> 0.7 }
        val out = current.toMutableMap()
        targets.forEach { (feature, target) ->
            val before = current[feature] ?: return@forEach
            if (!validWeight(feature, before)) return@forEach
            val range = bounds.getValue(feature)
            out[feature] = (before * localRatio + target * (1.0 - localRatio)).coerceIn(range.first, range.second)
        }
        return out
    }

    /** Imported sample/peer authority comes from each compatible feature, never pooled history WR/count. */
    fun hiveNudge8527(
        current: Map<String, Double>, model: CompatibleModel, localTradeCount: Int,
    ): Map<String, Double> {
        val targets = importableWeights8527(model, 100)
        if (targets.isEmpty()) return current
        val out = current.toMutableMap()
        targets.forEach { (feature, target) ->
            val before = current[feature] ?: return@forEach
            if (!validWeight(feature, before)) return@forEach
            val peers = model.features.getValue(feature).contributors
            val baseStrength = if (localTradeCount < 100) 0.18 else 0.07
            val strength = (baseStrength + if (peers >= 5) 0.04 else if (peers >= 2) 0.02 else 0.0).coerceIn(0.03, 0.28)
            val maxStep = if (localTradeCount < 100) 0.14 else 0.08
            val range = bounds.getValue(feature)
            out[feature] = (before + ((target - before) * strength).coerceIn(-maxStep, maxStep))
                .coerceIn(range.first, range.second)
        }
        return out
    }

    /** Cache schema is explicit too. Old cached bare maps do not acquire support on restart. */
    fun encodeCompatible8527(model: CompatibleModel): JSONObject = JSONObject().apply {
        val valid = importableWeights8527(model, 1)
        put(SCHEMA_KEY, JSONObject().apply {
            valid.forEach { (feature, weight) ->
                val e = model.features.getValue(feature)
                put(feature, JSONObject().put("weight", weight).put("samples", e.samples).put("contributors", e.contributors))
            }
        })
    }

    fun decodeCompatible8527(root: JSONObject?): CompatibleModel {
        val obj = root?.optJSONObject(SCHEMA_KEY) ?: return CompatibleModel()
        val evidence = mutableMapOf<String, FeatureEvidence>()
        obj.keys().forEach { feature ->
            val row = obj.optJSONObject(feature) ?: return@forEach
            val weight = (row.opt("weight") as? Number)?.toDouble() ?: return@forEach
            val samples = exactInt8527(row.opt("samples")) ?: return@forEach
            val contributors = exactInt8527(row.opt("contributors")) ?: return@forEach
            evidence[feature] = FeatureEvidence(weight, samples, contributors)
        }
        val model = CompatibleModel(evidence)
        val valid = importableWeights8527(model, 1)
        return CompatibleModel(evidence.filterKeys { it in valid })
    }

    private fun exactInt8527(raw: Any?): Int? {
        val d = (raw as? Number)?.toDouble() ?: return null
        if (!d.isFinite() || d < 0.0 || d > Int.MAX_VALUE.toDouble() || d != d.toInt().toDouble()) return null
        return d.toInt()
    }

    fun cacheApplicable8527(enabled: Boolean, updatedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        enabled && updatedAtMs > 0L && updatedAtMs <= nowMs && maxAgeMs >= 0L && nowMs - updatedAtMs <= maxAgeMs
}
