# AATE 5.0.8845 — evidence-led discovery/decision repair

## Baseline and problem reproduction

- 8840 device pipeline: FDG 0/1,092 allowed, 0 executor live entries; SHITCOIN FDG negative-model refusals 138; EXPRESS negative-model refusals 74. These are **diagnostic counts**, not unique missed trades.
- 8840 pipeline: `BuyerFlow8666` had 31,930 accepted swaps, but the `FlowRead8546` two/five-minute tape had known flow for 444 of 4,449 meme setup evaluations; 1-minute bars were ready in only 165 of 4,449. Reading a 60-second stream as five-minute candles would manufacture evidence.
- Supplied 17 Nov 61,087-row edge dataset: 59 recorded 100%-plus returns with a chain witness, reported liquidity at least $1,000 and model epoch at least 8801; 57 received negative predictions. These are labelled *mark* outcomes, not proof of a fillable live 100%-plus sale.
- 8844 exact-commit runtime smoke **failed**: `MainActivity.onCreate:985` was inflating the 7,050-line main layout while CPU contention delayed runnable main thread; 23-screen check recorded ANR, then restart never produced `SERVICE_BOOTSTRAP_READY_6516` / loops. 8844 APK must **not** be accepted.

## Source repair in this change

1. `market/BuyerFlow8666.kt`: publish a strictly time-fresh **60-second signed/price-witnessed swap-priority mint list**. Excludes inactive walletless/unsigned inferred snapshots. No fake five-minute observation.
2. `truth/MemeStrategies8546.kt`: scan those fresh mints in a bounded 48-slot priority pass in addition to the original trade-tape and round-robin pass; record a distinct `SIGNED_DEMAND_FORMING_8845` *paper/forward-label-only* family. Requires new independent buyers, positive bought SOL in successive 15-second windows, acceleration, diversified size and young age. It explicitly is **not** a price-reclaim claim and has **no live authority** until its own executable after-cost forward record pays.
3. `truth/CandidateAdmission8136.kt`: when the candidate's **selected and fired** setup has a supported (n>=30, non-suspect) **own** after-cost lower bound, use the better of that bound+cost and its heuristic gross move. This corrects two contradictions: an alternative setup with a verified forward record was deferred because the original (now rejected) setup had a finite forecast; or a supported setup was refused on the weaker hand estimate. This **does not** borrow unrelated setup/cell evidence or bypass the cost-margin, quote, route, wallet, sellability, hard safety, structural-loss or live-realised safeguards. Preserve the older unformed-cell fallback where both native forecasts were unavailable.
4. `.github/workflows/runtime-test.yml`: retain all 23-screen, doze, restart and economic-spine checks on the same APK, but run the cached API30 emulator on four cores. 8844 diagnostic showed the main thread runnable and CPU-starved (not a Java monitor deadlock); this improves test realism but **does not claim to fix the app ANR** without a passing 8845 on-device test.

## Verification and limits

- `Aate8845SignedDemandAndDecisionTest` verifies clean 60-second formation, no ghost wallet identities, stale priority exclusion, model-setup provenance, n-threshold, suspect exclusion, unknown cost and separate heuristic superiority.
- All version references for the APK release set to 5.0.8845; GitHub Actions build+sign+unit and separate runtime smoke are required. The acceptance pipeline must stay red if smoke fails. A signed APK without that acceptance cannot be called ready.
- Remaining design gap: the high-volume entry predictor trains to one 5/15-minute net target; the owner's 600–1,000% runners need an independently calibrated later-horizon/tail objective on verified exit marks and actual sell latency. Neither a 400% clip nor a 15-minute label justifies inventing a 1,000% fill. The existing exit runner ladder must still be tested end-to-end on wallet-executed positions before any profit claim.

## Main-branch acceptance criteria

1. Compilation, unit regression and historical upgrade green on the exact commit.
2. Real emulator smoke green: 23 screens, background, doze, startup, stop/restart, fresh bot loops, no ANR.
3. In a new real-device pipeline: rising `SIGNED_60S_FRESH_8845` plus `SIGNED_DEMAND_FORMING_8845` forward-labelled cohorts, no increase in phantom 2m/5m flow-known; matched evaluated mint → proof → one canonical admission → FDG → executor → signed fill or an explicit terminal reason.
4. Compare *unique mints*, grade windows and current route prices to the previous 8840 report; report after-cost live win rate, profit factor, matched buy/sell basis and missed-runner attribution. A promised percentage gain is not an engineering acceptance test.
