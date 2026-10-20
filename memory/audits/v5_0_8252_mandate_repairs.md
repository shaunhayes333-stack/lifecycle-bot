# V5.0.8252 mandate → V5.0.8253 / 8254 (source audit + repairs; CI per commit)

Evidence available to this pass: `are runtime 454.txt` (5.0.8251, 977 s). The mandate's 8252 report
(`aate_pipeline_20261020_180011_510.txt`) was NOT uploaded; its figures (14 closes PF 0.11, 55 runners / 2 held,
grossOpp 1.2982 / netBanked −0.0012, 2 unmanaged bot mints, 1 funded quarantine, 1 exit-reason invariant failure)
are taken as stated and the repairs below are the ones the SOURCE confirms. Nothing marked ✅ here is claimed as a
live result — every claim of profitability waits for a runtime log on these builds.

## Repairs landed (what / where / why)

| Mandate | Finding in source | Repair | Build |
|---|---|---|---|
| P5 RunnerPlan8153 74–80 | `grabbedAtEntry && !styleKnown` — a sealed QUICK_FLIP demoted a grabbed runner to a scalp plan | Explicit RUNNER_ENTRY intent: `CandidateAdmission8136.runnerEntry8252` is stamped when the record admits on the RUNNER_GRAB edge; `RunnerLive8153` reads `runnerEntryAdmitted8252(mint, entryTime)` (≤5 min window) and `plan8153` makes it RUNNER_SEALED whatever the sealed style. Scalp plan kept where the entry was only a scalp (grab map alone + known style unchanged). | 8253 |
| P2 launch cap | 8252 stood the proxy down only on a fresh reading; an unread readable curve still refused | One three-state `OnChainDepth8038.launchBuyers8252` (BUYERS_PROVEN / NO_BUYERS_PROVEN / BUYER_EVIDENCE_UNKNOWN) read identically by `earlyLaunchRefusal8048` and `buyRefusal8038`. UNKNOWN: early gate asks the chain (`needCurve8039`) and does not refuse; door defers `LAUNCH_BUYERS_UNKNOWN_DEFER_8252` at most 3× per mint then falls to the proxy. CURVE_NOBODY_BOUGHT_8046 runs first and stays binding. | 8253 |
| P1 §7 arrival | Held DEFER_DATA re-decided only on its backoff (5 s→60 s), never when the input arrived | `CandidateAdmission8136.evidenceArrived8252(mint, features)` marks the waiting feature due now and flags the mint near-decision; called from `DataOrchestrator.onTapeTrade7773` (tape/swaps/bp) and the pair-fetch answer (bp/chg/liq/mcap). No feature is assumed present — the record still reads its inputs. Counter `arrivalReevals8252`, labels `EVIDENCE_ARRIVED_REEVAL_DUE_8252`, `SETUP_PAIR_FETCH_ANSWERED_8252`. | 8253 |
| P3 QUALITY exec=0 | QUALITY buys run through `Executor.blueChipBuy`; the ticket terminal read only the wrapper `LIVE_BUY_NOT_OPENED_7948` | `lastLiveBuyStop8252[mint]` recorded in `emitLiveBuyFail` (46 sites) and `liveBuyDeferred`; the wrapper now terminalises as `LIVE_BUY_NOT_OPENED_7948:<stop>` so the next log names the exact stop. | 8253 |
| P4 NetEdge race | `lastComponents8169` global read by `breakdown8169` — the cost NUMBER was local (not racy) but the breakdown TEXT could mix another thread's venue/route | `tlComponents8252` thread-local; breakdown reads its own quote's components. | 8253 |
| P7/P9 exit-reason invariant | 6312 matched the word RUNNER alone: RUNNER_HARD_STOP_7967 / RUNNER_STRUCTURE_BREAK_7967 / RUNNER_FROM_ENTRY_GIVEBACK losses were rewritten to FALLBACK_AFTER_FAILED_PROFIT_EXIT and EXCLUDED from learning | `ExitReasonInvariant8253.claimsProfit8253`: a stop/break/giveback/floor/failure claims nothing; takes, banks, milestones, multiples still do. Runner loss exits now keep their reason and teach. | 8254 |
| P8 reconciliation | "✅ ALL RECONCILED" printed from examined rows while §7718 showed unmanaged bot mints / funded quarantine | `PipelineHealthCollector.inventoryCoverageGap8253`: examined-rows verdict and wallet-inventory coverage are printed as two truths; ALL RECONCILED only when both hold. | 8254 |
| P5 funnel | No per-stage runner counters | `RUNNER_FUNNEL_8254_{DETECTED, ADMISSION_EDGE, PLAN_ACTIVE, FIRST_TAKE_REQUESTED, FIRST_TAKE_LANDED, NET_PROFIT_BANKED}` at RunnerGrab7967 / CandidateAdmission8136 / RunnerLive8153 / RunnerRungBook8153 (net banked = confirmed proceeds > cost sold, per signature, deduped). | 8254 |

## Verified in source, no change needed
- P3 `StrategyElection8243.ownerOrder8243` IS the caller's ranking: `LaneExecutionCoordinator.kt:281` elects
  `ownerOrder8243(scores, verdicts) ?: convictionWinner`.
- P6 `SpikeCapture7943.onMark` early-return on `LifecycleHold8065.holding8065`: the lifecycle path runs FIRST
  (`rapidMarkOffers8144` → `LifecycleHold8065.onMark8065`: floor, takes at +100/+400/+900 (50/30/30 %), base/trend
  breaks) and tier-1 chart exits are still offered beside it. A lifecycle position is not left without a take.
- P8 8250 zero-finality path: UNREAD/UNKNOWN never becomes zero; two-provider absence, frozen guard, unresolved
  signature check and 180 s fresh-buy grace all precede a close (8250, CI green).

## Not done in this pass (honest)
- P0 deterministic fixtures from the 8252 report: the report file was not supplied. Fixtures need its decision-time
  rows (first price, venue, quote, route, risk class).
- P3 per-specialist positive/negative END-TO-END fixtures (12 lanes): not built; the trace table for 8251 is in
  `v5_0_8251_runtime_specialist_trace.md`. PROJECT_SNIPER birth witnesses and MANIPULATED/EXPRESS native floors
  untouched (EXPRESS NATIVE_NO_CONTINUATION refused set ran +9.8% — next evidence-led target).
- P4 immutable per-intent economic quote object: not introduced (the cost number is already local; only the
  breakdown text was racy and is fixed).
- P6/P7 per-position exit latency trace (triggerAt → … → canonicalSellBookedAt): not built.
- P9 Cortex walk-forward / provenance separation: not touched.
- P11 economic acceptance: no claim. Live expectancy on 8251 was PF 0.05 (1W/6L); nothing here is a proven edge
  until a runtime log on ≥ 8253 shows it.
