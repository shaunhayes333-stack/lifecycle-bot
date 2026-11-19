# 5.0.8871 — Cortex learning and native signal handoff repair

Date: 20 November 2026, Australia/Brisbane. Base: main `4e0dc7891c142d8e99de87165cce475596bd2416` / 5.0.8870.

## Required outcome

An actionable classic crypto signal must arrive at admission with its actual strategy, original generation and proposed ticket. A model excluded from authority must not regain a veto through a fallback calculation. An independently captured setup decision must receive its qualified forward outcome even when the expensive Cortex voter graph was not sampled. Price age must describe the price that the decision actually read.

This is an additive continuation of [the 8870 data and execution repair](AATE_8870_LIVE_DATA_AND_DECISION_REPAIR.md). Crypto keeps its classic signal engine; meme keeps its own specialist and strategy books. Shared evidence contracts improve both without merging performance histories, inventing model predictions, resetting learning, adding a new scoring system or increasing live capital allowances.

## Evidence and limits of attribution

The supplied 8869 pipeline report identifies 416 crypto submissions, four allows and 412 blocks; 195 blocks are named `LIVE_NON_POSITIVE_EXPECTANCY_7828`. Its Oracle reports 966/966 REFUSE, DEGENERATE and ADVISORY. The report does not expose the estimate basis for each of those 195 decisions. It therefore does not establish 195 measured negative trades, nor prove that every one would become profitable after this repair.

The same report counts 3,150 playbook-only unsampled captures, 1,033 forward deliveries with an unavailable original Cortex prediction, and six LanePlaybook labels versus 541 ForwardOutcomeModel labels. These counters are not joined episode identities and must not be treated as a one-to-one causal tally. The source nevertheless contains a definite disconnected path: an unsampled decision captures a playbook episode, but its later outcome can be acknowledged as missing a full Cortex prediction before that episode learns.

The run also reports 1,557 global sampling grants in 3,604 seconds despite the existing six-second interval, a mean capture lag of 359,188 ms, and 1,564 late voter reads. The old reservation code allowed a backwards decision timestamp to reopen a grant. This is a reproducible scheduling defect, consistent with the excess grants; there is no per-grant runtime trace proving how much of the measured lag it caused.

The existing Cortex scoreboard is insufficient or inverted on several lanes. This release does not turn those results into live model authority. Actual profitable trading still requires forward execution and realized closes from the installed build.

## Engineering contracts and implemented changes

### 1. Preserve a real classic crypto qualification across the handoff

Files: `perps/CryptoNativeSignal8871.kt`, `perps/CryptoAltTrader.kt`, `perps/CryptoStrategyCandidateBooks7803.kt`, `engine/cortex/CrossAssetCortex7931.kt`, `engine/truth/CandidateAdmission8136.kt`, `engine/truth/CanonicalAssetEntryContract6551.kt`.

**Defect.** Crypto records a READY classic strategy, but the downstream adapter reconstructs a TokenState and admission reads the meme specialist candidate book. The classic qualification disappears. The adapter also omits the proposed ticket, and later sealing substitutes an unrelated candidate generation. A qualifying signal can therefore arrive as unsupported or be costed for the wrong proposal.

**Repair.** The actual actionable-long producer freezes a small typed witness containing asset, classic tactic, side, generation, score, confidence, the existing dynamic floors and dated signal price. Admission accepts that witness only against the same strategy's READY record, same generation, same asset and scores, and the existing 45-second READY lifetime. Static markets, desk overlays, absent records and a previous generation cannot inherit it.

The adapter builds the admission using the original candidate version and requested size, and passes that assessment through to the live gate and crypto refusal classifier. The native witness supplies only the native qualification and its original floors; ordinary current costs, expected move, safety, source age and negative outcome evidence remain in charge. READY by itself does not become positive statistical evidence or an order.

A held/shadow assessment retains its existing policy and the same exact crypto inputs. If that policy requires a separate assessment, the refusal classifier receives the admission actually evaluated there. A verdict-only memo cannot substitute the earlier live assessment for that different policy result. The captured hold remains in the final gate.

**Behavioral acceptance.** A candidate with an existing independent positive edge and feasible economics changes from `NO_SUPPORTED_EDGE_8136` to `NATIVE_QUALIFIED_ADMIT_8259`, with the actual strategy and ticket preserved. The same candidate without independent live evidence remains refused. Wrong asset, chain, side, tactic, score, generation, future clock, expired READY, genuine losses, missing execution facts, negative economics and hard safety remain negative controls. Existing feed eligibility is preserved; no new short signal-age cutoff is introduced.

### 2. Stop an excluded Oracle influencing either side of fallback expectancy

Files: `engine/truth/CurrentCandidateExpectancy7832.kt`, `engine/truth/CanonicalAssetEntryContract6551.kt`, `engine/truth/LearnedAdmissionInputs6909.kt`.

**Defect.** Both assembly paths correctly exclude an unavailable, unproven or degenerate Oracle from providing numeric expectancy, then still feed its win probability into the current-candidate fallback. That hidden probability can flip a positive candidate into a veto or make a negative candidate appear acceptable.

**Repair.** The same numeric-authority decision now controls the probability input. An excluded probability uses the fallback's existing neutral behavior. Authoritative Oracle evidence retains first precedence, terminal forward evidence retains second precedence, and the existing candidate prior remains last. No score, confidence, loss threshold or capital allowance changes.

The canonical crypto path records a bounded estimate-basis counter and refusal detail: actual strategy, lane, version, ticket, price clock, Oracle authority/reason, terminal-forward source/sample count and fallback arithmetic. A future refusal can be attributed to an authoritative estimate, terminal forward result, candidate prior or unavailable evidence.

**Behavioral acceptance.** Tests cover both boundary directions, absent/invalid probabilities, preservation of authoritative and terminal negative estimates, and both actual assembly call sites. A positive fallback is still an estimate, not a proven profitable trade.

### 3. Let unsampled setup episodes learn through the existing durable outcome route

Files: `engine/cortex/Cortex7885.kt`, `LanePlaybookDecision8871.kt`, `LanePlaybook7907.kt`, `LaneSetupEpisodes8372.kt`, `ForwardOutcomeDelivery8404.kt`, `engine/truth/ForwardReturnLabeler7731.kt`.

**Defect.** Sampling the expensive voter graph also unintentionally determines whether an independently captured strategy setup receives its later result. Missing a full Cortex prediction is acknowledged without teaching the setup that was actually captured.

**Repair.** Only the existing unsampled/retired capture branch attaches a small immutable playbook decision proof. It freezes the original episode, cached eligibility, executability, dated input price, target, cohort and latency status. It does not rerun the voters, invent a prediction, perform an RPC or grant model authority.

The existing forward-delivery outbox carries this optional proof. A compatible reader leaves historical and fully sampled deliveries on their existing path. The setup consumer validates exact identity, original dated price, original target, typed source marks, the existing 5–60 second executable fill rebase, finite outcomes, flat-mark exclusion, plausibility and refusal-episode rules. A changed price at the genuine rebased fill remains legitimate; it must not be confused with changing the decision's original price clock.

The existing single playbook checkpoint worker saves the existing books, episode sidecar and exact delivery receipt atomically, processing up to 32 waiting deliveries per pass. The producer receives acknowledgement only after that durable receipt. A failed store retains producer ownership and retries without spinning or grading a second time. Missing full-model prediction no longer consumes an otherwise qualified setup outcome. Full Cortex calibration and model grading are not fabricated by this route.

Independent captures also request the existing two-minute pending checkpoint, so a restart before the target arrives does not depend on some other decision receiving a voter sample. A compact per-asset/lane episode clock preserves the six-hour rule when the bounded grade-detail map evicts an older record. Its retention is time bounded and pruned; old snapshots restore only clocks justified by their actual retained history. Successful book mutation receives a receipt even if that delayed episode's detailed trace is immediately evicted, preventing an admitted outcome from being applied again on retry.

An original already owned by the durable producer reserves a short-lived lease in the existing in-flight map. It does not re-enter the capacity-limited pending queue, where newer decisions could immediately evict it or be displaced themselves. Exact identity conflicts still reject the delivery; another callback's existing lease causes retry. The worker releases only its own lease, and hygiene exclusions retain their disposition even after the original pending row was evicted.

**Behavioral acceptance.** A source-qualified outcome for an actually captured unsampled setup must change its existing strategy record; duplicate/restart delivery must not change it twice. Tests cover restart before target maturity, failed commits, capacity eviction, a delayed admitted outcome whose detail is evicted, exact six-hour expiry and the existing admitted exception. Wrong episode, missing capture, malformed proof, invalid/future original clock, unfilled/late entry, flat result, wrong horizon and excluded eligibility must not grade it. The full-model grade/authority count must remain unchanged when only a setup decision existed.

### 4. Preserve cross-chain asset identity in outcome proofs

File: `engine/cortex/CortexGradeBook8144.kt`.

**Defect.** A decision ID is `asset|lane|time`, but the asset may itself be `chain|address`. Taking the substring before the first separator reduces an EVM asset to its chain name, rejecting a valid mark for the full asset.

**Repair.** Parse the final lane and time suffixes from the right, retaining the complete asset identity. Exact mark, decision and horizon checks remain unchanged. Tests accept a correctly qualified cross-chain asset and reject a different chain, asset, lane, timestamp or horizon.

### 5. Bound expensive Cortex work by elapsed time

Files: `engine/cortex/CortexSampleBudget8871.kt`, `engine/cortex/Cortex7885.kt`.

**Defect.** The global, prewarm and admitted reservation clocks use candidate episode timestamps. Out-of-order episodes can rewind those clocks and obtain extra work grants, adding queue delay and late predictions.

**Repair.** Use monotonic elapsed time for work reservations, with an atomic bounded retry. Keep the existing global six-second, prewarm twenty-second and admitted two-second intervals. Market observations, decision IDs, outcomes and all evidence clocks retain their actual wall-clock meaning. Setup capture and its independent outcome route do not depend on receiving a full voter sample.

**Behavioral acceptance.** Tests cover exact interval boundaries, a thousand out-of-order decision arrivals in one elapsed slot, the existing ten-per-minute global bound, clock regression, negative monotonic origin, nanosecond wrap, concurrent claimers and the independent admitted/prewarm budgets.

### 6. Bind price age to the frozen asset and value

Files: `engine/ObservedPriceIdentity8871.kt`, `engine/cortex/Cortex7885.kt`, `engine/truth/CandidateAdmission8136.kt`.

**Defect.** Both assessment and candidate admission take the maximum of a TokenState price timestamp and a mutable registry timestamp. A fresh registry mark for a different price can thereby refresh the old frozen input. A queued assessment can also borrow an event that occurred after its input time.

**Repair.** A dated corroboration can supply the clock only for the same canonical asset and exactly the same finite positive price, observed no later than the input time. A valid original clock survives older or absent registry data. Cortex freezes this clock before reading its voters and measures age at actual completion. A newer different price requires a new decision input rather than a rewritten timestamp.

This does not widen any execution quote lifetime. The 8870 pre-build and pre-send quote boundary still validates the actual order's source clock, mint, raw input/output, impact and existing 30-second maximum; an unsigned stale quote can be refreshed once before sending. The Cortex's existing three-minute decision-label eligibility is a learning rule, not an executable quote lifetime.

### 7. Treat optional Solana owners as unknown ownership, not absent balances

Files: `network/SolanaTokenAmounts8870.kt`, `network/HeliusPushSwapParser7277.kt`, `engine/HeliusCadence8361.kt`.

**Defect.** Solana token-balance `owner` is optional. Ignoring an ownerless observed row makes a missing aggregate look like zero. That can invent a full sale, buy, or wrapped-SOL spend even when the exact raw amount was decoded correctly.

**Repair.** Parse the paired pre/post arrays, account identities, actual string owners and mints together. An unread amount/owner remains unknown for the affected mint; unrelated proven mints remain usable. Known legacy owner rows remain compatible. A successfully decoded exact zero contributes nothing to any possible owner even when owner is omitted; this preserves genuine full sales without inferring account authority. Malformed or positive raw amounts cannot be overruled by a fallback zero. Genuinely absent accounts and explicit owner changes keep their actual meanings. An `unreadMints` set avoids allocating watched-owner × unknown-mint entries. Cadence separately preserves its existing exactly-one-base-asset proof.

**Behavioral acceptance.** The same 16 initial push/amount tests fail seven cases on 8870 and pass on the repair. The expanded Cadence and exact-zero tests bring the focused total to 23; replacing the zero fix with its preceding helper makes the two new zero regressions fail. This is a schema/ownership interpretation defect, not evidence of a general base64 failure. The official [Solana TokenBalance type](https://solana-foundation.github.io/solana-web3.js/types/TokenBalance.html) explicitly marks owner optional.

## Release verification and forward acceptance

The preceding 8870 tree compiled and signed successfully, but its required/full regression runs exposed three source-contract assertions needing alignment with the repaired code. Those assertions are updated while retaining their substantive cache, price-clock and lane-backing checks. No historical failure baseline is reset or widened. The required suite now includes `Aate8871*` in addition to `Aate8870*`.

The preceding runtime run also exposed a test-harness transition race: it accepted an old Splash hierarchy alongside later Main focus, then sent BACK to that newly opened Main before the next explicit launch. The captured failed hierarchy is the launcher; the later restart hierarchy renders the real dashboard and the process remained alive. The harness now requires the focused activity's own layout and skips synthetic BACK only during Security/Splash handovers. The Main rendering assertion, ordinary screen BACK checks and process-liveness checks remain strict; fixture tests reproduce the previously accepted mismatched snapshot and reject it.

8871 acceptance requires source guards, new behavior suites, existing affected tests, the complete historical delta, release build/signing, upgrade continuity, and runtime acceptance for the exact published commit. A failed or merely compiled APK is not accepted. Test results and artifact identity should be read from the exact commit's CI evidence, not an older version's passing job.

On the installed forward run, compare opportunities through each boundary: actual classic READY, canonical candidate with matching generation/ticket, estimate basis, actual admission/refusal, sealed ticket, submitted signature, confirmed fill, original strategy ownership, exit and final realized net result. For learning, compare qualified setup captures, durable setup receipts, duplicate/rejected dispositions, existing strategy-book updates and independently validated model authority. Count exact episodes rather than mixing rolling windows or cumulative counters.

The desired trading result is improved realized net return with acceptable loss/drawdown under the existing budget. More allows, labels, transactions or green background metrics alone do not establish that result. Remaining 8870 follow-ups—dated conversion availability, measured exit latency, full newer Pump quote-unit support, and EVM decimal custody—remain explicit work; this release does not claim them solved.
