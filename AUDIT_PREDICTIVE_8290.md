# AUDIT_PREDICTIVE_8290: why live buys are losers

Base: main 5.0.8286 @7c147e519. Branch wt-apred-8290. Scope: the predictive and admission path that decides what live money buys.
Evidence: the live 19-minute report for 5.0.8286 (/tmp/claude-0/audit8286.md). It shows 4/4 live losses (-33.8, -16.7, -10.6, -9.2 %), every one sized UNPROVEN at ROUTE_MIN. In the same report the label book read admitted60 n17 **+1.8 %**, refused60 -24 %, and the executable offset -3.5 pt.

There is no JVM compiler here, so every claim below comes from reading the code. The numbers are from the owner's report.

## How a live buy is admitted today (trace)

1. `LiveEdgeGate7877.liveRefusal` (live block, ~L468) calls `CandidateAdmission8136.assess8136` first.
   - DEFER_DATA defers.
   - A specific REFUSE is terminal.
   - ADMIT with `specific=true` ("SPECIFIC_EDGE_PROVEN_8136") sets `candidateSpecificAdmission8136`. That flag then **stands down** the 8094 timing / picked-setup / tactic vetoes (8201), the spike-chase rule (8243), the lane shape bins (8256), the watch-first negative cell (8256) and every non-"proven-negative" 7930 prior.
2. Next comes the proven-only door, `LIVE_PROVEN_SETUP_ONLY_8059` (L581). It passes when ANY of these holds:
   - `provenSetup8056`: a playbook setup record with n≥30 and mean−SE>0;
   - `measuredEdge8060`: a cell proven, `kindProven8063`, `cellBeatsLane7970`, a SpecialistMiner match, or an expert wallet;
   - a candidate-specific admission;
   - `genericNative8211`: unreachable live since 8238, see F9;
   - a RunnerTicket, a runner forming (RunnerPlay / RunnerGrab), a shadow audit, the cold start (3 per 6 h), or a Cortex audit.
3. Watch-first comes next, then `liveRefusalCore7970`: Cortex, rebuy lockout, NetEdge (skipped for a specific admission), lane fit, proven-losing named setup, chart BUY, specialist, playbook / stage / cost priors, shape, and finally `verdictFor`.
   - **"NONE" in `edge7877 admit=[MOONSHOT|NONE=67, SHITCOIN|NONE=26]`** is `Source.NONE`: either `EXPLORE_UNMEASURED` (the cell is thin or unseen) or `RUNNER_EXPLORE_UNCERTAIN` (the runner cohorts are measured but uncertain). So **no live admit in the report rested on a measured cell edge.** Every admit got its proof at the doors in steps 1–2, from forward-label books. That makes the books' read the defect.
4. The sizer (`LiveRiskPolicy7807.edgeKellyFracFor8057` + 8188 `validatedKellyFrac8188`) already refuses to grow size on forward labels alone. Every live buy came out UNPROVEN (route min). In other words, **the sizer's own proof test failed on every candidate the admission doors had just called "proven".** Admission was reading weaker proof than sizing.

## Findings (with evidence, file:line, impact)

### F1. OUTLIER_SUSPECT records still prove. The 8185 "pessimistic bound" is not pessimistic. HIGH
- `RobustLabelRead8185.read8185` (truth/RobustLabelRead8185.kt ~L95) reads a suspect record at its winsorised **upper** bound. Its standard error is `max(cappedSE, hi−lo)`.
- When the excess labels are moderate (1,000–4,000 %), `lo = (sum − sumSq/4cap)/n` is **positive**, so mean−SE > 0 and `cellProven8060` passes.
- Worked case, pinned in the test: 20 labels of +2,000 % and 80 of −20 %. The read is suspect, yet mean−SE ≈ +145 %, so it is "proven".
- Point-estimate tests read the upper bound itself:
  - `runnerVerdict` TAIL_EV (LiveEdgeGate7877 ~L161: n≥100, mean>0, runner≥10 %);
  - `cellWinsProven8094` (no SE, mean>0);
  - watch-first `cellEarnsLive7994` / `cellSkipsPlanWait7995` (n≥10, mean≥0);
  - `supported8136`'s tail branch;
  - `CellProofLadder7731` POSITIVE;
  - RunnerGrab's proven runner cell.
- Evidence: kinds8063 best are all SUSPECT (NARR|SHITCOIN|UNKNOWN +328.8 %, KIND|PROJECT_SNIPER|PUMPSWAP|MC_LT10K +107 %, KIND|MOONSHOT|OTHER|MC_LT10K +88.7 %). best60 BAND|MOONSHOT|MC_LT10K +165.6 % SUSPECT. `kindProven8063=663`.
  - Caveat: that counter increments on every call, and `cellOrKindProven8094` is called up to 5× per candidate.
- `CandidateAdmission8136.Evidence8136` had no suspect flag. `LanePlaybook7907.stat()` → `executableCopy8187` builds a realised-money Stat, so the setup record **loses its suspect flag** before admission reads it.
- Impact: live money admitted on basis-error books.

### F2. The 8187 executable offset is starved, so labels are read about 15 pt too high. HIGH
- `Cortex7885.execOffset8187` (cortex/Cortex7885.kt ~L1507) is the max of the cost gap and the *paired* shortfall.
- `CortexGradeBook8144.pairedShortfall8187` (~L281) needs an admitted decision with its own observed label. 657 of 701 graded decisions had lost it (`LABEL_LOST_GAP_8187=657`), so only the cost gap was measured: −3.5 pt.
- The actual gap: admitted60 +1.8 % against live closes averaging −17.6 %, about 19 pt (≈8.6 pt after n/(n+5) shrink on 4 closes).
- The label-to-bank gap has these sources:
  - (a) Entry latency / chase. The label re-bases at the first mark 5–60 s after the decision (`rebaseDelayMs8094`), and charges the chase only when the lane latency is unmeasured.
  - (b) Stop slippage. The label books the stop at exactly `-stop` (`LaneParticipation8029.playGross8029`), but live stops land at −20..−34 % (separate stop agent).
  - (c) Label horizon. Progressive grading (`checkpoint7997`, ForwardReturnLabeler7731 ~L1130) **revises the 5-minute slot up to 7 days** for coins still alive. The revision credits a held-peak ladder plus the rest at the mark, a survivor-weighted horizon that the live exit doctrine (lane stop, timed exits) never holds.
  - (d) Curve-only marks. These are partly handled by 8165 curve witness and 8180.
- I can't take those four apart without runtime pairs, because the pairs are exactly what is lost. The fix measures them **together, unpaired**.
- Impact: every proof (setup, cell, kind, Cortex, grab) reads +15 pt or more higher than a trade banks.

### F3. The live fresh cell is not read executable. MEDIUM
- `CandidateAdmission8136.candidateFor8136` (~L989) built `cellLoss8169` from `freshCellStatFor8169`. That is the raw read: `freshStat8169` never applies `executable8187`.
- Since 8210 `liveCell8210 = cellLoss8169` is also the **positive** live cell evidence. So a live SPECIFIC_EDGE_PROVEN could rest on a raw label mean that the all-time read (`cellStatFor`) would have discounted.

### F4. 8243 stood the spike-chase rule down for any specific ADMIT. HIGH (correlates with "buys losers")
- Commit 89ecd5788, LiveEdgeGate7877 ~L615. Its justification was "the chase is in the record". That is true only for a measured lane latency, and the record averages every point of the move. The timing band only has to be "not losing" (8257 then made even that advisory at one SE).
- 5.0.8286's worst close, BREAKOUT_RUNNER at −33.8 %, has the shape of a chased move.

### F5. 8256 made the measured shape bins yield to a specific ADMIT. HIGH
- Commit a845415d1, LiveEdgeGate7877 ~L1145. `TradeShapeLearner7883.shapeRefusal` is `provenLosing` on the lane's own labels, a measured loss rule. The line two above it even says it "remains binding even for a positive setup/cell elsewhere".
- 8256 ranked a forward-label setup record above it.
- Evidence: trade-shape rules AGE_MIN<3, LIQ<3k, MCAP<10k read strongly negative per lane.

### F6. 8257's thin-record advisory also applies live. MEDIUM
- Commit 914d617a6, CandidateAdmission8136 ~L337 / ~L402. A selected setup or timing record with 12+ labels and mean+SE<0 no longer refuses unless the loss is two SE below zero.
- Live, the losing strategy can then be carried by a supported cell, Cortex or grab edge under `SPECIFIC_EDGE_PROVEN`. 8201 then stands down LiveEdgeGate's duplicate `PICKED_SETUP_RECORD_NEGATIVE_8094`.
- 8198 had explicitly forbidden "a positive cell record carries a losing strategy". 8257 reopened it for live money.

### F7. 7955 setup authority admits on 15 labels without the executable / suspect test. LOW
- `verdictFor` → `setupAdmits7955`, LiveEdgeGate7877 ~L219: graduated label authority ≥0.5 on more labels than the cell.

### F8. Lane playbook "auth=1.00" records (LAUNCH_LADDER_PROVEN n824 +83.5 %) are label-only. Covered by F2.
- Not suspect: the 8187 runner-floor bound allows +83.5 % with about 10 % runners.
- `realisedBlend8187` only bites once the *same setup* has 2+ realised closes. The four live losses were on four different setups.
- The lane shortfall (F2 fix) now applies to them.

### F9. Other admit paths checked: no change needed
- `genericNativeClearsProvenOnly8211` is unreachable live. Since 8238, `GENERIC_PRIOR_CLEARS` live requires `cellSupported`, and that returns SPECIFIC_EDGE first.
- 8279 native-qualified admits are `specific=false` (tightening, correct).
- `kindStatsFor8063` already drops NARR|*|UNKNOWN (8280, correct).
- StageCarry8180 at the VALID margin produces no edge, so the candidate still needs the proven-only door (acceptable).
- 8283 lifecycle history is only recorded, never waited for (fine).

### F10. Predictive layer
- **PredictiveEntryOracle6915**: ADVISORY. It refused 542/542 (coldRefuse 396 via the score heuristic; the rest via LIVE-mode `laneHistLIVE` / `bookHistLIVE`). Its live reads are mode-correct (7402 / 7809). Its refusals agreed with reality, but the cold-book refusals are a heuristic, not proof, so it stays advisory. The fixes make the binding doors match its direction.
- **ForwardOutcomeModel**: fed by the same 5-minute forward labels (`recordLabel7734`), so it shares F2. It is advisory into the oracle and Brain consensus. E[pnl] negative is consistent with live.
- **LiveProbabilityEngine**: lane snapshots of realised closes. Correct book.
- **BrainConsensusGate**: soft-block 31.7 % on forward / live pWin. Reads are local learned state. No wrong read found.
- **TradeShapeLearner7883**: correct, measured. Its bypass was the defect (F5).
- **CellAllocator7962**: slot / priority bandit on the same labels. Not an admit path.
- **CellProofLadder7731**: NEGATIVE is sound. POSITIVE could tag a suspect record (F1).

## Fixes (commit on wt-apred-8290)

1. **New `engine/truth/LiveAdmissionProof8290`.**
   - `unpairedShortfall8290`: lane admitted-label mean minus the mean of its recent 50 live learning-eligible closes (capped −100..+1000), shrunk n/(n+5), floored at 0, needing at least 2 closes.
   - `extraBeyondOffset8290`: what the 8187 offset has not already taken off.
   - `survives8290`: not suspect, n≥30 (15 for 7955), mean − SE − extra > 0.
   - `laneExtra8290`: cached 30 s, **0 outside LIVE**.
   - Status line appended to `edge7877` as `proof8290[...]`.
2. **Suspect never admits.**
   - LiveEdgeGate: `judge` / `judgeRunner` refuse `CELL_OUTLIER_SUSPECT_8290`, and suspect cohorts are excluded. `cohortProvenPositive7948`, `cellOrKindProven8094`, `kindProven8063`, `measuredEdge8060`, the watch-first point estimates and the `specificRelease8153` kind check all exclude suspect records.
   - `RunnerGrab7967.provenCellFor` / `grabEvidence8245` / `cellBeatsLane7970` exclude suspect cells.
   - `CellProofLadder7731` never tags a suspect record POSITIVE.
   - `CandidateAdmission8136.Evidence8136.suspect8290`: `supported8136`, `grabSupported8245` and `bestSetup8153` ignore suspect records.
   - `LanePlaybook7907.firedSetupRecords8136` now carries the stored record's suspect flag as element [4].
3. **Shortfall applied to every label proof (live).**
   - In the admission adapter it is applied to setups, timing, setup timings, the cell, the fresh cell (F3: now read executable too), the Cortex STRONG cohort (shifted inside `decide8136`) and the runner grab. It is traced as `liveShortfall8290=`.
   - LanePlaybook `provenSetup8056` and `coldStartSetup8080` apply it too.
   - The `cellOrKindProven8094` / `kindProven8063` / `measuredEdge8060` doors apply it too, plus the 7955 authority path (`selectedSetupSurvives8290`).
4. **Reverted 8243 (F4).** The spike-chase rule binds a specific ADMIT again. The proven cell / kind, a developing grab and the tail-proven replay still stand it down.
5. **Reverted 8256 shape yield (F5).** The shape bin binds over a specific ADMIT; the earned Cortex overrule stays the one exit. The 8256 watch-first cell yield is kept, because the admission it yields to must now survive F1–F3.
6. **F6, live only.** The selected strategy's own losing setup or timing record (12+, mean+SE<0) refuses live. Paper keeps the 8257 advisory.

All of these are one-way: they can only refuse a live buy. Hard safety is untouched. Paper is untouched (extra = 0, the live flag is false). Learning stores and display lines are unchanged. If nothing survives, the bot buys less.

Tests:
- New: `src/test/kotlin/com/lifecyclebot/engine/Aate8290PredictiveAuditTest.kt` (12 tests: pure rules, the decision record, wiring contracts).
- Owner-pinned tests updated, each marked "Superseded by V5.0.8290":
  - `Aate8256GenericPriorYieldsTest.aLaneShapeBinStandsDownForTheCandidatesSupportedSetup`;
  - `Aate8243StrategyElectionTest` (CHASE label);
  - `Aate8201AdmittedTerminalTest` (CHASE label).
- `val2.sh` validated; `pins.py` broken 0.

## Growth target (2x–5x daily)

- **Wiring checked.**
  - `AutoCompoundEngine.processWin` is called on live closes (Executor ~L26250 / ~L29569) and feeds `getSizeMultiplier()` into the lane AIs' proposals.
  - The live floors and wallet-percent tiers in `LiveSizingProfile` (12 / 18 / 25 %) exist.
  - The proof-based size-up path works: `LiveSizeTarget8094` lets PROVEN take up to 25 % of the wallet, and `LiveRiskPolicy7807` sizes at quarter-Kelly, with the drawdown waiver for a mature edge.
  - `LiveRiskPolicy7807` clamps UNPROVEN requests to the upstream proposal, while size-up needs `edgeKellyFrac > 0` = min(label share, **realised or walk-forward proof**) (8188). In 5.0.8286 every buy was UNPROVEN (route min). That is not a broken compounding path: the realised live record was 4/4 losses, so there was no edge to compound.
- **What throttled growth was selection, not sizing.** The admission doors spent live trade count on label-only and suspect "proofs" that the sizer itself would not back, which bled the wallet the compounding needs. The 8290 fixes point live trade count at candidates whose proof survives the measured realised shortfall. Once a lane's live closes beat its labels, the shortfall drops to 0, those proofs admit at full strength, and the 8188 realised proof then lets size compound. Aggression now follows realised on-chain wins only.
- **Gap noted, outside my scope (sizing).** `validatedKellyFrac8188` returns 0 when the candidate has no label share, even if its lane has a mature realised Kelly. A lane with real live wins but no setup / cell / kind label proof therefore never sizes up. I did not change this: lending a lane-wide realised edge to any candidate in the lane could spend aggression on losing setups. It needs a candidate-specific realised record (`setupRealised8187`) to fix it properly. Recommended to the sizing owner.
- Nothing here loosens hard safety or uses paper or fabricated data.
