# Pipeline observability findings, 8528 repair work

Evidence: `aate_pipeline_20261104_234105_552.txt`, build 5.0.8527, approximately 27 minutes uptime. These findings describe reporting limitations; they are not proof that every refusal was wrong or every later price rise was executable profit.

## Existing evidence and remaining gaps

| Question | Present evidence | Missing or incomplete attribution |
|---|---|---|
| Where did a qualified candidate go? | Per-lane ownership chain and terminal counts. QUALITY has 1,046 `lostNoTerminal{QUALIFIED}` records. | The old report combines TTL expiration with bounded-ledger eviction. It loses candidate examples and cannot establish whether the underlying trade was blocked. These are candidate records, not unique tokens. |
| Which layer inverted a decision? | Cortex score ranking, strong/refused cohorts, admission and stage reason tables. | One joined decision needs the native, V3 and Cortex raw scores, normalization, each signed adjustment, effective thresholds and the exact overriding authority. Comparing unrelated aggregate counters cannot establish a sign inversion. |
| Is a setup good but entered too late? | Timing Cortex, entry-wait and runner-capture summaries. | A single episode needs discovery, first valid setup, first executable opportunity, decision, queue, quote and fill source timestamps and prices. Setup-specific early outcomes must stay distinct from terminal-profit labels. |
| Is Cortex learning from the right observation? | Pending, missing, hygiene and outcome-delivery counters; some per-model maturity tables. | Join the original frozen prediction, target horizon, accepted source mark, eligibility rejection, delivery receipt and resulting learner revision. `ALIVE` is not proof of an actual parameter update. |
| Is a blocker economically harmful? | Refusal outcomes and matched cohorts. | Compare like-for-like setups, market-cap bands, regimes, horizons and executable costs. Identify the first binding gate and other gates that would still refuse. Avoid counting the same missed opportunity once per retry or gate. |
| How much profit was available and lost? | Runner/peak and realised-return tables. | Contemporaneous route/depth/size/cost evidence and a feasible exit policy. A later chart peak is not achievable PnL. Separate discovery delay, decision delay, fill slippage, early exit and giveback. |
| Are latency summaries comparable? | Brief emergency maximum 6,352 ms; detailed catastrophic trigger-to-broadcast maximum 42,830 ms, including 41,237 ms queue time. | Shared episode identity and explicitly named start/end timestamps, sample population and time window. Different instrumented spans must not share an apparently identical label. |
| Why did a lane's thresholds move? | Canonical floor and hunter-band summaries. | Effective native/canonical thresholds, their score scale, mode, evidence sample, old/new values and the outcome evidence that caused the change. Include discovery exclusions below $2,000/within $2,000-$10,000, rather than showing only tokens that survived intake. |

## Implemented in this working change

- Ownership reporting separates missing-receipt `CAPACITY_EVICTION` from `IDLE_TTL`, with a bounded full-identity sample and last-observation clock. This does not invent a trading terminal when instrumentation lost the record.
- Native floor reporting exposes mode, lane, bootstrap floor, effective floor and score-band sample count. Learning receipts name missing immutable snapshots/scores and invalid bindings, or delivery to the existing score learner.
- Setup timing reports observed/unavailable early marks and its explicit 15/30/60/300-second clocks. Setup plans and completed horizons persist together with the timing book. Early timing ranks setups; it does not manufacture net-profit evidence or promote an unproven setup to statistically supported.

The complete joined decision timeline, common exit-latency clock, executable opportunity-cost accounting and per-learner revision receipts remain outstanding. This change must not be described as completing that full diagnostic contract.

## Validation boundary

The pure-model suite passed 169 tests, including the new setup timing and fluid floor tests. Android Kotlin production compilation passed twice, including the reporting changes. The local Android regression task then stopped before tests because the installed Java runtime lacks jlink; this is an environment failure, not a test pass. Android regression tests and release-APK validation remain CI requirements; these observations do not establish improved realised expectancy or a restored historical win rate.
