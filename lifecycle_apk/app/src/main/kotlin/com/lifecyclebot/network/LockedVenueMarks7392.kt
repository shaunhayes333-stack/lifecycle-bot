package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TokenMetaCache
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * V5.0.7392 — held positions are priced from the venue the token register
 * locked at purchase.
 *
 * Operator: "we have an on-board token register. we shouldn't have stale or
 * unknown prices. It stores full details on discovery then locks in at
 * purchase."
 *
 * The register (TokenMetaCache, sealed at MINT_ENTRY_MARKET_SNAPSHOT_STORED)
 * holds each traded mint's pool address, DEX and price source. The exit loop
 * nevertheless asked DexScreener's by-mint batch first — which does not list
 * bonding-curve tokens at all (MARK_BATCH_EMPTY_6970 = 333 of 364 ticks) — and
 * then a multi-feed fan-out whose single answers were labelled untrusted, so
 * locks, partials and culls refused them.
 *
 * Here each held mint is priced where it actually trades:
 *  - un-graduated pump.fun token: its bonding curve, read on-chain at the curve
 *    PDA derived from the mint (PumpCurveKeys7269.canonicalCurveKey7392);
 *  - graduated / AMM token: the exact pool sealed in the register, read by pool
 *    address (the base token must be this mint), never a "best pair" guess.
 * Both are the price a sell executes against, so the result is labelled
 * LOCKED_VENUE_* and treated as trusted. Whatever this cannot price still goes
 * through the existing batch/fan-out chain unchanged.
 */
object LockedVenueMarks7392 {

    data class Mark(val priceUsd: Double, val source: String)

    private const val CURVE_SOURCE = "LOCKED_VENUE_CURVE_7392"
    private const val POOL_SOURCE = "LOCKED_VENUE_POOL_7392"

    // V5.0.7432 — the exit/held-mark path must never inherit a provider's
    // multi-second socket timeout. 7392 is called from both the canonical
    // fallback chain and HeldHotMarkAuthority; a blocking provider here used
    // to park the owning coroutine in runBlocking/locked_venue and stop ALL
    // held positions behind it from advancing. Keep at most two provider calls
    // in flight, do not queue stale work, and return control to the caller on
    // the deadline. A timed-out provider may finish later on its daemon thread,
    // but it no longer owns the exit scheduler.
    private const val PROVIDER_DEADLINE_MS_7432 = 600L
    private val providerExecutor7432 = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>(),
        { r -> Thread(r, "locked-venue-7432").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun <T> boundedProvider7432(label: String, block: () -> T): T? {
        val future = try { providerExecutor7432.submit<T> { block() } } catch (_: Throwable) {
            try { PipelineHealthCollector.labelInc("LOCKED_VENUE_PROVIDER_SATURATED_7432") } catch (_: Throwable) {}
            return null
        }
        return try {
            future.get(PROVIDER_DEADLINE_MS_7432, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            try {
                PipelineHealthCollector.labelInc("LOCKED_VENUE_PROVIDER_TIMEOUT_7432")
                PipelineHealthCollector.labelInc("LOCKED_VENUE_PROVIDER_TIMEOUT_7432_" + label)
            } catch (_: Throwable) {}
            null
        } catch (_: Throwable) {
            future.cancel(true)
            try { PipelineHealthCollector.labelInc("LOCKED_VENUE_PROVIDER_ERROR_7432_" + label) } catch (_: Throwable) {}
            null
        }
    }

    private fun validPool(addr: String?): String? =
        addr?.trim()?.takeIf { it.length in 32..44 && it.none { c -> c == ':' || c == '|' || c == '/' } }

    private fun curveByRegister(entry: TokenMetaCache.Entry?): Boolean {
        if (entry == null) return false
        val tag = (entry.lastPriceSource + " " + entry.lastPriceDex).uppercase()
        return tag.contains("BONDING") || tag.contains("PUMP_FUN_BC") || tag.contains("PUMPFUN_BC") ||
            tag.contains("CURVE")
    }

    /** Price [mints] from their locked venues. Blocking; call off the main thread. */
    fun resolve(mints: List<String>, dex: DexscreenerApi): Map<String, Mark> {
        val wanted = mints.map { it.trim() }.filter { it.length in 32..44 }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val register = try {
            com.lifecyclebot.AATEApp.appContextOrNull()?.let { TokenMetaCache.get(it) }
        } catch (_: Throwable) { null }
        val out = HashMap<String, Mark>(wanted.size)

        // 1. Bonding curves (chain state).
        val curveMints = wanted.filter { m ->
            !PumpCurveKeys7269.isGraduated7392(m) && (
                PumpCurveKeys7269.keyFor(m) != null || m.endsWith("pump", ignoreCase = true) ||
                    curveByRegister(try { register?.lookup(m) } catch (_: Throwable) { null })
                )
        }
        if (curveMints.isNotEmpty()) {
            val curve = boundedProvider7432("CURVE") {
                ParallelMarkFanout7088.curvePrices7392(curveMints)
            } ?: emptyMap()
            for ((m, px) in curve) if (px.isFinite() && px > 0.0) out[m] = Mark(px, CURVE_SOURCE)
        }

        // 2. The exact pool sealed at purchase (graduated tokens, AMM tokens, and
        //    any curve mint the curve read did not price this pass).
        val poolByMint = HashMap<String, String>()
        for (m in wanted) {
            if (m in out) continue
            val entry = try { register?.lookup(m) } catch (_: Throwable) { null } ?: continue
            val pool = validPool(entry.pairAddress) ?: validPool(entry.lastPricePoolAddr) ?: continue
            poolByMint[m] = pool
        }
        if (poolByMint.isNotEmpty()) {
            val mintByPool = poolByMint.entries.associate { (m, p) -> p to m }
            for (chunk in mintByPool.keys.toList().chunked(30)) {
                val got = boundedProvider7432("POOL") {
                    dex.pairPriceFetch7392(chunk)
                } ?: emptyMap()
                for ((pool, basePx) in got) {
                    val mint = mintByPool[pool] ?: continue
                    // Identity lock: the pool's base token must be this mint.
                    if (!basePx.first.equals(mint, ignoreCase = false)) {
                        try { PipelineHealthCollector.labelInc("LOCKED_VENUE_POOL_BASE_MISMATCH_7392") } catch (_: Throwable) {}
                        continue
                    }
                    if (basePx.second.isFinite() && basePx.second > 0.0) out[mint] = Mark(basePx.second, POOL_SOURCE)
                }
            }
        }

        try {
            PipelineHealthCollector.labelInc("LOCKED_VENUE_PASS_7392")
            val curveN = out.values.count { it.source == CURVE_SOURCE }
            val poolN = out.size - curveN
            if (curveN > 0) PipelineHealthCollector.labelInc("LOCKED_VENUE_CURVE_PRICED_7392")
            if (poolN > 0) PipelineHealthCollector.labelInc("LOCKED_VENUE_POOL_PRICED_7392")
            if (out.size < wanted.size) PipelineHealthCollector.labelInc("LOCKED_VENUE_UNPRICED_LEFT_TO_CHAIN_7392")
        } catch (_: Throwable) {}
        return out
    }
}
