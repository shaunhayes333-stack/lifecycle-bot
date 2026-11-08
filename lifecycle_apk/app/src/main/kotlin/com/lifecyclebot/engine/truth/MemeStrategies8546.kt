package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8546 §EVENT_STRATEGY_FAMILIES — owner: "take everything you suggested, improve, upgrade or expand on the ideas and
 * implement them".
 *
 * Why a new layer: the evidence (8302 .. 8542 logs) says the meme stack buys strength. 28 of 48 runner-style live buys were
 * tops; at / near the high 0 of 8 live wins in 8542; BREAKOUT / PEAK / DUMP stage records -12 .. -22 % in every runner lane;
 * BASE the only paying stage. The 26 lane brains qualify on hard-coded price / cap / age snapshots, and the evidence is
 * split over so many cells (n ~7 each) that nothing ever proves. So: five event families, each a specific, testable
 * market event read from the coin's own 1-minute bars and its buyers, shared across every lane so evidence accumulates.
 *
 *  PULLBACK_RECLAIM   impulse (+25 % .. +400 % from its base inside 30 bars) -> pullback 15 .. 55 % off the peak -> a higher
 *                     low held for 2 bars -> the last bar reclaims (closes above the low bar's high and the prior close) while
 *                     still >= 12 % under the peak; buyers in control (2-min buy share of SOL >= 55 %, net SOL > 0).
 *  BASE_BREAKOUT_FLOW a tight base (8 .. 20 bars inside a 15 % range) broken by 2 .. 12 % (not extended), confirmed by
 *                     buyers: >= 6 buys in 2 min, buy share >= 60 %, buy size not shrinking (trend >= 0.8).
 *  GRADUATION_RETEST  a pump.fun coin first seen graduated <= 60 min ago that pulled back >= 10 % from its post-migration
 *                     high, held a higher low and reclaimed with buyers in control.
 *  WALLET_CLUSTER     >= 2 distinct tracked proven wallets bought in 5 min, the price <= 30 % over its 15-min low (not a
 *                     chase), and the tape is not being dumped on (2-min buy share >= 50 %).
 *  RANGE_LOW          established coins (liquidity >= $100k, >= 24 h old): a 12-hour 15-minute range 15 .. 60 % wide, price
 *                     in its lowest quarter, the range low not broken in the last 3 bars, net SOL flowing in over 5 min.
 *
 * Every signal opens a PAPER position in MemePaperTrader8544's book (both runtime modes; it never touches a live slot),
 * exits on the family's exit style (runner families on a born-runner lane's levers for young / small coins, the scalp
 * ladder otherwise) and books net of the priced round trip. A family earns LIVE money only through [liveProven8546]: n >= 30
 * paper closes, mean net minus one standard error > 0 and profit factor >= 1.3 — then a current signal on a coin carries
 * that coin's LIVE candidate (LiveEdgeGate7877.edge8114Live8538 "FAMILY_PROVEN_8546"), past the regime and into-high doors.
 * Hard safety (HARD_BLOCK tier) is never traded, paper or live.
 */
object MemeStrategies8546 {
    enum class Family8546 { PULLBACK_RECLAIM, BASE_BREAKOUT_FLOW, GRADUATION_RETEST, WALLET_CLUSTER, RANGE_LOW }

    data class Signal8546(val family: Family8546, val exitLane: String, val why: String)

    private const val PROVEN_MIN_N_8546 = 30
    private const val PROVEN_MIN_PF_8546 = 1.3
    private const val EVAL_GAP_MS_8546 = 15_000L
    private const val PER_TICK_8546 = 120
    private const val SIGNAL_TTL_MS_8546 = 120_000L

    // ── pure detectors (closed 1-minute bars, oldest first) ──

    private fun hi(c: Candle) = if (c.highUsd > 0.0) c.highUsd else c.priceUsd
    private fun lo(c: Candle) = if (c.lowUsd > 0.0) c.lowUsd else c.priceUsd

    /** Pure: impulse -> pullback -> higher low -> reclaim, on [bars]; the why, or null. */
    fun pullbackReclaim8546(bars: List<Candle>): String? {
        val b = bars.takeLast(30).filter { it.priceUsd > 0.0 }
        if (b.size < 8) return null
        val peakIdx = b.indices.maxByOrNull { hi(b[it]) } ?: return null
        if (peakIdx < 2 || peakIdx > b.size - 4) return null
        val base = b.subList(0, peakIdx).minOf { lo(it) }
        val peak = hi(b[peakIdx])
        val impulse = peak / base
        if (!impulse.isFinite() || impulse < 1.25 || impulse > 5.0) return null
        val after = b.subList(peakIdx + 1, b.size)
        val lowIdxInAfter = after.indices.minByOrNull { lo(after[it]) } ?: return null
        val low = lo(after[lowIdxInAfter])
        val depth = 1.0 - low / peak
        if (depth < 0.15 || depth > 0.55) return null
        // the low must be at least 2 bars old and the bars since must hold above it (a higher low)
        if (lowIdxInAfter > after.size - 3) return null
        val since = after.subList(lowIdxInAfter + 1, after.size)
        if (since.any { lo(it) <= low }) return null
        val last = b.last(); val prev = b[b.size - 2]
        if (last.priceUsd <= hi(after[lowIdxInAfter]) || last.priceUsd <= prev.priceUsd) return null
        if (last.priceUsd > peak * 0.88) return null   // still under the high: never bought at the top
        return "impulse=${"%.0f".format((impulse - 1) * 100)}% pullback=${"%.0f".format(depth * 100)}% reclaim=${"%.0f".format((1 - last.priceUsd / peak) * 100)}%offPeak"
    }

    /** Pure: a tight base broken by 2 .. 12 % on the last bar; the why, or null. */
    fun baseBreakout8546(bars: List<Candle>): String? {
        val b = bars.filter { it.priceUsd > 0.0 }
        if (b.size < 9) return null
        val last = b.last()
        for (len in 20 downTo 8) {
            if (b.size < len + 1) continue
            val base = b.subList(b.size - 1 - len, b.size - 1)
            val top = base.maxOf { hi(it) }; val bottom = base.minOf { lo(it) }
            if (bottom <= 0.0 || top / bottom > 1.15) continue
            val brk = last.priceUsd / top
            if (brk < 1.02 || brk > 1.12) continue
            return "base=${len}bars range=${"%.0f".format((top / bottom - 1) * 100)}% break=+${"%.1f".format((brk - 1) * 100)}%"
        }
        return null
    }

    /** A migration starts a new pool. Only its own bars can establish and reclaim the retest low. */
    fun graduationRetest(bars: List<Candle>, graduatedAtMs: Long): String? {
        val post = bars.filter { it.ts >= graduatedAtMs && it.priceUsd.isFinite() && it.priceUsd > 0.0 }
        if (post.size < 4) return null
        val peakIdx = post.indices.maxByOrNull { hi(post[it]) } ?: return null
        if (peakIdx > post.size - 4) return null
        val peak = hi(post[peakIdx])
        val after = post.subList(peakIdx + 1, post.size)
        val lowIdx = after.indices.minByOrNull { lo(after[it]) } ?: return null
        val low = lo(after[lowIdx])
        if (lowIdx > after.size - 3 || low > peak * 0.90) return null
        if (after.subList(lowIdx + 1, after.size).any { lo(it) <= low }) return null
        val close = after.last().priceUsd
        if (close >= peak || close <= hi(after[lowIdx]) || close <= after[after.lastIndex - 1].priceUsd) return null
        return "postMigration peak=$peak retest=$low reclaim=$close"
    }

    /** Pure: established-coin range low on 15-minute [bars]; the why, or null. */
    fun rangeLow8546(bars: List<Candle>): String? {
        val b = bars.takeLast(48).filter { it.priceUsd > 0.0 }
        if (b.size < 16) return null
        val top = b.maxOf { hi(it) }; val bottom = b.minOf { lo(it) }
        val width = top / bottom - 1.0
        if (width < 0.15 || width > 0.60) return null
        val last = b.last().priceUsd
        val pos = (last - bottom) / (top - bottom)
        if (pos > 0.25) return null
        if (b.takeLast(3).any { lo(it) <= bottom }) return null
        return "range=${"%.0f".format(width * 100)}% pos=${"%.0f".format(pos * 100)}%"
    }

    /** Pure: the buyers are in control enough for [family]. */
    fun flowConfirms8546(family: Family8546, f: FlowRead8546.Flow8546): Boolean = when (family) {
        Family8546.PULLBACK_RECLAIM, Family8546.GRADUATION_RETEST ->
            f.known && f.netSol2m > 0.0 && (f.buyShareSol2m >= 0.55 || f.absorbing)
        Family8546.BASE_BREAKOUT_FLOW ->
            f.known && f.buys2m >= 6 && f.buyShareSol2m >= 0.60 && (!f.buySizeTrend.isFinite() || f.buySizeTrend >= 0.8)
        Family8546.WALLET_CLUSTER -> f.smartWallets5m >= 2 && (!f.known || f.buyShareSol2m >= 0.50)
        Family8546.RANGE_LOW -> f.known && f.netSol5m > 0.0
    }

    /** Pure: the exit style (lane whose levers the paper position rides) for [family] on a coin of [mcapUsd]. */
    fun exitLane8546(family: Family8546, mcapUsd: Double): String = when (family) {
        Family8546.RANGE_LOW -> "QUALITY"
        else -> if (mcapUsd.isFinite() && mcapUsd in 1.0..1_000_000.0) "SHITCOIN" else "EXPRESS"
    }

    // ── live evaluation ──

    private val lastEvalAt = ConcurrentHashMap<String, Long>()
    private val lastSignal = ConcurrentHashMap<String, Map<Family8546, Long>>()
    private val fired = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    /** Bounded funnel counts: distinguish no chart setup, no executed buy/sell flow, and a genuine flow-confirmed entry. */
    private val probe8644 = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private fun note8644(key: String) { probe8644.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet() }
    private fun count8644(key: String) = probe8644[key]?.get() ?: 0L
    @Volatile private var cursor = 0

    /** Every signal [ts] shows now (no side effects). */
    private fun signals8546(ts: TokenState, nowMs: Long): List<Signal8546> {
        val out = ArrayList<Signal8546>(2)
        val m1 = try { MultiTimeframeCharts8521.snapshot8521(ts, MultiTimeframeCharts8521.Frame8521.M1, nowMs) }
            catch (_: Throwable) { note8644("M1_ERROR"); emptyList() }
        val flow = FlowRead8546.read8546(ts.mint, nowMs)
        note8644(if (m1.size >= 9) "M1_READY" else if (m1.isEmpty()) "M1_EMPTY" else "M1_SHORT")
        note8644(if (flow.known) "FLOW_KNOWN" else "FLOW_UNKNOWN")
        val mcap = ts.lastMcap
        fun add(f: Family8546, why: String?) {
            if (why == null) return
            note8644("RAW_${f.name}")
            if (flowConfirms8546(f, flow)) out += Signal8546(f, exitLane8546(f, mcap), why)
            else note8644("FLOW_REFUSED_${f.name}")
        }
        add(Family8546.PULLBACK_RECLAIM, pullbackReclaim8546(m1))
        add(Family8546.BASE_BREAKOUT_FLOW, baseBreakout8546(m1))
        val gradAt = try { com.lifecyclebot.network.PumpCurveKeys7269.graduatedAtMs8546(ts.mint) } catch (_: Throwable) { null }
        if (gradAt != null && nowMs - gradAt in 0L..3_600_000L)
            add(Family8546.GRADUATION_RETEST, graduationRetest(m1, gradAt)?.let { "graduated ${(nowMs - gradAt) / 60_000L}m ago $it" })
        if (flow.smartWallets5m >= 2) {
            val low15 = m1.takeLast(15).filter { it.priceUsd > 0.0 }.minOfOrNull { lo(it) }
            val px = ts.lastPrice
            if (low15 != null && px > 0.0 && px <= low15 * 1.30) add(Family8546.WALLET_CLUSTER, "wallets=${flow.smartWallets5m} over15mLow=${"%.0f".format((px / low15 - 1) * 100)}%")
        }
        val ageH = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null }?.div(60.0)
        if (ts.lastLiquidityUsd >= 100_000.0 && ageH != null && ageH >= 24.0) {
            val m15 = try { MultiTimeframeCharts8521.snapshot8521(ts, MultiTimeframeCharts8521.Frame8521.M15, nowMs) } catch (_: Throwable) { emptyList() }
            add(Family8546.RANGE_LOW, rangeLow8546(m15))
        }
        return out
    }

    /** BotService loop: scan a round-robin slice of [tokens]; every signal opens a paper position in the book. */
    fun tick8546(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        val all = tokens.values.toList()
        if (all.isEmpty()) return
        var done = 0
        var i = cursor % all.size
        var visited = 0
        while (done < PER_TICK_8546 && visited < all.size) {
            val ts = all[i]; i = (i + 1) % all.size; visited++
            if (nowMs - (lastEvalAt[ts.mint] ?: 0L) < EVAL_GAP_MS_8546) continue
            if (!eligible8546(ts, nowMs)) continue
            note8644("EVALUATED")
            lastEvalAt[ts.mint] = nowMs
            done++
            val sigs = try { signals8546(ts, nowMs) } catch (_: Throwable) { note8644("SIGNALS_ERROR"); emptyList() }
            if (sigs.isNotEmpty()) lastSignal.compute(ts.mint) { _, old ->
                old.orEmpty().filterValues { nowMs - it in 0L..SIGNAL_TTL_MS_8546 } + sigs.associate { it.family to nowMs }
            }
            for (s in sigs) {
                fired.computeIfAbsent(s.family.name) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
                try { PipelineHealthCollector.labelInc("MEME_FAMILY_SIGNAL_8546_${s.family}") } catch (_: Throwable) {}
                try { MemePaperTrader8544.openFamily8546(ts, s, nowMs) } catch (_: Throwable) {}
            }
        }
        cursor = i
        if (lastEvalAt.size > 6_000) lastEvalAt.entries.removeIf { nowMs - it.value > 600_000L }
        if (lastSignal.size > 3_000) lastSignal.entries.removeIf { it.value.values.none { at -> nowMs - at in 0L..SIGNAL_TTL_MS_8546 } }
    }

    private fun eligible8546(ts: TokenState, nowMs: Long): Boolean {
        if (ts.mint.isBlank() || !(ts.lastPrice > 0.0) || nowMs - ts.lastPriceUpdate > 60_000L) {
            note8644("SKIP_PRICE"); return false
        }
        if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) {
            note8644("SKIP_HARD_BLOCK"); return false
        }
        if (!(ts.lastLiquidityUsd >= 3_000.0)) { note8644("SKIP_LIQUIDITY"); return false }
        val solana = try { com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint) }
            catch (_: Throwable) { false }
        if (!solana) note8644("SKIP_NON_SOLANA")
        return solana
    }

    /** Pure: may a family's paper record carry LIVE money? */
    fun provenRecord8546(n: Int, meanPct: Double, sePct: Double, profitFactor: Double): Boolean =
        n >= PROVEN_MIN_N_8546 && meanPct.isFinite() && sePct.isFinite() && meanPct - sePct > 0.0 &&
            profitFactor >= PROVEN_MIN_PF_8546

    /** LiveEdgeGate7877: the proven family that signalled on [mint] in the last two minutes, else null. */
    fun liveProven8546(mint: String, nowMs: Long = System.currentTimeMillis()): String? {
        val family = lastSignal[mint]?.entries?.filter {
            nowMs - it.value in 0L..SIGNAL_TTL_MS_8546 && MemePaperTrader8544.familyProven8546(it.key.name)
        }?.maxByOrNull { it.value }?.key ?: return null
        return "FAMILY_PROVEN_8546:${family.name}"
    }

    fun statusLine8546(): String =
        "memeFamilies8546[signals{" + Family8546.values().joinToString(",") { "${it.name}=${fired[it.name]?.get() ?: 0}" } +
            "} liveProven{" + Family8546.values().filter { MemePaperTrader8544.familyProven8546(it.name) }.joinToString(",").ifBlank { "none yet" } +
            "} funnel8644{evaluated=${count8644("EVALUATED")} skipped=price:${count8644("SKIP_PRICE")}/safety:${count8644("SKIP_HARD_BLOCK")}/liq:${count8644("SKIP_LIQUIDITY")}/chain:${count8644("SKIP_NON_SOLANA")} " +
            "m1=empty:${count8644("M1_EMPTY")}/short:${count8644("M1_SHORT")}/ready:${count8644("M1_READY")}/error:${count8644("M1_ERROR")} " +
            "flow=known:${count8644("FLOW_KNOWN")}/unknown:${count8644("FLOW_UNKNOWN")} raw{" +
            Family8546.values().joinToString(",") { "${it.name}=${count8644("RAW_${it.name}")}/${count8644("FLOW_REFUSED_${it.name}")}noFlow" } +
            "} errors=${count8644("SIGNALS_ERROR")}}] " +
            MemeRegime8546.statusLine8546()
}
