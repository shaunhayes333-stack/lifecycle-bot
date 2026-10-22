package com.lifecyclebot.engine

/**
 * V5.0.8327 — which held-coin marks are the coin's own chain state.
 *
 * BotService.applyPumpTradeMark7278 stamps every per-trade mark of a held coin: the bonding-curve account on the Helius
 * WS (CurveTicks7968), a PumpPortal / Helius-decoded pump trade (both carry the curve's reserves) and a PumpSwap vault
 * pair (GradTicks8018). The direct curve RPC read is PUMP_CURVE_RPC. Those prints ARE the executable price; the
 * catastrophe contradiction check (BotService.catastropheContradicted7289) already trusted PUMP_CURVE_RPC and
 * JUPITER_QUOTE without a fan-out, and now trusts these streams the same way.
 */
object StopFeed8327 {
    private val CHAIN_SOURCES_8327 = listOf("PUMP_CURVE_RPC", "PUMP_PORTAL_TRADE_WS_7278", "PUMPSWAP_VAULT_WS_8018")

    /** Pure: is [source] (TokenState.lastPriceSource, any case) a chain-state mark of the held coin? */
    fun chainMark8327(source: String?): Boolean {
        val s = source.orEmpty().uppercase()
        return s.isNotBlank() && CHAIN_SOURCES_8327.any { s.contains(it) }
    }
}
