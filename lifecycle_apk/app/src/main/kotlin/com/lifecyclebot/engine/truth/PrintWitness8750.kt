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
        val outliers = ArrayDeque<Out8801>()
    }

    /** One refused print waiting to prove a re-pricing: its price, chain time, signature and trader (blank: unstated). */
    class Out8801(val px: Double, val at: Long, val sig: String, val wallet: String)

    // V5.0.8801 (external review of 8800, verified): five UNWITNESSED prints could build a coin's baseline, and three copies
    // of one wrong price from one feed re-priced it. A baseline is now built only from witnessed prints, and a re-pricing
    // needs independent trades: different signatures, more than one price, and more than one trader where traders are named.
    private const val DISTINCT_PRICE_8801 = 1e-9

    /** Pure: are these refused prints independent trades — not one feed repeating one price, nor one wallet alone? */
    fun shiftIndependent8801(run: List<Out8801>): Boolean {
        if (run.isEmpty()) return false
        val lo = run.minOf { it.px }; val hi = run.maxOf { it.px }
        if (!(hi > lo * (1.0 + DISTINCT_PRICE_8801))) return false            // a stuck or foreign quote, never a market
        val sigs = run.map { it.sig }.filter { it.isNotBlank() }
        if (sigs.size != sigs.distinct().size) return false                    // one transaction seen twice is one trade
        if (run.all { it.wallet.isNotBlank() } && run.map { it.wallet }.distinct().size < 2) return false
        return true
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
     * V5.0.8801 — only a [witnessed] print builds the baseline or counts toward a re-pricing; an unwitnessed one is judged
     * by the baseline (and passes while none exists) but leaves it as it was.
     */
    fun consensus8800(c: Consensus8800, priceSol: Double, atMs: Long, witnessed: Boolean = true, sig: String = "", wallet: String = ""): Boolean {
        val med = median8800(c.accepted, atMs)
        if (med == null || inBand8800(priceSol, med, CONSENSUS_BAND)) {
            if (witnessed) {
                c.accepted.addLast(priceSol to atMs)
                while (c.accepted.size > CONSENSUS_KEEP) c.accepted.removeFirst()
                c.outliers.clear()
            }
            return true
        }
        if (!witnessed) return false
        c.outliers.addLast(Out8801(priceSol, atMs, sig, wallet))
        while (c.outliers.isNotEmpty() && atMs - c.outliers.first().at > SHIFT_WINDOW_MS) c.outliers.removeFirst()
        while (c.outliers.size > SHIFT_N) c.outliers.removeFirst()
        val run = c.outliers.toList()
        if (run.size >= SHIFT_N && run.all { r -> run.all { inBand8800(r.px, it.px, SHIFT_AGREE) } } && shiftIndependent8801(run)) {
            c.accepted.clear(); run.forEach { c.accepted.addLast(it.px to it.at) }; c.outliers.clear()
            shifts8800.incrementAndGet()
            return true
        }
        return false
    }

    // ── V5.0.8801 §A_PRICE_MUST_FIT_THE_COIN ──────────────────────────────────────────────────────────────────────────
    //
    // The 8800 consensus removes isolated spikes, not a feed that is wrong all the time: 15.7 % of the 5.0.8797 dataset's
    // decisions (235 of 950 coins) carried a price path more than 2.5x away from the coin's own reported market cap, and
    // "plumber"'s whole first hour after graduation ran ~21x high. Two physical facts now bound every print:
    //  - CURVE: a coin on an active pump.fun bonding curve trades between the curve's opening price and its graduation price
    //    (cap ~28 .. ~410 SOL at 1e9 supply); a print outside [CURVE_MIN_CAP_SOL, CURVE_MAX_CAP_SOL] is a unit error;
    //  - ANCHOR: price x supply x SOL/USD may not sit more than [ANCHOR_BAND]x ABOVE the cap an independent source reported
    //    for the coin in the last [ANCHOR_MAX_AGE_MS] (TokenFacts.anchorMcap8801: DexScreener, Birdeye, pump.fun API, curve
    //    account). The allowance grows with the anchor's age (a real run moves the price before the next provider read), and
    //    it is one-sided on purpose: a print far UNDER the anchor may be a real crash, and a crash must always reach the
    //    stops (StopMarkConfirm8418 reads these prints); an isolated low is the consensus's to refuse, a persistent one is a
    //    producer bug repaired at its source (PumpSwapTape8307 maps a pool only once chain has proven it).
    // Every refusal is counted by feed and reason (the report names the producer to repair).
    private const val CURVE_MIN_CAP_SOL = 20.0
    private const val CURVE_MAX_CAP_SOL = 1_000.0
    private const val PUMP_SUPPLY = 1.0e9
    private const val ANCHOR_BAND = 4.0
    private const val ANCHOR_MAX_AGE_MS = 120_000L

    /** Pure: why [priceSol] cannot be this coin's price (null = it can). [anchorAgeMs] is how old [anchorCapUsd] is. */
    fun physicsRefusal8801(priceSol: Double, onActiveCurve: Boolean, supplyWhole: Double, solUsd: Double,
                           anchorCapUsd: Double?, anchorAgeMs: Long = 0L): String? {
        if (!(priceSol > 0.0) || !priceSol.isFinite()) return "NOT_A_PRICE"
        if (onActiveCurve && (priceSol * PUMP_SUPPLY !in CURVE_MIN_CAP_SOL..CURVE_MAX_CAP_SOL)) return "CURVE_PHYSICS"
        if (anchorCapUsd != null && anchorCapUsd > 0.0 && supplyWhole > 0.0 && solUsd > 0.0) {
            val r = priceSol * supplyWhole * solUsd / anchorCapUsd
            if (!r.isFinite() || r > anchorAllowance8801(anchorAgeMs)) return "MCAP_ANCHOR"
        }
        return null
    }

    /** Pure: how far above an independent cap [ageMs] old a print may sit: [ANCHOR_BAND]x fresh, +[ANCHOR_BAND]x a minute. */
    fun anchorAllowance8801(ageMs: Long): Double = ANCHOR_BAND * (1.0 + maxOf(0L, ageMs) / 60_000.0)

    // V5.0.8801 (external review of 8800, verified): the curve reader refuses a coin KNOWN to have graduated, but a
    // graduation the bot missed (a Helius outage) left the frozen curve standing as the coin's witness, and as its "on the
    // curve" physics. A curve reading is now this coin's only while it is recent, short of completion (the curve completes
    // at ~85 real SOL and freezes there), and no wSOL PumpSwap pool of the coin is proven on chain.
    private const val CURVE_REF_MAX_AGE_MS = 120_000L
    private const val CURVE_COMPLETE_REAL_SOL = 84.0

    /** Pure: may a curve reading of [realSol], [ageMs] old, stand as the coin's price? */
    fun curveUsable8801(realSol: Double, ageMs: Long, provenPool: Boolean): Boolean =
        !provenPool && realSol.isFinite() && realSol >= 0.0 && realSol < CURVE_COMPLETE_REAL_SOL && ageMs in -60_000L..CURVE_REF_MAX_AGE_MS

    /** The coin's live curve, or null (unknown, stale, at completion, graduated, or a proven pool). */
    private fun liveCurve8801(mint: String, nowMs: Long): CurveReserves7951.ExitReserveObservation? = try {
        CurveReserves7951.activeExitObservation(mint, nowMs, CURVE_REF_MAX_AGE_MS)?.takeIf {
            curveUsable8801(it.realSol, nowMs - it.observedAtMs, com.lifecyclebot.network.PumpSwapTape8307.hasProvenPool8801(mint))
        }
    } catch (_: Throwable) { null }

    /** The live inputs of [physicsRefusal8801] for [mint]. */
    private fun physics8801(mint: String, priceSol: Double, nowMs: Long): String? {
        val pump = try { com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) } catch (_: Throwable) { false }
        val onCurve = pump && liveCurve8801(mint, nowMs) != null
        val anchor = TokenFacts.anchorMcap8801(mint, nowMs, ANCHOR_MAX_AGE_MS)
            ?: return physicsRefusal8801(priceSol, onCurve, Double.NaN, 0.0, null)
        val supply = if (pump) PUMP_SUPPLY else try {
            com.lifecyclebot.engine.BotService.status.tokens[mint]?.let { TokenFacts.fact(it, TokenFacts.Field.SUPPLY_WHOLE)?.value }
        } catch (_: Throwable) { null } ?: Double.NaN
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        return physicsRefusal8801(priceSol, onCurve, supply, solUsd, anchor.value, nowMs - anchor.observedAtMs)
    }

    private val refusedBy8801 = ConcurrentHashMap<String, AtomicLong>()
    private fun refused8801(src: String, why: String) {
        if (refusedBy8801.size < 200) refusedBy8801.computeIfAbsent("${src.ifBlank { "?" }.take(28)}:$why") { AtomicLong() }.incrementAndGet()
        else refusedBy8801.computeIfAbsent("OTHER:$why") { AtomicLong() }.incrementAndGet()
    }

    private val consensus = ConcurrentHashMap<String, Consensus8800>()
    private val outliers8800 = AtomicLong()
    private val shifts8800 = AtomicLong()

    /** The live check, updating [mint]'s consensus. */
    private fun consensusAccepts8800(mint: String, priceSol: Double, atMs: Long, nowMs: Long, witnessed: Boolean, sig: String, wallet: String): Boolean {
        if (consensus.size > MAX_TRACKED) consensus.entries.removeIf { e -> synchronized(e.value) { e.value.accepted.lastOrNull()?.second ?: 0L } < nowMs - CONSENSUS_WINDOW_MS }
        val c = consensus.computeIfAbsent(mint) { Consensus8800() }
        return synchronized(c) { consensus8800(c, priceSol, atMs, witnessed, sig, wallet) }
    }

    /**
     * DataOrchestrator.onTradePrint7819 (the candle / launch-tape / mark-bar path, fed by feeds that do not all pass
     * [check8750]): read-only — may [priceSol] of [mint] set a bar, a peak or a low? True while the coin has no consensus.
     */
    fun consensusAllows8800(mint: String, priceSol: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        // V5.0.8801 — the bar path obeys the same physics as the print route
        physics8801(mint, priceSol, nowMs)?.let { refused8801("BAR_PATH", it); return false }
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
        liveCurve8801(mint, nowMs)?.let { o ->   // V5.0.8801 — never a frozen (missed-graduation) curve
            val p = CurveReserves7951.priceSol7951(o.virtualSol, o.virtualTokens)
            if (p > 0.0 && p.isFinite()) return p
        }
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
    fun check8750(mint: String, priceSol: Double, sol: Double, tokens: Double, atMs: Long, nowMs: Long, basis: Basis = Basis.TRADE_RATIO,
                  src: String = "", sig: String = "", wallet: String = ""): Verdict = try {
        val v00 = verdict8750(priceSol, sol, tokens, reference8750(mint, nowMs), basis)
        if (v00 == Verdict.OUT_OF_BAND) refused8801(src, "WITNESS_8X")
        // V5.0.8801 — the coin's physics (curve range, independent cap) before anything else
        val physics = if (v00 != Verdict.OUT_OF_BAND) physics8801(mint, priceSol, nowMs) else null
        if (physics != null) { refused8801(src, physics); try { PipelineHealthCollector.labelInc("TAPE_PRINT_PHYSICS_REFUSED_8801") } catch (_: Throwable) {} }
        val v0 = if (physics != null) Verdict.OUT_OF_BAND else v00
        // V5.0.8800 — an otherwise acceptable print still has to agree with the coin's own recent market.
        val v = if (v0 != Verdict.OUT_OF_BAND &&
            !consensusAccepts8800(mint, priceSol, atMs, nowMs, witnessed = v0 != Verdict.UNWITNESSED, sig = sig, wallet = wallet)) {
            refused8801(src, "CONSENSUS")
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
        " vaultAsks=${vaultAsks.get()} tracked=${lastWitnessed.size} refusedBy8801{${refusedBy8801.entries.sortedByDescending { it.value.get() }.take(14).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} consensus8800[outliers=${outliers8800.get()} shifts=${shifts8800.get()} mints=${consensus.size}] " +
        "rule=a print enters the tape only when an independent reading of the coin agrees within ${BAND.toInt()}x and the coin's own last prints agree within ${CONSENSUS_BAND.toInt()}x " +
        "(a re-pricing is taken on $SHIFT_N agreeing, independent, witnessed prints inside ${SHIFT_WINDOW_MS / 1000}s; only witnessed prints build the baseline)]"
}
