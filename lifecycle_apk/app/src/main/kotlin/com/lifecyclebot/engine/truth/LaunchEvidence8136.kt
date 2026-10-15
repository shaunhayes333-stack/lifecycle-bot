package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 §A_PRICE_IS_NOT_A_BUYER.
 *
 * PumpPortal refuses the keyed token-trade subscription (tradeStream=AUTH_DENIED), so on most devices no swap record
 * ever arrives from it, while the RPC curve ladder and the create frame still deliver PRICES. The launch classifiers
 * (LaunchPhaseAuthority7401, ProjectSniperAI) read buyer count / velocity / acceleration as plain Ints that are 0 both
 * when nobody bought and when the bot simply cannot see swaps, so "no feed" read as "no demand" and nothing said which.
 *
 * Launch evidence now carries its provenance:
 *  - [Provenance8136.SWAP_RECORDS]: actual swap records (signer, side, size) for this mint, or a swap feed that
 *    demonstrably covers it (then an empty window is a real, observed zero);
 *  - [Provenance8136.PRICE_ONLY]: prices were observed but no swap feed covers the mint;
 *  - [Provenance8136.NONE]: nothing observed.
 * Buyer count, buy/sell counts, velocity and acceleration are stated ONLY under SWAP_RECORDS; otherwise they are null
 * (unknown) — never zero, never derived from price updates. A repeated swap frame (same signature, or the same
 * signer/side/size/post-trade reserves) is one swap: [noteSwap8136] answers false for it so no consumer counts it twice.
 */
object LaunchEvidence8136 {
    enum class Provenance8136 { SWAP_RECORDS, PRICE_ONLY, NONE }

    /** One executed swap: who, which side, how much SOL, when (arrival time at the bot). */
    data class Swap8136(val atMs: Long, val signer: String, val isBuy: Boolean, val sol: Double)

    /** Launch flow with provenance; every count is null unless [provenance] is SWAP_RECORDS. */
    data class FlowRead8136(
        val provenance: Provenance8136,
        val buyers60s: Int?,
        val buyTx60s: Int?,
        val sellTx60s: Int?,
        val buySharePct: Double?,
        val buyersPerMin: Double?,
        val accelerationRising: Boolean?,
    ) {
        val known: Boolean get() = provenance == Provenance8136.SWAP_RECORDS
    }

    private const val WINDOW_MS = 60_000L
    private const val ACCEL_BUCKET_MS = 15_000L
    private const val DEDUP_KEEP_MS = 10L * 60_000L
    private const val MAX_DEDUP_KEYS = 20_000
    private const val MAX_MINTS = 4_000
    internal const val SWAP_COVERAGE_MS_8136 = 5L * 60_000L

    private val seenKeys = ConcurrentHashMap<String, Long>()
    private val lastSwapAt = ConcurrentHashMap<String, Long>()
    private val lastPriceAt = ConcurrentHashMap<String, Long>()
    private val swaps = AtomicLong(0)
    private val duplicates = AtomicLong(0)
    private val prices = AtomicLong(0)

    /** Pure: one swap's identity — the transaction signature when the frame has one, else its post-trade state. */
    fun dedupKey8136(mint: String, signature: String, signer: String, isBuy: Boolean, sol: Double, vSol: Double, vTok: Double): String =
        if (signature.isNotBlank()) "$mint|sig|$signature|$isBuy|$sol"
        else "$mint|$signer|$isBuy|$sol|$vSol|$vTok"

    /**
     * Pure: provenance of a launch's flow at decision time. [swapRecords60s] are swap records inside the window;
     * [swapFeedCovers] is true when a swap feed is known to be delivering this mint (an empty window is then a zero
     * that was observed); [priceObserved] is any price reading.
     */
    fun provenance8136(swapRecords60s: Int, swapFeedCovers: Boolean, priceObserved: Boolean): Provenance8136 = when {
        swapRecords60s > 0 || swapFeedCovers -> Provenance8136.SWAP_RECORDS
        priceObserved -> Provenance8136.PRICE_ONLY
        else -> Provenance8136.NONE
    }

    /**
     * Pure: the launch flow from swap records only, at decision time [nowMs] — a record stamped after [nowMs] is not
     * yet known and is ignored. Distinct buyers are distinct non-blank signers other than [devWallet]; velocity is
     * buyers per minute over the window; acceleration compares the last 15 s of buys with the 15 s before.
     */
    fun flowFrom8136(
        records: List<Swap8136>,
        swapFeedCovers: Boolean,
        priceObserved: Boolean,
        devWallet: String?,
        nowMs: Long,
    ): FlowRead8136 {
        val win = records.filter { it.atMs <= nowMs && nowMs - it.atMs <= WINDOW_MS }
        val prov = provenance8136(win.size, swapFeedCovers, priceObserved)
        if (prov != Provenance8136.SWAP_RECORDS) return FlowRead8136(prov, null, null, null, null, null, null)
        val buys = win.filter { it.isBuy }
        val sells = win.filterNot { it.isBuy }
        val dev = devWallet?.takeIf { it.isNotBlank() }
        val buyers = buys.asSequence().map { it.signer }.filter { it.isNotBlank() && it != dev }.toSet().size
        val buySol = buys.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 }
        val sellSol = sells.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 }
        val last15 = buys.count { nowMs - it.atMs < ACCEL_BUCKET_MS }
        val prev15 = buys.count { nowMs - it.atMs in ACCEL_BUCKET_MS until 2 * ACCEL_BUCKET_MS }
        return FlowRead8136(
            provenance = prov,
            buyers60s = buyers,
            buyTx60s = buys.size,
            sellTx60s = sells.size,
            buySharePct = if (buySol + sellSol > 0.0) buySol / (buySol + sellSol) * 100.0 else null,
            buyersPerMin = buyers.toDouble() * 60_000.0 / WINDOW_MS,
            accelerationRising = last15 >= 3 && last15 > prev15,
        )
    }

    /**
     * Pure: an already-counted flow (WhaleDetector's launch tape — PumpPortal + Helius swap records) restated with
     * provenance. [tradeCount60s] is that tape's swap count in its window; the counts survive only under SWAP_RECORDS.
     */
    fun restate8136(
        tradeCount60s: Int,
        swapFeedCovers: Boolean,
        priceObserved: Boolean,
        buyers60s: Int,
        buyTx60s: Int,
        sellTx60s: Int,
        buySharePct: Double,
        accelerationRising: Boolean,
    ): FlowRead8136 {
        val prov = provenance8136(tradeCount60s, swapFeedCovers, priceObserved)
        if (prov != Provenance8136.SWAP_RECORDS) return FlowRead8136(prov, null, null, null, null, null, null)
        return FlowRead8136(
            provenance = prov,
            buyers60s = buyers60s,
            buyTx60s = buyTx60s,
            sellTx60s = sellTx60s,
            buySharePct = if (tradeCount60s > 0 && buySharePct.isFinite()) buySharePct else null,
            buyersPerMin = buyers60s.toDouble() * 60_000.0 / WINDOW_MS,
            accelerationRising = accelerationRising,
        )
    }

    /**
     * PumpFunWS trade frame: records the swap once. False when [key] was already seen (a repeated or re-sent frame),
     * so the caller does not hand the same swap to the buyer tape / trade tape a second time.
     */
    fun noteSwap8136(mint: String, key: String, atMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || key.isBlank()) return false
        val prior = seenKeys.putIfAbsent(key, atMs)
        if (prior != null && atMs - prior <= DEDUP_KEEP_MS) {
            duplicates.incrementAndGet()
            return false
        }
        if (prior != null) seenKeys[key] = atMs
        lastSwapAt[mint] = atMs
        swaps.incrementAndGet()
        if (seenKeys.size > MAX_DEDUP_KEYS) seenKeys.entries.removeIf { atMs - it.value > DEDUP_KEEP_MS }
        if (seenKeys.size > MAX_DEDUP_KEYS) seenKeys.clear()
        if (lastSwapAt.size > MAX_MINTS) lastSwapAt.entries.removeIf { atMs - it.value > SWAP_COVERAGE_MS_8136 }
        return true
    }

    /** A price-only observation (create frame, curve read): evidence of a price, never of a buyer. */
    fun notePrice8136(mint: String, atMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        lastPriceAt[mint] = atMs
        prices.incrementAndGet()
        if (lastPriceAt.size > MAX_MINTS) lastPriceAt.entries.removeIf { atMs - it.value > SWAP_COVERAGE_MS_8136 }
    }

    /** True when a swap record for [mint] arrived within [SWAP_COVERAGE_MS_8136] before [nowMs]. */
    fun swapSeenRecently8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastSwapAt[mint]?.let { nowMs - it in 0L..SWAP_COVERAGE_MS_8136 } == true

    fun priceSeenRecently8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastPriceAt[mint]?.let { nowMs - it in 0L..SWAP_COVERAGE_MS_8136 } == true

    fun statusLine8136(): String = "swaps=${swaps.get()} duplicateFrames=${duplicates.get()} priceOnly=${prices.get()} mints=${lastSwapAt.size}"
}
