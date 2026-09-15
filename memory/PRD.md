# AATE V5.7 — Product Requirements & Session Notes

## Original Problem Statement
Native Kotlin Android Solana paper trading bot ("AATE"). Build must go
through GitHub Actions CI (no local compiler). Two overarching operator
mandates:

1. **Source-Level Authority Convergence** — fix bugs at their source
   authority; never stack overlays or add new patch layers.
2. **Full-Stack Authority Consolidation** (Feb 2026 directive) — the AATE
   hive mind thinks together. Specialists contribute expertise. Learning
   changes real decisions. AATE may WAIT / REJECT / change strategy /
   exit when its thesis breaks. No legacy throughput patch may force a
   losing trade through.

## Session February 2026 — Shipped Fixes

**Prior slices (context):** V5.0.6768 – V5.0.6781.

**First authority-consolidation wave (this session):**
- **6782**: Sealed cognitive decision + downstream resurrection removal
  (PROVEN_DEAD HARD_BLOCK, zero-conf REJECT, LIVE_RESTORE soft-allows
  removed, forceAdaptiveRelaxation neutered).
- **6783**: Symbolic universe block authoritative in ALL modes, stale
  safety = WAIT, COPY/WHALE lane-forced probes retired.
- **6784**: EarlyLaunchBypass below-floor bypass retired.
- **6785 / 6787**: golden-tape test alignment.
- **6786**: Zero-signal + weak-wait now emit WAIT (no more PROBE_ONLY
  resurrection). **Per-trade fee send restored** — Executor +
  MarketsLiveExecutor send 50/50 share DIRECTLY per trade to the two
  coded fee wallets, no accumulator, FeeRetryQueue owns transients.

**Second wave — Feb 2026 SOURCE REPAIR PRIORITY (this session):**
- **6788** P0 §CANONICAL_MARK_AUTHORITY: sentinel-shape quarantine
  admits identity-proven marks.
- **6789** P0 §SINGLE_SEALED_ENTRY_AUTHORITY: cross-verdict intent
  supersession — a stale BUY intent cannot survive a later NO_BUY/WAIT
  for the same (mode, mint, candidateVersion).
- **6789** P0 §OWNER_ATTRIBUTION: full provenance (candidateVersion +
  sealedFdgId + intentId) stamped into LaneAttributionLedger6427 at
  every paper/live open commit.
- **6790** P1 §TTL_SINGLE_SOURCE: 4 specialist 30s hardcodes retired,
  every ticket/reservation now reads AdaptiveTicketTtl6626 (180s).
- **6791** P1 §REMOVE_MIN_NOTIONAL_RESURRECTION: OrderSizeResolver6441
  now only promotes benign rounding (within 10% of min). Deliberately
  suppressed sizes emit SUPPRESSED_BELOW_MIN_NO_PROMOTION_6791 and
  return NO_TRADE.
- **6792** P1 §LEARNED_BLEEDER_AUTHORITY: shadow exploration stream
  emits PROVEN_DEAD_SHADOW_EXPLORATION_6791 telemetry for every
  hard-blocked catastrophic cohort candidate (no canonical capital).
- **6792** P2 §LEARNING_PURITY: UnifiedPolicyHead.recordOutcome gated
  on LaneAttributionLedger6427.hasFullProvenance6789 — no lane-head
  training from unresolved-owner or pre-6789 hydrated closes.
- **6793 / 6794 / 6795**: aligned brittle tests to the new doctrines.

## Design Notes
- 100-position hard cap (ExitThroughputAuthority6727) is intentional.
- Paper trading is the production mode.

## Architecture
- Native Kotlin Android app, event-sourced.
- Canonical registries under `com.lifecyclebot.engine.truth.*`.
- Build/test via GitHub Actions CI only.
- Version bumped in BOTH `/app/AATE_VERSION` and `/app/lifecycle_apk/AATE_VERSION`.

## Remaining Backlog
- Runtime Smoke Test brittle assertion (`NO_COMPLETED_PASSING_CURRENT_
  WINDOW`) still fails independently of build health; needs script
  adjustment (P2).
- Runner Compound Widget (dashboard).
- Perps Neural Bridge (perps↔stocks cross-learning).
- LLM Lab sandbox.

## Test Credentials
See `/app/memory/test_credentials.md` (none used — standalone bot).
