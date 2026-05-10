# AATE — Native Kotlin Android Solana Trading Bot

## Original Problem Statement
Native Kotlin Android Solana trading bot. Builds via GitHub Actions CI only —
NO local compiler. Multi-lane architecture (Memes [9 sub-lanes], Crypto/Alts,
Stocks, Markets, Tokenized Stocks, Forex, Metals, Commodities). Foreground
Service with a 50+ AI-module pipeline gated through processTokenCycle.

## Architecture (Key Files)
- engine/BotService.kt              — central engine, watchlist tick cadence (V5.9.663c)
- engine/Executor.kt                — buy/sell, closeAllPositions, liveSweepWalletTokens
- engine/SmokeTestReceiver.kt       — debug-only CI broadcast entry-point (V5.9.661b)
- engine/TokenLifecycleTracker.kt   — clearAll() V5.9.661c
- engine/HostWalletTokenTracker.kt  — clearAll() V5.9.661c
- engine/LifecycleStrategy.kt       — paper-mode confidence floors (V5.9.662)
- engine/TradeAuthorizer.kt         — UNKNOWN_QUALITY paper-learning floor (V5.9.662b)
- engine/BehaviorLearning.kt        — BIG LOSS log demoted to debug (V5.9.662d)
- engine/TreasuryManager.kt         — paper dust floor 0.0001 SOL (V5.9.663b)
- ui/MainActivity.kt + activity_main.xml — main UI, btnToggle, etActiveToken (sole owner)
- ui/SettingsBottomSheet.kt + dialog_settings.xml — Settings (etActiveToken removed V5.9.663)
- ui/BotViewModel.kt                — UI poll cadence 2500ms (V5.9.663b)
- v3/scoring/MoonshotTraderAI.kt    — minRcScore=1 paper learning (V5.9.662)
- v3/scoring/QualityTraderAI.kt     — paper-bootstrap minAge=1min (V5.9.663b)
- v3/scoring/BehaviorAI.kt          — trade-maturity ramp on paper penalties (V5.9.662d)
- v3/risk/FatalRiskChecker.kt       — paper-learning rugScore bypass (V5.9.662)
- v3/scoring/{ShitCoin,BlueChip,CashGen,Manipulated}TraderAI.kt
- perps/{CryptoAltTrader,MarketsTrader,TokenizedStockTrader,...}.kt
- ci/runtime-test.sh + .github/workflows/runtime-test.yml — Android emulator smoke test

## Implementation History — Recent Sessions

### V5.9.659 → V5.9.660b — JVM 64KB botLoop fix (May 10)
Extracted Markets watchdog + Scanner heartbeat as helpers; fixed signature mismatch.

### V5.9.661 — Unconditional position close on every stop ✅ user-verified (May 10)
Removed `if (cfg.closePositionsOnStop)` gate in stopBot() and onDestroy().

### V5.9.661b — Runtime Smoke Test actually starts the bot (May 10)
New SmokeTestReceiver.kt + UI tap fallback; funnel summary upgrade.

### V5.9.661c — UI "11 Open" stale counter fix (May 10)
TokenLifecycleTracker.clearAll() + HostWalletTokenTracker.clearAll().

### V5.9.662 → V5.9.662d — Unblock Moonshot/Quality/BlueChip/V3 lanes (May 10)
- 662   : MoonshotTraderAI minRcScore 5→1 paper; FatalRiskChecker paper-learning bypass.
- 662b  : TradeAuth UNKNOWN_QUALITY paper floor 5→1.
- 662d  : Replaces 662c (over-zeroed). Trade-maturity RAMP: 5% pre-3000, linear 3000-4999,
          100% from 5000+. Stats counters always increment so learning still happens.
- BIG LOSS log spam → debug.

### V5.9.663 — Settings cleanup (May 10)
Removed ACTIVE TOKEN field from dialog_settings.xml + SettingsBottomSheet.kt.

### V5.9.663b — Quality lane unblock + 70/30 paper dust floor + UI poll throttle (May 10)
- QualityTraderAI: paper-bootstrap minAge 5min → 1min. Was the choke for 9th lane.
- TreasuryManager: split MEME_SELL_MIN_PROFIT_SOL into live (0.003) + paper (0.0001).
  Paper-mode 70/30 split was effectively dead because every win < 0.003 SOL.
- BotViewModel.pollLoop: 1500ms → 2500ms (initial ANR mitigation).

### V5.9.663c — Open-position priority + watchlist 3-tick cadence (May 10) ⭐ operator-designed
- BotService.botLoop: forcedOpenMints (open positions) processed every tick;
  otherMints (watchlist) processed only when `loopCount % 3 == 0` (~3 seconds).
- Drops main-thread contention from cascading status.tokens reads by ~3x for the
  user's ~2400-mint watchlist while preserving fluid stop / TP / partial-sell
  responsiveness on open positions.

## Known Issues / In-Progress (PRIORITY ORDER)

### P0 — Verify on device (V5.9.663c build)
- ANR ("AATE isn't responding"): user-designed fix shipped. Confirm no longer happens.
- 70/30 split: confirm treasury balance grows in paper mode (look for log
  `🪙 70/30 SPLIT: profit=Xsol → treasury +Y` at debug or info level).
- Quality lane: confirm `[⭐ QUALITY ENTRY]` lines start appearing alongside
  ShitCoin entries on fresh launches (~1min old).
- Lane indicator strip: should diversify from `M-only` to `M / A / P / S` populated.

### P1 — Markets lane signals not converting to BUY
- Logs show `📈 SIGNAL: META score=65 conf=50 dir=SHORT` (and ORCL/NFLX similarly)
  but no executions follow. Investigate Executor accept criteria for Markets signals.

### P1 — True leverage for Markets lane (Drift/Parcl/Mango HTTP). Not started.

### P2 — UI Polish
- Strategy Leaderboard tile, PnL streak tile, Brain Health pill, Tune History tab.
- "Ladder" status pill on Memes tab.
- "Maturity" pill next to proof-run header showing TRADES X/5000 + STRICTNESS Y%.

## V5.9.664 — V5.9.665b changelog (May 10, 2026)

### V5.9.664 — Scanner ANR throttle
Doubled inter-source delay between deep-scan calls from 100ms to 200ms in
SolanaMarketScanner.kt. Conservative throttle, no logic changes.

### V5.9.665 — restore live trading: 4 surgical regression fixes
Operator forensics_20260511_010802 + live test report:
1. Meme trader did nothing in live mode.
2. Bought TRUMP "didn't land" (Jupiter confirmed but bot rejected swap).
3. Tried SHIB twice → bought USDC (USDC-collateral masquerade).
4. Bot freezes + ANR warnings the moment user switches to live.

Troubleshoot agent RCA identified three regressions:
- 758ecca26 (May 8) added `executeUsdcCollateralExposure()` and stripped
  the resolver's bridge/CEX/paper-only fallback, causing live trades to
  silently terminate at USDC.
- V5.9.661 wipes HostWalletTokenTracker on stop before
  LiveWalletReconciler can capture late-landing swaps.
- WalletPositionLock global 80% cap can be entirely consumed by one lane,
  starving memes when CryptoUniverse fires heavily.

Plus a fourth issue surfaced from the user's live-start ANR symptom:
- V5.9.430 STARTUP_SWEEP_HARD_FLOOR ran synchronously on the startBot()
  body, walking every restored position with blocking network I/O.

Fixes (V5.9.665):
- **Fix #1**: Clean revert of 758ecca26 (CryptoUniverseRouteResolver,
  CryptoUniverseExecutor, MarketsLiveExecutor). Removes
  executeUsdcCollateralExposure entirely; restores branched
  Bridge/CEX/Paper fallback when no SPL mint resolves.
- **Fix #2**: CryptoUniverseExecutor.kt verifyWalletDelta extended
  5×3s = 15s → 8×3s = 24s. On timeout, register the buy via
  HostWalletTokenTracker + LiveWalletReconciler and return
  Outcome.Executed with phase log `CU_DELTA_LATE_TRUST_SIG` instead
  of false-rejecting confirmed Jupiter swaps.
- **Fix #2b**: BotService.kt stop path now calls
  LiveWalletReconciler.reconcileNow(wallet, "stop_pre_tracker_clear")
  immediately before TokenLifecycleTracker.clearAll() /
  HostWalletTokenTracker.clearAll() so late-landing swaps get
  captured into the position store before the trackers are wiped.
- **Fix #3**: WalletPositionLock per-lane reservation caps added —
  Meme=50%, CryptoAlt=40%, Stocks=30%, Commodities/Metals/Forex=20%.
  No lane can ever be starved below its own reservation by another
  lane consuming the global 80% cap.
- **Fix #5 (live-start ANR)**: BotService.kt STARTUP_SWEEP_HARD_FLOOR
  now wrapped in `scope.launch` with a 5s startup grace and a 250ms
  delay between positions. Bot loop launches first; sweep runs
  asynchronously in the background.

### V5.9.665b — fix unit test for restored route-resolver contract
CryptoUniverseRouteResolverTest rewritten to validate the restored
contract (BTC routes to JUPITER_ROUTABLE if wrapped mint registered,
else PAPER_ONLY + executable=false) instead of the regressed
"always executable, mint=null" path.

CI status: Build ✅ + Runtime Smoke Test ✅ both green on commit a5ff2f32e.
Smoke test pipeline counters improved vs prior runs:
LANE_EVAL 11→46, V3 89→114, EXECUTE/BUY 3437→3608, JRNL 188→215.

## Critical Operator Mandates
- NO LOCAL COMPILER. All changes via Git → GitHub Actions CI.
- Brace counting before push (grep -c '{' vs '}') is mandatory.
- BotService.kt is at the JVM 64KB cap on botLoop — extract before adding inline blocks.
- Position close on stop must be UNCONDITIONAL (V5.9.661).
- **V5.9.665**: BEFORE clearAll() of trackers on stop, call
  LiveWalletReconciler.reconcileNow() to capture late-landing swaps.
- Smoke test must actually start the bot (V5.9.661b) — UI-only launches don't count.
- All lanes (not just ShitCoin) must collect paper-learning samples (V5.9.662 family).
- Penalty strictness scales with trade maturity (path to 5000 trades) (V5.9.662d).
- Open positions get refresh priority over watchlist scanning (V5.9.663c).
- **V5.9.665**: Never run synchronous network I/O on startBot()'s body —
  always wrap in scope.launch with a startup grace.
- ALWAYS pull and read smoke test artifacts (`logcat_full.txt` + `funnel_summary.txt`)
  before commenting on pipeline state. Do not assume — read.

## 3rd Party Integrations
- GitHub Actions (CI + Android emulator runtime smoke test)
- PumpPortal WS, Birdeye, Pyth, DexScreener, Jupiter API V6, Binance, CoinGecko,
  Yahoo Finance V8 (stocks)

## Tech Stack
- Native Kotlin Android Application
- SQLite (TradeHistoryStore, LearningPersistence)
- SharedPreferences (RUNTIME_PREFS, bot_config, aate_security)
- AGP / Gradle 8.7.0
