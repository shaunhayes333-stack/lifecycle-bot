# V5.0.8257 runtime (operator report, 604 s) — status + next targets

Verified on this build: 3 live buys / 3 finalized sells (2 manual, 1 early-rug backstop) + 1 profitable partial;
canonical WR 35.7 %, PF 1.39 over 14 closes (too few to claim an edge; the two profitable full exits were MANUAL);
wallet ↔ canonical ↔ chain MATCH, exceptions 0; §6533 invariants 0; drawdown 28 % (soft size restriction).
The learning reset + 8256/8257 reopened the door: FDG sealed 19 allow / 0 block at the final stage.

## Confirmed by source this pass
- The 0-buy lock (8255/8256) was the learned book: ~800 of ~1,400 blocks were SETUP_RECORD_LOSING / TIMING_ADVERSE /
  CELL / STAGE / PROVEN_SETUP_ONLY on an 18-trade PF 0.10 book. 8257 `clearlyLosing8136` (mean + 2·SE < 0, or n≥30
  proven) keeps clear losses binding and makes the one-SE band advisory. 8256 made lane-wide SHAPE bins and the
  watch-first cell yield to a supported setup.
- Curve ticks are now full prints (8252) AND reserve readings (8257) — liq/mcap hydration of curve coins no longer
  waits on the RPC batch.

## Ranked next targets (runtime-referenced, source-checked where noted)
1. QUALITY 5 × `LIVE_BUY_NOT_OPENED_7948` with NO inner stop named — the 8253 wrapper only names stops routed through
   `emitLiveBuyFail` / `liveBuyDeferred`; `Executor.liveBuy` has ~15 `return false` sites, several outside those two
   funnels. Next: route every early return through `emitLiveBuyFail` (then the ticket terminal names the stop).
2. `ROUTE_MIN_PROMOTION_REFUSED_DRAWDOWN_8188` = 32: `MinPromotion8188.defensiveDrawdown8188` refuses promotion to the
   routable minimum at ≥30 % drawdown, and 10/12 lane allocations sit below the 0.0283 SOL minimum on a 0.25 SOL
   wallet → in a ≥30 % drawdown nothing can size. A deliberate safety rule (8188); the operator must decide whether
   the defensive tier should allow ONE promoted exploration order per lane or stay closed.
3. MOONSHOT 1,065 qualified / 0 selected: 58 `MOONSHOT_ZONE_DROPPED_AFTER_ADMISSION_7265` are the lane's own mandate
   (mcap < fluid min, not runner-shaped by 7044) — legitimate; the remaining ~1,000 die pre-intent on
   `FDG_DATA_INCOMPLETE_DEFER_8136` / `MCAP_UNHYDRATED_DEFER` — watch whether 8257 reserve filing moves these.
4. PROJECT_SNIPER 0 qualified: 1,612 BIRTH_METADATA_HYDRATING + 920 PHASE_HANDOFF — birth witness arrives after the
   phase handed off. Needs `TokenBirthHydrator7441` / `AdmissionBirth8144` work (pair `pairCreatedAt`, curve oldest
   signature) — not started.
5. Data propagation: 787 FDG DATA_INCOMPLETE_DEFER, 539 METRICS_IDENTITY_BROKEN_7069, 92 repair requests
   `NO_FEED_ANSWERED` while the Pump stream applied 5,916 marks → identity/freshness join, not a feed outage.
6. Held quotes 277/478 noRoute (intermittent); specialist funnel sellConfirmed = 0 for every lane while 3 sells
   finalized (`specialistLearningMissing=1`) — attribution gap in the lane funnel; `6gXN9N4y` flagged unmanaged after
   a manual sell (stale §7718 projection — verify at signature level).
