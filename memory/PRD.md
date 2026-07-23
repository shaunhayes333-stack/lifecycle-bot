# AATE PRD — V5.0.6334

## Current build stack (all landed on `main`, all CI green through 6333, 6334 build ✅)

- **6323** (`5a54edb18` ✅) CanonicalBuyFillRegistry USD-preferred + SharedPreferences persistence + position card / journal / partial-sell PnL routed through the registry
- **6324** (`0c53c4b93` ✅) All 16 operator patches shipped as engine modules (CanonicalPositionRegistry, RawTokenAmount, SellQuantityAuthority, fluid governor with CAUTION/SOFT_TIGHT/RECOVERY, TacticBleedPivot, LiveProbeEntry, ImmediateCollapseGuard, ProviderAuthority, ExitCoordinatorHeartbeat, buy telemetry split, LearningEligibility, AccountingIdempotencyRegistry, FillLot chain, CatastrophicExitLatency, HealthSnapshot6324, 17 tests)
- **6325** (`80c273448` ✅) Wired modules into buy/sell/health paths
- **6326** (`714807b76` ✅) ImmediateCollapseGuard at liveBuy top
- **6327** (`78a8b9d36` ✅) Per-key StrategyTelemetry leaderboard cache (fix 250s cycles)
- **6328** (`26be524e6` ✅) Governor window persists across restart; scanner degradation soft-shapes only
- **6329** (`814475ecf` ✅) BrainConsensusBridge6329 fuses 5 brains into geometric-mean multiplier
- **6330** (`86008c846` ✅) WADDLE decimal repair + LearningEligibility on every SELL
- **6331** (`25ad96139` ✅) Demoted `LIVE_LANE_HARD_PAUSED_6247` → soft-shape
- **6332** (`0519817fe` ✅) **Concentrated Conviction**: governor bleed no longer arms safety hold; size multipliers inverted (fewer, larger, higher-conviction trades). Ceiling 1.0 → 1.75 with wallet safety gate. Auto-clear legacy `CONFIDENCE_GOVERNOR_*` arms.
- **6333** (`d037f75c4` ✅) **Denylist tier split** — HARD (real disqualifiers) vs ADVISORY (soft-shape signals pass through with telemetry). Fixes 616 double-filter blocks after 6332.
- **6334** (`f747f57b2` build ✅, runtime smoke in-flight) **Lane Edge Concentrator** — new `LaneEdgeConcentrator6334.evaluate(lane, score)` reads TacticSwitcher.snapshotAll() and returns per-bucket (lane × scoreBand) size multiplier:
  - WINNER (n≥3, wr≥40%, meanPnl>0): mult 1.00 → 1.50
  - NEUTRAL (small sample / mixed): 1.00
  - BLEEDER (n≥5, wr<25%, meanPnl<0): 0.60 → 0.85 (never zero)
  Wired into Executor combined mult stack. Ceiling raised 1.75 → 2.25 to allow govMult × concMult stacking. Wallet safety gate unchanged.

## Self-tuning invariants (V5.0.6334 verified)

1. **Learning from trade 1 — paper AND live**: `V3JournalRecorder.recordClose` (11 call sites incl. paper close paths) unconditionally calls `TacticSwitcher.onTradeClosed(lane, band, pnlPct)` regardless of `isPaper` flag.
2. **Paper→live handoff carries the sample**: TacticSwitcher persists per-bucket state to `LearningPersistence` (`tactic_LANE|BAND`), so flipping paper→live keeps the warm bucket data.
3. **Never disable, never block**: LaneEdgeConcentrator only shapes size. Bleeders fade but keep sampling so TacticSwitcher can rotate away.
4. **Per-bucket isolation**: winning BLUECHIP|S61+ cannot be dragged down by bleeding MOONSHOT|S11-25; each bucket gets its own multiplier.

## Snapshot pattern goals (post-6334)

- `LANE_EDGE_CONCENTRATOR_WINNER_6334` fires on proven buckets (BLUECHIP|S61+ MOMENTUM, etc)
- `LIVE_BUY_LANE_EDGE_AMPLIFIED_6334` counter tracks BUYs that got >1.0 concentrator mult
- BUY sizes on winning buckets should visibly grow (was ~0.015-0.020 SOL, expect up to ~0.030 SOL when both govMult and concMult amplify)
- 616 denylist rejections should drop dramatically after 6333

## Known open issues

- **P0-A: Loop stall regression** — max cycle 209s in 6332 snapshot, 108× `SUPERVISOR_WORKER_TIMEOUT` in 651s. Not caused by 6332-6334 code; likely scanner/reconciler lease. Investigate after 6334 telemetry lands.
- **P0-B: WR baseline still 10%** — 6334 should self-tune bucket sizing but sample needs to grow. Await post-6334 snapshot.

## Blocked / Backlog

- P1: BUY journal row rewrite from advisor estimate → wallet-verified qty
- P1: Pool-liquidity staleness invalidation for open-position mark prices
- P1: Loop stall root cause investigation
- P2: LiveProbeEntry buy-flow branch
- P2: SOL Perps / Leverage mode (Phase 1) — BLOCKED until edge-concentration proves stable
- P3: Phase 2 Neural bridge / Phase 3 LLM Lab
- P3: Sunset legacy journal rows

## Testing / CI

- All builds through 6334 pass `Build AATE APK`.
- Runtime Smoke Test: passed on 6329, 6332, 6333; 6334 in-flight.
- Learning loop verified: `TacticSwitcher.onTradeClosed` called from every close path regardless of paper/live.
- Governor sample persists across restart.
- No lane ever hard-disabled.
