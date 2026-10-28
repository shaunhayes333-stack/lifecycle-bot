# 8417 whole-app review — working evidence, not release approval

Baseline: published 5.0.8416, source 4af85d027b092cfdac17997a448cf6077a76319c.
Inventory: 1651 production Kotlin files, 26 Android components. Inventory does not mean each file is reviewed.
Baseline CI XML: 7119 tests, zero failures/skips. Candidate tests and runtime verification remain pending.

## Confirmed defects addressed in candidate, validation pending

- Shadow episodes keyed only by mint suppressed independent lane evidence. Canonical lane+mint identity; keep exact old persistence keys on upgrade; persisted pending full exits; duplicate open/restore isolation tests.
- Redundant unproven-shadow path did not feed evidence-hold release. Removed duplicate producer; canonical evidence path retained.
- Manual perps Open bypassed live submission; Close/Close All bypassed on-chain proof. Use automated execution owner and return confirmed results.
- Multi-Asset perps Close bypassed live close; Close All ignored selected asset tab. Route selected positions through their owner, retain tab/IDs from confirmation.
- Copy-perps bypassed sealed admission and used copied token price for a SOL trade. Route through perps execution owner with SOL market data.

- Multi-Asset close UI now distinguishes accepted requests from confirmed completion.
- Perps exit dispatcher excludes concurrent duplicate closes; already-settled recovery projects the canonical result without resubmission. Whole-position economics includes entry and exit fees.
- Selected-setup economics continues through CASHGEN/TREASURY lane fit.
- Cortex asynchronous pre-confirmation prediction capture fills the original sealed intent.
- Synthetic corpus outcomes cannot supply observed trade-performance aggregates or evict captured trades; delayed learning preserves the settlement mode.
- Detailed twelve-lane and AI-stack findings: [STRATEGY_INTELLIGENCE_REVIEW.md](STRATEGY_INTELLIGENCE_REVIEW.md).

## Confirmed findings still requiring work

- PerpsTraderAI.scaleInPosition and Forex/Metals/Commodities/TokenizedStockTrader.addToPosition change local size/entry without submitting an order or updating canonical position/capital. UI reports success. Must implement actual funded top-up or explicitly remove unsupported action; cannot certify functioning scale-in.
- Perps auto open rolls local position back on PENDING_PROOF as well as FAILED; verify canonical reconciliation restores monitor ownership before calling recovery proven.

## Scope clarification — all areas remain required

The operator explicitly requires the complete system, not mechanics instead of trading logic or trading logic instead of mechanics. Every lane and strategy must be traced from its designated discovery pool through intelligence, candidate selection, admission, sizing, execution, position management, exits and economically valid learning into subsequent decisions. Check profitability measurement against realised net receipts; compilation, activity counters and forward labels do not prove sustained profitability. Use the normative meme_specialist_desk_repair_brief.md matrix for all meme desks. Compare against source 8113/8114 as a historically reported working period without treating reported returns as verified.

## Remaining acceptance scope

All screens and controls; PIN/biometrics; wallet/key storage; credentials; settings persistence; notification/background lifecycle; upgrade and restart; all market lanes; discovery/provider outages; admission/sizing; submission unknown/failed/confirmed; exit ownership; cash/position accounting; learning/replay/collective settings; diagnostics/exports; native ABI/ART/signature and installation.

Source scans are triage, not behavioral proof. Runtime on emulator and device-dependent integration must be recorded separately. No funded live transactions are part of this review.

## Snapshot findings under causal review

- Session main FDG: 2398 decisions, zero allows. Largest named reasons: execution economics 983; scalp-flat 430; data defer 239. Counts have different scopes from native lane counters and must not be subtracted.
- Cortex scoreboard: assessed 3951, graded 126, pending 1462, lost 1422. Applied refusal/choice/size counters zero, shadow activity nonzero. Several lanes SUSPENDED. Need trace outcome coverage and authority, not force-enable predictions.
- ExploreShadow: 3000/3000 tickets, FULL_NO_EVICTABLE 8888, lane-full 5108, capacity pressure 3297. This is a candidate source of outcome starvation; capacity refusal itself is not a fabricated loss.
- Finalized bus: 455 canonical, most learning consumers processed 1/excluded 454. Learning epoch exists; source intentionally excludes pre-reset settlements while dashboards retain history. Must establish exclusion provenance before classifying this as dropped learning.
- Strategy-specific admission already skips duplicate NetEdge entry repricing. However laneFitRefusal8054 remains unconditional afterwards and CASHGEN/TREASURY compare historical hour movement with 3x preview-size cost. Their menus include multiple setups. Review whether this independently contradicts a supported setup's own expected move/current-size evidence; no relaxation made without a reproduction.
- Source contains independent specialist candidate books for all twelve desks plus hunter profiles; presence is not proof of runtime input quality or execution influence.
- Working period comparison available locally: 8114 commit 379ad2fa0. Substantial later changes in Cortex, admission, playbook, forward labels and shadow systems; changes must be evaluated causally, not wholesale reverted.

Follow-up after initial main publication: canonical DNA memory now receives every meme lane directly from the existing terminal bus, with immutable entry attribution, settlement mode and persisted position/event IDs. Source-inferred setup/lane capture is removed. Unattributed legacy rows remain stored but are excluded from performance aggregates. Partial-sale receipts cannot satisfy terminal proof. The first CI run exposed an obsolete perps publisher source guard; the guard was corrected and rerun. Full regression and APK acceptance remain pending.

Runtime run 40697921009 failed before compilation: an unnecessary curl of the already-committed Gradle wrapper exited 35. The runtime script now uses the reviewed repository wrapper and setup-gradle cache, preserving failure on an actual build/startup defect. Canonical DNA tests now include successful disk commit before ACK, retry after commit failure, persistence reload, and a lane-specific governor decision that changes after settled wins and losses.
