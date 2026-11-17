# AATE 5.0.8843 — Specific proof, not aggregate refusal; signed-wallet enrichment

## 8841 CI regression caught before release

- Build Release APK **succeeded**, but required regression tests found 1/2,579 failure and full suite 1/9,327: `Aate8675EvidencePathTest.walletCanBeEnrichedWithoutDuplicatingTheSignedTrade` (expected new buyer 1, got 0). The release was correctly marked `UNVERIFIED_TESTS_FAILED`.
- Cause: 8841 moved `BuyerFlow8666.note` after the signed-tape guard. That stopped phantom duplicate transactions but also stopped the wallet recovery behaviour deliberately implemented by 8790.
- Fix: on a positive-quantity, independently price-checked duplicate that supplies a wallet, pass it through `BuyerFlow8666`'s existing signature-aware enrichment path. It amends the original walletless observation; the second trade's volume, price, candles, launch data, scanner callback and token counts remain suppressed. `OUT_OF_BAND` duplicates are already rejected before this branch.

## Decision evidence hierarchy

- `CandidateAdmission8136.edgeModelRefusal8537` could veto a candidate's earned after-cost setup/cell record (e.g. n=60, net mean +9% ±1%) merely because the whole-lane model predicted a loss larger than the cost. This compared different conditional markets.
- The model's negative refusal still applies whenever **no locally proven candidate-specific record** is available. The selected setup, matched cell or proven runner grab must satisfy its existing support, sample, after-cost and outlier tests; a thin or suspect record can never defeat the negative model.
- A qualified specific proof now outranks the coarse prediction. Independent execution economics, fresh price, route, liquidity, safety, hard rug protection, structural loss, timing and the final sealed buy authority all remain in place.

## Tests and release contract

- Preserve historical wallet enrichment regression and exact one-swap volume assertion (`Aate8675EvidencePathTest`).
- Add `Aate8843SpecificProofAuthorityTest` to assert an earned local proof survives even a strong negative aggregate verdict, while thin, losing and outlier-suspect evidence does not.
- Update the historical `Aate8537EdgeModelTest` expectation that had encoded the inverted authority hierarchy.
- No claim that predicted rare runners are live profitable until a *single current main SHA* passes build, required regression tests, full unit suite, signing verification, runtime smoke, and is followed by canonical live trades with confirmed fills, exits and positive net P&L.
