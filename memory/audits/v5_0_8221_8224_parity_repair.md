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

## Open (next)
- P2 Cortex7885 calibration (inverted strong/refused cohorts).
- `ScannerDedup6430` no callers; `LIVE PIPELINE TRACE` no writers.
- Paper exit latency is a projection; a true delayed paper fill (async) would be the next parity step.
- BASIS_UNCERTAIN_7807 live closes still excluded from learners (CanonicalPositionAuthority6441 quarantine).
