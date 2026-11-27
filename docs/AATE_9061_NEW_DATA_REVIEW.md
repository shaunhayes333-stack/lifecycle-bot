# 9060 live baseline and 9061 learning continuation

Owner dataset: 28 November 2026, approximately 03:07–03:10 Australia/Brisbane.
Running baseline: **5.0.9060 @ f66a21ccd**. Changes in PR #93 are a challenger, not the version that produced these receipts.

## What is working

The seven latest closes in the supplied CSV total +0.052515 SOL ($3.78 at the export's $72/SOL), five wins and two losses. One +0.008456 SOL close was MANUAL. The remaining six automated closes total **+0.044059 SOL ($3.17), four wins and two losses**. The entire export still reports -0.003108 SOL (-$0.22). These are different windows, not contradictory results. Fees are exported as zero; the export alone cannot independently establish full wallet-level after-cost returns.

- Split: EXPRESS entered near $20,008 cap, score 29, and closed +75.90% under RUNNER_DOCTRINE_TRAIL_8630. Preserve the successful entry/runner exit path; raising a universal score floor would exclude this example.
- CORE: Fireball +8.89% and 7j1a6959 +23.83%, at approximately $753k and $157k entry caps. These are different populations from SWAN's approximately $5.9k MOONSHOT entry (+19.47%).
- Split's retained fire trace starts around $8,377 cap; MOONSHOT admission is recorded at +1 second, a defensive shadow wait follows, and EXPRESS landing is recorded at +388 seconds. This proves detection preceded the profitable fill. It does not prove buying the first signal would have produced a better executable result.
- Existing lane pockets are populated and served. The native neural challenger reports **zero models and zero predictions** at capture; it cannot be credited with these wins.

## Reproducible dataset audit

Run `tools/audit_native_learning.py` with the two uploaded gzip paths. It reads without modifying them, hashes inputs, preserves missing labels, selects the earliest decision before looking at its return, and prints fixed cohorts rather than mining a winning threshold. One asset counts once per lane/horizon. It also compares exact same-decision 1h/4h/24h pairs and separates quality stamps and LOSTMARK annotations.

| File | Rows | Unique assets | SHA-256 |
|---|---:|---:|---|
| aate_edge_dataset_8537_1795799254576.jsonl.gz | 49,319 | 6,398 | f5ae4e8a4d5235ac068d983592316d141140a084c1ecaa39d2aab449d9974e43 |
| aate_edge_research_8877_1795799257053.jsonl.gz | 180,252 | 6,214 | 11276022952130fd973517b62d7d2c02b5ce3bd8adb5898fad7589a62046f0da |

Neither file contains malformed JSON lines. This is structural validity, not market-data correctness.

The legacy decision file has no setup field; it cannot teach setup-specific timing merely by joining a later winning trade on mint. For example, Split's recorded five-minute CASHGEN research row is not its later EXPRESS fill. The research file has named setups on 63,887 rows, explicit age on only 9,667 (5.36%), path9013 summaries on 17,738, and priceProof9043 objects on 65. Older unstamped rows are not automatically invalid, but they are not interchangeable with the newer witnessed sequence data.

### Promising formation, but not a universal rule

These are broad **historical research `net` labels for first BASE_START decisions**, before applying current lane mandate filters. They are not executed profit, a reproduction of the live miner's managed labels, or a prospective model test. Each horizon uses its own available sample.

| Lane, 1h BASE_START | Priced / all assets | Mean reported net | Median | Mean excluding best 3 | First / second time-half mean |
|---|---:|---:|---:|---:|---:|
| CORE | 353 / 557 | +35.98% | +4.94% | +20.48% | +25.86% / +45.38% |
| EXPRESS | 366 / 600 | +32.69% | +3.31% | +19.62% | +23.58% / +41.42% |
| MOONSHOT | 352 / 577 | +35.01% | +3.20% | +19.47% | +19.48% / +49.03% |
| CYCLIC | 140 / 203 | +1.90% | -2.29% | -4.82% | -5.35% / +9.81% |
| CASHGEN | 68 / 102 | -6.27% | -43.88% | -23.69% | -21.69% / +11.07% |

Formation is a useful candidate for CORE/EXPRESS/MOONSHOT research. It is not sufficient evidence for transplanting their entries into CYCLIC or CASHGEN. Missing prices are substantial: assigning missing outcomes a hypothetical -100% reverses even the CORE/EXPRESS/MOONSHOT broad-cohort means. The script exposes that sensitivity; it does **not** assert the missing tokens lost 100%.

The precise population and horizon matter more than a pooled headline. Even CASHGEN's small PRICED-stamp one-hour subset averages +30.31% (47 assets), but its median is -21.75% and removing its best three makes the mean -25.24%. That is not dependable evidence for scaling it.

### Long-horizon evidence cannot yet choose exits

2,307 of 2,762 rows stamped PRICED at 24h also carry LOSTMARK in their reason. A price quality stamp is not a complete observed 24-hour path. Of CORE's 143 exact-decision BASE_START 1h/4h pairs, 93 later rows carry LOSTMARK and 44 repeat the same net value. EXPRESS has 98/163 later LOSTMARK rows and 49 repeated values. Those pairs do not justify replacing native holding/exit rules with a blanket longer hold.

The current native learner observes target-before-stop marks at 5m/15m/1h, including each native objective. Its six generic targets end at five times stop-plus-cost. That is not a 5,000% tail-capture objective, and endpoint marks are not a sell policy. A runner policy needs causal liquidity/price coverage, partial-exit accounting and actual net settlement comparisons. No 5,000% profitability claim is supported by this export.

## Concrete defects and continuation

### Completed research could wait in memory for an hour

At capture: 959 windows sampled, 860 completed, 720 fully censored, 860 queued, zero stored. Source confirms the native journal was flushed only inside the hourly StrategyLab batch, itself conditional on NORMAL memory. StrategyLab had already run once; the app had been up only about ten minutes. Zero saved observations therefore did not prove broken price decoding, but it exposed a persistence dependency that can lose learning on restart or bounded-queue overflow.

Implemented in this continuation:

- Every 30 seconds, coalesce at most one checkpoint on the **existing single StrategyLab worker**. No new thread, RPC, subscription, inference or order decision.
- Save at most 128 rows normally, 32 under HARD pressure and 8 under CRITICAL pressure. Encode one row at a time rather than duplicating a whole JSON batch in memory.
- Retain a failed batch for retry; roll back a failed append to its previous file length when the filesystem permits. Test prior-row preservation and retry without duplicates.
- Keep the journal bounded. Defer compaction unless pressure is NORMAL and expose storage deferral. Existing hourly training and its memory gate remain unchanged.
- Count proof rejection categories separately: unknown basis, dependent basis, untrusted source, missing venue, conflicting/unknown decimals, and invalid clock/price.
- Count completion causes (entry timeout, missing entry, path timeout/gap, stop and resolution). Missing paths remain censored; saving them does not turn them into losses or winners.

This reduces dependence on the hourly training schedule, not all data loss. A long-running job on the same executor still delays checkpoints; queue/drop metrics expose that. The app must remain running long enough to save. This is not a crash-proof transactional database or a promise that models will immediately meet independent-support thresholds.

### What remains deliberately unpromoted

- No changes in this continuation to live score floors, safety, lane entry decisions, runner trails or sell timing.
- The earlier draft still contains frozen lane population/pocket features, fairer bounded enrollment, and $5 cyclic accounting. These were not present in the profitable 9060 snapshot.
- CYCLIC's existing scalp exit/label contract remains unresolved. The $5 seed and settlement repairs do not themselves supply a successful doubling strategy.
- The neural challenger retains no order authority. It must outperform its linear baseline and the current native policy on later, independent assets and then on prospective, after-cost executions.
- Crypto/external venues require their native prices, direction, fees and setup contracts. Solana launch pockets cannot establish performance for perps, xStocks, forex or commodities.

## Learning method and acceptance

The source already uses chronological, asset-disjoint splits and label-availability embargoes, with train-only normalization and linear-vs-neural comparisons. Preserve those controls. The remaining problem is evidence delivery and matching outcomes to the correct decision/lane/setup, not evidence that a bigger network is required.

Research basis: Han, Huang and Wang, *Model Assessment and Selection under Temporal Distribution Shift*, ICML 2024, https://proceedings.mlr.press/v235/han24b.html. It studies model comparison under changing distributions; it does not validate this trading system. Here, later prospective comparisons must remain distinct from historical pocket selection.

Next-device acceptance: stored observations increase before the first hour; queue/drop and checkpoint/storage-deferral counters are visible; no execution latency or memory regression; rejected-proof reasons identify a measurable source issue; matched lane/setup training support grows without fabricated observations; old live winners remain reproducible under unchanged entry/exit rules. Then compare frozen challenger predictions with later outcomes. Do not promote based on the same dataset used to choose features.

Validation: 261 production-model tests passed locally, including coalesced checkpoint scheduling and failed-append rollback/retry. An Android integration test exercises the actual native journal encoder/decoder, successful target-before-stop rows, censoring and forged-label rejection. Full resulting-commit Android checks are required. The preceding draft ee8d343d passed runtime smoke, but that does not validate this follow-up commit.
