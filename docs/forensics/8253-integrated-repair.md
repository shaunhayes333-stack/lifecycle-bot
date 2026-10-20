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

The report contains Rowley’s mint and approximate entry/exit economics, but not its full transaction signatures or original automated exit trigger. Source tracing confirms the apparent chain quantity of `633319` is a 40-character diagnostic truncation: the full detail `canonicalRaw=63331990605 chainRaw=63331990605` becomes exactly the printed text. The report also counts MATCHED=1 and no QUANTITY_MISMATCH. Therefore a decimal-scale defect is not established by this report. HOST_MISSING_CANONICAL_HELD remains a real reported exception. The working change preserves full raw quantities and adds a reproducing diagnostic regression. The arithmetic regression uses reported basis 0.017925 SOL and gross proceeds 0.017154 SOL. Its conversion price is explicitly a synthetic unit-conversion fixture. It is not blockchain settlement verification.

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
