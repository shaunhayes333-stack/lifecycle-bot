package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8963 §A_CHAIN_READ_IS_A_PRICE
 *
 * Report 23 Nov 22:58 (5.0.8961, LIVE, 38 min): the proven pockets' coins deferred on data before anything else —
 * LANE_POCKET 102 of 211, LAB_STRATEGY 116 of 192, BASE_POCKET 18 of 56 — and the wait each named first was PRICE_STALE
 * (70 / 30 / 10; LAUNCH_RULE 9, MARKET_CELL 3). ChainConfirm8950 asked the chain for PRICE_DEPTH on every one of them
 * (EVIDENCE / DECISION / LANE asks), and the chain answered: the coin's pump.fun curve account (CurveReserves7951) or its
 * pool's two vaults (OnChainDepth8038). But both reads wrote LIQUIDITY only (TokenFacts liq accepted CHAIN_VAULT_8038
 * 254,363, CURVE_READ_8046 111,772; neither appears among the price sources), so the admission still read the coin's
 * last trade price, still older than 30 s, and deferred again; 8957's re-run met the same stale price.
 *
 * Now a chain read that is a price writes it, through the one price authority (TokenFacts.writePrice, CHAIN rank, the
 * read's own observation time), when it is newer than the price the coin holds:
 *  - an active pump.fun curve's account read: virtual SOL / virtual tokens x SOL/USD (not a graduated curve; a curve
 *    quoted in USDC or another coin never reaches here, QuoteTruth8952);
 *  - a constant-product pool's vault read (PumpSwap only once its virtual quote reserves are known, PoolVirtualQuote8952;
 *    Raydium v4 / CPMM). A concentrated venue's vault ratio is depth, never a price, and never writes here.
 *  - V5.0.8986: a concentrated pool's own state price (CLMM / Whirlpool sqrt price, DLMM active bin; ConcentratedPrice8418,
 *    layout-proved against the vaults' mints and decimals). 8418 decoded it for the buy door and the rug evidence only.
 * When the coin's price had gone stale, its waiting decisions are woken (CandidateAdmission8136.evidenceArrived8252,
 * "price"). No gate, threshold or verdict changes: the same staleness rule now meets the chain's answer.
 */
object ChainPrice8963 {
    const val CURVE_SOURCE_8963 = "CURVE_ACCOUNT_7951_PRICE_8963"
    const val VAULT_SOURCE_8963 = "CHAIN_VAULT_READ_8963_"
    /** V5.0.8986 — a concentrated pool's decoded state price (Raydium CLMM / Orca Whirlpool sqrt price, Meteora DLMM active bin). */
    const val STATE_SOURCE_8986 = "CHAIN_STATE_READ_8986_"
    private val written = ConcurrentHashMap<String, AtomicLong>()
    private val woke = AtomicLong(0L)
    private val notNewer = AtomicLong(0L)
    private val refused = AtomicLong(0L)

    /** Pure: is a chain observation at [observedAtMs] newer than the price held at [heldAtMs]? */
    internal fun newer8963(heldAtMs: Long, observedAtMs: Long): Boolean = observedAtMs > 0L && observedAtMs > heldAtMs

    /** Pure: had the held price gone stale for a LIVE decision (the hot-launch window, the shortest) at [nowMs]? */
    internal fun wasStale8963(heldAtMs: Long, nowMs: Long): Boolean =
        heldAtMs <= 0L || nowMs - heldAtMs > PriceAge8549.LIVE_HOT_STALE_MS_8549

    /** Write [usd] for [mint] from [source] observed at [observedAtMs] when newer than the held price. True when written. */
    fun publish8963(mint: String, usd: Double, source: String, observedAtMs: Long, pool: String? = null): Boolean {
        if (!(usd.isFinite() && usd > 0.0) || mint.isBlank()) return false
        val ts = try { com.lifecyclebot.engine.HandoffRoute8180.tokenFor8180(mint) } catch (_: Throwable) { null } ?: return false
        val heldAt = ts.lastPriceUpdate
        if (!newer8963(heldAt, observedAtMs)) { notNewer.incrementAndGet(); return false }
        val nowMs = System.currentTimeMillis()
        val ok = try {
            synchronized(ts) { TokenFacts.writePrice(ts, usd, source, TokenFacts.Rank.CHAIN, observedAtMs, nowMs, pool = pool) }
        } catch (_: Throwable) { false }
        if (!ok) { refused.incrementAndGet(); return false }
        val key = if (source.startsWith(VAULT_SOURCE_8963)) "VAULT" else if (source.startsWith(STATE_SOURCE_8986)) "STATE" else "CURVE"
        written.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        if (wasStale8963(heldAt, nowMs)) {
            woke.incrementAndGet()
            try { CandidateAdmission8136.evidenceArrived8252(mint, setOf("price"), nowMs) } catch (_: Throwable) {}
        }
        return true
    }

    fun statusLine8963(): String =
        "chainPrice8963[written{" + written.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "} wokeStale=${woke.get()} notNewer=${notNewer.get()} refusedByAuthority=${refused.get()} rule=a curve account, a " +
            "constant-product vault read or a concentrated pool's state price (8986) writes its price (CHAIN rank, its own time) when newer than the coin's; a stale coin's " +
            "waiting decisions are woken]"
}
