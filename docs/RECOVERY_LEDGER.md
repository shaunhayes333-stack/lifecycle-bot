# AATE recovery ledger

Working record for the owner's full-system recovery directive. Each entry is pinned to the source version it was
examined at. "Verified" means checked against the 8720 edge dataset (78,859 labelled decisions, 75 h) or a passing
CI test; "Inferred" means reasoned from source and not yet observed live. Nothing here is a claim of live profit.

## Source truth

| Commit | Version | CI |
|---|---|---|
| 1979f19 | 5.0.8720 | capture `aate_pipeline_20261113_035235` was taken on this build |
| aa04e2e | 5.0.8721 | green — last confirmed installable APK at the time of writing |
| 2fd29e9 | 5.0.8722 | compiled, unit tests passed, APK built; smoke test not finished when this was written |
| baedd47 | 5.0.8723 | red — did not compile (`CandidateAdmission8136.kt:722`) |
| f107908, d959d5e | 5.0.8724 | red — did not compile (`LaneAuthority8577.kt:161`, forward reference); fixed in 8726 |
| 9f274ce | 5.0.8725 | not confirmed |

## Defect ledger

| # | Where | Mechanism | Effect | Repair | Evidence |
|---|---|---|---|---|---|
| D1 | `EdgeModel8537.learn` / `target8567` | Target was ln(1+net): variance is charged, so launch coins score far below zero | Positive picks were coins that cannot run (0.1 % ran 100 %); 8715 then let it refuse live | 8722: target = banked outcome, three structure crosses | Verified walk-forward: kept set runs 2–3.7 % (was 0.1 %). Kept set is still −3..−5 % per coin: not a profitable selector |
| D2 | `EdgeModel8537` 8722 note, `ManagedBands8719.managed8719` | Every pulled pool counted as a filled stop | 8722 claimed kept coins banked −1.0 / +1.9 % | 8724: a label through twice the stop distance counts as printed; note corrected | Verified (re-run of the same walk-forward) |
| D3 | `PayingCell8723` (8723) | Cell identity had no lane; outcome used the labelling lane's stop | One lane's labels could authorise another | 8724: one record per lane × origin × age × pool, net as printed, no stop | Verified per lane: CORE, MANIPULATED, MOONSHOT, EXPRESS, SHITCOIN, CYCLIC proven; five lanes not proven and not authorised |
| D4 | `CandidateAdmission8136.decide8136`, `LaneAuthority8577.memeEntryRefusal8626` (8723) | Cell overrode setup-losing, cheat sheet, lane cell, lane-losing guard and all entry-state doors | Broad evidence erased narrower negative evidence | 8724: fixed precedence; cell answers only broader / unmeasured priors | `Aate8723PayingCellTest.precedence…` (source-pinned) |
| D5 | `CryptoAltTrader` paper branch | `markDispatch` before the paper transaction lock | Smoke test `DISPATCH_TERMINAL_CARDINALITY` | 8721 | Verified: CI green on aa04e2e |
| D6 | `EntryEconomics8329.chaseCapPct8329` | Hand caps 8 / 5 / 4 / 2.5 % | 3 of 6 tickets in the 8720 capture ended here | 8723: cap binds only once the lane's chase-miss record proves it protects | Inferred: the capture kept no per-ticket numbers (see D7) |
| D7 | `ExecutionAttemptLease.terminalFail`, report | A failed live buy kept only a reason name and a count | The six 8720 failures cannot be traced to coin, pool, lock % or quote move | 8726: last 16 failed buys kept with safety, pool, age and chase numbers | `Aate8726…aFailedBuyKeepsItsFacts` |
| D8 | `MarketCohort8701` FRESH cohort (8717) | Needs 100 labels of a chain-proven birth; splits the $10k–50k band | The paying cell could never be proven by it | Superseded by `PayingCell8723` (edge-model age, lane record) | Verified on the dataset |

| D9 | `SetupAuthority8535.authorityOf8535` (`else -> SETUP_TRIGGERED`) | `NATIVE_READY_8270:<text>` (a lane READY verdict with no playbook setup) and the three 8720 structure reads were executable entries on their name alone | A verdict or a description of the coin could be the executed strategy with no entry event (8720 runner record: live admits 75 bought the top, 17 won) | 8727: NATIVE_READY needs the observed breakout witness; FRESH_DEEP_POOL / DISTRIBUTED_HOLDERS are token-quality, EARLY_CURVE_AT_HIGH is a near-high read | `Aate8727SetupExecutabilityTest`. Still open: other native opinion ids not in the playbook default to triggered (pinned by `Aate8672…` as intended) |

| D10 | `StopMarkConfirm8418.sellsWithoutRouteQuote8706` | With no route quote inside the emergency budget, a fresh venue read meant "sell" whatever it said | 8727 live, jjaemu: a -15 % lane stop on a bad mark was refuted by the quote 9 times, the 10th quote took 3.9 s, the stop sold at -0.46 % | 8728: the read decides by its value (`venueRefutes8489`) | `Aate8728StopReadDecidesTest` |

### Price audit (8727 live capture + trade CSV, 06:35)

- Fills and accounting: correct. Entry price x token qty = SOL cost at the export's SOL price on every row; sells are chain-finalised with signatures.
- Forward labels: not biased. Deep pools (>= $250k, cap >= $5M, n4,060) read gross median 0.0 % at the label horizon.
- Exit marks: wrong often enough to matter. 24 stop triggers contradicted by an executable quote in 707 s of uptime;
  jjaemu -15 % stop sold at -0.46 %; 6DrxES -25 % backstop filled at -15.3 %. The stop triggers on the worst of two marks
  (`Executor` quote-outage block: `min(livePnl, cachedPnl)`), and the cached mark takes single trade prints. Still open:
  the triggers read prints; the quote / venue checks downstream are what keep it from selling.
- Entries: 5 of the 7 losses were real 13-21 % drops within 10-90 s of buying, after the coin had just run 28-35 %
  (run tape: marsoperator +28 %, EBreGK +35 %). runnerAdmits8297: live LOSS_BOUGHT_TOP 78 of 113 admits.

## Open — not repaired

| # | Where | What | Why it is open |
|---|---|---|---|
| O1 | `LiquidityOwnership8522.assess` (`MIN_LOCKED_PCT = 70`) | Live buy needs ≥70 % locked LP in a report under 2 min old | Owner decision (8725): measure, do not buy unlocked. `LpStateRecord8725` files paying-cell coins by the door's answer. The paying cell is made of pools too young to show locked LP, so it will mostly stay unbought until that record answers |
| O2 | `RunnerDoctrine8584` (`TRAIL_ARM_PEAK_PCT_8630 = 25`, 45 % give-back) | Hand numbers | No price-path data in the captures; replay below shows the path they do not survive |
| O3 | `RunnerGrab7967.TIERS_7967` | +900 % rung sells 67 % of the remainder | A fifth of the position rides past 10x; a 25x move banks ~8x |
| O4 | `EdgeModel8537` | One target (banked outcome, gain capped at +400 %) | No separate P(2x) / P(5x) / P(10x), drawdown or time objectives yet |
| O5 | Directive §3, §5, §7, §8, §11, §12, §13, §14 | Inventory, setup executability, data supply, authority consolidation, retired setups, paper/live parity, dead code, historical replay | Not started |

## Runner replay (doctrine rules as written, before cost and slippage)

`Aate8726BuyTraceAndRunnerReplayTest.whatTheDoctrineBanksOfARunner`

| Path | Banked (multiple of stake) |
|---|---|
| straight to 2x, then collapse | 1.62x |
| straight to 5x, then collapse | 2.8x |
| straight to 10x, then collapse | 6.4x |
| straight to 25x, then collapse | 8.0x |
| +60 %, dip 30 %, on to 2x, collapse | 1.62x (held through the dip since 8719) |
| 3x, back to 1.5x, on to 10x | 1.74x (trail sells at 1.65x; the 10x is missed) |

These are synthetic paths. The captures hold no tick paths of real runners, so the share of a real 5x the bot would
bank is not measured.

## The one proven edge in the 8720 dataset

Not pump.fun, under five minutes old, pool $10k–50k: 91 fillable entries (one per coin per 15 min), +34.5 % ± 11.7 net
as printed with no stop assumed, positive in both halves, ~29 a day, 20 % doubling within 15 min, 2 % at 5x within 15
min. Every other origin × age × pool cell is flat or losing. Whether live can buy it depends on O1.
