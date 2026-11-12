package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §A_DEAD_LEADERBOARD_IS_NOT_ASKED_AGAIN — 5.0.8416: `copytrade_other sr=0% 4xx=3`.
 *
 * CopyTradeEngine.discoverTopWallets walks ExpertWallets7962.leaderboardUrls8713: pump.fun's developer-API PnL
 * leaderboard (V5.0.8713; Bearer key, asked only when one is saved) and GMGN's 7-day wallet rank (Cloudflare-fronted: a
 * non-browser client is answered 403 or a challenge page). A route that does not answer is not asked again every cycle.
 *
 * Beside the board, wallets with a proven record are produced keyless from what the app pays Helius for: SmartMoneyDiscovery7277 step 2 compiles the daily alpha list from Helius-parsed swaps of observed runners
 * (ExpertWallets7962.alphaRows8362, ranked by the lower confidence bound of realized net per trip), and
 * HeliusCadence8361.watchList8361 watches those wallets too. A dead route is disabled cleanly: a route
 * that answers 401/403/404/410, a challenge page, or three answers with no rows is benched for a day (doubling to a week)
 * and not asked while benched; one answer with rows clears it. Nothing is fabricated in its place.
 */
object LeaderboardRoutes8418 {
    const val BENCH_BASE_MS_8418 = 24L * 60 * 60 * 1000
    private const val BENCH_MAX_MS_8418 = 7L * 24 * 60 * 60 * 1000
    private const val EMPTY_LIMIT_8418 = 3

    private class State8418 { var benchedUntilMs = 0L; var level = 0; var emptyStreak = 0; var lastWhy = "" }
    private val states = ConcurrentHashMap<String, State8418>()

    /** Pure: the bench after the [level]-th consecutive dead verdict (0 = first): a day doubling, capped at a week. */
    fun benchMs8418(level: Int): Long = (BENCH_BASE_MS_8418 shl level.coerceIn(0, 3)).coerceAtMost(BENCH_MAX_MS_8418)

    /** Pure: does this answer say the route is gone or closed to us (not a transient)? */
    fun deadAnswer8418(code: Int, challenge: Boolean): Boolean =
        challenge || code == 401 || code == 403 || code == 404 || code == 410

    /** The routes of [urls] that are not benched at [nowMs], in order. */
    fun live8418(urls: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> =
        urls.filter { (states[it]?.benchedUntilMs ?: 0L) <= nowMs }

    /**
     * Record one answer of route [url]: HTTP [code] (0 = transport failure), whether it was a challenge page, and the
     * rows parsed ([rows] = -1 when the body was not parsed). Transport failures and 429 / 5xx change nothing.
     */
    @Synchronized fun note8418(url: String, code: Int, challenge: Boolean, rows: Int, nowMs: Long = System.currentTimeMillis()) {
        val st = states.getOrPut(url) { State8418() }
        when {
            rows > 0 -> { st.level = 0; st.emptyStreak = 0; st.benchedUntilMs = 0L; st.lastWhy = "" }
            deadAnswer8418(code, challenge) -> bench(st, url, if (challenge) "challenge" else "http$code", nowMs)
            code in 200..299 && rows == 0 -> {
                st.emptyStreak++
                if (st.emptyStreak >= EMPTY_LIMIT_8418) bench(st, url, "noRows", nowMs)
            }
        }
    }

    private fun bench(st: State8418, url: String, why: String, nowMs: Long) {
        st.benchedUntilMs = nowMs + benchMs8418(st.level)
        st.level = (st.level + 1).coerceAtMost(3)
        st.emptyStreak = 0
        st.lastWhy = why
        try {
            PipelineHealthCollector.labelInc("LEADERBOARD_ROUTE_BENCHED_8418")
            ForensicLogger.lifecycle("LEADERBOARD_ROUTE_BENCHED_8418",
                "route=${url.substringAfter("://").substringBefore('?').take(60)} why=$why untilMs=${st.benchedUntilMs} replacement=alpha_list_from_helius_swaps")
        } catch (_: Throwable) {}
    }

    /** V5.0.8713 — is a pump.fun developer key saved (its route is not asked without one)? */
    @Volatile private var pumpFunKey8713: Boolean? = null
    fun noteKey8713(has: Boolean) { pumpFunKey8713 = has }

    /** V5.0.8713 — a new key was saved: the pump.fun route's bench (a 401 from the old key) no longer describes it. */
    fun keyChanged8713() { states.remove(ExpertWallets7962.PUMPFUN_BOARD_URL_8713); pumpFunKey8713 = true }

    /** leaderboard8418[host=benched(why,leftH)|live ...] */
    fun statusLine8418(nowMs: Long = System.currentTimeMillis()): String {
        val rows = states.entries.sortedBy { it.key }.joinToString(" ") { (u, s) ->
            val host = u.substringAfter("://").substringBefore('/')
            if (s.benchedUntilMs > nowMs) "$host=benched(${s.lastWhy},${(s.benchedUntilMs - nowMs) / 3_600_000L}h)" else "$host=live"
        }.ifBlank { "-" }
        val key = when (pumpFunKey8713) { true -> "set"; false -> "MISSING(developers.pump.fun)"; null -> "unasked" }
        return "leaderboard8418[$rows pumpFunKey8713=$key topTraders8713=${CopyTradeEngine.boardWallets8713().size}/${ExpertWallets7962.BOARD_SIZE_8713}]"
    }

    internal fun resetForTests8418() { states.clear() }
}
