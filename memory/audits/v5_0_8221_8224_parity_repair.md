# V5.0.8221 → 8224 (Oct 2026) — Operator 8220a forensic repair + Paper/Live parity

Branch synced to upstream `V5.0.8220a` (repo advanced ~1000 commits externally). All work is additive authorities.
CI: GitHub Actions only (no local compiler). Ignore `Runtime Smoke Test`; the gate is `Build AATE APK`.

## 8221 (previous session, build FAILED on new-dead-code guard)
- CandidateAdmission8136: native qualification survives a missing playbook pick (`NATIVE_BOOK_8221`); FRESH_LAUNCH learned
  refusals fall through to alternative setups (structural vs statistical hazard split).
- HandoffThroughput8190: NO_TRIGGER + missing inputs after the grace window is `DEFERRED_RECEIVER_DATA_GAP_PERSISTENT_8221`,
  never RECEIVER_RECORD_UNSOUND.
- SpecialistOwnership7951: lost rescue claims terminal-named `RESCUE_OVERFLOW_7951`.
- SellRouteFinality8144: profit stops (trail / giveback / breakeven) bypass the bot-loop queue (stop latency).

## 8222 — wire the 8221 constant (guard fix)
- Persistent data gap re-issues `DataDeferFetch8180.request8180` and re-arms the grace window (re-fetch + re-read cycle).
- Counted per lane: `RESCUE_DEFERRED_DATA_GAP_PERSISTENT_8222_<lane>`.
- Build: APK ✅, unit tests ❌ (`Aate8144SellRouteTest.catastrophicExitsRankAheadOfEveryOtherKindOfWork` asserted the
  pre-8221 queue contract: `bypassesQueue8144("TRAILING_STOP") == false`). Fixed in 8224.

## 8223 — Paper trades the live universe and pays the live exit latency
- **PaperUniverseParity8223** (new): paperBuy refuses the structural wall PreTradeHardGate applies to live (sub-$500 pool,
  active mint/freeze authority, confirmed rug, ≥35% top holder, blacklist, quarantine). Pending proofs still sample.
  Terminal `PAPER_BUY_NOT_OPENED_LIVE_WALL_8223_<wall>`; counters `PAPER_UNIVERSE_LIVE_WALL_8223_*`.
- **PaperExitLatency8223** (new): paper sell fills at trigger mark × adverse drift projected over the LIVE-measured
  trigger→broadcast latency (SellRouteSpine8144 NORM/EMRG averages, persisted `PAPER_EXIT_LATENCY_8223`; priors 16 s / 6 s
  from the 8220 forensic until n≥3). Only adverse drift; bounded −60%. Impact/fees then charged on the executable mark.
  Forensic `PAPER_EXIT_LATENCY_DRIFT_8223`; status in pipeline report "paper parity (8223)".
- **PeakLedger8188 / CapitalDrawdown7948**: a wallet-corroborated flow explained by the bot's own live legs in the lag window
  (sell proceeds landing after the journal row) is `REFUSED_TRADE_SETTLEMENT_LAG`, never a deposit → no artificial peak.
- **CandidateAdmission8136**: FDG snapshot prior (score, floor, size) carried per (mode, mint, lane, version) for 120 s so
  LiveEdgeGate7877 / TradePlan7739 / FDG floor fallback decide on the same prior (`ADMISSION_PRIOR_CARRIED_8223_<lane>`).
- **CostSides8169 / CostLedger7962**: `SELL_SIDE_ONLY` — a contradicted entry mark no longer discards the measured sell side
  (`sellSidePct8189` merges `COST_LEDGER_SELL_ONLY_8223`).
- Tests: `Aate8223ParityTest`.

## 8224 — one economic basis for route-minimum promotion
- `AdmissionEdge8180.Proof8198` carries expectedNetPct / costPct / costSizeSol; `expectedGrossPct`.
- `MinPromotion8188.runtimeRefusal8188` costs both sizes with `NetEdge8030.costPct8030` only (same model as admission) and
  uses the admission's own gross when the generation holds a proof (`ROUTE_MIN_PROMOTION_ADMISSION_BASIS_8224`).
- Aate8144SellRouteTest aligned to the 8221 contract.

## 8225–8231 — trace writers, DEFER-not-REFUSE, test alignment, CI proof robustness
- 8225/8226: `LivePipelineTrace6431` writers — INTAKE (markScannerProgress7807, live only), FDG_ALLOW (sealed live intent),
  AUTHORITY_ALLOW / block reason (LiveEdgeGate7877.liveRefusal), EXEC_ATTEMPT (liveBuy), BUY_CONFIRMED (canonical mirror),
  BUY_FAILED + reason (emitLiveBuyFail). NOTE: `admitProtectedMemeIntake` is a pinned oversized method — it may only shrink.
- 8229: LiveEdgeGate7877 data-defer exhausted branch records `Verdict8083.DEFER` (was REFUSE). Counter
  `DATA_DEFER_EXHAUSTED_KEPT_DEFER_8229`. Aate8204 assertion updated to the new contract.
- 8224/8227/8228/8230: tests aligned (Aate8144 profit-stop bypass + 3 s poll cap; Aate8169 SELL_SIDE_ONLY; Aate8136 rescue
  overflow terminal; Aate8190 persistent data gap). All three CI test suites green on 8230.
- 8230 emulator proof failed on a launcher ANR dialog (infra); 8231 teaches `ci/release-art-startup.py` to tap "Wait".

## Open (next)
- P2 Cortex7885 calibration (inverted strong/refused cohorts).
- `ScannerDedup6430` no callers; `LIVE PIPELINE TRACE` no writers.
- Paper exit latency is a projection; a true delayed paper fill (async) would be the next parity step.
- BASIS_UNCERTAIN_7807 live closes still excluded from learners (CanonicalPositionAuthority6441 quarantine).

## 8232 — one economic basis at the sizer (operator "no trades" 8231 snapshot)
- 8231 snapshot: FDG allow=0/2478; admission8136 ADMIT=174 yet LiveRiskPolicy7807 passes=NET_EV_NONPOSITIVE_AT_SIZE_8153=15,
  ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET=3 → every sized candidate refused on a SECOND gross/cost estimator.
- Fix: `runtimeInputs` gross = AdmissionEdge8180 proof's expectedGrossPct when the generation holds one
  (`SIZING_GROSS_FROM_ADMISSION_PROOF_8232`); `costAtSizePct8153` = the component/measured cost (costPct8030) when supplied,
  7766 model only as fallback (no more `max(7766, real)`). Aate8153SizingTest aligned.
- Structural remainder (not a bug): wallet 0.32 SOL → routable min 0.0285 SOL ticket → ~6.25% round trip; lane records
  mostly negative (lanes60 nets ≤0; live 0/3). EXECUTION_ECONOMICS (671) refuses gross < 1.5× cost honestly.

## 8233 — the shadow book learns from what live wanted (operator: "its how live learns quickly")
- `FdgOutcomeContinuity8197.evaluate` → after every specialist FDG call in LIVE mode, a candidate with an
  `AdmissionEdge8180` proof (edge authority ADMITTED) that the FDG blocked opens a shadow observation via
  `Executor.shadowObserve8233` (reason `admitted_blocked:<blockReason>`), counter `SHADOW_OPEN_ON_ADMITTED_BLOCK_8233_<lane>`.
- Book cap 60, oldest closed at mark; closes feed brain.learnFromTrade and ShadowBookTelemetry7215 (acceptance J: 20 closes).
