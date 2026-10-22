package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8308 — AN EMERGENCY SELL NEVER WAITS ON A ROUTE THAT IS NOT LANDING.
 *
 * 5.0.8306 live: 2bNrYy (MOONSHOT POST_MIGRATION_HOLD) went -85.7 % in 95 s. Its catastrophic exit took 26.7 s, the
 * broadcast -> confirm leg 33.6 s, and the first signed sell was EXPIRED_PROVEN. Over the same session PumpPortal landed
 * 40 % of what it sent (11 of 25 expired) while Jupiter landed 100 %. 8288 (PumpLanding8288) routes BUYS away from a
 * PumpPortal that is not landing; sells had no such rule. A failed emergency attempt (7807 B4 / 8009) even skipped the
 * Jupiter quote ladder and went straight to PumpPortal, the one route that was expiring.
 *
 * Rules, all measured from the signed-tx ledger (SignedTxLedger8085 rows: chain-resolved CONFIRMED / EXPIRED_PROVEN /
 * FAILED_ON_CHAIN, both sides — an expiry is a property of the route's blockhash and delivery, not of the side):
 *  - [poorLander8308]: a route with [MIN_RESOLVED_8308] or more resolved sends inside [WINDOW_MS_8308] and fewer than
 *    [MIN_LAND_RATE_PCT_8308] % confirmed is "not landing".
 *  - [justExpired8308]: a SELL of this mint on that route proven expired inside [JUST_EXPIRED_MS_8308].
 *  - an emergency (ProtectiveExitClass7807 ranks 1-3: capital preservation, structural, hard stop) does not send its
 *    first or direct-route attempt to such a route ([avoidPumpPortal8308]); it keeps the aggregator (Jupiter Ultra /
 *    Metis via Helius Sender / Jito) quote ladder ([keepAggregator8308]) and, when its PumpPortal send expired and no
 *    aggregator quote is in hand, re-sends through Jupiter at once ([resendViaJupiter8308]).
 *  - one signed sell in flight: every send still goes through SignedTxLedger8085, which refuses to sign while a row for
 *    (mint, SELL) is unresolved; an expiry is chain-proven (blockhash invalid, signature unseen) before any re-send.
 *  - per-route timing ([onSellState8308]): trigger -> broadcast and broadcast -> confirm for every resolved SELL row,
 *    split by route and by emergency class, printed in [statusLine8308].
 * Nothing here raises slippage, widens a stop, or skips a safety or balance check.
 */
object EmergencySellRoute8308 {

    private const val WINDOW_MS_8308 = 20L * 60_000L
    private const val MIN_RESOLVED_8308 = 5
    private const val MIN_LAND_RATE_PCT_8308 = 60
    private const val JUST_EXPIRED_MS_8308 = 180_000L

    /** One ledger row: (side, route, state, mint, createdAtMs, updatedAtMs). */
    data class Row8308(val side: String, val route: String, val state: String, val mint: String, val createdAtMs: Long, val updatedAtMs: Long)

    data class Rate8308(val resolved: Int, val landed: Int) {
        val pct: Int get() = if (resolved > 0) landed * 100 / resolved else -1
    }

    // ── pure ──

    /** Pure: route family of a ledger route name (JUPITER_ULTRA counts as JUPITER). */
    fun family8308(route: String): String {
        val r = route.trim().uppercase()
        return when {
            r.startsWith("JUPITER") -> "JUPITER"
            r.startsWith("PUMP") -> "PUMPPORTAL"
            r.startsWith("RAYDIUM") -> "RAYDIUM"
            r.isBlank() -> "UNKNOWN"
            else -> r
        }
    }

    /** Pure: [route]'s chain-resolved sends (both sides) created inside the window, and how many landed. */
    fun landRate8308(rows: List<Row8308>, route: String, nowMs: Long, windowMs: Long = WINDOW_MS_8308): Rate8308 {
        val fam = family8308(route)
        val mine = rows.filter { family8308(it.route) == fam && nowMs - it.createdAtMs in 0L..windowMs }
        val landed = mine.count { it.state == "CONFIRMED" }
        val unlanded = mine.count { it.state == "EXPIRED_PROVEN" || it.state == "FAILED_ON_CHAIN" }
        return Rate8308(landed + unlanded, landed)
    }

    /** Pure: is this route not landing (enough resolved sends, land rate under the floor)? */
    fun poorLander8308(r: Rate8308, minResolved: Int = MIN_RESOLVED_8308, minPct: Int = MIN_LAND_RATE_PCT_8308): Boolean =
        r.resolved >= minResolved && r.pct in 0 until minPct

    /** Pure: a SELL of [mint] on [route] was proven expired inside [JUST_EXPIRED_MS_8308]. */
    fun justExpired8308(rows: List<Row8308>, mint: String, route: String, nowMs: Long): Boolean {
        val fam = family8308(route)
        return rows.any {
            it.mint == mint && it.side.equals("SELL", true) && family8308(it.route) == fam &&
                it.state == "EXPIRED_PROVEN" && nowMs - it.updatedAtMs in 0L..JUST_EXPIRED_MS_8308
        }
    }

    /** Pure: is [reason] in the emergency class (capital preservation, structural, hard stop)? */
    private fun emergency8308(reason: String?): Boolean = try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }

    /** Pure: why an emergency must not go to [route] first, or null when it may. */
    fun avoidWhy8308(rows: List<Row8308>, mint: String, route: String, reason: String?, nowMs: Long): String? {
        if (!emergency8308(reason)) return null
        if (justExpired8308(rows, mint, route, nowMs)) return "JUST_EXPIRED"
        val r = landRate8308(rows, route, nowMs)
        return if (poorLander8308(r)) "LAND_RATE_${r.pct}PCT_OF_${r.resolved}" else null
    }

    /**
     * Pure: an emergency whose PumpPortal send just expired, with no aggregator quote in hand (the Jupiter ladder will not
     * run this attempt) and no other sell of the mint unresolved, re-sends through Jupiter now.
     */
    fun resendViaJupiter8308(reason: String?, jupiterQuoteInHand: Boolean, pumpJustExpired: Boolean, unresolvedSellExists: Boolean): Boolean =
        emergency8308(reason) && !jupiterQuoteInHand && pumpJustExpired && !unresolvedSellExists

    // ── live ──

    private fun ledgerRows8308(sinceMs: Long): List<Row8308> = try {
        com.lifecyclebot.network.SignedTxLedger8085.allRows8144()
            .filter { it.createdAtMs >= sinceMs || it.updatedAtMs >= sinceMs }
            .map { Row8308(it.side, it.route, it.state.name, it.mint, it.createdAtMs, it.updatedAtMs) }
    } catch (_: Throwable) { emptyList() }

    private val avoided = AtomicLong(0)
    private val ladderKept = AtomicLong(0)
    private val resent = AtomicLong(0)

    /** Executor.tryPumpPortalSell: true = do not send this (non-rescue) emergency sell to PumpPortal. */
    fun skipPumpPortalSell8308(mint: String, reason: String, labelTag: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (labelTag.contains("RESCUE")) return false   // last resort after every other route: still allowed
        val why = avoidWhy8308(ledgerRows8308(nowMs - WINDOW_MS_8308), mint, "PUMPPORTAL", reason, nowMs) ?: return false
        avoided.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("EMERGENCY_SELL_PUMPPORTAL_AVOIDED_8308")
            PipelineHealthCollector.labelInc("EMERGENCY_SELL_PUMPPORTAL_AVOIDED_8308_${why.substringBefore('_')}")
            ForensicLogger.lifecycle("EMERGENCY_SELL_PUMPPORTAL_AVOIDED_8308",
                "mint=${mint.take(10)} reason=${reason.take(60)} label=$labelTag why=$why action=jupiter_raydium_sender_first")
        } catch (_: Throwable) {}
        return true
    }

    /** Executor.skipQuoteLadder8009: true = this emergency keeps the Jupiter quote ladder (PumpPortal is not landing). */
    fun keepAggregator8308(mint: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val why = avoidWhy8308(ledgerRows8308(nowMs - WINDOW_MS_8308), mint, "PUMPPORTAL", reason, nowMs) ?: return false
        ladderKept.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("EMERGENCY_SELL_LADDER_KEPT_PUMP_NOT_LANDING_8308")
            ForensicLogger.lifecycle("EMERGENCY_SELL_LADDER_KEPT_8308", "mint=${mint.take(10)} reason=${reason.take(60)} why=$why")
        } catch (_: Throwable) {}
        return true
    }

    /** Executor.raceOrResend8308: should this attempt re-send through Jupiter right now? */
    fun shouldResendViaJupiter8308(mint: String, reason: String, jupiterQuoteInHand: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val rows = ledgerRows8308(nowMs - JUST_EXPIRED_MS_8308)
        val unresolved = try { com.lifecyclebot.network.SignedTxLedger8085.unresolvedFor(mint, "SELL") != null } catch (_: Throwable) { true }
        val go = resendViaJupiter8308(reason, jupiterQuoteInHand, justExpired8308(rows, mint, "PUMPPORTAL", nowMs), unresolved)
        if (go) {
            resent.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EMERGENCY_SELL_EXPIRED_RESEND_JUPITER_8308")
                ForensicLogger.lifecycle("EMERGENCY_SELL_EXPIRED_RESEND_JUPITER_8308", "mint=${mint.take(10)} reason=${reason.take(60)} action=fresh_quote_fresh_blockhash_now")
            } catch (_: Throwable) {}
        }
        return go
    }

    // ── per-route timing (trigger -> broadcast -> confirm) ──

    private class Agg8308 {
        var n = 0L; var sum = 0L; var max = 0L
        @Synchronized fun add(v: Long) { if (v < 0L) return; n++; sum += v; if (v > max) max = v }
        @Synchronized fun text(): String = if (n == 0L) "-" else "n$n/avg${sum / n}/max$max"
    }

    private val timing = ConcurrentHashMap<String, Agg8308>()
    private val outcomes = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: (triggerToBroadcastMs, broadcastToResolveMs) for one resolved sell row; -1 = unknown. */
    fun legs8308(triggerAtMs: Long, createdAtMs: Long, submittedAtMs: Long, resolvedAtMs: Long): Pair<Long, Long> {
        val broadcast = if (submittedAtMs > 0L) submittedAtMs else createdAtMs
        val t2b = if (triggerAtMs in 1L..broadcast) broadcast - triggerAtMs else -1L
        val b2c = if (broadcast in 1L..resolvedAtMs) resolvedAtMs - broadcast else -1L
        return t2b to b2c
    }

    /** SignedTxLedger8085.transition: a SELL row reached chain truth. */
    fun onSellState8308(mint: String, route: String, state: String, createdAtMs: Long, submittedAtMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (state != "CONFIRMED" && state != "EXPIRED_PROVEN" && state != "FAILED_ON_CHAIN") return
        try {
            val t = SellRouteSpine8144.timer8144(mint)
            val cls = if (t?.emergency == true) "EMRG" else "NORM"
            val fam = family8308(route)
            val (t2b, b2c) = legs8308(t?.triggerAtMs ?: 0L, createdAtMs, submittedAtMs, nowMs)
            outcomes.computeIfAbsent("$cls|$fam|$state") { AtomicLong(0) }.incrementAndGet()
            if (state == "CONFIRMED") StopSlip8327.noteConfirmedRoute8327(mint, fam)  // V5.0.8327 — the route of the fill
            if (state == "CONFIRMED") {
                timing.computeIfAbsent("$cls|$fam|t2b") { Agg8308() }.add(t2b)
                timing.computeIfAbsent("$cls|$fam|b2c") { Agg8308() }.add(b2c)
                if (t2b >= 0L && b2c >= 0L) timing.computeIfAbsent("$cls|$fam|t2c") { Agg8308() }.add(t2b + b2c)
            }
            if (cls == "EMRG") ForensicLogger.lifecycle("EMERGENCY_SELL_ROUTE_TIMING_8308",
                "mint=${mint.take(10)} route=$fam state=$state triggerToBroadcastMs=$t2b broadcastToResolveMs=$b2c")
        } catch (_: Throwable) {}
    }

    fun statusLine8308(nowMs: Long = System.currentTimeMillis()): String {
        val rows = ledgerRows8308(nowMs - WINDOW_MS_8308)
        val rates = listOf("PUMPPORTAL", "JUPITER", "RAYDIUM").joinToString(" ") { r ->
            val x = landRate8308(rows, r, nowMs); "$r=${x.landed}/${x.resolved}${if (poorLander8308(x)) "(NOT_LANDING)" else ""}"
        }
        val t = timing.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.text()}" }.ifBlank { "-" }
        val o = outcomes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "emergencyRoute8308[land20m{$rates} pumpAvoided=${avoided.get()} ladderKept=${ladderKept.get()} expiredResentJupiter=${resent.get()} timing{$t} outcomes{$o}]"
    }

    internal fun resetForTest8308() { avoided.set(0); ladderKept.set(0); resent.set(0); timing.clear(); outcomes.clear() }
}
