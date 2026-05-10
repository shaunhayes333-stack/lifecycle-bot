# AATE — Native Kotlin Android Solana Trading Bot

## Original Problem Statement
Upgrading a Native Kotlin Android Solana trading bot (AATE) to V5.7+ (now at V5.9.661+).
Builds via GitHub Actions CI only — NO local compiler. Multi-lane architecture (Memes,
Crypto/Alts, Stocks, Markets, Tokenized Stocks, Forex, Metals, Commodities). Runs as a
foreground Service with a 50+ AI-module pipeline gated through processTokenCycle and
9 trading layers.

## Architecture (Key Files)
- engine/BotService.kt              — central engine (~12,950 lines, JVM 64KB-managed)
- engine/Executor.kt                — buy/sell, closeAllPositions, liveSweepWalletTokens
- engine/SmokeTestReceiver.kt       — debug-only CI broadcast entry-point (V5.9.661b)
- ui/MainActivity.kt + activity_main.xml — main UI, btnToggle = "Start Bot"
- ui/SecurityActivity.kt            — PIN gate (smoke test bypasses via pre-seeded prefs)
- v3/scoring/{ShitCoin,Moonshot,BlueChip,Quality,Manipulated,CashGen}TraderAI.kt
- perps/{CryptoAltTrader,MarketsTrader,TokenizedStockTrader,...}.kt
- ci/runtime-test.sh + .github/workflows/runtime-test.yml — Android emulator smoke test

## Recent Sessions — Implementation History

### V5.9.659 → V5.9.660b (May 10) — JVM 64KB botLoop fix
- Extracted Markets Engine Watchdog (~85 lines) into runMarketsEngineWatchdog()
- Extracted Scanner Heartbeat (~30 lines) into runScannerHeartbeat()
- Refactored emitBotLoopTick() to use class field for prev-cycle delta
- Tightened .gitignore so /backend, /frontend, node_modules don't leak into commits
- 660b fixed call-site mismatch (2-arg → 1-arg) — CI green

### V5.9.661 (May 10) — Unconditional position close on every stop
- Removed `if (cfg.closePositionsOnStop)` gate in stopBot() (line ~3450)
- Removed same gate in onDestroy() (line ~1192)
- Result: every stop now executes paperSell/liveSell + liveSweepWalletTokens +
  purgeOrphanedTokensOnStop, regardless of the legacy toggle.
- The `cfg.closePositionsOnStop` field is kept in BotConfig for backward-compat
  but is now a no-op.

### V5.9.661b (May 10) — Runtime Smoke Test actually starts the bot
- New SmokeTestReceiver.kt: debug-only BroadcastReceiver, action
  `com.lifecyclebot.aate.SMOKE_AUTOSTART`. Hard-guarded on FLAG_DEBUGGABLE.
  Pre-seeds aate_security PIN bypass + forces paper_mode=true + starts BotService
  with EXTRA_USER_REQUESTED=true.
- runtime-test.sh: broadcast (path A) + UI tap of btnToggle (path B fallback).
- Funnel summary now tracks SMOKE / BOT_LOOP_TICK / SCAN_CB / TRADEJRNL_REC.

## Known Issues / In-Progress
- P0: Verify V5.9.661 actually closes paper SOL + live tokens on operator's device.
- P0: Verify V5.9.661b smoke test produces non-zero BOT_LOOP_TICK + SCAN_CB on CI emulator.
- P1: True leverage for Markets lane (Drift/Parcl/Mango HTTP). Not started.
- P2: Strategy Leaderboard tile, PnL streak tile, Brain Health pill, Tune History tab.
- P2: "Ladder" status pill on Memes tab.

## Critical Operator Mandates
- NO LOCAL COMPILER. All changes pushed via Git → GitHub Actions CI.
- Brace counting before push (grep -c '{' vs '}') is mandatory.
- BotService.kt is at the JVM 64KB cap on botLoop — extract before adding inline blocks.
- Position close on stop must be UNCONDITIONAL (V5.9.661).
- Smoke test must actually start the bot (V5.9.661b) — UI-only launches don't count.

## 3rd Party Integrations
- GitHub Actions (CI + Android emulator runtime smoke test)
- PumpPortal WS, Birdeye, Pyth, DexScreener, Jupiter API V6, Binance, CoinGecko

## Tech Stack
- Native Kotlin Android Application
- SQLite (TradeHistoryStore, LearningPersistence)
- SharedPreferences (RUNTIME_PREFS, bot_config, aate_security)
- AGP / Gradle 8.7.0
