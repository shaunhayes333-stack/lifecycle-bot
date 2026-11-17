# AATE 5.0.8848 — re-evaluate moving tokens rather than permanently rejecting an entry

## Source-level defects and observed evidence

1. In 8847 the previously unwired probation price hook was connected to accepted market observations and the scheduler reawakened previously negative candidates on fresh price/buyer evidence.
2. A second terminal bug survived: `GlobalTradeRegistry.processProbation` rejected and removed a mint after one <=-20% interval, although a sharp pullback may later become a different, profitable rebound setup. Price-only losses are a setup-state update, not permanent hard-safety proof.
3. On probation promotion, `BotService` still gave `admitProtectedMemeIntake` the original `result.initialLiquidity` and `result.initialMcap`, which may both be zero despite newer witnessed pool data. This caused an immediate `INTAKE_PROBATION_LIQ_ZERO_REJECT_4507` on promoted candidates with real updated metrics.
4. 8847's signed release build compiled but historical source-contract tests `Aate8772DiscoveryCadenceTest` and `Aate8283FeatureCacheLifecycleTest` expected the retired exact source expression / LRU deletion statement. The tests need to check the same invariants against the changed implementation: preserve eviction history via probation and invalidate only unsealed evidence.

## 8848 change contract

- On an accepted fresh observed price drawdown >=20% relative to the probation observation anchor, update the *observation anchor* to the lower market price. A later independently observed rebound must be assessed under a new strategy generation. Do not create a live BUY or bypass FDG; actual hard safety bans and quarantines remain binding.
- A price below the anchor without a sufficiently fresh confirmed fact stays in probation and is not permanently rejection-stamped on price alone.
- At promotion, use the latest accepted, timestamped `TokenFacts` liquidity and market-cap facts when available (bounded freshness). Never turn missing data into invented liquidity. If unavailable, keep the existing uncertainty / intake handling intact. The scanner, V3/V4 setup grading, FDG, executable quote and hard safety are unchanged.
- Historical source-contract regression tests now assert the actual generation-invalidation and probation retention behavior, rather than an obsolete literal code line. Add a pullback->rebound unit scenario.

## Acceptance and release blockers

- Compile, unit/historical suite, APK signing, runtime 23 screens, restart/bootstrap and canonical wallet proof must pass on the **same main SHA**. An APK produced by a failed build job is not an accepted release.
- The older 8846 Android stress test reported 491/491 MB Java heap and 387 s of GC under 8,192 seeded durable paper events; do not weaken/remove that load test. This source pass does not claim to fix that separate runtime performance problem until a green smoke proves it.
- Confirm in the next live pipeline: `PROBATION_PRICE_REFRESH_8847`, `PROBATION_PULLBACK_RETAINED_8848`, `PROBATION_PROMOTION_FRESH_FACTS_8848`, `MOVING_TOKEN_CYCLE_SLOT_8847`, re-graded outcomes for the same mint, FDG/quote-to-fill convergence, and net realized after-cost P&L. Neither profitable trades nor 600–1000% winners are guaranteed by a build.
