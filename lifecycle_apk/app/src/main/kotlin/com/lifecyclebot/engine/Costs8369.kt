package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8369 — WHAT A 0.029 SOL ROUND TRIP REALLY PAYS, AND THE THREE LEAKS THIS BUILD CLOSES.
 *
 * 5.0.8363 live (0.136 SOL wallet, 0.029 SOL ticket): measured round trip MOONSHOT 8.62 % (n7), SHITCOIN 7.24 % (n2)
 * against a 7.51 % model. Three of the components were leaks:
 *
 *  1. RENT. Every buy opens an associated token account (0.00203928 SOL = 7.0 % of a 0.029 ticket). The reclaimer
 *     (RentReclaimer7927) only ran on ExitRegret7752.tick, behind the learning ticks, and never ran (lastRunAgoSec=-1);
 *     the after-exit close was never queued because LivePositionCloseAuthority.finalizeClosed returned early on a close
 *     the ledger already held (afterExit8151=0 queued8151=0). The model still printed rent=0.00 %.
 *     Now: an always-on clock (BotService.reconcileWalletOffLoop8094, every live loop), a boot sweep, the after-exit
 *     close on every full exit, rentLocked8369 on the status line, and the model charges the rent that is not coming
 *     back ([rentLossRate8369]).
 *  2. ROUTE. Un-graduated pump.fun curve coins were bought through PumpPortal (0.5 %/side local-trade fee, landed 3 of
 *     17) although Jupiter may route the pump.fun curve; its quoted fee must be measured and landed 18 of 18. A curve buy now goes Jupiter
 *     first whenever Jupiter quotes the mint ([jupiterFirstForBuy8369]); PumpPortal only when it has no route. The
 *     model's route fee follows the route actually chosen ([modelRouteFeePctPerSide8369]).
 *  3. FIXED LEG. Every entry paid the URGENT Sender tip (200,000 lamports) plus the High CU price — about 0.8 % of a
 *     0.029 ticket per buy (PumpPortal: 0.0002 SOL priority + 0.0002 SOL tip = 1.4 %). An ordinary (not grabbed) entry
 *     now keeps tip + priority within [ENTRY_FIXED_CAP_SHARE_8369] of the ticket ([entryTipLamports8369]) unless the
 *     landing meter shows capped entries failing; when Sender's own minimum tip does not fit the cap the entry goes by
 *     the standard RPC / Jupiter path (tip 0). Sells (stops, emergencies, every exit) are untouched: they pay to land.
 *
 * The app's trading fee is the owner's and is not touched here. superseded by V5.0.8418 — was "0.5 %/side": the app fee
 * is 0.5 % on the BUY only (CostTrim8151.BUY_APP_FEE_PCT_8418, BuyAppFee8418), none on the sell
 * (CostTrim8151.SELL_APP_FEE_PCT_8418); winning sells pay PerformanceFees8391 on realised net profit.
 */
object Costs8369 {

    /** An ordinary entry's Sender tip + CU priority may take at most this share of the ticket. */
    const val ENTRY_FIXED_CAP_SHARE_8369 = 0.005
    /** Ticket assumed before the first confirmed buy (the route-minimum ticket of the 5.0.8363 report). */
    const val DEFAULT_TICKET_SOL_8369 = 0.029
    /** A Jupiter route probe for a curve buy may take this long; an unanswered probe keeps PumpPortal first. */
    const val JUP_PROBE_BUDGET_MS_8369 = 1_200L
    private const val JUP_QUOTE_OK_TTL_MS_8369 = 60_000L
    private const val JUP_QUOTE_NO_TTL_MS_8369 = 30_000L
    /** Landing judgement for capped entries: resolved rows needed and the land rate below which the cap is lifted. */
    private const val CAP_MIN_RESOLVED_8369 = 3
    private const val CAP_MIN_LAND_PCT_8369 = 50
    private const val CAP_WINDOW_MS_8369 = 120L * 60_000L

    private val capStartMs8369 = System.currentTimeMillis()

    // ── rent ──

    /** Pure: is a reclaim pass due on the always-on tick (live, after the first delay, once per interval)? */
    fun reclaimPassDue8369(nowMs: Long, startedAtMs: Long, lastRunMs: Long, live: Boolean, firstDelayMs: Long, intervalMs: Long): Boolean =
        live && nowMs - startedAtMs >= firstDelayMs && (lastRunMs <= 0L || nowMs - lastRunMs >= intervalMs)

    /** Pure: may a sweep (boot or periodic) close an empty account? Never a protected mint or a known-unclosable account. */
    fun sweepEligible8369(protectedMint: Boolean, unclosable: Boolean): Boolean = !protectedMint && !unclosable

    /**
     * Pure: the share of opened token-account rent that is not coming back. Unclosable (Token-2022 withheld fees) and
     * stuck accounts are lost; a live reclaimer that never completed a listing past its first interval reclaims nothing.
     */
    fun rentLossRate8369(unclosable: Int, closed: Long, stuck: Int, listedOnce: Boolean, overdue: Boolean): Double {
        if (!listedOnce && overdue) return 1.0
        val lost = (unclosable.coerceAtLeast(0) + stuck.coerceAtLeast(0)).toDouble()
        val total = lost + closed.coerceAtLeast(0L).toDouble()
        return if (total <= 0.0) 0.0 else (lost / total).coerceIn(0.0, 1.0)
    }

    // ── route ──

    /**
     * Pure: should a PumpPortal-eligible BUY go to Jupiter first?
     * V5.0.8413 — operator doctrine: Helius/Jupiter FIRST; PumpPortal only when it is the only route. Jupiter routes the
     * pump.fun curve fee-free (landed 18/18) while PumpPortal landed 1/7 on 5.0.8412 (3/17 on 8341). The old rule kept
     * PumpPortal first on an unanswered quote probe (null) and globally whenever Jupiter's recent buys stalled, so a
     * slow probe sent the buy down the route that does not land. Now PumpPortal goes first only when Jupiter answered
     * "no route" for this mint, or when this mint's previous attempt already went Jupiter-first ([recentlyTried]: the
     * retry is the remaining route, so a Jupiter miss can never choke the buy). [jupiterLandFailing] is reported only.
     */
    @Suppress("UNUSED_PARAMETER")
    fun jupiterFirstForBuy8369(venue: String, jupiterQuotes: Boolean?, recentlyTried: Boolean, jupiterLandFailing: Boolean): Boolean {
        if (venue == "PUMPSWAP" || venue == "RAYDIUM") return true
        if (recentlyTried) return false
        return jupiterQuotes != false
    }

    /** Pure: is a cached Jupiter quote answer still usable? */
    fun quoteCacheFresh8369(ok: Boolean, atMs: Long, nowMs: Long): Boolean =
        nowMs - atMs in 0L..(if (ok) JUP_QUOTE_OK_TTL_MS_8369 else JUP_QUOTE_NO_TTL_MS_8369)

    private val quoteSeen8369 = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val routeOf8369 = ConcurrentHashMap<String, String>()
    private val jupiterChosen8369 = AtomicLong(0)
    private val pumpChosen8369 = AtomicLong(0)

    fun cachedJupiterQuotes8369(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean? {
        val e = quoteSeen8369[mint] ?: return null
        return if (quoteCacheFresh8369(e.first, e.second, nowMs)) e.first else null
    }

    fun noteJupiterQuote8369(mint: String, ok: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        quoteSeen8369[mint] = ok to nowMs
        if (quoteSeen8369.size > 500) quoteSeen8369.entries.removeIf { nowMs - it.value.second > JUP_QUOTE_OK_TTL_MS_8369 }
    }

    /** The route chosen for a PumpPortal-eligible buy of [mint] ("JUPITER" / "PUMPPORTAL"). */
    fun noteBuyRoute8369(mint: String, route: String) {
        if (mint.isBlank()) return
        if (route == "JUPITER") jupiterChosen8369.incrementAndGet() else pumpChosen8369.incrementAndGet()
        routeOf8369[mint] = route
        if (routeOf8369.size > 500) routeOf8369.clear()
    }

    /** Pure: the share of eligible buys that went Jupiter, or null with fewer than 3 choices. */
    fun jupiterShare8369(jupiter: Long, pump: Long): Double? {
        val n = jupiter + pump
        return if (n < 3L) null else jupiter.toDouble() / n.toDouble()
    }

    /**
     * Pure: the BUY route the model prices for a mint. A non-pump mint and a graduated pool go Jupiter; a curve mint
     * takes the route chosen for it, else Jupiter (V5.0.8413: Jupiter first; PumpPortal only where it was the only route).
     */
    fun plannedBuyRoute8369(pumpMint: Boolean, venue: String, chosen: String?, jupiterShare: Double?): String = when {
        !pumpMint -> "JUPITER"
        venue == "PUMPSWAP" || venue == "RAYDIUM" -> "JUPITER"
        chosen != null && (chosen == "JUPITER" || chosen == "PUMPPORTAL") -> chosen
        // V5.0.8413 — Jupiter first is the doctrine; PumpPortal is priced only for a mint that was actually routed there.
        else -> "JUPITER"
    }

    /** Pure: the SELL route a mint's exit takes first (SellRouteEndpoint8144.routeOrder8144; a non-pump mint: Jupiter). */
    fun plannedSellRoute8369(pumpMint: Boolean, venue: String, jupiterOk: Boolean = true): String = when {
        !pumpMint -> "JUPITER"
        venue == "RAYDIUM" -> "RAYDIUM"
        venue == "AGGREGATOR_ONLY" -> "JUPITER"
        jupiterOk -> "JUPITER"   // V5.0.8390 — a curve / PumpSwap exit sells through Jupiter first (Executor.shouldTryPumpDirectFirst6099)
        else -> "PUMPPORTAL"
    }

    private data class QuoteFee8391(val pct: Double, val atMs: Long)
    private val jupiterFees8391 = ConcurrentHashMap<String, QuoteFee8391>()
    private const val SOL8391 = "So11111111111111111111111111111111111111112"

    internal fun quotedFeePct8391(json: org.json.JSONObject, v2: Boolean): Double? {
        val bps = if (v2) json.optDouble("feeBps", Double.NaN)
            else if (json.has("platformFee") && json.isNull("platformFee")) 0.0
            else json.optJSONObject("platformFee")?.optDouble("feeBps", Double.NaN) ?: Double.NaN
        return bps.takeIf { it.isFinite() && it in 0.0..10000.0 }?.div(100.0)
    }

    /** Quotes already include these fees in outAmount. Cache for independent mid-price modelling only; never subtract again. */
    fun noteJupiterQuote8391(input: String, output: String, json: org.json.JSONObject, v2: Boolean, now: Long = System.currentTimeMillis()) {
        val key = when { input == SOL8391 && output != SOL8391 -> "$output|BUY"
            output == SOL8391 && input != SOL8391 -> "$input|SELL"; else -> return }
        val pct = quotedFeePct8391(json, v2) ?: return
        jupiterFees8391[key] = QuoteFee8391(pct, now)
        if (jupiterFees8391.size > 1024) jupiterFees8391.entries.sortedBy { it.value.atMs }
            .take(jupiterFees8391.size - 1024).forEach { jupiterFees8391.remove(it.key, it.value) }
    }

    internal fun routeFee8391(route: String, mint: String, side: String, now: Long = System.currentTimeMillis()): Double {
        if (route !in setOf("JUPITER", "JUPITER_ULTRA")) return CostTrim8151.routeFeePctPerSide8151(route)
        val quote = jupiterFees8391["$mint|$side"]
        // No quote is not proof of a zero fee. Swap V2 charges 50bps for new tokens.
        return if (quote != null && now - quote.atMs in 0L..60_000L) quote.pct else 0.5
    }

    /** Pure: the route fee per side of a round trip bought on [buyRoute] and sold on [sellRoute]. */
    fun routeFeePerSide8369(buyRoute: String, sellRoute: String): Double =
        (CostTrim8151.routeFeePctPerSide8151(buyRoute) + CostTrim8151.routeFeePctPerSide8151(sellRoute)) / 2.0

    private fun venueOf8369(mint: String): String {
        val cached = try { com.lifecyclebot.engine.sell.SellRouteVenueProbe8144.cached8144(mint).name } catch (_: Throwable) { "UNKNOWN" }
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }
        return if (cached == "UNKNOWN" && graduated) "PUMPSWAP" else cached
    }

    /** NetEdge8030.componentsPct8151: the route fee per side for [mint], from the routes it is actually bought / sold on. */
    fun modelRouteFeePctPerSide8369(mint: String): Double {
        val pump = try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false }
        val venue = if (pump) venueOf8369(mint) else "UNKNOWN"
        val buy = plannedBuyRoute8369(pump, venue, routeOf8369[mint], jupiterShare8369(jupiterChosen8369.get(), pumpChosen8369.get()))
        val jupiterOk = try {
            com.lifecyclebot.engine.sell.SellRouteEndpoint8144.peek8144(mint, com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Route8144.JUPITER,
                System.currentTimeMillis()) == com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Admit8144.ALLOWED
        } catch (_: Throwable) { false }
        val sell = plannedSellRoute8369(pump, venue, jupiterOk)
        return (routeFee8391(buy, mint, "BUY") + routeFee8391(sell, mint, "SELL")) / 2.0
    }

    // ── fixed leg of an entry ──

    /**
     * Pure: an ordinary entry's Sender tip (lamports). Tip + [priorityLamports] stay within
     * [ENTRY_FIXED_CAP_SHARE_8369] of [ticketLamports], never above [legacyTip]; the tip never goes under Sender's
     * minimum (when the priority alone fills the cap, the minimum is paid); when the minimum itself does not fit the cap,
     * 0 — no Sender envelope, the standard RPC / Jupiter path. [capFailing] (capped entries not landing) keeps [legacyTip].
     */
    fun entryTipLamports8369(legacyTip: Long, ticketLamports: Long, priorityLamports: Long, senderMinTip: Long, capFailing: Boolean): Long {
        if (capFailing || ticketLamports <= 0L || legacyTip <= 0L) return legacyTip
        val cap = (ticketLamports.toDouble() * ENTRY_FIXED_CAP_SHARE_8369).toLong()
        if (senderMinTip > cap) return 0L
        val budget = cap - priorityLamports.coerceAtLeast(0L)
        return minOf(legacyTip, maxOf(senderMinTip, budget))
    }

    /**
     * Pure: are capped entries on this route group ([pumpRoute] = PumpPortal, else every other buy route) failing to
     * land? Only rows created since the cap went live count, so a route's failures at the old urgent tip do not lift it.
     */
    fun capLandFailing8369(rows: List<PumpLanding8288.Row8288>, pumpRoute: Boolean, sinceMs: Long, nowMs: Long): Boolean {
        val mine = rows.filter {
            it.side.equals("BUY", true) && it.route.equals("PUMPPORTAL", true) == pumpRoute &&
                it.createdAtMs >= sinceMs && nowMs - it.createdAtMs in 0L..CAP_WINDOW_MS_8369
        }
        val landed = mine.count { it.state == "CONFIRMED" }
        val resolved = landed + mine.count { it.state == "EXPIRED_PROVEN" || it.state == "FAILED_ON_CHAIN" }
        return resolved >= CAP_MIN_RESOLVED_8369 && landed * 100 < CAP_MIN_LAND_PCT_8369 * resolved
    }

    private fun capFailingNow8369(pumpRoute: Boolean): Boolean = try {
        val now = System.currentTimeMillis()
        capLandFailing8369(com.lifecyclebot.network.SignedTxLedger8085.rows8288(maxOf(capStartMs8369, now - CAP_WINDOW_MS_8369)), pumpRoute, capStartMs8369, now)
    } catch (_: Throwable) { false }

    /** Executor.costAggregatorFirst8151: are Jupiter / non-PumpPortal buys failing to land (window, any tip level)? */
    fun jupiterBuysFailing8369(): Boolean = try {
        val now = System.currentTimeMillis()
        capLandFailing8369(com.lifecyclebot.network.SignedTxLedger8085.rows8288(now - CAP_WINDOW_MS_8369), false, now - CAP_WINDOW_MS_8369, now)
    } catch (_: Throwable) { false }

    /** Executor.tryPumpPortalBuy: does this (not grabbed) PumpPortal entry take the capped (ordinary) priority fee? */
    fun entryCapActive8369(pump8369: Boolean, grabbed: Boolean): Boolean = !grabbed && !capFailingNow8369(pump8369)

    @Volatile private var lastFixedLegPct8369 = Double.NaN
    @Volatile private var lastTipLamports8369 = -1L
    private val senderFallbacks8369 = AtomicLong(0)
    private val cappedEntries8369 = AtomicLong(0)

    /** Executor.costSenderTip8151 (entry, not grabbed): the capped Sender tip for a buy of [ticketSol] (0 = last ticket). */
    fun entryTip8369(tip8151: Long, ticketSol: Double, pump8369: Boolean): Long {
        return try {
            val ticket = if (ticketSol.isFinite() && ticketSol > 0.0) ticketSol else CostMeter8151.ticketSol8151(DEFAULT_TICKET_SOL_8369)
            val ticketLamports = (ticket * 1e9).toLong()
            val failing = capFailingNow8369(pump8369)
            val priorityLamports = if (pump8369) {
                val sol = if (failing) com.lifecyclebot.network.PriorityFee8009.pumpPortalSol(0.0002)
                    else com.lifecyclebot.network.PriorityFee8009.ordinaryPumpPortalSol8151(0.0002, ticket)
                (sol * 1e9).toLong()
            } else {
                com.lifecyclebot.network.PriorityFee8009.jupiterMicroLamports(25_000L) * CostTrim8151.EST_SWAP_CU_8151 / 1_000_000L
            }
            val minTip = try { com.lifecyclebot.network.HeliusSender.minTipLamports8169() } catch (_: Throwable) { CostTrim8151.SWQOS_MIN_TIP_LAMPORTS_8151 }
            val tip = entryTipLamports8369(tip8151, ticketLamports, priorityLamports, minTip, failing)
            if (tip == 0L && tip8151 > 0L) senderFallbacks8369.incrementAndGet()
            if (tip < tip8151) cappedEntries8369.incrementAndGet()
            lastTipLamports8369 = tip
            lastFixedLegPct8369 = fixedLegPct8369(tip, priorityLamports, ticketLamports)
            tip
        } catch (_: Throwable) { tip8151 }
    }

    /** Pure: one leg's fixed cost (tip + priority + the base signature fee) as a percent of the ticket. */
    fun fixedLegPct8369(tipLamports: Long, priorityLamports: Long, ticketLamports: Long): Double =
        if (ticketLamports <= 0L) Double.NaN
        else (tipLamports.coerceAtLeast(0L) + priorityLamports.coerceAtLeast(0L) + CostTrim8151.BASE_FEE_LAMPORTS_8151).toDouble() / ticketLamports * 100.0

    // ── model vs measured ──

    private val modelByLane8369 = ConcurrentHashMap<String, Double>()

    /** NetEdge8030.costPct8030: the model round trip it just priced for [lane] (before the measured lift). */
    fun noteModel8369(lane: String, modelPct: Double) {
        if (lane.isBlank() || !modelPct.isFinite()) return
        modelByLane8369[lane.uppercase().take(16)] = modelPct
    }

    fun statusLine8369(): String {
        fun p(v: Double) = if (v.isFinite()) "%.2f".format(v) else "-"
        val reclaimed = try { RentReclaimer7927.reclaimedSol8369() } catch (_: Throwable) { Double.NaN }
        val locked = try { RentReclaimer7927.lockedSol8369() } catch (_: Throwable) { Double.NaN }
        val mvm = modelByLane8369.entries.sortedBy { it.key }.joinToString(",") { e ->
            val measured = try { com.lifecyclebot.engine.truth.CostLedger7962.measuredMedianPct8329(e.key, 1) } catch (_: Throwable) { null }
            "${e.key}:${p(e.value)}/${measured?.let { p(it) } ?: "-"}"
        }
        return "costs8369[rentReclaimed=${if (reclaimed.isFinite()) "%.5f".format(reclaimed) else "-"} " +
            "rentLocked=${if (locked.isFinite()) "%.5f".format(locked) else "-"} " +
            "route{jupiter=${jupiterChosen8369.get()},pumpportal=${pumpChosen8369.get()}} " +
            "fixedLegPctOfTicket=${p(lastFixedLegPct8369)} entryTip=${lastTipLamports8369} capped=${cappedEntries8369.get()} " +
            "senderFallback=${senderFallbacks8369.get()} capFailing{jup=${capFailingNow8369(false)},pump=${capFailingNow8369(true)}} " +
            "modelVsMeasured{${mvm.ifBlank { "-" }}}]"
    }
}
