# AATE v3 Architecture — Unlocked Mode

> **Scoring-based decisions instead of hard blocks. The actual unlock.**

## Implementation Status

| Component | File | Status |
|-----------|------|--------|
| BotOrchestrator | `v3/core/BotOrchestrator.kt` | ✅ Implemented |
| TradingConfigV3 | `v3/core/Config.kt` | ✅ Implemented |
| TradingContext | `v3/core/Config.kt` | ✅ Implemented |
| LifecycleManager | `v3/core/LifecycleManager.kt` | ✅ Implemented |
| V3BotMode | `v3/core/Enums.kt` | ✅ Implemented |
| DecisionBand | `v3/core/Enums.kt` | ✅ Implemented |
| EligibilityGate | `v3/eligibility/EligibilityGate.kt` | ✅ Implemented |
| CooldownManager | `v3/eligibility/EligibilityGate.kt` | ✅ Implemented |
| ExposureGuard | `v3/eligibility/EligibilityGate.kt` | ✅ Implemented |
| UnifiedScorer | `v3/scoring/UnifiedScorer.kt` | ✅ Implemented |
| ScoreCard | `v3/scoring/ScoreCard.kt` | ✅ Implemented |
| All AI Modules | `v3/scoring/ScoringModules.kt` | ✅ Implemented |
| FatalRiskChecker | `v3/risk/FatalRiskChecker.kt` | ✅ Implemented |
| ConfidenceEngine | `v3/decision/DecisionEngine.kt` | ✅ Implemented |
| FinalDecisionEngine | `v3/decision/DecisionEngine.kt` | ✅ Implemented |
| SmartSizerV3 | `v3/sizing/SmartSizerV3.kt` | ✅ Implemented |
| ShadowTracker | `v3/shadow/ShadowTracker.kt` | ✅ Implemented |
| LearningStore | `v3/learning/LearningStore.kt` | ✅ Implemented |
| TradeExecutor | `v3/execution/TradeExecutor.kt` | ✅ Implemented |
| V3Adapter | `v3/bridge/V3Adapter.kt` | ✅ Implemented |
| V3EngineManager | `v3/V3EngineManager.kt` | ✅ Implemented |

### Wiring Status
- [x] BotOrchestrator wired into BotService ✅
- [x] Jupiter API integration via ExecuteCallback ✅
- [x] Turso collective sync with LearningStore ✅
- [x] Shadow learning mode (V3 vs FDG comparison) ✅
- [x] V3 vs Legacy comparison logging ✅
- [x] WhaleTrackerAI → WhaleWalletTracker bridge ✅
- [x] AutoModeEngine → TimeModeScheduler integration ✅
- [x] FinalDecisionEngine → V3ConfidenceConfig integration ✅
- [x] SmartSizerV3 → V3ConfidenceConfig integration ✅
- [x] CollectiveLearning → CollectiveAnalytics dashboard ✅

### New Intelligence Systems (Session 7)

| System | File | Purpose |
|--------|------|---------|
| CollectiveAnalytics | `engine/CollectiveAnalytics.kt` | Dashboard data for hive mind |
| V3ConfidenceConfig | `engine/V3ConfidenceConfig.kt` | User-adjustable thresholds |
| WhaleWalletTracker | `engine/WhaleWalletTracker.kt` | Track whale win rates |
| TimeModeScheduler | `engine/TimeModeScheduler.kt` | Time-based mode switching |

### Legacy Deprecation
| Legacy File | V3 Replacement | Status |
|-------------|----------------|--------|
| `FinalDecisionGate.kt` | `v3/decision/FinalDecisionEngine` | @Deprecated |
| `SmartSizer.kt` | `v3/sizing/SmartSizerV3` | @Deprecated |
| `EntryIntelligence.kt` | `v3/scoring/ScoringModules` | @Deprecated |
| `AntiRugEngine.kt` | `v3/risk/FatalRiskChecker` | @Deprecated |
| `EdgeOptimizer.kt` | `v3/scoring/ScoringModules` | @Deprecated |

---

## Collective Learning Integration

V3 now syncs with the Turso Collective Hive Mind:

### Uploads (Every Trade)
- Pattern outcomes (entryPhase + tradingMode + source + liquidity + trend)
- Blacklisted tokens (mint, symbol, reason, severity)

### Periodic Syncs (Every 15 min)
- Mode performance stats (trades, wins, losses, avg PnL, hold time)
- V3 vs FDG comparison stats

### Score Adjustments
- Collective patterns provide -30 to +30 score adjustments
- Known winners get boosted, known losers get penalized
- Based on aggregate win rate across all AATE instances

---

## Philosophy

Replace this style:
```kotlin
if (quickRugcheckReturnedFalse) blockTrade()
if (copyTradeInvalidation) suppressSignal()
if (memoryBad) cancelTrade()
```

With this style:
```kotlin
score += rugPenalty
score += copyTradePenalty
score += memoryPenalty
```

**Only keep hard blocks for:**
- Unsellable
- Broken pair
- Liquidity collapse
- Extreme rug score (90+)
- Already open position
- Cooldown active
- Global exposure maxed

Everything else becomes a **score modifier**, not a gate.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      BotOrchestrator                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  DISCOVERY → ELIGIBILITY → SCORING → FATAL CHECK → CONFIDENCE  │
│                                                                 │
│                    ↓                                            │
│                                                                 │
│              FINAL DECISION ENGINE                              │
│                    ↓                                            │
│                                                                 │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐     │
│  │ EXECUTE_AGG │ EXECUTE_STD │ EXECUTE_SML │    WATCH    │     │
│  └─────────────┴─────────────┴─────────────┴─────────────┘     │
│                    ↓                                            │
│              SMART SIZER V3                                     │
│                    ↓                                            │
│              TRADE EXECUTOR                                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Module Breakdown

### Core
| File | Purpose |
|------|---------|
| `BotMode.kt` | PAPER, LEARNING, LIVE |
| `TradingConfig.kt` | All thresholds and limits |
| `TradingContext.kt` | Runtime state wrapper |
| `BotOrchestrator.kt` | Main pipeline coordinator |

### Lifecycle
| File | Purpose |
|------|---------|
| `LifecycleState.kt` | State machine states |
| `LifecycleManager.kt` | State tracking per token |

### Scanner
| File | Purpose |
|------|---------|
| `SourceType.kt` | DEX_BOOSTED, RAYDIUM_NEW_POOL, etc. |
| `CandidateSnapshot.kt` | Token data snapshot |

### Eligibility (Hard Gates Only)
| File | Purpose |
|------|---------|
| `EligibilityGate.kt` | Pre-scoring filters |
| `CooldownManager.kt` | Per-token cooldowns |
| `ExposureGuard.kt` | Position limits |

### Scoring (The Unlock)
| File | Purpose |
|------|---------|
| `UnifiedScorer.kt` | Orchestrates all AI modules |
| `ScoreCard.kt` | Aggregated score result |
| `ScoreComponent.kt` | Individual score piece |
| `EntryAI.kt` | Buy pressure, RSI, momentum |
| `MomentumAI.kt` | Pump detection |
| `LiquidityAI.kt` | LP health scoring |
| `VolumeProfileAI.kt` | Accumulation detection |
| `HolderSafetyAI.kt` | Concentration risk |
| `NarrativeAI.kt` | Identity signals |
| `MemoryAI.kt` | Historical pattern match |
| `MarketRegimeAI.kt` | Bull/Bear context |
| `TimeAI.kt` | Time-of-day edge |
| `CopyTradeAI.kt` | Stale/crowded penalties |

### Risk (Fatal Only)
| File | Purpose |
|------|---------|
| `FatalRiskChecker.kt` | Hard blocks only |
| `RugModel.kt` | Extreme rug scoring |
| `SellabilityCheck.kt` | Pair validity |

### Decision
| File | Purpose |
|------|---------|
| `ConfidenceEngine.kt` | Statistical + Structural + Ops |
| `FinalDecisionEngine.kt` | Band selection |
| `DecisionBand.kt` | EXECUTE_*, WATCH, REJECT, BLOCK |

### Sizing
| File | Purpose |
|------|---------|
| `SmartSizerV3.kt` | Confidence-adjusted sizing |
| `WalletSnapshot.kt` | Available balance |
| `PortfolioRiskState.kt` | Drawdown tracking |

### Shadow Tracking
| File | Purpose |
|------|---------|
| `ShadowTracker.kt` | Track near-misses |
| `ShadowSnapshot.kt` | Captured state |
| `ShadowOutcome.kt` | Post-hoc classification |

### Learning
| File | Purpose |
|------|---------|
| `LearningEvent.kt` | Trade outcome data |
| `LearningMetrics.kt` | Win rate, payoff, etc. |

---

## Decision Bands

| Band | Score Min | Confidence Min | Size % |
|------|-----------|----------------|--------|
| EXECUTE_AGGRESSIVE | 65 | 55 | 9-12% |
| EXECUTE_STANDARD | 50 | 45 | 6-7% |
| EXECUTE_SMALL | 35 | 30 | 3-4% |
| WATCH | 20 | - | 0% (shadow track) |
| REJECT | <20 | - | 0% |
| BLOCK_FATAL | - | - | 0% (hard block) |

---

## Confidence Breakdown

```
Effective = 0.50 × Statistical + 0.35 × Structural + 0.15 × Operational
```

**Statistical (50%):**
- Classified trade count
- Recent win rate
- Payoff ratio
- False block rate
- Missed winner rate

**Structural (35%):**
- Total score
- Positive component count
- Negative component count

**Operational (15%):**
- API health
- Price feed health
- Wallet health
- Latency

---

## Migration Path

1. **Phase 1:** Add scoring modules alongside existing logic
2. **Phase 2:** Route decisions through UnifiedScorer
3. **Phase 3:** Remove hard blocks (except fatal)
4. **Phase 4:** Enable SmartSizerV3
5. **Phase 5:** Activate shadow tracking
6. **Phase 6:** Feed learning metrics back

---

## Key Insight

> "The unlock is treating everything as a score modifier, not a gate."

Old way: 10 things can block a trade → You miss winners
New way: 10 things contribute to score → You size appropriately

Same risk management. Better outcomes.

---

© 2025 AATE Project. All rights reserved.
