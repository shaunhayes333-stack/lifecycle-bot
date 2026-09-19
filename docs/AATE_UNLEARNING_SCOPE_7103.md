# Retroactive Unlearning — scope

**Status:** scoped, not built. Phase 0 shipped in V5.0.7103.
**Question it answers:** when a data-integrity defect is found and fixed, how does the
stack retract what it learned from the corrupted trades?

The concrete instance: on 5.0.7088 the predictive oracle reported
`evals=894 admit=0 probe=251 refuse=643`. It had trained on entry prices that a 1e9
supply constant invented (fixed in V5.0.7089) and collapsed into refusing everything.
V5.0.7091 recorded the honest limitation at the time — *"nothing can un-teach what is
already persisted at effN=8"* — and the oracle only recovered because fresh samples
outvoted the poisoned ones. That is sample volume and luck, not a mechanism.

---

## 1. What already exists (verified in source, not assumed)

**The durable record is complete, ordered and replayable.**
`CanonicalFinalityPersistence6486` writes the full `CanonicalTradeFinalizedBus6450.Event`
per `positionId` to SharedPreferences, and `initAndReplay` already reads every row,
sorts by `settledAtMs`, and republishes through the bus. Rebuild-from-source is
therefore not new machinery — it is the startup path, called a second time.

**Per-consumer ack and exclusion state is durable too.**
`ackedIds6486(consumer)` / `excludedIds6734(consumer)` survive restart, so a rebuild can
tell "this learner already absorbed that trade" from "it was deliberately excluded".

**Learning ineligibility is already a first-class concept.** `learningEligible` +
`learningEligibilityReason` travel on the envelope, and V5.0.7097 made a
6495-quarantined terminal publish as *excluded* rather than vanish. Malformed economics
already never reach a learner.

## 2. What does not exist, and why subtraction is off the table

**No learner retains the identity of the samples it absorbed.**

| Learner | Storage | Can a named sample be removed? |
|---|---|---|
| `ScoreExpectancyTracker` | `ConcurrentHashMap<String, ArrayDeque<Double>>` keyed `lane:bucket` | No — values only, no positionId |
| `ForwardOutcomeModel` | Welford `Cell(n, mean, m2, wins, rugs)` per signature | No — aggregates; the arithmetic is reversible but *which* value belonged to a given trade is not recorded |
| `LiveProbabilityEngine` | lane aggregates | No |
| `UnifiedPolicyHead` | model weights | No, and not even in principle |

So "subtract the poisoned samples" is not implementable without adding per-sample
provenance to four stores — more state, more to keep consistent, and consistency
between parallel stores is the single most common defect class in this codebase.

**Rebuild is the sound method:** reset the learner, replay the surviving terminals from
the durable record. It is exact, it needs no new per-sample bookkeeping, and the replay
path is already written and already exercised at every startup.

**Reset surface is uneven.** `ForwardOutcomeModel`, `ScoreExpectancyTracker` and
`UnifiedPolicyHead` have `reset`/`export`/`import`. `LiveProbabilityEngine`,
`RewardPurityGate6441` and `LearnerRewardBridge6440` have no non-test reset at all.

**The record cannot express "which build produced this".** A poisoned epoch is
"everything settled while defect X was live". The row carries `settledAtMs`,
`dataQuality` and `priceIntegrity` but no build version, so the epoch can only be named
by wall-clock time — which is fragile across reinstalls and says nothing about which
code wrote it.

---

## 3. Design

### Phase 0 — make an epoch nameable *(shipped, V5.0.7103)*
Nothing can be retracted that cannot first be identified.
- Carry the producing `AATE_VERSION` on the persisted terminal row.
- Backfill is impossible for existing rows; they read as `pre_7103` and are addressable
  as exactly that.

### Phase 1 — the repudiation ledger
A durable, append-only set of **repudiation rules**, each one a predicate over persisted
terminals plus a reason:
- `version_before(v)` — everything a build older than *v* produced
- `settled_between(t0, t1)`
- `data_quality_in(set)` / `price_integrity_in(set)`
- `position_ids(set)` — the explicit, surgical case

A rule is a *statement about evidence*, never about a lane, a threshold or a verdict.
It is written once, survives restart, and is listed in the operator report. Adding one
changes nothing on its own — it only marks rows.

### Phase 2 — rebuild
`initAndReplay` becomes re-entrant as `rebuildFromDurable(reason)`:
1. Snapshot every learner's exportState (rollback material).
2. Reset the learners in scope.
3. Replay persisted terminals in `settledAtMs` order, **skipping repudiated rows**, and
   clear their acks so they are not double-counted.
4. Emit before/after per learner: sample counts, per-lane expectancy, oracle
   admit/refuse over a fixed probe set.

Runs on `MaintenanceWorker6448`, never on `botLoop`. Note V5.0.7101: the worker's budget
is advisory for blocking work, so this must be sized to complete, not to be cancelled.

### Phase 3 — closing the remaining reset gaps
`LiveProbabilityEngine`, `RewardPurityGate6441`, `LearnerRewardBridge6440` need a
non-test reset before they can participate. Until they do, a rebuild is partial and must
say so rather than report success.

### Phase 4 — self-service *(explicitly deferred)*
Automatic repudiation on detecting a data defect. **Not in scope.** V5.0.7102 already
detects a collapsed learner and deliberately does not act on it, for the reason that
applies here too: a collapse can be correct, and a system that retracts its own beliefs
on a heuristic can erase a real edge as easily as a fabricated one.

---

## 4. Risks, named

- **Rebuild is destructive.** It must snapshot first and be able to roll back. A partial
  rebuild that reports success is worse than no rebuild.
- **SharedPreferences growth.** One key per position, plus per-consumer ack and exclusion
  keys across 16 consumers, and `prefs.all` is read wholesale. Already unbounded; rebuild
  makes it hotter. Bound it before Phase 2, not after.
- **Replay must not touch cash.** `CanonicalTradeFinalizedBus6450.publish` fans out to
  learners *and* to `CanonicalFinalityPersistence6486.record`. The HERO directive puts
  paper account mutation off limits, and `LegacyReplayIsolation6630` already reports
  `migrationAuthorized=false`. A rebuild is a learning operation and must be proven not
  to move a single lamport — `paperReplay cashΔ` must stay `0.0000` across it.
- **Survivorship.** Repudiating a cohort can leave a biased remainder. The before/after
  report must show sample counts per lane so a thinned cohort is visible rather than
  silently confident.
- **This is the operator's lever.** Nothing here fires on its own.

---

## 5. What is NOT part of this

Changing FDG thresholds, the oracle's `REFUSE_EXPECTANCY_PCT` / `MIN_CONFIDENCE_TO_REFUSE`,
lane behaviour, position caps or any gate. Unlearning changes *the evidence a threshold
sees*, never the threshold.

---

## Appendix — oracle evidence upgrade shipped alongside (V5.0.7103)

Separate from unlearning, and the larger near-term win for predictiveness.

`ForwardOutcomeModel` keys on six dimensions — mode, lane, score band, quality, regime,
edgePhase — and `forecast()` reads the exact cell. But `cohortEvidence6911`, which is
what `PredictiveEntryOracle6915` judges on, matched `|lane|band|` and averaged every
other dimension away. **The oracle asked a 2-D question of a 5-D model**, so its entire
cell-level evidence was an unconditional mean. Two costs:

- **Regime was discarded.** The oracle already *receives* `regime` and spent it only on
  `AutonomousMetaPolicy.conviction`. A lane that is +40% in one regime and −40% in
  another reported ≈0 and was called neutral in both.
- **Paper and live were pooled at full strength.** V5.0.6869 exists because paper and
  live shared one predicted win rate; V5.0.6991 then stopped live inheriting paper at
  full strength in `forecast()`. This accessor predates both and never got the message,
  so the forecasting path and the admission path disagreed — and admission used the
  cruder answer.

Now hierarchical: `regime_mode` → `mode` → `pooled`, gated on the model's own
`MIN_SAMPLES`, falling back rather than starving. Worst case returns exactly the
pre-7103 answer, so no caller can end up with less evidence than before. Matching is by
key segment rather than substring. No threshold moved.
