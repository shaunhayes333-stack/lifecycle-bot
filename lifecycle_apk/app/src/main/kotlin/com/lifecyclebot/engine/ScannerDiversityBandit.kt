package com.lifecyclebot.engine

/** V5.0.4273 — source-family ordering helper; never blocks a source. */
object ScannerDiversityBandit {
    // V5.0.6856 §THE_BANDIT_ASKED_ABOUT_SOURCES_THAT_DO_NOT_EXIST — this used to be
    // a family→single-representative-source map, and six of its eight names
    // (DEXSCREENER, COINMARKETCAP, SCANNER_TRENDING, PUMP_PORTAL, BIRDEYE, OTHER)
    // are not SolanaMarketScanner.TokenSource values and therefore never existed as
    // a recorded key. Those families always looked up a miss, got the neutral 1.0,
    // and orderedFamilies() degenerated into a fixed sort that could not reorder
    // anything however the scanners performed. Only COINGECKO_TRENDING and
    // RAYDIUM_NEW_POOL were real names.
    //
    // A family is several feeds, not one: DEX is DEX_TRENDING / DEX_GAINERS /
    // DEX_BOOSTED, BIRDEYE is four separate feeds, PUMP is PUMP_FUN_NEW and
    // PUMP_FUN_GRADUATE. These are match tokens against the real enum names, pooled
    // by ScannerSourceBrain.familyIntakeMultiplier6856.
    private val familyMatchTokens = mapOf(
        "DEX" to listOf("DEX_TRENDING", "DEX_GAINERS", "DEX_BOOSTED", "DEXSCREENER"),
        "COINGECKO" to listOf("COINGECKO"),
        "CMC" to listOf("COINMARKETCAP", "CMC"),
        "RAYDIUM" to listOf("RAYDIUM"),
        "BIRDEYE" to listOf("BIRDEYE"),
        "SCANNER" to listOf("SCANNER_DIRECT", "SCANNER_", "NARRATIVE_SCAN"),
        "PUMP" to listOf("PUMP_FUN", "PUMP_PORTAL", "PUMP"),
        // OTHER is the residual bucket and has no feed of its own to score; it is
        // pinned last by orderedFamilies regardless, so leave it neutral.
        "OTHER" to emptyList(),
    )

    fun orderedFamilies(defaultPriority: List<String>): List<String> {
        return try {
            val known = defaultPriority.distinct()
            val nonPump = known.filter { it != "PUMP" && it != "OTHER" }
                .sortedWith(compareByDescending<String> { familyQuality(it) }.thenBy { known.indexOf(it) })
            val out = mutableListOf<String>()
            out.addAll(nonPump)
            if ("PUMP" in known) out.add("PUMP")
            if ("OTHER" in known) out.add("OTHER")
            known.filterNot { it in out }.forEach { out.add(it) }
            out
        } catch (_: Throwable) { defaultPriority }
    }

    private fun familyQuality(family: String): Double {
        val tokens = familyMatchTokens[family] ?: listOf(family)
        if (tokens.isEmpty()) return 1.0
        return try {
            ScannerSourceBrain.familyIntakeMultiplier6856(*tokens.toTypedArray()).coerceIn(0.40, 1.80)
        } catch (_: Throwable) { 1.0 }
    }
}
