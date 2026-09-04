package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.lifecyclebot.R
import com.lifecyclebot.data.*
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.ui.MainActivity
import com.lifecyclebot.v3.scoring.BehaviorAI
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class BotService : Service() {

    companion object {
        // V5.9.1355 P0.3 â€” WAIT-override dust-probe controls.
        // Below this liquidity a weak-WAIT candidate is hard-rejected from EXEC
        // (no probe) because there isn't enough depth to even exit a dust probe.
        // V5.9.1361 P0.6/volume â€” was 800.0, which blanket-blocked the large
        // fraction of fresh meme intake sitting under $800 liquidity (operator:
        // LANE_WAIT_OVERRIDE_BLOCKED=8984, projected exec/day collapsed to 107 vs
        // the 500-1000 doctrine floor). A 0.04x DUST probe is ~0.01-0.02 SOL
        // (~$2-4 notional) and exits cleanly against far less than $800 of depth,
        // so the old floor was killing learning volume, not protecting exitability.
        // Lower to $250: still enough to round-trip a dust position, but lets the
        // brain keep getting real outcomes on thin pockets (never-disable mandate:
        // weak pockets trade SMALL, never zero). This does NOT loosen normal-size
        // entry â€” zero-score normal buys stay hard-blocked below.
        // V5.9.1421 â€” QUALITY FLOOR (operator: "I want better and more quality
        // tokens. this isnt a pumpfun bot ... its the full sol network on the
        // memetrader"). Was 250.0 â€” which let $156-300 brand-new pump.fun rugs
        // buy a dust probe. On the FULL Solana network a token under ~$2.5K
        // liquidity is dust/rug, not a tradeable meme candidate. Raise the
        // buy-side floor so the meme lanes will not open ANY position (not even
        // a dust probe) below it. Discovery stays wide open (scanner unchanged);
        // this is purely the BUY decision self-selecting depth. -1 (unknown
        // liquidity) still passes â€” the zero-score gate handles those.
        private const val LANE_PROBE_MIN_LIQ_USD = 1_500.0  // V5.9.1429 2500->1500: re-admit legit $1.5-2.5K tokens (3424 sweet spot), still rejects sub-$1K rug spam

        // V5.0.6604 Â§SPECIALIST_CONSENSUS_GATE (troubleshoot_agent P0 fix).
        //   Min UnifiedPolicyHead.predictWinProb required for a MEME specialist
        //   to be elected as primary/rescue owner once its lane head has
        //   graduated to AUTHORITATIVE. Below this floor the head itself has
        //   voted the candidate as a loser; specialist affinity must not
        //   overrule the learned negative signal.
        private const val SPECIALIST_MIN_PWIN_6604 = 0.45

        // V5.9.1455 â€” TICK-TIME catastrophic loss floor for memes (Moonshot/ShitCoin).
        // Evaluated INSIDE the 1Hz openPositionTickLoop on every fresh price â†’ no
        // 2s hotExit / 30s sweep slippage window. The lane-specific HARD_FLOOR_STOP
        // (=-15) is retained as the slow-path backstop; this is the fast-path
        // catastrophic kill-switch. Tighter (-10) at operator directive after a
        // -29.4% real-money fill on a -15% stop (V5.9.1454 dump).
        private const val TICK_HARD_FLOOR_PCT = -10.0

        // Dust-probe size multiplier (applied via qualityPenalty) â€” tiny, so a
        // weak/blind context can still generate a labelled learning sample
        // without spraying full-size capital into it.
        private const val LANE_DUST_PROBE_SIZE_MULT = 0.04
        // V5.9.1466 â€” PROBE GRADUATION (spec item 8). A probe that shows a real
        // confirmation tick (liquidity materially rising vs first-seen baseline) is
        // no longer dead-end dust â€” it graduates to a larger (still capped) size so
        // confirmed flow finally gets meaningful exposure. ~5Ã— the dust floor but
        // still a fraction of a normal buy; the additive-confidence model + lane
        // caps keep full size gated on real conviction.
        private const val LANE_CONFIRMED_PROBE_SIZE_MULT = 0.20
        private const val PROBE_GRADUATION_LIQ_RISE_FRAC = 0.40  // +40% liq vs first-seen = a confirmation tick
        @Volatile private var _instance: java.lang.ref.WeakReference<BotService>? = null
        // V5.9.384 â€” one-shot flag so BacktestEngine.logAssetClassBaseline
        // doesn't rerun on every service restart within the same process
        // (was allocating all 5784 trades Ã— 6 replays each time).
        @Volatile private var sessionBacktestRan: Boolean = false
        var instance: BotService?
            get() = _instance?.get()
            set(value) { _instance = if (value != null) java.lang.ref.WeakReference(value) else null }
        const val ACTION_START  = "com.lifecyclebot.START"
        const val ACTION_STOP   = "com.lifecyclebot.STOP"
        // V5.9.675 â€” Doze-proof loop heartbeat. Fired by AlarmManager every
        // 60s via setAlarmClock + setExactAndAllowWhileIdle dual pattern.
        // The handler in onStartCommand checks lastBotLoopTickMs and force-
        // restarts the loop coroutine if it has gone silent for >180s.
        const val ACTION_LOOP_HEARTBEAT = "com.lifecyclebot.LOOP_HEARTBEAT"
        const val EXTRA_USER_REQUESTED = "com.lifecyclebot.USER_REQUESTED"
        const val EXTRA_STOP_SOURCE = "com.lifecyclebot.STOP_SOURCE"
        const val EXTRA_UI_STOP_CONFIRMED = "com.lifecyclebot.UI_STOP_CONFIRMED"
        // V5.9.1081 â€” separates the stuck-loop force-restart rescue from the
        // normal user UI START button. Normal START is now strictly idempotent:
        // start-while-starting and start-while-running both IGNORE (no cancel,
        // no restart). The legacy "userRequested && loopActive" force-restart
        // path now requires this explicit extra to be set, and the UI START
        // button NEVER sets it. Only an explicit "halt_reset"-style operator
        // confirmation can recover from a wedged loop.
        const val EXTRA_FORCE_RESTART_CONFIRMED = "com.lifecyclebot.FORCE_RESTART_CONFIRMED"
        const val RUNTIME_PREFS = "bot_runtime"
        const val KEY_WAS_RUNNING_BEFORE_SHUTDOWN = "was_running_before_shutdown"
        const val KEY_MANUAL_STOP_REQUESTED = "manual_stop_requested"
        const val CHANNEL_ID           = "bot_running"
        const val CHANNEL_TRADE        = "trade_signals"
        const val CHANNEL_TRADE_SILENT = "trade_signals_silent"
        const val NOTIF_ID      = 1

        // V5.9.721 â€” global shutdown flag so all traders can skip heavy AI
        // learning in their closePosition() fast paths during bot stop.
        // Set to true at the START of stopBot(), cleared on startBot().
        @Volatile var isShuttingDown: Boolean = false

        // V5.0.6504 Â§5 â€” one-shot latch (mint:entryTime key) so the
        // PAPER_STALE_ZOMBIE_SCRATCH_EXIT lifecycle line + requestSell fire
        // EXACTLY ONCE per eligible position. Cleared by prunePaperZombieLatch
        // when the mint closes so a legitimate re-open + re-timeout still
        // works.
        val paperStaleZombieLatch6504: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> =
            java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        // V5.9.1165 â€” permanent-runtime stop contract.
        // The bot may stop only from an explicit confirmed Stop button / halt
        // reset, operator manual stop, or controlled config restart. Unknown
        // ACTION_STOP intents, stale UI instances, notification/task lifecycle
        // churn, or direct calls are rejected. Operator rule: run permanently
        // unless I turn the bot off.
        fun isConfirmedManualStopSource(source: String, uiStopConfirmed: Boolean): Boolean {
            return (source == "ui_stop_button" && uiStopConfirmed) ||
                source == "halt_reset" ||
                source == "operator_manual_stop"
        }

        fun isAllowedStopSource(source: String, uiStopConfirmed: Boolean): Boolean {
            return isConfirmedManualStopSource(source, uiStopConfirmed) || source == "config_restart"
        }

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // PIPELINE DEBUG HELPERS - trace exactly why tokens don't buy
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        const val DEBUG_PIPELINE = true

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // V5.9.340: MARKET TRADER MASTER KILL-SWITCH
        // User directive: disable the Market Trader completely while we
        // rewire the AATE scoring/learning stack to match the build
        // #1920-#1947 behavior. This does NOT touch any live buy/sell
        // path â€” it simply forces every Markets sub-trader into the
        // "disabled" state so none of them scan, score, or open new
        // positions. Existing positions still close through their
        // normal exit paths. Flip to false to re-enable.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        const val MARKET_TRADER_KILL_SWITCH = false  // V5.9.362 â€” reinstated; switches in Settings now drive enable/disable per trader

        /**
         * V5.9.614 â€” AntiChokeManager safety hook. When the choke goes RECOVERY
         * because the watchlist is choked with unpriced pump.fun firehose mints,
         * trigger a soft scanner reset so cooldown/saturation maps clear and
         * the discovery feed can re-prioritise. Soft only â€” never clears
         * seenMints/rejectedMints.
         */
        fun forceScannerSoftResetIfPossible() {
            try {
                val svc = instance ?: return
                val sc = svc.marketScanner ?: return
                sc.forceReset()
            } catch (_: Throwable) { /* never break */ }
        }


        /**
         * V5.9.362 â€” runtime re-apply of the Markets Trader switches. Called
         * from MainActivity right after the Settings sheet save, so toggling
         * Perps/Stocks/Commodities/Metals/Forex/Alts in the UI takes effect
         * without a service restart. The MARKET_TRADER_KILL_SWITCH constant
         * is honoured so dev builds can still globally suppress the stack.
         */
        fun reapplyMarketsTraderSwitches(ctx: android.content.Context) {
            val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
            val kill = MARKET_TRADER_KILL_SWITCH
            // V5.0.6526 Â§TRADER_RUNTIME_PLAN â€” one derivation. reapply()
            // and startBot() both consume this so drift between the two
            // paths is physically impossible.
            val plan = com.lifecyclebot.engine.truth.TraderRuntimePlan6526.from(
                cfg = cfg, marketsKill = kill, marketsLaneOnFn = { isMarketsLaneEnabled(it) },
            )
            val marketsOn = plan.marketsLaneOn
            try { com.lifecyclebot.perps.PerpsTraderAI.setEnabled(plan.perpsEffective) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.TokenizedStockTrader.setEnabled(plan.stocksEffective) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.CommoditiesTrader.setEnabled(plan.commoditiesEffective) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.MetalsTrader.setEnabled(plan.metalsEffective) } catch (_: Exception) {}
            try { com.lifecyclebot.perps.ForexTrader.setEnabled(plan.forexEffective) } catch (_: Exception) {}
            // Stop PerpsExecutionEngine immediately when Markets master toggle is turned off
            if (!marketsOn) {
                try {
                    if (com.lifecyclebot.perps.PerpsExecutionEngine.isRunning()) {
                        com.lifecyclebot.perps.PerpsExecutionEngine.stop()
                        ErrorLogger.info("BotService", "ğŸ“´ reapply: Markets OFF â€” PerpsExecutionEngine stopped")
                        instance?.addLog("ğŸ“´ Markets Trader toggled OFF â€” engine stopped")
                    }
                } catch (_: Exception) {}
            }
            val cryptoUniverseOn = plan.cryptoUniverseOn
            try { com.lifecyclebot.perps.CryptoAltTrader.setEnabled(cryptoUniverseOn) } catch (_: Exception) {}
            try {
                // V5.0.6526 â€” publish the WHOLE canonical enabled set from
                // the plan so reapply() and startBot() agree on the exact
                // authority payload. The old code mutated only the CRYPTO
                // bit of the previous snapshot which left stale entries
                // when the operator toggled other lanes mid-session.
                com.lifecyclebot.engine.EnabledTraderAuthority.publish(plan.enabledTraderSet())
            } catch (_: Exception) {}
            if (!cryptoUniverseOn) {
                try {
                    com.lifecyclebot.perps.CryptoAltTrader.stop()
                    ErrorLogger.info("BotService", "CRYPTO_RUNTIME_DISABLED reason=MEME_ONLY_MODE_OR_MARKETS_OFF reapply marketsOn=$marketsOn cryptoToggle=${cfg.cryptoAltsEnabled}")
                    instance?.addLog("ğŸ“´ Crypto Universe disabled â€” Meme-only/Markets-off isolation")
                } catch (_: Exception) {}
            }
            ErrorLogger.info("BotService", "ğŸšï¸ Markets switches re-applied: " +
                "marketsOn=$marketsOn perps=${cfg.perpsEnabled} stocks=${cfg.stocksEnabled} " +
                "comm=${cfg.commoditiesEnabled} metals=${cfg.metalsEnabled} " +
                "forex=${cfg.forexEnabled} alts=${cfg.cryptoAltsEnabled}")
        }

        /**
         * V5.9.469 â€” single source of truth for "should the Markets lane run?".
         *
         * Operator-reported bug: Markets engine kept starting in live whether
         * the toggle was switched on or not. Root cause: the previous formula
         * was `marketsTraderEnabled || tradingMode==1 || tradingMode==2`. With
         * tradingMode defaulting to 2 (BOTH), the OR made the master toggle
         * silently ineffective â€” flipping marketsTraderEnabled=false had no
         * effect because the tradingMode==2 branch overrode it. Watchdog
         * loop kept restarting the engine on every 10th tick.
         *
         * Fix: AND semantics. The master toggle has authority; the trading
         * mode just decides whether the Markets lane is even applicable
         * (mode 0 = MEMES_ONLY â†’ markets off; modes 1/2 â†’ master toggle
         * decides).
         *
         * Safe at startup AND in the watchdog. Used in both places below.
         */
        fun isMarketsLaneEnabled(cfg: com.lifecyclebot.data.BotConfig): Boolean {
            // V5.0.6618 Â§MARKETS_TOGGLE_AUTHORITY (operator directive Feb 2026:
            //   "Markets currently runs even if it's switched off in settings.
            //    Fix that."). Pre-6618, paper mode short-circuited the master
            //   toggle: `if (cfg.paperMode && !KILL) return true`. That was
            //   the V5.0.6069 "PAPER = LEARN EVERYTHING" doctrine â€” good in
            //   isolation but it ignored the user's explicit Markets toggle
            //   because the OR was invisible from Settings.
            //   6618 correction: the user's `marketsTraderEnabled` toggle
            //   has authority over paper mode. The "learn everything"
            //   semantic still applies to sub-lane toggles (stocks/forex/etc.)
            //   AFTER the master toggle is on â€” but a master-off toggle now
            //   shuts Markets down in paper mode too. Kill switch still wins.
            if (MARKET_TRADER_KILL_SWITCH) return false
            if (!cfg.marketsTraderEnabled) return false
            if (cfg.paperMode) return true
            return cfg.tradingMode == 1 || cfg.tradingMode == 2
        }

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // V5.9.353: Strategy distrust pause
        //
        // User log showed: Trust[SHITCOIN] score=0.085 level=DISTRUSTED
        // WR=0% exp=-19.38 fp=87.5%  â€” yet the bot kept routing tokens to
        // ShitCoin and they all stop-lossed (Scum Ultman -8% in 1 min).
        // When a strategy is provably bleeding, freeze it for 10 min so
        // newer (less-poisoned) strategies get the trade flow.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        const val STRATEGY_DISTRUST_PAUSE_MS = 2L * 60_000L  // V5.9.726 â€” was 10min, dropped to 2min: 10min lockouts on the only-active SHITCOIN lane were starving the executor (5132 LANE_EVAL, 0 EXEC_BUY in V5.9.725 dump)
        private val strategyPauseUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val rapidEntryWarmupHoldUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun isStrategyPausedByTrust(strategy: String): Pair<Boolean, String> {
            // V5.9.1405 â€” autonomous agenic doctrine. Trust/distrust may shape
            // score, size, routing, or symbolic reflection, but it must never
            // pause/amputate a trading layer. Every strategy has to keep taking
            // samples so it can learn, pivot, and recover from trade #1.
            return false to "agenic_no_pause"
        }


        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // V5.9.352: Meme Bridge override guardrails
        //
        // V5.9.349 made the bridge override V3 on Watch / ShadowOnly /
        // Rejected (non-routing) the moment bridge.shouldEnter was true.
        // With bridge bootstrap floors (techFloor=30 / blendedFloor=25)
        // that fired on nearly every V3 rejection â€” meme WR crashed from
        // 43% to 2% on builds 2215+.
        //
        // Now the override requires a MUCH higher bar than the logging
        // threshold. scoreForEntry() still runs for every token (so the
        // "complete picture" log is intact) â€” but executor.v3Buy only
        // fires when bridge TA conviction is strong, liquidity isn't
        // collapsing, and we haven't already overridden too recently.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        const val BRIDGE_OVERRIDE_MIN_BLEND  = 60
        const val BRIDGE_OVERRIDE_MIN_TECH   = 55
        const val BRIDGE_OVERRIDE_WINDOW_MS  = 10 * 60 * 1000L  // 10 minutes
        const val BRIDGE_OVERRIDE_MAX_PER_WINDOW = 3
        @Volatile private var bridgeOverrideTimestamps: java.util.ArrayDeque<Long> = java.util.ArrayDeque()

        /**
         * V5.9.352 â€” decide whether a bridge verdict is strong enough to
         * override V3 AND that we haven't hit the rate-limit cap yet.
         * Returns (shouldOverride, reason-if-not).
         */
        fun bridgeOverrideAllowed(
            verdict: com.lifecyclebot.v3.MemeUnifiedScorerBridge.MemeVerdict,
            mint: String,
            symbol: String,
        ): Pair<Boolean, String> {
            if (!verdict.shouldEnter) return false to "shouldEnter=false"
            if (verdict.blendedScore < BRIDGE_OVERRIDE_MIN_BLEND)
                return false to "blend=${verdict.blendedScore}<$BRIDGE_OVERRIDE_MIN_BLEND"
            if (verdict.techScore < BRIDGE_OVERRIDE_MIN_TECH)
                return false to "tech=${verdict.techScore}<$BRIDGE_OVERRIDE_MIN_TECH"
            // Liquidity-collapse veto â€” do not override into a dying pool.
            try {
                val (block, why) = com.lifecyclebot.engine.LiquidityDepthAI.shouldBlockTrade(mint, symbol, isOpenPosition = false)
                if (block) return false to "liq=${why ?: "BLOCK"}"
            } catch (_: Exception) { /* non-fatal */ }
            // Rate limit â€” max N overrides per rolling window.
            val now = System.currentTimeMillis()
            synchronized(bridgeOverrideTimestamps) {
                while (bridgeOverrideTimestamps.isNotEmpty() &&
                       bridgeOverrideTimestamps.peekFirst() < now - BRIDGE_OVERRIDE_WINDOW_MS) {
                    bridgeOverrideTimestamps.pollFirst()
                }
                if (bridgeOverrideTimestamps.size >= BRIDGE_OVERRIDE_MAX_PER_WINDOW) {
                    return false to "rate_limit ${bridgeOverrideTimestamps.size}/$BRIDGE_OVERRIDE_MAX_PER_WINDOW in ${BRIDGE_OVERRIDE_WINDOW_MS / 60_000}m"
                }
                bridgeOverrideTimestamps.addLast(now)
            }
            return true to "ok"
        }

        fun logPipeline(symbol: String, stage: String, msg: String) {
            if (!DEBUG_PIPELINE) return
            ErrorLogger.info("BotService", "[PIPELINE/$stage] $symbol | $msg")
        }

        fun logNoBuy(symbol: String, stage: String, reason: String, mint: String = "", extra: String = "") {
            if (!DEBUG_PIPELINE) return
            val mintTag = if (mint.isNotBlank()) " | mint=${mint.take(12)}" else ""
            val extraTag = if (extra.isNotBlank()) " | $extra" else ""
            ErrorLogger.warn("BotService", "[NO_BUY/$stage] $symbol | $reason$mintTag$extraTag")
        }

        // V5.9.116: Per-mint throttle for layer-level "why I skipped" diagnostics
        // so Quality + ShitCoin Express emit at most one rejection log per mint
        // every 60s instead of spamming. Before this, they skipped silently and
        // the user saw zero trades with zero explanation in the logs.
        private val layerSkipLogThrottle = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val LAYER_SKIP_LOG_MIN_INTERVAL_MS = 60_000L

        fun logLayerSkip(layer: String, symbol: String, mint: String, reason: String) {
            val key = "$layer|$mint"
            val now = System.currentTimeMillis()
            val last = layerSkipLogThrottle[key] ?: 0L
            if (now - last < LAYER_SKIP_LOG_MIN_INTERVAL_MS) return
            layerSkipLogThrottle[key] = now
            ErrorLogger.info("BotService", "[$layer SKIP] $symbol | $reason")
        }

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // UNIFIED PAPER WALLET
        // V5.9.48: Every sub-trader (CryptoAlt, TokenizedStocks, Commodities,
        // Metals, Forex) used to keep its own isolated paper balance. User
        // kept seeing $34K Markets portfolio + $31K P&L while the main dash
        // showed $2,733 â€” because the Markets profits never flowed back into
        // the canonical wallet. One source of truth now lives here: any
        // paper-side trade (open or close) from ANY trader routes through
        // `creditUnifiedPaperSol(delta)`, which delegates to the same
        // safety-clamped callback the Executor already uses.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        @Deprecated("V5.0.6486: use CanonicalPaperTransaction6486 typed BUY/SELL/REFUND")
        fun creditUnifiedPaperSol(delta: Double, source: String) {
            try {
                ForensicLogger.lifecycle("GENERIC_PAPER_CREDIT_REJECTED_6486", "source=$source delta=${"%.6f".format(delta)}")
                PipelineHealthCollector.labelInc("GENERIC_PAPER_CREDIT_REJECTED_6486")
            } catch (_: Throwable) {}
        }


        fun logBuyHandoff(symbol: String, mint: String, sizeSol: Double, source: String = "", score: Double = 0.0) {
            if (!DEBUG_PIPELINE) return
            val srcTag = if (source.isNotBlank()) " | src=$source" else ""
            val scoreTag = if (score > 0.0) " | score=${score.toInt()}" else ""
            ErrorLogger.info("BotService", "[BUY_HANDOFF] $symbol | mint=${mint.take(12)} | size=${"%.4f".format(sizeSol)}$srcTag$scoreTag")
        }

        // Shared live state â€” observed by UI via polling or flow
        val status = BotStatus()
        lateinit var walletManager: WalletManager
        // V5.9: Track recently closed positions to prevent immediate re-entry (churn prevention)
        val recentlyClosedMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /**
         * V5.0.3792 â€” GHOST LIVE-POSITION PURGE (host wallet = source of truth).
         * Operator: "no tokens in my wallet except SOL but the bot thinks there is 3"
         * (RECOVERED rows WSOLP/VDoRrq/DiYVat: Size 0, Entry â€”, phantom qtyToken).
         * When the host-wallet tracker has terminally closed a mint (confirmed zero /
         * dust / absent from a trusted wallet read), the matching live TokenState must
         * also be cleared so the dashboard tile, status.openPositions and the
         * localLiveOpen accounting all drop it. Idempotent and lock-safe; only ever
         * clears a LIVE (non-paper) position so a paper sim book is never touched.
         */
        /**
         * V5.0.4550 â€” ENTRY_AUTHORITY_HELD_STICKY_STATUS_GUARD.
         * A wallet-held / bot-managed LIVE mint must never be evicted from
         * status.tokens/watchlist by source-balance, no-pair, basis-wait, or
         * ghost cleanup paths. Only explicit terminal close/zero-balance finality
         * may remove it. This protects RECOVERED_* rows and real live positions
         * during basis/route/provider degradation.
         */
        fun liveHeldOrManagedMint(mint: String): Boolean {
            if (mint.isBlank()) return false
            try {
                val ts = synchronized(status.tokens) { status.tokens[mint] }
                val pos = ts?.position
                if (pos != null && !pos.isPaperPosition && (pos.isOpen || pos.pendingVerify || pos.qtyToken > 0.0)) return true
            } catch (_: Throwable) {}
            val tracked = try { com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null } ?: return false
            return try {
                if (tracked.zeroBalanceConfirmedByTwoProviders) return false
                val rawPositive = try { java.math.BigInteger(tracked.rawAmount.trim().ifBlank { "0" }) > java.math.BigInteger.ONE } catch (_: Throwable) { false }
                val uiPositive = tracked.uiAmount.isFinite() && tracked.uiAmount > 1.0
                (tracked.status in com.lifecyclebot.engine.HostWalletTokenTracker.OPEN_STATUSES) || rawPositive || uiPositive
            } catch (_: Throwable) { false }
        }

        fun purgeGhostLivePosition(mint: String, reason: String) {
            if (mint.isBlank()) return
            if (liveHeldOrManagedMint(mint)) {
                try { PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_HELD_GHOST_PURGE_BLOCKED_4550") } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("ENTRY_AUTHORITY_HELD_GHOST_PURGE_BLOCKED_4550", "mint=${mint.take(10)} reason=$reason action=preserve_status_watchlist") } catch (_: Throwable) {}
                return
            }
            try {
                synchronized(status.tokens) {
                    val ts = status.tokens[mint] ?: return@synchronized
                    if (ts.position.isPaperPosition) return@synchronized
                    ts.position = com.lifecyclebot.data.Position()  // qtyToken=0 â†’ isOpen=false
                    status.tokens.remove(mint)
                }
                recentlyClosedMs[mint] = System.currentTimeMillis()
                try { com.lifecyclebot.v3.V3EngineManager.onPositionClosed(mint) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("GHOST_LIVE_POSITION_PURGED", "mint=${mint.take(10)} reason=$reason") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
        private const val RE_ENTRY_COOLDOWN_MS = 300_000L  // 5 minutes

        // V5.9.148 â€” guards the stop â†’ start race. stopBot() flips status.running
        // to false near its top, but then spends 20-60s closing Markets positions
        // and calling .stop() on every trader singleton. If the user tapped START
        // in that window, onStartCommand saw !running and launched startBot(),
        // which initialized the traders â€” then the tail of the OLD stopBot stopped
        // them again. Symptom: "30 button presses, bot won't restart".
        @Volatile
        var stopInProgress = false

        // V5.0.3789 â€” STOP PERSISTENCE FINALIZATION LATCH (operator fault #1).
        // A manual stop runs PositionPersistence.clear() as the canonical final
        // state. After that, NO code may re-save stale pre-clear status.tokens
        // (onDestroy crash-recovery save / pre-death flush) until a fresh Start
        // rebuilds canonical state. Without this latch, stopBot cleared persistence
        // and then onDestroy immediately re-saved 4 stale open positions, causing
        // liveStore / host tracker / close ledger / persisted positions to diverge
        // and the reconciler to re-import dead rows on next start.
        @Volatile
        var persistenceFinalizedByStop = false

        @Volatile
        var userStartQueuedDuringStop = false

        // A restart requested while the old service is draining must be delivered
        // to a fresh Service instance.  Starting inside the old service scope lets
        // onDestroy()/scope.cancel() kill the replacement.  This latch covers the
        // short interval between stopBot() completing and the fresh ACTION_START
        // being dispatched on the Android main looper.
        @Volatile
        private var restartAfterStopDispatchPending6518 = false

        /**
         * V5.9.1071 â€” service-owned runtime truth for UI.
         *
         * The UI previously read/wrote status.running directly. That lets an Activity/ViewModel
         * transition make Main show "stopped" while BotService.loopJob is still alive, or worse,
         * makes a subsequent START race the real service state. Runtime truth must be derived
         * from the service-owned loop/stop latches only. This is read-only to UI.
         */
        data class BackgroundRuntimeHealth6487(
            val runtimeActive: Boolean,
            val loopActive: Boolean,
            val foregroundActive: Boolean,
            val authorityActive: Boolean,
            val progressAgeMs: Long,
            val phase: String,
            val healthy: Boolean,
        )

        fun backgroundRuntimeHealth6487(nowMs: Long = System.currentTimeMillis()): BackgroundRuntimeHealth6487 {
            val svc = instance
            val runtimeActive = isRuntimeActive()
            val loopActive = try { svc?.loopJob?.isActive == true } catch (_: Throwable) { false }
            val foregroundActive = try { svc?.serviceForegroundActive6487 == true } catch (_: Throwable) { false }
            val progressAt = try { svc?.lastProgressAtMs ?: 0L } catch (_: Throwable) { 0L }
            val progressAge = if (progressAt > 0L) (nowMs - progressAt).coerceAtLeast(0L) else Long.MAX_VALUE
            val authorityActive = try { com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.isRuntimeActive() } catch (_: Throwable) { false }
            val phase = try { svc?.currentPhase ?: "NO_SERVICE" } catch (_: Throwable) { "UNKNOWN" }
            val healthy = runtimeActive && loopActive && foregroundActive && authorityActive && progressAge <= 120_000L
            return BackgroundRuntimeHealth6487(runtimeActive, loopActive, foregroundActive, authorityActive, progressAge, phase, healthy)
        }

        fun backgroundLivenessSnapshot6544(): String {
            val svc = instance ?: return "background service=ABSENT"
            return try {
                val pm = svc.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val interactive = pm?.isInteractive ?: true
                val idle = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) pm?.isDeviceIdleMode ?: false else false
                val whitelisted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) pm?.isIgnoringBatteryOptimizations(svc.packageName) ?: false else true
                val am = svc.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val usm = svc.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                val standby = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) usm?.appStandbyBucket ?: -1 else -1
                val restricted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) am?.isBackgroundRestricted ?: false else false
                val uiVisible = try { com.lifecyclebot.AATEApp.isAnyActivityVisible6487() } catch (_: Throwable) { false }
                val wakeHeld = try { svc.wakeLock?.isHeld == true } catch (_: Throwable) { false }
                val wifiHeld = try { svc.wifiLock6032?.isHeld == true } catch (_: Throwable) { false }
                "screenInteractive=$interactive uiVisible=$uiVisible batteryOptWhitelisted=$whitelisted " +
                    "deviceIdle=$idle standbyBucket=$standby backgroundRestricted=$restricted " +
                    "foregroundService=${svc.serviceForegroundActive6487} wakeLockHeld=$wakeHeld wifiLockHeld=$wifiHeld " +
                    "botLoopActive=${svc.loopJob?.isActive == true} phase=${svc.currentPhase} progressAgeMs=${backgroundRuntimeHealth6487().progressAgeMs}"
            } catch (_: Throwable) { "background liveness=UNAVAILABLE" }
        }

        fun isBackgroundRuntimeHealthy6487(): Boolean = backgroundRuntimeHealth6487().healthy

        // V5.0.6517 â€” UI-visible command truth. A queued Start is not a running
        // runtime, but it must never be invisible: MainActivity renders it as
        // "Cancel Start" and can revoke it through the normal confirmed Stop path.
        fun isStartPending6517(): Boolean = try {
            instance?.serviceStartRequested6517?.get() == true && !isRuntimeActive()
        } catch (_: Throwable) { false }

        fun startFailure6517(): String = try {
            instance?.serviceBootstrapFailure6517.orEmpty()
        } catch (_: Throwable) { "" }

        fun isRuntimeActive(): Boolean {
            return try {
                val svc = instance
                val svcLoopActive = try { svc?.loopJob?.isActive == true } catch (_: Throwable) { false }
                // Executing coroutine truth only.  stopInProgress and status.running
                // are intent/UI mirrors, not proof that the pipeline can scan or
                // execute.  Including either produced the operator's impossible
                // "ACTIVE + botLoopActive=false + EXEC=0" snapshot.
                BotRuntimeController.snapshot().runtimeActive || svcLoopActive || svc?.startInProgress == true
            } catch (_: Throwable) {
                try {
                    val svc = instance
                    (svc?.loopJob?.isActive == true) || svc?.startInProgress == true
                } catch (_: Throwable) { false }
            }
        }

        fun heldPositionCountForRescue(): Int {
            return try {
                val statusOpen = try { status.openPositions.size } catch (_: Throwable) { 0 }
                val tokenOpen = try { status.tokens.values.count { it.position.isOpen } } catch (_: Throwable) { 0 }
                val hostOpen = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { 0 }
                // V5.0.3796 â€” wallet-truth filtered lifecycle count. Raw openCount()
                // includes stale lifecycle-only rows after host/wallet reconciliation,
                // causing loop header positions=1 while hostOpen=0/walletOpen=0.
                val lifecycleOpen = try { com.lifecyclebot.engine.TokenLifecycleTracker.liveMemeOpenCount() } catch (_: Throwable) { 0 }
                val cashGenOpen = try {
                    com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(true).size +
                        com.lifecyclebot.v3.scoring.CashGenerationAI.getPositionsForMode(false).size
                } catch (_: Throwable) { 0 }
                maxOf(statusOpen, tokenOpen, hostOpen, lifecycleOpen, cashGenOpen)
            } catch (_: Throwable) { 0 }
        }

        // V5.9.621 â€” inert-loop watchdog state. Updated on every scanner discovery.
        @Volatile
        var lastScannerDiscoveryMs: Long = 0L

        @Volatile
        var inertWatchdogFiredOnce: Boolean = false

        // V5.9.714-FIX: set at the top of every startBot() call so the
        // pendingVerify force-clear knows if the watchdog has run this session.
        @Volatile
        var botStartTimeMs: Long = 0L
        // Promoted from botLoop() local var â€” needed by manageExits() for
        // pendingVerify force-clear gate (V5.9.714).
        @Volatile
        var lastPendingVerifyWatchdogAt: Long = 0L

        fun isManualStopRequested(ctx: Context): Boolean = try {
            ctx.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MANUAL_STOP_REQUESTED, false)
        } catch (_: Throwable) { false }
    }

    // Coroutine exception handler - logs errors without crashing
    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        ErrorLogger.error("BotService", 
            "Coroutine exception in ${context[CoroutineName]?.name ?: "unknown"}: " +
            "${throwable.javaClass.simpleName}: ${throwable.message}", 
            throwable
        )
        addLog("âš ï¸ Background error: ${throwable.javaClass.simpleName} - ${throwable.message?.take(50)}")
        
        // Don't crash - just log and continue
        // The SupervisorJob ensures child coroutines don't cancel siblings
    }

    // V5.0.6647 â€” structured service ownership.  Supervisor and exit work
    // are children of this job and run on separate bounded executors, so
    // provider/discovery pressure cannot queue the exit consumer indefinitely.
    private val serviceJob6647 = SupervisorJob()
    private val serviceExecutor6647: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
            Thread(r, "AATE-Service-6647").apply { isDaemon = true }
        }
    private val supervisorExecutor6647: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(16) { r ->
            Thread(r, "AATE-Entry-6647").apply { isDaemon = true }
        }
    private val exitExecutor6647: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "AATE-Exit-Coordinator-6647").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }
    private val exitWorkerExecutor6647: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
            Thread(r, "AATE-Exit-Policy-6647").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }
    // V5.0.6668 â€” the acceptance-window closer is wall-clock infrastructure,
    // not trading work. Keeping it off the bounded service coroutine pool
    // prevents blocking provider tasks from starving the mandatory 120-second
    // smoke verdict (observed after the CI Stop -> Start lifecycle exercise).
    private val acceptanceWindowExecutor6668: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AATE-Acceptance-6668").apply { isDaemon = true }
        }
    @Volatile
    private var acceptanceWindowFuture6668: java.util.concurrent.ScheduledFuture<*>? = null
    private val serviceDispatcher6647 = serviceExecutor6647.asCoroutineDispatcher()
    private val supervisorDispatcher6647 = supervisorExecutor6647.asCoroutineDispatcher()
    private val exitDispatcher6647 = exitExecutor6647.asCoroutineDispatcher()
    private val exitWorkerDispatcher6647 = exitWorkerExecutor6647.asCoroutineDispatcher()
    private val scope = CoroutineScope(serviceJob6647 + serviceDispatcher6647 + exceptionHandler)
    private val exitScope6647 = CoroutineScope(serviceJob6647 + exitDispatcher6647 + exceptionHandler + CoroutineName("exit-coordinator-6647"))
    private val exitWorkerScope6647 = CoroutineScope(serviceJob6647 + exitWorkerDispatcher6647 + exceptionHandler + CoroutineName("exit-policy-6647"))
    private val specialistWorkerJobs6647 = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val specialistRestartAfterMs6647 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val specialistRestartFailures6647 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    @Volatile private var specialistWorkerSupervisor6647: kotlinx.coroutines.Job? = null

    // V5.0.6515 â€” canonical durable replay must never run in Service.onCreate's main thread.
    @Volatile private var canonicalBootstrapReady6515 = false
    @Volatile private var canonicalBootstrapSucceeded6515 = false
    @Volatile private var canonicalBootstrapJob6515: kotlinx.coroutines.Job? = null
    // V5.0.6516 â€” complete persisted-state/service bootstrap barrier.
    @Volatile private var serviceBootstrapReady6516 = false
    @Volatile private var serviceBootstrapSucceeded6516 = false
    @Volatile private var serviceBootstrapJob6516: kotlinx.coroutines.Job? = null
    // `serviceStartQueued6516` owns the single waiter coroutine only.
    // `serviceStartRequested6517` separately owns operator intent so repeated
    // taps are durable and Stop can cancel a deferred start without races.
    private val serviceStartQueued6516 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val serviceStartRequested6517 = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var serviceBootstrapFailure6517: String = ""

    // V5.9.1495 â€” async safety-refresh trigger. Must fire BEFORE FDG's
    // LiveBuyAdmissionGate.SAFETY_STALE_MS (120s) cutoff so the regenerated
    // report lands while still "fresh", closing the 2minâ€“10min dead zone that
    // produced SAFETY_NOT_READY_STALE live-entry blocks. 30s margin covers the
    // safety check's own 0â€“10s network latency + the next loop tick.
    private val SAFETY_REFRESH_TRIGGER_MS: Long =
        (com.lifecyclebot.engine.sell.LiveBuyAdmissionGate.SAFETY_STALE_MS - 30_000L)
            .coerceAtLeast(30_000L)

    /**
     * V5.9.1495 â€” main-thread-safe live SOL balance read for UI preflight.
     *
     * ROOT CAUSE (5.0.3501 WALLET_RPC_ON_MAIN_THREAD): manualBuy/manualSell
     * preflight read `if (cached>0) cached else w.getSolBalance()`. When the
     * cached balance was 0.0 AND the call ran on the UI/Main thread (button
     * handler), getSolBalance()'s V5.9.771 main-thread guard THREW, the catch
     * swallowed it â†’ preflight saw 0.0 SOL and could mis-toast "insufficient
     * SOL" or skip the balance guard on a live trade. Dangerous for 10 live
     * testers.
     *
     * Fix at source: never invoke the throwing RPC path on Main. Prefer the
     * cached value; if empty, fall back to WalletManager's last published
     * state (exactly what the top bar shows). Only when we are NOT on Main do
     * we allow the on-demand RPC. No blocking call is ever issued on the UI
     * thread.
     */
    private fun livePreflightWalletSol(w: SolanaWallet?): Double {
        val cached = try {
            com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).state.value.solBalance
        } catch (_: Throwable) { 0.0 }
        if (cached > 0.0) return cached
        val onMain = android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
        if (onMain) {
            // Do NOT trip the RPC guard on Main. Use whatever WalletManager
            // last published (may be 0.0 on a brand-new wallet â€” caller still
            // gets a safe, non-throwing value and a fresh async refresh will
            // populate it shortly).
            return try {
                com.lifecyclebot.engine.WalletManager.getInstance(applicationContext)
                    .state.value.solBalance
            } catch (_: Throwable) { 0.0 }
        }
        return try { w?.getSolBalance() ?: 0.0 } catch (_: Throwable) { 0.0 }
    }

    // V5.9.1557b â€” keep singleton monitor startup OUT of botLoop's coroutine
    // state machine. Inline launches made the already-huge botLoop trip Kotlin's
    // CoroutineTransformer StackOverflowError in release CI.
    private fun startSingletonRuntimeMonitors() {
        ensureSpecialistWorkers6647()
        try {
            if (rapidStopLossMonitorJob?.isActive != true) {
                rapidStopLossMonitorJob = exitWorkerScope6647.launch(CoroutineName("rapid-stop-6647")) { rapidStopLossMonitor() }
            }
        } catch (_: Throwable) {}
        try {
            if (openPositionTickJob?.isActive != true) {
                openPositionTickJob = exitWorkerScope6647.launch(CoroutineName("open-mark-6647")) { openPositionTickLoop() }
            }
        } catch (_: Throwable) {}
    }

    private fun ensureSpecialistWorkers6647() {
        if (specialistWorkerSupervisor6647?.isActive == true) return
        // Keep specialist liveness off the saturated scan/execution scope.
        // The exit worker scope is independently dispatched and already owns
        // other latency-critical runtime monitors.
        specialistWorkerSupervisor6647 = exitWorkerScope6647.launch(CoroutineName("specialist-supervisor-6647")) {
            while (status.running) {
                val now = System.currentTimeMillis()
                // V5.0.6653 â€” runtime-owned terminalization.  Stale intents
                // must not depend on the operator opening/copying a report.
                try { com.lifecyclebot.engine.truth.PendingIntentBacklog6625.reap6625(30_000L) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.reap6627(30_000L) } catch (_: Throwable) {}
                ToolkitSignalSheet.configuredMemeDesks6647().forEach { lane ->
                    val current = specialistWorkerJobs6647[lane]
                    if (current?.isActive == true || now < (specialistRestartAfterMs6647[lane] ?: 0L)) return@forEach
                    val worker = exitWorkerScope6647.launch(CoroutineName("specialist-${lane.lowercase()}-6647")) {
                        val healthySince6647 = System.currentTimeMillis()
                        val registeredJob6647 = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                            ?: error("SPECIALIST_JOB_CONTEXT_MISSING")
                        com.lifecyclebot.engine.truth.SpecialistRuntimeRegistry6647.register(lane, "BotService:$lane", registeredJob6647)
                        try {
                            while (status.running) {
                                com.lifecyclebot.engine.truth.SpecialistRuntimeRegistry6647.heartbeat(lane)
                                com.lifecyclebot.engine.truth.SpecialistRuntimeRegistry6647.poll(lane)
                                if (System.currentTimeMillis() - healthySince6647 >= 30_000L) {
                                    specialistRestartFailures6647.remove(lane)
                                }
                                delay(250L)
                            }
                        } finally {
                            com.lifecyclebot.engine.truth.SpecialistRuntimeRegistry6647.stopped(lane, registeredJob6647)
                        }
                    }
                    worker.invokeOnCompletion { cause ->
                        specialistWorkerJobs6647.remove(lane, worker)
                        if (status.running) {
                            val failures = specialistRestartFailures6647
                                .computeIfAbsent(lane) { java.util.concurrent.atomic.AtomicInteger(0) }
                                .incrementAndGet()
                            val backoff = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
                            specialistRestartAfterMs6647[lane] = System.currentTimeMillis() + backoff
                            try { ForensicLogger.lifecycle("SPECIALIST_WORKER_RESTART_SCHEDULED_6647", "lane=$lane backoffMs=$backoff cause=${cause?.javaClass?.simpleName ?: "completed"}") } catch (_: Throwable) {}
                        }
                    }
                    specialistWorkerJobs6647[lane] = worker
                }
                delay(1_000L)
            }
        }
    }

    // V5.9.1023 â€” DEDICATED BOT-LOOP DISPATCHER.
    //
    // Operator V5.9.1022 snapshot showed the bot completely dead, stuck in
    // phase=RESCUE_LAUNCHING for 10+ minutes. Every 180s the heartbeat fired
    // a new performServiceScopeRescue â†’ scope.launch(Dispatchers.IO) â†’ newJob
    // active=true, but botLoop's FIRST line (markProgress("BOTLOOP_BOOT"))
    // never ran. No BOTLOOP_STARTED, no BOTLOOP_RESCUE_THREW â€” the coroutine
    // was queued but never got CPU time.
    //
    // Root cause: Dispatchers.IO thread-pool starvation. The supervisor phase
    // launches up to 100-range OkHttp .execute() blocking calls. When
    // Helius/Birdeye/DexScreener wedge in JNI socket-reads, those calls
    // ignore cancel() (native code is uncancellable). Each rescue cancels
    // the corpse and launches a NEW botLoop on the SAME saturated pool;
    // after a few rescues all 64 default IO threads are wedged and new
    // launches queue indefinitely.
    //
    // Fix: a dedicated OS thread that NEVER shares with Dispatchers.IO. Even
    // if all 64 IO threads are wedged in JNI socket-reads, this thread is
    // alive and ready to execute botLoop. The thread is daemon (won't keep
    // the JVM alive) and exclusive to botLoop/rescue dispatch. Note:
    // Dispatchers.IO.limitedParallelism(1) would NOT suffice â€” it shares the
    // underlying scheduler and would also stall when the parent pool is
    // wedged. We need a separate OS thread to guarantee liveness.
    private val botLoopExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "AATE-BotLoop-Dedicated").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1   // mild boost so it preempts cheap IO chatter
            }
        }
    private val botLoopDispatcher: CoroutineDispatcher = botLoopExecutor.asCoroutineDispatcher()

    private val dex    = DexscreenerApi()
    @Volatile private var serviceForegroundActive6487: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock6032: android.net.wifi.WifiManager.WifiLock? = null

    private fun ensureRuntimeWifiLock6032(reason: String) {
        try {
            val existing = wifiLock6032
            if (existing?.isHeld == true) return
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            wifiLock6032 = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lifecyclebot:network:always_on_6032").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            try { ForensicLogger.lifecycle("ALWAYS_ON_WIFI_LOCK_REASSERTED_6032", "reason=$reason held=true runtime=${status.running} loop=${loopJob?.isActive == true} hotExit=${hotExitJob?.isActive == true}") } catch (_: Throwable) {}
        } catch (t: Throwable) {
            try { ErrorLogger.warn("BotService", "ALWAYS_ON_WIFI_LOCK_REASSERT_FAILED_6032 reason=$reason err=${t.message}") } catch (_: Throwable) {}
        }
    }

    private fun ensureRuntimeWakeLock6031(reason: String) {
        try {
            val existing = wakeLock
            if (existing?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecyclebot:trading:always_on_6031").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            try { ForensicLogger.lifecycle("ALWAYS_ON_WAKELOCK_REASSERTED_6031", "reason=$reason held=true runtime=${status.running} loop=${loopJob?.isActive == true} hotExit=${hotExitJob?.isActive == true}") } catch (_: Throwable) {}
        } catch (t: Throwable) {
            try { ErrorLogger.warn("BotService", "ALWAYS_ON_WAKELOCK_REASSERT_FAILED_6031 reason=$reason err=${t.message}") } catch (_: Throwable) {}
        }
    }

    private fun ensureAlwaysOnRuntimeGuards6031(reason: String) {
        try {
            startForeground(NOTIF_ID, buildRunningNotif())
            serviceForegroundActive6487 = true
        } catch (t: Throwable) {
            serviceForegroundActive6487 = false
            try { PipelineHealthCollector.labelInc("BACKGROUND_FGS_REASSERT_FAILED_6487") } catch (_: Throwable) {}
            try { ErrorLogger.warn("BotService", "foreground reassert failed reason=$reason err=${t.message}") } catch (_: Throwable) {}
        }
        ensureRuntimeWakeLock6031(reason)
        ensureRuntimeWifiLock6032(reason)
        // V5.0.6616 Â§BACKGROUND_DOZE_EXEMPTION_AUDIT (operator directive
        //   Feb 2026: "Trading pauses when Android throttles the process
        //   at screen-off"). The service holds PARTIAL_WAKE_LOCK + WiFi
        //   lock + foreground service, but Doze / App Standby / background
        //   restriction can still throttle the process's Alarm/Job
        //   cadence if the user hasn't whitelisted the app in Battery
        //   Optimization. Emit a health label on every guard pass so the
        //   operator can grep for the exact throttling class active while
        //   trading is stalled at screen-off. No behaviour change here â€”
        //   this surfaces the root cause so the user can whitelist via
        //   Settings â†’ Battery â†’ Battery Optimization â†’ LifecycleBot â†’
        //   "Don't optimize". Folded into the 6616 patch as a companion
        //   background-liveness diagnostic; the journal repair proper
        //   lives in JournalEconomicAuthority6616.
        try {
            val pm6613 = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val whitelisted6613 = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                pm6613?.isIgnoringBatteryOptimizations(packageName) ?: false else true
            if (!whitelisted6613) {
                PipelineHealthCollector.labelInc("BACKGROUND_DOZE_RISK_NOT_WHITELISTED_6616")
                ForensicLogger.lifecycle(
                    "BACKGROUND_DOZE_RISK_NOT_WHITELISTED_6616",
                    "reason=$reason whitelisted=false action=user_must_whitelist_in_battery_opt_settings " +
                        "note=partial_wake_lock_alone_cannot_prevent_doze_throttling",
                )
            } else {
                PipelineHealthCollector.labelInc("BACKGROUND_DOZE_EXEMPT_WHITELISTED_6616")
            }
            val am6613 = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val restricted6613 = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                am6613?.isBackgroundRestricted ?: false else false
            if (restricted6613) {
                PipelineHealthCollector.labelInc("BACKGROUND_ANDROID_RESTRICTED_6616")
                ForensicLogger.lifecycle(
                    "BACKGROUND_ANDROID_RESTRICTED_6616",
                    "reason=$reason isBackgroundRestricted=true action=user_must_disable_restrict_background_activity",
                )
            }
            val usm6613 = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val bucket6613 = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                usm6613?.appStandbyBucket ?: -1 else -1
            // Bucket >= 30 (RARE) or 40 (RESTRICTED) severely throttles alarms.
            if (bucket6613 >= 30) {
                PipelineHealthCollector.labelInc("BACKGROUND_ANDROID_STANDBY_BUCKET_${bucket6613}_6616")
                ForensicLogger.lifecycle(
                    "BACKGROUND_ANDROID_STANDBY_RESTRICTED_6616",
                    "reason=$reason bucket=$bucket6613 action=doze_and_alarm_throttling_active",
                )
            }
        } catch (_: Throwable) {}
        try { scheduleKeepAliveAlarm() } catch (_: Throwable) {}
        try { ServiceWatchdog.scheduleAlarm(applicationContext) } catch (_: Throwable) {}
        val held = try { heldPositionCountForRescue() } catch (_: Throwable) { 0 }
        val shouldRun = try {
            val rp = getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            rp.getBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false) && !rp.getBoolean(KEY_MANUAL_STOP_REQUESTED, false)
        } catch (_: Throwable) { status.running }
        if ((status.running || shouldRun || held > 0) && hotExitJob?.isActive != true) {
            try { ensureHotExitAlive() } catch (_: Throwable) {}
        }
        if ((shouldRun || held > 0) && loopJob?.isActive != true && !startInProgress && !stopInProgress) {
            try {
                ForensicLogger.lifecycle("ALWAYS_ON_RUNTIME_RESCUE_6031", "reason=$reason held=$held shouldRun=$shouldRun statusRunning=${status.running} loopActive=false hotExit=${hotExitJob?.isActive == true}")
            } catch (_: Throwable) {}
            try { scope.launch { startBot() } } catch (_: Throwable) {}
        }
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wallet: SolanaWallet? = null

    // V5.9.73: track in-flight wallet connect so startBot() returns
    // immediately instead of blocking for 30â€“90s while RPC fallbacks
    // sequentially time out. stopBot() / mode switch can cancel it cleanly.
    @Volatile private var walletConnectJob: kotlinx.coroutines.Job? = null
    private lateinit var strategy: LifecycleStrategy
    internal lateinit var executor: Executor
    private lateinit var sentimentEngine: SentimentEngine
    private lateinit var safetyChecker: TokenSafetyChecker
    private lateinit var securityGuard: SecurityGuard
    private var orchestrator: DataOrchestrator? = null
    private var marketScanner: SolanaMarketScanner? = null

    // V5.9.634c â€” freeze-detector state. Lives on the class (not as botLoop
    // locals) so the detector body can be extracted into runFreezeDetectorTick
    // and keep botLoop under the JVM 64KB per-method bytecode limit.
    private var freezeLastExecCount: Long = -1L
    private var freezeLastExecChangeMs: Long = 0L
    private var freezeRecoveryFiredAt: Long = 0L
    internal var tradeDb: TradeDatabase? = null
    internal var botBrain: BotBrain? = null
    lateinit var soundManager: SoundManager
    lateinit var currencyManager: CurrencyManager
    lateinit var notifHistory: NotificationHistory
    lateinit var tradeJournal: TradeJournal
    lateinit var autoMode: AutoModeEngine
    lateinit var copyTradeEngine: CopyTradeEngine
    @Volatile private var loopJob: Job? = null
    @Volatile private var rapidStopLossMonitorJob: Job? = null
    @Volatile private var openPositionTickJob: Job? = null
    private val tokenMintUploadInFlight = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // V5.9.1081 â€” single-flight startup latch. Set to true the moment
    // ACTION_START is accepted, cleared when startBot() finishes
    // (success or failure). Prevents 10 rapid ACTION_START intents from
    // creating 10 concurrent runtime jobs. Used in addition to (not
    // instead of) loopJob.isActive â€” startInProgress covers the window
    // BETWEEN ACTION_START being accepted and loopJob first becoming
    // active, where loopJob.isActive is still false but a startup is
    // already in flight.
    @Volatile private var startInProgress: Boolean = false

    // V5.9.762 â€” EMERGENT CRITICAL #1: heartbeat/rescue rewrite.
    //
    // The class-level @Volatile loopJob is still here for backward
    // compatibility with the dozens of read-only call sites that just
    // check loopJob?.isActive. But ALL mutation now goes through the
    // synchronized helpers below to guarantee single-loop ownership
    // (CAS semantics on an Android-supported value).
    private val loopJobLock = Any()
    /** Monotonic timestamp updated whenever the loop completes a phase.
     *  The heartbeat reads (now - lastProgressAtMs) to decide whether
     *  the loop is wedged versus merely slow. Updated by markProgress(). */
    @Volatile private var lastProgressAtMs: Long = System.currentTimeMillis()
    // V5.9.935 â€” re-deadlock auto-stop tracking. See heartbeat block where
    // these are read & updated (consecutiveSamePhaseRescues >= 2 within
    // 120s â‡’ auto-stop bot). Volatile because read from alarm receiver
    // thread, written from heartbeat handler thread.
    @Volatile private var lastRescueMs: Long = 0L
    @Volatile private var lastRescuePhase: String = ""
    @Volatile private var consecutiveSamePhaseRescues: Int = 0
    // V5.9.1218/1219 â€” rescue debounce. Runtime 5.0.3185 showed
    // RESCUE_RELAUNCHED_SERVICE_SCOPE=104 while BOT_LOOP_TICK was still
    // advancing. Debounce only when progress is recently healthy; a stale
    // active coroutine must still be rescued.
    private val LOOP_RESCUE_MIN_INTERVAL_MS = 5L * 60_000L
    private val LOOP_RESCUE_FORCE_STALE_MS = 120_000L
    @Volatile private var lastKeepAliveAlarmScheduledMs: Long = 0L

    /** Current pipeline phase (BOT_LOOP_TICK, PRE_SUPERVISOR, SUPERVISOR,
     *  POST_SUPERVISOR, EXIT_SWEEP, CYCLE_EXIT, IDLE). Read by the heartbeat
     *  to decide whether to suppress a rescue while inside a critical
     *  long-running section. */
    @Volatile private var currentPhase: String = "IDLE"
    /** Phases where the loop legitimately runs for many seconds (RPC,
     *  exit sweep, wallet reconcile). The heartbeat will NEVER cancel
     *  a loop that is still inside one of these â€” operator EMERGENT
     *  V5.9.761 dump showed the bot was actually healthy but the
     *  rescue fired anyway and ripped a working SCAN_CB out from under
     *  itself, then GlobalScope-relaunched. We never want that again. */
    private val activePhaseSet = setOf(
        "PRE_SUPERVISOR", "SUPERVISOR", "POST_SUPERVISOR",
        "SCAN_CB", "INTAKE", "SAFETY", "EXIT_SWEEP", "WALLET_SWEEP",
        "BOTLOOP_BOOT",
    )
    /** Heartbeat tolerance â€” only rescue if no progress for this long.
     *  Each cycle is ~15s avg, slowest observed ~99s; 3 * 60s alarm =
     *  180s gives 12 cycle budgets. */
    private val rescueProgressGraceMs = 180_000L
    // V5.9.674 â€” separate watchdog coroutine that pings the bot loop every
    // 30s and force-restarts it if it has not produced a BOT_LOOP_TICK in
    // >180s. Cancelled in stopBot() / startBot crash paths.
    private var loopHeartbeatJob: Job? = null
    /** V5.9.756 â€” Emergent CRITICAL ticket item #4: periodic live-wallet reconciler.
     *  Forensics 2026-05-15: reconciler.totalChecked = 0 even with 3 live host
     *  positions. The per-cycle reconcileNow was being throttled (30 s gap) +
     *  the cycle was apparently not running at the time, so the host wallet
     *  was never being read. This dedicated job ticks every 10 s WHEN
     *  status.running == true AND live mode AND open live positions exist.
     *  Cancelled by stopBot via reconcilerJob?.cancel(). */
    private var reconcilerJob: Job? = null

    /** V5.9.905 â€” HIGH-FREQUENCY EXIT MANAGER.
     *
     * Operator V5.9.899 forensics: MOON peaked at +169% then round-tripped
     * to -27.8% in 3 minutes. PURPLECUP at -45.1% with no STRICT_SL fire.
     * TRUE at -23.8% past the -20% SL still open. Root cause: the bot
     * loop completed only 4 ticks across 319s of uptime (avg cycle 8.1s,
     * max 14.1s) because degraded API keys (birdeye blank, helius
     * placeholder) blew up per-token feature fetches. With the loop
     * stalled, sweepUniversalExits â€” the only place that calls
     * executor.runManageOnly for STRICT_SL / partial ladder / profit
     * lock / peak drawdown â€” only fired 4 times in 5 minutes.
     *
     * The doctrine fix: exit management MUST NOT depend on scanner
     * throughput. This dedicated coroutine ticks every 2s on its own
     * IO dispatcher slot. It only walks open positions and invokes
     * executor.runManageOnly on each. NO scanner work, NO feature
     * fetches, NO entry decisions. Reads ts.lastPrice from the shared
     * TokenState (updated in parallel by scanner / WS feeds) and lets
     * the executor's own getActualPrice resolver decide what's actionable.
     *
     * Cancelled in stopBot() / startBot crash paths.
     */
    private var hotExitJob: Job? = null
    private val hotExitCoverageCursor6663 = java.util.concurrent.atomic.AtomicInteger(0)
    private val fullExitCoverageCursor6663 = java.util.concurrent.atomic.AtomicInteger(0)
    private val manageExitCoverageCursor6663 = java.util.concurrent.atomic.AtomicInteger(0)

    /** Bounded, rotating coverage prevents a stable canonical list from always
     * starting at index zero.  With a large inventory, one slow early position
     * can no longer starve every position behind it from mid-hold and exit
     * management. */
    private fun <T> rotatingExitSlice6663(
        items: List<T>,
        maxItems: Int,
        cursor: java.util.concurrent.atomic.AtomicInteger,
    ): List<T> {
        if (items.isEmpty() || maxItems <= 0) return emptyList()
        if (items.size <= maxItems) return items
        val start = Math.floorMod(cursor.getAndAdd(maxItems), items.size)
        return List(maxItems) { offset -> items[(start + offset) % items.size] }
    }



    // V5.9.1313 â€” HOT-EXIT HEAL HELPER (extracted out of botLoop).
    // Returns true if the hot path was stale and this call handled the exit
    // sweep (resurrected hotExit + forced the inline backup sweep); false if
    // hotExit looks healthy and the caller should run its normal defer logic.
    // Kept as a plain (non-suspend) member fun so botLoop's coroutine state
    // machine stays small â€” inlining this logic overflowed the JVM back-end's
    // method transformer ("Couldn't transform method node: botLoop").
    private fun maybeHealHotExit(loopCount: Int, openCount: Int, nowMs: Long): Boolean {
        if (openCount <= 0) {
            // No open positions â†’ nothing to protect. Clear any stale episode so the NEXT
            // real stall logs cleanly (once).
            hotExitStaleEpisodeActive = false
            return false
        }
        val staleMs = nowMs - lastTickExitSweepMs
        // V5.9.1318 (Item 1) â€” operator doctrine: if hot exit is stale > 10s, force-reset
        // and run the universal-SL backup INDEPENDENTLY. Threshold lowered 12s â†’ 10s.
        val stale = lastTickExitSweepMs > 0L && staleMs >= 10_000L
        val neverRan = lastTickExitSweepMs == 0L && (nowMs - botLoopStartedAtMs) >= 10_000L
        if (!stale && !neverRan) {
            // Hot exit is healthy again â†’ end the stale episode so the next stall re-latches.
            if (hotExitStaleEpisodeActive) {
                hotExitStaleEpisodeActive = false
                try { ForensicLogger.lifecycle("HOT_EXIT_RECOVERED", "loop=$loopCount staleMs=$staleMs") } catch (_: Throwable) {}
            }
            return false
        }
        // We are in a stale episode. Recovery work runs EVERY loop (positions must stay
        // protected), but the loud STALE_RESET log + counter fires ONCE per episode only.
        val firstInEpisode = !hotExitStaleEpisodeActive
        if (firstInEpisode) {
            hotExitStaleEpisodeActive = true
            hotExitStaleResetCount += 1
            try {
                ForensicLogger.lifecycle(
                    "EXIT_COORDINATOR_STALE_RESET",
                    "loop=$loopCount open=$openCount hotExitLockAgeMs=$staleMs neverRan=$neverRan resets=$hotExitStaleResetCount â€” force-reset hot-exit lease + independent universal-SL backup",
                )
            } catch (_: Throwable) {}
            // V5.9.1324 â€” P1-7 surgical: structured stale-reset reason for snapshot.
            try {
                val structuredReason = when {
                    neverRan -> "NEVER_RAN_AFTER_BOT_START"
                    staleMs >= 60_000L -> "LOCK_AGE_>=60s"
                    staleMs >= 30_000L -> "LOCK_AGE_>=30s"
                    staleMs >= 20_000L -> "LOCK_AGE_>=20s"
                    else -> "LOCK_AGE_>=10s"
                }
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_COORDINATOR_STALE_RESET_REASON_$structuredReason")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_COORDINATOR_OPEN_POSITIONS_AT_STALE_${minOf(openCount, 50)}")
                // V5.9.1361 P0.6 â€” deterministic stale release telemetry. The lease
                // is force-reset below (ensureHotExitAlive + independent universal-SL
                // backup request), so the stale hold IS being released every episode;
                // this counter makes that release visible/trendable toward zero.
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_COORD_STALE_RELEASED")
            } catch (_: Throwable) {}
        }
        // Force-reset the hot-exit lease/lock and resurrect the manager.
        try { ensureHotExitAlive() } catch (_: Throwable) {}
        // V5.9.1522 â€” P0: sell reconciler is mandatory in live; resurrect if down/zombie.
        try { ensureSellReconcilerAlive() } catch (_: Throwable) {}
        // V5.9.1470 (spec item 4) â€” SINGLE-FLIGHT COALESCE. The old code re-requested the
        // coordinator EVERY loop while stale, even though full+universal were already
        // pending/running â€” producing the HOT_EXIT_STALE_RESET storm (pendingFull=true,
        // pendingUniversal=true re-enqueued endlessly). Only (re)request the sweep when
        // there is NOT already a pending one; an in-flight coordinator will drain it.
        val alreadyPending = try { fullExitSweepPending.get() && universalSlSweepPending.get() } catch (_: Throwable) { false }
        if (!alreadyPending) {
            try { requestExitSweepCoordinator(reason = "HOT_EXIT_STALE_RESET", full = true, universal = true) } catch (_: Throwable) {}
        } else {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HOT_EXIT_STALE_COALESCED") } catch (_: Throwable) {}
        }
        return true
    }

    // V5.9.1313 â€” RESTARTABLE / SELF-HEALING HOT-EXIT MANAGER.
    // ROOT CAUSE (operator log session a6f08d82): bot stuck holding open=10
    // pump.fun micros (vol1h=$0) with position age climbing 7sâ†’58s, ZERO sells,
    // ZERO new entries â€” "it doesn't keep trading". The hotExit 2s manager (the
    // ONLY path that calls executor.runManageOnly â†’ STRICT_SL / partials / profit
    // lock / STALE_FEED_EVICT) had gone silent: not a single "_hotExit tick="
    // heartbeat in 90s of logs. The job had died/been cancelled, and because it
    // was launched INLINE in startBot with NO watchdog, nothing restarted it.
    // Meanwhile the botLoop kept blindly deferring every cycle's exits to the
    // dead hot path (EXIT_SWEEP_DEFERRED_TO_HOT_EXIT), so positions could never
    // exit and the 10 slots stayed pinned â†’ forcedOpen=10 forever â†’ no volume.
    //
    // FIX: own the launch in a restartable function. Idempotent â€” if the job is
    // still alive it's a no-op. If dead/null, relaunch the identical body with a
    // freshly-loaded cfg. The botLoop deferral guard calls this whenever the hot
    // path looks stale, so a dead manager is resurrected within one loop cycle.
    @Volatile private var hotExitLastResurrectMs: Long = 0L
    // V5.9.1318 (Item 1) â€” stale-episode latch. HOT_EXIT_STALE_FORCING_INLINE_SWEEP was
    // firing EVERY loop while stale; the operator wants it ONCE per episode. We latch when
    // we enter a stale episode and only re-arm after a healthy sweep is observed.
    @Volatile private var hotExitStaleEpisodeActive: Boolean = false
    @Volatile private var hotExitStaleResetCount: Long = 0L
    // V5.9.1522 â€” LIVE EXECUTION FINALISATION: reconciler is MANDATORY in live.
    // Extracted from botLoop startup so the P0 watchdog can re-invoke it after a
    // deferred (wallet-not-ready) start or a detected zombie state.
    private fun startSellReconciler(cfg: com.lifecyclebot.data.BotConfig, runtimeGeneration: Long) {
        // V5.9.764 â€” EMERGENT CRITICAL item C: start the SellReconciler
        // watchdog. Runs every 10s in LIVE mode only; scans the host
        // wallet's open tracked positions, force-releases stale sell
        // locks past LOCK_TTL_MS, and re-queues stuck full-exits.
        // No-op if cfg.paperMode==true (the reconciler returns early).
        try {
            com.lifecyclebot.engine.sell.SellReconciler.start(
                scope = scope,
                isPaperMode = cfg.paperMode,
                hostWallet = wallet,
                // V5.9.779 â€” EMERGENT MEME-ONLY: SellReconciler now actively
                // triggers a sell via the executor when it requeues. If the
                // mint is missing from status.tokens (e.g. after a stopBot
                // trim or service kill), we rehydrate a minimal TokenState
                // from the host wallet tracker so the bot can still sell
                // tokens the user holds without restarting the watchlist.
                sellTrigger = { mint, symbol, balance ->
                    DownstreamWorkQueue.reconciliation("reconciler_sell_trigger", mint) {
                        try {
                            val existing = synchronized(status.tokens) { status.tokens[mint] }
                            val ts = existing ?: rehydrateTokenStateFromTracker(mint, symbol, balance)
                            if (ts != null) {
                                val curWallet = WalletManager.getWallet()
                                val curSol = walletManager.state.value.solBalance
                                val trackerStatus = try { com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(mint)?.status?.name ?: "UNKNOWN" } catch (_: Throwable) { "UNKNOWN" }
                                val requeueReason = "RECONCILER_REQUEUE_${trackerStatus}"
                                executor.requestSell(
                                    ts,
                                    requeueReason,
                                    curWallet,
                                    curSol,
                                )
                                try {
                                    ForensicLogger.lifecycle(
                                        "RECONCILER_SELL_TRIGGERED",
                                        "mint=${mint.take(10)} symbol=$symbol balance=$balance trackerStatus=$trackerStatus reason=$requeueReason rehydrated=${existing == null} async=downstream",
                                    )
                                } catch (_: Throwable) {}
                            }
                        } catch (e: Throwable) {
                            ErrorLogger.warn("BotService", "sellTrigger error: ${e.message?.take(80)}")
                        }
                    }
                },
                // V5.9.1496 â€” ZERO-BALANCE CLOSE FINALITY. When the reconciler
                // debounces a zero-balance OPEN_TRACKING row to CLOSED, finish
                // the finality chain: release any lane-primary election the mint
                // still holds (so dup/reentry logic is not polluted by an
                // already-closed mint) and surface the close. We do NOT re-train
                // here: the real sell path already recorded the trade outcome
                // when the position actually sold (that is WHY balance is now 0);
                // re-recording would double-count and corrupt analytics. The
                // PositionCloseLedger stamp inside confirmZeroBalanceClose is the
                // authoritative trainable close record.
                onZeroClose = { mint, symbol, sig ->
                    DownstreamWorkQueue.reconciliation("reconciler_zero_close", mint) {
                        try {
                            val laneGuess = try {
                                synchronized(status.tokens) { status.tokens[mint]?.position?.tradingMode }
                            } catch (_: Throwable) { null }
                            // Release primary across the lanes this mint could hold.
                            val lanesToRelease = listOfNotNull(
                                laneGuess,
                                "SHITCOIN", "MOONSHOT", "BLUECHIP", "QUALITY",
                                "TREASURY", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER",
                                "EXPRESS", "CORE",
                            ).distinct()
                            for (ln in lanesToRelease) {
                                try {
                                    com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(
                                        mint, ln, "ZERO_BALANCE_RECONCILER_CLOSE",
                                    )
                                } catch (_: Throwable) {}
                            }
                            try {
                                ForensicLogger.lifecycle(
                                    "RECONCILER_ZERO_CLOSE_FINALIZED",
                                    "mint=${mint.take(10)} symbol=$symbol sig=${sig ?: "none"} lanesReleased=${lanesToRelease.size} async=downstream",
                                )
                            } catch (_: Throwable) {}
                        } catch (e: Throwable) {
                            ErrorLogger.warn("BotService", "onZeroClose error: ${e.message?.take(80)}")
                        }
                    }
                },
                onHealWalletHeld = { heldMints ->
                    DownstreamWorkQueue.reconciliation("reconciler_heal_wallet_held", heldMints.firstOrNull().orEmpty()) {
                        try { healWalletHeldIntoLiveStore(heldMints) }
                        catch (e: Throwable) { ErrorLogger.warn("BotService", "onHealWalletHeld error: ${e.message?.take(80)}") }
                    }
                },
            )
            BotRuntimeController.markSellReconcilerStarted(runtimeGeneration, com.lifecyclebot.engine.sell.SellReconciler.isStarted)
            try {
                val r = com.lifecyclebot.engine.sell.SellReconciler
                val age = if (r.lastTickAtMs > 0L) System.currentTimeMillis() - r.lastTickAtMs else -1L
                ForensicLogger.lifecycle("SELL_RECONCILER", "running=${r.isStarted} ticks=${r.totalTicks} lastTickAgeMs=$age paperMode=${cfg.paperMode} gen=$runtimeGeneration")
                PipelineHealthCollector.labelInc(if (r.isStarted) "SELL_RECONCILER_RUNNING" else "SELL_RECONCILER_NOT_STARTED")
            } catch (_: Throwable) {}
            if (!cfg.paperMode) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { kotlinx.coroutines.delay(12_000L) } catch (_: Throwable) {}
                    val r = com.lifecyclebot.engine.sell.SellReconciler
                    val age = if (r.lastTickAtMs > 0L) System.currentTimeMillis() - r.lastTickAtMs else Long.MAX_VALUE
                    val healthy = r.isStarted && r.totalTicks > 0L && age < 30_000L
                    if (!healthy) {
                        try {
                            ForensicLogger.lifecycle("SELL_RECONCILER_LIVE_STARTUP_HARD_FAIL", "running=${r.isStarted} ticks=${r.totalTicks} lastTickAgeMs=$age gen=$runtimeGeneration")
                            PipelineHealthCollector.labelInc("SELL_RECONCILER_LIVE_STARTUP_HARD_FAIL")
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (e: Throwable) {
            BotRuntimeController.markSellReconcilerStarted(runtimeGeneration, false)
            ErrorLogger.warn("BotService", "SellReconciler start failed: ${e.message}")
        }

        // V5.0.3746 â€” BALANCE PROOF POLLER (operator spec items 2 + 5 + 10).
        // Non-blocking per-mint poller that owns mints in WAITING_BALANCE_PROOF.
        // Lives only in LIVE mode (paper has no on-chain balance to resolve).
        try {
            com.lifecyclebot.engine.sell.BalanceProofPoller.start(
                scope = scope,
                isPaperMode = cfg.paperMode,
                hostWallet = wallet,
                onProofReady = { mint, symbol, reason ->
                    DownstreamWorkQueue.retry("balance_proof_ready_enqueue", mint) {
                        try {
                            // Verified raw amount now exists in SellAmountAuthority's
                            // RPC resolution. Push back into PendingSellQueue as an
                            // ACTIVE retry; BotService's pending-sell processor will
                            // invoke executor.requestSell which acquires a fresh
                            // CloseLease and broadcasts under the verified amount.
                            com.lifecyclebot.engine.PendingSellQueue.add(mint, symbol, reason)
                            try {
                                ForensicLogger.lifecycle(
                                    "BALANCE_PROOF_ENQUEUED_ACTIVE_SELL",
                                    "mint=${mint.take(10)} symbol=$symbol reason=$reason async=downstream",
                                )
                            } catch (_: Throwable) {}
                        } catch (e: Throwable) {
                            ErrorLogger.warn("BotService", "onProofReady error: ${e.message?.take(80)}")
                        }
                    }
                },
                onZeroConfirmed = { mint, symbol, reason ->
                    DownstreamWorkQueue.verification("balance_proof_zero_confirmed", mint) {
                        try {
                            // V5.0.3749 â€” zero proof may close only through the tracker
                        // finality state machine. Do not release lanes / stamp close
                        // just because the poller saw a zero-like state.
                        try {
                            com.lifecyclebot.engine.HostWalletTokenTracker.recordIndependentZeroBalanceProof(
                                mint = mint,
                                sources = setOf("BALANCE_PROOF_POLLER_ZERO_STREAK", "SELL_AMOUNT_AUTHORITY_NONEMPTY_MINT_ABSENT"),
                                reason = "CONFIRMED_ZERO_FINALITY_$reason",
                            )
                        } catch (_: Throwable) {}
                        val closed = try {
                            com.lifecyclebot.engine.HostWalletTokenTracker.confirmZeroBalanceClose(
                                mint = mint,
                                hasConfirmedSellSig = false,
                                reason = "CONFIRMED_ZERO_FINALITY_$reason",
                            )
                        } catch (_: Throwable) { null }
                        if (closed != null) {
                            try {
                                com.lifecyclebot.engine.sell.LivePositionCloseAuthority.finalizeClosed(
                                    mint = mint,
                                    symbol = symbol,
                                    signature = null,
                                    reason = "BALANCE_PROOF_POLLER_ZERO_$reason",
                                    source = "balance_proof_poller_zero",
                                )
                            } catch (_: Throwable) {}
                            val lanesToRelease = listOf(
                                "SHITCOIN", "MOONSHOT", "BLUECHIP", "QUALITY",
                                "TREASURY", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER",
                                "EXPRESS", "CORE",
                            )
                            for (ln in lanesToRelease) {
                                try { com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(mint, ln, "CLOSED_BY_CONFIRMED_ZERO") } catch (_: Throwable) {}
                            }
                            try { ForensicLogger.lifecycle("REAP_CLOSED_CONFIRMED_ZERO", "mint=${mint.take(10)} symbol=$symbol reason=$reason") } catch (_: Throwable) {}
                        } else {
                            try { ForensicLogger.lifecycle("REAP_SKIPPED_BALANCE_UNKNOWN", "mint=${mint.take(10)} symbol=$symbol reason=$reason no_independent_zero_finality_or_last_positive") } catch (_: Throwable) {}
                        }
                        } catch (e: Throwable) {
                            ErrorLogger.warn("BotService", "onZeroConfirmed error: ${e.message?.take(80)}")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            ErrorLogger.warn("BotService", "BalanceProofPoller start failed: ${e.message}")
        }
    }

    // V5.9.1522 â€” P0 WATCHDOG. Called every botLoop cycle. Guarantees the sell
    // reconciler is alive whenever the runtime is live & active. Covers three
    // illegal states the operator forensic export proved can occur:
    //   1. pendingLiveStart latched (start() ran before wallet ready) + wallet now present
    //   2. reconciler not started while walletHeldMints>0  â†’ P0, restart now
    //   3. live zombie: activeJobs>0 but totalTicks==0 (loop never advanced)
    @Volatile private var reconcilerWatchdogLastMs: Long = 0L
    private fun ensureSellReconcilerAlive() {
        val cfg = try { com.lifecyclebot.data.ConfigStore.load(applicationContext) } catch (_: Throwable) { return }
        if (cfg.paperMode) {
            // Paper reconciler is best-effort; start it if somehow down but no P0.
            if (!com.lifecyclebot.engine.sell.SellReconciler.isStarted && status.running) {
                val gen = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }
                try { startSellReconciler(cfg, gen) } catch (_: Throwable) {}
            }
            return
        }
        if (!status.running) return
        // debounce restarts (3s) so a transient race can't spawn a storm
        val now = System.currentTimeMillis()
        if (now - reconcilerWatchdogLastMs < 3_000L) return

        val recon = com.lifecyclebot.engine.sell.SellReconciler
        try {
            val age = if (recon.lastTickAtMs > 0L) System.currentTimeMillis() - recon.lastTickAtMs else -1L
            ForensicLogger.lifecycle("SELL_RECONCILER", "running=${recon.isStarted} ticks=${recon.totalTicks} lastTickAgeMs=$age pending=${recon.pendingLiveStart}")
        } catch (_: Throwable) {}
        val walletHeld = try { HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { 0 }
        val activeJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size } catch (_: Throwable) { 0 }
        val zombie = try { recon.isLiveZombie(activeJobs) } catch (_: Throwable) { false }
        val walletReady = try { WalletManager.getWallet() != null } catch (_: Throwable) { false }
        // V5.9.1582 â€” in LIVE, reconciler is mandatory while runtime is RUNNING,
        // even when PositionStore/tracker currently detects zero open positions. It
        // owns wallet truth healing and orphan cleanup; gating start on walletHeld>0
        // leaves sellReconcilerStarted=false and lets stale orphan penalties poison buys.
        val deadWhileLive = (!recon.isStarted || recon.pendingLiveStart) && walletReady
        val deadWhileHeld = (!recon.isStarted || recon.pendingLiveStart) && walletHeld > 0
        val pendingNowSatisfiable = recon.pendingLiveStart && walletReady

        if (zombie || deadWhileLive || deadWhileHeld || pendingNowSatisfiable) {
            reconcilerWatchdogLastMs = now
            val gen = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }
            try {
                ForensicLogger.lifecycle(
                    "SELL_RECONCILER_P0_RESTART",
                    "zombie=$zombie deadWhileLive=$deadWhileLive deadWhileHeld=$deadWhileHeld pendingSatisfiable=$pendingNowSatisfiable " +
                    "walletHeld=$walletHeld activeJobs=$activeJobs started=${recon.isStarted} totalTicks=${recon.totalTicks} walletReady=$walletReady",
                )
            } catch (_: Throwable) {}
            try { ErrorLogger.error("BotService", "ğŸš¨ P0 SELL_RECONCILER restart â€” walletHeld=$walletHeld activeJobs=$activeJobs started=${recon.isStarted} ticks=${recon.totalTicks}") } catch (_: Throwable) {}
            try { recon.stop() } catch (_: Throwable) {}
            try { startSellReconciler(cfg, gen) } catch (e: Throwable) {
                ErrorLogger.warn("BotService", "P0 reconciler restart failed: ${e.message}")
            }
            // nudge an immediate tick so totalTicks advances out of zombie band
            try { recon.requestImmediateTick() } catch (_: Throwable) {}
        }
    }

    private fun ensureHotExitAlive() {
        try {
            if (hotExitJob?.isActive == true) return
        } catch (_: Throwable) {}
        // Debounce resurrections so a transient race can't spawn a storm.
        val nowMs = System.currentTimeMillis()
        if (nowMs - hotExitLastResurrectMs < 3_000L) return
        hotExitLastResurrectMs = nowMs
        val cfg = try { com.lifecyclebot.data.ConfigStore.load(applicationContext) } catch (_: Throwable) { return }
        val runtimeGeneration = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }
        try {
            ForensicLogger.lifecycle(
                "HOT_EXIT_RESURRECTED",
                "prevActive=${hotExitJob?.isActive} running=${status.running} gen=$runtimeGeneration â€” relaunching 2s exit manager",
            )
        } catch (_: Throwable) {}
        try { hotExitJob?.cancel() } catch (_: Throwable) {}
        hotExitJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { kotlinx.coroutines.delay(2_000L) } catch (_: Throwable) {}
            var tick = 0L
            while (status.running) {
                tick++
                try {
                    val curWallet = WalletManager.getWallet()
                    val curSol = status.getEffectiveBalance(cfg.paperMode)
                    val openTokens = canonicalExitTokenSnapshot6512()
                    if (openTokens.isNotEmpty()) {
                        // V5.9.1196 â€” make hotExit the authoritative active
                        // exit-maintenance heartbeat. 3163 showed POST_SUPERVISOR
                        // and openPositionTick still launching duplicate sweeps
                        // while this 2s manager was already walking open positions,
                        // causing EXIT_SWEEP/UNIVERSAL_SL coalesced + reset storms.
                        // Updating this timestamp lets botLoop/openPositionTick defer
                        // to the hot path without weakening hard-floor management.
                        lastTickExitSweepMs = System.currentTimeMillis()
                        val managedThisTick6663 = rotatingExitSlice6663(
                            openTokens, maxItems = 24, cursor = hotExitCoverageCursor6663,
                        )
                        managedThisTick6663.forEach { ts ->
                            try {
                                executor.runManageOnly(ts, curWallet, curSol)
                            } catch (e: Exception) {
                                ErrorLogger.debug(
                                    "BotService",
                                    "hotExit(${ts.symbol}): ${e.message}",
                                )
                            }
                        }

                        // V5.0.5999 â€” WIRED: LaneTransitionManager consulted
                        // every 5 hot-exit ticks (~10s) per open position.
                        // Operator directive 2026-07-02: "layer and trader
                        // promotion is meant to be there for all lanes,
                        // layers and traders." This is the universal
                        // promotion pathway â€” +100% winners â†’ MOONSHOT,
                        // +25% established â†’ CashGen compounding, mcap-band
                        // rotation, PROJECT_SNIPER graduation, paused-lane
                        // rotation. Every open position gets to be considered
                        // for promotion/rotation without waiting on scanner
                        // cycle time. Non-blocking â€” decisions are logged +
                        // update ts.position.tradingMode; actual exit happens
                        // through the standard exit path.
                        if (tick % 5L == 0L) {
                            openTokens.forEach { ts ->
                                try {
                                    val entry = ts.position.entryPrice
                                    if (entry > 0.0 && ts.lastPrice > 0.0) {
                                        val pnlPct = (ts.lastPrice - entry) / entry * 100.0
                                        val currentLane = ts.position.tradingMode.ifBlank { "STANDARD" }
                                        val td = LaneTransitionManager.evaluate(currentLane, ts, pnlPct)
                                        if (td.decision != LaneTransitionManager.Decision.KEEP) {
                                            LaneTransitionManager.logTransition(ts.mint, ts.symbol, currentLane, td)
                                            // Apply PROMOTE/ROTATE by updating tradingMode.
                                            // EXIT is left to the standard exit sweep (already
                                            // running above via runManageOnly). This avoids
                                            // double-exit racing with the exit sweep.
                                            if ((td.decision == LaneTransitionManager.Decision.PROMOTE ||
                                                 td.decision == LaneTransitionManager.Decision.ROTATE) &&
                                                td.targetLane != null && td.targetLane != currentLane) {
                                                ts.position.tradingMode = td.targetLane
                                            }
                                        }
                                    }
                                } catch (_: Throwable) { /* transition eval must never break hotExit */ }
                            }
                        }

                        // V5.9.1196 â€” low-cadence orphan/treasury sweep backup.
                        // runManageOnly covers strict SL / partials / profit locks
                        // every 2s. The heavier full/universal sweeps are retained
                        // as a 30s backup only when their single-flight gate is free.
                        // This preserves orphan rescue without spawning reset storms.
                        if (tick % 15L == 0L) {
                            try {
                                if (!exitSweepInFlight.get()) {
                                    launchExitSweepAsync("HOT_EXIT_PERIODIC")
                                } else {
                                    ForensicLogger.lifecycle("HOT_EXIT_FULL_SWEEP_SKIPPED", "reason=already_in_flight tick=$tick open=${openTokens.size}")
                                }
                            } catch (_: Throwable) {}
                            try {
                                if (!slSafetyNetInFlight.get()) {
                                    launchUniversalSlSweepAsync(cfg, curWallet)
                                } else {
                                    ForensicLogger.lifecycle("HOT_EXIT_UNIVERSAL_SL_SKIPPED", "reason=already_in_flight tick=$tick open=${openTokens.size}")
                                }
                            } catch (_: Throwable) {}
                        }

                        // V5.9.940 â€” STEALTH MINT-BURN MONITOR producer side.
                        // Every 45 ticks (~90s) poll BirdeyeMintBurnMonitor for
                        // each open position. registerOpen is idempotent so we
                        // just call it every cycle (cheap put on existing key).
                        // check() is suspend â†’ launch on the same IO scope so
                        // it never blocks the runManageOnly path.
                        // Unregister mints whose positions have closed (sync diff).
                        if (tick % 45L == 0L) {
                            try {
                                val cfgSnap = cfg
                                val mintBurnKey = cfgSnap.birdeyeApiKey
                                if (mintBurnKey.isNotBlank()) {
                                    val openMintIds = openTokens.map { it.mint }.toSet()
                                    val registered = com.lifecyclebot.engine.BirdeyeMintBurnMonitor.openMintIds()
                                    // Drop mints whose positions have closed
                                    (registered - openMintIds).forEach {
                                        com.lifecyclebot.engine.BirdeyeMintBurnMonitor.unregisterClose(it)
                                    }
                                    // Register + poll each open position
                                    openTokens.forEach { ts ->
                                        val supply = if (ts.position.entryPrice > 0.0 && ts.position.entryMcap > 0.0)
                                            ts.position.entryMcap / ts.position.entryPrice
                                        else 1_000_000_000.0  // pump.fun default
                                        if (!com.lifecyclebot.engine.BirdeyeMintBurnMonitor.isRegistered(ts.mint)) {
                                            com.lifecyclebot.engine.BirdeyeMintBurnMonitor.registerOpen(ts.mint, supply)
                                        }
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                com.lifecyclebot.engine.BirdeyeMintBurnMonitor.check(ts.mint, mintBurnKey)
                                            } catch (_: Throwable) { /* fail-open */ }
                                        }
                                    }
                                }
                            } catch (_: Throwable) { /* fail-open */ }
                        }

                        if (tick % 30L == 0L) {
                            try {
                                ForensicLogger.phase(
                                    ForensicLogger.PHASE.EXIT_GATE,
                                    "_hotExit",
                                    "tick=$tick managed=${managedThisTick6663.size} canonicalOpen=${openTokens.size} openMints cursor=${hotExitCoverageCursor6663.get()}",
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "hotExit tick error: ${e.message}")
                }
                try { kotlinx.coroutines.delay(2_000L) } catch (_: Throwable) { break }
            }
            ErrorLogger.info("BotService", "hotExitJob loop exited (status.running=${status.running})")
        }
        try { BotRuntimeController.registerJob(runtimeGeneration, "hotExit", hotExitJob) } catch (_: Throwable) {}
    }

    private var notifIdCounter = 100

    override fun onCreate() {
        super.onCreate()
        instance = this
        // A service object reaching onCreate is the fresh owner requested by the
        // post-stop dispatcher.  It is now safe for ACTION_START to proceed.
        stopInProgress = false
        restartAfterStopDispatchPending6518 = false

        // Must call startForeground() within 5 seconds of startForegroundService() or Android
        // throws ForegroundServiceDidNotStartInTimeException. Do it here before any slow init.
        createChannels()
        startForeground(NOTIF_ID, buildRunningNotif())
        serviceForegroundActive6487 = true
        ensureRuntimeWakeLock6031("onCreate_after_startForeground")
        ensureRuntimeWifiLock6032("onCreate_after_startForeground")

        try {
            // Initialize error logger first so we can capture any init errors
            ErrorLogger.init(applicationContext)
            // V5.0.6515 â€” P0 STARTUP ANR REPAIR. This durable replay previously
            // called SharedPreferences.all and rebuilt up to 8,192 economic events,
            // positions, lots, duplicate refunds, and projections synchronously in
            // Service.onCreate() on Android's main thread. Large real-device history
            // therefore caused an immediate fatal ANR while clean CI installs passed.
            // Start foreground first (above), then perform the complete canonical
            // bootstrap on the service IO scope. startBot() is hard-barriered below:
            // no scanner/executor may run until this succeeds.
            canonicalBootstrapReady6515 = false
            canonicalBootstrapSucceeded6515 = false
            val canonicalCtx6515 = applicationContext
            canonicalBootstrapJob6515 = scope.launch(kotlinx.coroutines.CoroutineName("canonical-bootstrap-6515")) {
                val started6515 = android.os.SystemClock.elapsedRealtime()
                try {
                    val startCap6432 = try {
                        com.lifecyclebot.data.ConfigStore.load(canonicalCtx6515).paperSimulatedBalance
                    } catch (_: Throwable) { 11.76 }
                    com.lifecyclebot.engine.truth.EconomicEventSchema6464.init6486(canonicalCtx6515)
                    val durableEconomicEvents6486 = com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot()
                    com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.rebuildPaperFromEvents6486(durableEconomicEvents6486)
                    com.lifecyclebot.engine.truth.CanonicalLotQuantity6464.rebuildPaperFromEvents6486(durableEconomicEvents6486)
                    val ledgerRestored6487 = com.lifecyclebot.engine.truth.PaperAccountLedger6430
                        .initPersistent6487(canonicalCtx6515, startCap6432)
                    if (!ledgerRestored6487) {
                        val migrated6487 = com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
                            .migrateLegacyLedgerOnce6487(startCap6432)
                        if (!migrated6487) com.lifecyclebot.engine.truth.PaperAccountLedger6430.persistCurrent6487()
                    }
                    try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.setPaperCash(com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.cashSol(), "startup_paper_ledger_authority_6487") } catch (_: Throwable) {}
                    val inventoryRepair6490 = com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486.refundDuplicateActiveMintLots6490()
                    val repairedPaperPositions6490 = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().filter { it.mode == "paper" }
                    com.lifecyclebot.engine.EmergentGuardrails.rebuildFromCanonical6475(repairedPaperPositions6490)
                    com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.reconcileActiveFromCanonical6489(repairedPaperPositions6490)
                    try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.setPaperCash(com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.cashSol(), "startup_duplicate_inventory_repair_6490") } catch (_: Throwable) {}
                    com.lifecyclebot.engine.truth.IndependentReconcilerScheduler6431.start {
                        // The independent 30-second cadence is the durable
                        // account healer. Keep it off the trading loop so a
                        // choked meme cycle cannot also freeze reconciliation.
                        com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
                            .reconcileJournalAuthority6663()
                        com.lifecyclebot.engine.truth.ForensicReconciliation6635
                            .reconcile6635()
                    }
                    canonicalBootstrapSucceeded6515 = true
                    try {
                        ForensicLogger.lifecycle("CANONICAL_BOOTSTRAP_READY_6515", "events=${durableEconomicEvents6486.size} positions=${repairedPaperPositions6490.size} duplicateMints=${inventoryRepair6490.duplicateMints} durMs=${android.os.SystemClock.elapsedRealtime() - started6515} thread=${Thread.currentThread().name}")
                        PipelineHealthCollector.labelInc("CANONICAL_BOOTSTRAP_READY_6515")
                    } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    canonicalBootstrapSucceeded6515 = false
                    ErrorLogger.crash("BotService", "CANONICAL_BOOTSTRAP_FAILED_6515: ${t.javaClass.simpleName}: ${t.message}", t)
                    try {
                        ForensicLogger.lifecycle("CANONICAL_BOOTSTRAP_FAILED_6515", "type=${t.javaClass.simpleName} msg=${t.message?.take(120)} durMs=${android.os.SystemClock.elapsedRealtime() - started6515}")
                        PipelineHealthCollector.labelInc("CANONICAL_BOOTSTRAP_FAILED_6515")
                    } catch (_: Throwable) {}
                } finally {
                    canonicalBootstrapReady6515 = true
                }
            }
            // V5.9.666 â€” install Choreographer-based ANR / long-frame
            // detector so the in-app Pipeline Health panel captures
            // every main-thread stutter with elapsed delta. onCreate
            // runs on the main thread, so this is the right anchor
            // point. Idempotent.
            try { PipelineHealthCollector.installAnrWatcherOnMainThread() } catch (_: Throwable) {}
            // V5.9.495z8 â€” register canonical learning subscribers once at startup.
            // Idempotent: subsequent calls are no-ops. Wires FluidLearningAI
            // mirror + LayerReadinessRegistry samples to the canonical bus.
            // V5.0.6516 â€” COMPLETE STARTUP FAMILY REPAIR. Everything below
            // can load persisted JSON/SharedPreferences/SQLite, rebuild learning,
            // or start a trader. Running any of it inline in Service.onCreate()
            // makes startup scale with device history and can trigger Android's
            // fatal service ANR. Keep only foreground establishment, ErrorLogger,
            // and the Choreographer hook on main; run the full dependency graph on IO.
            serviceBootstrapReady6516 = false
            serviceBootstrapSucceeded6516 = false
            serviceBootstrapJob6516 = scope.launch(kotlinx.coroutines.CoroutineName("service-bootstrap-6516")) {
                val serviceStarted6516 = android.os.SystemClock.elapsedRealtime()
                val bootstrapPhase6516: (String) -> Unit = { phase ->
                    try {
                        ForensicLogger.lifecycle(
                            "SERVICE_BOOTSTRAP_PHASE_6516",
                            "phase=$phase durMs=${android.os.SystemClock.elapsedRealtime() - serviceStarted6516}",
                        )
                    } catch (_: Throwable) {}
                }
                try {
                    canonicalBootstrapJob6515?.join()
                    check(canonicalBootstrapReady6515 && canonicalBootstrapSucceeded6515) {
                        "canonical bootstrap unavailable"
                    }
                    bootstrapPhase6516("CANONICAL_READY")
                    FeeRetryQueue.init(applicationContext)
                    FeeAccumulator.init(applicationContext)
                    ScannerHardRejectStore.init(applicationContext)
                    CanonicalSubscribers.registerAll()
            // V5.9.1382 â€” ExternalAlphaFeeds: ADDITIVE smart-money + token-safety
            // feeders into CrossTalkFusionEngine. Signal-only, fail-open, off the
            // trade-critical path (own IO coroutine, 90s cadence). Never vetoes,
            // never touches scanner intake. Idempotent start.
            try { com.lifecyclebot.v4.meta.ExternalAlphaFeeds.start(scope) } catch (_: Throwable) {}

            // V5.9.948 â€” TokenMetaCache warm boot. Hydrates persisted token
            // metadata (symbol/pair/logo/pool/dex/last-snapshot) from disk
            // so the bot doesn't re-pay CU + latency to rediscover tokens
            // it's seen before. Best-effort: failure here never blocks
            // anything downstream. Runs off the main thread because cold
            // SQLite open can touch disk; main-thread anchor is the
            // foreground-service requirement, not this cache.
            try {
                val cacheCtx = applicationContext
                Thread {
                    try {
                        val cache = com.lifecyclebot.engine.TokenMetaCache.get(cacheCtx)
                        val n = cache.warmStart()
                        ErrorLogger.info("BotService", "TokenMetaCache warmStart hydrated=$n rows")
                        // Schedule periodic flush + prune.
                        Thread {
                            while (true) {
                                try { Thread.sleep(60_000L) } catch (_: InterruptedException) { return@Thread }
                                try { cache.flushNow() } catch (_: Throwable) {}
                                // V5.9.1470 (spec item 10) â€” evict cold low-hit rows once
                                // the live set grows past 2500 so the cache stays warm and
                                // relevant. Protected 500-token scanner pool is a separate
                                // store and is never touched.
                                try { cache.evictColdSoft(2500) } catch (_: Throwable) {}
                            }
                        }.apply { name = "TokenMetaCache-flush"; isDaemon = true }.start()
                    } catch (t: Throwable) {
                        ErrorLogger.warn("BotService", "TokenMetaCache warmStart failed: ${t.message}")
                    }
                }.apply { name = "TokenMetaCache-warm"; isDaemon = true }.start()
            } catch (_: Throwable) { /* fail-open */ }


            // V5.9.855 â€” passive API key validator. Flags known dead defaults
            // (Emergent Gemini placeholder + Helius "hive-pattern-learn") so
            // consumers gate off cleanly instead of burning a 401 RTT every
            // call. Consumers should call KeyValidator.recordResult(...)
            // after their HTTP and KeyValidator.isLive("...") before next call.
            try {
                val cfg = ConfigStore.load(applicationContext)
                KeyValidator.preflightConfig(
                    geminiKey  = cfg.geminiApiKey,
                    heliusKey  = cfg.heliusApiKey,
                    groqKey    = cfg.groqApiKey,
                    birdeyeKey = cfg.birdeyeApiKey,
                    walletAddress = cfg.walletAddress,
                    jupiterKey = cfg.jupiterApiKey,
                )
            } catch (_: Throwable) { /* preflight is best-effort */ }
            // V5.9.455 â€” ANR FIX.
            // Previously LlmLabEngine.start() ran synchronously on the main
            // thread during onCreate and opened SQLite + seeded strategies,
            // which contributed to the ~2-minute freeze users reported on
            // "Start Live". It's fully optional to the critical boot path
            // (the tick consumer is null-safe via ctxRef) so defer it.
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try { com.lifecyclebot.engine.lab.LlmLabEngine.start(applicationContext) } catch (_: Throwable) {}
            }
            ErrorLogger.info("BotService", "onCreate starting")


            strategy        = LifecycleStrategy(
                cfg   = { ConfigStore.load(applicationContext) },
                brain = { botBrain },
            )
            sentimentEngine = SentimentEngine { ConfigStore.load(applicationContext) }
            safetyChecker   = TokenSafetyChecker { ConfigStore.load(applicationContext) }
            walletManager   = WalletManager.getInstance(applicationContext)  // Use singleton
            soundManager    = SoundManager(applicationContext)
            currencyManager = CurrencyManager(applicationContext)
            notifHistory    = NotificationHistory(applicationContext)
            tradeJournal    = TradeJournal(applicationContext)
            autoMode        = AutoModeEngine(
                cfg         = { ConfigStore.load(applicationContext) },
                status      = status,
                onModeChange = { from, to, reason ->
                addLog("âš¡ MODE: ${from.label} â†’ ${to.label}  ($reason)")
                sendTradeNotif("Mode Switch", "${to.label}: $reason",
                    NotificationHistory.NotifEntry.NotifType.INFO)
                soundManager.setEnabled(ConfigStore.load(applicationContext).soundEnabled)
            }
        )
        copyTradeEngine = CopyTradeEngine(
            ctx          = applicationContext,
            onCopySignal = { mint, wallet, sol ->
                val c = ConfigStore.load(applicationContext)
                val ts = status.tokens[mint]
                if (ts != null && c.copyTradingEnabled) {
                    autoMode.triggerCopy(mint, wallet)
                    addLog("ğŸ“‹ COPY BUY triggered: ${mint.take(8)}â€¦ from ${wallet.take(8)}â€¦", mint)
                    // V5.9: also fire copy-perps trade on SOL via MarketsLiveExecutor
                    if (!c.paperMode && c.heliusApiKey.isNotBlank()) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val copySizeSol = (c.smallBuySol * 0.5).coerceIn(0.01, 0.5)
                                val copyPositionId6486 = "COPY_PERPS:${mint}:${System.currentTimeMillis()}"
                                val result = com.lifecyclebot.perps.MarketsLiveExecutor.executeLiveTradeProof6486(
                                    positionId  = copyPositionId6486,
                                    market      = com.lifecyclebot.perps.PerpsMarket.SOL,
                                    direction   = com.lifecyclebot.perps.PerpsDirection.LONG,
                                    sizeSol     = copySizeSol,
                                    leverage    = 2.0,
                                    priceUsd    = ts.lastPrice,
                                    traderType  = "CopyTrade"
                                )
                                addLog("ğŸ“‹ COPY PERPS: ${if (result.confirmed) "âœ… LONG SOL ${copySizeSol}â—" else "âŒ ${result.state}:${result.reason.take(48)}"}", mint)
                            } catch (e: Exception) {
                                ErrorLogger.warn("BotService", "Copy perps error: ${e.message}")
                            }
                        }
                    }
                }
            },
            onLog = { msg -> addLog(msg) }
        )
        // V5.9.455 â€” loadWallets() does SharedPreferences + JSON parsing.
        // Cheap in isolation but historically contributed to the onCreate
        // main-thread cost on users with many tracked copy wallets. The
        // engine returns null-safely when queried before this completes.
        scope.launch {
            try { copyTradeEngine.loadWallets() } catch (_: Exception) {}
        }
        securityGuard   = SecurityGuard(
            ctx       = applicationContext,
            cfg       = { ConfigStore.load(applicationContext) },
            onLog     = { msg -> addLog("ğŸ”’ SECURITY: $msg") },
            onAlert   = { title, body -> sendTradeNotif(title, body, NotificationHistory.NotifEntry.NotifType.INFO) },
        )
        executor = Executor(
            cfg       = { ConfigStore.load(applicationContext) },
            onLog     = ::addLog,
            onNotify  = { title, body, type -> sendTradeNotif(title, body, type) },
            onToast   = { msg -> showToast(msg) },
            security  = securityGuard,
            sounds    = soundManager,
        )
        
        // Initialize FluidLearning for paper mode simulation
        val cfg = ConfigStore.load(applicationContext)
        FluidLearning.init(applicationContext, cfg.paperSimulatedBalance)
        
        // Initialize TradeHistoryStore for persistent trade stats
        TradeHistoryStore.init(applicationContext)

        // V5.9.438 â€” durable outcome-learning trackers across restarts.
        try { LearningPersistence.init(applicationContext) } catch (_: Exception) {}
        try {
            val replayedFinality6486 = com.lifecyclebot.engine.truth.CanonicalFinalityPersistence6486.initAndReplay(applicationContext)
            if (replayedFinality6486 > 0) PipelineHealthCollector.labelInc("DURABLE_FINALITY_REPLAYED_6486")
        } catch (t: Throwable) {
            try { ForensicLogger.lifecycle("DURABLE_FINALITY_REPLAY_FAILED_6486", t.message.orEmpty().take(120)) } catch (_: Throwable) {}
        }

        // V5.0.6382 â€” COLD-BOOT TACTIC RE-DERIVE. Purges phantom Î¼ drift from
        // pre-V5.0.6373d expectancy math (Î¼=+159% at 15% WR was blocking
        // rotation of broken tactics). Runs once per boot; fail-soft.
        try { com.lifecyclebot.engine.learning.TacticSwitcher.rederiveFromRawJournal6382() } catch (_: Throwable) {}

        // V5.0.6386 â€” HISTORICAL QUARANTINE (Section 10 of directive).
        // Reads the raw journal, tags every live row matching any of the 12
        // corruption criteria, and emits HISTORICAL_QUARANTINE_6386_* counters.
        // Downstream truth-model consumers must ignore quarantined rows.
        try { com.lifecyclebot.engine.truth.HistoricalQuarantine6386.runOnce() } catch (_: Throwable) {}

        // V5.0.6387 â€” FALSE-PROFIT HISTORICAL QUARANTINE + LEGACY_PRE_CANONICAL
        // tagging (Directive B P0 + Directive A P0 "Journal migration without
        // deleting forensics"). Tags every existing row LEGACY_PRE_CANONICAL_6387
        // and flags rows whose profit exit reasons contradict realised PnL.
        try { com.lifecyclebot.engine.truth.FalseProfitHistoricalQuarantine6387.runOnce() } catch (_: Throwable) {}
        // V5.0.4307 â€” report-only runtime proof for smart/dormant-system registry
        // and closeout sentinels. No scanner, FDG, sizing, routing, wallet, or
        // execution authority; this only makes theatre-vs-runtime visible.
        try { SmartSystemRuntimeRegistry.emitStartupProof() } catch (_: Throwable) {}
        bootstrapPhase6516("CORE_STORES_READY")


        // V5.9.69: Initialize PatternClassifier â€” online logistic-regression
        // pattern brain that learns from every closed trade.
        try { PatternClassifier.init(applicationContext) } catch (_: Exception) {}

        // V5.9.75: Initialize VoiceManager (mute-by-default).
        try { VoiceManager.init(applicationContext) } catch (_: Exception) {}
        
        // V5.9.455 â€” ANR FIX.
        // BehaviorAI.loadFromHistory() scans every historical trade from
        // SQLite + rebuilds the rolling behaviour vector. On a 4000+ trade
        // history this can take multiple seconds and used to run on the
        // main thread during onCreate. Move it off-main; the tick loop is
        // tolerant of a not-yet-loaded BehaviorAI (its query paths all
        // return neutral defaults until loaded).
        scope.launch {
            try {
                com.lifecyclebot.v3.scoring.BehaviorAI.init(applicationContext)
                com.lifecyclebot.v3.scoring.BehaviorAI.loadFromHistory()
                ErrorLogger.info("BotService", "BehaviorAI initialized and loaded from trade history (off-main)")
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "BehaviorAI init/load error: ${e.message}")
            }
        }
        
        // Initialize GeminiCopilot with API key from config
        if (cfg.geminiApiKey.isNotBlank()) {
            GeminiCopilot.init(cfg.geminiApiKey)
            ErrorLogger.info("BotService", "GeminiCopilot initialized with API key")
            // V5.9.361 â€” mirror the universal LLM key into VoiceManager's TTS
            // slot so the existing per-persona OpenAI voices (Cleetus â†’ onyx
            // + Florida-redneck instructions etc.) actually take effect.
            // Without this mirror the bot was silently falling back to
            // Android TTS (one default female voice for everyone).
            try { VoiceManager.ensureRemoteKeyMirroredFromGemini(applicationContext, cfg.geminiApiKey) } catch (_: Exception) {}

            // V5.9.915 â€” wire LLM fallback chain (groq â†’ openrouter â†’ cerebras).
            // Previously GeminiCopilot's openRouterApiKey / cerebrasApiKey
            // slots were ALWAYS blank because nothing called
            // configureFallbackApis() at boot. The fallback chain in
            // pickProviderOrder() then silently skipped both providers,
            // leaving Gemini as the sole LLM path and producing 429
            // backoff cascades whenever the Emergent proxy throttled.
            // With hardcoded keys in BotConfig we now have all four
            // providers live by default.
            try {
                GeminiCopilot.configureFallbackApis(
                    openRouterApiKey = cfg.openRouterApiKey,
                    groqApiKey       = cfg.groqApiKey,
                    cerebrasApiKey   = cfg.cerebrasApiKey,
                    mistralApiKey    = cfg.mistralApiKey,
                )
                ErrorLogger.info(
                    "BotService",
                    "ğŸ”‘ LLM fallback chain wired: groq=${cfg.groqApiKey.isNotBlank()} " +
                    "openrouter=${cfg.openRouterApiKey.isNotBlank()} " +
                    "cerebras=${cfg.cerebrasApiKey.isNotBlank()}"
                )
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "configureFallbackApis failed: ${e.message}")
            }
        }

        // V5.9.129: Start the Sentience loop â€” LLM â†” Personality â†” Symbolic feedback.
        // Runs every 6 min, reflects on live state, mutates traits + symbolic
        // composites, injects autonomous thoughts into the stream. Guarded by
        // tight clamps (Â±0.06 traits, Â±0.08 symbolic per cycle).
        try {
            SentienceOrchestrator.start(applicationContext)
            // V5.0.6073 â€” SSI PILOT: the LLM council flies the plane between
            // control-tower checkpoints. Fuses sentience symbolic state, lane
            // truth, meta-cognition and lab summary into a bounded directive
            // (size bias, lane focus/avoid, exit patience) every 5 minutes.
            try { SsiPilotCouncil.start() } catch (_: Throwable) {}
            ErrorLogger.info("BotService", "ğŸŒŒ SentienceOrchestrator started")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "SentienceOrchestrator start error: ${e.message}")
        }
        
        // V5.6: Initialize On-Device ML Engine for trade predictions
        try {
            com.lifecyclebot.ml.OnDeviceMLEngine.initialize(applicationContext)
            // V5.9.1255 â€” wire symbolic rule-trust persistence (self-revising rules
            // must survive restarts or they never mature).
            com.lifecyclebot.engine.SymbolicExitReasoner.attachContext(applicationContext)
            try { com.lifecyclebot.engine.AutonomousMetaPolicy.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.9.1260
            try { com.lifecyclebot.engine.ForwardOutcomeModel.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.9.1261
            try { com.lifecyclebot.engine.SignalQualityTracker.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.9.1271
            try { com.lifecyclebot.engine.UnifiedPolicyHead.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.9.1262
            try { com.lifecyclebot.engine.UnifiedExitPolicyHead.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.0.4095
            try { com.lifecyclebot.engine.LayerBrain.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.0.4111 â€” per-layer learning
            try { com.lifecyclebot.engine.StrategyHypothesisEngine.attachContext(applicationContext) } catch (_: Throwable) {}  // V5.9.1263
            ErrorLogger.info("BotService", "ğŸ§  ML Engine initialized | ${com.lifecyclebot.ml.OnDeviceMLEngine.getStatus()}")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "ML Engine init error: ${e.message}")
        }
        
        // V5.6.9: Initialize Position Persistence for crash recovery
        try {
            PositionPersistence.init(applicationContext)
            val persistedCount = PositionPersistence.getPersistedCount()
            if (persistedCount > 0) {
                ErrorLogger.info("BotService", "ğŸ’¾ Position Persistence initialized | $persistedCount positions saved")
            } else {
                ErrorLogger.info("BotService", "ğŸ’¾ Position Persistence initialized | No saved positions")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Position Persistence init error: ${e.message}", e)
        }

        // V5.0.6323 â€” Rehydrate the CanonicalBuyFillRegistry from disk so
        // a process restart preserves the immutable on-chain entry basis
        // for every open position. Without this, downstream code
        // (position card, sell journal override, partial-sell toast)
        // would fall back to pos.entryPrice which can drift after lane
        // reclassification, producing the "weird shit with prices"
        // divergence the operator flagged on Pilly.
        try {
            com.lifecyclebot.engine.CanonicalBuyFillRegistry.init(applicationContext)
            val canonCount = com.lifecyclebot.engine.CanonicalBuyFillRegistry.activeCount()
            ErrorLogger.info("BotService", "ğŸ”’ CanonicalBuyFillRegistry initialized | $canonCount fills restored")
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "CanonicalBuyFillRegistry init error: ${e.message}")
        }

        // V5.0.6344 â€” immutable FillLotLedger init/rehydrate. Restores the
        // append-only lot ledger keyed by (wallet, mint, buyTxSig) so cost
        // basis survives process restart. Feeds [RealizedPnlConduit6344].
        try {
            com.lifecyclebot.engine.FillLotLedger6344.init(applicationContext)
            val lotCount = com.lifecyclebot.engine.FillLotLedger6344.activeCount()
            ErrorLogger.info("BotService", "ğŸ”’ FillLotLedger6344 initialized | $lotCount lots restored")
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "FillLotLedger6344 init error: ${e.message}")
        }

        // V5.0.6328 â€” LiveEntrySafetyHold.init rehydrates the persisted
        // governor window cutoff so canonical trade history from prior
        // sessions still feeds the governor sample. Without this, every
        // restart threw away the WADDLE-era cutoff and re-quarantined
        // every earlier canonical row.
        try {
            com.lifecyclebot.engine.LiveEntrySafetyHold.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "LiveEntrySafetyHold init error: ${e.message}")
        }

        // V5.0.6238 â€” Live Win DNA Store: transferable knowledge base of winning
        // fingerprints (setup, chart pattern, source/lane/phase route, hold time,
        // exit reason) that every AGI/LLM/SSI/meta-cog/sentience brain can read
        // as a shared bias signal. Feeds compound-growth mentality.
        try {
            com.lifecyclebot.engine.LiveWinDNAStore.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "LiveWinDNAStore init error: ${e.message}")
        }

        // V5.0.6302 â€” HISTORICAL CORPUS SEED (asset) + DAILY REFRESH.
        // Operator directive: on cold-start, upsert the packaged
        // `assets/historical_corpus.jsonl.gz` into LiveWinDNAStore so fresh
        // installs and post-reinstall wipes still have real Solana shape
        // samples. Idempotent: rows are keyed by synthetic ts derived from
        // row index, so re-seeding every boot upserts the same keys instead
        // of duplicating. Then DailyCorpusRefresher pulls fresh DexScreener
        // data on-device once/24h so the corpus evolves without depending
        // on CI runs.
        try {
            com.lifecyclebot.engine.HistoricalCorpusSeeder.seedFromAssetsAsync(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "HistoricalCorpusSeeder start error: ${e.message}")
        }
        try {
            com.lifecyclebot.engine.DailyCorpusRefresher.start(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "DailyCorpusRefresher start error: ${e.message}")
        }

        // V5.0.6246 â€” DeadTokenQuarantine: load the persistent set of mints
        // the bot has permanently given up on (unroutable / unsellable).
        // Operator directive: "just quarantine them permanently if the bot
        // cant sell them." Loading here means the ghosts stay skipped
        // across restarts instead of every boot re-attempting to price them.
        try {
            com.lifecyclebot.engine.DeadTokenQuarantine.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "DeadTokenQuarantine init error: ${e.message}")
        }

        // V5.0.6247 â€” LiveLaneGovernor: load persistent per-lane pause state
        // so a hard-paused bleeder lane stays paused across restarts until
        // its window expires or shadow-paper proves recovery.
        try {
            com.lifecyclebot.engine.LiveLaneGovernor.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "LiveLaneGovernor init error: ${e.message}")
        }

        // V5.9.256: Initialize wallet token memory â€” persistent journal of all buys
        // Survives restarts/updates; used by StartupReconciler to recover positions
        // that the scanner hasn't re-discovered yet.
        try {
            WalletTokenMemory.init(applicationContext)
            val openMemory = WalletTokenMemory.getOpenEntries()
            if (openMemory.isNotEmpty()) {
                ErrorLogger.info("BotService", "ğŸ’¾ WalletTokenMemory: ${openMemory.size} open position(s) in journal: ${openMemory.joinToString(", ") { it.symbol }}")
            } else {
                ErrorLogger.info("BotService", "ğŸ’¾ WalletTokenMemory: no open positions in journal")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "WalletTokenMemory init error: ${e.message}", e)
        }

        // V5.9.262: Initialize live-trade end-to-end log store
        try { LiveTradeLogStore.init(applicationContext) } catch (_: Exception) {}

        // V5.9.495z10: Initialize HostWalletTokenTracker â€” canonical lifecycle
        // ledger keyed by mint. Independent of scanner watchlist; survives
        // restarts so the bot can never lose track of wallet-held tokens
        // (STRIKE / WCOR drift fix).
        try { HostWalletTokenTracker.init(applicationContext) } catch (_: Exception) {}

        // V5.9.495z24 â€” Initialize DynamicAltTokenRegistry with disk persistence
        // and start the background discovery loop. Operator: "the registry is
        // meant to be constantly finding new token mints and storing them in
        // persistent memory â€” should have 500+ already". Now hydrates from
        // disk on startup, runs DexScreener+CoinGecko+Jupiter discovery every
        // 5 min in the background, and persists after each cycle.
        try {
            com.lifecyclebot.perps.DynamicAltTokenRegistry.init(applicationContext)
            com.lifecyclebot.perps.DynamicAltTokenRegistry.startBackgroundDiscovery()
            addLog("ğŸª™ Token registry: ${com.lifecyclebot.perps.DynamicAltTokenRegistry.getStats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "DynamicAltTokenRegistry init error: ${e.message}", e)
        }

        // V5.9.495z25 â€” Initialize MemeMintRegistry. Persistent meme-mint
        // memory (parallel to DynamicAltTokenRegistry but for the meme
        // scanner). Survives restarts so the bot retains its discovery
        // history across sessions and decisions stay consistent across
        // trades on the same mint.
        try {
            com.lifecyclebot.engine.MemeMintRegistry.init(applicationContext)
            addLog("ğŸª™ Meme mint registry: ${com.lifecyclebot.engine.MemeMintRegistry.stats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "MemeMintRegistry init error: ${e.message}", e)
        }

        // V5.9.495z26 â€” Initialize TreasuryWalletManager. Auto-generates a
        // fresh on-chain Solana keypair on first launch, persists the private
        // key to EncryptedSharedPreferences, and exposes the treasury pubkey
        // + balance for the wallet UI. Live-mode profit splits are physically
        // transferred tradingâ†’treasury (see TreasuryManager.contributeFromMemeSell).
        try {
            com.lifecyclebot.engine.TreasuryWalletManager.init(applicationContext)
            val pk = com.lifecyclebot.engine.TreasuryWalletManager.publicKey()
            if (pk.isNotBlank()) {
                addLog("ğŸ¦ Treasury wallet: ${pk.take(8)}â€¦${pk.takeLast(4)}")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "TreasuryWalletManager init error: ${e.message}", e)
        }

        // V5.9.495z28 â€” TokenLifecycleTracker (operator's 10-item end-to-end
        // overhaul, item 3). Authoritative ledger keyed by mint covering
        // BUY_PENDING â†’ CLEARED + RESIDUAL_HELD/RECONCILE_FAILED. Persisted
        // and restored across restarts so positions can never be lost.
        try {
            com.lifecyclebot.engine.TokenLifecycleTracker.init(applicationContext)
            addLog("ğŸ“’ Lifecycle: ${com.lifecyclebot.engine.TokenLifecycleTracker.openCount()} open Â· ${com.lifecyclebot.engine.TokenLifecycleTracker.stats()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "TokenLifecycleTracker init error: ${e.message}", e)
        }

        // V5.9.495z29 â€” Configure LiveExecutionGate from the saved BotConfig
        // (operator spec item 8: throughput controls). The gate is a
        // single in-process semaphore + rate limiter every live BUY must
        // traverse; daily quota / concurrent ceiling / min spacing / pending
        // verification queues all live here. Reading the config once on
        // start is enough â€” config changes from the settings UI re-call this.
        try {
            val c = ConfigStore.load(applicationContext)
            com.lifecyclebot.engine.LiveExecutionGate.configure(
                com.lifecyclebot.engine.LiveExecutionGate.Config(
                    highThroughputLiveMode             = c.highThroughputLiveMode,
                    maxLiveTradesPerDay                = c.maxLiveTradesPerDay,
                    maxConcurrentLivePositions         = c.maxConcurrentLivePositions,
                    minSecondsBetweenLiveBuys          = c.minSecondsBetweenLiveBuys,
                    maxPendingBuyVerifications         = c.maxPendingBuyVerifications,
                    maxPendingSellVerifications        = c.maxPendingSellVerifications,
                    hotPathTimeoutMs                   = c.hotPathTimeoutMs,
                    walletReconcileTimeoutMs           = c.walletReconcileTimeoutMs,
                    skipSlowBackgroundScansWhenLiveBusy = c.skipSlowBackgroundScansWhenLiveBusy,
                )
            )
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "LiveExecutionGate configure error: ${e.message}", e)
        }
        bootstrapPhase6516("LEARNING_AND_EXECUTION_STORES_READY")
        
        // V5.6.28: Initialize CashGenerationAI for treasury persistence
        try {
            com.lifecyclebot.v3.scoring.CashGenerationAI.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "CashGenerationAI init error: ${e.message}", e)
        }
        
        // V5.6.28d: Initialize SmartSizer for streak persistence
        try {
            SmartSizer.init(applicationContext)
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "SmartSizer init error: ${e.message}", e)
        }
        
        // V5.6.29c: Initialize layer AI persistence
        try {
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.ShitCoinExpress.init(applicationContext)
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.QualityTraderAI.init(applicationContext)
            com.lifecyclebot.v3.scoring.ProjectSniperAI.init(applicationContext)
            // V5.0.4132 â€” DISCIPLINE PASS modules (rolling-WR pause, lane timeout,
            // rug-mint blacklist, scanner-lane bridge brain).
            com.lifecyclebot.engine.LivePauseButton.init(applicationContext)
            com.lifecyclebot.engine.LaneTimeoutGate.init(applicationContext)
            com.lifecyclebot.engine.RugMintBlacklist.init(applicationContext)
            com.lifecyclebot.engine.ScannerLaneBridge.init(applicationContext)
            ErrorLogger.info("BotService", "All layer AI persistence initialized | pause=${com.lifecyclebot.engine.LivePauseButton.tag()} rugBL=${com.lifecyclebot.engine.RugMintBlacklist.size()}")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Layer AI init error: ${e.message}", e)
        }
        
        // V5.7: Initialize PerpsTraderAI for leverage trading
        try {
            com.lifecyclebot.perps.PerpsTraderAI.init(applicationContext)
            ErrorLogger.info("BotService", "PerpsTraderAI initialized")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsTraderAI init error: ${e.message}", e)
        }
        
        // V5.7: Initialize PerpsLearningBridge for cross-layer perps intelligence
        try {
            com.lifecyclebot.perps.PerpsLearningBridge.init(applicationContext)
            // V5.9.368 â€” clear polluted non-directional layer stats once
            // (BehaviorAI/MetaCognitionAI/FluidLearningAI/etc were being
            // graded against directional outcome â€” produced nonsense
            // accuracy like BehaviorAI 1.3% on 2589 signals). One-shot.
            com.lifecyclebot.perps.PerpsLearningBridge.resetNonDirectionalCorrelationOnce()
            // V5.9.369 â€” initialize + restore leverage preferences for all
            // Markets-layer traders from SharedPreferences. Bug fix: each
            // trader's preferLeverage was a fresh AtomicBoolean(false) on
            // every boot, so user's LEVERAGE choice was being thrown away.
            try {
                com.lifecyclebot.engine.LeveragePreference.init(applicationContext)
                com.lifecyclebot.engine.LeveragePreference.restoreAllTraders()
            } catch (_: Exception) {}
            ErrorLogger.info("BotService", "PerpsLearningBridge initialized - ${com.lifecyclebot.perps.PerpsLearningBridge.getConnectedLayerCount()} layers connected")

            // V5.9.382 â€” one-time demotion wipe. The V5.9.374 uniformity
            // glitch poisoned layer accuracy stats (every MEME layer at
            // 20.2%), which triggered TradingCopilot's aggressive demotion
            // (half the brain silenced at 0.5Ã— weight), collapsing meme
            // WR from 33% â†’ 4%. Clear the inherited weight map once so the
            // brain starts fresh under the new gentler 0.80/0.90 curve.
            try {
                val p = getSharedPreferences("aate_bot_prefs", android.content.Context.MODE_PRIVATE)
                if (!p.getBoolean("poisoning_recal_v5_9_382", false)) {
                    com.lifecyclebot.engine.TradingCopilot.clearDemotionWeights()
                    p.edit().putBoolean("poisoning_recal_v5_9_382", true).apply()
                    ErrorLogger.info("BotService", "ğŸ§¹ V5.9.382: cleared inherited layer demotion weights (poisoning recal)")
                }
            } catch (_: Exception) {}

            // V5.9.375 â€” run the offline backtest baseline once on boot so the
            // user sees exactly what the bot did per asset class, segmented.
            // V5.9.384 â€” guarded with a first-boot-per-session flag so it
            // doesn't re-run on every service restart (was allocating all
            // 5784 trades Ã— 6 replays on every boot, contributing to OOM).
            try {
                if (!sessionBacktestRan) {
                    sessionBacktestRan = true
                    Thread {
                        try {
                            com.lifecyclebot.backtest.BacktestEngine.logAssetClassBaseline()
                        } catch (e: Exception) {
                            ErrorLogger.debug("Backtest", "baseline log error: ${e.message}")
                        }
                    }.apply { isDaemon = true; name = "BacktestBaseline" }.start()
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsLearningBridge init error: ${e.message}", e)
        }
        bootstrapPhase6516("MODEL_AND_LAYER_STATE_READY")
        
        // V5.7.3: Start ALL market traders â€” ALWAYS run when bot is active
        // V5.7.7: Apply individual sub-trader enabled flags from config before starting
        // V5.9.340: MARKET_TRADER_KILL_SWITCH overrides every Markets sub-trader.
        // V5.9.345: User directive â€” "look at the AATE alts trader, it trades
        // heaps and has good win rate." Keep kill-switch for Perps / Stocks /
        // Commodities / Metals / Forex, but EXEMPT CryptoAlts so it resumes
        // full operation. Alts has a separate scanner + its own trust hooks
        // and was producing +52% WR in the Strategy Trust log pre-kill.
        // V5.9.469: Markets lane gate uses isMarketsLaneEnabled â€” operator
        // reported "markets keeps starting whether the toggle is on or not";
        // root cause was an OR formula that let tradingMode=2 (default) bypass
        // the marketsTraderEnabled toggle. Now AND semantics: toggle has
        // authority. PerpsExecutionEngine + sub-traders only start when
        // isMarketsLaneEnabled(cfg) AND the per-lane toggle is true.
        val marketsStartCfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
        val marketsKill = MARKET_TRADER_KILL_SWITCH
        val marketsLaneOn = isMarketsLaneEnabled(marketsStartCfg)
        if (marketsKill) {
            ErrorLogger.warn("BotService", "ğŸ›‘ MARKET_TRADER_KILL_SWITCH=ON (excl. Alts) â€” Perps/Stocks/Commodities/Metals/Forex forced OFF")
            addLog("ğŸ›‘ Market Trader disabled (kill-switch) â€” Alts re-enabled per user directive")
        }
        if (!marketsLaneOn) {
            ErrorLogger.info("BotService", "ğŸ“´ Markets lane OFF at startup " +
                "(toggle=${marketsStartCfg.marketsTraderEnabled} mode=${marketsStartCfg.tradingMode}) â€” " +
                "skipping PerpsExecutionEngine + Stocks/Commodities/Metals/Forex starts")
            addLog("ğŸ“´ Markets lane OFF â€” Perps/Stocks/Commodities/Metals/Forex will not run this session")
        }
        // V5.0.6526 Â§TRADER_RUNTIME_PLAN â€” one derivation used by every
        // setEnabled call in startBot(). reapply() derives the same plan
        // via the same factory so drift is physically impossible.
        val plan6526 = com.lifecyclebot.engine.truth.TraderRuntimePlan6526.from(
            cfg = marketsStartCfg, marketsKill = marketsKill,
            marketsLaneOnFn = { isMarketsLaneEnabled(it) },
        )
        com.lifecyclebot.perps.PerpsTraderAI.setEnabled(plan6526.perpsEffective)
        com.lifecyclebot.perps.TokenizedStockTrader.setEnabled(plan6526.stocksEffective)
        com.lifecyclebot.perps.CommoditiesTrader.setEnabled(plan6526.commoditiesEffective)
        com.lifecyclebot.perps.MetalsTrader.setEnabled(plan6526.metalsEffective)
        com.lifecyclebot.perps.ForexTrader.setEnabled(plan6526.forexEffective)
        val cryptoUniverseOnAtStart = plan6526.cryptoUniverseOn
        com.lifecyclebot.perps.CryptoAltTrader.setEnabled(cryptoUniverseOnAtStart)
        if (!cryptoUniverseOnAtStart) {
            try { com.lifecyclebot.perps.CryptoAltTrader.stop() } catch (_: Exception) {}
            ErrorLogger.info("BotService", "CRYPTO_RUNTIME_DISABLED reason=MEME_ONLY_MODE_OR_MARKETS_OFF startup marketsLaneOn=$marketsLaneOn cryptoToggle=${marketsStartCfg.cryptoAltsEnabled}")
        }

        // V5.9.1405 â€” AUTONOMOUS AGENIC MEME-TRADER DOCTRINE.
        // Internal lanes/tools must not be disabled by UI/config toggles. The bot
        // is designed to trade, learn, pivot, and experiment from trade #1 across
        // the full specialist stack. A bad lane must size-shape / learn / pivot â€”
        // not disappear. Live wallet safety still lives in RuntimeModeAuthority,
        // TradeAuthorizer, FDG, hard-rug gates, and executor balance checks; this
        // authority only prevents accidental starvation of learning/execution
        // participants such as CYCLIC, TREASURY/CashGen, ProjectSniper, shadow lab,
        // and the broader toolkit.
        //
        // V5.0.3682 â€” P1 RESTORE MEME-ONLY AUTHORITY (operator deep-audit).
        // The blanket publish(Trader.values()) above broke meme-only isolation:
        // CYCLIC / SNIPER / MARKETS / PERPS kept evaluating every meme candidate,
        // laneEval/intake exploded, FDG ran multiple times per token, executor
        // starved â†’ 0 live buys. Compute the enabled set FROM CFG so meme-only
        // mode publishes only MEME. Non-meme-only modes still get the full
        // surface for autonomous learning.
        run {
            // V5.0.6533 â€” publish the same immutable plan already consumed by
            // setEnabled/startup. No second interpretation of mode or sub-toggles.
            com.lifecyclebot.engine.EnabledTraderAuthority.publish(plan6526.enabledTraderSet())
            // V5.0.6563 â€” do not unconditionally disable the Cyclic ring after
            // the runtime plan is built. That stale kill silently overrode the
            // PAPER=LEARN_EVERYTHING watchdog policy and made CYCLIC absent
            // even though its tick path explicitly allowed paper learning.
            val cyclicEnabled6563 = plan6526.paperMode || marketsStartCfg.cyclicTradeEnabled
            try { CyclicTradeEngine.setEnabled(cyclicEnabled6563) } catch (_: Throwable) {}
            try {
                PipelineHealthCollector.labelInc(if (cyclicEnabled6563) "CYCLIC_RUNTIME_ENABLED_6563" else "CYCLIC_RUNTIME_DISABLED_LIVE_TOGGLE_6563")
            } catch (_: Throwable) {}
            // V5.9.789 â€” operator audit Critical Fix 3: comprehensive startup
            // authority dump. The previous publish() call only logged the
            // enabled/disabled trader sets. Operator audit requires the full
            // runtime authority surface to be forensically visible on every
            // start so a "Meme-only run that secretly ran Sniper" can be
            // diagnosed from a single log line.
            try {
                val runtimeModeStr = try { com.lifecyclebot.engine.RuntimeModeAuthority.authority().name } catch (_: Throwable) { "?" }
                val sniperLocalEnabled = try { com.lifecyclebot.v3.scoring.ProjectSniperAI.isEnabled() } catch (_: Throwable) { false }
                val sniperAuthEnabled = com.lifecyclebot.engine.EnabledTraderAuthority.isEnabled(
                    com.lifecyclebot.engine.EnabledTraderAuthority.Trader.PROJECT_SNIPER
                )
                ErrorLogger.info(
                    "BotService",
                    "ğŸ” AUTH_SURFACE_AT_START " +
                        "RuntimeModeAuthority=$runtimeModeStr " +
                        "cfg.paperMode=${cfg.paperMode} " +
                        "ProjectSniperAI.isEnabled=$sniperLocalEnabled " +
                        "sniperAllowed=$sniperAuthEnabled " +
                        "enabledTraders=${com.lifecyclebot.engine.EnabledTraderAuthority.snapshotStr()}"
                )
            } catch (_: Throwable) { /* logging is best-effort */ }
        }

        if (plan6526.perpsEffective) {
            // V5.9.600 BUG-1 FIX: PerpsTraderAI was never getting setLiveMode called.
            // Sub-traders all get setLiveMode(!cfg.paperMode) below, but PerpsTraderAI
            // only had setTradingMode(isPaper) and was never reached from BotService.
            // Wire it the same way as every other trader so the live/paper flag is
            // consistent with the global config.
            try {
                com.lifecyclebot.perps.PerpsTraderAI.setLiveMode(!cfg.paperMode)
                ErrorLogger.info("BotService", "âš¡ PerpsTraderAI mode: ${if (cfg.paperMode) "PAPER" else "LIVE"}")
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "PerpsTraderAI setLiveMode error: ${e.message}")
            }
            try {
                com.lifecyclebot.perps.PerpsExecutionEngine.start(applicationContext)
                ErrorLogger.info("BotService", "âš¡ PerpsExecutionEngine STARTED - Fully Automatic Trading ACTIVE")
                // V5.0.6538 Â§SOL_PERPS_PHASE_1_ONLINE â€” operator directive:
                // "kick off Phase 1: enable SOL perps/leverage in paper mode
                // now that correctness bundles are stable". SOL is already
                // in PerpsMarket.isSolPerp and the SOL_MOMENTUM/SOL_SNIPER
                // scanners are running unconditionally through
                // PerpsMarketScanners.runAllScanners(). Emit an explicit
                // paper-only affirmation so the operator can see the SOL
                // perp lane is armed and observe the first scan tick.
                try {
                    val sol6538 = com.lifecyclebot.perps.PerpsMarket.SOL
                    val paperBal6538 = try {
                        com.lifecyclebot.perps.PerpsTraderAI.getBalance(true)
                    } catch (_: Throwable) { 0.0 }
                    val enabled6538 = try {
                        com.lifecyclebot.perps.PerpsTraderAI.isEnabled()
                    } catch (_: Throwable) { false }
                    val paperMode6538 = try {
                        com.lifecyclebot.perps.PerpsTraderAI.isPaperMode
                    } catch (_: Throwable) { true }
                    val laneEnabled6538 = try {
                        com.lifecyclebot.data.ConfigStore.load(applicationContext).perpsEnabled
                    } catch (_: Throwable) { true }
                    com.lifecyclebot.engine.PipelineHealthCollector
                        .labelInc("SOL_PERPS_PHASE_1_ONLINE_6538")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "SOL_PERPS_PHASE_1_ONLINE_6538",
                        "market=${sol6538.symbol} maxLeverage=${sol6538.maxLeverage}x " +
                            "paperMode=$paperMode6538 perpsTraderEnabled=$enabled6538 " +
                            "laneEnabled=$laneEnabled6538 paperWallet=${"%.4f".format(paperBal6538)}â— " +
                            "expected=SOL_MOMENTUM+SOL_SNIPER_scanner_ticks observed=engine_started " +
                            "action=learn_in_paper_no_live_execution",
                    )
                    ErrorLogger.info(
                        "BotService",
                        "â— SOL_PERPS_PHASE_1 ONLINE - market=SOL maxLev=${sol6538.maxLeverage}x paper=$paperMode6538 " +
                            "enabled=$enabled6538 lane=$laneEnabled6538 bal=${"%.4f".format(paperBal6538)}â—",
                    )
                } catch (_: Throwable) {}
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "PerpsExecutionEngine start error: ${e.message}", e)
            }
        }

        // V5.7.5: Start TokenizedStockTrader - DEDICATED stock trading engine
        if (plan6526.stocksEffective) {
            try {
                com.lifecyclebot.perps.TokenizedStockTrader.init()
                com.lifecyclebot.perps.TokenizedStockTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.TokenizedStockTrader.start()
                ErrorLogger.info("BotService", "ğŸ“ˆ TRADER_GATE MARKETS/STOCKS enabled=true started=true")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "TokenizedStockTrader start error: ${e.message}", e)
            }
        } else {
            // V5.9.776 â€” defence-in-depth: explicitly stop any stale instance
            // and log the OFF state so operator can verify toggle isolation.
            try { com.lifecyclebot.perps.TokenizedStockTrader.stop() } catch (_: Exception) {}
            ErrorLogger.info("BotService", "ğŸ“ˆ TRADER_GATE MARKETS/STOCKS enabled=false started=false (marketsLaneOn=$marketsLaneOn stocksEnabled=${marketsStartCfg.stocksEnabled})")
        }

        // V5.7.6: Start CommoditiesTrader - Energy & Agricultural commodities
        if (plan6526.commoditiesEffective) {
            try {
                com.lifecyclebot.perps.CommoditiesTrader.initialize()
                com.lifecyclebot.perps.CommoditiesTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.CommoditiesTrader.start()
                ErrorLogger.info("BotService", "ğŸ›¢ï¸ CommoditiesTrader STARTED - Oil, Gas, Agriculture ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "CommoditiesTrader start error: ${e.message}", e)
            }
        }

        // V5.7.6: Start MetalsTrader - Precious & Industrial metals
        if (plan6526.metalsEffective) {
            try {
                com.lifecyclebot.perps.MetalsTrader.initialize(applicationContext)
                com.lifecyclebot.perps.MetalsTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.MetalsTrader.start()
                ErrorLogger.info("BotService", "ğŸ¥‡ MetalsTrader STARTED - Gold, Silver, Industrial Metals ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "MetalsTrader start error: ${e.message}", e)
            }
        }

        // V5.7.6: Start ForexTrader - Currency pairs
        if (plan6526.forexEffective) {
            try {
                com.lifecyclebot.perps.ForexTrader.initialize(applicationContext)
                com.lifecyclebot.perps.ForexTrader.setLiveMode(!cfg.paperMode)
                com.lifecyclebot.perps.ForexTrader.start()
                ErrorLogger.info("BotService", "ğŸ’± ForexTrader STARTED - Major, Cross, EM Pairs ACTIVE")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "ForexTrader start error: ${e.message}", e)
            }
        }

        // V5.7.3: Start PerpsAutoReplayLearner for CONTINUOUS learning
        // V5.9.777 â€” EMERGENT MEME-ONLY: gate behind marketsLaneOn. The
        // operator runs Meme Trader only and the auto-replay learner has
        // nothing meaningful to do without perps lanes; it was running
        // unconditionally and contributing to ANR churn.
        if (marketsLaneOn) {
            try {
                com.lifecyclebot.perps.PerpsAutoReplayLearner.start()
                ErrorLogger.info("BotService", "ğŸ¬ TRADER_GATE PERPS_AUTO_REPLAY enabled=true started=true")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "PerpsAutoReplayLearner start error: ${e.message}", e)
            }
        } else {
            try { com.lifecyclebot.perps.PerpsAutoReplayLearner.stop() } catch (_: Throwable) {}
            ErrorLogger.info("BotService", "ğŸ¬ TRADER_GATE PERPS_AUTO_REPLAY enabled=false started=false (marketsLaneOn=false)")
        }

        // V5.7.4: Start Learning Insights Panel for continuous analysis
        try {
            com.lifecyclebot.perps.PerpsLearningInsightsPanel.start()
            ErrorLogger.info("BotService", "ğŸ§  PerpsLearningInsightsPanel STARTED - Continuous Analysis Mode ACTIVE")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "PerpsLearningInsightsPanel start error: ${e.message}")
        }

        // V2.0: Start CryptoAltTrader â€” gated by cryptoAltsEnabled toggle.
        // V5.9.776 â€” EMERGENT MEME-ONLY: previously called start() unconditionally
        // even when the UI toggle was OFF, which leaked through to the engine's
        // initial scan (now gated in CryptoAltTrader.start as well, defence-in-depth).
        // V5.0.3744 â€” operator explicit-enable escape hatch. If the operator
        // has cryptoAltsEnabled=true AND marketsTraderEnabled=true, START
        // the trader even when tradingMode==0 (meme-only). The crypto trader
        // runs its own engine loop on its own universe â€” it does NOT inflate
        // meme-lane fanout. Matches the V5.0.3744 escape inside
        // CryptoAltTrader.runtimeDisabledReason.
        try {
            com.lifecyclebot.perps.CryptoAltTrader.init(applicationContext)
            com.lifecyclebot.perps.CryptoAltTrader.setLiveMode(!cfg.paperMode)
            // V5.0.6015 â€” startup should follow the same isolated crypto-sidecar
            // doctrine as EnabledTraderAuthority publishing: MEME can run crypto
            // universe without enabling stocks/forex/perps/markets fanout.
            // V5.0.6526 â€” one derivation via TraderRuntimePlan6526.
            val cryptoPlan6526 = com.lifecyclebot.engine.truth.TraderRuntimePlan6526.from(
                cfg = cfg, marketsKill = MARKET_TRADER_KILL_SWITCH,
                marketsLaneOnFn = { isMarketsLaneEnabled(it) },
            )
            val cryptoUniverseOn = cryptoPlan6526.cryptoUniverseOn
            com.lifecyclebot.perps.CryptoAltTrader.setEnabled(cryptoUniverseOn)
            if (cryptoUniverseOn) {
                com.lifecyclebot.perps.CryptoAltTrader.start()
                ErrorLogger.info("BotService", "ğŸª™ TRADER_GATE CRYPTO_ALT enabled=true started=true (explicitMode=${!isMarketsLaneEnabled(cfg)})")
            } else {
                // Make sure any stale instance from a prior run is stopped.
                try { com.lifecyclebot.perps.CryptoAltTrader.stop() } catch (_: Exception) {}
                ErrorLogger.info("BotService", "CRYPTO_RUNTIME_DISABLED reason=MEME_ONLY_MODE cryptoToggle=${cfg.cryptoAltsEnabled} tradingMode=${cfg.tradingMode} markets=${cfg.marketsTraderEnabled}")
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "CryptoAltTrader start error: ${e.message}", e)
        }
        bootstrapPhase6516("TRADER_ENGINES_STARTED")

        // V5.9.54: One-time unified-paper-wallet reconciliation migration.
        // V5.9.48 started crediting every sub-trader open/close into
        // BotService.status.paperWalletSol, but historical realized P&L
        // accumulated in the sub-traders BEFORE that patch landed was never
        // rolled into the main wallet (user saw $13K main vs $98K Markets
        // portfolio + $85K P&L). Once-only: sum lifetime P&L from every
        // sub-trader and credit it to the main wallet, flag set so we never
        // double-count on subsequent boots.
        try {
            reconcileUnifiedPaperWallet()
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "Unified wallet reconciliation error: ${e.message}")
        }

        // V5.7.3: Start Network Signal Auto-Buyer (disabled by default, paper mode only)
        try {
            // Only start if user has explicitly enabled it
            val cfg = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            if (cfg.autoTradeNetworkSignals) {
                com.lifecyclebot.perps.NetworkSignalAutoBuyer.start(
                    com.lifecyclebot.perps.NetworkSignalAutoBuyer.AutoBuyerConfig(
                        enabled = true,
                        paperModeOnly = cfg.paperMode,
                    )
                )
                ErrorLogger.info("BotService", "ğŸ“¡ NetworkSignalAutoBuyer STARTED - Copy Trade from Hive ACTIVE")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "NetworkSignalAutoBuyer start error: ${e.message}")
        }
        
        // V5.9.319: One-time POISONED-LAYER AUTO-RESET migration.
        // After flashing the V5.9.318 isWin fix, layers built up under the
        // V5.9.190 'isWin = pnlPct >= 1.0' contract still have smoothedAccuracy
        // values calibrated against losses-disguised-as-wins. The cumulative
        // Bayesian smoothing means those wrong values won't self-heal in a
        // reasonable time. Detect catastrophic poisoning (avg accuracy < 25%
        // across 10+ active layers with 50+ trades each) and do ONE
        // resetAllLearning() so layers train fresh against the correct
        // direction-accuracy contract.
        try {
            val migrationKey = "v5_9_319_poison_reset_done"
            val prefs = applicationContext.getSharedPreferences("bot_migrations", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean(migrationKey, false)) {
                val maturity = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity().values
                val active = maturity.filter { it.trades >= 50 }
                if (active.size >= 10) {
                    val avgAcc = active.map { it.smoothedAccuracy }.average()
                    if (avgAcc < 0.25) {
                        ErrorLogger.warn("BotService",
                            "ğŸ’‰ V5.9.319 POISON_RESET triggered â€” avg accuracy=${(avgAcc*100).toInt()}% across ${active.size} active layers. Resetting once.")
                        addLog("ğŸ’‰ POISON_RESET: layers were poisoned by old learning contract. Wiped & re-training fresh.")
                        com.lifecyclebot.v3.scoring.EducationSubLayerAI.resetAllLearning()
                    } else {
                        ErrorLogger.info("BotService",
                            "âœ… V5.9.319 poison check OK â€” avg accuracy=${(avgAcc*100).toInt()}% (>=25%). No reset needed.")
                    }
                }
                prefs.edit().putBoolean(migrationKey, true).apply()
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "Poison-reset check error: ${e.message}")
        }

        // V5.7.4: Start Insider Tracker AI (Trump/Pelosi/Whale wallet monitoring)
        try {
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.start(heliusApiKey = cfg.heliusApiKey) { signal ->
                // Real-time alert callback for alpha signals
                if (signal.wallet.riskLevel == com.lifecyclebot.v3.scoring.InsiderTrackerAI.RiskLevel.ALPHA) {
                    ErrorLogger.info("BotService", "ğŸ” INSIDER ALERT: ${signal.wallet.label} | ${signal.signalType.name} | ${signal.tokenSymbol ?: "?"}")
                }
                // V5.9.367 â€” dispatch to copy-trade engine: ACCUMULATION/PRE_TWEET
                // â†’ memetrader watchlist via WHALE_COPY; DISTRIBUTION on alpha
                // wallets â†’ copy-exit across all Markets-layer traders.
                try { com.lifecyclebot.engine.InsiderCopyEngine.onTrackerSignal(signal) } catch (_: Exception) {}
            }
            ErrorLogger.info("BotService", "ğŸ” InsiderTrackerAI STARTED - Watching ${com.lifecyclebot.v3.scoring.InsiderTrackerAI.getAllWallets().size} wallets (Trump/Pelosi/Whales)")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "InsiderTrackerAI start error: ${e.message}")
        }

        // V5.9.311: Initialize InsiderWalletTracker (powers Insider Tracker UI +
        // delta-based on-chain signal detection). Periodic scanForSignals() is
        // wired in the main loop below to forward NEW_POSITION/ACCUMULATION
        // /DISTRIBUTION/SELL events into the notification + AI scoring pipeline.
        try {
            com.lifecyclebot.perps.InsiderWalletTracker.init(applicationContext)
            com.lifecyclebot.perps.InsiderWalletTracker.setSignalCallback { signal ->
                ErrorLogger.info("BotService", "ğŸ” INSIDER WALLET SIGNAL: ${signal.walletLabel} | ${signal.action} | ${signal.tokenSymbol} | \$${signal.usdValue.toInt()} | conf=${signal.confidence}%")
                try {
                    com.lifecyclebot.perps.PerpsNotificationManager.notifyInsiderSignal(
                        walletLabel = signal.walletLabel,
                        signalType = signal.action,
                        tokenSymbol = signal.tokenSymbol,
                        confidence = signal.confidence,
                    )
                } catch (_: Exception) {}
                // V5.9.367 â€” dispatch to copy-trade engine: BUY/NEW_POSITION
                // â†’ memetrader watchlist via WHALE_COPY scanner; SELL on
                // alpha (smart-money) wallets â†’ copy-exit across all
                // Markets-layer traders.
                try { com.lifecyclebot.engine.InsiderCopyEngine.onWalletTrackerSignal(signal) } catch (_: Exception) {}
            }
            val stats = com.lifecyclebot.perps.InsiderWalletTracker.getStats()
            ErrorLogger.info("BotService", "ğŸ” InsiderWalletTracker INITIALIZED â€” ${stats["total_wallets"]} wallets (POL=${stats["political"]}, SMART=${stats["smart_money"]}, CUSTOM=${stats["custom"]})")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "InsiderWalletTracker init error: ${e.message}")
        }
        
        // V5.6.28f: Sync RunTracker30D stats with TradeHistoryStore
        try {
            if (com.lifecyclebot.engine.RunTracker30D.isRunActive()) {
                com.lifecyclebot.engine.RunTracker30D.syncStatsFromTradeHistory()
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "RunTracker30D sync error: ${e.message}")
        }
        
        // V5.9.368 â€” One-shot 72h trust quarantine for TokenizedStockAI.
        // Backstory: the prior 50 stock trades all lost (0% WR) because
        // V3 was running its meme-coin scoring on AAPL/TSLA/etc., dragging
        // every blended score below the trade gate (fixed by V5.9.366's
        // V3 bypass for non-meme assets). Those 50 losses pushed
        // TokenizedStockAI's trust below 0.25 â†’ DISTRUSTED â†’ TRUST_GATE
        // is now blocking every new entry, so V5.9.366's fix can't prove
        // itself. 72h quarantine lets the new V3-bypass path produce a
        // clean baseline before trust is recomputed. Only triggered if
        // the strategy is currently DISTRUSTED â€” the call is idempotent
        // and harmless on every other launch.
        // V5.9.369 â€” extended to all Markets-layer trader strategies.
        // The pre-V5.9.366 mis-grading was identical for every asset
        // class, so any trader currently DISTRUSTED is in the same
        // recovery situation and deserves the same 72h clean window.
        try {
            val candidates = listOf(
                "TokenizedStockAI",
                "CryptoAltAI",
                "ForexAI",
                "MetalsAI",
                "CommoditiesAI",
            )
            for (name in candidates) {
                val lvl = com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel(name)
                // V5.9.463 â€” SENTIENT-FLUID RETUNE. Do NOT auto-quarantine
                // DISTRUSTED markets traders on boot. Per operator:
                // "nothing should really get to a distrusted state. we
                //  have full loop learning". Distrust â†’ coaching, not
                // pause. A quarantine would stop trades â†’ no outcomes â†’
                // no learning â†’ the strategy can never rebuild trust.
                // We leave the trust record intact; getTrustMultiplier
                // (V5.9.463) shapes sizing at 0.20x floor for heavily
                // underperforming strategies so they keep feeding the
                // learner at low risk.
                if (lvl == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED) {
                    ErrorLogger.info("BotService",
                        "ğŸ§  COACHING MODE (was V5.9.366 quarantine): $name stays active at " +
                        "coaching-floor size â€” sentient-fluid learning loop will rebuild trust.")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "Markets trust quarantine error: ${e.message}")
        }
        bootstrapPhase6516("AUXILIARY_FEEDS_READY")

        // V5.9.646 â€” onCreate-anchored meme scanner self-heal.
        //
        // Operator log dump V5.9.645 confirmed: every other lane (CryptoAlt,
        // Markets, Forex, Metals, Commodities, Perps, Insider, Replay) is
        // alive because they all auto-start inside onCreate(). The meme
        // scanner is the ONLY lane that depends on startBot() running, so
        // any Android lifecycle quirk (START_NOT_STICKY auto-restart, an
        // ACTION_START intent that doesn't propagate, a stopâ†’start race
        // that flips to the "already running" branch at line 1083, or
        // onCreate firing without onStartCommand) leaves the meme scanner
        // permanently dead while every other engine is producing signals.
        //
        // This onCreate-anchored heal coroutine closes that gap by running
        // bootMemeScanner() periodically, gated only on:
        //   â€¢ user has NOT manually stopped the bot
        //   â€¢ some meme-related config toggle is on (memeTraderEnabled,
        //     tradingMode 0/2, fullMarketScan, v3, autoTrade, autoAdd)
        //
        // It does NOT require status.running because the symptom we are
        // fixing is exactly that startBot never set status.running=true.
        scope.launch {
            try {
                kotlinx.coroutines.delay(3_000)  // V5.9.706: reduced from 15s â†’ 3s for faster cold-start heal
                while (true) {
                    try {
                        val cfg = ConfigStore.load(applicationContext)
                        val manualStop = isManualStopRequested(applicationContext)
                        val memeWanted = cfg.memeTraderEnabled ||
                            cfg.tradingMode == 0 || cfg.tradingMode == 2 ||
                            cfg.fullMarketScanEnabled ||
                            cfg.v3EngineEnabled ||
                            cfg.autoTrade ||
                            cfg.autoAddNewTokens
                        if (!manualStop && memeWanted) {
                            val sc = marketScanner
                            val alive = try { sc?.isAlive() ?: false } catch (_: Throwable) { false }
                            if (sc == null || !alive) {
                                ErrorLogger.warn(
                                    "BotService",
                                    "ğŸ©¹ ONCREATE_HEAL: meme scanner ${if (sc==null) "NULL" else "not alive"} â€” booting (manualStop=$manualStop, memeTraderEnabled=${cfg.memeTraderEnabled}, mode=${cfg.tradingMode}, status.running=${status.running})"
                                )
                                bootMemeScanner(reason = "ONCREATE_HEAL")
                            } else {
                                ErrorLogger.debug("BotService", "ONCREATE_HEAL: scanner alive â€” no action")
                            }
                        }
                    } catch (e: Throwable) {
                        ErrorLogger.debug("BotService", "ONCREATE_HEAL tick error: ${e.message}")
                    }
                    kotlinx.coroutines.delay(30_000)
                }
            } catch (_: Throwable) {}
        }


                    serviceBootstrapSucceeded6516 = true
                    serviceBootstrapFailure6517 = ""
                    try {
                        ForensicLogger.lifecycle("SERVICE_BOOTSTRAP_READY_6516", "durMs=${android.os.SystemClock.elapsedRealtime() - serviceStarted6516} thread=${Thread.currentThread().name}")
                        PipelineHealthCollector.labelInc("SERVICE_BOOTSTRAP_READY_6516")
                    } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    serviceBootstrapSucceeded6516 = false
                    serviceBootstrapFailure6517 = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}"
                    ErrorLogger.crash("BotService", "SERVICE_BOOTSTRAP_FAILED_6516: ${t.javaClass.simpleName}: ${t.message}", t)
                    try {
                        ForensicLogger.lifecycle("SERVICE_BOOTSTRAP_FAILED_6516", "type=${t.javaClass.simpleName} msg=${t.message?.take(120)} durMs=${android.os.SystemClock.elapsedRealtime() - serviceStarted6516}")
                        PipelineHealthCollector.labelInc("SERVICE_BOOTSTRAP_FAILED_6516")
                    } catch (_: Throwable) {}
                } finally {
                    serviceBootstrapReady6516 = true
                }
            }
        } catch (e: Exception) {
            ErrorLogger.crash("BotService", "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            android.util.Log.e("BotService", "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            // Don't crash the service - log and continue
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // V5.9.707 â€” START_STICKY resurrection handler.
        // When Android kills the process (OOM, aggressive OEM battery manager) and
        // restarts it via START_STICKY, the intent is null. Check wasRunning/manualStop
        // and relaunch the bot if the user had it running before the kill.
        if (intent == null) {
            val rp = getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            val wasRunning = rp.getBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
            val manualStop = rp.getBoolean(KEY_MANUAL_STOP_REQUESTED, false)
            ErrorLogger.warn("BotService", "onStartCommand null intent (START_STICKY resurrection) wasRunning=$wasRunning manualStop=$manualStop")
            val held = heldPositionCountForRescue()
            if ((wasRunning || held > 0) && !manualStop) {
                ensureRuntimeWakeLock6031("null_intent_sticky_resurrection")
                ensureRuntimeWifiLock6032("null_intent_sticky_resurrection")
                try { scheduleKeepAliveAlarm() } catch (_: Throwable) {}
                try { ServiceWatchdog.scheduleAlarm(applicationContext) } catch (_: Throwable) {}
            }
            if ((wasRunning || held > 0) && !manualStop && !isRuntimeActive()) {
                try {
                    addLog("ğŸ”„ AUTO-RESURRECT: Bot restarted by OS after unexpected kill/held positions")
                    try { ForensicLogger.lifecycle("STICKY_STRANDED_POSITION_RESCUE", "wasRunning=$wasRunning held=$held manualStop=false") } catch (_: Throwable) {}
                    scope.launch { startBot() }
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "Resurrection startBot failed: ${e.message}", e)
                }
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val userRequested = intent.getBooleanExtra(EXTRA_USER_REQUESTED, false)
                val forceRestartConfirmed = intent.getBooleanExtra(EXTRA_FORCE_RESTART_CONFIRMED, false)
                val manualStop = isManualStopRequested(applicationContext)

                // V5.9.1081 â€” every ACTION_START gets a REQUESTED breadcrumb so
                // the next snapshot can answer "did 10 START taps create 10
                // runtime jobs or exactly 1?".
                try { ForensicLogger.lifecycle("LIFECYCLE_START_REQUESTED", "userRequested=$userRequested forceRestart=$forceRestartConfirmed manualStop=$manualStop") } catch (_: Throwable) {}
                if (!manualStop) ensureAlwaysOnRuntimeGuards6031("action_start_entry")

                // V5.9.609 â€” stale keep-alive / watchdog / lifecycle alarms must
                // not undo a user stop. Only a fresh UI/user start may clear the
                // manual-stop latch. This fixes the meme bot randomly starting
                // after Stop because an older ACTION_START alarm fired later.
                if (manualStop && !userRequested) {
                    ErrorLogger.warn("BotService", "Ignoring non-user ACTION_START because manual stop latch is active")
                    try { ForensicLogger.lifecycle("LIFECYCLE_START_IGNORED_MANUAL_STOP_LATCH", "userRequested=false manualStop=true") } catch (_: Throwable) {}
                    cancelAllRestartAlarms()
                    try { ServiceWatchdog.cancel(applicationContext) } catch (_: Exception) {}
                    return START_NOT_STICKY
                }
                if (userRequested) {
                    userStartQueuedDuringStop = stopInProgress || restartAfterStopDispatchPending6518
                    try {
                        getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_MANUAL_STOP_REQUESTED, false)
                            .apply()
                    } catch (_: Exception) {}
                }

                // STOP OWNS THIS SERVICE INSTANCE.  Queue before inspecting the
                // old loop Job: that Job can still be active while teardown is in
                // progress, and treating it as a healthy "already running" loop
                // was the race that lost Start and later killed its replacement.
                // No coroutine is launched here; stopBot() dispatches ACTION_START
                // only after its complete tail and Android destroys this instance.
                if (stopInProgress || restartAfterStopDispatchPending6518) {
                    userStartQueuedDuringStop = true
                    serviceStartRequested6517.set(true)
                    addLog("â³ Stop in progress â€” restart queued for a fresh runtime")
                    ErrorLogger.warn("BotService", "Start requested during teardown â€” queued for fresh service")
                    try {
                        ForensicLogger.lifecycle(
                            "LIFECYCLE_START_QUEUED_STOP_IN_PROGRESS",
                            "userRequested=$userRequested stopInProgress=$stopInProgress dispatchPending=$restartAfterStopDispatchPending6518",
                        )
                    } catch (_: Throwable) {}
                    return START_STICKY
                }

                // V5.9.1081 â€” strict idempotency. Three early-exit checks BEFORE
                // any restart logic:
                //   1) startInProgress  â†’ a previous ACTION_START is mid-flight.
                //                         IGNORE silently. (User mashing the
                //                         button or alarm storm.)
                //   2) loopJob.isActive â†’ bot is already running. IGNORE.
                //                         Never cancel and restart a healthy
                //                         loop from normal ACTION_START.
                //   3) forceRestartConfirmed â†’ operator explicitly asked for a
                //                              stuck-loop rescue (e.g. halt_reset
                //                              path). Bypasses the running guard.
                // The normal UI START button NEVER sets EXTRA_FORCE_RESTART_CONFIRMED,
                // so it can never accidentally trigger the cancel+restart path.
                if (startInProgress && !forceRestartConfirmed) {
                    try { ForensicLogger.lifecycle("LIFECYCLE_START_IGNORED_ALREADY_STARTING", "userRequested=$userRequested") } catch (_: Throwable) {}
                    return START_STICKY
                }
                if (loopJob?.isActive == true && !forceRestartConfirmed) {
                    // V5.9.1167 â€” START while loop already exists must repair
                    // truth, not no-op. UI symptom: screen says "Bot stopped";
                    // pressing Start does nothing because this branch returned
                    // while status/runtime controller remained stale false.
                    status.running = true
                    isShuttingDown = false
                    try { com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.setRuntimeActive(true, "BotService.actionStart.rebind6487") } catch (_: Throwable) {}
                    ensureRuntimeWakeLock6031("action_start_already_running")
                    ensureRuntimeWifiLock6032("action_start_already_running")
                    try { ensureHotExitAlive() } catch (_: Throwable) {}
                    try {
                        getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, true)
                            .putBoolean(KEY_MANUAL_STOP_REQUESTED, false)
                            .apply()
                    } catch (_: Throwable) {}
                    // V5.9.1218 â€” this branch is hit by keep-alive ACTION_START
                    // while the bot is already running. Do not decrypt ConfigStore on
                    // the service/main path; use the already-published runtime authority.
                    val repairPaper = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
                    val repairAuto = try { RuntimeModeAuthority.current().autoTrade } catch (_: Throwable) { true }
                    try {
                        RuntimeModeAuthority.publishRuntimeStart(repairPaper, repairAuto)
                        // V5.0.3811 â€” already-running keepalive repair must not reset
                        // session counters. Rebounds are runtime-truth repairs, not new sessions.
                        PipelineHealthCollector.modeSnapshot = if (repairPaper) "PAPER" else "LIVE"
                    } catch (_: Throwable) {}
                    val repairGen = BotRuntimeController.beginStart(
                        paperMode = repairPaper,
                        enabledTraders = try { EnabledTraderAuthority.snapshotStr() } catch (_: Throwable) { "" }
                    )
                    BotRuntimeController.registerJob(repairGen, "botLoop", loopJob)
                    BotRuntimeController.publishRunning(repairGen)
                    try { ForensicLogger.lifecycle("LIFECYCLE_RUNTIME_JOB_ALREADY_EXISTS", "userRequested=$userRequested loopActive=true statusRunning=${status.running} repaired=true gen=$repairGen") } catch (_: Throwable) {}
                    try { ForensicLogger.lifecycle("LIFECYCLE_START_REBOUND_ALREADY_RUNNING", "userRequested=$userRequested repaired=true") } catch (_: Throwable) {}
                    // AlarmManager / ServiceWatchdog binder work showed up in 3185
                    // ANR samples. Re-arm asynchronously for idempotent keep-alive starts.
                    scope.launch {
                        try { scheduleKeepAliveAlarm() } catch (_: Throwable) {}
                        try { ServiceWatchdog.scheduleAlarm(applicationContext) } catch (_: Throwable) {}
                    }
                    return START_STICKY
                }

                if (forceRestartConfirmed && loopJob?.isActive == true) {
                    // V5.9.1081 â€” explicit operator-confirmed force-restart (e.g.
                    // halt_reset, or a future "rescue stuck loop" debug button).
                    // The normal UI START button does NOT set this extra, so it
                    // can never reach this branch. This preserves the V5.9.1068
                    // stuck-loop rescue capability under an explicit gesture.
                    ErrorLogger.warn("BotService", "ğŸ†˜ EXPLICIT FORCE RESTART: caller set EXTRA_FORCE_RESTART_CONFIRMED=true. Cancelling loop and restarting.")
                    addLog("ğŸ†˜ Operator-confirmed force-restart â€” cancelling and restarting loop")
                    try { ForensicLogger.lifecycle("LIFECYCLE_FORCE_RESTART_ACCEPTED", "loopActive=true statusRunning=${status.running}") } catch (_: Throwable) {}
                    startInProgress = true
                    scope.launch {
                        try {
                            try {
                                loopJob?.cancel(kotlinx.coroutines.CancellationException("operator force restart"))
                                withTimeoutOrNull(3_000L) { loopJob?.join() }
                            } catch (_: Throwable) {}
                            userStartQueuedDuringStop = false
                            try { ForensicLogger.lifecycle("LIFECYCLE_START_ACCEPTED", "forceRestart=true") } catch (_: Throwable) {}
                            startBot()
                            try { ForensicLogger.lifecycle("LIFECYCLE_RUNTIME_JOB_CREATED", "forceRestart=true loopActive=${loopJob?.isActive == true}") } catch (_: Throwable) {}
                        } finally {
                            startInProgress = false
                        }
                    }
                } else {
                    // Normal accepted start â€” loopJob is null/inactive, no stop
                    // in progress, no manual-stop latch. This is the only path
                    // that creates a fresh runtime job from a normal ACTION_START.
                    userStartQueuedDuringStop = false
                    try { ForensicLogger.lifecycle("LIFECYCLE_START_ACCEPTED", "userRequested=$userRequested fresh=true") } catch (_: Throwable) {}
                    startInProgress = true
                    scope.launch {
                        try {
                            startBot()
                            try { ForensicLogger.lifecycle("LIFECYCLE_RUNTIME_JOB_CREATED", "loopActive=${loopJob?.isActive == true}") } catch (_: Throwable) {}
                        } finally {
                            startInProgress = false
                        }
                    }
                }
            }
            ACTION_STOP  -> {
                val stopSource = intent.getStringExtra(EXTRA_STOP_SOURCE) ?: "unknown_action_stop"
                val uiStopConfirmed = intent.getBooleanExtra(EXTRA_UI_STOP_CONFIRMED, false)
                val isConfirmedManualStop = isConfirmedManualStopSource(stopSource, uiStopConfirmed)
                val isAllowedStop = isAllowedStopSource(stopSource, uiStopConfirmed)
                try { ForensicLogger.lifecycle("ACTION_STOP_RECEIVED", "source=$stopSource user=${intent.getBooleanExtra(EXTRA_USER_REQUESTED, false)} uiConfirmed=$uiStopConfirmed manual=$isConfirmedManualStop allowed=$isAllowedStop") } catch (_: Throwable) {}
                try { ForensicLogger.lifecycle("LIFECYCLE_STOP_REQUESTED", "source=$stopSource manual=$isConfirmedManualStop allowed=$isAllowedStop") } catch (_: Throwable) {}
                // V5.9.1075 â€” reject ambiguous UI stops. Regression RCA:
                // Main rendered START BOT from stale UiState, but its click handler
                // called toggleBot(); toggleBot re-read live runtime truth, saw an
                // active/ghost loop, and sent ACTION_STOP source=ui_stop_button.
                // Operator clicked/expected START; service accepted STOP. Never again.
                if (!isAllowedStop) {
                    val reason = if (stopSource == "ui_stop_button" && !uiStopConfirmed) "missing_ui_stop_confirm" else "unapproved_stop_source"
                    ErrorLogger.error("BotService", "ğŸš« Rejected ACTION_STOP: source=$stopSource uiConfirmed=$uiStopConfirmed reason=$reason")
                    try { ForensicLogger.lifecycle("ACTION_STOP_REJECTED", "source=$stopSource reason=$reason") } catch (_: Throwable) {}
                    try {
                        getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, true)
                            .putBoolean(KEY_MANUAL_STOP_REQUESTED, false)
                            .apply()
                    } catch (_: Throwable) {}
                    scheduleKeepAliveAlarm()
                    try { ServiceWatchdog.scheduleAlarm(applicationContext) } catch (_: Throwable) {}
                    return START_STICKY
                }
                try { ForensicLogger.lifecycle("LIFECYCLE_STOP_ACCEPTED", "source=$stopSource manual=$isConfirmedManualStop") } catch (_: Throwable) {}
                // Close the race before launching the teardown coroutine.  The old
                // code set this only inside stopBot(), leaving a window where an
                // ACTION_START saw the still-active old Job and was discarded as
                // "already running".  Duplicate stops are idempotent.
                if (stopInProgress) {
                    try { ForensicLogger.lifecycle("LIFECYCLE_STOP_DUPLICATE_IGNORED_6518", "source=$stopSource") } catch (_: Throwable) {}
                    return START_STICKY
                }
                stopInProgress = true
                serviceStartRequested6517.set(false)
                try { ForensicLogger.lifecycle("DEFERRED_START_CANCELLED_BY_STOP_6517", "source=$stopSource") } catch (_: Throwable) {}
                // V5.9.1078 â€” STOP LOOP != LIQUIDATE POSITIONS.
                // Only a confirmed operator stop should arm the manual-stop latch
                // and disarm resurrection. Internal/config/lifecycle stops are soft
                // stops used for restart/recovery and must preserve positions.
                try {
                    getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, !isConfirmedManualStop)
                        .putBoolean(KEY_MANUAL_STOP_REQUESTED, isConfirmedManualStop)
                        .apply()
                } catch (_: Exception) {}
                userStartQueuedDuringStop = !isConfirmedManualStop && stopSource == "config_restart"
                try { acceptanceWindowFuture6668?.cancel(false) } catch (_: Throwable) {}
                acceptanceWindowFuture6668 = null
                status.running = false
                if (isConfirmedManualStop) {
                    cancelAllRestartAlarms()
                    try { ForensicLogger.lifecycle("LIFECYCLE_PENDING_RESTART_CANCELLED", "source=$stopSource") } catch (_: Throwable) {}
                    try { ServiceWatchdog.cancel(applicationContext) } catch (_: Exception) {}
                }
                scope.launch { stopBot(stopSource) }
            }
            ACTION_LOOP_HEARTBEAT -> {
                if (isManualStopRequested(applicationContext)) {
                    cancelLoopHeartbeatAlarm()
                    try { com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.setRuntimeActive(false, "BotService.loopHeartbeat.manualStop6487") } catch (_: Throwable) {}
                    return START_NOT_STICKY
                }
                // V5.0.6487 â€” heartbeat is the background liveness authority.
                // Reassert FGS + CPU/network locks before evaluating progress so
                // minimized/screen-off recovery does not depend on Activity state.
                ensureAlwaysOnRuntimeGuards6031("loop_heartbeat_6487")
                if (status.running) try { com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.setRuntimeActive(true, "BotService.loopHeartbeat6487") } catch (_: Throwable) {}
                // V5.9.762 â€” EMERGENT CRITICAL #1: HEARTBEAT REWRITE.
                //
                // Previous V5.9.760 rescue logic still cancelled-and-relaunched
                // the loop whenever sinceLastTickMs > 180s, even though the
                // operator's V5.9.761 dump showed the loop was actually
                // healthy (slow due to SolanaWallet.rpc back-pressure)
                // and only LOOKED dead because BOT_LOOP_TICK is emitted
                // ONCE per cycle and a slow cycle can legitimately take
                // 60â€“99s. The result: 104 spurious rescues, scope death,
                // GlobalScope band-aid that masked the cause.
                //
                // New rules (per EMERGENT spec):
                //   1. Track lastProgressAtMs from MULTIPLE phase markers,
                //      not just BOT_LOOP_TICK. A cycle that crosses
                //      PRE_SUPERVISOR / SUPERVISOR / POST_SUPERVISOR /
                //      EXIT_SWEEP / CYCLE_EXIT is making progress.
                //   2. Rescue only fires if:
                //        a) bot supposed to be running AND
                //        b) (active job is null/cancelled/completed) OR
                //           (progress stalled > rescueProgressGraceMs AND
                //            currentPhase is NOT in activePhaseSet)
                //   3. If the loop is slow but progressing â†’ HEARTBEAT_OK
                //   4. If stalled but inside an active phase â†’
                //      HEARTBEAT_RESCUE_SUPPRESSED_ACTIVE_PHASE
                //   5. If stalled in idle phase but job is still active â†’
                //      HEARTBEAT_SLOW_NO_RESCUE (DO NOT cancel)
                //   6. If job is genuinely dead â†’ relaunch on SERVICE scope
                //      with synchronized(loopJobLock) CAS guard.
                //      No GlobalScope. No ACTION_START intent fallback.
                try {
                    val now = System.currentTimeMillis()
                    val sinceLastTickMs = now - lastBotLoopTickMs
                    val progressGapMs = now - lastProgressAtMs
                    val running = status.running
                    val lj = loopJob
                    val active = lj?.isActive == true
                    val phase = currentPhase
                    val phaseIsActive = phase in activePhaseSet

                    ForensicLogger.lifecycle(
                        "LOOP_HEARTBEAT_ALARM",
                        "sinceLastTickSec=${sinceLastTickMs / 1000} progressGapSec=${progressGapMs / 1000} running=$running loopActive=$active phase=$phase"
                    )

                    when {
                        // Bot off â€” nothing to do.
                        !running -> { /* fall through to re-arm */ }

                        // Job dead â†’ genuine rescue case (race-checked CAS below).
                        !active -> {
                            ErrorLogger.warn(
                                "BotService",
                                "ğŸ©º LOOP_HEARTBEAT(alarm): job is dead/inactive â€” relaunching via service scope. progressGap=${progressGapMs / 1000}s phase=$phase"
                            )
                            ForensicLogger.lifecycle(
                                "LOOP_HEARTBEAT_RESCUE",
                                "reason=job_inactive silentSec=${sinceLastTickMs / 1000} progressGapSec=${progressGapMs / 1000} phase=$phase"
                            )
                            performServiceScopeRescue(lj, phase, progressGapMs)
                        }

                        // Job alive, progress fresh â†’ all good.
                        progressGapMs < rescueProgressGraceMs -> {
                            ForensicLogger.lifecycle(
                                "HEARTBEAT_OK",
                                "progressGapSec=${progressGapMs / 1000} phase=$phase active=true"
                            )
                        }

                        // V5.9.914 â€” HARD UPPER BOUND for active-phase suppression.
                        //
                        // Operator dump 2026-05-18 21:00:59 showed
                        // progressGapSec=1920 (32 min) with phase=SUPERVISOR
                        // being suppressed forever. Memory rule #87.5
                        // ("safety gates that EXIST must FIRE") + #87.11
                        // ("exit safety must NOT depend on scanner throughput")
                        // demand a hard ceiling.
                        //
                        // V5.9.936 â€” TIGHTENED FURTHER to 2min (was 5min in V5.9.935,
                        // 10min in V5.9.914). Operator directive: "10 minutes
                        // is way too long, 2 mins."
                        //
                        // The original 10-min was set when supervisor had NO
                        // inner progress ticker, so a legitimate chunk-of-500
                        // run could plausibly take 8+ min before refreshing.
                        // With the V5.9.935 inner ticker (now 10s, see below)
                        // refreshing markProgress every 10s while supervisor
                        // is alive, 2min is plenty of headroom and any longer
                        // gap IS genuinely wedged.
                        //
                        // Math: ticker fires every 10s â†’ 12 refreshes in 2min.
                        // For the ceiling to fire, all 12 of those refreshes
                        // would have to fail to register, which means the
                        // supervisor coroutine itself is dead (not just slow).
                        // V5.0.6544 â€” Job.isActive is not proof of liveness. A
                        // running coroutine with no phase beacon for 120s is a
                        // stalled worker regardless of its current phase.
                        progressGapMs >= 120_000L -> {
                            ErrorLogger.warn(
                                "BotService",
                                "ğŸ©º LOOP_HEARTBEAT(alarm): FORCED RESCUE â€” progress stalled ${progressGapMs / 1000}s in active phase=$phase (past 2-min ceiling, V5.9.936 with 10s inner ticker)"
                            )
                            ForensicLogger.lifecycle(
                                "HEARTBEAT_RESCUE_PROGRESS_TIMEOUT_6544",
                                "progressGapSec=${progressGapMs / 1000} phase=$phase activePhase=$phaseIsActive ceilingMs=120000"
                            )
                            // V5.9.935 â€” track consecutive same-phase rescues.
                            // If we rescue twice in <120s on the same phase, the
                            // restart isn't fixing anything; auto-stop so UI matches reality.
                            val nowMs = System.currentTimeMillis()
                            val sincePrevRescueMs = nowMs - lastRescueMs
                            if (lastRescuePhase == phase && sincePrevRescueMs in 0..120_000L) {
                                consecutiveSamePhaseRescues++
                            } else {
                                consecutiveSamePhaseRescues = 1
                            }
                            lastRescueMs = nowMs
                            lastRescuePhase = phase

                            if (consecutiveSamePhaseRescues >= 2) {
                                // V5.9.1006 â€” DO NOT SELF-STOP.
                                // The operator's snapshot shows the bot can look stalled in
                                // POST_SUPERVISOR while scanner/intake/exit ticks are still alive.
                                // This branch was literally flipping status.running=false and
                                // cancelling loopJob after two same-phase rescues, creating the
                                // "bot stops itself" failure. A watchdog may rescue/relaunch;
                                // it must never become an autonomous STOP button.
                                ErrorLogger.error(
                                    "BotService",
                                    "ğŸ›Ÿ BOT REDEADLOCK â€” same phase=$phase $consecutiveSamePhaseRescues times within 120s. Forcing rescue; NOT auto-stopping."
                                )
                                ForensicLogger.lifecycle(
                                    "BOT_REDEADLOCK_RESCUE_NO_AUTOSTOP",
                                    "phase=$phase consecutiveRescues=$consecutiveSamePhaseRescues sincePrevSec=${sincePrevRescueMs/1000} running=${status.running}"
                                )
                                try { addLog("ğŸ›Ÿ Loop wedged in $phase â€” forcing rescue, bot remains running") } catch (_: Throwable) {}
                                consecutiveSamePhaseRescues = 0
                                performServiceScopeRescue(lj, phase, progressGapMs)
                            } else {
                                performServiceScopeRescue(lj, phase, progressGapMs)
                            }
                        }

                        // Job alive, progress stalled, BUT inside a critical
                        // long-running phase (SUPERVISOR/EXIT_SWEEP/etc.)
                        // AND still within the 10-min ceiling:
                        // suppress rescue, the loop is doing legitimate work.
                        phaseIsActive -> {
                            ErrorLogger.warn(
                                "BotService",
                                "ğŸ©º LOOP_HEARTBEAT(alarm): SUPPRESSED â€” progress stalled ${progressGapMs / 1000}s but phase=$phase is in active set (under 10-min ceiling)"
                            )
                            ForensicLogger.lifecycle(
                                "HEARTBEAT_RESCUE_SUPPRESSED_ACTIVE_PHASE",
                                "progressGapSec=${progressGapMs / 1000} phase=$phase"
                            )
                        }

                        // V5.9.919 â€” IDLE/UNKNOWN PHASE CEILING.
                        // Operator V5.9.916 dump showed phase=RESCUE_LAUNCHING
                        // stuck for 242s+ with HEARTBEAT_SLOW_NO_RESCUE
                        // logged every alarm â€” i.e. the rescue's own coroutine
                        // never reached its first markProgress() to advance
                        // the phase out of RESCUE_LAUNCHING. The bot is dead
                        // but heartbeat refuses to nuke it because the job
                        // object is still .isActive=true (queued, never ran).
                        //
                        // 5-min ceiling for non-active phases. If we've been
                        // sitting in IDLE / RESCUE_LAUNCHING / unknown phase
                        // for 5min with no markProgress, the coroutine is
                        // wedged â†’ force rescue. Lower than the 10-min
                        // active-phase ceiling because IDLE / RESCUE_LAUNCHING
                        // doing 5min of legitimate work is nonsensical.
                        // V5.9.936 â€” idle/unknown phase ceiling also tightened
                        // 5min â†’ 2min. The bot has no business sitting in
                        // RESCUE_LAUNCHING / IDLE / unknown for more than 2 min.
                        progressGapMs >= 120_000L -> {
                            ErrorLogger.warn(
                                "BotService",
                                "ğŸ©º LOOP_HEARTBEAT(alarm): FORCED RESCUE â€” progress stalled ${progressGapMs / 1000}s in idle/unknown phase=$phase (past 2-min ceiling, V5.9.936)"
                            )
                            ForensicLogger.lifecycle(
                                "HEARTBEAT_RESCUE_IDLE_PHASE_TIMEOUT",
                                "progressGapSec=${progressGapMs / 1000} phase=$phase ceilingMs=300000"
                            )
                            if (now - lastRescueMs < LOOP_RESCUE_MIN_INTERVAL_MS && active && progressGapMs < LOOP_RESCUE_FORCE_STALE_MS) {
                                ForensicLogger.lifecycle(
                                    "HEARTBEAT_RESCUE_DEBOUNCED_ACTIVE_JOB",
                                    "progressGapSec=${progressGapMs / 1000} phase=$phase sinceLastRescueSec=${(now - lastRescueMs) / 1000}"
                                )
                            } else {
                                performServiceScopeRescue(lj, phase, progressGapMs)
                            }
                        }
                        // Job alive, progress stalled, NOT in a known
                        // active phase, but under the 5-min ceiling.
                        // Log loudly â€” the job will either resume on its own
                        // or trip the ceiling above.
                        else -> {
                            ErrorLogger.warn(
                                "BotService",
                                "ğŸ©º LOOP_HEARTBEAT(alarm): SLOW_NO_RESCUE â€” progress stalled ${progressGapMs / 1000}s phase=$phase but job still active"
                            )
                            addLog("âš ï¸ Loop slow (${progressGapMs / 1000}s no progress, phase=$phase) â€” not cancelling")
                            ForensicLogger.lifecycle(
                                "HEARTBEAT_SLOW_NO_RESCUE",
                                "progressGapSec=${progressGapMs / 1000} phase=$phase active=true"
                            )
                        }
                    }

                    // Re-arm next alarm so the chain keeps going. Only re-arm
                    // while the bot is supposed to be running â€” otherwise the
                    // alarm cancels itself naturally on user Stop via
                    // cancelLoopHeartbeatAlarm().
                    if (running) scheduleLoopHeartbeatAlarm()
                } catch (e: Throwable) {
                    ErrorLogger.warn("BotService", "LOOP_HEARTBEAT alarm handler crashed: ${e.message}")
                }
            }
        }
        // V5.9.707 â€” Reverted START_NOT_STICKY back to START_STICKY now that the
        // manual-stop latch (KEY_MANUAL_STOP_REQUESTED) guards against random restarts.
        //
        // The V5.9.330 rationale was: Journal OOM killed process â†’ START_STICKY
        // restarted bot even when user had stopped it. That is now safe because:
        //   a) The manual-stop latch blocks any non-user-requested start.
        //   b) The null-intent branch below checks wasRunning && !manualStop before
        //      calling startBot() â€” so a system-kill resurrection only fires when
        //      the user genuinely had the bot running.
        //
        // START_STICKY: OS auto-restarts service with null intent after system kill.
        // The null-intent handler below uses the same wasRunning/manualStop guards.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // V5.0.6431 Â§K â€” stop the independent reconciler scheduler.
        try { com.lifecyclebot.engine.truth.IndependentReconcilerScheduler6431.stop() } catch (_: Throwable) {}
        ErrorLogger.warn("BotService", "onDestroy() called - service being destroyed")
        serviceForegroundActive6487 = false
        try { com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.setRuntimeActive(false, "BotService.onDestroy6487") } catch (_: Throwable) {}

        // V5.9.438 â€” flush outcome-learning trackers so nothing is lost on shutdown.
        try { LearningPersistence.saveAll() } catch (_: Exception) {}

        // V5.9.1473 â€” operator: "updates wipe held tokens and the treasury balance."
        // Positions are already force-saved below; the treasury was the missing
        // piece â€” onDestroy never persisted it, so a system kill (APK update,
        // OOM, Doze) lost every gain since the last 5s-throttled autoSave.
        // Save it unconditionally here, before any restart/branch logic, so it
        // is durable in the ~5s SIGKILL window. Cheap + idempotent.
        try { TreasuryManager.save(applicationContext) } catch (_: Exception) {}

        // V5.9.948 â€” TokenMetaCache shutdown checkpoint. Cheap + idempotent.
        try {
            val flushed = com.lifecyclebot.engine.TokenMetaCache
                .get(applicationContext).flushNow()
            ErrorLogger.info("BotService", "TokenMetaCache shutdown flushed=$flushed dirty rows")
        } catch (_: Throwable) { /* shutdown best-effort */ }
        
        // V5.9.673 â€” DO NOT attempt to liquidate positions on a SYSTEM-INITIATED
        // destroy. The V5.9.661 mandate ("every stop MUST close all positions")
        // was meant for USER-INITIATED stops (which run through stopBot() and
        // already close positions there before destroy fires). When Android
        // kills us for OOM / Doze / battery / ANR reasons, we have ~5 seconds
        // before SIGKILL â€” far less than the 10-30s a Jupiter swap needs.
        // Attempting the swap here usually:
        //   1. Fails because the process dies mid-swap.
        //   2. WORSE: mutates position.isOpen = false locally before the swap
        //      lands, so the AlarmManager-scheduled restart sees no open
        //      position even though the tokens are still on-chain â†’ ORPHAN.
        // Operator confirmed RMG buy (+353% gain, 436195 tokens) became
        // invisible to the bot after one such kill-restart cycle. New
        // behaviour: persist position state durably so the restart's
        // PositionPersistence.restorePositions + StartupReconciler chain can
        // re-adopt the position from on-chain truth on next boot.
        val onDestroyHeldCount = heldPositionCountForRescue()
        val onDestroyWasRunning = try {
            getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
        } catch (_: Throwable) { false }
        if ((status.running || onDestroyHeldCount > 0 || onDestroyWasRunning) && !isManualStopRequested(applicationContext) && !persistenceFinalizedByStop) {
            try {
                val tokensCopy = synchronized(status.tokens) { status.tokens.toMap() }
                val openCount = maxOf(tokensCopy.values.count { it.position.isOpen }, onDestroyHeldCount)
                ErrorLogger.warn("BotService",
                    "onDestroy: SYSTEM-INITIATED destroy (not manual stop). " +
                    "$openCount open position(s) â€” persisting to disk for restart recovery. " +
                    "NOT attempting close (would orphan positions due to 5s SIGKILL window).")
                // Force-save so the 30s rate-limiter in saveAllPositions does
                // not skip this critical pre-death flush.
                PositionPersistence.saveAllPositions(tokensCopy, force = true)
                ForensicLogger.lifecycle(
                    "ONDESTROY_SYSTEM_KILL",
                    "openCount=$openCount persisted=true closeAttempted=false"
                )
            } catch (e: Exception) {
                ErrorLogger.error("BotService",
                    "onDestroy: Could not persist positions before system kill: ${e.message}", e)
            }

            // Schedule a restart
            // V5.9.674 â€” DUAL-ALARM restart to defeat Android Doze throttling.
            // setExactAndAllowWhileIdle is rate-limited to ~9 MINUTES while
            // the device is in Doze (operator observed exactly that: bot
            // killed, restart deferred ~10 minutes). setAlarmClock bypasses
            // Doze rate-limiting because it is treated as a user-facing alarm.
            // Schedule BOTH:
            //   â€¢ request code 2: 1s setExactAndAllowWhileIdle (best-effort
            //     fast path when not in Doze)
            //   â€¢ request code 5: 5s setAlarmClock (Doze-bypass guarantee)
            // This matches onTaskRemoved's two-layer pattern.
            //
            // V5.9.1081 â€” DEDUPE + manual-stop guard. The persistence calls
            // BELOW (EdgeLearning, positions, EntryIntelligence, ExitIntelligence)
            // MUST always run â€” only skip the alarm-scheduling block when the
            // operator has manually stopped.
            val manualStopOnDestroy = try {
                getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_MANUAL_STOP_REQUESTED, false)
            } catch (_: Throwable) { false }
            if (manualStopOnDestroy) {
                try { ForensicLogger.lifecycle("CRASH_RECOVERY_RESTART_SKIPPED_MANUAL_STOP", "site=onDestroy") } catch (_: Throwable) {}
                ErrorLogger.warn("BotService", "onDestroy: skipping restart â€” manual stop latch active")
            } else {
                cancelAllRestartAlarms()
                try { ForensicLogger.lifecycle("CRASH_RECOVERY_DUPLICATE_CANCELLED", "site=onDestroy") } catch (_: Throwable) {}
                ErrorLogger.warn("BotService", "Bot was running - scheduling DUAL restart alarms (1s + 5s AlarmClock backup)")
                val restartIntent = Intent(applicationContext, BotService::class.java).apply {
                    action = ACTION_START
                }
            val am = getSystemService(android.app.AlarmManager::class.java)

            // Fast-path: 1s setExactAndAllowWhileIdle (works when not Doze)
            val piFast = android.app.PendingIntent.getService(
                this, 2, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1_000,
                piFast
            )

            // Doze-bypass backup: 5s setAlarmClock â€” fires within seconds
            // even during deep Doze. The fast-path's FLAG_ONE_SHOT means it
            // self-clears once delivered, so if it fires before this backup
            // the backup will land on an already-running service (handled
            // gracefully by the keep-alive branch in onStartCommand at L1142).
            try {
                val piBackup = android.app.PendingIntent.getService(
                    this, 5, restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val showPi = android.app.PendingIntent.getActivity(
                    this, 6, Intent(applicationContext, com.lifecyclebot.ui.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                am?.setAlarmClock(
                    android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 5_000, showPi),
                    piBackup
                )
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "onDestroy: AlarmClock backup failed: ${e.message}")
            }
            try { ForensicLogger.lifecycle("CRASH_RECOVERY_RESTART_SCHEDULED_ONCE", "site=onDestroy rc=2+5") } catch (_: Throwable) {}
            } // V5.9.1081 â€” closing the else branch of manualStopOnDestroy
        }
        
        // Save EdgeLearning thresholds before shutdown
        try {
            val edgeLearningPrefs = getSharedPreferences("edge_learning", android.content.Context.MODE_PRIVATE)
            EdgeLearning.saveToPrefs(edgeLearningPrefs)
            ErrorLogger.info("BotService", "ğŸ’¾ EdgeLearning saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save EdgeLearning: ${e.message}", e)
        }
        
        // V5.6.9: Save open positions before shutdown for crash recovery.
        // V5.0.3789 â€” but NEVER re-save after a manual stop already finalized
        // persistence (PositionPersistence.clear()). Re-saving stale pre-clear
        // status.tokens here was the root of the stop/restart ledger drift.
        if (!persistenceFinalizedByStop && !isManualStopRequested(applicationContext)) {
            try {
                val tokensCopy = synchronized(status.tokens) { status.tokens.toMap() }
                PositionPersistence.saveAllPositions(tokensCopy, force = true)
                val savedCount = PositionPersistence.getPersistedCount()
                ErrorLogger.info("BotService", "ğŸ’¾ Position Persistence: Saved $savedCount positions before destroy")
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Failed to save positions: ${e.message}", e)
            }
        } else {
            try { ForensicLogger.lifecycle("ONDESTROY_SAVE_SUPPRESSED", "reason=PERSISTENCE_FINALIZED_BY_STOP finalizedByStop=$persistenceFinalizedByStop manualStop=${isManualStopRequested(applicationContext)}") } catch (_: Throwable) {}
        }
        
        // Save Entry Intelligence AI before shutdown
        try {
            val entryAiPrefs = getSharedPreferences("entry_intelligence", android.content.Context.MODE_PRIVATE)
            EntryIntelligence.saveToPrefs(entryAiPrefs)
            ErrorLogger.info("BotService", "ğŸ’¾ EntryIntelligence saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save EntryIntelligence: ${e.message}", e)
        }
        
        // Save Exit Intelligence AI before shutdown
        try {
            val exitAiPrefs = getSharedPreferences("exit_intelligence", android.content.Context.MODE_PRIVATE)
            ExitIntelligence.saveToPrefs(exitAiPrefs)
            ErrorLogger.info("BotService", "ğŸ’¾ ExitIntelligence saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save ExitIntelligence: ${e.message}", e)
        }
        
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // SAVE NEW AI LAYERS BEFORE SHUTDOWN
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        
        // Save WhaleTrackerAI
        try {
            val whaleAiPrefs = getSharedPreferences("whale_tracker_ai", android.content.Context.MODE_PRIVATE)
            whaleAiPrefs.edit().putString("data", WhaleTrackerAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ WhaleTrackerAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save WhaleTrackerAI: ${e.message}", e)
        }
        
        // Save MarketRegimeAI
        try {
            val regimeAiPrefs = getSharedPreferences("market_regime_ai", android.content.Context.MODE_PRIVATE)
            regimeAiPrefs.edit().putString("data", MarketRegimeAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ MarketRegimeAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save MarketRegimeAI: ${e.message}", e)
        }
        
        // Save MomentumPredictorAI
        try {
            val momentumAiPrefs = getSharedPreferences("momentum_predictor_ai", android.content.Context.MODE_PRIVATE)
            momentumAiPrefs.edit().putString("data", MomentumPredictorAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ MomentumPredictorAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save MomentumPredictorAI: ${e.message}", e)
        }
        
        // Save NarrativeDetectorAI
        try {
            val narrativeAiPrefs = getSharedPreferences("narrative_detector_ai", android.content.Context.MODE_PRIVATE)
            narrativeAiPrefs.edit().putString("data", NarrativeDetectorAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ NarrativeDetectorAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save NarrativeDetectorAI: ${e.message}", e)
        }
        
        // Save TimeOptimizationAI
        try {
            val timeAiPrefs = getSharedPreferences("time_optimization_ai", android.content.Context.MODE_PRIVATE)
            timeAiPrefs.edit().putString("data", TimeOptimizationAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ TimeOptimizationAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save TimeOptimizationAI: ${e.message}", e)
        }
        
        // Save LiquidityDepthAI
        try {
            val liqAiPrefs = getSharedPreferences("liquidity_depth_ai", android.content.Context.MODE_PRIVATE)
            liqAiPrefs.edit().putString("data", LiquidityDepthAI.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ LiquidityDepthAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save LiquidityDepthAI: ${e.message}", e)
        }
        
        // Save AICrossTalk
        try {
            val crossTalkPrefs = getSharedPreferences("ai_crosstalk", android.content.Context.MODE_PRIVATE)
            crossTalkPrefs.edit().putString("data", AICrossTalk.saveToJson().toString()).apply()
            ErrorLogger.info("BotService", "ğŸ’¾ AICrossTalk saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save AICrossTalk: ${e.message}", e)
        }
        
        // V5.7: Save PerpsTraderAI
        try {
            com.lifecyclebot.perps.PerpsTraderAI.save(force = true)
            ErrorLogger.info("BotService", "ğŸ’¾ PerpsTraderAI saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save PerpsTraderAI: ${e.message}", e)
        }
        
        // V5.7: Save PerpsLearningBridge
        try {
            com.lifecyclebot.perps.PerpsLearningBridge.save()
            ErrorLogger.info("BotService", "ğŸ’¾ PerpsLearningBridge saved before destroy")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "Failed to save PerpsLearningBridge: ${e.message}", e)
        }
        
        // V5.7.3: Stop PerpsExecutionEngine
        try {
            com.lifecyclebot.perps.PerpsExecutionEngine.stop()
            ErrorLogger.info("BotService", "âš¡ PerpsExecutionEngine stopped")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsExecutionEngine stop error: ${e.message}", e)
        }
        
        // V5.7.5: Stop TokenizedStockTrader
        try {
            com.lifecyclebot.perps.TokenizedStockTrader.stop()
            ErrorLogger.info("BotService", "ğŸ“ˆ TokenizedStockTrader stopped")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "TokenizedStockTrader stop error: ${e.message}", e)
        }
        
        // V5.7.3: Stop PerpsAutoReplayLearner
        try {
            com.lifecyclebot.perps.PerpsAutoReplayLearner.stop()
            ErrorLogger.info("BotService", "ğŸ¬ PerpsAutoReplayLearner stopped")
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "PerpsAutoReplayLearner stop error: ${e.message}", e)
        }
        
        // V5.7.3: Stop Network Signal Auto-Buyer
        try {
            com.lifecyclebot.perps.NetworkSignalAutoBuyer.stop()
            ErrorLogger.info("BotService", "ğŸ“¡ NetworkSignalAutoBuyer stopped")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "NetworkSignalAutoBuyer stop error: ${e.message}")
        }

        // V5.9.357: Stop Macro Pollers
        try { MacroPollers.stop() } catch (_: Exception) {}
        
        // Shutdown CollectiveLearning
        try {
            com.lifecyclebot.collective.CollectiveLearning.shutdown()
            ErrorLogger.info("BotService", "ğŸŒ CollectiveLearning shutdown")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "CollectiveLearning shutdown error: ${e.message}")
        }
        
        // Shutdown V3 Engine
        try {
            com.lifecyclebot.v3.V3EngineManager.shutdown()
            ErrorLogger.info("BotService", "âš¡ V3 Engine shutdown")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "V3 Engine shutdown error: ${e.message}")
        }
        
        // Shutdown V3 Shadow Learning Engine
        try {
            com.lifecyclebot.v3.learning.ShadowLearningEngine.stop()
            ErrorLogger.info("BotService", "ğŸŒ‘ V3 ShadowLearning shutdown")
        } catch (e: Exception) {
            ErrorLogger.debug("BotService", "V3 ShadowLearning shutdown error: ${e.message}")
        }
        
        serviceJob6647.cancel()
        try { acceptanceWindowFuture6668?.cancel(false) } catch (_: Throwable) {}
        acceptanceWindowFuture6668 = null
        try { acceptanceWindowExecutor6668.shutdownNow() } catch (_: Throwable) {}
        try { exitDispatcher6647.close() } catch (_: Throwable) {}
        try { exitWorkerDispatcher6647.close() } catch (_: Throwable) {}
        try { supervisorDispatcher6647.close() } catch (_: Throwable) {}
        try { serviceDispatcher6647.close() } catch (_: Throwable) {}
        try { exitExecutor6647.shutdownNow() } catch (_: Throwable) {}
        try { exitWorkerExecutor6647.shutdownNow() } catch (_: Throwable) {}
        try { supervisorExecutor6647.shutdownNow() } catch (_: Throwable) {}
        try { serviceExecutor6647.shutdownNow() } catch (_: Throwable) {}
        try { botLoopExecutor.shutdownNow() } catch (_: Throwable) {}
    }

    /**
     * Called when user swipes the app from the recent apps list.
     * Schedules a restart via a pending intent so the foreground service
     * resumes automatically rather than dying silently.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ErrorLogger.warn("BotService", "onTaskRemoved() called - app swiped from recents, running=${status.running}")

        // V5.9.1473 â€” persist treasury + learning on task removal too (mirrors
        // onDestroy). Swiping from recents / OEM task-kill is another path that
        // previously dropped treasury gains.
        try { TreasuryManager.save(applicationContext) } catch (_: Exception) {}
        try { LearningPersistence.saveAll() } catch (_: Exception) {}
        
        val taskRemovedHeldCount = heldPositionCountForRescue()
        val taskRemovedWasRunning = try {
            getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
        } catch (_: Throwable) { false }
        if ((status.running || taskRemovedHeldCount > 0 || taskRemovedWasRunning) && !isManualStopRequested(applicationContext)) {
            try { ForensicLogger.lifecycle("TASK_REMOVED_STRANDED_POSITION_RESCUE", "statusRunning=${status.running} held=$taskRemovedHeldCount wasRunning=$taskRemovedWasRunning") } catch (_: Throwable) {}
            // V5.9.1081 â€” DEDUPE: cancel any prior pending restart alarms first
            // so onTaskRemoved cannot stack a second pair on top of an existing
            // onDestroy-armed pair.
            cancelAllRestartAlarms()
            try { ForensicLogger.lifecycle("CRASH_RECOVERY_DUPLICATE_CANCELLED", "site=onTaskRemoved") } catch (_: Throwable) {}
            // V5.6.8: Multiple restart mechanisms for aggressive OEMs
            val restartIntent = Intent(applicationContext, BotService::class.java).apply {
                action = ACTION_START
            }
            val am = getSystemService(android.app.AlarmManager::class.java)
            
            // Immediate restart (1 second)
            val pi1 = android.app.PendingIntent.getService(
                this, 1, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1_000,
                pi1
            )
            
            // Backup restart (5 seconds) with AlarmClock for highest priority
            try {
                val pi2 = android.app.PendingIntent.getService(
                    this, 3, restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val showPi = android.app.PendingIntent.getActivity(
                    this, 4, Intent(applicationContext, com.lifecyclebot.ui.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                am?.setAlarmClock(
                    android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 5_000, showPi),
                    pi2
                )
            } catch (e: Exception) {
                ErrorLogger.warn("BotService", "onTaskRemoved: AlarmClock fallback: ${e.message}")
            }
            
            ErrorLogger.info("BotService", "Scheduled restart alarms (1s + 5s backup)")
            try { ForensicLogger.lifecycle("CRASH_RECOVERY_RESTART_SCHEDULED_ONCE", "site=onTaskRemoved rc=1+3") } catch (_: Throwable) {}
        } else if (status.running && isManualStopRequested(applicationContext)) {
            try { ForensicLogger.lifecycle("CRASH_RECOVERY_RESTART_SKIPPED_MANUAL_STOP", "site=onTaskRemoved") } catch (_: Throwable) {}
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // V5.9.925 â€” MEMORY-PRESSURE HANDLER (random-stop hardening)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //
    // Operator: "once started the bot should run uninhibited unless stopped by
    // user". V5.9.913 fixed crash-loop disarms but did NOT cover OS-initiated
    // process kills under memory pressure. Symptom from the operator side
    // looks identical: bot "stops itself" with no log entry, comes back later
    // when watchdog cycles (looks like a "random restart").
    //
    // onTrimMemory is the OS's polite warning before it kills foreground
    // services to free RAM. We:
    //   1. Stay running â€” DO NOT call status.running=false.
    //   2. Free heavy in-memory caches that we can rebuild lazily (history
    //      rings beyond what trailing stops need, oldest forensic log entries,
    //      DexScreener pair cache).
    //   3. Suggest a GC so the OS sees a smaller working set on the next
    //      poll and is less likely to kill us.
    //
    // We deliberately do NOT touch position state, scanner watchlist, or any
    // exit-relevant data. The whole point is to survive the squeeze without
    // dropping any trade-safety state.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            val levelTag = when (level) {
                TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                TRIM_MEMORY_RUNNING_LOW      -> "RUNNING_LOW"
                TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                TRIM_MEMORY_UI_HIDDEN        -> "UI_HIDDEN"
                TRIM_MEMORY_BACKGROUND       -> "BACKGROUND"
                TRIM_MEMORY_MODERATE         -> "MODERATE"
                TRIM_MEMORY_COMPLETE         -> "COMPLETE"
                else                          -> "LEVEL_$level"
            }
            ForensicLogger.lifecycle("TRIM_MEMORY", "level=$levelTag running=${status.running}")

            // Only the RUNNING_* levels indicate live memory pressure where the OS
            // may kill us. UI_HIDDEN/BACKGROUND just means the user navigated away
            // and is normal â€” no action needed.
            if (level >= TRIM_MEMORY_RUNNING_LOW) {
                // Trim each token's candle history beyond what trailing stops need.
                // V5.9.749 caps at 300; trailing-stop / pattern AIs only look at the
                // last ~120 candles. Drop the oldest 100 across all tokens.
                try {
                    var trimmed = 0
                    val tokens = synchronized(status.tokens) { status.tokens.values.toList() }
                    for (t in tokens) {
                        synchronized(t.history) {
                            while (t.history.size > 200) {
                                t.history.removeFirst()
                                trimmed++
                            }
                        }
                    }
                    ErrorLogger.info("BotService", "ğŸ§¹ onTrimMemory($levelTag): trimmed $trimmed old candles to free RAM")
                } catch (_: Throwable) {}

                // Hint GC.
                try { System.gc() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            // Never let onTrimMemory crash the service.
            try { ErrorLogger.warn("BotService", "onTrimMemory error: ${e.message?.take(80)}") } catch (_: Throwable) {}
        }
    }


    /**
     * V5.0.3872 â€” PAPER WALLET SOURCE-AUTHORITY REPAIR.
     * Repairs only catastrophic paper-cash drift where the persisted wallet is
     * impossible versus journal-derived authority: configured start + realised
     * journal PnL - current open paper cost basis.
     */
    private fun repairUnifiedPaperWalletIfImpossible(source: String) {
        try {
            val cfgNow = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            if (!cfgNow.paperMode) return
            val current = status.paperWalletSol
            val stats = try { TradeHistoryStore.getStatsCached() } catch (_: Throwable) { null }
            val realized = stats?.totalPnlSol?.takeIf { it.isFinite() } ?: 0.0
            val openCost = try {
                status.openPositions.asSequence()
                    .filter { it.position.isPaperPosition && it.position.isOpen }
                    .sumOf { it.position.costSol.takeIf { v -> v.isFinite() && v > 0.0 } ?: 0.0 }
            } catch (_: Throwable) { 0.0 }
            val start = cfgNow.paperSimulatedBalance.takeIf { it.isFinite() && it > 0.001 } ?: 11.76
            val expectedCash = (start + realized - openCost).coerceAtLeast(0.0)
            val tolerance = maxOf(start * 20.0, kotlin.math.abs(realized) * 5.0, openCost * 5.0, 25.0)
            val upper = expectedCash + tolerance
            val impossible = !current.isFinite() || current < -0.001 || current > upper
            if (!impossible) return
            ErrorLogger.warn(
                "PaperWallet",
                "PAPER_WALLET_IMPOSSIBLE_REPAIRED source=$source cash=${current.fmt(4)} expected=${expectedCash.fmt(4)} upper=${upper.fmt(4)} start=${start.fmt(4)} realized=${realized.fmt(4)} openCost=${openCost.fmt(4)}"
            )
            try { ForensicLogger.lifecycle("PAPER_WALLET_IMPOSSIBLE_REPAIRED", "source=$source cash=${current.fmt(4)} repaired=${expectedCash.fmt(4)} realized=${realized.fmt(4)} openCost=${openCost.fmt(4)} upper=${upper.fmt(4)}") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("PAPER_WALLET_IMPOSSIBLE_REPAIRED") } catch (_: Throwable) {}
            // V5.0.6475 â€” this repair is diagnostic only. Journal/status-derived
            // expectedCash is not allowed to mutate wallet authority; canonical
            // PaperAccountLedger projection owns displayed paper cash.
            try { PipelineHealthCollector.labelInc("PAPER_WALLET_IMPOSSIBLE_DIAGNOSTIC_ONLY_6475") } catch (_: Throwable) {}
        } catch (e: Throwable) {
            try { ErrorLogger.warn("PaperWallet", "repairUnifiedPaperWalletIfImpossible failed: ${e.message}") } catch (_: Throwable) {}
        }
    }


    /**
     * V5.0.6448 â€” SINGLE PAPER CAPITAL AUTHORITY BRIDGE.
     * PaperAccountLedger6430 is the transactional paper-cash authority. The
     * UI/status wallet and CanonicalPositionAuthority.paperCashSol are facades
     * over the same ledger snapshot. If a pre-6448 runtime left the ledger cash
     * negative while the displayed paper wallet was healthy, repair cash once
     * from the displayed wallet, then publish ledger cash back to all facades.
     */
    private fun syncPaperCapitalAuthority6448(source: String) {
        try {
            val cfgNow = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            if (!cfgNow.paperMode) return
            val displayedCash = status.paperWalletSol.takeIf { it.isFinite() && it >= 0.0 }
                ?: try { FluidLearning.getPaperBalance() } catch (_: Throwable) { 0.0 }
            // V5.0.6475 â€” do not repair canonical ledger cash from displayed UI
            // cash. Displayed cash is projection-only; ledger is authority.
            // V5.0.6630 Â§CRITICAL_CAPITAL_AUTHORITY_RECOVERY (operator Feb 2026:
            //   "REMOVE any bot_loop_top/UI/replay code that writes 0.0 back
            //    into capital. PAPER_CAPITAL_AUTHORITY_SYNCED must publish
            //    the canonical snapshot. It must not manufacture
            //    ledgerCash=0.00000.")
            // The previous `coerceAtLeast(0.0)` FLOORED negative ledger cash
            // (an invariant violation) to zero and then wrote 0.0 into
            // CanonicalPositionAuthority and FluidLearning. That produced
            // the operator-reported "PAPER_CAPITAL_AUTHORITY_SYNCED
            // ledgerCash=0.00000 while ledger says -28.7230" contradiction
            // and starved the sizing resolver with NO_WALLET.
            val ledgerCashRaw = try { com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.cashSol() } catch (_: Throwable) { Double.NaN }
            if (!ledgerCashRaw.isFinite()) return
            if (ledgerCashRaw < 0.0) {
                // Negative cash is an accounting invariant violation. Do NOT
                // silently write 0.0 into every downstream projection â€” that
                // manufactures a phantom balance that hides the defect and
                // kills entries via NO_WALLET. Emit a diagnostic label and
                // return without mutating projections.
                try {
                    PipelineHealthCollector.labelInc("PAPER_CAPITAL_AUTHORITY_NEGATIVE_CASH_HELD_6630")
                    ForensicLogger.lifecycle(
                        "PAPER_CAPITAL_AUTHORITY_NEGATIVE_CASH_HELD_6630",
                        "source=$source ledgerCash=${"%.5f".format(ledgerCashRaw)} " +
                            "displayedWas=${"%.5f".format(displayedCash)} " +
                            "action=do_not_write_zero_to_projections",
                    )
                } catch (_: Throwable) {}
                return
            }
            val ledgerCash = ledgerCashRaw
            status.paperWalletSol = ledgerCash
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.setPaperCash(ledgerCash, "paper_account_ledger_facade_6448:$source")
            try { FluidLearning.forceSetBalance(ledgerCash) } catch (_: Throwable) {}
            // V5.0.6606 Â§REPAIR_MAIN_THREAD_ANR (operator directive on
            //   V5.0.6604 forensic dump: bot-loop cycles reached 25s with
            //   MECHANICAL_FAULT/ui/reporting root cause and "EXIT sweep
            //   starts but never completes" sentinel). syncPaperCapitalAuthority
            //   fires on every bot-loop iteration; the SharedPreferences write
            //   and per-cycle ForensicLogger emit were happening on the loop
            //   coroutine. Offload both to AppDispatchers.sideEffect so the
            //   bot loop never blocks on XML flush or forensic file I/O.
            //   Reads (cashSol) stay in-line (cheap atomic).
            scope.launch(com.lifecyclebot.util.AppDispatchers.sideEffect) {
                try { PaperWalletStore.persist(applicationContext, ledgerCash) } catch (_: Throwable) {}
                try {
                    PipelineHealthCollector.labelInc("PAPER_CAPITAL_AUTHORITY_SYNCED_6448")
                    ForensicLogger.lifecycle("PAPER_CAPITAL_AUTHORITY_SYNCED_6448", "source=$source ledgerCash=${"%.5f".format(ledgerCash)} displayedWas=${"%.5f".format(displayedCash)}")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }


    // â”€â”€ start / stop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // V5.9.54: one-time reconciliation of historical sub-trader P&L into
    // the unified main wallet. Safe to call repeatedly â€” guarded by a
    // SharedPreferences flag and drift-checked on subsequent boots.
    private fun reconcileUnifiedPaperWallet() {
        val prefs = getSharedPreferences("bot_runtime", MODE_PRIVATE)
        val MIGRATION_KEY = "unified_wallet_migration_v5_9_54"
        val LAST_SUB_PNL_KEY = "last_sub_trader_pnl_sol"

        val currentSubPnl = try {
            com.lifecyclebot.perps.TokenizedStockTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.CommoditiesTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.MetalsTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.ForexTrader.getTotalPnlSol() +
            com.lifecyclebot.perps.CryptoAltTrader.getTotalPnlSol()
        } catch (_: Exception) { 0.0 }

        val alreadyMigrated = prefs.getBoolean(MIGRATION_KEY, false)

        if (!alreadyMigrated) {
            // First-time migration: credit ALL historical realized P&L.
            if (kotlin.math.abs(currentSubPnl) > 0.001) {
                ErrorLogger.info("BotService",
                    "V5.0.6486 legacy aggregate PnL migration suppressed; durable typed economic events own paper capital")
                try { PipelineHealthCollector.labelInc("LEGACY_AGGREGATE_PNL_CREDIT_SUPPRESSED_6486") } catch (_: Throwable) {}
            }
            prefs.edit()
                .putBoolean(MIGRATION_KEY, true)
                .putFloat(LAST_SUB_PNL_KEY, currentSubPnl.toFloat())
                .apply()
            return
        }

        // Subsequent boots: drift-check. If live crediting from V5.9.48 is
        // working, (currentSubPnl - lastSubPnl) should exactly equal the
        // credits already applied since last boot. We can't easily verify
        // the other direction, but we can at least surface a drift warning
        // if a sub-trader's P&L advanced but the main wallet didn't move
        // alongside (e.g. new code that forgot to call creditUnifiedPaperSol).
        val lastSubPnl = prefs.getFloat(LAST_SUB_PNL_KEY, 0f).toDouble()
        val subDelta = currentSubPnl - lastSubPnl
        if (kotlin.math.abs(subDelta) > 0.01) {
            ErrorLogger.debug("BotService",
                "Unified-wallet drift snapshot: sub-trader realized P&L moved " +
                "${"%.4f".format(subDelta)} SOL since last boot.")
        }
        prefs.edit().putFloat(LAST_SUB_PNL_KEY, currentSubPnl.toFloat()).apply()
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // V5.9.669: V3 EXECUTION BRIDGE
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    /**
     * Routes V3 engine execution decisions into the real executor.doBuy
     * pipeline. V3 is a MAIN TRADER wired to the learning loop; previously
     * its onExecute was null, so V3 decisions silently failed and legacy
     * traders executed instead (at a fraction of V3's sizing and without
     * feeding V3's outcome tracker).
     *
     * Reuses the same wallet/walletSol resolution as manualBuy() (V5.9.495o
     * cached-balance fallback pattern).
     *
     * Returns ExecuteResult so V3's TradeExecutor.executeCallback can
     * register the entry, track for outcomes, and learn from the trade.
     */
    fun runV3Execution(req: com.lifecyclebot.v3.ExecuteRequest): com.lifecyclebot.v3.ExecuteResult {
        if (!req.isBuy) {
            return com.lifecyclebot.v3.ExecuteResult(success = false, error = "V3 sell-side not wired here")
        }
        if (!::executor.isInitialized) {
            return com.lifecyclebot.v3.ExecuteResult(success = false, error = "executor not initialised")
        }

        val ts = status.tokens[req.mint]
            ?: return com.lifecyclebot.v3.ExecuteResult(success = false, error = "token not in watchlist")
        if (ts.position.isOpen) {
            return com.lifecyclebot.v3.ExecuteResult(success = false, error = "position already open")
        }

        val cfgNow = ConfigStore.load(applicationContext)
        val isPaper = cfgNow.paperMode
        val w = wallet
        val walletSol = if (isPaper) {
            try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Throwable) { 0.0 }
        } else {
            // V5.9.1495 â€” main-thread-safe preflight read (no RPC on Main).
            livePreflightWalletSol(w)
        }

        var v3ZeroSignalProbe = false
        var execSol = req.sizeSol

        // Live preflight: wallet present + adequate SOL with fee buffer.
        if (!isPaper) {
            val v3StageFit = try { TokenMetricStageRouter.laneFit(ts, "V3") } catch (_: Throwable) { TokenMetricStageRouter.LaneFit(true, "V3", TokenMetricStageRouter.Stage.UNKNOWN, "fit_error") }
            if (!v3StageFit.allowed) {
                try {
                    PipelineHealthCollector.labelInc("V3_TOKEN_METRIC_STAGE_DEFERRED_${v3StageFit.stage.name}")
                    ForensicLogger.lifecycle("V3_TOKEN_METRIC_STAGE_DEFERRED", "mint=${ts.mint.take(10)} symbol=${ts.symbol} ${v3StageFit.reason}")
                } catch (_: Throwable) {}
                return com.lifecyclebot.v3.ExecuteResult(success = false, error = "V3_TOKEN_METRIC_STAGE_DEFERRED_${v3StageFit.stage.name}")
            }
            if (w == null) return com.lifecyclebot.v3.ExecuteResult(success = false, error = "live wallet not connected")
            // V5.0.4032 â€” V3 must not hide blind score/conf behind executor.doBuy(score=50).
            // If the V3 request has no usable score/conf metadata, fall back to the token's
            // latest V3 fields. Zero-signal live candidates are probe-only, not normal capital.
            val reqScore = (req.score ?: ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(-100, 150)
            val reqConf = (req.confidence ?: ts.lastV3Confidence ?: 0).coerceIn(0, 100)
            v3ZeroSignalProbe = reqScore <= 0 && reqConf <= 10
            execSol = if (v3ZeroSignalProbe) {
                // V5.0.6018 â€” no more live dollar probes. A V3 zero-signal that is
                // still allowed to execute must respect the live compounding floor;
                // otherwise big winners cannot move a sub-1 SOL wallet. True bad
                // candidates are handled by upstream hard safety/stage gates.
                val probeSol = com.lifecyclebot.engine.LiveSizingProfile.lastMileEntryFloor(
                    req.sizeSol.coerceAtLeast(0.001),
                    walletSol,
                    isPaperMode = false,
                )
                try {
                    PipelineHealthCollector.labelInc("V3_ZERO_SIGNAL_COMPOUND_FLOOR_6018")
                    ForensicLogger.lifecycle("V3_ZERO_SIGNAL_COMPOUND_FLOOR_6018", "mint=${ts.mint.take(10)} symbol=${ts.symbol} score=$reqScore conf=$reqConf band=${req.band ?: "UNKNOWN"} requested=${"%.4f".format(req.sizeSol)} exec=${"%.4f".format(probeSol)} action=compound_floor_live_learning")
                } catch (_: Throwable) {}
                probeSol
            } else req.sizeSol
            if (walletSol < execSol + 0.01) {
                return com.lifecyclebot.v3.ExecuteResult(success = false, error = "insufficient wallet SOL: ${"%.4f".format(walletSol)} < ${"%.4f".format(execSol + 0.01)}")
            }
        }

        return try {
            ErrorLogger.info("BotService",
                "âš¡ V3_EXEC ${ts.symbol} | ${"%.4f".format(if (!isPaper && v3ZeroSignalProbe) execSol else req.sizeSol)} SOL | mode=${if (isPaper) "PAPER" else "LIVE"}${if (!isPaper && v3ZeroSignalProbe) " | PROBE_ONLY_ZERO_SIGNAL" else ""}")
            // V5.0.6373 â€” SOURCE-OF-CREATION same-mint suppression. Operator
            // snapshot showed 523 EXEC_GATE PAPER_SAME_MINT_ALREADY_OPEN blocks in
            // 15 minutes: the V3 execute path is invoking executor.doBuy() again
            // for a mint already tracked in EmergentGuardrails, and the block
            // fires deep inside paperBuy() after tradeId/normalize work. Cut it
            // here so the fanout stops burning executor cycles + labels + logs
            // on already-open mints. The existing paperBuy guard remains as
            // belt-and-suspenders for other doBuy callers.
            val existingLayer6373 = try { com.lifecyclebot.engine.EmergentGuardrails.getPositionLayer(ts.mint) } catch (_: Throwable) { null }
            // V5.0.6464 Â§P0-#1 â€” CANONICAL MINT OCCUPANCY (early admission).
            // Runs BEFORE the legacy EmergentGuardrails.getPositionLayer path so
            // 90%+ of same-mint EXEC_GATE blocks vanish at hydration time.
            val mode6464 = if (isPaper) "paper" else "live"
            val admission6464 = try {
                com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.admit(
                    mode = mode6464, mint = ts.mint, symbol = ts.symbol, source = "V3_EXEC",
                )
            } catch (_: Throwable) { com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.Admission.PASS_NONE }
            val occupancyBlocked6464 = admission6464 == com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.Admission.BLOCK_OPEN ||
                admission6464 == com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.Admission.BLOCK_PENDING ||
                admission6464 == com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.Admission.BLOCK_EXITING
            if (occupancyBlocked6464) {
                try {
                    PipelineHealthCollector.onGate("EXEC_GATE", ts.symbol, false, "MINT_OCCUPANCY_${admission6464.name}_6464")
                } catch (_: Throwable) {}
                com.lifecyclebot.v3.ExecuteResult(
                    success = false, error = "MINT_OCCUPANCY_${admission6464.name}_6464",
                )
            } else if (!existingLayer6373.isNullOrBlank()) {
                try {
                    PipelineHealthCollector.labelInc("V3_EXEC_SAME_MINT_PREEMPT_6373")
                    PipelineHealthCollector.onGate("EXEC_GATE", ts.symbol, false, "V3_SAME_MINT_ALREADY_OPEN_6373 existing=$existingLayer6373")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "V3_EXEC_SAME_MINT_PREEMPT_6373",
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} existing=$existingLayer6373 mode=${if (isPaper) "PAPER" else "LIVE"}"
                    )
                } catch (_: Throwable) {}
                com.lifecyclebot.v3.ExecuteResult(
                    success = false,
                    error = "SAME_MINT_ALREADY_OPEN_6373_V3_PREEMPT",
                )
            } else {
            // V5.9.1475 (spec item 1/2) â€” capture open-state BEFORE the buy so we
            // can detect whether doBuy actually committed an open or bailed at a
            // finality/veto gate. doBuy returns Unit and bails silently on
            // PAPER_BUY_BLOCKED_FINALITY / LLM veto / route block, so the previous
            // unconditional success=true produced a stub MEME_EXECUTOR_DONE, fake
            // OPENED hooks, and canonical-learning poisoning. Now success is sourced
            // from the canonical open predicate â€” the single source of truth.
            val wasOpenBefore = executor.positionDidOpen(ts)
            executor.doBuy(
                ts = ts,
                sol = if (!isPaper && v3ZeroSignalProbe) execSol else req.sizeSol,
                score = (req.score ?: ts.lastV3Score ?: 50).toDouble(),
                wallet = w,
                walletSol = walletSol,
                identity = null,
                quality = req.band ?: "V3",
                skipGraduated = false,
            )
            val didOpen = executor.positionDidOpen(ts)
            if (!didOpen || wasOpenBefore) {
                // Buy did not create a NEW open (blocked finality / veto / dup).
                // Emit a single gate-dropped trace; do NOT report success, so the
                // orchestrator never emits EXECUTOR_DONE, never calls opened hooks,
                // never increments EXEC_BUY, never feeds canonical learning.
                try {
                    val label6497 = if (wasOpenBefore) "TRUE_DUPLICATE_OPEN"
                        else if (isPaper) "ROUTE_FAILED_PAPER" else "ROUTE_FAILED_LIVE"
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        label6497,
                        "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=V3 reason=${if (wasOpenBefore) "ALREADY_OPEN" else "NO_OPEN_COMMITTED"} mode=${if (isPaper) "PAPER" else "LIVE"} (see PAPER_BUY_NOT_OPENED_* for explicit reason)"
                    )
                    // Preserve legacy aggregate for dashboards.
                    if (!wasOpenBefore) com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ROUTE_FAILED")
                } catch (_: Throwable) {}
                com.lifecyclebot.v3.ExecuteResult(
                    success = false,
                    error = if (wasOpenBefore) "TRUE_DUPLICATE_OPEN" else "ROUTE_FAILED_NO_OPEN_COMMITTED",
                )
            } else {
                val lastPrice = ts.lastPrice
                com.lifecyclebot.v3.ExecuteResult(
                    success = true,
                    txSignature = null,
                    executedSol = if (!isPaper && v3ZeroSignalProbe) execSol else req.sizeSol,
                    executedPrice = if (lastPrice > 0.0) lastPrice else null,
                )
            }
            } // V5.0.6373 â€” close pre-empt-vs-normal-path else block
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "runV3Execution error for ${ts.symbol}", e)
            com.lifecyclebot.v3.ExecuteResult(success = false, error = "exec exception: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // V5.9.317: MANUAL TRADE API (paper + live, end-to-end)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Routes to executor.doBuy / executor.doSell which already handle all
    // routing (paper vs live), security guards, exposure caps, fee splits and
    // shadow-paper mirroring. This is the single source of truth used by the
    // manual BUY/SELL buttons on the active token panel in MainActivity.
    
    /**
     * Manual BUY for a specific token. Returns (success, message) for UI feedback.
     * - In paper mode: routes to paperBuy (no wallet required).
     * - In live mode: routes through full security guard + Jupiter swap pipeline.
     * - Sizing: caller-supplied sol amount (no SmartSizer override) so user has
     *   precise control. Validation prevents overdraw / negative amounts.
     */
    fun manualBuy(mint: String, solAmount: Double): Pair<Boolean, String> {
        if (mint.isBlank()) return false to "No token selected"
        if (solAmount <= 0.0 || solAmount.isNaN() || solAmount.isInfinite()) {
            return false to "Invalid amount: $solAmount SOL"
        }
        if (!::executor.isInitialized) return false to "Bot not started"

        val ts = status.tokens[mint] ?: return false to "Token not in watchlist: ${mint.take(8)}"
        if (ts.position.isOpen) {
            return false to "Position already open: ${ts.symbol}"
        }

        val cfgNow = ConfigStore.load(applicationContext)
        val isPaper = cfgNow.paperMode
        val w = wallet
        // V5.9.495o â€” operator: manual BUY toasted "Insufficient wallet SOL:
        // 0.0000 < 0.0600" while UI top bar showed 0.9439â—. Fresh on-demand
        // `getSolBalance()` was failing (3-retry RPC throws â†’ catch returns
        // 0.0). The cached `WalletManager.state.value.solBalance` is what
        // the UI displays and is refreshed on a periodic cadence â€” trust it
        // when fresh RPC fails. Only fall back to fresh RPC if cache is empty.
        val walletSol = if (isPaper) {
            try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Exception) { 0.0 }
        } else {
            // V5.9.1495 â€” main-thread-safe preflight read (no RPC on Main).
            livePreflightWalletSol(w)
        }

        // Live-mode preflight: ensure wallet exists + has enough SOL.
        if (!isPaper) {
            if (w == null) return false to "Live wallet not connected"
            // Reserve 0.01 SOL for swap fees (mirrors V5.9.309 fix).
            if (walletSol < solAmount + 0.01) {
                return false to "Insufficient wallet SOL: ${"%.4f".format(walletSol)} < ${"%.4f".format(solAmount + 0.01)}"
            }
        }

        return try {
            ErrorLogger.info("BotService",
                "ğŸ‘† MANUAL BUY: ${ts.symbol} | ${"%.4f".format(solAmount)} SOL | mode=${if (isPaper) "PAPER" else "LIVE"}")
            addLog("ğŸ‘† Manual BUY: ${ts.symbol} ${"%.4f".format(solAmount)} SOL ${if (isPaper) "(paper)" else "(LIVE)"}")
            executor.doBuy(
                ts = ts,
                sol = solAmount,
                score = 50.0,                // neutral score: this is a user override, not an AI decision
                wallet = w,
                walletSol = walletSol,
                identity = null,             // let TradeIdentityManager assign
                quality = "MANUAL",
                skipGraduated = false,
            )
            true to "Buy submitted (${if (isPaper) "paper" else "LIVE"})"
        } catch (e: Exception) {
            ErrorLogger.error("BotService", "manualBuy error for ${ts.symbol}", e)
            false to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Manual SELL of an open position. Returns (success, message).
     * - In paper mode: routes to paperSell (instant fill at last price).
     * - In live mode: routes through Jupiter swap pipeline + reconnect logic.
     * - Reason tag "MANUAL" so journal entries are clearly attributed.
     */
    fun manualSell(mint: String): Pair<Boolean, String> {
        if (mint.isBlank()) return false to "No token selected"
        if (!::executor.isInitialized) return false to "Bot not started"

        val cfgNow = ConfigStore.load(applicationContext)
        val isPaper = cfgNow.paperMode
        val w = wallet
        // V5.9.495o â€” same cached-balance fallback as manualBuy.
        val walletSol = if (isPaper) {
            try { com.lifecyclebot.v3.scoring.CashGenerationAI.getTreasuryBalance(true) } catch (_: Exception) { 0.0 }
        } else {
            // V5.9.1495 â€” main-thread-safe preflight read (no RPC on Main).
            livePreflightWalletSol(w)
        }
        if (!isPaper && w == null) return false to "Live wallet not connected"

        // V5.9.474 â€” operator-reported manual-SELL store-mismatch bug.
        //
        // Symptom: DMC visible in 'Treasury Scalps' card with +4.1% PnL but
        // pressing manual SELL toasted "No open position to sell" because
        // the old code only inspected `status.tokens[mint].position.isOpen`.
        // CashGenerationAI / ShitCoinTraderAI / QualityTraderAI /
        // BlueChipTraderAI / MoonshotTraderAI all maintain their OWN position
        // maps and (a) do NOT always set ts.position.isOpen=true on the
        // shared TokenState, (b) sometimes the mint isn't in status.tokens
        // at all (cleanup races, reboot rehydration). The sub-trader cards
        // were reading from those private maps but the sell button was
        // reading from the main one â€” visibility/action mismatch.
        //
        // Fix: scan ALL position stores in priority order. If found:
        //   1. ts.position.isOpen=true  â†’ use main executor.doSell path
        //      (works for ShitCoin / Quality / BlueChip / Moonshot since
        //      those layers DO mirror to ts.position when buying).
        //   2. Treasury-only position (CashGen has it, ts.position closed)
        //      â†’ call CashGenerationAI.closePosition directly so the
        //      strategy bookkeeping clears even if the swap path is busy.
        //   3. None of the above â†’ return a meaningful error listing every
        //      store we checked so the operator knows it's truly absent.
        val ts = status.tokens[mint]

        // Path 1: main TokenState says open â†’ use existing fast path.
        if (ts != null && ts.position.isOpen) {
            return try {
                ErrorLogger.info("BotService",
                    "ğŸ‘† MANUAL SELL [main]: ${ts.symbol} | qty=${ts.position.qtyToken} | mode=${if (isPaper) "PAPER" else "LIVE"}")
                addLog("ğŸ‘† Manual SELL: ${ts.symbol} ${if (isPaper) "(paper)" else "(LIVE)"}")
                val result = executor.doSell(ts, "MANUAL", w, walletSol)
                true to "Sell submitted (${result.name})"
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "manualSell error for ${ts.symbol}", e)
                false to "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // Path 2: scan sub-trader stores. Each holds its own position map.
        // If any of them has the mint we route the sell through it.
        val symbol = ts?.symbol ?: mint.take(8)

        try {
            val tp = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
            if (tp != null) {
                val price = com.lifecyclebot.v3.scoring.CashGenerationAI.getTrackedPrice(mint)
                    ?: tp.entryPrice
                ErrorLogger.info("BotService", "ğŸ‘† MANUAL SELL [Treasury]: ${tp.symbol} @ \$${price}")
                addLog("ğŸ‘† Manual SELL (Treasury): ${tp.symbol}")
                // If we also have a TokenState, run the swap; close the
                // treasury bookkeeping regardless so the card disappears.
                val realTs = ts
                val sellResult = if (realTs != null) {
                    try { executor.doSell(realTs, "MANUAL_TREASURY", w, walletSol).name } catch (_: Exception) { "TREASURY_BOOKKEEP_ONLY" }
                } else "TREASURY_BOOKKEEP_ONLY (no TokenState)"
                com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(
                    mint, price, com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT)
                return true to "Treasury position closed ($sellResult)"
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BotService", "manualSell Treasury check error: ${e.message}")
        }

        // ShitCoin / Quality / BlueChip / Moonshot all keep their positions
        // in private activePositions maps. They DO mirror to ts.position
        // when buying so the Path 1 branch above usually catches them. If
        // we got here, the main flag fell out of sync â€” list them so the
        // operator can see which store has it and we still attempt the
        // swap via doSell when a TokenState exists.
        if (ts != null) {
            data class StoreHit(val name: String, val symbol: String)
            val hits = mutableListOf<StoreHit>()
            try {
                if (com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().any { it.mint == mint })
                    hits += StoreHit("ShitCoin", ts.symbol)
            } catch (_: Exception) {}
            try {
                if (com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().any { it.mint == mint })
                    hits += StoreHit("Quality", ts.symbol)
            } catch (_: Exception) {}
            try {
                if (com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().any { it.mint == mint })
                    hits += StoreHit("BlueChip", ts.symbol)
            } catch (_: Exception) {}
            try {
                if (com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(mint))
                    hits += StoreHit("Moonshot", ts.symbol)
            } catch (_: Exception) {}
            if (hits.isNotEmpty()) {
                ErrorLogger.info("BotService",
                    "ğŸ‘† MANUAL SELL [sub-trader resync]: ${ts.symbol} found in ${hits.joinToString(",") { it.name }} â€” forcing doSell")
                addLog("ğŸ‘† Manual SELL (${hits.first().name}): ${ts.symbol}")
                return try {
                    val result = executor.doSell(ts, "MANUAL_${hits.first().name.uppercase()}", w, walletSol)
                    true to "Sell submitted via ${hits.first().name} (${result.name})"
                } catch (e: Exception) {
                    ErrorLogger.error("BotService", "manualSell sub-trader error for ${ts.symbol}", e)
                    false to "Error: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        return false to "No open position found for ${symbol} in any store (main/Treasury/ShitCoin/Quality/BlueChip/Moonshot)"
    }


    /**
     * V5.9.645 â€” single-purpose meme scanner self-heal builder.
     *
     * Used only by:
     *   â€¢ inert-loop watchdog HARD branch when marketScanner is null
     *   â€¢ 30-second post-startup self-heal in startBot
     *
     * The full-fat construction at startBot (with TradeIdentity, lifecycle,
     * blacklist diagnostics) stays the source of truth at boot. This path
     * only needs to GET A SCANNER ALIVE so tokens flow into protected intake.
     * admitProtectedMemeIntake already enforces the same gates internally.
     *
     * Returns true if a scanner is alive after this call, false on failure.
     */
    /** V5.9.1518 â€” PATCH ITEM 3/7: public entrypoint for RuntimeDoctor to ask
     *  for a scanner reboot when SCANNER_INACTIVE is diagnosed. Respects the
     *  operator kill-switch and only acts while running. */
    fun requestScannerRestart(reason: String) {
        try {
            if (!status.running) return
            if (RuntimeRepairState.isScannerUserDisabled()) return
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("SCANNER_WATCHDOG_RESTART", "reason=$reason via=requestScannerRestart") } catch (_: Throwable) {}
            bootMemeScanner(reason = reason)
        } catch (_: Throwable) {}
    }

    private fun bootMemeScanner(reason: String): Boolean {
        // V5.9.651 â€” forensic heal entry
        ForensicLogger.phase(ForensicLogger.PHASE.SCANNER_HEAL, "_scanner", "reason=$reason existing=${marketScanner != null}")
        val existing = marketScanner
        if (existing != null) {
            return try {
                if (!existing.isAlive()) {
                    ErrorLogger.warn("BotService", "ğŸ©¹ SELF_HEAL($reason): existing scanner not alive â€” restarting")
                    addLog("ğŸ©¹ Self-heal($reason): scanner not alive â€” restarting")
                    try { existing.stop() } catch (_: Throwable) {}
                    existing.start()
                }
                true
            } catch (t: Throwable) {
                ErrorLogger.error("BotService", "ğŸš¨ SELF_HEAL($reason) restart failed: ${t.message}", t)
                false
            }
        }
        return try {
            ErrorLogger.warn("BotService", "ğŸ©¹ SELF_HEAL($reason): constructing fresh SolanaMarketScanner")
            addLog("ğŸ©¹ Self-heal($reason): building Solana scanner from scratch")
            // V5.0.3682 â€” P0 SCANNER GENERATION GUARD. Capture the runtime
            // generation at construction time and check it (plus the runtime
            // state) at the top of every callback. Drops every emission from a
            // stale scanner whose generation has been superseded by a Start
            // press OR whose runtime is no longer STARTING/RUNNING. Eliminates
            // post-STOP SCANNER_CALLBACK_FIRE / INTAKE_BLOCKED_RUNTIME_STOPPED
            // spam at the source.
            val builtGeneration = com.lifecyclebot.engine.BotRuntimeController.currentGeneration()
            val sc = SolanaMarketScanner(
                cfg          = { ConfigStore.load(applicationContext) },
                onTokenFound = onTokenFound@{ mint, symbol, name, source, score, liquidityUsd, volumeH1 ->
                    try {
                        // V5.0.3682 â€” generation+state guard. Drop the callback
                        // silently if this scanner instance was built for an old
                        // runtime generation OR the runtime is no longer admitting.
                        val curGen = com.lifecyclebot.engine.BotRuntimeController.currentGeneration()
                        val rtState = com.lifecyclebot.engine.BotRuntimeController.snapshot().state
                        val admitting = rtState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.RUNNING ||
                                        rtState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.STARTING
                        if (builtGeneration != curGen || !admitting || isShuttingDown) {
                            // No logging on the hot path â€” that's the spam we're killing.
                            return@onTokenFound
                        }
                        // V5.9.650 â€” operator-requested visibility. Operator's
                        // log dump showed only PUMP_PORTAL_WS reaching protected
                        // intake; non-PumpPortal scanner sources never appear.
                        // This INFO line proves whether the scanner's onTokenFound
                        // callback is firing for the OTHER 13+ sources at all
                        // (DexGainers/Losers/Profiles/Boosted/PumpFunTrending/
                        //  scanTopVolume/scanPumpFunVolume/scanPumpFunActive/
                        //  scanEmergencyDexProfiles, etc).
                        ErrorLogger.info(
                            "BotService",
                            "ğŸ” SCANNER_CALLBACK_FIRE: $symbol src=${source.name} liq=\$$liquidityUsd score=$score"
                        )
                        lastScannerDiscoveryMs = System.currentTimeMillis()
                        marketScanner?.recordNewTokenFound()
                        // V5.0.3730 â€” scanner-active source truth (self-heal path sibling).
                        try { com.lifecyclebot.engine.BotRuntimeController.markScannerActive(builtGeneration, true) } catch (_: Throwable) {}
                        admitProtectedMemeIntake(
                            mint = mint,
                            symbol = symbol,
                            name = name.ifBlank { symbol },
                            source = "SCANNER_HEAL_${source.name}",
                            marketCapUsd = liquidityUsd * 10.0,
                            liquidityUsd = liquidityUsd,
                            volumeH1 = volumeH1,
                            confidence = score.toInt().coerceIn(1, 100),
                            allSources = setOf(source.name, "SCANNER_HEAL"),
                            playSound = false,
                            operatorLog = false,
                            expectedRuntimeGeneration = builtGeneration,
                        )
                        TokenMergeQueue.enqueue(
                            mint = mint,
                            symbol = symbol,
                            scanner = source.name,
                            marketCapUsd = liquidityUsd * 10,
                            liquidityUsd = liquidityUsd,
                            volumeH1 = volumeH1,
                        )
                    } catch (e: Throwable) {
                        ErrorLogger.debug("BotService", "self-heal callback error for $symbol: ${e.message}")
                    }
                },
                onLog = ::addLog,
                getBrain = { botBrain },
            )
            marketScanner = sc
            sc.start()
            ErrorLogger.warn("BotService", "âœ… SELF_HEAL($reason): scanner constructed and started")
            addLog("âœ… Self-heal($reason): meme scanner is live")
            true
        } catch (t: Throwable) {
            ErrorLogger.error("BotService", "ğŸš¨ SELF_HEAL($reason) construction failed: ${t.message}", t)
            addLog("âŒ Self-heal($reason) failed: ${t.message}")
            false
        }
    }

    /** V5.0.6517 â€” durable, visible and cancellable Start behind full bootstrap. */
    private fun deferStartUntilServiceReady6516(): Boolean {
        serviceStartRequested6517.set(true)
        if (serviceBootstrapReady6516) {
            if (!serviceBootstrapSucceeded6516) {
                serviceStartRequested6517.set(false)
                try {
                    ForensicLogger.lifecycle("START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516", "failure=${serviceBootstrapFailure6517.take(120)} action=surface_failure")
                    PipelineHealthCollector.labelInc("START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516")
                } catch (_: Throwable) {}
                return true
            }
            serviceStartRequested6517.set(false)
            return false
        }
        if (serviceStartQueued6516.compareAndSet(false, true)) {
            try {
                ForensicLogger.lifecycle("START_DEFERRED_SERVICE_BOOTSTRAP_6516", "action=await_complete_io_init visible=true cancellable=true")
                PipelineHealthCollector.labelInc("START_DEFERRED_SERVICE_BOOTSTRAP_6516")
            } catch (_: Throwable) {}
            scope.launch(kotlinx.coroutines.CoroutineName("service-start-barrier-6517")) {
                val bootstrap = serviceBootstrapJob6516
                if (bootstrap == null) {
                    serviceBootstrapFailure6517 = "bootstrap job missing"
                    serviceBootstrapSucceeded6516 = false
                    serviceBootstrapReady6516 = true
                    serviceStartQueued6516.set(false)
                    serviceStartRequested6517.set(false)
                    try {
                        ForensicLogger.lifecycle("SERVICE_BOOTSTRAP_JOB_MISSING_6517", "action=fail_visible_no_infinite_wait")
                        PipelineHealthCollector.labelInc("SERVICE_BOOTSTRAP_JOB_MISSING_6517")
                    } catch (_: Throwable) {}
                    return@launch
                }
                try {
                    bootstrap.join()
                } finally {
                    serviceStartQueued6516.set(false)
                }
                val requested = serviceStartRequested6517.getAndSet(false)
                if (requested && serviceBootstrapReady6516 && serviceBootstrapSucceeded6516 &&
                    !stopInProgress && !isManualStopRequested(applicationContext) && loopJob?.isActive != true) {
                    try { ForensicLogger.lifecycle("START_RESUMED_AFTER_SERVICE_BOOTSTRAP_6517", "operatorIntent=true") } catch (_: Throwable) {}
                    startInProgress = true
                    try {
                        startBot()
                    } finally {
                        startInProgress = false
                    }
                } else {
                    try {
                        ForensicLogger.lifecycle(
                            "START_NOT_RESUMED_AFTER_SERVICE_BOOTSTRAP_6517",
                            "requested=$requested ready=$serviceBootstrapReady6516 success=$serviceBootstrapSucceeded6516 stop=$stopInProgress manual=${isManualStopRequested(applicationContext)}",
                        )
                    } catch (_: Throwable) {}
                }
            }
        } else {
            try { ForensicLogger.lifecycle("START_REQUEST_RETAINED_DURING_BOOTSTRAP_6517", "operatorIntent=true waiterAlreadyActive=true") } catch (_: Throwable) {}
        }
        return true
    }

    fun startBot() {
        if (deferStartUntilServiceReady6516()) return
        isShuttingDown = false  // V5.9.721: clear shutdown flag so traders run normally
        // V5.0.6659d â€” a fresh service is initialized while the prior static
        // shutdown flag is still true. CryptoAltTrader's bootstrap start then
        // fail-closes itself as runtime_stopping. Rearm it only after the
        // operator start has cleared that flag; start() is idempotent.
        try {
            val startCfg6659d = com.lifecyclebot.data.ConfigStore.load(applicationContext)
            val startPlan6659d = com.lifecyclebot.engine.truth.TraderRuntimePlan6526.from(
                cfg = startCfg6659d, marketsKill = MARKET_TRADER_KILL_SWITCH,
                marketsLaneOnFn = { isMarketsLaneEnabled(it) },
            )
            com.lifecyclebot.perps.CryptoAltTrader.setEnabled(startPlan6659d.cryptoUniverseOn)
            if (startPlan6659d.cryptoUniverseOn) com.lifecyclebot.perps.CryptoAltTrader.start()
        } catch (t: Throwable) {
            try { ForensicLogger.lifecycle("CRYPTO_REARM_FAILED_6659D", "err=${t.message?.take(100)}") } catch (_: Throwable) {}
        }
        // V5.0.6464 Â§P0-#7 â€” REGISTER CANONICAL FINALIZED-TRADE BUS CONSUMERS.
        // The 8 acknowledged learners/EV/dashboard subscribers each get a
        // slot in CanonicalFinalizedTradeBus6464 so the parity report
        // surfaces zero-consumers by name. Registration is idempotent.
        try {
            for (c in listOf(
                "LearnerRewardBridge", "LosingStreakReflex", "GrowthRewardShaper",
                "TacticSwitcher", "Governor", "CapitalCreed",
                "EVEstimator", "Dashboard",
            )) com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.registerConsumer(c)
        } catch (_: Throwable) {}
        // V5.0.6456 Â§P0-#1 â€” install real mark provider so
        // CanonicalCapitalAuthority6450's unrealized/equity/conservation
        // reflect live prices from status.tokens (in-memory cache, no IO).
        // If a mint has no live price cached, the provider returns 0.0
        // which the snapshot treats as "use costBasis fallback so unrealized
        // reads 0" â€” never a phantom -100%.
        try {
            com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450.installMarkProvider { mint ->
                try {
                    val ts = status.tokens[mint] ?: return@installMarkProvider 0.0
                    val pos = ts.position
                    val px = ts.lastPrice
                    if (!px.isFinite() || px <= 0.0 || !pos.isOpen) 0.0
                    else if (try {
                        !com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500
                            .isRuntimeOpenEligible6636(mint, pos)
                    } catch (_: Throwable) { true }) {
                        // V5.0.6636 â€” the mark provider and UI now consume the
                        // same canonical projection verdict. A row that fails
                        // identity/qty/cost/entry/lock contributes zero market
                        // value immediately; it cannot inflate equity during
                        // the interval before a separate quarantine sweep.
                        0.0
                    }
                    else {
                        // V5.0.6496 Â§1 â€” MARK AUTHORITY INTEGRITY GATE. Fallback /
                        // sentinel / synthetic marks (per MarketDataProvenance6471)
                        // may DISPLAY in the UI but MUST NOT flow into
                        // openMarketValueSol / unrealizedPnl / EconomicOutcome6472
                        // / learners. Return 0.0 on non-authoritative â†’ snapshot
                        // falls back to costBasis so unrealized reads 0 (never a
                        // phantom +525 SOL / $926M inflation). Only the economic
                        // path is gated; ts.lastPrice for UI is untouched.
                        val provOk = try {
                            // V5.0.6625 Â§P6 â€” UI-off-main audit wrapper. If this
                            // evaluation is running on the Main thread and takes
                            // â‰¥32 ms it surfaces a UI_MAIN_THREAD_LONG_RUN counter
                            // so the operator can grep the exact snapshot cost.
                            val t0_6625 = android.os.SystemClock.uptimeMillis()
                            val out_6625 = com.lifecyclebot.engine.truth.MarkAuthorityIntegrityGate6496.isAuthoritative(
                                mint = mint,
                                priceUsd = ts.lastPrice,
                                mcapUsd = ts.lastMcap,
                                liquidityUsd = ts.lastLiquidityUsd,
                                source = ts.lastPriceSource.ifBlank { "UNKNOWN" },
                                poolAddress = ts.lastPricePoolAddr.ifBlank { "MINT_ROUTE:${mint.take(8)}" },
                                // V5.0.6596 Â§MARK_AUTHORITY_MINT_ROUTE_FOR_KNOWN_OPEN â€” this
                                // callsite is the exit-mark / openMV recompute path for
                                // KNOWN OPEN canonical positions (pos was fetched from the
                                // canonical authority via positionsByMint). The mint identity
                                // is therefore proven; MINT_ROUTE:* pool prefix is treated
                                // as acceptable for pool identity on this path only. New-
                                // entry paths continue to reject MINT_ROUTE:* as before.
                                isKnownOpenMint6596 = true,
                            )
                            try {
                                com.lifecyclebot.engine.truth.UiOffMainAudit6625.recordMainThreadWork6625(
                                    site = "MarkAuthorityIntegrityGate6496.isAuthoritative",
                                    durationMs = android.os.SystemClock.uptimeMillis() - t0_6625,
                                )
                            } catch (_: Throwable) {}
                            out_6625
                        } catch (_: Throwable) { false }
                        if (!provOk) 0.0
                        else {
                            // V5.0.6496 SOURCE-FIX â€” ts.lastPrice is USD-per-token
                            // (DexScreener / Jupiter both publish priceUsd).
                            // CanonicalCapitalAuthority6450 documents the mark
                            // provider must return WHOLE-MINT VALUE IN SOL. Return-
                            // ing USD here caused equity/openMV to be recorded as
                            // 'SOL' and then multiplied by solPrice for dashboard
                            // USD display â€” a double-USD scaling that produced the
                            // observed $956M equity on a $94 starting balance.
                            //
                            // Convert USDâ†’SOL at the provider boundary using the
                            // authoritative SOL/USD price. If the SOL price cache
                            // is missing or absurd, return 0.0 so the snapshot
                            // falls back to costBasis (unrealized reads 0, never
                            // a phantom -100%).
                            val solUsd = try {
                                com.lifecyclebot.engine.EfficiencyLayer.getCachedPrice()?.solPriceUsd
                                    ?: com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                            } catch (_: Throwable) { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice }
                            if (!solUsd.isFinite() || solUsd <= 50.0 || solUsd >= 5000.0) 0.0
                            else (px * pos.qtyToken.coerceAtLeast(0.0)) / solUsd
                        }
                    }
                } catch (_: Throwable) { 0.0 }
            }
        } catch (_: Throwable) {}
        // V5.0.6521 â€” canonical-raw reconstruction before quarantine; never abandon/force-close.
        try {
            // V5.0.6538 Â§LEGACY_MINT_ATTRIBUTION_SWEEP â€” the operator's
            // "root-cause the mint" mandate: attribute every economic-
            // invariant break to its lineage so we can distinguish
            // pre-V5.0.6509 legacy corruption (paperBuy minted qty with
            // solUsdâ‰ˆ$1 before the V5.0.6509 SOL-price validation guard
            // was added) from a fresh regression in the current commit.
            // The V5.0.6537 economic notional check will quarantine the
            // row a moment later â€” this sweep just tags provenance so
            // the operator's screenshot rows report as legacy vs new.
            for (canonical in com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()) {
                if (canonical.mode != "paper") continue
                if (canonical.entryPriceUsd <= 0.0 || canonical.entryCostSol <= 0.0) continue
                if (canonical.remainingQtyRaw <= java.math.BigInteger.ZERO) continue
                if (canonical.quantityScale !in 0..18) continue
                val src6538 = canonical.entryPriceSource.uppercase()
                if (src6538.contains("SYNTH") || src6538.contains("PUMP_FUN_BC")) continue
                val qtyToken6538 = canonical.remainingQtyRaw
                    .toBigDecimal().movePointLeft(canonical.quantityScale).toDouble()
                if (!qtyToken6538.isFinite() || qtyToken6538 <= 0.0) continue
                val impliedSolUsd6538 = (qtyToken6538 * canonical.entryPriceUsd) /
                    canonical.entryCostSol.coerceAtLeast(1e-18)
                if (!impliedSolUsd6538.isFinite() || impliedSolUsd6538 !in 5.0..10_000.0) {
                    val ageMs6538 = System.currentTimeMillis() - canonical.openedAtMs
                    val legacy6538 = ageMs6538 > 0L  // any pre-boot position is by definition legacy
                    try {
                        PipelineHealthCollector.labelInc(
                            if (legacy6538) "LEGACY_MINT_DETECTED_6538" else "FRESH_MINT_ECONOMIC_BROKEN_6538"
                        )
                        ForensicLogger.lifecycle(
                            if (legacy6538) "LEGACY_MINT_DETECTED_6538" else "FRESH_MINT_ECONOMIC_BROKEN_6538",
                            "positionId=${canonical.positionId.take(20)} mint=${canonical.mint.take(10)} " +
                                "symbol=${canonical.symbol} openedAgeMs=$ageMs6538 " +
                                "qtyToken=$qtyToken6538 entryPriceUsd=${canonical.entryPriceUsd} " +
                                "entryCostSol=${canonical.entryCostSol} impliedSolUsd=$impliedSolUsd6538 " +
                                "expected=implied_sol_price_in_[5..10000] observed=out_of_band " +
                                "action=quarantine_via_6537_at_next_check",
                        )
                    } catch (_: Throwable) {}
                    try {
                        com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500
                            .markInvariantBroken(canonical.mint, "LEGACY_ECONOMIC_MISMATCH_6538_impliedSol=$impliedSolUsd6538")
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        try {
            for (ts in status.tokens.values) {
                val pos = ts.position
                if (!pos.isOpen || !pos.isPaperPosition) continue
                val check = com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.check(ts.mint, pos)
                if (!check.ok) {
                    val repaired = com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.reconstructFromCanonical(ts.mint, pos)
                    if (repaired != null && com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.check(ts.mint, repaired).ok) {
                        ts.position = repaired
                        com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.release(ts.mint)
                        try { PositionPersistence.savePosition(ts) } catch (_: Throwable) {}
                        try {
                            ForensicLogger.lifecycle("QUANTITY_PROJECTION_RECONSTRUCTED_FROM_CANONICAL_RAW_6521", "mint=${ts.mint.take(10)} qty=${repaired.qtyToken} cost=${repaired.costSol} action=continue_position_learning_eligible")
                            PipelineHealthCollector.labelInc("QUANTITY_PROJECTION_RECONSTRUCTED_FROM_CANONICAL_RAW_6521")
                        } catch (_: Throwable) {}
                    } else {
                        com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.markInvariantBroken(ts.mint, "CANONICAL_RECONSTRUCTION_UNAVAILABLE: ${check.reason}")
                        try { PipelineHealthCollector.labelInc("QUANTITY_REPAIR_DEFERRED_NO_FORCE_CLOSE_6521") } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
        // V5.0.6502 Â§3 â€” CANONICAL WALLET REBUILD (phantom-realized reject
        // third leg). Rebuilds PaperAccountLedger6430.realizedPnl from the
        // canonical EconomicEventSchema6464 stream, dropping every event whose
        // mint sits in the QuantityInvariantAuthority6500 or
        // LearningQuarantineGate6470 quarantines. Must run AFTER the invariant
        // sweep above so the freshly-quarantined phantom-qty mints are already
        // marked before their realized rows are replayed. Idempotent â€” one
        // synchronous replay per startBot() call, no live-mode side effects
        // (writes only into PaperAccountLedger6430.realizedPnlPico).
        try {
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.rebuildRealizedFromCanonicalEvents6502()
        } catch (_: Throwable) {}
        // V5.0.6504 Â§10 â€” PURGE + REBUILD FROM IMMUTABLE FILL LOTS.
        // Operator mandate: "Rebuild contaminated PAPER performance from
        // immutable fills after repair. Do not treat TRUMP +7133.8% or
        // derived BLUECHIP +1300% EV as canonical while basis is
        // untrusted."
        // The 6502 rebuild replays EconomicEventSchema6464 which is a
        // journal of what the bot *thought* happened. The 6504 rebuild
        // sources realized PnL exclusively from FillLotLedger6504
        // (BUY_lots Ã— SELL_lots via FIFO lamport matching) â€” the ONLY
        // structurally immutable record. On divergence >|0.001 SOL|,
        // the ledger's realizedPnl is overwritten with the fill-lot
        // truth and a loud lifecycle line surfaces the delta.
        try {
            val fillLotRealized6504 = com.lifecyclebot.engine.truth.FillLotLedger6504.rebuildRealizedSol(isPaperOnly = true)
            val currentLedger6504 = com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.realizedPnlSol()
            val delta6504 = fillLotRealized6504 - currentLedger6504
            if (kotlin.math.abs(delta6504) > 0.001) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "FILL_LOT_REALIZED_DIVERGES_FROM_LEDGER_6504",
                    "fillLotRealized=${"%.6f".format(fillLotRealized6504)} " +
                        "ledgerRealized=${"%.6f".format(currentLedger6504)} " +
                        "delta=${"%.6f".format(delta6504)} action=overwrite_ledger_with_fill_lot_truth",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FILL_LOT_REALIZED_DIVERGES_FROM_LEDGER_6504")
                try {
                    com.lifecyclebot.engine.truth.PaperAccountLedger6430
                        .overrideRealizedFromFillLots6504(fillLotRealized6504)
                } catch (_: Throwable) {}
            } else {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FILL_LOT_REALIZED_MATCHES_LEDGER_6504")
            }
        } catch (_: Throwable) {}
        // V5.0.6508f Â§OPEN-COST RECONCILIATION FROM PROJECTIONS.
        // BEFORE the 6505 cash rebuild â€” the cash formula reads openCost,
        // so a drifted scalar (recordBuy/recordSell accumulator that lost
        // sync with per-lot truth) would silently under-count cash. Sum
        // remainingCostBasisSol across active paper projections and
        // overwrite the ledger scalar when |Î”| > 0.001 SOL. Emits
        // PAPER_LEDGER_OPEN_COST_RECONCILED_FROM_PROJECTIONS_6508.
        // Kills the operator-observed 2.9 SOL "missing cash" symptom.
        try {
            val projectionOpenCost6508 = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
                .activeMintProjections6490("paper")
                .sumOf { it.remainingCostBasisSol }
            com.lifecyclebot.engine.truth.PaperAccountLedger6430
                .overrideOpenCostFromProjections6508(projectionOpenCost6508)
        } catch (_: Throwable) {}
        // V5.0.6505 Â§5 â€” PAPER CASH RECONSTRUCTION.
        // Rebuild paper cash from the economic identity
        //   cash = startingCash + realizedPnL - fees - openCost
        // using the freshly-corrected realized figure. Non-clamping;
        // only overwrites when |Î”|>0.001 SOL. Never touches
        // startingCash / equity â€” economic events remain source of
        // truth per operator mandate #6.
        try {
            com.lifecyclebot.engine.truth.PaperAccountLedger6430.rebuildPaperCashFromIdentity6505()
        } catch (_: Throwable) {}
        // V5.0.6662 â€” old Stop paths could remove canonical positions and let
        // the identity rebuild return their basis to ledger cash without the
        // matching durable journal terminal.  Close those journal-only lots
        // at zero PnL so ledger, journal and hero converge without deleting
        // history or inventing profit.
        try {
            com.lifecyclebot.engine.truth.JournalEconomicReplay6619
                .repairOrphanedOpenLots6662()
        } catch (_: Throwable) {}
        // V5.0.6504 Â§5 â€” clear zombie latch on startBot so a
        // legitimately re-opened mint's stale-price timeout can fire
        // its one-shot again in the new session.
        try { paperStaleZombieLatch6504.clear() } catch (_: Throwable) {}
        // V5.0.6503 Â§2 â€” start HeroSnapshotAuthority6503 so MainActivity /
        // hero panels can read equity/exposure/openCount/pnl off Main via
        // an O(1) atomic reference. Idempotent. Publishes every 500ms on
        // Dispatchers.Default from a materialised token-map snapshot.
        try {
            com.lifecyclebot.engine.truth.HeroSnapshotAuthority6503.start(status)
        } catch (_: Throwable) {}
        // V5.0.6496 Â§5 â€” start the background UI snapshot refresher so
        // BotStatus.openPositions no longer traverses the token map on
        // Dispatchers.Main. Idempotent.
        try {
            com.lifecyclebot.engine.truth.UiSnapshotAuthority6496.start(status)
        } catch (_: Throwable) {}
        // V5.0.6454 Â§P0 â€” start the INDEPENDENT wall-clock reconciler +
        // risk clock BEFORE the bot loop launches. These run on their
        // OWN CoroutineScope (Dispatchers.Default) and are not affected
        // by any botLoop stall â€” their cadence is preserved even if the
        // scanner/watchdog/provider hangs 120s (operator's acceptance
        // criterion). start() is idempotent so repeated startBot() calls
        // are safe.
        try {
            com.lifecyclebot.engine.truth.WallClockReconciler6454.start(
                rowSnapshot = { try { com.lifecyclebot.engine.truth.ForensicRowMirror6442.snapshot() } catch (_: Throwable) { null } },
                fullReconstructor = { rows ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val typed = rows as? List<com.lifecyclebot.engine.truth.ForensicExecutionRow6441> ?: emptyList()
                        com.lifecyclebot.engine.truth.CanonicalReconciler6441.fullReconstruct(typed)
                    } catch (_: Throwable) {}
                },
            )
        } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.truth.CanonicalRiskClock6454.start { positionId, mint ->
                // V5.0.6454 heartbeat-only ping â€” the REAL per-tick
                // evaluate(markPx=live) is wired in Executor.riskCheck
                // which fires from every tick regardless of botLoop.
                // This clock's job is to guarantee the scheduler
                // heartbeat + starvation check run on wall-clock cadence
                // even if botLoop is wedged 150s. Passing markPx=0 makes
                // the scheduler treat the call as a heartbeat ping that
                // never latches (Â§P0-#9 no fake mark).
                try {
                    com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450.evaluate(
                        positionId = positionId,
                        mint = mint,
                        markPx = 0.0,
                        stopPx = 0.0,
                        catastrophePx = 0.0,
                        tpPx = 0.0,
                        trailPx = 0.0,
                        quoteAgeMs = 0L,
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // V5.0.3789 â€” a fresh Start rebuilds canonical state, so release the
        // stop-finalization latch. From here, normal persistence saves resume.
        persistenceFinalizedByStop = false
        // V5.9.1072 â€” once the runtime loop has been launched, startBot's
        // giant outer catch must NEVER declare the bot stopped. Late optional
        // init failures (toast/notification/reconciler/external stream/etc.)
        // previously fell into the same catch as fatal pre-loop startup and
        // executed status.running=false while scanner/loop/positions stayed
        // alive. Operator symptom: no ACTION_STOP_RECEIVED, intake continues,
        // Main later says STOPPED, positions remain stuck, Start races an
        // already-active loop. runtimeCommitted separates fatal pre-loop start
        // failure from degraded post-commit startup cleanup.
        var runtimeCommitted = false
        val startCfgForRuntime = try { ConfigStore.load(applicationContext) } catch (_: Throwable) { null }
        val startPaper = startCfgForRuntime?.paperMode ?: true
        val startAuto = startCfgForRuntime?.autoTrade ?: false
        try {
            RuntimeModeAuthority.publishRuntimeStart(startPaper, startAuto)
            PipelineHealthCollector.resetModeCountersForRuntime(if (startPaper) "PAPER" else "LIVE")
        } catch (_: Throwable) {}
        val runtimeGeneration = BotRuntimeController.beginStart(
            paperMode = startPaper,
            enabledTraders = try { EnabledTraderAuthority.snapshotStr() } catch (_: Throwable) { "" }
        )
        // V5.9.651 â€” forensic lifecycle marker
        ForensicLogger.lifecycle("BOT_START_REQUESTED", "gen=$runtimeGeneration loopActive=${loopJob?.isActive == true} statusRunning=${status.running} runtimeState=${BotRuntimeController.snapshot().state} modeSynced=true paper=$startPaper")
        // V5.9.647 â€” gate on actual botLoop activity, NOT on status.running.
        // BotViewModel.startBot() pre-sets BotService.status.running = true
        // for instant UI feedback BEFORE the service even starts. The old
        // guard `if (status.running) return` therefore short-circuited every
        // single startBot() invocation triggered by onStartCommand â†’
        // ACTION_START, leaving botLoop() permanently inactive. Net effect:
        // sub-traders that auto-start in onCreate (CryptoAlt, Markets, Forex,
        // Metals, Commodities, Perps) keep producing signals, the V5.9.646
        // onCreate-anchored scanner self-heal still feeds the watchlist, but
        // the meme/V3 trade-execution path (BlueChip qualifications,
        // ShitCoin/Moonshot scoring, FluidLearningAI, FinalDecisionGate,
        // wallet-confirmed buys) never fires because botLoop() is gated by
        // `while (status.running)` and only LAUNCHED inside startBot().
        // Operator screenshot V5.9.646 confirmed the symptom: 44 watchlist
        // entries, every one IDLE +0.0%, BlueChipAI logging
        // 'BLUE CHIP QUALIFIED: TRUMP score=70 conf=90% size=0.3210 SOL'
        // but no execution log following.
        if (loopJob?.isActive == true) {
            BotRuntimeController.registerJob(runtimeGeneration, "botLoop", loopJob)
            BotRuntimeController.publishRunning(runtimeGeneration, enabledTraders = try { EnabledTraderAuthority.snapshotStr() } catch (_: Throwable) { "" })
            status.running = true
            // A Stop -> Start may deliberately rebind the still-draining loop.
            // That is nevertheless a new accepted runtime session, so reset and
            // independently close its mandatory evidence window here too.
            scheduleExecutionSpineAcceptance6666(runtimeGeneration)
            try { com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.setRuntimeActive(true, "BotService.startBot.rebind6487") } catch (_: Throwable) {}
            ErrorLogger.warn("BotService", "startBot() called but botLoop is already active â€” rebinding runtime state")
            return
        }
        
        try {
            ErrorLogger.info("BotService", "startBot() called")
            addLog("ğŸš€ Starting bot...")

            // V5.9.1053 â€” No strategy auto-retire. isDisabled() always returns false.
            // clearDisabled() is a no-op. Kept for compile compat only.
            try {
                com.lifecyclebot.engine.StrategyTelemetry.clearDisabled()
                ForensicLogger.lifecycle("STRATEGY_TELEMETRY_DISABLED_CLEARED", "reason=no_op_v1053")
            } catch (_: Throwable) {}

            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
            // V5.9.934 â€” Surface AIStartupCoordinator state to operator at
            // bot-start. Pre-934 the coordinator's verdict was completely
            // invisible â€” it logged its own readiness internally but neither
            // the bot's UI feed nor the startBot path consulted it. Now the
            // operator sees a clear ON/OFF banner for every start, plus the
            // FDG soft-shape (this push) honors the state in LIVE mode.
            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
            try {
                val aiReady = com.lifecyclebot.v3.core.AIStartupCoordinator.isTradingAllowed()
                val summary = com.lifecyclebot.v3.core.AIStartupCoordinator.getSummary()
                if (aiReady) {
                    addLog("ğŸ§  AI Subsystems READY â€” $summary")
                } else {
                    addLog("âš ï¸ AI degraded â€” LIVE sizing reduced to 50%, paper full-size for learning ($summary)")
                    ErrorLogger.warn("BotService", "AIStartupCoordinator reports NOT ready: $summary")
                }
            } catch (_: Throwable) { /* fail-open â€” never block startBot on coordinator surface */ }

            status.running = true
            // V5.0.6662 â€” anchor the mandatory 120-second acceptance window
            // to the accepted runtime start.  The audit cadence is longer than
            // the smoke capture, so lazy baseline creation could never finish.
            scheduleExecutionSpineAcceptance6666(runtimeGeneration)
            // Note: startForeground is already called in onStartCommand to meet Android's 5-second requirement
            ErrorLogger.info("BotService", "Foreground service started")
            addLog("âœ“ Foreground service started")

            // Register network callback to reconnect WebSocket after connectivity loss
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (status.running) {
                            addLog("ğŸ“¡ Network restored â€” reconnecting streams")
                            scope.launch {
                                delay(2_000)
                                try {
                                    orchestrator?.reconnectStreams()
                                } catch (e: Exception) {
                                    addLog("Stream reconnect error: ${e.message}")
                                }
                            }
                        }
                    }
                    override fun onLost(network: Network) {
                        if (status.running) addLog("ğŸ“¡ Network lost â€” WebSocket will reconnect on restore")
                    }
                }
                cm.registerNetworkCallback(req, networkCallback!!)
                addLog("âœ“ Network callback registered")
            } catch (e: Exception) {
                addLog("âš ï¸ Network callback failed: ${e.message}")
            }

            val cfg = ConfigStore.load(applicationContext)
            // V5.9.602 â€” publish + log the actual persisted runtime mode at
            // service start. Wallet-connected/live-ready UI is not enough;
            // this is the authority that decides paper vs live execution.
            RuntimeModeAuthority.publishRuntimeStart(cfg.paperMode, cfg.autoTrade)
            PipelineHealthCollector.resetModeCountersForRuntime(if (cfg.paperMode) "PAPER" else "LIVE")
            addLog("âœ“ Config loaded: paperMode=${cfg.paperMode} authority=${RuntimeModeAuthority.authority()} modeSynced=true")

            // V5.9.105: fresh start = clean circuit breaker. beginSession() is
            // called below once the live wallet balance is known.
            LiveSafetyCircuitBreaker.reset()

            // â”€â”€ Paper wallet: restore from SharedPrefs (survives app updates) â”€â”€
            val botPrefs = getSharedPreferences("bot_paper_wallet", android.content.Context.MODE_PRIVATE)
            val savedBalance = botPrefs.getFloat("paper_wallet_sol", 0f).toDouble()
            // V5.0.6413 â€” PAPER-BALANCE WIPE GUARD.
            // Operator report: "randomly wiped the paper balance back to nothing.
            // it was matching the journal records increasing as wins banked etc.
            // same as the treasury." Root cause: savedBalance reads 0 either when
            // (a) the prefs key is truly absent (fresh install) OR (b) a corrupted/
            // racy read returns the default. The old logic treated both cases
            // identically â€” path (a) legitimately seeds cfg.paperSimulatedBalance,
            // but path (b) wipes gains. Distinguish them by presence of the key
            // AND by trusting the journal when it has ANY history at all.
            val paperKeyPresent6413 = try { botPrefs.contains("paper_wallet_sol") } catch (_: Throwable) { false }
            val savedBalanceTrusted6413 = paperKeyPresent6413 && savedBalance.isFinite()
            val lastModeWasPaper = botPrefs.getBoolean("last_mode_was_paper", true)
            val modeChangedLiveToPaper = cfg.paperMode && !lastModeWasPaper

            if (cfg.paperMode) {
                // V5.0.6376 â€” PAPER WALLET CONTINUITY (operator directive):
                //   "if I update or switch to live it resets the paper balance
                //    and wipes any gains... the wallet balance isn't growing
                //    despite the journal showing massive gains."
                //
                // Historical V5.9.54 code reset paperWalletSol to cfg.paperSimulatedBalance
                // whenever `modeChangedLiveToPaper == true`. This was the direct cause of
                // paper gains being wiped on every LIVEâ†’PAPER toggle. The paper wallet
                // has its own dedicated SharedPreferences key (`paper_wallet_sol`) that
                // never gets polluted by live-mode data, so a mode toggle MUST NOT
                // touch it. The concern V5.9.54 was worried about ("live balance
                // leaking in") never applied â€” those are two separate values.
                //
                // Reset logic now:
                //   â€¢ savedBalance < 0.01 with EMPTY journal   â†’ fresh install, seed cfg.paperSimulatedBalance
                //   â€¢ savedBalance < 0.01 with journal history â†’ wallet-truthful restore from journal
                //   â€¢ Otherwise                                â†’ restore savedBalance as-is
                //   â€¢ Sanity ceiling (100Ã— starting) still snaps to 10Ã— to break inflation loops
                val SANITY_CEILING_MULT = 100.0
                val sanityCeiling = cfg.paperSimulatedBalance * SANITY_CEILING_MULT
                val sanityResetTarget = cfg.paperSimulatedBalance * 10.0  // 10x = realistic "good run" cap
                val journalRealizedSol = try { TradeHistoryStore.getLifetimeStats().realizedPnlSol } catch (_: Throwable) { 0.0 }
                val journalHasHistory = try { TradeHistoryStore.getLifetimeStats().totalSells > 0 } catch (_: Throwable) { false }
                when {
                    savedBalance < 0.01 && !journalHasHistory && !paperKeyPresent6413 -> {
                        // V5.0.6413 â€” only seed when the key was NEVER written.
                        // Prevents a racy 0-read from wiping a real balance.
                        status.paperWalletSol = cfg.paperSimulatedBalance
                        addLog("ğŸ”„ Fresh install: paper wallet seeded to ${cfg.paperSimulatedBalance} SOL (no prefs key, no journal history)")
                        botPrefs.edit().putFloat("paper_wallet_sol", cfg.paperSimulatedBalance.toFloat()).apply()
                    }
                    savedBalance < 0.01 && journalHasHistory -> {
                        // Wallet-truthful restore: paperWallet = starting capital + realized journal PnL.
                        // Prefs got wiped (rare: sideload/backup restore) but the journal is intact,
                        // so we can rebuild the paper wallet without wiping gains.
                        val restored = (cfg.paperSimulatedBalance + journalRealizedSol).coerceAtLeast(0.01)
                        status.paperWalletSol = restored
                        addLog("ğŸ’° V5.0.6376 wallet-truthful restore: prefs missing but journal has ${TradeHistoryStore.getLifetimeStats().totalSells} sells â†’ wallet=${"%.4f".format(restored)} SOL (start ${cfg.paperSimulatedBalance} + realized ${"%.4f".format(journalRealizedSol)})")
                        try { PipelineHealthCollector.labelInc("PAPER_WALLET_JOURNAL_RESTORE_6376") } catch (_: Throwable) {}
                        botPrefs.edit().putFloat("paper_wallet_sol", restored.toFloat()).apply()
                    }
                    savedBalance > sanityCeiling -> {
                        ErrorLogger.warn("BotService",
                            "ğŸš¨ PAPER_SANITY_RESET: persisted=${savedBalance.fmt(2)} SOL > ${sanityCeiling.fmt(0)} SOL ceiling " +
                            "(${(savedBalance/cfg.paperSimulatedBalance).toInt()}x starting). Sizer fantasy-feedback loop detected. " +
                            "Snapping to ${sanityResetTarget.fmt(2)} SOL to break the loop.")
                        status.paperWalletSol = sanityResetTarget
                        addLog("ğŸš¨ Paper sanity reset: ${savedBalance.fmt(0)} SOL â†’ ${sanityResetTarget.fmt(2)} SOL (inflated feedback loop broken)")
                        botPrefs.edit().putFloat("paper_wallet_sol", sanityResetTarget.toFloat()).apply()
                        try { FluidLearning.reset(sanityResetTarget) } catch (_: Throwable) {}
                    }
                    else -> {
                        // V5.0.6413 â€” if we hit the else branch with a corrupted-read
                        // (savedBalance < 0.01 AND key WAS present AND no journal
                        // history to restore from), REFUSE to blow away the balance.
                        // Log the anomaly and hold at previous status.paperWalletSol
                        // (which is 0.0 on first startup but preserved on hot restart).
                        val incomingBalance6413 = savedBalance
                        val holdIncoming6413 = savedBalance < 0.01 && paperKeyPresent6413 && !journalHasHistory
                        if (holdIncoming6413) {
                            try {
                                PipelineHealthCollector.labelInc("PAPER_WALLET_WIPE_GUARD_HELD_6413")
                                ForensicLogger.lifecycle(
                                    "PAPER_WALLET_WIPE_GUARD_HELD_6413",
                                    "savedBalance=$incomingBalance6413 keyPresent=$paperKeyPresent6413 journalSells=0 " +
                                        "priorStatusBalance=${status.paperWalletSol} action=refuse_zero_restore",
                                )
                            } catch (_: Throwable) {}
                            // Keep whatever status.paperWalletSol currently holds (typically 0
                            // on fresh process, or the last in-memory value). Do NOT overwrite
                            // prefs with 0 â€” force a self-heal on next successful buy/sell.
                        } else {
                            status.paperWalletSol = savedBalance
                        }
                        val modeTag = if (modeChangedLiveToPaper) " (V5.0.6376 preserved across LIVEâ†’PAPER switch)" else ""
                        addLog("ğŸ’° Paper wallet restored: ${"%.4f".format(savedBalance)} SOL$modeTag" + if (holdIncoming6413) " âš ï¸ 6413_WIPE_GUARD_HELD" else "")
                        if (modeChangedLiveToPaper) {
                            try { PipelineHealthCollector.labelInc("PAPER_WALLET_MODE_TOGGLE_PRESERVED_6376") } catch (_: Throwable) {}
                        }
                    }
                }
                repairUnifiedPaperWalletIfImpossible("startBot.restore")
                // Clear state that accumulates during live sessions and blocks paper trades
                ReentryGuard.clearAll()
                FinalDecisionGate.clearAllEdgeVetoes()
                FinalDecisionGate.resetLearningState()  // V5.9.182: reset stale block counts
                addLog("ğŸ”„ Paper mode start: reentry locks + edge vetoes cleared")
                // V5.9.706 â€” FLUID LEARNING BALANCE SYNC.
                // SmartSizer routes ALL paper sizing through FluidLearning.getSimulatedBalance()
                // when fluidLearningEnabled=true. status.paperWalletSol and FluidLearning are
                // TWO SEPARATE balances. If FluidLearning drained to near-zero (blown paper run),
                // SmartSizer returns size=0 for every trade â€” bot appears fully idle even with
                // 400+ tokens in watchlist. The status.paperWalletSol reset above doesn't fix it.
                // Fix: sync FluidLearning balance to match the paper wallet on every startBot().
                try {
                    val fluidBal = FluidLearning.getSimulatedBalance()
                    val paperBal = status.paperWalletSol
                    val reserveSol = cfg.walletReserveSol.coerceAtLeast(0.05)
                    if (fluidBal < reserveSol * 1.5) {
                        FluidLearning.forceSetBalance(paperBal)
                        addLog("ğŸ”„ FluidLearning balance synced to paper wallet: ${"%.4f".format(paperBal)} SOL (was ${"%.4f".format(fluidBal)} SOL)")
                        ErrorLogger.warn("BotService", "V5.9.706 FLUID_SYNC: FluidLearning was $fluidBal SOL (below trade floor) â€” synced to $paperBal SOL")
                    }
                } catch (e: Exception) {
                    ErrorLogger.warn("BotService", "FluidLearning sync failed (non-fatal): ${e.message}")
                }
            }

            // V5.9.721 â€” LOW-WR STREAK RESET on startBot.
            // If system lifetime WR < 30%, the consecutive-loss tilt counter is likely
            // elevated from a bad run, keeping Copilot in EMERGENCY BRAKE and blocking
            // entries. A soft reset clears the streak/tilt WITHOUT wiping milestones,
            // giving the bot a clean tilt slate so it can trade its way out of the hole.
            try {
                val ls = com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats()
                val systemWr = if (ls.totalSells > 0) ls.totalWins.toDouble() / ls.totalSells else 1.0
                if (systemWr < 0.30 && ls.totalSells >= 50) {  // Only fire if we have real data
                    com.lifecyclebot.v3.scoring.BehaviorAI.softStreakReset()
                    // V5.9.730: Force-persist the cleared streak so it survives restart.
                    // Without this, restore() reloads the old loss count from SharedPrefs
                    // on the very next app launch, re-triggering EMERGENCY_BRAKE.
                    try {
                        com.lifecyclebot.v3.scoring.BehaviorAI.save(force = true)
                        addLog("ğŸ”„ V5.9.730: BehaviorAI streak persisted â€” won't restore on restart")
                    } catch (e2: Exception) {
                        ErrorLogger.debug("BotService", "BehaviorAI.save non-fatal: ${e2.message}")
                    }
                    // V5.9.730: Also clear Copilot's rolling PnL window so the live
                    // streak counter resets â€” BehaviorAI and Copilot use separate streak
                    // counters; softStreakReset only fixes BehaviorAI.
                    try {
                        com.lifecyclebot.engine.TradingCopilot.clearLossWindow()
                        addLog("ğŸ”„ V5.9.730: Copilot loss window cleared â€” live streak brake released")
                    } catch (e3: Exception) {
                        ErrorLogger.debug("BotService", "clearLossWindow non-fatal: ${e3.message}")
                    }
                    // V5.9.721: Also clear contaminated layer expectancy so the polarity-flip gate
                    // doesn't re-engage immediately on WR recovery using stale loss-run data.
                    try {
                        com.lifecyclebot.v3.scoring.EducationSubLayerAI.resetExpectancy()
                        addLog("ğŸ”„ V5.9.721: Layer expectancy cleared (WR=${(systemWr*100).toInt()}%) â€” polarity flip slate reset")
                    } catch (e4: Exception) {
                        ErrorLogger.debug("BotService", "resetExpectancy non-fatal: ${e4.message}")
                    }
                    addLog("ğŸ”„ V5.9.730: Full brake release (WR=${(systemWr*100).toInt()}%) â€” BehaviorAI + Copilot window + expectancy cleared")
                    ErrorLogger.warn("BotService", "V5.9.730 FULL_BRAKE_RELEASE: WR=${(systemWr*100).toInt()}% trades=${ls.totalSells} â€” all streak counters cleared + persisted")
                }
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "Low-WR streak reset check failed (non-fatal): ${e.message}")
            }

            // Persist current mode so next start can detect a mode switch
            botPrefs.edit().putBoolean("last_mode_was_paper", cfg.paperMode).apply()
            
            // Determine best RPC URL - prefer Helius if key available
            val rpcUrl = if (cfg.heliusApiKey.isNotBlank()) {
                "https://mainnet.helius-rpc.com/?api-key=${cfg.heliusApiKey}"
            } else {
                cfg.rpcUrl
            }
            
            // Check if wallet is already connected via singleton
            val alreadyConnected = walletManager.state.value.connectionState == WalletConnectionState.CONNECTED
            
            wallet = if (!cfg.paperMode && cfg.privateKeyB58.isNotBlank()) {
                if (alreadyConnected) {
                    // Wallet already connected - reuse it
                    addLog("âœ“ Wallet already connected: ${walletManager.state.value.shortKey}")
                    walletManager.getWallet()
                } else {
                    // V5.9.73 FIX: previously this called walletManager.connect()
                    // SYNCHRONOUSLY inside startBot(). Each RPC attempt could
                    // time out for ~30s; with two fallbacks sequenced after
                    // the user's RPC that's the 90-second freeze where the
                    // bot loop never starts, the UI stops receiving log
                    // updates, and toggling to paper mode can't recover
                    // until the stuck connect finally returns.
                    //
                    // Now: kick connect off in a detached coroutine, let
                    // startBot() continue immediately. Live trades inside
                    // the bot loop already null-check `wallet` and skip
                    // live actions until it's ready. Reconciliation fires
                    // once the wallet actually arrives.
                    addLog("ğŸ”Œ Connecting wallet in backgroundâ€¦")
                    launchWalletConnect(cfg.privateKeyB58, rpcUrl, runReconciliation = true)
                    null
                }
            } else {
                addLog("Paper mode enabled or no key provided")
                // Any in-flight live connect is now obsolete â€” kill it so
                // switching from live â†’ paper doesn't leave a stuck job
                // holding the wallet variable.
                walletConnectJob?.cancel()
                walletConnectJob = null
                null
            }

            // Run startup reconciliation to catch any state mismatch
            // from previous crash, manual sells, or failed transactions
            // (only fires here if wallet was already connected; the async
            // connect path above triggers it from inside the launch.)
            val liveWallet = wallet
            if (liveWallet != null) {
                scope.launch {
                    try {
                        val reconciler = StartupReconciler(
                            wallet  = liveWallet,
                            status  = status,
                            onLog   = { msg -> addLog(msg) },
                            onAlert = { title, body ->
                                sendTradeNotif(title, body,
                                    NotificationHistory.NotifEntry.NotifType.INFO)
                            },
                            executor = executor,  // Pass executor for orphan auto-sell
                            // V5.9.102: default off. Adoption path now runs first;
                            // only truly-unknown mints with no price data fall
                            // through to the sell path. User's friend lost tracked
                            // exits on profitable positions (FOF +$10.95) because
                            // auto-sell liquidated them on startup.
                            autoSellOrphans = false
                        )
                        reconciler.reconcile()
                    } catch (e: Exception) {
                        addLog("Reconciliation error: ${e.message}")
                    }
                }
            } else if (cfg.paperMode) {
                addLog("Paper mode â€” skipping on-chain reconciliation")
            }

            // V5.9.495z20 â€” Recovery Execution Loop. Periodic worker that
            // processes orphan-asset records (USDC residue from any pre-z19
            // partial-bridge buys, or the rare atomic-route surprise). It
            // only makes sense in LIVE mode where wallet has on-chain assets.
            try {
                val w = wallet
                if (!cfg.paperMode && w != null) {
                    com.lifecyclebot.engine.execution.RecoveryExecutionLoop.start(w)
                    addLog("ğŸ”„ Recovery loop started (orphan-USDC processor)")
                }
            } catch (e: Exception) {
                addLog("âš ï¸ Recovery loop start failed: ${e.message}")
            }

            // V5.9.495z22 (item B) â€” PositionWalletReconciler. Periodic worker
            // that compares each open position against actual host-wallet
            // truth and fires a critical alert + forensics PHANTOM_POSITION
            // event when a position's resolved mint has zero on-chain
            // balance after the settlement grace window. Running in BOTH
            // paper and live so phantoms in shadow runs are also flagged
            // (HostWalletTokenTracker is live-truth either way).
            try {
                val w = wallet
                if (w != null) {
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.installHostTrackerSource()
                    // V5.9.495z25 â€” register CryptoAltTrader as its own
                    // reconciler source so its open positions get phantom-
                    // checked directly (not just transitively via the host
                    // tracker).
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.registerSource("CryptoAltTrader") {
                        try {
                            com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions().mapNotNull { p ->
                                val resolvedMint = try {
                                    if (p.dynMint != null) p.dynMint
                                        .takeIf { !it.startsWith("cg:") && !it.startsWith("static:") }
                                        ?.takeIf { com.lifecyclebot.engine.execution.MintIntegrityGate.isLikelyMint(it) }
                                    else com.lifecyclebot.perps.crypto.CryptoWrappedAssetMapper.resolveWrappedMint(p.marketSymbol)
                                } catch (_: Throwable) { null }
                                com.lifecyclebot.engine.execution.PositionWalletReconciler.ReportedPosition(
                                    laneTag = "CRYPTO_ALT",
                                    intendedSymbol = p.marketSymbol,
                                    resolvedMint = resolvedMint,
                                    openedAtMs = p.openTime,
                                    sizeUiAmount = p.sizeSol,
                                )
                            }
                        } catch (_: Throwable) { emptyList() }
                    }
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.start(w)
                    addLog("ğŸ›¡ Positionâ†”Wallet reconciler started (host + crypto-alt)")

                    // V5.9.495z45 â€” operator forensics_20260508_143519 fix.
                    // Cold-start trigger of LiveWalletReconciler so the
                    // reconciler.totalChecked counter and the
                    // HostWalletTokenTracker.applyWalletSnapshot pipeline
                    // both kick over the moment the bot starts (not after
                    // the first per-token cycle, which can be > 30s late).
                    try {
                        com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(w, "bot_start")
                    } catch (_: Throwable) { /* fail-soft */ }

                    // V5.9.756 â€” Emergent ticket item #4: dedicated periodic
                    // reconciler. Ticks every 10 s, but each tick is a no-op
                    // unless live mode AND open live positions exist. This
                    // GUARANTEES totalChecked > 0 whenever live positions
                    // exist, regardless of whether the per-cycle reconcile
                    // ran or got rate-limited. Cancelled in stopBot().
                    reconcilerJob?.cancel()
                    reconcilerJob = scope.launch {
                        try {
                            // First tick delayed slightly so bot_start has
                            // already taken the throttle slot.
                            kotlinx.coroutines.delay(11_000L)
                            while (kotlinx.coroutines.currentCoroutineContext().isActive && status.running) {
                                try {
                                    val isLive = !com.lifecyclebot.data.ConfigStore.load(applicationContext).paperMode
                                    val openLive = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { 0 }
                                    val lifecycleOpen = try { com.lifecyclebot.engine.TokenLifecycleTracker.openCount() } catch (_: Throwable) { 0 }
                                    val statusLive = try { synchronized(status.tokens) { status.tokens.values.count { it.position.isOpen && !it.position.isPaperPosition } } } catch (_: Throwable) { 0 }
                                    val walletHeld = try { com.lifecyclebot.engine.HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { 0 }
                                    val shouldReconcile = isLive && (openLive > 0 || lifecycleOpen > 0 || statusLive > 0 || walletHeld > 0)
                                    if (shouldReconcile) {
                                        // Bypass the 30 s throttle for this guaranteed-cadence loop.
                                        // It must run even when hostOpen=0 but stale canonical/status
                                        // rows exist; otherwise totalChecked can stay 0 during drift.
                                        try {
                                            com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileBlocking(
                                                w, "periodic_live_host${openLive}_life${lifecycleOpen}_status${statusLive}_wallet${walletHeld}")
                                        } catch (e: Throwable) {
                                            ErrorLogger.warn("BotService",
                                                "periodic reconcile failed: ${e.message}")
                                        }
                                    }
                                } catch (_: Throwable) { /* never break the loop */ }
                                kotlinx.coroutines.delay(10_000L)
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // expected on stopBot
                        } catch (e: Throwable) {
                            ErrorLogger.warn("BotService", "reconcilerJob crashed: ${e.message}")
                        }
                    }
                    addLog("ğŸ”„ Periodic live-wallet reconciler armed (10 s cadence)")
                }
            } catch (e: Exception) {
                addLog("âš ï¸ Reconciler start failed: ${e.message}")
            }

            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
            // V5.6.9: RESTORE PERSISTED POSITIONS ON BOT START
            // 
            // This recovers positions that were lost when the app was killed.
            // CRITICAL: Must happen BEFORE botLoop() starts to avoid duplicate entries.
            //
            // V5.9.681 â€” GHOST REAPER ON START.
            // If KEY_MANUAL_STOP_REQUESTED == true on the runtime-prefs, the
            // previous shutdown was an explicit user-pressed Stop. In that case
            // stopBot() should have already cleared PositionPersistence â€” if
            // it did not (slow paper-sell loop, frozen bot loop, process kill
            // mid-stop), any positions still in storage are by definition
            // ghosts from an unclean teardown. Wipe the persistence BEFORE
            // restorePositions so we boot from a clean slate.
            //
            // V5.9.682 â€” MASS-GHOST FALLBACK.
            // Operator screenshot V5.9.681 showed 23 V3_SKIPPED position_open
            // events even though only 3 positions were really open. Root
            // cause: previous V5.9.680 session froze without a clean stop,
            // KEY_MANUAL_STOP_REQUESTED stayed false (force-killed process),
            // and the V5.9.681 reaper gated itself off. The unbroken V5.6.9
            // contract said "restore everything if not a manual stop", but
            // 20+ paper positions on a cold boot is by definition a ghost
            // backlog from a stuck previous session â€” never a legitimate
            // recovery. Now: in paper mode, if persistedCount > MASS_GHOST_THRESHOLD
            // (20) we wipe regardless of the manual-stop flag. Live mode
            // still honors the existing V5.6.9 path (legitimate on-chain
            // positions might be valuable and shouldn't auto-wipe).
            // Crash/kill paths with < 20 persisted rows still restore.
            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
            try {
                val prevWasManualStop = getSharedPreferences(RUNTIME_PREFS, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY_MANUAL_STOP_REQUESTED, false)
                val persistedBefore = try { PositionPersistence.getPersistedCount() } catch (_: Throwable) { -1 }
                val MASS_GHOST_THRESHOLD = 20
                val cfgForReap = try { ConfigStore.load(applicationContext) } catch (_: Throwable) { null }
                val isPaperReap = cfgForReap?.paperMode ?: true
                val massGhost = isPaperReap && persistedBefore > MASS_GHOST_THRESHOLD
                val shouldReap = (prevWasManualStop && persistedBefore > 0) || massGhost
                if (shouldReap) {
                    try {
                        PositionPersistence.clear()
                        val reason = when {
                            massGhost -> "mass_ghost_paper"
                            else      -> "manual_stop_unclean"
                        }
                        ForensicLogger.lifecycle(
                            "START_GHOST_REAP",
                            "reason=$reason prevManualStop=$prevWasManualStop persistedBefore=$persistedBefore wiped=true paper=$isPaperReap"
                        )
                        addLog("ğŸ§¹ Ghost reaper ($reason): wiped $persistedBefore stale position(s)")
                    } catch (e: Throwable) {
                        ErrorLogger.warn("BotService", "ghost reap wipe failed: ${e.message}")
                    }
                } else if (persistedBefore > 0) {
                    try {
                        ForensicLogger.lifecycle(
                            "START_GHOST_REAP",
                            "reason=skipped persistedBefore=$persistedBefore prevManualStop=$prevWasManualStop paper=$isPaperReap"
                        )
                    } catch (_: Throwable) {}
                }
            } catch (e: Exception) {
                ErrorLogger.debug("BotService", "ghost reap pre-check error: ${e.message}")
            }

            try {
                val tokensCopy = synchronized(status.tokens) { status.tokens.toMutableMap() }
                val restoredCount = PositionPersistence.restorePositions(tokensCopy)
                if (restoredCount > 0) {
                    // Copy restored tokens back to status
                    synchronized(status.tokens) {
                        tokensCopy.forEach { (mint, ts) ->
                            if (!status.tokens.containsKey(mint)) {
                                status.tokens[mint] = ts
                            } else if (ts.position.isOpen && !status.tokens[mint]!!.position.isOpen) {
                                // Restored position for existing token
                                status.tokens[mint]!!.position = ts.position
                            }
                        }
                    }
                    addLog("ğŸ’¾ RESTORED $restoredCount position(s) from persistence")
                    sendTradeNotif("Positions Restored", 
                        "$restoredCount position(s) recovered after restart",
                        NotificationHistory.NotifEntry.NotifType.INFO)
                    ErrorLogger.info("BotService", "ğŸ’¾ Restored $restoredCount positions from persistence")
                }
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Failed to restore positions: ${e.message}", e)
                addLog("âš ï¸ Position restore failed: ${e.message}")
            }

            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
            // V5.9.1507 â€” STARTUP HARD GHOST RECONCILE (refresh to wallet-truth
            // on Start). Operator: "0/36 ... it should instantly refresh to 0 on
            // bot start." After persistence restore, take ONE fresh wallet
            // snapshot and terminally close every tracked OPEN position the
            // wallet does NOT actually hold. Real on-chain holdings are kept.
            // Off the main thread (RPC). Runs in LIVE and PAPER (paper passes an
            // empty map â†’ all internal ghosts collapse, which is correct since a
            // fresh paper start holds nothing).
            //
            // V5.0.6068 â€” TWO-READ CONSENSUS + API-HEALTH GATE (operator P0:
            // "I can have 14 live positions running g install an update it drops
            // them all"). The single-read gate above was insufficient when
            // Helius returns partial snapshots during cold-start 429 storms.
            // A partial read + `forceStartupGhostReconcile` = real positions
            // erroneously closed as ghosts. Two new safeguards:
            //   1. API-health gate: refuse to reconcile if Helius/RPC is
            //      visibly degraded (<30% success rate).
            //   2. Two-read consensus: run the wallet scan twice with a 5s
            //      gap between reads. Only reconcile mints that appear
            //      MISSING in BOTH reads. Any position present in either
            //      read is preserved.
            try {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val cfgGhost = try { ConfigStore.load(applicationContext) } catch (_: Throwable) { null }
                        val paperGhost = cfgGhost?.paperMode ?: true
                        // V5.0.6068 â€” API-health gate. If Helius is 429ing, do NOT reconcile.
                        val heliusHealthy = try { com.lifecyclebot.engine.ApiHealthMonitor.successRate("helius_rpc") >= 0.30 } catch (_: Throwable) { false }
                        if (!paperGhost && !heliusHealthy) {
                            addLog("ğŸ›¡ Startup reconcile DEFERRED â€” Helius RPC degraded (V5.0.6068 guard)")
                            ForensicLogger.lifecycle("STARTUP_GHOST_RECONCILE_DEFERRED_6068", "reason=helius_unhealthy_would_erase_real_positions")
                            try { PipelineHealthCollector.labelInc("STARTUP_GHOST_RECONCILE_DEFERRED_6068") } catch (_: Throwable) {}
                            return@launch
                        }
                        val snap1: Map<String, com.lifecyclebot.engine.truth.CanonicalTokenAmount> = if (paperGhost) {
                            emptyMap()
                        } else {
                            try { wallet?.getTokenAccountsWithDecimalsBounded() ?: emptyMap() } catch (_: Throwable) { emptyMap() }
                        }
                        // V5.0.6068 â€” TWO-READ CONSENSUS for live mode. Single wallet
                        // read on cold-start can be partial due to RPC 429 or timeout.
                        // Wait 5s and read again. Only close mints missing from BOTH.
                        val snap: Map<String, com.lifecyclebot.engine.truth.CanonicalTokenAmount> = if (paperGhost) {
                            snap1
                        } else {
                            kotlinx.coroutines.delay(5_000L)
                            val snap2: Map<String, com.lifecyclebot.engine.truth.CanonicalTokenAmount> = try { wallet?.getTokenAccountsWithDecimalsBounded() ?: emptyMap() } catch (_: Throwable) { emptyMap() }
                            if (snap1.isEmpty() && snap2.isEmpty()) {
                                addLog("ğŸ›¡ Startup reconcile DEFERRED â€” both wallet reads empty (V5.0.6068)")
                                ForensicLogger.lifecycle("STARTUP_GHOST_RECONCILE_DEFERRED_6068", "reason=both_reads_empty_would_erase_real_positions snap1=${snap1.size} snap2=${snap2.size}")
                                try { PipelineHealthCollector.labelInc("STARTUP_GHOST_RECONCILE_DEFERRED_6068") } catch (_: Throwable) {}
                                return@launch
                            }
                            // Merge UNION of both reads. A mint present in EITHER read is
                            // preserved. Only mints missing from BOTH are subject to close.
                            val merged = HashMap<String, com.lifecyclebot.engine.truth.CanonicalTokenAmount>()
                            merged.putAll(snap1)
                            for ((k, v) in snap2) if (!merged.containsKey(k)) merged[k] = v
                            ForensicLogger.lifecycle("STARTUP_GHOST_RECONCILE_TWO_READ_UNION_6068", "snap1=${snap1.size} snap2=${snap2.size} merged=${merged.size}")
                            merged
                        }
                        // LIVE safety: if the merged snapshot is empty (both reads failed)
                        // we must NOT wipe real holdings.
                        if (paperGhost || snap.isNotEmpty()) {
                            val closed = com.lifecyclebot.engine.HostWalletTokenTracker.forceStartupGhostReconcile(snap)
                            if (closed > 0) addLog("ğŸ§¹ Startup reconcile: closed $closed ghost position(s) â†’ wallet-truth")
                        } else {
                            ForensicLogger.lifecycle("STARTUP_GHOST_RECONCILE", "skipped=wallet_read_empty_live_safety")
                        }
                    } catch (e: Throwable) {
                        ErrorLogger.debug("BotService", "startup ghost reconcile error: ${e.message}")
                    }
                }
            } catch (_: Throwable) {}

        // V5.0.6486 â€” paper price absence is not economic finality. The old
        // deferred ghost purge/refund was removed: it fabricated breakeven closes,
        // mutated wallet cash, poisoned journals, and released slots without a
        // canonical executed SELL. Canonical lifecycle/recovery owns cleanup.
        try { PipelineHealthCollector.labelInc("PAPER_GHOST_PURGE_REMOVED_6486") } catch (_: Throwable) {}

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // V5.9.430 â€” START-UP HARD-FLOOR CATCH-UP SWEEP
        // V5.9.665 â€” operator ANR fix: deferred + paced.
        //
        // Originally this sweep ran SYNCHRONOUSLY on the startBot() body,
        // walking every restored position and calling resolveLivePrice() +
        // executor.requestSell() inline. With dozens of restored live
        // positions on a fresh install, this burst of synchronous network
        // I/O blocked the IO scope long enough to cascade UI stalls and
        // the operator's "freezes + ANR warnings the moment I go live"
        // symptom.
        //
        // Now: wrapped in scope.launch with a 5s startup grace, plus a
        // 250ms gap between positions. The botLoop launches first, the
        // UI gets responsive, and the sweep still does its job in the
        // background.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        scope.launch {
            try {
                // V5.0.6068 â€” LONGER GRACE + API-HEALTH GATE (operator P0:
                // "updating g the bot forced held positions to drop"). The old
                // 5s grace was too short â€” on cold-start Helius is often 429
                // and DexScreener fresh-hits return stale/incorrect prices,
                // which the sweep read as -15% and force-closed HEALTHY
                // positions during the install-over window. Two changes:
                //   1. Grace bumped 5s -> 90s so oracles have time to warm and
                //      RPC 429-storms settle down.
                //   2. Gate the sweep on API health â€” if the primary price
                //      surfaces (Helius/Birdeye/CoinGecko) are visibly
                //      degraded, DO NOT sweep. Wait for the next attempt when
                //      prices are trustworthy.
                kotlinx.coroutines.delay(90_000L)  // 90s grace (was 5s)
                // V5.0.6068 â€” API-health gate. Consult ApiHealthMonitor for
                // provider health. If any critical price provider is <30%
                // success rate (rate-limited or down), skip the sweep for
                // this cycle. Aggressive threshold on purpose: better to keep
                // a stuck position than to force-close a HEALTHY one on
                // bad-oracle data.
                val healthyOracles = try {
                    val dex = com.lifecyclebot.engine.ApiHealthMonitor.successRate("dexscreener")
                    val hel = com.lifecyclebot.engine.ApiHealthMonitor.successRate("helius_rpc")
                    val bird = com.lifecyclebot.engine.ApiHealthMonitor.successRate("birdeye")
                    // Need DexScreener healthy AND at least one of Helius/Birdeye
                    dex >= 0.60 && (hel >= 0.30 || bird >= 0.30)
                } catch (_: Throwable) { false }  // fail-closed: don't sweep on doubt
                if (!healthyOracles) {
                    addLog("ğŸ›¡ Startup sweep DEFERRED â€” API oracles cold or degraded (V5.0.6068 guard)")
                    ErrorLogger.warn("BotService", "ğŸ›¡ STARTUP_SWEEP_DEFERRED_6068 reason=api_oracles_unhealthy â€” refusing to force-close positions on unreliable price data")
                    try { PipelineHealthCollector.labelInc("STARTUP_SWEEP_DEFERRED_6068") } catch (_: Throwable) {}
                    return@launch
                }
                val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                val sweepWallet = null as com.lifecyclebot.network.SolanaWallet?  // live wallet not wired until below; paper doesn't need it
                val snapshot = synchronized(status.tokens) { status.tokens.values.toList() }
                var swept = 0
                for (ts in snapshot) {
                    try {
                        val pos = ts.position
                        if (pos.qtyToken <= 0.0 || pos.entryPrice <= 0.0) continue
                        val price = resolveLivePrice(ts)
                        if (price <= 0.0) continue
                        val pnlVerdict6038 = OpenPnlSanity.inspectPosition(pos, price, "BotService.startup_sweep_6038/${ts.symbol}/${ts.mint.take(8)}", emit = true)
                        if (!pnlVerdict6038.ok) continue
                        val pnlPct = pnlVerdict6038.pnlPct
                        // V5.9.989 â€” sweep threshold aligned to operator-mandated -15%
                        // hard floor. Pre-V5.9.989: on cold-start, recovered positions
                        // sitting between -15% and -20% were NOT swept here (the only
                        // sweep above -20% was the global watchdog loop, which doesn't
                        // run until the bot fully boots â€” leaving a window where a
                        // -17% position survived restart un-stopped).
                        // V5.0.6068 â€” SECOND-PRICE CONFIRMATION. Before firing the
                        // sweep, re-read the price after 3s. A single-reading
                        // -15% could be a wick, a stale cache entry, or a bad
                        // provider fallback. Require BOTH reads to agree
                        // within 10% of the -15% threshold.
                        if (pnlPct <= -15.0) {
                            kotlinx.coroutines.delay(3_000L)
                            val price2 = resolveLivePrice(ts)
                            val pnl2 = if (price2 > 0.0) {
                                val v = OpenPnlSanity.inspectPosition(pos, price2, "BotService.startup_sweep_confirm_6068/${ts.symbol}/${ts.mint.take(8)}", emit = false)
                                if (v.ok) v.pnlPct else 0.0
                            } else 0.0
                            if (pnl2 > -13.0) {
                                ErrorLogger.warn("BotService",
                                    "ğŸ›¡ [STARTUP_SWEEP_ABORTED_6068] ${ts.symbol} | first=${pnlPct.toInt()}% confirm=${pnl2.toInt()}% â€” likely stale oracle, skipping force-close")
                                try { PipelineHealthCollector.labelInc("STARTUP_SWEEP_ABORTED_STALE_ORACLE_6068") } catch (_: Throwable) {}
                                continue
                            }
                            ErrorLogger.warn("BotService",
                                "ğŸ›‘ [STARTUP_SWEEP_HARD_FLOOR] ${ts.symbol} | ${pnlPct.toInt()}% (confirm=${pnl2.toInt()}%) â€” closing stale underwater position")
                            addLog("ğŸ›‘ STARTUP SWEEP: ${ts.symbol} ${pnlPct.toInt()}% â€” forced exit")
                            executor.requestSell(
                                ts = ts,
                                reason = "STARTUP_SWEEP_HARD_FLOOR_${pnlPct.toInt()}PCT",
                                wallet = sweepWallet,
                                walletSol = effectiveBalance,
                            )
                            try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(ts.mint, price,
                                    com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.STOP_LOSS) } catch (_: Exception) {}
                            try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.closePosition(ts.mint, price,
                                    com.lifecyclebot.v3.scoring.MoonshotTraderAI.ExitSignal.STOP_LOSS) } catch (_: Exception) {}
                            swept++
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "startup-sweep error for ${ts.symbol}: ${e.message}")
                    }
                    kotlinx.coroutines.delay(250L)  // pace network I/O so we never block the dispatcher
                }
                if (swept > 0) {
                    addLog("ğŸ§¹ Startup sweep: force-closed $swept underwater position(s) below -20%")
                    ErrorLogger.info("BotService", "Startup hard-floor sweep cleared $swept stuck positions (deferred)")
                }
            } catch (e: Exception) {
                ErrorLogger.error("BotService", "Startup hard-floor sweep failed: ${e.message}", e)
            }
        }

        // V5.9.621 â€” arm the inert-loop watchdog at start.
        lastScannerDiscoveryMs = System.currentTimeMillis()
        inertWatchdogFiredOnce = false
        // V5.9.714-FIX: mark session start so pendingVerify gate knows watchdog hasn't fired yet.
        botStartTimeMs = System.currentTimeMillis()

        addLog("âœ“ Starting bot loop...")
        val createdLoopJob6518 = scope.launch(botLoopDispatcher) { botLoop() } // V5.9.1023: dedicated single-thread dispatcher prevents Dispatchers.IO pool starvation from wedged supervisor workers
        synchronized(loopJobLock) { loopJob = createdLoopJob6518 }
        try {
            com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.setRuntimeActive(true, "BotService.startBot.launch6487")
            com.lifecyclebot.engine.truth.BackgroundTradingAuthority6469.registerRuntimeJob("BotService.startBot.launch6487")
        } catch (_: Throwable) {}
        BotRuntimeController.registerJob(runtimeGeneration, "botLoop", createdLoopJob6518)
        BotRuntimeController.publishRunning(runtimeGeneration, enabledTraders = try { EnabledTraderAuthority.snapshotStr() } catch (_: Throwable) { "" })
        runtimeCommitted = true
        try {
            ForensicLogger.lifecycle(
                "STARTBOT_RUNTIME_COMMITTED",
                "gen=$runtimeGeneration loopActive=${loopJob?.isActive == true} statusRunning=${status.running} runtimeState=${BotRuntimeController.snapshot().state}",
            )
        } catch (_: Throwable) {}

        // V5.9.905 â€” HIGH-FREQUENCY EXIT MANAGER LOOP.
        // Independent of scanner throughput. Ticks every 2s, walks every
        // open position, runs the in-position management triad (STRICT_SL
        // floor, partial sell ladder, profit lock, peak drawdown).
        // Doctrine: exit safety must NOT depend on scanner cycle time.
        // See hotExitJob field doc for full root-cause analysis.
        // V5.9.1313 â€” hotExit is now a restartable, self-healing manager.
        // The launch body moved into ensureHotExitAlive() so the botLoop can
        // resurrect it if it ever dies (see deferral guard). Start it here.
        ensureHotExitAlive()
        // V5.9.1522 â€” reconciler start extracted into startSellReconciler() so the
        // botLoop P0 watchdog (ensureSellReconcilerAlive) can re-invoke it after a
        // deferred (wallet-not-ready) start or a detected zombie. Mandatory in live.
        startSellReconciler(cfg, runtimeGeneration)

        // V5.9.777 â€” EMERGENT MEME-ONLY: LiveWalletReconciler periodic tick.
        // Operator forensics_20260516_014510 showed reconciler.totalChecked=0
        // and tickAtMs=0 â€” the wallet-truth reconciler was only invoked on
        // demand (throttled to 30 s) and had no background loop. The
        // periodic tick (6 s in LIVE mode) is now the canonical source of
        // wallet-truth-into-host-tracker hydration. PAPER mode skips it.
        if (!cfg.paperMode) {
            try {
                com.lifecyclebot.engine.sell.LiveWalletReconciler.start { WalletManager.getWallet() }
                ErrorLogger.info("BotService", "ğŸ”„ LiveWalletReconciler periodic tick STARTED for LIVE mode")
            } catch (e: Throwable) {
                ErrorLogger.warn("BotService", "LiveWalletReconciler start failed: ${e.message}")
            }
        } else {
            try { com.lifecyclebot.engine.sell.LiveWalletReconciler.stop() } catch (_: Throwable) {}
            ErrorLogger.info("BotService", "ğŸ”„ LiveWalletReconciler tick SKIPPED (paperMode=true)")
        }

        // V5.9.674 â€” STUCK-LOOP HEARTBEAT WATCHDOG. Operator reported the
        // loop coroutine going silent while still "active" (suspended on
        // a network call that has no timeout â€” Jupiter / RPC fallback chain
        // / DexScreener hydrate). Symptom: bot stops trading but loopJob.isActive
        // remains true, so the START button does nothing for 5-10 minutes
        // until the hung suspend point finally times out. V5.9.674 already
        // added a USER-triggered rescue path. This is the PROACTIVE side:
        // wake every 30s, compare lastBotLoopTickMs to wall clock. If the
        // loop has not ticked in >180s AND status.running is true (bot is
        // supposed to be alive), force-cancel the zombie loopJob with a
        // CancellationException and relaunch botLoop(). Bounded 3s join so
        // we never block on the same suspend point that hung the original.
        // Watchdog stops itself the moment status.running goes false (user
        // pressed Stop / startBot crashed).
        // V5.9.675 â€” DOZE-PROOF heartbeat replacement. The V5.9.674b coroutine
        // heartbeat (scope.launch { delay(30s); ... }) was suspended along
        // with the bot loop itself during Doze; operator's 7h pipeline dump
        // showed only 4 BOT_LOOP_TICKs across 25,891s of uptime (then +2,717
        // scan callbacks in 60s the moment the screen woke). Coroutine
        // delay() does not fire through Doze; AlarmManager.setAlarmClock
        // does. We schedule a real system alarm every 60s; when it fires
        // it broadcasts ACTION_LOOP_HEARTBEAT to ourselves, the alarm
        // handler in onStartCommand checks lastBotLoopTickMs vs wall clock,
        // and force-cancels + relaunches the loop coroutine if stale.
        try { loopHeartbeatJob?.cancel() } catch (_: Throwable) {}
        loopHeartbeatJob = null
        scheduleLoopHeartbeatAlarm()

        // V5.9.675 â€” battery-optimisation gate. Even with a foreground
        // service + PARTIAL_WAKE_LOCK, Doze suspends the process unless
        // the user has explicitly whitelisted the app. Check on every
        // start and broadcast the result so MainActivity can surface
        // a banner. On the first start where the app is NOT whitelisted,
        // we also kick the system dialog so the user can fix it in one tap.
        try { checkAndPromptBatteryOptimisation() } catch (e: Throwable) {
            ErrorLogger.warn("BotService", "battery-opt check failed: ${e.message}")
        }


        // V5.9.357 â€” start macro pollers (Binance funding 5m, Gemini sentiment
        // 15m, CoinGecko stables 60m). These feed FundingRateAwarenessAI,
        // NewsShockAI and StablecoinFlowAI which were previously voting 0
        // because nothing in the codebase ever called their feeders.
        try { MacroPollers.start(scope) } catch (_: Exception) {}

        // Start data orchestrator (real-time streams)
        addLog("âœ“ Creating data orchestrator...")
        try {
            orchestrator = DataOrchestrator(
                copyTradeEngine    = copyTradeEngine,
                cfg                = { ConfigStore.load(applicationContext) },
                status             = status,
                onLog              = ::addLog,
                onNotify           = { title, body, type -> sendTradeNotif(title, body, type) },
                onNewTokenDetected = { mint, symbol, name ->
                    // V5.9.626 â€” DataOrchestrator/Pump.fun is a first-class
                    // protected intake source. Do NOT save only to config
                    // watchlist; that can leave GlobalTradeRegistry/status.tokens
                    // empty while the UI says Meme Trader has 0 tokens.
                    try {
                        val c = ConfigStore.load(applicationContext)
                        // V5.9.628 â€” DataOrchestrator Pump.fun discoveries belong to Meme
                        // Trader too. If memeTraderEnabled is true, they must hydrate the
                        // protected intake even when auto-add/V3/autoTrade are disabled.
                        // V5.9.632 â€” keep parity with startBot scanner gate + botLoop meme gate
                        // (V5.9.631 added `|| status.running` in those two but missed THIS feed).
                        // If the bot is running and Meme intake is logically active, admit.
                        val shouldAdmit = c.memeTraderEnabled || c.tradingMode == 0 || c.tradingMode == 2 || c.autoAddNewTokens || c.v3EngineEnabled || c.autoTrade || status.running
                        if (shouldAdmit) {
                            // V5.9.1329 â€” ROOT FIX: DATA_ORCHESTRATOR re-emits
                            // existing mints with 0.0 liquidity/mcap defaults,
                            // which then trip QuarantineStore ZERO_LIQUIDITY
                            // for tokens we already have real data on (e.g.
                            // PumpPortal already admitted them at $1968 liq).
                            // Merge from the existing TokenState so the
                            // re-intake doesn't quarantine an active mint.
                            // V5.9.1519 â€” ROOT DE-CHOKE: a DATA_ORCHESTRATOR re-emit of an
                            // already-known mint frequently has NO fresh liquidity reading
                            // (the TokenState may not be hydrated yet, or this is a pure
                            // re-announce). The old code fell back to 0.0 = KNOWN-ZERO, which
                            // tripped QuarantineStore ZERO_LIQUIDITY and parked the entire
                            // intake funnel (oppo=$1639 etc. quarantined seconds after a
                            // healthy PumpPortal admit). Unknown liquidity must be NaN
                            // (= "unknown", quarantine skips the zero check), NOT 0.0. A real
                            // known-zero only comes from a source that actually measured it.
                            val existingTs = status.tokens[mint]
                            val mergedLiq = existingTs?.lastLiquidityUsd?.takeIf { it > 0.0 } ?: Double.NaN
                            val mergedMcap = existingTs?.lastMcap?.takeIf { it > 0.0 } ?: Double.NaN
                            admitProtectedMemeIntake(
                                mint = mint,
                                symbol = symbol,
                                name = name,
                                source = "DATA_ORCHESTRATOR",
                                marketCapUsd = mergedMcap,
                                liquidityUsd = mergedLiq,
                                volumeH1 = 0.0,
                                confidence = 55,
                                allSources = setOf("DATA_ORCHESTRATOR", "PUMP_FUN_NEW"),
                                playSound = true,
                                operatorLog = true,
                                expectedRuntimeGeneration = runtimeGeneration,
                            )
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("BotService", "DataOrchestrator protected intake error: ${e.message}")
                    }
                },
            onDevSell = { mint, pct ->
                val ts = status.tokens[mint]
                if (ts != null && ts.position.isOpen) {
                    val pctInt = (pct * 100).toInt()
                    addLog("ğŸš¨ DEV SELL DETECTED (${pctInt}%) â€” forcing exit", mint)
                    // Hard exit on large dev sells (>20%); urgency signal on smaller ones
                    if (pct >= 0.20) {
                        // Force immediate exit â€” dev dumping is a rug signal
                        scope.launch {
                            val cfg = ConfigStore.load(applicationContext)
                            val effectiveBalance = status.getEffectiveBalance(cfg.paperMode)
                            executor.maybeAct(ts, "EXIT", 0.0, effectiveBalance, wallet,
                                System.currentTimeMillis(), status.openPositionCount,
                                status.totalExposureSol)
                        }
                        sendTradeNotif("ğŸš¨ Dev Selling",
                            "${ts.symbol}: dev sold ${pctInt}% â€” exiting position",
                            NotificationHistory.NotifEntry.NotifType.INFO)
                    } else {
                        // Smaller sell â€” mark as elevated exit urgency via token state
                        synchronized(ts) { ts.lastError = "dev_sell_${pctInt}pct" }
                        addLog("âš ï¸ Dev sold ${pctInt}% â€” watching closely", mint)
                    }
                }
            }
        )
        orchestrator?.start()
        addLog("âœ“ Data orchestrator started")
        } catch (e: Exception) {
            addLog("âš ï¸ Orchestrator error: ${e.message}")
        }

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // V4.0: INITIALIZE GLOBALTRADEREGISTRY BEFORE SCANNER STARTS
        // This ensures all scanner discoveries go through the registry
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        val preScanCfg = ConfigStore.load(applicationContext)
        GlobalTradeRegistry.init(preScanCfg.watchlist, "CONFIG_PRESCAN")
        // V5.2: Set paper mode flags for more aggressive learning
        GlobalTradeRegistry.isPaperMode = preScanCfg.paperMode
        UnifiedModeOrchestrator.isPaperMode = preScanCfg.paperMode
        // V5.2.8: Set EfficiencyLayer paper mode for reduced cooldowns
        EfficiencyLayer.isPaperMode = preScanCfg.paperMode
        // FIX: Propagate mode to FinalExecutionPermit so live trades are not blocked
        FinalExecutionPermit.isPaperMode = preScanCfg.paperMode
        addLog("ğŸ“‹ GlobalTradeRegistry initialized with ${GlobalTradeRegistry.size()} tokens (paperMode=${preScanCfg.paperMode})")

        // V5.9.628 â€” Rehydrate persistent Meme mint memory into the live
        // watchlist/status surfaces before scanner start. MemeMintRegistry is
        // restored in onCreate(), but the trader/UI reads GlobalTradeRegistry +
        // status.tokens. Without this bridge, a restart can show 0 Meme tokens
        // until the scanner rediscovers everything.
        try {
            val restoredMemeMints = com.lifecyclebot.engine.MemeMintRegistry.getAll()
            var hydrated = 0
            // V5.9.682 â€” was: hydrateCap = maxWatchlistSize.coerceAtLeast(500).
            // That forced AT LEAST 500 regardless of config. With 500 historical
            // mints restored, cycle 2 wedged at 33s processing ghost tokens with
            // liq=$0 mcap=$0 (operator dump V5.9.681). Cap is now bounded BY the
            // user's maxWatchlistSize AND a hard ceiling of 80. We also drop
            // tokens with no activity in the last 60 minutes â€” those are
            // stale registry rows that should NOT clog the cold-start watchlist.
            //
            // V5.9.787 â€” operator fix C: 60 minutes was still leaving ~80
            // restored mints crashing through V3 with liq=$0/vol=$0 at every
            // cold boot (snapshot showed MEME_REGISTRY_RESTORE=80 dominating
            // intake source). MemeMintRegistry doesn't store last-known
            // liq/mcap, so admitted restored tokens enter V3 with zero
            // signal until enrichment populates them â€” that's the dominant
            // V3_SKIPPED vol_gate reject reason and the source of the
            // boot-loop perf hit (avg cycle 5.4s). Tightening:
            //   â€¢ cutoff   60min â†’ 10min (only very-recently confirmed mints)
            //   â€¢ cap         80 â†’ 40  (was excessive)
            // Older mints stay in the persistent registry (14-day retention)
            // but are not force-admitted to the watchlist; the scanner
            // re-discovers them organically when fresh activity arrives.
            val nowMs = System.currentTimeMillis()
            // V5.0.4055 â€” live throughput refill. 40 restored mints was safe for the
            // old zero-liq ghost era, but the runtime report showed a tiny cold-start
            // universe (MEME_REGISTRY_RESTORE=40 + scannerâ‰ˆ38 in 3min) while doctrine
            // needs a protected ~500-token active pool. Keep stale protection, but
            // restore a bigger recent slice and source-balance it so PumpPortal/pump.fun
            // does not monopolize the first hot watchlist window.
            val recentCutoffMs = 30 * 60 * 1000L  // was 10min; still far below old 60min ghost window
            val hydrateCap = maxOf(preScanCfg.maxWatchlistSize, 120).coerceAtMost(160)
            val recentCandidates = restoredMemeMints
                .asSequence()
                .filter { it.mint.isNotBlank() }
                .filter { (nowMs - it.lastSeenMs) < recentCutoffMs }
                .sortedWith(compareByDescending<com.lifecyclebot.engine.MemeMintRegistry.MemeMint> { it.sightings }.thenByDescending { it.lastSeenMs })
                .toList()
            val sourceBuckets = recentCandidates
                .groupBy { it.source.ifBlank { "restored" }.uppercase() }
                .mapValues { (_, rows) -> rows.sortedWith(compareByDescending<com.lifecyclebot.engine.MemeMintRegistry.MemeMint> { it.sightings }.thenByDescending { it.lastSeenMs }) }
            val recent = mutableListOf<com.lifecyclebot.engine.MemeMintRegistry.MemeMint>()
            val keys = sourceBuckets.keys.sorted()
            var offset = 0
            while (recent.size < hydrateCap && keys.any { (sourceBuckets[it]?.size ?: 0) > offset }) {
                for (k in keys) {
                    val row = sourceBuckets[k]?.getOrNull(offset) ?: continue
                    recent.add(row)
                    if (recent.size >= hydrateCap) break
                }
                offset++
            }
            for (m in recent) {
                if (m.mint.isBlank()) continue
                val ok = admitProtectedMemeIntake(
                    mint = m.mint,
                    symbol = m.symbol.ifBlank { m.mint.take(6) },
                    name = m.name.ifBlank { m.symbol.ifBlank { m.mint.take(6) } },
                    source = "MEME_REGISTRY_RESTORE",
                    // V5.9.1519 â€” unknown liquidity on a registry restore must be NaN,
                    // not 0.0. Passing 0.0 (known-zero) tripped RESTORE_ZERO_LIQUIDITY
                    // every cycle, re-quarantining restored mints and producing the
                    // "same tokens cycling / nothing trades" symptom. NaN = unknown,
                    // so the scanner re-hydrates real liquidity organically.
                    marketCapUsd = Double.NaN,
                    liquidityUsd = Double.NaN,
                    volumeH1 = 0.0,
                    confidence = 50,
                    allSources = setOf(m.source.ifBlank { "restored" }, "MEME_REGISTRY_RESTORE"),
                    playSound = false,
                    operatorLog = false,
                    expectedRuntimeGeneration = runtimeGeneration,
                )
                if (ok || status.tokens.containsKey(m.mint)) hydrated++
            }
            if (restoredMemeMints.isNotEmpty()) {
                val dropped = restoredMemeMints.size - recent.size
                addLog("ğŸª™ Meme restore: hydrated $hydrated/${recent.size} recent mints (dropped $dropped stale, cap=$hydrateCap)")
                ErrorLogger.info("BotService", "ğŸª™ Meme restore hydrated $hydrated/${recent.size} recent / ${restoredMemeMints.size} total (dropped $dropped stale > 60min)")
                try {
                    ForensicLogger.lifecycle(
                        "MEME_RESTORE_TRIMMED",
                        "hydrated=$hydrated recent=${recent.size} stale=$dropped total=${restoredMemeMints.size} cap=$hydrateCap"
                    )
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            ErrorLogger.warn("BotService", "Meme restore hydrate failed: ${e.message}")
        }

        // V5.0.6282 â€” WATCHLIST BOOT WARMUP hydrate. After MemeMintRegistry
        // restore, re-seed intake with the persisted top-conviction hot list
        // (live-held mints + WinMemory winners + high-touch watchlist rows).
        // This skips the ~90s cold-start starvation window shown in
        // op-report V5.0.6275 where the watchlist was 0 while scanners
        // spun up. Uses the same admitProtectedMemeIntake path so all
        // downstream safety/lane/FDG gates apply unchanged.
        try {
            HotConvictionWarmup.init(applicationContext)
            val hotList = HotConvictionWarmup.getAll()
            var warmed = 0
            for (h in hotList) {
                if (h.mint.isBlank()) continue
                if (status.tokens.containsKey(h.mint)) continue
                val ok = admitProtectedMemeIntake(
                    mint = h.mint,
                    symbol = h.symbol.ifBlank { h.mint.take(6) },
                    name = h.symbol.ifBlank { h.mint.take(6) },
                    source = "HOT_CONVICTION_WARMUP_6282",
                    marketCapUsd = Double.NaN,
                    liquidityUsd = if (h.liquidityUsdEstimate > 0.0) h.liquidityUsdEstimate else Double.NaN,
                    volumeH1 = 0.0,
                    confidence = 55,
                    allSources = setOf(h.source, "HOT_CONVICTION_WARMUP_6282"),
                    playSound = false,
                    operatorLog = false,
                    expectedRuntimeGeneration = runtimeGeneration,
                )
                if (ok || status.tokens.containsKey(h.mint)) warmed++
            }
            if (hotList.isNotEmpty()) {
                addLog("ğŸ©º Hot conviction warmup: seeded $warmed/${hotList.size} top-conviction mints")
                try {
                    ForensicLogger.lifecycle(
                        "HOT_CONVICTION_WARMUP_HYDRATE_6282",
                        "warmed=$warmed total=${hotList.size} note=cold_boot_starvation_bypass"
                    )
                    PipelineHealthCollector.labelInc("HOT_CONVICTION_WARMUP_HYDRATE_6282")
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            ErrorLogger.warn("BotService", "V5.0.6282 hot conviction warmup failed: ${e.message}")
        }

        // Start protected Solana/Meme intake scanner.
        val scanCfg = ConfigStore.load(applicationContext)
        // V5.9.629 â€” SOLANA WIDE-FEED RULE:
        // When Meme Trader is enabled, every available Solana source is allowed
        // to feed protected intake: PumpPortal new launches, DataOrchestrator,
        // Pump.fun REST, DexScreener latest/trending/gainers/boosted, Raydium,
        // GeckoTerminal, Meteora, Birdeye, and CoinGecko. The scanner/watchlist
        // is not an execution gate; downstream FDG/safety/sub-traders qualify.
        // Do not tie feed admission to autoTrade/autoAdd/V3 alone.
        // V5.9.628 â€” Meme scanner is owned by Meme Trader, not by unrelated
        // full-scan/auto-trade/V3 toggles. Latest-APK operator log showed
        // Markets/CryptoAlt/Forex alive while Meme Trader had literally 0 tokens
        // and no scanner-start logs. Root cause: V5.9.625 only fail-opened when
        // one of fullMarketScanEnabled/v3/autoTrade/autoAddNewTokens was true;
        // a valid running bot can have all four false while memeTraderEnabled is
        // still true. Under the protected-intake doctrine, Meme enabled means
        // scanner starts. fullMarketScanEnabled now controls scan breadth only;
        // it must never silently zero the Meme Trader universe.
        val memeModeSelected = scanCfg.tradingMode == 0 || scanCfg.tradingMode == 2  // 0=Meme, 2=Both
        val memeIntakeRequired = scanCfg.memeTraderEnabled ||
            memeModeSelected ||
            scanCfg.fullMarketScanEnabled ||
            scanCfg.v3EngineEnabled ||
            scanCfg.autoTrade ||
            scanCfg.autoAddNewTokens ||
            status.running
        val gateSummary = "meme=${scanCfg.memeTraderEnabled} mode=${scanCfg.tradingMode} fullScan=${scanCfg.fullMarketScanEnabled} " +
            "v3=${scanCfg.v3EngineEnabled} autoTrade=${scanCfg.autoTrade} autoAdd=${scanCfg.autoAddNewTokens}"
        ErrorLogger.info("BotService", "ğŸ›¡ Meme intake gate: $gateSummary -> start=$memeIntakeRequired")
        addLog("ğŸ›¡ Meme intake gate: $gateSummary â†’ ${if (memeIntakeRequired) "START" else "OFF"}")
        if (!scanCfg.fullMarketScanEnabled && memeIntakeRequired) {
            ErrorLogger.warn("BotService", "ğŸ›¡ MEME_INTAKE_FAIL_OPEN: fullMarketScanEnabled=false but Meme/V3/auto intake requires scanner â€” starting Solana scanner anyway")
            addLog("ğŸ›¡ Meme intake fail-open: scanner started despite Full Scan toggle OFF")
        }
        if (memeIntakeRequired) {
            try {
                ErrorLogger.info("BotService", "Creating market scanner...")
                // V5.0.3682 â€” P0 SCANNER GENERATION GUARD (startBot path).
                // Same doctrine as bootMemeScanner: capture the generation at
                // construction time and reject every callback from a superseded
                // scanner. STARTBOT_RUNTIME_COMMITTED below transitions state to
                // RUNNING and is the moment callbacks are formally admitted.
                val startBotScannerGen = runtimeGeneration
                marketScanner = SolanaMarketScanner(
                    cfg          = { ConfigStore.load(applicationContext) },
                    onTokenFound = onTokenFound@{ mint, symbol, name, source, score, liquidityUsd, volumeH1 ->
                        try {
                            // V5.0.3682 â€” generation+state guard at the source.
                            // Drop silently if this scanner is for an old runtime
                            // OR the runtime is not STARTING/RUNNING. Eliminates
                            // post-STOP SCANNER_CALLBACK_FIRE spam at the source.
                            val curGen = com.lifecyclebot.engine.BotRuntimeController.currentGeneration()
                            val rtState = com.lifecyclebot.engine.BotRuntimeController.snapshot().state
                            val admitting = rtState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.RUNNING ||
                                            rtState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.STARTING
                            if (startBotScannerGen != curGen || !admitting || isShuttingDown) {
                                return@onTokenFound
                            }
                            // V5.9.651 â€” forensic visibility for the ORIGINAL
                            // (full-fat) scanner callback path. Critical for
                            // distinguishing whether non-PumpPortal scanner
                            // sources (DexGainers, Boosted, PumpFunTrending,
                            // scanTopVolume, etc.) are firing here vs. the
                            // V5.9.646 simplified bootMemeScanner self-heal
                            // path. Operator can grep "ğŸ§¬[SCAN_CB]" to see
                            // the full source mix in real time.
                            ForensicLogger.phase(
                                ForensicLogger.PHASE.SCAN_CB,
                                symbol,
                                "path=STARTUP src=${source.name} liq=$$liquidityUsd score=$score vol=$$volumeH1"
                            )
                            // V5.0.6548 Â§P0-C â€” background phase progress
                            // beacon. Records that scan-callback is actually
                            // firing under the persistent service runtime
                            // so `BG_SCAN_CB` reflects real activity, not
                            // just service loop ticks.
                            try { markProgress("SCAN_CB") } catch (_: Throwable) {}
                            // V5.9.623 â€” scanner heartbeat means raw discovery, not only
                            // post-filter enqueue. Prevents false "scan stale 9999s" while
                            // the scanner is alive but candidates are returning early.
                            lastScannerDiscoveryMs = System.currentTimeMillis()
                            marketScanner?.recordNewTokenFound()
                            // V5.0.3730 â€” scanner-active source truth.
                            // Runtime reports showed scannerActive=false while SCAN_CB/INTAKE
                            // were firing in the same second. Heartbeat only runs every ~30s;
                            // raw scanner callbacks are the authoritative proof that the live
                            // watchlist feed is alive, so publish active here immediately.
                            try { com.lifecyclebot.engine.BotRuntimeController.markScannerActive(startBotScannerGen, true) } catch (_: Throwable) {}

                            val c = ConfigStore.load(applicationContext)
                            
                            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
                            // V4.0: USE GLOBALTRADEREGISTRY - NOT LOCAL WATCHLIST
                            // This prevents the watchlist reset bug (31â†’1)
                            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
                            
                            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
                            // TRADE IDENTITY: Create canonical identity for this trade
                            // All subsequent tracking uses this identity for consistency
                            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
                            val identity = TradeIdentityManager.getOrCreate(mint, symbol, source.name)
                            
                            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
                            // STAGE 1: DISCOVERED (raw scanner hit)
                            // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
                            TradeLifecycle.discovered(identity.mint, identity.symbol, score, source.name)
                            
                            ErrorLogger.debug("BotService", "DISCOVERED: ${identity.symbol} | liq=$${liquidityUsd.toInt()} | score=$score | src=${source.name}")

                            // V5.9.638 â€” restore pre-1900 arrival semantics.
                            // Known-good builds around 1890/1900 made scanner discoveries visible
                            // to the Meme runtime first, then let downstream gates decide execution.
                            // Later protected-intake rewrites could leave the UI at 0 tokens while
                            // CryptoAlt/Markets stayed alive. A raw scanner hit must hydrate
                            // GlobalTradeRegistry + status.tokens immediately so the Meme trader has
                            // candidates to process even if merge/probation/telemetry layers wobble.
                            // V5.0.4132 â€” DISCIPLINE FILTERS at the fast intake path.
                            // FDG is the smart gate but most live trades come through THIS
                            // path which previously had NO quality filter. Apply BEFORE
                            // admission so the registry never even sees toxic tokens:
                            //   1. RugMintBlacklist veto â€” same mint that rugged us â‰¤24h ago
                            //   2. PatternGoldenGoose catastrophic veto
                            //   3. ScannerLaneBridge.shouldRoute â€” proven-toxic (srcâ†’lane) pair
                            // None of these veto unknown tokens â€” they only block KNOWN bad.
                            val v4132_rug = try { com.lifecyclebot.engine.RugMintBlacklist.isBlacklisted(identity.mint) } catch (_: Throwable) { false }
                            val v4132_gooseCata = try { com.lifecyclebot.engine.PatternGoldenGoose.isCatastrophic(name.ifBlank { identity.symbol }, identity.symbol) } catch (_: Throwable) { false }
                            val v4132_toxicPair = try { !com.lifecyclebot.engine.ScannerLaneBridge.shouldRoute(source.name, "MEME") } catch (_: Throwable) { false }
                            val v4132_veto = v4132_rug || v4132_gooseCata || v4132_toxicPair
                            val v4132_vetoTag = when {
                                v4132_rug         -> "rug_blacklist"
                                v4132_gooseCata   -> "goose_catastrophic"
                                v4132_toxicPair   -> "scanner_lane_pair_toxic"
                                else              -> ""
                            }
                            val immediateAdmitted = if (v4132_veto) {
                                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MEME_DIRECT_INTAKE_VETO_${v4132_vetoTag.uppercase()}") } catch (_: Throwable) {}
                                ErrorLogger.warn("BotService", "ğŸ›‘ MEME_DIRECT_INTAKE_VETO: ${identity.symbol} reason=$v4132_vetoTag mint=${identity.mint.take(10)} src=${source.name}")
                                false
                            } else {
                                admitProtectedMemeIntake(
                                    mint = identity.mint,
                                    symbol = identity.symbol,
                                    name = name.ifBlank { identity.symbol },
                                    source = "SCANNER_DIRECT_${source.name}",
                                    marketCapUsd = liquidityUsd * 10.0,
                                    liquidityUsd = liquidityUsd,
                                    volumeH1 = volumeH1,
     ×<ãNÊ×¬¢h­µçHH
Ë›\İŒÔØÛÜ™HÎˆL
KÑİX›J
KˆŒĞÛÛ™šY[˜ÙHH
Ë›\İŒĞÛÛ™šY[˜ÙHÎˆL
KÑİX›J
Kˆ\ÙHHËœ\ÙKˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
B‚ˆËÈKKŒN8 %”’QÑHQ’TÓÔ–H“ÓÔÕ›Üˆ[ÛÛœÚİ
Y]]™JK‚ˆËÈÚ[ˆH›İ™[ˆÎIHÔˆ\˜Ú]Xİ\™H
Y[YU[šYšYYØÛÜ™\œšYÙJBˆËÈYÜ™Y\Ë[\ÛÛ™šY[˜ÙHH
ÌŒH[™ØÛÜ™HH
ÌËˆ™]™\‚ˆËÈÜ™X]\È[YÚXš[]HH]˜[X]Üˆ™Z™XİY‚ˆYˆ
Ë˜œšYÙPYš\ÛÜPYÜ™Y\È	‰ˆ[ÛÛœÚİØÛÜ™K™[YÚX›JHÂˆ[ÛÛœÚİØÛÜ™HH[ÛÛœÚİØÛÜ™K˜ÛÜJˆØÛÜ™HH
[ÛÛœÚİØÛÜ™KœØÛÜ™H
ÈÊK˜ÛÙ\˜ÙP][Üİ
ML
KˆÛÛ™šY[˜ÙHH
[ÛÛœÚİØÛÜ™K˜ÛÛ™šY[˜ÙH
ÈŒJK˜ÛÙ\˜ÙP][Üİ
KŒ
Bˆ
BˆB‚ˆËÈKKŒNH8 %QSQHQÑHRH›Üˆ[ÛÛœÚİˆØ[YH›İ[™YˆËÈ]\›‹\™XY˜XÚÈÈÔˆÚ^š[™ÈÈİ™XZÈÈÛ\İ\ˆİX\™ˆËÈ\[[™H\ÈÚ]ÛÚ[‹ˆÛÛ\İ\›Ë[ÜÈ˜[\ÈÈŒ	K‚ˆ˜[[ÛÛœÚİ˜\œ˜]]™HHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YS˜\œ˜]]™PRK™]Xİ
ËœŞ[X›Û
K˜Û\İ\‚ˆHØ]Ú
Îˆ^Ù\[ÛŠHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YS˜\œ˜]]™PRKÛ\İ\‹•S’Ó“ÕÓ‚ˆBˆ˜[[ÛÛœÚİÜ[’[Û\İ\ˆHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™Ù]Xİ]™TÜÚ][ÛœÊ
K˜Ûİ[ÈÜÈO‚ˆ[Ø]Ú[™ÈÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YS˜\œ˜]]™PRK™]Xİ
ÜËœŞ[X›Û
K˜Û\İ\ˆOH[ÛÛœÚİ˜\œ˜]]™BˆK™Ù]Ü‘Y˜][
˜[ÙJBˆBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈBˆ˜[[ÛÛœÚİYÙHHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YQYÙPRK™]˜[X]Jˆ^Y\ˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YQYÙPRK“^Y\‹“SÓÓ”ÒÕˆXØ\\ÙHË›\İXØ\ˆÚÙ[YÙSZ[]\ÈHÚÙ[YÙSZ[]\Ëˆ^T˜][ÔİHË›\İ^T™\Üİ\™Tİˆ›Û[YU\ÙHË›\İ\]ZY]U\Ù
ˆŒKˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆÛ\Ûİ[HËš\İÜK›\İÜ“[

OËšÛ\Ûİ[ÎˆˆÜÛ\”İHËÜÛ\”İÎˆËœØY™]KÜÛ\”İZÙRYˆÈ]HHÎˆYˆ
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ó]™J
JHLŒ[ÙHŒŒˆÛ\‘Ü›İİ˜]HHËšÛ\‘Ü›İİ˜]KˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KÑİX›J
Kˆ˜\ÙQ[TØÛÜ™HH[ÛÛœÚİØÛÜ™KœØÛÜ™KÑİX›J
Kˆ˜\œ˜]]™PÛ\İ\ˆH[ÛÛœÚİ˜\œ˜]]™KˆÜ[Û\İ\Ûİ[H[ÛÛœÚİÜ[’[Û\İ\‹ˆ
BˆYˆ
[ÛÛœÚİØÛÜ™K™[YÚX›H	‰‚ˆ
[ÛÛœÚİYÙKœØÛÜ™SYÙHOHŒˆ[ÛÛœÚİYÙK˜ÛÛ™šY[˜ÙSYÙHOHŒˆ[ÛÛœÚİYÙKœÚ^™S][\Y\ˆOHKŒ
JHÂˆ˜[YÙTÚ^™YH
[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛ
ˆ[ÛÛœÚİYÙKœÚ^™S][\Y\ŠBˆ˜ÛÙ\˜ÙP]X\İ
ŒJBˆ[ÛÛœÚİØÛÜ™HH[ÛÛœÚİØÛÜ™K˜ÛÜJˆØÛÜ™HH
[ÛÛœÚİØÛÜ™KœØÛÜ™H
È[ÛÛœÚİYÙKœØÛÜ™SYÙKÒ[

JK˜ÛÙ\˜ÙR[ŠML
KˆÛÛ™šY[˜ÙHH
[ÛÛœÚİØÛÜ™K˜ÛÛ™šY[˜ÙH
È[ÛÛœÚİYÙK˜ÛÛ™šY[˜ÙSYÙHÈLŒ
K˜ÛÙ\˜ÙR[ŠŒKŒ
KˆİYÙÙ\İYÚ^™TÛÛHYÙTÚ^™Yˆ
BˆB‚ˆËÈKKŒMMÍH8 %YÙ[XÈİ[HÚ\[™È›Üˆ[ÛÛœÚİˆØ[YH[™KˆËÈY™™\™[˜YHİ[H\ˆØ[™Y]NˆZXÜ›Ë\Ûš\HÈœ™XZÛİ]ˆËÈ[›™\ˆÈİÚ[™ÈÛÈ[˜XÚÈ™XÛZ[HÈÚ[H›ÛİËˆ\ÂˆËÈÙ\È›İ[İ™HØ]\ÎÈ]Ú[™Ù\ÈHİ˜]YŞH\˜[Y]\œÈBˆËÈ[™H^™\ÜÙ\È›Üˆ\È˜YK^[™[™ÈHØ[\Hİ\™˜XÙK‚ˆHÂˆ˜[İ[HHYÙ[XÔİ[T›İ]\‹™XÚYJË[ÙPÛ\ÜÚYšXØ][Û‹“SÓÓ”ÒÕŠBˆYˆ
[ÛÛœÚİØÛÜ™K™[YÚX›JHÂˆ[ÛÛœÚİØÛÜ™HH[ÛÛœÚİØÛÜ™K˜ÛÜJˆİYÙÙ\İYÚ^™TÛÛH
[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛ
ˆİ[K[™YÚ^™S][
K˜ÛÙ\˜ÙR[ŠŒKŒ
KˆZÙT›Ùš]İH
[ÛÛœÚİØÛÜ™KZÙT›Ùš]İ
ˆİ[K[™Y][
K˜ÛÙ\˜ÙP]X\İ
ŒŒ
Kˆ
Bˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆQÑS•P×ÔÕSWĞTQQ‹ˆ›[™OSSÓÓ”ÒÕŞ[X›ÛIİËœŞ[X›ÛHİ[OIÜİ[Kœİ[K›X™[HÚ^™påÏIÈ‰KŒ™ˆ‹™›Ü›X]
İ[K[™YÚ^™S][
_H0åÏIÈ‰KŒ™ˆ‹™›Ü›X]
İ[K[™Y][
_HÛ0åÏIÈ‰KŒ™ˆ‹™›Ü›X]
İ[K[™YÛ][
_H™X\ÛÛIÜİ[Kœ™X\ÛÛ‹ZÙJLŒ
_H‚ˆ
BˆBˆHØ]Ú
Îˆ›İØX›JHßB‚‚ˆËÈKKH8 %‘SSÕ‘QKKÈÒÔÔ‘R‘PÕ›Üˆ[ÛÛœÚİ‚ˆËÈHš[\ˆ›ØÚÙY^XİHHœ™\Ú[][˜ÚVĞ“ÓÔÕQÂˆËÈVÕ‘S‘S‘È[šY\È[ˆX\›Wİ[šÛ›İÛ‹Ü™WÜ[\\Ù\È]ˆËÈ›ÙXÙYZ[LNMIÜÈL	H[ÛÛœÚİ[›™\œËˆÛÙØÛÜš[™ÂˆËÈ
ŒÈ
È[šYšYYØÛÜ™\ˆ
ÈY]PÛÙÛš][ÛŠH[™XYHÙZYÚÈ\ÙBˆËÈÚYÛ˜[È8 %Y[™ÈH\™ØÛÜ™OŒ›ÛÜˆØ\ÈÚÚÙH™\Üİ\™K‚ˆˆYˆ
[[ÛÛœÚİØÛÜ™K™[YÚX›JHÂˆËÈKKŒˆÙÈ[ÛÛœÚİ™Z™Xİ[ÛœÈ]S‘“ÈÛÈÙHØ[ˆXYÛ›ÜÙHÚ[[˜ÙBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æ ÓSÓÓ”ÒÕH	İËœŞ[X›ÛH‘R‘PÕQ	Û[ÛÛœÚİØÛÜ™Kœ™Z™Xİ™X\ÛÛŸHXØ\IÊË›\İXØ\ÌL
KÒ[

_RÈ\OIİË›\İ\]ZY]U\ÙÒ[

_HœIİË›\İ^T™\Üİ\™TİÒ[

_IHŒÏIİË›\İŒÔØÛÜ™HÎˆ›[ŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
“SÓÓ”ÒÕ‹[ÛÛœÚİØÛÜ™Kœ™Z™Xİ™X\ÛÛŠBˆBˆYˆ
[ÛÛœÚİØÛÜ™K™[YÚX›JHÂˆËÈKŒ‹’Vˆ[œİ\™H[ÛÛœÚİ™]™\ˆ\Ù\È	HÔÓˆ˜[[ÛÛœÚİY™™Xİ]™UİHYˆ
[ÛÛœÚİØÛÜ™KZÙT›Ùš]İHŒ
HLŒ[ÙH[ÛÛœÚİØÛÜ™KZÙT›Ùš]İˆËÈKKŒŒÍNˆ˜[˜XÚÈ›ÛÜˆ˜Z\ÙYÈLMIH
X]Ú\ÈT‘Ñ“ÓÔ—ÔÕÔ
NÈÛ[\[ÛÈ\YYˆ˜[[ÛÛœÚİY™™Xİ]™TÛİH
Yˆ
[ÛÛœÚİØÛÜ™KœİÜÜÜÔİHŒ
HLMKŒ[ÙH[ÛÛœÚİØÛÜ™KœİÜÜÜÔİ
K˜ÛÙ\˜ÙP]X\İ
LMKŒ
BˆˆËÈKKÈ8 %[ˆ‘È‘Q“Ô‘H˜YP]]Üš^™\ˆÛˆ[ÛÛœÚİ‚ˆËÈ[ÛÛœÚİØ\ÈÚÚ\[™È‘È[\™[K[\š[™ÈÛˆÚÙ[œÂˆËÈ]‘ÉÜÈYÙK]™]È[™™YÚ[YHÚXÚÜÈÛİ[]™H›ØÚÙY‚ˆËÈ[ˆÙˆ[ÛÛœÚİ	ÜÈÚ[›™\œÈÙ\™HXİX[H‘ËY›YÙÙY[šY\ÂˆËÈ]\[™YÈÛÜšÈ\Ü]H˜YÛÛ™][ÛœÈ8 %HÛ™\È]ˆËÈY‰İ™XØ[YHY\İÜÜÜÙ\Ë‚ˆ˜[[ÛÛœÚİ™ÑXÚ\Ú[ÛˆHHÂˆ˜[[ÛÛœÚİ[ÙUYÈHHÂˆ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJËœÜÚ][Û‹˜Y[™Ó[ÙJBˆÎˆ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJ“SÓÓ”ÒÕŠBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈ[Bˆš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËˆØ[™Y]HH[™T]X[YšYY^QXÚ\Ú[ÛŠXÚ\Ú[Û‹“SÓÓ”ÒÕ‹ÛÛ™šY[˜ÙQ›ÛÜˆH[ÛÛœÚİØÛÜ™K˜ÛÛ™šY[˜ÙH
ˆLŒ\]ZY]U\ÙHË›\İ\]ZY]U\ÙZ[›Ü”›Ø™HHË›Z[
Kˆ[™TØÛÜ™HH[ÛÛœÚİØÛÜ™K˜ÛÛ™šY[˜ÙKËÈ[™XYHLLˆÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛH[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛˆœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈH[ÛÛœÚİ[ÙUYËˆÜXÚX[\İ[™HH“SÓÓ”ÒÕ‹ˆ
BˆHØ]Ú
™Ñ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'æ ÓSÓÓ”ÒÕH‘È\œ›Üˆ	Ù™Ñ^›Y\ÜØYÙ_H8 %›ØÙYY[™ÈÚ]İ]‘È™]ÈŠBˆ[ËÈ[H›È™]Ë›ØÙYYˆBˆ^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™ÊË›Z[ËœŞ[X›Û“SÓÓ”ÒÕ‹[ÛÛœÚİ™ÑXÚ\Ú[ÛË˜Ø[‘^Xİ]J
HÎˆYK[ÛÛœÚİ™ÑXÚ\Ú[ÛË˜›ØÚÔ™X\ÛÛ‹ÚYÛ˜[H•VH‹YÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KØY™]UY\ˆHËœØY™]KY\‹›˜[YK\]ZY]U\ÙHË›\İ\]ZY]U\Ù\™›Ô™X\ÛÛœÈHËœØY™]Kš\™›ØÚÔ™X\ÛÛœË[TØÛÜ™HHË™[TØÛÜ™KÒ[

KÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJKœ›İ]Tİ]\ËÚÙ[“X\Y˜][ÛÛÛ\]HHËÚÙ[“X\šY˜][ÛÛÛ\]KÚÙ[“X\^XİYİ]HËÚÙ[“X\™^XİYİ][[İ[ÚÙ[“X\›İšY\][\ÈHËÚÙ[“X\œ›İšY\][\ÊB‚ˆËÈKKLH8 %‘È\ÈSÑSUÔˆ›İÒSTˆ›ÜˆİX‹]˜Y\œË‚ˆËÈ\œ]X[[X\›š[™È\˜Ú]Xİ\™Nˆ‘ÈY\İÈÚ^™HÚ[ˆ]\ØYÜ™Y\ËˆËÈ]™]™\ˆ\™]™]Ù\ÈH]X[YšYY[ÛÛœÚİÚYÛ˜[ˆÛ›HİXİ\˜[ˆËÈ\™›ØÚÜÈ
\]ZY]HÛÛ\ÙKÛÛ™š\›YYYÏLLÛÜK]˜YJHİÜˆËÈH˜YKˆ]™\][™È[ÙH8¡¤ˆ›Ø™HÚ^™HÛÈHRHX\›œË‚ˆ˜[™Ò\ÔİXİ\˜[›ØÚÈH[ÛÛœÚİ™ÑXÚ\Ú[ÛˆOH[	‰‚ˆ[[ÛÛœÚİ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
H	‰‚ˆ[ÛÛœÚİ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛË›]Âˆ]˜ÛÛZ[œÊ“TURQUHŠH]˜ÛÛZ[œÊ“SÔ•Q×Ô“ĞP’SUHŠHˆ]˜ÛÛZ[œÊÓÔWÕQHŠH]˜ÛÛZ[œÊ‘SQT‘ÑSÖWÔÕÔŠBˆHOHYBˆ˜[™Ô™YXÙYÚ^™HHYˆ
[ÛÛœÚİ™ÑXÚ\Ú[ÛˆOH[	‰ˆ[[ÛÛœÚİ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
H	‰ˆY™Ò\ÔİXİ\˜[›ØÚÊHÂˆËÈ‘È\ØYÜ™Y\È]›İİXİ\˜[8 %[™HHÚ^™H›ÜˆX\›š[™Âˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨;î#È‘ÈÒV‘KT‘QPÑHÛˆSÓÓ”ÒÕˆ	İËœŞ[X›ÛH	Û[ÛÛœÚİ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ™™×ØØ]][ÛˆŸH˜Y[™È›Ø™HÚ^™HŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
“SÓÓ”ÒÕÑ‘×Ô“Ğ‘H‹[ÛÛœÚİ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ™™×ØØ]][ÛˆŠBˆYBˆH[ÙH˜[ÙBˆYˆ
™Ò\ÔİXİ\˜[›ØÚÊHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘ÈÕ•PÕTS“ĞÒÈÛˆSÓÓ”ÒÕˆ	İËœŞ[X›ÛH	Û[ÛÛœÚİ™ÑXÚ\Ú[ÛË˜›ØÚÔ™X\ÛÛˆÎˆ››È™X\ÛÛˆŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
“SÓÓ”ÒÕÑ‘È‹[ÛÛœÚİ™ÑXÚ\Ú[ÛË˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŠBˆH[ÙHÂ‚ˆËÈ™\ÛÛ™H[™ÙX[H^Xİ[ÛÛœÚİÚ^™H™Y›Ü™BˆËÈ]]Üš^˜][ÛÈİÛœİ™X[H^Xİ][Ûˆ™]\Ù\È]‚ˆ˜[Û\ĞØ[][HHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ØÛÜ™Q^Xİ[˜ŞU˜XÚÙ\‹˜Ø[Xœ˜][Û”Ú^™S][
“SÓÓ”ÒÕ‹[ÛÛœÚİØÛÜ™KœØÛÜ™JBˆHØ]Ú
Îˆ›İØX›JHÈKŒBˆ˜[YØXŞS[ÛÛœÚİÚ^™HH

Yˆ
™Ô™YXÙYÚ^™JBˆ
[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛ
ˆJK˜ÛÙ\˜ÙP]X\İ
Ù™ËœÛX[^TÛÛ
Bˆ[ÙH[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛ
H
ˆÛ\ĞØ[][
K˜ÛÙ\˜ÙP]X\İ
ŒJBˆ˜[\ÑY™™Xİ]™TÚ^™HH[ÛÛœÚİ™ÑXÚ\Ú[ÛËœÚ^™TÛÛˆÎˆYØXŞS[ÛÛœÚİÚ^™K˜ÛÙ\˜ÙR[ŠŒK[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛ˜ÛÙ\˜ÙP]X\İ
ŒJJBˆËÈKŒˆ]]Üš^™H›İYÚ˜YP]]Üš^™\‚ˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØÛÜ™HH[ÛÛœÚİØÛÜ™KœØÛÜ™KˆÛÛ™šY[˜ÙHH[ÛÛœÚİØÛÜ™K˜ÛÛ™šY[˜ÙKˆ]X[]HH[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™\Ü^S˜[YKˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË“SÓÓ”ÒÕˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™Kˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ™T™\ÛÛ™YÚ^™TÛÛH\ÑY™™Xİ]™TÚ^™Kˆ
BˆˆYˆ
X]]™\İ[š\Ñ^Xİ]X›J
JHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'æ ÓSÓÓ”ÒÕH	İËœŞ[X›ÛHUUÑS’QQ	Ø]]™\İ[œ™X\ÛÛŸHŠBˆH[ÙHÂˆ˜[[ÛÛœÚİ][\YH]]™\İ[˜][\YˆËÈXÜ]Z\™Hš[˜[^Xİ][Ûˆ\›Z]ˆËÈKKLH8 %\H‘È›Ø™H™YXİ[ÛˆYˆ‘È\ØYÜ™YYˆËÈKKŒMMÍH8 %Ø™^H‘Èš[˜[Ú^™H^XİHÚ[ˆ™\Ù[‚ˆËÈ[ÛÛœÚİ™]š[İ\ÛH›ÜÜÙYHØ[Xœ˜]YÚ^™HÈ‘ËˆËÈ[ˆ™XÛÛ\]Y]ÈİÛˆÚ^™HİÛœİ™X[H[™™YÚ\İ\™YˆËÈÜÚ][ÛœÈ\Ú[™ÈH˜]ÈİYÙÙ\İYÚ^™TÛÛˆ]™]™[YˆËÈH[™K\ÛXŞKÜİ[Hİ\™˜XÙHœ›ÛH™Z[™È™Y›XİY[‚ˆËÈXİX[^Üİ\™Kˆ˜[˜XÚÈ™\Ù\™\ÈYØXŞH™Z]š[İ\ˆY‚ˆËÈ‘È\œ›ÜœË‚ˆËÈKŒLÈ8 %Yˆ‘È^\İËØ™^H]Èš[˜[Ú^™H^XİK‚ˆËÈLˆØ[ˆ™\İÜ™HPUHÛÜ™H]™HÚ^™HY\ˆÛZXÜ›ËÜ›Ø™BˆËÈÙ[X[XÜÈÛÛ\ÙH]™[İÈÛÜ™NÈ\ÈİÛœİ™X[H[™HØ\ˆËÈ]\İ›İÚ[[HÛ[\]˜XÚÈÈ˜]ÈİYÙÙ\İYÚ^™TÛÛ‚ˆYˆ
š[˜[^Xİ][Û”\›Z]PXÜ]Z\™Q^Xİ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ^Y\ˆH“SÓÓ”ÒÕ‹ˆÚ^™TÛÛH\ÑY™™Xİ]™TÚ^™Kˆ][\YH[ÛÛœÚİ][\Yˆš[˜[]T™XÚXÚÙYHYKˆ\\“[ÙHHÙ™Ëœ\\“[ÙKˆYÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™Kˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆØY™]UY\ˆHËœØY™]KY\‹›˜[YKˆ\İØY™]PÚXÚÓ\ÈHË›\İØY™]PÚXÚËˆ
JHÂˆHÂˆ˜[ÛÛXİ]™SX™[HYˆ
[ÛÛœÚİØÛÜ™Kš\ĞÛÛXİ]™P›ÛÜİ
HˆĞÓÓPÕU‘WHˆ[ÙHˆ‚ˆ˜[›Ø™S›HYˆ
™Ô™YXÙYÚ^™JHˆÑ‘×Ô“Ğ‘WHˆ[ÙHˆ‚ˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æ ÓSÓÓ”ÒÕH	İËœŞ[X›ÛHS•–IÛÛXİ]™SX™[	›Ø™S›ˆ
Âˆ‰Û[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™[[Úš_H	Û[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™\Ü^S˜[Y_Hˆ
ÂˆœØÛÜ™OIÛ[ÛÛœÚİØÛÜ™KœØÛÜ™_HÛÛ™IÛ[ÛÛœÚİØÛÜ™K˜ÛÛ™šY[˜ÙKÒ[

_IHˆ
Âˆ›XØ\W		ÊË›\İXØ\ÌL
KÒ[

_RÈˆ
Âˆ•IÛ[ÛÛœÚİY™™Xİ]™UİÒ[

_IHÓIÛ[ÛÛœÚİY™™Xİ]™TÛİÒ[

_IHŠBˆˆËÈ^Xİ]H[ÛÛœÚİ[H8 %]™H[ÙH[œÈ™X[Û‹XÚZ[ˆİØ\ˆ˜[[ÛÛœÚİÜ[™YH^Xİ]Ü‹›[ÛÛœÚİ^JˆÈHËˆÚ^™TÛÛH\ÑY™™Xİ]™TÚ^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆØ[]HØ[]ˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™ÂˆØÛÜ™HH[ÛÛœÚİØÛÜ™KœØÛÜ™KÑİX›J
KˆÜXÙS[ÙQ[[ÚšHH[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™[[ÚšKˆÜXÙS[ÙS˜[YHH[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™\Ü^S˜[YKˆš[˜[]T™XÚXÚÙYHYKˆ][\YH[ÛÛœÚİ][\Yˆ
BˆYˆ
[[ÛÛœÚİÜ[™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹“SÓÓ”ÒÕ	İËœŞ[X›ÛH•VWÓ“ÕÓÔS‘Q™[X\ÙH]]Ü\›Z]È›È[™H™YÚ\İ˜][ÛˆŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ“S‘WĞ•VWÓ“ÕÓÔS‘QÔ‘SPTÑQ‹›[™OSSÓÓ”ÒÕŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[“SÓÓ”ÒÕ‹•VWÓ“ÕÓÔS‘QŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
HHØ]Ú
Îˆ›İØX›JHßBˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•VWÓ“ÕÓÔS‘Q‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË“SÓÓ”ÒÕ
HHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚ˆËÈKKŒÌ’Vˆ\ÙH]UÚÙ[ˆˆ[™[™Õ™\šYH
›İ\İ\ÓÜ[ŠK‚ˆËÈ›Üˆ]™H^\Ë]™P^J
HX]™\È[™[™Õ™\šYO]YHXZÚ[™È\ÓÜ[Y˜[ÙK‚ˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYJHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜YÜÚ][ÛŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK“[ÛÛœÚİÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHËœ™Y‹ˆ[TÛÛH\ÑY™™Xİ]™TÚ^™Kˆ[U[YHHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KˆZÙT›Ùš]İH[ÛÛœÚİY™™Xİ]™UİËÈKŒ‹ˆ\ÙHY™™Xİ]™HˆİÜÜÜÔİH[ÛÛœÚİY™™Xİ]™TÛİËÈKŒ‹ˆ\ÙHY™™Xİ]™HÓˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ[TØÛÜ™HH[ÛÛœÚİØÛÜ™KœØÛÜ™KÑİX›J
KˆÜXÙS[ÙHH[ÛÛœÚİØÛÜ™KœÜXÙS[ÙKˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ\ĞÛÛXİ]™UÚ[›™\ˆH[ÛÛœÚİØÛÜ™Kš\ĞÛÛXİ]™P›ÛÜİˆËÈKKŒN8 %Ø\\™H™X[[HÛÛ^›ÜˆY\]™SX\›š[™Ñ[™Ú[™Bˆ[P^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ[PYÙSZ[]\ÈH

Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË˜YYÕØ]Ú\İ]
HÈŒÌŒ
K˜ÛÙ\˜ÙP]X\İ
Œ
Kˆ[RÛ\Ûİ[HËš\İÜK›\İÜ“[

OËšÛ\Ûİ[Îˆˆ[UÜÛ\”İHËÜÛ\”İÎˆËœØY™]KÜÛ\”İZÙRYˆÈ]HHÎˆŒˆ[TYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KÑİX›J
Kˆ[RÛ\‘Ü›İİ˜]HHËšÛ\‘Ü›İİ˜]Kˆ[U›Û[YU\ÙHË›\İ\]ZY]U\Ù
ˆŒKˆ
Bˆ
BˆˆËÈK‹’Vˆ›İYHŒÈ^Üİ\™HİX\™ÂˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYHËœÜÚ][Û‹š\ÓÜ[ŠHÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][Û“Ü[™Y
Ë›Z[
BˆˆËÈ™YÚ\İ\ˆÚ]^Y\•˜[œÚ][Û“X[˜YÙ\ˆ›Üˆ˜XÚÚ[™ÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹œ™YÚ\İ\”ÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ^Y\ˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹•˜Y[™Ó^Y\‹“SÓÓ”ÒÕˆ[SXØ\HË›\İXØ\ˆ[TšXÙHHËœ™Y‹ˆ
BˆˆËœÜÚ][Û‹˜Y[™Ó[ÙHH“SÓÓ”ÒÕÉÛ[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK›˜[Y_H‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™[[ÚšBˆˆYÙÊ‰Û[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™[[Úš_HSÓÓ”ÒÕ•VIÛÛXİ]™SX™[ˆ	İËœŞ[X›ÛHˆ
Âˆ‰Û[ÛÛœÚİØÛÜ™KœÜXÙS[ÙK™\Ü^S˜[Y_Hˆ
Âˆ—		ÊË›\İXØ\ÌWÌ
KÒ[

_RÈXØ\ˆ
ÂˆœØÛÜ™OIÛ[ÛÛœÚİØÛÜ™KœØÛÜ™_Hˆ
Âˆ‰Û\ÑY™™Xİ]™TÚ^™K™›]
Ê_HÓÓ
˜]ÏIÛ[ÛÛœÚİØÛÜ™KœİYÙÙ\İYÚ^™TÛÛ™›]
Ê_JHˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
BˆˆHš[˜[HÂˆš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
BˆBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'æ ÓSÓÓ”ÒÕH	İËœŞ[X›ÛHVPÕUSÓ—Ğ“ĞÒÑQ[›İ\ˆ^Y\ˆ^Xİ][™ÈŠBˆBˆBˆBˆBˆBˆHËÈÛÜÙH‘Ë\™\]Z\™Y[ÙH›ØÚÈ
[ÛÛœÚİKKÊBˆHØ]Ú
[ÛÛ‘^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'æ ÓSÓÓ”ÒÕH	İËœŞ[X›ÛHT”“Ôˆ	Û[ÛÛ‘^›Y\ÜØYÙ_HŠBˆš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
BˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘[ÛÛœÚİ]˜[X][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈÒUÓÒSˆQTˆHYÙ[ˆ^\È›Üˆ	ÌÈX\šÙ]Ø\ÚÙ[œÂˆËÈKŒˆ’Vˆ\]YXØ\˜[™ÙHÈ]›ÚYİ™\›\Ú][ÛÛœÚİˆËÈ[œÈÓÓÕT”‘S•HÚ]ŒË™X\İ\K[™›YHÚ\ˆËÈ\™Ù]È[\™[‹˜^Y][K[ÛÛœÚİœ™\Ú][˜Ú\ÂˆËÈŒ’Vˆ]\İÚXÚÈš[˜[^Xİ][Û”\›Z]™Y›Ü™H^Xİ][™ÂˆËÈKŒˆ’Vˆ]\İÚXÚÈYˆ™X\İ\H[™XYH\ÈHÜÚ][ÛˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
]ËœÜÚ][Û‹š\ÓÜ[ˆ	‰ˆÚİ[[^S[™Q›ÜŞXÛJË”ÒUÓÒSˆ‹ŞXÛTš[X\S[™JH	‰ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RKš\Ñ[˜X›Y

JHÂˆËÈKKLH8 %›Ü™[œÚXÈS‘WÑUSX\šÙ\ˆÛÈÜ\˜]ÜˆØ[ˆÜ™\ˆËÈ¼'éëÓS‘WÑUSHˆ[™[œİ[HÙYHÚ]\ˆÚ]ÛÚ[ˆ]™[‚ˆËÈ[\™Y]˜[X][Ûˆ›ÜˆXXÚØ]Ú\İÚÙ[‹‚ˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ›[™OTÒUÓÒSˆ\\IØÙ™Ëœ\\“[Ù_HŒÔ™XYOIİHÈÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹š\Ô™XYJ
HHØ]Ú
Îˆ›İØX›JHÈ˜[ÙH_H\OI	İË›\İ\]ZY]U\ÙÒ[

_HØÛÜ™OIİË™[TØÛÜ™_H‚ˆ
BˆËÈKKH8 %ŒÈ\È›İÈHY[YH]]Üš]KˆYˆŒÈ\È[˜X›YˆËÈH\˜[[Ú]ÛÚ[ˆ^Xİ][Ûˆ]\Èİ\™\ÜÙY8 %ŒÈ
ÂˆËÈ‘ÈXÚYHH[H[™^Xİ]Ü‹ŒĞ^H\™›Ü›\È]Ú]ˆËÈHÚ]ÛÚ[ˆİX‹]˜Y\‰ÜÈÚYÛ˜[[™XYH›[™Y[ÈŒÂˆËÈØÛÜš[™È\İ™X[KˆHÚ]ÛÚ[ˆ›ØÚÈ™[XZ[œÈXİ]™HÛ›BˆËÈ\ÈHSPÒÈÚ[ˆŒÈ\È\ØX›YÈ›İ™XYK™\Ù\š[™ÂˆËÈ˜XÚİØ\™ÛÛ\]Xš[]K‚ˆËÈKKL8 %Ü\˜]Üˆİ™\œšYNˆ[ˆTTˆ[ÙK[ˆÚ]ÛÚ[ˆ[‚ˆËÈTSSÚ]ŒÈ[œİXYÙˆ]][™È]ˆKKHXYHŒÈBˆËÈÛÛHY[YH]]Üš]K]Ü\˜]Ü‰ÜÈKKH]šXÙHÚİÙYŒÂˆËÈ™]\›š[™ÈĞUÒÔ‘R‘PÕQ›ÜˆSÌ
ÈØ]Ú\İØ[™Y]\ÂˆËÈ
İÈ\İÜšXØ[\\ˆÔˆÉK‘È›ÛÜLMIJKˆÚ]Ú]ÛÚ[‚ˆËÈ]]YHY[YH˜Y\ˆÛÚÈ‘T“È˜Y\ÈÚ[HÜ\Ğ[	ÜÂˆËÈ•STš\™Y[[YYX][H8 %›İš[™ÈH^Xİ]ÜˆÛÜšÜÈ]BˆËÈŒË[Û›HY[YHØ]H\ÈÛÈYÚˆ[ˆ\\ˆ[ÙK\˜[[ˆËÈÚ]ÛÚ[ˆÚ]™\ÈH›İPT“’S‘È^Üİ\™HÛˆHY[YHİ™X[BˆËÈ[™]È\ÈÙYHXİX[™Z™Xİ[Ûˆ™X\ÛÛœÈ[ˆHÙË‚ˆËÈU‘H[™\\ˆ›İ™]Z[ˆH\™Xİ[™NÈŒÈÛÛšX]\ÂˆËÈØÛÜš[™ËÜØY™]H]Ù\È›İ™\XÙH\È^Xİ]Üˆ]]Üš]K‚ˆ˜[ŒÔ™XYQ›Ü“Y[YTÜ[™HHHÂˆXÙ™Ëœ\\“[ÙH	‰ˆÙ™ËŒÑ[™Ú[™Q[˜X›Y	‰ˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹š\Ô™XYJ
BˆHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆËÈKŒŒÌ8 %]™KÜ\\ˆ\š]NˆŒÈ™XY[™\ÜÈ\È“ÕİÛ™\œÚ\ÙˆBˆËÈ\™XİÚ]ÛÚ[ˆ^Xİ]Üˆ[™Kˆ]Ú]ÛÚ[ˆ[ˆ›İYÚ]ÈİÛˆ‘È
ÂˆËÈ]]Üš^™\ˆ][ˆ]™HÛÎÈYHŒÈ˜][ØY™]Hİ[\›Z[˜]\ÈBˆËÈ[™H™[İËˆ\È™[[İ™\ÈHY[ˆ]™K]›Û[YHÚÚÙHÚ\™HŒÈĞUÒÂˆËÈ›İ][™È™Z™XİÈYX[Ú]ÛÚ[ˆ™]™\ˆ™XXÚY^Xİ]X›K[Ü[‹‚ˆYˆ
ŒÔ™XYQ›Ü“Y[YTÜ[™JHÂˆHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê”ÒUÓÒS—ÓU‘WÕŒ×ÔTSSÑSPÒ×ÍŒÌŠHHØ]Ú
Îˆ›İØX›JHßBˆYˆ
ËœÜÚ][Û‹˜Y[™Ó[ÙKš\Ğ›[šÊ
JHËœÜÚ][Û‹˜Y[™Ó[ÙHH”ÒUÓÒSˆ‚ˆBˆËÈKËˆÚ]ÛÚ[ˆ[œÈ[™\[™[H8 %™X\İ\HÜÚ][ÛœÈÛ‰İ›ØÚÈ]ˆ[ˆ™[X\ÙTÚ]ÛÚ[][\ŒÌ
™X\ÛÛˆİš[™Ë™[X\ÙT\›Z]ˆ›ÛÛX[ˆHYK™[X\ÙP]]ˆ›ÛÛX[ˆHYJHÂˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[”ÒUÓÒSˆ‹™X\ÛÛŠHHØ]Ú
Îˆ›İØX›JHßBˆYˆ
™[X\ÙT\›Z]
HHÈš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
HHØ]Ú
Îˆ›İØX›JHßBˆYˆ
™[X\ÙP]]
HHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[™X\ÛÛ‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË”ÒUÓÒSŠHHØ]Ú
Îˆ›İØX›JHßBˆBˆHÂˆËÈŒ’VˆÚXÚÈ^Xİ][Ûˆ\›Z]š\œİˆ˜[\›Z]™\İ[Hš[˜[^Xİ][Û”\›Z]˜Ø[‘^Xİ]JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ™\]Y\İ[™Ó^Y\ˆH”ÒUÓÒSˆ‹ˆ\ÓÜ[”ÜÚ][ÛˆHËœÜÚ][Û‹š\ÓÜ[‚ˆ
BˆˆYˆ
\\›Z]™\İ[˜[İÙY
HÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛH“ĞÒÑQ	Ü\›Z]™\İ[œ™X\ÛÛŸHŠBˆËÈKKÍÈ8 %İ\™˜XÙHHÚ[[S‘WÑUS8¡¤ˆ‘È›Ü‚ˆËÈÜ\˜]Ü‰ÜÈKKÍˆ[\ÚİÙYMS‘WÑUS\ÜÙ\ÂˆËÈ[™‘È]˜[X][ÛœÈ8 %YX[š[™È]™\HÚÙ[ˆØ\ÂˆËÈÚ[[H™Z™XİYÛÛY]Ú\™H™]ÙY[ˆ[™H[H[™ˆËÈHš[˜[XÚ\Ú[Û‘Ø]HØ[ˆHš[˜[^Xİ][Û”\›Z]ˆËÈØ\ÈHš[YHİ\ÜXİ™XØ]\ÙH]È™Z™Xİ[ÛœÈÛ›H]ˆËÈ\œ›Ü“ÙÙÙ\‹™XYÈ
Ù™ˆ[ˆ™[X\ÙHZ[ÊKÛÈBˆËÈÜ\˜]Üˆ™]™\ˆØ]ÈH™X\ÛÛ‹ˆ›İÈÙH[Z]H™X[ˆËÈ›Ü™[œÚXÈØ]H]™[Ú]H\›Z]™X\ÛÛˆ™\˜˜][K‚ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]Jˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ[İÈH˜[ÙKˆ™X\ÛÛˆH”T“RUÑS–N‰Ü\›Z]™\İ[œ™X\ÛÛŸH[™OTÒUÓÒSˆ‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆH[ÙHÂˆËÈØ[İ[]HÚÙ[ˆYÙBˆ˜[ÚÙ[YÙSZ[]\ÈHYˆ
Ë˜YYÕØ]Ú\İ]ˆ
HÂˆ
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË˜YYÕØ]Ú\İ]
HÈŒÌŒˆH[ÙHÂˆLŒŒËÈY˜][ˆİ\œÈYˆ[šÛ›İÛ‚ˆBˆˆËÈÚXÚÈYˆ\È]X[YšY\È\ÈHÚ]ÛÚ[ˆØ[™Y]BˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RKš\ÔÚ]ÛÚ[Ø[™Y]JˆX\šÙ]Ø\\ÙHË›\İXØ\ˆÚÙ[YÙSZ[]\ÈHÚÙ[YÙSZ[]\Âˆ
JHÂˆËÈ]Xİ][˜Ú]›Ü›Hœ›ÛHÛİ\˜ÙBˆ˜[][˜Ú]›Ü›HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™]Xİ]›Ü›JËœÛİ\˜ÙJBˆˆËÈ^˜XİÛ\ˆ[™[™H[™›Èœ›ÛHØY™]H]BˆËÈ\ÙHš\œİ›ØÚÔİ\Tİ\È›ŞH›Üˆ]‹Ø[™HÛ[™Âˆ˜[]’ÛİHËœØY™]K™š\œİ›ØÚÔİ\TİZÙRYˆÈ]HHÎˆLŒˆ˜[[™TİHYˆ
ËœØY™]K˜[™Tš\ÚÈOH’QÒŠHŒˆ[ÙHYˆ
ËœØY™]K˜[™Tš\ÚÈOH“QQUSHŠHLŒˆ[ÙHLŒˆˆËÈØ[İ[]HÛØÚX[ØÛÜ™Hœ›ÛH]˜Z[X›HÚYÛ˜[Âˆ˜[ÛØÚX[ØÛÜ™HHØ[İ[]TÛØÚX[ØÛÜ™JÊBˆˆËÈÚXÚÈ›ÜˆV›ÛÜİİ™[™[™Âˆ˜[\Ñ^›ÛÜİYHËœÛİ\˜ÙK˜ÛÛZ[œÊ“ÓÔÕQ‹YÛ›Ü™PØ\ÙHHYJBˆ˜[^™[™[™Ô˜[šÈHYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ•‘S‘S‘È‹YÛ›Ü™PØ\ÙHHYJJHH[ÙHˆˆËÈÚXÚÈ›ÜˆÛÜXØ]ÜØØ[H]\›œÂˆ˜[\ĞÛÜPØ]H]XİÛÜPØ]
ËœŞ[X›Û
BˆˆËÈØ[İ[]HÜ˜YX][Ûˆ›ÙÜ™\ÜÈ›Üˆ[\™[ˆÚÙ[œÂˆ˜[Ü˜YX][Û”›ÙÜ™\ÜÈHYˆ
][˜Ú]›Ü›HOHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK“][˜Ú]›Ü›K”STÑ•SŠHÂˆËÈÜ˜YX][Ûˆ\[œÈ\›İ[™	RÈXØ\Ûˆ[\™[‚ˆ

Ë›\İXØ\ÈWÌŒ
H
ˆL
K˜ÛÙ\˜ÙP][Üİ
LŒ
BˆH[ÙHŒˆˆËÈKKŒLÎˆÓÓTÑHÕPT‘8 %ÚÚ\Ú]ÛÚ[ˆ[Z]Ûˆ˜Z[š[™Ë[\]ZY]HÚÙ[œÂˆËÈ\Ù\È\]ZY]Q\RIÜÈ^\İ[™È™[™[˜[\Ú\È
Ø[YH[™Ú[™H]ˆËÈ[™XYHÙÜÈ	ü'ä©ÈˆÓÓTÑH‹‹‰ÊKˆYˆ™[™\ÈÓÓTÑHÜˆRS‚ˆËÈÚ]\ÓÔ‹ÑS‘ÑT“ÕTËÙHÚÚ\H]˜[X]H[\™[H˜]\‚ˆËÈ[ˆ™[Z[™ÈÛˆŒÈÈ™]ÈY\ˆÚ]ÛÚ[ˆ[™XYH[Z]ÈUPSQ’QQ‚ˆ˜[\PÛÛ\ÙQ]XİYHHÂˆ˜[™[™HÛÛK›Y™XŞXÛX›İ™[™Ú[™K“\]ZY]Q\RK˜[˜[^™U™[™
Ë›Z[
Bˆ™[™™[™OHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“\]ZY]Q\RK•™[™ÓÓTÑHˆ
™[™™[™OHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“\]ZY]Q\RK•™[™‘RSˆ	‰‚ˆ
™[™™\]X[]HOHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“\]ZY]Q\RK‘\]X[]K”ÓÔˆˆ™[™™\]X[]HOHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“\]ZY]Q\RK‘\]X[]K‘S‘ÑT“ÕTÊJBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈ˜[ÙHBˆYˆ
\PÛÛ\ÙQ]XİY
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHÒÒTÑUSTURQUWĞÓÓTÑH]XİY8 %›İØY™HÈ[\ˆ‚ˆ
BˆH[ÙHÂ‚ˆËÈKKÎLˆ8 %Ü\˜]Üˆ]Y]][HˆŒÈ˜][]\İ\›Z[˜]H[™H›İË‚ˆËÈÚ\İHŒÈ›ØÚÑ˜][È™Z™XİYÈ›ØÚÙYÚXÚÈP“Õ‘HBˆËÈÚ]ÛÚ[ˆ]˜[X]HÛÈHYËXÜš]XØ[ÚÙ[ˆ™]™\ˆ]™[ˆ[œÂˆËÈ›İYÚHY[YH]X[YšXØ][Û‹ˆÚ]İ]\ÈİX\™Ú]ÛÚ[‚ˆËÈ[Z]ÈHUPSQ’QQÚYÛ˜[]Y\]™SX\›š[™Ñ[™Ú[™H[™BˆËÈİ˜]YŞH\İÙÈ™X]\ÈH›Z\ÜÙY[Hˆ8 %Û][™ÈBˆËÈ˜[ÙK\ÜÚ]]™H™YYˆ›İ]HH™Z™Xİ[ÛˆÈ‘R‘PÕQÑUSÕŒÂˆËÈ[™ÚÚ\[™H˜Z[š[™È[\™[H
™Z™XİYY™X]\™H[[Y]BˆËÈØ[ˆİ[[Z]İÛœİ™X[JK‚ˆ˜[ŒÒ\Ô\\“[ÙSØØ[HÙ™Ëœ\\“[ÙBˆ˜[ŒÑ˜][™X\ÛÛˆH
ŒÑXÚ\Ú[Ûˆ\ÏÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÑ˜][
OËœ™X\ÛÛˆÎˆˆ‚ˆËÈKKŒL8 %[ÛÈ^˜Xİ™X\ÛÛˆœ›ÛH™Z™XİYˆËÈÛ\ÜÈ
ŒÑXÚ\Ú[Û‹”™Z™XİY\È]ÈİÛ‚ˆËÈ™X\ÛÛˆİš[™ØšY[
KˆÜ\˜]ÜˆKKŒLÂˆËÈ[\ÚİÙYL]™[ÈXÚÙ]Y\È˜\™BˆËÈ”™Z™XİYˆ[ˆHKKŒLˆ\İÙÜ˜[H™XØ]\ÙBˆËÈHY™XŞXÛH]™[Û›HÙÙÙY™X\ÛÛH›Ü‚ˆËÈ›ØÚÑ˜][È™Z™XİYœ™X\ÛÛˆØ\ÈÚ[[BˆËÈ›ÜYˆ[œ›ÛHÚXÚ]™\ˆÛ\ÜÈØ\œšY\È]‚ˆ˜[ŒÔ™Z™XİY™X\ÛÛˆH
ŒÑXÚ\Ú[Ûˆ\ÏÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹”™Z™XİY
OËœ™X\ÛÛˆÎˆˆ‚ˆ˜[ŒÓÙÙÙY™X\ÛÛˆHÚ[ˆÂˆŒÑ˜][™X\ÛÛ‹š\Ó›İ›[šÊ
HOˆŒÑ˜][™X\ÛÛ‚ˆŒÔ™Z™XİY™X\ÛÛ‹š\Ó›İ›[šÊ
HOˆŒÔ™Z™XİY™X\ÛÛ‚ˆ[ÙHOˆŠ›Û™JH‚ˆBˆ[ˆŒÔ›İ][™Ô™Z™Xİ›Ü”Ú]ÛÚ[ŒÌÊ™X\ÛÛˆİš[™ÊNˆ›ÛÛX[ˆBˆ™X\ÛÛ‹˜ÛÛZ[œÊ”ÒUÓÒS—ĞĞS‘QUHŠH™X\ÛÛ‹˜ÛÛZ[œÊ“PĞTÕÓ×ÓÕÈŠBˆ[ˆŒÔ\\•˜Z[š[™ÔYÍŒÌÊ™X\ÛÛˆİš[™ÊNˆ›ÛÛX[ˆBˆ™X\ÛÛ‹œİ\ÕÚ]
•ŒÎ”•Q×ÑUSˆŠHˆ™X\ÛÛ‹˜ÛÛZ[œÊ”•Q×ĞÔ’UPĞSŠHˆ™X\ÛÛ‹˜ÛÛZ[œÊ‘V‘SQWÔ•QÈ‹YÛ›Ü™PØ\ÙHHYJBˆ[ˆŒÒ\™İÜÔÚ]ÛÚ[ŒÌÊ
Nˆ›ÛÛX[ˆÂˆ˜[›İ][™Ô™Z™XİHŒÔ›İ][™Ô™Z™Xİ›Ü”Ú]ÛÚ[ŒÌÊŒÔ™Z™XİY™X\ÛÛŠBˆ˜[\\•˜Z[š[™ÔYÈHŒÔ\\•˜Z[š[™ÔYÍŒÌÊŒÑ˜][™X\ÛÛŠBˆ™]\›ˆ\›İ][™Ô™Z™Xİ	‰ˆ
ˆŒÑXÚ\Ú[Ûˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹”™Z™XİYˆ
ŒÑXÚ\Ú[Ûˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÑ˜][	‰ˆJŒÒ\Ô\\“[ÙSØØ[	‰ˆ\\•˜Z[š[™ÔYÊJHˆŒÑXÚ\Ú[Ûˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÙY
BˆBˆ˜[ŒÔ™Z™XİY\Ô›İ][™ÍŒÌHŒÔ›İ][™Ô™Z™Xİ›Ü”Ú]ÛÚ[ŒÌÊŒÔ™Z™XİY™X\ÛÛŠBˆ˜[ŒÒ\™™Z™Xİ›Ü”Ú]ÛÚ[ˆHŒÒ\™İÜÔÚ]ÛÚ[ŒÌÊ
BˆYˆ
ŒÒ\™™Z™Xİ›Ü”Ú]ÛÚ[ŠHÂˆHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K‘›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ”‘R‘PÕQÑUSÕŒÈ‹ˆ›Z[IİË›Z[HŞ[OIİËœŞ[X›ÛHŒÏIİŒÑXÚ\Ú[Û˜Û\ÜËš˜]˜KœÚ[\S˜[Y_H™X\ÛÛIŒÓÙÙÙY™X\ÛÛˆ‹ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHÒÒTÑUSŒ×ÒT‘Ô‘R‘PÕ
	İŒÑXÚ\Ú[Û˜Û\ÜËš˜]˜KœÚ[\S˜[Y_JH8 %[™H›İÈ\›Z[˜]Y›È]X[YšXØ][Ûˆ[[Y]H‚ˆ
BˆH[ÙHÂ‚ˆ˜\ˆÚ]ÛÚ[”ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™]˜[X]JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆİ\œ™[šXÙHHËœ™Y‹ˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆÜÛ\”İHËÜÛ\”İÎˆËœØY™]KÜÛ\”İZÙRYˆÈ]HHÎˆYˆ
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ó]™J
JHLŒ[ÙHŒŒˆ^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ[ÛY[[HHË›[ÛY[[HÎˆŒˆ›Û][]HHË›Û][]HÎˆŒˆÚÙ[YÙSZ[]\ÈHÚÙ[YÙSZ[]\Ëˆ][˜Ú]›Ü›HH][˜Ú]›Ü›Kˆ]•Ø[]H[ËÈ]ˆØ[]˜XÚÚ[™È›İY][\[Y[Yˆ]’ÛİH]’Ûİˆ[™TİH[™TİˆÛØÚX[ØÛÜ™HHÛØÚX[ØÛÜ™KˆËÈKKŒN8 %ÒSQHÛØÚX[\ØÛÜ™H˜\˜ÙK‚ˆËÈ™KUKKŒN\ÙHÙ\™H˜XœšXØ]Yœ›ÛHHÚ[™ÛH›ŞBˆËÈ
Ş[X›Û›[™İˆ˜ÛØÚX[ØÛÜ™HHŒ
K™YY[™ÈÂˆËÈÚ[ÈÙˆØ\˜˜YÙHÚYÛ˜[ÈÚ]ÛÚ[ˆØÛÜš[™Ëˆ[[ÙBˆËÈÚ\™H™X[ÛØÚX[\™\Ù[˜ÙH]Xİ[Û‹Y˜][È˜[ÙK‚ˆËÈÚ]ÛÚ[‰ÜÈØÛÜ™H›ÛÜœÈ\™H[™XYHØ[Xœ˜]YİÈÛÂˆËÈ\ÈÛÛ‰İÚÚÙH[šY\È8 %]\İİÜÈ™YY[™È›Ú\ÙBˆËÈ[ÈY\]™SX\›š[™Ñ[™Ú[™H]\›ˆÙZYÚË‚ˆ\ÕÙXœÚ]HHËœZ\•\›š\Ó›İ›[šÊ
Kˆ\ÕÚ]\ˆHËœÙ[[Y[Y[[ÛœÈˆˆ\Õ[YÜ˜[HHËœÙ[[Y[[YÜ˜[SY[[ÛœÈˆˆ\ÑÚ]XˆH˜[ÙKˆ\Ñ^›ÛÜİYH\Ñ^›ÛÜİYˆ^™[™[™Ô˜[šÈH^™[™[™Ô˜[šËˆ\ĞÛÜPØ]H\ĞÛÜPØ]ˆÜ˜YX][Û”›ÙÜ™\ÜÈHÜ˜YX][Û”›ÙÜ™\ÜËˆ
B‚ˆËÈKKŒN8 %”’QÑHQ’TÓÔ–H“ÓÔÕ
Y]]™K™]™\ˆ›ØÚÜÊK‚ˆËÈÚ[ˆH›İ™[ˆÎIHÔˆ\˜Ú]Xİ\™H
Y[YU[šYšYYØÛÜ™\œšYÙJBˆËÈYÜ™Y\È\ÈÚÙ[ˆÚİ[™H[\™Y[\ÛÛ™šY[˜ÙHH
ÍBˆËÈ[™[HØÛÜ™HH
ÌËˆ\™H[XˆÛˆHØØ[H8 %™]™\‚ˆËÈÜ™X]\È[ˆ[HH]˜[X]Üˆ™Z™XİYÈ™]™\ˆ›ØÚÜÈÛ™K‚ˆYˆ
Ë˜œšYÙPYš\ÛÜPYÜ™Y\È	‰ˆÚ]ÛÚ[”ÚYÛ˜[œÚİ[[\ŠHÂˆÚ]ÛÚ[”ÚYÛ˜[HÚ]ÛÚ[”ÚYÛ˜[˜ÛÜJˆÛÛ™šY[˜ÙHH
Ú]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙH
ÈJK˜ÛÙ\˜ÙP][Üİ
L
Kˆ[TØÛÜ™HH
Ú]ÛÚ[”ÚYÛ˜[™[TØÛÜ™H
ÈÊK˜ÛÙ\˜ÙP][Üİ
L
Kˆ™X\ÛÛˆHÚ]ÛÚ[”ÚYÛ˜[œ™X\ÛÛˆ
Èˆ
ØœšYÙH‚ˆ
BˆB‚ˆËÈKKŒNH8 %QSQHQÑHRH8 %]\›‹\˜]H™XY˜XÚÈ
È^Y\ˆÔˆÚ^š[™ÂˆËÈ
Èİ™XZÈÙ[H[\[™È
ÈÛ\İ\ˆÛÜœ™[][ÛˆİX\™ˆ[›İ[™Y‚ˆËÈØÛÜ™HYÙH
ËËNÚ^™HÌ‹ŒKˆÛÛ\İ\
Œ˜Y\ÊH\ÈBˆËÈ›Ë[ÜÈ˜[\ÈÈŒ	H]\›ˆ›[™HRÈ˜Y\Ëˆ™]™\ˆ›ØÚÜË‚ˆ˜[Ú]ÛÚ[“˜\œ˜]]™HHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YS˜\œ˜]]™PRK™]Xİ
ËœŞ[X›Û
K˜Û\İ\‚ˆHØ]Ú
Îˆ^Ù\[ÛŠHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YS˜\œ˜]]™PRKÛ\İ\‹•S’Ó“ÕÓ‚ˆBˆ˜[Ú]ÛÚ[“Ü[’[Û\İ\ˆHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™Ù]Xİ]™TÜÚ][ÛœÊ
K˜Ûİ[ÈÜÈO‚ˆ[Ø]Ú[™ÈÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YS˜\œ˜]]™PRK™]Xİ
ÜËœŞ[X›Û
K˜Û\İ\ˆOHÚ]ÛÚ[“˜\œ˜]]™BˆK™Ù]Ü‘Y˜][
˜[ÙJBˆBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈBˆ˜[Ú]ÛÚ[‘YÙHHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YQYÙPRK™]˜[X]Jˆ^Y\ˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Y[YQYÙPRK“^Y\‹”ÒUÓÒS‹ˆXØ\\ÙHË›\İXØ\ˆÚÙ[YÙSZ[]\ÈHÚÙ[YÙSZ[]\Ëˆ^T˜][ÔİHË›\İ^T™\Üİ\™Tİˆ›Û[YU\ÙHË›\İ\]ZY]U\Ù
ˆŒKˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆÛ\Ûİ[HËš\İÜK›\İÜ“[

OËšÛ\Ûİ[ÎˆˆÜÛ\”İHËÜÛ\”İÎˆËœØY™]KÜÛ\”İZÙRYˆÈ]HHÎˆYˆ
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ó]™J
JHLŒ[ÙHŒŒˆÛ\‘Ü›İİ˜]HHËšÛ\‘Ü›İİ˜]KˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KÑİX›J
Kˆ˜\ÙQ[TØÛÜ™HHÚ]ÛÚ[”ÚYÛ˜[™[TØÛÜ™KÑİX›J
Kˆ˜\œ˜]]™PÛ\İ\ˆHÚ]ÛÚ[“˜\œ˜]]™KˆÜ[Û\İ\Ûİ[HÚ]ÛÚ[“Ü[’[Û\İ\‹ˆ
BˆYˆ
Ú]ÛÚ[‘YÙKœØÛÜ™SYÙHOHŒÚ]ÛÚ[‘YÙK˜ÛÛ™šY[˜ÙSYÙHOHŒ
HÂˆÚ]ÛÚ[”ÚYÛ˜[HÚ]ÛÚ[”ÚYÛ˜[˜ÛÜJˆ[TØÛÜ™HH
Ú]ÛÚ[”ÚYÛ˜[™[TØÛÜ™H
ÈÚ]ÛÚ[‘YÙKœØÛÜ™SYÙKÒ[

JK˜ÛÙ\˜ÙR[ŠL
KˆÛÛ™šY[˜ÙHH
Ú]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙH
ÈÚ]ÛÚ[‘YÙK˜ÛÛ™šY[˜ÙSYÙKÒ[

JK˜ÛÙ\˜ÙR[ŠL
Kˆ™X\ÛÛˆHÚ]ÛÚ[”ÚYÛ˜[œ™X\ÛÛˆ
Èˆ
ÙYÙVÈˆ
ÈÚ]ÛÚ[‘YÙK™^[˜][Ûˆ
È—H‚ˆ
BˆB‚ˆËÈKKH8 %‘SSÕ‘QKKÈÒÔÔ‘R‘PÕ›ÜˆÚ]ÛÚ[‹‚ˆËÈHš[\ˆ›ØÚÙYVĞ“ÓÔÕQÑVÕ‘S‘S‘È[šY\È[‚ˆËÈX\›Wİ[šÛ›İÛ‹Ü™WÜ[\\Ù\È]ØÛÜ™OŒ8 %^XİHBˆËÈœ™\Ú[\™[ˆ][˜Ú\ÈÚ]Ü\œÙH]H]›ÙXÙYˆËÈZ[LNMIÜÈÔ‹Z[‹]KMŒËˆÛÙØÛÜš[™È[™XYHÙZYÚÂˆËÈ\ÙHÚYÛ˜[ÎÈH\™›ÛÜˆØ\ÈÚÚÙH™\Üİ\™K‚‚‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒHÓÓTÕT•’VˆÚXÚÈ›Üˆ›Ûİİ˜\›Ü˜ÙY[BˆËÈ\ÙH˜]ÈÚYÛ˜[ØÛÜ™K›İÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙHÚXÚX^H™HİÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[˜]ÔÚ]ÛÚ[”ØÛÜ™HHØ[İ[]P›Ûİİ˜\ØÛÜ™Jˆ^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ[ÛY[[HHË›[ÛY[[HÎˆŒˆ›Û][]HHË›Û][]HÎˆŒˆ
Bˆˆ˜[›Ü˜ÙP›Ûİİ˜\[HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRKœÚİ[›Ü˜ÙP›Ûİİ˜\[JˆØÛÜ™HH˜]ÔÚ]ÛÚ[”ØÛÜ™Kˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆÚÙ[YÙSZ[]\ÈHÚÙ[YÙSZ[]\Ëˆ^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
HËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
BˆˆËÈŒNˆ[\ˆYˆÚ]ÛÚ[ˆØ^\ÈY\ÈÔˆ›Ûİİ˜\İ™\œšYHšYÙÙ\™YˆËÈKKŒÈ’VˆŒÈ\™\™Z™XİUTÕØ]H“Õœ˜[˜Ú\Ë›İ\İ›Ûİİ˜\‚ˆËÈ™]š[İ\ÛNˆÚ]ÛÚ[”ÚYÛ˜[œÚİ[[\ˆ
›Ü˜ÙP›Ûİİ˜\[H	‰ˆ]ŒÒ\™™Z™Xİ	‰ˆY[\
XˆËÈYX[HŒÈUS
V‘SQWÔ•Q×Ô’TÒ×ÎL
HÛˆÔRÑHØ\ÈÚ[[H\\ÜÙYHBˆËÈš[X\HÚ]ÛÚ[ˆ]\ÈÙY[ˆ[ˆ›ÙÙÜËˆ™X\İ\H[™XYHØ]\ÈÛØ˜[BˆËÈ
[™HÊNÈÚ]ÛÚ[ˆ›İÈZ\œ›ÜœÈ]İXİ\™K‚ˆËÈKŒŒÌÎˆÛ™HØØ[^Û›Û^HİÛœÈ›İ][™Ë]œËZ\™\İÜ›Üˆ\Èœ˜[˜Ú‚ˆËÈŒÈ™Z™XİY
”ÒUÓÒS—ĞĞS‘QUH‹È“PĞTÕÓ×ÓÕÈŠH\È›İ][™ÈÛ›NÂˆËÈYH›ØÚÙYÔ™Z™XİYÙ˜][İ^\È\›Z[˜[^Ù\\\‹[Û›HYÈ˜Z[š[™Ë‚ˆ˜[ŒÔ™Z”™X\ÛÛˆHŒÔ™Z™XİY™X\ÛÛ‚ˆ˜[\Ô›İ][™Ô™Z™XİHŒÔ™Z™XİY\Ô›İ][™ÍŒÌˆ˜[ØĞ›ØÚÑ˜][™X\ÛÛˆHŒÑ˜][™X\ÛÛ‚ˆ˜[ØĞ›ØÚÑ˜][\ÔYÈHŒÔ\\•˜Z[š[™ÔYÍŒÌÊØĞ›ØÚÑ˜][™X\ÛÛŠBˆ˜[Ú]ÛÚ[•ŒÒ\™™Z™XİHŒÒ\™İÜÔÚ]ÛÚ[ŒÌÊ
BˆËÈKKŒNÎˆ™[[İ™Y\XØ]HÚXÚÜÈ]Ù\™Hİ]ÚYH	‰‹YÜ›İ\
Ø\È[Ø^\Ë]YHÛˆ›ØÚÑ˜][
Bˆİ\™\ÜÊ•S•TÑQÑV‘TÔÒSÓˆŠBˆ˜[Ú]ÛÚ[’\Ñ[\HHÈRPÜ›ÜÜÕ[Ëš\ĞÛÛÜ™[˜]Y[\
Ë›Z[ËœŞ[X›Û
HHØ]Ú
Îˆ^Ù\[ÛŠHÈ˜[ÙHBˆËÈKKŒMMˆ8 %Ø[YH›Ûİİ˜\\\ÜÈ\È™X\İ\H]‚ˆ˜[Ú]ÛÚ[‘[\\\ÜÈHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]X\›š[™Ô›ÙÜ™\ÜÊ
HˆHØ]Ú
Îˆ^Ù\[ÛŠHÈ˜[ÙHBˆ˜[Ú]ÛÚ[‘[\›ØÚÜÈHÚ]ÛÚ[’\Ñ[\	‰ˆ\Ú]ÛÚ[‘[\\\ÜÂˆËÈKKŒLŒ8 %›Ûİİ˜\İ™\œšYHØ[››İ\\ÜÈX\›™YˆËÈX]XÚÙ]Ëˆ[[YHKŒŒÌMÌHÚİÙY›Ú™XİYˆËÈ^XÜËÙ^OLÌŒHÚ[HÒUÓÒSŸÌLLØ\ÈL•ËÌŒS‚ˆËÈÚ]ÛÚ[•˜Y\RHÛÜœ™XİH™]\›œÈÚİ[[\Y˜[ÙBˆËÈ›ÜˆÌÌLĞ“QQÑÕPT‘È^Xİ[˜ŞHÈÔˆ™XÛİ™\BˆËÈ›ÛÜœË]›Ü˜ÙP›Ûİİ˜\[HÔ‰Ù]˜XÚÈÈYK‚ˆËÈÙY\^Ü˜]ÜH›Ûİİ˜\›Üˆ™]]˜[™\ÚÛZ\ÜÙ\ÎÂˆËÈÈ›İ›Ü˜ÙH›İYÚ[\\šXØ[H˜YXÚÙ]Ë‚ˆ˜[Ú]™Z™Xİ™X\ÛÛ•\\ˆHÚ]ÛÚ[”ÚYÛ˜[œ™X\ÛÛ‹\\˜Ø\ÙJ
Bˆ˜[›Ü˜ÙP›Ûİİ˜\[İÙYHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆ›Ü˜ÙP›Ûİİ˜\[H	‰‚ˆ\Ú]™Z™Xİ™X\ÛÛ•\\‹˜ÛÛZ[œÊ”ÌÌLĞ“QQÑÕPT‘ŠH	‰‚ˆ\Ú]™Z™Xİ™X\ÛÛ•\\‹˜ÛÛZ[œÊ”ÒUÓÒS—ÑS‘ÑT—Ğ•PÒÑUÑÕPT‘ŠH	‰‚ˆ\Ú]™Z™Xİ™X\ÛÛ•\\‹˜ÛÛZ[œÊ‘VPÕSÖWÔ‘R‘PÕŠH	‰‚ˆ\Ú]™Z™Xİ™X\ÛÛ•\\‹˜ÛÛZ[œÊ•Ô—Ô‘PÓÕ‘T–WÔĞÓÔ‘WÑ“ÓÔˆŠBˆYˆ
›Ü˜ÙP›Ûİİ˜\[H	‰ˆY›Ü˜ÙP›Ûİİ˜\[İÙY
HÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ”ÒUÓÒS—ÓU‘WĞQTU‘WÑ“ÔÑWÔÕT‘TÔÑQ‹ˆœŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H™X\ÛÛIÜÚ]ÛÚ[”ÚYÛ˜[œ™X\ÛÛ‹ZÙJLŒ
_H˜]ÔØÛÜ™OI˜]ÔÚ]ÛÚ[”ØÛÜ™HØÛÜ™OIÜÚ]ÛÚ[”ÚYÛ˜[™[TØÛÜ™_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆBˆ˜\ˆÚİ[[\ˆH\Ú]ÛÚ[•ŒÒ\™™Z™Xİ	‰ˆ\Ú]ÛÚ[‘[\›ØÚÜÈ	‰‚ˆ
Ú]ÛÚ[”ÚYÛ˜[œÚİ[[\ˆ›Ü˜ÙP›Ûİİ˜\[İÙY
B‚ˆËÈKKŒÎˆ^XÚ]ÙÈÛÈH›ØÚÈ\Èš\ÚX›HÚ[ˆ]š\™\ÂˆYˆ
Ú]ÛÚ[•ŒÒ\™™Z™Xİ	‰ˆ
Ú]ÛÚ[”ÚYÛ˜[œÚİ[[\ˆ›Ü˜ÙP›Ûİİ˜\[İÙY
JHÂˆ˜[›ØÚÔ™X\ÛÛˆHÚ[ˆ
ŒÑXÚ\Ú[ÛŠHÂˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÑ˜][Oˆ•Œ×ÑUS‰ÊŒÑXÚ\Ú[Ûˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÑ˜][
Kœ™X\ÛÛŸH‚ˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÙYOˆ•Œ×Ğ“ĞÒÑQ‚ˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹”™Z™XİYOˆ•Œ×Ô‘R‘PÕQ‚ˆ[ÙHOˆ•Œ×ÒT‘Ô‘R‘PÕ‚ˆBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛH‘UÑQ	›ØÚÔ™X\ÛÛˆ
Ú]ÛÚ[”ÚYÛ˜[Ø[Y[JHŠBˆBˆˆËÈKŒŒH8 %›Ûİİ˜\ØÛÜ™HØ]H\È\\‹[Û›Kˆ]™HY\ÂˆËÈœ›ÛH˜YHHšXHÛX[ˆ]™Hİ]ÛÛYH™YY˜XÚÈ[™]\›Z[š\İXÈ›ÛÙ‹‚ˆ˜[\\›Ûİİ˜\›ØÚÙYŒÌHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRKœÚİ[›ØÚĞ›Ûİİ˜\˜YJÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙJBˆYˆ
\\›Ûİİ˜\›ØÚÙYŒÌ
HÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHTT—Ğ“ÓÕÕTĞ“ĞÒÑQØÛÜ™OIÜÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜Ù_H	ØÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]›Ûİİ˜\İ]\Ê
_H8 %œ˜[˜Ú[ØØ[ÚÚ\ŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ”ÒUÓÒS—Ğ“ÓÕÕTĞ”SÒÓĞĞSÔÒÒTÍŒÌ‹œŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HØÛÜ™OIÜÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜Ù_HŠHHØ]Ú
Îˆ›İØX›JHßBˆÚİ[[\ˆH˜[ÙBˆBˆˆYˆ
Úİ[[\ŠHÂˆËÈKKŒÍLÎˆ\İ\İ]\ÙH8 %™Y\ÙHÚ]ÛÚ[ˆ[šY\ÈÚ[‚ˆËÈİ˜]YŞU\İRH™\ÜÈTÕ•TÕQ
ÈÔL	H
ÈœÌ	K‚ˆËÈ™]™[ÈHÛÛ™\™ÙYX˜YXœ˜Z[ˆœ›ÛHÛÛ[Z[™ÈÈ›YYˆËÈ›İYÚ\È^Y\ˆ›ÜˆLZ[ˆ]H[YK‚ˆ˜[
]\ÙYÚJHH›İÙ\šXÙKš\Ôİ˜]YŞT]\ÙYU\İ
”ÒUÓÒSˆŠBˆ˜[İ˜]YŞQ\İ\İÚ^™S][ŒÌHYˆ
]\ÙY
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHTÕ•TÕ‘PÓÕ‘T–H“Ğ‘H	ÚHÚ^™S][LŒÍHŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ”ÒUÓÒS—ÔÕUQÖWÑTÕ•TÕÔ‘PÓÕ‘T–WÔ“Ğ‘WÍŒÌ‹œŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HÚOIÚHŠHHØ]Ú
Îˆ›İØX›JHßBˆŒÍBˆH[ÙHKŒˆËÈKŒŒH8 %›Ûİİ˜\Ú^™H][\Y\ˆ\È\\‹[Û›NÈ]™BˆËÈÚ^š[™Èİ^\È›ZYØY\]™Hœ›ÛHÛX\Ú^™\ˆ
È[™KÛX\šÙ]›ÛÙ‹‚ˆ˜[›Ûİİ˜\][\Y\ˆHYˆ
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
JHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]›Ûİİ˜\Ú^™S][\Y\Š
H[ÙHKŒˆËÈKKŒNH8 %\HY[YQYÙPRHÚ^™H][\Y\ˆ
›İ[™YÌ‹ŒK
K‚ˆ˜[YÙTÚ^™S][HÚ]ÛÚ[‘YÙKœÚ^™S][\Y\‚ˆËÈKKŒLMÈ8 %Ø[Xœ˜][Û‹X]Ø\™HÚš[šÈ
™][™YØ]]™H˜[™
K‚ˆ˜[ÜÚ]Ø[][HHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ØÛÜ™Q^Xİ[˜ŞU˜XÚÙ\‹˜Ø[Xœ˜][Û”Ú^™S][
”ÒUÓÒSˆ‹Ú]ÛÚ[”ÚYÛ˜[™[TØÛÜ™JBˆHØ]Ú
Îˆ›İØX›JHÈKŒBˆ˜\ˆY\İYÚ^™HH
Ú]ÛÚ[”ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛ
ˆ›Ûİİ˜\][\Y\ˆ
ˆYÙTÚ^™S][
ˆÜÚ]Ø[][
ˆİ˜]YŞQ\İ\İÚ^™S][ŒÌ
K˜ÛÙ\˜ÙP]X\İ
ŒJBˆˆËÈKŒ‹’VˆYˆ›Ûİİ˜\İ™\œšYH›Ü˜ÙY[K\ÙHY˜][ÔÓ˜[Y\Âˆ˜[Ú]ÛÚ[‘Y™™Xİ]™UİHYˆ
Ú]ÛÚ[”ÚYÛ˜[ZÙT›Ùš]İHŒ
HKŒ[ÙHÚ]ÛÚ[”ÚYÛ˜[ZÙT›Ùš]İˆ˜[Ú]ÛÚ[‘Y™™Xİ]™TÛİHYˆ
Ú]ÛÚ[”ÚYÛ˜[œİÜÜÜÔİHŒ
HNŒ[ÙHÚ]ÛÚ[”ÚYÛ˜[œİÜÜÜÔİˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKŒˆQHUUÔ’V‘TˆHUTÕ\ÜÈ™Y›Ü™HS–H^Xİ][Û‚ˆËÈ™]™[ÈÜİY^Xİ][ÛˆØ][™ÈšYˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKK8 %‘ÈØ]H›ÜˆÚ]ÛÚ[ˆ]ˆ˜[Ú]ÛÚ[‘™ÈHHÂˆš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËˆØ[™Y]HH[™T]X[YšYY^QXÚ\Ú[ÛŠXÚ\Ú[Û‹”ÒUÓÒSˆ‹ÛÛ™šY[˜ÙQ›ÛÜˆHÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙH
ˆLŒ\]ZY]U\ÙHË›\İ\]ZY]U\ÙZ[›Ü”›Ø™HHË›Z[
Kˆ[™TØÛÜ™HHÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙKÑİX›J
KËÈ[LLˆÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛHY\İYÚ^™Kˆœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈHHÈ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJ”ÒUĞÓÒSˆŠHHØ]Ú
Îˆ^Ù\[ÛŠHÈ[KˆÜXÚX[\İ[™HH”ÒUÓÒSˆ‹ˆ
BˆHØ]Ú
™Ñ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H‘È\œ›Üˆ	Ù™Ñ^›Y\ÜØYÙ_H8 %›ØÙYY[™È˜Z[[Ü[ˆŠBˆ[ˆBˆËÈKKH8 %[\‘È›Ü™[œÚXÈÛİ[\ˆ›ÜˆÒUÓÒSˆ]ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆœ]TÒUÓÒSˆØ[IÜÚ]ÛÚ[‘™ÏË˜Ø[‘^Xİ]J
HÎˆY_H™X\ÛÛIÜÚ]ÛÚ[‘™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›‹ØHŸHŠBˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]J›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆ[İÈHÚ]ÛÚ[‘™ÏË˜Ø[‘^Xİ]J
HÎˆYKˆ™X\ÛÛˆHÚ]ÛÚ[‘™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›ÚÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆ^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™ÊË›Z[ËœŞ[X›Û”ÒUÓÒSˆ‹Ú]ÛÚ[‘™ÏË˜Ø[‘^Xİ]J
HÎˆYKÚ]ÛÚ[‘™ÏË˜›ØÚÔ™X\ÛÛ‹ÚYÛ˜[H•VH‹YÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KØY™]UY\ˆHËœØY™]KY\‹›˜[YK\]ZY]U\ÙHË›\İ\]ZY]U\Ù\™›Ô™X\ÛÛœÈHËœØY™]Kš\™›ØÚÔ™X\ÛÛœË[TØÛÜ™HHË™[TØÛÜ™KÒ[

KÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJKœ›İ]Tİ]\ËÚÙ[“X\Y˜][ÛÛÛ\]HHËÚÙ[“X\šY˜][ÛÛÛ\]KÚÙ[“X\^XİYİ]HËÚÙ[“X\™^XİYİ][[İ[ÚÙ[“X\›İšY\][\ÈHËÚÙ[“X\œ›İšY\][\ÊBˆËÈKKŒLŒH8 %‘È\ÈHT‘‘UÈ›ÜˆÚ]ÛÚ[ˆÛË‚ˆËÈ[[YHÙÈÎŒÈÚİÙY\™XİÒUÓÒSˆ\\ˆ^\ÂˆËÈY\ˆŒËÑ‘Èİ]HØ\ÈĞUÒÒT‘Ó“×Ğ•VKˆHÛˆËÈKKLHÛÙH^XÚ]H™X]Y‘È\È›[Ù[]\ËˆËÈÙ\È›İ\™ZÚ[‹Ø]\Ú[™ÈZ\ÛXY[™ÈS•–HÙÜËˆËÈ]HTT—Ğ•VWĞ“ĞÒÑQÑ’SSUK[™[ˆÛÛYH]ÂˆËÈ\\ˆ˜YHÛ][Û‹ˆİ[™[™ÈÜ\˜]Üˆ[Nˆ‘ÂˆËÈ]\İ\™]™]È]™\H˜Y\ˆ^Xİ][Ûˆ]ˆ˜Z[[Ü[‚ˆËÈ\È™\Ù\™YÛ›H›Üˆ‘È^Ù\[ÛœËÛ[™\İ[‚ˆYˆ
Ú]ÛÚ[‘™ÈOH[	‰ˆ\Ú]ÛÚ[‘™Ë˜Ø[‘^Xİ]J
JHÂˆ˜[ØĞ›ØÚÈHÚ]ÛÚ[‘™Ë˜›ØÚÔ™X\ÛÛˆÎˆ‘‘×Ğ“ĞÒÈ‚ˆËÈKKŒLÍ8 %[YÛˆÒUÓÒSˆÚ]SÓÓ”ÒÕÓPS’TSUQˆÜ]ˆËÈÙ[Z[™HØY™]H›ØÚÜÈ
\™™]Ë[˜Ú[™ÙY
Hœ›ÛHÛÙˆËÈ]K\İ\˜][Ûˆ›ØÚÜÈ]›Ûİİ˜\Úİ[“Ğ‘H›İYÚ]ˆËÈ[HÚ^™HÛÈH[™HØ[ˆš[˜[HØ]\ˆ]H[™X\›ˆ]ÂˆËÈİÙY]Üİˆ‘È™[XZ[œÈH\™™]È›Üˆ]™\H™X[š\ÚÎÈÙBˆËÈÛ›HİÜÙH]™H›È]HY]ˆœ›ÛHÚ[[™ÈH˜Y\È]ˆËÈÛİ[Ù[™\˜]HH]Kˆ›Ø™H\ÈÚYK[Ü[‹\\ÙHÛ›K‚ˆ˜[ÚYSÜ[›Ûİİ˜\HHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K‘œ™YT˜[™ÙS[ÙKš\ÕÚYSÜ[Š
HHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆ˜[ØÔ›Ø™XX›HHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆÚYSÜ[›Ûİİ˜\	‰ˆ\Ô\\›Ûİİ˜\›Ø™XX›Q™Ğ›ØÚÊØĞ›ØÚÊBˆYˆ
\ØÔ›Ø™XX›JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘ÈT‘‘UÈÛˆÒUÓÒSˆ	İËœŞ[X›ÛH	ØĞ›ØÚÈŠBˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ”ÒUÓÒS—Ñ‘×ÒT‘Õ‘UÈ‹ˆœŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H™X\ÛÛIØĞ›ØÚÈ\OIİË›\İ\]ZY]U\ÙÒ[

_HYÏIİËœØY™]KœYØÚXÚÔØÛÜ™_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
”ÒUÓÒS—Ñ‘×ÒT‘Õ‘UÈ‹ØĞ›ØÚÊBˆ™[X\ÙTÚ]ÛÚ[][\ŒÌ
‘‘×ÒT‘Õ‘UÈ‹™[X\ÙT\›Z]H˜[ÙK™[X\ÙP]]H˜[ÙJBˆ™]\›‚ˆBˆËÈÛÙ›ØÚÈ[ˆÚYK[Ü[ˆ›Ûİİ˜\8¡¤ˆ[HX\›š[™È›Ø™K‚ˆY\İYÚ^™HH
Y\İYÚ^™H
ˆŒJK˜ÛÙ\˜ÙP]X\İ
ŒJBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'éêˆ‘ÈTTˆ“ÓÕÕT“Ğ‘HÛˆÒUÓÒSˆ	İËœŞ[X›ÛH	ØĞ›ØÚÈ›Ø™TÚ^™OIØY\İYÚ^™K™›]
Ê_HÓÓŠBˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ”ÒUÓÒS—Ñ‘×Ğ“ÓÕÕTÔ“Ğ‘H‹ˆœŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H™X\ÛÛIØĞ›ØÚÈ\OIİË›\İ\]ZY]U\ÙÒ[

_H›Ø™TÛÛIØY\İYÚ^™K™›]
Ê_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
”ÒUÓÒS—Ñ‘×Ğ“ÓÕÕTÔ“Ğ‘H‹ØĞ›ØÚÊBˆB‚ˆËÈKŒLÈ8 %Ú]ÛÚ[ˆ]˜[X]Y‘È]Ù\\Ú[™È™KQ‘ÂˆËÈY\İYÚ^™H›Üˆ\›Z]Ù^Xİ]Ü‹ˆ]\\ÜÙYL‰ÜÈ™\İÜ™YˆËÈÛÜ™HÚ^™H[™[H‘ÈX\›™Y\Ú^™Hİ\™˜XÙKˆ\H‘ÈÚ^™HÛ˜ÙBˆËÈHXÚ\Ú[Ûˆ\È^Xİ]X›NÈ\\ˆ›Ûİİ˜\›Ø™Hİ™\œšYHX›İ™BˆËÈ™[XZ[œÈ\\‹[Û›H[™[[[Û˜[HÛX[\‹‚ˆYˆ
Ú]ÛÚ[‘™ÏË˜Ø[‘^Xİ]J
HOHYH	‰ˆÚ]ÛÚ[‘™ËœÚ^™TÛÛˆŒ
HÂˆ˜[™Y›Ü™Q™ÔÚ^™MLÈHY\İYÚ^™BˆY\İYÚ^™HHÚ]ÛÚ[‘™ËœÚ^™TÛÛˆYˆ
Ûİ[‹›X]˜XœÊ™Y›Ü™Q™ÔÚ^™MLÈHY\İYÚ^™JHˆŒJHÂˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ”ÒUÓÒS—Ñ‘×Ñ’SSÔÒV‘WĞTQQÍLÈ‹œŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HÚ^™OIØ™Y›Ü™Q™ÔÚ^™MLË™›]

_KO‰ØY\İYÚ^™K™›]

_HŠHHØ]Ú
Îˆ›İØX›JHßBˆBˆB‚ˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØÛÜ™HHË›\İŒÔØÛÜ™HÎˆÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙKˆÛÛ™šY[˜ÙHHÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ]X[]HHÈ‹ËÈÚ]ÛÚ[ˆÙ\Û‰İ]™HÜ˜YK\ÙHY˜][ˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË”ÒUÓÒS‹ˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Ë›Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛHY\İYÚ^™Kˆ
BˆˆYˆ
X]]™\İ[š\Ñ^Xİ]X›J
JHÂˆYˆ
]]™\İ[š\ÔÚYİÓÛ›J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHÒQÕ×ÓÓ“H	Ø]]™\İ[œ™X\ÛÛŸHŠBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛH‘R‘PÕQ	Ø]]™\İ[œ™X\ÛÛŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
”ÒUÓÒSˆ‹]]™\İ[œ™X\ÛÛŠBˆBˆH[ÙHÂˆËÈUUÔ’V‘QH›ØÙYYÚ]^Xİ][Û‚ˆ˜[Ú]ÛÚ[][\YH]]™\İ[˜][\YˆˆËÈŒˆHÈXÜ]Z\™H^Xİ][Ûˆ\›Z]ˆ˜[Ø[‘^Xİ]HHš[˜[^Xİ][Û”\›Z]PXÜ]Z\™Q^Xİ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ^Y\ˆH”ÒUÓÒSˆ‹ˆÚ^™TÛÛHY\İYÚ^™Kˆ][\YHÚ]ÛÚ[][\Yˆš[˜[]T™XÚXÚÙYHYKˆ\\“[ÙHHÙ™Ëœ\\“[ÙKˆYÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™Kˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆØY™]UY\ˆHËœØY™]KY\‹›˜[YKˆ\İØY™]PÚXÚÓ\ÈHË›\İØY™]PÚXÚÂˆ
BˆˆYˆ
Ø[‘^Xİ]JHÂˆ˜[Ü˜YX™[HYˆ
Ú]ÛÚ[”ÚYÛ˜[™Ü˜YX][Û’[[Z[™[
HˆÑÔQSSRS‘S•WHˆ[ÙHˆ‚ˆ˜[[™SX™[HYˆ
Ú]ÛÚ[”ÚYÛ˜[˜[™UØ\›š[™ÊHˆĞ•S‘HWHˆ[ÙHˆ‚ˆ˜[›Ûİİ˜\YÈHYˆ
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆ›Ü˜ÙP›Ûİİ˜\[JHˆÔTT—Ğ“ÓÕÕTHˆ[ÙHˆ‚ˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHS•T‰›Ûİİ˜\YÈˆ
Âˆ‰ÜÚ]ÛÚ[”ÚYÛ˜[›][˜Ú]›Ü›K™[[Úš_H	ÜÚ]ÛÚ[”ÚYÛ˜[›][˜Ú]›Ü›K™\Ü^S˜[Y_Hˆ
Âˆ›XØ\W		ÊË›\İXØ\ÌWÌ
K™›]
J_RÈˆ
Âˆœš\ÚÏIÜÚ]ÛÚ[”ÚYÛ˜[œš\ÚÓ]™[™[[Úš_IÜÚ]ÛÚ[”ÚYÛ˜[œš\ÚÓ]™[›˜[Y_Hˆ
ÂˆœÚ^™OIØY\İYÚ^™K™›]
Ê_HÓÓ
	Ê›Ûİİ˜\][\Y\ŠŒL
KÒ[

_IJHˆ
Âˆ•IÜÚ]ÛÚ[”ÚYÛ˜[ZÙT›Ùš]İIIÜ˜YX™[	[™SX™[ŠBˆˆËÈ^Xİ]HÚ]ÛÚ[ˆ^Bˆ˜[Ú]ÛÚ[“Ü[™YH^Xİ]Ü‹œÚ]ÛÚ[^JˆÈHËˆÚ^™TÛÛHY\İYÚ^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆZÙT›Ùš]İHÚ]ÛÚ[‘Y™™Xİ]™UİËÈKŒ‹ˆ\ÙHY™™Xİ]™HˆİÜÜÜÔİHÚ]ÛÚ[‘Y™™Xİ]™TÛİËÈKŒ‹ˆ\ÙHY™™Xİ]™HÓˆØ[]HØ[]ˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ][˜Ú]›Ü›HHÚ]ÛÚ[”ÚYÛ˜[›][˜Ú]›Ü›Kˆš\ÚÓ]™[HÚ]ÛÚ[”ÚYÛ˜[œš\ÚÓ]™[ˆš[˜[]T™XÚXÚÙYHYKˆ][\YHÚ]ÛÚ[][\Yˆ[TØÛÜ™HHÚ]ÛÚ[”ÚYÛ˜[™[TØÛÜ™Kˆ[PÛÛ™šY[˜ÙHHÚ]ÛÚ[”ÚYÛ˜[˜ÛÛ™šY[˜ÙKˆ
BˆYˆ
\Ú]ÛÚ[“Ü[™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹”ÒUÓÒSˆ	İËœŞ[X›ÛH•VWÓ“ÕÓÔS‘Q™[X\ÙH]]Ü\›Z]È›È[™H™YÚ\İ˜][ÛˆŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ“S‘WĞ•VWÓ“ÕÓÔS‘QÔ‘SPTÑQ‹›[™OTÒUÓÒSˆŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆ™[X\ÙTÚ]ÛÚ[][\ŒÌ
•VWÓ“ÕÓÔS‘QŠBˆ™]\›‚ˆB‚ˆˆËÈK‹’Vˆ›İYHŒÈ^Üİ\™HİX\™ÂˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYHËœÜÚ][Û‹š\ÓÜ[ŠHÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][Û“Ü[™Y
Ë›Z[
B‚ˆËÈ™YÚ\İ\ˆÚ]^Y\ˆ˜[œÚ][ÛˆX[˜YÙ\‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹œ™YÚ\İ\”ÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ^Y\ˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹•˜Y[™Ó^Y\‹”ÒUÓÒS‹ˆ[SXØ\HË›\İXØ\ˆ[TšXÙHHËœ™Y‹ˆ
B‚ˆËÈKŒ’VˆX\šÈÜÚ][Ûˆ\ÈÚ]ÛÚ[ˆÛÈÚXÚÑ^]\Ù\ÈÛÜœ™Xİ™\ÚÛÂˆËœÜÚ][Û‹š\ÔÚ]ÛÚ[”ÜÚ][ÛˆHYBˆËœÜÚ][Û‹˜Y[™Ó[ÙHH”ÒUÓÒSˆ‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¼'äªH‚‚ˆËÈ™YÚ\İ\ˆÚ]Ú]ÛÚ[•˜Y\RHÚ[ˆÜÚ][Ûˆ\ÈÜ[™YÜˆ[™[™È]™H™\šYšXØ][Û‹‚ˆËÈKKŒÌ’Vˆ›ÜˆU‘H^\Ë]™P^J
HÙ]È[™[™Õ™\šYO]YHÚXÚXZÙ\ÂˆËÈ\ÓÜ[Y˜[ÙH8 %ÛÈÚXÚÚ[™È\ÓÜ[ˆ[Û™HÚÚ\È™YÚ\İ˜][Ûˆ›Üˆ[]™H˜Y\ÈBˆËÈÛÜœ™XİÚXÚÎˆ]UÚÙ[ˆˆ
\\ŠHÔˆ[™[™Õ™\šYO]YH
]™K]ØZ][™ÈÛÛ™š\›JK‚ˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYJHÂˆ˜[XİX[[TšXÙHHËœÜÚ][Û‹™[TšXÙKZÙRYˆÈ]ˆHÎˆËœ™Y‚ˆ˜[Ú]]•Ø[]˜]ÍŒÌˆHËÚÙ[“X\˜Ü™X]Ü“Ü‘]•Ø[]šY›[šÈÈËÚÙ[“X\›Z[]]Üš]K›Ü‘[\J
HBˆ˜[Ú]]•Ø[]ŒÌˆHÚ]]•Ø[]˜]ÍŒÌ‹ZÙRYˆÈ]š\Ó›İ›[šÊ
HBˆYˆ
Ú]]•Ø[]ŒÌˆOH[
HHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê”ÒUÓÒS—ÑU—ÕĞSUÕÒT‘QÍŒÌˆŠHHØ]Ú
Îˆ›İØX›JHßBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜YÜÚ][ÛŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK”Ú]ÛÚ[”ÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHXİX[[TšXÙKˆ[TÛÛHY\İYÚ^™Kˆ[U[YHHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™ÂˆZÙT›Ùš]İHÚ]ÛÚ[‘Y™™Xİ]™UİˆİÜÜÜÔİHÚ]ÛÚ[‘Y™™Xİ]™TÛİˆ][˜Ú]›Ü›HHÚ]ÛÚ[”ÚYÛ˜[›][˜Ú]›Ü›Kˆ]•Ø[]HÚ]]•Ø[]ŒÌ‹ˆ[™TİH[™TİˆÛØÚX[ØÛÜ™HHÚ]ÛÚ[”ÚYÛ˜[œÛØÚX[ØÛÜ™KˆËÈKKÍH8 %™\Ù\™H[HØÛÜ™H›Üˆİ]ÛÛYH]šX][Û‚ˆ[TØÛÜ™HHÚ]ÛÚ[”ÚYÛ˜[™[TØÛÜ™KˆËÈKKŒN8 %Ø\\™H™X[[HÛÛ^›ÜˆY\]™SX\›š[™Ñ[™Ú[™Bˆ[P^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ[PYÙSZ[]\ÈHÚÙ[YÙSZ[]\Ëˆ[RÛ\Ûİ[HËš\İÜK›\İÜ“[

OËšÛ\Ûİ[Îˆˆ[UÜÛ\”İHËÜÛ\”İÎˆËœØY™]KÜÛ\”İZÙRYˆÈ]HHÎˆŒˆ[TYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KÑİX›J
Kˆ[Q[XQ˜[”İ]HHË›Y]K™[XY˜[[YÛ›Y[šY›[šÈÈËœ\ÙHKˆ[RÛ\‘Ü›İİ˜]HHËšÛ\‘Ü›İİ˜]Kˆ[U›Û[YU\ÙHË›\İ\]ZY]U\Ù
ˆŒKËÈIH\İ[X]H
™X[›Û[YH›İ[Ø^\È[X™Y
Bˆ[S[ÛY[[HHË›[ÛY[[HÎˆŒˆ[QÜ˜YX][Û”›ÙÜ™\ÜÈHÜ˜YX][Û”›ÙÜ™\ÜËˆ
Bˆ
BˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'äªHÒUÓÒSˆ•VHY›İÜ[ˆÜÚ][Ûˆ›Üˆ	İËœŞ[X›ÛH8 %ÚÚ\[™ÈÚ]ÛÚ[•˜Y\RH™YÚ\İ˜][ÛˆŠBˆBˆˆËÈ™[X\ÙH\›Z]ˆš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
BˆˆËÈŒNˆ™XÛÜ™˜YH›ÜˆX\›š[™ÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRKœ™XÛÜ™˜YTİ\

Bˆˆ˜[›Ûİİ˜\X™[HYˆ
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆ›Ü˜ÙP›Ûİİ˜\[JHˆÔTT—Ğ“ÓÕÕTHˆ[ÙHˆ‚ˆËÈKKÌH8 %Ú\™HVPÈ›Ü™[œÚXÈÛİ[\ˆ›ÜˆÚ]ÛÚ[ˆ[™BˆËÈ
Ø\ÈÛ›HÚ\™Y[ˆQSQWÔÔS‘H]X]š[™ÈVPÏLÚ[ˆÚ]ÛÚ[ˆ˜[ŠBˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹™^XÊˆXİ[ÛˆHYˆ
Ù™Ëœ\\“[ÙJH”TT—Ğ•VHˆ[ÙH“U‘WĞ•VH‹ˆŞ[X›ÛHËœŞ[X›ÛˆšY[ÈH›[™OTÒUÓÒSˆÚ^™OIØY\İYÚ^™K™›]
Ê_I›Ûİİ˜\X™[‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆYÙÊ¼'äªHÒUÓÒSˆ•VI›Ûİİ˜\X™[ˆ	İËœŞ[X›ÛHˆ
Âˆ‰ÜÚ]ÛÚ[”ÚYÛ˜[›][˜Ú]›Ü›K™[[Úš_Hˆ
Âˆ—		ÊË›\İXØ\ÌWÌ
KÒ[

_RÈXØ\ˆ
Âˆ‰ØY\İYÚ^™K™›]
Ê_HÓÓˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
BˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHVPÕUSÓ—Ğ“ĞÒÑQ[›İ\ˆ^Y\ˆ^Xİ][™ÈŠBˆËÈKKÍÈ8 %š[˜[Ú[[Y›ÜÚ[ÛˆBˆËÈÒUÓÒSˆ[™H
H]]Üš^™\ˆ™Z™Xİ[Û‚ˆËÈ˜XÙJKˆİ\™˜XÙ\È˜[›İ\ˆ^Y\ˆ\È^Z[™ÂˆËÈ\ÈØ[YHZ[ˆÛÈÜ\˜]ÜœÈØ[ˆÙYHÛİˆËÈÛÛ[[ÛˆÚ]İ]Ü™\Z[™ÈXYÈÙÜË‚ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]Jˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ[İÈH˜[ÙKˆ™X\ÛÛˆHUU—ÔPÑH[›İ\‹[[™H[™OTÒUÓÒSˆ‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆËÈ™[X\ÙH]]Üš^™\‹Û[™HØÚÜÈÚ[˜ÙHÙHY‰İ^Xİ]Bˆ™[X\ÙTÚ]ÛÚ[][\ŒÌ
”T“RUĞ“ĞÒÑQ‹™[X\ÙT\›Z]H˜[ÙK™[X\ÙP]]HYJBˆBˆHËÈ[™]]™\İ[š\Ñ^Xİ]X›J
BˆBˆHËÈKKÎLˆÛÜÙH[ÙHÙˆŒÒ\™™Z™Xİ›Ü”Ú]ÛÚ[ˆİX\™
Ü\˜]Üˆ]Y]][H
BˆHËÈKKŒLÎˆÛÜÙH[ÙHÙˆ\PÛÛ\ÙQ]XİYİX\™ˆBˆHËÈÛÜÙH‘Ë\™\]Z\™Y[ÙH
ÒUÓÒSˆKK
BˆHØ]Ú
ØÑ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äªHÔÒUÓÒS—H	İËœŞ[X›ÛHT”“Ôˆ	ÜØÑ^›Y\ÜØYÙ_HŠBˆ™[X\ÙTÚ]ÛÚ[][\ŒÌ
‘VÑTSÓˆŠBˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘Ú]ÛÚ[ˆ]˜[X][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ8¦(;î#ÈHPS’TSUQHšYHX[š\[][Ûˆ[\È™Y›Ü™HH[\ˆËÈ[\œÈÚÙ[œÈÕTˆ^Y\œÈ“ĞÒÎˆ[™\ËØ\Ú˜Y\ËÚ[H[\ÂˆËÈ\™[Z[]H[YH^]8 %X[š\[]ÜœÈÛ‰İØZ]ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKH8 %ŒÈ\È›İÈHY[YH]]Üš]KˆØ[YHŒË\™XYHØ]BˆËÈ\È[ÛÛœÚİ8 %HX[š\[]YÚYÛ˜[İ[™YYÈŒÈ\İ™X[BˆËÈ]ŒÊÑ‘ÈİÛˆ^Xİ][ÛˆÚ[ˆ[˜X›Y‚ˆËÈKKˆ8 %X[š\[]Y[œÈSÓ‘ÔÒQHŒË›İ\ÈHŒÈ˜[˜XÚË‚ˆËÈHÛØ]HJŒÑ[˜X›Y	‰ˆŒÔ™XYJHYX[X[š\[]Y˜Y\RH™]™\‚ˆËÈ˜[ˆ™XØ]\ÙHŒÈ\È[Ø^\È™XYKÚ[[˜Ú[™ÈH[\™H˜Y\‹‚ˆ˜[X[š\[]Y[™P[İÙY\ĞŞXÛHH]ËœÜÚ][Û‹š\ÓÜ[ˆ	‰ˆÚİ[[^S[™Q›ÜŞXÛJË“PS’TSUQ‹ŞXÛTš[X\S[™JBˆYˆ
X[š\[]Y[™P[İÙY\ĞŞXÛH	‰ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RKš\Ñ[˜X›Y

JHÂˆËÈKKŒMNH8 %[Z]S‘WÑUSÛ›HY\ˆš[X\K[[™Hİ\™\ÜÚ[Û‹‚ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ›[™OSPS’TSUQ\\IØÙ™Ëœ\\“[Ù_HXØ\IİË›\İXØ\Ò[

_H\OIİË›\İ\]ZY]U\ÙÒ[

_H[™Tš\ÚÏIİËœØY™]K˜[™Tš\ÚßHØÛÜ™OIİË™[TØÛÜ™_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆHÂˆ˜[X[š\ÚÙ[YÙSZ[]\ÈHYˆ
Ë˜YYÕØ]Ú\İ]ˆ
HÂˆ
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË˜YYÕØ]Ú\İ]
HÈŒÌŒˆH[ÙHŒŒ‚ˆ˜[X[š\[™TİHYˆ
ËœØY™]K˜[™Tš\ÚÈOH’QÒŠHŒˆ[ÙHYˆ
ËœØY™]K˜[™Tš\ÚÈOH“QQUSHŠHLŒˆ[ÙHLŒ‚ˆ˜[X[š\ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK™]˜[X]JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆİ\œ™[šXÙHHËœ™Y‹ˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ[ÛY[[HHË›[ÛY[[HÎˆŒˆ^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ[™TİHX[š\[™TİˆÛİ\˜ÙHHËœÛİ\˜ÙKˆYÙSZ[]\ÈHX[š\ÚÙ[YÙSZ[]\ËˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
B‚ˆYˆ
X[š\ÚYÛ˜[œÚİ[[\ŠHÂˆËÈKKŒLLL8 %UPSUK[Û›HÛÛZ[›Y[]\İ\[ˆ‘Q“Ô‘H‘Ë‚ˆËÈKKŒLL›ØÚÙYPS’T]\‹]HLL™\Üİ[ˆËÈÚİÙYXİ]™H›Û‹TUPSUH‘ÏM‹ˆÈ›İØ[‘ËØ]]Ù^XÂˆËÈÚ[HUPSUK[Û›H\ÈXİ]™NÈ[Z]İ\™\ÜÙY‘È[[Y]HÛ›K‚ˆYˆ
[[YPÛÛ™šYÓİ™\›^Kš\Ó[™Q\ØX›Y
“PS’TSUQŠJHÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËˆËœŞ[X›Ûˆœ]SPS’Tİ\™\ÜÙY]YH™X\ÛÛTUPSUWÓÓ“WÔ‘WÑ‘È‚ˆ
Bˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ”UPSUWÓÓ“WÔ‘WÑ‘×Ğ“ĞÒÑQ‹ˆ›[™OSPS’TSUQŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆBˆËÈKK8 %‘ÈØ]H›ÜˆX[š\]ˆ˜[X[š\™ÈHHÂˆš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËˆØ[™Y]HH[™T]X[YšYY^QXÚ\Ú[ÛŠXÚ\Ú[Û‹“PS’TSUQ‹ÛÛ™šY[˜ÙQ›ÛÜˆHX[š\ÚYÛ˜[›X[š\ØÛÜ™KÑİX›J
K\]ZY]U\ÙHË›\İ\]ZY]U\ÙZ[›Ü”›Ø™HHË›Z[
Kˆ[™TØÛÜ™HHX[š\ÚYÛ˜[›X[š\ØÛÜ™KÑİX›J
KˆÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛHX[š\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈHHÈ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJ“PS’TSUQŠHHØ]Ú
Îˆ^Ù\[ÛŠHÈ[KˆÜXÚX[\İ[™HH“PS’TSUQ‹ˆ
BˆHØ]Ú
™Ñ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'ã«HÓPS’TH‘È\œ›Üˆ	Ù™Ñ^›Y\ÜØYÙ_H8 %›ØÙYY[™È˜Z[[Ü[ˆŠBˆ[ˆBˆËÈKKH8 %[\‘È›Ü™[œÚXÈÛİ[\ˆ›ÜˆPS’T]ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆœ]SPS’TØ[IÛX[š\™ÏË˜Ø[‘^Xİ]J
HÎˆY_H™X\ÛÛIÛX[š\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›‹ØHŸHŠBˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]J›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆ[İÈHX[š\™ÏË˜Ø[‘^Xİ]J
HÎˆYKˆ™X\ÛÛˆHX[š\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›ÚÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆ^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™ÊË›Z[ËœŞ[X›Û“PS’TSUQ‹X[š\™ÏË˜Ø[‘^Xİ]J
HÎˆYKX[š\™ÏË˜›ØÚÔ™X\ÛÛ‹ÚYÛ˜[H•VH‹YÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KØY™]UY\ˆHËœØY™]KY\‹›˜[YK\]ZY]U\ÙHË›\İ\]ZY]U\Ù\™›Ô™X\ÛÛœÈHËœØY™]Kš\™›ØÚÔ™X\ÛÛœË[TØÛÜ™HHË™[TØÛÜ™KÒ[

KÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJKœ›İ]Tİ]\ËÚÙ[“X\Y˜][ÛÛÛ\]HHËÚÙ[“X\šY˜][ÛÛÛ\]KÚÙ[“X\^XİYİ]HËÚÙ[“X\™^XİYİ][[İ[ÚÙ[“X\›İšY\][\ÈHËÚÙ[“X\œ›İšY\][\ÊBˆËÈKKLH8 %‘È[Ù[]\ËÙ\È›İ\™ZÚ[X[š\ÚYÛ˜[Âˆ˜[X[š\™ÔİXİ\˜[HX[š\™ÈOH[	‰ˆ[X[š\™Ë˜Ø[‘^Xİ]J
H	‰‚ˆX[š\™Ë˜›ØÚÔ™X\ÛÛË›]È]˜ÛÛZ[œÊ“TURQUHŠH]˜ÛÛZ[œÊ“SÔ•Q×Ô“ĞP’SUHŠH]˜ÛÛZ[œÊÓÔWÕQHŠH]˜ÛÛZ[œÊ‘SQT‘ÑSÖWÔÕÔŠHHOHYBˆ˜[X[š\™Ô›Ø™HHX[š\™ÈOH[	‰ˆ[X[š\™Ë˜Ø[‘^Xİ]J
H	‰ˆ[X[š\™ÔİXİ\˜[ˆYˆ
X[š\™ÔİXİ\˜[
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘ÈÕ•PÕTS“ĞÒÈÛˆPS’Tˆ	İËœŞ[X›ÛH	ÛX[š\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
“PS’TÑ‘È‹X[š\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŠBˆH[ÙHÂˆYˆ
X[š\™Ô›Ø™JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨;î#È‘ÈÒV‘KT‘QPÑHÛˆPS’Tˆ	İËœŞ[X›ÛH	ÛX[š\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×ØØ]][ÛˆŸH›Ø™H˜YHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
“PS’TÑ‘×Ô“Ğ‘H‹X[š\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×ØØ]][ÛˆŠBˆBˆ˜[X[š\]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØÛÜ™HHX[š\ÚYÛ˜[›X[š\ØÛÜ™KˆÛÛ™šY[˜ÙHHMKŒˆ]X[]HH“PS’TSUQ‹ˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË“PS’TSUQËÈK‹ˆ\ÙHPS’TSUQ›ÛÚÈÈ\\ÜÈYØÚXÚÂˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Ë›Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛHX[š\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆ
B‚ˆYˆ
[X[š\]]™\İ[š\Ñ^Xİ]X›J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦(;î#ÈÓPS’TH	İËœŞ[X›ÛH	ÚYˆ
X[š\]]™\İ[š\ÔÚYİÓÛ›J
JH”ÒQÕ×ÓÓ“Hˆ[ÙH”‘R‘PÕQŸH	ÛX[š\]]™\İ[œ™X\ÛÛŸHŠBˆYˆ
[X[š\]]™\İ[š\ÔÚYİÓÛ›J
JH™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
“PS’T‹X[š\]]™\İ[œ™X\ÛÛŠBˆH[ÙHÂˆ˜[X[š\][\YHX[š\]]™\İ[˜][\Yˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦(;î#ÈÓPS’TH	İËœŞ[X›ÛHS•Tˆˆ
ÂˆœØÛÜ™OIÛX[š\ÚYÛ˜[›X[š\ØÛÜ™_Hˆ
Âˆ˜[™OIÛX[š\[™TİÒ[

_IHˆ
Âˆ˜œIİË›\İ^T™\Üİ\™TİÒ[

_IHˆ
Âˆ›[ÛOIÊË›[ÛY[[HÎˆŒ
KÒ[

_IHˆ
ÂˆœÚ^™OIÔİš[™Ë™›Ü›X]
‰Kˆ‹X[š\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛ
_HÓÓˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸHŠB‚ˆ˜[X[š\Ü[™YH^Xİ]Ü‹œÚ]ÛÚ[^JˆÈHËˆÚ^™TÛÛHX[š\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆËÈKŒŒM8 %X]ÚX[š\[]Y˜Y\RHXÚY]˜X›HÙ[ÛY]K‚ˆZÙT›Ùš]İHMŒˆİÜÜÜÔİHLLKŒˆØ[]HØ[]ˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ][˜Ú]›Ü›HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™]Xİ]›Ü›JËœÛİ\˜ÙJKˆš\ÚÓ]™[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK”š\ÚÓ]™[‘V‘SQKˆš[˜[]T™XÚXÚÙYHYKˆ][\YHX[š\][\Yˆ[TØÛÜ™HHX[š\ÚYÛ˜[›X[š\ØÛÜ™Kˆ[PÛÛ™šY[˜ÙHHX[š\ÚYÛ˜[›X[š\ØÛÜ™Kˆ^Xİ][Û“[™HH“PS’TSUQ‹ˆ
B‚ˆˆYˆ
[X[š\Ü[™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹“PS’TSUQ	İËœŞ[X›ÛH•VWÓ“ÕÓÔS‘Q™[X\ÙH]]Ü\›Z]È›È[™H™YÚ\İ˜][ÛˆŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ“S‘WĞ•VWÓ“ÕÓÔS‘QÔ‘SPTÑQ‹›[™OSPS’TSUQŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[“PS’TSUQ‹•VWÓ“ÕÓÔS‘QŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
HHØ]Ú
Îˆ›İØX›JHßBˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•VWÓ“ÕÓÔS‘Q‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË“PS’TSUQ
HHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆBˆ˜[XİX[X[š\[HHËœÜÚ][Û‹™[TšXÙKZÙRYˆÈ]ˆHÎˆËœ™Y‚ˆYˆ
ËœÜÚ][Û‹š\ÓÜ[ŠHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK˜YÜÚ][ÛŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK“X[š\[]YÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHXİX[X[š\[Kˆ[TÛÛHX[š\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆ[U[YHHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KˆËÈKŒŒM8 %X]ÚX[š\[]Y˜Y\RIÜÈXÚY]˜X›BˆËÈ›İ[˜ÙHÙ[ÛY]Kˆİ[HKËMH\™Hİ™\œ›ÙHHÛ\ÜË[]™[ˆËÈMËLLHš^[™Ø]\ÙY[\ÜÜÚX›H\™Ù]È›Ú\ÙK]YÚİÜË‚ˆZÙT›Ùš]İHMŒˆİÜÜÜÔİHLLKŒˆX[š\ØÛÜ™HHX[š\ÚYÛ˜[›X[š\ØÛÜ™Kˆ[™TİHX[š\[™Tİˆ^T™\Üİ\™HHË›\İ^T™\Üİ\™Tİˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
Bˆ
BˆˆËÈK‹’Vˆ›İYHŒÈ^Üİ\™HİX\™ÂˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYHËœÜÚ][Û‹š\ÓÜ[ŠHÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][Û“Ü[™Y
Ë›Z[
B‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙHH“PS’TSUQ‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¸¦(;î#È‚‚ˆYÙÊ¸¦(;î#ÈPS’Tˆ	İËœŞ[X›ÛHØÛÜ™OIÛX[š\ÚYÛ˜[›X[š\ØÛÜ™_Hˆ
Âˆ•
ÌIHÓMIHZ[ˆX^ˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
BˆBˆBˆHËÈÛÜÙH‘Ë\™\]Z\™Y[ÙH
PS’TKK
BˆHØ]Ú
X[š\^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¸¦(;î#ÈÓPS’TH	İËœŞ[X›ÛHT”“Ôˆ	ÛX[š\^›Y\ÜØYÙ_HŠBˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘X[š\[]Y˜Y\RH]˜[X][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'äª|'æ ˆÒUÓÒSˆV‘TÔÈH]ZXÚÈ[ÛY[[HšY\È›ÜˆÌ	JÈ›Ùš]ÂˆËÈÛ›H]˜[X]\ÈÚÙ[œÈ]\™HS‘PQH[\[™È\™ˆËÈKŒˆ’Vˆ]\İÚXÚÈYˆ™X\İ\H[™XYH\ÈHÜÚ][ÛˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKŒNM8 %V‘TÔÈPT“HUUËTUTÑHĞUH
Ü\˜]Üˆ
KˆZ\œ›ÜœÂˆËÈHPS’TSUQØ]H]›İÙ\šXÙNLŒMˆ
ÈÚÙ[”ØY™]PÚXÚÙ\Ë‚ˆËÈš[ÜˆZ[ÈÛ›H[™›Ü˜ÙYH]\ÙH[œÚYHš[˜[XÚ\Ú[Û‘Ø]KˆËÈÚXÚYX[V‘TÔÈİ[\›™YÔHÛˆÚ]ÛÚ[‘^™\ÜË™]˜[X]BˆËÈ[™\‹]ÚÙ[ˆØÛÜ™HÛÜšÈ]™[ˆÚ[ˆH[™HØ\È]\ÙYˆ[İ™BˆËÈHÚXÚÈÈHÜÙˆHV‘TÔÈ]ÛÈ]\ÙY[™\ÈÛÜİˆËÈ\È›İ[™Ë‚ˆYˆ
[™P]]Ô]\ÙQİX\™š\Ô]\ÙY
‘V‘TÔÈŠJHÂˆ˜[]›İÈHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K›X\›š[™Ë•XİXÔİÚ]Ú\‹œ›İ]Q›Ü“[™T™\Üİ\™J‘V‘TÔÈ‹Ë™[TØÛÜ™KÒ[

K™^™\Ü×Ü]\ÙHŠK›˜[YHHØ]Ú
Îˆ›İØX›JHÈ•S’Ó“ÕÓˆˆBˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ‘V‘TÔ×ÓS‘WÕPÕP×ÔU“ÕÍÈ‹œŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HXİXÏI]›İÈXİ[ÛXÛÛ[YWÜØ[YWÛ[™HŠBˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê‘V‘TÔ×ÓS‘WÕPÕP×ÔU“ÕÍÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆBˆ˜[^™\ÜÓ[™P[İÙY\ĞŞXÛHH]ËœÜÚ][Û‹š\ÓÜ[ˆ	‰ˆÚİ[[^S[™Q›ÜŞXÛJË‘V‘TÔÈ‹ŞXÛTš[X\S[™JBˆYˆ
^™\ÜÓ[™P[İÙY\ĞŞXÛH	‰ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜËš\Ñ[˜X›Y

JHÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ›[™OQV‘TÔÈ\\IØÙ™Ëœ\\“[Ù_HXØ\IİË›\İXØ\Ò[

_H\OIİË›\İ\]ZY]U\ÙÒ[

_HØÛÜ™OIİË™[TØÛÜ™_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆËÈKËˆ^™\ÜÈ[œÈ[™\[™[K]KŒŒÎMÈ›İ]\È]›İYÚˆËÈHÚ\™Y›İ[™Y[™HØ]HÛÈÛİ™\˜YÙH™\ÜÈ›İ™H]\È[]™K‚ˆHÂˆ˜[ÚÙ[YÙSZ[]\ÈHYˆ
Ë˜YYÕØ]Ú\İ]ˆ
HÂˆ
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË˜YYÕØ]Ú\İ]
HÈŒÌŒˆH[ÙHŒŒˆˆËÈK‹ŒYˆİÙ\™Y™KYš[\ˆÈX]ÚÚ]ÛÚ[‘^™\ÜËšİ
Ø\ÈIKÍMIK›İÈÉKÍL	JBˆËÈKKŒLMÎˆ›ZY›Ûİİ˜\Ø]\È8 %Z\œ›ÜˆÚ]ÛÚ[‘^™\ÜÉÜÈİÛ‚ˆËÈ›ZYØ]\ÈÛÈœ™\Ú[][˜ÚÚÙ[œÈÚ]›È[ÛY[[H\İÜBˆËÈ]İ›Û™È^H™\Üİ\™Hİ[™XXÚ]˜[X]J
K‚ˆ˜[[ÛY[[HHË›[ÛY[[HÎˆŒˆ˜[^™\ÜÓX\›š[™ÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]X\›š[™Ô›ÙÜ™\ÜÊ
Bˆ˜[^™\ÜÓZ[“[ÛHH
KŒ
È^™\ÜÓX\›š[™È
ˆ‹Œ
K˜ÛÙ\˜ÙR[ŠKŒËŒ
BˆËÈKKˆ8 %İÙ\ˆ›ÛÜˆx¡¤ÛÈÚÙ[œÈÚ]KML	H^H™\Üİ\™BˆËÈ
H[\™[ˆÔÈY˜][˜[™
H\ÜÈH™KYš[\ˆ[™™XXÚ]˜[X]J
K‚ˆ˜[^™\ÜÓZ[^TH
Œ
È^™\ÜÓX\›š[™È
ˆLŒ
K˜ÛÙ\˜ÙR[ŠŒLŒ
BˆËÈKKˆ8 %İÙ\ˆ›ŞH™\ÚÛx¡¤MIK‚ˆËÈ[\™[ˆÔÈÚÙ[œÈ\œš]™HÚ]›ÈØ[™H\İÜHÛÈ[ÛY[[O[[ˆËÈ[™\İ^T™\Üİ\™Tİİ^\È]HÛÛœİXİÜˆY˜][
LŒ
K‚ˆËÈHÛIH›ÛÜˆYX[]™\H[\Ü[ÚÙ[ˆÛİY™™Xİ]™S[ÛOLˆËÈ[™]HV‘TÔÈÒÒTØ]KˆMIH]ÈHXZ›Üš]HÙˆœ™\ÚˆËÈ][˜Ú\ÈŞ[\Ú\ÙHHZ[š[X[[ÛY[[H™XYœ›ÛH^H™\Üİ\™K‚ˆËÈKKŒMH8 %PQPS‘’V
Z\œ›ÜˆÙˆÚ]ÛÚ[‘^™\ÜÊNˆ›ŞBˆËÈ›ÛÜˆMHOˆLÛÈÔËYY˜][
LŒ
HÚÙ[œÈŞ[\Ú\ÙH[ÛY[[BˆËÈ[™™XXÚ]˜[X]J
H[œİXYÙˆ™Z[™È™KYš[\™Y[ÈÚ[[˜ÙK‚ˆ˜[Y™™Xİ]™Q^™\ÜÓ[ÛHHYˆ
[ÛY[[HHŒ	‰ˆË›\İ^T™\Üİ\™TİHLŒ
HÂˆ
Ë›\İ^T™\Üİ\™TİHKŒ
K˜ÛÙ\˜ÙP]X\İ
^™\ÜÓZ[“[ÛJBˆH[ÙH[ÛY[[BˆËÈKKŒˆZ\œ›Üˆ[ÛÛœÚİ	ÜÈXØ\[šÛ›İÛ]\H\\ÜÈ8 %ˆËÈœ™\Ú[\™[ˆÚÙ[œÈ\œš]™HÚ]\İXØ\OL™Y›Ü™HBˆËÈš\œİXØ\™]Ú[™ÎÈİ[[İÈYˆ\]ZY]HH	RË‚ˆËÈKKŒNˆ˜Z\ÙY^™\ÜÈXØ\ÙZ[[™È	ÌÈ8¡¤ˆ	SH8 %™[™[™ÈY[Y\ÈÙ[ˆLËLÓBˆ˜[^™\ÜÒ[“XØ\˜[™ÙHHË›\İXØ\[ˆWÌŒ‹WÌÌŒËÈKKŒMLˆ’ËOŒRËX]ÚÚ]ÛÚ[‘^™\ÜÈ›ÛÜˆ
XY\]ZY]š^
Bˆ˜[^™\ÜÕ[šÛ›İÛ“XØ\ÚÈHË›\İXØ\HŒ	‰ˆË›\İ\]ZY]U\ÙHWÌŒˆ˜[\ÜÙ\Ô™Qš[\ˆH
^™\ÜÒ[“XØ\˜[™ÙH^™\ÜÕ[šÛ›İÛ“XØ\ÚÊH	‰‚ˆY™™Xİ]™Q^™\ÜÓ[ÛHH^™\ÜÓZ[“[ÛH	‰ˆË›\İ^T™\Üİ\™TİH^™\ÜÓZ[^TˆˆYˆ
\\ÜÙ\Ô™Qš[\ŠHÂˆËÈKKŒLMˆ›İYXYÛ›ÜİXÈÛÈH\Ù\ˆØ[ˆÙYHÒBˆËÈ^™\ÜÈ™]™\ˆ]X[YšY\È[œİXYÙˆÚ[[ÚÚ\‚ˆ˜[XØ\HË›\İXØ\Ò[

Bˆ˜[™X\ÛÛˆHÚ[ˆÂˆY^™\ÜÒ[“XØ\˜[™ÙH	‰ˆY^™\ÜÕ[šÛ›İÛ“XØ\ÚÈO‚ˆYˆ
Ë›\İXØ\HŒ
H›XØ\][šÛ›İÛˆ\OI	İË›\İ\]ZY]U\ÙÒ[

_H	RÈ‚ˆ[ÙHYˆ
Ë›\İXØ\WÌ
H›XØ\W		XØ\	RÈ‚ˆ[ÙH›XØ\W		XØ\ˆ	ÌÈ‚ˆY™™Xİ]™Q^™\ÜÓ[ÛH^™\ÜÓZ[“[ÛHOˆ›[ÛOIÙY™™Xİ]™Q^™\ÜÓ[ÛK™›]
J_IH	Ù^™\ÜÓZ[“[ÛK™›]
J_IH
X\›š[™ÏIÊ^™\ÜÓX\›š[™ÊŒL
KÒ[

_IJH‚ˆË›\İ^T™\Üİ\™Tİ^™\ÜÓZ[^TOˆ˜^TIİË›\İ^T™\Üİ\™TİÒ[

_IH	Ù^™\ÜÓZ[^TÒ[

_IH‚ˆ[ÙHOˆ[šÛ›İÛˆ‚ˆBˆÙÓ^Y\”ÚÚ\
¼'äª|'æ ˆV‘TÔÈ‹ËœŞ[X›ÛË›Z[™X\ÛÛŠBˆH[ÙHÂˆ˜[\Õ™[™[™ÈHËœÛİ\˜ÙK˜ÛÛZ[œÊ•‘S‘S‘È‹YÛ›Ü™PØ\ÙHHYJBˆ˜[\Ğ›ÛÜİYHËœÛİ\˜ÙK˜ÛÛZ[œÊ“ÓÔÕQ‹YÛ›Ü™PØ\ÙHHYJBˆˆËÈØ[İ[]HHZ[ˆšXÙHÚ[™ÙBˆ˜[\İ\İÜQ[HHËš\İÜK›\İÜ“[

Bˆ˜[šXÙPÚ[™ÙMSZ[ˆHYˆ
\İ\İÜQ[HOH[	‰ˆ\İ\İÜQ[KœšXÙU\Ùˆ
HÂˆ

Ëœ™YˆH\İ\İÜQ[KœšXÙU\Ù
HÈ\İ\İÜQ[KœšXÙU\Ù
ˆL
K˜ÛÙ\˜ÙR[ŠMLŒLŒ
BˆH[ÙHŒˆˆ˜[^™\ÜÔÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË™]˜[X]JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆİ\œ™[šXÙHHËœ™Y‹ˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ[ÛY[[HHY™™Xİ]™Q^™\ÜÓ[ÛKËÈKKŒLMÎˆ\ÙHŞ[\Ú^™Y›Ûİİ˜\[ÛY[[Bˆ^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ›Û[YPÚ[™ÙHHKKËÈY˜][\İ[X]BˆšXÙPÚ[™ÙMSZ[ˆHšXÙPÚ[™ÙMSZ[‹ˆ\Õ™[™[™ÈH\Õ™[™[™Ëˆ\Ğ›ÛÜİYH\Ğ›ÛÜİYˆÚÙ[YÙSZ[]\ÈHÚÙ[YÙSZ[]\Ëˆ
BˆˆYˆ
Y^™\ÜÔÚYÛ˜[œÚİ[šYJHÂˆËÈKKŒLMˆ›İYXYÛ›ÜİXÈ›ÜˆØÛÜ™K\İYÙH™Z™Xİ[ÛœË‚ˆÙÓ^Y\”ÚÚ\
¼'äª|'æ ˆV‘TÔÈ‹ËœŞ[X›ÛË›Z[^™\ÜÔÚYÛ˜[œ™X\ÛÛŠBˆBˆYˆ
^™\ÜÔÚYÛ˜[œÚİ[šYJHÂˆËÈKKŒLLL8 %UPSUK[Û›HÛÛZ[›Y[]\İ\[ˆ‘Q“Ô‘H‘Ë‚ˆËÈKKŒLL›ØÚÙYV‘TÔÈ]\‹]HLL™\Üİ[ˆËÈÚİÙYXİ]™H›Û‹TUPSUH‘ÏMMKˆÈ›İØ[‘ËØ]]Ù^XÂˆËÈÚ[HUPSUK[Û›H\ÈXİ]™NÈ[Z]İ\™\ÜÙY‘È[[Y]HÛ›K‚ˆYˆ
[[YPÛÛ™šYÓİ™\›^Kš\Ó[™Q\ØX›Y
‘V‘TÔÈŠJHÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËˆËœŞ[X›Ûˆœ]QV‘TÔÈİ\™\ÜÙY]YH™X\ÛÛTUPSUWÓÓ“WÔ‘WÑ‘È‚ˆ
Bˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ”UPSUWÓÓ“WÔ‘WÑ‘×Ğ“ĞÒÑQ‹ˆ›[™OQV‘TÔÈŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆBˆËÈKK8 %‘ÈØ]H›Üˆ^™\ÜÈ]ˆ˜[^™\ÜÑ™ÈHHÂˆš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËˆØ[™Y]HH[™T]X[YšYY^QXÚ\Ú[ÛŠXÚ\Ú[Û‹‘V‘TÔÈ‹ÛÛ™šY[˜ÙQ›ÛÜˆH^™\ÜÔÚYÛ˜[˜ÛÛ™šY[˜ÙH
ˆLŒ\]ZY]U\ÙHË›\İ\]ZY]U\ÙZ[›Ü”›Ø™HHË›Z[
Kˆ[™TØÛÜ™HH^™\ÜÔÚYÛ˜[˜ÛÛ™šY[˜ÙKÑİX›J
KËÈ[LLˆÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛH^™\ÜÔÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈHHÈ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJ‘V‘TÔÈŠHHØ]Ú
Îˆ^Ù\[ÛŠHÈ[KˆÜXÚX[\İ[™HH‘V‘TÔÈ‹ˆ
BˆHØ]Ú
™Ñ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'æ ˆÑV‘TÔ×H‘È\œ›Üˆ	Ù™Ñ^›Y\ÜØYÙ_H8 %›ØÙYY[™È˜Z[[Ü[ˆŠBˆ[ˆBˆËÈKKH8 %[\‘È›Ü™[œÚXÈÛİ[\ˆ›ÜˆV‘TÔÈ]ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆœ]QV‘TÔÈØ[IÙ^™\ÜÑ™ÏË˜Ø[‘^Xİ]J
HÎˆY_H™X\ÛÛIÙ^™\ÜÑ™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›‹ØHŸHŠBˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]J›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆ[İÈH^™\ÜÑ™ÏË˜Ø[‘^Xİ]J
HÎˆYKˆ™X\ÛÛˆH^™\ÜÑ™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›ÚÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆYˆ
^™\ÜÑ™ÈOH[	‰ˆY^™\ÜÑ™Ë˜Ø[‘^Xİ]J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘È‘UÈÛˆV‘TÔÎˆ	İËœŞ[X›ÛH	Ù^™\ÜÑ™Ë˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘V‘TÔ×Ñ‘È‹^™\ÜÑ™Ë˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŠBˆH[ÙHÂˆËÈKKŒMMÌ8 %^™\ÜÈ‘È™\™Xİ]\İ™HÜš][ˆ™Y›Ü™BˆËÈ˜YP]]Üš^™\‹Ñ^Xİ]X›SÜ[‘Ø]Hš[˜[]KˆH™Í™ÌØBˆËÈÙÈÚİÙY‘È]QV‘TÔÈØ[]YH[[YYX][H›ÛİÙYˆËÈHV‘TÔÈš[˜[]WÙ^X×ÛÜ[—Ù›ÜYÜ™WÙ™×Û›İØ^WİØ]ÚˆËÈÛZ[˜][™È™Z™Xİİ]È
ŒÌKÍÍŠKˆØ]\ÙNˆ^™\ÜÈØ[YˆËÈ‘È›Üˆ[[Y]H]™]™\ˆ™XÛÜ™™Ê
KÛÈš[˜[]H™XYˆËÈHÛŒÈĞUÒİ]KˆÜš]HH^Xİ]X›H™\™Xİ›İË‚ˆHÂˆ^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™ÊˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆËÈKŒ8 %™\Ù\™HH[™HY[]HÜ™X]YBˆËÈH^™\ÜÈÜXÚX[\İˆHÜšYÚ[˜[MMÌ™\Z\‚ˆËÈXØÚY[[HÙX[YH‘È[[\ÈÒUÓÒS‹ˆËÈÚ[H˜YP]]Üš^™\ˆ[™H^Xİ]Üˆ™\]Y\İYˆËÈV‘TÔÈ™[İËˆš[˜[š[™ÛÜœ™XİH™Y\ÙY]ˆËÈÛÛ˜YXİÜH\H\ÈHZ\ÜÚ[™È[[]]X›H[[‚ˆ[™HH‘V‘TÔÈ‹ˆØ[‘^Xİ]HH^™\ÜÑ™ÏË˜Ø[‘^Xİ]J
HÎˆYKˆ™X\ÛÛˆH^™\ÜÑ™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ‘V‘TÔ×ÓÒÈ‹ˆÚYÛ˜[H•VH‹ˆYÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆØY™]UY\ˆHËœØY™]KY\‹›˜[YKˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\™›Ô™X\ÛÛœÈH[\S\İ

Kˆ™Q™Õ™\™XİHYˆ
^™\ÜÑ™ÏË˜Ø[‘^Xİ]J
HOH˜[ÙJH““×Ğ•VHˆ[ÙH•VH‹ˆ[TØÛÜ™HH^™\ÜÔÚYÛ˜[™\İ[X]YØZ[”İÒ[

KˆÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJKœ›İ]Tİ]\ËˆÚÙ[“X\Y˜][ÛÛÛ\]HHËÚÙ[“X\šY˜][ÛÛÛ\]KˆÚÙ[“X\^XİYİ]HËÚÙ[“X\™^XİYİ][[İ[ˆÚÙ[“X\›İšY\][\ÈHËÚÙ[“X\œ›İšY\][\Ëˆ
BˆHØ]Ú
Îˆ›İØX›JHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹‘V‘TÔÈ™XÛÜ™™È˜Z[Yˆ	İË›Y\ÜØYÙ_H8 %ÛÛ[Z[™ÈÈ]]ŠBˆBˆËÈÙX[H^XİÚ^™H™Y›Ü™H]]Üš^˜][Û‹ˆHØ[YH˜[YBˆËÈ\È\ÜÙY[˜Ú[™ÙYÈH^Xİ]Üˆ™[İË‚ˆ˜[^™\ÜÑš[˜[Ú^™HH^™\ÜÑ™ÏËœÚ^™TÛÛˆÎˆ^™\ÜÔÚYÛ˜[œÜÚ][Û”Ú^™TÛÛ˜ÛÙ\˜ÙP]X\İ
ŒJBˆËÈKŒˆUTÕÚXÚÈ˜YP]]Üš^™\ˆ‘Q“Ô‘H[H^Xİ][Û‚ˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØÛÜ™HH^™\ÜÔÚYÛ˜[™\İ[X]YØZ[”İÒ[

KˆÛÛ™šY[˜ÙHHŒŒËÈ^™\ÜÈšY\È\™H[ÛY[[H^\Âˆ]X[]HH‘V‘TÔÈ‹ˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË‘V‘TÔËˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Ë›Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛH^™\ÜÑš[˜[Ú^™Kˆ
BˆYˆ
X]]™\İ[š\Ñ^Xİ]X›J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äª|'æ ˆÑV‘TÔ×H	İËœŞ[X›ÛH	ÚYˆ
]]™\İ[š\ÔÚYİÓÛ›J
JH”ÒQÕ×ÓÓ“Hˆ[ÙH”‘R‘PÕQŸH	Ø]]™\İ[œ™X\ÛÛŸHŠBˆYˆ
X]]™\İ[š\ÔÚYİÓÛ›J
JH™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘V‘TÔÈ‹]]™\İ[œ™X\ÛÛŠBˆH[ÙHÂˆ˜[^™\ÜĞ][\YH]]™\İ[˜][\YˆËÈKKŒMMÍ8 %^™\ÜÈ]\İØ™^H‘ÉÜÈX\›™YÚ^™K‚ˆËÈ[[YHÙÈŒMHÚİÙY‘×ÔÓPÖHZXÜ›Ë\Ú^š[™ÈÒUÓÒS‚ˆËÈÈŒL]^™\ÜÈİ[^Xİ]YØ›Ø\™Y]˜]ÂˆËÈ^™\ÜÔÚYÛ˜[œÜÚ][Û”Ú^™TÛÛLŒËˆ]\\ÜÙYˆËÈ[™TÛXŞK[™Ù\ˆXÚÙ]Ë˜]ÙİÛˆÚ\˜İZ][™“Ğ‘WÓÓ“BˆËÈ\İÚ^š[™Ëˆœ›ÛH\™HİÛ‹\ÙHHš[˜[‘ÈÚ^™K‚ˆËÈKŒLÈ8 %Yˆ‘È^\İËØ™^H]Èš[˜[Ú^™H^XİK‚ˆËÈÈ›İØ\™\İÜ™YØÛÜ™H‘ÈÚ^™H˜XÚÈİÛˆÈ˜]È^™\ÜÈÚYÛ˜[Ú^™K‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äª|'æ ˆÑV‘TÔ×H	İËœŞ[X›ÛH’QHˆ
Âˆ‰Ù^™\ÜÔÚYÛ˜[œšYU\K™[[Úš_H	Ù^™\ÜÔÚYÛ˜[œšYU\K›˜[Y_Hˆ
Âˆ›[ÛOIÊË›[ÛY[[HÎˆŒ
K™›]
J_IHˆ
ÂˆœÚ^™OIÙ^™\ÜÑš[˜[Ú^™K™›]
Ê_HÓÓ
˜]ÏIÙ^™\ÜÔÚYÛ˜[œÜÚ][Û”Ú^™TÛÛ™›]
Ê_JHˆ
Âˆ\™Ù]IÙ^™\ÜÔÚYÛ˜[™\İ[X]YØZ[”İÒ[

_IHŠBˆˆËÈ^Xİ]H^Hš\œİ8 %Û›H›Ø\™HšYHYˆH^HXİX[HÜ[™Yˆ˜[^™\ÜÓÜ[™YH^Xİ]Ü‹œÚ]ÛÚ[^JˆÈHËˆÚ^™TÛÛH^™\ÜÑš[˜[Ú^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆZÙT›Ùš]İH^™\ÜÔÚYÛ˜[™\İ[X]YØZ[”İˆİÜÜÜÔİHNŒˆØ[]HØ[]ˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ][˜Ú]›Ü›HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™]Xİ]›Ü›JËœÛİ\˜ÙJKˆš\ÚÓ]™[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK”š\ÚÓ]™[‘V‘SQKˆš[˜[]T™XÚXÚÙYHYKˆ][\YH^™\ÜĞ][\Yˆ[TØÛÜ™HH^™\ÜÔÚYÛ˜[˜ÛÛ™šY[˜ÙKˆ[PÛÛ™šY[˜ÙHH^™\ÜÔÚYÛ˜[˜ÛÛ™šY[˜ÙKˆ^Xİ][Û“[™HH‘V‘TÔÈ‹ˆ
BˆYˆ
Y^™\ÜÓÜ[™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹‘V‘TÔÈ	İËœŞ[X›ÛH•VWÓ“ÕÓÔS‘Q™[X\ÙH]]Ü\›Z]È›È[™H™YÚ\İ˜][ÛˆŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ“S‘WĞ•VWÓ“ÕÓÔS‘QÔ‘SPTÑQ‹›[™OQV‘TÔÈŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[‘V‘TÔÈ‹•VWÓ“ÕÓÔS‘QŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
HHØ]Ú
Îˆ›İØX›JHßBˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•VWÓ“ÕÓÔS‘Q‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË‘V‘TÔÊHHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚‚ˆËÈKŒŒŒÈ8 %]™H^\ÈØ[ˆ™H[™[™Õ™\šYO]YBˆËÈÚ[H\ÓÜ[Y˜[ÙKˆ›Ø\™^™\ÜÈİ]H›Üˆ›İ\\‚ˆËÈ]HÜ[œÈ[™]™H›ÛÙ‹\[™[™ÈÜ[œÈÛÈÚXÚÑ^]

H\ÂˆËÈ›İ›[™Y\ˆHİXØÙ\ÜÙ[]™H^K‚ˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYHËœÜÚ][Û‹š\ÓÜ[ŠHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË˜›Ø\™šYJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKZÙRYˆÈ]ˆŒHÎˆËœ™Y‹ˆ[TÛÛH^™\ÜÑš[˜[Ú^™Kˆ[ÛY[[HHË›[ÛY[[HÎˆŒˆ^T™\Üİ\™HHË›\İ^T™\Üİ\™Tİˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
BˆˆYÙÊ¼'äª|'æ ˆV‘TÔÎˆ	İËœŞ[X›ÛH	Ù^™\ÜÔÚYÛ˜[œšYU\K™[[Úš_Hˆ
Âˆ\™Ù]
ÉÙ^™\ÜÔÚYÛ˜[™\İ[X]YØZ[”İÒ[

_IHˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
BˆBˆBˆBˆHËÈÛÜÙH‘Ë\™\]Z\™Y[ÙH
V‘TÔÈKK
BˆHØ]Ú
^^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äª|'æ ˆÑV‘TÔ×H	İËœŞ[X›ÛHT”“Ôˆ	Ù^^›Y\ÜØYÙ_HŠBˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘Ú]ÛÚ[ˆ^™\ÜÈ]˜[X][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'ã«È“Ò‘PÕÓ’TTˆHÛš\Hœ™\Ú][˜Ú\ÂˆËÈK‹ŒYˆ™]È^Y\ˆ›ÜˆØ]Ú[™È][˜Ú[\ÂˆËÈKKÍÎH8 %SQT‘ÑS•QSQKSÓ“Nˆ\™Ü[]™[Ø]K‚ˆËÈÜ\˜]Üˆ›Ü™[œÚXÜÈKŒŒÌNˆÛš\\ˆÙ\Ü[š[™ÈZ\ÜÚ[ÛœÂˆËÈ[™Üš][™È\\ˆ^\ÈÚ[H\Ù\ˆYÛ›HY[YH[˜X›Y[‚ˆËÈU‘H[ÙKˆÙH›İÈ™Y\ÙHÈ[\ˆHÛš\\ˆ›ØÚÈ[›\ÜÂˆËÈ“Ò‘PÕÔÓ’TTˆ\È[ˆ[˜X›Y˜Y\]]Üš]IÜÈÙ]ÔˆÙIÜ™BˆËÈ[ˆTTˆ[ÙHÚ\™HHÜ\˜]ÜˆX^H^XÚ]HÜZ[‹‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[Ûš\\[İÙYHÛÛK›Y™XŞXÛX›İ™[™Ú[™K‘[˜X›Y˜Y\]]Üš]Kš\Ñ[˜X›Y
ˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K‘[˜X›Y˜Y\]]Üš]K•˜Y\‹”“Ò‘PÕÔÓ’TT‚ˆ
Bˆ˜[›Ú™XİÛš\\“Z\ÜÚ[Û“Ü[NNHHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RKš\ÓZ\ÜÚ[ÛŠË›Z[
Bˆ˜[›Ú™XİÛš\\‘[P[İÙYNNHH]ËœÜÚ][Û‹š\ÓÜ[ˆ	‰ˆÚİ[[^S[™Q›ÜŞXÛJË”“Ò‘PÕÔÓ’TTˆ‹ŞXÛTš[X\S[™JH	‰ˆT[[YPÛÛ™šYÓİ™\›^Kš\Ó[™Q\ØX›Y
”“Ò‘PÕÔÓ’TTˆŠBˆ˜[›Ú™XİÛš\\“[™P[İÙY\ĞŞXÛMÈH›Ú™XİÛš\\“Z\ÜÚ[Û“Ü[NNH›Ú™XİÛš\\‘[P[İÙYNNBˆËÈKKLŒ8 %“Ò‘PÕÔÓ’TTˆS‘WÑUS[Z]
™Y›Ü™HÛš\\[İÙYØ]BˆËÈÛÈœ˜Z[ˆÙY\ÈÚÚ\ÈYHÈ[ÙKÜ\›Z]ÛÊK‚ˆYˆ
›Ú™XİÛš\\‘[P[İÙYNNJHÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ›[™OT“Ò‘PÕÔÓ’TTˆ\\IØÙ™Ëœ\\“[Ù_HXØ\IİË›\İXØ\Ò[

_H\OIİË›\İ\]ZY]U\ÙÒ[

_HØÛÜ™OIİË™[TØÛÜ™_HÛš\\[İÙYIÛš\\[İÙYÚ[™ÛQØ]MÏ]YH‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆBˆYˆ
›Ú™XİÛš\\“[™P[İÙY\ĞŞXÛMÈ	‰ˆÛš\\[İÙY
HÂˆËÈÚXÚÈYˆÙH[™XYH]™HHÛš\\ˆZ\ÜÚ[ÛˆÛˆ\ÈÚÙ[‚ˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RKš\ÓZ\ÜÚ[ÛŠË›Z[
JHÂˆËÈÚXÚÈ^]ÛÛ™][ÛœÂˆHÂˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RK˜ÚXÚÑ^]
ˆË›Z[Ëœ™Y‹Ë›\İ^T™\Üİ\™Tİˆ
BˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚÈ›ÜˆÛš\\‹‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[”Ûš\\‰Ù^]ÚYÛ˜[œ˜[šË›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆYˆ
^]ÚYÛ˜[œÚİ[^]
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã«ÈÔÓ’TT—H	İËœŞ[X›ÛHVUˆ
Âˆ‰Ù^]ÚYÛ˜[œ˜[šË™[[Úš_H	Ù^]ÚYÛ˜[œ™X\ÛÛŸHŠBˆˆËÈ^Xİ]HHÙ[ˆ˜[Z\ÜÚ[ÛˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RK™Ù]Z\ÜÚ[ÛŠË›Z[
BˆYˆ
Z\ÜÚ[ÛˆOH[
HÂˆËÈKKÌÎ8 %\\‹[[ÙHXZÈš^‚ˆËÈ›İ]H›İYÚÛš\\”Ù[ÛÈ]™H[ÙHš\™\ÂˆËÈH\]\ˆİØ\È\\ˆ[ÙHİ[\Ù\È\\”Ù[‚ˆ^Xİ]Ü‹œÛš\\”Ù[
ˆÈHËˆ™X\ÛÛˆH”Ó’TT—ÉÙ^]ÚYÛ˜[œ˜[šË›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RK˜ÛÛ\]SZ\ÜÚ[ÛŠË›Z[Ëœ™Y‹^]ÚYÛ˜[
BˆBˆˆYÙÊ¼'ã«ÈÓ’TTˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[œ˜[šË™[[Úš_H	Ù^]ÚYÛ˜[œ™X\ÛÛŸH‹Ë›Z[
BˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã«ÈÔÓ’TT—H	İËœŞ[X›ÛHVUÑT”“Ôˆ	ÙK›Y\ÜØYÙ_HŠBˆBˆH[ÙHYˆ
XÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRKš\ÔÜÚ][ÛŠË›Z[
JHÂˆËÈHÈXÜ]Z\™H\™Ù]ˆHÂˆ˜[\ÜÙ\ÜÛY[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RK˜\ÜÙ\ÜÕ\™Ù]
ËËœ™YŠBˆˆËÈKKŒL8 %ÕÓ‘ÔQQT‘“ĞÒÈÈSSQU–K‚ˆËÈØ[YH˜][Û˜[H\È‘PTÕT–H
KKŒL
NˆHÍIHÜÜÈ˜]BˆËÈXÚÙ]Úİ[“ÕÚ[[HÚ[[]\™H[šY\È8 %ˆËÈ]Úİ[™XÛÜ™H[™Ù\ˆX™[™YXÙHÚ^™K[™ˆËÈ]H›İÙY\X\›š[™ËˆÜ\˜]ÜˆX[™]HÛ›Ü™Y‚ˆ˜[ÜÛš\\”ØÛÜ™HH\ÜÙ\ÜÛY[˜ÛÛ™šY[˜ÙK˜ÛÙ\˜ÙR[ŠL
Bˆ˜[ÜÛš\\’\Ñ[™Ù\ˆHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K“ÜÚ[™Ô]\›“Y[[ÜKš\Ñ[™Ù\–›Û™J”‘TĞSWÔÓ’TH‹ÜÛš\\”ØÛÜ™JBˆHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆËÈKŒŒÌˆ8 %‘KPT“QQS‘ÑT‹V“Ó‘H“ĞÒÈ
Ü\˜]ÜˆœİÜˆËÈ^Z[™ÈØ\˜˜YÙHŠKˆKKŒLİÛ™Ü˜YY\ÈÈ[[Y]BˆËÈ[™HÌLL‘TĞSWÔÓ’TH˜[™[ˆ›YLŒ‹ŒÉKÛÛÜˆËÈÚ[HH›İÙÙÙY‘S‘ÑT—Ö“Ó‘H
›İ›ØÚÙY
Hˆ[™ˆËÈ›İYÚ[]Ø^Kˆ™]ÈØİš[™N‚ˆËÈU‘Nˆ›ØÚÈÚ[ˆØÛÜ™OÌÔˆH˜[™\ÈH›İ™[‚ˆËÈ[™Ù\ˆ›Û™KˆØ\˜˜YÙH™]™\ˆİXÚ\È™X[ÓÓ‚ˆËÈTTˆ›ØÚÈÛ›HÚ[ˆH˜[™\ÈS‘PQH›İ™[‚ˆËÈŞXÈS‘ØÛÜ™OÌ8 %İY™šXÚY[Ø[\\È^\İˆËÈ[Ü™HÜÜÙ\ÈY™\›È[™›Ü›X][Û‹ˆ[œ›İ™[‚ˆËÈ˜[™ÈÙY\X\›š[™È
ŒHØİš[™H™\Ù\™Y
K‚ˆ˜[ÜÛš\\’\Ô\\ŒÌˆHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
HHØ]Ú
Îˆ›İØX›JHÈÙ™Ëœ\\“[ÙHBˆ˜[ÜÛš\\›ØÚÙYŒÌˆHYˆ
ÜÛš\\’\Ô\\ŒÌŠHÂˆÜÛš\\’\Ñ[™Ù\ˆ	‰ˆÜÛš\\”ØÛÜ™HÌˆH[ÙHÂˆÜÛš\\”ØÛÜ™HÌÜÛš\\’\Ñ[™Ù\‚ˆBˆYˆ
ÜÛš\\›ØÚÙYŒÌŠHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹ˆ¼'æäHÔÓ’TT—H	İËœŞ[X›ÛHS‘ÑT—Ö“Ó‘WĞ“ĞÒÑQÍŒÌˆØÛÜ™OIÜÛš\\”ØÛÜ™H\\IÜÛš\\’\Ô\\ŒÌˆ[™Ù\IÜÛš\\’\Ñ[™Ù\ˆ˜[™T‘TĞSWÔÓ’T_ÉÊÜÛš\\”ØÛÜ™KÌL
JŒLKIÊÜÛš\\”ØÛÜ™KÌL
JŒL
ÌLHŠBˆHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê”‘TĞSWÔÓ’TWÑS‘ÑT—Ğ“ĞÒÑQÍŒÌˆŠHHØ]Ú
Îˆ›İØX›JHßBˆH[ÙHYˆ
ÜÛš\\’\Ñ[™Ù\ŠHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹ˆ¼'ãíûî#ÈÔÓ’TT—H	İËœŞ[X›ÛHS‘ÑT—Ö“Ó‘WÕSSQU–H
ØÛÜ™OIÜÛš\\”ØÛÜ™H8¢iLÌ[İÙY
HÜÚ[™Ô]\›“Y[[ÜH›YÙÙY
‘TĞSWÔÓ’T_ÉÊÜÛš\\”ØÛÜ™KÌL
JŒLKIÊÜÛš\\”ØÛÜ™KÌL
JŒL
ÌLJHŠBˆBˆYˆ
\ÜÙ\ÜÛY[œÚİ[[™ØYÙH	‰ˆWÜÛš\\›ØÚÙYŒÌŠHÂˆËÈ]]Üš^™HÚ]˜YP]]Üš^™\‚ˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØÛÜ™HH\ÜÙ\ÜÛY[˜ÛÛ™šY[˜ÙKˆÛÛ™šY[˜ÙHH\ÜÙ\ÜÛY[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ]X[]HH”Ó’TTˆ‹ˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË”“Ò‘PÕÔÓ’TT‹ˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Ë›Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛH\ÜÙ\ÜÛY[œÜÚ][Û”Ú^™TÛÛˆ
BˆˆYˆ
]]™\İ[š\Ñ^Xİ]X›J
JHÂˆ˜[›Ú™XİÛš\\][\YH]]™\İ[˜][\Yˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã«ÈÔÓ’TT—H	İËœŞ[X›ÛHS‘ĞQÑHˆ
Âˆ‰Ø\ÜÙ\ÜÛY[™X]]™[™[[Úš_HYÙOIØ\ÜÙ\ÜÛY[ÚÙ[YÙTÙXÜß\Èˆ
ÂˆœÚ^™OIØ\ÜÙ\ÜÛY[œÜÚ][Û”Ú^™TÛÛ™›]
Ê_x¥ãˆÛÛ™IØ\ÜÙ\ÜÛY[˜ÛÛ™šY[˜Ù_IHŠBˆˆËÈKŒŒN8 %[™ØYÙHH›Ú™XİÛš\\ˆZ\ÜÚ[ÛˆÛ›HQ•T‚ˆËÈ^Xİ]ÜˆÛÛ™š\›\ÈH^HÜ[™YˆHÛÜ™\ˆÜ™X]YÚÜİˆËÈZ\ÜÚ[ÛœÈ™Y›Ü™HÚ]ÛÚ[^J
KÛÈ][İKÙš[˜[]KÜÚ^™H˜Z[\™\ÂˆËÈÛİ[š[H[Z\ÜÚ[ÛˆØ\[™›ØÚÈ™X[Ûš\\ˆ›Û[YK‚ˆËÈKŒŒÎL8 %›ZYÔÓšXH[™Q^][™\‹‚ˆËÈ™\XÙ\È\™ÛÙYÍKËLLˆÚ]\‹[[™BˆËÈ˜[Y\È]H[™\ˆ\ÈX\›™Yœ›ÛHBˆËÈ\İŒ“Ò‘PÕÔÓ’TTˆÛÜÙ\È
Û[\YˆËÈ][ÌŒLKKÛ][ÌÌLKŒÌJK‚ˆ˜[Ûš\\•][HHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K›X\›š[™Ë“[™Q^][™\‹™Ù]][
”“Ò‘PÕÔÓ’TTˆŠBˆHØ]Ú
Îˆ›İØX›JHÈKŒBˆ˜[Ûš\\”Û][HHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K›X\›š[™Ë“[™Q^][™\‹™Ù]Û][
”“Ò‘PÕÔÓ’TTˆŠBˆHØ]Ú
Îˆ›İØX›JHÈKŒBˆ˜[Ûš\\“Ü[™YH^Xİ]Ü‹œÚ]ÛÚ[^JˆÈHËˆÚ^™TÛÛH\ÜÙ\ÜÛY[œÜÚ][Û”Ú^™TÛÛˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆZÙT›Ùš]İHÍKŒ
ˆÛš\\•][ˆİÜÜÜÔİHLL‹Œ
ˆÛš\\”Û][ˆØ[]HØ[]ˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ][˜Ú]›Ü›HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™]Xİ]›Ü›JËœÛİ\˜ÙJKˆš\ÚÓ]™[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK”š\ÚÓ]™[‘V‘SQKˆš[˜[]T™XÚXÚÙYHYKˆ][\YH›Ú™XİÛš\\][\Yˆ[TØÛÜ™HH\ÜÙ\ÜÛY[˜ÛÛ™šY[˜ÙKˆ[PÛÛ™šY[˜ÙHH\ÜÙ\ÜÛY[˜ÛÛ™šY[˜ÙKˆ^Xİ][Û“[™HH”“Ò‘PÕÔÓ’TTˆ‹ˆ
B‚ˆYˆ
\Ûš\\“Ü[™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'ã«ÈÔÓ’TT—H	İËœŞ[X›ÛH•VWÓ“ÕÓÔS‘Q™[X\ÙH]]Ü\›Z]È›ÈZ\ÜÚ[Ûˆ[™ØYÙYŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ“S‘WĞ•VWÓ“ÕÓÔS‘QÔ‘SPTÑQ‹›[™OT“Ò‘PÕÔÓ’TTˆŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[”“Ò‘PÕÔÓ’TTˆ‹•VWÓ“ÕÓÔS‘QŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
HHØ]Ú
Îˆ›İØX›JHßBˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•VWÓ“ÕÓÔS‘Q‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË”“Ò‘PÕÔÓ’TTŠHHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”›Ú™XİÛš\\RK™[™ØYÙSZ\ÜÚ[ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHËœ™Y‹ˆ[TÛÛH\ÜÙ\ÜÛY[œÜÚ][Û”Ú^™TÛÛˆ\ÜÙ\ÜÛY[H\ÜÙ\ÜÛY[ˆ\]ZY]HHË›\İ\]ZY]U\ÙˆXØ\HË›\İXØ\ˆ^T™\Üİ\™HHË›\İ^T™\Üİ\™Tİˆ
Bˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê”“Ò‘PÕÔÓ’TT—ÓRTÔÒSÓ—ĞQ•T—Ğ•VWÍŒNŠBˆˆYÙÊ¼'ã«ÈÓ’TTˆ	İËœŞ[X›ÛH	Ø\ÜÙ\ÜÛY[™X]]™[™[[Úš_HS‘ĞQÑQˆ
Âˆ˜YÙOIØ\ÜÙ\ÜÛY[ÚÙ[YÙTÙXÜß\È	ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
BˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã«ÈÔÓ’TT—H	İËœŞ[X›ÛH	Ø]]™\İ[œ™X\ÛÛŸHŠBˆBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã«ÈÔÓ’TT—H	İËœŞ[X›ÛHT”“Ôˆ	ÙK›Y\ÜØYÙ_HŠBˆBˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘›Ú™XİÛš\\ˆ]˜[X][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'äâ|'ã«ÈTS•TˆH^H]X[]H\ÈÛˆ\İX›\ÚYÚÙ[œÂˆËÈKŒˆ’Vˆ]\İÚXÚÈYˆ™X\İ\H[™XYH\ÈHÜÚ][ÛˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
]ËœÜÚ][Û‹š\ÓÜ[ˆ	‰ˆÚİ[[^S[™Q›ÜŞXÛJË‘TÒS•Tˆ‹ŞXÛTš[X\S[™JH	‰ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RKš\Ñ[˜X›Y

JHÂˆËÈKKLŒ8 %TÒS•TˆS‘WÑUS[Z]‚ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK“S‘WÑUSˆËœŞ[X›Ûˆ›[™OQTÒS•Tˆ\\IØÙ™Ëœ\\“[Ù_HXØ\IİË›\İXØ\Ò[

_H\OIİË›\İ\]ZY]U\ÙÒ[

_HØÛÜ™OIİË™[TØÛÜ™_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆËÈKËˆ\[\ˆ[œÈ[™\[™[BˆHÂˆ˜[ÚÙ[YÙRİ\œÈHYˆ
Ë˜YYÕØ]Ú\İ]ˆ
HÂˆ
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË˜YYÕØ]Ú\İ]
HÈ
Œ
ˆŒ
ˆLŒ
BˆH[ÙHL‹ŒˆˆËÈ\[\ˆ›ÜˆÚÙ[œÈ	LËISHXØ\]]™H™Y[ˆ\›İ[™ˆYˆ
Ë›\İXØ\[ˆLÌŒ‹WÌÌŒ	‰ˆÚÙ[YÙRİ\œÈH‹Œ
HÂˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKŒLLˆ8 %‘PSTUPÕSÓˆ
Ø\ÈİXİ\˜[Hİ\™Y
K‚ˆËÈ•QÎˆ™]š[İ\ÈÛÙH\ÙYËš\İÜK›X^ÙˆÈ]œšXÙU\ÙBˆËÈ
Ø[™HÓÔÑHÛ›JH›ÜˆHœ™XÙ[YÚ‹[ˆ™[˜XÚÈÂˆËÈËœ™Yˆ
ˆKŒÈ8 %HP”’PĞUQÌ	HYÚ8 %Ú[ˆ\İÜHØ\È[‹‚ˆËÈ™\İ[ˆ]™\Hœ™\ÚÚÙ[ˆZ]\ˆÚİÙYŒ	H\
ÛÜÙx¢bİ\œ™[ˆËÈÛˆHØ^H\
H8¡¤ˆ“ÕĞWÑTÜˆHS•ÓHÌ	H\œ›ÛHH˜ZÙBˆËÈ˜[˜XÚÈ8¡¤ˆ˜[ÙHÓÓS—ÑTÚYÛ˜[Ëˆ\[\ˆ]˜[X]YŒÂˆËÈÛ˜\Úİ[™™]™\ˆÜ[™YH™X[˜YK‚ˆËÈ’Vˆ\ÙHHYHØ[™HQÒ
YÚ\Ù˜[˜XÚÈšXÙU\Ù
H[™ˆËÈ™Y™\ˆHY\\ˆM[H\İÜH
Ûİ™\œÈŒMZ
Hİ™\ˆH›Ú\ŞH[BˆËÈÚ[™İÈÛÈHÙ[Z[™H[˜XÚÈœ›ÛHH™X[XZÈ\È]XİYˆY‚ˆËÈ\™H\È“ÈXZÈX›İ™Hİ\œ™[šXÙK]\ÈÛ™\İH“ÕH\ˆËÈ
™XÙ[YÚİ^\È]İ\œ™[
H8 %›È˜XœšXØ]YKŒğåÈYÚÛÈBˆËÈ[™HİÜÈ[Z][™È˜[ÙHÚYÛ˜[ÈÛˆÚÙ[œÈ]Û›HÙ[\‚ˆËÈ[ÛÈÚ\™HH‘PS›Û[YUœĞ]™È
İ\œ™[œÈ˜Z[[™È]™ÊH[œİXYˆËÈÙˆH\™ÛÙYKŒ]\ØX›YH›Û[YKXÛÛ\ÙHİX\™‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[\\İHŞ[˜Ú›Ûš^™Y
Ëš\İÜJHÂˆYˆ
Ëš\İÜLM[Kš\Ó›İ[\J
JHËš\İÜLM[KÓ\İ

Bˆ[ÙHËš\İÜKÓ\İ

BˆBˆ˜[Ø[™RYÚH\\İ›X^Ù“Ü“[ÂˆYˆ
]šYÚ\ÙˆŒ
H]šYÚ\Ù[ÙH]œšXÙU\ÙˆHÎˆŒˆËÈÛ™\İYÚˆ™X[XZÈœ›ÛH\İÜK™]™\ˆ™[İÈİ\œ™[šXÙKˆËÈ™]™\ˆH˜XœšXØ]Y][\KˆYˆ›İ[™ÈYÚ\ˆ^\İÈ]\]X[ÂˆËÈİ\œ™[8¡¤ˆ\[\RHÛÛ\]\È	H\8¡¤ˆÛX[ˆ“ÕĞWÑT™Z™Xİ‚ˆ˜[™XÙ[YÚHX^ÙŠØ[™RYÚËœ™YŠBˆËÈ™X[›Û[YH˜][Îˆ]\İØ[™H›Û[YHœÈ˜Z[[™È]™\˜YÙK‚ˆ˜[\›ÛœĞ]™ÈH[ˆÂˆ˜[›ÛÈH\\İ›X\›İ[ÈÈO‚ˆ˜[ˆHYˆ
Ë›Û[YRHˆŒ
HË›Û[YRH[ÙHË›Û[YLˆ‹ZÙRYˆÈ]ˆŒBˆBˆYˆ
›ÛËœÚ^™HHÊHÂˆ˜[]™ÈH›ÛË˜]™\˜YÙJ
BˆYˆ
]™ÈˆŒ
H
›ÛË›\İ

HÈ]™ÊK˜ÛÙ\˜ÙR[ŠŒLŒ
H[ÙHKŒˆH[ÙHKŒËÈ›İ[›İYÚØ[\\È8¡¤ˆ™]]˜[
Û‰İ˜[ÙK]š\HİX\™
BˆBˆˆ˜[\ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK™]˜[X]JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆİ\œ™[šXÙHHËœ™Y‹ˆYÚšXÙHH™XÙ[YÚˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ^T™\Üİ\™TİHË›\İ^T™\Üİ\™Tİˆ›Û[YUœĞ]™ÈH\›ÛœĞ]™ËˆÚÙ[YÙRİ\œÈHÚÙ[YÙRİ\œËˆÛ\Ûİ[HËœXZÒÛ\Ûİ[ZÙRYˆÈ]ˆHÎˆLˆÛ\Ú[™ÙLHˆ\Ñ]”Ù[[™ÈHËœØY™]K˜[™Tš\ÚÈOH’QÒ‹ˆ
BˆˆYˆ
\ÚYÛ˜[œÚİ[^JHÂˆËÈKK8 %‘ÈØ]H›Üˆ\[\ˆ]ˆ˜[\™ÈHHÂˆš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËˆØ[™Y]HH[™T]X[YšYY^QXÚ\Ú[ÛŠXÚ\Ú[Û‹‘TÒS•Tˆ‹ÛÛ™šY[˜ÙQ›ÛÜˆH\ÚYÛ˜[˜ÛÛ™šY[˜ÙH
ˆLŒ\]ZY]U\ÙHË›\İ\]ZY]U\ÙZ[›Ü”›Ø™HHË›Z[
Kˆ[™TØÛÜ™HH\ÚYÛ˜[˜ÛÛ™šY[˜ÙKÑİX›J
KËÈ[LLˆÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛH\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈHHÈ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJ‘TÒS•TˆŠHHØ]Ú
Îˆ^Ù\[ÛŠHÈ[KˆÜXÚX[\İ[™HH‘TÒS•Tˆ‹ˆ
BˆHØ]Ú
™Ñ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'äâHÑTS•T—H‘È\œ›Üˆ	Ù™Ñ^›Y\ÜØYÙ_H8 %›ØÙYY[™È˜Z[[Ü[ˆŠBˆ[ˆBˆËÈKKH8 %[\‘È›Ü™[œÚXÈÛİ[\ˆ›ÜˆTS•Tˆ]ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆœ]QTS•TˆØ[IÙ\™ÏË˜Ø[‘^Xİ]J
HÎˆY_H™X\ÛÛIÙ\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›‹ØHŸHŠBˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]J›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËËœŞ[X›Ûˆ[İÈH\™ÏË˜Ø[‘^Xİ]J
HÎˆYKˆ™X\ÛÛˆH\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ›ÚÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆ^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™ÊË›Z[ËœŞ[X›Û‘TÒS•Tˆ‹\™ÏË˜Ø[‘^Xİ]J
HÎˆYK\™ÏË˜›ØÚÔ™X\ÛÛ‹ÚYÛ˜[H•VH‹YÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KØY™]UY\ˆHËœØY™]KY\‹›˜[YK\]ZY]U\ÙHË›\İ\]ZY]U\Ù\™›Ô™X\ÛÛœÈHËœØY™]Kš\™›ØÚÔ™X\ÛÛœË[TØÛÜ™HHË™[TØÛÜ™KÒ[

KÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJKœ›İ]Tİ]\ËÚÙ[“X\Y˜][ÛÛÛ\]HHËÚÙ[“X\šY˜][ÛÛÛ\]KÚÙ[“X\^XİYİ]HËÚÙ[“X\™^XİYİ][[İ[ÚÙ[“X\›İšY\][\ÈHËÚÙ[“X\œ›İšY\][\ÊBˆËÈKKLH8 %‘È[Ù[]\ËÙ\È›İ\™ZÚ[\[\ˆÚYÛ˜[Âˆ˜[\™ÔİXİ\˜[H\™ÈOH[	‰ˆY\™Ë˜Ø[‘^Xİ]J
H	‰‚ˆ\™Ë˜›ØÚÔ™X\ÛÛË›]È]˜ÛÛZ[œÊ“TURQUHŠH]˜ÛÛZ[œÊ“SÔ•Q×Ô“ĞP’SUHŠH]˜ÛÛZ[œÊÓÔWÕQHŠH]˜ÛÛZ[œÊ‘SQT‘ÑSÖWÔÕÔŠHHOHYBˆ˜[\™Ô›Ø™HH\™ÈOH[	‰ˆY\™Ë˜Ø[‘^Xİ]J
H	‰ˆY\™ÔİXİ\˜[ˆYˆ
\™ÔİXİ\˜[
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘ÈÕ•PÕTS“ĞÒÈÛˆTS•Tˆ	İËœŞ[X›ÛH	Ù\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘TS•T—Ñ‘È‹\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŠBˆH[ÙHÂˆYˆ
\™Ô›Ø™JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨;î#È‘ÈÒV‘KT‘QPÑHÛˆTS•Tˆ	İËœŞ[X›ÛH	Ù\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×ØØ]][ÛˆŸH›Ø™H˜YHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘TS•T—Ñ‘×Ô“Ğ‘H‹\™ÏË˜›ØÚÔ™X\ÛÛˆÎˆ™™×ØØ]][ÛˆŠBˆBˆËÈKŒˆUTÕÚXÚÈ˜YP]]Üš^™\ˆ‘Q“Ô‘H[H^Xİ][Û‚ˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØÛÜ™HH\ÚYÛ˜[˜ÛÛ™šY[˜ÙKˆÛÛ™šY[˜ÙHH\ÚYÛ˜[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ]X[]HH‘T‹ˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË‘TÒS•T‹ˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Ë›Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛH\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆ
BˆˆYˆ
X]]™\İ[š\Ñ^Xİ]X›J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äâ|'ã«ÈÑTH	İËœŞ[X›ÛH	ÚYˆ
]]™\İ[š\ÔÚYİÓÛ›J
JH”ÒQÕ×ÓÓ“Hˆ[ÙH”‘R‘PÕQŸH	Ø]]™\İ[œ™X\ÛÛŸHŠBˆYˆ
X]]™\İ[š\ÔÚYİÓÛ›J
JH™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘T‹]]™\İ[œ™X\ÛÛŠBˆH[ÙHÂˆ˜[\[\][\YH]]™\İ[˜][\Yˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äâ|'ã«ÈÑTH	İËœŞ[X›ÛH•VHˆ
Âˆ‰Ù\ÚYÛ˜[™\]X[]K™[[Úš_H	Ù\ÚYÛ˜[™\]X[]K›˜[Y_Hˆ
Âˆ™\IÙ\ÚYÛ˜[™\\İ™›]
J_IHˆ
ÂˆœÚ^™OIÙ\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛ™›]
Ê_HÓÓˆ
Âˆ\™Ù]JÉÙ\ÚYÛ˜[™^XİY™XÛİ™\TİÒ[

_IHŠBˆˆËÈKKÌÎ8 %\\‹[[ÙHXZÈš^‚ˆËÈ\ÙHH\[\^H›İ]\ˆÛÈ]™H[ÙBˆËÈÛÜœ™XİHš\™\ÈH\]\ˆİØ\[œİXYÙ‚ˆËÈÚ[[H›İ][™È›İYÚ\\^J
K‚ˆËÈKŒŒŒ8 %Ü[ˆ\[\ˆİ]HÛ›HQ•T‚ˆËÈ^Xİ]ÜˆÛÛ™š\›\ÈH^HÜ[™YˆHÛÜ™\‚ˆËÈÜ™X]YÚÜİXİ]™Q\ËÙZ[R[È™Y›Ü™H˜Z[YˆËÈ][İKÙš[˜[]KÜÚ^™H][\ËÚÚÚ[™È]™H›Û[YK‚ˆ˜[\Ü[™YH^Xİ]Ü‹™\[\^JˆÈHËˆÚ^™TÛÛH\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆØÛÜ™HH\ÚYÛ˜[˜ÛÛ™šY[˜ÙKÑİX›J
KˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆY[]HHY[]Kˆš[˜[]T™XÚXÚÙYHYKˆ][\YH\[\][\Yˆ
BˆYˆ
Y\Ü[™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹‘TÒS•Tˆ	İËœŞ[X›ÛH•VWÓ“ÕÓÔS‘Q™[X\ÙH]]Ü\›Z]È›È[™H™YÚ\İ˜][ÛˆŠBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ“S‘WĞ•VWÓ“ÕÓÔS‘QÔ‘SPTÑQ‹›[™OQTÒS•TˆŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[‘TÒS•Tˆ‹•VWÓ“ÕÓÔS‘QŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈš[˜[^Xİ][Û”\›Z]œ™[X\ÙQ^Xİ][ÛŠË›Z[
HHØ]Ú
Îˆ›İØX›JHßBˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•VWÓ“ÕÓÔS‘Q‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚË‘TÒS•TŠHHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK›Ü[‘\
ˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHËœ™Y‹ˆ[TÛÛH\ÚYÛ˜[œÜÚ][Û”Ú^™TÛÛˆYÚšXÙHH™XÙ[YÚˆ\\İH\ÚYÛ˜[™\\İˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
Bˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê‘TÒS•T—ÓÔS—ĞQ•T—Ğ•VWÍŒŒŠB‚ˆËÈK‹’Vˆ›İYHŒÈ^Üİ\™HİX\™ÈÛ›HY\ˆH™X[Ü[™[™ÈÜ[ˆ^\İË‚ˆYˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒËœÜÚ][Û‹œ[™[™Õ™\šYHËœÜÚ][Û‹š\ÓÜ[ŠHÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][Û“Ü[™Y
Ë›Z[
BˆˆËœÜÚ][Û‹˜Y[™Ó[ÙHH‘TÒS•Tˆ‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¼'äâH‚ˆˆYÙÊ¼'äâ|'ã«ÈT•VNˆ	İËœŞ[X›ÛH	Ù\ÚYÛ˜[™\]X[]K™[[Úš_Hˆ
Âˆ™\	Ù\ÚYÛ˜[™\\İÒ[

_IHˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
BˆBˆBˆBˆHËÈÛÜÙH‘Ë\™\]Z\™Y[ÙH
TS•TˆKK
BˆHØ]Ú
\^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'äâ|'ã«ÈÑTH	İËœŞ[X›ÛHT”“Ôˆ	Ù\^›Y\ÜØYÙ_HŠBˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘\[\ˆ]˜[X][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈS‘™X\İ\H[ÙH]˜[X][ÛˆH›İÈ›ØÙYYÚ]ŒÈXÚ\Ú[Ûˆ[™[™ÂˆËÈKŒˆ[ÛÛœÚİ]˜[X][Ûˆ[İ™Y‘Q“Ô‘HÚ]ÛÚ[ˆ›Üˆ›Ü\ˆ^Xİ][ÛˆÜ™\‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆÚ[ˆ
˜[™\İ[HŒÑXÚ\Ú[ÛŠHÂˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹‘^Xİ]HOˆÂˆËÈKKŒNNNˆİ˜]YŞU\İØ]H8 %ÚÚ\^Xİ]HYˆTÕ•TÕQÛ‰İ™]\›‚ˆËÈKŒŒH8 %œ™YK\˜[™ÙH\İ\\ÜÈ\È\\‹[Û›Kˆ]™BˆËÈ]\İ›İ^H›İYÚHTÕ•TÕQİ˜]YŞH\İ™XØ]\ÙBˆËÈHÛØ˜[X\›š[™ÈŞ\İ[H\ÈÚYK[Ü[‹‚ˆ˜[Y[YS[ÙHHËœÜÚ][Û‹˜Y[™Ó[ÙKšY›[šÈÈY[]Kœ\ÙKšY›[šÈÈ”ÒUÓÒSˆˆHBˆ˜[\İ[İÙYH
ÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆœ™YT˜[™ÙS[ÙKš\ÕÚYSÜ[Š
JHˆÛÛK›Y™XŞXÛX›İ›Y]K”İ˜]YŞU\İRKš\Ôİ˜]YŞP[İÙY
Y[YS[ÙJBˆYˆ
]\İ[İÙY
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¼'æªÈÕ•TÕĞUWH	ÚY[]KœŞ[X›ÛH[ÙOIY[YS[ÙHTÕ•TÕQ8 %ÚÚ\[™È^Xİ]HŠBˆH[ÙHÂˆËÈØXÚHŒÈØÛÜ™\ÈÛˆÚÙ[”İ]H›Üˆ™X\İ\H[ÙHÈ\ÙBˆË›\İŒÔØÛÜ™HH™\İ[œØÛÜ™BˆË›\İŒĞÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKÒ[

BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈVPÕUNˆÛX[ˆÙÙÚ[™È
È˜YH^Xİ][Û‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÔĞÓÔ’S‘×H	ÚY[]KœŞ[X›ÛHİ[IÜ™\İ[œØÛÜ™_H	Ü™\İ[˜œ™XZÙİÛŸHŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑUSH	ÚY[]KœŞ[X›ÛHTÔÈŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ĞÓÓ‘’QSÑWH	ÚY[]KœŞ[X›ÛH	Ü™\İ[˜ÛÛ™šY[˜ÙKÒ[

_IHŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑPÒTÒSÓ—H	ÚY[]KœŞ[X›ÛH	Ü™\İ[˜˜[™HØÛÜ™OIÜ™\İ[œØÛÜ™_HÛÛ™IÜ™\İ[˜ÛÛ™šY[˜ÙKÒ[

_IHŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÔÒV’S‘×H	ÚY[]KœŞ[X›ÛH	Ü™\İ[œÚ^™TÛÛ™›]

_HÓÓŠBˆˆËÈJÈ[\›ÜˆYÚXÛÛšXİ[ÛˆÙ]\ÂˆYˆ
™\İ[œØÛÜ™HHÍJHÂˆÛİ[™X[˜YÙ\‹œ^P\\Ğ[\

BˆYÙÊ¸«dQÒÓÓ‘’QSÑNˆ	ÚY[]KœŞ[X›ÛHØÛÜ™OIÜ™\İ[œØÛÜ™_H‹Ë›Z[
BˆBˆˆYˆ
XÙ™ËŒÔÚYİÓ[ÙJHÂˆËÈKKŒM8 %“RQ”‘TÒSUSÒĞÓÔ‘HĞUBˆËÈKKMÈYHš[˜\HØ]H
YÙO[H
ÈØÛÜ™O8¡¤ˆÚÚ\
NÂˆËÈKKŒML™[[İ™Y][\™[HÚXÚ™\İÜ™Y›Û[YBˆËÈ][šÙYÚ[‹\˜]HÛˆœ™\Ú[\™[ˆ][˜Ú\È]ˆËÈØÛÜ™YŒLÌ
ÛÚ[‹Y›\]X[]KLÈÙˆŒˆŒÈ^Y\œÂˆËÈ]K\İ\™Y][Z[ˆYÙJKˆ™\XÙHÚ]H›ZYˆËÈZ[š[][H]ØØ[\ÈÚ]X\›š[™È›ÙÜ™\ÜÎ‚ˆËÈ›Ûİİ˜\
	H›ÙÊH8¡¤ˆØÛÜ™HHMH
™\H[šY[
BˆËÈœ™\ÚX[ˆ
	H›ÙÊH8¡¤ˆØÛÜ™HHBˆËÈX]\™H
L	H›ÙÊx¡¤ˆØÛÜ™HH
KKMÈİšXİ
BˆËÈÛ›H\Y\ÈÈÚÙ[œÈ[Z[ˆÛÈ\İX›\ÚYˆËÈÚÙ[œÈ\ÙHHİ[™\™›ZY›ÛÜœË‚ˆ[ˆÂˆ˜[ÚÙ[YÙSZ[œÈHYˆ
Ë˜YYÕØ]Ú\İ]ˆ
HÂˆ
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË˜YYÕØ]Ú\İ]
HÈŒÌŒˆH[ÙHİX›K“PVÕSQBˆYˆ
ÚÙ[YÙSZ[œÈKŒ
HÂˆ˜[œ™\Ú][˜ÚZ[”ØÛÜ™HH
Lˆ
ÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRBˆ™Ù]X\›š[™Ô›ÙÜ™\ÜÊ
H
ˆ
KÒ[

K˜ÛÙ\˜ÙR[ŠL‹
BˆYˆ
™\İ[œØÛÜ™Hœ™\Ú][˜ÚZ[”ØÛÜ™JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ–ÕŒß”‘TÒÓUSÒÑĞUWH	ÚY[]KœŞ[X›ÛHÒÒTˆ
Âˆ˜YÙOIİÚÙ[YÙSZ[œËÒ[

_[O[HØÛÜ™OIÜ™\İ[œØÛÜ™_O	œ™\Ú][˜ÚZ[”ØÛÜ™H
›ZYHX\›š[™ÊH‚ˆ
Bˆ™]\›‚ˆBˆBˆB‚ˆËÈKKLÎˆ”‘TÒSUSÒUÑÕÓˆĞUBˆËÈÚ[ˆÛX\Ú\\ÈLØ[™\Ë]Ø[››İ™]ÂˆËÈ™X\š\Ú[İ™\ËÛÈİXœİ]]HHÚ[\H˜]ÙİÛ‚ˆËÈÚXÚÎˆÚÚ\H[HYˆšXÙH\È›ÜY[Ü™BˆËÈ[ˆMIHœ›ÛHHX\›Y\İ™XÛÜ™YØ[™H
ÛÛˆËÈ›Ü›X][ÛŠKˆ›İXİÈYØZ[œİœ˜[™[™]ÈYÜË‚ˆ[ˆÂˆ˜[Ø[™\ÈHËš\İÜBˆYˆ
Ø[™\ËœÚ^™H[ˆK‹JHÂˆ˜[š\œİšXÙHHØ[™\Ë™š\œİ

KœšXÙU\ÙˆYˆ
š\œİšXÙHˆŒ	‰ˆËœ™YˆˆŒ
HÂˆ˜[˜]ÙİÛ”İH

Ëœ™YˆHš\œİšXÙJHÈš\œİšXÙJH
ˆLŒˆYˆ
˜]ÙİÛ”İHLMKŒ
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ–ÕŒß”‘TÒÑUÑÕÓ—H	ÚY[]KœŞ[X›ÛHÒÒTˆ
Âˆ˜Ø[™\ÏIØØ[™\ËœÚ^™_HIÙ˜]ÙİÛ”İÒ[

_IHˆ
ÂˆŠš\œİIÙš\œİšXÙ_H›İÏIİËœ™YŸJH‚ˆ
Bˆ™]\›‚ˆBˆBˆBˆB‚ˆËÈKŒˆUTÕÚXÚÈ˜YP]]Üš^™\ˆ‘Q“Ô‘H[H^Xİ][Û‚ˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HË›Z[ˆŞ[X›ÛHY[]KœŞ[X›ÛˆØÛÜ™HH™\İ[œØÛÜ™KˆÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]Kˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚËÓÔ‘KˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Ë›Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛH™\İ[œÚ^™TÛÛˆ
BˆˆYˆ
X]]™\İ[š\Ñ^Xİ]X›J
JHÂˆËÈ“ÕUUÔ’V‘QHÙÈ[™ÚÚ\^Xİ][Û‚ˆYˆ
]]™\İ[š\ÔÚYİÓÛ›J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÕŒßUUH	ÚY[]KœŞ[X›ÛHÒQÕ×ÓÓ“H	Ø]]™\İ[œ™X\ÛÛŸHŠBˆËÈ˜XÚÈ›ÜˆÚYİÈX\›š[™ÂˆÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHY[]KœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH•Œ×ĞUUÔÒQÕ×ÉØ]]™\İ[œ™X\ÛÛŸH‹ˆ›ØÚÓ]™[H•QWĞUUÔ’V‘Tˆ‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛH™\İ[œÚ^™TÛÛˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
BˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÕŒßUUH	ÚY[]KœŞ[X›ÛH‘R‘PÕQ	Ø]]™\İ[œ™X\ÛÛŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
•Œ×ĞUU‹]]™\İ[œ™X\ÛÛŠBˆBˆH[ÙHÂˆËÈUUÔ’V‘QH›ØÙYYÚ]^Xİ][Û‚ˆËÈŒÈÓÓ•“ÓÈVPÕUSÓ‚ˆ˜[ŒÔÚ^™TÛÛH™\İ[œÚ^™TÛÛˆ˜[ŒÕ\Ú\ÈH•ŒÈØÛÜ™OIÜ™\İ[œØÛÜ™_H˜[™IÜ™\İ[˜˜[™H‚ˆˆËÈ\]HY™XŞXÛHÈŒÈİ]\ÂˆY[]KŒÑ^Xİ]J™\İ[œØÛÜ™K™\İ[˜˜[™™\İ[œÚ^™TÛÛ
BˆˆËÈ^Xİ]HH˜YBˆ˜\ˆ›ÜÜÙYÚ^™HH™\İ[œÚ^™TÛÛˆ˜[[ÙUYÈHHÂˆËÈKKH8 %\‹]ÚÙ[ˆ[™HÚ[œÈİ™\ˆÛØ˜[›İ[ÙBˆ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJËœÜÚ][Û‹˜Y[™Ó[ÙJBˆÎˆ[ÙPÛÛ™Ë›[ÙOË›]È[ÙTÜXÚYšXÑØ]\Ë™œ›ÛP›İ[ÙJ]
HBˆHØ]Ú
Nˆ^Ù\[ÛŠHÈ[B‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKN8 %ŒËSQSQHÑS•QSÑH	ˆÖSP“ÓPÈ‘KUÒT‘K‚ˆËÈ™Y›Ü™HKKNHŒÈ^Xİ]H]Ù[İ˜ZYÚÂˆËÈ^Xİ]Ü‹ŒĞ^J
H8 %\\ÜÚ[™È‘È[\™[H8 %ÚXÚYX[ˆËÈ
JHHXÚ[›™[Ş[X›ÛXĞÛÛ^™]™\ˆY\İYÚ^™HÛ‚ˆËÈY[Y\Ë
ŠH[ÙTÜXÚYšXÑØ]\Ë“[ÙS][\Y\œÈÙ\™HÛÛ\]YˆËÈ]™]™\ˆ\YY[™
ÊHÙ[Y[˜ÙRÛÚÜËœÚİ[š[\T\œÛÛ˜[]BˆËÈØ\ÈXYÛÙKˆHYØXŞHÓÔ‘KÕ™X\İ\KÔ]X[]H]ÂˆËÈİ[›İÈ›İYÚ‘Ë™]˜[X]J
HÛÈ^HÙ\™Hš[™H8 %ˆËÈÛ›HHY[YHŒËX]]Üš]H]ÜİHŞ[X›ÛXÈ™\›İ\ÂˆËÈŞ\İ[K‚ˆËÂˆËÈÙHÈ“Õ™K\[ˆ[‘Ë™]˜[X]J
H\™H
X]KÛİ[ˆËÈİX›K[ÙÊH8 %[œİXYÙH\HHØ[YHİ\™ÚXØ[[œ]ÂˆËÈ‘ÈÛİ[ˆ™Yœ™\ÚŞ[X›ÛXĞÛÛ^™XY[ÙS][\Y\œËˆËÈÛ›İ\ˆHŞ[X›ÛXÈ[š]™\œÙH›ØÚË[™\HÚ^™BˆËÈY\İY[È
È\œÛÛ˜[]H
ÈÜ›ÜÜËY[™Ú[™HšX\È™Y›Ü™BˆËÈ^Xİ]Ü‹ŒĞ^Hš\™\Ë‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[Ş[T™Yœ™\ÚYHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ş[X›ÛXĞÛÛ^œ™Yœ™\Ú
ËœŞ[X›ÛË›Z[
BˆYBˆHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆ˜[Ş[S[ÛÙHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ş[X›ÛXĞÛÛ^™[[İ[Û˜[İ]HHØ]Ú
Îˆ›İØX›JHÈ“‘UUSˆBˆ˜[Ş[QÜ™Y[“YÚHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ş[X›ÛXĞÛÛ^™Ù][QÜ™Y[“YÚ

HHØ]Ú
Îˆ›İØX›JHÈHBˆ˜[Ş[TÚ^™PYˆHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ş[X›ÛXĞÛÛ^™Ù]Ú^™PY\İY[

HHØ]Ú
Îˆ›İØX›JHÈKŒBˆ˜[Ş[PÚ\˜İZ]œ™XZÚ[™ÈHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ş[X›ÛXĞÛÛ^š\ĞÚ\˜İZ]œ™XZÚ[™Ê
HHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHB‚ˆËÈZ\œ›Üˆ‘ÉÜÈÖSP“ÓP×ÕS’U‘T”ÑWĞ“ĞÒÈ
U‘HÛ›JK‚ˆËÈ\\‹[[ÙHÙY\ÈX\›š[™È]™[ˆ[ˆ[šXËØÚ\˜İZ]İ]\Ë‚ˆYˆ
XÙ™Ëœ\\“[ÙH	‰‚ˆŞ[QÜ™Y[“YÚŒŒ	‰‚ˆŞ[S[ÛÙ[ˆ\İÙŠ”S’PÈ‹‘‘PT‘•SŠH	‰‚ˆŞ[PÚ\˜İZ]œ™XZÚ[™Âˆ
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Šˆ›İÙ\šXÙH‹ˆ¼'ã#ÕŒßÖSP“ÓP×Ğ“ĞÒ×H	ÚY[]KœŞ[X›ÛHÜ™Y[“YÚIÈ‰KŒ™ˆ‹™›Ü›X]
Ş[QÜ™Y[“YÚ
_H[ÛÙIŞ[S[ÛÙÚ\˜İZ]Øœ™XZÚ[™Ï]YH
U‘JH8 %ÒÒT^Xİ]H‚ˆ
BˆYÙÊ¼'ã#ÖSP“ÓPÈ“ĞÒÎˆ	ÚY[]KœŞ[X›ÛH	Ş[S[ÛÙÈÚ\˜İZ]Xœ™XZÚ[™È‹Ë›Z[
BˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•Œ×ÔÖSP“ÓP×Ğ“ĞÒ×Ô‘P•VH‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚËÓÔ‘JHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[ÓÔ‘H‹•Œ×ÔÖSP“ÓP×Ğ“ĞÒ×Ô‘P•VHŠHHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚ˆËÈKKH8 %‘SSÕ‘QKKŒH’TKQS‘ÑTˆ\™Ø]K‚ˆËÈHÛÛXš[˜][Ûˆ
[YPRKš\Ñ[™Ù\–›Û™H
ÈÛÛY[YS˜\œ˜]]™BˆËÈÛ\İ\ˆ
È\]ZY]O	LÊH›ØÚÙY^XİHHXYZİ\‚ˆËÈœ™\Ú[][˜ÚÈÜ\œÙKY]HÚÙ[œÈ]›ÙXÙYˆËÈZ[LNMIÜÈL	H[›™\œËˆXXÚÛÛ\Û™[İ[ˆËÈÛÛšX]\ÈMœÈÈHÛÙØÛÜ™NÈ]	ÜÈBˆËÈZ[LNMH˜\Ù[[™H™Z]š[İ\‹‚‚ˆËÈÙ[Y[˜ÙHÛÚÈÍˆ8 %\œÛÛ˜[]KYš]™[ˆš[\‹‚ˆËÈYˆH\Ù\‰ÜÈ™XÙ[Ú]ØZY˜]›ÚYÒUÓÒSˆˆÈ˜]›ÚYY[Y\È‹ˆËÈÚÚ\\È[Kˆ™\İYY™›Ü˜Z[[Ü[‹‚ˆ˜[™YÚ[YR[HËœÜÚ][Û‹˜Y[™Ó[ÙKšY›[šÈÈ[ÙPÛÛ™Ë›[ÙOË›˜[YHÎˆˆˆBˆ˜[\œÛÛ˜[]U™]ÈHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ù[Y[˜ÙRÛÚÜËœÚİ[š[\T\œÛÛ˜[]JˆŞ[X›ÛHY[]KœŞ[X›Ûˆ™YÚ[YHH™YÚ[YR[ˆ
BˆHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆ˜[\œÛÛ˜[]TÚ^™S][HYˆ
\œÛÛ˜[]U™]ÊHÌˆ[ÙHKŒˆYˆ
\œÛÛ˜[]U™]ÊHÂˆËÈKŒNN8 %\œÛÛ˜[]KÓHš[\œÈ\™HYš\ÛÜHÛ›K‚ˆËÈ[[YHNÚİÙY“H‘UÈİYÙÙ\İYˆ\š[™ÈHŒËÑ‘ÂˆËÈÚÚÙKˆHHÛÚÈ]Ù[ˆ\È\Ş[˜ËÙ˜Z[[Ü[‹]\ÂˆËÈØ[Ú]Hİ[\›™YHØXÚY\œÛÛ˜HX]Ú[ÈH\™ˆËÈ™X^H™]\›‹ˆ™\Ù\™HHÚYÛ˜[Hš[[Z[™ÈÚ^™H[œİXYˆËÈÙˆ™[X\Ú[™ÈH[™H[™İ\š[™È›İYÚ]‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ¼'éèÕŒßT”ÓÓSUWÔÓÑ•ÔÒTWH	ÚY[]KœŞ[X›ÛH™YÚ[YOI™YÚ[YR[Ú^™påÏIÈ‰KŒ™ˆ‹™›Ü›X]
\œÛÛ˜[]TÚ^™S][
_H‚ˆ
BˆYÙÊ¼'éèT”ÓÓHÓÑ•TÒTNˆ	ÚY[]KœŞ[X›ÛH0åÉÈ‰KŒ™ˆ‹™›Ü›X]
\œÛÛ˜[]TÚ^™S][
_H
™YÚ[YOI™YÚ[YR[
H‹Ë›Z[
BˆHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê•Œ×ÔT”ÓÓSUWÔÓÑ•ÔÒTWÍNNŠHHØ]Ú
Îˆ›İØX›JHßBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ•Œ×ÔT”ÓÓSUWÔÓÑ•ÔÒTWÍNN‹›Z[IİË›Z[ZÙJL
_HŞ[X›ÛIÚY[]KœŞ[X›ÛH™YÚ[YOI™YÚ[YR[Ú^™S][IÈ‰KŒ™ˆ‹™›Ü›X]
\œÛÛ˜[]TÚ^™S][
_HXİ[ÛXÛÛ[YWÛ›×Ú\™İ™]ÈŠHHØ]Ú
Îˆ›İØX›JHßBˆB‚ˆËÈ\HHÚ^™HØ\ØØYH‘ÈÛİ[]™H\YY‚ˆËÈ[ÙTÜXÚYšXÑØ]\ËœÜÚ][Û”Ú^™S][\Y\ˆ
\‹[[™KK™Ë‚ˆËÈY[Y\ÈHÛÜÙ\ˆØ\
H0åÈŞ[X›ÛXĞÛÛ^™Ù]Ú^™PY\İY[

BˆËÈ0åÈÙ[Y[˜ÙRÛÚÜËœİYÙÙ\İÚ^™S][\Y\Š
H
Ü›ÜÜËY[™Ú[™HšX\ÊK‚ˆ˜[[ÙS][\Y\œÈHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K“[ÙTÜXÚYšXÑØ]\Ë™Ù]][\Y\œÊ[ÙUYÊBˆHØ]Ú
Îˆ›İØX›JHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K“[ÙTÜXÚYšXÑØ]\Ë“[ÙS][\Y\œË‘QUSˆBˆ˜[TÚ^™S][HHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”Ù[Y[˜ÙRÛÚÜËœİYÙÙ\İÚ^™S][\Y\Šˆ[™Ú[™HH“QSQH‹ˆŞ[X›ÛHY[]KœŞ[X›Ûˆ™YÚ[YHH™YÚ[YR[ˆ
BˆHØ]Ú
Îˆ›İØX›JHÈKŒBˆËÈKKŒˆ8 %›ÙÜ™\ÜÚ]™HÚ^™Hš[Hœ›ÛHH]X[]SY\‹‚ˆËÈY\ˆ8¡¤ˆKŒY\ˆH8¡¤ˆLˆ™]™\ˆ™[İÈHÛÂˆËÈ›Û[YH\È™\Ù\™Y]™[ˆ]X^[][HØ]][Û‹‚ˆ˜[Y\”Ú^™S][HHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”]X[]SY\‹œÚ^™S][\Y\Š
HHØ]Ú
Îˆ›İØX›JHÈKŒBˆ˜[Ú^™P™Y›Ü™HH›ÜÜÙYÚ^™BˆËÈKKH8 %ĞTĞĞQH“ÓÔˆ
[KXÚÚÙJK‚ˆËÈÜ\˜]Üˆ	İHY[YH˜Y\ˆÚİ[XZ[Z[ˆ™X[HÛÛÙˆËÈ›Û[YHÛ˜ÙHX\›ˆ]Úİ[™]™\ˆ]™\ˆ™H[İÙYÂˆËÈÚÚÙH]Ù[ˆİ]‰ÂˆËÂˆËÈH][\Y\œÈÙ\™HİXÚÚ[™ÈİÛˆÈŒŒNpåÈ
[ÙHÌˆËÈ0åÈŞ[HM0åÈHKŒ0åÈY\ˆL
H8 %İ˜[™Û[™ÂˆËÈ[šY\ÈÈNIHÙˆ˜\ÙHÚ^™HÛˆ]™\HY[YH˜YKˆXXÚˆËÈ[\[™\ˆ[™]šYX[H\È™X\ÛÛ˜X›NÈH][\XØ]]™BˆËÈÛÛ\ÜÚ][Ûˆ\È›İˆ›ÛÜˆHÓÓP’S‘Q›ÙXİ]ŒˆËÈÛÈHÚ[™ÛHØ\ØØYHØ[ˆ™]™\ˆÚ\Hİ][Ü™H[ˆ	BˆËÈÙˆÜÚ][ÛˆÚ^™Kˆ›Û[YH™\Ù\™YÚYÛ˜[İ[İY\œË‚ˆ˜[˜]Ô›ÙXİBˆ[ÙS][\Y\œËœÜÚ][Û”Ú^™S][\Y\ˆ
‚ˆŞ[TÚ^™PYˆ
‚ˆTÚ^™S][
‚ˆ\œÛÛ˜[]TÚ^™S][
‚ˆY\”Ú^™S][ˆËÈKKM^ŒÈ8 %Ü\˜]Üˆ	Üİ\YHÛİÈ\\‹ˆÜ[‰Ë‚ˆËÈYH\\‹[[ÙH›ÛÜˆŒ8¡¤ŒÍHÛÈXXÚ[H\ÂˆËÈšYÙÙ\ˆ
ÍIHÙˆ˜\ÙH[œİXYÙˆŒ	JKˆ]™H›ÛÜˆİ^\ÂˆËÈ]Œ8 %š\ÚÈ\ØÚ\[™H™\Ù\™YÛˆ™X[[Û™^K‚ˆ˜[Ø\ØØYQ›ÛÜˆHYˆ
Ù™Ëœ\\“[ÙJHÍH[ÙHŒˆ˜[›ÛÜ™Y›ÙXİH˜]Ô›ÙXİ˜ÛÙ\˜ÙP]X\İ
Ø\ØØYQ›ÛÜŠBˆ›ÜÜÙYÚ^™HH
›ÜÜÙYÚ^™H
ˆ›ÛÜ™Y›ÙXİ
K˜ÛÙ\˜ÙR[ŠŒKKŒ
BˆYˆ
Ûİ[‹›X]˜XœÊ›ÜÜÙYÚ^™HHÚ^™P™Y›Ü™JHˆŒJHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ–ÕŒßÒV‘WĞĞTĞĞQWH	ÚY[]KœŞ[X›ÛH˜\ÙOIÜÚ^™P™Y›Ü™K™›]

_x¥ãˆ0åÈˆ
Âˆ›[ÙOIÈ‰KŒ™ˆ‹™›Ü›X]
[ÙS][\Y\œËœÜÚ][Û”Ú^™S][\Y\Š_H0åÈˆ
ÂˆœŞ[OIÈ‰KŒ™ˆ‹™›Ü›X]
Ş[TÚ^™PYŠ_H0åÈˆ
Âˆ›OIÈ‰KŒ™ˆ‹™›Ü›X]
TÚ^™S][
_H0åÈˆ
Âˆœ\œÛÛ˜OIÈ‰KŒ™ˆ‹™›Ü›X]
\œÛÛ˜[]TÚ^™S][
_H0åÈˆ
Âˆ›Y\IÈ‰KŒ™ˆ‹™›Ü›X]
Y\”Ú^™S][
_HH	È‰KŒ™ˆ‹™›Ü›X]
˜]Ô›ÙXİ
_Hˆ
Âˆ
Yˆ
˜]Ô›ÙXİØ\ØØYQ›ÛÜŠHˆÙ›ÛÜ™Y8¡¤‰È‰KŒ™ˆ‹™›Ü›X]
Ø\ØØYQ›ÛÜŠ_WHˆ[ÙHˆŠH
Âˆˆ8¡¤ˆ	Ü›ÜÜÙYÚ^™K™›]

_x¥ãˆˆ
ÂˆŠŞ[T™Yœ™\ÚYIŞ[T™Yœ™\ÚY[ÛÙIŞ[S[ÛÙÜ™Y[IÈ‰KŒ™ˆ‹™›Ü›X]
Ş[QÜ™Y[“YÚ
_JH‚ˆ
BˆB‚ˆËÈKŒLÌÈ8 %ŒÈ\›İ˜[\ÈØ]\Ø[[œ]›İHš[˜[]H\\ÜË‚ˆËÈ[ˆH™X[‘ÈÛ˜ÙH›ÜˆH[XİYØ[›ÛšXØ[[™H[™™\]Z\™BˆËÈ]È^Xİ[[]]X›H[[™Y›Ü™H^Xİ]ÜˆX^HÙYHHÜ™\‹‚ˆ˜[ŒĞØ[™Y]MLÌÈH[™T]X[YšYY^QXÚ\Ú[ÛŠˆXÚ\Ú[Û‹ŞXÛTš[X\S[™KˆÛÛ™šY[˜ÙQ›ÛÜˆH™\İ[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙˆZ[›Ü”›Ø™HHË›Z[ˆ
Bˆ˜[ŒÑ™ÍLÌÈHš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËØ[™Y]HHŒĞØ[™Y]MLÌËÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛH›ÜÜÙYÚ^™Kœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈH[ÙUYË[™TØÛÜ™HH™\İ[œØÛÜ™KÑİX›J
KˆÜXÚX[\İ[™HHŞXÛTš[X\S[™Kˆ
Bˆ˜[ŒĞØ[™Y]U™\œÚ[ÛLÌÈH[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹˜Ø[™Y]U™\œÚ[Û‘›ÜŠË›Z[
Bˆ˜[ŒÒ[[LÌÈH^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™Ğ[™Ù][[LÌÊˆZ[HË›Z[Ş[X›ÛHËœŞ[X›Û[™HHŞXÛTš[X\S[™KˆØ[‘^Xİ]HHŒÑ™ÍLÌË˜Ø[‘^Xİ]J
K™X\ÛÛˆHŒÑ™ÍLÌË˜›ØÚÔ™X\ÛÛ‹ˆÚYÛ˜[HYˆ
ŒÑ™ÍLÌË˜Ø[‘^Xİ]J
JH•VHˆ[ÙH““×Ğ•VH‹ˆYÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KØY™]UY\ˆHËœØY™]KY\‹›˜[YKˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ù\™›Ô™X\ÛÛœÈHËœØY™]Kš\™›ØÚÔ™X\ÛÛœËˆ™Q™Õ™\™XİHYˆ
ŒÑ™ÍLÌË˜Ø[‘^Xİ]J
JH
ŒÑ™ÍLÌË˜›ØÚÔ™X\ÛÛˆÎˆ•VHŠH[ÙH““×Ğ•VH‹ˆØ[™Y]U™\œÚ[ÛˆHŒĞØ[™Y]U™\œÚ[ÛLÌË[TØÛÜ™HH™\İ[œØÛÜ™KˆÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJKœ›İ]Tİ]\ËˆÚÙ[“X\Y˜][ÛÛÛ\]HHËÚÙ[“X\šY˜][ÛÛÛ\]KˆÚÙ[“X\^XİYİ]HËÚÙ[“X\™^XİYİ][[İ[ˆÚÙ[“X\›İšY\][\ÈHËÚÙ[“X\œ›İšY\][\Ëˆ™\]Z\™\ÔÛÛ[˜UÚÙ[“X\HYKˆ[İÕ[šÑ^Xİ][Û’[™Ù™LÌÈHYKˆ™\ÛÛ™YÚ^™TÛÛMNH›ÜÜÙYÚ^™Kˆ
BˆYˆ
]ŒÑ™ÍLÌË˜Ø[‘^Xİ]J
HŒÒ[[LÌÈOH[
HÂˆ˜[^XÚ]™X\ÛÛLÌÈHŒÑ™ÍLÌË˜›ØÚÔ™X\ÛÛˆÎˆ‘‘×ĞSÕ×ÕÒUÕUÑVP×ÒS•S•‚ˆHÂˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê•Œ×ÑVPÕUWÑVPÒUÔ‘R‘PÕÍLÌÈŠBˆYˆ
ŒÑ™ÍLÌË˜Ø[‘^Xİ]J
JH\[[™RX[ÛÛXİÜ‹›X™[[˜Ê•Œ×ĞSÕ×ÑVPÒUÔ‘R‘PÕÓ“×ÒS•S•ÍLÌÈŠBˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ•Œ×ÑVPÕUWÑVPÒUÔ‘R‘PÕÍLÌÈ‹›Z[IİË›Z[ZÙJL
_HŞ[X›ÛIİËœŞ[X›ÛH[™OIŞXÛTš[X\S[™H™X\ÛÛI^XÚ]™X\ÛÛLÌÈ™\œÚ[ÛIŒĞØ[™Y]U™\œÚ[ÛLÌÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆHÈ˜YP]]Üš^™\‹œ™[X\ÙTÜÚ][ÛŠË›Z[•Œ×Ñ‘×Ô‘R‘PÕÍLÌÈ‹˜YP]]Üš^™\‹‘^Xİ][Û›ÛÚËÓÔ‘JHHØ]Ú
Îˆ›İØX›JHßBˆHÈ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹œ™[X\ÙRY”š[X\JË›Z[ŞXÛTš[X\S[™K•Œ×Ñ‘×Ô‘R‘PÕÍLÌÈŠHHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆBˆYˆ
ŒÑ™ÍLÌËœÚ^™TÛÛˆŒ
H›ÜÜÙYÚ^™HHŒÑ™ÍLÌËœÚ^™TÛÛˆ˜[ŒĞ][\YHŒÒ[[LÌË˜][\Yˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑVPÕUSÓ—H	ÚY[]KœŞ[X›ÛH	ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸWĞ•VH	Ü›ÜÜÙYÚ^™K™›]

_HÓÓŠBˆˆËÈ™XÛÜ™›ÜÜØ[›ÜˆY\Bˆ˜YSY™XŞXÛKœ™XÛÜ™›ÜÜØ[
Y[]K›Z[
BˆˆËÈ^Xİ]H^H›İYÚ[šYšYY^Xİ]Ü‚ˆ^Xİ]Ü‹ŒĞ^JˆÈHËˆÚ^™TÛÛH›ÜÜÙYÚ^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆŒÔØÛÜ™HH™\İ[œØÛÜ™KˆŒĞ˜[™H™\İ[˜˜[™ˆŒĞÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKˆØ[]HØ[]ˆ\İİXØÙ\ÜÙ[Û\ÈH\İİXØÙ\ÜÙ[Û\ËˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆš[˜[]T™XÚXÚÙYH˜[ÙKˆ][\YHŒĞ][\Yˆ
BˆˆYÙÊ¸¦¨HŒÈVPÕUNˆ	ÚY[]KœŞ[X›ÛH	Ü™\İ[˜˜[™H	Ü›ÜÜÙYÚ^™K™›]

_HÓÓ‹Ë›Z[
BˆHËÈ[™]]™\İ[š\Ñ^Xİ]X›J
H[ÙH›ØÚÂˆH[ÙHÂˆËÈÚYİÈ[ÙHHÙÈÛ›Bˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÔÒQÕ×H	ÚY[]KœŞ[X›ÛHÓÕSÑVPÕUH	Ü™\İ[˜˜[™H	Ü™\İ[œÚ^™TÛÛ™›]

_HÓÓŠBˆYÙÊ¼'å+ŒÈÒQÕÎˆ	ÚY[]KœŞ[X›ÛH	Ü™\İ[˜˜[™H‹Ë›Z[
BˆBˆËÈKKLÎˆŒÈİÛœÈHXÚ\Ú[ÛˆÚ[ˆ[˜X›Y8 %È“Õ˜[ˆËÈ›İYÚÈHYØXŞH“ÓSÕSÓ—ÑĞUH]ÚXÚØ\ÂˆËÈÜİZØÈ[Z][™ÈÒQÕ×ÓÓ“HY\ˆHÛÜ™H^H[™XYBˆËÈš\™Yˆ™X\İ\H[™[İ\ˆ^Y\ˆ]˜[X][ÛœÈ˜[ˆX›İ™K‚ˆHËÈ[™\İX[İÙY[ÙH›ØÚÂˆ™]\›‚ˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹•Ø]ÚOˆÂˆËÈØXÚHŒÈØÛÜ™\ÈÛˆÚÙ[”İ]H›Üˆ™X\İ\H[ÙBˆË›\İŒÔØÛÜ™HH™\İ[œØÛÜ™BˆË›\İŒĞÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKÒ[

BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈĞUÒˆ˜XÚÈ]Û‰İ˜YH“Ô“PSBˆËÈ•U™X\İ\H[ÙHĞSˆİ[]˜[X]H\ÈÚÙ[ˆ›Üˆ]ZXÚÈØØ[ÈBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÔĞÓÔ’S‘×H	ÚY[]KœŞ[X›ÛHİ[IÜ™\İ[œØÛÜ™_H™[İÈ™\ÚÛŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑPÒTÒSÓ—H	ÚY[]KœŞ[X›ÛHĞUÒØÛÜ™OIÜ™\İ[œØÛÜ™_HÛÛ™IÜ™\İ[˜ÛÛ™šY[˜Ù_IHŠBˆˆËÈÚYİÈ˜XÚÈ›ÜˆX\›š[™ÂˆÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH•Œ×ÕĞUÒÜØÛÜ™OIÜ™\İ[œØÛÜ™_H‹ˆ›ØÚÓ]™[H•ŒÈ‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛHŒKˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
BˆˆËÈKKŒÍHÈKKŒÍLˆœšYÙHİ™\œšYHÛˆŒÈĞUÒ8 %Ø]YBˆËÈ›[™8¢iMŒ
ÈXÚ8¢iMMH
È\]ZY]KSÒÈ
È˜]K[[Z]ˆÙÙÚ[™Ë[Û›BˆËÈ›Üˆ]™\HÚÙ[È^Xİ][ÛˆÛ›HÚ[ˆÛÛšXİ[Ûˆ\ÈYÚ‚ˆYˆ
Y[YPœšYÙU™\™XİËœÚİ[[\ˆOHYJHÂˆ˜[
[İËÚJHHœšYÙSİ™\œšYP[İÙY
Y[YPœšYÙU™\™XİË›Z[Y[]KœŞ[X›Û
BˆYˆ
[İÊHÂˆHÂˆ˜[œšYÙTÚ^™HHŒBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã"H”’QÑHÕ‘T”’QHÛˆŒ×ÕĞUÒˆ	ÚY[]KœŞ[X›ÛHXÚIÛY[YPœšYÙU™\™XİXÚØÛÜ™_H›[™IÛY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™_HŠBˆYÙÊ¼'ã"HœšYÙH•VNˆ	ÚY[]KœŞ[X›ÛHŒ×ÕĞUÒİ™\œšYHXÚIÛY[YPœšYÙU™\™XİXÚØÛÜ™_H›[™IÛY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™_H	ØœšYÙTÚ^™_HÓÓ‹Ë›Z[
Bˆ^Xİ]Ü‹ŒĞ^JˆÈHËˆÚ^™TÛÛHœšYÙTÚ^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆŒÔØÛÜ™HHY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™KˆŒĞ˜[™H“QSQWĞ”’QÑH‹ˆŒĞÛÛ™šY[˜ÙHHŒŒˆØ[]HØ[]ˆ\İİXØÙ\ÜÙ[Û\ÈH\İİXØÙ\ÜÙ[Û\ËˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆ
BˆHØ]Ú
™Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙH^Xİ]H˜Z[YÛˆĞUÒ	ÚY[]KœŞ[X›ÛNˆ	Ø™K›Y\ÜØYÙ_HŠBˆBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙHİ™\œšYHÒÒT
ĞUÒ	ÚY[]KœŞ[X›ÛJNˆ	ÚHŠBˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒËŒÈ’VˆÈ“Õ‘UT“ˆH[İÈ™X\İ\H[ÙH]˜[X][Ûˆ™[İÈBˆËÈ™X\İ\H[ÙH[œÈÓÓÕT”‘S•H[™Ø[ˆØØ[ĞUÒÚÙ[œÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ™]š[İ\ÛNˆ™]\›ˆ
“ĞÒÑQ™X\İ\H[ÙHJBˆËÈKKLÎˆ™X\İ\H[™XYH]˜[X]YX›İ™H
[™HJK‚ˆËÈ™]\›š[™È\™H™]™[ÈYØXŞH“ÓSÕSÓ—ÑĞUHÛËYš\š[™Ë‚ˆ™]\›‚ˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹”ÚYİÓÛ›HOˆÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈÒQÕ×ÓÓ“Nˆ™K\›ÜÜØ[Ú[X\›š[™ÈÛ›BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑPÒTÒSÓ—H	ÚY[]KœŞ[X›ÛHÒQÕ×ÓÓ“H	Ü™\İ[œ™X\ÛÛŸHŠBˆˆËÈÚYİÈ˜XÚÈ›ÜˆX\›š[™ÂˆÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH•Œ×ÔÒQÕ×ÓÓ“WÉÜ™\İ[œ™X\ÛÛŸH‹ˆ›ØÚÓ]™[H•Œ×Ô‘WÔ“ÔÔĞS‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛHŒKˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHH™\İ[˜ÛÛ™šY[˜ÙKÑİX›J
Kˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
BˆˆËÈKKŒÍHÈKKŒÍLˆœšYÙHİ™\œšYHÛˆŒÈÒQÕ×ÓÓ“H8 %Ø]Y‚ˆYˆ
Y[YPœšYÙU™\™XİËœÚİ[[\ˆOHYJHÂˆ˜[
[İËÚJHHœšYÙSİ™\œšYP[İÙY
Y[YPœšYÙU™\™XİË›Z[Y[]KœŞ[X›Û
BˆYˆ
[İÊHÂˆHÂˆ˜[œšYÙTÚ^™HHŒBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã"H”’QÑHÕ‘T”’QHÛˆŒ×ÔÒQÕ×ÓÓ“Nˆ	ÚY[]KœŞ[X›ÛHXÚIÛY[YPœšYÙU™\™XİXÚØÛÜ™_H›[™IÛY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™_HŠBˆYÙÊ¼'ã"HœšYÙH•VNˆ	ÚY[]KœŞ[X›ÛHŒ×ÔÒQÕÈİ™\œšYHXÚIÛY[YPœšYÙU™\™XİXÚØÛÜ™_H›[™IÛY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™_H	ØœšYÙTÚ^™_HÓÓ‹Ë›Z[
Bˆ^Xİ]Ü‹ŒĞ^JˆÈHËˆÚ^™TÛÛHœšYÙTÚ^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆŒÔØÛÜ™HHY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™KˆŒĞ˜[™H“QSQWĞ”’QÑH‹ˆŒĞÛÛ™šY[˜ÙHHŒŒˆØ[]HØ[]ˆ\İİXØÙ\ÜÙ[Û\ÈH\İİXØÙ\ÜÙ[Û\ËˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆ
BˆHØ]Ú
™Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙH^Xİ]H˜Z[YÛˆÒQÕ×ÓÓ“H	ÚY[]KœŞ[X›ÛNˆ	Ø™K›Y\ÜØYÙ_HŠBˆBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙHİ™\œšYHÒÒT
ÒQÕ×ÓÓ“H	ÚY[]KœŞ[X›ÛJNˆ	ÚHŠBˆBˆBˆˆ™]\›‚ˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹”™Z™XİYOˆÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈ‘R‘PÕˆÛÜˆÙ]\Ôˆ›İ][™ÈÈ[›İ\ˆ^Y\‚ˆËÈKŒ‹ŒLˆÒUÓÒS—ĞĞS‘QUH™Z™Xİ[ÛˆYX[œÈ›]Ú]ÛÚ[ˆ^Y\ˆ[™H]‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈÚXÚÈYˆ\È\ÈH›İ][™È™Z™Xİ[Ûˆ
Ú]ÛÚ[ˆØ[™Y]JBˆ˜[\ÔÚ]ÛÚ[”›İ][™ÈH™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ”ÒUÓÒS—ĞĞS‘QUHŠBˆˆYˆ
\ÔÚ]ÛÚ[”›İ][™ÊHÂˆËÈKŒ‹ŒLˆÛ‰İ™]\›ˆH]HÚ]ÛÚ[ˆ]˜[X][ÛˆÙXİ[Ûˆ[™H\Âˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹–ÕŒß“ÕUWH	ÚY[]KœŞ[X›ÛH8¡¤ˆÒUÓÒSˆ	Ü™\İ[œ™X\ÛÛŸHŠBˆËÈ˜[›İYÚÈÚ]ÛÚ[ˆ^Y\ˆ]˜[X][Ûˆ™[İÂˆH[ÙHÂˆËÈYH™Z™Xİ[ÛˆHÚYİÈ˜XÚÈ[™™]\›‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑPÒTÒSÓ—H	ÚY[]KœŞ[X›ÛH‘R‘PÕ	Ü™\İ[œ™X\ÛÛŸHŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘PÒTÒSÓˆ‹™\İ[œ™X\ÛÛŠBˆˆËÈÚYİÈ˜XÚÂˆÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH•Œ×Ô‘R‘PÕÉÜ™\İ[œ™X\ÛÛŸH‹ˆ›ØÚÓ]™[H•ŒÈ‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛHŒKˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHHŒˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
BˆˆËÈKKŒÍHÈKKŒÍLˆœšYÙHİ™\œšYHÛˆŒÈ‘R‘PÕ8 %Ø]Y‚ˆYˆ
Y[YPœšYÙU™\™XİËœÚİ[[\ˆOHYJHÂˆ˜[
[İËÚJHHœšYÙSİ™\œšYP[İÙY
Y[YPœšYÙU™\™XİË›Z[Y[]KœŞ[X›Û
BˆYˆ
[İÊHÂˆHÂˆ˜[œšYÙTÚ^™HHŒBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã"H”’QÑHÕ‘T”’QHÛˆŒ×Ô‘R‘PÕˆ	ÚY[]KœŞ[X›ÛHXÚIÛY[YPœšYÙU™\™XİXÚØÛÜ™_H›[™IÛY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™_HŠBˆYÙÊ¼'ã"HœšYÙH•VNˆ	ÚY[]KœŞ[X›ÛHŒ×Ô‘R‘PÕİ™\œšYHXÚIÛY[YPœšYÙU™\™XİXÚØÛÜ™_H›[™IÛY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™_H	ØœšYÙTÚ^™_HÓÓ‹Ë›Z[
Bˆ^Xİ]Ü‹ŒĞ^JˆÈHËˆÚ^™TÛÛHœšYÙTÚ^™KˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆŒÔØÛÜ™HHY[YPœšYÙU™\™Xİ˜›[™YØÛÜ™KˆŒĞ˜[™H“QSQWĞ”’QÑH‹ˆŒĞÛÛ™šY[˜ÙHHŒŒˆØ[]HØ[]ˆ\İİXØÙ\ÜÙ[Û\ÈH\İİXØÙ\ÜÙ[Û\ËˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆ
BˆHØ]Ú
™Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙH^Xİ]H˜Z[YÛˆ‘R‘PÕ	ÚY[]KœŞ[X›ÛNˆ	Ø™K›Y\ÜØYÙ_HŠBˆBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙHİ™\œšYHÒÒT
‘R‘PÕ	ÚY[]KœŞ[X›ÛJNˆ	ÚHŠBˆBˆBˆˆ™]\›‚ˆBˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÑ˜][OˆÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈ“ĞÒ×ÑUSˆ˜][š\ÚÈ]XİYˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑPÒTÒSÓ—H	ÚY[]KœŞ[X›ÛH“ĞÒ×ÑUS	Ü™\İ[œ™X\ÛÛŸHŠBˆ™]\›‚ˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÙYOˆÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈ“ĞÒÎˆYØXŞH˜][›ØÚÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÑPÒTÒSÓ—H	ÚY[]KœŞ[X›ÛH“ĞÒ×ÑUS	Ü™\İ[œ™X\ÛÛŸHŠBˆ™]\›‚ˆBˆˆ[ÙHOˆÂˆËÈŒÈ›İ™XYHÜˆ\œ›ÜˆHÚÚ\\ÈÚÙ[‚ˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹–ÕŒ×H	ÚY[]KœŞ[X›ÛH“ÕÔ‘PQHŠBˆ™]\›‚ˆBˆBˆˆHØ]Ú
ŒÙNˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹–ÕŒ×H	ÚY[]KœŞ[X›ÛHT”“Ôˆ	İŒÙK›Y\ÜØYÙ_HŠBˆ™]\›‚ˆBˆˆËÈKKLÎˆ[HŒËY[˜X›YÛÙH]]™XXÚ\È\™H
K™Ëˆ™Z™XİYˆËÈÚ]ÒUÓÒS—ĞĞS‘QUH›İ][™ËÚXÚ[[[Û˜[H˜[È›İYÚˆËÈHÚ[ŠHÚİ[“Õ™KY[\ˆHYØXŞH“ÓSÕSÓ—ÑĞUKÑ‘È\[[™BˆËÈ8 %[[YÚX›H^Y\œÈ
™X\İ\KÚ]ÛÚ[‹›YPÚ\[ÛÛœÚİT
BˆËÈ]™H[™XYH]˜[X]YX›İ™Kˆ™]\›š[™È\™H™]™[ÈBˆËÈÜİZØÈÒQÕ×ÓÓ“H[Z\ÜÚ[ÛˆÙHØ]È[ˆH]Y]ÙË‚ˆ™]\›‚ˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ“ÕNˆ™X\İ\H[ÙH›İÈ[œÈ‘Q“Ô‘HHŒÈÚ[ˆ›ØÚÈ
X›İ™JBˆËÈÈ[œİ\™H]]˜[X]\ÈSÚÙ[œÈ™YØ\™\ÜÈÙˆŒÈXÚ\Ú[Û‹‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQĞPÖHSPÒÎˆÛ›H[œÈYˆŒÈ\È\ØX›YˆËÈ\È]Ú[™H\™XØ]YÛ˜ÙHŒÈ\È[H˜[Y]YˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d‚ˆËÈKKMˆXİX[H[™›Ü˜ÙHÚ]HÛÛ[Y[X›İ™H›ÛZ\ÙYˆÚ[ˆŒÈ\ÂˆËÈ[˜X›YHYØXŞH“ÓSÕSÓ—ÑĞUHÈ‘È\[[™H™[İÈ\ÈXYÛÙBˆËÈ]Ø\Èİ[š\š[™È›ÜˆÚÙ[œÈÚ\™HŒÈØ\ÈÚÚ\Y]ÌH
Z]\‚ˆËÈ™XØ]\ÙHËœÜÚ][Û‹š\ÓÜ[ˆOHYKÜˆŒÑ[™Ú[™SX[˜YÙ\‹š\Ô™XYJ
HØ\ÂˆËÈ˜[ÙJKˆÜÙH[Z\ÜÚ[ÛœÈ›ÙXÙYH[ÛH–ÕŒß“ÓSÕSÓ—ÑĞUWHˆËÈ[İÏY˜[ÙH8¡¤ˆÒQÕ×ÓÓ“Hˆ[™\ÈÙHØ]ÈÛˆ•SÈÓ“ÕÈÈS”ÈÂˆËÈSˆÈÙÈÈTÑĞHÈÔĞÈÈT“RSSÈĞUT“ÒQÈÒQˆÈH8 %›Û™HÙ‚ˆËÈÚXÚÙ\™H]™\ˆŒË\ØÛÜ™Yˆ\™YØ]HÛˆÙ™ËŒÑ[™Ú[™Q[˜X›YÛÈBˆËÈYØXŞH]\ÈÙ[Z[™[H\™XØ]YÚ[ˆŒÈ\ÈÛ‹‚ˆYˆ
Ù™ËŒÑ[™Ú[™Q[˜X›Y	‰ˆ]ËœÜÚ][Û‹š\ÓÜ[ŠHÂˆ™]\›‚ˆB‚ˆËÈYØXŞHİ\™\ÜÚ[Ûˆ[˜[H
›ÜˆÛÛ\\š\ÛÛˆÙÙÚ[™ÊBˆ˜[İ\™\ÜÚ[Û”[˜[HH\İšX][Û‘˜YP]›ÚY\‹™Ù]İ\™\ÜÚ[Û”[˜[JY[]K›Z[
BˆˆËÈ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆËÈT‘ĞUNˆ›ØÚÈYÙOTÒÒTÜˆÛÛ™L‘Q“Ô‘HØ[™Y]H›Û[İ[Û‚ˆËÈKŒˆ[İÈÒÒT›İYÚ\š[™È›Ûİİ˜\
Œ	HX\›š[™ÊH›ÜˆŒÈX\›š[™ÂˆËÈ\È™]™[ÈØ\˜˜YÙHœ›ÛHÛÚ[™È›İYÚĞS‘QUKÔ“ÔÔÑQÔÒV’S‘ÂˆËÈ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆ˜[YÙU™\™XİİˆHXÚ\Ú[Û‹™YÙT]X[]HËÈH‹ˆ‹È‹Üˆ”ÒÒT‚ˆ˜[ÛÛ™•˜[YHHXÚ\Ú[Û‹˜ZPÛÛ™šY[˜ÙBˆˆËÈÚXÚÈYˆÙIÜ™H[ˆ›Ûİİ˜\\ÙH8 %[İÈÒÒT˜Y\È›İYÚ›ÜˆŒÈX\›š[™Ë‚ˆËÈ™\ÚÛ[YÛ™YÚ]KŒÈË\\ÙHİ\™Nˆ›Ûİİ˜\[œÈ8¡¤ŒH
š\œİL˜Y\ÊK‚ˆËÈØ\ÈŒŒÚXÚØ\Èİ][™ÈÙ™ˆ›Ûİİ˜\X\›š[™È]Û›HŒL˜Y\Ë‚ˆ˜[X\›š[™Ô›ÙÜ™\ÜÈHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]X\›š[™Ô›ÙÜ™\ÜÊ
BˆHØ]Ú
Îˆ^Ù\[ÛŠHÈŒBˆ˜[\Ğ›Ûİİ˜\HÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆX\›š[™Ô›ÙÜ™\ÜÈËÈKŒŒNˆ\\‹[Û›HÚÚ\[İØ[˜ÙNÈ]™HY\Èœ›ÛH˜YHB‚ˆËÈKKŒÌH“RQˆH›İÚÛÜÙ\È]ÈİÛˆÛÛ™ˆ›ÛÜˆšXH›ZYX\›š[™ĞRK‚ˆËÈ›Ûİİ˜\
	JNˆŒMH8 %ÚYHÜ[‹Ø]\ˆ]K‚ˆËÈX]\™H
	JNˆH8 %š[\ˆİËXÛÛšXİ[Ûˆ›Ú\ÙH\ÈÚ[œ˜]H]HXØÜY\Ë‚ˆËÈ\ÈšYÈÚ]]™H\™›Ü›X[˜ÙH8 %›È[X[‹\Ù][X™\ˆ[ˆHİ]‚ˆËÈKKKÍÎˆ“RQÒÒTX[İØ[˜ÙKˆ\™ÛÙY˜[ÙXYX[]™\HÒÒTˆËÈØ[™Y]HØ\ÈÚYİË]˜XÚÙY›Ü™]™\‹ˆ›İÈ›ZY
ÈØXÚY‚ˆËÂˆËÈKKÎˆİØ\È˜YR\İÜTİÜ™K™Ù]›İ™[‘YÙPØXÚY

H8 %™]š[İ\ÂˆËÈ™\œÚ[ÛˆØ[Y[Ù]İ]Ê
H\™H\‹]ÚÙ[‹ÚXÚYËM[ˆËÈ]\˜][ÛœÈÙˆH˜YH\İ[™İ\™YHØØ[›™\ˆÛÜ›İ][™\ÂˆËÈ
\Ù\ˆØ]ÈØ]Ú\İİÜÜ[][™È[[H›İ™\İ\
K‚ˆ˜[›İ™[‘YÙHHÛÛK›Y™XŞXÛX›İ™[™Ú[™K•˜YR\İÜTİÜ™K™Ù]›İ™[‘YÙPØXÚY

Bˆ˜[›İ™[•Ú[”˜]HH›İ™[‘YÙKÚ[”˜]Bˆ˜[YX[š[™Ù[Ûİ[H›İ™[‘YÙK›YX[š[™Ù[˜Y\Âˆ˜[\Ô›İ™[‘YÙHH›İ™[‘YÙKš\Ô›İ™[‘YÙB‚ˆËÈKŒŒH8 %ÒÒTÛX\›š[™Ë[Ü[ˆ\\ÜÙ\È\™H\\‹[Û›Kˆ›İ™[ˆ]™HYÙBˆËÈØ[ˆÚ\HÚ^™KİXİXË]]Ø[››İİ™\œšYHØY™]KZ\ÚÒÒTYZ\ÜÚ[Û‹‚ˆ˜[[İÔÚÚ\›Ü“X\›š[™ÈH\Ğ›Ûİİ˜\ˆ˜[™MLX\›š[™ÓÜ[ˆHHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
H	‰ˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K‘œ™YT˜[™ÙS[ÙKš\ÕÚYSÜ[Š
HHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆ˜[Z[›Ûİİ˜\ÛÛ™ˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]\\ÛÛ™šY[˜ÙQ›ÛÜŠ
KÒ[

B‚ˆËÈKŒŒÎÌH8 %Ü\˜]ÜˆÔHœ›ÛHH‹LMÈŒŒLHÜ\˜][Û˜[[\‚ˆËÈ8 (ˆÔNK	HÛˆLMÈ˜Y\ËÛ™Ù\İÜÜÈİ™XZÈ‹Ë	K‚ˆËÈ8 (ˆÛ[ÚÚ[™ËYİ[ˆÙÈ[™\È™\X]Y›ÜˆÛ[ËĞTĞÑS‘ĞÒQÓVM‚ˆËÈÕŒßÒÒTÓÕ‘T”’QWHPT“’S‘×ÓÔS—Ô‘MLˆËÈYÙOTÒÒTÛÛ™NX\›š[™ÏL	H[İÚ[™È›İYÚˆËÂˆËÈ™MLX\›š[™ÓÜ[ˆ
\\‹[Û›Hœ™YT˜[™ÙS[ÙKš\ÕÚYSÜ[‹Y™][YHÙ[ÈL
BˆËÈİ\œ™[HXZÙ\ÈHÚÛHÒÒTÈÛÛ™‹Y›ÛÜˆØ]H™[İÈ\Ø\X\‹ÛÂˆËÈ[PRONX]UŒÏTÒÒT
ØÛÛ™NØ[™Y]\È^HÛˆ	LMˆ\]ZY]HÚÙ[œÂˆËÈ[™[[YYX][HÜÙKˆHÜ\˜]Ü‰ÜÈØİš[™H8 %HY[YH˜Y\‚ˆËÈ]\İ™]™\ˆÚÚÙH]Ù[ˆİ]ˆ8 %\È™\Ù\™YHÙY\[™È™MLÜ[‚ˆËÈ[ˆÜ\š]]™MLØ\È™Z[™È[\œ™]Y\È
›]\˜[Jˆ˜^BˆËÈ[][™ÈÚ][HÚYÛ˜[‹ÚXÚ\ÈÚ]›ÙXÙ\ÈH›YY‚ˆËÂˆËÈİ\™ÚXØ[Ø[š]H›ÛÜœÈ]™ML]\İ[Ø^\È™\ÜXİ‚ˆËÈKˆ8 %ŒÈÒÒT]\İ]™HÛÛ™ˆHˆ0åÈZ[›Ûİİ˜\ÛÛ™‹ˆ]ˆËÈX\›š[™ÏL	HH›ZY›ÛÜˆ\ÈŒËÛÈÒÒT™\]Z\™\ÈÛÛ™ˆHÂˆËÈ]X\›š[™ÏN	HH›ÛÜˆ\ÈŒÍ‹ÛÈÒÒT™\]Z\™\ÈÛÛ™ˆHÌ‹‚ˆËÈ\Èİ[]ÈÕ“Ó‘ÈÚYÛ˜[È›İYÚ\š[™È^Ü˜][ÛÈ]ˆËÈÛ›H›ØÚÜÈHÛÛ™L‹ÍÎ›Ú\ÙH]	HX\›š[™È[Z]Ë‚ˆËÈ‹ˆH8 %”‘TÒÓUSÒ[™H™\]Z\™\È\İ\]ZY]U\ÙH	ZËˆÛ\ˆËÈÛˆ	ZÈ\]ZY]HX]ÈH\™Ù][ˆÛ™H›İ]H›İ[™]š\ˆËÈ™YØ\™\ÜÈÙˆİÈİ›Û™ÈHÚYÛ˜[ÛÚÜË‚ˆËÈËˆH8 %[š]™\œØ[\]ZY]HØ[š]Nˆ[HØ[™Y]HÚ]ˆËÈ\İ\]ZY]U\Ùˆ[™	šÈ\È\™X›ØÚÙYˆ
ÙHÛ\˜]BˆËÈ\İ\]ZY]U\ÙOHŒ™XØ]\ÙH]\İYX[œÈÙH]™[‰İˆËÈÛY]Y]8 %]H›Ü›X[›İÈ[‹ŠBˆËÂˆËÈXXÚ›ØÚÈÜš]\ÈÒQÕ×ÓÓ“H›İYÚH^\İ[™ÈÚYİÓX\›š[™Ñ[™Ú[™BˆËÈ]Ø^HÛÈHŒÈœ˜Z[ˆİ[X\›œÈœ›ÛHH™Z™Xİ[Û‹\İÚ]İ]ˆËÈ\›š[™ÈØ\][‚ˆ˜[[™U\HH
XÚ\Ú[Û‹œ\ÙHÎˆˆŠK\\˜Ø\ÙJ
Bˆ˜[\Ñœ™\Ú][˜ÚH[™U\K˜ÛÛZ[œÊ‘”‘TÒÓUSÒŠH[™U\K˜ÛÛZ[œÊ”STÑ•SˆŠHˆ[™U\K˜ÛÛZ[œÊ”VQUSWÓ‘UÈŠBˆ˜[\U\ÙHHÈË›\İ\]ZY]U\ÙHØ]Ú
Îˆ›İØX›JHÈŒBˆ˜[™MLÚÚ\ÛÛ™‘›ÛÜˆH
ˆ
ˆZ[›Ûİİ˜\ÛÛ™ŠK˜ÛÙ\˜ÙP]X\İ
ŠBˆ˜[ÚÚ\›Ú\ÙR[”™MLHYÙU™\™XİİˆOH”ÒÒTˆ	‰ˆÛÛ™•˜[YH™MLÚÚ\ÛÛ™‘›ÛÜ‚ˆ˜[œ™\Ú][˜Ú\Tİ\™YH\Ñœ™\Ú][˜Ú	‰ˆ\U\Ù[ˆŒK‹ÎNNKŒˆ˜[[š]™\œØ[\Tİ\™YH\U\Ù[ˆŒK‹ŒWÎNNKŒˆ˜[™MLØ[š]P›ØÚÈH™MLX\›š[™ÓÜ[ˆ	‰ˆ
ÚÚ\›Ú\ÙR[”™MLœ™\Ú][˜Ú\Tİ\™Y[š]™\œØ[\Tİ\™Y
BˆYˆ
™MLØ[š]P›ØÚÈ	‰ˆ]ËœÜÚ][Û‹š\ÓÜ[ŠHÂˆ˜[ÚHHÚ[ˆÂˆÚÚ\›Ú\ÙR[”™MLOˆ”ÒÒTÓ“ÒTÑWØÛÛ™—ÉØÛÛ™•˜[YKÒ[

_WÏÉÜ™MLÚÚ\ÛÛ™‘›ÛÜŸH‚ˆœ™\Ú][˜Ú\Tİ\™YOˆ‘”‘TÒÓUSÒÓTWÔÕT•‘QÉÛ\U\ÙÒ[

_WÏÍL‚ˆ[š]™\œØ[\Tİ\™YOˆ“TWÔÕT•‘QÉÛ\U\ÙÒ[

_WÏÌŒ‚ˆ[ÙHOˆ”‘MLÔĞS’UH‚ˆBˆ\œ›Ü“ÙÙÙ\‹š[™›Êˆ›İÙ\šXÙH‹ˆ–ÕŒß‘MLÔĞS’UWĞ“ĞÒ×H	ÚY[]KœŞ[X›ÛH™X\ÛÛIÚHˆ
Âˆ™YÙOIYÙU™\™XİİˆÛÛ™IØÛÛ™•˜[YKÒ[

_H\OIÛ\U\ÙÒ[

_H8¡¤ˆÒQÕ×ÓÓ“H‹ˆ
BˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH”‘MLÔĞS’UWÉİÚ_H‹ˆ›ØÚÓ]™[H”‘MLÔĞS’UH‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛHŒKˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHHÛÛ™•˜[YKˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
Bˆ™]\›‚ˆB‚ˆYˆ
\™MLX\›š[™ÓÜ[ˆ	‰ˆ

YÙU™\™XİİˆOH”ÒÒTˆ	‰ˆX[İÔÚÚ\›Ü“X\›š[™ÊHÛÛ™•˜[YHZ[›Ûİİ˜\ÛÛ™ŠJHÂˆËÈKKŒÌ’VˆÔ’UPĞS8 %Ó“HÚÚ\S•–HYˆØÛÜ™H\ÈÛÈİË‚ˆËÈ‘U‘Tˆ™]\›ˆ\™HYˆÜÚ][Ûˆ\È[™XYHÔSˆ8 %]Ûİ[Ú[H^]]ˆËÈ[™X]™H]™HÜÚ][ÛœÈÛÛ\][H[›[Ûš]Ü™Y
›ÈÔÓİ™X\İ\H^]ÚXÚÜÊK‚ˆYˆ
]ËœÜÚ][Û‹š\ÓÜ[ŠHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÕŒß“ÓSÕSÓ—ÑĞUWH	ÚY[]KœŞ[X›ÛH[İÏY˜[ÙHˆ
Âˆœ™X\ÛÛYYÙWÉÙYÙU™\™Xİİ‹›İÙ\˜Ø\ÙJ
_WØÛÛ™—ÉØÛÛ™•˜[YKÒ[

_H
›ÛÜIZ[›Ûİİ˜\ÛÛ™ŠH8¡¤ˆÒQÕ×ÓÓ“HŠBˆˆËÈÚYİÈ˜XÚÈ›ÜˆX\›š[™ÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH”“ÓSÕSÓ—ÑĞUWÙYÙWÉÙYÙU™\™XİİŸWØÛÛ™—ÉÛÛ™•˜[YH‹ˆ›ØÚÓ]™[H“QĞPÖWÑĞUH‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛHŒKˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHHÛÛ™•˜[YKˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
Bˆˆ™]\›ˆËÈ^]™Y›Ü™HĞS‘QUKÔ“ÔÔÑQ
[HÛ›H8 %ÜÚ][Ûˆ\È›İÜ[ŠBˆBˆËÈYˆÜÚ][ÛˆTÈÜ[ˆ˜[›İYÚÈ^]X[˜YÙ[Y[
ÌLJHÛÈÓÕš\™HÛÜœ™XİBˆBˆˆËÈÙÈÚ[ˆÚÚ\İ™\œšYH\È\ÙYˆYˆ

[İÔÚÚ\›Ü“X\›š[™È™MLX\›š[™ÓÜ[ŠH	‰ˆYÙU™\™XİİˆOH”ÒÒTŠHÂˆ˜[™X\ÛÛˆHÚ[ˆÂˆ™MLX\›š[™ÓÜ[ˆOˆ“PT“’S‘×ÓÔS—Ô‘ML‚ˆ\Ô›İ™[‘YÙH	‰ˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
HOˆ”TT—Ô“Õ‘S—ÑQÑH
ÜIÜ›İ™[•Ú[”˜]KÒ[

_IHIYX[š[™Ù[Ûİ[
H‚ˆ[ÙHOˆ“ÓÕÕT‚ˆBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÕŒßÒÒTÓÕ‘T”’QWH	ÚY[]KœŞ[X›ÛH	™X\ÛÛˆˆ
Âˆ™YÙOIYÙU™\™XİİˆÛÛ™IØÛÛ™•˜[YKÒ[

_HX\›š[™ÏIÊX\›š[™Ô›ÙÜ™\ÜÈ
ˆL
KÒ[

_IH[İÚ[™È›İYÚŠBˆBˆˆËÈ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆËÈT‘ĞUHˆ›ØÚÈËYÜ˜YH
ÈİÈÛÛ™šY[˜ÙBˆËÈKŒ‹ŒLˆ\\ˆ[ÙH\ÈÕÑTˆ›ÛÜˆÈ[İÈ[Ü™HX\›š[™ÂˆËÈ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆ˜[\ĞÑÜ˜YHHXÚ\Ú[Û‹œÙ]\]X[]HOHÈˆXÚ\Ú[Û‹œÙ]\]X[]HOH‘‚ˆ˜[›ZYÑÜ˜YPÛÛ™‘›ÛÜˆHHÂˆ˜[X\›š[™Ô›ÙÜ™\ÜÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù]X\›š[™Ô›ÙÜ™\ÜÊ
BˆËÈKŒŒNˆ[ÙH[šY[˜ŞH\È\\‹[Û›Kˆ›İ™[ˆ]™HYÙHØ[ˆÚ\BˆËÈÚ^™KİXİXË]]\İ›İİÙ\ˆËYÜ˜YHØY™]KØYZ\ÜÚ[Ûˆ›ÛÜœË‚ˆ˜[[šY[HÛÛK›Y™XŞXÛX›İ™[™Ú[™K“[ÙS[šY[˜ŞK\ÙS[šY[Ø]\ÊÙ™Ëœ\\“[ÙJBˆYˆ
[šY[
HÂˆËÈ\\ˆÛ›Nˆ™\HİÈ›ÛÜˆÈX^[Z\ÙHX\›š[™È›Û[YBˆ
H
È
X\›š[™Ô›ÙÜ™\ÜÈ
ˆKŒ
JKÒ[

K˜ÛÙ\˜ÙR[ŠKL
BˆH[ÙHÂˆËÈ]™Nˆ[Ù\˜]H›ÛÜˆœ›ÛH˜YHBˆ
H
È
X\›š[™Ô›ÙÜ™\ÜÈ
ˆŒ
JKÒ[

K˜ÛÙ\˜ÙR[ŠKLÊBˆBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈYˆ
Ù™Ëœ\\“[ÙJHH[ÙHHBˆˆËÈKNˆYˆ›Ûİİ˜\ÒÒTİ™\œšYHØ\È\ÙY]Ø]HKÛ‰İ™KX›ØÚÈ]Ø]H‹‚ˆËÈ\ÙHÚÙ[œÈ\™H[X™\˜][H]›İYÚ›ÜˆX\›š[™È]™[ˆÚ]ÛÛ™L‚ˆYˆ
\™MLX\›š[™ÓÜ[ˆ	‰ˆ\ĞÑÜ˜YH	‰ˆÛÛ™•˜[YH›ZYÑÜ˜YPÛÛ™‘›ÛÜˆ	‰ˆX[İÔÚÚ\›Ü“X\›š[™ÊHÂˆËÈKKŒÌ’VˆØ[YH\ÈØ]HH8 %Ó“H›ØÚÈS•–K™]™\ˆÚ[^]]›ÜˆÜ[ˆÜÚ][ÛœÂˆYˆ
]ËœÜÚ][Û‹š\ÓÜ[ŠHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÕŒß“ÓSÕSÓ—ÑĞUWH	ÚY[]KœŞ[X›ÛH[İÏY˜[ÙHˆ
Âˆœ™X\ÛÛP×ÙÜ˜YWØÛÛ™—ÉØÛÛ™•˜[YKÒ[

_WØ™[İ×É›ZYÑÜ˜YPÛÛ™‘›ÛÜˆ8¡¤ˆÒQÕ×ÓÓ“HŠBˆˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH”“ÓSÕSÓ—ÑĞUWĞ×ÑÔQWĞÓÓ‘—Ñ“ÓÔˆ‹ˆ›ØÚÓ]™[H“QĞPÖWÑĞUH‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛHŒKˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]KˆÛÛ™šY[˜ÙHHÛÛ™•˜[YKˆ\ÙHHXÚ\Ú[Û‹œ\ÙKˆ
Bˆˆ™]\›ˆËÈ^]™Y›Ü™HĞS‘QUKÔ“ÔÔÑQ
[HÛ›H8 %ÜÚ][Ûˆ\È›İÜ[ŠBˆBˆËÈYˆÜÚ][ÛˆTÈÜ[ˆ˜[›İYÚÈ^]X[˜YÙ[Y[ˆBˆˆYˆ
]ËœÜÚ][Û‹š\ÓÜ[ˆ	‰ˆXÚ\Ú[Û‹™š[˜[ÚYÛ˜[OH•VHˆ	‰ˆØ[”›ÜÜÙQX\›H	‰‚ˆŞXÛTš[X\S[™K\\˜Ø\ÙJ
HZ[ˆÙ]ÙŠ•Œ×ĞÓÔ‘H‹”ÕS‘T‘‹ĞTÒÑSˆŠJHÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQHQS•UNˆX\šÈ\ÈØ[™Y]BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆY[]K˜Ø[™Y]JXÚ\Ú[Û‹™[TØÛÜ™KXÚ\Ú[Û‹œ\ÙKXÚ\Ú[Û‹œÙ]\]X[]JBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQ‘PÖPÓNˆĞS‘QUH
İ˜]YŞHÙ[™\˜]Y•VHÚYÛ˜[
BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜YSY™XŞXÛK˜Ø[™Y]JˆY[]K›Z[ˆXÚ\Ú[Û‹™[TØÛÜ™KˆXÚ\Ú[Û‹œ\ÙKˆXÚ\Ú[Û‹œÙ]\]X[]Bˆ
BˆËÈØ[İ[]H›ÜÜÙYÚ^™Hš\œİˆ˜[›ÜÜÙYÚ^™HH^Xİ]Ü‹˜Ø[İ[]P^TÚ^™JˆÈHËˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆ]X[]HHXÚ\Ú[Û‹™š[˜[]X[]Kˆ
BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQHQS•UNˆX\šÈ\È›ÜÜÙYˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆY[]Kœ›ÜÜÙY

BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQ‘PÖPÓNˆ“ÔÔÑQ8¡¤ˆ‘È]˜[X][Û‚ˆËÈ“ÕNˆ™XÛÜ™›ÜÜØ[

H[İ™YQ•Tˆ‘È]˜[X][ÛˆÈ™]™[ˆËÈ‘ÉÜÈØ[”›ÜÜÙJ
HÚXÚÈœ›ÛH›ØÚÚ[™ÈHØ[YH›ÜÜØ[ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜YSY™XŞXÛKœ›ÜÜÙY
Y[]K›Z[
BˆˆËÈÙ]˜Y[™È[ÙHYÈ›Üˆ‘È[ÙK\ÜXÚYšXÈ™\ÚÛÂˆËÈKKH8 %\‹]ÚÙ[ˆ˜Y[™Ó[ÙHÒS”Èİ™\ˆHÛØ˜[]]Ë[[ÙHÛÂˆËÈY[YHİX‹\\Ù\È
ÒUÓÒSˆÈPS’TSUQÈSÓÓ”ÒÕÊˆÈÕSÂˆËÈT”UU‘JH™XXÚ‘ÈÚ]Z\ˆ›Ü\ˆ][\Y\œËˆ™]š[İ\ÛBˆËÈ‘ÈÛ›H]™\ˆØ]ÈHÛØ˜[›İ[ÙH[™™[˜XÚÈÈQUS›Ü‚ˆËÈ]™\HY[YH˜YKİ\š[™È[Ş[X›ÛXÈÚ[›™[ÈÙˆ[™H]K‚ˆ˜[˜Y[™Ó[ÙUYÈHHÂˆ˜[ÚÙ[“[ÙHHËœÜÚ][Û‹˜Y[™Ó[ÙBˆ[ÙTÜXÚYšXÑØ]\Ë™œ›ÛU˜Y[™Ó[ÙJÚÙ[“[ÙJBˆÎˆ[ÙPÛÛ™Ë›[ÙOË›]È[ÙTÜXÚYšXÑØ]\Ë™œ›ÛP›İ[ÙJ]
HBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ[ˆBˆˆËÈKKŒLÍÌˆ8 %‘È‘KQUS“ÕH
ØİÜˆÜXÈÌKÈÍˆÚ[‘×ÑS“ÕUÑVÔÒSÓŠK‚ˆËÈ›ÛİØ]\ÙHÙˆ‘ËÚ[ZÙOLLKˆ
ŒËŒš\ÈH[˜\šX[
NˆHĞSQBˆËÈØ]Ú\İYZ[™K\[œÈH[š[˜[XÚ\Ú[Û‘Ø]K™]˜[X]J
HÛ‚ˆËÈU‘T–HXÚÈ]™K\›ÜÜÙ\Ë]™[ˆİYÚ
JHŒÈ8 %›İ‘È8 %\ÈBˆËÈ^Xİ][Ûˆ]]Üš]H\™H
‘È\ÈÛÛ\\š\ÛÛ‹[ÙÙÚ[™ÈÛ›HÚ[‚ˆËÈŒĞÛÛ›ÛÑ^Xİ][ÛŠK[™
ŠHH[œ]È˜\™[H[İ™HXÚË]Ë]XÚË‚ˆËÈÙHØXÚHH\İ‘È™\™Xİ\ˆZ[›ÜˆHÚÜÚ[™İÈ[™™]\ÙH]ˆËÈ[œİXYÙˆ™XÛÛ\][™ËS“TÔÈH›ÜÜÙY[HØÛÜ™H[İ™YˆËÈX]\šX[H
MHÊH8 %H™X[ÚYÛ˜[Ú[™ÙH\Ù\™\ÈHœ™\Ú™\™Xİ‚ˆËÈ•VKXØ\X›H™\™XİÈ\™H‘U‘TˆØXÚY
ÙH[Ø^\ÈØ[H]™H™KY]˜[Û‚ˆËÈ[ˆ^Xİ]X›HØ[™Y]JKˆ\ÈÙ\È“ÕÛÜÙ[ˆ[HØ]NˆHØXÚYˆËÈ™\™Xİ\ÈHĞSQH™\™XİHØ]H\İ›ÙXÙYÛ›H™]\ÙYœšYY›BˆËÈÈ]›ÚY™Y[™[ÛÛ\]Kˆ›İYÚ]\ÜÚ]]™KØİš[™H[HÌË‚ˆ˜[™ÔØÛÜ™S›İÎˆ[HHÈXÚ\Ú[Û‹™[TØÛÜ™KÒ[

HHØ]Ú
Îˆ›İØX›JHÈBˆ˜[™ĞØ[™Y]U™\œÚ[ÛLÈH[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹˜Ø[™Y]U™\œÚ[Û‘›ÜŠY[]K›Z[
Bˆ˜[™Ñ]šY[˜ÙU™\œÚ[ÛLÈH\İÙŠˆËœØY™]KY\‹›˜[YKˆËœØY™]KœYØÚXÚÔØÛÜ™KˆËœØY™]Kš\™›ØÚÔ™X\ÛÛœËœÛÜY

Kš›Ú[•Ôİš[™Ê‹ŠKˆYˆ
Ë›\İ\]ZY]U\ÙˆŒ
H“TURQˆ[ÙH““×ÓTH‹ˆXÚ\Ú[Û‹™š[˜[ÚYÛ˜[ˆXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛ‹ˆ
Kš›Ú[•Ôİš[™ÊŸŠBˆ˜[ØXÚY™ÈH™Ô™Q]˜[›İK™Ù]
ˆY[]K›Z[™ĞØ[™Y]U™\œÚ[ÛLËŞXÛTš[X\S[™Kˆ™Ñ]šY[˜ÙU™\œÚ[ÛLË™ÔØÛÜ™S›İËˆ
Bˆ˜[™ÕØ\ĞØXÚYHØXÚY™ÈOH[ˆ˜[™ÑXÚ\Ú[ÛˆHYˆ
ØXÚY™ÈOH[
HÂˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ‘‘×ĞĞPÒQÔ‘UTÑH‹›Z[IÚY[]K›Z[ZÙJL
_H™]\ÙY™\™XİØ[IØØXÚY™Ë˜Ø[‘^Xİ]J
_HØÛÜ™OI™ÔØÛÜ™S›İÈŠHHØ]Ú
Îˆ›İØX›JHßBˆØXÚY™ÂˆH[ÙHÂˆ˜[œ™\ÚHš[˜[XÚ\Ú[Û‘Ø]K™]˜[X]JˆÈHËˆØ[™Y]HHXÚ\Ú[Û‹ˆÛÛ™šYÈHÙ™Ëˆ›ÜÜÙYÚ^™TÛÛH›ÜÜÙYÚ^™Kˆœ˜Z[ˆH^Xİ]Ü‹˜œ˜Z[‹ˆ˜Y[™Ó[ÙUYÈH˜Y[™Ó[ÙUYËˆÜXÚX[\İ[™HHŞXÛTš[X\S[™Kˆ
BˆËÈÛ™H[[]]X›H‘È™\İ[\ˆØ[™Y]KÙ]šY[˜ÙH™\œÚ[Û‹ˆ•VBˆËÈXÚ\Ú[ÛœÈ\™HÙX[YÛÎÈİÛœİ™X[HZ[İ™\œÚ[ÛˆÛZ[\È™]™[ˆËÈHÙXÛÛ™^Xİ][ÛˆÚ[H]šY[˜ÙHÚ[™Ù\È\İ\ÈÙ^K‚ˆ™Ô™Q]˜[›İKœ]
ˆY[]K›Z[™ĞØ[™Y]U™\œÚ[ÛLËŞXÛTš[X\S[™Kˆ™Ñ]šY[˜ÙU™\œÚ[ÛLË™ÔØÛÜ™S›İËœ™\Úˆ
Bˆœ™\ÚˆBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'éëQSQWÔÔS‘H‘È	ÚY[]KœŞ[X›ÛHØ[IÙ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
_H]X[IÙ™ÑXÚ\Ú[Û‹œ]X[]_HÛÛ™IÙ™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKÒ[

_HÚ^™OIÙ™ÑXÚ\Ú[Û‹œÚ^™TÛÛ™›]

_H™X\ÛÛIÙ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ››Û™HŸHŠBˆËÈKŒŒM8 %^Xİ]X›HÚÙ[“X\]šY[˜ÙHX]\šX[^™\ÈHØ[›ÛšXØ[ˆËÈX\šÈ[[YYX][NÈ›ÈØØ[›™\ˆ™\^HÜˆ[œ™[]Y›İšY\ˆØZ]‚ˆ˜[ÚÙ[“X\ŒMHHÈÚÙ[“X\]]Üš]K™[œİ\™Q\ØÛİ™\UÚÙ[“X\
ËËœÛİ\˜ÙJHHØ]Ú
Îˆ›İØX›JHÈËÚÙ[“X\Bˆ˜[X\šÔ™Yœ™\ÚŒMHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K]Ø[›ÛšXØ[šXÙSX\šÔ™YÚ\İMLŒ‹œ™Yœ™\Úœ›ÛQ^Xİ]X›UÚÙ[“X\ŒM
ˆZ[HY[]K›Z[ˆZ\“Ü”ÛÛHÚÙ[“X\ŒMœÛÛY™\ÜËšY›[šÈÈÚÙ[“X\ŒMœZ\Y™\ÜËšY›[šÈÈË›\İšXÙTÛÛY‹šY›[šÈÈËœZ\Y™\ÜÈHHKˆ][İSZ[HÚÙ[“X\ŒMœ][İSZ[šY›[šÈÈ•TÑˆKˆÛİ\˜ÙHHË›\İšXÙTÛİ\˜ÙKšY›[šÈÈÚÙ[“X\ŒMœÛİ\˜ÙTØØ[›™\‹šY›[šÈÈËœÛİ\˜ÙHHKˆšXÙU\ÙHÚÙ[“X\ŒMœšXÙU\ÙÎˆË›\İšXÙKˆ\]ZY]U\ÙHÚÙ[“X\ŒM›\]ZY]U\ÙÎˆË›\İ\]ZY]U\Ùˆ›İ]Tİ]\ÈHÚÙ[“X\ŒMœ›İ]Tİ]\Ëˆ
BˆHØ]Ú
Îˆ›İØX›JHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K]Ø[›ÛšXØ[šXÙSX\šÔ™YÚ\İMLŒ‹”›Û[İ[Û”™\İ[ŒLÊ[•ÒÑS—ÓPTÓPT’×Ô‘Q”‘TÒÑVÑTSÓˆ‹Y[]HHY[]K›Z[
HBˆYˆ
X\šÔ™Yœ™\ÚŒMœ›Û[İY
HÂˆHÈÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™K“PT’×Ô‘PQH‹‰ÚY[]K›Z[N‰Ó[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹˜Ø[™Y]U™\œÚ[Û‘›ÜŠY[]K›Z[
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆH[ÙHHÂˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÑVPÕUP“WÓPT’×Ô‘Q”‘TÒÔ‘R‘PÕQÍŒM	ÛX\šÔ™Yœ™\ÚŒMœ™X\ÛÛŸHŠBˆHØ]Ú
Îˆ›İØX›JHßBˆËÈKKH8 %Ü\˜]Üˆ\[[™KZX[š\ÚXš[]Kˆ‘È™]š[İ\ÛBˆËÈØ\Û‰İÚ\™Y[ÈH[‹X\[›™[Ûİ[\ˆ
Ûİ[\ˆİXÚÈ]ˆËÈ]™[ˆÚ[ˆ‘ÈØ\Èš\š[™ÊKˆÛİ[Û›Hœ™\Ú‘È]˜[X][ÛœÈ\™K‚ˆËÈKŒŒÍÌˆØXÚY™]\ÙH\È“ÕH™]È‘È]˜[ÈÛİ[[™È]\È‘ÂˆËÈXYH‘ËÚ[ZÙH™\ÜH˜[ÙH˜[›İ]^ÜÚ[Ûˆ[™Y™X[ÛÛ\]K‚ˆHÂˆYˆ
Y™ÕØ\ĞØXÚY
HÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËY[]KœŞ[X›Ûˆ˜Ø[IÙ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
_H]X[IÙ™ÑXÚ\Ú[Û‹œ]X[]_HÛÛ™IÙ™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKÒ[

_HÚ^™OIÙ™ÑXÚ\Ú[Û‹œÚ^™TÛÛ™›]

_H™X\ÛÛIÙ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ››Û™HŸH‚ˆ
Bˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]Jˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËY[]KœŞ[X›Ûˆ[İÈH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
Kˆ™X\ÛÛˆH™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ›ÚÈ‚ˆ
BˆBˆHØ]Ú
Îˆ›İØX›JHßBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒÈS‘ÒS‘Nˆ’SPT–HPÒTÒSÓˆUUÔ’UBˆËÈˆËÈŒÈRQÔUSÓˆŒÈ\È›İÈHÓ“HXÚ\Ú[ÛˆXZÙ\ˆÚ[ˆ[˜X›Y‚ˆËÈ‘È\ÈÙ\›ÜˆÛÛ\\š\ÛÛˆÙÙÚ[™ÈÛ›K‚ˆËÈˆËÈXÚ\Ú[Ûˆ›İÎ‚ˆËÈKˆŒÈØÛÜ™\ÈHØ[™Y]H
[˜ÛY\È[[˜[Y\ÊBˆËÈ‹ˆŒÈİ]]ÎˆVPÕUWĞQÑÔ‘TÔÒU‘KVPÕUWÔÕS‘T‘VPÕUWÔÓPSĞUÒ‘R‘PÕ“ĞÒÂˆËÈËˆÛ›HŒÈXÚ\Ú[ÛˆX]\œÈ›Üˆ^Xİ][Û‚ˆËÈˆ‘È™\İ[\ÈÙÙÙY›ÜˆÛÛ\\š\ÛÛˆ˜XÚÚ[™ÈÛ›BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜\ˆ\ÙUŒÑXÚ\Ú[ÛˆH˜[ÙBˆ˜\ˆŒÔÚ^™TÛÛHŒˆ˜\ˆŒÕ\Ú\ÈHˆ‚ˆ˜\ˆŒĞÛÛ›ÛÑ^Xİ][ÛˆH˜[ÙHËÈŒÈ\ÈH›ÜÜÈÚ[ˆ[˜X›YˆˆYˆ
Ù™ËŒÑ[™Ú[™Q[˜X›Y	‰ˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹š\Ô™XYJ
JHÂˆŒĞÛÛ›ÛÑ^Xİ][ÛˆHXÙ™ËŒÔÚYİÓ[ÙHËÈŒÈÛÛ›ÛÈ^Xİ][Ûˆ[›\ÜÈÚYİÈ[ÙBˆˆHÂˆËÈÙÈYØXŞHXÚ\Ú[Ûˆ›ÜˆÛÛ\\š\ÛÛ‚ˆ˜[YØXŞTÚİ[˜YHHXÚ\Ú[Û‹œÚİ[˜YBˆ˜[YØXŞT[˜[HHİ\™\ÜÚ[Û”[˜[Bˆˆ˜[ŒÑXÚ\Ú[ÛˆHÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹œ›ØÙ\ÜÕÚÙ[ŠˆÈHËˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆÜ[”ÜÚ][ÛœÈHİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆ™XÙ[Ú[”˜]HH›İœ˜Z[Ë™Ù]™XÙ[Ú[”˜]J
HÎˆLŒˆ™XÙ[˜YPÛİ[H›İœ˜Z[Ë™Ù]˜YPÛİ[

HÎˆˆX\šÙ]™YÚ[YHH[ÙPÛÛ™Ë›[ÙOË›˜[YHÎˆ“‘UUS‚ˆ
BˆˆÚ[ˆ
˜[™\İ[HŒÑXÚ\Ú[ÛŠHÂˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹‘^Xİ]HOˆÂˆ˜[™ÕYÈHYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH‘‘Î¸§$Èˆ[ÙH‘‘Î¸§%È‚ˆ˜[YØXŞUYÈHYˆ
YØXŞTÚİ[˜YJH›YØXŞN¸§$Èˆ[ÙH›YØXŞN¸§%È‚ˆˆËÈŒÈS’Q’QQÑÎˆÚİÜÈØÛÜ™KÛÛ™šY[˜ÙK˜[™Ú^™Bˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨HŒÈVPÕUNˆ	ÚY[]KœŞ[X›ÛHˆ
Âˆ˜˜[™IÜ™\İ[˜˜[™HØÛÜ™OIÜ™\İ[œØÛÜ™_Hˆ
Âˆ˜ÛÛ™IÜ™\İ[˜ÛÛ™šY[˜ÙKÒ[

_IHÚ^™OIÜ™\İ[œÚ^™TÛÛ™›]

_HÓÓˆ
Âˆ‰YØXŞUYÈ	™ÕYÈŠBˆˆËÈ˜XÚÈŒÈœÈYØXŞHÛÛ\\š\ÛÛ‚ˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹œ™XÛÜ™XÚ\Ú[ÛÛÛ\\š\ÛÛŠˆŒÑXÚ\Ú[ÛˆH‘VPÕUH‹ˆ™ÕÛİ[^Xİ]HH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
Bˆ
BˆˆËÈKKÈ8 %‘È\ÈHT‘‘UÈÛˆŒÈVPÕUK‚ˆËÈ™]š[İ\ÛHŒÈVPÕUHØ\È[˜ÛÛ™][Û˜[8 %‘È™\İ[ˆËÈØ\ÈÙÙÙY\ÈHYÈ]™]™\ˆ›ØÚÙYH˜YK‚ˆËÈ[ˆÙˆ[Ú[›™\œÈÛˆ[ÛÛœÚİ
ÈÚ]ÛÚ[ˆÙ\™HÚ[YˆËÈHŒÈĞUÒÔ‘R‘PÕİ™\œšY\ÈÚ[H‘ÈØ\ÈÜ™Y[‹Ü‚ˆËÈ[\™YÚ[ˆ‘ÈØ\È™Y[™[[YYX][H]İÜÜÜË‚ˆËÈ›İÎˆŒÈVPÕUHÛ›H›ØÙYYÈÚ[ˆ‘È[ÛÈ\›İ™\Ë‚ˆËÈŒÈĞUÒÈ‘R‘PÕİ[›ØÚÈ™YØ\™\ÜÈ
ŒÈİÛœÈİÛœÚYJK‚ˆYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠHÂˆYˆ
Y™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JHÂˆËÈ‘È™]È8 %ÙÈÛX\›HÛÈÜ\˜]ÜˆØ[ˆÙYH]ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘È‘UÈÛˆŒËQVPÕUNˆ	ÚY[]KœŞ[X›ÛH	Ù™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ››È™X\ÛÛˆŸHÛÛ™IÙ™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKÒ[

_IHŠBˆYÙÊ¼'æªÈ‘È‘UÎˆ	ÚY[]KœŞ[X›ÛH	Ù™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŸH‹Z[
BˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]Jˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘‘ËY[]KœŞ[X›Ûˆ[İÈH˜[ÙKˆ™X\ÛÛˆH‘‘×Õ‘U×ÕŒ×ÑVPÕUNˆ	Ù™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ››È™X\ÛÛˆŸH‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆËÈ\ÙUŒÑXÚ\Ú[Ûˆİ^\È˜[ÙH8 %›È˜YBˆH[ÙHÂˆ\ÙUŒÑXÚ\Ú[ÛˆHYBˆŒÔÚ^™TÛÛH™\İ[œÚ^™TÛÛˆŒÕ\Ú\ÈH•ŒÈØÛÜ™OIÜ™\İ[œØÛÜ™_H˜[™IÜ™\İ[˜˜[™H‚ˆYÙÊ¸¦¨HŒÊÑ‘Îˆ	ÚY[]KœŞ[X›ÛH	Ü™\İ[˜˜[™Hˆ
Âˆ‰İŒÔÚ^™TÛÛ™›]

_HÓÓÛÛ™IÜ™\İ[˜ÛÛ™šY[˜ÙKÒ[

_IH‹Z[
BˆBˆH[ÙHÂˆËÈÚYİÈ[ÙHHÙÈÛ›BˆYÙÊ¼'å+ŒÈÒQÕÎˆ	ÚY[]KœŞ[X›ÛH	Ü™\İ[˜˜[™Hˆ
Âˆ‰Ü™\İ[œÚ^™TÛÛ™›]

_HÓÓ
	™ÕYÊH‹Z[
BˆBˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹•Ø]ÚOˆÂˆ˜[™ÕYÈHYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH‘‘Î¸§$Èˆ[ÙH‘‘Î¸§%È‚ˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨HŒÈĞUÒˆ	ÚY[]KœŞ[X›ÛHˆ
ÂˆœØÛÜ™OIÜ™\İ[œØÛÜ™_HÛÛ™IÜ™\İ[˜ÛÛ™šY[˜Ù_H	™ÕYÈŠBˆˆËÈ˜XÚÈÛÛ\\š\ÛÛ‚ˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹œ™XÛÜ™XÚ\Ú[ÛÛÛ\\š\ÛÛŠˆŒÑXÚ\Ú[ÛˆH•ĞUÒ‹ˆ™ÕÛİ[^Xİ]HH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
Bˆ
BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKLˆ8 %ÔTUÔˆĞÕ’S‘Hš[Û‰İ[™\ˆ‚ˆËÈ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆËÈŒÈĞUÒ\È“ÕHİ\YYXÚ\Ú[Ûˆ™]È8 %]	ÜÈŒÂˆËÈØ^Z[™ÈœØÛÜ™H\È™[İÈ^HVPÕUH›ÛÜˆ]X›İ™BˆËÈØ]ÚØÛÜ™SZ[ˆ‹ˆ™]š[İ\ÛH\È\™X›ØÚÙYBˆËÈ˜YH]™[ˆÚ[ˆ‘È\›İ™Y‚ˆËÂˆËÈKKÈÛÛ[Y[Ø\È^XÚ]ˆ’[ˆÙˆ[Ú[›™\œÂˆËÈÛˆ[ÛÛœÚİ
ÈÚ]ÛÚ[ˆÙ\™HÚ[YHŒÈĞUÒÂˆËÈ‘R‘PÕİ™\œšY\ÈÚ[H‘ÈØ\ÈÜ™Y[‹ˆ‚ˆËÂˆËÈ‘UÈ‘RU’SÔˆYˆ‘È\›İ™\ÈS‘ŒÈY‰İ]BˆËÈİ\YYXÚ\Ú[ÛˆØ]H
›ØÚÑ˜][
K]‘ÈXÚYBˆËÈÚ^š[™ÈÚ]H›İ[™YÚš[šÈ][\Y\‹ˆŒÉÜÂˆËÈÛÛ˜Ù\›ˆ™YXÙ\ÈÚ^™HÈH›Ø™H8 %]Ù\Û‰İˆËÈ™]ËˆHİ\YYXÚ\Ú[ÛˆØ]\È
‘ÈT‘›ØÚÜËˆËÈŒÈ›ØÚÑ˜][
Hİ[Ú[˜Y\Ë‚ˆËÂˆËÈÚš[šÎˆpåÈ‘Ë\İYÙÙ\İYÚ^™H
›Ø™HY\ŠK‚ˆËÈœšYÙH˜[˜XÚÈİ[[œÈ[ˆ\\ˆ\È™Y›Ü™K‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠHÂˆYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
H	‰ˆ™ÑXÚ\Ú[Û‹œÚ^™TÛÛˆŒ
HÂˆ˜[˜]Ô›Ø™TÚ^™HH
™ÑXÚ\Ú[Û‹œÚ^™TÛÛ
ˆJK˜ÛÙ\˜ÙP]X\İ
ŒÊBˆ˜[›Ø™TÚ^™HHYˆ
XÙ™Ëœ\\“[ÙJHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K“]™TÚ^š[™Ô›Ùš[K›\İZ[Q[Q›ÛÜŠˆ˜]Ô›Ø™TÚ^™KˆY™™Xİ]™P˜[[˜ÙKˆ\Ô\\“[ÙHH˜[ÙKˆ
BˆH[ÙH˜]Ô›Ø™TÚ^™Bˆ\ÙUŒÑXÚ\Ú[ÛˆHYBˆŒÔÚ^™TÛÛH›Ø™TÚ^™BˆŒÕ\Ú\ÈH•ŒËUĞUÒPÓÓTÕS‘Q“ÓÔˆØÛÜ™OIÜ™\İ[œØÛÜ™_HÛÛ™IÜ™\İ[˜ÛÛ™šY[˜Ù_H
‘ÏYÜ™Y[‹ŒÈÚ[šÈ[ˆ›ÛÜ‹X]Ø\™JH‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨HŒÈĞUÒ8¡¤ÓÓTÕS‘ˆ	ÚY[]KœŞ[X›ÛHÚ^™OIÜ›Ø™TÚ^™K™›]

_HÓÓ˜]ÏIÜ˜]Ô›Ø™TÚ^™K™›]

_H
‘È\›İ™Y
HŠBˆYÙÊ¸¦¨HŒÈĞUÒ8¡¤ÓÓTÕS‘ˆ	ÚY[]KœŞ[X›ÛHØÛÜ™OIÜ™\İ[œØÛÜ™_H	Ü›Ø™TÚ^™K™›]

_HÓÓ‹Z[
BˆH[ÙHÂˆYÙÊ¸¦¨HŒÈĞUÒˆ	ÚY[]KœŞ[X›ÛHØÛÜ™OIÜ™\İ[œØÛÜ™_H‘È[ÛÈXÛ[™Y
›È˜YJH‹Z[
Bˆ\ÙUŒÑXÚ\Ú[ÛˆH˜[ÙBˆBˆBˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹”™Z™XİYOˆÂˆ˜[™ÕYÈHYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH‘‘Î¸§$Èˆ[ÙH‘‘Î¸§%È‚‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨HŒÈ‘R‘PÕˆ	ÚY[]KœŞ[X›ÛHˆ
Âˆ‰Ü™\İ[œ™X\ÛÛŸH	™ÕYÈŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
•ŒÈ‹™\İ[œ™X\ÛÛŠB‚ˆËÈ˜XÚÈÛÛ\\š\ÛÛ‚ˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹œ™XÛÜ™XÚ\Ú[ÛÛÛ\\š\ÛÛŠˆŒÑXÚ\Ú[ÛˆH”‘R‘PÕ‹ˆ™ÕÛİ[^Xİ]HH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
Bˆ
B‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKŒŒÍÌÈ8 %\›Z[˜[ŒÈ™Z™XİØ[››İ™XÛÛYH[ˆ‘È›Ø™K‚ˆËÈH™]š[İ\ÈĞÓÔ‘WÕÓ×ÓÕÈ›Ø™H]\™XİH›ÙXÙYˆËÈHÜ\˜]Üˆ[\ÛÛ˜YXİ[Ûˆ‘R‘PÕQÑUSÕŒËÔĞÓÔ‘WÕÓ×ÓÕÂˆËÈ›ÛİÙYH‘×ĞSÕËÑVP×ÑĞUWĞSÕËˆYˆŒÈÛÛ›ÛÂˆËÈ^Xİ][Û‹H™Z™Xİ\ÈH™Z™Xİˆ\\ˆØ[ˆİ[X\›ˆœ›ÛBˆËÈXØÙ\Y›Ø™\ÎÈ]]\İ›İ^Xİ]H›İÜÈHØ]HX™[YˆËÈ\›Z[˜[‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[\Õ\›Z[˜[ŒÔ™Z™XİH™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ”ĞÓÔ‘WÕÓ×ÓÕÈ‹YÛ›Ü™PØ\ÙHHYJBˆ™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ••QWÖ‘T“×ÓTURQUH‹YÛ›Ü™PØ\ÙHHYJBˆ™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ“Õ×ÓTURQUH‹YÛ›Ü™PØ\ÙHHYJBˆ™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ’S‘SQÒP“H‹YÛ›Ü™PØ\ÙHHYJBˆ™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ•Ó×ÓÓ‹YÛ›Ü™PØ\ÙHHYJBˆ™\İ[œ™X\ÛÛ‹˜ÛÛZ[œÊ““×ÔRTˆ‹YÛ›Ü™PØ\ÙHHYJBˆYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠHÂˆYÙÊ¸¦¨HŒÈ‘R‘PÕˆ	ÚY[]KœŞ[X›ÛH	Ü™\İ[œ™X\ÛÛŸH‹Z[
Bˆ\ÙUŒÑXÚ\Ú[ÛˆH˜[ÙBˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ•Œ×Ô‘R‘PÕÑVP×ÔÕT‘TÔÑQ‹›Z[IÚY[]K›Z[ZÙJL
_HŞ[X›ÛIÚY[]KœŞ[X›ÛH™X\ÛÛIÜ™\İ[œ™X\ÛÛŸH™ĞØ[IÙ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKŒÍˆ8 %QSQHS’Q’QQĞÓÔ‘Tˆ”’QÑH
\\‹[Û›H˜[˜XÚÊBˆËÈH[È˜Y\‰ÜÈÎIHÔˆ\˜Ú]Xİ\™NˆH™KYš[\‚ˆËÈ
ÈŞ[]XÈ›ÛÜœÈ
ÈŒÍ›[™\\ÜÚ[™È‘ËˆÚ[‚ˆËÈŒÈ™Z™XİÈ[ˆ\\ˆ[ÙKHœšYÙHÙ]ÈHÙXÛÛ™ˆËÈÛÚËˆYˆ]Ø^\ÈÚİ[[\ˆÙHİ™\œšYHŒÈÚ]ˆËÈHÛX[ÜÚ][Û‹ˆ]™H[ÙHİ[Y™\œÈÈŒË‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[œšYÙP[İÙYH]\ÙUŒÑXÚ\Ú[Ûˆ	‰ˆZ\Õ\›Z[˜[ŒÔ™Z™Xİ	‰ˆÙ™Ëœ\\“[ÙBˆYˆ
œšYÙP[İÙY
HÂˆHÂˆ˜[™\™XİHÛÛK›Y™XŞXÛX›İŒË“Y[YU[šYšYYØÛÜ™\œšYÙKœØÛÜ™Q›Ü‘[JÊBˆYˆ
™\™XİœÚİ[[\ŠHÂˆËÈKKÈ8 %œšYÙH[ÛÈ™\]Z\™\È‘È\›İ˜[‚ˆËÈœšYÙHØ\Èİ™\œšY[™È›İŒÈS‘‘ËˆËÈ[\š[™ÈÛˆYÙÙYÈYÙK]™]ÙYÚÙ[œË‚ˆYˆ
Y™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã"H”’QÑH‘È‘UÎˆ	ÚY[]KœŞ[X›ÛH	Ù™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ™™×Ø›ØÚÈŸHŠBˆH[ÙHÂˆËÈ[HœšYÙHÚ^™H8 %œšYÙH[šY\È\™BˆËÈ›Ûİİ˜\X\›š[™È˜Y\ÎÈHY[YBˆËÈ˜Y\‰ÜÈY\]™HÚ^š[™ÈÚXÚÜÈ[ˆÛ˜ÙBˆËÈH^Y\‹XXØİ\˜XŞH]HXØİ[][]\Ë‚ˆ˜[œšYÙTÚ^™HHYˆ
Ù™Ëœ\\“[ÙJHŒH[ÙHŒBˆ\ÙUŒÑXÚ\Ú[ÛˆHYBˆŒÔÚ^™TÛÛHœšYÙTÚ^™BˆŒÕ\Ú\ÈH“Y[YPœšYÙHXÚIİ™\™XİXÚØÛÜ™_HŒÏIİ™\™XİŒÔØÛÜ™_H›[™Iİ™\™Xİ˜›[™YØÛÜ™_H][IÈ‰KŒ™ˆ‹™›Ü›X]
™\™Xİ\İ][\Y\Š_H[ÙOIÚYˆ
Ù™Ëœ\\“[ÙJHœ\\ˆˆ[ÙH›]™K[X\›š[™ÈŸH‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã"H”’QÑHÕ‘T”’QHÛˆŒËT‘R‘PÕˆ	ÚY[]KœŞ[X›ÛH	ŒÕ\Ú\ÈŠBˆYÙÊ¼'ã"HœšYÙH•VNˆ	ÚY[]KœŞ[X›ÛHXÚIİ™\™XİXÚØÛÜ™_H›[™Iİ™\™Xİ˜›[™YØÛÜ™_H	ØœšYÙTÚ^™_HÓÓ‹Z[
BˆBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙHXÛ[™Y	ÚY[]KœŞ[X›ÛNˆ	İ™\™Xİœ™Z™Xİ™X\ÛÛŸHŠBˆBˆHØ]Ú
™Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ã"HœšYÙH\œ›ÜˆÛˆ	ÚY[]KœŞ[X›ÛNˆ	Ø™K›Y\ÜØYÙ_HŠBˆBˆBˆBˆˆ\ÈÛÛK›Y™XŞXÛX›İŒË•ŒÑXÚ\Ú[Û‹›ØÚÙYOˆÂˆ˜[™ÕYÈHYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH‘‘Î¸§$Èˆ[ÙH‘‘Î¸§%È‚ˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨HŒÈ“ĞÒÈ
US
Nˆ	ÚY[]KœŞ[X›ÛHˆ
Âˆ‰Ü™\İ[œ™X\ÛÛŸH	™ÕYÈŠBˆˆËÈ˜XÚÈÛÛ\\š\ÛÛ‚ˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹œ™XÛÜ™XÚ\Ú[ÛÛÛ\\š\ÛÛŠˆŒÑXÚ\Ú[ÛˆH“ĞÒÈ‹ˆ™ÕÛİ[^Xİ]HH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
Bˆ
BˆˆËÈŒÈ“ĞÒÈHUSÈ“ÕVPÕUBˆYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠHÂˆYÙÊ¸¦¨HŒÈ“ĞÒÑQˆ	ÚY[]KœŞ[X›ÛH	Ü™\İ[œ™X\ÛÛŸH‹Z[
Bˆ™]\›ˆËÈŒÈØ^\È“ĞÒÈH^]ˆBˆBˆˆ[ÙHOˆÂˆËÈ\œ›ÜˆÜˆ›İ™XYHH˜[˜XÚÈÈ‘ÈÛ›HYˆŒÈ\È›İÛÛ›Û[™Âˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¸¦¨HŒÈ[˜]˜Z[X›H›Üˆ	ÚY[]KœŞ[X›ÛHH	ÚYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠH”ÒÒTS‘Èˆ[ÙH\Ú[™È‘ÈŸHŠBˆYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠHÂˆËÈŒÈ\Èİ\ÜÙYÈÛÛ›Û]˜Z[YHÛ‰İ˜YHÛˆ[˜Ù\Z[Bˆ™]\›‚ˆBˆBˆBˆˆHØ]Ú
ŒÙNˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹•ŒÈ[™Ú[™H\œ›Üˆ›Üˆ	ÚY[]KœŞ[X›ÛNˆ	İŒÙK›Y\ÜØYÙ_HŠBˆYˆ
ŒĞÛÛ›ÛÑ^Xİ][ÛŠHÂˆËÈŒÈÛÛ›ÛÈ]\œ›Ü™YHÛ‰İ˜[˜XÚÈÈYØXŞBˆ™]\›‚ˆBˆBˆBˆˆËÈ™\ÛÛ™HH[[]]X›HÚ^™H™Y›Ü™H]]Üš^˜][Û‹ˆ›È]]Üš^˜][Û‚ˆËÈX^HİÛˆHÚÙ[ˆØÚÈœ›ÛHHÒV‘WÔS‘S‘ÈØY™]K[Û›H™\™Xİ‚ˆ˜\ˆš[˜[Ú^™Q›Ü]]HHYˆ
\ÙUŒÑXÚ\Ú[Ûˆ	‰ˆŒÔÚ^™TÛÛˆ
HÂˆŒÔÚ^™TÛÛˆH[ÙHÂˆ™ÑXÚ\Ú[Û‹œÚ^™TÛÛˆBˆYˆ
]\ÙUŒÑXÚ\Ú[ÛŠHÂˆ[ÙPÛÛ™Ë›]Èš[˜[Ú^™Q›Ü]]H
H]œÜÚ][Û”Ú^™S][\Y\ˆBˆBˆ˜[\ÑÜ˜YX]Y›Ü]]HH]\ÙUŒÑXÚ\Ú[Ûˆ	‰ˆXÚ\Ú[Û‹œÙ]\]X[]H[ˆ\İÙŠJÈ‹ˆŠBˆ˜[XİX[[š]X[Ú^™Q›Ü]]HHYˆ
\ÑÜ˜YX]Y›Ü]]JHÂˆ^Xİ]Ü‹™Ü˜YX]Y[š]X[Ú^™Jš[˜[Ú^™Q›Ü]]KXÚ\Ú[Û‹œÙ]\]X[]JBˆH[ÙHÂˆš[˜[Ú^™Q›Ü]]BˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKŒˆQHUUÔ’V‘TˆHÚXÚÈ‘Q“Ô‘H[H^Xİ][Û‚ˆËÈ\È\ÈH[šYšYYØ]H]™]™[ÈÜİY^Xİ][ÛˆØ][™ÈšYˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[]]™\İ[H˜YP]]Üš^™\‹˜]]Üš^™JˆZ[HZ[ˆŞ[X›ÛHY[]KœŞ[X›ÛˆØÛÜ™HHË›\İŒÔØÛÜ™HÎˆˆÛÛ™šY[˜ÙHH™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKˆ]X[]HH™ÑXÚ\Ú[Û‹œ]X[]Kˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆ™\]Y\İY›ÛÚÈH^Xİ][Û›ÛÚÑ›Ü“[™MM
ŞXÛTš[X\S[™JKˆYØÚXÚÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KZÙRYˆÈ]HHÎˆLˆ\]ZY]HHË›\İ\]ZY]U\Ùˆ\Ğ˜[›™YH˜[›™YÚÙ[œËš\Ğ˜[›™Y
Z[
Kˆ™T™\ÛÛ™YÚ^™TÛÛHXİX[[š]X[Ú^™Q›Ü]]Kˆ
Bˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'éëQSQWÔÔS‘HUU	ÚY[]KœŞ[X›ÛH™\™XİIØ]]™\İ[™\™XİH™X\ÛÛIØ]]™\İ[œ™X\ÛÛŸH\\IØÙ™Ëœ\\“[Ù_H\OIİË›\İ\]ZY]U\ÙÒ[

_HŠB‚ˆËÈKŒŒM8 %]™\HÛİ[YÜXÚX[\İ•VH[[™XÙZ]™\ÈÛ™BˆËÈØ[YKZY[]H‘È\›Z[˜[İ]ÛÛYH™Y›Ü™H[HÒQÕËÔ‘R‘PÕ™]\›‹‚ˆ˜[ÜXÚX[\İØ]\Ø[YŒMH]]™\İ[˜][\YšY›[šÈÂˆ‰Ğ›İ[[YPÛÛ›Û\‹˜İ\œ™[Ù[™\˜][ÛŠ
_N‰Ó[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹˜Ø[™Y]U™\œÚ[Û‘›ÜŠY[]K›Z[
_N‰ŞXÛTš[X\S[™H‚ˆBˆ˜[Ø[™Y]U™\œÚ[ÛŒMH]]™\İ[˜Ø[™Y]U™\œÚ[ÛMZÙRYˆÈ]ˆBˆÎˆ[™Q^Xİ][ÛÛÛÜ™[˜]Ü‹˜Ø[™Y]U™\œÚ[Û‘›ÜŠY[]K›Z[
Bˆ˜\ˆÜXÚX[\İ[[ŒMH^Xİ]X›SÜ[‘Ø]K˜Xİ]™Q^Xİ][Û’[[LNJˆYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘H‹Y[]K›Z[Ø[™Y]U™\œÚ[ÛŒMˆ
BˆYˆ
]]™\İ[š\Ñ^Xİ]X›J
H	‰ˆÜXÚX[\İ[[ŒMOH[
HÂˆÜXÚX[\İ[[ŒMH^Xİ]X›SÜ[‘Ø]Kœ™XÛÜ™™Ğ[™Ù][[LÌÊˆZ[HY[]K›Z[Ş[X›ÛHY[]KœŞ[X›Û[™HHŞXÛTš[X\S[™KˆØ[‘^Xİ]HH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
K™X\ÛÛˆH™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛ‹ˆÚYÛ˜[HYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH•VHˆ[ÙH““×Ğ•VH‹ˆYÔØÛÜ™HHËœØY™]KœYØÚXÚÔØÛÜ™KØY™]UY\ˆHËœØY™]KY\‹›˜[YKˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ù\™›Ô™X\ÛÛœÈHËœØY™]Kš\™›ØÚÔ™X\ÛÛœËˆ™Q™Õ™\™XİHYˆ
™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH•VHˆ[ÙH““×Ğ•VH‹ˆØ[™Y]U™\œÚ[ÛˆHØ[™Y]U™\œÚ[ÛŒM[TØÛÜ™HHË›\İŒÔØÛÜ™HÎˆË™[TØÛÜ™KÒ[

KˆÚÙ[“X\›İ]Tİ]\ÈHÚÙ[“X\ŒMœ›İ]Tİ]\ËˆÚÙ[“X\Y˜][ÛÛÛ\]HHÚÙ[“X\ŒMšY˜][ÛÛÛ\]KˆÚÙ[“X\^XİYİ]HÚÙ[“X\ŒM™^XİYİ][[İ[ˆÚÙ[“X\›İšY\][\ÈHÚÙ[“X\ŒMœ›İšY\][\Ëˆ™\]Z\™\ÔÛÛ[˜UÚÙ[“X\HYKˆ[İÕ[šÑ^Xİ][Û’[™Ù™LÌÈHYKˆ™\ÛÛ™YÚ^™TÛÛMNHXİX[[š]X[Ú^™Q›Ü]]Kˆ
BˆBˆËÈKŒN0©ÕPÒÑUÔÕSTÔ‘U’QUSÔT’UH8 %Ü\˜]Üˆ[\™XˆŒ‚ˆËÈ“QPÒTÔÒUÓÒSˆ^R[[™ËÚ^™KX\šÈ[›Û‹^™\›ËˆËÈXÚÙ]L
PÒÑUĞÒÒÑQ
Kˆ›ÛİØ]\ÙNˆPÒÑU\ÈÛ›Hİ[\YˆËÈ[œÚYH™XÛÜ™™Ğ[™Ù][[LÌØ
^Xİ]X›SÜ[‘Ø]KšİMJK‚ˆËÈÚ[ˆ[ˆ^Xİ][Û’[[Ø\È[™XYHX]\šX[\ÙY[ˆHš[Ü‚ˆËÈ\ÙH8 %Hš[X\H]X›\Ú\È]šXBˆËÈX›\Ú™Ò[[LNX[œÚYH™XÛÜ™™Ê
H]ˆËÈ^Xİ]X›SÜ[‘Ø]KšİŒLHÚ]İ]HPÒÑUİ[\8 %H™]šY]™BˆËÈ][™HX›İ™H™]\›œÈ›Û‹[[[™H™XÛÜ™™Ğ[™Ù][[LÌØˆËÈœ˜[˜Ú
ÚXÚÙ\ÈHPÒÑUİ[\
H™]™\ˆ[œËˆHÜXÚX[\İˆËÈØ]\Ø[™XÛÜ™\™Y›Ü™H™]™\ˆÙY\ÈHPÒÑUİYÙH]™[ˆİYÚBˆËÈ˜[YÙX[YXÚÙ]^\İÈ[™İÛœİ™X[HVPÈ\È™Y[ˆš\™Y‚ˆËÈØ[YK[[™Hİ[\Ú]H[[	ÜÈØ[›ÛšXØ[][\YÈBˆËÈ™XÛÜ™\ÚÔİYÙH
[™_İYÙ_]™[Y
HY\Hİ[[™›Ü˜Ù\ÈÛ™BˆËÈİ[\\ˆ[[ÛÈHİXœÙ\]Y[™]šY]™H\ÈH›Ë[Ü‚ˆ˜[XÚÙ]İ[\[[NHÜXÚX[\İ[[ŒMˆYˆ
XÚÙ]İ[\[[NOH[
HHÂˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™K”ÓÓ‹XÚÙ]İ[\[[N˜][\Y
BˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™K•VWÒS•S•‹XÚÙ]İ[\[[N˜][\Y
BˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™K“PT’×Ô‘PQH‹XÚÙ]İ[\[[N˜][\Y
BˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™K”ÒV‘QÑVPÕUP“H‹XÚÙ]İ[\[[N˜][\Y
BˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™K•PÒÑU‹XÚÙ]İ[\[[N˜][\Y
BˆHØ]Ú
Îˆ›İØX›JHßBˆ˜[ÜXÚX[\İ™Ğ[İÙYŒMHÜXÚX[\İ[[ŒMË™™Ğ[İÙYOHYH™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
BˆHÂˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™\ÚÔİYÙJŞXÛTš[X\S[™KYˆ
ÜXÚX[\İ™Ğ[İÙYŒM
H‘‘×ĞSÕÈˆ[ÙH‘‘×Ğ“ĞÒÈ‹ÜXÚX[\İØ]\Ø[YŒM
BˆHØ]Ú
Îˆ›İØX›JHßBˆYˆ
]]™\İ[š\Ñ^Xİ]X›J
H	‰ˆ
\ÙUŒÑXÚ\Ú[Ûˆ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
JH	‰ˆÜXÚX[\İ[[ŒMOH[
HÂˆHÂˆÛÛÚ]ÚYÛ˜[ÚY]œ™XÛÜ™Ø]\Ø[\ÜİYMŒ
”ÔPÒPSTÕÒS•S•ÕÒUÕUÑ‘×ÓÕUÓÓQH‹ŞXÛTš[X\S[™KšYIÜXÚX[\İØ]\Ø[YŒMZ[IÚY[]K›Z[ZÙJL
_HŠBˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ”ÔPÒPSTÕÒS•S•ÕÒUÕUÑ‘×ÓÕUÓÓQH‹›[™OIŞXÛTš[X\S[™HYIÜXÚX[\İØ]\Ø[YŒMZ[IÚY[]K›Z[ZÙJL
_HXİ[ÛY^XÚ]Ü™Z™XİÛ›×Ø\\ÜÈŠBˆHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚ˆËÈYˆ˜YP]]Üš^™\ˆØ^\ÈÒQÕ×ÓÓ“K˜XÚÈ]Û‰İ^Xİ]BˆYˆ
]]™\İ[š\ÔÚYİÓÛ›J
JHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹–ÕŒßQWĞUUH	ÚY[]KœŞ[X›ÛHÒQÕ×ÓÓ“H	Ø]]™\İ[œ™X\ÛÛŸHŠBˆËÈ˜XÚÈ\ÈÚYİÈ]›ÚY›ÜˆX\›š[™ÂˆÛÛK›Y™XŞXÛX›İŒË›X\›š[™Ë”ÚYİÓX\›š[™Ñ[™Ú[™Kœ™XÛÜ™ÚYİĞ]›ÚY
ˆZ[HZ[ˆŞ[X›ÛHY[]KœŞ[X›ÛˆšXÙHHËœ™Y‹ˆZPÛÛ™šY[˜ÙHH™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKÒ[

KˆÙ]\]X[]HH™ÑXÚ\Ú[Û‹œ]X[]Kˆ™YÚ[YHH›İœ˜Z[Ë˜İ\œ™[™YÚ[YHÎˆ•S’Ó“ÕÓˆ‹ˆ[ÙHHYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘H‹ˆ›ØÚÔ™X\ÛÛˆH•QWĞUUÉØ]]™\İ[œ™X\ÛÛŸH‚ˆ
Bˆ™]\›ˆËÈÚÚ\^Xİ][Ûˆ[\™[BˆBˆˆËÈYˆ˜YP]]Üš^™\ˆØ^\È‘R‘PÕÚÚ\[\™[BˆYˆ
X]]™\İ[š\Ñ^Xİ]X›J
JHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹–ÕŒßQWĞUUH	ÚY[]KœŞ[X›ÛH‘R‘PÕQ	Ø]]™\İ[œ™X\ÛÛŸHŠBˆ™]\›ˆËÈÚÚ\^Xİ][Ûˆ[\™[BˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈVPÕUSÓˆUˆ\ÙHŒÈXÚ\Ú[ÛˆYˆXİ]™Kİ\Ú\ÙH‘ÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[Úİ[^Xİ]HH\ÙUŒÑXÚ\Ú[Ûˆ™ÑXÚ\Ú[Û‹˜Ø[‘^Xİ]J
BˆˆYˆ
Úİ[^Xİ]JHÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ‘PÓÔ‘“ÔÔĞSˆ˜XÚÈ]ÙH›ÜÜÙY
›ÜˆY\JBˆËÈ[İ™Y\™Hœ›ÛH™Y›Ü™H‘ÈÈ™]™[Ù[‹X›ØÚÚ[™ÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜YSY™XŞXÛKœ™XÛÜ™›ÜÜØ[
Y[]K›Z[
BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈÓÓTUH’SSÒV‘Nˆ\ÙHŒÈÚ^™HYˆ]˜Z[X›Kİ\Ú\ÙH‘ÈÚ^™BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[š[˜[Ú^™HHš[˜[Ú^™Q›Ü]]Bˆ˜[\ÑÜ˜YX]YH\ÑÜ˜YX]Y›Ü]]Bˆ˜[XİX[[š]X[Ú^™HHXİX[[š]X[Ú^™Q›Ü]]BˆˆËÈ]\›Z[™H\›İ˜[Û\ÜÈ[™ÛÛ™šY[˜ÙBˆ˜[\›İ˜[Û\ÜÈHYˆ
\ÙUŒÑXÚ\Ú[ÛŠHÂˆš[˜[XÚ\Ú[Û‘Ø]K\›İ˜[Û\ÜË“U‘HËÈŒÈXÚ\Ú[ÛœÈ\™H[Ø^\È›]™H‚ˆH[ÙHÂˆ™ÑXÚ\Ú[Û‹˜\›İ˜[Û\ÜÂˆBˆˆ˜[]X[]HHYˆ
\ÙUŒÑXÚ\Ú[ÛŠH•ŒÈˆ[ÙH™ÑXÚ\Ú[Û‹œ]X[]Bˆ˜[ÛÛ™šY[˜ÙHHYˆ
\ÙUŒÑXÚ\Ú[ÛŠHKŒ[ÙH™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQHQS•UNˆX\šÈ\È\›İ™YÚ]PÕPS[š]X[Ú^™BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆY[]K˜\›İ™Y
XİX[[š]X[Ú^™K]X[]KÛÛ™šY[˜ÙJBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQ‘PÖPÓNˆT“Õ‘Q8¡¤ˆÒV‘QˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜YSY™XŞXÛK™™Ğ\›İ™Y
ˆY[]K›Z[ˆ]X[]KˆÛÛ™šY[˜ÙKˆ\›İ˜[Û\ÜË›˜[YBˆ
Bˆ˜YSY™XŞXÛKœ™XÛÜ™\›İ˜[
Y[]K›Z[
HËÈ˜XÚÈ›ÜˆY\Bˆ˜YSY™XŞXÛKœÚ^™Y
Y[]K›Z[XİX[[š]X[Ú^™K›YY][HŠBˆˆËÈÙÈ\›İ˜[
ŒÈÜˆ‘ÊBˆYˆ
\ÙUŒÑXÚ\Ú[ÛŠHÂˆYÙÊ¸¦¨HŒÈT“Õ‘Qˆ	ÚY[]KœŞ[X›ÛHÚ^™OIØXİX[[š]X[Ú^™K™›]

_HÓÓ\Ú\Îˆ	ŒÕ\Ú\È‹Z[
Bˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¦¨HŒÈT“Õ‘Qˆ	ÚY[]KœŞ[X›ÛHˆ
ÂˆœÚ^™OIØXİX[[š]X[Ú^™K™›]

_HÓÓŠBˆH[ÙHÂˆš[˜[XÚ\Ú[Û‘Ø]K›ÙĞ\›İ™Y˜YJ™ÑXÚ\Ú[ÛŠHÈYÙÊ]Z[
HBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹‰ÚYŠ™ÑXÚ\Ú[Û‹š\Ğ™[˜ÚX\šÔ]X[]J
JH¼'çèˆˆ[ÙH¼'çèHŸHˆ
Âˆ‘‘È	Ù™ÑXÚ\Ú[Û‹˜\›İ˜[Û\ÜßNˆ	ÚY[]KœŞ[X›ÛHˆ
Âˆœ]X[]OIÙ™ÑXÚ\Ú[Û‹œ]X[]_HÛÛ™IÙ™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKÒ[

_IHˆ
ÂˆœÚ^™OIØXİX[[š]X[Ú^™K™›]

_HÓÓˆ
ÂˆYˆ
\ÑÜ˜YX]Y
Hˆ
Ü˜Yˆ\™Ù]IÙš[˜[Ú^™K™›]

_JHˆ[ÙHˆŠBˆBˆˆËÈKKŒMÌÈ8 %\\ˆ[ÙH\\ÜÙ\ÈH]\ÙHİX\™ˆX\›š[™ÂˆËÈ]\İ™]™\ˆİÜ[ˆ\\‹ˆ]™Hİ^\ÈØ]Y›ÜˆØY™]K‚ˆ˜[]\ÙP›ØÚÜÈHXÙ™Ëœ\\“[ÙH	‰ˆØ”İ]Kš\Ô]\ÙYˆYˆ
XØ”İ]Kš\Ò[Y	‰ˆ\]\ÙP›ØÚÜÊHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'éëQSQWÔÔS‘HVPÕUÔ—Ô“ÕUH	ÚY[]KœŞ[X›ÛH\\IØÙ™Ëœ\\“[Ù_HŒÏI\ÙUŒÑXÚ\Ú[ÛˆÚ^™OIØXİX[[š]X[Ú^™K™›]

_HØ[]IÙY™™Xİ]™P˜[[˜ÙK™›]

_H]]ÏIØÙ™Ë˜]]Õ˜Y_HŠBˆËÈKKÈ8 %Ú\™HVPÈ›Ü™[œÚXÈÛİ[\ˆÛÈ\[[™RX[VPÈ[H\È›Û‹^™\›Ë‚ˆËÈØ\È[Ø^\È™XØ]\ÙH›Ü™[œÚXÓÙÙÙ\‹™^XÊ
H^\İY]Ø\È™]™\ˆØ[Y‚ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹™^XÊˆXİ[ÛˆHYˆ
Ù™Ëœ\\“[ÙJH”TT—Ğ•VHˆ[ÙH“U‘WĞ•VH‹ˆŞ[X›ÛHY[]KœŞ[X›ÛˆšY[ÈHœÚ^™OIØXİX[[š]X[Ú^™K™›]

_HŒÏI\ÙUŒÑXÚ\Ú[ÛˆÛÛ™IÚYˆ
\ÙUŒÑXÚ\Ú[ÛŠH[ÙH™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKÒ[

_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ^Xİ]Ü‹›X^X™PXİÚ]XÚ\Ú[ÛŠˆÈHËˆXÚ\Ú[ÛˆHXÚ\Ú[Û‹ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆØ[]HØ[]ˆ\İÛ\ÈH\İİXØÙ\ÜÙ[Û\ËˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆ[ÙPÛÛ™šYÈH[ËÈÛ‰İ\ÜÈ[ÙHÛÛ™šYÈH[™XYH\YYX›İ™Bˆ™Ğ\›İ™YÚ^™HHXİX[[š]X[Ú^™KËÈ\ÙHš[˜[ÛÛ\]YÚ^™BˆØ[]İ[˜Y\ÈHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K›İÙ\šXÙKØ[]X[˜YÙ\‚ˆËœİ]OË˜[YOËİ[˜Y\ÈÎˆˆHØ]Ú
Îˆ^Ù\[ÛŠHÈKˆ˜YRY[]HHY[]KËÈ\ÜÈØ[›ÛšXØ[Y[]Bˆ™Ğ\›İ˜[Û\ÜÈH\›İ˜[Û\ÜËËÈ\ÜÈ\›İ˜[Û\ÜÈ›ÜˆX\›š[™Âˆ
BˆˆËÈ™XÛÜ™ŒÈÜÚ][ÛˆÜ[™YˆYˆ
\ÙUŒÑXÚ\Ú[ÛŠHÂˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹œÙ]ÛÛÛİÛŠY[]K›Z[ŒÌ
BˆBˆBˆH[ÙHÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ‘PÓÔ‘“ÔÔĞSˆ˜XÚÈ]ÙH›ÜÜÙY
›ÜˆY\JK]™[ˆYˆ›ØÚÙYˆËÈ\È™]™[ÈÜ[H™K\›ÜÜØ[ÈÙˆHØ[YHÚÙ[‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜YSY™XŞXÛKœ™XÛÜ™›ÜÜØ[
Y[]K›Z[
BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQHQS•UNˆX\šÈ\È›ØÚÙYˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆY[]K˜›ØÚÙY
ˆ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ•S’Ó“ÕÓˆ‹ˆ™ÑXÚ\Ú[Û‹˜›ØÚÓ]™[Ë›˜[YHÎˆ•S’Ó“ÕÓˆ‹ˆ™ÑXÚ\Ú[Û‹œ]X[]Kˆ™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙBˆ
BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈQ‘PÖPÓNˆ‘×Ğ“ĞÒÑQ
\Ú[™ÈY[]H›ÜˆÛÛœÚ\İ[˜ŞJBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜YSY™XŞXÛK™™Ğ›ØÚÙY
ˆY[]K›Z[ˆ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ•S’Ó“ÕÓˆ‹ˆ™ÑXÚ\Ú[Û‹˜›ØÚÓ]™[Ë›˜[YHÎˆ•S’Ó“ÕÓˆ‚ˆ
Bˆˆš[˜[XÚ\Ú[Û‘Ø]K›ÙĞ›ØÚÙY˜YJ™ÑXÚ\Ú[ÛŠHÈYÙÊ]Z[
HBˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æªÈ‘È“ĞÒÑQˆ	ÚY[]KœŞ[X›ÛHˆ
Âˆœ™X\ÛÛIÙ™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛŸH]™[IÙ™ÑXÚ\Ú[Û‹˜›ØÚÓ]™[HŠBˆ™Z™Xİ[Û•[[Y]Kœ™XÛÜ™
‘‘È‹™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ•S’Ó“ÕÓˆŠBˆˆËÈ™XÛÜ™\È›ÜˆX\›š[™È
Ú[][][ÛˆÛ›K›È^Xİ][ÛŠBˆ^Xİ]Ü‹˜œ˜Z[Ëœ™XÛÜ™›ØÚÙY˜YJˆZ[HY[]K›Z[ˆ\ÙHHY[]Kœ\ÙKˆÛİ\˜ÙHHY[]KœÛİ\˜ÙKˆ›ØÚÔ™X\ÛÛˆH™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ•S’Ó“ÕÓˆ‹ˆ]X[]HH™ÑXÚ\Ú[Û‹œ]X[]KˆÛÛ™šY[˜ÙHH™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKˆ
BˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈTTˆSÑHPT“’S‘ÎˆÚYİÈ˜XÚÈ›ØÚÙY˜Y\ÂˆËÈ˜XÚÈÚ]ÓÕS]™H\[™YYˆÙH˜YY\ÈÜÜ[š]BˆËÈ\È[˜X›\ÈX\›š[™ÈÚ]\ˆH‘È\ÈÛÈİšXİÜˆ\›ÜšX]BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
Ù™Ëœ\\“[ÙJHÂˆÚYİÓX\›š[™Ñ[™Ú[™K›Û‘™Ğ›ØÚÙY˜YJˆZ[HY[]K›Z[ˆŞ[X›ÛHY[]KœŞ[X›Ûˆ›ØÚÔ™X\ÛÛˆH™ÑXÚ\Ú[Û‹˜›ØÚÔ™X\ÛÛˆÎˆ•S’Ó“ÕÓˆ‹ˆ›ØÚÓ]™[H™ÑXÚ\Ú[Û‹˜›ØÚÓ]™[Ë›˜[YHÎˆ•S’Ó“ÕÓˆ‹ˆİ\œ™[šXÙHHËœ™Y‹ˆ›ÜÜÙYÚ^™TÛÛH›ÜÜÙYÚ^™Kˆ]X[]HH™ÑXÚ\Ú[Û‹œ]X[]KˆÛÛ™šY[˜ÙHH™ÑXÚ\Ú[Û‹˜ÛÛ™šY[˜ÙKˆ\ÙHHY[]Kœ\ÙKˆ
BˆBˆBˆH[ÙHYˆ
ËœÜÚ][Û‹š\ÓÜ[ˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒ	‰ˆËœÜÚ][Û‹œ[™[™Õ™\šYJJHÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKŒL’VˆÔ’UPĞS8 %^]X[˜YÙ[Y[š\™\È›ÜˆS–HÜÚ][Û‚ˆËÈ]\ÈÚÙ[œÈ
]UÚÙ[ˆˆ
K™YØ\™\ÜÈÙˆ[™[™Õ™\šYHİ]K‚ˆËÂˆËÈ‘Q“Ô‘H\Èš^ˆ\ÓÜ[ˆH]UÚÙ[ˆˆ	‰ˆ\[™[™Õ™\šYK‚ˆËÈYˆH™\šYHÛÜ›İ][™Hİ[Y
”ÈYË[™^[™ÈYÊK[™[™Õ™\šYBˆËÈİ^YYYH[™Yš[š][H8 %HÜÚ][ÛˆØ\È\›X[™[H[š\ÚX›HÂˆËÈS^]ÚXÚÜÈ
ÓYÈ]Xİ[Û‹™X\İ\H^]Ë]™\][™ÊK‚ˆËÈH›İ›İYÚ]Ûİ[‘U‘TˆÙ[ˆ\ÈØ\ÈH›ÛİØ]\ÙHÙ‚ˆËÈ]™HÙ[È™]™\ˆ^Xİ][™Ë‚ˆËÂˆËÈ›İÎˆÙH[\ˆ^]X[˜YÙ[Y[YˆÚÙ[œÈ^\İ][ˆÙH[ˆ]ˆËÈXXÚ^Y\‰ÜÈÚXÚÑ^]

H[™HYÈØY™]H™][ˆ\È›Ü›X[‚ˆËÂˆËÈĞQ‘UNˆYˆ[™[™Õ™\šYH\ÈÕSYH]LŒÈÛ
™\šYBˆËÈÛÜ›İ][™H\Èİ[[›š[™ÊKÙHÚÚ\^Xİ][™ÈHXİX[Ù[ÂˆËÈ]›ÚY˜XÚ[™ÈÚ]H™\šYKˆY\ˆLŒÈÙH›Ü˜ÙKXÛX\‚ˆËÈ[™[™Õ™\šYHÛÈHÜÚ][Ûˆ™XÛÛY\È[HX[˜YÙY‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ËœÜÚ][Û‹œ[™[™Õ™\šYJHÂˆ˜[[™[™ĞYÙS\ÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHËœÜÚ][Û‹™[U[YBˆYˆ
[™[™ĞYÙS\ÈLŒÌ
HÂˆËÈ™\šYHÛÜ›İ][™Hİ[\È[YH8 %ÚÚ\\ÈXÚÈ]ÙÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹ˆ¸£ìÈÔS‘S‘×Õ‘T’Q–WH	İËœŞ[X›ÛH	Ü[™[™ĞYÙS\ÈÈL\ÈÛ8 %™\šYHÚ[™İÈXİ]™KÚÚ\^]XÚÈŠBˆ™]\›‚ˆBˆËÈLŒÈ[\ÙY8 %™\šYHÚ[™İÈ\Èİ™\‹‚ˆËÈKKÌMQ’VˆYˆ\È\ÈHÜÚ][Ûˆ™\İÜ™Yœ›ÛH\œÚ\İ[˜ÙH
[U[YBˆËÈ\Èœ›ÛHH‘U’SÕTÈÙ\ÜÚ[ÛŠKH[™[™Õ™\šYHØ]ÚÙÈ]\İÈ[ˆÛ‹XÚZ[‚ˆËÈ”ÈÚXÚÈ™Y›Ü™HÙH\Üİ[YHHÜÚ][Ûˆ\È™X[ˆ›[™H›Ü˜ÙKXÛX\š[™È\™BˆËÈÛİ[\›ˆ[ˆ[‹XÛÛ™š\›YYÚÜİ
^HX^H]™H˜Z[Y™Y›Ü™HHÚ[
H[ÂˆËÈH[H›Ü[ˆˆÜÚ][Ûˆ]^]ÙÚXÈšY\ÈÈÙ[‚ˆËÂˆËÈØ]NˆÛ›H›Ü˜ÙKXÛX\ˆYˆHØ]ÚÙÈ\È[™XYHš\™Y]X\İÛ˜ÙH\ÂˆËÈÙ\ÜÚ[Ûˆ
\İ[™[™Õ™\šYUØ]ÚÙĞ]ˆ›İİ\[YS\ÊKˆYˆHØ]ÚÙÈ\Û‰İˆËÈ[ˆY]ÚÚ\\ÈXÚÈ8 %]Ú[š\™HÚ][ˆŒÈ[™™\ÛÛ™HÛÜœ™XİK‚ˆ˜[Ø]ÚÙÑš\™Y\ÔÙ\ÜÚ[ÛˆH\İ[™[™Õ™\šYUØ]ÚÙĞ]ˆ›İİ\[YS\ÂˆYˆ
]Ø]ÚÙÑš\™Y\ÔÙ\ÜÚ[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹ˆ¸£ìÈÔS‘S‘×Õ‘T’Q–WÔ‘TÕÔ‘QH	İËœŞ[X›ÛH	Ü[™[™ĞYÙS\ÈÈL\È8 %]ØZ][™ÈØ]ÚÙÈ”ÈÚXÚÈ
›İY]š\™Y\ÈÙ\ÜÚ[ÛŠHŠBˆ™]\›‚ˆBˆËÈØ]ÚÙÈ[™XYH˜[ˆ[™Y‰İ™\ÛÛ™H\ÈÜÚ][Ûˆ8 %YX[œÈZ]\‚ˆËÈKˆ”È˜Z[Y\İXÚÈ
Ø]ÚÙÈY]›Üˆ™]JKÔ‚ˆËÈ‹ˆHÜÚ][ÛˆØ\È™\šYšYY\È™X[][™[™Õ™\šYHØ\Û‰İÛX\™Y
YÊK‚ˆËÈ[ˆØ\ÙHNˆX]™H]Ø]ÚÙÈ™]šY\Ëˆ[ˆØ\ÙHˆØY™HÈ›Ü˜ÙKXÛX\ˆ›İË‚ˆËÈ\ØÜš[Z[˜]NˆYˆØ]ÚÙÈ˜[ˆˆLÈYÛÈÚ]İ]ÛX\š[™È\Ë\Üİ[YHØ\ÙH‹‚ˆ˜[Ø]ÚÙĞYÙHHŞ\İ[K˜İ\œ™[[YSZ[\Ê
HH\İ[™[™Õ™\šYUØ]ÚÙĞ]ˆYˆ
Ø]ÚÙĞYÙHLÌ
HÂˆËÈØ]ÚÙÈ\İš\™Y]”ÈX^H]™H˜Z[Y8 %Ú]™H][›İ\ˆŞXÛBˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹ˆ¸£ìÈÔS‘S‘×Õ‘T’Q–WÕĞRUÕÑH	İËœŞ[X›ÛHØ]ÚÙÈ˜[ˆ	İØ]ÚÙĞYÙKÌL\ÈYÛÈ8 %ØZ][™È›Üˆ™]HŠBˆ™]\›‚ˆBˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¸¦¨;î#ÈÔS‘S‘×Õ‘T’Q–WÔÕPÒ×H	İËœŞ[X›ÛH	Ü[™[™ĞYÙS\ÈÈL\È8 %Ø]ÚÙÈ˜[ˆ	İØ]ÚÙĞYÙKÌL\ÈYÛÈ[™Y[™[™Õ™\šYO]YKˆˆ
Âˆ‘›Ü˜ÙKXÛX\š[™È
Ø]ÚÙÈ”È]\İ]™HÛÛ™š\›YY™X[ÚÙ[œÊKˆŠBˆŞ[˜Ú›Ûš^™Y
ÊHÂˆËœÜÚ][ÛˆHËœÜÚ][Û‹˜ÛÜJ[™[™Õ™\šYHH˜[ÙJBˆBˆHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆBˆËÈÜÚ][ÛˆX[˜YÙ[Y[
^]ÊHHSĞVTÈ[Ûš]ÜˆÜ[ˆÜÚ][ÛœÂˆËÈ]™[ˆÚ[ˆ]\ÙYÙH™YYÈX[˜YÙHš\ÚÈÛˆ^\İ[™ÈÜÚ][ÛœÂ‚ˆËÈKŒ‹ŒLˆXYÈÙÙÚ[™È›Üˆ^]ÚXÚÈ›İÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'å!ÑVUÒPÒ×H	İËœŞ[X›ÛH\ÓÜ[]YH[\š[™È^]X[˜YÙ[Y[ŠB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKŒ8 %•QÈĞQ‘UH‘U
SĞVTËSÓŠBˆËÈKKŒÌˆ8 %PQQ‘QQ•QÈUPÕSÓˆ’VˆËÂˆËÈHHRH^Y\œÈ\™H\Ù[\ÜÈYˆHšXÙH™YY™]\›œÈ
ÚÙ[‚ˆËÈYÙÙY
H[™H\‹]˜Y\ˆ^]XÚXÚÈÛÙHX\ÚÜÈ]BˆËÈ˜[[™È˜XÚÈÈH[HšXÙKˆÚ]›İÚ[[HØ[İ[]YˆËÈ\È	K›ÈÓÈ•Q×ÑUPÕQ]™\ˆš\™\È8 %HÜÚ][ÛˆÚ]È]ˆËÈLL	HÛˆHRHÚ[HH]˜[X]Üˆ[šÜÈœİ[›]Û‹‚ˆËÂˆËÈKKŒÌˆ›ÛİØ]\ÙNˆHš[ÜˆØY™]H™]™[YYÛˆ™\İšXÙXˆËÈ
ÚXÚ˜[È˜XÚÈÈ\İšXÙJKˆYˆ\İšXÙHØ\ÈHİ[H[BˆËÈšXÙK™\İšXÙH[TšXÙH
ˆŒXØ\ÈSÑH8¡¤ˆØY™]H™]ˆËÈ™]™\ˆš\™Y8¡¤ˆÜÚ][ÛœÈØ]]LL	H[™Yš[š][H
[\M›KˆËÈ•RÖLÛH[ˆ\Ù\‹\™\ÜYØÜ™Y[œÚİ
K‚ˆËÂˆËÈ‘UÈÓPÖN‚ˆËÈHšYÙÙ\ˆÛˆ˜]ÔšXÙHHS‘YÙHHÌÈ
Ø\ÈŒÊH8 %HXYˆËÈšXÙH™YY›ÜˆÌ
ÈÙXÛÛ™ÈTÈHYÎÈÈ›İØZ]›Üˆ\İšXÙK‚ˆËÈHSÓÈšYÙÙ\ˆÛˆ™\İšXÙHIHÙˆ[H
^\İ[™È]
K‚ˆËÈH[ˆTTˆ[ÙKØ\H™XÛÜ™YÜÜÈ]LIHÛÈX\›š[™È\ÂˆËÈ›İÚ\ÛÛ™YHLL	Hİ]Y\œÈ
\È\ÈHÚ[K›İ™X[[Û™^JK‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆHÂˆ˜[ÜÈHËœÜÚ][Û‚ˆ˜[[PYÙS\ÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
HH
ÜË™[U[YKZÙRYˆÈ]ˆHÎˆŞ\İ[K˜İ\œ™[[YSZ[\Ê
JBˆ˜[˜]ÔšXÙHHË›\İšXÙBˆ˜[\İšXÙHHËš\İÜK›\İÜ“[

OËœšXÙU\ÙÎˆŒˆ˜[™\İšXÙHHYˆ
˜]ÔšXÙHˆŒ
H˜]ÔšXÙH[ÙH\İšXÙB‚ˆËÈKKŒÌˆÓÈ[™\[™[YÈšYÙÙ\œÂˆËÈ
JHXYšXÙH™YYˆ˜]ÔšXÙHOH›ÜˆÌ
ÈÙXÛÛ™È8¡¤ˆYÂˆËÈ
ŠHÜ˜\ÚYšXÙNˆ™\İšXÙHIHÙˆ[H8¡¤ˆYÂˆ˜[XY™YYYÈH[PYÙS\ÈHÌÌ	‰‚ˆÜË™[TšXÙHˆŒ	‰‚ˆ˜]ÔšXÙHHŒˆ˜[Ü˜\ÚYYÈH[PYÙS\ÈHÌÌ	‰‚ˆÜË™[TšXÙHˆŒ	‰‚ˆ™\İšXÙH[ˆŒK‹ŠÜË™[TšXÙH
ˆŒJBˆ˜[YÑ]XİYHXY™YYYÈÜ˜\ÚYYÂ‚ˆYˆ
YÑ]XİY
HÂˆËÈKKŒÌˆTT‹SSÑHÔÔÈĞT8 %]›ÚYÚ\ÛÛš[™ÈX\›š[™ÈÚ]LL	Hİ]Y\œË‚ˆËÈ[ˆ\\ˆ[ÙK›Ü˜ÙHH™XÛÜ™Y^]šXÙHÈ[H0åÈÍH
HLIHÜÜÊBˆËÈÛÈH›İX\›œÈœ›ÛHH™X[\İXÈÛÜœİXØ\ÙHYÈ˜]\ˆ[ˆØ]\İ›ÜXÈ›Ú\ÙK‚ˆ˜[\Ô\\ˆHHÈÛÛ™šYÔİÜ™K›ØY
\XØ][ÛÛÛ^
Kœ\\“[ÙHHØ]Ú
Îˆ^Ù\[ÛŠHÈYHBˆ˜[Y™™Xİ]™Q^]šXÙHHYˆ
\Ô\\ŠHÂˆÜË™[TšXÙH
ˆÍHËÈLIH\\ˆYÈØ\ˆH[ÙHÂˆ™\İšXÙK˜ÛÙ\˜ÙP]X\İ
ÜË™[TšXÙH
ˆŒJHËÈ]™Nˆ[H›ÛÜˆÈ]›ÚY]‹XK^™\›ÂˆBˆ˜[šYÙÙ\’Ú[™HYˆ
XY™YYYÊH‘PQÑ‘QQˆ[ÙHÔTÒ‚ˆ\œ›Ü“ÙÙÙ\‹Ø\›Šˆ›İÙ\šXÙH‹ˆ¼'æª•QÈĞQ‘UH‘U
	šYÙÙ\’Ú[™
Nˆ	İËœŞ[X›ÛHZ[IİË›Z[ZÙJ
_Hˆ
Âˆ™[OIÜÜË™[TšXÙ_H\İšXÙOI˜]ÔšXÙH\İšXÙOI\İšXÙHYÙOIÙ[PYÙS\ÈÈL\Èˆ
Âˆ‰ÚYˆ
\Ô\\ŠH”TTˆØ\LIHˆ[ÙH“U‘H™\İšXÙOI™\İšXÙHŸH8 %“ÔÑHÑS‚ˆ
BˆYÙÊ¼'æª•QÈĞQ‘UH
	šYÙÙ\’Ú[™
Nˆ	İËœŞ[X›ÛH	ÚYˆ
\Ô\\ŠHŠ\\ˆLIHØ\
Hˆ[ÙHœšXÙx¢bŸH8 %›Ü˜Ú[™È^]‹Ë›Z[
BˆËÈ\ÚHØ\YšXÙH[ÈÈÛÈİÛœİ™X[HÛÜÙK\™XÛÜ™\œÈ\ÙH]ˆYˆ
\Ô\\ŠHÂˆHÂˆË›\İšXÙHHY™™Xİ]™Q^]šXÙBˆË›\İšXÙTÛİ\˜ÙHH”•Q×ÔĞQ‘UWĞĞTQÑVUˆËÈKKÍˆHØ]Ú
Îˆ^Ù\[ÛŠHßBˆBˆHÂˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH”•Q×ÔĞQ‘UWÓ‘U‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆ
BˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹”•Q×ÔĞQ‘UWÓ‘UÙ[\œ›Üˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆËÈ[ÛÈÚ\H^Y\‹\İÜ™HÜÚ][Ûˆ˜XÚÙ\œÈ[[YYX][HÛÈBˆËÈRHİÜÈ\Ü^Z[™ÈHYÙÙYÜÚ][ÛˆÛˆ™^™Yœ™\Ú‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[Y™™Xİ]™Q^]šXÙKˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”•Q×ÑUPÕQ
HHØ]Ú
Îˆ^Ù\[ÛŠHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[Y™™Xİ]™Q^]šXÙKˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[”•Q×ÑUPÕQ
HHØ]Ú
Îˆ^Ù\[ÛŠHßBˆ™]\›‚ˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹”YÔØY™]S™]ÚXÚÈ˜Z[Yˆ	ÙK›Y\ÜØYÙ_H‹JBˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKH8 %‘SSÕ‘QKKHS’U‘T”ĞSÒT‘Ñ“ÓÔ—ÔÓ‚ˆËÂˆËÈH™KY\Ü]ÚLŒ	H›Ü˜ÙKXÛÜÙHØ\Èš\š[™ÈX\›Y\ˆ
[™İšXİ\ŠBˆËÈ[ˆXXÚİX‹]˜Y\‰ÜÈİÛˆT‘Ñ“ÓÔˆÚXÚËÚXÚZ[LNMH\˜BˆËÈ[[[Û˜[H˜[ˆQ•Tˆ\X[\Ù[È›Ùš][ØÚÈÈ˜Z[[™ÈÙÚXÂˆËÈÛÈÚXÚÜÈÛİ[™XÛİ™\‹ˆ™[[İš[™ÈH[š]™\œØ[›ØÚÈ™\İÜ™\ÈBˆËÈİX‹]˜Y\‹[İÛ™Y^]\š[Üš]HÚZ[‹ˆH•Q×ÔĞQ‘UWÓ‘UX›İ™BˆËÈİ[Ø]Ú\È8¢iNNKIHØ]\İ›ÜXÈYÜË[™XXÚİX‹]˜Y\‰ÜÂˆËÈT‘Ñ“ÓÔ—ÔÕÔÔÕ
›İÈ˜XÚÈ]LŒ[ˆÚ]ÛÚ[ˆšXHKKJBˆËÈØ]Ú\ÈHÜœ[™Y\ÜÚ][ÛˆØ\Ù\È\È›ØÚÈØ\ÈYX[ÂˆËÈ˜XÚÜİÜ8 %Ú]İ]Ú[[™ÈÚ[›™\œÈ]ÚXÚË‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈVQTˆS”ÒUSÓˆÒPÒÈH\Ü˜YHÜÚ][ÛœÈÛˆHØ^HTˆËÈÚXÚÈYˆÜÚ][ÛˆÚİ[˜[œÚ][ÛˆÈHYÚ\ˆ^Y\‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆˆ˜[˜[œÚ][ÛˆHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹˜ÚXÚÕ˜[œÚ][ÛŠˆZ[HË›Z[ˆİ\œ™[XØ\HË›\İXØ\ˆİ\œ™[šXÙHHİ\œ™[šXÙKˆ
BˆˆYˆ
˜[œÚ][Û‹œÚİ[˜[œÚ][ÛŠHÂˆËÈ\]HÜÚ][Û‰ÜÈ˜Y[™È[ÙHÈ™]È^Y\‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙHH˜[œÚ][Û‹Ó^Y\‹›˜[YHËÈKKŒŒMÎˆ\ÙH[[K›˜[YH›İ\Ü^S˜[YH8 %™]™[È“QPÒTĞ“QWĞÒTÜ]ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH˜[œÚ][Û‹Ó^Y\‹™[[ÚšBˆˆËÈ\]H\™Ù]È›Üˆ™]È^Y\‚ˆ˜[
™]Õ™]ÔÓ
HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹™Ù]Y\İY\™Ù]ÊˆË›Z[˜[œÚ][Û‹›™]ÕZÙT›Ùš]˜[œÚ][Û‹›™]ÔİÜÜÜÂˆ
BˆˆËÈ\]HÜÚ][Ûˆ›YÜÈ˜\ÙYÛˆ™]È^Y\‚ˆÚ[ˆ
˜[œÚ][Û‹Ó^Y\ŠHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹•˜Y[™Ó^Y\‹“QWĞÒTOˆÂˆËœÜÚ][Û‹š\Ğ›YPÚ\ÜÚ][ÛˆHYBˆËœÜÚ][Û‹š\ÔÚ]ÛÚ[”ÜÚ][ÛˆH˜[ÙBˆËœÜÚ][Û‹˜›YPÚ\ZÙT›Ùš]H™]ÕˆËœÜÚ][Û‹˜›YPÚ\İÜÜÜÈH™]ÔÓˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹•˜Y[™Ó^Y\‹•Œ×ÔUPSUHOˆÂˆËœÜÚ][Û‹š\ÔÚ]ÛÚ[”ÜÚ][ÛˆH˜[ÙBˆBˆ[ÙHOˆßBˆBˆˆYÙÊ¼'æ VQTˆTˆ	İËœŞ[X›ÛHˆ
Âˆ‰İ˜[œÚ][Û‹™œ›ÛS^Y\‹™[[Úš_H8¡¤ˆ	İ˜[œÚ][Û‹Ó^Y\‹™[[Úš_Hˆ
Âˆ›XØ\		ÊË›\İXØ\ÌL
KÒ[

_RÈ‹Ë›Z[
BˆˆËÈKKŒŒˆ‘SSÕ‘Q˜[ÙH›ZYX\›š[™ÈÚ[ˆÛˆ^Y\ˆ˜[œÚ][ÛœË‚ˆËÈ^Y\‹]\\È“ÕH˜YH^]8 %™XÛÜ™[™È\ÕÚ[]YH\™H[™›]YX]\š]BˆËÈÚ[ˆ˜]H]™[ˆÚ[ˆHÜÚ][Ûˆ]\ˆÛÜÙY]HÜÜË‚ˆËÈ›ZYX\›š[™ÈÚ[‹ÛÜÜÈ\È™XÛÜ™Y]XİX[^][ˆ^Xİ]Ü‹‚ˆBˆHØ]Ú
˜[œÑ^ˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹“^Y\ˆ˜[œÚ][ÛˆÚXÚÈ˜Z[Yˆ	İ˜[œÑ^›Y\ÜØYÙ_HŠBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ‘PTÕT–HSÑHVUÒPÒÈH]ZXÚÈØØ[ÈÚ]YÚ^]ÂˆËÈÚXÚÈ’T”Õ™Y›Ü™Hİ\ˆ^]ÙÚXÈÚ[˜ÙH™X\İ\H\ÈİšXİ[\ÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKŒMLH8 %H›YËX˜\ÙYØ]H
\Õ™X\İ\TÜÚ][Û˜ÂˆËÈ˜Y[™Ó[ÙOOH•‘PTÕT–H˜
HØ\ÈZ\ÜÚ[™ÈÜÚ][ÛœÈÚ\™HH›YÂˆËÈØ\È™]™\ˆÙ]]^KXÛÛ™š\›HÔˆØ\ÈÛX\™YHH]\ˆ^Y\‚ˆËÈ˜[œÚ][Û‹ˆ™\İ[ˆ™X\İ\IÜÈXİ]™TÜÚ][ÛœÈİ[İÛ™YBˆËÈZ[]›ØÙ\ÜÕÚÙ[ŞXÛH™]™\ˆØ[YÚXÚÑ^]8 %ĞRT•BˆËÈØœÙ\™Y]
ÍL‹ÉH›ÜˆŒ
ÈZ[ˆYØZ[œİH
Í	H]ˆËÈÚXÚÑ^]Ûİ[]™Hš\™Y[[YYX][KˆÛİ\˜ÙHÙˆ]\ÂˆËÈØ\ÚÙ[™\˜][ÛRK˜Xİ]™TÜÚ][ÛœÎÈH›YÈ\È›İÈÛ›HBˆËÈİ\[Y[\H[‚ˆ˜[™X\İ\SİÛœÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK™Ù]Xİ]™TÜÚ][ÛŠË›Z[
HOH[ˆYˆ
ËœÜÚ][Û‹š\Õ™X\İ\TÜÚ][ÛˆËœÜÚ][Û‹˜Y[™Ó[ÙHOH•‘PTÕT–Hˆ™X\İ\SİÛœÊHÂˆËÈKŒ‹ŒLˆXYÈH[\š[™È™X\İ\H^]ÚXÚÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ä¬Õ‘PTÕT–HS•T—H	İËœŞ[X›ÛH\Õ™X\İ\OIİËœÜÚ][Û‹š\Õ™X\İ\TÜÚ][ÛŸH[ÙOIİËœÜÚ][Û‹˜Y[™Ó[Ù_HŠBˆˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆˆËÈKŒ‹ŒLˆXYÈHÚİÈšXÙH™Z[™È\ÙYˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ä¬Õ‘PTÕT–H’PÑWH	İËœŞ[X›ÛHˆ
Âˆ›\İšXÙOIİË›\İšXÙ_H\İÜS\İIİËš\İÜK›\İÜ“[

OËœšXÙU\ÙHˆ
Âˆ™[TšXÙOIİËœÜÚ][Û‹™[TšXÙ_HTÒS‘ÏIİ\œ™[šXÙHŠBˆˆËÈKŒˆXYÈH™\šYHÚXÚÑ^]\È™Z[™ÈØ[Yˆ˜\ˆ™X\İ\TÜÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK™Ù]Xİ]™TÜÚ][ÛŠË›Z[
BˆYˆ
™X\İ\TÜÈOH[	‰ˆËœÜÚ][Û‹š\ÓÜ[ŠHÂˆËÈKH‘PÓÕ‘T–NˆØ\ÚÙ[™\˜][ÛRIÜÈ[‹[Y[[ÜHX\\È[\HY\ˆ™\İ\‚ˆËÈ™K\™YÚ\İ\ˆHÜÚ][Ûˆœ›ÛH\œÚ\İYËœÜÚ][Ûˆ]HÛÈÚXÚÑ^]ÛÜšÜË‚ˆ˜[™XÕİHYˆ
ËœÜÚ][Û‹™X\İ\UZÙT›Ùš]ˆ
HËœÜÚ][Û‹™X\İ\UZÙT›Ùš][ÙHŒˆ˜[™XÔÛİHYˆ
ËœÜÚ][Û‹™X\İ\TİÜÜÜÈ
HËœÜÚ][Û‹™X\İ\TİÜÜÜÈ[ÙHMŒˆËÈKKŒŒˆ\ÙH˜]È™X\İ\Q[TšXÙHYˆØ]™Y8 %›İÛ\YÙKXY™™XİY[TšXÙBˆ˜[™XÑ[TšXÙHHYˆ
ËœÜÚ][Û‹™X\İ\Q[TšXÙHˆ
HËœÜÚ][Û‹™X\İ\Q[TšXÙBˆ[ÙHËœÜÚ][Û‹™[TšXÙBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK›Ü[”ÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHH™XÑ[TšXÙKˆÜÚ][Û”ÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆZÙT›Ùš]İH™XÕİˆİÜÜÜÔİH™XÔÛİˆ
Bˆ™X\İ\TÜÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK™Ù]Xİ]™TÜÚ][ÛŠË›Z[
Bˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¼'ä¬Õ‘PTÕT–H‘PÓÕ‘T–WH	İËœŞ[X›ÛH™K\™YÚ\İ\™Y[ˆØ\ÚÙ[™\˜][ÛRHˆ
Âˆ™[OIİËœÜÚ][Û‹™[TšXÙ_HI™XÕİ	HÛI™XÔÛİ	HŠBˆËÈKKM^ŒÎHH8 %Ü\˜]ÜˆÜXÈ][HNˆ™K\™YÚ\İ\™YˆËÈ™X\İ\HÜÚ][ÛœÈUTÕ“ÕšYÙÙ\ˆ[ˆ[[YYX]HÙ[ˆËÈ[[ÚZ[ˆ˜\Ú\È\ÈØYYS‘H]™H›Ùš]X›H][İH\ÂˆËÈ›İ™[‹ˆØÚÈ\Y\ÈÛ›HÈ™XÛİ™\šY\ÈÚ]›ÂˆËÈÜšYÚ[˜[X^HÓÓ˜\Ú\È
ÛÜİÛÛH
Kˆ\œÚ\İYˆËÈÜÚ][ÛœÈÚ]˜[YÛÜİÛÛÚÚ\HØÚÈ8 %Z\‚ˆËÈ˜\Ú\È\È[™XYHÛ›İÛ‹‚ˆYˆ
ËœÜÚ][Û‹˜ÛÜİÛÛHŒ
HÂˆHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™KœÙ[”™XÛİ™\SØÚÕ˜XÚÙ\‹›ØÚÊˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ™X\ÛÛˆH•‘PTÕT–WÔ‘PÓÕ‘T–WÓ“×ĞTÒTÈ‹ˆ
BˆHØ]Ú
Îˆ›İØX›JHÈÊˆ˜Z[\ÛÙ
‹ÈBˆBˆB‚ˆËÈKKM^HH8 %S“ĞÒÈÚYHÙˆH™XÛİ™\HØÚË‚ˆËÈÜ\˜]Üˆ•Ú\™HU[›ØÚÕÚ]ÚZ[˜\Ú\Ê
H[›ØØ][Ûˆ]BˆËÈÙ[Y]˜[X][ÛˆXÚÈ8 %Ú]İ][ˆ[›ØÚÈØ[\‹ØÚÙYˆËÈ™X\İ\HÜÚ][ÛœÈİ^HØÚÙY›Ü™]™\‹ˆ‚ˆËÈ˜]K[[Z]Y
ÌËÛZ[
H[™[œÈHÚZ[ˆÛÜšÈÛˆSÈÛÂˆËÈ\ÈØ[\ÈØY™HÈš\™H]™\HXÚËˆ™XÛİ™\SØÚÕ[›ØÚÙ\‚ˆËÈÚÜXÚ\˜İZ]ÈÚ[ˆHZ[\Û‰İØÚÙY‚ˆHÂˆ˜[Ù™ÔÛ˜\HÛÛ™šYÔİÜ™K›ØY
\XØ][ÛÛÛ^
BˆÛÛK›Y™XŞXÛX›İ™[™Ú[™KœÙ[”™XÛİ™\SØÚÕ[›ØÚÙ\‹›X^X™P][\[›ØÚÊˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›ÛˆØ[]HØ[]X[˜YÙ\‹™Ù]Ø[]

Kˆ\]\\RÙ^HHÙ™ÔÛ˜\š\]\\RÙ^Kˆ
BˆHØ]Ú
Îˆ›İØX›JHÈÊˆ™]™\ˆœ™XZÈH™X\İ\HXÚÈ
‹ÈBˆˆËÈKŒˆØ[İ[]Hİ\œ™[	“›Üˆİ[X[[ÛÛœÚİ›Û[İ[Û‚ˆ˜[İ\œ™[›™\™XİHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“Ü[”›Ø[š]Kš[œÜXİ
ˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆİ\œ™[šXÙHHİ\œ™[šXÙKˆ[TÛİ\˜ÙHHËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙKˆİ\œ™[Ûİ\˜ÙHHË›\İšXÙTÛİ\˜ÙKˆ[TÛÛHËœÜÚ][Û‹™[TÛÛY™\ÜËˆİ\œ™[ÛÛHË›\İšXÙTÛÛY‹ˆšXÙP˜\Ú\Ô™\ØØ[YHËœÜÚ][Û‹œšXÙP˜\Ú\Ô™\ØØ[YˆÛÛ^H›İÙ\šXÙK˜İ\œ™[›ÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹ˆ
Bˆ˜[İ\œ™[›İHYˆ
İ\œ™[›™\™Xİ›ÚÊHİ\œ™[›™\™Xİœ›İ[ÙHŒˆˆËÈKŒˆXYÈHÙÈH“™Z[™ÈØ[İ[]YˆYˆ
™X\İ\TÜÈOH[
HÂˆ˜[™X\İ\T›™\™XİHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“Ü[”›Ø[š]Kš[œÜXİ
[TšXÙHH™X\İ\TÜË™[TšXÙKİ\œ™[šXÙHHİ\œ™[šXÙKÛÛ^H›İÙ\šXÙK™X\İ\QXYËÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_HŠBˆ˜[™X\İ\T›HYˆ
™X\İ\T›™\™Xİ›ÚÊH™X\İ\T›™\™Xİœ›İ[ÙHŒˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'ä¬Õ‘PTÕT–HÒPÒ×H	İËœŞ[X›ÛHˆ
ÂˆœšXÙOIİ\œ™[šXÙH™X\İ\Q[OIİ™X\İ\TÜË™[TšXÙ_H›Iİ™X\İ\T›™›]
J_IHŠBˆBˆˆËÈKŒ‹ŒLˆÚXÚÈ›ÜˆÜ›ÜÜË]˜YH›Û[İ[ÛˆÈ[ÛÛœÚİ
Œ	JÈØZ[œÊBˆËÈ[ÛÛœÚİXØÙ\È›Û[İ[ÛœÈœ›ÛH[HXØ\˜[™ÙH
	LËILJBˆYˆ
İ\œ™[›İHŒŒ	‰ˆË›\İXØ\[ˆLÌŒ‹ŒLÌÌŒ
HÂˆ˜[Úİ[›Û[İHHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKœÚİ[›Û[İUÓ[ÛÛœÚİ
ˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆœ›ÛS^Y\ˆH•‘PTÕT–H‹ˆİ\œ™[›İHİ\œ™[›İˆİ\œ™[šXÙHHİ\œ™[šXÙKˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ
BˆˆYˆ
Úİ[›Û[İJHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æ <'ä¬Ô“ÓSÕSÓ—H	İËœŞ[X›ÛH‘PTÕT–H8¡¤ˆSÓÓ”ÒÕˆ
ÂˆŠÉØİ\œ™[›İÒ[

_IH]]’QHHŠBˆˆËÈ^Xİ]HH›Û[İ[Ûˆ
ÛÜÙH™X\İ\HÜÚ][Û‹Ü[ˆ[ÛÛœÚİ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™^Xİ]T›Û[İ[ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆœ›ÛS^Y\ˆH•‘PTÕT–H‹ˆ[TšXÙHHİ\œ™[šXÙKËÈ™]È[HHİ\œ™[šXÙBˆÜÚ][Û”ÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆİ\œ™[›İHİ\œ™[›İˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
BˆˆËÈÛÜÙH™X\İ\H˜XÚÚ[™È
ÜÚ][Ûˆİ^\ÈÜ[ˆ[™\ˆ[ÛÛœÚİ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK˜ÛÜÙTÜÚ][ÛŠˆË›Z[İ\œ™[šXÙKÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK‘^]ÚYÛ˜[•RÑWÔ“Ñ’Uˆ
BˆˆËÈ\]HÜÚ][Ûˆ[ÙBˆËœÜÚ][Û‹š\Õ™X\İ\TÜÚ][ÛˆH˜[ÙBˆËœÜÚ][Û‹˜Y[™Ó[ÙHH“SÓÓ”ÒÕÓSTˆ‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¼'æ ‚ˆˆYÙÊ¼'æ <'ä¬“ÓSÕQÈSÓÓ”ÒÕˆ	İËœŞ[X›ÛH
ÉØİ\œ™[›İÒ[

_IHœ›ÛH™X\İ\Hˆ
Âˆ“›İÈšY[™È›ÜˆLLLH‹Ë›Z[
Bˆˆ™]\›ˆËÈ›Û[İ[Ûˆ›ØÙ\ÜÙYÛ‰İ^]ˆBˆBˆˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK˜ÚXÚÑ^]
Ë›Z[İ\œ™[šXÙJBˆˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK‘^]ÚYÛ˜[’Ó
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ä¬Õ‘PTÕT–HVUH	İËœŞ[X›ÛHˆ
ÂˆœÚYÛ˜[I^]ÚYÛ˜[šXÙOIİ\œ™[šXÙHŠBˆˆËÈK‹È’VˆSĞVTÈÑSÓˆVUH™]\›ˆØ\][
È›Ùš]Ú\™HÈØ[]ˆËÈ\Ù\ˆ™\]Y\İYˆL	HÈØ[]L	HÈ™X\İ\HˆH\È™\]Z\™\ÈÑSS‘ÂˆËÈÛ™Z]š[Üˆ›Û[İYÚ]İ]Ù[[™ËØÚÚ[™ÈØ\][›Ü™]™\‚ˆËÈ™]È™Z]š[ÜˆÑSš\œİ™]\›ˆØ\][ÈØ[][ˆ™KY[\ˆYˆ]X[YšYYˆˆ˜[›™\™Xİ™X\İ\Q^]HÛÛK›Y™XŞXÛX›İ™[™Ú[™K“Ü[”›Ø[š]Kš[œÜXİ
ˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆİ\œ™[šXÙHHİ\œ™[šXÙKˆ[TÛİ\˜ÙHHËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙKˆİ\œ™[Ûİ\˜ÙHHË›\İšXÙTÛİ\˜ÙKˆ[TÛÛHËœÜÚ][Û‹™[TÛÛY™\ÜËˆİ\œ™[ÛÛHË›\İšXÙTÛÛY‹ˆšXÙP˜\Ú\Ô™\ØØ[YHËœÜÚ][Û‹œšXÙP˜\Ú\Ô™\ØØ[YˆÛÛ^H›İÙ\šXÙK™X\İ\Q^]ÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹ˆ
Bˆ˜[›İHYˆ
›™\™Xİ™X\İ\Q^]›ÚÊH›™\™Xİ™X\İ\Q^]œ›İ[ÙHŒˆˆËÈK‹YÎˆ^Xİ]HÙ[[™Û›HÛÜÙHİ˜]YŞHYˆÛÛ™š\›YYˆ˜[Ù[™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH•‘PTÕT–WÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆˆËÈKKÌˆ’VˆYˆ˜\Y[Ûš]Üˆ[™XYHÛÜÙYËœÜÚ][Û‹™\]Y\İÙ[ˆËÈ™]\›œÈS‘PQWĞÓÔÑQ8 %İ[ÛX[ˆ\İX‹]˜Y\ˆİ]HÛÈRHÚÜİÈÛX\‹‚ˆËÈÛ›H˜Z[ÛˆRSQÔ‘U–PP“H
Ú[™]H™^XÚÈÚ]œ™\ÚšXÙKØ˜[[˜ÙJK‚ˆYˆ
Ù[™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JH™]\›‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK˜ÛÜÙTÜÚ][ÛŠˆË›Z[İ\œ™[šXÙK^]ÚYÛ˜[ˆ
Bˆ™XÙ[PÛÜÙY\ÖİË›Z[HHŞ\İ[K˜İ\œ™[[YSZ[\Ê
BˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ¼'ä¬‘PTÕT–HÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_H
ÉÜ›İÒ[

_IHˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH™\İ[IÙ[™\İ[‹Ë›Z[
BˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK‘^]ÚYÛ˜[•RÑWÔ“Ñ’U
HÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ä¬Õ‘PTÕT–HÓÓH	İËœŞ[X›ÛHˆ
ÂˆŠÉÜ›İÒ[

_IHØ\][™]\›™YÚÙ[ˆ]˜Z[X›H›Üˆİ\ˆ^Y\œÈÈ™KY[\ˆŠBˆBˆˆ™]\›ˆËÈ^]›ØÙ\ÜÙYˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈÒUÓÒSˆSÑHVUÒPÒÈHYÙ[ˆØØ[ÈÚ][˜KY˜\İ^]ÂˆËÈÚXÚÈÑPÓÓ‘Y\ˆ™X\İ\HÚ[˜ÙHÚ]ÛÚ[œÈ™YY˜\İ™XXİ[ÛœÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ËœÜÚ][Û‹š\ÔÚ]ÛÚ[”ÜÚ][ÛˆËœÜÚ][Û‹˜Y[™Ó[ÙHOH”ÒUÓÒSˆŠHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆˆËÈKŒˆØ[İ[]Hİ\œ™[	“›Üˆİ[X[[ÛÛœÚİ›Û[İ[Û‚ˆ˜[İ\œ™[›™\™XİHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“Ü[”›Ø[š]Kš[œÜXİ
ˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆİ\œ™[šXÙHHİ\œ™[šXÙKˆ[TÛİ\˜ÙHHËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙKˆİ\œ™[Ûİ\˜ÙHHË›\İšXÙTÛİ\˜ÙKˆ[TÛÛHËœÜÚ][Û‹™[TÛÛY™\ÜËˆİ\œ™[ÛÛHË›\İšXÙTÛÛY‹ˆšXÙP˜\Ú\Ô™\ØØ[YHËœÜÚ][Û‹œšXÙP˜\Ú\Ô™\ØØ[YˆÛÛ^H›İÙ\šXÙK˜İ\œ™[›ÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹ˆ
Bˆ˜[İ\œ™[›İHYˆ
İ\œ™[›™\™Xİ›ÚÊHİ\œ™[›™\™Xİœ›İ[ÙHŒˆˆËÈKŒ‹ŒLˆÚXÚÈ›ÜˆÜ›ÜÜË]˜YH›Û[İ[ÛˆÈ[ÛÛœÚİ
Œ	JÈØZ[œÊBˆËÈÚ]ÛÚ[ˆ8¡¤ˆ[ÛÛœÚİˆHYÙ[ˆ^H\›™Y[ÈH[ÛÛœÚİBˆYˆ
İ\œ™[›İHŒŒ	‰ˆË›\İXØ\[ˆLÌŒ‹ŒLÌÌŒ
HÂˆ˜[Úİ[›Û[İHHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKœÚİ[›Û[İUÓ[ÛÛœÚİ
ˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆœ›ÛS^Y\ˆH”ÒUÓÒSˆ‹ˆİ\œ™[›İHİ\œ™[›İˆİ\œ™[šXÙHHİ\œ™[šXÙKˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ
BˆˆYˆ
Úİ[›Û[İJHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'æ <'äªHÔ“ÓSÕSÓ—H	İËœŞ[X›ÛHÒUÓÒSˆ8¡¤ˆSÓÓ”ÒÕˆ
ÂˆŠÉØİ\œ™[›İÒ[

_IHQÑSˆÒSˆ8¡¤ˆSÓÓ”ÒÕHŠBˆˆËÈ^Xİ]HH›Û[İ[Ûˆ
ÛÜÙHÚ]ÛÚ[ˆÜÚ][Û‹Ü[ˆ[ÛÛœÚİ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™^Xİ]T›Û[İ[ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆœ›ÛS^Y\ˆH”ÒUÓÒSˆ‹ˆ[TšXÙHHİ\œ™[šXÙKËÈ™]È[HHİ\œ™[šXÙBˆÜÚ][Û”ÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆİ\œ™[›İHİ\œ™[›İˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
BˆˆËÈÛÜÙHÚ]ÛÚ[ˆ˜XÚÚ[™È
ÜÚ][Ûˆİ^\ÈÜ[ˆ[™\ˆ[ÛÛœÚİ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜ÛÜÙTÜÚ][ÛŠˆË›Z[İ\œ™[šXÙKÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[•RÑWÔ“Ñ’Uˆ
BˆˆËÈ\]HÜÚ][Ûˆ[ÙBˆËœÜÚ][Û‹š\ÔÚ]ÛÚ[”ÜÚ][ÛˆH˜[ÙBˆËœÜÚ][Û‹˜Y[™Ó[ÙHH“SÓÓ”ÒÕÓÔ’US‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¼'æî‚ˆˆYÙÊ¼'æî<'äªHQÑSˆ8¡¤ˆSÓÓ”ÒÕˆ	İËœŞ[X›ÛH
ÉØİ\œ™[›İÒ[

_IHÚ]ÛÚ[ˆÚ[ˆ8¡¤ˆˆ
Âˆ“›İÈ[[™ÈLLLH‹Ë›Z[
Bˆˆ™]\›ˆËÈ›Û[İ[Ûˆ›ØÙ\ÜÙYÛ‰İ^]ˆBˆBˆˆËÈKKŒNLˆ‘TÕT•‘PÓÕ‘T–H8 %Ø[YH]\›ˆ\È™X\İ\H
NMÊK‚ˆËÈY\ˆ›İ™\İ\Ú]ÛÚ[•˜Y\RK˜Xİ]™TÜÚ][ÛœÈ\È[\H[‹[Y[[ÜK‚ˆËÈÚXÚÑ^]

H™]\›œÈÓ›Ü™]™\ˆ8¡¤ˆÜÚ][ÛœÈÚ]YH[™Yš[š][K‚ˆËÈ™K\™YÚ\İ\ˆœ›ÛH\œÚ\İYËœÜÚ][Ûˆ]HÛÈ^]ÈÛÜšÈÛÜœ™XİK‚ˆËÈKKŒLÎˆZ\œ›ÜˆHKKŒÌš^8 %\ÓÜ[ˆ\È˜[ÙHÚ[H[™[™Õ™\šYO]YBˆËÈ
Ú][ˆš\œİLŒÊKˆÚ]İ]\Ë]™H^\ÈZY\™\İ\™]™\ˆ™K\™YÚ\İ\‹‚ˆ˜[ØÒ\Ô™X[ÜÚ][ÛˆHËœÜÚ][Û‹š\ÓÜ[ˆˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒ	‰ˆËœÜÚ][Û‹œ[™[™Õ™\šYJBˆYˆ
XÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RKš\ÔÜÚ][ÛŠË›Z[
H	‰ˆØÒ\Ô™X[ÜÚ][ÛŠHÂˆ˜[™XÕHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™Ù]›ZYZÙT›Ùš]

Bˆ˜[™XÔÛHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™Ù]›ZYİÜÜÜÊ
BˆËÈKKMÈ8 %™\İÜ™HXZËÒËİ˜Z[[™ÔİÜÛÈ[›Ùš]ØÚÜÈ™KX\›HY\ˆ™\İ\‚ˆËÈÚ]İ]\ËHÚ]ÛÚ[ˆÜÚ][Ûˆ]XZÙY
ÌÌ	HÛİ[™K\™YÚ\İ\‚ˆËÈÚ]XZÏLŒ[™S›İXİ[Ûˆ\Ø\›YY8 %Ø[YH›ÛİØ]\ÙH\È[ÛÛœÚİš^‚ˆ˜[ØÔ™XÛİ™\™YXZÈHËœÜÚ][Û‹œXZÑØZ[”İZÙRYˆÈ]ˆŒHÎˆŒˆ˜[ØÔ™XÛİ™\™YÈHËœÜÚ][Û‹šYÚ\İšXÙKZÙRYˆÈ]ˆËœÜÚ][Û‹™[TšXÙHBˆÎˆËœÜÚ][Û‹™[TšXÙBˆ˜[ØÔ™XÛİ™\™Y˜Z[HYˆ
ØÔ™XÛİ™\™YÈˆËœÜÚ][Û‹™[TšXÙJHÂˆ˜[[˜[ZXÕ˜Z[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™›ZY˜Z[İ
ØÔ™XÛİ™\™YXZÊBˆØÔ™XÛİ™\™YÈ
ˆ
KŒH[˜[ZXÕ˜Z[ÈLŒ
BˆH[ÙHÂˆËœÜÚ][Û‹™[TšXÙH
ˆ
KŒHÛİ[‹›X]˜XœÊ™XÔÛ
HÈLŒ
BˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜YÜÚ][ÛŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK”Ú]ÛÚ[”ÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆ[TÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆ[U[YHHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KËÈKKŒNL˜ˆØY™H˜[˜XÚÈ
›ÈÛ[YHšY[
BˆX\šÙ]Ø\\ÙHË›\İXØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™ÂˆZÙT›Ùš]İH™XÕˆİÜÜÜÔİH™XÔÛˆ][˜Ú]›Ü›HHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK“][˜Ú]›Ü›K”STÑ•S‹ˆXZÔ›İHØÔ™XÛİ™\™YXZËˆYÚØ]\“X\šÈHØÔ™XÛİ™\™YËˆ˜Z[[™ÔİÜHØÔ™XÛİ™\™Y˜Z[ˆ
Bˆ
Bˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¼'äªHÔÒUÓÒSˆ‘PÓÕ‘T–WH	İËœŞ[X›ÛH™K\™YÚ\İ\™Yˆ
Âˆ™[OIİËœÜÚ][Û‹™[TšXÙ_HIÜ™XÕÒ[

_IHÛIÜ™XÔÛÒ[

_IHXZÏJÉÜØÔ™XÛİ™\™YXZËÒ[

_IHŠBˆBˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜ÚXÚÑ^]
Ë›Z[İ\œ™[šXÙJBˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[”Ú]ÛÚ[‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßB‚ˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[’Ó
HÂˆ˜[^][[ÚšHHÚ[ˆ
^]ÚYÛ˜[
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[”•Q×ÑUPÕQOˆ¼'ä ‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[‘U—ÔÑSOˆ¼'æª‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[•RÑWÔ“Ñ’UOˆ¼'ã«È‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑHOˆ¼'ä¬‚ˆ[ÙHOˆ¼'äâH‚ˆB‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äªHÔÒUÓÒSˆVUH	İËœŞ[X›ÛHˆ
ÂˆœÚYÛ˜[I^]ÚYÛ˜[šXÙOIİ\œ™[šXÙHŠB‚ˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑJHÂˆËÈÙ[IK]ÍIHšYBˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHHŒKˆ™X\ÛÛˆH”ÒUÓÒS—ÔT•PSÕRÑWÌTÕ‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÔÒUÓÒSˆŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK›X\šÑš\œİZÙQÛ™JË›Z[
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK›Û”\X[Ù[
Ë›Z[ŒJHËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆYÙÊ¼'ä¬ÒUÓÒSˆT•PSˆ	İËœŞ[X›ÛHÛÛIKšY[™ÈÍIH‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆËÈ[^]›Üˆ[İ\ˆÚYÛ˜[ÂˆËÈK‹YÎˆÛ›HÛÜÙHİ˜]YŞHÜÚ][ÛˆYˆÙ[Ø\ÈÛÛ™š\›YYˆËÈKŒŒMˆ0©ÒSSUUP“WÑS•–WÓS‘WÑVUÔ‘PTÓÓˆ8 %™Yš^Ú]BˆËÈÜÚ][Û‰ÜÈ[[]]X›H˜Y[™Ó[ÙH[œİXYÙˆH\™ÛÙYˆËÈ”ÒUÓÒS—ÈˆÛÈHSÓÓ”ÒÕY[\™YÜÚ][Ûˆ]Ûİ›İ]YˆËÈ›İYÚHÚ]ÛÚ[ˆ^]œ˜[˜Úİ[›İ\›˜[È\ÂˆËÈSÓÓ”ÒÕÏÒQÓS‹ˆÙYHÚ]ÛÚ[•˜Y\RK™^Xİ]Q^]›Ü‚ˆËÈH[˜][Û˜[H8 %\È\ÈHZ\œ›ÜˆÙˆ]™\Z\‹‚ˆËÈ›ÛY[ÈHŒMˆ]Ú[Û™ÜÚYHH›İ\›˜[ˆËÈÚ[™ÛKX]]Üš]H™\Z\ˆÛÈHÚ[™ÛHÒHŞXÛH˜[Y]\ÂˆËÈ›İXØÛİ[[™È[™^][[™HÛÜœ™Xİ™\ÜË‚ˆ˜[[[]]X›S[™T™Yš^ŒLÈHËœÜÚ][Û‹˜Y[™Ó[ÙBˆZÙRYˆÈ]š\Ó›İ›[šÊ
H	‰ˆ]\\˜Ø\ÙJ
HOH”ÕS‘T‘ˆBˆË\\˜Ø\ÙJ
HÎˆ”ÒUÓÒSˆ‚ˆ˜[Ù[™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH‰Ú[[]]X›S[™T™Yš^ŒLßWÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
B‚ˆËÈKKÌˆ’VˆS‘PQWĞÓÔÑQYX[œÈ˜\Y[Ûš]Üˆ[™XYH^]Y\ÈÜÚ][Û‹‚ˆËÈİ[ÛX[ˆ\Ú]ÛÚ[•˜Y\RHİ]HÛÈHRH[H\Ø\X\œË‚ˆYˆ
Ù[™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JH™]\›‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜ÛÜÙTÜÚ][ÛŠˆË›Z[İ\œ™[šXÙK^]ÚYÛ˜[ˆ
BˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ‰^][[ÚšHÒUÓÒSˆÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH™\İ[IÙ[™\İ[‹Ë›Z[
B‚ˆ™]\›ˆËÈ^]›ØÙ\ÜÙYˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'äª|'æ ˆÒUÓÒSˆV‘TÔÈVUÒPÒÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜËš\ÔšYJË›Z[
JHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆ˜[İ\œ™[[ÛY[[HHË›[ÛY[[HÎˆŒˆˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË˜ÚXÚÑ^]
ˆË›Z[İ\œ™[šXÙKİ\œ™[[ÛY[[Bˆ
BˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[”Ú]^™\ÜÎ‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[’Ó
HÂˆ˜[^][[ÚšHHÚ[ˆ
^]ÚYÛ˜[
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[•RÑWÔ“Ñ’UÌLOˆ¼'æ ‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[•RÑWÔ“Ñ’UÍLOˆ¼'æ ˆ‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[•RÑWÔ“Ñ’UÌÌOˆ¸¦¨H‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[”ÕÔÓÔÔÈOˆ¼'ä©H‚ˆ[ÙHOˆ¼'äâH‚ˆB‚ˆËÈKKŒM8 %RÑWÔ“Ñ’UÖ\™HQTˆ[™ÜË›İ[XÛÜÙBˆËÈÚYÛ˜[Ëˆ™]š[İ\ÛH]™\H[™ÈÛÜÙYHÚÛHšYH]š\œİˆËÈ]Ùˆ
ÌÌ	Kˆ›İÎˆXXÚ[™Èš\™\ÈŒ	H\X[\Ù[[ˆËÈÛÜÙHÛ›HÛˆÕÔÓÔÔÈÈRSS‘×ÔÕÔÈSÓQS•SWÑPUÂˆËÈSQWÑVU‚ˆ˜[\ÓY\”[™ÈH^]ÚYÛ˜[[ˆ\İÙŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[•RÑWÔ“Ñ’UÌÌˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[•RÑWÔ“Ñ’UÍLˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË‘^]ÚYÛ˜[•RÑWÔ“Ñ’UÌLˆ
BˆYˆ
\ÓY\”[™ÊHÂˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHHŒŒˆ™X\ÛÛˆH‘V‘TÔ×ÉÙ^]ÚYÛ˜[›˜[Y_WÔT•PSÌŒÕ‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÑV‘TÔÈŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË›Û”\X[Ù[
Ë›Z[ŒŒ
HËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆYÙÊ‰^][[ÚšHV‘TÔÈT•PSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_HÛÛŒ	KšY[™È	H‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆËÈK‹YÎˆÛ›HÛÜÙHİ˜]YŞHÜÚ][ÛˆYˆÙ[Ø\ÈÛÛ™š\›YYˆ˜[Ù[™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH‘V‘TÔ×ÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆˆËÈKKÌˆ’VˆÛX[ˆ\İX‹]˜Y\ˆİ]H]™[ˆYˆS‘PQWĞÓÔÑQˆYˆ
Ù[™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JH™]\›‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜË™^]šYJË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ‰^][[ÚšHV‘TÔÈÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH™\İ[IÙ[™\İ[‹Ë›Z[
B‚ˆ™]\›‚ˆBˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ8¦(;î#ÈPS’TSUQVUÒPÒÂˆËÈ\™[Z[]H[YH^]8 %X[š\[]ÜœÈ]™H[™XYHYˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RKš\ÔÜÚ][ÛŠË›Z[
JHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK˜ÚXÚÑ^]
Ë›Z[İ\œ™[šXÙJBˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[“X[š\[]Y‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK“X[š\^]ÚYÛ˜[’Ó
HÂˆËÈKKŒM8 %Y\™Y\X[Ù[
Œ	H\ˆ[™ÊBˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK“X[š\^]ÚYÛ˜[”T•PSÕRÑJHÂˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHHŒŒˆ™X\ÛÛˆH“PS’TÔT•PSÕRÑWÌŒÕ‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÓPS’TŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK›Û”\X[Ù[
Ë›Z[ŒŒ
HËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆYÙÊ¼'ä¬PS’TT•PSˆ	İËœŞ[X›ÛHÛÛŒ	KšY[™È	H‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆËÈK‹YÎˆÛ›HÛÜÙHİ˜]YŞHÜÚ][ÛˆYˆÙ[Ø\ÈÛÛ™š\›YYˆ˜[Ù[™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH“PS’TSUQÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆˆËÈKKÌˆ’VˆÛX[ˆ\İX‹]˜Y\ˆİ]H]™[ˆYˆS‘PQWĞÓÔÑQˆYˆ
Ù[™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JH™]\›‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ¸¦(;î#ÈPS’TVUˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH™\İ[IÙ[™\İ[‹Ë›Z[
Bˆ™]\›‚ˆBˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'ã&HSÓÓ”ÒÕVUÒPÒÂˆËÈKŒˆÚXÚÈ›ÜˆSÓÓ”ÒÕ™Yš^Ú[˜ÙH[ÙH[˜ÛY\ÈÜXÙH[ÙH
SÓÓ”ÒÕÓÔ’US]ÊBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKš\ÔÜÚ][ÛŠË›Z[
HˆËœÜÚ][Û‹˜Y[™Ó[ÙOËœİ\ÕÚ]
“SÓÓ”ÒÕŠHOHYJHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆˆËÈKKŒŒˆSÓÓ”ÒÕ‘PÓÕ‘T–H8 %Y\ˆ™\İ\Xİ]™TÜÚ][ÛœÈ\È[\BˆËÈ[ÛÛœÚİ˜Y\RK˜ÚXÚÑ^]™]\›œÈÓ›Üˆ[œ™YÚ\İ\™YÜÚ][ÛœË‚ˆËÈ™K\™YÚ\İ\ˆœ›ÛH\œÚ\İY]HÛÈ^]Èš\™HÛÜœ™XİK‚ˆËÈKKŒLÎˆ[˜ÛYH[™[™Õ™\šYHÜÚ][ÛœÈ
]™H^\ÈÚ][ˆLŒÈÚ[™İÊK‚ˆ˜[\Ò\Ô™X[ÜÚ][ÛˆHËœÜÚ][Û‹š\ÓÜ[ˆˆ
ËœÜÚ][Û‹œ]UÚÙ[ˆˆŒ	‰ˆËœÜÚ][Û‹œ[™[™Õ™\šYJBˆYˆ
XÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKš\ÔÜÚ][ÛŠË›Z[
Bˆ	‰ˆ\Ò\Ô™X[ÜÚ][Ûˆ	‰ˆËœÜÚ][Û‹™[TšXÙHˆ
HÂˆ˜[˜]Ó[ÙHHËœÜÚ][Û‹˜Y[™Ó[ÙHÎˆ“SÓÓ”ÒÕÓÔ’US‚ˆ˜[ÜXÙS[ÙHHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK”ÜXÙS[ÙK˜[YSÙŠˆ˜]Ó[ÙKœ™[[İ™T™Yš^
“SÓÓ”ÒÕÈŠJBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK”ÜXÙS[ÙK“Ô’USBˆËÈKKMÈ8 %™\İÜ™HXZÈ[™YÚ]Ø]\ˆÛÈXZÑ˜]ÙİÛ“ØÚÈ[™ˆËÈ˜Z[[™ÔİÜ\™HÛÜœ™XİH™KX\›YYY\ˆ™\İ\ˆ™]š[İ\ÛH\ÙBˆËÈY˜][YÈŒÙ[TšXÙK\Ø\›Z[™ÈS›Ùš]›İXİ[Ûˆ›Ü‚ˆËÈ™XÛİ™\™YÜÚ][ÛœËˆHÚÙ[ˆ]XZÙY
ÌLÍ	H[™Ø\È™K\™YÚ\İ\™YˆËÈ]XZÏL	HÛİ[[\[HØ^HÈ	HÚ]›ÈØÚÈš\š[™Ë‚ˆ˜[™XÛİ™\™YXZÈHËœÜÚ][Û‹œXZÑØZ[”İZÙRYˆÈ]ˆŒHÎˆŒˆ˜[™XÛİ™\™YÈHËœÜÚ][Û‹šYÚ\İšXÙKZÙRYˆÈ]ˆËœÜÚ][Û‹™[TšXÙHBˆÎˆËœÜÚ][Û‹™[TšXÙBˆ˜[ÛİHÜXÙS[ÙK˜˜\ÙTÓ›]ÈYˆ
]H
HLMKŒ[ÙH]˜ÛÙ\˜ÙP]X\İ
LMKŒ
HBˆ˜[™XÛİ™\™Y˜Z[HYˆ
™XÛİ™\™YÈˆËœÜÚ][Û‹™[TšXÙJHÂˆ˜[[˜[ZXÕ˜Z[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™›ZY˜Z[İ
™XÛİ™\™YXZÊBˆ™XÛİ™\™YÈ
ˆ
KŒH[˜[ZXÕ˜Z[ÈLŒ
BˆH[ÙHÂˆËœÜÚ][Û‹™[TšXÙH
ˆ
KŒHÛİ[‹›X]˜XœÊÛİ
HÈLŒ
BˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜YÜÚ][ÛŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK“[ÛÛœÚİÜÚ][ÛŠˆZ[HË›Z[Ş[X›ÛHËœŞ[X›Ûˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆ[TÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆ[U[YHHËœÜÚ][Û‹™[U[YKZÙRYˆÈ]ˆHÎˆ
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHÌ
ˆŒÌ
KˆX\šÙ]Ø\\ÙHË›\İXØ\ZÙRYˆÈ]ˆHÎˆLÌŒˆ\]ZY]U\ÙHË›\İ\]ZY]U\ÙZÙRYˆÈ]ˆHÎˆWÌŒˆ[TØÛÜ™HHLŒˆZÙT›Ùš]İHÜXÙS[ÙK˜˜\ÙUˆİÜÜÜÔİHÛİˆÜXÙS[ÙHHÜXÙS[ÙKˆ\Ô\\“[ÙHHÙ™Ëœ\\“[ÙKˆXZÔ›İH™XÛİ™\™YXZËˆYÚØ]\“X\šÈH™XÛİ™\™YËˆ˜Z[[™ÔİÜH™XÛİ™\™Y˜Z[ˆ
Bˆ
BˆYÙÊ¼'ã&HÓSÓÓ”ÒÕ‘PÓÕ‘T–WH	İËœŞ[X›ÛH[ÙOI˜]Ó[ÙH[OIİËœÜÚ][Û‹™[TšXÙ_HXZÏJÉÜ™XÛİ™\™YXZËÒ[

_IHÏIÈ‰Kˆ‹™›Ü›X]
™XÛİ™\™YÊ_H‹Ë›Z[
BˆBˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜ÚXÚÑ^]
Ë›Z[İ\œ™[šXÙJBˆËÈKKŒÍŒˆ8 %[ÛÛœÚİİ[K\šXÙH^]ˆØ[YHš^\È]X[]KˆÚ]İ]ˆËÈ\ËHİXÚÈšXÙH™YY[œÈ›L	H[™[ÛÛœÚİ	ÜÈÚXÚÑ^]ˆËÈ™]\›œÈÓ›Ü™]™\ˆ
ÜÚ][ÛœÈÚ]x $Î[˜Ú[™ÙY[ˆHRJK‚ˆ[ˆÂˆ˜[[ÛÛœÚİÛZ[œÈH
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHËœÜÚ][Û‹™[U[YJHÈŒÌˆ˜[šXÙQœ™\Ú™\ÜÈHYˆ
Ë›\İšXÙU\]Hˆ
HŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHË›\İšXÙU\]H[ÙHÛ™Ë“PVÕSQBˆYˆ
šXÙQœ™\Ú™\ÜÈHL
ˆŒÌ	‰ˆ[ÛÛœÚİÛZ[œÈHŒ
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¼'ã&x£ì{î#ÈSÓÓ”ÒÕÕSKT’PÑHVUˆ	İËœŞ[X›ÛH™YYYÙOIÜšXÙQœ™\Ú™\ÜËÍŒÌ[Z[ˆ[IÛ[ÛÛœÚİÛZ[œß[Z[ˆŠBˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH“SÓÓ”ÒÕÔÕSWÔ’PÑH‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[ËœÜÚ][Û‹™[TšXÙKˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”ÕÔÓÔÔÊBˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ¸£ì{î#ÈSÓÓ”ÒÕÕSKT’PÑNˆ	İËœŞ[X›ÛHšXÙH™YYİ[H	ÜšXÙQœ™\Ú™\ÜËÍŒÌ[Z[‹›Ü˜ÙYÓ‹Ë›Z[
Bˆ™]\›‚ˆBˆBˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[“[ÛÛœÚİ‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[’Ó
HÂˆ˜[^][[ÚšHHÚ[ˆ
^]ÚYÛ˜[
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[•RÑWÔ“Ñ’UOˆ¼'ã&H‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[•RSS‘×ÔÕÔOˆ¼'ã«È‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑHOˆ¼'ä¬‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”ÕÔÓÔÔÈOˆ¼'æäH‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”•Q×ÑUPÕQOˆ¸¦¨;î#È‚ˆ[ÙHOˆ¼'äâH‚ˆBˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã&HÓSÓÓ”ÒÕVUH	ÚY[]KœŞ[X›ÛHÚYÛ˜[I^]ÚYÛ˜[šXÙOIİ\œ™[šXÙHŠB‚ˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑJHÂˆËÈÙ[L	K]L	HšYHÈH[ÛÛ‚ˆ˜[\X[İHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™Ù]\X[Ù[İ
Ë›Z[
Bˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHH\X[İˆ™X\ÛÛˆH“SÓÓ”ÒÕÔT•PSÕRÑWÉÊ\X[İ
ˆL
KÒ[

_TÕ‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÓSÓÓ”ÒÕŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆËÈÛ”\X[Ù[Y˜[˜Ù\È[™ËÙš\œİZÙHÛ›HY\ˆØ[›ÛšXØ[™XÙZ\‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK›Û”\X[Ù[
Ë›Z[\X[İ
HËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆYÙÊ¼'ä¬SÓÓ”ÒÕT•PSˆ	İËœŞ[X›ÛHÛÛ	Ê\X[İ
ŒL
KÒ[

_IKšY[™È™\İ‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆËÈKKÌH’VˆÛ›HÛÜÙHİX‹]˜Y\ˆÜÚ][ÛˆYˆÙ[ÛÛ™š\›YYˆ˜[[ÛÛœÚİÙ[™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH“SÓÓ”ÒÕÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆYˆ
[ÛÛœÚİÙ[™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JH™]\›‚‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆˆËÈK‹’Vˆ™[X\ÙH^Üİ\™HÛİˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
B‚ˆYÙÊ‰^][[ÚšHSÓÓ”ÒÕÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
B‚ˆ™]\›‚ˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ8«dUPSUHQTˆVUÒPÒÂˆËÈKŒ‹ŒLˆ›Ù™\ÜÚ[Û˜[ZYXØ\˜Y[™È^Y\ˆ
	LÈH	SHXØ\
BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RKš\ÔÜÚ][ÛŠË›Z[
HˆËœÜÚ][Û‹˜Y[™Ó[ÙHOH”UPSUHŠHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆ˜[İ\œ™[XØ\HË›\İXØ\ZÙRYˆÈ]ˆHÎˆŒˆˆËÈKKŒLNˆPQ’PÑHVU8 %YˆHšXÙH™YY\ÈÛÛ™HÛÛ\][BˆËÈÚ[[
™YL\İšXÙOL›È\İÜJHHÚÙ[ˆ\ÈZÙ[HYÙÙY‚ˆËÈ˜]\ˆ[ˆÚİÚ[™ÈLL	HÛˆRH[™Yš[š][K›Ü˜ÙHHÕÔÓÔÔÈ^]ˆËÈY\ˆHZ[]\ÈÙˆXYšXÙHÛÈHÜÚ][Ûˆ\ÈÛX[™Y\‚ˆ˜[]X[]RÛZ[œÈH
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHËœÜÚ][Û‹™[U[YJHÈŒÌˆ˜[\ÔšXÙQXYHËœ™YˆHŒ	‰ˆË›\İšXÙHHŒ	‰ˆ
Ëš\İÜK›\İÜ“[

OËœšXÙU\ÙÎˆŒ
HHŒˆYˆ
\ÔšXÙQXY	‰ˆ]X[]RÛZ[œÈHJHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¸«d<'ä UPSUHPQT’PÑHVUˆ	İËœŞ[X›ÛH™YL\İšXÙOL[IÜ]X[]RÛZ[œß[Z[ˆ8¡¤ˆ›Ü˜Ú[™ÈÓŠBˆ˜[XY^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”ÕÔÓÔÔÂˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH”UPSUWÑPQÔ’PÑH‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[ËœÜÚ][Û‹™[TšXÙKXY^]ÚYÛ˜[
BˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ¼'ä UPSUHPQT’PÑNˆ	İËœŞ[X›ÛHšXÙH™YYÛÛ™H[Z[‹›Ü˜ÙYÓ‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆËÈKKŒÍŒˆÕSKT’PÑHVU8 %HXY\šXÙHÚXÚÈX›İ™HÛ›Hš\™\ÂˆËÈÚ[ˆS™YHÛİ\˜Ù\È
™Y‹Û\İšXÙKÚ\İÜJH\™H™\›ËˆHXİX[ˆËÈ˜Z[\™H[ÙHH\Ù\ˆÙY\È\ÈÜÚ][ÛœÈİXÚÈ]^XİHŒ	H“ˆËÈ›Üˆİ\œÈ™XØ]\ÙH\İšXÙH\È›Û‹^™\›È]\Û‰İ™Y[ˆ™Yœ™\ÚYˆËÈH]SÜ˜Ú\İ˜]Ü‹Ğš\™^YKÜ[\™[ˆÛ\œÈ8 %ÛÈİ\œ™[šXÙH˜[ÂˆËÈ˜XÚÈÈ[TšXÙH[™ÔÓ™]™\ˆšYÙÙ\‹ˆ]XİšXHH™]ÂˆËÈ\İšXÙU\]H[Y\İ[\ˆYˆ›Èœ™\ÚšXÙH[ˆL
ÈZ[ˆ[™[ˆËÈŒ
ÈZ[‹›Ü˜ÙHHÕÔÓÔÔÈ^]ÛÈHÛİ\Èœ™YY‚ˆ˜[šXÙQœ™\Ú™\ÜÈHYˆ
Ë›\İšXÙU\]Hˆ
HŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHË›\İšXÙU\]H[ÙHÛ™Ë“PVÕSQBˆ˜[šXÙTİ[S\ÈHL
ˆŒÌˆYˆ
šXÙQœ™\Ú™\ÜÈHšXÙTİ[S\È	‰ˆ]X[]RÛZ[œÈHŒ
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¸«d8£ì{î#ÈUPSUHÕSKT’PÑHVUˆ	İËœŞ[X›ÛH™YYYÙOIÜšXÙQœ™\Ú™\ÜËÍŒÌ[Z[ˆ[IÜ]X[]RÛZ[œß[Z[ˆ8¡¤ˆ›Ü˜Ú[™ÈÓŠBˆ˜[İ[Q^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”ÕÔÓÔÔÂˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH”UPSUWÔÕSWÔ’PÑH‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[ËœÜÚ][Û‹™[TšXÙKİ[Q^]ÚYÛ˜[
BˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆYÙÊ¸£ì{î#ÈUPSUHÕSKT’PÑNˆ	İËœŞ[X›ÛHšXÙH™YYİ[H	ÜšXÙQœ™\Ú™\ÜËÍŒÌ[Z[‹›Ü˜ÙYÓ‹Ë›Z[
Bˆ™]\›‚ˆBˆˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK˜ÚXÚÑ^]
ˆË›Z[İ\œ™[šXÙKİ\œ™[XØ\ˆ
BˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[”]X[]N‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[’Ó
HÂˆ˜[^][[ÚšHHÚ[ˆ
^]ÚYÛ˜[
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[•RÑWÔ“Ñ’UOˆ¸§!H‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[•RSS‘×ÔÕÔOˆ¼'ã«È‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”ÕÔÓÔÔÈOˆ¼'æäH‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑHOˆ¼'ä¬‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”“ÓSÕWĞ“QPÒTOˆ¼'å-H‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”“ÓSÕWÓSÓÓ”ÒÕOˆ¼'æ ‚ˆ[ÙHOˆ¸£ìH‚ˆB‚ˆËÈKKŒMˆ8 %Y\™Y\X[Ù[
Œ	H\ˆ[™ÊBˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑJHÂˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHHŒŒˆ™X\ÛÛˆH”UPSUWÔT•PSÕRÑWÌŒÕ‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÔUPSUHŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK›Û”\X[Ù[
Ë›Z[ŒŒ
HËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆYÙÊ¼'ä¬UPSUHT•PSˆ	İËœŞ[X›ÛHÛÛŒ	KšY[™È	H‹Ë›Z[
Bˆ™]\›‚ˆBˆˆËÈÚXÚÈ›Üˆ›Û[İ[ÛœÈHÛ‰İÙ[\İ[™Ù™ˆÈYÚ\ˆ^Y\‚ˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”“ÓSÕWĞ“QPÒT
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆËÈ™YÚ\İ\ˆÚ]›YPÚ\^Y\ˆÛÈHÜÚ][ÛˆÛÛ[Y\È˜XÚÚ[™ÂˆYˆ
XÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RKš\ÔÜÚ][ÛŠË›Z[
JHÂˆ˜[˜ÕHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK™Ù]›ZYZÙT›Ùš]

Bˆ˜[˜ÔÛHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK™Ù]›ZYİÜÜÜÊ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK˜YÜÚ][ÛŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK›YPÚ\ÜÚ][ÛŠˆZ[HË›Z[ˆŞ[X›ÛHËœŞ[X›Ûˆ[TšXÙHHİ\œ™[šXÙKˆ[TÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆ[U[YHHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KˆX\šÙ]Ø\\ÙHİ\œ™[XØ\ˆ\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™ÂˆZÙT›Ùš]İH˜ÕˆİÜÜÜÔİH˜ÔÛˆ
Bˆ
BˆËœÜÚ][Û‹˜Y[™Ó[ÙHH“QWĞÒT‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¼'å-H‚ˆBˆYÙÊ‰^][[ÚšHUPSUx¡¤“QPÒTˆ	İËœŞ[X›ÛHXØ\W		Êİ\œ™[XØ\ÌL
KÒ[

_RÈIÈ‰KŒˆ‹™›Ü›X]
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK™Ù]›ZYZÙT›Ùš]

J_IH‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK‘^]ÚYÛ˜[”“ÓSÕWÓSÓÓ”ÒÕ
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
Bˆ˜[]X[]T›Û[İ[Û”›HÛÛK›Y™XŞXÛX›İ™[™Ú[™K“Ü[”›Ø[š]Kš[œÜXİ
ˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆİ\œ™[šXÙHHİ\œ™[šXÙKˆ[TÛİ\˜ÙHHËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙKˆİ\œ™[Ûİ\˜ÙHHË›\İšXÙTÛİ\˜ÙKˆ[TÛÛHËœÜÚ][Û‹™[TÛÛY™\ÜËˆİ\œ™[ÛÛHË›\İšXÙTÛÛY‹ˆšXÙP˜\Ú\Ô™\ØØ[YHËœÜÚ][Û‹œšXÙP˜\Ú\Ô™\ØØ[YˆÛÛ^H›İÙ\šXÙKœ]X[]T›Û[İS[ÛÛœÚİÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹ˆ
Bˆ˜[›Û[İYH]X[]T›Û[İ[Û”››ÚÈ	‰ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKœÚİ[›Û[İUÓ[ÛÛœÚİ
ˆZ[HË›Z[Ş[X›ÛHËœŞ[X›Ûœ›ÛS^Y\ˆH”UPSUH‹ˆİ\œ™[›İH]X[]T›Û[İ[Û”›œ›İˆİ\œ™[šXÙHHİ\œ™[šXÙKX\šÙ]Ø\\ÙHİ\œ™[XØ\ˆ
BˆYˆ
›Û[İY
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™^Xİ]T›Û[İ[ÛŠˆZ[HË›Z[Ş[X›ÛHËœŞ[X›Ûœ›ÛS^Y\ˆH”UPSUH‹ˆ[TšXÙHHİ\œ™[šXÙKÜÚ][Û”ÛÛHËœÜÚ][Û‹˜ÛÜİÛÛˆİ\œ™[›İH]X[]T›Û[İ[Û”›œ›İˆX\šÙ]Ø\\ÙHİ\œ™[XØ\\]ZY]U\ÙHË›\İ\]ZY]U\Ùˆ\Ô\\ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K”[[YS[ÙP]]Üš]Kš\Ô\\Š
KËÈKKŒMMŒÈ8 %[[YH]]Üš]K›İİ[HÙ™Âˆ
BˆËœÜÚ][Û‹˜Y[™Ó[ÙHH“SÓÓ”ÒÕÓÔ’US‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙQ[[ÚšHH¼'æ ‚ˆYÙÊ‰^][[ÚšHUPSUx¡¤“SÓÓ”ÒÕˆ	İËœŞ[X›ÛHXØ\W		Êİ\œ™[XØ\ÌL
KÒ[

_RÈ‹Ë›Z[
BˆH[ÙHÂˆËÈKKŒÈ•QÈ’Vˆ[ÛÛœÚİ™Z™XİY›Û[İ[Ûˆ
[ØØ\Y
H8 %UTÕÙ[›İË‚ˆËÈ™]š[İ\ÛNˆÜÚ][ÛˆØ\È™[[İ™Yœ›ÛH]X[]H]“ÕÛÛ[™“Õ[ˆ[ÛÛœÚİHÜœ[™YÚ]›ÈİÜÜÜË‚ˆËÈš^ˆYˆ[ÛÛœÚİÛÛ‰İZÙH]Ù[[[YYX][HÈ˜[šÈH›Ùš]ØY™[K‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸«dUPSUx¡¤“SÓÓ”ÒÕ‘R‘PÕQ8 %Ù[[™ÈÈ˜[šÈ›Ùš]ˆ	İËœŞ[X›ÛHšXÙOIİ\œ™[šXÙHŠBˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH”UPSUWÓSÓÓ”ÒÕÔ‘R‘PÕQÔÑS‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆYÙÊ¼'ä¬UPSUx¡¤“SÓÓ”ÒÕ‘R‘PÕQˆ	İËœŞ[X›ÛHÛÛÈ˜[šÈ
ÌL	H›Ùš]‹Ë›Z[
BˆBˆ™]\›‚ˆBˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸«dÔUPSUHVUH	İËœŞ[X›ÛHÚYÛ˜[I^]ÚYÛ˜[šXÙOIİ\œ™[šXÙHŠBˆˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH”UPSUWÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆˆËÈK‹’Vˆ™[X\ÙH^Üİ\™HÛİˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆˆYÙÊ‰^][[ÚšHUPSUHÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
Bˆˆ™]\›‚ˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'å-H“QHÒTVUÒPÒÂˆËÈKŒ‹ŒLˆ›Ù™\ÜÚ[Û˜[\™ÙKXØ\˜Y[™È^Y\ˆ
	SJÈXØ\
BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RKš\ÔÜÚ][ÛŠË›Z[
HˆËœÜÚ][Û‹˜Y[™Ó[ÙHOH“QWĞÒTŠHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK˜ÚXÚÑ^]
Ë›Z[İ\œ™[šXÙJBˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[›YPÚ\‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK‘^]ÚYÛ˜[’Ó
HÂˆ˜[^][[ÚšHHÚ[ˆ
^]ÚYÛ˜[
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK‘^]ÚYÛ˜[•RÑWÔ“Ñ’UOˆ¸§!H‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK‘^]ÚYÛ˜[•RSS‘×ÔÕÔOˆ¼'ã«È‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK‘^]ÚYÛ˜[”ÕÔÓÔÔÈOˆ¼'æäH‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑHOˆ¼'ä¬‚ˆ[ÙHOˆ¸£ìH‚ˆB‚ˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'å-HĞ“QPÒTVUH	İËœŞ[X›ÛHÚYÛ˜[I^]ÚYÛ˜[šXÙOIİ\œ™[šXÙHŠB‚ˆËÈKKŒMˆ8 %Y\™Y\X[Ù[
Œ	H\ˆ[™ÊBˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑJHÂˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHHŒŒˆ™X\ÛÛˆH“QPÒTÔT•PSÕRÑWÌŒÕ‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—Ğ“QPÒTŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK›Û”\X[Ù[
Ë›Z[ŒŒ
HËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆYÙÊ¼'ä¬“QPÒTT•PSˆ	İËœŞ[X›ÛHÛÛŒ	KšY[™È	H‹Ë›Z[
Bˆ™]\›‚ˆB‚ˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH“QPÒTÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆˆËÈK‹’Vˆ™[X\ÙH^Üİ\™HÛİˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆˆYÙÊ‰^][[ÚšH“QPÒTÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
Bˆˆ™]\›‚ˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ<'äâ|'ã«ÈTS•TˆVUÒPÒÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RKš\Ñ\
Ë›Z[
HËœÜÚ][Û‹˜Y[™Ó[ÙHOH‘TÒS•TˆŠHÂˆ˜[İ\œ™[šXÙHH™\ÛÛ™S]™TšXÙJÊBˆˆ˜[^]ÚYÛ˜[HÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK˜ÚXÚÑ^]
ˆË›Z[İ\œ™[šXÙKË›\İ\]ZY]U\Ùˆ
BˆËÈKKŒMÌ8 %š\™ZÜÙHX\›š[™È™YY˜XÚË‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘YXØ][Û”İX“^Y\RKœ™XÛÜ™Û™X\ÛÛŠË›Z[‘\[\‰Ù^]ÚYÛ˜[›˜[Y_HŠHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆˆYˆ
^]ÚYÛ˜[OHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK‘\^]ÚYÛ˜[’Ó
HÂˆ˜[^][[ÚšHHÚ[ˆ
^]ÚYÛ˜[
HÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK‘\^]ÚYÛ˜[“PVÔ‘PÓÕ‘T–HOˆ¼'ãáˆ‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK‘\^]ÚYÛ˜[”‘PÓÕ‘T–WÕT‘ÑUOˆ¸§!H‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK‘\^]ÚYÛ˜[”ÕÔÓÔÔÈOˆ¼'æäH‚ˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK‘\^]ÚYÛ˜[‘PUÔÔTSOˆ¼'ä ‚ˆ[ÙHOˆ¸£ìH‚ˆBˆˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH‘TÉÙ^]ÚYÛ˜[›˜[Y_H‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙBˆ
BˆˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RK˜ÛÜÙQ\
Ë›Z[İ\œ™[šXÙK^]ÚYÛ˜[
BˆˆËÈK‹’Vˆ™[X\ÙH^Üİ\™HÛİˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆˆYÙÊ‰^][[ÚšHTÑSˆ	İËœŞ[X›ÛH	Ù^]ÚYÛ˜[›˜[Y_Hˆ
Âˆ‰ÚYˆ
Ù™Ëœ\\“[ÙJH”TTˆˆ[ÙH“U‘HŸH‹Ë›Z[
Bˆˆ™]\›‚ˆBˆBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈSÑKTÔPÒQ’PÈVUÑÒPÂˆËÈˆËÈXXÚ˜YH\H\ÈY™™\™[^]Ú\˜Xİ\š\İXÜÎ‚ˆËÈHœ™\Ú][˜Úˆ˜\İ\İİÜË˜\İ\İ\X[ÂˆËÈHœ™XZÛİ]ˆ˜Z[™[İÈİXİ\™K[İÈÛ™Ù\ˆÛˆËÈH™]™\œØ[ˆZÙHš\œİ\™Ù]]ZXÚÙ\‹œ™XZÙ]™[ˆX\›BˆËÈH™[™[˜XÚÎˆÚY\İ]Y[˜ÙKYÚ\İİÜˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜\ˆÜÚ][Û•˜YU\HHHÂˆËÈHÈÙ]H˜YH\Hœ›ÛHÜÚ][Û‰ÜÈ˜Y[™È[ÙBˆËÈ’SÔ’UHNˆXXÚ[ÙHÚİ[]™HTÓÓUQ^]ÙÚXÂˆÚ[ˆ
ËœÜÚ][Û‹˜Y[™Ó[ÙK\\˜Ø\ÙJ
JHÂˆ”‘TĞSWÔÓ’TH‹“RPÔ“×ĞĞTˆOˆ[ÙT›İ]\‹•˜YU\K‘”‘TÒÓUSÒˆ“SÓQS•SWÔÕÒS‘ÈˆOˆ[ÙT›İ]\‹•˜YU\K”‘PRÓÕUĞÓÓ•S•PUSÓ‚ˆ”‘U’USˆOˆ[ÙT›İ]\‹•˜YU\K”‘U‘T”ĞSÔ‘PÓRSBˆ•ÒSWÑ“ÓÕÈˆOˆ[ÙT›İ]\‹•˜YU\K•ÒSWĞPĞÕSUSUSÓ‚ˆ’S”ÒQT—ÔÒT’ÈˆOˆ[ÙT›İ]\‹•˜YU\K’S”ÒQT—ÔÒT’ÂˆÓÔWÕQHˆOˆ[ÙT›İ]\‹•˜YU\KÓÔWÕQHËÈ’VˆÙ\\˜]H^]ÙÚXÂˆ“SÓÓ”ÒÕˆOˆ[ÙT›İ]\‹•˜YU\K‘ÔQPUSÓ‚ˆ”STÔÓ’TTˆˆOˆ[ÙT›İ]\‹•˜YU\K”ÑS•SQS•ÒQÓ’USÓ‚ˆ”ÕS‘T‘‹ÖPÓPÈ‹“QWĞÒTˆOˆ[ÙT›İ]\‹•˜YU\K•‘S‘ÔSPÒÂˆ[ÙHOˆ[ÙT›İ]\‹•˜YU\K•S’Ó“ÕÓ‚ˆBˆHØ]Ú
Îˆ^Ù\[ÛŠHÈ[ÙT›İ]\‹•˜YU\K•S’Ó“ÕÓˆBˆˆËÈØ[İ[]Hİ\œ™[“\Ú[™ÈPÕPS’PÑK›İX\šÙ]Ø\ˆËÈÔ’UPĞS’VˆËœ™YˆØ[ˆ™HX\šÙ]Ø\›İšXÙHBˆËÈ\ÙHË›\İšXÙH›ÜˆÛÛœÚ\İ[šXÙH˜XÚÚ[™Âˆ˜[İ\œ™[šXÙHHË›\İšXÙKZÙRYˆÈ]ˆHˆÎˆËš\İÜK›\İÜ“[

OËœšXÙU\ÙˆÎˆËœÜÚ][Û‹™[TšXÙBˆ˜[]›İ›™\™XİHÛÛK›Y™XŞXÛX›İ™[™Ú[™K“Ü[”›Ø[š]Kš[œÜXİ
ˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆİ\œ™[šXÙHHİ\œ™[šXÙKˆ[TÛİ\˜ÙHHËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙKˆİ\œ™[Ûİ\˜ÙHHË›\İšXÙTÛİ\˜ÙKˆ[TÛÛHËœÜÚ][Û‹™[TÛÛY™\ÜËˆİ\œ™[ÛÛHË›\İšXÙTÛÛY‹ˆšXÙP˜\Ú\Ô™\ØØ[YHËœÜÚ][Û‹œšXÙP˜\Ú\Ô™\ØØ[YˆÛÛ^H›İÙ\šXÙKš[]›İÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹ˆ
BˆYˆ
\]›İ›™\™Xİ›ÚÊH™]\›‚ˆ˜[›İH]›İ›™\™Xİœ›İˆ˜[Û[YS\ÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHËœÜÚ][Û‹™[U[YBˆ˜[Û[YSZ[]\ÈH
Û[YS\ÈÈŒÌ
KÒ[

B‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈKKŒLÍLH8 %STÔÒUSÓˆÕUQÖHU“Õ
]]Û›Û[İ\Ë™YXİ]™JK‚ˆËÈHÜÚ][Û‰ÜÈ^]XÚš\]YHØ\ÈÙ[YÈ]ÈS•–H[™Kˆ\™HBˆËÈÚ\™Y™YXİ]™Hœ˜Z[œÈ
›ÜØ\™İ]ÛÛYS[Ù[
È[ÛY[[T™YXİÜRH
ÂˆËÈÛÛXİ]™R[[YÙ[˜ÙJHXÚYHÚXÚ^]XÚš\]YH™\İš]ÈBˆËÈÚÙ[‰ÜÈU‘HÚYÛ˜]\™H[™]›İËœÜÚ][Û‹˜Y[™Ó[ÙHİØ\™]8 %ˆËÈ][™ÈHÛÛ™š\›YY[›™\ˆ[ˆÈİ][™ÈHİ[\‹ˆÛÙ\Ú\HÛ›N‚ˆËÈÛÛšXİ[Û‹YØ]Y
ÈÛÛÛİÛ‰Ù›È™]Ë\™LMIH›ÛÜˆ[İXÚYˆËÈ˜Z[[Ü[‹ˆÛˆ]›İ]]]]\ÈËœÜÚ][Û‹˜Y[™Ó[ÙKÛÈÙH™KY\š]™BˆËÈÜÚ][Û•˜YU\H™[İÈÛÈ[ÙTÜXÚYšXÑ^]È›İ]\ÈH‘UÈİ[K‚ˆHÂˆ˜[ˆHÛÛK›Y™XŞXÛX›İ™[™Ú[™K’[ÜÚ][Û”]›İ\˜š]\‹™]˜[X]JˆÈHË›İH›İXZÔ›İHËœÜÚ][Û‹œXZÑØZ[”İÛ[YS\ÈHÛ[YS\Ëˆ
BˆYˆ
‹œ]›İY
HÂˆÜÚ][Û•˜YU\HHÚ[ˆ
ËœÜÚ][Û‹˜Y[™Ó[ÙK\\˜Ø\ÙJ
JHÂˆ”‘TĞSWÔÓ’TH‹“RPÔ“×ĞĞTˆOˆ[ÙT›İ]\‹•˜YU\K‘”‘TÒÓUSÒˆ“SÓQS•SWÔÕÒS‘ÈˆOˆ[ÙT›İ]\‹•˜YU\K”‘PRÓÕUĞÓÓ•S•PUSÓ‚ˆ”‘U’USˆOˆ[ÙT›İ]\‹•˜YU\K”‘U‘T”ĞSÔ‘PÓRSBˆ•ÒSWÑ“ÓÕÈˆOˆ[ÙT›İ]\‹•˜YU\K•ÒSWĞPĞÕSUSUSÓ‚ˆ’S”ÒQT—ÔÒT’ÈˆOˆ[ÙT›İ]\‹•˜YU\K’S”ÒQT—ÔÒT’ÂˆÓÔWÕQHˆOˆ[ÙT›İ]\‹•˜YU\KÓÔWÕQBˆ“SÓÓ”ÒÕˆOˆ[ÙT›İ]\‹•˜YU\K‘ÔQPUSÓ‚ˆ”STÔÓ’TTˆˆOˆ[ÙT›İ]\‹•˜YU\K”ÑS•SQS•ÒQÓ’USÓ‚ˆ”ÕS‘T‘‹ÖPÓPÈ‹“QWĞÒTˆOˆ[ÙT›İ]\‹•˜YU\K•‘S‘ÔSPÒÂˆ[ÙHOˆÜÚ][Û•˜YU\BˆBˆYÙÊ—QÑQU“Õ	İËœŞ[X›ÛNˆ	Ü‹™œ›ÛS[Ù_WLŒNL‰Ü‹Ó[Ù_H
š]	È‰KŒ™ˆ‹™›Ü›X]
‹œØÛÜ™J_HœÈ	È‰KŒ™ˆ‹™›Ü›X]
‹š[˜İ[X™[ØÛÜ™J_JH‹Ë›Z[
BˆBˆHØ]Ú
Îˆ›İØX›JHÈÊˆ˜Z[[Ü[ˆÙY\[Hİ[H
‹ÈBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈÑSÔSRVUSÓˆRH
^Y\ˆ
BˆËÈˆËÈ[[YÙ[^]İ˜]YŞH]‚ˆËÈHZÙ\È›Ùš]È›ÙÜ™\ÜÚ]™[H
Ú[šÈÙ[[™ÊBˆËÈH]XİÈ[ÛY[[H^]\İ[Û‚ˆËÈHX\›œÈœ›ÛH\İÜšXØ[İ]ÛÛY\ÂˆËÈH™]™[È	H[œÈÚ]›İ[™ÈØZ[™Y‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ˜[Ù[ÜÚYÛ˜[HHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK™]˜[X]JˆÈHËˆİ\œ™[›İH›İˆÛ[YSZ[]\ÈHÛ[YSZ[]\Ëˆ[TšXÙHHËœÜÚ][Û‹™[TšXÙKˆÜÚ][Û”Ú^™TÛÛHËœÜÚ][Û‹˜ÛÜİÛÛËÈKKŒLÍÈ8 %™X[Ú^™Bˆ
BˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹”Ù[ÜRH\œ›Üˆ	ÙK›Y\ÜØYÙ_HŠBˆ[ˆBˆˆËÈ^Xİ]HÚ[šÈÙ[ÈÜˆ\™Ù[^]Èœ›ÛHÙ[Ü[Z^˜][ÛRBˆYˆ
Ù[ÜÚYÛ˜[OH[	‰ˆÙ[ÜÚYÛ˜[œÙ[İˆ	‰ˆˆÙ[ÜÚYÛ˜[\™Ù[˜ŞHOHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK‘^]\™Ù[˜ŞK““Ó‘JHÂˆˆ˜[İ˜]YŞHHÙ[ÜÚYÛ˜[œİ˜]YŞBˆ˜[\™Ù[˜ŞHHÙ[ÜÚYÛ˜[\™Ù[˜ŞBˆˆËÈ›ÜˆÚ[šÈÙ[ËØ[İ[]HXİX[[[İ[ÈÙ[ˆ˜[\ĞÚ[šÔÙ[Hİ˜]YŞH[ˆ\İÙŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK‘^]İ˜]YŞKÒS’×ÌKˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK‘^]İ˜]YŞKÒS’×ÍLˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK‘^]İ˜]YŞKÒS’×ÍÍKˆ
BˆˆYˆ
\ĞÚ[šÔÙ[
HÂˆËÈÚ[šÈÙ[H\X[ÜÚ][Ûˆ^]ˆ˜[Ú[šÔİHÙ[ÜÚYÛ˜[œÙ[İÈLŒˆ˜[Ù[[[İ[HËœÜÚ][Û‹œ]UÚÙ[ˆ
ˆÚ[šÔİˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'äâˆÔÑSÓÔÒS’×H	İËœŞ[X›ÛHˆ
Âˆ‰Üİ˜]YŞK™[[Úš_H	Üİ˜]YŞK›X™[HÙ[IÜÙ[ÜÚYÛ˜[œÙ[İÒ[

_IHˆ
Âˆœ›IÜ›İÒ[

_IHXZÏIÜÙ[ÜÚYÛ˜[œXZÔ›İÒ[

_IHˆ
Âˆ›ØÚÙYIÜÙ[ÜÚYÛ˜[›ØÚÙY›Ùš]ÛÛTÓÓŠBˆˆËÈ^Xİ]H\X[Ù[ˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËˆÙ[\˜Ù[YÙHHÚ[šÔİˆ™X\ÛÛˆH–ÔÑSÓÔH	Üİ˜]YŞK›X™[Nˆ	ÜÙ[ÜÚYÛ˜[œ™X\ÛÛŸH‹ˆØ[]HØ[]ˆØ[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆˆËÈ™XÛÜ™Ú[šÈÛ›HY\ˆÛÛ™š\›YYØ[›ÛšXØ[Û]™H]]][Û‹‚ˆYˆ
\X[™XÙZ\M‹˜\YY
HÂˆ˜[›Ùš]ÛÛH
ËœÜÚ][Û‹˜ÛÜİÛÛ
ˆÚ[šÔİ
H
ˆ
›İÈLŒ
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRKœ™XÛÜ™Ú[šÔÙ[
ˆË›Z[ËœÜÚ][Û‹˜ÛÜİÛÛ
ˆÚ[šÔİ›İ›Ùš]ÛÛˆ
BˆH[ÙHÂˆHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÔÑSÓÔŠHHØ]Ú
Îˆ›İØX›JHßBˆBˆˆH[ÙHYˆ
\™Ù[˜ŞH[ˆ\İÙŠˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK‘^]\™Ù[˜ŞK’QÒˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK‘^]\™Ù[˜ŞKÔ’UPĞSˆ
JHÂˆËÈ[^]›ÜˆYÚ\™Ù[˜ŞHÚYÛ˜[Âˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¼'ã«ÈÔÑSÓÔVUH	İËœŞ[X›ÛHˆ
Âˆ‰Üİ˜]YŞK™[[Úš_H	Üİ˜]YŞK›X™[H\™Ù[˜ŞOIİ\™Ù[˜ŞK›˜[Y_Hˆ
Âˆœ›IÜ›İÒ[

_IHXZÏIÜÙ[ÜÚYÛ˜[œXZÔ›İÒ[

_IHŠBˆˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH–ÔÑSÓÔH	Üİ˜]YŞK›X™[Nˆ	ÜÙ[ÜÚYÛ˜[œ™X\ÛÛŸH‹ˆØ[]HØ[]ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆ
BˆˆËÈÛÜÙHÜÚ][Ûˆ˜XÚÚ[™ÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù[Ü[Z^˜][ÛRK˜ÛÜÙTÜÚ][ÛŠË›Z[›İ
BˆˆËÈK‹’Vˆ™[X\ÙH^Üİ\™HÛİˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆBˆˆËÈ\]HİÜÜÜÈYˆİYÙÙ\İY
›Üˆ™X\İ\HÜÚ][ÛœÊBˆÙ[ÜÚYÛ˜[œİYÙÙ\İYİÜÜÜÏË›]È™]ÔİÜO‚ˆYˆ
ËœÜÚ][Û‹š\Õ™X\İ\TÜÚ][Ûˆ	‰ˆ™]ÔİÜˆËœÜÚ][Û‹™X\İ\TİÜÜÜÊHÂˆËœÜÚ][Û‹™X\İ\TİÜÜÜÈH™]ÔİÜˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹¼'å$ˆÔÑSÓÔH	İËœŞ[X›ÛHİÜ[İ™YÈ
ÉÛ™]ÔİÜÒ[

_IHŠBˆBˆBˆBˆˆËÈÙ][ÙK\ÜXÚYšXÈ^]™XÛÛ[Y[™][Û‚ˆ˜[^]™XÈHHÂˆ[ÙTÜXÚYšXÑ^]Ë™Ù]^]™XÛÛ[Y[™][ÛŠËÜÚ][Û•˜YU\K›İÛ[YS\ÊBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹“[ÙQ^]\œ›Üˆ	ÙK›Y\ÜØYÙ_HŠBˆ[ˆBˆˆËÈÙÈ\™Ù[^]™XÛÛ[Y[™][ÛœÂˆYˆ
^]™XÈOH[	‰ˆ^]™XËœÚİ[^]	‰ˆˆ^]™XË\™Ù[˜ŞH[ˆ\İÙŠ[ÙTÜXÚYšXÑ^]Ë‘^]\™Ù[˜ŞK’SSQQPUK[ÙTÜXÚYšXÑ^]Ë‘^]\™Ù[˜ŞK•T‘ÑS•
JHÂˆ[ÙTÜXÚYšXÑ^]Ë›ÙÑ^]™XÛÛ[Y[™][ÛŠËÜÚ][Û•˜YU\K^]™XÊBˆBˆˆYˆ
XØ”İ]Kš\Ò[Y
HÂˆ^Xİ]Ü‹›X^X™PXİÚ]XÚ\Ú[ÛŠˆÈHËˆXÚ\Ú[ÛˆHXÚ\Ú[Û‹ˆØ[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆØ[]HØ[]ˆ\İÛ\ÈH\İİXØÙ\ÜÙ[Û\ËˆÜ[”ÜÚ][ÛÛİ[Hİ]\Ë›Ü[”ÜÚ][ÛÛİ[ˆİ[^Üİ\™TÛÛHİ]\Ëİ[^Üİ\™TÛÛˆ[ÙPÛÛ™šYÈH[ÙPÛÛ™‹ˆØ[]İ[˜Y\ÈHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K›İÙ\šXÙKØ[]X[˜YÙ\‚ˆËœİ]OË˜[YOËİ[˜Y\ÈÎˆˆHØ]Ú
Îˆ^Ù\[ÛŠHÈKˆ
BˆˆËÈÙÈ]ÙIÜ™H[Ûš]Üš[™È\š[™È]\ÙBˆYˆ
Ø”İ]Kš\Ô]\ÙY
HÂˆYÙÊ¸£î]\ÙY][Ûš]Üš[™È	İËœŞ[X›ÛHÜÚ][Ûˆ‹Z[
BˆBˆBˆBˆˆYˆ
Ø”İ]Kš\Ò[Y
HÂˆYˆ
Z[OHÙ™Ë˜Xİ]™UÚÙ[ŠHYÙÊ¼'æäHSQˆ	ØØ”İ]Kš[™X\ÛÛŸH‹Z[
BˆH[ÙHÂˆYˆ
Z[OHÙ™Ë˜Xİ]™UÚÙ[ŠHYÙÊ¸£îĞˆ	ØØ”İ]Kœ]\ÙT™[XZ[š[™ÔÙXÜß\È‹Z[
BˆB‚ˆËÈ[˜ÛYH™X\İ\HY\ˆ[™ØØ[[™È[ÙH[ˆİ]\ÈÙÂˆ˜[ÛÛÙÈHØ[]X[˜YÙ\‹›\İÛ›İÛ”ÛÛšXÙBˆ˜[œÓÙÈH™X\İ\SX[˜YÙ\‹™X\İ\TÛÛ
ˆÛÛÙÂˆ˜[Y\“ÙÈHØØ[[™Ó[ÙK˜Xİ]™UY\ŠœÓÙÊBˆ˜[Y\”İˆHYˆ
Y\“ÙÈOHØØ[[™Ó[ÙK•Y\‹“RPÔ“ÊHˆ	İY\“ÙËšXÛÛŸIİY\“ÙË›X™[Hˆ[ÙHˆ‚ˆ˜[œÔİˆHYˆ
™X\İ\SX[˜YÙ\‹™X\İ\TÛÛˆŒJBˆˆ<'ãé‰Õ™X\İ\SX[˜YÙ\‹™X\İ\TÛÛ™›]
Ê_x¥ãˆˆ[ÙHˆ‚ˆYÙÊˆ‰İËœŞ[X›ÛœY[™

_H	Ü™\İ[œ\ÙKœY[™
N
_Hˆ
ÂˆœÚYÏIÜ™\İ[œÚYÛ˜[œY[™
N
_Hˆ
Âˆ™[OIÜ™\İ[™[TØÛÜ™KÒ[

_H^]IÜ™\İ[™^]ØÛÜ™KÒ[

_Hˆ
Âˆ›ÛIÜ™\İ[›Y]K›ÛØÛÜ™KÒ[

_H™\ÜÏIÜ™\İ[›Y]Kœ™\ÜÔØÛÜ™KÒ[

_Hˆ
ÂˆY\”İˆ
ÈœÔİ‹ˆZ[ˆ
B‚ˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆËÈKKMÈ8 %Ü\˜]ÜˆšXYÙH›İ[™Îˆ\ÈØ]ÚØ\ÈÒSS•ˆËÈ™Y›Ü™KˆYÙÊ
HÛ›HÜš]\ÈÈİ]\Ë›ÙÜÈ
RHY™™\ŠNÂˆËÈ^Ù\[ÛœÈ™]™\ˆ™XXÚYÙØØ]Üˆ\œ›Ü“ÙÙÙ\‹ˆY‚ˆËÈ›ØÙ\ÜÕÚÙ[ŞXÛH™]È[]Ú\™H[ˆ]ÈL[[™H›ÙBˆËÈ
Ş[\Z\ˆ]Ø[™HŞ[˜ËŞ[X›ÛXĞÛÛ^™Yœ™\ÚˆËÈ›Û™[™Ğİ\™U˜XÚÙ\‹ŒËÚ]ÛÚ[ˆ[™K8 )ŠHHÚÛBˆËÈ\[[™HYYÚ[[H[™HÜ\˜]ÜˆØ]È˜Y\ÈÚ]ˆËÈ›ÈĞQ‘UKÕŒËÓS‘WÑUS›Ü™[œÚXÈÙÜË‚ˆËÂˆËÈš\K]Üš]Nˆ[‹X\ÙÈ
^\İ[™ÊK\œ›Ü“ÙÙÙ\‹™\œ›Üˆ
ÛÂˆËÈ][™È[ˆÙØØ]S‘HPUH\œ›Ü—ÛÙÜÈÔS]HİÜ™JKˆËÈ[™›Ü™[œÚXÓÙÙÙ\‹™Ø]H
ÛÈ]ÚİÜÈ[ˆHØ[YH›Ü™[œÚXÂˆËÈÜ™\İ™X[H\ÈH™\İÙˆH\[[™JKˆBˆËÈ[İÏY˜[ÙXØ]HX\šÜÈHŞXÛH\È˜Z[YÛÈ[›™[ˆËÈÛİ[\œÈÛ‰İİX›KXÛİ[]\È›XYH]›İYÚ‹‚ˆİ]\ËÚÙ[œÖÛZ[OË›\İ\œ›ÜˆHK›Y\ÜØYÙHÎˆ[šÛ›İÛˆ‚ˆYÙÊ‘\œ›ÜˆÉZ[Nˆ	ÙK›Y\ÜØYÙ_H‹Z[
BˆHÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠˆ›İÙ\šXÙH‹ˆ¼'éëÔĞĞS—ĞĞ—HVÑTSÓˆZ[IÛZ[ZÙJ
_HŞ[OIÜİ]\ËÚÙ[œÖÛZ[OËœŞ[X›ÛÎˆÈŸHÛÏIÙKš˜]˜PÛ\ÜËœÚ[\S˜[Y_H\ÙÏIÙK›Y\ÜØYÙ_H‹ˆKˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆHÂˆËÈKK8 %\[™HÙ™™[™[™ÈİXÚËYœ˜[YHÈBˆËÈ›Ü™[œÚXÈØ]H™X\ÛÛˆÛÈHÜ\˜]ÜˆØ[ˆÙYHÒPÒˆËÈ[™H™]ÈH^Ù\[Ûˆ[ˆHØ[YH[\\ÈBˆËÈ\[[™H[›™[ˆHSÓĞ‘H[ˆS’TÈÈ[™^LBˆËÈÚİÙY\\™H8 %Ú]İ]Hœ˜[YH[™›ÈÙHÛİ[‰İˆËÈØØ[\ÙH]ˆ˜[È˜XÚÈÜ˜XÙY[HYˆ›ÈPUK\XÚØYÙBˆËÈœ˜[YH\È™\Ù[‚ˆ˜[İ\‘œ˜[YHHKœİXÚÕ˜XÙOË™š\œİÜ“[È]˜Û\ÜÓ˜[YOËœİ\ÕÚ]
˜ÛÛK›Y™XŞXÛX›İŠHOHYHBˆ˜[œ˜[YTİY™š^HYˆ
İ\‘œ˜[YHOH[
HÂˆˆ]	Ûİ\‘œ˜[YK˜Û\ÜÓ˜[YKœİXœİš[™ĞY\“\İ
	Ë‰Ê_K‰Ûİ\‘œ˜[YK›Y]Ù˜[Y_N‰Ûİ\‘œ˜[YK›[™S[X™\ŸH‚ˆH[ÙHˆ‚ˆ›Ü™[œÚXÓÙÙÙ\‹™Ø]Jˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK”ĞĞS—ĞĞ‹ˆİ]\ËÚÙ[œÖÛZ[OËœŞ[X›ÛÎˆZ[ZÙJŠKˆ[İÈH˜[ÙKˆ™X\ÛÛˆH‘VÑTSÓˆÛÏIÙKš˜]˜PÛ\ÜËœÚ[\S˜[Y_H\ÙÏIÙK›Y\ÜØYÙOËZÙJLŒ
_Iœ˜[YTİY™š^‹ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆBˆBˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒˆQS‘ÈVQTˆ‘PQS‘TÔÈ“QÂˆËÈ™]™[È˜Y[™È™Y›Ü™H[RH^Y\œÈ\™H[š]X[^™YˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆ›Û][Bˆš]˜]H˜\ˆ[˜Y[™Ó^Y\œÔ™XYHH˜[ÙBˆˆËÈŒÔ’UPĞSˆ›YÈÈ[œİ\™H˜Y[™È[Ù\ÈÛ›H[š]ÓÑH\ˆÙ\ÜÚ[Û‚ˆËÈ™]š[İ\ÛH\ÙHÙ\™H™Z[™È™Z[š]]™\HÛÜØ]\Ú[™Èİ]H™\Ù]ÈBˆ›Û][Bˆš]˜]H˜\ˆ˜Y[™Ó[Ù\Ò[š]X[^™YH˜[ÙBˆˆš]˜]H[ˆ[š]˜Y[™Ó[Ù\ÊÙ™Îˆ›İÛÛ™šYÊHÂˆËÈŒÔ’UPĞSˆİX\™YØZ[œİ™KZ[š]X[^˜][Û‚ˆYˆ
˜Y[™Ó[Ù\Ò[š]X[^™Y
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹¸¦¨;î#È[š]˜Y[™Ó[Ù\Ê
HØ[YYØZ[ˆH“ĞÒÑQ
[™XYH[š]X[^™Y
HŠBˆ™]\›‚ˆBˆˆËÈ™\Ù]™XY[™\ÜÈ›YÈ]İ\ˆ[˜Y[™Ó^Y\œÔ™XYHH˜[ÙBˆ˜\ˆ[š]Ûİ[Hˆ˜\ˆ˜Z[Ûİ[Hˆˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹’S’UPSV’S‘ÈQS‘ÈSÑTÈ
Ó‘KUSQHÓ“JHŠBˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹¸¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dŠBˆˆËÈØ\ÚÙ[™\˜][ÛˆRH
™X\İ\H[ÙJBˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRKœÙ]˜Y[™Ó[ÙJÙ™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹Ø\ÚÙ[™\˜][ÛRH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈÚ]ÛÚ[ˆ˜Y\‚ˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RKš[š]
Ù™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹”Ú]ÛÚ[•˜Y\RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈÚ]ÛÚ[ˆ^™\ÜÂˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[‘^™\ÜËš[š]
Ù™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹”Ú]ÛÚ[‘^™\ÜÈ[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆB‚ˆËÈ8¦(;î#ÈHX[š\[]YˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“X[š\[]Y˜Y\RKš[š]
Ù™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹“X[š\[]Y˜Y\RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈKŒ‹ŒLˆ]X[]H˜Y\ˆH›Ù™\ÜÚ[Û˜[ZYXØ\^Y\‚ˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RKš[š]
Ù™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹”]X[]U˜Y\RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈ\[\‚ˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘\[\RKš[š]
Ù™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹‘\[\RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈÛÛ[˜H\˜š]˜YÙBˆHÂˆ˜[™X\İ\P˜[[˜ÙHHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËØ\ÚÙ[™\˜][ÛRK™Ù]™X\İ\P˜[[˜ÙJÙ™Ëœ\\“[ÙJBˆHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K•™X\İ\SÜÜ[š]Q[™Ú[™KœÙ][˜X›Y
YJHHØ]Ú
Îˆ›İØX›JHßHËÈKŒÌÎYš\ÛÜH™X\İ\H\Ş[Y[[\‚ˆ˜[ÛÛšXÙHHØ[]X[˜YÙ\‹›\İÛ›İÛ”ÛÛšXÙKZÙRYˆÈ]ˆHÎˆMLŒˆ˜[™X\İ\U\ÙH™X\İ\P˜[[˜ÙH
ˆÛÛšXÙBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”ÛÛ[˜P\˜RKš[š]
Ù™Ëœ\\“[ÙK™X\İ\U\Ù
Bˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹”ÛÛ[˜P\˜RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈ^Y\ˆ˜[œÚ][ÛˆX[˜YÙ\‚ˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“^Y\•˜[œÚ][Û“X[˜YÙ\‹š[š]

Bˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹“^Y\•˜[œÚ][Û“X[˜YÙ\ˆ[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈ›YHÚ\˜Y\‚ˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RKš[š]
Ù™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹›YPÚ\˜Y\RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈKŒ‹ˆ[ÛÛœÚİ˜Y\ˆHØ\ÈZ\ÜÚ[™ÈBˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKš[š]X[^™JÙ™Ëœ\\“[ÙJBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹“[ÛÛœÚİ˜Y\RH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈ›ZYX\›š[™ÈRBˆHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRKš[š]

BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRKš[š]X\šÙ]Ô™YœÊ\ÊHËÈKŒˆ™\İÜ™HX\šÙ]È˜YHÛİ[ˆËÈKKŒLŒˆ\œÛÛ˜[]SY[[ÜTİÜ™H8 %\œÚ\İ[˜Z]ËZ[\İÛ™\ËˆËÈÚ]\İÜKˆÚ]İ]\ÈHH[™\œÛÛ˜[]H^Y\ˆ\ÂˆËÈ[[™\ÚXHÛˆ]™\H™\İ\‚ˆHÈ\œÛÛ˜[]SY[[ÜTİÜ™Kš[š]
\ÊHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆËÈKKŒLŒÎˆ™]ÈRH^Y\œÈÚ]\œÚ\İ[İ]K‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™ËRU\İ™]ÛÜšĞRKš[š]
\ÊHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“Ü\˜]Ü‘š[™Ù\œš[RKš[š]
\ÊHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ù\ÜÚ[Û‘YÙPRKš[š]
\ÊHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘^Xİ][ÛÛÜİ™YXİÜRKš[š]
\ÊHHØ]Ú
Îˆ^Ù\[ÛŠHßBˆËÈKKŒLNˆÙ[‹XÚXÚÈ8 %›Ûİ][YH\ÜÙ\[Ûˆ]Ù][˜[ZXÑ›ZYİÜˆËÈ™]\›œÈØ[™H˜[Y\È›Üˆ[›™\œËˆ\È\ÈH\›X[™[™YÜ™\ÜÚ[Û‚ˆËÈİX\™›ÜˆH›Ùš]Y›ÛÜ‹[ØÚÈYÈ]\È™]\›™YÈ[Y\Ë‚ˆËÈHZ\Ë\ÚYÛ™YİÜ
K™Ëˆ™]\›š[™ÈLÍˆ›ÜˆH
ÌÍÍÉHXZÈ[›™\ŠBˆËÈÚ[ØÜ™X[H[ˆHÙÈ‘Q“Ô‘HH›İZÙ\ÈHÚ[™ÛH˜Y˜YK‚ˆHÂˆ˜[\İİÜHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë‘›ZYX\›š[™ĞRK™Ù][˜[ZXÑ›ZYİÜ
ˆ[ÙQY˜][İÜHŒŒˆİ\œ™[›İHLKŒˆXZÔ›İHÍÍËŒˆÛ[YTÙXÛÛ™ÈHŒŒˆ›Û][]HHLŒˆ
BˆYˆ
\İİÜHŒ\İİÜˆÍÍËŒ
HÂˆ\œ›Ü“ÙÙÙ\‹˜Ü˜\Ú
›İÙ\šXÙH‹ˆ¼'æª“Ñ’UQ“ÓÔˆ‘QÔ‘TÔÒSÓˆU“ÓÕˆÙ][˜[ZXÑ›ZYİÜ
XZÏLÍÍÉK›İÏLLIJH™]\›™Y	\İİÜ8 %UTÕ™HÜÚ]]™H[™HXZËˆ[›™\œÈÚ[›İØÚÈØZ[œËˆ\ÈØ\ÈHQÓÔˆ
ÌL	H8¡¤ˆ
ÍL	HYËˆ‹ˆ[[YQ^Ù\[ÛŠœ›Ùš]Y›ÛÜˆÙ[‹XÚXÚÈ˜Z[YˆİÜI\İİÜŠJBˆH[ÙHÂˆ\œ›Ü“ÙÙÙ\‹š[™›Ê›İÙ\šXÙH‹ˆ¸§!H›Ùš]Y›ÛÜˆÙ[‹XÚXÚÎˆXZÏLÍÍÉH›İÏLLIH8¡¤ˆØÚÈ]
Éİ\İİÜÒ[

_IH
^]š\™\ÈÛÜœ™XİJHŠBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹”›Ùš]Y›ÛÜˆÙ[‹XÚXÚÈÚÚ\Yˆ	ÙK›Y\ÜØYÙ_HŠBˆBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹‘›ZYX\›š[™ĞRH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈ\]Hš[˜[XÚ\Ú[Û‘Ø]H[ÙBˆš[˜[XÚ\Ú[Û‘Ø]KœÙ][ÙQ›Ü•™]ÊÙ™Ëœ\\“[ÙJBˆˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈŒˆ[š]X[^™HÛØ˜[˜YT™YÚ\İHœ›ÛHÛÛ™šYÈØ]Ú\İˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆHÂˆÛØ˜[˜YT™YÚ\İKš[š]
Ù™ËØ]Ú\İÓÓ‘’QÈŠBˆ[š]Ûİ[
ÊÂˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ˜Z[Ûİ[
ÊÂˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹‘ÛØ˜[˜YT™YÚ\İH[š]RSQˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆˆËÈÙ]™XY[™\ÜÈ›YÈHÛ›HYˆÜš]XØ[^Y\œÈ[š]X[^™YˆËÈÜš]XØ[ˆ™X\İ\KÚ]ÛÚ[‹›YPÚ\›ZYX\›š[™ËÛØ˜[˜YT™YÚ\İBˆ[˜Y[™Ó^Y\œÔ™XYHH˜Z[Ûİ[OHˆˆYˆ
[˜Y[™Ó^Y\œÔ™XYJHÂˆYÙÊ¸§!H[	[š]Ûİ[˜Y[™È^Y\œÈ[š]X[^™YŠBˆH[ÙHÂˆYÙÊ¸¦¨;î#È˜Y[™È^Y\œÎˆ	[š]Ûİ[ÒË	˜Z[Ûİ[RSQH˜Y[™ÈX^H™H[Z]YŠBˆBˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈ’PÑHSPÒÈSTˆH^˜XİYÈ™YXÙH›İÛÜÛÛ\^]BˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆÊŠ‚ˆ
ˆHÈÙ]šXÙH]Hœ›ÛH˜[˜XÚÈÛİ\˜Ù\È
š\™^YK[\™[ŠBˆ
ˆÚ[ˆ^ØÜ™Y[™\ˆÙ\Û‰İ]™HHZ\‹‚ˆ
ˆ™]\›œÈYHYˆ]HØ\È™]ÚYİXØÙ\ÜÙ[K‚ˆ
‹ÂˆËÈKKŒÈ8 %œ›ØYØ\İHİXØÙ\ÜÙ[K\™\ÛÛ™Y˜[˜XÚÈšXÙHÈ]™\BˆËÈİX‹]˜Y\ˆ]XİX[HÛÈ\ÈZ[ˆ™]š[İ\ÛHQ˜[˜XÚÔšXÙQ]BˆËÈÛ›H\]YË›\İšXÙKÛÈİX‹]˜Y\ˆ›İÜÈ
\Üˆ]X[]JHİ[ˆËÈÚİÙY¸ %İ[H0­È›È]™H™YYM›Hˆ™XØ]\ÙHZ\ˆ\‹\ÜÚ][Û‚ˆËÈ\İÙY[”šXÙHÈ\İšXÙU\]S\È™]™\ˆÛİİXÚY]™[ˆÚ[ˆš\™^YBˆËÈÜˆ[\™[ˆYœ™\Ú]KˆXXÚ\]S]™TšXÙH\ÈH›Ë[Ü›Ü‚ˆËÈ˜Y\œÈ]Û‰İÛHZ[ÛÈ]	ÜÈØY™HÈ˜[ˆİ]›[™K‚ˆš]˜]H[ˆœ›ØYØ\İ˜[˜XÚÔšXÙJZ[ˆİš[™ËšXÙU\ÙˆİX›JHÂˆYˆ
šXÙU\ÙH
H™]\›‚ˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK\]S]™TšXÙJZ[šXÙU\Ù
HHØ]Ú
Îˆ›İØX›JHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK\]S]™TšXÙJZ[šXÙU\Ù
HHØ]Ú
Îˆ›İØX›JHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK\]S]™TšXÙJZ[šXÙU\Ù
HHØ]Ú
Îˆ›İØX›JHßBˆHÈÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK\]S]™TšXÙJZ[šXÙU\Ù
HHØ]Ú
Îˆ›İØX›JHßBˆËÈØ\ÚÙ[™\˜][ÛRHÈX[š\[]Y˜Y\RHÈÚ]ÛÚ[‘^™\ÜÈÛ‰İˆËÈ^ÜÙH\]S]™TšXÙH8 %^H\š]™H\İÙY[”šXÙH[œÚYHÚXÚÑ^]‚ˆB‚ˆËÈKKˆ8 %VUĞQ‘UH‘U[\‹‚ˆËÈKKÈ8 %^[™Yœ›ÛHHÚ[\HLŒ	H\™Y›ÛÜ‹[Û›HÚXÚÈÈH[ˆËÈ[YØ][ÛˆÈXXÚY[YHİX‹]˜Y\‰ÜÈØ[›ÛšXØ[ÚXÚÑ^]

HÚ]BˆËÈœ™\ÚK\™Yœ™\ÚY˜[˜XÚÈšXÙKˆ\ÈYX[œÈ˜Z[^]Ë›Ùš]Y›ÛÜ‚ˆËÈØÚÜË\X[]ZÙH[™ÜËYÈ]Xİ[ÛˆS‘H\™Y›ÛÜˆÓ[š\™BˆËÈÛˆŞXÛ\ÈÚ\™HHš[X\H^ØÜ™Y[™\ˆ™YY\ÈİÛˆ[™ˆËÈ›ØÙ\ÜÕÚÙ[ŞXÛHÛİ[İ\Ú\ÙH™]\›ˆX\›KˆÛİ™\œÈÚ]ÛÚ[•˜Y\RBˆËÈ[™[ÛÛœÚİ˜Y\RH
HY[YH[™\ÊKˆHš[˜[\™Y›ÛÜˆ˜[˜XÚÈ[œÂˆËÈYˆ™Z]\ˆİX‹]˜Y\ˆ\ÈHÜÚ][Ûˆ™YÚ\İ\™Y
Üœ[™YËœÜÚ][ÛŠK‚ˆÊŠ‚ˆ
ˆKKÎ8 %[š]™\œØ[ÓØY™]K[™]İÙY\‚ˆ
‚ˆ
ˆ[œÈÛ˜ÙH\ˆ›İÛÜXÚÈXÜ›ÜÜÈSÜ[ˆÜÚ][ÛœË[›ÚÚ[™Âˆ
ˆ[‘˜[˜XÚÔØY™]Q^]

H›ÜˆXXÚˆH[YØ]H[˜İ[Ûˆ\È[™XYBˆ
ˆY[\İ[
šY\È[™HÚXÚÑ^]YˆHÚÙ[ˆ\È™YÚ\İ\™YÚ]ˆ
ˆÚ]ÛÚ[•˜Y\RKÓ[ÛÛœÚİ˜Y\RK˜[È˜XÚÈÈHLŒ	H\™›ÛÜ‚ˆ
ˆ›ÜˆÜœ[œÈÚÜÙH˜Y[™Ó[ÙHYÈ\È[Ù[\JKˆHİÙY\^\İÂˆ
ˆÜXÚYšXØ[HÈ™\ØİYHÜÚ][ÛœÈ]H[™K\ÜXÚYšXÈ^]œ˜[˜Ú\Âˆ
ˆ[ˆ›ØÙ\ÜÕÚÙ[ŞXÛH
ÚXÚ›İ™\]Z\™HHÜ[]Y˜Y[™Ó[ÙBˆ
ˆšY[
HÚ[[HÚÚ\8 %Ü\˜]ÜˆØÜ™Y[œÚİ[ˆKKÍÈÚİÙYš]™Bˆ
ˆ\\ˆÜÚ][ÛœÈÚ][™ÈLN\˜Ù[YÙHÚ[È\İHÛÛ™šYİ\™Yˆ
ˆLŒ	HÓ™XØ]\ÙHÙˆ\ÈØ\‚ˆ
‚ˆ
ˆÙ\\ÈHÙ\\˜]HÜ[]™[Y]Ù
›İ[›[™Y[È›İÛÜ
HÛÂˆ
ˆH]XÛÙH]™\È[ˆ]ÈİÛˆÛİ[™™]™\ˆ™X][œÈ›İÛÜ	ÜÂˆ
ˆ•“HĞˆY]Ù\Ú^™HYÙ]ˆ™]\›œÈ[[YYX][HYˆH›İ\Âˆ
ˆ›İ[›š[™Ë‚ˆ
‹ÂˆÊŠ‚ˆ
ˆKŒˆ0©ĞÈ8 %ÕÑQTT‘PQS‘K‚ˆ
‚ˆ
ˆHHÛ˜\ÚİÚİÙYX^›İŞXÛ\ÈÙˆ‹NNHÙXÛÛ™Ëˆ›Ûİˆ
ˆØ]\ÙNˆ\ÈÛÜ˜[ˆH\‹\ÜÚ][Ûˆ›İšY\‹RKÓÈØ[ˆ
ˆ
[‘˜[˜XÚÔØY™]Q^]8¡¤ˆ^Xİ]Ü‹™Ù]XİX[šXÙTX›XØ
Bˆ
ˆŞ[˜Ú›Û›İ\ÛHİ™\ˆSÜ[ˆÜÚ][ÛœËˆÚ]MÈÜÚ][ÛœÈ[‚ˆ
ˆH™YÚ\İH[™›İšY\œÈYÜ˜YY
š\™^YHK[]\ÈKˆ
ˆ^ØÜ™Y[™\ˆ^
KXXÚ]˜[Ûİ[\›ˆMKLŒÈÛˆ™]ÛÜšÂˆ
ˆ[Y[İ]È[™HÚÛHİÙY\İ\Ü[™YH›İÛÜ‚ˆ
‚ˆ
ˆ\™Xİ]™Nˆš[™]šYX[^]]˜[X][ÛˆL\Èˆ
\‹\ÜÂˆ
ˆXY[™H[™›Ü˜ÙYHÚÜXÚ\˜İZ][™ÈÈH™^ÜÚ][ÛˆY‚ˆ
ˆH™]š[İ\ÈÛ™Hİ™\œ˜[ŠH[™™[\™H[š]™\œØ[ÓİÙY\‚ˆ
ˆÌ\Ë\™[Y\™Ù[˜ŞHİÙY\XY[™NˆL\È‹‚ˆ
‚ˆ
ˆ\ÈØ[XÛØÚÈİX\™\Y\ÈHİÙY\YÙ]ˆY\ˆXXÚˆ
ˆÜÚ][Û‹Yˆ[\ÙYHT‘ÑPQS‘WÓTÈÙHQ‘TˆBˆ
ˆ™[XZ[™\ˆ[™ÛÛ[YHÛˆH™^ŞXÛKˆ›ÈÜÚ][Ûˆ]™\‚ˆ
ˆİ\Ü[™ÈH›İÛÜ\İHXY[™K‚ˆ
‹Âˆš]˜]H[ˆ[•[š]™\œØ[ÛØY™]S™]İÙY\
Ù™Îˆ›İÛÛ™šYËØ[]ˆÛÛ[˜UØ[]ÊHÂˆYˆ
\İ]\Ëœ[›š[™ÊH™]\›‚ˆ˜[T‘ÑPQS‘WÓTÈHWÌˆ˜[ÓÑ•ÑPQS‘WÓTÈH×Ìˆ˜[T—ÔÔÒUSÓ—ÑSTÑQÕĞT“—ÓTÈHLˆ˜[İÙY\İ\Y]HŞ\İ[K˜İ\œ™[[YSZ[\Ê
Bˆ˜\ˆÜÚ][ÛœÔÙY[ˆHˆ˜\ˆÜÚ][ÛœÑ]˜[X]YHˆ˜\ˆÜÚ][ÛœÑY™\œ™YHˆHÂˆËÈÛ˜\ÚİHÚÙ[ˆX\ÛÈÙH™]™\ˆ]\˜]HÚ[H]]]]\ÂˆËÈ[™\ˆ\È
^Xİ]Ü‹œ™\]Y\İÙ[Ù]È\ÓÜ[Y˜[ÙH[ˆXÙJK‚ˆ˜[Ü[”Û˜\ÚİHØ[›ÛšXØ[^]ÚÙ[”Û˜\ÚİLLŠ
BˆÜÚ][ÛœÔÙY[ˆHÜ[”Û˜\ÚİœÚ^™B‚ˆYˆ
Ü[”Û˜\Úİš\Ñ[\J
JH™]\›‚‚ˆ›Üˆ
È[ˆÜ[”Û˜\Úİ
HÂˆ˜[[\ÙYHŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHİÙY\İ\Y]ˆËÈKŒˆ0©ĞÈ8 %\™İÙY\XY[™Kˆ™^[Û™\ËY™\‚ˆËÈ[™[XZ[š[™ÈÜÚ][ÛœÈÈH™^XÚË‚ˆYˆ
[\ÙYHT‘ÑPQS‘WÓTÊHÂˆ˜[™[XZ[š[™ÈHÜ[”Û˜\ÚİœÚ^™HHÜÚ][ÛœÑ]˜[X]YˆÜÚ][ÛœÑY™\œ™Y
ÏH™[XZ[š[™ÂˆHÂˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê•S’U‘T”ĞSÔÓÔÕÑQTÒT‘ÑPQS‘WÍˆŠBˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ•S’U‘T”ĞSÔÓÔÕÑQTÒT‘ÑPQS‘WÍˆ‹ˆ™[\ÙY\ÏI[\ÙYÜÚ][ÛœÔÙY[IÜÚ][ÛœÔÙY[ˆÜÚ][ÛœÑ]˜[X]YIÜÚ][ÛœÑ]˜[X]YY™\œ™YI™[XZ[š[™ÈYÙ]\ÏIT‘ÑPQS‘WÓTÈ‹ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆœ™XZÂˆBˆ˜[ÜÔİ\HŞ\İ[K˜İ\œ™[[YSZ[\Ê
BˆHÂˆ[‘˜[˜XÚÔØY™]Q^]
ËÙ™ËØ[]
BˆHØ]Ú
Nˆ›İØX›JHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊˆ›İÙ\šXÙH‹ˆ[š]™\œØ[ÓİÙY\\œˆ	İËœŞ[X›ÛNˆ	ÙK›Y\ÜØYÙ_H‚ˆ
BˆBˆÜÚ][ÛœÑ]˜[X]Y
ÊÂˆ˜[ÜÑ[\ÙYHŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHÜÔİ\ˆYˆ
ÜÑ[\ÙYHT—ÔÔÒUSÓ—ÑSTÑQÕĞT“—ÓTÊHÂˆHÂˆ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê•S’U‘T”ĞSÔÓÔÔÒUSÓ—ÔÓÕ×ÍˆŠBˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ•S’U‘T”ĞSÔÓÔÔÒUSÓ—ÔÓÕ×Íˆ‹ˆ›Z[IİË›Z[ZÙJL
_HŞ[OIİËœŞ[X›ÛHÜÑ[\ÙY\ÏIÜÑ[\ÙYİÙY\[\ÙY\ÏI[\ÙY‹ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆBˆËÈÛÙXY[™HYš\ÛÜH8 %İÙY\\Èİ[[›š[™È]\ÂˆËÈ›İÈİ™\ˆYÙ]ˆÛÛ[YH]ÙÈÛÈHÜ\˜]ÜˆÙY\ÂˆËÈH›İ[™\H]™[‚ˆYˆ
[\ÙY[ˆÓÑ•ÑPQS‘WÓTÈ[[T‘ÑPQS‘WÓTÈ	‰‚ˆÜÚ][ÛœÑ]˜[X]YOHÜÚ][ÛœÔÙY[ˆÈŠHÂˆHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê•S’U‘T”ĞSÔÓÔÕÑQTÔÓÑ•ÑPQS‘WÍˆŠHHØ]Ú
Îˆ›İØX›JHßBˆBˆBˆHØ]Ú
Nˆ›İØX›JHÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹[š]™\œØ[ÓİÙY\Ü[]™[ˆ	ÙK›Y\ÜØYÙ_HŠBˆHš[˜[HÂˆ˜[İ[HŞ\İ[K˜İ\œ™[[YSZ[\Ê
HHİÙY\İ\Y]ˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ•S’U‘T”ĞSÔÓÔÕÑQTÔÕSSPT–WÍˆ‹ˆ™[\ÙY\ÏIİ[ÜÚ][ÛœÔÙY[IÜÚ][ÛœÔÙY[ˆÜÚ][ÛœÑ]˜[X]YIÜÚ][ÛœÑ]˜[X]YÜÚ][ÛœÑY™\œ™YIÜÚ][ÛœÑY™\œ™Y\™XY[™S\ÏIT‘ÑPQS‘WÓTÈ‹ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆBˆB‚ˆš]˜]H[ˆ[‘˜[˜XÚÔØY™]Q^]
ÎˆÚÙ[”İ]KÙ™Îˆ›İÛÛ™šYËØ[]ˆÛÛ[˜UØ[]ÊHÂˆHÂˆ˜[šXÙHHHÈ^Xİ]Ü‹™Ù]XİX[šXÙTX›XÊÊHHØ]Ú
Îˆ›İØX›JHÈË›\İšXÙHBˆYˆ
šXÙHHŒËœÜÚ][Û‹™[TšXÙHHŒ
H™]\›‚ˆ˜[Y™™Xİ]™P˜[[˜ÙHHİ]\Ë™Ù]Y™™Xİ]™P˜[[˜ÙJÙ™Ëœ\\“[ÙJB‚ˆËÈKKÎ8 %[Z]TÑK‘VU›Ü™[œÚXÈÛÈH[›™[Ûİ[\‚ˆËÈVU[ˆH\[[™HX[[\š[˜[H™Y›XİÈ™X[]K‚ˆËÈš[ÜˆÈ\Ë›ÈÛÙH][]Ú\™H[ˆH›İ[Z]YˆËÈTÑK‘VUÛÈÜ\˜]Üˆ[\È\œ]X[HÚİÙYVULˆËÈ]™[ˆÚ[ˆX[HÜÚ][ÛœÈÙ\™H™Z[™È]˜[X]Y›Üˆ^]‚ˆ˜[Ü›™\™XİŒÎHÜ[”›Ø[š]Kš[œÜXİÜÚ][ÛŠËœÜÚ][Û‹šXÙK›İÙ\šXÙK™˜[˜XÚ×Ù^]Ü\ÙWÍŒÎÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹[Z]HYJBˆ˜[Ü›HYˆ
Ü›™\™XİŒÎ›ÚÊHÜ›™\™XİŒÎœ›İ[ÙHŒˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹œ\ÙJˆ›Ü™[œÚXÓÙÙÙ\‹”TÑK‘VUÑĞUKˆËœŞ[X›Ûˆœ›I×Ü›Ò[

_IH[ÙOIİËœÜÚ][Û‹˜Y[™Ó[ÙKZÙRYˆÈ]š\Ó›İ›[šÊ
HHÎˆ““Ó‘HŸHØÏIØÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RKš\ÔÜÚ][ÛŠË›Z[
_H\ÏIØÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKš\ÔÜÚ][ÛŠË›Z[
_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßB‚ˆËÈ8¥ 8¥ Ú]ÛÚ[•˜Y\RH[YØ][Ûˆ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RKš\ÔÜÚ][ÛŠË›Z[
JHÂˆ˜[ÚYÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜ÚXÚÑ^]
Ë›Z[šXÙJBˆYˆ
ÚYÈOHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[’Ó
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¼'æè{î#ÈÑSPÒ×ÑVUVÔÒUÓÒS—H	İËœŞ[X›ÛHÚYÛ˜[IÚYÈšXÙOIšXÙH
^ØÜ™Y[™\ˆİÛŠHŠBˆYˆ
ÚYÈOHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑJHÂˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËÙ[\˜Ù[YÙHHŒKˆ™X\ÛÛˆH‘SPÒ×ÔÒUÓÒS—ÔT•PSÕRÑH‹ˆØ[]HØ[]Ø[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÑSPÒ×ÔÒUÓÒSˆŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK›X\šÑš\œİZÙQÛ™JË›Z[
BˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK›Û”\X[Ù[
Ë›Z[ŒJHËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆH[ÙHÂˆ˜[˜”ØÔ™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH‘SPÒ×ÔÒUÓÒS—ÉÜÚYË›˜[Y_H‹ˆØ[]HØ[]Ø[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆ
BˆËÈKKÌˆ’VˆÛX[ˆ\İX‹]˜Y\ˆİ]H[›\ÜÈ™]XX›BˆYˆ
˜”ØÔ™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[šXÙKÚYÊBˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆBˆBˆ™]\›‚ˆBˆB‚ˆËÈ8¥ 8¥ [ÛÛœÚİ˜Y\RH[YØ][Ûˆ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆYˆ
ÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RKš\ÔÜÚ][ÛŠË›Z[
JHÂˆ˜[ÚYÈHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜ÚXÚÑ^]
Ë›Z[šXÙJBˆYˆ
ÚYÈOHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[’Ó
HÂˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¼'æè{î#ÈÑSPÒ×ÑVUVÓSÓÓ”ÒÕH	İËœŞ[X›ÛHÚYÛ˜[IÚYÈšXÙOIšXÙH
^ØÜ™Y[™\ˆİÛŠHŠBˆYˆ
ÚYÈOHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK‘^]ÚYÛ˜[”T•PSÕRÑJHÂˆ˜[\X[İHÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™Ù]\X[Ù[İ
Ë›Z[
Bˆ˜[\X[™XÙZ\MˆH^Xİ]Ü‹œ™\]Y\İ\X[Ù[ÛÛ™š\›YYMŠˆÈHËÙ[\˜Ù[YÙHH\X[İˆ™X\ÛÛˆH‘SPÒ×ÓSÓÓ”ÒÕÔT•PSÕRÑWÉÊ\X[İ
ˆL
KÒ[

_TÕ‹ˆØ[]HØ[]Ø[]˜[[˜ÙHHY™™Xİ]™P˜[[˜ÙKˆ
BˆYˆ
\\X[™XÙZ\M‹˜\YY
HÈHÈ\[[™RX[ÛÛXİÜ‹›X™[[˜Ê“QSQWÔT•PSÓ“ÕĞTQQÍM—ÑSPÒ×ÓSÓÓ”ÒÕŠHHØ]Ú
Îˆ›İØX›JHßNÈ™]\›ˆBˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK›Û”\X[Ù[
Ë›Z[\X[İ
HËÈKKÌBˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKœØ]™TÜÚ][ÛŠÊHËÈKKÌBˆH[ÙHÂˆ˜[˜“\Ô™\İ[H^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH‘SPÒ×ÓSÓÓ”ÒÕÉÜÚYË›˜[Y_H‹ˆØ[]HØ[]Ø[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆ
BˆËÈKKÌˆ’VˆÛX[ˆ\İX‹]˜Y\ˆİ]H[›\ÜÈ™]XX›BˆYˆ
˜“\Ô™\İ[OH^Xİ]Ü‹”Ù[™\İ[‘RSQÔ‘U–PP“JHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK˜ÛÜÙTÜÚ][ÛŠË›Z[šXÙKÚYÊBˆÛÛK›Y™XŞXÛX›İŒË•ŒÑ[™Ú[™SX[˜YÙ\‹›Û”ÜÚ][ÛÛÜÙY
Ë›Z[
BˆBˆBˆ™]\›‚ˆBˆB‚ˆËÈ8¥ 8¥ \İ\™\ÛÜ\™Y›ÛÜˆ
Üœ[™YÜÚ][ÛŠH8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ˆËÈYˆ™Z]\ˆİX‹]˜Y\ˆ\È\ÈZ[™YÚ\İ\™Y
K™Ëˆİ]BˆËÈØ\Û‰İ™ZY˜]YY\ˆ™\İ\
Kİ[š\™HHØ[›ÛšXØ[ˆËÈLŒ	HY[YH\™Y›ÛÜˆÈİÜØ]\İ›ÜXÈ›YY‚ˆ˜[›™\™XİŒÎHÜ[”›Ø[š]Kš[œÜXİÜÚ][ÛŠËœÜÚ][Û‹šXÙK›İÙ\šXÙK™˜[˜XÚ×Ú\™Ù›ÛÜ—ÍŒÎÉİËœŞ[X›ÛKÉİË›Z[ZÙJ
_H‹[Z]HYJBˆYˆ
\›™\™XİŒÎ›ÚÊH™]\›‚ˆ˜[›İH›™\™XİŒÎœ›İˆYˆ
›İHLŒŒ
HÂˆ˜[›İÓ\ÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
Bˆ˜[YÙS\ÈH
›İÓ\ÈHËœÜÚ][Û‹™[U[YJK˜ÛÙ\˜ÙP]X\İ

Bˆ˜[\Ô\\ˆHËœÜÚ][Û‹š\Ô\\”ÜÚ][Û‚ˆ˜[\ĞØ[›ÛšXØ[[™UYÈHËœÜÚ][Û‹˜Y[™Ó[ÙKš\Ó›İ›[šÊ
H	‰‚ˆËœÜÚ][Û‹˜Y[™Ó[ÙK\\˜Ø\ÙJ
HOH”ÕS‘T‘‚ˆ˜[\Ô\œÚ\İYHHÂˆÛÛK›Y™XŞXÛX›İ™[™Ú[™K”ÜÚ][Û”\œÚ\İ[˜ÙKš\Ô\œÚ\İYÜÚ][ÛŠË›Z[
BˆHØ]Ú
Îˆ›İØX›JHÈ˜[ÙHBˆ˜[Ûİ\˜ÙPÚ[™ÙY[œ™X˜\ÙYH\Ô\\ˆ	‰‚ˆËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙKš\Ó›İ›[šÊ
H	‰‚ˆËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙHOH•S’Ó“ÕÓˆˆ	‰‚ˆË›\İšXÙTÛİ\˜ÙKš\Ó›İ›[šÊ
H	‰‚ˆËœÜÚ][Û‹™[TšXÙTÛİ\˜ÙHOHË›\İšXÙTÛİ\˜ÙH	‰‚ˆ]ËœÜÚ][Û‹œšXÙP˜\Ú\Ô™\ØØ[Y‚ˆËÈKKŒLLH8 %ÚÜİ\ÜÚ][ÛˆÛÛZ[›Y[‚ˆËÈH˜[˜XÚÈÜœ[ˆ\™Y›ÛÜˆ\ÈHTÕ\™\ÛÜØY™]H™]›Ü‚ˆËÈÛÜ™\İÜ™YÜÚ][ÛœÈÚ]›È[™HİÛ™\‹ˆ]]\İ›İš\™HÛ‚ˆËÈœ˜[™[™]ÈTTˆ^\È]]™H\İ™Y[ˆ›İ\›˜[YÜ\œÚ\İY]ˆËÈ]™H›İY]™Y[ˆ™KXYÜYHİX‹]˜Y\ˆXİ]™HX\ËˆHLLˆËÈ™\ÜÚİÙYÈœ™\Ú\\ˆÜÚ][ÛœÈ›Ü˜ÙK\ÛÛ[ˆKMÜÈÚ]ˆËÈ˜ZÙHL	K‹‹NÉH“ˆÜÙHÙ\™HXØÛİ[[™ËÜšXÙKX˜\Ú\ÈÚÜİËˆËÈ›İ™X[X\šÙ]ÜÜÙ\Ëˆ›Ü›X[[™H^]Èİ[[™›Ü˜ÙHBˆËÈ[˜ÛÛ™][Û˜[LMIHİÜÈ\ÈİX\™Û›Hİ\™\ÜÙ\ÈHÜœ[‚ˆËÈ˜[˜XÚÈ][[HÜÚ][Ûˆ\ÈÛ[›İYÚÈ™H[HÜœ[™Y‚ˆYˆ
\Ô\\ˆ	‰ˆYÙS\ÈŒÌ
HÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ“Ô”S—ÑSPÒ×ÔÕT‘TÔÑQÑ”‘TÒÔTTˆ‹ˆœŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H›IÜ›İÒ[

_HYÙS\ÏIYÙS\È\œÚ\İYI\Ô\œÚ\İY[ÙOIİËœÜÚ][Û‹˜Y[™Ó[Ù_HÜ˜ÏIİËœÜÚ][Û‹™[TšXÙTÛİ\˜Ù_KO‰İË›\İšXÙTÛİ\˜Ù_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆBˆYˆ
\Ô\\ˆ	‰ˆ\ĞØ[›ÛšXØ[[™UYÈ	‰ˆ\Ô\œÚ\İY	‰ˆYÙS\ÈH
ˆŒÌ
HÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ“Ô”S—ÑSPÒ×ÔÕT‘TÔÑQĞĞS“Ó’PĞSÔTTˆ‹ˆœŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H›IÜ›İÒ[

_HYÙS\ÏIYÙS\È[ÙOIİËœÜÚ][Û‹˜Y[™Ó[Ù_H\œÚ\İY]YH‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆBˆYˆ
Ûİ\˜ÙPÚ[™ÙY[œ™X˜\ÙY
HÂˆHÂˆ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJˆ“Ô”S—ÑSPÒ×ÔÕT‘TÔÑQÔ’PÑWĞTÒTÈ‹ˆœŞ[X›ÛIİËœŞ[X›ÛHZ[IİË›Z[ZÙJL
_H›IÜ›İÒ[

_HYÙS\ÏIYÙS\ÈÜ˜ÏIİËœÜÚ][Û‹™[TšXÙTÛİ\˜Ù_KO‰İË›\İšXÙTÛİ\˜Ù_H‚ˆ
BˆHØ]Ú
Îˆ›İØX›JHßBˆ™]\›‚ˆB‚ˆ\œ›Ü“ÙÙÙ\‹Ø\›Š›İÙ\šXÙH‹ˆ¼'æäHÑSPÒ×ÔĞQ‘UWÔÓVÓÔ”S—H	İËœŞ[X›ÛH	Ü›İÒ[

_IH8 %›ÈİX‹]˜Y\ˆ\ÈZ[Èš\š[™È\™Y›ÛÜˆŠBˆ^Xİ]Ü‹œ™\]Y\İÙ[
ˆÈHËˆ™X\ÛÛˆH‘SPÒ×ÓÔ”S—ÒT‘Ñ“ÓÔ—ÉÜ›İÒ[

_TÕ‹ˆØ[]HØ[]Ø[]ÛÛHY™™Xİ]™P˜[[˜ÙKˆ
BˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆ\œ›Ü“ÙÙÙ\‹™XYÊ›İÙ\šXÙH‹–ÑSPÒ×ÔĞQ‘UWH\œ›Üˆ	ÙK›Y\ÜØYÙ_HŠBˆBˆB‚ˆÊŠ‚ˆ
ˆKKŒMH8 %Z[HŞ[]XÈZ\’[™›Èœ›ÛH[™XYK\Ü[]YÚÙ[”İ]Bˆ
ˆ˜[˜XÚÈ]H
[\™[ˆTHÈš\™^YH[]™\™YšXÙJÛXØ\
Û\]ZY]H[Âˆ
ˆË›\İšXÙHÈË›\İXØ\ÈË›\İ\]ZY]U\Ù]^ØÜ™Y[™\ˆ\È›Âˆ
ˆZ\ŠKˆ\È]È›ØÙ\ÜÕÚÙ[ŞXÛHÛÛ[YH[ÈŒËÔÚ]ÛÚ[ˆ[Bˆ
ˆ]˜[X][Ûˆ[œİXYÙˆ™]\›š[™ÈX\›Kˆ™KYÜ˜YX][Ûˆ[\™[ˆÚÙ[œÂˆ
ˆ\™HHÚ]ÛÚ[ˆ[™IÜÈ\ÚYÛ™Y\™Ù]X\šÙ]‚ˆ
‚ˆ
ˆšY[Ù[X[XÜÎ‚ˆ
ˆHØ[™NˆŞ[]XÈK]XÚÈØ[™H]İ\œ™[˜[˜XÚÈšXÙJÛXØ\‚ˆ
ˆ›Û[YHÈ^\ÈÈÙ[ÈY˜][È8 %İÛœİ™X[HØÛÜ™\œÈ[™XYBˆ
ˆ[™H››È]HY]ˆšXH›ZYX\›š[™ĞRH›Ûİİ˜\]\š\İXÜË‚ˆ
ˆH\]ZY]HÈ™ˆÛÜYYœ›ÛHËˆ›Üˆ[\™[ˆ›Û™[™ËXİ\™HÚÙ[œÂˆ
ˆ\È\ÈXØ\
ˆH
Ù]HQ˜[˜XÚÔšXÙQ]HÙYY]
K‚ˆ
ˆH\›ˆYÙÙYÛÈİÛœİ™X[HÛİ\˜ÙKY]Xİ[ÛˆÙY\È[\™[‹ÚXÚˆ
ˆÛÜœ™XİH›İ]\ÈHÚÙ[ˆ[ÈÚ]ÛÚ[•˜Y\RK“][˜Ú]›Ü›K”STÑ•S‹‚ˆ
‹Âˆš]˜]H[ˆŞ[\Ú^™Q˜[˜XÚÔZ\ŠÎˆÛÛK›Y™XŞXÛX›İ™]K•ÚÙ[”İ]JNˆÛÛK›Y™XŞXÛX›İ›™]ÛÜšË”Z\’[™›ÏÈÂˆYˆ
Ë›\İšXÙHHŒ
H™]\›ˆ[ˆ˜[›İÓ\ÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
Bˆ˜[Ø[™HHÛÛK›Y™XŞXÛX›İ™]KØ[™JˆÈH›İÓ\ËˆšXÙU\ÙHË›\İšXÙKˆX\šÙ]Ø\HË›\İXØ\˜ÛÙ\˜ÙP]X\İ
Œ
Kˆ›Û[YRHHŒˆ›Û[YLHŒˆ^\ÒHHˆÙ[ÒHHˆYÚ\ÙHË›\İšXÙKˆİÕ\ÙHË›\İšXÙKˆÜ[•\ÙHË›\İšXÙKˆ
BˆËÈT“[ÛÈ›ØÙ\ÜÕÚÙ[ŞXÛIÜÈÛİ\˜ÙKZ[™™\™[˜ÙHİ[YÜÈ[\™[‚ˆËÈÛÜœ™XİHÚ[ˆËœÛİ\˜ÙH\È[\H
HÔÈ™YYÙ]ÈSTÔÔ•SÕÔËˆËÈ]Y™[œÙKZ[‹Y\ˆ[][™È[ÙH[™È\™HÛÊK‚ˆ˜[\›HYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ”ST‹YÛ›Ü™PØ\ÙHHYJJHÂˆšÎ‹ËÜ[\™[‹ÉİË›Z[H‚ˆH[ÙHÂˆˆ‚ˆBˆ™]\›ˆÛÛK›Y™XŞXÛX›İ›™]ÛÜšË”Z\’[™›ÊˆZ\Y™\ÜÈHˆ‹ËÈ›ÈÛ‹XÚZ[ˆZ\ˆY]
›Û™[™Èİ\™JBˆ˜\ÙTŞ[X›ÛHËœŞ[X›ÛšY›[šÈÈË›Z[ZÙJŠHKˆ˜\ÙS˜[YHHË›˜[YKšY›[šÈÈËœŞ[X›ÛšY›[šÈÈË›Z[ZÙJŠHHKˆ\›H\›ˆØ[™HHØ[™KˆZ\Ü™X]Y]\ÈHË˜YYÕØ]Ú\İ]ZÙRYˆÈ]ˆHÎˆ›İÓ\Ëˆ\]ZY]HHË›\İ\]ZY]U\Ù˜ÛÙ\˜ÙP]X\İ
Œ
Kˆ™ˆHË›\İ™‹ZÙRYˆÈ]ˆHÎˆË›\İXØ\˜ÛÙ\˜ÙP]X\İ
Œ
Kˆ˜\ÙUÚÙ[Y™\ÜÈHË›Z[ˆ
BˆB‚ˆš]˜]H[ˆQ˜[˜XÚÔšXÙQ]JZ[ˆİš[™ËÎˆÚÙ[”İ]JNˆ›ÛÛX[ˆÂˆËÈHš\™^YHš\œİˆHÂˆ˜[Ù™ÌˆHÛÛ™šYÔİÜ™K›ØY
\XØ][ÛÛÛ^
Bˆ˜[İˆHÛÛK›Y™XŞXÛX›İ›™]ÛÜšËš\™^YP\JÙ™Ì‹˜š\™^YP\RÙ^JK™Ù]ÚÙ[“İ™\šY]ÊZ[
BˆYˆ
İˆOH[	‰ˆİ‹œšXÙU\Ùˆ
HÂˆŞ[˜Ú›Ûš^™Y
ÊHÂˆË›\İšXÙHHİ‹œšXÙU\ÙˆË›\İšXÙU\]HHŞ\İ[K˜İ\œ™[[YSZ[\Ê
BˆË›\İšXÙTÛİ\˜ÙHH’T‘VQWÓÕ‘T•’QUÈˆËÈKKÍˆË›\İ\]ZY]U\ÙHİ‹›\]ZY]BˆË›\İXØ\Hİ‹›X\šÙ]Ø\ˆË›\İ™ˆHİ‹›X\šÙ]Ø\ˆ˜[Ş[]XĞØ[™HHÛÛK›Y™XŞXÛX›İ™]KØ[™JˆÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KšXÙU\ÙHİ‹œšXÙU\ÙˆX\šÙ]Ø\Hİ‹›X\šÙ]Ø\›Û[YRHHŒ›Û[YLHŒˆ^\ÒHHÙ[ÒHHYÚ\ÙHİ‹œšXÙU\ÙˆİÕ\ÙHİ‹œšXÙU\ÙÜ[•\ÙHİ‹œšXÙU\Ùˆ
BˆŞ[˜Ú›Ûš^™Y
Ëš\İÜJHÂˆËš\İÜK˜Y\İ
Ş[]XĞØ[™JBˆYˆ
Ëš\İÜKœÚ^™HˆÌ
HËš\İÜKœ™[[İ™Qš\œİ

BˆBˆBˆœ›ØYØ\İ˜[˜XÚÔšXÙJZ[İ‹œšXÙU\Ù
HËÈKKŒÂˆYÙÊ¼'äèHš\™^YNˆ	İËœŞ[X›ÛH		Ûİ‹œšXÙU\ÙH‹Z[
Bˆ™]\›ˆYBˆBˆHØ]Ú
Îˆ^Ù\[ÛŠHßB‚ˆËÈKKŒÈ8 %^ØÜ™Y[™\“Ü˜XÛH
Ù\\˜]HÛÙH]œ›ÛH^™Ù]™\İZ\‹ˆËÈY™™\™[[™Ú[Y™™\™[ØXÚJKˆÚ[ˆHZ\‹X˜\ÙYØ[˜Z[ÂˆËÈ\ÈÚÙ[‹XY™\ÜÈØ[Ù[ˆİ[™]\›œÈ8 %^ØÜ™Y[™\ˆØXÚ\ÂˆËÈÚÙ[‹[]™[[™Z\‹[]™[]H[™\[™[K‚ˆYˆ
Ë›\İšXÙHH
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË›\İšXÙU\]JHˆLŒÌ
HÂˆHÂˆ˜[šXÙU\ÙHÛİ[˜ÛÜ›İ][™\Ëœ[›ØÚÚ[™ÈÂˆÛİ[˜ÛÜ›İ][™\ËÚ][Y[İ]Ü“[
Œ
HÂˆÛÛK›Y™XŞXÛX›İœ\œË‘^ØÜ™Y[™\“Ü˜XÛK™Ù]šXÙPPY™\ÜÊZ[
BˆBˆBˆYˆ
šXÙU\ÙOH[	‰ˆšXÙU\Ùˆ
HÂˆŞ[˜Ú›Ûš^™Y
ÊHÂˆË›\İšXÙHHšXÙU\ÙˆË›\İšXÙU\]HHŞ\İ[K˜İ\œ™[[YSZ[\Ê
BˆË›\İšXÙTÛİ\˜ÙHH”RT—ÑSPÒÈˆËÈKKÍˆBˆœ›ØYØ\İ˜[˜XÚÔšXÙJZ[šXÙU\Ù
BˆYÙÊ¼'äâˆ^ØÜ™Y[™\ŠÚÙ[ŠNˆ	İËœŞ[X›ÛH		ÜšXÙU\ÙH‹Z[
Bˆ™]\›ˆYBˆBˆHØ]Ú
Îˆ›İØX›JHßBˆB‚ˆËÈKKŒÈ8 %š\™^YSÜ˜XÛHÚÙ[‹XY™\ÜÈTH
Y™™\™[œ›ÛHš\™^YP\BˆËÈ\ÙYX›İ™KÚXÚ\Èİ™\šY]ËY›Øİ\ÙYÈ\ÈÛ™H\ÈšXÙKY›Øİ\ÙY[™ˆËÈ]ÈHÙ\\˜]H˜]K[[Z]XÚÙ]
K‚ˆYˆ
Ë›\İšXÙHH
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË›\İšXÙU\]JHˆLŒÌ
HÂˆHÂˆ˜[šXÙU\ÙHÛİ[˜ÛÜ›İ][™\Ëœ[›ØÚÚ[™ÈÂˆÛİ[˜ÛÜ›İ][™\ËÚ][Y[İ]Ü“[
Œ
HÂˆÛÛK›Y™XŞXÛX›İœ\œËš\™^YSÜ˜XÛK™Ù]šXÙPPY™\ÜÊZ[
BˆBˆBˆYˆ
šXÙU\ÙOH[	‰ˆšXÙU\Ùˆ
HÂˆŞ[˜Ú›Ûš^™Y
ÊHÂˆË›\İšXÙHHšXÙU\ÙˆË›\İšXÙU\]HHŞ\İ[K˜İ\œ™[[YSZ[\Ê
BˆË›\İšXÙTÛİ\˜ÙHH”RT—ÑSPÒÈˆËÈKKÍˆBˆœ›ØYØ\İ˜[˜XÚÔšXÙJZ[šXÙU\Ù
BˆYÙÊ¼'ä)ˆš\™^YSÜ˜XÛNˆ	İËœŞ[X›ÛH		ÜšXÙU\ÙH‹Z[
Bˆ™]\›ˆYBˆBˆHØ]Ú
Îˆ›İØX›JHßBˆB‚ˆËÈH[\™[ˆTBˆËÈKKŒÈ8 %[ÛÈ™]H[\™[ˆYˆH\İİXØÙ\ÜÙ[šXÙH\ÈŒLŒÂˆËÈİ[Kˆ™]š[İ\ÛHHYˆ
Ë›\İšXÙHH
XİX\™YX[[\™[‚ˆËÈØ\ÈÛ›HÛÛœİ[YÛˆœ˜[™[™]ÈÛÈ]Y™]™\ˆ™Y[ˆšXÙY‚ˆYˆ
Ë›\İšXÙHH
Ş\İ[K˜İ\œ™[[YSZ[\Ê
HHË›\İšXÙU\]JHˆLŒÌ
HÂˆHÂˆ˜[ÛY[HÛÛK›Y™XŞXÛX›İ›™]ÛÜšË”Ú\™YÛY[˜Z[\Š
Bˆ˜ÛÛ›™Xİ[Y[İ]
K˜]˜K][˜ÛÛ˜İ\œ™[•[YU[š]”ÑPÓÓ‘ÊBˆœ™XY[Y[İ]
K˜]˜K][˜ÛÛ˜İ\œ™[•[YU[š]”ÑPÓÓ‘ÊK˜Z[

BˆËÈKKŒH8 %X[X]Ø\™H^Xİ]Nˆ]]Ë[ZYÜ˜]HXYÜİÈ
È™XÛÜ™[[Y]Bˆ˜[ÜšYÚ[˜[\›HšÎ‹ËÙœ›Û[™X\K]ŒËœ[\™[‹ØÛÚ[œËÉZ[‚ˆ˜[Y™™Xİ]™U\›HHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K]]Ñ[™Ú[ZYÜ˜]Ü‹œ™]Üš]JÜšYÚ[˜[\›
HHØ]Ú
Îˆ›İØX›JHÈÜšYÚ[˜[\›Bˆ˜[™\]Y\İHÚÚË”™\]Y\İZ[\Š
Bˆ\›
Y™™Xİ]™U\›
BˆšXY\ŠXØÙ\‹˜\XØ][Û‹ÚœÛÛˆŠK˜Z[

Bˆ˜[[\İ\HŞ\İ[K˜İ\œ™[[YSZ[\Ê
Bˆ˜[™\ÜÛœÙHHHÂˆÛY[›™]ĞØ[
™\]Y\İ
K™^Xİ]J
BˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K\RX[[Ûš]Ü‹œ™XÛÜ™™]ÛÜšÑ\œ›ÜŠœ[\[ˆ‹K›Y\ÜØYÙJHHØ]Ú
Îˆ›İØX›JHßBˆ›İÈBˆBˆHÈÛÛK›Y™XŞXÛX›İ™[™Ú[™K\RX[[Ûš]Ü‹œ™XÛÜ™
œ[\[ˆ‹™\ÜÛœÙK˜ÛÙKŞ\İ[K˜İ\œ™[[YSZ[\Ê
HH[\İ\
HHØ]Ú
Îˆ›İØX›JHßBˆYˆ
™\ÜÛœÙKš\ÔİXØÙ\ÜÙ[
HÂˆ˜[›ÙHH™\ÜÛœÙK˜›ÙOËœİš[™Ê
BˆYˆ
›ÙHOH[
HÂˆ˜[œÛÛˆHÜ™ËšœÛÛ‹’”ÓÓ“Øš™Xİ
›ÙJBˆ˜[XØ\HœÛÛ‹›ÜİX›J\ÙÛX\šÙ]ØØ\‹Œ
BˆËÈ“ÕNˆ[\™[ˆTIÜÈœšXÙHˆšY[\È[ˆÓÓ
›İTÑ
KÛÈÙBˆËÈÛÛ\]HHÛÜœ™XİTÑšXÙHœ›ÛH\ÙÛX\šÙ]ØØ\Èİ[Üİ\K‚ˆËÈ[\™[ˆÚÙ[œÈ[Ø^\È]™HPˆÚÙ[ˆİ\H\ÈZ\ˆİ[™\™‚ˆ˜[İ[İ\HHœÛÛ‹›ÜİX›Jİ[Üİ\H‹WÌÌÌŒ
Bˆ›]ÈYˆ
]H
HWÌÌÌŒ[ÙH]Bˆ˜[šXÙU\ÙHYˆ
XØ\ˆ	‰ˆİ[İ\Hˆ
HXØ\Èİ[İ\H[ÙHŒˆYˆ
XØ\ˆ
HÂˆŞ[˜Ú›Ûš^™Y
ÊHÂˆË›\İšXÙHHšXÙU\ÙˆË›\İšXÙU\]HHŞ\İ[K˜İ\œ™[[YSZ[\Ê
BˆË›\İšXÙTÛİ\˜ÙHH”STÑ•S—Ñ”“Ó•S‘ĞTHˆËÈKKÍˆË›\İšXÙQ^H”STÑ•Sˆ‚ˆË›\İXØ\HXØ\ˆË›\İ™ˆHXØ\ˆË›\İ\]ZY]U\ÙHXØ\
ˆŒBˆ˜[Ş[]XĞØ[™HHÛÛK›Y™XŞXÛX›İ™]KØ[™JˆÈHŞ\İ[K˜İ\œ™[[YSZ[\Ê
KšXÙU\ÙHšXÙU\ÙˆX\šÙ]Ø\HXØ\›Û[YRHHŒ›Û[YLHŒˆ^\ÒHHÙ[ÒHHYÚ\ÙHšXÙU\ÙˆİÕ\ÙHšXÙU\ÙÜ[•\ÙHšXÙU\Ùˆ
BˆŞ[˜Ú›Ûš^™Y
Ëš\İÜJHÂˆËš\İÜK˜Y\İ
Ş[]XĞØ[™JBˆYˆ
Ëš\İÜKœÚ^™HˆÌ
HËš\İÜKœ™[[İ™Qš\œİ

BˆBˆBˆYÙÊ¼'ã«È[\™[ˆ	İËœŞ[X›ÛHXØ\W		ÛXØ\Ò[

_HšXÙU\ÙW		Ôİš[™Ë™›Ü›X]
‰KŒLˆ‹šXÙU\Ù
_H‹Z[
Bˆœ›ØYØ\İ˜[˜XÚÔšXÙJZ[šXÙU\Ù
HËÈKKŒÂˆ™]\›ˆYBˆBˆBˆBˆHØ]Ú
Îˆ^Ù\[ÛŠHßBˆBˆ™]\›ˆ˜[ÙBˆB‚ˆš]˜]H˜[[RY˜][Û”[™[™ÍÈH˜]˜K][˜ÛÛ˜İ\œ™[ÛÛ˜İ\œ™[\ÚX\›™]ÒÙ^TÙ]İš[™ÏŠ
Bˆš]˜]H[ˆ™\]Y\İ[RY˜][ÛÊZ[ˆİš[™ËÎˆÚÙ[”İ]JHÂˆYˆ
Y[RY˜][Û”[™[™ÍË˜Y
Z[
JH™]\›‚ˆØÛÜK›][˜Ú
İ\\š\ÛÜ‘\Ü]Ú\È
ÈÛÜ›İ][™S˜[YJ™[KZY˜][Û‹IÛZ[ZÙJ
_HŠJHÂˆHÂˆQ˜[˜XÚÔšXÙQ]JZ[ÊBˆHØ]Ú
ÙNˆØ[˜Ù[][Û‘^Ù\[ÛŠHÂˆ›İÈÙBˆHØ]Ú
ˆ›İØX›JHÂˆHÈ›Ü™[œÚXÓÙÙÙ\‹›Y™XŞXÛJ‘S•–WÒQUSÓ—ÑT”“Ô—ÍÈ‹›Z[IÛZ[ZÙJL
_H\œ›ÜIİš˜]˜PÛ\ÜËœÚ[\S˜[Y_N‰İ›Y\ÜØYÙOËZÙJ
_HŠHHØ]Ú
Îˆ›İØX›JHßBˆHš[˜[HÂˆ[RY˜][Û”[™[™ÍËœ™[[İ™JZ[
BˆBˆBˆB‚ˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆËÈÒUÓÒSˆVQTˆST”ÂˆËÈ8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥d8¥dˆˆÊŠ‚ˆ
ˆØ[İ[]HÛØÚX[ØÛÜ™H›ÜˆHÚÙ[ˆ˜\ÙYÛˆ]˜Z[X›HÚYÛ˜[Âˆ
‹Âˆš]˜]H[ˆØ[İ[]TÛØÚX[ØÛÜ™JÎˆÚÙ[”İ]JNˆ[Âˆ˜\ˆØÛÜ™HHˆˆËÈ›ÛÜİ›Üˆ™[™[™ÈÚÙ[œÂˆYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ•‘S‘S‘È‹YÛ›Ü™PØ\ÙHHYJJHØÛÜ™H
ÏHBˆYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ“ÓÔÕQ‹YÛ›Ü™PØ\ÙHHYJJHØÛÜ™H
ÏHŒˆËÈKŒŒÌˆ8 %™X[ØXÚYÛØÚX[[Kˆ\ÈÛÛœİ[Y\È^\İ[™ÈÙ[[Y[ˆËÈšY[ÈÛ›NÈ›ÈØØ[›™\‹Ù^Xİ]Üˆİ\]THØ[Ë‚ˆYˆ
]ËœÙ[[Y[š\Ôİ[JHÂˆYˆ
ËœÙ[[Y[Y[[ÛœÈˆ
HØÛÜ™H
ÏH
ËœÙ[[Y[Y[[ÛœÈ
ˆŠK˜ÛÙ\˜ÙP][Üİ
JBˆYˆ
ËœÙ[[Y[[YÜ˜[SY[[ÛœÈˆ
HØÛÜ™H
ÏH
ËœÙ[[Y[[YÜ˜[SY[[ÛœÈ
ˆŠK˜ÛÙ\˜ÙP][Üİ
Œ
BˆYˆ
ËœÙ[[Y[™XØ^YYØÛÜ™HˆŒŒ
HØÛÜ™H
ÏHMBˆYˆ
ËœÙ[[Y[™XØ^YYØÛÜ™HLŒŒ
HØÛÜ™HOHMBˆYˆ
ËœÙ[[Y[˜›ØÚÙY
HØÛÜ™HOHÌˆBˆˆËÈ›ÛÜİ›ÜˆÚÙ[œÈœ›ÛH™XÛÙÛš^™Y]›Ü›\ÂˆYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ”STÑ•Sˆ‹YÛ›Ü™PØ\ÙHHYJJHØÛÜ™H
ÏHMBˆYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ”VQUSH‹YÛ›Ü™PØ\ÙHHYJJHØÛÜ™H
ÏHLˆˆËÈÜÚ]]™HÚYÛ˜[Èœ›ÛHÛİ\˜ÙH˜[Z[™ÂˆYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ•‘T’Q’QQ‹YÛ›Ü™PØ\ÙHHYJJHØÛÜ™H
ÏHLˆYˆ
ËœÛİ\˜ÙK˜ÛÛZ[œÊ“SÓÓ”ÒÕ‹YÛ›Ü™PØ\ÙHHYJJHØÛÜ™H
ÏHLˆˆËÈŞ[X›Û[™İ]\š\İXÈ
YÚ][X]H›Ú™XİÈÙ[ˆ]™HËMˆÚ\ˆXÚÙ\œÊBˆ˜[Ş[X›Û[ˆHËœŞ[X›Û›[™İˆYˆ
Ş[X›Û[ˆ[ˆË‹ŠHØÛÜ™H
ÏHLˆYˆ
Ş[X›Û[ˆˆL
HØÛÜ™HOHHËÈÛÈÛ™ÈÙ[ˆ[™XØ]\ÈØØ[BˆˆËÈ[™Hš\ÚÈY™™XİÈÛØÚX[\˜Ù\[Û‚ˆYˆ
ËœØY™]K˜[™Tš\ÚÈOH“ÕÈŠHØÛÜ™H
ÏHLˆYˆ
ËœØY™]K˜[™Tš\ÚÈOH’QÒŠHØÛÜ™HOHMBˆˆ™]\›ˆØÛÜ™K˜ÛÙ\˜ÙR[ŠL
BˆBˆˆÊŠ‚ˆ
ˆ]XİÛÜXØ]ÜØØ[H]\›œÈ[ˆÚÙ[ˆŞ[X›ÛÂˆ
ˆ™]\›œÈYHYˆHÚÙ[ˆ\X\œÈÈ™HHÛÜXØ]ÙˆHÛ›İÛˆ›Ú™Xİˆ
‹Âˆš]˜]H[ˆ]XİÛÜPØ]
Ş[X›Ûˆİš[™ÊNˆ›ÛÛX[ˆÂˆ˜[İÙ\”Ş[X›ÛHŞ[X›Û›İÙ\˜Ø\ÙJ
BˆˆËÈÛ›İÛˆ›Ú™XİÈ]Ù]ÛÜYYœ™\]Y[Bˆ˜[Û›İÛ”›Ú™XİÈH\İÙŠˆœ\H‹™ÙÙH‹œÚXˆ‹™›ÚÚH‹ÛÚ˜ZÈ‹˜ÚY‹ˆ™[Ûˆ‹[\‹˜šY[ˆ‹œÛÛ[˜H‹™]‹˜È‹ˆ˜›ÛšÈ‹›^\›È‹˜›ÛYH‹ÚYˆ‹œÜØ]‹›Y]È‚ˆ
BˆˆËÈÚXÚÈ›ÜˆÛYÚ˜\šX][ÛœÈ
K™Ë‹”TLˆ‹”TQH‹”ÔHŠBˆ›Üˆ
›Ú™Xİ[ˆÛ›İÛ”›Ú™XİÊHÂˆËÈ^XİX]Ú\ÈÒÈ
Ûİ[™HYÚ]
BˆYˆ
İÙ\”Ş[X›ÛOH›Ú™Xİ
HÛÛ[YBˆˆËÈÚXÚÈ›Üˆİ\ÜXÚ[İ\È]\›œÂˆ˜[˜\šX][ÛœÈH\İÙŠˆ‰Ü›Ú™XİLˆ‹‰Ü›Ú™XİLÈ‹‰Ü›Ú™Xİ]Œˆ‹ˆ‰Ü›Ú™XİZ[H‹‰Ü›Ú™XİXÛÚ[ˆ‹‰Ü›Ú™Xİ]ÚÙ[ˆ‹ˆ˜˜XI›Ú™Xİ‹›Z[šI›Ú™Xİ‹‰Ü›Ú™XİXÛ\ÜÚXÈ‹ˆ‰Ü›Ú™XİXZH‹‰Ü›Ú™XİYÜ‹‰Ü›Ú™XİX›İ‚ˆ
BˆˆYˆ
˜\šX][ÛœË˜[HÈİÙ\”Ş[X›Û˜ÛÛZ[œÊ]
HİÙ\”Ş[X›ÛOH]JHÂˆ™]\›ˆYBˆBˆˆËÈÚXÚÈ›ÜˆÚ\˜Xİ\ˆİXœİ]][Ûˆ
K™Ë‹ÔKTÊBˆYˆ
İÙ\”Ş[X›Ûœ™\XÙJŒÈ‹™HŠKœ™\XÙJŒ‹›ÈŠKœ™\XÙJŒH‹šHŠHOH›Ú™Xİ
HÂˆ™]\›ˆYBˆBˆBˆˆ™]\›ˆ˜[ÙBˆB‚ˆš]˜]H[ˆYÙÊ\ÙÎˆİš[™ËZ[ˆİš[™ÈHˆŠHÂˆ˜[ÈH˜]˜K^”Ú[\Q]Q›Ü›X]
’›[NœÜÈ‹˜]˜K][“ØØ[K•TÊBˆ™›Ü›X]
˜]˜K][‘]J
JBˆ˜[HYˆ
Z[š\Ó›İ›[šÊ
JH–ÉÛZ[ZÙJŠ_WHˆ[ÙHˆ‚ˆ˜[[™HH–É×H		\ÙÈ‚ˆŞ[˜Ú›Ûš^™Y
İ]\Ë›ÙÜÊHÂˆİ]\Ë›ÙÜË˜Y\İ
[™JBˆYˆ
İ]\Ë›ÙÜËœÚ^™HˆŒ
Hİ]\Ë›ÙÜËœ™[[İ™Qš\œİ

BˆBˆB‚ˆ[ˆ^TÛİ[™›Ü•˜YJ›ÛÛˆİX›K\ÔÙ[ˆ›ÛÛX[‹™X\ÛÛˆİš[™ÈHˆŠHÂˆYˆ
Z\ÔÙ[
H™]\›‚ˆYˆ
›ÛÛˆ
HÂˆÛİ[™X[˜YÙ\‹œ^PØ\Ú™YÚ\İ\Š
BˆH[ÙHÂˆÛİ[™X[˜YÙ\‹œ^UØ\›š[™ÔÚ\™[Š
BˆBˆBˆˆËÈKKŒL8 %ÚİÕØ\İ

H^˜XİYÈ›İÙ\šXÙSY™XŞXÛQ^šİ‚ˆš]˜]H[ˆÙ[™˜YS›İYŠ]Nˆİš[™Ë›ÙNˆİš[™Ëˆ\Nˆ›İYšXØ][Û’\İÜK“›İY‘[K“›İY•\HH›İYšXØ][Û’\İÜK“›İY‘[K“›İY•\K’S‘“ÊHÂˆ›İY’\İÜK˜Y
]K›ÙK\JBˆˆËÈÚXÚÈYˆ›İYšXØ][ÛœÈ\™H[˜X›Yˆ˜[Ù™ÈHHÈÛÛK›Y™XŞXÛX›İ™]KÛÛ™šYÔİÜ™K›ØY
\XØ][ÛÛÛ^
HBˆØ]Ú
Îˆ^Ù\[ÛŠHÈ™]\›ˆBˆˆËÈÛ›HÚİÈŞ\İ[H›İYšXØ][ÛˆYˆ[˜X›YˆYˆ
Ù™Ë››İYšXØ][ÛœÑ[˜X›Y
HÂˆ˜[Ú[›™[HYˆ
Ù™ËšXœ˜][Û‘[˜X›Y
HÒS“‘SÕQH[ÙHÒS“‘SÕQWÔÒSS•ˆ˜[[[H[[
\ËXZ[Xİ]š]N˜Û\ÜËš˜]˜JBˆ˜[HH[™[™Ò[[™Ù]Xİ]š]J\Ë[[ˆ[™[™Ò[[‘“Q×ÕTUWĞÕT”‘S•Üˆ[™[™Ò[[‘“Q×ÒSSUUP“JBˆ˜[›İYˆH›İYšXØ][ÛÛÛ\]Z[\Š\ËÚ[›™[
BˆœÙ]ÛX[XÛÛŠ‹™˜]ØX›KšX×Û›İYŠBˆœÙ]ÛÛ[]J]JBˆœÙ]ÛÛ[^
›ÙJBˆœÙ]]]ĞØ[˜Ù[
YJBˆœÙ]š[Üš]JYˆ
Ù™ËšXœ˜][Û‘[˜X›Y
H›İYšXØ][ÛÛÛ\]”’SÔ’UWÒQÒ[ÙH›İYšXØ][ÛÛÛ\]”’SÔ’UWÑQUS
BˆœÙ]ÛÛ[[[
JBˆ˜Z[

Bˆ
Ù]Ş\İ[TÙ\šXÙJ“ÕQ’PĞUSÓ—ÔÑT•’PÑJH\È›İYšXØ][Û“X[˜YÙ\ŠBˆ››İYJ›İY’YÛİ[\ŠÊË›İYŠBˆBˆˆËÈZ\œ›ÜˆÈ[YÜ˜[HYˆÛÛ™šYİ\™Y
š\™KX[™Y›Ü™Ù]˜XÚÙÜ›İ[™™XY
BˆYˆ
Ù™Ë[YÜ˜[U˜YP[\È	‰ˆÙ™Ë[YÜ˜[P›İÚÙ[‹š\Ó›İ›[šÊ
JHÂˆØÛÜK›][˜Ú
Ûİ[˜ÛÜ›İ][™\Ë‘\Ü]Ú\œË’SÊHÂˆ[YÜ˜[S›İYšY\‹œÙ[™
Ù™Ë‰]OØ—‰›ÙHŠBˆBˆBˆB‚ˆËÈ8¥ 8¥ ›İYšXØ][ÛœÈ8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ 8¥ ‚ˆš]˜]H[ˆÜ™X]PÚ[›™[Ê
HÂˆ˜[›HHÙ]Ş\İ[TÙ\šXÙJ“ÕQ’PĞUSÓ—ÔÑT•’PÑJH\È›İYšXØ][Û“X[˜YÙ\‚ˆ›K˜Ü™X]S›İYšXØ][ÛÚ[›™[
ˆ›İYšXØ][ÛÚ[›™[
ÒS“‘SÒQ›İ[›š[™È‹ˆ›İYšXØ][Û“X[˜YÙ\‹’STÔ•SÑWÓÕÊK˜\HÂˆ\ØÜš\[ÛˆH”\œÚ\İ[›İYšXØ][ÛˆÚ[H›İ\ÈXİ]™H‚ˆBˆ
Bˆ›K˜Ü™X]S›İYšXØ][ÛÚ[›™[
ˆ›İYšXØ][ÛÚ[›™[
ÒS“‘SÕQK•˜YHÚYÛ˜[È‹ˆ›İYšXØ][Û“X[˜YÙ\‹’STÔ•SÑWÒQÒ
K˜\HÂˆ\ØÜš\[ÛˆH^KÜÙ[ÚYÛ˜[[\È‚ˆ[˜X›UšXœ˜][ÛŠYJBˆBˆ
Bˆ›K˜Ü™X]S›İYšXØ][ÛÚ[›™[
ˆ›İYšXØ][ÛÚ[›™[
ÒS“‘SÕQWÔÒSS••˜YHÚYÛ˜[È
Ú[[
H‹ˆ›İYšXØ][Û“X[˜YÙ\‹’STÔ•SÑWÑQUS
K˜\HÂˆ\ØÜš\[ÛˆH•˜YH[\ÈÚ]İ]šXœ˜][Ûˆ‚ˆ[˜X›UšXœ˜][ÛŠ˜[ÙJBˆÙ]Ûİ[™
[[
BˆBˆ
BˆB‚ˆš]˜]H[ˆZ[[›š[™Ó›İYŠ
Nˆ›İYšXØ][ÛˆÂˆ˜[[[H[[
\ËXZ[Xİ]š]N˜Û\ÜËš˜]˜JBˆ˜[HH[™[™Ò[[™Ù]Xİ]š]J\Ë[[ˆ[™[™Ò[[‘“Q×ÒSSUUP“JBˆ™]\›ˆ›İYšXØ][ÛÛÛ\]Z[\Š\ËÒS“‘SÒQ
BˆœÙ]ÛX[XÛÛŠ‹™˜]ØX›KšX×Û›İYŠBˆœÙ]ÛÛ[]JPUHŠBˆœÙ]ÛÛ[^
”[›š[™È8 %\ÈÜ[ˆŠBˆœÙ]Û™ÛÚ[™ÊYJBˆœÙ]ÛÛ[[[
JBˆœÙ]›Ü™YÜ›İ[™Ù\šXÙP™Z]š[ÜŠ›İYšXØ][ÛÛÛ\]‘“Ô‘QÔ“ÕS‘ÔÑT•’PÑWÒSSQQPUJBˆœÙ]š[Üš]J›İYšXØ][ÛÛÛ\]”’SÔ’UWÑQUS
HËÈY˜][š[Üš]BˆœÙ]Ø]YÛÜJ›İYšXØ][ÛÛÛ\]ĞUQÓÔ–WÔÑT•’PÑJBˆ˜Z[

BˆBˆˆÊŠ‚ˆ
ˆ\™ÙHÜœ[™YÚÙ[œÈÛˆ›İİÜ
]™H[ÙHÛ›JK‚ˆ
ˆØØ[œÈØ[]›ÜˆÚÙ[œÈ›İ˜XÚÙYH›İ[™Ù[È[K‚ˆ
‹Âˆš]˜]H[ˆ\™ÙSÜœ[™YÚÙ[œÓÛ”İÜ
Ù™Îˆ›İÛÛ™šYÊHÂˆHÂˆ˜[ÈHØ[]Îˆ™]\›‚ˆYÙÊ¼'éîHØØ[›š[™È›ÜˆÜœ[™YÚÙ[œÈÛˆÚ]İÛ‹‹‹ˆŠBˆˆ˜[ÚÙ[XØÛİ[ÈHË™Ù]ÚÙ[XØÛİ[Ê
Bˆ˜[˜XÚÙYZ[ÈHŞ[˜Ú›Ûš^™Y
İ]\ËÚÙ[œÊHÂˆİ]\ËÚÙ[œË˜[Y\Âˆ™š[\ˆÈ]œÜÚ][Û‹š\ÓÜ[ˆBˆ›X\È]›Z[BˆÔÙ]

BˆBˆˆ˜\ˆÜœ[œÔÛÛHˆˆÚÙ[XØÛİ[Ë™›Ü‘XXÚÈ
Z[]JHO‚ˆËÈÚÚ\\İˆYˆ
]HKŒ
H™]\››Ü‘XXÚˆËÈÚÚ\˜XÚÙYÜÚ][ÛœÂˆYˆ
Z[[ˆ˜XÚÙYZ[ÊH™]\››Ü‘XXÚˆËÈÚÚ\ÓÓˆYˆ
Z[OH”ÛÌLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLˆŠH™]\››Ü‘XXÚˆˆ˜[Ş[X›ÛHİ]\ËÚÙ[œÖÛZ[OËœŞ[X›ÛÎˆZ[ZÙJ
BˆYÙÊ¼'éîH›İ[™Üœ[™YÚÙ[ˆ	Ş[X›Û
	]JHŠBˆˆHÂˆ˜[ÛÛH^Xİ]Ü‹œÙ[Üœ[™YÚÙ[ŠZ[]KÊBˆYˆ
ÛÛ
HÂˆÜœ[œÔÛÛ
ÊÂˆYÙÊ¸§!HÛÛÜœ[ˆ	Ş[X›ÛŠBˆH[ÙHÂˆYÙÊ¸¦¨;î#ÈÛİ[›İÙ[	Ş[X›ÛHX[X[ÛX[\™YYYŠBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆYÙÊ¸¦¨;î#È\œ›ÜˆÙ[[™È	Ş[X›Ûˆ	ÙK›Y\ÜØYÙ_HŠBˆBˆBˆˆYˆ
Üœ[œÔÛÛˆ
HÂˆYÙÊ¼'éîH\™ÙY	Üœ[œÔÛÛÜœ[™YÚÙ[ŠÊHÛˆÚ]İÛˆŠBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆYÙÊ¸¦¨;î#ÈÜœ[ˆ\™ÙH˜Z[Yˆ	ÙK›Y\ÜØYÙ_HŠBˆBˆBˆˆÊŠ‚ˆ
ˆ\š[ÙXÈÜœ[ˆØØ[ˆ\š[™È[[YK‚ˆ
ˆØ]Ú\ÈÚÙ[œÈ]˜Z[YÈÙ[[™\™HİXÚÈ[ˆØ[]‚ˆ
‹Âˆš]˜]H[ˆØØ[[™Ù[Üœ[œÊÎˆÛÛ[˜UØ[]
HÂˆHÂˆ˜[ÚÙ[XØÛİ[ÈHË™Ù]ÚÙ[XØÛİ[Ê
Bˆ˜[˜XÚÙYZ[ÈHŞ[˜Ú›Ûš^™Y
İ]\ËÚÙ[œÊHÂˆİ]\ËÚÙ[œË˜[Y\Âˆ™š[\ˆÈ]œÜÚ][Û‹š\ÓÜ[ˆBˆ›X\È]›Z[BˆÔÙ]

BˆBˆˆ˜\ˆÜœ[œÑ›İ[™Hˆ˜\ˆÜœ[œÔÛÛHˆˆÚÙ[XØÛİ[Ë™›Ü‘XXÚÈ
Z[]JHO‚ˆËÈÚÚ\XİX[\İ
\ÜÈ[ˆ	ŒH˜[YH\XØ[JBˆËÈ›ÜˆY[YHÚÙ[œË]™[ˆHÛİ[™HÚYÛšYšXØ[ˆËÈ™]\ˆÚÚ\Yˆ]H\È\ÜÙ[X[H™\›ÂˆYˆ
]HŒJH™]\››Ü‘XXÚˆËÈÚÚ\˜XÚÙYÜÚ][ÛœÂˆYˆ
Z[[ˆ˜XÚÙYZ[ÊH™]\››Ü‘XXÚˆËÈÚÚ\ÓÓˆYˆ
Z[OH”ÛÌLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLˆŠH™]\››Ü‘XXÚˆˆÜœ[œÑ›İ[™
ÊÂˆ˜[Ş[X›ÛHİ]\ËÚÙ[œÖÛZ[OËœŞ[X›ÛÎˆZ[ZÙJ
BˆYÙÊ¼'éîHÔ”Sˆ“ÕS‘ˆ	Ş[X›Û]OI]HZ[IÛZ[ZÙJLŠ_K‹‹ˆŠBˆˆHÂˆ˜[ÛÛH^Xİ]Ü‹œÙ[Üœ[™YÚÙ[ŠZ[]KÊBˆYˆ
ÛÛ
HÂˆÜœ[œÔÛÛ
ÊÂˆYÙÊ¸§!HÔ”SˆÓÓˆ	Ş[X›ÛŠBˆH[ÙHÂˆYÙÊ¸¦¨;î#ÈÔ”SˆÑSRSQˆ	Ş[X›ÛHÙ[X[X[HšXH\]\ˆŠBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆYÙÊ¸§cÔ”SˆT”“Ôˆ	Ş[X›ÛH	ÙK›Y\ÜØYÙ_HŠBˆBˆBˆˆYˆ
Üœ[œÑ›İ[™ˆ
HÂˆYÙÊ¼'éîHÜœ[ˆØØ[ˆ›İ[™	Üœ[œÑ›İ[™ÛÛ	Üœ[œÔÛÛŠBˆH[ÙHÂˆYÙÊ¸§!H›ÈÜœ[™YÚÙ[œÈ›İ[™ŠBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÂˆYÙÊ¸¦¨;î#ÈÜœ[ˆØØ[ˆ˜Z[Yˆ	ÙK›Y\ÜØYÙ_HŠBˆ\œ›Ü“ÙÙÙ\‹™\œ›ÜŠ›İÙ\šXÙH‹“Üœ[ˆØØ[ˆ\œ›Üˆ	ÙK›Y\ÜØYÙ_H‹JBˆBˆBˆˆÊŠ‚ˆ
ˆØ[İ[]HH˜]ÈÚYÛ˜[ØÛÜ™H›Üˆ›Ûİİ˜\[HXÚ\Ú[ÛœË‚ˆ
ˆ\È\È[™\[™[ÙˆŒÈØÛÜ™HH\Ù\È˜]ÈX\šÙ]ÚYÛ˜[ÈÛ›K‚ˆ
ˆ\ÙYÈ[İÈ›Ûİİ˜\˜Y\È]™[ˆÚ[ˆŒÈ™Z™XİÈHÚÙ[‹‚ˆ
‹Âˆš]˜]H[ˆØ[İ[]P›Ûİİ˜\ØÛÜ™Jˆ^T™\Üİ\™TİˆİX›Kˆ\]ZY]U\ÙˆİX›Kˆ[ÛY[[NˆİX›Kˆ›Û][]NˆİX›Kˆ
Nˆ[Âˆ˜\ˆØÛÜ™HHËÈ˜\ÙHØÛÜ™BˆˆËÈ^H™\Üİ\™H
[Üİ[\Ü[
BˆØÛÜ™H
ÏHÚ[ˆÂˆ^T™\Üİ\™TİHÌOˆÌˆ^T™\Üİ\™TİHŒOˆBˆ^T™\Üİ\™TİHLOˆŒˆ^T™\Üİ\™TİHOˆLˆ[ÙHOˆˆBˆˆËÈ\]ZY]BˆØÛÜ™H
ÏHÚ[ˆÂˆ\]ZY]U\ÙHLOˆMBˆ\]ZY]U\ÙHLOˆL‚ˆ\]ZY]U\ÙHÌOˆˆ\]ZY]U\ÙHMLOˆBˆ[ÙHOˆˆBˆˆËÈ[ÛY[[BˆØÛÜ™H
ÏHÚ[ˆÂˆ[ÛY[[HHŒOˆLˆ[ÛY[[HHLOˆÂˆ[ÛY[[HHHOˆˆ[ÛY[[HHOˆ‚ˆ[ÙHOˆˆBˆˆËÈ›Û][]H[˜[H
ÛÈ›Û][HHš\ÚŞJBˆØÛÜ™HOHÚ[ˆÂˆ›Û][]HHLOˆLˆ›Û][]HHÌOˆBˆ[ÙHOˆˆBˆˆ™]\›ˆØÛÜ™K˜ÛÙ\˜ÙR[ŠL
BˆBŸB‚‹ËÈ^[œÚ[Ûˆ[˜İ[Ûˆ›Üˆ›Ü›X][™ÈİX›\Âœš]˜]H[ˆİX›K™›]
XÚ[X[Îˆ[H
HH‰K‰ÙXÚ[X[ßYˆ‹™›Ü›X]
\ÊB‚‹ËÈKKN8 %™\ÛÛ™HH]™HšXÙH›ÜˆH[ÚÙ[ˆ[ˆHĞSQHš[Üš]HÜ™\‚‹ËÈ]H[šYšYYÜ[ˆÜÚ][ÛœÈØ\™\Ù\ËÛÈİX‹]˜Y\ˆÚXÚÑ^]

HØ[‚‹ËÈ™]™\ˆ\ØYÜ™YHÚ]HRHH˜[[™È˜XÚÈÈ[TšXÙH
[™™]\›š[™Â‹ËÈÓ›Ü™]™\ŠHÚ[HHØ\™ÚİÜÈLÉKˆÛİ\˜ÙHÜ™\‚‹ËÈKˆË›\İšXÙH
[Üİ™XÙ[^ÛÜ˜XÛHXÚÊB‹ËÈ‹ˆËš\İÜK›\İ
\İ\œÚ\İYØ[™JB‹ËÈËˆİX‹]˜Y\ˆ\İÙY[”šXÙH
Ú]ÛÚ[ˆÈ[ÛÛœÚİÈ]X[]HÈ›YPÚ\
B‹ËÈˆËœÜÚ][Û‹™[TšXÙH
š[˜[˜[˜XÚÈ8 %Ø[YH\ÈÛ™Z]š[İ\ŠBš[\›˜[[ˆ™\ÛÛ™S]™TšXÙJÎˆÛÛK›Y™XŞXÛX›İ™]K•ÚÙ[”İ]JNˆİX›HÂˆ˜[ÔšXÙHHË›\İšXÙKZÙRYˆÈ]ˆŒBˆÎˆËš\İÜK›\İÜ“[

OËœšXÙU\ÙËZÙRYˆÈ]ˆŒBˆYˆ
ÔšXÙHOH[	‰ˆÔšXÙHˆŒ
H™]\›ˆÔšXÙB‚ˆ˜[Z[HË›Z[ˆ˜[İX”šXÙHHHÂˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”Ú]ÛÚ[•˜Y\RK™Ù]Xİ]™TÜÚ][ÛœÊ
Bˆ™š\œİÜ“[È]›Z[OHZ[OË›\İÙY[”šXÙOËZÙRYˆÈ]ˆŒBˆÎˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë“[ÛÛœÚİ˜Y\RK™Ù]Xİ]™TÜÚ][ÛœÊ
Bˆ™š\œİÜ“[È]›Z[OHZ[OË›\İÙY[”šXÙOËZÙRYˆÈ]ˆŒBˆÎˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë”]X[]U˜Y\RK™Ù]Xİ]™TÜÚ][ÛœÊ
Bˆ™š\œİÜ“[È]›Z[OHZ[OË›\İÙY[”šXÙOËZÙRYˆÈ]ˆŒBˆÎˆÛÛK›Y™XŞXÛX›İŒËœØÛÜš[™Ë›YPÚ\˜Y\RK™Ù]Xİ]™TÜÚ][ÛœÊ
Bˆ™š\œİÜ“[È]›Z[OHZ[OË›\İÙY[”šXÙOËZÙRYˆÈ]ˆŒBˆHØ]Ú
Îˆ›İØX›JHÈ[BˆYˆ
İX”šXÙHOH[	‰ˆİX”šXÙHˆŒ
H™]\›ˆİX”šXÙB‚ˆ™]\›ˆËœÜÚ][Û‹™[TšXÙBŸB‹ËÈZ[šYÙÙ\ˆMÍÍŒÍŒN‹ËÈZ[šYÙÙ\ˆMÍÍNB‹ËÈZ[šYÙÙ\ˆKKN‚‹ËÈKKM^ŒM8 %Ü[]™[Ø[\HÛİ[\ˆ›Üˆ\š[ÙXÈ[][K][YYœ˜[YB‹ËÈÛX\Ú\ØØ[œËˆ]™\È]š[HØÛÜH
›İ[œÚYHÛ\ÜÈ›İÙ\šXÙJHÛÈB‹ËÈÜ[]™[›ØÙ\ÜÕÚÙ[ŞXÛJ
H[˜İ[ÛˆØ[ˆXØÙ\ÜÈ]ˆ]™\HL[›ØØ][Û‚‹ËÈ[œÈÛX\Ú\ØØ[›™\‹œØØ[Š
H][KUˆÛÈÛ™Ù\‹ZÜš^›Ûˆ]\›œÂ‹ËÈ
İ\	ˆ[™KÙYÙ\ËXYØ]›İ[˜Ùx )ŠHØ[ˆXİX[Hš\™K‚œš]˜]H˜[ÛX\Ú\ØØ[Ûİ[\ˆH˜]˜K][˜ÛÛ˜İ\œ™[˜]ÛZXË]ÛZXÓÛ™Ê
B