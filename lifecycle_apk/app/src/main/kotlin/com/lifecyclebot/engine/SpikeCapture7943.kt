package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7943 — spikes are sold INTO, on the print that shows them.
 *
 * 5.0.7941 live: foff peaked +276%, LAMBO +105%, WcErhi18 +71% within one to two
 * minutes of entry and all three closed at about 0%. Every trailing lock in the
 * book sells on the way DOWN, and these spikes were one or two curve prints: the
 * next mark the monitors saw was back at entry. The only way to bank a spike is to
 * sell on the trade print that carries it. Every held-curve trade mark (PumpPortal
 * and Helius) comes through here first; a mark at or above a tier sells that
 * tier's fraction at once (off the socket thread). The remainder keeps the
 * fluid locks and the runner floor, so a real 500% runner still runs.
 */
object SpikeCapture7943 {
    /** (gross % at or above which the tier fires, fraction of the CURRENT holding sold). */
    val TIERS: List<Pair<Double, Double>> = listOf(40.0 to 0.60, 120.0 to 0.70, 400.0 to 0.70)

    private val firedTiers = ConcurrentHashMap<String, Int>()
    private val fired = java.util.concurrent.atomic.AtomicLong(0)
    /** V5.0.7944 — a tier whose sell did not land is re-armed, at most this many times per position. */
    private const val MAX_REARMS_7944 = 4
    private val rearms = ConcurrentHashMap<String, Int>()
    private val rearmed = java.util.concurrent.atomic.AtomicLong(0)
    /** V5.0.7944 — a non-curve mark must be this fresh to sell on (a stale print is not a spike). */
    private const val FRESH_MARK_MS_7944 = 5_000L

    /**
     * V5.0.7944 — the sell for [tier] did not land (another sell held the lock, no
     * route, slippage): the tier fires again on the next print still above it.
     * Returns true when re-armed.
     */
    fun rearm(ts: TokenState, tier: Int): Boolean {
        val key = "${ts.mint}|${ts.position.entryTime}"
        val n = rearms.merge(key, 1, Int::plus) ?: 1
        if (n > MAX_REARMS_7944 || !ts.position.isOpen) return false
        firedTiers.compute(key) { _, done -> if (done != null && done >= tier) tier - 1 else done }
        rearmed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_REARMED_7944") } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.7944 — the rapid monitor's 500 ms read of every open position. Spikes on
     * graduated / DEX tokens never come through the curve trade feed; this sells them
     * on the freshest mark the row holds. Returns the mark read (the monitor's price).
     */
    fun rapidMark(ts: TokenState, nowMs: Long, sell: (TokenState, Double, String) -> Unit): Double? {
        val px = ts.lastPrice.takeIf { it > 0 } ?: ts.history.lastOrNull()?.priceUsd
        if (px != null && px > 0.0 && ts.lastPriceUpdate > 0L && nowMs - ts.lastPriceUpdate <= FRESH_MARK_MS_7944) {
            try { onMark(ts, px, sell) } catch (_: Throwable) {}
        }
        return px
    }

    /** Pure: the highest tier index (1-based) this gross gain reaches, or 0. */
    fun tierReached(grossPct: Double): Int {
        if (!grossPct.isFinite()) return 0
        var t = 0
        TIERS.forEachIndexed { i, (pct, _) -> if (grossPct >= pct) t = i + 1 }
        return t
    }

    /**
     * Called with every fresh trade mark of a held position. Returns the tier fired
     * (and calls [sell] with the fraction and reason), or 0 when nothing fires.
     */
    fun onMark(ts: TokenState, priceUsd: Double, sell: (TokenState, Double, String) -> Unit): Int {
        val pos = ts.position
        if (!pos.isOpen || pos.entryPrice <= 0.0 || !priceUsd.isFinite() || priceUsd <= 0.0) return 0
        val gross = (priceUsd / pos.entryPrice - 1.0) * 100.0
        // A basis in the wrong units reads as a 100,000% "spike"; that is not a price.
        if (gross > 100_000.0) return 0
        val reached = tierReached(gross)
        if (reached == 0) return 0
        val key = "${ts.mint}|${pos.entryTime}"
        val done = firedTiers[key] ?: 0
        if (reached <= done) return 0
        if (firedTiers.size > 2_000) firedTiers.clear()
        firedTiers[key] = reached
        val frac = TIERS[reached - 1].second
        fired.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("SPIKE_CAPTURE_7943_TIER$reached")
            ForensicLogger.lifecycle(
                "SPIKE_CAPTURE_7943",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} gross=${"%.1f".format(gross)}% tier=$reached sell=${(frac * 100).toInt()}% action=sell_into_spike",
            )
        } catch (_: Throwable) {}
        sell(ts, frac, "SPIKE_CAPTURE_7943_T${reached}_${gross.toInt()}PCT")
        if (rearms.size > 2_000) rearms.clear()
        return reached
    }

    fun statusLine(): String = "fired=${fired.get()} rearmed7944=${rearmed.get()} tiers=${TIERS.joinToString(",") { "+${it.first.toInt()}%:${(it.second * 100).toInt()}%" }}"
}

/**
 * V5.0.7943 — pump.fun Mayhem Mode coins (created with create_v2 in mayhem mode:
 * 2 billion supply and a protocol agent trading the curve for its first 24 h) are
 * not organic demand. Detected by supply: the on-chain supply when resolved, else
 * the supply implied by market cap / price. Refused for LIVE entry.
 */
object MayhemMode7943 {
    private const val MAYHEM_SUPPLY_LO = 1.6e9
    private const val MAYHEM_SUPPLY_HI = 2.6e9
    private val refused = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure: is this supply a mayhem-mode pump.fun supply? */
    fun mayhemSupply(supply: Double): Boolean = supply.isFinite() && supply in MAYHEM_SUPPLY_LO..MAYHEM_SUPPLY_HI

    private fun isMayhem(ts: TokenState): Boolean {
        if (!ts.mint.endsWith("pump")) return false
        val onChain = try { com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.supplyOf7075(ts.mint) } catch (_: Throwable) { 0.0 }
        if (onChain <= 0.0) try { com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.requestAsync7075(ts.mint) } catch (_: Throwable) {}
        val implied = if (ts.lastMcap > 0.0 && ts.lastPrice > 0.0) ts.lastMcap / ts.lastPrice else 0.0
        val supply = if (onChain > 0.0) onChain else implied
        return mayhemSupply(supply)
    }

    /** LiveEdgeGate7877: the live refusal for a mayhem coin, or null. */
    fun liveRefusal(ts: TokenState): String? {
        if (!isMayhem(ts)) return null
        refused.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MAYHEM_MODE_REFUSED_7943") } catch (_: Throwable) {}
        return "MAYHEM_MODE_7943"
    }

    fun statusLine(): String = "refused=${refused.get()}"
}
