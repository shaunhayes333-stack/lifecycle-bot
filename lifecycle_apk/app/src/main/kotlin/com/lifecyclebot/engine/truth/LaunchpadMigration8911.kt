package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8911 §FOLLOW_THE_COIN_THROUGH_ITS_MIGRATION — owner, 21 Nov 18:08: "it cant be grading the coins correctly. also do we
 * correctly follow coins off bonding curve to main curve? meteora to raydium? pumpfun to pumpfun to dex?"
 *
 * What the code did. Trading followed a pump.fun graduation (PumpPortal's migration event, the DEX WS migration and the curve
 * account's complete flag all set TokenFacts.markGraduated; the sell route leaves the curve; a held coin is priced from its
 * PumpSwap vaults, GradTicks8018). Grading did not: since 8783 a forward-label mark read on a different named venue than the
 * decision's (the decision on "pump.curve", the mark on the new PumpSwap / Raydium pool) is refused as
 * OBSERVED_ONLY_VENUE_MISMATCH_8783 unless the mark is itself a chain read, and only held coins have chain reads of the new
 * pool. So a graded coin that graduated inside its hour stopped being followed at the migration: on the owner's 20-21 Nov
 * research rows 294 pump.fun hour reads ended on that refusal (and 33 others). Meteora DBC and Raydium LaunchLab coins had no
 * migration detector at all (CurveCustody8881 reads their migration flags only to refuse a custody proof), so their move from
 * the curve pool to the DAMM v2 / CPMM pool was never recognised anywhere.
 *
 * Now: a venue change is the coin's own migration — and a mark on the new venue is the same coin's price, carried — when the
 * coin's graduation is proven ([migrated8911]: the pump.fun graduation set, the row's migratedOrGraduated flag, or a DBC /
 * LaunchLab migration read here) and the mark is not on the pump.fun curve itself (a completed curve is no witness, 8165).
 * Everything else in LabelEvidence8386 stands: identity, units, decimals and time order; a <= 0.1x move on another venue still
 * needs a chain read; a >= 10x move still needs both marks chain-witnessed.
 *
 * The DBC / LaunchLab read: on such a refusal for a non-pump.fun coin whose decision venue is a pool address, that pool's
 * account is read once (keyless public RPC, KeylessRpc8359: no Helius credits; one background thread, a bounded queue, each
 * pool at most every [REREAD_MS_8911]). A DBC VirtualPool of the coin with is_migrated set, or a LaunchLab PoolState of the
 * coin with status 2 (migrated), is a proven migration: TokenFacts.markGraduated (both graduation truths) and the mint is
 * remembered here with its time.
 */
object LaunchpadMigration8911 {
    internal const val REREAD_MS_8911 = 15L * 60_000L
    private const val DBC_MIN_SIZE_8911 = 8 + 416
    private const val LAUNCHLAB_MIN_SIZE_8911 = 365
    private const val MAX_KEYS_8911 = 20_000

    private val migratedAt = ConcurrentHashMap<String, Long>()
    private val lastAsk = ConcurrentHashMap<String, Long>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val asked = AtomicLong(0L)
    private val found = ConcurrentHashMap<String, AtomicLong>()
    private val carried = AtomicLong(0L)
    private val rpcFailed = AtomicLong(0L)

    // ── pure ──

    /**
     * Pure: the launchpad a pool account ([owner], [data]) of [mint] says the coin has migrated off ("DBC" / "LAUNCHLAB"), or
     * null (another program, another coin, or still on its curve).
     */
    fun migratedRead8911(mint: String, owner: String, data: ByteArray?): String? {
        if (data == null || mint.isBlank()) return null
        fun disc(d: IntArray) = data.size >= 8 && (0 until 8).all { (data[it].toInt() and 0xFF) == d[it] }
        fun key(off: Int): String? = if (data.size < off + 32) null
            else try { io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(off, off + 32)) } catch (_: Throwable) { null }
        fun byte(off: Int) = data[off].toInt() and 0xFF
        return when (owner) {
            CurveCustody8881.DBC_PROGRAM_8881 ->
                if (data.size >= DBC_MIN_SIZE_8911 && disc(CurveCustody8881.DBC_DISC_8881) && key(136) == mint && byte(305) != 0) "DBC" else null
            CurveCustody8881.LAUNCHLAB_PROGRAM_8881 ->
                if (data.size >= LAUNCHLAB_MIN_SIZE_8911 && disc(CurveCustody8881.LAUNCHLAB_DISC_8881) && key(205) == mint && byte(17) == 2) "LAUNCHLAB" else null
            else -> null
        }
    }

    /**
     * Pure: does a venue change between a decision mark on [entryVenue] and an outcome mark on [outcomeVenue] carry the coin's
     * price, given whether its graduation is [migrated]? Only after a proven migration, and never onto the pump.fun curve.
     */
    fun carries8911(migrated: Boolean, entryVenue: String, outcomeVenue: String): Boolean =
        migrated && entryVenue.isNotBlank() && !outcomeVenue.trim().equals("pump.curve", ignoreCase = true)

    // ── state ──

    /** Is [mint]'s graduation proven (pump.fun graduation set, the row's flag, or a launchpad migration read here)? */
    private fun migrated8911(mint: String): Boolean {
        val m = mint.trim()
        if (m.isBlank()) return false
        if (migratedAt.containsKey(m)) return true
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(m) } catch (_: Throwable) { false }) return true
        return try { com.lifecyclebot.engine.BotService.status.tokens[m]?.tokenMap?.migratedOrGraduated == true } catch (_: Throwable) { false }
    }

    /**
     * LabelEvidence8386.status, on a venue refusal of [entry] vs [outcome]: is it the coin's migration? Counted when it carries;
     * for a non-pump.fun coin not yet known to have migrated, its decision pool is asked about in the background.
     */
    fun carriesNow8911(entry: LabelMark8386, outcome: LabelMark8386): Boolean {
        val migrated = migrated8911(entry.mint)
        if (!migrated) { ask8911(entry.mint, entry.venue); return false }
        val ok = carries8911(true, entry.venue, outcome.venue)
        if (ok) {
            carried.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LABEL_MIGRATION_CARRIED_8911") } catch (_: Throwable) {}
        }
        return ok
    }

    /** Ask, in the background, whether [pool] (a decision venue) is a DBC / LaunchLab curve of [mint] that has migrated. */
    private fun ask8911(mint: String, pool: String, nowMs: Long = System.currentTimeMillis()) {
        val m = mint.trim(); val p = pool.trim()
        if (m.isBlank() || p.length < 32 || p == m) return
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(m) } catch (_: Throwable) { false }) return
        lastAsk[p]?.let { if (nowMs - it in 0L until REREAD_MS_8911) return }
        if (!inFlight.add(p)) return
        lastAsk[p] = nowMs
        if (lastAsk.size > MAX_KEYS_8911) lastAsk.entries.removeIf { nowMs - it.value > REREAD_MS_8911 }
        try { exec.execute { try { read8911(m, p) } catch (_: Throwable) { rpcFailed.incrementAndGet() } finally { inFlight.remove(p) } } } catch (_: Throwable) { inFlight.remove(p) }
    }

    private fun read8911(mint: String, pool: String) {
        asked.incrementAndGet()
        val res = com.lifecyclebot.network.KeylessRpc8359.post8359(http, "getAccountInfo",
            JSONArray().put(pool).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")), "8911", maxRungs = 3)
        if (res == null) { rpcFailed.incrementAndGet(); return }
        val v = res.optJSONObject("value") ?: return
        val pad = migratedRead8911(mint, v.optString("owner"), OnChainDepth8038.accountData8418(v)) ?: return
        if (migratedAt.size > MAX_KEYS_8911) migratedAt.clear()
        migratedAt.putIfAbsent(mint, System.currentTimeMillis())
        found.computeIfAbsent(pad) { AtomicLong(0L) }.incrementAndGet()
        try { TokenFacts.markGraduated(mint, "${pad}_MIGRATED_8911") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("LAUNCHPAD_MIGRATED_8911_$pad") } catch (_: Throwable) {}
    }

    private val exec: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
            { r -> Thread(r, "launchpad-migration-8911").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
        )
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS).build()
    }

    /** Brief: marks carried through a migration, launchpad pools read and the migrations they proved. */
    fun statusLine8911(): String =
        "migration8911[carried=${carried.get()} asked=${asked.get()} found{${found.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "rpcFailed=${rpcFailed.get()} known=${migratedAt.size} rule=a label mark on a new venue is the coin's own price once its graduation " +
            "is proven (pump.fun graduation / DEX WS / curve complete; Meteora DBC is_migrated; LaunchLab status 2), never onto the dead pump.fun curve]"

    internal fun markForTest8911(mint: String) { migratedAt[mint] = 1L }
    internal fun resetForTest8911() { migratedAt.clear(); lastAsk.clear(); inFlight.clear(); carried.set(0L) }
}
