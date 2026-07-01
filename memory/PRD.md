# AATE (Autonomous AI Trading Engine) — PRD

**Last updated**: 2026-07-02
**Build stream**: V5.0.5999+ (Native Kotlin Android, GitHub Actions CI)
**Version catch-up commit**: V5.0.5999 pushed 2026-07-02 to align APK tag
with operator's counter after `docs:` commit briefly broke the CI sed
pattern for COMMIT_BUILD_NUMBER extraction.

## Architectural Doctrine — DESIGN FIDELITY (operator directive 2026-07-02)

> *"lanes, layers and traders are meant to internally pivot trading strategy
> logic hold logic exit logic in a live state as the tokens metrics change.
> especially not dumped into a paused lane. project Sniper is meant to buy
> legit projects with a resale or fresh launches as they either come off
> presale pump then flatten securing the profit ride or fresh tokens
> approaching bond on pump meteora bonk etc getting in at the best point
> before it approaches the graduation point and getting out after it
> graduates and dies or rides it - only then pushing into the appropriate
> lane or layer or trader ... cash gen/treasury literally is meant to make
> the bot a free trading engine. user additional funds compounded and added
> too constantly user withdraws initial deposit bot runs on free money
> forever!"*

### Lane / Layer / Trader Design Intent (per literal code docstrings)

- **PROJECT_SNIPER** (`ProjectSniperAI.kt`): fresh-launch snipe. Enter at 15–600s
  age, mcap $3K–$500K, liq $2K–$250K, buy pressure ≥48%, price move ≤+80%.
  TP tiers 15%/35%/75%, moonshot 150%, SL -12%. Job = enter the graduation
  window on Pump/Meteora/Bonk, exit at graduation success or death. **After
  exit**, if token warrants continued hold, ownership transfers to the
  appropriate lane (BLUECHIP if mcap>$500K, QUALITY if >$300K,
  MOONSHOT if consolidating uptrend, etc.). NEVER should ownership rotate
  INTO a paused lane like MANIPULATED.

- **CASH GEN / TREASURY** (`CashGenerationAI.kt`): daily profit compounder.
  Ultra-conservative scalp brain. 100+ trades/day of quick 3–5% scalps.
  Position sizing 0.05–2.0 SOL DYNAMIC (scales with wallet), TP 3.0% live,
  SL -5%. **This is the free-trading-engine core: user adds funds →
  Treasury compounds them into daily cashflow → user withdraws original
  deposit → bot runs forever on the compounded free capital**. Must run
  concurrently with meme trader ("2nd shadow mode").

- **MOONSHOT**: proven +EV lane (57.7% WR, +0.072 SOL). Diamond-hands
  runner brain. Wide TP, trailing stop, longer hold on trending tokens.

- **STANDARD**: proven +EV lane (66.7% WR when winner-bypass off, +0.078 SOL).
  Balanced entry/exit on quality tokens.

- **BLUECHIP**: established SOL blue-chip watchlist (JUP/WIF/BONK/JITO).
  Higher liquidity thresholds, wider stops, position for extended holds.

- **QUALITY**: high-quality mcap $100K–$5M tokens with strong fundamentals.
  Longer holds, lower turnover.

- **DIP_HUNTER**: reclaim/pullback plays. Enters on dip-reclaim setups.

- **SHITCOIN**: memes with high momentum, tight SL.

- **EXPRESS** *(currently hard-seed paused, 0/31 lifetime WR)*: 30%+ quick
  momentum rides. Must be revived only when Lab-proven or shadow-proof
  demonstrates positive-EV in a specific regime.

- **MANIPULATED** *(currently hard-seed paused, 14.6% WR, -0.48 SOL)*:
  detection of pump/dump manipulation. Was originally meant to trade the
  detected manipulation intentionally, but has been net-catastrophic.
  Must be revived only via Lab-proven strategy.

### Dynamic Lane Transitions (V5.0.4598+ mission)

Tokens must **transition lanes dynamically as their metrics evolve**:
  - Mcap breaks $500K rising → PROJECT_SNIPER graduates ownership to BLUECHIP
  - Mcap breaks $300K rising → STANDARD graduates ownership to QUALITY
  - Mcap enters $50K–$300K trending → MOONSHOT ownership
  - Mcap collapses / volume dies → owner lane exits, no auto-rotation

Ownership rotation into PAUSED lanes (EXPRESS, MANIPULATED) is **strictly
forbidden**. V5.0.4598 owner-lane pause check enforces this.

### Design-Fidelity Audit Plan (V5.0.4599 backlog)

Audit each trader's LIVE behavior vs docstring intent:
  1. PROJECT_SNIPER: entering only 15–600s tokens? mcap window respected?
     TP tiers hit? Ownership rotation to correct next-lane by mcap?
  2. CashGen/Treasury: running concurrently? 100+ scalps/day target hit?
     Compounding wallet or drained? Daily loss limit self-enforced?
  3. MOONSHOT: diamond-hands trailing correctly? Runner detection working?
  4. QUALITY/BLUECHIP: taking correct mcap-band tokens? Not fighting for
     memes it shouldn't own?
  5. All lanes: dynamic hold-logic pivoting live per token metric change?



> *"All gates are meant to be in a fluid state, eventually to be removed
> once the SUPER AGI / SSI stack takes over once they have enough learnt
> intelligence. These should not just be result-based decisions either."*

Every gate/pause/dampener added to this codebase MUST:

1. **Be fluid, not binary** — return a probability-weighted dampener (0.10 →
   0.55 → 1.0), NEVER a hard `size=0` rigid block. Even paused lanes should
   permit tiny learning probes so the AGI training loop is never blind.
2. **Yield to AGI authority** — always consult forward-looking AGI signals
   before applying the dampener:
     - `UnifiedPolicyHead` per-lane authority (AUTHORITATIVE vs BOOTSTRAP)
     - `LlmLabStore` proven strategies for the asset class
     - `LaneShadowProofLoop` proof-bar completion
   When the AGI has trained authority, the gate must scale itself down.
3. **Be forward-looking, not purely reactive** — do not gate solely on
   backward-looking WR / EV. Incorporate forecast (`ForwardOutcomeModel`),
   regime, brain confidence, and lab-proven forward evidence.
4. **Log rich telemetry** — every fluid-gate decision must emit a
   `ForensicLogger.lifecycle` event so the AGI can learn WHICH gates
   flipped, WHY, and whether the outcome validated the decision.
5. **Have an eventual sunset** — once AGI reaches a defined maturity bar
   (trained samples + authority + shadow-proof), the gate should be
   removed OR set to `noop` mode automatically.

**Applied in V5.0.4596**: `LiveProbabilityEngine.computeEdge` paused-lane
backstop returns fluid mult (0.10 / 0.35 / 0.55) instead of the initially
proposed rigid `mult=0.0`. AGI signals (`LlmLabStore.PROVEN`,
`UnifiedPolicyHead AUTHORITATIVE`) upgrade the dampener automatically.



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

### V5.0.4599 — SPECIALIST TRADER RESET + TREASURY REVIVAL (planned, 2026-07-02)
Per operator directive: PROJECT_SNIPER, SHITCOIN, EXPRESS are TRADERS,
not lanes. CashGen/Treasury needs its own scanner + brain. Universal
promotion pathway. Reject-bypass reformed into a sizing edge.

**Phase A — CI unblock (V5.0.4598b, in flight):** 3 golden-tape tests
updated for the paused-lane owner-election guard.

**Phase B — Specialist trader reset:**
  - Remove PROJECT_SNIPER + SHITCOIN + EXPRESS from `fullMemeTraderRing`
  - Convert to `SpecialistTrader` classification (own entry, own hold,
    own handoff). Each specialist keeps its position until mission
    complete (graduation, TP tier, or handoff trigger)
  - Bot cycle: specialists first (fresh launches), then lanes

**Phase C — Treasury scanner + brain:**
  - New `TreasuryScannerFeed.kt`: dedicated pipeline pulling
    established liquid tokens (CoinGecko top-100, Birdeye trending
    mcap>$1M, DexScreener established pools age>7d, blue-chip
    watchlist). NOT competing with PROJECT_SNIPER for pump.fun stream.
  - New `TreasuryBrain.kt`: scalp-setup scoring (5m/15m momentum,
    spread tightness, no-wick-chop) purpose-built for 3-5% scalps
  - CashGenerationAI polls its own watchlist on its own cadence

**Phase D — Universal LaneTransitionManager:**
  - Single decision brain per open position per cycle
  - Rules from docstrings:
    * PROJECT_SNIPER exit at graduation → mcap>$500K:BLUECHIP,
      mcap>$100K:MOONSHOT/LUNAR, mcap>$50K:MOONSHOT/ORBITAL
    * Any lane hits +100% → MOONSHOT promotion
    * Any lane hits +25% established → CashGen banks + restart
    * Mcap thresholds → STANDARD→QUALITY→BLUECHIP live rotation

**Phase E — LIVE_EXPECTANCY_REJECT_BYPASSED → edge:**
  - Don't reject on live. Use EV data as fluid size multiplier
    through liveSizeShape:
    EV≤-60% → 0.10x, EV≤-35% → 0.25x, EV≤-10% → 0.55x,
    EV≥+25% → 1.35x, EV≥+50% → 1.60x
  - Wire as authoritative multiplier alongside qualityBoost
  - Backward EV becomes edge, not obstruction

### V5.0.4598b — CI FIX for owner-lane paused-guard (in flight)

### V5.0.4596 — FLUID PAUSED-LANE DAMPENER + AGI OVERRIDE (2026-07-02)
Field V5.0.4595 confirmed wallet +99% (0.5→1.0 SOL) but MANIPULATED still
gained +6 trades bypassing all 3 hard gates (FDG/TokenSafety/BotService).
Rather than adding a 4th rigid gate, applied FLUID DAMPENER at
LiveProbabilityEngine.computeEdge that respects AGI authority:
- **LlmLabStore PROVEN** strategy → mult=0.35 (normal probe size)
- **UnifiedPolicyHead AUTHORITATIVE** → mult=0.55 (trained authority)
- **No AGI signal** → mult=0.10 (tiny learning probe — not zero!)
- Dampener naturally fades as AGI matures — no rigid block. AGI can
  fully override once trained. Aligns with operator's fluid-gates
  architectural doctrine (see top of PRD).

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
