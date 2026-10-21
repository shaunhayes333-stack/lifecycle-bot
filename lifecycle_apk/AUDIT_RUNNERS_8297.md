# AUDIT_RUNNERS_8297 — runner capture end to end (V5.0.8297)

Base: origin/main 7c147e519. Evidence: live 8282 (39 runners, 36 missed; KIMOTHY +2153%, Kimchi +4817%, LEONIDA +2226%
missed on DATA_INCOMPLETE_DEFER_8136), live 8286 (4/4 losses, all ROUTE_MIN, FDG blocks LIVE_PROVEN_SETUP_ONLY_8059=751,
EXECUTION_ECONOMICS=598, DATA_INCOMPLETE=468).

Path traced: ForwardReturnLabeler7731 -> RunnerPlay8018.onRun8018 (ticket) / RunnerGrab7967.grab7967 (grab) /
RunnerGrab7967.formingNative8260 -> CandidateAdmission8136.decide8136 -> LiveEdgeGate7877.liveRefusal -> FDG -> sizing ->
buy -> SpikeCapture7943 / RunnerLive8153 / RunnerExitLive8136 / Executor.requestPartialSell -> ProfitEscalation8189 ->
CanonicalFinalizedTradeBus6464 -> RunnerCapture8017 report.

## Findings

### F1 — the runner report could not say where a runner was lost (fixed)
- `engine/truth/RunnerCapture8017.kt` (statusLine, `held` count): any runner the bot ever held counted as `held`,
  including a pump bought at its top and stopped out, or a coin sold at +20% before a +2000% run. A missed runner printed
  only its last refusal label (`MISSED(<label>)`).
- `AdmissionRunnerTrace8144.classify8144` classifies correctness (CORRECT / WRONGLY_REFUSED / DATA_GAP) but not the
  funnel stage, and says nothing about bought runners.
- Fix: `engine/truth/RunnerFunnel8297.kt` — `RunnerFunnel8297.attribute8297` puts every 24 h runner in exactly one
  first-losing stage: NOT_EVALUATED, DATA_DEFER, SAFETY, GATE_REFUSED(<gate>), ADMITTED_NOT_BOUGHT(<downstream>),
  BOUGHT_AFTER_PEAK, SOLD_BEFORE_PEAK, SOLD_EARLY (<25% of the run available from its own entry), HOLDING, CAPTURED.
  The binding gate is the trace's binding refusal (the last gate that said no), else the labeler's refusal, else the
  FDG / admission refusal. RunnerCapture8017 now records the close time and hold time of each runner position
  (`Run.closeAtMs/holdMs`) and prints `funnel8297{runners=N <stage>=n ... top=[STAGE:GATE=n]}` on its status line;
  held rows with the peak outside the hold now say BOUGHT_AFTER_PEAK or SOLD_BEFORE_PEAK.
- "Never discovered" can't be measured here by construction: the forward labeler follows only coins the bot saw. The
  scanner/hunter agents own discovery coverage.

### F2 — confirmed runs died at the admission's data deferral before the run evidence was read (fixed)
- `engine/truth/LiveEdgeGate7877.kt:485-511`: the `DEFER_DATA` branch of `when (admission8136?.terminal)` returns
  DATA_INCOMPLETE_DEFER_8136 / DATA_DEFER_BUDGET_EXHAUSTED_8136 / DATA_DEFER_FETCH_NEVER_COMPLETED_8204. The run evidence
  (`runnerForming8113` = RunnerPlay8018 ticket || RunnerGrab7967 grab, line ~816) is only read later, at the proven-only
  door (line ~583). So a coin the bot had seen go +100% over its decision price with its chart standing (ticket), or a
  coin in a proven runner cell firming now (grab), was deferred for `bp`/`chg`/`shape` inputs. That is the
  KIMOTHY/Kimchi/LEONIDA shape: DATA_INCOMPLETE was the binding verdict, and there was no runner path past it.
- Fix: `RunnerDefer8297.terminalFor8297` (in RunnerFunnel8297.kt), a one-line change at LiveEdgeGate's `when` subject.
  A DEFER_DATA terminal becomes "no terminal" (the later gates decide) only when:
  (a) a ticket or grab is active, (b) the missing list is non-empty, and (c) none of the missing inputs is critical by
  `CandidateAdmission8136.criticalMissing8170`: price/staleness, economics, liquidity, route, sellability, depth, quote,
  identity, mint, supply, decimals, wallet, balance, cost. The fetch is still requested. Every later gate still binds:
  live-close setup refusal, timing/picked-setup/tactic negatives, the chase rule, watch-first, the core net-edge test,
  FDG, hard safety, rug, sellability, liquidity floors, sizing and the buy door. Counter:
  `RUNNER_CONFIRMED_PASSES_OPTIONAL_DEFER_8297`; the status line shows `runnerDefer8297[released=n]`.
- Not changed: `formingNative8260` (an unlearned "opinion" with no closed loop of its own) still defers. The lane-wide
  data-defer root cause (bp/chg binding for brand-new launches, the P4 lifecycle split, the starved DexScreener batch)
  belongs to the data-delivery and admission agents.

### F3 — a confirmed-run ticket had one 3-minute window per coin per 6 h (fixed)
- `engine/RunnerPlay8018.kt` `onRun8018`: `armed[mint]?.let { if (nowMs - it < SEEN_MS) return }` with
  TICKET_TTL_MS = 3 min. If those 3 minutes landed at the coin's high (CHASING_SPIKE_8065 correctly says wait for the
  pullback) or on a data deferral, the run was never offered again, even though the labeler kept confirming it.
- Fix: `RunnerPlay8018.rearms8297`. A lapsed window re-arms after TTL + 60 s while `confirmsRun8018` still holds
  (+100..+3000% within 4 h, structure standing, not held). There are at most 3 windows per coin, and none once the bot
  has closed a position in the coin since the first ticket (a ticket clears the repeat-loser lock in
  RebuyLockout8019:82, so it must not loop a loser). The record stand-down and MAX_OPEN_REENTRIES are unchanged.
  Status: `rearmed8297=`.

### F4 — a ticketed coin was not guaranteed a cycle inside its window (fixed)
- `engine/HandoffThroughput8190.kt` `targets8190`: the per-cycle priority window carried hand-off tasks and election
  yields, but not runner tickets. A ticketed mint depended on the 128-mint rotation and the supervisor slice to be
  evaluated within 3 minutes, and assess latency is high (p90 570 ms when the heap is full).
- Fix: `RunnerPlay8018.activeTicketTargets8297` is added to the targets (bounded by MAX_TARGETS_8190 = 12, soonest
  deadline first).

### F5 — early full exit of runner positions at their first uneconomic slice (fixed)
- `engine/Executor.kt:7367` (profitEscalated8189) protected only `LifecycleHold8065.holding8065 ||
  RunnerLive8153.continuationConfirmed8153`. The share budget in `engine/RunnerExitLive8136.kt:38-39`
  (protectsResidual8136) protects a wider set: `RunnerGrab7967.holdingRunner8018 || longHold || holding8065`. At route-min
  size (~0.029 SOL) most discretionary slices are uneconomic. So a grabbed, ticketed, promoted or sealed-runner position
  that had not yet confirmed continuation was sold WHOLE at its first rung, the exact residual the budget was protecting.
  That caps runners at the first take-profit.
- Fix: `ProfitEscalation8189.runnerProtected8297` uses the residual budget's set plus confirmed continuation; the
  Executor call site is one line. Non-runner positions keep the 8189 escalation, so small scalps still bank.

### F6 — runner false positives (pumps bought at the top) were not measured (fixed: measured)
- The grab and ticket records grade the 30-minute signal, not the realised buy.
- Fix: `RunnerFunnel8297.onAnyClose8297` (called from RunnerCapture8017.onClose8017). It grades every close of a
  runner-admitted position (RunnerGrab7967.isGrabbed7980 or an entry setup containing RUNNER) as WIN / WIN_CAPPED /
  LOSS_BOUGHT_TOP (mfe < 5%) / LOSS_GAVE_BACK, per mode. It also counts every live close that saw +100% MFE but banked
  under 25% of it (`liveCapped8297=`). The next device log shows whether the runner paths buy tops
  (LOSS_BOUGHT_TOP share) and whether exits cap runs.

### Question 2 — does runner admission reach the buy?
- Data defers: F2 (fixed for confirmed runs).
- LIVE_PROVEN_SETUP_ONLY_8059: cleared for tickets and grabs by runnerForming8113 (LiveEdgeGate ~583). Not cleared for
  formingNative8260 unless the admission ADMITs it (NATIVE_QUALIFIED_ADMIT_8259 is non-specific; the door then still
  needs measuredEdge / cold start / audit). Left as is (owner doctrine 8279: unproven opinions do not trade live).
- EXECUTION_ECONOMICS: decided on mandate/range/H1 expected gross vs round trip at route-min size
  (CandidateAdmission8136 ~430). Runners are not exempt, and should not be.
- Cooldowns: RebuyLockout8019 already yields to an active ticket. Spike chase (8065) yields only to grabs
  (developingRunGrab8117), not tickets, by design (anti-top). F3 gives the ticket later windows to catch the pullback.
- Max positions: live cap 24 (BotConfig.maxConcurrentLivePositions). At route-min sizes the binding constraint is
  wallet/exposure (70% ceiling), not slots. Losers holding capital is an exits/stops question (stop latency 10-19 s,
  owned by the exits agent).

### Question 3 — bought runners
- The runner ladder for runner-basis positions is RunnerGrab7967.TIERS_7967 (half at +100%, 35% at +400% and +1000%),
  with soft exits deferred while the structure stands, out on a structure break, peak capture (-35% from a +200%
  peak), from-entry giveback (half the peak for +40..199%), or -35%. That is sound. The cap was F5.
- Non-runner positions take the SpikeCapture prior (60% at +40%) until RunnerPlan8153 promotes them (+25% verified peak
  and print with structure firming). Unchanged.

## Growth target (2x–5x daily)
- In my scope, runners are where daily multiples come from. The size goes in on confirmation, not prediction:
  - Entry: grab opens at 1.5x (RunnerGrab7967.sizeMult7967); ticket at <=0.5x then promoted to the routable minimum
    (RunnerPlay8018.sizeMult8018 via TraderSizingBridge6444:121).
  - Pyramid: `Executor.autonomousTopUpSignal6091` -> `RunnerGrab7967.structureAdd7973` (one add per new confirmed 1m
    swing, in profit, max 2). `Executor.doTopUp` passes `runner8018 = holdingRunner8018(ts)` to `topUpSizeSol`, which
    gives runners up to 3x initial per add and 25% of the wallet per add, inside the 70% exposure ceiling. This is wired
    and live.
- Defects found in my scope that throttled runners and so compounding: F5 (runners dumped whole at their first rung,
  so no residual left to pyramid into or compound), F2/F3/F4 (confirmed runs never bought). All fixed. None of the fixes
  takes aggression from paper results or loosens safety. Releases need a live-confirmed run (labeler + structure) and
  every critical executable fact.
- Not audited here (sizing/traders agent): AutoCompoundEngine (compoundPct 45%, maxSizeMultiplier 3.0), LiveSizingProfile,
  MinPromotion8188's defensive-drawdown single-slot rule (8259), which keeps every entry at ROUTE_MIN while drawdown is
  ≥30%. That last one is the main reason "all entries ROUTE_MIN ~0.028 SOL" in 8286: size cannot compound until the
  verified drawdown recovers. Not changed here.

## Tests
`app/src/test/kotlin/com/lifecyclebot/engine/Aate8297RunnerCaptureTest.kt`: stage attribution for missed and bought
runners, summary, outcome grading, the optional-only release (critical inputs never released), the ticket re-arm
bounds, and source wiring pins.

## Remaining (not in this change)
1. The lane-wide DATA_INCOMPLETE on fresh launches (bp/chg binding at birth, P4 lifecycle split not firing, DexScreener
   batch throttled): data-delivery / admission agents. F2 only rescues runs that are already confirmed.
2. The ticket needs a structure read (StructureTracker7962 from ChartReader prices/trades). A coin with no prints
   (tape NO_PRINTS) never gets a ticket. Fixing that belongs to the tape/data path.
3. formingNative8260 still defers on optional inputs. Releasing it would need its own graded closed loop first.
4. Spike-chase (8065) still refuses tickets at the high (intentional). If `funnel8297` shows GATE:CHASING_SPIKE_8065
   dominating, consider a ticket-specific pullback entry.
5. Check the next device log for `funnel8297`, `runnerAdmits8297` (LOSS_BOUGHT_TOP share), `liveCapped8297`,
   `runnerDefer8297[released]` and `rearmed8297`.
