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
     *
     * V5.0.7115 §ONE_LANE_IDENTITY — the mandate above ("NEVER hand-fixed at a
     * call site") had been broken twice, and the two hand-copies had drifted
     * from this table AND from each other:
     *
     *   ExecutableOpenGate.canonicalLane   (private, same file as the consumer)
     *   Executor.canonicalExecutableLane   (a local fun nested in a fun body)
     *
     * Both carried SHIT_COIN / MANIP / DIP / SNIPER / PROJECT / CASH_GENERATION
     * folds that were missing here, and neither carried MOON_SHOT or MICRO_CAP,
     * which are here. Lane strings are compared for EQUALITY to decide whether a
     * sealed execution authority belongs to the requester, so two normalisers
     * that disagree is not cosmetic — it silently voids sealed authority.
     *
     * The union of the three tables now lives here and nowhere else.
     * ci/lane_identity_authority_scan.py fails the build if a fourth copy
     * appears.
     *
     * DELIBERATELY NOT AN ALIAS: CASHGEN -> TREASURY. ExecutableOpenGate's copy
     * carried that, and it is a lane MERGE, not an alias fold — CASHGEN and
     * TREASURY are two distinct executable specialists everywhere else in the
     * stack (MemeOwnershipInvariant6620's executable set, Executor's
     * executableLaneSet, UnifiedPolicyHead, UnifiedExitPolicyHead's cold-start
     * bias, ColdStartPriors, LiveStylePivotRouter, ToolkitSignalSheet), and
     * ExecutableOpenGate's own V5.0.6705 comment says so in as many words.
     * CASH_GENERATION therefore folds to CASHGEN, which is what Executor
     * already did and what the words mean.
     *
     * MemeExecutionIntent6621.canonicaliseLane6621 — a sibling in this very
     * package — states the rule outright:
     *
     *     "Only legacy string aliases are normalised here — never merge
     *      CORE/STANDARD/V3_CORE (operator §11 explicit rule) and never fold
     *      CASHGEN into TREASURY."
     *
     * so the fold ExecutableOpenGate carried was not an oversight of an unstated
     * rule. The rule was written down, in this package, and the second copy
     * broke it. Neither merge may enter this table.
     *
     * Separators are normalised BEFORE lookup, so a dash- or space-spelled
     * variant of any key below is covered without its own entry.
     */
    private val aliases: Map<String, String> = mapOf(
        // BLUECHIP identity fragmentation
        "BLUE_CHIP" to "BLUECHIP",
        // Historical MOONSHOT variants
        "MOON_SHOT" to "MOONSHOT",
        // Historical PROJECT_SNIPER variants
        "PROJECT" to "PROJECT_SNIPER",
        "SNIPER" to "PROJECT_SNIPER",
        // Historical MICRO variants
        "MICRO_CAP" to "MICRO",
        "MICROCAP" to "MICRO",
        // V5.0.7115 — absorbed from the hand-copies found by
        // ci/lane_identity_authority_scan.py. There were THIRTEEN of them.
        "SHIT_COIN" to "SHITCOIN",
        "MANIP" to "MANIPULATED",
        "DIP" to "DIP_HUNTER",
        "DIPHUNTER" to "DIP_HUNTER",
        "CASH_GENERATION" to "CASHGEN",
        "CASH_GEN" to "CASHGEN",
        "SNIPE" to "PROJECT_SNIPER",
        "PROJECTSNIPER" to "PROJECT_SNIPER",
        // PRESALE_SNIPE is a strategy-family name, not a lane.
        // MemeOwnershipInvariant6620's executable lane set names PROJECT_SNIPER
        // and does not contain PRESALE_SNIPE, and three independent lane-keyed
        // authorities (AdaptiveVetoConsensusAuthority6728, LaneCapitalFairness6732,
        // CausalFeedbackAuthority6715) already folded it this way.
        "PRESALE_SNIPE" to "PROJECT_SNIPER",
    )

    /**
     * Fold every recognised alias to its canonical form.
     * Preserves case for unknown/new lane names (returned as UPPERCASE
     * to enforce the write-time convention).
     *
     * V5.0.7115 — dashes and spaces fold to underscores before the alias
     * lookup, so "BLUE-CHIP", "BLUE CHIP" and "BLUE_CHIP" are one key. Both
     * hand-copies did this and this authority did not, which is why it needed
     * three separate BLUECHIP entries and still missed "MOON SHOT".
     */
    fun canonical(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val upper = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
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
