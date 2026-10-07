# 18-item repair status — unpushed Kotlin working tree

Updated operator authorization: validate the APK build on a temporary CI branch, then update main for on-device testing if the build passes. Full 18-item runtime closure remains unverified.
This document records source work, not a claim of Android runtime success.
Baseline: local 7857 commit d6789e5. Candidate build: 5.0.7859. Publication follows the updated build-first authorization.

| # | Issue | Source work / evidence | Closure still required |
|---|---|---|---|
| 1 | Distributed authority | BotService carries FDG directly into TradeAuthorizer; removed the second post-FDG V3 evaluation. | Trace one identity through the complete entry path. |
| 2 | BUY reverting to WAIT/DECLINE | Removed post-FDG WATCH/BLOCK returns; executor consumes the exact ticket; stale pre-entry learned opinion cannot defeat an exact FDG BUY at sealing. | Exercise changed advisory evidence after an approval. |
| 3 | Specialist ownership/handoff | Seal approved FDG owner before coordinator election. Specialist opens, closes, partials, ghost eviction and lock releases now receive account mode. | Competing-lane and account-switch execution checks. |
| 4 | FDG re-decision | Removed duplicate V3 decision in main handoff; cached FDG result retains its identity. | Demonstrate no second decision for the same sealed candidate. |
| 5 | Conflicting scoring | Main and V3 authorizers receive FDG effective score; both executor paths consume the ticket score. | Compare score at FDG, ticket and executor in a real run. |
| 6 | Sizing disagreement | Main and V3 executors consume ticket size; V3 forwards prechecked finality and canonical lane. Existing preauth seal consumes FDG size. | Show size equality through quote submission; fresh risk bounds still apply. |
| 7 | Entry-authority disagreement | Exact FDG provenance accompanies sealing; stale cooldown/streak/learned opinions become advisory at that boundary. Daily-loss and current hard-safety checks remain. | Exercise each refusal and confirm explicit terminal accounting. |
| 8 | Causal seal/immutable intent | Existing 7857 snapshot putIfAbsent retained; ticket is passed to executor, with named cancellation for runtime entry refusals. | Concurrent/superseded candidate verification. Attempt cleanup, retry ownership and cancellation lock release are now scoped to the exact attempt; duplicate authorization checks run before sealing. |
| 9 | Executor starvation / ART verifier | paperSell mark guards and canonical projection healing extracted into kept Kotlin methods; async closures capture a fixed Position reference. | Android ART class loading and paperSell execution. Source method-budget checks do not prove this. |
| 10 | Wallet → canonical LIVE ownership | Close stamps keyed by mode/mint; live wallet and close authorities explicitly read/write LIVE. Existing canonical adoption path retained. | Confirm wallet holdings agree with canonical LIVE inventory after reconciliation. |
| 11 | Supervisor / exit authority | Entry halt/freshness checks no longer suppress held-position exit dispatch; topups retain those checks. Manual Treasury close preserves inventory until terminal sell result; BlueChip inventory reads no longer purge held positions by age. Existing mode-scoped exit preflight retained. | Exercise independent exit clock, runtime halt and failed sell. Specialist projections now require canonical terminal evidence before close. |
| 12 | PAPER/LIVE contamination | Mode-scoped FDG stats, forward cohorts, ticket handoff lookups, entry locks, close ledger and specialist close maps; separate Manipulated maps. Paper ledger diagnostics excluded from LIVE root-cause ranking. | Cross-account regression checks, including replay, partials and exit selection. |
| 13 | Generic WR/expectancy on asymmetric lanes | Retained runner-lane generic-WR exemption; removed opposite-mode forward evidence and blended LIVE fallback. | Confirm actual asymmetric-lane admission reads only intended live cohorts. |
| 14 | Kill-switch/accounting disagreement | Retained 7857 migration of legacy PAPER-contaminated daily-loss latch and canonical LIVE-only outcome intake; removed paper root-cause misattribution. | Read actual migrated on-device state and reconcile against the user's 7 LIVE trades. |
| 15 | Ledger/journal divergence | Receipt-derived PAPER journal repair now validates receipt identity and records a ledger witness for the derived journal event ID; log reports enqueue, not durability. | Verify SQLite durability, replay idempotency and historical divergence after restart. |
| 16 | Mark/market-data authority | Added a dedicated price-observation timestamp to CanonicalTokenMap; route/cache refresh no longer refreshes price age; updated all production writers and executable-mark readers. | Fresh/stale/undated mark and source-identity checks on the execution path. |
| 17 | Fresh-launch conflict | Existing shared sniper launch-identity contract retained across election/execution; eliminated extra post-FDG V3 strategy verdict. | Verify birth hydration, graduation and age-boundary cases end to end. |
| 18 | BUY-qualified+sized dying before authorizer / AutoMode PAUSED | Removed second V3 handoff gate; true authorizer-entry counter retained. AutoMode caution telemetry states continue_to_strategy. | Running snapshot must show approved candidates reach authorizer and receive execution or a named refusal. |

## Checks performed after source edits

- `git diff --check`: passed.
- `ci/oversized_method_budget_7809.py`: passed (19 methods, 20 pinned entries).
- `ci/fdg_evaluate_budget_scan.py`: passed (512 declarations / 514 limit; 11 direct returns / 11 limit).
- No Gradle/JDK or Android build/test run for this working tree, per operator instruction.
- Existing/new Kotlin test sources have NOT been run against these changes.
- No current changes pushed. No APK produced from this working tree.

## Remaining work

Validate the complete Kotlin patch in the real Android build/runtime environment. The main exit-selection callers now pass the held position account explicitly, and all six specialist close adapters retain inventory until canonical terminal evidence exists. Do not relabel these items DONE from source inspection or counter changes alone. In particular, the known ART VerifyError is not closed without loading the resulting Executor class on Android.

## Continued repair pass

- Duplicate authorizer checks precede sealing and do not stamp the original attempt rejected.
- Terminal cleanup removes only the named attempt, including bare claim IDs and its retry slot. Deferred PAPER callbacks cannot overwrite another retry owner.
- Authorization locks carry attempt IDs; entry cancellation uses compare-and-remove for that attempt.
- Missing executor tickets receive named terminal cleanup instead of leaving their authorization lock behind.
- The alternate V3 path now supplies FDG score/confidence/quality/size to authorization, requires executable authorization, carries sealed size/score/lane to execution, and preserves prechecked finality.
- V3 refusal no longer creates a PAPER retry; PAPER entry learning requires an opened PAPER position rather than an unconditional success result.
- Recent ticket lookups require matching account mode and mint; LIVE score/handoff recovery requests LIVE explicitly.
- Source whitespace and both source method-budget checks passed again. These checks do not compile Kotlin or establish Android runtime closure.
