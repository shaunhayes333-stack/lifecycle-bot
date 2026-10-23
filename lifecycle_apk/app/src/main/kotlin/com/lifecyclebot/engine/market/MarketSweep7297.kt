package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.HostCircuitInterceptor
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7297 §A THE MARKET SCANNER, NOT ONLY A TOKEN SCANNER.
 *
 * Operator: "its meant to be a parallel sweep of providers. even helius can
 * feed the scanner. theres other free token sources already in the app as
 * well" … "each individual lane had its own scanner and brain to help the
 * scanner tune" … "its meant to have a market scanner not just a token
 * scanner".
 *
 * Every discovery source in SolanaMarketScanner answers "is there a new
 * token?". Most are DexScreener or GeckoTerminal endpoints; one DexScreener
 * 429 locks them all out for minutes and GeckoTerminal is dead, so on device
 * nearly all intake was PumpPortal launches and the specialist lanes whose
 * prey is established tokens saw nothing in their band.
 *
 * This sweeps the MARKET instead, from every free provider at once:
 *   Jupiter Tokens v2  toptrending / toptraded / toporganicscore (1h), recent
 *   Raydium api-v3     pools ranked by 24h volume
 *   Helius RPC         the latest PumpSwap AMM swaps, i.e. what is trading
 *                      on-chain right now
 * Raydium and Helius rows carry no market cap, so their mints are enriched
 * through one Jupiter /search call. Rows are merged by mint; every figure is
 * a provider value, and a field no provider supplied stays 0 (unknown).
 *
 * From the merged rows it keeps a market view per market-cap band: how many
 * tokens are active, breadth (the share up over 1h), median 1h move and
 * 1h volume share. LaneHunter7297 reads the rows and the band view; nothing
 * here gates, sizes or books a trade.
 */
object MarketSweep7297 {

    data class Row(
        val mint: String,
        val symbol: String,
        val name: String,
        val priceUsd: Double,
        val mcapUsd: Double,
        val liquidityUsd: Double,
        val volumeH1Usd: Double,
        val volumeH24Usd: Double,
        val priceChangeH1Pct: Double,
        val txCountH1: Int,
        val holders: Int,
        val organicScore: Double,
        val verified: Boolean,
        val ageHours: Double,
        val providers: Set<String>,
        // V5.0.8031 — the 5-minute and 24-hour moves (Jupiter stats5m / stats24h). NaN = no provider supplied it.
        val priceChange5mPct: Double = Double.NaN,
        val priceChangeH24Pct: Double = Double.NaN,
        // V5.0.8169 — the 1h buy and sell counts (Jupiter stats1h numBuys/numSells, DexScreener txns.h1; -1 = not
        // supplied) and when the row's provider answered (0 = unknown), so a setup input read from the sweep is a
        // sided, dated observation (SetupFeatureResolver8169), never a stand-in for a missing one.
        val buysH1: Int = -1,
        val sellsH1: Int = -1,
        val observedAtMs8169: Long = 0L,
    )

    enum class Band(val minMcap: Double, val maxMcap: Double) {
        MICRO(0.0, 50_000.0),
        SMALL(50_000.0, 500_000.0),
        MID(500_000.0, 5_000_000.0),
        LARGE(5_000_000.0, 100_000_000.0),
        MAJOR(100_000_000.0, Double.MAX_VALUE);

        companion object {
            fun of(mcap: Double): Band? =
                if (!mcap.isFinite() || mcap <= 0.0) null
                else values().firstOrNull { mcap >= it.minMcap && mcap < it.maxMcap }
        }
    }

    data class BandState(
        val band: Band,
        val count: Int,
        val breadthPct: Double,
        val medianChangeH1Pct: Double,
        val volumeShare: Double,
    )

    data class Snapshot(
        val rows: List<Row>,
        val bands: Map<Band, BandState>,
        val providerRows: Map<String, Int>,
        val atMs: Long,
    )

    /**
     * V5.0.7777 — bounded transition tape fed by the already-running market
     * data path. This is observation only: no provider call, no FDG call, no
     * execution authority. It lets the market scanner distinguish a static
     * "good" token from one whose participation/liquidity/price is changing now.
     */
    data class RealtimeObservation(
        val priceUsd: Double,
        val mcapUsd: Double,
        val liquidityUsd: Double,
        val buyPressurePct: Double,
        val priceChange5mPct: Double,
        val priceChange1hPct: Double,
        val volume5mUsd: Double,
        val txCount5m: Int,
        val atMs: Long,
    )

    /**
     * Cross-sectional opportunity intelligence. Absolute quality and relative
     * opportunity are deliberately separate: a good token can still be a poor
     * use of scarce attention when stronger setups exist elsewhere.
     */
    data class OpportunitySignal(
        val mint: String,
        val symbol: String,
        val score: Double,
        val rank: Int,
        val universe: Int,
        val percentile: Double,
        val setup: String,
        val relativeStrengthPct: Double,
        val priceVelocity5mPct: Double,
        val volumeAcceleration: Double,
        val txAcceleration: Double,
        val liquidityDeltaPct: Double,
        val buyPressurePct: Double,
        val providerAgreement: Int,
        val regime: String,
        val generatedAtMs: Long,
    )

    private const val JUP = "https://lite-api.jup.ag/tokens/v2"
    private const val RAYDIUM_POOLS =
        "https://api-v3.raydium.io/pools/info/list?poolType=all&poolSortField=volume24h&sortType=desc&pageSize=100&page=1"
    private const val PUMPSWAP_AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    private const val MIN_SWEEP_INTERVAL_MS = 45_000L
    // superseded by V5.0.8361 — owner: market data "hourly or daily depending on urgency"; the RPC-costed source runs
    // at most hourly (HeliusCadence8361.SWEEP_HELIUS_EVERY_MS_8361), the keyless lists keep their own clocks.
    private const val HELIUS_INTERVAL_MS = com.lifecyclebot.engine.HeliusCadence8361.SWEEP_HELIUS_EVERY_MS_8361
    private const val HELIUS_SIGNATURES = 15
    private const val PROVIDER_TIMEOUT_MS = 26_000L
    // V5.0.7807 — hard ceiling for the cap-enrichment pass (see enrichMissingCaps).
    private const val ENRICH_BUDGET_MS_7807 = 30_000L

    private val QUOTE_MINTS = setOf(
        "So11111111111111111111111111111111111111112",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
    )

    // V5.0.7302 — the inherited 12 s callTimeout cut the 100-row Jupiter
    // lists off mid-body (fail=EXC_InterruptedIOException, served 1/12 on
    // 5.0.7301). The sweep runs off the hot path; it gets its own ceiling.
    private val http = SharedHttpClient.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var last: Snapshot? = null
    @Volatile private var lastHeliusAtMs = 0L
    private val providerServed = ConcurrentHashMap<String, Long>()
    private val providerEmpty = ConcurrentHashMap<String, Long>()

    // V5.0.7777 — bounded opportunity memory. At 24 observations/mint and
    // 6,000 mints maximum this cannot become another unbounded heap resident.
    private const val OPPORTUNITY_OBS_PER_MINT_7777 = 24
    private const val OPPORTUNITY_OBS_MAX_AGE_MS_7777 = 10L * 60_000L
    private const val OPPORTUNITY_MAX_MINTS_7777 = 6_000
    private val realtime7777 = ConcurrentHashMap<String, ConcurrentLinkedDeque<RealtimeObservation>>()
    private val opportunity7777 = ConcurrentHashMap<String, OpportunitySignal>()
    private val opportunityRebuilds7777 = AtomicLong(0L)
    private val realtimeObservations7777 = AtomicLong(0L)

    fun latest(): Snapshot? = last

    /**
     * Hot-feed ingress used by DataOrchestrator after it has already accepted
     * a coherent DexScreener mark. O(1), local-only, bounded, and intentionally
     * unable to admit/refuse a trade.
     */
    fun recordRealtime7777(
        mint: String,
        priceUsd: Double,
        mcapUsd: Double,
        liquidityUsd: Double,
        buyPressurePct: Double,
        priceChange5mPct: Double,
        priceChange1hPct: Double,
        volume5mUsd: Double,
        txCount5m: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (mint.length < 32 || !priceUsd.isFinite() || priceUsd <= 0.0) return
        val q = realtime7777.computeIfAbsent(mint) { ConcurrentLinkedDeque() }
        q.addLast(
            RealtimeObservation(
                priceUsd = priceUsd,
                mcapUsd = mcapUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                liquidityUsd = liquidityUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                buyPressurePct = buyPressurePct.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 50.0,
                priceChange5mPct = priceChange5mPct.takeIf { it.isFinite() } ?: 0.0,
                priceChange1hPct = priceChange1hPct.takeIf { it.isFinite() } ?: 0.0,
                volume5mUsd = volume5mUsd.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                txCount5m = txCount5m.coerceAtLeast(0),
                atMs = nowMs,
            )
        )
        while (q.size > OPPORTUNITY_OBS_PER_MINT_7777) q.pollFirst()
        while (true) {
            val first = q.peekFirst() ?: break
            if (nowMs - first.atMs <= OPPORTUNITY_OBS_MAX_AGE_MS_7777) break
            q.pollFirst()
        }
        realtimeObservations7777.incrementAndGet()

        // A hard cap is defensive against long-running sessions with one-hit mints.
        if (realtime7777.size > OPPORTUNITY_MAX_MINTS_7777) {
            val staleBefore = nowMs - OPPORTUNITY_OBS_MAX_AGE_MS_7777
            realtime7777.entries.asSequence().take(512).forEach { e ->
                val newest = e.value.peekLast()?.atMs ?: 0L
                if (newest < staleBefore) realtime7777.remove(e.key, e.value)
            }
        }
    }

    fun opportunityFor7777(mint: String): OpportunitySignal? = opportunity7777[mint]

    // V5.0.8293 — the rebuild's leaders, kept in rank order so the cycle window reads them without re-sorting the map.
    @Volatile private var top8293: List<String> = emptyList()

    /** V5.0.8293 — the current opportunity leaders (best rank first, at most 16), for HunterWindow8293. */
    fun topOpportunities8293(): List<OpportunitySignal> = top8293.mapNotNull { opportunity7777[it] }

    /**
     * Pure (V5.0.8293): every row's age advanced to [nowMs] from the moment its provider answered. A list served from its
     * refresh window (up to 3 min, 8124) or carried on a failure (up to 20 min) kept the age it had when fetched, so a
     * "0.3-minute-old" launch could be minutes past the sniper / MANIPULATED / EXPRESS windows, and merge() took the
     * youngest (stalest) of a mint's ages. A row with no provider time or no known age is unchanged.
     */
    fun agedForward8293(rows: List<Row>, nowMs: Long): List<Row> = rows.map { r ->
        if (r.ageHours > 0.0 && r.ageHours.isFinite() && r.observedAtMs8169 > 0L && nowMs > r.observedAtMs8169)
            r.copy(ageHours = r.ageHours + (nowMs - r.observedAtMs8169) / 3_600_000.0)
        else r
    }

    /**
     * Soft attention/ordering multiplier only. This cannot remove a candidate.
     * Weak signals still remain available to lane scorers and forward learning.
     */
    fun opportunityMultiplier7777(mint: String): Double {
        val o = opportunity7777[mint] ?: return 1.0
        var m = 0.82 + (o.score.coerceIn(0.0, 100.0) / 100.0) * 0.36
        if (o.percentile >= 0.90) m += 0.06
        if (o.percentile >= 0.97) m += 0.04
        if (o.setup == "DISTRIBUTION" || o.setup == "EXHAUSTION") m = minOf(m, 0.90)
        return m.coerceIn(0.78, 1.28)
    }

    /**
     * Sweep every provider in parallel. Returns the cached snapshot when the
     * last sweep is younger than [MIN_SWEEP_INTERVAL_MS], so the scanner can
     * call this on every cycle without exceeding the free-tier rates.
     */
    suspend fun sweep(heliusKey: String, jupiterKey: String = ""): Snapshot? {
        jupiterKey7301 = jupiterKey.trim()
        val now = System.currentTimeMillis()
        last?.let { if (now - it.atMs < MIN_SWEEP_INTERVAL_MS) return it }
        // V5.0.8361 — keyless-first reads (KeylessRpc8359), so no key is needed; at most once an hour.
        val runHelius = com.lifecyclebot.engine.HeliusCadence8361.sweepHeliusDue8361(lastHeliusAtMs, now)
        if (runHelius) { lastHeliusAtMs = now; com.lifecyclebot.engine.HeliusCadence8361.noteSweepHelius8361(now) } else com.lifecyclebot.engine.HeliusCadence8361.noteSweepNotDue8361()

        val tasks = mutableListOf<Pair<String, suspend () -> List<Row>>>(
            "JUP_TRENDING" to { jupiterList("/toptrending/1h?limit=50", "JUP_TRENDING") },
            "JUP_TRADED" to { jupiterList("/toptraded/1h?limit=50", "JUP_TRADED") },
            "JUP_ORGANIC" to { jupiterList("/toporganicscore/1h?limit=50", "JUP_ORGANIC") },
            "JUP_RECENT" to { jupiterList("/recent?limit=50", "JUP_RECENT") },
            // V5.0.8029 — the whole market on more clocks: what is moving in the last 5 minutes (every venue: pump,
            // LaunchLab, Meteora, Raydium, Orca), and what has carried volume / organic flow over the day — the
            // established coins QUALITY / BLUECHIP / TREASURY / CASHGEN hunt.
            "JUP_TRENDING_5M" to { jupiterList("/toptrending/5m?limit=50", "JUP_TRENDING_5M") },
            "JUP_TRADED_24H" to { jupiterList("/toptraded/24h?limit=50", "JUP_TRADED_24H") },
            "JUP_ORGANIC_24H" to { jupiterList("/toporganicscore/24h?limit=50", "JUP_ORGANIC_24H") },
            "RAYDIUM_VOLUME" to { raydiumPools() },
        )
        if (runHelius) tasks += "HELIUS_SWAPS" to { heliusSwaps() }
        // V5.0.7300 — the external hive: GMGN smart-money rows kept by
        // ExternalAlphaFeeds' own 90 s poll (no request made here).
        tasks += "GMGN_SMART_MONEY" to {
            try { com.lifecyclebot.v4.meta.ExternalAlphaFeeds.smartMoneyRows7300() } catch (_: Throwable) { emptyList() }
        }

        val results = coroutineScope {
            tasks.map { (name, fn) ->
                async(Dispatchers.IO) {
                    // V5.0.8124 — a list still inside its own refresh window is served from its last good rows.
                    goodRows8124[name]?.let { (rows, at) -> if (now - at < refreshMs8124(name)) return@async name to rows }
                    val rows = try {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { fn() } ?: emptyList()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ErrorLogger.debug("MarketSweep7297", "$name failed: ${t.message}")
                        emptyList()
                    }
                    name to carryOrStore8124(name, rows, System.currentTimeMillis())
                }
            }.awaitAll()
        }

        val providerRows = results.associate { it.first to it.second.size }
        results.forEach { (name, rows) ->
            if (rows.isEmpty()) providerEmpty.merge(name, 1L, Long::plus)
            else providerServed.merge(name, 1L, Long::plus)
            try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_${name}_${if (rows.isEmpty()) "EMPTY" else "SERVED"}") } catch (_: Throwable) {}
        }

        val merged = merge(agedForward8293(results.flatMap { it.second }, System.currentTimeMillis()))  // V5.0.8293
        val enriched = enrichMissingCaps(merged)
        if (enriched.isEmpty()) return last
        val previous7777 = last
        val snap = Snapshot(enriched, bandStates(enriched), providerRows, System.currentTimeMillis())
        rebuildOpportunitySignals7777(previous7777, snap)
        last = snap
        // V5.0.8283 — a newly observed row advances its mint's shared feature revision (setup caches rebuild on it).
        try { com.lifecyclebot.engine.truth.FeatureRevision8283.noteSweepRows8283(previous7777?.rows, snap.rows, snap.atMs) } catch (_: Throwable) {}
        return snap
    }

    // ── V5.0.8124 — every feed fails soft ──
    // Owner: "we are meant to have multiple multiple sources of data". 8120: seven Jupiter lists were fetched in one
    // parallel burst every 45 s; the keyed host's rate limit tripped and every list reported LOCAL_CIRCUIT at once, so
    // the sweep lost 7 of its 10 feeds together. Each list now refreshes on its own clock (a 24 h ranking does not move
    // in 45 s), which cuts the burst from 7 calls to 2-3, and a feed that fails serves its last good rows for up to
    // [CARRY_MS_8124] instead of nothing.
    private val goodRows8124 = ConcurrentHashMap<String, Pair<List<Row>, Long>>()
    private val carried8124 = ConcurrentHashMap<String, Long>()
    private const val CARRY_MS_8124 = 20L * 60_000L

    /** Pure: how long a feed's rows stay current before it is asked again. */
    fun refreshMs8124(name: String): Long = when {
        name.endsWith("_24H") -> 10L * 60_000L
        name == "JUP_TRENDING_5M" || name == "JUP_RECENT" -> 60_000L
        name.startsWith("JUP_") -> 3L * 60_000L
        else -> 0L
    }

    /** Fresh rows are stored and returned; an empty answer returns the last good rows while they are young enough. */
    internal fun carryOrStore8124(name: String, rows: List<Row>, nowMs: Long): List<Row> {
        if (rows.isNotEmpty()) { goodRows8124[name] = rows to nowMs; return rows }
        val (prev, at) = goodRows8124[name] ?: return rows
        if (nowMs - at > CARRY_MS_8124) return rows
        carried8124.merge(name, 1L, Long::plus)
        try { PipelineHealthCollector.labelInc("MARKET_SWEEP_FEED_CARRIED_8124_$name") } catch (_: Throwable) {}
        return prev
    }

    // ── V5.0.8128 — the last sweep as a second source ──
    // Audit: Jupiter rows carry holderCount (Row.holders) but nothing outside the hunters read it, so every
    // Candle.holderCount stayed 0; and the scanner's empty-scan fallback asked DexScreener again, the provider
    // that had just failed. Both read the last snapshot, only while it is young enough to stand in.
    private const val SNAPSHOT_STANDIN_MS_8128 = 20L * 60_000L
    @Volatile private var holderIndex8128: Pair<Long, Map<String, Int>> = 0L to emptyMap()

    /** Pure: mint -> holder count for every row that carries one (0 = not supplied, left out). */
    internal fun holderIndexOf8128(rows: List<Row>): Map<String, Int> =
        rows.filter { it.holders > 0 }.associate { it.mint to it.holders }

    /** [mint]'s holder count from the last sweep, or null (not listed, or the snapshot is too old). */
    fun holdersFor8128(mint: String, nowMs: Long = System.currentTimeMillis()): Int? {
        val snap = last ?: return null
        if (nowMs - snap.atMs > SNAPSHOT_STANDIN_MS_8128) return null
        var idx = holderIndex8128
        if (idx.first != snap.atMs) { idx = snap.atMs to holderIndexOf8128(snap.rows); holderIndex8128 = idx }
        return idx.second[mint]
    }

    /** Pure: [snap]'s rows for the scanner's emergency pass, busiest hour first; empty when there is no young snapshot. */
    internal fun emergencyRowsOf8128(snap: Snapshot?, nowMs: Long, max: Int): List<Row> {
        if (snap == null || nowMs - snap.atMs !in 0L..SNAPSHOT_STANDIN_MS_8128) return emptyList()
        return snap.rows.filter { it.mint.length >= 32 && it.mint !in QUOTE_MINTS }
            .sortedByDescending { if (it.volumeH1Usd.isFinite()) it.volumeH1Usd else 0.0 }
            .take(max)
    }

    /** SolanaMarketScanner: the last sweep's rows when a whole scan came back empty. */
    fun emergencyRows8128(nowMs: Long = System.currentTimeMillis(), max: Int = 100): List<Row> = emergencyRowsOf8128(last, nowMs, max)

    /** Pure: combine rows for one mint, keeping every provider-supplied value. */
    fun merge(rows: List<Row>): List<Row> =
        rows.filter { it.mint.length >= 32 && it.mint !in QUOTE_MINTS }
            .groupBy { it.mint }
            .map { (_, g) ->
                fun pick(f: (Row) -> Double) = g.map(f).filter { it.isFinite() && it > 0.0 }.maxOrNull() ?: 0.0
                val named = g.firstOrNull { it.symbol.isNotBlank() } ?: g.first()
                Row(
                    mint = named.mint,
                    symbol = named.symbol,
                    name = named.name,
                    priceUsd = pick { it.priceUsd },
                    mcapUsd = pick { it.mcapUsd },
                    liquidityUsd = pick { it.liquidityUsd },
                    volumeH1Usd = pick { it.volumeH1Usd },
                    volumeH24Usd = pick { it.volumeH24Usd },
                    priceChangeH1Pct = g.firstOrNull { it.priceChangeH1Pct != 0.0 }?.priceChangeH1Pct ?: 0.0,
                    txCountH1 = g.maxOf { it.txCountH1 },
                    holders = g.maxOf { it.holders },
                    organicScore = g.maxOf { it.organicScore },
                    verified = g.any { it.verified },
                    ageHours = g.map { it.ageHours }.filter { it > 0.0 }.minOrNull() ?: 0.0,
                    providers = g.flatMap { it.providers }.toSet(),
                    priceChange5mPct = g.map { it.priceChange5mPct }.firstOrNull { it.isFinite() } ?: Double.NaN,
                    priceChangeH24Pct = g.map { it.priceChangeH24Pct }.firstOrNull { it.isFinite() } ?: Double.NaN,
                    // V5.0.8169 — the freshest sided row's counts; the OLDEST dated answer of the group (the dated
                    // feeds are the ones that carry moves and counts), so a merged row is never dated fresher than them.
                    buysH1 = g.filter { it.buysH1 >= 0 && it.sellsH1 >= 0 }.maxByOrNull { it.observedAtMs8169 }?.buysH1 ?: -1,
                    sellsH1 = g.filter { it.buysH1 >= 0 && it.sellsH1 >= 0 }.maxByOrNull { it.observedAtMs8169 }?.sellsH1 ?: -1,
                    observedAtMs8169 = g.map { it.observedAtMs8169 }.filter { it > 0L }.minOrNull() ?: 0L,
                )
            }

    // ── V5.0.8169 — the last sweep's row for one mint (setup-input fallback) ──
    @Volatile private var rowIndex8169: Pair<Long, Map<String, Row>> = 0L to emptyMap()

    /** [mint]'s merged row from the last sweep, or null (not listed, or the snapshot is older than [maxAgeMs]). */
    fun rowFor8169(mint: String, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = 20L * 60_000L): Row? {
        val snap = last ?: return null
        if (nowMs - snap.atMs !in 0L..maxAgeMs) return null
        var idx = rowIndex8169
        if (idx.first != snap.atMs) { idx = snap.atMs to snap.rows.associateBy { it.mint }; rowIndex8169 = idx }
        return idx.second[mint]
    }

    /** Pure: breadth, median move and volume share per market-cap band. */
    private fun bandStates(rows: List<Row>): Map<Band, BandState> {
        val byBand = rows.mapNotNull { r -> Band.of(r.mcapUsd)?.let { it to r } }.groupBy({ it.first }, { it.second })
        val totalVol = rows.sumOf { it.volumeH1Usd.coerceAtLeast(0.0) }
        return byBand.mapValues { (band, rs) ->
            // V5.0.7876 — a sub-hour token's "1h change" is its move since first print.
            val moves = rs.filter { !underOneHour7876(it.ageHours) }.map { it.priceChangeH1Pct }.sorted()
            val median = if (moves.isEmpty()) 0.0 else moves[moves.size / 2]
            BandState(
                band = band,
                count = rs.size,
                breadthPct = if (rs.isEmpty()) 0.0 else 100.0 * rs.count { it.priceChangeH1Pct > 0.0 } / rs.size,
                medianChangeH1Pct = median,
                volumeShare = if (totalVol > 0.0) rs.sumOf { it.volumeH1Usd.coerceAtLeast(0.0) } / totalVol else 0.0,
            )
        }
    }

    /**
     * V5.0.7777 — convert state into transition + relative-opportunity evidence.
     * No row is filtered here. The ranking allocates attention; downstream lane
     * doctrine and canonical safety/FDG remain the decision authorities.
     */
    private fun rebuildOpportunitySignals7777(previous: Snapshot?, current: Snapshot) {
        val now = current.atMs
        val prevByMint = previous?.rows?.associateBy { it.mint }.orEmpty()
        val provisional = ArrayList<OpportunitySignal>(current.rows.size)

        for (r in current.rows) {
            val rt = realtime7777[r.mint]?.toList()
                ?.filter { now - it.atMs <= OPPORTUNITY_OBS_MAX_AGE_MS_7777 }
                .orEmpty()
            val latestRt = rt.lastOrNull()
            val prev = prevByMint[r.mint]
            val bandMedian = Band.of(r.mcapUsd)?.let { current.bands[it]?.medianChangeH1Pct } ?: 0.0
            val relative = relativeStrength7876(r.priceChangeH1Pct, r.ageHours, bandMedian)
            val price5 = latestRt?.priceChange5mPct ?: run {
                if (prev != null && prev.priceUsd > 0.0 && r.priceUsd > 0.0) {
                    ((r.priceUsd / prev.priceUsd) - 1.0) * 100.0
                } else 0.0
            }

            fun acceleration(values: List<Double>): Double {
                if (values.size < 4) return 1.0
                val split = values.size / 2
                val early = values.take(split).filter { it > 0.0 }.averageOrNull7777()
                val late = values.drop(split).filter { it > 0.0 }.averageOrNull7777()
                return if (early > 0.0 && late >= 0.0) (late / early).coerceIn(0.0, 20.0) else 1.0
            }
            val volAccel = acceleration(rt.map { it.volume5mUsd })
            val txAccel = acceleration(rt.map { it.txCount5m.toDouble() })
            val liqNow = latestRt?.liquidityUsd?.takeIf { it > 0.0 } ?: r.liquidityUsd
            val liqBefore = rt.firstOrNull()?.liquidityUsd?.takeIf { it > 0.0 }
                ?: prev?.liquidityUsd?.takeIf { it > 0.0 }
            val liqDelta = if (liqBefore != null && liqBefore > 0.0 && liqNow > 0.0) {
                ((liqNow / liqBefore) - 1.0) * 100.0
            } else 0.0
            val bp = latestRt?.buyPressurePct ?: 50.0
            val providerAgreement = r.providers.size.coerceAtLeast(1)
            val turnover = if (r.liquidityUsd > 0.0) (r.volumeH1Usd / r.liquidityUsd).coerceAtLeast(0.0) else 0.0

            val setup = when {
                (r.priceChangeH1Pct >= 35.0 && price5 <= -6.0) || bp <= 38.0 -> "EXHAUSTION"
                price5 <= -8.0 && r.priceChangeH1Pct > 0.0 -> "DISTRIBUTION"
                r.priceChangeH1Pct <= -4.0 && price5 >= 1.5 && bp >= 55.0 -> "DIP_RECOVERY"
                price5 >= 2.0 && bp >= 58.0 && volAccel >= 1.35 -> "EARLY_MOMENTUM_IGNITION"
                price5 >= 5.0 && relative >= 5.0 -> "BREAKOUT_EXPANSION"
                liqDelta >= 12.0 && bp >= 52.0 -> "LIQUIDITY_EXPANSION"
                // V5.0.7867 — a leader needs live tape. 5.0.7866's top ranks were
                // "Transforme"/"ChatGPT"/"NVIDIA" at rs=+1000 with v5=0 and volx=1.0:
                // a 1h % from a minutes-old launch's first print, no realtime row.
                relative >= 8.0 && r.priceChangeH1Pct > 0.0 && latestRt != null -> "RELATIVE_STRENGTH_LEADER"
                r.priceChangeH1Pct >= 3.0 && price5 > 0.0 && bp >= 52.0 -> "CONTINUATION"
                else -> "OBSERVING"
            }

            val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "NORMAL" }
            val regimeFit = when {
                regime == "BULL_RIPPING" && setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "CONTINUATION", "RELATIVE_STRENGTH_LEADER") -> 5.0
                regime == "CHOP" && setup == "DIP_RECOVERY" -> 3.0
                regime in setOf("DUMP", "DEAD") && setup in setOf("EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION") -> -3.0
                else -> 0.0
            }

            var score = 50.0
            score += relative.coerceIn(-15.0, 15.0)
            score += (price5 * 1.2).coerceIn(-18.0, 18.0)
            score += ((volAccel - 1.0) * 10.0).coerceIn(-10.0, 18.0)
            score += ((txAccel - 1.0) * 6.0).coerceIn(-6.0, 10.0)
            score += (liqDelta / 3.0).coerceIn(-8.0, 10.0)
            score += ((bp - 50.0) / 2.5).coerceIn(-12.0, 12.0)
            score += ((providerAgreement - 1) * 2.0).coerceIn(0.0, 6.0)
            score += (r.organicScore / 15.0).coerceIn(0.0, 6.0)
            score += (kotlin.math.log10(1.0 + turnover) * 4.0).coerceIn(0.0, 6.0)
            score += regimeFit
            if (setup == "EXHAUSTION") score -= 15.0
            if (setup == "DISTRIBUTION") score -= 10.0
            score = score.coerceIn(0.0, 100.0)

            provisional += OpportunitySignal(
                mint = r.mint,
                symbol = r.symbol,
                score = score,
                rank = 0,
                universe = current.rows.size,
                percentile = 0.0,
                setup = setup,
                relativeStrengthPct = relative,
                priceVelocity5mPct = price5,
                volumeAcceleration = volAccel,
                txAcceleration = txAccel,
                liquidityDeltaPct = liqDelta,
                buyPressurePct = bp,
                providerAgreement = providerAgreement,
                regime = regime,
                generatedAtMs = now,
            )
        }

        val sorted = provisional.sortedByDescending { it.score }
        val n = sorted.size.coerceAtLeast(1)
        opportunity7777.clear()
        sorted.forEachIndexed { idx, s ->
            val rank = idx + 1
            val percentile = if (n <= 1) 1.0 else (1.0 - idx.toDouble() / (n - 1).toDouble()).coerceIn(0.0, 1.0)
            opportunity7777[s.mint] = s.copy(rank = rank, universe = n, percentile = percentile)
        }
        top8293 = sorted.take(16).map { it.mint }  // V5.0.8293
        opportunityRebuilds7777.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("OPPORTUNITY_INTELLIGENCE_REBUILT_7777")
            sorted.take(10).forEach { PipelineHealthCollector.labelInc("OPPORTUNITY_TOP10_7777_${it.setup}") }
        } catch (_: Throwable) {}
    }

    private fun List<Double>.averageOrNull7777(): Double =
        if (isEmpty()) 0.0 else average().takeIf { it.isFinite() } ?: 0.0

    fun opportunityStatusLine7777(limit: Int = 6): String {
        val ranked = opportunity7777.values.sortedBy { it.rank }.take(limit.coerceIn(1, 12))
        if (ranked.isEmpty()) {
            return "OPPORTUNITY_INTELLIGENCE_7777 candidates=0 observations=${realtimeObservations7777.get()} rebuilds=${opportunityRebuilds7777.get()} authority=ordering_only"
        }
        val top = ranked.joinToString(" | ") {
            "#${it.rank}/${it.universe} ${it.symbol.ifBlank { it.mint.take(6) }} ${it.setup} score=${it.score.toInt()} rs=${"%+.1f".format(it.relativeStrengthPct)} v5=${"%+.1f".format(it.priceVelocity5mPct)} volx=${"%.1f".format(it.volumeAcceleration)}"
        }
        return "OPPORTUNITY_INTELLIGENCE_7777 candidates=${opportunity7777.size} observations=${realtimeObservations7777.get()} rebuilds=${opportunityRebuilds7777.get()} top=[$top] authority=ordering_only no_execution_authority=true"
    }

    // ── providers ────────────────────────────────────────────────────────

    /**
     * V5.0.7301 — 5.0.7300 showed JUP_TRENDING/TRADED/ORGANIC/RECENT at 0 rows
     * every sweep with no reason on the report. Each provider now records why
     * its last call returned nothing (HTTP code, local circuit, exception,
     * non-array body), shown as `fail=` on the Market sweep line.
     */
    private val lastFail7301 = ConcurrentHashMap<String, String>()
    @Volatile private var jupiterKey7301 = ""
    private const val JUP_KEYED_7301 = "https://api.jup.ag/tokens/v2"

    private fun getBody(url: String, provider: String = "", headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AATE")
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { resp ->
            if (HostCircuitInterceptor.isSyntheticBlock(resp)) {
                try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_LOCAL_CIRCUIT_BLOCK") } catch (_: Throwable) {}
                if (provider.isNotBlank()) lastFail7301[provider] = "LOCAL_CIRCUIT"
                null
            } else if (!resp.isSuccessful) {
                try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_HTTP_${resp.code}") } catch (_: Throwable) {}
                if (provider.isNotBlank()) lastFail7301[provider] = "HTTP_${resp.code}"
                null
            } else {
                // V5.0.8191 — a provider that answers again is no longer failing: 5.0.8186 still read DEXSCREENER_ENRICH
                // LOCAL_CIRCUIT from a refusal long since recovered (only the Jupiter paths cleared their last failure).
                if (provider.isNotBlank()) lastFail7301.remove(provider)
                resp.body?.string()
            }
        }
    } catch (e: Exception) {
        ErrorLogger.debug("MarketSweep7297", "GET ${url.take(60)} failed: ${e.message}")
        if (provider.isNotBlank()) lastFail7301[provider] = "EXC_${e.javaClass.simpleName}"
        null
    }

    // V5.0.7719 — a lite-api token list that answers 401/403/404 is not
    // going to answer differently in 45 s; retrying it every sweep only feeds
    // the host circuit. Remember the refusal for 30 min and go keyed first.
    private const val LITE_TOKENS_DEAD_MS_7719 = 30L * 60_000L
    @Volatile private var liteTokensDeadUntil7719 = 0L

    /** Keyless lite-api first (unless it recently refused); with an operator Jupiter key, the keyed host. */
    private fun jupiterBody(path: String, provider: String): String? {
        val now = System.currentTimeMillis()
        if (now >= liteTokensDeadUntil7719) {
            getBody("$JUP$path", provider)?.trim()?.takeIf { it.startsWith("[") }?.let { lastFail7301.remove(provider); return it }
            val fail = lastFail7301[provider].orEmpty()
            // V5.0.7925 — a local circuit on the keyless host means it is refusing: go keyed.
            if (fail == "HTTP_401" || fail == "HTTP_403" || fail == "HTTP_404" || fail == "LOCAL_CIRCUIT") {
                liteTokensDeadUntil7719 = now + LITE_TOKENS_DEAD_MS_7719
                try { PipelineHealthCollector.labelInc("MARKET_SWEEP_LITE_TOKENS_DEAD_LATCHED_7719") } catch (_: Throwable) {}
            }
        } else {
            try { PipelineHealthCollector.labelInc("MARKET_SWEEP_LITE_TOKENS_SKIPPED_DEAD_7719") } catch (_: Throwable) {}
        }
        val key = jupiterKey7301
        if (key.isBlank()) return null
        return getBody("$JUP_KEYED_7301$path", provider, mapOf("x-api-key" to key))?.trim()
            ?.takeIf { it.startsWith("[") }?.also { lastFail7301.remove(provider) }
    }

    private fun jupiterList(path: String, provider: String): List<Row> {
        val body = jupiterBody(path, provider) ?: run {
            if (!lastFail7301.containsKey(provider)) lastFail7301[provider] = "NON_ARRAY_BODY"
            return emptyList()
        }
        return parseJupiter(JSONArray(body), provider)
    }

    /**
     * V5.0.7301 — cap enrichment fallback. Raydium and Helius rows carry no
     * market cap, and the lane hunters work in market-cap bands, so when the
     * Jupiter search fails those rows reach no lane. DexScreener's token
     * endpoint (30 mints per call) supplies cap, liquidity, 1h volume, 1h move
     * and 1h trades for the best-liquidity pair of each mint.
     */
    private fun dexScreenerEnrich7301(mints: List<String>): List<Row> {
        val out = ArrayList<Row>()
        val now = System.currentTimeMillis()
        for (chunk in mints.chunked(30).take(3)) {
            val body = getBody("https://api.dexscreener.com/tokens/v1/solana/${chunk.joinToString(",")}", "DEXSCREENER_ENRICH")?.trim()
            if (body == null || !body.startsWith("[")) continue
            val arr = JSONArray(body)
            val best = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val mint = p.optJSONObject("baseToken")?.optString("address").orEmpty()
                if (mint !in chunk) continue
                val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val cur = best[mint]
                if (cur == null || liq > (cur.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0)) best[mint] = p
            }
            for ((mint, p) in best) {
                val base = p.optJSONObject("baseToken")
                val tx = p.optJSONObject("txns")?.optJSONObject("h1")
                val created = p.optLong("pairCreatedAt", 0L)
                // V5.0.8169 — this pair's creation was parsed and dropped: a pump mint's curve pair is its birth, any other pool a floor.
                try { com.lifecyclebot.engine.truth.AdmissionBirth8144.notePairCreated8169(mint, p.optString("dexId", ""), created) } catch (_: Throwable) {}
                val cap = p.optDouble("marketCap", 0.0).finite().takeIf { it > 0.0 } ?: p.optDouble("fdv", 0.0).finite()
                out += Row(
                    mint = mint,
                    symbol = base?.optString("symbol", "").orEmpty(),
                    name = base?.optString("name", "").orEmpty(),
                    priceUsd = p.optString("priceUsd", "0").toDoubleOrNull()?.finite() ?: 0.0,
                    mcapUsd = cap,
                    liquidityUsd = p.optJSONObject("liquidity")?.optDouble("usd", 0.0)?.finite() ?: 0.0,
                    volumeH1Usd = p.optJSONObject("volume")?.optDouble("h1", 0.0)?.finite() ?: 0.0,
                    volumeH24Usd = p.optJSONObject("volume")?.optDouble("h24", 0.0)?.finite() ?: 0.0,
                    priceChangeH1Pct = p.optJSONObject("priceChange")?.optDouble("h1", 0.0)?.finite() ?: 0.0,
                    txCountH1 = (tx?.optInt("buys", 0) ?: 0) + (tx?.optInt("sells", 0) ?: 0),
                    holders = 0,
                    organicScore = 0.0,
                    verified = false,
                    ageHours = if (created > 0L && created <= now) (now - created) / 3_600_000.0 else 0.0,
                    providers = setOf("DEXSCREENER_ENRICH"),
                    buysH1 = if (tx != null && tx.has("buys") && tx.has("sells")) tx.optInt("buys", -1) else -1,   // V5.0.8169
                    sellsH1 = if (tx != null && tx.has("buys") && tx.has("sells")) tx.optInt("sells", -1) else -1,
                    observedAtMs8169 = now,
                )
            }
        }
        return out
    }

    private fun parseJupiter(arr: JSONArray, provider: String): List<Row> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mint = o.optString("id", "").trim()
            if (mint.length < 32) continue
            val s1 = o.optJSONObject("stats1h")
            val s24 = o.optJSONObject("stats24h")
            val s5 = o.optJSONObject("stats5m")
            fun vol(s: JSONObject?) = if (s == null) 0.0 else s.optDouble("buyVolume", 0.0).finite() + s.optDouble("sellVolume", 0.0).finite()
            val created = o.optJSONObject("firstPool")?.optString("createdAt", "").orEmpty()
            val createdMs = try { if (created.isBlank()) 0L else java.time.Instant.parse(created).toEpochMilli() } catch (_: Throwable) { 0L }
            // V5.0.8094 — Jupiter's FIRST pool is a launch time; the sniper refused 3,623 hunted rows on 8093 as
            // BIRTH_METADATA_HYDRATING_7440 while this field was on the wire (the RPC birth walk ran on an exhausted
            // Helius). A later pool (Raydium openTime, a DexScreener best pair) is not a launch and is not recorded here.
            if (createdMs > 0L) try {
                com.lifecyclebot.engine.truth.PoolCreationTime7385.record(mint, createdMs, "JUP_FIRST_POOL_8094")
            } catch (_: Throwable) {}
            out += Row(
                mint = mint,
                symbol = o.optString("symbol", ""),
                name = o.optString("name", ""),
                priceUsd = o.optDouble("usdPrice", 0.0).finite(),
                mcapUsd = o.optDouble("mcap", 0.0).finite(),
                liquidityUsd = o.optDouble("liquidity", 0.0).finite(),
                volumeH1Usd = vol(s1),
                volumeH24Usd = vol(s24),
                priceChangeH1Pct = s1?.optDouble("priceChange", 0.0)?.finite() ?: 0.0,
                txCountH1 = (s1?.optInt("numBuys", 0) ?: 0) + (s1?.optInt("numSells", 0) ?: 0),
                holders = o.optInt("holderCount", 0),
                organicScore = o.optDouble("organicScore", 0.0).finite(),
                verified = o.optBoolean("isVerified", false),
                ageHours = if (createdMs > 0L) ((now - createdMs) / 3_600_000.0).coerceAtLeast(0.0) else 0.0,
                providers = setOf(provider),
                priceChange5mPct = s5?.optDouble("priceChange", Double.NaN) ?: Double.NaN,
                priceChangeH24Pct = s24?.optDouble("priceChange", Double.NaN) ?: Double.NaN,
                buysH1 = if (s1 != null && s1.has("numBuys") && s1.has("numSells")) s1.optInt("numBuys", -1) else -1,   // V5.0.8169
                sellsH1 = if (s1 != null && s1.has("numBuys") && s1.has("numSells")) s1.optInt("numSells", -1) else -1,
                observedAtMs8169 = now,
            )
        }
        return out
    }

    private fun raydiumPools(): List<Row> {
        val body = getBody(RAYDIUM_POOLS)?.trim() ?: return emptyList()
        if (!body.startsWith("{")) return emptyList()
        val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        val nowSec = System.currentTimeMillis() / 1000.0
        val out = ArrayList<Row>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val a = p.optJSONObject("mintA") ?: continue
            val b = p.optJSONObject("mintB") ?: continue
            val base = when {
                b.optString("address") in QUOTE_MINTS && a.optString("address") !in QUOTE_MINTS -> a
                a.optString("address") in QUOTE_MINTS && b.optString("address") !in QUOTE_MINTS -> b
                else -> continue
            }
            val open = p.optString("openTime", "0").toDoubleOrNull() ?: 0.0
            out += Row(
                mint = base.optString("address"),
                symbol = base.optString("symbol", ""),
                name = base.optString("name", ""),
                priceUsd = 0.0,
                mcapUsd = 0.0,
                liquidityUsd = p.optDouble("tvl", 0.0).finite(),
                volumeH1Usd = 0.0,
                volumeH24Usd = p.optJSONObject("day")?.optDouble("volume", 0.0)?.finite() ?: 0.0,
                priceChangeH1Pct = 0.0,
                txCountH1 = 0,
                holders = 0,
                organicScore = 0.0,
                verified = false,
                ageHours = if (open > 0.0) ((nowSec - open) / 3600.0).coerceAtLeast(0.0) else 0.0,
                providers = setOf("RAYDIUM_VOLUME"),
            )
        }
        return out
    }

    /**
     * Mints traded in the latest PumpSwap AMM swaps. V5.0.8361 — read keyless-first through KeylessRpc8359 (public nodes,
     * Helius only when every keyless rung declined), at most hourly, inside a hard deadline.
     */
    private fun heliusSwaps(): List<Row> {
        if (!com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.admit(
                com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.Consumer.MARKET_SWEEP, 1.0 + HELIUS_SIGNATURES)) return emptyList()
        val start8361 = System.currentTimeMillis()
        val sigs8361 = com.lifecyclebot.engine.HeliusCadence8361.signatures8361(http, PUMPSWAP_AMM, HELIUS_SIGNATURES, null, keylessOnly = false, caller = com.lifecyclebot.engine.HeliusCadence8361.CALLER_SWEEP_8361)
        val mints = linkedSetOf<String>()
        for (sig in sigs8361) {
            if (System.currentTimeMillis() - start8361 > 20_000L) break
            val tx = com.lifecyclebot.engine.HeliusCadence8361.transaction8361(http, sig, keylessOnly = false, caller = com.lifecyclebot.engine.HeliusCadence8361.CALLER_SWEEP_8361) ?: continue
            val bals = tx.optJSONObject("meta")?.optJSONArray("postTokenBalances") ?: continue
            for (j in 0 until bals.length()) {
                val m = bals.optJSONObject(j)?.optString("mint").orEmpty()
                if (m.length >= 32 && m !in QUOTE_MINTS) mints += m
            }
        }
        return mints.map {
            Row(it, "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0, false, 0.0, setOf("HELIUS_SWAPS"))
        }
    }

    /** One Jupiter /search call fills cap, liquidity and 1h stats for rows that lack a cap. */
    private suspend fun enrichMissingCaps(rows: List<Row>): List<Row> {
        val missing = rows.filter { it.mcapUsd <= 0.0 }.map { it.mint }.take(100)
        if (missing.isEmpty()) return rows
        // V5.0.7807 — enrichment is up to five sequential blocking calls (Jupiter
        // keyless + keyed, three DexScreener chunks). withContext(IO) could not be
        // cancelled mid-call, so a slow host held the whole sweep. runInterruptible
        // + a hard budget bounds it; unenriched rows simply stay cap-unknown.
        // Field Manual L332 — an unknown figure stays unknown, never guessed.
        val found = withTimeoutOrNull(ENRICH_BUDGET_MS_7807) {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                val body = jupiterBody("/search?query=${missing.joinToString(",")}", "JUP_SEARCH")
                val jup = if (body == null) emptyList() else parseJupiter(JSONArray(body), "JUP_SEARCH")
                // V5.0.7301 — DexScreener fills whatever Jupiter did not.
                val stillMissing = missing - jup.filter { it.mcapUsd > 0.0 }.map { it.mint }.toSet()
                val dex = if (stillMissing.isEmpty()) emptyList() else try { dexScreenerEnrich7301(stillMissing) } catch (_: Throwable) { emptyList() }
                jup + dex
            }
        } ?: run {
            try { PipelineHealthCollector.labelInc("MARKET_SWEEP_ENRICH_BUDGET_EXCEEDED_7807") } catch (_: Throwable) {}
            emptyList()
        }
        if (found.isEmpty()) return rows
        try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_ENRICH_CALL_SERVED") } catch (_: Throwable) {}
        return merge(rows + found)
    }

    private fun Double.finite(): Double = if (isFinite()) this else 0.0

    fun statusLine(): String {
        val s = last ?: return "no sweep yet"
        val age = (System.currentTimeMillis() - s.atMs) / 1000
        val providers = s.providerRows.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val bands = Band.values().joinToString(" ") { b ->
            val st = s.bands[b]
            if (st == null) "${b.name}[0]"
            else "${b.name}[n=${st.count} up=${st.breadthPct.toInt()}% med=${"%+.1f".format(st.medianChangeH1Pct)}% vol=${(100 * st.volumeShare).toInt()}%]"
        }
        val served = providerServed.entries.joinToString(",") { "${it.key}:${it.value}/${it.value + (providerEmpty[it.key] ?: 0L)}" }
        val fails = lastFail7301.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "none" }
        return "rows=${s.rows.size} age=${age}s $providers | $bands | served=$served | fail=$fails carried8124=${carried8124.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "none" }} keyedJupiter=${jupiterKey7301.isNotBlank()}"
    }
}

/** V5.0.7876 — known age under one hour (0.0 = unknown age). */
internal fun underOneHour7876(ageHours: Double): Boolean = ageHours.isFinite() && ageHours > 0.0 && ageHours < 1.0

/**
 * V5.0.7876 — relative strength against the band median. 5.0.7875 ranked
 * minutes-old launches at rs=+1000.0: their "1h change" is the move since the
 * first print, not an hour of strength, so it saturated the clamp and outranked
 * every real leader. A known-sub-hour token has no 1h relative strength (0).
 */
internal fun relativeStrength7876(changeH1Pct: Double, ageHours: Double, bandMedian: Double): Double {
    if (underOneHour7876(ageHours) || !changeH1Pct.isFinite()) return 0.0
    return (changeH1Pct - bandMedian).coerceIn(-1000.0, 1000.0)
}
