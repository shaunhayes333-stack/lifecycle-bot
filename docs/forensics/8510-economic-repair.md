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

### Runner and receipt corrections

The 8512 capture report counted a runner only when its observed gain exceeded or equalled 400%. Since the producer computes (price / entry − 1) × 100, its reported “4x+” population actually started at 5x. The archive classifier already used +300%. The candidate aligns the live observation and restore threshold with four times the entry price. Existing trace files cannot retrospectively supply previously unrecorded 4x–5x runners. RunnerGrab7967's separate +400% signal remains a distinct policy.

RunnerCapture8017 also attributed a later paper close to the same mint's live row because the LIVE flag was sticky while the return was overwritten. The candidate keeps the selected live position's mode and identity together, clears unrelated basis, and uses the accepted mark's source time for peak-in-hold evidence. An unknown peak time earns no capture credit. EntryReceipt8500's legacy mint fallback now consumes an entry only when the open-time window matches and the conditional removal succeeds. Exact position IDs still match directly. The 124 unmatched-current counter includes events outside the 27-close governor window; these fixes do not establish how many of those events were affected.

Updated behavioural fixtures cover the 4x boundary, absent peak timing, live/paper contamination and out-of-order legacy receipts. None of these marks or replay labels is a settled fill.

### Pricing and protective traffic

The 8512 off-watch worker reported a maximum batch duration of 329,896 ms across a 45,000 ms mark-freshness window. ForwardReturnLabeler7731.fetchOffWatchMarks7737 used to assign the same end-of-batch timestamp to all its earlier observations. An old provider response could thereby acquire more than a full horizon of apparent freshness. The candidate publishes each fetched Dex/Jupiter snapshot on its own response, preserves curve observations on their native account-clock path and atomically rejects older writes. Where the API supplies no trade timestamp, response receipt is still only an observation bound. The foreground Dex pair lane and any upstream caching still need auditing.

The same worker's positive limiter wait caused DexscreenerApi.batchPriceFetchTyped8087 to send the held-position traffic header for background label requests. The label path now explicitly uses ordinary HTTP priority while keeping the bounded limiter wait. Existing held-price callers retain their priority. Provider starvation, restored queue continuity and improvement in on-device label coverage still require behavioural evidence.

### Admission, authorisation and terminal selection

The 8511 selection change left two downstream re-decisions in the 8512 source. TradeAuthorizer.authorize and LiveEntryEvidence7863.capture recomputed entryWait8506, with the latter consumed by Executor.liveBuy. Both could reject the exact soft wait admission had already evaluated. This also prevented a supported alternate setup from receiving its entry receipt. The log's STAGE_UNRESOLVED ticket drop is a different case and must still defer.

The candidate now attaches admission's evaluated stage and wait to its existing proof, then carries that proof in ExecutableOpenGate.ExecutionIntent. Trade authorisation and execution compare the fresh read against the admitted live mint, lane, generation, stage, wait and proof lifetime. Only the four named soft waits can match. Rug danger, unresolved structure, missing flow, different or expired evidence retain their existing binding outcomes. The stage router now gives rug danger and unknown structure precedence over zero participation. The candidate also uses the admitted gross move for the final cost comparison when that proof exists. An unchanged price can still become uneconomic if costs rise.

Aate8511StageWaitOrderTest exercises production admission through the shared decision check, including wrong lane, changed generation, changed read and expiry. Aate8506MemeSelectionTest covers overlapping danger/missing-flow cases. Source review proves the duplicated branch existed; it does not establish the number of profitable orders it suppressed. Remaining end-to-end cases include restarted execution and all twelve lane-specific admission → landed fill → exit → learner paths.

### Missing movement was treated as positive edge

StrategyMove8153.expected8153 and NetEdge8030.expectedMove8035 awarded the full lane target when both observed range and the positive hour return were absent and no selected strategy supported a move. A candidate with no observations could therefore get a larger forecast than the same candidate after receiving a small actual move. The 8512 C9uwhn trace includes MANDATE:50.0/bars0. That particular runner was refused. Its trace illustrates a possible input to the defect rather than a realised losing trade caused by it.

The candidate uses the existing move-history defer when that estimate is absent. A qualifying measured strategy still supplies its own forecast. Available candidate movement remains capped by the lane mandate. The existing request for candle and hour evidence and the corresponding re-evaluation keys serve this defer. The crypto lane reports absent movement or failed economics explicitly. Aate8035CoinMoveTest previously required an unsupported 50% forecast. Its replacement and Aate8418EconomicsTruthTest cover the missing-data boundary, an observed move, and supported strategy evidence over the playbook lanes. The effect on actual opportunities remains to be measured.

### Independent finalized baseline

Solana mainnet finalized transaction metadata reconciles all 103 transactions between the two supplied report timestamps. Opening signer balance 0.330115272 SOL; closing balance 0.268506211 SOL; wallet change −0.061609061 SOL. There are 22 complete zero-to-zero token cycles in the interval, with four positive and 18 negative after token-account deposits are separated. Their combined adjusted movement was −0.056414461 SOL before standalone transfers. Of the 103 transactions, 28 sent money to the app's configured fee wallets.

Three complete buy/sell pairs match signatures printed in the 8512 report. Each sold the exact raw token quantity bought. Their matching token accounts were subsequently closed and returned to the signer. Each buy had one matching transfer to each configured fee wallet, for the integer-lamport fee share specified in BuyAppFee8418, within 30 seconds of its block timestamp. The three combined cash results including those transfers and closure fees were −0.012503093 SOL. The separate transfers carry no on-chain position ID. The private workbook includes the signatures and join method. These results describe the baseline only.

Local production Kotlin compiled on the preceding draft tree. A missing jlink executable stopped Java compilation there. A complete JDK and Android 34 SDK were installed locally; release acceptance still depends on checks against the final candidate and complete economic evidence.

### Compounder ownership, restart and laboratory authority

CASHGEN and TREASURY deliberately share CashGenerationAI mechanics. SpecialistBrainBridge7542 attributed its result to CASHGEN, while the evaluator fetched TREASURY's loss patterns, score expectancy, calibration, edge card and exit-tuner factors. BotService already knew which compounder won before calling the same evaluator. The candidate threads that owner through both real callers and uses it for these decisions, position registration, exit-cost/hold reads, edge-card updates and recovery. The 8512 CASHGEN funnel alone cannot say how often a foreign score book changed a verdict.

PositionPersistence did not serialize the compounder's target, stop and raw entry basis. Its restored tracker used fixed +8%/−6% thresholds. BotService's alternate recovery called openPosition, which reset holding time and repeated opening side effects. Both paths now rebuild the tracker using persisted terms and its original entry time. Rows predating the fields retain their previous path-specific fallback thresholds. Repeated recovery and persistence of a previously raised trailing stop still need device-level coverage.

AsyncStrategyLab.rebuildReviewedBiasCache derives numeric biases from text in symbolically reviewed proposals. A proposal mentioning SIZE_UP could contribute 1.25 to StrategyHypothesisEngine.getSizeBias. FinalDecisionGate multiplies its proposed size before the later live risk ceilings. There is no per-proposal live realised-profit field on that symbolic review. The candidate permits the existing experimental multiplier in paper/shadow, retains defensive reductions in live, and caps this specific source at 1.0 for live or unknown mode. The mode-specific hypothesis outcome books and real-money risk policy still own earned growth. Other lab, Cortex and V3/V4 promotion paths remain subject to the full authority audit.

The 64041990 draft introduced four Aate8343RunnerGrabTest failures. The shared chase function treated a scout's absence of a prior admitted price as a failure. The correction restores pre-admission evaluation while retaining the mandatory price comparison immediately before live submission.

### Compounder trailing exits and validation

Both CashGenerationAI exit evaluators tested the **current** percentage gain against the 12% arm threshold. An entry at 100, peak of 114 and retracement to 104 therefore missed the 104.88 trailing stop. Recovery kept the peak but reset the trail to the original hard stop. The correction tests arming against the observed position high and reconstructs the trail from that high when restoring the tracker. The regression runs the restored position through checkExit at 105 and 104 in CASHGEN and TREASURY. An actual landed sell for this price sequence remains unverified.

Aate8030NetEdgeTest previously required the literal TREASURY in the scalp cost lookup, contradicting the elected CASHGEN owner. Its replacement runs the live exit-floor calculation for both desks at the same stake and checks the attributed cost and coverage. JVM unit test workers now resolve only the loopback host from a test-only hosts file. No operator configuration changes.

The two supplied reports and the bundled 500-row historical corpus still do not contain a joined, timestamped executable quote and route history for the three-policy comparison.

### 8513 runner admission and hydration

RunnerGrabLane8343 used two records for an admission. The native decision cache checked its mode and version, while the subsequent lane door, anti-chase and route-minimum consumers checked only the mint, lane and age. They now require the matching current verdict, mode, version and admission time. A native runner also used the full lane mandate if NetEdge8030 returned an unverified move. It now requests evidence and requires a priced forecast. A runner slot excludes positions from the other mode. No observed loss has been causally attributed to these branches.

HydrationReeval8343 previously removed a pending mint after any call to reviewNative8343, even if the requesting desk was absent or advisory. The queue now tracks which requesting desks received an authoritative read, consumes them individually and expires the remaining request on its existing deadline. This schedules an evaluation only.

EpisodeTrace8395 now keeps pre-election records separate by mode, rejects a mismatched disposition, persists its attempt ID and per-event material time and conditionally removes its own secondary-index bindings on eviction. Old JSONL rows lack the newly retained fields. These attribution tests do not establish exactly-once learning for all consumers.
