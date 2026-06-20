# V5.0.3945 Live Growth Alignment Audit

Date: 2026-06-20
Doctrine: live wallet daily growth target 2x-5x when opportunity and true safety permit.

## Verdict
The live stack is partially aligned after 3941-3944, but not fully. The main buy path is no longer globally sell-only blocked, and the hold patch prevents immediate non-catastrophic churn. However several remaining policies still behave like defensive research tooling rather than aggressive live-wallet compounding.

## Family tree audited
- Scanner intake/source balance: BotService protected intake, probation, watchlist picker, sourceBalancedWatchlistOrder.
- Lane/layer selection: canonicalCycleLaneFor, shouldRunBuyLaneForCycle, MEME owner collapse, specialist lanes.
- Scoring/gating: UnifiedScorer/FDG/ExecutableOpenGate/LiveBuyAdmissionGate/PreTradeHardGate.
- Sizing/compounding: Executor.buySizeSol, SmartSizer, WR recovery, composed multiplier floor, final normalizer, tier cap.
- Live execution: Executor.liveBuy, route guard, blacklist, immutable finality ticket/handoff, wallet mutex boundary.
- Exits: Executor.requestSell, strict SL/hard floor, profit lock, capital recovery, partial, runner bank, SellReconciler, LiveWalletReconciler, CloseLease/PendingSellQueue/BalanceProofWait.
- Learning/reporting: journal/CanonicalOutcomeBus/TradeHistoryStore/LearningPersistence/PipelineHealthCollector/Golden Tape.

## Confirmed aligned
1. SELL_ONLY_SAFE_MODE is no longer a global buy veto in LiveBuyAdmissionGate; same-mint close lease remains.
2. Healthy wallet-held live positions are not force-sold by SellReconciler RECONCILER_REQUEUE.
3. Live min-hold anti-churn now runs before TokenLifecycleTracker.onSellPending, CloseLease, and PendingSellQueue.
4. STRICT_SL alone does not bypass hold unless raw market PnL <= -15%.
5. Scanner/source balance exists: sourceBalancedWatchlistOrder interleaves fresh/unseen/cold by source family.
6. Protected Solana intake is preserved; cleanupWatchlist is telemetry/shadow rather than destructive pruning.
7. Entry break-even was bypassed to sell-side economics for live micro-probes.
8. Composed sizing multiplier floor prevents soft layers from dust-zeroing everything.
9. Immutable live finality ticket/handoff path is present.
10. Buy confirmation/journal telemetry guard exists.

## Confirmed misalignments found
### A. Live low-confidence hard veto
File: FinalDecisionGate.kt around confidence block.
Pattern: PAPER low confidence becomes dust probe, but LIVE low confidence was still setting blockReason=LOW_CONFIDENCE.
Risk: Live sample starvation. The stack cannot learn or compound if uncertainty blocks instead of micro-sizing.
Patch: V5.0.3945 converts live low confidence to LIVE LOW-CONF MICRO-PROBE with smaller multiplier than paper, no hard block.
Downstream: increases live attempts, data, and chance of catching early runners while preserving small size on weak confidence.

### B. PumpPortal kill switch false trip from skipped partial routes
File: Executor.tryPumpPortalSell partial/profit route skip.
Pattern: code skipped PumpPortal for partial/profit labels, then still called PumpPortalKillSwitch.recordPartialAttempt even though no PumpPortal attempt happened.
Risk: False global PumpPortal sell disablement, reducing fastest live exit route and harming realized PnL / emergency exit agility.
Patch: V5.0.3945 converts these skipped routes to telemetry only: PUMPPORTAL_PARTIAL_ROUTE_SKIPPED_NOT_ATTEMPTED / PUMPPORTAL_FRACTION_ROUTE_SKIPPED_NOT_ATTEMPTED.
Downstream: preserves exact-in Jupiter/Metis safety for partials without poisoning PumpPortal full-exit availability.

## Candidate misalignments requiring next audit/fix
### C. WR recovery sizing dampener may under-compound in live
File: Executor.liveBuy WR_RECOVERY_SIZE_DAMP and WrRecoveryPartial.
Risk: if live WR is low during bootstrap, entry sizes shrink exactly when the operator wants aggressive compounding and sample collection. Needs telemetry comparison: wrSizeMult frequency, average final SOL, live wallet growth.
Likely fix: cap dampener floor higher in live growth mode or route to lane/tier-specific boost rather than blanket shrink.

### D. SmartSizer / tier cap may over-cap tiny-wallet compounding
Files: Executor.buySizeSol, SmartSizer, ScalingMode tier cap.
Risk: fixed liquidity ownership caps are safety-reasonable, but final size may be too conservative for 2x-5x daily wallet growth on high-conviction micro launches. Needs actual final size distribution.
Likely fix: add growth-mode sizing boost for A/high-conviction + fresh source-balanced lanes, still bounded by route/liquidity.

### E. Partial profit/capital recovery may bank too early for daily x2-x5
Files: Executor.checkProfitLock, partial sell ladder.
Risk: capital recovery at 1.3x-4.0x and profit lock at 2.5x-10x can protect capital but may cap runner exposure if partial fractions are too large or too early.
Likely fix: runner retention rule: after min-hold, keep larger moonbag for MOONSHOT/QUALITY/BLUECHIP/high-MFE lanes; only bank heavy at ultra-runner thresholds.

### F. Lane observation owner-collapse may suppress multi-lane edge expression
Files: BotService.shouldRunBuyLaneForCycle, canonicalCycleLaneFor.
Risk: live owner-collapse prevents full ring duplicate buys, but also means only one lane gets primary execution. If owner selection is stale or too conservative, other specialist lanes cannot express edge.
Likely fix: allow multi-lane observation and one execution ticket per token with lane competitive bidding; no duplicate buys.

### G. Source-balanced picker is aligned but may still cap fresh evaluation under high opportunity
Files: BotService.selectOrderedMintsForCycle, supervisor caps.
Risk: cap protects runtime, but if opportunity floods, fresh tokens may still wait. 2x-5x target favors faster fresh evaluation.
Likely fix: dynamic burst mode: temporarily raise fresh/unseen budget when wallet has free SOL and live route health is green.

### H. Learning persistence looks broad but needs mode/lane/live-specific proof
Files: LearningPersistence, TradeHistoryStore, CanonicalOutcomeBus.
Risk: if paper dominates labels or live outcomes are underweighted, the huge stack optimizes the wrong surface.
Likely fix: live-weighted learner path and Golden Tape: mode, lane, trader, source, positionId, build tag required on all persisted outcomes.

## Patch state
- V5.0.3941: remove global sell-only buy veto.
- V5.0.3942: central live style/min-hold anti-churn gate.
- V5.0.3943/3944: compile/test cleanup for hold patch.
- V5.0.3945 pending: live low-confidence micro-probe + PumpPortal skip telemetry fix.

## Next patch recommendation
After 3945 builds green, patch C + D together: live growth-mode sizing/compounding. The current stack can find entries; the next bottleneck is likely how much SOL it is allowed to deploy and how quickly it compounds winners.
