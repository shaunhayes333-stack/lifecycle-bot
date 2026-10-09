package com.lifecyclebot.perps.crypto

import android.content.Context
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.MultiChainWalletVault6546
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7962 §EVERYTHING_BUT_SOL.
 *
 * Owner: "the crypto universe is meant to trade everything but sol. bnb has
 * memecoins, eth has meme coins, all chains have sub coins. don't just trade
 * the majors."
 *
 * The 7958 diag read the universe as discovery without execution: 14,534
 * chain+token identities (bsc 3,051, base 2,223, eth 1,161 ...), 546 fresh
 * pools reaching CryptoBrain, and ONE paper-only candidate at the gate. Every
 * EVM token resolved to BRIDGE_REQUIRED (a deBridge round trip from the
 * Solana wallet, priced out below 0.40 SOL) or PAPER_ONLY. Its labels went
 * through CrossAssetCortex7931 as a single CRYPTO_ALT lane with no age (every
 * row AGE_GT24H) and no chain, so nothing learned WHICH chain or cell pays.
 *
 * This lane runs the Solana meme recipe on every EVM chain:
 *
 *  1. DISCOVERY — GeckoTerminal new_pools / trending_pools per network and
 *     DexScreener token-profiles / boosts (keyless), each pool with liquidity,
 *     5m/1h volume, buy/sell counts, price change and pool age. Majors and
 *     stables are excluded: this lane is the sub-coins. GeckoTerminal is
 *     shared with the live candle feed, so a call waits for the feed's slot and
 *     never runs while it cools down (SolanaOhlcvFeed6916.providerSlotWait7809);
 *     every host goes through HealthAwareHttp's backoff.
 *  2. LABELS — every candidate is observed by ForwardReturnLabeler7731 in its
 *     chain's lane (EVM_BSC, EVM_BASE, ...) with its pool age and market cap,
 *     so each lands in a cell  MULTICHAIN | EVM_<chain> | mcap band | age band
 *     read at five minutes, and the same observation opens its Cortex7885 read.
 *     Marks are this lane's own DexScreener pair prices, fetched for exactly the
 *     observations near a horizon (CrossAssetCortex7931.priceFor falls back here).
 *  3. AUTHORITY — a cell earns live entries only from its own record: n >= 20
 *     labels and a mean, less half a standard error, that clears the measured
 *     cost of trading it on that chain (gas at the actual ticket + quote loss +
 *     token tax). A proven-negative cell or lane is refused in both modes. An
 *     unproven cell trades paper (exploration) so the exit book learns too.
 *  4. LIVE GATING — a chain is live only when the vault's EVM signer holds
 *     native gas there and the ticket clears that chain's measured gas against
 *     the best proven edge. Otherwise it runs shadow with real labels until
 *     funded. Ethereum mainnet's gas decides for itself.
 *  5. EXECUTION — EvmSwapExecutor7962 (KyberSwap, honeypot.is), positions and
 *     exits in this file's mirrored book on the learned ExitProfile7955 plan of
 *     the chain's lane (tiers, trail, max hold) with a hard stop.
 *
 * Solana tokens are never touched here (they are not in CHAINS_7962).
 */
object MultiChainMemeLane7962 {
    private const val TAG = "MultiChain7962"
    private const val PREFS = "aate_multichain_lane_7962"
    private const val TICK_MS = 4_000L
    private const val GECKO_GAP_MS = 20_000L
    private const val DEX_LIST_GAP_MS = 60_000L
    private const val DEX_TOKENS_GAP_MS = 1_200L
    private const val CHAIN_REFRESH_MS = 120_000L
    private const val MARK_MAX_AGE_MS = 45_000L
    private const val MARK_REFRESH_MS = 25_000L
    private const val REOBSERVE_MS = 15L * 60_000L
    private const val OBS_KEEP_MS = 72L * 60_000L
    /** Pending labels this lane may hold in the shared labeler (6,000 slots; Solana keeps the rest). */
    private const val LABEL_BUDGET = 700
    private const val MIN_LIQ_USD = 2_000.0
    private const val MIN_N_LIVE = 20
    private const val STOP_PCT = 25.0
    private const val PAPER_TICKET_USD = 25.0
    private const val MAX_OPEN_PER_CHAIN = 2
    private const val MAX_PAPER_PER_CHAIN = 6
    private const val LIVE_TICKET_FRACTION = 0.25
    private const val SWAP_GAS_UNITS_PRIOR = 250_000.0
    private const val APPROVE_GAS_UNITS = 55_000.0
    private const val MAX_TAX_PCT = 10.0

    // ── pure policy (unit-tested in Aate7962MultiChainTest) ─────────────────

    /** One discovered pool, in USD. Counts are trades; changes are percent. */
    data class Candidate7962(
        val chain: String,
        val token: String,
        val symbol: String,
        val pairAddress: String,
        val dexId: String,
        val priceUsd: Double,
        val liquidityUsd: Double,
        val mcapUsd: Double,
        val createdAtMs: Long,
        val buys5m: Int,
        val sells5m: Int,
        val buys1h: Int,
        val sells1h: Int,
        val vol5mUsd: Double,
        val vol1hUsd: Double,
        val chg5mPct: Double,
        val chg1hPct: Double,
        val source: String,
    ) {
        val key: String get() = "$chain|$token"
    }

    private val MAJORS = setOf(
        "ETH", "WETH", "BNB", "WBNB", "BTC", "WBTC", "BTCB", "CBBTC", "USDC", "USDC.E", "USDBC", "USDT", "DAI", "FDUSD",
        "BUSD", "USD1", "USDE", "SUSDE", "TUSD", "FRAX", "LUSD", "POL", "WPOL", "MATIC", "WMATIC", "AVAX", "WAVAX",
        "STETH", "WSTETH", "CBETH", "RETH", "WEETH", "EZETH", "ARB", "OP", "SOL", "WSOL", "EURC", "PYUSD", "GHO", "CRVUSD",
    )

    /** Pure: a major or a stable (the crypto lane's universe, not this one). */
    fun isMajor7962(symbol: String): Boolean = symbol.trim().uppercase() in MAJORS

    /** Pure: the labeler / Cortex / exit-profile lane of a chain. */
    fun laneOf7962(chainKey: String): String = "EVM_${chainKey.trim().uppercase()}"

    private fun d(o: JSONObject?, k: String): Double = o?.opt(k)?.toString()?.toDoubleOrNull() ?: Double.NaN
    private fun i(o: JSONObject?, k: String): Int = o?.opt(k)?.toString()?.toDoubleOrNull()?.toInt() ?: 0
    private fun fin(v: Double): Double = if (v.isFinite()) v else 0.0

    /** Pure: GeckoTerminal /networks/{net}/{new_pools|trending_pools}. Unknown network -> empty. */
    fun parseGeckoPools7962(body: String, network: String, source: String): List<Candidate7962> {
        val chain = EvmSwapExecutor7962.chainFor7962(network) ?: return emptyList()
        val data = try { JSONObject(body).optJSONArray("data") } catch (_: Throwable) { null } ?: return emptyList()
        val out = ArrayList<Candidate7962>()
        for (n in 0 until data.length()) {
            val pool = data.optJSONObject(n) ?: continue
            val a = pool.optJSONObject("attributes") ?: continue
            val rel = pool.optJSONObject("relationships")
            val baseId = rel?.optJSONObject("base_token")?.optJSONObject("data")?.optString("id", "").orEmpty()
            // Ids are "<network>_<address>" and network ids may hold '_' (polygon_pos); addresses never do.
            val token = baseId.substringAfterLast('_', "").lowercase()
            if (!EvmSwapExecutor7962.isEvmAddress7962(token)) continue
            val symbol = a.optString("name", "").substringBefore('/').trim().uppercase()
            val price = d(a, "base_token_price_usd")
            if (!price.isFinite() || price <= 0.0) continue
            val created = try { java.time.Instant.parse(a.optString("pool_created_at", "")).toEpochMilli() } catch (_: Throwable) { 0L }
            val tx = a.optJSONObject("transactions")
            val vol = a.optJSONObject("volume_usd")
            val chg = a.optJSONObject("price_change_percentage")
            val mcap = d(a, "market_cap_usd").takeIf { it.isFinite() && it > 0.0 } ?: d(a, "fdv_usd")
            out += Candidate7962(
                chain = chain.key, token = token, symbol = symbol.ifBlank { token.take(8) },
                pairAddress = a.optString("address", pool.optString("id", "").substringAfterLast('_', "")).lowercase(),
                dexId = rel?.optJSONObject("dex")?.optJSONObject("data")?.optString("id", "").orEmpty(),
                priceUsd = price, liquidityUsd = fin(d(a, "reserve_in_usd")), mcapUsd = fin(mcap), createdAtMs = created,
                buys5m = i(tx?.optJSONObject("m5"), "buys"), sells5m = i(tx?.optJSONObject("m5"), "sells"),
                buys1h = i(tx?.optJSONObject("h1"), "buys"), sells1h = i(tx?.optJSONObject("h1"), "sells"),
                vol5mUsd = fin(d(vol, "m5")), vol1hUsd = fin(d(vol, "h1")),
                chg5mPct = fin(d(chg, "m5")), chg1hPct = fin(d(chg, "h1")), source = source,
            )
        }
        return out
    }

    /** Pure: DexScreener pairs (tokens/v1 array, or {pairs:[...]}). Non-EVM chains are dropped. */
    fun parseDexPairs7962(body: String, source: String): List<Candidate7962> {
        val arr: JSONArray = try {
            val t = body.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("pairs") ?: JSONArray()
        } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<Candidate7962>()
        for (n in 0 until arr.length()) {
            val p = arr.optJSONObject(n) ?: continue
            val chain = EvmSwapExecutor7962.chainFor7962(p.optString("chainId", "")) ?: continue
            val base = p.optJSONObject("baseToken") ?: continue
            val token = base.optString("address", "").lowercase()
            if (!EvmSwapExecutor7962.isEvmAddress7962(token)) continue
            val price = d(p, "priceUsd")
            if (!price.isFinite() || price <= 0.0) continue
            val tx = p.optJSONObject("txns")
            val vol = p.optJSONObject("volume")
            val chg = p.optJSONObject("priceChange")
            val mcap = d(p, "marketCap").takeIf { it.isFinite() && it > 0.0 } ?: d(p, "fdv")
            out += Candidate7962(
                chain = chain.key, token = token, symbol = base.optString("symbol", "").uppercase().ifBlank { token.take(8) },
                pairAddress = p.optString("pairAddress", "").lowercase(), dexId = p.optString("dexId", ""),
                priceUsd = price, liquidityUsd = fin(d(p.optJSONObject("liquidity"), "usd")), mcapUsd = fin(mcap),
                createdAtMs = p.optLong("pairCreatedAt", 0L),
                buys5m = i(tx?.optJSONObject("m5"), "buys"), sells5m = i(tx?.optJSONObject("m5"), "sells"),
                buys1h = i(tx?.optJSONObject("h1"), "buys"), sells1h = i(tx?.optJSONObject("h1"), "sells"),
                vol5mUsd = fin(d(vol, "m5")), vol1hUsd = fin(d(vol, "h1")),
                chg5mPct = fin(d(chg, "m5")), chg1hPct = fin(d(chg, "h1")), source = source,
            )
        }
        return out
    }

    /** Pure: the price of [token] from a pair list: the known pair first, else its deepest pool. */
    fun markFromPairs7962(pairs: List<Candidate7962>, token: String, pairAddress: String): Double? {
        val mine = pairs.filter { it.token.equals(token, true) }
        val px = (mine.firstOrNull { pairAddress.isNotBlank() && it.pairAddress.equals(pairAddress, true) }
            ?: mine.maxByOrNull { it.liquidityUsd })?.priceUsd
        return px?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Pure: the all-in round-trip cost of one ticket on a chain, percent: buy + sell gas and
     * one approval at the measured price, the quote's one-way loss each way, and the token tax.
     */
    fun roundTripCostPct7962(ticketUsd: Double, swapGasUsd: Double, approveGasUsd: Double, swapLossPct: Double, buyTaxPct: Double, sellTaxPct: Double): Double {
        if (!ticketUsd.isFinite() || ticketUsd <= 0.0) return Double.POSITIVE_INFINITY
        val gas = (2.0 * fin(swapGasUsd) + fin(approveGasUsd)) / ticketUsd * 100.0
        return gas + 2.0 * fin(swapLossPct).coerceAtLeast(0.0) + fin(buyTaxPct).coerceAtLeast(0.0) + fin(sellTaxPct).coerceAtLeast(0.0)
    }

    /** Pure: a trade clears its cost when the cell's proven edge exceeds it. No fixed cap: the cost decides. */
    fun costClears7962(costPct: Double, edgePct: Double): Boolean =
        costPct.isFinite() && edgePct.isFinite() && costPct >= 0.0 && edgePct - costPct > 0.0

    /** A chain's live verdict. */
    data class ChainGate7962(val live: Boolean, val reason: String)

    /**
     * Pure: a chain trades live only when the runtime is live, the EVM signer is active, the
     * wallet holds native gas above the exit reserve, and the ticket clears the measured
     * round-trip gas against the chain's best proven cell edge. Otherwise it runs shadow.
     */
    fun chainGate7962(
        paper: Boolean, signerReady: Boolean, gasBalNative: Double, reserveNative: Double,
        ticketUsd: Double, roundTripGasUsd: Double, bestEdgePct: Double,
    ): ChainGate7962 = when {
        paper -> ChainGate7962(false, "PAPER_MODE")
        !signerReady -> ChainGate7962(false, "NO_EVM_SIGNER")
        !gasBalNative.isFinite() || gasBalNative <= 0.0 -> ChainGate7962(false, "NO_GAS")
        gasBalNative <= reserveNative -> ChainGate7962(false, "GAS_BELOW_EXIT_RESERVE")
        !ticketUsd.isFinite() || ticketUsd <= 0.0 -> ChainGate7962(false, "TICKET_UNPRICED")
        !bestEdgePct.isFinite() -> ChainGate7962(false, "NO_PROVEN_CELL")
        !costClears7962(roundTripGasUsd / ticketUsd * 100.0, bestEdgePct) ->
            ChainGate7962(false, "GAS_${(roundTripGasUsd / ticketUsd * 100.0).toInt()}PCT_OVER_EDGE_${bestEdgePct.toInt()}PCT")
        else -> ChainGate7962(true, "LIVE")
    }

    /** A labeler record reduced to what admission reads. */
    data class CellView7962(val n: Int, val meanNetPct: Double, val stderrPct: Double) {
        /** The mean less half a standard error: the edge a decision may lean on. */
        val edgePct: Double get() = if (n >= 2 && stderrPct.isFinite()) meanNetPct - 0.5 * stderrPct else Double.NEGATIVE_INFINITY
        val provenNegative: Boolean get() = n >= MIN_N_LIVE && stderrPct.isFinite() && meanNetPct + 0.5 * stderrPct < 0.0
    }

    /**
     * Pure: null = admit, else the refusal. A proven-negative lane (n >= 50) or cell refuses in
     * both modes; live needs a cell with n >= 20 whose edge clears [extraCostPct] (the chain's
     * measured gas at the ticket, beyond the label's base cost). Paper explores unproven cells.
     */
    fun admit7962(cell: CellView7962?, lane: CellView7962?, live: Boolean, extraCostPct: Double): String? {
        if (lane != null && lane.n >= 50 && lane.provenNegative) return "LANE_PROVEN_NEGATIVE_7962"
        if (cell != null && cell.provenNegative) return "CELL_PROVEN_NEGATIVE_7962"
        if (!live) return null
        if (cell == null || cell.n < MIN_N_LIVE) return "CELL_UNPROVEN_FOR_LIVE_7962"
        if (!costClears7962(extraCostPct, cell.edgePct)) return "CELL_EDGE_BELOW_CHAIN_COST_7962"
        return null
    }

    /** One exit step: sell [fraction] of what is held, for [reason]. */
    data class ExitAction7962(val fraction: Double, val reason: String)

    /**
     * Pure: the mirrored exit stack. Hard stop first; then the learned take-profit tiers
     * (gross trigger, fraction of the holding), one per read, the rest left to run; then the
     * learned trail once the peak passed half the first tier; then the learned max hold.
     */
    fun exitDecision7962(
        pnlPct: Double, peakPct: Double, holdMs: Long, tiers: List<Pair<Double, Double>>, firedTiers: Int,
        trailFrac: Double, maxHoldMs: Long, stopPct: Double,
    ): ExitAction7962? {
        if (!pnlPct.isFinite()) return null
        if (pnlPct <= -stopPct) return ExitAction7962(1.0, "STOP_LOSS_7962")
        if (firedTiers < tiers.size) {
            val (trigger, frac) = tiers[firedTiers]
            if (pnlPct >= trigger) return ExitAction7962(frac.coerceIn(0.05, 1.0), "TIER_${firedTiers + 1}_7962")
        }
        val arm = (tiers.firstOrNull()?.first ?: 30.0) * 0.5
        if (peakPct.isFinite() && peakPct >= arm) {
            val room = maxOf(peakPct * trailFrac.coerceIn(0.05, 0.9), 8.0)
            if (pnlPct <= peakPct - room) return ExitAction7962(1.0, "TRAIL_7962")
        }
        if (maxHoldMs > 0L && holdMs >= maxHoldMs) return ExitAction7962(1.0, "MAX_HOLD_7962")
        return null
    }

    // ── runtime state ───────────────────────────────────────────────────────

    private class ChainState {
        @Volatile var gasBalWei: BigInteger? = null
        @Volatile var gasPriceWei: BigInteger? = null
        @Volatile var nativeUsd: Double = Double.NaN
        @Volatile var swapGasUnits: Double = SWAP_GAS_UNITS_PRIOR
        @Volatile var refreshedAtMs = 0L
        @Volatile var gate = ChainGate7962(false, "STARTING")
        @Volatile var bestEdgePct = Double.NaN
        @Volatile var bestCell = ""
        val candidates = AtomicLong(0)
        val observed = AtomicLong(0)
        val admitted = AtomicLong(0)
        val refused = AtomicLong(0)
        val liveOpened = AtomicLong(0)
        val paperOpened = AtomicLong(0)
        val hpRefused = AtomicLong(0)
        val costRefused = AtomicLong(0)
        val execFailed = AtomicLong(0)
    }

    private class Pos(
        val id: String,
        val chain: String,
        val token: String,
        val symbol: String,
        val pairAddress: String,
        val lane: String,
        val live: Boolean,
        val openedAtMs: Long,
        var state: String,
        var entryPriceUsd: Double,
        var ticketUsd: Double,
        var costPct: Double,
        var qtyRaw: BigInteger = BigInteger.ZERO,
        var spentWei: BigInteger = BigInteger.ZERO,
        var receivedWei: BigInteger = BigInteger.ZERO,
        var buyTaxPct: Double = 0.0,
        var sellTaxPct: Double = 0.0,
        var peakPct: Double = 0.0,
        var tiersFired: Int = 0,
        var sellSeq: Int = 0,
        var sellFails: Int = 0,
        var pendingSellFrac: Double = 0.0,
        var pendingSellReason: String = "",
        var soldFrac: Double = 0.0,
        var paperBookedPct: Double = 0.0,
        var lastMarkUsd: Double = 0.0,
        var closedAtMs: Long = 0L,
        var closeReason: String = "",
        var realizedPct: Double = Double.NaN,
    ) {
        /** The setup the entry was read as (ExitProfile7955 key): only that key's own record shapes its exits. */
        var setup: String = "NONE"
    }

    private val chains = ConcurrentHashMap<String, ChainState>()
    private val marks = ConcurrentHashMap<String, Pair<Double, Long>>()        // key -> (usd, at)
    private val markAskedAt = ConcurrentHashMap<String, Long>()
    private val pairOf = ConcurrentHashMap<String, String>()                    // key -> pair address
    private val obsAt = ConcurrentHashMap<String, Long>()                       // key -> label start
    private val positions = ConcurrentHashMap<String, Pos>()
    private val closedLive = ArrayDeque<Double>()
    private val closedPaper = ArrayDeque<Double>()
    private val geckoCalls = AtomicLong(0)
    private val geckoDeferred = AtomicLong(0)
    private val dexCalls = AtomicLong(0)
    private val budgetSkipped = AtomicLong(0)
    @Volatile private var geckoCursor = 0
    @Volatile private var lastGeckoMs = 0L
    @Volatile private var lastDexListMs = 0L
    @Volatile private var lastDexTokensMs = 0L
    @Volatile private var dexListToggle = false
    @Volatile private var signerAddress: String = ""
    @Volatile private var signerCheckedMs = 0L
    @Volatile private var appCtx: Context? = null
    @Volatile private var job: Job? = null
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }

    private fun stateOf(chain: String): ChainState = chains.getOrPut(chain) { ChainState() }

    /** CrossAssetCortex7931.priceFor fallback: this lane's fresh pair mark for a labelled identity, else null. */
    fun freshMark7962(key: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val m = marks[key] ?: return null
        return if (nowMs - m.second <= MARK_MAX_AGE_MS) m.first else null
    }

    /** CryptoAltTrader.start: the lane runs beside the crypto trader (idempotent). */
    fun start7962(context: Context) {
        appCtx = context.applicationContext
        if (job?.isActive == true) return
        if (positions.isEmpty()) restore()
        job = scope.launch {
            ErrorLogger.info(TAG, "multi-chain meme lane started chains=${EvmSwapExecutor7962.CHAINS_7962.joinToString(",") { it.key }}")
            while (isActive) {
                try { tick() } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    ErrorLogger.warn(TAG, "tick: ${t.message}")
                }
                delay(TICK_MS)
            }
        }
    }

    /** CryptoAltTrader.stop. Open positions persist and resume on the next start. */
    fun stop7962() {
        persist()
        try { job?.cancel() } catch (_: Throwable) {}
        job = null
    }

    private fun paperRuntime(): Boolean =
        try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }

    private fun signer(nowMs: Long): String {
        if (nowMs - signerCheckedMs < 5L * 60_000L) return signerAddress
        signerCheckedMs = nowMs
        val ctx = appCtx ?: return ""
        signerAddress = try { MultiChainWalletVault6546.executable(ctx)?.ethereumAddress.orEmpty() } catch (_: Throwable) { "" }
        return signerAddress
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        refreshChains(now)
        refreshMarks(now)
        managePositions(now)
        discover(now)
        if (now % 60_000L < TICK_MS) persist()
    }

    // ── chain state: gas balance, gas price, native price, gate ──

    private fun weiToNative(w: BigInteger?): Double =
        w?.let { BigDecimal(it).divide(BigDecimal.TEN.pow(18), 12, RoundingMode.DOWN).toDouble() } ?: Double.NaN

    private fun nativeToWei(x: Double): BigInteger =
        if (!x.isFinite() || x <= 0.0) BigInteger.ZERO else BigDecimal(x).multiply(BigDecimal.TEN.pow(18)).toBigInteger()

    private fun swapGasUsd(cs: ChainState, units: Double = cs.swapGasUnits): Double {
        val gp = weiToNative(cs.gasPriceWei)
        return if (gp.isFinite() && cs.nativeUsd.isFinite()) gp * units * cs.nativeUsd else Double.NaN
    }

    private fun roundTripGasUsd(cs: ChainState): Double = 2.0 * swapGasUsd(cs) + swapGasUsd(cs, APPROVE_GAS_UNITS)

    /** Native kept back so every open position can still pay its exits: four round trips of gas. */
    private fun reserveNative(cs: ChainState): Double {
        val gp = weiToNative(cs.gasPriceWei)
        return if (gp.isFinite()) gp * (2.0 * cs.swapGasUnits + APPROVE_GAS_UNITS) * 4.0 else Double.POSITIVE_INFINITY
    }

    private fun liveTicketNative(cs: ChainState): Double {
        val bal = weiToNative(cs.gasBalWei)
        val free = bal - reserveNative(cs)
        return if (free.isFinite() && free > 0.0) free * LIVE_TICKET_FRACTION else 0.0
    }

    private fun ticketUsd(cs: ChainState): Double {
        val t = liveTicketNative(cs) * cs.nativeUsd
        return if (t.isFinite() && t > 0.0) t else PAPER_TICKET_USD
    }

    private suspend fun refreshChains(now: Long) {
        val addr = signer(now)
        val paper = paperRuntime()
        // One chain per tick: the RPC and price reads stay off any hot path and well under every free limit.
        val due = EvmSwapExecutor7962.CHAINS_7962.firstOrNull { now - stateOf(it.key).refreshedAtMs >= CHAIN_REFRESH_MS }
        if (due != null) {
            val cs = stateOf(due.key)
            cs.refreshedAtMs = now
            EvmSwapExecutor7962.gasPriceWei7962(due)?.let { cs.gasPriceWei = it }
            if (addr.isNotBlank()) cs.gasBalWei = EvmSwapExecutor7962.nativeBalanceWei7962(due, addr)
            dexTokens(due, listOf(due.wrappedNative))?.let { pairs ->
                markFromPairs7962(pairs, due.wrappedNative, "")?.let { cs.nativeUsd = it }
            }
            bestCellOf(due.key, cs)
        }
        for (c in EvmSwapExecutor7962.CHAINS_7962) {
            val cs = stateOf(c.key)
            cs.gate = chainGate7962(
                paper, addr.isNotBlank(), weiToNative(cs.gasBalWei), reserveNative(cs),
                if (liveTicketNative(cs) > 0.0) liveTicketNative(cs) * cs.nativeUsd else 0.0,
                roundTripGasUsd(cs), cs.bestEdgePct,
            )
        }
    }

    private val MCAP_REPS = doubleArrayOf(0.0, 5_000.0, 50_000.0, 500_000.0, 5_000_000.0)
    private val AGE_REPS_MIN = longArrayOf(-1L, 5L, 60L, 6L * 60L, 48L * 60L)

    private fun probeTs(lane: String, mcap: Double, ageMin: Long, now: Long) = TokenState(mint = "probe7962|$lane", symbol = "PROBE").apply {
        source = "MULTICHAIN"
        lastMcap = mcap
        addedToWatchlistAt = if (ageMin < 0L) 0L else now - ageMin * 60_000L
    }

    /** The chain's best proven cell (n >= 20) at the five-minute read, by edge (mean less half a standard error). */
    private fun bestCellOf(chain: String, cs: ChainState) {
        val lane = laneOf7962(chain)
        val now = System.currentTimeMillis()
        var best = Double.NaN
        var bestKey = ""
        for (m in MCAP_REPS) for (a in AGE_REPS_MIN) {
            val s = try { ForwardReturnLabeler7731.cellStatFor(probeTs(lane, m, a, now), lane, now) } catch (_: Throwable) { null } ?: continue
            if (s.n60 < MIN_N_LIVE) continue
            val e = CellView7962(s.n60, s.meanNet60Pct, s.stderr60Pct).edgePct
            if (e.isFinite() && (!best.isFinite() || e > best)) { best = e; bestKey = s.key.substringAfter("|$lane|") + " n${s.n60} ${"%+.1f".format(s.meanNet60Pct)}%" }
        }
        cs.bestEdgePct = best
        cs.bestCell = bestKey
    }

    // ── providers (paced, backoff-aware) ──

    private fun get(url: String, host: String): String? = try {
        val accept = if (host == "geckoterminal") "application/json;version=20230302" else "application/json"
        val req = Request.Builder().url(url).header("Accept", accept).header("User-Agent", "lifecycle-bot-android/6.0").build()
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host).use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Throwable) { null }

    /** DexScreener tokens/v1 for up to 30 addresses on one chain, paced (waits its gap); null when the call failed. */
    private suspend fun dexTokens(chain: EvmSwapExecutor7962.Chain7962, addrs: List<String>): List<Candidate7962>? {
        if (addrs.isEmpty()) return emptyList()
        val wait = DEX_TOKENS_GAP_MS - (System.currentTimeMillis() - lastDexTokensMs)
        if (wait > 0L) delay(wait)
        lastDexTokensMs = System.currentTimeMillis()
        dexCalls.incrementAndGet()
        val body = get("https://api.dexscreener.com/tokens/v1/${chain.dexScreenerId}/${addrs.take(30).joinToString(",")}", "dexscreener") ?: return null
        return parseDexPairs7962(body, "DEX_TOKENS")
    }

    // ── marks: open positions every refresh, label observations only near a horizon ──

    /** Pure-ish: is an observation of age [ageMs] inside a window the labeler reads (2m, 5m, 60m + grace)? */
    private fun nearHorizon(ageMs: Long): Boolean =
        ageMs in 105_000L..170_000L || ageMs in 285_000L..440_000L || ageMs in 3_585_000L..4_190_000L

    private suspend fun refreshMarks(now: Long) {
        obsAt.entries.removeIf { now - it.value > OBS_KEEP_MS }
        val want = LinkedHashSet<String>()
        positions.values.filter { it.state != "CLOSED" }.forEach { want += "${it.chain}|${it.token}" }
        obsAt.entries.filter { nearHorizon(now - it.value) }.forEach { want += it.key }
        val stale = want.filter { k -> now - (markAskedAt[k] ?: 0L) >= MARK_REFRESH_MS }
        if (stale.isEmpty()) return
        // At most three calls a tick (one per chain group), positions first.
        stale.groupBy { it.substringBefore('|') }.entries.take(3).forEach { (chainKey, keys) ->
            val chain = EvmSwapExecutor7962.chainFor7962(chainKey) ?: return@forEach
            val batch = keys.take(30)
            val pairs = dexTokens(chain, batch.map { it.substringAfter('|') }) ?: return@forEach
            val at = System.currentTimeMillis()
            for (k in batch) {
                markAskedAt[k] = at
                markFromPairs7962(pairs, k.substringAfter('|'), pairOf[k].orEmpty())?.let { marks[k] = it to at }
            }
        }
        if (marks.size > 5_000) marks.entries.removeIf { now - it.value.second > OBS_KEEP_MS }
        if (markAskedAt.size > 5_000) markAskedAt.entries.removeIf { now - it.value > OBS_KEEP_MS }
    }

    // ── discovery → labels → admission ──

    private val geckoPlan: List<Pair<String, String>> by lazy {
        val top = listOf("bsc", "base", "eth", "arbitrum")
        val rest = listOf("polygon", "avax", "optimism")
        top.map { it to "new_pools" } + top.map { it to "trending_pools" } + rest.map { it to "new_pools" } + top.map { it to "new_pools" }
    }

    private suspend fun discover(now: Long) {
        val found = ArrayList<Candidate7962>()
        if (now - lastGeckoMs >= GECKO_GAP_MS) {
            val slot = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809(now) } catch (_: Throwable) { null }
            // Never while the host is refusing the candle feed (429/5xx cooldown); one call per 20 s otherwise.
            if (slot?.cooldown == true) {
                geckoDeferred.incrementAndGet()
            } else {
                lastGeckoMs = now
                val (chainKey, endpoint) = geckoPlan[geckoCursor % geckoPlan.size]
                geckoCursor++
                val chain = EvmSwapExecutor7962.chainFor7962(chainKey)
                if (chain != null) {
                    geckoCalls.incrementAndGet()
                    get("https://api.geckoterminal.com/api/v2/networks/${chain.geckoNetwork}/$endpoint?page=1", "geckoterminal")
                        ?.let { found += parseGeckoPools7962(it, chain.geckoNetwork, if (endpoint == "new_pools") "GT_NEW" else "GT_TRENDING") }
                }
            }
        }
        if (now - lastDexListMs >= DEX_LIST_GAP_MS) {
            lastDexListMs = now
            dexListToggle = !dexListToggle
            val url = if (dexListToggle) "https://api.dexscreener.com/token-profiles/latest/v1" else "https://api.dexscreener.com/token-boosts/latest/v1"
            dexCalls.incrementAndGet()
            val listed = get(url, "dexscreener")?.let { body ->
                try {
                    val arr = JSONArray(body)
                    (0 until arr.length()).mapNotNull { n ->
                        val o = arr.optJSONObject(n) ?: return@mapNotNull null
                        val c = EvmSwapExecutor7962.chainFor7962(o.optString("chainId", "")) ?: return@mapNotNull null
                        val t = o.optString("tokenAddress", "").lowercase()
                        if (EvmSwapExecutor7962.isEvmAddress7962(t)) c to t else null
                    }
                } catch (_: Throwable) { emptyList() }
            }.orEmpty()
            listed.groupBy({ it.first }, { it.second }).entries.take(3).forEach { (chain, tokens) ->
                // The listing names tokens; their pools come from the paced tokens endpoint (next ticks pick up the rest).
                val fresh = tokens.filter { now - (obsAt["${chain.key}|$it"] ?: 0L) >= REOBSERVE_MS }
                dexTokens(chain, fresh)?.let { pairs ->
                    found += pairs.groupBy { it.token }.values.mapNotNull { ps -> ps.maxByOrNull { it.liquidityUsd } }
                        .map { it.copy(source = if (dexListToggle) "DEX_PROFILE" else "DEX_BOOST") }
                }
            }
        }
        if (found.isEmpty()) return
        // One read per token per pass; liveliest first.
        val ordered = found.groupBy { it.key }.values.mapNotNull { it.maxByOrNull { c -> c.liquidityUsd } }
            .sortedByDescending { scoreOf(it) }
        for (c in ordered) consider(c, now)
    }

    /** Ordering only (and the Cortex's entry score): buy pressure, activity, freshness. Never authority. */
    private fun scoreOf(c: Candidate7962): Int {
        val tx5 = c.buys5m + c.sells5m
        val bp = if (tx5 > 0) c.buys5m.toDouble() / tx5 else 0.5
        val fresh = c.createdAtMs > 0L && System.currentTimeMillis() - c.createdAtMs < 60L * 60_000L
        return ((bp * 50.0) + minOf(tx5, 60) * 0.5 + (if (fresh) 15.0 else 0.0) + (if (c.chg5mPct > 0.0) 5.0 else 0.0)).toInt().coerceIn(0, 100)
    }

    private fun tokenStateOf(c: Candidate7962, now: Long): TokenState {
        val tx5 = c.buys5m + c.sells5m
        return TokenState(mint = c.key, symbol = c.symbol, name = c.symbol, pairAddress = c.pairAddress).apply {
            source = "MULTICHAIN"
            addedToWatchlistAt = c.createdAtMs.coerceAtLeast(0L)
            lastPrice = c.priceUsd
            lastPriceUpdate = now
            lastPriceSource = "DEXSCREENER_POLL"
            lastPricePoolAddr = c.pairAddress
            lastPriceDex = c.dexId.uppercase()
            lastMcap = c.mcapUsd
            lastFdv = c.mcapUsd
            lastLiquidityUsd = c.liquidityUsd
            lastBuyPressurePct = if (tx5 > 0) c.buys5m * 100.0 / tx5 else 50.0
            lastSellPressurePct = if (tx5 > 0) c.sells5m * 100.0 / tx5 else 50.0
            lastPriceChange5m = c.chg5mPct
            lastPriceChange1h = c.chg1hPct
            entryScore = scoreOf(c).toDouble()
        }
    }

    private fun cellView(s: ForwardReturnLabeler7731.CellStat?): CellView7962? =
        s?.let { CellView7962(it.n60, it.meanNet60Pct, it.stderr60Pct) }

    private suspend fun consider(c: Candidate7962, now: Long) {
        if (isMajor7962(c.symbol) || c.liquidityUsd < MIN_LIQ_USD || c.buys5m + c.sells5m + c.buys1h + c.sells1h < 5) return
        val cs = stateOf(c.chain)
        cs.candidates.incrementAndGet()
        val seen = obsAt[c.key]
        if (seen != null && now - seen < REOBSERVE_MS) return
        if (obsAt.size >= LABEL_BUDGET) { budgetSkipped.incrementAndGet(); return }
        val lane = laneOf7962(c.chain)
        val ts = tokenStateOf(c, now)
        pairOf[c.key] = c.pairAddress
        marks[c.key] = c.priceUsd to now
        val cell = cellView(try { ForwardReturnLabeler7731.cellStatFor(ts, lane, now) } catch (_: Throwable) { null })
        val laneStat = cellView(try { ForwardReturnLabeler7731.laneStatFor7737(lane) } catch (_: Throwable) { null })
        val liveChain = cs.gate.live
        val ticket = ticketUsd(cs)
        val gasPct = (roundTripGasUsd(cs) / ticket * 100.0).let { if (it.isFinite()) it else Double.POSITIVE_INFINITY }
        val open = positions.values.count { it.chain == c.chain && it.state != "CLOSED" && it.live == liveChain }
        val cap = if (liveChain) MAX_OPEN_PER_CHAIN else MAX_PAPER_PER_CHAIN
        val refusal = admit7962(cell, laneStat, liveChain, if (liveChain) gasPct else 0.0)
            ?: (try { com.lifecyclebot.engine.cortex.Cortex7885.entryRefusal(ts, lane, !liveChain) } catch (_: Throwable) { null })
            ?: (if (open >= cap) "CHAIN_SLOTS_FULL_7962" else null)
            ?: (if (positions.values.any { it.chain == c.chain && it.token == c.token && it.state != "CLOSED" }) "ALREADY_HELD_7962" else null)
        val admitted = refusal == null
        obsAt[c.key] = now
        try { ForwardReturnLabeler7731.observe(ts, lane, admitted, refusal, now, scoreOf(c)) } catch (_: Throwable) {}
        cs.observed.incrementAndGet()
        if (!admitted) { cs.refused.incrementAndGet(); return }
        cs.admitted.incrementAndGet()
        val setup = try { com.lifecyclebot.engine.ExitProfile7955.entrySetup7955(ts, lane, now) } catch (_: Throwable) { "NONE" }
        if (liveChain) openLive(c, cs, cell, now, setup) else openPaper(c, cs, lane, gasPct, now, setup)
    }

    private fun openPaper(c: Candidate7962, cs: ChainState, lane: String, gasPct: Double, now: Long, setup: String) {
        val cost = (if (gasPct.isFinite()) gasPct else 0.0) + 2.0
        val p = Pos("P7962_${c.chain}_${c.token.takeLast(8)}_$now", c.chain, c.token, c.symbol, c.pairAddress, lane,
            live = false, openedAtMs = now, state = "OPEN", entryPriceUsd = c.priceUsd, ticketUsd = ticketUsd(cs), costPct = cost)
        p.setup = setup
        positions[p.id] = p
        cs.paperOpened.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MULTICHAIN_PAPER_OPEN_7962_${c.chain.uppercase()}") } catch (_: Throwable) {}
    }

    private suspend fun openLive(c: Candidate7962, cs: ChainState, cell: CellView7962?, now: Long, setup: String) {
        val ctx = appCtx ?: return
        val chain = EvmSwapExecutor7962.chainFor7962(c.chain) ?: return
        val hp = EvmSwapExecutor7962.honeypot7962(chain, c.token, MAX_TAX_PCT)
        if (!hp.ok) {
            cs.hpRefused.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MULTICHAIN_HONEYPOT_REFUSED_7962_${hp.code}") } catch (_: Throwable) {}
            return
        }
        val ticketNative = liveTicketNative(cs)
        val amountWei = nativeToWei(ticketNative)
        val quote = EvmSwapExecutor7962.quote7962(chain, EvmSwapExecutor7962.NATIVE_7962, c.token, amountWei)
        if (quote == null) { cs.execFailed.incrementAndGet(); return }
        if (quote.gasUnits > 0L) cs.swapGasUnits = cs.swapGasUnits * 0.7 + quote.gasUnits * 0.3
        val ticketUsd = ticketNative * cs.nativeUsd
        val cost = roundTripCostPct7962(ticketUsd, swapGasUsd(cs), swapGasUsd(cs, APPROVE_GAS_UNITS), quote.swapLossPct, hp.buyTaxPct, hp.sellTaxPct)
        if (!costClears7962(cost, cell?.edgePct ?: Double.NEGATIVE_INFINITY)) {
            cs.costRefused.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MULTICHAIN_COST_REFUSED_7962_${c.chain.uppercase()}") } catch (_: Throwable) {}
            return
        }
        val p = Pos("L7962_${c.chain}_${c.token.takeLast(8)}_$now", c.chain, c.token, c.symbol, c.pairAddress, laneOf7962(c.chain),
            live = true, openedAtMs = now, state = "PENDING_BUY", entryPriceUsd = c.priceUsd, ticketUsd = ticketUsd, costPct = cost,
            buyTaxPct = fin(hp.buyTaxPct), sellTaxPct = fin(hp.sellTaxPct))
        p.spentWei = amountWei
        p.setup = setup
        positions[p.id] = p
        persist()
        cs.liveOpened.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MULTICHAIN_LIVE_BUY_SUBMIT_7962_${c.chain.uppercase()}") } catch (_: Throwable) {}
        resumeBuy(ctx, chain, p, cs)
    }

    private suspend fun resumeBuy(ctx: Context, chain: EvmSwapExecutor7962.Chain7962, p: Pos, cs: ChainState) {
        val slip = EvmSwapExecutor7962.slippageBps7962(p.buyTaxPct, 3.0)
        when (val r = EvmSwapExecutor7962.buy7962(ctx, chain, p.token, p.spentWei, "${p.id}:BUY", slip)) {
            is EvmSwapExecutor7962.Swap7962.Filled -> {
                val dec = EvmSwapExecutor7962.decimals7962(chain, p.token) ?: 18
                val qty = BigDecimal(r.tokenDeltaRaw).divide(BigDecimal.TEN.pow(dec), 18, RoundingMode.DOWN).toDouble()
                val spentUsd = weiToNative(r.nativeDeltaWei) * cs.nativeUsd
                p.qtyRaw = r.tokenDeltaRaw
                p.spentWei = r.nativeDeltaWei
                // The fill basis is what the wallet paid (gas included) per token received.
                if (qty > 0.0 && spentUsd.isFinite() && spentUsd > 0.0) p.entryPriceUsd = spentUsd / qty
                p.state = "OPEN"
                persist()
                try { PipelineHealthCollector.labelInc("MULTICHAIN_LIVE_BUY_FILLED_7962_${chain.key.uppercase()}") } catch (_: Throwable) {}
                ErrorLogger.info(TAG, "LIVE BUY ${p.symbol} ${chain.key} tx=${r.txHash} spent=${weiToNative(r.nativeDeltaWei)} ${chain.nativeSymbol}")
            }
            is EvmSwapExecutor7962.Swap7962.Pending -> Unit
            is EvmSwapExecutor7962.Swap7962.Rejected -> {
                cs.execFailed.incrementAndGet()
                p.state = "CLOSED"; p.closedAtMs = System.currentTimeMillis(); p.closeReason = "BUY_${r.code}"
                persist()
                try { PipelineHealthCollector.labelInc("MULTICHAIN_LIVE_BUY_REJECTED_7962_${r.code}") } catch (_: Throwable) {}
            }
        }
    }

    // ── positions and exits ──

    private suspend fun managePositions(now: Long) {
        val ctx = appCtx
        for (p in positions.values.toList()) {
            if (p.state == "CLOSED") {
                if (now - p.closedAtMs > 6L * 3_600_000L) {
                    positions.remove(p.id)
                    ctx?.let { c -> EvmSwapExecutor7962.forget7962(c, "${p.id}:BUY"); for (s in 0..p.sellSeq) EvmSwapExecutor7962.forget7962(c, "${p.id}:SELL:$s") }
                }
                continue
            }
            val chain = EvmSwapExecutor7962.chainFor7962(p.chain) ?: continue
            val cs = stateOf(p.chain)
            if (p.state == "PENDING_BUY" && now - p.openedAtMs > 30L * 60_000L) {
                // Never settled in 30 minutes: surfaced (wallet check), not silently dropped.
                p.state = "CLOSED"; p.closedAtMs = now; p.closeReason = "BUY_UNSETTLED_CHECK_WALLET_7962"
                cs.execFailed.incrementAndGet()
                persist()
                continue
            }
            if (p.state == "PENDING_BUY") { if (ctx != null) resumeBuy(ctx, chain, p, cs); continue }
            if (p.state == "PENDING_SELL") { if (ctx != null) runSell(ctx, chain, p, cs); continue }
            val mark = freshMark7962("${p.chain}|${p.token}", now) ?: continue
            if (!(p.entryPriceUsd > 0.0)) continue
            p.lastMarkUsd = mark
            val pnl = (mark / p.entryPriceUsd - 1.0) * 100.0
            if (pnl > p.peakPct) p.peakPct = pnl
            val plan = try { com.lifecyclebot.engine.ExitProfile7955.planForKey7955(p.lane, p.setup, now) } catch (_: Throwable) { null }
            val tiers = plan?.tiers ?: listOf(40.0 to 0.6, 120.0 to 0.7, 400.0 to 0.7)
            val act = exitDecision7962(pnl, p.peakPct, now - p.openedAtMs, tiers, p.tiersFired,
                plan?.trailFrac ?: 0.5, plan?.maxHoldMs ?: 60L * 60_000L, STOP_PCT) ?: continue
            if (act.reason.startsWith("TIER_")) p.tiersFired++
            if (!p.live) {
                val frac = (act.fraction * (1.0 - p.soldFrac)).coerceIn(0.0, 1.0 - p.soldFrac)
                p.paperBookedPct += frac * (pnl - p.costPct)
                p.soldFrac += frac
                if (act.fraction >= 1.0 || p.soldFrac >= 0.999) close(p, act.reason, p.paperBookedPct, now)
            } else if (ctx != null) {
                p.pendingSellFrac = act.fraction
                p.pendingSellReason = act.reason
                p.state = "PENDING_SELL"
                persist()
                runSell(ctx, chain, p, cs)
            }
        }
    }

    private suspend fun runSell(ctx: Context, chain: EvmSwapExecutor7962.Chain7962, p: Pos, cs: ChainState) {
        val qty = if (p.pendingSellFrac >= 0.999) p.qtyRaw
        else BigDecimal(p.qtyRaw).multiply(BigDecimal(p.pendingSellFrac)).toBigInteger()
        if (qty <= BigInteger.ZERO) { p.state = "OPEN"; return }
        val slip = EvmSwapExecutor7962.slippageBps7962(p.sellTaxPct, 4.0 + 2.0 * p.sellFails)
        when (val r = EvmSwapExecutor7962.sell7962(ctx, chain, p.token, qty, "${p.id}:SELL:${p.sellSeq}", slip)) {
            is EvmSwapExecutor7962.Swap7962.Filled -> {
                p.receivedWei += r.nativeDeltaWei
                p.sellSeq++
                p.sellFails = 0
                val left = EvmSwapExecutor7962.tokenBalance7962(chain, p.token, signerAddress).takeIf { signerAddress.isNotBlank() }
                p.qtyRaw = left?.min(p.qtyRaw - r.tokenDeltaRaw)?.max(BigInteger.ZERO) ?: (p.qtyRaw - r.tokenDeltaRaw).max(BigInteger.ZERO)
                if (p.pendingSellFrac >= 0.999 || p.qtyRaw <= BigInteger.ZERO) {
                    val realized = if (p.spentWei > BigInteger.ZERO)
                        (BigDecimal(p.receivedWei).divide(BigDecimal(p.spentWei), 8, RoundingMode.HALF_UP).toDouble() - 1.0) * 100.0 else Double.NaN
                    close(p, p.pendingSellReason, realized, System.currentTimeMillis())
                } else {
                    p.state = "OPEN"
                }
                persist()
                try { PipelineHealthCollector.labelInc("MULTICHAIN_LIVE_SELL_FILLED_7962_${chain.key.uppercase()}") } catch (_: Throwable) {}
            }
            is EvmSwapExecutor7962.Swap7962.Pending -> Unit
            is EvmSwapExecutor7962.Swap7962.Rejected -> {
                // A failed sell never closes a held position: next attempt uses a new key and wider slippage.
                p.sellSeq++
                p.sellFails++
                cs.execFailed.incrementAndGet()
                p.state = "OPEN"
                persist()
                try { PipelineHealthCollector.labelInc("MULTICHAIN_LIVE_SELL_REJECTED_7962_${r.code}") } catch (_: Throwable) {}
            }
        }
    }

    private fun close(p: Pos, reason: String, realizedPct: Double, now: Long) {
        p.state = "CLOSED"
        p.closedAtMs = now
        p.closeReason = reason
        p.realizedPct = realizedPct
        val book = if (p.live) closedLive else closedPaper
        synchronized(book) {
            if (realizedPct.isFinite()) book.addLast(realizedPct)
            while (book.size > 200) book.removeFirst()
        }
        try { PipelineHealthCollector.labelInc("MULTICHAIN_${if (p.live) "LIVE" else "PAPER"}_CLOSE_7962_${p.chain.uppercase()}") } catch (_: Throwable) {}
        persist()
    }

    // ── persistence ──

    private fun persist() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            positions.values.forEach { p ->
                arr.put(JSONObject().put("id", p.id).put("c", p.chain).put("t", p.token).put("s", p.symbol).put("pa", p.pairAddress)
                    .put("l", p.lane).put("live", p.live).put("o", p.openedAtMs).put("st", p.state).put("e", p.entryPriceUsd)
                    .put("tu", p.ticketUsd).put("cp", p.costPct).put("q", p.qtyRaw.toString()).put("sw", p.spentWei.toString())
                    .put("rw", p.receivedWei.toString()).put("bt", p.buyTaxPct).put("stx", p.sellTaxPct).put("pk", p.peakPct)
                    .put("tf", p.tiersFired).put("ss", p.sellSeq).put("sf", p.sellFails).put("psf", p.pendingSellFrac)
                    .put("psr", p.pendingSellReason).put("sold", p.soldFrac).put("pb", p.paperBookedPct).put("ca", p.closedAtMs)
                    .put("cr", p.closeReason).put("rp", if (p.realizedPct.isFinite()) p.realizedPct else JSONObject.NULL).put("su", p.setup))
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("positions", arr.toString()).apply()
        } catch (_: Throwable) {}
    }

    private fun restore() {
        val ctx = appCtx ?: return
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("positions", null) ?: return
            val arr = JSONArray(raw)
            for (n in 0 until arr.length()) {
                val j = arr.optJSONObject(n) ?: continue
                val p = Pos(j.getString("id"), j.getString("c"), j.getString("t"), j.optString("s"), j.optString("pa"), j.optString("l"),
                    j.optBoolean("live"), j.optLong("o"), j.optString("st", "CLOSED"), j.optDouble("e", 0.0), j.optDouble("tu", 0.0), j.optDouble("cp", 0.0),
                    BigInteger(j.optString("q", "0")), BigInteger(j.optString("sw", "0")), BigInteger(j.optString("rw", "0")),
                    j.optDouble("bt", 0.0), j.optDouble("stx", 0.0), j.optDouble("pk", 0.0), j.optInt("tf"), j.optInt("ss"), j.optInt("sf"),
                    j.optDouble("psf", 0.0), j.optString("psr"), j.optDouble("sold", 0.0), j.optDouble("pb", 0.0), 0.0, j.optLong("ca"),
                    j.optString("cr"), j.optDouble("rp", Double.NaN))
                p.setup = j.optString("su", "NONE").ifBlank { "NONE" }
                if (p.entryPriceUsd > 0.0 || p.state == "PENDING_BUY") positions[p.id] = p
                pairOf["${p.chain}|${p.token}"] = p.pairAddress
            }
        } catch (t: Throwable) { ErrorLogger.warn(TAG, "restore: ${t.message}") }
    }

    // ── diag ──

    private fun fmtBook(b: ArrayDeque<Double>): String = synchronized(b) {
        if (b.isEmpty()) "n0" else "n${b.size} mean=${"%+.1f".format(b.average())}% wr=${(b.count { it > 0.0 } * 100 / b.size)}%"
    }

    /** PipelineHealthCollector: per chain gas balance, live/shadow, candidates, labels, cell EV. */
    fun statusLine(): String {
        val addr = signerAddress
        val sb = StringBuilder()
        sb.append("signer=").append(if (addr.isBlank()) "NONE(Wallet > CROSS-CHAIN BRIDGE > Create EVM signer + Confirm backup)" else addr)
            .append(" mode=").append(if (paperRuntime()) "PAPER" else "LIVE")
            .append(" running=").append(job?.isActive == true)
            .append(" labelsPending=").append(obsAt.size).append('/').append(LABEL_BUDGET)
            .append(" budgetSkipped=").append(budgetSkipped.get())
            .append(" calls[gecko=").append(geckoCalls.get()).append(" deferred=").append(geckoDeferred.get())
            .append(" dex=").append(dexCalls.get()).append(']')
            .append(" closed[live ").append(fmtBook(closedLive)).append(" · paper ").append(fmtBook(closedPaper)).append(']')
        for (c in EvmSwapExecutor7962.CHAINS_7962) {
            val cs = chains[c.key] ?: continue
            val lane = laneOf7962(c.key)
            val ls = try { ForwardReturnLabeler7731.laneStatFor7737(lane) } catch (_: Throwable) { null }
            val bal = weiToNative(cs.gasBalWei)
            val open = positions.values.filter { it.chain == c.key && it.state != "CLOSED" }
            sb.append("\n      ").append(c.key).append(": gas=")
                .append(if (bal.isFinite()) "%.5f".format(bal) else "?").append(c.nativeSymbol)
                .append(if (bal.isFinite() && cs.nativeUsd.isFinite()) "(\$${"%.2f".format(bal * cs.nativeUsd)})" else "")
                .append(' ').append(if (cs.gate.live) "LIVE" else "SHADOW(${cs.gate.reason})")
                .append(" rtGas=\$").append(roundTripGasUsd(cs).let { if (it.isFinite()) "%.3f".format(it) else "?" })
                .append(" ticket=\$").append("%.2f".format(ticketUsd(cs)))
                .append(" cand=").append(cs.candidates.get()).append(" labels=").append(cs.observed.get())
                .append(" adm=").append(cs.admitted.get()).append(" ref=").append(cs.refused.get())
                .append(" lane5m[").append(if (ls == null) "n0" else "n${ls.n60} ${"%+.1f".format(ls.meanNet60Pct)}% wr${(ls.winRate60 * 100).toInt()}% lost${ls.lost}").append(']')
                .append(" bestCell[").append(cs.bestCell.ifBlank { "none n>=$MIN_N_LIVE" }).append(']')
                .append(" open[live=").append(open.count { it.live }).append(" paper=").append(open.count { !it.live }).append(']')
                .append(" hpRef=").append(cs.hpRefused.get()).append(" costRef=").append(cs.costRefused.get())
                .append(" execFail=").append(cs.execFailed.get())
        }
        if (chains.isEmpty()) sb.append(" chains=not_refreshed_yet")
        return sb.toString()
    }
}
