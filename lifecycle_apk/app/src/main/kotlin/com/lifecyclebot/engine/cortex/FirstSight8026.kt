package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8026 — FIRST SIGHT: buy the runner when it is first seen, on evidence, without betting the wallet.
 *
 * Owner, 5.0.8025: "I want first sight ... tie it to maturity, data integrity, the EV and win rate %. Grow the
 * wallet balance exponentially and still protect the cash balance. The $50 to a millionaire mentality."
 *
 * 8025 took the Cortex's OVERRIDE power away until a lane has ~1,000 graded STRONG reads (maturity). That
 * left QubitCat (+1,853%) and brigitte (+1,029%) — refused at first sight as "no setup" — to the late
 * re-entry. First sight is a different, smaller right than override: a route-minimum probe on one coin,
 * held as a runner, granted only when ALL of these hold at that moment:
 *
 *  EVIDENCE   the coin's own Cortex read is STRONG on a fresh mark, and its lane's STRONG record shows edge:
 *             evidence fraction >= 0.5 (STRONG beats NEUTRAL by the margin, t-stat), EV lower bound
 *             (mean - SE, net of measured cost) > 0, and win rate >= [MIN_WIN_RATE].
 *  INTEGRITY  the price is an independent print (not a cap-derived seed), under 30 s old, and the row's cap
 *             agrees with price x supply (TrustedMcap8019) — no decision on numbers that contradict each other.
 *  SAFETY     never hard-blocked, never Mayhem, never a coin-specific proven loser (its cell, band or setup
 *             measured negative); a lane-wide aggregate ("no setup fired" in this lane) does not bind one coin.
 *  CASH       open first-sight probes <= 1 + 4 x maturity (1 while immature), their cost <= [MAX_EXPOSURE]
 *             of the wallet, today's realised probe losses <= [DAILY_LOSS_CAP] of the day's opening wallet,
 *             and the probes' own realised record (15+ closes, mean + SE < 0) has not stood them down.
 *
 * SIZE compounds with the wallet, not with hope: the probe opens at the route minimum and the Cortex's
 * conviction sizing (quarter-Kelly of EQUITY x authority, and authority now includes maturity) grows the
 * stake as the wallet and the lane's lifetime sample grow. Bigger wallet, bigger bets; proven lane, bigger
 * share — $50 compounds, a bad night cannot take more than the caps allow.
 */
object FirstSight8026 {
    const val MIN_EVIDENCE = 0.5
    const val MIN_WIN_RATE = 0.25
    const val MAX_EXPOSURE = 0.20
    const val DAILY_LOSS_CAP = 0.10
    const val PRICE_FRESH_MS = 30_000L
    private const val TICKET_MS = 3L * 60_000L
    private const val RECORD_MIN_N = 15

    // ── pure ──

    /** The lane's STRONG record has edge: evidence, EV lower bound and win rate all clear. */
    fun edgeClears8026(evidence: Double, meanPct: Double, sePct: Double, winRate: Double): Boolean =
        evidence.isFinite() && evidence >= MIN_EVIDENCE &&
            meanPct.isFinite() && sePct.isFinite() && meanPct - sePct > 0.0 &&
            winRate.isFinite() && winRate >= MIN_WIN_RATE

    /** Open probes allowed at this lane maturity: 1 while immature, up to 5 at full (unreachable) maturity. */
    fun maxOpen8026(maturity: Double): Int = 1 + (4.0 * maturity.coerceIn(0.0, 1.0)).toInt()

    /** Cash still allows another probe of [nextCostSol]? */
    fun cashAllows8026(openCostSol: Double, nextCostSol: Double, walletSol: Double, lossTodaySol: Double, dayStartSol: Double): Boolean {
        if (!(walletSol > 0.0)) return false
        if (openCostSol + nextCostSol > MAX_EXPOSURE * (walletSol + openCostSol)) return false
        val base = if (dayStartSol > 0.0) dayStartSol else walletSol
        return lossTodaySol < DAILY_LOSS_CAP * base
    }

    /** A refusal that names a coin-specific proven loser — first sight never overrides it. */
    fun coinProvenLoser8026(refusal: String): Boolean {
        val r = refusal.uppercase()
        return r.contains("CELL_NEGATIVE") || r.contains("BAND_NEGATIVE") || r.contains("SETUP_PROVEN_LOSING") || r.contains("PROVEN_NEGATIVE_EDGE")
    }

    /** The probes' own realised record has stood them down. */
    fun standsDown8026(n: Double, mean: Double, se: Double): Boolean = n >= RECORD_MIN_N && mean + se < 0.0

    // ── state ──

    private val tickets = ConcurrentHashMap<String, Long>()        // mint -> granted at
    private val granted = ConcurrentHashMap<String, Long>()        // mint -> granted at (until close)
    private val record = CortexLedger7885.Stat()
    @Volatile private var day = -1L
    @Volatile private var dayStartSol = 0.0
    @Volatile private var lossTodaySol = 0.0
    private val grants = AtomicLong(0)
    private val refusedEdge = AtomicLong(0)
    private val refusedIntegrity = AtomicLong(0)
    private val refusedCash = AtomicLong(0)

    private fun rollDay(nowMs: Long, walletSol: Double) {
        val d = nowMs / 86_400_000L
        if (d != day) { day = d; dayStartSol = walletSol; lossTodaySol = 0.0 }
    }

    /** Data integrity of [ts]'s price and cap right now. */
    fun integrityOk8026(ts: TokenState, nowMs: Long): Boolean {
        if (!(ts.lastPrice > 0.0) || nowMs - ts.lastPriceUpdate !in 0L..PRICE_FRESH_MS) return false
        if (!com.lifecyclebot.engine.truth.TrustedMcap8019.independentPrice8019(ts.lastPriceSource)) return false
        val supply = com.lifecyclebot.engine.truth.TrustedMcap8019.supplyFor8019(ts)
        val row = ts.lastMcap
        if (supply > 0.0 && row > 0.0) {
            val agreed = com.lifecyclebot.engine.truth.TrustedMcap8019.reconcile8019(row, ts.lastPrice, supply)
            if (agreed != row) return false
        }
        return true
    }

    /**
     * Cortex7885.overrulesEdgeRefusal, when the lane lacks override maturity: may [ts] be bought now as a
     * first-sight probe? [evidence]/[strong]/[maturity] are the lane's (caller holds no lock on them).
     */
    fun grant8026(ts: TokenState, lane: String, refusal: String, evidence: Double, strong: CortexLedger7885.Stat, maturity: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (coinProvenLoser8026(refusal)) return false
        tickets[ts.mint]?.let { if (nowMs - it in 0L..TICKET_MS) return true }
        val se = if (strong.n > 1.0) kotlin.math.sqrt(strong.variance() / strong.n) else Double.POSITIVE_INFINITY
        if (!edgeClears8026(evidence, strong.mean(), se, strong.winRate())) { refusedEdge.incrementAndGet(); return false }
        if (!integrityOk8026(ts, nowMs)) {
            refusedIntegrity.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FIRST_SIGHT_REFUSED_INTEGRITY_8026") } catch (_: Throwable) {}
            return false
        }
        val wallet = try { com.lifecyclebot.engine.BotService.status.walletSol } catch (_: Throwable) { 0.0 }
        rollDay(nowMs, wallet)
        val down = synchronized(record) { standsDown8026(record.n, record.mean(), if (record.n > 1.0) kotlin.math.sqrt(record.variance() / record.n) else Double.POSITIVE_INFINITY) }
        val open = granted.keys.mapNotNull { m -> try { com.lifecyclebot.engine.BotService.status.tokens[m]?.position?.takeIf { it.isOpen && !it.isPaperPosition } } catch (_: Throwable) { null } }
        val openCost = open.sumOf { it.costSol.coerceAtLeast(0.0) }
        val nextCost = com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(5.0, try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 })
            .takeIf { it.isFinite() && it > 0.0 } ?: 0.03
        if (down || open.size >= maxOpen8026(maturity) || !cashAllows8026(openCost, nextCost, wallet, lossTodaySol, dayStartSol)) {
            refusedCash.incrementAndGet()
            try { PipelineHealthCollector.labelInc(if (down) "FIRST_SIGHT_STOOD_DOWN_8026" else "FIRST_SIGHT_REFUSED_CASH_8026") } catch (_: Throwable) {}
            return false
        }
        tickets[ts.mint] = nowMs
        granted[ts.mint] = nowMs
        if (tickets.size > 2_000) tickets.entries.removeIf { nowMs - it.value > TICKET_MS }
        if (granted.size > 2_000) granted.entries.removeIf { nowMs - it.value > 24L * 3_600_000L }
        grants.incrementAndGet()
        try {
            com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs)
            PipelineHealthCollector.labelInc("FIRST_SIGHT_GRANTED_8026_${lane.uppercase()}")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "FIRST_SIGHT_GRANTED_8026",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$lane was=${refusal.take(60)} evidence=${"%.2f".format(evidence)} maturity=${"%.3f".format(maturity)} " +
                    "ev=${"%+.1f".format(strong.mean())}%±${"%.1f".format(se)} wr=${(strong.winRate() * 100).toInt()}% n=${strong.n.toInt()} open=${open.size} openCost=${"%.4f".format(openCost)} wallet=${"%.4f".format(wallet)}",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** TraderSizingBridge6444: a probe opens at the route minimum; conviction sizing (equity x authority) grows it. */
    fun sizeMult8026(mint: String, mult: Double): Double {
        val m = if (mult.isFinite() && mult > 0.0) mult else 1.0
        val at = tickets[mint] ?: return m
        return if (System.currentTimeMillis() - at in 0L..TICKET_MS) m.coerceAtMost(1.0) else m
    }

    /** CanonicalFinalizedTradeBus6464: a live close of a probe feeds its own record and the day's loss. */
    fun onClose8026(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
        if (!env.terminal || !env.mode.equals("live", true) || granted.remove(env.mint) == null) return
        if (env.realizedReturnPct.isFinite()) synchronized(record) { record.add(env.realizedReturnPct, env.realizedReturnPct >= 100.0) }
        if (env.realizedPnlSol.isFinite() && env.realizedPnlSol < 0.0) lossTodaySol += -env.realizedPnlSol
    }

    fun statusLine(): String {
        val rec = synchronized(record) { if (record.n < 1.0) "-" else "n${record.n.toInt()}/${"%+.1f".format(record.mean())}%/wr${(record.winRate() * 100).toInt()}%" }
        return "grants=${grants.get()} open=${granted.size} refused[edge=${refusedEdge.get()} integrity=${refusedIntegrity.get()} cash=${refusedCash.get()}] " +
            "record=$rec lossToday=${"%.4f".format(lossTodaySol)} caps[exposure=${(MAX_EXPOSURE * 100).toInt()}% dayLoss=${(DAILY_LOSS_CAP * 100).toInt()}% minWR=${(MIN_WIN_RATE * 100).toInt()}% minEvidence=$MIN_EVIDENCE]"
    }
}
