package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8189 — A LIFECYCLE HOLD NEEDS RUNNER EVIDENCE.
 *
 * Owner diagnosis of 5.0.8186: 6 live positions, 50 exit-gate evaluations, 0 confirmed sells and 261 lifecycle-hold
 * deferrals. LifecycleHold8065 held every position bought in a lifecycle lane (MOONSHOT / QUALITY / BLUECHIP / CORE)
 * or carrying a runner ticket: its ordinary stops were deferred to the -40% floor, StopAuthority7887 reported the
 * -40% floor as the position's stop, and the scalp ladder (SpikeCapture7943) never ran because the hold sells only its
 * own +100% take. An UNPROVEN loser was therefore held through a loss far past its lane doctrine stop (4-25%,
 * StopAuthority7887), and an unproven winner never banked a rung.
 *
 * The hold now applies only to a position with runner evidence ([Evidence8189]):
 *  - [Evidence8189.PROVEN_PEAK]: the chain-verified, corroborated position peak reached +100%
 *    (RunnerExitContract8136.provenPromotion8136 — position.peakGainPct only ratchets on a corroborated mark);
 *  - [Evidence8189.SEALED_RUNNER_PROVEN_SETUP]: the immutable entry style is a runner style (or RunnerPlan8153 sealed
 *    it a runner) AND the lane's playbook record of the setup it is showing is proven to pay
 *    (LanePlaybook7907.provenSetup8056: 30+ graded labels, mean - SE > 0). A proven setup latches for the position.
 * Anything else is [Evidence8189.UNPROVEN]: it takes its lane's ordinary exits, its lane stop and its profit ladder.
 * Tier-1 exits were never held (LifecycleHold8065.passes8065 / RunnerExitContract8136.emergency8136) and still are not.
 *
 * Every deferral is named ([deferralReason8189]: evidence + exit tier) and a hold that later lets an exit go at a
 * worse gross than the exit it first deferred is counted as REGRET ([regret8189]).
 */
object LifecycleEvidence8189 {

    enum class Evidence8189 { PROVEN_PEAK, SEALED_RUNNER_PROVEN_SETUP, UNPROVEN }

    /** A hold that releases more than this many gross points below its first deferral is regret, not noise. */
    private const val REGRET_PP_8189 = 1.0
    private const val SETUP_RECHECK_MS_8189 = 60_000L
    private const val MAX_KEYS_8189 = 2_000
    private const val MAX_REASON_KEYS_8189 = 48

    // ── pure ──

    /** Pure: the runner evidence of a position. */
    fun evidence8189(sealedRunner: Boolean, setupProven: Boolean, corroboratedPeakPct: Double): Evidence8189 = when {
        RunnerExitContract8136.provenPromotion8136(corroboratedPeakPct) -> Evidence8189.PROVEN_PEAK
        sealedRunner && setupProven -> Evidence8189.SEALED_RUNNER_PROVEN_SETUP
        else -> Evidence8189.UNPROVEN
    }

    /** Pure: may a lifecycle hold apply to a position with this evidence? */
    fun holds8189(e: Evidence8189): Boolean = e != Evidence8189.UNPROVEN

    /** Pure: the named reason a hold deferred [exitReason] (evidence + the exit's authority tier). */
    fun deferralReason8189(e: Evidence8189, exitReason: String): String =
        "LIFECYCLE_HOLD_8189_${e.name}_${RunnerExitContract8136.tierOf8136(exitReason).name}"

    /** Pure: the exit a hold finally let go was worse than the exit it first deferred. */
    fun regret8189(grossAtFirstDeferral: Double, grossAtRelease: Double): Boolean =
        grossAtFirstDeferral.isFinite() && grossAtRelease.isFinite() && grossAtRelease < grossAtFirstDeferral - REGRET_PP_8189

    // ── live evidence ──

    private class Setup8189(val proven: Boolean, val atMs: Long)
    private val setups = ConcurrentHashMap<String, Setup8189>()

    private fun key8189(ts: TokenState) = "${ts.mint}|${ts.position.entryTime}"

    /** The evidence of [ts]'s open position ([lane] = its canonical lane). Cheap: the setup read is cached per position. */
    fun evidenceFor8189(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Evidence8189 {
        val peak = ts.position.peakGainPct
        if (RunnerExitContract8136.provenPromotion8136(peak)) return Evidence8189.PROVEN_PEAK
        if (!sealedRunner8189(ts)) return Evidence8189.UNPROVEN
        return evidence8189(true, setupProven8189(ts, lane, nowMs), peak)
    }

    private fun sealedRunner8189(ts: TokenState): Boolean {
        val style = try { TradeExitContract8077.sealedStyle8136(ts) } catch (_: Throwable) { "" }
        if (RunnerExitContract8136.sealedRunnerStyle8136(style)) return true
        return try { RunnerLive8153.plan8153(ts) == RunnerPlan8153.Plan8153.RUNNER_SEALED } catch (_: Throwable) { false }
    }

    private fun setupProven8189(ts: TokenState, lane: String, nowMs: Long): Boolean {
        val k = key8189(ts)
        val seen = setups[k]
        if (seen != null && (seen.proven || nowMs - seen.atMs in 0L until SETUP_RECHECK_MS_8189)) return seen.proven
        val proven = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetup8056(ts, lane, nowMs) != null } catch (_: Throwable) { false }
        if (setups.size > MAX_KEYS_8189 && !setups.containsKey(k)) setups.clear()
        setups[k] = Setup8189(proven, nowMs)
        return proven
    }

    /** Test seam / restore: record the setup verdict of [ts]'s position. */
    internal fun noteSetupProven8189(ts: TokenState, proven: Boolean, nowMs: Long = System.currentTimeMillis()) {
        setups[key8189(ts)] = Setup8189(proven, nowMs)
    }

    // ── deferral audit ──

    private class Deferral8189(val firstGross: Double, val firstAtMs: Long) { @Volatile var count = 0 }
    private val deferrals = ConcurrentHashMap<String, Deferral8189>()
    private val byReason = ConcurrentHashMap<String, AtomicLong>()
    private val unprovenNotHeld = AtomicLong(0)
    private val released = AtomicLong(0)
    private val regrets = AtomicLong(0)
    private var regretPpSum = 0.0

    /** LifecycleHold8065.defer8065: the hold deferred an exit of the position [key] under [reasonName] at [grossPct]. */
    fun noteDeferral8189(key: String, reasonName: String, grossPct: Double, nowMs: Long = System.currentTimeMillis()) {
        if (deferrals.size > MAX_KEYS_8189 && !deferrals.containsKey(key)) deferrals.clear()
        deferrals.computeIfAbsent(key) { Deferral8189(grossPct, nowMs) }.count++
        if (byReason.size < MAX_REASON_KEYS_8189 || byReason.containsKey(reasonName)) byReason.computeIfAbsent(reasonName) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(reasonName) } catch (_: Throwable) {}
    }

    /**
     * LifecycleHold8065.defer8065: an exit of [key] passes the hold at [grossPct]. When the hold had deferred earlier
     * exits, the episode settles: true = regret (worse than the first deferred exit), false = not, null = never deferred.
     */
    fun noteRelease8189(key: String, grossPct: Double): Boolean? {
        val d = deferrals.remove(key) ?: return null
        released.incrementAndGet()
        val regret = regret8189(d.firstGross, grossPct)
        if (regret) {
            regrets.incrementAndGet()
            synchronized(this) { regretPpSum += d.firstGross - grossPct }
            try { PipelineHealthCollector.labelInc("LIFECYCLE_HOLD_REGRET_8189") } catch (_: Throwable) {}
        }
        return regret
    }

    /** LifecycleHold8065.defer8065: a lifecycle-lane position was not held because it has no runner evidence. */
    fun noteUnprovenNotHeld8189() {
        unprovenNotHeld.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIFECYCLE_UNPROVEN_NOT_HELD_8189") } catch (_: Throwable) {}
    }

    /** The current deferral episode of [key]: (deferrals, gross at the first one), or null. For the proof table. */
    fun deferralOf8189(key: String): Pair<Int, Double>? = deferrals[key]?.let { it.count to it.firstGross }

    fun statusLine8189(): String {
        val top = byReason.entries.sortedByDescending { it.value.get() }.take(6)
            .joinToString(",") { "${it.key.removePrefix("LIFECYCLE_HOLD_8189_")}=${it.value.get()}" }.ifBlank { "-" }
        val n = regrets.get()
        val mean = if (n > 0L) "%.1f".format(synchronized(this) { regretPpSum } / n) else "-"
        return "evidence8189[deferring=${deferrals.size} released=${released.get()} regret=$n meanRegretPp=$mean " +
            "unprovenNotHeld=${unprovenNotHeld.get()} reasons=[$top]]"
    }

    internal fun resetForTest8189() {
        setups.clear(); deferrals.clear(); byReason.clear()
        unprovenNotHeld.set(0); released.set(0); regrets.set(0)
        synchronized(this) { regretPpSum = 0.0 }
    }
}
