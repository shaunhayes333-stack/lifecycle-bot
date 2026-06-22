package com.lifecyclebot.engine

/**
 * V5.0.3965 — live-only bleeder memory for pre-entry routing.
 *
 * Reads recent LIVE closes only. Never disables a lane; it returns risk state so
 * LiveStylePivotRouter can pivot style, shrink size, and require proof.
 */
object BleederMemoryRouter {
    data class Stats(
        val lane: String,
        val n20: Int,
        val n50: Int,
        val n100: Int,
        val wr20: Double,
        val wr50: Double,
        val wr100: Double,
        val ev20Pct: Double,
        val ev50Pct: Double,
        val ev100Pct: Double,
        val netPnl50Sol: Double,
        val deepLosses50: Int,
        val zeroWinsRecent: Boolean,
        val failedBasisCount: Int,
        val orphanCount: Int,
    ) {
        // V5.0.4070 — faster pivot thresholds. Operator: "pivot correctly into
        // the right strategies earlier". Lower the bleeder detection thresholds
        // so lanes redirect into quality routes before they bleed out.
        val provenBleeder: Boolean get() = n50 >= 8 && wr50 < 30.0 && ev50Pct < 0.0
        val weakPerformer: Boolean get() = n20 >= 5 && wr20 < 35.0 && ev20Pct < 0.0
        val noWinsOverEight: Boolean get() = n20 >= 6 && zeroWinsRecent
        val repeatedDeepLoss: Boolean get() = deepLosses50 >= 2
        val requiresDefensiveProbe: Boolean get() = provenBleeder || weakPerformer || noWinsOverEight || repeatedDeepLoss || failedBasisCount > 0 || orphanCount > 0
    }

    private const val CACHE_MS = 5_000L
    @Volatile private var cacheAtMs = 0L
    @Volatile private var cached: Map<String, Stats> = emptyMap()

    fun statsFor(lane: String): Stats {
        val key = canon(lane)
        return snapshot()[key] ?: Stats(key, 0, 0, 0, 100.0, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0, 0, false, 0, 0)
    }

    fun shouldPivot(lane: String): Boolean = statsFor(lane).requiresDefensiveProbe

    private fun snapshot(): Map<String, Stats> {
        val now = System.currentTimeMillis()
        val c = cached
        if (c.isNotEmpty() && now - cacheAtMs < CACHE_MS) return c
        val fresh = compute()
        cached = fresh
        cacheAtMs = now
        return fresh
    }

    private fun compute(): Map<String, Stats> {
        val rows = try {
            TradeHistoryStore.getRecentValidClosedTrades(limit = 3_000, includePartials = true)
        } catch (_: Throwable) { emptyList() }
            .asSequence()
            .filter { it.mode.equals("live", true) }
            .filter { it.side.equals("SELL", true) || it.side.equals("PARTIAL_SELL", true) }
            .toList()
        if (rows.isEmpty()) return emptyMap()
        return rows.groupBy { canon(it.tradingMode.ifBlank { it.reason }) }.mapValues { (lane, laneRows) ->
            fun slice(n: Int) = laneRows.takeLast(n)
            fun wr(list: List<com.lifecyclebot.data.Trade>): Double {
                val wl = list.filter { it.pnlPct >= 0.5 || it.pnlPct <= -2.0 }
                if (wl.isEmpty()) return 100.0
                return wl.count { it.pnlPct >= 0.5 } * 100.0 / wl.size
            }
            fun ev(list: List<com.lifecyclebot.data.Trade>): Double = if (list.isEmpty()) 0.0 else list.map { it.pnlPct }.average()
            val r20 = slice(20); val r50 = slice(50); val r100 = slice(100)
            Stats(
                lane = lane,
                n20 = r20.size,
                n50 = r50.size,
                n100 = r100.size,
                wr20 = wr(r20), wr50 = wr(r50), wr100 = wr(r100),
                ev20Pct = ev(r20), ev50Pct = ev(r50), ev100Pct = ev(r100),
                netPnl50Sol = r50.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol },
                deepLosses50 = r50.count { it.pnlPct <= -50.0 },
                zeroWinsRecent = r20.takeLast(8).size >= 8 && r20.takeLast(8).none { it.pnlPct >= 0.5 },
                failedBasisCount = r50.count { it.reason.contains("BASIS", true) || it.proofState.contains("BASIS", true) },
                orphanCount = r50.count { it.reason.contains("ORPHAN", true) || it.proofState.contains("ORPHAN", true) },
            )
        }
    }

    fun canon(raw: String): String = try {
        TradeHistoryStore.normalizeTradeModeName(raw).ifBlank { raw.trim().uppercase() }
    } catch (_: Throwable) { raw.trim().uppercase() }
}
