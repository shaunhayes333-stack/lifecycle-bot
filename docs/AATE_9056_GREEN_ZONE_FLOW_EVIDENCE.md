# 9056 — green-zone causal buyer-flow evidence

Pinned predecessor 9055 (45ae5e48). Priority: teach the existing shared runner learner to distinguish forming demand, missing identities, and known observed zero buying. No new order gate, model promotion, trading threshold, or exposure change.

## Implementation
- Existing BuyerFlow8666 captures signed-swap provenance and monotonic latest mutation receipt; readForLearning9056 freezes the delivered print list only when all mutations were available at the decision cutoff. Delayed wallet enrichment cannot alter an older frozen precursor. Existing live read contract remains unchanged.
- BuyerFlowEvidence9056 records observed print count/span, known-wallet coverage among delivered buys, signed fraction, receipt lag and signed-side net SOL over 5s, 15s and adjacent prior 15s. These describe the delivered tape, not complete market coverage or proof of lifetime-new wallets.
- ForwardReturnLabeler uses causal research read. ResearchRows8877 writes explicit null identity metrics when any observed buy identity is missing, keeps trade volume and source/receipt evidence, and validates the stamp against the exact decision. LanePocket validates stamped buyers; legacy direct rows remain readable for historical compatibility.
- SharedRunnerLab9051 learns 46 columns from verified decision-time flow and independent raw features. Unstamped identity/flow fields are not evidence. F9056 journal tag upgrades old 36-column rows to 46, retaining existing labels and independent raw features, masking legacy buyer-flow/identity values. No history file deletion or live model authority changes.
- Preserve existing 18 mark-proxy barrier heads, independent asset-balanced splits, native decision/exit safety and release gates.

## Verification
Pure standalone Kotlin coverage builder: 30 assertions passed. Source blob matches tested file f03de1bb40733f19eb8ba841f0b2c3e8db7b2967. Required Aate9051BarrierLearningTest now exercises frozen walletless demand, genuine sell-only zero, identity repair, receipt cutoff, schema migration and shared feature mapping. Full Android compilation, integration regressions, device validation and market performance pending. 9055 CI was still in progress at last check; do not treat this as a verified APK.

## Next
Measure causal-feature coverage and per-desk unique-asset retention; extend chronological ordered path evidence beyond one hour; validate executable-size quotes and actual after-cost outcomes. Never infer full market coverage from a sparse delivered tape or promise profitability from synthetic tests.
