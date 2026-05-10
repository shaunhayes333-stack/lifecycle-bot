# AATE — Native Kotlin Android Solana Trading Bot

## Original Problem Statement
Native Kotlin Android Solana trading bot. Builds via GitHub Actions CI only —
NO local compiler. Multi-lane architecture (Memes, Crypto/Alts, Stocks, Markets,
Tokenized Stocks, Forex, Metals, Commodities). Foreground Service with a 50+
AI-module pipeline gated through processTokenCycle and 9 trading layers.

## Architecture (Key Files)
- engine/BotService.kt              — central engine (~12,950 lines, JVM 64KB-managed)
- engine/Executor.kt                — buy/sell, closeAllPositions, liveSweepWalletTokens
- engine/SmokeTestReceiver.kt       — debug-only CI broadcast entry-point (V5.9.661b)
- engine/TokenLifecycleTracker.kt   — clearAll() V5.9.661c
- engine/HostWalletTokenTracker.kt  — clearAll() V5.9.661c
- engine/LifecycleStrategy.kt       — paper-mode confidence floors (V5.9.662)
- engine/TradeAuthorizer.kt         — UNKNOWN_QUALITY paper-learning floor (V5.9.662b)
- engine/BehaviorLearning.kt        — BIG LOSS log demoted to debug (V5.9.662d)
- ui/MainActivity.kt + activity_main.xml — main UI, btnToggle = "Start Bot",
                                      etActiveToken still here (sole owner)
- ui/SettingsBottomSheet.kt + dialog_settings.xml — Settings (etActiveToken removed
                                      V5.9.663)
- ui/SecurityActivity.kt            — PIN gate (smoke test bypasses via pre-seeded prefs)
- v3/scoring/MoonshotTraderAI.kt    — minRcScore=1 for paper learning (V5.9.662)
- v3/scoring/BehaviorAI.kt          — trade-maturity ramp on paper penalties (V5.9.662d)
- v3/risk/FatalRiskChecker.kt       — paper-learning rugScore bypass (V5.9.662)
- v3/scoring/{ShitCoin,BlueChip,Quality,Manipulated,CashGen}TraderAI.kt
- perps/{CryptoAltTrader,MarketsTrader,TokenizedStockTrader,...}.kt
- ci/runtime-test.sh + .github/workflows/runtime-test.yml — Android emulator smoke test
- .github/workflows/build.yml       — APK build pipeline

## Implementation History — Recent Sessions

### V5.9.659 → V5.9.660b — JVM 64KB botLoop fix (May 10)
- Extracted Markets Engine Watchdog + Scanner Heartbeat as helpers.
- 660b fixed call-site mismatch (2-arg → 1-arg) — CI green.

### V5.9.661 — Unconditional position close on every stop ✅ user-verified (May 10)
- Removed `if (cfg.closePositionsOnStop)` gate in stopBot() and onDestroy().

### V5.9.661b — Runtime Smoke Test actually starts the bot (May 10)
- New SmokeTestReceiver.kt (action `com.lifecyclebot.aate.SMOKE_AUTOSTART`, hard-guarded).
- runtime-test.sh: broadcast (path A) + UI tap of btnToggle (path B fallback).
- Funnel summary now tracks SMOKE / BOT_LOOP_TICK / SCAN_CB / TRADEJRNL_REC.

### V5.9.661c — UI "11 Open" stale counter fix (May 10)
- New `TokenLifecycleTracker.clearAll()` and `HostWalletTokenTracker.clearAll()`,
  called from stopBot().

### V5.9.662 → V5.9.662d — Unblock Moonshot/Quality/BlueChip lanes (May 10)
- 662   : MoonshotTraderAI minRcScore 5→1 paper; FatalRiskChecker paper-learning
          bypass on EXTREME_RUG_RISK_100 + EXTREME_RUG_CRITICAL when secondary
          rug flags are clean (hard 0..2 still blocks); LifecycleStrategy paper
          conf floors 5%/10% → 1%.
- 662b  : TradeAuthorizer UNKNOWN_QUALITY_*_conf_below_5 floor lowered to 1
          in paper mode (this gate was catching Moonshot's ORBITAL/LUNAR/MARS
          quality strings).
- 662d  : Replaces 662c (which over-zeroed). Trade-maturity RAMP on paper
          tilt/discipline penalties: pre-3000 trades = 5%, 3000..4999 linear
          ramp 5→100%, 5000+ = full strictness. Stats counters always
          incremented so learning still happens.
- BIG LOSS log spam → demoted to debug.

### V5.9.663 — Settings cleanup (May 10)
- Removed ACTIVE TOKEN field from dialog_settings.xml + SettingsBottomSheet.kt.
- Same EditText still lives on activity_main.xml (sole owner now).

## Known Issues / In-Progress (PRIORITY ORDER)

### P0 — Verify on device (V5.9.662d + V5.9.663 build)
- Confirm Moonshot / Quality / BlueChip lanes start firing alongside ShitCoin.
  Smoke V5.9.662 still showed 100% LANE_EVAL=SHITCOIN (51× AUTH_DENIED
  resolved by 662b).
- Confirm penalty log spam stops (BIG LOSS at debug level).
- Confirm Settings sheet renders without ACTIVE TOKEN field.

### P0 — ANR ("AATE isn't responding")
- Hypothesis: `MainActivity.renderShitCoinPositions()` (and 4 sibling lane
  renderers) call `removeAllViews()` + create fresh LinearLayout/ImageView
  every UI tick (~1s). With 11+ open positions across 5 lanes that's
  ~50 view rebuilds/sec on the main thread. Smoke already showed
  "Choreographer Skipped 44 frames" at init.
- NOT yet fixed (avoiding speculative push without device verify).
- Proposed fix: diff lane mint set/P&L numbers before rebuild; rebuild only
  on change (with periodic full-rebuild every 10 ticks for safety).
  Also throttle UI tick from ~1s to ~2s.

### P1 — Markets lane signals not converting to BUY
- Logs show `📈 SIGNAL: META | score=65 conf=50 dir=SHORT` (and ORCL/NFLX
  similarly) but no executions follow. Investigate Executor's accept
  criteria for Markets lane signals.

### P1 — True leverage for Markets lane (Drift/Parcl/Mango HTTP). Not started.

### P2 — UI Polish
- Strategy Leaderboard tile, PnL streak tile, Brain Health pill, Tune History tab.
- "Ladder" status pill on Memes tab.

## Critical Operator Mandates
- NO LOCAL COMPILER. All changes via Git → GitHub Actions CI.
- Brace counting before push (grep -c '{' vs '}') is mandatory.
- BotService.kt is at the JVM 64KB cap on botLoop — extract before adding inline blocks.
- Position close on stop must be UNCONDITIONAL (V5.9.661).
- Smoke test must actually start the bot (V5.9.661b) — UI-only launches don't count.
- All lanes (not just ShitCoin) must collect paper-learning samples (V5.9.662 family).
- Penalty strictness scales with trade maturity (path to 5000 trades) (V5.9.662d).
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
