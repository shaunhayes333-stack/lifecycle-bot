# 5.0.6737: pipeline integrity

Baseline: Android source 83897f026942b508739046c38052464068e681be; delivery preserves the later runtime-smoke repair 06524e08fde6a77526f77de0cd8fed2add16283e.

Source repairs:
- Separate funded inventory/exposure from economically valid executable positions. Partial omissions remain explicitly unpriced paid basis, not vanished capital or fabricated returns.
- Use the same canonical marked snapshot in the paper-capital facade; keep hot cash reads atomic and cheap. Never use that PAPER-only snapshot for LIVE throughput/fairness.
- Enforce final learned risk, fee-aware cash and lane caps before executable minimums. A minimum does not manufacture risk permission.
- Calculate entry units and terminal exit proceeds with explicit raw scale, USD/token and USD/SOL conversions. No one-token default for unknown quantities; no cost-return or reason-based terminal fill fabrication.
- Require a fresh, exact-identity quote for paper settlement. Metadata hydration does not renew prices. Real same-provider/pool price collapses are not permanently rejected by an old ratio guard.
- Preserve canonical entry basis during paper price reads. Terminal identity/CAS, not a stale mint-level close label, controls closure.
- Keep paper sale proceeds in the shared canonical account; simulated treasury allocations cannot make pre-commit external-account mutations or be confused with fees. Explicit PAPER calls cannot become LIVE transfers after a global mode switch.
- Use exact, copied learning cohorts including mode, position identity, mutable PnL and quarantine state. Do not deduplicate distinct position generations by a five-minute mint window. Exclude both signs of legacy unquoted emergency outcomes from strategy statistics, without deleting accounting history.
- Respect fractional token holdings. Compare remaining quantity to remaining cost after partial fills.
- Recover genuinely dead exit coordinators; do not reset a live heartbeat during its scheduled cadence. Propagate cancellation and prevent old generations from replacing current progress.
- Classify cash/throughput deferrals separately from permanent token safety failures; do not train market quality on resource failures.

Validation includes production Kotlin arithmetic/property checks, PipelineIntegrity6737Test behavioral regressions, read-only source-contract scans, the existing release unit suite and release build. CI results must be checked rather than inferred from this document.

Boundaries: no credentials, live mode, real trades, or journal balances are changed. Invalid provider keys/quota require operator/account resolution. Historical lots lacking reliable entry quantity or FX remain visible as unpriced/quarantined exposure; no invented historical fill is used to make the ledger look healthy. A fresh device run is still required to validate sustained turnover and real performance. No win-rate guarantee is made.
