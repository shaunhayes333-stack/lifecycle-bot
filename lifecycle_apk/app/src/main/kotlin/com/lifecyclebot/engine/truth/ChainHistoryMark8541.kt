package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.HeliusCadence8361
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8541 §THE_HORIZON_PRICE_IS_ON_CHAIN — owner: "everything is available on the block chain".
 *
 * A forward label whose window closed while the app was down was priced only from keyless 1 m candles
 * (DowntimeRecovery8430, GeckoTerminal). That provider slot is shared with the live chart reads, and across every
 * restart in the 8430–8540 logs it served almost nothing: 8540 queued=150 fetched=0, gaveUp 487
 * (BOOT_CAPACITY=337, RETRY_WINDOW_EXPIRED=150) — half the day's labels lost on each build install.
 *
 * The price at the horizon is the pool's own state then. Every swap rewrites the pool's two vault token accounts, and the
 * transaction that did it carries their balances after it (meta.postTokenBalances). So, for a constant-product pool
 * (PumpSwap, Raydium v4 / CPMM, the venues OnChainDepth8038 already reads):
 *  1. the coin's pool and its vaults are found from the mint (OnChainDepth8038.historyPools8541);
 *  2. the vault's signatures are walked newest-first (getSignaturesForAddress, keyless public nodes only, at most
 *     [MAX_PAGES_8541] pages) down to the window's start;
 *  3. the earliest successful transaction INSIDE the window ([fromMs], [toMs]] is read (getTransaction) and the pool's
 *     SOL / coin balances after it give the price in SOL, observed at that block's time.
 * USD needs the SOL rate of that time: the rate the device last observed before it stopped (WalletManager, persisted)
 * and the fresh rate now must agree within [MAX_SOL_DRIFT_8541]; otherwise there is no mark (no rate is invented).
 * Nothing is inferred: no swap in the window, no pool, no rate — no mark, and the label closes exactly as before.
 */
object ChainHistoryMark8541 {
    const val SOURCE_8541 = "CHAIN_POOL_HISTORY_8541"
    const val MAX_SOL_DRIFT_8541 = 0.02
    private const val MAX_PAGES_8541 = 4
    const val CHAIN_WINDOW_MS_8541 = 30L * 60_000L
    private const val MAX_JOBS_PER_BOOT_8541 = 600
    private const val PAGE_8541 = 1000
    private const val CALLER_8541 = "label8541"
    private val BASE58_8541 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    class Read8541(val mark: LabelMark8386?, val waiting: Boolean)
    private val NONE_8541 = Read8541(null, false)
    private val WAIT_8541 = Read8541(null, true)
    private class Result8541(val mark: LabelMark8386?, val why: String)

    private val results = ConcurrentHashMap<String, Result8541>()
    private val pending = HashMap<String, MutableSet<Pair<Long, Long>>>()     // mint -> windows not yet read (guarded by lock)
    private val queued = HashSet<String>()                                    // mints with a job submitted (guarded by lock)
    private val lock = Any()
    private val jobs = AtomicLong(0)
    private val byWhy = ConcurrentHashMap<String, AtomicLong>()

    private val http by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder().connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
    private val workers: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(MAX_JOBS_PER_BOOT_8541),
            { r -> Thread(r, "label-chain-8541").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private fun key(mint: String, fromMs: Long, toMs: Long) = "$mint|$fromMs|$toMs"
    private fun note(why: String, n: Long = 1L) { byWhy.computeIfAbsent(why) { AtomicLong(0) }.addAndGet(n) }

    /**
     * ForwardReturnLabeler7731: the on-chain mark of [mint] inside ([fromMs], [toMs]] for a window that closed before
     * [bootMs]; WAITING while its read is queued inside [CHAIN_WINDOW_MS_8541] of boot. Runs only inside the app.
     */
    fun read8541(mint: String, fromMs: Long, toMs: Long, bootMs: Long, nowMs: Long): Read8541 {
        if (!BASE58_8541.matches(mint) || toMs >= bootMs || fromMs > toMs) return NONE_8541
        results[key(mint, fromMs, toMs)]?.let { return Read8541(it.mark, false) }
        if (nowMs - bootMs > CHAIN_WINDOW_MS_8541) return NONE_8541
        if (com.lifecyclebot.AATEApp.appContextOrNull() == null) return NONE_8541
        val submit = synchronized(lock) {
            val windows = pending[mint]
            if (windows != null && (fromMs to toMs) in windows) return WAIT_8541
            if (windows == null && jobs.get() >= MAX_JOBS_PER_BOOT_8541) { note("BOOT_CAPACITY"); return NONE_8541 }
            pending.getOrPut(mint) { HashSet() }.add(fromMs to toMs)
            queued.add(mint)
        }
        if (submit) {
            jobs.incrementAndGet()
            try { workers.execute { run8541(mint) } } catch (_: Throwable) {
                synchronized(lock) { queued.remove(mint); pending.remove(mint) }
                note("WORKER_REFUSED")
                return NONE_8541
            }
        }
        return WAIT_8541
    }

    private fun run8541(mint: String) {
        while (true) {
            val windows = synchronized(lock) {
                val w = pending[mint]?.toList().orEmpty()
                if (w.isEmpty()) { pending.remove(mint); queued.remove(mint); return }
                w
            }
            val got = try { readWindows8541(mint, windows) } catch (_: Throwable) { windows.associateWith { Result8541(null, "WORKER_FAILURE") } }
            for ((w, r) in got) {
                results[key(mint, w.first, w.second)] = r
                note(r.why)
            }
            if (results.size > 4_000) results.keys.take(1_000).forEach { results.remove(it) }
            synchronized(lock) { pending[mint]?.removeAll(windows.toSet()) }
        }
    }

    private fun readWindows8541(mint: String, windows: List<Pair<Long, Long>>): Map<Pair<Long, Long>, Result8541> {
        val pools = OnChainDepth8038.historyPools8541(mint)
        if (pools.isEmpty()) return windows.associateWith { Result8541(null, "NO_POOL") }
        val minFrom = windows.minOf { it.first }
        val (_, vaults) = pools.first()
        val sigs = ArrayList<Pair<String, Long>>()
        var before: String? = null
        var reachedStart = false
        for (page in 1..MAX_PAGES_8541) {
            val rows = HeliusCadence8361.signaturesTimed8541(http, vaults.first, PAGE_8541, before, keylessOnly = true, caller = CALLER_8541)
            if (rows.isEmpty()) { if (page == 1) return windows.associateWith { Result8541(null, "NO_SIGNATURES") }; break }
            sigs += rows
            before = rows.last().first
            val oldest = rows.filter { it.second > 0L }.minOfOrNull { it.second } ?: Long.MAX_VALUE
            if (oldest < minFrom || rows.size < PAGE_8541) { reachedStart = true; break }
        }
        val rate = solUsdAcross8541(
            com.lifecyclebot.engine.WalletManager.bootPriorSol8541?.first ?: Double.NaN,
            com.lifecyclebot.engine.WalletManager.bootPriorSol8541?.second ?: 0L,
            com.lifecyclebot.engine.WalletManager.freshSolPrice8163(), System.currentTimeMillis(), minFrom)
        val txs = HashMap<String, JSONObject?>()
        return windows.associateWith { (from, to) ->
            val pick = firstInWindow8541(sigs, from, to)
                ?: return@associateWith Result8541(null, if (reachedStart) "NO_SWAP_IN_WINDOW" else "HISTORY_TOO_DEEP")
            if (!(rate.isFinite() && rate > 0.0)) return@associateWith Result8541(null, "NO_SOL_RATE")
            val tx = txs.getOrPut(pick.first) { HeliusCadence8361.transaction8361(http, pick.first, keylessOnly = true, caller = CALLER_8541) }
                ?: return@associateWith Result8541(null, "NO_TRANSACTION")
            val mark = markOf8541(mint, tx, vaults, pick.second, rate, System.currentTimeMillis())
                ?: return@associateWith Result8541(null, "NOT_COIN_SOL")
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_DOWNTIME_CHAIN_8541") } catch (_: Throwable) {}
            Result8541(mark, "PRICED")
        }
    }

    /** Pure: the earliest signature whose block time is inside ([fromMs], [toMs]] (failed rows carry -1 and never match). */
    fun firstInWindow8541(sigs: List<Pair<String, Long>>, fromMs: Long, toMs: Long): Pair<String, Long>? =
        sigs.filter { it.second > 0L && it.second in fromMs..toMs }.minByOrNull { it.second }

    /**
     * Pure: the SOL/USD of a past instant [targetMs], or NaN. The rate observed last before the app stopped ([prior] at
     * [priorAtMs]) and the fresh rate [now] must agree within [MAX_SOL_DRIFT_8541]; the one nearer [targetMs] is used.
     */
    fun solUsdAcross8541(prior: Double, priorAtMs: Long, now: Double, nowMs: Long, targetMs: Long): Double {
        if (!now.isFinite() || now !in 50.0..1000.0) return Double.NaN
        if (!prior.isFinite() || prior !in 50.0..1000.0 || priorAtMs <= 0L || priorAtMs > nowMs) return Double.NaN
        if (kotlin.math.abs(now / prior - 1.0) > MAX_SOL_DRIFT_8541) return Double.NaN
        return if (kotlin.math.abs(targetMs - priorAtMs) <= kotlin.math.abs(nowMs - targetMs)) prior else now
    }

    /** Pure: every account key of a getTransaction answer, in index order (jsonParsed objects or plain strings + loaded). */
    fun accountKeys8541(tx: JSONObject): List<String> {
        val arr = tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("accountKeys") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        var parsed = false
        for (i in 0 until arr.length()) {
            val o = arr.opt(i)
            if (o is JSONObject) { parsed = true; out += o.optString("pubkey") } else out += arr.optString(i)
        }
        if (!parsed) {
            val loaded = tx.optJSONObject("meta")?.optJSONObject("loadedAddresses")
            for (k in listOf("writable", "readonly")) {
                val a: JSONArray = loaded?.optJSONArray(k) ?: continue
                for (i in 0 until a.length()) out += a.optString(i)
            }
        }
        return out
    }

    /** Pure: (mint, ui amount) of [account] after the transaction, from meta.postTokenBalances; null when absent. */
    private fun postBalance8541(tx: JSONObject, keys: List<String>, account: String): Pair<String, Double>? {
        val bals = tx.optJSONObject("meta")?.optJSONArray("postTokenBalances") ?: return null
        for (i in 0 until bals.length()) {
            val b = bals.optJSONObject(i) ?: continue
            if (keys.getOrNull(b.optInt("accountIndex", -1)) != account) continue
            val ui = b.optJSONObject("uiTokenAmount") ?: return null
            val amt = ui.optString("uiAmountString", "").toDoubleOrNull() ?: ui.optDouble("uiAmount", Double.NaN)
            return b.optString("mint") to amt
        }
        return null
    }

    /**
     * Pure: the label mark of [mint] after transaction [tx] at [blockMs]: the pool's SOL vault / coin vault
     * (OnChainDepth8038.sides8038 — one vault this coin, the other wrapped SOL) x [solUsd]. Null when not a coin/SOL read.
     */
    fun markOf8541(mint: String, tx: JSONObject, vaults: Pair<String, String>, blockMs: Long, solUsd: Double, readAtMs: Long): LabelMark8386? {
        if (tx.optJSONObject("meta")?.isNull("err") == false) return null
        val keys = accountKeys8541(tx)
        val sides = OnChainDepth8038.sides8038(mint, postBalance8541(tx, keys, vaults.first), postBalance8541(tx, keys, vaults.second)) ?: return null
        val usd = sides.first / sides.second * solUsd
        if (!usd.isFinite() || usd <= 0.0 || blockMs <= 0L || blockMs > readAtMs) return null
        return LabelMark8386(mint, "chain.pool", usd, blockMs, readAtMs, SOURCE_8541, independentBasis = true)
    }

    fun statusLine8541(): String =
        "chainHistory8541[jobs=${jobs.get()} " + byWhy.entries.sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "]"
}
