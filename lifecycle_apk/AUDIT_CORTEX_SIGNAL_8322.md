# AUDIT 8322: why the Cortex's measured edge went from hundreds of % to about 0 or negative, and inverted

Base: main 5.0.8319 @559226f83. Branch wt-cx-8322. Tag V5.0.8322.
Evidence: /tmp/claude-0/evidence8319_cortex.md.
Tests: `src/test/kotlin/com/lifecyclebot/engine/Aate8322CortexSignalTest.kt`.
Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. Line numbers refer to this branch.

No local compiler exists, so none of this has been compiled or run. CI is the only check.

**The owner's complaint.**
- The Cortex used to read hundreds of % and pick winners.
- At 8319 every lane reads about 0 or negative, and authority is 0.
- SHITCOIN STRONG reads −6.8% while SHITCOIN REFUSE reads +26.2%.
- MOONSHOT calibration slope is 0.00 and the lane is SUSPENDED.
- Setup records for the same coins are PROVEN: LAUNCH_LADDER_PROVEN n827 +80%.
- The pond is TAIL_DEPENDENT: median ≤ 0, and runners average +744..+1342%.

## 1. Short answer

From 8144 to 8199, a set of honesty fixes took the panel down from "hundreds of %". These were legitimate. The old figures came from repeat REFUSE labels, revisions out to 7 days, and outlier labels caused by basis bugs.

The inversion and the zero authority have a different cause. **Since 8187–8272 the Cortex has been trained on one target and judged on another.**

- **What it is trained on.** The voters (`CortexLedger7885`), the bucket books (`CortexScoreboard7885`), the setup×verdict books and the veto books are still revised along each coin's path. For runner lanes that means out to 6 h (`reviseLabel8006`, since 8006).
- **What it is judged on.** Every authority read comes from the grade book:
  - the walk-forward;
  - the STRONG door;
  - the tradeable verdict rows on the screen and in the report;
  - the 8310 refuse-beats-strong shadow;
  - the 8269 refusal proof;
  - calibration.

  The grade book was frozen at the **five-minute label** (8272). It was **clamped at +1,000%** at ingestion (8187). The STRONG door demanded a **positive median** (8238), which a tail-dependent pond can never have.

So the model learns that volatile early coins pay later, and is then scored on the first five minutes. In those five minutes the coins it rates STRONG have not run yet. The refused coins include the ones that already spiked inside five minutes. The result reads as an inverted, worthless Cortex.

## 2. Ranked causes

| # | Commit (build) | file:line on 559226f83 | Mechanism | Class |
|---|---|---|---|---|
| 1 | `0d72fb641` (8272) | `engine/cortex/CortexGradeBook8144.kt:147-156` `label()` | "The first executable label is the model's immutable FIVE-MINUTE target." Later horizons were stored apart in `executableRevisedPct`. They were **never read and never persisted** (encode stopped at 26 fields). Every authority series was therefore 5-minute only: `executableSeries`, `executableTimed8196`, `executableDecisionSeries8269`, `cells`. On runner lanes (MOONSHOT, SHITCOIN, EXPRESS, PROJECT_SNIPER, which pay at 15–60 min and beyond), each runner entry was graded at about −cost. Meanwhile the voters and board, revised to 6 h by 8006, learned the runs. The walk-forward then ruled the Cortex's own STRONG picks INVERTED_OOS or NO_EDGE, which produced authority 0, the 8310 shadow, and "STRONG −6.8% vs REFUSE +26.2%". | **(b) bug.** The leak 8272 guarded against was real, but the fix was to drop the long horizon. The right fix was to make folds leak-free. |
| 2 | `787837867` (8238) | `engine/cortex/Cortex7885.kt:1076-1086` | The Cortex STRONG door needed a **positive executable median**. On a TAIL_DEPENDENT pond (8303: median ≤ 0, non-runners ≤ +8%) the median is ≤ 0 by construction. So `STRONG_COHORT_NULL_8201_MEDIAN_NOT_POSITIVE` shut the door on exactly the lanes whose edge is the tail. | **(b) bug** for runner lanes. **(c)** for scalp lanes, so it is kept there. |
| 3 | `3fdd288e1` (8187), `e7414e59a`/`d459ea182` (8185) | `Cortex7885.kt:769-771, 1384-1385`; `CortexLedger7885.kt:366, 459, 463`; `CortexScoreboard7885.kt:228, 251`; `LanePlaybook7907.kt:1756, 1811, 1814, 1820, 1875`; `CortexTiming7900.kt:163`; `CortexExit7897.kt:335, 338`; `CortexGradeBook8144.kt:153-155` | Every new label was **clamped at ingestion** to +1,000% (+300% for $1M+), and those values cannot be recovered from the aggregates. On top of that, `Stat.mean()` read any book whose raw mean exceeded the bound **at the bound, with an SE spanning the whole winsorised range**. That is the OUTLIER_SUSPECT veto. With runners averaging +744..+1342%, the top of the tail, which is the edge, was cut off. This directly violates the owner's 8072 directive: "remove caps on blockchain provable data ... no win caps". | **(b) bug** (it destroys data and violates the directive). The robust read itself is legitimate only as a diagnostic, or as a regulariser for point forecasts. |
| 4 | `f991b4d1c` (8291) | `engine/cortex/CortexCalibration7901.kt:53-66` (`earlySlope8291`) + every `calibration.slope(lane) < MIN_CONSISTENT_SLOPE` site | Early shrink-only calibration from 20 grades. A slope below 0.5 SUSPENDS the lane: no refuse power, no overrule, no size-up, no STRONG door. That could happen from about 60 decayed grades. The fit is also fed only the **5-minute** outcome (`calibration.learn` in `onLabel`, never revised), regressed on deviations from voters trained on the long horizon. A slope of 0.00 on MOONSHOT is a fit of the wrong target, not a measurement. | **(b) bug** (wrong target and no grade floor). The measurement itself is **(c)**. |
| 5 | `6a36907a6` (8198/8199), `0086a55ba`/`0a3747319` (8269), `7fe46e8ee` (8166) | `ui/LearningCounterActivity.kt:393-408` | The lane panel led with **gross medians**. On a pond where the typical coin is flat at five minutes, every cohort printed "+0.0%" (for example TREASURY STRONG n56 +0.0% vs REFUSE n99 +0.0%). The report printed the same cohorts' **net means** (−13.8% / −20.9%). So the UI and the report disagreed. | **(a) presentation** |
| 6 | flat marks (labeler, long-standing; made visible by 8076/8094 re-base) | `engine/truth/ForwardReturnLabeler7731.kt:1459` (re-base counter), `:1037` (5-minute booking) | The re-base counted an **unchanged** first mark as "down". That inflates `rebased8076[up=99 down=859]`, which is mostly equal marks: the decision price and the first mark were the same registry or batch value. An off-watch coin whose later marks repeat the same cached price reads gross exactly 0 at five minutes. It then books net = −cost into every voter, bucket, setup and veto book as a "loss". | **(b) bug** (it poisons labels with fake losses). The counter wording was **(a)**. |
| 7 | `37030f808` (8187) executable offset (−3.3 pt/lane); `83f010566` (8153) tradeable records; `1383061de` (8144) hygiene (repeats, unexecutable, revision horizon); `7fe46e8ee` (8165) medians beside means | `CortexGradeBook8144` reads, `CortexLabelHygiene8144` | These took the panel from hundreds of % down to honest numbers: no repeat REFUSE runners, no 7-day survivor revisions, cost charged. | **(c) legitimate honesty fixes.** Not reverted. |

**Where the "+0.0%" figures came from (H3).**
- The exact +0.0% values are **gross medians** (row 5, presentation).
- The zeros that *do* feed the books are flat-mark labels (row 6). They enter the voters, board and calibration as net = −cost.
- The 8291 off-watch ranking (half-horizon LAST_MARK) books a *real* mark from the second half of the window. It is not a source of zeros.

## 3. What changed (V5.0.8322)

1. **Raw labels at ingestion.** Owner directive 8072.
   - Every Cortex-package book now stores the label as delivered, floored only at −100%:
     - `Cortex7885.onLabel` and `reviseLabel8006`;
     - `CortexLedger7885.grade` and `revise8006`;
     - `CortexScoreboard7885.record` and `revise8006`;
     - `LanePlaybook7907.addOutcome8187`, `onLabel` and `reviseLabel7997`;
     - `CortexTiming7900`;
     - `CortexExit7897`;
     - `CortexGradeBook8144.label`.
   - A label above the plausibility cap is counted (`LABEL_ABOVE_CAP_8322`), not cut.
   - `RobustLabelRead8185` is unchanged and stays available for display and diagnostics.
2. **OUTLIER_SUSPECT flags, it does not veto, when the tail is real.**
   - `CortexLedger7885.Stat.mean()/variance()` (`CortexLedger7885.kt:171-186`): a suspect book with **3+ runners** (`tailReal8322`) reads its raw mean with a Bessel-corrected variance. One huge label then moves mean − SE by about 0.
   - A suspect book resting on fewer runners keeps the 8187 bound. This is the single implausible label from 8175 (+60,000%), so the 8187 poisoned-book tests hold unchanged.
   - Point forecasts (`readSum8187` → `predictMean8322`) keep the bound as a regulariser. Fusion predictions are therefore byte-identical to 8319.
   - `fmt8187` shows the decision read with the other read beside it (`~bound` / `~raw`).
3. **Lane-appropriate objective** (`CortexGradeBook8144.objectivePct8322`, `:116`).
   - A runner lane is graded on its latest executable revision inside its hold window. These are the 1 h and 4 h checkpoints the labeler already books, credited on the runner ladder and give-back exit, net of cost.
   - Every other lane keeps the first label.
   - The first label is still immutable (8272 test unchanged).
   - The revision and its `availableAt` now persist (29-field rows; old 24–26-field rows still read).
   - The walk-forward stays leak-free. `Timed8144` carries `availableAtMs` and `firstPct`. `promoteTimed8196` trains each fold on `knownAt8322(foldStart)`: the revision once it was booked, else the 5-minute value. `CortexRefusalTruth8269` does the same for its training window.
   - Calibration follows the same horizon. `CortexCalibration7901.learn` returns a decay stamp, and `revise8322` replaces that grade's outcome at the weight it still owns (`Cortex7885.kt:1408`, counter `CALIBRATION_REVISED_8322`).
4. **Tail-aware STRONG door** (`CortexTailDoor8322`, `Cortex7885.kt:1096`). Runner lanes open on all of these:
   - walk-forward VALIDATED;
   - mean > 0;
   - the mean with the single largest value removed is still > 0 (not one coin);
   - 3+ runners at or above +50% net.

   Other lanes keep the 8238 median door, unchanged. Why the door stays shut is named as `STRONG_COHORT_NULL_8201_TAIL_<why>_8322`.
5. **Flat-mark labels are observed only** (`CortexFlatMark8322`).
   - The labeler flags a five-minute label whose gross is exactly 0 with no peak and no dip (`FORWARD...flatLabels8322`). The Cortex files it as an observed grade record only (`LABEL_HYGIENE_FLAT_MARK_8322`, `CortexGradeBook8144.markFlat8322`).
   - A flat label gets no executable value, never resolves or un-resolves its cohort, and leaves the coin's episode open.
   - The re-base counter now separates `flat8322` from up and down.
6. **Calibration grade floor** (`CortexCalibration7901.suspends8322`). A slope can switch a lane off only when both hold:
   - 100+ decayed grades;
   - the fused deviations have a standard deviation of at least 0.5 pt.

   All seven suspension sites now use it. Below the floor the slope still shrinks the calibrated edge, but it cannot suspend. The status line marks `noSuspend8322`.
7. **One statistic across UI, report and authority.** The lane row leads with the **net mean** of the graded objective, the same figure as the report. Net and gross medians are shown beside it, labelled.
8. **Nothing was reset.**
   - Stored aggregates are untouched.
   - Records whose executable value the 8187 clamp destroyed are counted (`clampedLegacy8322`, in the grade-records status line) and age out. New labels rebuild the series raw.
   - Revisions dropped by 8272 before this build were never persisted, so they cannot be re-graded. New ones are.

## 4. What did not change

- No change to:
  - hard safety;
  - live admission doors (`LiveEdgeGate7877`, `CandidateAdmission8136`, `LiveAdmissionProof8290`: their OUTLIER_SUSPECT checks on labeler cells are untouched);
  - FDG;
  - sizing caps (`LiveRiskPolicy7807` still caps walk-forward Kelly at the label cap);
  - route minimum;
  - economics;
  - realised-money robust reads.
- Cortex authority is still composed by minimum of four factors: label fraction, realised cap, walk-forward, and owner-routed realised evidence. It is still bounded by the 8298 RegressionRestore doors for live entry, and it never overrides safety.
- Veto retirement still requires the 8185 robust proof, so a refusal stays until its winners are robustly real.
- Out of scope, flagged for their owners:
  - `ScannerSourceBrain.recordOutcome` still caps at +200% (`Cortex7885.onLabel`).
  - `LaunchTape7921` still clamps at ingestion.
  - Labeler cell reads (`read8185`) still treat OUTLIER_SUSPECT as no proof for live doors.

## 5. What to watch in the next live report

- **Grade-records line.**
  - `objective8322[...]`, `clampedLegacy8322`, `flat8322`.
  - For runner lanes, `wf=` should move off INVERTED_OOS / NO_EDGE once 1 h / 4 h revisions accumulate. That takes about 4 h after restart for the first 4 h revisions.
- **Strong-cohort doors.** `STRONG_COHORT_NULL_8201_TAIL_*_8322` against `STRONG_COHORT_OFFERED_8200`. On runner lanes, `MEDIAN_NOT_POSITIVE` should drop to 0.
- **Calibration line.** `noSuspend8322` marks. MOONSHOT should no longer show SUSPENDED below 100 grades. `CALIBRATION_REVISED_8322` should grow.
- **Labels line.** `rebased8076[... flat8322=… flatLabels8322=…]` sizes the flat-mark leak. If `flatLabels8322` is a large share of the 5-minute labels, the off-watch mark source is serving stale prices. That is a data-delivery issue.
- **`LABEL_ABOVE_CAP_8322`.** How often the real tail exceeded +1,000%.
- **Risk.** Runner-lane STRONG authority can now validate on long-horizon labels. That feeds `walkForwardResult8188` (Kelly size-up proof) and STRONG cohort admission evidence. Both remain behind realised caps and the 8298 earned-record doors. If realised closes on those lanes lose while the label objective validates, the realised caps (8136/8153/8291) remove the power automatically. Watch `realised8153[S=…]` against `wf=`.

## Pins updated (superseded by V5.0.8322)

- `Aate8187CortexCompleteTest`:
  - `everyNewLabelIsClampedAtIngestion`, `aRevisionRemovesTheOldLabelAsBookedAndClampsTheNewOne` and `anOutcomeTallyIsClampedAndReadRobustly` now expect raw storage.
  - `gradeRecordsReadEveryLabelThroughItsCohortCap` now expects a raw executable series, with the display series still capped.
  - The onLabel ingestion pin now reads `val net8187 = netPct`.
- `Aate8006ClosedLoopTest`: the `retain8006(..., calStamp8322)` pin.
- `Aate8153LearningTest` and `Aate8169CortexTest`: the observed-only branch now includes `|| flat`.
- `GoldenTapeRegressionTest`: the five-minute `Cortex7885.onLabel(..., flat8322 = flat8322)` pin.
- `Aate8198LaneVerdictDisplayTest`: the lane row gap is on net means.
