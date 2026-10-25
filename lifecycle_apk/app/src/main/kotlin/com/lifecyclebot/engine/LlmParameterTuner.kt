package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.9.83 — Tier-2 Autonomous Parameter Tuner.
 *
 * Parses the LLM's optional <<TUNE>>...<<ENDTUNE>> block at the end of a reply
 * and softly nudges whitelisted BotConfig parameters. Every change is clamped,
 * logged to ErrorLogger, announced on SentientPersonality, and then persisted
 * via ConfigStore so the live bot picks it up on next cfg() read.
 *
 * HARD RULES:
 *  - LLM can NEVER buy/sell directly.
 *  - LLM can NEVER flip paperMode, change wallet/keys, or disable safety caps.
 *  - Only parameters in ALLOWED_SPECS are touchable, each with its own clamp.
 *  - Max 3 adjustments per reply.
 */
object LlmParameterTuner {

    private const val TAG = "LlmTuner"
    private const val MAX_ADJUSTMENTS_PER_CALL = 3

    private val BLOCK_REGEX = Regex(
        "<<\\s*TUNE\\s*>>\\s*(\\{[\\s\\S]*?\\})\\s*<<\\s*ENDTUNE\\s*>>",
        RegexOption.IGNORE_CASE
    )

    data class Applied(
        val cleanedReply: String,
        val changes: List<Change>,
        val rejected: List<String>
    ) {
        val hadBlock: Boolean get() = changes.isNotEmpty() || rejected.isNotEmpty()
    }

    data class Change(
        val key: String,
        val oldValue: Double,
        val newValue: Double,
        val reason: String
    )

    private data class ParamSpec(
        val get: (BotConfig) -> Double,
        val set: (BotConfig, Double) -> BotConfig,
        val min: Double,
        val max: Double,
        val maxStep: Double
    )

    private val ALLOWED_SPECS: Map<String, ParamSpec> = mapOf(
        "stopLossPct" to ParamSpec(
            { it.stopLossPct }, { c, v -> c.copy(stopLossPct = v) },
            min = 2.0, max = 25.0, maxStep = 3.0
        ),
        "trailingStopBasePct" to ParamSpec(
            { it.trailingStopBasePct }, { c, v -> c.copy(trailingStopBasePct = v) },
            min = 2.0, max = 20.0, maxStep = 3.0
        ),
        "exitScoreThreshold" to ParamSpec(
            { it.exitScoreThreshold }, { c, v -> c.copy(exitScoreThreshold = v) },
            min = 40.0, max = 80.0, maxStep = 6.0
        ),
        "entryCooldownSec" to ParamSpec(
            { it.entryCooldownSec.toDouble() },
            { c, v -> c.copy(entryCooldownSec = v.toInt()) },
            min = 30.0, max = 600.0, maxStep = 60.0
        ),
        "pollSeconds" to ParamSpec(
            { it.pollSeconds.toDouble() },
            { c, v -> c.copy(pollSeconds = v.toInt()) },
            min = 3.0, max = 30.0, maxStep = 5.0
        ),
        // V5.0.8363 — slippageBps removed: it feeds partial-TP and orphan sells, and the advisor tightened it with a
        // forced agreement. Advisory-only now (AdvisorKeyAuthority8353.ADVISORY_ONLY_KEYS_8353), like 8353's keys.
        // V5.0.8319 — perPositionSizePct removed: it has no readers outside
        // config/UI, so a tune moved nothing. Not wired into sizing.
        "minHoldMins" to ParamSpec(
            { it.minHoldMins }, { c, v -> c.copy(minHoldMins = v) },
            min = 0.5, max = 15.0, maxStep = 3.0
        ),
        "maxHoldMinsHard" to ParamSpec(
            { it.maxHoldMinsHard }, { c, v -> c.copy(maxHoldMinsHard = v) },
            min = 15.0, max = 720.0, maxStep = 60.0
        ),
        "sentimentEntryBoost" to ParamSpec(
            { it.sentimentEntryBoost }, { c, v -> c.copy(sentimentEntryBoost = v) },
            min = 0.0, max = 40.0, maxStep = 5.0
        ),
        "sentimentExitBoost" to ParamSpec(
            { it.sentimentExitBoost }, { c, v -> c.copy(sentimentExitBoost = v) },
            min = 0.0, max = 40.0, maxStep = 5.0
        ),
        "sentimentBlockThreshold" to ParamSpec(
            { it.sentimentBlockThreshold },
            { c, v -> c.copy(sentimentBlockThreshold = v) },
            min = -80.0, max = -20.0, maxStep = 10.0
        ),
        "behaviorAggressionLevel" to ParamSpec(
            { it.behaviorAggressionLevel.toDouble() },
            { c, v -> c.copy(behaviorAggressionLevel = v.toInt()) },
            min = 0.0, max = 11.0, maxStep = 2.0
        ),
        "defensiveLossThreshold" to ParamSpec(
            { it.defensiveLossThreshold.toDouble() },
            { c, v -> c.copy(defensiveLossThreshold = v.toInt()) },
            min = 1.0, max = 10.0, maxStep = 2.0
        ),
        "aggressiveWhaleThreshold" to ParamSpec(
            { it.aggressiveWhaleThreshold },
            { c, v -> c.copy(aggressiveWhaleThreshold = v) },
            min = 30.0, max = 90.0, maxStep = 10.0
        ),
        "convictionMult1" to ParamSpec(
            { it.convictionMult1 }, { c, v -> c.copy(convictionMult1 = v) },
            min = 1.0, max = 2.0, maxStep = 0.2
        ),
        "convictionMult2" to ParamSpec(
            { it.convictionMult2 }, { c, v -> c.copy(convictionMult2 = v) },
            min = 1.0, max = 2.5, maxStep = 0.25
        ),
        "partialSellTriggerPct" to ParamSpec(
            { it.partialSellTriggerPct }, { c, v -> c.copy(partialSellTriggerPct = v) },
            min = 50.0, max = 500.0, maxStep = 75.0
        ),
        "topUpSizeMultiplier" to ParamSpec(
            { it.topUpSizeMultiplier }, { c, v -> c.copy(topUpSizeMultiplier = v) },
            min = 0.1, max = 1.0, maxStep = 0.2
        ),
        "topUpMaxCount" to ParamSpec(
            { it.topUpMaxCount.toDouble() },
            { c, v -> c.copy(topUpMaxCount = v.toInt()) },
            min = 0.0, max = 10.0, maxStep = 2.0
        ),
        // V5.0.8353 — minLiquidityUsd and walletReserveSol removed: owner-only keys the LLM was never
        // granted (8351 auto-applied minLiquidityUsd 8000→10000 "because liquidity blocks prevent
        // trades"). Advisory-only now: see AdvisorKeyAuthority8353.ADVISORY_ONLY_KEYS_8353.
        "scanIntervalSecs" to ParamSpec(
            { it.scanIntervalSecs.toDouble() },
            { c, v -> c.copy(scanIntervalSecs = v.toInt()) },
            min = 3.0, max = 60.0, maxStep = 10.0
        ),
        // 8365: minDiscoveryScore deliberately excluded. Its config value has no
        // decision-path consumer: changing it looks like learning in the advisor
        // timeline but cannot alter discovered tokens. Reinstate ONLY with a
        // tested, live reader and evidence-calibrated semantics.
    )

    data class Contract8386(val unit: String, val scope: String, val consumer: String,
                            val increasingEffect: String, val decreasingEffect: String,
                            val precedence: String = "global default; mode/lane overrides and hard safety win")

    private fun contract8386(key: String): Contract8386? = when (key) {
        "exitScoreThreshold" -> Contract8386("score_points", "exit", "LifecycleStrategy: exitScore >= effectiveExitThresh",
            "harder_exit", "easier_exit", "modeConf.exitScoreThreshold overrides global; brain delta follows; clamp 35..75")
        "stopLossPct", "trailingStopBasePct" -> Contract8386("percentage_points", "exit", "LifecycleStrategy loss/trailing thresholds", "wider_loss_tolerance", "tighter_loss_tolerance")
        "partialSellTriggerPct" -> Contract8386("percentage_points", "exit", "partial profit threshold", "later_partial", "earlier_partial")
        "minHoldMins", "maxHoldMinsHard" -> Contract8386("minutes", "exit", "hold-time controls", "longer_hold", "shorter_hold")
        "entryCooldownSec" -> Contract8386("seconds", "entry", "entry cooldown", "longer_cooldown", "shorter_cooldown")
        "pollSeconds", "scanIntervalSecs" -> Contract8386("seconds", "data", "poll/scanner cadence", "slower_polling", "faster_polling")
        "sentimentEntryBoost" -> Contract8386("score_points", "entry", "sentiment entry shaping", "stronger_entry_boost", "weaker_entry_boost")
        "sentimentExitBoost" -> Contract8386("score_points", "exit", "sentiment exit shaping", "stronger_exit_boost", "weaker_exit_boost")
        "sentimentBlockThreshold" -> Contract8386("score_points", "entry", "sentiment negative threshold", "stricter_sentiment", "looser_sentiment")
        "behaviorAggressionLevel" -> Contract8386("level", "entry", "behavior aggression", "more_aggressive", "less_aggressive")
        "defensiveLossThreshold" -> Contract8386("count", "entry", "loss-streak defense", "later_defense", "earlier_defense")
        "aggressiveWhaleThreshold" -> Contract8386("percentage_points", "entry", "whale pressure threshold", "higher_whale_requirement", "lower_whale_requirement")
        "convictionMult1", "convictionMult2", "topUpSizeMultiplier" -> Contract8386("multiplier", "entry", "bounded sizing advisory", "larger_advisory", "smaller_advisory")
        "topUpMaxCount" -> Contract8386("count", "entry", "top-up count limit", "more_topups", "fewer_topups")
        else -> null
    }

    fun proposalError8386(key: String, delta: Double, unit: String, scope: String, semantics: String, effect: String): String? {
        val c = contract8386(key) ?: return "unknown_parameter_contract"
        if (!delta.isFinite() || delta == 0.0) return "invalid_delta"
        if (unit != c.unit) return "wrong_units_expected_${c.unit}"
        if (scope != c.scope) return "wrong_scope_expected_${c.scope}"
        if (semantics != "additive") return "relative_change_not_supported"
        if (effect != if (delta > 0) c.increasingEffect else c.decreasingEffect) return "inconsistent_direction"
        return null
    }

    fun explain8386(key: String, before: Double, after: Double): String {
        val c = contract8386(key) ?: return "unknown contract"
        val delta = after - before
        val effect = if (delta > 0) c.increasingEffect else c.decreasingEffect
        return "global default $before to $after; additive delta=$delta ${c.unit}; scope=${c.scope}; " +
            "effect=$effect; consumer=${c.consumer}; precedence=${c.precedence}; effective mode change not asserted"
    }

    fun addContract8386(row: org.json.JSONObject, key: String, delta: Double): org.json.JSONObject {
        val c = contract8386(key) ?: return row
        return row.put("unit", c.unit).put("scope", c.scope).put("semantics", "additive")
            .put("effect", if (delta > 0) c.increasingEffect else c.decreasingEffect)
    }

    fun contractSummary8386(): String = ALLOWED_SPECS.keys.mapNotNull { key -> contract8386(key)?.let {
        "$key: unit=${it.unit}, scope=${it.scope}, semantics=additive, plus=${it.increasingEffect}, minus=${it.decreasingEffect}; ${it.precedence}"
    } }.joinToString("\n")

    fun isAllowedKey(key: String): Boolean = ALLOWED_SPECS.containsKey(key)

    fun allowedKeys(): List<String> = ALLOWED_SPECS.keys.toList()

    /**
     * Extract the TUNE block (if any), strip it from the reply, apply the
     * whitelisted adjustments, return cleaned reply + change log.
     */
    // ── Phase thresholds matching FluidLearningAI ──────────────────────────────
    // V5.0.7814 — autonomous self-healing starts from the FIRST settled trade.
    // Trade 0 remains locked because there is no realized evidence yet.
    // Trade 1+ may apply one allowlisted adjustment with a tiny confidence-scaled
    // step; authority ramps with FreeRangeMode.adjustmentStrength().
    // Mature/Expert (3000+ trades): up to 3 adjustments, full bounded step.
    private const val TUNE_BOOTSTRAP_END = 1
    private const val TUNE_LEARNING_END  = 1     // V5.0.8319: 3 changes, full maxStep from trade 1 (was 3000)

    fun extractAndApply(ctx: Context?, llmReply: String): Applied {
        if (llmReply.isBlank()) {
            return Applied(cleanedReply = llmReply, changes = emptyList(), rejected = emptyList())
        }

        val match = BLOCK_REGEX.find(llmReply)
            ?: return Applied(cleanedReply = llmReply, changes = emptyList(), rejected = emptyList())

        val jsonPayload = match.groupValues.getOrNull(1).orEmpty().trim()
        val cleaned = llmReply.removeRange(match.range).trim()

        if (ctx == null || jsonPayload.isBlank()) {
            return Applied(cleanedReply = cleaned, changes = emptyList(), rejected = emptyList())
        }

        // ── Phase gate ─────────────────────────────────────────────────────────
        val totalTrades = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getTotalTradeCount()
        } catch (_: Throwable) { 0 }

        if (totalTrades < TUNE_BOOTSTRAP_END) {
            // Bootstrap phase — reject ALL tune blocks silently (strip from reply but apply nothing)
            ErrorLogger.info(TAG, "🔒 TUNE block rejected — no settled trade evidence yet ($totalTrades trades). Autonomous tuning starts at trade 1.")
            return Applied(cleanedReply = cleaned, changes = emptyList(), rejected = listOf("locked: requires first settled trade"))
        }

        // V5.9.408 — FreeRangeMode gates tuner strength. In free-range window
        // (≤3000 trades, or up to 5000 if unhealthy) the LLM gets a reduced
        // step cap that begins at 5% on trade 1 and ramps with evidence so early
        // self-healing is real but cannot make large blind parameter swings.
        val freeRangeScale = try { FreeRangeMode.adjustmentStrength() } catch (_: Throwable) { 1.0 }

        val phaseMaxAdj  = if (totalTrades < TUNE_LEARNING_END) 1 else MAX_ADJUSTMENTS_PER_CALL
        val phaseStepCap = ((if (totalTrades < TUNE_LEARNING_END) 0.5 else 1.0) * freeRangeScale)
            .coerceAtLeast(0.05)  // never zero out once past bootstrap floor

        val (changes, rejected) = parseAndApply(ctx, jsonPayload, phaseMaxAdj, phaseStepCap)
        return Applied(cleanedReply = cleaned, changes = changes, rejected = rejected)
    }

    private fun parseAndApply(
        ctx: Context,
        jsonPayload: String,
        maxAdjustments: Int = MAX_ADJUSTMENTS_PER_CALL,
        stepCapMultiplier: Double = 1.0
    ): Pair<List<Change>, List<String>> {
        val obj = try {
            JSONObject(jsonPayload)
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "bad JSON in TUNE block: ${t.message}")
            return emptyList<Change>() to listOf("malformed tune payload")
        }

        val arr: JSONArray = obj.optJSONArray("adjustments") ?: return emptyList<Change>() to emptyList()
        if (arr.length() == 0) return emptyList<Change>() to emptyList()

        val current = ConfigStore.load(ctx)
        var working = current

        val applied = mutableListOf<Change>()
        val rejected = mutableListOf<String>()
        // A single advisor/LLM reply must not stack several deltas on the
        // same exit, stop or polling parameter. Count ALL proposed keys,
        // including those beyond the per-call cap, before any write.
        val proposalCounts8373 = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("key")?.trim()?.takeIf { it.isNotBlank() }
        }.groupingBy { it }.eachCount()

        val limit = minOf(arr.length(), maxAdjustments)
        for (i in 0 until limit) {
            val entry = arr.optJSONObject(i) ?: continue
            val key = entry.optString("key").trim()
            val deltaRaw = entry.opt("delta")
            val reason = entry.optString("reason").trim().take(160)

            if (key.isBlank()) {
                rejected.add("missing key")
                continue
            }
            if ((proposalCounts8373[key] ?: 0) > 1) {
                rejected.add("$key: repeated/conflicting proposal; no change applied")
                continue
            }
            val spec = ALLOWED_SPECS[key]
            if (spec == null) {
                rejected.add("$key: not in allowlist")
                continue
            }

            val delta: Double = when (deltaRaw) {
                is Number -> deltaRaw.toDouble()
                is String -> {
                    val parsed = deltaRaw.toDoubleOrNull()
                    if (parsed == null) {
                        rejected.add("$key: non-numeric delta")
                        continue
                    }
                    parsed
                }
                else -> {
                    rejected.add("$key: missing delta")
                    continue
                }
            }

            if (!delta.isFinite() || delta == 0.0) {
                rejected.add("$key: zero/non-finite delta")
                continue
            }

            val contractError8386 = proposalError8386(key, delta, entry.optString("unit"), entry.optString("scope"), entry.optString("semantics"), entry.optString("effect"))
            if (contractError8386 != null) { rejected.add("$key: $contractError8386"); continue }
            val clampedStep = delta.coerceIn(-spec.maxStep * stepCapMultiplier, spec.maxStep * stepCapMultiplier)
            val oldValue = spec.get(working)
            val newValue = (oldValue + clampedStep).coerceIn(spec.min, spec.max)

            if (newValue == oldValue) {
                rejected.add("$key: no effective change (clamped)")
                continue
            }

            working = spec.set(working, newValue)
            val actual8386 = spec.get(working)
            if (actual8386 == oldValue) { rejected.add("$key: no effective change after type conversion"); continue }
            applied.add(Change(key = key, oldValue = oldValue, newValue = actual8386, reason = explain8386(key, oldValue, actual8386)))
        }
        for (i in limit until arr.length()) {
            val extraKey = arr.optJSONObject(i)?.optString("key")?.trim().orEmpty()
            rejected.add("${extraKey.ifBlank { "unnamed" }}: per-call adjustment limit $maxAdjustments reached")
        }

        if (applied.isEmpty()) {
            return applied to rejected
        }

        try {
            ConfigStore.save(ctx, working)
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "ConfigStore.save failed: ${t.message}")
            return emptyList<Change>() to (rejected + listOf("persist failed: ${t.message}"))
        }

        for (c in applied) {
            ErrorLogger.info(
                TAG,
                "🎛️ LLM tune: ${c.key} ${fmt(c.oldValue)} → ${fmt(c.newValue)} (${c.reason})"
            )
            try {
                SentientPersonality.onTuneApplied(c.key, c.oldValue, c.newValue, c.reason)
            } catch (_: Throwable) {
            }
        }

        return applied to rejected
    }

    private fun fmt(v: Double): String {
        return if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e12) {
            v.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        }
    }
}
