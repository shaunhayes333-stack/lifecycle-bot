# AATE — Meme Specialist Trading Desk Repair Brief

This brief is normative. Section 0 is the leading architecture contract for the full source-grounded Meme Trader repair and overrides any interpretation of lanes as interchangeable labels, classifiers, or mutually exclusive strategy tags.

## 0. CRITICAL ARCHITECTURE REQUIREMENT: THE MEME LANES ARE DESIGNATED TRADING DESKS — NOT TAGS

Do **not** treat the AATE Meme Trader lanes as interchangeable classifiers competing to label the same candidate.

The Meme Trader was deliberately designed with specialist lanes/desks that have designated trading tasks, opportunity pools, discovery responsibilities, market segments, holding behaviour, execution roles, risk profiles, sizing behaviour, entry logic, exit logic, lifecycle responsibilities, learning cohorts, and capital-deployment roles. Recover those responsibilities from actual source and restore them. Do not infer a role from its name.

Scope includes QUALITY, BLUECHIP, SHITCOIN, CYCLIC, EXPRESS / SHITCOIN EXPRESS, CORE, MOONSHOT, PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, TREASURY, CASHGEN, and every other configured Meme Trader specialist lane.

For every lane, search production, tests, historical contracts/comments, persistence, UI/config, reports, learning and execution paths, then produce this source-grounded matrix:

```text
LANE
DESIGNATED_TASK=
DESIGNATED_OPPORTUNITY_POOL=
DISCOVERY_SOURCES=
QUALIFICATION_LOGIC=
ENTRY_ROLE=
CAPITAL_ROLE=
SIZING_ROLE=
HOLDING_ROLE=
EXIT_ROLE=
LEARNING_ROLE=
CURRENT_RUNTIME_PATH=
CURRENT_CAUSAL_AUTHORITY=
BROKEN/BYPASSED_LOCATION=
currentlyInvoked=
currentlyExecutes=
currentlyControlsPositions=
brokenAt=
```

Continue for **all** Meme Trader lanes. Unknowns must be reported as unproven with the exact source search performed; never filled from naming assumptions.

## 0A. DO NOT COLLAPSE DESIGNATED POOLS INTO ONE GLOBAL WATCHLIST

Audit whether each specialist originally has or should have its own candidate pool, discovery queue, filtered view of canonical discovery, specialist source subscriptions, opportunity ranking, reserve/working set, refresh cadence, and pool capacity.

Determine whether refactors collapsed the intended architecture into:

```text
global watchlist -> generic affinity -> many lane labels -> primary election -> one generic execution
```

If confirmed, restore specialist opportunity pools without creating duplicate token identity or duplicate positions. There remains one canonical token identity, wallet/capital truth, entry plan, FDG and position authority. A token may occupy multiple specialist opportunity pools because each desk independently answers: “Does this candidate belong to my assigned task?”

Target model:

```text
                     CANONICAL DISCOVERY BUS
                              |
     -------------------------------------------------
     |            |             |             |
  QUALITY      MOONSHOT       CYCLIC       SHITCOIN
   pool          pool           pool           pool
     |            |             |             |
     -------- specialist intelligence ----------
                              |
                CANONICAL AGGREGATED ENTRY PLAN
                              |
                            FDG
                              |
                         EXECUTION
```

## 0B. RESTORE EACH LANE'S DESIGNATED HUNTING FUNCTION

For every lane prove, with executable/runtime evidence:

1. Its discovery loop runs.
2. Its designated source feed runs.
3. Its opportunity pool receives candidates.
4. Its ranking logic executes.
5. It identifies opportunities independently.
6. It emits BUY, WAIT or REJECT with an explicit causal reason.
7. Its BUY can contribute to canonical execution.
8. It can influence canonical sizing.
9. It can influence an already-open position where designed.
10. It can influence exits where designed.
11. Its economically valid outcome feeds its own learner and changes a later decision.

A lane with thousands of `LANE_EVAL` events but no independent pool, discovery, execution contribution, position/exit influence or learning feedback is telemetry, not functional.

## 0C. QUALITY MUST DO QUALITY'S JOB

Recover Quality’s source contract. Its pool must be populated by intended quality characteristics, not arbitrary globally discovered candidates. Quality qualification must retain causal authority over entry, quality-compatible sizing, holding, risk management and profit capture. If another desk becomes primary, Quality intelligence must not disappear from the canonical aggregated plan or position lifecycle.

## 0D. BLUECHIP MUST OPERATE ITS OWN ASSIGNED SEGMENT

Recover Bluechip’s universe and pool. Audit established-token sources, history expectations, liquidity, market-cap segment, volatility treatment, dip/rotation behaviour, size, hold duration and exits. Bluechip handling must materially differ from a $3k Pump.fun launch; identical generic lifecycle handling is a bypass.

## 0E. SHITCOIN MUST OPERATE ITS HIGH-RISK EARLY POOL

Recover Shitcoin’s exact intended task—not merely “came from Pump.fun.” Audit ultra-early discovery, microcap pool, minimum executable liquidity, scam/rug treatment, speculative sizing, fast invalidation, asymmetric upside and runner handling. Its risk profile must not leak into Quality/Bluechip, and their restrictions must not sterilise legitimate Shitcoin opportunities. Hard rug and raw hard-floor safety remain canonical.

## 0F. CYCLIC MUST ACTUALLY RUN THE CYCLIC STRATEGY

Locate and trace every Cyclic component: discovery, pool generation, cycle/regime analysis, oscillation detection, accumulation/distribution detection, timing, rotation, exits and learning. Prove Cyclic produces executable Meme Trader opportunities. Configuration/UI/telemetry with zero independent candidates, FDG contributions, executions, position-management influence and learning is dead. Do not route generic BUYs through CYCLIC; restore its real strategy.

## 0G. EXPRESS / SHITCOIN EXPRESS MUST RETAIN URGENCY AUTHORITY

Audit the high-velocity pool, short-lived setup and launch-acceleration detection, urgency scoring, fast execution, specialist sizing and fast exits. `EXPRESS eval=568` with no meaningful specialist execution is a causal-path failure. When Express identifies an opportunity that exists now and will expire shortly, its evidence cannot silently die behind generic primary election, generic WAIT or ticket expiry. Preserve hard safety and canonical one-position authority.

## 0H. CORE MUST NOT BE REDUCED TO SHADOW TELEMETRY

Determine from source whether CORE is the baseline strategy, ensemble coordinator, general meme execution strategy, fallback, intelligence foundation, or another designated role. Do not arbitrarily make CORE execute everything. If designed to trade or coordinate, it cannot remain permanently `shadow`, `read-floor`, `score_report_learn_only`, `no_fdg` and `no_exec_ticket`.

## 0I. MOONSHOT MUST BE A TRUE ASYMMETRIC-HUNTING DESK

Recover Moonshot’s full hunting model, not “high score + low market cap”: launches, acceleration, social/volume/liquidity/holder expansion, smart-money activity, bonding/pool progression, price velocity, narrative/attention acceleration, breakout structure and asymmetric-upside scoring.

Moonshot must be able to FIND, QUALIFY, BUY, HOLD, ADD/REDUCE where designed, RUN PROFIT and LEARN. Current 25–104 second losing exits are evidence to test for generic lifecycle override. Moonshot-compatible lifecycle authority must survive after open unless genuine catastrophic invalidation applies.

## 0J. PROJECT SNIPER MUST ACTUALLY SNIPE PROJECTS

Project Sniper cannot be the generic primary-election winner. Recover its original project pool and hunt: genuinely new projects, token/pool age, launch stage, market-cap expectations, source requirements, launch metadata, first-liquidity events, graduation events and early structure. Multi-billion-dollar tokens must never become Project Sniper through fallback classification.

Mandatory invariant and health counter:

```text
PROJECT_SNIPER_NON_SNIPER_ADMISSION = 0
```

## 0K. EACH LANE MUST HAVE BOTH INDEPENDENCE AND COOPERATION

The target is neither fourteen duplicate bots nor fourteen labels feeding one generic bot. It is specialist desks sharing canonical market truth, wallet/capital and position authority while retaining independent hunting jobs and causal intelligence:

```text
SPECIALIST POOLS
↓
SPECIALIST HYPOTHESES
↓
COOPERATIVE INTELLIGENCE AGGREGATION
↓
ONE CANONICAL EXECUTION
↓
ONE POSITION
↓
MULTI-SPECIALIST POSITION MANAGEMENT
↓
OUTCOME LEARNING BACK TO RELEVANT SPECIALISTS
```

Specialist influence must evolve during a position. Example: a new Pump launch can begin as PROJECT_SNIPER + SHITCOIN, gain MOONSHOT + EXPRESS during acceleration, then lose Moonshot conviction while QUALITY grows as it matures. Never open duplicate positions, and never give the first lane permanent exclusive ownership.

The immutable entry snapshot must retain every causal specialist contribution used at entry. Mid-hold contribution changes must be event-local, timestamped and position-linked. Terminal rewards must return only to relevant contributors under economically valid canonical finality.

## 0L. CAPITAL POOLS / ROLE BUDGETS MUST ALSO BE AUDITED

Search all lane-specific `capitalPool`, `allocation`, `reserve`, `budget`, `riskBudget`, `exposure`, `slots`, `maxPositions`, `targetExposure`, `workingCapital`, `laneBalance`, `capitalShare` and `allocationWeight` contracts.

Determine whether desks were intended to have virtual allocation, weighted access to the shared wallet, reserved opportunity slots, exposure targets or priority budgets. The wallet remains one canonical wallet; do not recreate fake lane wallets. Prevent one lane from starving all others unless explicit intelligence/allocation policy causally chooses that state.

Required report:

```text
===== MEME SPECIALIST CAPITAL =====
lane
targetAllocation
availableAllocation
usedAllocation
openPositions
pendingIntents
capitalStarved
starvedByLane
allocationDecisionSource
```

## 0M. ADD DESIGNATED ROLE LIVENESS HEALTH

Required runtime section for every configured Meme desk:

```text
===== MEME SPECIALIST ROLE LIVENESS =====
QUALITY
 taskAlive=
 poolAlive=
 discoveryAlive=
 candidateN=
 qualifiedN=
 buyIntentN=
 fdgN=
 execN=
 positionInfluenceN=
 exitInfluenceN=
 learningN=
 capitalAvailable=
 status=ACTIVE/DEGRADED/TELEMETRY_ONLY/DEAD
```

Repeat for BLUECHIP, SHITCOIN, CYCLIC, EXPRESS, CORE, MOONSHOT, PROJECT_SNIPER, DIP_HUNTER, MANIPULATED, TREASURY, CASHGEN and every configured specialist.

A lane is DEAD if its designated task cannot causally change trading. It is TELEMETRY_ONLY if calculations run but cannot affect entry, size, position management, exit or learning. It is DEGRADED when only part of the designed causal path works. ACTIVE requires the entire intended path to be connected and runtime-proven.

## 0N. FINAL ACCEPTANCE REQUIREMENT

Do not declare Meme Trader repaired because trade count, `LANE_EVAL`, FDG allows or lane-name logs increase. For every specialist prove:

```text
DESIGNATED DISCOVERY
↓
DESIGNATED OPPORTUNITY POOL
↓
SPECIALIST QUALIFICATION
↓
SPECIALIST DECISION
↓
CAUSAL AGGREGATION
↓
EXECUTION OR EXPLICIT REASON NOT EXECUTED
↓
CANONICAL POSITION INFLUENCE
↓
SPECIALIST-COMPATIBLE EXIT
↓
OUTCOME LEARNING
↓
NEXT DECISION CHANGED
```

If any arrow is missing, that desk remains broken. The target is not “all lanes logging.” The target is every designated Meme Trader desk performing its assigned job.

## Mandatory implementation constraints

- Recover intent from source before code changes; produce confirmed/candidate hit lists with file, line, risk and butterflies.
- Repair existing authorities and desk modules in place; no replacement Meme architecture, duplicate scanner, duplicate registry, duplicate executor or fake lane wallet.
- Preserve one canonical chain+mint identity, one capital truth, one immutable entry intent, one position and one economic finality.
- Preserve catastrophic safety: rugs, raw hard-floor failures, unsellable quarantine and manual emergency liquidation.
- No hot-path LLM/API calls, global threshold reduction, lane shutdown, learned zero sizing or synchronous provider dependency.
- Paper and live share causal architecture and meaningful economics; live risk increases require live-clean StrategyTruth and confirmed wallet deltas.
- Every production patch requires lane-specific executable acceptance coverage, family-tree/mux checks, persistence checks, telemetry/report checks and Golden Tape updates in the same commit.
