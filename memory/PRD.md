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
- **V5.9.654** (2026-05-09) — 10-Point Triage #1: CryptoPositionState bucket purity. Fixed `paper=45 / positions=1`
  drift bug where `record()` only added to buckets and never released on close — every symbol ever held this
  session accumulated, making the diagnostic line completely detached from reality. Added `replaceBucket()` method
  for atomic per-cycle rebuild from the real `positions` map; wired it into `runScanCycle` and added
  belt-and-braces `release()` in `closePosition`. This also retroactively explains why V5.9.653's cap bump felt
  unnecessary: paper=45 was a stale historical count, not real cap pressure. CI ✅ green.
- **V5.9.653** (2026-05-09) — Bumped CryptoAlt position caps for aggressive bootstrap learning. Operator complaint
  "it bought one single token. all of the memetrader and crypto trader are meant to be trading early in bootstrap
  so they learn and start adjusting". Root cause: CryptoAltTrader.MAX_POSITIONS=50 / SOFT_CAP=40 (V5.9.219b "user
  preference") was throttling aggressive entry. Operator screenshot showed paper=45/50 — 5 slots free, hence
  execSignals=1 per cycle. Reverted to V5.9.654 originals: MAX=100, SOFT_CAP=80. CI #2533 building.
- **V5.9.652** (2026-05-09) — Fixed CI build failure. V5.9.651's inline LOOP_TOP forensic snapshot pushed botLoop
  bytecode over the JVM 64KB method-size limit. Extracted the snapshot block into `private fun emitLoopTopSnapshot(loopCount)`.
  Same content, same call site, no behavior change. Brace 3467/3467. CI #2532 ✅ green.
- **V5.9.651** (2026-05-09) — Full forensic logging system. New `ForensicLogger` object with structured INFO-level
  emit (`🧬[PHASE] #seq SYMBOL field=val`) survives operator log exports. Phases instrumented: LIFECYCLE / LOOP_TOP /
  INTAKE / SCAN_CB (path=STARTUP) / SCANNER_HEAL / SAFETY (allow/deny + reasons + pen) / V3 (entry per evaluable
  mint) / LANE_EVAL (ShitCoin entry). Helpers: phase/gate/decision/exec/lifecycle/tick/snapshot. Default ON.
  TokenMergeQueue.size() helper added for LOOP_TOP. CI ✅ via V5.9.652 fix.
- **V5.9.650** (2026-05-09) — Triage agent RCA on "still not trading". Two surgical fixes: (1) PAPER mode runs
  ShitCoin in parallel with V3 (`v3OwnsMemes` requires `!cfg.paperMode`). (2) `🔍 SCANNER_CALLBACK_FIRE` INFO
  log at top of bootMemeScanner callback. CI #2531 ✅ green.
  Two surgical fixes: (1) in PAPER mode only, ShitCoin trader runs in parallel with V3 instead of being muted
  (`v3OwnsMemes` now requires `!cfg.paperMode`). V5.9.409 had silenced ShitCoin when V3 enabled, but V3 was
  rejecting all 300+ watchlist candidates due to low historical paper WR (7% over 414 trades). With ShitCoin
  muted too, meme lane took zero trades. (2) Added `🔍 SCANNER_CALLBACK_FIRE` INFO log at top of bootMemeScanner
  callback so next operator log dump definitively shows whether the 13+ non-PumpPortal scanner sources are
  firing their callback at all. CI #2531 building.
- **V5.9.649** (2026-05-09) — Fixed dashboard MEME tab "20% Win Rate" with subline "0W 0L 0S" data pollution.
  MainActivity.kt:2139 was falling through to TradeHistoryStore's cross-trader 24h winRate when the
  meme-specific RunTracker30D showed 0W+0L. On MEME tab now returns 0 instead of cross-trader fallback.
  CI #2530 ✅ green.
- **V5.9.648** (2026-05-09) — Demoted SafetyChecker hard blocks to soft penalties in PAPER mode (operator override
  of V5.9.495z39's "block both modes" policy). LP <30% locked → +40 penalty in paper. RED_FLAG_GATE 5+ flags
  → +35 penalty in paper. LIVE mode unchanged (still hard-blocks). Operator: "rc score 1 and above $500 market
  cap and above goes to upstream for qualification". CI #2529 ✅ green.
- **V5.9.647** (2026-05-09) — Fixed startBot/onStartCommand guard. BotViewModel.kt pre-sets `status.running=true`
  for instant UI feedback BEFORE the service starts; service-side checks `if (!status.running)` then short-circuit
  startBot. Result: every Start tap was a no-op for the actual trade-execution loop. Fix: 3 guards changed from
  `status.running` to `loopJob?.isActive` (real source of truth). Confirmed working — operator's V5.9.647 device
  finally produced `PIPELINE LOOP_START`, `BlueChipAI BLUE CHIP QUALIFIED: TRUMP`, watchlist 264→320+. CI #2528 ✅ green.
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
