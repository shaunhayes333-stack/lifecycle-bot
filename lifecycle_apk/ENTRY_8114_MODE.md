# ENTRY 8114 MODE (V5.0.8326)

The owner decided: "Make 8114 live now." Builds 8113 and 8114 (`361727241` / `379ad2fa0`, 15 Oct) are the last builds where the owner saw the bot make money: about 40 % wins, and cash doubled that night. Their trading source is identical.

`Entry8114Mode8326` defaults to ON. It is persisted as `entry_8114_mode_enabled_8326` and shown in Settings as "Entry 8114 mode". While it is ON, the **live entry decision** for the meme lanes below is the 8114 decision. The **size** of each 8114-admitted live trade also follows the 8114 size path.

Lanes covered: SHITCOIN, MOONSHOT, EXPRESS, MANIPULATED, PROJECT_SNIPER, QUALITY, BLUECHIP, DIP_HUNTER, TREASURY, CASHGEN, CYCLIC, CORE.

Exits, stops and routing are unchanged. The mode keeps the current machinery, including the faster emergency sells and the 8298 loss floors.

## How it works

| Piece | File | What it does |
|---|---|---|
| Switch | `LiveEdgeGate7877.liveRefusal` (one line) | Hands a live, covered-lane entry to `Entry8114Mode8326.decide8326`. |
| Shadow verdict | `Entry8114Mode8326.decide8326` | Computes the **current** full verdict first, inside `inCurrent8326`. This includes CandidateAdmission8136, the data defers, and the 8290/8298/8310 doors. It files its own assessment. |
| 8114 verdict | `Entry8114Gate8326.liveVerdict8114` | Runs the 379ad2fa0 composition in its exact order, inside `inLegacy8326`. |
| Legacy scope | one-line hooks | While the scope is set, the evidence reads that changed since 8114 answer the way they did at 8114 (see table, rows marked *legacy hook*). |
| Final | `finalVerdict8326` | The 8114 verdict decides. The current verdict binds only when it is a hard-safety or economic refusal (`hardSafety8326`). The final assessment is filed last, so FDG, the trade plan and sizing all read it. |
| FDG helpers | `cellProofBlock7731`, `freshLaunchBlock7737`, `playbookClearsFloor7937`, `baseSignalVerdict8203`, `legacyEdgeVetoVerdict8207` | In mode they read no admission (`admission8326` returns null), because 8114 had none. `evaluate()` itself is untouched. |
| Trade plan | `TradePlan7739.liveBlockReason` | A chart BUY is planned at once, as at 8114. The 8171 admission plan is off. |
| Sizing | `LiveRiskPolicy7807`, `MinPromotion8188`, `OrderSizeResolver6441`, `SmartSizerV3` | An 8114-admitted candidate is sized the 8114 way (see the sizing table). |
| Counters | `ENTRY8114_BYPASSED_<gate>[_<lane>]` | One count each time a post-8114 refusal did not refuse. Also counted: `ENTRY8114_ADMIT/REFUSE_8326_<lane>`, `ENTRY8114_FDG_*`, `ENTRY8114_ONLY_OLD/NEW_ADMITS_8326_<lane>`. |
| Report | Trade plans line (`TradePlan7739.statusLine`) | Shows `entry8114[on=… bypassed=… fdg=… size8114[…] compound=…x day=… multiple=…x shadow8114[…]]`. |

### What always binds, whatever the mode

These are safety and correctness checks, not selection:

- Hard rug, authority, freeze, Token-2022, sellability and honeypot checks; the safety tier's HARD_BLOCK; `SAFETY_REFUSED_8136`; `STRUCTURAL_RISK_8136` (rug-prone stage, structural launch refusal).
- Mayhem and the blacklist.
- Liquidity exit-safety floors, including the 8320 curve rule. These live in the lane scorers and PreTradeHardGate and are not touched.
- The dead launch check (`AT_LAUNCH_CAP_8047`).
- Identity and price-basis proofs; the blind wallet check (`WALLET_BLIND_8074`).
- The route minimum and the wallet/balance proofs; the tradeable-cash cap; the fixed-cost floor.
- SignedTxLedger, single-flight and duplicate-order protection, and every Executor pre-broadcast check.
- The kill switch and the drawdown HALT.
- **Economics at the owner's 1.5x:** `NetEdge8030.entryRefusal8030` runs inside the 8114 core; `EXECUTION_ECONOMICS_8136`; `ADMISSION_COST_REQUOTE_FAILED_8209`; `NET_EV_NONPOSITIVE_AT_SIZE_8153`; `ROUTE_MIN_PROMOTION_REFUSED_NEGATIVE_EDGE_8188`.
- If the 8114 verdict cannot be computed, the entry fails closed (`ENTRY8114_EVAL_FAILED_8326`).

### What binds in mode since V5.0.8329 (provable economics, not labels)

8326 ran live in mode: 1W/7L, PF 0.08; MOONSHOT four consecutive losing live closes while "lane realised losing" was bypassed 20 times; decision-to-fill chase MOONSHOT +13.5 % / EXPRESS +10.7 % / CASHGEN +6.1 %; measured round trips 14.8 % / 19.1 % / 8.4 % against a 3.7 % model; 34 promotions of a ~0 Kelly stake to the route minimum. The mode stays ON. These four reads are candidate-specific and provable from the chain or the executable quote, so they now bind inside it (`EntryEconomics8329`):

| Read | Where | Rule | Refusal / count |
|---|---|---|---|
| Lane realised losing | `decide8326` → `finalVerdict8326(old, now, realised)` | the lane's OWN live closes, 3+, mean < 0 (`LaneRealisedGovernor8295.tailRefuted8295` on `Cortex7885.laneRealised8295`). Re-opens when its next live close after the shut is a win or the mean of its last 5 closes is ≥ 0; after a re-open it re-arms only on a NEW losing close. | `ENTRY8114_LANE_REALISED_LOSING_BINDS_8329_<lane>`, `ENTRY8114_LANE_REOPENED_8329_<lane>`; report `laneShut8329[lane: since, closes, mean, binds]` |
| Pre-broadcast re-quote / chase | `Executor.prepareJupiterBuy8090` (the Jupiter quote the buy is built from, after the SecurityGuard quote check, before the swap is built) and `liveBuy` before the PumpPortal send (curve mark; that route has no quote) | move % = quote-implied price vs the admitted decision price (`ForwardReturnLabeler7731.decisionPx8094`, ≤ 10 min old), net of the quote's own impact. Cap by tactic: LAUNCH_EARLY / BASE_BREAKOUT / BREAKOUT 8 %, PULLBACK_RECLAIM / SWEEP_RECLAIM / PULLBACK / REACCUMULATION 2.5 %, MOMENTUM 5 %, else 4 %, an exhausted rally (`chasing8065` at fill) none. The move left (expected gross − move) must still clear 1.5× the round trip at this size (`preTradeCostPct8329`). | `ENTRY_CHASE_CONSUMED_8329_<lane>[_OVER_CAP/_EDGE_CONSUMED/_EXHAUSTED_RALLY]`; a refused forward label under `CHASEMISS_<lane>` grades it against what happened next; `ENTRY_CHASE_REARMED_HL_RECLAIM_8329_<lane>` when a higher-low reclaim re-arms the coin; report `chase8329[lane: checked/consumed/medianMove]`. EntryChase7961's 8-fill learned penalty is unchanged. |
| Route-minimum promotion reason | `MinPromotion8188.runtimeRefusal8188` (upstream sizers) and `LiveRiskPolicy7807.decide` (last mile), after the 8188/8326 filters | one named reason: the selected setup's own record positive on 12+ labels (`LanePlaybook7907.selectedRecord8153`), an early runner (LAUNCH_EARLY plan inside the launch window; the 8320 curve rule still binds in PreTradeHardGate), or Kelly ≥ 25 % of the minimum. The viable route minimum itself is unchanged. | `ROUTE_MIN_PROMOTION_UNSUPPORTED_8329_<lane>`, `ROUTE_MIN_PROMOTION_REASON_8329_<reason>`; report `promo8329[reason=count]` |
| Cost truth | `NetEdge8030.preTradeCostPct8329`: `entryRefusal8030` (the 8114 core), both promotion costings, `LiveRiskPolicy7807` cost at size | max(model, the lane's measured round-trip median once 2+ live trips exist — `CostLedger7962.measuredMedianPct8329`). Exits, labels and plan cards keep the 8-trade rule; wins are never capped. | `COST_TRUTH_MEASURED_LIFTS_MODEL_8329_<lane>` |

Still bypassed in mode, per the owner's choice: the label reads (`LIVE_LANE_LABELS_NOT_POSITIVE_8325`, proven-setup-only, cheat-sheet avoid, cell / stage / setup / tactic label records, data defers, selection doors).

The report line now ends `… shadow8114[…] chase8329[…] promo8329[…] laneShut8329[…]]`. Tests: `Aate8329EntryEconomicsTest`.

## Entry gate inventory (live meme buy, today)

Classes:
- **A** — existed at 8114 with the same semantics.
- **B** — existed at 8114 but changed since.
- **C** — added after 8114.

| # | Gate / refusal | Where | Class | Changed by | Mode ON |
|---|---|---|---|---|---|
| 1 | Hard safety: rug, freeze, mint, Token-2022, sellability, honeypot, HARD_BLOCK | FDG evaluate, TokenSafetyChecker, PreTradeHardGate | A | — | binding |
| 2 | Freeze proof UNKNOWN is pending, not a pass | FDG `SAFETY_FREEZE_PROOF_PENDING_DEFER_8271` | C | 8271 | binding (safety) |
| 3 | Lane native qualification and lane liquidity floors, incl. curve exit-safe | v3 scorers, LaneAgeLiquidityTruth8140 | B | 8140, 8320 | unchanged (safety) |
| 4 | Dead launch / launch cap | `OnChainDepth8038.earlyLaunchRefusal8048` | B | 8252 (3-state buyers) | current helper (safety) |
| 5 | Blind wallet | `LiveBootRecovery8074.blind8074` | B | 8268 (failure streak) | current helper (wallet proof) |
| 6 | Live-close setup refusal | `LiveTruth8064.setupRefusal8083` | A | — | unchanged |
| 7 | Timing record negative | `LanePlaybook7907.timingNegative8094` | B | 8201/8298 admission stand-down, 8324 tail credit | 8114 order, no admission; tail credit residual (being reverted in wt-fix-8325) |
| 8 | Picked-setup record negative | `pickedSetupNegative8094` | B | 8144 valid alternative, 8324 | **legacy hook**: no alternative |
| 9 | Tactic record losing | `TacticSwitcher.losingTacticFor8094` | B | 8164 (n≥12), 8153 selected setup | current helper (residual) |
| 10 | Proven-only door `LIVE_PROVEN_SETUP_ONLY_8059` | LiveEdgeGate | B | 8136 data defer, 8161 admission clears, 8211/8213 generic native, 8290 proof | **8114 port** |
| 11 | Runner ticket admits | `RunnerTicket8073.admits8073` | B | 8310 record suspension | **legacy hook**: no suspension |
| 12 | Bounded cold start (3 coins / 6 h) | LiveEdgeGate | A | — | ported (same budget) |
| 13 | Spike chase `CHASING_SPIKE_8065` | LiveEdgeGate | B | 8115 dropped the forming-run exemption, 8117, 8243, 8298, 8310 D4 | **8114 port** (forming run / tail rule / proven cell stand down) |
| 14 | Watch first (thin / negative cell, negative band) | LiveEdgeGate | B | 8153 release, 8256, 8290 shortfall/suspect | **8114 port** |
| 15 | Cortex earned refusal (C3/C6, timing) | `Cortex7885.entryRefusal` | B | 8269 held-out proof, 8279 either proof, 8310 inverted stand-down | **`Cortex7885.entryRefusal8114`** (constitution rules current, residual) |
| 16 | Rebuy lockout / repeat loser | `RebuyLockout8019.refusal8019` | B | 8130 falling knife, big loser 24 h | **legacy hook**: no 8130 locks |
| 17 | Net edge entry economics | `NetEdge8030.entryRefusal8030` | B | 8197 1.5x (owner), 8151 cost model, 8153 executable move and sparse-history defer | binding (economics); the 8153 defer does not refuse (counted) |
| 18 | Lane fit (scalp too flat / swing already ran) | `NetEdge8030.laneFitRefusal8054` | B | 8151 ticket size | current helper |
| 19 | Proven-losing setup, no side door | `provenLosingSetupRefusal8079` | B | 8153 setup identity | 8114 selection (legacy hook) |
| 20 | Chart reader BUY admits | `chartAdmits7950` | A | — | unchanged |
| 21 | Mayhem | `MayhemMode7943.liveRefusal` | A | — | binding |
| 22 | Promoted specialist admits | `SpecialistMiner7972.match7972` | A | — | unchanged |
| 23 | Playbook prior (setup proven losing / expected negative) | `LanePlaybook7907.liveRefusal` | B | 8153 strategyFor, 8295 runner tail | **legacy hook**: classified tag, 8114 runner tail |
| 24 | Stage prior | `TokenMetricStageRouter.liveStageRefusal7928` | A | — | unchanged |
| 25 | Measured cost above gross | `CostLedger7962.liveRefusal7962` | B | 8153 label-charged cost | current helper (economics) |
| 26 | Lane shape bins | `TradeShapeLearner7883.shapeRefusal` | A | 8256/8298 (admission stand-down only) | 8114 port (binds) |
| 27 | Cohort / cell verdict | `LiveEdgeGate7877.verdictFor` | B | 8290 suspect, selectedSetupSurvives | legacy scope (raw cells, 8114 proof) |
| 28 | Cell evidence read | `ForwardReturnLabeler7731.cellStat` / `executable8187` | B | 8185 robust, 8187 executable, 8322/8324 tail | **legacy hook**: raw mean, population SE, never suspect |
| 29 | Setup record read | `LanePlaybook7907.stat` | B | 8187 executable, 8295 realised governor | **legacy hook**: stored record |
| 30 | Setup selection | `selectedSetup8094` | B | 8153 admitted/ranked, 8144, 8323 rerank | **legacy hook**: proven setup, else classified (classifier rerank residual) |
| 31 | Live proof survives | `LiveAdmissionProof8290.survives8290` / `laneExtra8290` | C | 8290 | **legacy hook**: mean − SE > 0, no shortfall |
| 32 | CandidateAdmission8136 specific refusals: CELL/STAGE/SETUP/TACTIC losing, TIMING_ADVERSE, SHEET_AVOID_8323, NO_SUPPORTED_EDGE, GENERIC_SCORE_FLOOR, LIVE_GENERIC_PRIOR_UNSUPPORTED_8238 | CandidateAdmission8136 / LiveEdgeGate | C | 8136, 8161, 8238, 8240, 8257, 8323 | **bypassed** (counted) |
| 33 | CandidateAdmission8136 SAFETY_REFUSED / STRUCTURAL_RISK | CandidateAdmission8136 | C | 8136 | binding (safety) |
| 34 | CandidateAdmission8136 EXECUTION_ECONOMICS | CandidateAdmission8136 | C | 8136, 8197 | binding (economics 1.5x) |
| 35 | Data defers: DATA_INCOMPLETE_DEFER, budget exhausted, fetch never completed, runner defer | LiveEdgeGate, RunnerDefer8297 | C | 8136, 8144, 8204, 8229, 8297 | **bypassed** |
| 36 | Selection doors: setup live record, runner ticket record, tail cell, chase label stand-down | SelectionDoors8310 | C | 8310 | **bypassed** |
| 37 | Regression restore doors on admissions | RegressionRestore8298 | C | 8298 | **bypassed** (8114 doors bind through the port instead) |
| 38 | Realised lane governor of setup records | LaneRealisedGovernor8295 | C | 8295 | **legacy hook** (`stat`, `runnerTail8295`) |
| 39 | Native / generic admits (8211, 8213, 8259, 8279) | LiveEdgeGate, CandidateAdmission8136 | C | 8211–8279 | not consulted |
| 40 | FDG generic floor answered by the admission | `floorFromAdmission8136` | C | 8136 | skipped (the 8114 bypasses decide) |
| 41 | FDG cell proof ladder, admission stand-down | `cellProofBlock7731` | C | 8244 | no admission: binds as 8114 |
| 42 | FDG fresh-launch stops-first, admission stand-down | `freshLaunchBlock7737` | C | 8243 | no admission: binds as 8114 |
| 43 | FDG base signal superseded by admission | `baseSignalVerdict8203` | C | 8203, 8210 | no admission: 8114 rule |
| 44 | FDG legacy edge veto superseded by admission | `legacyEdgeVetoVerdict8207` | C | 8207 | no admission: veto refuses as 8114 |
| 45 | FDG sealed-size cost requote | `sealedCostHolds8209` | C | 8209 | binding (economics) |
| 46 | FDG council / plan / manual dissent | `specialistAdvisoryBlock7845` | A | 8244 → 8279 restored 8114 | unchanged |
| 47 | Cell proof ladder internals | `CellProofLadder7731.liveBlockReason` | B | 8180 audit retirement, 8185 POSITIVE tier | current helper (residual) |
| 48 | Fresh-launch selector internals | `FreshLaunchSelector7737.cellRefuses` | B | 8266 significant stops | current helper (residual) |
| 49 | Trade plan for a chart BUY | `TradePlan7739.chartPlanRefusal8136` | C | 8136 | 8114: planned at once |
| 50 | Trade plan from admission | `TradePlan7739` 8171 | C | 8171 | off |
| 51 | Route-minimum promotion refusals (loss budget, drawdown, unmeasured edge) | MinPromotion8188 | C | 8188, 8236, 8259, 8316, 8319 | **bypassed** for 8114-admitted; NEGATIVE_EDGE binds |
| 52 | Promotion envelope (tradeable SOL, lane room, cost at minimum) | SizingNetEdge8153 | C | 8153 | binding (wallet / economics) |
| 53 | Pre-seal dead / flat chart | `TicketExec8304` / FlatChart8027 | A | 8304 moved it earlier | unchanged |
| 54 | Executor pre-broadcast: quote, route, SignedTxLedger, single flight, wallet | Executor | A/C | many | binding |
| 55 | Lane owner election (not a refusal) | StrategyElection8243 | C | 8243 | unchanged (chooses the owner, does not refuse) |
| 56 | Kill switch / drawdown HALT | KillSwitch | A | — | binding |

**Counts: A 12, B 23, C 21** (56 rows).
- Of the 23 B rows: 14 run on 8114 semantics (port or legacy hook); 9 keep the current helper (safety, economics, or a named residual).
- Of the 21 C rows: 15 are reverted to 8114 behaviour (bypassed, not consulted, or the FDG helper reads no admission); 6 stay binding as safety/economics or are not refusals.

### Residuals (honest limits)

- **Setup rerank (8323).** The classifier's cached ranking (`rerank8084` → `LaneCheatSheet8323.choose8323`) is shared by both verdicts. The *selected* setup in the legacy scope is the 8114 one (proven setup, else classified tag), but the classified tag itself may have been reranked.
- **Tail credit (8324).** `tailCredit8324` on the timing and picked-setup loss tests is not hooked. wt-fix-8325 is reverting it.
- **Current helpers kept.** TacticSwitcher min-N, CellProofLadder 8180/8185, FreshLaunchSelector 8266, CostLedger 8153, laneFit cost ticket, and the Cortex constitution rules (C3 stand-downs) keep their current bodies.
- **Raw label reads.** The legacy scope reads raw label records, which 8185/8187 showed can be outlier-inflated. The shadow report is how to see whether this picks winners.
- **Shadow scope.** The shadow "current verdict" is LiveEdgeGate7877's current decision. FDG helper differences are counted separately (`ENTRY8114_FDG_*`).

## Sizing inventory (8114-admitted live trades)

| # | Size step | Where | Class | Changed by | Mode ON (8114-admitted) |
|---|---|---|---|---|---|
| S1 | Quarter-Kelly share of equity on the best proven setup / cell / kind | `LiveRiskPolicy7807.edgeKellyFracFor8057` | B | 8188 realised / walk-forward proof cap, 8296 recent-loss revoke, 8187/8290 reads | **8114**: label quarter-Kelly computed in the legacy scope (`kelly8114Or8326`) |
| S2 | Drawdown shrink, waived for a proven edge | `decide` / `drawdownWaiver8136` | B | 8136 needs a mature realised record | **8094 waiver** (`drawdownWaivedForProvenEdge8094`) |
| S3 | Live expectancy multiplier | `expectancyMultiplier` | B | 8296 recency shrink | **8114**: no recency shrink |
| S4 | Kelly target may exceed the upstream proposal, up to its ceiling | `LiveSizeTarget8094` | A | 8269 (hold approved size) | unchanged |
| S5 | Lane position cap, liquidity impact cap, hard per-trade loss at the stop, lane slot cap | `decide` | A | — | binding |
| S6 | Tradeable cash cap (wallet − reserve − sealed reservations) | `decide` 8153 | C | 8153 | binding (wallet reserve) |
| S7 | Lane portfolio envelope (slot share of equity) | `SizingNetEdge8153.laneEnvelopeRoomSol8153` | C | 8153 | **off** |
| S8 | Kelly re-netted at the size's round trip; NET_EV at size refuses | SizingNetEdge8153 | C | 8153, 8232 | binding (economics) |
| S9 | Fixed-cost executable floor | `executableFloorWithFixedCost8269` | C | 8269 | binding (route minimum) |
| S10 | Route-minimum promotion (risk-safe minimum 7840) | `decide`, MinPromotion8188 | B | 8188, 8236, 8259, 8316, 8319 | **8114**: promoted; only NEGATIVE_EDGE at the minimum refuses |
| S11 | Order-size promotion read | `OrderSizeResolver6441.liveEdgeAllows7877` | B | 8180 admission proof | 8114-admitted allowed |
| S12 | SmartSizerV3 admitted promotion | `SmartSizerV3` | C | 8180, 8188, 8316 | includes 8114-admitted; sub-routable still refused |
| S13 | Auto-compound multiplier | `AutoCompoundEngine.getSizeMultiplier` | B | 8296 live-only pool (wins add, losses drain) | **kept**. At 8114 paper wins grew the live multiplier; paper never sizes live. |
| S14 | SmartSizer paper fallback / paper→live streak seed | SmartSizer | B | 8296 | kept (paper never sizes live) |
| S15 | Total exposure ceiling / safe share cap / routable-capacity preflight / reserves | SmartSizerV3 `routableCapacityPreflight7224`, LiveSpendReserveAuthority7255 | A | — | binding |
| S16 | Live Kelly revoke on fresh live losses | `LiveConfidence8296.kellyGate8296` | C | 8296 | **off** (inside S1) |
| S17 | Admitted minimum promotion / drawdown deferral at the last mile | LiveRiskPolicy 8319 | C | 8319 | subsumed by S10 |
| S18 | Kill switch HALT | KillSwitch | A | — | binding |
| S19 | Runner adds / pyramiding, exit machinery | Executor / exits | — | — | unchanged (exits are not part of this mode) |

**Sizing counts: A 4, B 7, C 7** (18 classed rows; S19 is not classed).

The report line now includes `size8114[<lane> n avg last SOL kelly refused] compound=<live pool multiplier>x day=… multiple=…x target=2x stretch=5x`. The last part is `LiveRiskPolicy7807.dailyScoreLine8057`, today's marked-equity multiple against the day start.

## Shadow comparison

Each covered live coin gets one record per lane, opened when it is first evaluated, with:
- the first-sight price (an observed mark);
- the round trip quoted at first sight;
- whether the 8114 verdict and the current verdict admitted it at any evaluation inside 60 minutes.

At 60 minutes:
- forward net = observed mark ÷ first-sight price − 1 − round trip;
- runner = observed peak ≥ 1.5 × first-sight price;
- a coin with no observed mark is counted unresolved, never given a number.

Per lane the report shows:

`entry8114[<lane>: buys8114 a / buysNow b / both c / onlyOld d / onlyNew e; <set> n fwd60 ±x% run y% …; live8114 n w ±SOL]`

`live8114` counts the live whole-position closes (CanonicalFinalizedTradeBus6464, LIVE) of coins the 8114 decision admitted. `Entry8114Shadow8326.labelFor8326(mint, lane)` gives each coin's pair of verdicts, for example `8114:ADMIT now:REFUSE(CANDIDATE_ADMISSION_8136_TIMING_ADVERSE_8136)`. A divergent coin is logged once (`ENTRY8114_SHADOW_8326`).

## Turning it off

Switch "Entry 8114 mode" off in Settings. The current decision and sizing then apply unchanged (`Aate8326Entry8114ModeTest.modeOffKeepsCurrentBehaviour`).
