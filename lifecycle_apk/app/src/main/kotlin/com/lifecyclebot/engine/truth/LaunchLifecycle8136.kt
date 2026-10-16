package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 §ONE_LAUNCH_ONE_ORDERED_LIFE.
 *
 * LaunchPhaseAuthority7401 classifies each snapshot from scratch and tests IGNITION before PRE_IGNITION, and its
 * IGNITION test is a superset of PROJECT_SNIPER's own flow test (3+ buyers, 60%+ buy share, rising acceleration). So
 * the moment a launch showed real early buying — the sniper's whole thesis — it was labelled IGNITION and handed off
 * (PHASE_HANDOFF_IGNITION), and a launch could also step "backwards" between phases from one 750 ms read to the next.
 *
 * This is the ordered state machine
 *     BIRTH -> PRE_IGNITION -> IGNITION -> EXPANDING -> MIGRATION -> CONTINUATION -> DISTRIBUTION
 * fed with decision-time evidence only. Evidence (what was observed, with its timestamp and provenance) is kept apart
 * from routing ([sniperRoute8136] reads a state; it never re-derives one):
 *  - a launch never moves backwards; skipped stages carry no entry timestamp (they were not observed);
 *  - evidence stamped after the decision time, or older than the last evidence applied, is ignored;
 *  - PRE_IGNITION = real early buying (swap records) while the price has not yet moved 1.5x from the create price;
 *    IGNITION = that buying has moved the price (1.5x..1.8x), or a price impulse observed without swap records;
 *    EXPANDING = the impulse is consumed (>= 1.8x), the ignition is older than [IGNITION_HOLD_MS], or the launch is
 *    past the 3-minute launch window on its curve; MIGRATION / CONTINUATION after graduation; DISTRIBUTION on the
 *    same fade evidence the legacy authority calls POST_PUMP_FADE (dev dump, acute cascade, peak break, rollover).
 * The legacy [LaunchPhaseAuthority7401.Phase] is left unchanged for its many readers.
 */
object LaunchLifecycle8136 {
    enum class Stage8136 { BIRTH, PRE_IGNITION, IGNITION, EXPANDING, MIGRATION, CONTINUATION, DISTRIBUTION }

    /** What was observed about a launch at [atMs]. Flow fields are null when unknown (no swap evidence). */
    data class Evidence8136(
        val atMs: Long,
        val ageMs: Long?,
        val migrated: Boolean,
        val provenance: LaunchEvidence8136.Provenance8136,
        val buyTx60s: Int?,
        val buyers60s: Int?,
        val buySharePct: Double?,
        val accelerationRising: Boolean?,
        val devSold: Boolean,
        val createMultiple: Double?,
        val fade: Boolean,
    )

    /** The launch's ordered state: its stage, when each observed stage was entered, and the last evidence applied. */
    data class State8136(
        val stage: Stage8136,
        val enteredAtMs: Map<Stage8136, Long>,
        val lastEvidenceAtMs: Long,
        val reason: String,
        val provenance: LaunchEvidence8136.Provenance8136,
    ) {
        fun enteredAt8136(s: Stage8136): Long? = enteredAtMs[s]
    }

    /** PROJECT_SNIPER's phase verdict: assess, or an explicit alternate-lane handoff / refusal, never a silent drop. */
    data class SniperRoute8136(val eligible: Boolean, val code: String, val handoffLane: String?, val reason: String)

    internal const val EARLY_INTEREST_MAX_AGE_MS = 120_000L
    internal const val LAUNCH_WINDOW_MS = 180_000L
    internal const val IGNITION_HOLD_MS = 60_000L
    internal const val CONTINUATION_AFTER_MIGRATION_MS = 60_000L
    internal const val PRE_IGNITION_MAX_MULT = 1.5
    internal const val IMPULSE_CONSUMED_MULT = 1.8
    private const val MAX_MINTS = 4_000

    private val states = ConcurrentHashMap<String, State8136>()
    private val handoffs = ConcurrentHashMap<String, SniperRoute8136>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the stage the evidence alone supports (before the never-backwards rule), with its reason. */
    fun target8136(ev: Evidence8136, prior: State8136?): Pair<Stage8136, String> {
        val age = ev.ageMs ?: return Stage8136.BIRTH to "BIRTH_UNRESOLVED"
        val mult = ev.createMultiple?.takeIf { it.isFinite() && it > 0.0 }
        val swaps = ev.provenance == LaunchEvidence8136.Provenance8136.SWAP_RECORDS
        val buyTx = ev.buyTx60s ?: 0
        val share = ev.buySharePct ?: 0.0
        val earlyFlow = swaps && !ev.devSold && buyTx >= 2 && share >= 55.0
        val ignitionAt = prior?.enteredAt8136(Stage8136.IGNITION)
        return when {
            ev.fade -> Stage8136.DISTRIBUTION to "FADE"
            ev.migrated -> {
                val migratedAt = prior?.enteredAt8136(Stage8136.MIGRATION)
                if (migratedAt != null && ev.atMs - migratedAt >= CONTINUATION_AFTER_MIGRATION_MS) Stage8136.CONTINUATION to "POST_MIGRATION"
                else Stage8136.MIGRATION to "GRADUATED"
            }
            mult != null && mult >= IMPULSE_CONSUMED_MULT -> Stage8136.EXPANDING to "IMPULSE_CONSUMED"
            age > LAUNCH_WINDOW_MS -> Stage8136.EXPANDING to "PAST_LAUNCH_WINDOW"
            ignitionAt != null && ev.atMs - ignitionAt >= IGNITION_HOLD_MS -> Stage8136.EXPANDING to "IGNITION_AGED"
            mult != null && mult >= PRE_IGNITION_MAX_MULT ->
                Stage8136.IGNITION to (if (earlyFlow) "SWAP_FLOW_MOVED_PRICE" else "PRICE_IMPULSE_${ev.provenance.name}")
            earlyFlow && age <= EARLY_INTEREST_MAX_AGE_MS -> Stage8136.PRE_IGNITION to "SWAP_EARLY_BUYING"
            !swaps -> Stage8136.BIRTH to "FLOW_UNKNOWN_${ev.provenance.name}"
            else -> Stage8136.BIRTH to "NO_EARLY_INTEREST"
        }
    }

    /**
     * Pure: apply [ev] to [prior] at decision time [nowMs]. Future-stamped or out-of-order evidence leaves the state as
     * it was; otherwise the stage moves forward to the evidence's target (never backwards) and an entered stage is
     * stamped with the evidence time.
     */
    fun advance8136(prior: State8136?, ev: Evidence8136, nowMs: Long): State8136 {
        if (ev.atMs > nowMs) return prior ?: State8136(Stage8136.BIRTH, emptyMap(), 0L, "FUTURE_EVIDENCE_IGNORED", LaunchEvidence8136.Provenance8136.NONE)
        if (prior != null && ev.atMs < prior.lastEvidenceAtMs) return prior
        val (target, why) = target8136(ev, prior)
        val from = prior?.stage ?: Stage8136.BIRTH
        val next = if (target.ordinal > from.ordinal) target else from
        val entered = HashMap<Stage8136, Long>(prior?.enteredAtMs ?: emptyMap())
        if (prior == null) entered[Stage8136.BIRTH] = ev.atMs
        if (next != from || prior == null) entered.putIfAbsent(next, ev.atMs)
        val reason = if (next == target) why else "HELD_${next.name}_EVIDENCE_${why}"
        return State8136(next, entered, ev.atMs, reason, ev.provenance)
    }

    /**
     * Pure: PROJECT_SNIPER's window. PRE_IGNITION is assessed; IGNITION only when its setup is proven in the lane
     * ([provenSetup], V5.0.8118) AND the flow is swap-proven; every later stage is an explicit handoff
     * (MOONSHOT at/above [moonshotFloorUsd], SHITCOIN below it, EXPRESS for a fresh graduation) and DISTRIBUTION is a
     * terminal refusal. BIRTH is handed on with its reason (flow unknown vs no early interest).
     */
    fun sniperRoute8136(state: State8136, capUsd: Double, provenSetup: Boolean, moonshotFloorUsd: Double): SniperRoute8136 {
        val runner = if (capUsd.isFinite() && capUsd >= moonshotFloorUsd) "MOONSHOT" else "SHITCOIN"
        val swaps = state.provenance == LaunchEvidence8136.Provenance8136.SWAP_RECORDS
        return when (state.stage) {
            // Not yet assessable (flow unknown / no early buying): handed on by name, as the legacy EXPANDING default
            // did, so the runner lanes may read it now; a later PRE_IGNITION read routes it back here.
            Stage8136.BIRTH -> SniperRoute8136(false, "HANDOFF_BIRTH", runner, "PHASE_HANDOFF_8136_BIRTH_TO_${runner}_${state.reason}")
            Stage8136.PRE_IGNITION -> SniperRoute8136(true, "PRE_IGNITION", null, "SNIPER_PHASE_8136_PRE_IGNITION")
            Stage8136.IGNITION ->
                if (provenSetup && swaps) SniperRoute8136(true, "PROVEN_IGNITION", null, "SNIPER_PHASE_8136_PROVEN_IGNITION")
                else SniperRoute8136(false, "HANDOFF_IGNITION", runner,
                    "PHASE_HANDOFF_8136_IGNITION_TO_${runner}_${if (!swaps) "FLOW_UNKNOWN" else "SETUP_UNPROVEN"}")
            Stage8136.EXPANDING -> SniperRoute8136(false, "HANDOFF_EXPANDING", runner, "PHASE_HANDOFF_8136_EXPANDING_TO_$runner")
            Stage8136.MIGRATION -> SniperRoute8136(false, "HANDOFF_MIGRATION", "EXPRESS", "PHASE_HANDOFF_8136_MIGRATION_TO_EXPRESS")
            Stage8136.CONTINUATION -> SniperRoute8136(false, "HANDOFF_CONTINUATION", runner, "PHASE_HANDOFF_8136_CONTINUATION_TO_$runner")
            Stage8136.DISTRIBUTION -> SniperRoute8136(false, "TERMINAL_DISTRIBUTION", null, "SNIPER_TERMINAL_8136_DISTRIBUTION")
        }
    }

    /** LaunchPhaseAuthority7401.snapshot: apply this read's evidence to [mint]'s state and return it. */
    fun observe8136(mint: String, ev: Evidence8136, nowMs: Long): State8136 {
        if (mint.isBlank()) return advance8136(null, ev, nowMs)
        val next = states.compute(mint) { _, prior -> advance8136(prior, ev, nowMs) } ?: advance8136(null, ev, nowMs)
        if (states.size > MAX_MINTS) {
            val cutoff = nowMs - 30L * 60_000L
            try { states.entries.removeIf { it.value.lastEvidenceAtMs < cutoff } } catch (_: Throwable) {}
            if (states.size > MAX_MINTS) try { states.entries.sortedBy { it.value.lastEvidenceAtMs }.take(MAX_MINTS / 10).forEach { states.remove(it.key) } } catch (_: Throwable) {}
        }
        return next
    }

    /** ProjectSniperAI: record a refusal/handoff by name so the launch is never silently lost. */
    fun noteRoute8136(mint: String, route: SniperRoute8136) {
        counters.computeIfAbsent(route.code) { AtomicLong(0) }.incrementAndGet()
        if (mint.isBlank() || route.eligible) return
        handoffs[mint] = route
        if (handoffs.size > MAX_MINTS) handoffs.keys.take(MAX_MINTS / 10).forEach { handoffs.remove(it) }
        // V5.0.8144 — the emitted name is not the hand-off: the ledger records it as done only when a receiver has judged it.
        if (route.handoffLane != null) try { AdmissionHandoff8144.emitHandoff8144(mint, "PROJECT_SNIPER", route.handoffLane, route.reason) } catch (_: Throwable) {}
    }

    /** Pure (V5.0.8144): the runner lane a sniper hand-off names — the same rule as [sniperRoute8136] (MOONSHOT at/above its floor). */
    fun handoffLaneFor8144(capUsd: Double, moonshotFloorUsd: Double): String =
        if (capUsd.isFinite() && capUsd >= moonshotFloorUsd) "MOONSHOT" else "SHITCOIN"

    /** The last named sniper handoff/refusal for [mint], or null. */
    fun lastHandoff8136(mint: String): SniperRoute8136? = handoffs[mint]

    fun statusLine8136(): String =
        "launches=${states.size} routes=${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}"
}
