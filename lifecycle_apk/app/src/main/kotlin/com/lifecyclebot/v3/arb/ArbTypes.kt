package com.lifecyclebot.v3.arb

/**
 * ArbScannerAI - Arbitrage Type Definitions
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * NOT classic HFT arbitrage.
 * This is: short-window Solana mispricing capture using existing scanner,
 * scoring, risk, and execution stack.
 * 
 * THREE ARB TYPES:
 * 1. VENUE_LAG - Token discovered on one source before broader market reprices
 * 2. FLOW_IMBALANCE - Order-flow says price should move more than it has
 * 3. PANIC_REVERSION - Fast flush overextends, but structure survives
 */

/**
 * Types of arbitrage opportunities the system can detect.
 */
enum class ArbType {
    /**
     * Token gets discovered in one source before the broader market reprices it.
     * Example: Seen on DEX_BOOSTED, then appears on PUMP_GRADUATE 30s later.
     * The visibility expansion creates a price lag opportunity.
     */
    VENUE_LAG,
    
    /**
     * Order-flow says price should move more than it has.
     * Buy pressure strong, volume rising, momentum positive, but price hasn't caught up.
     * Flow-price divergence creates entry opportunity.
     */
    FLOW_IMBALANCE,
    
    /**
     * Fast flush overextends, but liquidity and structure survive.
     * Sharp dump detected, but no fatal rug evidence, sell pressure exhausted.
     * Bounce odds rise - mean reversion trade.
     */
    PANIC_REVERSION
}

/**
 * Decision bands for arb trades - SEPARATE from normal strategy bands.
 * Arb trades have their own risk/reward profile and sizing rules.
 */
enum class ArbDecisionBand {
    /**
     * Reject - conditions not met for any arb type.
     */
    ARB_REJECT,
    
    /**
     * Watch only - interesting but not actionable yet.
     * Log for data collection, don't trade.
     */
    ARB_WATCH,
    
    /**
     * Micro probe - conditions borderline, use tiny size.
     * 35% of normal arb size.
     */
    ARB_MICRO,
    
    /**
     * Standard arb entry - valid opportunity confirmed.
     * 60% of normal position size (arb trades always smaller).
     */
    ARB_STANDARD,
    
    /**
     * Fast exit only - enter ONLY if exit path is extremely tight.
     * Used for panic reversion where timing is critical.
     * 40% of normal size, aggressive trailing stop.
     */
    ARB_FAST_EXIT_ONLY
}

/**
 * Minimum confidence thresholds per band.
 */
object ArbThresholds {
    const val ARB_STANDARD_MIN_CONF = 45
    const val ARB_MICRO_MIN_CONF = 35
    const val ARB_FAST_EXIT_MIN_CONF = 40
    
    // Liquidity floors by arb type
    const val VENUE_LAG_MIN_LIQUIDITY = 12_000.0
    const val FLOW_IMBALANCE_MIN_LIQUIDITY = 10_000.0
    const val PANIC_REVERSION_MIN_LIQUIDITY = 15_000.0
    
    // Buy pressure minimums
    const val VENUE_LAG_MIN_BUY_PRESSURE = 58.0
    const val FLOW_IMBALANCE_MIN_BUY_PRESSURE = 65.0
    const val PANIC_REVERSION_MIN_BUY_PRESSURE = 50.0
    
    // Max hold times (seconds)
    const val VENUE_LAG_MAX_HOLD_SECONDS = 120
    const val FLOW_IMBALANCE_MAX_HOLD_SECONDS = 90
    const val PANIC_REVERSION_MAX_HOLD_SECONDS = 60
}
