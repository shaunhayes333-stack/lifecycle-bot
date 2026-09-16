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


## V5.0.6810 — NARROW 4-ITEM CAUSAL CORRECTNESS (2026-02)

Scope explicitly restricted by operator: DO NOT refactor canonical
accounting, paper ledger, position authority, finalized reward fanout,
sizing authority, or execution spine. This is a targeted correctness
patch, NOT another global unchoke.

- **#1 Executable mark propagation.** `FinalDecisionGate.evaluate` now
  promotes fresh source evidence (TokenMap + last-price state) into
  `CanonicalPriceMarkRegistry6522` BEFORE the FDG scoring path runs, so
  downstream execution/exit calls always find a canonical mark. Same
  synchronous promotion added to `BotService.buildExitVisiblePositionsCanonical`
  before the async provider refresh — if in-memory evidence admits, the
  network round-trip is skipped entirely. Counters:
  `FDG_PRE_MARK_PROMOTED_6809`, `EXIT_MARK_SYNC_PROMOTED_6809`. No
  fabrication, no sync-block on providers, dedup via registry identity
  rules.
- **#2 FDG_ALLOW → EXEC_INTENT sequencing.** `ExecutableOpenGate`
  invariant emission gated: the paper deferral window (`stateAgeMs<5s`)
  no longer double-counts as both `FDG_ALLOW_WITHOUT_EXEC_INTENT`
  (invariant) AND `FDG_ALLOW_AWAITING_EXEC_INTENT_6805` (defer). The
  invariant label is emitted only when the caller is LIVE or paper is
  past the deferral horizon. Prior health dump showed
  `FDG_ALLOW_WITHOUT_EXEC_INTENT=6` == `FDG_ALLOW_AWAITING_EXEC_INTENT_6805=6`;
  post-6810 the paper race is classified DEFER only.
- **#3 Policy version churn eviction.** `ExecutableOpenGate.intentSupersedes6809`
  tightened. Previously any `authorityVersion` bump evicted the sealed
  intent (1520 evictions per run with identical BUY/BUY/OPEN semantics).
  Now eviction requires MATERIAL change: `BUY → non-BUY`, `fdgAllowed`
  loss, different `action`, or different `canonicalLane`. Identical
  executable semantics → preserve the existing immutable ticket;
  telemetry-only differences do not rebuild.
- **#4 Exit coordinator false stale reset.** `BotService.maybeHealHotExit`
  now checks live heartbeat before declaring the coordinator stale:
    • `hotExitJob?.isActive == true` (main coordinator alive), OR
    • `exitSweepInFlight && exitSweepWorker.isActive && startedMs < HARD_MS`, OR
    • `slSafetyNetInFlight && slSafetyNetWorker.isActive && startedMs < HARD_MS`
  Any of these suppress the force-reset (`EXIT_COORDINATOR_STALE_SUPPRESSED_HEARTBEAT_6809`).
  Genuinely dead workers past `EXIT_SWEEP_HARD_MS` still trigger the
  emergency recovery path unchanged.
- **CI status:** `Build AATE APK` **succeeds** for V5.0.6810 (16m59s).
  Runtime Smoke Test still fails on the pre-existing
  `NO_COMPLETED_PASSING_CURRENT_WINDOW` — unchanged, unrelated brittleness.
- **NOT changed:** CanonicalPositionAuthority, paper ledger, finalized
  trade bus, reward purity, replay isolation, inventory accounting,
  lane identity sealing, strategy/expectancy dampers, lane inventory
  ceiling, FDG negative-EV thresholds, global trading aggressiveness.


## V5.0.6812 — CRASH-FIX SHIP: REVERT V5.0.6811, KEEP NEG-EV MIN-SAMPLE (2026-02)

Operator report Feb 2026: **V5.0.6811 crashed on load.** V5.0.6810 was
the last cleanly-booting build. Rolled back the entire 6811 batch and
re-applied only the safe, isolated policy-synth tightening.

- **Reverted (three commits)**:
    • `ac11d38cd` V5.0.6811 §AUTHORITY_CONSOLIDATION — FDG.evaluate
      entry/exit shadow guards + new `CanonicalFdgAuthorityRegistry6811`.
      Root suspicion: an early-return `FinalDecision` from the new shadow
      guard interacted badly with downstream callers on the hot path.
      The file `CanonicalFdgAuthorityRegistry6811.kt` is deleted and the
      FDG evaluate call-site is restored to its V5.0.6810 shape.
    • `9073b2ce1` V5.0.6811 compile hotfix (BlockLevel.MODE).
    • `042c42397` V5.0.6811 PRD changelog.
- **Re-applied surgically (no execution/authority path change; pure
  policy-synth input)**:
    • `AateDecisionEnvelope6512.PolicySynthesizer6512.synthesize`:
      hard-veto `POLICY_NEG_EV_BLOCK_6801` now requires
      `MIN_EV_HARD_VETO_SAMPLE_6812 = 3` independent attributable EV
      contributors. Below that count → `POLICY_NEG_EV_ADVISORY_6812`
      (BUY-like preserved, damping-only telemetry). Hard safety
      unchanged.
- **Preserved untouched (V5.0.6810 fixes remain in place):**
    • Executable mark propagation (pre-FDG + pre-exit-sweep sync
      promote).
    • FDG_ALLOW → EXEC_INTENT causal label atomicity (paper deferral no
      longer double-counts).
    • `intentSupersedes6809` material-change semantics.
    • Exit coordinator heartbeat-aware stale detection.
- **Acceptance tests updated**:
    • `negative_ev_aate_veto_never_becomes_buy` now uses 3 EV
      contributors (the invariant still holds at the required sample).
    • New `negative_ev_low_sample_becomes_advisory_not_block`.
- **CI status:** `Build AATE APK` **succeeds** for V5.0.6812 (16m41s).
  Runtime Smoke Test unchanged (pre-existing brittleness).
- **Deferred to a future ship**: authority consolidation at the FDG
  boundary (items #1/2/5/6/7 of the operator mandate) — needs a redesign
  that does not gate at the hot-path evaluate() entry. Candidate
  approaches: (a) claim ownership only downstream in ExecutableOpenGate
  where sibling attempts already surface as
  `EXEC_INTENT_INVALIDATED_ON_POLICY_CHANGE_6809`, (b) publish a lane
  ownership stamp at candidate-election time so FDG is only called by
  the elected lane in the first place, or (c) make the shadow guard
  opt-in via a caller-passed flag rather than a global hot-path check.


## V5.0.6813 — NARROW SOURCE REPAIR (2026-02)

Operator diagnosis Feb 2026, six-item mandate. **After the V5.0.6811
crash**, this ship is deliberately narrow: 4 isolated source-level
changes; 2 items deferred to dedicated ships to avoid touching the
canonical reconciler / hot-path ticket refresh loop.

- **#1 EV evidence-state refinement.** `PolicySynthesizer6512.synthesize`
  now classifies each candidate's EV signal as
  `EV_UNKNOWN` / `EV_INSUFFICIENT` / `EV_VALID_NEGATIVE` / `EV_VALID_NEUTRAL`.
  Hard veto `POLICY_NEG_EV_BLOCK_6801` fires ONLY on `EV_VALID_NEGATIVE`
  (≥3 attributable EV contributors AND ev ≤ -3.0%). Every other
  low-sample / defaulted / sentinel-derived EV path emits
  `AATE_POLICY_EV_INSUFFICIENT_ADVISORY_6811` with the exact operator-
  specified label (contributor count, raw EV, pWin, evidence state).
  Hard safety (rug/liquidity/scam/route) is unchanged.
- **#2a Market-cap Int-saturation guard.** ~25 log/reason string sites
  across `BotService`, `ToolkitSignalSheet`, `SolanaMarketScanner`,
  `TreasuryScannerFeed`, `ProjectSniperAI`, `MainActivity` converted
  from `.toInt()` to `.toLong()` for mcap and paired liq/vol values.
  No display or reason string can now saturate to Int.MAX_VALUE
  (2_147_483_647). Pure numeric-safety change; no economic path
  touched.
- **#2b Unit-invariant learning quarantine.** `CanonicalTradeFinalizedBus6450`
  §UNIT_INVALID_LEARNING_QUARANTINE detects entry-basis unit corruption
  at Envelope construction time and overrides `learningEligible=false`
  with reason `UNIT_INVALID_QUARANTINE_6813[:MCAP_INT_SATURATION]
  [:ENTRY_PRICE_DECIMAL_SKEW]`. Trigger predicates:
    • entry mcap ≈ Int.MAX (saturation signature); OR
    • entryPriceUsd > $1000 while mcap ∈ [$1, $500k] (memecoin decimal-
      skew signature — real per-token price would be <$0.01)
  This filters bad rows out of EV / WR / tactic μ / losing-streak /
  UnifiedPolicyHead / StrategyHypothesisEngine / ForwardOutcomeModel
  learning inputs. **Does NOT touch canonical position authority, does
  NOT rebase entry basis, does NOT mutate economics** — pure learning-
  input filter (matches operator's DO-NOT-TOUCH boundary).
- **#5 Conditional min-promotion reinstated.** `OrderSizeResolver6441`
  §CONDITIONAL_MIN_PROMOTION supersedes both the V5.0.6600 unconditional
  promote and the V5.0.6809 kill-min-promotion. Sub-min behaviour:
    • request ≥ minExec               → shape normally
    • request < minExec, caps admit   → promote once (`OK_MIN_PROMOTED_6600`)
    • request < minExec, caps refuse  → explicit `BELOW_MIN_NOTIONAL_6813`
  Never a silent zero-sized executable ticket. `SUB_MIN_ADAPTIVE_HELD_6809`
  taxonomy retired.
- **Deferred to dedicated ships (still on backlog):**
    • **#3 Reward/journal parity** (closed=226 vs finalized=218): the
      gap sits between `CanonicalPositionAuthority6441.close()` and
      `CanonicalTradeFinalizedBus6450.publish()`. Fixing this touches
      the canonical reconciler which is on the DO-NOT-TOUCH list; needs
      a dedicated audit-boundary recovery patch with no canonical
      mutation.
    • **#4 `EXPIRED_TICKET_ECONOMIC_REJECT_6614`**: refresh/reseal
      path already exists at `ExecutableOpenGate:510-580` with a
      10-minute sealed-provenance budget. Extending it without
      touching hot-path semantics needs a focused ship — the
      V5.0.6811 hot-path guard experience is fresh.
- **Test alignment**: `Repair6490AcceptanceTest`, `Repair6510AuthorityAcceptanceTest`,
  `Repair6511PaperExecutionSourceTest`, `Aate6600SpecialistAuthorityRestorationTest`,
  `V5_0_6567AcceptanceTest`, `AuthorityConvergenceAcceptanceTest6809`,
  `GoldenTapeRegressionTest` row 8166 — all updated to the conditional-
  min-promotion + CONDITIONAL_MIN_PROMOTION taxonomy.
- **CI status:** `Build AATE APK` **succeeds** for V5.0.6813 (16m28s).
  All acceptance tests pass. Runtime Smoke Test unchanged (pre-existing
  brittleness).


## V5.0.6814 / V5.0.6815 — CAPITAL VELOCITY REPAIR (2026-02)

Operator diagnosis Feb 2026, 13-item mandate: entry > exit imbalance,
weak entries consuming wallet, non-profitable cohorts crowding out
PROJECT_SNIPER, 5-second hot loop, negative expectancy still buying.
Shipped 4 items in two builds (V5.0.6814 + V5.0.6815); deferred 7
items to dedicated ships. **No hot-path early-return traps** after
the V5.0.6811 crash lesson.

### V5.0.6814

- **#1 CAPITAL_RECOVERY mode.** New `CapitalRecoveryAuthority6814`
  monitors cash/slot/buy-sell state and flips `isActive()` when any of:
    • `cash < max($0.05, equity × 5%)`
    • `openPositions ≥ 75% × slot capacity`
    • `buys/sells > 1.35`
  During recovery, `FinalDecisionGate.evaluate()` returns a proper
  `FinalDecision` with `blockReason = "CAPITAL_RECOVERY_6814"` via the
  standard blockReason path (NO hand-built early return). Exits (SELL/
  TP/SL/catastrophic) never traverse this gate. Recovery exits with
  hysteresis: `cash ≥ 15% equity` AND `open ≤ 55% slot capacity` AND
  `buy/sell ≤ 1.20`. Self-triggering — no BotService hot-loop change.
- **#2 EV authority tightened to 2 contributors.** Threshold dropped
  from 3 → 2 (`MIN_EV_HARD_VETO_SAMPLE_6814 = 2`). Weighted EV < -3%
  with ≥2 EV contributors → `POLICY_NEG_EV_BLOCK_6801` (hard veto).
  Single-contributor negative EV → `AATE_POLICY_EV_INSUFFICIENT_ADVISORY_6811`
  (advisory only). Structurally weak entries can no longer slip
  through as "low sample".

### V5.0.6815

- **#3 WAIT override restriction.** Added two hard preconditions at
  `BotService.processSpecialistLane()` before any `weakWait` branch
  can convert WAIT → probe:
    • `LaneExpectancyDamper.sizeMultiplier(lane) > 0.50` (else lane
      is a learned bleeder — override rejected)
    • `CapitalRecoveryAuthority6814.isActive() == false`
  Rejects labelled `SPECIALIST_WAIT_OVERRIDE_REJECTED_6814` +
  `..._BLEEDER_LANE_<lane>` / `..._CAPITAL_RECOVERY`. Aggressive
  speculation into bleeders / cash-starved windows now stops at source.
- **#7 Recycle-ratio size damper.** New `CapitalRecycleRatioAuthority6814`
  rolls 5-minute entry-notional vs realised-cash-returned. Exposes
  `sizeMultiplier()` in `[0.20, 1.0]`. `OrderSizeResolver6441`
  multiplies this into the existing adaptive stack alongside SSI/Lab.
  When cash returns fall behind entry throughput, new entries damp
  proportionally. Never upsizes. Counters:
  `CANONICAL_ADAPTIVE_SIZE_RECYCLE_DAMPED_6814`,
  `RECYCLE_RATIO_ENTRY_RECORDED_6814`,
  `RECYCLE_RATIO_CASH_RETURNED_6814`. **Follow-up ship needs to wire
  `recordEntry(notional)` at ticket-creation and
  `recordCashReturned(realised)` at finalized-close** — currently only
  the size-multiplier consumer is live; producer wiring pending.
- **6604 invariant respected.** Cash reads via
  `PaperCapitalAuthority6577.cashSol()` facade (not direct ledger call).

### V5.0.6816 — Deferred P0/P1 items shipped (mega-commit)
- **JournalReplayGuard6816** — new authority. Refuses to publish
  finalized envelopes while paper `openCostΔ != 0` during replay
  reconstruction (closes closed=226 vs finalized=218 gap surfaced in
  V5.0.6812 forensic dump). Guard consulted at
  `CanonicalFinalizedTradeBus6464.publish` entry. Reads openCostBasis
  through `PaperCapitalAuthority6577.openCostBasisSol()` facade
  (Aate6604 invariant respected).
- **ProfitHarvestAuthority6816** — new advisory authority for adaptive
  partial harvesting with 4-tier bank ladder (20%/30%/40%/50% at
  40/90/180/400% unrealised). Advisory API only in this ship;
  consumer wiring to PartialSellSizer lands in follow-up.
- **ExecutionTicketFinalityGuard6816** — six-field pre-condition audit
  wired at `ExecutionTicketMachine6411.create` (ENFORCE_HARD_BLOCK
  OFF, advisory telemetry only). Fires
  `EXEC_TICKET_FINALITY_INCOMPLETE_6816` with the missing field named.
- **ExpectancyWeightedLaneAllocator6816** — second-order lane weighting
  on top of LaneExpectancyDamper (winner uplift → 1.35 max for runner
  lanes; bleeder haircut → 0.30 floor; non-runner cap at 1.00).
  Observability-only in this ship (composition into
  `OrderSizeResolver.adaptiveMult6684` deferred — see V5.0.6817 red
  build fix below).
- **ProjectSniperSizingChoke6816** — PROJECT_SNIPER lane min-executable
  clamp + starvation telemetry. Wired into
  `OrderSizeResolver.minExecRaw` path. For non-PROJECT_SNIPER lanes
  the passthrough is a no-op.
- **CapitalRecycleRatioAuthority6814 producer wiring (attempted, then
  reverted)** — `recordEntry`/`recordCashReturned` are still not fed
  from `PaperAccountLedger6430` because populating the deque leaked
  cross-test state and dropped `sizeMultiplier()` below 1.0 in
  buy-only tests. Authority + consumer live; producer wiring will
  come via an out-of-hot-path BotService tick in a later ship.

### V5.0.6817 — Targeted Source Repair block shipped
Six new additive observability-first authorities. No hard blocks into
existing hot paths — matches the V5.0.6811 crash-safe pattern.
- **StaleMarkExitGate6817** — verdict path
  `VALIDATED / HOLD_DEFER / SCRATCH_DIAGNOSTIC / DEAD_CLOSE` for close
  paths. `isTrainable(positionId)` surfaces stale-mark closes as
  non-trainable. `STALE_MARK_EXIT_*_6817` counters visible.
- **UnresolvedOwnerLearningQuarantine6817** — name-list quarantine for
  StrategyExpectancy / LaneExpectancyDamper / TacticSwitcher /
  GrowthRewardShaper / LosingStreakReflex / UnifiedPolicyHead /
  ForwardOutcomeModel / MetaPolicy / LaneExitTuner /
  StrategyHypothesisEngine / source-lane WR. Diagnostics remain
  visible. `resolveOwnerFromSealedEntry` rejects STANDARD/CORE
  fallback defaults.
- **FdgAuthoritativeElection6817** — one authoritative FDG outcome per
  `(mode + canonicalMint + candidateVersion + epoch)`. Subsequent
  lanes become `CONTRIBUTOR` or `SHADOW`. Exposes
  `authoritativeToIntakeRatio()` for the operator's ≤ 1.5 target.
- **AtomicFdgExecIntentBinder6817** — `sealFdgBuy` + `sealExecIntent`
  atomic pair with 15s TTL. `sweepOrphans()` fires
  `FDG_ALLOW_AWAITING_EXEC_INTENT_ORPHAN_6817` with immutable
  six-tuple (candidateVersion, lane, entry snapshot ref, decision id,
  epoch, mark authority) preserved through ticket + executor.
- **PaperCommitOrderGuard6817** — witness for
  cash/basis/quantity/realized/positionState/journal single-transaction
  ordering. `recordBusPublish` fires
  `FINALIZED_BUS_PUBLISHED_BEFORE_COMMIT_6817` on ordering violation.
  `postCommitRevision()` for AcceptanceAudit consumers to read
  post-commit only.
- **RewardPurityAdmission6817** — pure predicate composing owner
  quarantine, stale-mark trainable flag, entry basis / terminal mark
  / economics reconciled. Diagnostic close remains in journal,
  `trainable=false`.

### CI status
- V5.0.6817 **Build AATE APK: SUCCESS** (12m56s, artifact
  `AATE_v5.0.6817` uploaded).
- All 2636 tests pass (green after fixing the 6816
  Aate6604MemeCausalAuthorityCoverageTest / Repair6491 / V5_0_6567 /
  Repair6511 / CanonicalEntryAuthority6551 regressions).
- Runtime Smoke Test remains pre-existingly failing
  (`NO_COMPLETED_PASSING_CURRENT_WINDOW` — not touched in this ship).

### Deferred to follow-up ships
- **Producer wiring for CapitalRecycleRatioAuthority6814** — must run
  out-of-hot-path (BotService periodic tick) to avoid the cross-test
  state leak observed in V5.0.6816.
- **Composition of ExpectancyWeightedLaneAllocator6816 into
  `adaptiveMult6684`** — same reason.
- **ExecutionTicketFinalityGuard6816 hard enforcement** — currently
  ENFORCE OFF; turn on after telemetry proves it does not
  false-positive.
- **V5.0.6817 authority consumers** — each 6817 authority is a pure
  API today. Wiring `StaleMarkExitGate6817.evaluate` into the exit
  paths, `UnresolvedOwnerLearningQuarantine6817.isQuarantined` into
  the ten learner admission gates, `FdgAuthoritativeElection6817`
  into lane-vote publish, `AtomicFdgExecIntentBinder6817` into FDG
  seal + intent creation, `PaperCommitOrderGuard6817` into the
  ledger/position/journal mutation batch, and
  `RewardPurityAdmission6817` into `LearnerRewardBridge` — each of
  those is a targeted one-line consumer edit that can now be done
  incrementally under the 6817 telemetry umbrella.
- Runtime Smoke Test `NO_COMPLETED_PASSING_CURRENT_WINDOW`.

