package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.857 — Runtime endpoint URL rewriter for known-dead hosts.
 *
 * COMPANION TO
 * ============
 * V5.9.855 KeyValidator (key auth verdicts)
 * V5.9.856 ApiHealthMonitor (per-host health stats)
 *
 * PROBLEM
 * =======
 * Endpoint migrations have historically required a code push:
 *   - V5.9.854 fixed 7 frontend-api.pump.fun call sites + Jupiter price.jup.ag
 *   - When the next host dies it'll take another code push to swap
 *
 * AutoEndpointMigrator interposes between consumer URL construction and the
 * HTTP client. Consumers call AutoEndpointMigrator.rewrite(url) before
 * .url(...) on Request.Builder. If the URL host matches a known-dead host,
 * we swap it for the live replacement transparently.
 *
 * STATIC RULES (curated from live probes)
 * =======================================
 * - frontend-api.pump.fun → frontend-api-v3.pump.fun
 * - price.jup.ag/v4 → lite-api.jup.ag/price/v3       (parser handles both shapes)
 * - price.jup.ag/v6 → lite-api.jup.ag/price/v3
 *
 * DYNAMIC RULES
 * =============
 * When a host's ApiHealthMonitor.successRate() drops below 0.10 over 20+
 * samples AND a known fallback exists, autoMigrate kicks in. Operators can
 * also `forceMigrate(badHost, goodHost)` at runtime from the QA surface.
 *
 * DOCTRINE
 * ========
 * - Conservative: only rewrites HOST portion of URL. Path + query untouched.
 * - Idempotent: rewriting an already-good URL is a no-op.
 * - Fail-open: any exception returns the original URL unchanged.
 * - Logs every actual rewrite to ErrorLogger at info level (audit trail).
 */
object AutoEndpointMigrator {
    private const val TAG = "AutoMigrator"

    /** Curated static rewrite rules — based on live probes (memory #146). */
    private val staticRules: Map<String, String> = mapOf(
        "frontend-api.pump.fun" to "frontend-api-v3.pump.fun",
        // price.jup.ag is DNS-dead; lite-api.jup.ag/price/v3 is the replacement.
        // We CANNOT just swap hosts here because the path also changes
        // (/v4 or /v6/price → /price/v3). Those callers should migrate
        // explicitly. Listed here as documentation:
        // "price.jup.ag" to (handled by explicit code — path change required)
    )

    /** Runtime-added rules (forceMigrate or autoMigrate detection). */
    private val dynamicRules = ConcurrentHashMap<String, String>()

    /** Track how many times each rule fired — for the audit surface. */
    private val rewriteCounter = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Returns the URL with HOST swapped if a rule matches.
     * Path, query string, scheme are preserved unchanged.
     */
    fun rewrite(url: String): String = try {
        // Cheap fast-path — if URL contains a dead-host substring, do the work.
        val combinedDead = staticRules.keys + dynamicRules.keys
        val match = combinedDead.firstOrNull { url.contains("://$it/") || url.contains("://$it?") || url.endsWith("://$it") }
        if (match == null) url
        else {
            val replacement = dynamicRules[match] ?: staticRules[match] ?: match
            val rewritten = url.replace("://$match/", "://$replacement/")
                .replace("://$match?", "://$replacement?")
            rewriteCounter.getOrPut(match) { AtomicLong(0L) }.incrementAndGet()
            try {
                ErrorLogger.debug(TAG, "↪ rewrote $match → $replacement (#${rewriteCounter[match]?.get()})")
            } catch (_: Throwable) {}
            rewritten
        }
    } catch (_: Throwable) {
        url   // fail-open
    }

    /**
     * Operator-driven runtime migration. Useful when a new host dies
     * mid-session and we don't want to wait for a code push.
     */
    fun forceMigrate(deadHost: String, liveHost: String) {
        dynamicRules[deadHost] = liveHost
        try { ErrorLogger.info(TAG, "🔧 forceMigrate $deadHost → $liveHost") } catch (_: Throwable) {}
    }

    /** Remove a dynamic rule — e.g. when operator confirms host is back. */
    fun clearMigration(deadHost: String) {
        dynamicRules.remove(deadHost)
    }

    /**
     * Health-driven auto-migration. Call periodically from a maintenance
     * task — if a host's success rate is catastrophic and we have a known
     * fallback in `fallbackMap`, install it as a dynamic rule.
     */
    fun maybeAutoMigrate(fallbackMap: Map<String, String>) {
        val snapshot = ApiHealthMonitor.snapshot()
        for ((host, stats) in snapshot) {
            val total = stats.successes.get() + stats.failures4xx.get() +
                stats.failures5xx.get() + stats.networkErrors.get()
            if (total < 20) continue   // need a meaningful sample
            if (stats.successRate() >= 0.10) continue
            val live = fallbackMap[host] ?: continue
            if (dynamicRules[host] == live) continue
            forceMigrate(host, live)
        }
    }

    /** Snapshot for QA / Pipeline Health surface. */
    fun snapshot(): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        for ((k, v) in staticRules) {
            out[k] = v to (rewriteCounter[k]?.get() ?: 0L)
        }
        for ((k, v) in dynamicRules) {
            out[k] = v to (rewriteCounter[k]?.get() ?: 0L)
        }
        return out
    }
}
