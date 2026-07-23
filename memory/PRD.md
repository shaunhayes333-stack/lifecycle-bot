# AATE PRD — V5.0.6330

## Current build stack (all landed on `main`, all CI green)

- **6323** (`5a54edb18` ✅) CanonicalBuyFillRegistry USD-preferred + SharedPreferences persistence + position card / journal / partial-sell PnL routed through the registry
- **6324** (`0c53c4b93` ✅) All 16 operator patches shipped as engine modules (CanonicalPositionRegistry, RawTokenAmount, SellQuantityAuthority, fluid governor with CAUTION/SOFT_TIGHT/RECOVERY, TacticBleedPivot, LiveProbeEntry, ImmediateCollapseGuard, ProviderAuthority, ExitCoordinatorHeartbeat, buy telemetry split, LearningEligibility, AccountingIdempotencyRegistry, FillLot chain, CatastrophicExitLatency, HealthSnapshot6324, 17 tests)
- **6325** (`80c273448` ✅) Wired modules into buy/sell/health paths: governor size mult, canonical position upsert at buy verify, catastrophic latency onDetect, provider-failed telemetry, sell qty forensic, canonical recordSold
- **6326** (`714807b76` ✅) ImmediateCollapseGuard evaluated at liveBuy top; mint/freeze authority live → hard block; advisor labels soft-shape combined multiplier
- **6327** (`78a8b9d36` ✅) Per-key StrategyTelemetry leaderboard cache (fix 24M ledger runs + 257s cycles + 29s ANR frame gap)
- **6328** (`26be524e6` ✅) Canonical governor window persists across restart via SharedPreferences; scanner degradation no longer arms safety hold (only wallet/accounting invariants can arm)
- **6329** (`814475ecf` ✅ Build + Runtime Smoke) **BrainConsensusBridge6329** fuses CapitalEfficiencyBrain + MetaCognitionExecutorBridge + SuperBrainEnhancements + BrainConsensusGate + SentienceOrchestrator into one geometric-mean multiplier stacked into liveBuy alongside governor + collapse guard
- **6330** (`86008c846` ✅) **WADDLE decimal repair**: `getTokenDecimals` prefers `ts.tokenMap.decimals` (real on-chain mint metadata) before any heuristic; LearningEligibility.classify runs on every finalised SELL so QUARANTINED_DECIMAL / PENDING_RECONCILIATION rows never train the brain

## Verified behavior improvements

vs the 6308 emergency report:
- Report builder no longer times out at 8s
- Avg cycle **66.9s → 33.6s** (−50%)
- Max cycle **257s → 169s** (−35%)
- ANR frame gap max **29,114ms → 816ms** (−97%)
- Worker timeouts (rate) −65%
- ExitCoordinator stale resets (rate) −80%

## Blocked / Backlog
- P1: BUY journal row rewrite from advisor estimate → wallet-verified qty (still shows drifted qty on the buy line when wallet cache empty; sell side is correct)
- P1: Pool-liquidity staleness invalidation for open-position mark prices
- P2: LiveProbeEntry buy-flow branch (SOFT_TIGHT candidates → 20-35% probe)
- P2: SOL Perps / Leverage mode (Phase 1)
- P3: Phase 2 Neural bridge / Phase 3 LLM Lab

## Testing / CI
- All builds through 6330 pass `Build AATE APK` on GitHub Actions.
- Runtime Smoke Test passed on 6329 (814475ec).
- Governor sample now persists across restart.
- No lane hard-disabled at any point.
- 6309/6317/6320 decimal/canonical protections preserved.
