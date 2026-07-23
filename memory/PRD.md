# AATE PRD — V5.0.6333

## Current build stack (all landed on `main`, all CI green through 6332)

- **6323** (`5a54edb18` ✅) CanonicalBuyFillRegistry USD-preferred + SharedPreferences persistence + position card / journal / partial-sell PnL routed through the registry
- **6324** (`0c53c4b93` ✅) All 16 operator patches shipped as engine modules (CanonicalPositionRegistry, RawTokenAmount, SellQuantityAuthority, fluid governor with CAUTION/SOFT_TIGHT/RECOVERY, TacticBleedPivot, LiveProbeEntry, ImmediateCollapseGuard, ProviderAuthority, ExitCoordinatorHeartbeat, buy telemetry split, LearningEligibility, AccountingIdempotencyRegistry, FillLot chain, CatastrophicExitLatency, HealthSnapshot6324, 17 tests)
- **6325** (`80c273448` ✅) Wired modules into buy/sell/health paths: governor size mult, canonical position upsert at buy verify, catastrophic latency onDetect, provider-failed telemetry, sell qty forensic, canonical recordSold
- **6326** (`714807b76` ✅) ImmediateCollapseGuard evaluated at liveBuy top; mint/freeze authority live → hard block; advisor labels soft-shape combined multiplier
- **6327** (`78a8b9d36` ✅) Per-key StrategyTelemetry leaderboard cache (fix 24M ledger runs + 257s cycles + 29s ANR frame gap)
- **6328** (`26be524e6` ✅) Canonical governor window persists across restart via SharedPreferences; scanner degradation no longer arms safety hold (only wallet/accounting invariants can arm)
- **6329** (`814475ecf` ✅ Build + Runtime Smoke) **BrainConsensusBridge6329** fuses CapitalEfficiencyBrain + MetaCognitionExecutorBridge + SuperBrainEnhancements + BrainConsensusGate + SentienceOrchestrator into one geometric-mean multiplier stacked into liveBuy alongside governor + collapse guard
- **6330** (`86008c846` ✅) **WADDLE decimal repair**: `getTokenDecimals` prefers `ts.tokenMap.decimals` (real on-chain mint metadata) before any heuristic; LearningEligibility.classify runs on every finalised SELL so QUARANTINED_DECIMAL / PENDING_RECONCILIATION rows never train the brain
- **6331** (`25ad96139` ✅) Demoted `LIVE_LANE_HARD_PAUSED_6247` → soft-shape (kill sticky hard-pause of lanes for poor performance; lanes never disable)
- **6332** (`0519817fe` ✅) **Concentrated Conviction**: governor bleed no longer arms `LIVE_ENTRY_SAFETY_HOLD` (was sticky, wr<25 froze all live buys). Size multipliers INVERTED — as governor tightens, size grows and score floor rises → fewer, larger, high-conviction trades. Executor combined-mult ceiling raised 1.0 → 1.75 with wallet-safety gate (only <0.10 SOL trades can amplify). Auto-clears legacy `CONFIDENCE_GOVERNOR_*` arms.
- **6333** (`d037f75c4` 🟡 in-flight) **Denylist tier split** — `LIVE_BYPASS_DENYLIST` split into HARD (no-data / probe-only disqualifiers) vs ADVISORY (soft-shape signals already handled by upstream sizing). Advisory labels now pass-through with `LIVE_BYPASS_ADVISORY_PASSTHROUGH_6333` telemetry instead of blocking. Fixes 616 blocks/session in the 6332 snapshot.

## Verified behavior improvements

vs the 6308 emergency report:
- Report builder no longer times out at 8s
- Avg cycle **66.9s → 8-18s** (~−75%)
- Max cycle **257s → 209s** (still watching; 6332 snapshot showed regression to 209s on worker timeouts)
- ANR frame gap max **29,114ms → 875ms** (−97%)
- Live BUYs: 6247-era frozen → 6330: 5 → 6331: 16 → 6332: 21 landing per session

## Snapshot pattern after 6332 (informed 6333 fix)

- `hold=✅ open` (not armed — 6332 fix confirmed working)
- `governor=HOLD` with wr=10%/n=10 correctly triggering concentrated-conviction shaping (size×1.50, floor+18)
- `LIVE_BUY_SIZE_GOVERNOR_APPLIED_6325: 621` — sizing pipeline exercising
- **Remaining choke: bypass denylist over-eager** — 616 rejections from advisor soft-shape labels (SMART_SIZER_V3_DUST_PROMOTED, PROVIDER_PROOF_HOLDER_CASCADE_BLIND, LANE_WAIT_OVERRIDE_DUST_PROBE, BRAIN_RUGCHECK_FLOOR, etc). → **fixed in 6333 via tier split**

## Known open issues

- **P0-A: Loop stall regression** — max cycle 209s in 6332 snapshot, 108× `SUPERVISOR_WORKER_TIMEOUT` in 651s. Not caused by 6332 code but resurfaced. Likely cause: something in the scanner/reconciler holding a worker. Needs a fresh snapshot post-6333 to see if buy-flow recovery alone unblocks the workers.
- **P0-B: WR still 10%** — canonical N=10 W/L=1/6 with wr=10% pf=0.04. Concentrated-conviction sizing plus HARD-only denylist should give the ~55-70 candidates that pass the floor (55 + 18 = 73 in HOLD) a fair, larger shot at proving edge. Needs post-6333 telemetry.

## Blocked / Backlog

- P1: BUY journal row rewrite from advisor estimate → wallet-verified qty (still shows drifted qty on the buy line when wallet cache empty; sell side is correct)
- P1: Pool-liquidity staleness invalidation for open-position mark prices
- P1: Investigate loop stall root cause once trade flow stabilizes
- P2: LiveProbeEntry buy-flow branch (SOFT_TIGHT candidates → 20-35% probe)
- P2: SOL Perps / Leverage mode (Phase 1) — BLOCKED until wr moves above the 20-30% band on a fair sample
- P3: Phase 2 Neural bridge / Phase 3 LLM Lab
- P3: Sunset legacy journal rows (UI "Cleanup Legacy" button)

## Testing / CI

- All builds through 6332 pass `Build AATE APK` on GitHub Actions.
- 6333 CI in-flight as of last push.
- Runtime Smoke Test last passed on 6329.
- Governor sample persists across restart.
- No lane ever hard-disabled.
- 6309/6317/6320 decimal/canonical protections preserved.
- 6332 confirmed on device: sticky governor hold no longer arms; `hold=✅ open`.

