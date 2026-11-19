package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8881 §CURVE_CUSTODY_ON_THE_OTHER_LAUNCHPADS (plan W9) — owner, 20 Nov: "the meme trader isnt a pumpfun trader
 * exclusively is a solana network trader. the full network"; "2. but dont buy unlocked drain coins".
 *
 * The LP rule (LiquidityOwnership8522) exempts a coin still on its bonding curve, because a curve has no LP to pull: its
 * quote sits in a vault only the curve program can move. That exemption needs a fresh read of the curve account itself,
 * and only pump.fun curves were ever read (CurveReserves7951). A coin on a Meteora Dynamic Bonding Curve (Believe, Bags,
 * Jupiter Studio and the other DBC launchpads) or a Raydium LaunchLab curve (bonk.fun) could never be proven: there is no
 * LP to lock and no ordinary pool for the provider to report, so it waited PENDING at the buy door, or met the "LP
 * unlocked" overlay, for its whole curve life. The 8731 phone run: 29 of 32 LIVE paying-cell coins were PENDING at the door
 * (+36.3 % forward, 34 % runners); on the 13 launch-hour exports Meteora-sourced coins under five minutes made +5.3 %
 * (n1645, 5.8 % ran 100 %+) while pump.fun's made -15.7 %.
 *
 * Now the same proof is read for those two curves, from their own accounts, never from a label:
 *  - the pool account is owned by the curve program, carries that program's Anchor account discriminator, and names THIS
 *    coin as its base mint;
 *  - it is still a curve: DBC is_migrated = 0 and migration_progress = 0 (pre-bonding-curve); LaunchLab status = 0
 *    (funding) and its quote mint is wrapped SOL;
 *  - its quote vault (read jsonParsed) is an initialised wrapped-SOL token account whose owner is the program's own vault
 *    authority PDA;
 *  - the SOL in custody, the smaller of the vault balance and the curve's own quote reserve, clears the curve exit floor
 *    (LaneAgeLiquidityTruth8140.CURVE_EXIT_MIN_REAL_SOL_8319, the pump.fun curve's bar).
 * A wrong offset fails the mint, discriminator or vault check, so a misread can only leave a coin PENDING as before. The
 * proof is good for 30 s (the pump.fun account proof's window). Before migration DBC lets only trading fees be claimed
 * (surplus and leftover withdrawals come after completion or migration) and LaunchLab has no instruction that withdraws
 * curve liquidity (docs.meteora.ag DBC accounts and permissions; docs.raydium.io LaunchLab accounts).
 *
 * Layouts (offsets include the 8-byte discriminator):
 *  - DBC VirtualPool, zero-copy, 416 bytes after the discriminator (meteora-sdk 0.7.0 src/dbc.rs, the program's field
 *    order): volatility_tracker 64, config, creator, base_mint @136, base_vault @168, quote_vault @200, base_reserve @232,
 *    quote_reserve @240, four fee u64s, sqrt_price u128 @280, activation_point @296, pool_type @304, is_migrated @305,
 *    two surplus flags, migration_progress @308. Discriminator sha256("account:VirtualPool")[0..8].
 *  - LaunchLab PoolState, Borsh (raydium-launchlab-sdk 0.1.5 idl.json; no serialization key): epoch, auth_bump, status @17,
 *    two decimals, migrate_type, supply, total_base_sell, virtual_base, virtual_quote, real_base, real_quote @61, four fee
 *    u64s, vesting 40, global_config, platform_config, base_mint @205, quote_mint @237, base_vault @269, quote_vault @301,
 *    creator @333. Discriminator [247,237,227,245,215,195,222,70] (the IDL's; sha256("account:PoolState") gives the same).
 *  - Vault authorities: the DBC pool_authority PDA and the LaunchLab vault authority (OnChainHolderConcentration7379
 *    .VENUE_AUTHORITIES_8418, checked 8418).
 *
 * Reads: keyless public nodes first, Helius behind them (KeylessRpc8359); one in flight per pool, at most one every 10 s
 * per pool, a pool that is not one of these curves is not asked again for 30 minutes. Nothing here admits, sizes or trades.
 */
object CurveCustody8881 {
    const val DBC_PROGRAM_8881 = "dbcij3LWUppWqq96dh6gJWwBifmcGfLSB5D4DuSMaqN"
    const val LAUNCHLAB_PROGRAM_8881 = "LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eADduJ3uj"
    const val DBC_AUTHORITY_8881 = "FhVo3mqL8PW5pH5U2CN4XE33DokiyZnUwuGpH2hmHLuM"
    const val LAUNCHLAB_AUTHORITY_8881 = "WLHv2UAZm6z4KyaaELi5pjdbJh6RESMva1Rnn8pJVVh"
    const val VENUE_DBC_8881 = "METEORA_DBC"
    const val VENUE_LAUNCHLAB_8881 = "RAYDIUM_LAUNCHLAB"
    private const val WSOL_8881 = OnChainDepth8038.WSOL
    private const val TOKEN_PROGRAM_8881 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    internal val DBC_DISC_8881 = intArrayOf(213, 224, 5, 209, 98, 69, 119, 92)
    internal val LAUNCHLAB_DISC_8881 = intArrayOf(247, 237, 227, 245, 215, 195, 222, 70)
    private const val DBC_SIZE_8881 = 8 + 416
    private const val LAUNCHLAB_MIN_SIZE_8881 = 365
    private const val PROOF_MS_8881 = 30_000L
    private const val REREAD_MS_8881 = 10_000L
    private const val NOT_CURVE_RETRY_MS_8881 = 30L * 60_000L
    private const val TIMEOUT_S_8881 = 3L

    /** One decoded curve: its venue, pool, quote vault, the curve's own quote reserve (SOL) and the vault authority it needs. */
    data class Curve8881(val venue: String, val pool: String, val quoteVault: String, val reserveSol: Double, val authority: String)

    /** One custody proof: [sol] in program custody on [pool] at [atMs]. */
    data class Custody8881(val mint: String, val pool: String, val venue: String, val sol: Double, val atMs: Long)

    private val custody = ConcurrentHashMap<String, Custody8881>()   // mint -> latest proof
    private val notCurve = ConcurrentHashMap<String, Long>()          // pool -> when it read as no curve of ours
    private val lastAsk = ConcurrentHashMap<String, Long>()           // pool -> last read started
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val asked = AtomicLong(0L)
    private val proven = AtomicLong(0L)
    private val notCurveN = AtomicLong(0L)
    private val belowFloor = AtomicLong(0L)
    private val vaultRefused = AtomicLong(0L)
    private val rpcFailed = AtomicLong(0L)
    private val byVenue = ConcurrentHashMap<String, AtomicLong>()

    // ── pure ──

    private fun disc(data: ByteArray, d: IntArray): Boolean = data.size >= 8 && (0 until 8).all { (data[it].toInt() and 0xFF) == d[it] }

    private fun key(data: ByteArray, off: Int): String? =
        if (data.size < off + 32) null
        else try { io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(off, off + 32)) } catch (_: Throwable) { null }

    private fun u64(data: ByteArray, off: Int): Double {
        var v = 0.0
        var m = 1.0
        for (i in 0 until 8) { v += (data[off + i].toInt() and 0xFF) * m; m *= 256.0 }
        return v
    }

    private fun byte(data: ByteArray, off: Int): Int = data[off].toInt() and 0xFF

    /** Pure: [data] of [pool] as a Meteora DBC VirtualPool of [mint] still on its curve, or null. */
    private fun dbc8881(mint: String, pool: String, data: ByteArray): Curve8881? {
        if (data.size < DBC_SIZE_8881 || !disc(data, DBC_DISC_8881)) return null
        if (key(data, 136) != mint) return null
        if (byte(data, 305) != 0 || byte(data, 308) != 0) return null   // is_migrated, migration_progress (0 = pre-bonding curve)
        val vault = key(data, 200) ?: return null
        return Curve8881(VENUE_DBC_8881, pool, vault, u64(data, 240) / 1e9, DBC_AUTHORITY_8881)
    }

    /** Pure: [data] of [pool] as a Raydium LaunchLab PoolState of [mint], quoted in SOL and still funding, or null. */
    private fun launchLab8881(mint: String, pool: String, data: ByteArray): Curve8881? {
        if (data.size < LAUNCHLAB_MIN_SIZE_8881 || !disc(data, LAUNCHLAB_DISC_8881)) return null
        if (byte(data, 17) != 0) return null   // status: 0 funding, 1 waiting for migration, 2 migrated
        if (key(data, 205) != mint || key(data, 237) != WSOL_8881) return null
        val vault = key(data, 301) ?: return null
        return Curve8881(VENUE_LAUNCHLAB_8881, pool, vault, u64(data, 61) / 1e9, LAUNCHLAB_AUTHORITY_8881)
    }

    /** Pure: a pool account ([owner], [data]) as one of the two curves of [mint], or null. */
    fun curve8881(mint: String, pool: String, owner: String, data: ByteArray?): Curve8881? = when {
        data == null || mint.isBlank() -> null
        owner == DBC_PROGRAM_8881 -> dbc8881(mint, pool, data)
        owner == LAUNCHLAB_PROGRAM_8881 -> launchLab8881(mint, pool, data)
        else -> null
    }

    /** Pure: the SOL in a quote vault [value] (getAccountInfo, jsonParsed) that is an initialised WSOL account owned by [authority]. */
    fun vaultSol8881(value: JSONObject?, authority: String): Double? {
        if (value == null || value.optString("owner") != TOKEN_PROGRAM_8881) return null
        val info = value.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: return null
        if (info.optString("mint") != WSOL_8881 || info.optString("owner") != authority || info.optString("state") != "initialized") return null
        val lamports = info.optJSONObject("tokenAmount")?.optString("amount")?.toLongOrNull() ?: return null
        return if (lamports >= 0L) lamports / 1e9 else null
    }

    /** Pure: the SOL in custody — the smaller of the curve's reserve and its vault — when it clears the curve exit floor. */
    fun custodySol8881(reserveSol: Double, vaultSol: Double?): Double? {
        if (vaultSol == null || !vaultSol.isFinite() || !reserveSol.isFinite()) return null
        val sol = minOf(reserveSol, vaultSol)
        return if (sol >= com.lifecyclebot.engine.LaneAgeLiquidityTruth8140.CURVE_EXIT_MIN_REAL_SOL_8319) sol else null
    }

    // ── state ──

    /** The fresh custody proof of [mint] ([maxAgeMs], the pump.fun account proof's 30 s by default), or null. */
    fun custody8881(mint: String, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = PROOF_MS_8881): Custody8881? =
        custody[mint.trim()]?.takeIf { nowMs - it.atMs in -5_000L..maxAgeMs }

    private fun askable(mint: String, pool: String, nowMs: Long): Boolean {
        if (mint.isBlank() || pool.isBlank() || pool == mint) return false
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) } catch (_: Throwable) { false }) return false
        notCurve[pool]?.let { if (nowMs - it in 0L until NOT_CURVE_RETRY_MS_8881) return false }
        lastAsk[pool]?.let { if (nowMs - it in 0L until REREAD_MS_8881) return false }
        return true
    }

    /** Ask for [pool]'s custody proof for [mint] in the background (the LP door: a pool it could not prove). */
    fun request8881(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()) {
        val m = mint.trim(); val p = pool.trim()
        if (!askable(m, p, nowMs) || !inFlight.add(p)) return
        lastAsk[p] = nowMs
        trim8881(nowMs)
        try { exec.execute { try { read8881(m, p) } finally { inFlight.remove(p) } } }
        catch (_: Throwable) { inFlight.remove(p) }
    }

    /**
     * TokenSafetyChecker (LIVE, a provider flagged the LP unlocked): the custody proof of [mint] on [pool], read now when no
     * fresh one is held, so the report it is building and the proof agree. Blocking (two reads, 3 s each at most).
     */
    fun prove8881(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()): Custody8881? {
        val m = mint.trim(); val p = pool.trim()
        custody8881(m, nowMs)?.let { if (p.isBlank() || it.pool == p) return it }
        if (!askable(m, p, nowMs) || !inFlight.add(p)) return custody8881(m, nowMs)
        lastAsk[p] = nowMs
        return try { read8881(m, p) } finally { inFlight.remove(p) }
    }

    private fun read8881(mint: String, pool: String): Custody8881? {
        asked.incrementAndGet()
        val res = rpc("getAccountInfo", JSONArray().put(pool).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            ?: run { rpcFailed.incrementAndGet(); return null }
        val v = res.optJSONObject("value")
        val curve = curve8881(mint, pool, v?.optString("owner").orEmpty(), OnChainDepth8038.accountData8418(v))
        if (curve == null) {
            notCurve[pool] = System.currentTimeMillis(); notCurveN.incrementAndGet()
            custody.remove(mint)
            return null
        }
        val vr = rpc("getAccountInfo", JSONArray().put(curve.quoteVault).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
            ?: run { rpcFailed.incrementAndGet(); return null }
        val vaultSol = vaultSol8881(vr.optJSONObject("value"), curve.authority)
        if (vaultSol == null) {
            vaultRefused.incrementAndGet(); custody.remove(mint)
            try { PipelineHealthCollector.labelInc("CURVE_CUSTODY_VAULT_REFUSED_8881_${curve.venue}") } catch (_: Throwable) {}
            return null
        }
        val sol = custodySol8881(curve.reserveSol, vaultSol)
        if (sol == null) { belowFloor.incrementAndGet(); custody.remove(mint); return null }
        val c = Custody8881(mint, pool, curve.venue, sol, System.currentTimeMillis())
        custody[mint] = c
        proven.incrementAndGet()
        byVenue.computeIfAbsent(curve.venue) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("CURVE_CUSTODY_PROVEN_8881_${curve.venue}") } catch (_: Throwable) {}
        return c
    }

    private fun trim8881(nowMs: Long) {
        if (lastAsk.size > 4_000) lastAsk.entries.removeIf { nowMs - it.value > REREAD_MS_8881 }
        if (notCurve.size > 8_000) notCurve.entries.removeIf { nowMs - it.value > NOT_CURVE_RETRY_MS_8881 }
        if (custody.size > 2_000) custody.entries.removeIf { nowMs - it.value.atMs > PROOF_MS_8881 }
    }

    private val exec: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
            { r -> Thread(r, "curve-custody-8881").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(TIMEOUT_S_8881, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S_8881, TimeUnit.SECONDS).build()
    }

    private fun rpc(method: String, params: JSONArray): JSONObject? =
        com.lifecyclebot.network.KeylessRpc8359.post8359(http, method, params, "8881", maxRungs = 3)

    /** Brief: reads, proofs by venue and what each refused read met. */
    fun statusLine8881(): String =
        "curveCustody8881[asked=${asked.get()} proven=${proven.get()} byVenue{${byVenue.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "notCurve=${notCurveN.get()} belowFloor=${belowFloor.get()} vaultRefused=${vaultRefused.get()} rpcFailed=${rpcFailed.get()} held=${custody.size} " +
            "rule=a Meteora DBC / Raydium LaunchLab curve proven from its own account and vault: program, discriminator, base mint, still a curve, WSOL vault held by the program's authority, custody >= 0.5 SOL; 30 s]"

    internal fun putForTest8881(c: Custody8881) { custody[c.mint] = c }
    internal fun resetForTest8881() { custody.clear(); notCurve.clear(); lastAsk.clear(); inFlight.clear() }
}
