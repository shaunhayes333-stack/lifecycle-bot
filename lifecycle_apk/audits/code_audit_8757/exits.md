# Meme exit / sell path code audit — 5.0.8757 (read-only)

- **Code:** `origin/main` = `HEAD` = `916e8eb71` (5.0.8757). The working tree is clean and nothing was modified.
- **Baseline:** `lifecycle_apk/audits/audit_8629/audit_exits_now.md`, which covers code at 5.0.8627 (`2be34b02a`). There are 51 commits since then.
- **Log:** `aate_pipeline_20261115_150239_790.txt`, build 5.0.8756, LIVE.
  - Uptime was 333 s after an OOM restart, with 0 live open positions and no live sells this session.
  - Live runtime evidence is therefore thin. The paper book (MemePaperTrader8544) and the stop-slip counters carry the session evidence.
- **Paths:** relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.
- **Severity:**
  - **P0:** money-losing, or a position stuck or unprotected.
  - **P1:** wrong exits.
  - **P2:** quality.

## 0. What changed on the exit path since the 8629 audit

| Area | Change | Status of the 8629 finding |
|---|---|---|
| RunnerDoctrine8584 | It now keys on the folded entry lane (`foldLane8630` / `entryLane8630`). It now *sells*: `RUNNER_DOCTRINE_TRAIL_8630`, armed at a +25 % peak, in the 500 ms monitor. The line is widened by the lane stop below 2x (`lineWithStop8719`). The ladder is now 25/18/67 % of the remainder (`RunnerGrab7967.kt:57`). | 8629 §0.3 (lane keying) is **fixed**. 8629 §0.2 is fixed for `checkProfitLock` / `checkPartialSell`, which now return early on doctrine lanes (`Executor.kt:6783`, `:10182`). |
| Rapid monitor | PEAK_CAPTURE / RAPID_PEAK_LOCK / RAPID_DRAWDOWN are skipped for doctrine lanes (8642, `BotService.kt:12385`, `:19519`). | Partly fixed. The 1 Hz tick lock was missed (F11). |
| StopMarkConfirm8418 | Large rewrite: 8681 pool identity, 8706 unread-stop rule, 8712 breach restart, 8728 sell-time read, 8730 identity-unproven, 8756 tape confirmation. | New P0 defects (F1, F2). |
| Sub-trader floor | Now uses the lane floor plus executable confirmation (8743). | New P1 side effect (F6). |
| Paper meme book | Second-mark stop (8683), stop latency (8699), exits run on every tape print (8756), stop-slip ledger (8756). | New P1 valuation bias (F14). |
| PaperMirror8757 | Live MOONSHOT now buys what the paper MOONSHOT book admits. | Its exits diverge from the paper book's (F15). |
| Still open from 8629 | LifecycleHold vs doctrine deadlock (now broader, F4); doctrine checked before LaneExitContract8468; tick one-strike on exact lane names; QUICK_RUNNER full exits on non-doctrine lanes; several ladders on non-doctrine lanes; RunnerGrab -35 % unreachable. | See §3. |

## 1. Ranked findings (top 20)

| # | Sev | Where | Defect | Fix |
|---|---|---|---|---|
| F1 | **P0** | `engine/StopMarkConfirm8418.kt:396`, `:427`; `engine/truth/ExitQuoteCorroboration7928.kt:85` | The sell-time "no route quote" check reads a wait clock that the request path has just deleted. A live price-loss stop with no Jupiter quote and no venue read is held (SIGNAL_INVALIDATED) every time, until the mark reaches -60 %. P2 `doSell` stops (STRICT_SL, LANE_HARD_15, CATASTROPHIC_HARD_BACKSTOP) never start the clock, so they are always held. | When the request path fires, keep the ask in a `firedUnread` map that `sellsWithoutRouteQuote8706` reads. Do not set `first = nowMs` when no ask exists: in that case treat the request path's verdict as final, or run `holdForVenueRead8631` from `doSell` too. |
| F2 | **P0** | `engine/StopMarkConfirm8418.kt:362-366`, `:383-388`; `engine/truth/ProtectiveExitScheduler6450.kt:455` | The unread-stop wait has no upper bound: an off-venue mark is "never a sell" above -60 %. This reintroduces the hole 8419 closed ("held a -15 % / -25 % stop until -60 %"). The 8712 breach restart also resets the 6 s wait whenever two asks are more than 3 s apart. The risk clock redispatches every 30 s and the bot cycle averages 7.8 s, so their stops can **never** pass the wait, even on an on-venue mark. | Bound the unread hold: fire after N s, or at the stop depth + 10 pt. Measure the breach gap per *mark* (price back above the line), not per *ask*. Count any loop's ask for the position. |
| F3 | **P0** | `engine/BotService.kt:19487` (before `:19568` / `:19575`); `:1677-1689` | `doctrineTrail8630` runs before RAPID_CATASTROPHE / RAPID_HARD_FLOOR and returns `true` even when its request was coalesced or held. For a doctrine position whose peak reached +25 %, every tick below its line sends a *non-emergency* TRAIL request. The catastrophe and hard floor are never evaluated, so the emergency pool, 2 s retry and holds bypass are lost. Only DEEP_CATASTROPHE_NET (-30 %, after 60 s) still runs. | Evaluate the catastrophe and hard floor first. Fire the trail only while pnl > -hardFloor. Return `true` only when a request was actually dispatched. |
| F4 | **P1** | `engine/LifecycleHold8065.kt:317-325`, `:301-305`; `engine/SpikeCapture7943.kt:241`; `engine/RunnerDoctrine8584.kt:117-127` | The doctrine ladder dies at its first rung. Any live position whose verified peak reaches +100 % is promoted to a lifecycle hold. From then on SpikeCapture returns 0, and the partial door refuses rungs (`partialHeld8065`). The hold's own `LIFECYCLE_PROFIT_TAKE*` slices are then held by the doctrine, because now (2.0x) is above the line (1.5x). The +100/+400/+900 % rungs never sell on promoted positions. 8629 #4 reported this for MOONSHOT only; it now applies to every doctrine lane. | On doctrine lanes, skip LifecycleHold promotion, or add `LIFECYCLE_PROFIT_TAKE` to the doctrine pass list and let TIERS_7967 be the only ladder. |
| F5 | **P1** | `engine/Executor.kt:24401-24412`; `engine/LiveStrategyTuner.kt:44` | Every arbiter "full" exit (fraction 1.0) goes through the partial door's below-breakeven guard, whose floor is ≥ 8 %. Exits whose names lack STOP/RISK/PROFIT/CAPTURE/RUNNER/LOCK are dropped whenever pnl < +8 %: `SCALP_TIME_8351`, `STRUCTURE_BREAK_7962_*`, `LIFECYCLE_GREEN_FLOOR_8363_*`. Such an exit can never land on a flat or red position. The branch `pnl <= -15 → PARTIAL_LADDER_COLLAPSED_RISK_EXIT` is **unreachable**, because the floor check (8 %) returns first. | For `pct ≥ 1`, route to `requestSell` before any partial-only guard. Put the -15 % collapse before the floor check. |
| F6 | **P1** | `engine/BotService.kt:12594-12597` | The sub-trader sweep calls `executableConfirms8419()` before testing `pnl <= floor`, for every sub-trader position at about 1 Hz. This has side effects: it primes and resets `awaitSince8419[mint\|entryTime]`, which the real rapid stop shares, and it requests Jupiter quotes and OnChainDepth reads for healthy positions. As a result a real stop's 5 s confirmation wait is cut to a random 0-5 s, and the quote budget burns (`MARK_FANOUT_RETRYABLE_SATURATION_8527=212` in the log). | Short-circuit: `pnl <= floor && (paper \|\| executableConfirms8419(...))`. |
| F7 | **P1** | `engine/BotService.kt:11583`, `:11819`, `:11981`, `:12009`, `:12533`, `:12602` | The 500 ms monitor still sells inline: STALE_PRICE_RUG_ESCAPE, STALE_LIVE_PRICE_RUG_ESCAPE, PAPER_CATASTROPHE, ZOMBIE_CATASTROPHE, RAPID_INSTANT_PROFIT_CAPTURE (a synchronous live partial) and RAPID_SUB_TRADER_HARD_FLOOR. The monitor runs on `hotPathDispatcher7807`, which is not a supervisor thread, so `requestSell` runs on the caller thread. One rug escape blocks every other position's stops for the length of a live sell. The sub-trader floor also re-requests every second with no coalescing, duplicating RAPID_HARD_FLOOR on the same TokenState. | Route all of them through `requestSellOffLoop7288`, as 8008 did for the others. Drop the sub-trader floor for mints that are in `status.tokens`. |
| F8 | **P1** | `engine/BotService.kt:11888`; `engine/truth/TokenFacts.kt:208-220`; `engine/StopMarkConfirm8418.kt:41-54` | The danger-zone refresh writes a DexScreener/Birdeye *aggregate* price (`DANGER_ZONE_FRESH`) without a pool. `lastPricePoolAddr` keeps the previous mark's pool, so `onBasis8418` sees `markPool == entryPool` and treats the oracle price as on-venue. The rapid fluid stop (`confirmed8418`) and the unread rule then fire on an aggregator print with no confirmation. ORACLE_FALLBACK is already excluded; DANGER_ZONE is not. | Clear or sentinel the pool when an oracle price is written, and add `DANGER_ZONE` to the off-basis words. |
| F9 | **P1** | `engine/BotService.kt:1677-1689`; `engine/RunnerDoctrine8584.kt:68-71` | The doctrine trail reads raw `ts.position.peakGainPct`. The 8642 comment records stored phantom peaks, e.g. "+12,105 %, realised -3 %". Such a peak puts the line at about 67x, so the trail sells the whole position at once. TRAIL is not a `priceLossStop8489`, so the breach also needs no StopMarkConfirm / quote check. It passes LifecycleHold, RunnerGrab, NetEdge and moonbagGate, so one bad mark at or below the line sells a runner whole. | Arm and price the trail on the confirmed peak (`min(g, chain-verified peak)`, as LifecycleHold does). Require the venue-read / two-mark confirmation for the breach. |
| F10 | **P1** | `engine/RunnerDoctrine8584.kt:86-90`; `engine/truth/FieldManual7715.kt:247-248`, `:265-267` | The line jumps at 2x. Below 2x the line is `min(half, peak × (1 − stop))`; from 2x it is `max(1.5, 0.55 × peak)`. Runner-lane stops are widened by cost up to 40 % (MOONSHOT measured round trip ≈ 19 % → stop 28.5 %). At 1.99x the line is about +42 % with a 28.5 % stop, or +19 % with a 40 % stop; at 2.00x it is +50 %. A position at +45 % whose peak ticks to 2x is sold at once. The comment's claim that "the two meet at 2x" holds only for stop ≤ 25 %. | Make the line continuous, e.g. for peak ≥ 2x use `max(0.55 × peak, min(1.5, peak × (1 − stop)))`. |
| F11 | **P1** | `engine/BotService.kt:13440-13530` | 8642 skipped PEAK_CAPTURE, RAPID_PEAK_LOCK and RAPID_DRAWDOWN for doctrine lanes, but the 1 Hz `TICK_PROFIT_LOCK` / `TICK_PROFIT_LOCK_GAPPED` was missed. It arms on +8..+25 % peaks, where the doctrine trail is not armed. GAPPED sells the whole position on one tick mark at pnl ≤ 0. The name contains PROFIT/LOCK, so there is no venue or quote check. This is the 8622 984e63 pattern (lock "-5 %", fill +11 %), and it still applies on doctrine lanes. | Skip the tick lock when `profitTakingOwned8630(entryLane8630(ts))`, as at `:12385`. |
| F12 | **P1** | `engine/Executor.kt:23150-23210` (vs `:3624-3637`) | The finality resume (`resumeLiveSellFinalization7362`, reached via `LANDED_SELL_REMAINDER` and reconciler zero-close) books a landed **partial** but never calls `RunnerLive8153.bookSlice8153`, `LifecycleHold8065.onConfirmedTake8076` or `noteSliceFill8136`. It also does not update `partialSoldPct`. The rung stays unconfirmed, the pending tranche expires as retryable, and the same rung or lifecycle take is sold again (double sell). The share budget reads a stale `partialSoldPct`. | Call the same post-commit hook as `commitVerifiedLiveSlice7835`, with the original trigger reason carried on the sig, and set `partialSoldPct` from canonical. |
| F13 | **P1** | `engine/MoonbagRunner7322.kt:128-136`, `:106-110`; `engine/BotService.kt:12524-12540` | `nextCaptureFraction` latches the tier *before* the sale, and the caller skips it on `!applied`, so a refused or failed RAPID TP rung is lost. Both maps are in memory and are cleared wholesale at 500 entries; entries are never removed on close. After a clear, or after a restart (OOM restarts are frequent), taken tiers sell again and `bankedPeak` is forgotten, so MOONBAG_BANK banks a second 60 %. | Commit the tier on a confirmed fill. Key the state by positionId and store it in canonical. Evict closed positions instead of calling `clear()`. |
| F14 | **P1** | `engine/truth/MemePaperTrader8544.kt:284`, `:288`, `:346`, `:437` | Paper LOST_MARK flatters the book. A *pending* STOP whose coin stops printing is rewritten to LOST_MARK, and the remainder is valued at 0 % (entry). A rug that printed once below the stop and then went dark (still awaiting its second mark) closes flat. LOST_MARK closes are also left out of the lane proof stats. Log: MOONSHOT LOST_MARK n112 mean +7.8 % pnl **+0.878 SOL** (34 % of MOONSHOT closes); EXPRESS 103, SHITCOIN 112. This book now drives live MOONSHOT entries (F15). | Fill a pending stop at its trigger mark. Value a lost-mark remainder at the last mark, or at −100 % when the last read was below the stop. Count LOST_MARK in the lane proof. |
| F15 | **P1** | `engine/truth/PaperMirror8757.kt` (whole file); `engine/RunnerDoctrine8584.kt:134`; `engine/truth/MemePaperTrader8544.kt:289-309` | The mirror's premise that live does what paper does is false on exits. Paper MOONSHOT has no ~15 % hard floor (stop = cost-widened `stopPct8392`, ~28-40 %; stop-slip log shows levels around -35 %). Its trail arms at a **+10 %** peak (`paperStopLevel8584`), while live arms at +25 %. Paper has no LifecycleHold, CortexExit or tick one-strike. Paper's edge is TRAIL_STOP (n98, +36 %); live will cut those coins at -15 % first. The -0.1 SOL loss stop window lives in memory (`startMs8757`) and restarts with every process, so with frequent OOM restarts the cap is per process, not total. | Run the paper book with the live stop stack, or the live MOONSHOT mirror with the paper exits. Keep the mirror's loss window in canonical storage. |
| F16 | P2 | `engine/truth/MemePaperTrader8544.kt:279-286`, `:296-305` | The paper second-mark rule also applies to *trails*, and a pending TAKE_PROFIT (16 s latency) blocks stop evaluation for that time. Stop slip in the log: SHITCOIN level +23 % → trigger -49 % → fill -49 %; EXPRESS +9 → -18. | Allow one pending exit per kind, with a stop/trail pre-empting a pending TP. Apply the two-mark rule to loss stops only. |
| F17 | P2 | `engine/Executor.kt:24260-24275` | `budget8136` takes the arbiter ticket *before* the TailBag / LifecycleHold / below-breakeven refusals, so a refused tranche holds the position's ticket for its grace. The `pct <= 0` path never calls the receipt. | Run every refusal before `budget8136`, or release the ticket on refusal. |
| F18 | P2 | `engine/StopMarkConfirm8418.kt:149-195` vs `:390-410`; `engine/truth/ExitQuoteCorroboration7928.kt` | There are three stacked waits on one stop, each with its own key: the rapid path's 5 s (mint\|entryTime), the request path's 6 s + breach restart (positionId), and the sell-time quote (emergency budget). A real on-venue dump waits 11 s or more before routing. The tape confirmation (8756) only exists in the rapid path. | Use one confirmation authority with one key per position. Add the tape leg to `stopRefutedByVenue8489`. |
| F19 | P2 | `engine/Executor.kt:25101-25108`; `engine/BotService.kt:13396-13405`; `engine/Executor.kt:9193` | Still open from 8629: (a) moonbagGate checks the doctrine before LaneExitContract8468; (b) the tick one-strike matches exact lane names (`MOONSHOT_<X>` falls into two-strike); (c) `QUICK_RUNNER_10X_FULL_EXIT` / `6X_BANK_95PCT` are full `doSell`s on non-doctrine lanes; (d) non-doctrine lanes still carry about 6 ladders (RAPID TP 25 %, SpikeCapture TIERS 60/70 %, ExitProfile7955 learned, checkProfitLock, checkPartialSell, QUICK_RUNNER). | As in 8629 §4. |
| F20 | P2 | `engine/Executor.kt:23624-23630`; `engine/BotService.kt:11406-11421` | StopSlip8756 records a live fill only when P1 returns CONFIRMED. The off-supervisor path returns FAILED_RETRYABLE while the sell still lands, and P2 `doSell` stops are never measured, so `live{-}` stays empty. `resolveStaleFallbackPrice` makes blocking HTTP calls inside the hot monitor. | Record the fill from the finalizer (by trigger key). Move the oracle reads off the hot path. |

## 2. Details and evidence

### F1: the unread-stop clock is deleted before the sell-time check (P0)

The order of events for a live loss stop when no venue reserve read and no executable quote exist:

1. `requestSellCore7948` calls `stopRefutedByVenue8489`. `venuePnlPct8489` is null and `executablePnlSinceAsk8681` is null, so it goes to `holdForVenueRead8631`.
   - For the first 6 s it holds (FAILED_RETRYABLE).
   - After 6 s, if the mark is on-venue, `fires = true`, and then **`if (fires) noReadSince8631.remove(key)`** (`:396`). The request proceeds to `doSell`.
2. `doSell` calls `freshExitDecision8521`, then `stopCorroboratedByQuote7928`, then `ExitQuoteCorroboration7928.allowSell`.
   - The emergency path bounds the Jupiter quote by `StopFast8288.STOP_QUOTE_CHECK_BUDGET_MS_8288`. A slow, no-route or erroring quote returns null.
   - It then calls `sellsUnquoted8706()`, i.e. `sellsWithoutRouteQuote8706`.
3. In `sellsWithoutRouteQuote8706`, `venuePnlPct8489` is null, and `executablePnlSinceAsk8681` is null because the key was just removed.
   - `first = noReadSince8631[...] ?: nowMs` (`:427`), which is **now**.
   - `unreadStopFires8706(now, now, …)` sees `0 in 0 until 6000`, so it returns false. The result is SIGNAL_INVALIDATED.
4. On the next request, `breachStart8712(first = null, …)` returns `nowMs`, so a new 6 s wait starts. The loop never ends.
   - The stop sells only once Jupiter answers in time, a reserve read appears, or the mark reaches -60 % (`BYPASS_PCT_8418`).

P2 `doSell`-direct stops (`STRICT_SL_*`, `LANE_HARD_15PCT_SL_*`, `CATASTROPHIC_HARD_BACKSTOP_-25*`) never pass through step 1, so step 3 always returns false.

This is exactly the 8728 jjaemu case, where a quote came back after the emergency check budget, but now the default outcome is *hold* rather than *sell*.

### F2: no upper bound on the unread hold, and the breach restart is keyed to asks (P0)

`unreadStopFires8706` behaves as follows:

```
markPnlPct <= -60 -> true
nowMs - firstAskMs in 0 until 6_000 -> false
else -> markOnVenue
```

An off-venue mark therefore *never* fires above -60 %. `identityUnproven8730` (`:74-79`) marks any mark that carries **no pool** as off-venue for a position filled on a real pool address, unless the source is an executable quote. If the reserve reader cannot price the venue (the concentrated / depth-only venues named in 8681) and Jupiter has no route (a dying pool), the stop is held until -60 %. This is the bound 8419 added and 8706 removed.

`breachStart8712` restarts the wait when `nowMs - lastAsk > 3 s` (`BREACH_GAP_MS_8712 = NO_READ_HOLD_MS_8631 / 2`). Two callers ask less often than that:
- the risk clock redispatches every 30 s (`ProtectiveExitScheduler6450.kt:455`);
- bot-cycle P1 stops run about every 7.8 s (`avgCycle=7847ms` in the log).

So `nowMs - first` is always 0 for them, and **those stops can never complete the wait even on an on-venue mark**. Only the 500 ms monitor's off-loop stops ask often enough.

### F3: the doctrine trail masks the catastrophe and hard floor (P0)

`evaluateRapidMonitorExit` line 19487:

```kotlin
if (doctrineTrail8630(ts, pnlPct, peakGainPct, wallet, effectiveBalance)) return true
```

It runs before `pnlPct <= catastropheThreshold` (`:19568`) and `pnlPct <= -HARD_FLOOR` (`:19575`).

- `trailBreached8630` is true for *any* pnl ≤ line once the peak is ≥ 25 %, including -14 % or -29 %.
- `doctrineTrail8630` always returns true. When the previous request is still in flight, `requestSellOffLoop7288` coalesces and returns without dispatching. The caller still executes `continue` (`:12092`).
- The trail reason contains TRAIL, so `ProtectiveExitClass7807.isEmergency` is false. That means the normal dispatcher, a 15 s coalesce window instead of the 2 s emergency rung, no `bypass7807`, and the tiny-profit / style-min-hold gates apply.
- The catastrophe never fires while the doctrine trail keeps asking. Only `DEEP_CATASTROPHE_NET` (`:12017`, -30 % after 60 s), which runs before this function, is left.

### F4: the doctrine ladder never sells past +100 % on promoted positions (P1)

1. `LifecycleHold8065.onMark8065:320` promotes any live position at g ≥ 100 with peak ≥ 100 (`states[k]=State(...)`). `holding8065` then stays true through "once held, always held" (`:233`), subject to `LifecycleEvidence8189`.
2. In `SpikeCapture7943.onMark:241`, `if (holding8065(ts)) return 0` means no doctrine rung.
3. In the partial door, `LifecycleHold8065.partialHeld8065` refuses `SPIKE_CAPTURE_7943_*` (it does not pass `passes8065`).
4. The hold's own take `LIFECYCLE_PROFIT_TAKE1_8065_*` (+100 %:50 %) reaches P3, where `runnerDoctrineHolds8584(…,"PARTIAL")` runs first. The reason is not NEVER_HELD, not SPIKE_CAPTURE and not tier 1, and now (2.0x) > line(2.0x) = 1.5x, so it is **held**.
5. The +400 % / +900 % rungs and lifecycle takes 2/3 are held the same way, because each sits above 0.55 × peak.

The only remaining exits are full ones: the doctrine trail at the line, the lifecycle trend break / give-back, or the floor. The log shows `runnerFunnel rungTriggered` / `SPIKE fired` at 0 across sessions (8629 §3, 8719 comment).

### F5: the partial-door guard drops full exits (P1)

Fraction-1.0 offers from the arbiter (`SpikeCapture7943.rapidMark` → `sellIntoSpike7943` → `requestPartialSellConfirmed6566`) reach `requestSell` only at `:24629`, after these refusals in order:
1. doctrine PARTIAL;
2. NetEdge partial;
3. uneconomic;
4. budget;
5. TailBag partial;
6. LifecycleHold partial;
7. `pnlPct < partialProfitFloorPct6920 (>= 8.0) && !isForcedCut` → return (no receipt).

`isForcedCut` needs one of RISK, LIQUIDITY, STOP, RUG, RECOVERY, CATASTROPHE, DRAIN, PROFIT, CAPTURE, RAPID, RUNNER or LOCK in the reason. Three exits lack them:
- `SCALP_TIME_8351` (`truth/ScalpExit8351.kt:36`);
- `STRUCTURE_BREAK_7962_*` (`chart/StructureTracker7962.kt:363`);
- `LIFECYCLE_GREEN_FLOOR_8363_*` (`LifecycleHold8065.kt:343`).

At any pnl < +8 % these are silently discarded and re-offered every 500 ms.

The `pnlPct <= -15.0 && !isForcedCut → requestSell(PARTIAL_LADDER_COLLAPSED_RISK_EXIT)` branch at `:24408` can never run: any pnl ≤ -15 % already failed the `< 8 %` test at `:24401`.

### F6: the sub-trader sweep confirms before checking pnl (P1)

```kotlin
val confirmed8743 = cfg.paperMode || (try { StopMarkConfirm8418.executableConfirms8419(ts, laneFloor8743) } ...)
if (pnlPct <= laneFloor8743 && confirmed8743) { ... }
```

`executableConfirms8419` is not pure:
- `putIfAbsent(key, now)`;
- a reserve refutation sets `awaitSince8419[key] = now`;
- `awaitExpired` removes the key after 5 s;
- it calls `OnChainDepth8038.request8038` and `requestExecutableQuote7301`.

It runs about once per second for **every** sub-trader position, healthy or not. The key `mint|entryTime` is the same one the rapid catastrophe / hard-floor confirmation uses (`BotService.kt:19564`). A real stop's "wait for the executable answer" therefore starts at a random 0-5 s age. The healthy-position reads and quotes also inflate `STOP_MARK_REFUTED_*` and compete for the Jupiter budget.

### F7: inline sells in the 500 ms monitor (P1)

The rapid monitor coroutine is `scope.launch(hotPathDispatcher7807 …)` (`BotService.kt:823`). `requestSell` leaves the caller thread only when the thread name starts with the supervisor prefix (`sell/ExitDispatchLatency7948.kt:36-37`).

The direct calls at `:11583`, `:11819`, `:11981`, `:12009`, `:12602`, and the synchronous live partial at `:12533`, each block the `for (ts in openPositions)` loop for one live sell. A sell includes quote, sign, broadcast and verify, and takes seconds on a dying pool. 8008/8327 moved the other doors off-loop for exactly this reason ("one sell never holds the other positions' stops").

### F8: an oracle print inherits the entry pool (P1)

`TokenFacts.writePrice(ts, freshDz, "DANGER_ZONE_FRESH", PROVIDER, now)` passes no `pool`, so `lastPricePoolAddr` is unchanged (`TokenFacts.kt:219`).

`onBasis8418("DANGER_ZONE_FRESH", oldPool, entryPool)` then evaluates as follows:
- the pools do not disagree;
- the identity is not unproven, because the pool is non-blank;
- the source is not in the chain list and not in the exclusion words;
- so it falls through to `entryPool == markPool` → **true** whenever the previous mark came from the entry pool.

The DexScreener best-pair or Birdeye price then confirms `RAPID_FLUID_STOP` (`confirmed8418`). It also makes `markOnVenue8706` true, so the unread rule fires on it.

### F10: line discontinuity, worked example

MOONSHOT measured cost is 19 %, so `stopPct8392 = max(15, 1.5 × 19) = 28.5` (cap 40 for runner mandates).

| Peak | Line | Gross at line |
|---|---|---|
| 1.99x | min(1.495, 1.99 × 0.715 = 1.423) = 1.423 | +42 % |
| 2.00x | 1.5 | +50 % |

With a 40 % stop (cap) the jump is +19 % → +50 %. A position at +45 % that prints a 2x peak is sold in the same tick.

### F12: the resume path books the slice but not the ladder (P1)

In `commitVerifiedLiveSlice7835` (`Executor.kt:3624-3637`) the runtime position is synced (`partialSoldPct` included), then `onConfirmedTake8076`, `noteSliceFill8136` and `bookSlice8153` run.

`resumeLiveSellFinalization7362` (`:23050-23230`) writes the journal row as `LIVE_SELL_FINALIZED_FROM_SIG_7362_partial_resume` and sets only qty and cost (`:23206-23207`). The trigger reason is lost, so no rung is confirmed.

`SpikeCapture7943` reads `RunnerLive8153.confirmedTier8153`, which is still the old tier. The pending tranche expires as retryable (`settlePending8153`) and the rung is re-requested. The log shows the resume path is live: `SELL_FINALITY_REDISPATCH_7231:93`, and the sources include reconciler zero-close and `LANDED_SELL_REMAINDER`.

### F14 / F15: paper evidence

| Lane | Exit | n | Mean | PnL |
|---|---|---|---|---|
| MOONSHOT | STOP | 116 | -23.4 % | -2.714 SOL |
| MOONSHOT | TRAIL_STOP | 98 | +36.3 % | +3.560 SOL |
| MOONSHOT | LOST_MARK | 112 | +7.8 % | **+0.878 SOL** |
| SHITCOIN | STOP | 60 | **-41.3 %** | |
| EXPRESS | STOP | 133 | -29.5 % | |
| FAM_FOLLOW_SIZE | STOP | 45 | **-63.9 %** | |
| MANIPULATED | STOP | 2 | -83.3 % | |

- Book balance is **0.097 SOL** against `STAKE_SOL_8544 = 0.1`, so `roomFor8544` returns NO_PAPER_BALANCE and the paper book can no longer open positions. The mirror still uses the paper *decision*, so its evidence base is frozen.
- Stop slip (§8756): `paper{MOONSHOT:n4 level-5 trig-28 fill-20; EXPRESS:n3 level+9 trig-18 fill-15; SHITCOIN:n2 level+23 trig-49 fill-49}`.
- The paper MOONSHOT edge comes from wide stops and +10 %-armed trails. Live runs a 15 % rapid hard floor and catastrophe (restore8298 not earned), the tick one-strike, CortexExit and a +25 % trail arm.

## 3. Still open from 8629 (verified unchanged)

- moonbagGate order: doctrine (`:25103`), then `ownExit` (`:25106`), then LaneExitContract8468 (`:25108`). The doctrine still holds Express' designed exits above its line.
- Five or more meme-lane sets:
  - doctrine: 6 lanes, folded;
  - tick one-strike: exact names, `BotService.kt:13396-13405`;
  - `LANE_HARD_15`: exact;
  - TailBag: canonical;
  - RunnerExitProfile7277: contains-match.
- `RUNNER_HARD_STOP_7967` (-35 %) is unreachable behind the ~15 % rapid catastrophe.
- QUICK_RUNNER full exits on non-doctrine lanes (`Executor.kt:9193`).
- The non-doctrine ladders listed in F19(d).

## 4. Suggested order of repair

1. F1 + F2 + F18: one StopMarkConfirm authority, one key, a bounded unread hold, and a breach gap measured on marks.
2. F3 + F9 + F10 + F11: the doctrine trail goes after safety, uses the confirmed peak and a confirmed breach, has a continuous line, and is the only sub-+25 % lock.
3. F4 + F12 + F13: one ladder per position, booked only from the confirmed fill on every finality path.
4. F5 + F17: full-fraction offers go straight to `requestSell`; partial-only guards apply only to partials.
5. F6 + F7 + F8: hot-loop hygiene (pure checks, off-loop sells, no inherited pool on oracle prints).
6. F14 + F15: fix paper valuation before it is used as the reason to buy live.
