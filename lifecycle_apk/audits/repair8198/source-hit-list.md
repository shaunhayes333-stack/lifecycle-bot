# 8198 source repair acceptance record

Baseline: complete PR #57 head 73e562111f2cb275763fd0dca3ffee6b2909bfa7 (8197), not merged main. Locally reproduced full baseline: 5,565 tests, 0 failures/errors/skips.

## Pre-change confirmed fault family

- Executor.kt:18146-18231, 18549-18562: valid sealed FDG score is re-evaluated against a fresh mutable strategy floor before entering the quote path. This contradicts one immutable decision. Preserve current hard safety, expiry, size/capital, route, mode and finality checks; consume valid sealed score without a second strategy vote.
- Executor.kt:18584,18602,18621,18690,18731: bare early returns lose exact refusal before generic wrapper terminal. Existing hard safety protections must remain; bind the real refusal to the exact attempt before cleanup.
- Executor.kt:13937-13956: failure attribution searches current candidate version, then any pending mint intent rather than the explicit dispatch attempt. Candidate rollover can terminate or mislabel another attempt. Exact supplied attempt must never fall back to another generation.
- TokenMapAuthority.kt:342-378: discovered venue/source fabricates expectedOutAmount=1.0 and promotes quote/route truth. Candidate sibling: RouteTruthHydrator and all consumers using pool availability as executable amount. Removing only the sentinel without separating quote eligibility would create a pre-quote deadlock; do not make that isolated change.
- ReconcileClassifier8144.kt:270-324: absent balances on partial reads are correctly guarded; positive incomplete totals and MATCH status need consumer review before changing.

## Candidate siblings and downstream verification

All native lane wrappers share liveBuy. Audit named receiver expiry/version and DATA/WAIT retry; admission/setup economics; pending signature ownership; partial runner quantity and confirmed proceeds; canonical learning ACK/replay; event-local mode and UI counters. Source-string checks do not establish a working quote/transaction boundary.

## Release status

UNVERIFIED. No main merge, APK or funded-wallet test authorized by this record. Historical QUALITY attempt identity and 8086 decision-time replay records have not been supplied in this workspace; aggregates alone cannot reconstruct them.

## Verified continuation findings

The operator reports Thursday night 15 October Brisbane time at ~40% win rate and doubled cash. Commit history spans 8094 through 8117 that evening; installed build is not established by commit dates. No reported gains have been promoted to verified realised samples.

The first modified full-suite run executed 5,570 tests. Five new behavioral sealed-authority tests passed. Two failures were exact-string contracts in Aate7790LiveCausalClosureTest and Aate7809EntryLastMileTest; both now assert explicit-attempt failure routing instead of the obsolete unscoped call.

Post-seal LiveSlotPriority7304 was the sole production consumer of a lane-history priority gate. Retired that obsolete gate instead of moving another arbitrary veto upstream. The executor still checks current wallet/reserve, exact immutable size, route, inventory, keypair, safety and pending signatures. Its two regression contracts now assert no post-seal priority revote and preservation of capital/size protections.

ReconcileClassifier8144 incorrectly called a positive incomplete chain balance below canonical quantity a mismatch, and counted equal partial balances as MATCHED. Partial enumerations now remain explicit lower-bound observations. Complete snapshots still identify missing inventory; partial excess holdings still identify a genuine discrepancy. No balance, close, reward or strategy policy is mutated by this classifier.

Pending-signature sibling: the QUALITY/BLUECHIP generic failed-open wrapper could classify an already-signed unresolved order as refused after the exception handler had correctly preserved it. The wrapper now consults the existing SignedTxLedger by exact attempt ID and emits a deferred-signature outcome. It still returns false, not a fabricated OPEN/success; the existing reconciler owns confirmation. Tests cover same-mint different-attempt isolation.

Terminal mux sibling: ToolkitSignalSheet refusal reason counters sampled current UI/runtime mode even when terminalizing an immutable LIVE ticket. Exact-ticket/retained-lineage mode now travels into that counter; terminal idempotency is synchronized. An explicit LIVE event under PAPER runtime is tested without changing economic records.

## Operator narrowed historical baseline

Operator identified builds 8113–8114. Verified `361727241..379ad2fa0` changes only root/module version files and CI error-string matching; trading production source is identical. This is now the comparison baseline. It is not a rollback authorization or proof of net realised returns.

## Runner receipt mutation pattern

Confirmed at RunnerRungBook8153.confirm8153: invalid/duplicate economic receipts could advance a new tier and clear pending state before validation. Caller hunt found an intentional quantity-only path through RunnerLive8153.confirmTier8153, reached after actual chain consumption or an APPLIED paper receipt. Repaired without breaking that path: explicit applied-quantity confirmation, and validated/deduplicated economic confirmation before any tier mutation. No new accounting authority or persistence format. Tests cover invalid economics, duplicate signature attempting another tier, restart replay, and quantity receipts that do not manufacture proceeds.

## Golden Tape pre-push hit list

Updated: V5_0_7239's legacy floor contract now also requires sealed-score consumption before fallback; V5_0_7304 no longer requires the retired post-seal lane-prior gate and instead protects wallet capacity and sealed-size checks. Added V5_0_8198 exact-attempt refusal, signed-pending preservation, event-local mode, incomplete-chain lower-bound and runner receipt-order contracts.

Inspected unchanged: immutable sizing/7835, 7305 lane preservation, 7733 signature retention, 6599 specialist causality, 7948 terminal idempotency, and existing hard safety/route/finality contracts. Their invariants remain active. Full Golden Tape runs as part of the full unit suite; no baseline exclusions, disabled tests or green-by-delta substitutions.

Local verification before version roll: 5,575 tests, zero failures/errors/skips. Original 8197 baseline independently executed 5,565 tests, zero failures. Static Kotlin, method-budget, economic-unit, authority, FDG-budget, lane identity, new-wiring, literal and patch-rot gates passed. Comment scan was rerun explicitly against all 2,449 Kotlin files after detecting its default path had scanned zero.

Release acceptance remains incomplete: no 8196 attempt replay, no historical wallet-delta dataset, no full quote/broadcast fixture, no local device/emulator. This branch is not a deployable acceptance claim. No main merge, no learning reset, no funded-wallet test.

## Remaining acceptance work (not declared repaired)

- Exact historical execution replay: no raw attempt snapshots from the 8196 QUALITY failure or the 8113/8114 successful session were supplied. Compare against 379ad2fa0, not 8086; never infer a root cause from aggregated counters.
- Route evidence family: TokenMapAuthority.classifyRoute still writes expectedOutAmount=1.0 for discovered Pump/DEX routes. Consumers include cachedExecutableForEntry7471 -> CanonicalPriceMark6522, pre-quote Executor checks, and liquidity safety readers. This predates 8114. Removing the sentinel without separating quote-readiness from real size-bound quote proof could choke all entry paths. It requires an end-to-end repair and real quote-boundary fixture, not a one-line removal.
- Reservation cleanup sibling: failure attribution now targets exact attempts, but WalletCapacitySeal7868.release is still mint-only in Executor, SizingQuoteRecheck8153 and TradeAuthorizer. Trace the FDG seal producer before adding attempt ownership; stale failure must not release a newer seal. Do not weaken actual wallet/reserve checks.
- Cortex: onCanonicalClose currently consumes an exact position read, attempts recoverRead8187, then files eligible unbound closes separately when pairing is absent. This is not evidence of a fake successful voter update. Inspect actual entry binding/persistence and callback failure delivery before altering learning; no learning reset.
- Runtime acceptance: local SDK has adb but no attached device, emulator binary, system image or KVM. The existing GitHub workflow includes emulator release/upgrade smoke. Private-branch CI can provide those proofs but is not funded-wallet execution proof.

No APK from this checkpoint is approved for installation as the complete repair. Do not merge on CI green alone. The full 18 October release directive and untouched 8197 ancestry remain binding.


## Final source-family continuation (owner requested main and installable APK)

The owner's latest instruction explicitly authorizes finishing and pushing an installable release to main. No trading, wallet funding, learning reset or blind rollback is authorized. Historical cash/expectancy reconciliation remains unproven and is not represented as live execution acceptance.

- WalletCapacitySeal now stores the decision generation and canonical lane, then binds the elected immutable attempt when ExecutableOpenGate publishes it. All production cleanup sites use exact attempt ownership or matching unbound generation/lane; compare-and-remove prevents stale failure from freeing newer cash. Repeated sizing cannot overwrite a still-active same-generation/same-lane reservation. Pre-broadcast economics carry the same generation and remove only the observed record.
- Every direct liveBuy refusal now uses a locally scoped wrapper capturing the original attempt ID. Unscoped diagnostic calls no longer fail whichever newer mint owner happens to be current. The exact-ticket backstop retains pending signatures and releases only its own reservation. SIZE_REJECT and refusal counters remain wired once through the event-local idempotent terminal helper.
- Discovery no longer fabricates expectedOutAmount=1.0 for Pump or DEX venues. Existing pre-quote readiness and fresh price/liquidity assessment remain available without synthetic output, avoiding an entry deadlock. Legacy executable/mark API names are not transport authorization; real final SwapQuote mint, positive input/output, input-size/security, simulation and economic checks still precede broadcast. A held entry venue no longer creates Jupiter quote approval. A graduation label without pool/price/liquidity evidence stays pending rather than masquerading as a discovered AMM.
- Behavioral fixtures cover stale attempt/preauthorization reservation release, new-generation preservation, Pump/DEX discovery without fake output, wrong-token/zero-output quotes, economics-generation mismatch and valid quote acceptance.
- Additional Golden Tape/source contracts inspected and updated: 6658 primary-lane sizing now carries candidateVersion; 7868 capacity producer; 8153/7325 final Jupiter quote handoff now carries sealed intent; 7809 refusal producer and 7869 exact-ticket lookup; 8167 migration no longer relies on a fabricated output. No behavioral test was disabled or excluded.

Release gate: all 5,579 current tests must pass, full static gates and regression delta must pass, then exact private-branch CI must prove signed release/upgrade/ART/UI-start/first-loop. Fast-forward main only after those gates, build main, verify its APK signature/version/source SHA and checksum, and deliver that artifact. Report LIVE EXECUTION UNVERIFIED, since no funded-wallet test is authorized. Do not ship an earlier checkpoint artifact.

## Owner screenshots and specialist alignment verification

Screenshots supplied 18 October show the Cortex lane panel at 00:55, 01:02 and 02:08: all fully visible authority bars are zero, and most STRONG headline medians are negative. The installed build is not visible. These images are not wallet-delta or realised-PnL records. LearningCounterActivity.renderCortex8069 reads UiLane8069, whose headline values come from LearningEntryTruth8153.verdictRow8153: executable first-of-episode, on-time forward labels. Means, medians and legacy decayed books are different populations/statistics and must not be conflated. Some medians improve across the three captures; no claim that every lane deteriorates monotonically is supported.

Cortex held authority is capped by calibration, mode-specific realised STRONG closes, owner-routed realised evidence and walk-forward proof. A zero held-authority bar is not itself a blanket ordinary-entry veto: entryRefusal applies named constitution/timing rules and otherwise records the entry read. No forced authority increase, invented profitable labels, learning reset or risk-size uplift has been added.

Source coverage: LaneHunter7297.profiles contains SHITCOIN, QUALITY, BLUECHIP, DIP_HUNTER, MOONSHOT, TREASURY, CASHGEN, EXPRESS, PROJECT_SNIPER, MANIPULATED, CYCLIC and CORE. These match SpecialistCandidateBooks7803.LANES and the 12 native brain dispatches in SpecialistBrainBridge7542. ResidentHunterWorker7807 supplies the existing scanner intake; resident claims scope the native brains. NativeEvidenceLanes8197 retains every qualified authoritative lane; only background Cortex prewarming is limited to two. All qualified lanes request their own missing setup evidence through the coalesced source refresher. LanePlaybook7907 supplies lane-specific multi-setup menus. Hunter bands/ranking consume learned mode/liquidity/timing/opportunity feedback, and settled outcomes are position-bound to the owner before updating hunter books.

At execution, CommonSenseTradePlaybook's non-hard strategy objections are already advisory after the seal (7849). Fresh safety, missing source evidence, wallet capacity, real quote identity/economics and finality remain binding. The new repair removes the verified duplicate score/slot votes and stale cross-attempt cleanup, without bypassing those real checks. Actual runtime token-to-setup errors cannot be inferred for individual coins from an aggregate screenshot: exact decision records would be needed for that causal attribution. No claim that scanner accuracy or future profitability has been proven is made.

Final local verification: 5,579 tests, zero failures/errors/skips. The final 7868 assertion now verifies that executor refusal delegates to the existing named, event-local, idempotent terminal helper rather than demanding a duplicate inline EXEC_REFUSED write.

## Rapid same-lane pivot fault family (confirmed before patch)

- CandidateAdmission8136.noteDecision8144 unconditionally records d.chosenSetup as the held strategy, including REFUSE and DEFER_DATA. Repeated refusal can renew the losing choice's 90-second priority over fresh ranking.
- StrategyIdentity8153 (in StrategyRank8153.kt) keys this cache only by mint/lane and accepts it on age/fired membership, without mode, candidate-generation or learned-book revision. LanePlaybook7907.selectedSetup8094 reads that held value before the revision-aware ranked alternatives. This can mask a newly evidenced VALID alternative before another admission attempt.
- Sibling: strategy-basis telemetry reads the same unscoped cache. Update writer, reader and trace together.
- Preserve already-sealed decisions: no learning update may rewrite the exact intent in flight, its entry strategy snapshot or position owner. Fresh selection applies to unsealed next decisions. No threshold reduction, risk increase, new veto, learning reset or cross-lane promotion is involved.
- Mid-hold review: HoldingLogicLayer and AICrossTalk are called by Executor. Existing HeldPositionPivotArbiter explicitly treats mutable tradingMode as exit technique while canonical entry/position identity retains ownership. This review does not establish that those runtime recommendations have been profitable, and does not authorize relabelling entry ownership.

Rapid-pivot repair result: admitted strategy pins now carry mode, lane, candidate generation and learned-book revision. A REFUSE or DEFER does not pin/renew a rejected strategy. An unsealed choice is reopened immediately when the existing playbook book revision changes, allowing its evidenced alternatives to rank without waiting for the 90-second TTL. Existing valid sealed intent protection prevents feedback or later rejected re-evaluations from rewriting the execution already in flight. Selection/basis readers carry the same scope; no learned-state storage was added or reset (this is the existing transient selection cache).

Five new behavioral tests cover refusal/defer clearing, immediate same-lane re-ranking on a revision, paper/live/lane/generation isolation, sealed-evidence preservation, and old refusal isolation. Added Golden Tape covers producer admission status, revision sensitivity and exact sealed lookup; the 8153 identity wiring contract now includes the scoped producer. Full local suite: 5,585 tests, zero failures/errors/skips. No threshold or automatic risk-size increase was introduced.

## Report compaction fault family (operator request, before patch)

Confirmed: ReportBudget8176.forCopy8176 drops zero/none rows, caps samples and setup lists, truncates lines and removes sections to fit 180k. PipelineHealthActivity then applies a second clipboard cap and falls back to rawText.take(60000). ReportingHub.buildUnifiedHealth caps individual sections and the whole report, with a reduced pipeline extract. ErrorLogActivity has a second truncated clipboard/export path and a large EXTRA_TEXT fallback. These are presentation/export defects, not trading-policy defects.

Repair scope: preserve every character of each collected source snapshot through reversible dictionary/repeated-row compaction, keep a full original export, and split the complete compact text into numbered bounded clipboard parts when needed. No source field, zero, timestamp, precision, event-local mode, lane, proof state or row may be silently removed. Unified report collection must not impose additional section/global trimming. Existing source retention windows/summary scope remain explicit; this does not claim to export unlimited historical database rows. All formatting/file I/O stays on background workers. Original and compact file sharing remain file-backed. Report changes require their own full suite/static validation before the pending consolidated release can merge.

Additional report-family findings: stale fallback reports were stamped with the new request time and could renew their cache lifetime; the repair retains the source generation time and explicitly labels stale data. ErrorLogger's ordinary text export clipped stack traces at 500 characters. Unified diagnostics now request full stacks for the explicitly named recent-200-record detail window while preserving the existing recent-80 severity/component rollup (removing that rollup would itself have lost prior report data). Historical databases are not mislabeled as fully exported.

Report Golden Tape hit list: updated legacy 4487/6048 envelope, toolkit/tail, 6078 observable-fallback, 6308 on-screen/worker delivery and 7589 report-section contracts to protect complete snapshot collection, bounded numbered parts and file-backed export rather than destructive clipping. Existing timeout/watchdog, source truth, learning visibility, canonical journal summary and main-thread protections remain asserted. Added exact round-trip, zero/cold-start retention, duplicate multiplicity, unicode/long-line splitting, marker collision and both-UI-path tests. Repeated telemetry fixture: 231,113 to 112,696 characters (51.2% smaller), expanded exactly to the original; this is a test fixture, not a measurement of the operator's current report.
