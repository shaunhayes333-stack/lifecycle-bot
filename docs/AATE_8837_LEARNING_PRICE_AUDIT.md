# AATE 5.0.8837 — source-level learning and price integrity repair

## Baseline and scope

Base: clean 5.0.8836 main. Operator's latest attached diagnostic is 5.0.8833, so it is **pre-repair evidence**, not a live 8837 result. The diagnostic reports 1W/7L live closes, PF 0.06; MOONSHOT 2 FDG allows out of 513 reached, EXPRESS none out of 347 reached. The Mind reports 40,788 learned observations; the 15 executable handwritten signal detectors have not yet established a positive after-cost replay proof. This change must **not** be labelled a trading-profitability win on build success alone.

The audit traced the prediction producer, stacked learner, lane-specific consumer, both Cortex caches, LLM request/result, input venue classification, candle fallback order, signal detector and shadow price path. It is not a claim that all 1,865 Kotlin production files have been line-reviewed.

## Confirmed defects and source repairs

| Priority | Fault | Source-level repair | Regression |
|---|---|---|---|
| P0 | `Mind8746.last` stored by mint only. Each subsequent strategy lane overwrote the previous lane's MIND/TREE/LLM/SSI features, despite the frozen forward labels already being per lane. | Key by `(mint, canonical lane)`, copy snapshot, refuse older writes, require explicit lane on reads. Wire `EdgeModel8537` producer and all four `CortexVotersWide8004` readers. | Same mint, disjoint lane predictions/tree/LLM, missing lane abstains, older write cannot rewind, snapshot immutable. |
| P0 | `CortexVoters7885` 3-second memo and 45-second slow-voter cache treated all four Mind voters as lane-free; merely fixing the producer would still return cross-lane values. | Mark all four voter IDs lane-dependent so both read caches key on the lane. Existing `Aate8169CortexTest` re-derives the lane-dependent set from voter lambdas. | IDs must be in `LANE_DEPENDENT_8169` and every voter must pass its lane. |
| P1 | `LlmAnalyst8746` prompt explicitly included `Raw.lane` but reused one mint-only cached answer/inflight slot across all lanes. It predicted a one-hour outcome even though the stacker's 5- or 15-minute forward labels graded its Brier and weight. An asynchronous result was timestamped when returned, as if old facts were just observed. | Cache and inflight by mint+lane; prompt asks for `PocketLedger8610.targetHorizon(lane)`; timestamp answer at the decision's request time. | Lane isolation; 5/15-minute target; request-time age. |
| P1 | `LearnerColumns8798.venueOf` classified **every** `*pump` mint with `migrated=false` as a curve even if an explicit PumpSwap, Raydium or Meteora venue was observed. Stale migration metadata poisoned the tree's `venue` feature. | Give explicit observed AMM venue priority; pump suffix only fallback. This corrects a *learner feature*, not the chain's authoritative pricing formula. | Pump suffix with explicit AMM and stale/true migrated flags. |
| P1 | `ShadowMark8360.observed8360` used a separate tick-first/labeler/curve/tape read to open shadow positions, whereas the position's exit tick used newest timestamped evidence including pool and Jupiter. An older tick could supply entry while another source marked exit. | Delegate the legacy open read to the same `observedTimed8372` resolver as the exit. Existing mark freshness and the independent-jump guard remain. | Shared source wiring and existing shadow/book mark regressions. |
| P1 | `HeliusSwapCandles7819.request7819` returned for unknown market addresses or paid API retry cooldown **before** running the free pump.fun candle merge; real cached history never got the chance to clear missing 1-minute coverage. | Run free local tape, cached price and pump.fun bars first; paid Helius eligibility/market address only after that. The free pass does not add an HTTP call. | Order of free fallback versus market/cooldown returns. |
| P2 | `SignalTape8828` could turn a first-bar buying signal on when the *next* trade arrived minutes later. The previously closed bar was no longer an executable entry. | A live trigger fires only when the next observed bar is adjacent; the replay uses the identical tape code. Stale history still records. | 61-second dormant opening can't fire S001; existing quiet-gap S012 test remains. |

## Invariants deliberately preserved

- No live buy authorization was added and no hard safety, wallet, sellability, quote, signer, FDG or kill-switch check was bypassed.
- V3/V4 native strategy authority and the independent Cortex admission proof are unchanged.
- No recorded models, paper holdings, trade journals or prior labels are deleted or reset. Existing dataset/reader epochs stay intact.
- No fabricated price replaces a missing observation. Model abstention is preferable to carrying the wrong lane's prediction.
- The owner’s 300 handwritten bible entries remain research candidates; only 15 signal detectors were executable in the baseline, and none had proven after-cost value in the attached replay. This change does not promote unproven detectors to live trading.

## Still required before claiming profitability restored

1. A new 5.0.8837 device report must confirm both keyed PumpPortal and PumpSwap entitlement by **actual trade frames**, not wallet-funding claims or socket subscription acknowledgements. The supplied 8833 log predates the reported funding and still showed minimum-balance refusals.
2. Reconcile live executed buys/sells and quoted exit proceeds against canonical on-chain fills; run same-mint/same-time multi-lane traces through the fixed Cortex cache.
3. Compare matched 8836 and 8837 replay cohorts for model-vote provenance, missing price/candles, decision delays, refused runners, **after-cost** live expectancy, win-rate and profit factor. Paper results and 600%/1100% historical peaks are not evidence of current live profitability.
4. Continue separate source audits of price-venue identity and 7x/500x bad prints, native-vs-FDG trade arbitration, the blocked crypto desk, exit-mark quote/fill economics, and the remaining unimplemented handwritten signals. This targeted repair is not a full-codebase certification.
