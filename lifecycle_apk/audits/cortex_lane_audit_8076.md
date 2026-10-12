# Cortex lane audit — build 5.0.8076

Evidence: operator screenshots at 16:55–16:56 and pipeline report revision 788,
captured at 17:14:14 on 2026-10-12. The two snapshots are different cohorts;
changes in their means are not a controlled before/after comparison.

## Confirmed source defects and repairs

1. **Late labels subtracted more evidence than remained in the book.** Outcome
   windows and squared errors decay, but progressive corrections previously
   applied the original full delta. This can produce an impossible negative
   squared error, skill above 100%, or an EXPRESS bucket below the ledger's
   -100% label floor. Each new grade now carries its original contribution
   scale and score ordinal. Corrections use the remaining weight, including
   scoreboard, setup, refusal and verdict-by-setup books. A regime cell that
   did not receive the original grade does not receive its correction.
2. **Skill was scored against a prediction recalculated at grading time.**
   New assessments retain each voter's prediction and baseline before the
   outcome. Pending observations persist these values. Old pending observations
   without a recorded prediction still teach outcome bins, but cannot invent
   an out-of-sample score. Later corrections retain the same frozen prediction.
3. **Setup identities collided.** `PLAYBOOK_SETUP_V2` had only 21 bins, while
   current lane menus contain many appended strategies. `PLAYBOOK_SETUP_V3`
   provides a bin for every current setup and NO_TRIGGER. V2 history remains
   stored separately because its mixed bin cannot honestly be split after the
   fact. This is one new voter identity, not a learning reset.
4. **Cross-lane evidence subtracted independently aged windows.** A 500-label
   global window minus a separately aged 500-label lane window is not the other
   lanes' history. Predictions now pool the actual other-lane books, excluding
   the current lane and cross-asset lanes.
5. **Inversion detection ignored REFUSE.** STRONG was compared only with
   NEUTRAL. The existing significance/sample rule now also compares REFUSE,
   including the realized books. A sufficiently inverted lane loses only its
   Cortex override/size-up authority while continuing to observe and learn.
   This does not turn REFUSE into an automatic buy recommendation.
6. **Proven-only returned before the refusal audit could act.** The early
   `LIVE_PROVEN_SETUP_ONLY_8059` path now consults its own lane's validated
   refusal evidence or the existing candidate-specific Cortex override. It
   does not borrow another lane's aggregate. A thin cell can use that evidence;
   a measured-negative cell/band still needs its own existing review. Live
   truth, chase, cost, lane fit, route, finality and safety checks still apply.
7. **Restored/Hive evidence could corrupt attribution.** Hive seats/bins are
   checked for valid arithmetic and matching bin counts; an imported seat
   cannot replace a locally graded seat with live revision receipts. Cortex
   books and their revision receipts are saved in one SQLite transaction.
   Graded receipt retention uses observation time, not realized return, so
   winning outcomes do not receive preferential retention.
8. **Late execution defects masked lane performance.** Flatline detection now
   requires five distinct real candles, checks the full OHLC range and current
   price, and excludes synthetic/stale bars. Lifecycle take progress advances
   after a verified canonical sell slice, not after asynchronous dispatch.

## Lane findings

| Lane | Evidence in revision 788 | Interpretation / disposition |
|---|---|---|
| MOONSHOT | STRONG n110 +26.1%; REFUSE n13 -8.6%; four live holdings; realized STRONG n2 -15.4% | Forward discrimination exists; realized sample is too small to establish live profitability. Verify entry freshness, actual take fills and exit latency. |
| SHITCOIN | STRONG n108 +47.4%; REFUSE n2 +492.7%; FLATLINE and sizing refusals | Two REFUSE outcomes cannot establish inversion. Setup separation, false-flatline repair and same-lane refusal audit address demonstrated faults. |
| CORE | STRONG n47 -18.4%; REFUSE n23 +51.5%; native yields to specialists | Inversion now has a REFUSE comparator. CORE/CYCLIC lack the specialist per-token buy block and are deliberately excluded from rescue selection; enabling fan-out alone would not create that executor path. |
| PROJECT_SNIPER | STRONG n43 -18.2%; REFUSE n16 +59.7%; native allows 0/5933 | Prediction direction and birth/phase admission are separate issues. Metadata hydration and phase handoff dominate current admission; no evidence that simply lifting a score floor repairs them. |
| MANIPULATED | STRONG n38 -16.6%; REFUSE n15 +63.3%; buyerEnabled=false | Direction audit applies. The disabled buyer is an explicit configuration fact, not a Cortex vote. No configuration change is included. |
| EXPRESS | STRONG n2 -562.9%; native continuation/liquidity refusals | The displayed strong mean violates this ledger's label floor. Weighted revisions address the source defect; this number cannot justify strategy tuning. |
| QUALITY | STRONG n1 -0.7%; REFUSE n28 -8.2%; six pre-size flatline refusals | Insufficient strong-side evidence. Source/mcap qualification and false flatline refusals are distinct from prediction quality. |
| BLUECHIP | READY candidates; slot-health refusals; zero validated sizing but raw ticket present | Inspect candidate-level stage continuity, not aggregate zeros. No forced admission or manufactured predecessor stamp is included. |
| CASHGEN | Native allows 2997/3551; flatline12; realized neutral n6 -1.7% | Qualification is functioning; entry refusal and execution-cost/exit outcomes require separate attribution. |
| TREASURY | Native allows19/8531; role floor/weak momentum dominate | A positive aggregate NO_TRIGGER forward mean does not establish candidate-level executable edge. Setup-bin repair applies. |
| DIP_HUNTER | Native allows0/865; cap/not-a-dip/age refusals | Current candidate suitability is the measured blocker. No evidence of a Cortex-owned execution veto. |
| CYCLIC | Native allows17/526; no owner/intents | Fresh-price/sellability and the absent per-token specialist buyer path need separate treatment. |
| CRYPTO_ALT | REFUSE n30 -9.4%; cross-asset route/toggle refusals | Keep cross-asset evidence isolated. Solana ownership and unavailable-route decisions are not repaired by changing the Cortex score. |

## Limits and release acceptance

- Preserving aggregate history does not reconstruct already corrupted historical
  contributions. Legacy revision receipts without weights are not replayed as
  full-weight corrections. A device export is needed to reconstruct exact past
  observations where that provenance is missing.
- Forward returns and refused-runner lists are not filled live trades. Compare
  matching lane, setup, observation horizon, entry cost and whole-position exit
  before promoting a strategy. Do not merge the 27 canonical closes with the
  different 22-close strategy-eligible cohort.
- Graded Cortex receipts remain bounded at 600 in memory / 150 persisted;
  pending observations persist at most 1000. The audit does not claim complete
  seven-day revision coverage. Playbook revision receipts remain process-local.
- The OOM stack names exception handling, not the allocating owner. Lowering
  resident token metadata is mitigation, not a proven OOM root-cause repair.
- Provider entitlement/WS failures, the EH4cP8 quantity discrepancy, and
  candidate-level missing funnel predecessors remain unresolved by this patch.
- Required validation: release compilation, required regression suite, runtime
  smoke witness on the final commit, then a fresh device snapshot confirming
  finite scores, no below-floor bucket means, correct lane attribution, and
  take advancement only after a landed slice. Compilation alone is not release
  acceptance. No profitability improvement is claimed before those observations.

Regression coverage is in `Aate8076CortexRevisionTest`,
`Aate8076CortexScoreIntegrityTest`, `Aate8076LaneSetupIdentityTest`, and
`Aate8076EntryAndTakeTest`, alongside the existing required lane/history suite.
