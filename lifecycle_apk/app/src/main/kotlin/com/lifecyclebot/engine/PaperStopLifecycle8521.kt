package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketData
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** An operator stop is a durable request against the lots that were open at that instant. */
object PaperStopLifecycle8521 {
    private const val PREFS = "paper_stop_8521"
    private const val KEY = "pending_lots"
    private const val MARK_MAX_AGE_MS = 30_000L

    data class CloseResult(
        val requestedIds: Set<String>,
        val attemptedIds: Set<String> = emptySet(),
        val closedIds: Set<String> = emptySet(),
    ) {
        val deferredIds: Set<String> get() = requestedIds - closedIds
    }

    data class MarketPosition(
        val id: String,
        val isPaper: Boolean,
        val market: PerpsMarket,
        val close: suspend (PerpsMarketData) -> Unit,
    )

    @Synchronized
    fun request(context: Context, positions: List<CanonicalPositionAuthority6441.Position>): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = decode(prefs.getString(KEY, null))
        positions.filter { it.mode.equals("paper", true) && it.remainingQtyRaw.signum() > 0 &&
            (it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN ||
                it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED) }
            .forEach { p ->
                rows.putIfAbsent(p.positionId, JSONObject()
                    .put("positionId", p.positionId).put("mode", "paper")
                    .put("mint", p.mint).put("assetClass", p.assetClass.name)
                    .put("requestedAtMs", System.currentTimeMillis()))
            }
        check(prefs.edit().putString(KEY, JSONArray(rows.values.toList()).toString()).commit()) {
            "PAPER stop request could not be persisted"
        }
        return rows.keys.toSet()
    }

    @Synchronized
    fun pending(context: Context): Set<String> = decode(context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).keys.toSet()

    /** Call after canonical replay. Unknown/unresolved lots remain requested across process death. */
    @Synchronized
    fun reconcile(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = decode(prefs.getString(KEY, null))
        val remaining = rows.filterValues { row ->
            val p = CanonicalPositionAuthority6441.getPosition(row.optString("positionId"))
            !terminalMatches8521(p, row.optString("mint"), row.optString("assetClass"))
        }
        if (remaining.size != rows.size) {
            check(prefs.edit().putString(KEY, JSONArray(remaining.values.toList()).toString()).commit()) {
                "PAPER stop completion could not be persisted"
            }
        }
        return remaining.keys.toSet()
    }

    private fun decode(raw: String?): LinkedHashMap<String, JSONObject> {
        if (raw.isNullOrBlank()) return linkedMapOf()
        val array = JSONArray(raw)
        val rows = linkedMapOf<String, JSONObject>()
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val id = row.getString("positionId")
            check(id.isNotBlank() && row.optString("mode") == "paper") { "Invalid PAPER stop identity" }
            rows[id] = row
        }
        return rows
    }

    internal fun terminalMatches8521(
        p: CanonicalPositionAuthority6441.Position?, mint: String, assetClass: String,
    ): Boolean = p != null && p.mode.equals("paper", true) && p.mint == mint &&
        p.assetClass.name == assetClass && p.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED &&
        p.remainingQtyRaw.signum() == 0

    fun confirmedClosed(positionId: String): Boolean = CanonicalPositionAuthority6441.getPosition(positionId)
        ?.let { terminalMatches8521(it, it.mint, it.assetClass.name) } == true

    fun tokenSnapshots(status: com.lifecyclebot.data.BotStatus, targetIds: Set<String>): Map<String, com.lifecyclebot.data.TokenState> =
        CanonicalPositionAuthority6441.openPositions().asSequence()
            .filter { it.mode.equals("paper", true) && it.positionId in targetIds &&
                it.assetClass == com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN &&
                !CanonicalPositionAuthority6441.isTraderOwnedOffChainMarket7819(it) &&
                !CanonicalPositionAuthority6441.isCryptoAltTraderOwned8082(it) }
            .mapNotNull { p ->
                com.lifecyclebot.engine.truth.CanonicalUiPositionProjection6686
                    .projectPosition8521(p, status.tokens[p.mint])?.let { p.positionId to it }
            }.toMap()

    fun eligibleMark8521(market: PerpsMarket, mark: PerpsMarketData, nowMs: Long): Boolean =
        mark.market == market && mark.price.isFinite() && mark.price > 0.0 &&
            mark.lastUpdate > 0L && nowMs >= mark.lastUpdate && nowMs - mark.lastUpdate <= MARK_MAX_AGE_MS

    /** Never substitute entry/currentPrice for an unanswered observed-price request. */
    suspend fun closeObserved8521(
        targetIds: Set<String>,
        positions: List<MarketPosition>,
        quote: suspend (PerpsMarket) -> PerpsMarketData? = { market ->
            withTimeoutOrNull(4_000L) { PerpsMarketDataFetcher.getMarketData(market) }
        },
        isClosed: (String) -> Boolean = ::confirmedClosed,
        nowMs: () -> Long = System::currentTimeMillis,
    ): CloseResult = coroutineScope {
        val owned = positions.filter { it.isPaper && it.id in targetIds }.distinctBy { it.id }
        val marks = owned.map { it.market }.distinct().map { market ->
            async {
                market to try { quote(market)?.takeIf { eligibleMark8521(market, it, nowMs()) } }
                catch (_: Exception) { null }
            }
        }.awaitAll().toMap()
        val attempted = linkedSetOf<String>()
        for (p in owned) {
            if (isClosed(p.id)) continue
            val mark = marks[p.market]?.takeIf { eligibleMark8521(p.market, it, nowMs()) } ?: continue
            attempted.add(p.id)
            try { p.close(mark) } catch (_: Exception) { }
        }
        val requested = owned.mapTo(linkedSetOf()) { it.id }
        CloseResult(requested, attempted, requested.filterTo(linkedSetOf()) { isClosed(it) })
    }

    suspend fun closeMarkets(targetIds: Set<String>): CloseResult = coroutineScope {
        val actions: List<suspend () -> CloseResult> = listOf(
            { com.lifecyclebot.perps.TokenizedStockTrader.closePaperPositions8521(targetIds) },
            { com.lifecyclebot.perps.CommoditiesTrader.closePaperPositions8521(targetIds) },
            { com.lifecyclebot.perps.MetalsTrader.closePaperPositions8521(targetIds) },
            { com.lifecyclebot.perps.ForexTrader.closePaperPositions8521(targetIds) },
            { com.lifecyclebot.perps.CryptoAltTrader.closePaperPositions8521(targetIds) },
            { com.lifecyclebot.perps.PerpsExecutionEngine.closePaperPositions8521(targetIds) },
        )
        val results = actions.map { action -> async {
            try { action() } catch (_: Exception) { CloseResult(emptySet()) }
        } }.awaitAll()
        CloseResult(
            results.flatMapTo(linkedSetOf()) { it.requestedIds },
            results.flatMapTo(linkedSetOf()) { it.attemptedIds },
            results.flatMapTo(linkedSetOf()) { it.closedIds },
        )
    }
}
