package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8750 §EVERY_PRINT_HAS_A_WITNESS — external review of 21641a619 (verified): most tape sources hand TradeTape8100 a
 * price computed from the print itself (sol / tokens: PumpSwap events, pool balance deltas, ProofData, OHLCV, validator),
 * and LaunchHour8628's 8x check then compared that price with itself. A decimal or unit error in a decode (a 9-decimal
 * token read as 6, a non-SOL quote) passed every check and reached the tape, its candles, the label marks, the sniper and
 * the launch replay.
 *
 * Now every print is judged against a witness:
 *  - RESERVES: the caller DECLARES its price a reserves price ([Basis.RESERVES]: pump.fun curve virtual reserves) and the
 *    print's own SOL / tokens agrees with it within [BAND]x (different fields of the same event), as does any independent reading;
 *  - WITNESSED: the price is the print's own ratio and an INDEPENDENT reading of the same coin agrees within [BAND]x: the
 *    curve account (CurveReserves7951), the pool's own vaults (OnChainDepth8038), or the last witnessed print, each no older
 *    than [REF_MAX_AGE_MS];
 *  - OUT_OF_BAND: the witness disagrees — refused and counted (a unit error, never a market move: 8x inside a few minutes);
 *  - UNWITNESSED: nothing independent exists yet — it stays in the in-memory tape (flow, candles) but never reaches the launch
 *    history, the sniper or a label mark, and the coin's pool vaults are asked for, so its next prints are witnessed.
 * Buyer flow (who bought, how much SOL) is recorded before this check: it does not depend on the price.
 */
object PrintWitness8750 {
    private const val BAND = 8.0
    private const val REF_MAX_AGE_MS = 10L * 60_000L
    private const val SAME_RATIO = 1e-6
    private const val MAX_TRACKED = 20_000

    enum class Verdict { RESERVES, WITNESSED, OUT_OF_BAND, UNWITNESSED }

    /**
     * V5.0.8750 (review of e0c6f3e) — where the caller's price came from, declared by the caller, never inferred from the
     * numbers: RESERVES only for a price read from the venue's own reserves fields of the same event (pump.fun curve virtual
     * reserves: HeliusCurveTape8354, PumpPortal curve frames); everything else is TRADE_RATIO and needs an independent reading.
     */
    enum class Basis { RESERVES, TRADE_RATIO }

    /** Pure: is [priceSol] this print's own SOL / tokens (true also when the print has no usable quantity)? */
    fun ownRatio8750(priceSol: Double, sol: Double, tokens: Double): Boolean =
        sol.isFinite() && sol > 0.0 && tokens.isFinite() && tokens > 0.0 && kotlin.math.abs(priceSol * tokens / sol - 1.0) < SAME_RATIO

    private fun within(a: Double, b: Double): Boolean {
        if (!(a > 0.0 && b > 0.0 && a.isFinite() && b.isFinite())) return false
        val m = a / b
        return m.isFinite() && m in (1.0 / BAND)..BAND
    }

    /**
     * Pure: the verdict on one print of declared [basis] given an independent reference price [refSol] (null when none).
     * A RESERVES price is cross-checked against its own trade's SOL / tokens (different fields of the same event: a decimal
     * error in either shows) and against any independent reading; any other price must agree with an independent reading.
     */
    fun verdict8750(priceSol: Double, sol: Double, tokens: Double, refSol: Double?, basis: Basis = Basis.TRADE_RATIO): Verdict {
        if (!(priceSol > 0.0) || !priceSol.isFinite()) return Verdict.OUT_OF_BAND
        if (refSol != null && !within(priceSol, refSol)) return Verdict.OUT_OF_BAND
        if (basis == Basis.RESERVES) {
            val hasQty = sol.isFinite() && sol > 0.0 && tokens.isFinite() && tokens > 0.0
            if (hasQty && !within(sol / tokens, priceSol)) return Verdict.OUT_OF_BAND
            return Verdict.RESERVES
        }
        return if (refSol != null) Verdict.WITNESSED else Verdict.UNWITNESSED
    }

    private val lastWitnessed = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val counts = Verdict.values().associateWith { AtomicLong() }
    private val vaultAsks = AtomicLong()
    private val askedAt = ConcurrentHashMap<String, Long>()
    private const val ASK_GAP_MS = 60_000L

    /** The newest independent price of [mint] (SOL per token) no older than [REF_MAX_AGE_MS], or null. */
    private fun reference8750(mint: String, nowMs: Long): Double? {
        try {
            CurveReserves7951.activeExitObservation(mint, nowMs, REF_MAX_AGE_MS)?.let { o ->
                val p = CurveReserves7951.priceSol7951(o.virtualSol, o.virtualTokens)
                if (p > 0.0 && p.isFinite()) return p
            }
        } catch (_: Throwable) {}
        try {
            OnChainDepth8038.freshRead8198(mint, REF_MAX_AGE_MS, nowMs)?.let { r ->
                val p = OnChainDepth8038.priceUsd8038(r.quoteSol, r.baseTokens, 1.0)
                if (p > 0.0 && p.isFinite()) return p
            }
        } catch (_: Throwable) {}
        return lastWitnessed[mint]?.takeIf { nowMs - it.second in 0L..REF_MAX_AGE_MS }?.first
    }

    /**
     * TradeTape8100.note8285: the verdict on this print of [mint] at chain time [atMs]. OUT_OF_BAND keeps it out of the tape
     * entirely. Only RESERVES / WITNESSED prints reach the launch history, the sniper and the label marks; an UNWITNESSED one
     * stays in the in-memory tape (flow, candles) and asks for the coin's pool vaults. Never throws.
     */
    fun check8750(mint: String, priceSol: Double, sol: Double, tokens: Double, atMs: Long, nowMs: Long, basis: Basis = Basis.TRADE_RATIO): Verdict = try {
        val v = verdict8750(priceSol, sol, tokens, reference8750(mint, nowMs), basis)
        counts[v]?.incrementAndGet()
        when (v) {
            Verdict.RESERVES, Verdict.WITNESSED -> {
                if (lastWitnessed.size > MAX_TRACKED) lastWitnessed.entries.removeIf { nowMs - it.value.second > REF_MAX_AGE_MS }
                lastWitnessed.merge(mint, priceSol to atMs) { old, new -> if (new.second >= old.second) new else old }
            }
            Verdict.OUT_OF_BAND -> try { PipelineHealthCollector.labelInc("TAPE_PRINT_OUT_OF_BAND_8750") } catch (_: Throwable) {}
            Verdict.UNWITNESSED -> {
                val prior = askedAt[mint]
                if ((prior == null || nowMs - prior !in 0L..ASK_GAP_MS) && com.lifecyclebot.AATEApp.appContextOrNull() != null) {
                    if (askedAt.size > MAX_TRACKED) askedAt.entries.removeIf { nowMs - it.value > ASK_GAP_MS }
                    askedAt[mint] = nowMs
                    try { OnChainDepth8038.requestMints8543(listOf(mint), REF_MAX_AGE_MS); vaultAsks.incrementAndGet() } catch (_: Throwable) {}
                }
            }
        }
        v
    } catch (_: Throwable) { Verdict.UNWITNESSED }

    /** ForwardReturnLabeler7731: is the tape's print of [mint] at [atMs] a witnessed one (usable as a label mark)? */
    fun witnessedPrint8750(mint: String, atMs: Long): Boolean = lastWitnessed[mint]?.second?.let { it >= atMs } == true

    fun statusLine8750(): String = "printWitness8750[" + Verdict.values().joinToString(" ") { "${it.name.lowercase()}=${counts[it]?.get() ?: 0}" } +
        " vaultAsks=${vaultAsks.get()} tracked=${lastWitnessed.size} rule=a print enters the tape only when an independent reading of the coin agrees within ${BAND.toInt()}x]"
}
