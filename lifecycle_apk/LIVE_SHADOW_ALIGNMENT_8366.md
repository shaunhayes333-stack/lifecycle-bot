# Live vs shadow alignment — V5.0.8366

Base: origin/main f90c3909d (5.0.8363). Paths are relative to `app/src/main/kotlin/com/lifecyclebot/`. Lines marked
"(8363)" are as of f90c3909d. Lines marked "(8366)" are after this change.

Shadow books:
- **EvidenceShadow8344** (ES). It mirrors the LIVE decision, and EvidenceHold8336 unlocks lanes on it.
- **ExploreShadow8347** (XS). It explores 6 entry x exit variants. Its record picks live's adopted entry and exit
  (`bestFor8351`, `liveExitStyle8351`, `liveEntryWait8347`).

Measured live numbers (report 5.0.8341, lifetime):
- Landing: BUY|PUMPPORTAL landed 3 of 17 (9 expired, 5 failed). BUY|JUPITER landed 18 of 18.
- CostLedger measured cost (n=2 per lane, not yet at the n>=8 bar): MOONSHOT 14.8 %, QUALITY 19.1 %.
- Chase: 2.9 % median.
- Live book: canonical 2W/12L.

## (a) Entry

| | LIVE position | SHADOW leg at 8363 | 8366 |
|---|---|---|---|
| Price | Wallet fill after decision -> quote -> land. EntryChase7961 measures the decision-to-fill chase (`truth/EntryChase7961.kt:59`). | Signal mark x (1 + lane chase). The lane chase is the measured median at 8+ live fills, else the 2.9 % model (`truth/ShadowFill8364.kt:43`; XS `truth/ExploreShadow8347.kt:455`, ES `truth/EvidenceShadow8344.kt:270`). | Unchanged (aligned in 8364). |
| Route | PumpPortal for a pump.fun mint. `PumpLanding8288.skipPumpPortalBuy8288` (`engine/Executor.kt:32262`, `engine/PumpLanding8288.kt:49`) routes the buy to Jupiter when PumpPortal landed under 50 % of 3+ buys in 120 min. Every other mint goes to Jupiter. | None. | `ShadowAlign8366.buyRoute8366` mirrors the routing, using the same `routeAway8288` read. |
| Landing failure | BUY\|PUMPPORTAL landed 3/17 and BUY\|JUPITER 18/18 (`engine/CostLanding8169.kt:137`, `SignedTxLedger8085.landRows8169`). An unlanded buy is no position. A FAILED_ON_CHAIN leg pays its fee (`CostMeter8151.noteFailedLeg8151`). An expired leg pays nothing. | Every buy lands. | The route's measured land rate (at 3+ resolved buys; 1.0 before, never guessed) becomes a fill probability. The draw is deterministic, from a mixed hash of mint, lane, setup, open time and entry (`ShadowAlign8366.draw8366`). An unlanded entry is no trade, booked as `-(fee x failed share) / ticket` (UNLANDED_8366). XS: `landed8366`, `ExploreShadow8347.kt:634`, at open and at every arm. ES: `EvidenceShadow8344.kt:275`. |
| Latency | Decision -> fill per lane (`EntryChase7961.laneLatencyMs8094`). | Inside the chase term only. | Unchanged. |

## (b) Cost

| Component | LIVE | SHADOW at 8363 | 8366 |
|---|---|---|---|
| Venue, route and app fees, slippage allowance, depth impact, unreclaimed rent | Paid on chain. Measured by CostLedger7962 as fill vs market on both legs (`truth/CostLedger7962.kt:177`). | `NetEdge8030.costPct8030` at 0.03 SOL. This is max(model, measured at n>=8) (`truth/NetEdge8030.kt:94`, `:111`). | Measured once n >= 8 (`CostLedger7962.measuredCostPct7962`, `:282`), even when it is below the model. The model is used before that (`ShadowAlign8366.shadowCostPct8366` / `laneCostPct8366`). |
| Priority fee and Sender/Jito tip | Wallet SOL. In the model as `CostMeter8151.roundTripFixedSol8151` (`engine/CostTrim8151.kt:280`): 2 measured network fees, the entry tip and the 200k-lamport sell tip. | Already in the model's fixed leg at 0.03 SOL (report: fixedLeg 0.0003 SOL = 1.04 %/side). | Kept, with no double count. Floored at `fixedPct8366` = fixed SOL / 0.03, so a fallback model can never drop it. Shown as `fixedLegs8366=` on the report line. |
| Failed-tx fee | Paid by FAILED_ON_CHAIN buys. | None. | The unlanded entry's booked loss, using `CostMeter8151.failedLegFeeSol8366` (measured average; 0.0003 SOL fallback). |
| Chase | Not in the measured cost: the ledger's entry mark is the market at the fill. | Added to the entry price (8364). | Unchanged, so it is not counted twice. |

## (c) Exit

Live exits are offered on every ~500 ms rapid mark (`SpikeCapture7943.rapidMark`, `engine/SpikeCapture7943.kt:101`). Per rule:

| Live rule | Live source | Shadow at 8363 | 8366 |
|---|---|---|---|
| Lane invalidation stop | `FieldManual7715.mandateFor(lane).invalidationPct` | Same (both books). | Unchanged. |
| Fluid-stop level: net break-even once the peak clears it; a runner's 40 % of a +20 % peak | `FieldManual7715.runnerAwareStop7935` / `costArmedStop7928` (`truth/FieldManual7715.kt:286`, `:303`), via `ExitDoctrine8169` fluid stop (`engine/ExitDoctrine8169.kt:290`) | Missing. The only floor was the 8338 give-back floor, armed at the lane's arm (`giveBackArmPct7791`, ~+20 %). | `ShadowAlign8366.liveStopLevel8366` calls the same two functions (exit LIVE_FLOOR_8366). XS `runnerWhy8366` (`ExploreShadow8347.kt:562`), ES `why8366` (`EvidenceShadow8344.kt:343`). |
| Green floor 8363: max(net BE, 50 % of peak) once the peak clears BE | `GreenFloor8363.floor8363` (`engine/Exits8363.kt:60`). Used by RunnerGrab7967 (`:504`, `:595`), TailBag8034 (`:134`) and LifecycleHold8065 (`:274`, `:342`). | Missing. | Applied on runner lanes inside `liveStopLevel8366`, and inside the lifecycle hold. |
| 8363 scalp stop: max(0.6 x lane stop, 2 x 1m range + chase, 6 %), no stop in the first 60 s | `ScalpStop8363.decide8363` (`engine/Exits8363.kt:137`), via `ScalpExit8351.offer8351` (`truth/ScalpExit8351.kt:64`) | XS SCALP leg used the pre-8363 inline rule `max(0.6 x stop, 4)` from the first tick. | XS `scalpWhy8366` (`ExploreShadow8347.kt:600`) calls `ScalpStop8363.decide8363`. The ladder (+15 % half, +30 % rest), the give-back floor and the 10-min clock come with it. The 1m range and chase are read once per ticket, as live does at the first mark. |
| Spike tiers (+40 % sells 60 %, +120 % / +400 % sell 70 %) | `SpikeCapture7943.TIERS` (`:20`). Live uses the position's learned ladder (`RunnerGrab7967.tiersFor7967`, ExitProfile7955; `:246`). | Prior TIERS. | Unchanged. Gap: the learned per-position ladder is not mirrored. |
| 8338 give-back floor | `FieldManual7715.runnerAwareStop7935` 8337 branch | `EvidenceShadow8344.giveBackFloor8344` | Kept. `liveStopLevel8366` dominates once armed. |
| Lifecycle hold: promotion at +100 % with a +100 % confirmed peak; -40 % floor; peak give-back; green floor; 100/400/900 takes; trend break; unbanked give-back; 72 h dead money; ordinary exits and partials held; no clock | `LifecycleHold8065.onMark8065` / `defer8065` / `partialHeld8065` (`engine/LifecycleHold8065.kt:309`, `:253`, `:300`) | Missing. A +100 % shadow leg still ran spike tiers and timed out at 120 / 30 min. | `ShadowAlign8366.holdPromotes8366` / `holdExit8366` call the hold's own pure functions in onMark8065's order. Spike tiers and the clock stop once a leg is held. Held state persists through ShadowPersist8355 (appended fields; restore window 7 d). Not mirrored: the 15-minute base-broke / trend-break bars (the shadow keeps no bars), and holds granted from the start by LifecycleEvidence8189. |
| Timeouts | No single live clock. ExitManager is dead code; time exits sit in CortexExit7897 (60 min veto), ExitProfile7955 maxHold and HoldingLogicLayer. A lifecycle hold has none. | 120 min (runner lanes), 30 min (others), 10 min (scalp). | Scalp clock = `ScalpExit8351.CLOCK_MS_8351` via decide8363. Held legs have no clock. The other clocks are unchanged (gap). |
| RunnerGrab7967 green lock (up to 6 h) | `RunnerGrab7967.heldExit7967` (`SpikeCapture7943.kt:130`) | Missing. | Its floor (GreenFloor8363) is mirrored; the 6 h hold itself is not (gap). |
| TailBag8034: 25 % tail held to +400/+1900/+4900 %, 7 d, or the green floor | `TailBag8034` | Missing. | Not mirrored (gap). |
| Chart / Cortex exits | `CortexExit7897`, chart exits via `ExitTruthArbiterLive8144` | Missing. | Not mirrored (gap: they need the full Cortex state per leg). |
| ProfitExitTruth8163 requote refusal on take-profits | `ProfitExitTruth8163` (protective locks pass since 8363) | Missing. | Not mirrored (gap). |
| Sell latency | Trigger -> broadcast. PaperExitLatency8223 measures it per class (16 s / 6 s priors; `truth/PaperExitLatency8223.kt:72`). The live fill is the price after that time. | Full exits priced at the trigger tick. | A triggered full exit waits. It fills at the first observed mark at least `sellLatencyMs8366` after the trigger (`ShadowAlign8366.latencyElapsed8366`; XS `step8347` `:530`, ES `why8366`). A mark lost meanwhile closes at the last mark. Partial slices are still priced at the trigger tick (gap). The latency is per exit class, not per lane: that is all PaperExitLatency8223 measures. |

## (d) Marking

| | Price source per tick |
|---|---|
| LIVE | `ts.lastPrice` on the ~500 ms rapid mark (`SpikeCapture7943.rapidMark`). Stops wait for a fresh executable mark (RunnerExitContract8136). |
| ES | Tick price under 45 s, else ForwardReturnLabeler7731.markFor7948, else ShadowMark8360 (tick / labeler / curve / tape under 60 s) (`EvidenceShadow8344.kt:312`). Every open position on every loop. |
| XS | ShadowMark8360.observed8360: tick, labeler, curve, tape under 60 s (`ExploreShadow8347.kt:491`). Round-robin, 600 of up to 3,000 tickets per loop. A ticket can be marked several seconds less often than a live position. A gap of 10 min closes it as LOST_MARK at the last mark. |

## Headline divergences (8363) and what 8366 does

1. **Every shadow buy landed.** Live PumpPortal landed 17 %. Fixed: measured land rate as a deterministic fill probability, and the failed-tx fee is booked.
2. **No fluid-stop or green floor in the shadow.** A +15 % peak rode back to the -15 % stop in the shadow, while live exits at net break-even. Fixed: `liveStopLevel8366`.
3. **The shadow scalp ran the pre-8363 stop** (0.6 x lane stop from the first tick). Its proof was for a stop live no longer uses. Fixed: `ScalpStop8363.decide8363`.
4. **No lifecycle hold.** A +100 % shadow leg kept selling spike tiers and timed out at 120 min, while live holds it with its own exits. Fixed: promotion and the hold's exits; 15-minute bars are not mirrored.
5. **Exits priced at the trigger tick.** Live fills after ~16 s. Fixed: the exit is priced at the first observed mark after the measured latency.
6. **Cost = max(model, measured).** A lane measured cheaper than the model was overcharged. Fixed: measured at n >= 8, else the model, never under the fixed legs.
7. **Paper sizing leak.** `SmartSizer` live perfMult used FluidLearning's paper win rate under 10 live trades (shrink-only since 8296). Fixed: neutral 1.0 until 10 live closes (`engine/LivePerf8366.kt`).

Still not mirrored, all marked "gap" above: the learned per-position spike ladder; RunnerGrab's 6 h hold; TailBag's tail; Cortex and chart exits; the 8163 requote refusal; latency on partial slices; the live time exits outside the scalp clock; the hold's 15-minute bars.

## Alignment metric

`align8366[pairs=n meanGapPts= p50= worst= lane{L n/gap,...}]` is on the report line "Live vs shadow (§8366)" (PipelineHealthCollector).
- Gap = live realised net % minus shadow net %, for the same canonical lane and mint, with entries within 60 s.
- Live side: CostLedger7962.onLiveFinalized, once per trade id.
- Shadow side: every landed ES close, and each XS native NOW leg on the lane's live exit style.
- Records persist under SHADOW_ALIGN_8366.
- Negative = live realised less than the shadow promised.
