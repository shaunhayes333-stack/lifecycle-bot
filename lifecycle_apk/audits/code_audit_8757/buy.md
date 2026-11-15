# AATE meme buy path: code audit at 5.0.8757

**Code:** `origin/main` = `916e8eb71` (5.0.8757, "live MOONSHOT mirrors the paper MOONSHOT book"). This was a read-only audit. The repository was not modified.
**Path prefix:** `K/` = `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`
**Runtime log:** `aa487cd1-aate_pipeline_20261115_150239_790.txt`, build 5.0.8756, 333 s uptime, LIVE, wallet 0.239 SOL. FDG allowed 0 of 163. Top FDG refusals: EDGE_MODEL_NEGATIVE 48, LIVE_ORIGIN_COHORT 36, DATA_INCOMPLETE 34, EXECUTION_ECONOMICS 25, SAFETY 19. The last crash was an OutOfMemoryError (ageMin 259). Build 8757 (the paper mirror) has **no runtime record yet**. Everything said about it below comes from reading the code.
**Scope:** this report does not repeat `audit_8629/audit_decision_now.md` or `DIAG_AND_BUILD_8629.md`. It covers what they missed and what changed between 8629 and 8757.

## 0. What changed since the 8629 audit (affects how the earlier findings read)

| 8629 finding | State at 8757 |
|---|---|
| Live admission runs `decide8136(copy(live=false)){false}` | **Changed.** Live now runs `decide8136(candidate, ::verifiedRecordDoor8650)` (`K/engine/truth/CandidateAdmission8136.kt:1622`). Only 5 record doors bind (`:1003-1006`). Every other `doorBinds8393(...)` door is **still dead in live** (finding 9). |
| LaneAuthority turns learned refusals into ADMIT | **Changed.** `applyLane8577` (`LaneAuthority8577.kt:298-329`) no longer flips REFUSE to ADMIT. The header doc (`:7-19`) and the brief ("overrides=0 … rule=V3+lane qualify") still describe the old behaviour (finding 17). |
| The edge model has no authority | **Changed.** In 8715 it gained a refusal-record authority (`EdgeModel8537.kt:310`, `callOf :556`). It is now the #1 live refusal, but the brief does not show that (finding 5). |
| 8757 is new | `PaperMirror8757` re-admits a refused live MOONSHOT candidate whenever the paper-way decision admits it. Findings 1-4 and 20 concern it. |

---

## 1. Findings, ranked

### P0: money-losing or blocking

**1. P0. The paper mirror admits live MOONSHOT buys with unknown or uneconomic expected gross, and no later layer re-checks it.**
- `K/engine/truth/PaperMirror8757.kt:70`: `val paper = CandidateAdmission8136.decide8136(c.copy(live = false, liveEdge8114 = null)) { false }`
- In paper mode, `paperExplores8539(c) = !c.live && specialistQualified8539(c)` (`CandidateAdmission8136.kt:993`). For a native-qualified MOONSHOT coin this switches off three checks:
  - the economics refusal (`:804`, "paperExplore8539 economicsAdvisory");
  - the unread-move defer (`:831-832`);
  - the falling-hour refusal (`:789`).
- The paper decision then admits `GENERIC_PRIOR_CLEARS_8136` (`:949`) with no gross at all.
- Live refusals that come before the economics step are not on `HARD_8757`, so the mirror overrides them: FALLING_HOUR_PROVEN_LOSING_8418 (`:794`, which by construction only fires when `decisionGross` is NaN), CELL_PROVEN_LOSING, SETUP_RECORD_LOSING, SHEET_AVOID and TIMING_ADVERSE. The paper decision also reads the paper stale-price bar (5 min, or 60 s hot) instead of the live one (30 s, or 15 s hot) (`:428-430`, `PriceAge8549.kt:18-19`).
- Nothing downstream catches this:
  - LaneAuthority's `EXECUTION_NET_NONPOSITIVE_8629` check (`LaneAuthority8577.kt:313`) runs **before** the mirror (`CandidateAdmission8136.kt:1621-1627`).
  - The gate's NetEdge refusals (`NET_EDGE_BELOW_2X_COST_8030`, `NET_EDGE_MOVE_UNOBSERVED_8418`) are not in `HARD_8757`, so `passesAtGate8757` passes them.
  - The FDG seal re-quote returns CLEAR whenever the size did not move (`FinalDecisionGate.kt:836`).
  - The Executor has no net-at-size check: `entryClears8030` has no caller in Executor, PreTradeHardGate, TicketExec or ExecutableOpenGate. That contradicts the 8649 comment at `LaneAuthority8577.kt:304-307`.
- The mirrored decision also mixes fields from two decisions (`PaperMirror8757.kt:75-77`): `chosenSetup` comes from the paper decision, while `expectedNetPct`, `costPct`, `basis` and the witness flags stay from the refused live decision.
- **Fix.** In `mirror8757`, require `paper.expectedNetPct.isFinite()` and `NetEdge8030.entryClears8030(paper.expectedNetPct + paper.costPct, c.costPct, c.costMargin8391)`. Re-check the live stale-price bar. Copy net, cost and basis from `paper`. Better still, run the mirror's paper decision with `paperExplore` forced off, so only learned record doors are skipped.

**2. P0. A mirrored admit skips the buy-door probe.**
- `CandidateAdmission8136.kt:1626-1627`: `.let { awaitBuyDoorProof8703(...) }` runs, then `.let { PaperMirror8757.mirror8757(...) }`.
- `awaitBuyDoorProof8703` returns immediately for a non-admitted decision (`:1419`). The REFUSE becomes an ADMIT only afterwards, so the mirrored admit never meets:
  - the PreTradeHardGate hard-block probe (8717);
  - the buy backoff;
  - the safety-proof wait.
- This is exactly the "ticketed, then dies at the door and sets a backoff" loop that 8703 and 8717 were written to stop.
- **Fix.** Apply `mirror8757` before `awaitBuyDoorProof8703`, or re-run `awaitBuyDoorProof8703` on the mirror's output.

**3. P0. The −0.1 SOL "automatic stop" does not bound the loss.**
- `PaperMirror8757.kt:49-61` counts **realised** live MOONSHOT closes only (`LiveConfidenceStats.byLane8280`, SELL / PARTIAL_SELL rows).
- The window is process-scoped (`@Volatile startMs8757`, `tripped8757`), so every restart re-arms it. The log's last crash was an OOM.
- Open mirrored positions at normal sizing are not counted. Before the first loss closes, the wallet can be filled with mirrored entries: 40 live slots, a 0.239 SOL wallet. The lane's own realised-loss probe gate is advisory (finding 8).
- **Fix.**
  - Persist `startMs8757`, the realised sum and the tripped flag.
  - Count open mirrored positions at their executable mark.
  - Cap concurrent mirrored opens (for example 1).
  - Cap cumulative mirrored notional at 0.1 SOL / avg ticket.

**4. P0/P1. At the gate the mirror overrides every binding door that is not on its own third copy of the hard list.**
- `LaneAuthority8577.kt:334-336`: `(!binding8577(reason) || PaperMirror8757.passesAtGate8757(reason, admission))`.
- `passesAtGate8757` is true unless `hard8757(reason)`. That bypasses:
  - (a) `verifiedRecordDoor8650` doors (`binding8577 :249`), which 8650 says "cannot be reversed … again at LiveEdgeGate";
  - (b) doors bound by their own record (`doorProtects8715`). The log shows `CURVE_NOBODY_BOUGHT_8046` n61 −38.9% PROTECTING, and that reason is not in `HARD_8757`;
  - (c) `AT_LAUNCH_CAP_8047`, `LIVE_POCKET_*`, `NON_ECONOMIC_APPROVAL`, `GENERIC_SCORE_FLOOR`, `NET_EDGE_BELOW_*`, `PRICE_COLLAPSED_ONCHAIN_8038`. All are binding in `BINDING_8577` or in `FdgGate8365.HARD_MARKERS_8392` (`FdgGate8365.kt:301-307`), and none is in `HARD_8757` (`PaperMirror8757.kt:27-34`).
- **Fix.** `hard8757(r) = FdgGate8365.hardSafety8392(r) || BINDING_8577 static match || verifiedRecordDoor8650 || GateOutcome8418.doorProtects8715(r, lane) || r.contains("CURVE_NOBODY") || r.contains("COLLAPSED")`. The mirror should only pass doors that are learned and **not yet** protecting.

### P1: wrong decisions

**5. P1. The edge model is the #1 live refuser, but the brief reads a different record and says no lane has authority.**
- The verdict takes its record from `oos[recordKey(raw.lane, key)]`, where `key` is whatever `predict8737` returned: Mind8746, the tree ranker, or the ridge (`EdgeModel8537.kt:539-548`).
- The status line reads `oos[recordKey(l, activeModel(l))]`, which is the ridge key only (`:779`).
- The brief prints `refuse8715=n1557 … vsKept=n0 … learning` for BLUECHIP and CASHGEN. Yet `ADMISSION8136_EDGE_MODEL_NEGATIVE_8537` refused 48 of 163 live candidates and is filed n388 (log l.58, l.424).
- The doc comments say "Until it has authority it decides nothing" (`EdgeModel8537.kt:30`; `CandidateAdmission8136.kt:1018-1020`), while `:1029` binds in LIVE on `refuses8715` alone.
- **Fix.** Print the record of the key the verdict used (store it on the Verdict) together with `refuses8715`. Update both doc comments.

**6. P1. Admission and gate price cost differently, and the measured median never binds an admitted entry.**
- Admission: `candidateFor8136` uses `NetEdge8030.entryCost8209` → `costPct8030`. That is the model, or the 8-trade mark measurement (`CandidateAdmission8136.kt:1775-1776` region; `NetEdge8030.kt:375-381`).
- Gate: `entryRefusal8030` uses `preTradeCostPct8329` = max(model, lane measured median) (`NetEdge8030.kt:225-245`).
- The comment at `NetEdge8030.kt:227` says "The admission reads the lane's MEASURED round trip". It does not.
- The gate refusal is also skipped for specific and generic-native admits (`LiveEdgeGate7877.kt:1373`). When it does fire, it is advisory for any admitted candidate: `NET_EDGE_*` is not on `BINDING_8577`, though `FdgGate8365` calls `NET_EDGE_BELOW` hard.
- **Fix.** `entryCost8209` should return `preTradeCostPct8329(...)`. Add `NET_EDGE_BELOW` to `BINDING_8577`.

**7. P1. A binding-list entry is never emitted, so the measured-cost refusal is advisory.**
- `BINDING_8577` lists `"COST_LEDGER_7962_ABOVE_GROSS"` (`LaneAuthority8577.kt:30`). No code emits that string. The real reason is `"COST_EXCEEDS_EDGE_7962_$l"` (`CostLedger7962.kt:427`).
- Result: "measured all-in cost above the setup's gross" is advisory for every admitted candidate.
- **Fix.** Bind `"COST_EXCEEDS_EDGE_7962"`. Add a unit test that every `BINDING_8577` entry matches at least one reason string the code produces.

**8. P1. The bot's own realised-loss doors are advisory, so a losing lane is not throttled.**
- The hold from `EntryEconomics8329.laneRealisedRefusal8329` returns `LANE_REALISED_LOSING_PROBE_OCCUPIED_8424_<lane>` or `…_PROVEN_PROBE_WAIT_8424` (`LaneRealisedProbe8424.kt:34-35, 99-100`). It becomes `doors8577` (`LiveEdgeGate7877.kt:555`).
- `LIVE_SETUP_LOSING_8083` (`:788`) is not on `BINDING_8577` either.
- `advisoryAtGate8577` makes both advisory for any admitted candidate (`:561-566`). That contradicts the comment at `:515` ("this captured hold remains the final live veto") and the canonicalRefusal comment ("Only the candidate's own setup proven losing on live closes refuses").
- At admission, `liveLocalGuard8325`'s `LIVE_LANE_REALISED_LOSING_8325` binds only if `doorBinds8393(why)` (`CandidateAdmission8136.kt:854`). Live passes `verifiedRecordDoor8650`, so it never binds.
- **Fix.** Add `"LANE_REALISED_LOSING"`, `"LIVE_SETUP_LOSING_8083"` and `LIVE_LANE_REALISED_LOSING_8325` to the binding and verified sets. These are the bot's own live losses, not learned opinions.

**9. P1. In live, every `doorBinds8393` door outside the 5-member verified set is dead. That includes the RUG_PRONE entry wait.**
- `CandidateAdmission8136.kt:621-624`: `ENTRY_${wait}_8506` refuses only `if (... doorBinds8393("ENTRY_${wait}_8506"))`. Live passes `::verifiedRecordDoor8650` (`:1622`), which holds only 5 names (`:1003-1006`).
- So `ENTRY_RUG_PRONE_8506` never refuses at admission. The coin is admitted, FDG-sized and wallet-sealed, then `TradeAuthorizer` refuses it as `ENTRY_RUG_PRONE_8506` (`TradeAuthorizer.kt:451-460`; `RunnerAdmission8180.kt:115-118` returns any non-soft wait). That spends a ticket and the wallet seal.
- The same gate keeps these doors dead in live, with comments claiming they bind:
  - `LIVE_REGIME_COLD_8546` (`:521`)
  - `STAGE_PROVEN_LOSING_8136` (`:711`)
  - `TACTIC_RECORD_LOSING_8136` (`:730`)
  - `LIVE_WATCHLIST_RECHECK_UNPROVEN_8507` (`:885`)
  - `LIVE_INTO_HIGH_UNPROVEN_8545` (`:905`)
  - `LIVE_LAUNCH_UNPROVEN_8559` (`:915`)
  - `LIVE_NATIVE_OPINION_UNPROVEN_8536` (`:924`; its comment at `:893` says "Not a demotable door")
  - `LIVE_GENERIC_PRIOR_UNSUPPORTED_8238` (`:945`)
- **Fix.** Make `ENTRY_RUG_PRONE_8506` (and DUMPING / PEAK waits, to match TradeAuthorizer's DANGER set) unconditional in live. Then either put each remaining door in the verified set or delete it.

**10. P1. When admission fails, the live gate fails open.**
- `assess8136` wraps everything in `try { … } catch (_: Throwable) { null }` (`CandidateAdmission8136.kt:1572, 1644`). That catch includes OutOfMemoryError, which the log shows.
- `LiveEdgeGate7877.kt:728-783`: a null admission maps to `null -> Unit` and the gate carries on as if there were no admission. FDG's `floorFromAdmission8136` returns null, so the lane-wide floor bypasses decide (`FinalDecisionGate.kt:1177, 1210-1237`).
- Related: `PreTradeHardGate.probeDoor8717` failing → null → no block and no wait (`CandidateAdmission8136.kt:1425-1441`).
- **Fix.** In LIVE, a null admission or a failed probe should be `DEFER "ADMISSION_UNAVAILABLE_8757"`, and that defer should bind.

**11. P1. A non-specific REFUSE admission is not terminal at the live gate.**
- `LiveEdgeGate7877.kt:773-781` returns only when `d.specific`. `NO_SUPPORTED_EDGE_8136`, `GENERIC_SCORE_FLOOR_8136`, `LIVE_GENERIC_PRIOR_*` and `LIVE_REGIME_COLD` are all `specific=false`, so they fall through to the remaining doors. If none fires, `final == null` and the gate returns ADMIT (`:568`).
- On the FDG side, `floorVerdict8136` returns null for non-specific decisions (`CandidateAdmission8136.kt:1097-1102`), so the lane-wide bypasses (ladder 7932, lane proven 7941, 8112, playbook 8535) can clear the floor too.
- **Fix.** In LIVE, any `terminal == REFUSE` admission is terminal at the gate.

**12. P1. The FDG seal re-quote trusts the admission's net unless the size moved.**
- `FinalDecisionGate.kt:835-836`: `if (!admissionCostStale8209(admission.costSizeSol, sealedSizeSol)) return CLEAR_8395`. An admitted decision with `expectedNetPct = NaN` or ≤ 0 is sealed when the size is unchanged.
- Today findings 1 and 4 produce such decisions. So can any admit whose gross came from a path that only advisory doors checked.
- **Fix.** Always require `admission.expectedNetPct.isFinite() && NetEdge8030.entryClears8030(net + cost, sealedCost, margin)`.

**13. P1. `binding8577` reads the door's all-lanes record, not the lane's own.**
- `LaneAuthority8577.kt:253`: `GateOutcome8418.doorProtects8715(r)`. The lane argument is omitted.
- `doorProtects8715(reason, lane)` (`GateOutcome8418.kt:92-97`) would use the lane's own record at n ≥ 40.
- Result: a door that protects in SHITCOIN binds in MOONSHOT, and a door that refuses MOONSHOT winners keeps binding there.
- **Fix.** Pass the lane through `advisoryAtGate8577` → `binding8577(reason, lane)`.

**14. P1. Three divergent "hard refusal" lists.**
- The lists:
  - `LaneAuthority8577.BINDING_8577` (`:26-45`)
  - `PaperMirror8757.HARD_8757` (`:27-34`)
  - `FdgGate8365.HARD_MARKERS_8392` (`:301-307`)
- How they differ:
  - `NET_EDGE_BELOW`, `ECONOMICS`, `COST_REQUOTE` and `AUTHORITY` are hard only in 8392.
  - The entry-state doors (`MEME_BUYS_THE_HIGH_8582`, `LIVE_STAGE_*`, `LIVE_ORIGIN_COHORT_*`), `GENERIC_SCORE_FLOOR`, `LIVE_POCKET_`, `AT_LAUNCH_CAP` and `EARLY_LAUNCH` are binding in 8577.
  - `AT_LAUNCH_CAP` and `EARLY_LAUNCH` are missing from 8757.
  - `CURVE_NOBODY_BOUGHT` is classed SAFETY in `FdgGate8365.class8365` (`:139`) but is in none of the lists.
- **Fix.** Keep one `HardRefusals` object with typed classes (SAFETY, INTEGRITY, ECONOMICS, OWN_LOSS, ENTRY_STATE). Every caller reads it.

### P2: quality, stacking, patch rot

**15. P2. Size multipliers compound after the "one immutable notional authority".**
- `FinalDecisionGate.kt:6212-6253`: the 6552 block sets `size = proposal × clamp(raw, 0.35, 1.5) × prior8083 × Cortex sizeShape`. This re-inflates any upstream shrink to at least 0.35 × the proposal, except for 4 pinned tags.
- Then `cooperativeDeskSizeMult6599` (0.65-1.25) is applied afterwards (`:6296-6300`, adopted at `:6350`). It is the conviction-weighted mean `sizeMult` over **every** desk hypothesis, including other lanes.
- Then `resolveExecutableSize7835` applies LaunchEntryShaping7871 (`:6461`), LiveRiskPolicy7807 (drawdown, governor) (`:6478-6512`) and OrderSizeResolver6441 (`:6540`).
- **Fix.** Delete the desk multiplier, or fold it into the bounded 6552 shape. Let LiveRiskPolicy7807 be the single live sizer.

**16. P2. MOONSHOT counts the V3 score several times and builds confidence from the score itself.**
- `MoonshotTraderAI.kt:642-649` adds +5..+15 for V3. That lane score becomes FDG `laneScore` and the admission's `genericScore` (`FinalDecisionGate.kt:1883, 1898`), which gates `specialistQualified8539` and so the paper-explore waivers.
- Confidence is `score/120×100 + 0.2×v3Confidence` (`:853`). BotService then adds +5 for the bridge and the MemeEdge nudges (`BotService.kt:29600-29650`), and passes the result as FDG `confidenceFloor` (`:29703`).
- The Harvard signature includes the total score (`"MOONSHOT_TRADER" to score`) next to its own components (`MoonshotTraderAI.kt:707-716`).
- A missing V3 score defaults to 50 here (`BotService.kt:29589`) but to `entryScore` in FDG (`FinalDecisionGate.kt:1758`).
- **Fix.** Drop the V3 term from the lane score, or exclude V3 from the FDG floor for a lane-own-score admit. Compute confidence independently of the score. Remove `MOONSHOT_TRADER` from the Harvard signature. Use one missing-V3 default (NaN, refuse).

**17. P2. LaneAuthority8577 no longer does what its doc, its brief line and its fields say.**
- The header says learned refusals are "ADVISORY for a lane-qualified LIVE candidate". `apply8577`'s doc (`:280-283`) says they become "an ADMIT". The code never converts REFUSE to ADMIT (`:298-329`), and the brief prints `overrides=0`.
- `Decision8136.laneQualified8577` is written in 5 places and read nowhere (grep: only `CandidateAdmission8136.kt:242` and assignments). That includes the mirror's `laneQualified8577 = true`.
- **Fix.** Update the doc and the brief, and delete the dead field.

**18. P2. MOONSHOT (and similar lane blocks) carry dead fail-open and probe code.**
- `BotService.kt:29711-29714`: "FDG error … proceeding without FDG veto" returns null.
- `:29721-29763`: the V5.9.691 "FDG is MODULATOR not KILLER" half-size probe.
- Both are dead, because `TradeAuthorizer` refuses first at `:259` → `SpecialistPreauthSeal7834.refusal` (`SpecialistPreauthSeal7834.kt:11-12`: a null decision gives `FDG_DECISION_MISSING_7835`, and `!canExecute()` gives the block reason).
- **Fix.** Delete the dead branches and log the FDG exception as a refusal.

**19. P2. The `laneDoor8105` flip of a MOONSHOT native refusal cannot work, and a ninth chase rule is still live.**
- `BotService.kt:1185-1186` sets `eligible=true` but keeps the refused `MoonshotScore(false, 0, 0.0, …)` score of **0** (`MoonshotTraderAI.kt:490-503`). The FDG floor then refuses it, so the "retired veto" flip is a no-op.
- `nearHighRefused8555` (pxPeak ≥ 0.92 with runup > 40, `MoonshotTraderAI.kt:454-455, 500-505`) is still active in the native trader. 8629 listed the 8555 chase as removed.
- **Fix.** On a flip, re-score with the door's input ignored, or delete `laneDoor8105`. Fold `near_high_8555` into the one chase rule.

**20. P2. The mirror runs in shadow rehearsals and does history I/O on the hot path.**
- `mirror8757` has no `FdgGate8365.shadowOnly8393()` check. A shadow-policy assessment (held lane, `LiveEdgeGate7877.kt:516-526`) can set `startMs8757` and increment `mirrored8757` for a decision that is never published. That skews the stop window and the counter.
- After the first mirror, every refused MOONSHOT assessment calls `byLane8280`, which reads up to 500 `TradeHistoryStore` rows (`LiveEntrySafetyHold.kt:748-757`). The device is under heap pressure (log: OOM).
- **Fix.** Return early when `shadowOnly8393()`. Cache the realised sum on the close bus.

### Other items (verified, lower impact)
- **Age.** Age caps are still inconsistent: SHITCOIN is 6 h in `DiscoveryFresh8631.kt:25`, 12 h or 72 h bootstrap in `ShitCoinTraderAI.kt:125-136`, and 24 h in `LaneAuthority8577.kt:133`.
- **Gate order.** `chartAdmits7950` (`LiveEdgeGate7877.kt:1396`) returns admit before the Mayhem, specialist and priors checks. It does check HARD_BLOCK and Mayhem itself (`:357-358`), so this is safe today. The ordering is fragile.
- **Margin.** `CostLedger7962.margin8391` is a constant 1.5 (`:295`). `adaptiveMargin8391` is report-only, while its name suggests it is the live margin.
- **Naming.** `NET_EDGE_BELOW_2X_COST_8030` is still named "2X" while `EDGE_MULT = 1.5` (`NetEdge8030.kt:31, 269`).

---

## 2. Suggested order of repair
1. Gate the 8757 mirror (findings 1-4, 20), or turn it off (`PaperMirror8757.enabled8757 = false`) until those fixes ship. It is the only path that now admits live money past the admission, and it bypasses economics, the buy door and record-protecting doors.
2. Merge the hard lists, and bind own-loss, cost and RUG_PRONE (findings 6-9, 13, 14).
3. Make the failure and non-admit paths fail closed (findings 10-12).
4. Clean up the size and score stacking and the dead code (findings 15-19).
