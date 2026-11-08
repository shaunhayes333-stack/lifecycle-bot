# Meme-position exit audit: 5.0.8627 (read-only)

**Scope.** Code at `HEAD 2be34b02a` (5.0.8627). `origin/main` has since moved to 5.0.8628 (`0f31c8eca`). That commit adds LaunchHour8628 and changes EdgeModel/labeler/admission/UI files. It does not touch any exit file.
**Log.** The log is from build **5.0.8622** (`d2130b3f6`). Between 8622 and 8627 the only engine changes are to Cortex7885, CortexForwardEpoch8521, CandidateAdmission8136, ExploreShadow8347, ForwardReturnLabeler7731, LaneAuthority8577 and MemePaperTrader8544. Those cover entry, labels and the paper book. **Live exit logic is identical in the logged build and in 8627.**
All paths below are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.

---

## 0. Findings in one page

1. **No single exit model exists.** A meme position is checked by **4 loops**:
   - the 500 ms rapid monitor;
   - the 1 Hz tick loop;
   - the bot-cycle `runManageOnly` / `maybeAct`;
   - the risk clock.

   These loops can fire **about 60 named exits**, sent through **5 different sell paths**. Each sell path applies a different set of about **15 hold/veto overlays**.
2. **RunnerDoctrine8584 is a hold rule, not a sell rule, and three sell paths never consult it.** `Executor.executeProfitLockSell` and `Executor.checkPartialSell`'s own route do not consult it. Those paths carry capital recovery (1.5–3x), wallet-growth harvest (35–65 % at ≥3x), PROFIT_LOCK (50 % at ~5x), the fresh-launch quick take (60 % at +50 %, 75 % at +100 %), the protective 25 % at +500 %, the ultra bank (80–95 % at 50x) and the WR-recovery partials (+50/+150/+1000 %). They sell a meme winner on ladders that contradict the doctrine ladder (+100 %:30 / +400 %:35 / +900 %:35).
3. **The doctrine matches lanes on the exact raw `tradingMode`.** It therefore misses every `MOONSHOT_<SPACEMODE>` position (`BotService.kt:29886, 32649, 32765, 33287`; `CanonicalLaneIdentity6506` does not fold these). It also misses positions whose `tradingMode` reads `STANDARD`. The one green live trade this session (984e63, entry lane SHITCOIN) ran as `STANDARD`, and a +8 % profit lock sold it.
4. **The doctrine fights other doors.**
   - It holds LifecycleHold8065's own takes, and LifecycleHold blocks the spike rungs. A lifecycle-held MOONSHOT therefore has no ladder at all.
   - It holds TailBag milestones and Express' designed exits, which overrides LaneExitContract8468.
   - It holds `RUNNER_GREEN_FLOOR` and `RUNNER_STRUCTURE_BREAK` above its line.
5. **Stops: five nominally different stops all sit at about -15 % on meme lanes.** These are the ordinary stop, the rapid hard floor, the rapid catastrophe, LANE_HARD_15 and the tick one-strike. They fire on single marks.
   - This session's stops filled at **-2.5 %** (RAPID_CATASTROPHE_STOP), **-3.8 %** (LANE_HARD_15PCT_SL) and **+1.3 %** (PROTECTIVE_EXIT_STOP_LOSS).
   - A "-5 %" profit lock filled at **+11 %**.
6. **Why `held=0`:**
   - No meme position this session was above the doctrine line (≥ +5 %) when a profit exit asked. The best peak was +8 %, on a STANDARD-labelled position. The mean MFE was -2.5 %, and the 8 tracked runner positions were all UNDER_BREAKEVEN.
   - The counter is in memory and was reset by the OOM restart.
   - Even with winners, the doctrine sits behind other holds and is bypassed by the profit-lock and partial paths.
   - By construction it can never prevent a LOSS_GAVE_BACK: it never sells, and it holds nothing at or below +5 %.
7. **The bigger loss source is entry, not exit.** Of 63 live runner admits, 40 never reached +5 % MFE (LOSS_BOUGHT_TOP), 10 gave back and 13 won. The forward labels show that of 84 EXPRESS labels stopped before their peak, only 7 ran on afterwards. A wider stop would not have rescued many.

---

## 1. Every exit door for a meme position

### 1.0 Evaluation loops and sell paths

| Loop | Entry point | Cadence |
|---|---|---|
| **A. Rapid monitor** | `BotService.rapidStopLossMonitor` `engine/BotService.kt:11412` | 500 ms, every open position |
| **B. Open-position tick** | `BotService.openPositionTickLoop` `engine/BotService.kt:12717` (exits at 13380–13700) | ~1 Hz |
| **C. Bot cycle / manage** | `Executor.runManageOnly` `engine/Executor.kt:8687` (called from `BotService.kt:3418`); `Executor.maybeAct` `:12231` / `maybeActWithDecision` (riskCheck at `:12311`, `:12982`; hold layer at `:12870-12904`); lane sub-trader handlers in BotService (`:1331`, `:31054`, `:32820`, `:32895`, `:32968`, `:33392`) | per cycle (avgCycle 6.3 s in the log) |
| **D. Risk clock** | `BotService.kt:7213-7330` → `ProtectiveExitScheduler6450.evaluate` (`engine/truth/ProtectiveExitScheduler6450.kt:240-315`) → reason `PROTECTIVE_EXIT_<KIND>_6450_RISKCLOCK` (`BotService.kt:34052`) | clock |

| Sell path | Where | Hold/veto gates applied, in order |
|---|---|---|
| **P1 `requestSell` → `requestSellCore7948`** | `Executor.kt:23609`, `:23717` | retire-if-closed → FrozenHeld → **LifecycleHold8065.defer8065** (:23723) → **LaunchWindow8379** (:23729; switched off, `LaunchWindow8379.kt` header) → **RunnerGrab7967.deferSell7967** (:23737) → BasisBreak8019 (:23743) → **StopMarkConfirm venue refute** (:23750) → dust latch → **NetEdge8030** (:23762) → canonical-existence gate → **moonbagGate7322** (:23921, live only) → tiny-profit dust deferral (:23923) → style min-hold (:23943) |
| **P2 `doSell`** (direct, used by STRICT_SL, LANE_HARD_15, QUICK_RUNNER, SWEEP_*, riskCheck, CATASTROPHIC_HARD_BACKSTOP…) | `Executor.kt:25261` | **CortexExit7897.holdVeto** + MissingMarkExitVeto6835 + quote corroboration 7928 (`freshExitReasonOrVeto7948` :25237) → LifecycleHold8065 (:25271) → LaunchWindow8379 (:25276) → **moonbagGate7322** (live :25462 / paper :25442). It has *no* RunnerGrab defer, NetEdge, venue refute or style/dust holds. |
| **P3 partial door `requestPartialSellWithDelivery8527`** (every partial and every rapidMark offer, including fraction 1.0) | `Executor.kt:24213` | **RunnerDoctrine8584 (PARTIAL)** (:24226) → NetEdge8030 partial (:24228) → uneconomic 8094 (:24235) → **RunnerExitLive8136.budget8136** share budget + arbiter ticket (:24243) → **TailBag8034.partialHeld** (:24246) → **LifecycleHold8065.partialHeld** (:24252) |
| **P4 `executeProfitLockSell`** (all of `checkProfitLock`) | `Executor.kt:7515` | only `partialUneconomic8094` (:7522). **No doctrine, NetEdge, Lifecycle or TailBag check.** |
| **P5 `checkPartialSell` live route** | `Executor.kt:10189`, own route stack at `:10700` | only `partialUneconomic8094` (:10333) and the break-even floor 6920 (:10299). **No doctrine.** |

**`moonbagGate7322`** (`Executor.kt:25081-25131`) runs these checks in order:
1. Tier-1 → pass (:25084)
2. **RunnerDoctrine8584 FULL** (:25085)
3. LaneExitContract8468 → sell whole (:25087)
4. **TailBag8034** set-aside / hold / milestone (live, :25088 → `tailBagGate8034` :25050-25079)
5. ProtectiveExitClass7807 class 1–3 bypass (:25092)
6. **MoonbagRunner7322** bank / hold (:25101)

### 1.1 Loop A: 500 ms rapid monitor, per position, in firing order

| # | Door | Where | Trigger and numbers | Applies to | Sell path | Overridden / held by |
|---|---|---|---|---|---|---|
| A1 | **rapidMark overlays** (one arbitrated winner per tick, `ExitTruthArbiterLive8144`; ranks 1 emergency … 8 time, `ExitTruthArbiter8144.kt:33-43`) | `BotService.kt:11441` → `SpikeCapture7943.rapidMark` `SpikeCapture7943.kt:115-141` → `sellIntoSpike7943` `BotService.kt:25832` | in order: (a) **LifecycleHold8065.onMark8065**, which runs the hold's own exits and skips the rest; (b) **ScalpExit8351.offer8351** (lanes proven SCALP); (c) **SpikeCapture onMark** ladder; (d) **chartExit7950** `CHART_STOP_7950_*` / `CHART_CAPTURE_TOP_7950_*`, full, after 90 s, at most every 20 s (`SpikeCapture7943.kt:160-171`); (e) **RunnerGrab7967.heldExit7967**; (f) StructureTracker7962 (shadow) | all open positions | P3 (even fraction 1.0) | each offer is held by the P3 gates; the arbiter lets ranks 1–3 beat ladders |
| A1c | **SpikeCapture7943 ladder** | `SpikeCapture7943.kt:239-318` | tiers = `RunnerGrab7967.tiersFor7967` (`RunnerGrab7967.kt:499-506`): **doctrine lanes → TIERS_7967 = +100 %:30 %, +400 %:35 %, +900 %:35 % of current**; else the runner/scalp plan ladder, `ExitProfile7955` learned tiers (e.g. global +300:90 %, MOONSHOT\|BUYERS_DOMINATING +30:95 % in the log), else `TIERS` +40 %:60 %, +120 %:70 %, +400 %:70 % (`SpikeCapture7943.kt:20`). The mark must be authoritative (curve print for curve coins) and fresh (≤ 5 s). | not lifecycle-held (`:254`) | P3; `SPIKE_CAPTURE_7943` is exempt from the doctrine (`RunnerDoctrine8584.kt:54`) | budget8136 / arbiter ticket, TailBag partialHeld, LifecycleHold |
| A1e | **RunnerGrab7967.heldExit7967** | `RunnerGrab7967.kt:583-626` | positions with a runner basis only (sealed runner style or proven +100 % promotion, `TradeExitContract8077.kt:35-43`). After 60 s: `RUNNER_HARD_STOP_7967` at **-35 %** (waits for a quote inside the first minute above -80 %); `RUNNER_PEAK_CAPTURE_7980` after a +200 % peak at **65 % of peak price**; `RUNNER_GREEN_FLOOR_8363` at max(0.4×peak, 65 % of peak price) (`Exits8363.kt:63-91`); **`RUNNER_STRUCTURE_BREAK_7967_1M` on any 1-minute broken structure at any gain**; 15 s break if ≥ +50 % | runner-basis only, ≤ 6 h | P3 | doctrine holds GREEN_FLOOR / STRUCTURE_BREAK / PEAK_CAPTURE when above the line (none are in NEVER_HELD) |
| A2 | `STALE_PRICE_RUG_ESCAPE` | `BotService.kt:11442-11580` | no price for > 90/120 s, oracle fallback dark, plus rug evidence (route dead / balance 0) or > 15 min dark (live) | all | P1 | tier-1-ish words |
| A3 | `STALE_LIVE_PRICE_RUG_ESCAPE` | `:11590-11796` | cached mark > 90/120 s old and last-known pnl ≤ 0 (live) | all | P1 | |
| A4 | `ZOMBIE_CATASTROPHE_PENDING_RETRY` / `PAPER_CATASTROPHE_ONESHOT_6497` | `:11928-11984` | age > 30 min and pnl ≤ -30 % | all | P1 | |
| A5 | `DEEP_CATASTROPHE_NET` | `:11986-11997` | age > 60 s and pnl ≤ **-30 %** | all | P1 (off-loop) | never held (CATASTROPH) |
| A6a | `RAPID_CATASTROPHE_STOP` | `evaluateRapidMonitorExit` `BotService.kt:19467`, fires `:19561` | pnl ≤ -max(fluid(-25), hardFloor, lifecycle 40) (`ExitDoctrine8169.kt:113-118, 261-266`). Live, with the 8298 restore not earned (`restore8298 earned=none` in the log), **hardFloor = 15 → the catastrophe ≈ -15 %**. An executable-quote confirm is needed only if the mark is "off-basis"; chain/curve marks are never off-basis (`:19532-19560`). | all | P1 | never held (tier 1 / CAPITAL_PRESERVATION); beats RunnerGrab's -35 % and the 8379 window |
| A6b | `RAPID_HARD_FLOOR_STOP` | `:19568` | pnl ≤ **-15 %** (stop + 5 only on an earned lane, `ExitDoctrine8169.kt:251-258`) | all | P1 | never held |
| A6c | `RAPID_DRAWDOWN_FROM_PEAK_SETTLE_BYPASS_6080` | `:19498-19592` | peak ≥ 20, pnl > 0, not runner-deferred (MOONSHOT < +25 %, other runner lanes < +20 %, `RunnerExitProfile7277.kt` `giveBackArmPct7791`), give-back ≥ max(25 pt, peak × `PeakDrawdownLock.triggerFracForPeak` 0.40→0.08) | all | P1 | doctrine holds above the line; RunnerGrab defer; NetEdge exempts it (ProfitLock8363) |
| A7 | warm-up hold | `:12285-12303` | live runner lanes, first 40 s, once per 30 s → skips A8–A11 | runner lanes | | |
| A8 | `PEAK_CAPTURE_TRAIL/GIVEBACK_SLIP/DISTRIBUTION_6394` | `:12207-12372` | `PeakCaptureAuthority6390` full-exit verdict (e.g. +12.5 % peak → +6.7 %), unless runner-deferred | all | P1 | doctrine above the line, RunnerGrab defer |
| A9 | `RAPID_PEAK_LOCK_BREACH_4301` | `:12373-12396` | peak ≥ 20 and pnl ≤ `FluidLearningAI.fluidProfitFloor`, unless runner-deferred | all | P1 | same |
| A10 | `RAPID_FLUID_STOP` / `RAPID_TRAILING_STOP` | `:12305-12432`; `fluidStop7928` `:1044-1061` | pnl ≤ dynamic fluid stop (FluidLearningAI + `ExitDoctrine8169.runnerAwareStop8169` + `ExitProfile7955.applyTrail7955`), runner lanes at least -10 % (`:99`), widened outside the cost band (8536), positive stop on a deferred runner only at its protect floor (`ExitDoctrine8169.kt:324-330`), not when a plan owns the exit, `StopMarkConfirm8418.confirmed8418` | all | P1 | doctrine (TRAILING above the line) |
| A11 | `RAPID_INSTANT_PROFIT_CAPTURE_4301` | `:12438-12513` | pnl ≥ lane TP; **25 % per tier**, runner lanes +100/+300/+1000 %, others TP/+100/+300 (`MoonbagRunner7322.kt:117-136`) | all | P3 | **doctrine holds it above the line** (not SPIKE_CAPTURE) |
| A12 | `RAPID_SUB_TRADER_HARD_FLOOR` | `:12527-12660` | sub-trader map position pnl ≤ **-15 %** | sub-trader maps | P1 | |

### 1.2 Loop B: 1 Hz tick

| # | Door | Where | Trigger and numbers | Applies to | Path / overridden by |
|---|---|---|---|---|---|
| B1 | `TICK_CATASTROPHIC_CONFIRMED_*`, `TICK_HARD_FLOOR_CATASTROPHIC_LANE_<L>_*_4588` (one strike), `RUNNER_EARLY_CUT_*_7277`, runner floor, `MOONSHOT_LANE_STOP_LOSS_*_7389`, `TICK_HARD_FLOOR_*` (two strikes) | `BotService.kt:13439-13526`; `tickFloor7887` `:1247` = min(-10, -ordinary stop) | one strike for exact lanes MANIPULATED/SHITCOIN/EXPRESS/QUALITY/BLUECHIP/CORE at the tick floor (≈ **-15 %** on meme lanes); runner early cut **-20 % inside 2 min** (`RunnerExitProfile7277.kt` `earlyCut`); MOONSHOT `MoonshotTraderAI.stopFor` (≥ -15 % for the first 12 min, `MoonshotTraderAI.kt:1207-1226`) | all | P1; plan-owned check (`planOwnsExit7754`) |
| B2 | plan / profile / cortex exits | `planTickRead7739` `:1250-1306` | `TradePlan7739.exitFor` (structure stop, half at the first target, structure trail, time stops); `PROFILE_MAX_HOLD_7955` (pop-fade keys, not for runner-from-entry); **`CORTEX_EXIT_7897_<L>_FWD-xxPCT`** when the exit cortex reads SELL_STRONG and the lane is proven (`cortex/CortexExit7897.kt:480-489`) | all | P1; doctrine passes losses |
| B3 | `TICK_PROFIT_LOCK_peak_now` / `TICK_PROFIT_LOCK_GAPPED_peak_floor_now` | `:13536-13700` | ratcheting floor from `fluidStop7928(modeDefault 20)`, capped at BE + 1 below half the lane target × tpMult (`:1064-1069`); not armed while runner-deferred; GAPPED fires when the stored floor > 0 and now is in (-10, 0] | all; **non-runner lanes such as STANDARD arm from a small peak** | P1; doctrine (above the line) |

### 1.3 Loop C: bot cycle (`Executor.runManageOnly`, in order)

| # | Door | Where | Trigger and numbers | Path | Notes |
|---|---|---|---|---|---|
| C1 | `INSIDER_SHARK_COPY_EXIT_6092_*` | `Executor.kt:8707` | copied wallet exits | P1 | |
| C2 | `data_quality_stale_feed_evict_*` | `:8738-8835` | feed dead ≥ 10 min and held ≥ 20 min, not a runner | P2 | |
| C3 | `STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP` | `:9012-9027` | no candidate pnl for ≥ 15 s | P2 | |
| C4 | **QUICK_RUNNER** | `:9099-9219` | dual-confirmed +500 % / +1000 %: runner position → `QUICK_RUNNER_MOONBAG_BANK_7349` (35 % MOONSHOT / 60 % others, P3); **non-runner (e.g. STANDARD) → `QUICK_RUNNER_10X_FULL_EXIT` / `QUICK_RUNNER_6X_BANK_95PCT`, which is P2 doSell = 100 %** | P3 / P2 | doctrine holds both above the line (the P2 route goes through moonbagGate) |
| C5 | `LANE_HARD_15PCT_SL_<L>` | `:9232-9251` | exact lane MANIPULATED/SHITCOIN/EXPRESS and **worstPnl = min(live, cached) ≤ -15 %** | P2 | |
| C6 | `THIN_LIQ_EARLY_RUG_BACKSTOP_-10/-15` | `:9272-9298` | liq < $5k and < 10 min and ≤ -10 %, or liq < $2.5k and ≤ -15 % | P2 | |
| C7 | `CATASTROPHIC_HARD_BACKSTOP_-25*` | `:9300-9528` | worstPnl ≤ -25 %, corroborated + evidence; held-quote refute, missing-mark and basis vetoes | P2 | |
| C8 | `STRICT_SL_<x>` / `_CACHED` / `STALE_PRICE_FORCED_EXIT_*` | `:9532-9841` | after the live settle-in (lane min-hold, ≥ 30 s): stop = fluid(-ordinary) **pulled earlier by predicted slip** (ceiling -3 %), metric-aware, held at the lane stop on runner lanes (`laneHeldStop8489` `:2836`) | P2 | |
| C9 | `STEALTH_MINT_RUG_CATASTROPHIC` | `:9871` | mint-burn alert | P2 | |
| C10 | **first 45 s:** `SETTLE_MFE_FLOOR_PEAK_*`, `SETTLE_PEAK_DRAWDOWN_PEAK_*`, then checkProfitLock, then `SWEEP_TAKE_PROFIT_*_SETTLE_BYPASS` → return | `:9885-9930` | PeakDrawdownLock floor / lane-aware settle lock | P1 / P4 / P2 | |
| C11 | dead token, dust liquidation | `:9932-10049` | 15 min with no feed; unroutable dust | P1 | |
| C12 | `STALE_FLAT_CULL_7353`, `DEAD_MONEY_CULL_7388` | `:10053-10140` | non-runner flat band; no new high for 8 min, pnl in -14..+5 % (live peak-exempt) | P1 | |
| C13 | `capitalRotation7948` | `:10143`, `:15142` | wallet cannot fund one order; flat/red stale live position | | |
| C14 | **`checkProfitLock`** | `:6791-7283` | (i) `protective_peak_partial` **25 % at +500 %** (:6935); (ii) **`fresh_launch_quick_take_7737` 60 % at +50 %, 75 % if the first take is ≥ +100 %, then 50 % at +100 %** (:6957; `truth/FreshLaunchSelector7737.kt:306-313`); (iii) ultra bank **80/90/95 % at ≥ 50x** (:7026-7075); (iv) **wallet-growth harvest 35/50/65 % at ≥ 3x** once unrealised ≥ max(0.02, 10 % of wallet, 2 × cost) (:7090-7165); (v) **capital recovery** at 1.5–3.0x (`calculateProfitLockThresholds` :6607-6789, cap `:311`), sized to recover cost (≈ 50 %+ at 2x); (vi) **PROFIT_LOCK 50 % at 3–500x (~5x)** (:7238-7280) | **P4: no doctrine** | |
| C15 | **`checkPartialSell`** | `:10189-10660` | milestones = WR-recovery rungs × 0.8–2.0: AGGRESSIVE **+50/+150/+1000 %**, MODERATE +50/+250/+1500 … (`WrRecoveryPartial.kt:196-202`), 15–25 % slices (`:266-274`), plus the Exit-AI partial (:10204) | **P5: no doctrine** | |
| C16 | `SWEEP_TAKE_PROFIT_<tp>` | `:8535-8684`, called `:10156` | **full exit** at max(lane TP, WR rung1 ≈ 50 %) × tpMult; not MOONSHOT/SNIPER/PRESALE, not when a plan exists | P2 | doctrine holds it above the line |
| C17 | `SWEEP_FLUID_FLOOR_<x>` | `:10159-10166` | pnl ≤ fluid(-25) (≈ -15..-25 %) | P2 | |
| C18 | `riskCheck` exits → doSell | `Executor.kt:11330-12200`, called `:12311`, `:12982` | reflex_liq_drain, ai_emergency, `learned_hold_ceiling_bank_*` (profit past the learned hold), gemini exits, `*_loss` stops, `breakeven_ratchet`, liquidity/whale/dev/velocity dumps, stop_loss | P2 | |
| C19 | V3 decision `sell`/`exit` | `:12988` | final signal SELL/EXIT | P2 | |
| C20 | HoldingLogicLayer EXIT_NOW / SCALE_OUT (profit side) | `Executor.kt:12870-12904`; `HoldingLogicLayer.kt:164-591`; `LaneHolds8418.kt:38-60` (EXPRESS 30 min / 10 pt, MANIPULATED 15 min / 8, SHITCOIN 12 h / 25, …) | runner trail = RunnerTrail8419 floor for promoted runners, table trail × patience otherwise; max-hold clocks | P1 / P3 (50 %) | |
| C21 | lane sub-trader exits | Express `v3/scoring/ShitCoinExpress.kt:767-871` handled `BotService.kt:32895-32945`: **rungs +30/+50/+100 % sell 20 %** (P3), -15 % stop, trailing, momentum death, time; ShitCoin `ShitCoinTraderAI.kt:1703-1860` (`SHITCOIN_PARTIAL_TAKE_25PCT`, -15 % in the first 60 s, 8 % trail); Moonshot `BotService.kt:1331`, `:33124`; Manipulated `:32968`; DipHunter `:33392`; ProjectSniper `:31054` | | P1 / P3 | LaneExitContract8468 says these act un-held before promotion, but the doctrine runs first |

### 1.4 Loop D: risk clock

`PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK` fires when the held-quote-adjusted mark is ≤ entry × (1 - ordinary stop), after 45 s. `PROTECTIVE_EXIT_CATASTROPHE` fires at **-25 %** (`Executor.protectiveExitThresholds6882` `Executor.kt:11271-11325`; `ExitThresholds8418.kt:74-79`). The sell goes through P1, and the venue refute (8489) can hold it.

### 1.5 Hold / modifier authorities

| Authority | Where | What it does | Numbers |
|---|---|---|---|
| **RunnerDoctrine8584** | `engine/RunnerDoctrine8584.kt` | holds any non-NEVER_HELD, non-tier-1 exit on lanes in the exact set {SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER, MANIPULATED, DIP_HUNTER} while `now > line(peak)`; time exits pass if peak < 1.5x. Called only at P3 (`Executor.kt:24226`) and in moonbagGate (`:25085`). Forces TIERS_7967 on spike rungs; floors the LaneExitTuner multipliers at 1.0. | line = peak < 2x: max(1.05, 1 + 0.5(peak-1)); peak ≥ 2x: max(1.5, 0.55 × peak) |
| **StopAuthority7887** (ordinary stop) | `engine/cortex/StopAuthority7887.kt:53-102` | plan > lifecycle 40 > learned 8040 / lane base × tuner > doctrine floor > winner-dip > noise + cost | bounds 4–25 %; **runner lanes ≥ 15 %**. Log: EXPRESS 15.3, SHITCOIN 15.0, STANDARD 15.0 (`stops8169`) |
| ExitDoctrine8169 | `engine/ExitDoctrine8169.kt` | hard floor = stop + 5 (15..30), catastrophe ≥ hard floor, runner protect floor | the 8298 restore keeps 15 % (`RegressionRestore8298.kt:28-30, 91-96`) |
| LaneExitTuner | `engine/learning/LaneExitTuner.kt:440-473` | tp/sl multipliers (0.6–1.4 / 0.7–1.3; regret up to 1.8 / 1.6); doctrine floor 1.0 on meme lanes | the log dump prints **EXPRESS slMult 0.86, SHITCOIN 0.98**, but the effective value is floored at 1.0 |
| RunnerExitProfile7277 | `engine/RunnerExitProfile7277.kt` | runner lanes (contains match, includes CORE); give-back arm +20 % (+25 % MOONSHOT); contract lanes promote at +50 % | |
| LifecycleHold8065 | `engine/LifecycleHold8065.kt:218-305` | MOONSHOT/QUALITY/BLUECHIP/CORE with runner evidence, or a ticket, or promotion at +100 %: holds every ordinary exit; own exits | floor -40 %, **takes +100 %:50 %, +400 %:30 %, +900 %:30 %**, trend break at 45 % of the peak multiple after +300 %, dead money 72 h |
| TailBag8034 | `engine/TailBag8034.kt:71-86, 126-143` | first non-structural exit sells 75 % and keeps 25 % if funded; the tail holds | milestones +400 %:⅓, +1900 %:½, +4900 %:rest; release at 7 d or under the green floor |
| MoonbagRunner7322 | `engine/MoonbagRunner7322.kt:61-102` | give-back exit with peak ≥ +25 % → bank 60 % (MOONSHOT 35 %), funded only | the bag holds while > peak × (1 - min(1.5 × PeakDrawdown frac, 0.5)) |
| LaneExitContract8468 | `engine/LaneExitContract8468.kt:47-59` | lane-prefixed designed exits sell whole before promotion | |
| NetEdge8030 | `engine/truth/NetEdge8030.kt:571-609` | holds green take-profit exits under cost + 1 % | |
| RunnerGrab7967 deferral | `engine/RunnerGrab7967.kt:510-552` | defers soft exits for a runner basis while the 1m/15s structure stands (green floor releases) | |
| CortexExit7897.holdVeto | `Executor.kt:25240` | holds an ordinary exit of a winner when "holding pays" is proven | |
| GreenFloor8363 / RunnerTrail8419 | `engine/Exits8363.kt:50-139`, `engine/RunnerTrail8419.kt` | runner floor = max(min(BE, ½peak), 0.4 × peak, 65 % of peak price) | |
| ExitProfile7955 | `engine/ExitProfile7955.kt:176-258` | learned tiers/trail/max-hold per key (trail 12 %, maxHold 17 min, POPFADE in the log) | |
| PeakDrawdownLock | `engine/PeakDrawdownLock.kt:31-167` | give-back fraction 0.40 (peak < 50) → 0.08 (≥ 3000) | |
| LaunchWindow8379 | `engine/LaunchWindow8379.kt` | loss hold above -35 % in the launch window. **Switch off (shadow only).** | |

---

## 2. Contradictions

1. **The doctrine says it owns meme exits "at both sell doors". Two more doors exist.** P4 (`executeProfitLockSell`) and P5 (`checkPartialSell`) never call `runnerDoctrineHolds8584`. The only call sites are `Executor.kt:25154/25166/25085/24226`. Sold past the doctrine:
   - capital recovery (1.5–3x, ≈ half or more of the bag at 2x);
   - wallet-growth harvest. At the live wallet (0.146 SOL) and ticket (0.035) it arms at **3x** (unrealised ≥ 2 × cost) and sells 35 %, then 50 % at ~4x and 65 % at ~5x;
   - PROFIT_LOCK 50 % at ~5x;
   - **fresh-launch quick take 60 % at +50 %**;
   - protective 25 % at +500 %;
   - ultra bank 80–95 % at 50x;
   - WR-recovery partials at +50/+150/+1000 %.

   Each contradicts +100:30 / +400:35 / +900:35. This is the 8584 pattern again ("sold by partial_25pct, partial_43pct…"): `partial_xxpct` is the P5 reason label (`Executor.kt:10591`).
2. **Lane keying.**
   - `RunnerDoctrine8584.memeLane8584` uses exact set membership (`:38`), so `MOONSHOT_LUNAR` / `MOONSHOT_ORBITAL` / `MOONSHOT_<SPACE>` (`BotService.kt:29886, 32649, 32765, 33287`) are **not doctrine positions**. `CanonicalLaneIdentity6506` has no alias for them (`:70-103`).
   - TailBag and LifecycleHold canonicalise and miss them too.
   - `RunnerExitProfile7277` uses `contains` and includes them; `LANE_HARD_15` uses an exact match.
   - Positions recorded as `STANDARD` (984e63: `lane=STANDARD entryLane=SHITCOIN`) fall outside every meme regime and get non-runner profit locks.
   - Five different "meme lane" sets exist:
     - doctrine (6 lanes);
     - TailBag (5);
     - RunnerGrab (3);
     - contract lanes (5);
     - LANE_HARD_15 (3);
     - tick one-strike (MANIP/SHITCOIN/EXPRESS + QUALITY/BLUECHIP/CORE);
     - LifecycleHold (MOONSHOT + 3 non-meme).
3. **Doctrine vs LaneExitContract8468.** moonbagGate checks the doctrine (`:25085`) *before* the contract (`:25087`). The partial door checks the doctrine first (`:24226`). Express' designed exits (`EXPRESS_TAKE_PROFIT_30/50/100_PARTIAL_20PCT`, `EXPRESS_TRAILING_STOP`, `EXPRESS_MOMENTUM_DEATH`) are held whenever price is above the line, which contradicts 8468's "act un-held before promotion". NetEdge8030 exempts the contract; the doctrine does not.
4. **Doctrine vs LifecycleHold8065 deadlock (MOONSHOT lifecycle holds).** LifecycleHold blocks spike rungs (`SpikeCapture7943.kt:254`, `LifecycleHold8065.partialHeld8065`). The doctrine holds `LIFECYCLE_PROFIT_TAKE1_8065_*`, which is not in NEVER_HELD and not tier 1. At a +100 % take the line is +50 %, so the take is held. Result: a lifecycle-held MOONSHOT banks nothing until price falls back under the line.
5. **Doctrine vs TailBag / Moonbag.**
   - `TAIL_MILESTONE_8034_*` at +400/+1900/+4900 % always sits above the line (≥ 2x peak → line 0.55 × peak), so every milestone is held.
   - `QUICK_RUNNER_MOONBAG_BANK_7349` at 6x is held.
   - Conversely, TailBag `SET_ASIDE` (sell 75 %) and MOONBAG_BANK (60 % / 35 %) run right after the doctrine passes, i.e. exactly when price crosses under the line. The first sub-line give-back exit therefore sells 60–75 % at once, not "a trail".
6. **At least 8 ladders for the same position.**

   | Ladder | Numbers |
   |---|---|
   | doctrine / TIERS_7967 | +100 %:30 / +400 %:35 / +900 %:35 |
   | SpikeCapture TIERS (still printed in the report as `tiers=+40%:60%…`) | +40:60 / +120:70 / +400:70 |
   | ExitProfile7955 learned (e.g. MOONSHOT\|BUYERS_DOMINATING) | +30:95 % … |
   | LifecycleHold | +100:50 / +400:30 / +900:30 |
   | TailBag | +400:⅓ / +1900:½ / +4900:all |
   | RAPID TP | 25 % at +100/+300/+1000 |
   | checkProfitLock (P4) | +50:60 %, 1.5–3x recovery, 3x harvest, ~5x 50 % … |
   | checkPartialSell (P5) | +50/+150/+1000 |
   | Express sub-trader | +30/+50/+100 at 20 % |
   | QUICK_RUNNER | 35/60 % or 100 % at 6x/10x |
   | MoonbagRunner | 60/35 % bank at the first give-back past +25 % |

7. **At least 10 trails / give-back rules.** Levels at which each fires (gross %):

   | Peak | Doctrine line | GreenFloor / RunnerTrail8419 / RunnerGrab | RAPID_DRAWDOWN / PeakDrawdownLock |
   |---|---|---|---|
   | +40 | +20 | +16 | +15 / +24 |
   | +100 | +50 | +40 | +70 |
   | +400 | +175 | +225 | +331 |
   | +900 | +450 | +550 | +784 |

   Also in play: TICK_PROFIT_LOCK (fluid ratchet), RAPID_PEAK_LOCK (fluidProfitFloor), PEAK_CAPTURE_6394 (fires on a +12 % peak), ExitProfile7955 trail 12 %, the Moonbag hold, the LifecycleHold 45 %-of-multiple trend break, Express `fluidTrailPct × 1.3`, the ShitCoin 8 % trail and the LaneHolds8418 table trails.

   On doctrine lanes the tighter trails fire first, every 500 ms / 1 s, and are each **held with FAILED_RETRYABLE** until price reaches the doctrine line. The door then sells, and TailBag/Moonbag sell 60–75 % there. On non-doctrine positions the tightest trail wins.
8. **Stops collapse onto -15 % on single marks.**
   - Ordinary stop ≥ 15 % (runner floor); rapid hard floor 15 %; rapid catastrophe ≈ 15 % (the catastrophe is defined as "≥ hard floor", but the 8298 restore pins both at 15); `LANE_HARD_15` at -15 % on `worstPnl`; tick one-strike at -15 %; Express and ShitCoin -15 %.
   - Against that: the risk-clock / Executor catastrophe at **-25 %**, `ExitThresholds8418`'s documented "CATASTROPHE -25 %", `RunnerGrab`'s **-35 %** hard stop and its "catastrophe stops above -35 % deferred", and the lifecycle -40 % floor.
   - RunnerGrab's -35 % is unreachable: `RAPID_CATASTROPHE_STOP` is CAPITAL_PRESERVATION, never deferred, and fires at -15 %.
   - STRICT_SL pulls its trigger earlier by the predicted slip (`Executor.kt:9607-9612`).
   - LaneExitTuner can "widen" the stop up to 25 %, but the fixed 15 % backstops make that a no-op.
9. **Stops and locks act on marks that the fill does not confirm** (session evidence, §3).
   - Chain/curve marks are exempt from executable confirmation (`BotService.kt:19551-19555`).
   - LANE_HARD_15 uses min(live, cached) (`Executor.kt:9032, 9239`).
   - `EXIT_TRIGGER_CONTRADICTED_BY_QUOTE_7928=15` shows the problem is known and partly caught.
10. **Hold overlays differ by door.** P1 has RunnerGrab defer, NetEdge, venue refute and style/dust; P2 has CortexExit veto + quote corroboration but not those; P3 has the doctrine first; P4/P5 have none. The same economic decision is held on one path and executed on another. Example: a `RAPID_DRAWDOWN` held by the doctrine, and on the next manage cycle `wallet_growth_harvest` sells 50 % via P4.
11. **RunnerGrab structure break.** It sells 100 % on the first 1-minute lower low at *any* pnl after 60 s (9GpJDZ at -1.7 %). Above the doctrine line the same reason is held, because STRUCTURE_BREAK is not in NEVER_HELD (only STRUCTURE_STOP is).
12. **QUICK_RUNNER on non-runner positions.** `QUICK_RUNNER_6X_BANK_95PCT` sells **100 %** (doSell, despite the label), and `QUICK_RUNNER_10X_FULL_EXIT` sells 100 %. STANDARD-labelled meme coins get exactly this.
13. **Clocks.** STALE_FLAT_CULL, DEAD_MONEY_CULL (8 min with no high), PROFILE_MAX_HOLD_7955, `learned_hold_ceiling_bank`, the HoldingLogicLayer max hold, the LaneHolds8418 clocks (EXPRESS 30 min, MANIP 15 min), Express TIME_EXIT/MOMENTUM_DEATH, Moonshot TIMEOUT/FLAT_EXIT, CortexExit, RunnerGrab's 6 h window, Lifecycle 72 h and TailBag 7 d.
    - The doctrine lets time exits through only for peaks under 1.5x, and recognises a time exit only by the words TIME/MAX_HOLD/STALE/EXPIRED/TIMEOUT/FLAT.
    - `learned_hold_ceiling_bank_*` and `sell`/`exit` (V3) are therefore treated as profit exits.
14. **CortexExit7897.** It sells full on SELL_STRONG (3 of 10 session exits, all -4.7..-7 %) and also vetoes ordinary exits of winners (P2). A predictive exit sits beside the stop, and a predictive hold sits beside the doctrine.
15. **Report vs runtime.**
    - "Spike capture tiers=+40%:60%…" is not what runs on doctrine lanes.
    - The LaneExitTuner dump shows slMult < 1 that is never applied.
    - "Stop authority … runnerFloor=15%" combined with "hardFloor=stop+5 (15..30)" reads as 20 %, but runs at 15 % (`restore8298`).

---

## 3. What actually closed live positions (log, build 8622)

**Session sells (10 of 10, from "Recent LIVE executions").** Realised = grossOut / soldBasis - 1.

| Mint | Lane (tradingMode) | Exit reason | Realised | Held |
|---|---|---|---|---|
| AxVwrg | SHITCOIN | RAPID_CATASTROPHE_STOP | **-2.5 %** | 20 s |
| 9GpJDZ | EXPRESS | RUNNER_STRUCTURE_BREAK_7967_1M_1PCT | -1.7 % | 84 s |
| 9GpJDZ | SHITCOIN | PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK | **+1.3 %** | 58 s |
| 7ypCq2 | QUALITY | CHART_STOP_7950_TOP_MOTIF | -8.7 % | 103 s |
| HSrxpy | EXPRESS | CORTEX_EXIT_7897_EXPRESS_FWD-22PCT | -5.6 % | 71 s |
| 51H7Az | EXPRESS | LANE_HARD_15PCT_SL_EXPRESS | **-3.8 %** | 99 s |
| 984e63 | **STANDARD** (entry SHITCOIN) | TICK_PROFIT_LOCK_GAPPED_peak8_floor5_now-5 | **+11.0 %** | 269 s |
| 8rHoAf | EXPRESS | PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK | -16.1 % | ? (recovered row) |
| 7mEhH5 | EXPRESS | CORTEX_EXIT_7897_EXPRESS_FWD-21PCT | -7.0 % | ? |
| HgJzMk | EXPRESS | CORTEX_EXIT_7897_EXPRESS_FWD-22PCT | -4.7 % | 138 s |

Net -0.0134 SOL.

- **Stops whose trigger sits ≥ 11 points away from the fill: 4 of 10.** These are the three "-15 %" stops filled at -2.5 %, -3.8 % and +1.3 %, and the "-5 %" lock filled at +11 %.
- 3 of 10 exits are CortexExit.
- 0 ladder, trail or doctrine exits.
- All holds were under 5 minutes.

**Window and history counters.**
- Canonical live: n = 38, W/L 8/30, PF 0.37, expectancy -0.0012 SOL.
- Scorecard: EXPRESS n21 WR 10 % PF 0.10; SHITCOIN n6 PF 0.02; MOONSHOT n8 WR 50 % PF 0.95.
- runnerAdmits (live): **LOSS_BOUGHT_TOP 40 / LOSS_GAVE_BACK 10 / WIN 13**. Per `truth/RunnerFunnel8297.kt:109-115`: GAVE_BACK = closed red with MFE ≥ +5 %; BOUGHT_TOP = MFE < +5 %.
- Decision-reason grades (persisted, after-exit price move):
  - LIVE `HARD_STOP@EXPRESS` n28, +1.2 %, up 32 %;
  - `HARD_STOP@MOONSHOT` n20, +3.2 %, up 45 %;
  - `HARD_STOP@QUALITY` n16, -3.4 %;
  - `RUG_DRAIN@QUALITY` n14, +4.3 %;
  - `HARD_STOP@CASHGEN` n11.

  Stop-family closes dominate. On EXPRESS the price kept falling after the stop 68 % of the time.
- Forward-label stops (`stops8169 labelStops beforePeak/thenRan/afterPeak`): EXPRESS 84/7/20, SHITCOIN 87/6/25, MOONSHOT 68/8/15. Most stopped labels never ran afterwards.
- Exit-machinery activity this session:
  - Spike capture `fired=0`; runnerFunnel `rungArmed=0 rungTriggered=0 sellSent=0`.
  - Lifecycle `holds=1 exitsHeld=0`.
  - Tails `0` (`TAIL_UNFUNDED_NOT_SET_ASIDE_8136=16`: losing exits correctly not tailed).
  - laneExitContract `acted=0`; greenFloor8363 `0`; `NET_PROFIT_EXIT_HELD_8030=1`.
  - runnerLock `UNDER_BREAKEVEN=8`, all others 0.
  - arbiter meanMfe -2.5 %, meanRealisedCapture -10.8 %.
  - `RUNNER_GIVEBACK_LOCK_DEFERRED_UNDER_MIN_PEAK_7277=747`.
  - Stop triggers: `RAPID_CATASTROPHE_STOP` SHITCOIN 34 / EXPRESS 5, `RAPID_SUB_TRADER_HARD_FLOOR` 20, `TICK_HARD_FLOOR_CATASTROPHIC_LANE` 7–13, `RISKCLOCK` 7.

**Why RunnerDoctrine8584 shows `held=0`:**
1. **No opportunity.** `holds8584` returns true only if the exit is a non-safety exit on a doctrine lane **and price > line(peak) ≥ +5 %** at that moment.
   - This session no meme-lane position was above +5 % when a profit exit asked.
   - The only green trade (984e63) peaked at +8 %, and its mark at the exit read -5 % (< line).
   - The other 9 exits were stops, the cortex or the chart at a loss: NEVER_HELD words, or pnl ≤ +5 %.
   - Mean MFE was -2.5 %, the 8 runner locks were UNDER_BREAKEVEN, and no rung armed.
2. **Blind spots, even with winners.**
   - (a) Exact-lane keying excludes `STANDARD` and `MOONSHOT_<SPACE>`.
   - (b) On P1 the doctrine runs after LifecycleHold, RunnerGrab defer, NetEdge, venue refute, etc. Those return first, and only the doctrine counts as `held`.
   - (c) P4/P5 never call it.
   - (d) It prices pnl from `ts.lastPrice`, while the exits fire on exec/worst/held-quote marks.
3. **Counter scope.** `held8584` is an in-memory map (`RunnerDoctrine8584.kt:74-82`). The process restarted after the OOM crash (143 min before the snapshot, uptime 2.4 h).
4. **By design it cannot fix LOSS_GAVE_BACK.** It never sells. It holds nothing at or below +5 %. For a +5..+10 % peak its line is +5 %, so a position that peaks at +8 % and falls to -15 % is still sold by the -15 % stop.

---

## 4. Proposed single exit model for meme lanes

**Principle.** One function decides every meme exit:

`MemeExit.decide(position, confirmedMark, executableQuote, nowMs) → {HOLD | SAFETY(reason) | CATASTROPHE | STOP | TRAIL | RUNG(i, fraction) | CLOCK}`

- It is evaluated once per position in Loop A (500 ms). Every other loop calls it or nothing.
- It is keyed on the **immutable entry lane** (the canonical position's `entryLane`, folded, so `MOONSHOT_*` maps to MOONSHOT and STANDARD-relabelled entries keep SHITCOIN). It is never keyed on mutable `tradingMode`.
- The executor doors become pure executors for meme positions. Any meme sell whose reason did not come from `MemeExit` or the safety list is refused and counted.

### 4.1 The one stop
- **STOP** fires at gross ≤ **-S**, with S = clamp(max(lane stop, 1.5 × measured round trip, round trip + 2 × 1m range), 15, 25).
  - This is today's `StopAuthority7887.stopMagFor` minus the winner-dip / tuner layers. The tuner may widen it, never tighten it.
  - It is armed after 45 s.
  - **It sells only on confirmation:** a fresh executable held-exit quote (`HeldExitQuote8189`) at or below the level, or two independent marks (curve print + DEX/pool) at least 2 s apart. This would have stopped the -2.5 % / -3.8 % / +1.3 % "-15 %" stops.
- **CATASTROPHE** fires at gross ≤ **-(S + 15)** (≈ -30..-40 %) on any single chain mark, immediately, no confirmation needed. A lifecycle position uses -40 %.
- **Replaces:** RAPID_CATASTROPHE_STOP, RAPID_HARD_FLOOR_STOP, DEEP_CATASTROPHE_NET, LANE_HARD_15PCT_SL, TICK_HARD_FLOOR (all variants), RUNNER_EARLY_CUT, MOONSHOT_LANE_STOP_LOSS, STRICT_SL (all), PROTECTIVE_EXIT_STOP_LOSS / CATASTROPHE, CATASTROPHIC_HARD_BACKSTOP_-25, SWEEP_FLUID_FLOOR, RAPID_FLUID_STOP, RAPID_SUB_TRADER_HARD_FLOOR, THIN_LIQ_EARLY_RUG_BACKSTOP, RUNNER_HARD_STOP_7967, sub-trader STOP_LOSS (Express, ShitCoin, Moonshot, Manip, Dip, Sniper), STALE_QUOTE_EMERGENCY_25PCT, ExitDoctrine8169 hard floor / catastrophe, RegressionRestore8298 backstops, LaunchWindow8379.

### 4.2 The one trail (the doctrine line, now as the sell rule)
- **Arms at a confirmed peak ≥ +25 %.** Below that, only STOP / CATASTROPHE / SAFETY / CLOCK apply. This deliberately drops the +8–12 % locks: they sell noise and they stop runners at +10 %.
- level(peak):
  - peak < 2x: max(net break-even + 1 %, 1 + 0.5 × (peak - 1)), i.e. keep half the peak gain;
  - peak ≥ 2x: max(1.5x, 0.55 × peak multiple), i.e. a 45 % give-back of the multiple.
- "Confirmed peak" means the chain-verified / corroborated peak (`PositionDisplayTruth8078`). The breach must be confirmed the same way as the stop.
- **TRAIL sells the whole remainder.** There is no 75 % tail set-aside and no 60 % moonbag bank at the cross.
- **Replaces:** RAPID_DRAWDOWN_FROM_PEAK, RAPID_PEAK_LOCK_BREACH, PEAK_CAPTURE_*_6394, TICK_PROFIT_LOCK (+GAPPED), RAPID_TRAILING_STOP, SETTLE_MFE_FLOOR / SETTLE_PEAK_DRAWDOWN, PeakDrawdownLock, GreenFloor8363 / RunnerTrail8419, RUNNER_PEAK_CAPTURE_7980 / RUNNER_GREEN_FLOOR, MoonbagRunner hold, TailBag hold / release, LifecycleHold trend break, ExitProfile7955 `applyTrail7955`, HoldingLogicLayer / LaneHolds8418 trails, sub-trader TRAILING_STOP, breakeven_ratchet, RunnerDoctrine8584 as a hold gate (it becomes this rule).

### 4.3 The one ladder
- **RUNG 1: +100 % gross, sell 30 %. RUNG 2: +400 %, sell 35 % of the current holding. RUNG 3: +900 %, sell 35 % of the current holding.** About 30 % of the original rides the trail.
- Rungs fire on the authoritative print, using the existing SpikeCapture7943 / RunnerRungBook8153 / arbiter-ticket plumbing with `TIERS_7967`.
- **Replaces:**
  - every other profit-side seller for meme lanes: SpikeCapture TIERS / ExitProfile7955 learned tiers, RAPID_INSTANT_PROFIT_CAPTURE_4301, QUICK_RUNNER_* (all three), SWEEP_TAKE_PROFIT;
  - in `checkProfitLock`: protective 25 %, fresh-launch quick take, ultra bank, wallet-growth harvest, capital recovery, PROFIT_LOCK_5X;
  - `checkPartialSell` milestones, LifecycleHold takes, TailBag milestones / set-aside, MoonbagRunner bank, Express TP_30/50/100, ShitCoin / Moonshot PARTIAL_TAKE / TAKE_PROFIT, `learned_hold_ceiling_bank`, the V3 `sell` signal in profit, the HoldingLogicLayer EXIT_NOW / SCALE_OUT in profit;
  - NetEdge8030's profit hold. It is no longer needed: the first sale is at +100 %.
- If the owner wants a fail-safe for illiquid mega-moves, keep **one** extra rung in the same table, e.g. ≥ 50x: sell 50 % of the current holding, not a separate module.

### 4.4 One clock
- **CLOCK:** if the confirmed peak is below +25 % after **T** minutes (one learned number per lane, default 30 min) and the gross is under break-even + 1 %, exit. It never fires while the trail is armed.
- **Replaces:** STALE_FLAT_CULL, DEAD_MONEY_CULL, PROFILE_MAX_HOLD_7955, LaneHolds8418 / HoldingLogicLayer max hold, Express TIME_EXIT / MOMENTUM_DEATH, Moonshot TIMEOUT / FLAT_EXIT, the RunnerGrab 6 h window, TailBag 7 d, Lifecycle dead money (meme lanes), data_quality_stale_feed_evict / DEAD_TOKEN (keep those only as SAFETY when the feed is dead).

### 4.5 What remains, as safety only

These bypass everything, with no lane logic:
- **Tier-1 / integrity:** rug, LP pull, liquidity collapse / drain (incl. reflex_liq_drain, liq < 50 % of entry), dev dump / sell, honeypot / cannot-sell, freeze / frozen, mint-authority, Mayhem, whale / velocity dump *only* when it carries the existing tier-1 evidence, STEALTH_MINT_RUG, STALE_PRICE_RUG_ESCAPE (long dark plus rug evidence).
- **Operator and accounting:** manual, kill switch, shutdown, reconciler / orphan / invariant / zero-balance / dust, capitalRotation7948 (only when no order can be funded).
- **Vetoes kept as mark guards, not exits:** BasisBreak8019, MissingMarkExitVeto6835, ExitQuoteCorroboration7928, StopMarkConfirm8418 (folded into the STOP confirmation), MarkGate8418.

### 4.6 Removed for meme lanes or reduced to telemetry

- RunnerDoctrine8584 hold gate (now §4.2)
- LifecycleHold8065 for MOONSHOT (meme positions use this model; it may stay for QUALITY / BLUECHIP / CORE)
- TailBag8034
- MoonbagRunner7322
- RunnerGrab7967 deferral and structure-break exits (structure can feed the trail arm, not sell)
- LaneExitContract8468
- tiny-profit dust deferral and style min-hold
- CortexExit7897 sell and holdVeto (keep it in shadow until it proves itself on executed outcomes)
- ScalpExit8351 on meme lanes
- ChartReader7950 CHART_STOP (shadow)
- ExitProfile7955 tiers / trail / max-hold (keep the learning, drop the actuation)
- the LaneExitTuner tp multiplier

### 4.7 What to expect from it
- This removes the 4-of-10 phantom stops and the profit sales below +100 %, and makes the 8584 ladder the only ladder.
- It does **not** fix the entry: 40 of 63 runner admits never reached +5 %. With the lane round trip (EXPRESS 3.5 %, MOONSHOT median 19 %) a meme book needs the occasional 5–10x to pay for those.
- To measure the model, add one counter per `MemeExit` verdict and one "refused non-model meme sell" counter (by caller). Over the next live window, check that the two counters reconcile with the canonical closes.
