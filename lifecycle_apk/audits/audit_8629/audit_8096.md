# AATE 5.0.8096 (cddf86c17) vs origin/main 5.0.8627 (2be34b02a): how the profitable build traded, and what has been added since

Audit only. The repository was not modified. The code was read from throwaway worktrees outside the repo (8096 = cddf86c17, 8114 = 379ad2fa0, main = 2be34b02a), which were removed afterwards.

Path prefix for every reference: `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. `8096:` means a line at cddf86c17 and `main:` means a line at 2be34b02a. Between 8096 and main there are 1,162 commits, 401 new Kotlin files and 1 deleted file. Over the same span BotService changed by +/-3.7k lines, Executor by +/-3.6k, Cortex7885 by +4.9k, LanePlaybook7907 by +1.4k and LiveEdgeGate7877 by +0.8k.

Caveats. This is a static reading of the code. I did not run anything and did not look at runtime logs, so throughput figures are derived from constants, not measured. A few FDG hard blocks listed in section 4 were not checked one by one for specialist-lane exemptions; they are marked where that applies.

---

## 1. Token discovery at 8096: sources, cadence, decisions per minute

**Scanner loop.** `8096: engine/SolanaMarketScanner.kt:1453-1693` (`scanLoop`)
- **Cycle interval:** `max(scanIntervalSecs*1000, 10_000)`, and `scanIntervalSecs = 8` (`8096: data/BotConfig.kt:248`), so a new scan cycle starts about every 10 s, plus the cycle's own run time.
- **Time limits:** each source has a 5 s timeout (`:958 SOURCE_SCAN_TIMEOUT_MS`) and the whole batch is cancelled at 8 s (`:971 SCAN_BATCH_BUDGET_MS`). A per-source circuit breaker applies (`:1119-1160`).
- **Every cycle, in parallel and equal-weighted (`:1554-1611`):**
  - pump.fun: `scanPumpFunDirect`, `scanPumpFunActive`, `scanPumpGraduates`, `scanFreshLaunches`, `scanPumpFunVolume`
  - DexScreener: `scanDexBoosted`, `scanDexTrending`, `scanDexGainers`, `scanTopVolumeTokens`
  - `scanRaydiumNewPools`
  - `scanSolanaBlueChipWatchlist`
- **Rotating every 4th cycle (`:1618-1626`):** Gecko trending pools, then Gecko top pools by volume, then Meteora via Gecko, then CoinGecko trending + established.
- **When the Birdeye budget allows (`:1627-1633`):** Birdeye trending, meme list, markets, new listings, narratives.
- **Fallbacks:**
  - If a cycle produced nothing, an emergency DexScreener-profile pass runs (`:1641-1643`).
  - If the watchlist drops below 100, the cooldown and saturation maps are cleared (`:1675-1683`).

**Other feeds**
- **ResidentHunterWorker7807** runs a market sweep every 45 s (`8096: engine/market/ResidentHunterWorker7807.kt:46`) and hands results back through `emitResidentHunt7807`.
- **LLM scout and Telegram calls** come in through `emitLlmScout7830` and `emitTelegramCall7830`.
- **Helius mention streams** were capped at 8 (`8096: network/HeliusWebSocket.kt:114`; reduced 16 to 8 in 8094 after an out-of-memory crash).
- **PumpPortal WebSocket creates:** a live create with no volume and an estimated liquidity under $1,500 is parked in probation as `coldPumpBase` (`8096: engine/BotService.kt:16474`).
  - Since 8048 changed that estimate to the curve's real SOL, essentially every live create was parked. This is documented on main in `engine/FreshLaunchIntake8303.kt` (header).
  - So at 8096, fresh pump.fun coins reached the lanes mostly through the REST scanners and the hunter, not the create firehose.

**Watchlist and loop cadence**
- **Watchlist cap:** `GlobalTradeRegistry.MAX_WATCHLIST_SIZE = 220` (`8096: engine/GlobalTradeRegistry.kt:136`). It is the same on main.
- **Main loop:** `pollSeconds = 5` (`8096: data/BotConfig.kt:120`).
- **Per-cycle window:** open positions, plus a 128-mint rotating window of candidates (`8096: engine/BotService.kt:21686-21700`).
- **Per-cycle supervisor slice:** 48 when healthy (`SUPERVISOR_HEALTHY_MEME_MAX_INFLIGHT`, `:24130`), 24 under pressure (`:24129`). It runs on a fixed pool of 16 `AATE-Entry-6647` threads (`:666-669`).

**Decisions per minute (derived, not measured)**
- The nominal ceiling is 48 token cycles per 5 s, about 575 per minute.
- The 16-thread pool and the 3-5 s cost of `processTokenCycle` hold the effective figure to roughly 12-16 per cycle, about 150-250 full lane evaluations per minute. The 8418 doc measured about 12 coins per 3 s loop.
- Every evaluated candidate also produces forward labels (section 7).

**Main is materially the same at this stage**
- The scanner sources, timeouts and caps are unchanged. Main adds an emergency fallback that reads the market sweep's snapshot first (`main: SolanaMarketScanner.kt scanEmergencySweepRows8128`).
- Main adds intake plumbing:
  - `ScannerIntake8292` (source identity)
  - `IntakeFirstSight8317` and `FreshLaunchIntake8303`, which re-admit the PumpPortal creates 8048 had parked
  - `PumpBandEntry8118` (admits a create when it enters the $10k-$100k band)
  - `ChainLaunchFeed8179`
  - `WatchlistEviction8398` (an undecided coin cannot be evicted before N evaluations)
  - `SupervisorWorkerBudget8418` (a lease is admitted only when one of the 16 pool threads is free)
- Discovery breadth is therefore not the regression. If anything, main sees more fresh launches.

## 2. Pre-lane filtering and ranking at 8096

**Scanner filter (permissive)**
- `passesFilter` (`8096: SolanaMarketScanner.kt:3959-4046`) runs the EfficiencyLayer liquidity and efficiency checks in shadow mode only ("emitting anyway").
- `passesFilterInternal` (`:4048-4124`) rejects only:
  - banned mints
  - impossible market caps
  - scam-pattern names (test, fake, scam, rug, honeypot, xxx, porn)
  - reserved symbols (sol, wsol, usdt, usdc, ray, jup)
  - names impersonating infrastructure
- There is no liquidity, market-cap or age floor at intake.

**Intake**
- `TokenMergeQueue` and `admitProtectedMemeIntake` dedupe and merge sources, and add a confidence boost when several sources found the same coin (`8096: BotService.kt processTokenMergeQueue`, about `:21641`).
- PumpPortal creates go through cold-pump probation (above).

**Ranking (`8096: BotService.kt:21701-21800`)**
- Open positions are scored +10,000.
- A never-processed coin gets +120 and a least-recently-processed boost of up to +180.
- Then:
  - freshness and source boosts
  - real pool USD /2,000 (capped at 60)
  - bonding-curve estimate /8,000 (capped at 15)
  - `CellAllocator7962.watchPriority7962`
  - buy pressure above 50% (capped at +35)
  - volume /3,000 (capped at 35)
  - a safety-freshness boost
  - +25 for a confirmed pool
- `selectOrderedMintsForCycle` (`:18587`) then takes the per-cycle slice.

**Per-token pre-lane exits in `processTokenCycle`** (`8096: BotService.kt:25764-34071`)
- Blacklisted coins: exit only.
- AutoMode PAUSED shapes size only and does not block (`:27242-27248`).
- Occupancy check: an OPEN, PENDING or IN_FLIGHT mint skips entry fan-out (`:27290-27315`).
- The circuit-breaker early short-circuit (`:27324+`).

## 3. Lane assignment and each meme lane's entry rule at 8096

**One primary lane per mint, version and cycle:** `canonicalCycleLaneFor` (`8096: BotService.kt:14861-~15000`, called at `:27316`). The order:
1. An operator-forced lane.
2. Otherwise the `AgenticStyleRouter` style lanes and the `ts.laneAffinity` lanes, filtered by `ToolkitSignalSheet.executableLanes7863`, `LaneEntryContract6342.isLaneIdentityEligible7252` and the cyclic policy.
3. A metric-stage proposal from `TokenMetricStageRouter`.
4. Desk hypotheses ranked by conviction, with +8 if the hypothesis matches the style primary, +6 if it matches the metric primary and +4 if it matches the coin's lane affinity.
5. CORE (the ensemble lane) wins when at least two lanes reach conviction 45 or more, the leader is below 75, and the gap is 10 or less (or the leader is below 65).
6. Otherwise a LaneHunter7297 claim, otherwise the metric primary, defaulting to SHITCOIN.

Each lane then runs its native brain and, if that brain says enter, calls `FinalDecisionGate.evaluate(..., specialistLane = <lane>)`:

| Lane | Native brain call (8096 BotService) | Native window / thresholds (8096 v3/scoring) |
|---|---|---|
| SHITCOIN | `ShitCoinTraderAI.evaluate` :29928, FDG :30140 | mcap $500-$500k; liq >= $300 (bootstrap) / $2k (mature); age <= 12 h (72 h bootstrap); score >= 15 / 48 and conf >= 10 / 40 (`ShitCoinTraderAI.kt:65-130, 2109-2114`); TP 12/20 %, SL -8/-6 % (lane exits, later overridden by the runner stack) |
| MOONSHOT | `MoonshotTraderAI.scoreToken` :29404, FDG :29523 | mcap $10k-$5M; liq >= $2k / $15k (`MoonshotTraderAI.kt:69,85-98`); hard floor -20 % (`:135`) |
| EXPRESS | `ShitCoinExpress` (shitcoin section) | mcap $1k-$300k; 5 min momentum >= 3 %; buy pressure >= 45 %; score >= 10 / 20 (`ShitCoinExpress.kt:50-86, 967`) |
| PROJECT_SNIPER | `ProjectSniperAI` | age 15-180 s; liq $2k-$250k; mcap $3k-$500k; buy pressure >= 48 %; change -5..+35 % (`ProjectSniperAI.kt:48-56`); max 8 concurrent missions |
| MANIPULATED | `ManipulatedTraderAI.evaluate` :30472, FDG :30509 | mcap $5k-$300k; liq >= $1.5k / $3k; age <= 12 / 8 min; manip score >= 18 / 32 (`ManipulatedTraderAI.kt:55-88`) |

- SHITCOIN's own enter flag combines the V3 hard-reject (`v3HardStopsShitCoin4233`), the coordinated-dump check (`AICrossTalk.isCoordinatedDump`) and `shitCoinSignal.shouldEnter` (`8096: BotService.kt:~30029-30031`). Paper-only bootstrap forcing does not apply in live.
- **Runner lanes** (`RunnerExitProfile7277.RUNNER_LANE_KEYS`, `8096: engine/RunnerExitProfile7277.kt:27-33`): MOONSHOT, SHITCOIN, MEME, EXPRESS, MANIPULATED, PRESALE, PROJECT_SNIPER, DIP_HUNTER, INSIDER_SHARK, COPY_TRADE, WHALE_FOLLOW, CORE.
- **Lifecycle-hold lanes:** MOONSHOT, QUALITY, BLUECHIP, CORE (`8096: engine/LifecycleHold8065.kt:LANES_8065`).
- **Tail-bag lanes:** SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER, MANIPULATED (`8096: engine/TailBag8034.kt:29`).

## 4. The live buy decision at 8096: every gate, in order

### 4a. Inside FinalDecisionGate.evaluate (`8096: engine/FinalDecisionGate.kt:1169-6025`)
In order:

1. **`PREFDG_HARD_NO_BUY_TERMINAL_7433`** (`:1260-1290`) — **binding.**
2. **Canonical V3 score floor** `CANONICAL_V3_SCORE_FLOOR_7243` / `CANONICAL_WAIT_PROMOTION_REFUSED_7243` (`:1380-1436`). The floor is fluid (CanonicalEntryFloor7266), maturing to 30, with a WAIT bar maturing to 55.
   - A specialist that already cleared its own floor is not re-floored (7292/7843).
   - The floor is **bypassed** by `playbookClearsFloor7937` (`:990-1019`) when any of these holds: the ladder has proven the launch (7932); the lane's own 5-minute labels are proven positive (7941: n >= 100, mean - SE > 0, at least 75% resolved); or the playbook score is above 50 and at the WAIT bar.
   - **Binding**, with those escape hatches.
3. **Fast block** for non-specialist WAIT signals (`:1551-1568`).
4. **`specialistAdvisoryBlock7845`** (`:865-897`, called at `:1574`):
   - **`LiveEdgeGate7877.liveRefusal`** (`:878`, via `liveEdgeBlock7877` `:1048-1075`) — **binding on every live path**, specialist lanes included.
   - FieldManual7715, TradePlan7739 and Council7740 bind only when no specialist lane is set. For the meme lanes, which always pass `specialistLane`, they are **advisory**: counted as `FDG_ADVISORY_*_7845` and never refuse (`:883-897`).
5. **`CellProofLadder7731`** (`:907-940`, at `:1575`) — binding. A live cell with 100+ labels and negative net expectancy refuses.
6. **`FreshLaunchSelector7737`** (`:942-975`, at `:1576`) — binding. A launch younger than 30 minutes with a structural crash shape, or whose cell hits -30% before +50%, refuses.
7. **The rest of the FDG hard stack** (all are `shouldTrade=false` returns; specialist-lane exemptions were not individually verified):

   | Line | Block |
   |---|---|
   | `:1587` | runtime overlay lane disabled |
   | `:1786` | symbolic universe |
   | `:1815` | zero-confidence live defer |
   | `:2018` | ML rug probability |
   | `:2099` | WR_ROLL50 collapse (runner lanes exempt via `WrRecoveryPartial.isRunnerLaneExempt7693`) |
   | `:2213` | SELECTION_QUALITY_FLOOR_6830 (stands down where its own graded refusals prove it refuses winners, 8094) |
   | `:2255` | inventory pressure |
   | `:2482` / `:2536` | liquidity floors |
   | `:2634` | circuit breaker |
   | `:2757` | re-entry guard |
   | `:2767` | zero liquidity |
   | `:2924` | sell pressure |
   | `:2933` | top holder |
   | `:2942` | safety hard block |
   | `:2954` | freeze authority |
   | `:3021` | liquidity under $500 |
   | `:3067` | rugcheck failed |
   | `:3084` | entry price unknown |
   | `:3150` | RSI overbought |
   | `:3330` / `:3347` | behaviour |
   | `:3517` / `:3552` | distribution |
   | `:3612` / `:3727` | edge veto |
   | `:3784` / `:3793` | narrative scam |
   | `:3824` / `:3832` | Gemini scam |
   | `:4011` / `:4017` | live auto-trade off / live quality too low |
   | `:4035` | liquidity collapse |
   | `:4086` / `:4091` | negative EV / high rug probability |
   | `:4921` | size too small |

8. **Sizing** (section 5) and the sealed execution intent (`:5062`, `:5085`).

### 4b. LiveEdgeGate7877.liveRefusal at 8096 (`engine/truth/LiveEdgeGate7877.kt:403-523`), the real live selector
All of it is LIVE only; paper is never refused. In order:

1. `LiquidityTruth8037.hydrate8037` and `OnChainDepth8038.request8038`, which read the pool from chain in the background.
2. `OnChainDepth8038.standDownIfTailProven8094`, then **`earlyLaunchRefusal8048`** (`:409-411`) — binding: an empty curve or a coin at its launch cap is refused.
3. **Live boot recovery:** `LiveBootRecovery8074.blind8074` (`:421-422`) — binding: if the wallet cannot be read, nothing is bought.
4. **`LiveTruth8064.setupRefusal8083`** (`:429-433`) — binding. It refuses only when **this setup's own live closes** prove it loses: 8+ closes in a 6-hour window (`LiveTruth8064.kt:28-29`; 8103 later lowered this to 5) with mean + SE < 0.
   - This replaced 8064's lane-wide refusal (8083 "no lane-wide deadlock"). The lane's live book is now a **size prior only** (`LOSING_LANE_SIZE_FACTOR_8083 = 0.6`, `LiveTruth8064.kt:45`).
5. **`TIMING_RECORD_NEGATIVE_8094`** (`:437-442`) — binding unless the coin's own cell or kind is proven (`cellOrKindProven8094`). Refuses when the picked setup's record at the current point of the move (pullback, mid, extended, peak) is negative.
6. **`PICKED_SETUP_RECORD_NEGATIVE_8094`** (`:447-452`) — binding unless a fired setup is proven or the cell/kind is proven. Refuses when the classified setup has 12+ labels with mean + SE < 0.
7. **`TACTIC_RECORD_LOSING_8094`** (`:457-462`) — binding with the same two exemptions.
8. **The 8059 / 8060 door** (`:469-486`). A live entry needs at least one of:
   - (8059) a **proven fired setup**: `LanePlaybook7907.provenSetup8056`, meaning 30+ graded labels with mean - SE > 0 (`LanePlaybook7907.kt:49,1001-1035`); multi-strategy, the best proven fired setup wins;
   - (8060) a **measured edge**: `measuredEdge8060` (`:638-655`), which is any of
     - the coin's cell proven (30+ labels, mean - SE > 0),
     - its kind proven (8063: venue x cap band, or narrative),
     - a runner cell beating its lane (`RunnerGrab7967.cellBeatsLane7970`: 20+ labels, mean - SE >= +15% at 5 minutes),
     - a promoted specialist (`SpecialistMiner7972`: a fact combination clearing +15% net after 2 SE on 25+ decisions),
     - an expert-wallet copy that clears the round trip (8062);
   - (8072/8073) a **runner ticket**: `RunnerTicket8073.admits8073` — a graduated PumpSwap coin at $10k-$100k, one ticket per 2 minutes, from a 10%-of-equity budget (`RunnerTicket8073.kt:27-31,59-82`);
   - (8080) a **bounded cold start**: the setup clears `coldStartSetup8080`, limited to 3 coins per 6 hours (`:575-636`).
   - **If none applies:** the gate asks whether the Cortex's own veto audit retires this door (`ownLaneVetoRefusesWinners8076`) or whether a Cortex STRONG read overrules it. If neither, it returns **`LIVE_PROVEN_SETUP_ONLY_8059`**, recorded as DEFER rather than a verdict on the token.
   - This door is **binding but wide**: five independent kinds of positive evidence open it. The 8060 commit says 8059 alone had refused 4,879 against 145 admitted and turned away POKEMON +1,077% and Gemini +708%.
9. **`CHASING_SPIKE_8065`** (`:491-500`) — binding unless the cell or kind is proven. It refuses a coin up 80% or more off its local low and within 12% of its high (`currentVsPeak >= 0.88`), or up 100% or more in 5 minutes (`chasing8065` `:535-537`).
10. **`watchFirst7994`** (`:505`, body `:683-756`). It clears on any of:
    - a runner ticket;
    - a cell with 10+ labels and a 5-minute mean >= 0;
    - a specialist;
    - a proven setup (8056, which also marks the coin to be held as a runner);
    - a RunnerPlay8018 re-entry ticket;
    - a TailHunter7996 ticket;
    - an audited admission when the cell is merely thin;
    - the veto audit retiring watch-first (8006);
    - a Cortex STRONG overrule (8016).

    Otherwise it refuses with `WATCH_FIRST_7994_THIN_CELL`, `_CELL_NEGATIVE` or `WATCH_FIRST_8019_BAND_NEGATIVE` (a lane x cap band with 20+ labels and mean + SE < 0).
11. **`liveRefusalCore7970`** (`:775-886`), in order:
    1. `Cortex7885.entryRefusal`, but only once the Cortex has earned refusal authority (`Cortex7885.kt:758-790`).
    2. **`RebuyLockout8019`**: no reopening a coin any lane closed in the last 10 minutes.
    3. **`NetEdge8030.entryRefusal8030`**: the expected move must be at least 2x the round trip (`NetEdge8030.kt:28,90`). Hard.
    4. **`laneFitRefusal8054`**: a scalp lane needs a coin moving 3x its cost in the last hour; a swing lane does not buy a coin already up 100%+ in 1 hour (`:115-137`).
    5. The Cortex refusal (when it has authority).
    6. **`provenLosingSetupRefusal8079`**: yields only to its own veto audit or to full Cortex authority.
    7. **`chartAdmits7950`**: a ChartReader BUY admits outright, unless the safety tier is HARD_BLOCK or the coin is Mayhem.
    8. **`MayhemMode7943.liveRefusal`**.
    9. A specialist match admits.
    10. **The playbook / stage / cost priors** (`LanePlaybook7907.liveRefusal`, `TokenMetricStageRouter.liveStageRefusal7928`, `CostLedger7962.liveRefusal7962`). Each one stands down when any of these applies: the veto audit retires it; a prior-only refusal is overruled by measured evidence; a lane-wide NO_TRIGGER record yields to a cell beating its lane; **a Cortex STRONG overrule (8019)**; or it is outweighed by a proven cohort measured on more labels (7948).
    11. `TradeShapeLearner7883` shape bins.
    12. **`verdictFor`** (`:163-214`). Cell bar: 30+ labels and mean - SE > +2%. Runner lanes judge on the best cohort, mean - SE > 0, or the tail test (100+ labels, mean > 0 and 10%+ runners). An **unmeasured cell explores (admits)**. Lane proven losing (7938) refuses unless a runner setup fires. Larger proven cohorts (7948) and setup authority (7955) admit.
    13. A Cortex STRONG overrule as the last word.
12. **On admit:**
    - The one immutable `Assessment8083` is recorded, carrying a size factor.
    - `LiveTruth8064.noteLiveAdmit8083` files the eventual close under this setup.
    - `RunnerGrab7967.noteAdmit7970`: an admit inside a proven runner cell is held like a runner and marked as a positive admit, which **skips TradePlan's plan wait** (`positiveAdmit7975`, 2-minute TTL).

**Binding versus advisory at 8096.** The live door was binding. It had about eight independent ways to open (proven setup, proven cell, proven kind, runner cell, specialist, runner ticket, cold start, chart BUY) and about four ways to have a soft refusal stood down (veto audit, Cortex STRONG, a larger proven cohort, an audited admission).
- Hard refusals were never overruled: safety, Mayhem, dead launch, wallet blind, net edge 2x, rebuy lockout, and a setup proven losing on live closes.
- The recurring pattern is a **refusal with self-correcting escape hatches**: every soft door had a closed loop that stood it down once its refused cohort was proven to win.

### 4c. Cortex at the buy (8096)
- **`entryRefusal`** binds only with earned refusal authority. That needs `board.refusalAuthority` plus `consistent()`, or the 8019 relative bar (REFUSE reads measurably below the lane's other reads while its STRONG reads hold authority) (`Cortex7885.kt:758-790`).
- **`overrulesEdgeRefusal`** (`:798-860`). It needs a STRONG bucket, a fresh mark, no HARD_BLOCK, a soft block and no Mayhem.
  - Authority must reach `OVERRULE_FRACTION_7955` (0.5) for a thin or prior refusal, and full authority (1.0) for a PROVEN_LOSING / NEGATIVE / CELL_NEGATIVE / BAND_NEGATIVE refusal.
  - It is refused when the selected setup's STRONG reads have lost (5+ graded, mean + SE < 0, 8094).
  - **FirstSight8026** lets a route-minimum runner probe through before authority matures.
- The Cortex was therefore mainly an **admit amplifier at 8096**: it cleared watch-first, prior and cohort refusals for STRONG reads. It refused only on proven authority.

### 4d. Executor live buy door at 8096 (`engine/Executor.kt liveBuy` :18340-~20300)
These are execution safety, mostly binding:

| Line | Check |
|---|---|
| `:18360` | sealed-size refusal |
| `:18606` | buy decision expired, rescore |
| `:18673` | route unavailable |
| `:18745` | rug blacklist |
| `:18883` | live mode desync |
| `:19109` / `:19142` | entry safety hold / mint re-entry cooldown (6312) |
| `:19352` | provider degraded |
| `:19389` | fee economic floor |
| `:19414` | advisor block |
| `:19434` | market snapshot missing |
| `:19576` | sniper not a launch (7385) |
| `:19583-19596` | TradePlan `chokepointRefusal7742`: **advisory for a sealed owner** ("continue_sealed_owner_buy") |
| `:19704` / `:19732` | token map incomplete / deferred |
| `:19747` / `:19804` | common-sense pre-buy |
| `:19859` / `:19892` | route / provider quorum |
| `:19981-19996` | executable open gate |
| `:20081` / `:20088` | restore-safety / true duplicate |
| `:20177-20204` | `PreTradeHardGate.requireLiveBuyAllowed` |
| `:20266-20276` | wallet capacity seal / SOL-USD missing |

`OnChainDepth8038.buyRefusal8038` is wired at `:13791`. There was **no last-mile chase or requote refusal** at 8096: the chase was handled at the gate (8065) and in the labels (8094 "graded at the executable price").

### 4e. What 8097-8114 (still in the profitable range) changed
From commit bodies; all in 379ad2fa0:
- **8098 / 8099:** 10 live positions per lane (from 1-3), with the 20-position book unchanged. `OpportunityRotation8098` lets a proven candidate refused only by the lane-slot cap sell the lane's poorest position. It never rotates a tail, a runner (peak >= +20%), a position under 3 minutes old, or one without a verified mark.
- **8103:** a live setup book counts as proven losing after 5 closes instead of 8.
- **8104:** live MOONSHOT, SHITCOIN, EXPRESS and PROJECT_SNIPER buys ride the RunnerGrab ladder (2x / 5x / 11x), the soft-exit hold and the moonbag **from entry**, whatever their style. A +40% to +199% peak keeps half its gain. Holds release losers that never ran. Rotation applies at the total cap.
- **8109:** lifecycle holds release unproven losers: a position that never reached +25% and has been red 45+ minutes or is at -20%, or whose pool liquidity has halved, or whose buy share is 25% or less.
- **8113:** "a confirmed run is its own proof". `runnerForming8113` (a re-entry ticket or a grab) passes the proven-only door and the chase rule; `shadowAuditAdmits8113` passes the proven-only door.

## 5. Sizing at 8096 (`engine/truth/LiveRiskPolicy7807.kt:451-550`)

**Lane budgets** (`:56-69`; slots, risk fraction at the stop, maximum position as a share of equity):

| Lane | Slots | Risk at stop | Max position |
|---|---|---|---|
| SHITCOIN | 3 | 1.5% | 20% |
| MOONSHOT | 3 | 1.5% | 20% |
| EXPRESS | 2 | 1.5% | 20% |
| PROJECT_SNIPER | 2 | 1.5% | 15% |
| MANIPULATED | 1 | 1.0% | 10% |
| CORE | 3 | 2.0% | 25% |
| QUALITY | 3 | 2.0% | 25% |

The total book is 20 (`LiveConcentrationDoctrine7697.kt:64`). 8099 raised every lane to 10 slots.

**How `decide()` builds a size**
1. A lane already at its slot cap refuses (`LANE_SLOT_CAP_7807`).
2. **Cost against the first target:** pass if the target is at least 2x the cost; halve the size if under 3x; refuse otherwise (`COST_CONSUMES_MOVE_7807`).
3. **Base size** = equity x risk fraction x multipliers / (stop + cost) (`sizeFromInvalidation`). The multipliers are:
   - the expectancy multiplier, 0.35-1.5;
   - the daily loss cap, the governor shrink and the admission multiplier;
   - drawdown, falling linearly to 0.35 at 30%, **waived for a proven-edge Kelly candidate (8094)**;
   - MOONSHOT with no plan: 0.6;
   - the cost multiplier;
   - the total is clamped to 0.15-1.5.
4. **Caps:** the lane position cap, and pool depth (half the liquidity x 3% one-way impact).
5. **Compounding (8057 / 8062 / 8063):** `edgeKellyFracFor8057` = **quarter-Kelly** of the best of: the proven setup's record (capped by its timing band's own Kelly, 8094), the proven cell's record, or the proven kind book. The formula is `0.25 x (mean - SE) / variance`, capped at 30% of equity (`:164-215`). When the Kelly size exceeds the risk size, Kelly wins (`PROVEN_EDGE_COMPOUNDING_SIZE_8057`).
6. **Route-minimum promotion:** if the size is below the venue minimum, the order is promoted to the minimum whenever it is fundable and its loss at the stop fits the hard per-trade cap (`OPEN_RISK_SAFE_MIN_PROMOTED_7840`, `:526-541`). The minimum may take up to 30% of equity at 0.25 SOL or less, falling to 15% at 1 SOL (`smallAccountMinShare7987`). The hard per-trade risk cap is 4%, rising to 8% on small accounts (`:97-100`, `:332-340`). No candidate-level reason was required.

**Modifiers**
- A runner grab is sized at least 1.5x, capped at 2.5x, for 10 minutes (`RunnerGrab7967.sizeMult7967`).
- A losing lane gets a 0.6x prior (`LiveTruth8064.sizeFactor8083`).
- The labels' default ticket is 0.0273 SOL (`NetEdge8030.kt:30`).

**Net effect.** Most unproven entries traded at the route minimum. Proven cells and setups compounded at quarter-Kelly of current equity. The drawdown shrink did not choke proven edges.

## 6. Exits per lane at 8096: how big winners were kept

**Mark path.** `SpikeCapture7943.rapidMark` (`8096: engine/SpikeCapture7943.kt:80-93`) runs every 500 ms on the rapid monitor and on every curve or Helius trade print. In order:
1. **`LifecycleHold8065.onMark8065` runs first.** If the position is held, it runs only the lifecycle exits and returns, so the spike tiers never fire for a held position.
2. Otherwise `SpikeCapture.onMark` (only on a mark less than 5 s old).
3. `ChartReader7950` exits (never in the first 90 s).
4. `RunnerGrab7967.heldExit7967`.
5. `StructureTracker7962` (shadow).

**SpikeCapture7943 tiers** (`:20`): +40% sells 60% of the current holding, +120% sells 70%, +400% sells 70%.
- A learned ExitProfile7955 ladder replaces these when present.
- A grabbed runner uses `RunnerGrab7967.TIERS_7967` instead: **+100% sells 50%, +400% sells 35%, +1,000% sells 35%** (`RunnerGrab7967.kt:49`).
- `RunnerPlay8018.onMark8018` promotes a firming run (structure at +25%) to the runner hold before the +40% scalp rung.
- Skipped tiers are sold as one compound fraction (8079). A failed sell re-arms the tier up to 16 times.

**RunnerGrab7967** (`8096: engine/RunnerGrab7967.kt`), the "grabbed" runner hold
- **Soft-exit deferral** (`deferSell7967` `:359-389`). Soft exits are deferred while all of these hold:
  - the position is above -35%;
  - a red position is in its first 3 minutes and above -15% (7991);
  - the 1-minute structure is unbroken;
  - a red position, or a green give-back exit, has a standing tape (8020 / 8080).
- **Own exits** (`heldExit7967` `:410-443`): a -35% hard stop; peak capture (after a +200% peak, sell when price is 35% under the peak price, 7980); a 1-minute structure break; a 15-second structure break once at +50% or more.
- The hold window is 6 hours (`:43`). It adds on confirmation: up to 2 adds on new higher-high/higher-low swings while in profit (`:281-307`).

**LifecycleHold8065** (`8096: engine/LifecycleHold8065.kt`), the key "hold big winners" contract
- **Who is held** (`holding8065`): a live position whose sealed style is not short (`TradeExitContract8077.longHold != false`, and unknown counts as hold), and that is in one of:
  - **lanes MOONSHOT, QUALITY, BLUECHIP, CORE from entry;**
  - **any live runner ticket;**
  - **any live position promoted at +100%** (current and peak gross both >= 100, `onMark8065` promotion).
- **Exits while held:**
  - a **-40% floor**;
  - a profit giveback after a +300% peak (price back to 45% of the peak multiple while still +20% or more);
  - **cumulative takes: 50% at +100%, 30% of the remainder at +400%, 30% at +900%**;
  - an unbanked giveback (a +100% peak, nothing taken, now at half the peak);
  - a 15-minute base break (red past -15% after 2 hours, with the last closed bar under the prior 16-bar low);
  - a trend break after the first take (24 bars) or a peak giveback;
  - dead money (72 hours old, between -15% and +20%, no new high for 48 hours).
- **`defer8065`:** every ordinary exit reason (stop, time, profit lock, anything that is not structural or emergency) is **deferred at the sell door** (`Executor.requestSellCore7948` `8096: Executor.kt:22950-22957`) while the position is above -40%. The exception is a promoted unbanked giveback (8080).

**Sell door order at 8096** (`Executor.requestSellCore7948` `:22950+`):
1. LifecycleHold defer
2. RunnerGrab defer
3. BasisBreak8019 (a 1000x mark is a broken basis)
4. DustBagLatch
5. `NetEdge8030.exitRefusal8030`: a green profit exit below round trip + 1% is held
6. … then the **`moonbagGate7322`** (`:24204+`), which runs `tailBagGate8034` first.

**TailBag8034 (the moonbag)** (`8096: engine/TailBag8034.kt:27-31`)
- On a live meme-lane position, the **first** ordinary exit sells 75% and **keeps 25% as a tail**.
- The tail ignores every ordinary exit. It sells only on a structural exit (rug, liquidity, dev, honeypot, freeze, manual, emergency …) or at multiples: a third at +400%, half the rest at +1,900%, the rest at +4,900%. After 7 days it is released.
- Tails take no lane slot.

**MoonbagRunner7322** (`8096: engine/MoonbagRunner7322.kt`)
- A give-back or take-profit exit on a runner lane whose peak reached +50% banks 60% (35% on MOONSHOT) the first time it fires.
- The moonbag then ignores give-back exits while it holds 50% of the peak gain or more.

**Runner give-back locks** (`RunnerExitProfile7277`)
- A runner lane's give-back locks do not arm below a +20% peak (+50% for MOONSHOT).
- A runner lane cuts on the first strike at -20% inside its first 2 minutes.
- The exit tuner may not pull a runner lane's take-profit multiplier under 1.0.

**Native lane exits** still exist (for example MOONSHOT's -20% hard floor, ShitCoin's -8/-6% SL and 8-minute flat exit, Express -8% with a 5% trail, Manipulated a 4-minute force exit). For live positions they are overridden by the holds above.

**How the +600% / +1,100% runners were kept at 8096.** Several layers stacked:
- A MOONSHOT or CORE buy, or any runner ticket, was lifecycle-held **from entry**: its stops sat at -40% and the 40%-sells-60% spike tier never ran on it.
- Any other live position that touched +100% was promoted into the same hold.
- Held positions sold only 50% at 2x, then 30% at 5x and 30% at 10x.
- Every meme position also kept a 25% tail that only a rug or +400% / +1,900% / +4,900% could sell.
- Grabbed and proven-cell runners deferred soft exits on structure.
- The cost was that losers in hold lanes could also ride to -40%. 8104 and 8109 trimmed that inside the profitable range.

## 7. Learning at 8096, and what it controlled

**`ForwardReturnLabeler7731`** (`8096: engine/truth/ForwardReturnLabeler7731.kt`)
- Labels **every** candidate the FDG ruled on, whether admitted or refused.
- Read slots: "15" is 1-2 minutes, **"60" is 5 minutes (the read everything uses)**, "240" is 60 minutes (`:62-64`).
- Labels are net of the Field Manual all-in cost at a $5 ticket (`:86`); a runner is a +50% move (`:88`).
- Cells are keyed source family | lane | mcap band | age band, plus lane x stage, kind (venue x cap) and narrative books (8063), and band books (8019).
- 8064 "honest labels": no spike credit, fresh books. 8071: the lane ladder is credited on a level the price held for 20 s. 8076: labels rebase to the first reachable mark.
- **Controls:** the cell, kind and band doors in LiveEdgeGate, watch-first, the cohort overrules, the Kelly fraction, and the V3-floor bypass (7941).

**`LanePlaybook7907`** (1,554 lines at 8096)
- Holds each lane's setup menu, plus the seven price-shape setups added in 8061, graded per lane on the labels.
- `provenSetup8056`: 30+ labels with mean - SE > 0, multi-strategy (the best proven fired setup).
- `provenLosingSetupRefusal8079`, `timingNegative8094`, `pickedSetupNegative8094` (12+ labels), `coldStartSetup8080`.
- **Controls:** the 8059 door, watch-first, timing and picked-setup refusals, Kelly size, and the trade's sealed style and exit (8094).

**`Cortex7885`** (1,546 lines at 8096)
- A voter fusion into REFUSE … STRONG buckets, graded on the same labels. Authority is earned per lane at 100 to 300 STRONG grades (8082), with a decaying per-book memory.
- **Controls:** refusal only with earned authority; mainly STRONG overrules of soft doors, and FirstSight probes.

**`LiveTruth8064`**
- The live realised book per lane and per setup, over a 6-hour window, honest-era closes only (8070).
- **Controls:** the setup-losing refusal and a 0.6x lane size prior.

**Other learners**
- `SpecialistMiner7972` (promoted fact combinations, which admit live).
- `TailHunter7996` (4-hour ladder replays; tickets were taken off live in 8064 but still clear watch-first).
- `RunnerGrab` grab record, `RunnerPlay8018` re-entry record.
- `StopLearner8040`, `ExitProfile7955` (learned spike ladders), `TradeShapeLearner7883`.
- The `Cortex7885.vetoRefusesWinners8006` veto audit: each soft door's refused cohort, graded per lane; once 40+ refusals are proven winners (mean - SE > +2% net), **the door stands down automatically**.

**Net.** Learning at 8096 mostly **opened** doors. A gate fell away when the bot's own labels showed it was refusing winners, and a proven edge compounded size. Refusals needed proof of loss on the candidate's own setup or cell.

---

## 8. Mechanisms on origin/main that did not exist at 8096, on the live buy or exit path

Already present at 8096 and modified since (not new): LiveEdgeGate7877, FinalDecisionGate, FieldManual7715, LanePlaybook7907 (+1,094 lines), Cortex7885 (+4.9k), LifecycleHold8065, TailBag8034, SpikeCapture7943, RunnerGrab7967. "New" below means the file is absent at cddf86c17.

The 8097-8114 additions (OpportunityRotation8098, TradeTape8100, the 8104 runner-from-entry rule, the 8109 hold release, 8113 runnerForming) are inside the profitable range and are not suspects.

### 8a. Live admission (buy path)

| Mechanism | Where it sits on main | What it does to a live buy |
|---|---|---|
| **CandidateAdmission8136** (1,744 lines) | `LiveEdgeGate7877.canonicalRefusal8392`, FDG `:1166-1180`, `:1715-1736` | One ADMIT / REFUSE / DEFER_DATA record per candidate (`decide8136` `:500-930`). Adds refusals: SAFETY, LIVE_REGIME_COLD, STRUCTURAL_RISK, DATA_INCOMPLETE defer, ENTRY_*_8506 waits, SETUP_RECORD_LOSING, SHEET_AVOID_8323, CELL / STAGE proven losing, TACTIC, TIMING_ADVERSE, FALLING_HOUR, EXECUTION_ECONOMICS, EDGE_MODEL_NEGATIVE, **GENERIC_SCORE_FLOOR_8136** (`:850`), LIVE_INTO_HIGH_UNPROVEN_8545, LIVE_LAUNCH_UNPROVEN_8559, LIVE_NATIVE_OPINION_UNPROVEN_8536, LIVE_GENERIC_PRIOR_UNSUPPORTED_8238, NO_SUPPORTED_EDGE. |
| **LaneAuthority8577** | `CandidateAdmission.apply8577`, `LiveEdgeGate.liveRefusal` `:531-535` | Makes learned doors **advisory** for a "lane-qualified" candidate, which requires native-qualified **and V3 score >= generic floor** (`specialistQualified8539`, `CandidateAdmission8136.kt:974-975`). Keeps a binding list (`LaneAuthority8577.kt:26-40`) that includes new hard refusals: `MEME_BUYS_THE_HIGH_8582`, `LIVE_STAGE_LOSES_8626`, `LIVE_RUNNER_STALE_COIN_8627`, `PAPER_LANE_PROVEN_LOSING`, `GENERIC_SCORE_FLOOR`, `DATA_`, `STALE`, `_FAILED`, `REBUY`, `BIG_LOSER`, `REPEAT_LOSER`, `ROUTE_MIN`, `LANE_LIQ`. |
| **MEME_BUYS_THE_HIGH_8582** | `LaneAuthority8577.kt:96-101` (binding) | Refuses any meme-lane live buy within 3% of its peak after a 5%+ run-up, **or with buy pressure >= 60%**. There is no proven-cell bypass (8096's chase rule was at 80% run-up within 12% of the high, waived for a proven cell or kind). |
| **LIVE_STAGE_LOSES_8626** | `LaneAuthority8577.kt:120-123` (binding) | Refuses PEAK_EXHAUSTION and DUMPING stage entries unless the lane's own stage record has n >= 30 and mean - SE > 0. Its own doc says these stages are about three-quarters of what the lanes are offered. |
| **LIVE_RUNNER_STALE_COIN_8627** (today) | `LaneAuthority8577.kt:137-138` (binding) | Runner lanes refuse any coin whose known launch age is over 24 hours. |
| **PAPER_LANE_PROVEN_LOSING_8608** + **MemePaperTrader8544** | `LaneAuthority8577.kt:212-216` | Refuses a **whole lane live** when its separate paper book is proven losing (unless PocketLedger says PAYS). This is a lane-wide shutdown of the kind 8083 removed as a deadlock. |
| **EvidenceHold8336** | `LiveEdgeGate.kt:495-497` | Refuses LIVE in a lane until its paper record since the hold started proves itself. **Off on main** (`enabled8336 = false`, `EvidenceHold8336.kt:41`); the read still runs, and a failed read returns `EVIDENCE_HOLD_READ_FAILED_8392`, which matches the binding `_FAILED`. |
| **EntryEconomics8329** | `LiveEdgeGate.kt:496` (lane realised), `Executor.kt:20999, 31419-31429, 32278-32294, 32498` (pre-broadcast) | (1) Shuts a lane after 3+ live closes with mean < 0 (8424 turned this into a probe slot). (2) **The last-mile chase cap**: the fresh quote against the sealed decision price must have moved less than 8% (launch / breakout), 5% (momentum), 4% (default) or 2.5% (pullback), and the remaining move must clear 1.5x the round trip, or the buy is not sent (`CHASE_CONSUMED_8329`). Also adds `jupiterQuoteRefusal8329`, `jupiterFloorRefusal8521`, `executableNetRefusal8521` and `pumpFinalRefusal8521` before signing. |
| **ExploreShadow8347** (1,580 lines) | `LiveEdgeGate.kt:522-523` | Niche refusal (`nicheRefusal8351`). The live entry waits for the lane's best shadow variant's PULLBACK / CONFIRM entry condition (`liveEntryWait8347`, `ExploreShadow8347.kt:1451-1471`); `ENTRY_TIMING_EXPIRED_8347` when it never comes. |
| **sourceRefusal8536 / unprovenLive8536** | `LiveEdgeGate.kt:570-649` | Refuses when every source that found the coin is proven losing (30+ labels, mean + SE < 0). Refuses `LIVE_UNPROVEN_EDGE_8536` unless there is specific proven evidence, an `edge8114Live8538` edge, or a runner ticket. |
| **edge8114Live8538** | `LiveEdgeGate.kt:600-622` | The 8538 attempt to **restore** the 8069-8114 edges (proven setup, measured edge, cell / kind, runner forming, runner ticket, shadow audit, chart BUY, Cortex STRONG), plus new MemeStrategies8546 family-proven and paper-lane-proven edges. |
| **FdgGate8365 / Rehearsal8339** | `LiveEdgeGate.kt:491-520`, FDG | Memoised per-question entry check (`memoKey8365` by mint, lane and candidate version); demotes duplicate evidence doors by basis; "rehearsal" makes paper decide like live. |
| **EdgeModel8537** | `CandidateAdmission.edgeModelRefusal8537` `:985+` | A learned ridge-regression expected-net scorer. With out-of-sample authority it refuses predicted losers (advisory for a lane-qualified candidate) and its positive picks count as proven (`EDGE_MODEL_PROVEN_8537` admit). |
| **PocketLedger8610** | `LaneAuthority8577.liveCoinStateRefusal8609` | Coin-state cells learned from labels. On main it is **advisory** (trace `advisory8620`); it exempts a lane from the paper-losing shutdown when it reads PAYS. |
| **MemeRegime8546** | `CandidateAdmission8136.kt:519-522` | `LIVE_REGIME_COLD_8546`: refuses live meme entries in a "cold" market unless a paper-proven strategy family carries the coin (demotable / advisory once lane-qualified). |
| **MemeStrategies8546** | `edge8114Live8538` | Five event families read from 1-minute bars; a family proven in paper is a live edge. |
| **LaneCheatSheet8323** (824 lines) | `CandidateAdmission` (`SHEET_AVOID_8323`), `NativeLaneConstraints8521` / `NativeLaneAge8521`, `TokenMergeQueue` routing, LaneHunter7297, Cortex wide voters | A per-lane sheet: mcap, liquidity and age bands, source-to-lane routing, setups to avoid. Feeds lane routing, native constraints (role mcap / liquidity / age floors) and the SHEET_AVOID refusal. |
| **LaneTrigger8468** | LiveEdgeGate | Each lane is admitted on its own trigger judged at its own horizon, instead of the shared setup menu. |
| **SelectionDoors8310 / LiveAdmissionProof8290** | LiveEdgeGate | Re-check doors (runner ticket, kind books, outlier-suspect cells) against the realised bank record before they may admit. Runner tickets are persisted and stand down on a losing record. |
| **RegressionRestore8298** | LiveEdgeGate / FDG | Re-binds the 8114-era learned doors that 8201-8256 had let a CandidateAdmission ADMIT stand down. |
| **NativeOpportunityPolicy8522 / CortexOpportunityPolarity8522** | BotService native opinions | An "OPERATOR_INVERSE" policy. When a lane's Cortex rank is verified INVERTED (60+ rows), it can **flip the native verdict**: take Cortex-REFUSE candidates and refuse Cortex-STRONG ones. |
| **SpecialistElection8136 / TicketExec8304 / LaneDispatch8418** | BotService / FDG | One election per candidate (the named yield receiver, READY hand-off rules); a sealed ticket executes or ends by name once; FDG size-zero exits. |
| **LearnedLiquidityFloor8539 / LaneAgeLiquidityTruth8140 / SafetyLiquidity8522 / BuyRouteSafety8522 / ExecutableEntryFloor8521** | FDG / Executor | Liquidity floors per lane x band, learned from labels; chain-read age and liquidity, so an unread pool **defers**; route safety and executable-net floors before the buy. |
| **CortexAuthority8366 / CortexInversion8310 / LaneRealisedGovernor8295 / LaneRealisedProbe8424** | Cortex / LiveEdgeGate | Rebuilt Cortex authority (one walk-forward test), inversion detection, live realised closes governing lane authority, and a losing-lane probe slot. |

### 8b. Sizing

| Mechanism | What it does |
|---|---|
| **Kelly8365** | Log-optimal Kelly on per-trade returns, replacing 8057's mean-variance quarter-Kelly; `KELLY_TOO_SMALL_FOR_ROUTE_8365` was made non-binding by SmallStake8370. |
| **SmallStake8370** | Restores 8114's route-minimum promotion without a reason (the 8329 promotion refusal is now a label). |
| **LiveConfidenceSizing8296** | Paper may only shrink a live order, never grow it. |
| **ExceptionalEntryBudget8397**, **LiveSizingTruth8136**, **SizingNetEdge8153** | An in-flight exception budget, sizing truth, and a net-edge sizing check. |

### 8c. Exits

| Mechanism | What it does to an exit |
|---|---|
| **LifecycleEvidence8189** | **A lifecycle hold now needs runner evidence** (`holds8189`): a chain-verified, two-feed-corroborated +100% peak, or a sealed runner style plus a proven setup. MOONSHOT, QUALITY, BLUECHIP and CORE are **no longer held from entry** (`LifecycleHold8065.defer8065`, `main :253+`). Unproven positions take lane stops and the scalp ladder. |
| **RunnerExitContract8136 / RunnerExitLive8136** | Runner eligibility comes from the sealed entry style or a proven promotion, not the lane (this reverses the 8104 rule). One residual-share budget for every discretionary partial. Tier-1 exits are never held. Unfunded tails are not set aside (`TailBag.funded8136`). |
| **RunnerDoctrine8584** | For SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER, MANIPULATED and DIP_HUNTER: the ladder is +100% sells 30%, +400% sells 35%, +900% sells 35%. Profit exits are held above a doctrine line (half the peak gain below 2x; a 45% give-back trail from 2x). |
| **RunnerTrail8419 / GreenFloor8363 / Exits8363** | A runner trails 35% under its peak price. A green floor releases lifecycle holds and tails once breached. Profit locks are classed as protective. |
| **ExitDoctrine8169** | Stop floors at the Field Manual invalidation, the token's 1-minute range and the round trip. Per RegressionRestore8298 this **widened loss backstops** (fresh meme 9% up to 25%, the hard floor too). |
| **LaunchWindow8379** | Defers loss stops on a coin in its launch window (`defer8379`, second in the sell door). |
| **StopMarkConfirm8418** | A loss stop on a non-chain, non-quote mark asks the executable price first; `stopRefutedByVenue8489` holds the stop. |
| **CostBandExit8536 / ProfitExitTruth8163 / ProfitEscalation8189** | No discretionary exit inside the cost band. A profit slice that cannot bank is escalated to a full exit or kept. |
| **ScalpExit8351 / LaneExitContract8468 / LaneHolds8418 / ExitThresholds8418** | Scalp exits for lanes whose shadow proved scalps. Fast lanes exit on their designed contract until proven to run. Per-lane max holds and trails. One binding value per threshold. |
| **ExitTruthArbiterLive8144 / StopFast8288 / SellRouteSpine8144 / SellDoor8435 / DeepRedConfirm8116 / HonestExit8366** | Per-tick exit arbitration, faster emergency sells, the sell route spine and sell door, rug confirmation without a quote, and labels graded at the first mark past the stop plus slippage. |

### 8d. Learning resets and label changes that feed the doors
- **`LearningEpoch8164`**: a durable reset line, so the journal no longer re-teaches. **`CortexForwardEpoch8521`**: a separate corrected forward contract.
- **`HonestExit8366`**: stops are graded at the first observed mark at or below the stop, plus measured latency slippage, and the ladder no longer gets the fixed 20 s credit.
- Effect: labels are more pessimistic and evidence books restart, so fewer cells and setups clear "30+ labels, mean - SE > 0". That shrinks every proven door and the Kelly size.

---

## 9. Top 10 post-8096 additions most likely to have degraded trading

These are ranked by expected impact on catching and keeping runners. Each one is a code-level argument; none has been confirmed against runtime counts.

1. **The lifecycle hold now needs evidence (LifecycleEvidence8189 + RunnerExitContract8136).**
   - At 8096, MOONSHOT, CORE and runner-ticket buys were held from entry, and any live position at +100% was promoted. Ordinary stops sat at -40%, and the hold suppressed the 40%-sells-60% spike tier. That is the mechanism that kept +600% and +1,100% bags.
   - On main, only a chain-verified, corroborated +100% peak or a proven setup earns the hold. A fast runner whose peak is not corroborated, or that never prints a verified +100% before a lane stop or scalp tier fires, is sold early.
2. **MEME_BUYS_THE_HIGH_8582 (binding, no proven bypass).**
   - It refuses any meme buy within 3% of its peak after a 5% run, or with buy pressure of 60% or more.
   - A breakout runner is at its high with buyers in control by definition. The 8096 rule (80% run-up within 12% of the high, waived for a proven cell or kind; 8113 also passed confirmed runs) let those through.
3. **The last-mile chase cap (EntryEconomics8329.preBroadcastRefusal8329).**
   - Launch and breakout entries are cancelled when the quote has moved more than 8% since the decision; pullbacks at 2.5%, momentum at 5%.
   - Fast runners move more than that between decision and quote, so the buys the 8096 runner rules were designed to take are aborted at the signing step.
4. **The V3 generic score floor is binding again, and gates "lane qualification" (CandidateAdmission8136 `:850` + `LaneAuthority8577.specialistQualified8539`).**
   - At 8096 the floor had bypasses (ladder-proven, lane labels proven, playbook score) and the live door opened on cell, kind, runner-cell, specialist, ticket or chart evidence.
   - On main, a coin must clear V3 >= floor to be "qualified", and only then do learned doors become advisory. The 8136 doc says V3 scores fresh launches 9-32, so fresh launches are precisely the coins most often under the floor.
5. **LIVE_STAGE_LOSES_8626 + LIVE_RUNNER_STALE_COIN_8627 + LIVE_REGIME_COLD_8546.** These are coarse, binding (8626 / 8627) or demotable (8546) refusals on whole stages, coin ages and market regimes.
   - 8626 by its own doc covers about three-quarters of what the lanes are offered.
   - Coarse aggregates override the candidate-specific evidence that 8096's design put first ("the narrower evidence wins").
6. **PAPER_LANE_PROVEN_LOSING_8608 (MemePaperTrader8544).** A separate paper book can shut a whole lane in live. This recreates the 8064 lane-wide deadlock that 8083 explicitly removed: a shut lane trades nothing live, so its live record cannot recover.
7. **Too many interacting doors, mostly scored on harsher labels.** Main has CandidateAdmission8136 (about 25 terminals), EntryEconomics8329, EvidenceHold8336 (off, but a failed read binds through `_FAILED`), ExploreShadow8347 waits and niches, unprovenLive8536, sourceRefusal8536, SelectionDoors8310, RegressionRestore8298 (re-binds doors) and FdgGate8365, with LaneAuthority8577 then trying to make many of them advisory.
   - The net is still a long series of independent refusals.
   - Every DATA_, STALE or _FAILED string binds, so data gaps on brand-new coins refuse instead of exploring. At 8096, "an unmeasured cell explores".
8. **HonestExit8366 + LearningEpoch8164 / CortexForwardEpoch8521 resets.** Pessimistic labels and restarted books make "30+ labels, mean - SE > 0" much rarer. Proven setups, cells and kinds (the 8059 / 8060 / 8094 doors), Cortex STRONG authority and the quarter-Kelly compounding all fire less, so admits fall and sizes sit at the route minimum.
9. **Runner-from-entry rule reversed and tail funding (RunnerExitContract8136, `TailBag.funded8136`), with RunnerDoctrine8584 / ScalpExit8351 / LaneExitContract8468 layered on top.**
   - Runner eligibility now comes from the sealed style instead of the lane (8104 had made every live meme buy ride the 2x/5x/11x ladder plus a tail).
   - At route-minimum sizes the 25% tail is often "unfunded" and never set aside.
   - Fast lanes exit on their designed scalp contracts until proven to run.
   - Together these remove the free tail that caught +1,000% moves at 8096.
10. **NativeOpportunityPolicy8522 (the inverse Cortex) and ExploreShadow8347's live entry wait.**
    - The first can flip a lane's native verdict on a 60-row inversion statistic.
    - The second makes live entries wait for a shadow variant's pullback or confirmation and then expire (`ENTRY_TIMING_EXPIRED_8347`).
    - Both move the entry away from the moment the lane's own brain fired, which is when 8096 bought. On a runner that moment does not come back.

**Honourable mentions** (loss side, mixed). ExitDoctrine8169's widened stops, the LaunchWindow8379 stop deferral and StopMarkConfirm8418 / `stopRefutedByVenue8489` all hold losers longer. EdgeModel8537 can refuse unqualified candidates once it has authority.
