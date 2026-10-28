package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8118 §ADMIT_THE_LAUNCH_WHEN_IT_ENTERS_THE_PROVEN_BAND.
 *
 * The forward labels' best cells are PUMP_PORTAL_WS|<lane>|MC_10K_100K|AGE_LT15M (+460..+708%, n40-78) while the same
 * source under $10k loses (-30..-35%). In live mode a fresh create is parked in probation (cold pump base: no volume,
 * curve depth under $1.5k) with no token row, and nothing re-admitted it when it crossed $10k: trade frames for a mint
 * without a row were dropped (applyPumpTradeMark7278 `status.tokens[mint] ?: return`), probation PRICE_UP has no
 * callers, and the launch-tape re-admit carried no cap. "Intake by source" showed 4 PumpPortal intakes per session.
 *
 * A PumpPortal launch with no token row whose trade frame puts it in [BAND_LO_USD, BAND_HI_USD) while it is under
 * [MAX_AGE_MS] old is admitted once, under its true source (PUMP_PORTAL_WS), with the curve's real depth. Every intake,
 * safety, FDG and buy-door gate still runs.
 *
 * V5.0.8136 §A_FAILED_INTAKE_IS_NOT_AN_ADMISSION.
 *  - The once-only `fired` set consumed the mint's only attempt on the CLAIM, before intake ran; an intake that
 *    refused it (stale generation, runtime pause, dedupe window) or parked it back in probation without a token row
 *    (admitProtectedMemeIntake answers true for "ALREADY_IN_PROBATION") left it unadmitted for good. Each mint now has
 *    a claim PENDING -> ACKNOWLEDGED | RETRYABLE | TERMINAL ([step8136], pure): a re-claim is idempotent while
 *    PENDING, a PENDING that never settles expires after [PENDING_TTL_MS_8136] and counts as a failed attempt, a
 *    RETRYABLE is re-granted after a growing backoff, and the [MAX_ATTEMPTS_8136]th failure (or a hard reject) is
 *    TERMINAL. BotService settles the claim from what intake actually did ([settleIntake8136]).
 *  - With PumpPortal's keyed trade stream refused (AUTH_DENIED) no trade frame ever reported a band crossing.
 *    [maybeSweep8136] (clocked by PumpFunWS frames) reads each young launch's freshest OBSERVED price — an executed
 *    trade mark (MarkBars7948: Helius/PumpPortal prints, the create frame) or the curve's own reserves
 *    (CurveReserves7951) — times pump.fun's fixed curve supply, and hands a genuine crossing to the same band entry.
 *    Nothing is estimated: no observation inside [OBS_FRESH_MS_8136], no crossing.
 *
 * V5.0.8418 — superseded "times pump.fun's fixed curve supply": the cap is the observed price x the mint account's
 * verified supply (MintSupply8418, decimals-adjusted). A launch whose mint account has not been read has an UNKNOWN cap
 * and is not a crossing yet (a read is requested; its claim is untouched, so the next sweep after the read can admit it).
 */
object PumpBandEntry8118 {
    private const val BAND_LO_USD = 10_000.0
    private const val BAND_HI_USD = 100_000.0
    private const val MAX_AGE_MS = 15L * 60_000L
    private const val MAX_TRACKED = 3_000

    data class Meta(val symbol: String, val name: String, val createdAtMs8136: Long = 0L)

    private val meta = ConcurrentHashMap<String, Meta>()

    // ── V5.0.8136 claim state machine ──────────────────────────────────────
    enum class ClaimState8136 { PENDING, ACKNOWLEDGED, RETRYABLE, TERMINAL }
    enum class Event8136 { CLAIM, INTAKE_ACKNOWLEDGED, INTAKE_RETRYABLE, INTAKE_TERMINAL }
    enum class Outcome8136 { ACKNOWLEDGED, RETRYABLE, TERMINAL }

    /** One mint's admission claim: state, grants so far, when the state was entered, and why. */
    data class Claim8136(val state: ClaimState8136, val attempts: Int, val sinceMs: Long, val reason: String)

    /** A transition: the next claim, and whether this event grants an intake attempt now. */
    data class Step8136(val next: Claim8136?, val grant: Boolean)

    internal const val PENDING_TTL_MS_8136 = 60_000L
    internal const val RETRY_BACKOFF_MS_8136 = 30_000L
    internal const val MAX_ATTEMPTS_8136 = 3
    private const val CLAIM_KEEP_MS_8136 = MAX_AGE_MS + 5L * 60_000L

    private val claims = ConcurrentHashMap<String, Claim8136>()

    /** PumpFunWS create frame (before the throttle): remember the launch's name for a later band entry. */
    fun noteCreate8118(mint: String, symbol: String, name: String, atMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (meta.size >= MAX_TRACKED) meta.keys.take(MAX_TRACKED / 10).forEach { meta.remove(it) }
        meta.putIfAbsent(mint, Meta(symbol.ifBlank { mint.take(6) }, name.ifBlank { symbol }, atMs))
    }

    /** Pure: does a launch at [capUsd], [ageMs] old, enter the proven band now? */
    fun inBand8118(capUsd: Double, ageMs: Long): Boolean =
        capUsd.isFinite() && capUsd >= BAND_LO_USD && capUsd < BAND_HI_USD && ageMs in 0L until MAX_AGE_MS

    /**
     * Pure (V5.0.8136): the claim transition for [event] at [nowMs]. CLAIM grants on a new mint, on a PENDING older
     * than [PENDING_TTL_MS_8136] (its intake never settled) and on a RETRYABLE whose backoff (attempts x
     * [RETRY_BACKOFF_MS_8136]) has passed; an attempt past [MAX_ATTEMPTS_8136] becomes TERMINAL instead. Intake
     * outcomes settle a PENDING (an acknowledgement also settles a RETRYABLE); late or repeated events change nothing.
     */
    fun step8136(prior: Claim8136?, event: Event8136, nowMs: Long, reason: String = ""): Step8136 {
        if (prior == null) {
            return if (event == Event8136.CLAIM) Step8136(Claim8136(ClaimState8136.PENDING, 1, nowMs, "FIRST_CROSSING"), true)
            else Step8136(null, false)
        }
        val unchanged = Step8136(prior, false)
        return when (event) {
            Event8136.CLAIM -> when (prior.state) {
                ClaimState8136.PENDING ->
                    if (nowMs - prior.sinceMs < PENDING_TTL_MS_8136) unchanged else reattempt8136(prior, "PENDING_TTL_EXPIRED", nowMs)
                ClaimState8136.RETRYABLE ->
                    if (nowMs - prior.sinceMs < RETRY_BACKOFF_MS_8136 * prior.attempts) unchanged else reattempt8136(prior, prior.reason, nowMs)
                ClaimState8136.ACKNOWLEDGED, ClaimState8136.TERMINAL -> unchanged
            }
            Event8136.INTAKE_ACKNOWLEDGED ->
                if (prior.state == ClaimState8136.PENDING || prior.state == ClaimState8136.RETRYABLE)
                    Step8136(prior.copy(state = ClaimState8136.ACKNOWLEDGED, sinceMs = nowMs, reason = reason.ifBlank { "INTAKE_ACKNOWLEDGED" }), false)
                else unchanged
            Event8136.INTAKE_RETRYABLE ->
                if (prior.state != ClaimState8136.PENDING) unchanged
                else if (prior.attempts >= MAX_ATTEMPTS_8136)
                    Step8136(prior.copy(state = ClaimState8136.TERMINAL, sinceMs = nowMs, reason = "MAX_ATTEMPTS_${reason.ifBlank { "INTAKE_RETRYABLE" }}"), false)
                else Step8136(prior.copy(state = ClaimState8136.RETRYABLE, sinceMs = nowMs, reason = reason.ifBlank { "INTAKE_RETRYABLE" }), false)
            Event8136.INTAKE_TERMINAL ->
                if (prior.state == ClaimState8136.PENDING || prior.state == ClaimState8136.RETRYABLE)
                    Step8136(prior.copy(state = ClaimState8136.TERMINAL, sinceMs = nowMs, reason = reason.ifBlank { "INTAKE_TERMINAL" }), false)
                else unchanged
        }
    }

    private fun reattempt8136(prior: Claim8136, why: String, nowMs: Long): Step8136 =
        if (prior.attempts >= MAX_ATTEMPTS_8136) Step8136(Claim8136(ClaimState8136.TERMINAL, prior.attempts, nowMs, "MAX_ATTEMPTS_$why"), false)
        else Step8136(Claim8136(ClaimState8136.PENDING, prior.attempts + 1, nowMs, "RETRY_$why"), true)

    /** Pure (V5.0.8136): may an intake attempt be granted now for a mint whose claim is [claim]? */
    fun claimOpen8136(claim: Claim8136?, nowMs: Long): Boolean = step8136(claim, Event8136.CLAIM, nowMs).grant

    /** Pure (V5.0.8136): what intake actually did. A hard reject is final; an admission with no token row is not one. */
    fun outcome8136(admitted: Boolean, rowCreated: Boolean, hardRejected: Boolean): Pair<Outcome8136, String> = when {
        hardRejected -> Outcome8136.TERMINAL to "HARD_REJECT"
        admitted && rowCreated -> Outcome8136.ACKNOWLEDGED to "TOKEN_ROW_CREATED"
        admitted -> Outcome8136.RETRYABLE to "PARKED_NO_TOKEN_ROW"
        else -> Outcome8136.RETRYABLE to "INTAKE_REFUSED"
    }

    /**
     * BotService trade frame / band sweep: the launch's name when it enters the band and an admission attempt is
     * granted (claims it), else null. V5.0.8136: a claim is no longer consumed for good — see [step8136].
     */
    fun claim8118(mint: String, capUsd: Double, ageMs: Long, nowMs: Long = System.currentTimeMillis()): Meta? {
        if (!inBand8118(capUsd, ageMs)) return null
        val m = meta[mint] ?: return null
        var granted = false
        claims.compute(mint) { _, prior ->
            val s = step8136(prior, Event8136.CLAIM, nowMs)
            granted = s.grant
            s.next
        }
        trimClaims8136(nowMs)
        if (!granted) return null
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_BAND_CLAIM_GRANTED_8136") } catch (_: Throwable) {}
        return m
    }

    /** BotService.bandEntry8118: settle [mint]'s claim from what protected intake did. */
    fun settleIntake8136(mint: String, admitted: Boolean, rowCreated: Boolean, hardRejected: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val (outcome, why) = outcome8136(admitted, rowCreated, hardRejected)
        settle8136(mint, outcome, why, nowMs)
    }

    private fun settle8136(mint: String, outcome: Outcome8136, reason: String, nowMs: Long) {
        val event = when (outcome) {
            Outcome8136.ACKNOWLEDGED -> Event8136.INTAKE_ACKNOWLEDGED
            Outcome8136.RETRYABLE -> Event8136.INTAKE_RETRYABLE
            Outcome8136.TERMINAL -> Event8136.INTAKE_TERMINAL
        }
        val next = claims.computeIfPresent(mint) { _, prior -> step8136(prior, event, nowMs, reason).next }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_BAND_ENTRY_8136_${next?.state?.name ?: "NO_CLAIM"}") } catch (_: Throwable) {}
    }

    /** The current claim for [mint], or null. */
    fun claimState8136(mint: String): Claim8136? = claims[mint]

    private fun trimClaims8136(nowMs: Long) {
        if (claims.size <= MAX_TRACKED) return
        try { claims.entries.removeIf { nowMs - it.value.sinceMs > CLAIM_KEEP_MS_8136 } } catch (_: Throwable) {}
        if (claims.size > MAX_TRACKED) try {
            claims.entries.sortedBy { it.value.sinceMs }.take(MAX_TRACKED / 10).forEach { claims.remove(it.key) }
        } catch (_: Throwable) {}
    }

    // ── V5.0.8136 band sweep: a crossing seen on another observed price source ──
    enum class BandSource8136 { EXECUTED_MARK, CURVE_RESERVES }

    /** One observed price for a launch (USD per token), with when it was observed and from where. */
    data class Observation8136(
        val mint: String, val createdAtMs: Long, val priceUsd: Double, val observedAtMs: Long, val source: BandSource8136,
        /** V5.0.8418 — the mint account's verified whole-token supply; NaN / 0 = not read, so the cap is UNKNOWN. */
        val verifiedSupply8418: Double = Double.NaN,
    )

    // superseded by V5.0.8418 — PUMP_CURVE_SUPPLY_8136 (a 1e9 constant every cap here was multiplied by) is removed:
    // the cap is price x the mint account's verified supply, or UNKNOWN.
    internal const val OBS_FRESH_MS_8136 = 30_000L
    private const val SWEEP_GAP_MS_8136 = 2_000L
    private const val SWEEP_MAX_FIRES_8136 = 2

    @Volatile private var onBandPrice8136: ((mint: String, priceSol: Double, mcapSol: Double) -> Unit)? = null
    private val lastSweepMs8136 = AtomicLong(0L)

    /** BotService.wireExternalStreams: the band entry a sweep crossing is handed to (the same one trade frames use). */
    fun setOnBandPrice8136(cb: (mint: String, priceSol: Double, mcapSol: Double) -> Unit) {
        onBandPrice8136 = cb
    }

    /**
     * Pure: is [obs] a genuine band crossing at [nowMs] for a mint whose claim is [claim]? The observation must be
     * real (finite, positive), not from the future, no older than [OBS_FRESH_MS_8136], not before the launch, and put
     * the curve cap (price x fixed supply) in the band while the launch is young; the claim must be open.
     */
    fun crossing8136(obs: Observation8136, claim: Claim8136?, nowMs: Long): Boolean {
        if (obs.createdAtMs <= 0L || obs.observedAtMs > nowMs || nowMs - obs.observedAtMs > OBS_FRESH_MS_8136) return false
        if (obs.observedAtMs < obs.createdAtMs) return false
        if (!obs.priceUsd.isFinite() || obs.priceUsd <= 0.0) return false
        val cap8418 = MintSupply8418.capOf8418(obs.verifiedSupply8418, obs.priceUsd)
        if (!inBand8118(cap8418, nowMs - obs.createdAtMs)) return false
        return claimOpen8136(claim, nowMs)
    }

    /** PumpFunWS frame clock: at most every [SWEEP_GAP_MS_8136], hand up to [SWEEP_MAX_FIRES_8136] crossings on. */
    fun maybeSweep8136(nowMs: Long = System.currentTimeMillis()) {
        val cb = onBandPrice8136 ?: return
        val last = lastSweepMs8136.get()
        if (nowMs - last < SWEEP_GAP_MS_8136 || !lastSweepMs8136.compareAndSet(last, nowMs)) return
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!(solUsd.isFinite() && solUsd > 0.0)) return
        var fires = 0
        for ((mint, m) in meta) {
            if (fires >= SWEEP_MAX_FIRES_8136) break
            val born = m.createdAtMs8136
            if (born <= 0L || nowMs - born !in 0L until MAX_AGE_MS) continue
            val claim = claims[mint]
            if (!claimOpen8136(claim, nowMs)) continue
            val obs0 = observed8136(mint, born, nowMs, solUsd) ?: continue
            // V5.0.8418 — the mint account's supply, or a read is requested and this launch waits (cap UNKNOWN).
            val supply8418 = try { MintSupply8418.wholeOrRequest8418(mint) } catch (_: Throwable) { 0.0 }
            if (!(supply8418 > 0.0)) continue
            val obs = obs0.copy(verifiedSupply8418 = supply8418)
            if (!crossing8136(obs, claim, nowMs)) continue
            val priceSol = EconomicUnitInvariant7061.usdToSol(obs.priceUsd, solUsd)
            if (!(priceSol.isFinite() && priceSol > 0.0)) continue
            try { cb(mint, priceSol, priceSol * supply8418) } catch (_: Throwable) {}
            // Only a crossing that took a claim (a launch with no token row yet) spends the sweep's budget; one that
            // already has a row is a no-op in the band entry.
            if (claims[mint] == claim) continue
            fires++
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_BAND_CROSSING_8136_${obs.source.name}") } catch (_: Throwable) {}
        }
    }

    /** The freshest observed price: an executed-trade mark first, else the curve's reserves read inside the window. */
    private fun observed8136(mint: String, born: Long, nowMs: Long, solUsd: Double): Observation8136? {
        val tick = try { MarkBars7948.recentTicks8094(mint, nowMs, OBS_FRESH_MS_8136).lastOrNull() } catch (_: Throwable) { null }
        if (tick != null) return Observation8136(mint, born, tick.second, tick.first, BandSource8136.EXECUTED_MARK)
        val px = try { CurveReserves7951.priceSolFor7951(mint, nowMs, OBS_FRESH_MS_8136) } catch (_: Throwable) { 0.0 }
        // priceSolFor7951 only answers for a reading inside the window; it is stamped at the sweep time.
        return if (px.isFinite() && px > 0.0) Observation8136(mint, born, px * solUsd, nowMs, BandSource8136.CURVE_RESERVES) else null
    }
}
