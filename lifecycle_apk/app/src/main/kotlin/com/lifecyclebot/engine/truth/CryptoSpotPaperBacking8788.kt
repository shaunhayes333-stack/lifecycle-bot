package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8788 §CRYPTO_SPOT_LIVE_FOLLOWS_ITS_OWN_PAPER_RECORD — owner, Tuning console: "crypto spot shows high expectation but
 * 0 live trades". The CRYPTO_SPOT paper book read +4.075 SOL (n169, W19/L13/s137, 59 % win rate) while the 8756 live crypto
 * funnel blocked 99 of 99 candidates at the canonical contract; 11 of them as LIVE_NON_POSITIVE_EXPECTANCY_7828, an expected
 * value taken from the meme-built oracle / forward model / formula fallback — never from this lane's own record. The owner
 * chose (as for the MOONSHOT mirror, 8757): a LIVE CRYPTO_SPOT candidate that expectancy refusal would stop is admitted when
 * the lane's own paper record backs it ([paperBacks8788]: 20+ decisive closes, a positive mean, positive SOL and a positive
 * profit-factor edge).
 *
 * Everything else stays binding and runs before this point in CanonicalAssetEntryContract6551: hard safety, the live route,
 * the crypto toggle, the wallet lock / routable floor, the oracle's hard-safety verdict. Bounds: at most [MAX_PER_HOUR_8788]
 * backed admits an hour, and an automatic stop once live CRYPTO_SPOT closes since the first backed admit realise
 * -[LOSS_STOP_SOL_8788] SOL. The window and the trip are persisted (read at boot by [load8788]).
 *
 * Note (StrategyTelemetry 3974): paper was declared hypothesis-only for live expectancy; this is the owner's explicit,
 * bounded exception for one lane.
 */
object CryptoSpotPaperBacking8788 {
    const val LANE_8788 = "CRYPTO_SPOT"
    const val REASON_8788 = "CRYPTO_SPOT_PAPER_BACKED_8788"
    const val MIN_DECISIVE_8788 = 20
    const val LOSS_STOP_SOL_8788 = 0.1
    const val MAX_PER_HOUR_8788 = 4
    @Volatile var enabled8788: Boolean = true

    @Volatile private var startMs8788 = 0L
    @Volatile private var tripped8788 = false
    @Volatile private var stopCheckedAtMs8788 = 0L
    // V5.0.8806 — owner: "the crypto trader still isn't buying anything at all despite its own tactics showing decent returns
    // in paper". The backing only ever answered lane CRYPTO_SPOT (static majors); every coin the crypto trader actually finds
    // is a dynamic alt, lane CRYPTO_ALT, so on the 5.0.8802 report it was never even consulted (backed=0 record=-) while 176
    // candidates died at LIVE_NON_POSITIVE_EXPECTANCY_7828. Each spot lane is now backed by ITS OWN paper record; the hourly
    // cap and the -0.1 SOL automatic stop are shared across them. CRYPTO_LEV is never backed.
    val BACKED_LANES_8806 = setOf(LANE_8788, "CRYPTO_ALT")
    private class Rec8806 { @Volatile var atMs = 0L; @Volatile var backs = false; @Volatile var line = "-" }
    private val records8806 = java.util.concurrent.ConcurrentHashMap<String, Rec8806>()
    private val backed8788 = AtomicLong(0L)
    private val admitTimes8788 = java.util.ArrayDeque<Long>()
    // A candidate clearing the EV exception is not yet an executable intent.
    // Hold a short-lived permit while the *remaining* canonical gates and
    // intent registration complete; only registered intents spend the quota.
    private val pendingPermits8832 = linkedMapOf<String, Long>()
    private const val PERMIT_TTL_MS_8832 = 90_000L
    @Volatile private var prefs8788: android.content.SharedPreferences? = null

    /** Pure: does a lane record of [wins] / [losses] decisive closes, [meanPct], [totalSol] and [pfEdgePp] back live entries? */
    fun paperBacks8788(wins: Int, losses: Int, meanPct: Double, totalSol: Double, pfEdgePp: Double): Boolean =
        wins + losses >= MIN_DECISIVE_8788 && meanPct.isFinite() && meanPct > 0.0 &&
            totalSol.isFinite() && totalSol > 0.0 && pfEdgePp.isFinite() && pfEdgePp > 0.0

    /** Pure: has [realisedSol] since the first backed admit reached the automatic stop? */
    fun lossStopHit8788(realisedSol: Double): Boolean = realisedSol.isFinite() && realisedSol <= -LOSS_STOP_SOL_8788

    /** Boot (LearningResetSweep7781.applyPendingAtBoot): read the persisted window and trip. */
    @Synchronized fun load8788(ctx: android.content.Context) {
        val p = try { ctx.applicationContext.getSharedPreferences("aate_crypto_spot_backing_8788", android.content.Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        prefs8788 = p
        startMs8788 = maxOf(startMs8788, p.getLong("start_ms", 0L))
        tripped8788 = tripped8788 || p.getBoolean("tripped", false)
        // The hourly quota must not reset when Android restarts this process.
        val now = System.currentTimeMillis()
        p.getString("admitted_at_ms_8832", "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it in (now - 3_600_000L)..now }
            .forEach { if (it !in admitTimes8788) admitTimes8788.addLast(it) }
        prunePermits8832(now)
    }

    private fun persist8788() {
        try {
            prefs8788?.edit()?.putLong("start_ms", startMs8788)
                ?.putBoolean("tripped", tripped8788)
                ?.putString("admitted_at_ms_8832", admitTimes8788.joinToString(","))?.apply()
        } catch (_: Throwable) {}
    }

    private fun recordBacks8788(lane: String, nowMs: Long): Boolean {
        val r = records8806.getOrPut(lane) { Rec8806() }
        if (nowMs - r.atMs < 60_000L) return r.backs
        r.atMs = nowMs
        val m = try {
            com.lifecyclebot.engine.StrategyTelemetry.computeLeaderboard(environment = "paper")
                .firstOrNull { it.strategy.equals(lane, ignoreCase = true) }
        } catch (_: Throwable) { null }
        r.backs = m != null && paperBacks8788(m.wins, m.losses, m.meanPnlPct, m.totalSolPnl, m.pfExpectancyPp)
        r.line = if (m == null) "none" else
            "n${m.trades} W${m.wins}/L${m.losses} mean=${"%+.1f".format(m.meanPnlPct)}% sol=${"%+.3f".format(m.totalSolPnl)} pfEdge=${"%+.1f".format(m.pfExpectancyPp)}"
        return r.backs
    }

    private fun stopped8788(nowMs: Long): Boolean {
        if (tripped8788) return true
        if (startMs8788 <= 0L || nowMs - stopCheckedAtMs8788 < 60_000L) return false
        stopCheckedAtMs8788 = nowMs
        val realised = try {
            com.lifecyclebot.engine.LiveEntrySafetyHold.LiveConfidenceStats.byLane8280(startMs8788)
                .filterKeys { it.trim().uppercase() in BACKED_LANES_8806 }.values.sumOf { it.expectancySol * it.canonicalN }
        } catch (_: Throwable) { 0.0 }
        if (lossStopHit8788(realised)) {
            tripped8788 = true
            persist8788()
            try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_PAPER_BACKING_LOSS_STOP_8788") } catch (_: Throwable) {}
        }
        return tripped8788
    }

    private fun prunePermits8832(nowMs: Long) {
        while (admitTimes8788.isNotEmpty() && nowMs - admitTimes8788.peekFirst() > 3_600_000L) admitTimes8788.pollFirst()
        pendingPermits8832.entries.removeIf { (_, at) -> nowMs - at > PERMIT_TTL_MS_8832 }
    }

    /** Pure regression seam: pending permits count, even before sealing. */
    internal fun room8832(admitted: Int, pending: Int): Boolean =
        admitted >= 0 && pending >= 0 && admitted + pending < MAX_PER_HOUR_8788

    internal enum class Availability8870 { READY, DISABLED_OR_WRONG_LANE, LOSS_STOP, RECORD_NOT_BACKING, CAPACITY }

    /** Capacity is temporary scheduling state; it is not evidence that a signal's expectancy is negative. */
    internal fun classifyAvailability8870(enabled: Boolean, eligibleLane: Boolean, stopped: Boolean,
                                         recordBacks: Boolean, admitted: Int, pending: Int): Availability8870 = when {
        !enabled || !eligibleLane -> Availability8870.DISABLED_OR_WRONG_LANE
        stopped -> Availability8870.LOSS_STOP
        !recordBacks -> Availability8870.RECORD_NOT_BACKING
        !room8832(admitted, pending) -> Availability8870.CAPACITY
        else -> Availability8870.READY
    }

    @Synchronized internal fun availability8870(lane: String?, nowMs: Long): Availability8870 {
        val l = lane?.trim()?.uppercase().orEmpty()
        if (!enabled8788 || l !in BACKED_LANES_8806) return Availability8870.DISABLED_OR_WRONG_LANE
        if (stopped8788(nowMs)) return Availability8870.LOSS_STOP
        val record = recordBacks8788(l, nowMs)
        prunePermits8832(nowMs)
        return classifyAvailability8870(true, true, false, record, admitTimes8788.size, pendingPermits8832.size)
    }

    fun capacityWait8870(lane: String?, nowMs: Long): Boolean =
        availability8870(lane, nowMs) == Availability8870.CAPACITY

    /**
     * Read-only eligibility at the EV gate. An Oracle / Cortex / sizing refusal
     * must not consume a live backing slot or masquerade as an approval.
     */
    @Synchronized fun backs8788(lane: String?, nowMs: Long): Boolean {
        return availability8870(lane, nowMs) == Availability8870.READY
    }

    /** Reserve only once every *other* canonical gate, including sizing, passed. */
    @Synchronized fun reserve8832(lane: String?, nowMs: Long, attemptId: String): Boolean {
        if (attemptId.isBlank() || pendingPermits8832.containsKey(attemptId)) return false
        if (!backs8788(lane, nowMs)) {
            try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_PAPER_BACKING_RATE_OR_PROOF_REFUSE_8832") } catch (_: Throwable) {}
            return false
        }
        pendingPermits8832[attemptId] = nowMs
        return true
    }

    /** Count an approval only after the exact immutable intent was registered. */
    @Synchronized fun commit8832(attemptId: String): Boolean {
        val reservedAt = pendingPermits8832.remove(attemptId) ?: return false
        admitTimes8788.addLast(reservedAt)
        if (startMs8788 <= 0L) startMs8788 = reservedAt
        backed8788.incrementAndGet()
        persist8788()
        try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_PAPER_BACKED_8788") } catch (_: Throwable) {}
        return true
    }

    /** Registration failure releases its permit without counting an approval. */
    @Synchronized fun release8832(attemptId: String) { pendingPermits8832.remove(attemptId) }

    fun statusLine8788(): String =
        "cryptoSpotPaperBacking8788[on=$enabled8788 backed=${backed8788.get()} reserved=${pendingPermits8832.size} hourly=${admitTimes8788.size} records{${BACKED_LANES_8806.joinToString(" ") { "$it=${records8806[it]?.line ?: "-"}" }}} stop=-${LOSS_STOP_SOL_8788}SOL tripped=$tripped8788 maxPerHour=$MAX_PER_HOUR_8788]"
}
