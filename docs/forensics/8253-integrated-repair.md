# 8253 integrated repair — work in progress

Baseline: main `aeb80cb8c480493b838722e80943c3e4f28716d7`, version 5.0.8253 (Android versionCode 8753).
Diagnostic: `aate_pipeline_20261020_193335_558.txt`, captured 2026-10-20 19:33:33 AEST.
Working branch: `repair/8253-integrated-forensics`. Main and release version remain unchanged.

These are source-confirmed defects and working corrections, not verified live improvements. Diagnostic counts have different scopes and are not treated as a common funnel denominator. Paths below are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/` and line numbers refer to the working tree.

| Directive / observed symptom | Source and confirmed contract defect | Producer → consumer correction | Regression / verification |
| --- | --- | --- | --- |
| §2–3: alternative liquidity available while admission defers | `engine/truth/CandidateAdmission8136.kt:933`: classifier missing list survives observed liquidity resolution | Resolve liquidity presence before binding missing inputs; retain every unrelated unknown | Twelve-lane optional-context and essential-input tests added; not yet passed |
| §2–3: fresh data fails to change deferred decision | `engine/truth/CandidateAdmission8136.kt:589`, `SetupFeatureResolver8169.kt`: admission and election caches can outlive new evidence | Pair observation publication wakes consumers; invalidate both decision caches on arrival; authoritative fresh entry mark participates in age calculation | On-chain reserve cache now feeds both setup and admission liquidity, and successful reserve fetches wake decisions. Stale/invalid reserve and fallback fixture added; full provider failover and wakeup replay still required |
| §4: qualified lanes never become execution owners | `engine/LaneExecutionCoordinator.kt:209`: nonempty READY set excludes qualified candidates before proven-strategy ranking | Include qualified specific ADMITs in the same election contest; retain safety, sealed owner and same-mint checks; resolve token aliases through existing handoff resolver | Every lane ranked against deferred READY competitor; not yet passed; cycle-scope replay pending |
| §4: rescue overflow becomes permanent refusal | `engine/SpecialistOwnership7951.kt:352`: slot competition marks lane INELIGIBLE | Record contributor competition separately; retain actual refusal terminals | Competition then safety-refusal test added; not yet passed |
| §5, §11: alternate admitted setup replaced downstream | `engine/truth/LiveEdgeGate7877.kt:521,637,1043`: setup loss checks and reporting re-read selection | Carry admission chosen setup into live-close checks, playbook loss checks and assessment; named setup checks retain negative evidence | Bound alternative identity test added; not yet passed; exact evaluated lifecycle-stage prior is retained to prevent duplicate stage veto; changed stages and setup/cost losses remain independent. Wider prior precedence audit open |
| §6: economics depends on unrelated last ticket | `engine/truth/NetEdge8030.kt:433`: absent proposed size reads global last sealed size | Capacity-bounded preview independent of previous trade; exact proposed notional remains authoritative; remove unrelated global cost-breakdown fallback | Final-size, deterioration and promotion replay pending |
| §7: indicative appreciation reported as executable profit | `engine/truth/PositionDisplayTruth8078.kt:134,178`, `ui/MemePositionPopout.kt`: spot mark treated as exit economics | Solana executable PnL requires quantity-matched held exit estimate; UI labels missing executable PnL separately | Reported Rowley arithmetic fixture added; not exact transaction replay; global mark-authority audit remains open |
| §7–8: cached quote/holding survives partial sell | `engine/truth/HeldExitQuote8189.kt:63,90,108`, `engine/PositionProof8189.kt` | Quote must match remaining raw quantity; reporting and quotes use existing post-signature proof authority; rejected quote work no longer silently leaves in-flight state | Quantity mismatch and post-signature freshness tests added; not yet passed |
| §9: runner intent lost after restart | `engine/RunnerLive8153.kt:76`, `engine/truth/EntryStrategySnapshot6450.kt:84,174,217`, `ExecutableOpenGate.kt`, `Executor.kt` | Same-version admission proof → sealed intent → pending/open entry snapshot → persisted runner management identity | Actual snapshot serializer/decoder and confirmed-position promotion regression added; device restart and pending-fill crash replay still required |
| §7, §11, §14: report cannot identify exact close trigger/transaction/origin | `engine/PipelineHealthCollector.kt`, `engine/TradeHistoryStore.kt` | Include transaction signature, original exit trigger and originating lane in execution reporting | Diagnostic formatting and attribution replay pending |

## Evidence limits and remaining work

The report contains Rowley’s mint and approximate entry/exit economics, but not full transaction signatures or the original automated exit trigger. Subsequent read-only public RPC retrieval recovered the exact matching buy, partial and full-close transactions; verbatim responses and provenance are in `lifecycle_apk/app/src/test/resources/forensics/rowley-8253`. The final transaction proves zero token balance, so the later diagnostic holding is inconsistent with that confirmed close. The original application trigger remains unknown. Source tracing confirms the apparent chain quantity of `633319` is a 40-character diagnostic truncation: the full detail `canonicalRaw=63331990605 chainRaw=63331990605` becomes exactly the printed text. The report also counts MATCHED=1 and no QUANTITY_MISMATCH. Therefore a decimal-scale defect is not established by this report. HOST_MISSING_CANONICAL_HELD remains a real reported exception. The working change preserves full raw quantities and adds a reproducing diagnostic regression. The arithmetic regression uses reported basis 0.017925 SOL and gross proceeds 0.017154 SOL. Its conversion price is explicitly a synthetic unit-conversion fixture. It is not blockchain settlement verification.

Still open: unified provider capability/fallback coverage; all twelve actual execution-cycle paths; remaining prior precedence; final-size economics and minimum promotion; global indicative versus executable mark authority; all profit-taking route/min-output paths; canonical quarantine/dust/settlement reconciliation; durable runner rung finality and continuation ticks; decision-time learning labels, duplicate-close protection and attribution; complete identity-based diagnostic coverage; historical 8086 behavioral comparison.

## Validation status

- Initial Gradle wrapper attempt failed resolving services.gradle.org. Official Gradle 8.7 and Android SDK 34 installed in the workspace; build now reaches Kotlin compilation.
- Static comment-balance and authority-contradiction checks passed, including the reserve-delivery and runner serialization edits. Final suite verification remains pending.
- Initial debug Kotlin compilation completed; Java compilation failed because this environment has a JRE without jlink. Installing a full JDK before rerunning. No behavioral test pass recorded.
- CI commit d68bb492 passed all static gates and reached release compilation; emulator smoke remains in progress. These runs do not cover later working edits.
- Release compilation, CI, installation/upgrade, startup, shadow parity, transaction replay and route evidence validation: not completed.
- No signed release APK delivered. No live order sent. No profitability claim. ADB reports no connected devices; /dev/kvm is unavailable.

## Version comparison

8251 `573606d22`: documentation-only change. 8252 `0265d15a6`: curve reserve/tape changes. 8253 `aeb80cb8c`: runner-entry association map, evidence wakeups, executor stop diagnostics and thread-local cost reporting. Historical 8086 `73034c76f` includes learning persistence; runner modules introduced later require behavioral comparison rather than whole-app reversion.

## Source audit findings that do not justify another patch

- MinPromotion8188 already reads doctrineBudgetSol8236 in both promotion stages in baseline 8253. The diagnostic’s advisory-budget hypothesis still needs candidate-specific sizing replay; no risk threshold has been relaxed.
- LivePipelineTrace6431 has real writers in LiveEdgeGate7877 and Executor added by 8224, despite its stale source comment. Coverage coherence still needs audit; it is not an entirely unwritten counter in this baseline.
- CORE and CYCLIC use different runtime scheduling paths. Removing their rescue exclusions alone would consume slots without adding executable receivers; those exclusions have not been blindly removed.
- LivePendingAttempt7868 stores pending broadcast bindings only in memory. The opened-position runner snapshot is now persisted, but restart between submission and confirmed opening remains an unresolved evidence-persistence path.

## Further confirmed evidence-delivery defects

- Held reserve estimates expire after 20 seconds, but OnChainDepth8038.request8038 previously refused a refresh while its 90-second admission cache remained fresh. The consumer now supplies its required age; valid cached reads and acquisition timestamps share that contract. Regression covers a 25-second read accepted for admission but stale for exit valuation, future timestamps and invalid reserves.
- A fresh spot mark previously prevented the held hot worker from requesting a quantity-bound quote. Every held Solana position now asks the independent debounced quote service; scheduling does not count as successful data. The global EXIT_ECONOMIC spot authority still requires repair.
- DexScreener pair-flow timestamps now use request acquisition time, and a concurrently superseded pair response does not announce a successful publication.
- The 8253 regression classes are now explicit required CI tests. Failed builds no longer upload an unverified APK; full diagnostic/test artifacts remain retained.

## Confirmed Rowley transaction reconstruction

The live TradeVerifier decoder is now shared with a finalized-RPC replay test. On-chain quantities match the diagnostic exactly: bought 158329976511, sold 94997985906, then sold 63331990605 to zero. Buy debit excluding 1513840 lamports refundable account rent was 29891786 lamports; net sale credits were 17153559 and 11358235. The combined economic loss was 1379992 lamports, with fees/tips already inside wallet deltas. This is real historical evidence; full canonical/journal/learning replay and the stale-inventory repair remain open. The new replay test is not yet passed.

## Settlement freshness and exit authority integration

- `TokenRefreshPolicy.kt` now publishes wallet balances, owner, completeness and observation time as one immutable snapshot. A generation captured before RPC prevents an in-flight pre-transaction read from restoring balances after TradeVerifier confirms a buy or sell. Strict wallet reads reject superseded results instead of returning them to reconciliation. Protective quarantine retirement requires two distinct, complete, recent observations from the current owner after position mutation; an empty complete snapshot is eligible, an incomplete snapshot is not.
- `CanonicalPriceMark6522.kt` now separates HELD_INDICATIVE observations from quantity/position-bound EXIT_ECONOMIC marks. Risk stops consume the newest held observation; entry promotion excludes both held purposes. Locked pool spots retain their original timestamps and cannot claim quantity-specific executable profit.
- `HeldHotMarkAuthority7419.kt` and `HeldExitQuote8189.kt` retain quote input/output identity, exact quantity, SOL proceeds, provider and original reserve/quote observation time. Curve estimates cannot exceed real SOL reserves. `PositionDisplayTruth8078.kt` subtracts current estimated transaction overhead and remaining basis from the retained SOL proceeds, without reconverting via a later SOL/USD observation.
- Added `Aate8253WalletEvidenceTest` and `Aate8253ExitEvidenceTest` for the transaction/cache race, owner separation, completeness, indicative versus executable authority, route amount identity, reserve timestamps and virtual-reserve sellability. These tests await compilation; no pass is claimed.
- Full Microsoft JDK 17 installed; debug compilation is running with the complete JDK. Economic-unit, authority-contradiction and Kotlin expression-body checks passed after these edits. Release/installation checks remain pending.

## First release-suite result

CI run 39411616833 (earlier repair revision d68bb492 / PR merge 001517a2) compiled the release APK successfully. Required suite: 1141 tests, 5 failures. All five failing assertions inspected obsolete source-call strings after admission identity was explicitly threaded into downstream checks (7994, 8080, two 8094 assertions, 8153). Their wiring assertions now require the bound admission argument and named setup check; the lost third selection call is checked as the new delegating wrapper. No test was removed or skipped. The independent behavioral 8253 tests retain negative-setup refusal and all-lane admission/selection checks. Rerun pending. This earlier workflow still uploaded an UNVERIFIED_TESTS_FAILED artifact; it is not a deliverable, and that failure-upload step was removed in later revisions.
