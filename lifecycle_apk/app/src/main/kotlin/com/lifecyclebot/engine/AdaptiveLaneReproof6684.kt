package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LabStrategy
import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.LlmLabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6684 — single causal lane re-proof authority.
 *
 * A failed specialist lane is quarantined from economic opens while the Lab
 * keeps testing replacements. Re-entry requires a paper-PROMOTED strategy
 * registered to that exact lane. Promotion opens a fresh epoch so stale losses
 * from the superseded strategy cannot immediately poison the replacement.
 * If post-promotion outcomes fail again, the promoted strategy is archived and
 * a fresh generation is created/tested.
 */
object AdaptiveLaneReproof6684 {
    const val VERSION = "V5.0.6684_LANE_REPROOF"
    private const val PERSIST_KEY = "ADAPTIVE_LANE_REPROOF_6684"
    private const val SEED_COOLDOWN_MS = 20L * 60_000L

    val SPECIALIST_LANES = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "PRESALE_SNIPE", "DIP_HUNTER",
        "MANIPULATED", "TREASURY", "CASHGEN",
    )

    data class Target(
        val strategyId: String,
        val lane: String,
        val scoreBand: String,
        val createdAtMs: Long,
        var promotedAtMs: Long = 0L,
        val provenance: String = "LANE_REPROVE",
    )

    data class EntryDirective(
        val strategyId: String,
        val strategyName: String,
        val minScore: Int,
        val sizeMultiplier: Double,
        val allow: Boolean,
    )

    private val targets = ConcurrentHashMap<String, Target>()
    private val lastSeed = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    fun canonicalLane(raw: String): String {
        val u = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
        return when (u) {
            "BLUE_CHIP" -> "BLUECHIP"
            "SHIT_COIN" -> "SHITCOIN"
            "PROJECT_SNIPER", "SNIPER" -> "PRESALE_SNIPE"
            "MANIP" -> "MANIPULATED"
            "DIP" -> "DIP_HUNTER"
            else -> u
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val raw = LearningPersistence.load(PERSIST_KEY) ?: return
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    targets[id] = Target(
                        strategyId = id,
                        lane = canonicalLane(o.optString("lane")),
                        scoreBand = o.optString("band"),
                        createdAtMs = o.optLong("createdAt"),
                        promotedAtMs = o.optLong("promotedAt"),
                        provenance = o.optString("provenance", "LANE_REPROVE"),
                    )
                }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            targets.values.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.strategyId)
                        .put("lane", t.lane)
                        .put("band", t.scoreBand)
                        .put("createdAt", t.createdAtMs)
                        .put("promotedAt", t.promotedAtMs)
                        .put("provenance", t.provenance),
                )
            }
            LearningPersistence.save(PERSIST_KEY, arr.toString())
        } catch (_: Throwable) {}
    }

    fun isTargetedStrategy(strategyId: String): Boolean {
        ensureLoaded()
        return targets.containsKey(strategyId)
    }

    fun proofPasses(s: LabStrategy): Boolean =
        s.status == LabStrategyStatus.PROMOTED &&
            s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&
            s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&
            s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION

    private fun strategiesById(): Map<String, LabStrategy> =
        try { LlmLabStore.allStrategies().associateBy { it.id } } catch (_: Throwable) { emptyMap() }

    fun activeStrategy(laneRaw: String, scoreBand: String = ""): LabStrategy? {
        ensureLoaded()
        val lane = canonicalLane(laneRaw)
        val byId = strategiesById()
        return targets.values.asSequence()
            .filter { it.lane == lane && it.promotedAtMs > 0L }
            .filter { scoreBand.isBlank() || it.scoreBand.isBlank() || it.scoreBand.equals(scoreBand, true) }
            .mapNotNull { t -> byId[t.strategyId] }
            .filter(::proofPasses)
            .maxByOrNull { s -> s.paperPnlSol / s.paperTrades.coerceAtLeast(1) }
    }

    fun activationEpochMs(laneRaw: String): Long {
        ensureLoaded()
        val lane = canonicalLane(laneRaw)
        return targets.values
            .filter { it.lane == lane && it.promotedAtMs > 0L }
            .maxOfOrNull { it.promotedAtMs } ?: 0L
    }

    fun entryDirective(laneRaw: String, entryScore: Int): EntryDirective? {
        val s = activeStrategy(laneRaw) ?: return null
        val wr = (s.winRatePct() / 100.0).coerceIn(0.0, 1.0)
        val mult = (0.70 + wr * 0.70).coerceIn(0.75, 1.30)
        return EntryDirective(
            strategyId = s.id,
            strategyName = s.name,
            minScore = s.entryScoreMin,
            sizeMultiplier = mult,
            allow = entryScore < 0 || entryScore >= s.entryScoreMin,
        )
    }

    fun sizeMultiplierForLane(laneRaw: String): Double =
        entryDirective(laneRaw, -1)?.sizeMultiplier ?: 1.0

    fun exitStrategy(laneRaw: String): LabStrategy? = activeStrategy(laneRaw)

    fun requestReproof(laneRaw: String, reason: String, scoreBand: String = "") {
        ensureLoaded()
        val lane = canonicalLane(laneRaw)
        if (lane.isBlank()) return
        val now = System.currentTimeMillis()
        val key = "$lane|${scoreBand.uppercase()}"
        val existingActive = targets.values.any { t ->
            t.lane == lane &&
                (t.scoreBand.isBlank() || scoreBand.isBlank() || t.scoreBand.equals(scoreBand, true)) &&
                LlmLabStore.getStrategy(t.strategyId)?.status == LabStrategyStatus.ACTIVE
        }
        if (existingActive) return
        val last = lastSeed[key] ?: 0L
        if (now - last < SEED_COOLDOWN_MS) return
        lastSeed[key] = now

        val seed = deterministicSeed(lane, scoreBand, reason)
        LlmLabStore.addStrategy(seed)
        targets[seed.id] = Target(
            strategyId = seed.id,
            lane = lane,
            scoreBand = scoreBand.uppercase(),
            createdAtMs = now,
            provenance = "DETERMINISTIC_REPROVE",
        )
        persist()
        try {
            PipelineHealthCollector.labelInc("LAB_LANE_REPROVE_SEEDED_6684_$lane")
            ForensicLogger.lifecycle(
                "LAB_LANE_REPROVE_SEEDED_6684",
                "lane=$lane band=$scoreBand strategy=${seed.id} reason=${reason.take(120)}",
            )
        } catch (_: Throwable) {}

        inventReplacementAsync(lane, scoreBand, reason)
    }

    fun onLaneFailed(laneRaw: String, reason: String, scoreBand: String = "") {
        ensureLoaded()
        val lane = canonicalLane(laneRaw)
        val active = activeStrategy(lane, scoreBand)
        if (active != null) {
            try { LlmLabStore.archiveStrategy(active.id, "post_promotion_failure_6684:$reason") } catch (_: Throwable) {}
            targets[active.id]?.promotedAtMs = 0L
            persist()
            try { PipelineHealthCollector.labelInc("LAB_PROMOTED_STRATEGY_FAILED_6684_$lane") } catch (_: Throwable) {}
        }
        requestReproof(lane, reason, scoreBand)
    }

    fun tick() {
        ensureLoaded()
        try {
            LaneAutoPauseGuard.pausedLanes().forEach { lane ->
                requestReproof(lane, "lane_paused_awaiting_exact_lab_proof")
            }
        } catch (_: Throwable) {}

        val byId = strategiesById()
        var mutated = false
        targets.values.forEach { t ->
            var s = byId[t.strategyId] ?: return@forEach
            val rawProof6684 = s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&
                s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&
                s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION
            if (s.status == LabStrategyStatus.ACTIVE && rawProof6684) {
                s = s.copy(status = LabStrategyStatus.PROMOTED)
                try { LlmLabStore.updateStrategy(s) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("LAB_IMMEDIATE_PROMOTION_6684_${t.lane}") } catch (_: Throwable) {}
            }
            if (s.status == LabStrategyStatus.ARCHIVED && t.promotedAtMs != 0L) {
                t.promotedAtMs = 0L
                mutated = true
                return@forEach
            }
            if (t.promotedAtMs == 0L && proofPasses(s)) {
                t.promotedAtMs = System.currentTimeMillis()
                mutated = true
                try {
                    LaneAutoPauseGuard.manualResume(
                        t.lane,
                        "LAB_PROOF_6684 strategy=${s.id} n=${s.paperTrades} wr=${"%.1f".format(s.winRatePct())}% pnl=${"%+.4f".format(s.paperPnlSol)}",
                    )
                    PipelineHealthCollector.labelInc("LAB_EXACT_LANE_PROMOTED_6684_${t.lane}")
                    ForensicLogger.lifecycle(
                        "LAB_EXACT_LANE_PROMOTED_6684",
                        "lane=${t.lane} band=${t.scoreBand} strategy=${s.id} n=${s.paperTrades} wr=${"%.1f".format(s.winRatePct())}% pnl=${"%+.4f".format(s.paperPnlSol)}",
                    )
                } catch (_: Throwable) {}
            }
        }
        if (mutated) persist()
    }

    private fun deterministicSeed(lane: String, scoreBand: String, reason: String): LabStrategy {
        data class P(val score: Int, val tp: Double, val sl: Double, val hold: Int, val size: Double)
        val p = when (lane) {
            "QUALITY" -> P(68, 22.0, -6.0, 180, 0.08)
            "BLUECHIP" -> P(65, 18.0, -5.0, 240, 0.10)
            "SHITCOIN" -> P(58, 16.0, -5.0, 50, 0.05)
            "CYCLIC" -> P(58, 14.0, -5.0, 90, 0.06)
            "EXPRESS" -> P(62, 10.0, -4.0, 25, 0.05)
            "CORE" -> P(60, 18.0, -6.0, 120, 0.07)
            "MOONSHOT" -> P(70, 40.0, -8.0, 240, 0.06)
            "PRESALE_SNIPE" -> P(66, 25.0, -7.0, 70, 0.05)
            "DIP_HUNTER" -> P(60, 16.0, -6.0, 120, 0.06)
            "MANIPULATED" -> P(66, 12.0, -5.0, 45, 0.05)
            "TREASURY", "CASHGEN" -> P(58, 12.0, -4.0, 180, 0.08)
            else -> P(62, 18.0, -6.0, 120, 0.05)
        }
        return LabStrategy(
            id = LlmLabStore.newStrategyId(),
            name = "Reprove · ${lane.take(12)} ${scoreBand.take(6)}".trim(),
            rationale = "Exact lane re-proof for $lane/$scoreBand after ${reason.take(80)}",
            asset = LabAssetClass.MEME,
            entryScoreMin = p.score,
            entryRegime = "ANY",
            takeProfitPct = p.tp,
            stopLossPct = p.sl,
            maxHoldMins = p.hold,
            sizingSol = p.size,
            generation = 1,
            status = LabStrategyStatus.ACTIVE,
        )
    }

    private fun inventReplacementAsync(lane: String, scoreBand: String, reason: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (!GeminiCopilot.isConfigured() || GeminiCopilot.isAIDegraded()) return@launch
                val raw = GeminiCopilot.rawText(
                    userPrompt = """
Failed AATE specialist lane: $lane
Score band: ${scoreBand.ifBlank { "ALL" }}
Failure evidence: ${reason.take(500)}
Invent ONE materially different replacement strategy for this exact lane.
It will be paper-tested in the LLM Lab before any economic re-entry.
Return JSON only:
{"name":"<=32 chars","entryScoreMin":40-95,"entryRegime":"ANY|BULL|BEAR|CHOP","takeProfitPct":3-100,"stopLossPct":-2..-30,"maxHoldMins":15-480,"sizingSol":0.03-0.25,"rationale":"<=140 chars"}
                    """.trimIndent(),
                    systemPrompt = "You are AATE's quantitative strategy laboratory. Design testable replacements; never bypass safety. JSON only.",
                    temperature = 0.85,
                    maxTokens = 420,
                ) ?: return@launch
                val a = raw.indexOf('{')
                val b = raw.lastIndexOf('}')
                if (a < 0 || b <= a) return@launch
                val o = JSONObject(raw.substring(a, b + 1))
                val s = LabStrategy(
                    id = LlmLabStore.newStrategyId(),
                    name = o.optString("name", "LLM $lane").take(32),
                    rationale = "LLM exact-lane reproof $lane/$scoreBand: " + o.optString("rationale", reason).take(120),
                    asset = LabAssetClass.MEME,
                    entryScoreMin = o.optInt("entryScoreMin", 65).coerceIn(40, 95),
                    entryRegime = o.optString("entryRegime", "ANY").uppercase().let { if (it in setOf("ANY", "BULL", "BEAR", "CHOP")) it else "ANY" },
                    takeProfitPct = o.optDouble("takeProfitPct", 18.0).coerceIn(3.0, 100.0),
                    stopLossPct = o.optDouble("stopLossPct", -6.0).coerceIn(-30.0, -2.0),
                    maxHoldMins = o.optInt("maxHoldMins", 120).coerceIn(15, 480),
                    sizingSol = o.optDouble("sizingSol", 0.06).coerceIn(0.03, 0.25),
                    generation = 1,
                    status = LabStrategyStatus.ACTIVE,
                )
                LlmLabStore.addStrategy(s)
                targets[s.id] = Target(
                    strategyId = s.id,
                    lane = lane,
                    scoreBand = scoreBand.uppercase(),
                    createdAtMs = System.currentTimeMillis(),
                    provenance = "LLM_REPROVE",
                )
                persist()
                try { PipelineHealthCollector.labelInc("LAB_LLM_REPLACEMENT_INVENTED_6684_$lane") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        ensureLoaded()
        val active = targets.values.count { it.promotedAtMs > 0L }
        val testing = targets.values.count { LlmLabStore.getStrategy(it.strategyId)?.status == LabStrategyStatus.ACTIVE }
        return "$VERSION targeted=${targets.size} testing=$testing active=$active paused=${try { LaneAutoPauseGuard.pausedLanes().size } catch (_: Throwable) { -1 }}"
    }
}
