package com.lifecyclebot.engine.truth

import java.math.BigInteger

/**
 * V5.0.9032 §THE_OTHER_SOLANA_POOLS — owner, 26 Nov 21:56: "add the other sol pools". The 21:03 report: the LP reader was
 * asked 402 pools and 174 were of programs it does not read — Orca Whirlpool 33, Raydium CLMM 15 the largest — so their
 * coins waited on an LP proof nothing could give. Both are concentrated-liquidity pools: there is no LP token, each
 * position is an account with a tick range and a liquidity figure, and a position is locked by a record of the venue's own
 * lock program. Layouts from the programs' source (offsets include the 8-byte discriminator):
 *  - Orca Whirlpool (orca-so/whirlpools programs/whirlpool/src/state): Whirlpool (LEN 653) token_mint_a @101, token_mint_b
 *    @181; Position (LEN 216) whirlpool @8, liquidity u128 @72, tick_lower_index i32 @88, tick_upper_index i32 @92;
 *    LockConfig (LEN 241) position @8, whirlpool @72, lock_type u8 @112 (the only type is Permanent: lock_position freezes
 *    the position and there is no unlock).
 *  - Raydium CLMM (raydium-io/raydium-clmm programs/amm/src/states): PoolState (packed) token_mint_0 @73, token_mint_1
 *    @105; PersonalPositionState (LEN 281) pool_id @41, tick_lower_index i32 @73, tick_upper_index i32 @77, liquidity u128
 *    @81. A locked position (Burn & Earn, no withdraw) has a record of the lock program LockrWmn6… (raydium-sdk-V2
 *    clmm/layout.ts LockPositionLayout / LockClPositionLayoutV2: poolId @41, positionId @73 in both versions).
 *
 * The locked share, as the DLMM reader's rule (the smallest locked fraction of any bin): liquidity of different tick ranges
 * is not comparable as one sum, so the price axis is cut at every position boundary and each piece's locked liquidity is
 * taken over all the liquidity in that piece; the pool's locked share is the SMALLEST such fraction over the pieces that
 * hold liquidity. At least that share of the liquidity at every price cannot leave. Pure; the reads are PoolLock8895's.
 */
object ConcentratedLock9032 {
    const val VENUE_WHIRLPOOL_9032 = "ORCA_WHIRLPOOL"
    const val VENUE_CLMM_9032 = "RAYDIUM_CLMM"
    const val CLMM_LOCK_PROGRAM_9032 = "LockrWmn6K5twhz3y9w1dQERbmgSaRkfnTeTKbpofwE"
    internal val WHIRLPOOL_DISC_9032 = intArrayOf(63, 149, 209, 12, 225, 128, 99, 9)   // sha256("account:Whirlpool")[0..8]
    internal const val WHIRLPOOL_SIZE_9032 = 653
    internal const val WHIRLPOOL_POSITION_SIZE_9032 = 216
    internal const val WHIRLPOOL_LOCK_SIZE_9032 = 241
    internal const val CLMM_POOL_MIN_SIZE_9032 = 273
    internal const val CLMM_POSITION_SIZE_9032 = 281
    /** The slices asked of each position: 24 bytes holding its liquidity and its two ticks. */
    internal const val WHIRLPOOL_POSITION_SLICE_AT_9032 = 72
    internal const val CLMM_POSITION_SLICE_AT_9032 = 73
    internal const val POSITION_SLICE_LEN_9032 = 24
    internal const val MAX_POSITIONS_9032 = 400

    class Position9032(val address: String, val lower: Int, val upper: Int, val liquidity: BigInteger)

    private fun i32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) or ((d[off + 2].toInt() and 0xFF) shl 16) or ((d[off + 3].toInt() and 0xFF) shl 24)

    /** Pure: is [data] a Whirlpool account pairing [mint] with a quote mint? */
    fun whirlpoolPool9032(mint: String, data: ByteArray?): Boolean =
        data != null && data.size >= WHIRLPOOL_SIZE_9032 && PoolLock8895.disc(data, WHIRLPOOL_DISC_9032) &&
            PoolLock8895.pairedWithQuote(mint, PoolLock8895.key(data, 101), PoolLock8895.key(data, 181))

    /** Pure: is [data] a Raydium CLMM PoolState pairing [mint] with a quote mint? */
    fun clmmPool9032(mint: String, data: ByteArray?): Boolean =
        data != null && data.size >= CLMM_POOL_MIN_SIZE_9032 && PoolLock8895.disc(data, PoolLock8895.CPMM_POOL_DISC_8925) &&
            PoolLock8895.pairedWithQuote(mint, PoolLock8895.key(data, 73), PoolLock8895.key(data, 105))

    /** Pure: a Whirlpool position from its 24-byte slice at 72 (liquidity u128, tick_lower i32, tick_upper i32). */
    fun whirlpoolPosition9032(address: String, slice: ByteArray?): Position9032? {
        if (address.isBlank() || slice == null || slice.size < POSITION_SLICE_LEN_9032) return null
        return Position9032(address, i32(slice, 16), i32(slice, 20), PoolLock8895.uLe8895(slice, 0, 16))
    }

    /** Pure: a Raydium CLMM position from its 24-byte slice at 73 (tick_lower i32, tick_upper i32, liquidity u128). */
    fun clmmPosition9032(address: String, slice: ByteArray?): Position9032? {
        if (address.isBlank() || slice == null || slice.size < POSITION_SLICE_LEN_9032) return null
        return Position9032(address, i32(slice, 0), i32(slice, 4), PoolLock8895.uLe8895(slice, 8, 16))
    }

    /**
     * Pure: the locked share (%) of a concentrated pool — the smallest locked fraction of any piece of the price axis that
     * holds liquidity; null when no position holds any. [locked] are the addresses of the positions under a lock record.
     */
    fun lockedPct9032(positions: List<Position9032>, locked: Set<String>): Double? {
        val live = positions.filter { it.liquidity.signum() > 0 && it.lower < it.upper }
        if (live.isEmpty()) return null
        val cuts = (live.map { it.lower } + live.map { it.upper }).distinct().sorted()
        var least = 1.0
        for (i in 0 until cuts.size - 1) {
            val a = cuts[i]; val b = cuts[i + 1]
            var all = BigInteger.ZERO; var held = BigInteger.ZERO
            for (p in live) if (p.lower <= a && p.upper >= b) { all = all.add(p.liquidity); if (p.address in locked) held = held.add(p.liquidity) }
            if (all.signum() <= 0) continue
            val f = java.math.BigDecimal(held).divide(java.math.BigDecimal(all), 9, java.math.RoundingMode.DOWN).toDouble()
            if (f < least) least = f
        }
        return least * 100.0
    }
}
