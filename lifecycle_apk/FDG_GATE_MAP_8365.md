# FDG gate map — V5.0.8365 (base f90c3909d / 5.0.8363)

Every refusal a meme-lane LIVE entry can meet after native qualification, in the order it is met. Paths are relative to
`app/src/main/kotlin/com/lifecyclebot/engine/`. Classes: SAFETY · ECONOMICS · EVIDENCE-HOLD (the owner's hold and
realised live money) · LABEL-EVIDENCE (graded labels / setup records) · DATA · STRUCTURE (timing, chase, setup shape,
duplicate-order) · DUPLICATE (asks a question another gate already answers).

Headline: **47 refusal points** on the live meme path (§1: 6, §2: 18, §4: 8, §5: 15 rows; the §3 shadow binds only
through its hard markers); **11 were duplicates** — 2.4, 2.5, 2.6, 2.7, 2.9, 2.10 (REPEAT_LOSER), 2.13, 2.14, 2.18
NO_TRIGGER, 2.18 EXPECTED_NEGATIVE ("the lane / setup has no proof", "did a setup fire", "is this setup losing") and 4.2
(the same decision asked twice).
8365 keeps one authoritative check per question (table at the end); the others are advisory in LIVE and counted.

## 0. Routing

| # | Where | What |
|---|---|---|
| 0.1 | `FinalDecisionGate.kt:1850` `specialistAdvisoryBlock7845` → `:954` `liveEdgeBlock7877` | FDG asks the live edge gate first (before FDG's own hard blocks at 2761–3373, see §5). |
| 0.2 | `truth/LiveEdgeGate7877.kt:471` | Every covered meme lane (`Entry8114Mode8326.LANES_8326`) routes to `Entry8114Mode8326.decide8326`; the 7877 body below that line runs only for non-meme lanes. |
| 0.3 | `truth/TradePlan7739.kt:404,414,425` | The executor chokepoint (sniper / native paths) asks the hold and then `LiveEdgeGate7877.liveRefusal` again — on the FDG path that was a second full decision of the same candidate (DUPLICATE; fixed, §6 D5). |

## 1. `Entry8114Mode8326.decide8326` (`truth/Entry8114Mode8326.kt:107`)

| # | file:line | Refusal | Reads | Class |
|---|---|---|---|---|
| 1.0 | `:117` | (8365) memoised verdict of this mint/lane/version inside 2 s | `FdgGate8365.memo8365` | — |
| 1.1 | `:120` | `now` = the full current verdict (`LiveEdgeGate7877.liveRefusal` in current scope, incl. CandidateAdmission8136) — binds only if `hardSafety8326(now)` | everything below in §3 | SAFETY / ECONOMICS (only these bind) |
| 1.2 | `:125` | `old` = the 8114 verdict (§2) | §2 | mixed |
| 1.3 | `:136` | `ENTRY8114_LANE_REALISED_LOSING_BINDS_8329_<lane>` | lane's realised live closes (`Cortex7885.laneRealised8295`, `CanonicalFinalizedTradeBus6464`) | EVIDENCE-HOLD |
| 1.4 | `:137` | `EVIDENCE_HOLD_8336_<lane>` (8337/8345 exception lifts it per candidate) | paper ALL closes since hold start (`TradeHistoryStore`), `EvidenceShadow8344.proof8344`, `ExploreShadow8347.bestFor8351` | EVIDENCE-HOLD (authoritative: "may this lane trade live") |
| 1.5 | `:146` | `LIVE_NO_FIRED_SETUP_8364_<lane>` | `LanePlaybook7907.matchedSetups8083` (cached classification) | STRUCTURE (authoritative: "did a named setup fire") |
| 1.6 | `:147` | `NICHE_ONLY_8351_<lane>_<dim>:<value>` | `ExploreShadow8347` niche choice | STRUCTURE |
| 1.7 | `:149` | `ENTRY_TIMING_WAIT_8347_*` / `ENTRY_TIMING_EXPIRED_8347_*` / `ENTRY_TIMING_NO_MARK_8347` | proven exploration variant, observed mark | STRUCTURE |

Composition (`FdgGate8365.oldYields8365` + `:145–149`): hard `now` > realised (1.3/1.4) > fired setup (1.5) > niche (1.6) >
`old` > entry wait (1.7). Before 8365 `old` came before 1.5/1.6, so a coin with no fired setup was attributed to
LIVE_PROVEN_SETUP_ONLY / PICKED_SETUP / PLAYBOOK_* (report: 3,429 + 669 admission rows). A non-label `old` refusal
(economics, chase, dead launch, hard) still comes first.

## 2. The 8114 verdict — `truth/Entry8114Gate8326.kt:30` (`liveVerdict8114`, legacy scope)

| # | file:line | Refusal | Reads | Class |
|---|---|---|---|---|
| 2.1 | `:35` | `earlyLaunchRefusal8048` (dead launch / at launch cap / `CURVE_NOBODY_BOUGHT_8046`) | on-chain curve reserves | SAFETY |
| 2.2 | `:41` | `LiveBootRecovery8074.blind8074` (wallet unreadable; LIVE runtime only) | wallet read | SAFETY |
| 2.3 | `:45` | `LIVE_SETUP_LOSING_8083` | realised live closes of lane+setup (`LiveTruth8064`) | EVIDENCE-HOLD (authoritative: "is this setup losing") |
| 2.4 | `:51` | `TIMING_RECORD_NEGATIVE_8094` | label timing book (setup@band, 12+, mean+SE<0) | LABEL-EVIDENCE · DUPLICATE of 2.3 / 1.4 |
| 2.5 | `:56` | `PICKED_SETUP_RECORD_NEGATIVE_8094` | label setup book (12+, mean+SE<0) | LABEL-EVIDENCE · DUPLICATE of 2.3 / 1.4 |
| 2.6 | `:60` | `TACTIC_RECORD_LOSING_8094` | TacticSwitcher tactic record | LABEL-EVIDENCE · DUPLICATE of 1.4 |
| 2.7 | `:71` | `LIVE_PROVEN_SETUP_ONLY_8059` (unless proven setup / measured edge / runner ticket / grab / shadow audit / cold start / audited Cortex) | label cells, kinds, setup records | LABEL-EVIDENCE · DUPLICATE of 1.4 |
| 2.8 | `:84` | `CHASING_SPIKE_8065` | `TokenMetricStageRouter.snapshot` runup / vs-peak / 5m change | STRUCTURE |
| 2.9 | `:87` | `WATCH_FIRST_8019_BAND_NEGATIVE` / `WATCH_FIRST_7994_THIN_CELL` / `WATCH_FIRST_7994_CELL_NEGATIVE` | label cell / cap band | LABEL-EVIDENCE · DUPLICATE of 1.4 |
| 2.10 | `:184` | `RebuyLockout8019.refusal8019` (`REBUY_LOCKOUT_*`, `REPEAT_LOSER_8022`) | recent closes of the mint | STRUCTURE (REPEAT_LOSER is a label book → evidence door) |
| 2.11 | `:186` | `NET_EDGE_BELOW_2X_COST_8030` / `NET_EDGE_COIN_TOO_SLOW_8035` (1.5× round trip; sparse-history defer bypassed) | `NetEdge8030.preTradeCostPct8329` (8350 depth model via `depthUsdForImpact8337`, max with measured median), executable move | ECONOMICS (binding) |
| 2.12 | `:189` | `SWING_LANE_ALREADY_RAN_8054` / scalp lane needs movement | 1h range vs cost | STRUCTURE |
| 2.13 | `:191` | `Cortex7885.entryRefusal8114` (earned refusal; `CORTEX_7885_C6_SETUP_DEAD_8082_*`) | Cortex grade book | LABEL-EVIDENCE (C6) · DUPLICATE of 1.4 |
| 2.14 | `:196` | `PLAYBOOK_SETUP_PROVEN_LOSING_7907_<lane>_<setup>` | label setup book (30+) | LABEL-EVIDENCE · DUPLICATE of 2.3 / 2.5 |
| 2.15 | `:198` | chart BUY (`chartAdmits7950`) admits here — nothing below runs | chart reader | — |
| 2.16 | `:199` | `MayhemMode7943.liveRefusal` | pump.fun mayhem flag | SAFETY |
| 2.17 | `:201` | promoted specialist admits here | SpecialistMiner7972 | — |
| 2.18 | `:207–222` | priors: `PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_*`, `PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948_*`, `STAGE_PROVEN_LOSING_7928_*`, `COST_EXCEEDS_EDGE_7962_*` | label setup / stage books; measured cost ledger vs setup label gross | LABEL-EVIDENCE (NO_TRIGGER = DUPLICATE of 1.5; EXPECTED_NEGATIVE = DUPLICATE of 2.3/2.5); COST = ECONOMICS |
| 2.19 | `:228` | `EDGE_7877_<shape>` | TradeShapeLearner7883 lane bins | LABEL-EVIDENCE (binding marker) |
| 2.20 | `:233` | `EDGE_7877_<why>_<lane>` | label cell / cohort verdict (`LiveEdgeGate7877.verdictFor` `:187`) | LABEL-EVIDENCE (binding marker) |

## 3. The current ("now") verdict — `truth/LiveEdgeGate7877.kt:466` (shadow; binds only on hard markers)

`LiquidityTruth8037.hydrate8037` → `OnChainDepth8038.earlyLaunchRefusal8048` (`:482`) → wallet blind (`:493`) →
`CandidateAdmission8136.assess8136` (`:502`; `truth/CandidateAdmission8136.kt:358 decide8136`: `SAFETY_REFUSED_8136` `:384`,
`STRUCTURAL_RISK_8136` `:385`, loss patterns `CELL_PROVEN_LOSING_8136` / `SHEET_AVOID_8323` `:444` / `TIMING_ADVERSE_8136`
`:503`, `EXECUTION_ECONOMICS_8136` `:547`, data defers) → DATA defer (`:506–533`, `DATA_INCOMPLETE_DEFER_8136` /
`DATA_DEFER_BUDGET_EXHAUSTED_8136` / `DATA_DEFER_FETCH_NEVER_COMPLETED_8204`) → `LIVE_SETUP_LOSING_8083` `:550` → timing / picked /
tactic `:567–598` → `LIVE_PROVEN_SETUP_ONLY_8059` `:605–624` → `SelectionDoors8310` `:630` → chase `:642–663` → watch first
`:667` (`:946`) → core `:1084` (Cortex, rebuy, NetEdge `:1098` skipped after an admission ADMIT, lane fit, proven-losing
setup, chart, Mayhem, specialist, priors, shape, cohort verdict).
In 8114 mode only its SAFETY / ECONOMICS refusals reach the candidate (`hardSafety8326`); the rest is telemetry
(`ENTRY8114_BYPASSED_*`). CandidateAdmission's `LIVE|` rows in `admission8136[...]` come from this shadow, even in a PAPER
runtime (report 8341) — they are not live refusals.

## 4. TradePlan / sizing / executor (after FDG's edge block)

| # | file:line | Refusal | Reads | Class |
|---|---|---|---|---|
| 4.1 | `truth/TradePlan7739.kt:404` | `EVIDENCE_HOLD_8336_*` (mint-aware) | as 1.4 | EVIDENCE-HOLD (same answer as 1.4) |
| 4.2 | `truth/TradePlan7739.kt:414/425` | `LiveEdgeGate7877.liveRefusal` again | as §1 | DUPLICATE (8365: memo, §6 D5) |
| 4.3 | `truth/TradePlan7739.kt:474,494` | `NO_PLAN_WAIT_7739:*` / `LAUNCH_NEGATIVE` | TradePlan bars / launch ladder | STRUCTURE (binding on the trunk path, advisory for a specialist owner `FinalDecisionGate.kt:976`) |
| 4.4 | `FinalDecisionGate.kt:959–961` | Field Manual / Council (trunk path) | lane manual, council votes | STRUCTURE |
| 4.5 | `truth/MinPromotion8188.kt:121` | `ROUTE_MIN_PROMOTION_REFUSED_*_8188` (loss budget, unmeasured edge, drawdown, unevaluated) | risk size vs route minimum | ECONOMICS (8114-admitted: only `REFUSED_NEGATIVE_EDGE_8188` binds, `Entry8114Mode8326.promotionFilter8326`) |
| 4.6 | `truth/EntryEconomics8329.kt:264,297` | `ROUTE_MIN_PROMOTION_UNSUPPORTED_8329`, `KELLY_TOO_SMALL_8365` | Kelly stake vs route minimum | ECONOMICS |
| 4.7 | `FinalDecisionGate.kt:6266–6281` | `ADMISSION_COST_REQUOTE_FAILED_8209`, `CANONICAL_ENVELOPE_BLOCK_7835`, `NON_ECONOMIC_APPROVAL_7835`, `SIZE_NOT_EXECUTABLE_7835` | sealed size re-quote, envelope | ECONOMICS (inside `evaluate()`; owner-locked) |
| 4.8 | `truth/EntryEconomics8329.kt:165,218,229` | `BUY_TERMINAL_*` pre-broadcast re-quote / chase cap | Jupiter quote / PumpPortal mark | ECONOMICS |

## 5. FDG's own blocks (inside `evaluate()`, owner-locked) — run AFTER §1

`FinalDecisionGate.kt:1854` CellProofLadder7731 (LABEL-EVIDENCE) · `:1855` FreshLaunchSelector7737 (LABEL-EVIDENCE) ·
`:2761/:2815` liquidity watchlist / execution floors (SAFETY) · `:2913` circuit breaker · `:3036` re-entry guard ·
`:3046` `HARD_BLOCK_ZERO_LIQUIDITY` · `:3203–3233` sell pressure / top holder / safety / freeze authority ·
`:3243` freeze proof defer (DATA) · `:3310` `HARD_BLOCK_LIQUIDITY_BELOW_500` · `:3356` rugcheck · `:3373` entry price unknown ·
`:3381` base signal · `:3881` legacy edge veto · `:4347` negative EV · `:5182` size too small.

## 6. Defects found and what 8365 does

| ID | Defect (file:line at f90c3909d) | Class | Fix |
|---|---|---|---|
| D1 | The hold (1.4) passes on the lane's paper ALL-closes proof — entries on which every 8114 evidence door was advisory (`Rehearsal8339.kt:47–55`) — or on the 8347 exploration book (opens whatever any gate says, `ExploreShadow8347.kt:16`). Live then re-imposed 2.4–2.7, 2.9, 2.10 (REPEAT_LOSER), 2.13 (C6), 2.14, 2.18 PLAYBOOK/STAGE: a stale duplicate refused the candidate after the authoritative check passed (`Entry8114Mode8326.kt:133` `realised ?: old`). | DUPLICATE | `FdgGate8365.demoteNow8365` via `Rehearsal8339.doorBindsNow8342`: LIVE, hold basis PAPER_ALL / EXPLORATION ⇒ evidence doors advisory, counted `FDG8365_DEMOTED_<door>_BY_EVIDENCE_HOLD_<basis>`. Lanes unlocked only by EvidenceShadow8344 (doors bound in that proof) or the 8337 exception keep their doors. |
| D2 | `PLAYBOOK_NO_TRIGGER_*` (2.18) reads the classified tag; a coin whose tag is NO_TRIGGER but which fired a named alternative passed 8364 (1.5) and was still refused on the lane-wide NO_TRIGGER aggregate. Same question as 1.5. | DUPLICATE / wrong evidence | LIVE: NO_TRIGGER door advisory; 1.5 is the one check (`BY_FIRED_SETUP_8364`). |
| D3 | 2.4 / 2.5 / 2.14 / 2.18 EXPECTED_NEGATIVE refuse on label records while the same setup's realised live closes exist (`LiveTruth8064`, the record 2.3 judges). | LABEL where realised exists | Advisory when realised n ≥ 5 and mean ≥ 0 (`LiveTruth8064.setupRealised8365`, `BY_REALISED`); realised losing still refuses at 2.3. |
| D4 | Attribution: `old` (label refusal) preceded 1.5 / 1.6, so a candidate with no fired setup was filed under LIVE_PROVEN_SETUP_ONLY / PICKED_SETUP / PLAYBOOK (`Entry8114Mode8326.kt:137`). | DUPLICATE (attribution) | `oldYields8365`: a label `old` yields to 1.5 / 1.6, then re-enters; one final per decision counted in `fdg8365[...]`. |
| D5 | FDG's edge block and `TradePlan7739.liveBlockReason` each ran the full decision (both verdicts, shadow record, hold-refusal accounting, admit filing) for one candidate in one pass. | DUPLICATE / cost | 2 s memo per mint/lane/version/book (`FdgGate8365.memoGet8365`); counted `reused=`. |
| D6 | `SHITCOIN_EXPRESS` was its own key in the 8114 stack (`CanonicalLaneIdentity6506` has no alias) while the specialist side files it as EXPRESS (`SpecialistOwnership7951.kt:67`, `ToolkitSignalSheet.kt:866`): hold / realised / memo read an empty book. | lane canonicalisation | `Entry8114Mode8326.expressAlias8365`; EvidenceHold8336 uses `canon8326`. |
| D7 | Veto audit: 8114 doors 2.4–2.6 never consulted the audit; the rule asked for is "refused set beat admitted on executable labels, n ≥ 100". | wrong-evidence binding | `Cortex7885.vetoRetiresAt100_8365`: n ≥ 100 refused labels with mean − SE > +2 % net (robust) ⇒ advisory (`BY_VETO_AUDIT_N100`). The report has no `veto audit` line, so no gate was demoted on data in this build; the admitted baseline is the lane's negative live / label record (every lane negative, PF 0.10), so +2 % absolute beats it. |

## 7. Checked and left as they are

- Cost double count NetEdge8030 vs CandidateAdmission `EXECUTION_ECONOMICS_8136`: none on the binding path. In 8114 mode the
  admission is not consulted (`Entry8114Mode8326.admission8326`); in the 7877 path NetEdge is skipped after an admission ADMIT
  (`LiveEdgeGate7877.kt:1098`). Both use `costPct8030`, whose impact uses `FieldManual7715.depthUsdForImpact8337` — the same
  depth as the 8350 model (`allInCostPct8350`). `COST_EXCEEDS_EDGE_7962` (2.18) is a separate measured-cost-vs-label-gross
  test; it stays binding (economics).
- DATA deferrals: the 8114 decision has none; its FDG-level twin (`DATA_INCOMPLETE_DEFER_8136`, §3) binds only non-meme
  lanes. The never-resumed deferrals of the 8344 audit (`LANE_LIQ_UNHYDRATED_DEFER`, 57 deferred / 0 resumed) are native-desk
  hand-offs (HydrationReeval8343 / HandoffReceiverTasks8153), outside this stack.
- Ordering: FDG runs the edge block (§1, which computes two verdicts) before its cheap hard blocks (§5 `:3036–3373`). The
  order lives in `evaluate()` (owner-locked); D5 halves the cost and every hard marker found by the shadow already binds first.
- TradePlan's `NO_PLAN_WAIT_7739` (4.3) and the 8347 entry wait (1.7) both wait for an entry moment on different readers;
  left binding (STRUCTURE).

## 8. One authoritative check per question (LIVE, 8114 lanes)

| Question | Authoritative | Advisory duplicates (counted) |
|---|---|---|
| May this lane trade live at all? | EVIDENCE_HOLD_8336 (+ 8329 realised shut, 8337/8345 exception) | LIVE_PROVEN_SETUP_ONLY_8059, WATCH_FIRST_*, TACTIC_RECORD_LOSING_8094, C6_SETUP_DEAD_8082, REPEAT_LOSER_8022, PLAYBOOK_* / STAGE_PROVEN_LOSING, PICKED / TIMING — once the hold passed on a doorless proof |
| Did a named setup fire? | LIVE_NO_FIRED_SETUP_8364 | PLAYBOOK_NO_TRIGGER_* |
| Is this setup losing? | LIVE_SETUP_LOSING_8083 (realised) | PICKED_SETUP_RECORD_NEGATIVE_8094, TIMING_RECORD_NEGATIVE_8094, PLAYBOOK_SETUP_PROVEN_LOSING / EXPECTED_NEGATIVE — when realised n ≥ 5, mean ≥ 0 |
| Which niche / entry style? | NICHE_ONLY_8351, ENTRY_TIMING_*_8347 | — |
| Does the move pay 1.5× cost? | NetEdge8030 (8114) / EXECUTION_ECONOMICS_8136 (7877) + pre-broadcast re-quote | — (never demoted) |
| Is it safe / sellable / exitable? | hard markers (`hardSafety8326`), FDG hard blocks | — (never demoted) |

Status: `fdg8365[book=… admitted=n refused{CLASS=GATE=n,…} reused=n] duplicatesDemoted=[GATE>BASIS=n,…]` (inside `entry8114[…]`).
