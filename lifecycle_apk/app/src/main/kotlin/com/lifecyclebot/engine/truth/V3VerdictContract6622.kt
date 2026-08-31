package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6622 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §10 (operator
 * directive Feb 2026):
 *
 *   "For MemeTrader:
 *
 *      V3 fatal safety
 *        May hard-block when the condition is genuinely fatal, such as:
 *          - unsellable/honeypot
 *          - banned token
 *          - invalid mint
 *          - unequivocal catastrophic rug condition
 *          - genuine system safety halt
 *
 *      V3 soft opinion
 *        WATCH, ordinary REJECT, score weakness, timing disagreement,
 *        etc. are inputs to specialist reasoning/size/confidence.
 *        They must NOT globally prevent SHITCOIN, EXPRESS, MOONSHOT,
 *        PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, QUALITY, BLUECHIP,
 *        CYCLIC, CORE, TREASURY or CASHGEN from exercising their
 *        intended specialist mandate.
 *
 *      CORE may use V3 as its own decision engine. V3 must not
 *      commandeer every other specialist."
 *
 * This authority is the SINGLE oracle that answers "is this V3 verdict
 * genuinely fatal or merely a soft opinion?". Every MemeTrader
 * specialist that used to return early on V3 WATCH/REJECT should
 * consult isFatal6622 — if false, the verdict becomes an INPUT
 * (confidence penalty, size adjustment) rather than a HARD BLOCK.
 *
 * Slice 3 delivers the oracle + counters + a scoreAdjustment helper;
 * broad specialist re-wiring (removing the "V3 is the only boss"
 * early-returns) is the follow-up mechanical migration.
 */
object V3VerdictContract6622 {

    /**
     * Genuinely fatal conditions. Anything else is SOFT opinion.
     * String comparisons intentionally lenient — real V3 verdict
     * strings vary across scorers (unsellable / HONEYPOT / RUG_HIGH /
     * BANNED_MINT / SAFETY_HALT / INVALID_MINT / etc.).
     */
    private val FATAL_TOKENS: Set<String> = setOf(
        "UNSELLABLE", "HONEYPOT", "RUG", "RUG_HIGH", "CATASTROPHIC",
        "BANNED", "BANNED_MINT", "INVALID_MINT", "MINT_INVALID",
        "SAFETY_HALT", "SYSTEM_HALT", "HARD_HALT", "FATAL",
    )

    private val fatalCalls = AtomicLong(0L)
    private val softCalls = AtomicLong(0L)
    private val specialistOverrides = AtomicLong(0L)

    /**
     * Returns TRUE only when the verdict indicates a genuinely fatal
     * condition per operator §10. Everything else is SOFT — the
     * specialist may proceed with its own decision after weighting
     * the V3 signal as an input.
     */
    fun isFatal6622(verdict: String?, reason: String? = null): Boolean {
        val v = verdict?.uppercase()?.trim().orEmpty()
        val r = reason?.uppercase()?.trim().orEmpty()
        val fatal = FATAL_TOKENS.any { token -> v.contains(token) || r.contains(token) }
        if (fatal) {
            fatalCalls.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("V3_FATAL_HARD_BLOCK_6622")
                ForensicLogger.lifecycle(
                    "V3_FATAL_HARD_BLOCK_6622",
                    "verdict=$v reason=${r.take(60)} action=block_all_specialists",
                )
            } catch (_: Throwable) {}
        } else {
            softCalls.incrementAndGet()
            try { PipelineHealthCollector.labelInc("V3_SOFT_OPINION_SPECIALIST_DECIDES_6622") } catch (_: Throwable) {}
        }
        return fatal
    }

    /**
     * Convert a V3 soft-opinion verdict into a confidence penalty the
     * specialist can multiply into its own decision score. Never
     * returns 0 — that would silently degrade to a hard-block.
     * Range: 0.5 (WATCH), 0.75 (REJECT), 1.0 (any allow/probe).
     */
    fun softConfidenceMultiplier6622(verdict: String?): Double {
        val v = verdict?.uppercase()?.trim().orEmpty()
        return when {
            v.contains("WATCH") -> 0.5
            v.contains("REJECT") -> 0.75
            else -> 1.0
        }
    }

    /**
     * Called when a specialist accepts a soft-opinion verdict as input
     * (rather than treating it as a hard block). Fires
     * V3_SOFT_OPINION_SPECIALIST_OVERRIDE_6622 so operator can grep
     * for every lane that exercised its specialist mandate.
     */
    fun recordSpecialistOverride6622(specialistLane: String, verdict: String) {
        specialistOverrides.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("V3_SOFT_OPINION_SPECIALIST_OVERRIDE_6622")
            PipelineHealthCollector.labelInc(
                "V3_SOFT_OPINION_SPECIALIST_OVERRIDE_${specialistLane.uppercase()}_6622"
            )
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "fatal=${fatalCalls.get()} soft=${softCalls.get()} " +
            "specialistOverrides=${specialistOverrides.get()}"

    internal fun resetForTest() {
        fatalCalls.set(0L); softCalls.set(0L); specialistOverrides.set(0L)
    }
}

/**
 * V5.0.6622 §11 canonical MemeLane enum (operator directive Feb 2026):
 *
 *   "Create/use a canonical MemeLane enum. Legacy strings may be
 *    accepted only at persistence/parser boundaries and immediately
 *    normalized. Never create tickets, intents, positions or journal
 *    records using aliases."
 *
 * This enum plus the parser is the boundary. All internal code should
 * use the enum; only serialization / SharedPreferences / journal
 * legacy rows are allowed to still carry the string forms.
 */
enum class MemeLane6622(val canonical: String) {
    QUALITY("QUALITY"),
    BLUECHIP("BLUECHIP"),
    SHITCOIN("SHITCOIN"),
    CYCLIC("CYCLIC"),
    EXPRESS("EXPRESS"),
    CORE("CORE"),
    MOONSHOT("MOONSHOT"),
    PROJECT_SNIPER("PROJECT_SNIPER"),
    DIP_HUNTER("DIP_HUNTER"),
    MANIPULATED("MANIPULATED"),
    TREASURY("TREASURY"),
    CASHGEN("CASHGEN"),
    STANDARD("STANDARD"),
    V3_CORE("V3_CORE"),
    ;
    companion object {
        /**
         * Boundary parser — accepts any legacy alias and returns the
         * canonical enum. Falls back to STANDARD when the string is
         * blank or unknown; emits MEME_LANE_UNKNOWN_6622 for the
         * unknown case so operator can grep unrecognised inputs.
         */
        fun parse6622(raw: String?): MemeLane6622 {
            val u = raw?.uppercase()?.trim()?.replace('-', '_')?.replace(' ', '_').orEmpty()
            val direct = values().firstOrNull { it.canonical == u }
            if (direct != null) return direct
            return when (u) {
                "BLUE_CHIP"     -> BLUECHIP
                "SHIT_COIN"     -> SHITCOIN
                "SNIPE"         -> PROJECT_SNIPER
                "PROJECTSNIPER" -> PROJECT_SNIPER
                "DIPHUNTER"     -> DIP_HUNTER
                "CASH_GEN"      -> CASHGEN
                "" -> STANDARD
                else -> {
                    try { PipelineHealthCollector.labelInc("MEME_LANE_UNKNOWN_6622") } catch (_: Throwable) {}
                    STANDARD
                }
            }
        }
    }
}

/**
 * V5.0.6622 §13 post-hoc healing audit (operator directive Feb 2026):
 *
 *   "Search for patches/comments equivalent to:
 *      override tradingMode after buy
 *      restore immutable ticket
 *      restore from frozen snapshot
 *      lane upgraded after ticket
 *      using sealed size despite local mismatch
 *      canonical projection healed
 *    Do not keep repairing malformed objects downstream when the
 *    creator can construct them correctly. Repair at creation."
 *
 * This authority provides the receiver that each identified post-hoc
 * healing site calls before rewriting a downstream object. Every call
 * increments POST_HOC_HEALING_DETECTED_6622_<pattern> so the operator
 * can dump a ranked list of remaining healing sites and knows where
 * to attack the CREATOR side. Slice-3 hard-refuses via a bool return;
 * consumers may then choose to short-circuit their rewrite.
 */
object PostHocHealingAudit6622 {

    private val detections = AtomicLong(0L)
    private val perPattern = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    /**
     * Every post-hoc rewrite site calls this before mutating. Returns
     * true when the rewrite should PROCEED (backwards-compat during
     * Slice 3 rollout), while emitting the detection counter so the
     * operator can audit removal progress.
     */
    fun detect6622(patternName: String, note: String = ""): Boolean {
        detections.incrementAndGet()
        val bucket = patternName.uppercase().trim().ifBlank { "UNSPECIFIED" }
        perPattern.computeIfAbsent(bucket) { AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("POST_HOC_HEALING_DETECTED_6622")
            PipelineHealthCollector.labelInc("POST_HOC_HEALING_DETECTED_${bucket}_6622")
            ForensicLogger.lifecycle(
                "POST_HOC_HEALING_DETECTED_6622",
                "pattern=$bucket note=${note.take(60)} " +
                    "action=telemetry_only_slice3_rewrite_creator_side_slice4",
            )
        } catch (_: Throwable) {}
        return true
    }

    fun statusLine(): String {
        val top = perPattern.entries
            .sortedByDescending { it.value.get() }
            .take(6)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "detections=${detections.get()} top=[$top]"
    }

    internal fun resetForTest() {
        detections.set(0L); perPattern.clear()
    }
}
