# AATE 5.0.8521: current-stack audit and repairs

## Scope and evidence

This audit follows the current discovery, metadata, native strategy, setup selection, executable entry, holding, exit, canonical accounting, learning, persistence and presentation paths. The baseline is main commit `f8ec83558fee406a31188c0afdda75fc02183d6b` (5.0.8520). It incorporates the earlier entry/exit recovery work and the operator's additional PAPER, chart, Cortex and lane-alignment reports.

The supplied screenshots show a stopped PAPER runtime with 67 open positions, portfolio results from CRYPTO_ALT as well as Solana lanes, and Cortex forward-cohort coverage/authority problems. The supplied November 3 pipeline export provided execution timing and quote/refusal evidence. Screenshots establish symptoms, not the correctness of every stored value. The repairs below are tied to current source call sites and regression cases.

The operator's previous >70% win rate, EV >2, doubled balance and 600%/1100% winners are performance benchmarks. This change does not encode those outcomes as guaranteed returns or modify accounting to make the benchmark appear achieved. Comparable performance needs the same account mode, fee model, trade population, observation period and definition of EV.

## Findings and corrected behavior

| Area | Confirmed defect | Corrected behavior |
| --- | --- | --- |
| PAPER visibility | The hero counted all canonical PAPER assets; the position card filtered to Solana. LIVE wallet-freeze, mint-only close and buy-fill state also contaminated PAPER projection. | The PAPER list and OPEN count use one immutable, mode-scoped canonical population across all asset families. Position identity survives projection and actions. All rows remain accessible beyond the compact display limit. |
| PAPER Stop | Explicit PAPER Stop was excluded from liquidation. The old generic loop counted attempts as closes and could invent force-close/refund outcomes. | A deliberate operator PAPER Stop durably requests exact current lot IDs and settles through their actual owner. Only canonical CLOSED with zero remaining quantity counts as complete. Missing-price/refused lots stay visible and persisted. |
| Stop/restart ownership | Stop cleared caches/registries and persistence regardless of finality. Startup erased PAPER persistence after a manual stop or merely because more than twenty rows existed. Another restore path expired PAPER rows after six hours. | Config/process restarts preserve holdings. Stop never wipes unresolved inventory. Exact pending PAPER stop IDs survive process death, retry before entry rearming, and retry periodically while a PAPER runtime is active. Completed lots cannot resurrect; newly opened lots are not captured by an old stop request. |
| PAPER settlement truth | Stock close refusal could become a fabricated breakeven refund. Perps could substitute entry price. Market-data wrappers could refresh the timestamp of a cached/stale price. | Explicit stop APIs require a finite positive observation for the correct market/identity within the freshness bound. Original source time survives cache and persistence. No unanswered quote is converted into entry-price settlement. |
| Manual position actions | A selected PAPER pop-out could resolve a same-mint LIVE runtime row and issue mint-only Sell. | The selected position ID and mode reach the executor and are checked before and after legacy rehydration. A failed selection or refused sale is reported as retained, not successfully sold. |
| Native lane mandate | Learned veto retirement and RunnerGrab could turn cap/age/required-data refusals into an eligible host lane. Unknown cap and watchlist age could stand in for actual lane qualification. | Lane-defining cap/age, required metadata, lifecycle and safety remain binding for each desk. Learning adapts discretionary evidence inside the mandate. Independent other setups remain eligible on their own evidence. Missing required cap schedules rehydration; canonical known-age and proven lower-bound evidence stay distinct. |
| Setup continuity | A later optional generic wait could discard an independently fired and admitted setup; candidate components could be reused from the wrong assessment. | Current component evidence is versioned separately from immutable entry evidence. Multi-setup comparison precedes optional wait handling. A bounded, same-asset/mode/lane/version/stage admission proof survives to execution; safety and genuinely new missing requirements still bind. |
| Metadata retry | Established lanes inherited a launch-age retry cutoff intended for PROJECT_SNIPER. | Launch expiry applies to the launch-only cohort. Other or mixed lanes can re-evaluate refreshed liquidity/metadata against their own requirements. |
| Chart data | No dedicated 1h/4h histories; an old fallback could replace primary 1m history with 4h. A provider's 6h bars were labelled 4h; periodic refresh depended on one provider. | Independent 1m/5m/1h/4h histories retain real source intervals. Bounded priority refresh uses existing source/budget paths, validates returned frames, and retries unavailable observations without assigning a full normal refresh penalty. Incompatible provider routes are removed. |
| Volume and pattern intelligence | Codex interval volume was discarded; detected pattern state was not propagated into strategy metadata. Some consumers used mixed/synthetic/ingestion-ordered history. | Explicit USD interval volume and unknown-versus-zero survive parsing. Existing EMA, pattern and volume detectors feed strategy metadata and grouped multi-timeframe voters. Native history preparation uses ordered actual observations. Missing optional higher-frame evidence remains unavailable rather than a fabricated observation or blanket veto. |
| Entry economics | Approval could be based on gross or stale economics before actual route/size constraints were known. | Exact minOut, sized curve reserves, costs and freshness are checked near execution. Required net edge is calculated on the executable candidate, without double-counting the same costs. |
| Protective exit execution | A fresh executable quote could disprove a stale stop yet remain classified as a provider failure, carrying retry/escalation debt. | Signal invalidation has its own result. Quote evidence is bound to the held lot, quantity, cost and freshness. Only an unsubmitted disproved stop is withdrawn; new genuine breaches and already-broadcast finality remain protected. |
| Runner holding | Capability, entry contract, promotion reader and holding readers disagreed for CORE, MANIPULATED and DIP_HUNTER. | Runner-capable positions can reach the existing earned-promotion state and consumers honor that state. Native entry contract and promotion thresholds are not silently widened. Protective stop authority remains binding. |
| Cortex calibration | Score pooling could create cross-lane artifacts; interval fitting and forecast/bucket selection could contradict the actual displayed net forecast. | Lane-centered evidence, corrected isotonic intervals and one current forecast classification keep the score, bucket and reported calibration consistent. Inversion is measured, not hidden by relabelling all signs. |
| Forward labels | Cached/pre-horizon marks, inferred total losses, rolling target revisions and late entry prices contaminated authority. Missing coverage could disappear from denominators. | Original source clocks and frozen entry/target contracts determine eligibility. An hour target can grade without an intermediate five-minute mark. Observed collapse is not fabricated as total loss. Missing and superseded obligations remain in coverage accounting until properly disposed. |
| Outcome restart/replay | Pending/outbox limits, target coalescing and receipt ordering could lose or double-apply outcomes. | Durable checkpoints, target-aware delivery and idempotent terminal receipts preserve delayed observations and exact target ownership across restart. Later observational horizons do not rewrite a frozen authority outcome. |
| Actual learning | Fast-close recovery could bind a later candidate assessment instead of the sealed entry version. | Canonical realized outcomes retain the exact entry seal when available; unbound actual outcomes remain explicitly unbound rather than grading a fabricated forecast. |

## Discovery and entry contract

The operator's required order is **token quality first, then multiple setup grades against that token**. Hunters, scanners and the watchlist establish one shared token-quality record: identity, safety, liquidity, age, volume, chart evidence and the source/age of each observation. Each applicable setup is then graded against that same token record; the bot compares qualifying fits before choosing the lane and entry.

The execution sequence is discovery and source attribution, independently timestamped enrichment and token grading, native lane assessment, comparison of actually fired setups, lane-owned economics and executable admission, then exact execution. A token is not assigned to a convenient desk by erasing that desk's refusal. Optional absent chart context cannot become a global data veto; a missing observation genuinely required to establish that desk's cap, age, liquidity, identity or safety cannot become a fictitious fact either.

The current bridges continue to use the existing hunters, scanners, native brains, setup engines and final decision gate. This work reconnects and corrects their handoffs rather than replacing them with a new score or overriding all lane limits. Current candidate components can evolve as new observations arrive; the snapshot that owns an executed trade remains immutable.

Key paths: `SpecialistBrainBridge7542`, `NativeLaneConstraints8521`, `NativeLaneAge8521`, `RunnerGrabLane8343`, `CandidateComponentBook8521`, `CandidateAdmission8136`, `RunnerAdmission8180`, `HydrationReeval8343`, `ExecutableEntryFloor8521`, `EntryEconomics8329` and `Executor`.

## Chart and holding contract

1m and 5m observations supply local shape/timing context. 1h and 4h observations supply slower context when genuine history exists. Dedicated histories prevent a slow-frame response from silently changing the time scale of a fast-frame reader. Only complete contiguous source intervals can be aggregated into a larger bar. Open bars, wrong intervals, stale data, incompatible known venues and invented volume do not become closed chart evidence.

No minimum 1h or 4h history is imposed on a newly launched token merely because the application supports those frames. Existing technical detectors and Cortex voters consume what is observed, with unavailable evidence represented as unavailable. Chart evidence can inform discretionary entries and exits; it cannot cancel protective liquidation or rewrite the entry seal after the outcome is known.

Runner capability and a position actually earning runner status are distinct. The repairs align the readers of the existing position plan, including LIVE readers, rather than promoting every position in a runner-capable lane at entry.

Key paths: `MultiTimeframeCharts8521`, `DataOrchestrator`, `ChartSources7955`, `BirdeyeApi`, `SolanaOhlcvFeed6916`, `LifecycleStrategy`, `CortexVotersWide8004`, `RunnerLive8153`, `RunnerPlan8153`, `TradeExitContract8077` and `ExitTruthArbiterLive8144`.

## PAPER ownership and Stop contract

| Event | Required result |
| --- | --- |
| Deliberate Stop in PAPER | Stop intake, durably name the exact open PAPER lots, obtain real observed quotes, settle through each lot's owner, report confirmed and pending separately. |
| Missing, stale, conflicting or invalid mark | Keep the lot and cost basis open; retain the pending close request. Do not invent a refund, loss, zero value or entry-price close. |
| App death during Stop | Rebuild canonical ownership, recover the durable request, retry those exact IDs before new entry rearming. |
| New position in the same mint later | The old request must not close the new position ID. |
| PAPER and LIVE positions in the same mint | A PAPER action must not read LIVE entry basis or execute a LIVE sale. |
| Config restart | Preserve inventory; a restart alone is not an economic exit. |
| Explicit LIVE liquidation | Preserve existing LIVE/lifecycle-hold intent, require real settlement evidence, retain unresolved holdings and their proof state. |

PAPER simulated fills retain the existing impact/fee model and canonical cash/journal path. Forced operator exit receipts remain actual performance evidence; they are not disguised as successful forecast-driven exit choices. Private strategy tracking is retired only after the corresponding canonical terminal receipt.

Key paths: `PaperStopLifecycle8521`, `PaperStopLaneRegistry8521`, `PositionPersistence`, `CanonicalUiPositionProjection6686`, `PaperPortfolioDisplay8521`, `PaperPortfolioRows8521`, `MemePositionPopout`, `BotService`, `Executor` and each native market trader's filtered stop API.

## What Cortex is measuring

The twelve displayed Solana cohorts are BLUECHIP, CASHGEN, QUALITY, CYCLIC, SHITCOIN, MOONSHOT, TREASURY, CORE, EXPRESS, DIP_HUNTER, PROJECT_SNIPER and MANIPULATED. Their forward-label selection, prediction calibration and earned authority are not the whole account's realized return.

Other asset families already submit through `CanonicalEntryAuthority6551` and `CrossAssetCortex7931`. Their sealed learning identity routes observations to separate `MKT_*` or `CRYPTO_*` books. These books do not contaminate the Solana global prior. A profitable CRYPTO_ALT paper portfolio therefore does not, by itself, validate a Solana lane's STRONG bucket.

Long-only forward predictions do not become short-direction predictions by renaming their sign. A SHORT outcome without a compatible prediction is retained as owner-routed actual evidence with an explicit no-read bucket. It cannot grade voters that made no matching prediction.

The corrected forward epoch isolates incompatible old aggregate labels because their summed state cannot reliably identify which old samples had valid timing and target ownership. Old snapshots remain at their original keys. Actual canonical fills, trade records, realized books and bound open-position evidence carry forward. A new forward cohort starts with honest sample/coverage counts; positive authority must be earned by the corrected evidence.

The frozen target is chosen from the lane mandate at decision time: five minutes for scalp/range mandates and one hour for mandates meeting the existing hour-grading criterion. Later 4h/24h observations remain useful research/diagnostic evidence; they cannot retrospectively replace the original target.

## Verification and practical limits

### Final integration after recovery

The recovered continuation was reviewed again before integration. Additional concrete defects were corrected:

- Actual Cortex closes now wait for complete learning-state restore before mutation. Failed receipt reads remain retryable, and a restored open trade record can receive its exact canonical close even when its voter snapshot is unavailable. Entry and exit learning publish readiness only after restore completes.
- Manual sells bind one canonical lot and mode throughout the action. Ambiguous mint-only actions and refused settlements cannot report a successful close; redundant specialist close bookkeeping was removed.
- A terminal runner receipt retires only its exact entry state, preserving a nearby same-mint re-entry's earned promotion and profit rungs.
- The separately recovered discovery changes restore newest-first bounded birth hydration and stream-refusal fallback handling, while retaining newer mint-init recovery. Invalid derived curve prices cannot enter reserve evidence.
- Higher-frame history remains on one known venue, and native volume comparisons use contiguous observed periods. Missing periods are not compressed into fabricated activity.
- Entry and oversold RSI readers share a period-correct calculation: gain/loss frequency across the fourteen periods remains represented, including for very small token prices.
- Executable entry economics retain the full protective sell fee when a built buy has a larger tip; averaging asymmetric fixed legs no longer understates that exit charge.
- Quote-refuted exit cleanup is bound to its original close lease. A late result cannot cancel or reschedule a newer lease on the same mint. Cleanup runs outside the lease monitor with an exact-generation reservation, removing the opposite-lock-order deadlock with wallet reconciliation while preserving queued emergencies and late finality.
- Specialist retirement requires the captured lot's canonical closed state, zero quantity and exact mode/mint. A stale same-mint close result cannot erase a newer lot's private or shared tracking.
- PAPER cleanup checks current canonical ownership under the authority lock, including funded quarantined holdings and pending LIVE entries. A PAPER terminal receipt cannot release their shared tracking.

The first recovered-source CI run compiled and passed release identity/signing checks, then reported eight failures in 1,640 required tests. Their obsolete source assertions and boot/cache fixtures were corrected while retaining the intended behavior assertions. The ownership review also exposed the quarantined/pending-entry cleanup defect above. Final integrated CI must rerun the entire required gate; this initial run is not release acceptance.

Behavioral regressions cover these cases in the 8521 suites, the recovered birth-priority suite and the curve-feed suite. The required CI selection includes all of them. Source checks alone do not establish that those behavioral tests executed; the final revision's Android CI and release evidence remain the acceptance authority below.

This repository's Android workflow is the release authority: compile and package, run the required regression gate (including every `Aate8521*` suite), evaluate historical unit-test delta, check signing/security, and exercise install/upgrade/lifecycle behavior on the emulator. An accepted installable artifact must come from the validated main revision. Local source scans are useful evidence, but are not a substitute for Kotlin compilation, test execution or device verification.

The new regression cases cover exact executable entry economics, quote-refuted stops, calibration intervals and lane pooling, frozen targets and source clocks, restart/outbox delivery, multi-timeframe parsing and chronology, setup/admission continuity, lane constraints/age evidence, all-family PAPER projection, exact PAPER stop identity/finality, persistence, and runner promotion alignment. Existing assertions that encoded the obsolete contract are updated narrowly; the build gates and economic protections remain in force.

After installation, runtime acceptance is observable: the same-mode OPEN count equals the complete position list; an operator PAPER Stop produces confirmed or explicitly pending exact-lot results; unresolved requests survive restart; actual P/L reconciles with canonical receipts; native desk bounds remain visible in eligibility; chart observations keep their own intervals and source clocks; authority samples correspond to their frozen target; and live protective exits retain valid quote/finality ownership.

A clean build establishes executable consistency and regression coverage. Profitability, win rate, payoff distribution and EV still require a representative post-install observation window with real fills and the same accounting definitions. No test in this change fabricates those market results.
