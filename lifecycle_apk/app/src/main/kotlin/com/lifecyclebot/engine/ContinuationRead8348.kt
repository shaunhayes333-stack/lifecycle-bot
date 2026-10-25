package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8348 §UNOBSERVED_IS_NOT_ZERO — every native momentum mandate reads ONE continuation, with its provenance.
 *
 * 5.0.8341 LIVE: EXPRESS refused NATIVE_NO_CONTINUATION 1,567 times; SSP (+402 %) read "EXPRESS:N:0:NO_CONTINUATION:
 * 0.3% < 1.0%" at 7 s of age. A 7-second-old coin has no 1 h change (the field cannot exist yet); its 0.0 was judged as
 * "no continuation" — a terminal mandate refusal — where the input had simply not arrived. 8236 mapped that case to a
 * DEFER only when buy pressure was unobserved too (Express can synthesise momentum from a strong tape), so a coin with a
 * stamped buy pressure and no observed price change was still refused on a value nobody measured.
 *
 * READ ([resolve8348], pure; [read8348] at the desk): inside the launch hour with tape prints, the tape's change over the
 * coin's actual age (TradeTape8100.read8100().chg.second — the last print against the launch price, the 1 h window base of
 * a coin younger than an hour, LaunchCohort8173.windowBase8173) — FROM_TAPE; else the provider's 1 h change when observed
 * (stamped, or non-zero) — FROM_1H; else the local history move over 2+ points — FROM_HISTORY; else the move since the
 * pump.fun create price (ExpressMomentum8190) — FROM_LAUNCH; else a non-zero 5 m change — FROM_5M; else UNOBSERVED (0.0,
 * flagged — never presented as a flat reading).
 *
 * VERDICT ([deferMomentum8348], pure; [reviewMomentum8348] at the desk, after the 8236 mapping and before the grab lane
 * review): a momentum-mandate refusal (NO_CONTINUATION, NO_MOMENTUM, MOMENTUM_TOO_LOW, WEAK_MOMENTUM, REJECTED_MOMENTUM,
 * mom+/mom=) that fired on an UNOBSERVED read becomes NATIVE_MOMENTUM_UNOBSERVED_DEFER_8236 — DATA-class to the receiver
 * task (HandoffThroughput8190 "UNOBSERVED"), remembered for and answered by the 8343/8345 hydration re-evaluation
 * ([dataDefer8348]) — and is not gradeable (it was never a judgement of the coin). An observed read below the bar refuses
 * as before. Safety, rug, sellability and liquidity rules are never mapped; no value is invented.
 */
object ContinuationRead8348 {
    enum class Source8348 { FROM_TAPE, FROM_1H, FROM_HISTORY, FROM_LAUNCH, FROM_5M, UNOBSERVED }

    data class Continuation8348(val pct: Double, val source: Source8348) {
        val observed: Boolean get() = source != Source8348.UNOBSERVED
    }

    private const val EPS_8348 = 0.000001
    private const val LAUNCH_HOUR_MIN_8348 = 60.0

    private val MOMENTUM_RULES_8348 = listOf("NO_CONTINUATION", "NO_MOMENTUM", "MOMENTUM_TOO_LOW", "WEAK_MOMENTUM",
        "REJECTED_MOMENTUM", "MOM+", "MOM=", "MOM<")
    private val NEVER_MAPPED_8348 = listOf("RUG", "SAFETY", "HONEYPOT", "LIQ", "BLOCK", "BLACKLIST", "SELLABILITY", "FREEZE", "MINT_AUTH")

    private val fromTape = AtomicLong(0L)
    private val from1h = AtomicLong(0L)
    private val fromHistory = AtomicLong(0L)
    private val fromLaunch = AtomicLong(0L)
    private val from5m = AtomicLong(0L)
    private val unobserved = AtomicLong(0L)
    private val unobservedDeferred = AtomicLong(0L)
    private val refusedObserved = AtomicLong(0L)

    private fun nonZero8348(v: Double): Boolean = v.isFinite() && kotlin.math.abs(v) > EPS_8348

    /** Pure: a coin inside its launch hour (age known, 0 <= age < 60 min). */
    fun youngCoin8348(ageMin: Double?): Boolean = ageMin != null && ageMin.isFinite() && ageMin >= 0.0 && ageMin < LAUNCH_HOUR_MIN_8348

    /**
     * Pure: the continuation read and where it came from. [tapeChangeOverAgePct] is the tape's change over the coin's own
     * age (used only inside the launch hour); [chgObservedAtMs] is the 8084 stamp of the provider change fields (a stamped
     * 0.0 is a real flat read); [sinceLaunchPct] the move since the create price when no history exists.
     */
    fun resolve8348(
        lastChange1h: Double, chgObservedAtMs: Long, chg5m: Double, histPct: Double, histPoints: Int,
        tapeChangeOverAgePct: Double?, sinceLaunchPct: Double?, ageMin: Double?,
    ): Continuation8348 = when {
        youngCoin8348(ageMin) && tapeChangeOverAgePct != null && tapeChangeOverAgePct.isFinite() ->
            Continuation8348(tapeChangeOverAgePct, Source8348.FROM_TAPE)
        nonZero8348(lastChange1h) || (chgObservedAtMs > 0L && lastChange1h.isFinite()) ->
            Continuation8348(lastChange1h, Source8348.FROM_1H)
        histPoints >= 2 && histPct.isFinite() -> Continuation8348(histPct, Source8348.FROM_HISTORY)
        sinceLaunchPct != null && sinceLaunchPct.isFinite() -> Continuation8348(sinceLaunchPct, Source8348.FROM_LAUNCH)
        nonZero8348(chg5m) -> Continuation8348(chg5m, Source8348.FROM_5M)
        else -> Continuation8348(0.0, Source8348.UNOBSERVED)
    }

    /** The tape's change over the coin's own age (its 1 h base is the launch price inside the launch hour), or null when it cannot state one. */
    fun tapeChangeOverAge8348(mint: String, nowMs: Long): Double? = try {
        com.lifecyclebot.engine.truth.TradeTape8100.read8100(mint, nowMs)?.chg?.second?.takeIf { it.isFinite() }
    } catch (_: Throwable) { null }

    /** SpecialistBrainBridge7542.evaluate: the one continuation read of [ts] this cycle (its source counted). */
    fun read8348(ts: TokenState, histPct: Double, histPoints: Int, ageMin: Double?, sinceLaunchPct: Double?, nowMs: Long): Continuation8348 {
        val tape = if (youngCoin8348(ageMin)) tapeChangeOverAge8348(ts.mint, nowMs) else null
        val c = resolve8348(ts.lastPriceChange1h, ts.priceChangeObservedAtMs8084, ts.lastPriceChange5m, histPct, histPoints, tape, sinceLaunchPct, ageMin)
        when (c.source) {
            Source8348.FROM_TAPE -> fromTape.incrementAndGet()
            Source8348.FROM_1H -> from1h.incrementAndGet()
            Source8348.FROM_HISTORY -> fromHistory.incrementAndGet()
            Source8348.FROM_LAUNCH -> fromLaunch.incrementAndGet()
            Source8348.FROM_5M -> from5m.incrementAndGet()
            Source8348.UNOBSERVED -> unobserved.incrementAndGet()
        }
        return c
    }

    /**
     * Pure: is the lanes' shared momentum input (the 5 m change, else the local history move) an observation? A stamped
     * change field, a non-zero 5 m change, two history points, or the tape read that stands in for an empty history.
     */
    fun momentumObserved8348(chg5m: Double, chgObservedAtMs: Long, histPoints: Int, c: Continuation8348): Boolean =
        nonZero8348(chg5m) || chgObservedAtMs > 0L || histPoints >= 2 || c.source == Source8348.FROM_TAPE

    /** Pure: is [reason] a momentum-mandate refusal this read may answer (never safety / liquidity, never an existing DEFER)? */
    private fun momentumRule8348(reason: String): Boolean {
        val r = reason.uppercase()
        return r.isNotBlank() && !r.contains("_DEFER") && NEVER_MAPPED_8348.none { r.contains(it) } && MOMENTUM_RULES_8348.any { r.contains(it) }
    }

    /** Pure: the DEFER verdict that replaces a momentum refusal fired on an unobserved read, or null when the refusal stands. */
    fun deferMomentum8348(reason: String, observed: Boolean): String? =
        if (!observed && momentumRule8348(reason)) "${LaneInputTruth8236.MOMENTUM_DEFER}:${reason.take(60)}" else null

    /** Pure: a data deferral the hydration re-evaluation answers — 8343's UNHYDRATED (liquidity / mcap) or this momentum deferral. */
    fun dataDefer8348(reason: String?): Boolean =
        (reason != null && com.lifecyclebot.engine.truth.OnChainDepth8038.reserveCause8385(reason.removePrefix("DEFERRED_")) != null) ||
        HydrationReeval8343.unhydratedDefer8343(reason) || reason?.uppercase()?.contains("MOMENTUM_UNOBSERVED_DEFER") == true

    /**
     * SpecialistBrainBridge7542.evaluate, after the 8236 mapping: every authoritative refusal on a momentum mandate is
     * reviewed against its read's provenance — EXPRESS against the continuation read, the other lanes against the shared
     * momentum input. Unobserved: DEFER (not gradeable). Observed: the refusal stands and is counted as a judgement.
     */
    fun reviewMomentum8348(out: MutableMap<String, SpecialistBrainBridge7542.Opinion>, expressObserved: Boolean, momObserved: Boolean) {
        for ((lane, o) in out.toList()) {
            if (o.eligible || !o.authoritative) continue
            if (o.reason.uppercase().contains("MOMENTUM_UNOBSERVED_DEFER")) { unobservedDeferred.incrementAndGet(); continue }
            if (!momentumRule8348(o.reason)) continue
            val observed = if (lane == "EXPRESS") expressObserved else momObserved
            val defer = deferMomentum8348(o.reason, observed)
            if (defer == null) { refusedObserved.incrementAndGet(); continue }
            out[lane] = o.copy(reason = defer, gradeable = false)
            unobservedDeferred.incrementAndGet()
            try { PipelineHealthCollector.labelInc("NATIVE_MOMENTUM_UNOBSERVED_DEFER_8348_$lane") } catch (_: Throwable) {}
        }
    }

    /** " continuation8348[fromTape=.. from1h=.. fromHistory=.. fromLaunch=.. from5m=.. unobserved=.. unobservedDeferred=.. refusedObserved=..]" */
    fun statusLine8348(): String =
        " continuation8348[fromTape=${fromTape.get()} from1h=${from1h.get()} fromHistory=${fromHistory.get()} fromLaunch=${fromLaunch.get()} " +
            "from5m=${from5m.get()} unobserved=${unobserved.get()} unobservedDeferred=${unobservedDeferred.get()} refusedObserved=${refusedObserved.get()}]"

    fun counts8348(): Map<String, Long> = linkedMapOf(
        "fromTape" to fromTape.get(), "from1h" to from1h.get(), "fromHistory" to fromHistory.get(), "fromLaunch" to fromLaunch.get(),
        "from5m" to from5m.get(), "unobserved" to unobserved.get(), "unobservedDeferred" to unobservedDeferred.get(), "refusedObserved" to refusedObserved.get(),
    )

    internal fun resetForTests8348() {
        fromTape.set(0L); from1h.set(0L); fromHistory.set(0L); fromLaunch.set(0L); from5m.set(0L)
        unobserved.set(0L); unobservedDeferred.set(0L); refusedObserved.set(0L)
    }
}
