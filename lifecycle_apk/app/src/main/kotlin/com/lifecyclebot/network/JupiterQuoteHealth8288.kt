package com.lifecyclebot.network

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * V5.0.8288 — Jupiter quote client: which failures are the provider's, which are ours, and when to try the other host.
 *
 * 5.0.8286 live: jupiter_quote transport=30% routeAcceptance=21% (s=27, 4xx=38, net=63); heldQuote noRoute 32/185;
 * preflight flagged JUPITER_QUOTE degraded. What the client did:
 *
 *  - every observation lookup (mark fan-out, held-hot mark, identity repair, price fallback) ran on a 1.2 s connect /
 *    1.8 s call budget of OUR choosing and was cancelled by its caller's outer timeout — and each miss was written to
 *    the same "jupiter_quote" row the live buy / exit preflight reads, as a provider network error. Observation now
 *    records under [OBS_HOST_8288], and a failure that is our own budget / cancel ([localFailure8288]) is not recorded
 *    as a provider failure at all (HealthAwareHttp.execute isLocalFailure8288).
 *  - a trade quote had a 12 s call timeout (15 s connect, 20 s read): one stalled host spent a stop's whole window. The
 *    quote client is now [QUOTE_CONNECT_MS_8288] / [QUOTE_READ_MS_8288] / [QUOTE_CALL_MS_8288].
 *  - the keyed api.jup.ag host failed over to lite-api.jup.ag ONLY on 429. A timeout, a network / DNS error, a 401 / 403
 *    (bad or missing key) or a 5xx stayed on the dead host (or ended the ladder). Those now move to the next host
 *    ([hostFailover8288]); a route 4xx (no route, amount too small, not tradable) still walks the params on one host,
 *    since every host answers a route the same.
 *  - the API key header was sent whenever the URL contained "api.jup.ag" — which lite-api.jup.ag does. It now goes only
 *    to the keyed host ([keyedHost8288]).
 *  - retries slept a fixed 300 ms; now 300 ms x attempt plus jitter ([retrySleepMs8288]).
 * Execution safety (min-out, slippage caps, the route params) is unchanged.
 */
object JupiterQuoteHealth8288 {

    const val OBS_HOST_8288 = "jupiter_quote_obs"
    const val QUOTE_CONNECT_MS_8288 = 2_500L
    const val QUOTE_READ_MS_8288 = 3_500L
    const val QUOTE_CALL_MS_8288 = 4_500L

    /** Pure: only the keyed host gets the key header (lite-api.jup.ag also contains "api.jup.ag"). */
    fun keyedHost8288(url: String): Boolean = url.trim().lowercase().startsWith("https://api.jup.ag/")

    /** Pure: a quote failure that says the HOST is unusable (not the route): try the next host. */
    fun hostFailover8288(msg: String?): Boolean {
        val m = msg ?: return false
        if (m.contains("Cannot resolve Jupiter host") || m.contains("Jupiter GET network error") ||
            m.contains("Jupiter API 401") || m.contains("Empty Jupiter GET response") || m.contains("backoff lockout")) return true
        return Regex("Jupiter GET (403|500|502|503|504)\\b").containsMatchIn(m)
    }

    /** Pure: may a timed-out host fail over to the next one with [remainingMs] of the exit budget left (null = no budget)? */
    fun failoverAffordable8288(remainingMs: Long?): Boolean = remainingMs == null || remainingMs >= 700L

    /**
     * Pure: a transport exception that is OUR doing — a call cut by a budget we set ([budgetScoped]: an exit-budget trim
     * or an observation lookup's short clock), a thread interrupt or a cancel — not evidence about the provider.
     */
    fun localFailure8288(e: Throwable, budgetScoped: Boolean, interrupted: Boolean): Boolean {
        val msg = e.message.orEmpty()
        if (msg.contains("Canceled", ignoreCase = true) || msg.contains("budget exhausted", ignoreCase = true)) return true
        if (e is SocketTimeoutException) return budgetScoped
        if (e is InterruptedIOException) return budgetScoped || interrupted
        return false
    }

    /** Pure: sleep before retry [attempt] (1-based) with [jitterMs] in 0..199. */
    fun retrySleepMs8288(attempt: Int, jitterMs: Long): Long = 300L * attempt.coerceAtLeast(1) + jitterMs.coerceIn(0L, 199L)
}
