package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7697 §FEWER_LARGER_HIGHER_CONVICTION.
 *
 * Operator, after the first 5.0.7696 live hour: "its spreading capital way
 * way too wide. less trades overall but higher conviction and higher
 * probability of profitability would make way more sense."
 *
 * What the stack was doing, from the same snapshots:
 *
 *   - SmartSizerV3's live floor is (tradeable x 10%) clamped into
 *     [routableMin, LIVE_FLOOR_CEILING_SOL_7127 = 0.05]. The band size on a
 *     small wallet is 1-5% of tradeable, always under that floor, so every
 *     live fill was promoted to ~0.042-0.057 SOL (five to six dollars) no
 *     matter what the wallet held: 0.28 SOL became five $5 tickets across
 *     MOONSHOT, BLUECHIP and TREASURY.
 *   - cfg.maxConcurrentLivePositions = 24 (LiveExecutionGate floors it at 24).
 *   - Twelve lanes each hold an advisory ~11% allocation, so the book is
 *     designed to be spread across all of them at once.
 *   - Entry admission is FDG allow + a 15-point score floor; the lane's own
 *     specialist brain (NATIVE_BRAIN_*_7542) is consulted and its verdict
 *     recorded, but a live entry proceeds whether or not it said yes
 *     (MOONSHOT nativeAllow=1858 / nativeReject=8184 in the same hour the
 *     lane's live candidates were sized).
 *
 * A $5 ticket cannot move a 0.3 SOL wallet even when it is right, and each
 * round trip pays the same fixed costs as a real position. This authority is
 * the one place that states the opposite doctrine, and the three surfaces
 * that decide size, count and admission read it:
 *
 *   SLOTS      how many live positions the wallet may hold at once:
 *              < 1 SOL -> 2, < 5 -> 3, < 20 -> 4, otherwise 6.
 *   SHARE      each position is tradeable / slots (never over 50%, never over
 *              MAX_POSITION_SOL), applied as the live floor in SmartSizerV3
 *              and as the share guard, so the sizer stops clamping to 0.05.
 *   CONVICTION a live entry needs the owning lane's specialist to be eligible
 *              for this mint when it has an authoritative opinion, and no
 *              danger-bucket / toxic-lane / proven-dead consensus objection.
 *              Paper is untouched: paper's job is to explore.
 *
 * None of this changes any exit. Fewer, larger, better-chosen entries; the
 * 7693-7696 exit work lets the ones that are right run.
 */
object LiveConcentrationDoctrine7697 {

    const val MAX_SHARE_7697 = 0.50
    /** Hard ceiling on one live position, whatever the wallet. */
    const val MAX_POSITION_SOL_7697 = 2.0

    private val slotRefusals = AtomicLong(0)
    private val convictionRefusals = AtomicLong(0)
    private val convictionAllows = AtomicLong(0)
    private val floorApplied = AtomicLong(0)
    @Volatile private var lastRefusal: String = ""

    /** Concurrent live positions the wallet may hold. */
    fun slots(tradeableSol: Double): Int {
        val t = if (tradeableSol.isFinite()) tradeableSol else 0.0
        return when {
            t < 1.0 -> 2
            t < 5.0 -> 3
            t < 20.0 -> 4
            else -> 6
        }
    }

    /** Share of tradeable one live position takes. */
    fun share(tradeableSol: Double): Double =
        kotlin.math.min(MAX_SHARE_7697, 1.0 / slots(tradeableSol))

    /** Target size of one live position, in SOL. The caller still applies the routable minimum. */
    fun positionSol(tradeableSol: Double): Double {
        val t = if (tradeableSol.isFinite()) tradeableSol.coerceAtLeast(0.0) else 0.0
        val sol = (t * share(t)).coerceAtMost(MAX_POSITION_SOL_7697)
        if (sol > 0.0) floorApplied.incrementAndGet()
        return sol
    }

    /** The figure live sizing uses (LIVE_WALLET_AUTHORITY_6686) less the untouchable reserve. */
    fun liveTradeableSol(): Double {
        val wallet = try { com.lifecyclebot.engine.BotService.status.walletSol } catch (_: Throwable) { 0.0 }
        val reserve = try { LiveSpendReserveAuthority7255.RESERVE_SOL } catch (_: Throwable) { 0.0 }
        return if (wallet.isFinite()) (wallet - reserve).coerceAtLeast(0.0) else 0.0
    }

    fun liveOpenCount(): Int = try {
        CanonicalPositionAuthority6441.openPositions().count { it.mode.equals("live", ignoreCase = true) }
    } catch (_: Throwable) { 0 }

    data class SlotVerdict(val allow: Boolean, val reason: String, val open: Int, val slots: Int, val tradeableSol: Double)

    /** Live only: refuse a new open once the wallet's slots are taken. */
    fun slotVerdict(openLiveCount: Int = liveOpenCount()): SlotVerdict {
        val tradeable = liveTradeableSol()
        // Slots are judged on the wallet as a whole (cash + what is already
        // deployed), otherwise the second position would read a half-spent
        // wallet and halve itself again.
        val deployed = try {
            CanonicalCapitalAuthority6450.snapshot().openMarketValueSol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        } catch (_: Throwable) { 0.0 }
        val n = slots(tradeable + deployed)
        val allow = openLiveCount < n
        if (!allow) {
            slotRefusals.incrementAndGet()
            lastRefusal = "slots open=$openLiveCount/$n tradeable=${"%.4f".format(tradeable)}"
            try { PipelineHealthCollector.labelInc("LIVE_CONCENTRATION_SLOTS_FULL_7697") } catch (_: Throwable) {}
        }
        return SlotVerdict(allow, if (allow) "OK" else "LIVE_CONCENTRATION_SLOTS_FULL_7697", openLiveCount, n, tradeable)
    }

    data class ConvictionVerdict(val allow: Boolean, val reason: String)

    private val DANGER_OBJECTIONS_7697 = listOf("LOSING_PATTERN_DANGER_ZONE", "LEARNED_TOXIC_LANE", "PROVEN_DEAD_CONTEXT")

    /**
     * Live only. The owning lane's specialist must not have rejected this mint
     * (an authoritative, fresh opinion with eligible=false refuses; no opinion
     * fails open — absence of a view is not a view), and the brain consensus
     * must not carry a danger-bucket objection.
     */
    fun convictionVerdict(mint: String, lane: String, objections: Collection<String>): ConvictionVerdict {
        val danger = objections.firstOrNull { o -> DANGER_OBJECTIONS_7697.any { o.contains(it) } }
        if (danger != null) return refuse(mint, lane, "DANGER_OBJECTION:${danger.take(60)}")
        val laneKey = lane.trim().uppercase()
        if (laneKey.isNotBlank()) {
            val opinion = try {
                com.lifecyclebot.engine.SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions?.get(laneKey)
            } catch (_: Throwable) { null }
            if (opinion != null && opinion.authoritative && !opinion.eligible) {
                return refuse(mint, laneKey, "SPECIALIST_REJECTS:$laneKey:score=${opinion.score}:${opinion.reason.take(60)}")
            }
        }
        convictionAllows.incrementAndGet()
        return ConvictionVerdict(true, "OK")
    }

    private fun refuse(mint: String, lane: String, reason: String): ConvictionVerdict {
        convictionRefusals.incrementAndGet()
        lastRefusal = "conviction lane=$lane $reason"
        try {
            PipelineHealthCollector.labelInc("LIVE_CONVICTION_REFUSED_7697")
            PipelineHealthCollector.labelInc("LIVE_CONVICTION_REFUSED_7697_${lane.take(20)}")
            ForensicLogger.lifecycle("LIVE_CONVICTION_REFUSED_7697", "mint=${mint.take(10)} lane=$lane reason=$reason")
        } catch (_: Throwable) {}
        return ConvictionVerdict(false, reason)
    }

    fun statusLine(): String {
        val t = liveTradeableSol()
        return "slots=${slots(t)} share=${"%.0f".format(share(t) * 100)}% positionSol=${"%.4f".format(positionSol(t))} " +
            "liveOpen=${liveOpenCount()} tradeable=${"%.4f".format(t)} " +
            "slotRefusals=${slotRefusals.get()} convictionAllow=${convictionAllows.get()} convictionRefused=${convictionRefusals.get()} " +
            "floorApplied=${floorApplied.get()}" +
            (if (lastRefusal.isNotBlank()) " last=[$lastRefusal]" else "") +
            " read=fewer_larger_higher_conviction_live_entries_exits_untouched"
    }
}
