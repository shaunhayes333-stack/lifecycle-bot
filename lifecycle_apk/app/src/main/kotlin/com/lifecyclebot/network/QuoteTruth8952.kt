package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8952 §A_PUMP_CURVE_IS_PRICED_IN_ITS_OWN_QUOTE
 *
 * pump.fun's own upgrades (pump-public-docs, Pump Developer Updates): since 21 May 2026 a coin can be paired with USDC
 * (buy_v2 / sell_v2), and since the depth-1 / depth-2 releases with an earlier pump.fun coin. The BondingCurve fields were
 * renamed virtual_quote_reserves / real_quote_reserves, and the TradeEvent (34 fields, the IDL as Helius parses it) appends
 * quote_mint, quote_amount, virtual_quote_reserves, real_quote_reserves after the variable-length ix_name and shareholders.
 * Every curve consumer in this bot (ChainLaunchFeed8179's decoder, CurveReserves7951's 30-SOL curve, the cap and the stage)
 * read sol_amount and the reserves as lamports: a USDC curve read ~7x cheap, a pump-coin-quoted curve in another coin's units.
 *
 * Now the decoder reads the quote mint ([tail8952]). A SOL curve is unchanged. A USDC curve's amounts are converted with
 * SOL/USD; a pump-coin-quoted curve's with its quote coin's own chain price (its curve, its pool's vaults, or its own
 * converted prints here). The SOL-only curve math (the 30-SOL start, realSol = vSol - 30, the curve account layout) never
 * runs for a non-SOL curve ([nonSol8952]: CurveReserves7951 refuses it); its prints reach the tape at their SOL price.
 * A quote with no price now is not read as SOL: the print is dropped and counted, and the quote coin's curve is asked.
 */
object QuoteTruth8952 {
    const val SOL = "SOL"
    const val USDC = "USDC"
    const val PUMP = "PUMP"
    const val OTHER = "OTHER"
    private const val ZERO_KEY_8952 = "11111111111111111111111111111111"
    private const val QUOTE_FRESH_MS_8952 = 60_000L
    private const val CAP_8952 = 6_000

    /** The quote-mint tail of a TradeEvent (raw base units). */
    data class Tail8952(val quoteMint: String, val quoteAmount: Double, val virtualQuote: Double, val realQuote: Double)

    private fun u64(b: ByteArray, off: Int): Double {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return if (v < 0) v.toDouble() + 18446744073709551616.0 else v.toDouble()
    }

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 3 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    /**
     * Pure: the quote tail of a TradeEvent, or null for an event older than the quote upgrade (or one whose variable part
     * does not parse). Fixed fields end at 258 (… current_sol_volume u64, last_update_timestamp i64); then ix_name (borsh
     * string), mayhem_mode u8, cashback_fee_bps / cashback / buyback_fee_bps / buyback_fee u64 x4, shareholders
     * (u32 count x {address 32, share_bps u16}), then quote_mint(32) quote_amount virtual_quote_reserves real_quote_reserves.
     */
    fun tail8952(b: ByteArray): Tail8952? {
        var at = 258
        if (b.size < at + 4) return null
        val nameLen = u32(b, at); at += 4
        if (nameLen > 64 || at + nameLen > b.size) return null
        at += nameLen.toInt()
        at += 1 + 32
        if (b.size < at + 4) return null
        val holders = u32(b, at); at += 4
        if (holders > 64) return null
        at += (holders * 34).toInt()
        if (b.size < at + 32 + 24) return null
        val quote = try { io.github.novacrypto.base58.Base58.base58Encode(b.copyOfRange(at, at + 32)) } catch (_: Throwable) { return null }
        return Tail8952(quote, u64(b, at + 32), u64(b, at + 40), u64(b, at + 48))
    }

    /** Pure: the quote kind of [quoteMint] (a default key on a legacy coin is SOL). */
    fun kind8952(quoteMint: String): String = when {
        quoteMint.isBlank() || quoteMint == ZERO_KEY_8952 || quoteMint == ChainLaunchFeed8179.WSOL_8179 -> SOL
        quoteMint == ChainLaunchFeed8179.USDC_8179 -> USDC
        PumpCurveKeys7269.isPumpMint8658(quoteMint) -> PUMP
        else -> OTHER
    }

    /** Pure: the decimals of a quote kind (USDC and pump.fun coins: 6), -1 when not known. */
    fun decimals8952(kind: String): Int = when (kind) { SOL -> 9; USDC, PUMP -> 6; else -> -1 }

    private val nonSol = ConcurrentHashMap<String, String>()            // base mint -> quote kind
    private val convertedPx = ConcurrentHashMap<String, Pair<Double, Long>>() // non-SOL base mint -> (SOL per token, at)
    private val byKind = ConcurrentHashMap<String, AtomicLong>()
    private val legacyLayout = AtomicLong(0L)
    private val quoteLayout = AtomicLong(0L)
    private val curveWritesRefused = AtomicLong(0L)

    fun noteLayout8952(legacy: Boolean) { (if (legacy) legacyLayout else quoteLayout).incrementAndGet() }

    fun noteQuote8952(kind: String, priced: Boolean) {
        byKind.computeIfAbsent("$kind:${if (priced) "priced" else "unpriced"}") { AtomicLong(0L) }.incrementAndGet()
    }

    /** A decoded event of [mint] named a non-SOL [kind] quote; its converted price [pxSol] (SOL per token) is kept. */
    fun noteNonSol8952(mint: String, kind: String, pxSol: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || kind == SOL) return
        if (nonSol.size > CAP_8952) nonSol.keys.take(CAP_8952 / 10).forEach { nonSol.remove(it) }
        nonSol[mint] = kind
        if (pxSol.isFinite() && pxSol > 0.0) {
            if (convertedPx.size > CAP_8952) convertedPx.entries.sortedBy { it.value.second }.take(CAP_8952 / 10).forEach { convertedPx.remove(it.key) }
            convertedPx[mint] = pxSol to nowMs
        }
    }

    /** CurveReserves7951 and every SOL-curve reader: [mint]'s curve is not quoted in SOL (the 30-SOL math does not apply). */
    fun nonSol8952(mint: String): Boolean = nonSol.containsKey(mint.trim())

    /** CurveReserves7951: a SOL-curve write for a non-SOL curve was refused. */
    fun noteCurveRefused8952() { curveWritesRefused.incrementAndGet() }

    /** SOL per whole unit of [quoteMint] now, or NaN (never guessed). An unpriced pump.fun quote's curve is asked. */
    fun quoteSolLive8952(quoteMint: String, nowMs: Long = System.currentTimeMillis()): Double {
        return when (kind8952(quoteMint)) {
            USDC -> {
                val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                if (solUsd > 0.0) com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(1.0, solUsd) else Double.NaN   // one USDC = 1 USD
            }
            PUMP -> {
                val curve = try { com.lifecyclebot.engine.truth.CurveReserves7951.priceSolFor7951(quoteMint, nowMs, QUOTE_FRESH_MS_8952) } catch (_: Throwable) { 0.0 }
                if (curve.isFinite() && curve > 0.0) return curve
                convertedPx[quoteMint]?.takeIf { nowMs - it.second in 0L..QUOTE_FRESH_MS_8952 }?.first?.let { return it }
                val pool = try { com.lifecyclebot.engine.truth.OnChainDepth8038.freshRead8198(quoteMint, QUOTE_FRESH_MS_8952, nowMs) } catch (_: Throwable) { null }
                if (pool != null) return pool.quoteSol / pool.baseTokens
                try { com.lifecyclebot.engine.truth.OnChainDepth8038.needCurve8039(quoteMint, nowMs) } catch (_: Throwable) {}
                Double.NaN
            }
            else -> Double.NaN
        }
    }

    fun statusLine8952(): String =
        "quoteTruth8952[events{quoteLayout=${quoteLayout.get()} legacyLayout=${legacyLayout.get()}} nonSol{" +
            byKind.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            "} nonSolMints=${nonSol.size} solCurveWritesRefused=${curveWritesRefused.get()} rule=a pump.fun curve is read in its own " +
            "quote: USDC via SOL/USD, a pump.fun quote coin via its own chain price; the 30-SOL curve math never runs on a non-SOL " +
            "curve; an unpriced quote is dropped, never read as SOL]"

    internal fun resetForTest8952() {
        nonSol.clear(); convertedPx.clear(); byKind.clear(); legacyLayout.set(0L); quoteLayout.set(0L); curveWritesRefused.set(0L)
    }
}
