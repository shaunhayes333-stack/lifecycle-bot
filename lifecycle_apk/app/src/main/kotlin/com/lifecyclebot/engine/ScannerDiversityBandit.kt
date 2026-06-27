package com.lifecyclebot.engine

/** V5.0.4273 — source-family ordering helper; never blocks a source. */
object ScannerDiversityBandit {
    private val representativeSource = mapOf(
        "DEX" to "DEXSCREENER",
        "COINGECKO" to "COINGECKO_TRENDING",
        "CMC" to "COINMARKETCAP",
        "RAYDIUM" to "RAYDIUM_NEW_POOL",
        "BIRDEYE" to "BIRDEYE",
        "SCANNER" to "SCANNER_TRENDING",
        "PUMP" to "PUMP_PORTAL",
        "OTHER" to "OTHER",
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
        val src = representativeSource[family] ?: family
        return try { ScannerSourceBrain.intakeMultiplier(src).coerceIn(0.40, 1.80) } catch (_: Throwable) { 1.0 }
    }
}
