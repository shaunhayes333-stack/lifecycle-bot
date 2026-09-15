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
Last build **V5.0.6808 Build AATE APK = SUCCESS** (13m24s; unit
tests + patch_rot_scan + golden_tape_literal_scan + authority_
contradiction_scan all passed). Runtime Smoke Test remains red on
its pre-existing brittle script assertion — unrelated to build
health.

## Feb 2026 Slice Log (6805–6808)
- **6805** §CAUSAL_INTEGRITY + §RETIRE_JOURNAL_REPLAY_ACCOUNTING +
  §LANE_CONCENTRATION_CEILING. Six architectural authority repairs
  (direction cherry-picked from ChatGPT's repair/6805-causal-
  integrity branch, applied as direct source edits, no generator
  layer):
  1. LOSS_STREAK_CAUSALITY — cooldown arms only at STREAK_HARD_
     LIMIT breach (not per loss); WIN clears cohortLosses AND
     cohortCooldownMs AND the new lossStreakVetoLatched6805 map;
     cooling no longer counts toward hard-veto; per-cohort veto
     latch so telemetry is a cohort transition not per-candidate.
  2. PAPER_FDG_WITHOUT_SEAL_IS_A_DEFERRAL — paper mode downgrades
     the AUTHORITY_INVARIANT_FAILURE window (fdgCan=true, seal=null)
     to FDG_ALLOW_AWAITING_EXEC_INTENT_6805; stale (>5s) unsealed
     paper state also destroys the provisional state so the next
     tick re-obtains a fresh seal. LIVE still hard-fails.
  3. EXIT_COORDINATOR_STALE_RESET_SELF_GUARD — staleReset consults
     shouldStaleReset at the destructive boundary and uses compare-
     and-remove; force=true retained for legitimate cleanup callers.
  4. LANE_CONCENTRATION_CEILING — LANE_INVENTORY_MAX_SHARE_6805 =
     0.35. Book >= 10 open + lane > 4 absolute → veto with
     EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805. Fixes PROJECT_
     SNIPER at 69% of the book despite 11.5% capital target.
  5. RETIRE_JOURNAL_REPLAY_ACCOUNTING — UI hero
     (UnifiedAccountSnapshot6635) reads CanonicalCapitalAuthority
     6450 ONLY. Removed JournalEconomicAuthority6616.currentSnapshot
     () and ForensicReconciliation6635.reconcile6635() from render
     path. Status = abs(conservationDeltaSol) ≤ 1e-4. Acceptance
     InvariantAudit6441 gains canonical_capital_conserved_6450
     pass criterion.
  6. BAND_LOCAL_TERMINAL_INVALIDATION — CausalFeedbackAuthority
     6715 advances terminalEpoch + learningRevision ONLY on BAND-
     scoped keys. Aggregate lane wins/losses/openPositions still
     update so advisories see live truth, but reservation freshness
     is band-local. Emits CAUSAL_SCOPE_LOCAL_INVALIDATION_6805.
- **6806** hotfix: stripped retired-authority literals from
  UnifiedAccountSnapshot6635 docstring + veto comment so the
  Aate6756PipelineRecoveryTest source-pin negative-assertion holds.
- **6807** hotfix: flipped patch_rot_scan.py UNIFIED_ACCOUNT_
  OBSERVATION_6678 contract to UNIFIED_ACCOUNT_READ_PURITY_6805 +
  UNIFIED_ACCOUNT_CANONICAL_SOURCE_6805 (forbid the retired calls,
  require the canonical source markers).
- **6808** §STALE_MARK_CLOSE_IS_ECONOMIC_NOT_REFUND — operator
  live report Feb 2026: "the balance isn't building. every recent
  SELL is REFUND:UNTRUSTED_DYNAMIC_MARK_ADMIN_REFUND_6663 at
  pnl=+0.000." The 6663 refund path was a safety net for genuine
  data-integrity failures but with 15k+ stale/missing marks it
  became the dominant exit path so every close short-circuited to
  net zero. CryptoAltTrader.settleUntrustedDynamicPaperPosition6663
  now uses the last observed mark to compute a real economic close
  when identity is verified and a mark was ever seen. PnL bounded
  to [-100%*leverage, +10,000%]. Legacy refund retained for
  IDENTITY_UNRESOLVED, no-mark-ever-observed, and economic-close-
  apply-failed cases. Emits CRYPTO_DYN_STALE_MARK_ECONOMIC_
  CLOSE_6808 with full economics.

## Feb 2026 Slice Log (6803–6804)
- **6803** §HEARTBEAT_IS_NOT_MARK_WAIT + §CLOSED_STAYS_STICKY +
  §LOSS_STREAK_HARD_CREED_ENFORCEMENT. Fixes the three dominant
  6802 downstream defects the operator surfaced:
  1. ProtectiveExitScheduler6450 gained a dedicated `heartbeat()`
     surface that bumps the eval + watchdog counters without
     emitting MARK_WAIT_6800 / PHASE.EXIT_GATE. Both wall-clock and
     bot-loop cadence heartbeats now call heartbeat() instead of
     evaluate(markPx=0). MARK_WAIT_6800 now fires only when a
     caller genuinely believed it had a mark. Fake `~100% mark-wait`
     alarm from 6802 dumps eliminated.
  2. PositionCloseLedger.clearIfCanonicallyReopened6699 now honours
     a 5-second reentry grace window past the close stamp. The
     SELL-confirm race that was zapping 776 legitimate close stamps
     against 65 canonical closed positions is resolved — canonical
     CLOSED remains sticky until propagation completes.
  3. ExecutableEntryAuthority6450.gate upgrades STREAK_HARD_LIMIT
     (3 consecutive losses) from a size shaper (0.35) to a hard
     DENY_LOSING_STREAK / LOSS_STREAK_HARD_VETO_6803 with reproof-
     probe carve-out. Cool-down (existing STREAK_COOLDOWN_MS) still
     enforces observation. Sub-limit streaks continue on the shaper
     ladder. Retires the '10-streak while shrinking size' regression.
- **6804** hotfix: Golden Tape 6488 shaper-ladder assertion updated
  to attest the 6803 hard-veto evolution.

## Feb 2026 Slice Log (6801–6802)
- **6801** §LEARNING_MUST_CONTROL_ADMISSION + §SOURCE_AWARE_LEARNING
  + §FROZEN_DECISION_MUST_YIELD + §FDG_ZERO_QUALITY_HARD_VETO +
  §POLICY_NEGATIVE_EV_HARD_VETO. Fixes all five smoking guns from
  the operator's 6800 diagnosis:
  1. ExecutableEntryAuthority6450.gate HARD-DENIES chronic terminal
     loser lanes/sources (WR under ~7.5% with adequate sample).
     Reproof probes still admitted at PROBE_SIZE_SOL.
  2. Executor.kt:11000 renamed FDG_MUTABLE_SIGNAL_IGNORED_6512 →
     FDG_MUTABLE_SIGNAL_UNFROZEN_6801 with release-and-defer. Newer
     WAIT signals now yield the sealed election; provenance stays.
  3. FinalDecisionGate.kt hard-vetos FDG BUY when laneScore=0 AND
     entryScore=0 AND aiConfidence=0 AND edgeConfidence=0.
  4. AateDecisionEnvelope6512.PolicySynthesizer refuses to stamp BUY
     when weighted EV <= -3.0% (with at least one EV contributor).
  5. New source-aware learning: LaneAttributionLedger6427.Entry
     gains discoverySource; CausalFeedbackAuthority6715 subscribes
     to the canonical terminal bus and accumulates per-source
     outcomes; sourceLoserAdvisory6801 exposes admission-level veto
     alongside lane advisory. Non-disruptive to ticket lifecycle.
- **6802** hotfix compile: aligned CohortLoserAdvisory field names
  (worstWinRatePct / worstDecidedCount vs SourceLoserAdvisory
  winRatePct / decidedCount).

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

## V5.0.6809 — SOURCE-LEVEL AUTHORITY CONVERGENCE (2026-02)

Operator mandate: "Learning must control capital. Throughput must never
overrule proven negative expectancy."

- **BOOTSTRAP retired as an execution authority.** `AuthorityTier.BOOTSTRAP`
  remains as a wire/DB compat value but `UnifiedPolicyHead.currentAuthority`
  and `ScannerSourceBrain.authority` never return it at runtime — cold heads
  return `ADVISORY` (neutral learned prior). `BOOTSTRAP_FLOOR_PAPER_BYPASS`,
  `BOOTSTRAP_MIN_CONFIDENCE_SOFT`, `BOOTSTRAP_OVERRIDE`, and the
  `PAPER BOOTSTRAP PROBE` warm-up bypasses in `FinalDecisionGate` are
  removed. `canBypassConfidenceFloors`, `isBootstrapPhase`, and
  `isBootstrap` collapsed to `false` at source.
- **True non-executable shadow/train verdicts restored.** `FdgRouteVerdict`
  now maps `SHADOW_TRACK_ONLY → ROUTE_SHADOW_TRACK` (non-exec, trainable)
  and `TRAIN_ONLY_NO_OPEN → ROUTE_TRAIN_ONLY` (non-exec, trainable). The
  V5.9.1325 collapse to `ALLOW_PAPER_MICRO` is deleted.
- **Negative-EV veto is final.** `AateDecisionEnvelope6512` policy synth
  downgrades any BUY-like action with weighted EV ≤ -3% to
  `POLICY_NEG_EV_BLOCK_6801`. `FinalDecisionGate` now honours both `BLOCK`
  and `POLICY_NEG_EV_BLOCK_6801` as hard vetoes (was: `BLOCK` only), with
  telemetry `FDG_HONORED_AATE_NEG_EV_VETO_6809`.
- **Execution intent finality.** `ExecutableOpenGate.registerCanonicalIntent6554`
  evicts any prior sealed intent whose `authorityVersion` / `fdgAllowed` /
  `finalDecision6613` / `action` has been superseded via new helper
  `intentSupersedes6809`. Telemetry: `EXEC_INTENT_INVALIDATED_ON_POLICY_CHANGE_6809`.
- **Min-size promotion killed.** `OrderSizeResolver6441.OK_MIN_PROMOTED_6600`
  and `canFundMinimum6600` removed. Sub-minimum adaptive requests resolve
  as `SUB_MIN_ADAPTIVE_HELD_6809` (non-executable); the caller routes to
  shadow/train observation instead of capital-floor manufacture.
- **Bleeder / source admission.** `ScannerSourceBrain.sourceCapitalExecutionSuppressed6809`
  and `LaneExpectancyDamper.laneCapitalExecutionSuppressed6809` now feed
  actual admission authority in `ExecutableEntryAuthority6450` — a source
  whose settled avg PnL ≤ -3% (n ≥ 40) or a lane whose damper multiplier
  ≤ 0.20 hard-denies capital, reproof probes still flow.
- **Acceptance tests.** New `AuthorityConvergenceAcceptanceTest6809.kt`
  locks 11 invariants: SHADOW/TRAIN never open, neg-EV never becomes
  BUY, min-size promotion retired, `AuthorityTier.BOOTSTRAP` never
  returned at runtime, hard-safety precedes lane policy, mode/operator
  block precedes all lane logic.
- **Doctrine artefacts.** Bumped to `5.0.6809`. Aligned:
  `Repair6490AcceptanceTest`, `Repair6510AuthorityAcceptanceTest`,
  `Repair6511PaperExecutionSourceTest`, `Aate6600SpecialistAuthorityRestorationTest`,
  `V5_0_6567AcceptanceTest`, `GoldenTapeRegressionTest` (rows 2645 and
  7429).
- **CI status:** `Build AATE APK` **succeeds** for V5.0.6809 (13m48s).
  `Runtime Smoke Test` still fails on the pre-existing
  `NO_COMPLETED_PASSING_CURRENT_WINDOW` window — unchanged from prior
  session (known issue in `ci/runtime-test.sh` / `ci/runtime_evidence.py`).

