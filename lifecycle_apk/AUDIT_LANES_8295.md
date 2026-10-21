# AUDIT_LANES_8295 — specialist meme lanes (TAG 8295, base 7c147e519 / live 5.0.8286)

Owner: "it went from a money printer to buying losers. No winners, no runners."
Scope: the 12 playbook lanes (QUALITY, BLUECHIP, SHITCOIN, EXPRESS, MOONSHOT, PROJECT_SNIPER, DIP_HUNTER, MANIPULATED,
TREASURY, CASHGEN, CYCLIC, CORE): LanePlaybook7907 menus/classifier, setup authority, lane attribution of outcomes, lane
exit fit, dead lanes. I did not touch sizing, the sell executor, stops, Jupiter, admission (CandidateAdmission8136), scanner or
hunters, because other agents own them.

## 1. Headline root causes (what I fixed)

### RC-1. Realised live losses never reached the playbook setup that took the trade (mis-attribution)
- `AateEntryIdentity8136.kt:72-79` `EntryIdentityStore8136.fillBlanks`: at the close, a blank sealed `exactSetup` was filled
  with `env.entrySetup`. That field is the **desk toolkit setup** whenever the toolkit fits the lane
  (`AgenticStyleRouter.sealedSetup8080`, `AgenticStyleRouter.kt:256`): MAINSTREAM_CRYPTO_SWING, DEGEN_MICRO_SNIPE and similar. The
  playbook tag is often blank at seal time, because `StrategyHypothesisEngine.entrySetupTag8091` only has a tag when the
  FDG hypothesis call ran with `entryTag8091`.
- `CortexLabelHygiene8144.kt` `CortexOutcomeRoute8144.owner8144` trusts the sealed setup first. The whole-position close was
  therefore filed as `MOONSHOT|MAINSTREAM_CRYPTO_SWING`, which matches the 8286 report: "MOONSHOT MAINSTREAM_CRYPTO_SWING
  -10.6%, -9.2%". No playbook record reads that key, so `Cortex7885.setupRealised8187(lane, setup)` stayed empty for the
  setup that admitted and sized the trade.
- **Fix**: `LaneSetupAttribution8295.deskSetup8295` (new, `cortex/LaneRealisedGovernor8295.kt`). A desk-vocabulary name is
  no longer filled into the seal, and `owner8144` skips a desk name in the sealed tag, the snapshot tag and the entry setup.
  It falls through to the snapshot tag and then the Cortex's own entry setup (`trade.setup` = DECISION_SELECTED playbook
  setup), or ends UNVERIFIABLE (lane economics only). Playbook and desk vocabularies were checked and have no names in common.
  Closes filed before this fix stay as they were: no history is rewritten. They still count at lane level (RC-2).

### RC-2. Setup authority = forward labels; realised money could not shrink it
- `LanePlaybook7907.kt:708` `stat()` → `Cortex7885.executableCopy8187` → `realisedBlend8187` (`Cortex7885.kt:~1525`) moved
  a record only when the **same setup** had **at least 2 closes of opposite sign**, with each close worth 30 labels against at
  most 60. With 4 closes spread over 4 lane/setup keys (and mis-filed per RC-1), nothing was ever blended. The records were
  built from forward labels: every fired *alt* setup is graded on every coin (`onLabel`, 7924), plus hive grades
  (`statRaw8187`) and outlier-heavy marks (labels capped at +1000%). The result was LAUNCH_LADDER_PROVEN n824 +83.5% auth=1.00
  while the bank lost. The lane's realised losses fed in only through `execOffset8187` (here -3.5pt), because the
  paired-shortfall needs on-time labels on admitted decisions, and 657 of 710 were LABEL_LOST.
- **Fix**: `LaneRealisedGovernor8295` is applied on every authority read in `LanePlaybook7907.stat()`. That covers proof,
  graduated authority, expected value, StrategyRank options, alternatives, refusals, conviction records and the status line.
  - *Setup losing* (1+ live close, mean < 0): the record is blended toward its closes (40 labels per close, at most 60
    labels), its evidence shrinks, and it is held under the 30-label proof (n ≤ 29), so it can never show auth=1.00 on labels
    the bank contradicts. Examples: 2 losing closes take 824/+83.5% down to n25.7/+21% (auth about 0.1). 3 closes take it to
    auth 0.
  - *Lane losing* (3+ attributed live closes in the account mode, mean < 0, which includes the pre-8295 mis-filed closes):
    every positive setup record in the lane loses evidence (12 labels per close of other setups) and is held under the
    30-label proof. Its mean is not moved.
  - It only ever lowers a positive record. Flat or negative records are left whole, so the proven-losing (n30+) and
    NO_TRIGGER refusals keep binding. A winning bank changes nothing, and the 8187 up-blend (realised winners lifting a
    record) is kept. Stored labels, ledgers and history are untouched. Authority recovers as soon as the lane's bank does.
  - The losing direction of 8187 is handed to the governor so a close is not counted twice (`stat()` passes the realised
    count to `executableCopy8187` only when the realised mean is > 0).
- Accessor: `Cortex7885.laneRealised8295` (one line), cached for 5 s on the playbook side because `stat()` is hot.

### RC-3. Runner-lane "tail" exemption let losing setups trade in a losing lane
- `provenLosing` / `expectedNegative7948` (`LanePlaybook7907.kt:956,1064`) exempt every runner lane (`RunnerExitProfile7277`
  lists almost every meme lane, CORE and DIP_HUNTER included) when 10% of a setup's labels grossed +50%. The mean already
  includes those runners. With no runners captured live ("no winners, no runners"), the exemption kept negative setups live.
- **Fix**: `runnerTail8295(lane)`: the exemption holds only while the lane's realised closes are not losing
  (`liveRefusalForSetup`, `provenLosingNamedSetupRefusal`).

### RC-4. Paper realised records could carry into live authority (mode carry-over)
- `Cortex7885.refreshExecutable8187` only *wrote* `realisedBySetup8187` entries for the current mode and never removed a
  setup absent in that mode. After a PAPER↔LIVE switch, paper per-setup realised stayed in the map. Through the 8187
  opposite-sign blend, paper wins could lift a live record. **Fix**: stale `lane|setup` keys not in this mode's set are dropped
  on refresh (two lines).

## 2. Per-lane findings

| Lane | Thesis / menu | What 8286 shows it admitted | Finding | Status |
|---|---|---|---|---|
| SHITCOIN | launch/flow menu + 8061 cross (incl. BREAKOUT_RUNNER) | 2 live closes: BREAKOUT_RUNNER -33.8%, SOCIAL_LAUNCH -16.7%; ROUTE_MIN, UNPROVEN | Admission went through the kindProven8063/outlier path (another agent). LAUNCH_LADDER_PROVEN shown at auth=1.00 on labels | RC-2/RC-3 fixed; admission path belongs to the admission agent |
| MOONSHOT | runner menu | 2 live closes filed as MAINSTREAM_CRYPTO_SWING (-10.6, -9.2) | RC-1: the toolkit setup took the attribution. Also the `AgenticStyleRouter.kt:39` style MAINSTREAM_CRYPTO_SWING lists MOONSHOT in its lanes, so a quality-coin swing style (hold 2.1x, swing trail) is sealed on sub-$1M memes. The toolkit candidate itself declares QUALITY/BLUECHIP only (`ToolkitSignalSheet.kt:~603`) | RC-1 fixed. Style lane list **not changed** (exit/style routing has many pins; recommend dropping MOONSHOT from that style unless mcap ≥ $1M) |
| PROJECT_SNIPER | PRE_IGNITION specialist, 15-180 s window | candidateN=0; NATIVE_BIRTH_METADATA_HYDRATING=470, PHASE_HANDOFF=379 | Lane side: `ProjectSniperAI.assessTarget` requires `LaunchPhaseAuthority7401.snapshot.birthResolved` (`ProjectSniperAI.kt:~266`) even when `AdmissionBirth8144` gave an age, because the phase classifier needs a canonical birth to compute stages (`LaunchPhaseAuthority7401.kt:220`). It then needs PRE_IGNITION flow (3+ buyers, 60% buy share, rising acceleration) or IGNITION with a *proven* setup (8118). With tape NO_PRINTS=3604, flow is unknown, so stages go to BIRTH → HANDOFF. Without birth data the gate is working as designed, not a bug. Loosening it would buy the pond the evidence calls worst (AGE_MIN<3 strongly negative, PROJECT_SNIPER refuse bucket -42.8%, EXPANDING stop rate 67-83%) | No loosening. Birth hydration + tape belong to other agents. The 8118 proven-ignition door now reads realised-governed authority |
| EXPRESS | migration/continuation | mandatePassed 7/118 (hunter) | No lane-side defect found. The menu matches the thesis | — |
| DIP_HUNTER | sweep/reclaim | worst cell MARKET_HUNT_SHITCOIN\|DIP_HUNTER -72% | It is listed as a runner lane in `RunnerExitProfile7277` (tail exemption). RC-3 now refutes the exemption when its bank loses | RC-3 |
| MANIPULATED | distribution reclaim | 2/86 hunter | Buyer gated by `manipulatedBuyerEnabled7609`. No lane defect found | — |
| CORE | ensemble | refuse bucket -39% | The native defaults to `MAINSTREAM_CRYPTO_SWING` (`SpecialistBrainBridge7542.kt:352`), a desk name: wherever it reaches the entry snapshot it hit the RC-1 path | RC-1 fixed |
| BLUECHIP | swing | native setup MAINSTREAM_CRYPTO_SWING (`SpecialistBrainBridge7542.kt:317`) | Same RC-1 attribution path | RC-1 fixed |
| QUALITY / TREASURY / CASHGEN / CYCLIC | non-meme / scalp | no live closes in the window | CASHGEN stays a scalp lane (`TradeExitContract8077.kt:26`), and the runner ladder applies only to MOONSHOT/SHITCOIN/EXPRESS/PROJECT_SNIPER when `runnerBasis8136` != NONE. Fit is correct | — |

## 3. Lane exits / targets (lane policy only)
- Runner from entry: only MOONSHOT, SHITCOIN, EXPRESS and PROJECT_SNIPER, and only when the sealed style or a proven
  promotion says runner (`TradeExitContract8077.runnerFromEntry8104`). Scalp lanes such as CASHGEN keep scalp exits. This is
  consistent.
- Setup grading follows each setup's exit family (`familyLabel8080`): long-hold setups are graded on the long path, scalps
  on the first read. This is consistent.
- Mismatch (not changed, flagged above): MAINSTREAM_CRYPTO_SWING / WHALE_ACCUMULATION_HOLD / SMART_WALLET_COPY_FOLLOW styles
  declare MOONSHOT, so a MOONSHOT meme can be sealed on a quality-swing exit. Owner of runner capture and exits should decide.

## 4. Duplication / competition
- One coin's forward label is graded into every fired setup's book (primary plus alts, 7924) and in every lane that captured
  it. This inflates alt setups such as LAUNCH_LADDER_PROVEN (n824) that were rarely the traded decision. It is now bounded,
  because authority needs the bank (RC-2), and the label n is capped at 60 against realised closes.
- Lane ownership of a live coin is elected once (LaneExecutionCoordinator / sealed identity). I found no double-buy across
  lanes. Outcome attribution goes to the sealed lane (RC-1 fixed the setup half).

## 5. Growth target (2x–5x daily)
- Judged against the mandate, the defect in my scope was aggression spent on **unproven/losing setups**. Label-only
  authority (auth=1.00) fed `provenSetupRecord7948` → Cortex conviction sizing and the auth≥0.5 admission door while the bank
  lost. Fixes RC-1 to RC-3 move that authority off setups and lanes whose on-chain realised closes lose.
- Pressing harder on proven lanes is kept. A setup or lane whose realised closes win is not touched by the governor, and the
  8187 up-blend (real live wins lifting a weak label record) stays. Paper realised can no longer lift live authority (RC-4).
- The compounding / daily-target / live-sizing wiring (LiveSizingProfile, AutoCompoundEngine, BotService daily snapshot)
  belongs to the traders & sizing agent and I did not audit it here. All 4 live entries were ROUTE_MIN (~0.028 SOL)
  UNPROVEN, so size stayed flat, which is correct for unproven setups. Size can only grow from lanes with realised edge, and
  RC-1 is what lets realised wins be credited to the right setup at all.

## 6. Remains / recommendations
1. Admission of UNPROVEN ROUTE_MIN trades through kindProven8063 / outlier-suspect cohorts: admission agent.
2. PROJECT_SNIPER revival depends on birth hydration and launch tape prints (data agents). Re-evaluate once
   `LAUNCH_PHASE_7401_METADATA_HYDRATING` falls.
3. The MOONSHOT ↔ quality-swing style lane lists (`AgenticStyleRouter.kt:32-55`): runner capture / exits agent.
4. Alt-setup grading (every fired setup gets the label) could be limited to setups that were selectable as the primary
   strategy. This is a learning-design change, left for a dedicated pass.

## Files
- new `app/src/main/kotlin/com/lifecyclebot/engine/cortex/LaneRealisedGovernor8295.kt` (LaneRealisedGovernor8295, LaneSetupAttribution8295)
- `cortex/LanePlaybook7907.kt` (stat governed, runnerTail8295, status line)
- `cortex/Cortex7885.kt` (+1 accessor, refresh drops stale mode keys: 3 lines)
- `cortex/CortexLabelHygiene8144.kt` (owner8144 skips desk names)
- `truth/AateEntryIdentity8136.kt` (fillBlanks never seals a desk name)
- test `app/src/test/kotlin/com/lifecyclebot/engine/Aate8295LaneAuthorityTest.kt`
