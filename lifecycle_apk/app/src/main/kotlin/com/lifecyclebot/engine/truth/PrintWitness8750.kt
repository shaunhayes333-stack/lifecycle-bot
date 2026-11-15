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

    // ── V5.0.8800 §THE_COIN_IS_ITS_OWN_WITNESS ─────────────────────────────────────────────────────────────────────────
    //
    // Owner, on "plumber" (9a13…pump: watched from +78 s, admitted by its lanes 566 times, never bought, ran ~14x): at
    // +18.5 min a print 500x its price set its peak, and a second stream kept printing a fixed price 7x under it. Every lane
    // and learner then read "96 % under its peak" — a rug — while it ran. The launch-hour exports carry such prints on 34 %
    // of launches (777 of 2,255 on 5.0.8797), clustered at 10x and 100x. The 8x band let a 7x print through, and a
    // 500x one passed whenever its "independent" reading was as wrong as it was (a completed curve's frozen reserves).
    //
    // So a print must also agree with the coin's OWN recent market: within [CONSENSUS_BAND]x of the median of its last
    // [CONSENSUS_KEEP] accepted prints inside [CONSENSUS_WINDOW_MS], once [CONSENSUS_MIN] exist. A genuine re-pricing (a fast
    // pump, a venue change) is taken when [SHIFT_N] consecutive outliers agree with each other within [SHIFT_AGREE]x inside
    // [SHIFT_WINDOW_MS]: the coin's consensus then restarts from them. One stray print (or a stream of them interleaved with
    // real trades) never moves the peak, the low, the candles, the launch tape or a label again.
    private const val CONSENSUS_BAND = 3.0
    private const val CONSENSUS_KEEP = 9
    private const val CONSENSUS_MIN = 5
    private const val CONSENSUS_WINDOW_MS = 3L * 60_000L
    private const val SHIFT_N = 3
    private const val SHIFT_AGREE = 1.5
    private const val SHIFT_WINDOW_MS = 60_000L

    class Consensus8800 {
        val accepted = ArrayDeque<Pair<Double, Long>>()     // (price SOL, chain ms), oldest first
        val outliers = ArrayDeque<Pair<Double, Long>>()
    }

    /** Pure: the median of the prices in [prints] no older than [CONSENSUS_WINDOW_MS] before [atMs]; null under [CONSENSUS_MIN]. */
    fun median8800(prints: Collection<Pair<Double, Long>>, atMs: Long): Double? {
        val xs = prints.filter { atMs - it.second <= CONSENSUS_WINDOW_MS && it.first > 0.0 }.map { it.first }.sorted()
        if (xs.size < CONSENSUS_MIN) return null
        return if (xs.size % 2 == 1) xs[xs.size / 2] else (xs[xs.size / 2 - 1] + xs[xs.size / 2]) / 2.0
    }

    private fun inBand8800(a: Double, b: Double, band: Double): Boolean = a > 0.0 && b > 0.0 && (a / b).let { it.isFinite() && it in (1.0 / band)..band }

    /**
     * Pure: does [priceSol] at [atMs] stand against the coin's consensus [c]? Updates [c] (accepted prints, the outlier run).
     * Returns true for an accepted print (in band, no consensus yet, or a confirmed re-pricing), false for an outlier.
     */
    fun consensus8800(c: Consensus8800, priceSol: Double, atMs: Long): Boolean {
        val med = median8800(c.accepted, atMs)
        if (med == null || inBand8800(priceSol, med, CONSENSUS_BAND)) {
            c.accepted.addLast(priceSol to atMs)
            while (c.accepted.size > CONSENSUS_KEEP) c.accepted.removeFirst()
            c.outliers.clear()
            return true
        }
        c.outliers.addLast(priceSol to atMs)
        while (c.outliers.isNotEmpty() && atMs - c.outliers.first().second > SHIFT_WINDOW_MS) c.outliers.removeFirst()
        while (c.outliers.size > SHIFT_N) c.outliers.removeFirst()
        val run = c.outliers.toList()
        if (run.size >= SHIFT_N && run.all { r -> run.all { inBand8800(r.first, it.first, SHIFT_AGREE) } }) {
            c.accepted.clear(); c.accepted.addAll(run); c.outliers.clear()
            shifts8800.incrementAndGet()
            return true
        }
        return false
    }

    private val consensus = ConcurrentHashMap<String, Consensus8800>()
    private val outliers8800 = AtomicLong()
    private val shifts8800 = AtomicLong()

    /** The live check, updating [mint]'s consensus. */
    private fun consensusAccepts8800(mint: String, priceSol: Double, atMs: Long, nowMs: Long): Boolean {
        if (consensus.size > MAX_TRACKED) consensus.entries.removeIf { e -> synchronized(e.value) { e.value.accepted.lastOrNull()?.second ?: 0L } < nowMs - CONSENSUS_WINDOW_MS }
        val c = consensus.computeIfAbsent(mint) { Consensus8800() }
        return synchronized(c) { consensus8800(c, priceSol, atMs) }
    }

    /**
     * DataOrchestrator.onTradePrint7819 (the candle / launch-tape / mark-bar path, fed by feeds that do not all pass
     * [check8750]): read-only — may [priceSol] of [mint] set a bar, a peak or a low? True while the coin has no consensus.
     */
    fun consensusAllows8800(mint: String, priceSol: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val c = consensus[mint] ?: return true
        val med = synchronized(c) { median8800(c.accepted, nowMs) } ?: return true
        return inBand8800(priceSol, med, CONSENSUS_BAND)
    }

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
        val v0 = verdict8750(priceSol, sol, tokens, reference8750(mint, nowMs), basis)
        // V5.0.8800 — an otherwise acceptable print still has to agree with the coin's own recent market.
        val v = if (v0 != Verdict.OUT_OF_BAND && !consensusAccepts8800(mint, priceSol, atMs, nowMs)) {
            outliers8800.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TAPE_PRINT_CONSENSUS_OUTLIER_8800") } catch (_: Throwable) {}
            Verdict.OUT_OF_BAND
        } else v0
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
        " vaultAsks=${vaultAsks.get()} tracked=${lastWitnessed.size} consensus8800[outliers=${outliers8800.get()} shifts=${shifts8800.get()} mints=${consensus.size}] " +
        "rule=a print enters the tape only when an independent reading of the coin agrees within ${BAND.toInt()}x and the coin's own last prints agree within ${CONSENSUS_BAND.toInt()}x " +
        "(a re-pricing is taken on $SHIFT_N agreeing prints inside ${SHIFT_WINDOW_MS / 1000}s)]"
}
