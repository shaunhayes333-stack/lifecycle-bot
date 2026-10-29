package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8297 §EVERY_MISSED_RUNNER_HAS_ONE_LOSING_STAGE.
 *
 * Owner, 5.0.8282/8286: "no winners, no runners" — 39 runners, 36 missed. RunnerCapture8017 printed `held` for any
 * runner the bot ever had a position in, so a coin bought at the top and stopped out, or bought at the start and sold
 * at +20% before a +2,000% run, counted as caught; and a missed runner printed only its last refusal label. Each runner
 * is now attributed to the FIRST funnel stage where the run was lost:
 *   NOT_EVALUATED        seen (forward-labelled) but never judged by a lane / the admission / the FDG
 *   DATA_DEFER           the binding verdict was a data deferral (inputs unobserved, budget spent, fetch never completed)
 *   SAFETY               a hard-safety refusal (correct at decision time)
 *   GATE_REFUSED         a non-data gate refused (named: the binding gate's stem)
 *   ADMITTED_NOT_BOUGHT  every gate admitted and the sizer / buy door stopped it (named: the downstream stop)
 *   BOUGHT_AFTER_PEAK    bought, but the run's peak was before the entry (a pump bought at its top)
 *   SOLD_BEFORE_PEAK     bought and closed before the run's peak (an exit capped it)
 *   SOLD_EARLY           closed with less than [SOLD_EARLY_SHARE_8297] of the run available from its own entry banked
 *   HOLDING / CAPTURED   still held / banked a real share of the run
 * Nothing here decides a trade: it reads what RunnerCapture8017 and AdmissionRunnerTrace8144 already recorded.
 *
 * It also keeps the runner-admit outcome ledger (false positives): every close of a position the runner paths admitted
 * (grab / proven-cell admit / confirmed-run ticket) is graded WIN / WIN_CAPPED / LOSS_BOUGHT_TOP / LOSS_GAVE_BACK on its
 * own realised return and excursion, and every live close that saw +100% but banked under a quarter of it is counted
 * WIN_CAPPED (an early take-profit capping a run).
 */
object RunnerFunnel8297 {

    enum class Stage8297 {
        NOT_EVALUATED, DATA_DEFER, SAFETY, GATE_REFUSED, ADMITTED_NOT_BOUGHT,
        BOUGHT_AFTER_PEAK, SOLD_BEFORE_PEAK, SOLD_EARLY, HOLDING, CAPTURED,
    }

    data class Attribution8297(val stage: Stage8297, val gate: String = "")

    /** A closed runner position that banked less than this share of the run available from its own entry sold early. */
    private const val SOLD_EARLY_SHARE_8297 = 0.25

    private val SAFETY_8297 = listOf("RUG", "FREEZE", "HONEYPOT", "MINT_AUTH", "HARD_BLOCK", "SAFETY", "TOXIC", "BLACKLIST",
        "SELLAB", "SCAM", "HOSTILE")
    private val DATA_8297 = listOf("DATA_INCOMPLETE", "DATA_DEFER", "TOO_FEW_BARS", "HYDRAT", "UNOBSERVED", "NOT_FETCHED")

    /** Pure: the rule name of a gate label (before its first ':' / ' ', at most 48 chars). */
    private fun stem8297(gate: String): String = gate.trim().substringBefore(':').substringBefore(' ').take(48)

    /** Pure: is [gate] a data deferral (the coin was waiting for inputs, not judged)? */
    private fun dataGate8297(gate: String): Boolean {
        val g = gate.trim().uppercase()
        return g.isNotEmpty() && (g.startsWith("DEFER") || DATA_8297.any { g.contains(it) })
    }

    /**
     * Pure: the first stage where a runner was lost. [binding] is the decision's binding refusal (the last gate that
     * said no: AdmissionRunnerTrace8144's binding, else the labeler's refusal, else the FDG / admission refusal);
     * [evaluated] = any lane, admission or FDG read exists; [downstream] = what stopped an admitted buy.
     * Held: [bankedPct] NaN = still open; [peakAtMs] / [closeAtMs] / [holdMs] place the run's peak against the hold.
     */
    fun attribute8297(
        held: Boolean, bankedPct: Double, peakPct: Double, entryVsStartPct: Double,
        peakAtMs: Long, closeAtMs: Long, holdMs: Long,
        evaluated: Boolean, binding: String, downstream: String,
    ): Attribution8297 {
        if (held) {
            if (!bankedPct.isFinite()) return Attribution8297(Stage8297.HOLDING)
            if (peakAtMs > 0L && closeAtMs > 0L && holdMs > 0L) {
                if (peakAtMs < closeAtMs - holdMs) return Attribution8297(Stage8297.BOUGHT_AFTER_PEAK)
                if (peakAtMs > closeAtMs) return Attribution8297(Stage8297.SOLD_BEFORE_PEAK)
            }
            val share = RunnerCapture8017.bankedShare8017(bankedPct, peakPct, entryVsStartPct)
            return if (share.isFinite() && share < SOLD_EARLY_SHARE_8297) Attribution8297(Stage8297.SOLD_EARLY)
            else Attribution8297(Stage8297.CAPTURED)
        }
        val all = "$binding $downstream".uppercase()
        SAFETY_8297.firstOrNull { all.contains(it) }?.let { return Attribution8297(Stage8297.SAFETY, stem8297(binding.ifBlank { downstream })) }
        if (!evaluated && binding.isBlank() && downstream.isBlank()) return Attribution8297(Stage8297.NOT_EVALUATED)
        // V5.0.8435 — the FURTHEST stage wins. 8434: Shillcoin +855% was headlined GATE:NATIVE_TREASURY_ROLE_FLOOR while
        // SHITCOIN's own hand-off reached FDG_ALLOW and the buy aborted downstream (LIVE_BUY_ABORTED): one lane's refusal
        // hid another lane's admitted, unbought entry. A downstream stop of an admitted buy is the runner's losing stage.
        if (downstream.isNotBlank()) return Attribution8297(Stage8297.ADMITTED_NOT_BOUGHT, stem8297(downstream))
        if (dataGate8297(binding)) return Attribution8297(Stage8297.DATA_DEFER, stem8297(binding))
        if (binding.isBlank()) return Attribution8297(Stage8297.ADMITTED_NOT_BOUGHT, stem8297(downstream.ifBlank { "NO_BUY_ATTEMPT" }))
        return Attribution8297(Stage8297.GATE_REFUSED, stem8297(binding))
    }

    /** Pure: one summary over attributions — per-stage counts and the top named gates of the missed ones. */
    fun summary8297(list: List<Attribution8297>): String {
        if (list.isEmpty()) return "funnel8297{runners=0}"
        val by = Stage8297.values().filter { s -> list.any { it.stage == s } }
            .joinToString(" ") { s -> "${s.name.lowercase()}=${list.count { it.stage == s }}" }
        val gates = list.filter { it.gate.isNotBlank() }.groupingBy { "${it.stage.name.take(4)}:${it.gate}" }.eachCount()
            .entries.sortedByDescending { it.value }.take(6).joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        return "funnel8297{runners=${list.size} $by top=[$gates]}"
    }

    // ── runner-admit outcomes (false positives) and capped wins ──

    enum class Outcome8297 { WIN, WIN_CAPPED, LOSS_BOUGHT_TOP, LOSS_GAVE_BACK, UNKNOWN }

    /** A close whose excursion reached this and banked under [SOLD_EARLY_SHARE_8297] of it was capped. */
    private const val CAPPED_MFE_PCT_8297 = 100.0
    /** A loss whose best excursion stayed under this was bought at (or after) the top. */
    private const val TOP_MFE_PCT_8297 = 5.0

    /** Pure: a close graded on its realised return and its maximum favourable excursion (NaN = unknown). */
    fun outcome8297(realizedPct: Double, mfePct: Double): Outcome8297 = when {
        !realizedPct.isFinite() -> Outcome8297.UNKNOWN
        realizedPct >= 0.0 && mfePct.isFinite() && mfePct >= CAPPED_MFE_PCT_8297 && realizedPct < mfePct * SOLD_EARLY_SHARE_8297 -> Outcome8297.WIN_CAPPED
        realizedPct >= 0.0 -> Outcome8297.WIN
        mfePct.isFinite() && mfePct < TOP_MFE_PCT_8297 -> Outcome8297.LOSS_BOUGHT_TOP
        else -> Outcome8297.LOSS_GAVE_BACK
    }

    private val admitOutcomes = ConcurrentHashMap<String, AtomicLong>()
    private val liveCapped = AtomicLong(0)
    private val liveCloses = AtomicLong(0)

    /** RunnerCapture8017.onClose8017: every finalized close (runner or not). */
    fun onAnyClose8297(env: CanonicalFinalizedTradeBus6464.Envelope) {
        if (!env.terminal || env.mint.isBlank()) return
        val o = outcome8297(env.realizedReturnPct, env.mfePct)
        val live = env.mode.equals("live", ignoreCase = true)
        if (live) {
            liveCloses.incrementAndGet()
            if (o == Outcome8297.WIN_CAPPED) {
                liveCapped.incrementAndGet()
                try { PipelineHealthCollector.labelInc("RUNNER_WIN_CAPPED_8297") } catch (_: Throwable) {}
            }
        }
        val runnerAdmit = (try { com.lifecyclebot.engine.RunnerGrab7967.isGrabbed7980(env.mint) } catch (_: Throwable) { false }) ||
            env.entrySetup.contains("RUNNER", ignoreCase = true)
        if (!runnerAdmit) return
        val k = "${if (live) "live" else "paper"}:${o.name}"
        if (admitOutcomes.size < 32 || admitOutcomes.containsKey(k)) admitOutcomes.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_ADMIT_OUTCOME_8297_${o.name}") } catch (_: Throwable) {}
    }

    fun outcomesLine8297(): String {
        val oc = admitOutcomes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "runnerAdmits8297[$oc] liveCapped8297=${liveCapped.get()}/${liveCloses.get()}"
    }

}

/**
 * V5.0.8297 §A_CONFIRMED_RUN_IS_NOT_WAITING_FOR_OPTIONAL_DATA.
 *
 * 5.0.8282: KIMOTHY +2,153%, Kimchi +4,817%, LEONIDA +2,226% missed on DATA_INCOMPLETE_DEFER_8136. In
 * LiveEdgeGate7877.liveRefusal the admission's DEFER_DATA branch returns BEFORE the run evidence is read: a RunnerPlay8018
 * re-entry ticket (the bot's own labels saw the coin +100% over its decision price, chart standing with buyers, its own
 * 30-minute record not stood down) or a RunnerGrab7967 grab (a cell proven to run, chart firming / not breaking, record
 * not stood down) is let through the proven-only door by runnerForming8113 — but only after the data branch already
 * returned, so the run was deferred for bp / chg / shape inputs the run itself had answered.
 *
 * A confirmed run now passes the deferral ONLY when every missing input is optional (no critical one: price, economics,
 * liquidity, route, sellability, depth, quote, identity, supply, decimals, wallet / balance, cost). The requested fetch
 * still goes out. Every later gate still decides: setup / timing / tactic negatives, the spike-chase rule, watch-first,
 * the core net-edge test, the FDG, hard safety, rug, sellability, liquidity floors, sizing and the buy door.
 */
object RunnerDefer8297 {
    private val released = AtomicLong(0)

    /** Pure: does a confirmed run pass this admission deferral? */
    fun releases8297(terminal: CandidateAdmission8136.Terminal8136?, missing: List<String>, confirmedRun: Boolean): Boolean =
        terminal == CandidateAdmission8136.Terminal8136.DEFER_DATA && confirmedRun && missing.isNotEmpty() &&
            missing.none { CandidateAdmission8136.criticalMissing8170(it) }

    /**
     * LiveEdgeGate7877.liveRefusal: the admission terminal the live door branches on — [d]'s own terminal, or null (no
     * terminal: the later gates decide) when a confirmed run passes an optional-only data deferral.
     */
    fun terminalFor8297(ts: TokenState, d: CandidateAdmission8136.Decision8136?, nowMs: Long,
                        candidatePositive8399: () -> Boolean = { false }): CandidateAdmission8136.Terminal8136? {
        val t = d?.terminal ?: return null
        if (t != CandidateAdmission8136.Terminal8136.DEFER_DATA) return t
        val missing = CandidateAdmission8136.missingOf8144(d)
        if (missing.isEmpty() || missing.any { CandidateAdmission8136.criticalMissing8170(it) }) return t
        val run = (try { com.lifecyclebot.engine.RunnerPlay8018.ticketActive8018(ts.mint, nowMs) } catch (_: Throwable) { false }) ||
            (try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Throwable) { false })
        // V5.0.8399 §P1_PRECEDENCE — candidate-specific POSITIVE evidence (measured cell edge, kind proven in this lane, a
        // specialist match, a lifecycle runner ticket, an expert-wallet entry) outranks a generic missing-optional-input
        // deferral, exactly as the directive orders it (hard safety > candidate negative > candidate positive > lane priors
        // > economics > missing data / generic prior). 8096 reached these routes; 8136-8398 returned the deferral first, so
        // a coin with a proven reason to trade waited on a fetch for an input its route did not need. Critical misses
        // (price / liq / route / sellability) still defer above.
        val positive = run || (try { candidatePositive8399() } catch (_: Throwable) { false })
        if (!releases8297(t, missing, positive)) return t
        released.incrementAndGet()
        if (!run) releasedPositive8399.incrementAndGet()
        try { com.lifecyclebot.engine.DataDeferFetch8180.request8180(ts, missing, nowMs) } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc(if (run) "RUNNER_CONFIRMED_PASSES_OPTIONAL_DEFER_8297" else "CANDIDATE_POSITIVE_PASSES_OPTIONAL_DEFER_8399")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("RUNNER_DEFER_8297", ts.mint)) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle("RUNNER_CONFIRMED_PASSES_OPTIONAL_DEFER_8297",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} optionalMissing=${missing.joinToString(",").take(120)} action=later_gates_decide")
            }
        } catch (_: Throwable) {}
        return null
    }

    private val releasedPositive8399 = java.util.concurrent.atomic.AtomicLong(0L)
    fun statusLine8297(): String = "runnerDefer8297[released=${released.get()} candidatePositive8399=${releasedPositive8399.get()}]"
}
