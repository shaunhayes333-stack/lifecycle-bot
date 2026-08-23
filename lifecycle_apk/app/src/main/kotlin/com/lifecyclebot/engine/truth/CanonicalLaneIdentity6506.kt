package com.lifecyclebot.engine.truth

/**
 * V5.0.6506 §P0-2 — CANONICAL LANE IDENTITY.
 *
 * Operator mandate: "Create one canonical lane identity function at
 * the boundary. Persist only canonical names. Legacy aliases migrate
 * on read: BLUE_CHIP → BLUECHIP."
 *
 * ExecutableOpenGate telemetry showed BLUE_CHIP / BLUECHIP identity
 * fragmentation surfacing as `EXEC_OPEN_DROPPED_CANON_LANE_UNRESOLVED`.
 * The lane-string enters the state machine from multiple upstream
 * producers (LayerTransitionManager enums, DecisionEngine strings,
 * legacy MainActivity constants). We normalise EVERY read/write via
 * `canonical(name)` so the executable-open snapshot / candidate
 * version / authority version can safely equality-compare.
 *
 * This is READ-ONLY normalization. Producer code that hard-codes
 * "BLUE_CHIP" stays as-is; consumers that snapshot the string call
 * `canonical(name)` before comparing / persisting.
 */
object CanonicalLaneIdentity6506 {

    /**
     * The set of aliases that map to canonical values. Keep this list
     * exhaustive — any new lane alias found by future audits must be
     * added here, NEVER hand-fixed at a call site.
     */
    private val aliases: Map<String, String> = mapOf(
        // BLUECHIP identity fragmentation
        "BLUE_CHIP" to "BLUECHIP",
        "BLUE-CHIP" to "BLUECHIP",
        "BLUE CHIP" to "BLUECHIP",
        // Historical MOONSHOT variants
        "MOON_SHOT" to "MOONSHOT",
        "MOON-SHOT" to "MOONSHOT",
        // Historical PROJECT_SNIPER variants
        "PROJECT-SNIPER" to "PROJECT_SNIPER",
        // Historical MICRO variants
        "MICRO_CAP" to "MICRO",
        "MICROCAP" to "MICRO",
    )

    /**
     * Fold every recognised alias to its canonical form.
     * Preserves case for unknown/new lane names (returned as UPPERCASE
     * to enforce the write-time convention).
     */
    fun canonical(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val upper = raw.trim().uppercase()
        return aliases[upper] ?: upper
    }

    /**
     * True when `a` and `b` are the same lane after alias normalization.
     * Use this instead of raw equality for lane comparisons.
     */
    fun sameLane(a: String?, b: String?): Boolean {
        val ca = canonical(a); val cb = canonical(b)
        return ca.isNotEmpty() && ca == cb
    }

    fun size(): Int = aliases.size
    fun aliasesForTest(): Map<String, String> = aliases
}
