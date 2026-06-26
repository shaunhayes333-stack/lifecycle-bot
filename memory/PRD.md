# AATE — Autonomous AI Trading Engine
## Product Requirements & Current State

### Original problem statement (verbatim user direction)

Upgrading a Native Kotlin Android Solana trading bot to V5.x. Building a
super smart SOL Perps / Leverage trading system that reuses existing AI
infrastructure, adding tokenized stocks, multi-asset trading, an Insider
wallet tracker, a live readiness gauge, and a continuous auto-replay
learning system.

Core product requirements:
1. SOL Perps / Leverage mode leveraging existing bot infrastructure.
2. Expand asset coverage to 150+ instruments.
3. Upgrade AI layers to learn from perps and tokenized stocks via a neural bridge.
4. Seamless cross-trader balance sharing and live trading capability.
5. Sentient AI personality + fully fluid/symbolic exit reasoning.
6. "LLM Lab" sandbox mini-universe where the LLM safely invents and tests strategies.

### Hard infrastructure constraints

* **NO LOCAL COMPILER.** All builds go through GitHub Actions CI via `git push`.
* Brace / paren balance **MUST** be verified with `grep -o '{' | wc -l` (or
  git-diff counter) before every push — recurring source of red CI builds.
* **MEME TRADER MUST NOT BE CHOKED.** Every new filter / brownout / damper
  must explicitly exempt open positions and high-conviction signals.
* Hot reload + supervisor convention does not apply — this is an Android APK,
  not a server.

---

## Implemented (this session)

| Version | Push | What it fixed |
|---|---|---|
| 5.0.4173 | gzip transparent decode hotfix | Bot revival: every Solana RPC was returning garbled bytes because V5.0.4170's manual `Accept-Encoding: gzip` disabled OkHttp BridgeInterceptor's transparent decompression. Restored wallet + scanners + rugcheck. |
| 5.0.4174 | Jupiter Tokens V1 → V2 migration | `tokens.jup.ag` was deprecated 30 Sep 2025 (NXDOMAIN). Migrated to `lite-api.jup.ag/tokens/v2/tag?query=verified` (4,307 verified tokens, free tier). |
| 5.0.4175 | 4-way unchoke (A+B+C+D) | A. SOURCE_SCAN_TIMEOUT 6→5s, SCAN_BATCH_BUDGET 14→8s. B. PROBATION_MAX_TIME 120→90s. C. BirdeyeBudgetGate.canAffordSafety() brownout at ≥98% daily CU. D. BOT_LOOP_CYCLE_OVERRUN forensic + bucketed counters at 20-90s band. |

Field validation:
* Before 4173: 0 SOL RPC reads, scanners blind, bot dead.
* After 4173: intake jumped 208 → 464, FDG 187 → 187 (rebalanced), EXEC 5 → 1073, LIVE BUYs 22 in 293s.
* After 4174: bot continued live trading, but cycle-bloat choke emerged
  (20 BUYs in 1394s = 0.86/min, cycle 17-48s, 22× EXIT_COORDINATOR_STALE_RESET,
  Birdeye CU at 100.1%).
* 4175 awaiting field validation.

---

## Pending / Backlog (operator-facing UI ideas)

P2:
* "Ladder" status pill at the top of the Memes tab (e.g. `🟡 TIER 2 · target 24.6% · actual 10.6%`).
* "Strategy Leaderboard Tile" showing live top-3 strategies by current expectancy.
* "🛡 Guards: 4 streak-blocks · 2 distrust pauses..." status strip on Behavior screen.
* "Brain Health" pill next to sentiment badge (STEADY / DRIFTING / POISONED / EXCELLENT).

P3:
* 24h-PnL drift alert at top of UI: "⚠ 24h PnL lags proof-run by -1.2%".
* "Tune History" UI tab under Behavior.
* `/positions backup` export button (dumps PerpsPositionStore JSON to file for device migration).

## Known chronic issues (not blockers)

* WR=22% below 45% adaptive floor → multiple size dampers stacking
  (regime CHOP×0.35, LiveStrategyTuner×0.35, LiveProbabilityEngine×0.40-0.70).
  Operator explicitly does **not** want these touched without explicit ask.
* `jupiter_quote` 4xx storm = Ultra v2 endpoint requires API key; bot falls
  back to v6 successfully. UX noise, not a real choke. Could be silenced by
  skipping v2 when apiKey is blank.
* PumpFun rate-limits us periodically (scanner timeout streaks). Self-heals.

## File map (most-touched this session)

```
app/src/main/kotlin/com/lifecyclebot/
├── engine/
│   ├── BotService.kt                 (23.7K lines — core engine + emitBotLoopTick)
│   ├── SolanaMarketScanner.kt        (scanner with parallel batch + timeouts)
│   ├── BirdeyeBudgetGate.kt          (daily/monthly CU budget gate)
│   ├── GlobalTradeRegistry.kt        (watchlist + probation)
│   ├── Executor.kt                   (BUY/SELL execution; live + paper)
│   ├── FinalDecisionGate.kt          (HARD_BLOCK_* enforcement)
│   ├── ExecutionHealthGuard.kt       (Jupiter DNS blackout protection)
│   ├── TokenMetaCache.kt             (cache, currently under-leveraged)
│   └── PipelineHealthCollector.kt    (snapshot generator)
└── network/
    ├── SharedHttpClient.kt           (gzip transparent decode V5.0.4173)
    ├── JupiterApi.kt                 (lite-api.jup.ag/swap/v1)
    ├── JupiterStrictTokenList.kt     (lite-api.jup.ag/tokens/v2 — V5.0.4174)
    ├── HostCircuitInterceptor.kt     (NXDOMAIN cooldown, 5xx cooldown)
    └── CloudflareDns.kt              (DoH prewarm)
```
