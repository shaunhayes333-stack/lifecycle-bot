# AATE full code audit — 5.0.8757 (fixes through 5.0.8759)

**Asked for:** "a full code audit please. bugs defects dead code patches duplicates stacked scoring bad logic".

**How it was done:**
- Seven read-only audits of origin/main at 916e8eb71 (5.0.8757), cross-checked against the 5.0.8756 live log (`aate_pipeline_20261115_150239_790`).
- The seven areas are buy path, data, learning, accounting, exits, discovery and static analysis.
- The static pass uses a Kotlin lexer and a reachability index over 1,832 files and 638,333 lines.
- Every finding below has file:line evidence and a fix in its area report under `audits/code_audit_8757/`.

**Severity:**
- **P0:** loses money, corrupts learning, or leaves a position unprotected.
- **P1:** wrong decisions under realistic conditions.
- **P2:** quality, rot, latent risk.

**Status column:**
- **FIXED:** shipped and tested.
- **PART:** partly fixed.
- **OPEN:** not fixed yet.

---

## 1. The short answer

The bot is not short of strategy. It is short of **truth**. Four things are wrong at the root:

1. **The numbers it learns from are partly fabricated.**
   - Stale curve prints are stamped "now" and labelled chain-witnessed.
   - Seed prices still become label bases.
   - Hour-lane 15-minute outcomes are filed as 5-minute results.
   - The model that holds the live veto (TreeRanker8737 → Mind8746) trains on rows that were never fillable.
2. **One fact is scored many times.**
   - Seven learners score the same coin-state cell (origin, depth, age, liquidity, cap). Each holds its own door, and the doors combine with AND. In a market whose base rate is negative, each learns "this cell loses", so almost nothing passes.
   - Sizing is a product of about 40 multipliers that collapses onto hard-coded floors.
3. **The money ledger is rewritten on every boot.**
   - Four "repairs" overwrite realized P&L and cash with different formulas.
   - Quarantined positions are refunded at cost: paper shows +185 SOL realized while the paper book is −8.3 SOL.
   - A live final sell after a partial is never journaled, so live P&L, the governor and the mirror's loss stop all miss the losing last slice.
4. **Exits and discovery leak.**
   - The stop-wait logic could hold a −15 % stop to −60 %. This was in my own 8631/8706 work and is fixed in 8759.
   - Evicted coins come back as "brand new".
   - The scanner runs about once a minute instead of every 8–10 s.
   - The process runs out of memory (OOM) from allocation churn.

The Cortex setups show positive while buys lose for three reasons:
- Those setups are scored on 5-minute forward labels that include the fabricated rows above.
- They are small-n: only TURNOVER_SPIKE@SHITCOIN is proven, at lb +4.8 %.
- The live path refuses them through the stacked learners in item 2 anyway.

---

## 2. Ranked findings (all areas)

### P0

| # | Area | Finding | Where | Status |
|---|---|---|---|---|
| 1 | exits | The unread-stop clock was deleted when the stop fired, so the sell-time check started a new 6 s wait and held the stop it had just let through. doSell-direct stops (STRICT_SL, LANE_HARD_15, CATASTROPHIC_HARD_BACKSTOP) never started a clock. | `StopMarkConfirm8418.kt` holdForVenueRead8631 / sellsWithoutRouteQuote8706 | **FIXED 8759** (one shared breach clock, kept after firing) |
| 2 | exits | The unread hold had no upper bound: an off-venue mark never sold above −60 %. The 3 s ask gap restarted the wait for the 30 s risk clock and the 7.8 s cycle, so their stops could never fire. | `StopMarkConfirm8418.kt` unreadStopFires8706 / breachStart8712 | **FIXED 8759** (30 s bound; recovery measured per mark; 35 s ask gap) |
| 3 | exits | The doctrine trail ran before RAPID_CATASTROPHE and RAPID_HARD_FLOOR and returned true even when coalesced, so for doctrine positions the emergency stops never ran. | `BotService.kt` evaluateRapidMonitorExit | **FIXED 8759** |
| 4 | buy | The 8757 paper mirror skipped the executable cost check, used the paper 5-minute price bar, ran after the buy-door proof, and could override curve-nobody-bought, launch-cap, net-edge and collapse refusals. | `PaperMirror8757.kt`, `CandidateAdmission8136.kt:1626` | **FIXED 8758** |
| 5 | buy / acct | The mirror's −0.1 SOL stop does not bound the loss. It counts realised closes only, lives in memory (every OOM restart re-arms it), ignores open mirrored positions, and misses final slices after partials (item 9). | `PaperMirror8757.kt:55`, `LiveEntrySafetyHold.kt:754` | **PART 8758** (4/hour cap); persist + count open + fix item 9 still **OPEN** |
| 6 | buy | At the live gate the mirror still passes record-protecting doors (`doorProtects8715`, `verifiedRecordDoor8650`) that are not on its own hard list. There are three different "hard refusal" lists. | `LaneAuthority8577.kt:334`, `PaperMirror8757.kt:27`, `FdgGate8365.kt:301` | **PART 8758** (4 reasons added); one shared hard list **OPEN** |
| 7 | accounting | The startup identity rebuild refunds quarantined or removed cost basis as spendable paper cash. | `BotService.kt:7105-7121`, `CanonicalPositionAuthority6441.kt:911` | OPEN |
| 8 | accounting | Every boot rewrites realized P&L, open cost and cash with four repairs (6502 / 6504 / 6508 / 6505) that use different formulas, including mint-level FIFO with no quarantine filter. | `BotService.kt:7064-7121`, `FillLotLedger6504.kt:285` | OPEN |
| 9 | accounting | A live final SELL after any partial is never journaled: `preRemainingQty` is the original buy amount, so the replay rejects the row. Live P&L, the governor and the mirror stop miss it. | `Executor.kt:29376`, `EconomicEventSchema6464.kt:342`, `JournalEconomicReplay6619.kt:816` | OPEN |
| 10 | data | QuietCurve8750 stamps a stale curve print "now" with chain-witness rank, so quiet or unseen coins book exactly −cost labels. | `QuietCurve8750.kt:50`, `ForwardReturnLabeler7731.kt:2176` | OPEN |
| 11 | data / learning | TreeRanker8737, the dominant voter of Mind8746, which holds live refusal authority, trains on fill=false rows, pre-8660 venue-poisoned rows and mixed-horizon rows. Its record is not shown; the brief shows the ridge's. | `TreeRanker8737.kt:106-129`, `EdgeModel8537.kt:401` | OPEN |
| 12 | data | Seed prices still become the label basis or outcome through TOKEN_STATE adoption; `seedPrice8559` misses `…MCAP_SEED_6401`. | `ForwardReturnLabeler7731.kt:2196, 3151`, `TokenFacts.kt:196` | PART 8627; OPEN |
| 13 | data | Hour lanes' 15-minute outcomes are booked into the "5-minute" slot and published as "5-min executable net". | `ForwardReturnLabeler7731.kt:1790, 1984, 1999` | OPEN |
| 14 | data | No plausibility check covers 2x–10x moves at 5–15 min for non-curve coins. The +5,000 % / +1,200 % caps would not have caught the 8627 artifacts. | `EdgeModel8537.kt:477`, `LabelEvidence8386.kt:36` | OPEN |
| 15 | learning | Stacked base-rate vetoes: one coin-state signal is scored by seven learners, each with a door, combined with AND. The learners are MarketCohort8701, PocketLedger8610, PayingCell8723, ManagedBands8719, the EdgeModel ridge, TreeRanker8737 and Mind8746. | learning.md §2 | OPEN |
| 16 | learning | Size multiplier collapse: about 40 sequential multipliers, several reading the same bucket, ending on hard-coded floors. | learning.md §3, static.md §3.12 | OPEN |
| 17 | discovery | OutOfMemoryError: 2–5 GB/min of allocation churn, plus 157 MB the census cannot attribute. Causes include unclosed error responses, one feed parsed by several readers, HTTP clients built per call, and debug strings built then discarded. | discovery.md F2 | OPEN |
| 18 | static | Safety checks on the buy path pass when they throw: the rug blacklist, RebuyLockout, holderBlock, several LiveEdgeGate refusals, flat-chart / depth refusals, wave / bleeder vetoes and lane pauses. | `Executor.kt:19223, 14608, 14045`; `PreTradeHardGate.kt:189`; static.md item 2 | OPEN |

### P1 — wrong decisions

| # | Area | Finding | Where | Status |
|---|---|---|---|---|
| 19 | exits | The doctrine trail priced off the stored peak, which has held one-print phantom peaks (+12,105 %). One bad mark at or below the line sold a runner whole. | `RunnerDoctrine8584.kt`, `BotService.kt` doctrineTrail8630 | **FIXED 8759** (peak two marks agree on; two marks under the line) |
| 20 | exits | The doctrine ladder dies at its first rung: a +100 % peak promotes to LifecycleHold, which refuses the rungs, while the doctrine holds the hold's own takes. The +100 / +400 / +900 % rungs never sell. | `LifecycleHold8065.kt:317`, `SpikeCapture7943.kt:241` | OPEN |
| 21 | exits | Full arbiter exits go through the partial door's +8 % breakeven guard and are dropped on flat or red positions. The −15 % collapse branch is unreachable. | `Executor.kt:24401` | OPEN |
| 22 | exits | The sub-trader sweep calls `executableConfirms8419()` before checking pnl, which resets the real stop's confirmation clock and burns the quote budget. | `BotService.kt:12594` | OPEN |
| 23 | exits | The 500 ms monitor still sells inline (rug escapes, paper/zombie catastrophe, instant profit capture, sub-trader floor), so one live sell blocks every other position's stops. | `BotService.kt:11583…12602` | OPEN |
| 24 | exits | DANGER_ZONE aggregate prices inherit the entry pool, so stops treat an oracle print as on-venue. | `BotService.kt:11888`, `TokenFacts.kt:208` | OPEN |
| 25 | exits | The doctrine line jumps at 2x for stops over 25 %: a position at +45 % whose peak ticks to 2x sells at once. | `RunnerDoctrine8584.kt:86` | OPEN |
| 26 | exits | TICK_PROFIT_LOCK / _GAPPED still runs on doctrine lanes and sells whole on one tick mark at pnl ≤ 0. | `BotService.kt:13440-13530` | OPEN |
| 27 | exits | The finality resume books a landed partial without booking the rung, so the same rung sells twice. | `Executor.kt:23150` | OPEN |
| 28 | exits | Moonbag tiers are latched before the sale, held in memory and cleared wholesale; after a restart they sell again. | `MoonbagRunner7322.kt:128` | OPEN |
| 29 | exits / buy | The paper book's exits differ from live: no ~15 % hard floor, trail arms at +10 % (live +25 %), LOST_MARK closes are valued flat. The mirror's premise ("live does what paper does") is false on exits. | `MemePaperTrader8544.kt:284-437` | OPEN |
| 30 | buy | The edge model is the #1 live refuser, but the brief reads a different record and says no lane has authority. | buy.md 5 | OPEN |
| 31 | buy | Admission and gate price cost differently; the measured-cost refusal is never emitted, so it is advisory. | buy.md 6, 7 | OPEN |
| 32 | buy | The bot's own realised-loss doors are advisory, so a losing lane is not throttled; RUG_PRONE entry wait is dead in live. | buy.md 8, 9 | OPEN |
| 33 | buy | When admission fails or returns a non-specific REFUSE, the live gate fails open; the FDG seal re-quote trusts the admission's net unless size moved. | buy.md 10-12 | OPEN |
| 34 | data | LIQUIDITY_USD mixes curve real SOL ×1, pool SOL ×2 and DexScreener totals in one field and one set of bands. | `LiquidityTruth8037.kt:45`, `OnChainDepth8038.kt:138` | OPEN |
| 35 | data | Received-at stamps make cached DexScreener / Jupiter prices look fresh; labels compare marks across venues; TokenFacts mixes clocks and refuses real chain reads as "older" (1,486 / 434 in the log). | data.md P1-2…P1-4 | OPEN |
| 36 | data | Age falls back to pool creation (a lower bound) and is exported as coin age; the stage router defaults buy/sell pressure to 50/50 and liquidity to 0. | data.md P1-5, P1-6 | OPEN |
| 37 | data | The ridge and lane books count one coin once per lane (up to 12x), so the error bars look 3x tighter than they are. | `EdgeModel8537.kt:518` | OPEN |
| 38 | learning | Mind8746's frozen-feature key collides across lanes; features and labels are mismatched. | learning.md §4 | OPEN |
| 39 | learning | Inverted safety learners: GovernorRecovery promotes a demotion; FluidLearningAI loosens on a lower win rate; the gate relaxer reads no data as a 100 % win rate. | learning.md §8-10 | OPEN |
| 40 | learning | The managed target applies the stop twice from two stop sources and removes stop slippage; refusal books are survivor-biased. | learning.md §7, §12 | OPEN |
| 41 | discovery | An evicted coin is re-admitted 30 s later with a new `addedAt`, so it goes back to the fresh tier and is protected from eviction (bypasses the 8631 fix). | `GlobalTradeRegistry.kt:632`, `SolanaMarketScanner.kt:4313` | OPEN |
| 42 | discovery | The scanner runs about once a minute because the LLM tuner can set `scanIntervalSecs` to 60 s with no clamp. The cycle is a fixed 8 s sleep. | `LlmParameterTuner.kt:145`, `BotService.kt:22477` | OPEN |
| 43 | discovery | PumpPortal create intake (~1,000 lines, global locks) runs on the WebSocket reader thread. | `PumpFunWS.kt:1105` → `BotService.kt:11157` | OPEN |
| 44 | discovery | 48 worker leases go onto a 16-thread pool and queue time counts against the 15 s budget (the inflight cap fired 42/42 cycles). Five readers hit `token-profiles/latest` every batch. | discovery.md F7, F8 | OPEN |
| 45 | discovery | Crypto-desk hand-offs enter meme intake as `DEX_TRENDING`, the top pre-FDG source. | `CryptoAltTrader.kt:860` | OPEN |
| 46 | discovery | Thread-safety: TokenMergeQueue's check-then-put, an unsafe `scanners` set, an unbounded plain-HashMap `proposalTrackers`, and a fast lane with an un-normalised `paperMode`. | discovery.md F11-F13 | OPEN |
| 47 | discovery | Non-pump launches have no venue price and block on DexScreener indexing while taking selector slots. | `BotService.kt:26192` | OPEN |
| 48 | static | The V3 DecisionEngine always reads liquidity as zero, because it looks for `liq=` in text that never has it. | `v3/decision/DecisionEngine.kt:253` | OPEN |
| 49 | static | Fail-direction disagreements: the frozen-token check returns true on error in two places and false in two others. Cost, stop and size fall back to different numbers per file (cost 3 / 5 / 6 / 7; stop 10 / 15; size 1.0 = full). | static.md items 3, 4 | OPEN |

### P2 — rot, duplicates, dead code (totals from the static pass)

- **Dead code: about 33,970 lines (5.3 %).**
  - 236 top-level declarations and 2,104 member functions nothing live calls.
  - 95 whole dead files (12,697 lines): the voice stack, ExitManager, TelegramBot, and old 6xxx truth objects.
  - About 12,800 dead lines are kept alive only by tests.
  - A 240-line `if (false && …)` block sits in `Executor.maybeAct`.
  - Dead private functions: `realisticEntrySize6867` (393 lines) and `runSupervisorPhase` (152 lines).
- **Duplicates:**
  - Entry timing ("buying the top"): 8 definitions.
  - Stop sources: at least 15.
  - Cost models: at least 10.
  - Age readers: at least 20.
  - Lane-name normalisers: at least 25, and they disagree.
  - "Proven" bars: 269 constants in 154 files, but only one Wilson bound.
  - Freshness windows: about 40 (10 s to 10 min).
  - Fill-lot ledgers: 3.
  - The crypto brain is copied from the meme brain.
  - The Commodities / Forex / Metals traders are triplicated.
  - Code clones: 137 exact-copy and 123 near-copy function groups.
- **Patch rot:**
  - 22,674 comment lines are version history; 56 % of types carry a `####` suffix.
  - The chase door flip-flopped four times (8582 → 8604 → 8626 → 8706, then 8757's mirror).
- **Swallowed errors:** 86 % of catch blocks swallow (17,213 of 19,953), and 10,492 are empty.
- **Giant functions:** `processTokenCycle` is 7,698 lines, `FDG.evaluate` 4,727 and `liveBuy` 3,614.
- **Production flags off, but on in tests:** `EvidenceHold8336`, `LiveNoPaper8184`, `ExploreLive8733`, `forceLeaders8631` and `useKellySizing`. Tests cover paths production never runs.
- **Learning P2s:**
  - PocketLedger's score is not a statistic.
  - CanonicalEntryFloor can never move down.
  - BrainConsensusGate is computed and discarded.
  - The LLM personality has economic authority.
  - Cortex7885 memory grows untrimmed.

---

## 3. Repair order

Each step removes a cause, not a symptom. Steps 1–3 are about truth, and should land before any tuning.

1. **Money truth (acct 7, 8, 9; buy 5):**
   - Journal the final sell after partials.
   - Keep one realized authority and make the four boot repairs report-only.
   - Make quarantine book the loss.
   - Persist the mirror's loss window and count open mirrored positions.
2. **Label truth (data 10–14, 34–37):**
   - QuietCurve marks are not witnessed marks.
   - No seed basis anywhere.
   - One horizon per slot.
   - Add a 2x–10x plausibility check.
   - Use one liquidity unit.
   - Count each coin once.
3. **Train the voter on fillable rows (learning 11, 38).** Then reset only the `TREE8737` / `MIND8746` out-of-sample records, which were measured on bad rows (the learned cells stay). Show the deciding model's record in the brief.
4. **One cell learner (learning 15):**
   - Keep one coin-state cell model with authority.
   - Make the other six report-only.
   - Size in log space with one cap (16).
5. **Fail closed and use one hard list (static 18, 49; buy 6, 31–33).**
6. **Exits (20–29):**
   - One ladder on doctrine lanes.
   - Full exits bypass the partial guard.
   - No inline sells in the monitor.
   - Paper uses live's stop stack.
7. **Discovery and memory (17, 41–47):**
   - Eviction memory.
   - Clamp the scan interval.
   - Move create intake off the WebSocket thread.
   - Close error responses.
   - Single-flight feed readers.
8. **Delete the dead code and fold the duplicates (P2).**

## 4. Shipped from this audit

- **5.0.8758:** the mirror keeps the cost check, the live price bar and the buy-door proof. It also gets four more hard refusals, is never active in shadow rehearsals, and is capped at 4 admits an hour.
- **5.0.8759:**
  - Unread stops: one bounded breach clock (request path and sell-time check), a 30 s cap, and a breach that ends on a recovered mark.
  - Doctrine trail: runs after the catastrophe and hard-floor stops, prices on the peak two marks agree on, sells on two marks under the line, and reports true only when a sell was dispatched.
