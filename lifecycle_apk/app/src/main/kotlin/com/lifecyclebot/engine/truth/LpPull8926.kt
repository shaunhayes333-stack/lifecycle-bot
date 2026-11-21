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
 * V5.0.8926 §LP_PULLED_WE_LEAVE — owner, 22 Nov 01:39-02:09: "maybe just take the good ideas and create it as our own source
 * code version", then "go. if it gets liq pulled im going to be very pissed of". Nothing read a held coin's pool for its
 * liquidity being taken out: a rug showed only later, as a price or depth collapse (REFLEX_LIQ_DRAIN, LIQ_HALVED_...).
 *
 * The pool account says it exactly, with no transaction parsing: a constant-product / concentrated pool's liquidity units
 * move only when liquidity is added or withdrawn, never on a swap.
 *  - Meteora DAMM v2: Pool.liquidity u128 @360 (damm-v2 state/pool.rs; swaps move sqrt_price, never liquidity).
 *  - Raydium CPMM: PoolState.lp_supply u64 @333 (raydium-cp-swap deposit.rs / withdraw.rs are its only writers).
 *  - Raydium AMM v4: AmmInfo.lp_amount u64 @720 (raydium-amm state.rs, packed, 752 bytes; deposit / withdraw).
 * While a coin is held, its pool is read every [READ_EVERY_MS_8926]; the most units seen since we started watching is the
 * baseline. Units falling [PULL_PCT_8926] % or more below it is liquidity pulled: the position is sold at once, as an
 * emergency stop (BotService; the reason carries LP_PULL and LIQ_DRAIN, so every protective exit class treats it as one and
 * no hold, plan or runner rule can keep it). A smaller fall is counted, never acted on. A pool of another program, or an
 * unreadable one, is not watched (nothing is guessed). Reads are one getAccountInfo (keyless nodes first).
 */
object LpPull8926 {
    internal const val PULL_PCT_8926 = 30.0
    private const val NOTE_PCT_8926 = 5.0
    private const val READ_EVERY_MS_8926 = 10_000L
    internal const val FRESH_MS_8926 = 30_000L
    private const val FORGET_MS_8926 = 30L * 60_000L
    private const val AMM_V4_PROGRAM_8926 = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"
    private const val CPMM_PROGRAM_8926 = "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C"
    internal const val AMM_V4_SIZE_8926 = 752
    const val REASON_8926 = "LP_PULL_LIQ_DRAIN_STOP_8926"

    /** One pool's watch: the most liquidity units seen since watching began, the latest read and when. */
    internal class Watch8926(val mint: String, val pool: String) {
        @Volatile var peak: BigInteger = BigInteger.ZERO
        @Volatile var last: BigInteger = BigInteger.ZERO
        @Volatile var lastAtMs = 0L
        @Volatile var askedAtMs = 0L
        @Volatile var noted = false
    }

    // ── pure ──

    /** Pure: the liquidity units of [mint]'s pool account [data] owned by [owner]; null when it is not a pool we read. */
    internal fun units8926(owner: String, mint: String, data: ByteArray?): BigInteger? {
        if (data == null) return null
        return when (owner) {
            OnChainDepth8038.METEORA_DAMM_V2_8418 -> {
                if (data.size < PoolLock8895.DAMM_V2_SIZE_8895 || !PoolLock8895.disc(data, PoolLock8895.DAMM_V2_POOL_DISC_8895)) null
                else if (!PoolLock8895.pairedWithQuote(mint, PoolLock8895.key(data, 168), PoolLock8895.key(data, 200))) null
                else PoolLock8895.uLe8895(data, 360, 16)
            }
            CPMM_PROGRAM_8926 -> PoolLock8895.cpmmPool8925(mint, data)?.second
            AMM_V4_PROGRAM_8926 -> {
                if (data.size != AMM_V4_SIZE_8926) null
                else if (!PoolLock8895.pairedWithQuote(mint, PoolLock8895.key(data, 400), PoolLock8895.key(data, 432))) null
                else PoolLock8895.uLe8895(data, 720, 8)
            }
            else -> null
        }
    }

    /** Pure: how far (%) [last] sits below [peak]; 0 when nothing has been read. */
    internal fun dropPct8926(peak: BigInteger, last: BigInteger): Double =
        if (peak.signum() <= 0 || last >= peak) 0.0
        else peak.subtract(last).multiply(BigInteger.valueOf(10_000L)).divide(peak).toDouble() / 100.0

    /** Pure: does a watch read at [lastAtMs] with [peak] / [last] units say the liquidity was pulled at [nowMs]? */
    internal fun pulled8926(peak: BigInteger, last: BigInteger, lastAtMs: Long, nowMs: Long): Boolean =
        nowMs - lastAtMs in 0L..FRESH_MS_8926 && dropPct8926(peak, last) >= PULL_PCT_8926

    // ── state ──

    private val watches = ConcurrentHashMap<String, Watch8926>()   // pool -> watch
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val otherProgram = ConcurrentHashMap<String, Long>()   // pool -> read as no pool we read (re-asked after 30 min)
    private val reads = AtomicLong(0L)
    private val notOurs = AtomicLong(0L)
    private val rpcFailed = AtomicLong(0L)
    private val drops = AtomicLong(0L)
    private val pulls = AtomicLong(0L)

    /**
     * BotService (each held position's tick): watch [mint]'s [pool] and say whether its liquidity was pulled. The read is in
     * the background; the answer is the latest fresh read. Returns the drop (%) when pulled, else null.
     */
    fun check8926(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val m = mint.trim(); val p = pool.trim()
        if (m.isBlank() || p.isBlank() || p == m) return null
        otherProgram[p]?.let { if (nowMs - it in 0L until FORGET_MS_8926) return null }   // a curve or a program we do not read
        val w = watches.computeIfAbsent(p) { Watch8926(m, p) }
        if (w.mint != m) return null
        if (nowMs - w.askedAtMs >= READ_EVERY_MS_8926 && inFlight.add(p)) {
            w.askedAtMs = nowMs
            if (watches.size > 400) watches.entries.removeIf { nowMs - it.value.askedAtMs > FORGET_MS_8926 }
            try { exec.execute { try { read8926(w) } catch (_: Throwable) {} finally { inFlight.remove(p) } } }
            catch (_: Throwable) { inFlight.remove(p) }
        }
        return if (pulled8926(w.peak, w.last, w.lastAtMs, nowMs)) dropPct8926(w.peak, w.last) else null
    }

    /** BotService: the pull was acted on (counted once per watch). */
    fun noteActed8926(pool: String) {
        val w = watches[pool.trim()] ?: return
        if (!w.noted) { w.noted = true; pulls.incrementAndGet() }
    }

    private fun read8926(w: Watch8926) {
        reads.incrementAndGet()
        val res = com.lifecyclebot.network.KeylessRpc8359.post8359(http, "getAccountInfo",
            JSONArray().put(w.pool).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")), "8926", maxRungs = 3)
            ?: run { rpcFailed.incrementAndGet(); return }
        val v = res.optJSONObject("value") ?: run { rpcFailed.incrementAndGet(); return }
        val units = units8926(v.optString("owner"), w.mint, OnChainDepth8038.accountData8418(v)) ?: run {
            notOurs.incrementAndGet(); watches.remove(w.pool)
            val now = System.currentTimeMillis()
            if (otherProgram.size > 4_000) otherProgram.entries.removeIf { now - it.value > FORGET_MS_8926 }
            otherProgram[w.pool] = now
            return
        }
        val before = dropPct8926(w.peak, w.last)
        if (units > w.peak) w.peak = units
        w.last = units
        w.lastAtMs = System.currentTimeMillis()
        val drop = dropPct8926(w.peak, units)
        if (drop >= NOTE_PCT_8926 && before < NOTE_PCT_8926) {
            drops.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(if (drop >= PULL_PCT_8926) "LP_PULL_DETECTED_8926" else "LP_WITHDRAWAL_NOTED_8926")
                com.lifecyclebot.engine.ForensicLogger.lifecycle("LP_WITHDRAWAL_8926",
                    "mint=${w.mint.take(10)} pool=${w.pool.take(10)} dropPct=${"%.1f".format(drop)} act=${drop >= PULL_PCT_8926}")
            } catch (_: Throwable) {}
        }
    }

    private val exec: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            { r -> Thread(r, "lp-pull-8926").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
        )
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }

    fun statusLine8926(): String =
        "lpPull8926[watching=${watches.size} reads=${reads.get()} notOurs=${notOurs.get()} rpcFailed=${rpcFailed.get()} withdrawals=${drops.get()} pulledSold=${pulls.get()} " +
            "rule=a held coin's pool (DAMM v2 liquidity / CPMM lp_supply / AMM v4 lp_amount, read every 10 s) ${PULL_PCT_8926.toInt()} %+ under its watched peak = liquidity pulled: sold at once as an emergency stop]"

    internal fun putForTest8926(mint: String, pool: String, peak: Long, last: Long, atMs: Long) {
        watches[pool] = Watch8926(mint, pool).also { it.peak = BigInteger.valueOf(peak); it.last = BigInteger.valueOf(last); it.lastAtMs = atMs; it.askedAtMs = atMs }
    }

    internal fun resetForTest8926() { watches.clear(); inFlight.clear(); otherProgram.clear() }
}
