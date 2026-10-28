# Buy-path audit — V5.0.8418 (base origin/main 46792a404, 5.0.8417; evidence: live report 5.0.8416, 3.7 h)

Owner: "8114 logic did no better anyway. When combined with more of what was built later it flopped. The full buy path logic
needs investigation." The goal is the $50 → $1,000,000 mentality: catch real runners, take a 100x when it shows, otherwise
stay quiet. The daily 2x–5x is a scoreboard, not a sizing input.

Paths are relative to `app/src/main/kotlin/com/lifecyclebot/engine/`. Line numbers are at this branch's head.

## 0. Headline

- **5.0.8416:** FDG allowed 0 and blocked 9,286. There was 1 execution, which was refused at the executor (EXEC_REFUSED=1).
- **The binding gate is the evidence hold.** Every candidate the whole canonical path admitted (`core == null`) was then
  refused by `EVIDENCE_HOLD_8336`: 2,077 FDG blocks, and 13,173 pre-intent refusals on MOONSHOT alone.
  - In a LIVE runtime the hold's paper bar never moves: the report says "no meme-lane paper closes since start".
  - The only way past the hold is the 8337/8345 exception (the "100x door"). It submitted **0** live attempts. Over the
    same period the grab lane admitted **435** live candidates on their own evidence.
- **No buy-path gate could be graded on what it refused.** The forward labeler keeps one pending observation per
  (mint, lane). The lane's NATIVE verdict always arrives first, so the FDG refusal a moment later was dropped as a
  re-observation.
  - The Cortex veto book (which FdgGate8365's n≥100 audit reads) therefore only ever held `NATIVE_*` reasons.
  - Per-gate "refuses winners" figures for FDG doors **do not exist in this log**. This build adds them (§4 C2).
- **The "economics paradox" figures are not gate evidence:**
  - `admitted60 n1300 +38.3% wr49%` is mostly NATIVE_READY *prediction* observations. FDG admitted 0 this session.
    The figure is a 1,300-label window that includes hive labels, and the best cells are `OUTLIER_FLAG_TAIL_8322`
    (median ≤ 0; "runnersAvg ≥ +5,000%").
  - `refused60 n568 −58.7% wr67% run41%` is internally impossible. A label is bounded below by −160% (−100% price,
    cost ≤ 60), so with 67% winners the mean cannot go below about −53%. The AGG|REFUSED tally (local + hive) is
    inconsistent and must not be used to judge gates.
- **Cortex:** every lane is UNCALIBRATED or has authority 0.
  - Rank IC is ≤ 0 on CORE, SHITCOIN, EXPRESS, CYCLIC, MANIPULATED, PROJECT_SNIPER, QUALITY and TREASURY.
  - TREASURY is INVERTED: STRONG n150 −14.3% against REFUSE n107 +113.0%.
  - The 8310 / 8366 guards bind at every entry use except one: the 100x door's odds leg (fixed, §4 C1).

## 1. The live buy path, in order (meme lanes, LIVE)

The 5.0.8416 counts are FDG block reasons and admission8136 rows. The admission counts include re-evaluations; they are
not unique coins.

| # | Gate (file:line) | Reads | 8416 refusals | Class |
|---|---|---|---|---|
| 0 | Discovery → intake (`BotService`, scanners, PumpPortal) → V3 lane scorers / native specialists (`SpecialistBrainBridge7542`, lane floors incl. 8320 curve rule, $500 non-curve exit floor) | scanner rows, curve / pool depth | V3 LOW_LIQUIDITY 1,548, LIQ_UNHYDRATED 864, SCORE_TOO_LOW 346; INTAKE PAIR_PENDING_HYDRATION 172; native NATIVE_MCAP_TOO_LOW etc. | SAFETY / lane identity |
| 0b | RunnerGrabLane8343 (`RunnerGrabLane8343.kt:228`) — re-asks a MANDATE-refused runner on its own legs | full safety, venue, forming run, 1.5x at grab size, 8329 chase cap, the hold's exception | admitted 435, refused 297 (VENUE 127, ECON 96, SAFETY 72) | the 100x door's front half |
| 1 | `EvidenceHold8336.refusal8336` + `EntryEconomics8329.laneRealisedRefusal8329` (`truth/LiveEdgeGate7877.kt:481`) | paper ALL closes since hold start, EvidenceShadow8344 proof, 8337/8345 exception, realised live closes | 2,077 FDG (QUALITY 479, BLUECHIP 444, EXPRESS 404, SHITCOIN 330, CASHGEN 226, TREASURY 194); 13k pre-intent MOONSHOT | EVIDENCE-HOLD (owner 8336) |
| 2 | dead launch `OnChainDepth8038.earlyLaunchRefusal8048` (`:548`) | curve reserves | CURVE_NOBODY_BOUGHT 154 (admission rows) | SAFETY |
| 3 | blind wallet `LiveBootRecovery8074.blind8074` (`:560`) | wallet read | 0 | SAFETY |
| 4 | `CandidateAdmission8136.decide8136` (`truth/CandidateAdmission8136.kt:373`): safety, structure, setup / cell / stage / tactic loss, SHEET_AVOID, timing, **EXECUTION_ECONOMICS**, data defers, liveLocal8325, generic floor, LIVE_GENERIC_PRIOR_UNSUPPORTED_8238 | label setup / cell / stage books, cost model at size, StrategyMove8153 move | ECONOMICS 2,680; DATA_INCOMPLETE 922 (+6,682 MOONSHOT pre-intent); FETCH_NEVER_COMPLETED 292; STRUCTURAL 134; STAGE 55; CELL 47 | mixed |
| 5 | `LiveTruth8064.setupRefusal8083` (`:623`) | realised live closes of lane+setup | 0 | EVIDENCE-HOLD (realised) |
| 6 | TIMING / PICKED / TACTIC 8094 (`:641/652/669`) | label setup records (12+, mean+SE<0) | not in the top 20 | LABEL (duplicate of 4) |
| 7 | `LIVE_PROVEN_SETUP_ONLY_8059` (`:699`) | proven setup / cell / kind / runner / cold-start exemptions | not in the top 20 | LABEL |
| 8 | `SelectionDoors8310.admitRefusal8310` (`:706`) | setup realised record, tail cell on a losing lane | 0 listed | LABEL |
| 9 | `CHASING_SPIKE_8065` `chaseRefusal8397` (`:718`, body `:938`) | TokenMetricStageRouter run-up / vs-peak / 5m | 702 | STRUCTURE |
| 10 | watch-first `watchFirst7994` (`:730`) | label cell / band | not in the top 20 | LABEL |
| 11 | core `liveRefusalCore7970` (`:1190`): Cortex entry refusal, rebuy / FALLING_KNIFE_8130 (103), NetEdge8030 (NET_EDGE_COIN_TOO_SLOW 126), lane fit (SCALP_TOO_FLAT 1,365; SWING_ALREADY_RAN 158), proven-losing named setup, chart, Mayhem, specialist, priors (PLAYBOOK_*, STAGE_7928, COST_7962), TradeShape `EDGE_7877_SHAPE` (102), cohort verdict `EDGE_7877_*` | mixed | as listed | mixed |
| 12 | niche / entry wait 8347/8351 (`:508–509`) | exploration shadow choice | NICHE_ONLY in earlier builds; none proven here | STRUCTURE |
| 13 | FDG `evaluate()` own blocks (owner-locked): CellProofLadder, FreshLaunchSelector, liquidity / freeze / rugcheck / zero-liq, base signal, legacy edge veto, sealed-cost requote / final-economics verdict | — | 0 reached | SAFETY / ECON |
| 14 | TradePlan7739 (`truth/TradePlan7739.kt:413–433`) hold + memoised liveRefusal; MinPromotion8188 / EntryEconomics8329 route-min promotion; LiveRiskPolicy7807; executor pre-broadcast re-quote and chase cap | — | 0 reached | ECON / SAFETY |

## 2. Redundant, contradictory, stale or proxy

| Finding | Evidence | Disposition |
|---|---|---|
| **Loss asked three times.** 4 (SETUP_RECORD_LOSING / TIMING_ADVERSE / TACTIC / CELL / STAGE), then 6 (8094 doors), then the 11 priors (PLAYBOOK / STAGE_7928). The 8094 doors use the thin test (mean+SE<0, n≥12) that 8257 / 8316 withdrew from 4. The 8201 stand-down after an ADMIT is earned only on mature positive *realised* closes (8298), which cannot happen at 1 live close. | Code. Not in the 8416 top 20, because 4 refuses or defers first. | Documented. Not changed: no measured impact in this log. `GateOutcome8418` will grade them. |
| **The 100x door re-asked by label doors.** A candidate the hold passed on its own exception (forming run, venue, odds, lane open; 3/h, 1/lane/30 min) then met 6, 7, 8, 10 and 11 again. These ask "does the lane's label record prove this?", which is the question the exception answers for runners. Runner cells are tail-dependent (median ≤ 0, `tail8303`), so mean-based label doors refuse them structurally. | 0 exceptions submitted in every report since 8337 (8344: 0 in 431 min; 8416: 0 in 223 min beside 435 live grab-lane admits). | **C3**: label doors are advisory for that candidate. |
| **Chase vs grab lane.** The grab lane admits a *forming* run, which is "up 80% off its low near its high" by definition. CHASING_SPIKE re-refused it unless it was also a proven-cell grab7967. 8117 (owner: "buy runners as they develop") exempts early grabs only. | 702 CHASING_SPIKE. | **C4**: grab-lane admits inside the 8117 early bound stand the chase rule down. |
| **Inverted Cortex in the 100x door.** The odds leg required Cortex STRONG with no inversion or authority check. On TREASURY, STRONG is the losing cohort. | TREASURY INVERTED; all lanes auth 0. | **C1**. |
| **Paper / shadow results lifting live doors** (review items): `laneWideProven8392` (paper OR shadow) lifted the live niche / timing vetoes; `SHADOW_ADVISORY_8393` demoted every live evidence door. | Code. | **R1 / R2**. |
| **Proxy / stale data:** cost model vs measured (8329 max), the `MCAP_UNKNOWN` / `LIQ_UNKNOWN` native refusals, `PRICE stale 16%` / `SAFETY stale 12%` provenance, $0 liquidity defers. | report provenance v11 | wt-econ-8418 (economics, move, data defers), int-8418-intake ($0 liq, HARD_RUG), wt-labels-8418 (marks). Not duplicated here. |
| **AGG\|REFUSED tally inconsistent** (mean below the bound its win rate allows). | §0 | Documented. Use `GateOutcome8418` instead. |

## 3. Refuses winners or losers? (evidence available in 8416)

The FDG gates have **no per-gate forward record** in this build (§0). What does exist:

- **What the canonical path admits.** EvidenceShadow8344 books every candidate the path admitted that the hold then
  refused, with real exits and costs.
  - MOONSHOT n69 +13.5% PF 1.67 and QUALITY n20 +38.5% PF 4.34.
  - SHITCOIN n22 −19.4%, EXPRESS n12 −14.1%, BLUECHIP n21 −7.1%, CASHGEN n11 −4.7% and TREASURY n6 −10.1%.
  - All are below the bar (mean−SE ≤ 2% or n small).
  - So the canonical path selects positively on MOONSHOT and QUALITY and negatively elsewhere. Small samples.
- **Native rule veto audit (Cortex vetoBook).**
  - Refusing losers: NATIVE_CORE_YIELD_CLEAR n161 −8.6%, NATIVE_PHASE_HANDOFF n72 −14.5%, CORE_OWN_ENSEMBLE n57
    −15.4% and CORTEX_SHADOW_REFUSE n117 −9.3%.
  - NATIVE_TREASURY_ROLE_FLOOR n176 +65.9% is `OUTLIER_SUSPECT` and not provable.
- **Cortex REFUSE cohorts.**
  - TREASURY REFUSE +113% (n107) and TREASURY\|REFUSE\|NO_TRIGGER n49 +143.5% PROVEN: the refused side wins.
  - Cortex refused 0 live (8310 shadowed). So the inversion is **not** steering live refusals or sizing. It was steering
    only the exception odds leg (C1).
- **Lane label books (`lanes60`, 5-minute slot):**

  | Lane | n | Net |
  |---|---|---|
  | DIP_HUNTER | 1253 | +36.7% |
  | PROJECT_SNIPER | 1146 | +41.6% |
  | CASHGEN | 1215 | +5.0% |
  | MOONSHOT | 1300 | −0.8% |
  | CYCLIC | 1190 | −1.1% |
  | CORE | — | −7.4% |
  | BLUECHIP | — | −9.0% |
  | QUALITY | — | −9.8% |
  | EXPRESS | — | −15.1% |
  | MANIPULATED | — | −19.7% |

  Most of these are tail-flagged.

**Ranking by "refuses winners" (provable today):**
1. The exception path's label re-checks (C3). The runner door never fired against 435 admits.
2. CHASING_SPIKE against forming runs (C4).
3. Everything else: not provable from this log.

`GateOutcome8418` produces the per-gate ranking from the next run on. Its report line is `Buy-path gates (§8418)`
(`gateOutcome8418[...]`), with the `FDG_ADMIT` cohort beside each gate, flagged `REFUSES_WINNERS` or `refuses_losers`.

## 4. 8114 against now: why it worked alone and flopped combined

- **8114 (379ad2fa0)** ran one body: dead launch → blind wallet → LIVE_SETUP_LOSING → timing / picked / tactic →
  PROVEN_ONLY with runner exemptions → chase (exempt for `runnerForming8113`) → watch-first → core.
  - There was no CandidateAdmission, no data defers, no hold, no SelectionDoors and no 8290 shortfall.
  - The cost model was about 3.7% per round trip, and the exits were the older ones.
- **8326 Entry8114Mode** ran that decision *with* the later cost model, stops, sizing and fills. Result: 1W / 7L,
  PF 0.08 (ENTRY_8114_MODE.md). The measured causes were not entry selection:
  - decision-to-fill chase of +13.5% (MOONSHOT), +10.7% (EXPRESS) and +6.1% (CASHGEN);
  - measured round trips of 14.8%, 19.1% and 8.4% against the 3.7% model;
  - 34 promotions of a ~0 Kelly stake to the route minimum;
  - the realised-loss bypass.
  - That points at the **interacting layers**: execution cost and chase at fill, route-minimum promotion, and stops.
  - The 8114 entry set was bought late and small, at a cost the label books never charged it.
- **8329** made those economics bind. **8336** then held every lane until paper or shadow proof.
- **8392** deleted the mode. Every post-8114 label door (§2 row 1) binds again on top of the hold, so even a hold pass
  meets five more label re-checks.
- Re-instating 8114 would bring back its runner-ticket chase exemption. 8113 bought 34fph3 at +1,238% on a ticket
  (8115 reverted it), and DIRECTIVE_LEDGER #10 (8337 FIX 6) removed it. So 8114 is not re-instated here.

## 5. What this build changes (each with behaviour tests)

| ID | Change | Where | Tests |
|---|---|---|---|
| C1 | An unvalidated or inverted Cortex ordering does not vote in the exception odds leg. `Cortex7885.orderingVotes8418` = 8366 authority granted AND not inverted. Otherwise the coin's early-run evidence decides (`CORTEX_UNVALIDATED_ABSTAINS_EARLY_RUN_8418`). | `cortex/Cortex7885.kt` (orderingVotes8418), `truth/EvidenceHold8336.kt` oddsLeg8337 / oddsLeg8418 | Aate8418BuyPathTest §1 |
| C2 | `GateOutcome8418`: the first buy-path gate (or FDG_ADMIT) is filed on the pending forward observation (≤ 120 s, meme lanes) and graded at 60 min. It is persisted and never reset. FdgGate8365's n≥100 veto audit now reads it as well as the Cortex veto book. It can demote evidence doors only. | `truth/GateOutcome8418.kt`, `truth/ForwardReturnLabeler7731.kt` (observe / book), `truth/FdgGate8365.kt` demoteNow8365, report via `FdgGate8365.evidenceAndGates8418` | §4 |
| C3 | A candidate the hold passed on its own exception carries `BASIS_EXCEPTION_8418`, so label / record doors are advisory for it (`FDG8365_DEMOTED_*_BY_EVIDENCE_HOLD_EXCEPTION_8418`). Still binding: safety, economics, data, chase, LIVE_SETUP_LOSING (realised), EDGE_7877 and `candidateEvidence8393`. Where those candidates end is counted in `exceptionFinal8418{}`. | `truth/FdgGate8365.kt`, `truth/EvidenceHold8336.kt` exceptionBasis8418, `truth/LiveEdgeGate7877.kt:480–489` | §2 |
| C4 | A live RunnerGrabLane8343 admit still inside the 8117 early bound (run-up < 150%, 5m < 100%) is not refused CHASING_SPIKE. | `truth/LiveEdgeGate7877.kt` chaseRefusal8397 / grabLaneEarly8418 | §3 |

### Review findings assigned to this audit (coordinator, review_a/b/c)

| ID | Finding | Verdict on evidence | Change |
|---|---|---|---|
| R1 | 17836cb9d `laneWideProven8392`: paper / shadow skip the LIVE niche veto and timing wait | Paper / shadow admitting live | Realised LIVE closes only (`laneWideLive8418`: 30+, mean > 2%). Pin superseded (Aate8392CortexCoverageTest). |
| R2 | b7e24dfc0 `SHADOW_ADVISORY_8393` hold basis | A shadow proof demoted live doors | Counted only (`SHADOW_ADVISORY_PROOF_NOT_A_LIVE_BASIS_8418_*`). |
| R3 | d4099d03d fast bar 15 / PF 1.2 | Operator quote not in DIRECTIVE_LEDGER_8367 | Retired: every lane uses 30 / 1.3. Pins superseded (Aate8336, Aate8401, Aate8345). |
| R4 | margin8391 can fall toward 1.0 | Owner's 1.5x (#28) | `adaptiveMargin8391 = max(EDGE_MULT, adaptive)`. Pin superseded (Aate8391). |
| R5 | 6ed51ecdb un-retires 12 patient setups with no hour evidence | Loosening with no data | The suspension needs the lane's hour book (30). Pins superseded (Aate8406, Aate8383). |
| R5b | 1c378bd92 retirement list frozen from one snapshot | Valid | **Open.** A way back needs the retired setups graded as their own cohort, which needs classifier and labeler work. Owner / learning agent. |
| R6 | 080676efc spares proven-losing refusals on hour lanes until 30 hour labels (one-sided) | Valid (QUALITY −13.7% x274) | `LaneGradeHorizon8406.refusalKept8418`: refusals keep binding and the would-be spare is counted. Pin superseded (Aate8408). The raise-only move read (wt-econ) is untouched. |
| R7 | 76565f9ca scalp test accepts a predicted net for the measured hour | Valid | wt-econ-8418 was cherry-picked and both scalpMoveTooSmall8054 and scalpWait8418 now read the measured move only. Pins superseded (Aate8417, Aate8418Economics). |
| R8 | 1c378bd92 `PLAYBOOK_OUTSIDE_ENVELOPE_8383` new live refusal | Restrictive; no evidence it refuses winners (34 refusals) | Kept binding (stay-quiet policy). GateOutcome8418 will grade it. |
| R9 | 1983e0bee RunnerTicket exempts the chase veto (lives in `chaseRefusal8397` since 8392 deleted Entry8114Gate) | Reverses DIRECTIVE_LEDGER #10 / 8337 FIX 6b | The ticket is no chase exemption (counted `CHASE_RUNNER_TICKET_NOT_EXEMPT_8418_*`). Early grab and grab-lane (C4) remain. Pin superseded (Aate8397). The watch-first ticket admits (pre-8326 7877 behaviour) are left as they are. |
| R10 | 0c799f0f0 four "clearly losing" candidate refusals | 8114 mode is gone. They are `candidateEvidence8393` and bind live, which is restrictive. | No change. Noted. |
| R11 | 03a800b4e edited `FinalDecisionGate.evaluate()` (final-economics verdict in the return block) | Rule violation; stricter in effect | **Not edited further.** Owner exception or refactor needed. |

## 6. Hard safety: unchanged

Rug, freeze / mint authority, Token-2022, sellability, Mayhem, the liquidity exit floors (8320 curve rule, $500 non-curve),
identity, the wallet / route / pre-broadcast proofs, economics at 1.5x (now a floor), the data defers and
`FinalDecisionGate.evaluate()` are all unchanged.

- No paper or shadow result sizes or admits a live trade through anything this build adds. R1 and R2 remove two
  existing paths.
- Nothing is fabricated: GateOutcome8418 holds only real graded labels, and a gate with none has no record.

## 7. Still open / owner decisions

- **The hold itself unlocks a lane on PAPER or SHADOW proof** (8336 / 8344, owner-set). By the standing rule that is a
  paper/shadow result admitting live, so the owner should rule on it. The PAPER_ALL / EXPLORATION hold bases (8365 D1)
  are the same kind of path.
- **R5b**: data-driven un-retirement of 8383 setups.
- **R11**: the evaluate() edit.
- **The TREASURY native role floor** (NATIVE_TREASURY_ROLE_FLOOR n176 +65.9%, outlier-suspect) and the inverted
  TREASURY Cortex. These are lane / learning owners' items.
- **Cannot be proven without a device:**
  - whether C3 / C4 turn grab-lane admits into fills (watch `exceptionFinal8418{}` and
    `CHASE_STOOD_DOWN_GRAB_LANE_EARLY_8418_*`);
  - the first `gateOutcome8418` ranking, which needs about 1 h of labels per gate and 100 refusals for any demotion;
  - fill quality and cost at the route minimum.
