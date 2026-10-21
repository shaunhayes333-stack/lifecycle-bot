# AUDIT 8298 — profitability regression: when and what changed

Owner: "it's gone from a near money printer — doubling the wallet, growing confidence, building bigger trades — to a bot that just buys losers."

## 1. The profitable era (evidence)

**Best-supported answer: builds 5.0.8113–8114 (source `379ad2fa0`, 15 Oct 2026, Brisbane Thursday night).** The window can stretch back to 8094 at most.

- `lifecycle_apk/audits/repair8198/8114-comparison.md`, line 3: "Operator reports approximately 40% wins and doubled cash on Thursday night, 15 October 2026 (Brisbane), around builds 8113–8114." The same audit says 8113 `361727241` and 8114 `379ad2fa0` have identical trading source. It then records "Operator narrowed historical baseline … builds 8113–8114 … This is now the comparison baseline."
- Commit `51b3d88a4` (5.0.8095, 15 Oct): "owner: drawdown stopped the balance growing". Proven-edge sizes were growing at this point. 8057 (`b9d6f7572`) quarter-Kelly compounding was live, and 8094 (`2be27bad4`) drawdown waiver for proven edges was live.
- Contrast: by 5.0.8168 the realised book was WR 20% / PF 0.34 (commit `b7b198ba8`). 8237 live losses were all generic-prior admits (`787837867`). 8261 was 18.2% WR, PF 0.67 (`1b9ec5bae`). 8286 was 4/4 losses, all route-minimum size and UNPROVEN.
- Caveat: the 8113/8114 result is what the operator reported. No realised ledger rows from that session are in the repo.

## 2. What the 8114 decision path was

Live entries went through `LiveEdgeGate7877.liveRefusal`. A coin needed one of these to pass the proven-only door (`LIVE_PROVEN_SETUP_ONLY_8059`):
- a proven setup (30+ labels, mean − SE > 0);
- a measured cell or kind edge;
- a runner ticket;
- `runnerForming8113` or `shadowAuditAdmits8113`;
- a bounded cold start (3 coins per 6 h).

Even with a proven setup, all of these still bound:
- the timing-negative record (exempt only for a proven cell/kind);
- the chase-spike rule;
- `TradeShapeLearner7883` lane shape bins;
- `NetEdge8030` at 2× the round trip;
- the lane priors (playbook, stage, cost);
- watch-first;
- the FDG cell proof ladder;
- the fresh-launch stops-first prior.

Loss backstops:
- fresh meme hard floor −9% in the first 5 minutes;
- hard floor −15% otherwise;
- MOONSHOT −15% for the first 12 minutes.

Sizing: label-proven coins were sized at quarter-Kelly of equity (8057), and the drawdown shrink was waived for proven edges (8094/8095). That is the "bigger trades, growing confidence" the owner saw.

## 3. Ranked regressing commits (worst first)

| # | Commit(s) | file:line (current HEAD 7c147e519) | Mechanism | Class |
|---|---|---|---|---|
| 1 | `32b96d3dd` 8136, `e404c5126` 8161, `8fde11672` 8201 | `LiveEdgeGate7877.kt:542-585` (`admittedJudged8201`), `:1101-1110` (priors cleared by admission) | A *candidate-specific ADMIT* from `CandidateAdmission8136` stands down the 8114 timing, picked-setup and tactic negatives and the lane priors. "Specific" rests on forward-**label** setup records (8286: LAUNCH_LADDER_PROVEN n824 +83.5%, COPYCAT_BETA +86%) while realised = all losses, and admitted60 = +1.8% (below a ~6% round trip). | loosened entry into losers |
| 2 | `89ecd5788` 8243 | `LiveEdgeGate7877.kt:614-631` (chase), `FinalDecisionGate.kt:1054` (`freshLaunchBlock7737`) | A specific ADMIT stands down the spike-chase rule and the fresh-launch stops-first prior. 8286: fresh-launch EXPANDING setups stop first 67–83% of the time. SHITCOIN BREAKOUT_RUNNER −33.8%. | loosened entry |
| 3 | `a845415d1` 8256 | `LiveEdgeGate7877.kt:1145` (shape), `:1002-1008` (watch-first negative cell) | Lane shape bins and a measured-negative watch cell yield to a specific ADMIT. 8286 trade-shape rules: AGE_MIN<3, LIQ<3k, MCAP<10k strongly negative per lane. | loosened entry |
| 4 | `b7b198ba8` 8169(exits) | `ExitDoctrine8169.kt:237-274` | Backstops widened: fresh meme 9% → ordinary stop (up to 25%); hard floor 15% → stop + 5 (up to 30%); MOONSHOT first 12 min 15% → up to 25%; predictive tightening clamped. The justification came from forward-label counts ("stoppedBeforePeak"). 8286 losses: −33.8%, −16.7%. | losers made bigger |
| 5 | `ec153f87b` 8188, `2bc4d6cef`/`f98797d03` 8136 | `LiveRiskPolicy7807.kt:212` (`validatedKellyFrac8188`), `:610-615` (`drawdownWaiver8136`) | Label Kelly is capped by a realised or walk-forward proof stake (0 for every lane today). The drawdown waiver needs a mature realised record. Result: `edgeKellyFrac8057 = 0` → ProofTier UNPROVEN → every order at the route minimum (~0.028 SOL), with no `PROVEN_EDGE_COMPOUNDING_SIZE_8057` and no compounding ceiling. | cut size growth (see §5) |
| 6 | `19944ede1` 8240, `275f8babd` 8180 (StageCarry), `b37a9f452` 8221, `914d617a6` 8257 | `CandidateAdmission8136.kt:330-395` | Inside admission: a losing cell is "carried" by a supported setup. A losing stage is carried by a merely VALID carry. A FRESH_LAUNCH learned refusal falls through. A thin losing setup or timing record (12+ labels, mean + SE < 0) is advisory. Each one turned an 8114 refusal into an ADMIT path. | loosened entry (admission agent's area) |
| 7 | `d80140604` 8200, `7ffc6ea8f` 8246, `1d6f7cbcf` 8260 | `CandidateAdmission8136.kt:398-401` | A Cortex STRONG cohort, a runner-grab cell, and an unlearned forming runner count as candidate-specific edge. That sets `specific = true`, which triggers rows 1–3. | loosened entry |
| 8 | `42b071d67` 8197 | `NetEdge8030` EDGE_MULT | Entry economics at 1.5× the round trip (was 2×). This was the **owner's choice**, so it is not reverted. With a ~6% route-minimum round trip, the margin is thin. | loosened entry |
| 9 | `ba8a10501` 8211, `04f0e6ea4` 8213, `4ed9a67f0` 8259 | `LiveEdgeGate7877.kt:66-73, 1046` | A generic or native admit cleared the proven-only door and NetEdge. Already mostly undone: 8238 (`787837867`) refuses live generic priors without a supported cell, and 8279 (`1b9ec5bae`) makes the native admit non-specific again. | loosened entry (largely repaired) |
| 10 | Learning feed: 8187 executable labels / LABEL_LOST_GAP, 8185 robust read | `ForwardReturnLabeler7731.kt:1540-1620`, Cortex7885 | The stored label aggregates are outlier-poisoned (kinds +107..+330% OUTLIER_SUSPECT). Cortex graded 710 and lost 701 (LABEL_LOST_GAP_8187=657). The evidence every stand-down rested on is not executable outcome. 8185/8187/8281 already read these robustly. | poisoned evidence (Cortex / predictive agents) |

## 4. What this branch changes (V5.0.8298)

New object `engine/truth/RegressionRestore8298.kt`. It has one honest rule: **the 8201–8256 stand-downs and the 8169 widened backstops apply only to a lane whose realised whole-position LIVE closes are mature and positive.** The test is `LiveSizingTruth8136.edgeMature8136`: 10+ clean closes, mean − SE > 0, net SOL > 0, read from the canonical live bus only. Until a lane meets it, the 8114 doors and backstops bind for that lane:

- `LiveEdgeGate7877`:
  - `admittedJudged8201` now also needs the earned record. Timing, picked-setup and tactic negatives bind as in 8114, with the proven8059 and cellOrKindProven exemptions kept.
  - The chase rule refuses an unearned admission and names its terminal.
  - Shape bins refuse (`shapeYields8298`).
  - A measured-negative watch cell refuses.
- `FinalDecisionGate`: only the helpers `cellProofBlock7731` and `freshLaunchBlock7737` changed. `evaluate()` is untouched. The cell proof ladder and the fresh-launch stops-first prior bind for an unearned admission.
- `ExitDoctrine8169`:
  - rapid and risk hard floor 15%;
  - fresh meme 9%;
  - MOONSHOT first 12 min 15%;
  - all until earned. The call sites are unchanged.
- Counters: `REGRESSION_RESTORE_8298_<door>_<lane>` labels, plus `restore8298[...]` on the LiveEdgeGate status line.
- Tests: `Aate8298RegressionRestoreTest`. `Aate8256GenericPriorYieldsTest` was updated to the earned yield.

Not touched:
- hard safety (rug, freeze/mint, Token-2022, sellability, liquidity, identity, execution proof);
- admission internals;
- learning stores (nothing reset);
- sizing caps;
- the owner's 1.5× economics.

## 5. Growth target (2x–5x daily)

- **Still wired and unchanged since 8114:**
  - `AutoCompoundEngine` (no commits since `379ad2fa0`; `processWin` is called from Executor);
  - the 8057 quarter-Kelly path (`LiveRiskPolicy7807.edgeKellySizeSol8057`);
  - the `LiveSizeTarget8094` PROVEN ceiling;
  - the 8057 daily 2×/5× scoreboard (report only).
- **Where it stopped feeding size:**
  - `ec153f87b` (8188, 17 Oct) made `edgeKellyFracFor8057` return `min(labelKelly, realised-or-walk-forward proof Kelly)`. No lane has a mature realised record, so it returns 0. Every candidate becomes ProofTier UNPROVEN, and `decide()` clamps it to the upstream proposal and then promotes it only to the route minimum. That matches 8286: "All entries ROUTE_MIN ~0.028 SOL, UNPROVEN".
  - `2bc4d6cef`/`f98797d03` (8136, 15 Oct) put the drawdown shrink back on unless the realised record is mature. This partly undid the owner's 8095 fix.
- **Judgement:** both gates source aggression from on-chain realised wins, which is what the mandate asks for. The 8114 compounding was sized off forward labels, and those labels are now shown to be outlier-inflated (they read +83..+330% while realised is −100% WR). Reverting 8188/8136 would put compounding behind losing entries, which the mandate forbids. **The growth path is not broken in code; it is starved because entries lose.** The fix is to restore entry selectivity (§4). Once a lane books 10+ clean, net-positive live closes, three things switch back on together for that lane: Kelly compounding, the drawdown waiver, and (through RegressionRestore8298) the wider stand-downs.
- One residual throttle for the sizing agent to review: `validatedKellyFrac8188` takes the *minimum* of the label stake and the realised stake. A coin with a strong realised lane record but no label-proven setup/cell/kind gets no Kelly at all. Lane realised proof alone could carry a bounded stake. That is a policy choice, not a regression, so it was not changed here.

## 6. For the other agents

- **Admission / predictive:** rows 6–7 are inside `CandidateAdmission8136`. Every `specific = true` path built on label records (setup tail rule `n>=100 && mean>bar && runner>=10%`, cell carry, VALID stage carry, Cortex STRONG, runner grab) should be judged on an executable/realised basis. The LiveEdgeGate and FDG stand-downs now wait for realised proof anyway.
- **Exits:** `ExitDoctrine8169` backstops are 8114 values again until a lane earns them. If you change the stop-width doctrine, keep `restored8298(...)`. Stop latency (EMRG t2b ~10 s) is separate and still the dominant risk at the 15% floor.
- **Sizing:** see §5. Do not raise size from labels or paper.
- **Cortex:** LABEL_LOST_GAP and label inflation are why the labels cannot be trusted. Until realised books mature, no label-based authority should stand down a refusal.
- **Expected runtime effect:** fewer live buys while every lane's realised record is immature. The `REGRESSION_RESTORE_8298_*` counters name every door that held, and they show if trade count starves. If it does, the right lever is better candidates (scanner, hunters, data), not reopening these doors.
