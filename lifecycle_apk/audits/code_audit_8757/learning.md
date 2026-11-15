# Learning / scoring code audit — lifecycle-bot 5.0.8757 (origin/main 916e8eb71)

Scope: every component that learns or scores. Read-only audit; the repository was not modified.
Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.
Runtime evidence comes from `aate_pipeline_20261115_150239_790.txt` (build 5.0.8756, uptime 333 s, LIVE, wallet 0.2394 SOL; cited as `log:<line>`).

Severity: **P0** corrupts live decisions now · **P1** corrupts decisions or evidence under conditions that are already present · **P2** waste, observability, or latent.

---

## Executive picture

The bot sees no edge in any lane (`log:36-48`: every lane is `NO_EDGE_SEEN`, except BLUECHIP `LEAK_TAIL_ONLY` and MOONSHOT `LEAK_EDGE_NOT_BOUGHT`). The learners make that worse in three ways:

1. **Seven learners score the same coin-state cell.** Origin, pool depth, age, liquidity and cap are scored by MarketCohort8701, PocketLedger8610, PayingCell8723, ManagedBands8719, the EdgeModel ridge (including its age×pool cross), TreeRanker8737 and Mind8746. Most of them hold their own refuse or admit door. In a market whose base rate is negative, each one learns "this cell loses", and their vetoes combine with AND.
2. **The model that actually refuses live coins is trained on contaminated rows, and its record is not shown.** Mind8746 is fed mostly by TreeRanker8737, which trains on unfillable, unscoped, wrong-horizon dataset rows. The status line prints the ridge's record instead of Mind's.
3. **Size is a product of about 40 multipliers.** Several of them read the same bucket. The product collapses onto hard-coded floors, so the final size carries no information.

Separately, several "safety" learners have the wrong polarity:
- GovernorRecovery: a demotion becomes a promotion.
- FluidLearningAI: a lower win rate loosens the thresholds.
- Gate relaxer: no data counts as a 100 % win rate.
- CanonicalEntryFloor: the floor is the lowest profitable bucket.

---

## Findings (ranked)

### 1. P0 — TreeRanker8737 trains on unfillable, unscoped and wrong-horizon rows. It is the dominant voter of the model that holds live refusal authority.
- **Where:**
  - `engine/truth/TreeRanker8737.kt:106-129` (`rowsOf8737`). It filters only `net` and plausibility, plus the prefixes `LPSTATE/SETUP/PLANWAIT/ORIGIN` at line 113.
  - Compare `engine/truth/EdgeModel8537.kt:597-611` (`replayAccept8642`). That check requires `fill == true`, venue-scope epoch ≥ 8660 and `horizonMs == targetHorizon(lane)`.
  - Rows written with `"fill":false` come from `engine/truth/ForwardReturnLabeler7731.kt:1781-1784` → `EdgeModel8537.recordUnfilled8546` (`EdgeModel8537.kt:733-737`), into the same `edge_dataset_8537.jsonl`.
- **Evidence:**
  - The 8546 comment says unfillable entries are "most fast meme coins". These are exactly the meme lanes where Mind is refusing.
  - `log:1577` `tree8737[trained rows=24912 …]`.
  - `log:1566` mind weights `tree=1.24, tree?=3.35, ridge=0.15, cell=0.00`: the tree is effectively the Mind.
  - `log:589-601`: `FDG_CANDIDATE_ADMISSION_8136_EDGE_MODEL_NEGATIVE_8537` = 14 (SHITCOIN), 86 (MOONSHOT), 86 (EXPRESS) refusals in about 5 min.
  - MOONSHOT is the only lane whose paper book is positive (`log:26` MOONSHOT n176 +7.9 % pf1.46). The model also refuses it.
  - The tree also accepts `PLANADMIT_*` and `CHASEMISS_*` pseudo-lane rows, and rows labelled at the old 60-min horizon mixed with 15-min ones.
- **Fix:** make `rowsOf8737` call `EdgeModel8537.replayAccept8642(o)` and reject every pseudo-lane, i.e. any lane not in `EdgeModel8537.LANES`. Retrain the tree and reset the `MIND8746` and `TREE8737` OOS records, because their history was earned on a contaminated model.

### 2. P0 — Stacked base-rate vetoes: one coin-state signal, seven learners, many doors
- **Where (learners scoring liquidity / cap / age / origin):**

  | Learner | File | Door |
  |---|---|---|
  | MarketCohort8701 (origin × pool [× fresh]) | `engine/truth/MarketCohort8701.kt` `provenLosing8703` | `LIVE_ORIGIN_COHORT_LOSES_8703` |
  | PocketLedger8610 (every band and pair) | `engine/truth/PocketLedger8610.kt:120-143` | `LaneAuthority8577.kt:61-63`: anything but PAYS refuses live, both LOSES and UNPROVEN |
  | PayingCell8723 (lane × origin × age × liq) | `engine/truth/PayingCell8723.kt` | admit evidence (`CandidateAdmission8136.kt:566, 1817`) and a Mind voter |
  | ManagedBands8719 (lane × liq, lane × cap) | `engine/truth/ManagedBands8719.kt` | floors (`LaneAgeLiquidityTruth8140.kt:198`, `LaneHunter7297.kt:340`) |
  | EdgeModel ridge | `EdgeModel8537.kt:120-150` (liq/cap/age bins + `CROSSES_8722` age×pool) | Mind voter, `EDGE_MODEL_NEGATIVE` |
  | TreeRanker8737 | (liq, mcap, age, isPump) | Mind voter |
  | Mind8746 | stacks tree + ridge + cell + LLM | `EDGE_MODEL_NEGATIVE` / `EDGE_MODEL_PROVEN` |

- **Evidence:**
  - `log:32` `pocket8610[cells=20640 paying=118 losing=6920]`.
  - `log:28` marketCohort: nearly every band is `/LOSING`.
  - `log:61` `ADMISSION8136_LIVE_ORIGIN_COHORT_LOSES_8703 n259 -> PROTECTING`.
  - Every lane's `ownerWhy8330` (`log:589-625`) mixes `LIVE_ORIGIN_COHORT_LOSES`, `EDGE_MODEL_NEGATIVE` and the pocket refusals.
  - The cohort door and the pocket door compare a cell's mean with 0. They do not compare it with the coins the lane selects inside that cell. In a negative-base-rate market they veto every candidate the lane picks, whatever its own conditional record.
  - The same evidence is counted again in the Mind's inputs.
- **Fix:**
  - Keep one learned selector, Mind/Tree once fixed per #1. Make MarketCohort8701, PocketLedger8610, PayingCell8723 and ManagedBands8719 report-only, or features of that selector.
  - A cell veto, if kept, must beat the lane's own kept cohort by more than 2 SE, the same as `refusalAuthority8715`, not merely be below zero.
  - LaneAuthority8577 must not treat `UNPROVEN` as a refusal (`POCKET_UNPROVEN_8610`).

### 3. P0 — Multiplier collapse in sizing (about 40 sequential multipliers, duplicated learners, floors hide the collapse)
- **Where:**
  - `engine/FinalDecisionGate.kt`: 32 statements of the form `finalSize = finalSize * m` between lines 1515 and 6080, including personality 6080, symbolic 5523, brain chain 5576/5583/5588, combined 5515, and tiered security/flow/vol/age/social 5076-5263.
  - Lane level, example `v3/scoring/ShitCoinTraderAI.kt`:
    - line 1527: `positionSol * dangerBucketSoftSize(LosingPatternMemory 0.3) * intelligenceGateSizeMult4231(ScoreExpectancy 0.35) * semantic * ultimateEdge * arbDeck`, then `.coerceAtLeast(0.01)`;
    - line 1590: `calibrationSizeMult` from the same ScoreExpectancy bucket (paper ≤ 0.25), then `.coerceAtLeast(0.02)`.
  - In LIVE the same bucket is applied again through `LiveProbabilityEngine.kt:437-441` (`liveSizeShape`, 0.55).
  - Then `CellAllocator7962.combinedMult7962` × Cortex conviction, `GovernorRecovery` probation clamp, and `PersonalityTraitMultipliers`.
- **Evidence:**
  - One score bucket is applied two or three times: `shouldReject` plus `calibrationSizeMult` in paper, `shouldReject` plus `liveSizeShape` in live.
  - LosingPatternMemory's score band (`engine/LosingPatternMemory.kt:69-75`) models the same thing as ScoreExpectancyTracker's score bucket.
  - The product 0.3 × 0.35 × 0.25 ≈ 0.026 hits the 0.01 / 0.02 SOL floors. On a 0.24 SOL wallet that floor is the size, so every learner's opinion is erased.
  - The log never reaches sizing in this window (`FDG allow=0 block=163`, `log:421`), so the effect shows in code only. It is structural.
- **Fix:**
  - Compose size in log space: `log size = log base + Σ w_i·log m_i`, with one bounded total, for example a clamp of the product to [0.5, 1.5].
  - Remove duplicate readers of the same statistic: keep ScoreExpectancy once, and drop LosingPatternMemory sizing or merge it.
  - Make hand-set tier multipliers (tiered security/flow/vol/age/social) report-only.
  - When the product falls below the routable minimum, refuse explicitly. Do not floor.

### 4. P1 — Mind8746 frozen-feature key collides across lanes, so features and labels are mismatched and labels are dropped
- **Where:**
  - `engine/truth/Mind8746.kt:131-139`: `frozen["$mint|$atMs"]`, removed on the first `learn8746(mint, atMs, y)`.
  - `engine/DecisionReasons8031.kt:85-100`: a loop over lane verdicts calls `ForwardReturnLabeler7731.observe(ts, lane, …, nowMs, …)` with one shared `nowMs`, so `EdgeModel8537.snapshot` freezes every lane at the same `atMs`.
- **Evidence:**
  - The tree, ridge, cell and cost features are lane-specific.
  - Lane B's freeze overwrites lane A's. The first label to land, for example lane A with A's lane stop in `managedTarget8722`, trains on B's features. Every other lane's label then finds nothing and is lost.
  - In addition:
    - eviction `frozen.keys.take(MAX_FROZEN/10)` removes arbitrary keys (hash order, not oldest), line 132;
    - pending frozen decisions are not persisted, which the comment at lines 57-60 admits, so after any restart only short-horizon lanes teach the Mind (survivorship toward quick lanes);
    - the last crash was an OOM (`log:4`).
- **Fix:** key `"$mint|$lane|$atMs"` (add lane to the `freeze8746` and `learn8746` signatures), evict by age, and persist frozen features with the labeler checkpoint, as `encodeSnap8571` already does for the edge snapshot.

### 5. P1 — The edge status line reports a model that no longer decides; the deciding model's record is invisible
- **Where:**
  - `engine/truth/EdgeModel8537.kt:777-785` (`statusLine`) reads `oos[recordKey(l, activeModel(l))]`, where `activeModel` (line 329) returns the lane or `"GLOBAL"`, i.e. the ridge.
  - Verdicts use the key returned by `predict8737` (lines 401-419, 537-549): `MIND8746` once warm.
- **Evidence:**
  - `log:31`: every lane reads `learning` with `vsKept=n0/-±-`. That is the ridge's frozen record: the ridge stopped predicting when the tree and Mind took over, and its kept side was reset by the 8750 load (lines 672-680).
  - Meanwhile `EDGE_MODEL_NEGATIVE_8537` refusals are firing (`log:589-601`), which requires `refuses8715` or `authority` under `MIND8746`.
  - The operator cannot see the record that is refusing money.
- **Fix:** print the record for every key per lane (`MIND8746`, `TREE8737`, lane/GLOBAL), and mark which key is active.

### 6. P1 — Out-of-sample authority bars are statistically weak: correlated labels, naive SE and continuous peeking across about 36 records
- **Where:**
  - `EdgeModel8537.kt:270-312`: `Oos.add`; `authority` needs n ≥ 30 and `selMean − 1·SE > 0`; `refusalAuthority8715` needs 30 per side and 2 SE.
  - Labels per mint and lane every 15 min: `ForwardReturnLabeler7731.kt:70` (`REOBSERVE_MS_7731`). Horizons overlap.
  - Same pattern elsewhere: `CortexVsStack8539.kt:41` (1 SE), `ManagedBands8719.proven8719` (1 SE), `CortexExit7897.kt:80-85` (1 SE on 60-second samples of the same position).
- **Evidence:**
  - The SE assumes independent labels. Repeated observations of one mint, and coins moving together in a market-wide dump, are not independent.
  - The bar is re-tested on every label for every (lane × key) record: 12 lanes × 3 keys. No correction for multiple tests or sequential peeking is applied.
  - A pick record can earn authority on one capped +400 % label.
- **Fix:** cluster SE by mint (one label per mint per window, as TreeRanker already weights), require z ≥ 3 or an alpha-spending sequential test, and evaluate on fixed, non-overlapping blocks.

### 7. P1 — The managed target applies the stop twice from two different stop sources and removes stop slippage
- **Where:**
  - `EdgeModel8537.kt:66-67` (`managedTarget8722`) → `ManagedBands8719.kt:43-51` (`managed8719`): clamps net to `-(stop + cost)` unless net ≤ −2 × distance.
  - The label `net` is already "graded on THIS lane's play: its own stop first". See `ForwardReturnLabeler7731.kt:1453-1493` (`captured7945` → `playGross8366`, honest stop fill after slippage).
  - The two stops come from different sources: `StopAuthority7887.laneStopMag8029` (learned StopLearner8040 or base × `LaneExitTuner.getSlMult`, doctrine-floored) versus `FieldManual7715.laneStopPct8392` (cost-derived).
- **Evidence:** a label that the honest stop filled at −(stop + slippage) is lifted back to exactly −(stop + cost). Losses between 1 and 2 stop distances are truncated, which biases every model trained on this target (ridge, tree, Mind, ManagedBands) optimistically.
- **Fix:** train on the labeler's played `net` as is (it already contains the stop). If a cap is wanted, apply only the upside pocket cap.

### 8. P1 — GovernorRecovery6388: a demotion is promoted to full size, a restart launders the state, and the infrastructure check is hard-coded
- **Where:** `engine/truth/GovernorRecovery6388.kt`.
  - `onReconcilerTick` lines 69-137: `HOLD_PROBATION && !governorHold → promote(BASELINE, "PERFORMANCE_GOVERNOR_BASELINE_SMALL_SAMPLE")` (line 135), and `BLOCKED/EXIT_ONLY && !governorHold → SOFT_TIGHT` (line 126).
  - `evaluateDemotion` line 196: `SOFT_TIGHT → HOLD_PROBATION` on a PF or streak breach.
  - The state is memory-only (line 54).
  - `engine/sell/SellReconciler.kt:283-296` passes `true` for 8 of the 10 infrastructure signals.
- **Evidence:**
  - A losing streak moves SOFT_TIGHT to HOLD_PROBATION. The next reconciler tick then moves HOLD_PROBATION to BASELINE (full size: `entryAuthority`, `probationSize` clamp off) whenever `LiveEntrySafetyHold` is not `HOLD`. The demotion ends one level higher than where it started.
  - Every process start resets to BLOCKED and jumps to SOFT_TIGHT. The last crash was an OOM 259 min before this capture (`log:4`).
  - `log:995` state=SOFT_TIGHT `lastPromotion=…INFRA_HEALTHY_GOVERNOR_ALLOW`, while `log:333` says `governor=BASELINE`: two governors disagree.
- **Fix:** leave HOLD_PROBATION only through `evaluatePromotion` (evidence). Persist the state and the rolling evidence. Pass real infrastructure signals or remove the fields.

### 9. P1 — FluidLearningAI progress has inverted, non-monotonic polarity, and live is stuck at bootstrap thresholds
- **Where:** `v3/scoring/FluidLearningAI.kt:635-756` (`getLearningProgress`) and 1095-1108 (`lerp(bootstrap, mature)`).
- **Evidence:**
  - Win rate below 30 % caps progress at 0.50, the "bootstrap ceiling" (line 716). Win rate below 25 % multiplies progress by 0.80 (line 748).
  - `lerp` moves every threshold from its mature (tight) value toward its bootstrap (loose) value as progress falls, so a losing bot gets looser floors.
  - In the >8000-trade branch, win rate 30-40 % yields ≥ 0.70 while win rate below 20 % yields 0.55 (lines 737-739): the output is non-monotonic.
  - In LIVE, `totalTrades` is the live MEME trade count, which is far below `BOOTSTRAP_PHASE_END = 5000` (line 171). Live is therefore permanently near the bootstrap end, made worse by ×0.8.
  - `log:2100` `FLUID_SCORE_SCAFFOLD_6020 baseFloor=51 … effective=28`.
- **Fix:** split "trust in learned layers" (sample count) from "selectivity" (driven by realised expectancy, monotonic: worse results give tighter thresholds, never looser). Compute it per lane from forward labels, not only closed trades.

### 10. P1 — LiveLayerGateRelaxer: no data counts as a 100 % win rate, and hand-set relaxations apply in losing lanes
- **Where:**
  - `engine/LiveLayerGateRelaxer.kt:329-341`: `allLive.filter { it.isStatisticallyMeaningful }` (trades ≥ 5 per strategy, `StrategyTelemetry.kt:115`); empty gives `100.0`.
  - Hard-coded `perLaneMultiplier` (SHITCOIN/EXPRESS 0.85 …).
  - `softStartMultiplier8327` (lines 190-212): cold start gives the full relax.
- **Evidence:**
  - `log:332` `GATE RELAXER (soft-start/fade): SHITCOIN ×0.85(n=2) · EXPRESS ×0.85(n=0) … liveWR=N/A n=0`.
  - There are 6 live closes (W/L 1/5, `log:23`), split 3/2/1 over lanes, so none is "meaningful", the global win-rate floor is skipped, and floors are relaxed 15 %.
  - `LiveRecord8327` reverses only from n ≥ 3 per lane.
- **Fix:** with no meaningful data return multiplier 1.0, never a relax. Delete the hand-set relax table, or derive it from forward-label evidence.

### 11. P1 — V3 UnifiedScorer stacks per-layer trust learners, and its asymmetric boosts bias scores upward
- **Where:**
  - `v3/scoring/UnifiedScorer.kt:968-1047` (`symmetricTrust`): weight = educationWeight (layer accuracy) × MetaCognition trust × Sharpe trust, clamped to [0.5, 1.6] (line 1039).
  - Line 1178: `EducationSubLayerAI.applyMuteBoost` on the same accuracy again. `EducationSubLayerAI.kt:2159`: BOOST ×1.2 only for `vote > 0`.
  - Line 1160: `approval_memory` `coerceAtLeast(0)`.
  - `CollectiveIntelligenceAI.score(v3Score = sum of components)` adds a component computed from the score itself (line ~1080).
  - MetaCognition `adjustScore` from the same components.
- **Evidence:**
  - One accurate layer can be multiplied by about 1.6 × 1.2. Negative votes are never boosted, and negative approval is clamped to 0. This is the same one-way optimism that the `V5.0.7113` comment (lines 421-447) says it removed from modernScore.
  - Layer accuracy is graded only on executed trades (`EducationSubLayerAI.recordTradeOutcomeAcrossAllLayers`, called from `Executor.kt:27596, 30612`). A negative vote that kept a coin out is never graded: survivorship.
  - The V3 total is then a feature of the edge model (`EdgeModel8537.kt:187`), which stacks again.
- **Fix:** one calibrated weight per layer, symmetric in sign, graded on forward labels of every scored coin. Delete applyMuteBoost or the education weight in `symmetricTrust`, not both kept. Remove the ≥ 0 clamp. Make Collective and MetaCognition report-only.

### 12. P1 — The labeler drops the refusal observation when the same coin is admitted later, so refusal books are survivor-biased
- **Where:** `engine/truth/ForwardReturnLabeler7731.kt:1248-1252` (`upgrade7930` → `pending.remove(key, prior7930)`).
- **Evidence:**
  - A coin refused earlier and admitted later, typically because it moved up, loses its refusal label. It is re-observed at the later price as an admit.
  - Every refused-cohort book is therefore missing the refusals the bot changed its mind about, which biases door audits toward PROTECTING. Affected: D DOORS (`log:52-61`), `doorBinds8393`, refuse8715 refN, and the cheat-sheet AVOID records.
- **Fix:** keep both observations (refusal at t0, admit at t1) and book both. Mark the pair for audits.

### 13. P1 — ExitProfile7955 learns exit ladders from raw spike peaks and unfillable entries, fitted in-sample
- **Where:**
  - `engine/truth/ForwardReturnLabeler7731.kt:2045-2052`: `onLabel7955(…, maxOf(o.peakPct, gross0), …)`. This uses the raw peak, not `heldPeak8366`, and checks only `eligible8386`, not `entryFill8521`.
  - `engine/ExitProfile7955.kt:151-176` (`optimalLevels7961`) maximises `level × P(peak ≥ level)` on those same peaks.
  - `learnedStopMag7962` (lines 124-130) sets stops from the same samples.
- **Evidence:** the labeler's own doctrine (lines 1464-1471, 8064 "NO spike credit … live never banks those prints") is ignored here. The ladders sit at levels live cannot fill. The CortexExit `holdVeto` uses this profile (`CortexExit7897.kt:462-466`).
- **Fix:** feed the held peak and filled entries only, and fit the levels on a holdout block.

### 14. P1 — LaneExitTuner: dead in practice; if it came alive it would re-apply the same evidence, spiral, and feed back into labels
- **Where:**
  - `engine/learning/LaneExitTuner.kt:252-…` (`recompute`), with `WINDOW = 60` (line 45) and `RECALC_EVERY = 5` (line 90).
  - Losing lane: `sl -= STEP*2` (line 403).
  - Shrinkage is toward the previous value, not 1.0.
  - Its `getSlMult` feeds the label stop through `StopAuthority7887.kt:121-125`.
- **Evidence:**
  - `log:2251-2255`: only LIVE CASHGEN/MOONSHOT/SHITCOIN with lifetime 1-3 and PAPER QUALITY lifetime 1, against 2332 lifetime closes, so it is always neutral and CPU spent for nothing.
  - When mature, each 60-close window is re-stepped about 12 times with no reversion.
  - A losing lane gets a tighter stop, which means more stop-outs and lower win rate, which tightens the stop further.
  - Through StopAuthority every forward label in that lane is regraded on the tighter stop, so every entry learner is moved by the exit tuner (a learner-to-label feedback loop).
- **Fix:** delete it, or one step per non-overlapping window with mean reversion. Never let a tuner's output grade the labels it learns from: use a fixed stop for labels.

### 15. P1 — The Mind's LLM voter is worse than the base rate, confounded and costly; Mind presence flags are collinear
- **Where:**
  - `engine/truth/EdgeModel8537.kt:408`: ask only when `tree >= 0`.
  - `engine/truth/LlmAnalyst8746.kt`.
  - `Mind8746.kt:35-44` (features).
- **Evidence:**
  - `log:1566` `llmGrade{n185 brier=0.133 baseRateBrier=0.128}`: worse than always predicting the base rate, yet weight `llm=0.31`.
  - `log:1567` `asked=21 answered=3 unparsed=15 lastUnparsed={null}`: a provider returning the literal "null" is counted as unparsed.
  - Because the LLM is asked only when the tree is non-negative, `llm?` is a proxy for "tree ≥ 0 and asked recently", not LLM skill.
  - The LLM probability is turned into a pseudo-percent ((p − 0.5) × 100) and mixed into the voter spread, which is in net-% units.
  - `tree?` and `ssi?` are almost always equal, since the ridge is always present. Their weights are identical (3.35 / 3.35), so the coefficients cannot be interpreted.
  - `ssi=+0.45` contradicts the SSI record (`disagree -17.3 %` vs `agree -10.6 %`).
- **Fix:** remove the LLM voter (report-only, graded offline). Drop `ssi?` and compute the spread over net-% voters only. Treat `"null"` as no answer.

### 16. P1 — Cortex7885 / CortexExit7897: no authority, but the largest memory growth, never trimmed
- **Where:**
  - `engine/cortex/CortexVsStack8539.kt:33-43`.
  - `engine/MemoryGuard7977.kt:504-508` ("Other learning books: counted, never trimmed", `cortexLedgers8309`).
  - `Cortex7885.kt:1223-1290` (`capture`, sampled voter graph), `CortexExit7897.kt:386-402`.
- **Evidence:**
  - `log:33`: every lane is `CORTEX_THIN` or `CORTEX_INCOMPLETE_OUTCOMES`.
  - `log:356-360`: the scoreboard reads EXPRESS `SUSPENDED INVERTED` (strong n349 −14.8 % vs refuse n14 −0.5 %) and SHITCOIN `INVERTED`. Its "strong" picks do worse than its refusals.
  - `did: refusedPaper=0 … sizedUp=0`.
  - `log:325` growth in 5 min: `cortexLedgers8309 +9875KB, cortex +2722KB, playbookCache8309 +2446KB`. The heap is 512 MB and the last crash was an OOM (`log:4`). The share of that growth that is restore/seed load cannot be separated in a 5-min window.
  - CortexExit itself:
    - its proof is 30 samples taken every 60 s on as few as 8 positions (correlated);
    - `BOOK_DECAY` scales all three buckets on every grade (line 392), so a rare bucket decays because of the other buckets' traffic;
    - the position set never decays (line 396).
- **Fix:** freeze the Cortex to report-only with hard byte caps (trim like `evidenceShadow8397`), or delete the voter graph and keep only the LanePlaybook capture that the edge features use. In CortexExit, cluster by position and decay per bucket.

### 17. P2 — PocketLedger8610: the score is not a statistic, the status counts are meaningless, and market cells double-count
- **Where:** `engine/truth/PocketLedger8610.kt`.
  - `callOf8610` (lines 120-132): mean of clipped t over about 60 highly overlapping pair cells.
  - `statusLine8610` (lines 210-216) applies the 0.15 t threshold to single cells.
  - The `record` dedupe (lines 86-96) works only when `atMs` is identical.
- **Evidence:**
  - `paying=118 losing=6920` (`log:32`) counts cells with |t| > 0.15, which is essentially the sign of the mean.
  - Observations of one coin by different paths (FDG `observe` default `now`, ExecutableOpenGate, Cortex shadow `a.atMs`) carry different `atMs`. The `M|` market cells receive the same outcome several times, which inflates n and t.
  - The decay is per observation, not per unit of time.
- **Fix:** dedupe by mint and time window (as PayingCell does, 15 min). Use a proper hierarchical estimate (or let the Tree consume the bands). Report cells with |t| ≥ 2 only. Per #2, make it report-only.

### 18. P2 — CanonicalEntryFloor7266: the floor is the lowest profitable bucket, and it can never move down
- **Where:** `engine/truth/CanonicalEntryFloor7266.kt:92-104` (`learnedFloor`: scans upward and `break`s at the first bucket that passes `profitableNetBand`).
- **Evidence:**
  - A lucky profitable bucket at 20 sets floor 20, which admits every losing bucket above it.
  - The evidence is ScoreExpectancyTracker realised closes only. Buckets below the floor stop trading, so their 200-close windows freeze and cannot argue the floor down. This is a refusal-starvation loop.
  - `log:725` `CANONICAL_FLOOR_FLUID_BELOW_MATURE_7266 514`.
- **Fix:** choose the lowest bucket b such that the pooled record of all buckets ≥ b is profitable with SE, computed on forward labels of scored coins, not closes.

### 19. P2 — BrainConsensusGate is computed and then discarded for every admitted lane candidate (dead learners using CPU)
- **Where:** `engine/BrainConsensusGate.kt:55-57` (`executionVerdict`: ALLOW whenever CandidateAdmission admitted and the lane is in `EdgeModel8537.LANES`), with `evaluate` at lines 75-215.
- **Evidence:**
  - Every candidate pays for LosingPatternMemory, ForwardOutcomeModel, LiveProbabilityEngine, AutonomousMetaPolicy, SecondScorer and the mood read, and the verdict is overridden.
  - `independentNegative7403` ANDs LiveProbabilityEngine (which fuses realised live) with realised live: not independent.
- **Fix:** skip `evaluate` when admission already decided, or make the gate report-only.

### 20. P2 — The LLM-written personality has economic authority, which contradicts the module's own contract
- **Where:**
  - `engine/SentienceOrchestrator.kt:29-31` ("LLM output is never an economic authority and cannot mutate live sizing, floors, exits … personality traits") versus lines 807-870 (`applyTraitMutations7192` → `PersonalityMemoryStore.nudgeTrait`).
  - `engine/PersonalityTraitMultipliers.kt:42-94`, consumed at:
    - `FinalDecisionGate.kt:6077` (size);
    - `Executor.kt:8597` (TP, which is not in the risk check);
    - `EarlyEntryAndPeakCapture6390.kt:213` (trail);
    - `ShitCoinTraderAI.kt:1219` and `MoonshotTraderAI.kt:759` (score floor).
- **Evidence:** `log:414` `Personality tune: size×0.98 · floor+0 · TP+-1.4% · trail×1.00`. LLM-proposed deltas of ±0.05 per cycle move size (±15 %), TP (−2..+5 pts), trail and floor, with no out-of-sample record. Only size and floor expansion are checked. The label renders "TP+-1.4%".
- **Fix:** make traits report-only, or grade every trait move like any other learner before it binds.

---

## Further findings (not in the top 20)

| Sev | Where | Finding | Fix |
|---|---|---|---|
| P2 | `engine/LosingPatternMemory.kt:118-123, 140-151` | Numeric bug: `mean = Σ pnl over ALL trades / (wins + losses)`. Trades between −5 % and +1 % are summed but not counted, which inflates \|mean\|. It also duplicates ScoreExpectancyTracker's score-bucket learner (see #3). | Divide by the total count, or delete in favour of ScoreExpectancy. |
| P2 | `engine/ScoreExpectancyTracker.kt:278-281` | `snapshot(layer)` filters `key.startsWith("LAYER:")`, but keys are `MODE\|LANE:bucket`, so a filtered snapshot is always empty. `laneStats7380` loops buckets 0..9, so scores ≥ 100 are ignored. | Match on `"\|${layer}:"`; include bucket 10+. |
| P2 | `engine/truth/FinalizedBusConsumerBridge6465.kt:507-511` | A non-finite `realizedReturnPct` becomes a **0 %** learning sample for ScoreExpectancy, HoldDuration, ExitReason and LaneExitTuner, instead of being skipped. | Skip non-finite values. |
| P2 | `engine/CellAllocator7962.kt:56, 480` with `posterior7962` (139-153) | Live closes are added with weight 3 and that weight is used as the posterior sample size (`n = w`), so live cells are 3× overconfident. Pseudo-lanes other than PLANWAIT/PLANADMIT (CHASEMISS_, LPSTATE8725_, SETUP8692_) feed the GLOBAL node. Size = cortex × allocator up to 2.5× on a 20-sample, P > 0.8 bar. | Use weight for the mean only and keep n as a count; filter to `EdgeModel8537.LANES`. |
| P2 | `engine/truth/EdgeModel8537.kt:491-528` | Every pseudo-lane label (PLANWAIT_, PLANADMIT_, CHASEMISS_, LPSTATE8725_, SETUP8692_, CORTEX_SHADOW) trains the global ridge, Mind, PocketLedger, ManagedBands and PayingCell. The same coin and outcome repeat with only the lane one-hot as "other". | `learn()` should return early for lanes not in `LANES`, except for a dedicated research store. |
| P2 | Targets across the stack | Inconsistent units: ridge/Mind managed stop target, Mind clip [−100, 200] (`Mind8746.kt:141`), Tree clip [−60, 200] (`TreeRanker8737.kt:35-36`), refusal OOS clip 200, Pocket/PayingCell `min(net, 400)` with no stop. The voters stacked by the Mind are in different units. | One target definition shared by all. |
| P2 | `engine/truth/PayingCell8723.kt` | Dead in practice: `log:1563` `paying{-}`, Mind weight `cell=0.00, cell?=0.00`, yet it is read per verdict (cached per minute, `EdgeModel8537.kt:386-395`) and per admission. | Keep as a report, or fold into Tree features. |
| P2 | `engine/truth/MemeStrategies8546.kt:159-…`, `tick8546` | Dead in practice: `log:27` all families 0 except FOLLOW_SIZE=1, with `m1=empty:715/short:126/ready:38` of 879. Up to 120 evaluations per tick for 38 usable charts. | Gate on M1 availability, or retire. |
| P2 | `engine/truth/MarketCohort8701.kt:56-62` | `claim8701` files the cohort once per mint per 15 min on whichever lane observes first. The cohort outcome is that lane's stop-graded label, which depends on lane evaluation order. | File the cohort once per mint, on a lane-neutral target. |
| P2 | `engine/cortex/LaneCheatSheet8323.kt:486-499` | AVOID on 12 labels with mean + 2 SE < 0 (fat-tailed runner returns: SE from 12 runner-free labels is small). The tail credit was withdrawn in 8325. Labels are from all scored coins (not starving), but AVOID binds through `VERIFIED_RECORD_DOORS_8650`. | Require ≥ 30, or a tail-aware bound. |
| P2 | `engine/truth/Mind8746.kt:143` | `FORGET 0.999` is applied to the normal equations, but `n` (warm bar 300) never decays. Warm stays true on stale data. | Track decayed n. |
| P2 | `v3/scoring/UnifiedScorer.kt:116, 403` | `classicScore` and `modernScore` are about 700 lines of dead code under the production `unifiedMode = true` (line 63). modernScore keeps the positive-only AITrustNetwork weight (lines 556-561). | Delete, or confirm it is unreachable. |
| P2 | `engine/truth/EdgeModel8537.kt:251-266` | Ridge `solve` silently returns the previous weights when Cholesky fails (`s <= 0`). | Count and expose failures. |
| P2 | `engine/learning/LaneExitTuner.kt` getters vs `ExitRegret7752` | In live, TP and SL multipliers are multiplied by ExitRegret, and the RunnerDoctrine floor stacks on that: three exit learners on one stop. The live Executor TP also stacks `max(tp, learnedTpFloor) × LiveStrategyTuner.tpMult + personality` (`Executor.kt:8587-8603`). | One exit learner per parameter. |

---

## Direct answers to the requested categories

- **Stacked scoring:**
  - #2: the coin-state cell is scored by seven learners and vetoed by several doors.
  - #3: about 40 size multipliers, with the same bucket applied two or three times.
  - #11: three or four per-layer trust learners multiplied.
  - The Mind stacks voters in different units (further findings, targets).
  - The products collapse onto the 0.01 / 0.02 SOL floors.
- **Polarity:**
  - #8: a demotion leads to a promotion.
  - #9: a worse win rate loosens thresholds.
  - #10: no data counts as a 100 % win rate.
  - #18: the lowest profitable bucket becomes the floor.
  - #15: the SSI weight sign contradicts its own record.
  - #11: one-way boosts and the ≥ 0 approval clamp.
- **Contaminated data:**
  - #1: unfillable, unscoped, wrong-horizon rows train the Tree.
  - #7: the stop is double-applied in the target.
  - #12: refusal books are survivor-biased.
  - #13: raw spike peaks and unfilled entries train exit ladders.
  - Pseudo-lane duplicates (further findings).
  - NaN closes counted as 0 % (further findings).
- **Feedback loops:**
  - #14: the exit tuner changes label grading.
  - #18: the floor starves its own buckets.
  - #11: layer accuracy is graded only on admitted trades.
- **Authority without out-of-sample proof:**
  - #20: LLM traits.
  - #10: hand-set relaxer.
  - #3: hand-set tier multipliers.
  - #6: OOS bars too weak.
  - #5: the deciding record is invisible.
- **Dead learners that still cost CPU or memory:** #16 Cortex, #19 BrainConsensusGate, #14 LaneExitTuner, MemeStrategies8546, PayingCell8723, the dead UnifiedScorer paths.
- **Duplicate learners:**
  - LosingPatternMemory and ScoreExpectancyTracker (score band).
  - PocketLedger, PayingCell, MarketCohort and ManagedBands (coin-state cells).
  - Education, MetaCognition and Sharpe trust (layer skill).
  - LaneExitTuner, ExitRegret, StopLearner8040 and ExitProfile7955 (stops).
- **Numeric bugs:**
  - LosingPatternMemory mean denominator.
  - Mind frozen key collision and arbitrary eviction (#4).
  - CortexExit cross-bucket decay (#16).
  - ScoreExpectancy snapshot filter.
  - NaN mapped to 0.
  - Mind `n` never decays.
  - Ridge solve failure is silent.

## Suggested order of work
1. #1 (tree data filter), then reset the `MIND8746` and `TREE8737` OOS records.
2. #4 (Mind key) and #5 (show the deciding record).
3. #7 (one target).
4. #2 (make the duplicate cell learners report-only) and #3 (log-space sizing with one cap).
5. #8, #9 and #10 (polarity of the governors).
6. #12 and #13 (label survivorship).
7. #16 (Cortex memory), and delete the dead learners in #19, #14 and the further findings.
