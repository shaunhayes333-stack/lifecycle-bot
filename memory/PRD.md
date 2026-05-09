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
- **P0** Verify V5.9.632 actually unblocks Meme Trader on the user's device. Awaiting
  fresh log dump from operator after install. Expected post-fix: "🆕 PumpPortal
  protected intake: …" lines and Meme Trader positions > 0 within minutes.
- **P0** If V5.9.632 still shows 0 meme tokens, dig into: (a) is the user's log filter
  hiding the BotService/Scanner/PumpFunWS tags? (b) is SolanaMarketScanner.start()
  itself failing silently and the catch swallowing it? (c) is the inert-watchdog
  null-recreate path ever running?

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
