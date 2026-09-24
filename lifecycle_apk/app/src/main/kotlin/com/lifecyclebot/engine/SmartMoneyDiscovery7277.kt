package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7277 §THE ONLY EDGE THAT PRINTS IS KNOWING WHO BUYS FIRST.
 *
 * Operator: "go into full blown crypto degeneracy trader mindset. what's
 * missing to turn it into a money printer?" — and then: "I want 1 done now."
 *
 * Item 1 was the absence of a proprietary edge source: every entry came from
 * public scanners with the same information every other bot has, late. The
 * copy machinery existed — CopyTradeEngine (wallet list, swap-to-signal),
 * InsiderCopyEngine (signal-to-watchlist), HeliusEnhancedWS (push on tracked
 * wallets) — and its wallet list was seeded from a public leaderboard when
 * pump.fun's frontend answered, which on 5.0.7274 was 36% of the time. The
 * COPY lane had zero trades.
 *
 * This object grows the list from the one thing the bot has that a
 * leaderboard does not: the runners it has personally watched go 3x, 5x,
 * 10x. For each such mint it walks the earliest buyers on chain (Helius
 * signatures back to genesis, parsed swaps for the first block of buys) and
 * counts wallets. A wallet that was early on two or more independent
 * runners is not luck at these sample sizes; it is promoted into the copy
 * list and the Helius push subscription, and its buys arrive here 200–500 ms
 * after they land. Everything it triggers still goes through V3, FDG, the
 * mark gates and the sizing stack — this is a source, not a bypass.
 *
 * Doctrine: additive, fail-soft, never on the hot path. One cycle every
 * fifteen minutes, at most three runners mined per cycle, all on IO.
 */
object SmartMoneyDiscovery7277 {
    private const val TAG = "SmartMoney7277"
    private const val PREFS = "smart_money_7277"
    private const val CYCLE_MS = 15L * 60_000L
    private const val FIRST_DELAY_MS = 90_000L
    private const val RUNNER_MIN_PEAK_PCT = 200.0
    private const val RUNNER_MIN_CLOSED_PNL_PCT = 150.0
    private const val MAX_RUNNERS_PER_CYCLE = 3
    private const val EARLY_BUYERS_PER_RUNNER = 40
    private const val SIGNATURE_PAGES_MAX = 6
    private const val PROMOTE_AT_RUNNER_HITS = 2
    private const val MAX_TRACKED_WALLETS = 40

    private val started = AtomicBoolean(false)
    private var job: Job? = null
    private var prefs: SharedPreferences? = null
    private val minedRunners = ConcurrentHashMap.newKeySet<String>()
    private val walletHits = ConcurrentHashMap<String, MutableSet<String>>()   // wallet -> runner mints
    private val cycles = AtomicLong(0L)
    private val promoted = AtomicLong(0L)

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun start(
        ctx: Context,
        scope: CoroutineScope,
        heliusKey: () -> String,
        copyEngine: () -> CopyTradeEngine?,
        onWatchlistChanged: (List<String>) -> Unit,
    ) {
        if (!started.compareAndSet(false, true)) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        job = scope.launch(Dispatchers.IO) {
            delay(FIRST_DELAY_MS)
            while (isActive) {
                try { runCycle(heliusKey(), copyEngine(), onWatchlistChanged) } catch (t: Throwable) {
                    ErrorLogger.debug(TAG, "cycle error: ${t.message?.take(120)}")
                }
                delay(CYCLE_MS)
            }
        }
    }

    fun statusLine(): String =
        "SmartMoney7277: cycles=${cycles.get()} minedRunners=${minedRunners.size} walletsSeen=${walletHits.size} promoted=${promoted.get()}"

    private fun runCycle(key: String, engine: CopyTradeEngine?, onWatchlistChanged: (List<String>) -> Unit) {
        cycles.incrementAndGet()
        // 1. Public leaderboard seed, when it answers. Cheap, and it was the
        //    only source before this object existed.
        try {
            val added = engine?.autoDiscoverTopWallets(maxWallets = 5, minPnlSol = 25.0) ?: 0
            if (added > 0) PipelineHealthCollector.labelInc("SMART_MONEY_LEADERBOARD_SEEDED_7277")
        } catch (_: Throwable) {}

        // 2. Runners the bot itself observed.
        val runners = observedRunners().filter { it !in minedRunners }.take(MAX_RUNNERS_PER_CYCLE)
        if (runners.isEmpty() || key.isBlank() || !KeyValidator.isUsableEnhancedHeliusKey(key)) {
            if (runners.isNotEmpty()) PipelineHealthCollector.labelInc("SMART_MONEY_RUNNERS_UNMINED_NO_HELIUS_7277")
            return
        }
        for (mint in runners) {
            val buyers = try { earliestBuyers(key, mint) } catch (t: Throwable) { emptyList() }
            minedRunners.add(mint)
            PipelineHealthCollector.labelInc("SMART_MONEY_RUNNER_MINED_7277")
            for (w in buyers) walletHits.getOrPut(w) { ConcurrentHashMap.newKeySet() }.add(mint)
            ForensicLogger.lifecycle(
                "SMART_MONEY_RUNNER_MINED_7277",
                "mint=${mint.take(10)} earlyBuyers=${buyers.size} walletsSeen=${walletHits.size}",
            )
        }
        save()

        // 3. Promote wallets early on >= 2 independent runners.
        val current = engine?.getWallets()?.associateBy { it.address } ?: emptyMap()
        var changed = false
        val candidates = walletHits.entries
            .filter { it.value.size >= PROMOTE_AT_RUNNER_HITS && it.key !in current }
            .sortedByDescending { it.value.size }
        for ((wallet, hits) in candidates) {
            if ((engine?.getWallets()?.size ?: 0) >= MAX_TRACKED_WALLETS) break
            try {
                engine?.addWallet(wallet, "SMART_MONEY_x${hits.size}")
                promoted.incrementAndGet()
                changed = true
                PipelineHealthCollector.labelInc("SMART_MONEY_WALLET_PROMOTED_7277")
                ForensicLogger.lifecycle(
                    "SMART_MONEY_WALLET_PROMOTED_7277",
                    "wallet=${wallet.take(8)} runners=${hits.size} action=copy_list_and_push_subscription",
                )
            } catch (_: Throwable) {}
        }
        if (changed) {
            val all = (engine?.getWallets()?.filter { it.isActive && !it.isPaused }?.map { it.address } ?: emptyList()).distinct()
            try { onWatchlistChanged(all) } catch (_: Throwable) {}
        }
    }

    /** Mints this bot watched run: closed at >= +150%, or open with a peak >= +200%. */
    private fun observedRunners(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            TradeHistoryStore.getRecentValidClosedTrades(limit = 600, includePartials = false)
                .asSequence()
                .filter { it.mint.length >= 32 && !it.mint.contains('|') && it.pnlPct >= RUNNER_MIN_CLOSED_PNL_PCT }
                .forEach { out.add(it.mint) }
        } catch (_: Throwable) {}
        try {
            BotService.status.tokens.values
                .asSequence()
                .filter { it.position.isOpen && it.position.peakGainPct >= RUNNER_MIN_PEAK_PCT && it.mint.length >= 32 }
                .forEach { out.add(it.mint) }
        } catch (_: Throwable) {}
        return out.toList()
    }

    /**
     * Earliest buyers of [mint]: page signatures back to the token's first
     * transactions, parse the oldest block of them, keep the fee payers that
     * received the token.
     */
    private fun earliestBuyers(key: String, mint: String): List<String> {
        var before: String? = null
        var oldest: List<String> = emptyList()
        var pages = 0
        while (pages < SIGNATURE_PAGES_MAX) {
            pages++
            val page = signaturesPage(key, mint, before)
            if (page.isEmpty()) break
            oldest = page
            if (page.size < 1000) break
            before = page.last()
        }
        if (oldest.isEmpty()) return emptyList()
        val earliest = oldest.takeLast(EARLY_BUYERS_PER_RUNNER).reversed()
        return parsedBuyers(key, mint, earliest)
    }

    private fun signaturesPage(key: String, mint: String, before: String?): List<String> {
        val params = JSONObject().put("limit", 1000).put("commitment", "confirmed")
        if (!before.isNullOrBlank()) params.put("before", before)
        val payload = JSONObject()
            .put("jsonrpc", "2.0").put("id", "sm7277")
            .put("method", "getSignaturesForAddress")
            .put("params", JSONArray().put(mint).put(params))
        val req = Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=$key")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        HealthAwareHttp.execute(http, req, host = "helius").use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("result") ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.isNull("err")) continue
                val s = o.optString("signature", "")
                if (s.isNotBlank()) out.add(s)
            }
            return out
        }
    }

    private fun parsedBuyers(key: String, mint: String, signatures: List<String>): List<String> {
        if (signatures.isEmpty()) return emptyList()
        val payload = JSONObject().put("transactions", JSONArray(signatures))
        val req = Request.Builder().url("https://api.helius.xyz/v0/transactions?api-key=$key")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        HealthAwareHttp.execute(http, req, host = "helius").use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONArray(resp.body?.string() ?: return emptyList())
            val buyers = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val tx = arr.optJSONObject(i) ?: continue
                val feePayer = tx.optString("feePayer", "")
                if (feePayer.isBlank()) continue
                val transfers = tx.optJSONArray("tokenTransfers") ?: continue
                for (j in 0 until transfers.length()) {
                    val t = transfers.optJSONObject(j) ?: continue
                    if (t.optString("mint", "") != mint) continue
                    if (t.optString("toUserAccount", "") == feePayer && t.optDouble("tokenAmount", 0.0) > 0.0) {
                        buyers.add(feePayer)
                        break
                    }
                }
            }
            return buyers.toList()
        }
    }

    private fun load() {
        val p = prefs ?: return
        try {
            p.getStringSet("mined_runners", emptySet())?.let { minedRunners.addAll(it) }
            val hits = JSONObject(p.getString("wallet_hits", "{}") ?: "{}")
            for (w in hits.keys()) {
                val arr = hits.optJSONArray(w) ?: continue
                val set = ConcurrentHashMap.newKeySet<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                walletHits[w] = set
            }
        } catch (_: Throwable) {}
    }

    private fun save() {
        val p = prefs ?: return
        try {
            val hits = JSONObject()
            for ((w, set) in walletHits) hits.put(w, JSONArray(set.toList()))
            p.edit()
                .putStringSet("mined_runners", minedRunners.take(500).toSet())
                .putString("wallet_hits", hits.toString())
                .apply()
        } catch (_: Throwable) {}
    }
}
