# AATE — Lane, Setup, Strategy, Score Polarity and Buy-Timing Audit

**Audit-only repair register for engineering handoff — 5 November 2026 (Brisbane).**
## Executive finding

The present failure is **not adequately explained as a universal `+`/`-` sign reversal**. There are proven, source-anchored **strategy/entry-timing contradictions**, positive scoring of already extended moves, frozen/default input contamination, score-scale compression, a severely incomplete outcome feed, and contradictory ownership/telemetry. Several inversions are **economic/predictive** (higher native scores can preferentially buy later in the move), rather than a Boolean comparator being written backwards. Blindly replacing `STRONG` with `REFUSE` would also make unsupported trading decisions.

**Actual runtime:** 18 chain-finalized live closes in the governor window, 3W/15L (16.7%), PF 0.33, expectancy -0.0020 SOL/trade. Five-minute admitted cohort n299 mean **-12.5%**, refused cohort n300 mean **-1.1%**. That demonstrates poor selection in this sample; the populations are not matched controls, so the difference alone cannot certify one flipped score. Only **187/655** due price marks were priced (28.5%); Cortex graded **33** in the run while assessing **8,730**. There were **730 FDG `DATA_INCOMPLETE_DEFER_8136`** outcomes and only **8 `FDG_ALLOW`** in the main funnel. Three live buys were reported in this 346-second interval. Earlier/lifetime records must not be conflated with this short runtime or the current grading epoch.

**Reading the register:** **[C]** = a code-level defect/contradiction is directly verifiable; **[E]** = runtime failure proven, code path found but exact internal trigger needs episode replay; **[H]** = risky design/predictive hypothesis, needs matched forward evidence. P0 is loss of correctness/trading function; P1 is strategy quality and opportunity capture; P2 is observability/coverage. Line references are to the pinned `main` snapshot, not 8529's exact source commit.

For direct GitHub navigation, prepend `https://github.com/shaunhayes333-stack/lifecycle-bot/blob/86a06e970ef2eaa2bff167bad92e8737ffb2e3de/` to the paths below and add `#L<line>`.

## P0: Correctness and cross-stack repairs

### P0-01 [C/E] The price-label outcome pipeline cannot deliver its promised learning population
**Source:** `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt:62-68,118-130,167-191,2068-2071,2174-2189,2232-2245,2265-2315,2894-2898`; `engine/truth/KeylessMarks8359.kt:34-51,82-130,267-292`.

**Fault:** The 1-minute label has a 60-second grace, off-watch retries are delayed **30 seconds**, fetching is limited to **300 mints per 10 seconds** in asynchronously serialized/bounded batches (up to 45 seconds before a stalled batch releases its slot), the keyless label lane has its own 300-per-tick/1,500-wanted limits, and Jupiter fallback covers at most **50** of each 300 batch. A missed call, queue wait or stale copied mark can exhaust a short target window without a fresh independent price. This is measurable, not theoretical: due 655, priced 187, **468 unpriced**; fanout retryable deadlines 719, price fallback all live sources failed 83, shadow exit mark waits 544. `noSource=0,late=0` here are *terminalized* losses, not evidence that the 468 will all be priced.

**Repair:** Maintain immutable per-(mint, decision, target, source) price requests with priority by approaching deadline; coalesce but do not silently skip; run bounded concurrent source families (curve/reserves, independent trades, pool mark, DexScreener, Jupiter) and publish completed independent marks immediately. Distinguish provider `NOT_LISTED`, `RATE_LIMIT`, `TIMEOUT`, `UNAVAILABLE` and insufficient witness from `PRICE_FLAT`. Persist request state and original target timestamps; terminalize exactly once after a documented window, not before. Make ingress and grading capacity sufficient for actual due rate and demonstrate 15/30/60/300-second and 5-minute label coverage by venue.

**Acceptance:** In an artificial 1,000-mint burst with provider delay/429, every eligible target ends **PRICED** or one explanatory **UNAVAILABLE**, with no stale/synthetic marks, no missing terminal and no double labels; report the priced/due fraction by horizon, source and failure reason. Verify true price history is observed **at target**, never reconstructed from a later mark.

### P0-02 [C/E] Repair/reconcile downtime backfill before treating an unpriced cohort as learned
**Source:** `ForwardReturnLabeler7731.kt:2553-2665,2706-2721`; `engine/truth/DowntimeRecovery8430.kt` (call at 2709 and 2636); `engine/cortex/CortexAuthority8366.kt`.

**Evidence:** `downtime labels queued=149 fetched=1 recovered=0 gaveUp=117` and many OOS cohorts `INCOMPLETE_OUTCOMES`. A queued historical label is not a successful price or a learner update.

**Repair:** Reconcile every queued backfill request to its original target, source-time and source basis; keep unavailable distinct from negative return; supply durable retry/terminal receipts and source constraints. Require a production-style outage/restart test. The exact cause of the 117 `gaveUp` requires queue trace; do not assert from this log that the historical price existed at those exact instants.

### P0-03 [C/E] Setup-specific early pullbacks are overridden by a generic `NO_RECLAIM` gate
**Source:** `engine/cortex/LanePlaybook7907.kt:294-295,355-357,437,489-490,556-569,579-581`; `engine/truth/CandidateAdmission8136.kt:463-470,518-553`; `engine/truth/LiveEdgeGate7877.kt:479-525`.

**Fault:** `MOMENTUM_PULLBACK_5M` intentionally fires on **5-minute change -6% to -1%** while hour trend is positive; `FIRST_DIP_BOUGHT` uses a 15-second buying reversal while the broader candle may still be negative; `TREND_DIP_8383`, `FIRST_PULLBACK_AFTER_RUN`, `TREND_PULLBACK_8383` and `PEAK_RECLAIM_8383` have distinct entry clocks. The candidate's generic `entryWait8506` can be `NO_RECLAIM`. The new native phase witness clears **only** `NO_PHASE_TRIGGER` when `stage == NO_SETUP` (`:466-470`) and cannot clear `NO_RECLAIM`. Without an independently **SUPPORTED** setup (30+ priced labels) the rule at `:544-547` refuses even if the correct *early* setup fired. In the runtime, `ENTRY_NO_RECLAIM` was frequent (112 main-FDG; QUALITY ownerWhy 92, MOONSHOT ownerWhy 234 across distinct counters).

**Repair:** Replace one generic Boolean reclaim with **strategy-owned entry contracts**: early-base, 15-second first-dip rebound, 5-minute pullback, breakout retest and momentum continuation have different required witnesses, quote freshness and maximum chase. A fired setup must never be overruled by an unrelated strategy's chart clock. Native readiness is not proof of expected return; retain economics, sellability, rug and quote controls. Log the strategy-specific signal and the exact first gate that refused it.

**Acceptance:** Fixture with observed `chg1h=+25%, chg5m=-3%, dip=20%, 15-sec buyer reversal`: the pullback strategy is evaluated on *its* bounce witness and quoted net; the momentum-breakout strategy may still wait. Test inverse cases and no-buy dangerous falling knives.

### P0-04 [C/H] Recent-run consumption check explicitly exempts *all* fresh launch windows
**Source:** `engine/truth/StrategyRunner8153.kt:50-95,97-128`; `engine/truth/NetEdge8030.kt:300-337`.

**Fault:** `expected8153` computes remaining move after hour-low runup only for certain bases, but line **68** immediately returns the **full** expected move when `inLaunchWindow8418 == true`. That exemption applies even when a fresh coin has already printed a +70% hour and now trades near its current high. The 8529 code comment records prior late top buys; 8529 log still reports 25 `LOSS_BOUGHT_TOP` and admitted forward mean -12.5%. This is a **conditional logic hole**, not proof every fresh entry is late.

**Repair:** Exempt an early launch **only** from falsely treating its very first move as a full historical hour, not from a documented in-window move already consumed before a fill. Derive distance to peak, last confirmed valid setup event, realized impulse, decision-to-quote drift and measured route latency; cost the **remaining executable** move and strategy's actual payoff. Preserve legitimate first-block bases with no proven prior run.

### P0-05 [C] Native specialist bridge converts unobserved inputs to positive defaults before scoring
**Source:** `engine/SpecialistBrainBridge7542.kt:337-374,388-415`; `engine/SpecialistInputParity8527.kt:59-71,103-130`.

**Fault:** `bp = observedBp8491 ?: 50.0` (`:345`); `volVs = measuredVolume ?: 1.0` (`:339`); `mom = marketInputs8522.momentumPct ?: 0.0` (`:352`). The **post-scoring missing-input correction** loops over opinions but says `if (o.eligible || !o.authoritative) continue` (`:413-414`), so an **eligible** opinion scored partly from neutralized unknown values is never downgraded to `DEFER` at that point. Unknown 50% buy-pressure can earn native points (`QualityTraderAI:358-364`, `BlueChipTraderAI:739-748`, `CashGenerationAI:560-570`), and unknown volume 1.0 earns dip score (`DipHunterAI:431-438`). Additional downstream admission checks sometimes catch this, but do not repair the upstream rank/record contamination.

**Repair:** Native scorers accept typed `(value, observedAt, source, state)` rather than numeric default for a required bullish witness. An unobserved input must not add positive direction/conviction or create a READY/native-qualified observation. Optional unknown features may contribute zero with bounded confidence. Test every positive eligible path with each input individually missing.

### P0-06 [C] Native “setup” and execution style identity can be a generic lane label, not the fired strategy
**Source:** `engine/SpecialistBrainBridge7542.kt:13-18,44-45,388-398`; `engine/truth/CandidateAdmission8136.kt:1370-1404,1538`; `engine/truth/StrategyRank8153.kt:150-180`; `engine/cortex/LanePlaybook7907.kt:1490-1559`.

**Fault:** Every native SHITCOIN/EXPRESS/MANIPULATED opinion is hard-coded to `VOLUME_IGNITION_SCALP`; MOONSHOT is always `DIAMOND_HANDS_RUNNER`, DIP_HUNTER always `PANIC_REVERSION_BOUNCE`, QUALITY `LIQUIDITY_DEPTH_QUALITY`, etc. The independent playbook chooses among actual fired setups, but when no pick exists or a native READY book supplies a fallback, the two strategy identities are not inherently the same. `CandidateAdmission` may derive `nativeSetup8213` from the hard-coded opinion; recorded styles/learning can then describe a different entry thesis from the event that actually fired. **This is a conditional identity divergence**; `StrategyIdentity8153` already fixes many successful admitted paths, so do not claim all three executed buys were mislabeled.

**Repair:** A native opinion must report actual matching predicate(s), signal event time, setup ID, requested entry and exit contract. Join the native ID, playbook selection, FDG decision and sealed position to one immutable `(mint, lane, candidateVersion, setupId, setupRevision)`; no fabricated generic setup on fallback. Test missing-playbook and multi-trigger competing-setup paths, label only what truly fired.

### P0-07 [C] Native ranking discards score scale above 100; “nativeAllow” is counted *before* eligibility revisions
**Source:** `engine/SpecialistBrainBridge7542.kt:38-45,387-415,423-445`; `v3/scoring/MoonshotTraderAI.kt:785,804-825,927`.

**Fault:** `op` applies `score.coerceIn(0,100)`, yet MOONSHOT explicitly permits score up to **150**. Distinct MOONSHOT scores 101, 120 and 150 tie as native 100. `op` calls `record` while opinions are first constructed, then lane-cap, birth-age, missing-data and veto-retirement steps rewrite `out[lane]` later. `nativeAllow` therefore counts **pre-review** positives, not necessarily final eligible/authoritative opinions. The runtime shows seemingly huge native READY numbers with zero owner selection on several desks; attribution must separate this telemetry artifact from a real handoff failure.

**Repair:** Keep typed native raw scale plus normalized percentile, expected net and source evidence separately; make cross-lane ranking comparable without clipping within-lane conviction. Emit pre/post-review counters and distinct candidate identities. Prove that a final `eligible=true` opinion was actually offered to election and that a chosen owner went to FDG.

### P0-08 [E] Ownership/receiver chain has real unevaluated handoffs, not just misleading counters
**Source:** `engine/SpecialistOwnership7951.kt:224-255,391-445,561-710`; `engine/HandoffReceiverTasks8153.kt`; `engine/truth/LiveEdgeGate7877.kt:479-525`.

**Evidence:** 12 lanes alive but QUALITY `ownerSelected=0/FDG block85`, BLUECHIP `nativeAllow=234/FDG=0`, CYCLIC `nativeAllow=401/FDG=0`, CORE `nativeAllow=436/FDG reached1`, TREASURY `nativeAllow=31/FDG reached6`; names for receiver failure include `SCHEDULED_STOPPED_AT_LANE_CYCLE_GATE` and `NAMED_MOONSHOT_NEVER_EVALUATED`. In the 8529 runner register, 52 of 80 raw wrongful refusals remained classified wrong after stricter evidence checks; others were unproven or data gaps. Since counters span different population stages, they cannot be directly subtracted.

**Repair:** Persist election/handoff task receipts with explicit `native_fired → qualified → ready_final → owner_offered → owner_selected → fdg_seen → fdg_allow → ticket → execution → close/label` timestamps and version. Resolve named receiver task stop conditions and prove one native-eligible candidate either gets a final reason or still-pending deadline, no silent lost work. Don't turn every shadowed candidate into a second buy.

### P0-09 [C/E] PROJECT_SNIPER birth hydration pre-empts the very window it trades
**Source:** `v3/scoring/ProjectSniperAI.kt:241-296`; `engine/truth/AdmissionBirth8144.kt`; `engine/truth/TokenBirthHydrator7441.kt`.

**Fault:** `assessTarget` returns `BIRTH_METADATA_HYDRATING_7440` before assessing pre-ignition flow if neither primary nor proof-based fallback resolved age (`:263-270`). A further check requires `snapshot.birthResolved` (`:275-280`). Runtime: 1,261 `NATIVE_BIRTH_METADATA_HYDRATING`, **0 native allows**, birth walks launched 444/resolved 29, sniper scope 173 in-window/1,261 unknown. These are valid uncertainty protections, but the feed's lack of timely canonical mint birth makes the early strategy effectively untradeable.

**Repair:** Ensure creation-slot/time is attached at intake from a verifiable mint-init/launch event and carried without re-fetching on every scan; race bounded on-chain proof and native launch event feeds; record true unknown vs definitely old vs in-window. Never substitute first-seen time as proven birth, and never buy without required age/safety evidence. Test with sub-minute token lifecycle and transient RPC failure.

### P0-10 [C/E] DIP_HUNTER discovery is allowed with no known price path, native dip detection cannot then possibly fire
**Source:** `engine/market/HunterMandates8031.kt:10-33`; `engine/market/LaneHunter7297.kt:175-187`; `engine/SpecialistBrainBridge7542.kt:337-373,394`; `v3/scoring/DipHunterAI.kt:329-392`.

**Fault:** The hunter's `dipFits8031` can let an unknown 5-minute change through to collect a candidate, but native needs a real historical high and bounce witness; the bridge falls back to `high = max(price, history.high ?: price)`, guaranteeing dip depth **0%** without history, and the `DipHunterAI` immediately rejects below 15%. Runtime: DIP_HUNTER native `NATIVE_PRICE_HISTORY_UNOBSERVED=735`, `LIQ_RATIO_LOW=258`, **0 allows**. Unknown must not be treated as a fake 0% dip.

**Repair:** Add a **history-hydration** state at the hunter, source durable previous peak/timestamp/venue, and publish a `WAIT_FOR_DIP_HISTORY` receipt rather than a market judgement. Carry the actual bounce event into native and FDG. Prove the same hydrated history is consumed by the setup matcher and score. Do not allow depth-only falling-knife buys.

### P0-11 [C/H] Cortex inversion authority is mathematically unavailable while outcome delivery is incomplete
**Source:** `engine/cortex/CortexOpportunityPolarity8522.kt:72-109`; `engine/cortex/CortexScoreboard7885.kt:29-58,183-195`; `engine/cortex/Cortex7885.kt:398-415`; `engine/truth/ForwardReturnLabeler7731.kt`.

**Fault:** The polarity intervention requires a current-epoch, lane-specific, rank-validated outcome cohort and otherwise deliberately **ABSTAINS**. In the runtime, Cortex `did` shows 0 live overrides/refusals and per-lane OOS proof repeatedly `INCOMPLETE_OUTCOMES` with authority 0. It therefore cannot automatically rescue excellent refused signals or suppress bad strong ones. **This is primarily a coverage/authority choke, not evidence the code’s inversion comparator is backwards.**

**Repair:** Complete P0-01/02 first; use paired candidate decisions, same mint/strategy/epoch and executable net after fees for rank correlation and signed-calibration proof. Surface `P(up)`, expected net, predicted tail, raw polarity, calibration curve, null fraction, sample and override receipt on every bound decision. Flip only **proven inverted lane/setup cohorts**; never globally negate the score.

### P0-12 [E] Emergency sell broadcast exceeds the protective SLA
**Source:** `engine/truth/LiveEdgeGate7877.kt` is *not* the sell dispatcher; audit `engine/sell/SellRouteSpine8144.kt` / `engine/Executor.kt` and the log’s `SellRouteSpine8144 preRoute8435` trace before making changes.

**Evidence:** 8529 `emergency trigger → broadcast` max **7,079 ms**, over the 5s threshold; `EMRG|JUPITER` average ~4,080 ms, and stop-slip sample n2 worst about +35.5 percentage points (notation needs validation against exact price/side). Two emergency sells were confirmed; do not count this as total execution failure. **Action:** locate precise pre-route queue/mark/quote/serialization delay per transaction and priority route; keep mandatory signing, simulation when required, sellability and finality safeguards. Accept only a timestamped trigger-to-broadcast bound and exact fill/mark slippage accounting.

## P1: Score-polarity, strategy design and entry timing defects / high-priority tests

### P1-01 [C/H] A price *being near its high* is treated as positive setup evidence for several late-stage setups
**Source:** `engine/cortex/LanePlaybook7907.kt:275-308,422-424,486-492`.

At source: `BREAKOUT_HOLD` accepts `pxPeak>=.95` with no hard post-trigger consumption check; `BREAKOUT_RUNNER` accepts `pxPeak>=.90` and already-run **30%-300%**; `RS_LEADER` accepts 1h **+30%** and no price-position input; `TX_VELOCITY_BREAK` requires `pxPeak>=.90`; `NEW_HIGH_PUSH` explicitly requires `pxPeak>=.99` and already-run **40%-400%**. All can be valid for breakout-continuation *if* bid/volume expands after a base and forward potential remains; they are unsafe as generalized “good buy now” evidence. In the run, live entries were disproportionately `AT_TOP/NEAR_HIGH` losers and 25 were classified `LOSS_BOUGHT_TOP` in the broader quality book.

**Repair:** Separate token momentum **quality** from executable **entry**. For each breakout setup require fresh base-break/hold event, volume/buyer acceleration, low-to-entry and high-to-entry distances, available upside and cost-adjusted stop/target. Grade 15s/30s/60s/5m and 1h outcomes by `distanceFromSignal` and entry-time region. Preserve momentum entries that have *measured* continuation beyond the fill.

### P1-02 [C/H] `LAUNCH_CONTINUATION` predicate skips actual `IGNITION`
**Source:** `engine/cortex/LanePlaybook7907.kt:287-290`; `v3/scoring/MoonshotTraderAI.kt:624-636`; `v3/scoring/ProjectSniperAI.kt:281-296`.

`LAUNCH_CONTINUATION` fires only when phase is `PRE_IGNITION` or `EXPANDING`, not `IGNITION`. MOONSHOT's own phase scorer correctly gives `IGNITION` +12. This is a **definition/phase inconsistency**, not automatically proof IGNITION is profitable. **Repair:** Explicitly define `PRE_IGNITION_BASE`, `IGNITION_BREAK`, `EXPANDING_CONTINUATION`, `POST_IGNITION_RETEST` as separate strategies with timing, own labels and candidate contract; ensure no stage falls into a semantic hole.

### P1-03 [C/H] Nine 8383 evidence-built setups include token-quality conditions without an actionable entry event
**Source:** `engine/cortex/LanePlaybook7907.kt:555-591`.

Examples: `SWEET_SPOT_8383` checks cap/liquidity/pressure and nonnegative 5m, no actual entry trigger; `YOUNG_LIQUID_8383` checks young age, buyer share and nonnegative 5m, no price location; `DEEP_RATIO_8383` checks cap/liq ratio and pressure, no timing at all; `STRONG_TREND_8383` fires on 1h **+100%** with buyer share 45% even if already at the spike top; `BAND_MOMENTUM_8383` checks +10%-30% in 5m, +30% hour but no recent breakout/retest or remaining upside; `TREND_DIP_8383` checks drawdown/strong hour/buy share but **no confirmed bounce**. These factors belong in token/strategy *ranking* or specific confirmation, not standalone unconditional buy timing.

**Repair:** Distinguish `TOKEN_QUALITY`, `SETUP_FORMING`, `SETUP_TRIGGERED`, `ENTRY_WINDOW`, `EXPIRED_OR_CHASED`. A quality condition alone never grants entry event authority. Keep all families available to grade and prove, do not indiscriminately delete them.

### P1-04 [C/H] Moonshot's highest native buy-pressure band still rewards potential one-sided blow-offs
**Source:** `v3/scoring/MoonshotTraderAI.kt:565-612,624-636`; `v3/scoring/ShitCoinTraderAI.kt:824-885`; `v3/scoring/ManipulatedTraderAI.kt:215-252`.

MOONSHOT grants **+18** for `buyPressure >75`, has no equivalent local penalty for `bp>85/90` when the pump is exhausted; SHITCOIN explicitly penalizes >=85 and momentum >40/55/80; MANIPULATED partially subtracts overextension. Treat this as **suspected economic inversion of an extreme pressure band** to test, not proof `>=` should simply become `<=`. **Repair:** Grade pressure × acceleration × position in run × time since first buyer × liquidity/holder flow by lane. Reward *rising, persistent* demand, penalize demonstrated saturation, and calibrate with net fill-to-exit labels.

### P1-05 [C/H] CASHGEN and TREASURY score existing momentum and extreme pressure positively even while targeting modest scalable scalps
**Source:** `v3/scoring/CashGenerationAI.kt:550-583,715-766,895-914`; `engine/TreasuryBrain.kt:43-119`.

CASHGEN awards +30 for bp>=70 and +20 for momentum>=10. The source itself records those high-score bands as anti-predictive and adds an anti-FOMO rejection **only in AGGRESSIVE WR-recovery mode** (`CashGenerationAI:747-753`); outside that band the same raw score remains rewarded. TREASURY's native scalp score also adds on higher momentum/pressure but checks exhaustion separately. **Repair:** Split stable-volume scalp suitability from urgent momentum entry; calibrate high-pressure overheat across every recovery mode, net-of-cost by strategy, not via a mode-dependent discontinuity. Never lower safety solely to create trades.

### P1-06 [C/H] Scalp economic gate demands already-realized movement instead of the selected strategy's future move
**Source:** `engine/truth/NetEdge8030.kt:385-439`.

`CASHGEN/TREASURY` require the **realized past-hour move** >= `3 * roundTripCost` (or defer/terminal on unknown), and comments explicitly reject substitution of supported setup's predicted move. With 4.42% modeled round-trip, that is **13.26% already moved** before buying. This can select late momentum instead of an emerging cashflow scalp and suppress a valid range-low bounce. It might be intentional for a particular momentum style; it is not a sound universal test for every member of those lanes' diverse shared menus (`LanePlaybook7907:664-667`). **Repair:** Classify `range scalp`, `reclaim scalp`, `momentum scalp` separately: realized movement proves *tradability*, forward executable move proves *entry value*. Use measured turnover/depth and actual quote economics with strategy-specific exits.

### P1-07 [C/H] Momentum-lane `FALLING_HOUR_UNPROVEN` can suppress rebound strategies with opposite direction clocks
**Source:** `engine/truth/StrategyRunner8153.kt:29-40,97-129`; `engine/cortex/LanePlaybook7907.kt:278-280,309-318,437`.

For SHITCOIN/MOONSHOT/EXPRESS/PROJECT_SNIPER, a falling 1h outside launch window yields gross=0/defer unless the exact strategy already has 30+ positive labels. That is reasonable for straight-line momentum entry, but `RECLAIM_AFTER_WEAKNESS`, `POST_EVENT_RECLAIM` and `MOMENTUM_PULLBACK_5M` may deliberately act before hour momentum turns. **Repair:** Gate on the actual strategy's sign/timing contract; falling-hour cannot be a universal adverse sign for recovery strategies. Test a negative-hour/positive-reclaim event against a sustained downtrend with no buyers.

### P1-08 [C/H] PROJECT_SNIPER price-change basis uses first-in-list, not earliest time
**Source:** `v3/scoring/ProjectSniperAI.kt:381-393`; `engine/cortex/LanePlaybook7907.kt:188-235`.

`ts.history.firstOrNull()` is treated as launch first-price without sorting or proving birth/source; `LanePlaybook7907` documents actual feed merges arriving **out of insertion order** (`:205-210`). With out-of-order/mixed-basis candles, the sniper may label a fresh coin `DUMPING` or `ALREADY_PUMPED` incorrectly. **Repair:** Use canonical birth/first tradable price with source/decimals validation and timestamp ordering, and fail to an explicit price-basis unknown rather than a 0% surrogate. Unit test shuffled same-ts/heterogeneous-source candles and real pump curves.

### P1-09 [C/H] Native “ready” may be a score-band condition without any lane-owned setup or entry-time signal
**Source:** `engine/CyclicTradeEngine.kt:188-207`; `engine/truth/CyclicEntryPolicy8527.kt:22-46`; `engine/SpecialistBrainBridge7542.kt:437-440`; `engine/cortex/LanePlaybook7907.kt:668-671`.

CYCLIC's `evaluateCandidate7542` approves on generic V3 score plus sellability and blacklists; its clock/strategy need not match one of CYCLIC's ten playbook families. CORE can become ensemble-ready based on top-two eligible specialists or V3 fallback with buyer pressure>=50. That explains why “qualified” is not synonymous with *ready to buy at a profitable moment*. **Repair:** Select a named lane strategy/event before READY; preserve inheritance/ensemble voting as a voter, not an invented entry signal. Verify candidate ownership plus specific costed setup on each admit.

### P1-10 [C/E] Default native opinions and generic positive scores inflate selection despite absent liquidity/age/proven setup
**Source:** `engine/SpecialistBrainBridge7542.kt:337-415`; `engine/cortex/LanePlaybook7907.kt:168-181,238-239,1490-1550`; `engine/truth/CandidateAdmission8136.kt:518-555`.

The classifier correctly distinguishes `DATA_INCOMPLETE` from `NO_MATCH`, but native scores still see legacy defaults; then the admission layer carries separate generic waits. The runtime shows `DATA_INCOMPLETE_DEFER` 730 despite `V3_SCORER_COMPONENTS` 1,623 and multiple lanes reporting READY. **Repair:** One typed *observed feature frame* from hunters through V3/Native/Playbook/FDG/Cortex; revision/age and `requiredByStrategy` fields; wake/reevaluate on true hydration once, not repeated transient native approvals. Never convert UNKNOWN to negative or a scored positive.

### P1-11 [E] Trading costs and executed quote economics may be materially miscalibrated for several lanes
**Source:** `engine/truth/NetEdge8030.kt:300-379,385-439`; `engine/truth/StrategyRunner8153.kt:50-95`; runtime log `Net edge (§8030)`.

Runtime `modelVsMarkGap8463` reports BLUECHIP 4.42/10.89, QUALITY 4.43/10.29, EXPRESS 4.42/14.53 (different measurement types may be mixed), while the MOONSHOT active quote estimates 6.15% round trip for ~0.031 SOL and fixed fee ~2.47% of ticket. **Repair:** Reconcile every model component to quote, landed buy, full/partial sell, rent recovery, Jito tips, platform fees and actual impact. Preserve negative/stale quotes as unknown. Require expected *remaining* move after all measured costs, with per-strategy reward/risk. Do not assume “model vs mark” represents an exact realized fee gap until the source clocks/bases are joined.

### P1-12 [E] Hot runner opportunity capture and post-entry profit are two distinct broken handoffs
**Source:** `engine/cortex/LanePlaybook7907.kt:266-492`; `engine/truth/CandidateAdmission8136.kt:475-817`; `engine/truth/LiveEdgeGate7877.kt:479-541`; `engine/truth/ForwardReturnLabeler7731.kt:2833-2850`.

The 8529 runner register reports roughly **33 detected 4x+ opportunities, one held/zero live at the important moment**, and a stricter trace yields **52 evidence-supported wrong refusals** among 80 raw candidates. Conversely, already-bought trades are frequently stopped near local tops; positions that later made big maxima were not all executable at the earlier decision. **Repair:** Join signal/quote/route feasibility and at-entry future at 15/30/60s/5m with actual stop/giveback decision, then stratify missed runner by `NEVER_DETECTED`, `DETECTED_LATE`, `WRONGLY_REFUSED`, `ELECTED_OUT`, `NO_BUY_ATTEMPT`, `FILLED_LATE`, `SOLD_PREMATURELY`. Replay realistic executable exit ladders and quantify *bankable* SOL, not hindsight chart peaks.

### P1-13 [C/H] Some observed-only/unknown setups become firing predicates via default-favour helpers
**Source:** `engine/cortex/LanePlaybook7907.kt:255-260,419-472`.

`HOLDER_SURGE`, `LIVESTREAM_LAUNCH`, `COPYCAT_BETA` use `geOrUnknown(bp,55)`, so an absent buy-pressure measurement does not prevent a match when the other input fires. This may be fine for *observation/learning* but not executable strategy confidence. **Repair:** Distinguish `fired on partial evidence` from `actionable`: required inputs are strategy-specific, authenticated and time-stamped; a partial setup may be watched/graded without qualifying for an executable entry. Negative volume/pressure confirmation must not be converted to missing and then favourable.

### P1-14 [C] Moonshot RugCheck handling/comments disagree about the confirmed dangerous 2..14 band
**Source:** `v3/scoring/MoonshotTraderAI.kt:420-424,516-547,564`; `engine/SpecialistBrainBridge7542.kt:366,392`.

The documented logic at `:545-546` says scores **2..14** are hard-refused, but the actual `rugcheckRefusal8418` only refuses `0`, and scores `2..14` merely subtract `(15-score)` points. A separate downstream safety authority may still refuse the token; this native rule itself does not implement the comment. **Repair:** Establish a single, normalized RugCheck contract (`CONFIRMED_RUG`, `DANGER`, `PENDING`, `SAFE`), with explicit safety vs learnable risk ownership. Verify which downstream gate currently handles confirmed danger. Never disable a real confirmed rug/sellability veto to increase throughput.

### P1-15 [E] Partially delivered forward outcomes leave setups on a cold-start rank indefinitely
**Source:** `engine/truth/StrategyRank8153.kt:56-89,110-143`; `engine/cortex/LanePlaybook7907.kt:1492-1525`; `engine/truth/ForwardReturnLabeler7731.kt`.

Ranker requires 12 priced labels for VALID and 30+ for SUPPORTED. This ordering is **correct**, but the 28.5% mark coverage means many actual candidate/setup/entry-time combinations cannot reach sample maturity; unproven generic/default strategies then compete against a rising threshold. **Repair:** Fix labels, maintain separate cold-start quantitative evidence with uncertainty (15s/30s/60s forward), and allow appropriately small *evidence-gathering* probes only through existing economic/safety authority; no synthetic profitability and no automatic 30-sample bypass.

### P1-16 [E] Crypto-alternative signals stop at a different FDG than the 12 Solana specialists
**Source:** inspect `engine/truth/LiveEdgeGate7877.kt` plus crypto desk's `CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244`, `CRYPTO_DESK_SIGNAL_REPRICE_FAILED_7391` and `CROSS_ASSET_HISTORY_REPAIR_6660` emitters; log `Cross-asset Cortex (§7931)` and mode execution tables.

**Evidence:** `CRYPTO_ALT candidate=245/submit=245/FDG allow=0/block=245`; 88 `CRYPTO_DESK_SIGNAL_REPRICE_FAILED`; crypto alt forward n12 -6.1% and lost=38, while its full trading summary mixes 180 history records, most skips/quarantine. **Repair:** Trace its own signal generation, market-data freshness, exact crypto FDG reject reasons, size/route mapping and immutable 1/5/60-minute labels by asset, not by reusing a Solana token curve logic. This is a distinct confirmed starvation symptom, **not a proven crypto-score inversion**. FOREX, PERPS and other markets in this snapshot have zero live candidate traffic; a numerical sign audit cannot be proven for inactive strategies from this file.

## P2: Observability and testability gaps that hide incorrect strategy decisions

### P2-01 [C] The repository audit itself records missing joined lineage and profit opportunity accounting
**Source:** `docs/audits/8528-pipeline-observability.md:7-16,18-28`.

The repository explicitly acknowledges absent one-row native/V3/Cortex signed score attribution, setup-specific first-executable timestamp/price, old-to-new learner revision receipts, comparable exit latency clocks and bankable profit opportunity calculations. A long pipeline report is therefore insufficient to pinpoint *which exact signed adjustment or gate* inverted one observed candidate. **Repair:** Put these at the decision episode level with source path/function, immutable evidence hashes and transaction/label receipts. Compare cohorts only after matching setup, cap, venue, age, spread and quote feasibility.

### P2-02 [C/H] Repeated V3 eligibility proposal tracking uses a globally mutable, unsynchronized map
**Source:** `v3/eligibility/EligibilityGate.kt:53-82,87-129`; invocation `engine/FinalDecisionGate.kt:1950-1961`.

`DGradeLooperTracker.recentProposals` is a `mutableMapOf` with `MutableList`, and `recordProposal`, `shouldBlockDGradeLooper` and `cleanOldRecords` read/mutate it without a lock or serialized ownership. If FDG work is concurrently scheduled, a race can corrupt counts or trigger exceptions. The runtime logged **four `DGradeLooperTracker`-associated exceptions**, but their exact causes cannot be attributed to a race from the abbreviated exception text. **Repair:** Choose a thread-safe bounded per-mint ring or confine all mutations to one execution context; preserve 2-minute/10-proposal semantics, add concurrency test and record complete exception stack.

### P2-03 [E] Native/FDG and label counters mix different units and epochs
**Source:** `engine/SpecialistBrainBridge7542.kt:38-45,400-415`; `engine/SpecialistOwnership7951.kt:224-255`; `engine/cortex/Cortex7885.kt`; `engine/truth/ForwardReturnLabeler7731.kt:75-84`.

Native counters count calls **before** post-review changes, FDG rows include repeated evaluations, the lane scoreboard uses canonical close windows, and Cortex has current 8527-epoch vs historical decayed books. Example: the brief says MOONSHOT forward n212 mean -65.9%, while another `lanes60` table reports n212 mean -9.0% with a different return/basis definition. **Repair:** Every metric must publish `(unit, cohort, source epoch, horizon, entry basis, cost model, dedup key)`; summaries with the same `n` must explain why means differ. Do not silently compare incompatible means or treat a price-path peak as fill net PnL.

## All 12 lanes: required directed audit and repair ownership

| Lane | Runtime 8529 (5-min forward / live execution) | Source-defined score/entry mismatch to repair or test | Confidence |
|---|---|---|---|
| **SHITCOIN** | n185 -10.2%; nativeAllow 1,123; exec 1 | Native pressure/overheat shape differs from playbook `NEW_HIGH_PUSH`, `BREAKOUT_RUNNER`, momentum `FALLING_HOUR` and 15-second first dip; actual chosen setup must control exact buy clock. `ShitCoinTraderAI.kt:824-885,1180-1194`, `LanePlaybook7907.kt:294-309,355-357,486-492`, P0-03/04. | C/H |
| **MOONSHOT** | n212 -65.9% in brief (alternate full cohort -9.0%); nativeAllow 2,157; exec 2; 5 live closes all losses | High pressure/top buy rewards, launch-window spent-move exemption, no phase named `IGNITION` in LAUNCH_CONTINUATION, false generic/native identity; `MoonshotTraderAI.kt:565-636`, `StrategyRunner8153.kt:66-70`, P1-02/04. | C/H |
| **EXPRESS** | n172 -12.3%; nativeAllow 317; ticket 1, exec 0; `CHASE_EXTENDED` frequent | Native blocks `chg5m>20` or momentum>30 (`ShitCoinExpress.kt:369-374`) while some playbook momentum setup predicates permit +8%/higher; ensure strategy-specific first-impulse clock and do not re-offer after chase. `ShitCoinExpress.kt:341-374`, P0-03/P1-01. | C/E |
| **PROJECT_SNIPER** | n38 -12.3%; 0 qualified/0 exec; 1,261 birth metadata waits | Birth before phase, first-in-list price-change basis, pre-ignition vs ignition handoff; `ProjectSniperAI.kt:241-300,381-393`, P0-09/P1-08. | C/E |
| **MANIPULATED** | n153 -9.4%; nativeAllow 30; ticket 1, exec 0 | Native rewarding 80% buy pressure and +15% momentum even after overheat penalty can favor coordinated blowoff; need time since coordinated buyer onset and exitability. `ManipulatedTraderAI.kt:199-255`, P1-04. | C/H |
| **DIP_HUNTER** | n65 -14.6%; 735 history unobserved; 0 qualified | No high -> 0% dip; hunter still invites unknown history; native bounce confirmation conflicts with playbook `TREND_DIP_8383` (no bounce test). `DipHunterAI.kt:329-392`, `LanePlaybook7907.kt:579-581`, P0-10. | C/E |
| **QUALITY** | n162 -8.7%; 1,068 pre-review nativeAllows; FDG block 85; 0 exec | Native score can reach floor on cap/liq/age plus unobserved 50 pressure; generic flow/reclaim waits block; `QualityTraderAI.kt:324-420`, `CandidateAdmission8136.kt:523-547`, P0-05/08. | C/E |
| **BLUECHIP** | n155 -7.7%; 234 pre-review nativeAllows; FDG 0; 0 exec | Up-momentum/pressure points ignore entry position except other gates; no owner ticket; audit cost-scaled trend pullback vs high buy. `BlueChipTraderAI.kt:706-789`, `SpecialistOwnership7951.kt:593-710`. | C/H |
| **CORE** | n223 -7.7%; nativeAllow 436; FDG reached1; 0 exec | CORE `ensemble||fallback` uses score/voter consensus rather than a named actionable CORE event; generic shared setup library cannot replace event, `SpecialistBrainBridge7542.kt:437-440`, P1-09. | C/E |
| **CYCLIC** | n83 -11.6%; nativeAllow 401; FDG 0; 0 exec | Native `V3 >= floor` not equivalent to time-correct mean reversion/compound strategy; missing executed setup/owner handoff. `CyclicTradeEngine.kt:188-207`, `LanePlaybook7907.kt:668-671`, P1-09. | C/E |
| **CASHGEN** | n134 -5.6%, coverage 63%; pre-review nativeAllow 753, exec 0; 1 prior live loss | Strong positive existing pressure/momentum while explicit anti-FOMO veto only AGGRESSIVE recovery; scalp realized-hour 3×cost; choose range-rebound vs momentum style. `CashGenerationAI.kt:560-583,715-753`, `NetEdge8030.kt:385-439`, P1-05/06. | C/H |
| **TREASURY** | n204 +1.3% coverage 64%; pre-review nativeAllow31; FDG reached6; 0 buys | Positive *observational* cohort is not a verified executable edge; TreasuryBrain momentum pressure scalp conflicts with 3× realized-hour move; lane-specific opportunity attribution. `TreasuryBrain.kt:43-119`, `NetEdge8030.kt:400-439`, P1-06. | C/E |

## Complete setup-library coverage and timing classification

The 89 **declared** `Setup("...")` predicates are in `engine/cortex/LanePlaybook7907.kt:265-677`; lane menus/membership at `:626-712`. The four ensemble lanes inherit the additional shared library at `:683-698`, so testing just their ten native menu entries misses strategies they may actually choose. Each setup must be tested *in each lane that can select it*; a passing predicate is not a proven positive-profit strategy. Below is the **full declared inventory**, grouped by source block; these are all in the 8530 source and are not claims that all 89 are faulty.

**Original structure/phase/tape, `:266-331` (28):** `HIGHER_LOW_PULLBACK`(266), `TREND_PULLBACK`(269), `PLAN_PULLBACK_RECLAIM`(272), `PLAN_BASE_BREAKOUT`(273), `PLAN_SWEEP_RECLAIM`(274), `BREAKOUT_HOLD`(275), `RECLAIM_AFTER_WEAKNESS`(278), `RELATIVE_STRENGTH`(281), `FLAG_CONTINUATION`(284), `LAUNCH_CONTINUATION`(287), `PRE_IGNITION_BASE`(291), `FIRST_PULLBACK`(294), `VOLUME_CONTINUATION`(297), `HIGHER_LOW_CONTINUATION`(301), `MICRO_FLAG`(304), `BREAKOUT_RUNNER`(305), `RS_LEADER`(308), `POST_EVENT_RECLAIM`(309), `VERIFIED_LAUNCH`(310), `LOW_RUNUP_BASE`(311), `SWEEP_RECLAIM_FLOW`(312), `CAPITULATION_HIGHER_LOW`(313), `SUPPORT_FLIP`(316), `DISTRIBUTION_RECLAIM`(317), `RANGE_LOW_BOUNCE`(320), `CROWD_FORMING`(324), `LAUNCH_LADDER_PROVEN`(329), `HL_RECLAIM`(331).

**Launch/follow-through/social, `:341-387` (16):** `EARLY_BUYER_EXPANSION_8170`(341), `FAST_CROWD`(346), `BROAD_DISTRIBUTION`(349), `NET_INFLOW_SURGE`(352), `FIRST_DIP_BOUGHT`(355), `DEV_HOLDS_CROWD_BUYS`(359), `CLEAN_DEV_LAUNCH`(362), `ACCELERATING_TAPE`(366), `GRADUATION_RUN`(369), `NO_BUNDLE_CLEAN`(372), `SOCIAL_LAUNCH`(376), `BOOSTED_LAUNCH`(379), `CTO_REVIVAL`(380), `NARRATIVE_WAVE`(381), `INSIDER_ACCUMULATION`(384), `POST_MIGRATION_HOLD`(387).

**Opportunity/v3/price-pattern, `:412-477` (28):** `OPP_EARLY_IGNITION`(412), `OPP_BREAKOUT_EXPANSION`(413), `OPP_RS_LEADER`(414), `OPP_DIP_RECOVERY`(415), `OPP_LIQUIDITY_EXPANSION`(416), `OPP_CONTINUATION`(417), `VOLUME_IGNITION`(419), `HOLDER_EXPANSION`(422), `TX_VELOCITY_BREAK`(423), `SECOND_LEG`(424), `LOW_VOL_COIL`(427), `DEEP_LIQ_TREND`(430), `MEAN_REVERSION_OVERSOLD`(431), `LP_LOCKED_BASE`(434), `MOMENTUM_PULLBACK_5M`(437), `RS_IN_WEAK_MARKET`(438), `RANGE_SUPPORT_SWING`(444), `SECOND_WAVE`(450), `SPECIALIST_EDGE`(455), `FRESH_LAUNCH_MOMENTUM`(456), `MICRO_CAP_IGNITION`(460), `HOLDER_SURGE`(463), `TURNOVER_SPIKE`(464), `GRADUATION_APPROACH`(467), `LIVESTREAM_LAUNCH`(470), `COPYCAT_BETA`(471), `COLOUR_SEQUENCE`(472), `MICRO_PULLBACK_TREND`(477).

**Shape expansion, `:486-492` (7):** `BUYERS_DOMINATING`(486), `BASE_BREAKOUT_FLOW`(487), `IGNITION_5M`(488), `FIRST_PULLBACK_AFTER_RUN`(489), `DIP_IN_UPTREND`(490), `QUIET_ACCUMULATION`(491), `NEW_HIGH_PUSH`(492).

**8383 evidence-built shapes, `:556-580` (9):** `TREND_PULLBACK_8383`(556), `SWEET_SPOT_8383`(559), `BAND_MOMENTUM_8383`(562), `RUNUP_HOLD_8383`(565), `PEAK_RECLAIM_8383`(568), `YOUNG_LIQUID_8383`(571), `STRONG_TREND_8383`(574), `DEEP_RATIO_8383`(577), `TREND_DIP_8383`(580).

**Expert extension `:676` (1):** `EXPERT_ENTRY`(676). **Total 89** (28 + 16 + 28 + 7 + 9 + 1 = 89). Other identifiers are native strategies, tactics, style plans or derived labels rather than `Setup` declarations; they require separate strategy-identity checks, not fabricated predicates.

The **highest-priority setup-by-setup event-clock tests** are: (a) `PRE_IGNITION_BASE`, `VERIFIED_LAUNCH`, `LOW_RUNUP_BASE`, `EARLY_BUYER_EXPANSION_8170` — buy **before** first significant impulse, require real birth/flow and low chase; (b) `LAUNCH_CONTINUATION`, `IGNITION_5M`, `ACCELERATING_TAPE`, `VOLUME_IGNITION`, `NET_INFLOW_SURGE`, `NEW_HIGH_PUSH`, `BREAKOUT_RUNNER`, `RS_LEADER`, `STRONG_TREND_8383` — buy only with **remaining** post-fill impulse and fresh confirmation; (c) `FIRST_DIP_BOUGHT`, `FIRST_PULLBACK`, `MOMENTUM_PULLBACK_5M`, `PEAK_RECLAIM_8383`, `RECLAIM_AFTER_WEAKNESS`, `TREND_DIP_8383`, `MEAN_REVERSION_OVERSOLD`, `RANGE_LOW_BOUNCE` — **rebound/reclaim** timing, not a generic positive 5m bar; (d) quality-only conditions `SWEET_SPOT_8383`, `YOUNG_LIQUID_8383`, `DEEP_RATIO_8383`, `HOLDER_SURGE`, `SPECIALIST_EDGE` cannot independently define an execution timestamp.

**Important guardrail:** `LanePlaybook7907.kt:537-552` already exempts the early-cap lanes from the generic $10k envelope floor when their own mandate starts lower. `ShitCoinTraderAI.kt:65-66` starts at $500, MOONSHOT bootstrap at $1,500 (`MoonshotTraderAI.kt:69-83`), and other lanes have different legitimate floors. Do **not** reinstate a blanket $10k market-cap cut, do not force BLUECHIP or TREASURY down to $2k, and do not confuse fluid scouting floor with executable depth/sellability. For the applicable early lanes, include $2k-$10k discovery and complete setup/forward grading.

## Required verification contract before claiming repair is complete

1. **Full decision lineage:** Every inspected candidate produces a single immutable ledger row: mint/asset, source and source-time, lane, strategy ID, fired predicate IDs, every raw input plus provenance, native raw score and scale, V3 raw components, Cortex forecast/probability, signed adjustments, effective floors, first binding veto, owner/handoff, quote, final ticket, fill or terminal reason. No summary-only "qualified" count substitutes for the actual row.
2. **Entry-time labels:** Freeze discovery price, setup-trigger price, **first route-executable quote**, decision price, buy fill and stop/exit basis. Grade 15s/30s/60s/300s and 1h from the correct entry clock. Keep missed-data and impossible-price states distinct. Score/net-return direction must match `+` for profitable net after fees for *every* model; a high risk score is not a high safety score.
3. **89-setup × 12-lane feasible matrix:** Enumerate *actual* lane memberships after extras/shared-library/retirement. For every reachable pair construct: bullish fire, bearish nonfire, unknown-data, stale-data, too-early, valid entry window, chased/expired window, adverse fee/depth quote, supported/losing learning record, native/FDG disagreement, simultaneous competing setup, sealed-exit attribution. Explicitly record unreachable or retired pairs; do not claim 1,068 independent setups simply by multiplying.
4. **Matched paper/shadow/live counterfactual:** Match by token, decision time, entry feasibility, setup, cap/liquidity band, age, venue, regime, cost and time horizon; measure executed/admitted/refused cohorts using *net executable* return. A token that eventually rallies is **not automatically a wrongful refusal**.
5. **Reconciliation:** Across restarts and provider outages, every selected/fired strategy with a quoted entry eventually has a priced result or one terminal unavailable result and an auditable learner parameter revision. Separately reconcile live positions, paper/shadow closes and partial-exit allocations. Native READY, model `ALIVE` or a counter increment is not proof of actual learning.
6. **Trade quality:** Demonstrate out-of-sample improvement in fill-to-exit expectancy, PF, per-lane setup edge and runner capture *after* the change. Compare with the 8529 baseline and a compatible previously profitable build only after restoring cost, labels, market conditions and sample definitions. A green APK alone is not evidence of profitable trading.
7. **Protection invariant:** Never bypass confirmed rugs, false sellability, hard exposure, occupied actual position, quote non-executability or canonical fill/ledger consistency to rescue a setup. Strategy-specific timing can supersede an unrelated **soft** gate only where source evidence supports that exact strategy and the final executable economics still clear.

## Items that cannot honestly be signed off from this snapshot

- **Not proven:** that *all* 89 setup predicates or V3 and Cortex scoring components have their sign reversed. The ranker `StrategyRank8153.kt:110-118` sorts SUPPORTED/VALID first and descending conservative net correctly; `CortexScoreboard7885.kt:44-58` gives negative executable net REFUSE, positive STRONG. `MoonshotTraderAI.kt:628-636` rewards pre-ignition and penalizes post-pump fade in the correct sign. These must be preserved unless paired evidence falsifies them.
- **Not proven:** that a zero owner-selected count means exactly that many profitable trades disappeared. Pre-review native counters, multiple repeat evaluations, candidate scopes, failed data and already-held coins distort raw subtraction. P0-07/08 correct the measurement first.
- **Not proven:** a live edge in inactive forex/perps/metals/stocks strategies. Their zero traffic means no forward execution test in 8529, while some accounting rows are quarantined/historical. A repo-wide code inventory beyond the 89 Solana playbook predicates is still necessary to certify every asset-specific strategy implementation. This report identifies source issues and execution paths; it does **not** certify all 3,000+ Kotlin files or all combinations as formally audited/tested.
- **Not proven:** that restoring the historically reported 70% win rate is guaranteed. A correct, label-complete, strategy-accurate system can be validated for improvement; profitable outcomes are contingent on market conditions, liquidity, costs and out-of-sample evidence.

## Required order of repair

**1. Price and timestamp truth (P0-01/02/05/09/10) → 2. One real native/setup identity plus owner ledger (P0-06/07/08) → 3. Strategy-owned entry clocks and remaining-upside economics (P0-03/04, P1-01..09) → 4. Per-lane score/pressure polarity and Cortex walk-forward calibration (P0-11, P1-10..15) → 5. Exit latency and capture, cross-asset extension (P0-12, P1-12/16) → 6. Replay and executable profit/learning acceptance.**

Each stage must prove upstream AND downstream consumers receive the corrected value, remove superseded duplicate policy branches, and add regression tests at the line of original failure. No unconditional score flip, no blanket floor, no fabricated labels, no mere telemetry patch passed off as trading improvement.
