package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8895 §THE_LOCK_IS_READ_ON_CHAIN — owner, 20 Nov: "if the liquidity lock cant be proven can we establish safety checks
 * another way?", then "do you consider trading them safe? a dev can bundle hold across multiple wallets to rug". Answer agreed:
 * an unlocked pool is not bought; the lock is PROVEN from the pool's own accounts where the provider (RugCheck) cannot read it.
 *
 * 17-20 Nov edge dataset, the coins the bot's own record says pay (not pump.fun, $5k-$20k cap, V3 >= 23, forecast >= +10 %,
 * one row per coin): 46 coins, +42.8 % +-10.2, every one under five minutes old; 42 had NO LP reading at the decision, 4 read
 * under 70 %. The LP rule (LiquidityOwnership8522, 70 % locked) takes its pool lock only from RugCheck's report, which has
 * nothing for a pool minutes old, so these coins waited LP_OWNERSHIP_PROOF_PENDING_8522 for their whole first hour.
 * The Meteora scan (MeteoraPools8703) brings exactly these pools: DAMM v2's newest (where Meteora DBC launchpads — Bags,
 * Believe, Jupiter Studio — graduate) and DLMM.
 *
 * The lock, read from the chain, never from a label:
 *  - Meteora DAMM v2 (cp-amm): the pool account carries `liquidity` and `permanent_lock_liquidity` (MeteoraAg/damm-v2
 *    programs/cp-amm/src/state/pool.rs, zero-copy, const_assert_eq Pool::INIT_SPACE = 1104; PoolFeesStruct 160 per fee.rs).
 *    Offsets with the 8-byte discriminator: token_a_mint @168, token_b_mint @200, liquidity u128 @360,
 *    permanent_lock_liquidity u128 @552. A permanent lock cannot be withdrawn (ix_permanent_lock_position accumulates it into
 *    the pool); the locked share is permanent / liquidity. Time-vested liquidity is NOT counted (it unlocks).
 *  - Meteora DLMM: no LP token. Each liquidity position is an account its owner can withdraw, unless it carries a
 *    `lock_release_point` (MeteoraAg/dlmm-sdk idls/dlmm.json PositionV2: lb_pair @8, liquidity_shares 70 x u128 @72,
 *    lower_bin_id @7912, upper_bin_id @7916, lock_release_point u64 @7992; an extended position appends 112-byte bin entries
 *    after 8 + 8112, liquidity_share first). The point is a slot or a unix time by the pair's activation_type (LbPair @86:
 *    0 slot, 1 timestamp), compared with the chain's own Clock — the SDK's own locked-position test (lock_release_point >
 *    current point + duration). Shares of one bin are comparable, shares of different bins are not, so the locked share is
 *    the SMALLEST locked fraction over every bin that holds liquidity: at least that share of every bin, so of the whole
 *    pool, cannot leave before the lock releases. A position read is by the program's own discriminator and the pair
 *    (no size filter, so extended positions are read too); a pair with more than [MAX_POSITIONS_8895] positions is not
 *    proven.
 * A lock must outlast the hold: [LOCK_MARGIN_S_8895] (24 h) past now, in slots at 0.4 s for a slot-clocked pair.
 *
 * What it changes (LiquidityOwnership8522.assess): an LP ownership that would be PENDING (unread) is PROVEN when this read
 * shows the coin's own pool at least 70 % locked. It never makes a pool BLOCKED and never waives a block: a provider report
 * of unlocked or under-locked liquidity still refuses, as does everything else at the buy door. An unlocked or unreadable
 * pool stays PENDING — exactly as before this file existed. Reads: keyless nodes first for accounts (KeylessRpc8359), Helius
 * for getProgramAccounts (10 credits); one in flight per pool, a pool re-read at most every [REREAD_MS_8895], a pool of
 * another program skipped for 30 minutes. Nothing here admits, sizes or trades.
 *
 * Superseded in part by V5.0.8937 (owner, 22 Nov 14:24: "a smarter strategy"): a pool this reader proves 70 %+ locked now
 * stands over the provider's LOWER figure for that same pool (LiquidityOwnership8522.chainProven8937, TokenSafetyChecker
 * lockedAt8937), and answers the provider's named unlocked finding when every pool with depth is so proven. Still: this reader
 * never blocks, its lower figure never replaces the provider's (it counts no third-party locker), and 70 % is unchanged.
 */
object PoolLock8895 {
    private const val DAMM_V2_PROGRAM_8895 = OnChainDepth8038.METEORA_DAMM_V2_8418
    private const val DLMM_PROGRAM_8895 = OnChainDepth8038.METEORA_DLMM_8418
    const val VENUE_DAMM_V2_8895 = "METEORA_DAMM_V2"
    private const val VENUE_DLMM_8895 = "METEORA_DLMM"
    const val PROVEN_REASON_8895 = "LP_LOCK_CHAIN_PROVEN_8895"
    internal const val MIN_LOCKED_PCT_8895 = 70.0
    internal val DAMM_V2_POOL_DISC_8895 = intArrayOf(241, 154, 109, 4, 17, 177, 109, 188)      // sha256("account:Pool")[0..8]
    internal val DLMM_PAIR_DISC_8895 = intArrayOf(33, 11, 49, 98, 181, 101, 177, 13)           // sha256("account:LbPair")[0..8]
    internal val DLMM_POSITION_DISC_8895 = intArrayOf(117, 176, 212, 199, 245, 180, 133, 182)  // sha256("account:PositionV2")[0..8]
    internal const val DAMM_V2_SIZE_8895 = 8 + 1104
    internal const val DLMM_PAIR_MIN_SIZE_8895 = 8 + 896
    internal const val DLMM_POSITION_BASE_8895 = 8 + 8112
    internal const val DLMM_BIN_ENTRY_8895 = 112
    internal const val DLMM_BASE_BINS_8895 = 70
    internal const val MAX_POSITIONS_8895 = 24
    internal const val LOCK_MARGIN_S_8895 = 24L * 3600L
    internal const val LOCK_MARGIN_SLOTS_8895 = LOCK_MARGIN_S_8895 * 5L / 2L   // 0.4 s a slot
    private const val CLOCK_SYSVAR_8895 = "SysvarC1ock11111111111111111111111111111111"
    private const val PROOF_MS_8895 = 60_000L
    private const val REREAD_MS_8895 = 30_000L
    private const val OTHER_RETRY_MS_8895 = 30L * 60_000L
    private const val TIMEOUT_S_8895 = 4L

    // ── V5.0.8925 §THE_REST_OF_THE_LOCK_ON_CHAIN — owner, 22 Nov 01:06-01:11: "why aren't we simply reading the data we need on
    //    chain?", "rug check is good yeah?", "go". 21 Nov 23:11: the paying-cell coins the buy door left waiting on an unread LP
    //    proof paid +33.7 % (n94; +28.7 % / +36.5 % in the two reports before), the proven ones -0.1 %; this reader asked 13 pools
    //    and found every DAMM v2 one UNLOCKED (8) and 5 of a program it did not read. Two exact gaps, from the programs' own source:
    //  - Meteora DAMM v2 VESTING. Meteora DBC graduates a coin into DAMM v2 and locks its LP with `permanent_lock_position` AND
    //    `lock_inner_position` (dynamic-bonding-curve migrate_damm_v2_initialize_pool.rs: a config's vested share is a vesting
    //    schedule on the position itself). 8895 counted only the permanent part, so a pool vested past the hold read unlocked.
    //    damm-v2 state/position.rs (INIT_SPACE 400) with the discriminator: pool @8, unlocked_liquidity u128 @152,
    //    vested_liquidity @168, permanent_locked_liquidity @184, inner_vesting @312 (state/vesting.rs InnerVesting, 80 bytes:
    //    cliff_point u64 +0, period_frequency u64 +8, cliff_unlock_liquidity u128 +16, liquidity_per_period u128 +32,
    //    total_released_liquidity u128 +48, number_of_period u16 +64). An external Vesting account (176 + 8): position @8,
    //    inner_vesting @40. What a schedule still holds at a point is its total less InnerVesting::get_max_unlocked_liquidity at
    //    that point (0 before the cliff; the cliff amount plus whole periods after it); the point is the pool's own clock
    //    (Pool activation_type @480: 0 slot, 1 timestamp) [LOCK_MARGIN_S_8895] past now, as DLMM's locks are read. Locked share
    //    = (each position's permanent + what its schedules hold past the hold) / pool liquidity.
    //  - Raydium CPMM, where Raydium LaunchLab graduates: the LP is burned (an SPL burn lowers the LP mint's supply; PoolState
    //    .lp_supply does not move, raydium-cp-swap deposit.rs / withdraw.rs), and LaunchLab's platform share is locked through
    //    Burn & Earn, which holds it in the LP mint's token account of the lock authority LOCK_CPMM_AUTH (raydium-sdk-V2
    //    cpmm/instruction.ts makeCpmmLockInstruction: lockLpVault = ATA(lockAuthProgram, lpMint)); a Burn & Earn lock has no
    //    withdraw. PoolState (packed, LEN 637): lp_mint @136, token_0_mint @168, token_1_mint @200, lp_supply u64 @333. Locked
    //    share = ((lp_supply - mint supply) + LP held by the lock authority) / lp_supply; any other locker is not counted.
    //  Unchanged: >= 70 % turns an unread LP proof into PROVEN and nothing else; a lower or unreadable read stays unproven and is
    //  not bought; a provider's finding of unlocked liquidity still refuses; RugCheck stays required. ──
    private const val CPMM_PROGRAM_8925 = "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C"
    // V5.0.8926 — Raydium AMM v4 (raydium-amm program/src/state.rs AmmInfo, repr(C, packed), 752 bytes): coin_vault_mint @400,
    // pc_vault_mint @432, lp_mint @464, lp_amount u64 @720 (the LP outstanding; an SPL burn lowers the LP mint's supply only),
    // so the burned share is (lp_amount - LP mint supply) / lp_amount, as CPMM's (its LP is burned, not Burn & Earn locked).
    private const val AMM_V4_PROGRAM_8926 = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"
    private const val VENUE_AMM_V4_8926 = "RAYDIUM_AMM_V4"
    private const val CPMM_LOCK_AUTH_8925 = "3f7GcQFG397GAaEnv51zR6tsTVihYRydnydDD1cXekxH"
    const val VENUE_CPMM_8925 = "RAYDIUM_CPMM"
    internal val CPMM_POOL_DISC_8925 = intArrayOf(247, 237, 227, 245, 215, 195, 222, 70)         // sha256("account:PoolState")[0..8]
    internal const val CPMM_POOL_SIZE_8925 = 637
    internal val DAMM_V2_POSITION_DISC_8925 = intArrayOf(170, 188, 143, 228, 122, 64, 247, 208)   // sha256("account:Position")[0..8]
    internal val DAMM_V2_VESTING_DISC_8925 = intArrayOf(100, 149, 66, 138, 95, 200, 128, 241)     // sha256("account:Vesting")[0..8]
    internal const val DAMM_V2_POSITION_SIZE_8925 = 8 + 400
    internal const val DAMM_V2_VESTING_SIZE_8925 = 8 + 176
    private const val MAX_EXTERNAL_VESTING_READS_8925 = 4
    private const val SCHEDULE_CACHE_MS_8925 = 5L * 60_000L
    // ── V5.0.8937 §THE_LOCK_IS_THE_POOLS_OWN — owner, 22 Nov 14:24: "ive asked you to research the lp lock and come up with a
    //    smarter strategy". PumpSwap, where every pump.fun coin graduates: pump.fun's migrate creates the canonical pool (index 0)
    //    and burns every LP token it mints ("All LP tokens minted during migration are immediately burned" — pump-fun/
    //    pump-public-docs, Migration to AMM; pump.fun help: "Liquidity cannot be removed manually"), so the migrated liquidity
    //    can never be withdrawn; a later deposit mints LP its depositor may withdraw. Pool (pump_amm IDL, Anchor
    //    sha256("account:Pool")): pool_bump u8 @8, index u16 @9, creator @11, base_mint @43, quote_mint @75, lp_mint @107,
    //    pool_base_token_account @139, pool_quote_token_account @171 (the vault offsets OnChainDepth8038 already reads),
    //    lp_supply u64 @203 (minted LP; an SPL burn lowers the LP mint's supply only). Burned share = (lp_supply - LP mint
    //    supply) / lp_supply, as Raydium AMM v4's. Pump.fun mints were skipped here until now (8895 read Meteora only).
    private const val PUMP_AMM_PROGRAM_8937 = OnChainDepth8038.PUMP_AMM
    const val VENUE_PUMPSWAP_8937 = "PUMPSWAP"
    internal const val PUMPSWAP_POOL_MIN_SIZE_8937 = 211
    /** A lock the safety report may carry (burned / permanent LP only grows): up to 10 minutes old. */
    internal const val REPORT_PROOF_MS_8937 = 10L * 60_000L
    private val QUOTES_8895 = setOf(OnChainDepth8038.WSOL, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB")

    /** One read: [mint]'s [pool] on [venue] had [lockedPct] % of its liquidity locked at [atMs] ([detail] says how). */
    data class Lock8895(val mint: String, val pool: String, val venue: String, val lockedPct: Double, val atMs: Long, val detail: String)

    /** One DLMM position: its bin range, liquidity share per bin (lower first) and lock release point. */
    internal data class Position8895(val lowerBin: Int, val upperBin: Int, val shares: List<BigInteger>, val lockReleasePoint: Long)

    // ── pure ──

    internal fun disc(data: ByteArray, d: IntArray): Boolean = data.size >= 8 && (0 until 8).all { (data[it].toInt() and 0xFF) == d[it] }

    internal fun key(data: ByteArray, off: Int): String? =
        if (data.size < off + 32) null
        else try { io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(off, off + 32)) } catch (_: Throwable) { null }

    /** Pure: unsigned little-endian integer of [len] bytes at [off]. */
    internal fun uLe8895(data: ByteArray, off: Int, len: Int): BigInteger {
        val be = ByteArray(len + 1)   // a leading zero byte keeps it unsigned
        for (i in 0 until len) be[len - i] = data[off + i]
        return BigInteger(be)
    }

    /** Pure: a u64 at [off] as a Long, a value past Long.MAX_VALUE (a lock "forever") held at Long.MAX_VALUE, never wrapped negative. */
    internal fun u64Clamp8895(data: ByteArray, off: Int): Long =
        uLe8895(data, off, 8).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()

    private fun i32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or ((data[off + 3].toInt() and 0xFF) shl 24)

    /** Pure: is the pair ([a], [b]) [mint] against a quote mint (wrapped SOL, USDC, USDT), in either order? */
    internal fun pairedWithQuote(mint: String, a: String?, b: String?): Boolean =
        (a == mint && b in QUOTES_8895) || (b == mint && a in QUOTES_8895)

    /** Pure: the permanently locked share (%) of a DAMM v2 pool account [data] of [mint]; null when it is not one. */
    fun dammV2LockedPct8895(mint: String, data: ByteArray?): Double? {
        if (data == null || data.size < DAMM_V2_SIZE_8895 || !disc(data, DAMM_V2_POOL_DISC_8895)) return null
        if (!pairedWithQuote(mint, key(data, 168), key(data, 200))) return null
        val liquidity = uLe8895(data, 360, 16)
        val locked = uLe8895(data, 552, 16)
        if (liquidity.signum() <= 0 || locked > liquidity) return null
        return locked.multiply(BigInteger.valueOf(10_000L)).divide(liquidity).toDouble() / 100.0
    }

    /** V5.0.8925 — one DAMM v2 vesting schedule (InnerVesting). */
    internal data class Vesting8925(val cliffPoint: Long, val periodFrequency: Long, val cliffUnlock: BigInteger,
                                    val perPeriod: BigInteger, val released: BigInteger, val periods: Int)

    /** V5.0.8925 — one DAMM v2 position: its permanent lock, remaining vested liquidity and its own schedule (null when empty). */
    internal data class DammPosition8925(val address: String, val permanent: BigInteger, val vested: BigInteger, val inner: Vesting8925?)

    /** Pure (V5.0.8925): the InnerVesting at [off] of [data]; null when every byte of it is zero (InnerVesting::is_empty). */
    internal fun vesting8925(data: ByteArray, off: Int): Vesting8925? {
        if (data.size < off + 80 || (off until off + 80).all { data[it].toInt() == 0 }) return null
        val periods = (data[off + 64].toInt() and 0xFF) or ((data[off + 65].toInt() and 0xFF) shl 8)
        return Vesting8925(u64Clamp8895(data, off), u64Clamp8895(data, off + 8), uLe8895(data, off + 16, 16),
            uLe8895(data, off + 32, 16), uLe8895(data, off + 48, 16), periods)
    }

    /** Pure (V5.0.8925): what [v] still holds at [point] — its total less InnerVesting::get_max_unlocked_liquidity(point). */
    internal fun heldAt8925(v: Vesting8925, point: Long): BigInteger {
        val total = v.cliffUnlock.add(v.perPeriod.multiply(BigInteger.valueOf(v.periods.toLong())))
        val unlocked = when {
            point < v.cliffPoint -> BigInteger.ZERO
            v.periodFrequency == 0L || v.perPeriod.signum() == 0 -> v.cliffUnlock
            else -> {
                val p = ((point - v.cliffPoint) / v.periodFrequency).coerceAtMost(v.periods.toLong())
                v.cliffUnlock.add(BigInteger.valueOf(p).multiply(v.perPeriod))
            }
        }
        return total.subtract(unlocked).max(BigInteger.ZERO)
    }

    /** Pure (V5.0.8925): what [v] has not yet released (its total less total_released_liquidity). */
    internal fun remaining8925(v: Vesting8925): BigInteger =
        v.cliffUnlock.add(v.perPeriod.multiply(BigInteger.valueOf(v.periods.toLong()))).subtract(v.released).max(BigInteger.ZERO)

    /** Pure (V5.0.8925): a DAMM v2 Position account [data] of [pool] at [address]; null when it is not one. */
    internal fun dammPosition8925(pool: String, address: String, data: ByteArray?): DammPosition8925? {
        if (data == null || data.size < DAMM_V2_POSITION_SIZE_8925 || !disc(data, DAMM_V2_POSITION_DISC_8925)) return null
        if (key(data, 8) != pool) return null
        return DammPosition8925(address, uLe8895(data, 184, 16), uLe8895(data, 168, 16), vesting8925(data, 312))
    }

    /** Pure (V5.0.8925): an external DAMM v2 Vesting account [data] of [position]; null when it is not one. */
    internal fun dammVesting8925(position: String, data: ByteArray?): Vesting8925? {
        if (data == null || data.size < DAMM_V2_VESTING_SIZE_8925 || !disc(data, DAMM_V2_VESTING_DISC_8925)) return null
        if (key(data, 8) != position) return null
        return vesting8925(data, 40)
    }

    /**
     * Pure (V5.0.8925): the share (%) of a DAMM v2 pool's [liquidity] that cannot leave before [point]: each position's permanent
     * lock plus what its own and its [external] schedules (position address -> schedules) still hold then, never more than the
     * position's remaining vested liquidity. Null for an empty pool or a sum past the pool's liquidity (a misread).
     */
    internal fun dammLockedPct8925(liquidity: BigInteger, positions: List<DammPosition8925>, external: Map<String, List<Vesting8925>>, point: Long): Double? {
        if (liquidity.signum() <= 0) return null
        var locked = BigInteger.ZERO
        for (p in positions) {
            var vestHeld = p.inner?.let { heldAt8925(it, point) } ?: BigInteger.ZERO
            for (v in external[p.address].orEmpty()) vestHeld = vestHeld.add(heldAt8925(v, point))
            locked = locked.add(p.permanent).add(vestHeld.min(p.vested))
        }
        if (locked > liquidity) return null
        return locked.multiply(BigInteger.valueOf(10_000L)).divide(liquidity).toDouble() / 100.0
    }

    /** Pure (V5.0.8925): the activation type of a DAMM v2 pool account [data] (0 slot, 1 timestamp); null when not a pool. */
    internal fun dammActivationType8925(data: ByteArray?): Int? {
        if (data == null || data.size < DAMM_V2_SIZE_8895 || !disc(data, DAMM_V2_POOL_DISC_8895)) return null
        val t = data[480].toInt() and 0xFF
        return if (t == 0 || t == 1) t else null
    }

    /** Pure (V5.0.8925): (LP mint, lp_supply) of a Raydium CPMM PoolState [data] of [mint]; null when it is not one. */
    internal fun cpmmPool8925(mint: String, data: ByteArray?): Pair<String, BigInteger>? {
        if (data == null || data.size < CPMM_POOL_SIZE_8925 || !disc(data, CPMM_POOL_DISC_8925)) return null
        if (!pairedWithQuote(mint, key(data, 168), key(data, 200))) return null
        val lp = key(data, 136) ?: return null
        return lp to uLe8895(data, 333, 8)
    }

    /** Pure (V5.0.8926): (LP mint, lp_amount) of a Raydium AMM v4 AmmInfo [data] of [mint]; null when it is not one. */
    internal fun ammV4Pool8926(mint: String, data: ByteArray?): Pair<String, BigInteger>? {
        if (data == null || data.size != LpPull8926.AMM_V4_SIZE_8926) return null
        if (!pairedWithQuote(mint, key(data, 400), key(data, 432))) return null
        val lp = key(data, 464) ?: return null
        return lp to uLe8895(data, 720, 8)
    }

    /** Pure (V5.0.8937): (LP mint, lp_supply) of a PumpSwap Pool [data] of [mint]; null when it is not one. */
    internal fun pumpSwapPool8937(mint: String, data: ByteArray?): Pair<String, BigInteger>? {
        if (data == null || data.size < PUMPSWAP_POOL_MIN_SIZE_8937 || !disc(data, DAMM_V2_POOL_DISC_8895)) return null
        if (!pairedWithQuote(mint, key(data, 43), key(data, 75))) return null
        val lp = key(data, 107) ?: return null
        return lp to uLe8895(data, 203, 8)
    }

    /** Pure (V5.0.8925): an SPL mint account's supply (u64 @36); null when it is too short. */
    internal fun mintSupply8925(data: ByteArray?): BigInteger? = if (data == null || data.size < 82) null else uLe8895(data, 36, 8)

    /** Pure (V5.0.8925): an SPL token account's (mint, owner, amount); null when it is too short. */
    internal fun tokenAccount8925(data: ByteArray?): Triple<String, String, BigInteger>? {
        if (data == null || data.size < 165) return null
        return Triple(key(data, 0) ?: return null, key(data, 32) ?: return null, uLe8895(data, 64, 8))
    }

    /**
     * Pure (V5.0.8925): a CPMM pool's locked share (%): LP burned (lp_supply less the LP mint's supply) plus LP the Burn & Earn
     * authority holds, over lp_supply. Null for an empty pool, a mint supply past lp_supply, or a lock past the supply (misreads).
     */
    internal fun cpmmLockedPct8925(lpSupply: BigInteger, mintSupply: BigInteger, lockedInVault: BigInteger): Double? {
        if (lpSupply.signum() <= 0 || mintSupply > lpSupply || lockedInVault.signum() < 0 || lockedInVault > mintSupply) return null
        val locked = lpSupply.subtract(mintSupply).add(lockedInVault)
        return locked.multiply(BigInteger.valueOf(10_000L)).divide(lpSupply).toDouble() / 100.0
    }

    /** Pure: the activation type of a DLMM LbPair account [data] of [mint] (0 slot, 1 timestamp); null when it is not one. */
    fun dlmmActivationType8895(mint: String, data: ByteArray?): Int? {
        if (data == null || data.size < DLMM_PAIR_MIN_SIZE_8895 || !disc(data, DLMM_PAIR_DISC_8895)) return null
        if (!pairedWithQuote(mint, key(data, 88), key(data, 120))) return null
        val t = data[86].toInt() and 0xFF
        return if (t == 0 || t == 1) t else null
    }

    /** Pure: a DLMM PositionV2 account [data] of [pair] (base and extended bins); null when it is not one. */
    internal fun dlmmPosition8895(pair: String, data: ByteArray?): Position8895? {
        if (data == null || data.size < DLMM_POSITION_BASE_8895 || !disc(data, DLMM_POSITION_DISC_8895)) return null
        if (key(data, 8) != pair) return null
        val lower = i32(data, 7912); val upper = i32(data, 7916)
        val width = upper - lower + 1
        if (width <= 0) return null
        val shares = ArrayList<BigInteger>(width)
        for (i in 0 until width) {
            val off = if (i < DLMM_BASE_BINS_8895) 72 + 16 * i
                else DLMM_POSITION_BASE_8895 + (i - DLMM_BASE_BINS_8895) * DLMM_BIN_ENTRY_8895
            if (data.size < off + 16) return null   // a range the account does not hold: not readable, never guessed
            shares.add(uLe8895(data, off, 16))
        }
        return Position8895(lower, upper, shares, u64Clamp8895(data, 7992))
    }

    /**
     * Pure: the share (%) of a DLMM pair's liquidity that cannot leave before [minReleasePoint]: per bin, locked shares over all
     * shares; the smallest such fraction over the bins that hold liquidity. Null when no position holds any.
     */
    internal fun dlmmLockedPct8895(positions: List<Position8895>, minReleasePoint: Long): Double? {
        val total = HashMap<Int, BigInteger>(); val locked = HashMap<Int, BigInteger>()
        for (p in positions) {
            val isLocked = p.lockReleasePoint > minReleasePoint
            p.shares.forEachIndexed { i, s ->
                if (s.signum() <= 0) return@forEachIndexed
                val bin = p.lowerBin + i
                total[bin] = (total[bin] ?: BigInteger.ZERO).add(s)
                if (isLocked) locked[bin] = (locked[bin] ?: BigInteger.ZERO).add(s)
            }
        }
        if (total.isEmpty()) return null
        return total.entries.minOf { (bin, t) ->
            (locked[bin] ?: BigInteger.ZERO).multiply(BigInteger.valueOf(10_000L)).divide(t).toDouble() / 100.0
        }
    }

    /** Pure: the release point a lock must pass, from the chain clock ([slot], [unixS]) and the pair's [activationType]. */
    fun minReleasePoint8895(activationType: Int, slot: Long, unixS: Long): Long =
        if (activationType == 0) slot + LOCK_MARGIN_SLOTS_8895 else unixS + LOCK_MARGIN_S_8895

    /** Pure: does [lock] prove [mint]'s [pool] for the LP rule at [nowMs]? */
    fun proves8895(lock: Lock8895?, mint: String, pool: String, nowMs: Long): Boolean =
        lock != null && lock.mint == mint && pool.isNotBlank() && lock.pool == pool && lock.lockedPct.isFinite() &&
            lock.lockedPct >= MIN_LOCKED_PCT_8895 && lock.lockedPct <= 100.0 && nowMs - lock.atMs in -5_000L..PROOF_MS_8895

    // ── state ──

    private val locks = ConcurrentHashMap<String, Lock8895>()        // pool -> latest read
    private val otherVenue = ConcurrentHashMap<String, Long>()      // pool -> read as no pool of ours
    private val lastAsk = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val asked = AtomicLong(0L)
    private val rpcFailed = AtomicLong(0L)
    private val tooMany = AtomicLong(0L)
    private val notOurs = AtomicLong(0L)
    private val outcome = ConcurrentHashMap<String, AtomicLong>()   // venue:LOCKED / venue:UNLOCKED

    /** The fresh read of [pool] when it proves [mint] at least 70 % locked, else null. */
    fun proof8895(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()): Lock8895? =
        locks[pool.trim()]?.takeIf { proves8895(it, mint.trim(), pool.trim(), nowMs) }

    /**
     * V5.0.8937 — TokenSafetyChecker: [pool]'s read when it proves [mint] at least 70 % locked within [REPORT_PROOF_MS_8937].
     * A burned or permanently locked share never shrinks except by new deposits, and a DLMM lock is read 24 h past now, so a
     * report made from a read ten minutes old states what the pool still holds. The buy door keeps its own 60 s proof.
     */
    fun reportProof8937(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()): Lock8895? {
        val l = locks[pool.trim()] ?: return null
        return l.takeIf { it.mint == mint.trim() && pool.isNotBlank() && it.lockedPct.isFinite() && it.lockedPct >= MIN_LOCKED_PCT_8895 &&
            it.lockedPct <= 100.0 && nowMs - it.atMs in -5_000L..REPORT_PROOF_MS_8937 }
    }

    // V5.0.8937 — the provider reported a pool under 70 % that its own accounts prove locked, by venue (what RugCheck misreads)
    private val corrected8937 = ConcurrentHashMap<String, AtomicLong>()
    // V5.0.8937 — the programs of pools this reader does not read, so the next venue to add is named by the data
    private val otherPrograms8937 = ConcurrentHashMap<String, AtomicLong>()

    /** TokenSafetyChecker: the provider's [providerPct] for [pool] stood corrected by [lock] (counted once per call). */
    fun noteCorrected8937(lock: Lock8895, providerPct: Double) {
        val band = if (providerPct < 30.0) "lt30" else "lt70"
        corrected8937.computeIfAbsent("${lock.venue}:$band") { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LP_LOCK_PROVIDER_CORRECTED_8937_${lock.venue}") } catch (_: Throwable) {}
    }

    private fun askable(mint: String, pool: String, nowMs: Long): Boolean {
        if (mint.isBlank() || pool.isBlank() || pool == mint) return false
        // superseded by V5.0.8937 — a pump.fun mint is read too: its graduated PumpSwap pool is one this reader proves (its
        // bonding curve is another program's account and is skipped for 30 minutes like any other)
        otherVenue[pool]?.let { if (nowMs - it in 0L until OTHER_RETRY_MS_8895) return false }
        lastAsk[pool]?.let { if (nowMs - it in 0L until REREAD_MS_8895) return false }
        return true
    }

    /** The LP door (a pool it could not prove): read [pool]'s lock for [mint] in the background. */
    fun request8895(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()) {
        val m = mint.trim(); val p = pool.trim()
        if (!askable(m, p, nowMs) || !inFlight.add(p)) return
        lastAsk[p] = nowMs
        trim8895(nowMs)
        try { exec.execute { try { read8895(m, p) } catch (_: Throwable) {} finally { inFlight.remove(p) } } }
        catch (_: Throwable) { inFlight.remove(p) }
    }

    private fun read8895(mint: String, pool: String) {
        asked.incrementAndGet()
        val res = rpc("getAccountInfo", JSONArray().put(pool).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            ?: run { rpcFailed.incrementAndGet(); return }
        val v = res.optJSONObject("value")
        val owner = v?.optString("owner").orEmpty()
        val data = OnChainDepth8038.accountData8418(v)
        val now = System.currentTimeMillis()
        when (owner) {
            DAMM_V2_PROGRAM_8895 -> {
                val pct = dammV2LockedPct8895(mint, data) ?: run { other(pool, now); return }
                if (pct >= MIN_LOCKED_PCT_8895) store(Lock8895(mint, pool, VENUE_DAMM_V2_8895, pct, now, "permanent/liquidity"))
                else readDammVesting8925(mint, pool, data!!, pct)   // V5.0.8925 — and what its positions' schedules hold past the hold
            }
            CPMM_PROGRAM_8925 -> readCpmm8925(mint, pool, data)   // V5.0.8925
            AMM_V4_PROGRAM_8926 -> readAmmV4_8926(mint, pool, data)   // V5.0.8926
            DLMM_PROGRAM_8895 -> {
                val type = dlmmActivationType8895(mint, data) ?: run { other(pool, now); return }
                readDlmm(mint, pool, type, dlmmQuoteIsY8997(data))
            }
            PUMP_AMM_PROGRAM_8937 -> readPumpSwap8937(mint, pool, data)   // V5.0.8937
            else -> { other(pool, now); if (owner.isNotBlank()) countOther8937(owner) }
        }
    }

    private fun readDlmm(mint: String, pool: String, activationType: Int, quoteIsY8997: Boolean? = null) {
        val clock = rpc("getAccountInfo", JSONArray().put(CLOCK_SYSVAR_8895).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            ?.optJSONObject("value")?.let { OnChainDepth8038.accountData8418(it) }
        if (clock == null || clock.size < 40) { rpcFailed.incrementAndGet(); return }
        val minPoint = minReleasePoint8895(activationType, u64Clamp8895(clock, 0), u64Clamp8895(clock, 32))
        val filters = JSONArray()
            .put(JSONObject().put("memcmp", JSONObject().put("offset", 0).put("bytes", discBase58(DLMM_POSITION_DISC_8895))))
            .put(JSONObject().put("memcmp", JSONObject().put("offset", 8).put("bytes", pool)))
        // first the bins and the lock of every position (88 bytes each), which also counts them
        val head = gpa(JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("withContext", true)
            .put("filters", filters).put("dataSlice", JSONObject().put("offset", 7912).put("length", 88))) ?: run { rpcFailed.incrementAndGet(); return }
        val now = System.currentTimeMillis()
        if (head.length() > MAX_POSITIONS_8895) { tooMany.incrementAndGet(); other(pool, now); return }
        if (head.length() == 0) { store(Lock8895(mint, pool, VENUE_DLMM_8895, 0.0, now, "no positions")); return }
        // V5.0.8997 — and, for a pool the LP record asked about, who holds its liquidity
        if (quoteIsY8997 != null && spreadWanted8997.remove(pool)) try { readSpread8997(mint, pool, filters, minPoint, quoteIsY8997) } catch (_: Throwable) { spreadFailed8997.incrementAndGet() }
        val lockedFlags = (0 until head.length()).mapNotNull { i ->
            OnChainDepth8038.accountData8418(head.optJSONObject(i)?.optJSONObject("account"))?.takeIf { it.size >= 88 }?.let { u64Clamp8895(it, 80) > minPoint }
        }
        if (lockedFlags.size != head.length()) { rpcFailed.incrementAndGet(); return }
        if (lockedFlags.none { it }) { store(Lock8895(mint, pool, VENUE_DLMM_8895, 0.0, now, "positions=${head.length()} locked=0")); return }
        // some positions are locked: the shares decide how much of every bin is
        val full = gpa(JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("withContext", true).put("filters", filters))
            ?: run { rpcFailed.incrementAndGet(); return }
        val positions = (0 until full.length()).mapNotNull { i -> dlmmPosition8895(pool, OnChainDepth8038.accountData8418(full.optJSONObject(i)?.optJSONObject("account"))) }
        if (positions.size != full.length()) { rpcFailed.incrementAndGet(); return }
        val pct = dlmmLockedPct8895(positions, minPoint) ?: 0.0
        store(Lock8895(mint, pool, VENUE_DLMM_8895, pct, System.currentTimeMillis(), "positions=${positions.size} locked=${positions.count { it.lockReleasePoint > minPoint }}"))
    }

    /**
     * V5.0.8997 §WHO_HOLDS_THE_POOL — owner, 25 Nov 04:06: "approve" (a second proof beside the lock: no single wallet holds
     * enough of the pool to drain it). What the journals show: the trades the owner was "stoked" about (INU, 20 Nov 11:03,
     * +98 % / +404 % / +927 % banked, about 4x on the stake) were off-pump base-start coins whose LP lock was UNREAD; the
     * paying-cell record then read PENDING n63 +37.0 % (20 Nov), n185 +39.8 % (22 Nov), LP_BLOCKED n0. This reader (8895,
     * 8925, 8937) then read those pools and found them unlocked (METEORA_DLMM:UNLOCKED 131 of 143 on 24 Nov), so the LP rule
     * refuses them: LP_BLOCKED n193 +30.3 %. Every pocket the bot proves is that cohort.
     * The rule exists to stop a drain. A DLMM pool has no LP token: each position is an account its OWNER can withdraw, and
     * the SOL a holder can sell into is the quote side of the bins. So the share of that SOL one wallet can pull is read
     * from the pool's own accounts (MeteoraAg/dlmm-sdk idls/dlmm.json, offsets with the 8-byte discriminator): PositionV2
     * owner @40 (lb_pair @8, liquidity_shares @72, verified against the IDL: total 8,120); BinArray (discriminator
     * [92,142,92,220,5,148,70,181], 10,136 bytes) index i64 @8, lb_pair @24, 70 bins of 144 bytes from @56, each amount_x
     * u64 @0 and amount_y u64 @8; LbPair token_x_mint @88, token_y_mint @120. Per bin an owner's part of the quote is its
     * unlocked shares over all shares of that bin (shares of one bin are comparable); summed over the bins and divided by
     * the pool's quote, the largest owner's figure is the most one wallet can remove.
     * THIS BUILD MEASURES ONLY. The LP record files each LP-blocked coin with its class ([spreadClass8997]: SPREAD = at
     * least [SPREAD_MIN_OWNERS_8997] owners and no owner over [SPREAD_MAX_TOP_PCT_8997] %; ONE_HOLDER = one owner at 70 %+;
     * CONCENTRATED between; NO_QUOTE; UNREAD), the forward labeler follows it, and the authority table grades each class.
     * Nothing is bought and the LP rule is unchanged until the owner has seen what each class does.
     * Stated, the owner's own objection of 20 Nov: "a dev can bundle hold across multiple wallets to rug" — distinct wallets
     * are not proven distinct people; the class is evidence of spread, not of independence.
     */
    internal val DLMM_BIN_ARRAY_DISC_8997 = intArrayOf(92, 142, 92, 220, 5, 148, 70, 181)       // sha256("account:BinArray")[0..8]
    internal const val DLMM_BIN_ARRAY_SIZE_8997 = 10_136
    internal const val DLMM_BIN_SIZE_8997 = 144
    internal const val DLMM_BINS_PER_ARRAY_8997 = 70
    internal const val SPREAD_MAX_TOP_PCT_8997 = 30.0
    internal const val SPREAD_MIN_OWNERS_8997 = 3
    private const val SPREAD_KEEP_MS_8997 = 10L * 60_000L

    /** One read: [owners] wallets hold [pool]'s [positions] positions; the largest can remove [topOwnerPct] % of its quote side. */
    data class Spread8997(val mint: String, val pool: String, val owners: Int, val positions: Int, val topOwnerPct: Double, val atMs: Long) {
        val cls: String get() = spreadClass8997(owners, topOwnerPct)
    }

    private val spreads8997 = ConcurrentHashMap<String, Spread8997>()
    private val spreadWanted8997 = ConcurrentHashMap.newKeySet<String>()
    private val spreadOutcome8997 = ConcurrentHashMap<String, AtomicLong>()
    private val spreadFailed8997 = AtomicLong(0L)

    /** Pure: the class of a pool held by [owners] wallets whose largest can remove [topOwnerPct] % of the quote side. */
    internal fun spreadClass8997(owners: Int, topOwnerPct: Double): String = when {
        !topOwnerPct.isFinite() -> "NO_QUOTE"
        owners >= SPREAD_MIN_OWNERS_8997 && topOwnerPct <= SPREAD_MAX_TOP_PCT_8997 -> "SPREAD"
        topOwnerPct >= 70.0 -> "ONE_HOLDER"
        else -> "CONCENTRATED"
    }

    /** Pure: the owner of a DLMM PositionV2 account [data]. */
    internal fun dlmmOwner8997(data: ByteArray?): String? = if (data == null || data.size < 72) null else key(data, 40)

    /** Pure: is the quote (SOL / USDC / USDT) the Y token of the LbPair account [data]? Null when neither side is a quote. */
    internal fun dlmmQuoteIsY8997(data: ByteArray?): Boolean? {
        if (data == null || data.size < 152) return null
        val x = key(data, 88); val y = key(data, 120)
        return when {
            y != null && y in QUOTES_8895 -> true
            x != null && x in QUOTES_8895 -> false
            else -> null
        }
    }

    /** Pure: bin id -> raw quote amount, from a BinArray account [data] of [pair]; null when it is not one. */
    internal fun dlmmBinQuote8997(pair: String, data: ByteArray?, quoteIsY: Boolean): Map<Int, BigInteger>? {
        if (data == null || data.size < DLMM_BIN_ARRAY_SIZE_8997 || !disc(data, DLMM_BIN_ARRAY_DISC_8997)) return null
        if (key(data, 24) != pair) return null
        val index = java.nio.ByteBuffer.wrap(data, 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
        if (index < -1_000_000L || index > 1_000_000L) return null
        val out = HashMap<Int, BigInteger>()
        for (j in 0 until DLMM_BINS_PER_ARRAY_8997) {
            val q = uLe8895(data, 56 + DLMM_BIN_SIZE_8997 * j + (if (quoteIsY) 8 else 0), 8)
            if (q.signum() > 0) out[(index * DLMM_BINS_PER_ARRAY_8997 + j).toInt()] = q
        }
        return out
    }

    /**
     * Pure: (the largest share (%) of the pool's quote one owner can remove before [minReleasePoint], the number of owners).
     * [owned] is every position of the pair with its owner; [quoteByBin] the quote each bin holds. NaN when no bin that a
     * position covers holds quote.
     */
    internal fun topOwnerQuotePct8997(owned: List<Pair<String, Position8895>>, quoteByBin: Map<Int, BigInteger>, minReleasePoint: Long): Pair<Double, Int> {
        val total = HashMap<Int, BigInteger>()
        val owners = HashSet<String>()
        for ((owner, p) in owned) p.shares.forEachIndexed { i, s ->
            if (s.signum() > 0) { owners.add(owner); val bin = p.lowerBin + i; total[bin] = (total[bin] ?: BigInteger.ZERO).add(s) }
        }
        val byOwner = HashMap<String, Double>()
        var all = 0.0
        for ((bin, q) in quoteByBin) {
            val t = total[bin] ?: continue
            if (t.signum() <= 0) continue
            val quote = q.toDouble()
            all += quote
            for ((owner, p) in owned) {
                if (p.lockReleasePoint > minReleasePoint) continue   // a locked position cannot leave
                val i = bin - p.lowerBin
                if (i < 0 || i >= p.shares.size) continue
                val s = p.shares[i]
                if (s.signum() > 0) byOwner[owner] = (byOwner[owner] ?: 0.0) +
                    quote * java.math.BigDecimal(s).divide(java.math.BigDecimal(t), 12, java.math.RoundingMode.HALF_UP).toDouble()
            }
        }
        val top = if (all > 0.0) (byOwner.values.maxOrNull() ?: 0.0) / all * 100.0 else Double.NaN
        return top to owners.size
    }

    /** Pure: is [sp] this [mint]'s read and still inside [SPREAD_KEEP_MS_8997] at [nowMs]? */
    internal fun fresh8997(sp: Spread8997, mint: String, nowMs: Long): Boolean =
        sp.mint == mint.trim() && nowMs - sp.atMs in -5_000L..SPREAD_KEEP_MS_8997

    /** LpStateRecord8725: who holds [pool] when it was read inside [SPREAD_KEEP_MS_8997]; else null, and the read is asked. */
    fun spreadFor8997(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()): Spread8997? {
        val p = pool.trim()
        if (p.isBlank()) return null
        spreads8997[p]?.takeIf { fresh8997(it, mint, nowMs) }?.let { return it }
        if (spreadWanted8997.size < 2_000) spreadWanted8997.add(p)
        request8895(mint, p, nowMs)
        return null
    }

    private fun readSpread8997(mint: String, pool: String, positionFilters: JSONArray, minPoint: Long, quoteIsY: Boolean) {
        val full = gpa(JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("withContext", true).put("filters", positionFilters))
            ?: run { spreadFailed8997.incrementAndGet(); return }
        val owned = ArrayList<Pair<String, Position8895>>(full.length())
        for (i in 0 until full.length()) {
            val d = OnChainDepth8038.accountData8418(full.optJSONObject(i)?.optJSONObject("account"))
            val pos = dlmmPosition8895(pool, d); val owner = dlmmOwner8997(d)
            if (pos == null || owner == null) { spreadFailed8997.incrementAndGet(); return }   // an unreadable position: not judged
            owned.add(owner to pos)
        }
        val binFilters = JSONArray()
            .put(JSONObject().put("memcmp", JSONObject().put("offset", 0).put("bytes", discBase58(DLMM_BIN_ARRAY_DISC_8997))))
            .put(JSONObject().put("memcmp", JSONObject().put("offset", 24).put("bytes", pool)))
        val arrays = gpa(JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("withContext", true).put("filters", binFilters))
            ?: run { spreadFailed8997.incrementAndGet(); return }
        val quote = HashMap<Int, BigInteger>()
        for (i in 0 until arrays.length()) {
            val m = dlmmBinQuote8997(pool, OnChainDepth8038.accountData8418(arrays.optJSONObject(i)?.optJSONObject("account")), quoteIsY)
                ?: run { spreadFailed8997.incrementAndGet(); return }
            quote.putAll(m)
        }
        val (top, owners) = topOwnerQuotePct8997(owned, quote, minPoint)
        val sp = Spread8997(mint, pool, owners, owned.size, top, System.currentTimeMillis())
        if (spreads8997.size > 2_000) spreads8997.entries.removeIf { sp.atMs - it.value.atMs > SPREAD_KEEP_MS_8997 }
        spreads8997[pool] = sp
        spreadOutcome8997.computeIfAbsent(sp.cls) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("POOL_SPREAD_8997_${sp.cls}") } catch (_: Throwable) {}
    }

    /** Brief: the DLMM pools whose holders were read, by class. */
    fun spreadLine8997(): String =
        "poolSpread8997[read{${spreadOutcome8997.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "failed=${spreadFailed8997.get()} held=${spreads8997.size} waiting=${spreadWanted8997.size} rule=an LP-blocked coin's Meteora DLMM pool: the largest " +
            "share of its quote side one wallet can remove (unlocked positions); SPREAD = $SPREAD_MIN_OWNERS_8997+ owners and none over " +
            "${SPREAD_MAX_TOP_PCT_8997.toInt()} %; measured only, filed on the LP record's label, nothing bought]"

    internal fun putSpreadForTest8997(sp: Spread8997) { spreads8997[sp.pool] = sp }
    internal fun resetSpreadForTest8997() { spreads8997.clear(); spreadWanted8997.clear(); spreadOutcome8997.clear(); spreadFailed8997.set(0L) }

    private class Schedules8925(val atMs: Long, val positions: List<DammPosition8925>, val external: Map<String, List<Vesting8925>>)
    private val schedules8925 = ConcurrentHashMap<String, Schedules8925>()   // pool -> its positions' locks (vesting schedules change only by a new lock)
    private class CpmmLocked8925(val atMs: Long, val lpMint: String, val burnedPlusVault: BigInteger)
    private val cpmmLocked8925 = ConcurrentHashMap<String, CpmmLocked8925>()   // pool -> burned + Burn & Earn LP (only ever grows)

    private fun clock8925(): Pair<Long, Long>? =
        rpc("getAccountInfo", JSONArray().put(CLOCK_SYSVAR_8895).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            ?.optJSONObject("value")?.let { OnChainDepth8038.accountData8418(it) }?.takeIf { it.size >= 40 }
            ?.let { u64Clamp8895(it, 0) to u64Clamp8895(it, 32) }

    /** V5.0.8925 — a DAMM v2 pool under 70 % permanently locked: its positions' permanent and vesting locks, past the hold. */
    private fun readDammVesting8925(mint: String, pool: String, poolData: ByteArray, permanentPct: Double) {
        val type = dammActivationType8925(poolData) ?: run { store(Lock8895(mint, pool, VENUE_DAMM_V2_8895, permanentPct, System.currentTimeMillis(), "permanent/liquidity")); return }
        val (slot, unixS) = clock8925() ?: run { rpcFailed.incrementAndGet(); return }
        val point = minReleasePoint8895(type, slot, unixS)
        val nowMs = System.currentTimeMillis()
        val cached = schedules8925[pool]?.takeIf { nowMs - it.atMs in 0L until SCHEDULE_CACHE_MS_8925 }
        val sched = cached ?: run {
            val filters = JSONArray()
                .put(JSONObject().put("dataSize", DAMM_V2_POSITION_SIZE_8925))
                .put(JSONObject().put("memcmp", JSONObject().put("offset", 0).put("bytes", discBase58(DAMM_V2_POSITION_DISC_8925))))
                .put(JSONObject().put("memcmp", JSONObject().put("offset", 8).put("bytes", pool)))
            val rows = gpa(DAMM_V2_PROGRAM_8895, JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("filters", filters))
                ?: run { rpcFailed.incrementAndGet(); return }
            if (rows.length() > MAX_POSITIONS_8895) {
                tooMany.incrementAndGet()
                store(Lock8895(mint, pool, VENUE_DAMM_V2_8895, permanentPct, System.currentTimeMillis(), "permanent/liquidity positions>${MAX_POSITIONS_8895}"))
                return
            }
            val positions = (0 until rows.length()).mapNotNull { i ->
                val r = rows.optJSONObject(i) ?: return@mapNotNull null
                dammPosition8925(pool, r.optString("pubkey"), OnChainDepth8038.accountData8418(r.optJSONObject("account")))
            }
            if (positions.size != rows.length()) { rpcFailed.incrementAndGet(); return }
            // a position whose vested liquidity its own schedule does not explain has external Vesting accounts: read those
            val external = HashMap<String, List<Vesting8925>>()
            for (p in positions.filter { pp -> pp.vested > (pp.inner?.let { remaining8925(it) } ?: BigInteger.ZERO) }.take(MAX_EXTERNAL_VESTING_READS_8925)) {
                val vf = JSONArray()
                    .put(JSONObject().put("dataSize", DAMM_V2_VESTING_SIZE_8925))
                    .put(JSONObject().put("memcmp", JSONObject().put("offset", 0).put("bytes", discBase58(DAMM_V2_VESTING_DISC_8925))))
                    .put(JSONObject().put("memcmp", JSONObject().put("offset", 8).put("bytes", p.address)))
                val vr = gpa(DAMM_V2_PROGRAM_8895, JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("filters", vf)) ?: continue
                external[p.address] = (0 until vr.length()).mapNotNull { i -> dammVesting8925(p.address, OnChainDepth8038.accountData8418(vr.optJSONObject(i)?.optJSONObject("account"))) }
            }
            Schedules8925(nowMs, positions, external).also { schedules8925[pool] = it }
        }
        val pct = dammLockedPct8925(uLe8895(poolData, 360, 16), sched.positions, sched.external, point) ?: permanentPct
        store(Lock8895(mint, pool, VENUE_DAMM_V2_8895, maxOf(pct, permanentPct), System.currentTimeMillis(),
            "permanent+vesting past the hold positions=${sched.positions.size}"))
    }

    /** V5.0.8925 — a Raydium CPMM pool: LP burned plus LP held by the Burn & Earn authority, over lp_supply. */
    private fun readCpmm8925(mint: String, pool: String, data: ByteArray?) {
        val (lpMint, lpSupply) = cpmmPool8925(mint, data) ?: run { other(pool, System.currentTimeMillis()); return }
        val nowMs = System.currentTimeMillis()
        val cached = cpmmLocked8925[pool]?.takeIf { it.lpMint == lpMint && nowMs - it.atMs in 0L until SCHEDULE_CACHE_MS_8925 }
        val lockedUnits = cached?.burnedPlusVault ?: run {
            val supply = rpc("getAccountInfo", JSONArray().put(lpMint).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
                ?.optJSONObject("value")?.let { mintSupply8925(OnChainDepth8038.accountData8418(it)) } ?: run { rpcFailed.incrementAndGet(); return }
            val largest = rpc("getTokenLargestAccounts", JSONArray().put(lpMint).put(JSONObject().put("commitment", "confirmed")))
                ?.optJSONArray("value") ?: run { rpcFailed.incrementAndGet(); return }
            val addrs = JSONArray(); for (i in 0 until largest.length()) largest.optJSONObject(i)?.optString("address")?.takeIf { it.isNotBlank() }?.let { addrs.put(it) }
            var vault = BigInteger.ZERO
            if (addrs.length() > 0) {
                val accts = rpc("getMultipleAccounts", JSONArray().put(addrs).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
                    ?.optJSONArray("value") ?: run { rpcFailed.incrementAndGet(); return }
                for (i in 0 until accts.length()) {
                    val (m, owner, amount) = tokenAccount8925(accts.optJSONObject(i)?.let { OnChainDepth8038.accountData8418(it) }) ?: continue
                    if (m == lpMint && owner == CPMM_LOCK_AUTH_8925) vault = vault.add(amount)
                }
            }
            if (supply > lpSupply || vault > supply) { rpcFailed.incrementAndGet(); return }
            lpSupply.subtract(supply).add(vault).also { cpmmLocked8925[pool] = CpmmLocked8925(nowMs, lpMint, it) }
        }
        // burned and locked LP only ever grow; fresh lp_supply carries every later deposit, so a cached amount is a lower bound
        val pct = if (lpSupply.signum() <= 0 || lockedUnits > lpSupply) null
            else lockedUnits.multiply(BigInteger.valueOf(10_000L)).divide(lpSupply).toDouble() / 100.0
        if (pct == null) { rpcFailed.incrementAndGet(); return }
        store(Lock8895(mint, pool, VENUE_CPMM_8925, pct, System.currentTimeMillis(), "burned+burnAndEarn/lp_supply"))
    }

    /** V5.0.8926 — a Raydium AMM v4 pool: LP burned over lp_amount. */
    private fun readAmmV4_8926(mint: String, pool: String, data: ByteArray?) {
        val (lpMint, lpAmount) = ammV4Pool8926(mint, data) ?: run { other(pool, System.currentTimeMillis()); return }
        val supply = rpc("getAccountInfo", JSONArray().put(lpMint).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            ?.optJSONObject("value")?.let { mintSupply8925(OnChainDepth8038.accountData8418(it)) } ?: run { rpcFailed.incrementAndGet(); return }
        val pct = cpmmLockedPct8925(lpAmount, supply, BigInteger.ZERO) ?: run { rpcFailed.incrementAndGet(); return }
        store(Lock8895(mint, pool, VENUE_AMM_V4_8926, pct, System.currentTimeMillis(), "burned/lp_amount"))
    }

    /** V5.0.8937 — a PumpSwap pool: LP burned (the migrated LP all of it) over lp_supply. */
    private fun readPumpSwap8937(mint: String, pool: String, data: ByteArray?) {
        val (lpMint, lpSupply) = pumpSwapPool8937(mint, data) ?: run { other(pool, System.currentTimeMillis()); return }
        val supply = rpc("getAccountInfo", JSONArray().put(lpMint).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            ?.optJSONObject("value")?.let { mintSupply8925(OnChainDepth8038.accountData8418(it)) } ?: run { rpcFailed.incrementAndGet(); return }
        val pct = cpmmLockedPct8925(lpSupply, supply, BigInteger.ZERO) ?: run { rpcFailed.incrementAndGet(); return }
        store(Lock8895(mint, pool, VENUE_PUMPSWAP_8937, pct, System.currentTimeMillis(), "burned/lp_supply"))
    }

    private fun countOther8937(program: String) {
        val k = program.take(8)
        (otherPrograms8937[k] ?: if (otherPrograms8937.size >= 16) otherPrograms8937.computeIfAbsent("OTHER") { AtomicLong(0L) }
            else otherPrograms8937.computeIfAbsent(k) { AtomicLong(0L) }).incrementAndGet()
    }

    private fun gpa(config: JSONObject): JSONArray? = gpa(DLMM_PROGRAM_8895, config)

    private fun gpa(program: String, config: JSONObject): JSONArray? =
        rpc("getProgramAccounts", JSONArray().put(program).put(config))?.optJSONArray("value")

    private fun discBase58(d: IntArray): String =
        io.github.novacrypto.base58.Base58.base58Encode(ByteArray(8) { d[it].toByte() })

    private fun store(l: Lock8895) {
        val before = locks.put(l.pool, l)
        val state = if (l.lockedPct >= MIN_LOCKED_PCT_8895) "LOCKED" else "UNLOCKED"
        // V5.0.8937 — a lock newly proven re-makes the coin's safety report on its next pass, so a provider's lower figure for
        // this pool stops standing as the report's LP lock (TokenSafetyChecker reads the proof); a re-read of a held lock does not
        val wasLocked = before != null && before.mint == l.mint && before.lockedPct >= MIN_LOCKED_PCT_8895 && l.atMs - before.atMs in 0L..REPORT_PROOF_MS_8937
        if (state == "LOCKED" && !wasLocked) try { com.lifecyclebot.engine.SafetyRefreshQueue.request(l.mint) } catch (_: Throwable) {}
        outcome.computeIfAbsent("${l.venue}:$state") { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("POOL_LOCK_8895_${l.venue}_$state") } catch (_: Throwable) {}
    }

    private fun other(pool: String, nowMs: Long) { otherVenue[pool] = nowMs; notOurs.incrementAndGet(); locks.remove(pool) }

    private fun trim8895(nowMs: Long) {
        if (lastAsk.size > 4_000) lastAsk.entries.removeIf { nowMs - it.value > REREAD_MS_8895 }
        if (otherVenue.size > 8_000) otherVenue.entries.removeIf { nowMs - it.value > OTHER_RETRY_MS_8895 }
        if (locks.size > 2_000) locks.entries.removeIf { nowMs - it.value.atMs > PROOF_MS_8895 }
        if (schedules8925.size > 500) schedules8925.entries.removeIf { nowMs - it.value.atMs > SCHEDULE_CACHE_MS_8925 }   // V5.0.8925
        if (cpmmLocked8925.size > 500) cpmmLocked8925.entries.removeIf { nowMs - it.value.atMs > SCHEDULE_CACHE_MS_8925 }
    }

    private val exec: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
            { r -> Thread(r, "pool-lock-8895").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(TIMEOUT_S_8895, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S_8895, TimeUnit.SECONDS).build()
    }

    private fun rpc(method: String, params: JSONArray): JSONObject? =
        com.lifecyclebot.network.KeylessRpc8359.post8359(http, method, params, "8895", maxRungs = 3)

    /** Brief: reads, what they found by venue, and what was not ours or too large to prove. */
    fun statusLine8895(): String =
        "poolLock8895[asked=${asked.get()} found{${outcome.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "notOurs=${notOurs.get()} tooManyPositions=${tooMany.get()} rpcFailed=${rpcFailed.get()} held=${locks.size} " +
            "providerCorrected8937{${corrected8937.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "otherPrograms8937{${otherPrograms8937.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "rule=Meteora DAMM v2 permanent lock + vesting held past 24 h (8925) / DLMM positions locked 24 h+ (smallest locked share of any bin) / " +
            "Raydium CPMM LP burned + Burn & Earn (8925) / Raydium AMM v4 LP burned (8926) / PumpSwap LP burned (8937), read on chain; " +
            ">=70 % proves the pool's LP, and from 8937 stands over a provider's lower figure for that same pool; an unlocked or " +
            "unreadable pool stays unproven and is not bought]"

    internal fun putForTest8895(l: Lock8895) { locks[l.pool] = l }
    internal fun resetForTest8895() { corrected8937.clear(); otherPrograms8937.clear(); schedules8925.clear(); cpmmLocked8925.clear(); locks.clear(); otherVenue.clear(); lastAsk.clear(); inFlight.clear(); outcome.clear() }
}
