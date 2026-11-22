package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8952 §A_PUMPSWAP_POOL_PRICES_ON_ITS_EFFECTIVE_QUOTE
 *
 * Pump Developer Updates (#43-#45): since 21 Jul 2026 a PumpSwap pool carries `virtual_quote_reserves` (i128, may be
 * negative since the next release), and every quote, price and index must use
 *   effective_quote_reserves = quote_vault_balance + virtual_quote_reserves.
 * The swap events were decoded that way since 8870, but OnChainDepth8038 priced a PumpSwap pool from its two vault
 * balances alone (SOL vault / coin vault): a graduated coin's chain price, cap and depth missed the virtual part. The
 * pool account's own field offset is not published where this build could read it, so the value is taken from the pool's
 * own decoded swap events (lamports, as of the swap), and the vault read adds the latest one ([effectiveSol8952]).
 * A pool with no event seen yet is counted ([unknown8952]); its read is the vault ratio as before.
 */
object PoolVirtualQuote8952 {
    private const val CAP_8952 = 4_000
    private val byPool = ConcurrentHashMap<String, Pair<Double, Long>>()   // pool -> (virtual quote lamports, at)
    private val notes = AtomicLong(0L)
    private val nonZero = AtomicLong(0L)
    private val negative = AtomicLong(0L)
    private val applied = AtomicLong(0L)
    private val unknown = AtomicLong(0L)
    private val refused = AtomicLong(0L)

    fun note8952(pool: String, virtualLamports: Double, nowMs: Long = System.currentTimeMillis()) {
        if (pool.length !in 32..44 || !virtualLamports.isFinite()) return
        notes.incrementAndGet()
        if (virtualLamports != 0.0) nonZero.incrementAndGet()
        if (virtualLamports < 0.0) negative.incrementAndGet()
        if (byPool.size > CAP_8952) byPool.entries.sortedBy { it.value.second }.take(CAP_8952 / 10).forEach { byPool.remove(it.key) }
        byPool[pool] = virtualLamports to nowMs
    }

    /** Pure: the effective SOL of a pool with [vaultSol] in its quote vault and [virtualLamports] virtual, or null if not positive. */
    fun effective8952(vaultSol: Double, virtualLamports: Double): Double? {
        val e = vaultSol + virtualLamports / 1e9
        return if (e.isFinite() && e > 0.0) e else null
    }

    /** OnChainDepth8038 (PumpSwap pool): the effective quote SOL of [pool] for a [vaultSol] vault read, or null when unpriceable. */
    fun effectiveSol8952(pool: String, vaultSol: Double): Double? {
        val v = byPool[pool]?.first
        if (v == null) { unknown.incrementAndGet(); return vaultSol }
        val e = effective8952(vaultSol, v)
        if (e == null) refused.incrementAndGet() else if (v != 0.0) applied.incrementAndGet()
        return e
    }

    fun statusLine8952(): String =
        "poolVirtualQuote8952[pools=${byPool.size} eventNotes=${notes.get()} nonZero=${nonZero.get()} negative=${negative.get()} " +
            "vaultReadsAdjusted=${applied.get()} vaultReadsNoEventYet=${unknown.get()} refusedNonPositive=${refused.get()} " +
            "rule=a PumpSwap vault read prices on vault + the pool's virtual quote reserves (from its own swap events)]"

    internal fun resetForTest8952() { byPool.clear(); notes.set(0); nonZero.set(0); negative.set(0); applied.set(0); unknown.set(0); refused.set(0) }
}
