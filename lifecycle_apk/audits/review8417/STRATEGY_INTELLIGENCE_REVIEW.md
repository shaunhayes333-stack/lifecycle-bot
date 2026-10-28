# 8417 strategy, intelligence and settlement review

Status: source review and confirmed reproductions; whole-system runtime acceptance is NOT complete.
Baseline: 8416 / 4af85d027. This accompanies WORKING_REVIEW.md; it does not replace the all-app scope.

## Discovery and scanner input review (8417 follow-up)

- `MarketSweep7297.merge` independently took maximum price, capitalization, liquidity, volume and holders, the first nonzero hourly change, and the oldest response date. Providers have different cache lifetimes (one to ten minutes, failure carry up to twenty). This could combine an old rally/peak cap with a current pullback, put a token in the wrong hunter band, and reject an actually fresh input as stale. Merge now keeps one complete priced/capped market observation with its own timestamp, newest first; incomplete rows remain eligible for existing enrichment. Provenance is unioned, but optional absent market fields are not invented or borrowed from another answer. Pool-age lower bounds retain the oldest witness rather than making a token young when a later pool appears. Raydium and GMGN now retain their response timestamps.
- A reported zero hourly change was treated as absent by `SetupFeatureResolver8169`, producing `NO_CHANGE` even with an explicit flat-hour response. Provider parsers now retain presence separately from the numeric value. Legacy missing defaults remain missing.
- `DiscoveryCapture8400.seedShape8407` divided the mutable token's current price by a cached pair's percentage changes. A later doubled price doubled the supposedly historical prices as well. `PairFlow8169` now retains the price and pool creation timestamp from the same provider response. Shape derivation uses that matching price and its matching endpoint. A three-minute-old pair cannot manufacture five-minute or hourly history, even if read five minutes later. Missing price or pool age does not manufacture candles; the existing candle hydration path still applies.
- Regression `Aate8417DiscoveryInputsTest` drives actual merge -> native hunter profiles -> setup feature reads, plus provider parse -> flow capture -> stored shape points. It covers stale peak versus fresh pullback, reversed arrival order, old-pool versus new-pool age, an explicitly flat hour, later price mutation and young/unknown pool refusal. These are deterministic fixtures, not evidence of profitable forward performance.
- The earlier required CI run compiled the release APK and executed 1464 tests with one failure: an old source guard still demanded mint-only evidence episode keys. The guard now requires lane/mint identity; the behavioral restart/isolation regressions remain required. Full current-source test and runtime acceptance are still pending.

Outstanding discovery/brain checks: scanner completeness by provider and launch venue; birth hydration queue latency relative to the sniper window; immutable lane-voter capture; readiness and freshness for every feature family; native strategy-to-size/hold/exit continuity; and canonical outcome changing the next real decision. An ALIVE counter or a list of function callers is not acceptance of any of these.

## Confirmed breaks under repair

1. **Prediction-to-position race:** Cortex7885.retryDeferredBinds8187 returned when no confirmed position awaited a prediction. An asynchronous prediction arriving between intent sealing and chain confirmation could therefore leave that intent PENDING indefinitely. The callback now fills the existing intent seal before checking position waiters. A production-callback regression checks original prediction retention and rejects replacement by later reassessment.
2. **Synthetic performance entering live decision statistics:** HistoricalCorpusSeeder and DailyCorpusRefresher manufacture holding times and retrospective returns. LiveWinDNAStore.realRows included these CORPUS records. Its readers include LaneBucketPivot sizing, Executor/LiveLaneGovernor setup evidence, and ExitIntelligence's winner holding-time floor. These records now remain distinguishable stored material but cannot enter trade-performance aggregates. No claim is made that a PAPER row is blockchain evidence.
3. **Synthetic timestamps erasing actual learning:** daily corpus uses artificial future timestamps; the store evicted the oldest timestamp at its 500-row cap. A large refresh could replace captured trades with synthetic records. Trimming now evicts synthetic/unproven records before captured trades. This cannot recover already-erased history; original settled history requires provenance-preserving replay.
4. **Wrong mode and strategy on delayed learning:** DNA capture moved from the generic education callback to the existing canonical terminal bus. Every meme lane now reaches the same memory consumer, joined by canonical position ID to its immutable entry snapshot. Discovery-source lane inference and latest-mint setup consumption were removed. Rows retain position/event IDs and settlement mode across persistence; unattributed legacy rows remain stored but cannot supply performance evidence. Tests exercise all twelve lanes in both modes, same-mint reassessment, duplicate delivery and persistence/reload.
5. **Strategy economics overwritten:** CASHGEN/TREASURY lane fit used historical movement/default quote after supported candidate admission. The selected setup's own admitted move and notional cost now reach that check; unrelated/general evidence cannot borrow that setup's edge. The original 3x cost requirement remains.
6. **Whole-position fee accounting:** perps local performance and Markets terminal publication used a reducer field that excludes entry fees. Both now use WholePositionEconomics7835 (all proceeds minus entry basis minus all separately recorded fees). Net wallet deltas that already contain fees must carry zero additional fee for that same charge. Regression includes entry and exit fees plus a cached mark showing a gain while settlement shows a loss.
7. **Partial sale mistaken for terminal proof:** the in-process terminal lookup accepted PARTIAL_SELL while the durable lookup correctly refused it. Both now require a terminal SELL; a partial receipt cannot produce a completed strategy lesson.
8. **Independent lane evidence:** shadow positions sharing a mint previously shared identity. Lane+mint identity and persisted pending exits preserve independent lane evidence and restart ownership (earlier 8417 commit).

## Runtime evidence and limits

The supplied session lasted 513 seconds. Main FDG recorded 2398 blocks, zero allows and zero executions. Cortex assessed 3951, graded 126 and reported 1422 lost outcomes; applied choice/size/refusal counters were zero. TREASURY's reported STRONG cohort underperformed its comparison cohort, so forcing its authority on would not repair prediction quality. Episode loss, timely capture, correct strategy horizon, durable outcome delivery and subsequent parameter changes need separate replay evidence.

There are two different truths: a finalized chain transaction proves execution, quantities and wallet movements; the immutable app entry/decision record proves which lane, setup and prediction caused that transaction. Neither substitutes for the other.

The retained Rowley buy/partial/close responses were independently fetched again from finalized Solana RPC during this review and matched exactly. They prove 158329976511 raw bought, 94997985906 then 63331990605 sold, terminal raw zero, and net -0.001379992 SOL excluding refundable account rent. The original decision/exit trigger is not recoverable from those chain records alone. Fixture provenance and signatures are in app/src/test/resources/forensics/rowley-8253.

## Common path checked in source

- Discovery: MarketSweep7297 and LaneHunter7297; twelve independent SpecialistCandidateBooks7803 books, with native qualification before election. Shared identity/wallet/position authority remains singular.
- Native judgement: SpecialistBrainBridge7542 calls each named native implementation; CORE is constructed from the native ensemble. Each opinion contains setup, entry/exit styles and sizing/holding suggestions.
- Arbitration: SpecialistElection8136 + LaneExecutionCoordinator. CORE/CYCLIC use separate scheduling paths and are excluded from generic rescue; deleting the exclusions alone cannot create an executable receiver.
- Strategy and AI: LanePlaybook7907, CandidateAdmission8136, Cortex7885/Authority8366; then LiveEdgeGate7877, final-size economics, ExecutableOpenGate and Executor.
- Entry provenance: EntryStrategySnapshot6450 retains exact strategy and specialist contributions; CortexEntrySeal8395 retains the approving prediction. Presence of fields is not proof every execution path fills them.
- Position management: immutable entry styles and runner identity feed existing lifecycle/exit owners. SpecialistContributorMerge6612 directly supplies bounded entry-size influence; all-lane ongoing hold/exit influence still needs an actual position replay.
- Settlement: canonical position quantity/basis + confirmed transaction receipts, WholePositionEconomics7835, finalized buses 6450/6464.
- Learning: exact strategy, causal, Cortex outcome, exit and cost consumers; epoch and eligibility exclusions are distinct from successful learning. The snapshot's 454 excluded/1 processed cannot be called dropped learning without checking the recorded reset epoch.

## Per-lane source matrix

The following names and menus come from LaneHunter7297, SpecialistBrainBridge7542, LanePlaybook7907 and FieldManual7715. Registered menus are not assertions that a setup fired or currently has positive evidence. Later added/retired setup families and candidate-specific ranking also apply. All lanes use shared canonical capital and final sizing; none has an independent wallet.

### QUALITY

- Designated task/pool: Organic participation, holder breadth and liquid trend structures.
- Native strategy: `QualityTraderAI`; entry `quality_native_entry`; exit `quality_native_exit`.
- Registered base setup menu: `PLAN_PULLBACK_RECLAIM`, `PLAN_BASE_BREAKOUT`, `PLAN_SWEEP_RECLAIM`, `HIGHER_LOW_PULLBACK`, `BREAKOUT_HOLD`, `RECLAIM_AFTER_WEAKNESS`, `HOLDER_EXPANSION`, `OPP_LIQUIDITY_EXPANSION`, `OPP_BREAKOUT_EXPANSION`, `LP_LOCKED_BASE`, `LOW_VOL_COIL`, `SECOND_LEG`, `MOMENTUM_PULLBACK_5M`, `RS_IN_WEAK_MARKET`, `HL_RECLAIM`, `RANGE_SUPPORT_SWING`, `SECOND_WAVE`.
- Holding/economic contract: Trend pullback; 20% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=1749, qualified=1262, FDG reached=8, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=4718. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_READY=1839,NATIVE_MCAP_TOO_LOW=1505,NATIVE_MCAP_TOO_HIGH_FOR=994,NATIVE_BUY_PRESSURE_LOW=314`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### BLUECHIP

- Designated task/pool: Established deeper liquidity and volume; swing continuation/reversion.
- Native strategy: `BlueChipTraderAI`; entry `bluechip_native_swing`; exit `bluechip_native_exit`.
- Registered base setup menu: `PLAN_PULLBACK_RECLAIM`, `PLAN_BASE_BREAKOUT`, `TREND_PULLBACK`, `RELATIVE_STRENGTH`, `FLAG_CONTINUATION`, `DEEP_LIQ_TREND`, `LOW_VOL_COIL`, `MEAN_REVERSION_OVERSOLD`, `OPP_RS_LEADER`, `OPP_LIQUIDITY_EXPANSION`, `OPP_CONTINUATION`, `RS_IN_WEAK_MARKET`, `RANGE_SUPPORT_SWING`.
- Holding/economic contract: Trend pullback; 20% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=2016, qualified=1160, FDG reached=83, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=4012. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_MCAP_TOO_LOW=2999,NATIVE_READY=985,FDG_EVIDENCE_HOLD_8336_BLUECHIP=286,FDG_CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136=272`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### SHITCOIN

- Designated task/pool: Early microcaps, activity, turnover, social/bundle/graduation evidence.
- Native strategy: `ShitCoinTraderAI`; entry `shitcoin_native_meme_entry`; exit `shitcoin_native_exit`.
- Registered base setup menu: `PLAN_PULLBACK_RECLAIM`, `PLAN_BASE_BREAKOUT`, `LAUNCH_CONTINUATION`, `FIRST_PULLBACK`, `PRE_IGNITION_BASE`, `CROWD_FORMING`, `LAUNCH_LADDER_PROVEN`, `FAST_CROWD`, `BROAD_DISTRIBUTION`, `NET_INFLOW_SURGE`, `FIRST_DIP_BOUGHT`, `DEV_HOLDS_CROWD_BUYS`, `CLEAN_DEV_LAUNCH`, `ACCELERATING_TAPE`, `GRADUATION_RUN`, `NO_BUNDLE_CLEAN`, `SOCIAL_LAUNCH`, `NARRATIVE_WAVE`, `INSIDER_ACCUMULATION`, `OPP_EARLY_IGNITION`, `HL_RECLAIM`, `SECOND_WAVE`.
- Holding/economic contract: Launch runner; 50% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=722, qualified=685, FDG reached=59, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=5810. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_READY=2321,NATIVE_LANE_LIQ_UNHYDRATED_DEFER=1255,NATIVE_TOO_OLD=732,NATIVE_THRESHOLD_FAIL_SCORE=643`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### CYCLIC

- Designated task/pool: Active established-enough range/turnover; native compound selection.
- Native strategy: `CyclicTradeEngine`; entry `cyclic_native_compound_pick`; exit `cyclic_inherit_owner_exit`.
- Registered base setup menu: `PLAN_SWEEP_RECLAIM`, `PLAN_BASE_BREAKOUT`, `RANGE_LOW_BOUNCE`, `SUPPORT_FLIP`, `MEAN_REVERSION_OVERSOLD`, `LOW_VOL_COIL`, `OPP_LIQUIDITY_EXPANSION`, `SECOND_LEG`, `OPP_DIP_RECOVERY`, `RANGE_SUPPORT_SWING`.
- Holding/economic contract: FieldManual currently falls through to GENERIC base breakout, 20%; 1h grading. Native CYCLIC deliberately inherits the selected owner’s style; exact continuity through this generic mandate needs review. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=375, qualified=318, FDG reached=0, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=447. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_CYCLIC_SCORE_BELOW_FLOOR=153,NATIVE_READY=148,NATIVE_SELLABILITY_REJECT=105,NATIVE_PRICE_NOT_FRESH=41`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_CHOKED`.

### EXPRESS

- Designated task/pool: Fast continuation and acceleration with an unexhausted 5-minute move.
- Native strategy: `ShitCoinExpress`; entry `express_native_rideType`; exit `express_native_exit`.
- Registered base setup menu: `PLAN_BASE_BREAKOUT`, `VOLUME_CONTINUATION`, `HIGHER_LOW_CONTINUATION`, `MICRO_FLAG`, `CROWD_FORMING`, `LAUNCH_LADDER_PROVEN`, `ACCELERATING_TAPE`, `FAST_CROWD`, `VOLUME_IGNITION`, `TX_VELOCITY_BREAK`, `MOMENTUM_PULLBACK_5M`, `OPP_EARLY_IGNITION`, `OPP_RS_LEADER`, `HL_RECLAIM`.
- Holding/economic contract: Momentum; 30% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=285, qualified=222, FDG reached=103, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=4003. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_LANE_LIQ_UNHYDRATED_DEFER=1234,NATIVE_NO_CONTINUATION=1038,NATIVE_READY=761,NATIVE_LANE_LIQ_BELOW_FLOOR=316`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### CORE

- Designated task/pool: Native ensemble when specialists agree without a clear winner; qualified generalist fallback.
- Native strategy: `SpecialistBrainBridge7542 core ensemble`; entry `core_native_ensemble`; exit `core_inherit_ensemble_exit`.
- Registered base setup menu: `PLAN_PULLBACK_RECLAIM`, `PLAN_BASE_BREAKOUT`, `PLAN_SWEEP_RECLAIM`, `HIGHER_LOW_PULLBACK`, `RANGE_LOW_BOUNCE`, `OPP_BREAKOUT_EXPANSION`, `OPP_RS_LEADER`, `HOLDER_EXPANSION`, `VOLUME_IGNITION`, `DEEP_LIQ_TREND`, `CROWD_FORMING`, `HL_RECLAIM`, `RANGE_SUPPORT_SWING`.
- Holding/economic contract: FieldManual quality/trend-pullback mandate, 20%; 1h grading. Native ensemble retains separate role.. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=386, qualified=386, FDG reached=0, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=6665. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_CORE_YIELD_CLEAR_SPECIALIST=3818,NATIVE_CORE_OWN_ENSEMBLE_BELOW=1630,NATIVE_READY=1215,FDG_EDGE_7877_RUNNER_CELL_PROVEN_NEGATIVE__6_2PCT_CORE=12`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_CHOKED`.

### MOONSHOT

- Designated task/pool: Asymmetric ignition, launch progression, active liquidity, runner continuation.
- Native strategy: `MoonshotTraderAI`; entry `moonshot_native_runner`; exit `moonshot_native_runner_exit`.
- Registered base setup menu: `PLAN_BASE_BREAKOUT`, `LAUNCH_CONTINUATION`, `BREAKOUT_RUNNER`, `RS_LEADER`, `POST_EVENT_RECLAIM`, `CROWD_FORMING`, `LAUNCH_LADDER_PROVEN`, `FAST_CROWD`, `NET_INFLOW_SURGE`, `GRADUATION_RUN`, `POST_MIGRATION_HOLD`, `BOOSTED_LAUNCH`, `SOCIAL_LAUNCH`, `NARRATIVE_WAVE`, `VOLUME_IGNITION`, `OPP_BREAKOUT_EXPANSION`, `OPP_EARLY_IGNITION`, `SECOND_LEG`, `CTO_REVIVAL`, `HL_RECLAIM`, `SECOND_WAVE`.
- Holding/economic contract: Launch runner; 50% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=1225, qualified=1181, FDG reached=570, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=6394. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_READY=4420,FDG_DATA_INCOMPLETE_DEFER_8136=2496,FDG_EVIDENCE_HOLD_8336_MOONSHOT=1667,FDG_CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136=1609`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### PROJECT_SNIPER

- Designated task/pool: Known first-three-minute launch pool, early structure and liquidity.
- Native strategy: `ProjectSniperAI`; entry `sniper_native_pre_ignition`; exit `sniper_native_fast_exit`.
- Registered base setup menu: `PLAN_BASE_BREAKOUT`, `VERIFIED_LAUNCH`, `LOW_RUNUP_BASE`, `FIRST_PULLBACK`, `CROWD_FORMING`, `LAUNCH_LADDER_PROVEN`, `CLEAN_DEV_LAUNCH`, `SOCIAL_LAUNCH`, `BROAD_DISTRIBUTION`, `NO_BUNDLE_CLEAN`, `LP_LOCKED_BASE`, `DEV_HOLDS_CROWD_BUYS`, `POST_MIGRATION_HOLD`, `HL_RECLAIM`.
- Holding/economic contract: Launch runner; 50% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=0, qualified=0, FDG reached=0, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=1551. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_BIRTH_METADATA_HYDRATING=978,NATIVE_PHASE_HANDOFF=425,NATIVE_SNIPER_TERMINAL=140,NATIVE_PREIGNITION_FLOW_NOT_PROVEN=8`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `DEAD`.

### DIP_HUNTER

- Designated task/pool: Mature liquid pullbacks with bounce/reclaim rather than ongoing collapse.
- Native strategy: `DipHunterAI`; entry `dip_native_reclaim`; exit `dip_native_recovery_exit`.
- Registered base setup menu: `PLAN_SWEEP_RECLAIM`, `SWEEP_RECLAIM_FLOW`, `CAPITULATION_HIGHER_LOW`, `SUPPORT_FLIP`, `MEAN_REVERSION_OVERSOLD`, `SECOND_LEG`, `FIRST_DIP_BOUGHT`, `HOLDER_EXPANSION`, `OPP_DIP_RECOVERY`, `HL_RECLAIM`.
- Holding/economic contract: Sweep/reclaim; 25% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=31, qualified=22, FDG reached=0, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=3230. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_NOT_A_DIP=1769,NATIVE_LIQ_RATIO_LOW=540,NATIVE_MCAP_TOO_HIGH=391,NATIVE_NO_BOUNCE_CONFIRMATION_DIP=192`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_CHOKED`.

### MANIPULATED

- Designated task/pool: Young one-sided flow/bundle evidence, native manipulation qualification.
- Native strategy: `ManipulatedTraderAI`; entry `manipulated_native_pump_ride`; exit `manipulated_native_fast_exit`.
- Registered base setup menu: `PLAN_SWEEP_RECLAIM`, `DISTRIBUTION_RECLAIM`, `SWEEP_RECLAIM_FLOW`, `CTO_REVIVAL`, `ACCELERATING_TAPE`, `FIRST_DIP_BOUGHT`, `HL_RECLAIM`.
- Holding/economic contract: Launch runner; 50% gross mandate; 1h grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=63, qualified=24, FDG reached=2, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=3675. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_TOKEN_TOO_OLD=1233,NATIVE_MCAP_UNHYDRATED_DEFER=1081,NATIVE_MCAP_TOO_LOW=1010,NATIVE_LANE_LIQ_UNHYDRATED_DEFER=113`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### TREASURY

- Designated task/pool: Deep-liquidity turnover and confirmed scalp/range opportunities.
- Native strategy: `TreasuryBrain`; entry `treasury_native_confirmed_scalp`; exit `treasury_native_bank`.
- Registered base setup menu: `PLAN_SWEEP_RECLAIM`, `RANGE_LOW_BOUNCE`, `RECLAIM_AFTER_WEAKNESS`, `MICRO_PULLBACK_TREND`, `DEEP_LIQ_TREND`, `LOW_VOL_COIL`, `MEAN_REVERSION_OVERSOLD`, `MOMENTUM_PULLBACK_5M`, `OPP_DIP_RECOVERY`, `RANGE_SUPPORT_SWING`.
- Holding/economic contract: Range reversion; 12% gross mandate; 5m grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=1537, qualified=868, FDG reached=25, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=3225. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_TREASURY_ROLE_FLOOR=1412,NATIVE_NEUTRAL_MOMENTUM_BALANCED_BOOK=678,FDG_CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136=592,FDG_NET_EDGE_SCALP_COIN_TOO_FLAT_8054=415`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

### CASHGEN

- Designated task/pool: Liquid turnover and cashflow opportunities, continuation/reversion.
- Native strategy: `CashGenerationAI`; entry `cashgen_native_cashflow`; exit `cashgen_native_quick_bank`.
- Registered base setup menu: `PLAN_SWEEP_RECLAIM`, `RANGE_LOW_BOUNCE`, `RECLAIM_AFTER_WEAKNESS`, `MICRO_PULLBACK_TREND`, `DEEP_LIQ_TREND`, `LOW_VOL_COIL`, `MEAN_REVERSION_OVERSOLD`, `MOMENTUM_PULLBACK_5M`, `OPP_CONTINUATION`, `RANGE_SUPPORT_SWING`.
- Holding/economic contract: Range reversion; 12% gross mandate; 5m grading. These are mandate priors, not measured returns or a universal exit time.
- Capital/sizing: native recommendation and contributor sizing flow into the shared canonical size resolver; final-size acceptance required.
- Learning: own lane/setup books plus immutable-entry exact-strategy/canonical outcome consumers. Next-decision change remains unproven in this session.
- Runtime: candidates=730, qualified=676, FDG reached=227, executions=0, opened=0, learning=0.
- Currently invoked: nativeCalled=2166. Currently executes: zero recorded. Currently controls positions: no new live position available to demonstrate it.
- Break/refusal location reported: `NATIVE_READY=1925,FDG_CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136=805,FDG_NET_EDGE_SCALP_COIN_TOO_FLAT_8054=479,NATIVE_REJECTED_BUY=142`.
- Acceptance: **UNPROVEN**, not ACTIVE. Reported runtime status `FDG_BLOCKED_ALL`.

## Remaining strategy/AI findings requiring causal repair or proof

- CYCLIC falls through to the generic FieldManual mandate even though its native module emits CYCLIC_COMPOUND. Review its full entry/hold/exit/label contract before changing it.
- EntryEconomics8329's pre-broadcast check re-reads selected setup/decision price rather than visibly consuming the full sealed candidate economics. Audit both Jupiter and Pump/Raydium fallbacks for exact-strategy continuity.
- EducationSubLayerAI routes specialist outcomes through a smaller consumer path; some branches only update layer counters. Prove native parameters, not merely counters, change for every intended contributor.
- Canonical DNA capture now has immutable position-bound attribution. Its new integration and restart regressions still require the CI execution result; generic token-pattern memory and each native learning policy remain separate review work.
- MemeCausalLearning6568's row schema has no account mode; inspect whether its bounded strategy prior mixes PAPER/LIVE and whether that can influence live authority.
- MarketsLiveExecutor still uses before/after wallet snapshots for parts of settlement and estimated entry principal/fees. Concurrent unrelated movements cannot stand in for transaction-scoped proof. Its fee arithmetic correction does not certify those producers.
- Full all-lane sequence still required: native opportunity -> selected setup -> intelligence contribution -> final-size quote -> sealed intent -> confirmed receipt -> position/exit influence -> canonical net outcome -> correct learner -> subsequent changed decision. Source wiring and synthetic test wins do not establish sustained profitability.

## Forward prediction lifecycle follow-up

Two additional code defects affect the one-hour model grade directly. Five-minute delivery consumed the original pending prediction and acknowledged its observed-only receipt; the deferred prediction was absent from both the next freeze and the Cortex receipt checkpoint. It now remains under the exact episode ID, survives the existing atomic checkpoint with a paired marker, and is released after its first model contribution. The restored grade record determines whether an intermediate receipt must retain it, so restart does not depend on a volatile set.

The labeler also skipped a due revision when net return changed by less than 0.5 points. With model grading deferred to the hour, an unchanged hour return still needs its first grade. Due horizons now deliver evidence independently of return changes. New integration coverage runs the actual delivery and SQLite receipt checkpoint at five/fifteen/thirty/sixty minutes, reloads the durable prediction and grade state, retains the original forecast, and checks one model contribution. This is functional regression evidence; it does not establish a positive live predictive edge. The supplied report's 587 missing checkpoint originals and 239 missing delivery originals identify the affected failure class, not a proven count attributable solely to this defect.
