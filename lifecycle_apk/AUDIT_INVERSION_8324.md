# AUDIT 8324: "these look inverted again. check all"

Base: main 5.0.8321 @9e12e2db3. Branch wt-inv-8324. Tag V5.0.8324.
Evidence: /tmp/claude-0/evidence8321_inverted.md (the screenshot, plus the full 1,143 s text report).
Tests: `Aate8324InversionTest`, `Aate8324RestoreProvenanceTest`.
Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. Line numbers refer to this branch.

There is no local compiler. Nothing here has been compiled or run. CI is the only check.

## 0. Short answer

**The panel's columns are wired correctly.** No label, bucket, sign or offset is swapped (§1). The "inversion" has four causes:

1. **The Cortex lost its learning at the 8321 boot.**
   - 8321 report: `seats=3986 seated=2 · vote cleaning seasoned=156`, `calibration slope=-`.
   - 8319 report: `seated=88 · seasoned=1860`, with a slope per lane.
   - 3,986 lane seats and 156 seasoned seats after 19 minutes is exactly what an empty start grades into.
   - Calibration learns only from a seated deviation, so it stayed empty.
   - A Cortex with nothing seated predicts its lane prior for every coin. That alone explains STRONG ≈ 0 and REFUSE everywhere. See §2.
2. **The runner-lane bucket rule cannot work on a tail pond, even with a full ledger.**
   - It uses an absolute +3 / −2 band around a bounded prior of about −18.
   - Its runner exemption is an absolute 10% runner rate.
   - See §3.
3. **Bounded reads still hid the tail everywhere outside the Cortex grade book.**
   - `tailReadRaw8322=2` against `suspectReads=3053`.
   - Loss tests that cannot tell "no runner yet" from "losing" refused the coins that run.
   - See §5 and §6.
4. **"AATE TAKE n0" is not a column defect.**
   - It means AATE admitted none of the graded coins (ADMIT=72 against REFUSE=4,120 in the report).
   - With TAKE empty, the REFUSE column is simply the whole pond. Its "+397%" is the pond mean, carried by one or two runners.
   - See §1 and §6.

## 1. Check 1: signs and column wiring, end to end

| Step | Code | Population / statistic | Finding |
|---|---|---|---|
| Fused edge | `CortexLedger7885.fuse` (`engine/cortex/CortexLedger7885.kt:671`) | `edge = m + Σw·d / (1 + Σw)`, with `m` the lane prior (`predictMean8322`, bounded) and `d` clamped (`clampPull8007`, :656) | Sign correct |
| Calibration | `CortexCalibration7901.calibrate` | `m + slope·(edge − m)`, slope ∈ [0, 1.5] | Sign correct. Empty at 8321 (§2) |
| Executable offset | `Cortex7885.kt:154` | `calibrated − executableOffset8187` (≥ 0, subtracted once) | Direction correct. The grade book subtracts the same offset once (`CortexGradeBook8144.executableSeries`) |
| Bucket | `CortexScoreboard7885.bucketOf` (`:45`) | REFUSE if edge < −2 unless (runner lane and rate ≥ 0.10); STRONG if edge > +3 | Wiring correct, rule wrong for runner lanes (§3) |
| Books | `Bucket` enum REFUSE=0, NEUTRAL=1, STRONG=2; `board.record(p.a.lane, p.a.bucket, …)`; grade record `bucket = p.a.bucket.ordinal`, `admitted = p.legacyAdmitted` | Decision-time bucket, decision-time admit | Correct |
| Cortex columns | `LearningEntryTruth8153.verdictRow8153` | STRONG / NEUTRAL / REFUSE = `executableSeries(lane, null, ordinal)`: graded (executable, first of episode, on time) objective (runner lanes: hold-window revision), raw, net of offset; **net MEAN**; medians beside | Correct |
| AATE columns | `decisionRow8269` | TAKE = `admitted == true`, REFUSE = `admitted == false`, same population; net mean | Correct. TAKE n0 = no admits |
| Lane panel | `ui/LearningCounterActivity.kt` (lane row) | Headline = AATE TAKE / REFUSE net mean; subtitle = Cortex STRONG / REFUSE / neutral net means, then net / gross medians | Labels match the fields |
| Report | `Cortex7885.statusLine` `tradeable8153[refuse= neutral= strong=]` | Same `verdictRow8153` | Same statistic |

**One real defect: the gross-median column read a different horizon.**

- *Cause.* `executableGrossSeries8198` and `executableDecisionGrossSeries8269` read `observedGrossPct`. The grade book's `label()` overwrites that field on every revision, including revisions past the hold window (out to 7 days). The net mean beside it reads the objective's own horizon.
- *Fix.* The record now stores the gross of the same label the executable value came from (`firstGross8324`, `revisedGross8324`). `objectiveGross8324` (`CortexGradeBook8144.kt:134`) answers each gross column on the objective's horizon. Rows written before this change fall back to the observed value.
- *Test.* `theGrossMedianFollowsTheObjectivesHorizonNotTheLatestObservedMark`.

**Arithmetic: why "Cortex REFUSE holds the runners".**

- SHITCOIN REFUSE n10 at +1,351.6% against neutral n23 at −17.6%. Ten coins with mean +1,352 means a sum of about +13,500. One runner at about +13,700% with nine coins at −20% gives exactly that.
- The bucket rule (§3) files about 30% of coins REFUSE by chance. The probability that a single runner among 33 coins lands in REFUSE is therefore about 30%.
- This is the expected result of a bucket that carries no information. It is not evidence of an inverted ranking.
- `inversion8324` (§4) now measures the ranking directly.

## 2. The seat and calibration collapse (coordinator finding 1)

**What the report shows.**
- seats 11,145 → 5,203.
- seasoned 1,860 → 156.
- seated 88 → 2.
- calibration lanes → 0.

**What did not cause it.**
- Nothing in 8320–8322 changed `PERSIST_KEY` (`CORTEX_7885_V8076`).
- Nothing changed the Stat, Seat or calibration encoding.
- Nothing changed the voter seat keys. New voters (SHEET_FAVOUR_*) only add seats.

So the 8321 boot did not read the stored book, or wrote an unread one over it. Three paths in the code can do that.

1. **`durableCloseCommit8198` had no `loaded` gate** (`Cortex7885.kt:976`).
   - `onCanonicalClose` calls `ensureLoaded()`. That returns without loading while `LearningPersistence` is not ready.
   - The grade then proceeds, and `commitOnce8090` writes `PERSIST_KEY` from the in-memory (empty) books if the store opened in between.
   - At boot the bus replays every historical close (8168: 1,345 in 9 minutes), so this race is repeated many times.
   - **Fixed:** the commit is refused while unloaded (`CORTEX_CLOSE_COMMIT_UNLOADED_REFUSED_8324`). The close is redelivered.
2. **A decode that throws `OutOfMemoryError`** (heap near cap, 8093 notes) was handled as corrupt.
   - It was quarantined, `loaded` stayed true, and the next save replaced the stored book with whatever had been decoded so far.
   - **Fixed:** a `VirtualMachineError` keeps the Cortex unloaded and retries (`CORTEX_LOAD_VM_ERROR_RETRY_8324`, `Cortex7885.kt:2826`). Other decode errors are quarantined as before.
3. **The stored row was missing.** In that case the legacy path starts empty, as before.

**Restore without reset** (`afterLoad8324`, `Cortex7885.kt:2884`).
- A healthy boot (200 or more seasoned seats, at least as many as already kept) is kept aside under `CORTEX_7885_V8076_BOOT_8324`.
- A boot that reads fewer than half the kept book's seasoned seats merges back from the kept book, or from the 8086 quarantine copy (`LearningPersistence.loadQuarantined8324`, which is multi-part aware). The merge takes:
  - every seat with more scores;
  - every lane prior with more labels;
  - every calibration lane with more grades (`CortexCalibration7901.mergeFrom8324`);
  - every bucket book with more grades;
  - veto and joint books that are missing.
- Newer evidence of the same seat is never replaced.
- Outstanding revision traces are dropped, because their weights were stamped on replaced books. Revisions then go to the grade record only (§4).
- `LearningPersistence.resetAll` deletes the kept row too, so an owner's deliberate reset is never undone.

**What can and cannot be recovered on the owner's device.**
- If 8321's boot overwrote the store (path 1 or 2), the old book is gone unless the quarantine row exists.
- The grade records do not keep per-voter raws, so voters cannot be re-graded from them.
- The hive seeds empty seats as a prior (8291).
- From this build on, a lost boot restores.
- The report line `store (§8324 restore without reset): bootSeasoned=… calibrationLanes=… restored=…|kept=…|boot=… kept=…` shows which case occurred.

Tests: `aBootThatReadAFarSmallerBookRestoresFromTheKeptOne`, `theKeptRowParsesAndCalibrationMergesByEvidence`, `theStoreCanNoLongerBeOverwrittenByUnreadBooks`.

## 2b. Assess latency (coordinator finding 2)

- The report shows `assessMs8187` p50 440 ms / p90 19.2 s.
- The SHEET_FAVOUR voters (`LaneCheatSheet8323.vote8323`) are not the cause. Each is two lock-free map reads (classification cache peek, verdict map), and no sheet is built per assessment. The sheet rebuilds at most every `REFRESH_MS_8323`, from admission only.
- The assessment's own locked work predicted every voter twice under the Cortex lock: once in `fuse`, once in `predictionSnapshot8076`. Each prediction runs a cross-lane global-deviation scan.
- **Fixed:** `CortexLedger7885.fuseWithSnapshot8324` (`:632`) does one prediction per voter. Its outputs are identical, which the test checks.
- How long an assessment waits for the lock is now reported: `assessLockWait8324[mean= max= n=]`.
- The p90 of 19 s is queueing behind the Cortex lock (capture pool queued 173). Which holder is slow is not proven from this report. The new line separates lock wait from compute on the next device report.

Test: `oneFusionPassMatchesFusePlusSnapshot`.

## 3. Check 2: why the Cortex cannot predict STRONG on a tail pond

**Arithmetic on the 8321 pond.**
- The lane prior `m` is `predictMean8322`. It is bounded by design and sits at about −15 to −20 (the typical coin).
- After the 3-point offset, a coin with no strong voter reads about −18 to −23.
- STRONG needs d > +21 to +26 points. Each pull is clamped to ±max(10, 3 SD) and shrunk by 1/(1 + Σw), so STRONG is unreachable.
- REFUSE (< −2) is the default.
- The runner exemption needs a fused runner rate of at least 0.10. That is an absolute bar, and a pond with about 5% runners never clears it.

**Why runnerRate reads low.**
- The runner flag is gross ≥ 50% on the credited play gross. That is correct.
- But runner lanes become runners at the 1 h / 4 h checkpoints, and a revision reached the books only while its decision's voter trace was retained (`graded8006`, 600 traces).
- At 8321 grade rates (about 500 per hour) the store held about 1.2 hours, so most 4 h checkpoints found no trace. The voters, bucket books and lane runner rate never saw them. Worse, the **grade record was not revised either**: the early `return` came before `gradeBook8144.label`.
- **Fixed** (`Cortex7885.kt:1512`, `:1523`):
  - a full trace store drops past-horizon traces first;
  - a revision whose trace is gone still revises its grade record (`REVISION_GRADE_ONLY_8324`).

**Fix: tail-aware runner-lane EV** (`engine/cortex/CortexTailEv8324.kt`).
- The pond is the lane's own graded objective records (hold-window revision, raw, executable-net).
- `EV = p × E[run] + (1 − p) × E[non-run]`, where:
  - `E[run]` is raw while the tail is real (plausible size or chain-witnessed, §5), else the per-label capped average;
  - `p` is the measured runner share, moved by the coin's fused runner-rate deviation from the fused baseline (`Fused.laneRunnerRate8324`).
- Bucket rules (`runnerBucket8324`, used at `Cortex7885.kt:158`):
  - **REFUSE** only with pond evidence (20 or more records) when EV + SE < −2 and the fused edge agrees.
  - **STRONG** when EV clears the lane's own bar: the larger of +3, pond mean + 2, and the top fifth of the lane's recent decision-time EVs.
  - Without evidence, never REFUSE.
- Scalp lanes keep the absolute rule. There, STRONG is a claim that the coin's net is positive, and on a losing pond it should not fire.
- Example (test): a 5% / +760 pond (mean +19) reads a baseline coin at EV +19, which is NEUTRAL (8321 filed it REFUSE). A coin with a 3-point higher runner rate reads +42.4, which is STRONG.

Tests: `aTailPondIsReadAsAMixtureNotAsItsTypicalCoin`, `aRunnerLaneNeverRefusesWithoutEvidenceAndRefusesAClearlyNegativeEv`, `strongFiresForACoinWhoseRunnerOddsBeatTheLane`, `anImplausibleTailIsReadAtItsCapNotFabricatedNorDropped`.

## 4. Check 3: ranking (rank IC on the stored records)

**The report line.**
```
ranking (§8324 …): inversion8324[LANE: ic=±x.xx nN topQ/botQ=+a%/+b% tailIc=… strong/refuse=s/r take/refuse=t/u RANKS|FLAT|INVERTED|THIN pond[n p= run= non= ev=]]
```
- `ic` is the Spearman rank correlation of the stored decision-time edge (`predictedEdgePct`) against the lane objective.
- `tailIc` uses the new stored `tailEv8324`.
- The same verdict appears in the lane panel subtitle (`ranking (§8324): …`).
- The owner's device data is not in this container, so the IC cannot be computed here. It will print on device.

**Voters that rank runners down.**
- On a runner lane, a seat whose ABOVE-mean picks hold measurably fewer runners than its BELOW-mean picks is demoted: z < −1 gives 0, z between −1 and 0 gives half (`CortexTailEv8324.runnerDirection8324`, `CortexLedger7885.runnerFactor8324` :337).
- This applies on 15 or more decisions per side, with 3 or more runners.
- The vote-cleaning line counts these (`antiRunnerDemoted8324=`).
- LOSING_PATTERN, chase and volatility style voters are caught this way when they penalise the coins that run.
- No learning was reset. Seats already re-score on the revised label (`revise8006`). The trace-retention fix in §3 is what lets the long horizon reach them.

Tests: `anAntiRunnerSeatLosesItsAuthorityOnARunnerLaneOnly`, `rankIcAndQuintilesMeasureTheOrdering`.

## 5. Bounded reads hiding the tail (coordinator finding 3): provenance, not size

**The defect.** `tailPlausible8320` used only size: sum per runner ≤ 3× cap. A real pump runner (+2,000 to +16,000%) fails it.

**Fix** (`CortexLedger7885.kt:57`):
- A tail is real at any size when 3 or more of its runners, and at least 80% of them, were **chain-witnessed**.
- Unwitnessed or mixed tails keep the 8320 size rule.
- The 8175 basis-error book (15% at +5,000%, none witnessed) stays bounded: `Aate8185RemainingGapsTest`, superseded by V5.0.8324.

**What counts as witnessed** (`ForwardReturnLabeler7731.witnessed8324` :161, `witnessNow8324` :167). One of:
- the mark agrees within 2× with the coin's own bonding-curve spot, read inside the 8165 witness window;
- the market cap moved by the same multiple from launch scale (`runCorroborated7769`).

**Where provenance is carried.**
- `Stat.witnessed8324`: decayed with the book, revised, persisted as a 7th field (6-field rows still read).
- Every Cortex book: ledger bins, lane priors, buckets, veto and joint books, through `onLabel` / `reviseLabel8006`.
- The labeler tallies (`Tally.witness60`, 13th field).
- The grade records (`witnessed8324`, field 33).

**Labeler cell read.** It now judges the local runners on the **local** sum. 8320 divided the hive-inflated sum by the local runner count, so a hive-heavy cell could never read raw (`tailRead8322` :1634).

**Not changed.** `LanePlaybook7907`'s own setup Stat adds still pass no witness. Those records keep the 8320 size rule until a later build plumbs it.

Tests: `aWitnessedTailIsRealAtAnySize…`, `theWitnessCountFollows…`, `aHiveHeavyCellIsJudgedOnItsLocalTail`, `aTailPondReadsItsWitnessedRunnersRaw`.

## 6. Check 4: AATE's own admission refusing the runners

Report: ADMIT=72, REFUSE=4,120 (`LIVE_PROVEN_SETUP_ONLY_8059` 2,001; `CANDIDATE_ADMISSION_8136` 1,788; `DATA_INCOMPLETE_DEFER` 349; `REPEAT_LOSER_8022` 168).

**Loss tests that cannot tell "no runner yet" from "losing".** On a 5%-runner pond, 12 labels hold no runner 54% of the time, so "12 labels, mean + SE < 0" is a coin flip. Places that used it:
- `CandidateAdmission8136`: SETUP_RECORD_LOSING, TIMING_ADVERSE, CELL / STAGE_PROVEN_LOSING (`losing8136`, `clearlyLosing8136`, `provenLosing8136`);
- `LaneCheatSheet8323.clearLoss8323` (SHEET_AVOID_8323);
- `LanePlaybook7907.pickedSetupNegative8094` / `timingNegative8094` (live PICKED_SETUP_RECORD_NEGATIVE / TIMING_RECORD_NEGATIVE ahead of LIVE_PROVEN_SETUP_ONLY).

**Fix.** On runner lanes each loss test reads the record credited with the runners its sample could still be missing (`CortexTailEv8324.missedRunnerPts8324`):
- the gap is (Laplace upper share (runners + 1)/(n + 1), capped at the lane's share) − the record's share, times the lane premium E[run] − E[non-run], both from the Cortex pond;
- example: 12 runnerless labels on a 5% / +780 lane get +39 points; 100 such labels get +7.7 points, so a large runnerless record is still a proven loss;
- there is no credit without a measured premium (3 or more plausible runners in the lane), when the runner share is unknown, or on scalp lanes;
- support tests are unchanged.

Code: `CandidateAdmission8136.tailCredited8324` (:236), `clearLoss8323` (`LaneCheatSheet8323.kt:425`), `LanePlaybook7907.tailCredit8324` (:1374).

**Veto audit.**
- `NATIVE_THRESHOLD_FAIL_SCORE@SHITCOIN` n20 read +60.0% bounded, +667.7% raw.
- Retirement (`vetoProvenWinners8006`) required the 8185 winsorised proof. A tail pond fails it by construction.
- **Fixed:** `vetoRobustProof8185` (`Cortex7885.kt:1454`) accepts a REAL tail (plausible or witnessed) on its raw read. n ≥ 40 and mean − SE > +2 are still required, so this SHITCOIN rule retires only when its record reaches 40.

**Cheat sheet favour.**
- `favour=[]` in every lane, because the strong records are hive-only (local 0..1, hive lent at most 10 labels against the 20-label bar).
- **Fixed:** `hiveFavour8324` (`LaneCheatSheet8323.kt:411`) grants FAVOUR when the network record (100 or more labels) read **bounded** clears prior + cost by one SE, **and** this install's own labels (5 or more) are above cost. Live closes must not be losing.
- Admission reads such a FAVOUR on the merged record (10 or more labels, lower bound > 0 past the realised shortfall).

**Read but not changed.**
- `LiveAdmissionProof8290.labelSide8316` (median-sign bound). It only lowers the label side of the shortfall, so it cannot refuse a runner.
- `CandidateAdmission8136.supported8136` / `LiveEdgeGate7877.runnerVerdict` (10% runner-rate tail branch). These are SUPPORT tests. Mean − SE on the raw tail read (8322) already supports a real 5% / +760 pond at n ≈ 100. Loosening support further is live money and was not done.
- `RegressionRestore8298` doors, live-close governors 8295 / 8296, economics / route minimum, the $500 non-curve floor, hard safety, `FinalDecisionGate.evaluate()`.

**DATA_INCOMPLETE holding the runners** (8084 forward-by-outcome: SHITCOIN n19 +368% bounded, +705% raw).
- This is the playbook's display tally ([n, sum, wins], five-minute only, never revised). It has no sum of squares or runner count, so it cannot be evidence for admission.
- These coins have no setup. On LIVE they meet `LIVE_GENERIC_PRIOR_UNSUPPORTED_8238` and LIVE_PROVEN_SETUP_ONLY. Their veto-audit row `DATA_INCOMPLETE_DEFER` reads n31 +0.7%, so the defer itself is not the refuser.
- Not changed here. A later build needs a revised, sum-of-squares cohort record before such coins can be admitted on evidence.

## 7. Pins (superseded by V5.0.8324)

- `Aate8007VoteCleaningTest`: cleaned authority × `runnerFactor8324`.
- `Aate8187CortexCompleteTest`: `board.record(…, witness8324)`.
- `GoldenTapeRegressionTest`, `Aate8322CortexSignalTest`: `onLabel(…, witness8324 = w8324)`.
- `Aate8006ClosedLoopTest`: `reviseLabel8006(…, witnessNew8324)`.
- `Aate8322TailCapsTest`, `Aate8185RemainingGapsTest` (tail read source pin + provenance assertions), `Aate8169ExitsTest`: the labeler's local-sum / witness tail read and fresh tally.

## 8. What to watch on device

- `inversion8324[...]` per lane: ic, topQ/botQ, verdict.
- `store (§8324 …)`: bootSeasoned, kept / restored, `assessLockWait8324`.
- Counters:
  - `CORTEX_CLOSE_COMMIT_UNLOADED_REFUSED_8324` and `CORTEX_LOAD_VM_ERROR_RETRY_8324`: either one names the path that lost the 8321 book;
  - `TAIL_EV_REBUCKET_8324_<lane>_<bucket>`;
  - `REVISION_GRADE_ONLY_8324`, `GRADED_TRACE_PAST_HORIZON_EVICTED_8324`.
- `witnessedRunnerLabels8324` in the labels line, and `tailReadRaw8322` against `suspectReads`.
- `antiRunnerDemoted8324` in the vote-cleaning line.
- `cheatSheet8323 favour=[...]`.
