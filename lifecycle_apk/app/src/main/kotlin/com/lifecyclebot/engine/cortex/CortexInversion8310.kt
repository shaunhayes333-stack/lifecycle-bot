package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8310 §A_REFUSAL_THAT_REFUSES_THE_BEST_IS_NOT_A_REFUSAL.
 *
 * Live 5.0.8306: the Cortex refused its best setup (SHITCOIN|REFUSE|LAUNCH_LADDER_PROVEN n11 +90.7%) and EXPRESS read
 * INVERTED. The REFUSE power (C3_PROVEN_NEGATIVE_EDGE / C3_RELATIVE_REFUSE_8019) is proven against the lane's whole
 * "rest" (NEUTRAL + STRONG pooled) and only stands down for a joint cell proven on 30+ grades, so a lane or a setup whose
 * REFUSE reads beat its STRONG reads still had its refusal bind live: the Cortex was refusing the coins it rated best.
 *
 * Rule (measured, automatic, both directions): on a lane — or for the selected setup — whose REFUSE column reads above
 * its STRONG column ([refuseBeatsStrong8310]: REFUSE n >= [MIN_REFUSE_N_8310], STRONG n >= [MIN_STRONG_N_8310], REFUSE
 * mean > STRONG mean), or whose STRONG reads are proven inverted (CortexScoreboard7885.inverted7948), a REFUSE-column
 * refusal does not bind LIVE: it is kept in shadow (counted) and the candidate goes on to every other gate. The same lane
 * gets no Cortex STRONG size-up and no stack-shrink overrule. As soon as REFUSE stops beating STRONG the power returns by
 * itself. PAPER, C1 hard safety, C5 / C6 and the timing wait are untouched; nothing here admits a coin.
 */
object CortexInversion8310 {
    private const val MIN_REFUSE_N_8310 = 10.0
    private const val MIN_STRONG_N_8310 = 5.0

    private val shadowed8310 = ConcurrentHashMap<String, AtomicLong>()
    private val sizeUpSuspended8310 = AtomicLong(0)

    /** Pure: does the REFUSE column read above the STRONG column, each with enough reads to say so? */
    fun refuseBeatsStrong8310(refuseN: Double, refuseMean: Double, strongN: Double, strongMean: Double): Boolean =
        refuseN.isFinite() && strongN.isFinite() && refuseN >= MIN_REFUSE_N_8310 && strongN >= MIN_STRONG_N_8310 &&
            refuseMean.isFinite() && strongMean.isFinite() && refuseMean > strongMean

    /** Pure: is [rule] a REFUSE-column power (the one an inverted lane must not exercise live)? */
    fun refuseColumnRule8310(rule: String?): Boolean =
        rule != null && (rule == "C3_PROVEN_NEGATIVE_EDGE" || rule == "C3_RELATIVE_REFUSE_8019")

    /**
     * Pure: why the REFUSE power is shadowed for this read, or null when it binds. [inverted] = the lane's STRONG reads are
     * proven inverted; lane columns and the selected setup's joint columns (setup STRONG falls back to the lane's STRONG
     * when the setup's own STRONG cell is thinner than [MIN_STRONG_N_8310]).
     */
    fun shadowWhy8310(
        inverted: Boolean,
        laneRefuseN: Double, laneRefuseMean: Double, laneStrongN: Double, laneStrongMean: Double,
        setupRefuseN: Double, setupRefuseMean: Double, setupStrongN: Double, setupStrongMean: Double,
    ): String? {
        if (inverted) return "INVERTED"
        if (refuseBeatsStrong8310(laneRefuseN, laneRefuseMean, laneStrongN, laneStrongMean)) return "LANE"
        val useSetupStrong = setupStrongN.isFinite() && setupStrongN >= MIN_STRONG_N_8310
        val sN = if (useSetupStrong) setupStrongN else laneStrongN
        val sM = if (useSetupStrong) setupStrongMean else laneStrongMean
        if (refuseBeatsStrong8310(setupRefuseN, setupRefuseMean, sN, sM)) return "SETUP"
        return null
    }

    fun noteShadowed8310(lane: String, why: String) {
        shadowed8310.computeIfAbsent("$lane:$why") { AtomicLong(0) }.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CORTEX_REFUSAL_SHADOWED_REFUSE_BEATS_STRONG_8310_${lane}_$why") } catch (_: Throwable) {}
    }

    fun noteSizeUpSuspended8310(lane: String) {
        sizeUpSuspended8310.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CORTEX_SIZE_UP_SUSPENDED_REFUSE_BEATS_STRONG_8310_$lane") } catch (_: Throwable) {}
    }

    fun statusLine8310(): String =
        "inversion8310[shadowed=${shadowed8310.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "0" }} " +
            "sizeUpSuspended=${sizeUpSuspended8310.get()} rule=REFUSE n>=${MIN_REFUSE_N_8310.toInt()} beats STRONG n>=${MIN_STRONG_N_8310.toInt()} -> refusal shadow]"
}
