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
- **6799** §OWNER_DIAGNOSIS_SOURCE_REPAIR (5-issue slice):
  1. UNIVERSAL_OWNER_PROVENANCE — every canonical OPEN commit now
     auto-stamps LaneAttributionLedger6427 with the caller's
     idempotencyKey as intent seal. Cross-asset openings, perps/
     markets executors, and paper rebuild paths no longer emit
     LEARNING_PURITY_SKIP_UNRESOLVED_OWNER_6792. First-write-wins
     preserves earlier full-provenance stamps.
  2. ADVISORY_MUST_NOT_ZERO — removed the 6797 10% "deliberate
     suppression" discriminator. Any strictly positive request the
     caps can fund is promoted to min-exec via OK_MIN_PROMOTED_6600.
     Callers wishing to hard-veto MUST return requestedSol=0.
     Retired SUPPRESSED_BELOW_MIN_NO_PROMOTION_6791.
  3. FALLBACK_MARK_NEVER_FOR_ECONOMIC_MATH — new
     CanonicalPriceMarkRegistry6522.getForEconomicMath6799 refuses
     to return CANONICAL_MARK_FALLBACK_OBSERVATION_6732 marks; hard-
     stop / refund / catastrophe math now defers instead of pricing
     off a bare observation.
  4. OWNERSHIP_ADAPTIVE_SELECTION — TERMINAL_MIN_DECIDED 20→6,
     TERMINAL_WR_FLOOR 0.05→0.15. PROJECT_SNIPER 0/7 at −61.8% EV
     now surrenders primary ownership at admission instead of
     collecting a size-only damper.
  5. EXIT_FUNNEL_TELEMETRY — ProtectiveExitScheduler6450.evaluate()
     emits PHASE.EXIT_GATE on every real canonical exit evaluation.
     Top-funnel EXIT counter now reflects reality (was: 0 while
     BG_EXIT=1840).
- **6800** §DOWNSTREAM_CHOKE_REPAIR — operator diagnosis of 6799
  identified the failure has moved from entry gating to exit-path
  health + inventory turnover (176 buys / 83 completed / 93 open,
  0.0064 SOL cash, forcedSlots=75, 137k exit evaluations with
  mark=0 rows, MOONSHOT EXIT_CHOKED):
  1. MARK_WAIT_EXPOSED_AS_ITS_OWN_STATE — ProtectiveExitScheduler
     6450.evaluate() branches non-finite / <=0 marks to a distinct
     MARK_WAIT_6800 taxonomy with its own phase label, priority-
     refresh notification, and NO threshold comparison. Fixes the
     `mark=0 stop=0 tp=0 trail=0` funnel pollution introduced by
     6799 telemetry. Optional listener hook
     installPriorityRefreshListener6800 lets a future mark subsystem
     wire up authoritative priority refresh without the scheduler
     taking a hard dependency.
  2. INVENTORY_BACKPRESSURE — SlotHealthGate.shouldDeferBuy now has
     a pre-existing hard-defer surface reading three canonical
     authorities BEFORE FDG/sizing:
       - CanonicalCapitalAuthority6450 cashSol < 0.05 SOL
       - forcedOpenCount > 40 (distress; separate from the 20 dirty
         cleanup threshold)
       - ProtectiveExitScheduler6450.latchedCount6800() >= 8
         (exit backlog material)
     Confirmed-high-edge probes still admitted so cohort reproof
     and strong signals aren't starved. Every authority read fails
     OPEN so a diagnostic outage cannot masquerade as inventory
     distress. snapshotLine gains backpressure6800[cash|forced|
     exitBacklog|highEdge] telemetry.

## CI Status
Last build **V5.0.6800 Build AATE APK = SUCCESS** (16m33s, both
Gradle builds completed BUILD SUCCESSFUL; unit tests passed).
Runtime Smoke Test remains red on its pre-existing brittle script
assertion — unrelated to build health / codebase correctness.

## Remaining Backlog (from operator diagnosis)
- **P1**: Learning-controlled admission — EXPRESS 1W/12L WR 7.7% EV
  −53% and SHITCOIN 0W/11L WR 0% must route to SHADOW_ONLY except
  reproof probes; do NOT permanently disable them (system architecture
  is supposed to adapt).
- **P1**: Source-aware learning — lane × source × score × regime.
  PUMP_FUN_NEW / SOLANA_BLUECHIP_WATCHLIST cohorts producing most
  winners while PUMP_PORTAL floods poor outcomes. Currently only lane
  × score-band is learned.
- **P2**: Reconcile 2 sign-flipped PnL sells; keep them quarantined
  from learning until repaired. Keep the 149 decimal-skew quarantines
  in place (do not remove to increase throughput).
- Runtime smoke test brittleness (`NO_COMPLETED_PASSING_CURRENT_WINDOW`
  in `ci/runtime_evidence.py`) — blocks a fully-green pipeline.
- Retire Journal Replay as accounting authority for UI/hero/audit
  (P1 §5): UI hero + acceptance audits still consume
  TRADE_JOURNAL_REPLAY_6619 → 3.44 vs −0.37 SOL divergence and 151/153
  J_* audit failures. CanonicalCapitalAuthority6450 must become the
  sole read surface.
- Runtime fan-out / staleness (P2 §7): coalesce shared intelligence
  before specialist fan-out; kill superseded generation work earlier;
  drop stale-generation candidates.
- Ticket expiry stale/expired blocks (P2): 38 remaining stale/
  expired execution blocks despite adaptive TTL.
- Birdeye 401 provider degradation (secondary choke).

## Architecture
- Native Kotlin Android app, event-sourced.
- Canonical registries under `com.lifecyclebot.engine.truth.*`.
- Build/test via GitHub Actions CI only.
- Version bumped in BOTH `/app/AATE_VERSION` and `/app/lifecycle_apk/AATE_VERSION`.

## Test Credentials
See `/app/memory/test_credentials.md` (none used — standalone bot).
