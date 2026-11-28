# 9065 — preserve provisional paper debits through reconciliation

9064 main failed four of 10,416 historical tests and its runtime accounting witness. Three tests still required the synchronous callback configuration reads removed in 9064. The new warm-up integration test reflected `row`, but production serialization is named `encode`. The assertions now target the actual callback and encoder; the warm-up and callback behavior checks remain enabled.

The runtime trace shows a second, production defect. At 11:12:20.234 UTC the MANDY buy had debited the ledger, but its final receipt was not stamped until later. At 11:12:20.250 `PAPER_LEDGER_RECONCILED_FROM_JOURNAL_6663` restored the older journal totals. The resulting 0.107333334 SOL basis and fee were effectively refunded even though the buy completed. At 11:12:52.829 the witness reported cash delta 0.109963 SOL, basis delta 0.107333 SOL, and matching token quantities. These are CI paper-account observations, not evidence of a live-wallet loss.

Meme paper buys now register an attempt-specific provisional debit under the ledger monitor. Journal reconciliation cannot replace scalars while any such debit exists. A failed open refunds only that attempt's recorded cost/fee once. A successful open hands exclusion to its existing final receipt, which remains unpaired until the durable journal leg arrives. Ending the provisional phase invalidates tokens captured within it. Account resets discard old provisional claims so an old rollback cannot credit a reset account. Startup/history reconciliation also avoids repair work when a provisional buy is already visible; the ledger's final guarded write covers races after that early check.

Regression tests exercise replay starting after the debit but before the receipt, final receipt handoff, stale replay tokens, duplicate attempts, independent rollback, and reset. Existing finality-order assertions follow the new tracked debit. No accounting tolerance, witness requirement, entry/exit threshold, or live-wallet behavior was loosened.

Validation must include required/full historical tests, Android compilation, signing, device runtime and in-place upgrade on the exact main commit before the APK is accepted. This repair does not claim profitable live performance or completion of the paper/live capacity and learning-observation audits.

## Paper/live slot priority

The 9063 operator report also showed `forced=29 open=29` during LIVE operation. Code inspection found live forced-open collection reading Moonshot's combined book and accepting a global registry that does not record mode as proof of live ownership. The collector now selects each specialist's explicit mode book (and filters Dip/Express records by their stored mode). Both selection and ghost reaping use canonical Solana protective inventory and explicit live specialist ownership, preserving funded quarantined live positions. Paper holdings are filtered from LIVE scheduling, not deleted or sold. Regression coverage includes both modes owning the same mint, specialist UI mode disagreeing with the requested account, and paper records remaining intact after LIVE collection.

## Local validation limits

The standalone production-model suite passed 274 tests. A separate local harness compiled the unchanged production ledger, receipt tracker and partial-sell unit types with external Android/storage/telemetry services stubbed; the six new ledger regression tests and six prior reconciliation-race tests passed. This does not replace Android/Gradle integration or device acceptance. Local Gradle could not download its distribution (`services.gradle.org` DNS unavailable). Android CI and device acceptance remain pending; no accepted 9065 APK has been produced.

The production slot selector also passed 18 isolated mode-selection checks using fixture-backed provider stores, including same-mint dual ownership and exclusion of other asset classes. The real specialist-book integration tests are included for Android CI, which remains pending.
