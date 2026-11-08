package com.lifecyclebot.engine

/**
 * V5.0.8633 — D6: one endpoint, one name (audit_8629 D6, DIAG_AND_BUILD_8629 Step 1 item 5).
 *
 * DexScreener `token-profiles/latest` was read by seven readers (six scanners and the boot test scan) under DEX_TRENDING, PUMP_FUN_NEW,
 * PUMP_FUN_GRADUATE and RAYDIUM_NEW_POOL (the last three picked by pair age), `token-boosts/top` by three
 * scanners under DEX_BOOSTED and PUMP_FUN_GRADUATE, GeckoTerminal trending_pools under DEX_TRENDING and
 * COINGECKO_TRENDING, and Meteora pools under RAYDIUM_NEW_POOL. One feed then counted as several
 * "independent" sources (hot admission, probation MULTI_CONFIRM, merge-queue bonus) and the learned source
 * books attributed outcomes to feeds that did not exist.
 *
 * The scanner now emits the endpoint's own name. This object holds the pure identity rules:
 *  - [endpointOf8633]: aliases of one endpoint (PUMP_PORTAL_WS / PUMPPORTAL, the MARKET_HUNT_<lane> rows
 *    of one market sweep, the legacy DEX_GAINERS name) resolve to one endpoint, so they count once;
 *  - [withVenueHint8633]: a rule that needs the coin's VENUE reads it from the mint, never from the feed
 *    name. Only the venue-agnostic DexScreener listing feeds get the hint (their young rows were labelled
 *    PUMP_FUN_* before), so no other source's behaviour widens.
 */
object SourceTruth8633 {
    /** DexScreener token-profiles/latest (paid profiles). */
    const val DEX_PROFILES_8633 = "DEX_PROFILES"
    /** DexScreener token-boosts top + latest (paid boosts); the existing DEX_BOOSTED name is kept. */
    const val DEX_BOOSTS_8633 = "DEX_BOOSTED"
    /** GeckoTerminal /dexes/meteora/pools. */
    const val METEORA_POOL_8633 = "METEORA_POOL"
    /** MarketSweep7297 snapshot rows (the resident hunters and the emergency fallback read the same view). */
    const val MARKET_SWEEP_8633 = "MARKET_SWEEP"
    /** Tag naming a pump.fun mint for venue rules; never a discovery source and never stored as one. */
    const val VENUE_PUMP_FUN_8633 = "VENUE_PUMP_FUN"

    private const val PUMP_PORTAL_8633 = "PUMP_PORTAL"
    private const val MARKET_HUNT_PREFIX_8633 = "MARKET_HUNT_"

    private val ENDPOINT_ALIASES_8633: Map<String, String> = mapOf(
        "PUMP_PORTAL_WS" to PUMP_PORTAL_8633,
        "PUMPPORTAL" to PUMP_PORTAL_8633,
        "RAYDIUM_NEW" to "RAYDIUM_NEW_POOL",
        "DEX_GAINERS" to DEX_PROFILES_8633,
        "DEX_BOOSTS" to DEX_BOOSTS_8633,
    )

    /** Feeds that list coins of every venue: the feed name says nothing about where the coin trades. */
    private val VENUE_AGNOSTIC_FEEDS_8633: Set<String> = setOf(DEX_PROFILES_8633, DEX_BOOSTS_8633)

    /** Pure: the endpoint behind one canonical source tag ("PUMP_PORTAL_WS" -> "PUMP_PORTAL"). */
    fun endpointOf8633(tag: String): String {
        val t = tag.trim().uppercase()
        if (t.startsWith(MARKET_HUNT_PREFIX_8633)) return MARKET_SWEEP_8633
        return ENDPOINT_ALIASES_8633[t] ?: t
    }

    /** Pure: [incoming] reads the same endpoint as one of [known] (so it is no second, confirming source). */
    fun sameEndpointAsAny8633(incoming: String, known: Collection<String>): Boolean {
        if (incoming.isBlank()) return false
        val endpoint = endpointOf8633(incoming)
        return known.any { it.isNotBlank() && endpointOf8633(it) == endpoint }
    }

    /** Pure: distinct endpoints behind a scanner set; a set holding only pathway tags is one sighting. */
    fun endpointCount8633(scanners: Collection<String>): Int {
        val real = scanners.map { ScannerSourceTags8292.canonicalTag8292(it) }.filter { it.isNotBlank() }
        if (real.isEmpty()) return minOf(1, scanners.size)
        return real.map { endpointOf8633(it) }.distinct().size
    }

    /** Pure: the coin's venue from its mint (same rule as PumpFunDirectApi.isPumpFunMint); "" when unknown. */
    fun venueFromMint8633(mint: String): String =
        if (mint.trim().endsWith("pump", ignoreCase = true)) VENUE_PUMP_FUN_8633 else ""

    /** Pure: the coin's venue tag when one of its feeds is venue-agnostic (and the mint names a venue); else empty. */
    fun venueTags8633(source: String, sources: Collection<String>, mint: String): Set<String> {
        val venue = venueFromMint8633(mint)
        if (venue.isBlank()) return emptySet()
        val feeds = (sources + source).flatMap { ScannerSourceTags8292.realSources8292(it) }.map { endpointOf8633(it) }
        return if (feeds.any { it in VENUE_AGNOSTIC_FEEDS_8633 }) setOf(venue) else emptySet()
    }

    /** Pure: [source] plus the coin's venue tag when the feed itself is venue-agnostic; else [source] unchanged. */
    fun withVenueHint8633(source: String, mint: String): String =
        venueTags8633(source, emptyList(), mint).fold(source) { acc, v -> "$acc|$v" }
}
