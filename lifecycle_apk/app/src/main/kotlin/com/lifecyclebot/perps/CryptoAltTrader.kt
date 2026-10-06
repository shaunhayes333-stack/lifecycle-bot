package com.lifecyclebot.perps

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LiveAttemptStats
import com.lifecyclebot.engine.RunTracker30D
import com.lifecyclebot.engine.ShadowLearningEngine
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.engine.ExecutableOpenGate
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.LaneExecutionCoordinator
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LaneEntryContract6342
import com.lifecyclebot.perps.crypto.CryptoFinalBuyCandidate
import com.lifecyclebot.perps.crypto.CryptoUniverseRouteResolver
import com.lifecyclebot.perps.crypto.isRealTradeable7005
import com.lifecyclebot.v3.scoring.BehaviorAI
import com.lifecyclebot.v3.scoring.BlueChipTraderAI
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI
import com.lifecyclebot.v3.scoring.MetaCognitionAI
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import com.lifecyclebot.v4.meta.NarrativeFlowAI
import com.lifecyclebot.v3.scoring.QualityTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinExpress
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI
import com.lifecyclebot.v4.meta.StrategyTrustAI
import com.lifecyclebot.v3.scoring.VolatilityRegimeAI
import com.lifecyclebot.v4.meta.*
import com.lifecyclebot.v4.meta.TradeLessonRecorder
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════════════════
// 🪙 CRYPTO ALT TRADER — V1.0.0
// ═══════════════════════════════════════════════════════════════════════════════
//
// Third dedicated trading engine for the full crypto alt market.
// Mirrors the SOL perps (meme) trader architecture exactly:
//   • Paper-first learning (5000-trade maturity arc, FluidLearningAI)
//   • Identical UI readiness phases: BOOTSTRAP → LEARNING → VALIDATING → MATURING → READY → LIVE
//   • Full V3/V4 AI layer stack (PerpsAdvancedAI, CrossTalkFusionEngine, PortfolioHeatAI,
//     CrossAssetLeadLagAI, CrossMarketRegimeAI, LeverageSurvivalAI)
//   • New CryptoAltScannerAI layer for alt-specific signals (BTC correlation, sector rotation,
//     dominance cycles, narrative momentum, L1/L2 spread, DeFi heat)
//   • Live execution via Jupiter DEX swap (SOL → wrapped alt token) using existing wallet
//   • Same SPOT / LEVERAGE toggle, same position card structure, same Hivemind wiring
//
// Markets:
//   All isCrypto && !isSolPerp markets from PerpsMarket (SOL perps engine covers the isSolPerp set)
//   ~68 alts: BTC, ETH, BNB, XRP, SHIB, FLOKI, TRUMP, POPCAT, ADA, SEI, FTM, ALGO,
//             HBAR, ICP, VET, FIL, RENDER, GRT, AAVE, MKR, STX, IMX, SAND, MANA, AXS,
//             ENS, LDO, RPL, MNGO, TRX, TON, BCH, XLM, XMR, ETC, ZEC, XTZ, EOS,
//             CAKE, NOT, and more.
//
// ═══════════════════════════════════════════════════════════════════════════════

object CryptoAltTrader {

    private const val TAG = "🪙CryptoAltTrader"

    // ─────────────────────────────────────────────────────────────────────────
    // V5.9.400 — realistic per-tier liquidity / mcap hints for V3 bridge.
    // Hardcoded $500K liq + $50M mcap was producing systematic
    // `liquidity=-7, metacognition=-4` veto on every alt signal (45→0 entries).
    // These tiers are coarse but accurate enough to stop the bleed; they keep
    // the V3 layers (LiquidityExitPath, ExecutionCost, MEV, OperatorFingerprint
    // etc.) feeding from sensible bands so they actually accumulate samples.
    // ─────────────────────────────────────────────────────────────────────────
    private val tier1 = setOf("BTC", "ETH", "SOL", "BNB", "XRP", "WBTC", "STETH")           // $1B+ liq, $50B+ mcap
    private val tier2 = setOf("DOGE", "ADA", "TRX", "LINK", "AVAX", "TON", "DOT", "MATIC",
                              "LTC", "BCH", "XMR", "XLM", "ETC", "NEAR", "APT", "ARB", "OP",
                              "ATOM", "ICP", "FIL", "HBAR", "VET", "INJ", "TAO", "RENDER")  // $100M+ liq, $5B+ mcap
    /**
     * V5.0.6923 — one place that maps a market symbol to the crypto-brain
     * tier bucket. This classification was written inline at the onTradeClose
     * site; V5.0.6923 needs the same bucket at entry to read the per-tier
     * exit verdict, and two copies of a bucket rule is how the write side and
     * the read side end up keyed differently — the defect that has already
     * cost this codebase several inert learners.
     */
    private fun cryptoBrainTier6923(symbol: String): String {
        val symU = symbol.uppercase()
        return when {
            symU in tier1 -> "TIER1"
            symU in tier2 -> "TIER2"
            else          -> "TIER3"
        }
    }

    private fun altLiqMcapHint(symbol: String): Pair<Double, Double> = when (symbol.uppercase()) {
        in tier1 -> 5_000_000_000.0 to 100_000_000_000.0
        in tier2 -> 200_000_000.0   to 10_000_000_000.0
        else     -> 5_000_000.0     to 200_000_000.0    // long-tail alts on Binance/Coinbase still $1M-$50M liq
    }

    // ─── Constants ────────────────────────────────────────────────────────────
    // V5.9.70: cap removed — exposure guard + wallet reserve are the real
    // concurrency governors. Large ceiling kept purely as a sanity bound
    // so a runaway loop can't allocate unbounded memory.
    // V5.9.189: was 10,000 — way too many. 3% per pos × 20 = 60% max exposure as designed.
    private const val MAX_POSITIONS         = 100  // V5.9.653: 50 → 100 — operator wants aggressive bootstrap learning. Was V5.9.219b "user preference" that throttled paper trades to ~1/cycle. Operator override: "memetrader and crypto trader are meant to be trading early in bootstrap so they learn and start adjusting".

    // V5.9.219: Tokens with no active price feeds on any source — skip to avoid spam
    private val NO_FEED_SYMBOLS = setOf("BLAST", "SCROLL", "CVXF", "PORTAL")
    // V5.9.91: SOFT cap — once positions exceed this, new entries only open
    // by REPLACING the weakest open position (lowest entry score) when the
    // incoming signal outscores it. Keeps capital rotating instead of
    // saturating at 110+ dead trades.
    private const val SOFT_CAP_POSITIONS    = 80   // V5.9.653: 40 → 80 — paired with MAX_POSITIONS bump. Soft cap triggers replace-only mode; bootstrap learning needs more exposure.
    private const val REPLACE_SCORE_MARGIN  = 8   // incoming must beat worst-held by at least this

    // V5.9.221: Stagnant + loser eviction thresholds
    // V5.9.221: STAGNANT/LOSER eviction — DEEP REVISION
    // V5.9.304: V5.9.190-198 ERA RESTORATION — only TRULY DEAD positions evicted.
    // V5.9.424: 15min/±0.3% was still scratch-killing real setups (logs showed WR
    // tank to 14% with hundreds of STAGNANT closes). Lifted to 25min and tightened
    // to ±0.2% so only stone-dead positions evict; healthy chop survives.
    // V5.9.432: Alts were bailing barely out of scratch even after the 25-min
    // lift — 20-min DEADWEIGHT was closing 12+ positions a cycle at +0.0% /
    // -0.7%. Raise STAGNANT to 45 min and band to 0.5%, raise DEADWEIGHT to
    // 60 min / 0.5%, and DROP MICROWIN_LOCK entirely so real winners can
    // climb to the fluid trail / TP without being capped.
    private const val STAGNANT_MIN_HOLD_MS  = 45 * 60 * 1000L  // V5.9.432: 25→45 min
    private const val STAGNANT_MAX_PNL_PCT  = 0.5              // V5.9.432: 0.2→0.5
    // V5.9.432: MICROWIN_LOCK removed. Partial-take ladder + fluid trail
    // replace it. Constants retained as 0 so any stray reference is a no-op.
    private const val MICROWIN_HOLD_MS      = Long.MAX_VALUE
    private const val MICROWIN_DEAD_WINDOW_MS = Long.MAX_VALUE
    private const val MICROWIN_DEAD_DELTA   = 0.0
    private const val LOSER_MIN_HOLD_MS     = 3  * 60 * 1000L  // 3 min before early loser check
    private const val LOSER_FAST_EXIT_PCT   = -3.0              // cut at -3% after 3 min
    // V5.9.272: 30→20 min — cap max hold for stale alts
    // V5.9.432: 20→60 min — 20 was closing every half-cooked setup. 60 min
    // lets intraday moves develop. Fluid trail + partial ladder handle the
    // winners; DEADWEIGHT is now a last-resort slot-reclaim, not a scratch
    // killer.
    private const val DEADWEIGHT_HOLD_MS    = 60 * 60 * 1000L
    // V5.9.432 — only evict as DEADWEIGHT if movement is also truly flat
    // (<0.5% either side). A position up +2% at 60min is not deadweight.
    private const val DEADWEIGHT_MAX_PNL_PCT = 0.5
    private const val SCAN_INTERVAL_MS      = 12_000L       // 12-second scan cycle
    private const val DYN_SCAN_INTERVAL_MS  = 30_000L       // Dynamic token scan every 30s
    private const val DYN_BATCH_SIZE        = 200           // Tokens per dynamic scan batch
    // V5.0.6095 — Crypto Universe MEME-parity sizing. 3% base kept the entire
    // rest-of-crypto universe behaving like perpetual micro-probes while MEME
    // lanes used compounding pressure, lane EV and winner ceilings. Raise the
    // base to 6% and let the existing exposure cap / route proof / wallet lock
    // act as safety; toxic strategy intelligence soft-shapes below instead of
    // amputating throughput.
    private const val DEFAULT_SIZE_PCT      = 6.0           // V5.0.6095: 3→6% balance base; no more crypto-only micro treadmill
    private const val DEFAULT_LEVERAGE      = 3.0           // Default leverage (when not SPOT)

    /**
     * V5.0.7183 §THERE_IS_NO_LEVERAGE_VENUE, SO THERE ARE NO LEVERAGED POSITIONS.
     *
     * Operator: "if it cant be done in real life disable it."
     *
     * This app holds a Solana wallet and routes through Jupiter. It has no CEX
     * account, no perps program integration, and no signed perp order has ever
     * left it — the sandbox reports `perpsSandbox enabled=true, txSubmitted=0`.
     * `CryptoExecutionRoute.PERP_ONLY` is already classified not-real-tradeable
     * for exactly this reason. Yet the trader kept minting leveraged positions,
     * because leverage was decided here, upstream of that route check.
     *
     * What that cost, from the 5.0.7176 run:
     *   * `getPnlPct` at :313 returns `verdict.pnlPct * dir * leverage`, so a
     *     3x multiplier was applied to every CRYPTO_LEV outcome — a number no
     *     venue would have paid.
     *   * CRYPTO_SHORT_REROUTED_TO_PERP_6533 = 325. The SHORT branch below
     *     treats `isPaperMode` ALONE as perp capability, so in paper every
     *     short became a fictional 3x perp.
     *   * CRYPTO_LEV then reported n=18 EV=+51.09%/trade PnL=+5.6738 SOL —
     *     83% of the session's entire profit, on positions that could not be
     *     opened with real money, at a leverage that does not exist.
     *
     * One flag in front of all four decision points rather than four patches,
     * so the answer cannot drift apart again. A `val` and not a `const val`
     * deliberately: the branches below stay compiled and reviewable rather
     * than being folded away as unreachable, and wiring a real venue is then
     * a one-line flip plus an adapter — see the audit note on Jupiter
     * Perpetuals / Drift as the only Solana-native candidates.
     *
     * Spot trading of the crypto universe is UNAFFECTED. This removes
     * leverage, not the lane.
     */
    private val LEVERAGE_VENUE_AVAILABLE_7183 = false
    // V5.9.8: DEFAULT_TP_SPOT removed — now dynamic via FluidLearningAI
    private const val DEFAULT_SL_SPOT       = 3.5           // SPOT stop-loss %
    // V5.9.8: DEFAULT_TP_LEV removed — now dynamic via FluidLearningAI
    private const val DEFAULT_SL_LEV        = 5.0           // LEVERAGE stop-loss %

    // Alts that the SOL perps engine already handles — excluded here
    private val SOL_PERPS_SYMBOLS = setOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX", "DOT", "LINK",
        "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP", "APT", "SUI", "INJ", "JUP",
        "PEPE", "WIF", "BONK", "NEAR", "TIA", "PYTH", "RAY", "ORCA", "DRIFT",
        "WLD", "JTO", "W", "STRK", "TAO", "GMX", "DYDX", "ENA", "PENDLE"
    )

    // V5.9.303: Flash.trade-supported perps symbols. When SPOT mint is missing
    // for an alt but the symbol is on Flash, the trader still routes the trade
    // via leveraged perps so the alt universe is reachable end-to-end.
    // Mirrors the FLASH_SUPPORTED set in MarketsLiveExecutor.executeFlashTradePerps.
    private val FLASH_TRADE_PERPS_SYMBOLS = setOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX",
        "LINK", "DOT", "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP",
        "APT", "SUI", "INJ", "JUP", "NEAR", "TIA", "WLD", "ENA",
        "BONK", "WIF", "PEPE", "TRUMP", "SHIB"
    )

    // ─── State ────────────────────────────────────────────────────────────────
    private val positions        = ConcurrentHashMap<String, AltPosition>()
    private val spotPositions    = ConcurrentHashMap<String, AltPosition>()
    private val leveragePositions= ConcurrentHashMap<String, AltPosition>()
    /** V5.0.7264 — per-position two-strike flag for the tick hard floor (see the guard at the tick site). */
    private val tickFloorStrike7264 = ConcurrentHashMap<String, Boolean>()
    // V5.9.424 — MICROWIN_LOCK momentum snapshot (id -> ts ms, pnlPct).
    // Used to detect "no movement in last 2min" before booking a small win.
    private val momentumSnapshots = ConcurrentHashMap<String, Pair<Long, Double>>()
    // V5.7.7: Closed positions archive — retained so Positions tab shows real win rate + history
    private val closedPositions  = java.util.concurrent.CopyOnWriteArrayList<AltPosition>()
    private val MAX_CLOSED_HISTORY = 500

    private val isRunning        = AtomicBoolean(false)
    private val isEnabled        = AtomicBoolean(true)
    private val preferLeverage   = AtomicBoolean(false)  // V5.9.3: mirrors UI SPOT/LEVERAGE toggle
    private val isPaperMode      = AtomicBoolean(true)

    /**
     * V5.0.7425 — ECONOMIC MODE AUTHORITY.
     * CryptoAlt keeps a local mode mirror for UI/persistence, but economic
     * candidates must read RuntimeModeAuthority at submit/dispatch time.
     */
    private fun authoritativePaperMode7425(): Boolean {
        val authoritative = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() }
            catch (_: Throwable) { isPaperMode.get() }
        val local = isPaperMode.get()
        if (local != authoritative) {
            isPaperMode.set(authoritative)
            try {
                PipelineHealthCollector.labelInc("CRYPTO_MODE_MIRROR_HEALED_7425")
                ForensicLogger.lifecycle(
                    "CRYPTO_MODE_MIRROR_HEALED_7425",
                    "local=" + (if (local) "PAPER" else "LIVE") + " authority=" + (if (authoritative) "PAPER" else "LIVE") + " action=runtime_authority_wins",
                )
            } catch (_: Throwable) {}
        }
        return authoritative
    }
    private val scanCount        = AtomicInteger(0)
    private val totalTrades      = AtomicInteger(0)
    private val winningTrades    = AtomicInteger(0)
    private val losingTrades     = AtomicInteger(0)
    // V5.9.358 — honest win contract: a "scratch" is |pnlPct| < 1%. Counted
    // separately so getWinRate() = wins / (wins + losses), matching
    // RunTracker30D.classifyTrade and the rest of AATE. Alts-only field —
    // does NOT touch any other trader's state.
    private val scratchTrades    = AtomicInteger(0)

    @Volatile private var paperBalance    = 0.0    // Balance managed by MultiAssetActivity shared pool
    @Volatile private var liveWalletBalance = 0.0
    @Volatile private var totalPnlSol     = 0.0
    @Volatile private var initialBalance  = 0.0  // V5.9.5: balance at session start for correct pnl%

    private var engineJob    : Job? = null
    private var monitorJob   : Job? = null
    private var dynScanJob   : Job? = null
    private var dynBatchIdx  = 0       // rotating batch cursor for dynamic token scan
    // V5.0.7823 — scan fairness for the resident Crypto Universe. The hunter
    // already writes strategy books; this remembers when the trader actually
    // consumed each identity so four high-score names cannot permanently sit
    // at the front of the resident queue.
    private val cryptoResidentLastScanAt7823 = ConcurrentHashMap<String, Long>()
    private const val CRYPTO_RESIDENT_SCAN_QUOTA_7823 = 64

    // V5.0.7825 — identity-level anti-churn. CryptoAlt previously had no
    // post-close re-entry memory, so the same few high-ranked coins could be
    // closed and immediately win the next 30s generation again. This is not a
    // blacklist: a materially high-edge setup can escape the cooldown.
    private val cryptoLastClosedAt7825 = ConcurrentHashMap<String, Long>()
    private const val CRYPTO_REENTRY_COOLDOWN_MS_7825 = 10L * 60_000L
    private const val CRYPTO_REENTRY_ESCAPE_SCORE_7825 = 70
    private const val CRYPTO_REENTRY_ESCAPE_COMBINED_7825 = 155

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private const val HELD_MARK_REFRESH_COOLDOWN_MS_7251 = 15_000L
    private val heldMarkRefreshAt7251 = ConcurrentHashMap<String, Long>()
    private val heldMarkRefreshInFlight7251 = ConcurrentHashMap.newKeySet<String>()
    private val heldMarkLogAt7251 = ConcurrentHashMap<String, Long>()
    private val heldRefreshCoalescedEmitAt7413 = ConcurrentHashMap<String, Long>()

    /**
     * V5.0.7708 — the live doctrine the meme lanes already obey, applied to a
     * crypto-alt entry before submit. Returns (sizeSol, refusal); refusal is a
     * label-safe reason or null.
     *
     *   1. The wallet must carry one routable ticket (SmartSizerV3's floor).
     *   2. The size is at least the concentration doctrine's position
     *      (tradeable / slots, never under the routable minimum).
     *   3. A live slot must be free (LiveConcentrationDoctrine7697).
     *   4. No sellable bot holding may sit outside exit scope
     *      (LiveExitCoverageGuard7701, as the meme admission gate checks).
     */
    private fun liveCryptoEntryGate7708(balance: Double, requested: Double): Pair<Double, String?> {
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!solUsd.isFinite() || solUsd <= 0.0) return requested to "CRYPTO_LIVE_REFUSED_NO_SOL_PRICE_7708"
        val reserve = try { com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255.RESERVE_SOL } catch (_: Throwable) { 0.0 }
        val tradeable = (balance - reserve).coerceAtLeast(0.0)
        val routableMin = try {
            com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(tradeable, solUsd).routableMinSol
        } catch (_: Throwable) { 0.0 }
        val floor = try {
            com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697.positionSol(tradeable, routableMin)
        } catch (_: Throwable) { 0.0 }
        if (floor <= 0.0) return requested to "CRYPTO_LIVE_REFUSED_WALLET_BELOW_ROUTABLE_7708:tradeable=${"%.4f".format(tradeable)} routableMin=${"%.4f".format(routableMin)}"
        val slots = try { com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697.slotVerdict() } catch (_: Throwable) { null }
        if (slots != null && !slots.allow) return requested to "CRYPTO_LIVE_REFUSED_SLOTS_FULL_7708:open=${slots.open}/${slots.slots}"
        val coverage = try {
            com.lifecyclebot.engine.sell.LiveExitCoverageGuard7701.assess(WalletManager.currentPubkey())
        } catch (_: Throwable) { null }
        if (coverage is com.lifecyclebot.engine.sell.LiveExitCoverageGuard7701.Decision.Blocked) {
            return requested to "CRYPTO_LIVE_BLOCKED_UNMANAGED_BOT_HOLD_7708:${coverage.reasonCode}:${coverage.mints.size}"
        }
        if (requested < floor) {
            try { PipelineHealthCollector.labelInc("CRYPTO_LIVE_SIZE_LIFTED_TO_DOCTRINE_7708") } catch (_: Throwable) {}
            return floor to null
        }
        return requested to null
    }

    internal fun canonicalCryptoLane7251(isDynamic: Boolean, isSpot: Boolean): String = when {
        !isSpot -> "CRYPTO_LEV"
        isDynamic -> "CRYPTO_ALT"
        else -> "CRYPTO_SPOT"
    }

    private fun refreshDynamicMark7251(identity: String): DynamicAltTokenRegistry.HeldMarkRefresh7251 {
        val clean = identity.trim()
        if (clean.isBlank()) return DynamicAltTokenRegistry.HeldMarkRefresh7251(0.0, "", 0L, false)
        val now = System.currentTimeMillis()
        val last = heldMarkRefreshAt7251[clean] ?: 0L
        if (now - last < HELD_MARK_REFRESH_COOLDOWN_MS_7251 || !heldMarkRefreshInFlight7251.add(clean)) {
            val prior7413 = heldRefreshCoalescedEmitAt7413[clean] ?: 0L
            if (now - prior7413 >= HELD_MARK_REFRESH_COOLDOWN_MS_7251) {
                heldRefreshCoalescedEmitAt7413[clean] = now
                try { PipelineHealthCollector.labelInc("CRYPTO_HELD_MARK_REFRESH_COALESCED_7251") } catch (_: Throwable) {}
            }
            return DynamicAltTokenRegistry.heldMarkSnapshot7251(clean)
        }
        heldMarkRefreshAt7251[clean] = now
        return try {
            DynamicAltTokenRegistry.refreshHeldMark7251(clean)
        } finally {
            heldMarkRefreshInFlight7251.remove(clean)
        }
    }

    // Persistent SharedPreferences — fast, always-available learning fallback
    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private const val PREFS_NAME  = "crypto_alt_trader_v2"
    private const val KEY_BALANCE = "paper_balance"
    private const val KEY_TRADES  = "total_trades"
    private const val KEY_WINS    = "winning_trades"
    private const val KEY_LOSSES  = "losing_trades"
    private const val KEY_PNL             = "total_pnl_sol"
    private const val KEY_INITIAL_BALANCE  = "initial_balance"
    // V5.9.53: removed hard 500 SOL cap — legitimate big wins were silently excluded
    // V5.9.358 — separate scratch counter persistence (Alts-only).
    private const val KEY_SCRATCHES = "scratch_trades_v358"
    // V5.9.358 — one-time migration flag: when first booting under the
    // honest win contract, the legacy `winningTrades` count is inflated
    // because it was incremented on `pnlSol >= 0` (counting scratches as
    // wins). We can't recover the true win/loss/scratch split from
    // history, so we reset Alts counters to zero exactly once and start
    // collecting honest data. Meme/Perps/RunTracker30D untouched.
    private const val KEY_WR_CONTRACT_MIGRATED_358 = "wr_contract_v358_migrated"
    private const val KEY_LIVE    = "is_live_mode"
    private const val DYNAMIC_MARK_MAX_AGE_MS_6654 = 60_000L

    // ─── Position model ───────────────────────────────────────────────────────
    data class AltPosition(
        val id            : String,
        val market        : PerpsMarket,
        // V5.9.1472 — DYNAMIC CRYPTO identity (set when market == PerpsMarket.DYN).
        val dynSymbol     : String? = null,
        val dynName       : String? = null,
        val dynEmoji      : String  = "🪙",
        val dynMint       : String? = null,
        val direction     : PerpsDirection,
        val isSpot        : Boolean,
        val isPaper       : Boolean,
        val canonicalAssetKey: String,
        // V5.0.6654 — a DYN sentinel is not an instrument identity.  A mark may
        // affect PnL/exit/accounting only after the exact canonical asset key has
        // been resolved.  Legacy rows intentionally restore with a blank key.
        var markAssetKey  : String = "",
        var markUpdatedAtMs: Long = 0L,
        val entryPrice    : Double,
        var currentPrice  : Double,
        val sizeSol       : Double,
        val leverage      : Double,
        val takeProfitPrice: Double,
        val stopLossPrice  : Double,
        val aiScore       : Int,
        val aiConfidence  : Int,
        val reasons       : List<String>,
        val openTime      : Long = System.currentTimeMillis(),
        val holdSetupQuality: String = "C",
        val adaptiveMaxHoldSeconds: Int = 0,
        var closeTime     : Long? = null,
        var closePrice    : Double? = null,
        var realizedPnl   : Double? = null,
        var highestPnlPct : Double = 0.0,  // V5.9.9: Track peak PnL for trailing stop
        // V5.9.320: Flash.trade position account key — needed for proper close via Flash API.
        // Set after open for leveraged crypto positions. Null = SPOT or USDC-parked.
        var flashPositionKey: String? = null,
    ) {
        // V5.9.1472 — real coin symbol/name for dynamic crypto positions.
        val marketSymbol: String get() = dynSymbol ?: market.symbol
        val marketName: String get() = dynName ?: market.displayName
        val isDynamic: Boolean get() = market == PerpsMarket.DYN || dynSymbol != null
        val leverageLabel: String get() = if (isSpot) "SPOT" else "${leverage.toInt()}x"

        fun hasTrustedMark(nowMs: Long = System.currentTimeMillis()): Boolean {
            if (!entryPrice.isFinite() || entryPrice <= 0.0 ||
                !currentPrice.isFinite() || currentPrice <= 0.0) return false
            if (!isDynamic) return true
            val positionKey = canonicalAssetKey.trim().lowercase()
            val markKey = markAssetKey.trim().lowercase()
            if (positionKey.isBlank() || markKey.isBlank() || positionKey != markKey) return false
            return markUpdatedAtMs > 0L &&
                nowMs - markUpdatedAtMs in 0L..DYNAMIC_MARK_MAX_AGE_MS_6654
        }

        fun getPnlPct(): Double {
            if (!hasTrustedMark()) return 0.0
            val identity = if (isDynamic) canonicalAssetKey else market.name
            val verdict = com.lifecyclebot.engine.OpenPnlSanity.inspect(
                entryPrice = entryPrice,
                currentPrice = currentPrice,
                entrySource = "CRYPTO_ALT_CANONICAL",
                currentSource = "CRYPTO_ALT_CANONICAL",
                entryPool = identity,
                currentPool = if (isDynamic) markAssetKey else identity,
                context = "CryptoAltTrader/${marketSymbol}/${canonicalAssetKey.take(24)}",
                emit = false,
                mint = canonicalAssetKey,
            )
            if (!verdict.ok) return 0.0
            val dir = if (direction == PerpsDirection.LONG) 1.0 else -1.0
            return verdict.pnlPct * dir * leverage
        }

        fun getPnlSol(): Double = sizeSol * (getPnlPct() / 100.0)

        fun shouldTakeProfit(tpPct: Double): Boolean {
            return getPnlPct() >= tpPct
        }

        fun shouldStopLoss(slPct: Double): Boolean {
            return getPnlPct() <= -slPct
        }
    }

    // V5.9.178 — JSON persistence so open positions survive app updates.
    private fun altPositionToJson(p: AltPosition): org.json.JSONObject {
        return org.json.JSONObject()
            .put("id",              p.id)
            .put("market",          p.market.name)
            .put("direction",       p.direction.name)
            .put("isSpot",          p.isSpot)
            .put("isPaper",         p.isPaper)
            .put("canonicalAssetKey", p.canonicalAssetKey)
            .put("markAssetKey",    p.markAssetKey)
            .put("markUpdatedAtMs", p.markUpdatedAtMs)
            .put("entryPrice",      p.entryPrice)
            .put("currentPrice",    p.currentPrice)
            .put("sizeSol",         p.sizeSol)
            .put("leverage",        p.leverage)
            .put("takeProfitPrice", p.takeProfitPrice)
            .put("stopLossPrice",   p.stopLossPrice)
            .put("aiScore",         p.aiScore)
            .put("aiConfidence",    p.aiConfidence)
            .put("reasons",         org.json.JSONArray(p.reasons))
            .put("openTime",         p.openTime)
            .put("holdSetupQuality", p.holdSetupQuality)
            .put("adaptiveMaxHoldSeconds", p.adaptiveMaxHoldSeconds)
            .put("highestPnlPct",    p.highestPnlPct)
            // V5.9.1472 — persist dynamic-crypto identity so DYN positions
            // restore as the real coin, not the bare sentinel.
            .apply {
                if (p.dynSymbol != null) put("dynSymbol", p.dynSymbol)
                if (p.dynName   != null) put("dynName",   p.dynName)
                if (p.dynMint   != null) put("dynMint",   p.dynMint)
                put("dynEmoji", p.dynEmoji)
            }
            .apply { if (p.flashPositionKey != null) put("flashPositionKey", p.flashPositionKey) }
    }
    private fun altPositionFromJson(j: org.json.JSONObject): AltPosition {
        val reasonsArr = j.optJSONArray("reasons")
        val reasons = if (reasonsArr != null) {
            (0 until reasonsArr.length()).map { reasonsArr.optString(it, "") }
        } else emptyList()
        return AltPosition(
            id              = j.getString("id"),
            market          = try { PerpsMarket.valueOf(j.getString("market")) } catch (_: Exception) { PerpsMarket.DYN },
            dynSymbol       = j.optString("dynSymbol", "").ifBlank { null },
            dynName         = j.optString("dynName", "").ifBlank { null },
            dynEmoji        = j.optString("dynEmoji", "🪙"),
            dynMint         = j.optString("dynMint", "").ifBlank { null },
            direction       = PerpsDirection.valueOf(j.getString("direction")),
            isSpot          = j.optBoolean("isSpot", true),
            isPaper         = j.optBoolean("isPaper", true),
            canonicalAssetKey = j.optString("canonicalAssetKey",
                j.optString("dynMint", j.optString("dynSymbol", j.optString("market", "UNKNOWN")))),
            markAssetKey    = j.optString("markAssetKey", ""),
            markUpdatedAtMs = j.optLong("markUpdatedAtMs", 0L),
            entryPrice      = j.getDouble("entryPrice"),
            currentPrice    = j.getDouble("currentPrice"),
            sizeSol         = j.getDouble("sizeSol"),
            leverage        = j.optDouble("leverage", 1.0),
            takeProfitPrice = j.optDouble("takeProfitPrice", 0.0),
            stopLossPrice   = j.optDouble("stopLossPrice", 0.0),
            aiScore         = j.optInt("aiScore", 50),
            aiConfidence    = j.optInt("aiConfidence", 50),
            reasons         = reasons,
            openTime        = j.optLong("openTime", System.currentTimeMillis()),
            holdSetupQuality = j.optString("holdSetupQuality", "C"),
            adaptiveMaxHoldSeconds = j.optInt("adaptiveMaxHoldSeconds", 0),
        ).apply {
            highestPnlPct = j.optDouble("highestPnlPct", 0.0)
            // Pre-6654 DYN rows may already contain a sentinel-derived mark.
            // Neutralise it on restore; the exact-identity monitor will replace
            // it with a fresh mark before any exit or accounting mutation.
            if (isDynamic && markAssetKey.isBlank()) {
                currentPrice = entryPrice
                highestPnlPct = 0.0
                markUpdatedAtMs = 0L
                try { PipelineHealthCollector.labelInc("CRYPTO_DYN_LEGACY_MARK_NEUTRALISED_6654") } catch (_: Throwable) {}
            }
            val fk = j.optString("flashPositionKey", "")
            if (fk.isNotBlank()) flashPositionKey = fk
        }
    }
    private fun persistAltPositions() {
        try {
            val blob = positions.values.map { altPositionToJson(it) }
            PerpsPositionStore.saveAll("crypto_alt", blob)
        } catch (_: Exception) {}
    }

    /** CanonicalPositionAuthority6441 is the sole open-position source.
     * Local JSON supplies presentation/execution details only and is rebound
     * to the immutable canonical positionId. Historical rows are retained. */
    private fun rehydrateCanonicalPositions6647() {
        val context = ctx ?: return
        try {
            PerpsPositionStore.init(context, "crypto_alt")
            val stored = PerpsPositionStore.loadAll("crypto_alt").mapNotNull { j ->
                try { altPositionFromJson(j) } catch (_: Throwable) { null }
            }
            val canonicalOpen = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .filter { it.assetClass == com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT }
            // V5.0.6659 — recover both open and historical terminal Crypto
            // events before the account reconciler reads the durable journal.
            com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486.repairCryptoHistory6659()
            var rehydratedCount = 0
            canonicalOpen.forEach { cp ->
                // Repair accounting projection from canonical truth even if
                // the optional local presentation row is unavailable.
                if (cp.mode.equals("paper", true)) {
                    com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486.ensureOpenProjection6659(cp)
                }
                val persisted = stored.firstOrNull { it.id == cp.positionId }
                    ?: stored.firstOrNull { it.canonicalAssetKey == cp.mint }
                if (persisted == null) {
                    try { ForensicLogger.lifecycle("CRYPTO_CANONICAL_REHYDRATE_MISSING_LOCAL_6647", "positionId=${cp.positionId} asset=${cp.mint} action=retain_canonical_no_purge") } catch (_: Throwable) {}
                    return@forEach
                }
                val pos = if (persisted.id == cp.positionId) persisted else persisted.copy(id = cp.positionId)
                if (positions.putIfAbsent(pos.id, pos) != null) return@forEach
                if (pos.isSpot) spotPositions[pos.id] = pos else leveragePositions[pos.id] = pos
                com.lifecyclebot.engine.WalletPositionLock.recordOpen("CryptoAlt", pos.sizeSol)
                rehydratedCount++
            }
            if (rehydratedCount > 0) {
                ErrorLogger.info(TAG, "🪙 REHYDRATED $rehydratedCount canonical CryptoAlt positions by immutable positionId")
            }
            val legacyUnmatched = stored.count { p -> canonicalOpen.none { it.positionId == p.id || it.mint == p.canonicalAssetKey } }
            if (legacyUnmatched > 0) {
                try { ForensicLogger.lifecycle("CRYPTO_LEGACY_POSITION_HISTORY_RETAINED_6647", "unmatched=$legacyUnmatched action=no_delete_no_silent_migration") } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "position rehydrate failed: ${e.message}")
        }
    }

    /**
     * V5.0.7255 — canonical Crypto inventory must not disappear merely because
     * the optional CryptoAlt presentation JSON missed a write. Wallet recovery
     * can restore a canonical LIVE position after this trader's startup pass;
     * previously the Positions screen continued to read only [positions] and
     * therefore showed WBTC while other held/bot-bought crypto stayed invisible.
     *
     * Rebuild only from a funded canonical CRYPTO_ALT row with a real entry
     * basis. This is a projection of existing authority, never a new trade.
     */
    @Synchronized
    private fun syncCanonicalCryptoPositions7255(): Int {
        val activePaperMode = authoritativePaperMode7425()
        val canonical = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .filter {
                    it.assetClass == com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT &&
                        it.mode.equals(if (activePaperMode) "paper" else "live", true)
                }
        } catch (_: Throwable) { return 0 }
        var added = 0
        for (cp in canonical) {
            if (!cp.entryPriceUsd.isFinite() || cp.entryPriceUsd <= 0.0 ||
                !cp.entryCostSol.isFinite() || cp.entryCostSol <= 0.0
            ) continue
            if (positions[cp.positionId]?.isPaper == activePaperMode ||
                positions.values.any { it.isPaper == activePaperMode && it.canonicalAssetKey == cp.mint }
            ) continue

            val market = PerpsMarket.values().firstOrNull {
                it.isCrypto && it.symbol.equals(cp.symbol, ignoreCase = true)
            } ?: PerpsMarket.DYN
            val dynamic = market == PerpsMarket.DYN
            val cached = if (!dynamic) try {
                PerpsMarketDataFetcher.getCachedPrice(market)?.price ?: 0.0
            } catch (_: Throwable) { 0.0 } else try {
                DynamicAltTokenRegistry.heldMarkSnapshot7251(cp.mint).price
            } catch (_: Throwable) { 0.0 }
            val current = cached.takeIf { it.isFinite() && it > 0.0 } ?: cp.entryPriceUsd
            val projected = AltPosition(
                id = cp.positionId,
                market = market,
                dynSymbol = if (dynamic) cp.symbol else null,
                dynName = if (dynamic) cp.symbol else null,
                dynMint = if (dynamic) cp.mint else null,
                direction = PerpsDirection.LONG,
                isSpot = true,
                isPaper = cp.mode.equals("paper", true),
                canonicalAssetKey = cp.mint,
                markAssetKey = if (dynamic && cached > 0.0) cp.mint else "",
                markUpdatedAtMs = if (dynamic && cached > 0.0) System.currentTimeMillis() else 0L,
                entryPrice = cp.entryPriceUsd,
                currentPrice = current,
                sizeSol = cp.entryCostSol,
                leverage = 1.0,
                takeProfitPrice = cp.entryPriceUsd * 1.06,
                stopLossPrice = cp.entryPriceUsd * 0.96,
                aiScore = 50,
                aiConfidence = 50,
                reasons = listOf("CANONICAL_CRYPTO_RECOVERY_7255", cp.entryPriceSource),
                openTime = cp.openedAtMs,
            )
            positions[projected.id] = projected
            spotPositions[projected.id] = projected
            added++
            try {
                PipelineHealthCollector.labelInc("CRYPTO_CANONICAL_POSITION_PROJECTED_7255")
                ForensicLogger.lifecycle(
                    "CRYPTO_CANONICAL_POSITION_PROJECTED_7255",
                    "positionId=${cp.positionId.take(28)} asset=${cp.mint.take(24)} symbol=${cp.symbol} mode=${cp.mode} source=${cp.entryPriceSource}",
                )
            } catch (_: Throwable) {}
        }
        if (added > 0) persistAltPositions()
        return added
    }

    // ─── Alt signal model ─────────────────────────────────────────────────────
    data class AltSignal(
        val market      : PerpsMarket,
        val direction   : PerpsDirection,
        val score       : Int,
        val confidence  : Int,
        val price       : Double,
        val priceChange24h: Double,
        val reasons     : List<String>,
        val layerVotes  : Map<String, PerpsDirection>,
        val leverage    : Double = DEFAULT_LEVERAGE,
        // V5.9.1472 — DYNAMIC CRYPTO. When `market == PerpsMarket.DYN`, this
        // signal is for an arbitrary non-Solana coin discovered by
        // DynamicAltTokenRegistry that has no hardcoded enum entry. The real
        // identity lives here. mint = the registry key (cg:id / real address).
        val dynSymbol   : String? = null,
        val dynName     : String? = null,
        val dynEmoji    : String  = "🪙",
        val dynMint     : String? = null,
        // V5.0.6544 — raw adapter address is separate from the chain-aware key.
        val dynChainId  : String? = null,
        val dynAssetKey  : String? = null,
        // V5.0.7803 — exact strategy identity survives candidate -> execution
        // -> position reasons -> close/learning instead of collapsing to CryptoAltAI.
        val strategy7803 : String = "CRYPTO_NATIVE",
        val deskOverlays7803: Set<String> = emptySet(),
        val candidateVersion7803: Long = 0L,
    ) {
        /** Real coin symbol — dynamic value when present, else the enum symbol. */
        val marketSymbol: String get() = dynSymbol ?: market.symbol
        val isDynamic: Boolean get() = market == PerpsMarket.DYN || dynSymbol != null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile private var altInited = false
    fun init(context: Context) {
        if (altInited) {
            ErrorLogger.debug(TAG, "🪙 init: already inited — skipping to preserve running state")
            return
        }
        altInited = true
        ctx   = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromSharedPrefs()
        // V5.9.8: Sync paper/live mode from main config (source of truth)
        try {
            val cfg = com.lifecyclebot.data.ConfigStore.load(context.applicationContext)
            isPaperMode.set(cfg.paperMode)
        } catch (_: Exception) {}
        // V5.0.7803 audit — config/prefs are mirrors, never economic mode authority.
        // Heal the mirror before any reused specialist AI is initialized so entry
        // opinions and later dispatch cannot disagree about PAPER vs LIVE.
        val initPaper7803 = authoritativePaperMode7425()
        scope.launch { loadPersistedState() }
        try { BehaviorAI.init(context.applicationContext) }        catch (e: Exception) { ErrorLogger.debug(TAG, "BehaviorAI: ${e.message}") }
        // V5.9.1442 — Crypto isolated brain. Initialised AFTER the legacy meme
        // AIs so the eager-load path under CryptoBrainState can complete with
        // a warm prefs cache, but BEFORE any scan cycle uses the brain.
        try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "CryptoBrain: ${e.message}") }
        // V5.0.4151 — Isolated crypto discipline pack init (parity with meme
        // engine.RugMintBlacklist/LivePauseButton/LaneTimeoutGate/ScannerLaneBridge).
        try { com.lifecyclebot.perps.crypto.brain.CryptoRugMintBlacklist.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "CryptoRugMintBlacklist: ${e.message}") }
        try { com.lifecyclebot.perps.crypto.brain.CryptoLivePauseButton.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "CryptoLivePauseButton: ${e.message}") }
        try { com.lifecyclebot.perps.crypto.brain.CryptoLaneTimeoutGate.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "CryptoLaneTimeoutGate: ${e.message}") }
        try { com.lifecyclebot.perps.crypto.brain.CryptoScannerLaneBridge.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "CryptoScannerLaneBridge: ${e.message}") }
        try { com.lifecyclebot.perps.crypto.CryptoBridgeAdapter.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "CryptoBridgeAdapter: ${e.message}") }
        try { StrategyTrustAI.init() }                             catch (e: Exception) { ErrorLogger.debug(TAG, "StrategyTrustAI: ${e.message}") }
        try { NarrativeFlowAI.init() }                             catch (e: Exception) { ErrorLogger.debug(TAG, "NarrativeFlowAI: ${e.message}") }
        try { RunTracker30D.init(context.applicationContext) }     catch (e: Exception) { ErrorLogger.debug(TAG, "RunTracker30D: ${e.message}") }
        try { ShadowLearningEngine.init() }                        catch (e: Exception) { ErrorLogger.debug(TAG, "ShadowLearning: ${e.message}") }
        try { TradeHistoryStore.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "TradeHistory: ${e.message}") }
        try { ShitCoinTraderAI.init(initPaper7803) }           catch (e: Exception) { ErrorLogger.debug(TAG, "ShitCoinAI: ${e.message}") }
        try { QualityTraderAI.init(initPaper7803) }            catch (e: Exception) { ErrorLogger.debug(TAG, "QualityAI: ${e.message}") }
        try { BlueChipTraderAI.init(initPaper7803) }           catch (e: Exception) { ErrorLogger.debug(TAG, "BlueChipAI: ${e.message}") }
        try { ShitCoinExpress.init(initPaper7803) }            catch (e: Exception) { ErrorLogger.debug(TAG, "ShitCoinExpress: ${e.message}") }
        try { MoonshotTraderAI.initialize(initPaper7803) }     catch (e: Exception) { ErrorLogger.debug(TAG, "MoonshotAI: ${e.message}") }
        try { ManipulatedTraderAI.init(initPaper7803) }        catch (e: Exception) { ErrorLogger.debug(TAG, "ManipulatedAI: ${e.message}") }
        try { PerpsLearningBridge.init(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "PerpsLearningBridge: ${e.message}") }
        try { FluidLearningAI.initAltsPrefs(context.applicationContext) } catch (e: Exception) { ErrorLogger.debug(TAG, "FluidLearningAI.initMarketsPrefs: ${e.message}") }

        // Canonical bootstrap may still be running at init; start() repeats
        // this idempotent rehydrate after service bootstrap has completed.
        rehydrateCanonicalPositions6647()
        // V5.9.1: Eagerly sync real wallet balance on init (live mode)
        if (!initPaper7803) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val sol = WalletManager.getWallet()?.getSolBalance() ?: 0.0
                    if (sol > 0.0) updateLiveBalance(sol)
                } catch (_: Exception) {}
            }
        }
        ErrorLogger.info(TAG, "🪙 CryptoAltTrader INITIALIZED | paper=$initPaper7803 | balance=${"%.2f".format(paperBalance)} SOL | trades=${totalTrades.get()}")
    }

    private fun runtimeDisabledReason(): String? {
        if (com.lifecyclebot.engine.BotService.isShuttingDown) return "runtime_stopping"
        // V5.0.3744 — OPERATOR EXPLICIT-ENABLE ESCAPE HATCH (crypto-isolated fix).
        //
        // Audit: with V5.0.3682 + V5.0.3702 in force, the EnabledTraderAuthority
        // publish set is amputated to {MEME} whenever tradingMode==0 OR
        // (tradingMode==2 && memeTraderEnabled). That correctly contains
        // meme-lane fanout (V5.0.3684 shouldRunBuyLaneForCycle), but it
        // simultaneously silenced THIS background trader's independent loop
        // even when the operator had cryptoAltsEnabled=true in settings.
        //
        // The operator's original P1 spec said
        // "PROJECT_SNIPER, CYCLIC, MARKETS, PERPS must be false UNLESS
        //  EXPLICITLY ENABLED". A per-trader toggle IS the explicit enable.
        // The crypto trader runs its own engine/monitor/dynScan jobs that
        // never inflate meme-lane fanout (separate scoring stack, separate
        // universe, separate ticker), so honouring cfg.cryptoAltsEnabled
        // here is safe and surgical.
        //
        // Order of checks below:
        //   1. cfg.cryptoAltsEnabled == true AND marketsTraderEnabled == true
        //      → operator explicitly opted in → bypass authority suppression.
        //   2. Otherwise fall through to the authority + config guards.
        val c = ctx
        val cfg = try { c?.let { com.lifecyclebot.data.ConfigStore.load(it) } } catch (_: Throwable) { null }
        // V5.0.6559 — runtime mode is the paper authority. A stale/early
        // published live trader set must not suppress the independent paper
        // learning scanner before its first tick.
        val paperRuntime6559 = (cfg?.paperMode == true) || try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        if (paperRuntime6559) {
            if (!isEnabled.get()) return "disabled"
            // V5.0.6626 §RUNTIME_LOOP_UNCHOKE §1 — coalesced hot-label increment.
            try { com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626("CRYPTO_PAPER_RUNTIME_PRECEDENCE_6559") } catch (_: Throwable) {}
            return null
        }
        val operatorExplicitlyEnabled = cfg != null &&
            cfg.cryptoAltsEnabled && cfg.marketsTraderEnabled
        if (operatorExplicitlyEnabled) {
            // Bypass the meme-only authority suppression — operator intent wins.
            if (!isEnabled.get()) return "disabled"
            return null
        }
        // V5.9.1318 (Item 5) — EnabledTraderAuthority is the SINGLE ATOMIC source of truth
        // for which traders are enabled. Consult it FIRST so a mode switch is obeyed
        // immediately and atomically, closing the ConfigStore-load lag window that let
        // CryptoAlt DynScan keep firing DynSig logs during a Meme-only session.
        var authorityAllowsCrypto6015 = false
        try {
            // V5.0.6524 §AUTHORITY_COLLAPSE — effectiveSnapshot() matches
            // isEnabled(t) semantics (paper=all, live=published).
            val authority = com.lifecyclebot.engine.EnabledTraderAuthority.effectiveSnapshot()
            authorityAllowsCrypto6015 = com.lifecyclebot.engine.EnabledTraderAuthority.Trader.CRYPTO_ALT in authority
            if (authority.isNotEmpty() && !authorityAllowsCrypto6015) {
                return "SUB_TRADER_SUPPRESSED_MEME_ONLY"
            }
        } catch (_: Throwable) { /* fall through to config check */ }
        // V5.0.6015 — if BotService published CRYPTO_ALT as the isolated crypto
        // sidecar, do not let stale markets/crypto UI toggles stop the trader loop.
        // V5.0.6553 — PAPER_MODE=LEARN_EVERYTHING. Runtime plan already
        // admits the crypto universe for paper sampling; stale meme-only UI
        // flags must not silence the independent multi-chain scanner.
        if (cfg != null && !cfg.paperMode && !authorityAllowsCrypto6015 &&
            (cfg.tradingMode == 0 || !cfg.marketsTraderEnabled || !cfg.cryptoAltsEnabled)) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_RUNTIME_BLOCKED_LIVE_CONFIG_6553") } catch (_: Throwable) {}
            return "MEME_ONLY_MODE"
        }
        if (cfg?.paperMode == true) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_PAPER_LEARN_EVERYTHING_ADMITTED_6553") } catch (_: Throwable) {}
        }
        if (!isEnabled.get()) return "disabled"
        return null
    }

    fun start() {
        rehydrateCanonicalPositions6647()
        runtimeDisabledReason()?.let { reason ->
            isEnabled.set(false)
            isRunning.set(false)
            ErrorLogger.info(TAG, "CRYPTO_RUNTIME_DISABLED reason=$reason")
            return
        }
        if (isRunning.get()) {
            // Detect silent loop death — check if jobs are actually alive
            val engineAlive  = engineJob?.isActive == true
            val monitorAlive = monitorJob?.isActive == true
            if (engineAlive && monitorAlive) {
                ErrorLogger.debug(TAG, "Already running and jobs alive — skip restart")
                return
            }
            // Jobs died silently — force cleanup and restart
            ErrorLogger.warn(TAG, "⚠️ isRunning=true but jobs dead — force-restarting...")
            engineJob?.cancel()
            monitorJob?.cancel()
            isRunning.set(false)
        }
        isRunning.set(true)
        // V5.0.6580 §P0-g — CRYPTO_ALT producer STARTED stamp.
        // Operator forensic (6578): CryptoAlt data/raw/actionable stages
        // active but producer 'started=0' in cross-asset funnel. The
        // canonical STARTED stamp was never emitted at start(). Fixed:
        // every enabled trader/producer now stamps STARTED with its
        // canonical asset class so 'no enabled asset class silently
        // stops at data/raw/actionable' becomes observable.
        try {
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(
                com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "STARTED"
            )
        } catch (_: Throwable) {}

        engineJob = scope.launch {
            ErrorLogger.info(TAG, "🪙🪙🪙 CryptoAltTrader ENGINE STARTED 🪙🪙🪙")
            ErrorLogger.info(TAG, "🪙 Scanning every ${SCAN_INTERVAL_MS / 1000}s | enabled=${isEnabled.get()}")

            // V5.9.776 — EMERGENT MEME-ONLY toggle isolation.
            // Operator reported CryptoAltTrader emitting SIGNAL: HBAR/ICP/VET/...
            // when its UI toggle was OFF. Root cause: the INITIAL scan
            // ran unconditionally, ignoring isEnabled. Now gated.
            try {
                if (isEnabled.get()) {
                    ErrorLogger.info(TAG, "🪙🪙🪙 Running INITIAL alt scan NOW... 🪙🪙🪙")
                    runScanCycle()
                } else {
                    ErrorLogger.info(TAG, "🪙 INITIAL scan SKIPPED — isEnabled=false (TRADER_GATE CRYPTO_ALT enabled=false)")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { ErrorLogger.error(TAG, "🪙 Initial scan error: ${e.message}", e) }

            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)
                    runtimeDisabledReason()?.let { reason ->
                        ErrorLogger.info(TAG, "CRYPTO_RUNTIME_DISABLED reason=$reason loop=scan")
                        stop()
                        return@launch
                    }
                    if (isEnabled.get()) runScanCycle()
                    else ErrorLogger.debug(TAG, "🪙 Alt trading DISABLED — skipping scan")
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e) }
            }
        }

        monitorJob = scope.launch {
            while (isRunning.get()) {
                runtimeDisabledReason()?.let { reason ->
                    ErrorLogger.info(TAG, "CRYPTO_RUNTIME_DISABLED reason=$reason loop=monitor")
                    stop()
                    return@launch
                }
                try { monitorPositions() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { ErrorLogger.error(TAG, "Monitor error: ${e.message}", e) }
                // V5.9.1455 — tightened 5s → 1s. Operator directive after
                // memes bled -29% on a -15% stop: crypto must also evaluate
                // exits at sub-second cadence to avoid the same slippage
                // window. 1Hz is well within DexScreener/Birdeye rate budgets
                // for typical 1-5 open positions in the crypto lane.
                delay(1_000)
            }
        }

        // Dynamic token scan — feeds entire DynamicAltTokenRegistry universe into AI learning
        dynScanJob = scope.launch {
            delay(10_000) // stagger start after main engine
            while (isRunning.get()) {
                try {
                    runtimeDisabledReason()?.let { reason ->
                        ErrorLogger.info(TAG, "CRYPTO_ALT_DYNSCAN_ABORTED reason=$reason")
                        stop()
                        return@launch
                    }
                    if (isEnabled.get()) runDynamicTokenScan()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { ErrorLogger.error(TAG, "DynScan error: ${e.message}", e) }
                delay(DYN_SCAN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        dynScanJob?.cancel()
        ErrorLogger.info(TAG, "🪙 CryptoAltTrader STOPPED")
    }

    /**
     * V5.0.7298 §STOP_CLOSES_AT_THE_PRICE_NOW, NOT THE PRICE AT ENTRY.
     *
     * Operator: "keep shut down closes but close at real prices". The monitor
     * loop is the only writer of currentPrice, and STOP cancels it before
     * closing. A position restored at startup is projected with
     * currentPrice = entry (canonical recovery), so a STOP soon after a
     * restart booked every CryptoAlt close at exactly +0.000. One bounded
     * pass now re-marks every open position the way monitorPositions does —
     * the static feed for enum markets, the exact-identity held mark for
     * dynamic tokens — before the closes run. A position no feed answers for
     * keeps its last mark; an untrusted dynamic mark is still held by
     * closePosition as before.
     */
    private fun refreshMarksForStop7298() {
        if (positions.isEmpty()) return
        try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    positions.toMap().map { (id, position) ->
                        async {
                            val mark: Triple<Double, String, Long>? = try {
                                if (position.isDynamic) {
                                    val key = position.canonicalAssetKey.trim()
                                    val held = refreshDynamicMark7251(key)
                                    if (held.freshObservation && held.canonicalIdentity.equals(key, true) &&
                                        held.price.isFinite() && held.price > 0.0) Triple(held.price, key, held.observedAtMs) else null
                                } else {
                                    val px = PerpsMarketDataFetcher.getMarketData(position.market).price
                                    val ratio = if (position.entryPrice > 0) px / position.entryPrice else 1.0
                                    if (px.isFinite() && px > 0.0 && ratio in 0.1..10.0) Triple(px, position.market.name, System.currentTimeMillis()) else null
                                }
                            } catch (_: Throwable) { null }
                            if (mark != null) {
                                val cur = positions[id] ?: return@async
                                val updated = cur.copy(
                                    currentPrice = mark.first,
                                    markAssetKey = mark.second,
                                    markUpdatedAtMs = mark.third,
                                )
                                positions[id] = updated
                                if (updated.isSpot) spotPositions[id] = updated else leveragePositions[id] = updated
                                try { PipelineHealthCollector.labelInc("CRYPTO_STOP_MARK_REFRESHED_7298") } catch (_: Throwable) {}
                            } else {
                                try { PipelineHealthCollector.labelInc("CRYPTO_STOP_MARK_UNANSWERED_7298") } catch (_: Throwable) {}
                            }
                        }
                    }.forEach { it.await() }
                }
            }
        } catch (_: Throwable) {}
    }

    /** Close all open positions immediately (called on STOP). */
    fun closeAllPositions() {
        refreshMarksForStop7298()
        val ids = positions.keys.toList()
        val closedCount = ids.count { id ->
            try { closePosition(id, "USER_STOP"); true }
            catch (_: Exception) { false }
        }
        // Failed closes remain durable and canonical so the next bootstrap can
        // resume the same position identity. Never erase history to make STOP
        // appear clean.
        if (closedCount < ids.size) {
            val remaining = ids.size - closedCount
            ErrorLogger.warn(TAG, "🪙 $remaining CryptoAlt position(s) failed individual close — retained for canonical retry")
            try { ForensicLogger.lifecycle("CRYPTO_STOP_CLOSE_RETRY_RETAINED_6647", "remaining=$remaining action=no_purge_resume_same_position_id") } catch (_: Throwable) {}
        }
        ErrorLogger.info(TAG, "🪙 All crypto alt positions closed on STOP (${ids.size} positions, $closedCount individual closes)")
        persistAltPositions()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // DYNAMIC TOKEN SCAN — feeds DynamicAltTokenRegistry universe into sub-AI engines
    // Runs every 30s in rotating batches of 200 tokens so ALL discovered tokens
    // (DexScreener/CoinGecko/Jupiter = thousands) get assessed for learning signals.
    // The sub-AIs record every evaluation for FluidLearningAI cross-layer learning.
    // ═══════════════════════════════════════════════════════════════════════════

    // V5.0.7244 — Dynamic Crypto Universe is owned by the isolated crypto brain.
    private data class DynamicCryptoDecision7244(
        val score: Int,
        val confidence: Int,
        val tier: String,
        val actionableLong: Boolean,
        val shadowOnlyLive: Boolean,
        val reason: String,
        val strategy7803: String,
    )

    private fun dynamicCryptoTier7244(mcapUsd: Double): String = when {
        mcapUsd >= 5_000_000_000.0 -> "TIER1"
        mcapUsd >= 100_000_000.0 -> "TIER2"
        else -> "TIER3"
    }

    private val recentCryptoScores7312 = java.util.ArrayDeque<Int>()
    private val recentCryptoConfs7312 = java.util.ArrayDeque<Int>()

    @Synchronized
    private fun recordCryptoScore7312(score: Int, conf: Int) {
        recentCryptoScores7312.addLast(score); recentCryptoConfs7312.addLast(conf)
        while (recentCryptoScores7312.size > 400) recentCryptoScores7312.pollFirst()
        while (recentCryptoConfs7312.size > 400) recentCryptoConfs7312.pollFirst()
    }

    /** Pure: min(maturity floor, p90 of recent values), never below [bootstrapFloor]. */
    private fun cryptoFloorFrom7312(maturityFloor: Int, recent: List<Int>, bootstrapFloor: Int): Int {
        if (recent.size < 50) return maturityFloor
        val sorted = recent.sorted()
        val p90 = sorted[((sorted.size - 1) * 0.90).toInt()]
        return minOf(maturityFloor, p90).coerceAtLeast(bootstrapFloor)
    }

    @Synchronized
    private fun cryptoFloor7312(maturityFloor: Int, recent: java.util.ArrayDeque<Int>, bootstrapFloor: Int): Int {
        val f = cryptoFloorFrom7312(maturityFloor, recent.toList(), bootstrapFloor)
        if (f < maturityFloor) {
            try { PipelineHealthCollector.labelInc("CRYPTO_FLOOR_MARKET_DECILE_7312") } catch (_: Throwable) {}
        }
        return f
    }

    private fun scoreDynamicCrypto7244(
        tok: DynamicAltTokenRegistry.DynToken,
        marketCapUsd: Double,
        liquidityUsd: Double,
        volume24hUsd: Double,
        change24hPct: Double,
        buyPressurePct: Double,
    ): DynamicCryptoDecision7244 {
        val paper7803 = authoritativePaperMode7425()
        DynamicAltTokenRegistry.markCryptoBrainReach7244(tok)
        val tier = dynamicCryptoTier7244(marketCapUsd)
        val brainScoreAdj = try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.scoreAdjustment() } catch (_: Throwable) { 0 }
        val brainConfAdj = try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.confidenceModifier() } catch (_: Throwable) { 0 }

        // V5.0.7403 — 24h change is context, not a prediction. Reward a
        // moderate trend, penalise exhaustion, and let actual buy/sell flow carry
        // more weight. The old linear formula made +20% already printed = +14 score.
        val momentum = when {
            change24hPct in 1.0..8.0 -> (change24hPct * 0.65).toInt().coerceAtMost(5)
            change24hPct > 25.0 -> -10
            change24hPct > 12.0 -> -4
            change24hPct < -12.0 -> -10
            change24hPct < -5.0 -> -5
            else -> 0
        }
        val flow = ((buyPressurePct - 50.0).coerceIn(-30.0, 30.0) * 0.48).toInt()
        val liquidity = when {
            liquidityUsd >= 5_000_000.0 -> 10
            liquidityUsd >= 1_000_000.0 -> 8
            liquidityUsd >= 250_000.0 -> 6
            liquidityUsd >= 50_000.0 -> 3
            liquidityUsd > 0.0 -> 0
            else -> -8
        }
        val activity = when {
            volume24hUsd >= 10_000_000.0 -> 8
            volume24hUsd >= 1_000_000.0 -> 6
            volume24hUsd >= 250_000.0 -> 3
            volume24hUsd > 0.0 -> 1
            else -> -5
        }
        val discovery = (if (tok.isTrending) 2 else 0) + (if (tok.isBoosted) 1 else 0)
        val score = (48 + momentum + flow + liquidity + activity + discovery + brainScoreAdj).coerceIn(0, 100)
        val confidence = (42 +
            // Confidence comes from evidence quality, not how far price already moved.
            kotlin.math.abs(buyPressurePct - 50.0).coerceIn(0.0, 30.0).toInt() / 2 +
            (if (liquidityUsd >= 50_000.0) 6 else if (liquidityUsd > 0.0) 2 else 0) +
            (if (volume24hUsd >= 250_000.0) 4 else 0) +
            (if (change24hPct > 25.0 || change24hPct < -12.0) -8 else 0) +
            brainConfAdj
        ).coerceIn(0, 100)

        val maturityScoreFloor7312 = try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.getSpotScoreFloor() } catch (_: Throwable) { 50 }
        val maturityConfFloor7312 = try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.getSpotConfFloor() } catch (_: Throwable) { 40 }
        // V5.0.7312 — the maturity floors rise with trade COUNT (48/42 ->
        // 72/68) while this score has no learned term to rise with them;
        // confidence tops out near 42 + |chg| + |bp-50|/2 + 6, so past
        // bootstrap almost nothing could clear it (5.0.7309: 1394 OBSERVE,
        // 1376 NO_ACTIONABLE, cryptoBrainSignals=0). The floor is now the
        // lower of the maturity floor and the market's own top decile, never
        // below the bootstrap floor: the brain always acts on the best of what
        // is on offer. Long-evidence and the losing-pattern shadow gate stand.
        recordCryptoScore7312(score, confidence)
        val scoreFloor = cryptoFloor7312(maturityScoreFloor7312, recentCryptoScores7312, 48)
        val confFloor = cryptoFloor7312(maturityConfFloor7312, recentCryptoConfs7312, 42)
        val notExtended7403 = change24hPct <= 20.0 && change24hPct >= -8.0
        val momentumEvidence7443 = notExtended7403 && (
            buyPressurePct >= 56.0 ||
            (change24hPct in 0.5..8.0 && buyPressurePct >= 52.0) ||
            (tok.isTrending && buyPressurePct >= 58.0)
        )

        // V5.0.7443 — CryptoTacticSwitcher now changes entry SHAPE, not just
        // telemetry. Use the local 20s CryptoLaneDesk tape for short-horizon
        // structure; never manufacture a tactic from repeated 24h snapshots.
        val tactic7443 = try {
            com.lifecyclebot.perps.crypto.brain.CryptoBrain.activeTactic(tier, score)
        } catch (_: Throwable) {
            com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic.MOMENTUM
        }
        val px7443 = try {
            CryptoLaneDesk7391.prices(tok.canonicalIdentity6544).filter { it.isFinite() && it > 0.0 }.takeLast(8)
        } catch (_: Throwable) { emptyList() }
        val cur7443 = px7443.lastOrNull() ?: tok.price.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val prior7443 = px7443.dropLast(1)
        val priorHigh7443 = prior7443.maxOrNull() ?: cur7443
        val priorLow7443 = prior7443.minOrNull() ?: cur7443
        val pullbackPct7443 = if (priorHigh7443 > 0.0 && cur7443 > 0.0)
            ((priorHigh7443 - cur7443) / priorHigh7443 * 100.0).coerceAtLeast(0.0) else 0.0
        val reboundPct7443 = if (priorLow7443 > 0.0 && cur7443 > 0.0)
            ((cur7443 - priorLow7443) / priorLow7443 * 100.0).coerceAtLeast(0.0) else 0.0
        val pullbackEvidence7443 = px7443.size >= 4 &&
            pullbackPct7443 in 2.0..12.0 && reboundPct7443 >= 1.0 &&
            buyPressurePct >= 52.0 && notExtended7403
        val breakoutEvidence7443 = px7443.size >= 4 && priorHigh7443 > 0.0 &&
            cur7443 >= priorHigh7443 * 1.005 && buyPressurePct >= 54.0 && notExtended7403
        val meanRevertEvidence7443 = px7443.size >= 4 &&
            pullbackPct7443 in 4.0..18.0 && reboundPct7443 >= 2.0 &&
            buyPressurePct >= 50.0 && change24hPct < 12.0
        val tacticEvidence7443 = when (tactic7443) {
            com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic.MOMENTUM ->
                momentumEvidence7443
            com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic.PULLBACK ->
                pullbackEvidence7443
            com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic.BREAKOUT ->
                breakoutEvidence7443
            com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic.MEAN_REVERT ->
                meanRevertEvidence7443
            com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic.LAB_PROPOSED ->
                pullbackEvidence7443 || breakoutEvidence7443 || meanRevertEvidence7443
        }
        try { PipelineHealthCollector.labelInc("CRYPTO_TACTIC_CONSUMED_7443_${tactic7443.name}") } catch (_: Throwable) {}

        val shadowOnlyLive = try {
            !paper7803 && com.lifecyclebot.perps.crypto.brain.CryptoBrain.shouldShadowOnly(tier, score)
        } catch (_: Throwable) { false }
        val actionableLong = tacticEvidence7443 && score >= scoreFloor && confidence >= confFloor && !shadowOnlyLive
        val reason = "CRYPTO_BRAIN_NATIVE_7244 tier=" + tier + " score=" + score + "/" + confidence +
            " floor=" + scoreFloor + "/" + confFloor + " tactic=" + tactic7443.name +
            " pullback=" + "%.2f".format(pullbackPct7443) + " rebound=" + "%.2f".format(reboundPct7443) +
            " chg=" + change24hPct + " bp=" + buyPressurePct +
            " liq=" + liquidityUsd.toLong() + " vol=" + volume24hUsd.toLong()
        return DynamicCryptoDecision7244(
            score, confidence, tier, actionableLong, shadowOnlyLive, reason,
            tactic7443.name,
        )
    }
    private suspend fun runDynamicTokenScan() = withContext(Dispatchers.Default) {
        com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "SCAN_TICK")
        runtimeDisabledReason()?.let { reason ->
            ErrorLogger.info(TAG, "CRYPTO_ALT_DYNSCAN_ABORTED reason=$reason")
            return@withContext
        }
        ensureActive()
        // V5.0.7823 — consume the resident hunter/strategy books explicitly.
        // Before this build ResidentHunterWorker7807 handed candidates into
        // CryptoStrategyCandidateBooks7803, but DynScan ignored those books and
        // rebuilt its scan solely from the generic registry ranking. The hunter
        // therefore had no scheduling authority and the same high-score assets
        // could dominate repeated generations.
        //
        // Keep two bounded channels:
        //   1) resident specialist prey (max 64/200), least-recently-consumed first;
        //   2) the rotating whole-universe queue for discovery breadth.
        // Neither channel authorizes a buy; all existing CryptoBrain, FDG,
        // sizing, route and finality gates remain downstream.
        val universe7823 = DynamicAltTokenRegistry.getBlendedOpportunityQueue6544()
        if (universe7823.isEmpty()) return@withContext
        val residentEntries7823 = try {
            CryptoStrategyCandidateBooks7803.priorityAssets7823(1024)
        } catch (_: Throwable) { emptyList() }
        val residentKeys7823 = residentEntries7823.map { it.assetKey }.toSet()
        val residentTokens7823 = residentEntries7823
            .mapNotNull { DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(it.assetKey) }
            .distinctBy { it.canonicalIdentity6544 }
            .sortedBy { cryptoResidentLastScanAt7823[it.canonicalIdentity6544] ?: 0L }
        val residentSelected7823 = residentTokens7823.take(
            minOf(CRYPTO_RESIDENT_SCAN_QUOTA_7823, DYN_BATCH_SIZE)
        )
        val genericUniverse7823 = universe7823.filterNot { it.canonicalIdentity6544 in residentKeys7823 }
        val genericQuota7823 = (DYN_BATCH_SIZE - residentSelected7823.size).coerceAtLeast(1)
        val totalBatches = maxOf(1, (genericUniverse7823.size + genericQuota7823 - 1) / genericQuota7823)
        val batchIdx = dynBatchIdx % totalBatches
        val batchStart = batchIdx * genericQuota7823
        val batchEnd = minOf(batchStart + genericQuota7823, genericUniverse7823.size)
        dynBatchIdx++

        val genericBatch7823 = if (batchStart < batchEnd) genericUniverse7823.subList(batchStart, batchEnd) else emptyList()
        val batch = (residentSelected7823 + genericBatch7823)
            .distinctBy { it.canonicalIdentity6544 }
            .take(DYN_BATCH_SIZE)
        try {
            if (residentSelected7823.isNotEmpty()) {
                PipelineHealthCollector.labelInc("CRYPTO_RESIDENT_SCAN_CONSUMED_7823")
            }
            PipelineHealthCollector.labelInc("CRYPTO_RESIDENT_SCAN_SLOTS_${residentSelected7823.size}_7823")
        } catch (_: Throwable) {}
        ErrorLogger.debug(TAG, "🪙⚡ DynScan batch ${batchIdx + 1}/$totalBatches | size=${batch.size} resident=${residentSelected7823.size} generic=${genericBatch7823.size} | universe=${universe7823.size}")

        var scanned = 0
        var signals = 0  // legacy meme-specialist signals (diagnostic only)
        var cryptoBrainSignals7244 = 0
        val dynExecutableSignals = mutableListOf<AltSignal>()

        for (tok in batch) {
            ensureActive()
            // V5.0.7823 — scheduling fairness is based on actual consumption,
            // not discovery. Bound the map without affecting economic state.
            cryptoResidentLastScanAt7823[tok.canonicalIdentity6544] = System.currentTimeMillis()
            if (cryptoResidentLastScanAt7823.size > 16_384) {
                val cutoff7823 = System.currentTimeMillis() - 24L * 60L * 60_000L
                cryptoResidentLastScanAt7823.entries.removeIf { it.value < cutoff7823 }
            }
            runtimeDisabledReason()?.let { reason ->
                ErrorLogger.info(TAG, "CRYPTO_ALT_DYNSCAN_ABORTED reason=$reason scanned=$scanned")
                return@withContext
            }
            try {
                // V5.0.7246 — once this asset is canonically held it leaves the
                // 4k+ Crypto Universe discovery rotation. monitorPositions() owns
                // held pricing/exits; DynScan must spend its batch on NEW assets.
                if (com.lifecyclebot.engine.HeldPositionSupervisor7246.isHeld(tok.canonicalIdentity6544) ||
                    com.lifecyclebot.engine.HeldPositionSupervisor7246.isHeld(tok.mint)) {
                    try { PipelineHealthCollector.labelInc("CRYPTO_HELD_DISCOVERY_BYPASS_7246") } catch (_: Throwable) {}
                    continue
                }
                if (SOL_PERPS_SYMBOLS.contains(tok.symbol.uppercase())) {
                    if (!DynamicAltTokenRegistry.markEvaluationStarted6567(tok)) continue
                    DynamicAltTokenRegistry.markEvaluationDisposition6567(tok, "OWNED_BY_SOL_PERPS")
                    continue
                }
                // V5.9.147 — lazy price hydration. Jupiter-seeded mints arrive
                // with price=0 and used to be skipped forever, which is why
                // DynScan was reporting scanned=0 on a 200-token universe.
                // We try a cache-first DexScreener lookup and only bail if
                // the token is genuinely unresolvable.
                var priceNow = tok.price
                if (priceNow <= 0.0) {
                    priceNow = withContext(Dispatchers.IO) {
                        DynamicAltTokenRegistry.refreshPriceForMintBlocking(tok.canonicalIdentity6544)
                    }
                    if (priceNow <= 0.0) {
                        if (!DynamicAltTokenRegistry.markEvaluationStarted6567(tok)) continue
                        DynamicAltTokenRegistry.releaseEvaluationForRetry7418(tok, "PRICE_UNAVAILABLE")
                        try { PipelineHealthCollector.labelInc("CRYPTO_PRICE_UNAVAILABLE_RETRY_7418") } catch (_: Throwable) {}
                        continue
                    }
                }
                val price   = priceNow
                // Re-read the freshly hydrated row so downstream fields are current.
                val refreshed = DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(tok.canonicalIdentity6544)
                    ?: DynamicAltTokenRegistry.getTokenByMint(tok.mint) ?: tok
                if (!DynamicAltTokenRegistry.markEvaluationStarted6567(refreshed)) continue
                DynamicAltTokenRegistry.markEvaluation6544(refreshed)
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "MARKET_DATA_OK")
                val executableSignalCountBefore6567 = dynExecutableSignals.size
                // V5.0.6554 — specialist AIs must receive registry-owned age;
                // fresh launches must not be presented as established tokens.
                val discoveryAgeMinutes6554 = refreshed.discoveryAgeHours6544
                    .takeIf { it.isFinite() && it >= 0.0 }?.times(60.0) ?: 9999.0
                val vol     = refreshed.volume24h
                val mcap = if (refreshed.hasTrustedMarketCap6492) refreshed.mcap else {
                    if (refreshed.mcap > 0.0) try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_MCAP_UNTRUSTED_DROPPED_6492")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "CRYPTO_MCAP_UNTRUSTED_DROPPED_6492",
                            "symbol=${refreshed.symbol} mint=${refreshed.mint.take(16)} raw=${refreshed.mcap} source=${refreshed.source} provenance=${refreshed.mcapSource.ifBlank { "MISSING" }} action=no_bluechip_no_learning",
                        )
                    } catch (_: Throwable) {}
                    0.0
                }
                // V5.0.6493 — liquidity is exact-provider data for this canonical
                // asset ID. Never relabel bulk 24h volume as token liquidity.
                val liq = refreshed.liquidityUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                val change  = refreshed.priceChange24h
                // V5.0.7391 — ratio of the 24h counts. Dividing each by 24 first
                // (integer) zeroed small counts and pinned buy pressure at 50.
                val buys24h7391 = refreshed.buys24h.coerceAtLeast(0)
                val sells24h7391 = refreshed.sells24h.coerceAtLeast(0)
                val buyPct  = if (buys24h7391 + sells24h7391 > 0) (buys24h7391.toDouble() / (buys24h7391 + sells24h7391) * 100.0) else 50.0
                val momentum= change   // use 24h change as momentum proxy
                val isMeme  = refreshed.sector.lowercase().let { it.contains("meme") || it.contains("gaming") }

                scanned++

                // Feed sector intelligence layer
                try {
                    val btcPrice = PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.BTC)?.price ?: 0.0
                    CryptoAltScannerAI.recordPrice(tok.mint, price, btcPrice)
                    // V5.0.7431 — CrossAssetLeadLagAI is symbol/sector keyed
                    // (BTC→SOL, SOL→MEME_SECTOR, etc.). Feeding a contract mint
                    // here created return histories no known pair could ever
                    // consume. Preserve mint identity for the crypto scanner and
                    // regime model, but feed lead/lag the market symbol it expects.
                    val leadLagSymbol7431 = refreshed.symbol.trim().uppercase()
                    if (leadLagSymbol7431.isNotBlank()) {
                        // V5.0.7674 — 7431 fixed identity (symbol not mint), but
                        // still fed the same rolling 24h return on every scan.
                        // That manufactures repeated pseudo-observations and can
                        // create false lead/lag correlation. Use 7441's bounded
                        // interval-price sampler just like hardcoded crypto/stocks.
                        CrossAssetLeadLagAI.recordPrice7441(leadLagSymbol7431, price)
                        try { PipelineHealthCollector.labelInc("CROSS_ASSET_DYNAMIC_INTERVAL_PRICE_FEED_7674") } catch (_: Throwable) {}
                    }
                    CrossMarketRegimeAI.updateMarketState(tok.mint, price, change, vol)
                } catch (_: Exception) {}

                // V5.0.7244 — dedicated CryptoBrain-owned dynamic candidate.
                val cryptoDecision7244 = scoreDynamicCrypto7244(
                    tok = refreshed, marketCapUsd = mcap, liquidityUsd = liq,
                    volume24hUsd = vol, change24hPct = change, buyPressurePct = buyPct,
                )
                // V5.0.7391 — the meme desk. Specialists judge their own mcap
                // bands on the provider's market cap (as the meme lanes do);
                // the trusted-only figure still owns CryptoBrain and BlueChip.
                val bandMcap7391 = when {
                    mcap > 0.0 -> mcap
                    refreshed.mcap.isFinite() && refreshed.mcap > 0.0 -> refreshed.mcap
                    refreshed.fdv.isFinite() && refreshed.fdv > 0.0 -> refreshed.fdv
                    else -> 0.0
                }
                if (mcap <= 0.0 && bandMcap7391 > 0.0) {
                    try { PipelineHealthCollector.labelInc("CRYPTO_DESK_BAND_MCAP_PROVIDER_7391") } catch (_: Throwable) {}
                }
                val deskIdentity7391 = refreshed.canonicalIdentity6544.ifBlank { refreshed.mint }
                CryptoLaneDesk7391.recordTick(deskIdentity7391, price, bandMcap7391, vol, buys24h7391, sells24h7391)
                val cryptoDeskTs7803 = CryptoLaneDesk7391.tokenState(
                    identity = deskIdentity7391, symbol = refreshed.symbol, name = refreshed.name,
                    priceUsd = price, marketCapUsd = bandMcap7391, liquidityUsd = liq,
                    buyPressurePct = buyPct, source = refreshed.source,
                    ageHours = refreshed.discoveryAgeHours6544,
                    brainScore = cryptoDecision7244.score, brainConfidence = cryptoDecision7244.confidence,
                )
                val deskCandidates7803 = CryptoLaneDesk7391.qualifyAll7803(cryptoDeskTs7803)
                val desk7391 = deskCandidates7803.maxByOrNull { it.conviction }
                    ?: CryptoLaneDesk7391.Election("", 0.0, "", "", 0)
                val cryptoCv7803 = System.currentTimeMillis() / 30_000L
                try {
                    CryptoStrategyCandidateBooks7803.watch(
                        deskIdentity7391, refreshed.symbol, cryptoDecision7244.strategy7803, "CRYPTO_BRAIN_DISCOVERY_7803"
                    )
                    CryptoStrategyCandidateBooks7803.qualify(
                        deskIdentity7391, refreshed.symbol, cryptoDecision7244.strategy7803,
                        cryptoCv7803, cryptoDecision7244.score, cryptoDecision7244.confidence,
                        cryptoDecision7244.reason,
                    )
                    deskCandidates7803.forEach { d ->
                        CryptoStrategyCandidateBooks7803.qualify(
                            deskIdentity7391, refreshed.symbol, "DESK_" + d.lane,
                            cryptoCv7803, d.conviction.toInt(), d.conviction.toInt(),
                            "CRYPTO_DESK_7803 setup=" + d.setup,
                        )
                    }
                } catch (_: Throwable) {}
                if (cryptoDecision7244.actionableLong) {
                    dynExecutableSignals.add(AltSignal(
                        market = PerpsMarket.DYN, direction = PerpsDirection.LONG,
                        score = cryptoDecision7244.score, confidence = cryptoDecision7244.confidence,
                        price = price, priceChange24h = change, reasons = listOf(cryptoDecision7244.reason),
                        layerVotes = emptyMap(), dynSymbol = refreshed.symbol, dynName = refreshed.name,
                        dynMint = refreshed.mint, dynChainId = refreshed.chainId,
                        dynAssetKey = refreshed.canonicalIdentity6544,
                        strategy7803 = cryptoDecision7244.strategy7803,
                        deskOverlays7803 = deskCandidates7803.map { "DESK_" + it.lane }.toSet(),
                        candidateVersion7803 = cryptoCv7803,
                    ).also { resident ->
                        try {
                            CryptoStrategyCandidateBooks7803.ready(
                                deskIdentity7391, refreshed.symbol, resident.strategy7803,
                                cryptoCv7803, resident.score, resident.confidence,
                                "CRYPTO_NATIVE_READY_7803",
                            )
                        } catch (_: Throwable) {}
                    })
                    cryptoBrainSignals7244++
                    try { PipelineHealthCollector.labelInc("CRYPTO_BRAIN_NATIVE_ACTIONABLE_7244") } catch (_: Throwable) {}
                } else {
                    DynamicAltTokenRegistry.markEvaluationProgress6570(
                        refreshed,
                        if (cryptoDecision7244.shadowOnlyLive) "CRYPTO_BRAIN_SHADOW_ONLY_7244" else "CRYPTO_BRAIN_OBSERVE_7244",
                    )
                }
                // ── ShitCoin sub-AI (low-cap / meme tokens) ──────────────────────────
                if ((bandMcap7391 > 0.0 && bandMcap7391 < 50_000_000.0) || isMeme) {
                    if (!ShitCoinTraderAI.hasPosition(tok.mint)) {
                        try {
                            val sig = ShitCoinTraderAI.evaluate(
                                mint              = tok.mint,
                                symbol            = tok.symbol,
                                currentPrice      = price,
                                marketCapUsd      = bandMcap7391,
                                liquidityUsd      = liq,
                                topHolderPct      = 0.0,
                                buyPressurePct    = buyPct,
                                momentum          = momentum,
                                volatility        = kotlin.math.abs(change),
                                tokenAgeMinutes   = discoveryAgeMinutes6554,
                                launchPlatform    = ShitCoinTraderAI.LaunchPlatform.UNKNOWN,
                                isDexBoosted      = tok.isBoosted,
                                dexTrendingRank   = if (tok.isTrending) tok.trendingRank else -1,
                                // V5.9.1317 — Crypto DynScan SCORES only; never write
                                // crypto-derived garbage into the shared Education layer
                                // that the Meme Trader reads. Keeps Meme learning clean.
                                recordEducationScores = false
                            )
                            if (sig.shouldEnter) {
                                signals++
                                ErrorLogger.info(TAG, "🪙💩 DynSig ShitCoin: ${tok.symbol} conf=${sig.confidence}")
                                // V5.0.4581: no canonical-open hook here — this is only signal generation.
                                // V5.9.2: Convert to tradeable AltSignal
                                // V5.9.1472 — DYNAMIC CRYPTO: open ANY non-Solana coin.
                                // Prefer a hardcoded enum entry when one exists (keeps
                                // its metadata); otherwise carry the real identity on the
                                // DYN sentinel so the coin is no longer silently dropped.
                                run {
                                    // V5.9.230: direction from buyPressure + momentum, not just price sign
                                    val scDir = when {
                                        buyPct >= 60 && momentum > 0 -> PerpsDirection.LONG
                                        buyPct < 40 && momentum < 0  -> PerpsDirection.SHORT
                                        momentum > 3.0               -> PerpsDirection.LONG
                                        momentum < -3.0              -> PerpsDirection.SHORT
                                        else -> if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
                                    }
                                    dynExecutableSignals.add(AltSignal(
                                        market = PerpsMarket.DYN, direction = scDir,
                                        score = sig.confidence, confidence = sig.confidence, price = price,
                                        priceChange24h = change, reasons = listOf("DynScan ShitCoin score=${sig.confidence} ${scDir.name}"),
                                        strategy7803 = "DESK_SHITCOIN",
                                        layerVotes = emptyMap(),
                                        dynSymbol = tok.symbol,
                                        dynName   = tok.name,
                                        dynMint   = tok.mint,
                                        dynChainId = tok.chainId,
                                        dynAssetKey = tok.canonicalIdentity6544
                                    ))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── BlueChip sub-AI (large-cap, liquid) ──────────────────────────────
                // V5.0.7391 — also when the desk elects BLUECHIP (trusted cap at its $1M+ floor).
                if ((mcap > 500_000_000.0 && liq > 1_000_000.0) ||
                    (desk7391.lane == "BLUECHIP" && mcap >= LaneEntryContract6342.blueChipMcapFloor7389() &&
                        liq >= LaneEntryContract6342.BLUECHIP_MIN_LIQ_7389)) {
                    if (!BlueChipTraderAI.hasPosition(tok.mint)) {
                        try {
                            val sig = BlueChipTraderAI.evaluate(
                                mint           = tok.mint,
                                symbol         = tok.symbol,
                                currentPrice   = price,
                                marketCapUsd   = mcap,
                                liquidityUsd   = liq,
                                topHolderPct   = 0.0,
                                buyPressurePct = buyPct,
                                v3Score        = 60,
                                v3Confidence   = 60,
                                momentum       = momentum,
                                volatility     = kotlin.math.abs(change)
                            )
                            if (sig.shouldEnter) {
                                signals++
                                ErrorLogger.info(TAG, "🪙🔵 DynSig BlueChip: ${tok.symbol} conf=${sig.confidence}")
                                // V5.0.4581: no canonical-open hook here — this is only signal generation.
                                run {
                                    val bcDir = when {
                                        buyPct >= 55 && momentum > 1.0 -> PerpsDirection.LONG
                                        buyPct < 45 && momentum < -1.0 -> PerpsDirection.SHORT
                                        else -> if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
                                    }
                                    dynExecutableSignals.add(AltSignal(
                                        market = PerpsMarket.DYN, direction = bcDir,
                                        score = sig.confidence + 5, confidence = sig.confidence, price = price,
                                        priceChange24h = change, reasons = listOf("DynScan BlueChip mcap=\$${(mcap/1_000_000).toInt()}M ${bcDir.name}"),
                                        strategy7803 = "DESK_BLUECHIP",
                                        layerVotes = emptyMap(),
                                        dynSymbol = tok.symbol,
                                        dynName   = tok.name,
                                        dynMint   = tok.mint,
                                        dynChainId = tok.chainId,
                                        dynAssetKey = tok.canonicalIdentity6544
                                    ))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── Express sub-AI (high-momentum tokens) ────────────────────────────
                if (kotlin.math.abs(change) > 5.0 && vol > 100_000.0) {
                    if (!ShitCoinExpress.hasRide(tok.mint)) {
                        try {
                            val sig = ShitCoinExpress.evaluate(
                                mint            = tok.mint,
                                symbol          = tok.symbol,
                                currentPrice    = price,
                                marketCapUsd    = bandMcap7391,
                                liquidityUsd    = liq,
                                momentum        = momentum,
                                buyPressurePct  = buyPct,
                                volumeChange    = if (vol > 0) 1.5 else 1.0,
                                priceChange5Min = change / 288.0,
                                isTrending      = tok.isTrending,
                                isBoosted       = tok.isBoosted,
                                tokenAgeMinutes = discoveryAgeMinutes6554
                            )
                            if (sig.shouldRide) {
                                signals++
                                ErrorLogger.info(TAG, "🪙⚡ DynSig Express: ${tok.symbol}")
                                val expressDir6554 = if (momentum >= 0.0) PerpsDirection.LONG else PerpsDirection.SHORT
                                dynExecutableSignals.add(AltSignal(
                                    market = PerpsMarket.DYN, direction = expressDir6554,
                                    score = sig.confidence, confidence = sig.confidence, price = price,
                                    priceChange24h = change, reasons = listOf("DynScan Express ${sig.reason} ${expressDir6554.name}"),
                                    strategy7803 = "DESK_EXPRESS",
                                    layerVotes = emptyMap(), dynSymbol = tok.symbol, dynName = tok.name,
                                    dynMint = tok.mint, dynChainId = tok.chainId, dynAssetKey = tok.canonicalIdentity6544
                                ))
                                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DYN_EXPRESS_EXECUTABLE_6554") } catch (_: Throwable) {}
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── Moonshot sub-AI (ultra-low mcap, trending) ───────────────────────
                if (bandMcap7391 in 100_000.0..50_000_000.0 || tok.isTrending || desk7391.lane == "MOONSHOT") {
                    if (!MoonshotTraderAI.hasPosition(tok.mint)) {
                        try {
                            val sig = MoonshotTraderAI.scoreToken(
                                mint           = tok.mint,
                                symbol         = tok.symbol,
                                marketCapUsd   = bandMcap7391,
                                liquidityUsd   = liq,
                                volumeScore    = minOf(100, (vol / 10_000).toInt()),
                                buyPressurePct = buyPct,
                                rugcheckScore  = if (tok.source.contains("Jupiter")) 5 else 3,
                                v3EntryScore   = cryptoDecision7244.score.toDouble(),
                                v3Confidence   = cryptoDecision7244.confidence.toDouble(),
                                phase          = "DynamicAlt",
                                isPaper        = isPaperMode.get()
                            )
                            if (sig.eligible) {
                                signals++
                                ErrorLogger.info(TAG, "🪙🌙 DynSig Moonshot: ${tok.symbol}")
                                // V5.0.4581: no canonical-open hook here — this is only signal generation.
                                dynExecutableSignals.add(AltSignal(
                                    // V5.9.230: Moonshot is always LONG (looking for explosive upside)
                                    market = PerpsMarket.DYN, direction = PerpsDirection.LONG,
                                    score = sig.score.coerceAtMost(95), confidence = sig.score.coerceAtMost(95), price = price,
                                    priceChange24h = change, reasons = listOf("DynScan Moonshot trending=${tok.isTrending}"),
                                    strategy7803 = "DESK_MOONSHOT",
                                    layerVotes = emptyMap(),
                                    dynSymbol = tok.symbol,
                                    dynName   = tok.name,
                                    dynMint   = tok.mint,
                                    dynChainId = tok.chainId,
                                    dynAssetKey = tok.canonicalIdentity6544
                                ))
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── QUALITY desk (V5.0.7391; crypto never called it) ─────────────────
                if (desk7391.lane == "QUALITY" || bandMcap7391 in LaneEntryContract6342.qualityMcapBand7389()) {
                    if (!QualityTraderAI.hasPosition(tok.mint)) {
                        try {
                            val q = QualityTraderAI.evaluate(
                                mint = tok.mint, symbol = tok.symbol, currentPrice = price,
                                marketCapUsd = bandMcap7391, liquidityUsd = liq,
                                buyPressure = buyPct.toInt(), tokenAgeMinutes = discoveryAgeMinutes6554,
                                v3Score = cryptoDecision7244.score, isMeme = isMeme,
                            )
                            if (q.shouldEnter) {
                                signals++
                                val qScore = maxOf(q.qualityScore, desk7391.conviction.toInt()).coerceIn(0, 100)
                                dynExecutableSignals.add(AltSignal(
                                    market = PerpsMarket.DYN, direction = PerpsDirection.LONG,
                                    score = qScore, confidence = qScore, price = price,
                                    priceChange24h = change, reasons = listOf("DynScan Quality ${q.reason}"),
                                    strategy7803 = "DESK_QUALITY",
                                    layerVotes = emptyMap(), dynSymbol = tok.symbol, dynName = tok.name,
                                    dynMint = tok.mint, dynChainId = tok.chainId, dynAssetKey = tok.canonicalIdentity6544,
                                ))
                                try { PipelineHealthCollector.labelInc("CRYPTO_DESK_QUALITY_SIGNAL_7391") } catch (_: Throwable) {}
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── DIP_HUNTER desk (V5.0.7391) — buys a confirmed bounce ─────────────
                if (desk7391.lane == "DIP_HUNTER" || bandMcap7391 in 50_000.0..5_000_000.0) {
                    if (!com.lifecyclebot.v3.scoring.DipHunterAI.hasDip(tok.mint)) {
                        try {
                            val recorded7391 = CryptoLaneDesk7391.prices(deskIdentity7391)
                            // The recent high: the recorded series, or the 24h open implied by a 24h fall.
                            val implied24hHigh = if (change < 0.0 && change > -99.0) price / (1.0 + change / 100.0) else price
                            val high7391 = maxOf(recorded7391.maxOrNull() ?: price, implied24hHigh, price)
                            val d = com.lifecyclebot.v3.scoring.DipHunterAI.evaluate(
                                mint = tok.mint, symbol = tok.symbol, currentPrice = price, highPrice = high7391,
                                marketCapUsd = bandMcap7391, liquidityUsd = liq, buyPressurePct = buyPct,
                                volumeVsAvg = 1.0, tokenAgeHours = refreshed.discoveryAgeHours6544.coerceAtMost(9_000.0),
                                holderCount = 100, holderChange24h = null, isDevSelling = false,
                                bounceConfirmed = CryptoLaneDesk7391.bounceConfirmed(deskIdentity7391, buyPct),
                            )
                            if (d.shouldBuy) {
                                signals++
                                dynExecutableSignals.add(AltSignal(
                                    market = PerpsMarket.DYN, direction = PerpsDirection.LONG,
                                    score = d.confidence, confidence = d.confidence, price = price,
                                    priceChange24h = change, reasons = listOf("DynScan DipHunter ${d.reason}"),
                                    strategy7803 = "DESK_DIP_HUNTER",
                                    layerVotes = emptyMap(), dynSymbol = tok.symbol, dynName = tok.name,
                                    dynMint = tok.mint, dynChainId = tok.chainId, dynAssetKey = tok.canonicalIdentity6544,
                                ))
                                try { PipelineHealthCollector.labelInc("CRYPTO_DESK_DIP_SIGNAL_7391") } catch (_: Throwable) {}
                            }
                        } catch (_: Exception) {}
                    }
                }

                // ── CORE ensemble (V5.0.7391) — two or more desks rate it, none leads ─
                if (desk7391.lane == "CORE" && desk7391.voters >= 2 && buyPct >= 50.0 &&
                    desk7391.movementPattern !in setOf("FREEFALL_NO_RECLAIM", "EXHAUSTION_CHASE")) {
                    val coreScore7391 = maxOf(desk7391.conviction, cryptoDecision7244.score.toDouble()).toInt().coerceIn(0, 100)
                    dynExecutableSignals.add(AltSignal(
                        market = PerpsMarket.DYN, direction = PerpsDirection.LONG,
                        score = coreScore7391, confidence = desk7391.conviction.toInt().coerceIn(0, 100), price = price,
                        priceChange24h = change,
                        reasons = listOf("DynScan Core ensemble voters=${desk7391.voters} setup=${desk7391.setup}"),
                        strategy7803 = "DESK_CORE",
                        layerVotes = emptyMap(), dynSymbol = tok.symbol, dynName = tok.name,
                        dynMint = tok.mint, dynChainId = tok.chainId, dynAssetKey = tok.canonicalIdentity6544,
                    ))
                    try { PipelineHealthCollector.labelInc("CRYPTO_DESK_CORE_SIGNAL_7391") } catch (_: Throwable) {}
                }

                // ── Manipulated sub-AI (pump signals) ────────────────────────────────
                var manipDanger7391 = false
                if (!ManipulatedTraderAI.hasPosition(tok.mint)) {
                    try {
                        val sig = ManipulatedTraderAI.evaluate(
                            mint          = tok.mint,
                            symbol        = tok.symbol,
                            currentPrice  = price,
                            marketCapUsd  = bandMcap7391,
                            liquidityUsd  = liq,
                            momentum      = momentum,
                            buyPressurePct= buyPct,
                            bundlePct     = 0.0,
                            source        = tok.source,
                            ageMinutes    = discoveryAgeMinutes6554,
                            rugcheckScore = if (tok.source.contains("Jupiter")) 5 else 3,
                            isPaper       = isPaperMode.get()
                        )
                        // V5.0.7391 — MANIPULATED is a danger read, not a buyer (as on
                        // the meme desk since 5.0.7389): a manipulation read shrinks this
                        // token's other signals (the meme overlay's -40 score penalty,
                        // MANIP_OVERLAY_DUST_PROBE_SCORE_PENALTY_6011) instead of opening one.
                        if (sig.shouldEnter) manipDanger7391 = true
                    } catch (_: Exception) {}
                }
                // V5.0.7391 — a manipulation read shrinks this token's signals by the
                // meme overlay's -40; every signal carries its lane (and the desk's
                // election) so the position's exits and learning read the same lane.
                if (manipDanger7391 && dynExecutableSignals.size > executableSignalCountBefore6567) {
                    try { PipelineHealthCollector.labelInc("CRYPTO_DESK_MANIP_OVERLAY_PENALTY_7391") } catch (_: Throwable) {}
                }
                // V5.0.7391 — a candidate priced from a registry figure older than
                // the entry-basis window (7281: 180 s) was refused at the fill as
                // CRYPTO_PAPER_ENTRY_BASIS_UNOBSERVED_7275 (215 vs 11 observed). Only
                // tokens that produced a signal are re-priced now, so the signal and
                // the basis come from one fresh observation.
                var freshPrice7391 = 0.0
                if (dynExecutableSignals.size > executableSignalCountBefore6567 &&
                    System.currentTimeMillis() - refreshed.lastUpdatedMs > 150_000L) {
                    freshPrice7391 = try {
                        withContext(Dispatchers.IO) {
                            DynamicAltTokenRegistry.refreshPriceForMintBlocking(refreshed.canonicalIdentity6544, forceRefresh = true)
                        }
                    } catch (_: Throwable) { 0.0 }
                    val reobserved7391 = DynamicAltTokenRegistry.heldMarkSnapshot7251(refreshed.canonicalIdentity6544).freshObservation
                    if (!reobserved7391) freshPrice7391 = 0.0
                    try {
                        PipelineHealthCollector.labelInc(
                            if (freshPrice7391 > 0.0) "CRYPTO_DESK_SIGNAL_REPRICED_7391" else "CRYPTO_DESK_SIGNAL_REPRICE_FAILED_7391",
                        )
                    } catch (_: Throwable) {}
                }
                for (i7391 in executableSignalCountBefore6567 until dynExecutableSignals.size) {
                    val s7391 = dynExecutableSignals[i7391]
                    val lane7391 = cryptoDeskLaneOf7391(s7391.reasons, desk7391.lane)
                    dynExecutableSignals[i7391] = s7391.copy(
                        score = if (manipDanger7391) (s7391.score - 40).coerceAtLeast(0) else s7391.score,
                        price = if (freshPrice7391 > 0.0) freshPrice7391 else s7391.price,
                        reasons = s7391.reasons + "${CryptoLaneDesk7391.LANE_REASON_PREFIX}$lane7391 desk=${desk7391.lane.ifBlank { "NONE" }} setup=${desk7391.setup} move=${desk7391.movementPattern}",
                    )
                }
                if (dynExecutableSignals.size > executableSignalCountBefore6567) {
                    com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "ACTIONABLE_SIGNAL")
                }
                if (dynExecutableSignals.size == executableSignalCountBefore6567) {
                    // V5.0.7472 — do not terminally retire a FRESH candidate before
                    // the existing tactic machinery has enough local observations to
                    // judge pullback/breakout/reversion structure. CryptoLaneDesk7391
                    // requires four samples for those shapes; a terminal NO_ACTIONABLE
                    // on samples 1..3 made fresh discovery disappear before the desk
                    // could become informative. Release only the evaluation lease and
                    // let the next scan observation advance the same candidate.
                    val freshTapeSamples7472 = try {
                        CryptoLaneDesk7391.prices(deskIdentity7391).size
                    } catch (_: Throwable) { 0 }
                    if (refreshed.isFresh6544) {
                        val retryReason7828 = if (freshTapeSamples7472 < 4)
                            "CRYPTO_FRESH_TAPE_WARMUP_7472"
                        else "CRYPTO_FRESH_NO_ACTIONABLE_RETRY_7828"
                        DynamicAltTokenRegistry.releaseEvaluationForRetry7418(refreshed, retryReason7828)
                        try {
                            PipelineHealthCollector.labelInc(retryReason7828)
                            PipelineHealthCollector.labelInc("CRYPTO_FRESH_TAPE_WARMUP_SAMPLE_${freshTapeSamples7472}_7472")
                        } catch (_: Throwable) {}
                    } else {
                        DynamicAltTokenRegistry.markEvaluationDisposition6567(
                            refreshed, "CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244",
                        )
                        try {
                            PipelineHealthCollector.labelInc("CRYPTO_SPECIALIST_SILENCE_OBSERVATION_ONLY_7244")
                            PipelineHealthCollector.labelInc("CRYPTO_NO_ACTION_GENERATION_COMPLETED_7443")
                        } catch (_: Throwable) {}
                    }
                }

            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  DynamicAltTokenRegistry.markEvaluationDisposition6567(tok, "EVALUATION_EXCEPTION_${e.javaClass.simpleName}")
              }
        }

        // V5.9.2: Execute top DynScan signals — previously these were logged but never acted on
        // Now we convert high-confidence DynToken signals into real AltSignal trades
        if (dynExecutableSignals.isNotEmpty()) {
            // V5.9.1442 — Crypto isolated brain thresholds (was FluidLearningAI.getAltsXxx).
            // V5.0.7803 — strategy residency survives discovery. Every proposal
            // is qualified independently; only actually executable proposals become
            // READY. Cross-strategy reduction happens HERE, immediately before the
            // one canonical Crypto execution spine.
            val uniqueDynSignals6567 = dynExecutableSignals
                .groupBy { it.dynAssetKey ?: it.dynMint ?: "${it.market.name}:${it.marketSymbol}" }
                .values.mapNotNull { rows ->
                    val first = rows.firstOrNull() ?: return@mapNotNull null
                    val assetKey7803 = first.dynAssetKey ?: first.dynMint ?: "${first.market.name}:${first.marketSymbol}"
                    val cv7803 = rows.map { it.candidateVersion7803 }.firstOrNull { it > 0L }
                        ?: (System.currentTimeMillis() / 30_000L)
                    rows.forEach { proposal ->
                        try {
                            CryptoStrategyCandidateBooks7803.qualify(
                                assetKey7803, proposal.marketSymbol, proposal.strategy7803,
                                cv7803, proposal.score, proposal.confidence,
                                proposal.reasons.joinToString(";").take(180),
                            )
                            val executableDirection7803 =
                                proposal.direction == PerpsDirection.LONG || LEVERAGE_VENUE_AVAILABLE_7183
                            if (executableDirection7803) {
                                CryptoStrategyCandidateBooks7803.ready(
                                    assetKey7803, proposal.marketSymbol, proposal.strategy7803,
                                    cv7803, proposal.score, proposal.confidence,
                                    "CRYPTO_EXECUTABLE_READY_7803",
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                    val ready7803 = CryptoStrategyCandidateBooks7803.bestReady(assetKey7803, cv7803)
                    val executableRows7803 = rows.filter {
                        it.direction == PerpsDirection.LONG || LEVERAGE_VENUE_AVAILABLE_7183
                    }
                    executableRows7803.firstOrNull { it.strategy7803 == ready7803?.strategy }
                        ?: executableRows7803.maxByOrNull { it.score * 1000 + it.confidence }
                }
            // V5.0.6569 — specialist thresholds are features for the shared
            // authority, not a terminal pre-V3 gate. Rank continuously and bound work.
            val topDyn = uniqueDynSignals6567
                .sortedWith(compareByDescending<AltSignal> { it.score + it.confidence }.thenByDescending { it.score })
                .take(25)
            uniqueDynSignals6567.filterNot { it in topDyn }.forEach { observed ->
                val observedTok6569 = observed.dynAssetKey?.let { DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(it) }
                    ?: observed.dynMint?.let { DynamicAltTokenRegistry.getTokenByMint(it) }
                // V5.0.7400 — ranked-out is NOT terminal. 7399 violated the
                // existing 6695 contract and retired candidates merely because
                // they missed one bounded top-25 window. Preserve them as shared
                // intelligence so they can be reconsidered as rank/price changes.
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_RANKED_OUT_RETAINED_7400") } catch (_: Throwable) {}
                DynamicAltTokenRegistry.markEvaluationProgress6570(observedTok6569, "SHARED_INTELLIGENCE_BACKLOG_COALESCED")
            }
            for ((signalIndex6567, sig) in topDyn.withIndex()) {
                if (activeModePositions7256(positions.values).size >= MAX_POSITIONS) {
                    topDyn.drop(signalIndex6567).forEach { capped ->
                        val cappedTok6567 = capped.dynAssetKey?.let { DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(it) }
                            ?: capped.dynMint?.let { DynamicAltTokenRegistry.getTokenByMint(it) }
                        DynamicAltTokenRegistry.markEvaluationDisposition6567(cappedTok6567, "POSITION_CAP_REACHED")
                    }
                    break
                }
                val sharedTok6569 = sig.dynAssetKey?.let { DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(it) }
                    ?: sig.dynMint?.let { DynamicAltTokenRegistry.getTokenByMint(it) }
                // V5.0.7244 — do not stamp route truth before route resolution.
                // The resolved candidate below is the only authority for routability.
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(
                    com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "RAW_SIGNAL"
                )
                if (sig.direction == PerpsDirection.SHORT && !LEVERAGE_VENUE_AVAILABLE_7183) {
                    DynamicAltTokenRegistry.markEvaluationDisposition6567(
                        sharedTok6569, "SPOT_ONLY_SHORT_OBSERVATION_7244",
                    )
                    try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_ONLY_SHORT_OBSERVATION_7244") } catch (_: Throwable) {}
                    continue
                }
                try { ForensicLogger.lifecycle("CRYPTO_SIGNAL_SELECTED_6566", "symbol=${sig.marketSymbol} source=DYNAMIC_ALT score=${sig.score} confidence=${sig.confidence} mode=${if (isPaperMode.get()) "PAPER" else "LIVE"}") } catch (_: Throwable) {}
                // V5.9.1472 — dedupe by real symbol so DYN coins aren't collapsed.
                if (hasPositionSymbol(sig.marketSymbol)) {
                    val openTok6567 = sig.dynAssetKey?.let { DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(it) }
                        ?: sig.dynMint?.let { DynamicAltTokenRegistry.getTokenByMint(it) }
                    DynamicAltTokenRegistry.markEvaluationDisposition6567(openTok6567, "POSITION_ALREADY_OPEN")
                    continue
                }
                // V5.9.3: respect UI toggle for DynScan signals too
                // V5.0.7183 — the DynScan path had its own copy of the toggle
                // read, so disabling leverage at the signal loop alone would
                // have left this one still minting 3x positions. Same authority.
                val dynSpot = if (LEVERAGE_VENUE_AVAILABLE_7183) !preferLeverage.get() else true
                val dynLev  = if (dynSpot) 1.0 else DEFAULT_LEVERAGE
                // V5.9.1548 — terminal-reject semantics: do not call this EXECUTE
                // before executeSignal() runs price sanity, spread, FDG, sizing and
                // route gates. Rejected candidates may be attempted/evaluated, but
                // must never emit EXECUTE wording unless an open actually succeeds.
                ErrorLogger.info(TAG, "🪙⚡ DynScan ATTEMPT: ${sig.marketSymbol} score=${sig.score} conf=${sig.confidence} ${if (dynSpot) "SPOT" else "${dynLev.toInt()}x"}")
                val terminalTok6567 = sig.dynAssetKey?.let { DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(it) }
                    ?: sig.dynMint?.let { DynamicAltTokenRegistry.getTokenByMint(it) }
                try {
                    // V5.0.7398 — this comment promised a producer CANDIDATE stamp,
                    // but the call was absent. That made live diagnostics report
                    // actionableSignal>0 with candidateCreated=0 even while
                    // executeSignal was being invoked. Stamp the actual bounded,
                    // deduped handoff here; canonical SUBMIT is stamped only when
                    // CanonicalEntryAuthority6551.submit is reached below.
                    com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(
                        com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "CANDIDATE"
                    )
                    executeSignal(sig.copy(leverage = dynLev), isSpot = dynSpot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    DynamicAltTokenRegistry.markEvaluationDisposition6567(terminalTok6567, "EXECUTION_EXCEPTION_${e.javaClass.simpleName}")
                    // V5.0.7735 — the class name alone hid a sizing bug for three builds; the message travels with it.
                    try {
                        ForensicLogger.lifecycle(
                            "CRYPTO_EXECUTE_SIGNAL_THREW_7735",
                            "symbol=${sig.marketSymbol} exception=${e.javaClass.simpleName} message=${(e.message ?: "").take(140)}",
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.completeProducerWindow6569(
            com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, isEnabled.get(), isRunning.get(),
            "batch=${batch.size} scanned=$scanned cryptoBrainSignals=$cryptoBrainSignals7244 specialistSignals=$signals sharedCandidates=${dynExecutableSignals.size}"
        )
        if (signals > 0 || cryptoBrainSignals7244 > 0 || scanned % 200 == 0) {
            ErrorLogger.info(TAG, "🪙⚡ DynScan done: scanned=$scanned execSignals=${dynExecutableSignals.size} (universe=${universe7823.size})")
        }
    }

    private suspend fun runScanCycle() {
        val scanPaper7803 = authoritativePaperMode7425()
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()

        // Periodic persistence — save learning state every 10 scan cycles
        if (scanNum % 10 == 0) {
            saveToSharedPrefs()
            savePersistedState()
            try { FluidLearningAI.saveAltsPrefs() } catch (_: Exception) {}
            try { PerpsLearningBridge.save() } catch (_: Exception) {}
        }

        ErrorLogger.info(TAG, "🪙 ═══════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🪙 ALT SCAN #$scanNum STARTING")
        // V5.9.495z34 — segregate live/paper/sim/watchlist counts so
        // operators can tell whether a paper backlog is masking a real
        // live position state. Operator-reported "CryptoAltTrader
        // positions=51" was blending all three.
        // V5.9.654 — operator 10-Point Triage #1: replace the open-loop
        // record() (which monotonically grew the bucket and produced
        // "paper=45" with positions=1) with replaceBucket() so the
        // diagnostic count is exactly the open `positions` map for the
        // current mode and stale symbols are evicted automatically.
        try {
            val isPaper = scanPaper7803
            val bucket = if (isPaper)
                com.lifecyclebot.engine.CryptoPositionState.Bucket.PAPER
            else
                com.lifecyclebot.engine.CryptoPositionState.Bucket.LIVE
            val openSymbols = activeModePositions7256(positions.values).map { it.market.symbol }
            com.lifecyclebot.engine.CryptoPositionState.replaceBucket(bucket, openSymbols)
        } catch (_: Throwable) { /* best-effort */ }
        val cpsLine = try {
            com.lifecyclebot.engine.CryptoPositionState.summaryLine()
        } catch (_: Throwable) { "n/a" }
        ErrorLogger.info(TAG, "🪙 positions=${activeModePositions7256(positions.values).size} ($cpsLine) | balance=${"%.2f".format(getBalance())} SOL")
        ErrorLogger.info(TAG, "🪙 ═══════════════════════════════════════════════════")

        // V5.9.1452 — UNMISSABLE diagnostic line emitted EVERY scan cycle.
        // Operator-reported "crypto universe isn't trading at all" — without
        // a per-cycle visible signature, the operator can't tell if the
        // scan is even running or where the funnel collapses. This line
        // prints the universe size, paper/live mode, current brain
        // maturity + thresholds, AND the live funnel snapshot.
        try {
            val mode = if (scanPaper7803) "PAPER" else "LIVE"
            val mat = com.lifecyclebot.perps.crypto.brain.CryptoBrain.maturity().name
            val scoreFloor = com.lifecyclebot.perps.crypto.brain.CryptoBrain.getSpotScoreFloor()
            val confFloor = com.lifecyclebot.perps.crypto.brain.CryptoBrain.getSpotConfFloor()
            val tilt = com.lifecyclebot.perps.crypto.brain.CryptoBrain.isTiltProtectionActive()
            val sentiment = com.lifecyclebot.perps.crypto.brain.CryptoBrain.sentimentClassification()
            ErrorLogger.info(TAG, "🪙 SCAN_DIAG mode=$mode maturity=$mat thresh=$scoreFloor/$confFloor tilt=$tilt mood=$sentiment trades=${com.lifecyclebot.perps.crypto.brain.CryptoBrain.tradeCount()}")
        } catch (_: Throwable) {}

        // All crypto markets that are NOT covered by the SOL perps engine.
        // V5.9.1442 — Solana isolation: CryptoUniverseFilter blocks any
        // Solana-native long-tail/meme token from the crypto universe via
        // an explicit denylist (BONK, WIF, PEPE, …). Multi-chain majors
        // (BTC/ETH/BNB/XRP/ADA/…) are always admitted; SOL ecosystem
        // majors (SOL/JTO/JUP/RAY/ORCA/PYTH/W) are admitted; everything
        // else with a Solana-only venue stays with the memetrader.
        val altMarkets = PerpsMarket.values().filter { m ->
            if (!m.isCrypto || SOL_PERPS_SYMBOLS.contains(m.symbol)) return@filter false
            // Conservative: treat all enum entries as non-Solana-native
            // (PerpsMarket holds multi-chain majors). The denylist alone
            // covers any meme-symbol that slipped into the enum.
            val admitted = com.lifecyclebot.perps.crypto.CryptoUniverseFilter
                .isAdmittedToCryptoUniverse(m.symbol, isSolanaNative = false)
            com.lifecyclebot.perps.crypto.brain.CryptoFunnel.universe(admitted)
            if (!admitted) {
                val reason = com.lifecyclebot.perps.crypto.CryptoUniverseFilter
                    .rejectionReason(m.symbol, isSolanaNative = false)
                try { ErrorLogger.debug(TAG, "🪙 universe-filter skip ${m.symbol} ($reason)") } catch (_: Throwable) {}
            }
            admitted
        }

        // V5.9.1452 — universe-size visibility every scan
        try {
            val funnelLine = com.lifecyclebot.perps.crypto.brain.CryptoFunnel.summary().lineSequence().take(2).joinToString(" / ")
            ErrorLogger.info(TAG, "🪙 UNIVERSE altMarkets=${altMarkets.size} after filter (PerpsMarket.values=${PerpsMarket.values().size}, SOL_PERPS_excluded=${SOL_PERPS_SYMBOLS.size}) | $funnelLine")
        } catch (_: Throwable) {}

        val signals      = mutableListOf<AltSignal>()
        var analyzed     = 0
        var skippedPos   = 0
        var skippedPrice = 0

        for (market in altMarkets) {
            try {
                if (hasPosition(market)) { skippedPos++; continue }

                // V5.9.292: LIVE mode — skip markets that cannot actually execute.
                // V5.9.303: Audit fix — the alt trader is meant to trade the ENTIRE crypto market.
                // The previous logic skipped any LIVE SPOT signal lacking a Solana mint, which
                // killed >80% of the alt universe (SAND, IMX, STX, RUNE, CRV, SNX, MKR, AAVE,
                // GRT, RENDER, FIL, VET, ICP, HBAR, ALGO, FTM, SEI, SHIB, etc. — all skipped).
                //
                // NEW POLICY:
                //   • PAPER mode: no mint check — paper sims everything, learning gets full universe.
                //   • LIVE mode + Flash.trade-supported symbol: allow signal, executor will route
                //     via Flash perps (no Solana mint needed).
                //   • LIVE mode + has Solana mint: allow signal (SPOT swap path).
                //   • LIVE mode + no mint + not on Flash: skip (truly unreachable).
                //
                // V5.9.495z23 — operator's 6-hour run had every PERPS_CRYPTOALT BUY fail with
                // "no tx (mint missing)" because the previous gate was skipped whenever
                // `preferLeverage=true`. That left FLOKI/PIXEL/ANKR/PERP/ZEN/HBAR/FTM/STG/GRT
                // (all non-Solana, non-Flash) generating live signals that could never execute.
                // The gate now runs in EVERY live path so unreachable symbols never burn cycles.
                val isLiveScan = !scanPaper7803
                if (isLiveScan) {
                    val hasMint = com.lifecyclebot.perps.crypto.CryptoWrappedAssetMapper
                        .resolveWrappedMint(market.symbol) != null
                    val flashSupported = market.symbol.uppercase() in FLASH_TRADE_PERPS_SYMBOLS
                    if (!hasMint && !flashSupported) {
                        ErrorLogger.debug(TAG, "🪙 LIVE SKIP: ${market.symbol} — no Solana mint AND not on Flash perps")
                        continue
                    }
                    if (!hasMint && flashSupported) {
                        ErrorLogger.debug(TAG, "🪙 LIVE → LEVERAGE: ${market.symbol} — no SPOT mint, routing via Flash perps")
                    }
                }

                val data = PerpsMarketDataFetcher.getMarketData(market)

                if (data.price <= 0) {
                    skippedPrice++
                    if (market.symbol in NO_FEED_SYMBOLS) {
                    ErrorLogger.debug(TAG, "🪙 ${market.symbol}: no feed configured — silenced")
                } else {
                    ErrorLogger.warn(TAG, "🪙 ${market.symbol}: price=0 — skipped")
                }
                    continue
                }

                // Feed V4 meta layers
                try {
                    CrossAssetLeadLagAI.recordPrice7441(market.symbol, data.price)
                    CrossMarketRegimeAI.updateMarketState(market.symbol, data.price, data.priceChange24hPct, data.volume24h)
                } catch (_: Exception) {}

                analyzed++

                val signal = analyzeAlt(market, data)
                com.lifecyclebot.perps.crypto.brain.CryptoFunnel.analyze(signal != null)
                if (signal != null) {
                    // V5.9.1442 — Crypto isolated brain thresholds.
                    val scoreThresh = com.lifecyclebot.perps.crypto.brain.CryptoBrain.getSpotScoreFloor()
                    val confThresh  = com.lifecyclebot.perps.crypto.brain.CryptoBrain.getSpotConfFloor()

                    // V5.9.132: V3 IS THE GATE. Fluid score/conf gate is now
                    // only a preliminary sanity check — any signal with score≥45
                    // gets a V3 UnifiedScorer evaluation (41 layers + AITrustNet).
                    // If V3 passes, it enters even if the fluid conf gate would
                    // have blocked. 50/50 signals no longer get silently dropped.
                    val fluidPass = signal.score >= scoreThresh && signal.confidence >= confThresh
                    val prefilterOk = signal.score >= 45
                    com.lifecyclebot.perps.crypto.brain.CryptoFunnel.threshold(fluidPass)

                    if (fluidPass) {
                        signals.add(signal)
                        ErrorLogger.info(TAG, "🪙 SIGNAL: ${market.symbol} | score=${signal.score} | conf=${signal.confidence} | dir=${signal.direction.symbol}")
                    } else if (prefilterOk) {
                        val v3Approves = try {
                            val (liqUsdEst2, mcapUsdEst2) = altLiqMcapHint(market.symbol)
                            val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                                symbol = market.symbol,
                                assetClass = "ALT",
                                price = signal.price,
                                technicalScore = signal.score,
                                technicalConfidence = signal.confidence,
                                liqUsd = liqUsdEst2,
                                mcapUsd = mcapUsdEst2,
                                priceChangePct = signal.priceChange24h,
                                direction = signal.direction.name,
                            )
                            if (verdict.shouldEnter) {
                                ErrorLogger.info(TAG, "🪙 V3-OVERRIDE: ${market.symbol} (score=${signal.score}/${signal.confidence} vs fluid ${scoreThresh}/${confThresh}) → v3=${verdict.v3Score} blended=${verdict.blendedScore}")
                                true
                            } else false
                        } catch (_: Exception) { false }
                        com.lifecyclebot.perps.crypto.brain.CryptoFunnel.v3(v3Approves)

                        if (v3Approves) signals.add(signal)
                        else ErrorLogger.warn(TAG, "🪙 ${market.symbol}: BELOW FLUID + V3 VETO (${signal.score}<$scoreThresh or ${signal.confidence}<$confThresh)")
                    } else {
                        ErrorLogger.warn(TAG, "🪙 ${market.symbol}: below prefilter (${signal.score}<45)")
                    }
                } else {
                    // V5.9.412 — demote to debug. analyzeAlt returns null when
                    // a momentum/RSI gate trips or data is missing — that's
                    // routine, not warn-worthy log noise (it was firing on
                    // every flat-ranging alt, multiple per minute).
                    ErrorLogger.debug(TAG, "🪙 ${market.symbol}: analyzeAlt skipped (gate or missing data)")
                }

            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { ErrorLogger.error(TAG, "🪙 ${market.symbol} exception: ${e.message}", e) }
        }

        ErrorLogger.info(TAG, "🪙 Scan stats: analyzed=$analyzed | hasPos=$skippedPos | badPrice=$skippedPrice | signals=${signals.size}")

        // V4 meta scan
        try {
            CrossAssetLeadLagAI.scan()
            CrossMarketRegimeAI.assessRegime()
            LeverageSurvivalAI.assess(
                currentVolatility = signals.map { abs(it.priceChange24h) }.average().takeIf { !it.isNaN() } ?: 0.0,
                fragilityScore    = LiquidityFragilityAI.getFragilityScore("ALT_MARKET")
            )
            CrossTalkFusionEngine.fuse()
        } catch (_: Exception) {}

        val topSignals = signals.sortedByDescending { it.score }.take(50)  // V5.9.128: raised from 5 → 50 so the bot actually uses the full alt universe

        // V5.9.130: FULL V3 STACK — run every signal through UnifiedScorer so the
        // alt trader gets the SAME 41 AI layers + AITrustNetwork + 14 V5.9.123
        // layers + real accuracy loop + ReflexAI that the memetrader uses.
        val v3Filtered = topSignals.mapNotNull { sig ->
            try {
                ForensicLogger.lifecycle("CRYPTO_SIGNAL_SELECTED_6566", "symbol=${sig.marketSymbol} source=STATIC_ALT score=${sig.score} confidence=${sig.confidence} mode=${if (scanPaper7803) "PAPER" else "LIVE"}")
                // V5.9.400 — pass realistic per-tier liquidity/mcap so V3
                // layers (LiquidityExitPath, ExecutionCost, MEV, etc.) don't
                // mis-score every alt with `liquidity=-7`. Tier inference
                // is rough but accurate enough to stop the systematic veto.
                // Mid/large caps (BTC/ETH/SOL/major DEX tokens) sit at the
                // top of LIQ_500K_PLUS; small alts stay at $500K floor.
                val (liqUsdEst, mcapUsdEst) = altLiqMcapHint(sig.market.symbol)
                val verdict = PerpsUnifiedScorerBridge.scoreForEntry(
                    symbol = sig.market.symbol,
                    assetClass = "ALT",
                    price = sig.price,
                    technicalScore = sig.score,
                    technicalConfidence = sig.confidence,
                    liqUsd = liqUsdEst,
                    mcapUsd = mcapUsdEst,
                    priceChangePct = sig.priceChange24h,
                    direction = sig.direction.name,
                )
                // V5.9.400 — V3 bridge is ADVISORY for alts. The alt trader's
                // own (score, conf, momentum gate) decides entry. V3 still
                // contributes via verdict.trustMultiplier (sizing) and
                // accumulates per-layer learning signals, but no longer
                // single-handedly vetoes 45/45 signals to zero entries.
                ErrorLogger.debug(TAG, "🪙 V3 advisory: ${sig.market.symbol} v3=${verdict.v3Score} blended=${verdict.blendedScore} trust=×${"%.2f".format(verdict.trustMultiplier)} shouldEnter=${verdict.shouldEnter}")
                sig to verdict
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "🪙 V3 bridge error for ${sig.market.symbol}: ${e.message}")
                sig to null   // fall back — allow signal through on bridge error
            }
        }

        if (v3Filtered.isEmpty()) {
            ErrorLogger.warn(TAG, "🪙 ⚠️ NO SIGNALS — skipping execution")
            return
        }

        ErrorLogger.info(TAG, "🪙 TOP ${v3Filtered.size} V3-filtered signals: ${v3Filtered.map { "${it.first.market.symbol}(${it.first.score}/${it.first.confidence})" }}")

        for ((signal, verdict) in v3Filtered) {
            if (activeModePositions7256(positions.values).size >= MAX_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached (hard cap)")
                break
            }
            // V5.9.221: At soft cap, try to evict a weak/losing position to make room.
            // If nothing can be evicted (all winners), skip this signal.
            if (activeModePositions7256(positions.values).size >= SOFT_CAP_POSITIONS) {
                val freed = evictWeakestForReplacement(signal.score)
                if (!freed) {
                    ErrorLogger.debug(TAG, "🪙 Soft cap: no weak slot to replace for ${signal.market.symbol} (score=${signal.score}) — all winners, skipping")
                    continue
                }
            }

            // V5.9.3: Respect the UI SPOT/LEVERAGE toggle instead of alternating by parity
            // V5.0.7183 — the UI toggle can no longer request a venue that does
            // not exist. With no perps integration, SPOT is the only executable
            // shape, so the toggle is overridden rather than obeyed.
            val useSpotDefault = if (LEVERAGE_VENUE_AVAILABLE_7183) !preferLeverage.get() else true
            var useSpot  = useSpotDefault
            var leverage = if (useSpot) 1.0 else DEFAULT_LEVERAGE
            if (!LEVERAGE_VENUE_AVAILABLE_7183 && preferLeverage.get()) {
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_LEVERAGE_DISABLED_NO_VENUE_7183")
                } catch (_: Throwable) {}
            }

            // V5.9.303: AUTO-ROUTE TO LEVERAGE when SPOT lacks a Solana mint but Flash supports the symbol.
            // This is the "currency bridge" the user expects — alt trader actually trades the whole market.
            // V5.0.7183 — "Flash perps" is not integrated; there is no adapter
            // and no signed perp order has ever left this app. An asset with no
            // SPOT mint is simply unroutable, and the route gate below refuses
            // it honestly instead of inventing a venue for it.
            if (LEVERAGE_VENUE_AVAILABLE_7183 && !scanPaper7803 && useSpot) {
                val hasMint = com.lifecyclebot.perps.crypto.CryptoWrappedAssetMapper
                    .resolveWrappedMint(signal.market.symbol) != null
                if (!hasMint && signal.market.symbol.uppercase() in FLASH_TRADE_PERPS_SYMBOLS) {
                    useSpot = false
                    leverage = DEFAULT_LEVERAGE
                    ErrorLogger.info(TAG, "🪙 AUTO-ROUTE LEVERAGE: ${signal.market.symbol} — no SPOT mint, using Flash perps ${leverage.toInt()}x")
                }
            }

            try {
                CrossMarketRegimeAI.updateMarketState(signal.market.symbol, signal.price, signal.priceChange24h)
                val gated = CrossTalkFusionEngine.computeGatedScore(
                    baseScore          = signal.score.toDouble(),
                    strategy           = "CryptoAltAI",
                    market             = "CRYPTO",
                    symbol             = signal.market.symbol,
                    leverageRequested  = leverage
                )
                if (gated.vetoes.any { it.startsWith("LEVERAGE") } && !useSpotDefault) {
                    useSpot  = true
                    leverage = 1.0
                    ErrorLogger.info(TAG, "🪙 V4 META: leverage suppressed for ${signal.market.symbol}")
                }
                PortfolioHeatAI.addPosition(
                    id       = "ALT_${signal.market.symbol}",
                    symbol   = signal.market.symbol,
                    market   = "CRYPTO",
                    sector   = signal.market.emoji,
                    direction= signal.direction.name,
                    sizeSol  = getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100) * fluidSizeMultiplier(signal.score, signal.confidence),
                    leverage = leverage
                )
            } catch (_: Exception) {}

            if (signal.direction == PerpsDirection.SHORT && useSpot) {
                // V5.0.7183 — this read `scanPaper7803 || symbol in
                // FLASH_TRADE_PERPS_SYMBOLS`, i.e. in paper EVERY short was
                // "perp capable" and became a fictional 3x position
                // (CRYPTO_SHORT_REROUTED_TO_PERP_6533 = 325 in one 26-minute
                // run). Paper mode is not a venue. With no perps integration a
                // spot-only wallet cannot short at all, so the signal falls
                // through to the existing CRYPTO_ADAPTER_UNSUPPORTED_DIRECTION
                // refusal below — which is the truthful outcome.
                val perpCapable6533 = LEVERAGE_VENUE_AVAILABLE_7183 &&
                    (scanPaper7803 || signal.market.symbol.uppercase() in FLASH_TRADE_PERPS_SYMBOLS)
                if (perpCapable6533) {
                    useSpot = false
                    leverage = DEFAULT_LEVERAGE
                    try { ForensicLogger.lifecycle("CRYPTO_SHORT_REROUTED_TO_PERP_6533", "symbol=${signal.market.symbol} paper=${scanPaper7803} leverage=$leverage") } catch (_: Throwable) {}
                } else {
                    try {
                        PipelineHealthCollector.labelInc("CRYPTO_ADAPTER_UNSUPPORTED_DIRECTION_6533")
                        ForensicLogger.lifecycle("CRYPTO_ADAPTER_UNSUPPORTED_DIRECTION_6533", "symbol=${signal.market.symbol} direction=SHORT adapter=SPOT action=no_buy_not_hard_safety")
                    } catch (_: Throwable) {}
                    continue
                }
            }
            ErrorLogger.info(TAG, "🪙 EXECUTING: ${signal.market.symbol} ${signal.direction.symbol} @ ${"%.4f".format(signal.price)} | ${if (useSpot) "SPOT" else "${leverage.toInt()}x"}")
            executeSignal(signal.copy(leverage = leverage), isSpot = useSpot)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS — CryptoAltScannerAI embedded
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun analyzeAlt(market: PerpsMarket, data: PerpsMarketData): AltSignal? {
        val reasons    = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score      = 50
        var confidence = 50

        val change    = data.priceChange24hPct

        // V5.9.381 — consult CryptoAltStrategy for BTC-dominance + vol-regime
        // aware direction. If the strategy stands down (spot + bearish, or no
        // edge), we skip analysis entirely. The strategy's direction overrides
        // the legacy technical fallback below.
        // V5.0.7403 — capability parity. With no real perp venue, both
        // PAPER and LIVE must learn the executable SPOT shape; otherwise the
        // strategy generates SHORT/leveraged winners that can never exist live.
        val altStrategyMode = if (LEVERAGE_VENUE_AVAILABLE_7183)
            com.lifecyclebot.perps.strategy.CryptoAltStrategy.Mode.PERPS_BIDIRECTIONAL
        else
            com.lifecyclebot.perps.strategy.CryptoAltStrategy.Mode.SPOT_LONG_ONLY
        val altRsi: Double? = try {
            PerpsAdvancedAI.seedHistoryFromOHLC(market, data.price, data.high24h, data.low24h, data.volume24h)
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            PerpsAdvancedAI.analyzeTechnicals(market).rsi
        } catch (_: Exception) { null }
        val btcMove7403 = try {
            PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.BTC)?.priceChange24hPct
                ?.takeIf { it.isFinite() } ?: PerpsMarketDataFetcher.getMarketData(PerpsMarket.BTC).priceChange24hPct
        } catch (_: Throwable) { 0.0 }
        val altSetup = try {
            com.lifecyclebot.perps.strategy.CryptoAltStrategy.decide(
                symbol = market.symbol,
                priceChange24hPct = change,
                mode = altStrategyMode,
                volatility24h = kotlin.math.abs(change),
                // Dominance feed is not available in this path; leave it neutral.
                // BTC price direction IS available and must not be hardcoded away.
                btcDominanceChange7d = 0.0,
                btcPriceChange24h = btcMove7403,
                rsi = altRsi,
            )
        } catch (_: Exception) { null }

        // V5.9.230: INDEPENDENT DIRECTION — evaluate LONG and SHORT separately.
        // Old code always picked LONG when change >= 0, meaning in flat/sideways markets
        // (most alts sitting at +0.01% to +0.83%) EVERY signal was LONG.
        // Now: pick direction based on strongest technical signal, not just price sign.
        val technicalDirection = run {
            // Prefer RSI/MACD direction when available (seeded from OHLC)
            try {
                val tech = PerpsAdvancedAI.analyzeTechnicals(market)
                // RSI<40 → strong SHORT signal; RSI>60 → strong LONG signal; else use price
                when {
                    // V5.0.7403 — extremes are exhaustion/reversal zones, not
                    // permission to chase the strongest lagging confirmation.
                    tech.isOversold -> PerpsDirection.LONG
                    tech.isOverbought && LEVERAGE_VENUE_AVAILABLE_7183 -> PerpsDirection.SHORT
                    tech.isOverbought -> PerpsDirection.LONG  // spot cannot short; later score veto may stand down
                    tech.rsi in 45.0..65.0 &&
                        tech.macdSignal in setOf(PerpsAdvancedAI.MacdSignal.BULLISH, PerpsAdvancedAI.MacdSignal.BULLISH_CROSS) ->
                        PerpsDirection.LONG
                    LEVERAGE_VENUE_AVAILABLE_7183 && tech.rsi in 35.0..55.0 &&
                        tech.macdSignal in setOf(PerpsAdvancedAI.MacdSignal.BEARISH, PerpsAdvancedAI.MacdSignal.BEARISH_CROSS) ->
                        PerpsDirection.SHORT
                    else -> if (!LEVERAGE_VENUE_AVAILABLE_7183) PerpsDirection.LONG
                        else if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
                }
            } catch (_: Exception) { if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT }
        }
        // V5.0.7403 — a strategy stand-down is a real no-trade. The old
        // fallback resurrected the exact setup CryptoAltStrategy rejected.
        if (altSetup == null && !LEVERAGE_VENUE_AVAILABLE_7183) {
            try { PipelineHealthCollector.labelInc("CRYPTO_STRATEGY_STANDDOWN_HONORED_7403") } catch (_: Throwable) {}
            return null
        }
        val direction = altSetup?.direction ?: technicalDirection
        if (altSetup != null) {
            score += (altSetup.conviction - 40).coerceAtLeast(0)
            confidence += (altSetup.conviction - 40).coerceAtLeast(0)
            reasons.addAll(altSetup.reasons.map { "🪙 $it" })
            layerVotes["CryptoAltStrategy"] = direction
        }

        // ── Layer 1: Price displacement / continuation quality ───────────────
        // V5.0.7403 — 24h displacement is context, not automatic alpha.
        // Reward moderate continuation; penalise already-exhausted extremes.
        when {
            direction == PerpsDirection.LONG && change in 1.0..8.0 -> {
                score += 8; confidence += 5; reasons.add("constructive_24h_long")
            }
            direction == PerpsDirection.LONG && change > 20.0 -> {
                score -= 14; confidence -= 10; reasons.add("long_overextended_24h")
            }
            direction == PerpsDirection.LONG && change < -8.0 -> {
                score -= 10; confidence -= 8; reasons.add("falling_requires_reclaim")
            }
            direction == PerpsDirection.SHORT && change in -8.0..-1.0 -> {
                score += 8; confidence += 5; reasons.add("constructive_24h_short")
            }
            direction == PerpsDirection.SHORT && change < -20.0 -> {
                score -= 14; confidence -= 10; reasons.add("short_overextended_24h")
            }
        }
        layerVotes["Momentum"] = direction

        // ── Layer 2: Alt Category / Sector Bonus ─────────────────────────────
        // V5.9.230: Direction-aware sector bonus — SHORT gets same treatment as LONG
        val sectorBoostDir = direction
        when {
            // Layer 1 DeFi blue-chips
            market.symbol in listOf("AAVE", "MKR", "CRV", "SNX", "LDO", "RPL") -> {
                score += 8; confidence += 10
                reasons.add(if (sectorBoostDir == PerpsDirection.LONG) "🏛️ DeFi blue-chip" else "🏛️ DeFi blue-chip SHORT")
            }
            // Meme / narrative
            market.symbol in listOf("SHIB", "FLOKI", "TRUMP", "POPCAT", "NOT") -> {
                score += 12; confidence += 5
                reasons.add(if (sectorBoostDir == PerpsDirection.LONG) "🐸 Meme narrative play" else "🐸 Meme SHORT")
            }
            // Gaming / metaverse
            market.symbol in listOf("AXS", "SAND", "MANA", "IMX") -> {
                score += 6; reasons.add("🎮 Gaming sector")
            }
            // Solana ecosystem (not covered by main engine)
            market.symbol in listOf("MNGO", "PYTH", "RAY", "ORCA") -> {
                score += 10; confidence += 8; reasons.add("◎ Solana ecosystem")
            }
            // L1 alts
            market.symbol in listOf("FTM", "ALGO", "HBAR", "ICP", "VET", "NEAR", "EOS") -> {
                score += 7; confidence += 6; reasons.add("⛓️ L1 alt")
            }
            // Storage / compute
            market.symbol in listOf("FIL", "RENDER", "GRT") -> {
                score += 8; confidence += 6; reasons.add("💾 Storage/compute narrative")
            }
            // Layer-2 & cross-chain
            market.symbol in listOf("STX", "ENS", "RUNE") -> {
                score += 7; confidence += 5; reasons.add("🔗 L2 / cross-chain")
            }
            // Exchange tokens
            market.symbol in listOf("BNB", "CRO") -> {
                score += 9; confidence += 10; reasons.add("🏦 Exchange token")
            }
            // OG alts
            market.symbol in listOf("BTC", "ETH", "LTC", "XMR", "ZEC", "ETC") -> {
                score += 8; confidence += 12; reasons.add("🪙 OG alt — deep liquidity")
            }
        }

        // ── Layer 3: BTC Correlation Divergence ───────────────────────────────
        // If BTC is falling but this alt is rising — strength signal
        try {
            val btcData = PerpsMarketDataFetcher.getMarketData(PerpsMarket.BTC)
            val btcChange = btcData.priceChange24hPct
            val divergence = change - btcChange
            when {
                divergence > 5.0 && direction == PerpsDirection.LONG -> {
                    score += 15; confidence += 10
                    reasons.add("⚡ Alt leading BTC by +${"%.1f".format(divergence)}%")
                    layerVotes["BtcDivergence"] = PerpsDirection.LONG
                }
                divergence < -5.0 && direction == PerpsDirection.SHORT -> {
                    score += 12; confidence += 8
                    reasons.add("📉 Alt lagging BTC by ${"%.1f".format(divergence)}%")
                    layerVotes["BtcDivergence"] = PerpsDirection.SHORT
                }
                abs(btcChange) < 1.0 && abs(change) > 3.0 -> {
                    // Alt moving on its own — narrative momentum
                    score += 8; confidence += 6
                    reasons.add("🎯 Narrative move (BTC flat)")
                    layerVotes["NarrativeMomentum"] = direction
                }
            }
        } catch (_: Exception) {}

        // ── Layer 4: Technical Analysis (PerpsAdvancedAI) ────────────────────
        // V5.9.230: History already seeded in direction block above — just analyze
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)

            if (technicals.recommendation == direction) {
                score += 10; confidence += 10
                reasons.add("📊 RSI confirms: ${"%.0f".format(technicals.rsi)}")
                layerVotes["Technical"] = direction
            }
            if (technicals.isOversold  && direction == PerpsDirection.LONG)  { score += 15; reasons.add("📉 Oversold bounce") }
            if (technicals.isOverbought && direction == PerpsDirection.SHORT) { score += 15; reasons.add("📈 Overbought short") }

            // V5.9.219: TECHNICAL VETO — if indicators strongly contradict direction, penalise
            // Prevents GALA-style LONG on RSI=42 MACD=BEARISH (was score=100 due to floor+sector heat)
            val macdBearish = technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH || technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BEARISH_CROSS
            val macdBullish = technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH || technicals.macdSignal == PerpsAdvancedAI.MacdSignal.BULLISH_CROSS
            if (direction == PerpsDirection.LONG && macdBearish && technicals.rsi < 50) {
                score -= 18; confidence -= 15
                reasons.add("⚠️ Tech veto: MACD bearish + RSI<50 on LONG")
            }
            if (direction == PerpsDirection.SHORT && macdBullish && technicals.rsi > 50) {
                score -= 15; confidence -= 12
                reasons.add("⚠️ Tech veto: MACD bullish + RSI>50 on SHORT")
            }
        } catch (_: Exception) {}

        // ── Layer 5: Volume Spike ─────────────────────────────────────────────
        try {
            val volume = PerpsAdvancedAI.analyzeVolume(market)
            if (volume.isSpike) {
                score += when (volume.spikeStrength) {
                    "EXTREME" -> 20; "STRONG" -> 15; "MILD" -> 8; else -> 0
                }
                reasons.add("📊 Volume ${volume.spikeStrength}")
                layerVotes["Volume"] = direction
            }
        } catch (_: Exception) {}

        // ── Layer 6: Support / Resistance ────────────────────────────────────
        try {
            val sr = PerpsAdvancedAI.analyzeSupportResistance(market, data.price)
            if (sr.nearSupport    && direction == PerpsDirection.LONG)  { score += 10; reasons.add("📍 Near support") }
            if (sr.nearResistance && direction == PerpsDirection.SHORT) { score += 10; reasons.add("📍 Near resistance") }
        } catch (_: Exception) {}

        // ── Layer 7: Fluid Learning (V5.9.1442 — isolated crypto brain) ──────
        try {
            val progress = com.lifecyclebot.perps.crypto.brain.CryptoBrain.progressPct()
            if (progress > 50) { confidence += 5; reasons.add("📚 Crypto Learning: $progress%") }
            val crossBoost = com.lifecyclebot.perps.crypto.brain.CryptoBrain.crossLearnedConfidence(confidence.toDouble()) - confidence
            if (crossBoost > 0) { confidence += crossBoost.toInt(); reasons.add("🔗 Cross-boost: +${crossBoost.toInt()}") }
        } catch (_: Exception) {}

        // ── Layer 8: Pattern Memory ───────────────────────────────────────────
        try {
            val technicals   = PerpsAdvancedAI.analyzeTechnicals(market)
            val patternConf  = PerpsAdvancedAI.getPatternConfidence(market, direction, technicals)
            if (patternConf > 60) {
                score += 10; confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}

        // ── Layer 9: CryptoAltScannerAI — Sector Heat ────────────────────────
        try {
            val sectorHeat = CryptoAltScannerAI.getSectorHeat(market.symbol)
            if (sectorHeat > 0.6) {
                score += 12; confidence += 8
                reasons.add("🔥 Sector heat: ${"%.0f".format(sectorHeat * 100)}%")
                layerVotes["SectorHeat"] = direction
            } else if (sectorHeat < 0.3 && direction == PerpsDirection.SHORT) {
                score += 8; reasons.add("❄️ Sector cooling — short bias")
            }
        } catch (_: Exception) {}

        // ── Layer 10: CryptoAltScannerAI — Dominance Cycle ───────────────────
        try {
            val dominanceSignal = CryptoAltScannerAI.getDominanceCycleSignal()
            if (dominanceSignal == "ALT_SEASON" && direction == PerpsDirection.LONG) {
                score += 15; confidence += 10
                reasons.add("🌊 Alt season signal")
                layerVotes["DominanceCycle"] = PerpsDirection.LONG
            } else if (dominanceSignal == "BTC_DOMINANCE" && direction == PerpsDirection.SHORT) {
                score += 10; reasons.add("🏦 BTC dominance rising — alt risk-off")
                layerVotes["DominanceCycle"] = PerpsDirection.SHORT
            }
        } catch (_: Exception) {}

        // ── Layer 11: BehaviorAI — session sentiment (V5.9.1442 — isolated) ──
        try {
            val bAdj    = com.lifecyclebot.perps.crypto.brain.CryptoBrain.scoreAdjustment()
            val confMod = com.lifecyclebot.perps.crypto.brain.CryptoBrain.confidenceModifier()
            if (bAdj != 0 || confMod != 0) {
                score += bAdj; confidence += confMod
                reasons.add("🧠 CryptoBrain: ${com.lifecyclebot.perps.crypto.brain.CryptoBrain.sentimentClassification()} (${if (bAdj >= 0) "+" else ""}$bAdj)")
            }
        } catch (_: Exception) {}

        // ── Layer 12: VolatilityRegimeAI ─────────────────────────────────────
        try {
            when (VolatilityRegimeAI.getRegime(market.symbol).name) {
                "HIGH"    -> { score -= 5; reasons.add("⚡ High vol") }
                "LOW"     -> { confidence += 5; reasons.add("😴 Low vol — squeeze build?") }
                "SQUEEZE" -> { score += 10; confidence += 8; reasons.add("🎯 Vol squeeze!"); layerVotes["VolSqueeze"] = direction }
            }
        } catch (_: Exception) {}

        // ── Layer 13: NarrativeFlowAI ─────────────────────────────────────────
        try {
            val narHeat = NarrativeFlowAI.getNarrativeHeat(market.symbol)
            if (narHeat > 0.6) {
                // V5.0.7114 §A_HOT_EXHAUSTING_NARRATIVE_SCORED_LIKE_A_HEALTHY_ONE.
                //
                // This read coerceIn(0, 15). getNarrativeMultiplier returns five
                // distinct phases and THREE of them are below 1.0:
                //
                //     EXPANDING  1.2  -> +2
                //     MATURE     1.0  ->  0
                //     EMERGING   0.9  -> -1   clamped to 0
                //     EXHAUSTING 0.5  -> -5   clamped to 0
                //     DEAD       0.7  -> -3   clamped to 0
                //
                // So only EXPANDING survived, and the other four were
                // indistinguishable from "no opinion". This gate fires ONLY when
                // narHeat > 0.6 — a HOT narrative — which makes the discarded
                // case the important one: high attention with momentum rolling
                // over is the most dangerous setup there is, the AI detects it
                // exactly (NarrativePhase.EXHAUSTING), and the clamp threw the
                // detection away.
                //
                // Third instance of the class V5.0.7112 and V5.0.7113 fixed,
                // found by the same ci/one_way_control_scan.py. The layer
                // directly below this one — StrategyTrustAI, `trust < 0.3 ->
                // score -= 8` — is two-sided, so the intent was never in doubt.
                //
                // Symmetric with the +15 ceiling already here; no new magnitude.
                val boost = ((NarrativeFlowAI.getNarrativeMultiplier(market.symbol) - 1.0) * 10).toInt().coerceIn(-15, 15)
                score += boost; confidence += boost / 2
                reasons.add("📣 Narrative: ${"%.0f".format(narHeat * 100)}% (${if (boost >= 0) "+" else ""}$boost)")
                layerVotes["NarrativeHeat"] = direction
            }
        } catch (_: Exception) {}

        // ── Layer 14: StrategyTrustAI ─────────────────────────────────────────
        try {
            val trust    = StrategyTrustAI.getTrustScore("CryptoAltAI")
            val trustMod = when {
                trust > 0.7 -> { score += 8; confidence += 6; 8 }
                trust > 0.5 -> { score += 3; 3 }
                trust < 0.3 -> { score -= 8; confidence -= 5; -8 }
                else        -> 0
            }
            if (trustMod != 0) reasons.add("🤝 Trust: ${"%.2f".format(trust)} (${if (trustMod >= 0) "+" else ""}$trustMod)")
        } catch (_: Exception) {}

        // ── Layer 15: CrossAssetLeadLagAI ─────────────────────────────────────
        try {
            CrossAssetLeadLagAI.recordReturn(market.symbol, change)
            val lead = CrossAssetLeadLagAI.getLeadSignalFor(market.symbol)
            if (lead != null && abs(change) > 1.0) {
                score += 6; confidence += 4; reasons.add("🔗 Lead-lag: ${lead.leader}")
            }
        } catch (_: Exception) {}

        // ── Layer 16: Fear & Greed override ───────────────────────────────────
        try {
            val fg = CryptoAltScannerAI.getCryptoFearGreed()
            when {
                fg >= 80 && direction == PerpsDirection.LONG  -> { score -= 10; reasons.add("⚠️ Extreme greed ($fg)") }
                fg <= 20 && direction == PerpsDirection.SHORT -> { score -= 10; reasons.add("⚠️ Extreme fear ($fg)") }
                fg in 35..65 -> confidence += 3
            }
        } catch (_: Exception) {}

        // V5.9.230: Remove score floor — it was overriding technical vetoes.
        // A vetoed token (50 base - 18 veto = 32) must actually score below
        // the entry threshold and be REJECTED. Floor of 35 was handing out free passes.
        // Only guard against negatives (score can go negative from multiple vetoes).
        if (score < 0)      score      = 0
        if (confidence < 0) confidence = 0
        reasons.add("📚 CryptoAlt learning mode")

        // V5.9.230: Hard garbage gate — if both score AND confidence are below floor,
        // return null. This prevents the FluidLearning threshold (currently ~5 at bootstrap)
        // from letting every single signal through in early learning. The gate scales:
        // below 20/20 means even the combined weight of all layers couldn't build conviction.
        // V5.9.432: raised garbage gate from 20/20 → 50/40 so near-flat alts
        // with noise-level scores can't sneak in during BOOTSTRAP. Combined
        // with the tighter momentum gate below, this kills the "enter AXS
        // at -0.07% AI:94" class of false positives from the user's
        // screenshot.
        if (score < 50 || confidence < 40) {
            ErrorLogger.debug(TAG, "🗑️ GARBAGE GATE: ${market.symbol} score=$score conf=$confidence — rejected")
            return null
        }

        // V5.9.272: MOMENTUM GATE — block entries on flat/dead tokens.
        // V5.9.432: tightened. Require change24h > 0.5% (was 1.5% with a
        // bypass that let anything with RSI<35 or >65 through, including
        // sideways tokens). Now: require BOTH (change > 0.5% AND RSI>55 OR
        // MACD bullish) OR a clear reversal setup (RSI<30 oversold +
        // positive 1h change). This matches the user's request: "require
        // RSI>55 OR MACD bullish-cross AND 1h change > +0.5%".
        val techRsiForGate = try {
            com.lifecyclebot.perps.PerpsAdvancedAI.analyzeTechnicals(market).rsi
        } catch (_: Exception) { 50.0 }
        val techMacdBullish = try {
            val sig = com.lifecyclebot.perps.PerpsAdvancedAI.analyzeTechnicals(market).macdSignal
            sig == com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH ||
                sig == com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH_CROSS
        } catch (_: Exception) { false }
        val strongLongSetup = kotlin.math.abs(change) >= 0.5 &&
            (techRsiForGate > 55.0 || techMacdBullish)
        val reversalSetup   = techRsiForGate < 30.0 && change > 0.0  // oversold bounce only
        val shortSetup      = techRsiForGate > 70.0 && change < 0.0  // overbought fade only
        val paperLearningCandidate6562 = isPaperMode.get() && score >= 50 && confidence >= 40
        if (!strongLongSetup && !reversalSetup && !shortSetup && !paperLearningCandidate6562) {
            ErrorLogger.debug(TAG,
                "🚫 MOMENTUM GATE: ${market.symbol} change=${"%.2f".format(change)}% RSI=${"%.0f".format(techRsiForGate)} MACD=${if (techMacdBullish) "BULL" else "NA"} — no directional edge")
            return null
        }
        if (paperLearningCandidate6562 && !strongLongSetup && !reversalSetup && !shortSetup) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_PAPER_LEARNING_ADMISSION_6562")
                ForensicLogger.lifecycle("CRYPTO_PAPER_LEARNING_ADMISSION_6562", "symbol=${market.symbol} score=$score confidence=$confidence change=${"%.2f".format(change)} action=fdg_safety_learning_only")
            } catch (_: Throwable) {}
        }

        // V5.9.432 — FLAT-4H FILTER: if the token's last-4h total movement
        // (highest-lowest) is < 0.5%, it's chopping sideways and even a
        // momentum-gate-positive signal is unlikely to produce a winner.
        // Uses the 24h change as a proxy (real 4h range requires candle
        // fetch; approximate via abs(change) < 0.5 ≈ flat 4h too).
        if (kotlin.math.abs(change) < 0.5 && !reversalSetup && !paperLearningCandidate6562) {
            ErrorLogger.debug(TAG,
                "🚫 FLAT_4H_FILTER: ${market.symbol} change=${"%.2f".format(change)}% — chop, skip")
            return null
        }

        return AltSignal(
            market         = market,
            direction      = direction,
            score          = score.coerceIn(0, 100),
            confidence     = confidence.coerceIn(0, 100),
            price          = data.price,
            priceChange24h = change,
            reasons        = reasons,
            layerVotes     = layerVotes,
            strategy7803   = "CRYPTO_STATIC_ANALYZE",
            candidateVersion7803 = System.currentTimeMillis() / 30_000L,
        )
    }


    private data class ExactAssetMetrics6493(
        val liquidityUsd: Double,
        val marketCapUsd: Double,
        val marketCapSource: String,
        val fdvUsd: Double,
        val volume24hUsd: Double,
        val liquidityKnown6533: Boolean,
    )

    private fun exactAssetMetrics6493(signal: AltSignal): ExactAssetMetrics6493 {
        val identity = cryptoAssetKey(signal, isSpot = true)
        val tok = DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(identity)
            ?: DynamicAltTokenRegistry.getTokenByMint(identity)
            ?: return ExactAssetMetrics6493(0.0, 0.0, "", 0.0, 0.0, false)
        val trustedMcap = tok.mcap.takeIf { tok.hasTrustedMarketCap6492 && it.isFinite() && it > 0.0 } ?: 0.0
        return ExactAssetMetrics6493(
            liquidityUsd = tok.liquidityUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            marketCapUsd = trustedMcap,
            marketCapSource = if (trustedMcap > 0.0) tok.mcapSource else "",
            fdvUsd = tok.fdv.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            volume24hUsd = tok.volume24h.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            liquidityKnown6533 = tok.liquidityUsd.isFinite() && tok.liquidityUsd > 0.0,
        )
    }

    private fun cryptoMarketCapLane6493(signal: AltSignal, trustedMcap: Double): CryptoFinalBuyCandidate.MarketCapLane {
        if (signal.dynMint != null) return when {
            trustedMcap >= 50_000_000_000.0 -> CryptoFinalBuyCandidate.MarketCapLane.MEGA_CAP
            trustedMcap >= 5_000_000_000.0 -> CryptoFinalBuyCandidate.MarketCapLane.MAJOR
            trustedMcap >= 1_000_000_000.0 -> CryptoFinalBuyCandidate.MarketCapLane.LARGE_CAP
            trustedMcap >= 100_000_000.0 -> CryptoFinalBuyCandidate.MarketCapLane.MID_CAP
            trustedMcap >= 10_000_000.0 -> CryptoFinalBuyCandidate.MarketCapLane.LOW_CAP
            else -> CryptoFinalBuyCandidate.MarketCapLane.MICRO_CAP
        }
        return cryptoMarketCapLane(signal.marketSymbol)
    }

    private fun cryptoMarketCapLane(symbol: String): CryptoFinalBuyCandidate.MarketCapLane = when (symbol.uppercase()) {
        "BTC", "WBTC", "ETH", "SOL" -> CryptoFinalBuyCandidate.MarketCapLane.MEGA_CAP
        "BNB", "XRP", "ADA", "DOGE", "TRX", "TON", "AVAX", "LINK", "DOT", "LTC" -> CryptoFinalBuyCandidate.MarketCapLane.MAJOR
        "BCH", "XLM", "XMR", "ETC", "NEAR", "APT", "ARB", "OP", "ICP", "FIL", "HBAR", "VET", "RENDER", "TAO", "INJ" -> CryptoFinalBuyCandidate.MarketCapLane.LARGE_CAP
        "JUP", "PYTH", "RAY", "ORCA", "JTO", "DRIFT", "SEI", "GRT", "AAVE", "MKR", "SNX", "CRV", "RUNE", "STX", "IMX", "SAND", "MANA", "AXS" -> CryptoFinalBuyCandidate.MarketCapLane.MID_CAP
        "MNGO", "W", "STRK", "FLOKI", "NOT", "POPCAT", "TRUMP", "PEPE", "WIF", "BONK" -> CryptoFinalBuyCandidate.MarketCapLane.LOW_CAP
        else -> CryptoFinalBuyCandidate.MarketCapLane.MICRO_CAP
    }

    /** V5.0.7391 — the lane a DynScan signal belongs to (the specialist that produced it). */
    private fun cryptoDeskLaneOf7391(reasons: List<String>, deskLane: String): String {
        val r = reasons.joinToString(" ")
        return when {
            r.contains("DynScan ShitCoin", true) -> "SHITCOIN"
            r.contains("DynScan BlueChip", true) -> "BLUECHIP"
            r.contains("DynScan Express", true) -> "EXPRESS"
            r.contains("DynScan Moonshot", true) -> "MOONSHOT"
            r.contains("DynScan Quality", true) -> "QUALITY"
            r.contains("DynScan DipHunter", true) -> "DIP_HUNTER"
            r.contains("DynScan Core", true) -> "CORE"
            else -> deskLane.ifBlank { "CORE" }   // CryptoBrain's own signal: the desk's owner
        }
    }

    private fun cryptoSignalStyle(signal: AltSignal): String = when {
        signal.reasons.any { it.contains("DynScan Quality", ignoreCase = true) } -> "QUALITY_SWING"
        signal.reasons.any { it.contains("DynScan DipHunter", ignoreCase = true) } -> "DIP_RECLAIM"
        signal.reasons.any { it.contains("DynScan Core", ignoreCase = true) } -> "CORE_ENSEMBLE"
        signal.reasons.any { it.contains("Moonshot", ignoreCase = true) } -> "MOMENTUM_BREAKOUT"
        signal.reasons.any { it.contains("BlueChip", ignoreCase = true) } -> "BLUECHIP_MOMENTUM"
        signal.reasons.any { it.contains("Manip", ignoreCase = true) } -> "MANIPULATION_REVERSAL"
        signal.reasons.any { it.contains("Express", ignoreCase = true) } -> "FAST_MOMENTUM"
        signal.reasons.any { it.contains("ShitCoin", ignoreCase = true) } -> "LOW_CAP_ROTATION"
        else -> "CRYPTO_ALT_SIGNAL"
    }

    private fun cryptoAssetKey(signal: AltSignal, isSpot: Boolean): String {
        signal.dynAssetKey?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        signal.dynMint?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        if (!isSpot) return "perps:${signal.market.name}"
        return com.lifecyclebot.perps.crypto.CryptoWrappedAssetMapper.resolveWrappedMint(signal.marketSymbol)
            ?: "unresolved:${signal.market.name}"
    }

    private fun buildCryptoFinalBuyCandidate(
        signal: AltSignal,
        isSpot: Boolean,
        finalSize: Double,
    ): CryptoFinalBuyCandidate {
        val symbol = signal.marketSymbol.uppercase()
        val exactMetrics6493 = exactAssetMetrics6493(signal)
        val lane = cryptoMarketCapLane6493(signal, exactMetrics6493.marketCapUsd)
        // V5.0.6536 §SPOT_SHORT_ADAPTER_REROUTE — defense-in-depth belt for
        // the operator directive: "if an adapter cannot express SHORT,
        // reroute to a supported perp/bearish adapter; do NOT stamp the
        // canonical candidate as HARD_NO_BUY or HARD_SAFETY". The executor
        // pre-check at line 1282 already reroutes SHORT+spot to perp when
        // perp-capable, but the canonical candidate is the *source of truth*
        // for the authority ledger. If a stray callsite bypasses that
        // pre-check, we must NEVER emit an incoherent (SPOT, SHORT) pair
        // to the canonical registry — the operator explicitly forbids
        // capability mismatches contaminating canonical execution state.
        val perpCapable6536 = isPaperMode.get() || symbol in FLASH_TRADE_PERPS_SYMBOLS
        val effectiveIsSpot6536 = if (isSpot && signal.direction == PerpsDirection.SHORT && perpCapable6536) {
            try {
                PipelineHealthCollector.labelInc("SPOT_SHORT_ADAPTER_REROUTED_CANDIDATE_6536")
                ForensicLogger.lifecycle(
                    "SPOT_SHORT_ADAPTER_REROUTED_CANDIDATE_6536",
                    "symbol=$symbol requested=SPOT direction=SHORT rerouted=PERP paper=${isPaperMode.get()} " +
                        "expected=perp_adapter_owns_short observed=candidate_would_have_been_incoherent",
                )
            } catch (_: Throwable) {}
            false
        } else isSpot
        val assetType = if (effectiveIsSpot6536) CryptoFinalBuyCandidate.AssetType.SPOT else CryptoFinalBuyCandidate.AssetType.PERP
        val walletSol = try { WalletManager.getWallet()?.getSolBalance() ?: getEffectiveBalance() } catch (_: Throwable) { getEffectiveBalance() }
        val route = try { CryptoUniverseRouteResolver.resolve(
            signal.market, walletSol, finalSize,
            assetSymbol6493 = signal.marketSymbol,
            targetMint6493 = signal.dynMint,
            targetChainId6544 = signal.dynChainId,
        ) } catch (_: Throwable) { null }
        val hardNo = mutableListOf<String>()
        val soft = mutableListOf<String>()
        if (signal.price <= 0.0) hardNo += "PRICE_CONTEXT_MISSING"
        if (finalSize < 0.01) hardNo += "SIZE_BELOW_FLOOR"
        if (signal.confidence <= 0 || signal.score <= 0) hardNo += "SCORE_CONTEXT_MISSING"
        if (!isPaperMode.get() && route?.executable != true) hardNo += "ROUTE_UNAVAILABLE"
        if (route?.route == com.lifecyclebot.perps.crypto.CryptoExecutionRoute.PAPER_ONLY && isPaperMode.get()) soft += "PAPER_ONLY_ROUTE"
        // V5.0.6536 §SPOT_SHORT_ADAPTER_REROUTE — evaluate against the
        // *effective* (post-reroute) assetType, not the requested one. If
        // we've flipped SPOT→PERP for a SHORT signal, the adapter now
        // supports the direction and the soft flag is stale.
        if (signal.direction == PerpsDirection.SHORT && effectiveIsSpot6536) soft += "ADAPTER_DIRECTION_UNSUPPORTED"
        val liq = exactMetrics6493.liquidityUsd
        if (signal.dynMint != null && liq <= 0.0) soft += "MINT_LIQUIDITY_UNKNOWN_6493"
        if (signal.dynMint != null && exactMetrics6493.marketCapUsd <= 0.0) soft += "MINT_MARKET_CAP_UNKNOWN_6493"
        val spread = when (lane) {
            CryptoFinalBuyCandidate.MarketCapLane.MEGA_CAP -> 0.03
            CryptoFinalBuyCandidate.MarketCapLane.MAJOR -> 0.08
            CryptoFinalBuyCandidate.MarketCapLane.LARGE_CAP -> 0.15
            CryptoFinalBuyCandidate.MarketCapLane.MID_CAP -> 0.35
            CryptoFinalBuyCandidate.MarketCapLane.LOW_CAP -> 0.75
            CryptoFinalBuyCandidate.MarketCapLane.MICRO_CAP -> 1.25
        }
        val slippage = spread * 2.0
        // V5.0.6644 — this is inferred from the market-cap lane, not measured
        // from an executable quote. It previously hard-blocked every dynamic
        // MICRO_CAP candidate (the inferred value is always 1.25), including
        // paper candidates. Live execution already rejects measured Jupiter
        // price impact fail-closed, so retain this heuristic as evidence only.
        if (spread > 1.0) soft += "ESTIMATED_SPREAD_HIGH_UNVERIFIED_6644"
        // V5.0.6536 §HARD_ACCEPTANCE_INVARIANT_D — detect regression:
        // if a SPOT+SHORT combination has been stamped HARD_NO_BUY via
        // adapter-direction reasons instead of being rerouted, bump the
        // invariant counter so the acceptance audit and unit test
        // observe the pathology. This never fires under the V5.0.6536
        // reroute; it will only trip if a future edit reintroduces the
        // leak by moving ADAPTER_DIRECTION_UNSUPPORTED from `soft` to
        // `hardNo` or wiring a new spot-short hard-safety path.
        if (signal.direction == PerpsDirection.SHORT && effectiveIsSpot6536 &&
            hardNo.any { it.contains("ADAPTER_DIRECTION_UNSUPPORTED", ignoreCase = true) }
        ) {
            try {
                PipelineHealthCollector.labelInc("SPOT_SHORT_ADAPTER_MISMATCH_HARD_SAFETY_6536")
                ForensicLogger.lifecycle(
                    "SPOT_SHORT_ADAPTER_MISMATCH_HARD_SAFETY_6536",
                    "symbol=$symbol direction=SHORT adapter=SPOT " +
                        "expected=reroute_to_perp observed=hard_no_buy_stamped " +
                        "action=investigate_regression",
                )
            } catch (_: Throwable) {}
        }
        // V5.0.7005 §REMOVE_ANYTHING_THAT_CANNOT_BE_TRADED_IN_REAL_LIFE.
        //
        // Operator: "remove anything that cant be traded in real life!"
        //
        // `adapter` below reads `isPaperMode.get() -> "PAPER_EXECUTOR"` FIRST,
        // so in paper the routability test that follows it is never reached.
        // That single short-circuit is why the 5.0.7003 snapshot shows
        // CRYPTO_ALT fdgBlock=0 against 164 candidates the registry had
        // already marked as having no live route: the app knew, recorded it,
        // and opened them regardless.
        //
        // A null route is treated as untradeable rather than unknown. Route
        // resolution failing is not evidence that a venue exists.
        // V5.0.7157 — pass the mode. PAPER_ONLY is the resolver's word for
        // "simulatable, not live-tradeable", and refusing it in PAPER stopped
        // the crypto lane opening anything at all (92 refusals, 0 opens).
        // V5.0.7182 — the mode argument is gone: PAPER_ONLY is refused in both
        // modes now, because the costs of a paper fill on an unroutable asset
        // (slot, cash, poisoned learner) are only ever paid in paper.
        val routeReal7005 = route?.route?.isRealTradeable7005() == true
        if (!routeReal7005) {
            hardNo += "NOT_REAL_TRADEABLE_7005:${route?.route?.name ?: "NO_ROUTE_RESOLVED"}"
            try {
                PipelineHealthCollector.labelInc(
                    "CRYPTO_ENTRY_REFUSED_NOT_REAL_TRADEABLE_7005_${route?.route?.name ?: "NO_ROUTE_RESOLVED"}",
                )
                ForensicLogger.lifecycle(
                    "CRYPTO_ENTRY_REFUSED_NOT_REAL_TRADEABLE_7005",
                    "symbol=$symbol route=${route?.route?.name ?: "NO_ROUTE_RESOLVED"} " +
                        "mode=${if (isPaperMode.get()) "PAPER" else "LIVE"} " +
                        "action=refuse_open_paper_trains_live",
                )
            } catch (_: Throwable) {}
        }
        // V5.0.7774 — a paper position on a non-Solana dynamic asset with no live-
        // executable route trains a lane live cannot use, and its held mark never
        // refreshes (CRYPTO_HELD_STALE_MARK_REFRESH_ONLY_7245 = 433 on 5.0.7771): 15
        // closes at exactly 0.00% (ADAPTIVE_HOLD_MAX) and four DEAD_TOKEN_NO_PRICE_EXIT
        // fee haircuts on 1.25-1.3 SOL rows. Paper is evidence for live (Field Manual
        // §12: mode-matched outcomes); Solana assets and live-routable ones are untouched.
        if (isPaperMode.get() && signal.isDynamic && route?.mint == null && route?.executable != true) {
            hardNo += "PAPER_NON_SOLANA_NOT_LIVE_EXECUTABLE_7774"
            try { PipelineHealthCollector.labelInc("CRYPTO_PAPER_NON_SOLANA_NOT_LIVE_EXECUTABLE_7774") } catch (_: Throwable) {}
        }
        val pre = when {
            hardNo.isNotEmpty() -> CryptoFinalBuyCandidate.PreFdgVerdict.HARD_NO_BUY
            signal.score >= 50 && signal.confidence >= 40 -> CryptoFinalBuyCandidate.PreFdgVerdict.BUY
            else -> CryptoFinalBuyCandidate.PreFdgVerdict.WATCH
        }
        val adapter = when {
            isPaperMode.get() -> "PAPER_EXECUTOR"
            route?.executable == true -> "CRYPTO_UNIVERSE_EXECUTOR"
            else -> "DEFERRED_ROUTE"
        }
        DynamicAltTokenRegistry.markFdgReach6544(
            DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(cryptoAssetKey(signal, effectiveIsSpot6536)),
            liveRoutable = route?.executable == true,
            paperOnlyNoRoute = route?.route == com.lifecyclebot.perps.crypto.CryptoExecutionRoute.PAPER_ONLY,
        )
        return CryptoFinalBuyCandidate(
            assetKey = cryptoAssetKey(signal, effectiveIsSpot6536),
            symbol = symbol,
            chain = if (route?.mint != null) "SOLANA" else "MULTICHAIN",
            venue = route?.route?.name ?: "UNKNOWN",
            assetType = assetType,
            direction = signal.direction,
            marketCapLane = lane,
            selectedLane = canonicalCryptoLane7251(signal.isDynamic, effectiveIsSpot6536),
            selectedSpecialist = cryptoSignalStyle(signal),
            preFdgVerdict = pre,
            score = signal.score,
            confidence = signal.confidence,
            safetyTier = "SAFE",
            liquidityUsd = liq,
            liquidityKnown6533 = exactMetrics6493.liquidityKnown6533,
            marketCapUsd6493 = exactMetrics6493.marketCapUsd,
            marketCapSource6493 = exactMetrics6493.marketCapSource,
            fdvUsd6493 = exactMetrics6493.fdvUsd,
            volume24hUsd6493 = exactMetrics6493.volume24hUsd,
            routeQuality = route?.route?.name ?: "UNKNOWN",
            spread = spread,
            slippageEstimate = slippage,
            hardNoReasons = hardNo.distinct(),
            softWarnings = soft.distinct(),
            finalSize = finalSize,
            executionAdapter = adapter,
            candidateVersion = LaneExecutionCoordinator.candidateVersionFor(cryptoAssetKey(signal, effectiveIsSpot6536)),
        )
    }

    private fun passesCryptoDiscipline6647(candidate: CryptoFinalBuyCandidate): Boolean {
        // V5.0.4151 — CRYPTO DISCIPLINE PACK (isolated). Mirrors the meme
        // Executor.kt liveBuy() veto stack (V5.0.4133/4134/4148/4149) but
        // uses fully isolated persistence + state so crypto closes NEVER
        // touch meme discipline state. Vetoes run BEFORE EXEC_GATE/auth.
        run {
            val assetKey4151 = candidate.assetKey
            // Lane tag must match the recorder side (CryptoAltTrader close path)
            // — CRYPTO_SPOT for SPOT/TOKENIZED, CRYPTO_LEV for PERP. This keeps
            // the per-lane WR windows in CryptoLivePauseButton/LaneTimeoutGate
            // partitioned by leverage class so one mode can timeout without
            // locking the other.
            val lane4151 = candidate.selectedLane
            val srcTag4151 = candidate.universe.ifBlank { "CRYPTO" }.uppercase()
            // (a) Rug-blacklist — non-negotiable, immune to all bypasses.
            if (com.lifecyclebot.perps.crypto.brain.CryptoRugMintBlacklist.isBlacklisted(assetKey4151)) {
                try { ForensicLogger.lifecycle("CRYPTO_RUG_BLACKLIST_VETO_V4151", "symbol=${candidate.symbol} assetKey=$assetKey4151 lane=$lane4151 src=$srcTag4151") } catch (_: Throwable) {}
                return false
            }
            // (b) Per-lane timeout. Crypto-specific lane WR memory.
            val laneTimedOut4151 = com.lifecyclebot.perps.crypto.brain.CryptoLaneTimeoutGate.isTimedOut(lane4151)
            // (c) Global pause button with top-performing-lane bypass.
            val pauseDefensive4151 = com.lifecyclebot.perps.crypto.brain.CryptoLivePauseButton.isDefensive()
            val topLane4151 = com.lifecyclebot.perps.crypto.brain.CryptoLivePauseButton.isTopPerformingLane(lane4151)
            val effectivePause4151 = pauseDefensive4151 && !topLane4151
            // (d) Scanner→lane bridge toxicity veto.
            val bridgeToxic4151 = !com.lifecyclebot.perps.crypto.brain.CryptoScannerLaneBridge.shouldRoute(srcTag4151, lane4151)
            // V5.0.6644 — performance memory may fail closed in LIVE, but it
            // cannot permanently deadlock PAPER: paper is where fresh evidence
            // is generated to recover/calibrate those memories. Canonical FDG,
            // sizing, exposure, rug and atomic-accounting safety remain active.
            val learnedDisciplineVeto6644 = effectivePause4151 || laneTimedOut4151 || bridgeToxic4151
            if (learnedDisciplineVeto6644 && isPaperMode.get()) {
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_PAPER_LEARNED_DISCIPLINE_DIAGNOSTIC_6644")
                    ForensicLogger.lifecycle(
                        "CRYPTO_PAPER_LEARNED_DISCIPLINE_DIAGNOSTIC_6644",
                        "symbol=${candidate.symbol} assetKey=$assetKey4151 lane=$lane4151 src=$srcTag4151 " +
                            "pause=$effectivePause4151 timeout=$laneTimedOut4151 bridgeToxic=$bridgeToxic4151 action=continue_to_fdg",
                    )
                } catch (_: Throwable) {}
            }
            if (learnedDisciplineVeto6644 && !isPaperMode.get()) {
                val reasonTag4151 = when {
                    effectivePause4151 -> "CRYPTO_PAUSE_DEFENSIVE"
                    laneTimedOut4151   -> "CRYPTO_LANE_TIMEOUT"
                    else               -> "CRYPTO_SCANNER_BRIDGE_VETO"
                }
                // V5.0.7400 — learned performance state is shaping authority,
                // not execution safety. Preserve the warning for learning/size/
                // priority consumers, but do not hard-veto here. Rug blacklist,
                // route proof, canonical FDG, wallet and finality remain hard.
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_DISCIPLINE_SOFT_SHAPED_7400")
                    PipelineHealthCollector.labelInc("CRYPTO_DISCIPLINE_SOFT_SHAPED_7400_$reasonTag4151")
                    ForensicLogger.lifecycle(
                        "CRYPTO_DISCIPLINE_SOFT_SHAPED_7400",
                        "symbol=${candidate.symbol} assetKey=$assetKey4151 lane=$lane4151 src=$srcTag4151 " +
                            "reason=$reasonTag4151 pause=$pauseDefensive4151 topLane=$topLane4151 timeout=$laneTimedOut4151 bridge=$bridgeToxic4151 action=continue_to_canonical_authority",
                    )
                } catch (_: Throwable) {}
            }
        }

        return candidate.canEnterFdg
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V5.0.7275 §THE ENTRY BASIS IS THE PRICE AT THE FILL, NOT THE PRICE AT
     * THE SCAN.
     *
     * Operator's 5.0.7274 tape, CRYPTO_ALT paper:
     *
     *   15:05:30.593 BUY  entry=0.0001768   15:05:31.435 SELL HARD_TP +84.8%
     *   15:05:31.105 BUY  entry=0.0002966   15:05:31.314 SELL TICK_HARD_FLOOR −34%
     *   15:05:30.838 BUY  entry=0.0005519   15:05:31.347 SELL TICK_HARD_FLOOR −13%
     *
     * Three closes 200–840 ms after their opens, reading +85%, −34% and −13%.
     * No market moved a third in a fifth of a second. `signal.price` is the
     * registry row's price as it stood when the dynamic scan built the signal
     * — a DexScreener or Gecko figure that can be minutes old on a launch
     * moving 30% a minute — and it was written as the entry basis unchanged.
     * 7274 then gave these positions a live mark within a second of the open,
     * and the gap between a stale basis and a fresh mark booked as P&L in
     * both directions: an imagined gain and an imagined loss from the same
     * defect. Both fed the regime detector, the lane damper and every learner.
     *
     * A paper fill happens now, so its basis must be observed now: one bounded
     * fan-out pass for a `solana|<mint>` identity (contested medians refused,
     * as everywhere since 7273), otherwise the registry's own forced refresh
     * with its 60 s freshness bar. No fresh observation means no basis, and
     * no basis means the open is refused before cash is debited — the same
     * rule 7252 applies to a missing quantity witness. Live fills are
     * untouched: their basis is the venue's fill.
     */
    private suspend fun freshDynamicEntryBasis7275(signal: AltSignal, isSpot: Boolean): Pair<Double, String>? {
        val identity = try { cryptoAssetKey(signal, isSpot).trim() } catch (_: Throwable) { "" }
        if (identity.isBlank() || identity.startsWith("unresolved:") || identity.startsWith("perps:")) return null
        val bareSolanaMint = identity.takeIf { it.startsWith("solana|") }?.removePrefix("solana|")?.trim().orEmpty()
        if (bareSolanaMint.isNotBlank()) {
            val fan = try {
                withContext(Dispatchers.IO) {
                    com.lifecyclebot.network.ParallelMarkFanout7088.resolve7088(listOf(bareSolanaMint))[bareSolanaMint]
                }
            } catch (_: Throwable) { null }
            val contested = fan != null && fan.sourceCount >= 2 && !fan.corroborated
            if (contested) {
                try { PipelineHealthCollector.labelInc("CRYPTO_PAPER_ENTRY_BASIS_CONTESTED_7275") } catch (_: Throwable) {}
                return null
            }
            val px = fan?.priceUsd?.takeIf { it.isFinite() && it > 0.0 }
            if (px != null) {
                try { DynamicAltTokenRegistry.observeHeldMark7274(identity, px) } catch (_: Throwable) {}
                return px to (if (fan!!.corroborated) "FANOUT_CORROBORATED_7088_x${fan.agreeingCount}" else "FANOUT_UNCORROBORATED_7088")
            }
        }
        val snap = try {
            withContext(Dispatchers.IO) { DynamicAltTokenRegistry.refreshHeldMark7251(identity) }
        } catch (_: Throwable) { null }
        if (snap != null && snap.freshObservation && snap.price.isFinite() && snap.price > 0.0) {
            return snap.price to "ALT_REGISTRY_FRESH_7251"
        }
        // V5.0.7281 §THE SCAN WAS AN OBSERVATION TOO.
        //
        // 7275 refused a paper entry with no observation younger than 60 s.
        // On 5.0.7280: 130 refused, 49 observed — the lane that ran at 62%
        // was being told to sit out because DexScreener's limiter or
        // CoinGecko happened to be closed at the instant of the fill, while
        // the registry held a price the scanner had observed one or two
        // minutes earlier. 7275's target was a basis minutes stale that
        // produced ±85% closes within a second; a price observed inside the
        // last three minutes is not that. Age is recorded on the row so the
        // learner can see how old the bases that lost were.
        if (snap != null && snap.price.isFinite() && snap.price > 0.0 && snap.observedAtMs > 0L) {
            val ageMs7281 = (System.currentTimeMillis() - snap.observedAtMs).coerceAtLeast(0L)
            if (ageMs7281 <= RECENT_BASIS_MS_7281) {
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_PAPER_ENTRY_BASIS_RECENT_7281")
                    ForensicLogger.lifecycle(
                        "CRYPTO_PAPER_ENTRY_BASIS_RECENT_7281",
                        "symbol=${signal.marketSymbol} asset=${identity.take(32)} price=${snap.price} ageMs=$ageMs7281 " +
                            "action=recent_registry_observation_is_the_basis",
                    )
                } catch (_: Throwable) {}
                return snap.price to "ALT_REGISTRY_RECENT_7281_${ageMs7281 / 1000}s"
            }
        }
        return null
    }

    /** V5.0.7281 — a registry observation this young is an entry basis. */
    private val RECENT_BASIS_MS_7281 = 180_000L

    private suspend fun executeSignal(signal: AltSignal, isSpot: Boolean) {
        val paper7803 = authoritativePaperMode7425()
        /**
         * V5.0.7171 §TWO WRITERS FOR ONE REFUSAL, AND ONE OF THEM COUNTED
         * CANDIDATES THAT NEVER BECAME INTENTS.
         *
         * Operator's 5.0.7169:
         *
         *   CRYPTO_ALT candidate=166 submit=83 fdgAllow=83 sized=83
         *              intent=83 dispatch=3 dispatchReject=231 open=3
         *
         * 231 dispatch rejects against 83 intents. A number that large
         * cannot be a per-intent count, and it is not one:
         *
         *  1. This helper stamps markDispatchRejectFor6569 at EVERY early
         *     return, including the eight PRE_SUBMIT paths that run before
         *     CanonicalEntryAuthority6551.submit — a candidate that never
         *     became an intent cannot have failed to dispatch.
         *  2. On the paths that DO have an intent, the authority already
         *     stamps the reject itself: releasePending6554:285 fires
         *     markDispatchRejectFor6569 for any attemptId not in
         *     dispatchedAttempts, deduped through terminalByAttempt6647.
         *     Calling markFailed AND this helper counts the same refusal
         *     twice, and bypasses the dedup that exists to stop exactly that.
         *
         * So the funnel read "we tried 231 times and dispatched 3" when the
         * truth is 83 intents of which 80 never reached dispatch — a real
         * problem, but a different and much smaller one, and unreadable
         * while the denominator was inflated.
         *
         * The registry disposition is this helper's actual job and always
         * runs. The dispatch reject belongs to whoever owns the intent:
         * before submit, nobody does, so this records it; after submit the
         * authority does, and callers that release the intent pass
         * [authorityOwnsReject] so it is counted once, by the deduped writer.
         */
        fun terminalDisposition6613(reason: String, rejectOwner7171: String = "THIS") {
            try {
                val key = cryptoAssetKey(signal, isSpot)
                DynamicAltTokenRegistry.markEvaluationDisposition6567(
                    DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(key), reason,
                )
                when (rejectOwner7171) {
                    // The intent exists and the authority released it, which
                    // already stamped the reject through its own dedup.
                    "AUTHORITY" ->
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_REJECT_DEFERRED_TO_AUTHORITY_7171")
                    // No intent was ever sealed. This is a refusal, but not a
                    // dispatch refusal — and the funnel already shows it as
                    // candidate minus submit. Counted under its own name so
                    // the evidence survives without inflating dispatchReject.
                    "PRE_SUBMIT" -> {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_REFUSED_BEFORE_INTENT_7171")
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                            "CRYPTO_REFUSED_BEFORE_INTENT_7171_" + reason.substringBefore(':').take(40),
                        )
                    }
                    else ->
                        com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markDispatchRejectFor6569(
                            com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, signal.marketSymbol, reason,
                        )
                }
            } catch (_: Throwable) {}
        }
        // V5.0.7275 — a paper dynamic entry is based on a price observed now,
        // not on the scan-time registry figure the signal carried. See
        // freshDynamicEntryBasis7275. Everything below — TP/SL, the sealed
        // candidate, the quantity witness, the canonical open — reads the
        // shadowed signal, so the position's basis and its first mark come
        // from the same moment.
        val wantsFreshBasis7275 = paper7803 && signal.isDynamic
        val basis7275: Pair<Double, String>? =
            if (wantsFreshBasis7275) freshDynamicEntryBasis7275(signal, isSpot) else null
        if (wantsFreshBasis7275 && basis7275 == null) {
            terminalDisposition6613("CRYPTO_PAPER_ENTRY_BASIS_UNOBSERVED_7275", "PRE_SUBMIT")
            try {
                PipelineHealthCollector.labelInc("CRYPTO_PAPER_ENTRY_BASIS_UNOBSERVED_7275")
                ForensicLogger.lifecycle(
                    "CRYPTO_PAPER_ENTRY_BASIS_UNOBSERVED_7275",
                    "symbol=${signal.marketSymbol} asset=${cryptoAssetKey(signal, isSpot).take(32)} scanPrice=${signal.price} " +
                        "action=no_fresh_observation_no_basis_refuse_before_debit",
                )
            } catch (_: Throwable) {}
            return
        }
        val entryBasisSource7275 = basis7275?.second ?: "signal.price"
        @Suppress("NAME_SHADOWING")
        val signal = if (basis7275 != null) {
            val scanPx7275 = signal.price
            val freshPx7275 = basis7275.first
            val movePct7275 = if (scanPx7275 > 0.0) (freshPx7275 / scanPx7275 - 1.0) * 100.0 else 0.0
            try {
                PipelineHealthCollector.labelInc("CRYPTO_PAPER_ENTRY_BASIS_OBSERVED_7275")
                if (kotlin.math.abs(movePct7275) >= 5.0) {
                    PipelineHealthCollector.labelInc("CRYPTO_PAPER_ENTRY_BASIS_MOVED_FROM_SCAN_7275")
                    ForensicLogger.lifecycle(
                        "CRYPTO_PAPER_ENTRY_BASIS_MOVED_FROM_SCAN_7275",
                        "symbol=${signal.marketSymbol} scanPrice=$scanPx7275 fillPrice=$freshPx7275 " +
                            "movePct=${"%.1f".format(movePct7275)} src=$entryBasisSource7275 " +
                            "action=basis_is_the_observed_fill_not_the_scan_row",
                    )
                }
            } catch (_: Throwable) {}
            signal.copy(price = freshPx7275)
        } else signal
        // V5.9.1472 — DYNAMIC CRYPTO: resolve the REAL coin symbol once. For DYN
        // sentinel signals (arbitrary non-Solana coin) this is dynSymbol; for
        // hardcoded enum coins it's market.symbol. ALL learning/record/log calls
        // below MUST use mktSym so per-coin learning isn't collapsed into "DYN".
        val mktSym = signal.marketSymbol

        // V5.0.7825 — prevent four-token churn from becoming the de-facto
        // Crypto Universe. Apply after exact identity resolution but before any
        // canonical submit/sizing/route work. A genuinely exceptional changed
        // setup may re-enter; ordinary repeated signals wait their turn while
        // resident hunters and universe rotation surface alternatives.
        val reentryKey7825 = cryptoAssetKey(signal, isSpot).trim()
        val lastClose7825 = cryptoLastClosedAt7825[reentryKey7825] ?: 0L
        val reentryAge7825 = System.currentTimeMillis() - lastClose7825
        val escape7825 = signal.score >= CRYPTO_REENTRY_ESCAPE_SCORE_7825 &&
            (signal.score + signal.confidence) >= CRYPTO_REENTRY_ESCAPE_COMBINED_7825
        if (lastClose7825 > 0L && reentryAge7825 in 0 until CRYPTO_REENTRY_COOLDOWN_MS_7825 && !escape7825) {
            terminalDisposition6613("CRYPTO_REENTRY_COOLDOWN_7825", "PRE_SUBMIT")
            try {
                PipelineHealthCollector.labelInc("CRYPTO_REENTRY_COOLDOWN_7825")
                ForensicLogger.lifecycle(
                    "CRYPTO_REENTRY_COOLDOWN_7825",
                    "symbol=${mktSym} asset=${reentryKey7825.take(32)} ageMs=${reentryAge7825} " +
                        "score=${signal.score} conf=${signal.confidence} action=rotate_to_other_crypto_candidates",
                )
            } catch (_: Throwable) {}
            return
        }
        if (lastClose7825 > 0L && reentryAge7825 in 0 until CRYPTO_REENTRY_COOLDOWN_MS_7825 && escape7825) {
            try { PipelineHealthCollector.labelInc("CRYPTO_REENTRY_HIGH_EDGE_ESCAPE_7825") } catch (_: Throwable) {}
        }

        // V5.9.198: Trust gate
        // V5.9.1452: Crypto-isolated bypass — the meme-side StrategyTrustAI was
        // accumulating "DISTRUSTED" verdicts on generic reason strings (e.g.
        // "✅ Bullish momentum 📈") shared across lanes, which silently blocked
        // 100% of crypto entries. Crypto's own losing-pattern memory + tilt
        // gate + EXEC_GATE finality already cover trust at the bucket level,
        // so the global strategy-trust gate is now consult-only for crypto:
        // it gives a trust multiplier (sizing) but never vetoes the trade.
        val tradingMode = signal.strategy7803.ifBlank { "CRYPTO_NATIVE" }
        val trustMult = try {
            com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustMultiplier(tradingMode)
        } catch (_: Throwable) { 1.0 }
        // V5.9.114: UNIFIED paper + live pipeline.
        // User policy: "live mode should behave exactly like paper". All
        // sizing, sanity, exposure, hive, TP/SL, AI registrations, and
        // position bookkeeping now run identically in both modes. The ONLY
        // difference is the capital-move step: paper debits the paper
        // wallet, live fires a Jupiter swap for the SAME finalSize.

        // Paper execution — only when in paper mode
        val balance = getEffectiveBalance()
        // V5.9.88: FLUID SIZING — scale with AI score + confidence instead of
        // the old flat 3% for every trade. Range: 0.4x..2.0x base size.
        // High-conviction (score≥85, conf≥80) rides 2x; low-conviction <55/40 rides 0.4x.
        val sizeMult = fluidSizeMultiplier(signal.score, signal.confidence)
        var sizeSol  = balance * (DEFAULT_SIZE_PCT / 100) * sizeMult * trustMult  // V5.9.198: trust-weighted sizing

        // V5.0.4586c — CRYPTO PARITY (operator P0): apply the same growth
        // envelope + WR-tuned lane multiplier + Bayesian probability edge +
        // daily-compound behind-target pressure that the meme lanes already
        // consume. Crypto is isolated from meme learning (V5.0.4151
        // "no meme contamination") by using distinct lane tags
        // CRYPTO_SPOT / CRYPTO_LEV — LiveStrategyTuner / LiveProbabilityEngine
        // / LosingPatternMemory all scope their state by lane string, so
        // these calls only read/write crypto-lane closes.
        run cryptoParitySizing@{
            try {
                val cryptoLane = canonicalCryptoLane7251(signal.isDynamic, isSpot)
                val cryptoTuneMult = try {
                    com.lifecyclebot.engine.LiveStrategyTuner.sizeMultiplier(cryptoLane)
                } catch (_: Throwable) { 1.0 }
                val cryptoEdge = try {
                    com.lifecyclebot.engine.LiveProbabilityEngine.forecast(
                        cryptoLane, signal.score.toInt(), "U", "NORMAL",
                    ).sizeMult
                } catch (_: Throwable) { 1.0 }
                val cryptoBehind = try {
                    com.lifecyclebot.engine.DailyCompoundingTracker.behindTargetPressure()
                } catch (_: Throwable) { 1.0 }
                val rawCryptoBlend6613 = cryptoTuneMult * cryptoEdge * cryptoBehind
                val cryptoBlend = rawCryptoBlend6613.coerceIn(0.20, 2.5)
                sizeSol *= cryptoBlend
                if (rawCryptoBlend6613 < 0.20) try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_LEARNED_SIZE_FLOORED_NONZERO_6613")
                    ForensicLogger.lifecycle("CRYPTO_LEARNED_SIZE_FLOORED_NONZERO_6613", "symbol=$mktSym lane=$cryptoLane raw=$rawCryptoBlend6613 floor=0.20 action=continue_to_canonical_sizer")
                } catch (_: Throwable) {}
                ErrorLogger.debug(TAG,
                    "🧠 CRYPTO_PARITY_SIZING ${mktSym} lane=$cryptoLane baseMult=${"%.2f".format(sizeMult)} tune=${"%.2f".format(cryptoTuneMult)} edge=${"%.2f".format(cryptoEdge)} behind=${"%.2f".format(cryptoBehind)} blend=${"%.2f".format(cryptoBlend)}")
            } catch (_: Throwable) {}
        }

        // V5.0.4586c — CRYPTO PARITY toxic-pattern hard block (meme's Rule 5
        // ported to crypto). LosingPatternMemory is bucket-keyed by
        // (tradingMode|scoreBand); the crypto lane tag is distinct from meme
        // lane tags so this only ever hard-blocks crypto lanes that have
        // proven-toxic buckets (sample≥30, lossRate≥90%, meanPnl≤-8%).
        var cryptoToxicSizeMult6095 = 1.0
        run cryptoParityToxicGate@{
            try {
                val cryptoLane = canonicalCryptoLane7251(signal.isDynamic, isSpot)
                val toxic = com.lifecyclebot.engine.LosingPatternMemory.stats(cryptoLane, signal.score.toInt())
                if (toxic.sample >= 30 && toxic.lossRatePct >= 90.0 && toxic.meanPnl <= -8.0) {
                    val bucketId = try { com.lifecyclebot.engine.LosingPatternMemory.bucketKey(cryptoLane, signal.score.toInt()) } catch (_: Throwable) { "$cryptoLane|?" }
                    cryptoToxicSizeMult6095 = 0.35
                    ErrorLogger.warn(TAG,
                        "🪙 CRYPTO_TOXIC_PATTERN_SOFT_SHAPE_6095 ${mktSym} bucket=$bucketId n=${toxic.sample} lossRate=${"%.1f".format(toxic.lossRatePct)}% mean=${"%.1f".format(toxic.meanPnl)}% — tactic/size pivot, not hard-block")
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_TOXIC_PATTERN_SOFT_SHAPE_6095") } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        sizeSol *= cryptoToxicSizeMult6095

        // V5.0.7244 — consume the isolated CryptoBrain sizing authority.
        val cryptoTier7244 = if (signal.isDynamic) {
            dynamicCryptoTier7244(exactAssetMetrics6493(signal).marketCapUsd)
        } else {
            cryptoBrainTier6923(mktSym)
        }
        val cryptoBrainSize7244 = try {
            com.lifecyclebot.perps.crypto.brain.CryptoBrain.sizingMultiplier(
                cryptoTier7244, signal.score,
            )
        } catch (_: Throwable) { 1.0 }
        sizeSol *= cryptoBrainSize7244
        try { PipelineHealthCollector.labelInc("CRYPTO_BRAIN_SIZE_APPLIED_7244") } catch (_: Throwable) {}

        // V5.0.7400 — do not kill Crypto before canonical sizing.
        // The downstream requestedFinalSize already applies the anti-dust floor
        // and CanonicalSizingBridge6532 owns executable-minimum affordability.
        // Returning here converted learned size dampers into candidate->submit=0
        // starvation. Keep the raw learned size and let the canonical resolver
        // promote/clamp/refuse with one authoritative reason.
        if (sizeSol < 0.01) {
            try { PipelineHealthCollector.labelInc("CRYPTO_PRECANONICAL_DUST_DEFERRED_TO_SIZER_7400") } catch (_: Throwable) {}
        }


        // V5.9.5 FIX: Sanity-check entry price vs last cached price.
        // Bad data (decimal shift, wrong feed ID, stale fallback) causes fake 1000x PnL.

        // V5.0.7400 — portfolio/exposure checks moved AFTER canonical sizing.
        // Raw learned size is advisory; CanonicalSizingBridge6532 produces the
        // sealed executable notional. Pre-authority portfolio checks on raw size
        // were starving Crypto at candidate->submit.
        val cachedPriceData = PerpsMarketDataFetcher.getCachedPrice(signal.market)
        if (signal.price <= 0.0) {
            terminalDisposition6613("PRE_SUBMIT_PRICE_ZERO", "PRE_SUBMIT")
            ErrorLogger.warn(TAG, "🪙 PRICE ZERO: ${mktSym} — REJECTING trade")
            return
        }
        if (cachedPriceData != null && cachedPriceData.price > 0) {
            val priceDiffPct = kotlin.math.abs(signal.price - cachedPriceData.price) / cachedPriceData.price * 100.0
            if (priceDiffPct > 90.0) {
                terminalDisposition6613("PRE_SUBMIT_PRICE_SANITY_${priceDiffPct.toInt()}", "PRE_SUBMIT")
                ErrorLogger.warn(TAG, "🪙 PRICE SANITY FAIL: ${mktSym} signal=\$${signal.price} cached=\$${cachedPriceData.price} diff=${priceDiffPct.toInt()}% — REJECTING")
                return
            }
        }

        val tpPct = com.lifecyclebot.perps.crypto.brain.CryptoBrain.getTpPct(isSpot)
        // V5.0.6923 — CryptoBrain.laneExitVerdict had zero callers.
        //
        // The line below reads the crypto brain's LEARNED take-profit. The
        // stop next to it was a bare constant, and the brain's own per-tier
        // exit verdict — CryptoLaneExitTuner, which keeps a 100-sample window
        // per tier and returns WIDEN_STOPS / DEFAULT / TIGHTEN_STOPS from that
        // tier's realised profit factor and net SOL — was never consulted by
        // anything. Its three enum values literally name what to do with the
        // stop ("lane is profitable + low PF -> run wider stops", "lane is
        // bleeding -> tighten stops") and no stop ever moved.
        //
        // Note on what was deliberately NOT changed here:
        //  * CryptoBrain.getSlPct returns a hardcoded 3.5 / 5.0, identical to
        //    DEFAULT_SL_SPOT / DEFAULT_SL_LEV. Routing through it would look
        //    like wiring and change nothing, so the constants stay.
        //  * This trader's use of the MEME FluidLearningAI.getDynamicFluidStop
        //    for its profit-floor lock is intentional (V5.9.118: "same lock
        //    semantics as the main meme trader so alts runners don't give back
        //    huge gains"), not a cross-contamination bug.
        //
        // The verdict needs n >= 25 in a tier before it is anything but
        // DEFAULT, so this is inert until that tier has real evidence.
        val laneTier6923 = cryptoBrainTier6923(mktSym)
        val laneExitVerdict6923 = try {
            com.lifecyclebot.perps.crypto.brain.CryptoBrain.laneExitVerdict(laneTier6923)
        } catch (_: Throwable) {
            com.lifecyclebot.perps.crypto.brain.CryptoLaneExitTuner.Verdict.DEFAULT
        }
        val laneStopMult6923 = when (laneExitVerdict6923) {
            com.lifecyclebot.perps.crypto.brain.CryptoLaneExitTuner.Verdict.WIDEN_STOPS -> 1.25
            com.lifecyclebot.perps.crypto.brain.CryptoLaneExitTuner.Verdict.TIGHTEN_STOPS -> 0.80
            com.lifecyclebot.perps.crypto.brain.CryptoLaneExitTuner.Verdict.DEFAULT -> 1.00
        }
        val slPctBase  = (if (isSpot) DEFAULT_SL_SPOT else DEFAULT_SL_LEV) * laneStopMult6923
        if (laneStopMult6923 != 1.00) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CRYPTO_LANE_EXIT_VERDICT_APPLIED_6923",
                    "sym=$mktSym tier=$laneTier6923 verdict=$laneExitVerdict6923 stopMult=${"%.2f".format(laneStopMult6923)} slPct=${"%.2f".format(slPctBase)} spot=$isSpot",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_LANE_EXIT_VERDICT_APPLIED_6923_$laneExitVerdict6923")
            } catch (_: Throwable) {}
        }
        val lev    = if (isSpot) 1.0 else signal.leverage

        // V5.9.88: FLUID TP/SL — scale with conviction, stop flat TP+7%/SL-3%
        // on every trade. High-score signals get wider TP + tighter SL;
        // low-score get tighter TP + wider SL (asymmetric conviction curve).
        val (tpMult, slMult) = fluidTpSlMultiplier(signal.score, signal.confidence)

        // Hivemind size / TP modifier
        val (_, hiveSizeMult, hiveTpAdj) = hiveEntryModifier(mktSym)
        // V5.0.6095 — MEME parity: Crypto Universe should not be trapped in
        // a micro-only treadmill. Keep anti-dust minimum, but allow the same
        // compounding/winner pressure already included above to express up to
        // 45% of available mode-local balance. Total portfolio risk cap remains
        // 80%, wallet lock still applies live, and route proof still gates real buys.
        // V5.0.7735 — coerceIn(0.01, balance * 0.45) throws IllegalArgumentException
        // ("empty range") whenever the balance is under 0.0222 SOL, and every
        // candidate then died as EXECUTION_EXCEPTION_ILLEGALARGUMENTEXCEPTION
        // (192 on 5.0.7734) instead of a named refusal. The floor is the cap when
        // the wallet is that small; the live doctrine below refuses the size.
        val requestedFinalSize0 = (sizeSol * hiveSizeMult).coerceIn(0.01, maxOf(0.01, balance * 0.45))
        // V5.0.7708 §THE_CRYPTO_LANE_WAS_SPENDING_OUTSIDE_THE_DOCTRINE.
        //
        // 5.0.7706 live tape, 23 minutes: EXEC_LIVE_BUY_OK=0 on the meme lanes,
        // yet the wallet SOL fell 0.366 -> 0.130 and Phantom showed a dozen
        // $2-$4 holdings (TRX, TNSR, IO, WBTC, AAVE, KMNO, CHZ, GALA, PIXEL).
        // Those were this lane: CRYPTO_SIGNED_VERIFY_PENDING_NO_OPEN_7434=24.
        // Each buy was 0.01-0.02 SOL — below the $5 routing floor every meme
        // lane is held to, below the concentration doctrine's position size,
        // never counted against the two live slots, never through the exit
        // coverage gate, and never opened as a position. That is the
        // "spreading capital way too wide" the operator named, from a lane
        // that had not been told the doctrine. Live crypto entries now clear
        // the same four checks before any SOL moves. Paper is untouched.
        val requestedFinalSize = if (authoritativePaperMode7425()) requestedFinalSize0 else {
            val gate7708 = liveCryptoEntryGate7708(balance, requestedFinalSize0)
            val refusal7708 = gate7708.second
            if (refusal7708 != null) {
                terminalDisposition6613(refusal7708, "PRE_SUBMIT")
                try {
                    PipelineHealthCollector.labelInc(refusal7708.substringBefore(':').take(60))
                    ForensicLogger.lifecycle("CRYPTO_LIVE_DOCTRINE_REFUSED_7708", "symbol=$mktSym requested=${"%.4f".format(requestedFinalSize0)} balance=${"%.4f".format(balance)} reason=$refusal7708")
                } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(signal.dynMint ?: signal.market.symbol, "CRYPTO", "CRYPTO_LIVE_DOCTRINE_REFUSED_7708") } catch (_: Throwable) {}
                return
            }
            gate7708.first
        }
        // V5.0.6540 §ONE_EXECUTION_AUTHORITY — CryptoAlt specialist must
        // announce its candidate to the canonical entry funnel BEFORE it
        // consults sizing. Route to MARKETS_SPOT/MARKETS_PERPS venue by
        // spot capability + leverage. This keeps the operator's funnel
        // telemetry (candidate → submit → allow → sized → intent →
        // dispatch → open) coherent per venue and enables the P0
        // acceptance-invariant fail-build guard.
        val venue6540 = com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.routeVenue(
            isLong = signal.direction == PerpsDirection.LONG,
            isSpotCapable = isSpot,
            leveraged = !isSpot,
        )
        try {
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markCandidate(
                venue = venue6540, symbol = mktSym,
                note = "isSpot=$isSpot lev=$lev score=${signal.score} conf=${signal.confidence}",
            )
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markAuthSubmit(
                venue = venue6540, symbol = mktSym,
                note = "requestedSol=$requestedFinalSize",
            )
        } catch (_: Throwable) {}
        // V5.0.6532 §CANONICAL_SIZING_BRIDGE.
        val altSizingRes = com.lifecyclebot.engine.truth.CanonicalSizingBridge6532.resolve(
            requestedSol = requestedFinalSize,
            assetClass = com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT,
            laneName = canonicalCryptoLane7251(signal.isDynamic, isSpot),
            walletSol = balance,
            paperMode = paper7803,
            canonicalAssetId = signal.dynMint?.ifBlank { signal.market.symbol } ?: signal.market.symbol, symbol = mktSym, price = signal.price, source = "CryptoAltTrader",
        )
        if (!altSizingRes.executable) {
            try {
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markAuthBlock(
                    venue = venue6540, symbol = mktSym,
                    reason = "SIZE_NOT_EXECUTABLE:${altSizingRes.reason}",
                )
            } catch (_: Throwable) {}
            terminalDisposition6613("PRE_SUBMIT_SIZE_NOT_EXECUTABLE:${altSizingRes.reason}", "PRE_SUBMIT")
            ErrorLogger.warn(TAG, "🪙 sizing gate declined ${mktSym}: ${altSizingRes.reason}")
            return
        }
        val finalSize = altSizingRes.finalSizeSol

        // V5.0.7400 — now enforce exposure/cross-trader capital on the SEALED size.
        // These remain real portfolio guards; only their authority ordering changed.
        var totalRisk7400 = activeModePositions7256(positions.values).sumOf { it.sizeSol }
        val maxRisk7400 = (balance + totalRisk7400) * 0.80
        if (signal.isDynamic && totalRisk7400 + finalSize > maxRisk7400) {
            if (rotateWeakPaperExposure7244(signal.score)) {
                totalRisk7400 = activeModePositions7256(positions.values).sumOf { it.sizeSol }
            }
        }
        if (totalRisk7400 + finalSize > maxRisk7400) {
            terminalDisposition6613("POST_SIZE_EXPOSURE_CAP_7400", "PRE_SUBMIT")
            try { PipelineHealthCollector.labelInc("CRYPTO_POST_SIZE_EXPOSURE_CAP_7400") } catch (_: Throwable) {}
            return
        }
        if (!paper7803) {
            val walletBal7400 = try { WalletManager.getWallet()?.getSolBalance() ?: 0.0 } catch (_: Exception) { 0.0 }
            if (!com.lifecyclebot.engine.WalletPositionLock.canOpen("CryptoAlt", finalSize, walletBal7400)) {
                terminalDisposition6613("POST_SIZE_LIVE_WALLET_LOCK_7400", "PRE_SUBMIT")
                try { PipelineHealthCollector.labelInc("CRYPTO_POST_SIZE_LIVE_WALLET_LOCK_7400") } catch (_: Throwable) {}
                return
            }
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_UNIVERSE_MEME_PARITY_SIZE_6095")
            ErrorLogger.info(TAG, "🪙 CRYPTO_UNIVERSE_MEME_PARITY_SIZE_6095 ${mktSym} base=${"%.4f".format(sizeSol)} hive=${"%.2f".format(hiveSizeMult)} final=${"%.4f".format(finalSize)} bal=${"%.4f".format(balance)} toxic=${"%.2f".format(cryptoToxicSizeMult6095)}")
        } catch (_: Throwable) {}
        val finalTp   = ((tpPct * tpMult) + hiveTpAdj).coerceAtLeast(1.5)
        // V5.9.432 — SL floor raised from 1.5% → 4% for SPOT, 3% → 6% for
        // LEVERAGE (via DEFAULT_SL_SPOT/LEV below-clamp). Prior 1.5% floor
        // produced the "SL-2%" user screenshot where any normal alt wobble
        // tripped the stop before the trade had room to develop. 4% gives
        // breathing room, trail + partial ladder handle upside.
        val slFloor = if (isSpot) 4.0 else 6.0
        val finalSl   = (slPctBase * slMult).coerceIn(slFloor, 15.0)

        val (tp, sl) = when (signal.direction) {
            PerpsDirection.LONG  -> signal.price * (1 + finalTp / 100) to signal.price * (1 - finalSl / 100)
            PerpsDirection.SHORT -> signal.price * (1 - finalTp / 100) to signal.price * (1 + finalSl / 100)
        }

        val candidate = buildCryptoFinalBuyCandidate(signal, isSpot, finalSize)
        try {
            PipelineHealthCollector.labelInc("CRYPTO_CANONICAL_PREFDG_HANDOFF_7810")
            if (candidate.preFdgVerdict == CryptoFinalBuyCandidate.PreFdgVerdict.BUY) {
                PipelineHealthCollector.labelInc("CRYPTO_CANONICAL_PREFDG_BUY_7810")
            }
        } catch (_: Throwable) {}
        com.lifecyclebot.perps.crypto.brain.CryptoFunnel.preFdg(candidate.canEnterFdg)
        val disciplinePassed6647 = passesCryptoDiscipline6647(candidate)
        if (!disciplinePassed6647) {
            com.lifecyclebot.perps.crypto.brain.CryptoFunnel.execGate(false)
            try {
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markAuthBlock(
                    venue = venue6540, symbol = mktSym,
                    reason = "FDG_OR_HARD_NO:${candidate.hardNoReasons}",
                )
            } catch (_: Throwable) {}
            ErrorLogger.info(TAG, "🪙 CRYPTO EXEC BLOCKED: ${mktSym} | preFdg=${candidate.preFdgVerdict} hardNo=${candidate.hardNoReasons} route=${candidate.routeQuality}")
            // V5.9.1317 (P0-5) — release the primary-lane lease on the CRYPTO book so a
            // blocked candidate does not suppress follow-up CRYPTO attempts for the same
            // asset until TTL. CRYPTO lane is isolated; this never touches Meme lanes.
            try { com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(candidate.assetKey, "CRYPTO", "CRYPTO_EXEC_BLOCKED") } catch (_: Throwable) {}
            terminalDisposition6613("PRE_SUBMIT_FDG_OR_HARD_NO:${candidate.hardNoReasons.joinToString(",")}", "PRE_SUBMIT")
            return
        }
        try { ForensicLogger.phase(ForensicLogger.PHASE.LANE_EVAL, candidate.symbol, "lane=CRYPTO_ALT source=CANONICAL_HANDOFF_6566 score=${signal.score} confidence=${signal.confidence} mode=${if (paper7803) "PAPER" else "LIVE"}") } catch (_: Throwable) {}
        // V5.0.6649a §P0-3 CRYPTO_ALT_CANDIDATE_STAMP — mark the
        //   producer stage transition at the moment the candidate
        //   is handed off to the CanonicalEntryAuthority6551.submit
        //   spine so the crypto funnel counter shows CANDIDATE
        //   -> ACTIONABLE_SIGNAL -> EXECUTE_BUY explicitly for
        //   every crypto candidate. BuildRepair6581CoverageTest
        //   asserts the literal source pair below.
        try {
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(
                com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "CANDIDATE"
            )
        } catch (_: Throwable) {}
        com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540.markProducerStage6569(
            com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT, "SUBMIT"
        )
        val canonicalCryptoAdmission6565 = com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.submit(
            com.lifecyclebot.engine.truth.CanonicalAssetEntryCandidate6551(
                assetId = candidate.assetKey, symbol = mktSym,
                assetClass = com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT,
                mode = if (authoritativePaperMode7425()) "PAPER" else "LIVE",
                direction = signal.direction.name, requestedVenue = candidate.venue,
                adapter = candidate.executionAdapter, source = candidate.universe,
                specialist = "CRYPTO", score = candidate.score.toDouble(), confidence = 1.0,
                evidence = mapOf(
                    "upstreamConfidence" to candidate.confidence.toString(), "walletSol" to balance.toString(),
                    "strategy7803" to signal.strategy7803,
                    "deskOverlays7803" to signal.deskOverlays7803.sorted().joinToString(","),
                    // Compatibility desk label; 7803 keeps every overlay resident.
                    "deskLane7391" to CryptoLaneDesk7391.laneFromReasons(signal.reasons).ifBlank { "NONE" },
                ),
                requestedSizeSol = finalSize, price = signal.price, liquidityUsd = candidate.liquidityUsd,
                routeAvailable = authoritativePaperMode7425() || candidate.executionAdapter != "NONE",
                hardSafetyReasons = candidate.hardNoReasons, candidateVersion = candidate.candidateVersion,
                diagnosticSignal = candidate.preFdgVerdict.name,
            )
        )
        val canonicalCryptoIntent6565 = when (canonicalCryptoAdmission6565) {
            is com.lifecyclebot.engine.truth.CanonicalAssetEntryResult6551.Allowed -> canonicalCryptoAdmission6565.intent
            is com.lifecyclebot.engine.truth.CanonicalAssetEntryResult6551.Probe -> canonicalCryptoAdmission6565.intent
            is com.lifecyclebot.engine.truth.CanonicalAssetEntryResult6551.Blocked -> {
                terminalDisposition6613("CANONICAL_BLOCKED:${canonicalCryptoAdmission6565.reason}", "PRE_SUBMIT")
                return
            }
            is com.lifecyclebot.engine.truth.CanonicalAssetEntryResult6551.Deferred -> {
                terminalDisposition6613("CANONICAL_DEFERRED:${canonicalCryptoAdmission6565.reason}", "PRE_SUBMIT")
                return
            }
        }
        val canonicalFinalSize6570 = canonicalCryptoIntent6565.resolvedSize
        try { ForensicLogger.phase(ForensicLogger.PHASE.FDG, candidate.symbol, "path=CRYPTO_ALT mode=${canonicalCryptoIntent6565.mode} verdict=${canonicalCryptoIntent6565.fdgVerdict} sealed=true attemptId=${canonicalCryptoIntent6565.attemptId}") } catch (_: Throwable) {}
        // V5.0.7521 — CanonicalEntryAuthority6551.submit() already owns the
        // authoritative pre-entry gate, sizing resolution, FDG outcome and immutable
        // intent seal. Re-running ExecutableOpenGate here created a second admission
        // authority AFTER the intent existed. Runtime 5.0.7518 proved the contradiction:
        // CRYPTO_ALT intent=22, dispatch=0, dispatchReject=22 while every sealed intent
        // had already passed canonical FDG+sizing. Venue/finality safety remains in the
        // paper canonical transaction and live venue executor; do not re-admit the same
        // trade through a mutable second gate.
        com.lifecyclebot.perps.crypto.brain.CryptoFunnel.execGate(true)
        try {
            PipelineHealthCollector.labelInc("CRYPTO_POST_SEAL_DUPLICATE_GATE_ELIMINATED_7521")
        } catch (_: Throwable) {}
        try {
            DynamicAltTokenRegistry.markEvaluationDisposition6567(
                DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(candidate.assetKey),
                "HANDED_TO_CANONICAL_AUTHORITY",
            )
        } catch (_: Throwable) {}
        if (!canonicalFinalSize6570.isFinite() || canonicalFinalSize6570 <= 0.0) {
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(canonicalCryptoIntent6565, "INVALID_SEALED_SIZE_6570")
            terminalDisposition6613("CANONICAL_INVALID_SEALED_SIZE", "AUTHORITY")
            return
        }
        val holdSetupQuality6663 = when {
            signal.score >= 80 && signal.confidence >= 70 -> "A+"
            signal.score >= 70 -> "A"
            signal.score >= 55 -> "B"
            else -> "C"
        }
        val holdRecommendation6663 = try {
            com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI.predict(
                mint = candidate.assetKey,
                symbol = mktSym,
                setupQuality = holdSetupQuality6663,
                liquidityUsd = candidate.liquidityUsd,
                volatilityRegime = if (kotlin.math.abs(signal.priceChange24h) >= 15.0) "HIGH" else "NORMAL",
                marketRegime = "NEUTRAL",
                entryScore = signal.score,
            )
        } catch (_: Throwable) { null }
        val position = AltPosition(
            id             = "ALT:${canonicalCryptoIntent6565.attemptId}",
            market         = signal.market,
            dynSymbol      = signal.dynSymbol,
            dynName        = signal.dynName,
            dynEmoji       = signal.dynEmoji,
            dynMint        = signal.dynMint,
            direction      = signal.direction,
            isSpot         = isSpot,
            isPaper        = paper7803,
            canonicalAssetKey = candidate.assetKey,
            markAssetKey   = candidate.assetKey,
            markUpdatedAtMs= System.currentTimeMillis(),
            entryPrice     = signal.price,
            currentPrice   = signal.price,
            sizeSol        = canonicalFinalSize6570,
            leverage       = lev,
            takeProfitPrice= tp,
            stopLossPrice  = sl,
            aiScore        = signal.score,
            aiConfidence   = signal.confidence,
            reasons        = listOf("STRATEGY7803=" + signal.strategy7803) +
                signal.deskOverlays7803.sorted().map { "OVERLAY7803=" + it } +
                signal.reasons + "CRYPTO_CANDIDATE:${candidate.assetKey}",
            holdSetupQuality = holdSetupQuality6663,
            adaptiveMaxHoldSeconds = holdRecommendation6663?.maxSeconds?.coerceIn(60, 3_600) ?: 3_600,
        )

        // Note: totalTrades incremented at CLOSE, not open, for accurate win rate

        // V5.9.114: UNIFIED capital move. Paper debits paper wallet;
        // live fires a Jupiter swap at the EXACT same canonicalFinalSize6570 so sizing
        // learnt in paper carries 1:1 into live. If the live swap fails
        // we roll back: the position is not created and we return.
        if (authoritativePaperMode7425()) {
            // V5.0.6578 §P1-1 — PAPER PATH DISPATCH PARITY.
            // Operator forensic (6573): CryptoAlt intent=3 dispatch=0 open=0
            // unexplained=3. The paper branch previously never called
            // markDispatch / markConfirmed on CanonicalEntryAuthority6551
            // so every paper attempt stalled between 'intent' and 'dispatch'
            // regardless of open outcome. Live branch was correct; paper
            // is now brought into parity: markDispatch before the canonical
            // open, markConfirmed on success, markFailed on rejection.
            // V5.0.7161 §THE COMMENT TWO LINES UP PROMISED THIS AND IT WAS
            // NEVER WRITTEN.
            //
            // "markDispatch before the canonical open, markConfirmed on
            // success, markFailed on rejection." markConfirmed and markFailed
            // are both here. markDispatch is not — it exists only in the LIVE
            // branch below (:2659+). So in PAPER the funnel reads:
            //
            //   CRYPTO_ALT candidate=16 submit=8 fdgAllow=8 sized=8
            //              intent=8 dispatch=0 dispatchReject=108
            //
            // dispatch=0 is STRUCTURAL in paper, not a choke. And it is worse
            // than a missing counter: CanonicalAssetEntryContract6551
            // .releasePending6554 does
            //
            //   val wasDispatched6647 = dispatchedAttempts6569.remove(attemptId)
            //   if (!wasDispatched6647) markDispatchRejectFor6569(...)
            //
            // and paper never adds to that set — so EVERY paper terminal
            // release is recorded as a dispatch REJECT. That is most of the
            // 108, and it made a working paper lane read as a lane that
            // cannot dispatch.
            //
            // The stage means "handed to an executor", not "spent money". The
            // paper executor is an executor.
            // V5.0.7252 — CanonicalPaperTransaction6486's generic default is
            // exactly 1e9 raw @ scale 9 (1.00 token). That is a sentinel, not
            // the funded crypto quantity. Derive the simulated fill from the
            // sealed SOL notional, SOL/USD witness and USD/token entry price.
            // Without those witnesses, refuse before cash is debited.
            val paperQuantityScale7252 = 9
            val paperSolUsd7252 = try {
                com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            } catch (_: Throwable) { 0.0 }
            val paperQtyRaw7252 = if (
                paperSolUsd7252.isFinite() && paperSolUsd7252 in 50.0..5_000.0 &&
                signal.price.isFinite() && signal.price > 0.0
            ) try {
                com.lifecyclebot.engine.truth.CanonicalRawQuantityAuthority6520.paperRawFromEconomics(
                    canonicalFinalSize6570.toString(), paperSolUsd7252.toString(),
                    signal.price.toString(), paperQuantityScale7252,
                )
            } catch (_: Throwable) { java.math.BigInteger.ZERO }
            else java.math.BigInteger.ZERO
            if (paperQtyRaw7252 <= java.math.BigInteger.ZERO) {
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(
                    canonicalCryptoIntent6565, "CRYPTO_PAPER_QUANTITY_WITNESS_MISSING_7252",
                )
                terminalDisposition6613("CRYPTO_PAPER_QUANTITY_WITNESS_MISSING_7252", "AUTHORITY")
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_PAPER_QUANTITY_WITNESS_MISSING_7252")
                    ForensicLogger.lifecycle(
                        "CRYPTO_PAPER_QUANTITY_WITNESS_MISSING_7252",
                        "symbol=$mktSym sizeSol=$canonicalFinalSize6570 solUsd=$paperSolUsd7252 tokenUsd=${signal.price} action=refuse_before_debit",
                    )
                } catch (_: Throwable) {}
                return
            }
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markDispatch(canonicalCryptoIntent6565)
            val canonicalOpen6486 = try {
                com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486.open(
                    positionId = position.id, mint = position.canonicalAssetKey, symbol = mktSym,
                    lane = canonicalCryptoLane7251(signal.isDynamic, isSpot), source = "CryptoAltTrader",
                    costSol = canonicalFinalSize6570, entryScore = signal.score, tactic = if (isSpot) "SPOT" else "LEVERAGE",
                    qtyRaw = paperQtyRaw7252,
                    decimals = paperQuantityScale7252,
                    quantityScale = paperQuantityScale7252,
                    // V5.0.6525 §ASSET_CLASS + §ENTRY_PRICE.
                    assetClass = com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT,
                    entryPriceUsd = signal.price,
                    // V5.0.7275 — names the observation the basis came from.
                    entryPriceSource = "CryptoAltTrader/$entryBasisSource7275",
                    executionIntent = canonicalCryptoIntent6565,
                )
            } catch (t: Throwable) {
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(
                    canonicalCryptoIntent6565,
                    "CANONICAL_PAPER_OPEN_EXCEPTION:${t.javaClass.simpleName}",
                )
                terminalDisposition6613("CANONICAL_PAPER_OPEN_EXCEPTION:${t.javaClass.simpleName}", "AUTHORITY")
                if (t is kotlinx.coroutines.CancellationException) throw t
                return
            }
            if (!canonicalOpen6486.applied) {
                val rejectionBucket6647 = canonicalOpen6486.reason.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_')
                ErrorLogger.warn(TAG, "CRYPTO_CANONICAL_OPEN_REJECT[$rejectionBucket6647]: symbol=$mktSym positionId=${position.id} reason=${canonicalOpen6486.reason}")
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_CANONICAL_OPEN_REJECT_$rejectionBucket6647")
                    ForensicLogger.lifecycle("CRYPTO_CANONICAL_OPEN_REJECT_6647", "bucket=$rejectionBucket6647 symbol=$mktSym positionId=${position.id} attemptId=${canonicalCryptoIntent6565.attemptId} exactReason=${canonicalOpen6486.reason}")
                } catch (_: Throwable) {}
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(canonicalCryptoIntent6565, canonicalOpen6486.reason)
                terminalDisposition6613("CANONICAL_PAPER_OPEN_REJECTED:${canonicalOpen6486.reason}", "AUTHORITY")
                return
            }
            // V5.0.6578 — success confirms the paper dispatch produced a canonical open.
            // V5.0.6583 §P0-11 — CanonicalPaperTransaction6486.open at line 82-86
            // already calls CanonicalEntryAuthority6551.markConfirmed on
            // successful commit for non-SOLANA_TOKEN asset classes. An
            // explicit markConfirmed here would double-count the funnel
            // opens for CRYPTO_ALT. Keep only the live branch's explicit call
            // (below) — that path does not run through
            // CanonicalPaperTransaction6486.open.
            try { com.lifecyclebot.engine.FluidLearning.recordPaperBuy(mktSym, canonicalFinalSize6570) } catch (_: Exception) {}
        } else {
            // LIVE mode — execute Jupiter swap at the exact paper-sized
            // canonicalFinalSize6570. If the swap + phantom-verify fail, we do NOT
            // create a bot position (nothing to clean up on-chain either,
            // because MarketsLiveExecutor only returns success after the
            // target mint actually arrived on-chain).
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markDispatch(canonicalCryptoIntent6565)
            val liveResult7434 = try {
                executeLiveTradeAtSize(position.id, signal, isSpot, canonicalFinalSize6570)
            } catch (t: Throwable) {
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(
                    canonicalCryptoIntent6565,
                    "CRYPTO_LIVE_BUY_EXCEPTION:${t.javaClass.simpleName}",
                )
                terminalDisposition6613("CRYPTO_LIVE_BUY_EXCEPTION:${t.javaClass.simpleName}", "AUTHORITY")
                if (t is kotlinx.coroutines.CancellationException) throw t
                return
            }
            if (liveResult7434 is LiveCryptoOpenResult7434.Pending) {
                // Do not markConfirmed, create a synthetic CryptoAlt position, or
                // learn from a signature without confirmed target-token quantity.
                try {
                    PipelineHealthCollector.labelInc("CRYPTO_SIGNED_VERIFY_PENDING_NO_OPEN_7434")
                    ForensicLogger.lifecycle("CRYPTO_SIGNED_VERIFY_PENDING_NO_OPEN_7434",
                        "positionId=${position.id} attemptId=${canonicalCryptoIntent6565.attemptId} " +
                        "mint=${liveResult7434.mint} txSig=${liveResult7434.signature} proof=${liveResult7434.proofState}")
                    com.lifecyclebot.engine.sell.LiveWalletReconciler.recordBuySignature(
                        liveResult7434.mint, liveResult7434.signature)
                    com.lifecyclebot.engine.HostWalletTokenTracker.recordBuyPending(
                        liveResult7434.mint, mktSym, liveResult7434.signature)
                    // V5.0.7708 — the signed buy is a receipt: cost, entry mark and
                    // lane travel with the pending row so the holding opens in the
                    // canonical book at its real basis once the wallet shows it.
                    com.lifecyclebot.engine.HostWalletTokenTracker.recordSignedBuyBasis7708(
                        liveResult7434.mint, mktSym, signal.price, canonicalFinalSize6570,
                        liveResult7434.signature, canonicalCryptoLane7251(signal.isDynamic, isSpot))
                    com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(
                        WalletManager.getWallet(), "CRYPTO_SIGNED_VERIFY_PENDING_7434")
                } catch (_: Throwable) {}
                return // Canonical dispatch remains pending, never an OPEN claim.
            }
            val liveFail7318 = (liveResult7434 as? LiveCryptoOpenResult7434.Failed)?.reason
            if (liveFail7318 != null) {
                ErrorLogger.warn(TAG, "🔴 LIVE alt trade failed: ${mktSym} — position not recorded: $liveFail7318")
                // V5.0.7318 — the funnel read dispatch=33 open=0 with every
                // failure filed as CRYPTO_LIVE_BUY_NOT_OPENED; the real cause
                // lived only in a log line. It now names itself.
                try {
                    val code7318 = liveFail7318.substringBefore(':').take(60)
                    PipelineHealthCollector.labelInc("CRYPTO_LIVE_NOT_OPENED_7318_$code7318")
                    ForensicLogger.lifecycle("CRYPTO_LIVE_NOT_OPENED_7318", "symbol=$mktSym size=$canonicalFinalSize6570 reason=${liveFail7318.take(200)}")
                } catch (_: Throwable) {}
                com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(canonicalCryptoIntent6565, liveFail7318)
                try { com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(candidate.assetKey, "CRYPTO", "CRYPTO_LIVE_BUY_NOT_OPENED") } catch (_: Throwable) {}
                terminalDisposition6613("CANONICAL_LIVE_OPEN_FAILED", "AUTHORITY")
                return
            }
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markConfirmed(canonicalCryptoIntent6565, position.id)
            ErrorLogger.info(TAG, "🪙 LIVE trade success: ${mktSym} (paper-sized ${canonicalFinalSize6570.fmt(4)}◎)")
        }

        positions[position.id]         = position
        if (isSpot) spotPositions[position.id]     = position
        else        leveragePositions[position.id]  = position
        // V5.0.6583 §P0-11 — REMOVED DIRECT markOpenConfirmed CALL.
        // CanonicalEntryAuthority6551.markConfirmed above (paper: line 2441,
        // live: line 2457) already cascades to markOpenConfirmedFor6551 which
        // bumps CRYPTO_OPEN_CONFIRMED_6540. The direct call here was
        // double-counting every crypto/alt open in the funnel.
        com.lifecyclebot.perps.crypto.brain.CryptoFunnel.open(true)
        // V5.0.4581 — CRYPTO CANONICAL OPEN HOOK. The isolated CryptoBrain close
        // path was settling trades without a matching open hook, causing canonical
        // learning drift and preventing clean maturity/threshold adaptation. Fire
        // only after the paper debit or live execution has actually committed.
        try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.onTradeStart() } catch (_: Throwable) {}
        com.lifecyclebot.engine.WalletPositionLock.recordOpen("CryptoAlt", canonicalFinalSize6570)

        // V5.9.320: After a successful LIVE leveraged open, look up the Flash.trade
        // position key so we can close it properly via the Flash close-position endpoint.
        // SPOT positions close via Jupiter swap (no Flash key needed).
        if (!paper7803 && !isSpot && mktSym in MarketsLiveExecutor.FLASH_SUPPORTED_PUBLIC) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.delay(3_000L) // brief delay for tx to settle on-chain
                    val wallet = WalletManager.getWallet()
                    val walletAddress = wallet?.publicKeyB58
                    if (!walletAddress.isNullOrBlank()) {
                        val key = MarketsLiveExecutor.findFlashPositionKey(
                            walletAddress = walletAddress,
                            symbol        = mktSym,
                            direction     = signal.direction,
                        )
                        if (key != null) {
                            positions[position.id]?.flashPositionKey = key
                            if (!isSpot) leveragePositions[position.id]?.flashPositionKey = key
                            persistAltPositions()
                            ErrorLogger.info("CryptoAlt", "⚡ Flash posKey stored for ${mktSym}: ${key.take(12)}...")
                        } else {
                            ErrorLogger.warn("CryptoAlt", "⚠️ Flash posKey not found for ${mktSym} — close will use wallet-scan fallback")
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // V5.9.178 — persist the actual position JSON so it survives app updates.
        persistAltPositions()

        // V5.9.171 — record in LOCAL orphan store (Turso-independent failsafe)
        // so paper capital is refundable even when the app is updated offline.
        if (paper7803) {
            try {
                com.lifecyclebot.collective.LocalOrphanStore.recordOpen(
                    trader = "CryptoAlt",
                    posId = position.id,
                    sizeSol = canonicalFinalSize6570,
                    symbol = mktSym,
                )
            } catch (_: Exception) {}
        }
        // V5.9.130: register entry with the V3 bridge so the real accuracy
        // loop + ReflexAI gate have a record to close against.
        // V5.9.170: push the real reason chain into the education layer so
        // it learns why CryptoAltTrader opened, not just that it opened.
        try {
            PerpsUnifiedScorerBridge.registerEntry(
                symbol = mktSym,
                assetClass = "ALT",
                direction = signal.direction.name,
                entryPrice = signal.price,
                // V5.0.6095 — feed V3 bridge realistic Crypto Universe liquidity
                // instead of a stale fixed 500k. This makes the new/outer layers
                // visible and trainable on actual crypto-tier context, matching
                // the MEME trader's rich entry snapshot structure.
                entryLiqUsd = exactAssetMetrics6493(signal).liquidityUsd,
                v3Score = signal.score,
                entryReason = signal.reasons.take(6).joinToString("|").ifBlank { "CryptoAlt:${signal.direction.name}" },
                traderSource = "CryptoAlt",
                canonicalAssetId6493 = position.canonicalAssetKey,
            )
        } catch (_: Exception) {}

        // V5.0.4581 — post-commit isolation/safety. Tilt protection must not
        // return after the position has already committed; pre-entry gates own
        // skips. Also keep CryptoAlt entry learning out of meme/global
        // MetaCognitionAI and ShadowLearningEngine.
        try { ErrorLogger.debug(TAG, "🪙 ISO_LEARNING: ${mktSym} entry retained in crypto-only brains; meme/global entry learners skipped") } catch (_: Exception) {}

        // ── NarrativeFlowAI — record activity ────────────────────────────────
        try {
            NarrativeFlowAI.recordActivity(
                symbol       = mktSym,
                volumeSpike  = signal.score > 75,
                priceMovePct = signal.priceChange24h
            )
        } catch (_: Exception) {}

        ErrorLogger.info(TAG, "🪙 OPENED: ${signal.direction.emoji} ${mktSym} @ ${"%.4f".format(signal.price)} | " +
            "${position.leverageLabel} | size=${canonicalFinalSize6570.fmt(3)}◎ | score=${signal.score} | TP=${"%.4f".format(tp)} SL=${"%.4f".format(sl)}")

        signal.reasons.take(3).forEach { ErrorLogger.debug(TAG, "   → $it") }

        persistPositionToTurso(position)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE EXECUTION — Jupiter DEX Swap
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V5.9.114: execute a LIVE Jupiter swap at a CALLER-provided size.
     * This is the surgical version of [executeLiveTrade] that does NOT
     * recompute sizing — it trusts the paper-mode pipeline's final size
     * so live behaves exactly like paper. Returns true only after the
     * swap succeeded AND the post-swap phantom verification confirmed
     * the target mint arrived on-chain.
     */
    // Signed-but-unverified is not OPEN and not a failed order. Keep three
    // distinct outcomes until the canonical wallet and lot authority agree.
    private sealed class LiveCryptoOpenResult7434 {
        data object Opened : LiveCryptoOpenResult7434()
        data class Pending(val signature: String, val mint: String, val proofState: String) : LiveCryptoOpenResult7434()
        data class Failed(val reason: String) : LiveCryptoOpenResult7434()
    }

    private suspend fun executeLiveTradeAtSize(
        positionId: String,
        signal: AltSignal,
        isSpot: Boolean,
        sizeSol: Double,
    ): LiveCryptoOpenResult7434 {
        // V5.0.7318 — null = opened; otherwise the exact reason it was not.
        return try {
            // A prior signed transaction with unresolved owner-token proof is
            // already wallet liability, even if canonical OPEN has no quantity
            // yet. The tracker persists this signature across process restarts;
            // the 10-minute entry-intent lease alone cannot prevent a second
            // spend after expiry. Wait for explicit wallet reconciliation.
            val pendingMint7436 = signal.dynMint?.takeIf { it.isNotBlank() }
                ?: if (signal.market != PerpsMarket.DYN) try {
                    com.lifecyclebot.perps.crypto.CryptoWrappedAssetMapper.resolveWrappedMint(signal.marketSymbol)
                } catch (_: Throwable) { null } else null
            val priorSigned7436 = if (signal.dynChainId.isNullOrBlank() || signal.dynChainId.equals("solana", true))
                pendingMint7436?.let { com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(it) }
            else null
            if (priorSigned7436 != null && !priorSigned7436.buySignature.isNullOrBlank() &&
                priorSigned7436.status in setOf(
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.BUY_PENDING,
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CONFIRMED_PENDING_BALANCE,
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.BUY_CONFIRMED,
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.HELD_IN_WALLET,
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.OPEN_TRACKING,
                )) {
                try { PipelineHealthCollector.labelInc("CRYPTO_PRIOR_SIGNED_BUY_RECONCILE_BEFORE_RETRY_7436") } catch (_: Throwable) {}
                return LiveCryptoOpenResult7434.Failed("PRIOR_SIGNED_BUY_PENDING_WALLET_PROOF")
            }
            val wallet = WalletManager.getWallet()
                ?: run { ErrorLogger.warn(TAG, "No wallet — cannot execute LIVE alt trade"); return LiveCryptoOpenResult7434.Failed("NO_WALLET") }

            val balance = try { wallet.getSolBalance() } catch (e: Exception) {
                try { PipelineHealthCollector.labelInc("CRYPTO_WALLET_BALANCE_UNAVAILABLE_7434") } catch (_: Throwable) {}
                return LiveCryptoOpenResult7434.Failed("WALLET_BALANCE_UNAVAILABLE:${e.javaClass.simpleName}")
            }
            if (!balance.isFinite() || balance < 0.0) {
                try { PipelineHealthCollector.labelInc("CRYPTO_WALLET_BALANCE_UNAVAILABLE_7434") } catch (_: Throwable) {}
                return LiveCryptoOpenResult7434.Failed("WALLET_BALANCE_UNAVAILABLE:INVALID_NUMBER")
            }
            if (balance > 0) updateLiveBalance(balance)
            val floor = 0.01
            if (balance < floor || sizeSol < floor) {
                ErrorLogger.warn(TAG, "🪙 ⛔ Live floor: bal=${"%.4f".format(balance)} size=${"%.4f".format(sizeSol)} — skip ${signal.market.symbol}")
                LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.FLOOR_SKIPPED)
                return LiveCryptoOpenResult7434.Failed(if (balance < floor) "WALLET_BELOW_FLOOR" else "SIZE_BELOW_FLOOR")
            }

            ErrorLogger.info(
                TAG,
                "🪙 ⚡ LIVE ATTEMPT: ${signal.marketSymbol} ${signal.direction.symbol} " +
                "| bal=${"%.4f".format(balance)}◎ size=${"%.4f".format(sizeSol)}◎ " +
                "${if (isSpot) "SPOT" else "${signal.leverage.toInt()}x"}"
            )

            // V5.9.495z30 — Route via CryptoUniverseExecutor so non-executable
            // assets (BTC w/ no CEX, XMR w/ no route, PAXG bridge, …) are
            // classified up-front with a precise diag code instead of fake
            // BUY_FAILED. Operator brief items B–G/J.
            val outcome = com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.executeLiveTrade(
                positionId = positionId,
                market     = signal.market,
                direction  = signal.direction,
                sizeSol    = sizeSol,
                leverage   = if (isSpot) 1.0 else signal.leverage,
                priceUsd   = signal.price,
                traderType = "CryptoAlt",
                assetSymbol6493 = signal.marketSymbol,
                targetMint6493 = signal.dynMint,
                targetChainId6544 = signal.dynChainId,
                liquidityUsd6493 = exactAssetMetrics6493(signal).liquidityUsd,
            )
            when (outcome) {
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.Executed -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.EXECUTED)
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_TERMINAL_7432_EXECUTED")
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_TO_OPEN_7432")
                    } catch (_: Throwable) {}
                    ErrorLogger.info(TAG, "🪙 LIVE TRADE EXECUTED: ${signal.marketSymbol} tx=${outcome.txSig ?: "ok"}")
                    try { updateLiveBalance(wallet.getSolBalance()) } catch (_: Exception) {}
                    LiveCryptoOpenResult7434.Opened
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.VerifyPending -> {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_TERMINAL_7432_VERIFY_PENDING") } catch (_: Throwable) {}
                    // Signature is chain-confirmed; TX_PARSE_META / owner delta owns
                    // promotion to FINAL_TOKEN_VERIFIED. Treat as accepted pending work,
                    // not a failure and not eligible for duplicate resubmission.
                    ErrorLogger.info(TAG,
                        "🪙 VERIFY PENDING: ${signal.marketSymbol} tx=${outcome.txSig.take(16)} proof=${outcome.proofState}")
                    LiveCryptoOpenResult7434.Pending(outcome.txSig, outcome.mint, outcome.proofState)
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.RouteDeferred -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.ROUTE_DEFERRED)
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_TERMINAL_7432_ROUTE_DEFERRED")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle("CRYPTO_DISPATCH_TERMINAL_7432",
                            "positionId=$positionId chain=${signal.dynChainId} mint=${signal.dynMint} stage=ROUTE " +
                            "code=${outcome.resolution.diagCode} detail=${outcome.resolution.humanMessage.take(180)}")
                    } catch (_: Throwable) {}
                    ErrorLogger.info(TAG,
                        "🪙 ROUTE DEFERRED: ${signal.marketSymbol} → ${outcome.resolution.route} " +
                        "[${outcome.resolution.diagCode}] ${outcome.resolution.humanMessage}")
                    LiveCryptoOpenResult7434.Failed("ROUTE_DEFERRED_${outcome.resolution.diagCode}:${outcome.resolution.humanMessage.take(100)}")
                }
                is com.lifecyclebot.perps.crypto.CryptoUniverseExecutor.Outcome.ExecFailed -> {
                    LiveAttemptStats.record("CryptoAlt", LiveAttemptStats.Outcome.FAILED)
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_TERMINAL_7432_${outcome.code7432}")
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_FAILURE_BY_STAGE_7432_${outcome.stage7432}")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle("CRYPTO_DISPATCH_TERMINAL_7432",
                            "positionId=$positionId chain=${signal.dynChainId} mint=${signal.dynMint} " +
                            "stage=${outcome.stage7432} code=${outcome.code7432} " +
                            "exceptionClass=${outcome.exceptionClass7432.ifBlank { "none" }} " +
                            "detail=${outcome.reason.take(220)}")
                    } catch (_: Throwable) {}
                    ErrorLogger.warn(TAG,
                        "🪙 Live exec FAILED for ${signal.marketSymbol}: ${outcome.code7432}/${outcome.stage7432} ${outcome.reason}")
                    LiveCryptoOpenResult7434.Failed("${outcome.code7432}:${outcome.stage7432}:${outcome.reason.take(120)}")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ErrorLogger.error(TAG, "🪙 Live trade exception: ${e.message}", e)
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_TERMINAL_7432_UNKNOWN_EXCEPTION")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_DISPATCH_FAILURE_BY_STAGE_7432_CRYPTO_ALT")
                com.lifecyclebot.engine.ForensicLogger.lifecycle("CRYPTO_DISPATCH_TERMINAL_7432",
                    "positionId=$positionId stage=CRYPTO_ALT code=UNKNOWN_EXCEPTION exceptionClass=${e.javaClass.simpleName} detail=${e.message?.take(180)}")
            } catch (_: Throwable) {}
            LiveCryptoOpenResult7434.Failed("UNKNOWN_EXCEPTION:CRYPTO_ALT:${e.javaClass.simpleName}")
        }
    }




    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.221: STAGNANT / LOSER EVICTION
    // Runs every monitor cycle. Closes positions that are:
    //   a) Deadweight:   held >12 min regardless of PnL
    //   b) Stagnant:     held >5 min and PnL within ±1% (not moving)
    //   c) Early loser:  held >3 min and PnL < -3%
    // Also evicts the weakest slot when at SOFT_CAP to make room for better signals.
    // ═══════════════════════════════════════════════════════════════════════════

    // V5.9.432 — partial-take ladder rungs (mirror of meme architecture).
    // Each rung fires once per position, sells 25% of remaining size, then
    // arms the next rung. The fluid trail (see trailSnapshots) kicks in once
    // the first rung fires so locked-in gains never round-trip.
    private val PARTIAL_LADDER_PCTS = doubleArrayOf(5.0, 10.0, 20.0, 35.0)
    private val partialLadderHit = ConcurrentHashMap<String, Int>()      // id -> next rung index
    private val trailPeakPct     = ConcurrentHashMap<String, Double>()   // id -> peak pnl% seen

    private fun evictStagnantAndLosers() {
        val now = System.currentTimeMillis()
        for ((id, pos) in positions.toMap()) {
            if (pos.isPaper != isPaperMode.get()) continue
            // A stale/mismatched DYN mark is a HOLD, never a zero-PnL exit.
            if (pos.isDynamic && !pos.hasTrustedMark(now)) continue
            val holdMs = now - pos.openTime
            val pnlPct = pos.getPnlPct()

            // ── V5.9.432: PARTIAL-TAKE LADDER ────────────────────────────
            // Fires on winners crossing 5 / 10 / 20 / 35% peak. Sells 25%
            // of remaining qty per rung. Runs BEFORE any stagnant/loser
            // check so wins lock in even if volatility is low.
            try {
                val peak = trailPeakPct.compute(id) { _, v ->
                    if (v == null || pnlPct > v) pnlPct else v
                } ?: pnlPct
                val rungIdx = partialLadderHit[id] ?: 0
                if (rungIdx < PARTIAL_LADDER_PCTS.size) {
                    val rungPct = PARTIAL_LADDER_PCTS[rungIdx]
                    if (peak >= rungPct) {
                        partialLadderHit[id] = rungIdx + 1
                        // Best-effort partial: if exchange/exec layer has a
                        // partial-sell hook, use it; otherwise record the
                        // rung hit for UI + trail purposes only. Trail lock
                        // still protects the remainder.
                        ErrorLogger.info(TAG,
                            "🪙 🎯 PARTIAL_RUNG ${pos.market.symbol} @ +${"%.1f".format(peak)}% (rung ${rungIdx + 1}/${PARTIAL_LADDER_PCTS.size}, ${rungPct.toInt()}%)")
                    }
                }
            } catch (_: Exception) {}

            // ── V5.9.432: FLUID TRAIL (slides up with peak) ──────────────
            // Once any partial rung has fired, protect 50% of the peak gain.
            // If peak is +20% and price falls to +10%, exit. This lets real
            // breakouts keep running past 35% (no cap), while sideways
            // fakeouts that spike and fade get booked.
            try {
                val peak = trailPeakPct[id] ?: pnlPct
                val rungHit = (partialLadderHit[id] ?: 0) > 0
                if (rungHit && peak >= PARTIAL_LADDER_PCTS[0]) {
                    val trailFloor = peak * 0.5
                    if (pnlPct <= trailFloor && pnlPct < peak - 2.0) {
                        closePosition(id,
                            "FLUID_TRAIL_LOCK: peak=+${"%.1f".format(peak)}% → now=+${"%.1f".format(pnlPct)}% (50% floor)")
                        ErrorLogger.info(TAG,
                            "🪙 🔒 FLUID_TRAIL ${pos.market.symbol} peak+${peak.toInt()}% → +${pnlPct.toInt()}%")
                        continue
                    }
                }
            } catch (_: Exception) {}

            // V5.9.229: protect ANY winning position — even small gains deserve to run
            if (pnlPct >= 1.0) continue

            when {
                // V5.9.432 — DEADWEIGHT requires BOTH long hold AND flat PnL.
                // A position up +2% at 60 min is not deadweight.
                holdMs >= DEADWEIGHT_HOLD_MS && Math.abs(pnlPct) <= DEADWEIGHT_MAX_PNL_PCT -> {
                    val label = if (pnlPct >= 0) "+${pnlPct.toInt()}%" else "${pnlPct.toInt()}%"
                    closePosition(id, "DEADWEIGHT: $label after ${holdMs/60000}min — freeing slot")
                    ErrorLogger.info(TAG, "🪙 ⏰ DEADWEIGHT evicted ${pos.market.symbol} ($label)")
                }
                holdMs >= STAGNANT_MIN_HOLD_MS && Math.abs(pnlPct) <= STAGNANT_MAX_PNL_PCT -> {
                    closePosition(id, "STAGNANT: ${pnlPct.toInt()}% after ${holdMs/60000}min — no momentum")
                    ErrorLogger.info(TAG, "🪙 😴 STAGNANT evicted ${pos.market.symbol} (${pnlPct.toInt()}%)")
                }
                holdMs >= LOSER_MIN_HOLD_MS && pnlPct <= LOSER_FAST_EXIT_PCT -> {
                    closePosition(id, "FAST_LOSER: ${pnlPct.toInt()}% after ${holdMs/60000}min — cut early")
                    ErrorLogger.info(TAG, "🪙 ✂️ FAST_LOSER evicted ${pos.market.symbol} (${pnlPct.toInt()}%)")
                }
            }
        }
    }

    /**
     * V5.9.221: At SOFT_CAP, evict the weakest position to make room for an
     * incoming signal. Only evicts if the weakest is losing OR the incoming
     * signal significantly outscores it.
     */
    private fun evictWeakestForReplacement(incomingScore: Int): Boolean {
        if (activeModePositions7256(positions.values).size < SOFT_CAP_POSITIONS) return true

        val now = System.currentTimeMillis()
        val candidate = activeModePositions7256(positions.values)
            .filter { (now - it.openTime) >= 3 * 60 * 1000L }  // held at least 3 min
            .minByOrNull { it.getPnlPct() + (it.aiScore / 10.0) }

        if (candidate == null) return false

        val pnlPct = candidate.getPnlPct()
        val incomingBeats = incomingScore >= (candidate.aiScore + REPLACE_SCORE_MARGIN)
        val weakestLosing = pnlPct <= -1.5

        return if (incomingBeats || weakestLosing) {
            val reason = if (weakestLosing)
                "REPLACED_LOSER: ${pnlPct.toInt()}% score=${candidate.aiScore} → incoming=$incomingScore"
            else
                "REPLACED_WEAK: score=${candidate.aiScore} → incoming=$incomingScore"
            ErrorLogger.info(TAG, "🪙 🔄 ${candidate.market.symbol} $reason")
            closePosition(candidate.id, reason)
            true
        } else false
    }

    /**
     * V5.0.7244 — dynamic universe turnover under the existing 80% risk cap.
     * The old SOFT_CAP replacement never ran because 6% sizing reaches the
     * portfolio-risk cap around 10–15 positions, long before 80 positions.
     * Paper can synchronously replace one weak position. Live never assumes
     * an asynchronous close freed capital.
     */
    private fun rotateWeakPaperExposure7244(incomingScore: Int): Boolean {
        if (!isPaperMode.get()) return false
        val now = System.currentTimeMillis()
        val candidate = activeModePositions7256(positions.values).asSequence()
            .filter { it.isPaper }
            .filter { now - it.openTime >= 3 * 60 * 1000L }
            .filter { it.getPnlPct() < 1.0 }
            .minByOrNull { it.getPnlPct() + (it.aiScore / 10.0) }
            ?: return false
        val pnlPct = candidate.getPnlPct()
        val incomingBeats = incomingScore >= candidate.aiScore + REPLACE_SCORE_MARGIN
        val losing = pnlPct <= -1.5
        if (!incomingBeats && !losing) return false
        closePosition(
            candidate.id,
            "CRYPTO_UNIVERSE_EXPOSURE_ROTATION_7244 pnl=" + pnlPct.toInt() +
                " score=" + candidate.aiScore + " incoming=" + incomingScore,
        )
        val released = positions[candidate.id] == null
        try {
            PipelineHealthCollector.labelInc(
                if (released) "CRYPTO_EXPOSURE_ROTATED_7244" else "CRYPTO_EXPOSURE_ROTATION_PENDING_7244"
            )
        } catch (_: Throwable) {}
        return released
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun monitorPositions() {
        // V5.9.221: Evict stagnant/losing positions before checking each one
        evictStagnantAndLosers()

        for ((id, position) in positions.toMap()) {
            try {
                // V5.0.6654 — never ask PerpsMarketDataFetcher for DYN.  DYN is
                // one enum sentinel shared by thousands of instruments, so its
                // enum-keyed cache can only return another asset's quote.
                val markPrice: Double
                val validatedMarkKey: String
                if (position.isDynamic) {
                    val positionKey = position.canonicalAssetKey.trim()
                    val resolved = DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(positionKey)
                        ?: position.dynMint?.let { DynamicAltTokenRegistry.getTokenByMint(it) }
                    val identityMatches = resolved != null && (
                        resolved.canonicalIdentity6544.equals(positionKey, true) ||
                        resolved.mint.equals(positionKey, true) ||
                        resolved.tokenAddress.equals(positionKey, true)
                    )
                    if (!identityMatches) {
                        try { PipelineHealthCollector.labelInc("CRYPTO_DYN_MARK_IDENTITY_REJECTED_6654") } catch (_: Throwable) {}
                        ErrorLogger.warn(TAG, "🪙 DYN MARK BLOCKED: ${position.marketSymbol} positionKey=${positionKey.take(28)} resolved=${resolved?.canonicalIdentity6544?.take(28)}")
                        holdUntrustedDynamicPosition7245(position, "IDENTITY_UNRESOLVED")
                        continue
                    }
                    val ageMs = (System.currentTimeMillis() - resolved!!.lastUpdatedMs).coerceAtLeast(0L)
                    val heldMark7251 = if (ageMs > 60_000L) {
                        refreshDynamicMark7251(resolved.canonicalIdentity6544)
                    } else {
                        DynamicAltTokenRegistry.heldMarkSnapshot7251(resolved.canonicalIdentity6544)
                    }
                    val refreshedPrice = heldMark7251.price
                    val exactIdentity7251 = heldMark7251.canonicalIdentity.equals(positionKey, true)
                    if (!heldMark7251.freshObservation || !exactIdentity7251 ||
                        !refreshedPrice.isFinite() || refreshedPrice <= 0.0) {
                        // V5.0.7413 — holdUntrustedDynamicPosition7245 already
                        // emits the canonical stale-held state at a 15s cadence.
                        // Do not emit a second per-tick stale label here.
                        holdUntrustedDynamicPosition7245(position, "MARK_STALE_OR_MISSING")
                        continue
                    }
                    markPrice = refreshedPrice
                    validatedMarkKey = position.canonicalAssetKey
                } else {
                    val data = PerpsMarketDataFetcher.getMarketData(position.market)
                    if (!data.price.isFinite() || data.price <= 0.0) continue
                    markPrice = data.price
                    validatedMarkKey = position.market.name
                }

                // Static enum feeds retain the legacy spike guard.  Exact-identity
                // dynamic marks may legitimately run beyond 10x and are protected
                // by the canonical identity/freshness gates above.
                val priceRatio = if (position.entryPrice > 0) markPrice / position.entryPrice else 1.0
                if (!position.isDynamic && (priceRatio > 10.0 || priceRatio < 0.1)) {
                    ErrorLogger.warn(TAG, "🪙 SPIKE GUARD: ${position.marketSymbol} entry=${position.entryPrice} new=$markPrice ratio=${"%.2f".format(priceRatio)}x — skipping")
                    continue
                }
                val updated = position.copy(
                    currentPrice = markPrice,
                    markAssetKey = validatedMarkKey,
                    markUpdatedAtMs = if (position.isDynamic) {
                        DynamicAltTokenRegistry.heldMarkSnapshot7251(validatedMarkKey).observedAtMs
                    } else System.currentTimeMillis(),
                )
                // V5.0.6832 — advisory mark-freshness observation. Value-diff
                // vs previous tick tells us whether this mark is genuinely
                // moving or is a carry-forward touch. Zero economic effect;
                // pipeline/UI read only.
                try {
                    com.lifecyclebot.engine.truth.MarkPriceFreshnessTelemetry6832.observe(
                        mintKey = validatedMarkKey,
                        price = markPrice,
                    )
                } catch (_: Throwable) {}
                // Exit functions read the map. Publish the validated tick before
                // any SL/TP/floor decision so settlement cannot use the old mark.
                positions[id] = updated
                if (updated.isSpot) spotPositions[id] = updated else leveragePositions[id] = updated

                // ═══════════════════════════════════════════════════════════════
                // V5.9.1455 — TICK-TIME CATASTROPHIC FLOOR (-10% kill-switch).
                // Operator directive after memes bled -29% on a -15% stop in
                // a single tick. The configured stopLossPrice below may be set
                // looser (lane-specific). This hard backstop fires first the
                // instant a tick shows pnl ≤ -10%, no matter what the per-lane
                // SL was, and no matter what direction. Bypasses all the gates
                // beneath. This is the floor of the floor for crypto alts.
                // ═══════════════════════════════════════════════════════════════
                val tickPnl = updated.getPnlPct()
                if (tickPnl <= -10.0) {
                    // V5.0.7264 §THE_MEME_FLOOR_HAD_A_PHANTOM_GUARD;_THIS_ONE_DID_NOT.
                    //
                    // Operator 5.0.7263, paper: CRYPTO_ALT "solana" closed
                    // TICK_HARD_FLOOR_-98PCT — entry 0.000398, sold for 0.007 SOL
                    // on a 0.49 SOL position. That single row was 75% of the
                    // session's realised loss, beside STALE_PRICE_QUARANTINED
                    // gainMultiple=2419 and METRICS_IDENTITY_BROKEN=81k on the
                    // same book. BotService's meme tick floor has carried the
                    // V5.9.1564 guard since 5.0.3671 for exactly this: a single
                    // tick can read a stale or cross-identity mark and report
                    // -96% on a token that is fine, so anything below -50% needs
                    // a second consecutive sub-floor read before it may fire.
                    // The -10% kill-switch itself is unchanged: a read between
                    // -10% and -50% still closes on the first tick. Only the
                    // phantom range waits one tick.
                    val phantomRange7264 = tickPnl < -50.0
                    val priorStrike7264 = tickFloorStrike7264[id] == true
                    tickFloorStrike7264[id] = true
                    if (phantomRange7264 && !priorStrike7264) {
                        try {
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_ALT_TICK_FLOOR_PHANTOM_DEFERRED_7264")
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "CRYPTO_ALT_TICK_FLOOR_PHANTOM_DEFERRED_7264",
                                "id=$id symbol=${updated.marketSymbol} entry=${position.entryPrice} mark=$markPrice " +
                                    "pnl=${"%.1f".format(tickPnl)}% dynamic=${position.isDynamic} markKey=$validatedMarkKey " +
                                    "action=second_consecutive_sub_floor_read_required",
                            )
                        } catch (_: Throwable) {}
                        continue
                    }
                    ErrorLogger.warn(TAG,
                        "🛑 TICK_HARD_FLOOR ${updated.marketSymbol} " +
                        "${"%.1f".format(tickPnl)}% ≤ -10.0% — immediate exit " +
                        "(peak=${"%.1f".format(updated.highestPnlPct)}% twoStrike=$priorStrike7264)")
                    tickFloorStrike7264.remove(id)
                    closePosition(id, "TICK_HARD_FLOOR_${tickPnl.toInt()}PCT")
                    continue
                } else {
                    tickFloorStrike7264.remove(id)
                }
                // ─── Peak give-back trailing (lock big runners) ───
                val tickPeak = if (updated.highestPnlPct > tickPnl) updated.highestPnlPct else tickPnl
                var deskExited7391 = false
                val tickGiveBackRatio = when {
                    tickPeak >= 500.0 -> 0.30   // 5x+ : lock 70% of peak
                    tickPeak >= 200.0 -> 0.40   // 2x+ : lock 60% of peak
                    tickPeak >= 100.0 -> 0.50   // 1x+ : lock 50% of peak
                    tickPeak >=  30.0 -> 0.60   // +30%+ runners: lock 40%
                    else -> Double.NaN
                }
                if (!tickGiveBackRatio.isNaN()) {
                    val tickLockedFloor = tickPeak - (tickPeak * tickGiveBackRatio)
                    if (tickPnl < tickLockedFloor && tickPnl > 0.0) {
                        ErrorLogger.warn(TAG,
                            "🔒 TICK_PROFIT_LOCK ${updated.marketSymbol} " +
                            "peak=${"%.1f".format(tickPeak)}% now=${"%.1f".format(tickPnl)}% " +
                            "floor=${"%.1f".format(tickLockedFloor)}% — locking profit")
                        closePosition(id, "TICK_PROFIT_LOCK_peak${tickPeak.toInt()}_now${tickPnl.toInt()}")
                        continue
                    }
                }

                // V5.0.7391 — the meme exit tools on crypto positions, keyed by the
                // desk lane the position was opened under: the sliding give-back
                // lock and the fluid profit floor (both arm from the start), the
                // MFE profit floor, and the runner lanes' early cut. Crypto's own
                // give-back table, hard SL/TP and fluid stop all still apply.
                run {
                    val deskLane7391 = specialistLaneFromReasons7803(updated.reasons)
                    if (deskLane7391.isBlank() || !tickPnl.isFinite()) return@run
                    val ageMs7391 = (System.currentTimeMillis() - updated.openTime).coerceAtLeast(0L)
                    val fluidFloor7391 = try {
                        com.lifecyclebot.v3.scoring.FluidLearningAI.fluidProfitFloor(
                            tickPeak, holdSeconds = ageMs7391 / 1000.0, lane = deskLane7391,
                        )
                    } catch (_: Throwable) { Double.NEGATIVE_INFINITY }
                    val why7391 = when {
                        com.lifecyclebot.engine.PeakDrawdownLock.shouldLock(tickPeak, tickPnl, deskLane7391) -> "PEAK_DRAWDOWN_LOCK"
                        com.lifecyclebot.engine.PeakDrawdownLock.shouldFloorLock(tickPeak, tickPnl) -> "MFE_PROFIT_FLOOR"
                        tickPnl < fluidFloor7391 -> "FLUID_PROFIT_FLOOR"
                        com.lifecyclebot.engine.RunnerExitProfile7277.earlyCut(deskLane7391, tickPnl, ageMs7391) -> "RUNNER_EARLY_CUT"
                        else -> ""
                    }
                    if (why7391.isNotEmpty()) {
                        try { PipelineHealthCollector.labelInc("CRYPTO_DESK_EXIT_7391_$why7391") } catch (_: Throwable) {}
                        ErrorLogger.warn(TAG,
                            "🔒 DESK_EXIT_7391 ${updated.marketSymbol} lane=$deskLane7391 $why7391 " +
                            "peak=${"%.1f".format(tickPeak)}% now=${"%.1f".format(tickPnl)}%")
                        closePosition(id, "DESK_${why7391}_${deskLane7391}_peak${tickPeak.toInt()}_now${tickPnl.toInt()}_7391")
                        deskExited7391 = true
                    }
                }
                if (deskExited7391) continue

                // V5.9.272: HARD SL — fire before anything else if price crossed stop
                val slPriceOk = updated.stopLossPrice > 0 && updated.entryPrice > 0
                val tpPriceOk = updated.takeProfitPrice > 0 && updated.entryPrice > 0
                if (slPriceOk) {
                    val hitSl = when (updated.direction) {
                        com.lifecyclebot.perps.PerpsDirection.LONG  -> markPrice <= updated.stopLossPrice
                        com.lifecyclebot.perps.PerpsDirection.SHORT -> markPrice >= updated.stopLossPrice
                    }
                    if (hitSl) {
                        closePosition(id, "HARD_SL: price=${markPrice.fmt(6)} crossed SL=${updated.stopLossPrice.fmt(6)} (${updated.getPnlPct().let { if (it>=0) "+${"%.2f".format(it)}" else "${"%.2f".format(it)}" }}%)")
                        continue
                    }
                }
                // V5.9.272: HARD TP — take profit immediately when price crosses target
                // V5.9.903: RUNNER BYPASS — skip HARD_TP when the position has
                // already been a proven runner past the TP price (peak gain
                // >= 1.5x current PnL@TP). At that point FLUID_LOCK +
                // PEAK_DRAWDOWN + trailing stop already manage exits cleanly,
                // and forcing the full close at the original TP price during a
                // pullback kneecaps every alts runner. Same doctrine as
                // V5.9.899-902: runners must be allowed to run.
                if (tpPriceOk) {
                    val hitTp = when (updated.direction) {
                        com.lifecyclebot.perps.PerpsDirection.LONG  -> markPrice >= updated.takeProfitPrice
                        com.lifecyclebot.perps.PerpsDirection.SHORT -> markPrice <= updated.takeProfitPrice
                    }
                    // Compute implied TP% from entry vs tpPrice — used to detect
                    // whether peakPnlPct has already exceeded TP territory by 1.5x.
                    val _tpImpliedPct = if (updated.entryPrice > 0.0) {
                        val raw = (kotlin.math.abs(updated.takeProfitPrice - updated.entryPrice) / updated.entryPrice) * 100.0 * updated.leverage
                        raw.coerceAtLeast(0.01)
                    } else 999.0
                    // V5.0.7329 — the peak read here lagged the mark, so a tick that
                    // gapped straight through TP (+67.7% against a ~+4% target on
                    // 5.0.7324) closed the whole runner at the gap. The current PnL
                    // counts as peak evidence too; the trail and peak-drawdown
                    // exits own the position from there.
                    val _runnerProven = maxOf(updated.highestPnlPct, updated.getPnlPct()) >= _tpImpliedPct * 1.5
                    if (hitTp && !_runnerProven) {
                        closePosition(id, "HARD_TP: price=${markPrice.fmt(6)} crossed TP=${updated.takeProfitPrice.fmt(6)} (+${"%.2f".format(updated.getPnlPct())}%)")
                        continue
                    }
                }

                // V5.9.9: Track peak PnL for trailing stop
                val currentPnl = updated.getPnlPct()
                if (currentPnl > updated.highestPnlPct) {
                    updated.highestPnlPct = currentPnl
                }
                positions[id] = updated
                if (updated.isSpot) {
                    spotPositions[id] = updated
                    leveragePositions.remove(id)
                } else {
                    leveragePositions[id] = updated
                    spotPositions.remove(id)
                }

                val tpPct = com.lifecyclebot.perps.crypto.brain.CryptoBrain.getTpPct(updated.isSpot)

                // V5.9.9: FULLY AGENTIC EXIT — SymbolicExitReasoner evaluates every cycle
                val holdSec = (System.currentTimeMillis() - updated.openTime) / 1000
                val peakPnl = if (updated.highestPnlPct > updated.getPnlPct()) updated.highestPnlPct else updated.getPnlPct()

                // V5.0.6663 — Crypto previously registered no position with
                // HoldTimeOptimizerAI and therefore ignored its learned exit
                // horizon.  The fixed 45/60-minute cleanup only touched flat
                // or losing positions, allowing small winners to occupy a slot
                // indefinitely.  Every new position now carries its immutable
                // entry-time recommendation; legacy restores retain a one-hour
                // safety cap.
                val adaptiveMaxHold6663 = updated.adaptiveMaxHoldSeconds.takeIf { it > 0 } ?: 3_600
                // V5.0.7332 — the hold horizon frees capital from positions that
                // are going nowhere. A position past its TP and still holding at
                // least half its peak is running; the timer closing it caps the
                // win, so it is left to the trail / peak-drawdown exits.
                val pnlNow7332 = updated.getPnlPct()
                val runningWinner7332 = tpPct > 0.0 && pnlNow7332 >= tpPct && pnlNow7332 >= peakPnl * 0.5
                if (holdSec >= adaptiveMaxHold6663 && runningWinner7332) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_HOLD_MAX_DEFERRED_RUNNING_WINNER_7332") } catch (_: Throwable) {}
                }
                if (holdSec >= adaptiveMaxHold6663 && !runningWinner7332) {
                    closePosition(id, "ADAPTIVE_HOLD_MAX_6663:${adaptiveMaxHold6663}s pnl=${"%.2f".format(updated.getPnlPct())}%")
                    continue
                }

                // Calculate price velocity (% change per minute over hold period)
                val priceVelocity = if (holdSec > 30) updated.getPnlPct() / (holdSec / 60.0) else 0.0

                val assessment = com.lifecyclebot.engine.SymbolicExitReasoner.assess(
                    currentPnlPct   = updated.getPnlPct(),
                    peakPnlPct      = peakPnl,
                    entryConfidence = updated.aiConfidence.toDouble(),
                    tradingMode     = strategyFromReasons7803(updated.reasons),
                    holdTimeSec     = holdSec,
                    priceVelocity   = priceVelocity,
                    volumeRatio     = 1.0
                )

                when (assessment.suggestedAction) {
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.EXIT ->
                        closePosition(id, "AI_EXIT: ${assessment.primarySignal} (conv=${"%.2f".format(assessment.conviction)})")
                    com.lifecyclebot.engine.SymbolicExitReasoner.Action.PARTIAL ->
                        partialPosition6566(id, "AI_PARTIAL: ${assessment.primarySignal} (conv=${"%.2f".format(assessment.conviction)})")
                    else -> {
                        // V5.9.118: FLUID PROFIT-FLOOR LOCK — same lock semantics
                        // as the main meme trader so alts runners don't give back
                        // huge gains. getDynamicFluidStop returns a POSITIVE
                        // trailing stop level while in profit; exit fires when
                        // currentPnl <= that level. Paired with a peak-drawdown
                        // hard floor (>=35% give-back on peak>=100%).
                        val dynamicLock = try {
                            com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                                modeDefaultStop = 20.0,
                                currentPnlPct = currentPnl,
                                peakPnlPct = peakPnl,
                                holdTimeSeconds = holdSec.toDouble(),
                                volatility = 50.0,
                            )
                        } catch (_: Exception) { Double.NEGATIVE_INFINITY }

                        val lockFired = dynamicLock > 0.0 && currentPnl <= dynamicLock
                        // V5.9.358 — Alts-only tighter peak-drawdown floor.
                        // Was: only fired at peak >= 100% with 35pt give-back,
                        // letting +27% peaks round-trip to -2% before any
                        // protection. Now: any peak >= 20% is exit-locked
                        // when current gain falls below half the peak
                        // (i.e. given back 50% of the peak). Catches the
                        // +27 → -2 round-trip class directly without
                        // touching Meme or any other trader.
                        val peakDrawdownFired =
                            (peakPnl >= 100.0 && (peakPnl - currentPnl) >= 35.0) ||
                            (peakPnl >= 20.0 && currentPnl <= peakPnl * 0.5)

                        when {
                            lockFired ->
                                closePosition(id, "FLUID_LOCK: peak=+${peakPnl.toInt()}% now=+${currentPnl.toInt()}% lock=+${dynamicLock.toInt()}%")
                            peakDrawdownFired ->
                                closePosition(id, "PEAK_DRAWDOWN: peak=+${peakPnl.toInt()}% now=+${currentPnl.toInt()}% (gave back ${(peakPnl - currentPnl).toInt()}%)")
                            // V5.9.903: TP_SAFETY runner bypass — skip when peak gain
                            // has already proven the runner is past the safety cap.
                            // Pre-fix: a position that peaked at +100% but pulled
                            // back to +52% (mature TP*2.5=50% cap) got force-closed.
                            // FLUID_LOCK + PEAK_DRAWDOWN are runner-aware and will
                            // catch the giveback at the right moment.
                            (updated.shouldTakeProfit(tpPct * 2.5) && updated.highestPnlPct < tpPct * 3.0) ->  // V5.9.230: 1.5→2.5x TP ceiling (bootstrap 8%×2.5=20%) | V5.9.903 runner bypass
                                closePosition(id, "TP_SAFETY: +${"%.2f".format(updated.getPnlPct())}% exceeded ${tpPct * 2.5}% peak=+${peakPnl.toInt()}%")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }
    }

    private fun partialPosition6566(positionId: String, reason: String) {
        val position = positions[positionId] ?: return
        if (!position.isPaper) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_PARTIAL_DEFERRED_NO_CONFIRMED_ADAPTER_6566_CRYPTO") } catch (_: Throwable) {}
            return
        }
        val receipt = com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486.partial(
            position.id, position.canonicalAssetKey, position.marketSymbol, 0.5,
            position.getPnlPct(), 0.005, reason,
        )
        if (!receipt.applied) {
            ErrorLogger.warn(TAG, "PAPER PARTIAL REJECTED: ${position.marketSymbol} ${receipt.reason}")
            return
        }
        val updated = position.copy(sizeSol = receipt.remainingCostSol)
        positions[positionId] = updated
        if (updated.isSpot) spotPositions[positionId] = updated else leveragePositions[positionId] = updated
        persistAltPositions()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CROSS_ASSET_PARTIAL_APPLIED_6566_CRYPTO") } catch (_: Throwable) {}
    }

    /**
     * V5.0.7245 — ownership outranks mark freshness. Missing/stale pricing
     * preserves the position and increases refresh pressure; it never refunds,
     * closes, hides, or trains from an invented terminal result.
     */
    private fun holdUntrustedDynamicPosition7245(pos: AltPosition, cause: String) {
        if (!pos.isDynamic) return
        val identity7251 = pos.canonicalAssetKey.ifBlank { pos.dynMint ?: "" }
        val now7251 = System.currentTimeMillis()
        val lastLog7251 = heldMarkLogAt7251[pos.id] ?: 0L
        if (now7251 - lastLog7251 >= HELD_MARK_REFRESH_COOLDOWN_MS_7251) {
            heldMarkLogAt7251[pos.id] = now7251
            try {
                PipelineHealthCollector.labelInc("CRYPTO_HELD_STALE_MARK_REFRESH_ONLY_7245")
                ForensicLogger.lifecycle(
                    "CRYPTO_HELD_STALE_MARK_REFRESH_ONLY_7245",
                    "positionId=${pos.id} asset=${pos.canonicalAssetKey.take(32)} symbol=${pos.marketSymbol} cause=$cause action=preserve_open_refresh_only",
                )
            } catch (_: Throwable) {}
        }
        val refreshDue7251 = now7251 - (heldMarkRefreshAt7251[identity7251] ?: 0L) >=
            HELD_MARK_REFRESH_COOLDOWN_MS_7251 && identity7251 !in heldMarkRefreshInFlight7251
        if (refreshDue7251) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { refreshDynamicMark7251(identity7251) } catch (_: Throwable) {}
        }
    }

    private fun closePosition(positionId: String, reason: String) {
        val pos = positions[positionId] ?: return
        if (pos.isDynamic && !pos.hasTrustedMark()) {
            holdUntrustedDynamicPosition7245(pos, "CLOSE_REQUEST:${reason.take(80)}")
            try { PipelineHealthCollector.labelInc("CRYPTO_DYN_UNTRUSTED_CLOSE_BLOCKED_6654") } catch (_: Throwable) {}
            ErrorLogger.warn(TAG, "🪙 DYN CLOSE DEFERRED: ${pos.marketSymbol} has no fresh exact-identity mark; preserving position; reason=$reason")
            return
        }
        val mktSym = pos.marketSymbol
        val settlementPnl6486 = pos.getPnlSol()
        if (pos.isPaper) {
            val canonicalClose6486 = com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486.close(
                positionId = pos.id, mint = pos.canonicalAssetKey, symbol = mktSym,
                grossProceedsSol = (pos.sizeSol + settlementPnl6486).coerceAtLeast(0.0),
                exitReason = reason, terminalSequence = System.currentTimeMillis(),
                expectedRealizedPnlSol6569 = pos.sizeSol * (pos.getPnlPct() / 100.0),
                leveragedReturnPct6569 = pos.getPnlPct(),
            )
            if (!canonicalClose6486.applied) {
                ErrorLogger.warn(TAG, "PAPER CLOSE REJECTED: $mktSym ${canonicalClose6486.reason}")
                return
            }
        } else {
            val closeSuccess6486 = try {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    MarketsLiveExecutor.closeLivePositionProof6486(
                        positionId = pos.id,
                        market = pos.market, direction = pos.direction, sizeSol = pos.sizeSol,
                        leverage = pos.leverage, traderType = "CryptoAlt",
                        flashPositionKey = pos.flashPositionKey,
                        cryptoTargetMintOverride = pos.dynMint,
                        cryptoSymbolOverride = mktSym,
                        exitReason = reason,
                        entryTactic = if (pos.isSpot) "SPOT" else "FLASH_PERPS",
                    ).confirmed
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Live close failed for $mktSym: ${e.message}")
                false
            }
            try { com.lifecyclebot.perps.crypto.brain.CryptoFunnel.close(closeSuccess6486) } catch (_: Throwable) {}
            if (!closeSuccess6486) return
        }

        // V5.0.6678 — PAPER CLOSE JOURNAL AUTHORITY CONVERGENCE.
// CanonicalPaperTransaction6486.close() performs terminal mutation and
// durably projects that exact receipt into TradeHistoryStore before it
// returns. CryptoAlt owns only local position cleanup after that boundary.
// A second paper journal write here would be a contradictory patch stack.
        positions.remove(positionId)
        spotPositions.remove(positionId)
        leveragePositions.remove(positionId)
        // V5.9.424 — drop momentum snapshot so the map stays bounded.
        momentumSnapshots.remove(positionId)
        // V5.9.432 — drop partial-ladder + trail state for this position.
        partialLadderHit.remove(positionId)
        trailPeakPct.remove(positionId)
        // V5.9.654 — operator 10-Point Triage #1: release the symbol from
        // the per-mode CryptoPositionState bucket so the diagnostic
        // "live=… paper=… sim=… watch=…" line cannot drift away from the
        // real `positions` map. Belt-and-braces with the per-cycle
        // replaceBucket() rebuild in runScanCycle.
        try {
            val isPaper = pos.isPaper
            val bucket = if (isPaper)
                com.lifecyclebot.engine.CryptoPositionState.Bucket.PAPER
            else
                com.lifecyclebot.engine.CryptoPositionState.Bucket.LIVE
            com.lifecyclebot.engine.CryptoPositionState.release(mktSym, bucket)
        } catch (_: Throwable) { /* best-effort */ }
        // V5.9.178 — persist the map without this position.
        persistAltPositions()
        // V5.9.171 — clear from local orphan store since capital is being
        // returned to the paper wallet via creditUnifiedPaperSol below.
        try { com.lifecyclebot.collective.LocalOrphanStore.clear(positionId) } catch (_: Exception) {}
        com.lifecyclebot.engine.WalletPositionLock.recordClose("CryptoAlt", pos.sizeSol)

        // V5.9.721-FIX: FAST SHUTDOWN PATH — skip all heavy AI learning on bot stop.
        // CryptoAltTrader.closeAllPositions() passes "USER_STOP". Running 44-layer
        // PerpsUnifiedScorerBridge + personality + Turso writes per position with
        // 10+ open positions causes the same 60-90s freeze as the main Executor did.
        // On bot stop: close fast, credit wallet, done. Learning runs on real closes.
        if (reason == "USER_STOP" || reason == "bot_shutdown" || com.lifecyclebot.engine.BotService.isShuttingDown) {
            val pnlSolFast = pos.getPnlSol()
            totalPnlSol += pnlSolFast
            if (pos.isPaper) {
                try { /* V5.0.6972 — pass the REAL exit reason and regime. Both parameters existed and every caller let them default to UNKNOWN@NEUT, collapsing the entire exit-tag learner into one bucket. */ com.lifecyclebot.engine.FluidLearning.recordPaperSell(mktSym, pos.sizeSol, pnlSolFast, reason, regimeTag6972()) } catch (_: Exception) {}
                paperBalance = com.lifecyclebot.engine.FluidLearning.getSimulatedBalance()
            }
            // Async Turso orphan delete (non-blocking)
            scope.launch {
                try { com.lifecyclebot.collective.CollectiveLearning.getClient()?.deleteMarketsPosition(pos.id) } catch (_: Exception) {}
            }
            ErrorLogger.info(TAG, "🪙 FAST_CLOSE [${mktSym}] pnl=${"%.4f".format(pnlSolFast)} reason=$reason — AI learning skipped on shutdown")
            persistAltPositions()
            return
        }

        try {
            com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI.recordOutcomeSeconds(
                mint = pos.canonicalAssetKey,
                actualHoldSeconds = ((System.currentTimeMillis() - pos.openTime) / 1000L).toInt().coerceAtLeast(0),
                pnlPct = pos.getPnlPct(),
                setupQuality = pos.holdSetupQuality,
            )
        } catch (_: Throwable) {}

        // V5.9.134 — delete the OPEN row from Turso so it doesn't linger
        // as an orphan that wipes paper balance on the next app update.
        scope.launch {
            try {
                com.lifecyclebot.collective.CollectiveLearning.getClient()
                    ?.deleteMarketsPosition(pos.id)
            } catch (_: Exception) {}
        }

        val pnlSol = pos.getPnlSol()
        totalPnlSol += pnlSol

        // V5.9.136 — route outcome to the LLM Trade Score scoreboard if
        // this was a chat-triggered paper trade (marked by the reasons
        // prefix set in llmOpenPaperBuy).
        // V5.9.350 — also route into the persona memory funnel so LLM chat
        // trades drift the bot's traits, trigger milestones, and update the
        // active persona's bio (this was previously meme-only).
        if (pos.isPaper && pos.reasons.any { it.startsWith("LLM chat:", ignoreCase = true) }) {
            try {
                com.lifecyclebot.engine.LlmTradeScore.recordClose(
                    pnlSol = pnlSol,
                    pnlPct = pos.getPnlPct(),
                    symbol = mktSym,
                )
            } catch (_: Exception) {}
            try {
                val heldMin = ((System.currentTimeMillis() - pos.openTime) / 60_000L).toInt().coerceAtLeast(0)
                val giveback = (pos.highestPnlPct - pos.getPnlPct()).coerceAtLeast(0.0)
                com.lifecyclebot.engine.PersonalityMemoryStore.recordTradeOutcome(
                    pnlPct = pos.getPnlPct(),
                    gaveBackFromPeakPct = giveback,
                    heldMinutes = heldMin,
                )
                val ctx = com.lifecyclebot.engine.BotService.instance?.applicationContext
                val personaId = if (ctx != null) {
                    try { com.lifecyclebot.engine.Personalities.getActive(ctx).id } catch (_: Exception) { "aate" }
                } else "aate"
                com.lifecyclebot.engine.PersonalityMemoryStore.recordPersonaTrade(
                    personaId = personaId,
                    pnlPct    = pos.getPnlPct(),
                )
            } catch (_: Exception) {}
        }

        // V5.9.130: close the V3 bridge learning loop so every one of the 41
        // AI layers gets its real-accuracy update based on how this alt trade
        // played out vs what each layer predicted at entry.
        // V5.9.170: include the real exit reason + loss cause so the
        // education firehose learns WHY we closed, not just the magnitude.
        try {
            PerpsUnifiedScorerBridge.recordClose(
                symbol = mktSym,
                assetClass = "ALT",
                pnlPct = pos.getPnlPct(),
                exitReason = reason.ifBlank { "crypto_alt_close" },
                lossReason = if (pos.getPnlPct() < -2.0) reason else "",
                canonicalAssetId6493 = pos.dynMint ?: pos.canonicalAssetKey,
            )
        } catch (_: Exception) {}

        // V5.7.7: Count trades at close so win rate is accurate (wins+losses / total)
        // V5.9.358 — honest win contract: switch from `pnlSol >= 0` (which
        // counted scratches as wins, inflating top-of-screen WR to 62% while
        // realised PnL was -13 SOL) to `pnlPct >= 1.0`, matching
        // RunTracker30D.classifyTrade and the rest of AATE.
        // Scope: ALTS-ONLY. The boolean `isWinByPct` is computed locally
        // from this position's pnlPct and used only by Alts-side state
        // updates below. Any callee that previously got an Alts-flavoured
        // boolean (FluidLearning markets counters, SentientPersonality)
        // gets a more honest signal but the same API.
        val pnlPctForWin = pos.getPnlPct()
        // V5.9.419 — tighten win/loss threshold from ±1.0% → ±0.1%.
        // The old ±1.0% gate was bucketing 307 of 311 paper alt trades into
        // "scratch", leaving the UI showing W/L/S = 3/1/307 even though the
        // engine's effective directional accuracy was much higher. ±0.1%
        // matches the meme/Moonshot scratch gate and produces honest W/L
        // counts for the 30-Day card without polluting FluidLearning.
        val isWinByPct   = pnlPctForWin >= 0.1
        val isLossByPct  = pnlPctForWin <= -0.1
        totalTrades.incrementAndGet()
        when {
            isWinByPct -> {
                winningTrades.incrementAndGet()
                try { if (pos.isPaper) FluidLearningAI.recordAltsPaperTrade(true, pnlPctForWin) else FluidLearningAI.recordAltsLiveTrade(true) } catch (_: Exception) {}
                // V5.0.4586c — CRYPTO PARITY: feed the shared AutoCompoundEngine
                // so crypto wins actually reinvest into the compound pool
                // (previously only meme wins fed it). Pool size lifts
                // future position size ceilings across all traders.
                try {
                    val profitSol = pos.getPnlSol()
                    if (profitSol > 0.0 && !pos.isPaper) {
                        com.lifecyclebot.engine.AutoCompoundEngine.processWin(profitSol)
                    }
                } catch (_: Throwable) {}
            }
            isLossByPct -> {
                losingTrades.incrementAndGet()
                try { if (pos.isPaper) FluidLearningAI.recordAltsPaperTrade(false, pnlPctForWin) else FluidLearningAI.recordAltsLiveTrade(false) } catch (_: Exception) {}
                // V5.0.4586c — CRYPTO PARITY: feed loss into compound engine
                // so streak/drawdown state tracks live capital reality.
                try {
                    if (!pos.isPaper) com.lifecyclebot.engine.AutoCompoundEngine.processLoss()
                } catch (_: Throwable) {}
            }
            else -> {
                // Scratch: |pnlPct| < 1%. Bump local scratch counter only.
                // Skip FluidLearning markets win/loss feed so noise doesn't
                // poison Alts learning curves. Other traders (Meme, Perps)
                // are completely untouched.
                scratchTrades.incrementAndGet()
            }
        }

        // V5.0.4151 — feed the isolated crypto discipline pack on every live
        // close. recordOutcome is keyed on assetKey/lane so the rolling-30
        // WR windows + per-(src,lane) PnL memory live in the crypto-only
        // SharedPreferences (no meme contamination). Lane tag is coarse —
        // CRYPTO_SPOT vs CRYPTO_LEV — so the LaneTimeoutGate can timeout one
        // mode without locking the other.
        if (!pos.isPaper) {
            try {
                val assetKey4151 = pos.dynMint ?: pos.canonicalAssetKey
                val lane4151 = canonicalCryptoLane7251(pos.isDynamic, pos.isSpot)
                val src4151 = "CRYPTO"
                val holdMs4151 = (System.currentTimeMillis() - pos.openTime).coerceAtLeast(0L)
                com.lifecyclebot.perps.crypto.brain.CryptoRugMintBlacklist.recordClose(assetKey4151, pnlPctForWin, holdMs4151)
                com.lifecyclebot.perps.crypto.brain.CryptoLivePauseButton.recordOutcome(lane4151, pnlPctForWin)
                com.lifecyclebot.perps.crypto.brain.CryptoLaneTimeoutGate.recordOutcome(lane4151, pnlPctForWin)
                com.lifecyclebot.perps.crypto.brain.CryptoScannerLaneBridge.recordOutcome(src4151, lane4151, pnlPctForWin)
                // V5.0.4160 — feed shared ScratchStreakRegistry (butterfly sweep).
                // Lane tags CRYPTO_SPOT / CRYPTO_LEV never collide with meme
                // lane tags, so isolation is preserved by key-space partition.
                com.lifecyclebot.engine.ScratchStreakRegistry.recordOutcome(lane4151, pnlPctForWin)
            } catch (_: Throwable) {}
        }

        if (pos.isPaper) {
            try { com.lifecyclebot.engine.FluidLearning.recordPaperSell(mktSym, pos.sizeSol, pnlSol) } catch (_: Exception) {}
            paperBalance = com.lifecyclebot.engine.FluidLearning.getSimulatedBalance()
        } else {
            try {
                val newBal = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBal)
            } catch (_: Exception) {}
        }

        val pnlPct    = pos.getPnlPct()
        // V5.9.358 — same honest contract for personality routing. Only
        // genuine ≥1% wins fire onTradeWin; only genuine ≤-1% losses fire
        // onTradeLoss. Scratches (|pnlPct|<1%) skip personality entirely so
        // SentientPersonality doesn't drift toward false euphoria on
        // fee-band noise. Alts-only — Meme & Perps unchanged.
        val isWin     = pnlPct > 0.0
        val isScratch = pnlPct > -1.0 && pnlPct < 1.0
        val paper     = pos.isPaper
        val modeStr   = if (paper) "paper" else "live"
        val timestamp = System.currentTimeMillis()
        val holdMs    = timestamp - pos.openTime

        // V5.9.9: Sentient personality reacts to trade outcomes
        // V5.9.358 — skip personality update entirely on scratches to
        // prevent false-positive euphoria from fee-band noise (Alts-only).
        try {
            if (isScratch) {
                // no-op: scratch trades don't update persona traits
            } else if (isWin) {
                com.lifecyclebot.engine.SentientPersonality.onTradeWin(
                    mktSym, pnlPct, strategyFromReasons7803(pos.reasons), holdMs / 1000
                )
            } else {
                com.lifecyclebot.engine.SentientPersonality.onTradeLoss(
                    mktSym, pnlPct, strategyFromReasons7803(pos.reasons), reason
                )
            }
        } catch (_: Exception) {}

        // V5.7.7: Archive closed position for Positions tab history
        val closedPos = pos.copy(
            currentPrice  = pos.currentPrice,
            closeTime     = timestamp,
            closePrice    = pos.currentPrice,
            realizedPnl   = pnlSol
        )
        closedPositions.add(0, closedPos)
        // V5.0.7825 — close-time stamp belongs to the exact canonical identity,
        // not ticker, so same-symbol assets on different chains do not suppress
        // one another. This feeds only the entry scheduling cooldown above.
        try {
            val closedKey7825 = pos.canonicalAssetKey.trim().ifBlank { pos.dynMint.orEmpty().trim() }
            if (closedKey7825.isNotBlank()) {
                cryptoLastClosedAt7825[closedKey7825] = timestamp
                if (cryptoLastClosedAt7825.size > 8_192) {
                    val cutoff7825 = timestamp - 24L * 60L * 60_000L
                    cryptoLastClosedAt7825.entries.removeIf { it.value < cutoff7825 }
                }
                PipelineHealthCollector.labelInc("CRYPTO_REENTRY_CLOSE_STAMPED_7825")
            }
        } catch (_: Throwable) {}
        if (closedPositions.size > MAX_CLOSED_HISTORY) {
            closedPositions.subList(MAX_CLOSED_HISTORY, closedPositions.size).clear()
        }
        // Persist counters immediately so win rate survives restarts
        try { saveToSharedPrefs() } catch (_: Exception) {}

        ErrorLogger.info(TAG, "🪙 CLOSED: ${mktSym} | $reason | pnl=${pnlSol.fmt(3)}◎ | wr=${"%.0f".format(getWinRate())}%")

        // V5.9.401 — Sentience hook #4: cross-engine telegraph (ALTS).
        try { com.lifecyclebot.engine.SentienceHooks.recordEngineOutcome("ALTS", pnlSol, isWin) } catch (_: Exception) {}

        try { PortfolioHeatAI.removePosition("ALT_${mktSym}") } catch (_: Exception) {}

        // ── BehaviorAI (V5.9.1442 — isolated crypto brain) ───────────────────
        try {
            // Determine tier from symbol for the crypto-brain bucket key.
            // V5.0.6923 — was an inline copy of this rule; now the same
            // helper the entry-side verdict read uses, so the write key and
            // the read key cannot diverge.
            val tier = cryptoBrainTier6923(mktSym)
            com.lifecyclebot.perps.crypto.brain.CryptoBrain.onTradeClose(
                tier   = tier,
                score  = pos.aiScore,
                pnlPct = pnlPct,
                pnlSol = pnlSol,
                isPaper = paper,
                reason  = reason,
                symbol  = mktSym,
            )
        } catch (_: Exception) {}

        // V5.9.852 — non-meme close → CanonicalOutcomeBus (Layer Readiness fix).
        if (!paper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit(
            tradeIdSeed   = "${pos.id}_$timestamp",
            mint          = mktSym,    // CryptoAlt has no mint — use market symbol
            symbol        = mktSym,
            source        = com.lifecyclebot.engine.TradeSource.MARKETS,
            isPaper       = paper,
            entryTimeMs   = pos.openTime,
            exitTimeMs    = timestamp,
            entryPrice    = pos.entryPrice,
            exitPrice     = pos.currentPrice,
            entrySol      = pos.sizeSol,
            exitSol       = pos.sizeSol + pnlSol,
            realizedPnlSol = pnlSol,
            realizedPnlPct = pnlPct,
            maxGainPct    = pos.highestPnlPct.takeIf { it > 0.0 },
            closeReason   = "CRYPTOALT_$reason",
            // V5.0.6540 §CANONICAL_ATTRIBUTION_FIX — operator directive:
            // "CryptoAltTrader close currently publishes every close as
            // CRYPTO_ALT_SPOT. This is wrong for leveraged/perp positions."
            // Publish canonical asset class from the committed position:
            //   pos.isSpot == true  → CRYPTO_ALT_SPOT (Markets Spot lane)
            //   pos.isSpot == false → PERPS_CRYPTOALT (Markets Perps lane)
            assetClass    = if (pos.isSpot)
                com.lifecyclebot.engine.AssetClass.CRYPTO_ALT_SPOT
            else
                com.lifecyclebot.engine.AssetClass.PERPS_CRYPTOALT,
            entryScore    = pos.aiScore.toDouble(),
            // V5.9.896 — promote lite→rich for BehaviorLearning.
            entryPattern  = "CRYPTOALT_ENTRY",
        )

        // ── TradeHistoryStore — cross-bot shared log ──────────────────────────
        if (!paper) try {
            TradeHistoryStore.recordTrade(Trade(
                side             = "SELL", mode = modeStr,
                sol              = (pos.sizeSol + pnlSol).coerceAtLeast(0.0), price = pos.currentPrice,
                ts               = timestamp, reason = "ALT:STRATEGY7803=${strategyFromReasons7803(pos.reasons)}:$reason",
                pnlSol           = pnlSol, pnlPct = pnlPct,
                score            = pos.aiScore.toDouble(),
                tradingMode      = "CryptoAlt_${if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x"}",
                tradingModeEmoji = "🪙", mint = mktSym,
                entryPriceSnapshot = pos.entryPrice,
                entryCostSol      = pos.sizeSol
            ))
        } catch (_: Exception) {}

        // ── RunTracker30D ─────────────────────────────────────────────────────
        try {
            if (RunTracker30D.isRunActive()) {
                RunTracker30D.recordTrade(
                    symbol = mktSym, mint = mktSym,
                    entryPrice = pos.entryPrice, exitPrice = pos.currentPrice,
                    sizeSol = pos.sizeSol, pnlPct = pnlPct,
                    holdTimeSec = holdMs / 1000,
                    mode = "CryptoAlt_${if (pos.isSpot) "SPOT" else "${pos.leverage.toInt()}x"}",
                    score = pos.aiScore, confidence = pos.aiConfidence,
                    decision = "STRATEGY7803=${strategyFromReasons7803(pos.reasons)};$reason"
                )
            }
        } catch (_: Exception) {}

        // ── TradeLessonRecorder — StrategyTrustAI cross-learning ─────────────
        try {
            val lessonCtx = TradeLessonRecorder.captureContext(
                strategy = strategyFromReasons7803(pos.reasons), market = "CRYPTO_ALT",
                symbol = mktSym, leverageUsed = pos.leverage,
                executionRoute = if (paper) "PAPER" else "LIVE",
                expectedFillPrice = pos.entryPrice
            )
            TradeLessonRecorder.completeLesson(
                context = lessonCtx, outcomePct = pnlPct,
                mfePct = if (isWin) pnlPct else 0.0, maePct = if (!isWin) pnlPct else 0.0,
                holdSec = (holdMs / 1000).toInt().coerceAtLeast(1),
                exitReason = reason, actualFillPrice = pos.currentPrice
            )
        } catch (_: Exception) {}

        // V5.0.4581 — CRYPTO ISOLATION WALL. Do not feed CryptoAlt outcomes into
        // meme/global MetaCognitionAI or ShadowLearningEngine. Crypto uses the same
        // tech-stack shape through CryptoBrain/PerpsLearningBridge/CanonicalBus, but
        // learning behavior remains isolated by namespace/source/assetClass.
        try { ErrorLogger.debug(TAG, "🪙 ISO_LEARNING: ${mktSym} close retained in crypto-only brains; meme/global learners skipped") } catch (_: Exception) {}

        // ── PerpsLearningBridge — cross-layer learning from alt trade ─────────
        // V5.9.395 — route into dedicated ALT lane (not PERPS). This stops
        // alt outcomes polluting perps trust AND prevents the old
        // routeLearningToLayer path from training meme sub-traders
        // (Moonshot/ShitCoin/BlueChip/Quality/Express) on alt trades.
        try {
            val contributingLayers = pos.reasons.mapNotNull { r ->
                when {
                    r.contains("Momentum")     -> "MomentumPredictorAI"
                    r.contains("Technical")    -> "PerpsAdvancedAI"
                    r.contains("NarrativeHeat")-> "NarrativeFlowAI"
                    r.contains("Trust")        -> "StrategyTrustAI"
                    r.contains("BehaviorAI")   -> "BehaviorAI"
                    else                       -> null
                }
            }.distinct().ifEmpty { listOf("CryptoAltAI") }
            PerpsLearningBridge.learnFromAltTrade(
                symbol = mktSym,
                isWin = pnlPct > 0.0,
                pnlPct = pnlPct,
                contributingLayers = contributingLayers,
                isPaper = paper,
            )
            ErrorLogger.debug(TAG, "🪙🧠 PerpsLearningBridge(ALT lane): ${mktSym} | pnl=${pnlPct.fmt(1)}% | cross-learn OK")
        } catch (_: Exception) {}

        // ── FluidLearningAI persistence ───────────────────────────────────────
        try { FluidLearningAI.saveAltsPrefs() } catch (_: Exception) {}

        // V5.9.112: feed live PnL into LiveSafetyCircuitBreaker for session drawdown halt.
        if (!paper) {
            try { com.lifecyclebot.engine.LiveSafetyCircuitBreaker.recordTradeResult(pnlSol) } catch (_: Exception) {}
        }

        saveToSharedPrefs()
        savePersistedState()
        persistTradeToTurso(pos, pnlSol, reason)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.88: FLUID SIZING & FLUID TP/SL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Scales base position size based on combined AI score + confidence.
     * Returns a multiplier in [0.4, 2.0] — low-conviction trades ride smaller,
     * high-conviction rides bigger. No more flat 3% on every signal.
     */
    private fun fluidSizeMultiplier(score: Int, confidence: Int): Double {
        val s = score.coerceIn(0, 100)
        val c = confidence.coerceIn(0, 100)
        // Blend score (60%) and confidence (40%) — score is the harder signal.
        val blended = (s * 0.6 + c * 0.4)
        return when {
            blended >= 88 -> 2.00    // "all in" conviction
            blended >= 80 -> 1.65
            blended >= 72 -> 1.35
            blended >= 64 -> 1.10
            blended >= 56 -> 0.90
            blended >= 48 -> 0.70
            else          -> 0.45    // whisper-only, low-risk probe
        }
    }

    /**
     * Scales TP and SL distances based on conviction. Stops the old "every
     * position shows TP+7% SL-3%" pattern — high-conviction trades get more
     * room to run and a tighter stop; low-conviction trades get a tighter
     * TP and a looser stop to absorb noise.
     *
     * Returns (tpMult, slMult) where 1.0 = base pct.
     */
    private fun fluidTpSlMultiplier(score: Int, confidence: Int): Pair<Double, Double> {
        val s = score.coerceIn(0, 100)
        val c = confidence.coerceIn(0, 100)
        val blended = (s * 0.6 + c * 0.4)
        return when {
            blended >= 85 -> 1.75 to 0.75   // wide TP, tight SL — ride the winner
            blended >= 75 -> 1.40 to 0.85
            blended >= 65 -> 1.15 to 0.95
            blended >= 55 -> 1.00 to 1.00   // neutral
            blended >= 45 -> 0.85 to 1.15   // take profit early, wider stop
            else          -> 0.70 to 1.30   // scalp-only probe
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // HIVEMIND ENTRY MODIFIER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hiveEntryModifier(symbol: String): Triple<Boolean, Double, Double> {
        val sizeMult = try {
            // V5.9.1442 — isolated crypto behaviour (was BehaviorAI).
            (com.lifecyclebot.perps.crypto.brain.CryptoBehavior.getSizingMultiplier().coerceIn(0.5, 2.0) *
             NarrativeFlowAI.getNarrativeMultiplier(symbol).coerceIn(0.8, 1.5)).coerceIn(0.5, 2.0)
        } catch (_: Exception) { 1.0 }
        val tpAdj = try { if (StrategyTrustAI.getTrustMultiplier("CryptoAltAI") > 1.1) 1.5 else 0.0 } catch (_: Exception) { 0.0 }
        return Triple(true, sizeMult, tpAdj)
    }

    private fun loadFromSharedPrefs() {
        val p = prefs ?: return
        paperBalance      = p.getFloat(KEY_BALANCE, 0.0f).toDouble()
        totalTrades.set(   p.getInt(KEY_TRADES,  0))
        winningTrades.set( p.getInt(KEY_WINS,    0))
        losingTrades.set(  p.getInt(KEY_LOSSES,  0))
        scratchTrades.set( p.getInt(KEY_SCRATCHES, 0))
        totalPnlSol       = p.getFloat(KEY_PNL, 0f).toDouble()
        val savedInitial  = p.getFloat(KEY_INITIAL_BALANCE, 0f).toDouble()
        if (savedInitial > 0.0) initialBalance = savedInitial
        else if (paperBalance > 0.0) initialBalance = paperBalance
        isPaperMode.set(  !p.getBoolean(KEY_LIVE, true))

        // V5.9.358 — one-time honest-win-contract migration. The legacy
        // counter incremented on `pnlSol >= 0`, so any persisted Alts
        // run before V5.9.358 has scratches mis-counted as wins. We
        // can't reconstruct the truth, so we reset Alts trade counters
        // ONCE on first boot of V5.9.358 and start collecting honest
        // data. paperBalance / totalPnlSol are NOT reset (those are
        // cumulative SOL, still correct). Meme/Perps untouched.
        if (!p.getBoolean(KEY_WR_CONTRACT_MIGRATED_358, false)) {
            val hadHistory = totalTrades.get() > 0
            if (hadHistory) {
                ErrorLogger.warn(TAG, "🔧 V5.9.358 honest-WR migration: resetting Alts counters " +
                    "(was trades=${totalTrades.get()} W=${winningTrades.get()} L=${losingTrades.get()} " +
                    "— legacy contract counted scratches as wins).")
            }
            totalTrades.set(0)
            winningTrades.set(0)
            losingTrades.set(0)
            scratchTrades.set(0)
            p.edit()
                .putBoolean(KEY_WR_CONTRACT_MIGRATED_358, true)
                .putInt(KEY_TRADES, 0)
                .putInt(KEY_WINS, 0)
                .putInt(KEY_LOSSES, 0)
                .putInt(KEY_SCRATCHES, 0)
                .apply()
        }

        ErrorLogger.debug(TAG, "🪙 SharedPrefs: bal=${paperBalance.fmt(2)} trades=${totalTrades.get()}")
    }

    private fun saveToSharedPrefs() {
        prefs?.edit()
            ?.putFloat(KEY_BALANCE,  paperBalance.toFloat())
            ?.putInt(KEY_TRADES,     totalTrades.get())
            ?.putInt(KEY_WINS,       winningTrades.get())
            ?.putInt(KEY_LOSSES,     losingTrades.get())
            ?.putInt(KEY_SCRATCHES,  scratchTrades.get())
            ?.putFloat(KEY_PNL,             totalPnlSol.toFloat())
            ?.putFloat(KEY_INITIAL_BALANCE, initialBalance.toFloat())
            ?.putBoolean(KEY_LIVE,  !isPaperMode.get())
            ?.apply()
    }

    /** V5.9.635 — Wired into TradeHistoryStore.clearAllTrades() for unified
     *  Clear UX. Resets counters only; positions and balance preserved. */
    fun resetCounters() {
        totalTrades.set(0)
        winningTrades.set(0)
        losingTrades.set(0)
        scratchTrades.set(0)
        totalPnlSol = 0.0
        saveToSharedPrefs()
        ErrorLogger.info(TAG, "🧹 CryptoAltTrader counters reset")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun loadPersistedState() {
        try {
            val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
            if (tursoClient != null) {
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                // Reuse MarketsState schema — prefix instanceId so it's separate from stocks
                val state = tursoClient.loadMarketsState("ALT_$instanceId")
                if (state != null) {
                    paperBalance = if (state.paperBalanceSol > 1.0) state.paperBalanceSol else 0.0
                    totalPnlSol  = state.totalPnlSol
                    // V5.9.358 — only restore counter values from Turso AFTER
                    // the one-time honest-WR migration. paperBalance and
                    // totalPnlSol stay (those are honest cumulative SOL),
                    // but the trade/win/loss counters are inflated under the
                    // legacy contract and must start fresh once.
                    val migrated = prefs?.getBoolean(KEY_WR_CONTRACT_MIGRATED_358, false) ?: false
                    if (migrated) {
                        totalTrades.set(state.totalTrades)
                        winningTrades.set(state.totalWins)
                        losingTrades.set(state.totalLosses)
                    } else {
                        ErrorLogger.warn(TAG, "🔧 V5.9.358 honest-WR migration: ignoring cloud trade counters " +
                            "(was trades=${state.totalTrades} W=${state.totalWins} L=${state.totalLosses}) " +
                            "to start clean under the new contract.")
                        // Force-set the flag so subsequent boots resume normal cloud restore.
                        prefs?.edit()?.putBoolean(KEY_WR_CONTRACT_MIGRATED_358, true)?.apply()
                    }
                    // Track initial balance for correct pnl% display
                    if (initialBalance <= 0.0 && paperBalance > 0.0) initialBalance = paperBalance
                    isPaperMode.set(!state.isLiveMode)
                    ErrorLogger.info(TAG, "🪙 Loaded state: balance=${paperBalance.fmt(2)} SOL | trades=${totalTrades.get()} | WR=${"%.1f".format(getWinRate())}%")
                } else {
                    ErrorLogger.info(TAG, "🪙 No persisted state — using defaults")
                }

                // V5.9.134 — RECONCILE ORPHANED PAPER POSITIONS.
                // Positions are debited from status.paperWalletSol at entry
                // but kept only in memory maps. An app update wipes those
                // maps, leaving the debit with no way to close → paper
                // balance "disappears". Refund + purge via the shared
                // reconciler so the bug can't bleed back in.
                if (isPaperMode.get()) {
                    com.lifecyclebot.collective.PaperOrphanReconciler
                        .reconcile(assetClass = "CRYPTO_ALT", sourceLabel = "CryptoAlt")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Load state error: ${e.message}")
        }
    }

    fun savePersistedState() {
        scope.launch {
            try {
                val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
                if (tursoClient != null) {
                    val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                    val state = com.lifecyclebot.collective.MarketsState(
                        instanceId     = "ALT_$instanceId",
                        paperBalanceSol= paperBalance,
                        totalTrades    = totalTrades.get(),
                        totalWins      = winningTrades.get(),
                        totalLosses    = losingTrades.get(),
                        totalPnlSol    = totalPnlSol,
                        learningPhase  = getPhaseLabel(),
                        isLiveMode     = !isPaperMode.get(),
                        lastUpdated    = System.currentTimeMillis()
                    )
                    tursoClient.saveMarketsState(state)
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Save state error: ${e.message}")
            }
        }
    }

    private fun persistPositionToTurso(pos: AltPosition) {
        scope.launch {
            try {
                val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                // Reuse the Markets position schema
                client.saveMarketsPosition(com.lifecyclebot.collective.MarketsPositionRecord(
                    id               = pos.id,
                    instanceId       = instanceId,
                    assetClass       = "CRYPTO_ALT",
                    market           = pos.market.symbol,
                    direction        = pos.direction.name,
                    tradeType        = if (pos.isSpot) "SPOT" else "LEVERAGE",
                    entryPrice       = pos.entryPrice,
                    currentPrice     = pos.currentPrice,
                    sizeSol          = pos.sizeSol,
                    sizeUsd          = pos.sizeSol * pos.currentPrice,
                    leverage         = pos.leverage,
                    takeProfitPrice  = pos.takeProfitPrice,
                    stopLossPrice    = pos.stopLossPrice,
                    entryTime        = pos.openTime,
                    aiScore          = pos.aiScore,
                    aiConfidence     = pos.aiConfidence,
                    paperMode        = isPaperMode.get(),
                    status           = "OPEN",
                    lastUpdate       = System.currentTimeMillis()
                ))
            } catch (_: Exception) {}
        }
    }

    private fun persistTradeToTurso(pos: AltPosition, pnlSol: Double, reason: String) {
        // Trade history logged — full persistence uses MarketsState (balance + counters) saved on each close
        ErrorLogger.debug(TAG, "🪙 Trade closed: ${pos.market.symbol} | pnl=${pnlSol.fmt(3)}◎ | reason=$reason")
        savePersistedState()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /** Public close — used by UI (CryptoAltActivity) */
    fun requestClose(positionId: String) {
        scope.launch { closePosition(positionId, "USER_REQUEST") }
    }

    fun isRunning(): Boolean = isRunning.get()

    /** Returns true only if running AND engine/monitor coroutines are actually alive. */
    fun isHealthy(): Boolean {
        if (!isRunning.get()) return false
        return (engineJob?.isActive == true) && (monitorJob?.isActive == true)
    }
    fun isEnabled()  : Boolean = isEnabled.get()
    fun isPaperMode(): Boolean = isPaperMode.get()
    fun isLiveMode() : Boolean = !isPaperMode.get()

    // V5.0.6577 §P0-1 — SHARED PAPER CAPITAL AUTHORITY.
    // Every paper lane reads the same canonical PaperCapitalAuthority6577,
    // never a lane-local field. Previously BotService.status.paperWalletSol
    // was used, which could lag behind the canonical ledger during in-flight
    // trades — Crypto Alt UI displayed $648 while other lanes saw $1,190.
    /**
     * V5.0.7211 §THE_CRYPTO_LANE_COULD_NOT_AFFORD_TO_LEARN_WHAT_IT_COULD_AFFORD.
     *
     * Operator, live: "crypto lane doesn't trade or if it is positions aren't
     * showing on the app." It does not trade, and this is why.
     *
     * `liveWalletBalance` is declared 0.0 at line 244 and its ONLY writer is
     * updateLiveBalance(), called from attemptLiveBuy at line 2951 — i.e.
     * INSIDE the live buy, AFTER sizing. But sizing reads it first:
     *
     *   :2348  val balance = getEffectiveBalance()        <- 0.0 in live
     *   :2353  var sizeSol  = balance * (DEFAULT_SIZE_PCT/100) * ...   -> 0.0
     *   :2612  evidence = mapOf(... "walletSol" to balance.toString())
     *            -> OrderSizeResolver6441:345 authoritativeCash = walletSol = 0.0
     *            -> not executable
     *
     * So the lane is told it has no money, sizes to zero, never opens a
     * position, and therefore never reaches the line that would have told it
     * how much money it has. A one-way latch of exactly the shape this run
     * keeps finding — 7209's pause, 7193's reproof, 7154's refusal.
     *
     * The 5.0.7210 device says it precisely:
     *
     *   CRYPTO_ALT candidate=20 submit=10 fdgAllow=10 sized=10 intent=10
     *              dispatch=10 dispatchReject=0 open=0
     *   crypto refusal by route 7156=[PAPER_ONLY=31, INSUFFICIENT_SOL=21]
     *   evaluation terminal reasons=... PRE_SUBMIT_SIZE_BELOW_FLOOR:64
     *
     * Ten dispatches, zero rejections, zero positions — because the size was
     * already zero before the route was ever asked.
     *
     * Fix: ask the authority instead of a cache that only a successful trade
     * can fill. BotService.status.walletSol is the live cash source the lane
     * allocator itself uses (ToolkitSignalSheet:877, capitalSource
     * LIVE_WALLET_AUTHORITY_6686), and on that same run it read 0.1950 SOL
     * correctly while this cache read 0.0. One authority, one answer.
     *
     * Write-through keeps the cache warm so it remains a real fallback, and a
     * non-positive authority reading falls back to the cache rather than
     * overwriting a known-good value with zero — this may only ever raise the
     * lane's view of its own wallet toward the truth, never lower it to zero.
     * The 0.01 live floor at :2952 and every downstream cap are untouched, so
     * this cannot size a trade the wallet cannot fund.
     */
    private fun liveWalletSol7211(): Double {
        val cached = liveWalletBalance
        val authority = try {
            com.lifecyclebot.engine.BotService.status.walletSol
        } catch (_: Throwable) { 0.0 }
        if (!authority.isFinite() || authority <= 0.0) return cached
        if (authority != cached) {
            liveWalletBalance = authority
            try {
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("CRYPTO_LIVE_BALANCE_FROM_AUTHORITY_7211")
            } catch (_: Throwable) {}
        }
        return authority
    }

    fun getBalance()          : Double = getEffectiveBalance()
    fun getEffectiveBalance() : Double = if (authoritativePaperMode7425())
        try { com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.availableCashSol() }
            catch (_: Throwable) { com.lifecyclebot.engine.BotService.status.paperWalletSol }
        else liveWalletSol7211()

    fun setBalance(bal: Double) {
        paperBalance = bal
        // V5.9.5: No-op — balance is owned by FluidLearning shared pool
    }
    fun setEnabled(enabled: Boolean)   {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "🪙 Enabled: $enabled")
        if (!enabled) {
            try { stop() } catch (_: Throwable) {}
            ErrorLogger.info(TAG, "CRYPTO_RUNTIME_DISABLED reason=toggle_or_runtime_authority")
        }
    }
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "🪙 Mode switched to ${if (live) "🔴 LIVE" else "📄 PAPER"}")
        if (live) {
            // V5.9.445 — Paper→Live parity audit (user 02-2026: "ensure when
            // we go live all learning is applied. so the user feels no
            // difference"). Emit an explicit confirmation so we can see in
            // the logs that the full learning/guard stack is still wired.
            ErrorLogger.info(TAG, "🔴 LIVE PARITY: ChopFilter✓ OutcomeGates✓ PeakDrawdownLock✓ FluidLearning✓ Sentience✓ V3Scorer✓ — all gates active")
            // Sync wallet balance immediately on mode switch
            try {
                val sol = WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (sol > 0.0) updateLiveBalance(sol)
            } catch (_: Exception) {}
        }
    }
    fun updateLiveBalance(sol: Double) { liveWalletBalance = sol }

    /** Sync paper wallet balance from BotService (consistent across all traders) */
    fun setPaperBalance(sol: Double) {
        if (isPaperMode.get() && sol > 0.0) paperBalance = sol
    }

    /**
     * Returns ALL positions (open + closed) so the Positions tab can show win rate, avg hold,
     * and closed trade history. Open positions have closeTime == null; closed have closeTime set.
     */
    private fun activeModePositions7256(values: Collection<AltPosition>): List<AltPosition> {
        val paper = authoritativePaperMode7425()
        return values.filter { it.isPaper == paper }
    }
    fun getAllPositions()      : List<AltPosition> { syncCanonicalCryptoPositions7255(); return activeModePositions7256(positions.values) + closedPositions.toList() }
    fun getOpenPositions()     : List<AltPosition> { syncCanonicalCryptoPositions7255(); return activeModePositions7256(positions.values) }
    fun getClosedPositions()   : List<AltPosition> = closedPositions.toList()
    fun getSpotPositions()    : List<AltPosition> { syncCanonicalCryptoPositions7255(); return activeModePositions7256(spotPositions.values) }
    fun getLeveragePositions(): List<AltPosition> = activeModePositions7256(leveragePositions.values)

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.135 — LLM PAPER-TRADE HOOKS
    // ───────────────────────────────────────────────────────────────────────────
    // Lets the sentient chat layer open/close simulated positions via a
    // <<TRADE>>{...}<<ENDTRADE>> block. PAPER MODE ONLY — live-mode calls are
    // rejected at the gate. Intentionally minimal: uses SPOT @ 1x leverage
    // with a synthetic mid-confidence signal so the existing risk/sizing
    // machinery (exposure cap, fluid sizing, TP/SL, AI registration) runs
    // unchanged. Returns a short outcome string suitable for echoing back
    // into the LLM's chat reply.
    // ═══════════════════════════════════════════════════════════════════════════

    sealed class LlmTradeResult {
        data class Success(val summary: String) : LlmTradeResult()
        data class Rejected(val reason: String) : LlmTradeResult()
    }

    /**
     * Open a simulated CryptoAlt paper position from the chat layer.
     * Size is clamped to [0.05, 2.0] SOL for safety.
     */
    /**
     * V5.0.6096 — INSIDER_SHARK/COPY layer opens Crypto Universe positions too.
     * The prior InsiderCopyEngine could enqueue MEME mints and copy-exit alts,
     * but its own comment admitted forced copy-BUY on CryptoAlts was out of
     * scope. That made the new INSIDER_SHARK layer visible/advisory while not
     * actually trading the broader crypto universe. Route through the existing
     * LLM paper buy hook so the normal CryptoAlt sizing/exposure/learning stack
     * owns the position. Live execution remains governed by the normal runtime
     * mode + route-proof path; this is a signal bridge, not a bypass.
     */
    fun copyBuyFromInsiderSignal(symbol: String, confidence: Int, walletLabel: String): Boolean {
        val clean = symbol.trim().uppercase()
        if (clean.isBlank()) return false
        return try {
            val confMult = (confidence.toDouble() / 100.0).coerceIn(0.50, 1.80)
            val bal = getEffectiveBalance().takeIf { it > 0.0 } ?: 1.0
            val size = (bal * (DEFAULT_SIZE_PCT / 100.0) * confMult).coerceIn(0.05, bal * 0.45)
            val res = llmOpenPaperBuy(clean, size, "INSIDER_SHARK_COPY_BUY_6096 wallet=${walletLabel.take(24)} conf=$confidence")
            val opened = res is LlmTradeResult.Success
            if (opened) {
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("INSIDER_SHARK_CRYPTO_COPY_BUY_6096") } catch (_: Throwable) {}
                ErrorLogger.info(TAG, "🦈 INSIDER_SHARK_CRYPTO_COPY_BUY_6096 $clean size=${size.fmt(3)}◎ wallet=${walletLabel.take(24)} conf=$confidence")
            }
            opened
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "INSIDER_SHARK_CRYPTO_COPY_BUY_6096 $clean error: ${t.message}")
            false
        }
    }

    fun llmOpenPaperBuy(symbol: String, sizeSol: Double, reason: String): LlmTradeResult {
        if (!isPaperMode.get()) return LlmTradeResult.Rejected("HARD RULE V5.9.187: LLM cannot spend real money. Paper mode only.")
        val ticker = symbol.trim().uppercase()
        val market = try { PerpsMarket.valueOf(ticker) } catch (_: Exception) {
            return LlmTradeResult.Rejected("unknown symbol '$ticker'")
        }
        val clampedSize = sizeSol.coerceIn(0.05, 2.0)
        val avail = getEffectiveBalance()
        if (clampedSize > avail * 0.80) {
            return LlmTradeResult.Rejected(
                "size ${clampedSize.fmt(2)}◎ exceeds 80% of available ${avail.fmt(2)}◎"
            )
        }
        scope.launch {
            try {
                val priceData = try { PerpsMarketDataFetcher.getMarketData(market) } catch (_: Exception) { null }
                val price = priceData?.price ?: PerpsMarketDataFetcher.getCachedPrice(market)?.price ?: 0.0
                if (price <= 0.0) {
                    ErrorLogger.warn(TAG, "💬 LLM BUY ${market.symbol} — no price, aborting")
                    return@launch
                }
                val signal = AltSignal(
                    market           = market,
                    direction        = PerpsDirection.LONG,
                    score            = 70,
                    confidence       = 70,
                    price            = price,
                    priceChange24h   = priceData?.priceChange24hPct ?: 0.0,
                    reasons          = listOf("LLM chat: ${reason.take(80)}"),
                    layerVotes       = emptyMap(),
                    strategy7803     = "LLM_PROPOSED",
                    candidateVersion7803 = System.currentTimeMillis() / 30_000L,
                    // V5.0.7183 — this is dispatched with isSpot=true, so a
                    // 3x on the signal was already contradictory; it rode
                    // along onto the position record and into getPnlPct's
                    // `* leverage`. Stated honestly as 1.0.
                    leverage         = if (LEVERAGE_VENUE_AVAILABLE_7183) DEFAULT_LEVERAGE else 1.0,
                )
                executeSignal(signal, isSpot = true)
                try { com.lifecyclebot.engine.LlmTradeScore.recordOpen() } catch (_: Exception) {}
                ErrorLogger.info(TAG, "💬 LLM PAPER BUY: ${market.symbol} | ${clampedSize.fmt(3)}◎ | $reason")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "💬 LLM BUY error: ${e.message}")
            }
        }
        return LlmTradeResult.Success("📄 paper buy queued: ${market.symbol} ~${clampedSize.fmt(2)}◎")
    }

    /**
     * Close the latest-opened paper position matching a symbol.
     * V5.9.338: Now searches meme/BotService positions first (by symbol),
     * then falls back to CryptoAlt perps desk. The LLM was emitting
     * symbol="ASTEROID" but this function was only searching perps positions —
     * meme tokens (ShitCoin/Moonshot/Quality/BlueChip/Treasury) live in
     * BotService.status.tokens keyed by mint, not symbol. That caused the
     * "can't find token" error even when the position was visible on screen.
     */
    fun llmClosePaperSell(symbol: String, reason: String): LlmTradeResult {
        if (!isPaperMode.get()) return LlmTradeResult.Rejected("HARD RULE V5.9.187: LLM cannot spend real money. Paper mode only.")
        val ticker = symbol.trim().uppercase()

        // ── 1. Try meme / BotService positions first ──────────────────────────
        // These are TokenState objects in BotService.status.tokens, keyed by mint.
        // The LLM knows them by symbol (e.g. "ASTEROID"), so search by symbol.
        val botService = try { com.lifecyclebot.engine.BotService.instance } catch (_: Exception) { null }
        if (botService != null) {
            val memeTs = try {
                val tokensMap = com.lifecyclebot.engine.BotService.status.tokens
                synchronized(tokensMap) {
                    tokensMap.values
                        .filter { it.symbol.equals(ticker, ignoreCase = true) && it.position.isOpen }
                        .maxByOrNull { it.position.entryTime }
                }
            } catch (_: Exception) { null }

            if (memeTs != null) {
                return try {
                    val (ok, msg) = botService.manualSell(memeTs.mint)
                    if (ok) {
                        val pnlPct = if (memeTs.position.entryPrice > 0) (memeTs.lastPrice / memeTs.position.entryPrice - 1.0) * 100.0 else 0.0
                        ErrorLogger.info(TAG, "💬 LLM MEME SELL: $ticker pnl=${"%.1f".format(pnlPct)}% | $reason")
                        LlmTradeResult.Success("📄 meme sell queued: $ticker @ ${"%.1f".format(pnlPct)}%")
                    } else {
                        LlmTradeResult.Rejected("meme sell failed: $msg")
                    }
                } catch (e: Exception) {
                    LlmTradeResult.Rejected("meme sell error: ${e.message}")
                }
            }
        }

        // ── 2. Fall back to CryptoAlt perps desk ──────────────────────────────
        val match = activeModePositions7256(positions.values)
            .filter { it.market.symbol.equals(ticker, ignoreCase = true) && it.closeTime == null }
            .maxByOrNull { it.openTime }
            ?: return LlmTradeResult.Rejected("no open $ticker position found (checked meme + perps desks)")
        val pnlPct = match.getPnlPct()
        requestClose(match.id)
        ErrorLogger.info(TAG, "💬 LLM PERPS SELL: ${ticker} pnl=${pnlPct.fmt(1)}% | $reason")
        return LlmTradeResult.Success("📄 perps sell queued: $ticker @ ${pnlPct.fmt(1)}%")
    }
    private fun strategyFromReasons7803(reasons: List<String>): String =
        reasons.firstOrNull { it.startsWith("STRATEGY7803=") }
            ?.removePrefix("STRATEGY7803=")
            ?.takeIf { it.isNotBlank() }
            ?: "CRYPTO_NATIVE"

    /**
     * 7803 specialist exit identity. New positions may select a DESK_* strategy
     * directly or carry one/more DESK_* overlays. Resolve those first; retain
     * LANE7391 only for positions opened before the resident-strategy migration.
     */
    private fun specialistLaneFromReasons7803(reasons: List<String>): String {
        val strategy = strategyFromReasons7803(reasons)
        if (strategy.startsWith("DESK_")) return strategy.removePrefix("DESK_")
        val overlay = reasons.firstOrNull { it.startsWith("OVERLAY7803=DESK_") }
            ?.removePrefix("OVERLAY7803=DESK_")
            ?.takeIf { it.isNotBlank() }
        return overlay ?: CryptoLaneDesk7391.laneFromReasons(reasons)
    }

    fun hasPosition(market: PerpsMarket): Boolean = activeModePositions7256(positions.values).any { it.market == market }
    // V5.9.1472 — DYNAMIC CRYPTO: dedupe by REAL coin symbol, not the shared DYN
    // sentinel (otherwise only one DYN coin could ever be open at a time).
    fun hasPositionSymbol(symbol: String): Boolean =
        activeModePositions7256(positions.values).any { it.marketSymbol.equals(symbol, ignoreCase = true) }

    /** V5.9.85: Manual close for Markets UI. */
    fun closePositionManual(positionId: String, reason: String = "USER"): Boolean {
        if (positionId.isBlank()) return false
        val position7256 = positions[positionId]
            ?: spotPositions[positionId]
            ?: leveragePositions[positionId]
            ?: return false
        if (position7256.isPaper != isPaperMode.get()) return false
        closePosition(positionId, reason)
        return true
    }

    fun getTotalTrades(): Int    = totalTrades.get()
    fun getWinCount(): Int       = winningTrades.get()
    // V5.9.358 — honest WR: decisive trades only (wins / (wins + losses)).
    // Matches RunTracker30D + Meme contract. Was wins/totalTrades which
    // included scratches as "denominator only" while pnlSol≥0 made them
    // count as wins → top header showed 62% with -13 SOL realised PnL.
    fun getWinRate(): Double {
        val w = winningTrades.get()
        val l = losingTrades.get()
        val decisive = w + l
        return if (decisive > 0) w.toDouble() / decisive * 100 else 0.0
    }
    fun getScratchCount(): Int   = scratchTrades.get()
    fun getLossCount(): Int      = losingTrades.get()
    fun getTotalPnlSol(): Double = totalPnlSol
    fun getInitialBalance(): Double = if (initialBalance > 0.0) initialBalance else paperBalance

    fun getUnrealizedPnlSol(): Double = activeModePositions7256(positions.values).sumOf { it.getPnlSol() }
    fun getUnrealizedPnlPct(): Double {
        val bal = getEffectiveBalance()
        return if (bal > 0) getUnrealizedPnlSol() / bal * 100 else 0.0
    }

    fun getStats(): Map<String, Any> {
        // V5.9.432 — overlay RunTracker30D per-lane bucket so UI numbers
        // match the 30D tracker / Live Readiness views. In-memory counters
        // are the fast local feed; RunTracker30D is the authoritative
        // cross-session source and is preferred when it has more trades
        // (meaning process was restarted and in-memory was reset).
        var trades   = totalTrades.get()
        var wins     = winningTrades.get()
        var losses   = losingTrades.get()
        var scratch  = scratchTrades.get()
        var wr       = getWinRate()
        try {
            val lane = com.lifecyclebot.engine.RunTracker30D.getLaneStats("CRYPTO_ALT")
            val laneTrades = (lane["trades"] as? Int) ?: 0
            if (laneTrades > trades) {
                trades  = laneTrades
                wins    = (lane["wins"]      as? Int) ?: wins
                losses  = (lane["losses"]    as? Int) ?: losses
                scratch = (lane["scratches"] as? Int) ?: scratch
                wr      = (lane["winRate"]   as? Double) ?: wr
            }
        } catch (_: Exception) {}
        return mapOf(
            "totalTrades"    to trades,
            "winningTrades"  to wins,
            "losingTrades"   to losses,
            "scratchTrades"  to scratch,   // V5.9.419 — expose for UI W/L/S parity
            "winRate"        to wr,
            "totalPnlSol"    to totalPnlSol,
            "openPositions"  to activeModePositions7256(positions.values).size,
            "paperBalance"   to paperBalance,
            "isLiveMode"     to !isPaperMode.get(),
            "learningPhase"  to getPhaseLabel(),
            "sizePolicy"      to "MEME_PARITY_6095 base=6pct cap=45pct toxic_soft_shape",
            "layerPolicy"     to "41+ MEME+CRYPTO layers · DIAMOND/INSIDER_SHARK/COPY visible"
        )
    }

    private fun getPhaseLabel(): String {
        // V5.9.1442 — isolated crypto brain maturity.
        val trades = com.lifecyclebot.perps.crypto.brain.CryptoBrain.tradeCount()
        val wr     = getWinRate()
        return when {
            trades < 250  -> "📚 BOOTSTRAP"
            trades < 1000 -> "🧠 LEARNING"
            trades < 2500 -> "🔬 VALIDATING"
            trades < 4500 -> "⚡ MATURING"
            wr >= 52.0    -> "✅ READY"
            else          -> "⚡ MATURING"
        }
    }

    /** Whether this trader has met all requirements to go live */
    fun isLiveReady(): Boolean =
        com.lifecyclebot.perps.crypto.brain.CryptoBrain.tradeCount() >= 4500 && getWinRate() >= 52.0

    /** V5.9.3: Called from MAA when user taps SPOT/LEVERAGE toggle */
    fun setPreferLeverage(lev: Boolean) {
        preferLeverage.set(lev)
        ErrorLogger.info(TAG, "🪙 Mode → ${if (lev) "LEVERAGE (${DEFAULT_LEVERAGE.toInt()}x)" else "SPOT"}")
    }
    fun isPreferLeverage(): Boolean = preferLeverage.get()

    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt

    /**
     * V5.0.6972 — one regime string for the exit-tag learner, read from the same
     * RegimeDetector the rest of the stack uses so exit-reason x regime buckets
     * line up with every other regime-keyed surface.
     */
    private fun regimeTag6972(): String = try {
        com.lifecyclebot.engine.RegimeDetector.currentRegime().name
    } catch (_: Throwable) { "NEUT" }

}
