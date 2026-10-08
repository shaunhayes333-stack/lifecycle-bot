# AATE Cortex — plan v2

v2 keeps v1 (snapshot → voters → fusion → constitution → action authority → outcome truth →
calibration) and adds the three things v1 was missing:

1. A data economy, so intelligence is bought where it pays.
2. Trade-shape learning, so the bot changes *how* it trades rather than *how much*.
3. Respect for the route minimum, which v1's sizing ignored.

Evidence comes from the 5.0.7876–7880 snapshots and the tuning-stack audit of 08 Oct 2026.

---

## A. What the audit changed

| Finding | Consequence for the design |
|---|---|
| About 20 outcome-driven size multipliers chain on every live entry. With a 0.0435 SOL route minimum on a 0.16–0.20 SOL wallet, each one either gets promoted back to the minimum (no effect) or refuses (a hidden veto). | Size is **not** a learning lever until equity is ≥ 3× the route minimum per open slot. Below that, learning acts through admission and trade shape only. |
| No learner changes entry timing. TradePlan setups are fixed rules. TacticSwitcher nudges the score ±6–8 on SHITCOIN only. | Timing becomes a learned per-lane shape parameter (§C). |
| Tokenomics knowledge (ExpertTraderKnowledge7813 feature stats, e.g. `SHITCOIN HOLDER_QUALITY POS -18.6%`) is computed, but only ranking reads it. | Feature stats become shape-rule updates with authority (§C). |
| No loss is attributed to a cause. | Cause attribution is the core of §C. |
| Generic layers overwrite lane logic: TradePlan's stop/target, one Field Manual mandate for all runner lanes, the streak floor, the 7807 ×0.6 Moonshot cut. | Lane logic owns its trade. Generic layers become Constitution rules (refuse/constrain) or voters, never silent overrides. |
| Sample mismatch: TacticSwitcher reacts at 2–8 closes; oracle and policy heads need 30–100 live closes. | Every learner uses hierarchical shrinkage toward the cheat-sheet base, weighted by n/(n+k). Forward labels count as evidence, so learning starts from trade one. |
| Loss evidence is double-counted (ColdStreak ×2, drawdown ×2). | One outcome truth (v1 §2.7); each piece of evidence is counted once (v1 §2.4). |

---

## B. Data economy (Build 1 = 5.0.7881, live)

**Shipped.**

- **Meter.** Every Helius call is priced by method from Helius's published table. Websocket bytes are metered too.
- **Tiers.** EXECUTION and DECISION are never refused. ENRICHMENT runs on a paced daily budget, with each consumer's share scaled by the measured value of its signal. Proven signals can borrow; measured losers keep only a trickle.
- **Snapshot section.** "Helius credit economy".

**Next: progressive enrichment (value of information).** This is the "smarter, not limited" step.

1. Every snapshot feature declares its **cost** (credits and latency) and its **state** (`OBSERVED | UNKNOWN | STALE`). This extends v1 §2.1.
2. Voters run in tiers:
   - **Free tier first:** scanner payload, PumpPortal stream, cached marks, cached safety.
   - **Cheap tier next:** RPC reads at 1 credit.
   - **Expensive tier last:** Enhanced or DAS calls at 10–100 credits, and LLM calls.
3. After each tier, the Cortex computes the fused edge and its uncertainty. An expensive feature is fetched only when:
   - the candidate's fused edge is within the feature's historical swing of the admission threshold, i.e. **the answer could flip the decision**; and
   - the feature's measured predictive power (AuthorityLedger, per lane) justifies its price.
   - Otherwise the decision is made without it, and the skipped fetch is logged as a saving.
4. **Immutable data is bought once.** Launch block, creator history and confirmed transactions are cached by content: launch-block analysis for 6h, creator for 7 days, transactions for good.
5. **Streams are sized by value.** The smart-money stream already is (7881). Mint log subscriptions should cover held and decision-stage mints, not the whole watchlist.
6. **Accounting.** Credits per decision, credits per trade, and credits per unit of decision value appear on the scoreboard. A feed whose credits per decision value doesn't fall over a week is demoted to a smaller share automatically.

---

## C. Trade-shape learning (Build 2)

**Goal.** A loss changes the *rule that made the trade*, in the lane that made it, within bounds around the lane's educated base.

### C.1 ShapeBook per lane

Each lane exposes its own trade shape as named, bounded parameters with a cheat-sheet base value:

- **Entry gates:** minimum liquidity, market-cap band, maximum top-holder %, minimum buy pressure, maximum run-up from create, maximum token age, minimum volume.
- **Timing:** `ENTER_ON_SIGNAL | WAIT_PULLBACK | WAIT_BREAKOUT | WAIT_RECLAIM`, plus a pullback-depth band.
- **Exit:** stop distance, first-target %, partial %, trail distance, trail-arm %, maximum hold.

The lane's TraderAI reads these values instead of constants. Base values are today's constants, so day one behaves exactly as today.

### C.2 Trade record

For every live close **and** every forward label (refused candidates included, so learning starts at trade one), store:

- the entry feature vector the lane's own logic used;
- the path: MAE, MFE, time to MFE, time to MAE;
- the exit that fired;
- what the price did afterwards (ExitRegret7752).

### C.3 Attribution

Per lane, compare winners and losers on each shape parameter's feature:

- **Entry features.** If losers concentrate where `topHolder > 25%` (lift with confidence interval), the lane's `maxTopHolder` moves toward 25%. ExpertTraderKnowledge7813 already computes the per-feature stats; this gives them authority.
- **Timing.** Compare forward labels of "entered at signal" with "would-have-entered at first pullback of X%". If the pullback entry dominates net of missed trades, the lane switches to `WAIT_PULLBACK`. This needs a counterfactual pullback label, which is a small extension of ForwardReturnLabeler7731.
- **Stop.** Set the stop just beyond the 80th percentile of winners' MAE, never inside normal noise. ExitRegret7752 already proves the current stops cut runners: realised -5.2%, price +24.9% after the exit.
- **Targets and trail.** Set them from the winners' MFE distribution and time to MFE.

### C.4 Bounds and learning rate

- Each parameter moves at most one step per K closes, within `[base × 0.5, base × 2]`, shrunk toward the base by n/(n + k).
- Every change is logged with its evidence.
- Every change is reversible by the same evidence.

### C.5 Authority

ShapeBook changes are binding for the lane that owns them. Generic layers may refuse a trade (Constitution) but may not rewrite a lane's shape.

### C.6 Fixes included in this build

- **Lane hint.** `AgenticStyleRouter.decide` at `BotService.kt:26378` passes no lane hint, so every lane reads the SHITCOIN tactic. It will pass the lane.
- **Double counting.** ColdStreakDamper and drawdown count once.
- **Size below the route minimum.** Size multipliers stop acting below 3× the route minimum. The pressure they carried moves into admission (LiveEdgeGate7877) and shape.

---

## D. Sizing (replaces v1 §2.6 sizing)

`size = clamp(riskBudget(wallet, lane) × f(fusedEdge, uncertainty) ÷ (stop + cost), routeMin, laneCap)`

- **Below the route minimum,** the choice is binary: trade at the minimum, or don't trade. That choice belongs to admission.
- **Kelly fraction** is capped at 0.25 once edge is proven. Before that, the route minimum applies.
- **Floors only refuse.** No shrink-then-floor reversal.

---

## E. Phase order (v2)

| Build | Content | Status |
|---|---|---|
| 1 (5.0.7881) | Data economy: meter, tiered value-paced purse, stream sizing, bundle waste removed | pushed |
| 2 (5.0.7883) | Trade-shape learner (§C) + lane-hint fix + double-count removal | pushed |
| 3 (5.0.7884) | v1 Phase 0 data-truth items, verified against current code (marks, exit priority, accounting) — see §G | pushed |
| 4 (5.0.7885) | v1 Phase 1: snapshot, opinions, voter registry (36 voters), scoreboard | pushed |
| 5 (5.0.7886) | Progressive enrichment: paid bundle fetch bought only while its Cortex seat is learning or earning (10% exploration otherwise); realised whole-position outcome filed by entry verdict | pushed (first feature) |
| 6 (5.0.7885) | v1 Phase 2: outcome truth (forward labels) + authority ledger (prequential skill, evidence and error-correlation discount) | pushed |
| 7 (5.0.7885–7886) | v1 Phase 3: Constitution (refuse-only, named rules C1–C3); SymbolicContext mood layer made observation-only (7886) | pushed; migrating scattered vetoes still open |
| 8, 10 (5.0.7885) | Fusion decides paper refusals and live refusals/overrules on bar V1 evidence | pushed |
| 9 (5.0.7887) | Single exit authority, first step: one ordinary stop distance per position (StopAuthority7887 = plan, else lane base × LaneExitTuner/ExitRegret multiplier, runner floor 15%, 4–25%) read by the risk clock, STRICT_SL fallback, rapid fluid stop and tick floor; the LIVE rapid "catastrophe" moved from −14% to −25%. Exit proposals with one lock are still open. | pushed |
| 11 | v1 Phase 7: clean-up | |

Each build is one green CI run on main, followed by a snapshot review.

## F. Decisions taken (operator: "fix it all")

- **Phase order:** as table E. The data economy goes first because it was burning money.
- **Promotion bar (revised in 5.0.7885):**
  - Voter authority is earned on forward labels: net of cost, on admitted and refused candidates alike, scored prequentially. Live closes are too few to seat anything for weeks, and they are graded by exits rather than by selection.
  - The Cortex's own say is a separate, versioned bar (CORTEX_BAR_V1) over its decision-time verdicts (§H).
- **Sizing:** §D (fractional Kelly ≤ 0.25 once proven; route minimum before).
- **Paper:** the shadow book stays the paper surface while live; Cortex decides in paper in Build 8.
- **Retiring voters:** zero-authority voters stay in shadow at the free tier only. They lose any paid data budget (§B.6).
- **Collective (Turso):** a voter on other instances' LIVE rows only, deduplicated. Zero authority until calibrated.

## G. Phase 0 as verified against 5.0.7883 (Build 3 = 5.0.7884)

| Item | Finding in current code | 7884 |
|---|---|---|
| Marks C1 | Fallback chain served DexScreener pairs up to 45 s (135 s rate-limited) old, stamped "now"; repaired exit marks from 60 s-old quotes stamped "now" | Stale pairs skipped (>10 s); repaired marks carry their observation time |
| Marks C2 | Live off-route loss locked to entry price for up to 180 s; exit classifier read `lastPrice` with no age check | Silence window 60 s; classifier uses a fresh tick, else a fresh canonical mark |
| Marks H1 | Intake rug check compared candles by position (ChartHistoryFetcher seeds one 24 h back) → permanent blacklist | Window must span ≤10 min with newest ≤120 s |
| Exits F1 | Catastrophe and hold-gate bypass already correct | No change |
| Exits F5 | Escalation worked but waited one retry window | Escalation dispatches on the next tick |
| Exits F2/F3 | ~14 stop authorities | Deferred to Build 9 (single exit authority) |
| Exits F4 / NONE class | FLUID_FLOOR, EARLY_CUT sold on stale marks; five loss exits resolved to NONE | Veto covers them; they are HARD_SL. STALE_PRICE_FORCED stays veto-exempt by design |
| Config F1 | RemoteKillSwitch never polled | Polled from KillSwitch init; live entries refuse while killed |
| Sizing S4 | Row paper/live fell back to `Position()` default | The row's own mode decides |
| Config F2 | Wallet heal could promote a stored paper position; watchlist entries left the holding unmonitored | Conflict logged, never promoted; empty entries adopt the live position |
| Concurrency C2 | Markets stop in paper runtime sold live positions | Only a live-runtime stop liquidates |
| Cross-asset F1 | Runtime pause not honoured by forex/metals/commodities/stocks | Pause refuses them. Executor seal check deferred |
| Accounting F2 | Fixed (live receipts net of fees) | Paper zero-net edge left |
| Accounting F1/F3 | Profit-lock partial bypassed the canonical slice | Commits through the canonical slice (signature-idempotent) |
| Accounting F4 | Several recordTrade learners graded the last slice | Deferred to Build 6 (OutcomeTruth) |
| Learning F1/F2 | LanePolicy shared paper/live cells | Paper cells keyed separately; live keeps its history |
| Learning F6 | Exit-optimality label inverted | Fed by ExitRegret7752's after-exit price |

## H. Cortex as built (5.0.7885)

- **Voters.** 36 cheap, side-effect-free reads (`engine/cortex/CortexVoters7885`):
  - specialist cache: own score and confidence, breadth, mean
  - V3 score and confidence
  - strategy and flow scores
  - CrossTalk, SuperBrain and capital-efficiency brains
  - forward model EV and P(win), score expectancy, pattern classifier
  - token memory, expert prior
  - plan setup and R:R, edge-gate cell, shape rule, launch read, stage fit
  - rugcheck, top holder, safety penalty
  - buy pressure, 5m/1h change, liquidity, market cap, sentiment

  A voter abstains with NaN; it never fakes a neutral.
- **Ledger.** Per (voter, lane), each raw bin's net return is learned and shrunk toward the lane mean (K = 20). Every prediction is scored before its outcome is learned.
  - Skill = decayed out-of-sample error reduction versus the lane mean.
  - Authority = 0 until 60 scores with skill > 0.5%. It reaches full weight at 5% skill.
  - Runner lanes are graded at 240 minutes, the others at 60.
- **Fusion.** The lane prior carries weight 1. Each seated voter's weight = authority ÷ (1 + voters sharing its evidence) ÷ (1 + Σ positive error correlation).
- **Constitution.** Refuse-only rules, each with an id:
  - C1 hard safety (live)
  - C2 stale mark > 180 s (live)
  - C3 proven negative edge
- **Bar V1** (per lane, re-evaluated on every read):

  | Authority | Requires |
  |---|---|
  | Refuse | REFUSE-bucket n ≥ 20 (paper) or ≥ 40 (live), mean + SE < −2%, ≥ 2% worse than the lane's other decisions; runner lanes also need runner rate < 10% |
  | Overrule a live edge-gate refusal | STRONG-bucket n ≥ 40 and mean − SE > +2% |

  Until a lane clears the bar, the Cortex only counts what it would have done (`SHADOW_*`).

## I. Integration principle (operator, 08 Oct 2026)

"The cortex must sit beside and integrate and work together with the current architecture."

- **Reads, never rebuilds.** Every Cortex input is an existing component, read as a voter. StopAuthority7887 is assembled from the lane traders' own stop bands, TradePlan7739, LaneExitTuner and ExitRegret7752.
- **One existing chokepoint.** The Cortex acts through LiveEdgeGate7877, which FDG and TradePlan already call. Its training comes from ForwardReturnLabeler7731 and CanonicalFinalizedTradeBus6464, which already run. No parallel pipeline.
- **Authority only by evidence.** With nothing proven, the current stack decides exactly as before, and the Cortex only records what it would have done.

## J. Builds 7888–7893

| Build | Change |
|---|---|
| 7888 | Profit exits steered by ExitRegret, as stops were in 7877. In LIVE, lane targets (getTpMult) and the plan's full target widen when trails and profit locks were followed by the price running on. |
| 7889 | Five timing and tokenomics voters (age, run-up, peak position, drawdown, mcap/liq). Credits per decision on the scoreboard. |
| 7890 | Collective hive voter. The promotion record decays (~330 decisions), so stale authority fades. |
| 7891 | Veto audit: every existing refusal is graded by its refused candidates' forward return. A rule is flagged REFUSING_WINNERS when proven. |
| 7892 | Tick floor moved behind a helper, because that loop is at the JVM backend limit. |
| 7893 | Conviction sizing (§D). On a lane whose STRONG record is proven, a STRONG candidate is sized toward quarter-Kelly of that record: never below the request, at most 2×, and downstream lane, wallet and liquidity caps still apply. |
| 7894 | The 7878 forced runner size/TP/hold floor is removed: sizing is the lanes' own pipeline. |
| 7895 | Unknown edge. Every V3 UnifiedScorer module (~50, previously only summed) is now its own voter. 45 further voters cover built-but-unused tech (momentum predictor sub-scores, liquidity trend, volume profile, regime transitions, source cohort, narrative heat, meme-cluster crowding, insider score), collected-but-unused data (bundle internals, LP lock, holders, volatility, turnover, short moves, last exit, live alpha pipeline, sweep ranking, dev history, DexScreener boosts and socials) and the V4 meta layer (fragility, narrative heat and exhaustion, lead-lag, CrossTalk fusion direction, global risk mode, portfolio heat). The miscomputed wallet-concentration sentiment block, which zeroed entry scores at ~70–100% "concentration" on nearly every token, is removed. |
| 7897 | **Cortex v3 — exit cortex.** Every open position is sampled every 3 min on all token voters plus its own state (P&L, peak, giveback, time held, share sold). Each sample is graded by the price move over the next 30 min (runner lanes 120 min). Authority is per lane and per bucket under EXIT_BAR_V1: n ≥ 60 across ≥ 15 distinct positions, mean ± SE beyond ±2%. HOLD: an ordinary exit of a winner is held while proven, capped at 60 min, and never a stop, emergency or operator exit. SELL: the cortex exits through the plan-exit path. Otherwise it only records what it would have done (SHADOW_*). |
| 7898 | **Cortex v4 — regime cells** (v1 §2.8 voter × lane × regime). The lane prior is the lane's mean in the current RegimeDetector regime, shrunk to its overall mean (K = 30). Each seasoned voter's bin is refined by its regime-level bin. Applies to both the entry and exit cortex. |
| 7899 | **Cortex v5 — interaction discovery.** In each lane the four most-trusted voters are crossed pairwise. Each joint bin becomes a voter, graded and seated like any other, so a combination earns authority only by out-of-sample skill beyond its parts. As the trusted set changes, new combinations are tried. |
| 7900 | **Cortex v6 — timing cortex** (§C.3 timing). Every captured candidate is also graded on its next 5-minute move, using the same ledger and regime cells. Under TIMING_BAR_V1 (DIP bucket n ≥ 40, mean + SE < −2%), a non-STRONG, non-runner candidate read as a dip is deferred (C4_WAIT_FOR_DIP) and re-evaluated at the lower price. CI now requires the Aate789*/790* tests. |
| 7901 | **Cortex v7 — calibration** (v1 §2.8 calibration slope). Per lane, a decayed regression of the realised deviation on the fused deviation. Buckets read the calibrated edge, so an overconfident Cortex is shrunk to what it earns (slope 0–1.5, 1.0 until 100 graded decisions). |
| 7902 | **Cortex v8 — capital allocation.** When free live cash funds only one more route-minimum trade, and this lane's STRONG reads are proven to beat its NEUTRAL reads by 2%, a NEUTRAL candidate does not take the last slot (C5_SAVE_LAST_SLOT_FOR_STRONG). Otherwise it is counted in shadow. |
| 7903 | v8 route-minimum conversion routed through EconomicUnitInvariant7061. |
| 7904 | **Cortex v9 — self-consistency.** Every power (refuse, overrule, conviction sizing, slot saving, exit hold/sell) is exercised only while that lane's calibration slope is ≥ 0.5. A Cortex whose predictions have stopped meaning what they say loses its say until they do again. The exit cortex is now calibrated too. |
| 7905 | **Cortex v10 — hierarchical prior** (v1 §2.8 cell → lane → global). Each voter's bin prior is the lane mean plus that voter's shrunk deviation on the other lanes, with the lane's own cell subtracted so nothing is double-counted. A new or thin lane uses what a voter proved elsewhere from its first decision. |
| 7906 | Cortex C2_MARK_STALE removed: 5.0.7891 refused 852 live candidates using the token timestamp alone. |
| 7907 | **Lane playbooks.** No lane is on one idea. Each of the 12 lanes carries 3–6 FIELD_MANUAL §4 setups: trend pullback, base breakout/retest, sweep/reclaim, range-low bounce, momentum continuation, launch continuation, relative strength, capitulation higher-low and others. They fire on measurable conditions, including TradePlan bar setups and the FreshLaunch phase. Every setup has an educated prior (structure +1%, flow +0.5%, launch probe 0, no trigger −1%) and is graded per lane on forward labels from trade one. Each candidate is tagged with its best-record matching setup, which is also a Cortex voter. LIVE binding rules: no matching setup is refused unless the lane's NO_TRIGGER record is proven positive (n ≥ 40, mean − SE > +1%); a setup proven losing in that lane is refused (n ≥ 30, mean + SE < −2%, with a runner-tail exemption). Paper is never refused. |
| 7908 | **Discovery quality.** ScannerSourceBrain, which orders and weights scanner intake by source, learned only from closed trades (a handful a day). Every graded Cortex decision now teaches it what that source's tokens did (forward net), so discovery shifts toward sources whose tokens pay from trade one. |
| 7909 | **v1 §2.9 compute model.** Voters run on a bounded two-thread worker pool, off the decision path: 5.0.7891 measured 25.7 ms per assessment inline on the FDG thread. The gate reads only a fresh cached assessment; a miss schedules one and the gate proceeds without a Cortex opinion that cycle. Capture also runs on the pool. Per-voter time budget: a voter averaging over 4 ms is read on a 10% sample only. Playbook classification is cached for 15 s. |
| 7910 | **v1 §2.8 win-probability scoring.** Every seat also scores its P(net > 0), shrunk per bin toward the lane base rate, prequentially by decayed Brier and log loss against that base rate. The fused verdict carries a pooled pWin. Both skills are on the scoreboard beside the return R². |
| 7911 | **Cortex v11 — self-checking prover** (v1 §2.5) **and feature provenance** (v1 §2.1). Invariants run every minute as named alarms: I1 a live position blind for more than 5 min, I2 a live position with no entry price, I3 a Cortex worker backlog, I4 pending stores near their caps. Each assessment classifies price, liquidity, mcap, holders and safety as OBSERVED/UNKNOWN/STALE, and the rates are on the scoreboard. |
| 7912 | **Cortex v12 — outcome truth** (v1 §2.7). Every voter's entry-time opinion is also graded per mode on the whole-position realised return (all legs, fees once) in its own ledger. Real-fill skill sits beside the forward-label skill that grants authority. |
| 7913 | Scheduler can't strand a mint: dropped jobs are rescheduled after 30 s. Scanner source brain persists at most every 30 s. |
| 7914 | **Phase 0 cross-asset seal.** Forex, metals and commodities live orders carry their sealed execution ticket into MarketsLiveExecutor, which refuses an order larger than the ticket's resolved size or a ticket that is not an allowed BUY. |
| 7915 | **Cortex v13 — paper choice** (v1 Phase 4). In PAPER the Cortex now chooses as well as refuses. A candidate blocked on a soft reason (confidence or edge), or declined by its lane on one, is admitted when three things hold: the Cortex reads it STRONG, that lane's STRONG record clears the overrule bar, and the Cortex is self-consistent. One choice per lane per 5 min. It never overrides hard, mode or size blocks (safety, rug, route, freeze/mint authority, duplicates, wallet), never acts on a stale mark, and never acts in LIVE. Chosen positions are booked separately as PAPER_CHOSEN on whole-position closes, so the Cortex's own picks are graded on real exits. |
| 7916 | **Cortex v14 — planners and LLM analysts as voters** (v1 §2.3, shadow scoring of planner proposals). The Super/SSI stack's decision stamp feeds nine voters: plan exposure (WAIT, reduced, base, conviction), world-model tactical EV, thesis pWin, tail, failure risk, latent state, critic fragility, tree confidence and arbiter meta-confidence. The Gemini narrative cache feeds three more: viral potential, scam confidence and BUY/WATCH/AVOID. Each is graded per lane on forward labels and earns authority or none. All reads are cache peeks, with no planner run and no LLM call. |
| 7917 | **Cortex v15 — the legacy size stack, graded** (v1 Phase 5). The FDG's ~30 legacy size factors collapse into one composite shape (6552), which is now the voter LEGACY_SIZE_SHAPE: does the stack's shrink or grow predict the outcome? In a lane where it has been scored 300 times and earned no seat, the stack can no longer shrink a candidate the Cortex reads STRONG on a proven record; that candidate's shape is floored at 1.0, the lane's own calculated size. Absolute caps still apply: the live ceiling, pinned probes, and wallet and route caps. |
| 7918 | **Phase 0 seal, completed across assets.** Tokenized stocks and crypto alts now carry their sealed execution ticket to dispatch, like forex, metals and commodities. One shared check (MarketsLiveExecutor.ticketRefusal7914) refuses any order that is larger than its ticket or whose ticket is not an allowed BUY. |

### Status against the v1 plan after 7918

Done:
- Phase 0, except the legacy last-slice learners in recordTrade (a pinned method at the JVM limit).
- Phases 1–2 and 2.1 provenance.
- 2.3, including Super/SSI planners and LLM analysts as voters (7916).
- Phase 4: the Cortex chooses paper entries (7915).
- 2.4 and 2.5: refuse-only rules, prover invariants, veto audit.
- 2.6: stop authority and conviction sizing.
- 2.7 outcome truth, 2.8 (return, Brier and log loss, calibration, cells), 2.9 compute, 2.10 snapshot scoreboard.
- Phase 6.

Open:
- Phase 5, remainder. The size stack is now one graded voter and loses its shrink power over proven STRONG reads (7917). Deleting the individual factors waits on the evidence that lane-by-lane shows they carry no skill.
- Phase 7 clean-up.
- A UI scoreboard panel.
