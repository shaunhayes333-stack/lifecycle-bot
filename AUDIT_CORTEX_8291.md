# AUDIT_CORTEX_8291 — Cortex audit end to end against the 5.0.8286 live evidence

Base: main 5.0.8286 @7c147e519. Branch wt-acortex-8290. Tag V5.0.8291.
Evidence: /tmp/claude-0/audit8286.md (a 19-minute live report: 4 of 4 live closes lost, playbook auth=1.00 on setups that never traded).
Tests: `src/test/kotlin/com/lifecyclebot/engine/Aate8291CortexAuditTest.kt`. Paths below are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.

No local compiler exists, so none of this has been compiled or run. CI is the only check. The status lines added below (coverage8291, unexecWhy8291, the `id` slope marker, local/hive, realisedCap8291) are how the next live report should confirm each fix.

## 1. Labels lost faster than graded (lost=701 / graded=710; GAP 657, BASIS 44)

**Finding.** A Cortex decision is graded only on the labeler's 5-minute slot (`Cortex7885.onLabel`, `horizonMin != 60 -> return`). When no mark arrives by 7:30 (5 min plus 150 s of grace), `ForwardReturnLabeler7731.resolveVanished7944` (truth/ForwardReturnLabeler7731.kt:1244) classifies the decision GAP and calls `Cortex7885.onLabelLost8187` (engine/cortex/Cortex7885.kt:715). A refused coin leaves the watchlist, so its marks come only from the off-watch batch. That batch prices 50 mints every 20 s (`OFFWATCH_FETCH_GAP_MS_7737`, :106) and re-asks a mint after 90 s (`OFFWATCH_RETRY_MS_7809`, :1328), so it can serve about 150 mints a minute. Each unpriced observation needs about 4 asks over its 6.5 unpriced minutes, so demand is far above capacity. The batch was sorted by decision time only (:1527). Every unpriced observation from age 1 minute on competed equally, because its 1-minute slot never books while it is unpriced. Coins already dead and coins only at their 1-minute read took the batch. Observations inside their 5:00–7:30 window, the only marks that book the label, waited behind them and were filed GAP.

**Price-only curve marks.** `markFor` (truth/ForwardReturnLabeler7731.kt:1295) prices a curve coin from its observed reserves within 45 s. Those are real prices and are kept. The real gap was the scheduling above, not the curve.

**BASIS (44).** Any mark failing `basisSuspect7738` drops the whole observation. That is correct: it is a basis error, not a move. No change.

**Fix (V5.0.8291).** New `engine/cortex/CortexLabelFeed8291.kt` ranks the off-watch requests:
- rank 0: inside the 5-minute window;
- rank 1: the entry mark inside the re-base window;
- rank 2: from half the horizon to the horizon (this read still books the label as LAST_MARK);
- rank 3: everything else.

Within a rank, the oldest goes first. The labeler's three `dueUnpriced7737.add` lines now carry the rank key (truth/ForwardReturnLabeler7731.kt:1414–1420). This is the only edit outside `engine/cortex/*`, made because the root cause sits there. No mark is invented: only the order in which real marks are fetched changes.

**Impact.** More decisions get a real in-window or LAST_MARK read. This raises the resolved share the walk-forward needs (UNRESOLVED_COHORT) and the STRONG grade counts (item 5).

## 2. unexec=803 (LABEL_HYGIENE_UNEXECUTABLE)

**Finding.** `CortexLabelHygiene8144.executableDecision8144` (engine/cortex/CortexLabelHygiene8144.kt:64) files a refusal as unexecutable in three cases:
- (a) the lane's native specialist refused it (`nativeEligible == false`, from 8196);
- (b) the reason names a native mandate band;
- (c) the reason contains a hard word anywhere in its text (`UNEXECUTABLE_8144`, :34).

(a) and (b) are right. A coin the lane could never buy must not grade STRONG books, and that is how +83% STRONG artefacts happen. (c) over-matched learned vetoes whose names carry a hard word: `TOXIC_PATTERN`, `LEARNED_TOXIC_LANE`, `TOXIC_BUCKET_HARD_VETO_6249`, `PAPER_EV_BUCKET_HARD_BLOCK_6405`. These are statistical judgements about buyable coins. Filed unexecutable, their refusals never reached the veto book, so the system could never show them refusing winners.

There was also no breakdown of the 803.

**Fix.**
- A learned-veto marker with no physical word (rug, route, safety, freeze, liquidity, and so on) is now executable. A native refusal and the mandate list still rule.
- `unexecClass8291` (:83) buckets every unexecutable decision as NATIVE_INELIGIBLE, NATIVE_MANDATE, or the hard word matched. The counts appear in the hygiene line as `unexecWhy8291=[...]` (Cortex7885.kt:291).
- Most of the 803 should show as NATIVE_INELIGIBLE. That is by design: the coin was not buyable in that lane.

## 3. Timing cortex WINDOW_MISSED=977 of 1058

**Finding.** `CortexTiming7900.tick` (engine/cortex/CortexTiming7900.kt:149) grades a sample on the first mark between 5:00 and 9:00. Its marks come from the same mark chain (`markFor7948`) on the same clock as the labeler. A refused coin is priced only when the labeler's off-watch batch asks for it, so the timing window starved for the same reason as item 1, and worse: the timing cortex has no LAST_MARK fallback.

**Fix.** Rank 0 in item 1 (5:00–7:30) falls inside the timing window, so the marks the labeler now fetches first are the ones the timing cortex grades on. There is no separate code change and no invented or stale mark.

**Residual.** The timing target stays a mark inside its own window. This will not fully close the gap if the batch capacity itself is too small. That capacity belongs to the data-delivery owner.

## 4. Calibration slopes all 1.00 (n=26..84)

**Finding.** Calibration was learning (`calibration.learn` in onLabel). But `centredSlope8169` returns identity below `MIN_N`=100 decayed grades, and the 0.995 decay caps a lane near 200. So 1.00 was not a measurement. It also passed every `slope >= MIN_CONSISTENT_SLOPE (0.5)` check, so an over-confident Cortex was read at face value.

**Fix.** `CortexCalibration7901.earlySlope8291` (:53):
- from 20 grades, the measured slope phases in linearly, reaching full weight at 100;
- below 100 it may only shrink (an early stretch above 1 is not trusted);
- at 100 and above, the estimator is unchanged (existing tests hold);
- the status line marks identity slopes as `1.00id(nN)`.

## 5. Authority LABEL_N everywhere (strongN 0–14 of 15)

**Finding.** This follows from items 1 and 2. STRONG grades need an executable, first-of-episode, on-time label. With most labels lost (GAP) and many excluded (native-ineligible, repeats), no lane reached 15 STRONG grades. The authority gate (`GRAD_MIN_N_7955`=15) is right to withhold authority.

**Fix.** Indirect: item 1 resolves more labels and item 2 keeps learned-veto refusals in the books. The threshold was not loosened.

## 6. Voter budget (abstained=79094 vs cacheServed=19418, refreshDropped=3500, SPECIALIST_MATCH 15 ms)

**Findings.** In `engine/cortex/CortexVoters7885.kt`:
- **Refresh claims leaked.** The refresh pool used `DiscardPolicy`. A discarded refresh never reached its `finally`, so its key stayed in `refreshQueued8187` for good, and that (voter, coin) was never refreshed again: it abstained on every read. `refreshDropped` was also counted approximately.
- **Refresh was one job per voter.** About 290 jobs per coin against a 512-slot single-worker queue, so most refreshes were dropped and the cache never filled. Hence abstained ≫ cacheServed.
- **The budget hit voters by index order.** Once a read passed 35 ms (`ASSESS_BUDGET_MS_8187`, :526), every voter after that point in `ALL` abstained, whether fast or slow, and including the voters seated in the lane, which are the only ones fusion weighs. With assessment latency p50 88 ms and p90 570 ms, most assessments were over budget by mid-list. So yes: predictions ran with their seated voters missing.

**Fixes.**
- **Core voters are never abstained.** `Cortex7885.coreVoters8291` (:165) marks voters with seat authority above 0 in the lane (cached 30 s). `readOne8169` (:684) reads them inline whatever the budget.
- **Cheapest first.** `readAll` (:659) reads the remaining voters in order of measured mean read time, so the budget is spent on many fast voters and only the slow tail goes to the cache.
- **Refresh fills the cache.**
  - One job per coin and lane (`RefreshJob8291`).
  - A rejected job is removed and counted exactly (:540).
  - Claims are timestamped and expire after 30 s (`refreshClaim8291`), so a dropped or raced claim can never pin a voter.
- **Coverage is measured.** `coverage8291[p10 p50 n coreInline refreshFilled]` is appended to the budget line, giving each assessment's share of voters that answered.

## 7. Lane playbook: LAUNCH_LADDER_PROVEN n824 +83.5% auth=1.00, COPYCAT_BETA +86% auth=1.00, while every live close lost

**Findings.**
- **The 8187 realised blend never applied to these setups.** It reads only a setup's own closes (n ≥ 2, opposite sign). The four live losses were BREAKOUT_RUNNER, SOCIAL_LAUNCH and MAINSTREAM_CRYPTO_SWING (two), so LAUNCH_LADDER_PROVEN and COPYCAT_BETA had none.
- **The lane offset fell back to the cost gap.** The paired shortfall (`CortexGradeBook8144.pairedShortfall8187`, :281) needs a labelled admitted decision per trade, those labels were lost (item 1), and it shrinks by n/(n+5). With few or no pairs, the offset was the 3.5-point cost gap: the "exec −3.5" on screen.
- **Setup authority had no realised cap at all.** `LanePlaybook7907.setupFraction7955` (:1477) carries graduated setup authority. That authority overrules a live edge refusal at ≥ 0.5 (`LiveEdgeGate7877.setupAdmits7955`, truth/LiveEdgeGate7877.kt:231) and sizes conviction (`provenSetupRecord7948`). The Cortex's own 8136 realised cap is not decisive either: under 5 closes it is a 0.95 "partial" (`GRAD_PARTIAL_CAP_7955`).
- **Hive inflation.** n824 includes up to 1,000 hive labels from other installs (item 8).

**Fixes.**
- `Cortex7885.setupRealisedCap8291` (:1595) sets the cap from realised closes:
  - the setup's own closes: 2 or more with mean ≤ 0 → cap 0;
  - the lane's newest 10 realised closes in the account mode: 2 or more with mean ≤ 0 and at least 2 in 3 losses → cap 0;
  - mean ≤ 0 otherwise → cap 0.25 (under the 0.5 overrule bar).
- It is applied through `LanePlaybook7907.setupAuthority8291` (:1484) in `classifiedAuthority7955` (the LiveEdgeGate overrule), `provenSetupRecord7948` (conviction sizing) and the screen, which shows `auth=0.00(labels 1.00, realisedCap8291)`.
- With 5.0.8286's record (SHITCOIN −33.8% and −16.7%; MOONSHOT −10.6% and −9.2%), every SHITCOIN and MOONSHOT setup loses overrule and size-up power at once, not 3.5 points of it.
- The cap only lowers. It is re-read on every call, so authority returns with realised wins.
- I deliberately did not apply the cap to the "proven" admission test (`setupProvenPositive7948`). A lane-wide denial there could lock a live lane out of the trades it needs to recover. That door belongs to the admission owner.

## 8. Hive brain: seatsInherited=1354, specialistsFromHive=3, HIVE_SCORE_INVALID_8076=2184

**Findings.**
- **Inherited seats.** `hiveInherit8001` (Cortex7885.kt:2646) seeded an empty voter seat at the hive's full weight: `scored` up to the network count, bins capped at 1,000 per key. The seat was "seasoned" at once on other builds' label definitions; the hive keys carry no epoch. `hiveSnapshot8001` (:2613) then re-exported it as this install's own, echoing it back into the network.
- **Playbook setup records.** `LanePlaybook7907.statRaw8187` (:716) added up to 1,000 hive labels to the local record, so a setup could hold authority on foreign data alone.
- **HIVE_SCORE_INVALID_8076.** It is counted again on every inherit pass, so 2,184 is not 2,184 distinct seats. The rejections are mostly `v[5] != bins`: foreign voter-edge schemas, rightly refused.
- **specialistsFromHive=3** is SpecialistMiner, outside the Cortex. Not changed.

**Fixes.**
- **Inherited seats.** The seed is scaled to at most 30 scores (`hiveSeatScale8291`), keeping the hive's skill ratios and bin means. That is under `MIN_SCORED`=60, so an inherited seat holds no authority until this install has graded it 30 times itself. A seed is not exported until it has 30 local scores (`hiveExportable8291`).
- **Setup records.** The hive lends at most clamp(local n, 10, 60) labels (`hiveScale8291`), keeping mean, spread and rates. Foreign data alone stays under `GRAD_MIN_N_7955`. The screen shows `local=N hive=lent/total`.
- **Residual.** Seats inherited by earlier builds carry no marker and were not touched (rule: never reset learning). They age out under the 0.995 decay as local grades accumulate.

## Growth target (2x–5x daily)

Judged against the owner's mandate (/tmp/claude-0/growth_mandate.txt), within the Cortex area only.
- **Compounding from the Cortex was starved.**
  - `convictionMult` (Cortex7885.kt ~2060) sizes toward quarter-Kelly of current equity, so stakes grow with the wallet. But since 8187 it requires `validatedEvidence8187`: walk-forward out-of-sample proof, or proven realised closes.
  - Walk-forward proof needs resolved, on-time STRONG labels. With 701 lost and strongN under 15 (items 1 and 5), no lane could validate, so the Cortex size-up never fired and stakes stayed flat at ROUTE_MIN.
  - Items 1, 2 and 6 restore the evidence feed: labels mature, learned-veto refusals stay in the books, and seated voters are present.
  - No threshold was loosened.
- **Aggression was being spent on losing setups.**
  - Setup authority 1.00 on LAUNCH_LADDER_PROVEN and COPYCAT_BETA overruled live edge refusals and sized conviction while live closes lost (−10% to −34%).
  - Item 7 removes that power from lanes whose real, on-chain realised closes lose, and returns it as soon as they win.
  - Item 8 stops foreign hive labels granting it.
  - This points aggression at proven live edge, as the mandate requires.
- **Not in my area, not audited here.** `LiveSizingProfile`, `AutoCompoundEngine`, the daily-target snapshot in BotService, and FirstSight probe caps. FirstSight8026 sizes through the same `convictionMult`, so it compounds once validated evidence exists. Whether the wallet-level daily-target and auto-compound path is still wired should be checked by the sizing owner.
- **Safety unchanged.** No hard safety was loosened, no paper result feeds live authority (realised caps are per account mode), and no mark or outcome is fabricated.

## Pins updated (superseded by V5.0.8291)
- `Aate8187CortexCompleteTest`: the first-mark pin now reads `val first8291 = firstMarkDue8187(...)` and `if (first8291) dueUnpriced7737.add(`.
- `Aate8013GradingScheduleTest`: the checkpoint off-watch pin now carries the rank key.
