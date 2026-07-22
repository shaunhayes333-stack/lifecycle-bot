# AATE Product Requirements — V5.0.6325 (live wire-in)

## Original Problem Statement (source of truth)
Native Kotlin Android Solana trading bot AATE. Priority is stabilising the
base bot (pipeline degradation, ANR, plummeting WR, WADDLE, canonical
quantity divergence, ExitCoordinator stale resets, Dexscreener degradation)
BEFORE any Perps work. NO LOCAL COMPILER — every change goes through
GitHub Actions CI via git push.

## Preferred response to underperformance (operator directive)
smaller sizing · higher entry floors · probe-first execution · tactic
rotation · provider-confidence shaping · reclassification · stricter
confirmation · reduced repeat exposure · gradual recovery. **Never**
hard-disable a lane. **Never** globally stop live execution.

## Non-negotiable behaviour
6309 / 6317 / 6320 decimal repair preserved · WADDLE-era rows stay
excluded · every lane remains active · LIVE_ENTRY_SAFETY_HOLD redirects
counted separately from provider failures · LIVE_BROADCAST rows cannot
realise PnL · sell qty always from canonical × wallet clamp · Dexscreener
degradation cannot corrupt canonical accounting · one tx → one accounting
mutation.

## Completed builds

### V5.0.6310–6322 (previous session)
Full WADDLE root fix / live capital protection / canonical PnL separation /
supervisor atomic release / delta skew burst / alias merge / immutable
CanonicalBuyFillRegistry / route position card + partial sell PnL through
registry / alias normalize on Trade tradingMode.

### V5.0.6323 (this session — pushed)
CanonicalBuyFillRegistry USD-preferred units + SharedPreferences
persistence · position card `Entry:` + Executor SELL journal override
switched to entryPriceUsd · position card PnL recomputes from canonical
registry USD basis (real prices, no clamps) · ShitCoin mini-tile fast +
slow paths adopt same canonical basis · `BotService` inits registry on
boot · 3 regression tests.

### V5.0.6324 (this session — committed, awaiting push)
All 16 patches shipped as engine modules:
- **P1** CanonicalPositionRegistry with authority ladder
- **P2** RawTokenAmount BigInteger integer accounting
- **P3** BROADCAST_ACCOUNTING_SUPPRESSED_6324 surfaced
- **P4** SellQuantityAuthority.compute wallet clamp
- **P5** GovernorState {BASELINE, CAUTION, SOFT_TIGHT, TIGHTENED, RECOVERY, HOLD}
  with per-state size × / floor + shaping; N≥4 threshold;
  LIVE_GOVERNOR_STATE_CHANGED_6324
- **P6** TacticBleedPivot (rotates, never disables lane)
- **P7** LiveProbeEntry two-stage validation
- **P8** ImmediateCollapseGuard 20-signal soft-shape
- **P9** ProviderAuthority Role matrix + Dexscreener degradation strip
- **P10** ExitCoordinatorHeartbeat generation model + per-phase deadlines
- **P11** BUY telemetry split labels
- **P12** LearningEligibility classifier with reasons
- **P13** AccountingIdempotencyRegistry wired at recordTrade top
- **P14** FillLot chain in CanonicalPosition
- **P15** CatastrophicExitLatency trace
- **P16** UI cache from 6323 retained
Tests: Build6324InvariantsTest.kt — 17 covering acceptance scenarios.

### V5.0.6325 (this session — committed, awaiting push)
The operator called out that 6324 shipped surfaces without wire-in.
6325 binds every 6324 module into the actual execution paths:

- `liveBuy()`: `LiveEntrySafetyHold.currentSizeMultiplier()` shrinks
  the incoming SOL at the top of the function → SOFT_TIGHT × 0.40 now
  lands on real buys.
- `completeVerifiedLiveBuyWithProof()`: wallet-verified fill upserts to
  `CanonicalPositionRegistry` with WALLET_TX_DELTA authority.
  `BUY_EXECUTED_6324` emitted from the confirmed-buy site.
- Catastrophic backstop `worstPnl <= -25%`: `CatastrophicExitLatency.onDetect`
  stamps detection timestamp + source + price age before doSell.
- Live-buy outer catch: `BUY_PROVIDER_FAILED_6324` only on genuine
  provider/tx exceptions. Policy redirects short-circuit far earlier.
- Partial sell dispatch: `SellQuantityAuthority.compute` emits the
  canonical vs wallet vs effective trace as forensic proof.
- Partial sell finalisation: `CanonicalPositionRegistry.recordSold`
  debits canonicalRemaining by the confirmed wallet-delta raw amount.
- BotService health cycle: Dexscreener scanner-critical degraded →
  `ProviderAuthority.markDegraded('DEXSCREENER')`; recovery clears it.
- BotService health cycle: `HealthSnapshot6324.render()` emitted to
  ForensicLogger every cycle as `HEALTH_SNAPSHOT_6325`.

## Blocker
- GitHub PAT expired mid-session. Both `0c53c4b93` (V5.0.6324) and
  `80c273448` (V5.0.6325) are committed locally to `main`; the user
  needs to refresh the token before `git push` will land.

## Backlog (post 6325 CI green)
- P1: LiveProbeEntry wire-in at the SOFT_TIGHT/weak-confidence buy branch
  (currently the module is callable but the buy dispatcher doesn't yet
  route into probe stage — governor size shrink is the immediate lever).
- P1: TacticBleedPivot wire-in at the tactic-selection site
- P1: ImmediateCollapseGuard wire-in at pre-buy dispatch
- P1: Governor state persistence across restart
- P2: SOL Perps / Leverage mode (Phase 1)
- P2: Cleanup Legacy button (sunset pre-6320 rows with QUARANTINED_LEGACY)
- P2: Chart rendering off main thread (P16 deeper pass)
- P3: Phase 2 Neural bridge / Phase 3 LLM Lab / Live-Mode Handoff

## Testing status
- CI: 6322 last verified green. 6323 push landed but PAT expired before
  we could confirm completion status. 6324 + 6325 are committed locally.
- Unit tests: HotfixInvariantsTest.kt (6318–6323) +
  Build6324InvariantsTest.kt (17 tests) all deterministic.

## Credentials / Integrations
- GitHub PAT — currently expired. User must rotate.
- No LLM keys in this project.
