# AATE Changelog

All notable changes to the Autonomous AI Trading Engine.

---

## [5.0.4131] - 2026-02 — REAL-SIZE ENTRIES (liquidity-cap fix)

### Root cause
Operator journal showed live entries of ~0.01–0.03 SOL ($1–$3) despite the
V5.0.4129 absolute floor in `doBuy`. The leak was downstream in
`realisticLiveEntrySize` (`Executor.kt:2308`) where the **liquidity-impact
cap** (2% of pool size in SOL terms) was crushing entries on low-liq
pump.fun newborns:
  - liqUsd=$100 → cap = (100 × 0.02) / $104/SOL = **0.0096 SOL** (dust)
  - liqUsd=$500 → cap = **0.048 SOL**
  - liqUsd=$1000 → cap = 0.19 SOL
This cap was bypassing my doBuy absolute floor because line 2347
(`out.coerceAtLeast(minOf(requestedSol, cap))`) selects `cap` when
`requestedSol > cap`.

### Fix 1 — Pattern Golden Goose impact tolerance
- GOLD verdict → impact tolerance × 4 (allows ~8% pool impact)
- WINNER verdict → impact tolerance × 2.5 (~5% impact)
- NEUTRAL/TOXIC/CATASTROPHIC → unchanged (~2% impact)
- Rationale: theme_space pattern wins 82.7% with 47% avg gain — accepting 8%
  slippage on a 47% expected return is a strictly winning trade.

### Fix 2 — Absolute floor at the final size-authority
- After all caps, if `walletHealthy` (spendable > MIN_ENTRY × 3) AND
  `liquidityAdequate` (≥$500 = exitable) → lift to `MIN_ENTRY_SOL` (0.040 SOL).
- TOXIC/CATASTROPHIC verdicts SKIP this lift (don't size up bad patterns).

### Impact
- Healthy wallets + adequate liquidity → entries floor at 0.040 SOL (~$4) instead of dust
- Known-edge (GOLD/WINNER) tokens → up to 4× higher liquidity-impact ceiling
- Quality-confirmed scaling preserved end-to-end across the size pipeline

### Telemetry
- `LIVE_ABS_FLOOR_LIFT_V4131` label + forensic `LIVE_ABS_FLOOR_LIFT_V4131` log
- `GROWTH_MODE_TRACE` now includes goose verdict

---

## [5.0.4130] - 2026-02 — PROFIT-BOOSTER TRIO (no volume loss)

### Fix 1 — ultra_runner_bank current-price sanity gate (Executor.kt)
- `peakGainPct >= 5_000.0` triggered the panic-banker indefinitely after a
  position ever peaked at 50x, EVEN after the price collapsed back through
  entry. Journal showed banker selling at -29% / -66% PnL because
  `qty × price / costSol` still read 50x on a stale-peak basis.
- Added: `currentValue >= pos.costSol * 1.5` — only banks when the position
  is ACTUALLY a runner right now. Maintains volume (winners still bank);
  eliminates the loss-exit cascade.

### Fix 2 — FDG TOKEN_MAP_INCOMPLETE goose downgrade (FinalDecisionGate.kt)
- Op report: 9 of 11 FDG verdicts blocked here (transient route-data lag on
  fresh launches, mostly Raydium/pump migration).
- GOLD/WINNER pattern verdicts: downgrade from HARD_BLOCK to advisory,
  apply soft-shape via `LiveSizingProfile.markGateSoftShape("FLUID_EXECUTE_FLOOR")`,
  let the executor's fallback routing (Jupiter Ultra / PumpSwap / Raydium probe)
  do its job. Unknown / TOXIC / CATASTROPHIC still hard-block.
- Volume impact: NEUTRAL+ (unblocks tokens already KNOWN to convert at 50-82% WR).

### Fix 3 — DUMP-regime goose bypass (Executor.kt)
- `RegimeDetector.sizeMultiplier()` returns 0.10 in DUMP — crushed every entry
  to 10% of base regardless of asset-level edge.
- GOLD verdict → bypass to 1.00× (full size).
- WINNER verdict → 0.60× floor.
- Other verdicts → standard regime brake unchanged.
- Telemetry: `REGIME_GOOSE_BYPASS_<verdict>` label + forensic log.
- Volume impact: NEUTRAL (same trades, real size when goose confirms quality).

### Composition — all three boost profit WITHOUT cutting volume
- Fix 1: prevents giving back gains (loss-exits gone)
- Fix 2: unblocks pattern-confirmed entries previously vetoed by data-lag
- Fix 3: lets pattern-confirmed entries get REAL size in dump regimes
- Quality protection: TOXIC/CATASTROPHIC verdicts never bypass anything.

---

## [5.0.4129] - 2026-02 — MEME-TRADER MONEY-PRINTER PASS (P0 trio)

### Fix 1 — Sizing cascade absolute floor + goose override (Executor.kt)
- `doBuy` was applying `liveFloorMult × sol`, a RELATIVE floor. When upstream
  SmartSizer multipliers had already collapsed `sol` to dust (0.003 SOL),
  the floor became 0.0009 SOL (relative to dust input). Result: +24,570% wins
  paying $0.33.
- Now: `effSolRaw` clamps to `max(relMin, absMin)` where `absMin` is an
  ABSOLUTE entry floor sourced from `LiveSizingProfile` tiers (MIN/DEFAULT/STRONG)
  and gated on wallet adequacy.
- PatternGoldenGoose verdict override:
  - GOLD → absolute floor lifted to STRONG_ENTRY_SOL (0.110 SOL) + max boost 3.00×
  - WINNER → DEFAULT_ENTRY_SOL (0.060 SOL) + 2.35×
  - TOXIC/CATASTROPHIC → no absolute lift (size shrinks as before)
- Telemetry: `LIVE_ABS_FLOOR_LIFT_<verdict>` label + forensic `LIVE_ABS_FLOOR_LIFT_V4129`.

### Fix 2 — Goose exit protection on MOONSHOT (MoonshotTraderAI.kt)
- GOLD pattern tokens now bypass:
  - `EARLY_TIGHT_STOP` (-5% cut when peak < +8%)
  - `HOLD_BUCKET_EARLY_EXIT`
- Hard floor -15% STILL applies — only the early-cut layers are relaxed.
- Lets proven-winner signatures (theme_space 82% WR, theme_ai 50% WR) ride to
  their statistical mean (+47% for theme_space).

### Fix 3 — GateRelaxer per-token golden-goose override (LiveLayerGateRelaxer.kt)
- New `floorMultiplierForToken(traderTag, name, symbol)`: when live WR < 30%
  doctrine floor (death-spiral lock), the global relaxer disables. This now
  bypasses the lock FOR THE SPECIFIC TOKEN if the goose says GOLD/WINNER.
- TOXIC/CATASTROPHIC verdicts NEVER get a relax (extra protection).

### Fix 4 — Starved-lane wakeup (BotService.kt)
- `laneAffinityForTradeType` and `inferIntakeLaneAffinity` expanded to seed
  CASHGEN, CYCLIC, MANIPULATED, EXPRESS, DIP_HUNTER into the candidate pool.
- Pre-fix: 6 of 12 enabled lanes silent (0 evals). Post-fix: every enabled lane
  is a candidate from intake.
- `AgenticStyleRouter.boundedLanes` still caps to 2 lanes per token via
  `stablePick` — broader candidate pool, no eval explosion. Variety rotates.

### Why
Operator: "theres literally no action from most of the trading layers still live.
strategy scoring or data supply issues are starving the lanes either at discovery
or classification." Fix 4 addresses the structural starvation. Fixes 1-3 address
the size collapse + clipping that prevented winners from paying out.

---

## [5.0.4128] - 2026-02 — PATTERN GOLDEN GOOSE

### Added
- **TokenWinMemory.patternEdgeForToken** — sharp asymmetric pattern edge:
  enumerates a token's matched name/symbol patterns and returns the BEST
  and WORST independently (rather than blending). Verdict ladder:
  CATASTROPHIC / TOXIC / NEUTRAL / WINNER / GOLD.
- **PatternGoldenGoose** — thin facade that exposes the edge as a lane
  score-bias (-35..+16, asymmetric: toxic dominates gold) plus a
  `isCatastrophic` veto hook.

### Changed
- **MoonshotTraderAI.scoreToken** — applies `PatternGoldenGoose` score
  bias to the lane score itself (additive, not floor). CATASTROPHIC
  verdict short-circuits to hard reject; rejection reasons now carry the
  goose tag (e.g. `goose=TOXIC_-theme_inu=0%n13_bias-22`).
- **ShitCoinTraderAI.evaluate** — same pattern wiring as Moonshot so
  both meme-traders share the same golden-goose leverage.

### Why
Operator: "find the data golden goose for each lane and traders switching
the bot into a money printer. half of its still silent re the meme trader."
The bot already records sharp pattern data (theme_space 82% WR n=75,
theme_musk 0% WR n=11). Until now this only contributed ±5 via
OrthogonalSignals — nowhere near what the data deserves. The goose:
  - Lifts marginal gold-pattern tokens over the score floor (+16).
  - Sinks strong-but-toxic tokens below the floor (-22).
  - Hard rejects catastrophic patterns (n≥15, WR≤5%).
Asymmetric tilt by design: toxic veto is ~2× gold lift — bleed-stop
matters more than moonshot capture in the current regime.

---

## [5.0.4127] - 2026-02 — RUNNER PROTECTION (U-SHAPED TRAIL)

### Changed
- **AdvancedExitManager.calculateProgressiveTrailingStop**: Converted the
  trail curve from monotonic-tighten to a U-shape so monster runners can
  compound through the MONSTER_LOCK ladder.
  - Below +500%: unchanged (tighten progressively, protect from giveback).
  - +500% → trail = base × 0.55 (was 0.30 — gives room to reach T2 +1500%).
  - +1000% → trail = base × 0.75.
  - +3000% → trail = base × 0.95.
  - +10000% → trail = base × 1.20 (wider than base — monster compounds to T4/T5).
  - Clamp ceiling raised from 25.0 → 35.0 so monster trails aren't capped.

### Why
Operator: "we have to have a huge huge win in the next 24 hours". With the
old curve, a runner at +1000%+ trailing at base×0.3 ≈ 6% would round-trip on
a normal pullback BEFORE the MONSTER_LOCK_T2 (+1500%) tier could fire. The
lock-ladder already banks realized $ at +500/+1500/+5000/+15000/+30000%, so
the trail can afford to widen above +500% — the dollars are already in the
bank; the trail's only job above that is to catch a true round-trip giveback.

---

## [5.0.4126] - 2026-02 — MOONSHOT FLUID PIVOT

### Added
- **MoonshotAdaptiveGate**: New lane-specific brain that fluidly pivots the
  MOONSHOT entry quality bar based on its own recent outcomes.
  - Hybrid recency-weighted WR over a 100-trade rolling window (newest 50
    trades count 2.0×, prior 50 count 1.0×).
  - Returns a bounded score-floor bias in [-5, +20]:
    - EMERGENCY (wr < 15%) → +20 (tighten hard)
    - DEFENSIVE (wr 15-25%) → +12
    - NEUTRAL (wr 25-50%) → +6 below target, 0 above
    - AGGRESSIVE (wr >= 50%) → -5 (let it breathe)
  - Never a hard veto — only nudges the score floor. Auto-loosens as WR
    recovers so the lane self-heals.
  - Persists rolling history across reboots via SharedPreferences.

### Changed
- **MoonshotTraderAI.scoreToken**: `effectiveMinScore` now includes
  `MoonshotAdaptiveGate.scoreFloorBias()` (additive after `personalityFloorBias`).
  Rejection messages surface the live phase tag (e.g. `gate=DEFENSIVE_wr22_n67_bias+12`).
- **MoonshotTraderAI.closePosition**: Now calls
  `MoonshotAdaptiveGate.recordOutcome(pnlPct)` so the gate trains live, and
  also calls `LayerBrain.recordOutcomeAll(mint, pnlPct)` (previously skipped
  because Moonshot bypasses `Executor.recordTrade`).

### Why
Operator: "its meant to fluidly pivot bro! not disable. each lane has a brain
specifically for that lane use it not disabled the lane!!!" The lane was
bleeding (-0.85 SOL, 24.6% WR over 248 trades). Existing learned gates
(`ScoreExpectancyTracker.shouldReject`, `LosingPatternMemory.recommendedSlPct`)
are paper-only or per-bucket — none responded to global lane WR sliding in
LIVE. This gate closes that loop without disabling anything.

---

## [5.2.11] - 2026-04-02

### Fixed
- **QualityTraderAI Wiring**: Fixed compilation error in BotService.kt caused by non-existent TokenState properties
  - `tokenAgeMinutes`: Now calculated from history candles
  - `holderCount`: Retrieved from last candle in history
  - `buyPressure`: Fixed type conversion (Double to Int)

### Added
- **QualityTraderAI**: New professional Solana trading layer for $100K-$1M mcap tokens
  - 417 lines of specialized quality trading logic
  - Targets 15-50% gains with 15-60 minute holds
  - Bridges gap between ShitCoin and BlueChip tiers
- **Quality Positions Dialog**: UI dialog showing open Quality layer positions

### Changed
- **V3 Tile**: Now shows aggregate system stats instead of 0%
- **AdvancedExitManager**: Time multipliers now looser at entry (not tighter)
- **FluidLearningAI**: Bootstrap thresholds adjusted for better learning

---

## [5.2.10] - 2026-04-01

### Fixed
- **Harvard Brain Education**: All 25 AI layers now properly wired to education system
- **Collective Hivemind**: Fixed data parsing issues
- **Overnight Trading**: Performance improvements for extended sessions

---

## [5.2.9] - 2026-03-31

### Added
- **Ultra-Aggressive Paper Mode**: Maximum learning velocity in paper trading

### Changed
- **Stability & Reliability Pass**: General hardening across all systems

---

## [5.2.8] - 2026-03-30

### Added
- **30-Day Run Stats**: UI card showing 30-day performance metrics
- **Export Button**: Export trading data for analysis

### Fixed
- **0% TP Instant-Exit Bug**: Complete fix for premature exits
- **Paper Mode Scanning**: Faster learning with more aggressive scanning

---

## [5.2.7] - 2026-03-29

### Changed
- **All Trading Layers**: Enabled in Paper Mode for comprehensive testing

---

## [5.2.6] - 2026-03-28

### Fixed
- **UI Tile Stats**: Added missing XML TextViews for complete data display

---

## [5.2.5] - 2026-03-27

### Fixed
- **Safe Build Fix**: Compilation errors resolved

---

## [5.2.4] - 2026-03-26

### Added
- **UI Tile Stats**: Learning progress visualization
- **Learning Progress Fixes**: Improved accuracy of progress tracking

---

## [5.2.3] - 2026-03-25

### Fixed
- **Build Errors**: Various compilation fixes
- **EMERGENT PATCH PACKAGE**: Critical patches applied

---

## [5.2.2] - 2026-03-24

### Added
- **Treasury Min Hold Time**: Prevents premature treasury exits

### Fixed
- **Shadow Learning UI**: Display corrections
- **CollectiveLearning**: Connection reliability improvements
- **Paper Mode**: Complete behavior penalty bypass
- **Throughput Pipeline**: Performance improvements

---

## [5.2.1] - 2026-03-23

### Fixed
- **Trailing Stop Exits**: Fixed premature trailing stops causing 5-6% win rate
- **Hold Time Protection**: Hardened across all exit triggers
- **Treasury Aggressive Exits**: Loosened overly tight exit conditions
- **Treasury→ShitCoin Promotion**: Fixed overly tight -2.5% stop loss

---

## [5.2.0] - 2026-03-22

### Added
- **Education Sub-Layer AI**: Every trade now teaches the system
- **Harvard Brain Integration**: Centralized learning repository

### Changed
- **Complete Architecture Review**: Major audit of all exit conditions
- **4-Tier System Clarification**: Treasury → ShitCoin → Quality → BlueChip

---

## Statistics

- **Total Commits**: 914+
- **Total Lines**: 110,444
- **AI Layers**: 28
- **Development Time**: ~10 days
- **Development Device**: Mobile phone only

---

## Legend

- **Added**: New features
- **Changed**: Changes in existing functionality
- **Fixed**: Bug fixes
- **Removed**: Removed features
- **Security**: Security-related changes
