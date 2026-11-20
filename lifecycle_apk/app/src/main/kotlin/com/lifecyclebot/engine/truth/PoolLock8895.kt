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
    private val QUOTES_8895 = setOf(OnChainDepth8038.WSOL, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB")

    /** One read: [mint]'s [pool] on [venue] had [lockedPct] % of its liquidity locked at [atMs] ([detail] says how). */
    data class Lock8895(val mint: String, val pool: String, val venue: String, val lockedPct: Double, val atMs: Long, val detail: String)

    /** One DLMM position: its bin range, liquidity share per bin (lower first) and lock release point. */
    internal data class Position8895(val lowerBin: Int, val upperBin: Int, val shares: List<BigInteger>, val lockReleasePoint: Long)

    // ── pure ──

    private fun disc(data: ByteArray, d: IntArray): Boolean = data.size >= 8 && (0 until 8).all { (data[it].toInt() and 0xFF) == d[it] }

    private fun key(data: ByteArray, off: Int): String? =
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
    private fun pairedWithQuote(mint: String, a: String?, b: String?): Boolean =
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

    private fun askable(mint: String, pool: String, nowMs: Long): Boolean {
        if (mint.isBlank() || pool.isBlank() || pool == mint) return false
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) } catch (_: Throwable) { false }) return false
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
                store(Lock8895(mint, pool, VENUE_DAMM_V2_8895, pct, now, "permanent/liquidity"))
            }
            DLMM_PROGRAM_8895 -> {
                val type = dlmmActivationType8895(mint, data) ?: run { other(pool, now); return }
                readDlmm(mint, pool, type)
            }
            else -> other(pool, now)
        }
    }

    private fun readDlmm(mint: String, pool: String, activationType: Int) {
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

    private fun gpa(config: JSONObject): JSONArray? =
        rpc("getProgramAccounts", JSONArray().put(DLMM_PROGRAM_8895).put(config))?.optJSONArray("value")

    private fun discBase58(d: IntArray): String =
        io.github.novacrypto.base58.Base58.base58Encode(ByteArray(8) { d[it].toByte() })

    private fun store(l: Lock8895) {
        locks[l.pool] = l
        val state = if (l.lockedPct >= MIN_LOCKED_PCT_8895) "LOCKED" else "UNLOCKED"
        outcome.computeIfAbsent("${l.venue}:$state") { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("POOL_LOCK_8895_${l.venue}_$state") } catch (_: Throwable) {}
    }

    private fun other(pool: String, nowMs: Long) { otherVenue[pool] = nowMs; notOurs.incrementAndGet(); locks.remove(pool) }

    private fun trim8895(nowMs: Long) {
        if (lastAsk.size > 4_000) lastAsk.entries.removeIf { nowMs - it.value > REREAD_MS_8895 }
        if (otherVenue.size > 8_000) otherVenue.entries.removeIf { nowMs - it.value > OTHER_RETRY_MS_8895 }
        if (locks.size > 2_000) locks.entries.removeIf { nowMs - it.value.atMs > PROOF_MS_8895 }
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
            "rule=Meteora DAMM v2 permanent lock / DLMM positions locked 24 h+ (smallest locked share of any bin), read on chain; >=70 % turns an " +
            "unread LP proof into PROVEN, nothing else changes; an unlocked or unreadable pool stays unproven and is not bought]"

    internal fun putForTest8895(l: Lock8895) { locks[l.pool] = l }
    internal fun resetForTest8895() { locks.clear(); otherVenue.clear(); lastAsk.clear(); inFlight.clear(); outcome.clear() }
}
