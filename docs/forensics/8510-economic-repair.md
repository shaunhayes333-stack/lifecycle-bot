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

## 8512 device evidence, 2 November at 22:27 Brisbane

The attached report is from the implementation parent. Its governor window covers 27 canonical live closes, six wins and 21 losses, PF 0.31, expectancy −0.0025 SOL. Five closes were in the session. Wallet 0.2685 SOL and zero live positions. The 54 elsewhere are paper. The 8510 and 8512 windows differ; neither the wallet delta nor their difference in mean expectancy is an attribution to a particular change.

| Desk | Qualified | FDG allow/block | Tickets | Executions | Governor live closes and expectancy (SOL) |
|---|---:|---:|---:|---:|---|
| BLUECHIP | 991 | 0/45 | 0 | 0 | n1 exp-0.0028 |
| CASHGEN | 420 | 0/78 | 0 | 0 | n1 exp-0.0052 |
| CORE | 301 | 0/0 | 0 | 0 | - |
| CYCLIC | 562 | 0/0 | 0 | 0 | - |
| DIP_HUNTER | 0 | 0/0 | 0 | 0 | - |
| EXPRESS | 161 | 2/62 | 0 | 0 | n2 exp-0.0036 |
| MANIPULATED | 58 | 0/0 | 0 | 0 | - |
| MOONSHOT | 874 | 7/592 | 4 | 2 | n15 exp-0.0003 |
| PROJECT_SNIPER | 0 | 0/0 | 0 | 0 | - |
| QUALITY | 980 | 0/156 | 0 | 0 | n4 exp-0.0045 |
| SHITCOIN | 556 | 4/75 | 3 | 1 | n4 exp-0.0076 |
| TREASURY | 734 | 0/17 | 0 | 0 | - |

Rows combine reporting scopes. Execution counts cannot be joined to historical closes by subtracting columns.

* SHITCOIN now has three tickets and one execution. Its four governor closes average −0.0076 SOL. Three attributed NATIVE_QUALIFIED_ADMIT_8259 receipts show two bought tops, one giveback, and no win.
* CORE has 641 owner-reason rows beginning READY_NOT_EVALUATED_PRIMARY, including 468 behind SHITCOIN. CYCLIC has 523 such rows behind SHITCOIN and 193 behind CASHGEN. Trace the READY resident proposal, selected spine, rescue eligibility and candidate version in BotService.laneEvaluationAllowed7951 and LaneDispatch8418.spineLane8418.
* The six runner prints are restored observations. The C9uwhn mint shows MOONSHOT:Y at the native read, a SHITCOIN structural-risk refusal, an expired unevaluated MOONSHOT handoff, zero window time and no route. The execution economics of that miss are indeterminate.
* Runner replay previously treated any positive depth as sufficient. Its evidence test now requires a dated window, finite net expectation and depth of at least the production 8331 floor of USD 500. This replay is still a refusal classifier. A CURVE/POOL route marker and a peak do not establish a fill or banked gain.
* Buy start-to-broadcast improved to a 4.672 second mean across three measured attempts. Emergency trigger-to-broadcast reached 13.866 seconds. The 3Di8Hz close logged 8.313 seconds in CLOSE_LEASE/queue of 9.524 seconds to broadcast. SellRouteSpine8144 accumulates retry and lease waits in this stage. Signing alone cannot explain it. Trace the individual lease owner, retry timing, earlier venue failures and re-dispatch for this sell before changing retry limits.
* StopFast8288.bounded8288 launched optional corroborating quotes in an unbounded cached thread pool and left timed-out tasks running. The candidate caps concurrency at four with zero queued work and requests cancellation on timeout. This is an optional read. The actual sell still runs through its existing route, signature, lease and finality checks. Its effect on exit latency is unmeasured.
* Heap reached 512/512 MB without a recent crash. Another sample in the same report was 470 MB, of which 342 MB was outside the store census. There were 28 hard trims, five classified ineffective. A heap allocation trace and thread dump are required to attribute retained memory. Shrinking one reported store cannot establish the root cause.
* Cortex priced 891/2489 due marks (35.8%), assessed 4593 and graded 271. The report shows 998 lost, alongside 453 no-source and 348 late label marks. The populations need episode/horizon joins. No Cortex authority increase is justified by these totals.
* The on-device cost book reports MOONSHOT median 7.26% on four measured trips and QUALITY 7.58% on two. The latest SHITCOIN model at 0.0318 SOL gives 5.31%. These are different cohorts. Exact trade-level receipt matching remains open, including 124 unmatched current entry receipts reported by 8512.

The versioned baseline tree manifest includes every tracked path and blob at 8510. It establishes source coverage only. The full registered-component producer/caller/consumer/learning/test map remains incomplete. The updated inventory scripts expose complete unread-function and authority matches and permit writing snapshot outputs separately. The live-awareness report now includes additional specialist implementation files. SHADOW_PAPER covers the ExploreShadow book in this report; other shadow consumers still require tracing. Neither string references nor the historical F_DEAD label prove a runtime status.

### Unclosed release gates

Coverage is still required for every lane, including the additional asset systems and their actual enabled modes; full decision ownership and learning attribution; incomplete-data resume; all supported economic fallback paths; signed-fill and capital reconciliation after restart; protective-sell deadlines and runner banking; chronological 8114/8510/candidate executable replay with costs, uncertainty and drawdown; APK build/test/emulator and release identity. The runner and episode JSONL files and candidate-time route/size observations are still needed for the economic comparison. No after-cost performance result exists for this branch.
