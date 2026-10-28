package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §THE_PRICE_OF_A_CONCENTRATED_POOL_IS_IN_ITS_STATE — a concentrated pool's vault ratio is not its price
 * (liquidity sits in ticks / bins), so OnChainDepth8038 reads those venues as depth only. Their spot price IS in the pool
 * account, and is decoded here from the program sources' own layouts (offsets include the 8-byte Anchor discriminator):
 *
 *  Raydium CLMM  PoolState (raydium-io/raydium-clmm programs/amm/src/states/pool.rs, repr(C, packed)):
 *     bump u8 @8, amm_config @9, owner @41, token_mint_0 @73, token_mint_1 @105, token_vault_0 @137, token_vault_1 @169,
 *     observation_key @201, mint_decimals_0 u8 @233, mint_decimals_1 u8 @234, tick_spacing u16 @235, liquidity u128 @237,
 *     sqrt_price_x64 u128 @253.   price(token1 per token0, ui) = (sqrt / 2^64)^2 x 10^(dec0 - dec1)
 *  Orca Whirlpool (orca-so/whirlpools programs/whirlpool/src/state/whirlpool.rs):
 *     whirlpools_config @8, bump @40, tick_spacing u16 @41, fee_tier_index_seed @43, fee_rate u16 @45,
 *     protocol_fee_rate u16 @47, liquidity u128 @49, sqrt_price u128 @65, tick_current_index i32 @81, fees owed 2x u64
 *     @85, token_mint_a @101, token_vault_a @133, fee_growth_global_a u128 @165, token_mint_b @181, token_vault_b @213.
 *     price(b per a, ui) = (sqrt / 2^64)^2 x 10^(decA - decB)   (decimals are not stored: read from the vaults)
 *  Meteora DLMM  LbPair (MeteoraAg/dlmm-sdk idls/dlmm.json): StaticParameters 32 @8, VariableParameters 32 @40,
 *     bump_seed @72, bin_step_seed @73, pair_type @75, active_id i32 @76, bin_step u16 @80, status @82, ...,
 *     token_x_mint @88, token_y_mint @120, reserve_x @152, reserve_y @184.
 *     price(y per x, ui) = (1 + bin_step / 10_000)^active_id x 10^(decX - decY)
 *
 * Every decode first proves the layout: the mints at their offsets must be exactly {coin, WSOL} as the vaults reported
 * them, and on CLMM the stored decimals must equal the vaults' — a wrong layout yields NO price, never a wrong one.
 * Sell-out / price impact is NOT derived: that needs the liquidity in the tick arrays / bin arrays around the current
 * price, which these reads do not fetch. It stays UNKNOWN.
 */
object ConcentratedPrice8418 {
    private val TWO_64: java.math.BigDecimal = java.math.BigDecimal(BigInteger.ONE.shiftLeft(64))

    /** A chain price: SOL per one whole coin, the program it was decoded from, and when it was observed. */
    data class ChainPrice8418(val solPerCoin: Double, val source: String, val atMs: Long)

    // ── little-endian readers (pure) ──

    fun u128le8418(data: ByteArray, off: Int): BigInteger? {
        if (off < 0 || data.size < off + 16) return null
        val be = ByteArray(17)   // leading 0 keeps it unsigned
        for (i in 0 until 16) be[16 - i] = data[off + i]
        return BigInteger(be)
    }

    fun i32le8418(data: ByteArray, off: Int): Int? {
        if (off < 0 || data.size < off + 4) return null
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or ((data[off + 3].toInt() and 0xFF) shl 24)
    }

    fun u16le8418(data: ByteArray, off: Int): Int? {
        if (off < 0 || data.size < off + 2) return null
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun key(data: ByteArray, off: Int): String? {
        if (off < 0 || data.size < off + 32) return null
        return try { io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(off, off + 32)) } catch (_: Throwable) { null }
    }

    /** Pure: (sqrtX64 / 2^64)^2 — the raw (base-unit) price of token 1 / b in token 0 / a. Null when not positive. */
    fun rawPriceFromSqrtX64_8418(sqrtX64: BigInteger): Double? {
        if (sqrtX64.signum() <= 0) return null
        val r = java.math.BigDecimal(sqrtX64).divide(TWO_64, java.math.MathContext.DECIMAL64).toDouble()
        val p = r * r
        return if (p.isFinite() && p > 0.0) p else null
    }

    /** Pure: (1 + binStep / 10 000)^activeId — the raw price of y in x for a DLMM bin. */
    fun rawPriceFromBin8418(activeId: Int, binStep: Int): Double? {
        if (binStep <= 0) return null
        val p = Math.pow(1.0 + binStep / 10_000.0, activeId.toDouble())
        return if (p.isFinite() && p > 0.0) p else null
    }

    /**
     * Pure: SOL per whole coin from a pool price of "token1 per token0" in raw units, given which side is the coin.
     * ui price (token1 per token0) = raw x 10^(dec0 - dec1).
     */
    fun solPerCoin8418(rawPrice1Per0: Double, dec0: Int, dec1: Int, coinIsToken0: Boolean): Double? {
        if (!(rawPrice1Per0.isFinite() && rawPrice1Per0 > 0.0) || dec0 !in 0..18 || dec1 !in 0..18) return null
        val ui = rawPrice1Per0 * Math.pow(10.0, (dec0 - dec1).toDouble())
        val out = if (coinIsToken0) ui else 1.0 / ui
        return if (out.isFinite() && out > 0.0) out else null
    }

    /**
     * Pure: the coin's spot price in SOL from a concentrated pool account [data] owned by [owner], or null. [coin] and
     * [coinDecimals] / [solDecimals] come from the jsonParsed vault accounts (the same read).
     */
    fun decode8418(owner: String, data: ByteArray, coin: String, coinDecimals: Int, solDecimals: Int): Double? {
        val wsol = OnChainDepth8038.WSOL
        return when (owner) {
            OnChainDepth8038.RAYDIUM_CLMM_8418 -> {
                val m0 = key(data, 73) ?: return null
                val m1 = key(data, 105) ?: return null
                if (setOf(m0, m1) != setOf(coin, wsol) || m0 == m1) return null
                val d0 = data.getOrNull(233)?.toInt()?.and(0xFF) ?: return null
                val d1 = data.getOrNull(234)?.toInt()?.and(0xFF) ?: return null
                val coinIs0 = m0 == coin
                // Layout proof: the stored decimals are the vaults' decimals.
                if ((if (coinIs0) d0 else d1) != coinDecimals || (if (coinIs0) d1 else d0) != solDecimals) return null
                val raw = rawPriceFromSqrtX64_8418(u128le8418(data, 253) ?: return null) ?: return null
                solPerCoin8418(raw, d0, d1, coinIs0)
            }
            OnChainDepth8038.ORCA_WHIRLPOOL_8418 -> {
                val ma = key(data, 101) ?: return null
                val mb = key(data, 181) ?: return null
                if (setOf(ma, mb) != setOf(coin, wsol) || ma == mb) return null
                val coinIsA = ma == coin
                val da = if (coinIsA) coinDecimals else solDecimals
                val db = if (coinIsA) solDecimals else coinDecimals
                val raw = rawPriceFromSqrtX64_8418(u128le8418(data, 65) ?: return null) ?: return null
                solPerCoin8418(raw, da, db, coinIsA)
            }
            OnChainDepth8038.METEORA_DLMM_8418 -> {
                val mx = key(data, 88) ?: return null
                val my = key(data, 120) ?: return null
                if (setOf(mx, my) != setOf(coin, wsol) || mx == my) return null
                val coinIsX = mx == coin
                val dx = if (coinIsX) coinDecimals else solDecimals
                val dy = if (coinIsX) solDecimals else coinDecimals
                val raw = rawPriceFromBin8418(i32le8418(data, 76) ?: return null, u16le8418(data, 80) ?: return null) ?: return null
                solPerCoin8418(raw, dx, dy, coinIsX)
            }
            else -> null
        }
    }

    /** Pure: the source tag a decoded price carries into every reader. */
    fun sourceOf8418(owner: String): String = when (owner) {
        OnChainDepth8038.RAYDIUM_CLMM_8418 -> "CHAIN_RAYDIUM_CLMM_SQRT_8418"
        OnChainDepth8038.ORCA_WHIRLPOOL_8418 -> "CHAIN_ORCA_WHIRLPOOL_SQRT_8418"
        OnChainDepth8038.METEORA_DLMM_8418 -> "CHAIN_METEORA_DLMM_BIN_8418"
        else -> "CHAIN_UNKNOWN_8418"
    }

    // ── observed prices (mint -> latest decode) ──

    private val prices = ConcurrentHashMap<String, ChainPrice8418>()

    fun record8418(mint: String, solPerCoin: Double, owner: String, atMs: Long) {
        if (mint.isBlank() || !(solPerCoin.isFinite() && solPerCoin > 0.0)) return
        prices[mint] = ChainPrice8418(solPerCoin, sourceOf8418(owner), atMs)
        if (prices.size > 3_000) prices.entries.sortedBy { it.value.atMs }.take(300).forEach { prices.remove(it.key) }
    }

    /** The decoded chain price of [mint] at most [maxAgeMs] old, or null. Never requests. */
    fun fresh8418(mint: String, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): ChainPrice8418? =
        prices[mint]?.takeIf { nowMs - it.atMs in 0L..maxAgeMs }

    fun size8418(): Int = prices.size

    internal fun resetForTests8418() { prices.clear() }
}
