package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.network.JupiterApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * V5.0.8853: small, optional route-QUOTE witness for native-READY Solana runners.
 * The $10 buy and six-hour FULL-QUANTITY sell are asked on ONE low-priority
 * background worker, never on discovery, entry, FDG, execution or exit threads.
 * Jupiter's taker-less responses are INDICATIVE, not binding orders or fills.
 * A failed route is missing evidence, not a token failure or a -100% return.
 */
internal object RunnerRouteQuotes8853 {
    const val MAX_ACTIVE = 24
    private const val ENTRY_WINDOW_MS = RunnerStudy8852.ENTRY_WINDOW_MS
    private const val EXIT_GRACE_MS = RunnerStudy8852.EXIT_GRACE_MS
    private const val SIX_HOURS_MS = RunnerStudy8852.SIX_HOURS_MS
    private const val MIN_REQUEST_GAP_MS = 3_000L
    private const val RETRY_GAP_MS = 20_000L
    private const val MAX_REQUESTS_PER_SIDE = 2
    private const val MAX_RECENT = 48
    private const val LAMPORTS_PER_SOL = 1_000_000_000.0
    private val SOL_MINT = JupiterApi.SOL_MINT
    private val SOLANA_MINT = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    data class QuoteLeg(
        val requestedAtMs: Long, val receivedAtMs: Long, val inRaw: Long,
        val outRaw: Long, val solUsd: Double, val router: String,
        val pool: String, val impactPct: Double,
    ) {
        fun json() = JSONObject().put("requestedMs", requestedAtMs).put("receivedMs", receivedAtMs)
            .put("inRaw", inRaw).put("outRaw", outRaw).put("solUsd", solUsd)
            .put("router", router).put("pool", pool)
            .put("impactPct", impactPct.takeIf { it.isFinite() } ?: JSONObject.NULL)
        companion object {
            fun decode(j: JSONObject) = QuoteLeg(j.optLong("requestedMs"), j.optLong("receivedMs"),
                j.optLong("inRaw"), j.optLong("outRaw"), j.optDouble("solUsd"),
                j.optString("router"), j.optString("pool"), j.optDouble("impactPct", Double.NaN))
        }
    }

    enum class State { TWO_QUOTES_INDICATIVE, ENTRY_QUOTE_UNAVAILABLE, EXIT_QUOTE_UNAVAILABLE }

    data class Result(
        val mint: String, val lane: String, val selectedAtMs: Long, val state: State,
        val buy: QuoteLeg?, val sell: QuoteLeg?, val buyAttempts: Int, val sellAttempts: Int,
        val grossSellUsd: Double?, val nominalEntryUsd: Double?, val reason: String,
    ) {
        fun journal() = JSONObject().put("kind", "RUNNER_ROUTE_QUOTE_8853")
            .put("mint", mint).put("lane", lane).put("selectedMs", selectedAtMs)
            .put("state", state.name).put("buy", buy?.json() ?: JSONObject.NULL)
            .put("sell", sell?.json() ?: JSONObject.NULL)
            .put("buyAttempts", buyAttempts).put("sellAttempts", sellAttempts)
            .put("nominalEntryUsd", nominalEntryUsd ?: JSONObject.NULL)
            .put("grossSellQuotedUsd", grossSellUsd ?: JSONObject.NULL)
            .put("reason", reason).put("chain", "solana")
            .put("quoteClass", "JUPITER_NON_BINDING_INDICATIVE")
            .put("fullBuyQuotedQuantitySold", sell != null && buy?.outRaw == sell.inRaw)
            .put("bindingBuyOrderObserved", false).put("bindingSellOrderObserved", false)
            .put("buyFillObserved", false).put("sellFillObserved", false)
            .put("realisedWalletPnl", JSONObject.NULL)
    }

    private class Episode(val mint: String, val lane: String, val selectedAtMs: Long) {
        var buy: QuoteLeg? = null
        var sell: QuoteLeg? = null
        var buyAttempts = 0
        var sellAttempts = 0
        var lastRequestMs = 0L
        var inFlight = false
        var lastError = ""
        fun json() = JSONObject().put("m", mint).put("l", lane).put("at", selectedAtMs)
            .put("buy", buy?.json() ?: JSONObject.NULL).put("sell", sell?.json() ?: JSONObject.NULL)
            .put("ba", buyAttempts).put("sa", sellAttempts).put("asked", lastRequestMs)
            .put("err", lastError)
    }

    private val active = ConcurrentHashMap<String, Episode>()
    private val recent = ArrayDeque<Result>()
    private var nominated = 0L
    private var capacityRefused = 0L
    private var unsupported = 0L
    private var paired = 0L
    private var entryMissing = 0L
    private var exitMissing = 0L
    private var lastEnqueueMs = 0L

    /** No wallet, signed transaction, token transfer or independent RPC lane. */
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8),
            java.util.concurrent.ThreadFactory { r -> Thread(r, "aate-research-route-8853").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
    private val jupiter by lazy { JupiterApi(observationOnly7397 = true) }

    /** Pure identity filter: cross-asset symbols, EVM addresses and unknown identities are never sent to Jupiter. */
    fun solanaMint8853(mint: String): Boolean = SOLANA_MINT.matches(mint) && mint != SOL_MINT

    @Synchronized fun nominate(mint: String, lane: String, selectedAtMs: Long): Boolean {
        if (!solanaMint8853(mint)) { unsupported++; return false }
        if (selectedAtMs <= 0L || lane.isBlank()) return false
        if (active.containsKey(mint)) return true
        if (active.size >= MAX_ACTIVE) { capacityRefused++; return false }
        active[mint] = Episode(mint, lane, selectedAtMs)
        nominated++
        return true
    }

    /** Called from the existing label tick; scheduling only, never a synchronous network call. */
    @Synchronized fun tick(nowMs: Long) {
        active.values.toList().forEach { p ->
            val buy = p.buy
            if (!p.inFlight && buy == null && nowMs > p.selectedAtMs + ENTRY_WINDOW_MS)
                finish(p, State.ENTRY_QUOTE_UNAVAILABLE, "ENTRY_WINDOW_EXPIRED")
            else if (!p.inFlight && buy != null && nowMs > buy.receivedAtMs + SIX_HOURS_MS + EXIT_GRACE_MS)
                finish(p, State.EXIT_QUOTE_UNAVAILABLE, "SIX_HOUR_SELL_QUOTE_MISSING")
        }
        if (AATEApp.appContextOrNull() == null || nowMs - lastEnqueueMs < MIN_REQUEST_GAP_MS) return
        val due = active.values.asSequence().filter { p ->
            if (p.inFlight || nowMs - p.lastRequestMs < RETRY_GAP_MS) false
            else if (p.buy == null) p.buyAttempts < MAX_REQUESTS_PER_SIDE && nowMs - p.selectedAtMs in 0L..ENTRY_WINDOW_MS
            else p.sellAttempts < MAX_REQUESTS_PER_SIDE && nowMs - (p.buy!!.receivedAtMs + SIX_HOURS_MS) in 0L..EXIT_GRACE_MS
        }.sortedWith(compareBy<Episode> { if (it.buy != null) 0 else 1 }
            .thenBy { it.lastRequestMs }.thenBy { it.selectedAtMs }).firstOrNull() ?: return
        val exit = due.buy != null
        due.inFlight = true; due.lastRequestMs = nowMs; lastEnqueueMs = nowMs
        if (exit) due.sellAttempts++ else due.buyAttempts++
        try {
            worker.execute {
                try { request(due, exit) }
                catch (_: Throwable) { failRequest(due, "PROVIDER_OR_ROUTE_UNAVAILABLE") }
                finally { synchronized(this) { due.inFlight = false } }
            }
        } catch (_: Throwable) { due.inFlight = false; due.lastError = "QUEUE_UNAVAILABLE" }
    }

    private fun request(p: Episode, exit: Boolean) {
        val nowMs = System.currentTimeMillis()
        val solUsd = try { WalletManager.labelSolPrice8580(nowMs) } catch (_: Throwable) { Double.NaN }
        if (!solUsd.isFinite() || solUsd <= 0.0) { failRequest(p, "SOL_USD_UNAVAILABLE"); return }
        val entrySol = EconomicUnitInvariant7061.usdToSol(10.0, solUsd)
        val amount = if (exit) p.buy?.outRaw ?: return else
            if (entrySol.isFinite() && entrySol > 0.0) (entrySol * LAMPORTS_PER_SOL).roundToLong() else 0L
        if (amount <= 0L) { failRequest(p, "AMOUNT_UNAVAILABLE"); return }
        val askedAt = System.currentTimeMillis()
        val q = try {
            if (exit) jupiter.getQuote(p.mint, SOL_MINT, amount, 500)
            else jupiter.getQuote(SOL_MINT, p.mint, amount, 300)
        } catch (_: Throwable) { failRequest(p, "PROVIDER_OR_ROUTE_UNAVAILABLE"); return }
        val receivedAt = System.currentTimeMillis()
        val pool = q.raw.optJSONArray("routePlan")?.optJSONObject(0)
            ?.optJSONObject("swapInfo")?.optString("ammKey").orEmpty().take(64)
        val leg = QuoteLeg(askedAt, receivedAt, amount, q.outAmount, solUsd, q.router.take(48),
            pool, q.priceImpactPct)
        if (exit) acceptExit(p.mint, p.selectedAtMs, leg) else acceptEntry(p.mint, p.selectedAtMs, leg)
    }

    @Synchronized private fun failRequest(p: Episode, reason: String) {
        if (active[p.mint] === p) p.lastError = reason
        try { PipelineHealthCollector.labelInc("RUNNER_ROUTE_QUOTE_FAIL_8853") } catch (_: Throwable) {}
    }

    /** Pure-ish event transition, also exercised by unit tests without network or wallet. */
    @Synchronized internal fun acceptEntry(mint: String, selectedAtMs: Long, leg: QuoteLeg): Boolean {
        val p = active[mint] ?: return false
        if (p.selectedAtMs != selectedAtMs || p.buy != null || leg.inRaw <= 0L || leg.outRaw <= 0L ||
            !leg.solUsd.isFinite() || leg.solUsd <= 0.0 || leg.requestedAtMs > leg.receivedAtMs ||
            leg.requestedAtMs < selectedAtMs || leg.receivedAtMs - selectedAtMs !in 0L..ENTRY_WINDOW_MS) return false
        p.buy = leg; p.lastError = ""
        return true
    }

    @Synchronized internal fun acceptExit(mint: String, selectedAtMs: Long, leg: QuoteLeg): Boolean {
        val p = active[mint] ?: return false
        val buy = p.buy ?: return false
        val target = buy.receivedAtMs + SIX_HOURS_MS
        if (p.selectedAtMs != selectedAtMs || leg.inRaw != buy.outRaw || leg.outRaw <= 0L ||
            !leg.solUsd.isFinite() || leg.solUsd <= 0.0 || leg.requestedAtMs > leg.receivedAtMs ||
            leg.requestedAtMs - target !in 0L..EXIT_GRACE_MS ||
            leg.receivedAtMs - target !in 0L..EXIT_GRACE_MS) return false
        p.sell = leg
        finish(p, State.TWO_QUOTES_INDICATIVE, "FULL_QUANTITY_ROUTED_QUOTE_NO_FILL")
        return true
    }

    private fun finish(p: Episode, state: State, reason: String) {
        if (!active.remove(p.mint, p)) return
        val buyUsd = p.buy?.let { EconomicUnitInvariant7061.solToUsd8418(it.inRaw / LAMPORTS_PER_SOL, it.solUsd) }
        val grossUsd = p.sell?.let { EconomicUnitInvariant7061.solToUsd8418(it.outRaw / LAMPORTS_PER_SOL, it.solUsd) }
        val r = Result(p.mint, p.lane, p.selectedAtMs, state, p.buy, p.sell,
            p.buyAttempts, p.sellAttempts, grossUsd, buyUsd, reason +
                p.lastError.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty())
        when (state) {
            State.TWO_QUOTES_INDICATIVE -> paired++
            State.ENTRY_QUOTE_UNAVAILABLE -> entryMissing++
            State.EXIT_QUOTE_UNAVAILABLE -> exitMissing++
        }
        recent.addLast(r)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        RunnerStudy8852.appendEvidence8853(r.journal())
        try { PipelineHealthCollector.labelInc("RUNNER_ROUTE_${state.name}_8853") } catch (_: Throwable) {}
    }

    @Synchronized fun encode(): String = JSONObject().put("nominated", nominated)
        .put("cap", capacityRefused).put("unsupported", unsupported).put("paired", paired)
        .put("entryMissing", entryMissing).put("exitMissing", exitMissing)
        .put("active", JSONArray().also { arr -> active.values.forEach { arr.put(it.json()) } }).toString()

    @Synchronized fun restore(raw: String?, nowMs: Long = System.currentTimeMillis()) {
        if (raw.isNullOrBlank()) return
        try {
            val j = JSONObject(raw)
            nominated = maxOf(nominated, j.optLong("nominated"))
            capacityRefused = maxOf(capacityRefused, j.optLong("cap"))
            unsupported = maxOf(unsupported, j.optLong("unsupported"))
            paired = maxOf(paired, j.optLong("paired"))
            entryMissing = maxOf(entryMissing, j.optLong("entryMissing"))
            exitMissing = maxOf(exitMissing, j.optLong("exitMissing"))
            val arr = j.optJSONArray("active") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), MAX_ACTIVE)) {
                val x = arr.optJSONObject(i) ?: continue
                val m = x.optString("m"); val at = x.optLong("at")
                if (!solanaMint8853(m) || at <= 0L || nowMs - at !in 0L..(SIX_HOURS_MS + EXIT_GRACE_MS * 3)) continue
                val p = Episode(m, x.optString("l"), at)
                p.buy = x.optJSONObject("buy")?.let { QuoteLeg.decode(it) }
                p.sell = x.optJSONObject("sell")?.let { QuoteLeg.decode(it) }
                p.buyAttempts = x.optInt("ba").coerceIn(0, MAX_REQUESTS_PER_SIDE)
                p.sellAttempts = x.optInt("sa").coerceIn(0, MAX_REQUESTS_PER_SIDE)
                p.lastRequestMs = x.optLong("asked"); p.lastError = x.optString("err")
                if (p.buy == null || (p.buy!!.outRaw > 0 && p.buy!!.receivedAtMs >= at)) active.putIfAbsent(m, p)
            }
            tick(nowMs)
        } catch (_: Exception) {
            try { PipelineHealthCollector.labelInc("RUNNER_ROUTE_RESTORE_FAILED_8853") } catch (_: Throwable) {}
        }
    }

    @Synchronized fun pendingRows(nowMs: Long): List<String> = active.values.map { p ->
        JSONObject().put("kind", "RUNNER_ROUTE_PENDING_8853")
            .put("snapshotMs", nowMs).put("episode", p.json()).toString()
    }

    @Synchronized fun line() = "routeQuote8853[selected=$nominated active=${active.size}/$MAX_ACTIVE" +
        " twoIndicative=$paired entryMissing=$entryMissing exitMissing=$exitMissing" +
        " unsupported=$unsupported capacity=$capacityRefused noFills=true]"

    @Synchronized internal fun resultsForTest(): List<Result> = recent.toList()
    @Synchronized internal fun resetForTest() {
        active.clear(); recent.clear(); nominated = 0; capacityRefused = 0
        unsupported = 0; paired = 0; entryMissing = 0; exitMissing = 0; lastEnqueueMs = 0
    }
}
