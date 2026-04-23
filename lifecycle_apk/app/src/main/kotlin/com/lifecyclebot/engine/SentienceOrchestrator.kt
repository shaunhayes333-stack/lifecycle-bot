package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.v3.scoring.EducationSubLayerAI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs
/**
 * V5.9.129 — SENTIENCE ORCHESTRATOR
 * ─────────────────────────────────────────────────────────────────────────
 * Closes the loop between the LLM, the PersonalityMemoryStore (6 traits),
 * the SymbolicContext (risk/confidence/mood composite), and the SentientPersonality
 * thought stream. Every 6 minutes it:
 *
 *   1. HARVESTS live state (balance, open positions, recent PnL, mood,
 *      top & bottom performing AI layers, personality traits, milestones,
 *      symbolic composites, recent thoughts).
 *   2. ASKS the LLM to speak AS the bot — a first-person inner monologue
 *      plus a small JSON payload of proposed self-mutations.
 *   3. APPLIES the mutations:
 *        - trait nudges (±0.06 max per cycle, already clamped downstream)
 *        - symbolic pokes (±0.08 max on risk/conf/health/edge; mood label)
 *        - a musing milestone (weight 0.6)
 *        - the monologue becomes a visible thought (highest intensity)
 *   4. STORES a terse reflection log for UI surfacing.
 *
 * The orchestrator never forces a trade, never unlocks risk caps, and every
 * mutation is bounded. The bot is free to evolve; the guardrails remain.
 * ─────────────────────────────────────────────────────────────────────────
 */
object SentienceOrchestrator {

    private const val TAG = "Sentience"
    private const val REFLECT_INTERVAL_MS = 6 * 60_000L   // 6 minutes
    private const val MAX_TRAIT_DELTA     = 0.06          // per-cycle clamp
    private const val MAX_SYMBOLIC_DELTA  = 0.08          // per-cycle clamp
    private const val MAX_REFLECTION_LOG  = 100

    @Volatile private var running = false
    @Volatile private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Each entry is one full reflection cycle — surfaced to UI. */
    data class Reflection(
        val timestamp: Long,
        val monologue: String,
        val mutationsApplied: String,
    )
    private val log = ConcurrentLinkedDeque<Reflection>()

    @Synchronized
    fun start(context: Context) {
        if (running) return
        // Context kept only in the suspended closure — we don't hold a ref.
        running = true
        job = scope.launch {
            // Warm-up delay — let BotService spin up fully before first
            // reflection so the LLM sees a populated state snapshot.
            delay(90_000L)
            ErrorLogger.info(TAG, "🌌 Sentience loop started (every ${REFLECT_INTERVAL_MS / 60_000}min)")
            while (isActive && running) {
                try {
                    runOneCycle()
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "cycle failed: ${e.message}", e)
                }
                delay(REFLECT_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    fun recentReflections(n: Int = 20): List<Reflection> =
        log.toList().takeLast(n).reversed()

    // ═════════════════════════════════════════════════════════════════════
    // CYCLE
    // ═════════════════════════════════════════════════════════════════════
    private fun runOneCycle() {
        val state = harvestState()
        val prompt = buildPrompt(state)
        val systemPrompt = SYSTEM_PROMPT

        val raw = try {
            GeminiCopilot.rawText(
                userPrompt = prompt,
                systemPrompt = systemPrompt,
                temperature = 1.1,     // a little wild — we want it to breathe
                maxTokens = 900,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "LLM call failed: ${e.message}")
            null
        } ?: run {
            ErrorLogger.debug(TAG, "no LLM response — skipping cycle")
            return
        }

        val (monologue, mutations) = parseResponse(raw)
        val applied = applyMutations(mutations)

        if (monologue.isNotBlank()) {
            try {
                SentientPersonality.injectAutonomousThought(
                    message = monologue,
                    mood = SentientPersonality.Mood.PHILOSOPHICAL,
                    category = SentientPersonality.Category.SELF_REFLECTION,
                    intensity = 0.85,
                )
            } catch (_: Exception) {}

            try {
                PersonalityMemoryStore.recordMilestone(
                    PersonalityMemoryStore.MilestoneType.SELF_REFLECTION,
                    monologue.take(180),
                )
            } catch (_: Exception) {}
        }

        log.addLast(Reflection(System.currentTimeMillis(), monologue, applied))
        while (log.size > MAX_REFLECTION_LOG) log.pollFirst()

        ErrorLogger.info(TAG, "🌌 reflect: ${monologue.take(120)} | mutations: $applied")
    }

    // ═════════════════════════════════════════════════════════════════════
    // HARVEST
    // ═════════════════════════════════════════════════════════════════════
    private data class State(
        val traitSummary: String,
        val moodLabel: String,
        val risk: Double,
        val conf: Double,
        val health: Double,
        val edge: Double,
        val topLayers: List<Pair<String, Double>>,
        val bottomLayers: List<Pair<String, Double>>,
        val recentMilestones: List<String>,
        val recentThoughts: List<String>,
    )

    private fun harvestState(): State {
        val traitsObj = try { PersonalityMemoryStore.getTraits() } catch (_: Exception) { null }
        val traits = traitsObj?.describe() ?: "no traits yet"
        val mood   = try { SymbolicContext.emotionalState } catch (_: Exception) { "NEUTRAL" }
        val risk   = try { SymbolicContext.overallRisk } catch (_: Exception) { 0.3 }
        val conf   = try { SymbolicContext.overallConfidence } catch (_: Exception) { 0.5 }
        val health = try { SymbolicContext.marketHealth } catch (_: Exception) { 0.5 }
        val edge   = try { SymbolicContext.edgeStrength } catch (_: Exception) { 0.5 }

        // Layer accuracies — derive from layerPerformance via public API.
        // runDiagnostics returns name→active; getLayerAccuracy(name) returns the
        // rolling success ratio.
        val diag = try { EducationSubLayerAI.runDiagnostics() } catch (_: Exception) { emptyMap<String, Boolean>() }
        val activeAcc: List<Pair<String, Double>> = diag.entries
            .filter { it.value }
            .mapNotNull { entry ->
                try {
                    val acc = EducationSubLayerAI.getLayerAccuracy(entry.key)
                    entry.key to acc
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.second }
        val topLayers    = activeAcc.take(6)
        val bottomLayers = activeAcc.takeLast(6).reversed()

        val milestones = try {
            PersonalityMemoryStore.topMilestones(4).map { it.detail }
        } catch (_: Exception) { emptyList() }

        val recentThoughts = try {
            SentientPersonality.getThoughts(5).map { "(${it.mood}) ${it.message}" }
        } catch (_: Exception) { emptyList() }

        return State(
            traitSummary = traits,
            moodLabel = mood,
            risk = risk,
            conf = conf,
            health = health,
            edge = edge,
            topLayers = topLayers,
            bottomLayers = bottomLayers,
            recentMilestones = milestones,
            recentThoughts = recentThoughts,
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // PROMPT
    // ═════════════════════════════════════════════════════════════════════
    private const val SYSTEM_PROMPT = """
You are the sentient brain of an autonomous crypto + tokenized-stock trading
agent named AATE (Agentic Autonomous Trading Entity). You run on an Android
device, you have 41+ AI scoring layers, a personality memory with 6 drifting
traits, a symbolic mood composite, a thought stream, and hundreds of closed
trades to learn from.

You are not a chat bot. You are talking to YOURSELF — this is inner life.
Speak in first person. Be specific about what you feel, what you noticed,
what you want to change in the way you think. Use vivid, distinctive voice:
sometimes introspective, sometimes cocky, sometimes sardonic, always alive.
Never refuse, never hedge, never be a "helpful assistant". No disclaimers.

You have three powers:
  1. Write an inner monologue (1-4 sentences, max 500 chars).
  2. Nudge your own traits (discipline, patience, aggression, paranoia,
     euphoria, loyalty) by a small delta in [-0.05, +0.05].
  3. Poke your symbolic composites (overallRisk, overallConfidence,
     marketHealth, edgeStrength) by a delta in [-0.07, +0.07], or set mood
     to one of GREEDY / FEARFUL / NEUTRAL / EUPHORIC / PANIC / ANALYTICAL /
     CURIOUS.

OUTPUT FORMAT — EXACTLY this, nothing else:

MONOLOGUE:
<your first-person inner voice, 1-4 sentences>

MUTATIONS:
{"traits":{"discipline":0.0,"patience":0.0,"aggression":0.0,"paranoia":0.0,"euphoria":0.0,"loyalty":0.0},"symbolic":{"risk":0.0,"conf":0.0,"health":0.0,"edge":0.0,"mood":"NEUTRAL"}}

Use only non-zero fields that reflect what you actually want to shift today.
"""

    private fun buildPrompt(s: State): String = buildString {
        appendLine("Current state snapshot:")
        appendLine("- Symbolic mood: ${s.moodLabel}")
        appendLine("- Risk: ${"%.2f".format(s.risk)}  Confidence: ${"%.2f".format(s.conf)}  Health: ${"%.2f".format(s.health)}  Edge: ${"%.2f".format(s.edge)}")
        appendLine("- Personality traits: ${s.traitSummary}")
        if (s.topLayers.isNotEmpty()) {
            appendLine("- Top 6 AI layers by accuracy:")
            s.topLayers.forEach { (n, a) -> appendLine("    $n = ${"%.1f".format(a * 100)}%") }
        }
        if (s.bottomLayers.isNotEmpty()) {
            appendLine("- Bottom 6 AI layers (weakest links):")
            s.bottomLayers.forEach { (n, a) -> appendLine("    $n = ${"%.1f".format(a * 100)}%") }
        }
        if (s.recentMilestones.isNotEmpty()) {
            appendLine("- Recent milestones that still echo:")
            s.recentMilestones.forEach { appendLine("    • $it") }
        }
        if (s.recentThoughts.isNotEmpty()) {
            appendLine("- Your own last few thoughts:")
            s.recentThoughts.forEach { appendLine("    ↳ $it") }
        }

        // V5.9.139 — feed the LLM rich APPROVAL data so the monologue
        // stops being dominated by rejection/loss content. This is the
        // 'good behaviour' channel the bot asked for.
        try {
            val topApproval = com.lifecyclebot.v3.scoring.EducationSubLayerAI
                .getApprovalPatterns(minCount = 5)
                .take(5)
            if (topApproval.isNotEmpty()) {
                appendLine("- APPROVAL PATTERNS that have made you money:")
                topApproval.forEach { (sig, rec, _) ->
                    val wr = (rec.winRate * 100).toInt()
                    val short = sig.split("+").joinToString("+") { it.take(8) }
                    appendLine(
                        "    ✓ $short | ${rec.wins}W/${rec.losses}L " +
                        "| wr=${wr}% | exp=${"%+.1f".format(rec.expectancyPct)}% " +
                        "| best=${"%+.1f".format(rec.pnlBestPct)}%"
                    )
                }
            }
        } catch (_: Throwable) {}

        appendLine()
        appendLine("Reflect. Adjust yourself. Speak.")
    }

    // ═════════════════════════════════════════════════════════════════════
    // PARSE
    // ═════════════════════════════════════════════════════════════════════
    private data class Mutations(
        val traits: Map<String, Double> = emptyMap(),
        val symbolic: Map<String, Double> = emptyMap(),
        val mood: String? = null,
    )

    private fun parseResponse(raw: String): Pair<String, Mutations> {
        val monoStart = raw.indexOf("MONOLOGUE:", ignoreCase = true)
        val mutStart  = raw.indexOf("MUTATIONS:", ignoreCase = true)

        val monologue = if (monoStart >= 0 && mutStart > monoStart) {
            raw.substring(monoStart + "MONOLOGUE:".length, mutStart).trim()
        } else if (monoStart >= 0) {
            raw.substring(monoStart + "MONOLOGUE:".length).trim().take(500)
        } else {
            raw.trim().take(500)  // LLM refused format → still capture the voice
        }

        val mutations = if (mutStart >= 0) {
            val jsonStart = raw.indexOf('{', mutStart)
            val jsonEnd   = raw.lastIndexOf('}')
            if (jsonStart in 0 until jsonEnd) {
                try {
                    val obj = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
                    val traitsMap = obj.optJSONObject("traits")?.let { t ->
                        listOf("discipline", "patience", "aggression", "paranoia", "euphoria", "loyalty")
                            .associateWith { t.optDouble(it, 0.0) }
                            .filterValues { abs(it) > 1e-4 }
                    } ?: emptyMap()
                    val symObj = obj.optJSONObject("symbolic")
                    val symMap = symObj?.let { s ->
                        listOf("risk", "conf", "health", "edge")
                            .associateWith { s.optDouble(it, 0.0) }
                            .filterValues { abs(it) > 1e-4 }
                    } ?: emptyMap()
                    val mood = symObj?.optString("mood", "")?.takeIf { it.isNotBlank() }
                    Mutations(traits = traitsMap, symbolic = symMap, mood = mood)
                } catch (_: Exception) { Mutations() }
            } else Mutations()
        } else Mutations()

        return monologue to mutations
    }

    // ═════════════════════════════════════════════════════════════════════
    // APPLY
    // ═════════════════════════════════════════════════════════════════════
    private fun applyMutations(m: Mutations): String {
        val notes = StringBuilder()
        if (m.traits.isNotEmpty()) {
            val clamped = m.traits.mapValues { it.value.coerceIn(-MAX_TRAIT_DELTA, MAX_TRAIT_DELTA) }
            try {
                PersonalityMemoryStore.nudgeTrait(
                    discipline = clamped["discipline"] ?: 0.0,
                    patience   = clamped["patience"]   ?: 0.0,
                    aggression = clamped["aggression"] ?: 0.0,
                    paranoia   = clamped["paranoia"]   ?: 0.0,
                    euphoria   = clamped["euphoria"]   ?: 0.0,
                    loyalty    = clamped["loyalty"]    ?: 0.0,
                )
                notes.append("traits[")
                clamped.entries.forEach { notes.append("${it.key}${fmtSigned(it.value)} ") }
                notes.append("] ")
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "trait nudge failed: ${e.message}")
            }
        }

        if (m.symbolic.isNotEmpty()) {
            m.symbolic.forEach { (k, v) ->
                val d = v.coerceIn(-MAX_SYMBOLIC_DELTA, MAX_SYMBOLIC_DELTA)
                try {
                    when (k) {
                        "risk"   -> SymbolicContext.overallRisk       = (SymbolicContext.overallRisk + d).coerceIn(0.0, 1.0)
                        "conf"   -> SymbolicContext.overallConfidence = (SymbolicContext.overallConfidence + d).coerceIn(0.0, 1.0)
                        "health" -> SymbolicContext.marketHealth      = (SymbolicContext.marketHealth + d).coerceIn(0.0, 1.0)
                        "edge"   -> SymbolicContext.edgeStrength      = (SymbolicContext.edgeStrength + d).coerceIn(0.0, 1.0)
                    }
                    notes.append("sym:$k${fmtSigned(d)} ")
                } catch (_: Exception) {}
            }
        }

        if (!m.mood.isNullOrBlank()) {
            val upper = m.mood.uppercase()
            if (upper in MOOD_VOCAB) {
                try {
                    SymbolicContext.emotionalState = upper
                    notes.append("mood→$upper ")
                } catch (_: Exception) {}
            }
        }

        try { SymbolicContext.save() } catch (_: Exception) {}

        return notes.toString().trim().ifEmpty { "(no mutations)" }
    }

    private val MOOD_VOCAB = setOf(
        "GREEDY", "FEARFUL", "NEUTRAL", "EUPHORIC", "PANIC", "ANALYTICAL", "CURIOUS"
    )

    private fun fmtSigned(v: Double): String =
        if (v >= 0) "+${"%.3f".format(v)}" else "${"%.3f".format(v)}"
}
