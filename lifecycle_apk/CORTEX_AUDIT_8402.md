# Cortex end-to-end audit

Audited source: repair branch production tree in 9d8be97c2a61ec400eabf7e3754b897348e01589 (5.0.8402). Evidence: uploaded 8401 report at 0128092a0 and three later screenshots. The screenshots do not show a build header, so their exact binary is not established. This is a source and evidence audit, not a replay of the phone's complete database.

## Verdict

The Cortex is receiving assessments and some labels, but reliable end-to-end learning is not established. The 8402 repairs address specific delivery, checkpoint, coverage and scheduling defects. The subsequent 8404 repair implements the successful-forward-label acknowledgement contract; its validation status is recorded below. Passing the APK build or the current regression suite must not be described as full Cortex recovery.

Zero positive authority is not itself proof of an incorrect veto. In the screenshots, most lanes have no qualifying STRONG observations; QUALITY has nine with a negative mean. Their missing-outcome populations are also substantial. Increasing authority would not repair those facts.

## 8404 repair status (validation pending)

The implementation now addresses the concrete C1, C2, C3 and C5 paths below; the descriptions later in this document preserve the original 8402 findings.

- **C1/C2 delivery:** a bounded successful-outcome outbox durably owns the original prediction, exact episode, observed value and proof before the producer completes the horizon. First labels and progressive revisions remain until Cortex's atomic model/receipt checkpoint and the source learner's acknowledgement. A retry invokes the delivery consumer, not the entire multi-learner booking method. Failed initial writes freeze the first value; all booking hooks use that value when storage recovers. Durable explicit dispositions cover originals that genuinely cannot be recovered. Batch delivery rotates past pending consumers. Storage refusals, source acknowledgement waits, missing originals and durable acknowledgements have separate counters.
- **Revision integrity:** duplicate and older proven outcomes cannot rewind an accepted newer revision. Hold-window checks use the observation timestamp rather than delivery time. Shadow-exit ownership still prevents later forward labels from adding another contribution.
- **C3 predictive coverage:** each new reserved Cortex assessment offers its frozen timestamp to the existing bounded forward observer before simulation. Existing observations coalesce; this does not introduce another exploration book or fabricate a fill. A simulated unavailable outcome does not immediately mature a decision that still has its exact forward observer. A later live admission preserves the predictive observation and obtains its own execution read.
- **Prediction hygiene:** stale-price provenance survives checkpoint/restore and is enforced in the shared forward verdict, as it already was in the shadow path. A restart cannot convert that original into eligible training.
- **C5 cost basis:** new grades retain decayed executable-shadow contribution weight alongside unchanged raw returns. Revisions replace that weighted basis; snapshots preserve both the statistics and the trace's basis. The fallback forecast, scoreboard and auxiliary-book reads charge the forward-label share once and do not re-charge known executable-shadow outcomes. Measured NET calibration still uses its own basis-aware target. Legacy aggregates without basis provenance receive no invented credit; unavailable historical detail cannot be reconstructed faithfully.
- **C4 authority:** the fresh report has active voters, so the earlier zero-seat explanation does not apply. No grade floor, evidence hold or live admission rule is relaxed to manufacture authority.

New regression fixtures cover durable first-value retention, capacity and fair delivery, retry after restart, failed consumer/deletion, duplicate/older revisions, admission identity, cost-basis revision/decay/restore, and actual Cortex outcome delivery with failed then successful SQLite receipt commits. Required and full Android CI results must still be recorded before distributing an APK. A successful test suite is not evidence of profitable live selection or a replay of the complete phone database.

## Fresh 02:58 report: 8402 is still failing under restored state

The subsequently supplied `aate_pipeline_20261028_025852_004.txt` identifies 5.0.8402@1b372115a, uptime 734 seconds. GitHub confirms this is the PR merge of 0f95424a into d8037e540: it already includes the production repairs described above. The later 9d8be revision only corrects a test assertion. Reinstalling that identical production code is not a repair for this report.

- Its later funnel section records 1,242 FDG blocks, zero allows and zero executor calls. The +0.0025 SOL historical strategy-window close is not a new execution in this run.
- The forward terminal outbox has 1,391 queued entries, zero acknowledged at the snapshot, and an oldest age of 3,063,600 ms (51.1 minutes). Capacity refusals and producer storage failures are zero; those two counters do not measure consumer checkpoint latency.
- Cortex reports snapshot encoding at 584,630 ms, database write at 836 ms and snapshot-copy lock hold at 107 ms. These are reported checkpoint timings, not proof of a continuous ten-minute main-thread lock. The report has zero ANR hints.
- Heap usage is 407/512 MB with a 100% peak and eight hard trims. Assessment lock wait reaches 33,290 ms; capture lag reaches 358,739 ms and the detailed compute audit records 121 late voter reads. The separate grading audit's zero late-read count is a different scope.
- Voters are now active: the headline reports 49 seated, while the filtered vote-cleaning scope reports 14 and 272 seasoned. Therefore C4's earlier zero-seat explanation cannot describe this fresh run.
- Missing outcomes include LOST_MARK (1,214 Cortex events), TIMEOUT (506), and simulated UNLANDED (1,008), not merely local rate denials or simulated failed fills.

Additional source defect confirmed: terminal delivery offers the first 64 *unacknowledged* entries repeatedly, while an unavailable-only checkpoint waits for 256 staged outcomes or two minutes. Filtering acknowledged records fixed one starvation case, but did not make unacknowledged/staged batches progress. The flush itself synchronously serializes the entire Cortex state on a label/shadow tick's caller, delaying further observations.

The 8403 patch rotates bounded terminal batches while retaining every record until durable acknowledgement, schedules coalesced shadow checkpoint work on the existing persistence worker, and streams the pending checkpoint generation directly, eliminating three full pending-state JSON parses. Tests cover 300 unacknowledged records reaching the consumer, a blocked checkpoint leaving its producer free, coalescing, rejection and retry. These changes require CI validation and a restored-device run; they do not close C1/C2 or establish that the entire 584-second encode cost is removed.

## What the screenshots establish

These are counterfactual selection/outcome records, not realised account P&L. AATE TAKE/REFUSE and Cortex STRONG/REFUSE are different classifications.

| Lane | Matured | Missing | Missing share | Qualifying Cortex STRONG |
|---|---:|---:|---:|---:|
| CORE | 464 | 390 | 84.1% | 0 |
| MOONSHOT | 352 | 294 | 83.5% | 0 |
| SHITCOIN | 249 | 207 | 83.1% | 0 |
| CASHGEN | 191 | 159 | 83.2% | 0 |
| EXPRESS | 164 | 134 | 81.7% | 0 |
| QUALITY | 151 | 125 | 82.8% | 9 |
| DIP_HUNTER | 111 | 86 | 77.5% | 0 |
| TREASURY | 128 | 106 | 82.8% | 0 |
| BLUECHIP | 82 | 70 | 85.4% | 0 |
| PROJECT_SNIPER | 114 | 103 | 90.4% | 0 |
| MANIPULATED | 31 | 26 | 83.9% | 0 |
| CYCLIC | 41 | 38 | 92.7% | 0 |

These totals must not be combined with the earlier report as though they were one session. `coverageS=1.0` with zero STRONG rows is the empty-group convention, not evidence of a well-covered STRONG predictor.

## Delivery defects confirmed in the audited 8402 baseline

### C1. Successful forward outcomes have no durable consumer acknowledgement

Confirmed source path:

1. `ForwardReturnLabeler7731.tick` sets `o.done60=true` before `bookSixty7944`. Recovery paths have the same ordering. Despite its legacy name, this slot is the five-minute label (`H60_MS_7731`).
2. `bookSixty7944` calls `Cortex7885.onLabel` inside a catch that discards exceptions.
3. `onLabel` returns Unit. Missing original capture, episode mismatch and full deferred-capture capacity return without grading. The producer cannot distinguish these from successful consumption.
4. The exact pending prediction is removed before label validation and model mutation. A failure after removal has no normal retry using that pending original.
5. Forward model persistence is periodic/asynchronous. The unavailable-terminal durable outbox does not carry this successful outcome and cannot repair its lost delivery.

The in-memory deferred-label queue helps when capture is still underway, but is not a durable acknowledgement contract. The 8401 paired restart checkpoint prevents one class of missing originals; it does not repair this handoff. The available history attributes the explicit missing-capture early return to 8397; the producer's completion-before-delivery ordering predates the fetched history boundary. It is therefore inaccurate to call every part a new regression.

Required repair: persist the immutable successful outcome and original prediction under one episode identity; retain it until each required consumer has a durable acknowledgement or explicit observed-only disposition. Replay must not add the same grade twice. Do not merely move `done60` below the call: other learners are already invoked in that method, so repeating the whole method can duplicate their contributions.

Acceptance: capture delayed, deferred queue full, consumer exception, storage failure, process death before/after commit, duplicate replay, and a newer same-token/lane episode. Every case must produce one correct learning contribution or a durable named terminal disposition.

### C2. Forward source-learning failure is ignored

`gradeDecision8372` calls `sourceOutcome8372`, ignores its Boolean acknowledgement, then mutates Cortex models. `sourceOutcome8372` explicitly returns false when the ScannerSourceBrain delivery is not acknowledged. The shadow outcome caller checks that same return and retries; the forward caller does not.

Thus Cortex and the source learner can disagree about whether the same forward outcome was delivered. The source acknowledgement method does request an asynchronous checkpoint, so a false return does not mean the outcome is necessarily lost: that save may finish normally. However, there is no retained forward delivery awaiting its durable acknowledgement if the save fails or the process stops. `SOURCE_OUTCOME_RETRY_NEEDED_8372` counts exceptions, not every false acknowledgement. The successful-outcome delivery contract above must track source-learning acknowledgement independently, without regrading Cortex on a source-only retry.

## Confirmed mechanisms requiring separate treatment

### C3. Simulated failed fills are classified as unavailable predictive evidence

`ExploreShadow8347.landed8366` deterministically simulates landing using `ShadowAlign8366`'s measured route rate. A rejected simulated fill closes with `executable=false`. `Cortex7885.onShadowUnavailable8372` marks the eligible original decision lost and matures its unavailable outcome immediately. That row participates in predictive coverage.

The earlier report contains 1,942 Cortex `UNLANDED_8366` unavailable events and a PumpPortal rate of 1 landed out of 7 resolved transactions, versus 23/24 for Jupiter. These are different scopes and are not counts of missing provider prices. The screenshots' entire missing population cannot be attributed to this mechanism from aggregate evidence alone.

A later valid forward outcome can clear a bound decision's lost flag; the outcome book deliberately allows this. However, assessment-only shadow enrollment does not itself create an independent forward observation for every failed simulated fill. Execution failure can therefore prevent that assessment from acquiring a predictive outcome even while prices are available.

Required design correction: keep price-prediction outcomes, modeled execution outcomes and actual fills explicitly separate, linked by episode. Continue a bounded price observation after a simulated non-fill where eligible. Do not invent an executable trade, count an unlanded fill as a token loss, or delete genuinely missing outcomes from the denominator. Preserve the simulator's landing failures as execution evidence.

### C4. No seated voters means the fused score falls back to a lane prior

`CortexLedger7885.fuseFrom8324` ignores voters with zero authority. With none seated, the token deviation is zero and its fused edge is the lane/regime prior. The earlier log reports 4,592 seats, zero seasoned and zero seated voters. This explains why reading hundreds of features does not necessarily yield token-specific selection in that sample.

Voter seating requires 60 scored observations plus skill/discrimination checks. The 8399 positive-authority ramp from 15 out-of-sample results is a separate gate; it did not change voter seating. The screenshots alone do not establish the later seat population. This is a verified cold-start mechanism, not proof that removing the seating checks would improve trading.

Required validation: replay frozen features and chronological outcomes through actual voter grading, seating, fusion, calibration and bucket selection. Include informative, uninformative and inverted features. Report per-lane finite feature coverage, scored/seasoned/seated counts and token score dispersion.

### C5. Cost basis is explicit in calibration but mixed in the fallback ledger

`gradeModelsLocked8372` sends forward-net and executable-shadow-net outcomes into the same ledger without a target-basis argument. Calibration does retain that distinction: `netFit8372` subtracts the execution offset from forward rows only. When that fit is not calibrated, `calibratedRead8372` instead subtracts the full offset from the fused ledger read.

This is a confirmed basis inconsistency and a double-adjustment risk for the shadow contribution. The effect on this user's scores has not been quantified; the screenshots' -3.2 point offset is not by itself proof of an incorrect fee. Correcting it requires a defined common target and migration/revision semantics for retained mixed history, not simply deleting the offset globally.

Acceptance: identical economic outcomes delivered as forward versus executable-shadow records must map to the intended same target; mixed populations and later shadow revisions must neither charge cost twice nor count the episode twice. Replay old persisted state as well as new records.

## Authority and FDG audit

- `CortexAuthority8366.test8372` uses frozen STRONG versus non-STRONG outcomes, chronological warmup, positive rank, profitable STRONG mean, spread over cost and a sample/uncertainty ramp. Missing coverage below 30% suppresses authority. The screenshots are below that level in every listed lane.
- Coverage can replace the displayed proof verdict with `INCOMPLETE_OUTCOMES`, masking a simultaneous shortage of STRONG rows or negative edge. Reporting should show both evidence quality and delivery coverage reasons.
- Positive authority, refusal authority and voter authority are different controls. `Cortex7885.entryRefusal` is called by `LiveEdgeGate7877`; the final gate also consumes paper-choice and size-shape decisions. A zero positive-authority bar does not by itself identify who blocked a live candidate.
- The earlier report recorded zero actual Cortex live/paper refusals, overrules and size increases in its action section. It does not support blaming Cortex for every FDG refusal.
- `paperChoice` requires earned positive authority. Independent `ExploreShadow` enrollment already accepts Cortex YES/NO/HOLD without live FDG permission. Adding another duplicate shadow entry system is not the repair.
- Cross-asset lanes intentionally do not enter the Solana execution simulator. Their forward-label price identity must survive restart; 8402 repairs that mapping checkpoint. A long-only forward labeler must not silently grade shorts with a long-return sign.
- `SpecialistBrainBridge7542` publishes the full native snapshot before enrollment and offers all native opinions to exploration. Its two-lane prewarm cap is separate from the twelve-lane native evidence contract; it is not evidence that only two specialists can learn.

## Repairs already in 8402 and their limits

The branch repairs local data-budget waits, same-key cooldown starvation, silently rejected chart workers, terminal batch starvation, paired prediction/observation restart retention, missing lane arguments in evidence proof, synchronous cold shadow reporting, exit-worker starvation, unavailable-shadow checkpoint cadence, paired coverage omission and cross-asset price identity restoration. See `AUDIT_8402.md` for the bounded patch inventory.

Existing safeguards retained: exact episode matching, decision-time frozen predictions, no post-outcome reconstruction of missing features, late/flat/unexecutable labels excluded from predictive authority, shadow/forward ownership deduplication, atomic canonical-close model/receipt commits, no fabricated market prices and no lowered live admission thresholds.

The restored-state responsive-report fixture covers approximately 13,500 cohort keys. The emulator execution witness covers a synthetic 8,192-event persisted history and start/stop/start. Neither is a replay of the entire phone state, a validation of selection profitability, or proof that C1-C5 are resolved.

## Release and audit acceptance

An APK must pass release compilation, required regressions, the complete historical suite, Android upgrade/ART startup and the runtime execution witness for the same source revision. Record those actual results separately from this source audit.

Full Cortex recovery additionally requires an exact recorded candidate to complete data arrival, immutable capture, native and Cortex assessment, shadow/paper exit, durable per-consumer outcome acknowledgement, correct learner update and chronological authority evaluation. A live-policy-eligible fixture must reach FDG allow, sealed intent and executor invocation without making an unsolicited real trade. The code repairs and test results must be reported separately from restored-device recovery. Profitable live selection and the complete phone-state candidate replay remain unproven until observed on that device; missing historical evidence must not be invented to claim otherwise.
