package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8223 §PAPER_EXIT_PAYS_THE_LIVE_LATENCY.
 *
 * A paper sell filled at the mark that TRIGGERED it. A live sell fills after the trigger -> broadcast path
 * (SellRouteSpine8144: 5.0.8220 measured ~16 s on a normal stop): on a dumping dust pool the price at the fill is
 * not the price at the trigger, and that gap — not the pool impact PaperVenueCost7287 already charges — is where the
 * live -20..-64% stop fills came from while paper booked the stop at its own threshold. Paper trained the learners on
 * exits that cannot be executed.
 *
 * The paper fill now moves by the token's OWN observed momentum over the LIVE-MEASURED latency: the tape mark
 * [latency] ago against the trigger mark gives the velocity the price showed over exactly that horizon; the same
 * move is projected forward over the latency the live path takes. Only an ADVERSE projection is applied (a rising
 * coin does not pay paper a better fill than it triggered at — live chases up with adverse selection, and an
 * optimistic bias is the fault being removed). The latency is the live spine's measured trigger -> broadcast
 * average per exit class (normal / emergency) once [MIN_N_8223] exits measured it, persisted across restarts; before
 * that the 8220 forensic figures stand as labelled priors.
 */
object PaperExitLatency8223 {
    /** 5.0.8220 forensic: normal-stop trigger -> broadcast; replaced by the live measurement at [MIN_N_8223]. */
    internal const val PRIOR_NORMAL_MS_8223 = 16_000L
    internal const val PRIOR_EMERGENCY_MS_8223 = 6_000L
    internal const val MIN_N_8223 = 3
    /** A projected adverse move is bounded here: the pool impact and the -100% floor are charged elsewhere. */
    internal const val MAX_ADVERSE_PCT_8223 = 60.0
    /** The reference mark must be at least this old for a velocity read (a one-tick mark is noise). */
    internal const val MIN_HORIZON_MS_8223 = 3_000L
    private const val KEY_8223 = "PAPER_EXIT_LATENCY_8223"

    private val normalN = AtomicLong(0); private val normalSumMs = AtomicLong(0)
    private val emergencyN = AtomicLong(0); private val emergencySumMs = AtomicLong(0)
    private val charged8223 = AtomicLong(0)
    private val flat8223 = AtomicLong(0)
    private val noReference8223 = AtomicLong(0)
    @Volatile private var loaded = false

    // ── pure ──

    /** Pure: the latency to simulate — the measured average at [n] >= [MIN_N_8223], else the labelled prior. */
    internal fun latencyFrom8223(n: Long, sumMs: Long, priorMs: Long): Long =
        if (n >= MIN_N_8223 && sumMs > 0L) (sumMs / n).coerceIn(500L, 120_000L) else priorMs

    /**
     * Pure: the adverse drift (%) to apply to a sell at [priceNow] when the tape read [priceBefore] exactly
     * [horizonMs] earlier and the live fill lands [latencyMs] after the trigger. The observed velocity over the
     * horizon is projected over the latency; a non-adverse projection is 0; bounded at -[MAX_ADVERSE_PCT_8223].
     */
    internal fun adverseDriftPct8223(priceNow: Double, priceBefore: Double, horizonMs: Long, latencyMs: Long): Double {
        if (!priceNow.isFinite() || !priceBefore.isFinite() || priceNow <= 0.0 || priceBefore <= 0.0) return 0.0
        if (horizonMs < MIN_HORIZON_MS_8223 || latencyMs <= 0L) return 0.0
        val velocityPct = (priceNow / priceBefore - 1.0) * 100.0
        if (velocityPct >= 0.0) return 0.0
        return (velocityPct * (latencyMs.toDouble() / horizonMs.toDouble())).coerceIn(-MAX_ADVERSE_PCT_8223, 0.0)
    }

    // ── live measurement (SellRouteSpine8144.finish8144) ──

    /** A live exit's trigger -> broadcast time, by class. Persisted. */
    fun noteLive8223(emergency: Boolean, triggerToBroadcastMs: Long) {
        if (triggerToBroadcastMs <= 0L || triggerToBroadcastMs > 300_000L) return
        ensureLoaded()
        if (emergency) { emergencyN.incrementAndGet(); emergencySumMs.addAndGet(triggerToBroadcastMs) }
        else { normalN.incrementAndGet(); normalSumMs.addAndGet(triggerToBroadcastMs) }
        try { LearningPersistence.save(KEY_8223, "${normalN.get()},${normalSumMs.get()},${emergencyN.get()},${emergencySumMs.get()}") } catch (_: Throwable) {}
    }

    internal fun latencyMs8223(emergency: Boolean): Long {
        ensureLoaded()
        return if (emergency) latencyFrom8223(emergencyN.get(), emergencySumMs.get(), PRIOR_EMERGENCY_MS_8223)
        else latencyFrom8223(normalN.get(), normalSumMs.get(), PRIOR_NORMAL_MS_8223)
    }

    internal fun measured8223(emergency: Boolean): Boolean {
        ensureLoaded()
        return (if (emergency) emergencyN.get() else normalN.get()) >= MIN_N_8223
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val f = LearningPersistence.load(KEY_8223)?.split(',') ?: return
            if (f.size != 4) return
            normalN.set(f[0].toLongOrNull() ?: 0L); normalSumMs.set(f[1].toLongOrNull() ?: 0L)
            emergencyN.set(f[2].toLongOrNull() ?: 0L); emergencySumMs.set(f[3].toLongOrNull() ?: 0L)
        } catch (_: Throwable) {}
    }

    // ── paper fill ──

    internal data class Fill8223(val price: Double, val driftPct: Double, val latencyMs: Long, val horizonMs: Long, val measured: Boolean)

    /**
     * Executor.paperSell: the executable paper fill for [mint] triggered at [triggerPrice] by [reason] — the trigger
     * mark moved by the adverse drift over the live latency. The tape reference is StructureTracker7962's mark
     * [latency] before now; with no reference the trigger mark stands (counted, never guessed).
     */
    internal fun paperFill8223(mint: String, triggerPrice: Double, reason: String, nowMs: Long = System.currentTimeMillis()): Fill8223 {
        val emergency = try { com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }
        val latency = latencyMs8223(emergency)
        val refAt = nowMs - latency
        val before = try { com.lifecyclebot.engine.chart.StructureTracker7962.markAt7962(mint, refAt) } catch (_: Throwable) { null }
        if (before == null) {
            noReference8223.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PAPER_EXIT_LATENCY_NO_REFERENCE_8223") } catch (_: Throwable) {}
            return Fill8223(triggerPrice, 0.0, latency, 0L, measured8223(emergency))
        }
        val drift = adverseDriftPct8223(triggerPrice, before, latency, latency)
        if (drift < 0.0) {
            charged8223.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PAPER_EXIT_LATENCY_DRIFT_CHARGED_8223")
                PipelineHealthCollector.labelInc(if (emergency) "PAPER_EXIT_LATENCY_DRIFT_EMRG_8223" else "PAPER_EXIT_LATENCY_DRIFT_NORM_8223")
            } catch (_: Throwable) {}
        } else flat8223.incrementAndGet()
        return Fill8223(triggerPrice * (1.0 + drift / 100.0), drift, latency, latency, measured8223(emergency))
    }

    fun statusLine8223(): String {
        ensureLoaded()
        return "paperExitLatency8223[norm=${latencyMs8223(false)}ms(n=${normalN.get()}${if (measured8223(false)) "" else ",prior"}) " +
            "emrg=${latencyMs8223(true)}ms(n=${emergencyN.get()}${if (measured8223(true)) "" else ",prior"}) " +
            "charged=${charged8223.get()} flat=${flat8223.get()} noReference=${noReference8223.get()}]"
    }
}
