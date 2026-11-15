package com.lifecyclebot.perps

import android.content.Context

/**
 * V5.0.8797 — owner: "also retire what we cant trade". The Solana-side markets traders no longer open anything:
 *  - FOREX: no Solana route but EURC (every other pair had nowhere to trade live).
 *  - COMMODITIES: no Solana token for oil, gas or crops; commodities trade as HIP-3 perps on the Hyperliquid desk.
 *  - STOCKS: the legacy TokenizedStockTrader (record n48 -3.6 %/trade): its "SHORT" was a SOL -> USDC swap, its "3x" bought
 *    three times the tokens, its price was the underlying's Pyth quote and its liquidity a hardcoded $10M. V5.0.8802: Solana
 *    xStocks trade again on com.lifecyclebot.xstocks.XsDesk (long or flat, 1x, executable prices, their own history);
 *    stock shorts and leverage are HIP-3 perps on the Hyperliquid desk.
 *  - METALS: spot PAXG / xStock metals only; gold and silver trade as HIP-3 perps there.
 *  - SOL PERPS (PerpsTraderAI via Flash.trade): decided on a 24 h change and RSI with no candle, funding or open-interest
 *    history; perps trade on the Hyperliquid desk (com.lifecyclebot.hyperliquid.HlDesk) with all three.
 * TraderRuntimePlan6526 never enables them and never publishes MARKETS_STOCKS / PERPS. A retired trader that still holds
 * positions is started for its exits only (its entries stay disabled), so nothing it bought is left unmanaged; the
 * report counts what each still holds.
 */
object RetiredMarkets8797 {
    val WHY: Map<String, String> = linkedMapOf(
        "FOREX" to "no Solana route",
        "COMMODITIES" to "no Solana token; HIP-3 on Hyperliquid",
        "STOCKS" to "legacy trader (fake short and leverage, Pyth price); xStocks spot desk XsDesk 8802 and HIP-3 on Hyperliquid",
        "METALS" to "spot only; HIP-3 on Hyperliquid",
        "SOL_PERPS" to "no market history; Hyperliquid desk",
    )
    @Volatile private var windDowns = ""

    /** Every retired trader refuses new entries. */
    fun disableEntries() {
        try { ForexTrader.setEnabled(false) } catch (_: Throwable) {}
        try { CommoditiesTrader.setEnabled(false) } catch (_: Throwable) {}
        try { TokenizedStockTrader.setEnabled(false) } catch (_: Throwable) {}
        try { MetalsTrader.setEnabled(false) } catch (_: Throwable) {}
        try { PerpsTraderAI.setEnabled(false) } catch (_: Throwable) {}
    }

    /** (live, paper) positions each retired trader still holds. */
    fun holdings(): Map<String, Pair<Int, Int>> {
        fun count(paper: List<Boolean>) = paper.count { !it } to paper.count { it }
        return linkedMapOf(
            "FOREX" to (try { count(ForexTrader.getAllPositions().map { it.isPaper }) } catch (_: Throwable) { 0 to 0 }),
            "COMMODITIES" to (try { count(CommoditiesTrader.getAllPositions().map { it.isPaper }) } catch (_: Throwable) { 0 to 0 }),
            "STOCKS" to (try { count(TokenizedStockTrader.getAllPositions().map { it.isPaper }) } catch (_: Throwable) { 0 to 0 }),
            "METALS" to (try { count(MetalsTrader.getAllPositions().map { it.isPaper }) } catch (_: Throwable) { 0 to 0 }),
            "SOL_PERPS" to (try { count(PerpsTraderAI.getActivePositions().map { it.isPaper }) } catch (_: Throwable) { 0 to 0 }),
        )
    }

    /** BotService start: entries off; a retired trader that still holds positions runs its exit monitor until flat. */
    fun windDown(ctx: Context) {
        disableEntries()
        try { TokenizedStockTrader.init() } catch (_: Throwable) {}
        try { CommoditiesTrader.initialize(ctx) } catch (_: Throwable) {}
        try { MetalsTrader.initialize(ctx) } catch (_: Throwable) {}
        try { ForexTrader.initialize(ctx) } catch (_: Throwable) {}
        try { PerpsTraderAI.init(ctx) } catch (_: Throwable) {}
        disableEntries()                                           // initialize may restore a saved switch
        val held = holdings()
        val started = ArrayList<String>()
        fun open(k: String) = held[k]?.let { it.first + it.second > 0 } == true
        if (open("COMMODITIES")) try { CommoditiesTrader.start(); started += "COMMODITIES" } catch (_: Throwable) {}
        if (open("METALS")) try { MetalsTrader.start(); started += "METALS" } catch (_: Throwable) {}
        if (open("STOCKS")) try { TokenizedStockTrader.start(); started += "STOCKS" } catch (_: Throwable) {}
        if (open("SOL_PERPS")) try { PerpsExecutionEngine.start(ctx); started += "SOL_PERPS" } catch (_: Throwable) {}
        windDowns = started.joinToString(",")
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle("RETIRED_MARKETS_8797",
                "entries=off windDown=${windDowns.ifBlank { "none" }} held=${held.entries.joinToString(",") { "${it.key}:${it.value.first}L/${it.value.second}P" }}")
        } catch (_: Throwable) {}
    }

    @Volatile private var lastWatchMs = 0L

    /**
     * BotService's markets watchdog (both loops): a retired trader that still holds positions and has stopped is started
     * again for its exits (entries stay off); one that holds nothing is never restarted. At most every 30 s.
     */
    fun watchdog(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastWatchMs < 30_000L) return
        lastWatchMs = nowMs
        disableEntries()
        val held = holdings()
        fun open(k: String) = held[k]?.let { it.first + it.second > 0 } == true
        try { if (open("COMMODITIES") && !CommoditiesTrader.isHealthy()) CommoditiesTrader.start() } catch (_: Throwable) {}
        try { if (open("METALS") && !MetalsTrader.isHealthy()) MetalsTrader.start() } catch (_: Throwable) {}
        try { if (open("STOCKS") && !TokenizedStockTrader.isHealthy()) TokenizedStockTrader.start() } catch (_: Throwable) {}
        try { if (open("SOL_PERPS") && !PerpsExecutionEngine.isHealthy()) PerpsExecutionEngine.start(ctx) } catch (_: Throwable) {}
    }

    fun statusLine(): String = "retired8797[" + holdings().entries.joinToString(" ") { (k, v) -> "$k(${WHY[k]}) live=${v.first} paper=${v.second}" } +
        " windDown=${windDowns.ifBlank { "none" }} rule=no new entries; held positions run their own exits; FOREX positions close from Markets -> STOP]"
}
