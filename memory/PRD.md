# AATE — Native Kotlin Solana Trading Bot

## Original Problem Statement
Native Kotlin Android Solana trading bot (Fork session). Build a super-smart, multi-asset, multi-lane AI trading platform — Memes (Solana on-chain), CryptoAlt perps, tokenized stocks, commodities, metals, forex, leverage. Continuous auto-replay learning, sentient AI personality, fluid exit reasoning, LLM Lab strategy sandbox.

**Core constraint: NO LOCAL COMPILER.** GitHub Actions CI is the sole build system — all code changes must be pushed to `main` to trigger the APK build, which we poll for success/failure before declaring a fix done.

## Architecture
```
/app/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/
├── ui/                # MainActivity, MultiAssetActivity, BrainNetworkView
├── engine/            # BotService (~12.2k lines), Executor, TradingCopilot, TreasuryManager,
│                      # SolanaMarketScanner, AntiChokeManager, MemeMintRegistry,
│                      # PaperWalletStore, GlobalTradeRegistry, TokenMergeQueue,
│                      # TokenLifecycleTracker
├── engine/sell/       # PartialSellMismatchDetector, PriceResolverFallback, LiveWalletReconciler,
│                      # PumpPortalKillSwitch, RealizedPnLCalculator
├── perps/             # CryptoAltTrader, MarketsTrader, TokenizedStockTrader, MetalsTrader,
│                      # ForexTrader, CommoditiesTrader, PerpsExecutionEngine, PerpsTraderAI,
│                      # InsiderWalletTracker
├── v3/scoring/        # ShitCoinTraderAI, MoonshotTraderAI, FluidLearningAI, CashGenerationAI,
│                      # CapitalEfficiencyAI, CorrelationHedgeAI, DrawdownCircuitAI,
│                      # ExecutionCostPredictorAI, FundingRateAwarenessAI, LiquidityExitPathAI,
│                      # MEVDetectionAI, NewsShockAI, OperatorFingerprintAI
├── network/           # PumpFunWS (PumpPortal), PumpFunDirectApi, SolanaWallet
└── data/              # Models, TokenState, ConfigStore, BotConfig
```

## Recent Build History (latest first)
- **V5.9.647** (2026-05-09) — Fixed startBot/onStartCommand guard (third smoking gun in this session).
  Operator screenshot V5.9.646 showed Watchlist (44) tokens flowing in via the V5.9.646 self-heal,
  but every entry was IDLE +0.0% — even `BlueChipAI: BLUE CHIP QUALIFIED: TRUMP score=70 conf=90% size=0.3210 SOL`
  never executed. Root cause: `BotViewModel.kt:147` pre-sets `BotService.status.running = true` for instant UI feedback
  BEFORE the foreground service starts. Then `BotService.onStartCommand` line 1133 checks
  `else if (!status.running)` which evaluates FALSE → `scope.launch { startBot() }` is NEVER called.
  Even if it were, `startBot()` line 1803 had `if (status.running) return` which would also short-circuit.
  Net effect: every single Start tap was a no-op for the actual trade-execution loop. Sub-traders
  (CryptoAlt/Markets/Forex/Metals/Commodities/Perps/Insider/Replay) auto-start in onCreate so they
  kept running, the V5.9.646 onCreate scanner self-heal kept feeding the watchlist, but the meme/V3
  trade-execution path (BlueChip qualifications, ShitCoin/Moonshot scoring, FluidLearningAI, FDG
  decisions, wallet buys) was permanently dead because all of those live inside `botLoop()` which
  was the only thing that didn't run. Fix: 3 guards changed from `status.running` to
  `loopJob?.isActive` (the actual source of truth for whether botLoop is running). The ViewModel
  pre-set is now harmless. CI #2528 ✅ green.
- **V5.9.646** (2026-05-09) — onCreate-anchored meme scanner self-heal. Operator log dump V5.9.645
  showed every other lane alive but no scanner logs at all (no `🛡 Meme intake gate`, no
  `Creating market scanner...`, no `🩺 SCANNER_HEARTBEAT`, no `🩹 Self-heal`). startBot was never
  called in that 30-second window, so all V5.9.645 hooks (which lived inside startBot/botLoop)
  never fired. Fix: anchor the scanner self-heal in onCreate via a long-running scope.launch
  coroutine that fires bootMemeScanner() every 30s after a 15s settle delay, gated only on
  `!isManualStopRequested && memeWanted` (no dependency on status.running). Scanner now self-heals
  whenever the BotService is alive, regardless of whether startBot ran. CI #2527 ✅ green.
  → Operator confirmed V5.9.646 worked: Watchlist (44), 238 mints scanned, MemeMintRegistry
  persisted 45 mints, scanner pipeline `SRC 5/5/0 → RAW 65 → ENQ 65 → MQ 44 → WL 44` healthy.
- **V5.9.645** (2026-05-09) — Self-healing meme scanner + 🩺 heartbeat. Added bootMemeScanner(),
  replaced inert-watchdog null-branch dead-end with a self-heal call, added 30s post-startup check,
  added 🩺 SCANNER_HEARTBEAT every ~30s in botLoop. Partially worked but symptom remained because
  startBot itself wasn't running (root cause not yet found). CI #2526 ✅ green.
- V5.9.644 — SmartSizer + MemeEdgeAI + FDG bootstrap fixes (parallel fork agent, not us).
- V5.9.638 — restore pre-1900 direct meme intake (parallel fork agent).
- V5.9.636 → V5.9.642b — sequence of "restore" attempts by parallel fork agents.
- **V5.9.632** (2026-05-09) — Closed two residual Meme intake feed gates that V5.9.631 missed:
  PumpFunWS `onNewToken` and DataOrchestrator `onNewTokenDetected`. Both now include
  `|| status.running` so they have parity with the scanner gate (line 2204) and the
  botLoop meme gate (line 4651). Symptom fixed: Meme Trader showed 0 tokens / 0 trades
  while every other desk ran normally because PumpPortal — the highest-volume launch
  feed — was silently rejecting tokens when memeTraderEnabled=false + tradingMode=1
  (Markets) + autoAdd/V3/autoTrade=false. CI ✅ green.
- V5.9.631 — Restored known-good meme lane authority (added `|| status.running` to
  startBot scanner gate + botLoop meme gate; rebuilt inert-watchdog HARD branch).
- V5.9.630 — Hydrate paper balance on cold open.
- V5.9.629 — Restore Solana wide-feed PumpPortal intake.
- V5.9.628 — Force protected meme scanner startup (MemeMintRegistry hydrate at boot).
- V5.9.626 — Canonical protected meme intake (DataOrchestrator routes through admit).
- V5.9.625 — Fail-open meme intake before gates.
- V5.9.624 — Non-destructive meme intake fairness.
- V5.9.623 — Protect scanner watchlist intake pool.
- V5.9.621 — Paper Ghost Auto-Purge + Inert-Loop Watchdog.

## Active Issues / Pending User Verification
- **P0** Verify V5.9.647 actually unblocks the trade-execution loop on the user's device.
  Expected post-fix on next install: within 60s of tapping Start, the log should show:
    • `BotService: startBot() called` (first ErrorLogger.info inside startBot)
    • `BotService: Foreground service started`
    • `BotService: botLoop() started`
    • At least one `🩺 SCANNER_HEARTBEAT` line every ~30s
    • Trade-execution lines following any BlueChip/Quality/ShitCoin/Moonshot qualification
    • Watchlist entries transitioning from IDLE to BUYING/HOLDING
  And the Recent Trades section should populate within a few minutes.
- **P0** If V5.9.647 STILL shows 'No trades yet' after botLoop starts, the next step is to find what
  rejection layer is blocking entries. The 🩺 heartbeat will show if scanner is feeding the queue.
  Then look for FDG/TradingCopilot/AntiChoke decisions in the log.

## Backlog (P1/P2/P3)
- P1: True Leverage for Markets Lane (Drift/Parcl/Mango via Kotlin HTTP)
- P2: "Strategy Leaderboard Tile" on the main UI showing live top-3 strategies by current expectancy
- P2: "PnL Streak tile" showing rolling 24h realised SOL gain/loss
- P2: "Ladder" status pill on Memes tab (TIER 1 / 2 / 3 progressive QualityLadder phase)
- P2: "Brain Health" pill (STEADY / DRIFTING / POISONED / EXCELLENT)
- P3: Drift alert at top of UI: "⚠ 24h PnL lags proof-run by -1.2%"
- P3: "Tune History" UI tab under Behavior to view/revert LLM parameter nudges
- P3: `/positions backup` export button to dump PerpsPositionStore JSON

## Refactoring Backlog
- BotService.kt is ~12,270 lines. Eventually extract:
  - Scanner construction block (lines 2179-2438) → `private fun bootMemeScanner()`,
    callable from startBot AND inert-watchdog null branch (currently only logs and
    asks operator to restart).
  - botLoop has been partially extracted (V5.9.495z53/54). Continue extracting helpers.

## Tech Stack / Integrations
- GitHub Actions (CI)
- Jupiter API V6 (pricing/routing)
- PumpPortal WS + HTTP, Pump.fun direct REST
- DexScreener, Birdeye, Pyth, Raydium, GeckoTerminal, Meteora, CoinGecko (price/discovery)
- Helius Enhanced WS (whale wallet pushes, optional)
- Pyth Hermes SSE (~400ms SOL/BTC/ETH pricing)
- EmergentLlmClient → Claude-Sonnet-4.5 (opt-in risk validator + exit narrator)

## Critical Operator Rules
- NO LOCAL COMPILER: every change must be `git push`-tested on GitHub Actions.
- Brace check before push: `tr -cd '{' < BotService.kt | wc -c` vs `}` must match.
- Don't choke the bot: avoid adding new aggressive gates/filters; the user has been
  burned multiple times by silent OR-gates dropping volume to zero.
- IGNORE CRYPTO UNIVERSE for now per user's prior directive — focus is Meme Trader
  live/paper execution.
