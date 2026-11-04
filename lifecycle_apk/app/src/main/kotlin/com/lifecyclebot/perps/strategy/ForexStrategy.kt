package com.lifecyclebot.perps.strategy

import com.lifecyclebot.perps.PerpsDirection
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 💱 FOREX STRATEGY — V5.9.376 · V5.0.8535 §C14/§C16
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
 * 8535 §C16 — sessions were static UTC hour bands (21:00–23:00 filed as ASIA before Tokyo opens, London fixed at
 * 07:00 UTC through the DST switch, the week closing at a UTC hour). They are now read from the exchange calendars in
 * their own zones: Tokyo 09:00–18:00 JST, London 08:00–17:00 Europe/London, New York 08:00–17:00 America/New_York;
 * the week runs Sunday 17:00 to Friday 17:00 New York. The Sydney-only window before Tokyo is OFF_HOURS (thin), and
 * EM pairs stand down outside London / New York. There is no economic-event calendar source in this build; that is
 * stated in the reasons rather than assumed away.
 *
 * 8535 §C14 — the setup is decided once, at analysis, and carried to the fill ([SETUP_TTL_MS_8535]); the trader no
 * longer re-decides without the RSI inputs to borrow a second thesis' stops.
 *
 * PUBLIC API:
 *   ForexStrategy.decide(...)                  → ForexSetup?
 *   ForexStrategy.currentSession(nowMs)        → Session
 *   ForexStrategy.classifyPair(symbol)         → PairClass
 *   ForexStrategy.leverageFor(cls, conviction) → Double
 *   ForexStrategy.pipsToPct(symbol, pips, px)  → Double
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ForexStrategy {

    enum class Session { ASIA, LONDON, LONDON_NY_OVERLAP, NY, OFF_HOURS, CLOSED }
    enum class PairClass { MAJOR, CROSS, JPY_CROSS, EM }

    /** A setup decided at analysis is executable for this long; after that the entry is cancelled, not re-decided. */
    const val SETUP_TTL_MS_8535 = 15 * 60_000L

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

    private val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
    private val LONDON: ZoneId = ZoneId.of("Europe/London")
    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    private fun open(z: ZonedDateTime, openHour: Int, closeHour: Int): Boolean =
        z.dayOfWeek != DayOfWeek.SATURDAY && z.dayOfWeek != DayOfWeek.SUNDAY && z.hour >= openHour && z.hour < closeHour

    /**
     * The session at [nowMs], from the exchange calendars in their own zones (DST follows each zone).
     * CLOSED from Friday 17:00 to Sunday 17:00 New York; OFF_HOURS when no major centre is open (the Sydney-only window
     * before Tokyo, and the gap after New York).
     */
    fun currentSession(nowMs: Long = System.currentTimeMillis()): Session {
        val instant = Instant.ofEpochMilli(nowMs)
        val ny = ZonedDateTime.ofInstant(instant, NEW_YORK)
        val weekendClosed = when (ny.dayOfWeek) {
            DayOfWeek.SATURDAY -> true
            DayOfWeek.FRIDAY -> ny.hour >= 17
            DayOfWeek.SUNDAY -> ny.hour < 17
            else -> false
        }
        if (weekendClosed) return Session.CLOSED
        val london = ZonedDateTime.ofInstant(instant, LONDON)
        val tokyo = ZonedDateTime.ofInstant(instant, TOKYO)
        val londonOpen = open(london, 8, 17)
        val nyOpen = open(ny, 8, 17)
        val tokyoOpen = open(tokyo, 9, 18)
        return when {
            londonOpen && nyOpen -> Session.LONDON_NY_OVERLAP
            londonOpen -> Session.LONDON
            nyOpen -> Session.NY
            tokyoOpen -> Session.ASIA
            else -> Session.OFF_HOURS
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
        nowMs: Long = System.currentTimeMillis(),
    ): ForexSetup? {
        val cls = classifyPair(symbol)
        val session = currentSession(nowMs)
        if (session == Session.CLOSED) return null
        // 8535 §C16 — EM pairs have no liquid market outside London / New York.
        if (cls == PairClass.EM && session != Session.LONDON && session != Session.NY && session != Session.LONDON_NY_OVERLAP) return null

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
                if (cls == PairClass.JPY_CROSS) { conviction += 8; reasons.add("🇯🇵 Tokyo session (JPY relevant)") }
                else { conviction -= 5; reasons.add("🌙 Tokyo session (low conviction for non-JPY)") }
            }
            Session.OFF_HOURS -> {
                // Sydney-only / post-NY: thin books, wide spreads, no centre open.
                conviction -= if (cls == PairClass.JPY_CROSS) 3 else 10
                reasons.add("🌑 Off-hours (no major centre open)")
            }
            Session.CLOSED -> {}
        }
        reasons.add("📅 no economic-event calendar source in this build")

        // 3) RSI reversal setups — pure contrarian only when extreme
        rsi?.let { r ->
            when {
                r <= 25 -> { bias += 6; reasons.add("📉 Deep oversold RSI=${r.toInt()} → LONG bias") }
                r <= 35 -> { bias += 3; reasons.add("📉 Oversold RSI=${r.toInt()}") }
                r >= 75 -> { bias -= 6; reasons.add("📈 Deep overbought RSI=${r.toInt()} → SHORT bias") }
                r >= 65 -> { bias -= 3; reasons.add("📈 Overbought RSI=${r.toInt()}") }
                else -> Unit
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
