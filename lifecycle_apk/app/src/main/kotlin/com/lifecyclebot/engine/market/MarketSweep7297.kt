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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private const val HELIUS_INTERVAL_MS = 120_000L
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
        val runHelius = heliusKey.isNotBlank() && now - lastHeliusAtMs >= HELIUS_INTERVAL_MS
        if (runHelius) lastHeliusAtMs = now

        val tasks = mutableListOf<Pair<String, suspend () -> List<Row>>>(
            "JUP_TRENDING" to { jupiterList("/toptrending/1h?limit=50", "JUP_TRENDING") },
            "JUP_TRADED" to { jupiterList("/toptraded/1h?limit=50", "JUP_TRADED") },
            "JUP_ORGANIC" to { jupiterList("/toporganicscore/1h?limit=50", "JUP_ORGANIC") },
            "JUP_RECENT" to { jupiterList("/recent?limit=50", "JUP_RECENT") },
            "RAYDIUM_VOLUME" to { raydiumPools() },
        )
        if (runHelius) tasks += "HELIUS_SWAPS" to { heliusSwaps(heliusKey) }
        // V5.0.7300 — the external hive: GMGN smart-money rows kept by
        // ExternalAlphaFeeds' own 90 s poll (no request made here).
        tasks += "GMGN_SMART_MONEY" to {
            try { com.lifecyclebot.v4.meta.ExternalAlphaFeeds.smartMoneyRows7300() } catch (_: Throwable) { emptyList() }
        }

        val results = coroutineScope {
            tasks.map { (name, fn) ->
                async(Dispatchers.IO) {
                    val rows = try {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { fn() } ?: emptyList()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ErrorLogger.debug("MarketSweep7297", "$name failed: ${t.message}")
                        emptyList()
                    }
                    name to rows
                }
            }.awaitAll()
        }

        val providerRows = results.associate { it.first to it.second.size }
        results.forEach { (name, rows) ->
            if (rows.isEmpty()) providerEmpty.merge(name, 1L, Long::plus)
            else providerServed.merge(name, 1L, Long::plus)
            try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_${name}_${if (rows.isEmpty()) "EMPTY" else "SERVED"}") } catch (_: Throwable) {}
        }

        val merged = merge(results.flatMap { it.second })
        val enriched = enrichMissingCaps(merged)
        if (enriched.isEmpty()) return last
        val previous7777 = last
        val snap = Snapshot(enriched, bandStates(enriched), providerRows, System.currentTimeMillis())
        rebuildOpportunitySignals7777(previous7777, snap)
        last = snap
        return snap
    }

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
                )
            }

    /** Pure: breadth, median move and volume share per market-cap band. */
    private fun bandStates(rows: List<Row>): Map<Band, BandState> {
        val byBand = rows.mapNotNull { r -> Band.of(r.mcapUsd)?.let { it to r } }.groupBy({ it.first }, { it.second })
        val totalVol = rows.sumOf { it.volumeH1Usd.coerceAtLeast(0.0) }
        return byBand.mapValues { (band, rs) ->
            val moves = rs.map { it.priceChangeH1Pct }.sorted()
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
            val relative = (r.priceChangeH1Pct - bandMedian).coerceIn(-1000.0, 1000.0)
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
            } else resp.body?.string()
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
            if (fail == "HTTP_401" || fail == "HTTP_403" || fail == "HTTP_404") {
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
            fun vol(s: JSONObject?) = if (s == null) 0.0 else s.optDouble("buyVolume", 0.0).finite() + s.optDouble("sellVolume", 0.0).finite()
            val created = o.optJSONObject("firstPool")?.optString("createdAt", "").orEmpty()
            val createdMs = try { if (created.isBlank()) 0L else java.time.Instant.parse(created).toEpochMilli() } catch (_: Throwable) { 0L }
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

    private val JSON = "application/json".toMediaType()

    private fun rpc(key: String, payload: String): String? = try {
        http.newCall(
            Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=$key")
                .post(payload.toRequestBody(JSON)).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful || HostCircuitInterceptor.isSyntheticBlock(resp)) null else resp.body?.string()
        }
    } catch (e: Exception) { null }

    /** Mints traded in the latest PumpSwap AMM swaps, read from Helius RPC. */
    private fun heliusSwaps(key: String): List<Row> {
        val sigBody = rpc(
            key,
            """{"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress","params":["$PUMPSWAP_AMM",{"limit":$HELIUS_SIGNATURES}]}""",
        ) ?: return emptyList()
        val sigs = JSONObject(sigBody).optJSONArray("result") ?: return emptyList()
        val batch = JSONArray()
        for (i in 0 until sigs.length()) {
            val s = sigs.optJSONObject(i) ?: continue
            if (!s.isNull("err")) continue
            batch.put(JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", i); put("method", "getTransaction")
                put("params", JSONArray().apply {
                    put(s.optString("signature"))
                    put(JSONObject().apply { put("encoding", "jsonParsed"); put("maxSupportedTransactionVersion", 0) })
                })
            })
        }
        if (batch.length() == 0) return emptyList()
        val txBody = rpc(key, batch.toString())?.trim() ?: return emptyList()
        if (!txBody.startsWith("[")) return emptyList()
        val txs = JSONArray(txBody)
        val mints = linkedSetOf<String>()
        for (i in 0 until txs.length()) {
            val bals = txs.optJSONObject(i)?.optJSONObject("result")?.optJSONObject("meta")
                ?.optJSONArray("postTokenBalances") ?: continue
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
        return "rows=${s.rows.size} age=${age}s $providers | $bands | served=$served | fail=$fails keyedJupiter=${jupiterKey7301.isNotBlank()}"
    }
}
