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
        "slippageBps" to ParamSpec(
            { it.slippageBps.toDouble() },
            { c, v -> c.copy(slippageBps = v.toInt()) },
            min = 50.0, max = 500.0, maxStep = 100.0
        ),
        "perPositionSizePct" to ParamSpec(
            { it.perPositionSizePct }, { c, v -> c.copy(perPositionSizePct = v) },
            min = 0.02, max = 0.25, maxStep = 0.03
        ),
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
        "minLiquidityUsd" to ParamSpec(
            { it.minLiquidityUsd }, { c, v -> c.copy(minLiquidityUsd = v) },
            min = 100.0, max = 10_000.0, maxStep = 2_000.0
        ),
        "walletReserveSol" to ParamSpec(
            { it.walletReserveSol }, { c, v -> c.copy(walletReserveSol = v) },
            min = 0.01, max = 2.0, maxStep = 0.25
        ),
        "scanIntervalSecs" to ParamSpec(
            { it.scanIntervalSecs.toDouble() },
            { c, v -> c.copy(scanIntervalSecs = v.toInt()) },
            min = 3.0, max = 60.0, maxStep = 10.0
        ),
        "minDiscoveryScore" to ParamSpec(
            { it.minDiscoveryScore }, { c, v -> c.copy(minDiscoveryScore = v) },
            min = 0.0, max = 40.0, maxStep = 8.0
        ),
    )

    fun isAllowedKey(key: String): Boolean = ALLOWED_SPECS.containsKey(key)

    fun allowedKeys(): List<String> = ALLOWED_SPECS.keys.toList()

    /**
     * Extract the TUNE block (if any), strip it from the reply, apply the
     * whitelisted adjustments, return cleaned reply + change log.
     */
    // ── Phase thresholds matching FluidLearningAI ──────────────────────────────
    // Bootstrap (0-999 trades): LLM TUNE is completely locked.
    //   The bot is still learning the market — self-adjustments this early cause
    //   premature gate tightening before any real data exists.
    // Learning (1000-2999 trades): 1 adjustment allowed, step cap halved.
    //   Slight nudges are OK but keep them gentle.
    // Mature/Expert (3000+ trades): Full autonomy (up to 3 adjustments, full step).
    private const val TUNE_BOOTSTRAP_END = 1000
    private const val TUNE_LEARNING_END  = 3000

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
            ErrorLogger.info(TAG, "🔒 TUNE block rejected — bootstrap phase ($totalTrades/$TUNE_BOOTSTRAP_END trades). LLM cannot self-adjust until learning phase.")
            return Applied(cleanedReply = cleaned, changes = emptyList(), rejected = listOf("locked: bootstrap phase ($totalTrades/$TUNE_BOOTSTRAP_END trades)"))
        }

        val phaseMaxAdj  = if (totalTrades < TUNE_LEARNING_END) 1 else MAX_ADJUSTMENTS_PER_CALL
        val phaseStepCap = if (totalTrades < TUNE_LEARNING_END) 0.5 else 1.0  // 0.5 = half-steps in learning phase

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

            val clampedStep = delta.coerceIn(-spec.maxStep * stepCapMultiplier, spec.maxStep * stepCapMultiplier)
            val oldValue = spec.get(working)
            val newValue = (oldValue + clampedStep).coerceIn(spec.min, spec.max)

            if (newValue == oldValue) {
                rejected.add("$key: no effective change (clamped)")
                continue
            }

            working = spec.set(working, newValue)
            applied.add(Change(key = key, oldValue = oldValue, newValue = newValue, reason = reason))
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
