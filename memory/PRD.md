# AATE (Autonomous AI Trading Engine) — PRD

**Last updated**: 2026-02
**Build stream**: V5.0.4588+ (Native Kotlin Android, GitHub Actions CI)

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

### P0 (immediate next)
- Confirm V5.0.4588 on-device: EXPRESS + MANIPULATED actually auto-pause?
- MOONSHOT/STANDARD hitting 2x-2.6x entry?

### P1
- **Scanner surface expansion**: intake dominated by pump.fun new-mints; need
  established Solana tokens too (CoinGecko trending, DEX top-liq, blue-chip
  watchlist JUP/WIF/BONK/JITO) so QUALITY/BLUECHIP actually get fed
- **LLM Lab shadow-proof loop**: when lane auto-paused, spin sandboxed strategy;
  if it proves >30% WR + positive EV over 20 shadow trades, auto-resume
- **PerpsTraderAI + TokenizedStockTrader parity** (deferred by operator)

### P2
- Ladder status pill / Brain Health pill / Strategy Leaderboard Tile
- Positions backup UI export
- MainActivity ANR fix (29s startup stall observed)

### P3
- Tune History UI, 24h PnL drift alert

## Constants Not To Touch

- `TICK_HARD_FLOOR_PCT = -10.0` (BotService)
- `DOCTRINE_FLOOR_PCT = 30.0` (do NOT lower per operator V5.0.4178)
- `TARGET_MULT_MIN = 2.0` (daily compound floor)
- LaneAutoPauseGuard triggers: n>=15+wins=0, or n>=20+wr<20%+ev<-40%
