package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7270 §A_PEGGED_ASSET_HAS_NO_EDGE_TO_FIND.
 *
 * Operator 5.0.7267 at 25 minutes: BLUECHIP evaluated 904 candidates, raised
 * 123 buy intents and drew 682 of the 689 fan-out-cap blocks — on USD1, USDG,
 * PYUSD, SUSDE, USYC, USDE, USDGO and BUIDL. QUALITY held USDS and USTB the
 * session before. These are dollar-pegged instruments. Their price is
 * engineered not to move, so there is no runner in them, no 2%, and the lane
 * budget they consume is taken from assets that can move.
 *
 * This is not a threshold on a strategy. A peg is a structural fact about the
 * instrument, the same class of fact as "this mint has no pool". The guard
 * reads two things, symbol family and a price sitting on 1.00 at a market cap
 * only a stable reaches, and requires the price test for anything the symbol
 * list does not name, so a memecoin that happens to be called PIGUSD is not
 * caught unless it also trades at exactly a dollar with a nine-figure cap.
 * Counted per lane so the operator sees what was declined and why.
 *
 * V5.0.7273 — the guard keyed on the symbol alone, and 5.0.7271/7272 show
 * CORE buying PYUSD (2b1kV6…) and QUALITY buying USDS (USDSwr…) twice, both
 * with a blank symbol on the row: the journal printed the mint prefix. The
 * mint is the identity that never goes missing, so the guard now knows the
 * major stable mints directly, and for a blank symbol reads the vanity prefix
 * the issuers put on their own addresses (USDS…, USD1…) before falling back to
 * the price-at-cap test, which for a blank symbol accepts a dollar price at a
 * billion-dollar cap on its own — nothing that large sits on 1.00 by accident.
 */
object PeggedAssetGuard7270 {

    private val PEG_SYMBOLS = setOf(
        "USDC", "USDT", "USDE", "SUSDE", "USDG", "USDGO", "USD1", "USDS", "USDY", "USDX", "USDH",
        "PYUSD", "USYC", "BUIDL", "USTB", "DAI", "FDUSD", "TUSD", "GUSD", "CUSD", "EURC", "EURT",
        "UXD", "PAI", "USDD", "FRAX", "LUSD", "SUSD", "USDP", "USDR", "CASH", "USDCET", "USDTET",
    )

    /** V5.0.7273 — the mints behind the symbols the lanes keep buying. */
    private val PEG_MINTS = setOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", // PYUSD
        "USDSwr9ApdHk5bvJKMjzff41FfuX8bSxdKcR81vTwcA",  // USDS
        "USD1ttGY1N17NEEHLmELoaybftRBUSErhqYiQzvEmuB",  // USD1
        "2u1tszSeqZ3qBWF3uNGPFc8TzMk2tdiwknnRMWGWjGWH", // USDG
        "DEkqHyPN7GMRJ5cArtQFAWefqbZb33Hyf6s5iCwjEonT", // USDe
        "9zNQRsGLjNKwCUU5Gq5LR8beUCPzQMVMqKAi3SSZh54u", // FDUSD
        "A1KLoBrKBde8Ty9qtNQUtq3C2ortoC3u7twggz7sEto6", // USDY
    )

    private const val PEG_PRICE_LOW = 0.97
    private const val PEG_PRICE_HIGH = 1.03
    private const val PEG_MIN_MCAP_USD = 50_000_000.0
    /** V5.0.7273 — with no symbol to read, only a stable reaches this cap at a dollar. */
    private const val PEG_BLANK_SYMBOL_MIN_MCAP_USD = 1_000_000_000.0

    private val skipped = AtomicLong(0L)

    fun isPegged(symbol: String?, priceUsd: Double, mcapUsd: Double, mint: String = ""): Boolean {
        val m = mint.trim()
        if (m.isNotBlank() && m in PEG_MINTS) return true
        val sym = symbol?.trim()?.uppercase()?.removePrefix("$") ?: ""
        if (sym.isNotBlank() && sym != "?" && sym in PEG_SYMBOLS) return true
        val symbolBlank = sym.isBlank() || sym == "?"
        val vanityUsd = symbolBlank && m.length >= 4 && m.take(4).uppercase().startsWith("USD")
        // Symbol not on the list: only a dollar price at a stablecoin-sized cap counts.
        if (!priceUsd.isFinite() || !mcapUsd.isFinite()) return false
        val onPeg = priceUsd in PEG_PRICE_LOW..PEG_PRICE_HIGH
        if (!onPeg) return false
        if (vanityUsd && mcapUsd >= PEG_MIN_MCAP_USD) return true
        if (symbolBlank) return mcapUsd >= PEG_BLANK_SYMBOL_MIN_MCAP_USD
        return mcapUsd >= PEG_MIN_MCAP_USD &&
            (sym.startsWith("USD") || sym.endsWith("USD") || sym.startsWith("EUR"))
    }

    /** Record a lane declining a pegged instrument; returns true for chaining. */
    fun noteSkipped(lane: String, symbol: String?): Boolean {
        skipped.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PEGGED_ASSET_LANE_SKIPPED_7270")
            PipelineHealthCollector.labelInc("PEGGED_ASSET_LANE_SKIPPED_7270_${lane.trim().uppercase().take(16)}")
        } catch (_: Throwable) {}
        return true
    }

    fun skippedCount(): Long = skipped.get()
}
