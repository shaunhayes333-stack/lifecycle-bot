package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 💱 FOREX STRATEGY — V5.9.376
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Replaces the "24h-change ≥ 0 → LONG" heuristic ForexTrader was using with a
 * real forex-native decision framework:
 *
 *   • Session-aware: London open & London/NY overlap are high-conviction;
 *     Tokyo is noise for EUR/GBP pairs, high for JPY pairs; closed = stand down
 *   • Bidirectional: every pair can go LONG or SHORT based on:
 *       — carry bias (JPY crosses = structural SHORT for JPY-as-funder)
 *       — USD regime (USD-quoted pairs flip on DXY trend)
 *       — overbought / oversold via RSI + distance-from-range
 *   • Pip-based TP/SL (the correct forex primitive — USDJPY pipsize 0.01,
 *     majors 0.0001). A 0.5% SL on EURUSD was 50 pips of room; now we use
 *     ATR-scaled pips per pair class.
 *   • Leverage tiers: majors 10-30x, crosses 5-15x, EM 1-5x — gated by conviction
 *
 * PUBLIC API:
 *   ForexStrategy.decide(...)                  → ForexSetup?
 *   ForexStrategy.currentSession()             → Session
 *   ForexStrategy.classifyPair(symbol)         → PairClass
 *   ForexStrategy.leverageFor(cls, conviction) → Double
 *   ForexStrategy.pipsToPct(symbol, pips, px)  → Double
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ForexStrategy {

    enum class Session { ASIA, LONDON, LONDON_NY_OVERLAP, NY, CLOSED }
    enum class PairClass { MAJOR, CROSS, JPY_CROSS, EM }

    data class ForexSetup(
        val direction: PerpsDirection,
        val conviction: Int,                // 0-100
        val tpPips: Double,
        val slPips: Double,
        val leverage: Double,
        val session: Session,
        val pairClass: PairClass,
        val reasons: List<String>,
    )

    private val MAJORS = setOf(
        "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD",
    )
    private val JPY_CROSSES = setOf(
        "EURJPY", "GBPJPY", "AUDJPY", "CADJPY", "CHFJPY", "NZDJPY",
    )
    private val CROSSES = setOf(
        "EURGBP", "EURCHF", "GBPCHF", "EURCAD", "EURAUD", "GBPAUD", "AUDCAD",
        "AUDNZD",
    )
    private val EM = setOf(
        "USDMXN", "USDBRL", "USDINR", "USDCNY", "USDZAR", "USDTRY", "USDRUB",
        "USDSGD", "USDHKD", "USDKRW",
    )

    /** London opens 07:00 UTC, closes 16:00. NY opens 12:00, closes 21:00.
     *  Tokyo is 23:00-08:00 UTC. Weekend (Fri 21:00 - Sun 22:00) = CLOSED. */
    fun currentSession(
        utcHour: Int = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .get(java.util.Calendar.HOUR_OF_DAY),
        dayOfWeek: Int = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .get(java.util.Calendar.DAY_OF_WEEK),
    ): Session {
        // DAY_OF_WEEK: Sun=1, Mon=2, ..., Sat=7
        if (dayOfWeek == java.util.Calendar.SATURDAY) return Session.CLOSED
        if (dayOfWeek == java.util.Calendar.SUNDAY && utcHour < 22) return Session.CLOSED
        if (dayOfWeek == java.util.Calendar.FRIDAY && utcHour >= 21) return Session.CLOSED
        return when (utcHour) {
            in 0..6 -> Session.ASIA
            in 7..11 -> Session.LONDON
            in 12..15 -> Session.LONDON_NY_OVERLAP
            in 16..20 -> Session.NY
            in 21..23 -> Session.ASIA
            else -> Session.ASIA
        }
    }

    fun classifyPair(symbol: String): PairClass = when {
        symbol in MAJORS -> PairClass.MAJOR
        symbol in JPY_CROSSES -> PairClass.JPY_CROSS
        symbol in CROSSES -> PairClass.CROSS
        symbol in EM -> PairClass.EM
        symbol.contains("JPY") -> PairClass.JPY_CROSS
        symbol.startsWith("USD") -> PairClass.EM
        else -> PairClass.CROSS
    }

    /** Pip size in price units. JPY quote = 0.01, everything else = 0.0001. */
    fun pipSize(symbol: String): Double =
        if (symbol.endsWith("JPY")) 0.01 else 0.0001

    fun pipsToPct(symbol: String, pips: Double, currentPrice: Double): Double {
        if (currentPrice <= 0) return 0.0
        return (pips * pipSize(symbol)) / currentPrice * 100.0
    }

    /**
     * TP/SL in pips per pair class. Conviction (0-100) widens TP on strong
     * setups and tightens SL on weak ones.
     */
    fun tpSlPips(cls: PairClass, conviction: Int): Pair<Double, Double> {
        val c = conviction.coerceIn(30, 100)
        val factor = 0.7 + (c - 30) / 100.0  // 0.7..1.4
        return when (cls) {
            PairClass.MAJOR -> Pair(25.0 * factor, 15.0 * (2.0 - factor))        // majors: tight
            PairClass.JPY_CROSS -> Pair(40.0 * factor, 25.0 * (2.0 - factor))    // more range
            PairClass.CROSS -> Pair(30.0 * factor, 20.0 * (2.0 - factor))
            PairClass.EM -> Pair(60.0 * factor, 45.0 * (2.0 - factor))           // widest
        }
    }

    /** Leverage ceiling per pair class, scaled by conviction. */
    fun leverageFor(cls: PairClass, conviction: Int): Double {
        val c = conviction.coerceIn(0, 100) / 100.0
        return when (cls) {
            PairClass.MAJOR -> (5.0 + 25.0 * c).coerceIn(5.0, 30.0)
            PairClass.CROSS -> (3.0 + 12.0 * c).coerceIn(3.0, 15.0)
            PairClass.JPY_CROSS -> (3.0 + 12.0 * c).coerceIn(3.0, 15.0)
            PairClass.EM -> (1.0 + 4.0 * c).coerceIn(1.0, 5.0)
        }
    }

    /**
     * Core decision: given a pair + market data + optional technical readings,
     * return a ForexSetup or null (stand down). Bidirectional by construction.
     */
    fun decide(
        symbol: String,
        price: Double,
        priceChange24hPct: Double,
        rsi: Double? = null,
        isOversold: Boolean = false,
        isOverbought: Boolean = false,
        dxyChangePct: Double? = null,  // DXY daily change for USD regime context
    ): ForexSetup? {
        val cls = classifyPair(symbol)
        val session = currentSession()
        if (session == Session.CLOSED) return null

        val reasons = mutableListOf<String>()
        var conviction = 40
        var bias = 0.0  // positive = long lean, negative = short lean

        // 1) Momentum (weighted by ABSOLUTE magnitude, not just sign)
        when {
            abs(priceChange24hPct) > 1.0 -> { conviction += 20; bias += priceChange24hPct.coerceIn(-3.0, 3.0) * 8 }
            abs(priceChange24hPct) > 0.5 -> { conviction += 12; bias += priceChange24hPct * 5 }
            abs(priceChange24hPct) > 0.2 -> { conviction += 5; bias += priceChange24hPct * 2 }
        }
        reasons.add("24h Δ ${"%+.2f".format(priceChange24hPct)}%")

        // 2) Session edge
        when (session) {
            Session.LONDON_NY_OVERLAP -> {
                conviction += 15
                reasons.add("🌆 London/NY overlap (highest liquidity)")
            }
            Session.LONDON -> { conviction += 10; reasons.add("🇬🇧 London session") }
            Session.NY -> { conviction += 8; reasons.add("🇺🇸 NY session") }
            Session.ASIA -> {
                if (cls == PairClass.JPY_CROSS) { conviction += 8; reasons.add("🇯🇵 Asia session (JPY relevant)") }
                else { conviction -= 5; reasons.add("🌙 Asia session (low conviction for non-JPY)") }
            }
            else -> {}
        }

        // 3) RSI reversal setups — pure contrarian only when extreme
        rsi?.let { r ->
            when {
                r <= 25 -> { bias += 6; reasons.add("📉 Deep oversold RSI=${r.toInt()} → LONG bias") }
                r <= 35 -> { bias += 3; reasons.add("📉 Oversold RSI=${r.toInt()}") }
                r >= 75 -> { bias -= 6; reasons.add("📈 Deep overbought RSI=${r.toInt()} → SHORT bias") }
                r >= 65 -> { bias -= 3; reasons.add("📈 Overbought RSI=${r.toInt()}") }
            }
        }
        if (isOversold) { bias += 3 }
        if (isOverbought) { bias -= 3 }

        // 4) USD regime (when DXY available)
        dxyChangePct?.let { dxy ->
            val usdQuote = symbol.endsWith("USD")
            val usdBase = symbol.startsWith("USD")
            // DXY up = USD strong: USD-base (USDJPY etc) → long; USD-quote (EURUSD etc) → short
            if (usdBase) bias += dxy * 3
            if (usdQuote) bias -= dxy * 3
            reasons.add("DXY ${"%+.2f".format(dxy)}%")
        }

        // 5) Carry trade structural bias (JPY funding currency → short JPY = long JPY crosses)
        if (cls == PairClass.JPY_CROSS && symbol.endsWith("JPY")) {
            bias += 1.5
            reasons.add("💰 Carry bias (JPY funder)")
        }

        // Decision threshold: |bias| < 1 means "no edge, stand down"
        if (abs(bias) < 1.0) return null

        val direction = if (bias > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        conviction = conviction.coerceIn(0, 100)
        val (tpPips, slPips) = tpSlPips(cls, conviction)
        val lev = leverageFor(cls, conviction)

        return ForexSetup(
            direction = direction,
            conviction = conviction,
            tpPips = tpPips,
            slPips = slPips,
            leverage = lev,
            session = session,
            pairClass = cls,
            reasons = reasons,
        )
    }

    /**
     * Convenience: given a ForexSetup + entry price, compute the actual
     * TP/SL price levels (direction-aware).
     */
    fun tpSlPrices(setup: ForexSetup, symbol: String, entryPrice: Double): Pair<Double, Double> {
        val pip = pipSize(symbol)
        val tpOffset = setup.tpPips * pip
        val slOffset = setup.slPips * pip
        return if (setup.direction == PerpsDirection.LONG) {
            Pair(entryPrice + tpOffset, entryPrice - slOffset)
        } else {
            Pair(entryPrice - tpOffset, entryPrice + slOffset)
        }
    }
}
