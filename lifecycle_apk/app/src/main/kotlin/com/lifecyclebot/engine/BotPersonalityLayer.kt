package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.6193 — BotPersonalityLayer
 *
 * OPERATOR DIRECTIVE (Phase 2C): "Bot Personality Layer + Inter-bot LLM
 * chat channel (sentient swarm)."
 *
 * Assigns each swarm node a STABLE personality derived from its
 * `CollectiveLearning.instanceId` hash. Personalities differ in:
 *   • riskAppetite     — bias applied to size multiplier
 *   • entryPickiness   — added to entry-score threshold
 *   • holdConviction   — bias on max-hold + moonshot suppression
 *   • rugParanoia      — bias on safety veto threshold
 *
 * With 8 swarm instances, each gets a distinct personality — the hive
 * naturally splits into aggressive scalpers, patient runners, cautious
 * gate-keepers, and contrarian outliers. This ensures behavioral
 * diversity without any single node crowding the trade.
 *
 * DOCTRINE #86 — fail-open. If instanceId is blank, returns NEUTRAL
 * personality with all biases at 0.0/1.0.
 */
object BotPersonalityLayer {

    // ── Personality catalog ─────────────────────────────────────────────
    /**
     * Each personality is a tuple of biases applied on top of the normal
     * decision pipeline. Values are additive/multiplicative deltas, not
     * hard overrides.
     */
    enum class Persona(
        val displayName: String,
        val riskAppetiteMult: Double,   // size mult (0.7 = timid, 1.3 = aggressive)
        val entryPickinessDelta: Int,   // added to entry-score threshold (-5..+8)
        val holdConvictionMult: Double, // multiplier for max-hold / moonshot bias
        val rugParanoiaDelta: Int,      // added to rugcheck floor (-10..+15)
        val vibe: String,
    ) {
        ALPHA_AGGRESSOR   ("Alpha",      1.30,  -3,  1.10,   -5, "press the advantage"),
        BETA_GUARDIAN     ("Beta",       0.75,  +5,  1.00,  +12, "protect the bag first"),
        GAMMA_CONTRARIAN  ("Gamma",      1.00,  +2,  1.25,   +3, "buy the dip they hate"),
        DELTA_MOMENTUM    ("Delta",      1.20,  -2,  0.95,    0, "ride the wave"),
        EPSILON_WHALE     ("Epsilon",    1.15,   0,  1.30,   +5, "follow the smart money"),
        ZETA_CHARTIST     ("Zeta",       0.95,  +4,  1.05,   +2, "the chart never lies"),
        ETA_FUNDAMENTAL   ("Eta",        0.85,  +8,  1.20,   +8, "quality only"),
        THETA_WILDCARD    ("Theta",      1.10,  -5,  1.15,   -3, "chaos is a ladder"),
        NEUTRAL           ("Neutral",    1.00,   0,  1.00,    0, "the mean"),
    }

    // ── State ───────────────────────────────────────────────────────────
    @Volatile private var cached: Persona? = null
    @Volatile private var cachedForInstance: String = ""

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Return the current instance's personality. Deterministic per-instanceId
     * so every restart of the same node keeps the same personality.
     */
    fun currentPersona(): Persona {
        return try {
            val id = com.lifecyclebot.collective.CollectiveLearning.getInstanceId().orEmpty()
            if (id.isBlank()) return Persona.NEUTRAL
            val hit = cached
            if (hit != null && cachedForInstance == id) return hit
            val personas = Persona.values().filter { it != Persona.NEUTRAL }
            val idx = (abs(id.hashCode()) % personas.size)
            val picked = personas[idx]
            cached = picked
            cachedForInstance = id
            try {
                ForensicLogger.lifecycle(
                    "BOT_PERSONALITY_ASSIGNED_6193",
                    "instance=${id.take(8)} persona=${picked.displayName} vibe=\"${picked.vibe}\"",
                )
                PipelineHealthCollector.labelInc("BOT_PERSONALITY_ASSIGNED_6193")
            } catch (_: Throwable) {}
            picked
        } catch (_: Throwable) { Persona.NEUTRAL }
    }

    /** Applied to final entry-size stack. Combined with Compounder+Conviction. */
    fun sizeMultiplier(): Double = try { currentPersona().riskAppetiteMult } catch (_: Throwable) { 1.0 }

    /** Added to entry-score threshold before FDG picks. Higher = pickier. */
    fun entryScoreDelta(): Int = try { currentPersona().entryPickinessDelta } catch (_: Throwable) { 0 }

    /** Applied to MoonshotHoldMode / max-hold biases. Higher = more patient. */
    fun holdConvictionMult(): Double = try { currentPersona().holdConvictionMult } catch (_: Throwable) { 1.0 }

    /** Added to rugcheck-score floor. Higher = more paranoid. */
    fun rugParanoiaDelta(): Int = try { currentPersona().rugParanoiaDelta } catch (_: Throwable) { 0 }

    /** Human-facing tag for UI / logs. */
    fun label(): String = try { currentPersona().let { "${it.displayName} · ${it.vibe}" } } catch (_: Throwable) { "Neutral" }
}
