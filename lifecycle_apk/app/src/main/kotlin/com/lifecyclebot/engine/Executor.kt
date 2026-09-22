package com.lifecyclebot.engine

import com.lifecyclebot.util.AppDispatchers
import android.content.Context
import com.lifecyclebot.engine.NotificationHistory
import com.lifecyclebot.engine.quant.QuantMetrics
import com.lifecyclebot.engine.quant.PortfolioAnalytics
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

import com.lifecyclebot.data.*
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.util.pct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.lifecyclebot.data.allowLiveMicroProbe
import com.lifecyclebot.data.capitalMode
import com.lifecyclebot.data.maxLiveBuySol
import com.lifecyclebot.data.maxPoolImpactPct
import com.lifecyclebot.data.maxWalletRiskPerTradePct
import com.lifecyclebot.data.minLiveBuySol


data class MintEntryMarketSnapshot(
    val priceUsd: Double,
    val marketCapUsd: Double,
    val liquidityUsd: Double,
    val poolAddress: String,
    val priceSource: String,
    val dex: String,
    val capturedAtMs: Long = System.currentTimeMillis(),
) {
    val valid: Boolean get() = priceUsd.isFinite() && priceUsd > 0.0 &&
        // V5.0.3976 ‚Äî mcap is learning/report metadata, not executable basis.
        // Under Birdeye conservation / fresh-pool APIs, price+liquidity can be
        // fully executable while market cap or a concrete pool address is absent.
        // Requiring mcap>0 created a silent live-buy choke in 3976; requiring a
        // pool address resurfaced the same choke in 3982 as
        // ENTRY_MARKET_SNAPSHOT_MISSING_DEFERRED. Accounting basis needs a real
        // price + liquidity + source; pool/route id is route metadata and may be
        // filled by a mint-route sentinel until Jupiter/executor proves the swap.
        marketCapUsd.isFinite() && marketCapUsd >= 0.0 &&
        liquidityUsd.isFinite() && liquidityUsd > 0.0 &&
        priceSource.isNotBlank()

    companion object {
        /** The provider timestamp, identity and price travel together. Reading a
         * quote never refreshes it. The paper/live provenance gate still decides
         * whether this tuple may create an economic position. */
        internal fun fromCanonicalMark6735(
            mint: String,
            mark: com.lifecyclebot.engine.truth.CanonicalPriceMark6522,
            marketCapUsd: Double,
            dex: String,
            nowMs: Long = System.currentTimeMillis(),
        ): MintEntryMarketSnapshot? {
            if (mark.mint != mint || mark.baseMint != mint || mark.timestampMs <= 0L ||
                nowMs - mark.timestampMs !in -5_000L..120_000L ||
                mark.purpose == com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.EXIT_ECONOMIC)
                return null
            val price = mark.priceUsd.value.toDouble()
            if (com.lifecyclebot.engine.truth.MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(price))
                return null
            return MintEntryMarketSnapshot(
                priceUsd = price,
                marketCapUsd = marketCapUsd.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                liquidityUsd = mark.liquidityUsd?.toDouble() ?: 0.0,
                poolAddress = mark.pairId, priceSource = mark.source, dex = dex,
                capturedAtMs = mark.timestampMs,
            ).takeIf { it.valid }
        }
    }
}

// V5.0.6052 ‚Äî ROUTE-LOCK DOCTRINE: max staleness for a cached on-route
// tick before we prefer entryPrice over the cache. 60s is generous enough
// to bridge Helius/Birdeye backoff windows without letting a truly-dead
// route lull us into ignoring real moves.
private const val ROUTE_LOCK_MAX_STALENESS_MS: Long = 60_000L

// V5.0.6895 ‚Äî cross-source comparability band for PAPER marks.
//
// A quote arriving on a DIFFERENT source from the one the entry was stamped
// on is only comparable to that entry if the two sit on the same basis. A
// pump.fun bonding-curve entry (mcap/1B) against a post-graduation AMM quote
// differs by orders of magnitude, and operator 5.0.6892 shows what that books:
// a +15,532% partial on EaxKqb, QUALITY averaging +3170% on a 5/21 record.
//
// 10x is deliberately generous. A genuine runner on a STABLE source is never
// touched by this at any multiple ‚Äî the 10x-1000x doctrine (V5.9.1358) is not
// negotiable, and this gate requires a source change before it looks at
// magnitude at all. What it catches is the discontinuous one-tick jump that
// only a basis switch produces. Anything inside the band still flows through
// untouched, so a cross-source move that is merely large stays tradeable.
private const val CROSS_BASIS_MAX_RATIO_6895: Double = 10.0

// V5.0.6904 ‚Äî evidence thresholds for the catastrophic backstop.
//
// CATASTROPHE_LIQ_FLOOR_USD_6904: a pool this thin cannot absorb an exit, so a
// 25% drawdown against it is a genuine liquidity event rather than a dip. Set
// to the same $5K the V5.0.4551 earlyRug block already treats as thin, so the
// two gates agree on what "thin" means instead of using different numbers.
//
// CATASTROPHE_UNAMBIGUOUS_PCT_6904: past this depth, price IS the evidence. A
// memecoin does not print -60% and recover with its pool intact, so requiring
// further confirmation there would only add latency to a real rug. This is the
// escape hatch that keeps the backstop's original protective purpose whole.
private const val CATASTROPHE_LIQ_FLOOR_USD_6904: Double = 5_000.0
private const val CATASTROPHE_UNAMBIGUOUS_PCT_6904: Double = -60.0

// V5.0.6054 ‚Äî REAL PRICE SOURCES for route-lock enforcement.
// Anything NOT real is treated as symbolic/recovery basis (e.g.
// LIVE_PROOF_COST_BASIS, RESTORED_LIVE_BASIS_UNKNOWN, SYNTH_COST_DIV_QTY,
// WALLET_REHYDRATE_BASIS_UNKNOWN, UNKNOWN). Symbolic sources can never
// match a live tick source, so route-lock would freeze the position
// forever. Bypass it and self-heal on the first real on-route tick.
//
// V5.0.7166 ¬ßTHE ALLOW-LIST WAS WRITTEN BEFORE THE FEEDS IT HAD TO NAME.
//
// 6054 enumerated the sixteen source labels that existed in 2026-06. Since
// then the mark path was rebuilt three times and every one of its labels is
// absent from that list:
//
//   BotService:10783  "DEXSCREENER_BATCH"
//   BotService:10971  "FANOUT_CORROBORATED_7088_x$n" / "FANOUT_UNCORROBORATED_7088"
//   BotService:10998  "KEYLESS_BATCH_6996"
//   BotService:11093  "KEYLESS_$provider"
//   BotService:14492  "TOKEN_META_ARCHIVE_6908"
//                     "DEFILLAMA_CROSSCHAIN_7004", "TOKEN_MAP_CACHE_6513"
//
// 6999 is where it broke. Before it, every REST mark was stamped
// "DEXSCREENER_WS" whatever answered ‚Äî that was a reporting bug, and fixing
// it to name the real provider silently un-listed the entire REST mark path.
// A corroborated six-feed price is now classified the same as
// "BASIS_UNKNOWN". One of those labels cannot even be matched by a literal
// set: the corroboration count is IN the string, so x2 and x3 are different
// "sources" on consecutive ticks.
//
// What that switches off is not small. Three separate builds of protection
// live behind `isRealPriceSource(entry) && isRealPriceSource(tick)`:
// 6895's cross-basis refusal, 7017's entry-mcap backfill and 7059's
// same-source mcap cross-check ‚Äî the guard written because paper booked
// +15,532% on one rung and taught every learner that QUALITY prints money.
// None of them has run on a fanout-marked position.
//
// So stop maintaining a list of everything that exists and classify what a
// label MEANS instead. There are only three kinds, and the closed set is the
// symbolic one because we write those stamps ourselves:
//
//   SYMBOLIC ‚Äî an accounting stamp. No market observed this number.
//   PUMP_BC  ‚Äî pre-graduation bonding-curve pricing (mcap/1B). A different
//              BASIS, which is the switch 6895 exists to catch.
//   MARKET   ‚Äî an observed AMM/aggregator price.
//
// Comparing FAMILY rather than label is the other half, and it must ship
// with it: promoting these labels to "real" while still demanding an exact
// string match would make route-lock reject every tick whose provider
// rotated, freeze the position on its entry price, and hide real losses.
// DexScreener's WS and its batch poll are the same basis; a fanout mark and
// a keyless mark are the same basis; the bonding curve is not.
private const val PRICE_BASIS_SYMBOLIC_7166 = "SYMBOLIC"
private const val PRICE_BASIS_PUMP_BC_7166 = "PUMP_BC"
private const val PRICE_BASIS_MARKET_7166 = "MARKET"

private fun priceBasisFamily7166(source: String): String {
    val s = source.trim().uppercase()
    if (s.isBlank()) return PRICE_BASIS_SYMBOLIC_7166
    return when {
        s == "UNKNOWN" ||
            s.contains("BASIS_UNKNOWN") || s.contains("COST_BASIS") ||
            s.contains("SYNTH_COST") || s.contains("REHYDRATE") ||
            s.contains("RESTORED") || s.contains("REPLAY") ||
            s.contains("QUARANTIN") || s.contains("UNRECOVERABLE") ||
            s.contains("CAPPED_EXIT") || s.contains("PENDING_CALLER") -> PRICE_BASIS_SYMBOLIC_7166
        s.contains("PUMP") -> PRICE_BASIS_PUMP_BC_7166
        else -> PRICE_BASIS_MARKET_7166
    }
}

private fun isRealPriceSource(source: String): Boolean =
    priceBasisFamily7166(source) != PRICE_BASIS_SYMBOLIC_7166

/** Same observable basis? Route-lock and the paper basis guard both ask this. */
private fun sameBasis7166(entrySource: String, tickSource: String): Boolean =
    priceBasisFamily7166(entrySource) == priceBasisFamily7166(tickSource)

private enum class SellRoutePriority6099 { PUMP_FIRST, JUPITER_FIRST, UNKNOWN_BUY_ROUTE }

private fun sellRoutePriorityFromBuyRoute6099(ts: TokenState): SellRoutePriority6099 {
    val p = ts.position
    val tm = ts.tokenMap
    val hay = listOf(
        p.entryPriceSource,
        p.entryPoolAddress,
        p.entryPolicySnapshot,
        ts.lastPriceSource,
        ts.lastPricePoolAddr,
        ts.pairAddress,
        ts.source,
        tm.sourceScanner,
        tm.venue,
        tm.dexId,
        tm.routeStatus,
        tm.poolAddress,
        tm.pairAddress,
        tm.pumpFunBondingCurveAddress,
    ).joinToString(" ").uppercase()
    return when {
        hay.contains("PUMP") && !tm.migratedOrGraduated -> SellRoutePriority6099.PUMP_FIRST
        hay.contains("JUPITER") || hay.contains("ULTRA") || hay.contains("METIS") || tm.jupiterQuoteOk || tm.expectedOutAmount > 0.0 -> SellRoutePriority6099.JUPITER_FIRST
        hay.contains("RAYDIUM") || hay.contains("ORCA") || hay.contains("METEORA") || hay.contains("DEX") || hay.contains("POOL") || p.entryPoolAddress.isNotBlank() || ts.lastPricePoolAddr.isNotBlank() || ts.pairAddress.isNotBlank() -> SellRoutePriority6099.JUPITER_FIRST
        else -> SellRoutePriority6099.UNKNOWN_BUY_ROUTE
    }
}

private fun shouldTryPumpDirectFirst6099(ts: TokenState, label: String): Boolean {
    val priority = sellRoutePriorityFromBuyRoute6099(ts)
    val pumpInvalid = try { com.lifecyclebot.engine.sell.MemeVenueRouter.isPumpRouteInvalid(ts.mint) } catch (_: Throwable) { false }
    val pumpFirst = priority == SellRoutePriority6099.PUMP_FIRST && !pumpInvalid
    try {
        ForensicLogger.lifecycle(
            "SELL_ROUTE_PRIORITY_BUY_ROUTE_6099",
            "mint=${ts.mint.take(10)} symbol=${ts.symbol} label=$label priority=$priority pumpFirst=$pumpFirst pumpInvalid=$pumpInvalid entrySource=${ts.position.entryPriceSource} entryPool=${ts.position.entryPoolAddress.take(16)} source=${ts.source} lastPriceSource=${ts.lastPriceSource} pool=${ts.lastPricePoolAddr.take(16)} routeStatus=${ts.tokenMap.routeStatus}"
        )
    } catch (_: Throwable) {}
    try { PipelineHealthCollector.labelInc("SELL_ROUTE_PRIORITY_${priority.name}_6099") } catch (_: Throwable) {}
    return pumpFirst
}

/**
 * Executor v3 ‚Äî SecurityGuard integrated
 *
 * Every live trade now passes through SecurityGuard checks:
 *   1. Pre-flight (buy): circuit breaker, wallet reserve, rate limit,
 *      position cap, price/volume anomaly
 *   2. Quote validation: price impact ‚â§ 3%, output ‚â• 90% expected
 *   3. Sign delay enforced (500ms between sign and broadcast)
 *   4. Post-trade: circuit breaker counters updated
 *   5. Key integrity verified before every tx
 *   6. All log messages sanitised ‚Äî no keys in logs
 */
class Executor(
    private val cfg: () -> com.lifecyclebot.data.BotConfig,
    private val onLog: (String, String) -> Unit,
    private val onNotify: (String, String, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType) -> Unit,
    private val onToast: (String) -> Unit = {},  // Toast callback for immediate visual feedback
    val security: SecurityGuard,
    private val sounds: SoundManager? = null,
) {
    companion object {

        /**
         * V5.0.7003 ‚Äî how long the price feed must ACTUALLY be dark before the
         * stale-quote backstop force-exits a position.
         *
         * 90 seconds. The mark loop runs at 1Hz and QuoteFreshnessGuard already
         * treats 60s as stale, so a genuine outage clears this in a minute and a
         * half. A rug's outage is permanent, so the backstop still fires on every
         * case it exists for ‚Äî it just stops firing on a token whose pool is new
         * enough that no aggregator has indexed it yet, which is most of what
         * this bot buys.
         */
        private const val STALE_QUOTE_OUTAGE_MIN_MS_7003 = 90_000L

        // V5.9.1499 ‚Äî ANALYTICS TIMESTAMP INTEGRITY (root-cause fix).
        // Persisted TradeRecords drove a 29,684,473-minute "held" value and a
        // 0.0% drawdown in the analytics block. Root cause: when a position was
        // rehydrated/restored (or its entry record was lost) pos.entryTime was 0
        // (epoch), so (now - entryTime)/60000 = now/60000 ‚âà 29M minutes and the
        // tsEntry=0 poisoned the equity-curve ordering. These helpers are the
        // SINGLE source of a sane entry timestamp + bounded hold for every
        // journal insert, so corruption can never enter the analytics store.
        // A trade cannot sanely be held longer than this; anything above is a
        // lost/zero entry timestamp, not a real hold.
        private const val MAX_SANE_HOLD_MS = 7L * 24L * 60L * 60L * 1000L  // 7 days

        /** Returns a trustworthy entry-ts. If rawEntry is missing/epoch/absurd
         *  relative to exit, fall back to exitTs (hold‚âà0) rather than poison the
         *  record. Never returns 0. */
        fun sanitizeEntryTs(rawEntry: Long, exitTs: Long): Long {
            val exit = if (exitTs > 0L) exitTs else System.currentTimeMillis()
            // valid = positive, not in the future, within the sane hold window.
            if (rawEntry in 1 until exit && (exit - rawEntry) <= MAX_SANE_HOLD_MS) return rawEntry
            return exit
        }

        /** Bounded hold in minutes from a (possibly bad) raw entry-ts. */
        fun sanitizeHeldMins(rawEntry: Long, exitTs: Long): Double {
            val exit = if (exitTs > 0L) exitTs else System.currentTimeMillis()
            val entry = sanitizeEntryTs(rawEntry, exit)
            return ((exit - entry).coerceAtLeast(0L)) / 60_000.0
        }

        // V5.9.1442 ‚Äî physically-impossible single-tick gain multiple. Any exit-tick
        // multiple above this is a stale/glitched price quote (e.g. failed pump.fun‚Üí
        // Raydium rebase), NOT a real move. Real graduations rebase far below this.
        private const val PHANTOM_MULTIPLE_CEILING = 1000.0
        // V5.9.719: Paper sell lock ‚Äî prevents double-exit race condition where
        // CASHGEN_STOP_LOSS and STALE_LIVE_PRICE_RUG_ESCAPE fire simultaneously
        // for the same paper position. Both see isOpen=true before either closes it.
        private val paperSellLocks = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
        // V5.0.6447 ‚Äî source-level same-mint open cooldown for paper buy aliases.
        // ExecutableOpenGate still protects finality, but repeated lane/source
        // aliases were hitting PAPER_SAME_MINT_ALREADY_OPEN_6371 ~100 times per
        // snapshot. This short mint-wide cooldown suppresses repeat work before
        // price/finality/size paths while preserving re-entry after close.
        private val paperSameMintOpenCooldownUntil6447 = ConcurrentHashMap<String, Long>()
        private const val PAPER_SAME_MINT_OPEN_COOLDOWN_MS_6447: Long = 45_000L

        private fun paperSameMintOpenCooldownActive6447(mint: String): Boolean {
            val now = System.currentTimeMillis()
            val until = paperSameMintOpenCooldownUntil6447[mint] ?: return false
            return if (now < until) true else {
                paperSameMintOpenCooldownUntil6447.remove(mint, until)
                false
            }
        }

        private fun armPaperSameMintOpenCooldown6447(mint: String) {
            paperSameMintOpenCooldownUntil6447[mint] = System.currentTimeMillis() + PAPER_SAME_MINT_OPEN_COOLDOWN_MS_6447
        }
        // V5.0.6071 ‚Äî paperSellLock TTL. If a sell path crashes/exceptions
        // between acquire and release (e.g. dead price oracle throws inside
        // paperSell), the AtomicBoolean stays `true` forever and every future
        // sell for that mint returns ALREADY_CLOSED. Record acquire timestamp
        // so orphaned locks older than the TTL can be reclaimed on retry.
        private val paperSellLockAcquiredMs = ConcurrentHashMap<String, Long>()
        private const val PAPER_SELL_LOCK_STALE_MS = 60_000L  // 1 min TTL
        // V5.9.1022 ‚Äî POST-SELL COOLDOWN. Operator V5.9.1021 snapshot showed
        // same mint (4vRgJ7) recorded TWO SELL trades within 118 ms:
        //   21:47:37.057  SELL  RAPID_CATASTROPHE_STOP  sol=0.275 pnl=-0.056
        //   21:47:37.175  SELL  CASHGEN_STOP_LOSS       sol=0.661 pnl=-0.030
        // The lock prevents concurrent execution but releases on completion;
        // a second sell arriving 118 ms after release re-acquires cleanly
        // because acquirePaperSellLock removes the entry on release (line 522).
        // The position may also be partially-updated mid-fanout (V5.9.1011 async
        // journal/learning), so pos.isOpen can read stale-true for a small
        // window after release.
        //
        // This cooldown map records the last completed-sell timestamp per mint.
        // Any new paperSell within 2 s of the prior completion returns
        // ALREADY_CLOSED without recording ‚Äî eliminating the duplicate trade
        // and the credit burn from its downstream learning fanout.
        private val lastPaperSellCompletedMs = ConcurrentHashMap<String, Long>()
        private const val PAPER_SELL_COOLDOWN_MS = 2_000L
        fun acquirePaperSellLock(mint: String): Boolean {
            // Cooldown gate fires BEFORE the AtomicBoolean dance so the same
            // mint can't reuse a freshly-vacated lock slot inside 2 s.
            val lastDoneMs = lastPaperSellCompletedMs[mint]
            if (lastDoneMs != null && (System.currentTimeMillis() - lastDoneMs) < PAPER_SELL_COOLDOWN_MS) {
                return false
            }
            // V5.0.6071 ‚Äî reclaim orphaned locks. If a lock has been held for
            // longer than PAPER_SELL_LOCK_STALE_MS without a corresponding
            // release, the prior sell path crashed. Force-release so retry
            // can succeed.
            val acquiredMs = paperSellLockAcquiredMs[mint]
            if (acquiredMs != null && (System.currentTimeMillis() - acquiredMs) > PAPER_SELL_LOCK_STALE_MS) {
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "PAPER_SELL_LOCK_STALE_RECLAIMED_6071",
                        "mint=${mint.take(10)} ageMs=${System.currentTimeMillis() - acquiredMs} action=force_release"
                    )
                } catch (_: Throwable) {}
                paperSellLocks.remove(mint)
                paperSellLockAcquiredMs.remove(mint)
            }
            val acquired = paperSellLocks.getOrPut(mint) { java.util.concurrent.atomic.AtomicBoolean(false) }
                .compareAndSet(false, true)
            if (acquired) paperSellLockAcquiredMs[mint] = System.currentTimeMillis()
            return acquired
        }
        fun releasePaperSellLock(mint: String) {
            // Record completion timestamp BEFORE removing the lock so the
            // cooldown gate sees it on the very next acquire attempt.
            lastPaperSellCompletedMs[mint] = System.currentTimeMillis()
            paperSellLocks.remove(mint)
            paperSellLockAcquiredMs.remove(mint)
        }
        /** V5.9.720: force-clear ALL paper sell locks ‚Äî called on bot stop so shutdown
         *  close can never be blocked by a stale lock from a crashed sell path.
         *  V5.9.1022: also clear the post-sell cooldown so bot-restart can sell
         *  the same mints immediately. */
        fun clearAllPaperSellLocks() {
            paperSellLocks.clear()
            paperSellLockAcquiredMs.clear()
            lastPaperSellCompletedMs.clear()
        }
        // V5.7.3: Dual wallet fee system
        private const val TRADING_FEE_WALLET_1 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
        private const val TRADING_FEE_WALLET_2 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"

        /**
         * V5.9.1504 ‚Äî SELF-LOOP FEE FIX. At the time, the operator's trading
         * wallet was A8QPQr‚Ä¶kkpd, identical to TRADING_FEE_WALLET_1, so every
         * fee_w1 send was a transfer-to-self ‚Üí "Account loaded twice" failure
         * on EVERY sell, the fee share permanently stuck in the retry queue.
         *
         * V5.0.7124 ‚Äî THAT IS NO LONGER TRUE, AND THE SENTENCE ABOVE USED TO
         * ASSERT IT IN THE PRESENT TENSE. The operator's trading wallet is
         * cVD9iLb‚Ä¶v6ks, which appears nowhere in this codebase; both
         * TRADING_FEE_WALLET_1 and _2 are confirmed fee destinations they own
         * and expect a 50/50 split across. So `self` matches NEITHER, the
         * resolver below is a no-op in the current configuration, and every
         * share goes to its intended wallet. The stale claim cost real
         * diagnostic time ‚Äî it is the reason the redirect was first suspected
         * of collapsing both shares onto wallet 2, which it does not do. A
         * comment that names a wallet is a comment that expires when the
         * wallet rotates; the resolver is kept precisely so the code does not
         * depend on which one is true today.
         *
         * This sender resolves the real destination per share: if a fee wallet
         * equals the sending wallet's own address, that share is REDIRECTED to
         * the other fee wallet (so the fee is still collected, not lost). If
         * BOTH equal self, the share is skipped cleanly (no failed tx, no
         * endless queue). Returns true if at least one transfer was attempted.
         *
         * V5.0.3919 ‚Äî FEE-MIN THRESHOLD LOWERED to 0.000005 SOL.
         *
         * V5.0.3920 ‚Äî FEE ACCUMULATOR. Instead of attempting a network tx
         * per micro fee (Solana base fee + priority fee + rent checks make
         * sub-$0.10 fees uneconomic and most fail), accrue each share to a
         * persisted per-destination bucket. The next FeeRetryQueue drain
         * (once per scan cycle) calls FeeAccumulator.tryFlush(wallet)
         * which flushes/distributes all destination buckets once total onboard
         * accrued fees cross 1.0 SOL. Result: large batched transfers, no
         * micro-fee tx spam, no fees silently lost.
         */
        private const val FEE_SEND_MIN_SOL = 0.000005
        private fun sendFeeSplit(
            wallet: SolanaWallet,
            amount1: Double,
            amount2: Double,
            tag: String,
        ): Boolean {
            val self = try { wallet.publicKeyB58 } catch (_: Throwable) { "" }
            fun dest(primary: String, fallback: String): String? = when {
                !primary.equals(self, false) -> primary
                !fallback.equals(self, false) -> fallback   // redirect self‚Üíother
                else -> null                                 // both self ‚Äî skip
            }
            var accrued = false
            // share 1 ‚Üí wallet1, falling back to wallet2 if wallet1 is self
            if (amount1 >= FEE_SEND_MIN_SOL) {
                val d = dest(TRADING_FEE_WALLET_1, TRADING_FEE_WALLET_2)
                if (d != null) {
                    // V5.0.6439 ‚Äî observability. Prove the fee actually reached the pipe.
                    // V5.0.7124 ‚Äî MOVED AFTER the accrual, and made conditional on it.
                    // It used to fire first, unconditionally, so a share that accrue()
                    // then dropped (uninitialised prefs returned 0.0 without throwing)
                    // was counted as having reached the pipe. That is the exact shape
                    // of instrument that turns "fees never send" into an unfalsifiable
                    // complaint: the counter agreed the fee had been taken.
                    try {
                        val bucket7124 = FeeAccumulator.accrue(d, amount1, "${tag}_w1")
                        if (bucket7124 > 0.0) {
                            accrued = true
                            try { com.lifecyclebot.engine.truth.FeeAccrualObservability6439.noteAccrue(d, amount1, "${tag}_w1", false) } catch (_: Throwable) {}
                        } else {
                            PipelineHealthCollector.labelInc("FEE_ACCRUE_RETURNED_ZERO_7124")
                        }
                    }
                    catch (e: Exception) {
                        // Accumulator persistence failed ‚Äî fall back to immediate retry queue
                        FeeRetryQueue.enqueue(d, amount1, "${tag}_w1_acc_fail")
                    }
                } else {
                    ErrorLogger.warn("Executor", "ü™ô fee_w1 ($tag) skipped: both fee wallets == self")
                }
            } else if (amount1 > 0.0) {
                ErrorLogger.debug("Executor", "ü™ô fee_w1 ($tag) dust-skipped: ${amount1} SOL < ${FEE_SEND_MIN_SOL}")
            }
            // share 2 ‚Üí wallet2, falling back to wallet1 if wallet2 is self
            if (amount2 >= FEE_SEND_MIN_SOL) {
                val d = dest(TRADING_FEE_WALLET_2, TRADING_FEE_WALLET_1)
                if (d != null) {
                    // V5.0.6439 ‚Äî observability. V5.0.7124 ‚Äî see the w1 share above.
                    try {
                        val bucket7124 = FeeAccumulator.accrue(d, amount2, "${tag}_w2")
                        if (bucket7124 > 0.0) {
                            accrued = true
                            try { com.lifecyclebot.engine.truth.FeeAccrualObservability6439.noteAccrue(d, amount2, "${tag}_w2", false) } catch (_: Throwable) {}
                        } else {
                            PipelineHealthCollector.labelInc("FEE_ACCRUE_RETURNED_ZERO_7124")
                        }
                    }
                    catch (e: Exception) {
                        FeeRetryQueue.enqueue(d, amount2, "${tag}_w2_acc_fail")
                    }
                } else {
                    ErrorLogger.warn("Executor", "ü™ô fee_w2 ($tag) skipped: both fee wallets == self")
                }
            } else if (amount2 > 0.0) {
                ErrorLogger.debug("Executor", "ü™ô fee_w2 ($tag) dust-skipped: ${amount2} SOL < ${FEE_SEND_MIN_SOL}")
            }
            return accrued
        }
        
        // V5.7.3: Fee percentages
        private const val MEME_TRADING_FEE_PERCENT = 0.005  // V5.9.1451 operator: 1% ROUND-TRIP = 0.5% per side (buy + sell both charge this)
        private const val PERPS_TRADING_FEE_PERCENT = 0.01  // 1% for leverage/perps trades
        
        // Fee split (50/50 between wallets)
        private const val FEE_SPLIT_RATIO = 0.5

        // V5.9.778 ‚Äî EMERGENT MEME-ONLY: MEME_LIVE_BUY_MUTEX.
        // Operator forensics showed 15 live buy attempts (each ~0.097
        // SOL) competing against a 0.107 SOL wallet balance, causing
        // Jupiter "Insufficient funds" failures + balance exhaustion.
        // Single live-buy in flight at a time per wallet; tryAcquire
        // with a short timeout so we don't queue up stale signals.
        private val MEME_LIVE_BUY_MUTEX = java.util.concurrent.Semaphore(1, true)
        private const val MEME_LIVE_BUY_MUTEX_WAIT_MS = 150L
        // V5.0.4561 ‚Äî one journaled terminal SELL row per mint-close finality id.
        // PositionCloseLedger itself is the close authority; this set tracks
        // whether that closeId has already been journaled/trained by recordTrade().
        private val terminalSellJournaledCloseIds4561 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // V5.0.4576 ‚Äî live BUY journal idempotency by signature. A confirmed
        // buy can be observed by the verifier/reconciler before the liveBuy
        // thread reaches its normal recordTrade(BUY) section. The recovery path
        // must backfill a missing BUY row, but never duplicate one for the same tx.
        private val liveBuyJournaledSigs4576 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // V5.0.6637 ‚Äî proof-gated BUY side effects. Journal, learning,
        // notifications, sounds, and platform-fee accrual are committed once
        // per finalized transaction signature and never at quote/broadcast time.
        private val liveBuyProofSideEffects6637 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // V5.0.4585 ‚Äî real-money notification idempotency. Win/capital alerts
        // must be emitted only after wallet-finalized SOL accounting, never from
        // mark-price trigger paths, and never more than once for the same sell tx.
        private val realizedMoneyNotifiedSellKeys4585 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
    
    // Lazy init to get Jupiter API key from config
    private val jupiter: JupiterApi by lazy { JupiterApi(cfg().jupiterApiKey) }
    var brain: BotBrain? = null
    var tradeDb: TradeDatabase? = null
    var onPaperBalanceChange: ((Double) -> Unit)? = null  // Callback to update paper wallet balance
    private val slippageGuard: SlippageGuard by lazy { SlippageGuard(jupiter) }
    private var lastNewTokenSoundMs = 0L

    // V5.9.780 ‚Äî short helpers so the 36 mode-decision call sites don't
    // each carry the full FQN string. The bulk replacement of
    // cfg().paperMode ‚Üí isPaperRT()
    // pushed one big method past the JVM 64 KB bytecode limit
    // (Kotlin "Couldn't transform method node" failure on V5.9.779/780).
    // These two collapse every call back to a single virtual dispatch.
    //
    // V5.9.786 ‚Äî CRITICAL FIX:
    //   V5.9.780a shipped these as `private fun isPaperRT(): Boolean = isPaperRT()`
    //   ‚Äî INFINITE RECURSION. Kotlin accepted it (a function may call itself), so
    //   CI was green, but every runtime call StackOverflowError'd. Effects:
    //     1. EXEC_PAPER_BUY_OK=0 ‚Äî paperBuy()/openPosition mode checks crashed,
    //        silently dropping every FDG-approved trade (36 allow ‚Üí 0 exec).
    //     2. BOTLOOP_RESCUE_THREW=58/60 ‚Äî bot loop mode checks crashed every tick.
    //   The intent (per V5.9.780a commit message) was to call RuntimeModeAuthority.
    private fun isPaperRT(): Boolean = RuntimeModeAuthority.isPaper()
    private fun isLiveRT(): Boolean = RuntimeModeAuthority.isLive()

    
    // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
    // SHADOW PAPER POSITIONS
    // Track shadow positions separately from live/paper positions.
    // These are monitored for learning but don't affect real balance.
    // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
    data class ShadowPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val quality: String,
        val entryScore: Double,
        val source: String,
    )
    private val shadowPositions = mutableMapOf<String, ShadowPosition>()
    private val MAX_SHADOW_POSITIONS = 20  // Limit to prevent memory bloat

    // V5.0.7215 ‚Äî the shadow paper book's counters live in
    // ShadowBookTelemetry7215, not here. PipelineHealthCollector has no handle
    // on the service's Executor instance, and acceptance test J (">=20 clean
    // paper closes") has to be readable from the report. See that file for why
    // this book was invisible and what its three silent evidence-destroying
    // exits were.
    
    // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
    // V3.3: RECOVERY SCAN TRACKING
    // Tokens that hit hard/fluid stop go back to watchlist for potential recovery
    // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
    data class RecoveryCandidate(
        val mint: String,
        val symbol: String,
        val stopPrice: Double,        // Price at which we stopped out
        val lossPct: Double,          // How much we lost
        val stopTime: Long,           // When we stopped out
        val stopReason: String,       // "hard_floor" or "fluid_stop"
        val targetRecoveryPrice: Double,  // Price we need to hit for breakeven re-entry
    )
    private val recoveryCandidates = mutableMapOf<String, RecoveryCandidate>()
    private val RECOVERY_SCAN_WINDOW_MS = 30 * 60 * 1000L  // 30 minute window for recovery
    
    /**
     * Mark a stopped-out token for potential recovery scan.
     * Instead of cooldown, we keep watching it for a recovery opportunity.
     */
    private fun markForRecoveryScan(ts: TokenState, lossPct: Double, stopReason: String) {
        normalizePositionScaleIfNeeded(ts)
        val currentPrice = getActualPrice(ts)
        if (currentPrice <= 0) return
        
        // Calculate target price for recovery (breakeven + small profit to cover gas)
        val entryPrice = ts.position.entryPrice
        val targetPrice = entryPrice * 1.02  // V5.9.1451 ‚Äî +2% breakeven after 1% round-trip bot fee (0.5%/side) + gas
        
        val candidate = RecoveryCandidate(
            mint = ts.mint,
            symbol = ts.symbol,
            stopPrice = currentPrice,
            lossPct = lossPct,
            stopTime = System.currentTimeMillis(),
            stopReason = stopReason,
            targetRecoveryPrice = targetPrice
        )
        
        recoveryCandidates[ts.mint] = candidate
        
        ErrorLogger.info("Executor", "üîÑ RECOVERY CANDIDATE: ${ts.symbol} | " +
            "stopped at ${lossPct.toInt()}% | watching for bounce to \$${String.format("%.8f", targetPrice)}")
    }
    
    /**
     * Check if a token is a recovery candidate that has bounced.
     * Returns true if we should re-enter for recovery trade.
     */
    fun checkRecoveryOpportunity(ts: TokenState): Boolean {
        normalizePositionScaleIfNeeded(ts)
        val candidate = recoveryCandidates[ts.mint] ?: return false
        
        // Check if recovery window expired
        val elapsed = System.currentTimeMillis() - candidate.stopTime
        if (elapsed > RECOVERY_SCAN_WINDOW_MS) {
            recoveryCandidates.remove(ts.mint)
            return false
        }
        
        val currentPrice = getActualPrice(ts)
        if (currentPrice <= 0) return false
        
        // Check if price has bounced above target recovery price
        val bounceFromStop = ((currentPrice - candidate.stopPrice) / candidate.stopPrice) * 100
        
        if (bounceFromStop >= 10.0) {  // 10%+ bounce from stop price
            ErrorLogger.info("Executor", "üöÄ RECOVERY BOUNCE: ${ts.symbol} | " +
                "+${bounceFromStop.toInt()}% from stop | ELIGIBLE for recovery entry")
            
            // Check if price approaching target
            if (currentPrice >= candidate.targetRecoveryPrice * 0.95) {  // Within 5% of target
                ErrorLogger.info("Executor", "üí∞ RECOVERY TARGET HIT: ${ts.symbol} | " +
                    "price approaching recovery target | RE-ENTRY opportunity")
                recoveryCandidates.remove(ts.mint)
                return true
            }
        }
        
        return false
    }
    
    /**
     * Get all active recovery candidates for display/logging.
     */
    fun getRecoveryCandidates(): List<RecoveryCandidate> {
        // Clean up expired candidates
        val now = System.currentTimeMillis()
        recoveryCandidates.entries.removeIf { now - it.value.stopTime > RECOVERY_SCAN_WINDOW_MS }
        return recoveryCandidates.values.toList()
    }

    /**
     * CRITICAL FIX: Dynamic token price normalization.
     *
     * Some feeds leak base-unit scaled prices (lamports / token base units) or
     * mis-route large numeric references into the price path. We normalize using:
     *   1. explicit token decimals when available
     *   2. trade-context inference from quote.outAmount vs SOL in
     *   3. fallback heuristics (6 / 9 decimals)
     *
     * This helper is the single source of truth for all price-based logic.
     */
    fun getActualPricePublic(ts: TokenState): Double = getActualPrice(ts)

    /**
     * V5.0.7029 ¬ßTHE_SELL_LEG_NEVER_GOT_THE_6310_UNITS_FIX.
     *
     * Token quantity times a USD mark, expressed in SOL. Null when the SOL/USD
     * rate is not trustworthy enough to convert, and a caller that gets null
     * must REFUSE rather than book the USD figure.
     *
     * THE UNIT CONVENTION, which this file already states twice. Line 1416:
     *
     *     estimatedQty = (solAmount_SOL * solPrice_USDperSOL) / priceUsd_USDperToken
     *                  = qty_tokens
     *
     * so `qtyToken` is tokens, `getActualPrice` is USD PER TOKEN, and
     * `costSol` is SOL. Therefore `qtyToken * actualPrice` is USD. It is not
     * SOL, and it never was.
     *
     * V5.0.6310 found exactly this on the BUY leg ‚Äî it is the "WADDLE 1000x
     * skew", and its comment says the missing factor left quantities "~200x
     * too small". The SELL leg was never given the same treatment, so eight
     * sites across the paper partial and profit-lock paths computed
     *
     *     val sellSol = sellQty * actualPrice          // USD, named SOL
     *
     * and handed it to the canonical ledger as SOL proceeds, subtracted
     * `costSol` from it, and paid it into the paper wallet.
     *
     * WHAT THAT LOOKS LIKE IN THE OPERATOR'S DATA (paper CSV vs 00:47:50):
     *
     *   twelve canonical partials reported     +46.9204 SOL
     *   the same twelve, from their own
     *   entry and exit prices                  + 0.3320 SOL
     *
     *   position   reported gain    price-implied gain    ratio
     *   4bs8G6aR      60,166%              451%           133.4
     *   H1B8yG7d      58,955%              439%           134.3
     *   8AcC8JuK      49,669%              356%           139.5
     *   EsR3JyV6      44,451%              307%           144.8
     *
     * Those ratios are not noise and they are not four different bugs. They
     * are the SOL/USD rate, drifting the way the SOL/USD rate drifts, which
     * is the signature of exactly this conversion being absent.
     *
     * It also silently broke sizing in the other direction: capital recovery
     * divided a SOL target by a USD position value, so it sold ~1/133 of what
     * it needed to recover the entry.
     *
     * NOT A CAP AND NOT A THROTTLE (V5.9.1358). This changes no threshold and
     * no fraction. It converts a currency. A genuine 1000x runner banks 1000x
     * here, in SOL, which is what the ledger has always claimed to be counting.
     */
    /**
     * V5.0.7057 ¬ß1 ‚Äî canonical SOL-denominated gain multiple for a position
     * priced in USD per token.
     *
     * OPERATOR PROOF (WOTF):
     *   soldQty       = 382.963755 tokens
     *   exitPriceUsd  = 0.072585 USD/token
     *   qty x price   = 27.797563 USD
     *   committed as    27.797563 SOL      <- inflated by solUsd (~113.24x)
     *   correct         27.797563 / 113.24 = 0.245475 SOL
     *
     * `qtyToken * markPriceUsd` is a USD notional. `costSol` is SOL. Their
     * ratio is USD/SOL, not a multiple, and it overstates the gain by exactly
     * the SOL price ‚Äî which is how 108 partial rows cleared |1000%| and put
     * ~+599 SOL of unsupported profit into the ledger.
     *
     * Converts the notional to SOL BEFORE dividing, using the same rate and
     * the same 50 USD sanity floor proceedsSol7029 uses.
     *
     * Returns 1.0 (flat, no gain claimed) when the rate is unavailable. A
     * missing SOL price must never be allowed to manufacture a multiple ‚Äî 1.0
     * claims nothing, whereas the old expression claimed a 113x win.
     */
    private fun gainMultipleFromNotional7057(
        qtyToken: Double,
        markPriceUsd: Double,
        costSol: Double,
    ): Double {
        if (!costSol.isFinite() || costSol <= 0.0) return 1.0
        val notionalSol = proceedsSol7029(qtyToken, markPriceUsd)
        if (notionalSol == null || !notionalSol.isFinite() || notionalSol <= 0.0) {
            try {
                PipelineHealthCollector.labelInc("GAIN_MULTIPLE_UNCONVERTIBLE_FLAT_7057")
            } catch (_: Throwable) {}
            return 1.0
        }
        val m = notionalSol / costSol
        return if (m.isFinite() && m > 0.0) m else 1.0
    }

    private fun proceedsSol7029(qtyToken: Double, markPriceUsd: Double): Double? {
        if (!qtyToken.isFinite() || qtyToken <= 0.0) return null
        if (!markPriceUsd.isFinite() || markPriceUsd <= 0.0) return null
        // Same 50 USD floor 6310 uses: below it the feed is cold or dead, and
        // a bad rate would corrupt the conversion as surely as its absence.
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!solUsd.isFinite() || solUsd < 50.0) {
            try {
                PipelineHealthCollector.labelInc("PROCEEDS_SOL_UNCONVERTIBLE_7029")
                ForensicLogger.lifecycle(
                    "PROCEEDS_SOL_UNCONVERTIBLE_7029",
                    "qty=$qtyToken markUsd=$markPriceUsd solUsd=$solUsd " +
                        "action=refuse_rather_than_book_usd_as_sol",
                )
            } catch (_: Throwable) {}
            return null
        }
        // V5.0.7061 ¬ß1 ‚Äî ONE CONVERSION FUNCTION IN THE APP.
        //
        // This used to spell the division out inline. So did the exit path, the
        // mark provider, the partial gate and the 7057 multiple repair ‚Äî five
        // separate copies of tokens x (USD/token) / (USD/SOL), and three of
        // them have shipped it wrong at least once (7029 omitted the divisor,
        // 7057 inverted the denominator, 6496 returned USD labelled SOL). A
        // conversion written five times is a conversion that will be written
        // wrong a sixth. Route it through the named function so a crossing has
        // one place to be caught and one place to be fixed.
        val sol = com.lifecyclebot.engine.truth.EconomicUnitInvariant7061
            .usdToSol(com.lifecyclebot.engine.truth.EconomicUnitInvariant7061
                .proceedsUsd(qtyToken, markPriceUsd), solUsd)
        return if (sol.isFinite() && sol >= 0.0) sol else null
    }
    
    private fun getActualPrice(ts: TokenState): Double {
        // V5.0.6453 ¬ßP0-#7 ‚Äî REAL PRICE CONTRACT. Stamp the freshness
        // guard with whichever provenance we can attribute. Consumers
        // (riskCheck, learners) can then interrogate isFresh() before
        // using the returned mark for PnL/classification/execution.
        val livePriceForStamp = ts.lastPrice.takeIf { it.isFinite() && it > 0.0 }
        if (livePriceForStamp != null) {
            try {
                val src = ts.lastPriceSource.uppercase()
                // V5.0.7001 ‚Äî "came from a fallback PROVIDER" is not the same
                // as "was DERIVED rather than observed".
                //
                // isFresh() refuses DERIVED outright, so this bucket decides
                // whether a mark may justify an exit. It must therefore mean
                // "this number was synthesised" ‚Äî carried forward, estimated,
                // interpolated ‚Äî and nothing else. A real HTTP quote from
                // Birdeye's price endpoint or from the keyless chain is an
                // observation of the market; it is second in PREFERENCE order,
                // which is a completely different property.
                //
                // Until V5.0.6999 this distinction was invisible because the
                // open-position loop stamped every mark "DEXSCREENER_WS"
                // regardless of who answered, so everything landed in WS_LIVE.
                // 6999 started recording the provider that actually replied ‚Äî
                // correct in itself, and it would have routed every
                // BIRDEYE_PRICE_FALLBACK and KEYLESS_* mark straight into
                // DERIVED and had the exit engine refuse marks it had been
                // accepting the day before. Fixing the label exposed the latent
                // bug in the classifier rather than causing it, but shipping
                // one without the other would have been a self-inflicted
                // outage.
                val provenance = when {
                    src.contains("SYNTH") || src.contains("DERIVED") ||
                        src.contains("CARRY") || src.contains("ESTIMATE") || src.contains("PROJECTED") ->
                        com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.DERIVED
                    src.contains("CACHE") -> com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.CACHED
                    src.contains("WS") || src.contains("STREAM") -> com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.WS_LIVE
                    src.isNotBlank() -> com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.REST_LIVE
                    else -> com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.UNKNOWN
                }
                val ageMs = try {
                    val stampAt = ts.lastPriceUpdate
                    if (stampAt > 0L) (System.currentTimeMillis() - stampAt).coerceAtLeast(0L) else 0L
                } catch (_: Throwable) { 0L }
                com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.note(
                    mint = ts.mint,
                    priceUsd = livePriceForStamp,
                    source = provenance,
                    quoteAgeMs = ageMs,
                )
                // ‚îÄ‚îÄ V5.0.7215 ¬ßTHE_CANDLE_BUILDER'S_ONLY_PRODUCER_COULD_NOT_FIRE.
                //
                // Operator directive #5: "repair market-data fallback ‚Äî no hot
                // path wait on Birdeye/CoinGecko/LLM; build candles locally."
                //
                // The builder exists and is correct. LocalCandleSynthesis7055's
                // header names the chain it unblocks: ModeRouter gates
                // BREAKOUT_CONTINUATION on hist.size>=10, REVERSAL_RECLAIM on
                // >=8 and TREND_PULLBACK on >=15, and TREASURY, CASHGEN and
                // DIP_HUNTER are reachable ONLY through the latter two. Those
                // lanes sat at ownerSelected=0 against hundreds of qualified
                // candidates, choked by an empty array rather than by any gate.
                //
                // It had exactly ONE producer ‚Äî BotService:14836 ‚Äî and that
                // producer cannot fire. It derives its price as
                // trustedMarketCapUsd / chainSupply and requires either a
                // pump.fun mint or chainSupply >= 1.0, and the 5.0.7212
                // snapshot says supply is resolved for 32 of 910 observed
                // mints (TOKEN_METRICS_UNVERIFIABLE_NO_ONCHAIN_SUPPLY_7075 =
                // 597). So the candle feed was gated behind a supply lookup
                // that fails 96% of the time ‚Äî a remedy behind a precondition
                // it cannot reach, the same shape as 7148, 7154, 7204 and the
                // 7086 raw-count gate fixed in 7214. Result:
                //   Local candle synth (¬ß7055): ticksBinned=0 candles=0
                //   Keyless OHLCV (¬ß6916): fetches=989 served=0 barsDelivered=0
                //                          rateLimited6944=969
                // 989 fetches, 969 rate-limited, not one bar, and the free
                // keyless fallback built for exactly that never ran.
                //
                // This is where the raw material actually is. 7055's own header
                // says so ‚Äî "it polls a price for every watched mint every
                // cycle (¬ß6452 reported 17,053 quote notes in one session)" ‚Äî
                // and those 17,053 notes are this stamp, three lines up. The
                // observation is already in hand and was being discarded.
                //
                // OBSERVED PRICES ONLY. A DERIVED or CACHED mark is refused,
                // because a synthesised candle built from a carried-forward
                // number would put motion into ts.history that the market never
                // made, and ModeRouter would then classify on it. `note` itself
                // adds the other guards: candidates only (it returns on an open
                // position, which already gets per-tick candles), it stands down
                // the moment a real OHLCV fetch delivers a bar with volume, and
                // it discards single-tick buckets so hist.size cannot cross an
                // archetype threshold on one observation counted twice.
                //
                // Free and keyless: arithmetic on data already fetched. No new
                // provider, no key, no request, no threshold or lane change.
                val observed7215 =
                    provenance == com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.REST_LIVE ||
                        provenance == com.lifecyclebot.engine.truth.QuoteFreshnessGuard6452.Provenance.WS_LIVE
                if (observed7215) {
                    com.lifecyclebot.engine.truth.LocalCandleSynthesis7055.note(
                        ts = ts,
                        priceUsd = livePriceForStamp,
                        mcapUsd = ts.lastMcap,
                    )
                } else {
                    PipelineHealthCollector.labelInc("LOCAL_CANDLE_TICK_REFUSED_NOT_OBSERVED_7215_${provenance.name}")
                }
            } catch (_: Throwable) {}
        }
        // V5.9.744 ‚Äî POOL/SOURCE-AWARE PRICE RESOLVER.
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // The pre-744 implementation returned `ts.lastPrice` as-is, with
        // a >100x rejection for what looked like feed glitches. But the
        // "glitch" was often NOT a glitch ‚Äî it was a REAL price on a
        // DIFFERENT BASIS. A Pump.Fun BC token priced via mcap/1B at
        // entry, then graduated to Raydium and DexScreener WS quoted the
        // REAL pool price (different per-token basis), would look like
        // a 38x jump and either get rejected (V5.9.734 reject path) or
        // logged as phantom +3960% PnL on the journal (pre-734 substitute
        // path). Same mint, same wallet state ‚Äî two prices on two
        // incompatible synthesizer bases.
        //
        // The operator-mandated fix: at buy-time we stamped the entry
        // pricing source / pool / DEX into Position. Here we:
        //   1. Read the current ts.lastPrice + ts.lastPriceSource.
        //   2. If we have an open position and the source has CHANGED
        //      from what was stamped at entry, REBASE entryPrice once
        //      using the candle history pivot ‚Äî this makes the entry
        //      and current quote comparable on the new source's basis.
        //   3. After that single rebase, return ts.lastPrice unmodified.
        //   4. No rejections. No clamping. No simulation. Real data only.
        //
        // The rebase only ever fires ONCE per position (gated by
        // pos.priceBasisRescaled). After it fires, all future ticks on
        // the new source feed normally into PnL.

        // V5.0.7069 ¬ßTHE_IDENTITY_RUNS_BEFORE_ANYTHING_ELSE.
        //
        // Operator: "all token metrics should be stored on arrival with the
        // metrics updating in real time. there should be no imagined gains and
        // bullshit profit."
        //
        // Every price guard below this line ‚Äî the 6052 route lock, the 6895
        // band, the 7017 reconciler, the 7059 ladder ‚Äî argues about how far
        // wrong a mark may be before it is refused. That argument only exists
        // because price and market cap arrive as two unrelated numbers with
        // nothing linking them. TokenMetaCache now stores the link, so it is
        // settled here, arithmetically, before any of them run:
        //
        //     marketCap = price x supply     therefore     price = mcap / supply
        //
        // Supply is captured from the first observation that carries both and
        // is immutable thereafter. A cap that has not moved means a price that
        // has not moved ‚Äî not "within tolerance of not moved". The CARDSc rows
        // (2.47x price against a dead flat $674,010 cap) become arithmetically
        // impossible rather than merely improbable.
        //
        // The repair is written back to ts.lastPrice, not just returned, so no
        // later reader re-derives the wrong number from a field left stale ‚Äî
        // V5.0.7046's lesson, applied at the top of the chain instead of the
        // bottom.
        val metrics7069 = try {
            com.lifecyclebot.engine.truth.TokenMetricsAuthority7069.observe(
                mint = ts.mint,
                symbol = ts.symbol ?: "",
                rawPriceUsd = ts.lastPrice,
                rawMcapUsd = ts.lastMcap,
                source = ts.lastPriceSource.ifBlank { "UNKNOWN" },
            )
        } catch (_: Throwable) { null }
        // V5.0.7087 ¬ßTHE WRITE-BACK IS GONE, AND IT WAS THE POISON.
        //
        // This block used to copy TokenMetricsAuthority7069's substituted price
        // into ts.lastPrice and stamp "+MCAP_IDENTITY_7069" onto the source. That
        // is the tag on every absurd row in the operator's 5.0.7082 report, and
        // the write-back is what made a ONE-TICK bad market cap permanent:
        // ts.lastMcap corrected itself seconds later (¬ß4481 reads entryMcap ==
        // currentMcap == 675220, mcapGain=0.0) but the invented 0.8224 price had
        // already been persisted, so every later reader saw a 1211x no feed had
        // ever reported.
        //
        // 7069 no longer substitutes anything ‚Äî it classifies and returns the
        // provider's price untouched ‚Äî so there is nothing to write back and the
        // block is deleted rather than left inert. TS_LAST_PRICE_REPAIRED_7069
        // is retired with it; its absence from the next report is the acceptance
        // test for this build.

        val livePrice = ts.lastPrice.takeIf { it > 0 && it.isFinite() }
        val pos = ts.position

        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // V5.0.6052 ‚Äî ROUTE-LOCK DOCTRINE (operator mandate)
        // V5.0.6054 ‚Äî SYNTHETIC-SOURCE BYPASS + SELF-HEAL
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // For LIVE positions: reads must respect entryPriceSource. When
        // the tick arriving in ts.lastPriceSource does NOT match the
        // route the position was bought on, the price is advisory only.
        // Prefer the most recent on-route tick (cached on the position).
        // If no fresh on-route tick, return entryPrice so exit gates see
        // a neutral 0% PnL rather than a phantom drop from an alt source.
        //
        // 6054: if entryPriceSource is a SYMBOLIC/RECOVERY label (e.g.
        // LIVE_PROOF_COST_BASIS, RESTORED_LIVE_BASIS_UNKNOWN,
        // WALLET_REHYDRATE_BASIS_UNKNOWN, SYNTH_COST_DIV_QTY, UNKNOWN),
        // it can never match any real tick source ‚Äî the position had no
        // authoritative entry route to lock to. Skip route-lock entirely
        // and let the position ride live ticks. The FIRST real on-route
        // tick self-heals entryPriceSource to that real source, so from
        // then on route-lock protects normally.
        //
        // Paper positions and positions with blank entry source are
        // unaffected ‚Äî they fall through to the existing rebase logic.
        if (livePrice != null && pos.isOpen && !pos.isPaperPosition &&
            pos.entryPriceSource.isNotBlank() &&
            ts.lastPriceSource.isNotBlank() &&
            isRealPriceSource(pos.entryPriceSource)) {
            // V5.0.7166 ‚Äî same BASIS, not same label. A provider rotation
            // inside one basis is not an off-route tick.
            if (sameBasis7166(pos.entryPriceSource, ts.lastPriceSource)) {
                // On-route tick ‚Äî cache as the live source of truth for
                // future off-route reads.
                pos.lastRoutePrice = livePrice
                pos.lastRoutePriceTs = System.currentTimeMillis()
            } else {
                pos.routeLockRejects += 1
                val onRouteAgeMs = System.currentTimeMillis() - pos.lastRoutePriceTs
                if (pos.lastRoutePrice > 0.0 && onRouteAgeMs < ROUTE_LOCK_MAX_STALENESS_MS) {
                    if (pos.routeLockRejects % 20L == 1L) {
                        ErrorLogger.warn("Executor",
                            "üîí ROUTE_LOCK_REJECT ${ts.symbol}: tick source ${ts.lastPriceSource} " +
                            "!= entry ${pos.entryPriceSource} ‚Äî using cached on-route price " +
                            "${pos.lastRoutePrice} (age=${onRouteAgeMs}ms rejects=${pos.routeLockRejects})")
                    }
                    return pos.lastRoutePrice
                } else if (pos.entryPrice > 0.0) {
                    // V5.0.6261 ‚Äî ASYMMETRIC ROUTE-LOCK RELIEF. Prior behavior
                    // returned entryPrice unconditionally when the on-route
                    // cache was stale, silently masking real price movement.
                    // Op-report V5.0.6260 caught VORF stuck at +62% profit
                    // that the WS tick was reporting but the poll-source
                    // route lock hid ‚Äî RAPID_INSTANT_PROFIT_CAPTURE_4301 fired
                    // 10+ times and PARTIAL_BLOCKED_BELOW_BREAKEVEN killed
                    // every attempt because pnl computed as 0. The safety
                    // intent of route-lock is preventing PHANTOM LOSSES
                    // (spoofed downwards ticks from a bad feed) ‚Äî a phantom
                    // PROFIT is far less dangerous because the sell tx itself
                    // proves it. So when the off-route tick shows a PROFIT
                    // (livePrice > entryPrice) we pass it through; losses
                    // still get entryPrice-locked. This unlocks the profit
                    // capture path without opening the phantom-loss surface.
                    if (livePrice > pos.entryPrice) {
                        if (pos.routeLockRejects % 20L == 1L) {
                            ErrorLogger.warn("Executor",
                                "üîì ROUTE_LOCK_PROFIT_PASSTHROUGH_6261 ${ts.symbol}: off-route tick " +
                                "${ts.lastPriceSource} shows +${"%.1f".format((livePrice - pos.entryPrice) / pos.entryPrice * 100.0)}% " +
                                "vs entry ${pos.entryPriceSource}=${pos.entryPrice}; letting profit tick through " +
                                "(rejects=${pos.routeLockRejects})")
                        }
                        try { PipelineHealthCollector.labelInc("ROUTE_LOCK_PROFIT_PASSTHROUGH_6261") } catch (_: Throwable) {}
                        return livePrice
                    }
                    if (pos.routeLockRejects % 20L == 1L) {
                        ErrorLogger.warn("Executor",
                            "üîí ROUTE_LOCK_STALE ${ts.symbol}: tick source ${ts.lastPriceSource} " +
                            "!= entry ${pos.entryPriceSource}, no fresh on-route price " +
                            "(age=${onRouteAgeMs}ms) ‚Äî returning entryPrice ${pos.entryPrice}")
                    }
                    return pos.entryPrice
                }
                // No fresh cache AND no entryPrice ‚Üí nothing safe to return.
                // Fall through; upstream fallbacks will handle it.
            }
        } else if (livePrice != null && pos.isOpen && !pos.isPaperPosition &&
                   ts.lastPriceSource.isNotBlank() &&
                   !isRealPriceSource(pos.entryPriceSource) &&
                   isRealPriceSource(ts.lastPriceSource)) {
            // V5.0.6054 ‚Äî SELF-HEAL. Position was stamped with a symbolic
            // basis (LIVE_PROOF_COST_BASIS / RESTORED / WALLET_REHYDRATE / etc)
            // that no live tick will ever match. First real on-route tick
            // upgrades the position to that source so route-lock can start
            // working normally.
            val old = pos.entryPriceSource
            ts.position = pos.copy(
                entryPriceSource = ts.lastPriceSource,
                lastRoutePrice = livePrice,
                lastRoutePriceTs = System.currentTimeMillis(),
            )
            ErrorLogger.warn("Executor",
                "ü©π ROUTE_LOCK_SELF_HEAL ${ts.symbol}: entryPriceSource $old ‚Üí ${ts.lastPriceSource} " +
                "(first real tick, price=$livePrice)")
        }

        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // V5.0.6895 ¬ßPAPER_HAD_NO_BASIS_PROTECTION_AT_ALL
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // Two gates below combine to leave paper positions completely
        // unguarded against a basis switch:
        //   * route-lock (above) is gated on `!pos.isPaperPosition`
        //   * the V5.9.744 rebase (below) is gated on
        //     `pos.positionId.isBlank()` (V5.0.6636, "immutable canonical
        //     fills never rebase") ‚Äî and EVERY canonical position has a
        //     positionId, so that rebase cannot fire on anything real.
        // The mechanism built to fix pump.fun-BC -> AMM basis switches has
        // therefore been inert, and paper had no fallback.
        //
        // Operator 5.0.6892 is what that costs:
        //   PARTIAL_SELL EaxKqb sol=1.954085 pnl=+1.941522 cost=0.0125
        //     -> +15,532% booked, then the remainder closed at pnl=-0.037
        //   QUALITY  W/L=5/21  mu=+3170.2%
        //   BLUECHIP W/L=7/14  mu=+6537.9%
        // You cannot average +6537% on a 33% win rate unless individual rows
        // are astronomically wrong. Those rows are entry priced on the
        // bonding-curve basis (mcap/1B) against a mark priced on the AMM
        // basis. The gain is arithmetic, not economic ‚Äî and it is banked as
        // real paper cash by partial_25pct, then taught to the tactic
        // switcher, the lane damper, the forward-outcome model and the policy
        // head as evidence that QUALITY prints money. That is why the bot
        // keeps loading QUALITY while its true EV is -28.75%/trade.
        //
        // 6636 is right that a canonical entry price must never be mutated.
        // So this does not rebase anything: when the mark is not comparable
        // to the entry, it REFUSES the mark, exactly as route-lock already
        // does for live. Fresh on-route price if we have one, else entryPrice
        // (neutral 0%), which is the same doctrine live has had since 6052.
        //
        // The discriminator is a SOURCE CHANGE, not magnitude. A genuine
        // 10x-1000x runner on a stable source is untouched ‚Äî that doctrine is
        // never negotiable (V5.9.1358). Only a cross-source jump outside the
        // band is treated as a basis artefact, because a basis switch appears
        // in one discontinuous tick while a real runner climbs through
        // intermediate prices on the same feed.
        //
        // NOTE on direction: V5.0.6261 lets an off-route PROFIT through for
        // LIVE, reasoning that the sell tx itself proves it. Paper has no sell
        // tx ‚Äî a phantom profit there is booked straight into cash and into
        // every learner. In paper a phantom gain is therefore MORE dangerous
        // than a phantom loss, so both directions are refused.
        if (livePrice != null && pos.isOpen && pos.isPaperPosition &&
            pos.entryPrice > 0.0 &&
            pos.entryPriceSource.isNotBlank() && ts.lastPriceSource.isNotBlank() &&
            isRealPriceSource(pos.entryPriceSource) && isRealPriceSource(ts.lastPriceSource)) {
            // V5.0.7166 ‚Äî same BASIS, not same label. 6895's discriminator is
            // a basis switch (bonding curve -> AMM), which is what this asks
            // now; two AMM feeds disagreeing is 7059's job, immediately below.
            if (sameBasis7166(pos.entryPriceSource, ts.lastPriceSource)) {
                // On-basis tick ‚Äî cache it so a later cross-source read has
                // something truthful to fall back to.
                pos.lastRoutePrice = livePrice
                pos.lastRoutePriceTs = System.currentTimeMillis()
                // V5.0.6907 ‚Äî the basis is comparable again, so clear the
                // refusal stamp immediately instead of waiting for it to age
                // out. A position that recovers a matching feed must be
                // priceable on this very tick; the doctrine is "don't disable,
                // re-educate", and the lifetime counter above still preserves
                // the history for the learning exclusion.
                pos.markRefusedAtMs6907 = 0L
                // V5.0.7017 ‚Äî the unpriceable RUN ends here too, so every
                // surface that reads these stamps sees the position recover on
                // this very tick rather than on some later sweep.
                pos.markRefusedSinceMs7017 = 0L
                // V5.0.7017 ¬ßBACKFILL_THE_ENTRY_BASIS_WHILE_WE_ARE_ON_IT.
                //
                // This branch is the one place in the app that KNOWS the tick
                // and the entry share a basis. That makes supply recoverable ‚Äî
                // supply = mcap / price ‚Äî and therefore makes the entry's own
                // market cap recoverable as entryPrice x supply, even for a
                // position that opened without one recorded.
                //
                // Why bother: reconciling a later cross-source mark needs the
                // entry market cap, and a position that never had one is the
                // last remaining way to be permanently unpriceable. Captured
                // once and never overwritten, because the first on-basis
                // observation is the closest one to the entry.
                if (pos.entryMcapBackfilled7017 <= 0.0 &&
                    pos.entryPrice > 0.0 && livePrice > 0.0 && ts.lastMcap > 0.0
                ) {
                    val supply7017 = ts.lastMcap / livePrice
                    val derived7017 = pos.entryPrice * supply7017
                    if (derived7017.isFinite() && derived7017 > 0.0) {
                        pos.entryMcapBackfilled7017 = derived7017
                        try {
                            PipelineHealthCollector.labelInc("ENTRY_MCAP_BACKFILLED_7017")
                        } catch (_: Throwable) {}
                    }
                }
                // V5.0.7059 ¬ßTHE_SAME_SOURCE_TICK_IS_THE_ONE_NOBODY_CHECKS.
                //
                // Everything 6895 and 7017 built ‚Äî the band, the reconciler,
                // the refusal, the repair ‚Äî lives in the `else` arm below, i.e.
                // it runs ONLY when the tick's source differs from the entry's.
                // A tick from the SAME source has, until this build, been
                // cached and served with no cross-check of any kind. Matching
                // provenance was being read as proof of correctness, and it is
                // not: a provider that starts dividing by the wrong supply, or
                // quoting a new pool under its old name, reports a wrong price
                // under exactly the right label.
                //
                // That is the operator's F9CBDp ladder. Four partial rungs,
                // market cap flat at $3,146 across all of them, ~79 SOL of
                // proceeds booked against ~0.089 SOL of basis. Every rung had
                // the market cap that disproves it sitting on the same
                // TokenState, and nothing on this path ever looked at it.
                //
                // So look at it. Market cap is basis-independent ‚Äî supply
                // cancels ‚Äî so entryPrice x (curMcap / entryMcap) places this
                // tick on the position's own basis exactly. When the two
                // corroborate, the raw tick is served untouched, which is every
                // genuine runner: a real move carries price and market cap
                // together and CanonicalMarkResolution7059 stays silent through
                // all 1000x of it. Only when they disagree by more than 3x does
                // the invariant quantity win ‚Äî and then it is a repair, because
                // the two cannot both be describing the same token.
                //
                // Note the backfill immediately above makes this self-
                // consistent on a position's FIRST same-source tick: the entry
                // market cap is derived from this very price, so mcapImplied
                // reduces to livePrice and the comparison is an identity. The
                // check can only bite once the relationship between the two
                // reported numbers has actually broken.
                val curMcap7059 = ts.lastMcap
                val mark7059 = try {
                    com.lifecyclebot.engine.truth.CanonicalMarkResolution7059
                        .resolve(pos, livePrice, curMcap7059)
                } catch (_: Throwable) { null }
                if (mark7059 != null && mark7059.usable &&
                    mark7059.provenance == com.lifecyclebot.engine.truth
                        .CanonicalMarkResolution7059.Provenance.MCAP_RECONCILED
                ) {
                    try {
                        com.lifecyclebot.engine.truth.CanonicalMarkResolution7059
                            .noteCorrection(ts.mint, ts.symbol, mark7059, "getActualPrice/same_source")
                    } catch (_: Throwable) {}
                    // Repair the cached fields as well as the return value.
                    // V5.0.7046 learned this the hard way: 7017 fixed only what
                    // it returned and left ts.lastPrice holding the number it
                    // had just disproved, so every surface reading the field
                    // directly drew the wrong figure with full confidence.
                    pos.lastRoutePrice = mark7059.price
                    pos.lastRoutePriceTs = System.currentTimeMillis()
                    try {
                        ts.lastPrice = mark7059.price
                        ts.lastPriceUpdate = System.currentTimeMillis()
                        // Provider stays as the prefix so
                        // MarkAuthorityIntegrityGate6496's prefix whitelist
                        // still matches (the 7046 regression, fixed in 7048);
                        // the transform is appended, and substringBefore keeps
                        // it idempotent across repeated repairs.
                        val base7059 = ts.lastPriceSource
                            .substringBefore("+MCAP_RECONCILED_7059")
                            .takeIf { it.isNotBlank() } ?: "RECONCILED"
                        ts.lastPriceSource = "$base7059+MCAP_RECONCILED_7059"
                        PipelineHealthCollector.labelInc("TS_LAST_PRICE_REPAIRED_7059")
                    } catch (_: Throwable) {}
                    return mark7059.price
                }
            } else {
                val ratio6895 = livePrice / pos.entryPrice
                val outOfBand6895 = !ratio6895.isFinite() ||
                    ratio6895 > CROSS_BASIS_MAX_RATIO_6895 ||
                    ratio6895 < (1.0 / CROSS_BASIS_MAX_RATIO_6895)
                if (outOfBand6895) {
                    // V5.0.7017 ¬ßNO_POSITION_SHOULD_EVER_GO_UNPRICEABLE.
                    //
                    // Operator: "no position should ever go unpriceable. ever."
                    //
                    // Before refusing, TAKE THE MEASUREMENT. Price-per-token is
                    // basis-dependent, which is why the tick above is
                    // incomparable ‚Äî but market cap is not. Supply cancels, so
                    // a tick from any source that reports a market cap can be
                    // placed exactly on this position's own entry basis:
                    //
                    //     entryPrice x (currentMcap / entryMcap)
                    //
                    // No supply, no decimals, no knowledge of which venue
                    // either side quotes. Exact, not approximate, and immune to
                    // the whole class of defect that produced this refusal ‚Äî
                    // V5.0.7016's pump.fun bug corrupted the derived price by
                    // 10^decimals while `usd_market_cap` stayed correct, so
                    // this path would have kept every affected position
                    // priceable straight through it.
                    //
                    // Only when BOTH market caps are unavailable is the mark
                    // genuinely unknown, and only then do we fall through to
                    // the refusal below.
                    val curMcap7017 = ts.lastMcap
                    val reconciled7017 = try {
                        com.lifecyclebot.engine.truth.MarkBasisReconciler7017
                            .reconcileForPosition(pos, curMcap7017)
                    } catch (_: Throwable) { 0.0 }
                    if (reconciled7017 > 0.0 && reconciled7017.isFinite()) {
                        try {
                            com.lifecyclebot.engine.truth.MarkBasisReconciler7017.note(
                                mint = ts.mint, symbol = ts.symbol,
                                entryPrice = pos.entryPrice,
                                entryMcap = com.lifecyclebot.engine.truth.MarkBasisReconciler7017.entryMcapOf(pos),
                                currentMcap = curMcap7017,
                                rawTick = livePrice, reconciledPx = reconciled7017,
                                refusals = pos.crossBasisRefusals6895 + 1,
                            )
                        } catch (_: Throwable) {}
                        // The position is PRICEABLE, so nothing about it is
                        // refused: no refusal stamp, no refusal counter, and
                        // the reconciled mark is cached as the on-route price
                        // so a later gap has something truthful to hold.
                        // Clearing the 6907 stamps is what lets every surface
                        // that reads them ‚Äî OpenPnlSanity, the exit feed ‚Äî
                        // treat this position as priced again on this tick.
                        pos.lastRoutePrice = reconciled7017
                        pos.lastRoutePriceTs = System.currentTimeMillis()
                        pos.markRefusedAtMs6907 = 0L
                        pos.markRefusedSinceMs7017 = 0L
                        // V5.0.7046 ¬ßONE_PRICE_PER_TOKEN ‚Äî repair the field too.
                        //
                        // 7017 fixed this function's RETURN VALUE and pos
                        // .lastRoutePrice and stopped there, leaving ts.lastPrice
                        // holding the tick we just proved incomparable. Every
                        // surface that reads the field directly ‚Äî the Open
                        // Positions rows above all ‚Äî then drew that number with
                        // full confidence. The operator's 5.0.7044 screen:
                        //
                        //   WOTF +99785.5%   entry 0.0101505  rawTick 10.138878
                        //   header  18.706‚óé at risk  ¬∑  +5547.0532‚óé unrealized
                        //
                        // against a canonical unrealized of 0.6795 SOL. The tick
                        // is curMcap/1e6 while the entry was priced mcap/1e9 ‚Äî
                        // exactly 1000x ‚Äî and CanonicalCapitalAuthority6450 was
                        // right the whole time because 6604 quarantines the mark
                        // at cost basis (2921 times in a 385s session).
                        //
                        // So before 7017 these positions read "unpriceable"; after
                        // it they read confidently and enormously wrong. That is
                        // worse, and it is the reason this write exists. Inside
                        // this branch the raw tick is ALREADY PROVEN out of band
                        // by 6895, so replacing it with the on-basis reconstruction
                        // is strictly a repair ‚Äî never a guess, and never applied
                        // to a tick that passed.
                        //
                        // Source-stamped like every other writer (V5.9.744), so
                        // the provenance says which path produced the number
                        // rather than inheriting the rejected tick's label. A
                        // later poll may overwrite this with another bad tick;
                        // this runs again and repairs it again, and the counter
                        // says how often, which is the measurement that tells us
                        // whether the upstream unit bug is still live.
                        try {
                            ts.lastPrice = reconciled7017
                            ts.lastPriceUpdate = System.currentTimeMillis()
                            // V5.0.7048 ‚Äî KEEP THE PROVIDER IN THE PROVENANCE.
                            //
                            // 7046 stamped a flat "MARK_BASIS_RECONCILED_7046".
                            // MarkAuthorityIntegrityGate6496 canonicalises the
                            // source by PREFIX (line 176) and whitelists seven
                            // provider names, so a flat tag canonicalised to
                            // itself, matched nothing, and every repaired mark
                            // came back SOURCE_NOT_WHITELISTED ‚Äî
                            // MARK_AUTHORITY_GATE_BLOCKED_6496 fired 94 times in
                            // a 241s session with provenance=AUTHORITATIVE and a
                            // real pool. I fixed the number and broke its
                            // paperwork.
                            //
                            // The provider is still the honest answer to "where
                            // did this come from": the market cap driving the
                            // reconstruction is that provider's own reading. So
                            // keep its name as the prefix and append the
                            // transform, which satisfies the prefix whitelist
                            // AND leaves the rebase visible. The substringBefore
                            // keeps it idempotent across repeated repairs.
                            val base7048 = ts.lastPriceSource
                                .substringBefore("+RECONCILED_7046")
                                .takeIf { it.isNotBlank() } ?: "RECONCILED"
                            ts.lastPriceSource = "$base7048+RECONCILED_7046"
                            PipelineHealthCollector.labelInc("TS_LAST_PRICE_REPAIRED_7046")
                        } catch (_: Throwable) {}
                        return reconciled7017
                    }

                    val onRouteAge6895 = System.currentTimeMillis() - pos.lastRoutePriceTs
                    val fallback6895 = if (pos.lastRoutePrice > 0.0 && onRouteAge6895 < ROUTE_LOCK_MAX_STALENESS_MS) {
                        pos.lastRoutePrice
                    } else pos.entryPrice
                    pos.crossBasisRefusals6895 += 1
                    // V5.0.6907 ‚Äî publish the refusal as a fact every other
                    // surface can read. Until now this decision lived only
                    // inside this function: the engine served entryPrice and
                    // correctly saw 0%, while OpenPnlSanity re-asked the same
                    // question with its own 51x band, approved the raw tick,
                    // and painted +4290% on the position card. One decision,
                    // one writer, one timestamp.
                    pos.markRefusedAtMs6907 = System.currentTimeMillis()
                    // V5.0.7017 ‚Äî open the run on the FIRST refusal only, so
                    // the stamp measures how long this position has been
                    // unpriceable rather than how recently it was refused.
                    if (pos.markRefusedSinceMs7017 <= 0L) {
                        pos.markRefusedSinceMs7017 = pos.markRefusedAtMs6907
                    }
                    if (pos.crossBasisRefusals6895 % 20L == 1L) {
                        try {
                            PipelineHealthCollector.labelInc("PAPER_CROSS_BASIS_MARK_REFUSED_6895")
                            ForensicLogger.lifecycle(
                                "PAPER_CROSS_BASIS_MARK_REFUSED_6895",
                                "mint=${ts.mint.take(10)} sym=${ts.symbol} " +
                                    "entrySrc=${pos.entryPriceSource} tickSrc=${ts.lastPriceSource} " +
                                    "entry=${pos.entryPrice} tick=$livePrice ratio=${"%.4g".format(ratio6895)} " +
                                    "band=$CROSS_BASIS_MAX_RATIO_6895 served=$fallback6895 " +
                                    "refusals=${pos.crossBasisRefusals6895} " +
                                    "action=refuse_incomparable_mark_do_not_book_or_learn",
                            )
                        } catch (_: Throwable) {}
                    }
                    return fallback6895
                }
            }
        }

        // Detect source-basis switch on an open position.
        // Three conditions must ALL hold:
        //   (a) we have a live price,
        //   (b) the position is open with a real entryPrice,
        //   (c) the source at entry differs from current source, AND
        //   (d) we haven't already rebased once.
        // V5.9.747 ‚Äî LIVE POSITIONS NEVER REBASE.
        // Operator report: 'live buys have gone weird. very messy.'
        // Root cause: V5.9.744 rebase fires on ANY position when the price
        // source changes from entry source. But LIVE positions have a real
        // on-chain entry price from the Jupiter swap (SOL paid / tokens
        // received) ‚Äî that's ground truth. Rebasing that based on off-chain
        // mcap pivots produces a fictional entry price, which then breaks
        // displayed PnL, SL/TP triggers, partial-sell levels, and exit gates
        // for the entire life of the live position. The rebase was designed
        // for PAPER positions where the entry was a synthesized quote (and
        // therefore vulnerable to basis switches at graduation). Gate the
        // rebase block on isPaperPosition so live entries stay sacred.
        if (livePrice != null && pos.isOpen && pos.entryPrice > 0 &&
            pos.isPaperPosition &&  // V5.9.747 ‚Äî live positions never rebase
            pos.positionId.isBlank() && // V5.0.6636 ‚Äî immutable canonical fills never rebase in a shadow store
            !pos.priceBasisRescaled &&
            pos.entryPriceSource.isNotBlank() &&
            ts.lastPriceSource.isNotBlank() &&
            pos.entryPriceSource != ts.lastPriceSource) {

            // The ratio between the entry's basis price and the new source's
            // price ON THE SAME MOMENT (we approximate with the first cross-
            // source tick we see) is the multiplicative rescale factor.
            // Concretely: if entry was on PUMP_FUN_BC at $0.000003 (mcap/1B)
            // and DexScreener WS just reported $0.00012 for the same token at
            // graduation moment, the BC-basis equivalent of the new price
            // is what we want entryPrice scaled INTO so PnL = livePrice/scaled-
            // entryPrice reflects real percent change post-graduation.
            //
            // Without a synchronized cross-source quote (which we don't have),
            // the most honest default is to NOT rescale on the very first
            // post-switch tick and instead use ts.history's most recent
            // candle on the NEW source as the rescale pivot. Pump.Fun
            // graduation always produces a fresh Raydium pool price; that
            // first new-source candle is the pivot.
            val newSourceCandle = ts.history.lastOrNull { c ->
                c.priceUsd > 0 && c.priceUsd.isFinite()
            }
            if (newSourceCandle != null && newSourceCandle.priceUsd > 0) {
                // Rescale factor: how much did the basis itself change?
                // Best proxy available: the ratio of MCAP at entry to MCAP now,
                // which is invariant across supply-assumption changes (mcap is
                // dollar-denominated, not per-token).
                val entryMcap = pos.entryMcap.takeIf { it > 0 }
                val currentMcap = ts.lastMcap.takeIf { it > 0 } ?: newSourceCandle.marketCap.takeIf { it > 0 }
                if (entryMcap != null && currentMcap != null && currentMcap > 0) {
                    // Equivalent entry price on the NEW basis is whatever
                    // entryPrice would have been if we had measured it via
                    // the new source's per-token price at the same mcap level.
                    // = livePrice * (entryMcap / currentMcap)
                    val rebasedEntry = livePrice * (entryMcap / currentMcap)
                    if (rebasedEntry > 0 && rebasedEntry.isFinite()) {
                        val factor = rebasedEntry / pos.entryPrice
                        if (factor.isFinite() && factor > 0 && factor < 1e9) {
                            // V5.0.6500 ‚Äî SOURCE FIX: invariant preservation.
                            // Pre-6500 rebase multiplied entryPrice by
                            // `factor` but LEFT qtyToken unchanged. That
                            // silently inflates the qty √ó entryPrice
                            // notional by exactly `factor` ‚Äî the source
                            // of compassSOL 6710 √ó $112 = $752K phantom
                            // equity on a $5 paper buy. Correct behaviour:
                            // scale qtyToken inversely so
                            //   qty' √ó entryPrice' = (qty/factor) √ó (entryPrice√ófactor)
                            //                      = qty √ó entryPrice
                            //                      = original notional  ‚úì
                            // Additionally cap the factor at 100√ó ‚Äî any
                            // rebase larger than that comes from a
                            // corrupted quote (pump.fun BC mcap/1B basis
                            // at buy vs Raydium USD basis after grad).
                            // Those positions are quarantined instead
                            // of rebased.
                            val CAP_FACTOR = 100.0
                            val factorOutOfBand = factor > CAP_FACTOR || factor < (1.0 / CAP_FACTOR)
                            if (factorOutOfBand) {
                                try {
                                    ForensicLogger.lifecycle(
                                        "PRICE_BASIS_REBASE_REJECTED_INVARIANT_6500",
                                        "mint=${ts.mint.take(10)} sym=${ts.symbol} factor=${"%.4g".format(factor)} cap=$CAP_FACTOR action=quarantine",
                                    )
                                    PipelineHealthCollector.labelInc("PRICE_BASIS_REBASE_REJECTED_INVARIANT_6500")
                                } catch (_: Throwable) {}
                                try {
                                    com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500
                                        .markInvariantBroken(ts.mint, "REBASE_FACTOR_OUT_OF_BAND_$factor")
                                } catch (_: Throwable) {}
                                // Mark as rescaled so we don't loop; but
                                // do NOT actually rebase.
                                ts.position = pos.copy(priceBasisRescaled = true)
                            } else {
                                val newQty = pos.qtyToken / factor
                                ErrorLogger.warn("Executor",
                                    "üîÑ PRICE_BASIS_REBASE ${ts.symbol}: source ${pos.entryPriceSource}‚Üí${ts.lastPriceSource} " +
                                    "| entry ${pos.entryPrice}‚Üí${rebasedEntry} (√ó${"%.4g".format(factor)}) " +
                                    "| qty ${pos.qtyToken}‚Üí${newQty} (√∑factor, invariant preserved) " +
                                    "| mcap ${entryMcap}‚Üí${currentMcap} | live ${livePrice}")
                                ts.position = pos.copy(
                                    entryPrice = rebasedEntry,
                                    qtyToken = newQty,
                                    highestPrice = pos.highestPrice * factor,
                                    lowestPrice = if (pos.lowestPrice > 0) pos.lowestPrice * factor else 0.0,
                                    lastTopUpPrice = if (pos.lastTopUpPrice > 0) pos.lastTopUpPrice * factor else 0.0,
                                    priceBasisRescaled = true,
                                    priceBasisRescaleFactor = factor,
                                )
                            }
                        }
                    }
                } else {
                    // No mcap data ‚Üí mark rescaled anyway so we don't loop
                    // every tick logging the same warning. The position will
                    // measure PnL on the new source's raw scale; any drift
                    // is bounded by the position's own size-cap from sizer.
                    ErrorLogger.warn("Executor",
                        "üîÑ PRICE_BASIS_REBASE ${ts.symbol}: source ${pos.entryPriceSource}‚Üí${ts.lastPriceSource} " +
                        "| no mcap pivot available, accepting new-source price as-is (PnL may show one-time step)")
                    ts.position = pos.copy(priceBasisRescaled = true)
                }
            }
        }

        // After (possibly) rebasing, return the live price directly.
        if (livePrice != null) return livePrice

        // Fallback 1: latest candle price.
        val candlePrice = ts.history.lastOrNull()?.priceUsd?.takeIf { it > 0 && it.isFinite() }
        if (candlePrice != null) return candlePrice

        // Fallback 2: entry price (so callers see SOMETHING non-zero rather
        // than treating the eval as failed). Exit gates handle stale price
        // via their own time-since-update checks.
        val entryPrice = ts.position.entryPrice.takeIf { it > 0 && it.isFinite() }
        if (entryPrice != null) return entryPrice

        return 0.0
    }

    /**
     * One-shot self-heal for legacy positions whose stored entry/high/low prices
     * were written before the scaling fix. Without this, current normalized price
     * vs legacy raw entry price creates fake million-percent PnL swings.
     */
    private fun normalizePositionScaleIfNeeded(ts: TokenState) {
        val pos = ts.position
        if (!pos.isOpen) return
        // V5.0.6636 ‚Äî this heuristic predates canonical raw lots. Mutating a
        // committed position's entry price without changing canonical truth
        // manufactures the projection mismatch shown in the operator's six
        // INVARIANT_BROKEN_6500 screenshots. Canonical positions are immutable;
        // only unlinked legacy projections may use this migration helper.
        if (pos.positionId.isNotBlank()) return

        val currentPrice = getActualPrice(ts)
        val entryPrice = pos.entryPrice
        if (currentPrice <= 0.0 || entryPrice <= 0.0 || !currentPrice.isFinite() || !entryPrice.isFinite()) return

        val ratio = entryPrice / currentPrice
        val absRatio = kotlin.math.abs(ratio)
        if (absRatio < 100.0) return

        val scale = detectPowerOfTenScale(absRatio)
        if (scale <= 1.0) return

        val divideStored = ratio > 1.0
        fun fix(v: Double): Double {
            if (v <= 0.0 || !v.isFinite()) return v
            return if (divideStored) v / scale else v * scale
        }

        ts.position = pos.copy(
            entryPrice = fix(pos.entryPrice),
            highestPrice = fix(pos.highestPrice),
            lowestPrice = fix(pos.lowestPrice),
            lastTopUpPrice = fix(pos.lastTopUpPrice),
        )

        if (normalizedPositionScale.putIfAbsent(ts.mint, true) == null) {
            val action = if (divideStored) "√∑" else "√ó"
            ErrorLogger.warn(
                "Executor",
                "üõ† PRICE SCALE HEAL: ${ts.symbol} legacy position normalized ($action${scale.toLong()})"
            )
        }
    }

    /**
     * Background monitor helper. Uses normalized prices for recovery / rug checks
     * even when the rest of the strategy loop has not touched the token yet.
     */
    fun updatePositions(activeTokens: List<TokenState>) {
        val now = System.currentTimeMillis()

        activeTokens.forEach { ts ->
            normalizePositionScaleIfNeeded(ts)

            val currentPrice = getActualPrice(ts)
            val entryPrice = ts.position.entryPrice
            if (currentPrice <= 0.0 || entryPrice <= 0.0) return@forEach

            val pnlVerdict6038 = OpenPnlSanity.inspectPosition(ts.position, currentPrice, "Executor.active_hard_floor_6038/${ts.symbol}/${ts.mint.take(8)}", emit = true, mint = ts.mint)
            if (!pnlVerdict6038.ok) return@forEach
            val pnlPct = pnlVerdict6038.pnlPct
            if (pnlPct <= -33.0 && !RuggedContracts.isBlacklisted(ts.mint)) {
                ErrorLogger.warn("Executor", "üö® RUG/STOP LOSS: ${ts.symbol} at ${pnlPct.toInt()}%")
                markForRecoveryScan(ts, pnlPct, "hard_floor")
                RuggedContracts.add(ts.mint, ts.symbol, pnlPct)
            }
        }

        recoveryCandidates.entries.removeIf { now - it.value.stopTime > RECOVERY_SCAN_WINDOW_MS }
    }

    private val normalizedPositionScale = ConcurrentHashMap<String, Boolean>()
    // V5.7.8: Track zero-balance sell retries ‚Äî force close after 5 attempts
    private val zeroBalanceRetries = ConcurrentHashMap<String, Int>()

    // V5.0.4091 ‚Äî SELL-SIDE SLIPPAGE ABORT (operator P0: rounds out the SL
    // safety story alongside V5.0.4090 entry liquidity floor). When Jupiter
    // returns a sell quote with priceImpactPct > SELL_SLIPPAGE_ABORT_PCT
    // (e.g. >25% impact on a thin/dying pool), defer the sell for one tick
    // and re-quote rather than broadcasting into a death-spiral. Per-mint
    // counter caps the retry at SELL_SLIPPAGE_ABORT_MAX so we never stick a
    // position permanently ‚Äî if the pool stays thin after 2 retries, force-
    // proceed and accept whatever fill the pool can give (better to realize
    // the loss than carry a permanently-stuck position). Emergency exits
    // (RUG/HONEYPOT/SHUTDOWN/EMERGENCY/MAX_HOLD/STALE) bypass the abort
    // entirely ‚Äî those MUST broadcast at any cost.
    private val SELL_SLIPPAGE_ABORT_PCT = 25.0
    private val SELL_SLIPPAGE_ABORT_MAX = 2
    private val sellSlippageAborts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun isEmergencySellReason(reason: String): Boolean {
        val r = reason.uppercase()
        val emergencyKeys = listOf("RUG", "HONEYPOT", "EMERGENCY", "SHUTDOWN", "PHANTOM", "STALE", "MAX_HOLD", "MUST_SELL", "CATASTROPHIC")
        return emergencyKeys.any { r.contains(it) }
    }

    private fun buildPriceVariants(rawPrice: Double, decimals: Int): List<Double> {
        if (!rawPrice.isFinite() || rawPrice <= 0.0) return emptyList()

        val variants = linkedSetOf<Double>()
        variants += rawPrice

        listOf(decimals, 6, 9).distinct().forEach { d ->
            if (d > 0) {
                val scaled = rawPrice / 10.0.pow(d.toDouble())
                if (scaled.isFinite() && scaled > 0.0) variants += scaled
            }
        }
        return variants.toList()
    }

    private fun scorePriceCandidate(candidate: Double, references: List<Double>, mcapUsd: Double): Double {
        if (!candidate.isFinite() || candidate <= 0.0) return Double.MAX_VALUE

        var score = 0.0

        if (candidate > 1_000_000.0) score += 500.0
        if (candidate < 1e-18) score += 500.0

        if (references.isNotEmpty()) {
            val minDistance = references
                .filter { it.isFinite() && it > 0.0 }
                .minOfOrNull { kotlin.math.abs(log10(candidate / it)) }
                ?: 0.0
            score += minDistance
        } else if (candidate > 10_000.0) {
            score += 5.0
        }

        if (mcapUsd in 1.0..30_000_000.0) {
            when {
                candidate < 1.0 -> score -= 0.25
                candidate > 1_000.0 -> score += 2.0
            }
        }

        if (mcapUsd > 0.0 && candidate >= mcapUsd * 0.25) {
            score += 50.0
        }

        return score
    }

    private fun detectPowerOfTenScale(value: Double): Double {
        if (!value.isFinite() || value <= 0.0) return 1.0

        val exponents = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12)
        var bestScale = 1.0
        var bestDistance = Double.MAX_VALUE

        for (exp in exponents) {
            val scale = 10.0.pow(exp.toDouble())
            val distance = kotlin.math.abs(log10(value / scale))
            if (distance < bestDistance) {
                bestDistance = distance
                bestScale = scale
            }
        }

        return if (bestDistance <= 0.25) bestScale else 1.0
    }

    private fun getTokenDecimals(ts: TokenState): Int {
        // V5.0.6311 ‚Äî wallet-verified decimals FIRST. Set by
        // promoteVerifiedLiveBuy once the RPC returns the token account's
        // authoritative decimals. This is the only source that is ever
        // trustworthy on a fresh mint; every other path is heuristic.
        // V5.0.6670 ¬ßDECIMAL_SEED_AUTHORITY_ON_WALLET_HIT ‚Äî every path that
        // reads verified decimals must also seed MintDecimalsAuthority6392
        // so BUY and SELL and MARK all converge on one integer scale.
        walletDecimalsByMint6311[ts.mint]?.takeIf { it >= 0 }?.let {
            try { com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.resolveAndCache(ts.mint, it.coerceAtMost(18)) } catch (_: Throwable) {}
            return it
        }

        // V5.0.6330 ‚Äî WADDLE root repair. Prefer the CanonicalTokenMap
        // decimals field (populated from token metadata / RPC on
        // hydration) over any reflection heuristic. This is what the
        // Solana chain says the mint's decimals are, not an inference
        // from a heuristic float division. Fixes the case where the
        // wallet cache hasn't populated yet on the very first sell/mark
        // and the executor otherwise silently fell into inferUiScaleFromTrade.
        ts.tokenMap.decimals?.takeIf { it >= 0 }?.let {
            // Warm the wallet cache too so downstream callers hit it
            // immediately rather than repeating this lookup.
            walletDecimalsByMint6311.putIfAbsent(ts.mint, it)
            try { com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.resolveAndCache(ts.mint, it.coerceAtMost(18)) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("TOKEN_DECIMALS_FROM_TOKEN_MAP_6330") } catch (_: Throwable) {}
            return it
        }

        val reflected = reflectInt(ts, "decimals", "tokenDecimals", "baseDecimals", "mintDecimals")
            ?: reflectInt(ts.meta, "decimals", "tokenDecimals", "baseDecimals")
            ?: reflectInt(ts.position, "decimals", "tokenDecimals")

        // V5.0.6670 ¬ßDECIMAL_SKEW_ROOT_AUTHORITY ‚Äî operator dump Feb 2026:
        //   QTY_DECIMAL_SKEW_6309: 7GCihg buyQty=97.49 sellQty=9.826e+10
        //   kZbqhb buyQty=2073 sellQty=53.36   (skew learning quarantine=74)
        //
        //   BUY vs SELL journal legs called getTokenDecimals() ‚Üí -1 at
        //   different times: BUY hit while ts.tokenMap.decimals was
        //   populated (say 6) so the qty was 97.49 tokens; by the time the
        //   SELL leg ran the reflection field had rotated out (fresh
        //   TokenState after a re-hydration) and getTokenDecimals returned
        //   -1, so rawTokenAmountToUiAmount fell into
        //   inferUiScaleFromTrade with a different priceUsd ‚Üí picked a
        //   different scale ‚Üí sell qty landed at 9.826e+10. Same mint,
        //   two decimal interpretations ‚Üí phantom +0.368 SOL profits and
        //   +74 rows in the skew-learning quarantine.
        //
        //   MintDecimalsAuthority6392 already caches chain-resolved
        //   decimals across the process lifetime ‚Äî that's the source
        //   DecimalIntegrityAuthority6405 reads first in its strict
        //   resolver, so BUY-side wallet verification writes there and
        //   SELL-side sees the same value. Consult it before falling
        //   to the inferUiScaleFromTrade heuristic. Warms the wallet
        //   cache and the ts.tokenMap.decimals too so subsequent BUY /
        //   SELL / MARK all converge on the same integer scale.
        val authority6670 = if (reflected == null) {
            try { com.lifecyclebot.engine.truth.MintDecimalsAuthority6392.get(ts.mint)?.takeIf { it in 0..24 } }
            catch (_: Throwable) { null }
        } else null
        if (authority6670 != null) {
            walletDecimalsByMint6311.putIfAbsent(ts.mint, authority6670)
            try { ts.tokenMap.decimals = authority6670 } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("TOKEN_DECIMALS_FROM_AUTHORITY_6670") } catch (_: Throwable) {}
            return authority6670
        }

        val resolved = reflected?.coerceAtLeast(0) ?: -1
        if (resolved < 0) {
            // No authoritative source found. Flag it so the WIN/LOSS
            // labeller downstream can classify this row as
            // PENDING_RECONCILIATION rather than training the brain on
            // a heuristic-derived qty. The heuristic still runs so the
            // buy can proceed on estimate, but learning is suppressed.
            try { PipelineHealthCollector.labelInc("TOKEN_DECIMALS_UNKNOWN_HEURISTIC_6330") } catch (_: Throwable) {}
        }
        return resolved
    }

    // V5.0.6311 ‚Äî authoritative mint-decimals cache, populated by
    // promoteVerifiedLiveBuy from the wallet's token account. Persists for
    // the bot's lifetime so top-ups / partial sells / subsequent BUY
    // reconciles never fall to the inferUiScaleFromTrade heuristic even
    // when ts.meta / ts.position lose the reflection field.
    private val walletDecimalsByMint6311 = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun rawTokenAmountToUiAmount(
        ts: TokenState,
        rawAmount: Long,
        solAmount: Double = 0.0,
        priceUsd: Double = 0.0,
        explicitDecimals: Int? = null,
    ): Double {
        if (rawAmount <= 0L) return 0.0

        val scale = when {
            explicitDecimals != null && explicitDecimals >= 0 -> 10.0.pow(explicitDecimals.toDouble())
            getTokenDecimals(ts) >= 0 -> 10.0.pow(getTokenDecimals(ts).toDouble())
            solAmount > 0.0 && priceUsd > 0.0 -> inferUiScaleFromTrade(rawAmount, solAmount, priceUsd)
            else -> tokenScale(rawAmount)
        }

        return rawAmount.toDouble() / scale.coerceAtLeast(1.0)
    }

    private fun inferUiScaleFromTrade(rawAmount: Long, solAmount: Double, priceUsd: Double): Double {
        if (rawAmount <= 0L || solAmount <= 0.0 || priceUsd <= 0.0) return 1_000_000_000.0

        // V5.0.6310 ‚Äî UNITS FIX (root cause of the "WADDLE" 1000√ó skew).
        // Historical impl computed `estimatedQty = solAmount / priceUsd`, but
        // `solAmount` is in SOL while `priceUsd` is USD per token. That left
        // an implicit ~$150 SOL/USD factor missing, so `estimatedQty` was
        // ~200√ó too small and the log10-nearest candidate scale landed
        // 100√ó‚Äì1000√ó off the real mint decimals. BUY leg then journaled
        // qty at the wrong scale; wallet-verify later promoted the correct
        // qty into ts.position, so the SELL leg journaled the correct qty
        // and the pipeline health audit saw a 10¬≥√ó BUY‚ÜîSELL divergence,
        // which the AI learning brain read as fake "10√ó runner" wins.
        //
        // Fix: multiply through by the last-known SOL price so the units
        // resolve correctly:
        //   estimatedQty = (solAmount_SOL * solPrice_USDperSOL) / priceUsd_USDperToken
        //                = solNotionalUsd_USD / priceUsd_USDperToken
        //                = qty_tokens          ‚úî
        //
        // Safety fallback: if SOL price is unknown (< 50 USD, dead feed),
        // the old (buggy) formula is preserved to avoid a regression that
        // could crash-loop on cold boot before WalletManager warms up.
        // A canary log fires whenever the fallback is exercised so the
        // operator can grep CI/logcat and see it never happens in flight.
        val solPriceUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val estimatedQty = if (solPriceUsd >= 50.0) {
            (solAmount * solPriceUsd) / priceUsd
        } else {
            try {
                ErrorLogger.warn(
                    "Executor",
                    "‚ö† INFER_UI_SCALE_SOLPRICE_FALLBACK_6310 solPx=$solPriceUsd raw=$rawAmount solAmt=$solAmount pxUsd=$priceUsd ‚Äî using pre-6310 buggy formula until SOL price warms"
                )
                PipelineHealthCollector.labelInc("INFER_UI_SCALE_SOLPRICE_FALLBACK_6310")
            } catch (_: Throwable) {}
            solAmount / priceUsd
        }
        if (!estimatedQty.isFinite() || estimatedQty <= 0.0) return 1_000_000_000.0

        val observedScale = rawAmount.toDouble() / estimatedQty
        val candidates = listOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0, 10_000_000.0, 100_000_000.0, 1_000_000_000.0, 1_000_000_000_000.0)

        return candidates.minByOrNull { kotlin.math.abs(log10(observedScale / it)) } ?: 1_000_000_000.0
    }

    private fun resolveSellUnits(ts: TokenState, qty: Double, wallet: SolanaWallet? = null): Long {
        return resolveSellUnitsForMint(
            mint = ts.mint,
            qty = qty,
            wallet = wallet,
            fallbackDecimals = getTokenDecimals(ts).takeIf { it >= 0 }
        )
    }

    /**
     * V5.9.491 ‚Äî ON-CHAIN REHYDRATE (last-resort).
     *
     * Operator: 'wrong the tokens are in my wallets' ‚Äî the rapid-stop
     * monitor saw isOpen=true at filter time, but by the time
     * requestSell ran, ts.position was empty AND none of the sub-trader
     * stores (V5.9.475) had the mint. Result: ABORT ‚Äî pos.isOpen=false
     * (qty=0.0) ‚Ä¶ with the actual tokens still sitting in the wallet.
     *
     * The original V5.9.475 rehydration only scanned 5 known sub-trader
     * stores. Anything bought via a different path (Markets lane, manual
     * deposit, an old build, an external wallet drop) is invisible to it.
     *
     * This helper is the FINAL safety net: if all sub-trader stores miss,
     * we read the live wallet's `getTokenAccountsWithDecimals()` for the
     * mint. If a non-zero balance exists, we stamp ts.position with that
     * qty so the sell pipeline can dump it. Entry price defaults to the
     * current observed price (so PnL math books at break-even ‚Äî not
     * accurate, but the goal is GET THE BAG OUT, not journal precision).
     *
     * Returns true if a wallet-rehydrate occurred.
     */
    private fun rehydratePositionFromWallet(ts: TokenState, wallet: SolanaWallet?): Boolean {
        if (ts.position.qtyToken > 0.0) return false
        if (wallet == null) return false
        val mint = ts.mint
        val accounts = try {
            wallet.getTokenAccountsWithDecimalsBounded()
        } catch (e: Exception) {
            ErrorLogger.warn("Executor", "rehydrateFromWallet: getTokenAccounts failed: ${e.message}")
            return false
        }
        val entry = accounts[mint] ?: return false
        val qty = entry.uiDoubleForDisplay()
        if (entry.raw.signum() <= 0) return false
        // Best-effort entry price: current price if we have it, else 0
        // (which leaves entryPrice unchanged ‚Äî caller can still compute
        // qty in lamports for the SELL since qtyToken is what matters).
        val entryPrice = ts.lastPrice.takeIf { it > 0.0 }
            ?: ts.history.lastOrNull()?.priceUsd
            ?: ts.position.entryPrice.takeIf { it > 0.0 }
            ?: 0.0
        synchronized(ts) {
            ts.position = ts.position.copy(
                qtyToken       = qty,
                // V5.0.3777 ‚Äî do NOT synthesize SOL basis from USD/token price.
                // Wallet-only recovery has token proof but no spend proof; invented
                // costSol poisons PnL, thesis, and stop logic. Preserve existing
                // cost if known, otherwise leave 0 and mark entry source as basis-unknown.
                costSol        = ts.position.costSol.takeIf { it > 0.0 } ?: 0.0,
                entryPrice     = entryPrice.takeIf { it > 0.0 } ?: ts.position.entryPrice,
                entryTime      = if (ts.position.entryTime > 0L) ts.position.entryTime else System.currentTimeMillis(),
                isPaperPosition= false,  // wallet has on-chain tokens ‚Üí live by definition
                pendingVerify  = false,
                entryPriceSource = ts.position.entryPriceSource.ifBlank { "WALLET_REHYDRATE_BASIS_UNKNOWN" },
            )
        }
        ErrorLogger.warn("Executor",
            "ü©π ON-CHAIN REHYDRATE [Wallet] ${ts.symbol} (${mint.take(8)}‚Ä¶): qty=$qty " +
            "(entryPrice=${entryPrice.takeIf { it > 0 } ?: "unknown"}) ‚Äî " +
            "no sub-trader had this position; reading from wallet directly")
        return true
    }

    /**
     * V5.9.475 ‚Äî POSITION-STORE REHYDRATION (P1 sell-kill fix).
     *
     * Operator-reported bug: 'nothing sell wise in the meme trader fires in
     * the host wallet. the transaction has never made it to that point.'
     *
     * Root cause: CashGenerationAI / ShitCoinTraderAI / QualityTraderAI /
     * BlueChipTraderAI / MoonshotTraderAI all keep positions in their OWN
     * private maps and do NOT write back to ts.position when they take
     * ownership. So when an auto-exit fires (Treasury TP, profit-lock,
     * normal exit, manual sell), the sell path checks
     * ts.position.isOpen ‚Üí qtyToken=0 ‚Üí returns ALREADY_CLOSED. The actual
     * Jupiter swap never gets called. Tokens stay in the host wallet
     * forever and the bot reports the position 'closed' with no PnL.
     *
     * This helper scans every sub-trader store. If any has the mint AND
     * ts.position is empty, we copy the cost basis + entry price + paper
     * flag back onto ts.position and synthesise qtyToken = entrySol /
     * entryPrice. After this, the isOpen guard passes and the sell flows
     * end-to-end through the existing live-sell pipeline (V5.9.467 RPC
     * rescue ‚Üí V5.9.468 binding-order quote ‚Üí V5.9.470/472 slippage
     * escalation ‚Üí V5.9.474 forensics) just like a non-rehydrated sell.
     *
     * Returns true if a rehydration occurred. Safe to call repeatedly ‚Äî
     * skips work if ts.position already has positive qty.
     */
    private fun rehydratePositionFromSubTraders(ts: TokenState): Boolean {
        // Already valid ‚Äî nothing to do.
        if (ts.position.qtyToken > 0.0) return false

        val mint = ts.mint
        // Local helper: stamp the rehydrated values onto ts.position.
        fun applyRehydrate(entrySol: Double, entryPrice: Double, entryTime: Long, isPaper: Boolean, sourceTag: String): Boolean {
            val mode = if (isPaper) "paper" else "live"
            val canonical = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .filter { it.mint == mint && it.mode.equals(mode, true) && it.remainingQtyRaw.signum() > 0 }
                .maxByOrNull { it.lastMutationMs } ?: return false
            val qty = com.lifecyclebot.engine.truth.CanonicalTokenAmount(canonical.remainingQtyRaw, canonical.quantityScale).uiDoubleForDisplay()
            if (qty <= 0.0 || !qty.isFinite()) return false
            synchronized(ts) {
                ts.position = ts.position.copy(
                    qtyToken = qty,
                    costSol = (canonical.entryCostSol - canonical.soldCostBasisSol).coerceAtLeast(0.0),
                    entryPrice = canonical.entryPriceUsd,
                    entryTime = canonical.openedAtMs,
                    isPaperPosition = isPaper,
                    pendingVerify = false,
                )
            }
            ErrorLogger.info("Executor",
                "ü©π REHYDRATE_CANONICAL_6522 [${sourceTag}] ${ts.symbol}: positionId=${canonical.positionId} raw=${canonical.remainingQtyRaw} decimals=${canonical.quantityScale} qty=$qty " +
                "(isPaper=$isPaper) ‚Äî sub-trader had position but ts.position was empty")
            return true
        }

        // Treasury (CashGenerationAI) ‚Äî most common case for the operator's bug.
        try {
            val tp = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
            if (tp != null && applyRehydrate(tp.entrySol, tp.entryPrice, tp.entryTime, tp.isPaper, "Treasury")) {
                return true
            }
        } catch (_: Exception) {}

        // ShitCoin
        try {
            val sp = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().find { it.mint == mint }
            if (sp != null && applyRehydrate(sp.entrySol, sp.entryPrice, sp.entryTime, sp.isPaper, "ShitCoin")) {
                return true
            }
        } catch (_: Exception) {}

        // Quality (no isPaper field ‚Äî infer from isPaperRT())
        try {
            val qp = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().find { it.mint == mint }
            if (qp != null && applyRehydrate(qp.entrySol, qp.entryPrice, qp.entryTime, isPaperRT(), "Quality")) {
                return true
            }
        } catch (_: Exception) {}

        // BlueChip
        try {
            val bp = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().find { it.mint == mint }
            if (bp != null && applyRehydrate(bp.entrySol, bp.entryPrice, bp.entryTime, bp.isPaper, "BlueChip")) {
                return true
            }
        } catch (_: Exception) {}

        // Moonshot (uses isPaperMode field name ‚Äî same semantics)
        try {
            val mp = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().find { it.mint == mint }
            if (mp != null && applyRehydrate(mp.entrySol, mp.entryPrice, mp.entryTime, mp.isPaperMode, "Moonshot")) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private fun resolveSellUnitsForMint(
        mint: String,
        qty: Double,
        wallet: SolanaWallet? = null,
        fallbackDecimals: Int? = null,
    ): Long {
        if (!qty.isFinite() || qty <= 0.0) return 1L

        // V5.9.495l ‚Äî WALLET-CAP SAFETY NET. Operator (06 May 2026): when
        // a position records inflated qty (e.g. PUMP-FIRST estimate from a
        // stale getActualPrice baked 1000√ó too many tokens into Position),
        // sells try to dump tokens that aren't there. Jupiter quotes still
        // come back proportionally large ‚Üí fake +62000% gains in journal.
        // Cap the sell qty at whatever the wallet ACTUALLY holds so we
        // never sell phantom inventory.
        val walletAccounts = try { wallet?.getTokenAccountsWithDecimalsBounded() } catch (_: Exception) { null }
        val walletEntry = walletAccounts?.get(mint)
        val walletQty = walletEntry?.first ?: 0.0
        val cappedQty = if (walletQty > 0.0 && walletQty < qty) {
            ErrorLogger.info("Executor",
                "üõ° WALLET-CAP: ${mint.take(8)}‚Ä¶ requested qty=$qty but wallet has $walletQty ‚Äî capping sell to wallet")
            // V5.0.6312 ‚Äî canonical telemetry for the operator hotfix spec.
            // Emits SELL_RAW_QTY_CLAMPED_TO_WALLET so the safety-hold health
            // sampler can detect clamp bursts and arm the hold.
            try {
                ForensicLogger.lifecycle(
                    "SELL_RAW_QTY_CLAMPED_TO_WALLET",
                    "mint=${mint.take(10)} requestedQty=$qty walletQty=$walletQty clampRatio=${if (qty > 0.0) walletQty / qty else 0.0}",
                )
                PipelineHealthCollector.labelInc("SELL_RAW_QTY_CLAMPED_TO_WALLET")
            } catch (_: Throwable) {}
            walletQty
        } else qty
        // V5.0.6312 ‚Äî wallet balance authority not available: emit
        // SELL_BLOCKED_UNKNOWN_RAW_BALANCE telemetry (advisory only ‚Äî we
        // still proceed with requested qty to avoid stranding a losing
        // position; the safety-hold health check will observe the burst).
        if (walletAccounts == null || walletEntry == null) {
            try {
                PipelineHealthCollector.labelInc("SELL_BLOCKED_UNKNOWN_RAW_BALANCE_ADVISORY_6312")
            } catch (_: Throwable) {}
        }

        // V5.0.6405 ¬ß5 ‚Äî DECIMAL INTEGRITY HARD BLOCK (no bandaid).
        // Was: `walletEntry?.second ?: fallbackDecimals ?: 9` ‚Äî this
        // fabricated `9` decimals whenever the wallet had not yet indexed
        // the mint AND no caller-supplied fallback existed. Operator
        // directive: "false or unknown data is inexcusable ‚Äî fix the
        // issue instead of bandaiding". We now walk a strict ladder:
        //   wallet ‚Üí MintDecimalsAuthority6392 cache ‚Üí local cache
        //   ‚Üí getAccountInfo(mint, jsonParsed) ‚Üí caller fallback ‚Üí REFUSE.
        // A refusal returns 0L so the caller's downstream `<=0` guard
        // aborts the sell cleanly with a forensic marker ‚Äî no phantom
        // scale factor, no Double fallback, no over-sell.
        val decimals: Int = try {
            com.lifecyclebot.engine.truth.DecimalIntegrityAuthority6405
                .resolveDecimalsStrict(
                    mint = mint,
                    wallet = wallet,
                    walletCachedDecimals = walletEntry?.second,
                    fallbackDecimals = fallbackDecimals,
                )
        } catch (e: com.lifecyclebot.engine.truth.DecimalIntegrityAuthority6405.UnresolvableDecimalsException) {
            ErrorLogger.warn(
                "Executor",
                "üö´ SELL_ABORTED_DECIMAL_INTEGRITY_6405 mint=${mint.take(10)} reason=${e.reason} ‚Äî refusing to guess decimals",
            )
            try {
                ForensicLogger.lifecycle(
                    "SELL_ABORTED_DECIMAL_INTEGRITY_6405",
                    "mint=${mint.take(10)} reason=${e.reason}",
                )
                PipelineHealthCollector.labelInc("SELL_ABORTED_DECIMAL_INTEGRITY_6405")
            } catch (_: Throwable) {}
            return 0L
        }
        // V5.0.6402 ¬ß7/¬ß8 SELL-PATH INTEGER MATH ‚Äî BigDecimal.movePointRight
        // via SellIntentQuantityAuthority6401. Any conversion failure at
        // this stage is ALSO a hard refusal (was: silent Double.pow
        // fallback). Decimals are now proven, so the only remaining
        // failure modes are non-finite qty or arithmetic overflow ‚Äî both
        // of which are integrity events, not "keep trying" cases.
        val rawFromAuthority: java.math.BigInteger = try {
            val decimalsAuthority = com.lifecyclebot.engine.truth.MintDecimals.Known(
                count = decimals,
                source = if (walletEntry != null) "WALLET_TOKEN_ACCOUNTS"
                    else "MINT_DECIMALS_AUTHORITY_6405",
                proofSignature = "resolveSellUnitsForMint",
            )
            val rawUi = com.lifecyclebot.engine.truth.SellIntentQuantityAuthority6401
                .convertUiToRaw(cappedQty, decimalsAuthority)
            if (walletEntry != null) {
                // Wallet raw balance is authoritative ‚Üí validate.
                val walletRaw = com.lifecyclebot.engine.truth.SellIntentQuantityAuthority6401
                    .convertUiToRaw(walletEntry.first, decimalsAuthority)
                if (walletRaw.signum() > 0) {
                    when (val v = com.lifecyclebot.engine.truth.SellIntentQuantityAuthority6401
                        .validateSellIntent(mint, rawUi, walletRaw, decimalsAuthority)) {
                        is com.lifecyclebot.engine.truth.SellIntentQuantityAuthority6401.Verdict.Accept -> v.rawQty
                        is com.lifecyclebot.engine.truth.SellIntentQuantityAuthority6401.Verdict.Reject -> {
                            try {
                                PipelineHealthCollector.labelInc("SELL_INTENT_AUTHORITY_CLAMPED_6402")
                                ForensicLogger.lifecycle(
                                    "SELL_INTENT_AUTHORITY_CLAMPED_6402",
                                    "mint=${mint.take(10)} reason=${v.reason} rawReq=${v.rawRequested} rawAvail=${v.rawAvailable} overshootPct=${"%.2f".format(v.overshootPct)}",
                                )
                            } catch (_: Throwable) {}
                            walletRaw
                        }
                    }
                } else rawUi
            } else rawUi
        } catch (t: Throwable) {
            // V5.0.6405 ¬ß5 ‚Äî NO LEGACY DOUBLE FALLBACK. Decimals are proven,
            // so any conversion failure here is an integrity event.
            ErrorLogger.warn(
                "Executor",
                "üö´ SELL_ABORTED_QTY_CONVERSION_6405 mint=${mint.take(10)} err=${t.message?.take(120)} ‚Äî refusing Double fallback",
            )
            try {
                ForensicLogger.lifecycle(
                    "SELL_ABORTED_QTY_CONVERSION_6405",
                    "mint=${mint.take(10)} decimals=$decimals cappedQty=$cappedQty err=${t.message?.take(120)}",
                )
                PipelineHealthCollector.labelInc("SELL_ABORTED_QTY_CONVERSION_6405")
            } catch (_: Throwable) {}
            return 0L
        }

        // V5.0.6405 ¬ß5 ‚Äî Lifetime raw-qty invariant: sum(raw sold) <= raw
        // entry. Executor records entry raw on buy confirmation; this
        // clamps mid-flight over-sells before they leave the app.
        val positionGeneration = try {
            // Callers pass ts through resolveSellUnits(); this path lacks
            // ts context so we use the position's entryTime via the
            // shared runtime bridge. Keying by (mint, entryTime) matches
            // the executor's existing per-lifetime scheme.
            com.lifecyclebot.engine.truth.PositionGenerationBridge6405.get(mint)
        } catch (_: Throwable) { 0L }
        val invariantClamped = com.lifecyclebot.engine.truth.DecimalIntegrityAuthority6405
            .clampSoldRawToEntry(mint, positionGeneration, rawFromAuthority)

        // Long range guard ‚Äî Jupiter/PumpPortal builders take Long. Anything
        // that exceeds Long.MAX_VALUE was a decimal-skew catastrophe; clamp
        // to Long.MAX_VALUE (upstream will still fail sanity but at least
        // won't overflow into a negative).
        val maxLong = java.math.BigInteger.valueOf(Long.MAX_VALUE)
        return if (invariantClamped > maxLong) Long.MAX_VALUE
        // V5.0.6405 ¬ß5 ‚Äî no .coerceAtLeast(1L). Returning 0L is a
        // legitimate refusal signal for callers; the previous minimum
        // guaranteed a broadcast even when the invariant said "nothing
        // more to sell".
        else invariantClamped.toLong().coerceAtLeast(0L)
    }

    /** V5.0.3801 ‚Äî amount proof/clamp/formatting lives in ProcessorAmountPlanner; Executor remains route orchestration. */

    private fun recalcSellPlanForProcessor(
        ts: TokenState,
        wallet: SolanaWallet,
        processor: String,
        requestedUiQty: Double,
        exitReason: String = processor,
        fraction: Double? = null,
        sellTradeKey: String? = null,
        traderTag: String = "MEME",
    ): ProcessorAmountPlanner.SellPlan? {
        return ProcessorAmountPlanner.planSell(
            ts = ts,
            wallet = wallet,
            processor = processor,
            requestedUiQty = requestedUiQty,
            exitReason = exitReason,
            fraction = fraction,
            sellTradeKey = sellTradeKey,
            traderTag = traderTag,
        )
    }

    /** V5.0.3764 ‚Äî single fee authority for live tx builders. */
    private fun effectiveJitoTipLamports(c: com.lifecyclebot.data.BotConfig, urgent: Boolean = false): Long {
        if (!c.jitoEnabled) return 0L
        val dynamic = try { com.lifecyclebot.network.JitoTipFetcher.getDynamicTip(c.jitoTipLamports) } catch (_: Throwable) { c.jitoTipLamports }
        val floored = maxOf(dynamic, c.jitoTipLamports, 200_000L)
        return if (urgent) (floored * 2L).coerceAtMost(1_000_000L) else floored
    }

    private fun recalcBuyPlanForProcessor(
        ts: TokenState,
        wallet: SolanaWallet,
        processor: String,
        requestedSol: Double,
        priorityFeeSol: Double = 0.0,
        jitoTipLamports: Long = 0L,
        tradeKey: String? = null,
        traderTag: String = "MEME",
    ): ProcessorAmountPlanner.BuyPlan? {
        return ProcessorAmountPlanner.planBuy(
            ts = ts,
            wallet = wallet,
            processor = processor,
            requestedSol = requestedSol,
            priorityFeeSol = priorityFeeSol,
            jitoTipLamports = jitoTipLamports,
            tradeKey = tradeKey,
            traderTag = traderTag,
        )
    }

    private fun blockIfSellInFlight(ts: TokenState, reason: String, tradeKey: String? = null): Boolean {
        val rU = reason.uppercase()
        val isUnconditionalSafety = rU.contains("HARD_FLOOR") || rU.contains("CATASTROPHE") ||
            rU.contains("RUG") || rU.contains("DRAIN") || rU.contains("SHUTDOWN") || rU.contains("MANUAL") ||
            rU.contains("HONEYPOT") || rU.contains("DEV_DUMP") || rU.contains("DEV_SELL") ||
            rU.contains("STRICT_SL") || rU.contains("HARD_STOP") || rU.contains("STOP_LOSS") ||
            rU.contains("EXIT-RESCUE") || rU.contains("EXIT_RESCUE") ||
            rU.contains("EXIT-DRAIN-RESCUE") || rU.contains("RAPID_CATASTROPHE") ||
            rU.contains("LIQUIDITY_REMOVED") || rU.contains("WALLET_DRAIN")

        // V5.0.4151 ‚Äî strict/catastrophe exits override recovered-hold grace.
        // Previously RecoveredHoldGuard ran before the safety punch-through and
        // could return true for STRICT_SL/CATASTROPHE, creating -98% MOONSHOT
        // stop rows. Safety exits must always continue to sell route selection.
        try {
            if (RecoveredHoldGuard.shouldSuppress(ts.mint, reason) && !isUnconditionalSafety) {
                LiveTradeLogStore.log(
                    tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                    ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                    "HOLD_PROTECTED_EXIT_SUPPRESSED reason=$reason graceRemainMs=${RecoveredHoldGuard.graceRemainingMs(ts.mint)}",
                    traderTag = "MEME",
                )
                ForensicLogger.lifecycle(
                    "HOLD_PROTECTED_EXIT_SUPPRESSED",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason " +
                    "remainMs=${RecoveredHoldGuard.graceRemainingMs(ts.mint)} cause=RECOVERED_HOLD_GRACE"
                )
                PipelineHealthCollector.labelInc("HOLD_PROTECTED_EXIT_SUPPRESSED")
                return true   // BLOCK the sell ‚Äî held by recovered grace
            }
        } catch (_: Throwable) { }
        if (isUnconditionalSafety) {
            try {
                PipelineHealthCollector.labelInc(if (rU.contains("CATASTROPHE")) "CATASTROPHE_OVERRIDE_PROFIT_LOCK" else "STRICT_SL_OVERRIDE_HOLD")
            } catch (_: Throwable) {}
        }

        val stateReason = HostWalletTokenTracker.sellBlockReason(ts.mint)
        // V5.9.1522 ‚Äî unconditional safety reasons PUNCH THROUGH a stale in-flight
        // block. A -15% hard floor / catastrophe / rug exit must never wait behind
        // a leaked SELL_BLOCKED_ALREADY_IN_PROGRESS (operator: positions bled to
        // -50%/-97% while a prior sell attempt's lock was stuck). These reasons
        // additionally clear the stale marker so the fresh stop proceeds now.
        // V5.0.4103 / V5.0.4151 ‚Äî full-exit + stop-loss class punches through too.
        // isUnconditionalSafety is computed before recovered-hold suppression so
        // hold/profit-lock grace cannot suppress STRICT_SL/RAPID_CATASTROPHE.
        if (stateReason != null && isUnconditionalSafety) {
            // V5.0.4103 ‚Äî also raise the lease intent priority so any active
            // worker switching context (route refresh / retry pick) reads the
            // new emergency reason instead of an older trail/partial.
            try {
                val severity = com.lifecyclebot.engine.sell.SellIntentSeverity.forReason(reason)
                com.lifecyclebot.engine.sell.CloseLease.raiseIntent(ts.mint, reason, severity)
            } catch (_: Throwable) {}
            try { HostWalletTokenTracker.clearSellInFlight(ts.mint, "PUNCH_THROUGH_$rU") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint) } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("SELL_INFLIGHT_PUNCH_THROUGH", "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason staleState=$stateReason") } catch (_: Throwable) {}
            return false   // allow the safety sell to proceed
        }
        if (stateReason != null && !com.lifecyclebot.engine.sell.SellSafetyPolicy.isManualEmergency(reason)) {
            // V5.9.967 ‚Äî z43-D SellSpamGuard: suppress duplicate blocked-log
            // floods. Higher-priority reasons (rug/manual/hard_stop) punch
            // through immediately; lower-priority (trail/partial) cool down 30s.
            if (com.lifecyclebot.engine.sell.SellSpamGuard.shouldLogBlocked(ts.mint, reason)) {
                LiveTradeLogStore.log(
                    tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                    ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                    "SELL_BLOCKED_ALREADY_IN_PROGRESS state=$stateReason reason=$reason", traderTag = "MEME",
                )
            }
            return true
        }
        // V5.9.775 ‚Äî EMERGENT MEME stale-lock auto-recovery.
        // Previously isLocked returned true for any entry, including
        // leaked locks that never released. Now SellExecutionLocks
        // has a 60 s TTL and isLocked() lazy-evicts stale entries ‚Äî
        // so reaching this branch genuinely means a sell is in flight
        // within the TTL window. Still emit the canonical
        // SELL_BLOCKED_ALREADY_IN_PROGRESS line with the lock age so
        // operator can spot churn.
        if (com.lifecyclebot.engine.sell.SellExecutionLocks.isLocked(ts.mint)) {
            val ageMs = com.lifecyclebot.engine.sell.SellExecutionLocks.ageMs(ts.mint) ?: 0L
            // V5.9.967 ‚Äî z43-D SellSpamGuard wrap.
            if (com.lifecyclebot.engine.sell.SellSpamGuard.shouldLogBlocked(ts.mint, reason)) {
                LiveTradeLogStore.log(
                    tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                    ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                    "SELL_BLOCKED_ALREADY_IN_PROGRESS lock=true ageMs=$ageMs reason=$reason", traderTag = "MEME",
                )
            }
            return true
        }
        return false
    }

    private fun reflectInt(target: Any?, vararg names: String): Int? {
        for (name in names) {
            val value = reflectValue(target, name) ?: continue
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun reflectDouble(target: Any?, vararg names: String): Double? {
        for (name in names) {
            val value = reflectValue(target, name) ?: continue
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun reflectValue(target: Any?, name: String): Any? {
        if (target == null) return null
        val cls = target.javaClass

        try {
            val field = cls.getDeclaredField(name)
            field.isAccessible = true
            return field.get(target)
        } catch (_: Exception) {
        }

        val suffix = if (name.isEmpty()) name else name.substring(0, 1).uppercase() + name.substring(1)
        val methodNames = arrayOf("get$suffix", "is$suffix", name)

        for (methodName in methodNames) {
            try {
                val method = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
                return method.invoke(target)
            } catch (_: Exception) {
            }
        }

        return null
    }

    // ‚îÄ‚îÄ position sizing ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ

    /**
     * Smart position sizing ‚Äî delegates to SmartSizer.
     * Size scales with wallet balance, conviction, win rate, and drawdown.
     * Returns 0.0 if sizing conditions block the trade (drawdown circuit breaker etc.)
     */
    fun buySizeSol(
        entryScore: Double,
        walletSol: Double,
        currentOpenPositions: Int = 0,
        currentTotalExposure: Double = 0.0,
        walletTotalTrades: Int = 0,
        liquidityUsd: Double = 0.0,
        mcapUsd: Double = 0.0,
        // NEW: AI-driven parameters
        aiConfidence: Double = 50.0,
        phase: String = "unknown",
        source: String = "unknown",
        brain: BotBrain? = null,
        setupQuality: String = "C",    // A+ / B / C from strategy
        ts: TokenState? = null,        // V5.9.69: optional ‚Äî enables PatternClassifier boost
        laneMode: String = "",         // V5.9.718 ‚Äî trading lane for phase-aware size scaling
    ): Double {
        val isPaperMode = isPaperRT()

        // V5.9.69: PatternClassifier boost ‚Äî additive on aiConfidence plus a
        // sizing multiplier. Paper-only gate enforced inside the classifier
        // (returns 0 / 1.0 in live until it has 50 live samples @ 55% wr).
        var adjustedConfidence = aiConfidence
        var patternSizeMult = 1.0
        if (ts != null) {
            try {
                val feats = PatternClassifier.extract(ts)
                val boost = PatternClassifier.getConfidenceBoost(feats, isPaperMode)
                if (boost != 0) {
                    adjustedConfidence = (aiConfidence + boost).coerceIn(0.0, 100.0)
                }
                patternSizeMult = PatternClassifier.getSizeMultiplier(feats, isPaperMode)
            } catch (_: Exception) {}
        }

        // ‚îÄ‚îÄ V5.9.817 ‚Äî BehaviorAI MEME path wire-up (AGI campaign push 6) ‚îÄ‚îÄ
        // CryptoAltTrader already consumes BehaviorAI.getSizingMultiplier (see
        // CryptoAltTrader.hiveEntryModifier, V5.9.x). Meme path had three dark
        // consumers per audit_v5.9.811_dormant_agi.md TIER 1.1:
        //   getSizingMultiplier  ‚Äî dark on meme until now
        //   getEntryThresholdMod ‚Äî dark everywhere until now
        //   getMinQualityGrade   ‚Äî dark everywhere until now
        //
        // V5.9.816 closed BehaviorAI's internal autoDeescalation loop. This
        // commit finally lets that loop steer the meme execution path. When
        // BehaviorAI auto-deescalates aggression 5‚Üí2 after a loss streak:
        //   - confidence is shaved by 5 points (harder bar)
        //   - size is multiplied by 0.7 (smaller bets)
        //   - if grade < min, size further multiplied by 0.7
        //
        // Doctrine compliance (memory #86): no new veto. Every output stays
        // in the soft-shape range [0.5, 1.5]√ó and bounded confidence delta.
        // The candidate still trades ‚Äî just smaller and pickier when the bot
        // is on tilt. Aggression band 1 (the auto-floor) still yields a
        // non-zero size; only manual setAggressionLevel(0) clamps to 0.5√ó.
        var behaviorSizeMult = 1.0
        var behaviorGradeMult = 1.0
        try {
            val rawSize = com.lifecyclebot.v3.scoring.BehaviorAI.getSizingMultiplier()
            behaviorSizeMult = rawSize.coerceIn(0.5, 1.5)

            // Confidence shave: BehaviorAI returns positive = harder threshold
            // (i.e. SHAVE confidence). Negative = looser (BOOST confidence).
            val confShave = com.lifecyclebot.v3.scoring.BehaviorAI.getEntryThresholdMod()
            if (confShave != 0.0) {
                adjustedConfidence = (adjustedConfidence - confShave).coerceIn(0.0, 100.0)
            }

            // Quality-grade soft penalty: if candidate's setupQuality is below
            // BehaviorAI's current min-required grade, √ó0.7 size (NOT reject).
            val minGrade = com.lifecyclebot.v3.scoring.BehaviorAI.getMinQualityGrade()
            val gradeOrder = mapOf("A" to 5, "B" to 4, "C" to 3, "D" to 2, "F" to 1)
            val candidateRank = gradeOrder[setupQuality.uppercase()] ?: 3
            val minRank = gradeOrder[minGrade.uppercase()] ?: 3
            if (candidateRank < minRank) {
                behaviorGradeMult = 0.7
            }
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // ‚îÄ‚îÄ V5.9.818 ‚Äî MarketRegimeAI policy wire-up (AGI campaign push 7) ‚îÄ‚îÄ
        // Audit ref: audit_v5.9.811_dormant_agi.md TIER 1.2. Five MarketRegimeAI
        // policy methods were architecturally orphaned: getPositionSizeMultiplier,
        // getMinEntryScore, isFavorableForEntry, shouldReduceExposure,
        // getHoldTimeMultiplier. The Regime enum DATA was being consulted via
        // direct field reads in LifecycleStrategy (exit timing only) ‚Äî never
        // through the policy methods on the entry path. So STRONG_BEAR would
        // populate, but the meme path would still size 1.0√ó through it.
        //
        // This commit consumes all 4 entry-side methods into a single bounded
        // regimeSizeMult composed alongside behaviorSizeMult. holdTimeMultiplier
        // is exit-side and deferred to a future commit that touches the
        // timeout path.
        //
        // Bounds: regimeSizeMult ‚àà [0.5√ó, 1.5√ó] per Regime enum (STRONG_BEAR
        // floor ‚Üí STRONG_BULL ceiling). Composed multipliers cannot push the
        // size below 0.5 * 0.5 * 0.5 = 0.125√ó (auto-deesc + regime bear + grade
        // penalty all stacked), which still trades ‚Äî just very small. No veto.
        var regimeSizeMult = 1.0
        var regimeMinScoreBoost = 0.0
        try {
            val regimeMult = com.lifecyclebot.engine.MarketRegimeAI.getPositionSizeMultiplier()
            regimeSizeMult = regimeMult.coerceIn(0.5, 1.5)

            // isFavorableForEntry: if STRONG_BEAR / HIGH_VOL / CRAB ‚Üí false.
            // Apply a small additional shrink (soft, not veto).
            if (!com.lifecyclebot.engine.MarketRegimeAI.isFavorableForEntry()) {
                regimeSizeMult *= 0.85
            }

            // shouldReduceExposure: STRONG_BEAR or HIGH_VOLATILITY only. This
            // is the strongest signal ‚Äî apply on top of the favorable check.
            if (com.lifecyclebot.engine.MarketRegimeAI.shouldReduceExposure()) {
                regimeSizeMult *= 0.85
            }

            // Re-clamp after compounding so the floor stays at 0.5
            regimeSizeMult = regimeSizeMult.coerceIn(0.5, 1.5)

            // Entry-score shave: getMinEntryScore returns 20-50 per regime.
            // Treat as a soft confidence shave: NEUTRAL=30 ‚Üí 0 shave,
            // STRONG_BEAR=50 ‚Üí +20 needed ‚Üí shave 20 from confidence.
            // Cap shave at +20 so STRONG_BULL doesnt push it negative.
            val regimeMinScore = com.lifecyclebot.engine.MarketRegimeAI.getMinEntryScore()
            regimeMinScoreBoost = (regimeMinScore - 30.0).coerceIn(-10.0, 20.0)
            if (regimeMinScoreBoost != 0.0) {
                adjustedConfidence = (adjustedConfidence - regimeMinScoreBoost).coerceIn(0.0, 100.0)
            }
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // ‚îÄ‚îÄ V5.9.819 ‚Äî SymbolicContext booleans wire-up (AGI campaign push 8) ‚îÄ‚îÄ
        // Audit ref: audit_v5.9.811_dormant_agi.md TIER 1.3 ‚Äî "biggest meme
        // volume unlock per audit". SymbolicContext maintains 24+ symbolic
        // signals via refresh() (called from BotBrain regime update). Until
        // now ONLY getSignal(name, default) was consumed (by SymbolicExitReasoner
        // on the exit side). Every boolean shortcut on the entry side was dark.
        //
        // Five soft-shape consumers wired here:
        //   isHighRisk()           ‚Üí size √ó 0.80   (overall risk > 60%)
        //   isExecutionDegraded()  ‚Üí size √ó 0.70   (slippage / RPC degraded)
        //   isMarketHealthy()      ‚Üí size √ó 1.10   (healthy ‚Üí size up modestly)
        //   isConfident()          ‚Üí confShave -3  (looser bar when sure of edge)
        //   !isFresh()             ‚Üí size √ó 0.85   (stale symbolic state ‚Üí caution)
        //
        // Skipping isFundingUnfavourable ‚Äî perps-only signal, not meme-relevant.
        //
        // WR/profit thesis: every multiplier here was designed by the audit to
        // SHRINK when conditions are bad (cuts losses) and modestly EXPAND when
        // conditions are good (more $ on winners). Net effect on WR: more
        // selective sizing ‚Üí fewer dollar-weighted losses ‚Üí higher dollar WR.
        // Net effect on profit: size scales with quality of conditions.
        var symbolicSizeMult = 1.0
        var symbolicConfShave = 0.0
        try {
            val sym = com.lifecyclebot.engine.SymbolicContext

            // Fresh-data guard: if symbolic state is stale (>30s old), the
            // booleans below are reading stale signals. Apply a small all-
            // round size cut so the bot doesn't size up on dead data.
            if (!sym.isFresh()) {
                symbolicSizeMult *= 0.85
            }

            // Risk / execution degraded ‚Äî these are the strongest signals
            // for "shrink this bet". Compose multiplicatively so when BOTH
            // fire, size drops to 0.80 * 0.70 = 0.56√ó (still trades).
            if (sym.isHighRisk()) {
                symbolicSizeMult *= 0.80
            }
            if (sym.isExecutionDegraded()) {
                symbolicSizeMult *= 0.70
            }

            // Positive signal ‚Äî modest upsize when market is healthy.
            // Capped: cant push above 1.15√ó even when ALL good signals fire.
            if (sym.isMarketHealthy()) {
                symbolicSizeMult *= 1.10
            }

            // Confidence shave: -3 (LOOSER bar) when bot is internally
            // confident in its edge. Small magnitude on purpose ‚Äî symbolic
            // confidence is a soft hint, not the dominant signal.
            if (sym.isConfident()) {
                symbolicConfShave = -3.0
                adjustedConfidence = (adjustedConfidence - symbolicConfShave).coerceIn(0.0, 100.0)
            }

            // Final clamp on size ‚Äî even with all positive signals firing,
            // bound at [0.5, 1.15]. Worst case isHighRisk + isExecDegraded +
            // !isFresh stacks to 0.85 * 0.80 * 0.70 = 0.476 ‚Üí clamp to 0.5.
            symbolicSizeMult = symbolicSizeMult.coerceIn(0.5, 1.15)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // ‚îÄ‚îÄ V5.9.821 ‚Äî TimeOptimizationAI wire-up on MEME buy size ‚îÄ‚îÄ
        // Audit ref: TIER 3 ‚Äî tracker AIs dormant on meme decision path.
        // TimeOptimizationAI has been measuring per-hour / per-day / per-session
        // outcomes via recordOutcome() (wired from Executor.kt lines 9092 + 11278)
        // for months. Stats populate. Then on the BUY side:
        //   LifecycleStrategy ‚Üí consumes for ENTRY SCORE adjustment
        //   FinalDecisionGate ‚Üí deprecated (V3 migration)
        //   buy size envelope ‚Üí NEVER CONSUMED (this commit)
        //
        // WR/profit thesis: golden hours (top-quartile WR windows historically)
        // get +15% size, danger zones (bottom-quartile WR windows) get -25%.
        // Direct dollar-WR lever: bigger bets when conditions historically
        // produce winners; smaller bets when they historically produce losers.
        //
        // Bounds: timeSizeMult ‚àà [0.7, 1.2]. Combined with everything else in
        // the envelope, the bot now adapts size to:
        //   personality state (BehaviorAI)
        //   market regime (MarketRegimeAI)
        //   symbolic mood (SymbolicContext)
        //   time-of-day edge (TimeOptimizationAI) ‚Üê NEW
        var timeSizeMult = 1.0
        try {
            val tao = com.lifecyclebot.engine.TimeOptimizationAI
            // isGoldenHour requires sample size (per its internal threshold);
            // returns false until enough trades observed at this hour. Same
            // for isDangerZone ‚Äî both gate themselves on maturity.
            if (tao.isGoldenHour()) {
                timeSizeMult *= 1.15
            }
            if (tao.isDangerZone()) {
                timeSizeMult *= 0.75
            }
            // Final clamp ‚Äî even if both somehow fire (shouldnt ‚Äî hourly slot
            // is either golden, danger, or neutral), bound at [0.7, 1.2].
            timeSizeMult = timeSizeMult.coerceIn(0.7, 1.2)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // ‚îÄ‚îÄ V5.9.823 ‚Äî MomentumPredictorAI wire-up on MEME buy size ‚îÄ‚îÄ
        // Audit ref: TIER 3 tracker AIs. recordPricePoint called from
        // BotService:9886 continuously; recordOutcome called from Executor at
        // close. Data populates. hasStrongMomentum + shouldAvoid + getMomentumScore
        // ‚Äî ALL READ-SIDE METHODS DARK on entry path until this commit.
        //
        // Per-mint signal: needs ts (TokenState) in scope. ts is already an
        // optional param on buySizeSol (used by PatternClassifier wire), so
        // the guard pattern matches.
        //
        // WR/profit thesis: MomentumPredictor has been classifying tokens as
        // STRONG_PUMP / PUMP_BUILDING / NEUTRAL / WEAK / DISTRIBUTION for months.
        // Sizing up on momentum-positive predictions + down on distribution-
        // detected = direct dollar concentration on statistically winning
        // patterns. Same volume, more $ on the right ones.
        //
        // Bounds: momentumSizeMult ‚àà [0.7, 1.2]. No new veto.
        var momentumSizeMult = 1.0
        if (ts != null) {
            try {
                val mom = com.lifecyclebot.engine.MomentumPredictorAI
                when {
                    mom.hasStrongMomentum(ts.mint) -> {
                        momentumSizeMult = 1.20
                    }
                    mom.shouldAvoid(ts.mint) -> {
                        momentumSizeMult = 0.70
                    }
                    // else: neutral (NEUTRAL or no data yet) ‚Üí 1.0
                }
                momentumSizeMult = momentumSizeMult.coerceIn(0.7, 1.2)
            } catch (_: Throwable) { /* fail-open per FDG doctrine */ }
        }

        // ‚îÄ‚îÄ V5.9.824 ‚Äî NarrativeDetectorAI hot/cold consumed on MEME size ‚îÄ‚îÄ
        // Audit ref: TIER 3 tracker AIs. Producer side has been fully wired
        // for months:
        //   Executor:9189, 11375          ‚Üí recordOutcome (paper + live close)
        //   EducationSubLayerAI:819       ‚Üí recordOutcome (cross-learning)
        //   BotService:7981               ‚Üí refreshHeat (periodic)
        //
        // Consumer side until V5.9.824:
        //   AICrossTalk ‚Üí getEntryScoreAdjustment (score boost only)
        //   isHotNarrative / isColdNarrative ‚Äî DARK on size path
        //
        // Both methods self-gate on outcomes.size >= 3 internally, so during
        // bootstrap (< 3 samples for a narrative) they return false ‚Üí
        // sizeMult stays at 1.0 ‚Üí fully bootstrap-safe.
        //
        // WR/profit thesis: when a narrative (e.g. AI, DOG, CAT, GAMING) is
        // running HOT (3+ historical trades averaging > 20% PnL), tokens
        // matching that narrative get +10% size ‚Äî concentration on the
        // currently-paying narrative. When narrative is COLD (avg < -10% over
        // 3+ samples), -15% ‚Äî less $ on losing narratives.
        //
        // Bounded [0.85, 1.10], no new veto.
        var narrativeSizeMult = 1.0
        if (ts != null) {
            try {
                val nd = com.lifecyclebot.engine.NarrativeDetectorAI
                when {
                    nd.isHotNarrative(ts.symbol, ts.name) -> {
                        narrativeSizeMult = 1.10
                    }
                    nd.isColdNarrative(ts.symbol, ts.name) -> {
                        narrativeSizeMult = 0.85
                    }
                    // else: neutral narrative or insufficient data ‚Üí 1.0
                }
                narrativeSizeMult = narrativeSizeMult.coerceIn(0.85, 1.10)
            } catch (_: Throwable) { /* fail-open per FDG doctrine */ }
        }

        // ‚îÄ‚îÄ V5.9.827 ‚Äî sell-pressure soft-shape on MEME size ‚îÄ‚îÄ
        // For months DataOrchestrator's DexScreener WS lambda dropped sells5m
        // on every tick ‚Äî only buys5m flowed through to pressScore. The bot
        // was structurally blind to distribution. V5.9.827 surfaces it as
        // ts.lastSellPressurePct (sells5m / txns5m * 100) and consumes here.
        //
        // WR/profit thesis: high sell-pressure entries are distribution
        // events disguised as continuations. Soft-shave size [-15% at 65%
        // sell-pressure, -25% at 75%+]. Below 50% sell-pressure (i.e. buy-
        // dominated) ‚Üí mild +5% boost. Bounded, no new veto, fail-open.
        var sellPressureSizeMult = 1.0
        if (ts != null) {
            try {
                val sp = ts.lastSellPressurePct
                sellPressureSizeMult = when {
                    sp >= 75.0 -> 0.75  // heavy distribution
                    sp >= 65.0 -> 0.85  // moderate distribution
                    sp <= 35.0 -> 1.05  // strong buy dominance
                    else       -> 1.00  // neutral / no signal
                }
                sellPressureSizeMult = sellPressureSizeMult.coerceIn(0.75, 1.05)
            } catch (_: Throwable) { /* fail-open per FDG doctrine */ }
        }

        // ‚îÄ‚îÄ V5.9.827 ‚Äî 1h trend soft-shape on MEME size ‚îÄ‚îÄ
        // priceChange1h was also dropped by the WS lambda for months. Now
        // available as ts.lastPriceChange1h. Strong 1h dump (-20%+) ‚Üí -15%
        // size. Strong 1h pump (+20%+) ‚Üí +10% size. Bounded, no veto.
        var trend1hSizeMult = 1.0
        if (ts != null) {
            try {
                val ch = ts.lastPriceChange1h
                trend1hSizeMult = when {
                    ch <= -20.0 -> 0.85
                    ch <= -10.0 -> 0.92
                    ch >=  20.0 -> 1.10
                    ch >=  10.0 -> 1.05
                    else        -> 1.00
                }
                trend1hSizeMult = trend1hSizeMult.coerceIn(0.85, 1.10)
            } catch (_: Throwable) { /* fail-open per FDG doctrine */ }
        }

        // ‚îÄ‚îÄ V5.9.834 (B8) ‚Äî EmergentGuardrails rate-limit soft-shaper ‚îÄ‚îÄ
        // EmergentGuardrails.recordTradeExecution() has been firing on every
        // trade close since the safety system was added, populating
        // tradeTimestamps. But getRateLimitSizeMultiplier() has been DARK ‚Äî
        // no consumer. Wire it as a size multiplier so the bot naturally
        // slows down trade aggression when the per-minute trade rate
        // approaches the configured threshold.
        // Returns from EmergentGuardrails are already bounded:
        //   ratio >= 1.0  ‚Üí 0.5
        //   ratio >= 0.8  ‚Üí 0.75
        //   else          ‚Üí 1.0
        // Pure soft-shape. No new veto. FDG hard floors untouched.
        var rateLimitSizeMult = 1.0
        try {
            rateLimitSizeMult = com.lifecyclebot.engine.EmergentGuardrails
                .getRateLimitSizeMultiplier().coerceIn(0.5, 1.0)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }

        // Update session peak (mode-aware to prevent paper stats affecting live)
        SmartSizer.updateSessionPeak(walletSol, isPaperMode)

        val perf = SmartSizer.getPerformanceContext(walletSol, walletTotalTrades, isPaperMode)
        val solPx = try { WalletManager.lastKnownSolPrice } catch (_: Exception) { 130.0 }

        val result = SmartSizer.calculate(
            walletSol            = walletSol,
            entryScore           = entryScore,
            perf                 = perf,
            cfg                  = cfg(),
            openPositionCount    = currentOpenPositions,
            currentTotalExposure = currentTotalExposure,
            liquidityUsd         = liquidityUsd,
            solPriceUsd          = solPx,
            mcapUsd              = mcapUsd,
            aiConfidence         = adjustedConfidence,
            phase                = phase,
            source               = source,
            brain                = brain,
            setupQuality         = setupQuality,
            laneMode             = laneMode,
        )

        if (result.solAmount <= 0.0) {
            onLog("üìä AI Sizer blocked: ${result.explanation}", "sizing")
        } else {
            // V5.9.446 ‚Äî meme WR emergency brake halves sizing when engaged
            // (meme WR < 30% after 500+ trades). Releases automatically at 35%.
            val brakeMult = try {
                com.lifecyclebot.engine.MemeWREmergencyBrake.sizingMultiplier()
            } catch (_: Throwable) { 1.0 }
            // V5.9.817 ‚Äî compose BehaviorAI multipliers into the same
            // multiplicative envelope as patternSizeMult and brakeMult.
            // Both already clamped: behaviorSizeMult ‚àà [0.5, 1.5],
            // behaviorGradeMult ‚àà {0.7, 1.0}.
            // V5.9.824 ‚Äî also compose narrativeSizeMult into the envelope.
            //
            // V5.9.867 ‚Äî COMPOSED-PRODUCT FLOOR.
            // Audit showed worst-case product across 10 multipliers can reach
            // 0.0141 (1.4% of base). In a bad regime that pushes a 0.05 SOL
            // base buy to ~0.0007 SOL, well below the 0.001 dust floor at
            // line 5108/5456 ‚Äî which silently rejects the trade.
            //
            // Doctrine #20: bot must keep training at 500+ trades/day.
            // Doctrine #86: every soft-shaper must HELP, not effectively veto.
            // Soft-shape spirit: composed product floored at 0.25 so the
            // bot still trades at ~25% of base size in the absolute worst
            // case (enough to clear dust + produce learning signal) but
            // still feels the full directional pressure of the stack.
            // Upper end stays unclamped ‚Äî best-case ~4.7x is fine because
            // SmartSizer already caps absolute SOL exposure upstream.
            val rawMultProduct = patternSizeMult * brakeMult *
                                 behaviorSizeMult * behaviorGradeMult * regimeSizeMult *
                                 symbolicSizeMult * timeSizeMult * momentumSizeMult *
                                 narrativeSizeMult * sellPressureSizeMult * trend1hSizeMult *
                                 rateLimitSizeMult
            val composedMult = rawMultProduct.coerceAtLeast(0.25)
            if (rawMultProduct < 0.25) {
                onLog("ü™Ç Composed mult floor: raw=${"%.4f".format(rawMultProduct)}x ‚Üí 0.25x " +
                      "(stack was about to size-zero ‚Äî keeping bot trading per doctrine)", "sizing")
            }
            // V5.9.939 ‚Äî TIER-AWARE POSITION SIZE CAP (operator doctrine).
            // ScalingMode.Tier.maxPositionSol() applies tier-specific
            // ownership caps:
            //   MICRO         4% of pool liquidity
            //   STANDARD      4%
            //   GROWTH        3%
            //   SCALED        2%
            //   INSTITUTIONAL 1%
            // Pre-V5.9.939 this method existed but was UNUSED ‚Äî Executor
            // used a global cap regardless of token tier. A position that
            // owns 4% of a $5K micro pool ($200) is healthy; that same
            // 4% of a $50M chip pool ($2M) is catastrophic. Tier-aware
            // cap honors the architectural intent.
            var finalSol = result.solAmount * composedMult   // V5.9.867 floor applied
            try {
                if (mcapUsd > 0.0 && liquidityUsd > 0.0) {
                    val tier = com.lifecyclebot.engine.ScalingMode.tierForToken(
                        liquidityUsd = liquidityUsd,
                        mcapUsd = mcapUsd,
                    )
                    val solUsdPrice = try {
                        com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                            .takeIf { it in 50.0..500.0 } ?: 85.0
                    } catch (_: Throwable) { 85.0 }
                    val tierCap = tier.maxPositionSol(liquidityUsd, solUsdPrice)
                    if (tierCap.isFinite() && tierCap > 0.0 && finalSol > tierCap) {
                        onLog(
                            "üéØ TierCap: ${tier.label} (${tier.icon}) " +
                            "${finalSol.fmt(4)} ‚Üí ${tierCap.fmt(4)} SOL " +
                            "(${(tier.ownershipCapPct * 100).fmt(1)}% of \$${liquidityUsd.toInt()} pool)",
                            "sizing"
                        )
                        finalSol = tierCap
                    }
                }
            } catch (_: Throwable) { /* fail-open ‚Äî tier cap is advisory */ }
            if (patternSizeMult != 1.0) {
                onLog("üß† Pattern mult: ${"%.2f".format(patternSizeMult)}x " +
                      "(${result.solAmount.fmt(4)} ‚Üí ${finalSol.fmt(4)} SOL)", "sizing")
            }
            if (brakeMult != 1.0) {
                onLog("üö® WR-brake mult: ${"%.2f".format(brakeMult)}x (meme WR low ‚Äî halving risk)", "sizing")
            }
            if (behaviorSizeMult != 1.0 || behaviorGradeMult != 1.0) {
                val aggrLvl = try {
                    com.lifecyclebot.v3.scoring.BehaviorAI.getAggressionLevel()
                } catch (_: Throwable) { 5 }
                onLog("üéöÔ∏è BehaviorAI: aggr=$aggrLvl size√ó${"%.2f".format(behaviorSizeMult)} " +
                      "grade√ó${"%.2f".format(behaviorGradeMult)} confShave‚Üí${adjustedConfidence.toInt()}", "sizing")
            }
            if (regimeSizeMult != 1.0 || regimeMinScoreBoost != 0.0) {
                val regimeLabel = try {
                    com.lifecyclebot.engine.MarketRegimeAI.getCurrentRegime().label
                } catch (_: Throwable) { "?" }
                onLog("üåê MarketRegime: $regimeLabel size√ó${"%.2f".format(regimeSizeMult)} " +
                      "scoreShave=${regimeMinScoreBoost.toInt()}", "sizing")
            }
            if (symbolicSizeMult != 1.0 || symbolicConfShave != 0.0) {
                val sym = com.lifecyclebot.engine.SymbolicContext
                val flags = buildString {
                    if (sym.isHighRisk())          append("RISK ")
                    if (sym.isExecutionDegraded()) append("EXEC- ")
                    if (sym.isMarketHealthy())     append("HEALTH+ ")
                    if (sym.isConfident())         append("CONF+ ")
                    if (!sym.isFresh())            append("STALE ")
                }.trim().ifBlank { "neutral" }
                onLog("üß¨ Symbolic: [$flags] size√ó${"%.2f".format(symbolicSizeMult)} " +
                      "confShave=${symbolicConfShave.toInt()}", "sizing")
            }
            if (timeSizeMult != 1.0) {
                val tao = com.lifecyclebot.engine.TimeOptimizationAI
                val tag = when {
                    tao.isGoldenHour() -> "GOLDEN"
                    tao.isDangerZone() -> "DANGER"
                    else               -> "neutral"
                }
                onLog("üïê TimeOpt: [$tag] hour=${tao.getCurrentHourUtc()}utc " +
                      "size√ó${"%.2f".format(timeSizeMult)}", "sizing")
            }
            if (momentumSizeMult != 1.0 && ts != null) {
                val mom = com.lifecyclebot.engine.MomentumPredictorAI
                val tag = when {
                    mom.hasStrongMomentum(ts.mint) -> "STRONG_PUMP"
                    mom.shouldAvoid(ts.mint)       -> "DISTRIBUTION"
                    else                            -> "neutral"
                }
                onLog("üìà Momentum: [$tag] size√ó${"%.2f".format(momentumSizeMult)}", "sizing")
            }
            if (narrativeSizeMult != 1.0 && ts != null) {
                val nd = com.lifecyclebot.engine.NarrativeDetectorAI
                val tag = when {
                    nd.isHotNarrative(ts.symbol, ts.name)  -> "HOT"
                    nd.isColdNarrative(ts.symbol, ts.name) -> "COLD"
                    else                                    -> "neutral"
                }
                val narrative = try {
                    nd.detectNarrative(ts.symbol, ts.name).name
                } catch (_: Throwable) { "?" }
                onLog("üìñ Narrative: [$tag $narrative] size√ó${"%.2f".format(narrativeSizeMult)}", "sizing")
            }
            if (sellPressureSizeMult != 1.0 && ts != null) {
                val sp = ts.lastSellPressurePct
                val tag = when {
                    sp >= 75.0 -> "DISTRIB++"
                    sp >= 65.0 -> "DISTRIB"
                    sp <= 35.0 -> "BUYS+"
                    else       -> "neutral"
                }
                onLog("üìâ SellPress: [$tag ${sp.toInt()}%] size√ó${"%.2f".format(sellPressureSizeMult)}", "sizing")
            }
            if (rateLimitSizeMult != 1.0) {
                onLog("üöß RateLimit: size√ó${"%.2f".format(rateLimitSizeMult)} (trade rate near/at threshold)", "sizing")
            }
            if (trend1hSizeMult != 1.0 && ts != null) {
                val ch = ts.lastPriceChange1h
                val arrow = if (ch >= 0) "‚Üë" else "‚Üì"
                onLog("‚è±Ô∏è 1hTrend: $arrow${"%.1f".format(ch)}% size√ó${"%.2f".format(trend1hSizeMult)}", "sizing")
            }
            onLog("üìä AI Sizer: conf=${adjustedConfidence.toInt()} ‚Üí ${result.explanation}", "sizing")
            // V5.9.972 ‚Äî Kelly soft-cap from global lifetime stats.
            // Only ever shrinks. Skipped when <200 settled trades.
            val finalSolCapped = applyLedgerDriftCap(applyKellyCap(finalSol, walletSol, adjustedConfidence))
            return finalSolCapped
        }

        // V5.9.972 ‚Äî same Kelly cap on the non-logging path.
        val resultSolCapped = applyLedgerDriftCap(applyKellyCap(result.solAmount, walletSol, adjustedConfidence))
        return resultSolCapped
    }

    /**
     * V5.9.972 ‚Äî z PositionSizing.kellyCapFromGlobalStats revival.
     * Reads lifetime win/loss stats from TradeDatabase and applies a
     * Kelly-from-history clamp. NEVER enlarges. Allows 1.5√ó headroom
     * above raw Kelly so high-conviction AI sizing isn't crippled.
     */
    /** V5.9.1518 ‚Äî PATCH ITEM 5: hard ceiling on LIVE buy size while the ledger
     *  is drifting / reconciler is stalled. 0.05 SOL per operator spec. */
    private val LEDGER_DRIFT_MAX_LIVE_SOL: Double = 0.05

    private data class LiveSellAccounting(
        val pnlSol: Double,
        val pnlPct: Double,
        val netPnlSol: Double,
        val feeSol: Double,
    )

    /**
     * V5.0.3849 ‚Äî LIVE SELL ACCOUNTING AUTHORITY.
     *
     * Sell PnL must be computed as proceeds - allocated cost basis. It must not
     * add cost basis into proceeds, and stop-loss / hard-floor reasons must not
     * publish positive wins unless price-basis authority proves the position was
     * actually positive. This prevents STRICT_SL rows like +99% and partial rows
     * at impossible +20,000% from poisoning journal/learning/treasury.
     */
    private fun liveSellAccountingAuthority(
        ts: TokenState,
        allocatedCostSol: Double,
        proceedsSol: Double,
        reason: String,
        context: String,
    ): LiveSellAccounting {
        val safeCost = allocatedCostSol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val safeProceeds = proceedsSol.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        // V5.0.4112 ‚Äî PHANTOM_PNL_FIX (P0). When the cost basis is unknown
        // (e.g. WALLET_RECOVERED orphan: WalletReconciler sets costSol=0
        // because we don't know what the operator originally paid), the rest
        // of this function would compute pnlSol = proceeds - 0 = proceeds
        // and downstream `pct(0, proceeds)` returned 0% but the journal
        // normalizer (‚âàline 2540 below) then falls back to `sol` (proceeds)
        // as the basis, producing `netPct = proceeds / proceeds ‚âà 100%` for
        // EVERY recovered sell ‚Äî explaining the cluster of +99.6/8/9% wins
        // and the phantom STANDARD:compounding_runner WR=100% n=25 line in
        // the leaderboard, which inflated live size√ó=1.41 against REAL
        // negative-EV STANDARD trades and bled the wallet.
        //
        // Recovered positions have NO accountable PnL. Record them as
        // pnlSol=0 / pnlPct=0 (scratch) so the wallet still credits the
        // SOL we got, but the learning + sizing stack is not poisoned.
        val isUnknownCost = safeCost <= 0.0
        val tm = (ts.position.tradingMode ?: "").uppercase()
        val isRecovered = tm == "WALLET_RECOVERED" || ts.position.entryPhase == "WALLET_RECOVERED" ||
            reason.uppercase().contains("WALLET_RECOVERED") || reason.uppercase().contains("DEAD_TOKEN_NO_PRICE")
        if (isUnknownCost && isRecovered) {
            try {
                ForensicLogger.lifecycle(
                    "RECOVERED_SCRATCH_FORCED",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reason context=$context " +
                        "proceeds=${safeProceeds.fmt(6)} ‚Üí pnl=0/scratch (phantom-pnl fix V5.0.4112)",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("RECOVERED_SCRATCH_FORCED") } catch (_: Throwable) {}
            return LiveSellAccounting(pnlSol = 0.0, pnlPct = 0.0, netPnlSol = 0.0, feeSol = 0.0)
        }
        var pnlSol = safeProceeds - safeCost
        var pnlPct = pct(safeCost, safeProceeds)
        val r = reason.uppercase()
        val stopLike = r.contains("STOP") || r.contains("STRICT_SL") || r.contains("HARD_FLOOR") || r.contains("FALLBACK_ORPHAN_HARD_FLOOR")
        val impossible = !pnlPct.isFinite() || pnlPct > 5_000.0 || pnlPct < -100.0001
        val signConflict = stopLike && pnlPct > 0.5
        if (impossible || signConflict) {
            val priceVerdict = try { OpenPnlSanity.inspect(ts, "SELL_ACCOUNTING:$context", emit = true) } catch (_: Throwable) { OpenPnlSanity.Verdict(false, reason = "INSPECT_THROW") }
            val replacementPct = when {
                priceVerdict.ok && priceVerdict.pnlPct.isFinite() && priceVerdict.pnlPct in -100.0..5_000.0 -> priceVerdict.pnlPct
                signConflict -> 0.0
                else -> 0.0
            }
            val replacementPnl = safeCost * (replacementPct / 100.0)
            try {
                ForensicLogger.lifecycle(
                    "LIVE_SELL_ACCOUNTING_REPAIRED",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} context=$context reason=$reason oldPct=${pnlPct.fmt(2)} newPct=${replacementPct.fmt(2)} oldPnl=${pnlSol.fmt(6)} newPnl=${replacementPnl.fmt(6)} cost=${safeCost.fmt(6)} proceeds=${safeProceeds.fmt(6)} basis=${priceVerdict.reason}",
                )
            } catch (_: Throwable) {}
            pnlPct = replacementPct
            pnlSol = replacementPnl
        }
        val pair = slippageGuard.calcNetPnl(pnlSol, safeCost)
        return LiveSellAccounting(pnlSol, pnlPct, pair.first, pair.second)
    }

    /**
     * V5.0.3848 ‚Äî REALISTIC LIVE ENTRY SIZE AUTHORITY.
     *
     * The lane/V3 stack sometimes hands final live execution a tiny 0.005‚Äì0.009 SOL
     * "probe" even when the wallet has >1 SOL and liquidity can absorb more. That
     * cannot drive profits. This final entry-size normalizer raises dust outputs to
     * a wallet/liquidity/score-aware live size while still respecting rent reserve,
     * wallet exposure, and liquidity impact. It never bypasses safety/route gates.
     */
    /**
     * V5.0.6867 ¬ßONE_GROWTH_POLICY_SURFACE_MEANT_ONE ‚Äî this authority used to open
     * with `if (!RuntimeModeAuthority.isLive()) return requestedSol`, so everything
     * below it was LIVE-only:
     *   the LiveGrowthDoctrine wallet-% floor and cap, the liquidity-impact cap, the
     *   PaperEvBucketGate runner boost, the EarlyMoonshotHunter lift, the
     *   LiveGrowthCompounder lane-win bump and wallet-tier lift, the small-wallet
     *   turbo, and the absolute anti-dust floor.
     *
     * PAPER received none of it. Paper sized off SmartSizer's ladder times the
     * multiplier stack and stopped there. The V5.0.6418 paper-parity attempt in
     * paperBuy says so in its own note: it emits the lift and never applies it,
     * "advisory_full_wire_lands_when_paperbuy_sol_var_refactored", because `sol` is
     * a val parameter in a ~1000-line function.
     *
     * That is backwards from the design intent. LiveGrowthDoctrine calls itself
     * "the source authority for the operator's north-star" and states its scope as
     * "all meme/live trader families, lane archetypes, and trading tools share ONE
     * growth policy surface". Paper's entire purpose is to predict what live will
     * do, and it cannot do that while sizing by a different formula: every
     * expectancy it learns is measured at the wrong position size, the relative fee
     * drag on a 0.02 SOL paper ticket is nothing like a 0.25 SOL live one, and the
     * runner / moonshot / wallet-tier compounding paths that actually decide live
     * outcomes were never exercised in paper at all.
     *
     * So the authority now runs in BOTH modes. The live numbers are untouched ‚Äî they
     * are the operator's declared 2x‚Äì5x growth policy and were already what live
     * used. Paper simply sizes by the same policy, against the paper balance, using
     * the paper-scoped compounder state that already exists for exactly this
     * (consumeNextPaperBuyBump / capturePaperBaseline / paperWalletGrowthLift, added
     * as "PAPER PARITY" in V5.0.6417 and then never reached).
     */
    private fun realisticEntrySize6867(
        ts: TokenState,
        requestedSol: Double,
        walletSol: Double,
        score: Double,
        lane: String,
        source: String,
    ): Double {
        val isPaper6867 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        if (!requestedSol.isFinite() || requestedSol <= 0.0 || walletSol <= 0.0) return requestedSol
        val rentReserve = 0.012
        val spendable = (walletSol - rentReserve).coerceAtLeast(0.0)
        if (spendable <= 0.0) return requestedSol
        val solPx = try { WalletManager.lastKnownSolPrice.takeIf { it.isFinite() && it > 0.0 } ?: 104.0 } catch (_: Throwable) { 104.0 }
        val liqUsd = ts.lastLiquidityUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val laneKey = lane.ifBlank { ts.source }.uppercase()
        // V5.0.3947 ‚Äî all live traders/lanes/tools consume one source-level
        // growth doctrine here, at the final size authority used by both
        // doBuy.final and liveBuy.final. This is not a downstream lane patch:
        // every live execution path that reaches this chokepoint gets the same
        // wallet-growth policy while route/safety/wallet gates remain upstream.
        val movementSignal = try { MovementPatternSignal.from(ts) } catch (_: Throwable) { null }
        val growthPolicy = LiveGrowthDoctrine.sizePolicy(laneKey, score, walletSol, spendable, movementSignal)
        try { if (movementSignal != null) PipelineHealthCollector.labelInc("MOVEMENT_PATTERN_${movementSignal.pattern.take(48)}") } catch (_: Throwable) {}
        // V5.0.4131 ‚Äî PATTERN GOLDEN GOOSE IMPACT TOLERANCE.
        // For known-edge tokens (GOLD/WINNER patterns) accept more price impact
        // since expected return on these signatures historically dwarfs slippage
        // (theme_space 47% avg win, name_medium 237%). For unknown tokens keep
        // the conservative 2% impact cap. TOXIC/CATASTROPHIC use the standard
        // floor ‚Äî they shouldn't be sized up.
        val gooseImpactVerdict4131 = try {
            com.lifecyclebot.engine.PatternGoldenGoose.edge(ts.name, ts.symbol).verdict
        } catch (_: Throwable) { com.lifecyclebot.engine.TokenWinMemory.Verdict.NEUTRAL }
        val impactMult4131 = when (gooseImpactVerdict4131) {
            com.lifecyclebot.engine.TokenWinMemory.Verdict.GOLD    -> 4.0  // 8% impact OK
            com.lifecyclebot.engine.TokenWinMemory.Verdict.WINNER  -> 2.5  // 5% impact OK
            else                                                    -> 1.0  // standard 2%
        }
        val walletPct = growthPolicy.walletPct
        val laneMult = growthPolicy.laneMult
        val walletTarget = spendable * walletPct * laneMult
        // Intended live buy should be small relative to pool depth. Use current SOL
        // price and live liquidity to prevent unrealistic impact, but don't mistake
        // low-but-exitable liquidity for a dust-only probe.
        val liquidityCapSol = if (liqUsd > 0.0) {
            ((liqUsd * growthPolicy.liquidityImpactPct * impactMult4131) / solPx).coerceIn(0.005, spendable)
        } else {
            spendable * (growthPolicy.maxWalletPct * 0.65)
        }
        val walletCapSol = (spendable * growthPolicy.maxWalletPct).coerceAtMost(growthPolicy.absoluteCapSol)
        // V5.0.7191 ‚Äî name the binding ceiling. Until this build there was no
        // counter anywhere saying "the absolute cap decided this trade's size",
        // so the terminal `else -> 3.000` rung could override every lane's
        // designed share indefinitely without producing a single line of
        // evidence. The snapshot showed the sizing inputs and never showed
        // which one actually won. A ceiling that silently outranks the design
        // is the same defect class as a gate whose output is structurally
        // constant: invisible by construction.
        //
        // laneShare vs absolute tells the operator WHICH ceiling bound, so the
        // next cap question is answered by the log instead of by reading
        // Executor top to bottom.
        val laneShareSol7191 = spendable * growthPolicy.maxWalletPct
        if (laneShareSol7191 > growthPolicy.absoluteCapSol) {
            try {
                PipelineHealthCollector.labelInc("ENTRY_SIZE_ABSOLUTE_CAP_BOUND_7191")
                ForensicLogger.lifecycle(
                    "ENTRY_SIZE_ABSOLUTE_CAP_BOUND_7191",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$laneKey " +
                        "wallet=${walletSol.fmt(4)} laneShare=${laneShareSol7191.fmt(4)} " +
                        "absoluteCap=${growthPolicy.absoluteCapSol.fmt(4)} bound=ABSOLUTE",
                )
            } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("ENTRY_SIZE_LANE_SHARE_BOUND_7191") } catch (_: Throwable) {}
        }
        // V5.0.6408 ‚Äî GROWTH-CENTRIC RUNNER COMPOUNDING.
        // Operator directive: '$50 to $1M mindset ‚Äî maintain growth-centric
        // trading, defend the wallet but target overall growth'. When
        // PaperEvBucketGate6405 flags a proven-winner bucket, relax the
        // wallet cap: 1.5√ó base for a 'winner' (1.5x multiplier) or 2.0√ó
        // base for an 'elite' (2.0x multiplier) ‚Äî with the liquidityCapSol
        // and spendable still being hard ceilings. This lets a healthy
        // wallet actually deploy real capital into what works, while
        // untested / losing buckets remain on the conservative growthPolicy
        // maxWalletPct.
        val runnerBoost6408 = try {
            com.lifecyclebot.engine.truth.PaperEvBucketGate6405.sizeMultiplier(
                mint = ts.mint, symbol = ts.symbol, lane = laneKey,
                // V5.0.6867 ‚Äî was hardcoded false because this authority only ever
                // ran in live. Pass the real mode now that it runs in both.
                scoreInt = score.toInt(), isPaper = isPaper6867,
            )
        } catch (_: Throwable) { 1.0 }
        // V5.0.6415 ‚Äî EARLY MOONSHOT HUNTER (sub-$25k mcap runner).
        // Operator directive: "several tokens have 26x or better this week
        // we need to find them before 25k buy a good sized chunk and hold
        // for huge profits. it needs to be smart, learn and integrate
        // across the stack, wire it thru."
        val moonshot6415 = try {
            val vol1h = ts.tokenMap.volume1hUsd ?: 0.0
            val sourceCount6415 = maxOf(1, ts.laneAffinity.size)
            val totalPressure = (ts.lastBuyPressurePct + ts.lastSellPressurePct).coerceAtLeast(1.0)
            val buysApprox = ((ts.lastBuyPressurePct / totalPressure) * 10.0).toInt().coerceAtLeast(0)
            val sellsApprox = ((ts.lastSellPressurePct / totalPressure) * 10.0).toInt().coerceAtLeast(0)
            val rugSafe = try {
                val s = ts.safety
                s.freezeAuthorityDisabled == true &&
                    s.mintAuthorityDisabled == true &&
                    s.tier != com.lifecyclebot.engine.SafetyTier.HARD_BLOCK
            } catch (_: Throwable) { false }
            com.lifecyclebot.engine.truth.EarlyMoonshotHunter6415.scoreCandidate(
                mint = ts.mint, symbol = ts.symbol,
                mcapUsd = ts.lastMcap,
                liquidityUsd = ts.lastLiquidityUsd,
                vol1hUsd = vol1h,
                sourceCount = sourceCount6415,
                buysLastWindow = buysApprox,
                sellsLastWindow = sellsApprox,
                rugSafetyConfirmed = rugSafe,
            )
        } catch (_: Throwable) { null }
        val moonshotMult6415 = moonshot6415?.sizeMult ?: 1.0
        if (moonshot6415 != null && moonshotMult6415 > 1.0) {
            try {
                com.lifecyclebot.engine.truth.EarlyMoonshotHunter6415.registerHoldProfile(ts.mint, ts.symbol, moonshot6415)
            } catch (_: Throwable) {}
        }
        // Compose the runner boost with the moonshot lift. Both are size-side
        // relaxations; multiplying them lets a bucket-winner *AND* moonshot
        // candidate stack (still bounded by wallet + liquidity below).
        val effectiveBoost6415 = runnerBoost6408 * moonshotMult6415
        // V5.0.6416 ‚Äî LIVE GROWTH COMPOUNDER (lane-win-primed bump + wallet-tier lift).
        // Operator directive: "wallet balance isnt increasing again on live or paper
        // trading. it needs to be more growth centric especially live trading."
        // Compose two additive lifts on top of the runner/moonshot boost:
        //   ¬ßA next-buy bump from a recent lane win (1.0..2.0√ó, 5min TTL, one-shot)
        //   ¬ßB wallet growth tier lift (1.0/1.10/1.20/1.35 based on currentSol/baseline)
        // Both bounded by liquidityCapSol + spendable in the cap calculation below.
        // V5.0.6867 ‚Äî mode-scoped compounder state. LiveGrowthCompounder6416 keeps
        // paper and live in separate maps on purpose ("so paper wins don't bump live
        // buys and vice-versa"), and the paper half has existed since V5.0.6417 with
        // no caller that applies it. Route to the matching half.
        val laneWinBump6416 = try {
            if (isPaper6867) {
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.consumeNextPaperBuyBump(
                    lane = laneKey, mint = ts.mint, symbol = ts.symbol,
                )
            } else {
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.consumeNextBuyBump(
                    lane = laneKey, mint = ts.mint, symbol = ts.symbol,
                )
            }
        } catch (_: Throwable) { 1.0 }
        val walletTierLift6416 = try {
            if (isPaper6867) {
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.capturePaperBaseline(walletSol)
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.paperWalletGrowthLift(walletSol)
            } else {
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.captureBaseline(walletSol)
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.walletGrowthLift(walletSol)
            }
        } catch (_: Throwable) { 1.0 }
        // V5.0.6956 ¬ßTHE_COMPOUNDER_WHOSE_ONLY_READER_WAS_A_UNIT_TEST.
        //
        // Operator, repeatedly: "wallet balance isnt increasing again on live or
        // paper trading. it needs to be more growth centric."
        //
        // RunnerAutoCompound6422 is the module built for exactly that. Its FEED
        // is fully wired ‚Äî onPaperClose and onLiveClose are called on every
        // terminal close, so it has been tracking win streaks (RUNNER, TURBO,
        // SUPER, MEGA) and extended wallet-growth tiers 4/5/6 the entire time.
        //
        // Its OUTPUT went nowhere. paperStreakMultiplier and liveStreakMultiplier
        // have exactly one external caller each, and that caller is
        // InvariantSelfCheck6430 ‚Äî a SELF-TEST. paperTotalLift, liveTotalLift,
        // paperExtendedTierLift and liveExtendedTierLift have zero callers of any
        // kind. So the compounder computed a lift on every close and the only
        // thing in the entire binary that ever read one was an assertion about
        // itself. The bot has never once compounded a winning streak into size.
        //
        // The extended tiers are the sharper half: LiveGrowthCompounder6416's
        // walletGrowthLift caps out at 3x wallet growth, and 6422's tiers 4/5/6
        // exist precisely to carry past that cap. A bot that actually tripled its
        // bankroll would have stopped scaling at exactly the moment scaling
        // started to matter.
        //
        // Mode-scoped, matching the isPaper6867 split directly above: paper
        // streaks must not lift live buys and vice versa. The ratio is
        // currentWallet/baseline from the matching half of 6416, which is the
        // same baseline walletTierLift6416 is already computed against ‚Äî one
        // definition of wallet growth, not a second one invented here.
        //
        // This multiplies INTO growthLift6416, so it passes through the
        // canExpandRisk gate added in 6950: a winning streak compounds, and a
        // streak that runs while the wallet is under its 24h high does not. That
        // ordering is deliberate ‚Äî compounding into a drawdown is the reward
        // hacking 6439 exists to refuse.
        val runnerCompoundLift6956 = try {
            val baseline6956 = if (isPaper6867) {
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.paperBaselineSolOrNull()
            } else {
                com.lifecyclebot.engine.truth.LiveGrowthCompounder6416.liveBaselineSolOrNull()
            }
            val ratio6956 = if (baseline6956 != null && baseline6956 > 0.0 && walletSol > 0.0)
                walletSol / baseline6956 else 1.0
            val lift6956 = if (isPaper6867) {
                com.lifecyclebot.engine.truth.RunnerAutoCompound6422.paperTotalLift(ratio6956)
            } else {
                com.lifecyclebot.engine.truth.RunnerAutoCompound6422.liveTotalLift(ratio6956)
            }
            if (lift6956.isFinite() && lift6956 >= 1.0) {
                if (lift6956 > 1.0) {
                    try {
                        ForensicLogger.lifecycle(
                            "RUNNER_AUTO_COMPOUND_APPLIED_6956",
                            "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$laneKey " +
                                "mode=${if (isPaper6867) "PAPER" else "LIVE"} wallet=${walletSol.fmt(4)} " +
                                "baseline=${baseline6956?.let { it.fmt(4) } ?: "-"} ratio=${"%.2f".format(ratio6956)} " +
                                "lift=${"%.2f".format(lift6956)} status=${com.lifecyclebot.engine.truth.RunnerAutoCompound6422.statusLine()}",
                        )
                        PipelineHealthCollector.labelInc("RUNNER_AUTO_COMPOUND_APPLIED_6956")
                    } catch (_: Throwable) {}
                }
                lift6956
            } else 1.0
        } catch (_: Throwable) { 1.0 }
        val growthLift6416 = laneWinBump6416 * walletTierLift6416 * runnerCompoundLift6956
        // V5.0.6950 ¬ßTHE_GUARD_THAT_WATCHED_AND_WAS_NEVER_ASKED.
        //
        // AntiRewardHackingGuard6439 exists for one operator directive: "Bad
        // behaviour should NEVER be seen or recognised as good behaviour."
        // Learners rationalise expanding risk after a loss ‚Äî "I lost because my
        // size was too small, next time bigger" ‚Äî and that wipes accounts.
        //
        // Its observation half IS wired: BotService feeds observeWalletBalance
        // every loop, so the rolling 24h wallet high has been tracked accurately
        // this whole time. Its DECISION half, canExpandRisk, had zero callers.
        // The file's own header states the contract it expected ‚Äî "every learner
        // asks: canExpandRisk(currentWalletSol) -> Boolean and only proceeds with
        // an expand-risk tune if the answer is true" ‚Äî and no learner ever did.
        // So the bot tracked its own drawdown precisely and then sized up
        // through it regardless, which is the exact behaviour the guard was
        // written to prevent.
        //
        // This is the right single gate: totalBoost6416 is where EVERY expansion
        // path converges ‚Äî runner boost, moonshot multiplier, lane-win bump and
        // wallet-tier lift all multiply into it, and walletSol is in scope.
        //
        // IT CLAMPS TO 1.0, IT NEVER GOES BELOW. This declines to ADD risk while
        // the wallet is under its 24h high; it does not cut the base size. That
        // matters ‚Äî the doctrine is "never throttle, never cap-to-dust, never
        // disable a lane", and a boost of 1.0 is the unboosted baseline, not a
        // throttle. Every lane keeps trading at full normal size.
        //
        // smallWalletTurbo6409 below is deliberately NOT gated: it is an explicit
        // operator directive for bankrolls under 0.2 SOL, where the 24h high is
        // noise, and it is already bounded by liquidity and spendable.
        val canExpandRisk6950 = try {
            com.lifecyclebot.engine.truth.AntiRewardHackingGuard6439.canExpandRisk(walletSol)
        } catch (_: Throwable) { true }
        val totalBoost6416 = if (!canExpandRisk6950) {
            val wanted6950 = effectiveBoost6415 * growthLift6416
            if (wanted6950 > 1.0) {
                try {
                    ForensicLogger.lifecycle(
                        "RISK_EXPANSION_VETOED_DRAWDOWN_6950",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$laneKey " +
                            "wallet=${walletSol.fmt(4)} wantedBoost=$wanted6950 " +
                            "runnerBoost=$runnerBoost6408 moonshotMult=$moonshotMult6415 " +
                            "growthLift=$growthLift6416 applied=1.0 reason=below_24h_wallet_high " +
                            "note=baseline_size_unchanged_no_lane_throttled",
                    )
                    PipelineHealthCollector.labelInc("RISK_EXPANSION_VETOED_DRAWDOWN_6950")
                } catch (_: Throwable) {}
            }
            1.0
        } else effectiveBoost6415 * growthLift6416
        val walletCapSol6408 = if (totalBoost6416 > 1.0) {
            val relaxed = walletCapSol * totalBoost6416
            try {
                ForensicLogger.lifecycle(
                    "GROWTH_RUNNER_CAP_RELAX_6408",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$laneKey boost=$effectiveBoost6415 runnerBoost=$runnerBoost6408 moonshotMult=$moonshotMult6415 " +
                        "walletCap=${walletCapSol.fmt(4)} relaxed=${relaxed.fmt(4)} spendable=${spendable.fmt(4)}",
                )
                PipelineHealthCollector.labelInc("GROWTH_RUNNER_CAP_RELAX_6408")
            } catch (_: Throwable) {}
            relaxed.coerceAtMost(spendable)
        } else walletCapSol
        // V5.0.6409 ¬ß3 ‚Äî SMALL-WALLET TURBO.
        // Operator directive: "When wallet < 0.2 SOL AND runnerBoost active,
        // allow the entire liquidity-safe amount (skip the doctrine cap
        // entirely) so early growth compounds faster from a tiny bankroll."
        // Bounded by liquidityCapSol and spendable, so this never overspends
        // beyond what the pool can absorb or the wallet actually holds.
        val smallWalletTurbo6409 = spendable < 0.20 && runnerBoost6408 > 1.0
        val cap = if (smallWalletTurbo6409) {
            val turboCap = minOf(liquidityCapSol, spendable)
            try {
                ForensicLogger.lifecycle(
                    "SMALL_WALLET_TURBO_6409",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$laneKey wallet=${walletSol.fmt(4)} " +
                        "spendable=${spendable.fmt(4)} runnerBoost=$runnerBoost6408 " +
                        "walletCapBypassed=${walletCapSol6408.fmt(4)} turboCap=${turboCap.fmt(4)}",
                )
                PipelineHealthCollector.labelInc("SMALL_WALLET_TURBO_6409")
            } catch (_: Throwable) {}
            turboCap
        } else {
            minOf(liquidityCapSol, walletCapSol6408, spendable)
        }
        val minRealistic = growthPolicy.minExecutableSol.coerceAtMost(cap)
        val desired = maxOf(requestedSol, walletTarget, minRealistic).coerceAtMost(cap)
        val out = desired.coerceAtLeast(minOf(requestedSol, cap)).coerceAtMost(spendable)
        // V5.0.4131 ‚Äî ABSOLUTE FLOOR. The user's mandate: "literally everything is
        // basically .01 sol buys" ‚Äî unsustainable. When the wallet is healthy AND
        // the token has at least minimum exitable liquidity ($500), guarantee a
        // floor of MIN_ENTRY_SOL (0.040 SOL ‚âà $4). TOXIC/CATASTROPHIC verdicts
        // bypass this floor (they shouldn't be sized up).
        val absFloor4131 = com.lifecyclebot.engine.LiveSizingProfile.MIN_ENTRY_SOL
        val walletHealthy = spendable > absFloor4131 * 3.0   // 3√ó headroom for fees + slippage
        val liquidityAdequate = liqUsd >= 500.0               // exitable
        val skipFloorForToxic = gooseImpactVerdict4131 == com.lifecyclebot.engine.TokenWinMemory.Verdict.TOXIC ||
                                 gooseImpactVerdict4131 == com.lifecyclebot.engine.TokenWinMemory.Verdict.CATASTROPHIC
        val outWithFloor = if (walletHealthy && liquidityAdequate && !skipFloorForToxic) {
            maxOf(out, absFloor4131).coerceAtMost(spendable)
        } else out
        if (outWithFloor > out + 0.0005) {
            try { ForensicLogger.lifecycle("LIVE_ABS_FLOOR_LIFT_V4131", "symbol=${ts.symbol} lane=$laneKey out=${out.fmt(4)} ‚Üí ${outWithFloor.fmt(4)} (goose=${gooseImpactVerdict4131.name} liq=${liqUsd.toInt()} wallet=${walletSol.fmt(3)})") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("LIVE_ABS_FLOOR_LIFT_V4131") } catch (_: Throwable) {}
        }
        try {
            ForensicLogger.lifecycle(
                "GROWTH_MODE_TRACE",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} source=$source lane=$laneKey requested=${requestedSol.fmt(4)} walletTarget=${walletTarget.fmt(4)} out=${outWithFloor.fmt(4)} wallet=${walletSol.fmt(4)} spendable=${spendable.fmt(4)} liqUsd=${liqUsd.toInt()} liquidityCap=${liquidityCapSol.fmt(4)} walletCap=${walletCapSol.fmt(4)} cap=${cap.fmt(4)} minExec=${minRealistic.fmt(4)} score=${score.fmt(1)} growth=${growthPolicy.reason} goose=${gooseImpactVerdict4131.name}",
            )
        } catch (_: Throwable) {}
        if (kotlin.math.abs(outWithFloor - requestedSol) >= 0.0005) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_REALISTIC_SIZE_AUTHORITY",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} source=$source lane=$laneKey requested=${requestedSol.fmt(4)} target=${walletTarget.fmt(4)} out=${outWithFloor.fmt(4)} wallet=${walletSol.fmt(4)} spendable=${spendable.fmt(4)} liqUsd=${liqUsd.toInt()} cap=${cap.fmt(4)} score=${score.fmt(1)} growth=${growthPolicy.reason}",
                )
            } catch (_: Throwable) {}
        }
        return outWithFloor
    }

    private fun applyKellyCap(size: Double, walletSol: Double, confidence: Double): Double {
        return try {
            val kellyCap = com.lifecyclebot.engine.PositionSizing.kellyCapFromGlobalStats(
                walletSol = walletSol,
                config = cfg(),
                modeMultiplier = 1.0,
                confidence = confidence,
            ) ?: return size  // null = stats too thin ‚Üí no-op
            val cap = kellyCap * 1.5
            if (size > cap) {
                onLog("üìê KellyCap: ${"%.4f".format(size)} ‚Üí ${"%.4f".format(cap)} SOL (lifetime Kelly+50% headroom)", "sizing")
                cap
            } else size
        } catch (_: Throwable) { size }
    }

    /**
     * V5.0.7162 ¬ßCOST WAS EATING 72% OF THE LOSS AND NOTHING WAS LOOKING AT IT.
     *
     * Operator's paper ledger, 5.0.7155:
     *
     *     realized = -0.8659 SOL      fees = 0.6224 SOL
     *
     * Gross of costs the book is about -0.24 SOL. Net of costs it is -0.87.
     * Fees are SEVENTY-TWO PERCENT of the realised loss. That is not a
     * strategy result, it is a cost structure, and no edge survives it.
     *
     * The mechanism is the sizing stack. Roughly twenty-five multipliers
     * compound downward ‚Äî ColdStreakDamper x0.25, laneCap 12%, regime DUMP
     * x0.35, laneBias x0.50 ‚Äî and every one of them SHRINKS. The order then
     * lands on the 0.05 SOL floor. Round-trip cost (buy slip + liquidity-
     * implied sell slip + priority fee + 1% platform + spread + MEV) is
     * near-fixed per trade, so shrinking the notional raises cost as a
     * PERCENTAGE of the position. A configuration that maximises trade count
     * and minimises trade size is a cost-maximising configuration.
     *
     * The bot already computes both halves of the comparison and has never
     * made it: LiveBreakEvenGuard.requiredEdgePct is this trade's full
     * round-trip cost, and expectedEdgePct is its forecast upside. 7149 put
     * both on the Position, but only AFTER the buy ‚Äî as a record, not a
     * decision.
     *
     * So make the decision. When the forecast edge cannot clear the cost of
     * the round trip, REFUSE rather than shrink. A trade whose expected move
     * is smaller than its fee is a donation, and taking it at dust size
     * donates slightly less while still paying the fixed leg.
     *
     * DELIBERATELY NARROW, because throughput is the other half of the
     * operator's goal and this must not become a new choke:
     *   - Both figures must be present and positive. An absent forecast is
     *     NOT evidence of no edge ‚Äî that is the defect this whole run of
     *     builds removed ‚Äî so unknown proceeds untouched.
     *   - A margin is required, not equality, so marginal trades still go.
     *   - Refusal is reported per lane, so its cost in throughput is
     *     measurable and reversible rather than invisible.
     */
    // V5.0.7163 ‚Äî plain `val`, not `const val`. Executor is a CLASS, and
    // Kotlin allows const only at top level, in a named object, or in a
    // companion. The const vals added earlier this run compiled because
    // their owners (KeylessLlmClient, LearnedPolicyDegeneracyWatch7102) are
    // objects; this one is not, and 7162 went red on exactly that line.
    private val COST_EDGE_MARGIN_7162 = 1.15

    private fun costExceedsEdge7162(
        ts: TokenState,
        sizeSol: Double,
        score: Double,
        lane: String,
    ): Boolean {
        if (sizeSol <= 0.0) return false
        return try {
            val laneKey = lane.ifBlank { "STANDARD" }
            val required = com.lifecyclebot.engine.LiveBreakEvenGuard.requiredEdgePct(
                ts, laneKey, laneKey, cfg().slippageBps, sizeSol, score,
            )
            val expected = com.lifecyclebot.engine.LiveBreakEvenGuard.expectedEdgePct(laneKey, score)
            // Absence is not a fact: no cost model or no forecast means no
            // opinion, and no opinion means do not block.
            if (!required.isFinite() || required <= 0.0) return false
            if (!expected.isFinite()) return false
            // V5.0.7162b ¬ßZERO FROM THIS FUNCTION IS SOMETIMES A MEASUREMENT.
            //
            // expectedEdgePct gates EVERY evidence term on winRatePct >= 45
            // and totalSolPnl > 0 (LiveBreakEvenGuard:36, :47, :56). For the
            // operator's bleeders ‚Äî EXPRESS 0% WR / -0.128 SOL, BLUECHIP 0% /
            // -0.0999, QUALITY 5.9% / -0.29, CORE 0% / -0.046 ‚Äî every term
            // therefore returns 0.0 and only the score prior survives.
            //
            // So a 0.0 here means EITHER "no evidence" OR "evidence, and it
            // says no edge", and the return value alone cannot tell them
            // apart. Treating both as "proceed" ‚Äî which is what I wrote first
            // ‚Äî would let exactly the four lanes bleeding the book keep
            // paying full round-trip cost, which is the thing this gate
            // exists to stop.
            //
            // LaneExpectancyDamper can tell them apart. A non-neutral
            // multiplier means it has enough closes to hold an opinion; on
            // that snapshot it read EXPRESS x0.39, QUALITY x0.52, CORE x0.73,
            // BLUECHIP x0.73 against CYCLIC x1.00 and PROJECT_SNIPER x1.00.
            // Mature evidence plus zero forecast edge against a real cost is
            // a refusal. No evidence is still no opinion, and still proceeds.
            //
            // This is item 2 of the operator's plan ‚Äî stop the bleeders ‚Äî
            // arriving as a consequence of item 1 rather than as a second
            // hand-picked EV threshold. The lanes keep discovering,
            // qualifying, rotating tactics and learning; they just stop
            // paying fees to relearn what they have already shown. And the
            // damper returns to neutral if expectancy recovers, so this
            // releases itself without an operator unpause.
            if (expected <= 0.0) {
                val damperOpinion7162 = try {
                    com.lifecyclebot.engine.LaneExpectancyDamper.sizeMultiplier(laneKey)
                } catch (_: Throwable) { 1.0 }
                val laneHasEvidence7162 =
                    damperOpinion7162.isFinite() && kotlin.math.abs(damperOpinion7162 - 1.0) > 1e-9
                if (!laneHasEvidence7162) return false
                try {
                    PipelineHealthCollector.labelInc("COST_EDGE_ZERO_WITH_LANE_EVIDENCE_7162")
                } catch (_: Throwable) {}
            }
            val blocked = expected < required * COST_EDGE_MARGIN_7162
            if (blocked) {
                try {
                    PipelineHealthCollector.labelInc("COST_EXCEEDS_EDGE_REFUSED_7162")
                    PipelineHealthCollector.labelInc(
                        "COST_EXCEEDS_EDGE_REFUSED_7162_${laneKey.uppercase().take(20)}",
                    )
                    ForensicLogger.lifecycle(
                        "COST_EXCEEDS_EDGE_REFUSED_7162",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$laneKey " +
                            "sizeSol=${"%.4f".format(sizeSol)} score=${"%.1f".format(score)} " +
                            "expectedEdgePct=${"%.2f".format(expected)} requiredEdgePct=${"%.2f".format(required)} " +
                            "margin=$COST_EDGE_MARGIN_7162 liqUsd=${ts.lastLiquidityUsd.toInt()} " +
                            "action=refuse_not_shrink_a_trade_smaller_than_its_fee_is_a_donation",
                    )
                } catch (_: Throwable) {}
            } else {
                try { PipelineHealthCollector.labelInc("COST_EDGE_CLEARED_7162") } catch (_: Throwable) {}
            }
            blocked
        } catch (_: Throwable) { false }
    }

    /**
     * V5.9.1518 / V5.0.4202 ‚Äî cap LIVE buy size only for REAL unresolved ledger drift.
     * Runtime 4199/4201 pictures showed InvariantGuardian/LLM reporting
     * reconciler.totalChecked=0 while LiveWalletReconciler logs had checked=2.
     * Root pattern: the sizing cap read PositionWalletReconciler only, while
     * RuntimeStateSnapshot uses the effective reconciler truth across position,
     * sell, and live-wallet reconcilers. That stale surface silently dust-capped
     * live buys even when wallet truth was being checked.
     *
     * Keep catastrophic protection, but do not throttle growth for a single
     * canonical>wallet drift when an active sell/lease is already resolving it
     * and any reconciler has checked wallet/position truth.
     */
    private fun applyLedgerDriftCap(size: Double): Double {
        return try {
            if (isPaperRT()) return size
            val snap = com.lifecyclebot.engine.execution.PositionWalletReconciler.snapshot()
            val positionChecked = snap.totalChecked
            val sellChecked = try { com.lifecyclebot.engine.sell.SellReconciler.totalChecked.toInt() } catch (_: Throwable) { 0 }
            val liveWalletChecked = try { com.lifecyclebot.engine.sell.LiveWalletReconciler.totalChecked() } catch (_: Throwable) { 0 }
            val effectiveChecked = maxOf(positionChecked, sellChecked, liveWalletChecked)
            val canonical = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { 0 }
            val walletHeld = try { com.lifecyclebot.engine.HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { 0 }
            val proofGrace6019 = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenAwaitingWalletProofCount(90_000L) } catch (_: Throwable) { 0 }
            val driftCount = (canonical - walletHeld - proofGrace6019).coerceAtLeast(0)
            val activeSellResolution = try {
                com.lifecyclebot.engine.sell.CloseLease.activeBlockingLeaseCount() +
                    com.lifecyclebot.engine.sell.SellJobRegistry.activeCount()
            } catch (_: Throwable) { 0 }
            val benignSingleDriftResolving = driftCount <= 1 && effectiveChecked > 0 && activeSellResolution > 0
            val reconcilerStalled = effectiveChecked == 0 && canonical > 0
            val unresolvedDrift = driftCount > 0 && !benignSingleDriftResolving
            if ((unresolvedDrift || reconcilerStalled) && size > LEDGER_DRIFT_MAX_LIVE_SOL) {
                onLog("üõ° LedgerDriftCap: ${"%.4f".format(size)} ‚Üí ${"%.4f".format(LEDGER_DRIFT_MAX_LIVE_SOL)} SOL (canonical=$canonical walletHeld=$walletHeld drift=$driftCount checked=$effectiveChecked pos=$positionChecked sell=$sellChecked live=$liveWalletChecked activeSell=$activeSellResolution)", "sizing")
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("LIVE_SIZE_CAPPED_LEDGER_DRIFT", "size=${"%.4f".format(size)} cap=$LEDGER_DRIFT_MAX_LIVE_SOL canonical=$canonical walletHeld=$walletHeld drift=$driftCount checked=$effectiveChecked posChecked=$positionChecked sellChecked=$sellChecked liveWalletChecked=$liveWalletChecked activeSell=$activeSellResolution") } catch (_: Throwable) {}
                LEDGER_DRIFT_MAX_LIVE_SOL
            } else {
                if (benignSingleDriftResolving) {
                    try { com.lifecyclebot.engine.ForensicLogger.lifecycle("LIVE_LEDGER_DRIFT_CAP_BYPASS_RESOLVING_4202", "canonical=$canonical walletHeld=$walletHeld drift=$driftCount checked=$effectiveChecked activeSell=$activeSellResolution action=size_normal") } catch (_: Throwable) {}
                }
                size
            }
        } catch (_: Throwable) { size }
    }

    /**
     * Calculate buy size for FDG evaluation.
     * Simplified wrapper around buySizeSol for the Final Decision Gate.
     */
    fun calculateBuySize(
        ts: TokenState,
        walletSol: Double,
        totalExposureSol: Double,
        openPositionCount: Int,
        quality: String,
    ): Double {
        return buySizeSol(
            entryScore = ts.entryScore,
            walletSol = walletSol,
            currentOpenPositions = openPositionCount,
            currentTotalExposure = totalExposureSol,
            walletTotalTrades = 0,  // Not critical for size calc
            liquidityUsd = ts.lastLiquidityUsd,
            mcapUsd = ts.lastMcap,
            aiConfidence = 50.0,  // Default confidence for FDG size calc
            phase = ts.phase,
            source = ts.source,
            brain = brain,
            setupQuality = quality,
            ts = ts,  // V5.9.69: enable PatternClassifier
        )
    }
    
    /**
     * V5.0.4162 ‚Äî canonical execution-lane resolver.
     *
     * Root fix: source labels (PUMP_FUN_NEW / DATA_ORCHESTRATOR /
     * MEME_REGISTRY_RESTORE) were being used as lane keys inside the universal
     * doBuy() size/discipline choke. That poisoned LanePolicy, LaneTimeoutGate,
     * LiveStrategyTuner, UnifiedPolicyHead, ScannerLaneBridge, and journal/close
     * attribution by training/scaling SOURCE buckets instead of real MemeTrader
     * lanes. This resolver returns only canonical lane names; discovery source
     * stays a separate dimension.
     */
    private fun normalizeExecutionLane(raw: String?): String {
        val u0 = raw?.trim()?.uppercase().orEmpty()
        if (u0.isBlank()) return ""
        val u = u0.replace('-', '_').replace(' ', '_')
        return when {
            u == "BLUE_CHIP" || u == "BLUE_CHIPS" || u == "BLUECHIPS" -> "BLUECHIP"
            u == "SHITCOIN_EXPRESS" || u == "SHITCOINEXPRESS" -> "EXPRESS"
            u.startsWith("MOONSHOT_") -> "MOONSHOT"
            u in setOf(
                "STANDARD", "CORE", "V3", "MEME", "SHITCOIN", "MOONSHOT", "EXPRESS",
                "QUALITY", "BLUECHIP", "TREASURY", "CASHGEN", "CYCLIC", "MANIPULATED",
                "DIP_HUNTER", "PROJECT_SNIPER", "PRESALE_SNIPE", "WHALE_FOLLOW",
                "COPYTRADE", "NETWORK_SIGNAL", "WALLET_RECOVERED"
            ) -> u
            else -> ""
        }
    }

    private fun resolveExecutionLane(ts: TokenState, identity: TradeIdentity? = null, fallback: String = "STANDARD"): String {
        val explicit = normalizeExecutionLane(identity?.executionLane)
        if (explicit.isNotBlank()) return explicit
        val cyclePrimary = try { normalizeExecutionLane(com.lifecyclebot.engine.TokenMetricStageRouter.preferredPrimaryLane(ts, "")) } catch (_: Throwable) { "" }
        if (cyclePrimary.isNotBlank()) return cyclePrimary
        val committedLegacy = if (ts.position.isOpen) normalizeExecutionLane(ts.position.tradingMode) else ""
        if (committedLegacy.isNotBlank()) return committedLegacy
        try {
            PipelineHealthCollector.labelInc("EXEC_LANE_IDENTITY_INVARIANT_FAILED")
            ForensicLogger.lifecycle("EXEC_LANE_IDENTITY_INVARIANT_FAILED", "mint=${ts.mint.take(10)} symbol=${ts.symbol} candidateVersion=${identity?.fdgCandidateVersion ?: 0L} explicit=${identity?.executionLane} cycle=$cyclePrimary position=${ts.position.tradingMode} identitySource=${identity?.source} tokenSource=${ts.source} fallback=$fallback")
        } catch (_: Throwable) {}
        return fallback
    }

    /**
     * Record a trade to both TokenState and persistent TradeHistoryStore
     */
    private fun recordTrade(ts: TokenState, trade: Trade) {
        // V5.0.6324 ‚Äî ACCOUNTING IDEMPOTENCY GATE (operator hotfix ¬ß13).
        // A single confirmed transaction can arrive via websocket +
        // polling + wallet reconciliation + retry sweep. Each callback
        // used to write another journal row + governor mutation. Reject
        // duplicates keyed on {signature|mint|side}. Blank signatures
        // (early pre-broadcast estimates) always pass; those are
        // superseded by the canonical position authority ladder once a
        // real signature lands.
        try {
            val idempotencySig = trade.sig
            if (idempotencySig.isNotBlank() && !idempotencySig.startsWith("PHANTOM_")) {
                val mintForKey = if (trade.mint.isBlank()) ts.mint else trade.mint
                val actionForKey = trade.side.uppercase()
                val ok = com.lifecyclebot.engine.AccountingIdempotencyRegistry.claim(
                    idempotencySig, mintForKey, actionForKey,
                    reasonForLog = "recordTrade/${trade.reason.take(40)}",
                )
                if (!ok) return
            }
            // V5.0.6508 ¬ßP0-2 ‚Äî PAPER CLOSE IDEMPOTENCY (STABLE closeId).
            // Paper trades usually have a blank `sig` so the classic
            // AccountingIdempotencyRegistry skip fires and the caller
            // could produce duplicate journal rows (operator-observed
            // 3√ó rndriz / 6√ó BLUE_CHIP mode_maxhold_zombie_de duplicates).
            // Synthesize a stable closeId for paper terminal SELLs.
            //
            // V5.0.6508a ‚Äî operator screenshot revealed journal rows
            // land with positionId="" (`id=unlinked`), so the original
            // `trade.positionId.isNotBlank()` guard NEVER triggered
            // the dedup. Broadened: use positionId when present,
            // otherwise fall back to (mint + reason + 5-second tsBucket)
            // as the idempotency key. tsBucket=5s tolerates repeated
            // exit paths firing in the same tick without collapsing
            // legitimate re-entries.
            if (idempotencySig.isBlank() &&
                trade.mode.equals("paper", ignoreCase = true) &&
                trade.side.equals("SELL", ignoreCase = true) &&
                !trade.reason.startsWith("partial", ignoreCase = true) &&
                !trade.reason.contains("profit_lock", ignoreCase = true) &&
                !trade.reason.contains("capital_recovery", ignoreCase = true) &&
                !trade.reason.contains("wr_recovery_partial", ignoreCase = true)) {
                val mintForKey6508 = if (trade.mint.isBlank()) ts.mint else trade.mint
                if (mintForKey6508.isNotBlank()) {
                    val closeId6508 = if (trade.positionId.isNotBlank()) {
                        "PAPER:${trade.positionId}:${trade.reason.take(40)}"
                    } else {
                        // 5-second bucket so same-tick exit-path storms
                        // collapse; legitimate re-entries land in the
                        // next bucket window.
                        val tsBucket6508 = trade.ts / 5_000L
                        "PAPER:${mintForKey6508}:${trade.reason.take(40)}:${tsBucket6508}"
                    }
                    val ok6508 = com.lifecyclebot.engine.AccountingIdempotencyRegistry.claim(
                        closeId6508, mintForKey6508, "SELL",
                        reasonForLog = "paperClose6508/${trade.reason.take(40)}",
                    )
                    if (!ok6508) {
                        try {
                            ForensicLogger.lifecycle(
                                "PAPER_CLOSE_JOURNAL_DUPLICATE_SUPPRESSED_6508",
                                "mint=${mintForKey6508.take(10)} symbol=${ts.symbol} " +
                                    "positionId=${trade.positionId.take(24).ifBlank { "unlinked" }} " +
                                    "closeId=$closeId6508 " +
                                    "reason=${trade.reason.take(40)} " +
                                    "action=drop_duplicate_journal_row",
                            )
                            PipelineHealthCollector.labelInc("PAPER_CLOSE_JOURNAL_DUPLICATE_SUPPRESSED_6508")
                        } catch (_: Throwable) {}
                        return
                    }
                }
            }
        } catch (_: Throwable) {}
        // V5.9.791 ‚Äî operator audit Item 1 + 2: PositionExitArbiter chokepoint.
        // Without this, a cascade of exit reasons (CASHGEN_STOP_LOSS firing
        // simultaneously with STRICT_SL and RAPID_CATASTROPHE_STOP on the
        // same Position) would each push a fresh Trade row + canonical
        // outcome + ToxicModeCircuitBreaker loss + MetaCognition outcome
        // + RunTracker30D row, polluting every learner and the journal.
        // Arbitrate ONLY for terminal SELLs (skip BUY + partial SELLs).
        //
        // V5.9.800 ‚Äî operator audit FIX: profit-lock / capital-recovery /
        // WR-recovery partials use reason strings that DON'T start with
        // 'partial' (e.g. 'profit_lock_1.5x', 'capital_recovery_2x',
        // 'wr_recovery_partial_9'). Pre-V5.9.800 the arbiter treated those
        // as terminal, locking the positionKey on the FIRST profit lock
        // and then SUPPRESSING the actual terminal close ‚Äî breaking the
        // entire fluid stop-loss / fluid profit-lock flow. Now we detect
        // ALL non-terminal sell signatures by reason prefix AND by an
        // independent quantity check: if the sell didn't move the
        // position size to zero (within rounding), it's a partial.
        try {
            val isSell = trade.side.equals("SELL", ignoreCase = true)
            val reasonLower = trade.reason.lowercase()
            val isPartialByReason =
                reasonLower.startsWith("partial") ||
                reasonLower.contains("partial_") ||
                reasonLower.contains("partialsell") ||
                reasonLower.startsWith("profit_lock") ||
                reasonLower.startsWith("capital_recovery") ||
                reasonLower.startsWith("wr_recovery") ||
                reasonLower.contains("_partial_") ||
                reasonLower.contains("profit_take_partial") ||
                reasonLower.contains("scale_out")
            // Quantity-based partial detection ‚Äî independent of reason
            // string. If the position still has > 1% of qty left after
            // this sell, it's a partial. Defensive against future exit-
            // reason strings we haven't whitelisted yet.
            val isPartialByQty = try {
                val price = if (trade.price > 0.0) trade.price else 1.0
                val sellQty = trade.sol / price
                val totalQty = ts.position.qtyToken
                totalQty > 0.0 && sellQty < totalQty * 0.99
            } catch (_: Throwable) { false }
            val isPartial = isPartialByReason || isPartialByQty
            if (isSell && !isPartial) {
                // V5.0.4561 ‚Äî mint-finality journal choke. Runtime 4540 showed
                // the same mint closing twice under different lane labels, e.g.
                // MANIPULATED loss followed by SHITCOIN scratch. TradeOutcomeLedger
                // keys include lane/entry identity, so lane drift can bypass the
                // close outcome suppressor. Live sell finality stamps
                // PositionCloseLedger before recordTrade(), so existence alone must
                // NOT suppress the first real row. Instead, allow exactly one
                // terminal journal row per fresh closeId, then suppress later rows
                // for that same mint-close finality regardless of lane/sig drift.
                val existingCloseId4561 = try { com.lifecyclebot.engine.PositionCloseLedger.closeIdOf(ts.mint) } catch (_: Throwable) { null }
                if (!existingCloseId4561.isNullOrBlank() && !terminalSellJournaledCloseIds4561.add(existingCloseId4561)) {
                    try {
                        ForensicLogger.lifecycle(
                            "TERMINAL_SELL_DUPLICATE_SUPPRESSED_BY_CLOSE_LEDGER_4561",
                            "mint=${ts.mint.take(10)} symbol=${ts.symbol} closeId=$existingCloseId4561 reason=${trade.reason.take(80)} lane=${trade.tradingMode.ifBlank { ts.position.tradingMode }} sig=${trade.sig.take(16)}"
                        )
                        PipelineHealthCollector.labelInc("TERMINAL_SELL_DUPLICATE_SUPPRESSED_BY_CLOSE_LEDGER_4561")
                    } catch (_: Throwable) {}
                    return
                }
                val env = if (ts.position.isPaperPosition) "PAPER" else "LIVE"
                val verdict = com.lifecyclebot.engine.PositionExitArbiter.arbitrate(
                    canonicalMint = ts.mint,
                    entryTimeMs = ts.position.entryTime,
                    reason = trade.reason.ifBlank { "UNKNOWN_TERMINAL" },
                    env = env,
                    sig = trade.sig.ifBlank { null },
                )
                if (verdict.decision == com.lifecyclebot.engine.PositionExitArbiter.Decision.SUPPRESS) {
                    return
                }
                // V5.9.791 ‚Äî pre-mark this tradeId as rich-published so the
                // legacy bridge (TradeHistoryStore.recordTrade ‚Üí
                // publishFromLegacyTrade) early-returns and the arbiter slot
                // (just locked above) isn't falsely "suppressed-once" by the
                // bridge before the actual rich publish at line ~1600 fires.
                try {
                    val tradeId = "${ts.mint}_${trade.ts}"
                    com.lifecyclebot.engine.CanonicalOutcomeBus.markRichPublished(tradeId)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // V5.9.1038 ‚Äî operator V5.9.1037 triage: some exit paths
        // (sweepUniversalExits, rapid-monitor closes) construct Trade objects
        // without populating tradingMode, leaving downstream learners with
        // source=UNKNOWN. Inherit pos.tradingMode whenever Trade's is blank.
        // V5.9.1331 ‚Äî UNKNOWN DATA-CAPTURE FIX (operator: "we need all data captured
        // correctly. what is the unknown"). UNKNOWN is NOT a lane ‚Äî it is the bucket the
        // telemetry assigns when a trade's tradingMode is blank at journal time. The honest
        // backtest then reads that phantom bucket (n=87, sharpe -4.7) as "this lane loses,
        // STOP TRADING" ‚Äî but those trades DID have a real lane; the mode field was simply
        // never stamped. Root cause: the main V3 buy route (doBuy ‚Üí paperBuy at the spine
        // call site) does NOT pass layerTag, and position.tradingMode is set AFTER the buy
        // journals for lane-routed opens ‚Äî so the BUY records blank and every downstream
        // SELL inherits blank too. Resolve the lane here through the best available signal,
        // in priority order, and fall back to "STANDARD" (the convention used everywhere
        // else in this file) instead of letting it reach the UNKNOWN bin. This makes the
        // expectancy/backtest bins reflect the lane that actually decided the trade.
        // Lane signals only, in priority order. trade.tradingMode and
        // ts.position.tradingMode are the REAL lane (BLUECHIP/SHITCOIN/TREASURY/...).
        // We deliberately do NOT fall back to the discovery source (PUMP_FUN_NEW,
        // DEX_TRENDING) ‚Äî that is not a lane and would just create new phantom bins.
        // "STANDARD" is the established convention for a genuinely unclassified lane
        // (see ts.position.tradingMode.ifBlank{"STANDARD"} at the exit-side bin sites).
        fun isMeaningfulLaneName(raw: String?): Boolean {
            val u = raw?.trim()?.uppercase().orEmpty()
            return u.isNotBlank() && u !in setOf("UNKNOWN", "NULL", "NONE", "UNSET", "N/A", "NA")
        }
        val resolvedTradingMode = when {
            isMeaningfulLaneName(trade.tradingMode)       -> trade.tradingMode
            isMeaningfulLaneName(ts.position.tradingMode) -> ts.position.tradingMode
            else -> {
                // V5.0.4300 ‚Äî live report showed every clean close landing in
                // STANDARD while all meme lanes were evaluating/trading. No lane
                // attribution means no per-trader learning. Use the lane resolver
                // as the final fallback so SHITCOIN/MOONSHOT/EXPRESS/etc. learn
                // from their own terminal outcomes instead of poisoning STANDARD.
                resolveExecutionLane(ts, fallback = "STANDARD")
            }
        }
        val ledgerPositionId = try { com.lifecyclebot.engine.TradeOutcomeLedger.positionId(ts, trade) } catch (_: Throwable) { "" }
        val entryTsForJournal = ts.position.entryTime.takeIf { it > 0L } ?: if (trade.side.equals("BUY", true)) trade.ts else trade.entryTsMs
        val entryCostForJournal = ts.position.costSol.takeIf { it > 0.0 } ?: if (trade.side.equals("BUY", true)) trade.sol else trade.entryCostSol
        // V5.0.6449 ¬ß3 ‚Äî For SELL/PARTIAL rows, the caller (paperSell /
        // executeProfitLockSell) now supplies canonical-locked qty in
        // trade.entryQtyToken / trade.soldQtyToken. Prefer that over the
        // potentially-drifted ts.position.qtyToken to preserve the qty
        // source lock end-to-end into the journal.
        val entryQtyForJournal = if (!trade.side.equals("BUY", true) && trade.entryQtyToken > 0.0) {
            trade.entryQtyToken
        } else {
            ts.position.qtyToken.takeIf { it > 0.0 } ?: trade.entryQtyToken
        }
        val exitReason6509 = trade.reason.lowercase()
        val isExit6509 = trade.side.equals("SELL", true) || trade.side.equals("PARTIAL_SELL", true)
        val isPartial6509 = trade.side.equals("PARTIAL_SELL", true) || exitReason6509.contains("partial") ||
            exitReason6509.startsWith("profit_lock") || exitReason6509.startsWith("capital_recovery") || exitReason6509.contains("scale_out")
        // V5.0.6509 ‚Äî source-locked token quantity. Economic return is never a token fraction.
        // Legacy inference is intentionally zero unless a future importer explicitly marks/provides it.
        val soldQtyForJournal = if (isExit6509) {
            com.lifecyclebot.engine.truth.PaperTokenQuantityAuthority6509.resolveJournalSoldQty(
                suppliedSoldQtyToken = trade.soldQtyToken,
                suppliedEntryQtyToken = trade.entryQtyToken,
                terminal = !isPartial6509,
                explicitLegacyInferenceQty = 0.0,
            )
        } else trade.soldQtyToken
        val entryMcapForJournal: Double = trade.entryMcapUsd.takeIf { it > 0.0 }
            ?: ts.position.entryMcap.takeIf { it > 0.0 }
            // V5.0.3809 ‚Äî do NOT stamp current/discovery mcap onto SELL/PARTIAL rows.
            // If a restored/legacy position lacks entryMcap, leave it unknown (0) and let
            // the row display "mcap=n/a" instead of mislabeling exit/discovery mcap as entry mcap.
            ?: (if (trade.side.equals("BUY", true)) ts.lastMcap.takeIf { it > 0.0 } else null)
            ?: 0.0
        val proofStateForJournal4502 = trade.proofState.ifBlank {
            val sideU = trade.side.uppercase()
            val isPaper = trade.mode.equals("paper", true) || ts.position.isPaperPosition
            when {
                isPaper -> "PAPER_SIMULATED"
                (sideU == "SELL" || sideU == "PARTIAL_SELL") && trade.sig.isNotBlank() -> "LIVE_FINALIZED"
                trade.sig.isNotBlank() -> "LIVE_SIG_CONFIRMED"
                else -> "LIVE_BROADCAST"
            }
        }
        var tradeWithMint = trade.copy(
            mint = if (trade.mint.isBlank()) ts.mint else trade.mint,
            tradingMode = com.lifecyclebot.engine.LaneAlias.normalize(resolvedTradingMode).ifBlank { resolvedTradingMode },
            positionId = trade.positionId.ifBlank { ledgerPositionId },
            entryTsMs = trade.entryTsMs.takeIf { it > 0L } ?: entryTsForJournal,
            entryPriceSnapshot = trade.entryPriceSnapshot.takeIf { it > 0.0 } ?: ts.position.entryPrice.takeIf { it > 0.0 } ?: if (trade.side.equals("BUY", true)) trade.price else 0.0,
            entryMcapUsd = entryMcapForJournal,
            entryQtyToken = entryQtyForJournal,
            entryCostSol = entryCostForJournal,
            soldQtyToken = soldQtyForJournal,
            remainingQtyToken = if (entryQtyForJournal > 0.0) (entryQtyForJournal - soldQtyForJournal).coerceAtLeast(0.0) else trade.remainingQtyToken,
            entryPriceSource = trade.entryPriceSource.ifBlank { ts.position.entryPriceSource },
            entryPoolAddress = trade.entryPoolAddress.ifBlank { ts.position.entryPoolAddress },
            proofState = proofStateForJournal4502,
        )

        // V5.0.6320 ‚Äî CANONICAL BUY FILL OVERRIDE (¬ß8). If the wallet has
        // verified the entry fill for this mint (via promoteVerifiedLiveBuy),
        // the mint-keyed registry holds the immutable on-chain truth.
        // Override the SELL row's entry snapshot with those values so the
        // journal, learning brain, MFE tracker, and CSV export all read
        // the same authoritative fill ‚Äî closing the multi-source-of-truth
        // divergence the operator saw on Pilly (journal e=$0.00009761 vs
        // position card $0.00000587 vs sell toast ‚àí31%).
        if (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) {
            try {
                val fill6320 = com.lifecyclebot.engine.CanonicalBuyFillRegistry.get(tradeWithMint.mint)
                if (fill6320 != null) {
                    // V5.0.6323 ‚Äî UNITS FIX. Journal Trade.entryPriceSnapshot
                    // is USD/token (mirrors ts.position.entryPrice which is
                    // written from proofEntryUsd in completeVerifiedLiveBuyWithProof).
                    // Preferring entryPriceSol here produced a ~1/solPrice
                    // (‚âà1/150) units divergence between journal rows and the
                    // Pilly-style position card. Always take USD first; only
                    // fall back to SOL/token if USD is missing (early boot
                    // before SOL/USD is known).
                    val canonicalEntryPx = fill6320.entryPriceUsd.takeIf { it > 0.0 } ?: fill6320.entryPriceSol
                    val stalePx = tradeWithMint.entryPriceSnapshot
                    val staleQty = tradeWithMint.entryQtyToken
                    val entryPxMismatch = canonicalEntryPx > 0.0 && stalePx > 0.0 &&
                        maxOf(canonicalEntryPx, stalePx) / minOf(canonicalEntryPx, stalePx) > 1.10
                    val entryQtyMismatch = fill6320.walletVerifiedQty > 0.0 && staleQty > 0.0 &&
                        maxOf(fill6320.walletVerifiedQty, staleQty) / minOf(fill6320.walletVerifiedQty, staleQty) > 1.10
                    if (entryPxMismatch || entryQtyMismatch) {
                        val correctedRemaining = (fill6320.walletVerifiedQty - tradeWithMint.soldQtyToken).coerceAtLeast(0.0)
                        tradeWithMint = tradeWithMint.copy(
                            entryPriceSnapshot = if (canonicalEntryPx > 0.0) canonicalEntryPx else tradeWithMint.entryPriceSnapshot,
                            entryQtyToken = fill6320.walletVerifiedQty,
                            entryCostSol = if (fill6320.solSpentNet > 0.0) fill6320.solSpentNet else tradeWithMint.entryCostSol,
                            entryDecimals = if (fill6320.decimals >= 0) fill6320.decimals else tradeWithMint.entryDecimals,
                            entryTsMs = fill6320.entryTsMs.takeIf { it > 0L } ?: tradeWithMint.entryTsMs,
                            remainingQtyToken = correctedRemaining,
                            entryPriceSource = "CANONICAL_BUY_FILL_6320",
                        )
                        try {
                            ForensicLogger.lifecycle(
                                "SELL_JOURNAL_CANONICAL_OVERRIDE_6320",
                                "mint=${tradeWithMint.mint.take(10)} sym=${ts.symbol} stalePx=$stalePx‚Üí${canonicalEntryPx} staleQty=$staleQty‚Üí${fill6320.walletVerifiedQty} pxMismatch=$entryPxMismatch qtyMismatch=$entryQtyMismatch",
                            )
                            PipelineHealthCollector.labelInc("SELL_JOURNAL_CANONICAL_OVERRIDE_6320")
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // V5.0.6314 ‚Äî CANONICAL PnL SEPARATION (¬ß12). Classify every SELL
        // row into one of four buckets and increment the matching counter
        // so operators can distinguish canonical live outcomes from
        // provisional / duplicated / broadcast telemetry rows. Only
        // LIVE_FINALIZED / LIVE_RECONCILED count toward canonical live
        // performance (already enforced by LiveConfidenceStats.load).
        if (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) {
            try {
                when (proofStateForJournal4502.uppercase()) {
                    "LIVE_FINALIZED" -> PipelineHealthCollector.labelInc("LIVE_PNL_CONFIRMED_ROWS")
                    "LIVE_RECONCILED" -> PipelineHealthCollector.labelInc("LIVE_PNL_RECONCILED_ROWS")
                    "LIVE_SIG_CONFIRMED" -> PipelineHealthCollector.labelInc("LIVE_PNL_ESTIMATED_ROWS")
                    "LIVE_BROADCAST" -> PipelineHealthCollector.labelInc("LIVE_PNL_BROADCAST_EXCLUDED")
                    else -> PipelineHealthCollector.labelInc("LIVE_PNL_ESTIMATED_ROWS")
                }
            } catch (_: Throwable) {}
        }


        // V5.0.6312 ‚Äî EXIT_REASON_INVARIANT (¬ß13). If the SELL row's reason
        // claims TAKE_PROFIT / RUNNER / QUICK_RUNNER / +N% BANK but the
        // realised PnL is ‚â§ 0, rewrite the reason to
        // FALLBACK_AFTER_FAILED_PROFIT_EXIT and stamp the original label
        // on a counter so the audit trail is preserved. This closes the
        // "QUICK_RUNNER_10X_FULL_EXIT at a large loss" contradiction the
        // operator flagged in the LIVE_20260722_2114 export.
        if (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) {
            // V5.0.6330 ‚Äî LEARNING ELIGIBILITY CLASSIFIER (WADDLE cascade
            // defence). Every finalised SELL row is classified through
            // LearningEligibility so QUARANTINED_DECIMAL / PENDING_RECONCILIATION
            // rows are visible in telemetry and skipped by the governor
            // sample. This is the direct fix for the operator report
            // 'change WIN/LOSS label assignment across all strategies to
            // use actual execution results, not stale mark prices' ‚Äî a
            // stale-mark-derived qty produces a decimal-skewed row that
            // this classifier now flags before it can retrain the brain.
            try {
                val windowStart = try { LiveEntrySafetyHold.governorWindowStart() } catch (_: Throwable) { 0L }
                val eligibility = LearningEligibility.classify(tradeWithMint, windowStart)
                PipelineHealthCollector.labelInc("LEARNING_ELIGIBILITY_${eligibility.eligibility.name}_6330")
                if (eligibility.eligibility != LearningEligibility.Eligibility.ELIGIBLE) {
                    ForensicLogger.lifecycle(
                        "LEARNING_ELIGIBILITY_QUARANTINED_6330",
                        "mint=${tradeWithMint.mint.take(10)} mode=${tradeWithMint.tradingMode} eligibility=${eligibility.eligibility} reason=${eligibility.reason} pnl=${"%.4f".format(tradeWithMint.pnlSol)} realized=${"%.4f".format(tradeWithMint.netPnlSol)}",
                    )
                }
            } catch (_: Throwable) {}
            val rawReason6312 = tradeWithMint.reason.uppercase()
            val claimsProfit6312 = rawReason6312.contains("QUICK_RUNNER") ||
                rawReason6312.contains("TAKE_PROFIT") ||
                rawReason6312.contains("BANK") ||
                rawReason6312.contains("RUNNER") ||
                rawReason6312.contains("PROFIT_TARGET") ||
                rawReason6312.contains("10X") ||
                rawReason6312.contains("6X")
            val realisedPnl6312 = if (tradeWithMint.netPnlSol != 0.0) tradeWithMint.netPnlSol else tradeWithMint.pnlSol
            if (claimsProfit6312 && realisedPnl6312 <= 0.0) {
                val originalReason6312 = tradeWithMint.reason
                tradeWithMint = tradeWithMint.copy(
                    reason = "FALLBACK_AFTER_FAILED_PROFIT_EXIT_6312:${originalReason6312.take(60)}"
                )
                try {
                    ForensicLogger.lifecycle(
                        "EXIT_REASON_INVARIANT_FAILED",
                        "originalReason=$originalReason6312 rewritten=FALLBACK_AFTER_FAILED_PROFIT_EXIT_6312 pnlSol=${realisedPnl6312.fmt(6)} mint=${tradeWithMint.mint.take(10)} sym=${ts.symbol}",
                    )
                    PipelineHealthCollector.labelInc("EXIT_REASON_INVARIANT_FAILED")
                    PipelineHealthCollector.labelInc("EXIT_REASON_INVARIANT_ORIGINAL_${originalReason6312.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(60)}")
                } catch (_: Throwable) {}
            }

            // V5.0.6312 ‚Äî MINT RE-ENTRY COOLDOWN (¬ß21). On every FINALIZED
            // SELL row (proofStateForJournal4502 == LIVE_FINALIZED), arm a
            // per-mint cooldown sized by the exit severity. Downstream
            // liveBuy() consults MintReEntryCooldown.shouldBlockReEntry()
            // before approving a fresh live buy on this mint. Paper/shadow
            // paths are unaffected.
            if (proofStateForJournal4502.equals("LIVE_FINALIZED", true) && tradeWithMint.mode.equals("live", true)) {
                try {
                    MintReEntryCooldown.onFinalisedClose(
                        mint = tradeWithMint.mint,
                        exitReason = tradeWithMint.reason,
                        pnlPct = tradeWithMint.pnlPct,
                    )
                } catch (_: Throwable) {}
            }
        }


        // V5.0.3868 ‚Äî paper‚Üílive transfer authority: every legacy/journal/learning
        // consumer must see executable NET edge, not gross displayed paper percent.
        // Canonical rich publish already used netPnlSol for realizedPnlSol, but
        // TradeHistoryStore/LosingPatternMemory/RunTracker30D/SmartSizer consumed
        // tradeWithMint.pnlPct before the rich event. Normalize close rows here at
        // the shared choke point so paper readiness cannot promote scratch edges
        // that live fees/slippage turn red.
        if (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) {
            val isPartialClose = tradeWithMint.side.equals("PARTIAL_SELL", true)
            // V5.0.4112 ‚Äî PHANTOM_PNL_FIX. The previous fallback to
            // `tradeWithMint.sol` (proceeds) as cost basis caused the
            // +99% recovered-sell win cluster: when both entryCostSol AND
            // position.costSol were 0 (WALLET_RECOVERED orphan), we used the
            // sell PROCEEDS as the "cost", producing pnlPct = (net/proceeds) =
            // ‚âà100% for every recovered close. We now refuse to use proceeds
            // as basis. Recovered closes with no real cost must surface as
            // pnlPct=0 / pnlSol=0 ‚Äî they are accounting-unsafe SCRATCHES, not
            // wins. Real partials with no entryCostSol still allow the sol
            // fallback because partial SELL rows store the sold-leg cost in
            // `sol`, which is genuine cost basis, not proceeds.
            val tmU = (ts.position.tradingMode ?: "").uppercase()
            val isRecoveredRow = tmU == "WALLET_RECOVERED" ||
                ts.position.entryPhase == "WALLET_RECOVERED" ||
                tradeWithMint.reason.uppercase().contains("WALLET_RECOVERED") ||
                tradeWithMint.reason.uppercase().contains("DEAD_TOKEN_NO_PRICE")
            val basis = if (isPartialClose) {
                // Partial SELL rows use sol as the sold-leg cost basis; using full
                // position cost would dilute/lie about partial edge.
                tradeWithMint.sol.takeIf { it.isFinite() && it > 0.0 }
                    ?: tradeWithMint.entryCostSol.takeIf { it.isFinite() && it > 0.0 }
                    ?: 0.0
            } else if (isRecoveredRow) {
                // Recovered terminal closes: do not invent a basis. 0 ‚Üí SCRATCH.
                tradeWithMint.entryCostSol.takeIf { it.isFinite() && it > 0.0 }
                    ?: ts.position.costSol.takeIf { it.isFinite() && it > 0.0 }
                    ?: 0.0
            } else {
                tradeWithMint.entryCostSol.takeIf { it.isFinite() && it > 0.0 }
                    ?: ts.position.costSol.takeIf { it.isFinite() && it > 0.0 }
                    ?: tradeWithMint.sol.takeIf { it.isFinite() && it > 0.0 }
                    ?: 0.0
            }
            val realizedNet = when {
                isRecoveredRow && (basis <= 0.0) -> 0.0     // explicit scratch
                tradeWithMint.netPnlSol.isFinite() && tradeWithMint.netPnlSol != 0.0 -> tradeWithMint.netPnlSol
                tradeWithMint.feeSol.isFinite() && tradeWithMint.feeSol > 0.0 -> tradeWithMint.pnlSol - tradeWithMint.feeSol
                else -> tradeWithMint.pnlSol
            }
            if (isRecoveredRow && basis <= 0.0) {
                // Force the recovered row to scratch end-to-end.
                if (tradeWithMint.pnlPct != 0.0 || tradeWithMint.pnlSol != 0.0) {
                    try { PipelineHealthCollector.labelInc("RECOVERED_PHANTOM_PNL_NORMALIZED") } catch (_: Throwable) {}
                    tradeWithMint = tradeWithMint.copy(pnlPct = 0.0, pnlSol = 0.0, netPnlSol = 0.0)
                }
            } else if (basis > 0.0 && realizedNet.isFinite()) {
                val netPct = (realizedNet / basis) * 100.0
                if (netPct.isFinite() && kotlin.math.abs(netPct - tradeWithMint.pnlPct) > 0.01) {
                    try { PipelineHealthCollector.labelInc("PAPER_LIVE_TRANSFER_NET_PCT_NORMALIZED") } catch (_: Throwable) {}
                    tradeWithMint = tradeWithMint.copy(pnlPct = netPct, pnlSol = realizedNet, netPnlSol = realizedNet)
                }
            }
        }

        // V5.0.4112 ‚Äî RECOVERED-SCRATCH LEARNING EXCLUSION.
        // Even after we force pnl=0 on recovered rows, downstream tuners
        // (LiveStrategyTuner, LaneExitTuner, PatternAutoTuner, MetaCognition,
        // SessionEdgeAI, KillSwitch, etc.) would still ingest the row and
        // count it toward sample-size + EV-bin denominators, biasing future
        // sizing and exit profiles. The operator audit identified the
        // STANDARD:compounding_runner WR=100% n=25 line as direct evidence
        // that these recovered closes were corrupting the live tuners.
        //
        // For recovered closes we journal the row (audit), but we DO NOT
        // fan it out into learning paths. The row will not appear in
        // strategy expectancy tables, in size√ó/tp√ó/sl√ó tuners, or in the
        // probability-engine EV bins. It is invisible to learning.
        val isRecoveredScratch = (
            (ts.position.tradingMode ?: "").uppercase() == "WALLET_RECOVERED" ||
            ts.position.entryPhase == "WALLET_RECOVERED" ||
            tradeWithMint.reason.uppercase().contains("WALLET_RECOVERED") ||
            tradeWithMint.reason.uppercase().contains("DEAD_TOKEN_NO_PRICE")
        ) && (ts.position.costSol <= 0.0 && tradeWithMint.entryCostSol <= 0.0)
        if (isRecoveredScratch && (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true))) {
            try { PipelineHealthCollector.labelInc("LEARNING_EXCLUDED_RECOVERED_SCRATCH") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle(
                "LEARNING_EXCLUDED_RECOVERED_SCRATCH",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=${tradeWithMint.reason} " +
                    "tradingMode=${ts.position.tradingMode} action=skip_learning_fanout",
            ) } catch (_: Throwable) {}
        }

        // V5.9.1100 ‚Äî canonical execution/outcome idempotency.
        // BUY records register a canonical PositionId. Terminal SELL records
        // must acquire exactly one TradeOutcomeId before any closed-trade
        // journal/learning fanout. Partial sells remain journal-visible but do
        // not count as closed outcomes; orphan/duplicate closes are suppressed
        // from strategy learning/accounting.
        val ledgerAllowsClosedLearning: Boolean = try {
            when {
                tradeWithMint.side.equals("BUY", ignoreCase = true) -> {
                    val ok = TradeOutcomeLedger.recordOpen(ts, tradeWithMint)
                    if (!ok) {
                        ForensicLogger.lifecycle("DUPLICATE_OPEN_SUPPRESSED", "mint=${ts.mint.take(10)} symbol=${ts.symbol} mode=${tradeWithMint.mode} lane=${tradeWithMint.tradingMode}")
                        return
                    }
                    false
                }
                tradeWithMint.side.equals("SELL", ignoreCase = true) || tradeWithMint.side.equals("PARTIAL_SELL", ignoreCase = true) -> {
                    val r = tradeWithMint.reason.lowercase()
                    val partialByReason = r.startsWith("partial") || r.contains("partial_") || r.contains("partialsell") ||
                        r.startsWith("profit_lock") || r.startsWith("capital_recovery") || r.startsWith("wr_recovery") ||
                        r.contains("_partial_") || r.contains("profit_take_partial") || r.contains("scale_out")
                    val partialByQty = try {
                        val price = if (tradeWithMint.price > 0.0) tradeWithMint.price else 1.0
                        val sellQty = tradeWithMint.sol / price
                        val totalQty = ts.position.qtyToken
                        totalQty > 0.0 && sellQty < totalQty * 0.99
                    } catch (_: Throwable) { false }
                    val close = TradeOutcomeLedger.recordClose(ts, tradeWithMint, partial = partialByReason || partialByQty)
                    if (!close.accepted && !close.partial) {
                        ForensicLogger.lifecycle("TRADE_OUTCOME_SUPPRESSED", "mint=${ts.mint.take(10)} symbol=${ts.symbol} outcomeId=${close.outcomeId} reason=${close.reason} orphan=${close.orphan}")
                        return
                    }
                    close.accepted
                }
                else -> false
            }
        } catch (_: Throwable) { tradeWithMint.side.equals("SELL", ignoreCase = true) }

        // V5.0.6310 ‚Äî QTY_DECIMAL_SKEW LEARNING QUARANTINE.
        // If the SELL row's entryQtyToken (post-wallet-verify, correct
        // decimals) diverges by >10√ó from the last recorded BUY row for
        // the same mint (pre-wallet-verify, possibly heuristic-inferred),
        // we know the paired BUY leg was journaled at the wrong scale.
        // The row's %PnL is still directionally correct (it's price-ratio-
        // driven, not qty-driven), but sizing / PnLSol accounting for
        // per-lane learning becomes untrustworthy. Quarantine from the
        // learning fanout so the Bayesian brain doesn't ingest fake
        // "10√ó winners" while the wallet actually took a loss.
        val qtyDecimalSkew6310: Boolean = try {
            if (!tradeWithMint.side.equals("SELL", true) && !tradeWithMint.side.equals("PARTIAL_SELL", true)) false
            else {
                val sellQty = tradeWithMint.entryQtyToken
                val buyQty = TradeHistoryStore.getLatestBuyByMintSnapshot()[tradeWithMint.mint]?.entryQtyToken ?: 0.0
                if (sellQty > 0.0 && buyQty > 0.0) {
                    val ratio = maxOf(sellQty, buyQty) / minOf(sellQty, buyQty)
                    if (ratio > 10.0) {
                        // V5.0.6725 ¬ßSKEW_QUARANTINE_RATIO_BREAKDOWN ‚Äî 6724
                        // dump showed 34 lots quarantined on this counter
                        // without visibility into WHY (which decade of skew).
                        // The three canonical bad shapes are 10x/100x
                        // (single decimal drift), 1000x/10000x (missing
                        // shift by 3-4 decimals ‚Äî the CryptoAltTrader
                        // stringly-typed decimals path), and 100000x+
                        // (raw lamport vs UI amount confusion). This
                        // ratio-bucketed counter tells the operator which
                        // decade dominates so the next push can target
                        // the actual conversion site.
                        val ratioBucket6725 = when {
                            ratio < 20.0 -> "10X"
                            ratio < 200.0 -> "100X"
                            ratio < 2_000.0 -> "1000X"
                            ratio < 20_000.0 -> "10000X"
                            ratio < 200_000.0 -> "100000X"
                            else -> "GT_100000X"
                        }
                        try {
                            ForensicLogger.lifecycle(
                                "QTY_DECIMAL_SKEW_LEARNING_QUARANTINE_6310",
                                "mint=${tradeWithMint.mint.take(10)} sym=${ts.symbol} side=${tradeWithMint.side} buyQty=${buyQty.fmt(6)} sellQty=${sellQty.fmt(6)} ratio=${ratio.fmt(1)}√ó bucket=$ratioBucket6725 reason=${tradeWithMint.reason} ‚Äî decimals mismatch, excluded from learning fanout",
                            )
                            PipelineHealthCollector.labelInc("QTY_DECIMAL_SKEW_LEARNING_QUARANTINE_6310")
                            PipelineHealthCollector.labelInc("QTY_DECIMAL_SKEW_QUARANTINE_BUCKET_6725_$ratioBucket6725")
                        } catch (_: Throwable) {}
                        true
                    } else false
                } else false
            }
        } catch (_: Throwable) { false }

        val accountingTrainable: Boolean = try {
            if (!tradeWithMint.side.equals("SELL", true) && !tradeWithMint.side.equals("PARTIAL_SELL", true)) true
            // V5.0.4112 ‚Äî recovered scratches are NEVER trainable. They have
            // no real cost basis and represent inventory cleanup, not edge.
            else if (isRecoveredScratch) false
            // V5.0.6882 ¬ßDEFERRAL_IS_NOT_A_DISPOSITION ‚Äî a closure that only
            // happened because MissingMarkExitVeto6835's deferral bound
            // expired was priced off a mark the veto itself judged
            // untrustworthy. The position had to be released (it was holding a
            // POSITION_HARD_CAP slot and its basis in openCost indefinitely),
            // but the -N% it reports is a feed artefact, not edge. This is the
            // exact row 6835 exists to keep out of strategy expectancy,
            // losing-pattern memory, the forward-outcome model and the policy
            // head ‚Äî so it is journalled for audit and trained on by nothing.
            // V5.0.6895 ¬ßA_BASIS_SPLIT_POSITION_IS_NOT_EVIDENCE ‚Äî if this
            // position ever served a refused cross-basis mark, its entry and
            // its marks were never on the same basis, so its terminal PnL
            // describes arithmetic rather than economics. Journal it for audit;
            // train nothing on it.
            //
            // This is the row that taught the stack QUALITY prints +3170% on a
            // 5/21 record. Excluding it is what lets LaneExpectancyDamper, the
            // tactic switcher, ForwardOutcomeModel and the policy head finally
            // see QUALITY's real -28.75%/trade and rotate away from it ‚Äî the
            // win-rate fix is in the exclusion, not in any new signal.
            else if (ts.position.crossBasisRefusals6895 > 0L) {
                try {
                    PipelineHealthCollector.labelInc("LEARNING_EXCLUDED_CROSS_BASIS_6895")
                    ForensicLogger.lifecycle(
                        "LEARNING_EXCLUDED_CROSS_BASIS_6895",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} " +
                            "refusals=${ts.position.crossBasisRefusals6895} " +
                            "entrySrc=${ts.position.entryPriceSource} pnlPct=${tradeWithMint.pnlPct} " +
                            "action=journal_only_no_learning_fanout",
                    )
                } catch (_: Throwable) {}
                false
            }
            else if (tradeWithMint.reason.contains("MARK_UNTRUSTED_6882")) {
                try {
                    PipelineHealthCollector.labelInc("LEARNING_EXCLUDED_MARK_UNTRUSTED_6882")
                    ForensicLogger.lifecycle(
                        "LEARNING_EXCLUDED_MARK_UNTRUSTED_6882",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=${tradeWithMint.reason} " +
                            "action=journal_only_no_learning_fanout",
                    )
                } catch (_: Throwable) {}
                false
            }
            // V5.0.6310 ‚Äî decimal-skew quarantine (belt-and-suspenders on top
            // of the inferUiScaleFromTrade units fix and explicitDecimals plumb).
            else if (qtyDecimalSkew6310) false
            // V5.0.6314 ‚Äî broadcast-only rows are TELEMETRY, not finality.
            // Never train the Bayesian brain / tactic switcher / lane
            // expectancy from a row that hasn't been on-chain proven.
            else if (tradeWithMint.proofState.equals("LIVE_BROADCAST", true) ||
                     tradeWithMint.proofState.equals("LIVE_SIG_CONFIRMED", true)) {
                try { PipelineHealthCollector.labelInc("LEARNING_ACCOUNTING_QUARANTINE_BROADCAST_6314") } catch (_: Throwable) {}
                false
            }
            else {
                val proceeds = tradeWithMint.sol + (tradeWithMint.netPnlSol.takeIf { it != 0.0 } ?: tradeWithMint.pnlSol)
                tradeWithMint.price > 0.0 && tradeWithMint.sol > 0.0 && proceeds >= -0.0000001
            }
        } catch (_: Throwable) { false }

        if (!accountingTrainable && (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true))) {
            try {
                ForensicLogger.lifecycle(
                    "INVALID_ACCOUNTING_NOT_TRAINED",
                    "mint=${tradeWithMint.mint.take(10)} side=${tradeWithMint.side} price=${tradeWithMint.price} sol=${tradeWithMint.sol} pnl=${tradeWithMint.pnlSol} reason=${tradeWithMint.reason}",
                )
            } catch (_: Throwable) {}
        }

        val terminalOutcomeQuality4286 = if (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) {
            try { TerminalOutcomeQualityGate.classify(tradeWithMint, ledgerAllowsClosedLearning, accountingTrainable) } catch (_: Throwable) { null }
        } else null
        try { terminalOutcomeQuality4286?.let { TerminalOutcomeQualityGate.report(tradeWithMint, ts.position.tradingMode ?: tradeWithMint.tradingMode, ts.source, it) } } catch (_: Throwable) {}

        // V5.9.1161 ‚Äî premark all valid sell-like outcomes, including
        // PARTIAL_SELL, before the legacy TradeHistoryStore bridge runs.
        // Otherwise a valid partial would publish once through the legacy
        // bridge and again through the feature-rich Executor publisher.
        try {
            if ((tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) && ledgerAllowsClosedLearning) {
                com.lifecyclebot.engine.CanonicalOutcomeBus.markRichPublished("${tradeWithMint.mint}_${tradeWithMint.ts}")
            }
        } catch (_: Throwable) {}

        ts.trades.add(tradeWithMint)
        // V5.0.6337 ‚Äî DECIMAL SKEW RETRO-BACKFILL AT SELL WRITE.
        //
        // 6311's wallet-verified backfill only fires on promoteVerifiedLiveBuy,
        // which requires the wallet to sync 15-45s after the BUY confirms. For
        // fast RAPID_CATASTROPHE_STOP sells (see vTKXhk: BUY 02:08:34 ‚Üí SELL
        // 02:08:56, 22s later) the wallet verify hadn't landed yet, so the BUY
        // row kept its pre-verify heuristic qty and the SELL row shipped with
        // the true wallet qty ‚Äî giving the operator's QTY_DECIMAL_SKEW_6309
        // audit rows like BUY=8686 vs SELL=43.11 (200√ó ratio).
        //
        // Retro-fix at SELL time: when we now have both the buy row and the
        // sell qty in front of us, if they disagree by >10√ó and we hold a
        // wallet-verified decimals, run the same backfill retroactively so the
        // trainer / dashboard / LearningEligibility never see the skewed row.
        try {
            val isSellSide =
                tradeWithMint.side.equals("SELL", true) ||
                tradeWithMint.side.equals("PARTIAL_SELL", true)
            val sellQty = if (isSellSide) {
                if (tradeWithMint.soldQtyToken > 0.0) tradeWithMint.soldQtyToken
                else tradeWithMint.entryQtyToken
            } else 0.0
            if (isSellSide && sellQty > 0.0) {
                val mint = tradeWithMint.mint.ifBlank { ts.mint }
                val walletDecimals = walletDecimalsByMint6311[mint]?.takeIf { it >= 0 }
                    ?: ts.tokenMap.decimals?.takeIf { it >= 0 }
                    ?: -1
                if (walletDecimals >= 0) {
                    // Ask the store what the last BUY qty was for this mint.
                    val lastBuyQty = try {
                        TradeHistoryStore.getLastBuyQtyForMint(mint)
                    } catch (_: Throwable) { -1.0 }
                    if (lastBuyQty > 0.0) {
                        val ratio = maxOf(lastBuyQty, sellQty) / minOf(lastBuyQty, sellQty)
                        if (ratio > 10.0) {
                            val backfilled = TradeHistoryStore.backfillLastBuyEntryQty6311(
                                mint = mint,
                                walletVerifiedQty = sellQty,
                                walletDecimals = walletDecimals,
                            )
                            try {
                                PipelineHealthCollector.labelInc("BUY_QTY_BACKFILL_ON_SELL_WRITE_6337")
                                ForensicLogger.lifecycle(
                                    "BUY_QTY_BACKFILL_ON_SELL_WRITE_6337",
                                    "mint=${mint.take(10)} sym=${ts.symbol} buyQtyStale=${lastBuyQty.fmt(4)} sellQtyTruth=${sellQty.fmt(4)} ratio=${ratio.fmt(1)}√ó decimals=$walletDecimals backfilledRows=$backfilled reason=fast_sell_before_promote_verify",
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        // V5.0.6673b ¬ßPAPER_QTY_HEAL_REVERTED (Fire A rollback).
        //
        // The V5.0.6673 heal path was surgically wrong: (a) it assumed
        // `tradeWithMint.price * qty ‚âà sol` as a cost-consistency check
        // for paper rows, but the paper journal uses distinct internal
        // decimal representations for price vs sol so the check
        // frequently passes on numerically-inconsistent rows;
        // (b) `backfillLastBuyEntryQty6311` finds the MOST RECENT BUY
        // for a mint ‚Äî when a stale/replayed SELL fires against a mint
        // that already has a fresh reopen, the heal rewrites the fresh
        // OPEN position's entryQtyToken + remainingQtyToken to the
        // skewed sell qty, producing "qty INVALID (invariant broken)"
        // rows on the operator's dashboard (USMS/STEP screenshot).
        // Pre-6671 stale-qty positions self-drain as they close;
        // wallet-verified LIVE trades continue to backfill through the
        // proven V5.0.6337 path above. No qty writes on the paper leg.
        TradeHistoryStore.recordTrade(tradeWithMint)
        val rowLearningAdmitted4349 = try {
            val rowSane4349 = com.lifecyclebot.engine.learning.TradeRowSanityCheck.inspect(tradeWithMint) == com.lifecyclebot.engine.learning.TradeRowSanityCheck.QuarantineReason.OK
            val sideU6448 = tradeWithMint.side.uppercase()
            val rewardPure6448 = when (sideU6448) {
                "BUY" -> true
                "PARTIAL_SELL" -> {
                    try { PipelineHealthCollector.labelInc("REWARD_PURITY_PARTIAL_LEARNING_BLOCKED_6448") } catch (_: Throwable) {}
                    false
                }
                "SELL" -> {
                    val pid6448 = try { com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.positionIdOf(tradeWithMint.mint.ifBlank { ts.mint }) } catch (_: Throwable) { "" }
                    val accepted6448 = pid6448.isNotBlank() && try { com.lifecyclebot.engine.truth.RewardPurityGate6441.outcomeOf(pid6448) != null } catch (_: Throwable) { false }
                    if (!accepted6448) {
                        try {
                            PipelineHealthCollector.labelInc("REWARD_PURITY_LEARNING_BLOCKED_6448")
                            ForensicLogger.lifecycle("REWARD_PURITY_LEARNING_BLOCKED_6448", "mint=${tradeWithMint.mint.take(10)} side=${tradeWithMint.side} pid=$pid6448 reason=${tradeWithMint.reason.take(80)}")
                        } catch (_: Throwable) {}
                    }
                    accepted6448
                }
                else -> true
            }
            rowSane4349 && rewardPure6448
        } catch (_: Throwable) { true }
        try {
            if (tradeWithMint.side.equals("SELL", true) || tradeWithMint.side.equals("PARTIAL_SELL", true)) {
                val resultMint6078 = tradeWithMint.mint.ifBlank { ts.mint }
                val resultLane6078 = tradeWithMint.tradingMode.ifBlank { resolveExecutionLane(ts, fallback = "STANDARD") }
                val resultStyle6078 = listOf(tradeWithMint.tradingModeEmoji, tradeWithMint.reason)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "result_style_unknown" }
                val resultPnlPct6078 = tradeWithMint.pnlPct
                val resultPnlSol6078 = tradeWithMint.netPnlSol.takeIf { it != 0.0 } ?: tradeWithMint.pnlSol
                val resultTrainable6078 = accountingTrainable && rowLearningAdmitted4349
                val resultAccepted6078 = ledgerAllowsClosedLearning
                val resultIsPaper6078 = tradeWithMint.mode.equals("paper", true) || ts.position.isPaperPosition
                // V5.0.6078 ‚Äî ALL-RESULT OBSERVABILITY FANOUT. Policy heads still
                // train only on accepted/sane rows below, but LLM/SSI/meta-cog context
                // must see every sell-like result with accepted/trainable flags so
                // nothing disappears from the intelligence stack.
                GlobalScope.launch(AppDispatchers.sideEffect) {
                    try {
                        com.lifecyclebot.engine.lab.LlmLabEngine.recordExternalOutcome6078(
                            lane = resultLane6078,
                            style = resultStyle6078,
                            pnlPct = resultPnlPct6078,
                            pnlSol = resultPnlSol6078,
                            trainable = resultTrainable6078,
                            accepted = resultAccepted6078,
                            paper = resultIsPaper6078,
                        )
                    } catch (_: Throwable) {}
                    try {
                        if (!resultAccepted6078 || !resultTrainable6078) {
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "ALL_RESULT_CONTEXT_OBSERVED_6078",
                                "mint=${resultMint6078.take(10)} lane=$resultLane6078 style=${resultStyle6078.take(80)} side=${tradeWithMint.side} pnl=${"%.2f".format(resultPnlPct6078)}% accepted=$resultAccepted6078 trainable=$resultTrainable6078 paper=$resultIsPaper6078",
                            )
                        }
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ALL_RESULT_CONTEXT_OBSERVED_6078")
                    } catch (_: Throwable) {}
                }
                val edgePeak4529 = try { ts.position.peakGainPct } catch (_: Throwable) { tradeWithMint.pnlPct }
                val edgeDraw4529 = try {
                    if (ts.position.entryPrice > 0.0 && ts.position.lowestPrice > 0.0) ((ts.position.lowestPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100.0 else 0.0
                } catch (_: Throwable) { 0.0 }
                MathematicalEdgeEngine.captureTerminal(
                    stage = "recordTrade",
                    lane = tradeWithMint.tradingMode.ifBlank { resolveExecutionLane(ts, fallback = "STANDARD") },
                    source = ts.source.ifBlank { ts.lastPriceSource.ifBlank { "UNKNOWN" } },
                    mint = tradeWithMint.mint.ifBlank { ts.mint },
                    symbol = ts.symbol,
                    side = tradeWithMint.side,
                    reason = tradeWithMint.reason,
                    pnlPct = tradeWithMint.pnlPct,
                    pnlSol = tradeWithMint.pnlSol,
                    sizeSol = tradeWithMint.sol,
                    holdMs = if (tradeWithMint.entryTsMs > 0L) (tradeWithMint.ts - tradeWithMint.entryTsMs).coerceAtLeast(0L) else 0L,
                    peakGainPct = edgePeak4529,
                    maxDrawdownPct = edgeDraw4529,
                    trainable = accountingTrainable && rowLearningAdmitted4349,
                    accepted = ledgerAllowsClosedLearning,
                    score = (tradeWithMint.score.takeIf { it > 0.0 } ?: ts.position.entryScore),
                    regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "NORMAL" },
                )
            }
        } catch (_: Throwable) {}
        // V5.0.4514 ‚Äî CENTRAL TERMINAL POLICY FANOUT.
        // Pending entry heads were previously fed from specific sell paths only
        // (~live/paper sell call sites), while recordTrade() is the actual journal
        // choke point used by all terminal close routes. Consume pending labels here
        // after TradeOutcomeLedger + accounting + TradeRowSanityCheck acceptance so
        // UnifiedPolicyHead / UnifiedExitPolicyHead / ForwardOutcomeModel see every
        // valid terminal close exactly once. These recordOutcome APIs remove pending
        // mint state, so older downstream path calls become harmless no-ops.
        try {
            if (tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                val terminalSnap4514 = tradeWithMint
                val pnlForHeads4514 = terminalSnap4514.pnlPct
                val mintForHeads4514 = terminalSnap4514.mint.ifBlank { ts.mint }
                GlobalScope.launch(AppDispatchers.sideEffect) {
                    try { com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(mintForHeads4514, pnlForHeads4514) } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.UnifiedPolicyHead.recordOutcome(mintForHeads4514, pnlForHeads4514) } catch (_: Throwable) {}
                    // V5.0.6258 ‚Äî PAPER‚ÜíLIVE AGI REWIRE. Central-fanout StrategyHypothesisEngine
                    // recordOutcome so BOTH paper and live closes credit the A/B arms. Prior
                    // impl only fired inside paperSell/liveSell; any close arriving via a
                    // different path (shadow, wallet-recovery, external route) left arms at
                    // n=0 forever. Op-report V5.0.6257 showed 6 active hypotheses with
                    // ctrl=0/var=0 after 1500+ closes. Idempotent: engine.recordOutcome
                    // bails if pending[mint] is empty (already consumed by local fanout).
                    try { com.lifecyclebot.engine.StrategyHypothesisEngine.recordOutcome(mintForHeads4514, pnlForHeads4514) } catch (_: Throwable) {}
                    // V5.0.6260 ‚Äî BYPASS-WIN STREAK. Credit the outcome to
                    // LiveLaneGovernor so a lane that keeps winning through
                    // DNA-approved bypasses gets auto-unpaused early. Idempotent
                    // (recordBypassOutcome bails when mint wasn't a bypass entry).
                    try { com.lifecyclebot.engine.LiveLaneGovernor.recordBypassOutcome(mintForHeads4514, pnlForHeads4514) } catch (_: Throwable) {}
                    // V5.0.6009 ‚Äî CRITICAL BUG FIX: EXIT BRAIN TRAINED BACKWARDS.
                    // Prior label `pnlForHeads4514 > -5.0` marked ANY exit with pnl
                    // above -5% as "optimal" ‚Äî INCLUDING -4%, -3%, -2%, -1% LOSSES.
                    // The brain learned "small losses are optimal exits" and started
                    // paper-handing every winner. UnifiedExitPolicyHead docstring
                    // says the label means "banked >70% of peak or dodged a dump".
                    //
                    // Correct label: exit was optimal iff we banked a real win.
                    // Real win threshold = 2% net (above trade noise / slippage).
                    // Also credit TAKE_PROFIT / TRAILING_STOP reasons which by
                    // definition are managed profitable exits. STOP_LOSS variants
                    // are NEVER optimal ‚Äî those are the paper-handing pattern.
                    val exitReason = terminalSnap4514.reason.uppercase()
                    val exitWasOptimal = when {
                        exitReason.contains("STOP_LOSS") || exitReason.contains("STRICT_SL") || exitReason.contains("STOPLOSS") -> false
                        exitReason.contains("TAKE_PROFIT") || exitReason.contains("TRAILING_STOP") || exitReason.contains("TP_") -> true
                        pnlForHeads4514 >= 2.0 -> true   // real banked win
                        else -> false                     // scratches + all losses = not optimal
                    }
                    try { com.lifecyclebot.engine.UnifiedExitPolicyHead.recordOutcome(mintForHeads4514, exitWasOptimal) } catch (_: Throwable) {}
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "UNIFIED_EXIT_POLICY_HEAD_LABEL_FIX_6009",
                            "mint=${mintForHeads4514.take(10)} pnl=${"%.2f".format(pnlForHeads4514)}% reason=$exitReason optimal=$exitWasOptimal",
                        )
                    } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("CENTRAL_TERMINAL_POLICY_FANOUT_4514") } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        try {
            if (tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                LivePaperDriftSentinel.onTerminalClose(tradeWithMint)
                ExitCostMicrobrain.recordTerminalExit(
                    trade = tradeWithMint,
                    liquidityUsd = ts.position.entryLiquidityUsd.takeIf { it > 0.0 } ?: ts.lastLiquidityUsd,
                    lane = tradeWithMint.tradingMode.ifBlank { resolveExecutionLane(ts, fallback = "STANDARD") },
                    source = ts.source.ifBlank { ts.lastPriceSource.ifBlank { "UNKNOWN" } },
                )
            }
        } catch (_: Throwable) {}

        // V5.9.994 ‚Äî ML TRAINING LOOP (Doctrine #4 ‚Äî mature WR requires the
        // V5.0.4207 ‚Äî SellOptimizationAI.recordExitOutcome was a dead feedback edge.
        // registerPosition/evaluate/closePosition were wired, but the strategy win-rate
        // learner never received terminal outcomes. Feed it from the same idempotent
        // closed-learning gate used by ML/KillSwitch: terminal SELL only, not partials,
        // not recovered scratch, not invalid accounting. `wouldHaveBeen` is a bounded
        // excursion proxy (peak for winners, low-water for losers) until a delayed +5m
        // labeler exists; this is advisory learning only and never blocks exit finality.
        try {
            if (tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                val sellOptTrade = tradeWithMint
                val posEntryPrice = ts.position.entryPrice
                val posPeakPrice = ts.position.highestPrice
                val posLowPrice = ts.position.lowestPrice
                val holdMins = if (sellOptTrade.entryTsMs > 0L) ((sellOptTrade.ts - sellOptTrade.entryTsMs) / 60_000L).toInt().coerceAtLeast(0) else 0
                val wouldHaveBeenProxy = try {
                    when {
                        sellOptTrade.pnlPct >= 0.0 && posEntryPrice > 0.0 && posPeakPrice > 0.0 -> {
                            val peakPct = ((posPeakPrice - posEntryPrice) / posEntryPrice) * 100.0
                            maxOf(sellOptTrade.pnlPct, peakPct)
                        }
                        sellOptTrade.pnlPct < 0.0 && posEntryPrice > 0.0 && posLowPrice > 0.0 -> {
                            val lowPct = ((posLowPrice - posEntryPrice) / posEntryPrice) * 100.0
                            minOf(sellOptTrade.pnlPct, lowPct)
                        }
                        else -> sellOptTrade.pnlPct
                    }
                } catch (_: Throwable) { sellOptTrade.pnlPct }
                val sellOptStrategy = when {
                    sellOptTrade.reason.contains("stop", true) || sellOptTrade.reason.contains("risk", true) || sellOptTrade.reason.contains("rug", true) -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.STOP_LOSS
                    sellOptTrade.reason.contains("profit_lock", true) || sellOptTrade.reason.contains("trailing", true) || sellOptTrade.reason.contains("runner", true) -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.TRAILING_LOCK
                    sellOptTrade.reason.contains("time", true) || sellOptTrade.reason.contains("stale", true) || sellOptTrade.reason.contains("maxhold", true) -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.TIME_DECAY_EXIT
                    sellOptTrade.reason.contains("whale", true) -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.WHALE_EXIT
                    sellOptTrade.reason.contains("learn", true) || sellOptTrade.reason.contains("fluid", true) -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.LEARNED_EXIT
                    sellOptTrade.reason.contains("momentum", true) || sellOptTrade.reason.contains("take_profit", true) || sellOptTrade.reason.contains("sweep", true) -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.MOMENTUM_EXIT
                    else -> com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy.FULL_EXIT
                }
                GlobalScope.launch(AppDispatchers.sideEffect) {
                    try {
                        com.lifecyclebot.v3.scoring.SellOptimizationAI.recordExitOutcome(
                            strategy = sellOptStrategy,
                            exitPnlPct = sellOptTrade.pnlPct,
                            wouldHaveBeen = wouldHaveBeenProxy,
                            tokenType = sellOptTrade.tradingMode.ifBlank { "STANDARD" },
                            holdTimeMinutes = holdMins,
                        )
                        try { PipelineHealthCollector.labelInc("SELL_OPTIMIZATION_OUTCOME_LEARNED_4207") } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // V5.0.4241 ‚Äî ASI/A18/A19 terminal-outcome fanout.
        // Feed the new SemanticPatternGraph and CounterfactualReplayEngine from
        // the same terminal SELL + closed-learning + accounting-trainable gate
        // used by SellOptimizationAI. This is side-effect/background only:
        // it never rewrites journal truth, never touches entry gates, and never
        // blocks sell finality.
        try {
            if (tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                val graphTrade = tradeWithMint
                val graphLane = graphTrade.tradingMode.ifBlank { "STANDARD" }
                val graphSource = ts.source.ifBlank { ts.lastPriceSource.ifBlank { "UNKNOWN" } }
                val graphSetup = "phase=${ts.phase}|ema=${ts.meta.emafanAlignment}|rsi=${ts.meta.rsi}|liq=${ts.lastLiquidityUsd.toInt()}|mcap=${ts.lastMcap.toLong()}|score=${graphTrade.score.toInt()}|source=$graphSource"
                val graphEntry = ts.position.entryPrice
                val graphPeak = ts.position.highestPrice
                val graphLow = ts.position.lowestPrice
                val graphPeakPct = try { if (graphEntry > 0.0 && graphPeak > 0.0) ((graphPeak - graphEntry) / graphEntry) * 100.0 else graphTrade.pnlPct } catch (_: Throwable) { graphTrade.pnlPct }
                val graphLossPct = try { if (graphEntry > 0.0 && graphLow > 0.0) ((graphLow - graphEntry) / graphEntry) * 100.0 else minOf(0.0, graphTrade.pnlPct) } catch (_: Throwable) { minOf(0.0, graphTrade.pnlPct) }
                val graphHoldSeconds = if (graphTrade.entryTsMs > 0L) ((graphTrade.ts - graphTrade.entryTsMs) / 1000L).coerceAtLeast(0L) else 0L
                val runnerReplayHint = try { CounterfactualReplayEngine.policyHints(graphLane) } catch (_: Throwable) { "" }
                val graphMint = graphTrade.mint.ifBlank { ts.mint }
                val graphSymbol = ts.symbol
                val graphDeployer = ts.tokenMap.creatorOrDevWallet
                GlobalScope.launch(AppDispatchers.sideEffect) {
                    try {
                        com.lifecyclebot.engine.SemanticPatternGraph.recordOutcome(
                            lane = graphLane,
                            source = graphSource,
                            setup = graphSetup,
                            deployer = graphDeployer,
                            exitReason = graphTrade.reason,
                            failureMode = if (graphTrade.pnlPct < 0.0) graphTrade.reason else "",
                            pnlPct = graphTrade.pnlPct,
                            peakGainPct = graphPeakPct,
                        )
                        com.lifecyclebot.engine.CounterfactualReplayEngine.recordTerminalTrade(
                            lane = graphLane,
                            exitReason = graphTrade.reason,
                            realizedPnlPct = graphTrade.pnlPct,
                            peakGainPct = graphPeakPct,
                            maxLossPct = graphLossPct,
                            holdSeconds = graphHoldSeconds,
                        )
                        com.lifecyclebot.engine.RunnerRetentionOptimizer.recordTerminalExit(
                            trade = graphTrade,
                            lane = graphLane,
                            peakGainPct = graphPeakPct,
                            holdSeconds = graphHoldSeconds,
                            replayHint = runnerReplayHint,
                        )
                        com.lifecyclebot.engine.RunnerExitShadowLedger.recordTerminalExit(
                            lane = graphLane,
                            exitReason = graphTrade.reason,
                            realizedPnlPct = graphTrade.pnlPct,
                            peakGainPct = graphPeakPct,
                            holdSeconds = graphHoldSeconds,
                        )
                        val researchReason = graphTrade.reason.uppercase()
                        if (graphTrade.pnlPct < -8.0 || researchReason.contains("RUG") || researchReason.contains("LP") || researchReason.contains("HOLDER")) {
                            com.lifecyclebot.engine.ResearchScout.enqueueBackgroundRequest(
                                mint = graphMint,
                                symbol = graphSymbol,
                                reason = "terminal_exit_research_4242:${graphTrade.reason.take(80)}",
                                sourceTag = "BACKGROUND_RESEARCH_SCOUT_TERMINAL_EXIT_4242",
                            )
                            try { PipelineHealthCollector.labelInc("RESEARCH_SCOUT_TERMINAL_EXIT_QUEUED_4242") } catch (_: Throwable) {}
                        }
                        try {
                            val swept4258 = com.lifecyclebot.engine.ResearchScout.maybeRunPeriodicBackgroundSweep(
                                sourceTag = "BACKGROUND_RESEARCH_SCOUT_PERIODIC_4258",
                            )
                            if (swept4258 > 0) PipelineHealthCollector.labelInc("RESEARCH_SCOUT_PERIODIC_SWEEP_4258")
                        } catch (_: Throwable) {}
                        try {
                            if (com.lifecyclebot.engine.ReflectiveOptimizerGEPA.runBackgroundReflection(
                                    lane = graphLane,
                                    sourceTag = "BACKGROUND_GEPA_TERMINAL_OUTCOME_4244",
                                )
                            ) {
                                PipelineHealthCollector.labelInc("GEPA_TERMINAL_REFLECTION_QUEUED_4244")
                            }
                        } catch (_: Throwable) {}
                        try { PipelineHealthCollector.labelInc("SEMANTIC_COUNTERFACTUAL_OUTCOME_4241") } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // V5.9.994 ‚Äî ML TRAINING LOOP (Doctrine #4 ‚Äî mature WR requires the
        // learners actually learn). Audit (V5.9.962‚Üí994 sweep) found
        // OnDeviceMLEngine.predict() was called by FinalDecisionGate on every
        // entry, but OnDeviceMLEngine.recordTrade() / TradeHistoryStore.
        // recordTradeForML had ZERO callers ‚Äî the on-device ML loop was
        // predicting on stale shipped weights and never training on outcomes.
        // Wire the SELL closeout to feed the ML engine with the exit context.
        // Entry-time fields come from Position snapshots (entryLiquidityUsd);
        // exit-time fields come from current TokenState. Field mapping mirrors
        // FinalDecisionGate.predict() so train+predict feature sets align.
        // Fail-open: any exception is swallowed ‚Äî never block the sell finalize.
        //
        // V5.9.998 ‚Äî operator triage: bot loop was dying after ~3 trade rounds
        // because this block (and the KillSwitch block below) ran SYNCHRONOUSLY
        // on the same thread as the bot loop's tick. Each sell finalize blocked
        // 100-500ms for the ML feature build + SQLite write; with 30+ sells/min
        // the cycle time exploded past the heartbeat timeout. Moved to a
        // background IO coroutine ‚Äî ML still trains on every outcome, but no
        // longer wedges the sell path. Snapshot inputs into immutable locals
        // BEFORE launch so the coroutine has stable values (no race on ts).
        try {
            if ((tradeWithMint.side == "SELL" || tradeWithMint.side == "PARTIAL_SELL") && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                val tradeSnap     = tradeWithMint
                val recentSnap    = ts.history.takeLast(30).toList()
                val entryWindow   = ts.history.takeLast(60).take(30).toList()
                val isRug = tradeSnap.reason.uppercase().let { r ->
                    r.contains("RUG") || r.contains("ML_RUG") || tradeSnap.pnlPct <= -50.0
                }
                val entryLiq = if (ts.position.entryLiquidityUsd > 0.0)
                    ts.position.entryLiquidityUsd else ts.lastLiquidityUsd
                val exitLiq = ts.lastLiquidityUsd
                val holders = ts.history.lastOrNull()?.holderCount ?: 0
                val rugScore = ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 50
                val mintRev  = ts.safety.mintAuthorityDisabled ?: false
                val freezeRev= ts.safety.freezeAuthorityDisabled ?: false
                val topHold  = ts.safety.topHolderPct.takeIf { it >= 0 } ?: (ts.topHolderPct ?: 0.0)
                val rsiSnap  = ts.meta.rsi
                val emaSnap  = ts.meta.emafanAlignment
                val paperLiveWeight4351 = try {
                    val scoreBand4351 = "S" + ((tradeSnap.score.toInt().coerceIn(0, 100) / 10) * 10).toString().padStart(2, '0')
                    val lane4351 = tradeSnap.tradingMode.ifBlank { ts.position.tradingMode ?: "STANDARD" }
                    val isPaper4351 = isPaperRT()
                    if (!isPaper4351) {
                        com.lifecyclebot.engine.learning.PaperLiveConfidenceWeights.noteLiveSample(lane4351, scoreBand4351)
                    }
                    com.lifecyclebot.engine.learning.PaperLiveConfidenceWeights.weight(lane4351, scoreBand4351, isPaper4351)
                } catch (_: Throwable) { if (isPaperRT()) 0.40 else 1.0 }
                try { PipelineHealthCollector.labelInc("PAPER_LIVE_CONFIDENCE_WEIGHT_4351|${if (isPaperRT()) "paper" else "live"}|${(paperLiveWeight4351 * 100).toInt()}") } catch (_: Throwable) {}
                GlobalScope.launch(AppDispatchers.sideEffect) {
                    try {
                        TradeHistoryStore.recordTradeForML(
                            trade              = tradeSnap,
                            candlesAtEntry     = entryWindow,
                            candlesAtExit      = recentSnap,
                            liquidityAtEntry   = entryLiq,
                            liquidityAtExit    = exitLiq,
                            holdersAtEntry     = holders,
                            holdersAtExit      = holders,
                            rugcheckScore      = rugScore,
                            mintRevoked        = mintRev,
                            freezeRevoked      = freezeRev,
                            topHolderPct       = topHold,
                            rsi                = rsiSnap,
                            emaAlignment       = emaSnap,
                            wasRug             = isRug,
                        )
                    } catch (_: Throwable) { /* fail-open background */ }
                }
            }
        } catch (_: Throwable) {}

        // V5.9.994 ‚Äî KILL SWITCH FEED (Doctrine #4 ‚Äî safety guard must be fed).
        // Audit found KillSwitch.recordTrade() had ZERO callers. The kill
        // switch initializes in BotService and has onKillTriggered + onWarning
        // callbacks wired, but no trade outcome flow ‚Üí it would never trigger
        // in live mode. KillSwitch.recordTrade short-circuits in paper mode
        // (returns true immediately), so this is fail-safe for paper trading
        // but actually arms the safety guard for live. Fail-open: any
        // exception swallowed ‚Äî never block sell finalize.
        //
        // V5.9.998 ‚Äî operator triage: also moved to background IO coroutine
        // (see ML block above). KillSwitch.recordTrade touches SharedPrefs
        // and a rolling buffer; cheap individually but with 30+ sells/min
        // it added cumulative latency to the bot tick. Async = never blocks
        // the loop, still arms the kill switch in live mode within ms.
        try {
            if ((tradeWithMint.side == "SELL" || tradeWithMint.side == "PARTIAL_SELL") && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                // BotService.instance?.applicationContext ‚Äî same pattern as
                // GeminiCopilot.kt:595. solBalance comes from the canonical
                // WalletManager state ‚Äî same accessor as BotService.kt:3874
                // (live currentBalance for kill-switch decisions).
                val appCtx = com.lifecyclebot.engine.BotService.instance?.applicationContext
                val currentSol = try {
                    com.lifecyclebot.engine.BotService.walletManager.state.value.solBalance
                } catch (_: Throwable) { 0.0 }
                if (appCtx != null && currentSol > 0.0) {
                    val pnlSnap = tradeWithMint.pnlPct
                    GlobalScope.launch(AppDispatchers.sideEffect) {
                        try {
                            com.lifecyclebot.engine.KillSwitch.recordTrade(
                                context        = appCtx,
                                pnlPct         = pnlSnap,
                                currentBalance = currentSol,
                            )
                        } catch (_: Throwable) { /* fail-open background */ }
                    }
                }
            }
        } catch (_: Throwable) {}

        // V5.9.996 ‚Äî COPY-TRADE OUTCOME LOOP step 2/2 (Doctrine #4 ‚Äî
        // genuine learning). Audit found CopyTradeEngine.recordResult had
        // ZERO callers across all .kt files (main + test trees). The
        // engine tracks per-wallet wins/losses/PnL and auto-pauses bad
        // copy wallets via the winRate threshold at recordResult line
        // 122 ‚Äî but with no outcome flow the stats stay at zero forever
        // and auto-pause never triggers. Without this, the bot follows
        // unprofitable copy wallets indefinitely.
        //
        // Attribution: Position.copyWallet was stamped at entry (step
        // 1/2 above). If blank, this isn't a copy trade ‚Äî skip silently.
        // CopyTradeEngine is a lateinit on BotService.instance, same
        // access pattern as autoMode (BotViewModel.kt:119).
        //
        // Fail-open: any exception swallowed ‚Äî never block sell finalize.
        try {
            if ((tradeWithMint.side == "SELL" || tradeWithMint.side == "PARTIAL_SELL")
                && ledgerAllowsClosedLearning
                && accountingTrainable
                && ts.position.copyWallet.isNotBlank()
            ) {
                com.lifecyclebot.engine.BotService.instance
                    ?.copyTradeEngine
                    ?.recordResult(
                        wallet = ts.position.copyWallet,
                        pnlSol = tradeWithMint.pnlSol,
                    )
            }
        } catch (_: Throwable) {}

        // V5.9.69 PatternClassifier hooks ‚Äî continuous-feature online learner.
        // BUY: stash features. SELL: run one SGD step on the outcome.
        try {
            if (trade.side == "BUY") {
                val patternKey6643 = tradeWithMint.positionId.ifBlank { ts.mint }
                val patternFeatures6643 = PatternClassifier.extract(ts)
                val patternEntry6643 = {
                    PatternClassifier.noteEntry(patternKey6643, patternFeatures6643)
                    try { PipelineHealthCollector.labelInc("PATTERN_ENTRY_DURABLE_DELIVERED_6643") } catch (_: Throwable) {}
                }
                if (tradeWithMint.mode.equals("paper", true)) {
                    val eventId = tradeWithMint.economicEventId
                    if (!com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.afterCommitted(eventId, patternEntry6643)) {
                        try { PipelineHealthCollector.labelInc("PATTERN_ENTRY_MISSING_ECONOMIC_EVENT_6643") } catch (_: Throwable) {}
                    }
                } else patternEntry6643()
                // V5.9.380 ‚Äî capture each of the 26 meme layers' votes at
                // entry. On SELL closeout (below, ~line 803) we replay each
                // vote into PerpsLearningBridge so layers diverge in accuracy
                // based on their OWN opinion, not the bot's aggregate WR.
                try {
                    com.lifecyclebot.learning.LayerVoteSampler.captureAllMemeVotes(ts)
                } catch (_: Exception) {}
            } else if (trade.side == "SELL" && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) {
                // V5.0.3948 ‚Äî train chart/movement pattern classifier on terminal
                // outcomes only. Partial sells are useful accounting, but they are
                // not the final movement result; consuming the pending entry on the
                // first partial made live learning miss the runner/round-trip label
                // and made the bot feel like it never learned the real move.
                val patternKey6643 = tradeWithMint.positionId.ifBlank { ts.mint }
                val patternExit6643 = {
                    PatternClassifier.noteExit(
                        positionKey = patternKey6643,
                        pnlPct = trade.pnlPct,
                        isLive = trade.mode.equals("live", ignoreCase = true)
                    )
                    try { PipelineHealthCollector.labelInc("PATTERN_OUTCOME_DURABLE_DELIVERED_6643") } catch (_: Throwable) {}
                }
                if (tradeWithMint.mode.equals("paper", true)) {
                    val eventId = tradeWithMint.economicEventId
                    if (!com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.afterCommitted(eventId, patternExit6643)) {
                        try { PipelineHealthCollector.labelInc("PATTERN_OUTCOME_MISSING_ECONOMIC_EVENT_6643") } catch (_: Throwable) {}
                    }
                } else patternExit6643()
            }
        } catch (_: Exception) {}

        // V5.9.58: reset BotBrain's drought watchdog on every BUY so the
        // watchdog only eases thresholds if the scanner has truly gone
        // V5.9.1062 ‚Äî onBuyFired() restored to synchronous path.
        // Async execution (1060) caused stalled trading after ~150 trades.
        if (trade.side == "BUY") {
            try { brain?.onBuyFired() } catch (_: Exception) {}
        }

        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // V5.9.1060 ‚Äî ASYNC LEARNING FANOUT
        // ToxicMode, MetaCognition, BehaviorAI, Copilot, PersonalityMemory,
        // CorrelationHedge, SessionEdge, LayerVoteStore, RunTracker30D ‚Äî
        // all are pure learning signals, no return value used by the sell path.
        // Moved to GlobalScope.launch(IO) to eliminate per-trade latency on the
        // bot loop thread. Snapshots captured BEFORE launch so coroutine has
        // stable values and there is no race on ts / trade / position fields.
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        if (((trade.side.equals("SELL", true) || trade.side.equals("PARTIAL_SELL", true)) && ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349) || trade.side.equals("BUY", true)) {
            val _fanoutSide       = trade.side
            val _fanoutPnlPct     = trade.pnlPct ?: 0.0
            val _fanoutMint       = ts.mint
            val _fanoutSymbol     = ts.symbol
            val _fanoutReason     = trade.reason.ifBlank { "unknown" }
            val _fanoutSol        = trade.sol
            val _fanoutIsPaper    = isPaperRT()
            val _fanoutEntryTime  = ts.position.entryTime
            val _fanoutTradingMode = (ts.position.tradingMode ?: "").uppercase()
            val _fanoutSource     = ts.source
            val _fanoutPositionId = try { trade.positionId.ifBlank { com.lifecyclebot.engine.TradeOutcomeLedger.positionId(ts, trade) } } catch (_: Throwable) { trade.positionId }
            val _fanoutEventTsMs  = trade.ts.takeIf { it > 0L } ?: System.currentTimeMillis()
            val _fanoutBuildTag   = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" }
            val _fanoutEntryPrice = ts.position.entryPrice
            val _fanoutExitPrice  = trade.price
            val _fanoutEntryScore = ts.entryScore
            val _fanoutIsRun      = try { RunTracker30D.isRunActive() } catch (_: Throwable) { false }
            val _fanoutRunScore   = ts.trades.lastOrNull { it.side == "BUY" }?.score?.coerceIn(0.0, 100.0)?.toInt() ?: 50
            val _fanoutConfidence = ts.entryScore.toInt().coerceIn(0, 100)
            GlobalScope.launch(AppDispatchers.sideEffect) {
                try {
                    try {
                        LearningFanoutMuxSentinel.report(
                            mode = if (_fanoutIsPaper) "paper" else "live",
                            lane = _fanoutTradingMode,
                            source = _fanoutSource,
                            positionId = _fanoutPositionId,
                            mint = _fanoutMint,
                            symbol = _fanoutSymbol,
                            eventTsMs = _fanoutEventTsMs,
                            build = _fanoutBuildTag,
                        )
                        LiveWalletGrowthGovernorReport.record(trade, _fanoutIsPaper)
                        val _fanoutIsSellMovement6041 = _fanoutSide.equals("SELL", true) || _fanoutSide.equals("PARTIAL_SELL", true)
                        if (_fanoutIsSellMovement6041) { LiveWalletGrowthGovernorReport.maybeEmit(); OperatorKpiCloseoutReport.emit() }
                        if (_fanoutSide.equals("BUY", true)) SourceFamilyOpportunityScorecard.recordOpened(_fanoutSource)
                        if (_fanoutIsSellMovement6041) { SourceFamilyOpportunityScorecard.recordClosed(_fanoutSource, trade); SourceFamilyOpportunityScorecard.maybeReport() }
                    } catch (_: Throwable) {}
                    // ‚îÄ‚îÄ ToxicModeCircuitBreaker ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                    if (_fanoutSide == "SELL" && _fanoutPnlPct < 0) {
                        try {
                            val mode = _fanoutTradingMode.takeIf { isMeaningfulLaneName(it) } ?: "STANDARD"
                            ToxicModeCircuitBreaker.recordLoss(
                                mode = mode,
                                pnlPct = _fanoutPnlPct,
                                mint = _fanoutMint,
                                symbol = _fanoutSymbol
                            )
                        } catch (_: Exception) {}
                    }
                    if (_fanoutSide.equals("PARTIAL_SELL", true)) {
                        val holdTimeMs = if (_fanoutEntryTime > 0) System.currentTimeMillis() - _fanoutEntryTime else 0L
                        val _fluidTm = _fanoutTradingMode
                        val _behAsset = when (_fluidTm) {
                            "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS" -> "SHITCOIN"
                            "EXPRESS"                                         -> "EXPRESS"
                            "CYCLIC"                                          -> "CYCLIC"
                            "QUALITY"                                         -> "QUALITY"
                            "BLUECHIP", "BLUE_CHIP"                           -> "BLUECHIP"
                            "MOONSHOT"                                        -> "MOONSHOT"
                            "TREASURY", "CASHGEN"                             -> "TREASURY"
                            "PRESALE_SNIPE", "PROJECT_SNIPER"                 -> "PRESALE_SNIPE"
                            "MANIPULATED"                                     -> "MANIPULATED"
                            "DIP_HUNTER"                                      -> "DIP_HUNTER"
                            else                                              -> "MEME"
                        }
                        if (_fanoutPnlPct < 0) {
                            try { ToxicModeCircuitBreaker.recordLoss(_fanoutTradingMode.takeIf { isMeaningfulLaneName(it) } ?: "STANDARD", _fanoutPnlPct, _fanoutMint, _fanoutSymbol) } catch (_: Exception) {}
                        }
                        try { com.lifecyclebot.v3.scoring.BehaviorAI.recordTradeForAsset(pnlPct = _fanoutPnlPct, reason = _fanoutReason, mint = _fanoutMint, isPaperMode = _fanoutIsPaper, assetClass = _behAsset) } catch (_: Exception) {}
                        if (_behAsset == "MEME" && _fanoutReason != "DEAD_TOKEN_NO_PRICE_EXIT") {
                            try { com.lifecyclebot.engine.TradingCopilot.recordTradeForAsset(pnlPct = _fanoutPnlPct, isPaper = _fanoutIsPaper, assetClass = "MEME") } catch (_: Exception) {}
                        }
                        if (_fanoutIsRun) {
                            try {
                                val holdTimeSec = if (_fanoutEntryTime > 0) (System.currentTimeMillis() - _fanoutEntryTime) / 1000 else 0L
                                val mode = _fanoutTradingMode.takeIf { isMeaningfulLaneName(it) } ?: "STANDARD"
                                RunTracker30D.recordTrade(symbol = _fanoutSymbol, mint = _fanoutMint, entryPrice = _fanoutEntryPrice, exitPrice = _fanoutExitPrice, sizeSol = _fanoutSol, pnlPct = _fanoutPnlPct, holdTimeSec = holdTimeSec, mode = mode, score = _fanoutRunScore, confidence = _fanoutConfidence, decision = _fanoutReason.ifBlank { "PARTIAL_SELL" })
                                EmergentGuardrails.recordTradeExecution()
                            } catch (e: Exception) { ErrorLogger.debug("Executor", "RunTracker30D partial record error: ${e.message}") }
                        }
                        try { PipelineHealthCollector.labelInc("PARTIAL_SELL_MOVEMENT_FANOUT_6041") } catch (_: Throwable) {}
                    }
                    if (_fanoutSide == "SELL") {
                        try { CapitalEfficiencyBrain.recordTerminalTrade(trade, _fanoutTradingMode, _fanoutSource) } catch (_: Throwable) {}
                        val holdTimeMs = if (_fanoutEntryTime > 0) System.currentTimeMillis() - _fanoutEntryTime else 0L
                        try {
                            val arbEval4348 = com.lifecyclebot.v3.arb.ArbScannerAI.cachedOpportunity(_fanoutMint, ttlMs = 6L * 60L * 60L * 1000L)
                            if (arbEval4348 != null) {
                                com.lifecyclebot.v3.arb.ArbLearning.recordOutcome(
                                    com.lifecyclebot.v3.arb.ArbOutcome(
                                        mint = _fanoutMint,
                                        symbol = _fanoutSymbol,
                                        arbType = arbEval4348.arbType,
                                        band = arbEval4348.band,
                                        entryScore = arbEval4348.score.coerceIn(0, 100),
                                        entryConfidence = _fanoutConfidence,
                                        holdSeconds = (holdTimeMs / 1000L).toInt().coerceAtLeast(0),
                                        pnlPct = _fanoutPnlPct,
                                        isWin = _fanoutPnlPct >= 0.5,
                                        exitReason = _fanoutReason,
                                        timestampMs = _fanoutEventTsMs,
                                    )
                                )
                                PipelineHealthCollector.labelInc("ARB_LEARNING_TERMINAL_OUTCOME_4348|${arbEval4348.arbType}")
                            }
                        } catch (_: Throwable) {}
                        val _fluidTm = _fanoutTradingMode
                        val isMemeBaseClose = _fluidTm.isBlank() || _fluidTm !in setOf(
                            "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS", "EXPRESS", "CYCLIC",
                            "QUALITY", "BLUECHIP", "BLUE_CHIP", "MOONSHOT", "TREASURY", "CASHGEN",
                            "PRESALE_SNIPE", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER",
                            "WHALE_FOLLOW", "COPYTRADE", "WALLET_RECOVERED"
                        )
                        val _behAsset = when (_fluidTm) {
                            "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS" -> "SHITCOIN"
                            "EXPRESS"                                         -> "EXPRESS"
                            "CYCLIC"                                          -> "CYCLIC"
                            "QUALITY"                                         -> "QUALITY"
                            "BLUECHIP", "BLUE_CHIP"                           -> "BLUECHIP"
                            "MOONSHOT"                                        -> "MOONSHOT"
                            "TREASURY", "CASHGEN"                             -> "TREASURY"
                            "PRESALE_SNIPE", "PROJECT_SNIPER"                 -> "PRESALE_SNIPE"
                            "MANIPULATED"                                     -> "MANIPULATED"
                            "DIP_HUNTER"                                      -> "DIP_HUNTER"
                            else                                              -> "MEME"
                        }
                        // ‚îÄ‚îÄ MetaCognitionAI ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        if (isMemeBaseClose) {
                            try {
                                com.lifecyclebot.v3.scoring.MetaCognitionAI.recordTradeOutcome(
                                    mint = _fanoutMint, symbol = _fanoutSymbol,
                                    pnlPct = _fanoutPnlPct, holdTimeMs = holdTimeMs,
                                    exitReason = _fanoutReason
                                )
                            } catch (_: Exception) {}
                        }
                        // ‚îÄ‚îÄ BehaviorAI ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        try {
                            com.lifecyclebot.v3.scoring.BehaviorAI.recordTradeForAsset(
                                pnlPct = _fanoutPnlPct, reason = _fanoutReason,
                                mint = _fanoutMint, isPaperMode = _fanoutIsPaper,
                                assetClass = _behAsset,
                            )
                        } catch (_: Exception) {}
                        // ‚îÄ‚îÄ TradingCopilot ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        if (_behAsset == "MEME" && _fanoutReason != "DEAD_TOKEN_NO_PRICE_EXIT") {
                            try {
                                com.lifecyclebot.engine.TradingCopilot.recordTradeForAsset(
                                    pnlPct = _fanoutPnlPct, isPaper = _fanoutIsPaper,
                                    assetClass = "MEME",
                                )
                            } catch (_: Exception) {}
                        }
                        // ‚îÄ‚îÄ PersonalityMemoryStore ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        if (isMemeBaseClose) {
                            try {
                                val activePersona = try {
                                    com.lifecyclebot.AATEApp.appContextOrNull()?.let {
                                        Personalities.getActive(it).id
                                    } ?: "aate"
                                } catch (_: Exception) { "aate" }
                                PersonalityMemoryStore.recordTradeOutcome(
                                    _fanoutPnlPct, 0.0,
                                    if (_fanoutEntryTime > 0) ((System.currentTimeMillis() - _fanoutEntryTime) / 60_000L).toInt() else 0
                                )
                                PersonalityMemoryStore.recordPersonaTrade(activePersona, _fanoutPnlPct)
                            } catch (_: Exception) {}
                        }
                        // ‚îÄ‚îÄ CorrelationHedgeAI + SessionEdgeAI ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        if (_behAsset == "MEME") {
                            try {
                                val won = _fanoutPnlPct > 0.5
                                com.lifecyclebot.v3.scoring.CorrelationHedgeAI.registerClosed(_fanoutMint)
                                com.lifecyclebot.v3.scoring.SessionEdgeAI.recordOutcome(
                                    com.lifecyclebot.v3.scoring.SessionEdgeAI.currentSession(), won)
                            } catch (_: Exception) {}
                        }
                        // ‚îÄ‚îÄ LayerVoteStore ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        if (isMemeBaseClose) {
                            try {
                                com.lifecyclebot.learning.LayerVoteStore.closeoutMeme(
                                    mint = _fanoutMint, isWin = _fanoutPnlPct >= 1.0,
                                    pnlPct = _fanoutPnlPct, symbol = _fanoutSymbol,
                                )
                            } catch (_: Exception) {}
                        }
                        // ‚îÄ‚îÄ RunTracker30D ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
                        if (_fanoutIsRun) {
                            try {
                                val holdTimeSec = if (_fanoutEntryTime > 0)
                                    (System.currentTimeMillis() - _fanoutEntryTime) / 1000 else 0L
                                // V5.9.1556b ‚Äî UNKNOWN is not a lane. RunTracker must use
                                // the same source-of-creation lane already resolved for the
                                // Trade journal, not ModeRouter's coarse classifier fallback.
                                val mode = _fanoutTradingMode.takeIf { isMeaningfulLaneName(it) } ?: "STANDARD"
                                RunTracker30D.recordTrade(
                                    symbol = _fanoutSymbol, mint = _fanoutMint,
                                    entryPrice = _fanoutEntryPrice, exitPrice = _fanoutExitPrice,
                                    sizeSol = _fanoutSol, pnlPct = _fanoutPnlPct,
                                    holdTimeSec = holdTimeSec, mode = mode,
                                    score = _fanoutRunScore, confidence = _fanoutConfidence,
                                    decision = _fanoutReason.ifBlank { "AUTO" }
                                )
                                EmergentGuardrails.recordTradeExecution()
                            } catch (e: Exception) {
                                ErrorLogger.debug("Executor", "RunTracker30D record error: ${e.message}")
                            }
                        }
                    }
                } catch (_: Throwable) { /* fail-open ‚Äî never block sell path */ }
            }
        }

        // V5.9.1161 ‚Äî RICH CANONICAL PUBLISH (SELL + valid PARTIAL_SELL)
        if (tradeWithMint.side.equals("SELL", ignoreCase = true) || tradeWithMint.side.equals("PARTIAL_SELL", ignoreCase = true)) {
            // V5.9.486 ‚Äî fire-and-forget LLM exit narration (async internally, no block)
            try {
                val holdMin = if (ts.position.entryTime > 0)
                    ((System.currentTimeMillis() - ts.position.entryTime) / 60_000L).toInt()
                else 0
                com.lifecyclebot.network.EmergentLlmClient.narrateExitAsync(
                    symbol = ts.symbol, reason = trade.reason.ifBlank { "unknown" },
                    pnlPct = trade.pnlPct, holdMinutes = holdMin,
                ) { narration -> try { onLog("ü™∂ ${ts.symbol}: $narration", ts.mint) } catch (_: Exception) {} }
            } catch (_: Throwable) {}

            // V5.9.495z9 ‚Äî RICH CANONICAL PUBLISH. Operator: 'wire AdaptiveLearning,
            // RunTracker30D, BehaviorLearning, MetaCognitionAI at their feature-rich
            // emit sites'. We are at the universal trade-close site with ts/trade
            // both in scope, so capture mode/score/conf/holdTime/exitReason and
            // publish a fully-populated CanonicalTradeOutcome. Marks the tradeId
            // so the TradeHistoryStore legacy bridge skips it (no double count).
            try {
                if (tradeWithMint.side.equals("SELL", ignoreCase = true) || tradeWithMint.side.equals("PARTIAL_SELL", ignoreCase = true)) {
                    val tradeId = "${tradeWithMint.mint}_${tradeWithMint.ts}"
                    val isPaperEnv = isPaperRT()
                    // V5.9.1509 ‚Äî NET-OF-FEE WIN/LOSS CLASSIFICATION (operator: "see
                    // heaps of win alerts but the gain doesn't represent true gains").
                    // V5.0.3868 ‚Äî tradeWithMint has already been normalized at the
                    // legacy/journal choke point so recorded pnlPct represents executable
                    // net edge when fee/net fields exist. Keep classification net-SOL based
                    // so paper/live readiness and canonical outcomes agree.
                    val grossPnl = tradeWithMint.pnlPct
                    val costBasis = (tradeWithMint.netPnlSol.takeIf { it != 0.0 }?.let { _ ->
                        // prefer the entry cost actually consumed by this (partial) sell
                        ts.position.costSol.takeIf { it > 0.0 } ?: tradeWithMint.sol.takeIf { it > 0.0 }
                    }) ?: (ts.position.costSol.takeIf { it > 0.0 } ?: tradeWithMint.sol)
                    val netPnlPct = if (!isPaperEnv && tradeWithMint.netPnlSol != 0.0 && costBasis != null && costBasis > 0.0) {
                        (tradeWithMint.netPnlSol / costBasis) * 100.0
                    } else grossPnl
                    // V5.9.1513 ‚Äî P0 FIX 1 (parity with CanonicalPublishHelper):
                    // classify on REALIZED NET SOL against a fee-aware epsilon, for
                    // BOTH paper and live. The prior paper path fell back to GROSS %
                    // with a ¬±1.0% band, so a +1.0% gross paper scratch (net-negative
                    // after the ~1.6% round-trip) was booked WIN ‚Äî inflating paper WR
                    // and poisoning learning. Win must clear the fee floor in SOL.
                    val classifyCost = costBasis ?: ts.position.costSol.takeIf { it > 0.0 } ?: tradeWithMint.sol
                    val feeCostSolCls = ((classifyCost ?: 0.0) * 0.016).coerceAtLeast(0.0)
                    val epsilonSolCls = maxOf(0.0002, feeCostSolCls)
                    // realized net SOL for this (partial) sell: prefer explicit netPnlSol,
                    // else derive from gross pnl% on the cost actually consumed.
                    val realizedNetSol = if (tradeWithMint.netPnlSol != 0.0) tradeWithMint.netPnlSol
                                         else (netPnlPct / 100.0) * (classifyCost ?: 0.0)
                    val pnl = netPnlPct
                    val resultEnum = when {
                        realizedNetSol >  epsilonSolCls -> com.lifecyclebot.engine.TradeResult.WIN
                        realizedNetSol < -epsilonSolCls -> com.lifecyclebot.engine.TradeResult.LOSS
                        else -> com.lifecyclebot.engine.TradeResult.BREAKEVEN
                    }
                    val executionEnum = if (tradeWithMint.sig.isNotBlank() || isPaperEnv)
                        com.lifecyclebot.engine.ExecutionResult.EXECUTED
                    else com.lifecyclebot.engine.ExecutionResult.UNKNOWN
                    // V5.9.1556b ‚Äî fix UNKNOWN at source of rich canonical creation.
                    // ModeRouter.classify(ts) can legitimately return UNKNOWN for generic
                    // V3/core meme positions; using it first corrupted learning/UI bins.
                    // The Trade has already been source-resolved above from the position
                    // flags / pos.tradingMode / STANDARD fallback, so make that the
                    // authority. ModeRouter is only a last-resort fallback.
                    val rawMode = when {
                        isMeaningfulLaneName(tradeWithMint.tradingMode) -> tradeWithMint.tradingMode
                        isMeaningfulLaneName(ts.position.tradingMode) -> ts.position.tradingMode
                        else -> try { ModeRouter.classify(ts).tradeType.name } catch (_: Throwable) { "STANDARD" }
                    }
                    val modeEnumRaw = com.lifecyclebot.engine.CanonicalOutcomeNormalizer.normalizeMode(rawMode)
                    val modeEnum = if (modeEnumRaw == com.lifecyclebot.engine.TradeMode.UNKNOWN &&
                        (tradeWithMint.sig.isNotBlank() || isPaperEnv) && tradeWithMint.price > 0.0
                    ) com.lifecyclebot.engine.TradeMode.STANDARD else modeEnumRaw
                    // V5.9.853 ‚Äî operator audit F4: source enum mistag.
                    // Executor.recordTrade default-mapped every non-flag position
                    // to TradeSource.V3, which clobbered MOONSHOT/MANIP/COPY/
                    // EXPRESS/CYCLIC attribution. Position.tradingMode carries
                    // the actual ExtendedMode tag set at open ("MOONSHOT",
                    // "MANIP", "COPY_TRADE", etc); consult it before falling
                    // back to V3. Keeps TradeSource bucket counters honest so
                    // BehaviorLearning + AdaptiveLearning can stratify accuracy
                    // by lane instead of dumping everything into the V3 bucket.
                    val sourceEnum = when {
                        ts.position.isShitCoinPosition -> com.lifecyclebot.engine.TradeSource.SHITCOIN
                        ts.position.isBlueChipPosition -> com.lifecyclebot.engine.TradeSource.BLUECHIP
                        ts.position.isTreasuryPosition -> com.lifecyclebot.engine.TradeSource.TREASURY
                        else -> when (ts.position.tradingMode.uppercase()) {
                            "MOONSHOT"                                       -> com.lifecyclebot.engine.TradeSource.MOONSHOT
                            "MANIP", "MANIPULATED"                           -> com.lifecyclebot.engine.TradeSource.MANIP
                            "COPY", "COPY_TRADE", "COPYTRADE"                -> com.lifecyclebot.engine.TradeSource.COPYTRADE
                            "EXPRESS", "PUMP_SNIPER", "EXPRESS_LAUNCH"       -> com.lifecyclebot.engine.TradeSource.EXPRESS
                            "CYCLIC", "CYCLIC_TRADE"                         -> com.lifecyclebot.engine.TradeSource.CYCLIC
                            "MARKETS", "CRYPTOALT", "CRYPTO_ALT"             -> com.lifecyclebot.engine.TradeSource.MARKETS
                            "MANUAL", "USER", "USER_MANUAL"                  -> com.lifecyclebot.engine.TradeSource.MANUAL
                            else                                             -> com.lifecyclebot.engine.TradeSource.V3
                        }
                    }
                    val assetClassEnum = when (sourceEnum) {
                        com.lifecyclebot.engine.TradeSource.BLUECHIP -> com.lifecyclebot.engine.AssetClass.BLUECHIP
                        else -> com.lifecyclebot.engine.AssetClass.MEME
                    }
                    val holdSec = if (ts.position.entryTime > 0) (System.currentTimeMillis() - ts.position.entryTime) / 1000 else null
                    val features = mapOf(
                        "entryScore" to (ts.entryScore.toDouble().takeIf { it.isFinite() } ?: 0.0),
                        "entryConfidence" to (ts.position.entryScore.takeIf { it.isFinite() } ?: 0.0),
                        "tradeSize" to tradeWithMint.sol,
                        "holdSec" to (holdSec?.toDouble() ?: 0.0),
                    )
                    // V5.9.785 ‚Äî operator audit Wave 5 producer sweep: build a
                    // feature-rich CandidateFeatures payload from TokenState +
                    // Trade + mode + source so BehaviorLearning / AdaptiveLearning
                    // / MetaCognitionAI can pattern-match on venue/route/safety/
                    // age/liq/mcap instead of training on legacy bridge stubs.
                    val envEnum = if (isPaperEnv) com.lifecyclebot.engine.TradeEnvironment.PAPER
                                  else com.lifecyclebot.engine.TradeEnvironment.LIVE
                    // V5.9.810 ‚Äî read the symbolic verdict that FDG recorded at
                    // candidate-evaluation time. consume() removes it so a re-entry
                    // on the same mint gets a fresh verdict next FDG.evaluate.
                    // Returns "" if the verdict expired (TTL 10 min) or this mint
                    // never went through FDG (e.g. copy-trade path).
                    val symVerdict = try {
                        com.lifecyclebot.engine.SymbolicVerdictRegistry.consume(ts.mint)
                    } catch (_: Throwable) { "" }
                    val (candFeatures, isIncomplete) = com.lifecyclebot.engine.CanonicalFeaturesBuilder
                        .fromTokenState(ts, trade, modeEnum, sourceEnum, envEnum, symVerdict)
                    val canonicalEntrySizeSol = ts.position.costSol.takeIf { it > 0.0 }
                    val canonicalRealizedPnlSol = tradeWithMint.netPnlSol.takeIf { it != 0.0 } ?: tradeWithMint.pnlSol
                    val rich = com.lifecyclebot.engine.CanonicalTradeOutcome(
                        tradeId = tradeId,
                        mint = ts.mint,
                        symbol = ts.symbol,
                        assetClass = assetClassEnum,
                        mode = modeEnum,
                        source = sourceEnum,
                        environment = envEnum,
                        entryTimeMs = ts.position.entryTime,
                        exitTimeMs = tradeWithMint.ts,
                        entryPrice = ts.position.entryPrice,
                        exitPrice = tradeWithMint.price,
                        entrySol = canonicalEntrySizeSol,
                        entrySizeSol = canonicalEntrySizeSol,
                        sizeBucket = com.lifecyclebot.engine.CanonicalSizeContext.bucket(canonicalEntrySizeSol),
                        solWeightedReturn = com.lifecyclebot.engine.CanonicalSizeContext.solWeightedReturn(canonicalEntrySizeSol, canonicalRealizedPnlSol, pnl),
                        exitSol = tradeWithMint.sol,
                        realizedPnlSol = canonicalRealizedPnlSol,
                        realizedPnlPct = pnl,  // V5.9.1509 ‚Äî net-of-fee (pnl is now netPnlPct)
                        maxGainPct = if (ts.position.entryPrice > 0 && ts.position.highestPrice > 0)
                            ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100.0 else null,
                        maxDrawdownPct = if (ts.position.entryPrice > 0 && ts.position.lowestPrice > 0)
                            ((ts.position.lowestPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100.0 else null,
                        holdSeconds = holdSec,
                        result = resultEnum,
                        executionResult = executionEnum,
                        closeReason = tradeWithMint.reason.ifBlank { null },
                        featuresAtEntry = features,
                        candidate = candFeatures,
                        featuresIncomplete = isIncomplete,
                        isPartial = tradeWithMint.side.equals("PARTIAL_SELL", ignoreCase = true),
                        partialIndex = if (tradeWithMint.side.equals("PARTIAL_SELL", true)) tradeWithMint.reason.filter { it.isDigit() }.toIntOrNull() ?: 1 else 0,
                        parentPositionId = com.lifecyclebot.engine.TradeOutcomeLedger.positionId(ts, trade),
                        costBasisSol = tradeWithMint.sol.takeIf { it > 0.0 },
                        proceedsSol = (tradeWithMint.sol + (tradeWithMint.netPnlSol.takeIf { it != 0.0 } ?: tradeWithMint.pnlSol)).coerceAtLeast(0.0),
                        feesSol = trade.feeSol,
                        isTrainable = tradeWithMint.price > 0.0 && tradeWithMint.sol > 0.0 && (tradeWithMint.sol + (tradeWithMint.netPnlSol.takeIf { it != 0.0 } ?: tradeWithMint.pnlSol)) >= -0.0000001,
                        invalidReason = if (tradeWithMint.price <= 0.0 || tradeWithMint.sol <= 0.0) "INVALID_ACCOUNTING" else null,
                        // V5.9.793 ‚Äî operator audit Item 5: flag BC-sim-only outcomes
                        // so the production WR aggregator can exclude them. A paper
                        // close priced exclusively against a pump.fun bonding-curve
                        // estimate is NOT a real-money signal ‚Äî it must not move
                        // the live WR needle.
                        bcSimOnly = try {
                            com.lifecyclebot.engine.LiquidityClassifier.isBcSimOnly(ts)
                        } catch (_: Throwable) { false },
                    )
                    com.lifecyclebot.engine.CanonicalOutcomeBus.markRichPublished(tradeId)
                    // V5.9.791 ‚Äî operator audit Item 1: Executor.recordTrade already
                    // arbitrated this terminal SELL at the top of the method;
                    // bypass-arbiter publish prevents the bus from re-checking
                    // the (now-locked) positionKey and falsely SUPPRESS-ing this
                    // legitimate first-write canonical event.
                    com.lifecyclebot.engine.CanonicalOutcomeBus.publishUnchecked(rich)
                }
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "Canonical rich publish error: ${e.message?.take(80)}")
            }
        } // end if SELL (CanonicalPublish)
    }

    // ‚îÄ‚îÄ top-up sizing ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ

    /**
     * Size a top-up (pyramid) add.
     * Each successive top-up is smaller than the one before:
     *   1st top-up: initialSize * multiplier          (e.g. 0.10 * 0.50 = 0.05)
     *   2nd top-up: initialSize * multiplier^2        (e.g. 0.10 * 0.25 = 0.025)
     *   3rd top-up: initialSize * multiplier^3        (e.g. 0.10 * 0.125 = 0.0125)
     *
     * This keeps total exposure bounded while still adding meaningful size
     * into the strongest moves.
     */
    fun topUpSizeSol(
        pos: Position,
        walletSol: Double,
        totalExposureSol: Double,
    ): Double {
        val c          = cfg()
        val topUpNum   = pos.topUpCount + 1  // which top-up this would be
        val initSize   = pos.initialCostSol.coerceAtLeast(c.smallBuySol)
        // V5.9.808 ‚Äî operator triage: GROWTH pyramiding, not decay.
        // Old formula: size = initSize * (multiplier ^ topUpNum) where
        // multiplier defaulted to 0.50 ‚Äî meaning every top-up SHRANK to
        // 1/2 the previous one (0.04 ‚Üí 0.02 ‚Üí 0.01 ‚Üí 0.005). That's the
        // exact opposite of the operator's mandate: 'increase position
        // size as it runs'. New formula: top-up size grows with the
        // position's PEAK gain so far. A flat-out runner at +50% gets
        // a 2√ó top-up; a wobbler at +10% gets a 1.2√ó top-up.
        //   peakGain  0%  ‚Üí 1.0x initial
        //   peakGain 25%  ‚Üí 1.5x initial
        //   peakGain 50%+ ‚Üí 2.0x initial (cap)
        // Still bounded above by topUpMaxTotalSol AND walletSol*0.15 so
        // we never go nuclear. Subsequent top-ups stay at the same
        // gain-scaled level rather than decaying.
        val peakGainPct = pos.peakGainPct.coerceAtLeast(0.0)
        val diamondHands6091 = pos.tradingMode.equals("DIAMOND_HANDS", true) || pos.isLongHold
        val growthBonus = (peakGainPct / 50.0).coerceIn(0.0, if (diamondHands6091) 2.0 else 1.0)
        val growthMultiplier = (1.0 + growthBonus).coerceAtMost(if (diamondHands6091) 3.0 else 2.0)
        var size       = initSize * growthMultiplier

        // Top-up cap from config. V5.0.6091: DIAMOND_HANDS/long-hold runners get
        // deeper pyramiding room while still obeying portfolio exposure and wallet caps.
        val currentTotal  = pos.costSol
        val maxTopUpTotal6091 = if (diamondHands6091) c.topUpMaxTotalSol * 3.0 else c.topUpMaxTotalSol
        val remainingRoom = maxTopUpTotal6091 - currentTotal
        size = size.coerceAtMost(remainingRoom)

        // ‚îÄ‚îÄ V5.9.894 ‚Äî totalExposureSol finally consumed in top-up sizing ‚îÄ‚îÄ
        // Audit (memory #145) found topUpSizeSol(pos, walletSol, totalExposureSol)
        // accepts totalExposureSol but the body NEVER reads it. Old comment
        // here literally said "Wallet room from SmartSizer exposure ‚Äî
        // unlimited from config side" ‚Äî meaning the function relied on
        // upstream SmartSizer to handle exposure caps. But top-ups go
        // through THIS function on a DIFFERENT path than fresh buys, so
        // SmartSizer's 70% legacy-exposure cap (SmartSizer.kt L616) never
        // applied to additions.
        //
        // Net effect: a position already at 65% wallet exposure could
        // receive a top-up of up to 15% of walletSol, pushing total
        // exposure to 80%+ ‚Äî past the SmartSizer doctrine ceiling.
        //
        // Wire the same 70% portfolio-exposure ceiling here. Hard cap,
        // not soft-shape: the doctrine is explicit (SmartSizer enforces
        // it for fresh buys), top-ups must respect the same envelope.
        // Hard returns 0.0 only when the new exposure WOULD cross 70%;
        // otherwise sizes the top-up to fit within the remaining headroom.
        val exposureCeilingSol = walletSol * 0.70
        val exposureRoomSol = (exposureCeilingSol - totalExposureSol).coerceAtLeast(0.0)
        if (exposureRoomSol <= 0.0) {
            // Already at/past the 70% cap ‚Äî no top-up at all
            return 0.0
        }
        size = size.coerceAtMost(exposureRoomSol)

        // Minimum viable trade. V5.0.6091: diamond/long-hold runners may add more
        // per step, but still never exceed the 70% portfolio exposure ceiling above.
        val perAddWalletPct6091 = if (diamondHands6091) 0.25 else 0.15
        return size.coerceAtMost(walletSol * perAddWalletPct6091)
               .coerceAtLeast(0.0)
    }

    /** V5.0.6091 ‚Äî autonomous add-to-winner trigger.
     * AGI/SSI/LLM/sentience should be able to add to an existing winner when fresh
     * conviction appears (whale/velocity buy-in, bull fan, holder growth, slow-build
     * diamond runner), instead of waiting for a stale meta.topUpReady flag. This never
     * averages down and never bypasses rug/security/portfolio exposure checks; it only
     * routes healthy open-position conviction into the existing doTopUp() executor.
     */
    private fun autonomousTopUpSignal6091(
        ts: TokenState,
        entryScore: Double,
        exitScore: Double,
        emafanAlignment: String,
        volScore: Double,
        exhaust: Boolean,
    ): Boolean {
        return try {
            val pos = ts.position
            if (!pos.isOpen || !cfg().autoTrade) return false
            if (exhaust || exitScore >= 65.0) return false
            val currentPrice = getActualPrice(ts)
            val gainPct = pct(pos.entryPrice, currentPrice)
            if (gainPct < 2.0) return false // add to winners only ‚Äî never average down
            val whaleBid = ts.meta.whaleSummary.isNotBlank() || ts.meta.velocityScore >= 70.0
            val bullStructure = emafanAlignment in listOf("BULL_FAN", "BULL_FLAT") && volScore >= 45.0
            val holderConviction = ts.holderGrowthRate >= 10.0 && (ts.peakHolderCount >= 50 || ts.holderDataResolved)
            val diamondConviction = pos.tradingMode.equals("DIAMOND_HANDS", true) || pos.isLongHold ||
                (gainPct >= 25.0 && bullStructure && holderConviction && ts.lastLiquidityUsd >= 8_000.0)
            val agiConviction = entryScore >= 65.0 && bullStructure && ts.lastLiquidityUsd >= 4_000.0
            val runnerConviction = pos.peakGainPct >= 20.0 || gainPct >= 12.0
            val dangerClear = ts.lastLiquidityUsd >= 2_500.0 && !ts.meta.breakdown && emafanAlignment != "BEAR_FAN"
            dangerClear && (
                (whaleBid && gainPct >= 3.0) ||
                (agiConviction && runnerConviction) ||
                diamondConviction
            )
        } catch (_: Throwable) { false }
    }

    /**
     * Decides whether to top up an open position.
     *
     * Rules (all must pass):
     *   1. Top-up enabled in config
     *   2. Position is open and profitable
     *   3. Gain has crossed the next top-up threshold
     *   4. Not at max top-up count
     *   5. Cooldown since last top-up has passed
     *   6. EMA fan is bullish (if required by config)
     *   7. Volume is not exhausting (don't add into a dying move)
     *   8. No spike top forming (never add at the top)
     *   9. Sufficient room left in position/wallet caps
     *   10. Exit score is LOW (momentum still healthy)
     */
    fun shouldTopUp(
        ts: TokenState,
        entryScore: Double,
        exitScore: Double,
        emafanAlignment: String,
        volScore: Double,
        exhaust: Boolean,
    ): Boolean {
        val c   = cfg()
        val pos = ts.position
        val autonomyTopUp6091 = autonomousTopUpSignal6091(ts, entryScore, exitScore, emafanAlignment, volScore, exhaust)
        if (!c.topUpEnabled && !autonomyTopUp6091) return false
        if (!pos.isOpen)       return false
        if (!c.autoTrade)      return false

        // CRITICAL FIX: Use actual price, not market cap
        val currentPrice = getActualPrice(ts)
        val gainPct   = pct(pos.entryPrice, currentPrice)
        val heldMins  = (System.currentTimeMillis() - pos.entryTime) / 60_000.0

        // Must be profitable ‚Äî never average down
        if (gainPct <= 0) return false

        // CHANGE 6: High-conviction and long-hold positions pyramid deeper
        // For MOONSHOTS (100x+), allow unlimited top-ups as long as position is healthy
        val nextTopUp = pos.topUpCount + 1
        val gainPctNow = pct(pos.entryPrice, currentPrice)
        val effectiveMax = when {
            gainPctNow >= 10000.0 -> 10    // 100x+ moonshot: up to 10 top-ups
            gainPctNow >= 1000.0  -> 7     // 10x+ strong runner: up to 7 top-ups
            pos.tradingMode.equals("DIAMOND_HANDS", true) -> 12
            pos.isLongHold || pos.entryScore >= 75.0 -> 7
            autonomyTopUp6091 -> 6
            else -> c.topUpMaxCount
        }
        if (nextTopUp > effectiveMax) return false

        // CHANGE 3: High-conviction entries pyramid earlier
        // Entry score ‚â•75 = pre-grad/whale/BULL_FAN confluence ‚Äî fire at 12% not 25%
        // V5.9.808 ‚Äî operator triage: pyramid EARLIER on every winner, not
        // just A-grade entries. Was 25% / +30% per rung ‚Äî too slow for
        // meme cadence; most runs round-trip before hitting 25%. New cap:
        // first top-up fires at 8% (high-conviction) or 10% (anyone),
        // and each subsequent rung needs +12% more (was +30%). Forces
        // the new bigger-on-winners behaviour onto existing operator
        // configs that still have the old 25/30 saved in prefs.
        val earlyFirst = (pos.entryScore >= 75.0 || autonomyTopUp6091) && pos.topUpCount == 0
        val baseMin    = when {
            autonomyTopUp6091 && pos.topUpCount == 0 -> 3.0
            earlyFirst -> 8.0
            else -> c.topUpMinGainPct.coerceAtMost(10.0)
        }
        val stepGain   = if (autonomyTopUp6091) c.topUpGainStepPct.coerceAtMost(8.0) else c.topUpGainStepPct.coerceAtMost(12.0)
        val requiredGain = baseMin + (pos.topUpCount * stepGain)
        if (gainPct < requiredGain) return false

        // Cooldown since last top-up
        if (pos.topUpCount > 0) {
            val minsSinceTopUp = (System.currentTimeMillis() - pos.lastTopUpTime) / 60_000.0
            val cooldownMins6091 = if (autonomyTopUp6091) c.topUpMinCooldownMins.coerceAtMost(2.0) else c.topUpMinCooldownMins
            if (minsSinceTopUp < cooldownMins6091) return false
        }

        // EMA fan requirement
        if (c.topUpRequireEmaFan && emafanAlignment != "BULL_FAN" && !autonomyTopUp6091) return false

        // Don't add into exhaustion
        if (exhaust) return false

        // Don't add if exit score is very high (momentum dying)
        // Raised threshold from 35 to 50 to allow more top-ups on runners
        if (exitScore >= (if (autonomyTopUp6091) 65.0 else 50.0)) return false

        // Don't add if entry score is very low (market structure weak)
        if (entryScore < 15.0) return false  // was 20.0 - lowered for more aggressive pyramiding

        // Volume must be healthy (but not required to be super strong)
        if (volScore < (if (autonomyTopUp6091) 20.0 else 25.0)) return false  // was 30.0 - lowered

        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // TREASURY-AWARE MAX POSITION SIZE
        // 
        // Higher treasury = can afford larger positions on confirmed runners
        // ScalingMode already handles this, but we add extra room for moonshots
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        val effectiveMaxSol = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            // Scale max position with treasury tier
            when (tier) {
                ScalingMode.Tier.INSTITUTIONAL -> c.topUpMaxTotalSol * 3.0  // 3x max position
                ScalingMode.Tier.SCALED        -> c.topUpMaxTotalSol * 2.0  // 2x max position
                ScalingMode.Tier.GROWTH        -> c.topUpMaxTotalSol * 1.5  // 1.5x max position
                ScalingMode.Tier.STANDARD      -> c.topUpMaxTotalSol * 1.2  // 1.2x max position
                ScalingMode.Tier.MICRO         -> c.topUpMaxTotalSol        // Standard max
            }
        } catch (_: Exception) { c.topUpMaxTotalSol }
        
        // Must have room left (using treasury-adjusted max)
        val remainingRoom = effectiveMaxSol - pos.costSol
        if (remainingRoom < 0.005) return false

        return true
    }

    // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
    // GRADUATED POSITION BUILDING
    // Split entry into phases: 40% initial, 30% confirm, 30% full
    // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê

    fun graduatedInitialSize(fullSize: Double, quality: String): Double {
        return fullSize * graduatedInitialPct(quality)
    }
    
    fun graduatedInitialPct(quality: String): Double {
        return when (quality) {
            "A+" -> 0.50
            "B"  -> 0.40
            else -> 0.35
        }
    }

    fun shouldGraduatedAdd(pos: Position, currentPrice: Double, volScore: Double): Pair<Double, Int>? {
        if (pos.isFullyBuilt || pos.targetBuildSol <= 0) return null
        if (pos.buildPhase !in listOf(1, 2)) return null
        
        val gainPct = pct(pos.entryPrice, currentPrice)
        val remaining = pos.targetBuildSol - pos.costSol
        val timeSince = System.currentTimeMillis() - pos.entryTime
        
        // Phase 2: 3%+ gain, 30s delay
        if (pos.buildPhase == 1 && gainPct >= 3.0 && timeSince >= 30_000 && volScore >= 35) {
            val add = remaining * 0.50
            if (add >= 0.005) return Pair(add, 2)
        }
        
        // Phase 3: 8%+ gain
        if (pos.buildPhase == 2 && gainPct >= 8.0) {
            val add = remaining.coerceAtLeast(0.005)
            if (add >= 0.005) return Pair(add, 3)
        }
        
        return null
    }

    fun doGraduatedAdd(ts: TokenState, addSol: Double, newPhase: Int) {
        val price = getActualPrice(ts)  // CRITICAL FIX: Use actual price, not market cap
        if (price <= 0 || !ts.position.isOpen) return
        
        val addTokens = addSol / maxOf(price, 1e-12)
        val newQty = ts.position.qtyToken + addTokens
        val newCost = ts.position.costSol + addSol
        
        ts.position = ts.position.copy(
            qtyToken = newQty,
            costSol = newCost,
            buildPhase = newPhase
        )
        
        val trade = Trade("BUY", "paper", addSol, price, System.currentTimeMillis(), score = 0.0)
        recordTrade(ts, trade)
        security.recordTrade(trade)
        onPaperBalanceChange?.invoke(-addSol)
        
        val emoji = if (newPhase == 3) "üéØ" else "üìà"
        onLog("$emoji BUILD P$newPhase | +${addSol.fmt(3)} SOL", ts.mint)
    }

    // ‚îÄ‚îÄ trailing stop ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
    // V5: SMART RUNNER CAPTURE - Dynamic trailing based on trend health
    
    /**
     * Smart Trailing Floor - Dynamically adjusts based on:
     * 1. Gain percentage (base adjustment)
     * 2. EMA fan health (widening fan = looser trail)
     * 3. Volume trend (increasing = looser trail)
     * 4. Buy pressure (strong = looser trail)
     * 
     * The goal is to ride runners to their full potential while still
     * protecting gains when momentum starts to fade.
     */
    fun trailingFloor(pos: Position, current: Double,
                       modeConf: AutoModeEngine.ModeConfig? = null,
                       // V5: Additional signals for smart trailing
                       emaFanAlignment: String = "FLAT",
                       emaFanWidening: Boolean = false,
                       volScore: Double = 50.0,
                       pressScore: Double = 50.0,
                       exhaust: Boolean = false): Double {
        val base    = modeConf?.trailingStopPct ?: cfg().trailingStopBasePct
        val gainPct = pct(pos.entryPrice, current)
        
        // Trail adjustment after partial sells
        // After taking profits, we can be slightly looser (not tighter!) since we've secured gains
        val partialFactor = when {
            pos.partialSoldPct >= 50.0 -> 0.90
            pos.partialSoldPct >= 25.0 -> 0.95
            else                       -> 1.0
        }
        
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        // HOUSE MONEY MULTIPLIER ‚Äî After capital recovered, we can be a TOUCH
        // looser, but never enough to give back 25-35pp from a peak. The old
        // 1.5-1.8√ó widening plus the old growing baseTrail curve was turning
        // peak +136% into lock +101% (26% of peak value bled away).
        // V5.9.145 ‚Äî capped to 1.15√ó max. Profit-locked gets the most
        // breathing room, but not at the expense of giving back a quarter
        // of peak value.
        // ‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê‚ïê
        val houseMoneyMultiplier = when {
            pos.profitLocked     -> 1.15
            pos.isHouseMoney     -> 1.10
            pos.capitalRecovered -> 1.05
            else                 -> 1.0
        }
        
        var healthMultiplier = 1.0
        
        when {
            emaFanAlignment == "BULL_FAN" && emaFanWidening -> {
                healthMultiplier += 0.35
            }
            emaFanAlignment == "BULL_FAN" -> {
                healthMultiplier += 0.20
            }
            emaFanAlignment == "BULL_FLAT" -> {
                healthMultiplier += 0.10
            }
            emaFanAlignment == "BEAR_FLAT" -> {
                healthMultiplier -= 0.15
            }
            emaFanAlignment == "BEAR_FAN" -> {
                healthMultiplier -= 0.30
            }
        }
        
        when {
            volScore >= 70 -> healthMultiplier += 0.15
            volScore >= 55 -> healthMultiplier += 0.08
            volScore < 35  -> healthMultiplier -= 0.12
            volScore < 25  -> healthMultiplier -= 0.20
        }
        
        when {
            pressScore >= 65 -> healthMultiplier += 0.12
            pressScore >= 55 -> healthMultiplier += 0.05
            pressScore < 40  -> healthMultiplier -= 0.15
            pressScore < 30  -> healthMultiplier -= 0.25
        }
        
        if (exhaust) {
            healthMultiplier -= 0.30
        }
        
        // V5.9.145 ‚Äî tightened ceiling. Was 1.6x which could re-inflate a
        // √ó0.35 peak-locked trail back up to √ó0.56 (+60% wider) on a strong
        // BULL_FAN. We want the tight lock to hold; bull-regime conviction
        // can still buy us modest extra room but not erase the trail
        // inversion we just installed.
        healthMultiplier = healthMultiplier.coerceIn(0.70, 1.25)
        
        val learnedTrailInfluence = if (ExitIntelligence.getTotalExits() >= 20) {
            val learnedStop = ExitIntelligence.getLearnedTrailingStopDistance()
            (learnedStop / 5.0).coerceIn(0.8, 2.0)
        } else 1.0
        
        // V5.9.145 ‚Äî INVERTED trail curve.
        // Previous curve WIDENED as gain grew (base√ó1.5 at 100%, √ó3 at 1000%),
        // which stacked with houseMoneyMultiplier 1.5-1.8√ó meant the peak
        // +136% MOG position had an effective trail of ~35pp ‚Äî giving back
        // 26% of peak value before exit triggered.
        //
        // New curve TIGHTENS as gain grows ‚Äî the bigger the peak, the less
        // of it we're willing to bleed:
        //
        //   peak   <  15%  ‚Üí base √ó 1.00   (normal noise room for early moves)
        //   peak  15-30%   ‚Üí base √ó 0.85
        //   peak  30-60%   ‚Üí base √ó 0.65
        //   peak  60-120%  ‚Üí base √ó 0.50   (moon runner: lock ~90% of peak)
        //   peak 120-250%  ‚Üí base √ó 0.35   (big runner:  lock ~93% of peak)
        //   peak 250-500%  ‚Üí base √ó 0.28
        //   peak 500-2000% ‚Üí base √ó 0.25
        //   peak > 2000%   ‚Üí base √ó 0.22   (legendary: lock ~96% of peak)
        //
        // Example on alt mode (base=15): a 136% peak now trails at 15√ó0.35
        // = 5.25pp instead of the old ~23pp+houseMult=35pp. We lock at
        // 130.75% instead of 101%. That's 30pp of profit saved on a single
        // position.
        val baseTrail = when {
            gainPct >= 2000  -> base * 0.22
            gainPct >= 500   -> base * 0.25
            gainPct >= 250   -> base * 0.28
            gainPct >= 120   -> base * 0.35
            gainPct >= 60    -> base * 0.50
            gainPct >= 30    -> base * 0.65
            gainPct >= 15    -> base * 0.85
            else             -> base * 1.00
        }
        
        var smartTrail = baseTrail * healthMultiplier * partialFactor * learnedTrailInfluence * houseMoneyMultiplier
        
        val regimeTrailMult = try {
            val regime = MarketRegimeAI.getCurrentRegime()
            val confidence = MarketRegimeAI.getRegimeConfidence()
            
            if (confidence >= 40.0) {
                when (regime) {
                    MarketRegimeAI.Regime.STRONG_BULL -> 1.2
                    MarketRegimeAI.Regime.BULL -> 1.1
                    MarketRegimeAI.Regime.NEUTRAL -> 1.0
                    MarketRegimeAI.Regime.CRAB -> 0.95
                    MarketRegimeAI.Regime.BEAR -> 0.85
                    MarketRegimeAI.Regime.STRONG_BEAR -> 0.75
                    MarketRegimeAI.Regime.HIGH_VOLATILITY -> 0.9
                }
            } else 1.0
        } catch (_: Exception) { 1.0 }
        
        smartTrail *= regimeTrailMult
        
        if (gainPct >= 100.0 && (healthMultiplier != 1.0 || learnedTrailInfluence != 1.0 || regimeTrailMult != 1.0)) {
            val direction = if (healthMultiplier > 1.0) "LOOSE" else "TIGHT"
            val regimeLabel = try { MarketRegimeAI.getCurrentRegime().label } catch (_: Exception) { "?" }
            ErrorLogger.debug("SmartTrail", "üéØ Runner ${gainPct.toInt()}%: " +
                "health=${healthMultiplier.fmt(2)} ($direction) | " +
                "fan=$emaFanAlignment wide=$emaFanWidening | " +
                "vol=${volScore.toInt()} press=${pressScore.toInt()} | " +
                "learnedMult=${learnedTrailInfluence.fmt(2)} | " +
                "regime=$regimeLabel(${regimeTrailMult.fmt(2)}) | " +
                "trail=${smartTrail.fmt(2)}%")
        }
        
        return pos.highestPrice * (1.0 - smartTrail / 100.0)
    }
    
    fun trailingFloorBasic(pos: Position, current: Double,
                            modeConf: AutoModeEngine.ModeConfig? = null): Double {
        return trailingFloor(pos, current, modeConf)
    }

    // ‚îÄ‚îÄ profit lock system ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ
    
    private fun calculateProfitLockThresholds(ts: TokenState): Pair<Double, Double> {
        val pos = ts.position
        
        var capitalRecoveryMultiple = 2.0
        var profitLockMultiple = 5.0
        
        val treasuryTierAdjustment = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                ScalingMode.Tier.INSTITUTIONAL -> 1.40
                ScalingMode.Tier.SCALED        -> 1.25
                ScalingMode.Tier.GROWTH        -> 1.15
                ScalingMode.Tier.STANDARD      -> 1.05
                ScalingMode.Tier.MICRO         -> 1.00
            }
        } catch (_: Exception) { 1.0 }
        
        val liqUsd = ts.lastLiquidityUsd
        val liqAdjustment = when {
            liqUsd < 5_000   -> 0.70
            liqUsd < 10_000  -> 0.80
            liqUsd < 25_000  -> 0.90
            liqUsd < 50_000  -> 1.00
            liqUsd < 100_000 -> 1.10
            else             -> 1.20
        }
        
        val mcap = ts.lastMcap
        val mcapAdjustment = when {
            mcap < 50_000    -> 0.75
            mcap < 100_000   -> 0.85
            mcap < 250_000   -> 0.95
            mcap < 500_000   -> 1.00
            mcap < 1_000_000 -> 1.10
            else             -> 1.20
        }
        
        val volatility = ts.meta.rangePct
        val volAdjustment = when {
            volatility > 50  -> 0.70
            volatility > 30  -> 0.80
            volatility > 20  -> 0.90
            volatility > 10  -> 1.00
            else             -> 1.10
        }
        
        val entryPhase = pos.entryPhase.lowercase()
        val phaseAdjustment = when {
            entryPhase.contains("early") || entryPhase.contains("accumulation") -> 0.80
            entryPhase.contains("pre_pump") -> 0.85
            entryPhase.contains("markup") || entryPhase.contains("breakout") -> 1.00
            entryPhase.contains("momentum") -> 1.05
            entryPhase.contains("distribution") -> 0.70
            else -> 0.90
        }
        
        val qualityAdjustment = when {
            pos.entryScore >= 80 -> 1.15
            pos.entryScore >= 70 -> 1.05
            pos.entryScore >= 60 -> 1.00
            pos.entryScore >= 50 -> 0.90
            else -> 0.80
        }
        
        val tokenTier = ScalingMode.tierForToken(ts.lastLiquidityUsd, ts.lastMcap)
        val tokenTierAdjustment = when (tokenTier) {
            ScalingMode.Tier.INSTITUTIONAL -> 1.30
            ScalingMode.Tier.SCALED        -> 1.20
            ScalingMode.Tier.GROWTH        -> 1.10
            ScalingMode.Tier.STANDARD      -> 1.00
            ScalingMode.Tier.MICRO         -> 0.85
        }
        
        val holdTimeMs = System.currentTimeMillis() - pos.entryTime
        val holdTimeMinutes = holdTimeMs / 60_000.0
        
        val actualPrice = getActualPrice(ts)
        // V5.0.7057 ¬ß2 ‚Äî the else branch is `tokens x USD-per-token / SOL`,
        // a USD numerator over a SOL denominator, inflated by the whole SOL
        // price (~113x). It is reached by any position with entryPrice <= 0,
        // INCLUDING paper ones ‚Äî and a paper position can absolutely have no
        // entry price (PAPER_BUY_EFFECTIVE_PRICE_SENTINEL_REJECTED_6911,
        // OPEN_POSITION_ZERO_ENTRY_QTY_6627, and the legacy replay rows that
        // open at entryPriceUsd=0.0). Convert the numerator to SOL first.
        val gainMultiple = if (pos.costSol > 0) {
            if (pos.isPaperPosition && pos.entryPrice > 0.0) {
                (actualPrice / pos.entryPrice)
            } else {
                gainMultipleFromNotional7057(pos.qtyToken, actualPrice, pos.costSol)
            }
        } else 1.0
        val currentValue = pos.costSol * gainMultiple
        val gainPctPerMinute = if (holdTimeMinutes > 0) {
            ((gainMultiple - 1.0) * 100.0) / holdTimeMinutes
        } else {
            100.0
        }
        
        val timeAdjustment = when {
            holdTimeMinutes < 0.5 && gainMultiple >= 1.5 -> 0.50
            holdTimeMinutes < 1.0 && gainMultiple >= 2.0 -> 0.55
            holdTimeMinutes < 2.0 && gainMultiple >= 2.0 -> 0.65
            gainPctPerMinute > 50  -> 0.60
            gainPctPerMinute > 25  -> 0.70
            gainPctPerMinute > 10  -> 0.85
            holdTimeMinutes < 5    -> 0.90
            holdTimeMinutes < 10   -> 1.00
            holdTimeMinutes < 30   -> 1.10
            holdTimeMinutes < 60   -> 1.20
            holdTimeMinutes < 120  -> 1.30
            else                   -> 1.40
        }
        
        val product = liqAdjustment * mcapAdjustment * volAdjustment * phaseAdjustment * 
            qualityAdjustment * tokenTierAdjustment * treasuryTierAdjustment * timeAdjustment
        val combinedAdjustment = product.pow(1.0 / 8.0).coerceIn(0.5, 1.8)
        
        capitalRecoveryMultiple *= combinedAdjustment
        profitLockMultiple *= combinedAdjustment
        
        // V5.0.6725 ¬ßSMART_EXIT_TOOLS_WIRED ‚Äî profit-lock previously
        // consulted liq/mcap/volatility/phase/quality/tier/hold-time but
        // was BLIND to the live vol-delta / holder-growth / whale-flow /
        // buy-pressure the operator specifically flagged as required.
        // A healthy runner (rising vol, growing holders, whale accum)
        // should widen the profit-lock threshold so we do not clip a
        // real parabolic move at 5x; a dying token should compress it
        // so we bank before the collapse. Multiplier is bounded (0.6..1.8)
        // so a single noisy tick cannot amputate a live runner or
        // over-extend a rug.
        val metricsSnap6725 = try {
            com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.snapshot(ts)
        } catch (_: Throwable) { null }
        if (metricsSnap6725 != null) {
            val metricMult6725: Double = when (metricsSnap6725.healthTier) {
                com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier.HEALTHY_RUNNER -> 1.60
                com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier.HEALTHY_STABLE -> 1.20
                com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier.NEUTRAL -> 1.00
                com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier.WEAKENING -> 0.85
                com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier.DYING -> 0.70
                com.lifecyclebot.engine.truth.CanonicalTokenMetricsSnapshot6725.HealthTier.RUG_LIKE -> 0.60
            }.coerceIn(0.60, 1.80)
            capitalRecoveryMultiple *= metricMult6725
            profitLockMultiple *= metricMult6725
            if (metricMult6725 != 1.0) {
                try {
                    PipelineHealthCollector.labelInc(
                        "PROFIT_LOCK_METRIC_ADJUST_6725_${metricsSnap6725.healthTier.name}"
                    )
                } catch (_: Throwable) {}
            }
        }
        
        val learnedRungs = try { WrRecoveryPartial.learnedExitRungs(pos.tradingMode.ifBlank { "STANDARD" }) } catch (_: Throwable) { Triple(50.0, 1000.0, 10000.0) }
        val learnedCapitalRecovery = 1.0 + (learnedRungs.second / 100.0)
        val learnedProfitLock = 1.0 + (learnedRungs.third / 100.0)
        // V5.0.3963 ‚Äî profit exits follow learned expectancy bands. Never let
        // capital recovery at 1.3x or profit-lock at 2.5x amputate a live runner.
        capitalRecoveryMultiple = maxOf(capitalRecoveryMultiple, learnedCapitalRecovery).coerceIn(1.5, 50.0)
        profitLockMultiple = maxOf(profitLockMultiple, learnedProfitLock).coerceIn(3.0, 500.0)
        
        return Pair(capitalRecoveryMultiple, profitLockMultiple)
    }
    
    fun checkProfitLock(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c = cfg()
        normalizePositionScaleIfNeeded(ts)
        val prePricePos = ts.position
        if (!prePricePos.isOpen) return false
        
        val actualPrice = getActualPrice(ts)
        // getActualPrice(ts) may apply the existing PRICE_BASIS_REBASE script/path
        // and mutate ts.position. Re-read it here; otherwise this function uses
        // the stale pre-rebase Position and pos.copy(...) writes the old basis
        // back over the corrected entryPrice/source fields.
        val pos = ts.position
        if (!pos.isOpen) return false
        // V5.9.1128 ‚Äî use the existing price-basis resolver correctly for PAPER.
        // getActualPrice(ts) already links the stored mint to current price/mcap/pool
        // data and rebases paper entries when the source basis changes. The bug was
        // below this line: profit-lock used qtyToken * actualPrice, so any simulated
        // qty/basis drift produced fake 240x/75,000x rows. Paper has no on-chain
        // token balance; its value is cost basis √ó resolved price return. LIVE keeps
        // qtyToken math because chain token quantity is real ground truth.
        // V5.0.7057 ¬ß2 ‚Äî see gainMultipleFromNotional7057. `qtyToken *
        // actualPrice` is USD; dividing it by a SOL cost basis inflates the
        // multiple by the SOL price. Reached whenever entryPrice <= 0, which
        // paper positions do hit.
        val rawGainMultiple = if (pos.isPaperPosition && pos.entryPrice > 0.0) {
            (actualPrice / pos.entryPrice)
        } else {
            gainMultipleFromNotional7057(pos.qtyToken, actualPrice, pos.costSol)
        }
        // V5.9.1510 ‚Äî PHANTOM-MULTIPLE GUARD (operator export: USD1 bought @ $64.978,
        // "sold" @ $64.972 ‚Äî price FELL ‚Äî yet booked +254513% / capital_recovery_2546.1x,
        // flagged INVALID_EXPORT). Root cause: high-unit-price tokens (USD1 ‚âà $64.97)
        // get their PAPER entryPrice rebased toward a near-zero mcap-pivot basis, so
        // actualPrice/entryPrice explodes to thousands even when the real price barely
        // moved. A genuine recovery/profit-lock can't be triggered by a basis artifact.
        // Cross-check the multiple against the candle-history price move; if the position
        // multiple disagrees with reality by a large factor, the entry basis is corrupt ‚Äî
        // fall back to the honest price-move multiple and never let it exceed the 1000x
        // sanity ceiling already used on the journal path.
        val histRef = ts.history.lastOrNull { it.priceUsd > 0 && it.priceUsd.isFinite() }?.priceUsd
        val priceMoveMultiple = if (pos.entryPrice > 0.0 && actualPrice > 0.0) actualPrice / pos.entryPrice else rawGainMultiple
        val gainMultiple = when {
            !rawGainMultiple.isFinite() || rawGainMultiple <= 0.0 -> 1.0
            // If the position-derived multiple is wildly larger than the actual price
            // move (basis corruption), trust the price move, not the corrupted qty/basis.
            priceMoveMultiple.isFinite() && priceMoveMultiple > 0.0 &&
                rawGainMultiple > priceMoveMultiple * 5.0 && rawGainMultiple > 5.0 -> {
                try { com.lifecyclebot.engine.ErrorLogger.warn("Executor",
                    "üö´ PHANTOM_MULTIPLE_GUARD ${ts.symbol}: raw=${rawGainMultiple} >> priceMove=${priceMoveMultiple} " +
                    "(entry=${pos.entryPrice} live=${actualPrice}) ‚Äî using price-move, basis likely corrupt") } catch (_: Throwable) {}
                try {
                    PipelineHealthCollector.labelInc("PHANTOM_QTY_REPAIR_REQUESTED_6522")
                    ForensicLogger.lifecycle("PHANTOM_QTY_REPAIR_REQUESTED_6522", "mint=${ts.mint.take(10)} action=retain_canonical_raw_no_price_derived_qty")
                } catch (_: Throwable) {}
                priceMoveMultiple.coerceAtMost(100.0)
            }
            else -> rawGainMultiple.coerceAtMost(100.0)
        }
        // V5.0.7049 ¬ßTHE_SELL_PATH_BANKED_A_PRICE_THE_PNL_PATH_HAD_REFUSED.
        //
        // One mint, one price, one tick, two opposite verdicts (5.0.7047):
        //
        //   OPEN_PNL_BASIS_REJECTED  reason=PRICE_BASIS_UNTRUSTED_EXTREME_RATIO
        //     context=BotService.rapidStop/RENDER/rndrizKT
        //     entry=0.002848705866  current=0.8075859836903634  ratio=283.49
        //
        //   PARTIAL_SELL rndriz sol=18.954276 pnl=+18.886642 cost=0.0673
        //     reason=ultra_runner_bank_100.0x
        //
        // OpenPnlSanity refused that ratio as a corrupt basis. Profit-lock took
        // the same number and banked 18.89 SOL on it. Note the "100.0x" in the
        // reason is not a measurement ‚Äî it is the coerceAtMost(100.0) ceiling
        // twelve lines up, so a 283x basis artifact and a genuine 100x runner
        // print the identical string.
        //
        // AND THE GUARD ABOVE CANNOT CATCH IT. PHANTOM_MULTIPLE_GUARD compares
        // rawGainMultiple against priceMoveMultiple, but on the PAPER branch
        // both are `actualPrice / pos.entryPrice` ‚Äî the same expression. Its
        // test is `raw > priceMove * 5.0`, i.e. `x > 5x`, which is false for
        // every positive x. It has never fired for a paper position and cannot.
        // It works only on the LIVE branch, where raw uses qtyToken.
        //
        // So consult the authority that already has an opinion. This does NOT
        // throttle runners, cap a multiple, or narrow a trigger: when the mark
        // is trusted every threshold behaves exactly as before. It refuses to
        // realise a gain from a price this same app has declared false, which
        // is the difference between capturing a runner and inventing one.
        val pricingTruth7049 = try {
            OpenPnlSanity.pricingTruth(ts, "Executor.profitLock/${ts.symbol}/${ts.mint.take(8)}", emit = false)
        } catch (_: Throwable) { null }
        if (pricingTruth7049 != null && !pricingTruth7049.trusted && gainMultiple > 1.0) {
            try {
                PipelineHealthCollector.labelInc("PROFIT_LOCK_REFUSED_UNTRUSTED_BASIS_7049")
                ForensicLogger.lifecycle(
                    "PROFIT_LOCK_REFUSED_UNTRUSTED_BASIS_7049",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} entry=${pos.entryPrice} mark=$actualPrice " +
                        "gainMultiple=${"%.2f".format(gainMultiple)} costSol=${"%.4f".format(pos.costSol)} " +
                        "reason=${pricingTruth7049.reason} src=${ts.lastPriceSource} " +
                        "action=hold_position_no_bank_on_refused_mark",
                )
            } catch (_: Throwable) {}
            // Hold. The position is untouched and every exit that does not
            // depend on this mark ‚Äî stop loss, catastrophe, time ‚Äî still runs.
            return false
        }
        val currentValue = pos.costSol * gainMultiple
        val gainPct = (gainMultiple - 1.0) * 100.0

        // V5.0.6064 ‚Äî A: PROTECTIVE PEAK PARTIAL for extreme runners.
        // Operator screenshot V5.0.6063: RUNNER peaked +3358% then crashed to
        // -87.3% with 'route pending' next to UNREALIZED ‚Äî Jupiter/pump-direct
        // routing stalled while the meme rug-snapped and the full-position
        // sell landed at dust. Fire an IMMEDIATE 25% protective partial the
        // moment peak first crosses +500%. Locks in some SOL even if the
        // main runner-bank Jupiter route stalls under provider degradation.
        // Only fires ONCE per position (partialSoldPct == 0.0), so a normal
        // laddered ride is untouched ‚Äî this is a safety valve for the fast
        // rugsnap case only.
        run {
            val peakPct6064 = try { pos.peakGainPct.coerceAtLeast(gainPct) } catch (_: Throwable) { gainPct }
            // V5.0.6072 ‚Äî PAPER PARITY. Was `!pos.isPaperPosition && ...`; paper
            // now fires the same protective peak partial so paper trades log a
            // "banked at +peak%" outcome for learning parity with live.
            val protectiveArm = peakPct6064 >= 500.0 &&
                pos.partialSoldPct <= 0.01 &&
                (pos.isPaperPosition || wallet != null)
            if (protectiveArm) {
                val remainingFraction6064 = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
                val sellFraction6064 = 0.25.coerceAtMost(remainingFraction6064)
                if (sellFraction6064 > 0.0) {
                    try {
                        ForensicLogger.lifecycle(
                            "PROTECTIVE_PEAK_PARTIAL_FIRED_6064",
                            "mint=${ts.mint.take(10)} symbol=${ts.symbol} peakPct=${peakPct6064.toInt()} curPct=${gainPct.toInt()} gain=${gainMultiple.fmt(2)}x sellPct=25 paper=${pos.isPaperPosition} reason=lock_first_slice_of_extreme_runner_before_route_stall",
                        )
                        PipelineHealthCollector.labelInc("PROTECTIVE_PEAK_PARTIAL_FIRED_6064")
                    } catch (_: Throwable) {}
                    onLog("üí∞‚ö° PROTECTIVE PEAK PARTIAL: ${ts.symbol} peak=${peakPct6064.toInt()}% ‚Äî banking 25% NOW before route stall", ts.mint)
                    executeProfitLockSellPaperOrLive(ts, wallet, sellFraction6064, "protective_peak_partial_${peakPct6064.toInt()}pct", walletSol, pos, actualPrice, gainMultiple, gainPct)
                    return true
                }
            }
        }

        val (capitalRecoveryThreshold, profitLockThreshold) = calculateProfitLockThresholds(ts)

        // V5.0.3896 ‚Äî ULTRA-RUNNER PANIC BANK.
        // If a live meme bid turns into a 50x/5000% monster, the correct action is
        // not to admire the runner or wait for the regular partial ladder. Bank the
        // bulk immediately before the inevitable holder/rug liquidity snapback. This
        // path bypasses normal capital-recovery/profit-lock cadence, but still uses
        // executeProfitLockSell so wallet proof/route/finality/journal authority stays
        // centralized. Paper keeps normal ladder so learning data remains comparable.
        // V5.0.4130 ‚Äî SANITY GATE. The peakGainPct >= 5000% trigger fires forever
        // once a position EVER peaked at 50x, even after the price collapses back
        // through entry. Operator journal showed banker selling at -29% / -66% pnl
        // because qty * price / costSol still read 50x while CURRENT price was
        // below entry. Require current value ‚â• 1.5√ó cost basis so we only bank
        // when the position is ACTUALLY a runner right now, not historically.
        val peakGainPct = try { pos.peakGainPct.coerceAtLeast(gainPct) } catch (_: Throwable) { gainPct }
        val currentValueAboveBasis4130 = currentValue >= pos.costSol * 1.5
        fun tryRouteRealClaimMismatchHarvest6029(label: String): Boolean {
            val w = wallet ?: return false
            val routeMultiple = try { RealPriceLock.routeImpliedGainMultiple(ts) } catch (_: Throwable) { null } ?: return false
            val routeValueSol = pos.costSol * routeMultiple
            val routeProfitSol = (routeValueSol - pos.costSol).coerceAtLeast(0.0)
            if (routeMultiple < 3.0) return false
            if (routeProfitSol < maxOf(0.02, walletSol * 0.08, pos.costSol * 1.5)) return false
            val remainingFraction6029 = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
            val sellFraction6029 = when {
                routeProfitSol >= walletSol.coerceAtLeast(0.01) -> 0.60
                routeProfitSol >= walletSol.coerceAtLeast(0.01) * 0.50 -> 0.45
                else -> 0.30
            }.coerceAtMost(remainingFraction6029)
            if (sellFraction6029 <= 0.0) return false
            try {
                ForensicLogger.lifecycle("ROUTE_REAL_CLAIM_MISMATCH_HARVEST_6029", "mint=${ts.mint.take(10)} symbol=${ts.symbol} label=$label claimed=${gainMultiple.fmt(2)}x route=${routeMultiple.fmt(2)}x routeProfit=${routeProfitSol.fmt(4)} wallet=${walletSol.fmt(4)} sellPct=${(sellFraction6029*100).toInt()} reason=ui_claim_overstated_but_route_profit_real")
                PipelineHealthCollector.labelInc("ROUTE_REAL_CLAIM_MISMATCH_HARVEST_6029")
            } catch (_: Throwable) {}
            onLog("üí∞ ROUTE-REAL HARVEST: ${ts.symbol} UI=${gainMultiple.fmt(1)}x route=${routeMultiple.fmt(1)}x profit‚âà${routeProfitSol.fmt(4)} SOL ‚Äî selling ${(sellFraction6029*100).toInt()}% NOW", ts.mint)
            executeProfitLockSell(ts, w, sellFraction6029, "route_real_harvest_${routeMultiple.fmt(1)}x", walletSol)
            return true
        }

        fun tryPersistedEntryRouteHarvest6099(label: String): Boolean {
            val w = wallet ?: return false
            val priority6099 = sellRoutePriorityFromBuyRoute6099(ts)
            if (priority6099 == SellRoutePriority6099.UNKNOWN_BUY_ROUTE) return false
            val routeMetaPresent6099 = pos.entryPriceSource.isNotBlank() || pos.entryPoolAddress.isNotBlank() || ts.lastPricePoolAddr.isNotBlank() || ts.pairAddress.isNotBlank() || ts.tokenMap.poolAddress.isNotBlank() || ts.tokenMap.pairAddress.isNotBlank()
            if (!routeMetaPresent6099) return false
            val routeProfit6099 = (currentValue - pos.costSol).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: return false
            if (gainMultiple < 3.0) return false
            if (routeProfit6099 < maxOf(0.02, walletSol * 0.05, pos.costSol * 1.5)) return false
            val remainingFraction6099 = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
            val sellFraction6099 = when {
                gainMultiple >= 50.0 || peakGainPct >= 5_000.0 -> 0.35
                gainMultiple >= 10.0 || peakGainPct >= 1_000.0 -> 0.30
                else -> 0.25
            }.coerceAtMost(remainingFraction6099)
            if (sellFraction6099 <= 0.0) return false
            try {
                ForensicLogger.lifecycle("PERSISTED_ENTRY_ROUTE_HARVEST_6099", "mint=${ts.mint.take(10)} symbol=${ts.symbol} label=$label gain=${gainMultiple.fmt(2)}x peakPct=${peakGainPct.toInt()} profit=${routeProfit6099.fmt(4)} wallet=${walletSol.fmt(4)} priority=$priority6099 entrySource=${pos.entryPriceSource} entryPool=${pos.entryPoolAddress.take(16)} sellPct=${(sellFraction6099*100).toInt()} reason=realpricelock_missing_or_disagrees_but_buy_route_known")
                PipelineHealthCollector.labelInc("PERSISTED_ENTRY_ROUTE_HARVEST_6099")
            } catch (_: Throwable) {}
            onLog("üí∞üß≠ ENTRY-ROUTE HARVEST: ${ts.symbol} @ ${gainMultiple.fmt(1)}x route=$priority6099 ‚Äî selling ${(sellFraction6099*100).toInt()}% NOW via buy-route priority", ts.mint)
            executeProfitLockSell(ts, w, sellFraction6099, "entry_route_harvest_${gainMultiple.fmt(1)}x", walletSol)
            return true
        }
        // V5.0.6072 ‚Äî PAPER PARITY. Was `!pos.isPaperPosition && ...`; paper
        // now also fires ultra-runner bank so 50x/100x paper monsters record
        // the exit outcome for learning symmetry.
        val ultraRunnerLiveBank = (gainMultiple >= 50.0 || peakGainPct >= 5_000.0) &&
            currentValueAboveBasis4130
        if (ultraRunnerLiveBank) {
            if (!pos.isPaperPosition && wallet == null) {
                ErrorLogger.warn("Executor", "üö´ ULTRA_RUNNER_BANK_DEFERRED: ${ts.symbol} @ ${gainMultiple.fmt(1)}x peak=${peakGainPct.toInt()}% ‚Äî wallet=null")
                try { ForensicLogger.lifecycle("ULTRA_RUNNER_BANK_DEFERRED", "mint=${ts.mint.take(10)} symbol=${ts.symbol} gain=${gainMultiple.fmt(2)}x peakPct=${peakGainPct.toInt()} reason=wallet_null") } catch (_: Throwable) {}
                return false
            }
            // V5.0.4182 ‚Äî REAL PRICE LOCK on ULTRA_RUNNER_BANK trigger.
            // Live-only price verifier; paper has no on-chain route to verify,
            // so paper always passes the price-real check (V5.0.6072).
            val priceReal = if (pos.isPaperPosition) true else try {
                com.lifecyclebot.engine.RealPriceLock.verifyUltraRunnerBank(
                    ts, gainMultiple, currentValue, pos.costSol,
                )
            } catch (_: Throwable) { true }  // never block on verifier failure
            if (!priceReal) {
                if (tryRouteRealClaimMismatchHarvest6029("ultra_runner_bank")) return true
                if (tryPersistedEntryRouteHarvest6099("ultra_runner_bank")) return true
                try {
                    ForensicLogger.lifecycle(
                        "ULTRA_RUNNER_BANK_DEFERRED_PRICE_UNREAL",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} gain=${gainMultiple.fmt(2)}x peakPct=${peakGainPct.toInt()} reason=jupiter_route_disagrees ‚Äî refresh next cycle",
                    )
                    PipelineHealthCollector.labelInc("ULTRA_RUNNER_BANK_DEFERRED_PRICE_UNREAL")
                } catch (_: Throwable) {}
                return false
            }
            val remainingFraction = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
            val sellFraction = when {
                peakGainPct >= 10_000.0 || gainMultiple >= 100.0 -> 0.95
                peakGainPct >= 5_000.0 || gainMultiple >= 50.0 -> 0.90
                else -> 0.80
            }.coerceAtMost(remainingFraction)
            if (sellFraction > 0.0) {
                try {
                    ForensicLogger.lifecycle(
                        "ULTRA_RUNNER_BANK_TRIGGERED",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} gain=${gainMultiple.fmt(2)}x peakPct=${peakGainPct.toInt()} sellPct=${(sellFraction*100).toInt()} paper=${pos.isPaperPosition} reason=mega_mfe_bank",
                    )
                    PipelineHealthCollector.labelInc("ULTRA_RUNNER_BANK_TRIGGERED")
                } catch (_: Throwable) {}
                onLog("üöÄüí∞ ULTRA RUNNER BANK: ${ts.symbol} @ ${gainMultiple.fmt(1)}x peak=${peakGainPct.toInt()}% ‚Äî selling ${(sellFraction*100).toInt()}% NOW", ts.mint)
                if (pos.isPaperPosition) {
                    executeProfitLockSellPaperOrLive(ts, wallet, sellFraction, "ultra_runner_bank_${gainMultiple.fmt(1)}x", walletSol, pos, actualPrice, gainMultiple, gainPct)
                } else {
                    @Suppress("NAME_SHADOWING") val wallet = wallet ?: return false
                    executeProfitLockSell(ts, wallet, sellFraction, "ultra_runner_bank_${gainMultiple.fmt(1)}x", walletSol)
                }
                return true
            }
        }
        
        // V5.0.6028 ‚Äî WALLET-GROWTH RUNNER HARVEST.
        // A screenshot +6779% runner does not matter unless SOL lands back in the
        // wallet. The old path waited for trophy ultra thresholds / trailing locks
        // while the compounding governor only saw closed negative truth, so live
        // buys stayed tiny and monster open PnL sat unrealized. If a live runner's
        // trusted current profit is already material versus wallet size, bank a
        // tranche now through the same executeProfitLockSell finality path.
        // V5.0.6072 ‚Äî PAPER PARITY. Paper positions now also fire so paper's
        // huge unrealized runners land banked SOL into the (simulated) wallet
        // for symmetric compounding learning.
        val unrealizedProfitSol6028 = (currentValue - pos.costSol).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val walletGrowthHarvest6028 = gainMultiple >= 3.0 &&
            unrealizedProfitSol6028 >= maxOf(0.02, walletSol * 0.10, pos.costSol * 2.0) &&
            currentValueAboveBasis4130
        if (walletGrowthHarvest6028) {
            if (!pos.isPaperPosition && wallet == null) {
                val recoveryReason = "URGENT_WALLET_GROWTH_HARVEST_WALLET_NULL_${gainMultiple.fmt(1)}x"
                try { PendingSellQueue.add(ts.mint, ts.symbol ?: "?", recoveryReason) } catch (_: Throwable) {}
                try { HostWalletTokenTracker.markSellWaitingBalanceProof(ts.mint, ts.symbol, recoveryReason) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("WALLET_GROWTH_HARVEST_DEFERRED_WALLET_NULL_6028") } catch (_: Throwable) {}
                return false
            }
            // Paper skips the Jupiter route verification (no on-chain route to check).
            val priceReal6028 = if (pos.isPaperPosition) true else try {
                RealPriceLock.verifyUltraRunnerBank(ts, gainMultiple, currentValue, pos.costSol)
            } catch (_: Throwable) { true }
            if (!priceReal6028) {
                if (tryRouteRealClaimMismatchHarvest6029("wallet_growth_harvest")) return true
                if (tryPersistedEntryRouteHarvest6099("wallet_growth_harvest")) return true
                // V5.0.6045 ‚Äî STALE-RUNNER FORCE-HARVEST (operator mandate 2026-07-03:
                // "the bot should be well ahead of its starting balance. this has to
                //  be captured. no exceptions!").
                // When RealPriceLock and route-real fallback BOTH fail (typically
                // during API provider degradation ‚Äî Jupiter/Birdeye/etc. timing out),
                // a genuine +1000%+ runner with a locked trailing stop was sitting
                // unrealized indefinitely. Doctrine: after the position has held
                // material profit for a sustained window (>=120s since entry AND
                // gain sustained), FORCE the harvest sell attempt regardless of
                // price-verifier disagreement. Sell fires with a smaller fraction
                // (25% probe) so if the route is genuinely dead the loss is capped;
                // if the route fills, real profit lands in the wallet and next
                // cycle's fresh price data unlocks the remaining tranche via the
                // normal path. Peak-hold check ensures we're banking a real runner
                // and not a momentary blip.
                val nowMs6045 = System.currentTimeMillis()
                val ageMs6045 = if (pos.entryTime > 0L) (nowMs6045 - pos.entryTime).coerceAtLeast(0L) else 0L
                val peakSustained6045 = try { pos.peakGainPct >= peakGainPct * 0.85 && pos.peakGainPct >= 300.0 } catch (_: Throwable) { false }
                val staleForceHarvest6045 = ageMs6045 >= 120_000L && peakSustained6045 &&
                    unrealizedProfitSol6028 >= maxOf(0.02, walletSol * 0.05)
                if (staleForceHarvest6045 && wallet != null) {
                    val remainingFraction6045 = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
                    val forceFraction6045 = 0.25.coerceAtMost(remainingFraction6045)
                    if (forceFraction6045 > 0.0) {
                        try {
                            ForensicLogger.lifecycle("WALLET_GROWTH_HARVEST_STALE_FORCE_6045", "mint=${ts.mint.take(10)} symbol=${ts.symbol} gain=${gainMultiple.fmt(2)}x peakPct=${peakGainPct.toInt()} unrealized=${unrealizedProfitSol6028.fmt(4)} wallet=${walletSol.fmt(4)} ageMs=$ageMs6045 sellPct=${(forceFraction6045*100).toInt()} reason=price_verifier_disagrees_but_position_stale_material_profit")
                            PipelineHealthCollector.labelInc("WALLET_GROWTH_HARVEST_STALE_FORCE_6045")
                        } catch (_: Throwable) {}
                        onLog("üí∞‚ö° STALE-RUNNER FORCE-HARVEST: ${ts.symbol} @ ${gainMultiple.fmt(1)}x peak=${peakGainPct.toInt()}% unrealized=${unrealizedProfitSol6028.fmt(4)} SOL ‚Äî probing route with ${(forceFraction6045*100).toInt()}%", ts.mint)
                        executeProfitLockSell(ts, wallet, forceFraction6045, "stale_runner_force_${gainMultiple.fmt(1)}x", walletSol)
                        return true
                    }
                }
                try {
                    ForensicLogger.lifecycle("WALLET_GROWTH_HARVEST_DEFERRED_PRICE_UNREAL_6028", "mint=${ts.mint.take(10)} symbol=${ts.symbol} gain=${gainMultiple.fmt(2)}x unrealized=${unrealizedProfitSol6028.fmt(4)} wallet=${walletSol.fmt(4)} reason=route_disagrees ageMs=$ageMs6045 peakSustained=$peakSustained6045")
                    PipelineHealthCollector.labelInc("WALLET_GROWTH_HARVEST_DEFERRED_PRICE_UNREAL_6028")
                } catch (_: Throwable) {}
                return false
            }
            val remainingFraction6028 = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
            val sellFraction6028 = when {
                unrealizedProfitSol6028 >= walletSol.coerceAtLeast(0.01) * 1.0 -> 0.65
                unrealizedProfitSol6028 >= walletSol.coerceAtLeast(0.01) * 0.50 -> 0.50
                else -> 0.35
            }.coerceAtMost(remainingFraction6028)
            if (sellFraction6028 > 0.0) {
                try {
                    ForensicLogger.lifecycle("WALLET_GROWTH_HARVEST_TRIGGERED_6028", "mint=${ts.mint.take(10)} symbol=${ts.symbol} gain=${gainMultiple.fmt(2)}x unrealized=${unrealizedProfitSol6028.fmt(4)} wallet=${walletSol.fmt(4)} sellPct=${(sellFraction6028*100).toInt()} reason=land_runner_profit_in_wallet")
                    PipelineHealthCollector.labelInc("WALLET_GROWTH_HARVEST_TRIGGERED_6028")
                } catch (_: Throwable) {}
                onLog("üí∞ WALLET GROWTH HARVEST: ${ts.symbol} @ ${gainMultiple.fmt(1)}x unrealized=${unrealizedProfitSol6028.fmt(4)} SOL ‚Äî selling ${(sellFraction6028*100).toInt()}% NOW", ts.mint)
                if (pos.isPaperPosition) {
                    executeProfitLockSellPaperOrLive(ts, wallet, sellFraction6028, "wallet_growth_harvest_${gainMultiple.fmt(1)}x", walletSol, pos, actualPrice, gainMultiple, gainPct)
                } else {
                    @Suppress("NAME_SHADOWING") val wallet = wallet ?: return false
                    executeProfitLockSell(ts, wallet, sellFraction6028, "wallet_growth_harvest_${gainMultiple.fmt(1)}x", walletSol)
                }
                return true
            }
        }

        if (!pos.capitalRecovered && gainMultiple >= capitalRecoveryThreshold) {
            // V5.0.3686 ‚Äî accounting/source fix: capital recovery sizing is
            // target-recovery / expected net value, clamped to remaining position.
            // Never force a 25% minimum and never let a phantom multiple inflate
            // sell quantity/notional. Quantity is fraction of remaining tokens only.
            // V5.0.7029 ‚Äî this was `pos.qtyToken * actualPrice`, which is USD,
            // divided one line below by a SOL target. The recovery fraction was
            // therefore ~1/133 of what it should be, so "sell enough to recover
            // the entry" sold almost nothing and the position stayed fully
            // exposed. The `?: currentValue` fallback was already SOL, so the
            // two branches of the same variable were in different currencies.
            val expectedNetPositionValueSol =
                proceedsSol7029(pos.qtyToken, actualPrice)?.takeIf { it > 0.0 } ?: currentValue
            val targetRecoverySol = pos.costSol.coerceAtLeast(0.0)
            val sellFraction = (targetRecoverySol / expectedNetPositionValueSol)
                .coerceIn(0.0, 1.0)
                .coerceAtMost((100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0)
            if (sellFraction <= 0.0) return false
            val sellQty = pos.qtyToken * sellFraction
            // V5.0.7029 ‚Äî USD, not SOL; see proceedsSol7029. This value is
            // stamped onto the position as capitalRecoveredSol and
            // lockedProfitFloor below, so an unconverted figure did not merely
            // misreport a number ‚Äî it set a SOL-denominated profit FLOOR from
            // a USD quantity, ~133x too high, which no later exit could clear.
            val sellSol = proceedsSol7029(sellQty, actualPrice) ?: 0.0
            
            onLog("üîí CAPITAL RECOVERY REQUEST: ${ts.symbol} @ ${gainMultiple.fmt(2)}x (threshold: ${capitalRecoveryThreshold.fmt(2)}x) ‚Äî attempting to sell ${(sellFraction*100).toInt()}% to recover initial", ts.mint)
            try { PipelineHealthCollector.labelInc("CAPITAL_RECOVERY_NOTIFY_DEFERRED_UNTIL_FINALITY_4585") } catch (_: Throwable) {}
            
            // V5.9.751b ‚Äî route on POSITION isPaper, not config. Previously
            // a live position with a transient wallet=null silently booked a
            // PAPER capital-recovery sell, leaving the real tokens on-chain
            // while the position numbers updated as if they were sold. The
            // wallet=null branch now defers (returns false) so the engine
            // retries on the next monitor tick once the wallet reconnects.
            if (!pos.isPaperPosition && wallet == null) {
                val recoveryReason = "URGENT_CAPITAL_RECOVERY_WALLET_NULL_${gainMultiple.fmt(1)}x"
                ErrorLogger.warn("Executor",
                    "üö´ CAPITAL_RECOVERY_DEFERRED: ${ts.symbol} ‚Äî live position but wallet=null. Enqueued urgent proof/retry.")
                try { PendingSellQueue.add(ts.mint, ts.symbol ?: "?", recoveryReason) } catch (_: Throwable) {}
                try { HostWalletTokenTracker.markSellWaitingBalanceProof(ts.mint, ts.symbol, recoveryReason) } catch (_: Throwable) {}
                try {
                    com.lifecyclebot.engine.sell.BalanceProofWaitState.markWaiting(
                        ts.mint, ts.symbol ?: "?", recoveryReason,
                        runtimeGeneration = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L },
                    )
                } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle("PROFIT_PRESSURE_SELL_RECOVERY_ENQUEUED_4384", "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$recoveryReason kind=capital_recovery wallet_null pendingSell=true balanceProofWait=true")
                    PipelineHealthCollector.labelInc("PROFIT_PRESSURE_SELL_RECOVERY_ENQUEUED_4384")
                } catch (_: Throwable) {}
                return false
            }
            if (pos.isPaperPosition) {
                val opReason6510 = "capital_recovery_${gainMultiple.fmt(1)}x"
                executeProfitLockSellPaperOrLive(ts, wallet, sellFraction, opReason6510, walletSol, pos, actualPrice, gainMultiple, gainPct)
                if (ts.position.qtyToken < pos.qtyToken) {
                    ts.position = ts.position.copy(capitalRecovered = true, capitalRecoveredSol = sellSol,
                        isHouseMoney = true, lockedProfitFloor = sellSol)
                }
            } else {
                // V5.9.751b ‚Äî wallet is guaranteed non-null here by the
                // CAPITAL_RECOVERY_DEFERRED guard above; assert for smart cast.
                executeProfitLockSell(ts, wallet!!, sellFraction, "capital_recovery_${gainMultiple.fmt(1)}x", walletSol)
            }
            return true
        }
        
        if (pos.capitalRecovered && !pos.profitLocked && gainMultiple >= profitLockThreshold) {
            val remainingFraction = (100.0 - pos.partialSoldPct).coerceAtLeast(0.0) / 100.0
            val sellFraction = 0.50.coerceAtMost(remainingFraction)
            if (sellFraction <= 0.0) return false
            val sellQty = pos.qtyToken * sellFraction
            // V5.0.7029 ‚Äî USD, not SOL; see proceedsSol7029.
            val sellSol = proceedsSol7029(sellQty, actualPrice) ?: 0.0
            
            onLog("üîê PROFIT LOCK REQUEST: ${ts.symbol} @ ${gainMultiple.fmt(2)}x (threshold: ${profitLockThreshold.fmt(2)}x) ‚Äî attempting to lock 50% of remaining profits", ts.mint)
            try { PipelineHealthCollector.labelInc("PROFIT_LOCK_NOTIFY_DEFERRED_UNTIL_FINALITY_4585") } catch (_: Throwable) {}
            
            // V5.9.751b ‚Äî see B1 note. Route on position isPaper, defer if
            // live-position + wallet=null.
            if (!pos.isPaperPosition && wallet == null) {
                val recoveryReason = "URGENT_PROFIT_LOCK_WALLET_NULL_${gainMultiple.fmt(1)}x"
                ErrorLogger.warn("Executor",
                    "üö´ PROFIT_LOCK_DEFERRED: ${ts.symbol} ‚Äî live position but wallet=null. Enqueued urgent proof/retry.")
                try { PendingSellQueue.add(ts.mint, ts.symbol ?: "?", recoveryReason) } catch (_: Throwable) {}
                try { HostWalletTokenTracker.markSellWaitingBalanceProof(ts.mint, ts.symbol, recoveryReason) } catch (_: Throwable) {}
                try {
                    com.lifecyclebot.engine.sell.BalanceProofWaitState.markWaiting(
                        ts.mint, ts.symbol ?: "?", recoveryReason,
                        runtimeGeneration = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L },
                    )
                } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle("PR◊é6”Æ ◊¨¢h≠µÁHYà
ôYP[]HèHåJH¬àôYTô]ûT]Y]YKô[ú]Y]YJêQSë◊—ëQW’–SUÃKôYP[]H
àëQW‘‘U‘êUSÀú\ùX[‹Ÿ[›åó›ÃHäBàôYTô]ûT]Y]YKô[ú]Y]YJêQSë◊—ëQW’–SUÃãôYP[]H
à
KåHëQW‘‘U‘êUS Kú\ùX[‹Ÿ[›åó›ÃàäBàBàBÇà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW‘””‘ëUTìëQàî\ùX[€€	 ›
åL
Kù“[ù

_IH€›	‹€€òX⁄Àôõ]

_H””ô]	€ô]õôõ]

_Hãà⁄Y»Hö[ò[⁄YÀà€€[[›[ùH€€òX⁄ÀàòY\ïY»HìQSQHãà
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àúô[X\ŸJÀõZ[ù
BÇàÀ»çKéKçÕ»8†%⁄\ôHÃÃÃôX\›\ûH⁄\€à€ù»HUëHúò[ò⁄àÀ»ŸàHX[ùX[ô\]Y\›\ùX[Ÿ[[ùûH⁄[ùàHTTÇàÀ»úò[ò⁄Ÿà\»ÿ[YHù[ò›[€à[ôXYH⁄\€ú»
çKéKçé
N¬àÀ»HUëHúò[ò⁄ÿ\»Z\‹⁄[ô»Hÿ[€»[ûH^\õò[àÀ»\ùX[X€‹ŸHô\]Y\›
RHù]€à»TJH\‹⁄]Y	BàÀ»»ôX\›\ûH[à]ôH[ŸKà\ŸHô]õ
‹›YôYJKÇàYà
ô]õàå
H¬àûH¬àYà
‹Àö\’ôX\›\ûT‹⁄][€à‹ÀùòY[ô”[ŸHOHïëPT’TñHäH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKïôX\›\ûSX[òYŸ\ãò€€ùöXù]Qù[Qúõ€UôX\›\ûTÿÿ[
àô]õ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKïÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸJBàH[ŸH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKïôX\›\ûSX[òYŸ\ãò€€ùöXù]Qúõ€SY[YTŸ[
àô]õ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKïÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸJBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãïôX\›\ûH‹]\úõ‹à
ô\T\ùX[]ôJNà	ŸKõY\‹ÿYŸ_HäBàBàBÇàÀ»çKéKåLMåH8†%õ»ﬁ[ù]X»ô\õÀ\öXŸHTïPS‘—Sõ›»\ôKÇàÀ»]ôUòYHXõ›ôH\»Hÿ[õ€öXÿ[\ùX[›]€€YH[ôòZ[ú¬àÀ»õ›Y⁄òYR\›‹ûT›‹ôH8°§àÿ[õ€öXÿ[›]€€YPù\»⁄[àò[YÇÇàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTïPS‘—S–P–”’SïSë»ãàõ[ŸO[]ôHZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H€€›I ›
åL
Kôõ]
J_H€‹›I€]ôT\ùX[€‹›ò\⁄\‘€€ôõ]€€

_H‹õ‹‹œI‹€€òX⁄Àôõ]€€

_HõI€]ôTõôõ]⁄Y€ôY€€

_Hô]I€ô]õôõ]⁄Y€ôY€€

_H›I€]ôTÿ€‹ôKôõ]›ôX⁄\ŸJ
_HôX\€€èI€]ôUòYKúôX\€€üH⁄YœIŸö[ò[⁄YÀùZŸJMä_H€›\òŸO\ô\]Y\›\ùX[Ÿ[äHHÿ]⁄
Œàõ›ÿXõJHﬂBà€ìŸ ∏ß!HUëHTïPS—S	 ›
åL
Kù“[ù

_IH	€]ôTÿ€‹ôKôõ]›ôX⁄\ŸJ
_Hà
¬àò€‹›I€]ôT\ùX[€‹›ò\⁄\‘€€ôõ]€€

_H‹õ‹‹œI‹€€òX⁄Àôõ]€€

_HõI€]ôTõôõ]⁄Y€ôY€€

_Hô]I€ô]õôõ]⁄Y€ôY€€

_H⁄YœIŸö[ò[⁄YÀùZŸJMä_x†)àãÀõZ[ù
Bàà€ìõ›YûJº'‰¨]ôH\ùX[Ÿ[ãàâ›Àúﬁ[Xõ€Nà€€	 ›
åL
Kù“[ù

_IHì	€]ôTÿ€‹ôKôõ]›ôX⁄\ŸJ
_H
	€ô]õôõ]⁄Y€ôY€€

_H””ô]
Hãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì BàôXŸZ\çMçèÀö[ùõ⁄ŸJ\ùX[Ÿ[ôXŸZ\çMçäùYKò[ŸKìUëW‘TïPS–””ëíTìQQãö[ò[⁄Y JBààHÿ]⁄
Nà^Ÿ\[€äH¬àÀ»çKéKçÕ8†%€\‹⁄YöYY‹›\][›Hõ‹ô[ú⁄X‹»õ‹à\ùX[\Ÿ[ÿ]⁄àò[Ÿ[òYRŸ^LàH]ôUòYSŸ‘›‹ôKöŸ^Qõ‹äÀõZ[ùÀú‹⁄][€ãô[ùûU[YJBàò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ KõY\‹ÿYŸHŒàù[ö€õ›€àäBàò[úõÿYÿ\›ô]öY\»Hô\õ–ò[[òŸTô]öY\ÀõY\ôŸJÀõZ[ù
»óÿúõÿYÿ\›‹\ùX[ãJH»€»Oà€
»HHŒàBàÀ»çKéKåMLåà8†%ÿ[õ€öXÿ[€\‹⁄YöXÿ][€à
MŒ\»“SKõ›Ÿ[ô\öX»€\YŸJKÇàò[õ›]P€»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ›]Q\úõ‹ê€\‹⁄YöY\ãò€\‹⁄YûJÿYôJBàò[õ›]T€XﬁHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ›]Q\úõ‹ê€\‹⁄YöY\ãúô]ûT€XﬁJõ›]P€ÀúõÿYÿ\›ô]öY\Àô]ûTÿ⁄Y[YHò[ŸJBàÀ»çKéKåMLÃ»8†%‹X»][HŒàMŒ»»ST‘ì’UW“SïêSQ]\›ëKTëT””ëHBàÀ»ô[ùYKõ›ô]ûHHÿ[YH[\Y\ôX›^[ÿYàX\ö»HZ[ù€»Hô^àÀ»][\⁄⁄\»[\[ò]]ôH[ôôK\ô\€€ô\»»[\›ÿ\‘ò^Y][K“ù\]\ãÇàYà
õ›]T€XﬁKúô\]Z\ôUô[ùYTôTô\€€][€äH¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ìY[YUô[ùYTõ›]\ãõX\ö‘[\õ›]R[ùò[Y
ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S’ëSïQW‘ëW‘ëT””ëHãàõZ[ùI›ÀõZ[ùùZŸJL
_H€\‹œI‹õ›]P€Àõò[Y_HX›[€è\⁄⁄\‹[\Ÿ\ôX›‹ô\ô\€€ôHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàò[òZ[\ôP€\‹»Hõ›]P€Àõò[YBàYà
õ›]T€XﬁKúô[X\ŸSÿ⁄ H¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àúô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùîì’UW—êRSQ»à
»õ›]P€Àõò[YJHHÿ]⁄
Œàõ›ÿXõJHﬂBàBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^LãÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàâòZ[\ôP€\‹»8†%	ŸKöò]òP€\‹Àú⁄[\Sò[Y_Nà	‹ÿYôKùZŸJLå
_H
\ùX[\Ÿ[][\	úõÿYÿ\›ô]öY\ HãàòY\ïY»HìQSQHãà
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àúô[X\ŸJÀõZ[ù
Bà\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àã∏ßcUëHTïPS—SêRSQà	›Àúﬁ[Xõ€H€\‹œIòZ[\ôP€\‹»	ŸKõY\‹ÿYŸ_HäBà€ìŸ ∏ßc]ôH\ùX[Ÿ[êRSQà	òZ[\ôP€\‹»8†%	‹ÿYôKùZŸJ
_H
ô]ûH	úõÿYÿ\›ô]öY\ HãÀõZ[ù
BàBàBàBàBÇàö]ò]Hù[à\”õ€îõ›]TŸ[ÿZ]
Œà⁄Ÿ[î›]JNàõ€€X[à¬àô]\õàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[êò[[òŸTõ€ŸïÿZ]›]Kö\’ÿZ][ô ÀõZ[ù
HàòYUô\öYöY\ãòX›]ôTŸ[⁄Y ÀõZ[ù
HOHù[à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKú⁄›[õÿ⁄”ô^ô]ûJÀõZ[ù
Hà‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãúŸ[õÿ⁄‘ôX\€€äÀõZ[ù
HOHù[à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôT‹⁄][€ê€‹ŸP]]‹ö]Kö\’\õZ[ò[‹ê€‹⁄[ô ÀõZ[ù
BàHÿ]⁄
Œàõ›ÿXõJH»ò[ŸHBàBÇà[ù\õò[ù[à‘Ÿ[
Œà⁄Ÿ[î›]KôX\€€éà›ö[ôÀàÿ[]à€€[òUÿ[]Àÿ[]€€à›XõKàY[ù]NàòYRY[ù]O»Hù[
NàŸ[ô\›[¬àò[\\ê€‹ŸP]]‹ö]PX›]ôHHÀú‹⁄][€ãö\‘\\î‹⁄][€ÇàYà
\\ê€‹ŸP]]‹ö]PX›]ôJH¬àò[›X\ôH\\î‹⁄][€ê€‹ŸP]]‹ö]KúôTŸ[›X\ô
îTTàãÀõZ[ùÀúﬁ[Xõ€ôX\€€äBàYà
›X\ôòõÿ⁄ŸY
H¬àô]\õàŸ[ô\›[êSëPQW–”‘—QàBà\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹ŸTô\]Y\›Y
îTTàãÀõZ[ùÀúﬁ[Xõ€ôX\€€äBàBà^X›][€îõ€›ÿ]\ŸUòXŸKúŸ[
ë◊‘—S—SïñHãÀúôX\€€èIôX\€€àÿ[]ÿYYI›ÿ[]OHù[Hÿ[]€€Iÿ[]€€Y[ù]OI⁄Y[ù]OÀú€›\òŸHŒàãHüH‹‘]OI›Àú‹⁄][€ãú]U⁄Ÿ[üH[ùûOI›Àú‹⁄][€ãô[ùûTöXŸ_HY⁄I›Àú‹⁄][€ãöY⁄\›öXŸ_HäBàÀ»çKéKåMLH8†%[›ôH\\àŸ]KZ[à[^H›X\ô\ôX›H[ù»‘Ÿ[ÇàÀ»\»[ú›\ô\»^]»‹öY⁄[ò][ô»úõ€Hö\⁄–⁄X⁄»
ZŸHéÿÿ]\›õ‹X◊€‹‹ BàÀ»‹à[òQò\›ùY—]X›‹à\ôHÿ]Y⁄ûHHŸ]KZ[à[^HYà^H\ôHòZŸH€Ÿù‹‹Ÿ\ÀÇàYà
Àú‹⁄][€ãö\‘\\î‹⁄][€äH¬àò[ôX\€€ï\\àHôX\€€ãù\\òÿ\ŸJ
Bàò[\ôõ€‹ì‹ë[Y\ôŸ[òﬁHHôX\€€ï\\ãò€€ùZ[ú íTë—ì”‘àäHàôX\€€ï\\ãò€€ùZ[ú îêTQ“Të—ì”‘àäHàôX\€€ï\\ãò€€ùZ[ú î’TïT‘’—QT“Të—ì”‘àäHàôX\€€ï\\ãò€€ùZ[ú îïQ◊‘–QëUHäHàôX\€€ï\\ãò€€ùZ[ú ìPSïPSäBàò[€‹ŸYY€”\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
HH
õ›Ÿ\ùöXŸKúôXŸ[ùP€‹ŸY\÷›ÀõZ[ùHŒà
BàYà
Z\ôõ€‹ì‹ë[Y\ôŸ[òﬁH	âà€‹ŸYY€”\»[àãçåÃ
H¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S—TP–UW‘’TëT‘—QãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€à€‹ŸYY€”\œI€‹ŸYY€”\»›YŸO\ôW‹Ÿ[€ÿ⁄»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàÀ»çKååÕÃà8†%€‹ŸK[YŸ\à\Xÿ]H›\ô\‹⁄[€à]\›\[àôYõ‹ôBàÀ»X‹]Z\ôTŸ[ÿ⁄ 
KàÕÃ[\⁄›ŸY—S”–“◊‘—UML»[ôàÀ»TTó‘—S—TP–UW‘’TëT‘—QLÕç»ôXÿ]\ŸH‘Ÿ[X‹]Z\ôYBàÀ»Ÿ[ô\ò[ÿ⁄À[à\\îŸ[\ÿ€›ô\ôYHZ[ùÿ\»[ôXYH”‘—QÇàÀ»\Xÿ]H€‹ŸYõ›‹»\ôHõ›Ÿ[][\Œ»ô]ô\à⁄\õàŸ[ÿ⁄‹ÀÇàò[^\›[ô–€‹ŸRYHûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî‹⁄][€ê€‹ŸSYŸ\ãò€‹ŸRYŸäÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJH»ù[BàYà
^\›[ô–€‹ŸRYOHù[
H¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S—TP–UW‘’TëT‘—QãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H‹öY⁄[ò[€‹ŸRYI^\›[ô–€‹ŸRYôX\€€èIôX\€€à›YŸO\ôW‹Ÿ[€ÿ⁄»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàYà
⁄›[[^T\\î€Ÿù‹‹—^]
ÀôX\€€äJHô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàH[ŸH¬àYà
õÿ⁄“YîŸ[[ëõY⁄
ÀôX\€€äJHô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBÇàò[òYRYHY[ù]HŒàòYRY[ù]SX[òYŸ\ãôŸ]‹ê‹ôX]JÀõZ[ùÀúﬁ[Xõ€Àú€›\òŸJBÇàÀ»]€ZX»›X\ôà€õH”ëHŸ[ÿ[àõÿŸYY\àZ[ù]H[YKÇàÀ»çKéKçÕMà8†%XòX⁄ŸYX‹]Z\ôH
å»›[K\ô[X\ŸHÿ]⁄Ÿ KÇàYà
XX‹]Z\ôTŸ[ÿ⁄ ÀõZ[ù
JH¬à€ìŸ ∏¶®;Ó#»—S““TQàŸ[[ôXYH[ã\õŸ‹ô\‹»õ‹à	›Àúﬁ[Xõ€HãòYRYõZ[ù
Bàô]\õàŸ[ô\›[êSëPQW–”‘—QàBÇàûH¬ÇàÀ»çKéKçÕH8†%ëRQêUHôYõ‹ôHH\”‹[à⁄X⁄»€»›Xã]òY\à‹⁄][€ú¬àÀ»
ôX\›\ûK⁄]€⁄[ã]X[]KõYP⁄\[€€ú⁄›
H]ô]ô\à‹õ›BàÀ»òX⁄»»Àú‹⁄][€àÿ[à›[ôH€€àÿ\»Hö[X\ûHÿ]\ŸHŸÇàÀ»	€Y[YHŸ[»ô]ô\àôXX⁄H‹›ÿ[]	Œà›Xã]òY\ú»Ÿ\‹⁄][€ú¬àÀ»[àö]ò]HX\ÀÀú‹⁄][€ãö\”‹[èYò[ŸK‘Ÿ[ô]\õôYàÀ»SëPQW–”‘—Q›ÿ\ô]ô\àö\ôYÇàôZYò]T‹⁄][€ëúõ€T›XïòY\ú  BàÀ»çKéKçLH8†%ö[ò[€ãX⁄Z[àò[òX⁄ŒàYàõ»›Xã]òY\àY]ù]àÀ»Hÿ[]€ãX⁄Z[à—T»€HZ[ùôZYò]Húõ€H\ôKÇàÀ»‹\ò]‹àõ‹ô[ú⁄X‹Œà	›‹õ€ô»H⁄Ÿ[ú»\ôH[à^Hÿ[]…»8†%BàÀ»õ›ÿ\»Xõ‹ù[ô»Ÿ[»⁄]]OL⁄[HHòY»ÿ\»⁄][ô»[ÇàÀ»Hÿ[]à\»›X\ò[ùY\»HŸ[\[[ôHÿ[àS–VT»[\àÀ»[ûH⁄Ÿ[àHÿ[]X›X[H›€úÀÇàYà
Àú‹⁄][€ãú]U⁄Ÿ[àHå
H¬àôZYò]T‹⁄][€ëúõ€Uÿ[]
Àÿ[]
BàBÇàò[\‘\\àHÀú‹⁄][€ãö\‘\\î‹⁄][€Çàò[\’ÿ[]Hÿ[]OHù[ÇàÀ»çKéKåéLíVà‘Ÿ[›X\ô8†%ÿ[YH[ô[ô’ô\öYûHõ‹òŸKX€X\à\»õ›Ÿ\ùöXŸH
»]ôTŸ[ÇàÀ»Yà]U⁄Ÿ[ààù][ô[ô’ô\öYûO]ùYHõ‹ààLåÀ€X\à]€»\”‹[àôX€€Y\»ùYBàÀ»[ôHŸ[\»õ›[ò€‹úôX›HôZôX›Y\ôKÇàYà
Àú‹⁄][€ãú[ô[ô’ô\öYûH	âàÀú‹⁄][€ãú]U⁄Ÿ[ààå
H¬àò[[ô[ô–YŸS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YBàYà
[ô[ô–YŸS\»èHLåÃ
H¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãà∏¶®;Ó#»—‘—S’ëTíQñW‘’P“◊H	›Àúﬁ[Xõ€H	‹[ô[ô–YŸS\»»L\»8†%õ‹òŸKX€X\ö[ô»[ô[ô’ô\öYûH[à‘Ÿ[àäBàﬁ[ò⁄õ€ö^ôY
 H¬àÀú‹⁄][€àHÀú‹⁄][€ãò€‹J[ô[ô’ô\öYûHHò[ŸJBàBàBàBàYà
]Àú‹⁄][€ãö\”‹[äH¬àYà
\\ê€‹ŸP]]‹ö]PX›]ôJH¬à\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹ŸY
îTTàãÀõZ[ùÀúﬁ[Xõ€î‘“US”ó–SëPQW–”‘—QâôX\€€àäBàBà€ìŸ ∏¶®;Ó#»—S““TQà‹⁄][€à[ôXYH€‹ŸYõ‹à	›Àúﬁ[Xõ€H
]OI›Àú‹⁄][€ãú]U⁄Ÿ[üH[ô[ô’ô\öYûOI›Àú‹⁄][€ãú[ô[ô’ô\öYû_JHãòYRYõZ[ù
Bàô]\õàŸ[ô\›[êSëPQW–”‘—QàBààò[€€ôöY”[ŸHHYà
\‘\\îï

JHú\\àà[ŸHõ]ôHÇàò[‹⁄][€ì[ŸHHYà
\‘\\äHú\\àà[ŸHìUëHÇà€ìŸ º'‰È‘Ÿ[à	›Àúﬁ[Xõ€H‹⁄][€ì[ŸOI‹⁄][€ì[ŸH€€ôöY”[ŸOI€€ôöY”[ŸH\’ÿ[]I\’ÿ[]ôX\€€èIôX\€€àãòYRYõZ[ù
BààYà
Z\‘\\à	âà\‘\\îï

JH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àã∏¶®;Ó#»UëH‹⁄][€àŸ[⁄[H€€ôöY»\»TTàH^X›][ô»UëHŸ[[û]ÿ^HHäBà€ìŸ ∏¶®;Ó#»UëH‹⁄][€à	›Àúﬁ[Xõ€H]\›ôH€€UëH]ô[à›Y⁄€€ôöY»\»\\àãòYRYõZ[ù
BàBààYà
Z\‘\\à	âàÿ[]OHù[
H¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'Ê™‘íUP–Sà]ôH[ŸHŸ[][\Yù]–SUT»ïSHäBà\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'Ê™⁄Ÿ[à	›Àúﬁ[Xõ€HH][\[ô»ÿ[]ôX€€õôX›ããàäBààûH¬àò[ôX€€õôX›Yÿ[]Hÿ[]X[òYŸ\ãò][\ôX€€õôX›

BàYà
ôX€€õôX›Yÿ[]OHù[
H¬à\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àã∏ß!Hÿ[]ôX€€õôX›YHõÿŸYY[ô»⁄]Ÿ[ããàäBà€ìŸ ∏ß!Hÿ[]ôX€€õôX›YHõÿŸYY[ô»⁄]	›Àúﬁ[Xõ€HŸ[ãòYRYõZ[ù
Bàô]\õà]ôTŸ[
ÀôX\€€ãôX€€õôX›Yÿ[]ôX€€õôX›Yÿ[]ôŸ]€€ò[[òŸJ
KòYRY
BàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'Ê™ÿ[]ôX€€õôX›òZ[Yà	ŸKõY\‹ÿYŸ_HäBàBàà\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'Ê™⁄Ÿ[à	›Àúﬁ[Xõ€HUQUQQì‘àëUñHHôX€€õôX›ÿ[]HäBà€ìŸ º'Ê™—SUQUQQà	›Àúﬁ[Xõ€Hÿ[]\ÿ€€õôX›YH⁄[ô]ûHãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™ÿ[]\ÿ€€õôX›YHãàêÿ[õõ›Ÿ[	›Àúﬁ[Xõ€HHÿ[]\»ïSH]Y]YYõ‹àô]ûKàôX€€õôX›ÿ[]Hãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà€ïÿ\›
º'Ê™ôX€€õôX›ÿ[]»Ÿ[	›Àúﬁ[Xõ€HHäBàà[ô[ô‘Ÿ[]Y]YKòY
ÀõZ[ùÀúﬁ[Xõ€ôX\€€äBàô]\õàŸ[ô\›[ìì◊’–SUàBààYà
\‘\\äH¬à€ìŸ º'‰·õ›][ô»»\\îŸ[
\\ì[ŸOI\‘\\äHãòYRYõZ[ù
Bàô]\õà\\îŸ[
ÀôX\€€ãòYRY
BàH[ŸHYà
ÿ[]OHù[
H¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'Ê™UëHS—H—Sì–“—Qàÿ[]\»ïSHäBà€ìŸ º'Ê™UëH—Sì–“—Qà	›Àúﬁ[Xõ€Hõ»ÿ[]H‹⁄][€àì’€X\ôYãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™Ÿ[õÿ⁄ŸYHãàêÿ[õõ›Ÿ[	›Àúﬁ[Xõ€HHÿ[]õ›€€õôX›Yà‹⁄][€à›[‹[àHãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà€ïÿ\›
º'Ê™ÿ[õõ›Ÿ[	›Àúﬁ[Xõ€HHôX€€õôX›ÿ[]HäBàô]\õàŸ[ô\›[ìì◊’–SUàH[ŸH¬àYà
õÿ⁄“YîŸ[[ëõY⁄
ÀôX\€€ã]ôUòYSŸ‘›‹ôKöŸ^Qõ‹äÀõZ[ùÀú‹⁄][€ãô[ùûU[YJJJH¬àô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàYà
X€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹ÀùûPX‹]Z\ôJÀõZ[ù
JH¬àÀ»çKéKéMç»8†%çÀQŸ[‹[Q›X\ô‹ò\ÇàYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[‹[Q›X\ôú⁄›[Ÿ–õÿ⁄ŸY
ÀõZ[ùôX\€€äJBà]ôUòYSŸ‘›‹ôKõŸ à]ôUòYSŸ‘›‹ôKöŸ^Qõ‹äÀõZ[ùÀú‹⁄][€ãô[ùûU[YJKÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀàî—S–ì–“—Q–SëPQW“Só‘ì—‘ëT‘»ù[ôX\€€èIôX\€€àãòY\ïY»HìQSQHäBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBà€ìŸ º'‰¨õ›][ô»»]ôTŸ[ãòYRYõZ[ù
Bàò[ô\›[H]ôTŸ[
ÀôX\€€ãÿ[]ÿ[]€€òYRY
BàÀ»çKçÀç»íVà]]À\ô\]Y]YH€àô]ûXXõHòZ[\ôH€»”’ô]ô\àŸ]»⁄[[ùHõ‹YàYà
ô\›[OHŸ[ô\›[ëêRSQ‘ëUñPPìJH¬àò[õ€îõ›]UÿZ]H\”õ€îõ›]TŸ[ÿZ]
 BàYà
õ€îõ›]UÿZ]
H¬à€ìŸ ∏£Ó;Ó#»Ÿ[ô]ûH›\ô\‹ŸYõ‹à	›Àúﬁ[Xõ€Nà^\›[ô»ö[ò[]K‹õ€Ÿà€‹öŸ\à›€ú»\»Z[ù»õ»[ô[ô‘Ÿ[]Y]YK€õ»õ‘⁄YÀàãòYRYõZ[ù
BàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘ëUñW‘’TëT‘—Q”ì”ó‘ì’UW’–RUãõZ[ùI›ÀõZ[ùùZŸJLä_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àõ€ŸïÿZ]Iÿ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[êò[[òŸTõ€ŸïÿZ]›]Kö\’ÿZ][ô ÀõZ[ù
_HX›]ôT⁄YœI’òYUô\öYöY\ãòX›]ôTŸ[⁄Y ÀõZ[ù
OÀùZŸJLäHŒàõõ€ôHüHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàH[ŸH¬à[ô[ô‘Ÿ[]Y]YKòY
ÀõZ[ùÀúﬁ[Xõ€ôX\€€äBà€ìŸ º'Â!Ÿ[]]À\]Y]YYõ‹àô]ûNà	›Àúﬁ[Xõ€HôX\€€èIôX\€€àãòYRYõZ[ù
Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãº'Â!—SëTUQUQQà	›Àúﬁ[Xõ€H8†%⁄[ô]ûH⁄[àÿ[]‘î»ôX€›ô\ú»äBàÀ»çKåçéH8†%Ÿ[ô\öX»ô]ûXXõHŸ[»\ôHì’Ÿ[Yö[ò[]BàÀ»€‹úù\[€ãàôKXúõÿYÿ\›õ›]H^]\›[€ãŸ\›€õÀ]ÿ[]\õ›]BàÀ»]\›ô]ô\à[Z]—S”ì◊–’TîëSï“S‘ì”—ó”ì’‘ëUíQQôXÿ]\ŸBàÀ»Hÿ›‹à[ù\úô]»]\»HZ\‹⁄[ôÀÿ€‹úù\[ôYŸ[ÇàÀ»\Yõ›]HòZ[\ô\»\ôH[ôY[ú⁄YH]ôTŸ[

H\¬àÀ»ì’UW—êRSQ”ì◊‘“Q”êUTëH[ô\ôHõ€ãXõÿ⁄⁄[ôÀ€õ€ãX€‹⁄[ôÀÇàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàî—S‘ëUñW—SîUQUQQ”ì◊—íSêSUW—êUSãàõZ[ùI›ÀõZ[ùùZŸJLä_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àõ◊ÿ€‹ŸO]ùYHô]ûO]ùYHõ‘⁄YœYò[ŸHÇà
Bà\[[ôRX[€€X›‹ãõXô[[ò î—S‘ëUñW—SîUQUQQ”ì◊—íSêSUW—êUSäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàÀ»çKååÕÕà8†%‹\ò]‹à‹X»][\»KKéà–RUSë◊–êSSê—W‘ì”—à\»BàÀ»õ€ŸàÿZ]ëUëTà[àX›]ôHŸ[à⁄⁄\[ô[ô‘Ÿ[]Y]YKòY
õ¬àÀ»—S‘ëUñW’ST‘êTñW””ìH[Z\‹⁄[€äH[ô⁄⁄\àÀ»—S”ì◊–’TîëSï“S‘ì”—ó”ì’‘ëUíQQ
õ»õ›]Hÿ\»X›X[BàÀ»][\Y
KàHò[[òŸTõ€Ÿî€\àZŸ\»›ô\àúõ€H\ôKÇàYà
ô\›[OHŸ[ô\›[ï–RUSë◊–êSSê—W‘ì”—äH¬à€ìŸ ∏£Ó;Ó#»—S–RUSë»êSSê—Hì”—éà	›Àúﬁ[Xõ€H8†%›€ôYûHò[[òŸTõ€Ÿî€\ãõ»ô\]Y]YK€õ»õÿ⁄⁄[ô»X\ŸKàãòYRYõZ[ù
BàBàô]\õàô\›[àBÇàHö[ò[H¬àÀ»[ÿ^\»ô[X\ŸHHŸ[›X\ô»Yù\àHŸ[›ô\öYûHYôXﬁX€Hô]\õúÀÇàô[X\ŸTŸ[ÿ⁄ ÀõZ[ù
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àúô[X\ŸJÀõZ[ù
BàBàBÇà äÇà
àçKéKçŒ8†%SQTë—SïQSQHTTàëPST”H[\ãÇà
à\úŸHH›ò]YﬁI‹»[ù[ôY^]ô\⁄€úõ€HHŸ[ôX\€€Çà
àXô[€»\\îŸ[ÿ[à€[\H^]öXŸH»HôX[\›X»ò[ôÇà
àô]\õú»
›‘›Y⁄›
Hô[]]ôH»[ùûTöXŸK‹à
ù[ù[
Bà
àõ‹à[ö€õ›€àXô[»
õ»€[\\YY
KÇà
ã¬ÇÇà äÇà
àçKéKçŒ8†%SQTë—SïQSQHTTàëPST”H[\ãÇà
à\»\»\\àòYHôX\€€àHô\õÀZ[ôõ‹õX][€à–‘êU“—ìU]à
à⁄›[ôH^€YYúõ€HX\õö[ô»€›[ùœ»
ù[ïòX⁄Ÿ\åÃ⁄[\ŸBà
à\»»]õ⁄Y€›[ù[ô»õ]^]»›ÿ\ôHõ€››ò\Z[\›€ôKäBà
ã¬à[ù\õò[ù[à\‘\\îÿ‹ò]⁄òYJôX\€€éà›ö[ôÀõ›à›XõJNàõ€€X[à¬àò[àHôX\€€ãù\\òÿ\ŸJ
BàYà
€›[ãõX]òXú õ›
HHKçJHô]\õàùYBàô]\õàãò€€ùZ[ú î–‘êU“äHãò€€ùZ[ú ëìU—VUäBàBÇàö]ò]Hù[àX^€€ôöY›\ôY\\ïòYT€€

Nà›XõH¬àô]\õàûH¬àò[»HŸô 
Bàò[YÿXﬁSX^HX^ŸäÀú€X[ù^T€€ÀõX^‹⁄][€î€€
KùZŸRYà»]ö\—ö[ö]J
H	âà]àåHŒàåMBàÀ»çKååŒÃ»8†%STTàSïíQT»]\›òZ[à]]ôK]ò[úŸô\à⁄^ôKõ›àÀ»YÿXﬁHZX‹õÀ\õÿôH⁄^ôKà€X\ù⁄^ô\àÿ[à€€\]HôX[\›X»\\à⁄^ô\ÀàÀ»ù]\»ö[ò[^X›]‹à€[\\ŸY»‹ù\⁄]ô\ûHõ‹õX[\\à[ôBàÀ»òX⁄»»X^‹⁄][€î€€
Yò][åMH””
Kà]XZŸ\»\\àì⁄[\X›àÀ»€»€X[»XX⁄]ôH⁄^ö[ôÀà\ŸHL	HŸà€€ôöY›\ôY\\àò[ö‹õ€\¬àÀ»H[ö]ô\úÿ[\\ã[X\õö[ô»ÿ\õ›[ôY»Hÿ[ôHà””ŸZ[[ôÀÇàÀ¬àÀ»çKåçåç»8†%–SU””T’SëSë»ëQQêP“Àà‹\ò]‹éàùÿ[]ò[[òŸBàÀ»\€ù‹õ›⁄[ô»]ù\›‹[ú»[‹ôH‹⁄][€ú»ãàõ€›ÿ]\ŸNà⁄^ö[ô»ôXYàÀ»H’UP»\\î⁄[][]Yò[[òŸHò[YK€»ôX[^ôY⁄[ú»ô]ô\àõ€YàÀ»òX⁄»[ù»‹⁄][€à⁄^ö[ôÀàõ›»ŸH][\HûH€€\›[ô‹õ››òX›‹ä
BàÀ»⁄X⁄ô]\õú»
ò\ŸPò[ö‹õ€
»ôX[^ôY€X[îõ
Kÿò\ŸPò[ö‹õ€€[\YàÀ»»ÃKåååKà\»\\àìXÿ›[][]\ÀHŸZ[[ô»ö\Ÿ\»8†%€¬àÀ»ìQP“T€€\›[ô[ô»ù[õô\ú»
⁄X⁄]ôHö[ùY
Õç»””]éIH‘äBàÀ»ÿ[àX›X[H⁄^ôH\[ù»H€€\›[ô[ô»›\ùôH[ú›XYŸàôZ[ô¬àÀ»€[\Y]ãå””õ‹ô]ô\ãÇàò[ò\ŸPÿ\HX^ŸäYÿXﬁSX^
Àú\\î⁄[][]Yò[[òŸH
àåL
Kò€Ÿ\òŸR[äYÿXﬁSX^ãå
JBàò[‹õ››H€€\›[ô‹õ››òX›‹ä
Bà
ò\ŸPÿ\
à‹õ››
Kò€Ÿ\òŸR[äò\ŸPÿ\å
BàHÿ]⁄
Œàõ›ÿXõJH»KåBàBÇà äÇà
àçKåçåç»8†%€€\›[ôY‹õ››ÿ[]ôYYòX⁄»õ‹à⁄^ö[ôÀÇà
àô]\õú»
\\î⁄[][]Yò[[òŸH
»ôX[^ôY€X[îõ
H»\\î⁄[][]Yò[[òŸBà
à€[\Y»ÃKåååKàòZ[[‹[à
ô]\õú»Kå
H€à[ûHôXYòZ[\ôH€¬à
à⁄^ö[ô»ô]ô\àúôXZ‹ÀÇà
ã¬àö]ò]Hù[à€€\›[ô‹õ››òX›‹ä
Nà›XõH¬àô]\õàûH¬àò[»HŸô 
Bàò[ò\ŸHHÀú\\î⁄[][]Yò[[òŸKùZŸRYà»]ö\—ö[ö]J
H	âà]àåHŒàô]\õàKåàò[ôX[^ôYHûH¬àòYR\›‹ûT›‹ôKôŸ]€X[î›]‘€ò\⁄›LM 
Kù›[õ€€àHÿ]⁄
Œàõ›ÿXõJH»åBàò[YôôX›]ôHH
ò\ŸH
»ôX[^ôY
Kò€Ÿ\òŸP]X\›
ò\ŸJBà
YôôX›]ôH»ò\ŸJKò€Ÿ\òŸR[äKååå
BàHÿ]⁄
Œàõ›ÿXõJH»KåBàBÇàö]ò]Hù[àZ[ê€€ôöY›\ôY\\ïòYT€€

Nà›XõH¬àÀ»çKåççMMH8†%\\à]\›õ›‹ôX]HŸ[ùÀ]€‹ù‹⁄][€úÀà€X[ù^T€€àÀ»ô[XZ[ú»[à‹ô[ò\ûH\ôŸ]»H[ô\[ô[ù^X›]XõHõ€‹à\»åBàÀ»””õ›[ôY[ôÿ\⁄Xÿ\Yà›ò]YﬁHù]X^H⁄\HHòYH›€ãàÀ»ù]ÿ[õõ›⁄[[ùH\õà[à^X›]XõHù^H[ù»\›Çàò[ù[ù[YSZ[ö[][MçLLHHûH»Ÿô 
KõZ[ì]ôPù^T€€Hÿ]⁄
Œàõ›ÿXõJH»åHBàò[õ›[ôYZ[ö[][MçLLHH\\îôUX⁄Ÿ]⁄^ôQõ€‹ççLLKòõ›[ôYZ[ö[][Jù[ù[YSZ[ö[][MçLLJBàô]\õàõ›[ôYZ[ö[][MçLLBàBÇàö]ò]Hù[à€[\\\ïòYT€€
ô\]Y\›Yà›XõKZ[ùà›ö[ô»Hàãﬁ[Xõ€à›ö[ô»Hàã€›\òŸNà›ö[ô»Hú\\àãX^›ô\úöYT€€à›XõO»Hù[
Nà›XõH¬àYà
\ô\]Y\›Yö\—ö[ö]J
Hô\]Y\›YHå
Hô]\õàåàò[Z[î€€HZ[ê€€ôöY›\ôY\\ïòYT€€

Bàò[[ôPÿ\H
X^›ô\úöYT€€ÀùZŸRYà»]ö\—ö[ö]J
H	âà]àåHŒàX^€€ôöY›\ôY\\ïòYT€€

JKò€Ÿ\òŸP]X\›
å
Bàò[[ôMçLLHòYRY[ù]SX[òYŸ\ãôŸ]
Z[ù
OÀô^X›][€ì[ôOÀùZŸRYà»]ö\”õ›õ[ö 
HHŒà€›\òŸBàò[ÿ\⁄çLLHûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î\\êÿ\][]]‹ö]MçMÕÀòÿ\⁄€€

HHÿ]⁄
Œàõ›ÿXõJH»åBàò[ô\€€ôYçLLH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ì‹ô\î⁄^ôTô\€€ô\ççKúô\€€ôJàô\]Y\›Y€€Hô\]Y\›Y[ôSò[YHH[ôMçLLÿ[]€€Hÿ\⁄çLL\\ì[ŸHHùYKà[ôTö\⁄–ÿ\€€H[ôPÿ\[ôSZ[ë^X›]XõT€€HZ[î€€à
BàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘“VëW––Sì”íP–S‘ëT””ëTóÕçLLãõZ[ùI€Z[ùùZŸJL
_Hﬁ[Xõ€Iﬁ[Xõ€€›\òŸOI€›\òŸH	‹ô\€€ôYçLLùòXŸJ
_HäBà\[[ôRX[€€X›‹ãõXô[[ò îTTó‘“VëW––Sì”íP–S‘ëT””ëTóÕçLLäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàô\€€ôYçLLôö[ò[⁄^ôT€€àBÇÇàù[à\\îŸ[
Œà⁄Ÿ[î›]KôX\€€éà›ö[ôÀY[ù]NàòYRY[ù]O»Hù[
NàŸ[ô\›[¬àò[òYRYHY[ù]HŒàòYRY[ù]SX[òYŸ\ãôŸ]‹ê‹ôX]JÀõZ[ùÀúﬁ[Xõ€Àú€›\òŸJBàù[àôX€€ò⁄[Pÿ[õ€öXÿ[€‹ŸYçLJ
Nàõ€€X[à¬àYà
T\\ï\õZ[ò[õ⁄ôX›[€ê€€ùô\ôŸ[òŸMçLKòÿ[õ€öXÿ[€‹ŸYõ–X›]ôJÀõZ[ù
JHô]\õàò[ŸBà\\ï\õZ[ò[õ⁄ôX›[€ê€€ùô\ôŸ[òŸMçLKò€€ùô\ôŸJÀõZ[ùÀúﬁ[Xõ€ê–Sì”íP–S–SëPQW–”‘—QÕçLNâôX\€€àã
BàÀú‹⁄][€àH‹⁄][€ä
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùê–Sì”íP–S–SëPQW–”‘—QÕçLHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùê–Sì”íP–S–SëPQW–”‘—QÕçLHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àôõ‹òŸTô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»ô[X\ŸT\\îŸ[ÿ⁄ ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S––Sì”íP–S–”‘—Q‘ëP””ê“SQÕçLHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàùYBàBàYà
ôX€€ò⁄[Pÿ[õ€öXÿ[€‹ŸYçLJ
JHô]\õàŸ[ô\›[êSëPQW–”‘—QàÀ»çKåçç8†%—SZ\úõ‹à[›ôY»€€ôö\õYY\\àö[ô[›ÀÇàÀ»»õ›]]]Hÿ[õ€öXÿ[YôXﬁX€H]Ÿ[X][\[YH⁄]ô\õ¬àÀ»õÿŸYYÀÿ€‹›»]ÿ\»H\ôX›€›\òŸHŸà—S[ùò\öX[ùö[€][€úÀÇàÀ»çKåççL»0©‘VUíSêSUH8†%⁄[ôŸY»ò\ò€»HX[]àÀ»[àUW—UëTë—T◊—îì”W––Sì”íP–Sÿ[àôXö[ôYù\à€‹Z[ô»⁄]àÀ»›]ù]]H
ŸYH[ôHåNMå
KÇàò\à‹»HÀú‹⁄][€Çàò[öXŸHHŸ]X›X[öXŸJ BàYà
\‹Àö\”‹[äH¬à\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹ŸY
îTTàãÀõZ[ùÀúﬁ[Xõ€îTTó‘—S”ì’”‘SéâôX\€€àäBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàYà
öXŸHOHå
H¬à\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö—òZ[Y
îTTàãÀõZ[ùÀúﬁ[Xõ€îTTó‘—S”ì◊‘íP—NâôX\€€àäBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàÀ»çKåçéLå8†%H^]úòZ[â‹»€õH›[\⁄]Hÿ\»[úôXX⁄XõHõ‹ÇàÀ»\\à‹⁄][€úÀ€»ôX€‹ô›]€€YHYõ›[ô»[ô[ô»»òZ[à€ÇàÀ»[ô]ô\ûH[ôH^]XY›^YY]òZ[ôYLõ‹ô]ô\ãà›[\\ôKàÀ»€àH\\à\õZ[ò[]ôYõ‹ôHôX€‹ôòYI‹»ò[õ›]€€ú›[Y\»]Çà›[\[öYöYY^]õ‹ê€‹ŸMéLå
ÀôX\€€äBàÀ»çKéKåMÃ
‹X»][HäH8†%”‘—HQST’Sê÷KàYà\»Z[ù[ôXYH\»H]ôBàÀ»€‹ŸH›[\Hô]ö[›\»\\îŸ[[ôXYHö[ò[^ôY]à›\ô\‹»H\Xÿ]BàÀ»—Sà»ì’õ›\õò[òZ[ã‹àôK[ÿÿ›\HH€›àö^\»Hÿ[YK[Z[ùàÀ»][K\Ÿ[›‹õH
ú€ûH»–U]ú‹
HH‹\ò]‹àÿúŸ\ùôYÇàù[à¬àò[^\›[ô–€‹ŸRYH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî‹⁄][€ê€‹ŸSYŸ\ãò€‹ŸRYŸäÀõZ[ù
BàYà
^\›[ô–€‹ŸRYOHù[
H¬à\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹ŸY
îTTàãÀõZ[ùÀúﬁ[Xõ€ìQ—Tó–SëPQW–”‘—QâôX\€€àã^\›[ô–€‹ŸRY
BàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S—TP–UW‘’TëT‘—QãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H‹öY⁄[ò[€‹ŸRYI^\›[ô–€‹ŸRYôX\€€èIôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàBàÀ»çKåççMH0©‘—S—”‘ó”RQ‘êUS”à8†%ô\Ÿ\ùôU\õZ[ò[Ÿ[–T»ëQì‘ëBàÀ»H\\àŸ[ÿ⁄»
⁄X⁄\»HYÿXﬁH[ãYõY⁄›X\ô
H€»[ûBàÀ»\Xÿ]Kÿ€€ò›\úô[ù\õZ[ò[—Sõ‹àH–SQH‹⁄][€íY\¬àÀ»\⁄Xÿ[H[\‹‹⁄XõH\›\»[ôKà‹⁄][€íY\»X[ô]‹ûN¬àÀ»õ[öÀ›[ö€õ›€à‹⁄][€ú»òZ[X€‹ŸYà÷éMÃòﬁëKÃíìé]BàÀ»ô\X]YT—S]\õà\»õ›»[úôXX⁄XõH]H–T»€‹ãÇàÀ»çKåççåÕH0©Õà–Sì”íP–S‘—S””“’T–ñW‘‘“US”ó“Q
‹\ò]‹ÇàÀ»ôXàåçàõ€ã[ôY€›XXõH\ôX›]ôNàúô\€€ôHH‹[ÇàÀ»‹⁄][€à\⁄[ô»ÿ[õ€öXÿ[‹⁄][€íYö\ú›ããàò[òX⁄¬àÀ»€⁄›\X^HôH
[ŸKÿ[õ€öXÿ[Z[ù
Hù]€õH⁄[à]àÀ»ô\€€ô\»[ö\]Y[KàäKàÀú‹⁄][€ãú‹⁄][€íY\»BàÀ»ïVKX€€[Z]Y[ò⁄‹à]\ú⁄\›Y‹⁄][€îôY⁄\›ûBàÀ»]X⁄\»]‘Sà8†%ô]ô\àò[òX⁄»»Z[ù\ÿÿ[à⁄[à]àÀ»\»ô\Ÿ[ù
»ô\€€òXõKÇàò[ô\]Y\›YYõ‹îŸ[çåÕHHÀú‹⁄][€ãú‹⁄][€íYùö[J
Bàò[[‹[î\\úÕçåÕHHûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[‹⁄][€ê]]‹ö]MçKõ‹[î‹⁄][€ú 
Bàôö[\à»]õ[ŸHOHú\\ààBàHÿ]⁄
Œàõ›ÿXõJH»[\S\›

HBàò[ÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLàHYà
ô\]Y\›YYõ‹îŸ[çåÕKö\”õ›õ[ö 
JH¬àò[ûTYçåÕHH[‹[î\\úÕçåÕKôö\ú›‹ìù[»]ú‹⁄][€íYOHô\]Y\›YYõ‹îŸ[çåÕHBàYà
ûTYçåÕHOHù[
H¬àûH»\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S‘ëT””ëQ–ñW‘‘“US”íQÕçåÕHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûTYçåÕBàH[ŸH¬àÀ»ò[òX⁄Œà
[ŸKÿ[õ€öXÿ[Z[ù
H8†%ù]”ìH⁄[à[ö\]YKÇàò[ûTÿ[YSZ[ùçåÕHH[‹[î\\úÕçåÕKôö[\à»]õZ[ùOHÀõZ[ùBà⁄[à
ûTÿ[YSZ[ùçåÕKú⁄^ôJH¬àOàù[àHOà¬àûH¬à\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S‘ëT””ëQ–ñW”RSï—êSêP“◊ÕçåÕHäBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàîTTó‘—S‘ëT””ëQ–ñW”RSï—êSêP“◊ÕçåÕHãàúô\]Y\›YYI‹ô\]Y\›YYõ‹îŸ[çåÕKùZŸJç
_Hô\€€ôYYIÿûTÿ[YSZ[ùçåÕVÃKú‹⁄][€íYùZŸJç
_Hà
¬àõZ[ùI›ÀõZ[ùùZŸJL
_HX›[€è][ö\]YW€Z[ùŸò[òX⁄◊€⁄»ãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûTÿ[YSZ[ùçåÕVÃBàBà[ŸHOà¬àûH¬à\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S–SPíQ’S’T◊”RSï—êSêP“◊‘ëQïT—QÕçåÕHäBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàîTTó‘—S–SPíQ’S’T◊”RSï—êSêP“◊‘ëQïT—QÕçåÕHãàúô\]Y\›YYI‹ô\]Y\›YYõ‹îŸ[çåÕKùZŸJç
_HZ[ùI›ÀõZ[ùùZŸJL
_Hà
¬àòÿ[ôY]P€›[ùIÿûTÿ[YSZ[ùçåÕKú⁄^ô_HX›[€è\ôYù\ŸWÿ[XöY›[›\◊Ÿ^]‹[ô[ô»ãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàù[àBàBàBàH[ŸH¬àÀ»YÿXﬁNàõ»‹⁄][€íY]X⁄Yàô]Z[à€Z[ù\ÿÿ[àù]€›[ùŸ\\ò][KÇàûH»\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S””“’T”RT‘“Së◊‘QÕçåÕHäHHÿ]⁄
Œàõ›ÿXõJHﬂBà[‹[î\\úÕçåÕKôö\ú›‹ìù[»]õZ[ùOHÀõZ[ùBàBàYà
ÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLàOHù[
H¬àYà
ôX€€ò⁄[Pÿ[õ€öXÿ[€‹ŸYçLJ
JHô]\õàŸ[ô\›[êSëPQW–”‘—QàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S––Sì”íP–S‘‘“US”ó”RT‘“Së◊ÕçNãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àX›[€è\ô]ûW€õ◊‹õ⁄ôX›[€ó€]]][€àäBà\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S––Sì”íP–S‘‘“US”ó”RT‘“Së◊ÕçNäBà€€⁄]⁄Y€ò[⁄Y]úôX€‹ôÿ]\ÿ[\‹›YMçå
úŸ[ÿ[õ€öXÿ[€⁄›\òZ[\ôHãÀú‹⁄][€ãùòY[ô”[ŸKõZ[ùI›ÀõZ[ùùZŸJL
_H‹⁄][€íYI›Àú‹⁄][€ãú‹⁄][€íYùZŸJN
_HäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö—òZ[Y
îTTàãÀõZ[ùÀúﬁ[Xõ€ê–Sì”íP–S‘‘“US”ó”RT‘“Së◊ÕçNâôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùê–Sì”íP–S‘‘“US”ó”RT‘“Së◊ÕçNäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùê–Sì”íP–S‘‘“US”ó”RT‘“Së◊ÕçNäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àôõ‹òŸTô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàò[\õZ[ò[YçMHHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú‹⁄][€íYàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMõ€ë[ùûJ\õZ[ò[YçMJHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]îŸ[]Põ›[ô\ûP€[\ççÀúﬁ[ò–]]‹ö]]]ôTò] à\õZ[ò[YçMKÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ‹öY⁄[ò[]Tò]Àÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãúô[XZ[ö[ô‘]Tò]Àà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKåççMç»8†%TTà›[ùYŸ\ú»\ŸHÿ[õ€öXÿ[]X[ù]Tÿÿ[K⁄X⁄\¬àÀ»[ô\[ô[ùŸà€ãX⁄Z[àZ[ùX⁄[X[Àà\⁄[ô»⁄Ÿ[ëX⁄[X[»\ôHõŸXŸYàÀ»^X›0ÂÃLåLà—Sõ›\õò[€‹úù\[€à⁄[à\\àÿÿ[OLLà[ôZ[ùY]Y]OLÇàò[\õZ[ò[X⁄[X[ÕçLàHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú]X[ù]Tÿÿ[Kò€Ÿ\òŸR[äN
Bàò[\õZ[ò[ô[XZ[ö[ô‘ò]ÕçLàHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLèÀúô[XZ[ö[ô‘]Tò]¬àÀùZŸRYà»]àò]òKõX]êöY“[ùYŸ\ãñëTì»BàŒàûH¬àò]òKõX]êöY—X⁄[X[ùò[YSŸä‹Àú]U⁄Ÿ[ãò€Ÿ\òŸP]X\›
å
JBàõ][\Jò]òKõX]êöY—X⁄[X[ïSãú› \õZ[ò[X⁄[X[ÕçLäJBàúŸ]ÿÿ[Jò]òKõX]îõ›[ô[ô”[ŸKíSó’T
Kù–öY“[ùYŸ\ä
BàHÿ]⁄
Œàõ›ÿXõJH»ò]òKõX]êöY“[ùYŸ\ãñëTì»Bàò[\õZ[ò[ô[XZ[ö[ô–€‹›çLàHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLèÀõ]»
]ô[ùûP€‹›€€H]ú€€€‹›ò\⁄\‘€€
Kò€Ÿ\òŸP]X\›
å
HBàÀùZŸRYà»]ö\—ö[ö]J
H	âà]àåHŒà‹Àò€‹›€€ò€Ÿ\òŸP]X\›
å
Bàò[ô\Ÿ\ùôTô\›[çMHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMàúô\Ÿ\ùôU\õZ[ò[Ÿ[
\õZ[ò[YçMKôX\€€äBàYà
ô\Ÿ\ùôTô\›[çMHOH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMîô\Ÿ\ùôTô\›[îëT—TïëQ
H¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàîTTó‘—S’TìRSêS‘ëT—TïëW‘ëRëP’QÕçMHãàõZ[ùI›ÀõZ[ùùZŸJL
_HYI›\õZ[ò[YçMKùZŸJLä_Hô\›[Iô\Ÿ\ùôTô\›[çMHôX\€€èIôX\€€àãà
Bà\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S’TìRSêS‘ëT—TïëW‘ëRëP’QÕçMHäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùïTìRSêS‘ëT—TïëW‘ëRëP’QÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùïTìRSêS‘ëT—TïëW‘ëRëP’QÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àôõ‹òŸTô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»ô[X\ŸT\\îŸ[ÿ⁄ ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàÀ»çKéKçÃNNàX‹]Z\ôH\\àŸ[ÿ⁄»»ô]ô[ù›XõKY^]òXŸKÇàÀ»Yà[õ›\àŸ[ô\]Y\›\»[ôXYH[ãYõY⁄õ‹à\»Z[ùôZôX›\»€ôKÇàYà
XX‹]Z\ôT\\îŸ[ÿ⁄ ÀõZ[ù
JH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãº'Â$àTTó—’PìW‘—S–ì–“—Qà	›Àúﬁ[Xõ€HôX\€€èIôX\€€à[ôXYHŸ[[ô»äBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMòXò[ô€ï\õZ[ò[Ÿ[
\õZ[ò[YçMKú\\ó€ÿ⁄◊€õ›ÿX‹]Z\ôYÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùîTTó”–“◊”ì’–P‘URTëQÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùîTTó”–“◊”ì’–P‘URTëQÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBà\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹⁄[ô îTTàãÀõZ[ùÀúﬁ[Xõ€ôX\€€äBàÀ»çKéKçÃåàûKŸö[ò[H[ú›\ô\»ÿ⁄»\»S–VT»ô[X\ŸY8†%]ô[à€à^Ÿ\[€ãÇàÀ»⁄]›]\ÀH‹ò\⁄ZY\Ÿ[X]ô\»Hÿ⁄»Ÿ]õ‹ô]ô\ãÿ]\⁄[ô¬àÀ»õ›\›‹€‹ŸP[‹⁄][€ú 
H»ŸYHSëPQW–”‘—Q[ô⁄⁄\H‹⁄][€ãÇàûH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S‘’TïãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€⁄]⁄Y€ò[⁄Y]úôX€‹ô\⁄‘›YŸJÀú‹⁄][€ãùòY[ô”[ŸKî—S–USTãâ›Àú‹⁄][€ãú‹⁄][€íYNâôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBÇàÀ»çKåççå8†%ÿ[õ€öXÿ[‹⁄][€à]]‹ö]H[ôXYH\‹ŸYHçMÃ^]àÀ»[Y⁄Xö[]H€€ùòX›Xõ›ôKàX[H]]XõH⁄Ÿ[î›]Hõ⁄ôX›[€àúõ€BàÀ»]]]‹ö]N»ô]ô\àô]»HŸ[ùZ[ôHÿ[õ€öXÿ[€‹ŸHôXÿ]\ŸHHõ›\õò[‹ÇàÀ»YÿXﬁHõ⁄ôX›[€à[ô^\»›[KÇàù[à¬àò[ÿ[õ€öXÿ[]U⁄Ÿ[ççåHûH¬àò]òKõX]êöY—X⁄[X[
ÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãúô[XZ[ö[ô‘]Tò] Bàõ[›ôT⁄[ùYù
ÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú]X[ù]Tÿÿ[Kò€Ÿ\òŸR[äN
JKù—›XõJ
BàHÿ]⁄
Œàõ›ÿXõJH»åBàò[ÿ[õ€öXÿ[€‹›çåH
ÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãô[ùûP€‹›€€Hÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú€€€‹›ò\⁄\‘€€
Kò€Ÿ\òŸP]X\›
å
Bàò[ÿ[õ€öXÿ[[ùûMçåHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãô[ùûTöXŸU\ŸùZŸRYà»]ö\—ö[ö]J
H	âà]àåHŒà‹Àô[ùûTöXŸBàYà
ÿ[õ€öXÿ[]U⁄Ÿ[ççåö\—ö[ö]J
H	âàÿ[õ€öXÿ[]U⁄Ÿ[ççåàå	âàÿ[õ€öXÿ[€‹›çåö\—ö[ö]J
H	âàÿ[õ€öXÿ[€‹›çåàå	âàÿ[õ€öXÿ[[ùûMçåö\—ö[ö]J
H	âàÿ[õ€öXÿ[[ùûMçåàå
H¬àò[õ⁄ôX›[€ëöYùçåH‹Àú‹⁄][€íYOHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú‹⁄][€íYà\‹ÀùòY[ô”[ŸKô\]X[ ÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ[ôKùYJHà€›[ãõX]òXú ‹Àú]U⁄Ÿ[àHÿ[õ€öXÿ[]U⁄Ÿ[ççå
HàX^ŸäYKNKÿ[õ€öXÿ[]U⁄Ÿ[ççå
àåJHà€›[ãõX]òXú ‹Àò€‹›€€Hÿ[õ€öXÿ[€‹›çå
HàX^ŸäYKNKÿ[õ€öXÿ[€‹›çå
àåJBàYà
õ⁄ôX›[€ëöYùçå
H¬à‹»H‹Àò€‹Jà]U⁄Ÿ[àHÿ[õ€öXÿ[]U⁄Ÿ[ççå[ùûTöXŸHHÿ[õ€öXÿ[[ùûMçåà[ùûU[YHHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ‹[ôY]\À€‹›€€Hÿ[õ€öXÿ[€‹›çåà[ùûTöXŸT€›\òŸHHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãô[ùûTöXŸT€›\òŸKà[ùûT€€Yô\‹»Hÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãô[ùûT€€Yô\‹Àà[ùûQ^Hÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãô[ùûQ^à\‘\\î‹⁄][€àHùYKòY[ô”[ŸHHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ[ôKà‹⁄][€íYHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú‹⁄][€íYà
BàÀú‹⁄][€àH‹¬àûH¬à\[[ôRX[€€X›‹ãõXô[[ò î—S––Sì”íP–S‘ì“ëP’S”ó“PSQÕçåäBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S––Sì”íP–S‘ì“ëP’S”ó“PSQÕçåãú‹⁄][€íYIÿÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú‹⁄][€íYHZ[ùI›ÀõZ[ùùZŸJL
_H[ôOIÿÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ[ô_H]OIÿ[õ€öXÿ[]U⁄Ÿ[ççå€‹›Iÿ[õ€öXÿ[€‹›çåX›[€èX€€ù[ùYWÿÿ[õ€öXÿ[ÿ€‹ŸHäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàBàH[ŸH¬àûH¬à\[[ôRX[€€X›‹ãõXô[[ò î—S–ì–“—Q––Sì”íP–S‘‘“US”ó”PSì‘ìQQÕçåäBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S–ì–“—Q––Sì”íP–S‘‘“US”ó”PSì‘ìQQÕçåãú‹⁄][€íYIÿÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãú‹⁄][€íYHZ[ùI›ÀõZ[ùùZŸJL
_H]OIÿ[õ€öXÿ[]U⁄Ÿ[ççå€‹›Iÿ[õ€öXÿ[€‹›çå[ùûOIÿ[õ€öXÿ[[ùûMçåX›[€è\ô]ûW€õ◊›\õZ[ò[ÿXò[ô€àäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMòXò[ô€ï\õZ[ò[Ÿ[
\õZ[ò[YçMKòÿ[õ€öXÿ[‹‹⁄][€ó€X[õ‹õYYÕçåäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö—òZ[Y
îTTàãÀõZ[ùÀúﬁ[Xõ€ê–Sì”íP–S‘‘“US”ó”PSì‘ìQQÕçåâôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùê–Sì”íP–S‘‘“US”ó”PSì‘ìQQÕçåäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùê–Sì”íP–S‘‘“US”ó”PSì‘ìQQÕçåäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àôõ‹òŸTô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»ô[X\ŸT\\îŸ[ÿ⁄ ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàBÇàÀ»íVà\ŸHŸ\ôHZ\‹⁄[ô»[ôÿ]\ŸY[›\à€€\[HòZ[\ôBàÀ»çKéKéŒà›X\ôYÿZ[ú›[úŸ][ùûU[YH
€›[XZŸH€[YHHõ›ÀY\ÿ⁄HMà\ú KÇàò[[ùûU[YTÿYôHHYà
‹Àô[ùûU[YHàWÃÃÃÃ
H‹Àô[ùûU[YH[ŸHﬁ\›[Kò›\úô[ù[YSZ[\ 
Bàò[€[YSZ[ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH[ùûU[YTÿYôJH»åÃåàò[€Z[ù]\»H€[YSZ[ú¬ààÀ»çKéKçŒ8†%SQTë—SïQSQHTTàëPST”KÇàÀ»‹\ò]‹àõ‹ô[ú⁄X‹Œà’íP’‘”ÀLL^]»õ€⁄ŸY]NM	N»
ÃÃ	HàÀ»^]»õ€⁄ŸY]
ŒåÕ	Kà\\à⁄[Hÿ\»\⁄[ô»Ÿ]X›X[öXŸJ BàÀ»ô\òò][H⁄]õ»€[\
»€õHçx†$Õ	H€\YŸKà]ôH^X›][€ÇàÀ»–Sìì’ô\õŸXŸH‹ŸHô]\õú»8†%ù\]\à€\YŸH€àY[YH\›àÀ»\»8†$ÃçIKà€»\\à\»ôY[àRSë»»HRH^Y\ú»Xõ›]YŸKàÀ»Ÿ[ô[ô»Hõ›[ù»UëH⁄]Hò[ù\ﬁH
ÃÕ…K›òYHö[‹ãÇàÀ¬àÀ»⁄^€‹úôX›[€ú»\YYô[›ŒÇàÀ»
JHôX[\›X»€\YŸH›\ùôHò\ŸY€à\]ZY]HY\ÇàÀ»
äH^]\öXŸH€[\\õ›[ôH›ò]YﬁI‹»›]Yô\⁄€àÀ»€»’íP’‘”ÀLLõ€⁄‹»[àÀLL	KLMIWKõ›NM	BàÀ»[ôêTQ’R—W‘ì—íUÃÃõ€⁄‹»[à ÃçIK
ÃÃ	WKõ›
ŒåÕ	BàÀ»
 H\]ZY]KX]ÿ\ôHô]\õàÿ\
⁄[ô€HòYHõõ›[ôYûBàÀ»\H»€‹›€€
àçX8†%[›Hÿ[â›^òX›
Œ	Húõ€HBàÀ»	»€€⁄]H	H‹⁄][€àôXÿ]\ŸH^]\]ZY]H\¬àÀ»ÿ\YûHHÿ[YH€€
BàÀ»
JH–‘êU“—ìU^]»õYŸŸY€»ù[ïòX⁄Ÿ\åÃÿ[à^€YBàÀ»[Húõ€HX\õö[ô»€›[ù»
ô\õÀZ[ôõ‹õX][€àòY\»Ÿ\ôBàÀ»[ôõ][ô»Hõ€››ò\Z[\›€ôJKÇàÀ»çKéKåMÕà8†%ëPST’P»TTà”TQ—H
^]⁄YJKàŸYH[ùûK\⁄YBàÀ»õ›NàH€N	KÃL	KÕâH^]^ÿ\»H€Z[ò[ùö]ô\àŸàBàÀ»Y[ùXÿ[“U”“Só‘’‘”‘‘»‹‹»€\›\ú»[ô[ù€Hÿ]\›õ‹BàÀ»ö[ù»[àHõ›\õò[àÿ\Y]H‹\ò]‹â‹»ôX[IH€‹ú›Xÿ\ŸBàÀ»
\Xÿ[åIJKàY\à⁄\Hô\Ÿ\ùôYà]ôH^X›][€à[ù›X⁄Y8†%àÀ»ôX[ù\]\à€\YŸHT»HôX[€‹›\ôKÇàò[⁄[][]Y€\YŸT›H⁄[à¬àÀõ\›\]ZY]U\ŸWÃåOàKåÀ»\›[\ôù[àõ€ô[ô»›\ùôH
ÿ\»N
BàÀõ\›\]ZY]U\ŸåÃåOàÀåÀ»€X[‹›Y‹òY€€
ÿ\»L
BàÀõ\›\]ZY]U\ŸLÃåOàãåÀ»
ÿ\»äBàÀõ\›\]ZY]U\ŸçLÃåOàKåÀ»
ÿ\» Bà[ŸHOàçHÀ»
ÿ\»KçJBàBàò[€\YŸS][\Y\àHKåH
⁄[][]Y€\YŸT›»Lå
Bàò\àYôôX›]ôTöXŸHHöXŸH
à€\YŸS][\Y\ÇÇàÀ»çKåççÃÕ8†%H›‹ôX\€€à\ÿ‹öXô\»HX⁄\⁄[€ãõ›[à^X›]XõHöXŸKÇàÀ»ô]\ôYTTó—VU‘íP—W–”STQ»TTó—VU–êSë”ì◊—SïñNà^H€›[àÀ»\õàHô\öYöYYNNIHÿ\[ù»HòXúöXÿ]YLMIHö[⁄\€€ö[ô»X\õö[ôÀÇàÀ»H[ùûKXò\⁄\»[ô][›H›X\ô»Xõ›ôHô[XZ[àX[ô]‹ûKà€õHBàÀ»ÿúŸ\ùôYöXŸH\»H^\›[ô»^X⁄]⁄[][][€à€‹›»]\õZ[ôH[€ô^KÇÇàÀ»çKååŒé8†%^X›]XõK[]ôH\\àúöX›[€ãÇàÀ»ô\‹ùà]ôHY[YUòY\àù^\À‹Ÿ[»õ›Àù]\\àYŸHŸ\€â›ò[úŸô\é¬àÀ»õ›ù[ú»Xõ›]]ô[ã‹€Y⁄H[ô\à]ôKàõ€›ÿ]\ŸNà\õZ[ò[\\àŸ[¬àÀ»€õH⁄\ôŸYçIK⁄[H]ôHY[YHõ›[ô]ö\òY»\»8¢bKçâH\»õ›]K¬àÀ»\]ZY]H€\YŸKàòZ[à\\à€à^X›]XõHô]YŸKõ›‹[Z\›X¬àÀ»‹õ‹‹»\\àYŸK€»ôXY[ô\‹À€[ôHY[[‹ûHõ€[›H€õHòY\»]ÿ[ÇàÀ»›\ùö]ôHôX[ôY\À‹€\Çàò[^X›Yõ›]T€\›HûH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë^X›][€ê€‹›ôYX›‹êRKô^X›Y^òT€\›
Àõ\›\]ZY]U\Ÿ
BàHÿ]⁄
Œàõ›ÿXõJH»åBàò[⁄[][]YôYT›H
Kçà
»^X›Yõ›]T€\›ò€Ÿ\òŸR[äåå
JKò€Ÿ\òŸR[äKçãKçäBÇàò[öXŸQ\ö]ôYõ›H›
‹Àô[ùûTöXŸKYôôX›]ôTöXŸJKò€Ÿ\òŸR[äLLåLå
Bàò[ò]’ò[YHH\õZ[ò[ô[XZ[ö[ô–€‹›çLà
à
Kå
»öXŸQ\ö]ôYõ›»Lå
H
à
KåH⁄[][]YôYT›»Lå
BàÀ»
 H€‹›Xò\⁄\»\\àõÿŸYY»8†%\\à\»õ»ôX[⁄Ÿ[àò[[òŸKà¬àÀ»ì’õ€⁄»õÿŸYY»úõ€H]U⁄Ÿ[à
àöXŸN»H›[H]H‹à€›\òŸKXò\⁄\¬àÀ»Z\€X]⁄‹ôX]\»[\‹‹⁄XõHZ[[€ãT””õ›‹ÀàŸH[ôXYH]ôHBàÀ»€‹úôX›€€\\òXõH[ùûKŸ^]öXŸHYù\àŸ]X›X[öXŸJ
HôXò\ŸH[ôàÀ»‹[€ò[ôX\€€à€[\€»õÿŸYY»\ôH€‹›ò\⁄\»0Â»öXŸHô]\õãÇàò[ÿ\Yò[YHHù[à¬àò[€€öXŸU\ŸHÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸBàYà
Àõ\›\]ZY]U\Ÿàå	âà€€öXŸU\Ÿàå
H¬àò[X^ò[YT€€H
Àõ\›\]ZY]U\Ÿ
àçJH»€€öXŸU\ŸàYà
ò]’ò[YHàX^ò[YT€€
H¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàîTTó‘ì”TURQUW––TQãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€Hò]’ò[YT€€I»âKçôàãôõ‹õX]
ò]’ò[YJ_Hÿ\€€I»âKçôàãôõ‹õX]
X^ò[YT€€
_H\U\ŸI»âKåàãôõ‹õX]
Àõ\›\]ZY]U\Ÿ
_Hãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàX^ò[YT€€àH[ŸHò]’ò[YBàH[ŸHò]’ò[YBàBàò[ò[YHHÿ\Yò[YKò€Ÿ\òŸP]X\›
å
Bàò[‹õ‹‹”õ—úöX›[€ïò[YHH
\õZ[ò[ô[XZ[ö[ô–€‹›çLà
à
Kå
»öXŸQ\ö]ôYõ›»Lå
JKò€Ÿ\òŸP]X\›
å
Bàò[⁄[][]YôYT€€H
‹õ‹‹”õ—úöX›[€ïò[YHHò[YJKò€Ÿ\òŸP]X\›
å
Bàò[õHò[YHH\õZ[ò[ô[XZ[ö[ô–€‹›çLÇàò[õH›
\õZ[ò[ô[XZ[ö[ô–€‹›çLãò[YJBàÀ»çKåççH0©Ã»—SUH”’Tê—H–“Àà‹\ò]‹à”SõÃ€à€ò\⁄›⁄›ŸYàÀ»ù^OLLçMHŸ[LNKçLÕH8†%û›ô\úŸ[àõ€›ÿ]\ŸNàõ›\õò[òYHõ›¬àÀ»ÿ\»ôXY[ô»‹Àú]U⁄Ÿ[à⁄X⁄YöYùY
[X\À[Y\ôŸKŸ›XõKX€›[ù
KÇàÀ»õ‹òŸHHõ›\õò[Y
»Z\úõ‹ôY]H»›öX›HòX⁄»Hÿ[õ€öXÿ[àÀ»ô[XZ[ö[ô»]Húõ€Hÿ[õ€öXÿ[‹⁄][€ê]]‹ö]MçKà\»\»H“Së”BàÀ»]]‹ö]]]ôHŸ[]Hõ‹àõ›HòYHõ›»[ôHÿ[õ€öXÿ[àÀ»Z\úõ‹ãàò[»òX⁄»»‹Àú]U⁄Ÿ[à€õH⁄[àÿ[õ€öXÿ[\»[ú‹[]YÇàÀ»çKåççLà8†%\\îŸ[\»H\õZ[ò[TNàŸ[^X›HBàÀ»ÿ[õ€öXÿ[ô[XZ[ö[ô»›à\ùX[T\»\ŸH\õZ[ò[Yò[ŸHôYXŸ\úÀÇàò[€€]U⁄Ÿ[ççHHûH¬à\õZ[ò[ô[XZ[ö[ô‘ò]ÕçLãù–öY—X⁄[X[

Bàô]öYJò]òKõX]êöY—X⁄[X[ïSãú› \õZ[ò[X⁄[X[ÕçLäJBàù—›XõJ
BàHÿ]⁄
Œàõ›ÿXõJH»‹Àú]U⁄Ÿ[ãò€Ÿ\òŸP]X\›
å
HBÇàò[òYHHòYJà⁄YHHî—Sãà[ŸHHú\\àãàÀ»çKéKåLN»8†%‹\ò]‹àöXYŸHùYŒà\»ÿ\»€€H‹Àò€‹›€€àÀ»⁄X⁄ôX€‹ô»H”‘’êT“T»
⁄][‹]\»›[[YY[äH\¬àÀ»H—Sõ›…‹»€€öY[à]ô\ûH›\à—S€€ú›ùX›‹à[ÇàÀ»\»ö[H8†%]ôHŸ[À\ùX[Ÿ[ÀõŸö][ÿ⁄Àÿ\][àÀ»ôX€›ô\ûH8†%\Ÿ\»‘ì‘‘»ì–—QQÀàHZ\€X]⁄XYH‹\ò]‹ÇàÀ»ŸYHïVH€€LçéX8°§à—S€€LKåÕÃõKLKåLMX
Hù^H]àÀ»‹]\Y»KåÕÃ€‹›ò\⁄\»[à[\Y
H[ôôXY]\¬àÀ»éN	H‹‹»[àŸX€€ô»õ‹àõ»ôX\€€àãà][€»ôY€‹›ò\⁄\¬àÀ»[ù»ÿ[õ€öXÿ[X\õö[ôÀô^]€€HòYKú€€8†%€‹úù\X\õö[ôÀÇà€€Hò[YKàöXŸHHöXŸKà»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
KàôX\€€àHôX\€€ãàõ€€Hõàõ›HõàÀ»çKéKåLåH8†%€‹ŸHõ›‹»]\›ô\Ÿ\ùôH[ùûHÿ€‹ôHõ‹ÇàÀ»‹⁄[ô‘]\õìY[[‹ûKàYò][XYHõ‹õX[⁄]€⁄[ã‘]X[]BàÀ»€‹Ÿ\»€⁄»ZŸHÃLLX]XùX⁄Ÿ]ÿ[\\ÀÇàÿ€‹ôHH‹Àô[ùûTÿ€‹ôKàôYT€€H⁄[][]YôYT€€àô]õ€€HõàòY[ô”[ŸHH‹ÀùòY[ô”[ŸKàòY[ô”[ŸQ[[⁄öHH‹ÀùòY[ô”[ŸQ[[⁄öKàÀ»çKåçåÕåH8†%TTàïSQVUUHëT—TïêUS”ãÇàÀ»‹\ò]‹àéVYñ€ò\⁄›àïVH]OMÃŒK—S]OMéMH€àBàÀ»“U”“Só‘’‘”‘‘»ù[Y^]àH—SòYHõ›»ÿ\»ô]ô\ÇàÀ»‹[]Y⁄]€€]U⁄Ÿ[à»[ùûT]U⁄Ÿ[à»[ùûP€‹›€€¬àÀ»[ùûTöXŸT€ò\⁄›€»›€ú›ôX[H\‹^H
»X\õö[ô»]àÀ»òX⁄ÀX€€\]Y]Húõ€H€€»öXŸX⁄X⁄õŸXŸ\»BàÀ»]\ö\›X»€XŸKõ›HùYH‹⁄][€à⁄^ôKàù[Y^]àÀ»\\àŸ[»]\›õ›\õò[Hù[‹⁄][€à]H[ô€‹›àÀ»ò\⁄\»€»ÿ[õ€öXÿ[X\õö[ô–€€ùòX›åÕà\ö]H⁄X⁄‹»\‹¬àÀ»[ôHõ›[ô]ö\]H⁄›‹»€‹úôX›H[àH[KÇàÀ»çKåççH0©Ã»8†%€€]U⁄Ÿ[àÿ⁄ŸY»ÿ[õ€öXÿ[ô[XZ[ö[ôÀÇà[ùûT]U⁄Ÿ[àH€€]U⁄Ÿ[ççKà€€]U⁄Ÿ[àH€€]U⁄Ÿ[ççKà[ùûP€‹›€€H\õZ[ò[ô[XZ[ö[ô–€‹›çLãà[ùûTöXŸT€ò\⁄›H‹Àô[ùûTöXŸKà[ùûQX⁄[X[»H\õZ[ò[X⁄[X[ÕçLãà‹⁄][€íYH\õZ[ò[YçMKà[ùûTò]‘]HH\õZ[ò[ô[XZ[ö[ô‘ò]ÕçLãàÿ[õ€öXÿ[€€ú›[YYò]»Hò]òKõX]êöY“[ùYŸ\ãñëTìÀàô[XZ[ö[ô‘ò]‘]HH\õZ[ò[ô[XZ[ö[ô‘ò]ÕçLãà⁄Ÿ[ëX⁄[X[»H\õZ[ò[X⁄[X[ÕçLãà
BàÀ»çKåççLå8†%õ»Rx°§úò]»õ›\õò[ÿ[›[][€ãàH€‹ŸHôXŸZ\›\Y\»€€ú›[YYò]ÀÇàÀ»çKéKåLLH8†%TTà—SêT’UÇàÀ»€ò\⁄›ôYõ‹ôH€‹⁄[ô»HôX[‹⁄][€ã[à[›ôHÿ[õ€öXÿ[õ›\õò[àÀ»
»ŸX›\ö]H
»S€X\õö[ô»ò[õ›]Ÿôà\»Ÿ[ôXYàçKéKåLLàÀ»õ›ôY\\îŸ[ÿ\»›[[ô»ô]ŸY[àTTó‘—S‘’Tï[ôàÀ»TTó‘—S“ì’TìêS—”ëKKôKà[ú⁄YHﬁ[ò⁄õ€õ›\»ôX€‹ôòYJ
K¬àÀ»òYR\›‹ûT›‹ôK‘ŸX›\ö]Q›X\ô€‹öÀà€‹⁄[ô»H‹⁄][€à[ôàÀ»ô[X\⁄[ô»ÿ⁄‹»]\›õ›ÿZ]õ‹à]ò[õ›]Çàò\àòYT€ò\HYà
òYKõZ[ùö\–õ[ö 
JHòYKò€‹JZ[ùHÀõZ[ù
H[ŸHòYBàò[”X\õö[ô‘€ò\HûH¬àÀò€‹Jà‹⁄][€àH‹Àò€‹J
KàòY\»H]]XõS\›Ÿä
KàôXŸ[ù[ùûU[Y\»HÀúôXŸ[ù[ùûU[Y\Àù”]]XõS\›

Kà
BàHÿ]⁄
Œàõ›ÿXõJH»»BàÀ»çKåççÕ8†%õ›\õò[€X\õö[ô»õ⁄ôX›[€à\»]Y]YY€õHYù\àBàÀ»ÿ[õ€öXÿ[\\à€‹ŸHôYXŸ\àô[›»€€[Z]ÀàH›XÿŸ\‹Ÿù[—Sõ›¬àÀ»⁄]›]ÿ[õ€öXÿ[ÿ\⁄€‹[ê€‹›Ÿö[ò[^ôYò[õ›]\»H[€ô^K\]YKÇààÀ»çKéKçé8†%ôX\›\ûH‹]ëQì‘ëHÿ[]‹ôY]
ÿ\»›XõKX€›[ù[ô KÇàÀ»ô]ö[›\€Nàÿ[]€›ò[YX
ö[ò⁄\[
»L	HõŸö]
HSëôX\›\ûBàÀ»€›Ã	H€à‹8†%[ôõ][ô»õ›ò[[òŸ\»[ôX]ö[ô»ôX\›\ûHBàÀ»[ù€H€›[ù\ãàõ›Œà€€\]HôX\›\ûT⁄\ôHö\ú›‹ôY]ÿ[]àÀ»⁄]
ò[YHHôX\›\ûT⁄\ôJH€»ÿ\][Xÿ€›[ù[ô»\»€ô\›ÇàÀ»8†(à‹⁄[ôÀ‹ÿ‹ò]⁄Ÿ[»8°§àôX\›\ûT⁄\ôHH
[ò⁄[ôŸY
BàÀ»8†(àôX\›\ûK\ÿÿ[⁄[ú»8°§àL	HŸàõŸö]8°§àôX\›\ûBàÀ»8†(à[›\àY[YH⁄[ú»8°§àÃ	HŸàõŸö]8°§àôX\›\ûBàò[ôX\›\ûT⁄\ôHHYà
õà
H¬àûH¬àYà
‹Àö\’ôX\›\ûT‹⁄][€à‹ÀùòY[ô”[ŸHOHïëPT’TñHäH¬àôX\›\ûSX[òYŸ\ãò€€ùöXù]Qù[Qúõ€UôX\›\ûTÿÿ[
õÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸK\‘\\àHùYJBàH[ŸH¬àôX\›\ûSX[òYŸ\ãò€€ùöXù]Qúõ€SY[YTŸ[
õÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸK\‘\\àHùYJBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãïôX\›\ûH‹]\úõ‹à
\\äNà	ŸKõY\‹ÿYŸ_HäBàåàBàH[ŸHåÇàò\àÿ[õ€öXÿ[\\îŸ[€€[Z]YçÕHò[ŸBàûH¬àÀ»çKåççÕ8†%ïS\\à—S\Ÿ\»Hÿ[YHÿ[õ€öXÿ[ôYXŸ\à\»\ùX[ÀÇàÀ»\»€€[Z]»ÿ\⁄‹ôY]
»‹[ê€‹›ô[X\ŸH
»\YX€€õ€ZX»—S
¬àÀ»ö[ò[^ôYù\»ôYõ‹ôH[ûH›XÿŸ\‹Ÿù[õ›\õò[õ⁄ôX›[€àÿ[àôH‹ö][ãÇàò[€€]Tò]ÕçÕH\õZ[ò[ô[XZ[ö[ô‘ò]ÕçLÇàÀ»çKåççM8†%ù[ù[YHô\›\ù»]\›õ›[ùò[Y]HHY⁄][X]BàÀ»ÿ[õ€öXÿ[‹⁄][€ãàŸ[ô\ò][€à\»H[[]]XõH‹[ôY]\»ÿ\úöYYàÀ»ûHHÿ[õ€öXÿ[‹⁄][€ãô]ô\àH^\õò[òYRYÇàò[Ÿ[Ÿ[ô\ò][€ççÕHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ‹[ôY]\¬àò[YçÕH\õZ[ò[YçMBàò[\õZ[ò[YçÕHú\\óŸù[…‹YçÕW…‹Ÿ[Ÿ[ô\ò][€ççÕHÇàò[€‹ŸMçÕH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[\\ï\õZ[ò[úöYŸMçéKôö[ò[^ôTŸ[
à‹⁄][€íYHYçÕàZ[ùHòYRYõZ[ùàﬁ[Xõ€HòYRYúﬁ[Xõ€àŸ[ô\ò][€àHŸ[Ÿ[ô\ò][€ççÕàŸ[⁄Y»H\õZ[ò[YçÕà€€]Tò]»H€€]Tò]ÕçÕàôTô[XZ[ö[ô‘ò]»H\õZ[ò[ô[XZ[ö[ô‘ò]ÕçLãàôTô[XZ[ö[ô–€‹›ò\⁄\‘€€H\õZ[ò[ô[XZ[ö[ô–€‹›çLãàÀ»ò[YX\»[ôXYHô]Ÿà⁄[][]YôYT€€àH\õZ[ò[àÀ»ôYXŸ\à›XùòX›»ôY\»^X›H€òŸK€»\‹»ôKYôYH‹õ‹‹ÀÇà‹õ‹‹‘õÿŸYY‘€€H
‹õ‹‹”õ—úöX›[€ïò[YHHôX\›\ûT⁄\ôJKò€Ÿ\òŸP]X\›
å
Kà€€€‹›ò\⁄\‘€€H\õZ[ò[ô[XZ[ö[ô–€‹›çLãàôY\‘€€H⁄[][]YôYT€€ò€Ÿ\òŸP]X\›
å
Kà[ôHH‹ÀùòY[ô”[ŸKöYêõ[ö»»òYRYúﬁ[Xõ€Kà^]ôX\€€àHôX\€€ãà\õZ[ò[HùYKà›\ô\‹”X\õö[ô—ò[õ›]çLHôX\€€ãò€€ùZ[ú ú›[WŸôYYãY€õ‹ôPÿ\ŸHHùYJHàôX\€€ãò€€ùZ[ú ô]W‹]X[]HãY€õ‹ôPÿ\ŸHHùYJKà
Bàÿ[õ€öXÿ[\\îŸ[€€[Z]YçÕH€‹ŸMçÕò\YYàYà
ÿ[õ€öXÿ[\\îŸ[€€[Z]YçÕ
H¬àò[ò]’ô\ôX›çLåH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[ò]‘]X[ù]P]]‹ö]MçLåõõ‹õX[^ôSYÿXﬁRõ›\õò[ò] àõ›\õò[ò]»H€‹ŸMçÕòÿ[õ€öXÿ[€€ú›[YYò]Ààÿ[õ€öXÿ[€€ú›[YYò]»H€€]Tò]ÕçÕà
BàYà
\ò]’ô\ôX›çLåòXÿŸ\Y
H¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ìX\õö[ô‘]X\ò[ù[ôQÿ]MçÃú]X\ò[ù[ôT‹⁄][€íY
àYçÕëP“SPS‘––SW”RT”PU“‘ëP””î’ïP’––Sì”íP–S”’ÕçLåãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàòYT€ò\HòYT€ò\ò€‹JàÀ»çKåçççLH8†%[[]]XõHôXŸZ\õ⁄ôX›[€ãàô]ô\ÇàÀ»ôXÿ[›[]H\õZ[ò[X€€õ€ZX‹»úõ€H⁄Ÿ[î›]K’RHöY[ÀÇà€€H€‹ŸMçÕô‹õ‹‹‘õÿŸYY‘€€àõ€€H€‹ŸMçÕô‹õ‹‹‘õÿŸYY‘€€H€‹ŸMçÕú€€€‹›ò\⁄\‘€€H€‹ŸMçÕôôY\‘€€àô]õ€€H€‹ŸMçÕô‹õ‹‹‘õÿŸYY‘€€H€‹ŸMçÕú€€€‹›ò\⁄\‘€€H€‹ŸMçÕôôY\‘€€àõ›HYà
€‹ŸMçÕú€€€‹›ò\⁄\‘€€àå
Bà

€‹ŸMçÕô‹õ‹‹‘õÿŸYY‘€€H€‹ŸMçÕú€€€‹›ò\⁄\‘€€H€‹ŸMçÕôôY\‘€€
H¬à€‹ŸMçÕú€€€‹›ò\⁄\‘€€
H
àLå[ŸHåàôYT€€H€‹ŸMçÕôôY\‘€€à[ùûP€‹›€€H€‹ŸMçÕú€€€‹›ò\⁄\‘€€àôP€‹›€€H€‹ŸMçÕúôTô[XZ[ö[ô–€‹›ò\⁄\‘€€à€€€‹›ò\⁄\‘€€H€‹ŸMçÕú€€€‹›ò\⁄\‘€€à‹›€‹›€€H€‹ŸMçÕú‹›ô[XZ[ö[ô–€‹›ò\⁄\‘€€à‹õ‹‹‘õÿŸYY‘€€H€‹ŸMçÕô‹õ‹‹‘õÿŸYY‘€€àôT]U⁄Ÿ[àH€€]U⁄Ÿ[ççKà‹›]U⁄Ÿ[àHåàô[XZ[ö[ô‘]U⁄Ÿ[àHåà[ùûTò]‘]HH€‹ŸMçÕúôTô[XZ[ö[ô‘ò]Ààÿ[õ€öXÿ[€€ú›[YYò]»Hò]’ô\ôX›çLåõõ‹õX[^ôYò]Ààô[XZ[ö[ô‘ò]‘]HH€‹ŸMçÕú‹›ô[XZ[ö[ô‘ò]Àà⁄Ÿ[ëX⁄[X[»H€‹ŸMçÕù⁄Ÿ[ëX⁄[X[ÀùZŸRYà»]èHHŒà\õZ[ò[X⁄[X[ÕçLãàX€€õ€ZX—]ô[ùYH€‹ŸMçÕôX€€õ€ZX—]ô[ùYà
BàûH»ÀùòY\ÀòY
òYT€ò\
HHÿ]⁄
Œàõ›ÿXõJHﬂBàBàÀ»çKåççL0©ÃH8†%SSUUPìHíS’Q—Tà
—S⁄YJKÇàÀ»\ú⁄\›H\õZ[ò[—Sö[€»ÿ[õ€öXÿ[]SŸäZ[ù
HBàÀ»3®–ù^H8¢$à3®Ÿö[ò[^ôYŸ[àö[ò[^ôY]ùYHôXÿ]\ŸH\»úò[ò⁄àÀ»\»€õHôXX⁄YYù\àÿ[õ€öXÿ[\\ï\õZ[ò[úöYŸMçéBàÀ»€€[Z]Yÿ\⁄‹ôY]
»‹[ê€‹›ô[X\ŸH
»\YX€€õ€ZX¬àÀ»—S
»ö[ò[^ôYù\ÀàY[\›[ù€à
Z[ù›Y]\õZ[ò[Y
KÇàYà
ÿ[õ€öXÿ[\\îŸ[€€[Z]YçÕ
H¬àûH¬àò[õÿŸYY”[\‹ùÕçLHò]òKõX]êöY“[ùYŸ\ãùò[YSŸäà

€‹ŸMçÕô‹õ‹‹‘õÿŸYY‘€€H€‹ŸMçÕôôY\‘€€
Kò€Ÿ\òŸP]X\›
å
H
àWÃÃÃå
Kù”€ô 
Kò€Ÿ\òŸP]X\›

Bà
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ëö[›YŸ\ççLúôX€‹ôŸ[ö[
àZ[ùHòYRYõZ[ùà›YH\õZ[ò[YçÕà]U⁄Ÿ[îò]»H€€]Tò]ÕçÕà[\‹ù»HõÿŸYY”[\‹ùÕçLàö[ò[^ôYHùYKà\‘\\àHùYKà€›\òŸHH‹ÀùòY[ô”[ŸKöYêõ[ö»»òYRYúﬁ[Xõ€Kàõ›HHú\\îŸ[ù[ççÕâôX\€€àãùZŸJLå
Kà
BàHÿ]⁄
àõ›ÿXõJH¬àûH»\[[ôRX[€€X›‹ãõXô[[ò î‘’–”‘—W—íS”’‘’ST—êRSÕçLHäN»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî‘’–”‘—W—íS”’‘’ST—êRSÕçLHãõZ[ùI›òYRYõZ[ùùZŸJL
_H\úèI›õY\‹ÿYŸOÀùZŸJ
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jê–Sì”íP–S‘TTó‘—S–””SRUÕçÕãõZ[ùI›òYRYõZ[ùùZŸJL
_HYI‹YçÕùZŸJN
_H\õZ[ò[YI\õZ[ò[YçÕ\YYIÿ€‹ŸMçÕò\YYH€Z[YYIÿ€‹ŸMçÕù\õZ[ò[€Z[YYHù\œIÿ€‹ŸMçÕòù\‘Xõ\⁄YHÿ\⁄Iÿ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î\\êÿ\][]]‹ö]MçMÕÀòÿ\⁄€€

Kôõ]€€

_H‹[ê€‹›Iÿ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î\\êÿ\][]]‹ö]MçMÕÀõ‹[ê€‹›ò\⁄\‘€€

Kôõ]€€

_HôX\€€èIôX\€€àäBà\[[ôRX[€€X›‹ãõXô[[ò ê–Sì”íP–S‘TTó‘—S–””SRUÕçÕäBà\[[ôRX[€€X›‹ãõXô[[ò ê–Sì”íP–S‘TTó––T“–‘ëQUÕçÕäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàHÿ]⁄
àõ›ÿXõJH¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó–P–”’SïSë◊”UUUS”ó‘ëRëP’QãõZ[ùI›òYRYõZ[ùùZŸJL
_HôX\€€èIôX\€€à\úèI›õY\‹ÿYŸOÀùZŸJL
_HX›[€è[õ◊‹›XÿŸ\‹Ÿù[‹Ÿ[⁄õ›\õò[äBà\[[ôRX[€€X›‹ãõXô[[ò îTTó–P–”’SïSë◊”UUUS”ó‘ëRëP’QäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ—êUSàBàYà
Xÿ[õ€öXÿ[\\îŸ[€€[Z]YçÕ
H¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó–P–”’SïSë◊”UUUS”ó‘ëRëP’QãõZ[ùI›òYRYõZ[ùùZŸJL
_HôX\€€èIôX\€€àX›[€è[õ◊‹›XÿŸ\‹Ÿù[‹Ÿ[⁄õ›\õò[äBà\[[ôRX[€€X›‹ãõXô[[ò îTTó–P–”’SïSë◊”UUUS”ó‘ëRëP’QäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ—êUSàBàÀ»çKåççåé0©ÕH–Sì”íP–S–”‘—W“ì’TìêS–U”RP“UH
‹\ò]‹à\ôX›]ôHôXàåçéÇàÀ»êHÿ[õ€öXÿ[TTà€‹ŸH\»ì’ò[úÿX›[€ò[H€€\]H[ù[]¬àÀ»X€€õ€ZX»õ›\õò[]ô[ù^\›Àà\ŸH€ôH\õZ[ò[€‹ŸHò[úÿX›[€éÇàÀ»\[ô[[]]XõHX€€õ€ZX»õ›\õò[—S‘TïPS]ô[ùàÀ»]]]Hÿ[õ€öXÿ[\\êXÿ€›[ùYŸ\ÇàÀ»›[\ÿ[õ€öXÿ[‹⁄][€ÇàÀ»Xõ\⁄ö[ò[^ôY]ô[ùàÀ»ô]ûH\»Y[\›[ùûH\õZ[ò[]]][€íYàäBàÀ¬àÀ»\\êXÿ€›[ùYŸ\à[ôXYH]]]Yﬁ[ò⁄õ€õ›\€HXõ›ôHöXBàÀ»ÿ[õ€öXÿ[\\ï\õZ[ò[úöYŸMçéKôö[ò[^ôTŸ[àHòYR\›‹ûT›‹ôBàÀ»õ›\õò[‹ö]K›Ÿ]ô\ãò[à€à€ÿò[ÿ€‹Kõ][ò⁄8†%YX[ö[ô¬àÀ»\\ï\õZ[ò[õ⁄ôX›[€ê€€ùô\ôŸ[òŸMçLKò€€ùô\ôŸH
⁄X⁄ÿ[¬àÀ»\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹ŸY[ôõÿô\»Hõ›\õò[õ› BàÀ»S–VT»òXŸYZXYŸàH‹ö]Kà]õŸXŸYHÃ¬àÀ»TTó–”‘—W”ì◊“ì’TìêS‘ì’◊Õçåå»]»[àH‹\ò]‹â‹»çKåççåçÇàÀ»[\àù[àHõ›\õò[‹ö]Hﬁ[ò⁄õ€õ›\€HTëH€»X\ö–€‹ŸYÿ[ÇàÀ»ÿúŸ\ùôHHõ›Œ»ŸY\S»ŸX›\ö]H»\ú»ò[õ›]\ﬁ[òÀÇàûH¬àôX€‹ôòYJ”X\õö[ô‘€ò\òYT€ò\
BàûH»\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S“ì’TìêS‘÷Sê◊–TSëQÕçåéäHHÿ]⁄
Œàõ›ÿXõJHﬂBàHÿ]⁄
àõ›ÿXõJH¬àûH¬à\[[ôRX[€€X›‹ãõXô[[ò îTTó‘—S“ì’TìêS‘÷Sê◊—TîóÕçåéäBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàîTTó‘—S“ì’TìêS‘÷Sê◊—TîóÕçåéãàõZ[ùI›òYT€ò\õZ[ùùZŸJL
_H\úèI›õY\‹ÿYŸOÀùZŸJ
_Hãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàBà€›[ûò€‹õ›][ô\Àë€ÿò[ÿ€‹Kõ][ò⁄
\\‹]⁄\úÀú⁄YQYôôX›
H¬àûH¬àûH»ŸX›\ö]KúôX€‹ôòYJòYT€ò\
HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKåççLÃH0©‘Tî◊”ëUTêS–îíQ—H8†%ôYY‹õ‹‹ÀX\‹Ÿ]\õZ[ò[àÀ»›]€€Y\»[ù»H\ú»⁄^ô\àõ›»]\‹Ÿ]€\‹»Y‹»]ô\ûBàÀ»ÿ[õ€öXÿ[õ›Àà””SêW’“—Sà›]€€Y\»›^H[àHY[YHX\õô\ãÇàûH¬àò[\‹Ÿ]€\‹ÕçLÃHHÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLèÀò\‹Ÿ]€\‹¬àŒà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ê\‹Ÿ]€\‹Àî””SêW’“—SÇà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î\ú”ô]\ò[úöYŸMçLÃKúôX€‹ô\õZ[ò[›]€€YJà\‹Ÿ]€\‹»H\‹Ÿ]€\‹ÕçLÃKàﬁ[Xõ€H”X\õö[ô‘€ò\úﬁ[Xõ€àõ›HòYT€ò\úõ›à
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S“ì’TìêS—”ëHãõZ[ùI›òYT€ò\õZ[ùùZŸJL
_Hﬁ[Xõ€I›”X\õö[ô‘€ò\úﬁ[Xõ€Hõ›I›òYT€ò\úõ›ù“[ù

_HôX\€€èI›òYT€ò\úôX\€€üHÿ[õ€öXÿ[€€[Z]Y]ùYHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãô^X îTTó‘—S”“»ã”X\õö[ô‘€ò\úﬁ[Xõ€õZ[ùI›òYT€ò\õZ[ùùZŸJL
_Hõ›I›òYT€ò\úõ›ù“[ù

_HôX\€€èI›òYT€ò\úôX\€€ãùZŸJ
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàHÿ]⁄
àõ›ÿXõJH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãú\\îŸ[\ﬁ[ò»õ›\õò[\úõ‹éà	›õY\‹ÿYŸ_HäBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S“ì’TìêS–T÷Sê◊—TîàãõZ[ùI›òYT€ò\õZ[ùùZŸJL
_H\úèI›õY\‹ÿYŸOÀùZŸJ
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãô^X îTTó‘—S—êRSã”X\õö[ô‘€ò\úﬁ[Xõ€õZ[ùI›òYT€ò\õZ[ùùZŸJL
_HôX\€€èPT÷Sê◊—Tîéâ›õY\‹ÿYŸOÀùZŸJå
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S”PTìíSë◊–T÷Sê◊‘UQUQQãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àÿ[õ€öXÿ[€€[Z]Y]ùYHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKåççMH0©‘—S—”‘ó”RQ‘êUS”à8†%–T»”‘“Së»Oà”‘—QYù\ÇàÀ»Ÿ][Y[ù⁄YHYôôX›»
ÿ[õ€öXÿ[Z\úõ‹à
»Xÿ€›[ùYŸ\äBàÀ»›XÿŸYYà€€ôö\õU\õZ[ò[Ÿ[ô]\õú»ò[ŸHYà[ôXYH”‘—Q8†%àÀ»]]\»SîëPP“PìH\›ô\Ÿ\ùôU\õZ[ò[Ÿ[Xõ›ôKù]àÀ»H€€ôö\õH\ôH\»Hö[ò[€Xú›€ôH]›X\ò[ùY\¬àÀ»\õZ[ò[€›[ù
Y
HOHKÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMò€€ôö\õU\õZ[ò[Ÿ[
\õZ[ò[YçMJBàHÿ]⁄
àõ›ÿXõJH¬àûH»\[[ôRX[€€X›‹ãõXô[[ò î‘’–”‘—W‘’UW”Q—Tó–””ëíTìW—êRSÕçLHäN»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî‘’–”‘—W‘’UW”Q—Tó–””ëíTìW—êRSÕçLHãõZ[ùI›òYRYõZ[ùùZŸJL
_H\úèI›õY\‹ÿYŸOÀùZŸJ
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàÀ»çKåççN8†%ÿ[õ€öXÿ[]]][€à\»H€€[Z]⁄[ùà€õHõ›»X^BàÀ»õ⁄ôX›[€ú»›[\”‘—Qô[X\ŸH€›À‹à›\ô\‹»ù]\ôHõ€XöYH^]ÀÇàò[õ›õ‹ìYŸ\ççNHûH»‹[îõÿ[ö]Kö[ú‹X›‹⁄][€ä‹ÀöXŸKë^X›]‹ãò€‹ŸW€YŸ\ó‹›[\ÕåŒÕçN…›Àúﬁ[Xõ€K…›ÀõZ[ùùZŸJ
_Hã[Z]HùYJKùZŸRYà»]õ⁄»OÀúõ›Àù“[ù

HŒàHÿ]⁄
Œàõ›ÿXõJH»Bà\\ï\õZ[ò[õ⁄ôX›[€ê€€ùô\ôŸ[òŸMçLKò€€ùô\ôŸJòYRYõZ[ùÀúﬁ[Xõ€ôX\€€ãõ›õ‹ìYŸ\ççN
BàÀ»ÿ[õ€öXÿ[”‘—Q[€»€‹Ÿ\»Hÿÿ[õ⁄ôX›[€é»õ»ù]\ôH^]ô]ûHX^HôX]]\»‹[ãÇàÀú‹⁄][€àH‹⁄][€ä
BÇàò[ﬁX€X’ö\ùX[\\ê€‹ŸHH‹ÀùòY[ô”[ŸKô\]X[ ê÷P”P»ãùYJHôX\€€ãú›\ù’⁄]
ê÷P”P◊»ãùYJBàYà
ﬁX€X’ö\ùX[\\ê€‹ŸJH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S‘“TëQ’–SU–‘ëQU‘““TQãõZ[ùI›òYRYõZ[ùùZŸJL
_Hﬁ[Xõ€I›òYRYúﬁ[Xõ€H[ŸOI‹‹ÀùòY[ô”[Ÿ_HôX\€€èIôX\€€àò[YOI›ò[YKôõ]

_Hö\ùX[ÿõ€⁄œP÷P”P»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàH[ŸH¬à€î\\êò[[òŸP⁄[ôŸOÀö[ùõ⁄ŸJò[YHHôX\›\ûT⁄\ôJBàBÇàÀ»ì’T“U’”àêT’U8†%[[YYX][HYù\àH\òXõHÿ[õ€öXÿ[€‹ŸKàÀ»ﬁ[ò⁄õ€õ›\»õ›\õò[\[ôõ⁄ôX›[€à€€ùô\ôŸ[òŸK[ôRHò[[òŸBàÀ»õ⁄ôX›[€ãà\»\ŸY»⁄]
òYù\äàõZYX\õö[ôÀ[\ùÀàÀ»]]ÀX€€\›[ô[ô»[ôö[ôH\ã[[ôH€‹ŸHÿ[òX⁄‹Àà⁄]M\\ÇàÀ»‹⁄][€ú»]XYH›‹õ›

Hù[àõ‹àZ[ù]\Œ»]»Ã»ô\›\ùÿZ]\ÇàÀ»[à][ò⁄YHô\XŸ[Y[ù[ú⁄YHHZ[ô»Ÿ\ùöXŸH[ôH€Z[àÀ»ÿ[òŸ[Y]à⁄]›€à^]»\ôHõ‹òŸY›]€€Y\»[ô]\›õ›òZ[àBàÀ»›ò]YﬁH›X⁄Ààõ›Ÿ\ùöXŸH€X\ú»]ô\ûH[ôHôY⁄\›ûH[àù[»]\ãÇàYà
ôX\€€àOHòõ›‹⁄]›€àäH¬àò[⁄]›€ê€‹›€€H‹Àò€‹›€€àòYRYò€‹ŸY
öXŸKõõôX\€€äBàûH»€ÿò[òYTôY⁄\›ûKò€‹ŸT‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»[Y\ôŸ[ù›X\ôòZ[Àù[úôY⁄\›\î‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»€€KõYôXﬁX€Xõ›ùçõY]Kî‹ùõ€[“X]RKúô[[›ôT‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀú‹⁄][€àH‹⁄][€ä
BàÀõ\›^]»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
BàÀõ\›^]öXŸHHöXŸBàÀõ\›^]õ›HõàÀõ\›^]ÿ\’⁄[àHõèHKåàûH»‹⁄][€î\ú⁄\›[òŸKúô[[›ôT‹⁄][€äÀõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹ŸY
îTTàãÀõZ[ùÀúﬁ[Xõ€òõ›‹⁄]›€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH¬à⁄Ÿ[ìYôXﬁX€UòX⁄Ÿ\ãõ€îŸ[Ÿ]Y
àZ[ùHÀõZ[ùà⁄Y»HîTTéòõ›‹⁄]›€àãà€€ôXŸZ]ôYH⁄]›€ê€‹›€€ò€Ÿ\òŸP]X\›
å
Kàÿ[]⁄Ÿ[êYù\àHåà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ›Ÿ\ùöXŸKúôXŸ[ùP€‹ŸY\÷›ÀõZ[ùHHﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»ÿ[]‹⁄][€ìÿ⁄ÀúôX€‹ô€‹ŸJìY[YHã⁄]›€ê€‹›€€
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàÀ»çKåçççç8†%H\õZ[ò[€‹ŸH]\›€X\àHõ€⁄»]X›X[BàÀ»‹[ôYHZ[ùà”‘ëHÿ\»H›[H\ôX€ŸYYò][[ôYùàÀ»‹X⁄X[\›ÿ⁄‹»ôZ[ôàù[]\õZ[ò[[ùô[ù‹ûH\»Z[ùàÀ»ÿ[õ€öXÿ[€»€X\à]ô\ûHô\⁄YX[õ€⁄»õ‹à\»Z[ùÇàûH»òYP]]‹ö^ô\ãúô[X\ŸT‹⁄][€äÀõZ[ùî—S…ôX\€€àäHHÿ]⁄
Œà^Ÿ\[€äHﬂBà€ìŸ º'Ê‰H“U’”à”‘—Nà	›Àúﬁ[Xõ€H	‹õù“[ù

_IH
X\õö[ô»⁄⁄\Y
HãòYRYõZ[ù
Bàô]\õàŸ[ô\›[îTTó–””ëíTìQQàBààYà
Ÿô 
KôõZYX\õö[ô—[òXõY
H¬àõZYX\õö[ôÀúôX€‹ô\\îŸ[
òYRYõZ[ù‹Àò€‹›€€õ
BàõZYX\õö[ôÀúôX€‹ôöXŸR[\X›
òYRYõZ[ù‹Àò€‹›€€Àõ\›\]ZY]U\Ÿ\–ù^HHò[ŸJBàõZYX\õö[ôÀò€X\îöXŸR[\X›
òYRYõZ[ù
BàBàà€ìŸ îTTà—S	‹öXŸKôõ]

_H	ôX\€€àõ	‹õôõ]

_H””
	‹õôõ]›

_JHãòYRYõZ[ù
Bà€ìõ›YûJº'‰‚H\\àŸ[ãâ›òYRYúﬁ[Xõ€H	ôX\€€àì	‹õôõ]›

_Hã€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì BààòYP[\ùÀõ€îŸ[
Ÿô 
KòYRYúﬁ[Xõ€õõôX\€€ã\‘\\àHùYJBààYà
õà
H€›[ôœÀú^Pÿ\⁄ôY⁄\›\ä
H[ŸH€›[ôœÀú^Uÿ\õö[ô‘⁄\ô[ä
BàYà
õà
H€›[ôœÀú^SZ[\›€ôJõ
Bà€X\ù⁄^ô\ãúôX€‹ôòYJõà\‘\\ì[ŸHHùYJBÇàÀ»çKéKç8†%ôYY^X›][€ê€‹›ôYX›‹êRH[à\\à[ŸH€»]¬àÀ»\ã[\KXò[ô\›‹ûHXÿ›[][]\»ÿ[\\»
ÿ\»L	Hô\õ»»PQ
KÇàÀ»ﬁ[ù]X»€\H‹\ù
⁄^ôK€\JHò\⁄\À\⁄[ù[Ÿ[8†%õ›Y⁄ù]àÀ»XX⁄\»H^Y\àHô[][€ú⁄\ô]ŸY[à⁄^ôK\]ZY]H[ôàÀ»ôX[^ôY€‹›à^Y\àÿ[à[àôYö[ôH⁄[à]ôHòY\»ù[ãÇàûH¬àò[\U\Ÿ€\HÀõ\›\]ZY]U\Ÿàò[⁄^ôU\ŸH‹Àò€‹›€€
àÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸBàYà
\U\Ÿ€\à	âà⁄^ôU\Ÿà
H¬àò[€\úòX»H€›[ãõX]ú‹\ù
⁄^ôU\Ÿ»\U\Ÿ€\
Kò€Ÿ\òŸP][‹›
åL
Bàò[][›YHÀõ\›öXŸBàò[ôX[^ôYH][›Y
à
KåH€\úòX Bà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë^X›][€ê€‹›ôYX›‹êRKõX\õäà\U\ŸH\U\Ÿ€\à][›Y\ŸH][›YàôX[^ôY\ŸHôX[^ôYà
BàBàHÿ]⁄
Œà^Ÿ\[€äHﬂBÇàÀ»çKéKåŒNH»çKéKçé8†%ôX\›\ûH‹][›ôYXõ›ôH[ô[ùY‹ò]Y⁄]àÀ»ÿ[]‹ôY]€»Xÿ€›[ù[ô»\»€ô\›
õ»›XõKX€›[ù[ô Kà‹⁄[ô¬àÀ»Ÿ[»›[€€ùöXù]Hõ›[ôÀÇÇàYà
õà
H¬àûH¬àò[ò]Ÿ›€î›H€X\ù⁄^ô\ãôŸ]›\úô[ùò]Ÿ›€î›
\‘\\àHùYJBàò[[ÿÿ][€àH]]–€€\›[ô[ô⁄[ôKúõÿŸ\‹’⁄[äõò]Ÿ›€î›
BààYà
[ÿÿ][€ãù’ôX\›\ûHà
H¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKòY’ôX\›\ûJ[ÿÿ][€ãù’ôX\›\ûK\‘\\àHùYJBàBàà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'Â!UUÀP””T’Së‘TTóNà	‹õôõ]

_H””õŸö]8°§àà
¬àïôX\›\ûNà	ÿ[ÿÿ][€ãù’ôX\›\ûKôõ]
 _Hà
¬àê€€\›[ôà	ÿ[ÿÿ][€ãù–€€\›[ôôõ]
 _Hà
¬àïÿ[]à	ÿ[ÿÿ][€ãù’ÿ[]ôõ]
 _Hà
¬àî⁄^ôH][à	ÿ[ÿÿ][€ãõô]‘⁄^ôS][\Y\ãôõ]
ä_^äBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãê]]–€€\›[ô\úõ‹à
\\äNà	ŸKõY\‹ÿYŸ_HäBàBàBÇàûH¬àò[\’⁄[àHõèHKåÀ»çKéKååçéàIHõ€‹à8†%õå[ò€Y\»ôYK]ÿ\⁄ôX\ã^ô\õ‹¬ààò[ôX\›\ûT⁄Y€ò[HYà
\’⁄[äH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[ïR—W‘ì—íUàH[ŸH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[î’‘”‘‘¬àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKò€‹ŸT‹⁄][€äÀõZ[ùöXŸKôX\›\ûT⁄Y€ò[
Bààò[⁄]€⁄[î⁄Y€ò[HYà
\’⁄[äH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ïòY\êRKë^]⁄Y€ò[ïR—W‘ì—íUàH[ŸH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ïòY\êRKë^]⁄Y€ò[î’‘”‘‘¬àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ïòY\êRKò€‹ŸT‹⁄][€äÀõZ[ùöXŸK⁄]€⁄[î⁄Y€ò[
Bààò[õYX⁄\⁄Y€ò[HYà
\’⁄[äH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[ïR—W‘ì—íUàH[ŸH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[î’‘”‘‘¬àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKò€‹ŸT‹⁄][€äÀõZ[ùöXŸKõYX⁄\⁄Y€ò[
BÇàÀ»çKéKéMå»8†%SíUëTî–S’PãUêQTà”‘—H
\\îŸ[⁄[ã€‹‹»]
KÇàÀ»ôKYö^€õHÿ\⁄Ÿ[ã‘⁄]€⁄[ã–õYP⁄\€›€‹ŸT‹⁄][€ä
H\ôKÇàÀ»H›\àà›Xã]òY\ú»
[€€ú⁄›]X[]KX[ö\[]Y\[ù\ãàÀ»õ⁄ôX›€ö\\ã⁄]€⁄[ë^ô\‹ Hô]Z[ôYH[ùûH[àZ\àö]ò]BàÀ»X›]ôT‹⁄][€ú»X\õ‹ô]ô\ãàYù\àÀú‹⁄][€àÿ\»ô\õŸYô[›ÀàÀ»ò\Y›‹‹‹”[€ö]‹â‹»çKéKçÃåH›Xã]òY\à›ŸY\Ÿ\ö[ô[ô»BàÀ»õ€XöYH[ùûKö\ôYô\]Y\›Ÿ[OàX‹]Z\ôTŸ[ÿ⁄»OàSëPQW–”‘—QàÀ»Oàô[X\ŸTŸ[ÿ⁄»]å»ÿ[À‹ŸX»õ‹àä»Z[ù]\»\àXYZ[ùÇàÀ»çKéKéMåàõ‹ô[ú⁄X‹ŒàM»—S”–“◊‘—Uõ‹à»ôX[Ÿ[ÀÇàûH¬àò[Q^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKë^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKò€‹ŸT‹⁄][€äÀõZ[ùöXŸKQ^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[Q^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKë^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKò€‹ŸT‹⁄][€äÀõZ[ùöXŸKQ^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[X[ë^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKìX[ö\^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKìX[ö\^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKò€‹ŸT‹⁄][€äÀõZ[ùöXŸKX[ë^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[\^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKë\^]⁄Y€ò[îëP”’ëTñW’Të—Uà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKë\^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKò€‹ŸQ\
ÀõZ[ùöXŸK\^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[€ë^H€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKë^]⁄Y€ò[
à⁄›[^]HùYKà^]›HLàôX\€€àHYà
\’⁄[äHïR—W‘ì—íUà[ŸHî’‘”‘‘»ãàò[ö»H€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKî€ö\\îò[öÀîSëSëÀà
Bà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKò€€\]SZ\‹⁄[€äÀõZ[ùöXŸK€ë^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[^^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àë^]⁄Y€ò[ïR—W‘ì—íUÃÃà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àô^]öYJÀõZ[ùöXŸK^^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBÇà\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àã∏ß!H€‹ŸY[H›Xã]òY\à‹⁄][€ú»õ‹à	›Àúﬁ[Xõ€HäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãë\úõ‹à€‹⁄[ô»^Y\à‹⁄][€úŒà	ŸKõY\‹ÿYŸ_HäBàBÇàÀ»çKéKåLLÃ»8†%€‹ŸH\\à‹⁄][€à›]HôYõ‹ôHX]ûHX\õö[ô»ò[õ›]ÇàÀ»ÃL⁄›ŸY⁄‹›[€⁄⁄[ô»‹[à‹⁄][€ú»Yù\àŸ[»⁄[HH€ŸHŸ\àÀ»Àú‹⁄][€à‹[à[ù[Yù\àòY[ÿúŸ\ùò][€ãÿúòZ[ã€Y[[‹ûH€‹öÀàBàÀ»\ﬁ[ò»õ›\õò[[ôXYH\Ÿ\»”X\õö[ô‘€ò\[ô]\àX\õö[ô»ÿ[àôXYàÀ»[[]]XõH‹ÿ€»€X\à]ôH›]Hõ›»»›‹^]›ŸY\À‹ôKY[ùûBàÀ»›X\ô»úõ€HŸYZ[ô»H€‹ŸY\\àòYH\»›[‹[ãÇàÀú‹⁄][€àH‹⁄][€ä
BàÀõ\›^]»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
BàÀõ\›^]öXŸHHöXŸBàÀõ\›^]õ›HõàÀõ\›^]ÿ\’⁄[àHõèHKåàûH»‹⁄][€î\ú⁄\›[òŸKúô[[›ôT‹⁄][€äÀõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S‘‘“US”ó–”‘—QãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€Hõ›I‹õù“[ù

_HôX\€€èIôX\€€à›YŸOYX\õWÿ€‹ŸH\ú⁄\›Y\ô[[›ôYäHHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKéKåLÕéH8†%“‘’íV
YôXﬁX€HòX⁄Ÿ\äKàHYôXﬁX€HYŸ\à€õHôXX⁄\¬àÀ»HTìRSêS›]H
”PTëQ
H⁄[à€îŸ[Ÿ]YŸY\»ÿ[]⁄Ÿ[êYù\èY\›à[ÇàÀ»TTà[ŸH\ôH\»ì»]ôHÿ[]»ôX€€ò⁄[K€»H€]ôK]ÿ[]Yö]ô[ÇàÀ»Ÿ]Hô]\õôYù[OàëT“QPS“S
õ€ã]\õZ[ò[
H[ô‹[ê€›[ù

HŸ\BàÀ»€€\\à‹⁄][€àõ‹ô]ô\ãàRH⁄›ŸY[ôõ]Yåç‹[ààú»»ôX[àH\\ÇàÀ»Ÿ[T»Hù]
H⁄[Hù[H^] K€»Ÿ]H]\õZ[ö\›Xÿ[H⁄]àÀ»ÿ[]⁄Ÿ[êYù\èLåOà”PTëQà€‹Ÿ\»HYôXﬁX€K]òX⁄Ÿ\à⁄‹›€\‹ÀÇàûH¬à⁄Ÿ[ìYôXﬁX€UòX⁄Ÿ\ãõ€îŸ[Ÿ]Y
àZ[ùHÀõZ[ùà⁄Y»HîTTéâ‹ôX\€€üHãà€€ôXŸZ]ôYH‹Àò€‹›€€ò€Ÿ\òŸP]X\›
å
Kàÿ[]⁄Ÿ[êYù\àHåà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ›Ÿ\ùöXŸKúôXŸ[ùP€‹ŸY\÷›ÀõZ[ùHHﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€ÿò[òYTôY⁄\›ûKò€‹ŸT‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»[Y\ôŸ[ù›X\ôòZ[Àù[úôY⁄\›\î‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»€€KõYôXﬁX€Xõ›ùçõY]Kî‹ùõ€[“X]RKúô[[›ôT‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBààûH¬àòYP]]‹ö^ô\ãúô[X\ŸT‹⁄][€äàZ[ùHÀõZ[ùàôX\€€àHî—S…ôX\€€àãà
Bà\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãº'Â$»TìRSêSì”“»–“‘»ëSPT—Qà	›Àúﬁ[Xõ€HäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãëòZ[Y»ô[X\ŸH\õZ[ò[õ€⁄»ÿ⁄‹Œà	ŸKõY\‹ÿYŸ_HäBàBÇàò[X^ÿZ[î›HYà
‹Àô[ùûTöXŸHà	âà‹ÀöY⁄\›öXŸHà
H¬à

‹ÀöY⁄\›öXŸHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLåàH[ŸHåààò[⁄Ÿ[êYŸSZ[ú»HYà
ÀòYY’ÿ]⁄\›]à
H¬à
‹Àô[ùûU[YHHÀòYY’ÿ]⁄\›]
H»åÃåàH[ŸHåààÀ»çKéKåÕå»8†%÷SSQUíP»–‘êU“ì”ëKÇàÀ»€à“Sà8¢iH
Ããå	K‘‘»8¢iLÀå	KàH\ﬁ[[Y]öX»L…H‹‹»ô\⁄€àÀ»YX[ù⁄[õô\ú»]ô]òXŸYúõ€H
ÕL	HòX⁄»»
ÃIH€›Y[à\¬àÀ»–‘êU“⁄[HLâH‹‹Ÿ\»›[⁄›ŸY\\»	‹ôX[	Àà⁄]NMåàÀ»ÿ‹ò]⁄\»»åH⁄[ú»»NH‹‹Ÿ\À\»\ﬁ[[Y]ûHÿ\»\›‹ù[ô¬àÀ»HX\õö[ô»⁄Y€ò[SëH‘à\‹^Kàﬁ[[Y]öX»0¨LKçIH⁄]ô\¬àÀ»õ›⁄Y\»\]X[ŸZY⁄8†%ô]Ÿ\àÿ‹ò]⁄\ÀòZ\ô\àúòZ[àòZ[ö[ôÀÇàò[òYP€\‹⁄YöXÿ][€àH⁄[à¬àõèHKçHOàï“SàÇàõHLKçHOàì‘‘»Çà[ŸHOàî–‘êU“ÇàBààò[\‘ÿ‹ò]⁄òYHHòYP€\‹⁄YöXÿ][€àOHî–‘êU“Çàò[⁄›[X\õê\”‹‹»HòYP€\‹⁄YöXÿ][€àOHì‘‘»Çàò[⁄›[X\õê\’⁄[àHòYP€\‹⁄YöXÿ][€àOHï“SàÇàà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'‰‚à	›òYRYúﬁ[Xõ€H”T‘“QíQQà	òYP€\‹⁄YöXÿ][€àà
¬àúõI‹õù“[ù

_IH€I⁄€[YSZ[úÀù“[ù

_[Z[àà
¬àõX\õèI⁄Yä\‘ÿ‹ò]⁄òYJHìì»
ÿ‹ò]⁄
Hà[ŸHñQT»üHäBààYà
⁄›[X\õê\”‹‹ H¬àò[ò[ìò[YHHÀõY]Kô[XYò[ê[Y€õY[ùàò[H‹Àô[ùûT\ŸBàò[‹ò»HÀú€›\òŸKöYêõ[ö»»ïSí”ì’”ààBÇàòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHú\ŸOI‹JŸ[XOIŸò[ìò[Y_HãàôZ]ö[›\ï\HHëSïñW‘“Q”êSãà\ÿ‹ö\[€àHì‹‹»€à	
»	ò[ìò[YH8†%õI‹õù“[ù

_IHãà‹‹‘›Hõà
BàYà
‹ò»OHïSí”ì’”àäHòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHú€›\òŸOI‹‹òﬂHãàôZ]ö[›\ï\HHî”’Tê—Hãà\ÿ‹ö\[€àHì‹‹»úõ€H€›\òŸH	‹ò»ãà‹‹‘›Hõà
BàòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHô^]‹ôX\€€èI‹ôX\€€üHãàôZ]ö[›\ï\HHëVU‘UTìàãà\ÿ‹ö\[€àHë^]öXH	ôX\€€àô\›[Y[à‹‹»ãà‹‹‘›Hõà
Bàò[ÿ€‹ôTò[ôŸHH⁄[à¬à‹Àô[ùûTÿ€‹ôHèHOàöY⁄Œ
»Çà‹Àô[ùûTÿ€‹ôHèHçHOàõYY][WÕçKMŒHÇà‹Àô[ùûTÿ€‹ôHèHLOàõ›◊ÕLMçÇà[ŸHOàùô\ûW€›◊œLÇàBàòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHô[ùûW‹ÿ€‹ôW‹ò[ôŸOI‹ÿ€‹ôTò[ôŸ_HãàôZ]ö[›\ï\HHî–”‘ëW‘UPSUHãà\ÿ‹ö\[€àHì‹‹»⁄][ùûHÿ€‹ôH	‹‹Àô[ùûTÿ€‹ôKù“[ù

_H
	ÿ€‹ôTò[ôŸJHãà‹‹‘›Hõà
BààúòZ[èÀõ]»àOÇàò[⁄›[õX⁄€\›HãõX\õëúõ€UòYJà\’⁄[àHò[ŸKà\ŸHHà[XQò[àHò[ìò[YKà€›\òŸHH‹òÀàõ›HõàZ[ùHÀõZ[ùàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKàù^Tô\‹›\ôHHÀõY]Kúô\‹‘ÿ€‹ôKà‹€\î›HÀúÿYô]Kù‹€\î›à\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà\”]ôUòYHHò[ŸKà\õ›ò[€\‹»HòYRYôô–\õ›ò[€\‹ÀöYë[\H»îTTó–ëSê“PTí»àKà€[YSZ[ù]\»H€[YSZ[úÀàX^ÿZ[î›HX^ÿZ[î›à^]ôX\€€àHôX\€€ãà⁄Ÿ[êYŸSZ[ù]\»H⁄Ÿ[êYŸSZ[úÀà
BàYà
⁄›[õX⁄€\›
H¬àÀ»çKååŒ8†%ô\X]Y‹‹Ÿ\»\ôHX\õö[ô»ô\‹›\ôKõ›àÀ»X[X⁄[›\»Z[ùõ€Ÿãà^HX^HôYXŸHÿ€‹ôK‹⁄^ôH‹àöYŸŸ\ÇàÀ»⁄‹ù€€€›€ã‹]õ›Ÿ⁄XÀù]]\›ô]ô\à‹ö]HH\ôàÀ»õX⁄€\›]]ô\ù»ù]\ôH]ôHÿ[ôY]\»[ù»⁄Y›ÀÇàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàêïVW—–UW—P“T“S”àãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HX⁄\⁄[€èTSêSW””ìHôX\€€èLä◊€‹‹Ÿ\»€›\òŸOQ^X›]‹ãú\\ìX\õö[ô»]ôQ[Y⁄XõO]ùYHãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò êïVW—–UW‘SêSW””ìW‘ëTPUQ”‘‘»äHHÿ]⁄
Œàõ›ÿXõJHﬂBà€ìŸ º'ÈËSêSW””ìNà	›Àúﬁ[Xõ€Hô\X]Y‹‹Ÿ\»8†%ÿ€‹ôK‹⁄^ôKÿ€€€›€àô\‹›\ôH€õKõ»õX⁄€\›ãÀõZ[ù
BàBàBààòY[ô”Y[[‹ûKõX\õëúõ€PòYòYJàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à‹‹‘›Hõà\ŸHHà[XQò[àHò[ìò[YKà€›\òŸHH‹òÀà\]ZY]HHÀõ\›\]ZY]U\ŸàXÿ\HÀõ\›Xÿ\àYŸR›\ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH
Àö\›‹ûKôö\ú›‹ìù[

OÀù»Œàﬁ\›[Kò›\úô[ù[YSZ[\ 
JJH»◊ÕåÃåàY€ÿ⁄X[»Hò[ŸKà\‘[\ù[àHÀú€›\òŸKò€€ùZ[ú ú[\ãY€õ‹ôPÿ\ŸHHùYJKàõ€[YU”\Tò][»HYà
Àõ\›\]ZY]U\Ÿà
HÀö\›‹ûKõ\›‹ìù[

OÀùõ€Àô]äÀõ\›\]ZY]U\Ÿ
HŒàå[ŸHåà
Bà€ìŸ º'È%àRHPTìëQà‹‹»€à	›Àúﬁ[Xõ€H\ŸOI[XOIò[ìò[YH]\õàôX€‹ôYãÀõZ[ù
BààYà
õHLMKå
H¬à€›[ûò€‹õ›][ô\Àë€ÿò[ÿ€‹Kõ][ò⁄
\\‹]⁄\úÀú⁄YQYôôX›
H¬àûH¬à€€KõYôXﬁX€Xõ›ò€€X›]ôKê€€X›]ôSX\õö[ôÀòúõÿYÿ\››⁄Ÿ[äàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àõ›Hõà€€ôöY[òŸHHà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà[ŸHH‹ÀùòY[ô”[ŸKöYêõ[ö»»îTTààKàôX\€€àHêUì“Q… \õ
Kù“[ù

_IW”‘‘»Çà
Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àã∏¶®;Ó#»îì–Q–T’Uì“Qà	›Àúﬁ[Xõ€H	‹õù“[ù

_IH8°§àô]€‹ö»ÿ\õôYHäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãêúõÿYÿ\›]õ⁄Y\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBàBàBàH[ŸHYà
⁄›[X\õê\’⁄[äH¬àò[ò[ìò[YHHÀõY]Kô[XYò[ê[Y€õY[ùàò[H‹Àô[ùûT\ŸBàò[‹ò»HÀú€›\òŸKöYêõ[ö»»ïSí”ì’”ààBàòYQèÀúôX€‹ô€€ŸÿúŸ\ùò][€äú\ŸOI‹JŸ[XOIŸò[ìò[Y_HäBàòYQèÀúôX€‹ô€€ŸÿúŸ\ùò][€äú€›\òŸOI‹‹òﬂHäBààúòZ[èÀõ]»àOÇàãõX\õëúõ€UòYJà\’⁄[àHùYKà\ŸHHà[XQò[àHò[ìò[YKà€›\òŸHH‹òÀàõ›HõàZ[ùHÀõZ[ùàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKàù^Tô\‹›\ôHHÀõY]Kúô\‹‘ÿ€‹ôKà‹€\î›HÀúÿYô]Kù‹€\î›à\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà\”]ôUòYHHò[ŸKà\õ›ò[€\‹»HòYRYôô–\õ›ò[€\‹ÀöYë[\H»îTTó–ëSê“PTí»àKà€[YSZ[ù]\»H€[YSZ[úÀàX^ÿZ[î›HX^ÿZ[î›à^]ôX\€€àHôX\€€ãà⁄Ÿ[êYŸSZ[ù]\»H⁄Ÿ[êYŸSZ[úÀà
BàBààòY[ô”Y[[‹ûKõX\õëúõ€U⁄[õö[ô’òYJàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à⁄[î›Hõà\ŸHHà[XQò[àHò[ìò[YKà€›\òŸHH‹òÀà€[YSZ[ù]\»H€[YSZ[úÀà
Bà€ìŸ º'È%àRHPTìëQà⁄[à€à	›Àúﬁ[Xõ€H
…‹õù“[ù

_IH]\õàôZ[ôõ‹òŸYãÀõZ[ù
BààYà
õèHåå
H¬à€›[ûò€‹õ›][ô\Àë€ÿò[ÿ€‹Kõ][ò⁄
\\‹]⁄\úÀú⁄YQYôôX›
H¬àûH¬à€€KõYôXﬁX€Xõ›ò€€X›]ôKê€€X›]ôSX\õö[ôÀòúõÿYÿ\››⁄Ÿ[äàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àõ›Hõà€€ôöY[òŸHH‹Àô[ùûTÿ€‹ôKù“[ù

Kò€Ÿ\òŸR[äL
Kà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà[ŸHH‹ÀùòY[ô”[ŸKöYêõ[ö»»îTTààKàôX\€€àHYà
õèHL
HìQQ–W’“SìëTó…‹õù“[ù

_IHà[ŸHêíQ◊’“Só…‹õù“[ù

_IHÇà
Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'‰ËHîì–Q–T’»ëU”‘íŒà	›Àúﬁ[Xõ€H
…‹õù“[ù

_IH8°§à[õ›»õ›YöYYHäBàòYP[\ùÀõ€êöY’⁄[äŸô 
KÀúﬁ[Xõ€õõ\‘\\àHùYJBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãêúõÿYÿ\›\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBàBàBàH[ŸH¬à€ìŸ º'‰‚à	›Àúﬁ[Xõ€Nàÿ‹ò]⁄òYH
	‹õù“[ù

_IJHH⁄⁄\Yõ‹àX\õö[ô»ãÀõZ[ù
BàBààYà
⁄›[X\õê\’⁄[à⁄›[X\õê\”‹‹ H¬àúòZ[èÀõX\õïô\⁄€
à\’⁄[àH⁄›[X\õê\’⁄[ãàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKàù^Tô\‹›\ôHHÀõY]Kúô\‹‘ÿ€‹ôKà‹€\î›HÀúÿYô]Kù‹€\î›à\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿàõ›Hõà
BàBÇàò[í\’⁄[àH⁄[à¬à\‘ÿ‹ò]⁄òYHOàù[àõàKåOàùYBà[ŸHOàò[ŸBàBààò[Ÿ^]ÃHHﬁ\›[Kò›\úô[ù[YSZ[\ 
HÀ»çKéKåMNH8†%⁄[ô€H^]]Œ»[ùûK⁄[ÿ[ö]^ôYàòYQèÀö[úŸ\ùòYJòYTôX€‹ô
à—[ùûO\ÿ[ö]^ôQ[ùûU Àú‹⁄][€ãô[ùûU[YKŸ^]ÃJK—^]WŸ^]ÃKàﬁ[Xõ€]Àúﬁ[Xõ€Z[ù]ÀõZ[ùà[ŸOZYäÀú‹⁄][€ãô[ùûT\ŸKò€€ùZ[ú ú[\äJHìUSê“à[ŸHîêSë—Hãà[ùûTöXŸO]Àú‹⁄][€ãô[ùûTöXŸK[ùûTÿ€‹ôO]Àú‹⁄][€ãô[ùûTÿ€‹ôKà[ùûT\ŸO]Àú‹⁄][€ãô[ùûT\ŸK[XQò[è]ÀõY]Kô[XYò[ê[Y€õY[ùàõ€ÿ€‹ôO]ÀõY]Kùõ€ÿ€‹ôKô\‹‘ÿ€‹ôO]ÀõY]Kúô\‹‘ÿ€‹ôK[€Tÿ€‹ôO]ÀõY]Kõ[€Tÿ€‹ôKà€\ê€›[ù]Àö\›‹ûKõ\›‹ìù[

OÀö€\ê€›[ùŒåà€\ë‹õ››]Àö€\ë‹õ››ò]K\]ZY]U\Ÿ]Àõ\›\]ZY]U\ŸXÿ\\Ÿ]Àõ\›Xÿ\à^]öXŸO\öXŸK^]\ŸO]Àú\ŸK^]ôX\€€è\ôX\€€ãà[Z[úœ\ÿ[ö]^ôR[Z[ú Àú‹⁄][€ãô[ùûU[YKŸ^]ÃJKà‹\€›[ù]Àú‹⁄][€ãù‹\€›[ù\ùX[€€]Àú‹⁄][€ãú\ùX[€€›à€€[è]Àú‹⁄][€ãò€‹›€€€€›]]ò[YKõ€€\õõ›\õà\’⁄[èYí\’⁄[ãà\‘ÿ‹ò]⁄Z\‘ÿ‹ò]⁄òYKà
JBààûH¬àò[€Z[ú»H

ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YJH»åÃå
Bàò[⁄Ÿ[êYŸSZ[ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀòYY’ÿ]⁄\›]
H»åÃåàò[ôX]\ô\»HY\]ôSX\õö[ô—[ô⁄[ôKòÿ\\ôQôX]\ô\ à[ùûSXÿ\\ŸHÀú‹⁄][€ãô[ùûS\]ZY]U\Ÿ
àãà⁄Ÿ[êYŸSZ[ù]\»H⁄Ÿ[êYŸSZ[úÀàù^Tò][‘›HÀõY]Kúô\‹‘ÿ€‹ôKàõ€[YU\ŸHÀõ\›\]ZY]U\Ÿ
àÀõY]Kùõ€ÿ€‹ôH»Låà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà€\ê€›[ùHÀö\›‹ûKõ\›‹ìù[

OÀö€\ê€›[ùŒàà‹€\î›HÀúÿYô]Kù‹€\î›à€\ë‹õ››ò]HHÀö€\ë‹õ››ò]Kà]ïÿ[]›Håàõ€ô[ô–›\ùôTõŸ‹ô\‹»HLåàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKù—›XõJ
Kà[XQò[î›]HHÀõY]Kô[XYò[ê[Y€õY[ùà[ùûTÿ€‹ôHHÀú‹⁄][€ãô[ùûTÿ€‹ôKàöXŸQúõ€P]HYà
Àú‹⁄][€ãöY⁄\›öXŸHà
Hà

Àú‹⁄][€ãöY⁄\›öXŸHHÀú‹⁄][€ãô[ùûTöXŸJH»Àú‹⁄][€ãô[ùûTöXŸH
àL
H[ŸHåàõ›HõàX^ÿZ[î›HYà
Àú‹⁄][€ãô[ùûTöXŸHà
Hà

Àú‹⁄][€ãöY⁄\›öXŸHHÀú‹⁄][€ãô[ùûTöXŸJH»Àú‹⁄][€ãô[ùûTöXŸH
àL
H[ŸHåàX^ò]Ÿ›€î›Håà[YU‘XZ”Z[ú»H€Z[ú»
àçKà€[YSZ[ú»H€Z[úÀà^]ôX\€€àHôX\€€ãà[ùûT\ŸHHÀú‹⁄][€ãô[ùûT\ŸKà›XõUòYRŸ^HHâ›ÀõZ[ùNâ›Àú‹⁄][€ãô[ùûU[Y_Hãà
BàY\]ôSX\õö[ô—[ô⁄[ôKõX\õëúõ€UòYJôX]\ô\ BààYà
⁄›[X\õê\’⁄[à⁄›[X\õê\”‹‹ H¬àò[⁄Ÿ[êYŸR›\ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀòYY’ÿ]⁄\›]
H»◊ÕåÃåàÿÿ[õô\ìX\õö[ôÀúôX€‹ôòYJà€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKà\U\ŸHÀõ\›\]ZY]U\ŸàYŸR›\ú»H⁄Ÿ[êYŸR›\úÀà\’⁄[àH⁄›[X\õê\’⁄[Çà
BàÀ»çKåçM»8†%ÿÿ[õô\î€›\òŸPúòZ[à
\ò[[Q“HXY
BàûH¬àÀ»çKåçéMà8†%\‹»Hò]»õ›ô[ò[òŸKàöYë[\H»ïSí”ì’”ààXàÀ»\õôYHZ\‹⁄[ô»€›\òŸH[ù»H]\ò[€›\òŸHêSQQïSí”ì’”àãàÀ»õ›[ô[ô»Hÿÿ[õô\à€⁄‹ù›]Ÿàõ›‹»⁄‹ŸH‹öY⁄[àÿ\»‹›ÇàÀ»ÿÿ[õô\î€›\òŸPúòZ[àõ›»€›[ù»[ôõ‹»Hõ[ö»[ú›XYÇàÿÿ[õô\î€›\òŸPúòZ[ãúôX€‹ô›]€€YJà€›\òŸHHÀú€›\òŸKàõ›Hõà
BàHÿ]⁄
Œàõ›ÿXõJH»BàÀ»
\\à€‹Ÿ\»[ù[ù[€ò[H»ì’ôYY]ôT⁄^ö[ô‘õŸö[KÇàÀ»Z[Hò[\\»ôYúõ€HUëH€‹Ÿ\»€õH8†%ŸYHUëH€‹ŸH]äBàò[òY[ô”[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYë[\H»î’SëTëàBàò[›\ìŸë^Qõ‹ì[ŸHHò]òKù][êÿ[[ô\ãôŸ][ú›[òŸJ
KôŸ]
ò]òKù][êÿ[[ô\ãí’Tó”—ó—VJBàò[€[YS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YBàà[ŸSX\õö[ôÀúôX€‹ôòYJà[ŸHHòY[ô”[ŸKà\’⁄[àH⁄›[X\õê\’⁄[ãàõ›Hõà€[YS\»H€[YS\Àà[ùûT\ŸHHÀú‹⁄][€ãô[ùûT\ŸKà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKà›\ìŸë^HH›\ìŸë^Qõ‹ì[ŸKà
Bàà[ŸSX\õö[ôÀúŸ[íX[[ô–⁄X⁄—õ‹ì[ŸJòY[ô”[ŸJBàH[ŸH¬à\úõ‹ìŸŸŸ\ãôXùY îÿÿ[õô\ìX\õö[ô»ãî⁄⁄\Yÿ‹ò]⁄òYHõ‹à	›Àúﬁ[Xõ€H
õI‹õù“[ù

_IJHäBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY êY\]ôSX\õö[ô»ãëôX]\ôHÿ\\ôH\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBààûH¬àò[€Z[ú»H

ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YJH»åÃå
Kù“[ù

Bàò[›\ìŸë^HHò]òKù][êÿ[[ô\ãôŸ][ú›[òŸJ
KôŸ]
ò]òKù][êÿ[[ô\ãí’Tó”—ó—VJBàò[^SŸïŸYZ»Hò]òKù][êÿ[[ô\ãôŸ][ú›[òŸJ
KôŸ]
ò]òKù][êÿ[[ô\ãëVW”—ó’—QR Bààò[õ€][]S]ô[H⁄[à¬àÀú‹⁄][€ãöY⁄\›öXŸHà	âàÀú‹⁄][€ãô[ùûTöXŸHàOà¬àò[›⁄[ô»H

Àú‹⁄][€ãöY⁄\›öXŸHHÀú‹⁄][€ãõ›Ÿ\›öXŸJH»Àú‹⁄][€ãô[ùûTöXŸH
àL
Bà⁄[à¬à›⁄[ô»àLOàíQ“Çà›⁄[ô»àåOàìQQUSHÇà[ŸHOàì’»ÇàBàBà[ŸHOàìQQUSHÇàBààò[õ€[YT⁄Y€ò[H⁄[à¬àÀõY]Kùõ€ÿ€‹ôHàOàî’Të—HÇàÀõY]Kùõ€ÿ€‹ôHàåOàíSê‘ëPT“Së»ÇàÀõY]Kùõ€ÿ€‹ôHàOàìì‘ìPSÇàÀõY]Kùõ€ÿ€‹ôHàåOàëP‘ëPT“Së»Çà[ŸHOàì’»ÇàBààò[X\öŸ]Ÿ[ù[Y[ùH⁄[à¬àÀõY]Kô[XYò[ê[Y€õY[ùò€€ùZ[ú êïSäHOàêïSÇàÀõY]Kô[XYò[ê[Y€õY[ùò€€ùZ[ú êëPTàäHOàêëPTàÇà[ŸHOàìëUUêSÇàBààôZ]ö[‹ìX\õö[ôÀúôX€‹ôòYJà[ùûTÿ€‹ôHHÀú‹⁄][€ãô[ùûTÿ€‹ôKù“[ù

Kà[ùûT\ŸHHÀú‹⁄][€ãô[ùûT\ŸKàŸ]\]X[]HH⁄[à¬àÀú‹⁄][€ãô[ùûTÿ€‹ôHèHLOàêJ»ÇàÀú‹⁄][€ãô[ùûTÿ€‹ôHèHOàêHÇàÀú‹⁄][€ãô[ùûTÿ€‹ôHèHÃOàêàÇà[ŸHOàê»ÇàKàòY[ô”[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYë[\H»î”PTï‘”íTTààKàX\öŸ]Ÿ[ù[Y[ùHX\öŸ]Ÿ[ù[Y[ùàõ€][]S]ô[Hõ€][]S]ô[àõ€[YT⁄Y€ò[Hõ€[YT⁄Y€ò[à\]ZY]U\ŸHÀõ\›\]ZY]U\ŸàXÿ\\ŸHÀõ\›Xÿ\à€\ï‹›HÀúÿYô]Kù‹€\î›àùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKà›\ìŸë^HH›\ìŸë^Kà^SŸïŸYZ»H^SŸïŸYZÀà€[YSZ[ù]\»H€Z[úÀàõ›Hõà
BààYà
€€KõYôXﬁX€Xõ›ò€€X›]ôKê€€X›]ôSX\õö[ôÀö\—[òXõY

JH¬à\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„$–””P’UëWH—Sà\—[òXõY]ùYK][ò⁄[ô»\ÿYõ‹à	›Àúﬁ[Xõ€HäBà€ÿò[ÿ€‹Kõ][ò⁄
\\‹]⁄\úÀú⁄YQYôôX›
H¬àûH¬à\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„$–””P’UëWH—S€‹õ›][ôH’TïQõ‹à	›Àúﬁ[Xõ€HäBàò[\]ZY]PùX⁄Ÿ]H⁄[à¬àÀõ\›\]ZY]U\ŸWÃOàìRP‘ì»ÇàÀõ\›\]ZY]U\ŸçWÃOàî”PSÇàÀõ\›\]ZY]U\ŸLÃOàìRQÇà[ŸHOàìTë—HÇàBàà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„$–””P’UëWHÿ[[ô»\ÿY]\õì›]€€YHõ‹à	›Àúﬁ[Xõ€KããàäBà€€KõYôXﬁX€Xõ›ò€€X›]ôKê€€X›]ôSX\õö[ôÀù\ÿY]\õì›]€€YJà]\õï\HHâ›Àú‹⁄][€ãô[ùûT\Ÿ_W…›Àú‹⁄][€ãùòY[ô”[ŸKöYë[\H»î’SëTëà_Hãà\ÿ€›ô\ûT€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKà\]ZY]PùX⁄Ÿ]H\]ZY]PùX⁄Ÿ]à[XUô[ôHX\öŸ]Ÿ[ù[Y[ùà\’⁄[àH⁄›[X\õê\’⁄[ãàõ›Hõà€Z[ú»H€Z[úÀù—›XõJ
Bà
Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„$–””P’UëWH\ÿY]\õì›]€€YH”ëHõ‹à	›Àúﬁ[Xõ€HäBàà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„$–””P’UëWHÿ[[ô»\ÿYòYH—Sõ‹à	›Àúﬁ[Xõ€KããàäBà€€KõYôXﬁX€Xõ›ò€€X›]ôKê€€X›]ôSX\õö[ôÀù\ÿYòYJà⁄YHHî—Sãàﬁ[Xõ€HÀúﬁ[Xõ€àZ[ùHÀõZ[ùà[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYë[\H»î’SëTëàKà€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKà\]ZY]U\ŸHÀõ\›\]ZY]U\ŸàX\öŸ]Ÿ[ù[Y[ùHX\öŸ]Ÿ[ù[Y[ùà[ùûTÿ€‹ôHHÀú‹⁄][€ãô[ùûTÿ€‹ôKù“[ù

Kà€€ôöY[òŸHHLàõ›Hõà€Z[ú»H€Z[úÀù—›XõJ
Kà\’⁄[àH⁄›[X\õê\’⁄[ãà\\ì[ŸHH\‘\\îï

Bà
Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„$–””P’UëWH\ÿYòYH—S”ëHõ‹à	›Àúﬁ[Xõ€HäBàà€€X›]ôP[ò[]X‹ÀúôX€‹ô]\õï\ÿY

Bàà\úõ‹ìŸŸŸ\ãôXùY ê€€X›]ôSX\õö[ô»ãàº'‰È\ÿYYà	›Àúﬁ[Xõ€H	⁄Yä⁄›[X\õê\’⁄[äHï“Sàà[ŸHì‘‘»üH	‹õù“[ù

_IHäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äê€€X›]ôSX\õö[ô»ãï\ÿY\úõ‹àõ‹à	›Àúﬁ[Xõ€Nà	ŸKõY\‹ÿYŸ_HãJBàBàBàH[ŸH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãº'„$–””P’UëWH—Sà\—[òXõYYò[ŸK⁄⁄\[ô»\ÿYõ‹à	›Àúﬁ[Xõ€HäBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY êôZ]ö[‹ìX\õö[ô»ãúôX€‹ôòYH\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBààò[€\‹⁄YöXÿ][€àH⁄[à¬à\‘ÿ‹ò]⁄òYHOàî–‘êU“Çà⁄›[X\õê\’⁄[àOàï“SàÇà⁄›[X\õê\”‹‹»Oàì‘‘»Çà[ŸHOàïSí”ì’”àÇàBààòYRYò€‹ŸY
öXŸKõõôX\€€äBàòYRYò€\‹⁄YöYY
€\‹⁄YöXÿ][€ãYà
\‘ÿ‹ò]⁄òYJHù[[ŸH⁄›[X\õê\’⁄[äBàÿ[]‹⁄][€ìÿ⁄ÀúôX€‹ô€‹ŸJìY[YHãÀú‹⁄][€ãò€‹›€€
BààÀ»çKéKéNàŸ[ùY[ù\ú€€ò[]HôXX›¬àûH¬àYà
⁄›[X\õê\’⁄[äHŸ[ùY[ù\ú€€ò[]Kõ€ïòYU⁄[äòYRYúﬁ[Xõ€õÀú‹⁄][€ãùòY[ô”[ŸK
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YJH»L
Bà[ŸHYà
⁄›[X\õê\”‹‹ HŸ[ùY[ù\ú€€ò[]Kõ€ïòYS‹‹ òYRYúﬁ[Xõ€õÀú‹⁄][€ãùòY[ô”[ŸKôX\€€äBàHÿ]⁄
Œà^Ÿ\[€äHﬂBààòYSYôXﬁX€Kò€‹ŸY
òYRYõZ[ùöXŸKõôX\€€äBàòYSYôXﬁX€Kò€\‹⁄YöYY
òYRYõZ[ù€\‹⁄YöXÿ][€ãYà
\‘ÿ‹ò]⁄òYJHù[[ŸH⁄›[X\õê\’⁄[äBàòYSYôXﬁX€Kò€X\îõ‹‹ÿ[òX⁄⁄[ô òYRYõZ[ù
BÇàÀ»çKéKéNàôYYY[YH\\àòYH[ù»çòYS\‹€€îôX€‹ô\à8°§à›ò]YﬁUù\›RBàûH¬àò[òY[ô”[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYêõ[ö»»î’SëTëàBàÀ»çKåçéNH8†%ôX[ÿ]\ÿ[€€ù^[ú›XYŸà]\ò[ÀàH€õÿ⁄¬àÀ»›[\Y[ùûTôY⁄[YOTíT“◊””ã[ùûTŸ\‹⁄[€èS—ëó“’TîÀù\›ÿ€‹ôOLçKàÀ»úòY⁄[]Tÿ€‹ôOLå»[ô‹ùõ€[“X]Lå»€àUëTñH\‹€€ã€»BàÀ»ôY⁄[YKYö]^X›][€ã\]X[]H[ôò\úò]]ôK\\ú⁄\›[òŸHY[[‹ûH[ô\¬àÀ»\»ôX€‹ô\à^\›»»ŸY\Ÿ\\ò]HYõ›[ô»»Ÿ\\ò]KÇàò[\‹€€ê›H€€KõYôXﬁX€Xõ›ùçõY]KïòYS\‹€€îôX€‹ô\ãõ]ôP€€ù^éNJà›ò]YﬁHHòY[ô”[ŸKX\öŸ]HìQSQHãﬁ[Xõ€HòYRYúﬁ[Xõ€àZ[ùHòYRYõZ[ùà^X›Yö[öXŸHHÀú‹⁄][€ãô[ùûTöXŸKàÿ\\ôU[YHHÀú‹⁄][€ãô[ùûU[YKà
Bà€€KõYôXﬁX€Xõ›ùçõY]KïòYS\‹€€îôX€‹ô\ãò€€\]S\‹€€äà€€ù^H\‹€€ê››]€€YT›HõàYôT›HYà
Àú‹⁄][€ãöY⁄\›öXŸHà	âàÀú‹⁄][€ãô[ùûTöXŸHà
Bà

Àú‹⁄][€ãöY⁄\›öXŸHHÀú‹⁄][€ãô[ùûTöXŸJH»Àú‹⁄][€ãô[ùûTöXŸH
àL
H[ŸHõò€Ÿ\òŸP]X\›
å
KàXYT›Hõò€Ÿ\òŸP][‹›
å
Kà€ŸX»H

ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YJH»L
Kù“[ù

Kà^]ôX\€€àHôX\€€ãX›X[ö[öXŸHHöXŸBà
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBààYà
ôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ô\›öXù][€àäJH¬àö[ò[X⁄\⁄[€ëÿ]KúôX€‹ô\›öXù][€ë^]
òYRYõZ[ù
Bà€ìŸ º'Ê™»\›öXù][€à€€€›€éà	›Àúﬁ[Xõ€Hõÿ⁄ŸYõ‹àåMå»ãòYRYõZ[ù
BàBààò[ôX\€€ì›Ÿ\àHôX\€€ãõ›Ÿ\òÿ\ŸJ
Bà⁄[à¬àôX\€€ì›Ÿ\ãò€€ùZ[ú ò€€\ŸHäHôX\€€ì›Ÿ\ãò€€ùZ[ú õ\WŸòZ[àäHOà¬àôY[ùûQ›X\ôõ€ì\]ZY]P€€\ŸJòYRYõZ[ù
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HH\]ZY]H€€\ŸH
[Z[äHãòYRYõZ[ù
BàBàôX\€€ì›Ÿ\ãò€€ùZ[ú ô\›öXù][€àäHôX\€€ì›Ÿ\ãò€€ùZ[ú ù⁄[WŸ[\äHôX\€€ì›Ÿ\ãò€€ùZ[ú ô]óŸ[\äHOà¬àôY[ùûQ›X\ôõ€ë\›öXù][€ë]X›Y
òYRYõZ[ù
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HH\›öXù][€à]\õà
€Z[äHãòYRYõZ[ù
BàBàôX\€€ì›Ÿ\ãò€€ùZ[ú ú›‹€‹‹»äHOà¬àôY[ùûQ›X\ôõ€î›‹‹‹“]
òYRYõZ[ùõ
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HH›‹‹‹»]
õZ[äHãòYRYõZ[ù
BàBàÀ»çKéKåLÃéH8†%ëKQSïñHPR»íV
]ôH€ò\⁄›KååÃéM KàHé^]ò[Z[BàÀ»Xô[»Ÿ]ô\ôH‹‹Ÿ\»\»ùéÿÿ]\›õ‹X◊€‹‹»à»ùé‹Ÿ]ô\ôW€‹‹»ã⁄X⁄àÀ»X]⁄Yì”ëHŸàHÿ\Ÿ\»Xõ›ôH8†%€»Hÿ]\›õ‹X»‹‹»\õYYì»ÿ⁄€›][ôàÀ»Hõ›[ú›[ùHôKXõ›Y⁄Hÿ[YHXYZ[ù
NMöêÕã–UõZúã“çHXX⁄àÀ»ôKY[ù\ôY⁄][àÀLM‹»[ôYYYÿZ[äKà\ŸHTëH›‹[‹‹ÀX€\‹»^]Œ»\õBàÀ»Hÿ[YHÿ⁄€›]€»ŸH›‹^Z[ô»ô\X]YZ][€à€àH€‹úŸKÇàôX\€€ì›Ÿ\ãò€€ùZ[ú òÿ]\›õ‹X»äHôX\€€ì›Ÿ\ãò€€ùZ[ú úŸ]ô\ôHäHOà¬àôY[ùûQ›X\ôõ€î›‹‹‹“]
òYRYõZ[ùõ
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HHÿ]\›õ‹XÀ‹Ÿ]ô\ôH‹‹»
õZ[äHãòYRYõZ[ù
BàBàBààÀ»çKéKåÃŒà€õHYX[ö[ôŸù[‹‹Ÿ\»öYŸŸ\àôY[ùûQ›X\ôàÿ‹ò]⁄òY\¬àÀ»
LIKãå	JH\ôHõ⁄\ŸH[ô⁄›[â›ÿ⁄»›]ù]\ôHôKY[ùöY\ÀÇàYà
õHLKå
H¬àôY[ùûQ›X\ôõ€ïòYS‹‹ òYRYõZ[ùõ
BàBààYŸSX\õö[ôÀõX\õëúõ€S›]€€YJàZ[ùHòYRYõZ[ùà^]öXŸHHöXŸKàõ\òŸ[ùHõàÿ\—^X›]YHùYKà
BààÀ»çKéKåŒL8†%]ô\û][ô»ô[›»\»Y[YKXúòZ[ã\‹X⁄YöX»
⁄[H¬àÀ»ôY⁄[YH»[€Y[ù[H»ò\úò]]ôH»[YS‹»‹õ‹‹’[»»€[YH¬àÀ»\]ZY]Q\»[ùûR[ù[YŸ[òŸH»^][ù[YŸ[òŸH[àÀ»ÿ[Xúò]Y€àY[YKXò\ŸH›]€€Y\ Kà›Xã]òY\à€‹Ÿ\»]\›ì’àÀ»€]H[Kà€€\]Y€òŸH\Yúõ€ùÿ]Yô[›ÀÇàò[‹’HH
Àú‹⁄][€ãùòY[ô”[ŸHŒààäKù\\òÿ\ŸJ
Bàò[‹“\”Y[YPò\ŸHH‹’Kö\–õ[ö 
H‹’HZ[àŸ]Ÿäàî“U”“Sàãî“U”“Só—VëT‘»ãî“U”“SëVëT‘»ãëVëT‘»ãê÷P”P»ãàîUPSUHãêìQP“TãêìQW–“TãàìS””î“’ãïëPT’TñHãê–T“—SàãîëT–SW‘”íTHãîì“ëP’‘”íTTàãàìPSíTSUQãëT“SïTàãï“SW—ì”’»ãê”‘UêQHãï–SU‘ëP”’ëTëQÇà
BàYà
‹“\”Y[YPò\ŸJH¬à[ùûR[ù[YŸ[òŸKõX\õëúõ€S›]€€YJòYRYõZ[ùõ€Z[ù]\Àù“[ù

JBàà^][ù[YŸ[òŸKõX\õëúõ€Q^]
òYRYõZ[ùôX\€€ãõ€Z[ù]\Àù“[ù

JBà^][ù[YŸ[òŸKúô\Ÿ]‹⁄][€äòYRYõZ[ù
BààûH¬àò[ÿ\‘⁄Y€ò[€‹úôX›H⁄[à¬àõàKåOàùYBàõMKåOàò[ŸBà[ŸHOàù[àBàYà
ÿ\‘⁄Y€ò[€‹úôX›OHù[
H¬à⁄[UòX⁄Ÿ\êRKúôX€‹ô⁄Y€ò[›]€€YJòYRYõZ[ùÿ\‘⁄Y€ò[€‹úôX›õ
BàBàHÿ]⁄
Œà^Ÿ\[€äHﬂBààÀ»çKéKåLMà8†%X\öŸ]ôY⁄[YPRKúôX€‹ôòYS›]€€YH[€»ÿ[YûBàÀ»YXÿ][€î›Xì^Y\êRKúôX€‹ôòYS›]€€YPX‹õ‹‹–[^Y\ú»ô[›ÀÇàÀ»ô[[›ôY\ôX›ÿ[»ô]ô[ù∞Â»ôX€‹ô[ô»\à\\à€‹ŸKÇààÀ»çKéKåLMà8†%[€Y[ù[TôYX›‹êRKò\úò]]ôQ]X›‹êRK[YS‹[Z^ò][€êRNÇàÀ»[ÿ[YûHYXÿ][€î›Xì^Y\êRKúôX€‹ôòYS›]€€YPX‹õ‹‹–[^Y\ú»ô[›ÀÇàÀ»ô[[›ôY\ôX›ÿ[»8†%Ÿ\ôH›XõKX€›[ù[ô»]ô\ûH\\à€‹ŸKÇààûH¬à[YS[ŸTÿ⁄Y[\ãúôX€‹ôòYS›]€€YJà[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYë[\H»î”PTï‘”íTTààKàõ›Hõà
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬àHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬àÀ»çKéKåLMà8†%\]ZY]Q\RKúôX€‹ô›]€€YH[€»[àT”ô[›»8†%ô[[›ôYÇàÀ»€X\ë[ùûS\]ZY]H\»›\ŸZŸY\[ôÀ[€õK›[ôYYY\ôKÇà\]ZY]Q\RKò€X\ë[ùûS\]ZY]JÀõZ[ù
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬àÀ»çKåçÃ8†%‹ôY]H[ùûK][YH‹õ‹‹À][»⁄Y€ò[]X›X[BàÀ»⁄\YHòYKàôX€€\][ô»]€‹ŸH‹ôY]»H‹õ€ô»XX⁄\ãÇàRP‹õ‹‹’[ÀúôX€‹ô›[\Y[ùûS›]€€YJÀõZ[ùõõàÀú‹⁄][€ãùòY[ô”[ŸJBàHÿ]⁄
Œà^Ÿ\[€äHﬂBàHÀ»[ô‹“\”Y[YPò\ŸHÿ]H
çKéKåŒL
BààÀ»çKéKåLMà8†%€[YS‹[Z^ô\êRKúôX€‹ô›]€€YH[€»[àT”ô[›ÀÇàÀ»ô[[›ôY\ôX›ÿ[8†%ÿ\»›XõKX€›[ù[ô»]ô\ûH\\à€‹ŸKÇààÀ»çKéKåLMà8†%⁄Ÿ[ï⁄[ìY[[‹ûKúôX€‹ôòYS›]€€YH[€»[àT”ô[›ÀÇàÀ»ô[[›ôY\ôX›ÿ[8†%ÿ\»›XõKX€›[ù[ô»]ô\ûH\\à€‹ŸKÇààûH¬àò[X\öŸ]Ÿ[ù[Y[ùHÀõY]Kô[XYò[ê[Y€õY[ùõ]»[XHOÇà⁄[à¬à[XKò€€ùZ[ú êïSäHOàêïSÇà[XKò€€ùZ[ú êëPTàäHOàêëPTàÇà[ŸHOàìëUUêSÇàBàBàà€€KõYôXﬁX€Xõ›ùåÀïå—[ô⁄[ôSX[òYŸ\ãúôX€‹ô›]€€YJàZ[ùHòYRYõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àõ›Hõà€[YSZ[ù]\»H€Z[ù]\Àù“[ù

Kà^]ôX\€€àHôX\€€ãà[ùûT\ŸHHÀú‹⁄][€ãô[ùûT\ŸKöYë[\H»ïSí”ì’”ààKàòY[ô”[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYë[\H»î’SëTëàKà\ÿ€›ô\ûT€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà[XUô[ôHX\öŸ]Ÿ[ù[Y[ùà
Bà€€KõYôXﬁX€Xõ›ùåÀïå—[ô⁄[ôSX[òYŸ\ãõ€î‹⁄][€ê€‹ŸY
òYRYõZ[ù
BàÀ»çKéKåLÕ»8†%Z\úõ‹àH€‹ŸH»Ÿ[‹[Z^ò][€êRH€»]¬àÀ»X›]ôT‹⁄][€ú»X\ÿ[â›õ›⁄]›[HXZ»»⁄[ö‹‘€€àÀ»ô]ŸY[à^]Ààô]ö[›\€H€‹ŸHÿ\»€õHÿ[Y[ú⁄YH€ôBàÀ»úò[ò⁄[àõ›Ÿ\ùöXŸH
Q“–‘íUP–S\ôŸ[òﬁJK€»[ûH^]àÀ»öXH›‹[‹‹ÀòZ[[ôÀ⁄Y€ò[õ\X[ùX[‘”]ÀÇàÀ»YùHX\\ùH[ôúõ⁄ŸHôKY[ùöY\»€àHÿ[YHZ[ùÇàûH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîŸ[‹[Z^ò][€êRKò€‹ŸT‹⁄][€äòYRYõZ[ùõ
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬à]X[ùY]öX‹ÀúôX€‹ôòYJàﬁ[Xõ€HÀúﬁ[Xõ€àZ[ùHÀõZ[ùàõ€€Hõàõ›Hõà€[YSZ[ù]\»H€[YSZ[úÀà[ùûT\ŸHHÀú‹⁄][€ãô[ùûT\ŸKà]X[]HHòYP€\‹⁄YöXÿ][€ãà
Bàà‹ùõ€[–[ò[]X‹Àúô[[›ôT‹⁄][€äÀõZ[ù
BààHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY î]X[ùY]öX‹»ãîôX€‹ô[ô»\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBààûH¬àò[€[YS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YBàò[\’⁄[àHõèHKåÀ»çKéKåNNà[öYöYYIHõ€‹Çàò[[ŸT›àHÀú‹⁄][€ãùòY[ô”[ŸBààò[^[ŸHHûH¬à[öYöYY[ŸS‹ò⁄\›ò]‹ãë^[ôY[ŸKùò[YSŸä[ŸT›äBàHÿ]⁄
Nà^Ÿ\[€äH¬à[öYöYY[ŸS‹ò⁄\›ò]‹ãë^[ôY[ŸKî’SëTëàBàà[öYöYY[ŸS‹ò⁄\›ò]‹ãúôX€‹ôòYJà[ŸHH^[ŸKà\’⁄[àH\’⁄[ãàõ›Hõà€[YS\»H€[YS\Àà
Bààò[›]€€YT›àHYà
\’⁄[äHï“Sàà[ŸHYà
õLãå
Hì‘‘»à[ŸHî–‘êU“Çà›\\êúòZ[ë[ö[òŸ[Y[ùÀù\]R[ú⁄Y⁄›]€€YJÀõZ[ù›]€€YT›ãõ
BàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ì[ŸS‹ò⁄\›ò]‹àãîôX€‹ô[ô»\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBààûH¬àò[ôX\›\ûQ^]⁄Y€ò[H⁄[à¬àôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú úõŸö]äHôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ù\ôŸ]äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[ïR—W‘ì—íUàôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ú›‹äHôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú õ‹‹»äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[î’‘”‘‘¬àôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ùòZ[äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[ïêRSSë◊‘’‘àôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ù[YHäHôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ö€äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[ïSQW—VUà[ŸHOà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[í”àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKò€‹ŸT‹⁄][€äòYRYõZ[ùöXŸKôX\›\ûQ^]⁄Y€ò[
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬àò[õYP⁄\^]⁄Y€ò[H⁄[à¬àôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú úõŸö]äHôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ù\ôŸ]äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[ïR—W‘ì—íUàôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ú›‹äHôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú õ‹‹»äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[î’‘”‘‘¬àôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ùòZ[äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[ïêRSSë◊‘’‘àôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ù[YHäHôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ö€äHOàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[ïSQW—VUà[ŸHOà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[í”àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ùöXŸKõYP⁄\^]⁄Y€ò[
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBÇàÀ»çKéKéMå»8†%SíUëTî–S’PãUêQTà”‘—H
\\îŸ[ôX\€€ãZŸ^]€‹ô]
KÇàÀ»ÿ[YHö^\»H⁄[ã€‹‹»õÿ⁄»Xõ›ôKà⁄]›]\À[à^]öXBàÀ»’íP’‘”ÀLLêTQ“Të—ì”‘ó‘’‘SQW—VU]ÀàX]ô\¬àÀ»[€€ú⁄›‘]X[]K”X[ö\[]Y—\[ù\ã‘õ⁄ôX›€ö\\ã‘⁄]€⁄[ë^ô\‹¬àÀ»ö]ò]H‹⁄][€ú»]ôHOà›Xã]òY\à›ŸY\õ€XöY\ÀÇàò[çàHôX\€€ãõ›Ÿ\òÿ\ŸJ
Bàò[\‘õŸö]àHçãò€€ùZ[ú úõŸö]äHçãò€€ùZ[ú ù\ôŸ]äHçãò€€ùZ[ú ùZŸW‹õŸö]äBàûH¬àò[Q^HYà
\‘õŸö]äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKë^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ùöXŸKQ^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[Q^HYà
\‘õŸö]äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKë^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ùöXŸKQ^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[X[ë^HYà
\‘õŸö]äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKìX[ö\^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKìX[ö\^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ùöXŸKX[ë^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[\^HYà
\‘õŸö]äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKë\^]⁄Y€ò[îëP”’ëTñW’Të—Uà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKë\^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKò€‹ŸQ\
òYRYõZ[ùöXŸK\^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[€ë^H€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKë^]⁄Y€ò[
à⁄›[^]HùYKà^]›HLàôX\€€àHYà
\‘õŸö]äHïR—W‘ì—íUà[ŸHî’‘”‘‘»ãàò[ö»H€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKî€ö\\îò[öÀîSëSëÀà
Bà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKò€€\]SZ\‹⁄[€äòYRYõZ[ùöXŸK€ë^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[^^HYà
\‘õŸö]äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àë^]⁄Y€ò[ïR—W‘ì—íUÃÃà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àô^]öYJòYRYõZ[ùöXŸK^^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBÇàÀ»‹⁄][€à[ôXYH€X\ôYôYõ‹ôHX]ûHX\õö[ô»ò[õ›]
çKéKåLLÃ KÇàÀõ\›^]»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
BàÀõ\›^]öXŸHHöXŸBàÀõ\›^]õ›HõàÀõ\›^]ÿ\’⁄[àHõèHKåÀ»çKéKåNBàÀ»çKéKåçMéàX\ö»€‹ŸY[à\ú⁄\›[ùÿ[]Y[[‹ûBàûH»ÿ[]⁄Ÿ[ìY[[‹ûKúôX€‹ô^]
ÀõZ[ùÀúﬁ[Xõ€öXŸKõôX\€€äHHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãúôX€‹ôŸ[€€ôö\õYY
ÀõZ[ùÀúﬁ[Xõ€öXŸKõôX\€€äHHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬à‹⁄][€î\ú⁄\›[òŸKúÿ]ôT‹⁄][€ä BàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãî‹⁄][€à\ú⁄\›[òŸHô[[›ò[\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBÇàYà
Z\‘ÿ‹ò]⁄òYJH¬à⁄Y›”X\õö[ô—[ô⁄[ôKõ€ì]ôUòYQ^]
àZ[ùHòYRYõZ[ùà^]öXŸHHöXŸKà^]ôX\€€àHôX\€€ãà]ôTõ€€Hõà\’⁄[àHõèHKåÀ»çKéKåNNà[öYöYYIHõ€‹ãà
BàBààûH¬àò[Ÿ]\]X[]T›àH⁄[à
òYP€\‹⁄YöXÿ][€äH¬àîïSìëTààOàëV—SSïÇàêíQ◊’“SààOàëV—SSïÇàï“SààOàë””—Çàî–‘êU“àOàìëUUêSÇàì‘‘»àOàî”‘àÇàêêQàOàêêQÇà[ŸHOàìëUUêSÇàBààò[›\úô[ù€\ê€›[ùHÀö\›‹ûKõ\›‹ìù[

OÀö€\ê€›[ùŒààò[›\úô[ùõ€[YHHÀö\›‹ûKõ\›‹ìù[

OÀùõ€ŒàåàÀ»çKéKåLçŒà›X\ôYÿZ[ú›[úŸ][ùûU[YH
ò]»\ÿ⁄XZ»8°§àMã^YX\à€
BàÀ»çKåçççç8†%LLÃ»[ù[ù[€ò[H€X\ú»Àú‹⁄][€àôYõ‹ôH\¬àÀ»ŸôõÿYYX\õö[ô»€‹öÀàôXYH[[]]XõHôKX€‹ŸH€ò\⁄›¬àÀ»›\ù⁄\ŸH]ô\ûH‹X⁄X[\›\õZ[ò[\»⁄[[ùHX\õôY\¬àÀ»’SëTë”QSQH⁄]ô\õ»[ùûHX€€õ€ZX‹ÀÇàò[[ùûU[YTÿYôQYHHYà
‹Àô[ùûU[YHàWÃÃÃÃ
Bà‹Àô[ùûU[YH[ŸHﬁ\›[Kò›\úô[ù[YSZ[\ 
Bàò[€[YQ›XõHH
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH[ùûU[YTÿYôQYJH»ååàò[\õﬁ⁄Ÿ[êYŸSZ[ù]\»H€[YQ›XõH
»Kåàò[XZ‘õHYà
‹Àô[ùûTöXŸHà	âà‹ÀöY⁄\›öXŸHà
H¬à

‹ÀöY⁄\›öXŸHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLåàH[ŸHõààò[\òXõQ]ô[ùYçç»HòYT€ò\ôX€€õ€ZX—]ô[ùYàò[›]€€YQ]HH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀëYXÿ][€î›Xì^Y\êRKïòYS›]€€YQ]JàZ[ùHòYRYõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à⁄Ÿ[ìò[YHHÀõò[YKàõ›Hõà€[YSZ[ù]\»H€[YQ›XõKà[ùûU[YS\»H[ùûU[YTÿYôQYKà^]ôX\€€àHôX\€€ãà[ùûT\ŸHH‹Àô[ùûT\ŸKöYë[\H»ïSí”ì’”ààKàòY[ô”[ŸHH‹ÀùòY[ô”[ŸKöYë[\H»î’SëTëàKà\ÿ€›ô\ûT€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKàŸ]\]X[]HHŸ]\]X[]T›ãà[ùûSXÿ\\ŸH‹Àô[ùûSXÿ\ùZŸRYà»]àHŒà
‹Àô[ùûS\]ZY]U\Ÿ
àäKà^]Xÿ\\ŸHÀõ\›Xÿ\à⁄Ÿ[êYŸSZ[ù]\»H\õﬁ⁄Ÿ[êYŸSZ[ù]\Ààù^Tò][‘›HÀö\›‹ûKõ\›‹ìù[

OÀòù^Tò][œÀù[Y\ L
HŒàLåàõ€[YU\ŸH›\úô[ùõ€[YKà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà€\ê€›[ùH›\úô[ù€\ê€›[ùà‹€\î›HÀù‹€\î›ŒàÀúÿYô]Kù‹€\î›ùZŸRYà»]èHåHŒàYà
‹Àö\‘\\î‹⁄][€äHå[ŸHLåà€\ë‹õ››ò]HHÀö€\ë‹õ››ò]Kà]ïÿ[]›Håàõ€ô[ô–›\ùôTõŸ‹ô\‹»HåàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKù—›XõJ
Kò€Ÿ\òŸP]X\›
å
Kà[XQò[î›]HHÀõY]Kô[XYò[ê[Y€õY[ùöYë[\H»ïSí”ì’”ààKà[ùûTÿ€‹ôHH‹Àô[ùûTÿ€‹ôKàöXŸQúõ€P]HåàX^ÿZ[î›HXZ‘õàX^ò]Ÿ›€î›H‹Àõ›Ÿ\›öXŸKõ]»›»OÇàYà
›»à	âà‹Àô[ùûTöXŸHà
H¬à

›»H‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLåàH[ŸHåàKà[YU‘XZ”Z[ú»H€[YQ›XõH
àçKàÀ»çKéKåÃåà\ö]ôHòY\î€›\òŸHúõ€HX›X[òY[ô»[ŸKõ›\ô€ŸYàòY\î€›\òŸHH⁄[à¬à‹ÀùòY[ô”[ŸKú›\ù’⁄]
ìS””î“’äHOàìS””î“’Çà‹ÀùòY[ô”[ŸHOHî“U”“SààOàî“U”“SàÇà‹ÀùòY[ô”[ŸHOHêìQW–“Tà‹ÀùòY[ô”[ŸHOHêìQP“TàOàêìQP“TÇà‹ÀùòY[ô”[ŸHOHîUPSUHàOàîUPSUHÇà‹ÀùòY[ô”[ŸHOHïëPT’TñHàOàïëPT’TñHÇà‹ÀùòY[ô”[ŸHOHëT“SïTààOàëT“SïTàÇà‹ÀùòY[ô”[ŸHOHìPSíTSUQàOàìPSíTSUQÇà‹ÀùòY[ô”[ŸHOHëVëT‘»àOàëVëT‘»Çà‹ÀùòY[ô”[ŸHOHîì“ëP’‘”íTTààOàîì“ëP’‘”íTTàÇà[ŸHOàìQSQHÇàKà‹‹‘ôX\€€àHYà
õLãå
HôX\€€à[ŸHàãà[ùûP€‹›€€H‹Àò€‹›€€àõ€€Hõà^X›][€ì[ŸHHYà
‹Àö\‘\\î‹⁄][€äHú\\àà[ŸHõ]ôHãàõ€Ÿî›]HHòÿ[õ€öXÿ[Ÿö[ò[^ôYãàX€€õ€ZX—]ô[ùYH\òXõQ]ô[ùYççÀà
BÇàò[]Y]YYçç»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[X€€õ€ZX—]ô[ùçåÕKòYù\ê€€[Z]Y
\òXõQ]ô[ùYçç H¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀëYXÿ][€î›Xì^Y\êRKúôX€‹ôòYS›]€€YPX‹õ‹‹–[^Y\ú ›]€€YQ]JBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKê]]€õ€[›\”Y]T€XﬁKúôX€‹ô›]€€YJÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò î”P÷W“PQ—TëP’—êSì’U‘’TëT‘—QÕMàäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî⁄Y€ò[]X[]UòX⁄Ÿ\ãúôX€‹ô›]€€YJÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKì^Y\êúòZ[ãúôX€‹ô›]€€YP[
ÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî›ò]YﬁR\›\⁄\—[ô⁄[ôKúôX€‹ô›]€€YJÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò íSïSQ—Sê—W—TêPìW‘—USQSï—SUëTëQÕçç»äHHÿ]⁄
Œàõ›ÿXõJHﬂBà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„§»TïêTëîêRSéà\òXõH›]€€YH	›Àúﬁ[Xõ€H]ô[ùIŸ\òXõQ]ô[ùYççÀùZŸJç
_HìI‹õù“[ù

_IHäBàBàYà
\]Y]YYçç H¬àûH»\[[ôRX[€€X›‹ãõXô[[ò íSïSQ—Sê—W—TêPìW‘—USQSï”RT‘“Së◊Õçç»äHHÿ]⁄
Œàõ›ÿXõJHﬂBà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãí\ùò\ô›]€€YHôZôX›YàZ\‹⁄[ô»ÿ[õ€öXÿ[]ô[ù	\òXõQ]ô[ùYçç»äBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãº'„§»\ùò\ôúòZ[àôX€‹ô[ô»òZ[Yà	ŸKõY\‹ÿYŸ_HäBàBààÀ»çKéKåçà›[\€€€›€à€»[ö]ô\úÿ[ÿ]Hõÿ⁄‹»[[YYX]HôKY[ùûBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêõ›Ÿ\ùöXŸKúôXŸ[ùP€‹ŸY\÷›ÀõZ[ùHHﬁ\›[Kò›\úô[ù[YSZ[\ 
BàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S—”ëHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€⁄]⁄Y€ò[⁄Y]úôX€‹ô\⁄‘›YŸJÿ[õ€öXÿ[\õZ[ò[‹⁄][€ççLãõ[ôKî—S–””ëíTìQQã\õZ[ò[YçMJHHÿ]⁄
Œàõ›ÿXõJHﬂBÇàô]\õàŸ[ô\›[îTTó–””ëíTìQQàHö[ò[H¬àÀ»çKéKçÃåàô[X\ŸH[ô\àS^]]»
õ‹õX[
»^Ÿ\[€à
»⁄]›€äBàò[\\î›]HHûH»\\î‹⁄][€ê€‹ŸP]]‹ö]Kú›]SŸäîTTàãÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJH»ù[BàYà
\\î›]HOH\\î‹⁄][€ê€‹ŸP]]‹ö]Kî›]Kê”‘—W‘ëTUQT’Q\\î›]HOH\\î‹⁄][€ê€‹ŸP]]‹ö]Kî›]Kê”‘“Së H¬àûH»\\î‹⁄][€ê€‹ŸP]]‹ö]KõX\ö—òZ[Y
îTTàãÀõZ[ùÀúﬁ[Xõ€îTTó‘—S—VUQ’“U’U–”‘—NâôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàô[X\ŸT\\îŸ[ÿ⁄ ÀõZ[ù
BàÀ»çKåççMH0©‘—S—”‘ó”RQ‘êUS”à8†%YàHô\Ÿ\ùôY–T»ô]ô\ÇàÀ»ò[ú⁄][€ôY»”‘—Q
ù[ò›[€àô]»»ô]\õôYX\õHYù\ÇàÀ»ô\Ÿ\ùôJKXò[ô€àHô\Ÿ\ùò][€à€»H‹⁄][€àÿ[àô]ûKÇàûH¬àYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMõYôXﬁX€J\õZ[ò[YçMJHOBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMìYôXﬁX€Kê”‘“Së H¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMàòXò[ô€ï\õZ[ò[Ÿ[
\õZ[ò[YçMKú\\îŸ[Ÿ^]Y›⁄]›]ÿ€€ôö\õNâôX\€€àäBàBàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîTTó‘—S”–“◊‘ëSPT—QãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBÇàö]ò]Hù[à]ôTŸ[
Œà⁄Ÿ[î›]KôX\€€éà›ö[ôÀàÿ[]à€€[òUÿ[]ÿ[]€€à›XõKàY[ù]NàòYRY[ù]O»Hù[
NàŸ[ô\›[¬à^X›][€îõ€›ÿ]\ŸUòXŸKúŸ[
ìUëW‘—S—SïñHãÀúôX\€€èIôX\€€àÿ[]€€Iÿ[]€€‹‘]OI›Àú‹⁄][€ãú]U⁄Ÿ[üH[ùûOI›Àú‹⁄][€ãô[ùûTöXŸ_HY⁄I›Àú‹⁄][€ãöY⁄\›öXŸ_HäBàÀ»çKåçéLå8†%›[\H^]úòZ[à€àH]ôH\õZ[ò[]€ÀàBàÀ»€›[\⁄]HõŸH[ú⁄YHHZ[ãZ€[ô\›YYô\à⁄X⁄‹À€¬àÀ»[ûH]ôH^]]⁄⁄\Y‹ŸH
[Y\ôŸ[òﬁH\]ZY]KôX€€ò⁄[\ÇàÀ»ô\]Y]YKÿ[]^ô\õ»€X[ù\
H[€»òZ[ôYõ›[ôÀàôK\›[\[ô»\¬àÀ»ÿYôNà›[\

H›ô\ù‹ö]\»[ô[ô÷€Z[ùKÇà›[\[öYöYY^]õ‹ê€‹ŸMéLå
ÀôX\€€äBàÀ»çKåççMH0©‘—S—”‘ó”RQ‘êUS”à8†%–T»ô\Ÿ\ùôHëQì‘ëH[ûH]ôBàÀ»⁄YHYôôX›àõ[öÀ›[ö€õ›€à‹⁄][€íYOàòZ[€‹ŸY
ô]\õÇàÀ»SëPQW–”‘—Q
Kàô]ô[ù»÷éM\›[Hô\X]]ôH—S»úõ€BàÀ»]ô\àôXX⁄[ô»ÿ\⁄]]][€à»õ›\õò[»ô]ÿ\ôÇàÀ»çKåçÃLéH8†%T—HHïVKP””SRUQSê“‘ãì’HêPîíP–UQQÇàÀ¬àÀ»‹⁄][€íYŸä
H[ô»[àŒàâ€[Ÿ_Nâ€Nâù[íY\⁄òà⁄[à]ÿ[õõ›àÀ»ö[ôHÿ[õ€öXÿ[‹[à‹⁄][€àûHZ[ù]SïëSï»[àY8†%[ôàÀ»ô\Ÿ\ùôU\õZ[ò[Ÿ[\»ô]ô\àŸY[à]Y€»]ô]\õú¬àÀ»ëRëP’Q’Sí”ì’”à[ô\»Ÿ[òZ[»€‹ŸYà€àH‹\ò]‹â‹»KåçÃLéàÀ»]öXŸH]ÿ\»]ô\ûH\õZ[ò[Ÿ[àô\Ÿ\ùôYLôZèNã⁄]àÀ»TìRSêS‘—S’Sí”ì’”ó‘‘“US”óÕçMHãÇàÀ¬àÀ»Hõ€Ÿà\»[àZ\à›€àŸÀàﬁ\ô€é^\ñàÿ\»õ›Y⁄]NçNNåç»\¬àÀ»Y\[\åÃŒMLåà[ôH^]€ôHZ[ù]H]\ã€àH
ÕLç…HXZ»⁄]ö[ô¬àÀ»òX⁄»»MÀéIKÿ\»ôYù\ŸYYÿZ[ú›‹⁄][€íYSUëNëﬁ\ô€éKà€»Y»õ‹ÇàÀ»€ôH‹⁄][€éàHù^HôY⁄\›\ôYHôX[€ôKHŸ[ô\Ÿ\ùôY[ÇàÀ»[ùô[ùY€ôKÇàÀ¬àÀ»HTTà][ôXYHô\€€ô\»\»€‹úôX›H[ôôX€‹ô»⁄H8†%àÀ»çKåççåÕH0©Õã‹\ò]‹à\ôX›]ôNàúô\€€ôHH‹[à‹⁄][€à\⁄[ô¬àÀ»ÿ[õ€öXÿ[‹⁄][€íYö\ú›ããàÀú‹⁄][€ãú‹⁄][€íY\»BàÀ»ïVKX€€[Z]Y[ò⁄‹à]\ú⁄\›Y‹⁄][€îôY⁄\›ûH]X⁄\»]‘Sà8†%àÀ»ô]ô\àò[òX⁄»»Z[ù\ÿÿ[à⁄[à]\»ô\Ÿ[ù
»ô\€€òXõKààHUëBàÀ»]⁄⁄\Y›òZY⁄»HZ\úõ‹à[ô€»ô]ô\à€õ›\ôY]ÇàÀ¬àÀ»HZ\úõ‹àô[XZ[ú»Hò[òX⁄»õ‹à‹⁄][€ú»⁄]õ»ù[ù[YH[ò⁄‹ÇàÀ»
ôX€€ò⁄[\àô\]Y]Y\Àÿ[]\ôX€›ô\ôYõ›‹ Kà\»€õH›‹»HëPSYàÀ»úõ€HôZ[ô»\ÿÿ\ôY[àò]õ›\àŸàHﬁ[ù]X»€ôKÇàò[[ò⁄‹îY]ôMÃLéHHÀú‹⁄][€ãú‹⁄][€íYùö[J
Bàò[\õZ[ò[Y]ôMçMHHYà
[ò⁄‹îY]ôMÃLéKö\”õ›õ[ö 
JH¬àûH»\[[ôRX[€€X›‹ãõXô[[ò ìUëW‘—S‘Q—îì”W–ïVW–Sê“‘óÕÃLéHäHHÿ]⁄
Œàõ›ÿXõJHﬂBà[ò⁄‹îY]ôMÃLéBàH[ŸH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ë^X›]‹êÿ[õ€öXÿ[Z\úõ‹ççãú‹⁄][€íYŸäÀõZ[ù
BàBàò[ô\Ÿ\ùôTô\›[]ôMçMHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMàúô\Ÿ\ùôU\õZ[ò[Ÿ[
\õZ[ò[Y]ôMçMKôX\€€äBàYà
ô\Ÿ\ùôTô\›[]ôMçMHOH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€î›]SYŸ\ççMîô\Ÿ\ùôTô\›[îëT—TïëQ
H¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàìUëW‘—S’TìRSêS‘ëT—TïëW‘ëRëP’QÕçMHãàõZ[ùI›ÀõZ[ùùZŸJL
_HYI›\õZ[ò[Y]ôMçMKùZŸJLä_Hô\›[Iô\Ÿ\ùôTô\›[]ôMçMHôX\€€èIôX\€€àãà
Bà\[[ôRX[€€X›‹ãõXô[[ò ìUëW‘—S’TìRSêS‘ëT—TïëW‘ëRëP’QÕçMHäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùïTìRSêS‘ëT—TïëW‘ëRëP’QÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùïTìRSêS‘ëT—TïëW‘ëRëP’QÕçHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àôõ‹òŸTô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàÀ»çKåççà8†%ÿ[õ€öXÿ[UëHŸ[]]][€àÿÿ›\ú»€õH[ú⁄YBàÀ»Ÿ[ö[ò[^ò][€ê€€‹ô[ò]‹àYù\à[Y]Hõ›ô\»€€ú›[YYò]»]H[ô””õÿŸYYÀÇàò[òYRYHY[ù]HŒàòYRY[ù]SX[òYŸ\ãôŸ]‹ê‹ôX]JÀõZ[ùÀúﬁ[Xõ€Àú€›\òŸJBàò\à€‹ŸP]]‹ö]T⁄YŒà›ö[ôœ»Hù[ààò[»HŸô 
Bàò[‹»HÀú‹⁄][€ÇàôY⁄[ìY[YQ^X›][€î›X⁄ à⁄YHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKô^X›][€ãìY[YQ^X›][€îõ›]T›X⁄Àî⁄YKî—Sà»HÀà[[›[ù[àH‹Àú]U⁄Ÿ[ãà[[›[ù[îò]»Hú]U⁄Ÿ[èI‹‹Àú]U⁄Ÿ[üHãà€\YŸPú»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKö[ö]X[€\YŸPú ôX\€€äKàôX\€€àHôX\€€ãà\ôŸ[òﬁHH⁄[à¬àôX\€€ãò€€ùZ[ú îSíP»ãY€õ‹ôPÿ\ŸHHùYJHôX\€€ãò€€ùZ[ú ê–UT’ì‘HãY€õ‹ôPÿ\ŸHHùYJHôX\€€ãò€€ùZ[ú îïQ»ãY€õ‹ôPÿ\ŸHHùYJHôX\€€ãò€€ùZ[ú ëêRSàãY€õ‹ôPÿ\ŸHHùYJHOà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKô^X›][€ãìY[YQ^X›][€îõ›]T›X⁄Àï\ôŸ[òﬁKîSíP¬àôX\€€ãò€€ùZ[ú î’‘ãY€õ‹ôPÿ\ŸHHùYJHôX\€€ãò€€ùZ[ú íTë—ì”‘àãY€õ‹ôPÿ\ŸHHùYJHOà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKô^X›][€ãìY[YQ^X›][€îõ›]T›X⁄Àï\ôŸ[òﬁKî’‘”‘‘¬àôX\€€ãò€€ùZ[ú ìPSïPSãY€õ‹ôPÿ\ŸHHùYJHOà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKô^X›][€ãìY[YQ^X›][€îõ›]T›X⁄Àï\ôŸ[òﬁKìPSïPSà[ŸHOà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKô^X›][€ãìY[YQ^X›][€îõ›]T›X⁄Àï\ôŸ[òﬁKìì‘ìPSàKàÿ[]€€Hÿ[]€€à⁄Ÿ[êò[[òŸP]]‹ö]HHî—S–SS’Sï–UU‘íUW‘SëSë»ãàÿ[⁄]HHõ]ôTŸ[ãà
BàÀ»çKéKåçåà8†%‹õ›\[—S]ô[ù»⁄]HïVH]ô[ù»õ‹àHÿ[YHòYBàÀ»ûHô]\⁄[ô»H[ùûU[YH\»HŸ^\›€ôKÇàò[Ÿ[òYRŸ^HH]ôUòYSŸ‘›‹ôKöŸ^Qõ‹äÀõZ[ù‹Àô[ùûU[YJBÇàÀ»çKååÕéà8†%’‘—S‘SH”à”‘—Q÷ëTì»êP“—Tàì’‘ÀÇàÀ»ôYõ‹ôH⁄[ô»SñHŸàHX]ûHQTH»ëP”’ëTñW”–“»»–QëUH»][›BàÀ»€‹ö»]Y⁄][X][H€‹›»THÿ[»[ô‹ö]\»UëH—S’Tï¬àÀ»HŸÀ⁄X⁄»Hÿ[õ€öXÿ[‹›]ÿ[]òX⁄Ÿ\ãàYàHõ›»\¬àÀ»[ôXYH”‘—QZ]\éÇàÀ»
JHÿ[]€€ôö\õ\»ô\õ»8°§àù\›€‹ŸH⁄Ÿ[î›]H[ôXõ‹ùÇàÀ»
äHÿ[]\»õ»›\úô[ù[õ€Ÿà8°§àYô\à
»ÿ⁄Y[Hö[‹ö]HôX€€ò⁄[K¬àÀ»ì’[Z]UëH—S’TïYÿZ[ãàHŸ»‹[Hÿ\»[[Y\ö[ô»BàÀ»RH[ôHõ›\õò[⁄]õ»ôZ]ö[›\ò[⁄[ôŸKÇàûH¬àò[‹›õ›»H‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãôŸ][ùûJÀõZ[ù
Bàò[‹›€‹ŸYH‹›õ›»OHù[	âà
à‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àê”‘—Qà‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àê”‘—Q‘””–ñW–PUHà‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àê”‘—Q—VTìêSW”PSïPS‘’–Tà‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àî””–””ëíTìQQà
BàYà
‹›€‹ŸY	âà
‹›õ›œÀùZP[[›[ùŒàå
HHå
H¬àÀ»€€ôö\õH⁄]Ÿ[[[›[ù]]‹ö]HôYõ‹ôHX›[ô»
õÀX›\úô[ùZ[\õ€Ÿà›[Yô\ú KÇàò[ô\€€][€àHûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]]‹ö]Kúô\€€ôJÀõZ[ùÿ[]
BàHÿ]⁄
Œàõ›ÿXõJH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]]‹ö]Kîô\€€][€ãï[ö€õ›€àBàò[\÷ô\õ»Hô\€€][€à\»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]]‹ö]Kîô\€€][€ãñô\õ¬àò[\’[ö€õ›€àHô\€€][€à\»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]]‹ö]Kîô\€€][€ãï[ö€õ›€ÇàYà
\÷ô\õ H¬àÀ»ÿ[]€€ôö\õ\»[\H8°§à€‹ŸH⁄Ÿ[î›]H
»Xõ‹ùŸ[ÇàÀ»‹⁄][€ãö\”‹[à\»H€€\]Yõ‹\ùH8†%ô\õ»]U⁄Ÿ[ÇàÀ»\»[õ›Y⁄»X\ö»]€‹ŸYÇàûH»Àú‹⁄][€àH‹Àò€‹J]U⁄Ÿ[àHå
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàî—S–Pì‘ï–SëPQW–”‘—Q‘ëP””ê“SQãàõZ[ùI›ÀõZ[ùùZŸJLä_Hﬁ[OI›Àúﬁ[Xõ€HôX\€€èIôX\€€àà
¬àùòX⁄Ÿ\èI⁄‹›õ›œÀú›]\ﬂHZOI⁄‹›õ›œÀùZP[[›[ùHÿ[]VëTì»Çà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàYà
\’[ö€õ›€äH¬àÀ»çKååÕÃH8†%”‘—Q’êP“—Tó’Sí”ì’”ó‘î◊‘ëUñW””‘ÇàÀ»YàHÿ[õ€öXÿ[òX⁄Ÿ\ã€YŸ\à[ôXYHÿ\úöY\»[à]]‹ö]]]ôBàÀ»€‹ŸHõ€Ÿã[àîÀY[\K›[ö€õ›€àôXY]\›ì’ô\]Y]YHõ‹ô]ô\ãÇàÀ»Hô\‹ù⁄›ŸYòX⁄Ÿ\èP”‘—QZOLÿ[][L⁄]][\LMŒÇàÀ»ô]\õö[ô»êRSQ‘ëUñPPìH\ôHôKXYYHÿ[YHZ[ù¬àÀ»[ô[ô‘Ÿ[]Y]YH]ô\ûH€‹[ôŸ\VU–””‘ëSêU‘ó‘’SW‘ëT—UàÀ»€[Xö[ôÀà\»\»õ›HòZŸH€‹ŸNà]€õH\õZ[ò[H€X\ú»BàÀ»ÿÿ[⁄Ÿ[î›]H⁄[à‹›€YŸ\à[ôXYHÿ^\»€‹ŸYÇàò[YŸ\ê€‹ŸYHûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî‹⁄][€ê€‹ŸSYŸ\ãö\–€‹ŸY
ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJH»ò[ŸHBàò[‹›]]‹ö]]]ôP€‹ŸYH‹›õ›»OHù[	âà
à‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àê”‘—Q‘””–ñW–PUHà‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àê”‘—Q—VTìêSW”PSïPS‘’–Tà‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àî””–””ëíTìQQà
‹›õ›Àú›]\»OH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î›]\Àê”‘—Q	âàZ‹›õ›ÀúŸ[⁄Y€ò]\ôKö\”ù[‹êõ[ö 
JBà
BàYà
YŸ\ê€‹ŸY‹›]]‹ö]]]ôP€‹ŸY
H¬àûH»Àú‹⁄][€àH‹Àò€‹J]U⁄Ÿ[àHå[ô[ô’ô\öYûHHò[ŸJHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»[ô[ô‘Ÿ[]Y]YKúô[[›ôJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàî—S–Pì‘ï’êP“—Tó–”‘—Q”ì◊–’TîëSï“S‘ì”—ó’TìRSêSãàõZ[ùI›ÀõZ[ùùZŸJLä_Hﬁ[OI›Àúﬁ[Xõ€HôX\€€èIôX\€€àòX⁄Ÿ\èI⁄‹›õ›œÀú›]\ﬂHà
¬àùZOI⁄‹›õ›œÀùZP[[›[ùHYŸ\ê€‹ŸYIYŸ\ê€‹ŸY⁄YœI⁄‹›õ›œÀúŸ[⁄Y€ò]\ôOÀùZŸJ
HŒàõõ€ôHüHÇà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[êSëPQW–”‘—QàBàÀ»ÿ⁄Y[Hö[‹ö]HôX€€ò⁄[Hô^X⁄Àà»ì’‹[HUëH—S’TïÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôUÿ[]ôX€€ò⁄[\ãúôX€€ò⁄[Sõ› ÿ[]î—S‘UT—Q’êP“—Tó–”‘—Q…›ÀõZ[ùùZŸJ
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàî—S‘UT—Q’êP“—Tó–”‘—Q”ì◊–’TîëSï“S‘ì”—àãàõZ[ùI›ÀõZ[ùùZŸJLä_Hﬁ[OI›Àúﬁ[Xõ€HôX\€€èIôX\€€àà
¬àùòX⁄Ÿ\èI⁄‹›õ›œÀú›]\ﬂHZOI⁄‹›õ›œÀùZP[[›[ùH8†%ôX€€ò⁄[Hô\]Y\›YÇà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ï–RUSë◊–êSSê—W‘ì”—ÇàBàBàHÿ]⁄
Œàõ›ÿXõJH» àô]ô\àõÿ⁄»Ÿ[€à›X\ôòZ[\ôH
ã»BààÀ»çKéKçM^H8†%TP–UKQVU’PTë
‹X»][HJKÇàòYUô\öYöY\ãòX›]ôTŸ[⁄Y ÀõZ[ù
OÀõ]»^\›[ô‘⁄Y»OÇà€ìŸ ∏£Ï»—SQTNà	›Àúﬁ[Xõ€H8†%^\›[ô»Ÿ[⁄YœIŸ^\›[ô‘⁄YÀùZŸJMä_x†)à›[ô\öYûZ[ôÀà⁄⁄\[ô»ô]»	ôX\€€à^]àãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀà∏£Ï»QTNà^\›[ô»Ÿ[⁄YœIŸ^\›[ô‘⁄YÀùZŸJMä_x†)à›[[àõY⁄à⁄⁄\[ô»\Xÿ]H	ôX\€€à^]àãà⁄Y»H^\›[ô‘⁄YÀòY\ïY»HìQSQHãà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[êò[[òŸTõ€ŸïÿZ]›]KõX\ö’ÿZ][ô ÀõZ[ùÀúﬁ[Xõ€Œàè»ãêP’UëW‘—S‘“Q◊“Só—ìQ“âôX\€€àãù[ù[YQŸ[ô\ò][€àHûH»õ›ù[ù[YP€€ùõ€\ãò›\úô[ùŸ[ô\ò][€ä
HHÿ]⁄
Œàõ›ÿXõJH»JHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ï–RUSë◊–êSSê—W‘ì”—ÇàBÇàÀ»çKéKéMÕ»8†%çÀQàŸ[òZ[\ôR\›‹ûKú⁄›[õÿ⁄”ô^ô]ûH€€ú›[Y\ÇàÀ»
ÿ\»‘ìPSï\‹]HôX€‹ô

HôZ[ô»⁄\ôY[àçKéKéMé
KÇàÀ»‹\ò]‹à‹XŒàíYà‘Tî—W”“◊–ïU‘ì’UW—êRSQõ›ô\»⁄Ÿ[ú»Ÿ\ôBàÀ»€€ú›[YYûHHô]ö[›\»õ›]K»õ›ŸY\ô]ûZ[ô»Hÿ[YHŸ[àÇàÀ»X[ùX[[Y\ôŸ[ò⁄Y\»
PSïPS‘ïQÀ—êRSã––UT’ì‘JH[ò⁄õ›Y⁄8†%àÀ»^HX^HôYY»ö\ôH]ô[àYù\àH\úŸKS“»òZ[\ôHõ‹àÿYô]KÇàûH¬àYà
X€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKö\”X[ùX[[Y\ôŸ[òﬁJôX\€€äH	âÇàX€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKö\“\ôùY ôX\€€äH	âÇà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKú⁄›[õÿ⁄”ô^ô]ûJÀõZ[ù
JH¬àò[\›H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKõ]\›
ÀõZ[ù
Bà€ìŸ º'Ê‰H—Sì–“—Q–ñW—êRSTëW“T’‘ñNà	›Àúﬁ[Xõ€H\›I€\›Àö⁄[ôH⁄YœI€\›Àú⁄Y”‹ìù[ÀùZŸJMä_HôYù\⁄[ô»ô]ûH[ù[ôX€€ò⁄[\à€€ôö\õ\»ò[[òŸH[›ôYàãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀàº'Ê‰Hì–“—Q–ñW—êRSTëW“T’‘ñNà\›I€\›Àö⁄[ôH8†%»õ›ô]ûH[ù[ôX€€ò⁄[\ãàãà⁄Y»H\›Àú⁄Y”‹ìù[òY\ïY»HìQSQHãà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[êò[[òŸTõ€ŸïÿZ]›]KõX\ö’ÿZ][ô ÀõZ[ùÀúﬁ[Xõ€Œàè»ãëêRSTëW“T’‘ñW‘ëP””ê“STó’–RUâôX\€€àãù[ù[YQŸ[ô\ò][€àHûH»õ›ù[ù[YP€€ùõ€\ãò›\úô[ùŸ[ô\ò][€ä
HHÿ]⁄
Œàõ›ÿXõJH»JHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ï–RUSë◊–êSSê—W‘ì”—ÇàBàHÿ]⁄
Œàõ›ÿXõJHﬂBÇà€ìŸ º'Â!—S’Tïà	›Àúﬁ[Xõ€HôX\€€èIôX\€€à‹Àö\”‹[èI‹‹Àö\”‹[üH‹Àú]U⁄Ÿ[èI‹‹Àú]U⁄Ÿ[üH‹Àò€‹›€€I‹‹Àò€‹›€€HãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘’Tïàº'Â-UëH—S’TïôX\€€èIôX\€€à]OI‹‹Àú]U⁄Ÿ[ãôõ]

_Hÿ[]I›ÿ[]€€ôõ]

_H””ãà⁄Ÿ[ê[[›[ùH‹Àú]U⁄Ÿ[ãòY\ïY»HìQSQHãà
BÇàÀ»çKéKçM^åŒH8†%‹\ò]‹à‹X»][HNÇàÀ»ïôX\›\ûHôX€›ô\ûH»ôK\ôY⁄\›ò][€à]\›ì’öYŸŸ\à[ÇàÀ»[[YYX]HŸ[[ù[€ãX⁄Z[àò\⁄\»\»€€ôö\õYYàÇàÀ»ôX€›ô\ûSÿ⁄’òX⁄Ÿ\à€»HZ[ù⁄[àH‹⁄][€àÿ\¬àÀ»ôK\ôY⁄\›\ôY
KôÀàûHôX\›\ûH›ŸY\
H€»H^X›]‹àÿ[õõ›àÀ»Ÿ[[ù»›[HRHöXŸ\»ôYõ‹ôH⁄Z[àò\⁄\»\»ÿYYÇàÀ»çKéKçM^çH8†%‹‹ù[ö\›Xÿ[H][\[õÿ⁄»ö\ú›
ò]K[[Z]YàÀ»ÃÀ€Z[ùù[ú»€àS KàYà\»][\ö\ô\»Së⁄Z[àò\⁄\»\¬àÀ»õ›»õ›òXõH
»HõŸö]XõH][›H\»]òZ[XõKHÿ⁄»\¬àÀ»€X\ôYﬁ[ò⁄õ€õ›\€H[ôŸHò[õ›Y⁄»Hõ‹õX[Ÿ[àÀ»]à›\ù⁄\ŸHHX\õK\ô]\õàô[›»›[⁄X⁄‹»[ãÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îôX€›ô\ûSÿ⁄’[õÿ⁄Ÿ\ãõX^XôP][\[õÿ⁄ àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àÿ[]Hÿ[]àù\]\ê\RŸ^HHÀöù\]\ê\RŸ^Kà
BàHÿ]⁄
Œàõ›ÿXõJH» àô]ô\àúôXZ»HŸ[X⁄»
ã»BàYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îôX€›ô\ûSÿ⁄’òX⁄Ÿ\ãö\”ÿ⁄ŸY]ÿZ][ô–⁄Z[êò\⁄\ ÀõZ[ù
JH¬àÀ»çKéKåMLÃ8†%ëP”’ëTñH–“»UT’ëUëTàì–“»HíT“»VUà]»€õHõÿà\¬àÀ»»›‹HôX€›ô\ôY‹⁄][€à
€‹›€€Lﬁ[ù]X»[ùûTöXŸJHúõ€HôZ[ô¬àÀ»ì—íUUR—Sà€àH›[HRHöXŸHôYõ‹ôH⁄Z[àò\⁄\»ÿYÀàù][ÇàÀ»[ô\ùÿ]\àÿ[]XY‹Y⁄‹›€›[ëUëTà[õÿ⁄»
ûU[õÿ⁄»ô\]Z\ô\¬àÀ»õŸö]
K€»HLMIH\ôõ€‹à»ùY»»òZ[à»X[ùX[^]€›[ÇàÀ»[ò€€ô][€ò[êRSQ‘ëUñPPìH[ôHòY»õYõ‹ô]ô\ãà]ö[€]\»BàÀ»[ò€€ô][€ò[\ôYõ€‹àù[Kàö\⁄»^]»[ò⁄õ›Y⁄»€õH\ÿ‹ô][€ò\ûBàÀ»õŸö]]ZŸK›òZ[Ÿ[»Yô\ãàHÿ⁄»€\à[àH›[H[€»[ò⁄\¬àÀ»õ›Y⁄€»õ›[ô»\»ò\Y€àHXYò\⁄\»[ôYö[ö][KÇàò[ìÿ⁄»HôX\€€ãù\\òÿ\ŸJ
Bàò[\‘ö\⁄—^]Hìÿ⁄Àò€€ùZ[ú íTë—ì”‘àäHìÿ⁄Àò€€ùZ[ú î’‘”‘‘»äHàìÿ⁄Àò€€ùZ[ú î’‘äHìÿ⁄Àò€€ùZ[ú î’íP’‘”äHìÿ⁄Àò€€ùZ[ú ê–UT’ì‘HäHàìÿ⁄Àò€€ùZ[ú îïQ»äHìÿ⁄Àò€€ùZ[ú ëêRSàäHìÿ⁄Àò€€ùZ[ú î“U’”àäHàìÿ⁄Àò€€ùZ[ú ìPSïPSäHìÿ⁄Àò€€ùZ[ú ëSQTë—Sê÷HäHìÿ⁄Àò€€ùZ[ú ìTURQUHäBàò[ÿ⁄–YŸS\»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îôX€›ô\ûSÿ⁄’òX⁄Ÿ\ãõÿ⁄–YŸS\ ÀõZ[ù
Bàò[›[Sÿ⁄»Hÿ⁄–YŸS\»àåÃÀ»LZ[à8†%ò\⁄\»€X\õHõ›ÿY[ô¬àYà
\‘ö\⁄—^]›[Sÿ⁄ H¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îôX€›ô\ûSÿ⁄’òX⁄Ÿ\ãôõ‹òŸU[õÿ⁄ ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîëP”’ëTñW”–“◊‘Sê“’ì’Q“ãàõZ[ùI›ÀõZ[ùùZŸJLä_HôX\€€èIôX\€€àö\⁄œI\‘ö\⁄—^]›[S\œIÿ⁄–YŸS\»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»ò[õ›Y⁄»Hõ‹õX[Ÿ[]8†%ö\⁄»^]õÿŸYY»ì’ÀÇàH[ŸH¬à€ìŸ º'Â$à—SQëTîëQà	›Àúﬁ[Xõ€H8†%ëP”’ëTñW‘‘“US”ó”–“—Q
õŸö]]ZŸH€õJKàãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀàº'Â$àëP”’ëTñW”–“ŒàYô\úö[ô»\ÿ‹ô][€ò\ûH	ôX\€€à^][ù[⁄Z[àò\⁄\»ÿYYàãàòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàBÇàÀ»çKéKçM^åŒH8†%‹\ò]‹à‹X»][HNà[[›[ù]ö[€][€àÿ⁄ÀÇàÀ»Ÿ[[[›[ù]Y]‹àõYŸŸY\»Z[ùYù\àHô]ö[›\»Ÿ[€€ú›[YYàÀ»X]\öX[H[‹ôH[àô\]Y\›YàôYù\ŸHô]»Ÿ[»[ù[BàÀ»ôX€€ò⁄[\à€X\ú»Hÿ⁄»8†%Hô^õ‹ô[ú⁄X»^‹ù⁄[[àÀ»‹\ò]‹à⁄]\[ôYÇàYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]Y]‹ãö\”ÿ⁄ŸY
ÀõZ[ù
JH¬àò[àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]Y]‹ãôŸ]ö[€][€äÀõZ[ù
Bà€ìŸ º'Â$à—Sì–“—Qà	›Àúﬁ[Xõ€H8†%—S–SS’Sï’íS”US”àÿ⁄»à
¬àä›ô\èI›èÀõ›ô\ò€€ú›[YYò]ﬂH	»âKåYàãôõ‹õX]
èÀõ›ô\ò€€ú›[YY›Œàå
_IJKàãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàº'Â$à—S–SS’Sï’íS”US”ó”–“»X›]ôH8†%X[ùX[ôX€€ò⁄[\à[õÿ⁄»ô\]Z\ôYàãàòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBÇàÀ»çKéKåéLíVàYà[ô[ô’ô\öYûH\»›[ùYHù]⁄Ÿ[ú»^\›
]U⁄Ÿ[àà
BàÀ»Së]	‹»ôY[ààLå»⁄[òŸH[ùûKõ‹òŸKX€X\à[ô[ô’ô\öYûH\ôHôYõ‹ôHBàÀ»\”‹[à›X\ôà\»Z\úõ‹ú»Hõ›Ÿ\ùöXŸHö^àHô\öYûH€‹õ›][ôHù[ú»õ‹ÇàÀ»8¢iÃ»8†%YàŸI‹ôH›[[ô[ô»]Lå»]	‹»Yö[ö]]ô[H›X⁄À[ôŸH]\›àÀ»õ›Xõ‹ù[à\ôŸ[ùŸ[
ùYÀ›‹[‹‹ Hù\›ôXÿ]\ŸHô\öYöXÿ][€à›[YÇàÀ»\»\»HT’SëH—àQëSê—H8†%õ›Ÿ\ùöXŸH⁄›[]ôH[ôXYH€X\ôY]ÇàYà
‹Àú[ô[ô’ô\öYûH	âà‹Àú]U⁄Ÿ[ààå
H¬àò[[ô[ô–YŸS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
HH‹Àô[ùûU[YBàYà
[ô[ô–YŸS\»èHLåÃ
H¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãà∏¶®;Ó#»”UëT—S’ëTíQñW‘’P“◊H	›Àúﬁ[Xõ€H	‹[ô[ô–YŸS\»»L\»8†%õ‹òŸKX€X\ö[ô»[ô[ô’ô\öYûH[à]ôTŸ[àà
¬àîõÿŸYY[ô»⁄]Ÿ[»õ›X›‹⁄][€ãàäBàﬁ[ò⁄õ€ö^ôY
 H¬àÀú‹⁄][€àHÀú‹⁄][€ãò€‹J[ô[ô’ô\öYûHHò[ŸJBàBàBàBàÀ»ôK\ôXY‹»Yù\à›[ùX[[ô[ô’ô\öYûH€X\àXõ›ôBàò[‹–Yù\ïô\öYûP⁄X⁄»HÀú‹⁄][€ÇàYà
\‹–Yù\ïô\öYûP⁄X⁄Àö\”‹[äH¬àÀ»[€»[ô\»]U⁄Ÿ[àOH
Ÿ[ùZ[ô[Hõ›[ô»»Ÿ[
Bà€ìŸ º'Ê‰H—SPì‘ïQà‹⁄][€àõ›‹[à
]U⁄Ÿ[èI‹‹–Yù\ïô\öYûP⁄X⁄Àú]U⁄Ÿ[üK[ô[ô’ô\öYûOI‹‹–Yù\ïô\öYûP⁄X⁄Àú[ô[ô’ô\öYû_JHãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàêPì‘ïQ8†%‹Àö\”‹[èYò[ŸH
]OI‹‹–Yù\ïô\öYûP⁄X⁄Àú]U⁄Ÿ[üH[ô[ô’ô\öYûOI‹‹–Yù\ïô\öYûP⁄X⁄Àú[ô[ô’ô\öYû_JHãàòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[êSëPQW–”‘—QàBÇàò[[ùY‹ö]S⁄»HŸX›\ö]Kùô\öYûRŸ^\Z\í[ùY‹ö]Jÿ[]úXõX“Ÿ^PçNàÀùÿ[]Yô\‹ÀöYêõ[ö»»ÿ[]úXõX“Ÿ^PçNJBààYà
Z[ùY‹ö]S⁄ H¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àã∏¶®;Ó#»Ÿ^\Z\à[ùY‹ö]HZ\€X]⁄õ‹à—SH][\[ô»ô[ÿYããàäBààûH¬àò[ô[ÿYYÿ[]Hÿ[]X[òYŸ\ãò][\ôX€€õôX›

BàYà
ô[ÿYYÿ[]OHù[
H¬àò[ô]ûR[ùY‹ö]HHŸX›\ö]Kùô\öYûRŸ^\Z\í[ùY‹ö]Jàô[ÿYYÿ[]úXõX“Ÿ^PçNàÀùÿ[]Yô\‹ÀöYêõ[ö»»ô[ÿYYÿ[]úXõX“Ÿ^PçNBà
BàYà
ô]ûR[ùY‹ö]JH¬à\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àã∏ß!HŸ^\Z\àô[ÿYY›XÿŸ\‹Ÿù[KõÿŸYY[ô»⁄]Ÿ[äBàô]\õà]ôTŸ[
ÀôX\€€ãô[ÿYYÿ[]ô[ÿYYÿ[]ôŸ]€€ò[[òŸJ
KòYRY
BàBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àã∏¶®;Ó#»Ÿ^\Z\àô[ÿY][\òZ[Yà	ŸKõY\‹ÿYŸ_HäBàBàà€ìŸ ∏¶®;Ó#»—Sì–—QQSë»T‘UHSïQ‘íUH–TìíSëŒà	›Àúﬁ[Xõ€HãòYRYõZ[ù
Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àã∏¶®;Ó#»—Sì–—QQSëŒà[ùY‹ö]HòZ[Yù]][\[ô»[û]ÿ^Hõ‹à	›Àúﬁ[Xõ€HäBàBÇàò\à⁄Ÿ[ï[ö]»Hô\€€ôTŸ[[ö] À‹Àú]U⁄Ÿ[äBà€ìŸ º'‰‚à—SPïQŒà[ö]X[⁄Ÿ[ï[ö]»úõ€HòX⁄Ÿ\àH	⁄Ÿ[ï[ö]»ãòYRYõZ[ù
BÇàÀ»çKéKçåNà]ô\ûH]ôHŸ[ô\]Z\ô\»›\úô[ù^X››€ô\ä€Z[ùò[[òŸBàÀ»ô\öYöXÿ][€ãà€\àòX⁄Ÿ\ã[€õHò\›\]ÿÿYôõ€[ô»\»ô[[›ôY¬àÀ»õ›[ôYîÀ€›€ô\ãY[HôX€›ô\ûHô[›»\»H⁄[ô€H]]‹ö]KÇàò\à€€ôö\õYYŸ[ZT]HHåàûH¬à€ìŸ º'‰‚à—SPïQŒàô]⁄[ô»€ãX⁄Z[à⁄Ÿ[àò[[òŸ\ÀããàãòYRYõZ[ù
BàÀ»çKéKçÕLH8†%ò\ò€»HîÀTëP”’ëTñHôYúô\⁄ô[›»ÿ[àôXö[ôàÀ»YàHö\ú›ôXYô]\õôY[à[\HX\ÇàÀ¬àÀ»çKéKéNNXà8†%‹\ò]‹àöXYŸNàõ›[€‹YX]õ€›ÿ]\ŸKÇàÀ»Hﬁ[ò⁄õ€õ›\»î»
»ô]öY\»[ú⁄YHH\ô[ùàÀ»Ÿ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[ 
Hÿ[à[ô»õ‹àåLN»€àBàÀ»[]\À’ö]€à›[ŸY⁄[ô»\»€‹õ›][ôHSë€[ô»BàÀ»Ÿ[ÿ⁄»€»›XúŸ\]Y[ùŸ[»]Y]YH[ôYö[ö][KàYù\àHô]¬àÀ»›X⁄[ô‹»\‹]⁄\úÀíS»\»^]\›Y[ôHõ›€‹àÀ»\X\ú»XYàHõ›[ôY‹ò\\à
€€[òUÿ[]ö›
HŸôõÿY¬àÀ»Hÿ[»HYXÿ]YY[[€à€€⁄]H\ôH»ŸZ[[ôŒ¬àÀ»çKååÕÕŒ8†%[Y[›]›ò[ú‹‹ùòZ[\ôH\»SëUTìRSêUKõ›[à[\BàÀ»ÿ[]€ò\⁄›àô]ô\à€€ùô\ù]»Ÿ[]]‹ö]Hõ‹àôX€›ô\ôYõ›‹ÀÇàò\àÿ[]ôXY[ô]\õZ[ò]HHò[ŸBàò\à€ê⁄Z[êò[[òŸ\ŒàX\›ö[ôÀ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[⁄Ÿ[ê[[›[ùàHûH¬àÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY
WÃ
BàHÿ]⁄
Nàõ›ÿXõJH¬àÿ[]ôXY[ô]\õZ[ò]HHùYBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S’–SU‘ëPQ“SëUTìRSêUW”ì◊‘ëT–’QHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H\úèIŸKõY\‹ÿYŸOÀùZŸJLå
_HX›[€è]ÿZ]ÿ›\úô[ù›ÿ[]‹õ€ŸàäHHÿ]⁄
Œàõ›ÿXõJHﬂBà[\SX\

BàBàYà
€ê⁄Z[êò[[òŸ\Àö\—[\J
JH¬à€ìŸ ∏£ÏH—Sÿ[]ôXY[\K⁄[ô]\õZ[ò]Nà	›Àúﬁ[Xõ€H8†%›\úô[ùÿ[]õ€Ÿàô\]Z\ôYôYõ‹ôHúõÿYÿ\›ãòYRYõZ[ù
BàBàò\à⁄Ÿ[ë]HH€ê⁄Z[êò[[òŸ\÷›ÀõZ[ùBàò\àX\[\HH€ê⁄Z[êò[[òŸ\Àö\—[\J
BÇàÀ»çKéKçç»8†%îÀQSTHëT–’QH
‹\ò]‹ã\ô\‹ùYŸ[Z⁄[ùY KÇàÀ¬àÀ»ﬁ[\€Húõ€H\Ÿ\àõ‹ô[ú⁄X‹ŒàïVW’ëTíQíQQ”SëQ]OMNÀçMàÀ»]åŒåÕŒåNH8°§àUëH—S’TïåŒåŒNåNH8°§à—S—êRSQîî¬àÀ»ô]\õôYSTHò[[òŸHX\
ô]ûHKÃå
H8†%⁄[ô]ûHô^àÀ»X⁄»àõ‹àå
»ô]öY\Àà⁄Ÿ[ú»Ÿ\ôHYö[ö]]ô[H€ãX⁄Z[ÇàÀ»
ô\öYöYYöXHTTî—H]ù^JHù]]ô\ûHŸ[][\ÿ\¬àÀ»⁄[[ùHõÿ⁄ŸYôXÿ]\ŸHHî…‹»Ÿ]⁄Ÿ[êXÿ€›[ù–ûS›€ô\ÇàÀ»ÿ[ô]\õôYõ»Xÿ€›[ù»][
õ›Hô\õ»õ‹à\»Z[ùàÀ»H[ù\ôHX\ÿ\»[\H8†%€\‹⁄X»[]\À’ö]€à›ô\õÿYàÀ»ô\‹€úŸJKÇàÀ¬àÀ»€ôZ]ö[›\éàX\[\O]ùYH8°§àì–“»—S8°§àô]ûHô^X⁄¬àÀ»
õ‹ô]ô\ãX‹õ‹‹»\ô\›\ù Kà\»⁄[[ùH›ò[ô»BàÀ»‹⁄][€àX‹õ‹‹»[ûHî»õ\€ôŸ\à[àHå»òX⁄Ÿ\ÇàÀ»ù\›⁄[ô›ÀÇàÀ¬àÀ»ô]»ôZ]ö[›\éàX\[\O]ùYHSë⁄Ÿ[ï[ö]œå
úõ€Hô\öYöYYàÀ»ù^JH8°§àõÿŸYY⁄]òX⁄Ÿ\à]KàHî»ôZ[ô»úõ⁄Ÿ[àŸ\¬àÀ»ì’YX[àH⁄Ÿ[ú»\ôH€€ôKàHù\]\à][›H
»›ÿ\ÿ[àÀ»⁄[òZ[€X[õHYàH⁄Ÿ[ú»\ôHŸ[ùZ[ô[HZ\‹⁄[ôÀ€¬àÀ»ŸH€â›ö\⁄»ò[ŸHŸ[ÀàH‹ú[ãX[\ù]
[ôBàÀ»ô[› H›[ö\ô\»⁄[àHX\\»ì”ãQSTHù]\»Z[ùàÀ»\»XúŸ[ù8†%]	‹»HôX[ô^\õò[H€€‹ùYŸŸYàÿ\ŸKÇàYà
X\[\JH¬àÀ»çKéKçÕLH8†%ò\ŸMX⁄Ÿ]][HÕKàô]ö[›\€HŸHô]\õôYàÀ»êRSQ‘ëUñPPìH[[YYX][H[ô‹[[YY—S–ì–“—Q¬àÀ»õ‹ô[ú⁄X‹»€à]ô\ûHX⁄»8†%‹\ò]‹à€›[õ›[YàBàÀ»ô]ûHÿ\»X›X[H]Y]YYàõ›Œà»”ëH^X⁄]ôYúô\⁄àÀ»[à[Z]—S‘ëUñW‘–“QSQ][[‹›[€òŸH\à›X⁄¬àÀ»⁄[ô›Àà›[õ»úõÿYÿ\›YÿZ[ú›[ö€õ›€à›]H
BàÀ»çKéKçç»[ùò\öX[ù
N»ù\›ô]\à⁄Y€ò[€õ⁄\ŸKÇàò[ô]ûP€›[ùŸ^HHÀõZ[ù
»óÿò[[òŸW›[ö€õ›€àÇàò[ô]ûP€›[ùHô\õ–ò[[òŸTô]öY\ÀõY\ôŸJô]ûP€›[ùŸ^KJH»€»Oà€
»HHŒàBàò\àôX€›ô\ôYHò[ŸBàûH¬àôXYú€Y\
ML
HÀ»úöYYàúôX][ô»õ€€HôYõ‹ôHôK\€àò[ô]ûPò[[òŸ\»Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

BàYà
ô]ûPò[[òŸ\Àö\”õ›[\J
JH¬àÀ»ôXö[ô€»Hô\›Ÿà\»õÿ⁄»ŸY\»HôX€›ô\ôYX\Çà€ê⁄Z[êò[[òŸ\»Hô]ûPò[[òŸ\¬à⁄Ÿ[ë]HHô]ûPò[[òŸ\÷›ÀõZ[ùBàX\[\HHò[ŸBàôX€›ô\ôYHùYBà€ìŸ ∏¶n»îÀTëP”’ëTñNà	›Àúﬁ[Xõ€H8†%ôYúô\⁄ô]\õôY	‹ô]ûPò[[òŸ\Àú⁄^ô_HXÿ€›[ù»
ÿ\»[\JHãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–êSSê—W–“P“ÀàîîÀTëP”’ëTñH8†%ôYúô\⁄ô]\õôY	‹ô]ûPò[[òŸ\Àú⁄^ô_HXÿ€›[ùŒ»õÿŸYY[ô»⁄]Ÿ[]ò[X][€àãàòY\ïY»HìQSQHãà
Bàô\õ–ò[[òŸTô]öY\Àúô[[›ôJô]ûP€›[ùŸ^JBàBàHÿ]⁄
Nàõ›ÿXõJH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãîî»ôYúô\⁄òZ[Yõ‹à	›Àúﬁ[Xõ€Nà	ŸKõY\‹ÿYŸOÀùZŸJ
_HäBàBàYà
\ôX€›ô\ôY
H¬àÀ»çKååÕÕMà8†%€›\òŸHö^õ‹àHÕÕMHòXŸHÿ\ÇàÀ»HXZ[à]ôTŸ[

HîÀY[\HôX⁄X⁄»ò[àôYõ‹ôHHô]Ÿ\ÇàÀ»Ÿ[[[›[ù]]‹ö]Kúô\€€ôQõ‹ë^]

H›€ô\ãY[Hò[òX⁄À€¬àÀ»[Y\ôŸ[òﬁHŸ[»ô]\õôY–RUSë◊–êSSê—W‘ì”—àõ‹ô]ô\à]ô[ÇàÀ»⁄[àHù^H]YôX€‹ôY›€ô\ãYö[\ôY[Y]Hõ€ŸãÇàÀ»\⁄»H]]‹ö]H\ôHôYõ‹ôHX€\ö[ô»êSSê—W’Sí”ì’”ãÇàò[]]‹ö]Tô\€€][€àHûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]]‹ö]Kúô\€€ôQõ‹ë^]
ÀõZ[ùÿ[]ôX\€€äBàHÿ]⁄
Œàõ›ÿXõJH»ù[Bàò[]]‹ö]P€€ôö\õYYH]]‹ö]Tô\€€][€à\œ»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[[›[ù]]‹ö]Kîô\€€][€ãê€€ôö\õYYàYà
]]‹ö]P€€ôö\õYYOHù[	âà]]‹ö]P€€ôö\õYYúò]–[[›[ùú⁄Y€ù[J
Hà
H¬àò[X⁄[X[»H]]‹ö]P€€ôö\õYYôX⁄[X[Àò€Ÿ\òŸP]X\›

Bàò[ZHHûH»ò]òKõX]êöY—X⁄[X[
]]‹ö]P€€ôö\õYYúò]–[[›[ù
Kõ[›ôT⁄[ùYù
X⁄[X[ Kù—›XõJ
HHÿ]⁄
Œàõ›ÿXõJH»åBàYà
ZHàå	âàZKö\—ö[ö]J
JH¬à⁄Ÿ[ë]HH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[⁄Ÿ[ê[[›[ù
]]‹ö]P€€ôö\õYYúò]–[[›[ùX⁄[X[ BàX\[\HHò[ŸBàôX€›ô\ôYHùYBàô\õ–ò[[òŸTô]öY\Àúô[[›ôJô]ûP€›[ùŸ^JBà^X›][€îõ€›ÿ]\ŸUòXŸKò]]‹ö]Jî—SãìUëT—S‘î◊—STW”’”ëTó—SW‘ëP”’ëTëQãÀúôX\€€èIôX\€€àò]œIÿ]]‹ö]P€€ôö\õYYúò]–[[›[ùHZOIZHX⁄[X[œIX⁄[X[»€›\òŸOIÿ]]‹ö]P€€ôö\õYYú€›\òŸ_HäBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JìUëT—S‘î◊—STW”’”ëTó—SW‘ëP”’ëTëQãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àò]œIÿ]]‹ö]P€€ôö\õYYúò]–[[›[ùHZOIZHX⁄[X[œIX⁄[X[»€›\òŸOIÿ]]‹ö]P€€ôö\õYYú€›\òŸ_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàBàYà
\ôX€›ô\ôY	âàÿ[]ôXY[ô]\õZ[ò]JH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘î◊—STW‘ëT–’QW–ì–“—Q“SëUTìRSêUHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èIôX\€€àX›[€è[õ◊‹›[W›òX⁄Ÿ\ó‹Ÿ[äHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàYà
\ôX€›ô\ôY
H¬àÀ»çKååÕÕåà8†%›öX›]ôHò[[òŸH]]‹ö]Kàÿ[]]⁄Ÿ[àôXYàÀ»[ô]\õZ[ò]H
»õ»›€ô\ãYö[\ôYÿù^K]YYõ€ŸàYX[ú»êSSê—W’Sí”ì’”ãÇàÀ»»õ›\ŸHŸ[ô\öX»‘Tî—K][›HX]‹à›[H⁄Ÿ[ï[ö]ÀÇàò[òX⁄ŸYHûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãôŸ][ùûJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJH»ù[BàYà
òX⁄ŸYOHù[	âàòX⁄ŸYú€›\òŸHOH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î€›\òŸKï‘Tî—JH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JêêSSê—W‘ì”—ó‘ëRëP’QãúôX\€€èQ—SëTíP◊’‘Tî—W”ì’”’”ëTó—íSTëQZ[ùI›ÀõZ[ùùZŸJL
_H⁄]O[]ôTŸ[‹ú◊Ÿ[\HòX⁄Ÿ\î›]\œI›òX⁄ŸYú›]\Àõò[Y_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàò[òX⁄ŸY›]\’òXŸHHòX⁄ŸYÀú›]\œÀõò[YHŒàõõ€ôHÇàò[òX⁄ŸYò]’òXŸHHòX⁄ŸYÀúò]–[[›[ùŒàõõ€ôHÇà^X›][€îõ€›ÿ]\ŸUòXŸKò]]‹ö]Jî—SãìUëT—S’–SU‘ëPQ“SëUTìRSêUHãÀúôX\€€èIôX\€€àô]ûOIô]ûP€›[ùòX⁄ŸY›]\œIòX⁄ŸY›]\’òXŸHòX⁄ŸYò]œIòX⁄ŸYò]’òXŸHX›[€è]ÿZ]ÿò[[òŸW‹õ€ŸàäBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–êSSê—W–“P“Ààî—S‘UW‘”’Tê—OPêSSê—W’Sí”ì’”àôX\€€èU–SU’“—Só‘ëPQ“SëUTìRSêUH8†%õ»›€ô\ãYö[\ôYò[[òŸHõ€Ÿé»õ»úõÿYÿ\›ãàòY\ïY»HìQSQHãà
BàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S’–RUSë◊–êSSê—W‘ì”—àãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èU–SU’“—Só‘ëPQ“SëUTìRSêUH€‹ŸW€X\ŸW‹ô[X\ŸY]ùYHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[^X›][€ìÿ⁄‹Àúô[X\ŸJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùêêSSê—W’Sí”ì’”ó”ì◊‘“Q”êUTëHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãõX\ö‘Ÿ[ÿZ][ô–ò[[òŸTõ€ŸäÀõZ[ùÀúﬁ[Xõ€êêSSê—W’Sí”ì’”ó’–SU’“—Só‘ëPQ“SëUTìRSêUHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKååÕÕà8†%‹\ò]‹à‹X»][\»KãNà[ôŸôà¬àÀ»ò[[òŸTõ€Ÿî€\àöXHò[[òŸTõ€ŸïÿZ]›]Kà\»UT’ì’àÀ»[Z]—S‘ëUñW’ST‘êTñW””ìKUT’ì’X‹]Z\ôKÿõÿ⁄⁄[ô¬àÀ»X\ŸKUT’ì’[ú]Y]YHP’UëW‘—S‘ëPQKàH‹ò\\à]àÀ»ô\]Y\›Ÿ[

HôX€Ÿ€ö\Ÿ\»–RUSë◊–êSSê—W‘ì”—à[ô⁄⁄\¬àÀ»[ô[ô‘Ÿ[]Y]YKòY
»—S”ì◊–’TîëSï“S‘ì”—ó”ì’‘ëUíQQÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[êò[[òŸTõ€ŸïÿZ]›]KõX\ö’ÿZ][ô àÀõZ[ùÀúﬁ[Xõ€ôX\€€ãàù[ù[YQŸ[ô\ò][€àHûH»õ›ù[ù[YP€€ùõ€\ãò›\úô[ùŸ[ô\ò][€ä
HHÿ]⁄
Œàõ›ÿXõJH»Kà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ï–RUSë◊–êSSê—W‘ì”—ÇàBàÀ»ôX€›ô\ôY8†%ò[õ›Y⁄»Hõ‹õX[⁄Ÿ[ë]H⁄X⁄‹¬àÀ»ô[›»
⁄X⁄ù[àYÿZ[ú›HôYúô\⁄YX\
KÇàBàYà
⁄Ÿ[ë]HOHù[⁄Ÿ[ë]Kôö\ú›Hå
H¬àÀ»çKéKçÃà‘íUP–SíVàô]ö[›\»Ÿ⁄X»õ‹òŸKX€‹ŸYH‹⁄][€ÇàÀ»Yù\àHûô\õ»ò[[òŸHàôXYÀÿ[[ô»òYRYò€‹ŸY
ããäH[ôàÀ»ô]\õö[ô»””ëíTìQQ]ô[à›Y⁄ì»›ÿ\ÿ\»úõÿYÿ\›àYàBàÀ»î»ô]\õôY[à[\KŸ\úõ‹ôYX\
ò]K[[Z]€›»ﬁ[ò KàÀ»H⁄Ÿ[ú»Ÿ\ôH›[[àH\Ÿ\â‹»ÿ[]ù]Hõ›àÀ»X\öŸY[Hú€€]LL	Hã]HHìXÿ€›[ù[ôÀ[ôàÀ»ÿ[ŸY]ÿ^Kà\Ÿ\àÿ]Œà⁄Ÿ[ú»ô[XZ[à€ãX⁄Z[ãõ»””òX⁄ÀÇàÀ¬àÀ»çKéKççŒàHX\Y[\Húò[ò⁄\»õ›»[ôYXõ›ôHûBàÀ»îÀTëT–’QKà€õHôXX⁄\»]⁄[àHî»ô]\õôYàÀ»Hì”ãQSTHX\[ô\»‹X⁄YöX»Z[ùÿ\»XúŸ[ùﬁô\õ¬àÀ»8†%KôKà⁄Ÿ[ú»Ÿ[ùZ[ô[H€€ôH
ùYÀ€ô^\›^\õò[àÀ»Ÿ[
Kà›\ôòXŸH\»‹ú[à[\ùX]ôH‹⁄][€à‘SãàÀ»ô]ô\à€Z[HHŸ[ìÇàò[ô]ûP€›[ùHô\õ–ò[[òŸTô]öY\ÀõY\ôŸJÀõZ[ùJH»€»Oà€
»HHŒàBÇàYà
ô]ûP€›[ùèHå
H¬à€ìŸ º'Ê™‘îSàSTïà	›Àúﬁ[Xõ€H8†%€ãX⁄Z[àò[[òŸH\»ôY[àõ‹à	ô]ûP€›[ùà
¬àò€€úŸX›]]ôHôXY»
X\õ€ãY[\KZ[ùXúŸ[ù
Kà⁄Ÿ[ú»ZŸ[HùYŸŸY»^\õò[H€€àà
¬àî‹⁄][€àŸ\‘Sàõ‹àX[ùX[ô[X\ŸKàãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™‹ú[à‹⁄][€àãàâ›Àúﬁ[Xõ€Nà€ãX⁄Z[àò[[òŸHõ‹à	ô]ûP€›[ù€Ààõ»›ÿ\ÿ\»Ÿ[ùàà
¬àê€X\àX[ùX[Húõ€HH‹⁄][€ú»[ô[àãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàì‘îSà8†%€ãX⁄Z[àò[[òŸHõ‹à	ô]ûP€›[ù€Àà⁄Ÿ[ú»ZŸ[HùYŸŸYà‹⁄][€àŸ\‘Sàõ‹àX[ùX[ô[X\ŸKàãàòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBÇà€ìŸ î—Sì–“—Qà€ãX⁄Z[àò[[òŸOLõ‹à	›Àúﬁ[Xõ€H
ô]ûH	ô]ûP€›[ùÃå
H8†%Z[ùXúŸ[ùúõ€Hõ€ãY[\HX\ãàòYRYõZ[ù
Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãìUëH—Sì–“—Qà	›Àúﬁ[Xõ€H”ê“RSó÷ëTì»
ô]ûH	ô]ûP€›[ùÃå
HäBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàì€ãX⁄Z[àò[[òŸHH
ô]ûH	ô]ûP€›[ùÃå
H8†%⁄[ô]ûHô^X⁄»ãàòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàH[ŸH¬àò[X›X[ò[[òŸUZHH⁄Ÿ[ë]Kôö\ú›à€€ôö\õYYŸ[ZT]HHX›X[ò[[òŸUZBàò[X›X[X⁄[X[»H⁄Ÿ[ë]KúŸX€€ôà€ìŸ º'‰‚à—SPïQŒà€ãX⁄Z[àò[[òŸHH	X›X[ò[[òŸUZHX⁄[X[œIX›X[X⁄[X[»Z[ùI›ÀõZ[ùùZŸJ
_KããàãòYRYõZ[ù
BààÀ»çKçÀéàYàò[[òŸH\»\›Së‹⁄][€à\»Y\[à‹‹Àõ‹òŸH€‹ŸBàÀ»ù\]\àÿ[â››ÿ\[ûH[[›[ù»8†%€â›ŸY\ô]ûZ[ô»õ‹ô]ô\Çàò[[ùûUò[YT€€H‹Àò€‹›€€àò[›\úô[ùò[YQ\›[X]HHX›X[ò[[òŸUZH
à
Ÿ]X›X[öXŸJ JBàò[\—Y\‹‹»H[ùûUò[YT€€à	âà›\úô[ùò[YQ\›[X]H[ùûUò[YT€€
àåHÀ»€‹ùIHŸà[ùûBàò[\—\›HX›X[ò[[òŸUZHKå›\úô[ùò[YQ\›[X]HåHÀ»\‹»[àH⁄Ÿ[à‹àåH””ààYà
\—\›	âà\—Y\‹‹ H¬àÀ»çKéKçÃà‘íUP–SíVàô]ö[›\€H\»úò[ò⁄ÿ[YàÀ»òYRYò€‹ŸY
LL	JH[ôô]\õôY””ëíTìQQ8†%€Z[Z[ô»BàÀ»Ÿ[]ô]ô\à\[ôYàH⁄Ÿ[ú»ô[XZ[ôY[àBàÀ»ÿ[]à[ú›XYŸHõ›»[\ùH\Ÿ\à[ô]\ŸHô]öY\¬àÀ»€à\»Z[ù»H‹⁄][€à›^\»‘Sà[ù[H\Ÿ\ÇàÀ»X[ùX[Hô[X\Ÿ\»]‹àù\]\àôX€€Y\»][›XõHYÿZ[ãÇà€ìŸ º'Ê™’P“»‘“US”éà	›Àúﬁ[Xõ€H8†%ò[[òŸOIX›X[ò[[òŸUZH
â‘›ö[ôÀôõ‹õX]
âKçôàã›\úô[ùò[YQ\›[X]J_H””
Hà
¬àù€»€X[õ‹àù\]\ãàì’€€8†%⁄Ÿ[ú»ô[XZ[à€ãX⁄Z[ãàãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™›X⁄»‹⁄][€àãàâ›Àúﬁ[Xõ€Nà\›\⁄^ôYò[[òŸKù\]\àÿ[â›õ›]Kà⁄Ÿ[ú»ô[XZ[à[àÿ[]àà
¬àê€X\àX[ùX[Húõ€HH‹⁄][€ú»[ô[àãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãî’P“»‘“US”éà	›Àúﬁ[Xõ€H8†%\›
	ÿX›X[ò[[òŸUZ_H⁄Ÿ[ú H8†%à
¬àõYù‹[ãõ»›ÿ\úõÿYÿ\›àäBàÀ»çKéKçN8†%õ‹ô[ú⁄X‹Œà›\ôòXŸH\›\‹⁄][€àXõ‹ù»€»BàÀ»\Ÿ\àŸY\»⁄HHŸ[ô]ô\àúõÿYÿ\›Çà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàëT’
—QT”‘‘»8†%ò[[òŸOI»âKçàãôõ‹õX]
X›X[ò[[òŸUZJ_H
â»âKçôàãôõ‹õX]
›\úô[ùò[YQ\›[X]J_H””
Kù\]\àÿ[â›õ›]Kà⁄Ÿ[ú»ô[XZ[à€ãX⁄Z[ãàãà⁄Ÿ[ê[[›[ùHX›X[ò[[òŸUZKòY\ïY»HìQSQHãà
BàûH»[ô[ô‘Ÿ[]Y]YKúô[[›ôJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùëT’”ì◊–îì–Q–T’”ì◊‘“Q”êUTëHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùëT’”ì◊–îì–Q–T’”ì◊‘“Q”êUTëHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[îì’UW—êRSQ”ì◊‘“Q”êUTëBàBààò[][\Y\àHLåú› X›X[X⁄[X[Àù—›XõJ
JBàò[X›X[ò]’[ö]»H
X›X[ò[[òŸUZH
à][\Y\äKù”€ô 
Bàà€ìŸ º'‰‚à—SPïQŒàòX⁄ŸYI⁄Ÿ[ï[ö]»€ãX⁄Z[èIX›X[ò]’[ö]»
	ÿX›X[X⁄[X[ﬂYX HãòYRYõZ[ù
Bààò[Yôî›HYà
⁄Ÿ[ï[ö]»à
HXú 
X›X[ò]’[ö]»H⁄Ÿ[ï[ö] Kù—›XõJ
JH»⁄Ÿ[ï[ö]»
àL[ŸHåàYà
Yôî›àKå
H¬à€ìŸ ∏¶®;Ó#»ò[[òŸHYù\›Y[ùà\⁄[ô»€ãX⁄Z[àò[[òŸH
	X›X[ò]’[ö] H[ú›XYŸàòX⁄ŸY
	⁄Ÿ[ï[ö] HãòYRYõZ[ù
BàBàà⁄Ÿ[ï[ö]»HX›X[ò]’[ö]Àò€Ÿ\òŸP]X\›
S
Bà€ìŸ º'‰‚à—SPïQŒàö[ò[⁄Ÿ[ï[ö]»»Ÿ[H	⁄Ÿ[ï[ö]»ãòYRYõZ[ù
BàÀ»çKéKçÕÕH8†%SQTë—SïQSQH—S‘UW‘”’Tê—Hõ‹ô[ú⁄XÀÇàÀ»‹\ò]‹à‹X»çKéKçÕÕNà]ô\ûHŸ[]\›Ÿ»BàÀ»]]‹ö]]]ôH€›\òŸHŸà⁄Ÿ[ï[ö]ÿ€»H‹\ò]‹àÿ[ÇàÀ»[]H€[òŸH⁄]\àHõ›\ŸYH€ãX⁄Z[àî¬àÀ»ôXY[ôÀàòX⁄Ÿ\ã’‘Tî—Hò[òX⁄»\»õ‹òöY[àõ‹à]ôH[[›[ù]]‹ö]N¬àÀ»\»\»Hõ‹õX[îÀ\ô\€€ôY]ÇàûH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–êSSê—W–“P“Ààî—S‘UW‘”’Tê—OTî»ò]œIX›X[ò]’[ö]»ZOI»âKçôàãôõ‹õX]
X›X[ò[[òŸUZJ_HX⁄[X[œIX›X[X⁄[X[»ãà⁄Ÿ[ê[[›[ùHX›X[ò[[òŸUZKòY\ïY»HìQSQHãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKéKçÕÕ8†%SQTë—SïQSQNà\›XúõÿYÿ\››X\ôÇàÀ»öXYŸHYŸ[ùõ›[ôàH€Ÿ\òŸP]X\›
S
X€›[õ‹òŸHBàÀ»úõÿYÿ\›ŸàHò]»[ö]⁄[àX›X[ò[[òŸUZH\»€»€X[àÀ»]õ€‹ãY]öY\»»ò]»[ö]Àà€€Xö[ôY⁄]BàÀ»\—\›	âàZ\—Y\‹‹»]ò[[ô»õ›Y⁄Xõ›ôK\¬àÀ»õŸXŸYHYX[ö[ô€\‹»K\ò]À][ö]úõÿYÿ\›]ù\]\ÇàÀ»€›[õ›õ›]H[ô]ÿ\›YH€›[àHŸ[ZõÿÇàÀ»ôY⁄\›ûKàŸHõ›»êRS‘ëUñPPìH^X⁄]H⁄[àBàÀ»
úôX[
àò]»[[›[ù\»ô\õ»SëH‹⁄][€à\»›[àÀ»[]ôH8†%ÿ[YHV\»HT’
—QT”‘‘»]ù]õ‹àBàÀ»ô\›ù]õŸö]XõHàYŸHÿ\ŸKÇàYà
X›X[ò]’[ö]»H
H¬à€ìŸ º'Ê™T’–êSSê—W”ì◊–îì–Q–T’à	›Àúﬁ[Xõ€H8†%X›X[ò[[òŸUZOIÿX›X[ò[[òŸUZ_KX⁄[X[œIÿX›X[X⁄[X[ﬂKò]œIX›X[ò]’[ö]»8¢iàõ»úõÿYÿ\›àãòYRYõZ[ù
BàûH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàëT’–êSSê—W”ì◊–îì–Q–T’8†%ò[[òŸOI»âKçôàãôõ‹õX]
X›X[ò[[òŸUZJ_HX⁄[X[œIX›X[X⁄[X[»ò]œIX›X[ò]’[ö]»8†%‹⁄][€àYù‹[ãõ»›ÿ\úõÿYÿ\›àãà⁄Ÿ[ê[[›[ùHX›X[ò[[òŸUZKòY\ïY»HìQSQHãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»[ô[ô‘Ÿ[]Y]YKúô[[›ôJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùëT’‘êU◊÷ëTì◊”ì◊‘“Q”êUTëHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùëT’‘êU◊÷ëTì◊”ì◊‘“Q”êUTëHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[îì’UW—êRSQ”ì◊‘“Q”êUTëBàBàÀ»çKéKçÕç8†%SQTë—Sï‘íUP–S][HNà]K\⁄ö[ö»ÿYô]H\‹Ÿ\ù[€ãÇàÀ»‹\ò]‹àõ‹ô[ú⁄X‹◊ÃåçåLMWÃMLMåÕöú€€à⁄›ŸYSH[Y\ôŸ[òﬁBàÀ»ô]öY\»\⁄[ô»]OLLÃÃHYÿZ[ú›Hÿ[]ò[[òŸHŸàLåååL
KåIJKàÀ»[ô–PêSô]öY\»]]ONYÿZ[ú›ÃÃÃ
KåâJKàõ‹à[ûBàÀ»ïS—VUX€\‹»ôX\€€à
ïQ»»Të‘’‘»’‘”‘‘»»]ÀäHŸBàÀ»ôYù\ŸH»úõÿYÿ\›H]H]	‹»\‹»[àL	HŸàBàÀ»]]‹ö]]]ôH€ãX⁄Z[àò[[òŸK[ôôXùZ[⁄]Hù[àÀ»ÿ[]ò[[òŸH[ú›XYà\»õÿ⁄‹»H^X›KLâH€‹úù\[€ÇàÀ»]\õà⁄]›]úôXZ⁄[ô»\ùX[]ZŸK\õŸö]Ÿ[ÀÇàò[›X\ôY[ö]»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[]Q›X\ôô›X\ô
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àôX\€€àHôX\€€ãà]U‘Ÿ[H⁄Ÿ[ï[ö]Àà]]‹ö]]]ôUÿ[]]HHX›X[ò]’[ö]Àà
BàYà
›X\ôY[ö]»OH⁄Ÿ[ï[ö] H¬à€ìŸ àº'ÊËH—SUH’PTëà	›Àúﬁ[Xõ€H	ôX\€€à]HôXùZ[	⁄Ÿ[ï[ö]»8°§à	›X\ôY[ö]»
ù[ÿ[]ò[[òŸJHãàòYRYõZ[ùà
Bà⁄Ÿ[ï[ö]»H›X\ôY[ö]¬àBàÀ»çKéKçÕç8†%ôY⁄\›\à»ôYúô\⁄HŸ[õÿà
][HH›]K[XX⁄[ôJBàÀ»€»HôX€€ò⁄[\à
»ÿ⁄»\[[ôH]ôH]]‹ö]]]ôH›]BàÀ»õ‹à\»][\àõ‹àïS—VUôX\€€ú»ŸH[ò⁄‹à€àBàÀ»€ãX⁄Z[àò[[òŸN»\ùX[Ÿ[»
TïPS’R—W‘ì—íU»ì—íU”–“ BàÀ»\‹»õ›Y⁄[ò⁄[ôŸYÇàûH¬àò[⁄ì[ŸHHYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[]Q›X\ôö\—ù[^]ôX\€€äôX\€€äJBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿì[ŸKëïS—VUà[ŸBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿì[ŸKîTïPS—VUà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKôŸ]‹ê‹ôX]JàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àôX\€€àHôX\€€ãàô\]Y\›Y]HH⁄Ÿ[ï[ö]Ààÿ[]]P]›\ùHX›X[ò]’[ö]Àà[ŸHH⁄ì[ŸKà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàHÀ»[ô[ŸH
⁄Ÿ[ë]Hõ€ã[ù[ôYö[ô[Y[ùúò[ò⁄
H8†%çKéKçç¬ààHÿ]⁄
Nà^Ÿ\[€äH¬à€ìŸ º'Ê‰H—Sì–“—Qà	›Àúﬁ[Xõ€HêSSê—W’Sí”ì’”àYù\àò[[òŸKX⁄X⁄»òZ[\ôNà	ŸKõY\‹ÿYŸOÀùZŸJå
_HãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sã]ôUòYSŸ‘›‹ôKî\ŸKî—S–êSSê—W–“P“ÀàêêSSê—W’Sí”ì’”àYù\àò[[òŸKX⁄X⁄»òZ[\ôH8†%úõÿYÿ\›õÿ⁄ŸY»ÿX⁄Y⁄Ÿ[ï[ö]»õ›]]‹ö]]]ôHãàòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBÇàò\àõHåàò\àõHåÇàûH¬àÀ»çKéKçÃ8†%€\YŸH\ÿÿ[][€àX‹õ‹‹»îì–Q–T’ô]öY\»
õ›ù\›][›Hô]öY\ KÇàÀ»‹\ò]‹àõ‹ô[ú⁄X‹»€àì”H
[\ôù[à‹òYX]YY[YJH⁄›ŸYÇàÀ»—S‘US’W”“»åú»
][›HXÿŸ\YûHY]\»YŸ‹ôYÿ]‹äBàÀ»—S’–ïRS8°§à—S–îì–Q–T’8°§à—S—êRSQMŒ
€\YŸJBàÀ»ããà][\éàÿ[YHåú»][›Kÿ[YHMŒúõÿYÿ\›òZ[\ôBàÀ»[\ôù[àõ€ô[ôÀX›\ùôHY[Y\»\ôHõ€][N»HöXŸH[›ô\»ô]ŸY[ÇàÀ»][›HXÿŸ\[òŸH[ô€ãX⁄Z[à^X›][€ã€»H‹öY⁄[ò[åú¬àÀ»€\ò[òŸHŸ]»^ŸYYY]H›ÿ\õŸ‹ò[Kà€€‹€õBàÀ»\ÿÿ[]Y€\YŸH€àUS’HòZ[\ôKô]ô\à€àîì–Q–T’òZ[\ôKàÀ»€»›XúŸ\]Y[ùX⁄‹»Ÿ\ô]ûZ[ô»]åú»õ‹ô]ô\ãÇàÀ»õ›Œà\ã[Z[ùúõÿYÿ\›ô]öY\»€›[ù\à[ò‹ôX\Ÿ\»HêT—H€\YŸBàÀ»õ‹àô]öY\»
å8°§àÃ8°§àL8°§à8°§àLú»ÿ\
H€»H‹ô
¬àÀ»][\\»HôX[⁄[òŸHYÿZ[ú›[\ôù[ãX€\‹»öXŸHöYùÇàò[Ÿ[€\YŸHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKö[ö]X[€\YŸPú ôX\€€äBàò[ö[‹êúõÿYÿ\›ô]öY\»Hô\õ–ò[[òŸTô]öY\÷›ÀõZ[ù
»óÿúõÿYÿ\›óHŒàà€ìŸ º'‰‚à—SPïQŒàô\]Y\›[ô»][›H€\YŸOI‹Ÿ[€\YŸ_Xú»⁄Ÿ[ï[ö]œI⁄Ÿ[ï[ö]»úõÿYÿ\›ô]öY\œIö[‹êúõÿYÿ\›ô]öY\»ãòYRYõZ[ù
Bààò\à][›Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî›ÿ\][›O»Hù[àò\à\›\úõ‹éà^Ÿ\[€è»Hù[ààÀ»çKçÀéàYŸ‹ô\‹⁄]ôHŸ[8†%ûHõ‹õX[€\YŸK[àû[à^[àX^àÀ»çKéKåLŒàô]ö[›\€H\ôXÿ\Y]LúÀàçKéKçÃàYùY¬àÀ»Lú»ù]”ìHõ‹à⁄Ÿ[ú»]]ôH[ôXYHòZ[YúõÿYÿ\›àÀ»][\H[Y\»8†%ö\ú›][\»›[\ŸHHÿYôHåú»[ôàÀ»\ÿÿ[]H⁄][à\»€‹€õHYàH][›H]Ÿ[àÿ[â›ö[ÇàÀ»\»ô\Ÿ\ùô\»HçKéKåL»	Ÿ€â›Ÿ[ùY‹»][àöXŸI»[ù[ùàÀ»⁄[H][ô»Ÿ[ùZ[ô[Hõ€][H[\ôù[àY[Y\»€€\]HYù\àãL¬àÀ»MŒô]öY\ÀÇàò[€\YŸS]ô[»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKõY\äôX\€€äBÇàÀ»çKåçLà8†%ÿ]ôHéà”–êSù\]\àŸ[\⁄YH⁄\ò›Z]úôXZŸ\ãÇàÀ»Yà€»L‹»[ôY[àH\›ÃÀ⁄⁄\Hù\]\à][›HY\ÇàÀ»[ù\ô[H
L»€€€›€äH[ô]^X›][€àò[õ›Y⁄»BàÀ»[\‹ù[“[]\»\ôX›õ›]Hô[›ÀàõÿôK[€òŸH€à€€€›€ÇàÀ»^\ûN»H›XÿŸ\‹Ÿù[ù\]\àÿ[€X\ú»HúôXZŸ\ãÇàò[ù\]\ê⁄\ò›Z]‹[àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[ö\“ù\]\ë^]Y‹òYY

BàYà
ù\]\ê⁄\ò›Z]‹[äH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W—êRSàíïTUTó–“Tê’RU”‘Sà8†%⁄⁄\[ô»ù\]\àY\ã€⁄[ô»\ôX›
[\‹ù[“[]\ Kà€€€›€îô[XZ[ì\œIÿ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[öù\]\ê€€€›€îô[XZ[ö[ô”\ 
_HãàòY\ïY»HìQSQHãà
BàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JíïTUTó”QTó‘““TQ–“Tê’RU”‘SàãàõZ[ùI›ÀõZ[ùùZŸJL
_H€€€›€îô[XZ[ì\œIÿ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[öù\]\ê€€€›€îô[XZ[ö[ô”\ 
_HäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàBÇàò\àù\]\îõ›öY\ê€\‹—òZ[\ôMÃåéHò[ŸBàù\]\ìY\çÃåéõ‹à
€\]ô[[àYà
ù\]\ê⁄\ò›Z]‹[äH[\S\›

H[ŸH€\YŸS]ô[ H¬àõ‹à
][\[àKãåäH¬àûH¬à€ìŸ î—Sà][›H][\€\YŸOI‹€\]ô[Xú»ûOI][\ããàãòYRYõZ[ù
BàÀ»çKéKçMà8†%õ‹ô[ú⁄X‹Œà[Z]—S‘US’W’ñHôYõ‹ôHù\]\ÇàÀ»ÿ[€»H]ôHòYHõ‹ô[ú⁄X‹»õ»€ôŸ\à€Ÿ\»\ö¬àÀ»õ‹àÃNL»ô]ŸY[à—S‘’Tï[ô—S’–ïRSÇà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W’ñKàî][›H][\	‹€\]ô[Xú»ûOI][\ãà€\YŸPú»H€\]ô[àòY\ïY»HìQSQHãà
Bàò[ù\]\î[àHôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHíïTUTó’SêW”QUT»ãàô\]Y\›YZT]HH€€ôö\õYYŸ[ZT]KàŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãà
HŒàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBà⁄Ÿ[ï[ö]»Hù\]\î[ãúò]–[[›[ùà][›HHŸ]][›U⁄]€\YŸQ›X\ô
ÀõZ[ùù\]\ê\Kî”””RSïà⁄Ÿ[ï[ö]À€\]ô[à\–ù^HHò[ŸKàŸ[ZŸ\àHÿ[]úXõX“Ÿ^PçN
Bà€ìŸ î—Sà][›H“»›]I‹][›Kõ›][[›[ùH[\X›I‹][›KúöXŸR[\X››IHõ›]\èI‹][›Kúõ›]\üHôúOI‹][›Kö\‘ôúTõ›]_Hô\RYI‹][›Kúô\]Y\›YùZŸJLä_HãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W”“Ààî][›H“»	‹€\]ô[Xú»›]I‹][›Kõ›][[›[ùH[\X›I»âKåôàãôõ‹õX]
][›KúöXŸR[\X››
_IHõ›]\èI‹][›Kúõ›]\üI⁄Yà
][›Kö\‘ôúTõ›]JHà
ëîJHà[ŸHàüI⁄Yà
][›Kù[òTôZôX›YôX\€€ãö\”õ›õ[ö 
JHà8¶®[òHëRëP’Q8°§àY]\»ò[òX⁄»à[ŸHàüHãà€\YŸPú»H€\]ô[àòY\ïY»HìQSQHãà
BàúôXZ¬àHÿ]⁄
Nà^Ÿ\[€äH¬à\›\úõ‹àHBà€ìŸ î—Sà][›HòZ[Y€\YŸOI‹€\]ô[XúŒà	ŸKõY\‹ÿYŸOÀùZŸJL
_HãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W—êRSàî][›H	‹€\]ô[Xú»êRSQà	ŸKõY\‹ÿYŸOÀùZŸJ
HŒàè»üHãà€\YŸPú»H€\]ô[àòY\ïY»HìQSQHãà
BàÀ»çKåçLà8†%ÿ]ôHéàôYYL»»[ò]òZ[XõH[ù»BàÀ»€ÿò[⁄\ò›Z]úôXZŸ\ãàôX€‹ôù\]\îŸ[L»€õBàÀ»‹[ú»HúôXZŸ\àYù\àû[àÃÀÇàûH¬àYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[ö\‘õ›öY\ê€\‹—òZ[\ôJKõY\‹ÿYŸJJH¬àù\]\îõ›öY\ê€\‹—òZ[\ôMÃåéHùYBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[úôX€‹ôù\]\îõ›öY\ëòZ[\ôJKõY\‹ÿYŸHŒàúõ›öY\óŸòZ[\ôHäBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘ì’íQTó‘ì’UW“SSQQPUWÕÃåéãàúõ›öY\èRïTUTàZ[ùI›ÀõZ[ùùZŸJL
_HôX\€€èIŸKõY\‹ÿYŸOÀùZŸJL
_HX›[€èXúôXZ◊€Y\ó‹õ›]HäBàBàHÿ]⁄
Œàõ›ÿXõJHﬂBàYà
ù\]\îõ›öY\ê€\‹—òZ[\ôMÃåé
HúôXZ¬àYà
][\äHôXYú€Y\
Ã
BàBàBàYà
ù\]\îõ›öY\ê€\‹—òZ[\ôMÃåé
HúôXZ–ù\]\ìY\çÃåéàYà
][›HOHù[
H¬àÀ»çKåçLà8†%›XÿŸ\‹Ÿù[ù\]\àŸ[][›H€X\ú»HúôXZŸ\ãÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[úôX€‹ôù\]\îŸ[⁄ 
HBàÿ]⁄
Œàõ›ÿXõJHﬂBàúôXZ¬àBàBààÀ»çKéKçÃà‘íUP–SíVàYàS][›H][\»òZ[Y—QTBàÀ»‹⁄][€à‹[ãàô]ö[›\€H\»õ‹òŸKX€‹ŸY⁄]LL	Hì[ôàÀ»€Z[YYHŸ[]ô]ô\à\[ôY»H⁄Ÿ[ú»›^YY[àBàÀ»\Ÿ\â‹»ÿ[]àHòZ[Yù\]\à][›H\»[[‹›[ÿ^\¬àÀ»ôX€›ô\òXõH
ò]K[[Z][ô^\àYÀ[€Y[ù\ûH[à€€
KÇàÀ»çKéKåMLé8†%ì’íQTàíS‘íUNàST8°§àSUT»8°§àïTUTà
Ÿ[€›\òŸHö^
KÇàÀ»ì”’–UT—NàHù\]\à][›HL»\ŸY»\ô\ô]\õàUS’HVUT’QàÀ»TëH8†%ôYõ‹ôHH[\‹ù[“[]\»\ôX›õ›]H
ûT[\‹ù[Ÿ[àÀ»çL[ô\»ô[› Hÿ\»]ô\à][\Y]ô[à›Y⁄[\ôù[à»[\›ÿ\¬àÀ»ò^Y][KX]]»õ›][ô»ôYY»ì»ù\]\à][›H][à\à‹\ò]‹à‹XÀàÀ»ù\]\à\»HT’\ô\€‹ùYŸ‹ôYÿ]‹é»HòZ[Yù\]\à][›H]\›àÀ»ì’UH»H\ôX›õ›]Kõ›^]\›HŸ[àŸHõ›»ôX]Hù[àÀ»][›H\»ì”ãQêUSà^X›][€àò[»õ›Y⁄»STQíTî’
⁄X⁄ùZ[¬àÀ»
»úõÿYÿ\›»öXH[\‹ù[“[]\»Ÿ[ô\à⁄]õ»ù\]\à\[ô[òﬁJKÇàÀ»Hù\]\àúõÿYÿ\›Y\àô[›»\»[ôXYH⁄⁄\Y⁄[àH\ôX›⁄Y¬àÀ»[ôŒ»ŸHS”»⁄⁄\]⁄[à\ôH\»õ»][›H»ùZ[úõ€Kà€õHYÇàÀ»ì’H\ôX›õ›]HSëù\]\à\ôH[ò]òZ[XõH»ŸHŸY\BàÀ»‹⁄][€à‘Só’êP““Së»⁄]]»⁄[ô€H[ô[ô»Ÿ[[ù[ù
X\ŸJKÇàò[ù\]\î][›U[ò]òZ[XõHH
][›HOHù[
BàYà
ù\]\î][›U[ò]òZ[XõJH¬àÀ»çKåçMåH8†%TëP’Tì’UH—SîëQVëHõ‹àõ€ãY[Y\ôŸ[òﬁHôX\€€úÀÇàÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dàÀ»‹\ò]‹à[\åçãLãLçà⁄›ŸYòY\»õYY[ô»»MÃIH¬àÀ»MN	H⁄[àù\]\àî»YYà][›Hÿ[YHòX⁄»ù[BàÀ»^X›]‹àô[›òZY⁄õ›Y⁄»HST“SUT»\ôX›àÀ»õ›]K⁄X⁄ö[»[ù»Z[ô»\]ZY]H⁄]õ»€\YŸBàÀ»õ⁄ôX›[€ãàõ‹àH’íP’‘”ÀLL^]]\õôY[ù¬àÀ»MÃIHôX[^ôY‹‹ÀÇàÀ¬àÀ»ù[Nà⁄[àù\]\à\»XYSëHôX\€€à\»ì’HùYBàÀ»[Y\ôŸ[òﬁKYô\àõ‹à\»åÃ»
HX⁄‹ H‹[ô»ù\]\ÇàÀ»ôX€›ô\úÀàHYô\à\»\ã[Z[ù[ôÿ\Y8†%Yù\àBàÀ»ÿ\ŸHUT’õ‹òŸK\õÿŸYY
ô]\àHòYö[[àBàÀ»\õX[ô[ùH›X⁄»‹⁄][€äKÇàÀ¬àÀ»[Y\ôŸ[òﬁHôX\€€ú»
ïQÀ”ëVT’–UT’ì‘PÀàÀ»’PS”RSï’SKPV“”UT’‘—SSQTë—Sê÷KàÀ»“U’”ãSï”KêRSãSíPÀëQìV^X⁄]TURQUW à[Y\ôŸ[ò⁄Y\ HS–VT¬àÀ»úõÿYÿ\›8†%^IŸò]\àZŸHHML	Hö[[àôBàÀ»›X⁄»[àHùYÀÇàYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë^X›][€íX[›X\ôú⁄›[Yô\ë\ôX›õ›]TŸ[
ÀõZ[ùôX\€€äJH¬àò[àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë^X›][€íX[›X\ôô\ôX›õ›]QYô\ê€›[ù
ÀõZ[ù
Bàò[YŸS\»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë^X›][€íX[›X\ôô\ôX›õ›]QYô\êYŸS\ ÀõZ[ù
Bà€ìŸ º'Ê‰H—SQëTîëQ
ù\]\àXYõ€ãY[Y\ôŸ[òﬁJNà	›Àúﬁ[Xõ€HôX\€€èI…ôX\€€â»
Yô\à	€üKÕHYŸOIÿYŸS\ﬂ[\»ÿ\LÃ\ H8†%ôK\]Y]YKô]ûH€àô^^]X⁄»ãÀõZ[ù
BàûH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W—êRSàî—S—TëP’‘ì’UW—îëQVëWÕMå»ù\]\èYXYôX\€€èI…ôX\€€â»Yô\èIãÕHYŸOIÿYŸS\ﬂ[\»ÿ\LÃ\»8†%ÿZ][ô»úöYYõHõ‹àù\]\àôX€›ô\ûHôYõ‹ôHúõÿYÿ\›[ô»\ôX›õ›]HãàòY\ïY»HìQSQHãà
Bàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàî—S—TëP’‘ì’UW—îëQVëHãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èI‹ôX\€€ãùZŸJ
_HYô\èIãÕHYŸS\œIYŸS\»ù\]\èYXYX›[€èYYô\ó‹[ô[ô◊‹ôX€›ô\ûHÇà
Bà\[[ôRX[€€X›‹ãõXô[[ò î—S—TëP’‘ì’UW—îëQVëWÕMå»äBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W—êRSàî—S‘ì’íQTó—êRSõ›öY\èRïTUTà›]\œ\][›W›[ò]òZ[XõHX›[€è\õ›]W€õ›Ÿ^]\›8°§àST“SUT»\ôX›ö\ú›ãàòY\ïY»HìQSQHãà
BàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘ì’íQTó—êRSãàúõ›öY\èRïTUTà›]\œ\][›W›[ò]òZ[XõHX›[€è\õ›]W€õ›Ÿ^]\›Z[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàH[ŸH¬àò[Q›X\ôHŸX›\ö]Kùò[Y]T][›J][›HHK\–ù^HHò[ŸK[ú]€€H‹Àò€‹›€€
BàYà
Q›X\ô\»›X\ôô\›[êõÿ⁄ H¬à€ìŸ ∏¶®Ÿ[][›Hÿ\õö[ôŒà	‹Q›X\ôúôX\€€üH8†%õÿŸYY[ô»[û]ÿ^HãÀõZ[ù
BàBàÀ»çKåçLH8†%—ST“QH”TQ—HPì‘ïàYô\àHŸ[YàBàÀ»][›H⁄›‹»ÿ]\›õ‹X»öXŸH[\X›
åçIJK⁄]ö[ô»H€€àÀ»H⁄[òŸH»X⁄Ÿ[à€àHô^^]X⁄Ààÿ\»]àô]öY\¬àÀ»\àZ[ù€»HŸ[ùZ[ô[K\ùYŸ⁄[ô»‹⁄][€à›[Ÿ]»\]ZY]YàÀ»ò]\à[àÿ\úöYYõ‹ô]ô\ãà[Y\ôŸ[òﬁH^]»û\\‹ÀÇàYà
][›HHKúöXŸR[\X››à—S‘”TQ—W–Pì‘ï‘’	âàZ\—[Y\ôŸ[òﬁTŸ[ôX\€€äôX\€€äJH¬àò[€›[ù\àHŸ[€\YŸPXõ‹ùÀò€€\]RYêXúŸ[ù
ÀõZ[ù
H»ò]òKù][ò€€ò›\úô[ùò]€ZXÀê]€ZX“[ùYŸ\ä
HBàò[àH€›[ù\ãö[ò‹ô[Y[ù[ôŸ]

BàYà
àH—S‘”TQ—W–Pì‘ï”PV
H¬à€ìŸ º'Ê‰H—SQëTîëQàöXŸR[\X›I»âKåYàãôõ‹õX]
][›HHKúöXŸR[\X››
_IHà	‘—S‘”TQ—W–Pì‘ï‘’ù“[ù

_IH
ô]ûH	€üK…‘—S‘”TQ—W–Pì‘ï”PVJKàôK\]Y]YZ[ô»õ‹àô^^]X⁄»8†%€€X^HX⁄Ÿ[ãàãÀõZ[ù
BàûH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W—êRSàî—S‘”TQ—W–Pì‘ï[\X›I»âKåYàãôõ‹õX]
][›HHKúöXŸR[\X››
_IHô\⁄€I‘—S‘”TQ—W–Pì‘ï‘’ù“[ù

_IHô]ûOI€üK…‘—S‘”TQ—W–Pì‘ï”PVHôX\€€èI…ôX\€€â»8°§àYô\à»ô^X⁄»ãàòY\ïY»HìQSQHãà
Bàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘”TQ—W–Pì‘ï—QëTàãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H[\X›I»âKåYàãôõ‹õX]
][›HHKúöXŸR[\X››
_Hô\⁄€I‘—S‘”TQ—W–Pì‘ï‘’Hô]ûOIàX^I—S‘”TQ—W–Pì‘ï”PVôX\€€èI‹ôX\€€ãùZŸJ
_HäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàH[ŸH¬à€ìŸ ∏¶®—Sì‘ê—KTì–—QQàöXŸR[\X›I»âKåYàãôõ‹õX]
][›HHKúöXŸR[\X››
_IHXõ›ôH	‘—S‘”TQ—W–Pì‘ï‘’ù“[ù

_IHYù\à	‘—S‘”TQ—W–Pì‘ï”PVHô]öY\»8†%XÿŸ\[ô»ö[»]õ⁄Y›X⁄»‹⁄][€ãàãÀõZ[ù
BàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘”TQ—W–Pì‘ï—ì‘ê—HãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H[\X›I»âKåYàãôõ‹õX]
][›HHKúöXŸR[\X››
_Hô]öY\œIàôX\€€èI‹ôX\€€ãùZŸJ
_HäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàŸ[€\YŸPXõ‹ùÀúô[[›ôJÀõZ[ù
BàBàH[ŸHYà
][›HHKúöXŸR[\X››H—S‘”TQ—W–Pì‘ï‘’
H¬àŸ[€\YŸPXõ‹ùÀúô[[›ôJÀõZ[ù
BàBàBÇàÀ»çKéKçŒ8†%SãSSëHîì–Q–T’ëUñHõ‹àMŒ»”TQ—W—V—QQQÇàÀ»çKéKçŒH8†%êRSãQVUSQTë—Sê÷HS—H
»òZ\ŸYYò][ÿ\ÇàÀ¬àÀ»‹\ò]‹à\ôX›]ôH
çKéKçŒ
NàéŸX€€ô»\»[Xà[ôÿ^H¬àÀ»€ô»HöXŸH⁄[[ÿ^\»]ôH[›ôY\»ŸH⁄[Z\‹»]ZX⁄¬àÀ»[\‹ZŸ\»]ôYY»»ôH\»ò\›\»Hù^\»\ôHZÿH[ú›[ùãÇàÀ¬àÀ»‹\ò]‹àõ‹ô[ú⁄X‹»
çKéKçŒK€€€õX[ãÃŸ’éVY⁄x†)äNàH⁄Ÿ[à[ÇàÀ»	ŸòZ[óŸ^]€\]ZY]Wÿ€€\ŸI»Ÿ\òZ[[ô»MŒ]ô[à]àÀ»LúÀàH⁄Ÿ[â‹»öXŸHÿ\»€€\⁄[ô»åL	H\àŸX€€ô€¬àÀ»]ô[àL	H€\YŸH€\ò[òŸHÿ\»[ú›YôöX⁄Y[ùàõ‹àòZ[à»ùY¬àÀ»»€€\ŸH^]»ŸHXÿŸ\ÿ]\›õ‹X»€\YŸH»\ÿÿ\BàÀ»H‹⁄][€à]X^HôH€‹ù	[àÃŸX€€ôÀÇàÀ¬àÀ»Yò][Y\àÿ\[€»ù[\Yúõ€HL8°§àåú»ôXÿ]\ŸBàÀ»[\ôù[àY[Y\»õ›][ô[H[›ôHLMIH\à[àõ‹õX[
õ€ã\ùY BàÀ»€€ô][€ú»[ôHçKéKçŒLú»ÿ\ÿ\»ö\[ô»€ÇàÀ»X[H‹⁄][€ú»€ÀÇàÀ¬àÀ»⁄[àúõÿYÿ\›òZ[»⁄]”TQ—W—V—QQQôK\][›H]BàÀ»ô^€\YŸHY\àSãSSëH⁄][àHÿ[YH]ôTŸ[ÿ[ÇàÀ»åÀMHŸX€€ô»ô]ŸY[àY\ú»
ù\]\à][›H][òﬁH€õJKà[àÀ»Y\ú»€€\]H⁄][àåMKLç\»ú»Z[ù]\»õ›Y⁄H]Y]YKÇàÀ»[›\àòZ[\ôH[Ÿ\»
“SKëîKêUW”SRUQ—VTëQàÀ»Sî’QëíP“QSï—ïSëÀïTUTó–ïRS—êRSQ
HôK]õ›»»H›]\ÇàÀ»ÿ]⁄[ò⁄[ôŸY8†%€õH”TQ—H\ÿÿ[]\»[õ[ôKÇàÀ»ô\õ–ò[[òŸTô]öY\÷€Z[ù
»óÿúõÿYÿ\›óH\»›[ù[\Y\ÇàÀ»òZ[\ôHõ‹àõ‹ô[ú⁄X‹»
»‹õ‹‹ÀXÿ[›]KÇàò[\—òZ[ë^]H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKõX^€\YŸPú ôX\€€äHàLå	âÇà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKö\“\ôùY ôX\€€äHà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKö\”X[ùX[[Y\ôŸ[òﬁJôX\€€äJBàò[úõÿYÿ\›€\Y\àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKõY\äôX\€€äBàYà
\—òZ[ë^]
H¬à€ìŸ º'Ê™êRSãQVU[ŸHõ‹à	›Àúﬁ[Xõ€H
	ôX\€€äNàY\èIÿúõÿYÿ\›€\Y\ãöõ⁄[ï‘›ö[ô ãä_Xú»ãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘’Tïàº'Ê™êRSãQVU[ŸHY\èIÿúõÿYÿ\›€\Y\ãöõ⁄[ï‘›ö[ô ãä_Xú»ãàòY\ïY»HìQSQHãà
BàBàò\à⁄YŒà›ö[ôœ»Hù[àò\à\›úõÿYÿ\›^Ÿ\[€éà^Ÿ\[€è»Hù[àò\àúõÿYÿ\›][\»HàÀ»çKéKçMH8†%SíUëTî–SSTQíTî’õ›][ô»
[õ[ôY8†%õ»ù[à»XàÀ»[XôH€»€›[à€X\ùXÿ\›€à⁄Yÿ›[€‹ö‹»›€ú›ôX[JKÇàÀ»çKéKçLàYYSTQíTî’õ‹à[\ôù[àZ[ù»€õKà‹\ò]‹ÇàÀ»\ôX›]ôH
ôXàåçäNàùŸHõ›»€õ›»⁄]€‹ö‹»YH[\ù[àõ‹ÇàÀ»H[ù\ôH€€ô]€‹ö»ò\⁄Xÿ[HHù\]\à[òH[àBàÀ»›\àÿ[òX⁄‹»ãà[\‹ù[Y⁄ö[ô…‹»€€Hò]]»àõ›]\¬àÀ»õ›Y⁄[\ôù[àõ€ô[ô»›\ùôK[\›ÿ\SSKSëò^Y][H8†%àÀ»€›ô\ö[ô»[‹›‘⁄Ÿ[ú»⁄]ô[ùYH›\‹ùõ›ù\›[\àÀ»›Yôö^Z[ùÀàŸHûH[\‹ù[íTî’õ‹à]ô\ûHŸ[àYà]àÀ»»
[ã\õ›]XõNàY\Y‹òYX]Y‹òÿK[€õK[ú›YôöX⁄Y[ùàÀ»\]ZY]K]ÀäHŸHò[õ›Y⁄»Hù\]\à[òH8°§àY]\¬àÀ»Y\ã[à»Hö[ò[STTëT–’QHô]ûH]Y⁄\à€\ÇàÀ»çKéKçÕŒH8†%SQTë—SïQSQHì’UTàíVàô\€€ôH[ôŸ»BàÀ»ô[ùYHX⁄\⁄[€àëQì‘ëHHŸ[][\€»H‹\ò]‹àÿ[ÇàÀ»ŸYH⁄HH\ùX›[\àõ›]Hÿ\»⁄‹Ÿ[àõ‹à\»Z[ùÇàò[ô[ùYTô\€€][€àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ìY[YUô[ùYTõ›]\ãúô\€€ôJÀõZ[ùÀúﬁ[Xõ€
BàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàîì’UW–USTãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€Hõ›]OTST‘ïS”––S][\LHà
¬àú€\YŸOMHô[ùYOI›ô[ùYTô\€€][€ãùô[ùYKõò[Y_Hà
¬àúôYô\î[\ò]]ôOIÿ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ìY[YUô[ùYTõ›]\ãúôYô\î[\ò]]ôJô[ùYTô\€€][€ãùô[ùYJ_Hãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKéKåMMà8†%][›KY\ö]ôY[ö]X[€\
ŸYHõŸö][ÿ⁄»]
KàY⁄àÀ»ö\ú›
ôX\€€ãX]ÿ\ôHõ€‹äKY\à⁄Y[ú»»IH€õH€àHòZ[Y[ôÇàò[‘[\€\H
Ÿ[€\YŸH»L
Kò€Ÿ\òŸR[äKJBàò[‘[\ö]»HÀöö]—[òXõYàò[‘[\\HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùH\—òZ[ë^]
BàÀ»çKéKåMLé8†%õ›öY\ã\Ÿ[X›õ‹ô[ú⁄X»
‹\ò]‹à‹X Kà[\‹ù[àÀ»Y⁄ö[ô»€€Hò]]»àõ›]\»[\ôù[àõ€ô[ô»›\ùôH8°§à[\›ÿ\8°§ÇàÀ»ò^Y][H[ôúõÿYÿ\›»õ›Y⁄H[]\»Ÿ[ô\é»]\»HíSPTñBàÀ»\ôX›õ›]Kàù\]\à
HY\àô[› H\»ò[òX⁄»€õKÇàûH¬àò[[\Z[ùH€€KõYôXﬁX€Xõ›õô]€‹öÀî[\ù[ë\ôX›\Kö\‘[\ù[ìZ[ù
ÀõZ[ù
Bàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘ì’íQTó‘—SP’ãàúõ›öY\èI⁄Yà
[\Z[ù
HîST—TëP’à[ŸHíSUT◊—TëP’üHà
¬àúôX\€€èI⁄Yà
[\Z[ù
Hú[\€Z[ùà[ŸHúö[X\ûHüHà
¬àõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€Hù\]\î][›OI⁄Yà
ù\]\î][›U[ò]òZ[XõJHù[ò]òZ[XõHà[ŸHò]òZ[XõWŸò[òX⁄»üHäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàò[^][\ö\ú›åNHH⁄›[ûT[\\ôX›ö\ú›åNJÀëVUäBà⁄Y»HYà
Y^][\ö\ú›åNJH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîST—TëP’‘““TQ–ïVW‘ì’UWÕåNHãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HXô[QVUö[‹ö]OI‹Ÿ[õ›]Tö[‹ö]Qúõ€Pù^Tõ›]MåNJ _HX›[€èZù\]\óŸö\ú›äHHÿ]⁄
Œàõ›ÿXõJHﬂBàù[àH[ŸHûT[\‹ù[Ÿ[
à»HÀàÿ[]Hÿ[]à⁄Ÿ[ï[ö]»HôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHYà
\—òZ[ë^]
HîST‘ïS—VU—êRSàà[ŸHîST‘ïS—VUãàô\]Y\›YZT]HH€€ôö\õYYŸ[ZT]KàŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãà
OÀúò]–[[›[ùŒàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìKà€\›H‘[\€\àö[‹ö]QôYT€€HYà
\—òZ[ë^]
HåH[ŸHåKà\ŸRö]»H‘[\ö]Ààö]’\[\‹ù»H‘[\\àŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãàXô[Y»HYà
\—òZ[ë^]
HëVUQêRSàà[ŸHëVUãà
BàÀ»çKéKçLà8†%⁄⁄\ù\]\àY\à[ù\ô[HYàSTQíTî’[ôYÇàÀ»çKéKçMY8†%[òKYö\ú›ò[òX⁄»⁄Y€ò[à]ôTŸ[\Ÿ\¬àÀ»Ÿ]][›U⁄]ZŸ\à⁄X⁄ôYô\ú»ù\]\à[òHåà[àçÇàÀ»Y]\»€àëîHôZôX›[€ãàŸ»H[ù[ù€»‹\ò]‹àŸY\»BàÀ»\ÿÿ[][€à⁄Z[à[ô]ÀY[ô€àHõ‹ô[ú⁄X‹»[KÇàYà
⁄Y»OHù[
H¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKíSëìÀà∏°™HêSêP“»8°§àù\]\à[òH
åäHö[X\ûKçàY]\»ŸX€€ô\ûHY\èIÿúõÿYÿ\›€\Y\ãöõ⁄[ï‘›ö[ô ã»ä_Xú»ãàòY\ïY»HìQSQHãà
BàBàõ‹à
›\úô[ù€\[àYà
⁄Y»OHù[ù\]\î][›U[ò]òZ[XõJH[\S\›[ùä
H[ŸHúõÿYÿ\›€\Y\äH¬àúõÿYÿ\›][\  ¬àûH¬àÀ»ôK\][›H]Hô]»€\YŸHY\à€à][\»äÀàBàÀ»ö\ú›][\ôK]\Ÿ\»H][›HÿùZ[ôYûHH][›BàÀ»€‹Xõ›ôH
õ»^òHù\]\àõ›[ôö\
KÇàYà
úõÿYÿ\›][\»àJH¬à€ìŸ ∏¶®H”TQ—HT––SUS”éàôK\][›[ô»	›Àúﬁ[Xõ€H	ÿ›\úô[ù€\Xú»
][\	úõÿYÿ\›][\ x†)àãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W’ñKà∏¶®HT––SUS”àôK\][›H	ÿ›\úô[ù€\Xú»
][\	úõÿYÿ\›][\ Hãà€\YŸPú»H›\úô[ù€\òY\ïY»HìQSQHãà
BàûH¬àÀ»çKéKåMLÃ»8†%‹X»][Héà\‹Ÿ\ùHö[ò[úõÿYÿ\›ú»\¬àÀ»⁄][àHLú»õ€ãY[Y\ôŸ[òﬁH\ôÿ\
€›[ù»
»€[\»[ûHû\\‹ KÇàò[ÿYôT€\H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKò\‹Ÿ\ù⁄][êÿ\
ôX\€€ã›\úô[ù€\
Bàò[Y\íù\]\î[àHôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHíïTUTó’SêW”QUT◊”QTàãàô\]Y\›YZT]HH€€ôö\õYYŸ[ZT]KàŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãà
HŒàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBà⁄Ÿ[ï[ö]»HY\íù\]\î[ãúò]–[[›[ùà][›HHŸ]][›U⁄]€\YŸQ›X\ô
àÀõZ[ùù\]\ê\Kî”””RSï⁄Ÿ[ï[ö]ÀÿYôT€\à\–ù^HHò[ŸKŸ[ZŸ\àHÿ[]úXõX“Ÿ^PçN
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W”“ÀàîôK\][›H“»	ÿ›\úô[ù€\Xú»›]I‹][›Kõ›][[›[ùHõ›]\èI‹][›Kúõ›]\üI⁄Yà
][›Kö\‘ôúTõ›]JHà
ëîJHà[ŸHàüI⁄Yà
][›Kù[òTôZôX›YôX\€€ãö\”õ›õ[ö 
JHà8¶®[òHëRëP’Qà[ŸHàüHãà€\YŸPú»H›\úô[ù€\òY\ïY»HìQSQHãà
BàHÿ]⁄
Y^à^Ÿ\[€äH¬à\›úõÿYÿ\›^Ÿ\[€àHY^à€ìŸ ∏¶®;Ó#»ôK\][›HòZ[Y	ÿ›\úô[ù€\XúŒà	‹Y^õY\‹ÿYŸOÀùZŸJL
_HãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W—êRSàîôK\][›H	ÿ›\úô[ù€\Xú»êRSQà	‹Y^õY\‹ÿYŸOÀùZŸJ
HŒàè»üHãà€\YŸPú»H›\úô[ù€\òY\ïY»HìQSQHãà
Bà€€ù[ùYBàBàBÇà€ìŸ º'‰‚à—SPïQŒàùZ[[ô»ò[úÿX›[€à
€\Iÿ›\úô[ù€\Xú»][\IúõÿYÿ\›][\ KããàãòYRYõZ[ù
BàÀ»çKéKçà8†%⁄Y[à[ò[ZX‘€\YŸSX^ú»€»ù\]\â‹»⁄[][][€à\¬àÀ»ôX[õ€€H»X⁄»HŸ[ú⁄XõH€\YŸPúÀà›]X»›\úô[ù€\àÀ»
KôÀàåú Hÿ\»€»Y⁄õ‹àù\]\à»[ò€ŸH[û][ô¬àÀ»\ŸYù[8†%]ô\ûHY\àòZ[Y]⁄[][][€ãàõ›Œàåú»ÿ\àÀ»€àõ‹õX[Ÿ[ÀNNNXú»€àòZ[ãY^]ò[\[ô»\X‹õ‹‹¬àÀ»[ã[[ôHô]öY\Ààù\]\â‹»⁄[][][€àX⁄‹»HX›X[àÀ»ò[YH⁄][àõ›[ô»ò\ŸY€àôX[€€›]KÇàò[[î€\ÿ\H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁKõX^€\YŸPú ôX\€€äKò€Ÿ\òŸP]X\›
›\úô[ù€\
Bàò[ô\›[HùZ[⁄]ô]ûJà][›HHKÿ[]úXõX“Ÿ^PçN[ò[ZX‘€\YŸSX^ú»H[î€\ÿ\àŸ[ô\ï\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùH\—òZ[ë^]
Kà
Bà€ìŸ º'‰‚à—SPïQŒàò[úÿX›[€àùZ[ô\]Y\›YI›ô\›[úô\]Y\›YÀùZŸJMäHŒàõõ€ôHüHãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’–ïRSàïùZ[õ›]\èI›ô\›[úõ›]\üHôúOI›ô\›[ö\‘ôúTõ›]_H€\Iÿ›\úô[ù€\Xú»
][\	úõÿYÿ\›][\ Hà
»
Yà
ô\›[ô[î€\X⁄ŸYú»èH
Hà[ã\€\X⁄ŸYI›ô\›[ô[î€\X⁄ŸYúﬂXú»[ò›\úôYI›ô\›[ô[î€\[ò›\úôYúﬂXú»à[ŸHàäKàòY\ïY»HìQSQHãà
BàÀ»çKéKçÕç»8†%ö]ôHŸ[õÿîôY⁄\›ûH›]HXX⁄[ôH[ô]ÀY[ôÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\ÀêïRSSë HHÿ]⁄
Œàõ›ÿXõJHﬂBàŸX›\ö]Kô[ôõ‹òŸT⁄Y€ë[^J
BÇàò[\ŸRö]»HÀöö]—[òXõY	âà\][›HHKö\’[òBàÀ»çKéKç»8†%[ò[ZX»ö]»\úõ€Hù[ô\Àöö]Àù›àÕ]\òŸ[ù[KÇàò[ö]’\HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùH\—òZ[ë^]
BÇàYà
][›HHKö\’[òJH¬à€ìŸ º'Ê†úõÿYÿ\›[ô»Ÿ[öXHù\]\à[òH
ôX[HQUàõ›X›[€äH	ÿ›\úô[ù€\Xú¯†)àãÀõZ[ù
BàH[ŸHYà
\ŸRö] H¬à€ìŸ ∏¶®HúõÿYÿ\›[ô»Ÿ[öXHö]»QUàõ›X›[€à	ÿ›\úô[ù€\Xú¯†)àãÀõZ[ù
BàH[ŸH¬à€ìŸ êúõÿYÿ\›[ô»Ÿ[	ÿ›\úô[ù€\Xú¯†)àãÀõZ[ù
BàBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–îì–Q–T’àêúõÿYÿ\›[ô»	ÿ›\úô[ù€\Xú»õ›]OI⁄Yà
][›HHKö\’[òJHïSêHà[ŸHYà
\ŸRö] HííU»à[ŸHîî»üH
][\	úõÿYÿ\›][\ HãàòY\ïY»HìQSQHãà
BàÀ»çKéKçÕç»8†%ö]ôHŸ[õÿîôY⁄\›ûH›]HXX⁄[ôH[ô]ÀY[ôÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\Àêîì–Q–T’Së HHÿ]⁄
Œàõ›ÿXõJHﬂBÇà€ìŸ º'‰‚à—SPïQŒà⁄Y€ö[ô»[ôúõÿYÿ\›[ô»
õ›]\èI›ô\›[úõ›]\üKôúOI›ô\›[ö\‘ôúTõ›]_JKããàãòYRYõZ[ù
Bàò[[òTô\RYHYà
][›HHKö\’[òJHô\›[úô\]Y\›Y[ŸHù[à⁄Y»Hÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJô\›[ùò\ŸMç\ŸRö]Àö]’\[òTô\RYÀöù\]\ê\RŸ^Kô\›[ö\‘ôúTõ›]Kô\›[úŸ[ô\ê€€\]XõJBà€‹ŸP]]‹ö]T⁄Y»H⁄Y¬àûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôT‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–úõÿYÿ\›
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€Œàè»ãà⁄Y€ò]\ôHH⁄Y»Œààãàò]–[[›[ùHò]òKõX]êöY“[ùYŸ\ãñëTìÀàõÿŸ\‹€‹àHYà
][›HHKö\’[òJHíïTUTó’SêHà[ŸHô\›[úõ›]\ãàõ›]HHYà
][›HHKö\’[òJHïSêHà[ŸHYà
\ŸRö] HííU»à[ŸHîî»ãàôX\€€àHôX\€€ãàŸ[ô\ò][€àHûH»õ›ù[ù[YP€€ùõ€\ãò›\úô[ùŸ[ô\ò][€ä
HHÿ]⁄
Œàõ›ÿXõJH»Kà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBà€ìŸ º'‰‚à—SPïQŒàò[úÿX›[€à€€ôö\õYYH⁄YœI‹⁄YÀùZŸJå
_KããàãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–””ëíTìQQà∏ß!HŸ[€€ôö\õYY€ãX⁄Z[à	ÿ›\úô[ù€\Xú»
][\	úõÿYÿ\›][\ Hãà⁄Y»H⁄YÀòY\ïY»HìQSQHãà
BàÀ»çKéKçÕç»8†%ö]ôHŸ[õÿîôY⁄\›ûH›]HXX⁄[ôH[ô]ÀY[ôÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\Àê””ëíTìRSë HHÿ]⁄
Œàõ›ÿXõJHﬂBàúôXZ»À»›XÿŸ\‹»8†%^]€\YŸHY\ÇàHÿ]⁄
úõÿYÿ\›^à^Ÿ\[€äH¬à\›úõÿYÿ\›^Ÿ\[€àHúõÿYÿ\›^àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ úõÿYÿ\›^õY\‹ÿYŸHŒàù[ö€õ›€àäBàò[\‘€\YŸHHÿYôKò€€ùZ[ú åMŒãY€õ‹ôPÿ\ŸHHùYJHàÿYôKò€€ùZ[ú åMŒHãY€õ‹ôPÿ\ŸHHùYJHàÿYôKò€€ùZ[ú ï€”]T€€ôXŸZ]ôYãY€õ‹ôPÿ\ŸHHùYJHàÿYôKò€€ùZ[ú î€\YŸHãY€õ‹ôPÿ\ŸHHùYJHàÿYôKò€€ùZ[ú î€\YŸU€\ò[òŸQ^ŸYYYãY€õ‹ôPÿ\ŸHHùYJBàYà
Z\‘€\YŸJH¬àÀ»õ€ã\€\YŸHòZ[\ôH
“SKëîKêUW”SRUQ—VTëQàÀ»Sî’QëíP“QSï—ïSëÀïTUTó–ïRS—êRSQ8†)äKàôK\][›[ô¬àÀ»]Y⁄\à€\YŸH€€â›[8†%ôK]õ›»»H›]\ÇàÀ»ÿ]⁄õ‹à€\‹⁄YöXÿ][€à[ôô]ûK\]Y]YH[ôŸôãÇàõ›»úõÿYÿ\›^àBàÀ»€\YŸH\úõ‹à8†%ù[\HYô][YH€›[ù\à
õ‹ô[ú⁄X‹»
¬àÀ»‹õ‹‹ÀXÿ[\ÿÿ[][€äKŸÀ[ôûHHô^Y\ãÇàò[úò»Hô\õ–ò[[òŸTô]öY\ÀõY\ôŸJÀõZ[ù
»óÿúõÿYÿ\›ãJH»€»Oà€
»HHŒàBà€ìŸ ∏¶®H”TQ—H	ÿ›\úô[ù€\Xú»8†%\ÿÿ[][ô»SSQQPUSH
Yô][YHúõÿYÿ\›ô]öY\œIúò x†)àãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàî”TQ—H	ÿ›\úô[ù€\Xú»
[ã[[ôH][\	úõÿYÿ\›][\ H8†%\ÿÿ[][ô»ãà€\YŸPú»H›\úô[ù€\òY\ïY»HìQSQHãà
BàÀ»çKåçLà8†%ÿ]ôHéàMŒ[ùò[Y]\»H[\õ›]BàÀ»ÿX⁄Hõ‹à\»Z[ù[ôYù\àHŸX€€ô›öZŸH›\ô\‹Ÿ\¬àÀ»[\\ôX›õ‹àå»
\à‹\ò]‹à‹X Kà\ã[Z[ù€õH8†%àÀ»HYôô\ô[ùZ[ùÿ[à›[ûH[\\ôX›ÇàYà
ÿYôKò€€ùZ[ú åMŒãY€õ‹ôPÿ\ŸHHùYJJH¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[úôX€‹ô[\MŒ
ÀõZ[ù
HBàÿ]⁄
Œàõ›ÿXõJHﬂBàBàÀ»€‹€€ù[ùY\»»ô^€\YŸHY\ÇàBàBÇàÀ»çKéKç8†%STëïSàTëP’êSêP“ÀÇàÀ¬àÀ»Yà[ù\]\à€\YŸHY\ú»òZ[YSë\»\»H[\ôù[ÇàÀ»Z[ù
›Yôö^	‹[\	 KûH€ôHö[ò[\ôX›Ÿ[öXH[\‹ù[àÀ»Y⁄ö[ô»ÿÿ[]òY[ô»TKà\»û\\‹Ÿ\»ù\]\à[ù\ô[BàÀ»[ôõ›]\»õ›Y⁄[\ôù[â‹»›€àõ€ô[ô»›\ùôH»[\›ÿ\àÀ»SSH8†%Hô[ùYH]‹öY⁄[ò[HZ[ùY[ô\›YH⁄Ÿ[ãÇàÀ¬àÀ»⁄H\»€‹ö‹»⁄[àù\]\àŸ\€â›à[\ôù[â‹»õŸ‹ò[BàÀ»XÿŸ\»H—S⁄YHò]]ô[H
ù\]\àëîHõ›öY\ú»ôYù\ŸBàÀ»][ôY]\»[ò[ZX‘€\YŸH⁄[][]‹à€€ú⁄\›[ùH›ÿò[¬àÀ»HX⁄ŸY€\€à[\]ZYY[Y\ KàH[\ôù[àõŸ‹ò[BàÀ»€€\]\»Z[î€€›]H^X›Y
à
HH€\YŸT\òŸ[ùÃL
BàÀ»[ô]	‹»H€õH€ãX⁄Z[àõ›[ô8†%õ»‹òX€Kõ¬àÀ»⁄[][][€àÿ\ÇàÀ¬àÀ»ŸH\ŸHHQ“€\YŸH\òŸ[ù
òZ[ãY^]àÕIKõ‹õX[àÃ	JBàÀ»ôXÿ]\ŸHH‹\ò]‹à\»ù\›^]\›YHù\]\à][\¬àÀ»[ô\»ò\à\›ÿ\ö[ô»Xõ›]ôòZ\àà^X›][€à8†%^HôYYàÀ»HòY»›]ŸàHÿ[]][ûHöXŸKÇàÀ»çKéKç8†%STëïSà»ST’–TTëP’êSêP“»
[Z[ù KÇàÀ¬àÀ»çKéKçLŒàõ‹YHô[ô’⁄]
ú[\äHÿ]Kà‹\ò]‹à[ú⁄Y⁄ÇàÀ»	€[‹›Ÿà€€[òHÿ[àôHXÿŸ\‹ŸY[ôòYYöXH[\ù[à[ôàÀ»[\›ÿ\\ŸH^\»€»X^HôHXõH»\ŸH]»›\ÇàÀ»Yò[ùYŸIÀà[\‹ù[Y⁄ö[ô…‹»€€Iÿ]]…»õ›]\»õ›Y⁄àÀ»[\ôù[àõ€ô[ô»›\ùôK[\›ÿ\SSKSëò^Y][H€€»8†%€¬àÀ»]ÿ[à[\‹òYX]Y⁄Ÿ[ú»
”‘ëJKò^Y][K[€õHY[Y\¬àÀ»
T”SëPSQSãì’TìëVJK]ô[à“TîQKàù\]\à^]\›[ô¬àÀ»YX[ú»HòY»\»›X⁄ﬁHUïTUTâ‹»õ›]\Œ»[\‹ù[	‹¬àÀ»Ÿ\\ò]Hõ›]\à€€X^H›[ö[ôHÿ^H›]ÇàÀ¬àÀ»Yà[ù\]\à€\YŸHY\ú»òZ[Y[ôSTQíTî’Yâ›àÀ»[ôXYHûH
çKéKçLäK[ùõ⁄ŸHH\ôX›\õ›]Hò[òX⁄»\¬àÀ»H\›][\àòZ[ãY^]Ÿ]»ÕIH€\YŸKõ‹õX[Ã	KÇàÀ»çKéKçMH8†%STëT–’QHô]ûKàYàõ›STQíTî’[ôBàÀ»[ù\ôHù\]\àY\àòZ[YZŸH€ôH[‹ôH⁄›][\‹ù[àÀ»⁄]ù[\Y€\YŸH
L	Hõ‹õX[»L	HòZ[äKà[\‹ù[	‹¬àÀ»€€Hò]]»àõ›]\à€›ô\ú»õ€ô[ô»›\ùôH
»[\›ÿ\
»ò^Y][KàÀ»€»HY⁄\àX\öŸ]ZY⁄[ô\ôH⁄[àù\]\à[ôBàÀ»[ö]X[STQíTî’][\€›[â›ÇàYà
⁄Y»OHù[
H¬àò[ô\ÿ›YT€\HHÀ»çKéKåMLç8†%IH]ôHŸ[ÿ\
ÿ\»LÕL»ùZ[\à[€»ÿ\ Bàò[ô\ÿ›YRö]»HÀöö]—[òXõYàò[ô\ÿ›YU\HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùH\—òZ[ë^]
BàÀ»çKåçLà8†%ÿ]ôHéà⁄⁄\[\ô\ÿ›YHYàHZ[ù\»›\úô[ùBàÀ»[àå»[\Y\ôX››\ô\‹⁄[€àYù\àûMŒ›öZŸ\ÀàBàÀ»ô\ÿ›YH⁄[ôHôKX][\Y€àH]\àX⁄»€òŸH€€€›€ÇàÀ»[\Ÿ\Œ»HŸ[X\ŸH\ú⁄\›À€»H‹⁄][€à›^\¬àÀ»[ù[ù\ô\Ÿ\ùôYÇàò[[\›\ô\‹ŸYHûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[ö\‘[\\ôX››\ô\‹ŸY
ÀõZ[ù
BàHÿ]⁄
Œàõ›ÿXõJH»ò[ŸHBàYà
[\›\ô\‹ŸY
H¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘ì’UW‘““TQàîST‘ëT–’QW‘““TQ8†%Z[ù\»[àMŒ›\ô\‹⁄[€à€€€›€ãàãàòY\ïY»HìQSQHãà
BàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîST‘ëT–’QW‘““TQ‘’TëT‘—QãõZ[ùI›ÀõZ[ùùZŸJL
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàH[ŸH¬à⁄Y»HûT[\‹ù[Ÿ[
à»HÀàÿ[]Hÿ[]à⁄Ÿ[ï[ö]»HôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHYà
\—òZ[ë^]
HîST‘ïS—VU—êRSó‘ëT–’QHà[ŸHîST‘ïS—VU‘ëT–’QHãàô\]Y\›YZT]HH€€ôö\õYYŸ[ZT]KàŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãà
OÀúò]–[[›[ùŒàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìKà€\›Hô\ÿ›YT€\àö[‹ö]QôYT€€HYà
\—òZ[ë^]
Hå[ŸHåÀà\ŸRö]»Hô\ÿ›YRö]Ààö]’\[\‹ù»Hô\ÿ›YU\àŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãàXô[Y»HYà
\—òZ[ë^]
HëVUQêRSãTëT–’QHà[ŸHëVUTëT–’QHãà
BàÀ»çKåçLà8†%›XÿŸ\‹Ÿù[[\ô\ÿ›YH€X\ú»H›\ô\‹⁄[€àõ‹à\»Z[ùÇàYà
⁄Y»OHù[
H¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[úôX€‹ô[\Ÿ[⁄ ÀõZ[ù
HBàÿ]⁄
Œàõ›ÿXõJHﬂBàBàHÀ»[ô[ŸH
[\ô\ÿ›YH[›ŸYúò[ò⁄
BàBÇàYà
⁄Y»OHù[
H¬àÀ»[[ã[[ôH€\YŸHY\ú»^]\›Y8†%ôK]õ›»»›]\ÇàÀ»ÿ]⁄€»ŸHŸ]H€\‹⁄YöYYõ›]HòZ[\ôKàçKåçéHŸY\¬àÀ»\»\»õ€ãX€‹⁄[ôÀ€õ€ãXõÿ⁄⁄[ô»ì’UW—êRSQ”ì◊‘“Q”êUTëKàÀ»õ›HŸ[Yö[ò[]Hò][[ôõ›H[ô[ô‘Ÿ[]Y]YH]⁄Çàõ›»\›úõÿYÿ\›^Ÿ\[€àŒàù[ù[YQ^Ÿ\[€äàìì◊‘“Q”êUTëNà	›Àúﬁ[Xõ€Nà[õ›öY\ú»^]\›Y⁄]›]úõÿYÿ\›⁄Y€ò]\ôH]	ÿúõÿYÿ\›€\Y\ãõ\›

_Xú»äBàBÇàÀ»çKéKçŒ8†%ÿ\\ôHHö[ò[õ€ã[ù[][›H
H€ôH⁄‹ŸBàÀ»úõÿYÿ\›X›X[H[ôY
H€»›€ú›ôX[HìX]»[ôàÀ»ŸŸ⁄[ô»€â›ö\€›[â‹»ù[XõK\ôXŸZ]ô\à⁄X⁄‹ÀÇàÀ»çKéKåMLé8†%ö[ò[][›H\»ïS⁄[àHŸ[[ôYöXHHST¬àÀ»SUT»\ôX›õ›]H⁄]õ»ù\]\à][›H
ù\]\î][›U[ò]òZ[XõJKÇàÀ»]\»\ŸY€õHõ‹àìY\‹^Hò[òX⁄»
»€‹›\ôYX›‹àX\õö[ôÀàÀ»õ›Ÿà⁄X⁄[ôXYHôYô\àHôX[€ãX⁄Z[àÿ[][Kà›X\ôàÀ»H€»\ôYú»ò]\à[àîH€àH\ôôX›H€€Ÿ\ôX›\õ›]HŸ[Çàò[ö[ò[][›Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî›ÿ\][›O»H][›BààûH¬àÀ»çKéKçMH8†%ëQHêRTìëT‘»íVÇàÀ»ô]ö[›\€H\»€€\]YôYH\»çIHŸàH—Sì–—QQ¬àÀ»
][›Kõ›][[›[ù
K⁄X⁄YX[ù‹⁄[ô»Ÿ[»ZYò\à\‹¬àÀ»[à⁄[õö[ô»Ÿ[»[ô›[[‹‹»ùY‹»ZY8¢b””ôXÿ]\ŸBàÀ»HôYP[[›[ù€€èHåH›X\ôõ›[ôY]]ÿ^Kà]ô\ûBàÀ»›\àôYH]
õŸö][ÿ⁄À\ùX[\Ÿ[åK›åãù^JBàÀ»[ôXYH\Ÿ\»‹Àò€‹›€€\»Hò\⁄\Àà[Y€à\»]ÇàÀ»ôYHHçIHŸàSïñHêT“TÀ\YY»]ô\ûH]ôHŸ[àÀ»⁄]\à]ÿ\»õŸö]XõH‹àõ›à‹\ò]‹à\ôX›]ôNÇàÀ»õ]ôHò[úÿX›[€àôY\»⁄›[ôH€à[Ÿ[»õŸö]XõBàÀ»‹àõ›àÇàò[ôYP[[›[ù€€H‹Àò€‹›€€
àQSQW’êQSë◊—ëQW‘Tê—SïàYà
ôYP[[›[ù€€èHëQW‘—Së”RSó‘””
H¬àò[ôYUÿ[]HHôYP[[›[ù€€
àëQW‘‘U‘êUS¬àò[ôYUÿ[]àHôYP[[›[ù€€
à
KåHëQW‘‘U‘êUS BààÀ»çKéKåML8†%⁄[ô€Hô\€€ô\àÿ[[ô\»ì’⁄\ô\ÀàÀ»ôY\ôX›[ô»[ûHŸ[ãXYô\‹ŸYôYHÿ[]»H›\ãÇàŸ[ôôYT‹]
ÿ[]ôYUÿ[]KôYUÿ[]ãôôYHäBààÀ»çKåçåé8†%UëH‘êP”HS—Nà⁄\ôHHôX€‹ô]ôQôYH€⁄ÀÇàÀ»]ôS[ôQ€›ô\õõ‹ãúôX€‹ô]ôQôYHÿ\»Yö[ôYù]YëTì»ÿ[\ú¬àÀ»
ôYT›[T€€LôYSèL[à]ô\ûH‹ô\‹ù
Kà⁄]›]\ÀBàÀ»ôYKYX€€õ€ZX»õ€‹à›X\ô
“VëW–ëS’◊—ëQW—P””ì”RP◊—ì”‘óÕåç Hÿ\¬àÀ»YôôX›]ô[H\ÿXõY[ô\›\⁄^ôYù^\»Ÿ\X][ô»ôY\»àõŸö]ÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKì]ôS[ôQ€›ô\õõ‹ãúôX€‹ô]ôQôYJôYP[[›[ù€€
HHÿ]⁄
Œàõ›ÿXõJHﬂBàà€ìŸ º'‰ÆêQSë»ëQNà	‘›ö[ôÀôõ‹õX]
âKçôàãôYP[[›[ù€€
_H””
çIHŸà[ùûJH‹]LÕLãòYRYõZ[ù
Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'‰ÆUëH—SëQNà	ŸôYP[[›[ù€€H””‹]»õ›ÿ[]»
[ùûKXò\⁄\ÀõXY€õ‹›X HäBàH[ŸH¬àÀ»çKåçÃLç8†%H⁄[[ùúò[ò⁄àH]ôHŸ[⁄‹ŸHôYHõ›[ô¬àÀ»ô[›»HŸ[ôZ[ö[][H⁄\ôŸYì’Së»[ôÿZYõ›[ôÀ€¬àÀ»ôôY\»\ôHõ›ôZ[ô»Ÿ[ù€à]ô\ûHòYHà[ôôôY\»Ÿ\ôHô]ô\ÇàÀ»YH€à]òYHàŸ\ôHHÿ[YHÿúŸ\ùò][€ãÇàÀ¬àÀ»Hò\⁄\»\»‹Àò€‹›€€[ôHXÿŸ\[òŸH]Y]€à\¬àÀ»ùZ[\»òZ[[ô»ó–êT“T◊—SKàH]ôH‹⁄][€à⁄‹ŸH€‹›àÀ»ò\⁄\»\»ôX€‹ôY\»ô\õ»\ôYõ‹ôHõŸXŸ\»Hô\õ»ôYH€à[ÇàÀ»›\ù⁄\ŸH\ôôX›H€€ŸŸ[à‹][ô»H€»ÿ\Ÿ\»\ôH\¬àÀ»⁄]X⁄Y\»⁄]\àHôYH€€\Z[ù\»HôYK\]YôX›‹ÇàÀ»H€‹›Xò\⁄\»YôX›ŸX\ö[ô»]»€›\ÀÇàYà
‹Àò€‹›€€Hå
H¬à\[[ôRX[€€X›‹ãõXô[[ò ëëQW‘““TQ÷ëTì◊–”‘’–êT“T◊ÕÃLçäBà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãàº'Ê™UëH—SëQH““TQ8†%€‹›ò\⁄\»\»	‹‹Àò€‹›€€Hõ‹à	›Àúﬁ[Xõ€Kàà
¬àìõ»ôYHÿ\»⁄\ôŸYôXÿ]\ŸH\ôH\»õ»ò\⁄\»»⁄\ôŸH]YÿZ[ú›àäBàH[ŸH¬à\[[ôRX[€€X›‹ãõXô[[ò ëëQW‘““TQ—T’–êT“T◊ÕÃLçäBàBàBàHÿ]⁄
ôYQ^à^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'Ê™ëQH—SëêRSQ8†%êQSë»ôYHì’Ÿ[ù⁄[ô]ûHô^òYNà	ŸôYQ^õY\‹ÿYŸ_HäBàÀ»çKéKååçéàùY»Õ»8†%[ú]Y]YHõ‹àô]ûH
ôXÿ[›[]H8†%ôYP[[›[ù€€õ›[àÿ]⁄ÿ€‹JBàò[ôYP[]Ÿ[ô]ûHH
‹Àò€‹›€€
àQSQW’êQSë◊—ëQW‘Tê—Sï
Kò€Ÿ\òŸP]X\›
å
BàYà
ôYP[]Ÿ[ô]ûHèHåJH¬àôYTô]ûT]Y]YKô[ú]Y]YJêQSë◊—ëQW’–SUÃKôYP[]Ÿ[ô]ûH
àëQW‘‘U‘êUSÀúŸ[ŸôYW›ÃHäBàôYTô]ûT]Y]YKô[ú]Y]YJêQSë◊—ëQW’–SUÃãôYP[]Ÿ[ô]ûH
à
KåHëQW‘‘U‘êUS KúŸ[ŸôYW›ÃàäBàBàBààÀ»çKéKçM^Kﬁà8†%êQKUëTíQíQTàïST—SUU‘íUH
‹X»][\¬àÀ»ã
Kàù[à\\úŸHö\ú›»YàSëQŸH⁄⁄\HYÿXﬁBàÀ»ÿ[]\€]\ö\›X‹»
»\›Xù\›\à[ù\ô[Kà‹\ò]‹éÇàÀ»íH›[ÿ[ùHŸ[ô\öYöYY[ô]H⁄Ÿ[ú»€X\ÇàÀ»Hÿ[][ôô]\õàH€€à8†%ô\öYöY\àô\]Z\ô\»ì’àÀ»⁄Ÿ[ú»€X\ôYSë””ôXŸZ]ôYôYõ‹ôHX€\ö[ô»SëQÇàò\àù[Ÿ[ô\öYöYY€€à€ô»HLSàò\àô\öYöY\ì[ôYHò[ŸBàò\àô\öYöYYŸ[ù]çéàòYUô\öYöY\ãîŸ[ô\›[»Hù[àù[à¬àYà
⁄YÀö\–õ[ö 
H⁄YÀú›\ù’⁄]
îSï”W»äJHô]\õêù[ÇàÀ»çKéKçÕç»8†%ö]ôHŸ[õÿîôY⁄\›ûH›]HXX⁄[ôH[ô]ÀY[ôÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\ÀïëTíQñRSë HHÿ]⁄
Œàõ›ÿXõJHﬂBàò[ú‹àHûH¬àòYUô\öYöY\ãùô\öYûTŸ[
ÿ[]⁄YÀÀõZ[ù[Y[›]\»HåÃ
BàHÿ]⁄
ôûàõ›ÿXõJH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKï–TìíSëÀà∏¶®òYUô\öYöY\äù[\Ÿ[
Hô]Œà	›ôûõY\‹ÿYŸOÀùZŸJå
_Hãà⁄Y»H⁄YÀòY\ïY»HìQSQHãà
Bàù[àBà⁄[à
ú‹èÀõ›]€€YJH¬àòYUô\öYöY\ãì›]€€YKìSëQOà¬àù[Ÿ[ô\öYöYY€€Hú‹ãú€€ôXŸZ]ôY[\‹ù¬àô\öYöY\ì[ôYHùYBàô\öYöYYŸ[ù]çàHú‹Çà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’‘Tî—W”“Àà∏ß!HïS—SSëQàò]–€€ú›[YYI›ú‹ãúò]’⁄Ÿ[ê€€ú›[YYHZOI›ú‹ãùZU⁄Ÿ[ê€€ú›[YYôõ]

_H€€ôXŸZ]ôYI›ú‹ãú€€ôXŸZ]ôY[\‹ùﬂH[I⁄Yà
ú‹ãù⁄Ÿ[êXÿ€›[ù€‹ŸYù[^]
Hà
UH€‹ŸY
Hà[ŸHàüHãà⁄Y»H⁄YÀ⁄Ÿ[ê[[›[ùHú‹ãùZU⁄Ÿ[ê€€ú›[YYòY\ïY»HìQSQHãà
BàYà
ú‹ãù⁄Ÿ[êXÿ€›[ù€‹ŸYù[^]
H¬àûH¬à‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãúôX€‹ô]]‹ö]]]ôU\úŸP€‹ŸJàÀõZ[ùÀúﬁ[Xõ€⁄YÀàî—S’‘Tî—W”“◊—ïS—VU’“—Só–””î’SQQ–UW–”‘—Q‘””‘ëUTìëQÇà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàBàÀ»çKéKçÕç»8†%\õZ[ò[SëQ›]KàY[\›[ù»ôX€€ò⁄[\ÇàÀ»[€»ÿ[»X\ö”[ôY⁄[à€ãX⁄Z[àò[[òŸHôXX⁄\»ô\õÀÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKõX\ö”[ôY
ÀõZ[ù⁄Y HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKåççH0©ÕK©ÕÀ©Õ©ÃJ©Ãà8†%ù[Y^]ÿ[õ€öXÿ[⁄\ôKÇàûH¬àò[‹⁄][€ëŸ[àHÀú‹⁄][€ãô[ùûU[YKùZŸRYà»]àHŒààò[€€ò]»Hú‹ãúò]’⁄Ÿ[ê€€ú›[YYàò[ôX€›ô\ôYHò]òKõX]êöY“[ùYŸ\ãùò[YSŸäú‹ãú€€ôXŸZ]ôY[\‹ù Bàò[ÿ[]YàHûH»ÿ[]úXõX“Ÿ^PçNHÿ]⁄
Œàõ›ÿXõJH»ààBàYà
€€ò]Àú⁄Y€ù[J
Hà	âà‹⁄][€ëŸ[àà
H¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ëX⁄[X[[ùY‹ö]P]]‹ö]MçBàúôX€‹ô€€ò] ÀõZ[ù‹⁄][€ëŸ[ã€€ò] BàBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[]ô[ù›ôX[MçKò\[ô
àÿ[]Hÿ[]YãàZ[ùHÀõZ[ùà‹⁄][€ëŸ[ô\ò][€àH‹⁄][€ëŸ[ãà\HH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]àêÿ[õ€öXÿ[]ô[ù›ôX[MçKï\Kî—S’ëTíQíQQàò]‘]HH€€ò]Àà[\‹ù»HôX€›ô\ôYà€›\òŸHHëïS‘—S’‘Tî—W”“»ãàõ›HHú⁄YœI‹⁄YÀùZŸJMä_Hù[^]I›ú‹ãù⁄Ÿ[êXÿ€›[ù€‹ŸYù[^]Hãà
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹ùõ€[‘›‹ôMçKò\[ô]ô[ù
àÿ[]Hÿ[]YãàZ[ùHÀõZ[ùà‹⁄][€ëŸ[ô\ò][€àH‹⁄][€ëŸ[ãà⁄[ôHî—S’ëTíQíQQãàò]‘]HH€€ò]Àà[\‹ù»HôX€›ô\ôYà€›\òŸHHëïS‘—S’‘Tî—W”“»ãàõ›HHú⁄YœI‹⁄YÀùZŸJMä_Hãà
BàYà
ú‹ãù⁄Ÿ[êXÿ€›[ù€‹ŸYù[^]
H¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ï\õZ[ò[ö[ò[]P]]‹ö]MçBàõX\ö’\õZ[ò[
àÀõZ[ù‹⁄][€ëŸ[ãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]àï\õZ[ò[ö[ò[]P]]‹ö]MçKï\õZ[ò[ê”‘—Q—ïS—VUàëïS‘—S–UW–”‘—Qãà
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]î‹⁄][€ëŸ[ô\ò][€êúöYŸMçBàò€X\äÀõZ[ù
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ê⁄X⁄‹⁄[ùôX€›ô\ûP]]‹ö]MçBàúô]\ôJÿ[]YãÀõZ[ù‹⁄][€ëŸ[äBàBàHÿ]⁄
Œàõ›ÿXõJHﬂBàBàòYUô\öYöY\ãì›]€€YKëêRSQ–””ëíTìQQOà¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQ–””ëíTìQQàº'Ê™ïS—SêRSQ–””ëíTìQQàY]Kô\úèI›ú‹ãù\úüHãà⁄Y»H⁄YÀòY\ïY»HìQSQHãà
BàÀ»çKéKçÕç»8†%\õZ[ò[êRSQ—íSêS›]KÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\ÀëêRSQ—íSêS
HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKéKéMé8†%çÀQàŸ[òZ[\ôR\›‹ûHôX€‹ô
ÿ\»ö[KYXY
KÇàÀ»‹\ò]‹à‹XŒàÿ[\àUT’€€ú›[ôYõ‹ôHô]ûN»Yà\›àÀ»[ùûH\»‘Tî—W”“◊–ïU‘ì’UW—êRSQ»õ›ô]ûHõ[ôKÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKúôX€‹ô
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à⁄[ôH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKí⁄[ôîì’UW—êRSQ–QïTó–îì–Q–T’àôX\€€àHëêRSQ–””ëíTìQQàY]Kô\úèI›ú‹ãù\úüHãà⁄Y»H⁄YÀà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàòYUô\öYöY\ãô[ôŸ[
ÀõZ[ù
Bàõ›»ù[ù[YQ^Ÿ\[€äîŸ[òZ[Y€ãX⁄Z[éà	›ú‹ãù\úüHäBàBàòYUô\öYöY\ãì›]€€YKíSê””ê”T“UëW‘SëSëÀàòYUô\öYöY\ãì›]€€YKïëTíQíP–US”ó—Tîì‘àOà¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀà∏£Ï»ïS—SSê””ê”T“UëH8†%ò[[ô»õ›Y⁄»YÿXﬁHÿ[]\€
»\›Xù\›\àãà⁄Y»H⁄YÀòY\ïY»HìQSQHãà
BàBàù[OàﬂBàBàBààûH¬àYà
ô\öYöY\ì[ôY
H¬àÀ»ô\öYöY\àõ›ôYõ›⁄Y\»€ãX⁄Z[à8†%⁄⁄\YÿXﬁHÿ[]\€Ÿ\›Xù\›\ãÇà€ìŸ ∏ß!HïS—SëTíQíQQíPHTTî—Nà	›Àúﬁ[Xõ€H⁄YœI‹⁄YÀùZŸJå
_x†)àãòYRYõZ[ù
BàH[ŸH¬àôXYú€Y\
ML
Bàò[‹›Ÿ[ò[[òŸ\»Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

Bàò[‹›Ÿ[X\[\HH‹›Ÿ[ò[[òŸ\Àö\—[\J
Bàò[ô[XZ[ö[ô’⁄Ÿ[ú»H‹›Ÿ[ò[[òŸ\÷›ÀõZ[ùOÀôö\ú›ŒàåÇàÀ»çKéKçM^åŒH8†%‹\ò]‹à‹X»][\»KÕÕãÕÀŒ⁄\ö[ôÀÇàÀ»Ÿ[ö[ò[^ò][€ê€€‹ô[ò]‹àù[ú»H]Y]‹à
»[Y]BàÀ»ö[ò[^ô\à
»õ‹‹ù[€ò[Tìÿ[»
»ÿ[]ôYúô\⁄
¬àÀ»ÿ[õ€öXÿ[—S”SëQõ‹ô[ú⁄X‹»[àH⁄[ô€H\‹ÀÇàÀ»ô\›YYôõ‹ù»ô]ô\àúôXZ‹»HŸ[õ›ÀÇàûH¬àò[X⁄[X[»H‹›Ÿ[ò[[òŸ\÷›ÀõZ[ùOÀúŸX€€ôàŒà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKï⁄Ÿ[ìYôXﬁX€UòX⁄Ÿ\ãôŸ][ùûSY]Y]JÀõZ[ù
OÀô[ùûQX⁄[X[¬àŒàBàò[ôU⁄Ÿ[îò]»Hù[à¬àò[ZHH‹Àú]U⁄Ÿ[ÇàYà
ZHàå	âàX⁄[X[»à
Hò]òKõX]êöY—X⁄[X[
ZJKõ[›ôT⁄[ùöY⁄
X⁄[X[ Kù–öY“[ùYŸ\ä
Bà[ŸHò]òKõX]êöY“[ùYŸ\ãñëTì¬àBàò[‹›⁄Ÿ[îò]»Hù[à¬àò[ZHHô[XZ[ö[ô’⁄Ÿ[ú¬àYà
X⁄[X[»à
Hò]òKõX]êöY—X⁄[X[
ZJKõ[›ôT⁄[ùöY⁄
X⁄[X[ Kù–öY“[ùYŸ\ä
Bà[ŸHò]òKõX]êöY“[ùYŸ\ãñëTì¬àBàò[ÿ[]€ò]»HYà
‹›Ÿ[X\[\JHù[[ŸH‹›⁄Ÿ[îò]¬àò[[ùûSY]HH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKï⁄Ÿ[ìYôXﬁX€UòX⁄Ÿ\ãôŸ][ùûSY]Y]JÀõZ[ù
Bàò[[ùûT€€‹[ùH[ùûSY]OÀô[ùûT€€‹[ùÀùZŸRYà»]àåHŒà‹Àò€‹›€€àò[[ùûU⁄Ÿ[îò]»H[ùûSY]OÀô[ùûU⁄Ÿ[îò]–€€ôö\õYYÀùZŸRYà»]ú⁄Y€ù[J
HàHŒàôU⁄Ÿ[îò]¬àò[[ù[ùH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[ù[ùòùZ[
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àÀ»]ôTŸ[\»HïSXò[[òŸH]à\ùX[X€\‹¬àÀ»ôX\€€ú»
ì—íU”–“»»–TUS‘ëP”’ëTñH¬àÀ»TïPS’R—W‘ì—íU
H\ôHõ‹òöY[àûHŸ[[ù[ùàÀ»⁄[àúòX›[€èLLÃ
»òZ[è]ùYN»^H]\›ôBàÀ»õ€[›Y»Të‘’‘»ïQ◊—êRSà\ôKÇàôX\€€àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX\€€ê€\‹⁄YöY\ãôù[^]úõ€T›ö[ô ôX\€€äKàô\]Y\›YúòX›[€êú»HLÃÀ»]ôTŸ[\»ù[Xò[[òŸH]à€€ôö\õYYÿ[]ò]»HôU⁄Ÿ[îò]ÀõX^
ò]òKõX]êöY“[ùYŸ\ãì”ëJKàX⁄[X[»HX⁄[X[Àà€\YŸPú»HÀ»õ€⁄⁄ŸY\[ô»€õH8†%X›X[€\]ô\»[àõŸö[Bà[Y\ôŸ[òﬁQòZ[àHùYKà[ùûT€€‹[ùH[ùûT€€‹[ùò€Ÿ\òŸP]X\›
å
Kà[ùûU⁄Ÿ[îò]»H[ùûU⁄Ÿ[îò]ÀõX^
ò]òKõX]êöY“[ùYŸ\ãì”ëJKà
BàYà
‹›Ÿ[X\[\JH¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S—íSêSUW‘SëSë◊‘ëUñHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èSRT‘“Së◊‘‘’–êSSê—W‘ì”—à⁄YœI‹⁄YÀùZŸJMä_HX›[€è[õ◊ÿ€‹ŸW€õ◊⁄õ›\õò[€õ◊€X\õö[ô◊⁄ŸY\€X\ŸHäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúôX€‹ôô]ûJÀõZ[ùî—S—íSêSUW‘SëSë◊‘ëUñW”RT‘“Së◊‘‘’–êSSê—W‘ì”—àäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX€€ò⁄[\ãúô\]Y\›\ôŸ[ùX⁄ î—S—íSêSUW‘SëSë◊‘ëUñW”RT‘“Së◊‘‘’–êSSê—W‘ì”—àäBàHÿ]⁄
Œàõ›ÿXõJHﬂBà€ìŸ ∏£Ï»—S—íSêSUW‘SëSë◊‘ëUñNà	›Àúﬁ[Xõ€HZ\‹⁄[ô»‹›\Ÿ[ÿ[]õ€Ÿà8†%õ»€‹ŸK⁄õ›\õò[€X\õö[ô»Y]ãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀà∏£Ï»—Sö[ò[]H[ô[ô»ô]ûH8†%Z\‹⁄[ô»‹›Xò[[òŸHõ€Ÿãàõ»õ‹õX[—S”“À⁄õ›\õò[€‹ŸKàãà⁄Y»H⁄YÀòY\ïY»HìQSQHãà
Bàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàHÿ]⁄
Nàõ›ÿXõJH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãàîŸ[ö[ò[^ò][€ê€€‹ô[ò]‹ãôö[ò[^ôHô]»
õ€ãYò][
Nà	ŸKõY\‹ÿYŸ_HäBàBÇàò[‹öY⁄[ò[⁄Ÿ[ú»H‹Àú]U⁄Ÿ[ÇàYà
‹›Ÿ[X\[\JH¬àÀ»çKååŒNH8†%]€ZX»ö[ò[]NàH⁄Y€ò]\ôH[€ôH\»õ›BàÀ»€‹ŸKàî»[\K[X\\»õ›‹›Xò[[òŸHõ€Ÿã[ô]\›õ›àÀ»ò[õ›Y⁄»õ‹õX[—S”“À⁄õ›\õò[‹€›ô[X\ŸKÇà€ìŸ ∏£Ï»—S—íSêSUW‘SëSë◊‘ëUñNàî»[\K[X\Yù\à€€ôö\õYY⁄Y»8†%]ÿZ][ô»‹›Xò[[òŸHõ€ŸàãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKï–TìíSëÀà∏£Ï»—Sö[ò[]H[ô[ô»ô]ûNàî»[\K[X\Yù\à€€ôö\õYY⁄YÀàõ»õ‹õX[€‹ŸH[ù[‹›Xò[[òŸK‹õÿŸYY»õ€Ÿà\úö]ô\ÀàãàòY\ïY»HìQSQHãà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúôX€‹ôô]ûJÀõZ[ùî—S—íSêSUW‘SëSë◊‘ëUñW‘î◊—STW”PTäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX€€ò⁄[\ãúô\]Y\›\ôŸ[ùX⁄ î—S—íSêSUW‘SëSë◊‘ëUñW‘î◊—STW”PTäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàH[ŸHYà
‹öY⁄[ò[⁄Ÿ[ú»à	âàô[XZ[ö[ô’⁄Ÿ[ú»à‹öY⁄[ò[⁄Ÿ[ú»
àåJH¬àò[ô[XZ[ö[ô‘›H
ô[XZ[ö[ô’⁄Ÿ[ú»»‹öY⁄[ò[⁄Ÿ[ú»
àL
Kù“[ù

Bà€ìŸ º'Ê™—SSê””TUNà›[€[ô»	‹ô[XZ[ö[ô‘›IHŸà⁄Ÿ[ú»HãòYRYõZ[ù
Bà€ìŸ à‹öY⁄[ò[à	‹öY⁄[ò[⁄Ÿ[ú»ô[XZ[ö[ôŒà	ô[XZ[ö[ô’⁄Ÿ[ú»ãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàº'Ê™Ÿ[[ò€€\]H8†%	‹ô[XZ[ö[ô‘›IH
	‹ô[XZ[ö[ô’⁄Ÿ[úÀôõ]

_JH›[[àÿ[]ãà⁄Ÿ[ê[[›[ùHô[XZ[ö[ô’⁄Ÿ[úÀòY\ïY»HìQSQHãà
BààYà
ô[XZ[ö[ô’⁄Ÿ[ú»àåJH¬à€ìŸ º'ÈÓHT’PïT’Téà][\[ô»»Ÿ[ô[XZ[ö[ô»	ô[XZ[ö[ô’⁄Ÿ[ú»⁄Ÿ[úÀããàãòYRYõZ[ù
BàûH¬àò[\›[àHôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHíïTUTó—T’–ïT’Tàãàô\]Y\›YZT]HHô[XZ[ö[ô’⁄Ÿ[úÀàŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãà
HŒàõ›»ù[ù[YQ^Ÿ\[€äë\›Xù\›\àò[[òŸHõ€Ÿà[ò]òZ[XõHäBàò[\›][›HHŸ]][›U⁄]€\YŸQ›X\ô
ÀõZ[ùù\]\ê\Kî”””RSïà\›[ãúò]–[[›[ùML\–ù^HHò[ŸKàŸ[ZŸ\àHÿ[]úXõX“Ÿ^PçN
Bàò[\›HùZ[⁄]ô]ûJà\›][›Kÿ[]úXõX“Ÿ^PçNàŸ[ô\ï\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHò[ŸJKà
Bàò[\›⁄Y»Hÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJ\›ùò\ŸMçÀöö]—[òXõYYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHò[ŸJKàYà
\›][›Kö\’[òJH\›úô\]Y\›Y[ŸHù[Àöù\]\ê\RŸ^K\›ö\‘ôúTõ›]K\›úŸ[ô\ê€€\]XõJBàà€ìŸ º'ÈÓHT’PïT’Tà’P–—T‘Œà€€ô[XZ[ö[ô»⁄Ÿ[ú»⁄YœIŸ\›⁄YÀùZŸJå
_KããàãòYRYõZ[ù
BààôXYú€Y\
ML
Bàò[ö[ò[ò[[òŸ\»Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

Bàò[ö[ò[ô[XZ[ö[ô»Hö[ò[ò[[òŸ\÷›ÀõZ[ùOÀôö\ú›Œàåà€ìŸ º'ÈÓHT’PïT’Téàö[ò[ò[[òŸHH	ö[ò[ô[XZ[ö[ô»⁄Ÿ[ú»ãòYRYõZ[ù
BàHÿ]⁄
\›^à^Ÿ\[€äH¬à€ìŸ ∏¶®;Ó#»T’PïT’TàêRSQà	Ÿ\›^õY\‹ÿYŸOÀùZŸJå
_HãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™Ÿ[[ò€€\]HHãàâ›Àúﬁ[Xõ€Nà	‹ô[XZ[ö[ô‘›IH⁄Ÿ[ú»›[[àÿ[]8†%Ÿ[]Y]YYõ‹àô]ûKàãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì BàÀ»çKéKçLéàôK]õ›»€»‹⁄][€à\»ì’€X\ôY[ôHŸ[\»ôK\]Y]YYàõ›»ù[ù[YQ^Ÿ\[€äîŸ[[ò€€\]Nà	ô[XZ[ö[ô‘›	H⁄Ÿ[ú»ô[XZ[àYù\à\›Xù\›\àòZ[Y
	Ÿ\›^õY\‹ÿYŸOÀùZŸJ
_JHäBàBàBàH[ŸH¬à€ìŸ ∏ß!H—SëTíQíQQà⁄Ÿ[àò[[òŸH\»õ›»	‹ô[XZ[ö[ô’⁄Ÿ[úﬂH
ÿ\»	‹öY⁄[ò[⁄Ÿ[ú HãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW’“—Só—””ëKà∏ß!H⁄Ÿ[àYù‹›ÿ[]ô[XZ[ö[ôœI‹ô[XZ[ö[ô’⁄Ÿ[úÀôõ]

_H
ÿ\»	€‹öY⁄[ò[⁄Ÿ[úÀôõ]

_JHãà⁄Ÿ[ê[[›[ùHô[XZ[ö[ô’⁄Ÿ[úÀòY\ïY»HìQSQHãà
BàÀ»çKéKçÕç»8†%\õZ[ò[SëQ›]HöXHYÿXﬁHô\öYûH]ÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKõX\ö”[ôY
ÀõZ[ù⁄Y HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»ô\öYûH””ô]\õôYûH⁄X⁄⁄[ô»ò[[òŸHù[\àûH¬àôXYú€Y\

Bàò[ô]‘€€Hÿ[]ôŸ]€€ò[[òŸJ
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW‘””‘ëUTìëQàº'‰¨””ò[[òŸHYù\àŸ[à	€ô]‘€€ôõ]

_H
ÿ\»	›ÿ[]€€ôõ]

_JHãà€€[[›[ùHô]‘€€òY\ïY»HìQSQHãà
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàBàHÀ»[ô[ŸH
ô\öYöY\ì[ôYOHò[ŸH8°§àYÿXﬁH]
BàHÿ]⁄
ô\öYûQ^àù[ù[YQ^Ÿ\[€äH¬àõ›»ô\öYûQ^àHÿ]⁄
Nà^Ÿ\[€äH¬à€ìŸ ∏¶®;Ó#»—SëTíQíP–US”éàò[[òŸH⁄X⁄»òZ[Y
	ŸKõY\‹ÿYŸOÀùZŸJ
_JHãòYRYõZ[ù
BààûH¬àôXYú€Y\
å
Bàò[ô]ûPò[[òŸ\»Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

Bàò[ô]ûTô[XZ[ö[ô»Hô]ûPò[[òŸ\÷›ÀõZ[ùOÀôö\ú›ŒàåààYà
ô]ûTô[XZ[ö[ô»à‹Àú]U⁄Ÿ[à
àåJH¬àò[ô]ûT›H
ô]ûTô[XZ[ö[ô»»‹Àú]U⁄Ÿ[à
àL
Kù“[ù

Bà€ìŸ º'Ê™—SëTíQíP–US”àëUñNà›[€[ô»	‹ô]ûT›IHŸà⁄Ÿ[ú»HãòYRYõZ[ù
BààYà
ô]ûTô[XZ[ö[ô»àåJH¬à€ìŸ º'ÈÓHT’PïT’Tà
ô]ûJNà][\[ô»»Ÿ[ô[XZ[ö[ô»	ô]ûTô[XZ[ö[ô»⁄Ÿ[úÀããàãòYRYõZ[ù
BàûH¬àò[ô]ûQ\›[àHôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHíïTUTó—T’–ïT’Tó‘ëUñHãàô\]Y\›YZT]HHô]ûTô[XZ[ö[ôÀàŸ[òYRŸ^HHŸ[òYRŸ^KàòY\ïY»HìQSQHãà
HŒàõ›»ù[ù[YQ^Ÿ\[€äë\›Xù\›\àô]ûHò[[òŸHõ€Ÿà[ò]òZ[XõHäBàò[\›][›HHŸ]][›U⁄]€\YŸQ›X\ô
ÀõZ[ùù\]\ê\Kî”””RSïàô]ûQ\›[ãúò]–[[›[ùå\–ù^HHò[ŸKàŸ[ZŸ\àHÿ[]úXõX“Ÿ^PçN
Bàò[\›HùZ[⁄]ô]ûJà\›][›Kÿ[]úXõX“Ÿ^PçNàŸ[ô\ï\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHò[ŸJKà
Bàò[\›⁄Y»Hÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJ\›ùò\ŸMçÀöö]—[òXõYYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHò[ŸJKàYà
\›][›Kö\’[òJH\›úô\]Y\›Y[ŸHù[Àöù\]\ê\RŸ^K\›ö\‘ôúTõ›]K\›úŸ[ô\ê€€\]XõJBà€ìŸ º'ÈÓHT’PïT’Tà
ô]ûJH’P–—T‘Œà⁄YœIŸ\›⁄YÀùZŸJå
_KããàãòYRYõZ[ù
BàHÿ]⁄
\›^à^Ÿ\[€äH¬à€ìŸ ∏¶®;Ó#»T’PïT’Tà
ô]ûJHêRSQà	Ÿ\›^õY\‹ÿYŸOÀùZŸJå
_HãòYRYõZ[ù
BàBàBàà€ìõ›YûJº'Ê™Ÿ[[ò€€\]HHãàâ›Àúﬁ[Xõ€Nà	‹ô]ûT›IH⁄Ÿ[ú»›[[àÿ[]Yù\àô]ûHHãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bàõ›»ù[ù[YQ^Ÿ\[€äîŸ[ô\öYöXÿ][€àô]ûHòZ[Yà›[€[ô»	‹ô]ûT›IH⁄Ÿ[ú»äBàH[ŸH¬à€ìŸ ∏ß!H—SëTíQíQQ€àô]ûNà⁄Ÿ[àò[[òŸH\»õ›»	‹ô]ûTô[XZ[ö[ôﬂHãòYRYõZ[ù
BàBàHÿ]⁄
ô]ûQ^àù[ù[YQ^Ÿ\[€äH¬àõ›»ô]ûQ^àHÿ]⁄
ô]ûQNà^Ÿ\[€äH¬à€ìŸ º'Ê™‘íUP–Sàÿ[õõ›ô\öYûHŸ[€€\][€àHŸY\[ô»‹⁄][€àX›]ôHHãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™Ÿ[[ùô\öYöYYHãàâ›Àúﬁ[Xõ€Nàÿ[õõ›ô\öYûH€ãX⁄Z[ãà‹⁄][€àì’€X\ôYà⁄X⁄»X[ùX[HHãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà€ïÿ\›
º'Ê™—SSïëTíQíQQà	›Àúﬁ[Xõ€Wêÿ[õõ›€€ôö\õH€ãX⁄Z[ãàX[ùX[⁄X⁄»ô\]Z\ôYHäBàõ›»ù[ù[YQ^Ÿ\[€äîŸ[[ùô\öYöXXõNàò[[òŸH⁄X⁄»òZ[Y⁄XŸH
	‹ô]ûQKõY\‹ÿYŸ_JHäBàBàBààò[öXŸHHŸ]X›X[öXŸJ BàÀ»çKéKåNMéà\ŸHX›X[€ãX⁄Z[à””ò[[òŸH[Hõ‹àìõ›H][›Y\›[X]KÇàÀ»ù\]\à][›Kõ›][[›[ù\»\›[X]Y›]]ôYõ‹ôHQUã‹€\YŸKàŸHôXYBàÀ»]ôH””ò[[òŸH
Yù\à]ÿZ]€€ôö\õX][€à€€ôö\õYYH
H[ôYôàYÿZ[ú›BàÀ»ôK\Ÿ[ÿ[]€€»Ÿ]HX›X[””ôXŸZ]ôY[ù»H€€õôX›Yÿ[]ÇàÀ»çKéKçM^8†%‹\ò]‹àöXYŸHàX^Håçà
òYHõ›\õò[ÿ‹ôY[ú⁄›
NÇàÀ»\[àÿ\][‹ôX€›ô\ûWŒLåôX€‹ôY
ÃLçÕÀç…H»
–IKåLà8†%BàÀ»ò[ŸHõ›\õò[[ùûKàõ€›ÿ]\ŸNà⁄[àH‹›\Ÿ[””[BàÀ»ÿ[YHòX⁄»8¢iåH
⁄Z[àYâ›Ÿ]K‹àî»YÀ‹àôY\»]BàÀ»]
KHò[òX⁄»\ŸYö[ò[][›Kõ›][[›[ù8†%ù\]\â‹¬àÀ»ëK]òYH\›[X]H8†%⁄X⁄€à[\]ZY[\Y⁄Ÿ[ú»\¬àÀ»⁄[H[ôõ]Yàÿ[YH]õŸXŸYLL	H—å÷ë‹‹‹Ÿ\¬àÀ»
Ÿ[úõÿYÿ\›ù]õ»””ÿ[YHòX⁄»8°§à[H8°§à][›BàÀ»[ôõ]Y8°§à]ô[ùX[›XùòX›[€à8°§àLL	JKÇàÀ»ö^àô]ûH””ò[[òŸHôXYpÂÃ‹»⁄]òX⁄€ŸôãàYàŸH›[àÀ»ŸYHõ»[K‹ö]HH–‘êU“[ùûH
€€òX⁄»H€‹›€€	BàÀ»ì
H[ú›XYŸà‹ôY[›\€Hù\›[ô»H[ôõ]Y][›KÇàò[€€òX⁄Œà›XõHHYà
ù[Ÿ[ô\öYöYY€€à
H¬àÀ»çKéKçM^à8†%⁄[àòYUô\öYöY\àõ›ôYH€ãX⁄Z[à””àÀ»[H€à\»ÿ[YH⁄YÀ\ŸH]\»H]]‹ö]]]ôBàÀ»ò[YKàû\\‹Ÿ\»H””Xò[[òŸK\€òXŸH]õŸXŸYàÀ»H
ÃLçÕ…H\[à»LL	H—å÷ë‹[ù€Hõ›\õò[àÀ»[ùöY\Àà‹\ò]‹éàíH›[ÿ[ùHŸ[ô\öYöYY[ôàÀ»]H⁄Ÿ[ú»€X\àHÿ[][ôô]\õàH€€ãÇàò[ô\öYöYY€€Hù[Ÿ[ô\öYöYY€€»WÃÃÃåà€ìŸ º'‰‚à—Sì
ô\öYöY\äNàôXŸZ]ôYI›ô\öYöYY€€ôõ]
ä_H””
\\úŸH]]‹ö]]]ôJHãòYRYõZ[ù
Bàô\öYöYY€€àH[ŸHûH¬àò\àX›X[ò[[òŸHHÿ[]ôŸ]€€ò[[òŸJ
Bàò\à[HHX›X[ò[[òŸHHÿ[]€€àYà
[HHåJH¬àÀ»ô]ûH\»pÂÃ‹»
M\»⁄[ô› H8†%[ô\»î»YÀÇàò\à][\HBà⁄[H
][\HH	âà[HHåJH¬àûH»ôXYú€Y\
Ã
HHÿ]⁄
Œà[ù\úù\Y^Ÿ\[€äH»úôXZ»BàX›X[ò[[òŸHHûH»ÿ[]ôŸ]€€ò[[òŸJ
HHÿ]⁄
Œàõ›ÿXõJH»X›X[ò[[òŸHBà[HHX›X[ò[[òŸHHÿ[]€€à][\
 ¬àBàBàYà
[HàåJH¬àò[][›Y\‹Hö[ò[][›OÀõ]»
]õ›][[›[ù»WÃÃÃå
Kôõ]
äHHŒàõãÿJ\ôX›
HÇà€ìŸ º'‰‚à—Sì
X›X[
NàôXŸZ]ôYIŸ[Kôõ]
ä_H””][›YI][›Y\‹””ãòYRYõZ[ù
Bà[HÀ»ÿ[]””[H\»õÿŸYY»úõ€HHŸ[õ›€‹›
‹õÿŸYY¬àH[ŸH¬àÀ»çKååŒNH8†%õ»õÿŸYY»õ€ŸàYX[ú»õ»ö[ò[]Kà»õ›àÀ»ôX€‹ô–‘êU“[ô»õ›õ›\õò[›òZ[ãÿ€‹ŸN»ŸY\ô]ûZ[ô¬àÀ»[ù[Y]K‹õÿŸYY»‹àõ›]YŸ][Y[ùõ€Ÿà^\›ÀÇà€ìŸ ∏£Ï»—S—íSêSUW‘SëSë◊‘ëUñNàõ»””‹õÿŸYY»[HYù\àM\»8†%õ»õ‹õX[õ›\õò[€‹ŸHãòYRYõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀà∏£Ï»—Sö[ò[]H[ô[ô»ô]ûNàõ»””‹õÿŸYY»[HYù\àM\Ààõ»õ‹õX[—Sõ›\õò[õ›Ààãà⁄Y»H⁄YÀòY\ïY»HìQSQHãà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúôX€‹ôô]ûJÀõZ[ùî—S—íSêSUW‘SëSë◊‘ëUñW”ì◊‘ì–—QQ»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX€€ò⁄[\ãúô\]Y\›\ôŸ[ùX⁄ î—S—íSêSUW‘SëSë◊‘ëUñW”ì◊‘ì–—QQ»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàHÿ]⁄
ò[^à^Ÿ\[€äH¬àÀ»]ô[à””ò[[òŸHôXYòZ[Y[ù\ô[H8†%[ô[ô»ô]ûKõ›ÿ‹ò]⁄Çà€ìŸ ∏£Ï»—S—íSêSUW‘SëSë◊‘ëUñNà””ò[[òŸHôXYòZ[Y
	ÿò[^õY\‹ÿYŸOÀùZŸJ
_JHãòYRYõZ[ù
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúôX€‹ôô]ûJÀõZ[ùî—S—íSêSUW‘SëSë◊‘ëUñW‘””‘ëPQ—êRSQäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX€€ò⁄[\ãúô\]Y\›\ôŸ[ùX⁄ î—S—íSêSUW‘SëSë◊‘ëUñW‘””‘ëPQ—êRSQäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàò[\õZ[ò[Xÿ›H]ôTŸ[Xÿ€›[ù[ô–]]‹ö]JÀ‹Àò€‹›€€€€òX⁄ÀôX\€€ãõ]ôTŸ[ù\õZ[ò[äBàõH\õZ[ò[Xÿ›úõ€€àõH\õZ[ò[Xÿ›úõ›àò[ô]õH\õZ[ò[Xÿ›õô]õ€€àò[ôYT€€H\õZ[ò[Xÿ›ôôYT€€àò[ö[ò[Ÿ[ôX\€€àHù[à¬àò[ïHHôX\€€ãù\\òÿ\ŸJ
Bàò[›‹ZŸHHïKò€€ùZ[ú î’‘äHïKò€€ùZ[ú î’íP’‘”äHïKò€€ùZ[ú íTë—ì”‘àäBàò[€€ôöY›\ôY›‹HôYŸ^
ãO◊
»äKôö[ô
ïJOÀùò[YOÀù—›XõS‹ìù[

OÀõ]»€›[ãõX]òXú ]
HHŒàLåàò[ÿ]\›õ‹X–[›ÿ[òŸHHJ€€ôöY›\ôY›‹
»åå
Kò€Ÿ\òŸP]X\›
Ãå
BàYà
›‹ZŸH	âàõHÿ]\›õ‹X–[›ÿ[òŸJH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî’‘”‘‘◊”’ëTîïSó––UT’ì‘P»ãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H‹öY⁄[ò[ôX\€€èIôX\€€àõI‹õôõ]
ä_H€€ôöY›\ôY›‹Iÿ€€ôöY›\ôY›‹ôõ]
J_HX›[€èZõ›\õò[ÿÿ]\›õ‹X◊€õ›€õ‹õX[‹€äHHÿ]⁄
Œàõ›ÿXõJHﬂBàê–UT’ì‘P◊‘’‘”‘‘◊”’ëTîïSó…‹õù“[ù

_\›—îì”W…‹ôX\€€üHÇàH[ŸHôX\€€ÇàBÇàÀ»çKåçåÕ8†%–Sì”íP–Sì””ëRU
⁄Y›»[ôõ‹òŸ[Y[ù
KÇàÀ»õ›]HHö[ò[^ôYŸ[õ›Y⁄‘ôX[^ôYõ€€ôZ]åÕBàÀ»⁄X⁄€⁄‹»\H[[]]XõHù^H›úõ€H—ö[›YŸ\çåÕBàÀ»[ô[Yÿ]\»»–ÿ[õ€öXÿ[ì]]‹ö]MåÕ◊Kà[à\»\⁄àÀ»H€€ôZ]\»]]‹ö]]]ôHõ‹à]»›€àô]\õàò[YHù]àÀ»Ÿ\»ì’õÿ⁄»HŸ[]YàH€\‹⁄X»[õ[ôHõàÀ»\ÿY‹ôY\Àà]ô\ôŸ[òŸ\»\ôHŸŸŸYöXBàÀ»–Sì”íP–S‘ì—UëTë—Sê—WÕåÕõ‹àõ‹ô[ú⁄X»ô\^KÇàûH¬àÀ»çKåçåŒà8†%’Pî’êUKQíTî’ëPQ
\ôX›]ôHŸX›[€àéàîô[[›ôBàÀ»ÿ[õ€öXÿ[ù^Qö[ôY⁄\›ûHúõ€HôX[\ŸYìäKàôYô\àHô]¬àÀ»[[]]XõHö[›YŸ\çåŒàõ‹à€‹›Xò\⁄\»]öXù][€é»ò[àÀ»òX⁄»»HYÿXﬁHôY⁄\›ûH€õHYàõ»›Xú›ò]H›^\›¬àÀ»Y]
KôÀà€‹[à‹⁄][€ú»]ôKY]HHZY‹ò][€äKÇàò[›Xú›ò]PYŸ»HûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]ëö[›YŸ\çåŒãòYŸ‹ôYÿ]Yö[õ‹ìZ[ù
ÀõZ[ù
BàHÿ]⁄
Œàõ›ÿXõJH»ù[Bàò[ù^Qö[åÕH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêÿ[õ€öXÿ[ù^Qö[ôY⁄\›ûKôŸ]
ÀõZ[ù
Bàò[[ùûTöXŸT€€ô\€€ôYà›XõBàò[X⁄[X[‘ô\€€ôYà[ùàò[ù^U⁄Y‘ô\€€ôYà›ö[ô¬àYà
›Xú›ò]PYŸ»OHù[	âà›Xú›ò]PYŸÀõ›€›[ùà
H¬à[ùûTöXŸT€€ô\€€ôYH›Xú›ò]PYŸÀùù—[ùûT€€\ï⁄Ÿ[ÇàX⁄[X[‘ô\€€ôYHù^Qö[åÕÀôX⁄[X[»ŒàHÀ»X⁄[X[»ò]ô[⁄]HYÿXﬁHôX€‹ôõ‹àõ›¬àù^U⁄Y‘ô\€€ôYHù^Qö[åÕÀòù^T⁄Y€ò]\ôHŒààÇàûH»\[[ôRX[€€X›‹ãõXô[[ò îëPSVëQ‘ì–”‘’–êT“T◊‘”’Tê—WÕåŒó‘’Pî’êUHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàH[ŸH¬à[ùûTöXŸT€€ô\€€ôYHù^Qö[åÕÀô[ùûTöXŸT€€ŒàåàX⁄[X[‘ô\€€ôYHù^Qö[åÕÀôX⁄[X[»ŒàBàù^U⁄Y‘ô\€€ôYHù^Qö[åÕÀòù^T⁄Y€ò]\ôHŒààÇàûH»\[[ôRX[€€X›‹ãõXô[[ò îëPSVëQ‘ì–”‘’–êT“T◊‘”’Tê—WÕåŒó”Q–P÷W—êSêP“»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàò[€€ôZ]ô\›[åÕH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKîôX[^ôYõ€€ôZ]åÕôö[ò[^ôJàÿ[]Yô\‹»Hÿ[]úXõX“Ÿ^PçNàZ[ùYô\‹»HÀõZ[ùàù^U⁄Y»Hù^U⁄Y‘ô\€€ôYàŸ[⁄Y»H⁄YÀà€€]HH⁄Ÿ[î]X[ù]KõŸä‹Àú]U⁄Ÿ[äKàõÿŸYY‘€€H€€[[›[ùõŸä€€òX⁄ KàôYT€€H€€[[›[ùõŸäôYT€€
Kàõ€Ÿî€›\òŸHHìUëW—íSêSVëQãàò[òX⁄—[ùûP€‹›€€H€€[[›[ùõŸä‹Àò€‹›€€
Kàò[òX⁄—[ùûT]HH⁄Ÿ[î]X[ù]KõŸä‹Àú]U⁄Ÿ[äKàò[òX⁄—[ùûTöXŸT€€HöXŸT€€\ï⁄Ÿ[ãõŸä[ùûTöXŸT€€ô\€€ôY
Kàò[òX⁄’⁄Ÿ[ëX⁄[X[»HX⁄[X[‘ô\€€ôYà
BàYà
X€€ôZ]ô\›[åÕòÿ[õ€öXÿ[
H¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàê–Sì”íP–S‘ì‘UPTêSïSëQÕåÕãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[OI›Àúﬁ[Xõ€Hà
¬àúôX\€€úœIÿ€€ôZ]ô\›[åÕú]X\ò[ù[ôTôX\€€úÀöõ⁄[ï‘›ö[ô üä_Hà
¬àò€\‹⁄X‘õI»âKçôàãôõ‹õX]
õ
_HX›[€è\⁄Y›◊€õ◊ÿõÿ⁄»ãà
BàBàHÿ]⁄
Nàõ›ÿXõJH¬àûH¬à\[[ôRX[€€X›‹ãõXô[[ò ê–Sì”íP–S‘ì–””ëRU—V—TS”óÕåÕäBà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãîôX[^ôYõ€€ôZ]åÕô]»
õ€ãYò][
Nà	ŸKõY\‹ÿYŸ_HäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàBÇàÀ»çKéKåÕM»8†%ôYYôX[ù\]\à][›K]úÀ\ôX[^ôY€\[ù¬àÀ»^X›][€ê€‹›ôYX›‹êRH€»]»\ã[\]ZY]KXò[ô\›‹ûBàÀ»ö[ò[HXÿ›[][]\»ÿ[\\Àà][›Kõ›][[›[ùH‹[Z\›X¬àÀ»\›[X]N»€€òX⁄»HX›X[””ôXŸZ]ôY‹›YôY\À”QUãÇàûH¬àò[][›Y€€H
ö[ò[][›OÀõ›][[›[ùŒà
H»WÃÃÃåàYà
][›Y€€àå	âà€€òX⁄»àå
H¬àò[\U\ŸHÀõ\›\]ZY]U\Ÿà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë^X›][€ê€‹›ôYX›‹êRKõX\õäà\U\ŸH\U\Ÿà][›Y\ŸH][›Y€€àôX[^ôY\ŸH€€òX⁄Àà
BàÀ»çKåçMåH8†%€\YŸHö[€][€à[\õNà⁄[àôX[^ôYàÀ»””\»åå	H€‹úŸH[à][›YŸ»VP’US”ó‘”TQ—W’íS”US”ÇàÀ»€»Hÿ]\›õ‹XÀYö[òZ[\ôH[ŸH\»ö\⁄XõH[ÇàÀ»[[Y]ûKàÿúŸ\ùòXö[]H€õH8†%õ»€€ùõ€Yõ›»⁄[ôŸKÇà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë^X›][€íX[›X\ôúôX€‹ô€\YŸS›]€€YJàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à][›Y€€H][›Y€€àôX[^ôY€€H€€òX⁄ÀàôX\€€àHôX\€€ãà
BàBàHÿ]⁄
Œà^Ÿ\[€äHﬂBÇàÀ»çKåçMåH8†%€X\à[ûH\ôX›\õ›]HYô\à›]H€à€€ôö\õYYŸ[ÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë^X›][€íX[›X\ôò€X\ë\ôX›õ›]QYô\äÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBÇàÀ»çKéKçÃéà€X\àô]ûH€›[ù\ú»€àHŸ[ùZ[ôH›XÿŸ\‹Ÿù[›ÿ\€¬àÀ»HZ[ù›\ù»úô\⁄ô^[YKÇàô\õ–ò[[òŸTô]öY\Àúô[[›ôJÀõZ[ù
Bàô\õ–ò[[òŸTô]öY\Àúô[[›ôJÀõZ[ù
»óÿúõÿYÿ\›äBàà€ìŸ º'‰‚à—SPïQŒà€€òX⁄œI‹€€òX⁄Àôõ]
ä_H€‹›€€I‹‹Àò€‹›€€ôõ]
ä_HõI‹õôõ]
ä_Hõ›I‹õôõ]›

_HãòYRYõZ[ù
BÇàÀ»çKéKçM^ç»‹\ò]‹à‹X»][HH8†%ôX€‹ôŸ[⁄Y€ò]\ôH
¬àÀ»ÿ⁄Y[HHôX€€ò⁄[\à\‹»Yù\à]ô\ûH€€ôö\õYYŸ[€»BàÀ»‹››òX⁄Ÿ\ãõ\›‹Ÿ[‹⁄Y€ò]\ôH›‹»ôZ[ô»ààõ‹ô]ô\ãÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôUÿ[]ôX€€ò⁄[\ãúôX€‹ôŸ[⁄Y€ò]\ôJÀõZ[ù⁄Y Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôUÿ[]ôX€€ò⁄[\ãúôX€€ò⁄[Sõ› ÿ[]úŸ[ÿ€€ôö\õYY…›ÀõZ[ùùZŸJä_HäBàHÿ]⁄
Œàõ›ÿXõJH» àòZ[\€Ÿù
ã»BÇÇàÀ»çKéKçM^åŒH8†%‹\ò]‹à‹X»][\»»»H⁄\ö[ôÀÇàÀ»õ›»]€€òX⁄ÀŸôYT€€€ô]õ\ôH€õ›€ã[Z]Hÿ[õ€öXÿ[àÀ»—S”SëQõ‹ô[ú⁄X‹»õ›»⁄]⁄Z[ãX€€ôö\õYYõ‹‹ù[€ò[àÀ»€‹›Xò\⁄\»ìàô[X[ôXúòXŸ\»⁄]H^\›[ô»õ›\õò[8†%àÀ»\»õ›»\Ÿ\»Hô]»öY[ò[Y\»H‹\ò]‹à\⁄ŸYõ‹ãÇàûH¬àò[Ÿ[ù]çàHô\öYöYYŸ[ù]çÇàò[[ùûSY]Qö[ò[H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKï⁄Ÿ[ìYôXﬁX€UòX⁄Ÿ\ãôŸ][ùûSY]Y]JÀõZ[ù
Bàò[X—ö[ò[HŸ[ù]çèÀôX⁄[X[¬àŒà[ùûSY]Qö[ò[Àô[ùûQX⁄[X[¬àŒàŸ]⁄Ÿ[ëX⁄[X[  Bàò[[ùûT€€‹[ùö[ò[H[ùûSY]Qö[ò[Àô[ùûT€€‹[ùàÀùZŸRYà»]àåBàŒà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêÿ[õ€öXÿ[ù^Qö[ôY⁄\›ûKôŸ]
ÀõZ[ù
OÀú€€‹[ùô]àŒà‹Àò€‹›€€àò[[ùûU⁄Ÿ[îò]—ö[ò[H[ùûSY]Qö[ò[Àô[ùûU⁄Ÿ[îò]–€€ôö\õYYàÀùZŸRYà»]ú⁄Y€ù[J
HàBàŒà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêÿ[õ€öXÿ[ù^Qö[ôY⁄\›ûKôŸ]
ÀõZ[ù
OÀõ]»ö[OÇàò]òKõX]êöY—X⁄[X[
ö[ùÿ[]ô\öYöYY]JKõ[›ôT⁄[ùöY⁄
ö[ôX⁄[X[ Kù–öY“[ùYŸ\ä
BàBàŒàò]òKõX]êöY—X⁄[X[
‹Àú]U⁄Ÿ[äKõ[›ôT⁄[ùöY⁄
X—ö[ò[
Kù–öY“[ùYŸ\ä
BàõX^
ò]òKõX]êöY“[ùYŸ\ãì”ëJBàò[€€ú›[YYò]—ö[ò[çàHŸ[ù]çèÀúò]’⁄Ÿ[ê€€ú›[YYàò[ôU⁄Ÿ[îò]—ö[ò[HYà
€€ú›[YYò]—ö[ò[çàOHù[	âà€€ú›[YYò]—ö[ò[çãú⁄Y€ù[J
Hà
Bà[ùûU⁄Ÿ[îò]—ö[ò[õX^
€€ú›[YYò]—ö[ò[çäBà[ŸHò]òKõX]êöY—X⁄[X[
‹Àú]U⁄Ÿ[äKõ[›ôT⁄[ùöY⁄
X—ö[ò[
Kù–öY“[ùYŸ\ä
KõX^
ò]òKõX]êöY“[ùYŸ\ãì”ëJBàò[‹›⁄Ÿ[îò]—ö[ò[HYà
€€ú›[YYò]—ö[ò[çàOHù[	âà€€ú›[YYò]—ö[ò[çãú⁄Y€ù[J
Hà
H¬àôU⁄Ÿ[îò]—ö[ò[ú›XùòX›
€€ú›[YYò]—ö[ò[çäKõX^
ò]òKõX]êöY“[ùYŸ\ãñëTì BàH[ŸH¬àò[‹›ZQö[ò[HûH»ÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùOÀôö\ú›ŒàåHÿ]⁄
Œàõ›ÿXõJH»LKåBàYà
‹›ZQö[ò[å
H¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S—íSêSUW‘SëSë◊‘ëUñHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èSRT‘“Së◊‘‘’–êSSê—W‘ì”—ó—íSêS⁄YœI‹⁄YÀùZŸJMä_HX›[€è[õ◊ÿ€‹ŸW€õ◊⁄õ›\õò[€õ◊€X\õö[ô◊⁄ŸY\€X\ŸHäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúôX€‹ôô]ûJÀõZ[ùî—S—íSêSUW‘SëSë◊‘ëUñW”RT‘“Së◊‘‘’–êSSê—W‘ì”—ó—íSêSäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX€€ò⁄[\ãúô\]Y\›\ôŸ[ùX⁄ î—S—íSêSUW‘SëSë◊‘ëUñW”RT‘“Së◊‘‘’–êSSê—W‘ì”—ó—íSêSäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàûH»ò]òKõX]êöY—X⁄[X[
‹›ZQö[ò[ò€Ÿ\òŸP]X\›
å
JKõ[›ôT⁄[ùöY⁄
X—ö[ò[
Kù–öY“[ùYŸ\ä
HBàÿ]⁄
Œàõ›ÿXõJH»ò]òKõX]êöY“[ùYŸ\ãñëTì»BàBàò[[ù[ùö[ò[H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[[ù[ùòùZ[
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àôX\€€àH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX\€€ê€\‹⁄YöY\ãôù[^]úõ€T›ö[ô ö[ò[Ÿ[ôX\€€äKàô\]Y\›YúòX›[€êú»HLÃà€€ôö\õYYÿ[]ò]»HôU⁄Ÿ[îò]—ö[ò[àX⁄[X[»HX—ö[ò[à€\YŸPú»Hà[Y\ôŸ[òﬁQòZ[àHùYKà[ùûT€€‹[ùH[ùûT€€‹[ùö[ò[ò€Ÿ\òŸP]X\›
å
Kà[ùûU⁄Ÿ[îò]»H[ùûU⁄Ÿ[îò]—ö[ò[à
Bàò[ö[ò[]HH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ö[ò[^ò][€ê€€‹ô[ò]‹ãôö[ò[^ôJà[ù[ùH[ù[ùö[ò[àôU⁄Ÿ[êò[[òŸTò]»HôU⁄Ÿ[îò]—ö[ò[à‹›⁄Ÿ[êò[[òŸTò]»H‹›⁄Ÿ[îò]—ö[ò[àÿ[]€ò]»H‹›⁄Ÿ[îò]—ö[ò[à€€ôXŸZ]ôY[\‹ù»HŸ[ù]çèÀú€€ôXŸZ]ôY[\‹ù¬àŒà
€€òX⁄»
àWÃÃÃå
Kù”€ô 
Kò€Ÿ\òŸP]X\›

KàŸ[€€ôXŸZ]ôYHŸ[ù]çèÀú€€ôXŸZ]ôY[\‹ù¬àÀù—›XõJ
OÀô]äWÃÃÃå
HŒà€€òX⁄ÀàôY\‘€€HôYT€€àX⁄[X[»HX—ö[ò[à€\YŸU\ŸYú»HàŸ[⁄Y»H⁄YÀàòY\ïY»HìQSQHãà
BàYà
ö[ò[]Kú[ô[ô‘ô]ûJHô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàHÿ]⁄
Nàõ›ÿXõJH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãàîŸ[ö[ò[^ò][€ê€€‹ô[ò]‹àŸö[ò[Hô]»
õ€ãYò][
Nà	ŸKõY\‹ÿYŸ_HäBàBÇàÀ»8•d8•d8•dçKéKåMLÃ8†%U”RP»—S—íSêSVëH
€‹ŸH]]‹ö]H]H€›\òŸJH8•d8•d8•dàÀ»ù[ú»TëK[ú⁄YHH€€ôö\õYY\Ÿ[õÿ⁄»⁄\ôH⁄YÀ‹€€òX⁄ÀŸôYT€€‹õ\ôBàÀ»[]ôK€»H€€ôö\õYYUëHŸ[ëUëTàX]ô\»H€‹ŸHYŸ\à[ú›[\YÇàÀ»úô\⁄[]\»ò[[òŸH8°§à\›‹ôX[^ôYX]8°§àïSYŸ\à›[\8°§àòX⁄Ÿ\ÇàÀ»”‘—Q8°§àõ‹Ÿ[õÿà8°§à[õÿ⁄»[ûHôX€›ô\ûHÿ⁄»8°§àVP◊”UëW‘—S”“»8°§ÇàÀ»—S—íSêSVëQà‹ò\Y€»Hö[ò[^ôHXÿ›\ÿ[àô]ô\àõ›»HŸ[]Çàù[à¬àò[î⁄Y»H€‹ŸP]]‹ö]T⁄Y»ŒààÇàYà
î⁄YÀö\”õ›õ[ö 
JH¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S—íSêSVëW‘’TïãõZ[ùI›ÀõZ[ùùZŸJLä_H⁄YœIŸî⁄YÀùZŸJMä_HäBàò[
‹›ZK‹›X HHûH¬àò[ò[Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùBà
ò[Àôö\ú›Œàå
H»
ò[ÀúŸX€€ôŒàJBàHÿ]⁄
Œàõ›ÿXõJH»å»HBàò[‹›ò]»HûH»ò]òKõX]êöY—X⁄[X[
‹›ZJKõ[›ôT⁄[ùöY⁄
‹›X Kù”€ô 
HHÿ]⁄
Œàõ›ÿXõJH»Bàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S—íSêSVëW–êSSê—HãõZ[ùI›ÀõZ[ùùZŸJLä_HôOI⁄Ÿ[ï[ö]»‹›I‹›ò]»\›I‹›ZHäBàò[õ›[ùHûH»õù“[ù

HHÿ]⁄
Œàõ›ÿXõJH»BàÀ»ÿ[õ€öXÿ[‹⁄][€ãŸX€€õ€ZXÀŸö[ò[]H]]][€à[ôXYH€€[Z]YàÀ»ûHŸ[ö[ò[^ò][€ê€€‹ô[ò]‹àúõ€H[Y]Hù]Çàò[⁄YH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî‹⁄][€ê€‹ŸSYŸ\ãõX\ö–€‹ŸYù[
àZ[ùHÀõZ[ùôX\€€àHö[ò[Ÿ[ôX\€€ãõ›Hõ›[ùŸ[⁄Y»Hî⁄YÀà€€]Tò]»H⁄Ÿ[ï[ö]Àô[XZ[ö[ô‘]Tò]»H‹›ò]À\›[[›[ùH‹›ZKàôX[^ôY€€H€€òX⁄ÀôX[^ôYõHõ€›\òŸHHíSUT»ãà
BàûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãúôX€‹ôŸ[€€ôö\õYY
ÀõZ[ùÀúﬁ[Xõ€öXŸKõö[ò[Ÿ[ôX\€€äHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKõX\ö”[ôY
ÀõZ[ùî⁄Y HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îôX€›ô\ûSÿ⁄’òX⁄Ÿ\ãôõ‹òŸU[õÿ⁄ ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãô^X ìUëW‘—S”“»ãÀúﬁ[Xõ€ú⁄YœIŸî⁄YÀùZŸJMä_Hõ€€I»âKçàãôõ‹õX]
õ
_HôX\€€èIö[ò[Ÿ[ôX\€€àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S—íSêSVëQãõZ[ùI›ÀõZ[ùùZŸJLä_H⁄YœIŸî⁄YÀùZŸJMä_H€‹ŸY]ùYH\›I‹›ZH€›\òŸORSUT»€‹ŸRYI⁄YäBàHÿ]⁄
ö[ë^àõ›ÿXõJH¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S—íSêSUW‘SëSë◊‘ëUñHãõZ[ùI›ÀõZ[ùùZŸJLä_H⁄YœIŸî⁄YÀùZŸJMä_HôX\€€èQíSêSVëW—V—TS”ó…Ÿö[ë^öò]òP€\‹Àú⁄[\Sò[Y_HX›[€è[õ◊ŸY‹òYYÿ€‹ŸW€õ◊€]ôW‹Ÿ[€⁄»äBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúôX€‹ôô]ûJÀõZ[ùî—S—íSêSUW‘SëSë◊‘ëUñW—íSêSVëW—V—TS”àäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ôX€€ò⁄[\ãúô\]Y\›\ôŸ[ùX⁄ î—S—íSêSUW‘SëSë◊‘ëUñW—íSêSVëW—V—TS”àäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBàBàBÇàò[òYHHòYJà⁄YHHî—Sãà[ŸHHõ]ôHãàÀ»çKéKåLåà8†%ÿ[YHö^\»çKéKåLN»\\îŸ[[ôHLLÃKàBàÀ»]ôH—SòYHÿ\»[€»ôX€‹ô[ô»”‘’êT“T»
‹Àò€‹›€€
BàÀ»\»H€€öY[€€ùòYX›[ô»HõÿŸYY»Ÿ[X[ùX»\ŸYàÀ»ûH]ô\ûH›\à—Sõ›»[ôûHÿ[õ€öXÿ[X\õö[ôÀô^]€€ÇàÀ»çKéKåLN»€õHö^YH\\à]à€€òX⁄»\ôH\»BàÀ»X›X[””ôXŸZ]ôYúõ€Hù\]\à
‹›\€\YŸH‹õ‹‹¬àÀ»õÿŸYY K⁄X⁄\»⁄]HöY[⁄›[ô\ô\Ÿ[ùÇà€€H€€òX⁄ÀàöXŸHHöXŸKà»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
KàôX\€€àHö[ò[Ÿ[ôX\€€ãàõ€€Hõàõ›Hõà⁄Y»H⁄YÀàôYT€€HôYT€€àô]õ€€Hô]õàòY[ô”[ŸHH‹ÀùòY[ô”[ŸKàòY[ô”[ŸQ[[⁄öHH‹ÀùòY[ô”[ŸQ[[⁄öKà
BàôX€‹ôòYJÀòYJBàŸX›\ö]KúôX€‹ôòYJòYJBàà[Y\ôŸ[ù›X\ôòZ[Àù[úôY⁄\›\î‹⁄][€äòYRYõZ[ù
BàÀ»çKéKåŒH8†%]ôK\]€‹ŸNàô[X\ŸHHÿÿ[õô\à]öX›[€à›X\ôÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKë€ÿò[òYTôY⁄\›ûKò€‹ŸT‹⁄][€äòYRYõZ[ù
BàHÿ]⁄
Œà^Ÿ\[€äH» àõ€ãX‹ö]Xÿ[
ã»BàûH»€€KõYôXﬁX€Xõ›ùçõY]Kî‹ùõ€[“X]RKúô[[›ôT‹⁄][€äòYRYõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBÇà€X\ù⁄^ô\ãúôX€‹ôòYJô]õà\‘\\ì[ŸHHò[ŸJBà]ôTÿYô]P⁄\ò›Z]úôXZŸ\ãúôX€‹ôòYTô\›[
ô]õ
HÀ»çKéKåLHŸ\‹⁄[€àò]Ÿ›€à[ÇàÀ»çKéKåŒNH»çKéKçé8†%ôX\›\ûH‹]
]ôK[[ŸHZ\úõ‹àŸà\\îŸ[
KÇàÀ»]ôHÿ[â›ô]õÿX›]ô[HYX›úõ€HH€ãX⁄Z[àÿ[]
””àÀ»[ôXYHô]\õôYûHù\]\à]ù[ò[YJN»\»\»õ€⁄⁄ŸY\[ô¬àÀ»€õH[ù[[à€ãX⁄Z[àò[úŸô\à»HYXÿ]YôX\›\ûHÿ[]àÀ»\»⁄\ôY\àY[YH⁄[ú»8°§àÃ	KàôX\›\ûHÿÿ[»8°§àL	Kà‹⁄[ô¬àÀ»Ÿ[»€€ùöXù]Hõ›[ôÀÇàYà
õà
H¬àûH¬àYà
Àú‹⁄][€ãö\’ôX\›\ûT‹⁄][€àÀú‹⁄][€ãùòY[ô”[ŸHOHïëPT’TñHäH¬àôX\›\ûSX[òYŸ\ãò€€ùöXù]Qù[Qúõ€UôX\›\ûTÿÿ[
õÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸJBàH[ŸH¬àôX\›\ûSX[òYŸ\ãò€€ùöXù]Qúõ€SY[YTŸ[
õÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸJBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãïôX\›\ûH‹]\úõ‹à
]ôJNà	ŸKõY\‹ÿYŸ_HäBàBàBààYà
õà
H¬àûH¬àò[ò]Ÿ›€î›H€X\ù⁄^ô\ãôŸ]›\úô[ùò]Ÿ›€î›
\‘\\àHò[ŸJBàò[[ÿÿ][€àH]]–€€\›[ô[ô⁄[ôKúõÿŸ\‹’⁄[äõò]Ÿ›€î›
BààYà
[ÿÿ][€ãù’ôX\›\ûHà
H¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKòY’ôX\›\ûJ[ÿÿ][€ãù’ôX\›\ûK\‘\\àHò[ŸJBàBààò[€€öXŸHHÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸBàôX\›\ûSX[òYŸ\ãõÿ⁄‘ôX[^ôYõŸö]
[ÿÿ][€ãù’ôX\›\ûK€€öXŸJBàà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'Â!UUÀP””T’Së”UëWNà	‹õôõ]

_H””õŸö]8°§àà
¬àïôX\›\ûNà	ÿ[ÿÿ][€ãù’ôX\›\ûKôõ]
 _Hà
¬àê€€\›[ôà	ÿ[ÿÿ][€ãù–€€\›[ôôõ]
 _Hà
¬àïÿ[]à	ÿ[ÿÿ][€ãù’ÿ[]ôõ]
 _Hà
¬àî⁄^ôH][à	ÿ[ÿÿ][€ãõô]‘⁄^ôS][\Y\ãôõ]
ä_^äBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãê]]–€€\›[ô\úõ‹à
]ôJNà	ŸKõY\‹ÿYŸ_HäBàò[€€öXŸHHÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸBàÀ»çKéKååçNà\ŸH€€úŸ\ùò]]ôHå	HôX\›\ûHúòX›[€à€à]]–€€\›[ôòZ[\ôH
ÿ\»ù[õ8†%›ô\ãX€›[ùY
BàôX\›\ûSX[òYŸ\ãõÿ⁄‘ôX[^ôYõŸö]
õ
àåå€€öXŸJBàBàBÇà€ìŸ ∏ß!HUëH—S””TUH	‹öXŸKôõ]

_H	ö[ò[Ÿ[ôX\€€àõ	‹õôõ]

_H””à
¬àä	‹õôõ]›

_JH⁄YœI‹⁄YÀùZŸJMä_x†)àãÀõZ[ù
Bà€ìõ›YûJ∏ß!H]ôHŸ[ãàâ›Àúﬁ[Xõ€H	ö[ò[Ÿ[ôX\€€àì	‹õôõ]›

_Hãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì BààòYP[\ùÀõ€îŸ[
Ÿô 
KÀúﬁ[Xõ€õõö[ò[Ÿ[ôX\€€ã\‘\\àHò[ŸJBààYà
õèHåå
H¬àòYP[\ùÀõ€êöY’⁄[äŸô 
KÀúﬁ[Xõ€õõ\‘\\àHò[ŸJBàBààò[[[⁄öHHYà
õèH
H∏ß!Hà[ŸHº'‰‚HÇà€ïÿ\›
â[[⁄öHUëH—Sà	›Àúﬁ[Xõ€Wîìà	‹õôõ]›

_H
	‹õôõ]

_H””
HäBÇàHÿ]⁄
Nà^Ÿ\[€äH¬àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ KõY\‹ÿYŸHŒàù[ö€õ›€àäBà€ìŸ î—SV—TS”éà	ŸKöò]òP€\‹Àú⁄[\Sò[Y_H	‹ÿYô_HãòYRYõZ[ù
Bà€ìŸ à›X⁄Œà	ŸKú›X⁄’òXŸKùZŸJ Köõ⁄[ï‘›ö[ô à8°§àäH»â⁄]ôö[Sò[Y_Nâ⁄]õ[ôSù[Xô\üHà_HãòYRYõZ[ù
BààÀ»çKéKçÃà‘íUP–SíVàúõÿYÿ\›òZ[\ô\»»ì’YX[àH⁄Ÿ[ú¬àÀ»\ôH€€ôH‹àHŸ[\[ôYàô]ö[›\€H\»⁄[[ùH€‹ŸYàÀ»H‹⁄][€àYù\àH^Ÿ\[€ú»[ôô]\õôY””ëíTìQQ]ô[ÇàÀ»›Y⁄ì»›XÿŸ\‹Ÿù[⁄Y€ò]\ôH]ô\à^\›Yà⁄Ÿ[ú»›^YY[ÇàÀ»ÿ[]õ›õ‹ô€›[Kàõ›Œà[ÿ^\»ô]\õàêRSQ‘ëUñPPìH€¬àÀ»H‹⁄][€à›^\»‹[à[ôHô^X⁄»Ÿ]»[õ›\à⁄[òŸKÇàò[úõÿYÿ\›ô]öY\»Hô\õ–ò[[òŸTô]öY\ÀõY\ôŸJÀõZ[ù
»óÿúõÿYÿ\›ãJH»€»Oà€
»HHŒàBÇàÀ»çKéKçé8†%‘íUP–Sì‘ëSî“P‘»íV
‹\ò]‹ã\ô\‹ùYŸ[⁄[[ùZ⁄[
KÇàÀ¬àÀ»‹\ò]‹àÿ‹ôY[ú⁄›⁄›ŸY—S‘US’W”“»ö\ö[ô»ô\X]YH⁄]àÀ»õ»—S’–ïRS»—S–îì–Q–T’»—S—êRSQ\ŸH]ô\ÇàÀ»ŸŸŸYYù\à]8†%€õHHÿ\›	‘—SêRSQàõ‹õZYH
][\ IÀÇàÀ»õ€›ÿ]\ŸNà\»ÿ]⁄[ô\»]ô\ûH‹›\][›HòZ[\ôH
ù\]\ÇàÀ»ùZ[]»⁄Y€à»[òH^X›]H»ëîK[€õK\]»]ÿZ]€€ôö\õJBàÀ»ù]€õH[Z]Y€ìŸ»
»€ïÿ\›ô]ô\àH]ôUòYSŸ‘›‹ôH\ŸKÇàÀ»Hõ‹ô[ú⁄X‹»[H\ôYõ‹ôHúõﬁôH]—S‘US’W”“»[ôH\Ÿ\ÇàÀ»Yõ»ÿ^H»ŸYH“HHŸ[YYàôX[⁄[[ùZ⁄[ùYÀÇàÀ¬àÀ»õ›Œà]ô\ûH‹›\][›HòZ[\ôH›\ôòXŸ\»H—S—êRSQ\ŸH⁄]àÀ»H^Ÿ\[€à€\‹»
»ÿ[ö]\ŸYY\‹ÿYŸH€»Hõ‹ô[ú⁄X‹»ÿ\ôàÀ»S–VT»\õZ[ò]\»[àHö\⁄XõHòZ[\ôHôX\€€ãàHõ››[àÀ»ô]\õú»êRSQ‘ëUñPPìH
‹⁄][€à›^\»‹[ãõ»òZŸH””ëíTìQQ
BàÀ»8†%€õHHö\⁄Xö[]Hÿ\»Z\‹⁄[ôÀÇàÀ¬àÀ»ŸH[€»^òX›⁄]\àHòZ[\ôHÿ\»ZŸ[HHù\]\àTBàÀ»ùZ[Ÿ^X›]HòZ[\ôHú»[àî»⁄[][][€àòZ[\ôHú»Hô[ù¬àÀ»[ú›YôöX⁄Y[ùYù[ô»òZ[\ôK€»H\Ÿ\àÿ[à[[YYX][HŸYBàÀ»H€\‹»Ÿàõÿõ[KÇàÀ»çKååÕŒMH8†%\ŸHHÿ[õ€öXÿ[õ›]H€\‹⁄YöY\à\ôH€ÀàH€àÀ»[ô]‹ö][àòZ[\ôP€\‹ÿZ\‹ŸYì◊‘“Q”êUTëKÿ[\õ›öY\ãY^]\›YàÀ»[ô€€\ŸY]»îì–Q–T’—êRSQ⁄X⁄ô]\õôYêRSQ‘ëUñPPìKÇàÀ»‘Ÿ[

H[à]Y]YY[ô[ô‘Ÿ[]Y]YH⁄[Hô\]Y\›Ÿ[

HŸ\BàÀ»€‹ŸSX\ŸKõŸX⁄[ô»UëW‘—S”ì◊—íSêSUNàõ‘⁄Yœå
»õÿ⁄⁄[ô»X\ŸKÇàò[õ›]P€»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ›]Q\úõ‹ê€\‹⁄YöY\ãò€\‹⁄YûJÿYôJBàò[õ›]T€XﬁHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ›]Q\úõ‹ê€\‹⁄YöY\ãúô]ûT€XﬁJõ›]P€ÀúõÿYÿ\›ô]öY\Àô]ûTÿ⁄Y[YHò[ŸJBàYà
õ›]T€XﬁKúô\]Z\ôUô[ùYTôTô\€€][€äH¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ìY[YUô[ùYTõ›]\ãõX\ö‘[\õ›]R[ùò[Y
ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàBàò[òZ[\ôP€\‹»Hõ›]P€Àõò[YBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàâòZ[\ôP€\‹»8†%	ŸKöò]òP€\‹Àú⁄[\Sò[Y_Nà	‹ÿYôKùZŸJLå
_H
][\	úõÿYÿ\›ô]öY\ HãàòY\ïY»HìQSQHãà
Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãî—S‘’TUS’HêRSTëNà	›Àúﬁ[Xõ€H€\‹œIòZ[\ôP€\‹»à
¬àô^œIŸKöò]òP€\‹Àú⁄[\Sò[Y_H\ŸœI‹ÿYôKùZŸJ
_Hô]ûOIúõÿYÿ\›ô]öY\»äBÇàYà
X€‹ŸP]]‹ö]T⁄YÀö\”ù[‹êõ[ö 
JH¬àûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôT‹⁄][€ê€‹ŸP]]‹ö]KõX\ö–€‹⁄[ô’[ö€õ›€äàÀõZ[ùàÀúﬁ[Xõ€Œàè»ãàî‘’–îì–Q–T’’ëTíQñW—êRSQâòZ[\ôP€\‹»ãà€‹ŸP]]‹ö]T⁄YÀà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»[ô[ô‘Ÿ[]Y]YKúô[[›ôJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî—S‘ëUñW‘’TëT‘—Q–îì–Q–T’‘SëSë◊‘ì”—àãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HòZ[\ôP€\‹œIòZ[\ôP€\‹»⁄YœIÿ€‹ŸP]]‹ö]T⁄YœÀùZŸJLä_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[ï–RUSë◊–êSSê—W‘ì”—ÇàBÇàYà
úõÿYÿ\›ô]öY\»èHH	âàúõÿYÿ\›ô]öY\»	HHOH
H¬àÀ»›Y\àõ›YöXÿ][€à]ô\ûHH€€úŸX›]]ôHòZ[\ô\»8†%ù]ŸBàÀ»›[€â›€‹ŸKà\Ÿ\àX⁄Y\»⁄[à»⁄]ôH\Çà€ìŸ º'Ê™—Sîì–Q–T’’P“Œà	›Àúﬁ[Xõ€H8†%	úõÿYÿ\›ô]öY\»€€úŸX›]]ôHà
¬àòúõÿYÿ\›òZ[\ô\Àà⁄Ÿ[ú»›[[àÿ[]à‹⁄][€àô[XZ[ú»‘SãàãòYRYõZ[ù
Bà€ìõ›YûJº'Ê™Ÿ[›X⁄»ãàâ›Àúﬁ[Xõ€Nà	úõÿYÿ\›ô]öY\»úõÿYÿ\›][\»òZ[Y
	òZ[\ôP€\‹ Kàà
¬àï⁄Ÿ[ú»ô[XZ[ãàô]ûH€€ù[ùY\Œ»€X\àX[ùX[HYà[›Hÿ[ù»›‹àãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãî—Sîì–Q–T’’P“Œà	›Àúﬁ[Xõ€HYù\à	úõÿYÿ\›ô]öY\»ô]öY\»8†%à
¬àú‹⁄][€àŸ\‘Sãõ»òZŸH””ëíTìQQàäBàBÇà€ìõ›YûJîŸ[òZ[Yãàâ›Àúﬁ[Xõ€Nà	òZ[\ôP€\‹»8†%	‹ÿYôKùZŸJ
_H
ô]ûH	úõÿYÿ\›ô]öY\ Hãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bà€ïÿ\›
î—SêRSQà	›Àúﬁ[Xõ€H
	òZ[\ôP€\‹»0≠»][\	úõÿYÿ\›ô]öY\ HäBÇàYà
õ›]P€»OH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ›]Q\úõ‹ê€\‹⁄YöY\ãê€\‹Àìì◊‘“Q”êUTëJH¬àò[õõ›—õ‹ï[úŸ[XõHHûH¬àò[õ›’[úŸ[XõHHŸ]X›X[öXŸJ BàYà
‹Àô[ùûTöXŸHàå	âàõ›’[úŸ[XõHàå
H

õ›’[úŸ[XõHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLå[ŸHåàHÿ]⁄
Œàõ›ÿXõJH»åBàò[\ô‹‹—^]HôX\€€ãù\\òÿ\ŸJ
Kõ]»úàOàúãò€€ùZ[ú î’íP’‘”äHúãò€€ùZ[ú ê–UT’ì‘HäHúãò€€ùZ[ú íTë—ì”‘àäHúãò€€ùZ[ú î’‘”‘‘»äHBàYà
\ô‹‹—^]	âàõõ›—õ‹ï[úŸ[XõHHLÃå
H¬àûH»\[[ôRX[€€X›‹ãõXô[[ò îïQ◊’Sî—SPìW‘ì’UW—””ëHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò î’‘—êRSQ’Sî—SPìHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»ùY”Z[ùõX⁄€\›úôX€‹ô€‹ŸJÀõZ[ùõõ›—õ‹ï[úŸ[XõKﬁ\›[Kò›\úô[ù[YSZ[\ 
HH‹Àô[ùûU[YJHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîïQ◊’Sî—SPìW‘ì’UW—””ëHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HõI»âKåYàãôõ‹õX]
õõ›—õ‹ï[úŸ[XõJ_HôX\€€èIôX\€€àòZ[\ôOI‹ÿYôKùZŸJMä_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàûH»[ô[ô‘Ÿ[]Y]YKúô[[›ôJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ê€‹ŸSX\ŸKúô[X\ŸJÀõZ[ùîì’UW—êRSQ…‹õ›]P€Àõò[Y_W”ì◊–ì–““Së◊‘ëUñHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãò€X\îŸ[[ëõY⁄
ÀõZ[ùîì’UW—êRSQ…‹õ›]P€Àõò[Y_W”ì◊–ì–““Së◊‘ëUñHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKúôX€‹ô
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à⁄[ôH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[òZ[\ôR\›‹ûKí⁄[ôîì’UW—êRSQ”ì◊‘“Q”êUTëKàôX\€€àHâ‹õ›]P€Àõò[Y_Nà	‹ÿYôKùZŸJLå
_Hãà⁄Y»Hù[à
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àö[ò à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹ÀëVP◊”UëW‘—S‘ì’UW—êRSQ”ì◊‘“Q”êUTëKàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H€\‹œI‹õ›]P€Àõò[Y_HX›[€è\ô[X\ŸW€õ◊‹]Y]YHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàŸ[ô\›[îì’UW—êRSQ”ì◊‘“Q”êUTëBàBàô]\õàŸ[ô\›[ëêRSQ‘ëUñPPìBàBÇàò[^]öXŸHHŸ]X›X[öXŸJ Bààò[€[YSZ[ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH‹Àô[ùûU[YJH»åÃåààò[X^ÿZ[î›]ôHHYà
‹Àô[ùûTöXŸHà	âà‹ÀöY⁄\›öXŸHà
H¬à

‹ÀöY⁄\›öXŸHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLåàH[ŸHåààò[⁄Ÿ[êYŸSZ[ú”]ôHHYà
ÀòYY’ÿ]⁄\›]à
H¬à
‹Àô[ùûU[YHHÀòYY’ÿ]⁄\›]
H»åÃåàH[ŸHåààÀ»çKéKåÕå»8†%÷SSQUíP»–‘êU“ì”ëH
]ôHZ\úõ‹àŸà\\ã[[ŸH⁄[ôŸJKÇàò[òYP€\‹⁄YöXÿ][€àH⁄[à¬àõèHKçHOàï“SàÇàõHLKçHOàì‘‘»Çà[ŸHOàî–‘êU“ÇàBààò[\‘ÿ‹ò]⁄òYS]ôHHòYP€\‹⁄YöXÿ][€àOHî–‘êU“Çàò[⁄›[X\õê\”‹‹»HòYP€\‹⁄YöXÿ][€àOHì‘‘»Çàò[⁄›[X\õê\’⁄[àHòYP€\‹⁄YöXÿ][€àOHï“SàÇàà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'‰‚àUëH	›òYRYúﬁ[Xõ€H”T‘“QíQQà	òYP€\‹⁄YöXÿ][€àà
¬àúõI‹õù“[ù

_IH€I⁄€[YSZ[úÀù“[ù

_[Z[àà
¬àõX\õèI⁄Yä\‘ÿ‹ò]⁄òYS]ôJHìì»
ÿ‹ò]⁄
Hà[ŸHñQT»üHäBààYà
⁄›[X\õê\”‹‹ H¬àò[ò[ìò[YHHÀõY]Kô[XYò[ê[Y€õY[ùàò[H‹Àô[ùûT\ŸBàò[‹ò»HÀú€›\òŸKöYêõ[ö»»ïSí”ì’”ààBÇàòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHú\ŸOI‹JŸ[XOIŸò[ìò[Y_HãàôZ]ö[›\ï\HHëSïñW‘“Q”êSãà\ÿ‹ö\[€àHì‹‹»€à	
»	ò[ìò[YH8†%õI‹õù“[ù

_IHãà‹‹‘›Hõà
BàYà
‹ò»OHïSí”ì’”àäHòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHú€›\òŸOI‹‹òﬂHãàôZ]ö[›\ï\HHî”’Tê—Hãà\ÿ‹ö\[€àHì‹‹»úõ€H€›\òŸH	‹ò»ãà‹‹‘›Hõà
BàòYQèÀúôX€‹ôòYÿúŸ\ùò][€äàôX]\ôRŸ^HHô^]‹ôX\€€èI‹ôX\€€üHãàôZ]ö[›\ï\HHëVU‘UTìàãà\ÿ‹ö\[€àHë^]öXH	ôX\€€àô\›[Y[à‹‹»ãà‹‹‘›Hõà
BÇàúòZ[èÀõ]»àOÇàò[⁄›[õX⁄€\›HãõX\õëúõ€UòYJà\’⁄[àHò[ŸKà\ŸHHà[XQò[àHò[ìò[YKà€›\òŸHH‹òÀàõ›HõàZ[ùHÀõZ[ùàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKàù^Tô\‹›\ôHHÀõY]Kúô\‹‘ÿ€‹ôKà‹€\î›HÀúÿYô]Kù‹€\î›à\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà\”]ôUòYHHùYKà\õ›ò[€\‹»HòYRYôô–\õ›ò[€\‹ÀöYë[\H»ìUëHàKà€[YSZ[ù]\»H€[YSZ[úÀàX^ÿZ[î›HX^ÿZ[î›]ôKà^]ôX\€€àHôX\€€ãà⁄Ÿ[êYŸSZ[ù]\»H⁄Ÿ[êYŸSZ[ú”]ôKà
BàYà
⁄›[õX⁄€\›
H¬àÀ»çKååŒ8†%]ôHô\X]Y[‹‹»X\õö[ô»]\›]õ›‹[ò[^ôKàÀ»õ›ôX€€YHH\õX[ô[ùZ[ùõX⁄€\›àùYH\ôõÿ⁄‹»ô[XZ[ÇàÀ»[àÿYô]K‹ô]òYHÿ]\ŒàùYÀ[õÿ⁄ŸYZ[ùŸúôY^ôH]]àÀ»€ô^\›€õ»Ÿ[õ›]Kò][€\à€€òŸ[ùò][€ãô\õ»\KÇàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JàêïVW—–UW—P“T“S”àãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HX⁄\⁄[€èTSêSW””ìHôX\€€èLä◊€‹‹Ÿ\»€›\òŸOQ^X›]‹ãõ]ôSX\õö[ô»]ôQ[Y⁄XõO]ùYHãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò êïVW—–UW‘SêSW””ìW‘ëTPUQ”‘‘»äHHÿ]⁄
Œàõ›ÿXõJHﬂBà€ìŸ º'ÈËUëHSêSW””ìNà	›Àúﬁ[Xõ€Hô\X]Y‹‹Ÿ\»8†%⁄^ôK‹ÿ€‹ôKÿ€€€›€àô\‹›\ôH€õKõ»õX⁄€\›ãÀõZ[ù
BàBàBàH[ŸHYà
⁄›[X\õê\’⁄[äH¬àò[ò[ìò[YHHÀõY]Kô[XYò[ê[Y€õY[ùàò[H‹Àô[ùûT\ŸBàò[‹ò»HÀú€›\òŸKöYêõ[ö»»ïSí”ì’”ààBàòYQèÀúôX€‹ô€€ŸÿúŸ\ùò][€äú\ŸOI‹JŸ[XOIŸò[ìò[Y_HäBàòYQèÀúôX€‹ô€€ŸÿúŸ\ùò][€äú€›\òŸOI‹‹òﬂHäBààúòZ[èÀõ]»àOÇàãõX\õëúõ€UòYJà\’⁄[àHùYKà\ŸHHà[XQò[àHò[ìò[YKà€›\òŸHH‹òÀàõ›HõàZ[ùHÀõZ[ùàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKàù^Tô\‹›\ôHHÀõY]Kúô\‹‘ÿ€‹ôKà‹€\î›HÀúÿYô]Kù‹€\î›à\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà\”]ôUòYHHùYKà\õ›ò[€\‹»HòYRYôô–\õ›ò[€\‹ÀöYë[\H»ìUëHàKà€[YSZ[ù]\»H€[YSZ[úÀàX^ÿZ[î›HX^ÿZ[î›]ôKà^]ôX\€€àHôX\€€ãà⁄Ÿ[êYŸSZ[ù]\»H⁄Ÿ[êYŸSZ[ú”]ôKà
BàBàH[ŸH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãìUëH	›Àúﬁ[Xõ€Nàÿ‹ò]⁄òYH
	‹õù“[ù

_IJHH⁄⁄\Yõ‹àX\õö[ô»äBàBÇàò[í\’⁄[ì]ôHH⁄[à¬à\‘ÿ‹ò]⁄òYS]ôHOàù[àõàKåOàùYBà[ŸHOàò[ŸBàBààò[Ÿ^]ÃàHﬁ\›[Kò›\úô[ù[YSZ[\ 
HÀ»çKéKåMNH8†%⁄[ô€H^]]Œ»[ùûK⁄[ÿ[ö]^ôYàòYQèÀö[úŸ\ùòYJòYTôX€‹ô
à—[ùûO\ÿ[ö]^ôQ[ùûU ‹Àô[ùûU[YKŸ^]ÃäK—^]WŸ^]Ããàﬁ[Xõ€]Àúﬁ[Xõ€Z[ù]ÀõZ[ùà[ŸOZYä‹Àô[ùûT\ŸKò€€ùZ[ú ú[\äJHìUSê“à[ŸHîêSë—Hãà[ùûTöXŸO\‹Àô[ùûTöXŸK[ùûTÿ€‹ôO\‹Àô[ùûTÿ€‹ôKà[ùûT\ŸO\‹Àô[ùûT\ŸK[XQò[è]ÀõY]Kô[XYò[ê[Y€õY[ùàõ€ÿ€‹ôO]ÀõY]Kùõ€ÿ€‹ôKô\‹‘ÿ€‹ôO]ÀõY]Kúô\‹‘ÿ€‹ôK[€Tÿ€‹ôO]ÀõY]Kõ[€Tÿ€‹ôKà€\ê€›[ù]Àö\›‹ûKõ\›‹ìù[

OÀö€\ê€›[ùŒåà€\ë‹õ››]Àö€\ë‹õ››ò]K\]ZY]U\Ÿ]Àõ\›\]ZY]U\ŸXÿ\\Ÿ]Àõ\›Xÿ\à^]öXŸOY^]öXŸK^]\ŸO]Àú\ŸK^]ôX\€€è\ôX\€€ãà[Z[úœ\ÿ[ö]^ôR[Z[ú ‹Àô[ùûU[YKŸ^]ÃäKà‹\€›[ù\‹Àù‹\€›[ù\ùX[€€\‹Àú\ùX[€€›à€€[è\‹Àò€‹›€€€€›]\õ
»‹Àò€‹›€€õ€€\õõ›\õà\’⁄[èYí\’⁄[ì]ôKà\‘ÿ‹ò]⁄Z\‘ÿ‹ò]⁄òYS]ôKà
JBÇàûH¬àò[€Z[ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH‹Àô[ùûU[YJH»åÃåàò[⁄Ÿ[êYŸSZ[ú»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀòYY’ÿ]⁄\›]
H»åÃåàò[ôX]\ô\»HY\]ôSX\õö[ô—[ô⁄[ôKòÿ\\ôQôX]\ô\ à[ùûSXÿ\\ŸH‹Àô[ùûS\]ZY]U\Ÿ
àãà⁄Ÿ[êYŸSZ[ù]\»H⁄Ÿ[êYŸSZ[úÀàù^Tò][‘›HÀõY]Kúô\‹‘ÿ€‹ôKàõ€[YU\ŸHÀõ\›\]ZY]U\Ÿ
àÀõY]Kùõ€ÿ€‹ôH»Låà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà€\ê€›[ùHÀö\›‹ûKõ\›‹ìù[

OÀö€\ê€›[ùŒàà‹€\î›HÀúÿYô]Kù‹€\î›à€\ë‹õ››ò]HHÀö€\ë‹õ››ò]Kà]ïÿ[]›Håàõ€ô[ô–›\ùôTõŸ‹ô\‹»HLåàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKù—›XõJ
Kà[XQò[î›]HHÀõY]Kô[XYò[ê[Y€õY[ùà[ùûTÿ€‹ôHH‹Àô[ùûTÿ€‹ôKàöXŸQúõ€P]HYà
‹ÀöY⁄\›öXŸHà
Hà

‹ÀöY⁄\›öXŸHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸH
àL
H[ŸHåàõ›HõàX^ÿZ[î›HYà
‹Àô[ùûTöXŸHà
Hà

‹ÀöY⁄\›öXŸHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸH
àL
H[ŸHåàX^ò]Ÿ›€î›Håà[YU‘XZ”Z[ú»H€Z[ú»
àçKà€[YSZ[ú»H€Z[úÀà^]ôX\€€àHôX\€€ãà[ùûT\ŸHH‹Àô[ùûT\ŸKà›XõUòYRŸ^HHâ›ÀõZ[ùNâ‹‹Àô[ùûU[Y_Hãà
BàY\]ôSX\õö[ô—[ô⁄[ôKõX\õëúõ€UòYJôX]\ô\ BààYà
⁄›[X\õê\’⁄[à⁄›[X\õê\”‹‹ H¬àò[⁄Ÿ[êYŸR›\úÃàH
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀòYY’ÿ]⁄\›]
H»◊ÕåÃåàÿÿ[õô\ìX\õö[ôÀúôX€‹ôòYJà€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKà\U\ŸHÀõ\›\]ZY]U\ŸàYŸR›\ú»H⁄Ÿ[êYŸR›\úÃãà\’⁄[àH⁄›[X\õê\’⁄[Çà
BàÀ»çKåçM»8†%ÿÿ[õô\î€›\òŸPúòZ[à
\ò[[Q“HXY
BàûH¬àÀ»çKåçéMà8†%\‹»Hò]»õ›ô[ò[òŸKàöYë[\H»ïSí”ì’”ààXàÀ»\õôYHZ\‹⁄[ô»€›\òŸH[ù»H]\ò[€›\òŸHêSQQïSí”ì’”àãàÀ»õ›[ô[ô»Hÿÿ[õô\à€⁄‹ù›]Ÿàõ›‹»⁄‹ŸH‹öY⁄[àÿ\»‹›ÇàÀ»ÿÿ[õô\î€›\òŸPúòZ[àõ›»€›[ù»[ôõ‹»Hõ[ö»[ú›XYÇàÿÿ[õô\î€›\òŸPúòZ[ãúôX€‹ô›]€€YJà€›\òŸHHÀú€›\òŸKàõ›Hõà
BàHÿ]⁄
Œàõ›ÿXõJH»BàÀ»çKåçL8†%ÿ]ôHŒàôYYôX[^ôYUëH€‹ŸH[ù»]ôT⁄^ö[ô‘õŸö[BàÀ»Z[Hò[\àõ\»[ôXYH[à””
]ôTõ€€
KàôX[^ôY[€õH8†%àÀ»ëT’‘ëQò\⁄\»€‹›\ô\»\ôHõYŸŸY€»^Hô]ô\àôYYàÀ»€€\›[ô[ô»]]‹ö]H
ÿ›ö[ôJKÇàûH¬àò[ô\›‹ôY[ö€õ›€êò\⁄\»BàÀú€›\òŸKò€€ùZ[ú îëT’‘ëQãY€õ‹ôPÿ\ŸHHùYJHàÀú‹⁄][€ãò€‹›€€Håà]ôT⁄^ö[ô‘õŸö[KúôX€‹ôôX[^ôY€‹ŸJàÿ[]õ›‘€€HåÀ»[ò⁄‹àôYúô\⁄\»öXH€X\ù⁄^ô\àX⁄¬àõ€€Hõàô\›‹ôY[ö€õ›€êò\⁄\»Hô\›‹ôY[ö€õ›€êò\⁄\Àà
BàHÿ]⁄
Œàõ›ÿXõJH»BàBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY êY\]ôSX\õö[ô»ãëôX]\ôHÿ\\ôH\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBÇàYà
Z\‘ÿ‹ò]⁄òYS]ôJH¬à⁄Y›”X\õö[ô—[ô⁄[ôKõ€ì]ôUòYQ^]
àZ[ùHòYRYõZ[ùà^]öXŸHH^]öXŸKà^]ôX\€€àHôX\€€ãà]ôTõ€€Hõà\’⁄[àHõèHKåÀ»çKéKåNNà[öYöYYIHõ€‹ãà
BàBààò[€\‹⁄YöXÿ][€ì]ôHH⁄[à¬à\‘ÿ‹ò]⁄òYS]ôHOàî–‘êU“Çà⁄›[X\õê\’⁄[àOàï“SàÇà⁄›[X\õê\”‹‹»Oàì‘‘»Çà[ŸHOàïSí”ì’”àÇàBààòYRYò€‹ŸY
^]öXŸKõõôX\€€äBàòYRYò€\‹⁄YöYY
€\‹⁄YöXÿ][€ì]ôKYà
\‘ÿ‹ò]⁄òYS]ôJHù[[ŸH⁄›[X\õê\’⁄[äBààòYSYôXﬁX€Kò€‹ŸY
òYRYõZ[ù^]öXŸKõôX\€€äBàòYSYôXﬁX€Kò€\‹⁄YöYY
òYRYõZ[ù€\‹⁄YöXÿ][€ì]ôKYà
\‘ÿ‹ò]⁄òYS]ôJHù[[ŸH⁄›[X\õê\’⁄[äBàòYSYôXﬁX€Kò€X\îõ‹‹ÿ[òX⁄⁄[ô òYRYõZ[ù
BÇàÀ»çKéKéNàôYYY[YHòYH[ù»çòYS\‹€€îôX€‹ô\à8°§à›ò]YﬁUù\›RBàûH¬àò[òY[ô”[ŸHHÀú‹⁄][€ãùòY[ô”[ŸKöYêõ[ö»»î’SëTëàBàÀ»çKåçéNH8†%ôX[ÿ]\ÿ[€€ù^[ú›XYŸà]\ò[ÀàH€õÿ⁄¬àÀ»›[\Y[ùûTôY⁄[YOTíT“◊””ã[ùûTŸ\‹⁄[€èS—ëó“’TîÀù\›ÿ€‹ôOLçKàÀ»úòY⁄[]Tÿ€‹ôOLå»[ô‹ùõ€[“X]Lå»€àUëTñH\‹€€ã€»BàÀ»ôY⁄[YKYö]^X›][€ã\]X[]H[ôò\úò]]ôK\\ú⁄\›[òŸHY[[‹ûH[ô\¬àÀ»\»ôX€‹ô\à^\›»»ŸY\Ÿ\\ò]HYõ›[ô»»Ÿ\\ò]KÇàò[\‹€€ê›H€€KõYôXﬁX€Xõ›ùçõY]KïòYS\‹€€îôX€‹ô\ãõ]ôP€€ù^éNJà›ò]YﬁHHòY[ô”[ŸKX\öŸ]HìQSQHãﬁ[Xõ€HòYRYúﬁ[Xõ€àZ[ùHòYRYõZ[ùà^X›Yö[öXŸHHÀú‹⁄][€ãô[ùûTöXŸKàÿ\\ôU[YHHÀú‹⁄][€ãô[ùûU[YKà
Bà€€KõYôXﬁX€Xõ›ùçõY]KïòYS\‹€€îôX€‹ô\ãò€€\]S\‹€€äà€€ù^H\‹€€ê››]€€YT›HõàYôT›HYà
Àú‹⁄][€ãöY⁄\›öXŸHà	âàÀú‹⁄][€ãô[ùûTöXŸHà
Bà

Àú‹⁄][€ãöY⁄\›öXŸHHÀú‹⁄][€ãô[ùûTöXŸJH»Àú‹⁄][€ãô[ùûTöXŸH
àL
H[ŸHõò€Ÿ\òŸP]X\›
å
KàXYT›Hõò€Ÿ\òŸP][‹›
å
Kà€ŸX»H

ﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YJH»L
Kù“[ù

Kà^]ôX\€€àHôX\€€ãX›X[ö[öXŸHH^]öXŸBà
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBààYà
ôX\€€ãõ›Ÿ\òÿ\ŸJ
Kò€€ùZ[ú ô\›öXù][€àäJH¬àö[ò[X⁄\⁄[€ëÿ]KúôX€‹ô\›öXù][€ë^]
òYRYõZ[ù
Bà€ìŸ º'Ê™»\›öXù][€à€€€›€éà	›Àúﬁ[Xõ€Hõÿ⁄ŸYõ‹àåMå»ãòYRYõZ[ù
BàBààò[ôX\€€ì›Ÿ\ì]ôHHôX\€€ãõ›Ÿ\òÿ\ŸJ
Bà⁄[à¬àôX\€€ì›Ÿ\ì]ôKò€€ùZ[ú ò€€\ŸHäHôX\€€ì›Ÿ\ì]ôKò€€ùZ[ú õ\WŸòZ[àäHOà¬àôY[ùûQ›X\ôõ€ì\]ZY]P€€\ŸJòYRYõZ[ù
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HH\]ZY]H€€\ŸH
[Z[äHãòYRYõZ[ù
BàBàôX\€€ì›Ÿ\ì]ôKò€€ùZ[ú ô\›öXù][€àäHôX\€€ì›Ÿ\ì]ôKò€€ùZ[ú ù⁄[WŸ[\äHôX\€€ì›Ÿ\ì]ôKò€€ùZ[ú ô]óŸ[\äHOà¬àôY[ùûQ›X\ôõ€ë\›öXù][€ë]X›Y
òYRYõZ[ù
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HH\›öXù][€à]\õà
€Z[äHãòYRYõZ[ù
BàBàôX\€€ì›Ÿ\ì]ôKò€€ùZ[ú ú›‹€‹‹»äHOà¬àôY[ùûQ›X\ôõ€î›‹‹‹“]
òYRYõZ[ùõ
Bà€ìŸ º'Â$àëQSïñHì–“—Qà	›Àúﬁ[Xõ€HH›‹‹‹»]
õZ[äHãòYRYõZ[ù
BàBàBààÀ»çKéKåÃŒà€õHYX[ö[ôŸù[‹‹Ÿ\»öYŸŸ\àôY[ùûQ›X\ô
]ôH€‹ŸH]
BàYà
õHLKå
H¬àôY[ùûQ›X\ôõ€ïòYS‹‹ òYRYõZ[ùõ
BàBààÀ»çKéKéŒà›X\ôYÿZ[ú›[úŸ][ùûU[YBàò[]ôQ[ùûU[YTÿYôHHYà
Àú‹⁄][€ãô[ùûU[YHàWÃÃÃÃ
HÀú‹⁄][€ãô[ùûU[YH[ŸHﬁ\›[Kò›\úô[ù[YSZ[\ 
Bàò[€Z[ù]\”]ôHH

ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH]ôQ[ùûU[YTÿYôJH»å
Kù“[ù

BàÀ»çKååŒMÃ»8†%[›ôYŸ[ô\öX»[ùûK—^]X\õö[ô»ôZ[ô€“\”Y[YPò\ŸKÇàÀ»€›\òŸHùYŒà]ôH›Xã[[ôH€‹Ÿ\»
VëT‘À–÷P”PÀ‘ëT–SKŸ] HŸ\ôBàÀ»€][ô»Ÿ[ô\öX»[ùûR[ù[YŸ[òŸK—^][ù[YŸ[òŸHôYõ‹ôHHÿ]BàÀ»ô[›»]ô[à›Y⁄Hô\›Ÿà\»õÿ⁄»ÿ\»›X\ôYÇÇàÀ»çKéKåŒL8†%Y[YK[€õHX\õö[ô»ÿ]Hõ‹àHUëHŸ[]à›Xã]òY\ÇàÀ»]ôH€‹Ÿ\»]\›õ›€]HôZ]ö[‹ìX\õö[ô»»⁄[H»ôY⁄[YH¬àÀ»[€Y[ù[H»ò\úò]]ôH»[YS‹»\]ZY]Q\»‹õ‹‹’[»¬àÀ»⁄Ÿ[ï⁄[ìY[[‹ûKà€€\]Y€òŸN»XX⁄›€ú›ôX[HûKXõÿ⁄»⁄X⁄‹»]Çàò[€’HH
Àú‹⁄][€ãùòY[ô”[ŸHŒààäKù\\òÿ\ŸJ
Bàò[€“\”Y[YPò\ŸHH€’Kö\–õ[ö 
H€’HZ[àŸ]Ÿäàî“U”“Sàãî“U”“Só—VëT‘»ãî“U”“SëVëT‘»ãëVëT‘»ãê÷P”P»ãàîUPSUHãêìQP“TãêìQW–“TãàìS””î“’ãïëPT’TñHãê–T“—SàãîëT–SW‘”íTHãîì“ëP’‘”íTTàãàìPSíTSUQãëT“SïTàãï“SW—ì”’»ãê”‘UêQHãï–SU‘ëP”’ëTëQÇà
BÇàYà
€“\”Y[YPò\ŸJH¬à[ùûR[ù[YŸ[òŸKõX\õëúõ€S›]€€YJòYRYõZ[ùõ€Z[ù]\”]ôJBà^][ù[YŸ[òŸKõX\õëúõ€Q^]
òYRYõZ[ùôX\€€ãõ€Z[ù]\”]ôJBà^][ù[YŸ[òŸKúô\Ÿ]‹⁄][€äòYRYõZ[ù
BàBÇàÀ»çKéKåÃåíVàYŸSX\õö[ô»ÿ\»ëUëTàÿ[Y[à]ôH]
\\ã[€õJKÇàÀ»õ›»Z\úõ‹ú»H\\à‘Ÿ[]€»YŸSX\õö[ô…‹»ô\⁄€àÀ»]]À][ô\à
ù^IK›õ€[YK‹\ŸHÿ]\ HX\õú»úõ€HôX[[€ô^H›]€€Y\ÀÇàYà
€“\”Y[YPò\ŸJHYŸSX\õö[ôÀõX\õëúõ€S›]€€YJàZ[ùHòYRYõZ[ùà^]öXŸHH^]öXŸKàõ\òŸ[ùHõàÿ\—^X›]YHùYKà
BÇàÀ»çKéKåÃåíVàôZ]ö[‹ìX\õö[ô»
[ô⁄[ôJHÿ\»ëUëTàÿ[Y[à]ôH]ÇàÀ»õ›»ôYHù[öX⁄€€ù^€»]\õààX\õú»úõ€H]ôH›]€€Y\ÀÇàûH¬àò[]ôUõ€][]S]ô[H⁄[à¬àÀõY]Kùõ€ÿ€‹ôHàOàëVëSQHÇàÀõY]Kùõ€ÿ€‹ôHàåOàíQ“ÇàÀõY]Kùõ€ÿ€‹ôHàOàìQQUSHÇà[ŸHOàì’»ÇàBàò[]ôUõ€[YT⁄Y€ò[H⁄[à¬àÀõY]Kùõ€ÿ€‹ôHàOàî’Të—HÇàÀõY]Kùõ€ÿ€‹ôHàåOàíSê‘ëPT“Së»ÇàÀõY]Kùõ€ÿ€‹ôHàOàìì‘ìPSÇàÀõY]Kùõ€ÿ€‹ôHàåOàëP‘ëPT“Së»Çà[ŸHOàì’»ÇàBàò[]ôTŸ[ù[Y[ùH⁄[à¬àÀõY]Kô[XYò[ê[Y€õY[ùò€€ùZ[ú êïSäHOàêïSÇàÀõY]Kô[XYò[ê[Y€õY[ùò€€ùZ[ú êëPTàäHOàêëPTàÇà[ŸHOàìëUUêSÇàBàò[]ôR›\àHò]òKù][êÿ[[ô\ãôŸ][ú›[òŸJ
KôŸ]
ò]òKù][êÿ[[ô\ãí’Tó”—ó—VJBàò[]ôQ^HHò]òKù][êÿ[[ô\ãôŸ][ú›[òŸJ
KôŸ]
ò]òKù][êÿ[[ô\ãëVW”—ó’—QR BàYà
€“\”Y[YPò\ŸJH¬àôZ]ö[‹ìX\õö[ôÀúôX€‹ôòYJà[ùûTÿ€‹ôHH‹Àô[ùûTÿ€‹ôKù“[ù

Kà[ùûT\ŸHH‹Àô[ùûT\ŸKöYë[\H»ïSí”ì’”ààKàŸ]\]X[]HH⁄[à¬à‹Àô[ùûTÿ€‹ôHèHLOàêJ»Çà‹Àô[ùûTÿ€‹ôHèHOàêHÇà‹Àô[ùûTÿ€‹ôHèHÃOàêàÇà[ŸHOàê»ÇàKàòY[ô”[ŸHH‹ÀùòY[ô”[ŸKöYë[\H»ìUëHàKàX\öŸ]Ÿ[ù[Y[ùH]ôTŸ[ù[Y[ùàõ€][]S]ô[H]ôUõ€][]S]ô[àõ€[YT⁄Y€ò[H]ôUõ€[YT⁄Y€ò[à\]ZY]U\ŸHÀõ\›\]ZY]U\ŸàXÿ\\ŸHÀõ\›Xÿ\à€\ï‹›HÀúÿYô]Kù‹€\î›ù—›XõJ
KàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKà›\ìŸë^HH]ôR›\ãà^SŸïŸYZ»H]ôQ^Kà€[YSZ[ù]\»H€Z[ù]\”]ôKàõ›Hõà
BàHÀ»çKéKåŒL8†%[ô€“\”Y[YPò\ŸHÿ]BàHÿ]⁄
Œà^Ÿ\[€äHﬂBààÀ»çKéKåŒL8†%ÿ]HHô[XZ[ô\àŸàY[YK\‹X⁄YöX»X\õö[ô»€à\‹Ÿ]€\‹ÀÇàYà
€“\”Y[YPò\ŸJH¬àûH¬àò[ÿ\‘⁄Y€ò[€‹úôX›H⁄[à¬àõàKåOàùYBàõMKåOàò[ŸBà[ŸHOàù[àBàYà
ÿ\‘⁄Y€ò[€‹úôX›OHù[
H¬à⁄[UòX⁄Ÿ\êRKúôX€‹ô⁄Y€ò[›]€€YJòYRYõZ[ùÿ\‘⁄Y€ò[€‹úôX›õ
BàBàHÿ]⁄
Œà^Ÿ\[€äHﬂBààÀ»çKéKåLMà8†%X\öŸ]ôY⁄[YPRK[€Y[ù[TôYX›‹êRKò\úò]]ôQ]X›‹êRKàÀ»[YS‹[Z^ò][€êRH[ÿ[Y[ú⁄YHT”úôX€‹ôòYS›]€€YPX‹õ‹‹–[^Y\ú»ô[›ÀÇàÀ»ô[[›ôY\ôX›ÿ[»8†%Ÿ\ôH›XõKX€›[ù[ô»]ô\ûH]ôH€‹ŸKÇàûH»\]ZY]Q\RKò€X\ë[ùûS\]ZY]JÀõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬àÀ»çKåçÃ8†%‹ôY]H[ùûK][YH‹õ‹‹À][»⁄Y€ò[]X›X[BàÀ»⁄\YHòYKàôX€€\][ô»]€‹ŸH‹ôY]»H‹õ€ô»XX⁄\ãÇàRP‹õ‹‹’[ÀúôX€‹ô›[\Y[ùûS›]€€YJÀõZ[ùõõàÀú‹⁄][€ãùòY[ô”[ŸJBàHÿ]⁄
Œà^Ÿ\[€äHﬂBààÀ»çKéKåLMà8†%⁄Ÿ[ï⁄[ìY[[‹ûKúôX€‹ôòYS›]€€YH[€»[àT”ô[›»8†%ô[[›ôYÇàHÀ»çKéKåŒL8†%[ô€“\”Y[YPò\ŸHõÿ⁄¬ààûH¬àò[€[YS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
HHÀú‹⁄][€ãô[ùûU[YBàò[\’⁄[àHõèHKåÀ»çKéKåNNà[öYöYYIHõ€‹Çàò[[ŸT›àHÀú‹⁄][€ãùòY[ô”[ŸBààò[^[ŸHHûH¬à[öYöYY[ŸS‹ò⁄\›ò]‹ãë^[ôY[ŸKùò[YSŸä[ŸT›äBàHÿ]⁄
Nà^Ÿ\[€äH¬à[öYöYY[ŸS‹ò⁄\›ò]‹ãë^[ôY[ŸKî’SëTëàBààô\X]
JH»À»çKéKåNMNàÿ\»ô\X]
 H8†%[ôõ]Y[ŸH›]¬à[öYöYY[ŸS‹ò⁄\›ò]‹ãúôX€‹ôòYJà[ŸHH^[ŸKà\’⁄[àH\’⁄[ãàõ›Hõà€[YS\»H€[YS\Àà
BàBààò[›]€€YT›àHYà
\’⁄[äHï“Sàà[ŸHYà
õLãå
Hì‘‘»à[ŸHî–‘êU“Çà›\\êúòZ[ë[ö[òŸ[Y[ùÀù\]R[ú⁄Y⁄›]€€YJÀõZ[ù›]€€YT›ãõ
BàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ì[ŸS‹ò⁄\›ò]‹àãìUëHôX€‹ô[ô»\úõ‹éà	ŸKõY\‹ÿYŸ_HäBàBààÀú‹⁄][€àH‹⁄][€ä
BàÀõ\›^]»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
BàÀõ\›^]öXŸHH^]öXŸBàÀõ\›^]õ›HõàÀõ\›^]ÿ\’⁄[àHõààÀ»çKéKåçMéàX\ö»€‹ŸY[à\ú⁄\›[ùÿ[]Y[[‹ûBàûH»ÿ[]⁄Ÿ[ìY[[‹ûKúôX€‹ô^]
ÀõZ[ùÀúﬁ[Xõ€^]öXŸKõîTTó—VUäHHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãúôX€‹ôŸ[€€ôö\õYY
ÀõZ[ùÀúﬁ[Xõ€^]öXŸKõîTTó—VUäHHÿ]⁄
Œà^Ÿ\[€äHﬂBààûH¬à‹⁄][€î\ú⁄\›[òŸKúÿ]ôT‹⁄][€ä Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'‰ØàUëH‹⁄][€àô[[›ôYúõ€H\ú⁄\›[òŸNà	›Àúﬁ[Xõ€HäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãº'‰Øà‹⁄][€à\ú⁄\›[òŸHô[[›ò[\úõ‹éà	ŸKõY\‹ÿYŸ_HãJBàBààûH¬àò[\’⁄[àHõèHKåÀ»çKéKååçéàIHõ€‹à8†%õå[ò€Y\»ôYK]ÿ\⁄ôX\ã^ô\õ‹¬ààò[ôX\›\ûT⁄Y€ò[HYà
\’⁄[äH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[ïR—W‘ì—íUàH[ŸH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKë^]⁄Y€ò[î’‘”‘‘¬àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêÿ\⁄Ÿ[ô\ò][€êRKò€‹ŸT‹⁄][€äòYRYõZ[ù^]öXŸKôX\›\ûT⁄Y€ò[
Bààò[⁄]€⁄[î⁄Y€ò[HYà
\’⁄[äH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ïòY\êRKë^]⁄Y€ò[ïR—W‘ì—íUàH[ŸH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ïòY\êRKë^]⁄Y€ò[î’‘”‘‘¬àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ïòY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ù^]öXŸK⁄]€⁄[î⁄Y€ò[
Bààò[õYX⁄\⁄Y€ò[HYà
\’⁄[äH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[ïR—W‘ì—íUàH[ŸH¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKë^]⁄Y€ò[î’‘”‘‘¬àBà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀêõYP⁄\òY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ù^]öXŸKõYX⁄\⁄Y€ò[
BÇàÀ»çKéKéMå»8†%SíUëTî–S’PãUêQTà”‘—H
]ôTŸ[]
KÇàÀ»ÿ[YHö^\»\\îŸ[à€‹ŸHHàZ\‹⁄[ô»›Xã]òY\àX\»€¬àÀ»ò\Y›‹‹‹”[€ö]‹â‹»›Xã]òY\à›ŸY\ÿ[â›ö[ôõ€XöY\ÀÇàûH¬àò[Q^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKë^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀì[€€ú⁄›òY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ù^]öXŸKQ^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[Q^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKë^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî]X[]UòY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ù^]öXŸKQ^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[X[ë^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKìX[ö\^]⁄Y€ò[ïR—W‘ì—íUà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKìX[ö\^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀìX[ö\[]YòY\êRKò€‹ŸT‹⁄][€äòYRYõZ[ù^]öXŸKX[ë^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[\^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKë\^]⁄Y€ò[îëP”’ëTñW’Të—Uà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKë\^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀë\[ù\êRKò€‹ŸQ\
òYRYõZ[ù^]öXŸK\^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[€ë^H€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKë^]⁄Y€ò[
à⁄›[^]HùYKà^]›HLàôX\€€àHYà
\’⁄[äHïR—W‘ì—íUà[ŸHî’‘”‘‘»ãàò[ö»H€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKî€ö\\îò[öÀîSëSëÀà
Bà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀîõ⁄ôX›€ö\\êRKò€€\]SZ\‹⁄[€äòYRYõZ[ù^]öXŸK€ë^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH¬àò[^^HYà
\’⁄[äH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àë^]⁄Y€ò[ïR—W‘ì—íUÃÃà[ŸH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àë^]⁄Y€ò[î’‘”‘‘¬à€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀî⁄]€⁄[ë^ô\‹Àô^]öYJòYRYõZ[ù^]öXŸK^^
BàHÿ]⁄
Œà^Ÿ\[€äHﬂBÇàòYP]]‹ö^ô\ãúô[X\ŸT‹⁄][€äàZ[ùHòYRYõZ[ùàôX\€€àHî—S…ôX\€€àãà
Bàà\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãº'Â$»UëH—Sàô[X\ŸY[ÿ⁄‹»õ‹à	›Àúﬁ[Xõ€HäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãôXùY ë^X›]‹àãë\úõ‹àô[X\⁄[ô»ÿ⁄‹»[à]ôTŸ[à	ŸKõY\‹ÿYŸ_HäBàBààûH¬àò[òYP€\‹⁄YöXÿ][€àH⁄[à¬àõèHLåOàîïSìëTàÇàõèHMKåOàêíQ◊’“SàÇàõèHãåOàï“SàÇàõèHLãåOàî–‘êU“ÇàõèHLLåOàì‘‘»Çà[ŸHOàêêQÇàBààò[Ÿ]\]X[]T›àH⁄[à
òYP€\‹⁄YöXÿ][€äH¬àîïSìëTààOàëV—SSïÇàêíQ◊’“SààOàëV—SSïÇàï“SààOàë””—Çàî–‘êU“àOàìëUUêSÇàì‘‘»àOàî”‘àÇàêêQàOàêêQÇà[ŸHOàìëUUêSÇàBààÀ»çKéKéŒà›X\ôYÿZ[ú›[úŸ][ùûU[YH
ò]»\ÿ⁄XZ»ùY Bàò[[ùûU[YTÿYôL»HYà
‹Àô[ùûU[YHàWÃÃÃÃ
H‹Àô[ùûU[YH[ŸHﬁ\›[Kò›\úô[ù[YSZ[\ 
Bàò[€Z[ù]\»H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH[ùûU[YTÿYôL H»ååàò[XZ‘õ›HYà
‹Àô[ùûTöXŸHà	âà‹ÀöY⁄\›öXŸHà
H¬à

‹ÀöY⁄\›öXŸHH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLàH[ŸHõààò[›\úô[ù€\ê€›[ùHÀö\›‹ûKõ\›‹ìù[

OÀö€\ê€›[ùŒààò[›\úô[ùõ€[YHHÀö\›‹ûKõ\›‹ìù[

OÀùõ€Œàåàò[\õﬁ⁄Ÿ[êYŸSZ[ù]\»H€Z[ù]\»
»Kåààò[›]€€YQ]HH€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀëYXÿ][€î›Xì^Y\êRKïòYS›]€€YQ]JàZ[ùHòYRYõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€à⁄Ÿ[ìò[YHHÀõò[YKàõ›Hõà€[YSZ[ù]\»H€Z[ù]\Àà[ùûU[YS\»H[ùûU[YTÿYôLÀà^]ôX\€€àHôX\€€ãà[ùûT\ŸHH‹Àô[ùûT\ŸKöYë[\H»ïSí”ì’”ààKàòY[ô”[ŸHH‹ÀùòY[ô”[ŸKöYë[\H»ìUëHàKà\ÿ€›ô\ûT€›\òŸHHÀú€›\òŸKöYë[\H»ïSí”ì’”ààKàŸ]\]X[]HHŸ]\]X[]T›ãà[ùûSXÿ\\ŸH‹Àô[ùûSXÿ\ùZŸRYà»]àHŒà
‹Àô[ùûS\]ZY]U\Ÿ
àäKà^]Xÿ\\ŸHÀõ\›Xÿ\à⁄Ÿ[êYŸSZ[ù]\»H\õﬁ⁄Ÿ[êYŸSZ[ù]\Ààù^Tò][‘›HÀö\›‹ûKõ\›‹ìù[

OÀòù^Tò][œÀù[Y\ L
HŒàLåàõ€[YU\ŸH›\úô[ùõ€[YKà\]ZY]U\ŸHÀõ\›\]ZY]U\Ÿà€\ê€›[ùH›\úô[ù€\ê€›[ùà‹€\î›HÀù‹€\î›ŒàÀúÿYô]Kù‹€\î›ùZŸRYà»]èHåHŒàYà
‹Àö\‘\\î‹⁄][€äHå[ŸHLåà€\ë‹õ››ò]HHÀö€\ë‹õ››ò]Kà]ïÿ[]›Håàõ€ô[ô–›\ùôTõŸ‹ô\‹»HåàùYÿ⁄X⁄‘ÿ€‹ôHHÀúÿYô]KúùYÿ⁄X⁄‘ÿ€‹ôKù—›XõJ
Kò€Ÿ\òŸP]X\›
å
Kà[XQò[î›]HHÀõY]Kô[XYò[ê[Y€õY[ùöYë[\H»ïSí”ì’”ààKà[ùûTÿ€‹ôHHÀô[ùûTÿ€‹ôKàöXŸQúõ€P]HåàX^ÿZ[î›HXZ‘õ›àX^ò]Ÿ›€î›H‹Àõ›Ÿ\›öXŸKõ]»›»OÇàYà
›»à	âà‹Àô[ùûTöXŸHà
H¬à

›»H‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸJH
àLàH[ŸHåàKà[YU‘XZ”Z[ú»H€Z[ù]\»
àçKàÀ»çKéKåÃåà\ö]ôHòY\î€›\òŸHúõ€HX›X[òY[ô»[ŸH8†%ÿ\»\ô€ŸYìY[YHÇàòY\î€›\òŸHH⁄[à¬à‹ÀùòY[ô”[ŸKú›\ù’⁄]
ìS””î“’äHOàìS””î“’Çà‹ÀùòY[ô”[ŸHOHî“U”“SààOàî“U”“SàÇà‹ÀùòY[ô”[ŸHOHêìQW–“TàOàêìQP“TÇà‹ÀùòY[ô”[ŸHOHîUPSUHàOàîUPSUHÇà‹ÀùòY[ô”[ŸHOHïëPT’TñHàOàïëPT’TñHÇà‹ÀùòY[ô”[ŸHOHëT“SïTààOàëT“SïTàÇà‹ÀùòY[ô”[ŸHOHìPSíTSUQàOàìPSíTSUQÇà[ŸHOàìQSQHÇàKà‹‹‘ôX\€€àHYà
õLãå
HôX\€€à[ŸHàãà[ùûP€‹›€€H‹Àò€‹›€€àõ€€Hõà^X›][€ì[ŸHHYà
‹Àö\‘\\î‹⁄][€äHú\\àà[ŸHõ]ôHãàõ€Ÿî›]HHòÿ[õ€öXÿ[Ÿö[ò[^ôYãà
Bàà€€KõYôXﬁX€Xõ›ùåÀúÿ€‹ö[ôÀëYXÿ][€î›Xì^Y\êRKúôX€‹ôòYS›]€€YPX‹õ‹‹–[^Y\ú ›]€€YQ]JBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKê]]€õ€[›\”Y]T€XﬁKúôX€‹ô›]€€YJÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKåçMà8†%»ì’òZ[àõ‹ùÿ\ô›]€€YS[Ÿ[’[öYöYY€XﬁRXY¬àÀ»[öYöYY^]€XﬁRXYúõ€H\»YÿXﬁHŸ[[ÿÿ[ÿ[òX⁄ÀàçKåçLMàÀ»Ÿ[ùò[^ôY‹ŸHXY»]^X›]‹ãúôX€‹ôòYHYù\àòYTõ›‘ÿ[ö]P⁄X⁄¬àÀ»[ô›ò]YﬁUù]YŸ\àÿ]\ÀàòZ[ö[ô»\ôH\Xÿ]\ÀŸ\ùK]òZ[ú»BàÀ»€XﬁHXY»[ô\»€ôHôX\€€àX\‹⁄]ôH[Ÿ[H⁄\ö[ô»Yõ›ò[ú€]BàÀ»[ù»õŸö]XõH]]‹ö]KàŸY\õ€ã\€XﬁHYÿXﬁHX\õô\ú»ô[›ÀÇàûH»\[[ôRX[€€X›‹ãõXô[[ò î”P÷W“PQ—TëP’—êSì’U‘’TëT‘—QÕMàäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî⁄Y€ò[]X[]UòX⁄Ÿ\ãúôX€‹ô›]€€YJÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKì^Y\êúòZ[ãúôX€‹ô›]€€YP[
ÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂHÀ»çKåçLLBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKî›ò]YﬁR\›\⁄\—[ô⁄[ôKúôX€‹ô›]€€YJÀõZ[ùõ
HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKåçLÃà8†%T–“TSëKTT‘»ëQQêP“»”‘àÀ»òZ[àHõ€[ôÀU‘à]\ŸHù]€ã[ôK][Y[›]ÿ]Kÿÿ[õô\ã[[ôBàÀ»úöYŸHúòZ[ã[ôŸYYHùY»õX⁄€\›à[õ›\àŸ[ã][ôHúõ€BàÀ»€‹ŸY]òYH›]€€Y\Œ»õ»Ÿ\\ò]HòZ[ö[ô»\‹»ôYYYÇàûH¬àò[[ôUYÕLÃàH
‹ÀùòY[ô”[ŸKöYêõ[ö»»ìQSQHàJKù\\òÿ\ŸJ
Bàò[‹ò’YÕLÃàH
Àú€›\òŸKöYêõ[ö»»ïSí”ì’”ààJKù\\òÿ\ŸJ
Bàò[€\ÕLÃàHûH»
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HH‹Àô[ùûU[YJKò€Ÿ\òŸP]X\›

HHÿ]⁄
Œàõ›ÿXõJH»Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKì]ôT]\ŸPù]€ãúôX€‹ô›]€€YJ[ôUYÕLÃãõ
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKì[ôU[Y[›]ÿ]KúôX€‹ô›]€€YJ[ôUYÕLÃãõ
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKîÿÿ[õô\ì[ôPúöYŸKúôX€‹ô›]€€YJ‹ò’YÕLÃã[ôUYÕLÃãõ
Bà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKîùY”Z[ùõX⁄€\›úôX€‹ô€‹ŸJÀõZ[ùõ€\ÕLÃäBàHÿ]⁄
Œàõ›ÿXõJHﬂBà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àãº'„§»TïêTëîêRSà
UëJNàôX€‹ôY›]€€YHõ‹à	›Àúﬁ[Xõ€HìI‹õù“[ù

_IHäBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãº'„§»\ùò\ôúòZ[àôX€‹ô[ô»òZ[Yà	ŸKõY\‹ÿYŸ_HäBàBààûH¬àò[î⁄Y»H€‹ŸP]]‹ö]T⁄Y»ŒààÇà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôT‹⁄][€ê€‹ŸP]]‹ö]Kôö[ò[^ôP€‹ŸY
àZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€Œàè»ãà⁄Y€ò]\ôHHî⁄YÀöYêõ[ö»»ù[KàôX\€€àHôX\€€ãà€€]Tò]»Hàô[XZ[ö[ô‘]Tò]»Hàõ›Hõù“[ù

Kà€›\òŸHHô^X›]‹ó€]ôW‹Ÿ[‹›XÿŸ\‹»ãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBÇàÀ»çKéKåéà€X\à[ûH[ô[ô»Ÿ[]Y]YH[ùûH€à€€ôö\õYY^]8†%ô]ô[ù¬àÀ»\Xÿ]H—S‘’Tï€àHô^õ›Ÿ\ùöXŸH€‹⁄[àHŸ[›XÿŸYYYàÀ»ù]H›[H[ùûHÿ\»›[⁄][ô»[à[ô[ô‘Ÿ[]Y]YH
[ú]Y]YYûH[ÇàÀ»X\õY\àêRSQ‘ëUñPPìH][\]ôXŸYYH›XÿŸ\‹Ÿù[Ÿ[
KÇàûH»[ô[ô‘Ÿ[]Y]YKúô[[›ôJÀõZ[ù
HHÿ]⁄
Œà^Ÿ\[€äHﬂBÇàÀ»çKéKåéLHíVà‘íUP–S8†%]ôTŸ[ô]ô\à€X\ôYÀú‹⁄][€ãÇàÀ»⁄]›]\ÀÀú‹⁄][€ãö\”‹[à›^YYùYHYù\àH€€ôö\õYY›ÿ\àÀ»€»õ›Ÿ\ùöXŸHôKY[ù\ôYH^]õÿ⁄»€àHëVX⁄»[ôÿ[YàÀ»ô\]Y\›Ÿ[YÿZ[à€àH‹⁄][€à]ÿ\»[ôXYH€€€ãX⁄Z[ãÇàÀ»\\îŸ[[ÿ^\»Y\»€X\é»]ôTŸ[ÿ\»Z\‹⁄[ô»][ù\ô[KÇàﬁ[ò⁄õ€ö^ôY
 H¬àÀú‹⁄][€àH‹⁄][€ä
BàÀõ\›^]»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
BàÀõ\›^]öXŸHH^]öXŸBàÀõ\›^]õ›HõàÀõ\›^]ÿ\’⁄[àHõààBàÀ»çKéKåçMéàX\ö»€‹ŸY[à\ú⁄\›[ùÿ[]Y[[‹ûBàûH»ÿ[]⁄Ÿ[ìY[[‹ûKúôX€‹ô^]
ÀõZ[ùÀúﬁ[Xõ€^]öXŸKõôX\€€äHHÿ]⁄
Œà^Ÿ\[€äHﬂBàûH»‹⁄][€î\ú⁄\›[òŸKúÿ]ôT‹⁄][€ä HHÿ]⁄
Œà^Ÿ\[€äHﬂBÇà€ìŸ ∏ß!HUëW—VU–””ëíTìQQà	›Àúﬁ[Xõ€HôX\€€èIôX\€€àìI‹õù“[ù

_IHãòYRYõZ[ù
Bà\úõ‹ìŸŸŸ\ãö[ôõ ë^X›]‹àã∏ß!HUëW—VU–””ëíTìQQà	›Àúﬁ[Xõ€HôX\€€èIôX\€€àìI‹õù“[ù

_IHäBÇàÀ»çKéKåçà›[\€€€›€à€»[ö]ô\úÿ[ÿ]Hõÿ⁄‹»[[YYX]HôKY[ùûBàÀ»ì’Nà[€»›[\YXõ›ôH[àﬁ[ò⁄õ€ö^ôYõÿ⁄»öXH\›^]À\»\»ô[X[ô\›\‹[ô\úÀÇà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêõ›Ÿ\ùöXŸKúôXŸ[ùP€‹ŸY\÷›ÀõZ[ùHHﬁ\›[Kò›\úô[ù[YSZ[\ 
Bàô]\õàŸ[ô\›[ê””ëíTìQQàBÇàÀ»8• 8• €‹ŸH[‹⁄][€ú»
õ‹àõ›⁄]›€äH8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• Çàù[à€‹ŸP[‹⁄][€ú à⁄Ÿ[úŒàX\›ö[ôÀ€€KõYôXﬁX€Xõ›ô]Kï⁄Ÿ[î›]Oãàÿ[]à€€[òUÿ[]Ààÿ[]€€à›XõKà\\ì[ŸNàõ€€X[ãà
Nà[ù¬àò\à€‹ŸY€›[ùHàò[‹[î‹⁄][€ú»H⁄Ÿ[úÀùò[Y\Àôö[\à»]ú‹⁄][€ãö\”‹[àBààÀ»çKéKçÃåà€X\à[ûH›[H\\àŸ[ÿ⁄‹»ëQì‘ëH]\ò][ô»‹⁄][€úÀÇàÀ»Hÿ⁄»Yù›ô\àúõ€HH‹ò\⁄YZY\Ÿ[
^Ÿ\[€àôYõ‹ôHö[ò[Hõÿ⁄»ö\ô\ÀàÀ»‹àúõ€HHŸ\‹⁄[€à›\ùYôYõ‹ôHHûKŸö[ò[Hö^
H€›[ÿ]\ŸH\\îŸ[

BàÀ»»ô]\õàSëPQW–”‘—Qõ‹à]ô\ûH‹⁄][€à8°§àÿ[]ô]ô\àôYù[ôY€à›‹Çà€X\ê[\\îŸ[ÿ⁄‹ 
BÇàYà
‹[î‹⁄][€úÀö\—[\J
JH¬à€ìŸ º'Ê‰Hõ››‹[ô»8†%õ»‹[à‹⁄][€ú»»€‹ŸHãú⁄]›€àäBàô]\õààBàà€ìŸ º'Ê‰Hõ››‹[ô»8†%€‹⁄[ô»	€‹[î‹⁄][€úÀú⁄^ô_H‹[à‹⁄][€ä Kããàãú⁄]›€àäBà€ìõ›YûJº'Ê‰Hõ››‹[ô»ãàê€‹⁄[ô»	€‹[î‹⁄][€úÀú⁄^ô_H‹[à‹⁄][€ä Hãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bààõ‹à
»[à‹[î‹⁄][€ú H¬àûH¬àò[‹»HÀú‹⁄][€ÇàYà
\‹Àö\”‹[äH€€ù[ùYBààò[ÿZ[î›HYà
‹Àô[ùûTöXŸHà
H¬à

Ÿ]X›X[öXŸJ HH‹Àô[ùûTöXŸJH»‹Àô[ùûTöXŸH
àL
BàH[ŸHåàà€ìŸ º'Â-SQTë—Sê÷H”‘—Nà	›Àúﬁ[Xõ€H	ŸÿZ[î›ù“[ù

_IHÿZ[àôX\€€èXõ›‹⁄]›€àãÀõZ[ù
BààÀ»çKéKçÕLXà8†%õ›]H€à‘“US”à\‘\\ãõ›€€ôöYÀàô]ö[›\€BàÀ»H]ôH‹⁄][€à⁄]ÿ[][ù[\ö[ô»⁄]›€à⁄[[ùBàÀ»õ€⁄ŸYH\\îŸ[
[ù€H€‹ŸNà‹⁄][€àù[Xô\ú»\]YàÀ»ù]⁄Ÿ[ú»›^YY€ãX⁄Z[äKàõ›Œà\\à‹⁄][€ú»\\îŸ[àÀ»]ôH‹⁄][€ú»]ôTŸ[àYàH]ôH‹⁄][€à\»õ»ÿ[]àÀ»⁄⁄\H€‹ŸH€»Hô^Ÿ\‹⁄[€àôX€€ò⁄[\àÿ[àY‹]Çàò[\‘\\î‹»H‹Àö\‘\\î‹⁄][€Çà⁄[à¬à\‘\\î‹»Oà¬à\\îŸ[
Àòõ›‹⁄]›€àäBà€‹ŸY€›[ù
 ¬àBàÿ[]OHù[Oà¬à]ôTŸ[
Àòõ›‹⁄]›€àãÿ[]ÿ[]€€
Bà€‹ŸY€›[ù
 ¬àBà[ŸHOà¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãàº'Ê™»“U’”ó–”‘—W—QëTîëQà	›Àúﬁ[Xõ€H8†%]ôH‹⁄][€à⁄]õ»ÿ[]àà
¬àî‹⁄][€àYù‘Sàõ‹àô^\Ÿ\‹⁄[€àôX€€ò⁄[\à»Y‹àäBà€ìŸ ∏£Ó]ôH	›Àúﬁ[Xõ€HYù‹[à8†%ÿ[]\ÿ€€õôX›Y]⁄]›€ãàãÀõZ[ù
BàBàBààHÿ]⁄
Nà^Ÿ\[€äH¬à€ìŸ ëòZ[Y»€‹ŸH	›Àúﬁ[Xõ€Nà	ŸKõY\‹ÿYŸ_HãÀõZ[ù
BàÀ»çKçÀéàõ‹òŸH€‹ŸH€àSñHòZ[\ôH\ö[ô»⁄]›€à8†%€â›X]ôH⁄‹›¬àûH¬àò[òYRYH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKïòYRY[ù]SX[òYŸ\ãôŸ]‹ê‹ôX]JÀõZ[ùÀúﬁ[Xõ€Àú€›\òŸJBàòYRYò€‹ŸY
Ÿ]X›X[öXŸJ KàYà
Àú‹⁄][€ãô[ùûTöXŸHà
H

Ÿ]X›X[öXŸJ HHÀú‹⁄][€ãô[ùûTöXŸJH»Àú‹⁄][€ãô[ùûTöXŸH
àL
H[ŸHLLåàJÀú‹⁄][€ãò€‹›€€
Kî“U’”ó—ì‘ê—W–”‘—HäBà€ìŸ ëõ‹òŸKX€‹ŸY€à⁄]›€éà	›Àúﬁ[Xõ€HãÀõZ[ù
Bà€‹ŸY€›[ù
 ¬àHÿ]⁄
Œà^Ÿ\[€äH¬àYà
\\ì[ŸJH¬àûH¬àò[‹»HÀú‹⁄][€Çàò[ò[YHH‹Àú]U⁄Ÿ[à
àŸ]X›X[öXŸJ Bà€î\\êò[[òŸP⁄[ôŸOÀö[ùõ⁄ŸJò[YJBàÀú‹⁄][€àH€€KõYôXﬁX€Xõ›ô]Kî‹⁄][€ä
Bà€ìŸ ëõ‹òŸKX€‹ŸY\\à‹⁄][€éà	›Àúﬁ[Xõ€HãÀõZ[ù
Bà€‹ŸY€›[ù
 ¬àHÿ]⁄
Œà^Ÿ\[€äHﬂBàBàBàBàBàà€ìŸ ∏ß!H€‹ŸY	€‹ŸY€›[ù…€‹[î‹⁄][€úÀú⁄^ô_H‹⁄][€ú»€à⁄]›€àãú⁄]›€àäBà€ìõ›YûJ∏ß!H‹⁄][€ú»€‹ŸYãàê€‹ŸY	€‹ŸY€›[ù‹⁄][€ä H€àõ›⁄]›€àãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bààô]\õà€‹ŸY€›[ùàBÇàÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dàÀ»çKéKåçåH8†%UëH–SU’—QT
HK[[€ù⁄õ€öX»ùY»ö^
BàÀ¬àÀ»^Y\àòY\ú»
⁄]€⁄[ã”[€€ú⁄›–õYP⁄\”X[ö\—^ô\‹À‘]X[]JH€õBàÀ»»[ã[Y[[‹ûHìXÿ€›[ù[ô»[àZ\à€‹ŸT‹⁄][€ä
X]Àà^BàÀ»ëUëTàúõÿYÿ\›Hù\]\àŸ[àô\›[à⁄[àH\Ÿ\à]’‘ì’àÀ»]ô\ûHY[YKÿ[€⁄[àHõ›õ›Y⁄›^YY€ãX⁄Z[à[àHÿ[]àÀ»õ‹ô]ô\à[ôY»ôH€X\ôYX[ùX[HöXH[ù€KÇàÀ¬àÀ»\»›ŸY\[ù[Y\ò]\»UëTñHõ€ã\›XõX€⁄[à‘€[ô»[àBàÀ»ÿ[]Ÿ]»Húô\⁄ù\]\à][›K[ôúõÿYÿ\›»HôX[àÀ»›ÿ\]ÀT””⁄]Hÿ[YH€\YŸKY\ÿÿ[][€àŸ⁄X»]ôTŸ[\Ÿ\ÀÇàÀ»›XõX€⁄[ú»
T—À’T—
H[ô””\ôHô\Ÿ\ùôYÇàÀ¬àÀ»ÿ[YûHõ›Ÿ\ùöXŸKú›‹õ›

H[à]ôH[ŸKàY[\›[ù8†%ù[õö[ô¬àÀ»]⁄XŸH\»õ»YôôX›€òŸHHÿ[]\»[\HŸàòYH⁄Ÿ[úÀÇàÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dàù[à]ôT›ŸY\ÿ[]⁄Ÿ[ú àÿ[]à€€[òUÿ[]àÿ[]€€à›XõKàY][€ò[ô\Ÿ\ùôYZ[ùŒàŸ]›ö[ôœàH[\TŸ]

Kà
Nà[ù¬àÀ»›XõX€⁄[ú»»””ŸHô]ô\à]]À\Ÿ[€à⁄]›€Çàò[ëT—TïëQ”RSï»HŸ]Ÿäàù\]\ê\Kî”””RSïÀ»‘””àëTëïŸP]YúT‘‹YSLúSå^ûXò\ŒÕ—Q—⁄÷ùﬁU]àãÀ»T—¬àë\Œ]ìQúûòP—TõRôúëçëñQ–€”ö÷LLSX–ŸNô[ù”ñPàãÀ»T—àî€ÃLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLàãÀ»‘””[à
H
»Y][€ò[ô\Ÿ\ùôYZ[ù»À»çKéKåÃNàô\Ÿ\ùôHX›]ôHå»‹⁄][€ú»\ö[ô»\ö[ŸX»ôX€€ò⁄[Bàò\à€€€›[ùHàò[»HŸô 
BÇàò[€ê⁄Z[àHûH¬àÀ»çKéKåÃNàô]ûHî»\»»[Y\»⁄]òX⁄€ŸôãàHK[[€ùàÀ»⁄õ€öX»	‘’‘ì’õ›€X\ö[ô»]ôH⁄Ÿ[ú…»ùY»Y\»\»BàÀ»õ€›ÿ]\ŸNàH⁄[ô€HòZ[[ô»î»ÿ[€›[⁄[[ùHXõ‹ùBàÀ»[ù\ôH›ŸY\X]ö[ô»]ô\ûHXZŸY⁄Ÿ[à›ò[ôY€ãX⁄Z[ãÇàò\à\›\úéà^Ÿ\[€è»Hù[àò\àô\›[àX\›ö[ôÀ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[⁄Ÿ[ê[[›[ùè»Hù[àõ‹à
][\[àKãå H¬àûH¬àô\›[Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

BàúôXZ¬àHÿ]⁄
Nà^Ÿ\[€äH¬à\›\úàHBà€ìŸ ∏¶®;Ó#»“U’”à’—QTàî»][\	][\Ã»òZ[Y8†%	ŸKõY\‹ÿYŸOÀùZŸJå
_Hãú⁄]›€àäBàYà
][\ HûH»ôXYú€Y\

][\
àML
Kò€Ÿ\òŸP]X\›
L
JHHÿ]⁄
Œà^Ÿ\[€äHﬂBàBàBàô\›[Œàù[à¬à€ìŸ ∏¶®;Ó#»“U’”à’—QTà[»î»][\»òZ[Y8†%	€\›\úèÀõY\‹ÿYŸOÀùZŸJ
_Hãú⁄]›€àäBàô]\õààBàHÿ]⁄
Nà^Ÿ\[€äH¬à€ìŸ ∏¶®;Ó#»“U’”à’—QTàòZ[Y»[ù[Y\ò]Hÿ[]8†%	ŸKõY\‹ÿYŸOÀùZŸJ
_Hãú⁄]›€àäBàô]\õààBÇàYà
€ê⁄Z[ãö\—[\J
JH¬à€ìŸ ∏ß!H“U’”à’—QTàÿ[][\H8†%õ›[ô»»\]ZY]Hãú⁄]›€àäBàô]\õààBÇàÀ»çKååÕŒH8†%‹\ò]‹àò][ÃŒà⁄]›€à›ŸY\]\›”ìHŸ[›\úô[ùàÀ»ÿ[]Z[]ôH‹⁄][€ú»⁄]ù\›Yÿ[]ò]»àHôX[Ÿ[XõBàÀ»õ€‹ãàHò\ôH]Kôö\ú›àåYZ]Y\›ZŸHZOLåH»ò]œLKàÀ»⁄X⁄[àùZ[HõŸ›\»—S‘Só”“»YÿZ[ú›Hõ€ã\‹⁄][€ãà[ôõ‹òŸNÇàÀ»HZ[ùõ›ô\Ÿ\ùôY
””»›Xõ\»»X›]ôHå»‹⁄][€ú KàÀ»H›\úô[ùÿ[]RH›öX›HXõ›ôHH›ŸY\\›õ€‹ãàÀ»Hò]»[ö]»Xõ›ôHH›ŸY\ò]»õ€‹ãàÀ»HZ[ùõ››[\Y”‘—Q»Sí”ì’”ó’SïïT’Q‘î»[àH€‹ŸH]]‹ö]KÇàò[’—QT”RSó’RHHYKMÀ»ô[›»\»\»\›ô]ô\àõ›]XõBàò[’—QT”RSó‘êU»HLÀ»ò]»õ€‹à[ô\[ô[ùŸàX⁄[X[¬àò[Ÿ[XõHH€ê⁄Z[ãôö[\à»
Z[ù]JHOÇàYà
Z[ù[àëT—TïëQ”RSï Hô]\õêö[\àò[ŸBàò[ZHH]Kôö\ú›àò[X⁄[X[»H]KúŸX€€ôàYà
JZHà’—QT”RSó’RJJH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî“U’”ó‘’—QT‘““T—T’ãõZ[ùI€Z[ùùZŸJL
_HZOIZHôX\€€èPëS’◊‘’—QT’RW—ì”‘àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õêö[\àò[ŸBàBàò[ò]–\õﬁHûH»
ZH
àLåú› X⁄[X[Àù—›XõJ
JJKù”€ô 
HHÿ]⁄
Œàõ›ÿXõJH»BàYà
ò]–\õﬁ’—QT”RSó‘êU H¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî“U’”ó‘’—QT‘““T—T’ãõZ[ùI€Z[ùùZŸJL
_Hò]œIò]–\õﬁôX\€€èPëS’◊‘’—QT‘êU◊—ì”‘àäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õêö[\àò[ŸBàBàò[€‹ŸY‹ï[ùù\›YHûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ì]ôT‹⁄][€ê€‹ŸP]]‹ö]Kö\–€‹ŸY‹ï[ùù\›Y
Z[ù
BàHÿ]⁄
Œàõ›ÿXõJH»ò[ŸHBàYà
€‹ŸY‹ï[ùù\›Y
H¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jî“U’”ó‘’—QT‘““T–”‘—QãõZ[ùI€Z[ùùZŸJL
_HôX\€€èP”‘—Q”‘ó’SïïT’Q‘î»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õêö[\àò[ŸBàBàùYBàBÇàYà
Ÿ[XõKö\—[\J
JH¬à€ìŸ ∏ß!H“U’”à’—QTàõ€ã\›XõX€⁄[à€[ô‹»8†%ÿ[]\»€X[àãú⁄]›€àäBà]ôUòYSŸ‘›‹ôKõŸ àú›ŸY\â‘ﬁ\›[Kò›\úô[ù[YSZ[\ 
_Hãùÿ[]ãú›ŸY\ãî’—QTãà]ôUòYSŸ‘›‹ôKî\ŸKî’—QT—”ëKàïÿ[][ôXYH€X[à
Ÿ[XõH€[ô‹ HãàòY\ïY»Hî’—QTãà
Bàô]\õààBÇàò[›ŸY\Ÿ^HHú›ŸY\â‘ﬁ\›[Kò›\úô[ù[YSZ[\ 
_HÇà€ìŸ º'Ê‰H“U’”à’—QTà\]ZY][ô»	‹Ÿ[XõKú⁄^ô_H€ãX⁄Z[à€[ô  H»””8†)àãú⁄]›€àäBà]ôUòYSŸ‘›‹ôKõŸ à›ŸY\Ÿ^Kùÿ[]ãú›ŸY\ãî’—QTãà]ôUòYSŸ‘›‹ôKî\ŸKî’—QT‘’Tïàî›ŸY\›\ùY8†%	‹Ÿ[XõKú⁄^ô_Hõ€ã\›XõX€⁄[à€[ô‹»ãàòY\ïY»Hî’—QTãà
Bà€ìõ›YûJàº'Ê‰H›ŸY\[ô»ÿ[]ãàîŸ[[ô»	‹Ÿ[XõKú⁄^ô_H⁄Ÿ[ä HòX⁄»»””ôYõ‹ôH⁄]›€àãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëìÀà
BÇàõ‹à

Z[ù]JH[àŸ[XõJH¬àò[ò[[òŸUZHH]Kôö\ú›àò[X⁄[X[»H]KúŸX€€ôàò[ﬁ[Xõ€HûH¬àòYRY[ù]SX[òYŸ\ãôŸ]‹ê‹ôX]JZ[ùZ[ùùZŸJäKú⁄]›€ó‹›ŸY\äKúﬁ[Xõ€àHÿ]⁄
Œà^Ÿ\[€äH»Z[ùùZŸJäHBàò[⁄Ÿ[î›ŸY\Ÿ^HHâ›ŸY\Ÿ^NâZ[ùÇà]ôUòYSŸ‘›‹ôKõŸ à⁄Ÿ[î›ŸY\Ÿ^KZ[ùﬁ[Xõ€î’—QTãà]ôUòYSŸ‘›‹ôKî\ŸKî’—QT’“—Só’ñKàïûZ[ô»	ﬁ[Xõ€]OIÿò[[òŸUZKôõ]

_HXœIX⁄[X[»ãà⁄Ÿ[ê[[›[ùHò[[òŸUZKòY\ïY»Hî’—QTãà
BÇàûH¬àò[][\Y\àHLåú› X⁄[X[Àù—›XõJ
JBàò[ò]’[ö]»H
ò[[òŸUZH
à][\Y\äKù”€ô 
Kò€Ÿ\òŸP]X\›
S
BÇàÀ»⁄⁄\\›]ù\]\àÿ[õõ›õ›]H
ååH””Ÿàò[YJKÇàÀ»ŸH€â›]ôHH]ôHöXŸHõ‹à\òö]ò\ûHZ[ù»\ôK€¬àÀ»\ŸHH€€úŸ\ùò]]ôHò]À][ö]»õ€‹ãÇàYà
ò]’[ö]»S
H¬à€ìŸ ∏£Î{Ó#»’—QT““T	ﬁ[Xõ€à\›]HãZ[ù
N»€€ù[ùYBàBÇàÀ»çKéKçŒ8†%X]⁄H]ôTŸ[\ÿÿ[][€àY\à€»BàÀ»⁄]›€à›ŸY\Ÿ\€â›òZ[\›X⁄»€àõ€][H[\ôù[àY[Y\¬àÀ»]Lú»⁄[à]ôTŸ[\ÿÿ[]\»\»Lú»[Ÿ]⁄\ôKÇàÀ»‹\ò]‹éà	›⁄[à[›H›‹Hõ›[⁄Ÿ[ú»ô[XZ[à[àBàÀ»‹›ÿ[]	Ààõ€›ÿ]\ŸNà\»›ŸY\ÿ\Y]Lú»[ôàÀ»û\\‹ŸYŸ]][›U⁄]€\YŸQ›X\ô
€»ëîHõ›]\»Ÿ\ô[â›àÀ»ZŸ\ãXõ›[ôúõÿYÿ\›]MŒ[ô⁄[[ùHòZ[Y]àÀ»[ôHÕçJKàõ›ŒàåÕÕåÃLúÀZŸ\ãXõ›[ôö[ô[ô¬àÀ»Ÿ[][›H
ëîKX]ÿ\ôJK[ôúõÿYÿ\›òZ[\ô\»⁄][àBàÀ»Y\à€‹ò[õ›Y⁄»Hô^Y\à[ú›XYŸÇàÀ»ùXòõ[ô»\»H›]\à	›⁄Ÿ[àYù[àÿ[]	»ÿ]⁄Çàò[€\YŸS]ô[»H\›ŸäååL
Bàò\à][›Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî›ÿ\][›O»Hù[àò\à›ŸY\⁄YŒà›ö[ôœ»Hù[àò\à›ŸY\\›^à^Ÿ\[€è»Hù[à›ŸY\õ‹à
€\[à€\YŸS]ô[ H¬àûH¬àò[›ŸY\»H⁄Ÿ[î›]JZ[ùHZ[ùﬁ[Xõ€Hﬁ[Xõ€
Bàò[›ŸY\[àHôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»H›ŸY\Ààÿ[]Hÿ[]àõÿŸ\‹€‹àHíïTUTó‘“U’”ó‘’—QTãàô\]Y\›YZT]HHò[[òŸUZKàŸ[òYRŸ^HH⁄Ÿ[î›ŸY\Ÿ^KàòY\ïY»Hî’—QTãà
HŒàõ›»ù[ù[YQ^Ÿ\[€äî›ŸY\ò[[òŸHõ€Ÿà[ò]òZ[XõHäBà][›HHŸ]][›U⁄]€\YŸQ›X\ô
àZ[ùù\]\ê\Kî”””RSï›ŸY\[ãúò]–[[›[ù€\à\–ù^HHò[ŸKŸ[ZŸ\àHÿ[]úXõX“Ÿ^PçN
BàHÿ]⁄
Nà^Ÿ\[€äH¬à›ŸY\\›^HBà€ìŸ ∏¶®;Ó#»’—QT	ﬁ[Xõ€à][›H	‹€\Xú»òZ[Y8†%	ŸKõY\‹ÿYŸOÀùZŸJL
_HãZ[ù
BàôXYú€Y\
çL
Bà€€ù[ùYP›ŸY\àBàÀ»€›H][›H]\»Y\à8†%ûH»úõÿYÿ\›ÇàûH¬àò[ô\›[›ŸY\HùZ[⁄]ô]ûJà][›Kÿ[]úXõX“Ÿ^PçN[ò[ZX‘€\YŸSX^ú»H
€\
àJKò€Ÿ\òŸR[ä€\NNNJKàŸ[ô\ï\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHùYJKà
BàŸX›\ö]Kô[ôõ‹òŸT⁄Y€ë[^J
Bàò[\ŸRö]»HÀöö]—[òXõY	âà\][›Kö\’[òBàò[[òTô\RYHYà
][›Kö\’[òJHô\›[›ŸY\úô\]Y\›Y[ŸHù[à›ŸY\⁄Y»Hÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJàô\›[›ŸY\ùò\ŸMç\ŸRö]ÀYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHùYJKà[òTô\RYÀöù\]\ê\RŸ^Kô\›[›ŸY\ö\‘ôúTõ›]Kô\›[›ŸY\úŸ[ô\ê€€\]XõKà
BàúôXZ–›ŸY\àHÿ]⁄
ô^à^Ÿ\[€äH¬à›ŸY\\›^Hô^àò[ÿYôT›ŸY\HŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ ô^õY\‹ÿYŸHŒàù[ö€õ›€àäBàò[\‘›ŸY\€\HÿYôT›ŸY\ò€€ùZ[ú åMŒãY€õ‹ôPÿ\ŸHHùYJHàÿYôT›ŸY\ò€€ùZ[ú åMŒHãY€õ‹ôPÿ\ŸHHùYJHàÿYôT›ŸY\ò€€ùZ[ú ï€”]T€€ôXŸZ]ôYãY€õ‹ôPÿ\ŸHHùYJHàÿYôT›ŸY\ò€€ùZ[ú î€\YŸHãY€õ‹ôPÿ\ŸHHùYJBàYà
\‘›ŸY\€\
H¬à€ìŸ ∏¶®H’—QT	ﬁ[Xõ€à”TQ—H	‹€\Xú»8†%\ÿÿ[][ô»»ô^Y\àãZ[ù
Bà€€ù[ùYP›ŸY\àBàÀ»õ€ã\€\YŸHúõÿYÿ\›òZ[\ôH8†%ùXòõH›]ŸàH€‹Çàõ›»ô^àBàBàYà
][›HOHù[›ŸY\⁄Y»OHù[
H¬à€ìŸ º'Ê™’—QT	ﬁ[Xõ€àY\à^]\›Y
	‹›ŸY\\›^ÀõY\‹ÿYŸOÀùZŸJå
HŒàõõ»][›HüJKà⁄Ÿ[àYù[àÿ[]àãZ[ù
Bà€€ù[ùYBàBà€ìŸ ∏ß!H’—QT””	ﬁ[Xõ€à	ÿò[[òŸUZKôõ]

_H8°§à””⁄YœI‹›ŸY\⁄YÀùZŸJMä_x†)àãZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ à⁄Ÿ[î›ŸY\Ÿ^KZ[ùﬁ[Xõ€î’—QTãà]ôUòYSŸ‘›‹ôKî\ŸKî’—QT’“—Só—”ëKà∏ß!H€€	ÿò[[òŸUZKôõ]

_H8°§à””ãà⁄Y»H›ŸY\⁄YÀ⁄Ÿ[ê[[›[ùHò[[òŸUZKòY\ïY»Hî’—QTãà
Bà€€€›[ù
 ¬àHÿ]⁄
Nà^Ÿ\[€äH¬à€ìŸ º'Ê™’—QT	ﬁ[Xõ€êRSQà	ŸKõY\‹ÿYŸOÀùZŸJ
_H8†%⁄Ÿ[àô[XZ[ú»[àÿ[]ãZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ à⁄Ÿ[î›ŸY\Ÿ^KZ[ùﬁ[Xõ€î’—QTãà]ôUòYSŸ‘›‹ôKî\ŸKî’—QT’“—Só—êRSQàº'Ê™›ŸY\òZ[Yà	ŸKõY\‹ÿYŸOÀùZŸJLå
_H8†%⁄Ÿ[àô[XZ[ú»[àÿ[]ãàòY\ïY»Hî’—QTãà
Bà\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãî⁄]›€à›ŸY\òZ[Yõ‹à	Z[ùà	ŸKõY\‹ÿYŸ_HãJBàBàBÇà€ìŸ ∏ß!H“U’”à’—QT””TUNà€€	€€€›[ù…‹Ÿ[XõKú⁄^ô_H€[ô‹»»””ãú⁄]›€àäBà]ôUòYSŸ‘›‹ôKõŸ à›ŸY\Ÿ^Kùÿ[]ãú›ŸY\ãî’—QTãà]ôUòYSŸ‘›‹ôKî\ŸKî’—QT—”ëKàî›ŸY\€€\]H8†%	€€€›[ù…‹Ÿ[XõKú⁄^ô_H€€»””ãàòY\ïY»Hî’—QTãà
Bà€ìõ›YûJà∏ß!Hÿ[]›Ÿ\ãàî€€	€€€›[ùŸà	‹Ÿ[XõKú⁄^ô_H€[ô‹»òX⁄»»””ãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëìÀà
Bàô]\õà€€€›[ùàBÇàÀ»8• 8• ù\]\à[\ú»8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• Çàö]ò]Hù[àŸ]][›U⁄]€\YŸQ›X\ô
à[ìZ[ùà›ö[ôÀ›]Z[ùà›ö[ôÀ[[›[ùà€ôÀ€\YŸPúŒà[ùà[ú]€€à›XõHHåà\–ù^Nàõ€€X[àHùYKàŸ[ZŸ\éà›ö[ôœ»Hù[À»çKéKçé8†%XöŸ^Hõ‹àZŸ\ãXõ›[ôö[ô[ô»Ÿ[‹ô\Çàù^UZŸ\éà›ö[ôœ»Hù[À»çKåçÃçH8†%XöŸ^Hõ‹àZŸ\ãXõ›[ôö[ô[ô»ïVH‹ô\ÇàôYô\îŸ[ô\ïò[ú‹‹ùàõ€€X[àHò[ŸKà
Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî›ÿ\][›H¬àYà
Z\–ù^JH¬àÀ»çKéKçé8†%ê–Hö^àô]ö[›\€Hÿ[YŸ]][›J
H
õ€ãXö[ô[ô»€‹ô\ÇàÀ»⁄]›]ZŸ\äH⁄X⁄[ÿ^\»›XÿŸYYYõ‹àëîHõ›öY\ú»]ô[à⁄[ÇàÀ»^H€›[ôZôX›Hö[ô[ô»‹ô\ãX]ö[ô»HŸ[»òZ[àÀ»⁄[[ùH[àùZ[›ÿ\àõ›»ŸHô\]Y\›Hö[ô[ô»‹ô\à]àÀ»][›H[YH⁄[àŸH]ôHHZŸ\àXöŸ^K€»ëîHôZôX›[€ú¬àÀ»›\ôòXŸH\»—S‘US’W—êRSò]\à[à⁄‹›[ô»HòYKÇàô]\õàYà
\Ÿ[ZŸ\ãö\”ù[‹êõ[ö 
JH¬àù\]\ãôŸ]][›U⁄]ZŸ\ä[ìZ[ù›]Z[ù[[›[ù€\YŸPúÀŸ[ZŸ\äBàH[ŸH¬àÀ»YÿXﬁHÿ[\ú»]€â›]ôHHZŸ\à8†%ŸY\€ôZ]ö[›\ÇàÀ»
][›K[€õJKàY»õ»ôY‹ô\‹⁄[€àö\⁄ÀÇàù\]\ãôŸ]][›J[ìZ[ù›]Z[ù[[›[ù€\YŸPú BàBàBàÀ»çKåçÃçH8†%”’Tê—HíVõ‹àH^X›⁄Xõ[ô»ŸàHçKéKçéŸ[àÀ»ùY»€àHïVH⁄YKàù[ù[YHÃç⁄›ŸY][›S⁄œLÀ‹›ÿ\ùZ[LÇàÀ»Hõ€ãXö[ô[ô»\ŸKLH[òH][›H[ÿ^\»ú›XÿŸYY»ã[ÇàÀ»ùZ[[òU	‹»úô\⁄ZŸ\ãXõ›[ô\ŸKLà€‹ô\àÿ[Ÿ]»ëîKBàÀ»X€[ôY⁄]õ›[ô»\›ôX[H]ô\àŸYZ[ô»]à\‹⁄[ô»ù^UZŸ\ÇàÀ»XZŸ\»€\YŸQ›X\ôô\]Y\›Hö[ô[ô»‹ô\àUUS’HSQH€¬àÀ»[àëîHX€[ôH›\ôòXŸ\»\ôH\»H][›HòZ[\ôH
ô]ûXXõK¬àÀ»\ÿÿ[]XõJH[ú›XYŸàH⁄[[ùXY[ô]HùZ[\ãÇàò[ò[Y]YH€\YŸQ›X\ôùò[Y]T][›Jà[ìZ[ù›]Z[ù[[›[ù€\YŸPúÀ[ú]€€ù^UZŸ\ãàôYô\îŸ[ô\ïò[ú‹‹ùHôYô\îŸ[ô\ïò[ú‹‹ùà
BàYà
]ò[Y]Yö\’ò[Y
H¬àõ›»^Ÿ\[€äò[Y]YúôZôX›ôX\€€äBàBàô]\õàò[Y]Yú][›BàBÇàö]ò]Hù[àùZ[⁄]ô]ûJà][›Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî›ÿ\][›KXöŸ^Nà›ö[ôÀà[ò[ZX‘€\YŸSX^úŒà[ù»Hù[àŸ[ô\ï\[\‹ùŒà€ô»HàŸ[ô\ê€€\]U[ö]öXŸSZX‹õ”[\‹ùŒà€ô»HçWÃà
Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî›ÿ\ô\›[¬àô]\õàûH¬àù\]\ãòùZ[›ÿ\
][›KXöŸ^K[ò[ZX‘€\YŸSX^úÀŸ[ô\ï\[\‹ùÀŸ[ô\ê€€\]U[ö]öXŸSZX‹õ”[\‹ù BàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àã∏¶®;Ó#»ùZ[›ÿ\][\HòZ[Yà	ŸKöò]òP€\‹Àú⁄[\Sò[Y_H	ŸKõY\‹ÿYŸOÀùZŸJLå
_HäBàôXYú€Y\
L
BàûH¬àù\]\ãòùZ[›ÿ\
][›KXöŸ^K[ò[ZX‘€\YŸSX^úÀŸ[ô\ï\[\‹ùÀŸ[ô\ê€€\]U[ö]öXŸSZX‹õ”[\‹ù BàHÿ]⁄
Léà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àã∏ßcùZ[›ÿ\][\àS”»òZ[Yà	ŸLãöò]òP€\‹Àú⁄[\Sò[Y_H	ŸLãõY\‹ÿYŸOÀùZŸJLå
_HäBàõ›»LÇàBàBàBÇàö]ò]Hù[à⁄Ÿ[îÿÿ[Jò]–[[›[ùà€ô Nà›XõH¬àô]\õà⁄[à¬àò]–[[›[ùèHWÃÃÃÃOàWÃÃÃÃåàò]–[[›[ùèHWÃÃÃOàWÃÃÃåà[ŸHOàWÃÃåàBàBÇàÀ»8• 8• ôX\›\ûH⁄]ò]ÿ[8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• 8• Çàù[à^X›]UôX\›\ûU⁄]ò]ÿ[
àô\]Y\›Y€€à›XõKà\›[ò][€êYô\‹Œà›ö[ôÀàÿ[]à€€KõYôXﬁX€Xõ›õô]€‹öÀî€€[òUÿ[]Ààÿ[]€€à›XõKà
Nà›ö[ô»¬àò[€€Hÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸBàò[ô\›[HôX\›\ûSX[òYŸ\ãúô\]Y\›⁄]ò]ÿ[[[›[ù
ô\]Y\›Y€€€€
BÇàYà
\ô\›[ò\õ›ôY
H¬à€ìŸ º'„Èà⁄]ò]ÿ[õÿ⁄ŸYà	‹ô\›[õY\‹ÿYŸ_HãùôX\›\ûHäBàô]\õàêì–“—Qà	‹ô\›[õY\‹ÿYŸ_HÇàBÇàò[\õ›ôYHô\›[ò\õ›ôY€€à€ìŸ º'„ÈàôX\›\ûH⁄]ò]ÿ[à	ÿ\õ›ôYôõ]

_x•„à8°§à	Ÿ\›[ò][€êYô\‹ÀùZŸJMä_x†)àãùôX\›\ûHäBÇàÀ»çKéKçÕLXà8†%ôYù\ŸH\\àò[òX⁄»€àôX\›\ûH⁄]ò]ÿ[⁄[à]ôKÇàYà
\‘\\îï

JH¬àôX\›\ûSX[òYŸ\ãô^X›]U⁄]ò]ÿ[
\õ›ôY€€\›[ò][€êYô\‹ Bà€ìŸ îTTàëPT’TñH“UêU–Sà	ÿ\õ›ôYôõ]

_x•„àãùôX\›\ûHäBàô]\õàì“◊‘TTàÇàBàYà
ÿ[]OHù[
H¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãàº'Ê™»UëW’ëPT’TñW’“UêU–S‘ëQïT—Qàÿ[]\»ïSàôYù\⁄[ô»\\ãYò[òX⁄»⁄]ò]ÿ[àäBà€ìŸ º'Ê™»ôX\›\ûH⁄]ò]ÿ[õÿ⁄ŸYàÿ[]\ÿ€€õôX›YàôX€€õôX›»€€ù[ùYKàãùôX\›\ûHäBàô]\õàêì–“—Qàÿ[]Ÿ\ÿ€€õôX›YÇàBÇàYà
\ŸX›\ö]Kùô\öYûRŸ^\Z\í[ùY‹ö]Jÿ[]úXõX“Ÿ^PçNàŸô 
Kùÿ[]Yô\‹ÀöYêõ[ö»»ÿ[]úXõX“Ÿ^PçNJJH¬à€ìŸ º'Ê‰HŸ^\Z\à⁄X⁄»òZ[Y8†%⁄]ò]ÿ[Xõ‹ùYãùôX\›\ûHäBàô]\õàêì–“—QàŸ^\Z\àÇàBÇàô]\õàûH¬àò[⁄Y»Hÿ[]úŸ[ô€€
\›[ò][€êYô\‹À\õ›ôY
BàôX\›\ûSX[òYŸ\ãô^X›]U⁄]ò]ÿ[
\õ›ôY€€\›[ò][€êYô\‹ Bà€ìŸ ∏ß!HUëHëPT’TñH“UêU–Sà	ÿ\õ›ôYôõ]

_x•„à⁄YœI‹⁄YÀùZŸJMä_x†)àãùôX\›\ûHäBà€ìõ›YûJº'„ÈàôX\›\ûH⁄]ò]ÿ[ãàîŸ[ù	ÿ\õ›ôYôõ]

_x•„à8°§à	Ÿ\›[ò][€êYô\‹ÀùZŸJLä_x†)àãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì Bàì“Œâ⁄Y»ÇàHÿ]⁄
Nà^Ÿ\[€äH¬àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ KõY\‹ÿYŸHŒàù[ö€õ›€àäBà€ìŸ ïôX\›\ûH⁄]ò]ÿ[êRSQà	ÿYôHãùôX\›\ûHäBàëêRSQà	ÿYôHÇàBàBÇàù[àŸ[‹ú[ôY⁄Ÿ[äZ[ùà›ö[ôÀ]Nà›XõKÿ[]à€€[òUÿ[]
Nàõ€€X[à¬àÀ»çKéKçÕŒH8†%ôXY[ŸHúõ€Hù[ù[YS[ŸP]]‹ö]H
⁄[ô€H€›\òŸHŸàù]
KÇàYà
\‘\\îï

JH¬à€ìŸ º'ÈÓH‹ú[àŸ[⁄⁄\Y
\\à[ŸJNà	Z[ùãZ[ù
Bàô]\õàò[ŸBàBàò[»HŸô 
Bààô]\õàûH¬à€ìŸ º'ÈÓH][\[ô»‹ú[àŸ[à	Z[ù
	]H⁄Ÿ[ú HãZ[ù
BàYà
]HHåJH¬àûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jì‘îSó—T’“Q”ì‘ëQãõZ[ùI€Z[ùùZŸJL
_H]OI]HôX\€€èXô[›◊€Z[ó‹Ÿ[XõW›ô\⁄€äBà\[[ôRX[€€X›‹ãõXô[[ò ì‘îSó—T’“Q”ì‘ëQäBàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàùYBàBààò[Ÿ[[ö]»Hô\€€ôTŸ[[ö]—õ‹ìZ[ù
Z[ù]Kÿ[]Hÿ[]
Bàò[Ÿ[€\YŸHH
Àú€\YŸPú»
à Kò€Ÿ\òŸP][‹›
L
HÀ»çKéKåLŒà\ôÿ\IBÇàÀ»çKåçåNH8†%‘îSàì’UHUHëP”’ëTñKàôYô\àH]ôH⁄Ÿ[ÇàÀ»›‹òYŸHôX€‹ô€»Ÿ[õ›][ô»ÿ[à\ŸHHÿ[YH€›\òŸK‹€€\ŸY]àÀ»ù^K€‹[ãà€õHﬁ[ù\⁄^ôHHõ[ö»⁄[HYàHZ[ù\»ù[HXúŸ[ùÇàò[‹ú[ï»HûH»õ›Ÿ\ùöXŸKú›]\Àù⁄Ÿ[ú÷€Z[ùHHÿ]⁄
Œàõ›ÿXõJH»ù[BàŒà⁄Ÿ[î›]JZ[ùHZ[ùﬁ[Xõ€Hì‘îSãI€Z[ùùZŸJ
_HäBàò[‹ú[íŸ^HH]ôUòYSŸ‘›‹ôKöŸ^Qõ‹äZ[ùﬁ\›[Kò›\úô[ù[YSZ[\ 
JBàò[‹ú[î[\ö\ú›åNHH⁄›[ûT[\\ôX›ö\ú›åNJ‹ú[ïÀì‘îSãT’—QTäBàò[[\⁄Y»HYà
[‹ú[î[\ö\ú›åNJH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîST—TëP’‘““TQ–ïVW‘ì’UWÕåNHãàõZ[ùI€Z[ùùZŸJL
_HXô[S‘îSãT’—QTö[‹ö]OI‹Ÿ[õ›]Tö[‹ö]Qúõ€Pù^Tõ›]MåNJ‹ú[ï _HX›[€èZù\]\óŸö\ú›äHHÿ]⁄
Œàõ›ÿXõJHﬂBàù[àH[ŸHûT[\‹ù[Ÿ[
à»H‹ú[ïÀàÿ[]Hÿ[]à⁄Ÿ[ï[ö]»HôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»H‹ú[ïÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHîST‘ïS”‘îSó‘’—QTãàô\]Y\›YZT]HH]KàŸ[òYRŸ^HH‹ú[íŸ^KàòY\ïY»Hì‘îSàãà
OÀúò]–[[›[ùŒàô]\õàò[ŸKà€\›HKÀ»çKéKåMLç8†%IH]ôHŸ[ÿ\àö[‹ö]QôYT€€HåKà\ŸRö]»HÀöö]—[òXõYàö]’\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHùYJKàŸ[òYRŸ^HH‹ú[íŸ^KàòY\ïY»Hì‘îSàãàXô[Y»Hì‘îSãT’—QTãà
BÇàYà
[\⁄Y»OHù[
H¬à€ìŸ ∏ß!H‹ú[à€€öXH[\‹ù[à	Z[ù⁄YœI‹[\⁄YÀùZŸJMä_x†)àãZ[ù
Bà€ìõ›YûJº'ÈÓH‹ú[à€X[ù\ãàî€€Yù›ô\à⁄Ÿ[ú»öXH[\‹ù[ãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì BàùYBàH[ŸH¬ÇàÀ»ò[òX⁄Œàù\]\à[òH8°§àY]\»Y\é»úô\⁄[[›[ù[àõ‹à\»õÿŸ\‹€‹ãÇàò[‹ú[íù\]\î[àHôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»H‹ú[ïÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHíïTUTó’SêW”QUT◊”‘îSó‘’—QTãàô\]Y\›YZT]HH]KàŸ[òYRŸ^HH‹ú[íŸ^KàòY\ïY»Hì‘îSàãà
HŒàô]\õàò[ŸBàò[][›HHŸ]][›U⁄]€\YŸQ›X\ô
àZ[ùù\]\ê\Kî”””RSï‹ú[íù\]\î[ãúò]–[[›[ùŸ[€\YŸK\–ù^HHò[ŸJBàò[ô\›[HùZ[⁄]ô]ûJà][›Kÿ[]úXõX“Ÿ^PçNàŸ[ô\ï\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHò[ŸJKà
Bààò[\ŸRö]»HÀöö]—[òXõY	âà\][›Kö\’[òBàò[ö]’\HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHò[ŸJBàò[[òTô\RYHYà
][›Kö\’[òJHô\›[úô\]Y\›Y[ŸHù[ààò[⁄Y»HûH¬àÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJô\›[ùò\ŸMç\ŸRö]Àö]’\[òTô\RYÀöù\]\ê\RŸ^Kô\›[ö\‘ôúTõ›]Kô\›[úŸ[ô\ê€€\]XõJBàHÿ]⁄
ù\^à^Ÿ\[€äH¬àÀ»ö[ò[[\‹ù[ô]ûH]Y⁄\à€\Yàù\]\àYY€ÀÇàò[ô\ÿ›YRŸ^HH]ôUòYSŸ‘›‹ôKöŸ^Qõ‹äZ[ùﬁ\›[Kò›\úô[ù[YSZ[\ 
JBàûT[\‹ù[Ÿ[
à»H‹ú[ïÀàÿ[]Hÿ[]à⁄Ÿ[ï[ö]»HôXÿ[‘Ÿ[[ëõ‹îõÿŸ\‹€‹äà»H‹ú[ïÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHîST‘ïS”‘îSó‘ëT–’QHãàô\]Y\›YZT]HH]KàŸ[òYRŸ^HHô\ÿ›YRŸ^KàòY\ïY»Hì‘îSàãà
OÀúò]–[[›[ùŒàõ›»ù\^à€\›HKÀ»çKéKåMLç8†%IH]ôHŸ[ÿ\àö[‹ö]QôYT€€Håãà\ŸRö]»HÀöö]—[òXõYàö]’\[\‹ù»HYôôX›]ôRö]’\[\‹ù À\ôŸ[ùHùYJKàŸ[òYRŸ^HHô\ÿ›YRŸ^KàòY\ïY»Hì‘îSàãàXô[Y»Hì‘îSãTëT–’QHãà
HŒàõ›»ù\^àBàò[€€òX⁄»HYà
⁄YÀú›\ù’⁄]
îSï”W»äJHå[ŸH][›Kõ›][[›[ù»WÃÃÃåàà€ìŸ ∏ß!H‹ú[à€€à	Z[ù8°§à	‹€€òX⁄Àôõ]

_H””⁄YœI‹⁄YÀùZŸJMä_x†)àãZ[ù
Bà€ìõ›YûJº'ÈÓH‹ú[à€X[ù\ãàî€€Yù›ô\à⁄Ÿ[ú»8°§à	‹€€òX⁄Àôõ]

_H””ãà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKìõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëì BàùYBàBàHÿ]⁄
Nà^Ÿ\[€äH¬à€ìŸ ∏ßc‹ú[àŸ[òZ[Yõ‹à	Z[ùà	ŸKõY\‹ÿYŸ_HãZ[ù
Bàò[ŸBàBàBààÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dàÀ»çKçÀåŒàëU”‘í»“Q”êSUUÀPïVBàÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dàà äÇà
à]Y]YHHô]€‹ö»⁄Y€ò[]]ÀXù^Bà
àÿ[YûHô]€‹ö‘⁄Y€ò[]]–ù^Y\à⁄[àH⁄Y€ò[öYŸŸ\ú¬à
àà
à—Œàù[[\[Y[ù][€à[ô[ô»H›\úô[ùHô]\õú»ò[ŸH
\ÿXõY
Bà
ã¬àù[à]Y]YSô]€‹ö‘⁄Y€ò[ù^JàZ[ùà›ö[ôÀàﬁ[Xõ€à›ö[ôÀà⁄^ôT›à›XõKàôX\€€éà›ö[ôÀà\‘\\éàõ€€X[ãà
Nàõ€€X[à¬àò[»HŸô 
BÇàÀ»ÿYô]Nàõ‹òŸH\\à[ŸHYà\Ÿ\à\€â›‹Y[ù»]ôHù]⁄Y€ò[ô\]Y\›Y]ôBàÀ»çKéKçÕŒH8†%ôXY[ŸHúõ€Hù[ù[YS[ŸP]]‹ö]Hõ‹à⁄[ô€H€›\òŸKÇàYà
Z\‘\\à	âà\‘\\îï

JH¬à€ìŸ º'‰ËHô]€‹ö»⁄Y€ò[ù^HôZôX›Yà\\à[ŸH€õHãZ[ù
Bàô]\õàò[ŸBàBÇàÀ»çKéKçLH8†%ëPSSTSQSïUS”à
ÿ\»H›Xàô]\õö[ô»ò[ŸH⁄[òŸHçKçÀå KÇàÀ»€⁄‹»\H›\úô[ù⁄Ÿ[î›]Húõ€Hõ›Ÿ\ùöXŸI‹»ÿ]⁄\›€»ŸBàÀ»YŸﬁXòX⁄»€à]ô\ûH^\›[ô»\\êù^K€]ôKXù^HÿYô]HòZ[àÀ»
ÿ\\Xÿ]K[‹[à›X\ôõ›\õò[⁄^ö[ôÀ[ùK]ÿ\⁄]ÀäKÇàò[Œà⁄Ÿ[î›]O»HûH¬àò[»H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêõ›Ÿ\ùöXŸKú›]\¬àﬁ[ò⁄õ€ö^ôY
Àù⁄Ÿ[ú H»Àù⁄Ÿ[ú÷€Z[ùHBàHÿ]⁄
Œà^Ÿ\[€äH»ù[BÇàYà
»OHù[
H¬à€ìŸ º'‰ËHô]€‹ö»⁄Y€ò[ù^HôZôX›Yà	ﬁ[Xõ€õ›[àÿ]⁄\›Y]ãZ[ù
Bàô]\õàò[ŸBàBàYà
Àú‹⁄][€ãö\”‹[äH¬à€ìŸ º'‰ËHô]€‹ö»⁄Y€ò[ù^H⁄⁄\Yà	ﬁ[Xõ€[ôXYH‹[àãZ[ù
Bàô]\õàò[ŸBàBàYà
Àõ\›öXŸHHå
H¬à€ìŸ º'‰ËHô]€‹ö»⁄Y€ò[ù^H⁄⁄\Yà	ﬁ[Xõ€\»õ»öXŸHãZ[ù
Bàô]\õàò[ŸBàBÇàÀ»ô\€€ôHÿ[]ò[[òŸHõ‹à⁄^ö[ô»
\\à\Ÿ\»[öYöYYÿ[]
Bàò[ÿ[]€€HûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKêõ›Ÿ\ùöXŸKú›]\ÀôŸ]YôôX›]ôPò[[òŸJ\‘\\äBàHÿ]⁄
Œà^Ÿ\[€äH»åBàò[⁄^ôT€€H
ÿ[]€€
à
⁄^ôT›»Lå
JKò€Ÿ\òŸP]X\›
åJBàYà
⁄^ôT€€àÿ[]€€
H¬à€ìŸ º'‰ËHô]€‹ö»⁄Y€ò[ù^H⁄⁄\Yà[ú›YôöX⁄Y[ùò[[òŸH	›ÿ[]€€ôõ]
 _H	‹⁄^ôT€€ôõ]
 _HãZ[ù
Bàô]\õàò[ŸBàBÇàô]\õàûH¬àYà
\‘\\äH¬à\\êù^Jà»HÀ€€H⁄^ôT€€ÿ€‹ôHHååà^Y\ïY»HìëU”‘í◊‘“Q”êSãà^Y\ïY—[[⁄öHHº'‰ËHãà
BàH[ŸH¬àÀ»]ôHò[õ›Y⁄öXH›[ô\ô–ù^H]
[ô\»ÿ[]
»ÿYô]Hÿ]\ KÇà–ù^Jà»HÀ€€H⁄^ôT€€ÿ€‹ôHHååàÿ[]Hù[ÿ[]€€Hÿ[]€€à
BàBà€ìŸ º'‰ËHëU”‘í»“Q”êSUUÀPïVHVP’UQà	ﬁ[Xõ€⁄^ôOI‹⁄^ôT€€ôõ]
 _x•„à	ôX\€€àãZ[ù
Bà€ìõ›YûJàº'‰ËHô]€‹ö»⁄Y€ò[ãàâﬁ[Xõ€]]ÀXù^H	‹⁄^ôT€€ôõ]
 _x•„àãàõ›YöXÿ][€í\›‹ûKìõ›Yë[ùûKìõ›Yï\KíSëìÀà
BàùYBàHÿ]⁄
Nà^Ÿ\[€äH¬à\úõ‹ìŸŸŸ\ãô\úõ‹äë^X›]‹àãìô]€‹ö»⁄Y€ò[ù^HòZ[Yõ‹à	ﬁ[Xõ€à	ŸKõY\‹ÿYŸ_HãJBàò[ŸBàBàBÇàö]ò]Hù[à›XõKôõ]
à[ùHäHHâKâŸYàãôõ‹õX]
\ BÇàÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dàÀ»çKéKçMH8†%SíUëTî–SSTQíTî’STî¬àÀ¬àÀ»‹\ò]‹à\ôX›]ôH
ôXàåçäNàùŸHõ›»€õ›»⁄]€‹ö‹»YH[\ù[ÇàÀ»õ‹àH[ù\ôH€€ô]€‹ö»ò\⁄Xÿ[HHù\]\à[òH[àBàÀ»›\àÿ[òX⁄‹Àà[ù^Z[ô»[ôŸ[[ô»€€»[Ÿ\»òY\ú»[ôàÀ»›XàòY\ú»]\›ôH⁄X⁄ŸY[ò€Y[ô»H\ùX[ZŸHõŸö]»[ôàÀ»HY»‹⁄][€ú»[ôHX[ùX[ù^H[ôŸ[ù]€úÀàÇàÀ¬àÀ»[\‹ù[Y⁄ö[ô…‹»€€Hò]]»àõ›]\»ïVK‘—Sõ›Y⁄[\ôù[ÇàÀ»õ€ô[ô»›\ùôK[\›ÿ\SSKSëò^Y][H€€»8†%€›ô\ö[ô»[‹›àÀ»\]ZY‘⁄Ÿ[ú»€à€€[òKõ›ù\›[\\›Yôö^Z[ùÀÇàÀ¬àÀ»õ›][ô»‹ô\à
]ô\ûHÿ[⁄]H\Ÿ\»\»Y\äNÇàÀ»JHûT[\‹ù[Ÿ[»ûT[\‹ù[ù^H8°§[\‹ù[\ôX›
ò\›å‹ BàÀ»äHù\]\à[òH8°§àY]\»çàY\à8°§^\›[ô»ù\]\ê\H]àÀ» Hö[ò[[\‹ù[ò[òX⁄»8°§€õH€àŸ[ÀYù\àù\]\àY\ÇàÀ¬àÀ»[\ú»ô]\õàH⁄Y€ò]\ôH›ö[ô»€à›XÿŸ\‹À‹àù[€à[ûBàÀ»òZ[\ôH
ÕLô]€‹ö»õ\⁄Y€à\úõ‹äKàÿ[\ú»UT’àÀ»ò[õ›Y⁄»ù\]\à€àù[ÇàÀ»8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•d8•dÇà äÇà
àçKéKçMH8†%ûH[\‹ù[QíTî’Ÿ[àô]\õú»⁄Y»€à›XÿŸ\‹Àù[à
à€à[ûHòZ[\ôH
ÿ[\àò[»õ›Y⁄»ù\]\äKàŸ‹»¬à
à]ôUòYSŸ‘›‹ôH
»€ìŸ»⁄]H›\YYòY\ã⁄õ›\õò[Y‹ÀÇà
ã¬àö]ò]Hù[àûT[\‹ù[Ÿ[
àŒà⁄Ÿ[î›]Kàÿ[]à€€[òUÿ[]à⁄Ÿ[ï[ö]Œà€ôœÀà€\›à[ùàö[‹ö]QôYT€€à›XõKà\ŸRö]Œàõ€€X[ãàö]’\[\‹ùŒà€ôÀàŸ[òYRŸ^Nà›ö[ôÀàòY\ïYŒà›ö[ôÀàXô[YŒà›ö[ôÀà
Nà›ö[ôœ»¬àô]\õàûH¬àò[[\ô[ùYHHYà
€€KõYôXﬁX€Xõ›õô]€‹öÀî[\ù[ë\ôX›\Kö\‘[\ù[ìZ[ù
ÀõZ[ù
JBàú[\ôù[àà[ŸHù[ö]ô\úÿ[X]]»ÇÇàÀ»çKåçLH8†%ÿ]ôHHŸàŸ[YòZ[\ôH]⁄ÇàÀ»ëQì‘ëNàŸ^]€‹ôXõÿà\›][\YVUTëT–’QH»‘îSãT’—QTàÀ»»ëP”’ëTñH»ëPT’TñH[à⁄]TïPS‘ì—íU[ôàÀ»⁄⁄\Y[\‹ù[õ‹à[Ÿà[KÇàÀ»ì’»à›öX›^][ù[ù€\‹⁄YöY\à8†%€õHŸ[ùZ[ôBàÀ»TïPS‘ì—íU»êRSSë◊‘ì—íU»T’‘’—QTXô[¬àÀ»⁄⁄\[\‹ù[àù[^]Àô\ÿ›Y\À‹ú[à›ŸY\ÀàÀ»›‹[‹‹Ÿ\ÀùY»^]»SôXX⁄[\‹ù[€ò]]ôBàÀ»õ›]\»YÿZ[à
H‹\ò]‹â‹»ö[X\ûH€€\Z[ù
KÇàÀ»HMIK[Ÿã]ÿ[][\‹ù[úòX›[€à⁄X⁄»ô[›»ô[XZ[ú¬àÀ»HX›X[ÿYô]HYÿZ[ú›H‹öY⁄[ò[›ô\ãX€€ú›[\[€àùYÀàÀ»[ô\[ô[ùŸàXô[Çàò[ô\]Y\›YúòX›[€éà›XõHHûH¬àYà
⁄Ÿ[ï[ö]»OHù[	âà⁄Ÿ[ï[ö]»à
H¬àò[ô\öYöYYò]Œàò]òKõX]êöY“[ùYŸ\àHûH¬àò[ò[Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùBàò[ZHHò[Àôö\ú›Œàåàò[X»Hò[ÀúŸX€€ôŒàBàYà
ZHàå
Hò]òKõX]êöY—X⁄[X[
ZJKõ[›ôT⁄[ùöY⁄
X Kù–öY“[ùYŸ\ä
H[ŸHò]òKõX]êöY“[ùYŸ\ãñëTì¬àHÿ]⁄
Œàõ›ÿXõJH»ò]òKõX]êöY“[ùYŸ\ãñëTì»BàYà
ô\öYöYYò]Àú⁄Y€ù[J
Hà
H¬àò]òKõX]êöY—X⁄[X[
⁄Ÿ[ï[ö] Bàô]öYJò]òKõX]êöY—X⁄[X[
ô\öYöYYò] Kãò]òKõX]îõ›[ô[ô”[ŸKíSó’T
Bàù—›XõJ
BàH[ŸHKåàH[ŸHKåàHÿ]⁄
Œàõ›ÿXõJH»KåBÇàYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^][ù[ù€\‹⁄YöY\Çàú⁄›[⁄⁄\[\‹ù[õ‹î\ùX[õŸö]
Xô[YÀô\]Y\›YúòX›[€äJH¬àÀ»Ÿ[ùZ[ôHTïPS‘ì—íU»êRSSë◊‘ì—íU»T’8†%\›‹öXÿ[àÀ»‹\ò]‹à‹X»çKéKçM^çŒàõ›]Hù\]\à^X›Z[à€õH[ù[àÀ»[\‹ù[^X›X[[›[ùŸ[X[ùX‹»\ôHõ›ô[ãàù[^]¬àÀ»
ëT–’QH»ëP”’ëTñH»’—QT»’‘»ïQ Hû\\‹»\»⁄⁄\Çà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘ì’UW‘““TQàî[\‹ù[⁄⁄\Yõ‹à\ùX[‹õŸö]Xô[IXô[Y»
úòX›[€èI»âKåŸàãôõ‹õX]
ô\]Y\›YúòX›[€ä_JH8†%ù\]\ã”Y]\»^X›Z[ãàãàòY\ïY»HòY\ïYÀà
BàûH»\[[ôRX[€€X›‹ãõXô[[ò îST‘ïS‘TïPS‘ì’UW‘““TQ”ì’–USTQäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàù[àBàÀ»çKéKçM^ç»‹\ò]‹à‹X»][Hà8†%]ô[àõ‹àôù[^]àXô[ÀàÀ»\⁄Xÿ[Hô\öYûHHô\]Y\›YúòX›[€à\»èHMIHŸàBàÀ»⁄Z[ãX€€ôö\õYYÿ[]ò[[òŸHôYõ‹ôH[›⁄[ô»[\‹ù[ÇàÀ»çKéKçÕŒH8†%SQTë—SïQSQHì’UTàíVà⁄[àî»ô]\õú»[\BàÀ»‘à\»Z\‹⁄[ô»HZ[ùò[òX⁄»»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ÇàÀ»‘Tî—H]]‹ö]H[ú›XYŸà⁄⁄\[ô»HMIH⁄X⁄»
⁄X⁄àÀ»ô]ö[›\€Hÿ]\ŸY[\‹ù[»ò[õ›Y⁄»ù\]\ÇàÀ»^X›Z[à]ô[à⁄[à‹›òX⁄Ÿ\àY]]‹ö]]]ôH]JKÇàYà
⁄Ÿ[ï[ö]»OHù[	âà⁄Ÿ[ï[ö]»à
H¬àò[ô\öYöYYò]Œàò]òKõX]êöY“[ùYŸ\àHûH¬àò[ò[Hÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùBàò[ZHHò[Àôö\ú›Œàåàò[X»Hò[ÀúŸX€€ôŒàBàYà
ZHàå
Hò]òKõX]êöY—X⁄[X[
ZJKõ[›ôT⁄[ùöY⁄
X Kù–öY“[ùYŸ\ä
H[ŸHò]òKõX]êöY“[ùYŸ\ãñëTì¬àHÿ]⁄
Œàõ›ÿXõJH»ò]òKõX]êöY“[ùYŸ\ãñëTì»BàÀ»çKååÕÕ8†%õ»‹›òX⁄Ÿ\ã’‘Tî—Hò[òX⁄»õ‹à[\‹ù[[[›[ù]]‹ö]KÇàò[YôôX›]ôUô\öYöYYàò]òKõX]êöY“[ùYŸ\àHô\öYöYYò]¬àYà
ô\öYöYYò]Àú⁄Y€ù[J
HH
H¬àò[òX⁄ŸYHûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãôŸ][ùûJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJH»ù[BàYà
òX⁄ŸYÀú€›\òŸHOH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î€›\òŸKï‘Tî—JH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JêêSSê—W‘ì”—ó‘ëRëP’QãúôX\€€èQ—SëTíP◊’‘Tî—W”ì’”’”ëTó—íSTëQZ[ùI›ÀõZ[ùùZŸJL
_H⁄]O\[\‹ù[ŸúòX›[€óÿ⁄X⁄»äHHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàYà
YôôX›]ôUô\öYöYYú⁄Y€ù[J
Hà
H¬àò[ô\]Y\›YHò]òKõX]êöY“[ùYŸ\ãùò[YSŸä⁄Ÿ[ï[ö] Bàò[›[Y\ÃLHô\]Y\›Yõ][\Jò]òKõX]êöY“[ùYŸ\ãùò[YSŸäL
JBàô]öYJYôôX›]ôUô\öYöYY
BàYà
›[Y\ÃLò]òKõX]êöY“[ùYŸ\ãùò[YSŸäMJJH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘ì’UW‘““TQàî[\‹ù[⁄⁄\Yàô\]Y\›YI⁄Ÿ[ï[ö]»ô\öYöYYIYôôX›]ôUô\öYöYYà
¬àä	‹›[Y\ÃLIJHMIH8†%€€ù[ùYHù\]\à^X›Z[à€õKàãàòY\ïY»HòY\ïYÀà
BàûH»\[[ôRX[€€X›‹ãõXô[[ò îST‘ïS—îêP’S”ó‘ì’UW‘““TQ”ì’–USTQäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàù[àBàBàBÇàÀ»çKéKçMY8†%QTì‘ëSî“P‘»õ‹àŸ[Àà€ò\⁄›ÿ[]””
¬àÀ»⁄Ÿ[àò[[òŸHôKXúõÿYÿ\›€»H‹\ò]‹àÿ[àŸYHBàÀ»ôYõ‹ôKÿYù\à]HòYHÿ‹ôY[ãà\ﬁ[ò»‹›XúõÿYÿ\›ÿ]⁄\ÇàÀ»Ÿ‹»—S’ëTíQñW’“—Só—””ëH»—S’ëTíQñW‘””‘ëUTìëQ⁄[ÇàÀ»H⁄Z[àÿ]⁄\»\8†%\õú»⁄[[ùŸ[»[ù»Hö\⁄XõH]Y]àÀ»òZ[[ô]ÀY[ôÇàÀ»çKéKçM]8†%î»STKSPTUP’
‹\ò]‹àöXYŸHàX^HåçéÇàÀ»ô]ô\ûHù^H[ô»\»H[ù€Hà
»úŸ[»\X\à»õÿŸ\‹»ù]àÀ»⁄Ÿ[ú»\ôH›[[àÿ[]äKà[]\À’ö]€à›ô\õÿYô]\õú¬àÀ»[à[\HŸ]⁄Ÿ[êXÿ€›[ù–ûS›€ô\àX\»ôYõ‹ôH\»ö^BàÀ»ëKT—S\›⁄X⁄»ô[›»[ù\úô]Y]\»ôU⁄Ÿ[î]OLàÀ»[ôòZ[YSï”H8†%]ô[à›Y⁄Hÿ[][›\ÿ[ô»ŸÇàÀ»⁄Ÿ[úÀàŸHõ›»ôXYH“”HX\Ÿ\\ò][K]X›àÀ»[\[ô\‹À[ôòZ[‹õÿŸYYXÿ€‹ô[ô€KÇàò[ôPò[[òŸ\ŒàX\›ö[ôÀ€€KõYôXﬁX€Xõ›ô[ô⁄[ôKùù]êÿ[õ€öXÿ[⁄Ÿ[ê[[›[ùè»HûH¬àÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

BàHÿ]⁄
Œàõ›ÿXõJH»ù[Bàò[ôPò[[òŸ\—[\HHôPò[[òŸ\œÀö\—[\J
HOHùYBàò[ôUÿ[]€€à›XõHHûH»ÿ[]ôŸ]€€ò[[òŸJ
HHÿ]⁄
Œàõ›ÿXõJH»LKåBàò[ôU⁄Ÿ[î]Nà›XõHHôPò[[òŸ\œÀôŸ]
ÀõZ[ù
OÀôö\ú›ŒàLKåà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–êSSê—W–“P“Ààº'‰‚»ëKT—Sàÿ[]€€I»âKçàãôõ‹õX]
ôUÿ[]€€
_H⁄Ÿ[êò[I⁄Yà
ôU⁄Ÿ[î]H
Hîîœ»à[ŸHâKçàãôõ‹õX]
ôU⁄Ÿ[î]J_I⁄Yà
ôPò[[òŸ\—[\JHà8¶®îÀQSTKSPTà[ŸHàüHô[ùYOI[\ô[ùYHãàòY\ïY»HòY\ïYÀà
BÇàÀ»çKéKçMZ»8†%Sï”KT‘“US”à’PTë⁄]“QSëQ\›ô\⁄€ÇàÀ»çKéKçMYà\ŸYôU⁄Ÿ[î]H[àåãåYKNX⁄X⁄ÿ\»€»Y⁄ÇàÀ»Hÿ[]⁄]KôÀàYKMH⁄Ÿ[ú»
õ‹õX]Y\»ååà[àBàÀ»õ‹ô[ú⁄X‹»[JH€\Y\›H›X\ôöYY»Ÿ[BàÀ»ôX€‹ôYö[[€ã]⁄Ÿ[à‹⁄][€à⁄^ôK[ôù\õôYàô]öY\¬àÀ»õ‹àH
Õåççå…H[ù€HÿZ[à[àHòYHõ›\õò[à[û][ô¬àÀ»ô[›»åHRH⁄Ÿ[ú»\»\›8†%ôX]\»[ù€KÇàÀ»çKéKçM]8†%ù]”ìH⁄[àHî»X›X[Hô]\õôYHX\ÇàÀ»[à[\K[X\î»õ\\»ì’H€€ôö\õX][€àŸà\›»]	‹»[ÇàÀ»î»òZ[\ôKàYàHõ›\»⁄Ÿ[ï[ö]»úõ€HHô\öYöYYù^BàÀ»
ÿ[\àô\€€ôTŸ[[ö]»ù\›»HòX⁄Ÿ\äKõÿŸYY⁄]àÀ»úõÿYÿ\›8†%ù\]\ã‘[\‹ù[⁄[ôZôX›€X[õHYàBàÀ»ÿ[]ù[H\»[\KÇàYà
\ôPò[[òŸ\—[\H	âàôU⁄Ÿ[î]H[àåãååJH¬à€ìŸ º'‰n»Sï”H]X›Yà	›Àúﬁ[Xõ€Hõ›[ö‹»‹[àù]ÿ[]UHH	ôU⁄Ÿ[î]H
\›
H8†%⁄⁄\[ô»Ÿ[Y\àãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàº'‰n»Sï”Nàÿ[]UH\›
ôU⁄Ÿ[êò[I»âKçôàãôõ‹õX]
ôU⁄Ÿ[î]J_JH8†%ÿÿ[€‹ŸKõ»úõÿYÿ\›ãàòY\ïY»HòY\ïYÀà
BàÀ»ô]\õàHﬁ[ù]X»Ÿ[ù[ô[⁄Y»€»Hÿ[\àôX]»]àÀ»\»	‹€€	»[ôH‹⁄][€àôX€‹ô»€‹ŸHÿÿ[KàBàÀ»›ö[ô»›\ù»⁄]	‘Sï”W…»€»›€ú›ôX[H]Y]‹ú»ÿ[ÇàÀ»\›[ô›Z\⁄úõ€HôX[€ãX⁄Z[àŸ[»[àòYR\›‹ûT›‹ôBàÀ»[ôô\õ»›]H€€òX⁄»€€\]][€à
õ»ôX[ôX€›ô\ûJKÇàô]\õàîSï”W…‘ﬁ\›[Kò›\úô[ù[YSZ[\ 
Kù‘›ö[ô Mä_HÇàBàYà
ôPò[[òŸ\—[\JH¬àÀ»çKååÕÕ8†%[\‹ù[\»[à^X›][€àõÿŸ\‹€‹ãõ›ò[[òŸBàÀ»]]‹ö]Kà[\H›€ô\ã]⁄Ÿ[àî»YX[ú»êSSê—W’Sí”ì’”é»»õ›àÀ»\ŸH‹›òX⁄Ÿ\à»‘Tî—H»ÿ[\àÿX⁄H»õÿŸYYÇàò[òX⁄ŸYHûH»‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãôŸ][ùûJÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJH»ù[BàYà
òX⁄ŸYÀú€›\òŸHOH‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãî‹⁄][€î€›\òŸKï‘Tî—JH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JêêSSê—W‘ì”—ó‘ëRëP’QãúôX\€€èQ—SëTíP◊’‘Tî—W”ì’”’”ëTó—íSTëQZ[ùI›ÀõZ[ùùZŸJL
_H⁄]O\[\‹ù[‹ôXò[[òŸHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–êSSê—W–“P“ÀàîîÀQSTKSPT8°§àêSSê—W’Sí”ì’”à8†%[\‹ù[úõÿYÿ\›õÿ⁄ŸY»õ»›€ô\ãYö[\ôYò[[òŸHõ€ŸàãàòY\ïY»HòY\ïYÀà
BàÀ»çKååÕÕà8†%‹\ò]‹à‹X»][\»KãNàêSSê—W’Sí”ì’”à]BàÀ»[\‹ù[ôKXò[[òŸH⁄X⁄»]\›[ôHZ[ù¬àÀ»ò[[òŸTõ€Ÿî€\àöXHò[[òŸTõ€ŸïÿZ]›]KàH›]\ÇàÀ»ô\]Y\›Ÿ[‹ò\\àô[X\Ÿ\»H€‹ŸHX\ŸHõ‹ÇàÀ»–RUSë◊–êSSê—W‘ì”—é»\ôHŸHù\›X\ö»HÿZ]€»Hô^àÀ»^]X⁄»»Ÿ[ôX€€ò⁄[\àŸ\»ì’ôKXX‹]Z\ôHHõÿ⁄⁄[ô»X\ŸKÇàûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[êò[[òŸTõ€ŸïÿZ]›]KõX\ö’ÿZ][ô àÀõZ[ùÀúﬁ[Xõ€îST‘ïS–êSSê—W’Sí”ì’”àãàù[ù[YQŸ[ô\ò][€àHûH»õ›ù[ù[YP€€ùõ€\ãò›\úô[ùŸ[ô\ò][€ä
HHÿ]⁄
Œàõ›ÿXõJH»Kà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàù[àBÇà€ìŸ º'Ê†STQíTî’…Xô[YÀ…[\ô[ùYWNà	›Àúﬁ[Xõ€H8°§à[\‹ù[	‹€\›IH€\ãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘US’W’ñKàº'Ê†STQíTî’…Xô[Y◊H	‹€\›IH€\ö[‹ö]QôYOI‹ö[‹ö]QôYT€€x•„à\I⁄ö]’\[\‹ùﬂ[[HãàòY\ïY»HòY\ïYÀà
Bàò[Ÿ[X⁄[X[»HûH¬à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKí‹›ÿ[]⁄Ÿ[ïòX⁄Ÿ\ãôŸ][ùûJÀõZ[ù
OÀôX⁄[X[»ŒàÇàHÿ]⁄
Œàõ›ÿXõJH»àBàò[[Y\ôŸ[òﬁS›ô\úöYHH€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[ÿYô]T€XﬁBàö\”X[ùX[[Y\ôŸ[òﬁJXô[Y HXô[YÀò€€ùZ[ú îSíP◊—êRSàãY€õ‹ôPÿ\ŸHHùYJBàò[ùZ[H€€KõYôXﬁX€Xõ›õô]€‹öÀî[\ù[ë\ôX›\KòùZ[Ÿ[
àXõX“Ÿ^PçNHÿ[]úXõX“Ÿ^PçNàZ[ùHÀõZ[ùà⁄Ÿ[ê[[›[ùH⁄Ÿ[ï[ö]Àà€\YŸT\òŸ[ùH€\›àö[‹ö]QôYT€€Hö[‹ö]QôYT€€àX⁄[X[»HŸ[X⁄[X[Àà[›—[Y\ôŸ[òﬁS›ô\úöYHH[Y\ôŸ[òﬁS›ô\úöYKà
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’–ïRSàïùZ[õ›]\èTST—TëP’…Xô[Y◊H€\I‹€\›IH⁄^ôOI›⁄Ÿ[ï[ö]»ŒàêSüHãàòY\ïY»HòY\ïYÀà
BàÀ»çKéKçÕç»8†%ö]ôHŸ[õÿîôY⁄\›ûH›]HXX⁄[ôH[ô]ÀY[ô
[\]
KÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\ÀêïRSSë HHÿ]⁄
Œàõ›ÿXõJHﬂBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–îì–Q–T’àêúõÿYÿ\›[ô»STQíTî’…Xô[Y◊H	‹€\›IHõ›]OI⁄Yà
\ŸRö] HííU»à[ŸHîî»üHãàòY\ïY»HòY\ïYÀà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\Àêîì–Q–T’Së HHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKéKçå»8†%[\‹ù[Ÿ[]\›ÿZ]õ‹à€ãX⁄Z[à€€ôö\õX][€ãÇàÀ»Hò]»Ÿ[ôò[úÿX›[€à⁄Y€ò]\ôH€õHYX[ú»îÀ“ö]»XÿŸ\YBàÀ»X⁄Ÿ]»]ÿ[à›[^\ôKŸòZ[ôYõ‹ôH[ô[ôÀàÿ[]€[ô¬àÀ»ô[›»ô[XZ[ú»H]]‹ö]Hõ‹à⁄Ÿ[ãY€€ôK‘””\ô]\õôYõ€ŸãÇàò[⁄Y»Hÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJùZ[ùò\ŸMç\ŸRö]ÀX^Ÿäö]’\[\‹ùÀåÃ
KŸ[ô\ê€€\]XõHHò[ŸJBàYà
⁄YÀö\–õ[ö 
JH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàîSTQíTî’…Xô[Y◊Nàõ[ö»€€ôö\õYY⁄Y€ò]\ôH8†%ò[[ô»òX⁄»»ù\]\àãàòY\ïY»HòY\ïYÀà
Bàô]\õàù[àBà€ìŸ ∏ß!HSTQíTî’…Xô[Y◊H—S””ëíTìQQà⁄YœI‹⁄YÀùZŸJå
_x†)à
ô\öYûZ[ô»ÿ[]Ÿ][Y[ù
HãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S–””ëíTìQQà∏ß!H€€ôö\õYYöXH[\‹ù[…Xô[Y◊H	‹€\›IH€\8†%ÿ[]Ÿ][Y[ùô\öYûHÿ⁄Y[Yãà⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
BàÀ»çKéKçÕç»8†%ö]ôHŸ[õÿîôY⁄\›ûH›]HXX⁄[ôH[ô]ÀY[ô
[\]
KÇàÀ»ö[ò[SëQò[ú⁄][€à\»\‹Ÿ\ùYûHHôX€€ò⁄[\à⁄[à€ãX⁄Z[ÇàÀ»ò[[òŸHôXX⁄\»ô\õ»
Hô»ô\öYûH][ò⁄\»ô[›Œ»ŸHÿ[õõ›àÀ»ﬁ[ò⁄õ€õ›\€HX\ö”[ôYúõ€H\»]
KÇàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿîôY⁄\›ûKùò[ú⁄][€ï ÀõZ[ù€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õÿî›]\Àê””ëíTìRSë HHÿ]⁄
Œàõ›ÿXõJHﬂBÇàÀ»çKéKçMY8†%êP“—‘ì’Së—SëTíQñKà€»ÿ[]õ‹à⁄Ÿ[ÇàÀ»\ÿ\X\ò[òŸH
»””ô]\õãŸ‹»õ›»Hõ‹ô[ú⁄X‹»[KÇàÀ»\»XZŸ\»ôYHŸ[X›X[H[ô»àHö\⁄XõH[ú›Ÿ\ÇàÀ»[ú›XYŸàH⁄[[ù[ôô\ô[òŸKàòZ[\ôH»ô\öYûH⁄][ÇàÀ»ç\»[Z]»H—S‘’P“»ÿ\õö[ô»]H‹\ò]‹à⁄[ŸYKÇàûH¬à€›[ûò€‹õ›][ô\Àë€ÿò[ÿ€‹Kõ][ò⁄
\\‹]⁄\úÀú⁄YQYôôX›
H¬àò\àŸY[ï⁄Ÿ[ë€€ôHHò[ŸBàò\àŸY[î€€ô]\õôYHò[ŸBàò[€€ù[\ô\⁄€HåHÀ»Y€õ‹ôHõ⁄\ŸHúõ€Hô[ùŸôY\»õX›X][€ú¬àò[XY[ôS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
H
»WÃàò\à€Hà⁄[H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HXY[ôS\»	âà
\ŸY[ï⁄Ÿ[ë€€ôH\ŸY[î€€ô]\õôY
JH¬à€›[ûò€‹õ›][ô\Àô[^J◊Ã
Bà€
 ¬àò[›\î€€HûH»ÿ[]ôŸ]€€ò[[òŸJ
HHÿ]⁄
Œàõ›ÿXõJH»LKåBàò[›\ï⁄»HûH¬àÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùOÀôö\ú›ŒàåàHÿ]⁄
Œàõ›ÿXõJH»LKåBàYà
\ŸY[ï⁄Ÿ[ë€€ôH	âàôU⁄Ÿ[î]Hàå	âà›\ï⁄»[àåãäôU⁄Ÿ[î]H
àåJJH¬àŸY[ï⁄Ÿ[ë€€ôHHùYBà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW’“—Só—””ëKà∏ß!H⁄Ÿ[à€X\ôYúõ€Hÿ[]à	»âKçàãôõ‹õX]
ôU⁄Ÿ[î]J_H8°§à	»âKçàãôõ‹õX]
›\ï⁄ _H
€…€
Hãà⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
Bà€ìŸ ∏ß!HST…Xô[Y◊H⁄Ÿ[à€X\ôY€ãX⁄Z[à
€…€
HãÀõZ[ù
BàBàYà
\ŸY[î€€ô]\õôY	âàôUÿ[]€€èHå	âà›\î€€èHå	âà
›\î€€HôUÿ[]€€
Hà€€ù[\ô\⁄€
H¬àŸY[î€€ô]\õôYHùYBàò[ÿZ[àH›\î€€HôUÿ[]€€à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW‘””‘ëUTìëQà∏ß!H””ô]\õôYà	»âKçàãôõ‹õX]
ôUÿ[]€€
_H8°§à	»âKçàãôõ‹õX]
›\î€€
_H
3•
…»âKçàãôõ‹õX]
ÿZ[ä_x•„ã€…€
Hãà⁄Y»H⁄YÀ€€[[›[ùHÿZ[ãòY\ïY»HòY\ïYÀà
Bà€ìŸ ∏ß!HST…Xô[Y◊H””ô]\õôY
…»âKçàãôõ‹õX]
ÿZ[ä_x•„à
€…€
HãÀõZ[ù
BàBàBàYà
\ŸY[ï⁄Ÿ[ë€€ôH	âà\ŸY[î€€ô]\õôY
H¬àÀ»çKéKçM^ç8†%‹\ò]‹à‹XŒà—S‘’P“»]\›ì’ôBàÀ»[Z]YYàHòYH\»[ôXYHôY[à]]‹ö]]]ô[BàÀ»ô\€€ôYûH\\úŸH
òYUô\öYöY\à]
H€àHÿ[YBàÀ»⁄Y»»òYRŸ^KàH[€õ›€öX»›X\ô[ú⁄YBàÀ»]ôUòYSŸ‘›‹ôH⁄[[€»›\ô\‹»\»Yà]€\¬àÀ»õ›Y⁄ù]⁄‹ùX⁄\ò›Z][ô»\ôHÿ]ô\»Hÿ\›YàÀ»€€‹[ôŸY\»õ‹ô[ú⁄X‹»]ZY]€àôX[›XÿŸ\‹Ÿ\ÀÇàYà
]ôUòYSŸ‘›‹ôKö\’\õZ[ò[Tô\€€ôY
Ÿ[òYRŸ^K⁄Y JH¬à\úõ‹ìŸŸŸ\ãôXùY àë^X›]‹àãàîSTQíTî’…Xô[Y◊H\»ÿ]⁄ŸŒàòYH[ôXYH\õZ[ò[Hô\€€ôY
⁄YœI‹⁄YÀùZŸJMä_JH8†%⁄⁄\[ô»—S‘’P“»Çà
BàH[ŸH¬à]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀà∏£Ï»\»‹›XúõÿYÿ\›àõ»€ãX⁄Z[à€€ôö\õX][€àY]8†%ôY⁄\›\ö[ô»õ‹àãÕKÃLZ[àôX€€ò⁄[H⁄YœI‹⁄YÀùZŸJMä_Hãà⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
BàûH¬à[ô[ô‘ôX€€ò⁄[T]Y]YKúôY⁄\›\îŸ[
à⁄Y»H⁄YÀàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àòYRŸ^HHŸ[òYRŸ^KàòY\ïY»HòY\ïYÀàÿ[]Hÿ[]à
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàBàHÿ]⁄
Œà^Ÿ\[€äHﬂBà⁄Y¬àHÿ]⁄
òYô\Nà€€KõYôXﬁX€Xõ›õô]€‹öÀî[\Ÿ[òYô\]Y\›
H¬àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ òYô\KõY\‹ÿYŸHŒàòòY‹ô\]Y\›äBàYà
òYô\Kö€ŸHèHLòYô\Kö€ŸHOHNNJH¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[úôX€‹ô[\õ›öY\ëòZ[\ôJí…ÿòYô\Kö€Ÿ_NâÿYôHäHHÿ]⁄
Œàõ›ÿXõJHﬂBàBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àö[ò à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àî—S‘ì’UW‘ëPïRS–QïTóÕàõZ[ùI›ÀõZ[ùùZŸJL
_HIÿòYô\Kö€Ÿ_HXô[IXô[Y»8°§àù\]\àòZ[›ô\àäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àö[ò à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àî—S—êRS’ëTó“SSQQPUKàõZ[ùI›ÀõZ[ùùZŸJL
_Húõ€OTST—TëP’ôX\€€èR…ÿòYô\Kö€Ÿ_HäBà€ìŸ º'Â HSTQíTî’…Xô[Y◊H	ÿòYô\Kö€Ÿ_H
òY^[ÿY
H8†%ôXùZ[öXHù\]\à
õ»ô\]Y]YJHãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘ì’UW—êRSQ”ì◊‘“Q”êUTëKàîSTQíTî’…Xô[Y◊H	ÿòYô\Kö€Ÿ_HòY^[ÿY8†%ô[ùYHòZ[›ô\éà	‹ÿYôKùZŸJN
_HãàòY\ïY»HòY\ïYÀà
Bàù[àHÿ]⁄
[ùò[Yà€€KõYôXﬁX€Xõ›õô]€‹öÀî[\Ÿ[^[ÿY[ùò[Y
H¬àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ [ùò[YõY\‹ÿYŸHŒàö[ùò[Y‹^[ÿYäBà€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àö[ò à€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[îŸ[õ‹ô[ú⁄X‹Àî—S—êRS’ëTó“SSQQPUKàõZ[ùI›ÀõZ[ùùZŸJL
_Húõ€OTST—TëP’ôX\€€èTVS–Q“SïêSQäBà€ìŸ º'Â HSTQíTî’…Xô[Y◊H^[ÿY[ùò[Y8†%ôXùZ[öXHù\]\à
õ»ô\]Y]YJNà	‹ÿYôKùZŸJM
_HãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S‘ì’UW—êRSQ”ì◊‘“Q”êUTëKàîSTQíTî’…Xô[Y◊H^[ÿY[ùò[Y8†%ô[ùYHòZ[›ô\éà	‹ÿYôKùZŸJN
_HãàòY\ïY»HòY\ïYÀà
Bàù[àHÿ]⁄
[\^à^Ÿ\[€äH¬àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ [\^õY\‹ÿYŸHŒàù[ö€õ›€àäBàYà
€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[ö\‘õ›öY\ê€\‹—òZ[\ôJ[\^õY\‹ÿYŸJJH¬àûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ë^]õ›öY\íX[úôX€‹ô[\õ›öY\ëòZ[\ôJÿYôJHHÿ]⁄
Œàõ›ÿXõJHﬂBàBà€ìŸ ∏¶®;Ó#»STQíTî’…Xô[Y◊HòZ[Y
	‹ÿYôKùZŸJN
_JH8†%ò[[ô»õ›Y⁄»ù\]\à[òHãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àŸ[òYRŸ^KÀõZ[ùÀúﬁ[Xõ€î—Sãà]ôUòYSŸ‘›‹ôKî\ŸKî—S—êRSQàîSTQíTî’…Xô[Y◊HòZ[Yà	‹ÿYôKùZŸJç
_H8†%ò[[ô»òX⁄»»ù\]\à[òHãàòY\ïY»HòY\ïYÀà
Bàù[àBàBÇà äÇà
àçKéKçMH8†%ûH[\‹ù[QíTî’ù^Kàô]\õú»Z\ä⁄YÀ]U⁄Ÿ[ïZJBà
à€à›XÿŸ\‹Àù[€à[ûHïQHòZ[\ôH
ÿ[\àò[»õ›Y⁄¬à
àù\]\à[òJKàŸ‹»»]ôUòYSŸ‘›‹ôH[ô€ìŸÀÇà
Çà
àçKéKçåà8†%»ì’ïT’’PìRT‘“S”àS”ëKàYà⁄Y€ê[ôŸ[ôô]\õú»Hõ€ãXõ[ö¬à
à⁄YÀHÿ\»€õHXÿŸ\YûHö]À‘î»õ‹à[ò€\⁄[€ãàX\õY\àô\ú⁄[€ú¬à
à€YHÿ[]õ‹àH⁄Ÿ[ãXò[[òŸH[HKé»Yù\àúõÿYÿ\›[ôà
àô]\õôYù[€à[H8†%ù]Ÿ]⁄Ÿ[êXÿ€›[ù–ûS›€ô\òY‹»L8†$ÃÃ¬à
àôZ[ô€à€€[[Ÿ]Hî‹À€»HôX[›XÿŸ\‹Ÿù[ù^H€›[ò[Ÿ[Bà
àôòZ[à[ôHÿ[\à€›[ò[õ›Y⁄»ù\]\ã›XõK\‹[ô[ô¬à
àH””à‹\ò]‹àõ‹ô[ú⁄X‹»
àX^Håçàÿ‹ôY[ú⁄›SX\]8†)úî‹[\
NÇà
à[\‹ù[[ôY⁄Y»÷ï‹ûVùÕ[x†)àù]õ›ô[òX⁄»»ù\]\ãà
à⁄X⁄[à\úõ‹ôYí[ú›YôöX⁄Y[ùù[ô»àôXÿ]\ŸHH””Y[ôXYBà
àYùHÿ[]àõ›»ŸH\›[X]H]Húõ€HH‹[ù””0Ì»]ôHöXŸBà
à[ôôX]Hù^H\»⁄Z[ãX€€ôö\õYY€õHYù\à⁄Y€îŸ[ô[ô€€ôö\õKÇà
àH€€[[€à[ô[ô’ô\öYûHÿYôY›X\ô»›€ú›ôX[H\ôH›[H]]‹ö]Bà
àõ‹à⁄]\à⁄Ÿ[ú»X›X[H[ôY[àHÿ[]Çà
ã¬àö]ò]Hù[àûT[\‹ù[ù^JàŒà⁄Ÿ[î›]Kàÿ[]à€€[òUÿ[]à€€[[›[ùà›XõKà€\›à[ùàö[‹ö]QôYT€€à›XõKà\ŸRö]Œàõ€€X[ãàö]’\[\‹ùŒà€ôÀàòYRŸ^Nà›ö[ôÀàòY\ïYŒà›ö[ôÀà
NàZ\è›ö[ôÀ›XõOè»¬àô]\õàûH¬àò[[\ô[ùYHHYà
€€KõYôXﬁX€Xõ›õô]€‹öÀî[\ù[ë\ôX›\Kö\‘[\ù[ìZ[ù
ÀõZ[ù
JBàú[\ôù[àà[ŸHù[ö]ô\úÿ[X]]»ÇÇàÀ»çKéKçMY8†%QTì‘ëSî“P‘»õ‹àù^\Àà€ò\⁄›ÿ[]””
¬àÀ»^\›[ô»⁄Ÿ[àò[[òŸHôKXúõÿYÿ\›€»H‹\ò]‹àÿ[àŸYBàÀ»HôYõ‹ôKÿYù\à]HòYHÿ‹ôY[ãà\ﬁ[ò»‹›XúõÿYÿ\›àÀ»ÿ]⁄\àŸ‹»ïVW’ëTíQíQQ”SëQ⁄[àH⁄Z[àÿ]⁄\»\Çàò[ôUÿ[]€€à›XõHHûH»ÿ[]ôŸ]€€ò[[òŸJ
HHÿ]⁄
Œàõ›ÿXõJH»LKåBàò[ôU⁄Ÿ[î]Nà›XõHHûH¬àÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùOÀôö\ú›ŒàåàHÿ]⁄
Œàõ›ÿXõJH»åBà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW‘US’W’ñKàº'‰‚»ëKPïVNàÿ[]€€I»âKçàãôõ‹õX]
ôUÿ[]€€
_HôU⁄Ÿ[êò[I»âKçàãôõ‹õX]
ôU⁄Ÿ[î]J_Hô[ùYOI[\ô[ùYHãà€€[[›[ùH€€[[›[ùòY\ïY»HòY\ïYÀà
BÇàÀ»çKéKçÕLH8†%ò\ŸMX⁄Ÿ]][HÕŒà–SU–êSSê—W’Sí”ì’”àXõ‹ùÇàÀ»LKå\»HŸ[ù[ô[õ‹àôŸ]€€ò[[òŸHô]»à8†%î»[úôXX⁄XõBàÀ»‹àô]\õôY[ùò[YTHŸ^KàùZ[[ôÀÿúõÿYÿ\›[ô»YÿZ[ú›[ÇàÀ»[ö€õ›€àÿ[]›]H\»[úÿYôNàŸH€›[›ô\ú⁄^ôHHù^H[ôàÀ»]ôH]òZ[›€ú›ôX[KX]ö[ô»[ù€HST—TëP’\ùYòX›ÀÇàÀ»€X[õHò[õ›Y⁄
ô]\õàù[
H€»H›]\àÿ[\àò[¬àÀ»òX⁄»»ù\]\à
⁄X⁄\»]»›€àò[[òŸHÿ]\ KÇàYà
ôUÿ[]€€å
H¬à]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW—êRSQàï–SU–êSSê—W’Sí”ì’”à8†%î»ô]\õôYõ»””ò[[òŸH
LKåŸ[ù[ô[
KàôYù\⁄[ô»»ùZ[ST—TëP’YÿZ[ú›[ö€õ›€à›]KàãàòY\ïY»HòY\ïYÀà
Bà\úõ‹ìŸŸŸ\ãùÿ\õäë^X›]‹àãàñ—VP’US”ã’–SU–êSSê—W’Sí”ì’”óH	›Àúﬁ[Xõ€NàŸ]€€ò[[òŸHô]\õôYLHŸ[ù[ô[Xõ‹ù[ô»STQíTî’äBàô]\õàù[àBàò[õÿŸ\‹€‹î[àHôXÿ[–ù^T[ëõ‹îõÿŸ\‹€‹äà»HÀàÿ[]Hÿ[]àõÿŸ\‹€‹àHîST‘ïS–ïVW“SïTìêSãàô\]Y\›Y€€H€€[[›[ùàö[‹ö]QôYT€€Hö[‹ö]QôYT€€àö]’\[\‹ù»Hö]’\[\‹ùÀàòYRŸ^HHòYRŸ^KàòY\ïY»HòY\ïYÀà
HŒàô]\õàù[àò[ù^T€€HõÿŸ\‹€‹î[ãú€€[[›[ùÇà€ìŸ º'Ê†STQíTî’ïVH…[\ô[ùYWNà	›Àúﬁ[Xõ€H8°§à[\‹ù[	»âKçàãôõ‹õX]
ù^T€€
_x•„à	‹€\›IH€\ãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW‘US’W’ñKàº'Ê†STQíTî’ïVH…[\ô[ùYWH	»âKçàãôõ‹õX]
ù^T€€
_x•„à	‹€\›IHö[‹ö]QôYOI‹ö[‹ö]QôYT€€x•„à\I⁄ö]’\[\‹ùﬂ[[Hãà€€[[›[ùHù^T€€òY\ïY»HòY\ïYÀà
BàYà
^X›][€ë[ô⁄[ùX[ö\—\ÿXõY
îST—TëP’–ïRSãÀõZ[ù
H€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ìY[YUô[ùYTõ›]\ãö\‘[\õ›]R[ùò[Y
ÀõZ[ù
JH¬àûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîST—TëP’‘““TQ‘ëTëT””ëHãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HôX\€€èY[ô⁄[ù€‹ó€Z[ù‹õ›]WŸ\ÿXõYäHHÿ]⁄
Œàõ›ÿXõJHﬂBàô]\õàù[àBàò[ùZ[H€€KõYôXﬁX€Xõ›õô]€‹öÀî[\ù[ë\ôX›\KòùZ[ù^U
àXõX“Ÿ^PçNHÿ[]úXõX“Ÿ^PçNàZ[ùHÀõZ[ùà€€[[›[ùHù^T€€à€\YŸT\òŸ[ùH€\›àö[‹ö]QôYT€€Hö[‹ö]QôYT€€à
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW’–ïRSàïùZ[õ›]\èTST—TëP’€\I‹€\›IHû]\œIÿùZ[ùò\ŸMçõ[ô›HãàòY\ïY»HòY\ïYÀà
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW–îì–Q–T’àêúõÿYÿ\›[ô»STQíTî’ïVH	‹€\›IHõ›]OI⁄Yà
\ŸRö] HííU»à[ŸHîî»üHãàòY\ïY»HòY\ïYÀà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKëõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JêïVW–îì–Q–T’ãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€Hõ›]OTST—íTî’€€Iù^T€€€\I€\›äHHÿ]⁄
Œàõ›ÿXõJHﬂBàÀ»çKéKçåà8†%[\Yö\ú›]\›ì’ôX]H›XõZ]Y⁄Y€ò]\ôH\¬àÀ»H€€\]Yù^KàŸ[ôò[úÿX›[€ã“ö]»XÿŸ\[òŸHÿ[à›[^\ôBàÀ»‹àòZ[ôYõ‹ôH[ô[ôÀ⁄X⁄‹ôX]Y⁄‹›ö[à‹⁄][€ú»⁄]àÀ»õ»ÿ[]⁄Ÿ[úÀà\ŸHH€€ôö\õYY]ù\›ZŸHù\]\ãÇàò[⁄Y»Hÿ[]ú⁄Y€îŸ[ô[ô€€ôö\õJùZ[ùò\ŸMç\ŸRö]ÀX^Ÿäö]’\[\‹ùÀåÃ
KŸ[ô\ê€€\]XõHHò[ŸJBàÀ»ÿ[ö]Nà⁄Y€îŸ[ô[ô€€ôö\õHõ›‹»€àîÀ€€ãX⁄Z[àòZ[\ôKù]Yô[ú⁄]ô[BàÀ»ô\öYûHHô]\õôY⁄Y»\»õ€ãXõ[ö»ôYõ‹ôHù\›[ô»]ÇàYà
⁄YÀö\–õ[ö 
JH¬à€ìŸ ∏¶®;Ó#»STQíTî’ïVNàõ[ö»€€ôö\õYY⁄Y»8†%ò[[ô»õ›Y⁄»ù\]\àãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW—êRSQàîSTQíTî’àõ[ö»⁄Y€ò]\ôH8†%ò[[ô»òX⁄»»ù\]\àãàòY\ïY»HòY\ïYÀà
Bàô]\õàù[àBà€ìŸ ∏ß!HSTQíTî’ïVH””ëíTìQQà⁄YœI‹⁄YÀùZŸJå
_x†)à
ô\öYûZ[ô»⁄Ÿ[à\úö]ò[
HãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW–””ëíTìQQà∏ß!H€€ôö\õYY€ãX⁄Z[à8†%]ÿZ][ô»⁄Ÿ[ãX\úö]ò[ô\öYöXÿ][€àãà⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
BàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKëõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JêïVW–””ëíTìQQãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H⁄Y€ò]\ôOI‹⁄YÀùZŸJMä_Hõ›]OTST—íTî’äHHÿ]⁄
Œàõ›ÿXõJHﬂBÇàÀ»çKéKçM[8†%íSPTñH]H€›\òŸHH–SUêSêP“»HöXŸHX]ÇàÀ»‹\ò]‹à
àX^HåçäNàú[ù€Hõ›Y⁄ù]^H\ôH[àBàÀ»ÿ[][à€€ö[ôHù]ÿ[YH\‹›YHŸZ\ôöXŸHÿ[›[][€ÇàÀ»\‹^YY[àõ›\õò[ãà\›[X][ô»]Húõ€H€€[[›[ù0Â»€€àÀ»0Ì»Ÿ]X›X[öXŸXÿ\»][ô»›[Kﬁô\õÀ›‹õ€ô»öXŸ\ÀòZ⁄[ô¬àÀ»H⁄[H‹õ€ô»]H[ù»H‹⁄][€àôX€‹ôàôX[\ŸYÿZ[ú¬àÀ»[à⁄›ŸY
Õåå	H€àŸ[»ôXÿ]\ŸH]Hÿ\»[ôõ]YL0ÂÀÇàÀ¬àÀ»ô]»‹ô\éÇàÀ»KàôXYÿ[]⁄Ÿ[àò[[òŸH[[YYX][H
ô\›YYôõ‹ùàÀ»î»X^Hõ›]ôH[ô^YY]8†%]	‹»ö[ôJKÇàÀ»ãàYàÿ[]⁄›‹»Hô]»⁄Ÿ[ú»8°§à\ŸH][H\»]KÇàÀ»Àà[ŸHò[òX⁄»»öXŸHX]⁄X⁄»ŸôàHÿ]⁄Ÿ¬àÀ»
⁄X⁄ôX€€ò⁄[\»\ﬁ[ò⁄õ€õ›\€Hô[› KÇàÀ¬àÀ»çKéKçM\à8†%‹\ò]‹éàõY[YHòY\à\»^ô[Y[H]ZY][ÇàÀ»]ôH[ŸH€õHHòYH‹[àãàHKç\»ôXYú€Y\€ÇàÀ»]ô\ûHù^Hÿ\»Ÿ\öX[^ö[ô»Hõ›	‹»€‹õ›][ôH8†%]LàÀ»ù^\À€Z[à]	‹»çIHŸàH€‹ÿ\›Y[à€Y\àô[[›ôY¬àÀ»Hÿ]⁄Ÿ»
ô[› H[ô\»HîÀ[Y»ÿ\ŸH[û]ÿ^KÇàò[ö\ú›ôXY]Nà›XõHHûH¬àò[›\àHÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùOÀôö\ú›Œàåà
›\àHôU⁄Ÿ[î]JKò€Ÿ\òŸP]X\›
å
BàHÿ]⁄
Œàõ›ÿXõJH»åBÇàò[öXŸHHŸ]X›X[öXŸJ KùZŸRYà»]àåHŒàÀú‹⁄][€ãô[ùûTöXŸKùZŸRYà»]àåBàò[€€öXŸU\ŸHÿ[]X[òYŸ\ãõ\›€õ›€î€€öXŸBàò[öXŸSX]]HHYà
öXŸHOHù[	âàöXŸHàå	âà€€öXŸU\Ÿàå
H¬à
ù^T€€
à€€öXŸU\Ÿ
H»öXŸBàH[ŸH¬àÀ»[ö€õ›€à]X[ù]Hô[XZ[ú»[ô[ô»[ù[ÿ[]›õ€ŸãÇàÀ»H€ôK]⁄Ÿ[àXŸZ€\à€‹úù\»€‹›ò\⁄\»[ôìÇàåàBÇàÀ»ù\›Hÿ[]ôXYYà]ô]\õôYHõ€ã]ö]öX[[BàÀ»
8¢iLIHŸàöXŸK[X]\›[X]JH8†%]	‹»›\à‹õ›[ôù]Çàò[\›[X]Y]HHYà
ö\ú›ôXY]HàöXŸSX]]H
àåH	âàö\ú›ôXY]Hàå
H¬à]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW’ëTíQñW‘”àº'Â#Hÿ[]‹õ›[ôù]à]OI»âKçàãôõ‹õX]
ö\ú›ôXY]J_H
öXŸK[X]\›[X]H€›[]ôHôY[à	»âKçàãôõ‹õX]
öXŸSX]]J_JHãà⁄Ÿ[ê[[›[ùHö\ú›ôXY]K⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
Bàö\ú›ôXY]BàH[ŸH¬à]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW’ëTíQñW‘”àº'Â#H\›[X]Y]OI»âKçàãôõ‹õX]
öXŸSX]]J_HöXŸOI‹öXŸOÀõ]»âKéàãôõ‹õX]
]
HHŒàìã–HüHÿ[]ôXYô]\õôY	»âKçàãôõ‹õX]
ö\ú›ôXY]J_H8†%\⁄[ô»öXŸHX]ÿ]⁄Ÿ»⁄[ôX€€ò⁄[Hãà⁄Ÿ[ê[[›[ùHöXŸSX]]K⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
BàöXŸSX]]BàBÇàÀ»çKéKçMY8†%êP“—‘ì’SëïVHëTíQñHÿ]⁄ŸÀà€»ÿ[]àÀ»⁄Ÿ[àò[[òŸH]ô\ûH‹»\»\ÀŸ‹»SëQ⁄[àHô]¬àÀ»òY»⁄›‹»\ôX€€ò⁄[\»Àú‹⁄][€ãú]U⁄Ÿ[à»HX›X[àÀ»€ãX⁄Z[à]HYà]Yôô\ú»úõ€H\›[X]HûHçIKàŸ‹¬àÀ»ïVW‘Sï”HYàH⁄Z[àô]ô\à[ô^\»Hù^H
ÿ]\›õ‹X¬àÀ»î»òZ[‹àô]ô\ù‹›XúõÿYÿ\›
KÇàûH¬à€›[ûò€‹õ›][ô\Àë€ÿò[ÿ€‹Kõ][ò⁄
\\‹]⁄\úÀú⁄YQYôôX›
H¬àò[XY[ôS\»Hﬁ\›[Kò›\úô[ù[YSZ[\ 
H
»WÃàò\à€Hàò\à[ôYHò[ŸBà⁄[H
ﬁ\›[Kò›\úô[ù[YSZ[\ 
HXY[ôS\»	âà[[ôY
H¬à€›[ûò€‹õ›][ô\Àô[^J◊Ã
Bà€
 ¬àò[›\ï⁄»HûH¬àÿ[]ôŸ]⁄Ÿ[êXÿ€›[ù’⁄]X⁄[X[–õ›[ôY

V›ÀõZ[ùOÀôö\ú›ŒàåàHÿ]⁄
Œàõ›ÿXõJH»åBàò[›\î€€HûH»ÿ[]ôŸ]€€ò[[òŸJ
HHÿ]⁄
Œàõ›ÿXõJH»LKåBàò[⁄Ÿ[ë[HH›\ï⁄»HôU⁄Ÿ[î]BàYà
⁄Ÿ[ë[Hàå	âà›\ï⁄»àå
H¬à[ôYHùYBàò[€€‹[ùHYà
ôUÿ[]€€àå	âà›\î€€èHå
HôUÿ[]€€H›\î€€[ŸH€€[[›[ùà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW’ëTíQíQQ”SëQà∏ß!H“—Sî»SëQà
…»âKçàãôõ‹õX]
⁄Ÿ[ë[J_H
€…€
H””‹[ùI»âKçàãôõ‹õX]
€€‹[ù
_x•„àÿ[]õ›»	»âKçàãôõ‹õX]
›\î€€
_x•„àãà⁄Ÿ[ê[[›[ùH⁄Ÿ[ë[K⁄Y»H⁄YÀ€€[[›[ùH€€‹[ùòY\ïY»HòY\ïYÀà
Bà€ìŸ ∏ß!HSTQíTî’⁄Ÿ[ú»[ôY€ãX⁄Z[éà
…»âKçàãôõ‹õX]
⁄Ÿ[ë[J_H	›Àúﬁ[Xõ€H
€…€
HãÀõZ[ù
BàÀ»ôX€€ò⁄[HÀú‹⁄][€ãú]U⁄Ÿ[àYà\›[X]H]ô\ôŸYçIKÇàò[]ô\ôŸ[òŸHHX]òXú ⁄Ÿ[ë[HH\›[X]Y]JH»\›[X]Y]Kò€Ÿ\òŸP]X\›
YKNJBàYà
]ô\ôŸ[òŸHàåJH¬à]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW’ëTíQíQQ”SëQàº'Â!]HôX€€ò⁄[Yà\›[X]OI»âKçàãôõ‹õX]
\›[X]Y]J_H8°§àX›X[I»âKçàãôõ‹õX]
⁄Ÿ[ë[J_H
	»âKåYàãôõ‹õX]
]ô\ôŸ[òŸJåL
_IHŸôäHãà⁄Ÿ[ê[[›[ùH⁄Ÿ[ë[K⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
BàÀú‹⁄][€àHÀú‹⁄][€ãò€‹J]U⁄Ÿ[àH⁄Ÿ[ë[JBàBàBàBàYà
[[ôY
H¬àÀ»çKéKçM^ç8†%‹\ò]‹à‹XŒàïVW‘Sï”H]\›ì’ôBàÀ»[Z]YYà\\úŸH[ôXYHõ›ôYH⁄Ÿ[à[H€ÇàÀ»Hÿ[YH⁄Y»»òYRŸ^KàH›‹ôK[]ô[[€õ›€öX¬àÀ»›X\ô›\ô\‹Ÿ\»[ûHXZŒ»⁄‹ùX⁄\ò›Z]\ôH€ÀÇàYà
]ôUòYSŸ‘›‹ôKö\’\õZ[ò[Tô\€€ôY
òYRŸ^K⁄Y JH¬à\úõ‹ìŸŸŸ\ãôXùY àë^X›]‹àãàîSTQíTî’ïVHÿ]⁄ŸŒàòYH[ôXYH\õZ[ò[Hô\€€ôY
⁄YœI‹⁄YÀùZŸJMä_JH8†%⁄⁄\[ô»ïVW‘Sï”HÇà
BàH[ŸH¬à]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKî—S’ëTíQñW“Sê””ê”T“UëW‘SëSëÀà∏£Ï»\»‹›XúõÿYÿ\›àõ»⁄Ÿ[à[HY]8†%ôY⁄\›\ö[ô»õ‹àãÕKÃLZ[àôX€€ò⁄[H⁄YœI‹⁄YÀùZŸJMä_Hãà⁄Y»H⁄YÀòY\ïY»HòY\ïYÀà
BàûH¬à[ô[ô‘ôX€€ò⁄[T]Y]YKúôY⁄\›\êù^Jà⁄Y»H⁄YÀàZ[ùHÀõZ[ùàﬁ[Xõ€HÀúﬁ[Xõ€àòYRŸ^HHòYRŸ^KàòY\ïY»HòY\ïYÀàÿ[]Hÿ[]à
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàBàBàBàHÿ]⁄
Œà^Ÿ\[€äHﬂBàZ\ä⁄YÀ\›[X]Y]JBàHÿ]⁄
[\^à^Ÿ\[€äH¬àò[ÿYôHHŸX›\ö]Kúÿ[ö]\ŸQõ‹ìŸ [\^õY\‹ÿYŸHŒàù[ö€õ›€àäBàÀ»çKéKçÕŒ8†%SQTë—SïQSQKS”ìNà€\‹⁄YûH»»Ÿ\ù\úõ‹úÀÇàÀ»‹\ò]‹àõ‹ô[ú⁄X‹»KååçÃH⁄›ŸYàÀ»STQíTî’ïVHòZ[Yàò]òKúŸX›\ö]KòŸ\ùêŸ\ù]ò[Y]‹ë^Ÿ\[€éÇàÀ»ù\›[ò⁄‹àõ‹àŸ\ùYöXÿ][€à]õ›õ›[ôàÀ»Hõ›⁄[[ùHô[òX⁄»»ù\]\à\⁄[ô»H–SQHôKXù^BàÀ»ÿ[]€ò\⁄›òX⁄[ô»›\à€€ò›\úô[ùù^\ÀàŸHõ›»X\ö¬àÀ»H\úõ‹à\»ì’UW’SêUêRSPìW’»[àõ‹ô[ú⁄X‹»€»H‹\ò]‹ÇàÀ»ÿ[à‹›][ô[Z]H\›[ò›Ÿ»[ôKàHù\]\àò[òX⁄¬àÀ»\»›[][\Y
ô]\à[àôYù\⁄[ô»Hù^JHù]BàÀ»QSQW”UëW–ïVW”UUV
çKéKçÕŒ
H[ú›\ô\»€õH€ôHò[òX⁄»ù[úÀÇàò[\’»H[\^\»ò]ò^õô]ú‹€î‘”[ô⁄ZŸQ^Ÿ\[€àà[\^\»ò]ò^õô]ú‹€î‘”Y\ï[ùô\öYöYY^Ÿ\[€àà[\^\»ò]òKúŸX›\ö]KòŸ\ùêŸ\ù]ò[Y]‹ë^Ÿ\[€àà
[\^òÿ]\ŸH\»ò]òKúŸX›\ö]KòŸ\ùêŸ\ù]ò[Y]‹ë^Ÿ\[€äHàÿYôKò€€ùZ[ú êŸ\ù]ãY€õ‹ôPÿ\ŸHHùYJHàÿYôKò€€ùZ[ú ïù\›[ò⁄‹àãY€õ‹ôPÿ\ŸHHùYJHàÿYôKò€€ùZ[ú î‘”[ô⁄ZŸHãY€õ‹ôPÿ\ŸHHùYJBàYà
\’ H¬à€ìŸ º'Â$àSTQíTî’ïVNà»ù\›[ò⁄‹àòZ[\ôH8†%ì’UW’SêUêRSPìW’Ààò[[ô»õ›Y⁄»ù\]\ãàãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW—êRSQàîì’UW’SêUêRSPìW’»8†%[\‹ù[Ÿ\ù⁄Z[àôZôX›YûH[ôõ⁄Yù\››‹ôNà	‹ÿYôKùZŸJ
_H8†%ò[[ô»òX⁄»»ù\]\àãàòY\ïY»HòY\ïYÀà
BàûH¬àõ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€Jàîì’UW’SêUêRSPìW’»ãàõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€H\úèI‹ÿYôKùZŸJ
_Hãà
BàHÿ]⁄
Œàõ›ÿXõJHﬂBàH[ŸH¬àò[\‘[\MŒHÿYôKò€€ùZ[ú åMŒãY€õ‹ôPÿ\ŸHHùYJHÿYôKò€€ùZ[ú ò›\›€HõŸ‹ò[H\úõ‹àãY€õ‹ôPÿ\ŸHHùYJBàYà
\‘[\MŒ
H¬àûH»^X›][€ë[ô⁄[ùX[ô\ÿXõJîST—TëP’‘“SHãåMŒ⁄[][][€àôZôX›Yõ›]HãåÃÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»€€KõYôXﬁX€Xõ›ô[ô⁄[ôKúŸ[ìY[YUô[ùYTõ›]\ãõX\ö‘[\õ›]R[ùò[Y
ÀõZ[ù
HHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»\[[ôRX[€€X›‹ãõXô[[ò îST—TëP’‘“SWÃMŒäHHÿ]⁄
Œàõ›ÿXõJHﬂBàûH»õ‹ô[ú⁄X”ŸŸŸ\ãõYôXﬁX€JîST—TëP’ÃMŒ‘ì’UW—T–PìQãõZ[ùI›ÀõZ[ùùZŸJL
_Hﬁ[Xõ€I›Àúﬁ[Xõ€HX›[€è\ôYúô\⁄ÿXÿ€›[ù◊ÿõÿ⁄⁄\⁄›ô\öYûW€ZY‹ò][€ó›[ó‹õ›]W‹õ›]H\úèI‹ÿYôKùZŸJLå
_HäHHÿ]⁄
Œàõ›ÿXõJHﬂBà€ìŸ ∏¶®;Ó#»STQíTî’ïVHMŒ⁄[][][€àôZôX›Y8†%\ÿXõ[ô»[\\ôX›õ‹àZ[ù[ôõ›][ô»õ›]HãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW‘“SW—êRSàîST—TëP’‘“SW—êRSÃMŒ8†%õ›]H\ÿXõYõ‹àZ[ù»ôYúô\⁄Xÿ€›[ùÀÿõÿ⁄⁄\⁄€ZY‹ò][€à[àõ›]Hõ›öY\àãàòY\ïY»HòY\ïYÀà
BàH[ŸH¬à€ìŸ ∏¶®;Ó#»STQíTî’ïVHòZ[Y
	‹ÿYôKùZŸJ
_JH8†%ò[[ô»õ›Y⁄»ù\]\à[òHãÀõZ[ù
Bà]ôUòYSŸ‘›‹ôKõŸ àòYRŸ^KÀõZ[ùÀúﬁ[Xõ€êïVHãà]ôUòYSŸ‘›‹ôKî\ŸKêïVW—êRSQàîSTQíTî’ïVHòZ[Yà	‹ÿYôKùZŸJ
_H8†%ò[[ô»òX⁄»»ù\]\àãàòY\ïY»HòY\ïYÀà
BàBàBàù[àBàBüBúö]ò]Hù[à›XõKôõ]€€

Nà›ö[ô»H⁄[à¬à]\Àö\—ö[ö]J
HOàååÇà€›[ãõX]òXú \ HèHKåOàâKçàãôõ‹õX]
\ Bà€›[ãõX]òXú \ HèHåHOàâKçYàãôõ‹õX]
\ Bà[ŸHOàâKçôàãôõ‹õX]
\ BüBÇúö]ò]Hù[à›XõKôõ]⁄Y€ôY€€

Nà›ö[ô»H⁄[à¬à]\Àö\—ö[ö]J
HOàäÃåÇà€›[ãõX]òXú \ HèHKåOàâJÀçàãôõ‹õX]
\ Bà€›[ãõX]òXú \ HèHåHOàâJÀçYàãôõ‹õX]
\ Bà[ŸHOàâJÀçôàãôõ‹õX]
\ BüBÇúö]ò]Hù[à›XõKôõ]›ôX⁄\ŸJ
Nà›ö[ô»H⁄[à¬à]\Àö\—ö[ö]J
HOàäÃå	HÇà€›[ãõX]òXú \ HèHLåOàâJÀåYâIHãôõ‹õX]
\ Bà€›[ãõX]òXú \ HèHLåOàâJÀåôâIHãôõ‹õX]
\ Bà[ŸHOàâJÀåŸâIHãôõ‹õX]
\ BüBÇúö]ò]Hù[à›XõKôõ]›

HHâJÀåYâIHãôõ‹õX]
\ B