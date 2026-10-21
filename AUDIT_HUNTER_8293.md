# AUDIT_HUNTER_8293 — token hunters (V5.0.8293)

Scope: LaneHunter7297 (bands and mandates), MarketSweep7297 (sweep and opportunity intelligence 7777), ResidentHunterWorker7807 and
`SolanaMarketScanner.emitResidentHunt7807` (hunter delivery), SpecialistCandidateBooks7803, RunnerGrab7967, RunnerPlay8018 re-entry,
TailHunter7996. Evidence: `/tmp/claude-0/audit8286.md` (live 5.0.8286, 19 min, 4/4 losses, every entry at ROUTE_MIN and UNPROVEN).
Base: main 5.0.8286 @7c147e519. Tests: `lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate8293HunterAuditTest.kt`.

Limits on the evidence: I had only the 19-minute summary, not the per-row sweep dump or the forward-label rows. So the mandate
corrections below follow two rules. First, a hunter must pick what the lane's own native scorer can buy, which I checked against the
native code. Second, a hunter must not rank up the shape that the lane's realised record says loses: AGE_MIN<3, LIQ<3k and MCAP<10k
read strongly negative for every lane. The forward-label "best" cells (MOONSHOT MC_LT10K +165%, PROJECT_SNIPER MC_LT10K +107%) are
all flagged `OUTLIER_SUSPECT_8185`. Lanes60 are all negative and the realised closes are all losses, so I treated those cells as
outlier-driven means, not as evidence that those lanes win.

## Findings

### F1 — The sweep's ages were as old as the provider answer, and merge kept the stalest one (CRITICAL for every age mandate)
- `engine/market/MarketSweep7297.kt` `parseJupiter` sets `ageHours` from `now - firstPool.createdAt` at fetch time.
  `carryOrStore8124` / the `goodRows8124` refresh window then re-serve those rows for up to 3 min (JUP lists) or 20 min (carry on
  failure) without advancing their age. `merge()` takes the minimum age across providers, which picks the most stale (youngest)
  reading.
- Impact: a "0.2-0.5-minute-old" PROJECT_SNIPER pick (8286) could already be minutes past the sniper's 180 s window. The same
  happens to MANIPULATED (8 to 12 min), EXPRESS, and the MOONSHOT youth prior. `sniperPastWindowAtPublish8201` only adds the sweep's
  latency, not the row's. This is part of why the sniper's book stayed empty while the hunter "selected" first-minute coins.
- Fix: `MarketSweep7297.agedForward8293` advances every dated row to now before merge (`MarketSweep7297.kt` `sweep()`: `merge(agedForward8293(...))`).

### F2 — MOONSHOT hunted below its own native floors and ranked hardest into the realised losing shape
- Band: `LaneHunter7297` MOONSHOT profile used `MIN_MARKET_CAP_BOOTSTRAP_USD_7719` ($1.5k) permanently. Native
  `MoonshotTraderAI.minMarketCapUsdFluid7719()` walks to $10k as learning matures, and the native refuses below it
  (`mcap_too_low_*`).
- Mandate: liquidity was at least $1.5k. The native floor is `minLiquidityUsdNow8180()` ($2k bootstrap, $15k mature), so rows under
  it were refused natively as `liq_too_low`.
- Rank: `earlyCap` gave sub-$10k 6.0 and `youth` gave ≤15 min (including the first 3 min) 3.0. That is exactly the
  AGE_MIN<3 / MCAP<10k shape that the realised trade-shape rules read strongly negative. The 7967 note itself puts the running cell
  at $10k-$100k.
- On "mandateRejected 107/176": the rejection share alone is not the defect. The h1>0 / ≤120% asymmetry rule matches the lane. The
  defect was which rows passed: they came from below the native floors, ranked toward the losing shape.
- Fix (`HunterMandates8293`): the band floor is the native fluid floor (`baseMin8293`, applied in `hunt()` and `fluidBandFor`). The
  liquidity floor is the native one (`moonshotFits8293`). The priors now peak at $10k-$100k and 3-15 minutes, and the first 3
  minutes and sub-$10k score lower (`moonshotCapPrior8293`, `moonshotYouthPrior8293`).

### F3 — The EXPRESS mandate was stricter than native, which explains 7/118 passed
- `HunterMandates8031.expressFits8031` required a known age of at least 5 min and a 5m move of 0.5-12%. Native
  `ShitCoinExpress.evaluate` has no age floor (age only scores), no 5m minimum, and its chase line is 5m > 20% or 1h momentum > 30%.
- Fix: `expressFits8293`: 1h from 1% (bootstrap minimum) to 30%, and 5m ≤ 20% when known. Ranking adds the native 5m price-score
  shape (2-8% best) through `expressM5Rank8293`.

### F4 — MANIPULATED used fixed gates instead of the native fluid ones (2/86)
- The hunter used a fixed 8 min and $1.5k. Native `getFluidMaxAge()` is 12→8 min and `getFluidMinLiquidity()` is $1.5k→$3k.
- Fix: `manipulatedFits8293` reads both. Structural note: the sweep (45 s cadence, list refreshes of 60 s or more) rarely surfaces
  coins under 8 minutes. The executed-trade tape (F9) is the realistic source for young lanes.

### F5 — PROJECT_SNIPER selects first-minute coins but the sniper is DEAD
- The cause is F1 (stale ages) plus latency. A sweep pick goes through publish, intake, rugcheck, watchlist and then a cycle, which
  rarely completes inside 180 s. 8201 correctly drops past-window picks at publish, so the hunter feeds the sniper book almost
  nothing.
- Fix: F1 makes the age honest. F9 adds a direct path: a tape-detected run whose known launch age (`PumpCurveKeys7269.createdAtMs7280`)
  is still inside the window minus 30 s is also claimed for PROJECT_SNIPER and published to its book (`RunTape8293.lanesFor8293`).
  The sniper's own pool and scope (`candidateN=0`) belong to the scanner/intake and admission owners.

### F6 — The lane hunters spent picks on rows their own live gate refuses (learned losing shapes)
- `TradeShapeLearner7883` learns per-lane losing bins (n≥30, mean+SE<-2%, worse than the lane, runner lanes without a tail), and
  `LiveEdgeGate7877` refuses on them. The hunters never read them, so they kept re-picking rows bound for refusal.
- Fix: `TradeShapeLearner7883.provenLosingBin8293` is a read-only form of the same rule. `HunterShape8293.orderLive8293` checks
  AGE_MIN, LIQUIDITY_USD, MCAP_USD and MCAP_LIQ from the sweep row and moves those rows behind every other row of the lane. They are
  demoted, not removed, so paper and learning still see them. The `LANE_HUNT_SHAPE_DEMOTED_8293_<lane>` counter records it.

### F7 — Opportunity intelligence leaders never reached evaluation first (JONATHAN, Alpenglow, PHP)
- Trace: `MarketSweep7297.rebuildOpportunitySignals7777` ranks every row. That rank was used only as a multiplier from 0.70 to 1.55
  (`opportunityLaneMultiplier7777`, `adaptiveTimingMultiplier7812`) inside lanes whose band and mandate the row already passed.
- A BREAKOUT_EXPANSION leader with h1 above 120% fails MOONSHOT, above 30% fails EXPRESS, and above $300k fails EXPRESS and
  MANIPULATED. So it can only be picked by broad lanes (SHITCOIN, CORE). Even then, once on the watchlist it waited for the 128-mint
  rotating priority window (`BotService` botLoop `priorityWindowInput6489`) and the cycle slice like any cold-tail token. Nothing
  used the ranking to choose what is evaluated first.
- Fix (ordering only):
  - A leader (rank ≤10, score ≥80, constructive setup) that passes a lane's band and mandate goes to the front of that lane's picks
    (`HunterShape8293.order8293`).
  - `HunterWindow8293` puts up to 4 watched leaders inside the priority window and the cycle slice, like hand-off targets, re-visited
    at most every 15 s. This is a token-neutral swap of `HandoffPriority8190.windowTargets8190` / `dueTargets8190` in BotService
    (botLoop budget unchanged; validated by `oversized_method_budget_7809.py`).
  - Each leader's route is recorded in `OpportunityRoute8293.line8293()`, shown on `LaneHunter7297.auditLine8169()`: which lanes fit,
    which refused by MANDATE, which picked it, plus cap, liq, age and h1. Counters are `OPPORTUNITY_TOP_ROUTED_8293`,
    `OPPORTUNITY_TOP_NO_LANE_FIT_8293` and `OPPORTUNITY_TOP_CYCLE_SLOT_8293`.
  - Whether a leader is then bought is decided downstream: FDG `LIVE_PROVEN_SETUP_ONLY_8059`, setup records, economics. Those belong
    to the admission and predictive owners. The new route line shows whether a leader died at discovery or later.

### F8 — Delivery churn (requeue cooldown 579, alreadyWatched 620, multi-lane 606, picks on cooldown)
- `SolanaMarketScanner.emitResidentHunt7807` checked the once-per-hunt dedupe only on the unseen path. A seen mint picked by N lanes
  was requeued by the first lane, and the other N-1 lanes each counted `REQUEUE_COOLDOWN` (or `ALREADY_WATCHED`) in the same pass.
  Most of the 579 / 620 are this per-lane duplication.
- The requeue carried only the first lane's affinity. A mint the intake bounced (requeued but still not watched) was requeued again
  every 10 min forever.
- `freshFirst8170` (8170) put any never-seen mint ahead of watched higher-ranked mints at any depth (rank 200 over rank 4), so novelty
  replaced rank for 9 of 12 picks.
- Fix:
  - One hand-off per mint per hunt, before the seen branch, carrying every lane that picked it (`HuntDelivery8293.lanesByMint8293`).
  - The requeue cooldown escalates to 10, 20, 40, then 60 min while the intake keeps bouncing it, and resets once the mint is
    watched (`HuntDelivery8293`, counter `MARKET_HUNT_REQUEUE_BOUNCED_8293`). The lane ranking puts escalated-cooldown rows last.
  - Delivery picks apply fresh-first only within the lane's best 64 ranks, and never let a losing-shape or cooling row jump a
    qualified one (`deliveryPicks8293`).
  - "alreadyWatched" picks are not waste. They refresh the lane's claim and book in `hunt()`, and now count once per mint.

### F9 — Runner hunters did not hunt: no detector for the start of a run
- `RunnerGrab7967.grab7967` and `formingNative8260` act only on coins already on the watchlist, with a structure read. RunnerPlay
  re-entry needs +100% over a decision price. TailHunter7996 is a 4-hour replay and lottery path whose tickets need tail-proven keys
  (30+ replays, 2+ runners). It is a measurement system, which fits runners=1 and tickets=8. None of them watched the executed-trade
  tape, which since 8285 is source-tagged and signature-deduplicated across PumpPortal and Helius.
- Fix: `engine/RunTape8293.kt` is the run tape hunter. It runs every 10 s on its own coroutine (`ResidentHunterWorker7807`) and checks
  each mint with a fresh executed print (`TradeTape8100.activeMints8293`) over its last 3 minutes of prints. It flags a run when all
  of these hold:
  - at least 10 prints, the newest within 20 s;
  - at least 6 buys, with at least 3 in the last minute;
  - at least 1 SOL bought;
  - buyers at least 60% of SOL volume and at least 55% of prints;
  - price +15% to +150% off the run's low;
  - price within 10% of the window high, and up at least 5% over the last minute.
- Hand-off: the coin is claimed for MOONSHOT and SHITCOIN (PROJECT_SNIPER too while inside the launch window), published to their
  books with the evidence string, and requeued to the ordinary intake when not watched. `RunnerGrab7967.formingNative8260` accepts a
  fresh detection as the observed forming read. Safety, Mayhem, a proven-losing runner cell, critical facts, fresh price, economics,
  FDG and sizing all still bind. It never hands off an open position, a HARD_BLOCK token or a Mayhem refusal.
- Learning: each detection is graded at 30 min on the same tape, and it stands down at n≥20 with mean+SE<0. The record is persisted
  in `run_tape_8293.txt`. When no print exists near the horizon the detection is counted `ungraded`; no outcome is invented. Status
  is on the snapshot (`SpikeCapture7943`: "Run tape (§8293)").

### F10 — RunnerGrab record30m=none: held admits were never graded
- `RunnerGrab7967.gradeDue` ran only inside `grab7967` after a proven-cell and standing check passed. With grabs=0, the 17 held-admit
  signals from `noteAdmit7970` sat ungraded, so the record (and its stand-down) could never form. When `markAt7962` had no bar, the
  signal was dropped silently.
- Fix: `noteAdmit7970` grades. `grab7967` grades at most every 30 s whatever it decides (`gradeSoon8293`). Grading falls back to a
  fresh mark, as RunnerPlay8018 does, and lost grades are counted (`gradesLost8293`).
- Why grabs=0 while proven cells exist: `grab7967` additionally needs firming or a non-broken 60 m read at admission time, and those
  coins' reads were breaking or seller-dominated. That is correct behaviour, and the summary carries no per-coin trace to say more.

### F11 — RunnerPlay re-entry runsConfirmed=1
- `RunnerPlay8018.onRun8018` returned silently when the confirmed coin had no TokenState. Coins the bot refused usually leave the
  watchlist.
- Fix: such a coin is requeued through the ordinary intake (`RunTape8293.requestWatch8293`, escalating cooldown), so the next mark
  can confirm it on a real TokenState. It is counted as `notWatched8293`.

## Changes to hunter selection since ~5.0.8150 (git log -p)
- 6f8d5bcdf (8165) and 4124e268d (8167): LaunchTape7921 first buyers and tape recovery. These are discovery data only, with no
  selection change.
- e06e029ff (8168): BLUECHIP no longer vetoes `*pump` mints. This is neutral to positive.
- 017528266 (8169): HunterAssetClass8169 excludes tokenised equities and pegs, which is correct, and adds a 10-min requeue cooldown
  where cooling rows rank last. That is correct, but the cooldown was per lane and non-escalating, so churn persisted (F8).
- adf79346e and 90775135c (8170): `freshFirst8170`. DEGRADED selection: after the top 3, novelty beat rank at any depth, so for
  MOONSHOT and SHITCOIN the picks tilted to newly listed, unqualified, often sub-$10k / sub-3-min coins (the realised losing shape).
  Repaired in F8 and F6.
- 1a1452511 (8191): provider failure clearing. Neutral.
- 0fa097035 (8202): past-window sniper picks are not residents. Correct, but the ages it read were stale (F1).
- 7ffc6ea8f (8246), 1d6f7cbcf (8260), 2816605fa (8270): the runner grab as admission evidence, the unlearned forming runner, and
  "missing structure cannot witness a forming runner". 8270 is correct, but together these left runner admission dependent on a
  structure read that young coins rarely have. F9 adds observed tape evidence instead of loosening 8270.
- 63ef39b26 (8254) and 2ffa4e902 (8280): runner funnel counters and the first-rung target. Exit side, with no hunter selection
  change.
- 28cad2124 (8283): sweep rows bump the feature revision. Neutral.
- Older but still live: the 8124 per-list refresh and carry (pre-8150) is the root of F1. Its effect grew once 8169/8201 began
  trusting row ages.

## Growth target (2x–5x daily)
- Wiring check (read-only; sizing belongs to the admission and sizing owners):
  - `AutoCompoundEngine.processWin` is still called on live closes (`Executor.kt` ~26250 and ~29569, and the CryptoAltTrader closes).
  - `getSizeMultiplier()` is read by the ShitCoin, BlueChip, CashGen, Manipulated and Sniper scorers.
  - `LiveSizingProfile` floors (0.06 SOL minimum, 12-25% wallet tiers) are in place.
- Why 8286 still sizes flat: every live entry was `ROUTE_MIN ~0.028 SOL UNPROVEN`. `LiveSizingTruth8136` promotes a Kelly-below-min
  size to the route minimum when the setup has no proven edge (`ROUTE_MIN_PROMOTED_KELLY_BELOW_MIN`). With 4/4 realised losses,
  that is the correct reaction. Compounding on those setups would be spending aggression on losers. The compounding path is wired;
  it is starved of proven live wins.
- How the hunters serve the target: growth needs trade count on edges that win on-chain. These fixes raise both without touching
  safety or sizing:
  - Picks the native scorers can actually buy (F2-F4).
  - Fewer picks in shapes the lane's realised record proves losing (F6).
  - Opportunity leaders evaluated first (F7).
  - No re-handling churn (F8).
  - A detector for runs as they start, which feeds the runner lanes with real flow evidence and its own 30-min record (F9-F11).
- When those produce realised wins, the existing proven-edge and Kelly paths and AutoCompound grow size. Nothing here uses paper
  results or invented data, or loosens a hard gate.
- Recommendation to the sizing owner: once a lane or setup clears the proven bar on realised closes, verify that ROUTE_MIN promotion
  releases and the size actually steps up (12-25% tiers). This audit could not observe that path live.

## Validation
- `bash /tmp/claude-0/val2.sh` prints only "validated". `pins.py` reports broken 0 after the three pins intentionally superseded
  (marked "superseded by V5.0.8293" in `Aate8190HandoffThroughputTest`, `Aate8270DirectiveTest` and `Aate7796SpecialistHuntersTest`, rewritten to hold against current
  source).
- New tests are in `Aate8293HunterAuditTest`, covering:
  - native mandates (EXPRESS, MOONSHOT, MANIPULATED);
  - MOONSHOT priors;
  - age-forward and merge;
  - shape demotion and delivery picks;
  - the learner read;
  - opportunity route and window selection;
  - requeue escalation and dedupe placement;
  - run tape detection (positive, stale, thin, seller-led, vertical and dumping cases), grading, stand-down, lanes and evidence TTL;
  - wiring and safety guards.
- The JVM tests were not run locally (no compiler). CI is the compiler.
