# 8510 economic repair investigation

Status **NOT COMPLETE**. This branch has no measured after-cost live improvement.

| Source | Frozen identity |
|---|---|
| 8114 control | 379ad2fa0bbbd45b431409d5a3a6ac600f0a32f6, both version files read 5.0.8114 |
| Requested baseline | fba703a65792decf5f80f85e46f786d3ac12ee28, 3,605 tracked paths, 1,721 production Kotlin files, 1,293 JVM test Kotlin files |
| Implementation parent | b0aaa261cf5626acef544794151384fa8aceaca0, 5.0.8512 |
| Primary report | aate_pipeline_20261102_160836_070.txt, revision 65, 364 seconds uptime |

## Economic baseline

The governor's four chain-finalised closes lost an average 0.0039 SOL per position. The report shows 0.3301 SOL in the wallet, zero live positions, and 54 paper positions. Its ten restored 4x runner observations divide into two data deferrals, five gate refusals and three admitted without a purchase. The report alone supplies no counterfactual sale proceeds. SHITCOIN shows 131 qualifications, nine FDG blocks and zero approvals. This does not identify what happened to 122 individual candidates because the counters do not establish a one-to-one cohort. MOONSHOT has two tickets and one execution.

The reported 4.53% MOONSHOT cost is modelled at 0.0273 SOL, with a 0.000342 SOL fixed cost per leg, zero reported liquidity, zero estimated impact, and no measured comparator for that row. The last BLUECHIP entry cost line is a different ticket. Neither is a paid round-trip receipt. The 14,428 ms start-to-broadcast sample includes 5,477 ms from quote to built transaction and 1,058 ms from build to simulated. Confirmation took another 893 ms. One attempt cannot establish a latency distribution.

## Defect and acceptance ledger

| State | Path and function (under lifecycle_apk/app/src/main/kotlin/com/lifecyclebot) | Source evidence and affected consumer | Next proof |
|---|---|---|---|
| Changed, awaiting Android verification | engine/truth/EntryEconomics8329.preBroadcastRefusal8329, engine/Executor.prepareJupiterBuy8090 | Mint-wide latest admission could come from another lane. Jupiter could subsequently replace the checked quote after a simulation retry. Compare the final quote to the executable mark of the supplied ticket. Stop if the units, generation evidence, or candidate economics are unavailable. | Full route tests including resumed valid evidence, supported alternate setups, adverse re-quote and ticket expiry. Measure added refusals by reason. |
| Changed, awaiting Android verification | engine/SizingQuoteRecheck8153.quotePrice8167, engine/EntryContinuity8418.refusal8418 | With a cached market mark and missing decimals or SOL/USD rate, the terminal route-price check could substitute the cached mark. Require a computable route price. | Aate8167PipelineRepairTest and Aate8418EntryContinuityTest. |
| Changed, awaiting Android verification | engine/truth/EntryEconomics8329.consumed8329 | A target of 120 from 100 with entry at 105 leaves 14.286% gross. The baseline gives 15% and passes a 15% hurdle. | Aate8329EntryEconomicsTest, plus chronological distribution of affected fills. |
| Changed, awaiting Android verification | engine/Executor.tryRaydiumBuy7325 | The direct Raydium fallback can send before the Jupiter terminal checks. It could report a price-derived wallet quantity while token indexing lagged and label the direct fill as PumpPortal. The fallback now checks the sealed decision and quoted output. A missing observed delta remains pending. | Test all three fallback entrances, submitted-signature recovery, wallet drift, top-up and final receipt. Separately trace perps/crypto/CryptoUniverseExecutor. |
| Changed, awaiting concurrency verification | engine/truth/KeylessMarks8359.noteLabelDue8418, pruneLabel8418 | Concurrent read/put of a deadline could shorten a renewed subscription. Concurrent capacity pruning could remove a renewed entry or calculate a negative count. Update atomically and condition eviction on the observed value. | Test concurrent registration and expiration, then measure due/priced by horizon. |
| Previously changed, economic effect unverified | engine/truth/CandidateAdmission8136, engine/TokenMetricStageRouter | 8511 changed the ordering of stage waits and alternative setup evaluation after the frozen baseline. | Run identical safe/unsafe/unknown fixtures across 8114, 8510, 8512 and candidate. |
| Open | engine/truth/ForwardReturnLabeler7731, engine/DexBatchLane8287, engine/truth/KeylessMarks8359 | 125 of 549 due labels priced. A subscribed mint or a completed request does not establish the source observation time or target-horizon coverage. | Audit provider caches per source and replay delayed, duplicated, stale, missing and out-of-order responses. |
| Open | SpecialistBrainBridge7542, SpecialistElection8136, SpecialistOwnership7951, HandoffReceiverTasks8153, BotService, FinalDecisionGate, ExecutableOpenGate | Native qualifications greatly exceed approvals for multiple desks. Cohort identity and terminal handoffs remain unproven. | Capture every decision generation, terminal reason and wake-up through reservation and settlement. |
| Open | AsyncStrategyLab, StrategyHypothesisEngine, LanePlaybook7907, Cortex, V3/V4 and native brains | No demonstrated held-out after-cost superiority of a promoted policy. | Reconcile the 8491 index, actual effect on sealed decisions and learner acknowledgements by strategy and mode. |
| Open | RunnerGrab7967, RunnerPlan8153, ExitTruthArbiter8144, ProfitExitTruth8163, ProfitEscalation8189, Executor | No matched proof of obtainable size, ordinary volatility tolerance, executable partials, final fees, released capital and exactly-once outcome for the runner series. | Replay and test entries through landed partial/full exits, restart, re-entry and learner attribution. |

## Coverage limits

All four requested inventory scripts ran on the frozen tree. triage_unwired found 4,288 public functions without an external caller by its heuristic. audit_unread_intelligence flagged 283 queries across 117 modules. triage_dead is declaration-shape triage. The authority audit assembled 6,763 lines. None of these establishes runtime reachability or economic effect. The individual component inventory, per-trader fixtures and operating-state reconciliation across every market remain open. A reproducible path list is available with git ls-tree -r --name-only at the pinned SHA.

The full three-policy replay still requires candidate-time market observations and executable route/depth/cost data joined by candidate generation, followed by subsequent tradable quotes and settlement evidence. The historical corpus sample contains retrospective bands and price history. No complete timestamped executable tape was established in this investigation. Preserve missing coverage in any comparison.

Local Python CI discovery ran 54 tests successfully. The Android Gradle wrapper stopped before compilation because services.gradle.org could not resolve. No local Android SDK, emulator, or APK was available. Full Kotlin and emulator runs, controlled restart, safety and mode separation, all trader fixtures, out-of-sample replay, drawdown and independent live profitability remain open. No promotion of Cortex authority or change to operator settings is proposed.
