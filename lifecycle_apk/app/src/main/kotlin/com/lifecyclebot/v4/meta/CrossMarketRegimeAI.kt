package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * ===============================================================================
 * CROSS-MARKET REGIME AI — V4 Top-Level Context Brain
 * ===============================================================================
 *
 * This sits ABOVE ModeRouter, not beside it.
 *
 * Reads: SOL/BTC/ETH trend state, tokenized stock index state, volatility,
 *        risk-on/risk-off behavior, liquidity breadth, stablecoin flow,
 *        funding/OI pressure, session context
 *
 * Outputs: globalRiskMode, per-market confidence caps, leverage allowance,
 *          capital bias by market
 *
 * Rule: Your app spans shitcoins, treasury, blue chips, arbitrage, tokenized
 * stocks, leverage. Those should NOT all fire equally under the same regime.
 *
 * ===============================================================================
 */
object CrossMarketRegimeAI {

    private const val TAG = "RegimeAI"

    // Current regime
    // V5.9.362 — boot default is ROTATIONAL (neutral). Previously RISK_ON
    // meant the bot started life with a permanent bullish bias whenever
    // the regime engine had no live price feed (e.g. Perps offline).
    private val currentRegime = AtomicReference(GlobalRiskMode.ROTATIONAL)
    @Volatile private var lastAssessAtMs: Long = 0L

    // Market state tracking
    private val marketTrends = ConcurrentHashMap<String, TrendState>()
    private val volatilityHistory = ConcurrentHashMap<String, MutableList<Double>>()

    data class TrendState(
        val symbol: String,
        val trend: String,          // "UP", "DOWN", "SIDEWAYS"
        val strength: Double,       // 0.0 - 1.0
        val volatility: Double,     // Recent realized vol
        val momentum: Double,       // Rate of change
        val timestamp: Long = System.currentTimeMillis()
    )

    data class RegimeOutput(
        val mode: GlobalRiskMode,
        val confidence: Double,
        val perMarketCaps: Map<String, MarketCap>,
        val leverageAllowance: Double,
        val capitalBias: Map<String, Double>,
        val reasons: List<String>
    )

    // ═══════════════════════════════════════════════════════════════════════
    // UPDATE MARKET STATE
    // ═══════════════════════════════════════════════════════════════════════

    fun updateMarketState(symbol: String, price: Double, change24hPct: Double, volume: Double = 0.0) {
        val trend = when {
            change24hPct > 3.0 -> "UP"
            change24hPct < -3.0 -> "DOWN"
            else -> "SIDEWAYS"
        }
        val strength = (abs(change24hPct) / 10.0).coerceIn(0.0, 1.0)

        val volHistory = volatilityHistory.getOrPut(symbol) { mutableListOf() }
        synchronized(volHistory) {
            volHistory.add(abs(change24hPct))
            if (volHistory.size > 30) volHistory.removeAt(0)
        }

        val recentVol = synchronized(volHistory) { volHistory.average() }

        marketTrends[symbol] = TrendState(
            symbol = symbol,
            trend = trend,
            strength = strength,
            volatility = recentVol,
            momentum = change24hPct
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ASSESS REGIME — Core regime detection
    // ═══════════════════════════════════════════════════════════════════════

    fun assessRegime(): RegimeOutput {
        val btc = marketTrends["BTC"]
        val sol = marketTrends["SOL"]
        val eth = marketTrends["ETH"]
        val spy = marketTrends["SPY"]

        val reasons = mutableListOf<String>()

        // 1. Check for RISK_OFF
        val majorDownCount = listOf(btc, sol, eth).count { it?.trend == "DOWN" }
        val avgMomentum = listOf(btc, sol, eth).mapNotNull { it?.momentum }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgVol = listOf(btc, sol, eth).mapNotNull { it?.volatility }.average().takeIf { !it.isNaN() } ?: 0.0

        val mode = when {
            // RISK_OFF: Most majors down + high vol
            majorDownCount >= 2 && avgVol > 3.0 -> {
                reasons.add("${majorDownCount}/3 majors down, vol=${String.format("%.1f", avgVol)}%")
                GlobalRiskMode.RISK_OFF
            }
            // CHAOTIC: High vol + no direction consensus
            avgVol > 5.0 && majorDownCount == 1 -> {
                reasons.add("High vol ${String.format("%.1f", avgVol)}% with mixed signals")
                GlobalRiskMode.CHAOTIC
            }
            // TRENDING: Most majors same direction + decent strength
            majorDownCount == 0 && avgMomentum > 2.0 -> {
                reasons.add("All majors up, momentum=${String.format("%.1f", avgMomentum)}%")
                GlobalRiskMode.TRENDING
            }
            majorDownCount >= 2 && avgMomentum < -2.0 -> {
                reasons.add("All majors down, momentum=${String.format("%.1f", avgMomentum)}%")
                GlobalRiskMode.TRENDING
            }
            // ROTATIONAL: Some up, some down, moderate vol
            majorDownCount == 1 && avgVol < 3.0 -> {
                reasons.add("Mixed direction, moderate vol — rotation likely")
                GlobalRiskMode.ROTATIONAL
            }
            // MEAN_REVERT: Extended move + declining momentum
            avgVol > 2.0 && avgVol < 4.0 && abs(avgMomentum) > 1.0 -> {
                reasons.add("Extended move may revert, vol=${String.format("%.1f", avgVol)}%")
                GlobalRiskMode.MEAN_REVERT
            }
            // RISK_ON: Low vol, positive bias — only when we actually
            // have data. V5.9.362: if no majors are populated, fall
            // through to ROTATIONAL (neutral) instead of fabricating a
            // bullish bias.
            btc != null || sol != null || eth != null -> {
                reasons.add("Normal conditions, risk-on bias")
                GlobalRiskMode.RISK_ON
            }
            else -> {
                reasons.add("No live price feed yet — neutral stance")
                GlobalRiskMode.ROTATIONAL
            }
        }

        currentRegime.set(mode)
        lastAssessAtMs = System.currentTimeMillis()

        val confidence = when (mode) {
            GlobalRiskMode.RISK_ON -> 0.7
            GlobalRiskMode.RISK_OFF -> 0.8
            GlobalRiskMode.CHAOTIC -> 0.6
            GlobalRiskMode.ROTATIONAL -> 0.5
            GlobalRiskMode.TRENDING -> 0.85
            GlobalRiskMode.MEAN_REVERT -> 0.55
        }

        // Per-market capital bias based on regime
        val capitalBias = when (mode) {
            GlobalRiskMode.RISK_ON -> mapOf("MEME" to 0.3, "STOCKS" to 0.3, "PERPS" to 0.2, "FOREX" to 0.1, "METALS" to 0.05, "COMMODITIES" to 0.05)
            GlobalRiskMode.RISK_OFF -> mapOf("MEME" to -0.5, "STOCKS" to -0.2, "PERPS" to -0.3, "FOREX" to 0.2, "METALS" to 0.5, "COMMODITIES" to 0.3)
            GlobalRiskMode.CHAOTIC -> mapOf("MEME" to -0.8, "STOCKS" to -0.3, "PERPS" to -0.5, "FOREX" to 0.1, "METALS" to 0.3, "COMMODITIES" to 0.2)
            GlobalRiskMode.TRENDING -> mapOf("MEME" to 0.2, "STOCKS" to 0.4, "PERPS" to 0.4, "FOREX" to 0.0, "METALS" to -0.1, "COMMODITIES" to 0.1)
            GlobalRiskMode.ROTATIONAL -> mapOf("MEME" to 0.1, "STOCKS" to 0.3, "PERPS" to 0.1, "FOREX" to 0.2, "METALS" to 0.2, "COMMODITIES" to 0.1)
            GlobalRiskMode.MEAN_REVERT -> mapOf("MEME" to -0.3, "STOCKS" to 0.1, "PERPS" to -0.2, "FOREX" to 0.2, "METALS" to 0.3, "COMMODITIES" to 0.2)
        }

        // Leverage allowance based on regime
        val leverageAllowance = when (mode) {
            GlobalRiskMode.RISK_ON -> 5.0
            GlobalRiskMode.TRENDING -> 3.0
            GlobalRiskMode.ROTATIONAL -> 2.0
            GlobalRiskMode.MEAN_REVERT -> 1.0
            GlobalRiskMode.RISK_OFF -> 0.0
            GlobalRiskMode.CHAOTIC -> 0.0
        }

        // Publish to CrossTalk
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = "GLOBAL",
            confidence = confidence,
            horizonSec = 600,
            regimeTag = mode.name,
            leverageAllowed = leverageAllowance,
            riskFlags = when (mode) {
                GlobalRiskMode.RISK_OFF -> listOf("RISK_OFF", "SUPPRESS_MEME")
                GlobalRiskMode.CHAOTIC -> listOf("CHAOTIC", "NO_LEVERAGE", "SUPPRESS_MEME")
                else -> emptyList()
            }
        ))

        return RegimeOutput(mode, confidence, emptyMap(), leverageAllowance, capitalBias, reasons)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getCurrentRegime(): GlobalRiskMode = currentRegime.get()

    fun getCapitalBias(market: String): Double {
        val snapshot = CrossTalkFusionEngine.getSnapshot()
        return snapshot?.marketBias?.get(market) ?: 0.0
    }

    fun isLeverageAllowed(): Boolean {
        val regime = currentRegime.get()
        return regime != GlobalRiskMode.RISK_OFF && regime != GlobalRiskMode.CHAOTIC
    }

    fun getRegimeFitMultiplier(strategy: String): Double {
        val regime = currentRegime.get()
        return when (strategy) {
            // ShitCoin/Meme family — love RISK_ON, die in RISK_OFF
            "SHITCOIN", "QUALITY", "V3_QUALITY", "EXPRESS", "STANDARD",
            "MOONSHOT_LUNAR", "MOONSHOT_ORBITAL", "MOONSHOT" -> when (regime) {
                GlobalRiskMode.RISK_ON -> 1.2
                GlobalRiskMode.TRENDING -> 1.0
                GlobalRiskMode.ROTATIONAL -> 0.6
                GlobalRiskMode.RISK_OFF -> 0.1
                GlobalRiskMode.CHAOTIC -> 0.0
                GlobalRiskMode.MEAN_REVERT -> 0.3
            }
            // Stable/arb family — thrive in RISK_OFF
            "TREASURY", "SolArbAI" -> when (regime) {
                GlobalRiskMode.RISK_OFF -> 1.3
                GlobalRiskMode.CHAOTIC -> 1.1
                else -> 0.9
            }
            // Blue chip / structured — follow trends
            "BLUE_CHIP", "BLUECHIP", "DIP_HUNTER", "DIPHUNTER",
            "MANIPULATED", "TokenizedStockAI", "CryptoAltAI" -> when (regime) {
                GlobalRiskMode.TRENDING -> 1.3
                GlobalRiskMode.RISK_ON -> 1.1
                GlobalRiskMode.ROTATIONAL -> 1.0
                GlobalRiskMode.RISK_OFF -> 0.5
                GlobalRiskMode.CHAOTIC -> 0.3
                GlobalRiskMode.MEAN_REVERT -> 0.7
            }
            else -> 1.0
        }
    }

    fun clear() {
        currentRegime.set(GlobalRiskMode.ROTATIONAL)
        marketTrends.clear()
        volatilityHistory.clear()
    }

    /** V5.9.362 — diagnostic ping for the BotService regime pulse. */
    fun lastAssessAgeMs(): Long {
        val t = lastAssessAtMs
        return if (t == 0L) Long.MAX_VALUE else System.currentTimeMillis() - t
    }

    fun trackedMarketCount(): Int = marketTrends.size
}
