# AATE Changelog

All notable changes to the Autonomous AI Trading Engine.

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
