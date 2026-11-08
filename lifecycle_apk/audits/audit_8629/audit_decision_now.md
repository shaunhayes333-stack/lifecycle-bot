# AATE live meme buy decision: audit of the current stack and a proposal for one decision model

**Code audited:** `origin/main` = `0f31c8eca` (5.0.8628). The request named 5.0.8627; main has moved on by one commit. 8628 changes only labelling and launch-hour measurement, plus one ordering change in `LiveEdgeGate7877`: the rebuy lockout now runs before the memo. All line numbers below come from a read-only snapshot of origin/main. The repository was not modified.
**Path prefix:** `K/` = `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`
**Log:** `fa6783fb-aate_pipeline_20261108_141215_897.txt`, build **5.0.8622**, 143 min uptime, LIVE. The rules added in 8626 and 8627 were derived from this same log, so the log has no forward record of them yet.

---

## 0. Summary of findings

1. **One question is asked many times.** A live meme candidate passes about **30 stages** and **about 90 named refusal points** before broadcast. About 45 of them carry a trading opinion. The question "is this a good entry for this lane" is re-judged in at least **9 places**: V3 floor, admission, LaneAuthority, LiveEdgeGate, TradePlan, CellProof/FreshLaunch, TradeAuthorizer 8506, Executor 8506/FlatChart, and Executor chokepoint 7742. Each uses its own thresholds, its own statistical bar and its own definition of the outcome.
2. **LIVE admission is a PAPER decision.** `CandidateAdmission8136.assess8136` calls `decide8136(candidate.copy(live = false)) { false }` (`K/engine/truth/CandidateAdmission8136.kt:1498`, added in 8604 "LIVE decides like the PAPER book"). Every live-only rule inside `decide8136` is therefore **dead code in live**, and every record door is turned off: regime-cold 8546, entry-wait 8506, into-high 8545, into-launch 8559, native-opinion 8536, generic-prior 8238, watchlist 8507, setup/cell/stage/tactic/timing losing, and cheat-sheet AVOID. The paper book this copied was itself an artefact: +8.4 SOL came from lost-mark closes at stale marks, fixed in 8623. The foundation cheat sheet says the opposite: "paper success does not authorize live risk" (`docs/EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md:39`).
3. **Most learned doors only count.** `LaneAuthority8577` turns every learned refusal into an ADMIT for a lane-qualified coin (`LaneAuthority8577.kt:197-239`). It also makes every LiveEdgeGate refusal advisory for any admitted coin (`:244-246`). The things that still bind are hard safety, data or staleness, the executable minimum, the lockouts, and four hand-written "entry state" rules (8582 high/crowd, 8608 paper bench, 8626 stage, 8627 stale). Those four were re-armed one at a time from single logs.
4. **The filters cannot separate winners, because the whole market lost.** Admitted −12.7% vs refused −12.8% at 5 minutes, against a market-wide regime mean of **−13.7%** with 28% of coins rising (log l.34, 41). Every candidate pays about 4–9% round trip at the 0.035 SOL ticket. The "master switch" that should stop live entries in such a market (`MemeRegime8546`) has two faults. Its COLD test needs fewer than 25% of coins rising, so this market reads NEUTRAL. And its refusal sits in `decide8136`'s live branch, which is dead (finding 2).
5. **Label coverage is 37% (admitted) and 8% (refused)**, and the report itself marks this "UNRELIABLE" (log l.39). Every learned door, ledger, cheat-sheet verdict and stage record that decides live entries was fitted on survivor-biased data.
6. **Sizing does nothing.** Every live buy in the log is 0.035 SOL, the route minimum (`routableMin=0.0336`). About 8 sizers (Kelly, governor, lane prior, launch shaping, Field Manual risk cap, LosingPatternMemory, commonSense, drawdown) compute a size that always collapses to the floor.

---

## 1. The live meme buy path, in execution order

Lane used for line refs: **EXPRESS** (most live closes: 21). Other lanes follow the same skeleton at the `FdgOutcomeContinuity8197.evaluate` sites listed in `K/engine/BotService.kt`: CORE 14093, TREASURY/CASHGEN 28785, QUALITY 29065, BLUECHIP 29313, MOONSHOT 29705, SHITCOIN 30297, MANIPULATED 30650, EXPRESS 30873, PROJECT_SNIPER 31208, DIP_HUNTER 31412.

**Legend.** **B** = binds (refuses or defers). **A** = advisory (counted only). **R** = resizes. **ADM** = can admit or override a refusal. **D** = dead in live at 8628. "Fwd" = the forward record in the log, where one exists.

### A. Before the lane runs (BotService.processTokenCycle, `BotService.kt:26044`)

| # | Component | Reads | Rule | Status | Fwd / log |
|---|---|---|---|---|---|
| 1 | Token safety tier (TokenSafetyChecker → `ts.safety`) | rugcheck, LP, authorities, top holder | HARD_BLOCK tier refuses | B | SAFETY allow 4902 / block 236 (HARD=LP 175, RUG_CLUSTER 29) |
| 2 | **V3 scorer** `V3EngineManager.processToken` (`BotService.kt:28083`) | snapshot features | Sets `ts.lastV3Score` (28094-28101). BlockFatal / structural Rejected is a hard reject on the treasury path (28537-28553). | B (some lanes); the score feeds #13 | V3 allow 1026. Top V3 rejects: LOW_LIQUIDITY 300, LIQ_UNHYDRATED 176+70 |
| 3 | Circuit-breaker early short-circuit (`BotService.kt:~27594`) | breaker state | No buy work while tripped | B | — |
| 4 | Lane election: `electionSnapshot8522` / `canonicalCycleLaneFor` (27581-27582), `shouldRunBuyLaneForCycle` (15107, used at 30813) | toolkit sheet | Only the elected or allowed lane evaluates this cycle | DEFER | CORE `READY_NOT_EVALUATED_PRIMARY_*` 353/275/167 |
| 5 | `LaneAutoPauseGuard` (30806) | lane P&L | Pause triggers only a tactic pivot | A | — |
| 6 | Lane pre-filter (EXPRESS mcap 1k–300k, 30827) | mcap/liq | Outside band → skip | B | EXPRESS nativeWhy MCAP_TOO_HIGH 265 / LOW 164 |
| 7 | **Native lane trader** `ShitCoinExpress.evaluate` (`K/v3/scoring/ShitCoinExpress.kt:269`) via `SpecialistInputParity8527.express8527` | momentum, buy pressure, liq | Momentum ≥ 1–3% (346, 370), buy pressure ≥ 45% (347, 377), liq floor `EXPRESS_MIN_LIQ_8323` = $500 (331). TP 30/50/100, SL −8 (77-83). | B | EXPRESS NO_CONTINUATION 1400, LIQ_BELOW_FLOOR 1037, WEAK_BUYS 448 |
| 8 | `NativeOpportunityDoor8522` + `SpecialistBrainBridge7542.opportunityDecision8522` (`BotService.kt:1197-1205`) | inverse-election opinion | REFUSE ends it; otherwise projects the signal | B / ADM | — |
| 9 | **`laneDoor8105`** (`BotService.kt:1090-1110`) | RunnerPlay ticket, RunnerGrabLane, `Cortex7885.vetoRefusesWinners8006` | **Turns a native refusal into shouldRide=true** with size ≥ 0.01 and a QUICK_FLIP ride (1203-1205) | ADM | MOONSHOT `LANE_VETO_RETIRED_8105_NEAR_HIGH` 44 |
| 10 | `RuntimeConfigOverlay.isLaneDisabled` (30857) | overlay | QUALITY-only mode | B | — |
| 11 | `laneQualifiedBuyDecision` (14139 → core 14157) | lane score/conf | `chartBuyDecision7950` (25810) can force BUY. Thin-liq WAIT (14560). `DEFENSIVE_WAIT_SHADOW_ONLY_6487` (14573). `EXPLORATION_*_6747/6967` (14628-14793). `DUST_PROBE_SHADOW_ONLY_8080` (14803). | B / ADM | FDG/DEFENSIVE_WAIT 144, EXPLORATION_DAMPED_DUST 38, DUST_PROBE fwd n2 −28% |

### B. FinalDecisionGate.evaluate (`K/engine/FinalDecisionGate.kt:1489-6378`), via `FdgOutcomeContinuity8197.evaluate` (`K/engine/FdgOutcomeContinuity8197.kt:22`)

| # | Component | Reads | Rule | Status | Fwd / log |
|---|---|---|---|---|---|
| 12 | HARD_NO_BUY (1527-1598) | candidate signal | Terminal | B | — |
| 13 | **Canonical V3 / lane score floor** `CanonicalEntryFloor7266.resolve` (FDG 1624-1752; `CanonicalEntryFloor7266.kt:173`) | `lastV3Score`, lane score, learned score band (ScoreExpectancyTracker), regime and damper deltas | Below the floor, or WAIT under the wait floor, refuses. **Bypasses:** a specific admission ADMIT (`floorFromAdmission8136`, 1173), ladder-proven launch 7932, lane proven 7941, retired veto population 8112, playbook edge ≥ wait bar 8535 (`playbookClearsFloor7937`, 1202-1238). | **B, before LaneAuthority** | FDG/CANONICAL_V3_SCORE_FLOOR_7243 389 (**EXPRESS 371**, SHITCOIN 18), WAIT_PROMOTION 24 |
| 14 | **CandidateAdmission8136.assess8136** (computed at FDG 1675; recomputed inside #15 at LiveEdgeGate 671 and #16 at TradePlan 459) | see section B.1 below | see section B.1 | B / ADM | FDG/CANDIDATE_ADMISSION_8136_PAPER_LANE_PROV 1051, EXECUTION_ECONO 332, STRUCTURAL_RISK 232, FALLING_HOUR 23 |
| 15 | **LiveEdgeGate7877.liveRefusal** (`K/engine/truth/LiveEdgeGate7877.kt:486-569`) via `specialistAdvisoryBlock7845` → `liveEdgeBlock7877` (FDG 994-1006, 1268) | see section B.2 below | about 20 doors, then LaneAuthority advisory (539) | mostly A; binding list B | LaneAuthority overrides = 1028 (CHASING_SPIKE 237, NET_EDGE_COIN_TOO_SLOW 111, LIVE_SETUP_LOSING 109, WATCH_FIRST band 90, shape RUNUP<25 57, SOURCE_LOSING 49, …) |
| 16 | **TradePlan7739.liveBlockReason** (`K/engine/truth/TradePlan7739.kt:424-544`), called at FDG 1027-1032 | 1-min bars, admission | `EvidenceHold8336` (disabled: `enabled8336=false`, EvidenceHold8336.kt:41). `LivePivotAuthority7876` for non-runner lanes (450; binds while KillSwitch pivot reasons exist). **A second LiveEdgeGate call** (452). Then: any admitted candidate gets a plan, the chart setup if one exists, else a **default LAUNCH_EARLY plan** (468-472). The exception: evidence admit + `NO_PULLBACK_YET` + unretraced impulse → WAIT (461-466). Non-admitted needs pullback-reclaim, base breakout or sweep-reclaim with 2R, or a launch ladder. | **B for specialists in live** (FDG 1029: `FDG_TRADE_PLAN_BINDS_SPECIALIST_8418`) | PIVOT_7876_UNPROVEN TREASURY 79, BLUECHIP 22 |
| 17 | FieldManual7715.decide (FDG 948-982) | plan card: regime, trigger, cost, R:R | ENTER / SMALL_PROBE / WAIT / PASS | **A for every specialist lane** (FDG 1023-1026); B only for the trunk | FIELD_MANUAL_WAIT 518; fwd **NO_E_TRIGGER refused n15 +22.4% wr46% run20%** |
| 18 | Council7740.liveBlockReason (FDG 1337; `K/engine/truth/Council7740.kt:107`) | owner, expectancy band, oracle | A binding ballot refuses | **A for specialists** (FDG 1039-1042) | COUNCIL_REFUSED 88; fwd refused **n3 +43.1%** |
| 19 | CellProofLadder7731 (`cellProofBlock7731`, FDG 1058) | cell (source\|lane\|mcap\|age) with 100 labels | Negative expectancy refuses unless a specific ADMIT and the 8298 stand-down apply | **B** (not routed through LaneAuthority) | — |
| 20 | FreshLaunchSelector7737 (`freshLaunchBlock7737`, FDG 1105) | launch < 30 min, crash shape, −30%-before-+50% | Refuses | **B** (not via LaneAuthority) | FDG_FRESH_LAUN… appears in SHITCOIN ownerWhy |
| 21 | Overlay lane disabled (FDG 1918) | overlay | — | B | — |
| 22 | Legacy FDG body (2058-4600) | many | ZERO_CONFIDENCE_LIVE_DEFER 2155 · ML_RUG 2358 · WR_ROLL50_COLLAPSE 2439 · SELECTION_QUALITY_FLOOR_6830 2557 · INVENTORY_PRESSURE 2599 · LIQUIDITY_BELOW_* 2833/2887 · CIRCUIT_BREAKER 2985 · REENTRY_GUARD 3108 · HARD_BLOCK_* 3118-3445 · RSI_OVERBOUGHT 3492 · BEHAVIOR 3672/3689 · DISTRIBUTION 3859/3894 · legacy EDGE_VETO 3954 / `EDGE_VETO_ACTIVE` 794 · UNKNOWN_PHASE 3984 · EDGE_VETO_<q> 4060 · NARRATIVE / GEMINI scam 4117-4165 · LIVE_QUALITY_TOO_LOW 4348 · LIQUIDITY_COLLAPSE 4366 · EVCalculator NEGATIVE_EV / HIGH_RUG 4400-4422 · LosingPatternMemory size shaping 4498-4552 · routing 4599 · SIZE_TOO_SMALL 5252 | B; R (4531-4537) | EDGE_VETO_ACTIVE 21, LIQUIDITY_BELOW_WATCHLIST 17 |
| 23 | BrainConsensusGate (FDG 5540-5768) | brain consensus, losing-pattern danger | HARD / SOFT block | B | fwd **BRAIN_CONSENSUS_VETO… n13 −31.6%** |
| 24 | LiveConcentrationDoctrine7697 (FDG 5783-5787) | objections | Conviction refusal | B | LIVE_CONVICTION_7697 70 |
| 25 | **Sizing** `resolveExecutableSize7835` (FDG 6379-6560) plus `sizeFactor8083` (6205) | wallet, depth, curve, Kelly, governor, lane prior | LaunchEntryShaping7871 (6439) → depth / curve caps → **LiveRiskPolicy7807** (6448-6489) → LiveSizeTarget8094 tier → `routeAwareSpendCap7842` → **OrderSizeResolver6441** (FieldManual risk cap; 6520) → sealed minimum. Zero → `SIZE_NOT_EXECUTABLE_7835` (6362). | R / B | All 9 live buys 0.035 SOL = route minimum. `fdgSizeZero8552` EXPRESS RESOLVER_CONVICTION 56, CORE RISK_SIZE_BELOW_MIN 15. Fwd SIZE_NOT_EXECUTABLE **n14 −26.4%** |
| 26 | Sealed economics re-quote 8209 (FDG 6348) | cost at sealed size | `ADMISSION_COST_REQUOTE_FAILED_8209` | B | — |

#### B.1 Inside CandidateAdmission8136 (`assess8136` 1430-1517, `candidateFor8136` 1532-1700, `decide8136` 500-931)

*Inputs* (`candidateFor8136`): the playbook strategy pick and fired setups (`LanePlaybook7907.strategyPick8153/strategyOptions8153`, gated by `SetupAuthority8535`), setup records, cell stat and fresh cell, stage from `TokenMetricStageRouter.laneFit/entryRead8506` and its stage record, the timing band, cost `NetEdge8030.entryCost8209`, expected move `NetEdge8030.expectedMove8418` (built on **the FieldManual mandate gross**, NetEdge 286-289), `EdgeModel8537.verdict`, `edge8114Live8538`, `regimeCold8546`, cheat-sheet verdicts, the Cortex STRONG cohort, runner grab, price age, and missing inputs.

*Decision as it actually runs in live:* `decide8136(copy(live=false)){false}` (1498).

| Rule (line) | Live at 8628? |
|---|---|
| SAFETY_REFUSED (516) | **binds** |
| LIVE_REGIME_COLD_8546 (519-522) | **D**: needs `c.live` |
| STRUCTURAL_RISK (538) | **binds** |
| entryWait8506 defer/refuse (557-602) | **D**: needs `c.live` |
| SETUP_RECORD_LOSING (638), SHEET_AVOID_8323 (658), CELL / STAGE / TACTIC / TIMING losing (677-723), liveLocalGuard8325 (827) | **D**: `doorBinds = { false }` |
| FALLING_HOUR_PROVEN_LOSING / DEFER (770/775) | binds **only when not specialist-qualified** (`paperExplores8539` skip) |
| EXECUTION_ECONOMICS 1.5× cost (788) | binds **only when not specialist-qualified** |
| DATA_INCOMPLETE_DEFER (816) | binds (defer) |
| EDGE_MODEL_NEGATIVE_8537 (823) | binds only with authority. **None today**: all lanes "learning", out-of-sample picks −51% to −63%. |
| SPECIFIC_EDGE_PROVEN (839), EDGE_MODEL_PROVEN (843) | ADM |
| GENERIC_SCORE_FLOOR (850) | binds |
| WATCHLIST_RECHECK 8507 (864), INTO_HIGH 8545 (885), INTO_LAUNCH 8559 (896), NATIVE_OPINION 8536 (906), GENERIC_PRIOR 8238 (925) | **D**: need `c.live` |
| NATIVE_QUALIFIED_ADMIT (916), GENERIC_PRIOR_CLEARS (927) | ADM |

*Then `LaneAuthority8577.apply8577`* (1497-1501; `K/engine/truth/LaneAuthority8577.kt:183-239`):
1. `PAPER_LANE_PROVEN_LOSING_8608` refuses unless the pocket says PAYS (212-220). **Binds.** It reads `MemePaperTrader8544.laneProvenLosing8608` (`MemePaperTrader8544.kt:382`). When the priced record has fewer than 30 closes, that function falls back to the old **P8572 record, which includes lost-mark closes**.
2. `memeEntryRefusal8626` (141-151) **binds**: (a) runner lane and launch age over 24 h → `LIVE_RUNNER_STALE_COIN_8627`; (b) `memeBuysTheHigh8582` (96-101): pxPeak ≥ 0.97 with runup ≥ 5%, **or buy pressure ≥ 60%**, across 10 lanes including QUALITY, BLUECHIP, CORE and CYCLIC (93-94); (c) stage PEAK_EXHAUSTION or DUMPING unless that lane's stage record has n ≥ 30 and mean − se > 0 (120-123). *Ordering bug:* this is applied only when the base decision is not already REFUSE (222). A specialist-qualified REFUSE (today only EDGE_MODEL_NEGATIVE could produce one) is then flipped to ADMIT at 227-238 **without** the high, stage or stale checks.
3. A specialist-qualified candidate (native-qualified and generic ≥ floor, `CandidateAdmission8136.kt:974`) whose refusal is not on `BINDING_8577` (26-45) becomes **ADMIT `LANE_AUTHORITY_8577`** (231-238).
4. Coin-state 8609 and pocket 8610 refusals are **advisory** (8620; 188-194).
5. Side effects: `MemePaperTrader8544.consider8544` (paper book), `TuningReport8618.noteLiveVerdict8618`.

*Fwd (8622):* `ADMISSION8136_PAPER_LANE_PROVEN_LOSING_8608` refused n49 −19.7% (PROTECTING). `ADMISSION8136_LIVE_POCKET_PROVEN_LOSING_8610` refused n71 **−6.5% wr23%**, against admitted −12.7%: **the pocket refused better coins than live bought.** STRUCTURAL_RISK n8 −70.7%. EXECUTION_ECONOMICS n5 −10.4%. In TUNING, the paper result by live verdict shows every LIVE_ADMIT lane negative: SHITCOIN −10.2% n40, QUALITY −5.4%, TREASURY −4.5%, EXPRESS −2.1%.

#### B.2 Inside LiveEdgeGate7877.liveRefusal (486-569) and canonicalRefusal8392 (652-860)

In order:
1. `RebuyLockout8019.refusal8019` (504; `K/engine/RebuyLockout8019.kt:90-111`): REPEAT_LOSER / BIG_LOSER_8130 / REBUY_LOCKOUT. **B** (on the binding list).
2. `EntryEconomics8329.laneRealisedRefusal8329` (496), then `EvidenceHold8336` (disabled): A.
3. `OnChainDepth8038.earlyLaunchRefusal8048` (665): B (EARLY_LAUNCH).
4. `LiveBootRecovery8074.blind8074` (677): B (BLIND).
5. Admission terminal (689-740): DEFER_DATA (DATA_* binds; FALLING_HOUR_DEFER is advisory) or specific REFUSE `CANDIDATE_ADMISSION_8136_<reason>`. This binds when the reason is on the binding list or the admission is not admitted.
6. `LiveTruth8064.setupRefusal8083` LIVE_SETUP_LOSING (750): A.
7. TIMING_RECORD_NEGATIVE_8094 (771): A.
8. PICKED_SETUP_RECORD_NEGATIVE_8094 (782): A.
9. `SelectionDoors8310.admitRefusal8310` (817): A.
10. `chaseRefusal8397` (829; `chasing8065` 881: runup ≥ 80 and ≥ 0.88 of peak, or 5-min change ≥ 100%; `chasingRunner8468` 891: runup ≥ 40 and ≥ 0.92, or ≥ 60): **A** (CHASING_SPIKE is not on the binding list).
11. `watchFirst7994` (841): A.
12. `liveRefusalCore7970` (1321-1420): Cortex entryRefusal (dead after 8623), `NetEdge8030.entryRefusal8030` (`NetEdge8030.kt:236-270`: a second economics test at a different ticket size and cost model, still named "2X"), `laneFitRefusal8054`, playbook named-setup proven-losing 8079, Mayhem (B), SpecialistMiner7972 (ADM), then the priors `LanePlaybook7907.liveRefusal` (1379), `TokenMetricStageRouter.liveStageRefusal7928`, `CostLedger7962.liveRefusal7962`: all A.
13. `ExploreShadow8347.nicheRefusal8351` / `liveEntryWait8347` (523-524): A.
14. `sourceRefusal8536` / `unprovenLive8536` (530, 571-596). The proof list is `edge8114Live8538` (601-633): family proven, paper lane proven, proven setup, measured edge, cell/kind, runner forming, runner ticket, shadow audit, chart BUY, Cortex STRONG. All A.
15. **`LaneAuthority8577.advisoryAtGate8577`** (539): advisory for **any** admitted candidate unless `binding8577`.

*Net effect:* 1, 3, 4, 5 and Mayhem bind. Everything else is counted.

### C. After the FDG

| # | Component | Rule | Status | Fwd / log |
|---|---|---|---|---|
| 27 | `TradeAuthorizer.authorize` (`K/engine/TradeAuthorizer.kt:168`, locked 195-416) | Preauth seal 7834 · kill switch · paused / lane disabled · slot health · ALREADY_OPEN (cross-book) · rugcheck catastrophic · **`ENTRY_<wait>_8506`** (452-460; soft waits are settled by the admission proof **unless the stage is now DUMPING / PEAK / RUG_PRONE**, `RunnerAdmission8180.kt:112-135`) · **`PRESEAL_<flat>_8304`** (462; FlatChart8027) · cooldown 8327 (467) · FDG seal 7835 / WalletCapacitySeal7868 · lane election PREAUTH · finality · banned · LIVE_SAFETY_CB · liquidity < $1.2k | B | `AUTH_ENTRY_NO_RECLAIM_8506` SHITCOIN 96, EXPRESS 39. Ticket→exec conversion: EXPRESS 54→4 (7%), SHITCOIN 34→3 (9%) |
| 28 | `Executor.shitCoinBuy` (`K/engine/Executor.kt:18159`) | finalityPrechecked preflight | B | LIVE_BUY_NOT_OPENED_7948 |
| 29 | `Executor.liveBuy` (18821-21030) | sealed size · preflight 7948 · **ENTRY_8506 again** (18875-18877) · **FlatChart8027 again** (18879; MANUFACTURED_CLIMB_8036) · last-exit failed 7314 · held exit stuck · collapse guard 6326 · rug risk 6405 · decision expired · ROUTE_UNAVAILABLE 6411 · RUG_BLACKLIST 19229 · LIVE_ENTRY_SAFETY_HOLD_6312 19599 · MINT_REENTRY_COOLDOWN 19633 · PROVIDER_DEGRADED 19832 · SIZE_BELOW_FEE_FLOOR_6247 19869 · ADVISOR_BLOCK 19894 · snapshot missing 19914 · sniper-not-launch 20058 · **CHOKEPOINT_7742** (20066: re-runs `TradePlan7739.liveBlockReason` and `CellProofLadder` unless the buy is a sealed FDG buy, `TradePlan7739.kt:652-672`) · commonSense size mult · TOKEN_MAP_INCOMPLETE 20243 · COMMON_SENSE_PREBUY 20258/20315 · route fail 20370 · **`PreTradeHardGate.requireLiveBuyAllowed`** 20688 (RebuyLockout again `PreTradeHardGate.kt:144`, SAFETY_HARD_BLOCK, RUG_CLUSTER) | B / R | BUY ok/fail **8/23**: PRETRADE_BIG_LOSER_8130 9, MANUFACTURED_CLIMB 3, HARD_BLOCK_COOLDOWN 2, TOP_HOLDER 2, PRELEASE_SCORE_7256 1, CHOKEPOINT_7742_CHASING_SPIKE 1 |
| 30 | Route / broadcast: `tryPumpPortalBuy` (21006) / `prepareJupiterBuy8090` (21027) → Helius Sender → Jito → RPC | quote / sim / send | B (technical) | start→broadcast avg 8.9 s, max 21 s |

**ExecutableOpenGate** (`K/engine/ExecutableOpenGate.kt:2575/2984`) and **TicketExec8304** (`K/engine/TicketExec8304.kt`) carry no trading opinion. They do the sealed-ticket and intent bookkeeping (election-lane mismatch, synthetic candidates) and name every drop. TicketExec also hosts the pre-seal chart refusal (#27) and the zero-size counters (#25).

---

## 2. Knowledge sources that encode "rules" or "edge"

| Source | What it claims | Where it is consumed (live meme) | Live authority at 8628 |
|---|---|---|---|
| **`docs/FIELD_MANUAL.md`** + **`FieldManual7715`** (`K/engine/truth/FieldManual7715.kt`) | No trigger and invalidation means no trade (manual l.305). Lane mandates (218-233): runner +50% gross / 15% invalidation, momentum 30/10, quality 20/10, treasury 12/5. All-in cost (`allInCostPct8350` 314). Stop = max(mandate, 1.5× cost) (`stopPct8392`). | FDG #17 (A for specialists). Its mandates also feed **NetEdge expected move** (`NetEdge8030.kt:240, 287`), LiveRiskPolicy (663), OrderSizeResolver risk cap (401), cheat sheet, ExploreShadow | Plan card advisory. Its *numbers* silently drive economics and sizing. |
| **`docs/EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md`** | Missing evidence means WAIT/PASS (l.30). **Paper success does not authorize live risk** (l.39). The prior fades as evidence grows. | Cited by exits only (`ProtectiveExitClass7807.kt:27`) | **Contradicted** by 8572/8604/8608/8616 |
| **`LaneCheatSheet8323`** (`K/engine/cortex/LaneCheatSheet8323.kt`; doc `lifecycle_apk/LANE_CHEAT_SHEETS_8323.md`) | One sheet per lane: mandate (stages, age / mcap / liq bands), setup FAVOUR / NEUTRAL / AVOID (FAVOUR n ≥ 20 with mean − SE > prior + cost; AVOID n ≥ 12 with mean + 2SE < cost, or n ≥ 30, or 2 losing live closes), timing, exits, hunter bands, source routing | Merge-queue routing, LaneHunter, playbook `choose8323` (`LanePlaybook7907.kt:1349`), admission `sheetFavour8323` / `SHEET_AVOID` (`CandidateAdmission8136.kt:651-660`), NativeLaneConstraints8521 | Routing and FAVOUR choice live. **AVOID refusal dead** (doorBinds false). |
| **`LanePlaybook7907`** (`K/engine/cortex/LanePlaybook7907.kt`) | Per-lane menus of 10–22 setups (MENU 707-735) plus cross-lane 8061, MEME_8383 (TREND_PULLBACK, SWEET_SPOT, BAND_MOMENTUM, RUNUP_HOLD, PEAK_RECLAIM; **all need bp ≥ 50–55**, 567-583), the envelope 8383 (536-560), static RETIRED_8383 (602-621) and RETIRED_8555 (641-661), **PEAK_BAND_CHASE_8586** (1341; timingBand PEAK = runup ≥ 80 and ≥ 0.88, 1564-1570). Proven losing = n ≥ 30, mean + SE < −2%, runner rate < 10% (1402-1408). NO_TRIGGER proof = n ≥ 40, mean − SE > +1%. | Admission inputs (setups, records, pick). `liveRefusal` priors (A). FDG floor bypass 8535. `provenSetup8056` (proof list) | Setup **selection** live. Refusals advisory except the structural retirements and envelope (classification-level). |
| **`MemeStrategies8546`** (`K/engine/truth/MemeStrategies8546.kt`) | Five event families: PULLBACK_RECLAIM (impulse 25–400%, pullback 15–55%, buy share ≥ 55%), BASE_BREAKOUT_FLOW (buy share ≥ 60%), GRADUATION_RETEST, WALLET_CLUSTER, RANGE_LOW. A family earns live money at n ≥ 30, mean − se > 0, PF ≥ 1.3. | Paper book. `edge8114Live8538` FAMILY_PROVEN (A at gate) | Log: signals all 0, liveProven none. **Inert.** |
| **`TradePlan7739`** | Pullback-reclaim / base-breakout / sweep-reclaim with structural stop and 2R. LAUNCH_EARLY ladder. NO_PULLBACK_YET wait. | FDG #16 (**B**), Executor chokepoint #29 | Binds, but **any admit gets a default plan** (468-472) |
| **`ExitProfile7955`** (`K/engine/ExitProfile7955.kt`), `RunnerDoctrine8584`, `StopAuthority7887`, `ShitCoinExpress` constants | Learned exits per (lane, setup). Runner ladder +100/+400/+900. Stop bases EXPRESS 10, SHITCOIN 8 (`StopAuthority7887.kt:35`). Express executor SL −8 (`BotService.kt:30973`). | Exits; the entry plan stop | Four stop sources for one entry |
| **`Cortex7885`** + voters | STRONG cohorts, vetoRefusesWinners, overrule. Scoreboard: all lanes SUSPENDED / INSUFFICIENT (log l.279-298). | `laneDoor8105`, gate priors, admission `cortexStrong8200` | **Voter graph retired in 8623** (`Cortex7885.kt:231`). Buy-path reads return null. Dead. |
| **`EdgeModel8537`** (`K/engine/truth/EdgeModel8537.kt`) | Ridge model of log net return at the 5-min label. Authority only from out-of-sample picks (n30, mean − se > 0, beats lane). | Admission NEGATIVE / POSITIVE (823, 842) | No authority. Out-of-sample picks −51% to −63% on all lanes (log l.36). Inert. |
| **`PocketLedger8610`** (`K/engine/truth/PocketLedger8610.kt`) | Banded feature cells and pairs. Outcome = **managed −20 / +400** of the label. Call PAYS when the mean clipped t > +0.15 over cells with n ≥ 40. | `apply8577` (exempts the 8608 bench on PAYS). 8609/8610 advisory. | Advisory, plus one admit path. 17,952 LOSES calls. Refused n71 −6.5% vs admitted −12.7%. |
| **`MemePaperTrader8544`** lane verdicts | laneProven8572 (n30, mean − se > 0, PF ≥ 1.3) · laneLeads8616 (n30, mean > 0, PF ≥ 1.5) · laneProvenLosing8608 (n30, mean + se < 0, PF < 1, **falls back to P8572 incl. lost marks**) | 8608 bench (B), 8572/8616 unlocks | **Binding bench** on a mixed-quality record |
| **Stage records** `ForwardReturnLabeler7731.stageStatFor7928` + `TokenMetricStageRouter.LANE_STAGE_SHEET_7928` (`K/engine/TokenMetricStageRouter.kt:489-495`) + `entryWait8506` (469-479) | Stage sheet prior: launch desks buy LAUNCH / BASE. Own record overrules when n60 ≥ 30 and mean > 0 by SE (or 4-hour mean > 0), or mean < −2% by SE. | 8626 stage refusal (B), 8506 waits (B at TradeAuthorizer and Executor), `liveStageRefusal7928` (A) | Three stage rules with three bars |
| **`FallingHourCohort8418`** | Momentum lane, falling hour, no supported strategy: refuse only when proven losing | Admission 770/775 | Non-specialist only; the defer is advisory at the gate |
| **`CellAllocator7962`** (`K/engine/CellAllocator7962.kt`) | Thompson bandit over cells, 5-min label reward | Watch priority (`BotService.kt:21895`), throughput (`ExecutableOpenGate.kt:4115`), rotation | Priority only |
| **`MemeRegime8546`** (`K/engine/truth/MemeRegime8546.kt:19-24`) | COLD when mean < −6% **and** rising < 25% over 45 min | `regimeCold8546` (admission 519) | **Dead in live**, and NEUTRAL at −13.7% / 28% rising |
| **CanonicalEntryFloor7266** + ScoreExpectancyTracker | Learned V3-score band per lane | FDG #13 (**B**) | Binds first |
| **CellProofLadder7731 / FreshLaunchSelector7737** | 100-label cell negative; launch crash shape | FDG #19/#20 (B), TradePlan launch ladder, chokepoint | Binds outside LaneAuthority |
| **LosingPatternMemory / BrainConsensusGate / EVCalculator / LiveConcentrationDoctrine7697** | Score-bucket danger, consensus, EV, conviction | FDG #22-24 | Bind outside LaneAuthority |

### 2.1 Contradictions and duplicates (worst first)

1. **Paper as live authority vs the foundation doc.** Live admission is `decide8136(live=false){false}` (`CandidateAdmission8136.kt:1498`). The lane bench and unlocks come from the paper book (8572/8608/8616). `EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md:39` says paper success does not authorize live risk. 8623 showed the paper book's profit was lost-mark artefacts. The 8608 bench still falls back to that record (`MemePaperTrader8544.kt:386-389`). This lets SHITCOIN trade live on a P8572 record of +30.3% ± 25.3, while SHITCOIN is the worst live lane (n6, PF 0.02) and its live-admitted coins lost −10.2% on paper.
2. **Buy pressure: the same feature with opposite signs.** `memeBuysTheHigh8582` **refuses bp ≥ 60** on 10 lanes (`LaneAuthority8577.kt:96-101`). The playbook's MEME_8383 setups **fire only at bp ≥ 50–55** (`LanePlaybook7907.kt:567-583`; BAND_MOMENTUM 55–80). MemeStrategies families need buy share ≥ 55/60%. Express needs bp ≥ 45 (`ShitCoinExpress.kt:67`). The PocketLedger's top paying pocket is **EXPANDING & bp ≥ 80: +15.0%, t = 2.63** (n193), and EXPANDING & bp < 45 is losing at −10.7% (log l.192-203). The binding rule refuses about half of what the setup library is built to fire on.
3. **"Chasing the top" has seven definitions:**
   - `chasing8065`: runup ≥ 80 and ≥ 0.88, or 5-min change ≥ 100. Advisory, **but still enforced by the Executor chokepoint** on non-sealed paths.
   - `chasingRunner8468`: runup ≥ 40 and ≥ 0.92, or ≥ 60. Advisory.
   - `PEAK_BAND_CHASE_8586`: runup ≥ 80 and ≥ 0.88. Structural.
   - `memeBuysTheHigh8582`: ≥ 0.97 with runup ≥ 5. Binds.
   - `LIVE_INTO_HIGH_UNPROVEN_8545`: stage-based. Dead.
   - `LIVE_STAGE_LOSES_8626`: PEAK / DUMP. Binds.
   - `ENTRY_NO_RECLAIM_8506`: PEAK / DUMP without the sheet's reclaim. Binds, twice downstream.
   - The removed 8555 chase (0.92) and 8130 falling-knife.

   Result in the log: live runner entries were LOSS_BOUGHT_TOP 40 of 63.
4. **Stage rule ×3 with different bars.** 8626 uses n ≥ 30 and mean − se > 0. 8506 / 7928 uses n60 ≥ 30, mean > 0 by SE **or** a positive 4-hour mean, and refuses at mean < −2% by SE. 8545 / 8559 are dead. 8559 refuses **FRESH_LAUNCH unless proven**, yet LAUNCH is **the only positive stage** in the log: EXPRESS +72.5% n7, MOONSHOT +113.6% n5 (log l.55-60). The cheat sheet says launch desks buy LAUNCH / BASE.
5. **Age, four values.** ShitCoinTraderAI allows 12 h mature / **72 h bootstrap** (`ShitCoinTraderAI.kt:133-140`), and the cheat sheet copies 72 h. 8627 refuses runner lanes above **24 h**. The trader's optimal window is 30 min–2 h (145). The hunter still selects 2–5 h coins for SHITCOIN (log l.1316-1319), and the native trader then refuses NATIVE_AGE_ABOVE_MAX 1,422 times.
6. **About 15 different "proven" bars, on 5 outcome definitions.**
   - Bars: n ≥ 30 with mean − se > 0 (cell 8060, stage 8626, family 8546 + PF 1.3, EdgeModel + beats lane). n ≥ 30 with mean + se < 0 (+ PF < 1 for 8608, source 8536). Mean + se < −2% with runner rate < 10% (playbook). n ≥ 40 with mean − se > +1% (NO_TRIGGER). Win-rate lower bound > 0.55 (8094). n ≥ 20 with mean − SE > prior + cost (sheet FAVOUR). n ≥ 12 with mean + 2SE (AVOID). 100 labels (CellProof). Clipped t > 0.15 at n ≥ 40 (pocket). 5 realised closes (8365). 100 refusals (veto audit). PF ≥ 1.5 (8616).
   - Outcomes: 5-min executable net (slot "60", which is 5 minutes since 7946; FallingHourCohort's doc still says "60-minute label"), managed −20 / +400 (pocket, 8603 analyses), 15-min hour-lane grade (8592), paper closes, live closes.
   - The same coin is judged "proven" by one rule and "losing" by another. Each rule was tuned on a single log at 37% / 8% label coverage.
7. **"Baseline" doctrines that are advisory.** Council7740's doc says the cheat-sheet baseline (FieldManual + TradePlan) is "Binding from trade one" (`Council7740.kt:34-36`), and the manual says "no trigger, no trade". For every specialist lane FDG makes the manual and the council advisory (7845), and TradePlan admits any admitted coin on a default LAUNCH_EARLY plan. The manual's own refusals were the best cohort in the log: NO_E_TRIGGER refused n15 **+22.4%**.
8. **Re-judging downstream wastes the slot and changes the answer.** ENTRY_8506 runs at TradeAuthorizer (452) **and** Executor (18875). FlatChart runs pre-seal (462) **and** in liveBuy (18879). RebuyLockout runs at the gate (504) **and** PreTradeHardGate (`:144`, after the ticket: 9 BIG_LOSER fails). CHOKEPOINT_7742 re-runs TradePlan (20066). Ticket conversion is 7–9%.
9. **Economics ×2.** The admission requires gross ≥ 1.5× cost at `admissionQuoteSize8212` (`CandidateAdmission8136.kt:788`). `NetEdge8030.entryRefusal8030` uses `preTradeCostPct8329` = max(model, measured median) at `entryTicket8151()`, under a "2X" name (`NetEdge8030.kt:236-270`). The "expected gross" in both is the **Field Manual mandate shaped by recent range** (`NetEdge8030.kt:286-311`), not a measured setup edge. The FieldManual fallback cost is 1.5% (`FieldManual7715.kt:184`) against a measured 4–9%.
10. **The V3 score floor binds first, though the code admits V3 cannot rank.** EdgeModel8537's header says admitted coins fell 9.3% vs 6.9% for refused under V3 (`EdgeModel8537.kt:14-17`). Yet `CANONICAL_V3_SCORE_FLOOR_7243` refused 371 EXPRESS candidates before any lane evidence was read.
11. **The regime switch is dead.** See finding 4.
12. **The cheat sheet's AVOID is a no-op.** The doc says admission lets AVOID refuse, but doorBinds is false. The static `RETIRED_8383/8555` lists play the AVOID role instead and never rotate back, so two "rotate away from bad setups" mechanisms disagree.
13. **Stops: four sources for one EXPRESS entry.** −8 (executor and trader), 10 (StopAuthority), FieldManual 10 widened to 1.5× cost, ExitProfile learned. MANIPULATED logs "TP+25% SL-5%" while it trades 14/−11 (`BotService.kt:30732, 30781`).
14. **`MEME_LANES_8582` contains non-meme lanes** (QUALITY, BLUECHIP, CORE, CYCLIC), so a meme crowding rule refuses established-coin desks.
15. **Rule churn without a promotion protocol.** 8577 advisory → 8582 bind high → 8603 drop spike → 8604 paper-like → 8608 bench → 8609/8610 state/pocket → 8620 all report-only → 8622 re-arm bench → 8626 re-bind high + stage → 8627 stale. Each flip was justified by one dump's forward record.

---

## 3. Proposed single decision model: `MemeEntryPolicy` (one per lane)

### 3.1 Principle

One pure function is evaluated **once** per (mint, lane, candidateVersion):

`decide(candidate, laneCard, evidence) → REFUSE(class, reason) | WAIT(reason, recheckAfter) | ADMIT(setup, plan{stop, target, horizon}, size)`

The result is sealed into the ticket. Every component downstream may only check **execution invariants that can change between decision and send**: route, wallet, duplicate, finality, price drift, a danger-stage flip. It never re-judges edge. If an invariant fails, the candidate is **re-decided by the same function**, never refused under a different rule.

### 3.2 Inputs and order (each layer may only refuse or wait; only L5 and L6 adjust)

| Layer | Content (single source) | Binds? | Learned adjustment allowed |
|---|---|---|---|
| **L0 Execution minimum** | Identity; fresh executable price (lane max age); cost at intended size known (one cost model: CostLedger7962 measured median, else the NetEdge model); route exists; wallet / kill switch / duplicate / own-loss lockout (RebuyLockout8019); hard safety (HARD_BLOCK, freeze / mint authority, rug cluster, sellability); structural risk. Replaces today's `BINDING_8577` list, PreTradeHardGate, the FDG hard blocks 3118-3445 and TradeAuthorizer safety, run **once**. | Yes | None |
| **L1 Market switch** | MemeRegime from **priced** labels with coverage ≥ 50%. If the 45-min executable mean is below −X% (start: −6%, no "rising" escape), live meme entries pause except L5-proven pockets. Paper continues. | Yes | X within [−10%, −3%] only via the promotion protocol (3.4) |
| **L2 Lane card** | One table per lane: age band, mcap / liq band, venues, **allowed lifecycle stages**, **allowed timing band** (one chase definition: `timingBand8094`), crowding limit if any, stop / target family. Merges trader constants, LaneCheatSheet mandate, LANE_STAGE_SHEET_7928, FieldManual mandates, envelope 8383, RUNNER_MAX_AGE_8627 and MEME_LANES_8582. Hunters and merge-queue routing read the same card. | Yes | Bands move only via the protocol, ±1 band step per promotion |
| **L3 Setup trigger and plan** | Exactly one named setup fires: playbook menu + MemeStrategies families + TradePlan reads, unified as `Setup{predicate, invalidation, target, horizon}`. No trigger → **WAIT** (re-read next bar). The default LAUNCH_EARLY plan for any admit is removed. | Yes (WAIT) | Which setups are enabled per lane = the L5 verdict (FAVOUR / NEUTRAL / AVOID) |
| **L4 Economics** | The setup's expected gross (L5 estimate, prior = FieldManual mandate) ≥ 1.5 × round trip at the actual size. One test, one cost model. | Yes | Margin within [1.3, 2.0] |
| **L5 Evidence book** (one learner) | One store keyed (lane, setup, stage, timing band, plus a few banded features). **One outcome:** executable net at the lane's horizon, priced labels only, weighted by coverage. **One statistical bar** everywhere: proven positive = n ≥ 30 with mean − se > 0 and PF ≥ 1.3 out of sample; proven losing = n ≥ 30 with mean + se < 0. Built from PocketLedger8610 cells + the playbook setup records + stage / cell stats; EdgeModel8537 is kept as a challenger. | Refuses only **proven losing** for that key | Can (a) refuse a proven-losing key, (b) let a proven-positive key trade without a named setup, (c) size ×[0.5, 2.0] |
| **L6 Sizer** | Risk from stop: (stop + cost) × size ≤ lane risk budget; capped by depth, curve and wallet share; × L5 multiplier × drawdown governor. **If the result is below the route minimum and L5 is not proven positive → REFUSE**; never round up to the minimum on no edge. | Yes | Through L5 only |
| **L7 Send-time invariants** | Price drift since decision ≤ lane limit; stage not flipped to DUMP / RUG; route quote within the economics margin. A failure re-runs `decide` (same function). | Yes (re-decide) | None |

### 3.3 Fate of existing components

**Replaced by the policy (their logic moves into one layer):**
- CandidateAdmission8136 `decide8136` → L3–L5. The live / paper split is removed. One decision, with mode used only for sizing.
- LaneAuthority8577 → the binding list becomes L0. The "advisory" transform disappears, because there are no learned doors left to override.
- LiveEdgeGate7877 → RebuyLockout, earlyLaunch and blind move to L0; every other door goes to L5 or is deleted.
- TradePlan7739 → L3 plan producer.
- FieldManual7715 → mandates to L2, cost to L4, risk-from-stop to L6. The plan card is kept as the L3 output format.
- LaneCheatSheet8323 → becomes the L2 card plus the L5 FAVOUR / AVOID view (and actually binds).
- LanePlaybook7907 → setup predicates to L3; records to L5; RETIRED lists to L5 AVOID.
- MemeStrategies8546 → L3 setups.
- TokenMetricStageRouter stage sheet and `entryWait8506` → L2 stages + L7.
- NetEdge8030 / EntryEconomics8329 / CostLedger7962 test → L4.
- FDG sizing chain, LiveRiskPolicy7807, OrderSizeResolver6441, LaunchEntryShaping7871, LosingPatternMemory multipliers, commonSense multiplier, `sizeFactor8083` → L6.
- MemeRegime8546 → L1.
- CanonicalEntryFloor7266 V3 floor → removed as a gate; V3 score becomes an L5 feature.

**Report-only (kept, counted on the forward record, never decide):** EdgeModel8537 (until it beats L5 out of sample), Cortex7885 (already dead), Council7740, BrainConsensusGate, LiveConcentrationDoctrine7697, EVCalculator / MathematicalEdge, CellProofLadder7731 and FreshLaunchSelector7737 (as L5 inputs), CellAllocator7962 (watch priority only), MemePaperTrader8544 lane verdicts (**never authorize or deny live**), ExploreShadow8347 niche / wait, SelectionDoors8310, LiveTruth8064 setup refusal, TradeShapeLearner7883 doors, NativeOpportunityDoor8522 inverse election, TuningReport8618, RunnerFunnel8297.

**Deleted:**
- Duplicate downstream re-judges: TradeAuthorizer ENTRY_8506 and Executor ENTRY_8506 (one L7 check remains), the second FlatChart, Executor CHOKEPOINT_7742, the second RebuyLockout in PreTradeHardGate.
- `laneDoor8105` native-refusal flipping.
- `specialistAdvisoryBlock7845`, Rehearsal8339 / FdgGate8365 demotion machinery, EvidenceHold8336 (disabled), EvidenceShadow8344 gating.
- `edge8114Live8538` / `unprovenLive8536` proof lists.
- The dead live branches in `decide8136` (regimeCold, entryWait, intoHigh, intoLaunch, liveNative, liveGenericPrior, watchlistRecheck).
- `memeEntryRefusal8626` / `memeBuysTheHigh8582` / `PAPER_LANE_PROVEN_LOSING_8608` / 8609 / 8610 as separate rules (they become L2 / L5 keys).
- The legacy FDG soft gates in 2058-4600 that are not safety (WR_ROLL50, SELECTION_QUALITY_FLOOR, INVENTORY_PRESSURE, RSI_OVERBOUGHT, UNKNOWN_PHASE, EDGE_VETO, LIVE_QUALITY_TOO_LOW, NEGATIVE_EV).
- Five of the seven chase definitions.

### 3.4 How learning may change the policy (the promotion protocol)

- A learned rule starts **report-only**, filed on its forward record (refused vs admitted, same outcome definition, priced coverage ≥ 50%).
- It may bind only when its refused cohort is worse than the admitted cohort by at least 1 se, with n ≥ 100 refusals, across **two separate sessions**. It is demoted automatically when the gap inverts.
- At most one rule changes binding status per build. Each change names its forward record in the report.
- Paper evidence may **seed** L5 priors (shrunk toward zero at k = 20) but never authorizes or bans a live lane on its own.

### 3.5 Prerequisite

Fix label coverage first (FIX QUEUE items 1–3: heap OOM, 37% / 8% priced labels). Until coverage is at least 50%, L1 should hold live meme entries at the minimum or paused. Nothing the bot has learned so far can separate a winner from a loser in a −13.7% market.
