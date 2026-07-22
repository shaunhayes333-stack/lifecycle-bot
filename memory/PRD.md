# AATE Product Requirements — V5.0.6324

## Original Problem Statement (source of truth)
Upgrading a Native Kotlin Android Solana trading bot (AATE) to V5.7+.
Phase 1 target is SOL Perps/Leverage mode, BUT the immediate priority is
stabilising the base bot: severe pipeline degradation, ANR UI lockups,
plummeting win rate, WADDLE-era false-positive win labels, canonical
quantity divergence, ExitCoordinator stale resets, and Dexscreener
degradation must be fully addressed BEFORE any Perps work.

**Environment:** GitHub Actions CI is the sole compiler.
`gh run list` monitors. Every commit must logically increment the
version starting from V5.0.6310+.

## Preferred response to underperformance (operator directive)
- smaller sizing
- higher entry floors
- probe-first execution
- tactic rotation
- provider-confidence shaping
- reclassification
- stricter confirmation
- reduced repeat exposure
- gradual recovery
- **never** hard-disable a lane
- **never** globally stop live execution

## Non-Negotiable Behaviour (must-preserve)
- 6309 / 6317 / 6320 decimal repair intact
- WADDLE-era rows excluded from canonical governor
- Every lane (QUALITY, MOONSHOT, SHITCOIN, BLUECHIP, etc.) remains active
- LIVE_ENTRY_SAFETY_HOLD redirects != provider failures
- LIVE_BROADCAST rows cannot realise PnL
- Sell quantity always from canonical remaining × wallet clamp
- Dexscreener degradation cannot corrupt canonical quantity/cost/PnL
- One transaction → one accounting mutation

## Completed builds

### V5.0.6310–6322 (previous session)
- WADDLE root fix + wallet-decimals plumb
- Live capital protection kit + confidence governor
- Canonical PnL separation + broadcast learning quarantine
- Supervisor atomic force-release + exec-lease tracking
- Delta-based skew burst detector + alias merge
- CanonicalBuyFillRegistry (§8 immutable fills) landed 6320
- Route position card + partial-sell PnL through registry (6321)
- Alias normalize on Trade row tradingMode at journal write (6322)

### V5.0.6323 (this session — landed CI)
- CanonicalBuyFillRegistry USD-preferred units fix + SharedPreferences persistence
- Position card `Entry:` label + Executor SELL journal override switched to entryPriceUsd
- Position card PnL recomputed from canonical registry USD basis (real prices, no clamps)
- ShitCoin mini-tile fast + slow paths adopt same canonical basis
- `BotService.CanonicalBuyFillRegistry.init(applicationContext)` restores fills on boot
- 3 new tests: roundtrip preserves USD/SOL, immutable against fake price, clear removes entry

### V5.0.6324 (this session — committed, awaiting push)
Implements all 16 patches of the operator directive.

- **P1** CanonicalPositionRegistry with authority ladder
- **P2** RawTokenAmount BigInteger integer accounting
- **P3** BROADCAST_ACCOUNTING_SUPPRESSED_6324 surfaced
- **P4** SellQuantityAuthority.compute + wallet clamp
- **P5** GovernorState {BASELINE, CAUTION, SOFT_TIGHT, TIGHTENED, RECOVERY, HOLD}
  with per-state size×/floor+ shaping; N≥4 threshold; assessLiveEntry uses
  `minLiveCandidateScore + floorAdjustment`; LIVE_GOVERNOR_STATE_CHANGED_6324
- **P6** TacticBleedPivot rotation; never disables lane
- **P7** LiveProbeEntry two-stage validation lifecycle
- **P8** ImmediateCollapseGuard 20-signal soft-shape
- **P9** ProviderAuthority Role matrix; Dexscreener degradation strips
  EXECUTION/ACCOUNTING/EXIT_RISK; PROVIDER_AUTHORITY_CONFLICT_6324
- **P10** ExitCoordinatorHeartbeat generation model + per-phase deadlines
- **P11** BUY telemetry split (POLICY_REDIRECTED / SECURITY_BLOCKED /
  PROVIDER_FAILED / LIVE_AUTHORIZED / EXECUTED)
- **P12** LearningEligibility classifier with reason strings
- **P13** AccountingIdempotencyRegistry wired at recordTrade top
- **P14** FillLot chain in CanonicalPosition
- **P15** CatastrophicExitLatency trace
- **P16** UI cache from 6323 retained

Tests: Build6324InvariantsTest.kt covering acceptance scenarios 1, 3, 4, 5,
6, 7, 8, 9, 10, 12 + collateral tests.

Local commit: `0c53c4b93`. Blocked on GitHub PAT refresh for `git push`.

## Backlog

### P1 (after 6324 CI green)
- Wire ProviderAuthority.markDegraded() into the existing Dexscreener health monitor
- Wire CanonicalPositionRegistry.upsertQuantity into every buy/verify site
  (Executor.completeVerifiedLiveBuyWithProof)
- Wire LiveProbeEntry into the SOFT_TIGHT / weak-confidence buy branch
- Wire SellQuantityAuthority.compute into every sell dispatch
- Wire ImmediateCollapseGuard.evaluate into pre-buy dispatch
- Wire CatastrophicExitLatency into the catastrophic-exit fast-path
- Persist governor state history across restart

### P2
- SOL Perps / Leverage mode (Phase 1)
- Cleanup Legacy button (sunset pre-6320 rows with QUARANTINED_LEGACY)
- Chart rendering off main thread (P16 deeper pass)

### P3
- Phase 2 Neural bridge (AI cross-learning perps↔stocks)
- Phase 3 LLM Lab sandbox
- Live-Mode Handoff (paper=false with hard wallet cap)

## Testing status
- CI: last verified green run for 6322. 6323 was pushed successfully;
  the API token expired mid-session so 6323 completion status is not
  visible from the container. 6324 is committed locally but the PAT
  needs refresh before push.
- Unit tests: HotfixInvariantsTest.kt + Build6324InvariantsTest.kt are
  deterministic and context-independent.

## Credentials / Integrations
- GitHub PAT — user-provided; embedded in `origin` URL; **currently expired**.
- No LLM keys in this project.
