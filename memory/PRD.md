# AATE (Autonomous AI Trading Engine) — PRD

**Last updated**: 2026-07-02
**Build stream**: V5.0.4595+ (Native Kotlin Android, GitHub Actions CI)

## Original Problem Statement

Native Kotlin Android Solana trading bot with:
1. SOL Perps/Leverage trading system reusing existing AI infrastructure
2. Expanded asset coverage to 150+ instruments
3. Neural bridge for AI layers learning from perps and tokenized stocks
4. Seamless cross-trader balance sharing + live trading
5. Sentient AI personality + fluid/symbolic exit reasoning
6. "LLM Lab" sandbox mini-universe for safe strategy invention

**Operator clarification (2026-02)**: The "meme trader" is actually the
**entire Solana network trader** — memes are one subset. Lane names
(SHITCOIN/EXPRESS/MOONSHOT/MANIPULATED) are strategy classifications, not
asset types. QUALITY/BLUECHIP/DIP_HUNTER catch higher-tier established
Solana assets (JUP, WIF, SOL, BONK, etc).

## Environment

- **NO LOCAL COMPILER**: GitHub Actions CI is the sole build system
- Repo: `shaunhayes333-stack/lifecycle-bot`
- Every push triggers CI build + runtime smoke test + APK generation
- Brace/paren balance check mandatory before every push

## Operator Compounding Doctrine

- **Min daily target**: 2x compound (wallet doubles per UTC day)
- **Stretch target**: 5x or better
- **Growth strategy**: press winners (asymmetric sizing), quarantine bleeders
  (not disable — reroute through LLM Lab strategy sandbox)

## Current Session Wins (V5.0.4585 – V5.0.4588)

### V5.0.4585 — Rule 2 + Quick Snipe
- Hard -15% SL for MANIPULATED/SHITCOIN/EXPRESS at Executor rapid-check
- Quick Snipe: instant bank at +500%/+1000% peak

### V5.0.4586 — 6-Rule Profitability Doctrine (Rules 1, 4, 5)
- Rule 1: Asymmetric compounding via DailyCompoundingTracker
- Rule 4: DailyCompoundingTracker.kt (new) — 2x-per-day floor tracker
- Rule 5: FDG toxic-pattern hard-block (n>=30, lossRate>=90%)

### V5.0.4586c — Crypto Universe Parity
- Wired growth stack into CryptoAltTrader (isolated CRYPTO_SPOT/CRYPTO_LEV lanes)

### V5.0.4587 — Meme Trader Unchoke
- Fixed V4572 rapid-pivot false-positive clamping ALL lanes at 0.35x
- Result: STANDARD 0.35x->0.96x, MOONSHOT 0.68x->0.92x, buys 4x, cycle 2x faster

### V5.0.4588 — Lane Auto-Pause + Proven-Winner Press
- LaneAutoPauseGuard.kt (new) — auto-pauses n>=15+wins=0 OR n>=20+wr<20%+ev<-40%
- Catches EXPRESS (0/19) and MANIPULATED (n=33, wr=15%, ev=-50%)
- Task D: One-strike tick exit for catastrophic lanes
- Task C: Proven-winner 2x-3x press (WR>=45% + n>=5 + PnL>0)

## Architecture

```
/app/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/
├── engine/
│   ├── BotService.kt                (main loop, 24k+ lines)
│   ├── Executor.kt                  (Rule 2 rapid-check)
│   ├── FinalDecisionGate.kt         (FDG w/ toxic + auto-pause)
│   ├── LiveGrowthDoctrine.kt        (wallet-aware envelope)
│   ├── LiveStrategyTuner.kt         (WR-tuned multipliers)
│   ├── LiveProbabilityEngine.kt     (pWin/EV + laneSnapshots)
│   ├── LosingPatternMemory.kt       (bucket toxicity)
│   ├── DailyCompoundingTracker.kt   (V5.0.4586 new)
│   ├── LaneAutoPauseGuard.kt        (V5.0.4588 new)
│   ├── AutoCompoundEngine.kt
│   └── TokenMetricStageRouter.kt
├── perps/
│   ├── CryptoAltTrader.kt           (parity wired V5.0.4586c)
│   ├── PerpsTraderAI.kt             (backlog: parity)
│   └── TokenizedStockTrader.kt      (backlog: parity)
└── v3/scoring/FluidLearningAI.kt, ShitCoinTraderAI.kt, MoonshotTraderAI.kt
```

## Backlog

### V5.0.4595 — OPEN VALVE + API/RPC HARDENING (2026-07-02, CI green ✅ Build #4579 + Smoke #2049)
Field V5.0.4594 confirmed: EXPRESS/MANIPULATED frozen, wallet +21%, no
new -99% exits. But volume was choked on winner lanes (STANDARD 66%WR,
MOONSHOT 56%WR) via RSI hard-block and holder-cascade-blind soft
advisor. RPC snapshot also hammering Helius first every cycle:
- **FDG RSI relax for proven winners** — lane in {STANDARD, MOONSHOT}
  with WR≥50% over ≥5 closes → downgrade RSI>90 hard-block to penalty
- **Holder-cascade-blind relax for winners** with liq≥$5K — pipeline
  gap in holder data no longer chokes profitable lanes
- **Wallet RPC round-robin + 30s cooldown** — AtomicInteger rotates
  starting endpoint each snapshot; ConcurrentHashMap tracks unhealthy
  endpoints (429/500/401/403/TLS/network-throw) and skips for 30s.
  Fail-open: if ALL endpoints in cooldown, use full unfiltered list

### V5.0.4594 — STOP-THE-BLEED SHIP (2026-07-02, CI green ✅ Build #4578 + Smoke #2048)
Root-caused via triage subagent (10-step RCA). Field build 5.0.4593 was
bleeding -0.593 SOL on EXPRESS (0/31 WR) + MANIPULATED (14.6% WR) plus
-99% catastrophic exits from stale quotes:
- **LaneAutoPauseGuard hard-seed** — EXPRESS + MANIPULATED pre-paused at
  init so field APK actually receives the block (was written in HEAD but
  never tagged)
- **LaneShadowProofLoop resume-blacklist** with operator toggle
  (`allowLaneResume()` / `blockLaneResume()`). EXPRESS + MANIPULATED
  cannot be auto-resumed by shadow-proof until operator opens the lane
- **EXPRESS early-gate** in BotService (mirrors MANIPULATED at
  TokenSafetyChecker:487 + BotService:9216) — pause now enforced BEFORE
  ShitCoinExpress.evaluate() so paused lanes cost zero CPU
- **Stale-quote emergency -25% backstop** in Executor.kt — when BOTH
  live and cached prices are non-finite for >15s, force-exit. Kills the
  -99%/-96%/-95% overruns that slipped past existing -15% / -25% SLs
  because they returned early on empty candidates during quote outages
- **Scanner intake cap** = 75 tokens/pass (was 166+), quality-first URL
  reorder (market_cap DESC + reply_count DESC before created_timestamp)
  — targets cycle time 14–36s → <10s so exits fire on time

### P0 (immediate next)
- **Verify V5.0.4594 field impact**: EXPRESS + MANIPULATED lifetime
  trade count should stop growing; cycle time should drop <10s;
  catastrophic-SL log entries (`STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP`)
  should show up in operator report

### P1
- **RPC round-robin failover** (Helius → Triton → QuickNode) —
  originally scoped, deferred as secondary to trade-quality bleed
- **LaneAutoPauseGuard.evaluateLive()** — autonomous dynamic pause
  logic still silently fails; hard-seed covers it for now
- **PerpsTraderAI + TokenizedStockTrader parity** (deferred by operator)

### P2
- MainActivity ANR fix (16 hits at onCreate:63 — actual site is
  setContentView XML inflation; needs layout simplification, not
  Kotlin coroutine offload)
- Ladder status pill / Brain Health pill / Strategy Leaderboard Tile
- Positions backup UI export

### P3
- Tune History UI, 24h PnL drift alert
- TokenWinMemory phantom purge (>50,000% pnl rows)

## Constants Not To Touch

- `TICK_HARD_FLOOR_PCT = -10.0` (BotService)
- `DOCTRINE_FLOOR_PCT = 30.0` (do NOT lower per operator V5.0.4178)
- `TARGET_MULT_MIN = 2.0` (daily compound floor)
- LaneAutoPauseGuard triggers: n>=15+wins=0, or n>=20+wr<20%+ev<-40%
