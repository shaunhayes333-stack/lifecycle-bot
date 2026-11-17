# AATE 5.0.8846 — reject phantom executable economics; bounded fresh-receipt retry

## 8845 rejection, not a certified release

- The release APK compiled and passed signing/security, but its required + historical unit suites were RED: eight existing test contracts saw `SPECIFIC_EDGE_PROVEN_8136` or `ADMIT` instead of `EXECUTION_ECONOMICS_8136`/`DEFER_DATA`. The cause was `grossFromSetupProof8845`, which substituted a historical setup's positive mean for a currently observed weak or missing per-setup executable gross move. A historical mean is evidence for *setup selection*, not a quote, and cannot override execution economics. **8846 removes that substitution completely and restores the previous 8171 no-forecast fallback.** The long-standing `Aate8153StrategyTest` and the new explicit 8846 regression must pass.
- The exact-commit 8845 Android smoke passed its 23-screen, background, doze and restart checks, but the strict 120-second execution-spine witness was RED. A crypto-paper BUY entered its canonical five-store commit at the window boundary: `openInFlight=1`, age `288 ms`, position/ledger/fill/terminal true and journal false. The independently completed cash, basis, realised and quantity deltas were each zero, but accounting was *not current*, so the gate correctly refused it. The journal's durable stamp was logged roughly 25 ms before the failed snapshot. A 30-second retry budget had already been consumed by earlier stale accounting, not by that new receipt.

## Targeted correction

1. Source `CandidateAdmission8136`: live execution costs remain compared against the **selected fired setup's current measured gross move**. No substitution from past model/paper averages, even if the setup has n>=30 profitable labels. Preserve `DEFER_DATA` when the alternative has no observed move; do not borrow the rejected default's chart.
2. Source `ExecutionSpineAcceptanceWindow6647`: after the original 30-second accounting retry is exhausted, *only* a newly witnessed canonical OPEN receipt younger than 2 seconds may start one additional **10-second maximum** retry for a fresh reconciler pass. No PENDING, stuck or old receipt qualifies. No partial event is marked committed, no accounting deltas are synthesized, no acceptance tolerance is raised, and the original evaluator still requires a **fully current, reconciled** canonical receipt set. This correction changes sampling timing only and is bounded to two 5-second retries.
3. Tests: `Aate8846AccountingBoundaryTest` verifies the new-boundary case and exhaustion; `Aate8845SignedDemandAndDecisionTest` now explicitly asserts a good historical setup cannot promote a currently weak executable move. All existing lane tests still bind.

## End-to-end acceptance

- New main SHA must pass compile, signing, required and full historical Kotlin suites, 23-screen smoke and the strict 120-second execution-spine conservation check. Red tests mean **no accepted APK**; rerunning alone is not proof.
- When installed, compare fresh signed swap discovery, distinct mints evaluated, executable setup proofs, FDG approvals, wallet-confirmed BUY/SELL fills and true after-cost realised P&L with 8840 baseline. No 600–1,100% win is guaranteed. The separate long-horizon tail learner remains an identified design gap; 5/15-minute capped label targets cannot stand in for a 60-minute sale.
