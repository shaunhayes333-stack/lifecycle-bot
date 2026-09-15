# AATE V5.7 — Product Requirements & Session Notes

## Original Problem Statement
Native Kotlin Android Solana paper trading bot ("AATE"). Build must go
through GitHub Actions CI (no local compiler). Operator mandates:

1. **Source-Level Authority Convergence** — fix at source, no overlays.
2. **Full-Stack Authority Consolidation** — AATE hive mind thinks together.
   No throughput patch may force a losing trade through.

## Session February 2026 — Full History

### Wave 1 — Authority-Consolidation
- **6782** Sealed cognitive decision + downstream resurrection removal
  (PROVEN_DEAD HARD_BLOCK, zero-conf REJECT, LIVE_RESTORE soft-allows
  removed, forceAdaptiveRelaxation neutered).
- **6783** Symbolic universe block authoritative in ALL modes;
  stale safety = WAIT; COPY/WHALE lane-forced probes retired.
- **6784** EarlyLaunchBypass below-floor bypass retired.
- **6785 / 6787** golden-tape test alignment.
- **6786** Zero-signal + weak-wait now emit WAIT (no more PROBE_ONLY
  resurrection). **Per-trade fee send restored** — Executor +
  MarketsLiveExecutor send 50/50 share DIRECTLY per trade to the two
  coded fee wallets, no accumulator.

### Wave 2 — Feb 2026 SOURCE REPAIR PRIORITY
- **6788** P0 §CANONICAL_MARK_AUTHORITY — sentinel-shape quarantine
  admits identity-proven marks.
- **6789** P0 §SINGLE_SEALED_ENTRY_AUTHORITY (later rolled back in 6796
  because it over-fired on metadata drift; owner-attribution wiring
  from same slice retained).
- **6789** P0 §OWNER_ATTRIBUTION — full provenance (candidateVersion +
  sealedFdgId + intentId) stamped into LaneAttributionLedger6427 at
  every open commit.
- **6790** P1 §TTL_SINGLE_SOURCE — 4 specialist 30s hardcodes retired;
  every ticket/reservation now reads AdaptiveTicketTtl6626 (180s).
- **6791** P1 §REMOVE_MIN_NOTIONAL_RESURRECTION — OrderSizeResolver6441
  90% band added (later widened in 6797).
- **6792** P1 §LEARNED_BLEEDER_AUTHORITY shadow stream +
  P2 §LEARNING_PURITY — UnifiedPolicyHead training gated on
  hasFullProvenance6789.
- **6793 / 6794 / 6795** brittle test alignment.

### Wave 3 — Operator Diagnosis Feb 2026 Top-3 Critical
- **6796** (by operator) — rolled back 6789 supersession-churn while
  keeping the owner-attribution provenance wiring.
- **6797** — TWO CRITICAL FIXES:
    - **OrderSizeResolver6441** — 6791 90% band was killing legitimate
      FDG-approved intents (ALMOND 0.00506, DANGR 0.01929). Replaced
      with a 10%-of-min-exec absolute floor: below 10% is deliberate
      stacked-multiplier suppression (operator's 0.002-vs-0.050 example);
      at/above 10% is authoritative micro-notional that promotes to
      min when caps fund it.
    - **PaperLedgerDivergenceGuard6731** — journal projection was
      blocking admissions while canonical reconciler reported
      mismatchesEver=0. Added
      CanonicalReconciler6441.mismatchesEver() accessor and the
      guard now fails-open with OK_CANONICAL_CLEAN_6797 whenever the
      authoritative reconciler is clean.
- **6798** §LEARNING_ACK_PURITY — CausalFeedbackAuthority6715.
  markLearned was false-ACKing 50/50 unresolved-owner closes. Now
  gated on hasFullProvenance6789; unresolved-owner closes skip the
  ACK entirely (label CAUSAL_ACK_SKIPPED_UNRESOLVED_OWNER_6798).

## CI Status
Last build **V5.0.6798 Build AATE APK = SUCCESS**. Runtime Smoke Test
still red on its pre-existing brittle script assertion — unrelated to
build health.

## Remaining Backlog (from operator diagnosis)
- HIGH: mark authority still admitting corrupted/fallback prices far
  enough downstream to cause extreme exits/refunds
  (CANONICAL_MARK_FALLBACK_OBSERVATION_6732: 2634).
- HIGH: exit coordinator lifecycle detached from the actually-firing
  background/risk-clock exit mechanism (`EXIT: 0` telemetry vs 468
  background exits).
- HIGH: PROJECT_SNIPER/restored-position poisoning quality metrics.
- MEDIUM: 38 stale/expired execution blocks despite adaptive TTL.
- MEDIUM: Birdeye 401 provider degradation (secondary choke).

## Architecture
- Native Kotlin Android app, event-sourced.
- Canonical registries under `com.lifecyclebot.engine.truth.*`.
- Build/test via GitHub Actions CI only.
- Version bumped in BOTH `/app/AATE_VERSION` and `/app/lifecycle_apk/AATE_VERSION`.

## Test Credentials
See `/app/memory/test_credentials.md` (none used — standalone bot).
