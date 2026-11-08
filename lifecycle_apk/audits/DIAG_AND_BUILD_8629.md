# AATE — Diagnosis and Build Instructions (written against 5.0.8628)

Owner brief: the bot used to find good tokens, hit +600 % and +1,100 % wins, double the balance and at times win ~70 %
(APK 5.0.1983 and APK 5.0.8069–8114). Today it finds bad tokens, decides badly and exits badly. Do not revert the code base:
correct the current one so everything — discovery, cheat sheets, field guides, token cheat sheets, playbooks, Cortex,
setups, strategies, exits — works off one logic.

Sources for every claim below (full detail, file:line):
`audit_1983.md` (code at 98faee2e9, V5.9.66 ≈ APK 1983), `audit_8096.md` (cddf86c17 / 379ad2fa0), `audit_discovery_now.md`,
`audit_decision_now.md`, `audit_exits_now.md`, plus the 8622 runtime log (fa6783fb, 14:12) and edge export (33,008 rows).
The APK 4100s code is not in git (history jumps from V5.0.3985 on 20 June to V5.0.6411 on 3 August).

---

## PART A — DIAGNOSIS

### A0. What the good versions actually did

| | APK 1983 (Apr) | APK 8096 (Oct) | Today (8628) |
|---|---|---|---|
| Rules on a buy | ~40–55 refusal points, one V3 score + lane rules; one learning dial moved every threshold | strict live door with **many ways in** (proven setup, proven cell, runner cell, specialist, runner ticket, cold start, chart buy); soft refusals stood aside when their own record showed they refused winners | ~30 stages, ~90 named refusals, "is this a good entry?" asked in ≥ 9 places with different rules |
| Holding winners | Most exits ran only when the entry-confidence gate passed, so a coin in profit was often never checked; the profit trail had a sign bug and never fired; MOONSHOT sold 50 % once and trailed only above +30 %; SHITCOIN/QUALITY winners were **promoted into MOONSHOT** at +200 % / +100 % | MOONSHOT, CORE and runner tickets **held from entry** (−40 % stop, no 40 %-sells-60 % spike sale); any live position at +100 % promoted into the same hold (50 % at 2x, 30 % at 5x, 30 % at 10x); every meme buy kept a **25 % tail** sold only at +400 / +1,900 / +4,900 % or a rug | ~60 exits through 5 sell paths; the first profit sale can fire at +8–12 %; the hold needs proof first |
| Caveat | In live most runner lanes were shadow-only; paper was marked to prices up to 45 s stale. The big 1983 wins were probably paper / lenient mode — check the journal | Wins were real live closes in the 8069–8114 range | Live 8 W / 30 L, PF 0.37 |

**Lesson:** both good eras won by **letting winners run loosely** and by having **few, consistent rules**. Neither had a
smarter entry filter. Today's stack has more filters and kills winners early.

### A1. Discovery feeds the lanes old and already-pumped coins

- About **3/4 of what the meme lanes are offered is at PEAK_EXHAUSTION or DUMPING**; the whole pool loses ~13 % in 5 min.
- **Re-sightings reset `addedAt`** (`GlobalTradeRegistry.kt:416`): coins on trending lists stay in the "fresh" tier forever
  and are never evicted, while real launches age out after 120 s. New-coin share is only 26 %.
- **Selection ranks by liquidity and size** (`BotService.kt:19059`), the scanner score gives +15 for a 1 h move over +100 %
  (`SolanaMarketScanner.kt:3954-4024`), pump.fun is pinned second-to-last (`ScannerDiversityBandit.kt:33-41`).
- **Runner lanes evaluate every coin**: MOONSHOT/EXPRESS have no age ceiling (`LaneCheatSheet8323.kt:254-269`); MOONSHOT
  hunt rows are 18 h old at first decision, EXPRESS 23 days. Coins > 24 h old: 2 moves of 2x in 1,541 decisions.
- **Hunters sample trending lists** (51 % of intake, `MarketSweep7297.kt:313-385`) and force the top-4 momentum "leaders" into
  every cycle (`HunterWindow8293.kt`, `HunterRepair8293.kt:184-199`). Hunt sources lose −8.6 % to −32.5 % at 5 min.
- **Fresh launches are starved**: the fast lane needs $2k estimated liquidity, a fresh create has ~$3 (`BotService.kt:11149`) —
  129 fast-lane runs against ~1,000 creates; pump.fun is capped at 35 % of the watchlist (`GlobalTradeRegistry.kt:146-236`)
  and 24 creates/min (`FreshLaunchIntake8303.kt:41`). Sniper: 7,586 past-window vs 1,618 in-window evaluations.
- **Source labels are false**: DexScreener's paid-profiles feed is read by 5 scanners under 4 names (incl.
  `RAYDIUM_NEW_POOL`, `PUMP_FUN_NEW`); Meteora pools are labelled Raydium. That fakes multi-source confirmation and corrupts
  per-source learning.
- **Losing sources are never throttled** (direct intake at `BotService.kt:8581` bypasses the quality queue); held and
  high-touch coins re-enter as candidates on every boot (`HotConvictionWarmup.kt:100-160`, held coins −21.6 % at 5 min).
- **Missing inputs**: PumpPortal per-coin trade stream DENIED (API-key wallet < 0.02 SOL); on-chain launch feed down
  (Helius/Codex quotas).
- 140 coins went 4x+ in 24 h; 138 were evaluated; **109 were refused by gates**, top `NATIVE_CORE_OWN_ENSEMBLE_BELOW` (35).

### A2. The buy decision has no single logic

- ~30 stages: V3 floor → CandidateAdmission8136 → LaneAuthority8577 → LiveEdgeGate7877 (≈20 doors) → TradePlan7739 →
  FieldManual7715 / Council7740 → CellProof / FreshLaunch → ~25 legacy FDG checks → sizing → re-quote → TradeAuthorizer →
  Executor (which re-checks entry waits, chart, chokepoint 7742, rebuy lockout again).
- **Live decides as paper**: `CandidateAdmission8136.kt:1498` runs `decide8136(candidate.copy(live = false)) { false }`
  (8604). Every live-only rule inside it never runs in live.
- **The filters do not separate winners**: admitted −12.7 % vs refused −12.8 % at 5 min. V3 buckets are non-monotonic;
  EdgeModel picks 1.7 % vs 1.8 % 2x-rate; Cortex graded 127 of 9,086 and its voter graph is retired (8623).
- **The V3 floor binds first** (371 EXPRESS refusals) although the code itself says V3 cannot rank (`EdgeModel8537.kt:14-17`).
- **"No trigger, no trade" is not enforced**: FieldManual and Council are advisory for every meme lane; TradePlan gives any
  admit a default LAUNCH_EARLY plan (`TradePlan7739.kt:468-472`). The field manual's own refusals were the best cohort (+22.4 %).
- **The paper book authorises live**: the 8608 bench falls back to a paper record inflated by stale-mark closes, so SHITCOIN
  trades live (paper +30 %) while it is the worst live lane (PF 0.02).

### A3. The knowledge sources contradict each other

- **Buy pressure has opposite signs**: 8582 refuses bp ≥ 60; the playbook's MEME_8383 setups only fire at bp ≥ 50–55; the
  pocket ledger's best pocket is EXPANDING & bp ≥ 80 (+15 %, t 2.63).
- **"Buying the top" has 7 definitions** (8065, 8468, 8586, 8582, 8545, 8626, 8506) with different numbers; result: 40 of 63
  live runner entries bought the top.
- **Stage has 3 rules with 3 statistical bars** (8626, 8506/7928, 8559); one refuses FRESH_LAUNCH, the only positive stage.
- **Coin age has 4 limits**: 72 h (trader + cheat sheet), 24 h (8627), 30 min–2 h "optimal", hunters picking 2–5 h coins.
- **~15 "proven" bars on 5 outcome definitions** (5-min net, managed −20/+400, 15-min grade, paper closes, live closes).
- **Four stop sources for one EXPRESS entry** (−8, 10, FieldManual × 1.5 cost, learned).
- The cheat sheet's AVOID is a no-op (doorBinds false) while static RETIRED lists never rotate back.
- `EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md:39` says paper success does not authorise live risk; 8572/8604/8608/8616 do exactly that.

### A4. Exits sell winners early and stop on bad marks

- Four loops, ~60 named exits, 5 sell paths, ~15 hold overlays.
- **Two sell paths skip RunnerDoctrine8584**: `executeProfitLockSell` (`Executor.kt:7515`) and `checkPartialSell`
  (`Executor.kt:10189`) still sell 60 % at +50 % (fresh-launch quick take), capital recovery at 1.5–3x, 35–65 % harvest from
  3x, a 50 % lock at ~5x, partials at +50 / +150 / +1,000 %.
- **The doctrine matches the lane name exactly**, so it misses `MOONSHOT_LUNAR` / `MOONSHOT_<mode>` and `STANDARD`.
- **Stops collapse to −15 %** across five doors (RunnerGrab's −35 % is unreachable) and **fire unconfirmed**: three "−15 %"
  stops filled at −2.5 %, −3.8 % and +1.3 %.
- About 8 ladders and 10 trails disagree; at a +400 % peak the doctrine line is +175 %, the runner trail +225 %, the
  drawdown locks ~+331 %.
- What closed live positions: stops on bad marks, CortexExit forecasts (−4.7 to −7 %), chart stop, structure break, one real
  stop. No ladder, trail or doctrine exit ever fired; every hold was under 5 minutes.

### A5. The measuring stick is broken

- Every label is a **5-minute** snapshot — too short to judge a 5x/10x strategy.
- Only **7 %** of due label marks were priced (6,120 due, 438 priced); admitted coverage 37 %, refused 8 %.
- **Seed-basis labels** (cap ÷ supply used as the entry price) booked fresh launches at +2,600..+2,930 % and made
  PROJECT_SNIPER look like PF 3.3 (PF 0.31 without them). Fixed for new labels in 8627; the learned books still hold the old ones.
- Every learned door, proof bar and pocket was fitted on this data.

### A6. Sizing and cost

- All live buys were 0.035 SOL (route minimum) from a 0.146 SOL wallet. Round trip costs ~2.8 % model / 4–9 % real at this
  size (fixed legs 1.7 % alone). A strategy needs a large average win to beat that; small scalps cannot.

### A7. Runtime

- Heap 511/512 MB (100 %), repeated OutOfMemoryError (8622), 6.3 s average cycle, 202,879 refused DexScreener fetches.
  New coins wait several cycles for data; labels go unpriced.

---

## PART B — BUILD INSTRUCTIONS

Build in this order. Each step lists the goal, what to change, what to delete, the test, and the number that proves it.
One step per build (or per small group of builds). Do not start a step until the previous step's acceptance number holds on
a real runtime log. **Every rule below lives in exactly one place; no other component may re-implement it.**

### STEP 0 — Fix the measuring stick (prerequisite for everything else)

1. **Heap**: fix the remaining OOM sources (census `unattributedMb=360`); target heap < 80 % over 4 h.
2. **Outcome = one definition**: for every meme decision record, from **traded prices only**, the peak and the net at
   **5 min, 15 min, 60 min**, plus the exit the unified exit (Step 4) would have taken ("managed outcome"). New class
   `MemeOutcome` written by `ForwardReturnLabeler7731`; the dataset export carries all four numbers.
3. **Coverage**: horizon pricer (8583) must reach ≥ 50 % priced at 5 min and ≥ 50 % at 60 min for admitted **and** refused rows.
4. **Purge seed-basis rows** from every learned book (pocket ledger, edge model, setup records, stage records, cell stats):
   drop rows whose basis source was `PUMP_FUN_BC_SYNTHETIC` / `CHAIN_SUPPLY_CAP_SEED`, or whose net sits in the 27–30x cluster
   with no traded basis. Keep learning otherwise (do not reset).
- **Accept**: heap < 80 %; `labelCoverage` 5m ≥ 50 % and 60m ≥ 50 % on admitted and refused; zero seed-basis rows in books.

### STEP 1 — Discovery offers fresh, real candidates

1. `GlobalTradeRegistry.kt:416`: `addedAt` is the **first** admission and never changes; add `lastSightedAt`. Fresh tier =
   first admission < 120 s **and** (witnessed create **or** chain age under the lane's age cap). Evict by `lastEvaluatedAt`
   with no new evidence. Re-select a coin only when its evidence changed (`FeatureRevision8283`, stage change, price move > X %).
2. **Fresh-launch fast lane**: trigger on `FreshLaunchIntake8303.freshCreate8303` (witnessed create), not estimated liquidity
   (`BotService.kt:11149`). Exempt witnessed creates from the 35 % pump share. Re-evaluate at +15 s and +30 s. Create throttle
   stays at 10 SOL whatever the list size.
3. **Runner-lane scope** in `laneEvaluationAllowed7951` (`BotService.kt:15130+`): SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER,
   MANIPULATED do not evaluate a coin above the lane card's age cap (Step 2). Rows hunted for QUALITY / BLUECHIP / CYCLIC /
   DIP_HUNTER / TREASURY / CASHGEN never reach runner lanes.
4. **Hunters**: runner-lane hunters draw only from recent lists, new pools and creates, rows < 2 min old, with the lane card's
   chase filter. Momentum "leaders" leave `mustInclude` until their own forward cell is positive.
5. **Honest source labels**: one reader per endpoint with its true name (DexScreener profiles, DexScreener boosts, Meteora,
   Raydium, pump.fun, PumpPortal). Aliases count as one source for confirmation and learning.
6. **Source throttle at emit**: each source's emission rate is capped by its forward record (Step 5 book).
7. Held / high-touch coins go to the exit supervisor only; drop the boot re-seed as candidates.
8. Selector order inside each tier: the Step 5 evidence score for the coin + stage prior, not liquidity/size; PEAK and DUMP
   coins move to a **reclaim watch** list re-evaluated only on a reclaim trigger.
- **Accept**: PEAK+DUMP share of meme evaluations < 35 % (now ~75 %); new-coin share > 60 % (now 26 %); sniper in-window >
  past-window; median launch-to-first-decision for creates < 20 s with flow present.

### STEP 2 — One lane card (merges cheat sheets, field guides, token cheat sheets, trader constants)

Create `engine/truth/LaneCard.kt`: one immutable record per lane, the **only** place these numbers live.

| Field | Merges (delete the duplicates) |
|---|---|
| age band (min/max) | ShitCoinTraderAI 12 h / 72 h, LaneCheatSheet8323 mandate + `ageBandMin8323`, `RUNNER_MAX_AGE_MS_8627`, trader "optimal" windows |
| mcap / liquidity band, venues | trader constants, cheat-sheet bands, hunter bands, envelope 8383 |
| allowed lifecycle stages | `LANE_STAGE_SHEET_7928`, `LIVE_STAGE_LOSES_8626`, `entryWait8506`, 8545/8559 |
| **one** chase rule | the seven chase definitions → one: price ≥ 0.97 of peak after a ≥ 5 % run = AT_HIGH (the 8577 export: n119 −26.9 % PF 0.02). Drop the bp ≥ 60 clause (contradicted by the pocket ledger). |
| setup menu | LanePlaybook7907 MENU, MemeStrategies8546 families, TradePlan reads — as `Setup{predicate, invalidation, target, horizon}` |
| stop family, target family, horizon | FieldManual mandates, StopAuthority7887 bases, trader TP/SL, ExitProfile7955 priors |
| cost model reference | one: CostLedger7962 measured median, else NetEdge model |

Suggested starting values (tune only through Step 5's protocol): SHITCOIN age ≤ 6 h; MOONSHOT ≤ 3 h; EXPRESS ≤ 1 h;
PROJECT_SNIPER ≤ 3 min from create; MANIPULATED ≤ 12 min; all runner lanes: stages LAUNCH / BASE / BREAKOUT / PULLBACK only.

Hunters, merge-queue routing, the selector, admission and exits all **read** the card; none keeps its own copy.
`docs/FIELD_MANUAL.md`, `LANE_CHEAT_SHEETS_8323.md` and `EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md` are regenerated from the card
(one doc, `docs/LANE_CARDS.md`), so docs and code can never disagree again.
- **Test**: a unit test asserts no other file defines an age/mcap/stage/chase/stop constant for a meme lane (grep guard in CI).
- **Accept**: one value per lane per field across code and docs.

### STEP 3 — One entry decision: `MemeEntryPolicy.decide()`

One pure function per (mint, lane, candidate version), sealed into the ticket:
`decide(candidate, laneCard, evidence) → REFUSE(class, reason) | WAIT(reason, recheckAfter) | ADMIT(setup, plan, size)`.
Same function in paper and live; mode only changes sizing.

| Layer | Rule | Binds |
|---|---|---|
| L0 execution minimum | identity, fresh executable price, cost known at size, route, wallet, kill switch, duplicate, own-loss lockout, hard safety (freeze/mint auth/rug cluster/sellability), structural risk — **run once** | yes |
| L1 market switch | 45-min priced executable mean below −6 % (coverage ≥ 50 %, no "rising" escape) → pause live meme entries except Step-5-proven keys; paper continues | yes |
| L2 lane card | age, cap/liq, venue, allowed stage, chase rule | yes |
| L3 setup trigger | exactly one named setup fires, with invalidation and target; **no trigger → WAIT**; no default plan | yes (WAIT) |
| L4 economics | setup's expected gross ≥ 1.5 × round trip at the actual size | yes |
| L5 evidence | refuses only a **proven-losing** key; a **proven-positive** key may trade without a named setup and may override the chase rule for that key; sizes × 0.5–2 | partial |
| L6 sizing | (stop + cost) × size ≤ lane risk budget; capped by depth/curve/wallet share; × L5 multiplier. Below route minimum and not proven positive → REFUSE (never round up on no edge) | yes |
| L7 send-time | price drift ≤ lane limit, stage not flipped to DUMP/RUG, quote within economics margin; a failure **re-runs decide()** | re-decide |

**Delete** (logic moves into a layer above): `laneDoor8105`; the live/paper split in `decide8136`; LaneAuthority8577's
advisory transform (its binding list becomes L0); LiveEdgeGate7877 doors other than rebuy/early-launch/blind (→ L0) ;
the duplicate downstream re-judges (TradeAuthorizer + Executor ENTRY_8506, second FlatChart, CHOKEPOINT_7742, second rebuy
check); `edge8114Live8538` / `unprovenLive8536`; the dead live branches in `decide8136`; the legacy FDG soft gates
(WR_ROLL50, SELECTION_QUALITY_FLOOR, INVENTORY_PRESSURE, RSI_OVERBOUGHT, UNKNOWN_PHASE, EDGE_VETO, LIVE_QUALITY_TOO_LOW,
NEGATIVE_EV); `memeEntryRefusal8626` / `memeBuysTheHigh8582` / `PAPER_LANE_PROVEN_LOSING_8608` / 8609 / 8610 as separate rules
(they become L2 card fields and L5 keys); the V3 score floor as a gate (V3 becomes an L5 feature). FieldManual, Council,
Cortex, EdgeModel, paper lane verdicts, CellAllocator: **report-only**, filed on the forward record, never decide.
- **Test**: every live buy carries one `decide()` verdict id; a CI guard fails if any other class can refuse a meme buy
  except L0/L7 invariants.
- **Accept**: admitted vs refused 60-min managed outcome separate by ≥ 1 se on a real log (today: no separation).

### STEP 4 — One exit function: `MemeExit.decide()`

Evaluated once per position in the 500 ms loop; every other loop calls it or nothing. Keyed on the **immutable entry lane**
(folded: `MOONSHOT_*` → MOONSHOT; a STANDARD relabel keeps its entry lane). Any meme sell whose reason did not come from
`MemeExit` or the safety list is refused and counted.

- **STOP**: S = clamp(max(lane stop, 1.5 × measured round trip, round trip + 2 × 1-min range), 15 %, 25 %); armed after 45 s;
  sells **only on confirmation** (fresh executable held-exit quote at/below the level, or two independent marks ≥ 2 s apart).
- **CATASTROPHE**: −(S + 15) on any chain mark, immediately (lifecycle/MOONSHOT holds: −40 %).
- **TRAIL** (sells the whole remainder): arms at a confirmed peak ≥ +25 %; below 2x keep half the peak gain (≥ break-even
  + 1 %); from 2x a 45 % give-back of the multiple, never under 1.5x.
- **LADDER**: +100 % sells 30 %, +400 % sells 35 % of the remainder, +900 % sells 35 % of the remainder (optionally one more
  rung at ≥ 50x sells 50 %). Nothing sells for profit before +100 %.
- **CLOCK**: confirmed peak < +25 % after T minutes (lane card, default 30) and gross < break-even + 1 % → exit.
- **SAFETY (bypasses everything)**: rug, LP pull, liquidity collapse, dev dump/sell, honeypot, freeze, mint authority, Mayhem,
  manual, kill switch, shutdown, reconciler/orphan/dust, capital rotation when nothing can be funded.

**Delete / telemetry-only for meme lanes**: RAPID_* profit exits, TICK_PROFIT_LOCK, PEAK_CAPTURE_*, PeakDrawdownLock,
GreenFloor/RunnerTrail, `executeProfitLockSell` profit branches (quick take, ultra bank, harvest, capital recovery,
PROFIT_LOCK_5X), `checkPartialSell` milestones, SpikeCapture learned tiers, MoonbagRunner, TailBag, LifecycleHold takes,
LaneExitContract8468, CortexExit forecast exits, Express/ShitCoin/Moonshot sub-trader TP/partials, all duplicate stop doors
(LANE_HARD_15, RAPID_HARD_FLOOR, STRICT_SL, etc. → STOP/CATASTROPHE), RunnerDoctrine8584 hold (its line becomes TRAIL).
- **Test**: a position simulator test replays price paths (1983/8096-style runners, rugs, chop) through `MemeExit` only.
- **Accept**: no profit sale below +100 % on meme lanes; ≥ 90 % of stops filled within 5 points of their trigger; at least
  one ladder/trail exit recorded on runners in a real log.

### STEP 5 — One evidence book (the only learner that may change decisions)

- Keyed (lane, setup, stage, timing band, a few banded features); built from PocketLedger8610 cells + playbook setup records +
  stage/cell stats. **One outcome**: Step 0's 60-min managed outcome, priced rows only, weighted by coverage.
- **One bar everywhere**: proven positive = n ≥ 30, mean − se > 0, PF ≥ 1.3 out of sample; proven losing = n ≥ 30,
  mean + se < 0.
- **Promotion protocol**: any learned rule starts report-only; it may bind only when its refused cohort is worse than its
  admitted cohort by ≥ 1 se with n ≥ 100 refusals across **two separate sessions**; auto-demoted when the gap inverts; at most
  one rule changes binding status per build, and the build note names its forward record.
- Paper evidence may seed priors (shrunk toward zero at k = 20) but never authorises or bans a live lane.
- EdgeModel8537 stays as a challenger; Cortex stays retired from decisions until it beats the book out of sample.

### STEP 6 — Sizing that can win

- Size from the stop (L6). Proven-positive keys compound at quarter-Kelly of equity (as at 8096); unproven trades only at the
  minimum **when** the setup's expected gross clears 1.5 × the real round trip at that size, otherwise no trade.
- Report fixed-leg cost as a share of ticket on every buy; refuse when fixed legs alone exceed 1/3 of the setup's expected gross.

### STEP 7 — Delete what is left over

After Steps 3–4 are live and accepted: remove the dead code paths named above, retire their tests with
"superseded by" notes, and keep the CI guards that stop a second copy of any rule from returning.

---

## Owner actions (cannot be done in code)

1. Fund the wallet linked to the PumpPortal API key with ≥ 0.02 SOL (per-coin trade stream is DENIED; fresh launches have no
   buyer flow without it).
2. A live wallet of 0.146 SOL forces route-minimum tickets where fixed costs eat 1.7 %+ per round trip. More capital (or
   paper until Step 3/4 prove out) is needed for the runner strategy to pay.
3. Check the trade journal for the 1983-era +600 % / +1,100 % wins: real live or paper. It decides how much weight the 1983
   behaviour should get.

## Acceptance dashboard (put these lines in the Diagnosis Brief)

| Metric | Now | Target |
|---|---|---|
| Heap | 100 % | < 80 % |
| Label coverage 5m / 60m (admitted, refused) | 37 % / 8 % at 5m | ≥ 50 % / ≥ 50 % |
| PEAK+DUMP share of meme evaluations | ~75 % | < 35 % |
| New-coin share of intake | 26 % | > 60 % |
| Sniper in-window vs past-window | 1,618 vs 7,586 | in > past |
| Components that can refuse a meme buy | ~30 | L0–L7 only |
| Admitted vs refused 60-min managed outcome | −12.7 % vs −12.8 % (5m) | separated by ≥ 1 se |
| Profit sales below +100 % (meme) | many | 0 |
| Stops filled within 5 pts of trigger | ~60 % | ≥ 90 % |
| Live PF | 0.37 | > 1.3 |
