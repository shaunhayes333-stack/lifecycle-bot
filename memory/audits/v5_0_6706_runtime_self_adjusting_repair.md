# V5.0.6706 runtime self-adjusting repair

Work in progress: source-grounded repair for the 5.0.6705 runtime snapshot and red smoke acceptance. This audit is intentionally created before source mutation so the repair lineage is explicit.

Observed runtime failures from the 5.0.6705 smoke/runtime evidence:
- 120-second execution-spine acceptance failed on PHANTOM_SIZED_ONLY + CASH_DELTA + BASIS_DELTA + REALIZED_DELTA + QUANTITY_DELTA.
- Runtime report showed persistent FDG_ALLOW_WITHOUT_EXECUTION_INTENT, specialist sizing/ticket/intent chokes, paper journal/ledger diagnostic divergence, and main-thread SharedPreferences / report rendering pressure.
- Learned performance remained far below the operator doctrine because several learning surfaces were advisory only and did not sufficiently constrain entry authority after statistically meaningful losing cohorts.

Repair doctrine:
1. Preserve the canonical economic authorities; do not hide real deltas by weakening acceptance.
2. Remove pre-FDG phantom executable accounting and only count sizing as executable after immutable FDG intent exists.
3. Close the paper ledger/journal replay parity gap at source.
4. Make rolling performance feedback execution-effective: below-target cohorts tighten ordinary entry authority and size, while retaining bounded dust reprobes for relearning; recovery automatically relaxes the constraint.
5. Keep hard safety / immutable FDG provenance / one-position-per-mint invariants intact.
6. Offload UI/persistence work from the main thread where the source still violates that contract.
7. Extend patch-rot and contradiction scans so later repair layers cannot reintroduce retired authority paths.

Acceptance: standard build scans/tests plus Runtime Smoke Test must pass the 120-second execution-spine gate on the new source lineage.