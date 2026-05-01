package com.lifecyclebot.engine

/**
 * V5.9.409 — LANE CLASSIFIER
 *
 * Single source of truth answering "what asset class is this?".
 * Before this file existed, different sub-systems answered differently:
 *   - TradeAuthorizer used ExecutionBook
 *   - FDG used ModeSpecificGates.TradingModeTag (but only for the global
 *     bot mode, never for the per-token mode)
 *   - AICrossTalk didn't ask at all
 *
 * Result: the meme lane bled into the stocks/alts/perps learning loop
 * and vice-versa. The user's original 50 %+ meme win-rate collapsed as
 * new asset classes polluted the shared caches and weights.
 *
 * All meme classification is intentionally centralized here so that
 * meme learning (trust, patterns, cross-talk) can be kept isolated from
 * the rest of the universe while still participating in the shared
 * sentient brain / neural network.
 */
object LaneTag {

    enum class Lane { MEME, BLUECHIP, ALT, STOCK, PERP, FOREX, METAL, COMMODITY, CORE, UNKNOWN }

    /** Classify from a per-token tradingMode string (set by the routing logic). */
    fun fromTradingMode(tradingMode: String?): Lane {
        if (tradingMode.isNullOrBlank()) return Lane.UNKNOWN
        val m = tradingMode.trim().uppercase()
        return when {
            m.startsWith("MOONSHOT")       -> Lane.MEME
            m == "SHITCOIN"                 -> Lane.MEME
            m == "MANIPULATED"              -> Lane.MEME
            m == "CULT"                     -> Lane.MEME
            m == "NARRATIVE"                -> Lane.MEME
            m == "MICRO_CAP"                -> Lane.MEME
            m == "PUMP_SNIPER"              -> Lane.MEME
            m == "MEME"                     -> Lane.MEME
            m == "BLUECHIP" || m == "BLUE_CHIP" -> Lane.BLUECHIP
            m == "LONG_HOLD"                -> Lane.BLUECHIP
            m.contains("STOCK")             -> Lane.STOCK
            m.contains("PERP") || m.contains("LEVERAGE") -> Lane.PERP
            m.contains("FOREX")             -> Lane.FOREX
            m.contains("METAL") || m == "GOLD" || m == "SILVER" -> Lane.METAL
            m.contains("COMMOD")            -> Lane.COMMODITY
            m.contains("ALT")               -> Lane.ALT
            m == "CORE" || m == "STANDARD"  -> Lane.CORE
            else                            -> Lane.UNKNOWN
        }
    }

    /** Is this lane a "meme-family" lane? */
    fun isMeme(tradingMode: String?): Boolean = fromTradingMode(tradingMode) == Lane.MEME

    /**
     * Cross-talk weight profile per lane.
     * Defaults lean toward your proven pre-markets meme-dominant settings;
     * non-meme lanes get balanced weights.
     */
    data class CrossTalkWeights(
        val whale: Double,
        val momentum: Double,
        val liquidity: Double,
        val narrative: Double,
        val regime: Double,
    )

    fun crossTalkWeights(lane: Lane): CrossTalkWeights = when (lane) {
        // Memes key off whales + momentum + narrative (your proven config).
        Lane.MEME      -> CrossTalkWeights(whale = 1.30, momentum = 1.30, liquidity = 0.85, narrative = 1.45, regime = 0.85)
        // Bluechips care about liquidity + regime, narrative is noise.
        Lane.BLUECHIP  -> CrossTalkWeights(whale = 1.10, momentum = 1.00, liquidity = 1.25, narrative = 0.60, regime = 1.20)
        // Stocks follow market regime and lead-lag, not meme narrative.
        Lane.STOCK     -> CrossTalkWeights(whale = 0.85, momentum = 1.00, liquidity = 1.15, narrative = 0.40, regime = 1.35)
        // Perps — momentum > everything, regime matters, narrative barely.
        Lane.PERP      -> CrossTalkWeights(whale = 0.90, momentum = 1.40, liquidity = 1.05, narrative = 0.50, regime = 1.20)
        Lane.FOREX     -> CrossTalkWeights(whale = 0.70, momentum = 1.10, liquidity = 1.20, narrative = 0.35, regime = 1.30)
        Lane.METAL,
        Lane.COMMODITY -> CrossTalkWeights(whale = 0.80, momentum = 1.00, liquidity = 1.20, narrative = 0.40, regime = 1.30)
        Lane.ALT       -> CrossTalkWeights(whale = 1.10, momentum = 1.15, liquidity = 1.05, narrative = 0.90, regime = 1.05)
        else           -> CrossTalkWeights(whale = 1.00, momentum = 1.00, liquidity = 1.00, narrative = 1.00, regime = 1.00)
    }

    fun crossTalkWeights(tradingMode: String?): CrossTalkWeights =
        crossTalkWeights(fromTradingMode(tradingMode))
}
