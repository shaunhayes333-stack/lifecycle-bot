package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8951 §THE_CAP_THE_LANES_READ_AGAINST_THE_CHAIN — measured, not yet acted on.
 *
 * Owner, 22 Nov 23:53: "we are ... interpreting the data and market caps ... totally wrong". The research rows (21-22 Nov):
 * lanes deciding the same coin in the same second read different caps on 1,240 of 7,968 decisions, 2x or more apart on 64
 * (e.g. $1,987 on three lanes, $6,498 on two); the same coin's cap moved 3x+ between reads within 10 minutes on 299 of 15,741
 * pairs. Every lane band and every proven pocket ($5k-$20k ...) is keyed on that figure, so switching its authority would
 * re-key what the bot trades on today. First the size and direction of the error is measured: on every LIVE stage reading of
 * a coin on an active pump.fun curve, the cap the lanes read ([TokenMetricStageRouter.Snapshot.marketCapUsd], ts.lastMcap)
 * is compared with the cap the chain gives: the curve's own price (its virtual reserves) x the mint account's verified
 * supply (MintSupply8418), in USD. At most one comparison per coin per [GAP_MS_8951]. [statusLine8951] shows the ratio
 * buckets and which side was higher, so the next build moves the authority on measured data.
 */
object CapTruth8951 {
    private const val GAP_MS_8951 = 30_000L
    private const val CURVE_FRESH_MS_8951 = 30_000L
    private val lastAt = ConcurrentHashMap<String, Long>()
    private val buckets = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the bucket of a lanes-read cap [readUsd] against the chain's cap [chainUsd] ("" when either is not a reading). */
    internal fun bucket8951(readUsd: Double, chainUsd: Double): String {
        if (!(readUsd.isFinite() && readUsd > 0.0 && chainUsd.isFinite() && chainUsd > 0.0)) return ""
        val hi = maxOf(readUsd, chainUsd); val lo = minOf(readUsd, chainUsd)
        val r = hi / lo
        val band = when { r < 1.1 -> "agree<1.1x"; r < 2.0 -> "1.1-2x"; r < 10.0 -> "2-10x"; else -> "10x+" }
        return if (band == "agree<1.1x") band else band + (if (readUsd > chainUsd) ":readHigher" else ":readLower")
    }

    /** TokenMetricStageRouter.snapshot (LIVE): compare the cap the lanes read for [ts] with the chain's. */
    fun observe8951(ts: TokenState, readCapUsd: Double, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (mint.length !in 32..44) return
        val last = lastAt[mint] ?: 0L
        if (last > 0L && nowMs - last < GAP_MS_8951) return
        val pxSol = try { CurveReserves7951.priceSolFor7951(mint, nowMs, CURVE_FRESH_MS_8951) } catch (_: Throwable) { 0.0 }
        if (!(pxSol > 0.0)) return
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!(solUsd > 0.0)) return
        val chainUsd = (try { MintSupply8418.capSol8418(mint, pxSol) } catch (_: Throwable) { Double.NaN }) * solUsd
        val b = bucket8951(if (readCapUsd > 0.0) readCapUsd else ts.lastMcap, chainUsd)
        if (b.isEmpty()) return
        if (lastAt.size > 6_000) lastAt.entries.removeIf { nowMs - it.value > GAP_MS_8951 }
        lastAt[mint] = nowMs
        buckets.computeIfAbsent(b) { AtomicLong(0L) }.incrementAndGet()
        // V5.0.8952 — a disagreement is settled by the chain: the curve account is read, and its cap is written (CurveReserves7951)
        if (b != "agree<1.1x") try { OnChainDepth8038.needCurve8039(mint, nowMs) } catch (_: Throwable) {}
    }

    fun statusLine8951(): String =
        "capTruth8951[" + buckets.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            " curveCapWritten8952=${CurveReserves7951.capWritten8952()} rule=the cap the lanes read vs the chain's (active pump.fun " +
            "curve price x verified mint supply), per coin every ${GAP_MS_8951 / 1000}s; a disagreement asks the curve account, whose " +
            "read writes the cap at CHAIN rank (8952)]"
}
