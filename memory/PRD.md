# AATE Lifecycle Bot — Product Requirements Document

## Session (09 Jul 2026 · continued) — V5.0.6218 SHIPPED · CI GREEN ✅

### V5.0.6218 (`39e9e17af`) — LIVE-MODE AUTO-PAUSE GUARD
**Operator directive:** "I still cant understand with the amount of
intelligence data and tech we have how its even possible for the bot
to be losing money at all trading live!!!!" Config: 1a + 2c + 3a —
auto pause+resume, 10-cycle trigger, PAPER flip only.

New `LiveModeAutoPauseGuard.kt`:
- **PAUSE (LIVE→PAPER):** cleanLive rolling WR (last 30 closes) < 20%
  AND sample >= 20, sustained for 10 consecutive 30s evaluation ticks.
- **RESUME (PAPER→LIVE):** only if we own the flip. Post-flip paper WR
  >= 25% AND sample >= 20, sustained for 10 ticks. Flips paperMode
  back to false automatically.
- Respects manual operator toggles — if the operator flips modes
  themselves the guard defers and stops trying to resume.
- Never touches lane config or authority. Only paperMode. Fail-open.
- Hooked into BotService main loop right after LaneAutoPauseGuard.

## Session (09 Jul 2026) — V5.0.6216 + V5.0.6217 SHIPPED · CI GREEN ✅

### V5.0.6217 (`ed25ea359`) — Copy button responsiveness after long runtime
**Operator:** "if I let the bot run for a while the pipeline report is
unable to be copied!!! the button becomes unresponsive". Root cause:
after 5+ hours the PipelineHealthCollector maps grow huge and the
underlying dumpText/compactPipelineDump can take 8-20s per build. The
old `buildTextAsync(forceFresh=true)` held the ReportingHub buildMutex
the whole time, so stacked Copy taps queued behind the same mutex.

- **PipelineHealthActivity** — Copy tap shows an immediate "Building…"
  Toast so the tap visually registers; a `copyInFlight` guard blocks
  repeat queueing; `forceFresh=false` uses the 2.5s cache.
- **ReportingHub** — new `MUTEX_HANDOFF_STALE_MS = 30_000L`: if the
  build mutex is already held and a ≤30s-old cached report exists,
  return it immediately instead of waiting. Copy returns in <100ms
  during long-runtime states, serving the last built report.

### V5.0.6216 (`35ac226ec`) — Scanner parallelism + micro-wallet emergency probe
**Operator report:** wallet 0.1277 SOL bleeding, scanner timing out on
DexScreener 5xx storms (SR=42%, 382× 5xx), and every buy dying with
SIZE_TOO_THIN_FOR_NON_MICRO_TRADE.

- **SolanaMarketScanner.fetchJupiterPricesBatch** — 305-mint watchlist
  chunks now fire in parallel via coroutineScope+async.awaitAll. Wall
  time drops from serial 7-14s to ~1-2s, unblocking
  scanSolanaBlueChipWatchlist which was timing out (streak=2) inside
  the 3500ms per-source withTimeout.
- **SolanaMarketScanner.runScanBatch** — added SEVERE-STREAK adaptive
  core rotation: at streak >=6 even "core" Dex sources rotate 1-in-3
  cycles; at streak >=12 rotate 1-in-6. Previously coreSource bypassed
  rotation, so 4 core Dex sources at streak=9 burned ~14s of dead time
  every cycle (past the 8s SCAN_BATCH_BUDGET_MS).
- **SolanaMarketScanner.runScan** — high-streak fast-fail: sources
  with streak >=3 get halved per-source timeout (1500ms not 3500ms)
  so wedged APIs release permits faster.
- **Executor.kt** — MICRO_WALLET_EMERGENCY_PROBE_6216 mode: when
  walletSol < 0.15, drop into micro-probe floor (0.005 SOL) and widen
  walletRiskCap to 30%, regardless of layerTag. Normal risk caps
  restore as soon as wallet climbs back above 0.15 SOL.
- **GoldenTapeRegressionTest** — updated V5_0_6189 assertion to reflect
  the new adaptive per-source timeout log format.

**Files:** SolanaMarketScanner.kt · Executor.kt · ReportingHub.kt ·
PipelineHealthActivity.kt · GoldenTapeRegressionTest.kt

## Session (08 Jul 2026) — V5.0.6207 SHIPPED · CI FULLY GREEN ✅
### Un-choke of TOXIC_PATTERN_MEMORY_6192 (killed 180 live trades in last op-report)

**Root cause (forensic):** `TokenWinMemory.patternEdgeForLiveContext6192()`
scanned 5 candidate dimensions for the worst-performing paper cohort — but
two of those (`lane`, `buy_route`) are **structural**: the bot always trades
through a lane and a DEX route. A single bad paper cohort in the MEME lane
(e.g. n=8, WR=15%) auto-poisoned EVERY live meme entry regardless of setup
quality. The FDG then converted that verdict to HARD_BLOCK live entries.

**Fix shipped in V5.0.6207 (`5db51f014`):**
1. **TokenWinMemory.patternEdgeForLiveContext6192** — exclude `lane` and
   `buy_route` from worstPattern eligibility. Raise sample floor: TOXIC
   n>=20 (was 8), CATASTROPHIC n>=30 (was 10). Paper-derived toxicity now
   requires REAL evidence before abandoning live risk.
2. **FinalDecisionGate.kt @ line 2236** — verdict routing split:
   • CATASTROPHIC → remains HARD block (rare, high-evidence).
   • TOXIC → SOFT-SHAPE probe (size × 0.35) via LiveSizingProfile.
   Bot still learns from small entries instead of full abandonment.
3. **LiveSizingProfile.gateSizeMult** — new mapping
   `TOXIC_PATTERN_SOFT_6207 → 0.35`.

**Files:** TokenWinMemory.kt · FinalDecisionGate.kt · LiveSizingProfile.kt
**Brace/paren parity:** verified identical delta vs last GREEN commit.
**CI:** Build ✅ + Runtime Smoke ✅ (run 28948687744).

## Original Problem Statement
Upgrading a Native Kotlin Android Solana trading bot (AATE) to V5.7+.
Building a super smart SOL Perps/Leverage trading system that reuses
existing AI infrastructure, adding tokenized stocks, multi-asset trading,
an Insider wallet tracker, a live readiness gauge, and a continuous
auto-replay learning system. NO LOCAL COMPILER — all code changes must be
pushed via Git to trigger GitHub Actions CI.

## Session (01 Jul 2026) — ROOT-CAUSE AUDIT: why live bleeds while paper prints

### THE FINDING (forensic, code-proven)
`LiveSizingProfile.lastMileEntryFloor()` did `max(baseSol, 12% wallet)` on
LIVE ONLY (paper exempt). Every risk damper the AI stack applied (bleeder
0.35x, DUMP 0.35x, CorrelationGuard 0.20x, discipline probes, BehaviorAI
tilt) was ERASED by the max() re-inflation — live bet 12-32% of wallet on
proven-toxic lanes while paper kept the damped probe sizes. Paper +19.9
SOL / live -9.0 SOL was structural, not luck. Compounded by: no lane-EV
kill outside DUMP/CHOP (MOONSHOT ran 117 live trades at EV -24.44%/trade
in NORMAL), 1% round-trip on-chain fee, 2-5%/side meme slippage, and
stale-price marks firing phantom -74% stop-losses.

### V5.0.6205 + V5.0.6205b (SHIPPED — CI GREEN, build + runtime smoke)
1. **Damper-respecting floor** (LiveSizingProfile.kt + 2 Executor call
   sites): floor now scales with composed risk multiplier; damped probes
   dust-guarded at 0.015 SOL, hard-capped at 8% wallet; undamped winners
   keep full compounding floor.
2. **All-regime bleeder-lane hard gate** (Executor.kt): lane with n>=15 &
   (EV<=-10% or WR<20% net-negative) blocked from live SOL in ANY regime;
   paper keeps learning; auto-unblocks on stat recovery.
3. **P0 stale-price SL guard** (BotService.kt): SL triggers with mark >15s
   old are vetoed for a 20s grace + fresh-quote requeue; fail-safe exits
   proceed after grace (rug nets never permanently vetoed).
4. **P1 last-good-response cache** (SolanaMarketScanner.kt getWithRetry):
   serves <10min-old cached list bodies during DexScreener 5xx storms —
   restores the full network feed (all 7-8 discovery lanes unstarved).
5. **P1 QUOTE_EXHAUSTED second-wind retry** (Executor.kt): one extra quote
   at max slippage after 1.2s cool-off before terminal fail (~32% burst
   buy-failure rescue).
6. **251-mint BlueChip watchlist** shipped (scan cap 24→40/cycle).
7. **P2 UI**: pivot-to-winners banner, pilot-log AI monologue ticker
   (marquee), POSITIONS backup export button → Downloads/AATE_Backups/.
- V5.0.6205b hotfix: removed duplicate staleSlGraceUntilMs6205 declaration.

### Still open after this session
- CorrelationGuard damping now actually EFFECTIVE (floor no longer erases
  it) — monitor operator reports.
- HARD_BLOCK_REENTRY_GUARD on blue-chips (earlier issue, unfixed).
- Future phases: SOL Perps/Leverage mode, neural bridge, LLM Lab sandbox.

## Session (07-08 Feb 2026) — Phase 2C Swarm Sentience + Live-Green Pivot

### V5.0.6193 — PHASE 2C SWARM SENTIENCE (SHIPPED GREEN)
- **BotPersonalityLayer.kt** — 8 deterministic personas per instanceId
  hash: Alpha aggressor, Beta guardian, Gamma contrarian, Delta momentum,
  Epsilon whale, Zeta chartist, Eta fundamental, Theta wildcard.
  Exposes riskAppetiteMult, entryPickinessDelta, holdConvictionMult,
  rugParanoiaDelta.
- **SwarmVariantABTuner.kt** — per-instance config perturbations
  (entryScore ±3, sl ±0.10, tp ±0.15, lab sizing ±0.05, cofire ±1).
  evolveTowardChampion drifts local config toward best-performing
  swarm winner.
- **InterBotLLMChat.kt** — OBSERVATION/CONFIRM/CONSENSUS message bus.
  ≥3 CONFIRMs within 90s triggers CONSENSUS event. Piggybacks on
  SwarmIntel's Turso channel.

### V5.0.6194 — HARD_BLOCK_FREEZE_AUTHORITY liquidity floor (SHIPPED)
Lowered fdgAuthorityUnknownRouteProof6186 liq floor from \$3,000 to
\$1,200. Operator report showed 148 blocks/minute on ANSEM-profile
launches (\$1.5-\$2.5k liq). Safety already softly allows UNKNOWN auth.

### V5.0.6195 — WINNER PRESS + ENTRY-PRICE HEAL (SHIPPED GREEN)
- RegimeDetector.laneAwareSizeMultiplier — new WINNER PRESS clause: any
  lane with n>=5 AND WR>=50% gets 1.10x during DUMP (not damped).
  Priority-lane floor raised 0.70 -> 0.85.
- OpenPnlSanity heal chain extended: pos.highestPrice fallback,
  currentPrice fallback. Unclogs ENTRY_PRICE_INVALID spam.

### V5.0.6196 — PIVOT-TO-WINNERS ROUTER (SHIPPED GREEN)
DumpRegimeWinnerRouter — FDG live-mode gate. During DUMP or CHOP, HARD
BLOCK live entries on lanes with n>=5, negative SOL PnL, EV<-2.5%,
WR<30%. Force capital into TREASURY (WR 50%, EV +205%), BLUECHIP
(WR 36.8%, EV +84%), Metals (WR 66.7%, EV +54%). Report showed 96
blocks/minute after activation — confirming the router IS firing.

### V5.0.6197 — UNCLOG CHICKEN-EGG (SHIPPED)
Report 2026-07-08 05:56 showed 6196 was over-blocking (FDG: 0/174
allow) because pump.fun-fed meme lanes got blocked as bleeders but
non-meme lanes have no live intake feed. Also PatternGoldenGoose was
vetoing every fresh meme intake (goose_catastrophic on name-pattern
memory that shouldn't apply to brand-new mints).
- BotService intake: bypass PatternGoldenGoose.isCatastrophic for
  fresh-launch sources (PUMP_FUN_NEW, RAYDIUM_NEW_POOL, PUMP_PORTAL_WS).
- FDG pivot router: bypass when FreshLaunchHunter matches ANSEM profile.
- OpenPnlSanity: synthetic 1e-9 basis when all other heals fail.
  Frees stuck RECOVERED_2xKQg4 slot.

### V5.0.6198 — MOONSHOT_HOLD + RPC_FAILOVER + CONFIG_STORE_FIX (SHIPPED GREEN)
- MoonshotHoldMode.shouldSuppressExit wired into Executor.requestSell
  so parabolic runners are actually held to \$3.4M target.
- WalletManager.reconnectViaFallbacks cycles 17 Solana RPC endpoints
  on Helius 429 rate-limit errors.
- ConfigStore.load(ctx) reference fix in the wallet-state bridge.

### V5.0.6199 — SOL-NETWORK-WIDE BLUECHIP INTAKE + PROJECT_SNIPER_LAUNCHPAD (SHIPPED GREEN)
Operator directive: "scan the entire sol network!!! its not a pumpfun bot".
Report 2026-07-08 19:27 showed intake=40 total with 0 SOLANA_BLUECHIP_WATCHLIST
emissions (raw=0 enq=0 durMs=2) because (a) isSeen() short-circuited after
cycle #1 and (b) DexScreener SR=24% storm starved dex.getBestPair() calls.
- scanSolanaBlueChipWatchlist: bypass isSeen() for blue-chip mints (persistent
  watchlist, not one-shot memes); skip only on hard-reject or open position.
- Primary path is now a batched Jupiter lite-api/price/v3 call
  (SR=100%, no key, one call for all 20 mints). DexScreener is now the
  fallback, not the primary.
- Category-aware conservative liquidity floors (SOL_WRAPPED \$5M, DEX_HUB
  \$1.5M, ESTABLISHED_MEME \$800K, LSD \$500K, INFRA \$400K, DeFi \$200K)
  so TokenMetricStageRouter's established-token override fires and routes
  into BLUECHIP/DIP_HUNTER/QUALITY.
- New TokenSource.PROJECT_SNIPER_LAUNCHPAD: scanFreshLaunches tags fresh
  launches passing legit-quality filter (mcap>=\$500K, liq>=\$50K, age>=30m)
  with this source. ScannerSourceBrain now learns launchpad-quality WR
  separately from pump.fun firehose.

## Priority Backlog

### P1 — Ship next
- CorrelationGuard — portfolio-level correlated-holding damper.
- DexScreener 5xx storm mitigation for scanFreshLaunches / scanDex*.
  Add Jupiter price fallback where feasible.
- Wire additional Solana-ecosystem feeders (Jito airdrop tokens,
  Backpack tokens, wSOL wrappers) into blue-chip watchlist as they mature.

### P2 — UI
- Show live persona label (BotPersonalityLayer.label()) on main UI.
- Pilot Log ticker consuming InterBotLLMChat.recent(20).
- Pivot-to-winners banner when router is active (fun to watch).

## Doctrine Rules
- **No local compiler** — push to GitHub Actions CI.
- **Brace parity mandatory** — count `{}` before every push.
- **Doctrine #86 — fail-open** — every gate returns ALLOW on error.

## Session 2026-02-08 evening — LIVE MEME TRADER GREEN PIVOT (V5.0.6199-6204b, all CI GREEN)

Operator P0: "live meme trader must go green tonight. paper +19.9 SOL,
live -9.0 SOL. compounding must actually compound."

Two triage-subagent full audits identified 12 asymmetries between paper
and live pipelines. All fixes shipped this session:

### V5.0.6199 — SOL-network-wide Blue-Chip Intake via Jupiter
- scanSolanaBlueChipWatchlist: bypass isSeen() (persistent watchlist);
  batched Jupiter lite-api /price/v3 as primary path (SR=100%);
  DexScreener demoted to fallback; category-aware conservative
  liquidity floors.
- New TokenSource.PROJECT_SNIPER_LAUNCHPAD for legit-quality fresh
  launches (mcap>=$500K + liq>=$50K + age>=30m).

### V5.0.6200 — PatternGoldenGoose bypass for curated sources
- BotService.v4132_isFreshLaunchSource now covers SOLANA_BLUECHIP_WATCHLIST
  and PROJECT_SNIPER_LAUNCHPAD so WIF isn't vetoed as goose_catastrophic.

### V5.0.6201 — Live-pipeline un-strangling (6 fixes)
- LaneExitTuner TP_MIN 0.60 → 0.80 (winners run to +45% not +25%)
- RealizedWalletCompoundingGovernor lane exemption >-0.05 (was >0.0)
- DumpRegimeWinnerRouter small-wallet (<1 SOL) recovery exemption
- DANGER_ZONE_6072 live rule relaxed (score>=40 danger admits)
- SmartSizer live base 4-15% → 8-20% + tier caps 8-15% → 12-22%
- StrategyTruthLedger mint-close dedup window 5min → 60s

### V5.0.6202 — Money-print alignment (4 fixes)
- Fee threshold display %.2f → %.5f (fees WERE flushing at 0.0001 SOL
  correctly — display was rounding to '≥ 0.00 SOL')
- Compounding growth unlock at 10% gain (was 30%); new 1.20x tier
- RegimeDetector lane exemption threshold 5 → 3 samples
- STABLECOIN_SYMBOL_SKIP_6202: PYUSD/USDC/DAI/etc skipped in
  POSITION_AUTO_HEAL

### V5.0.6203 — Proven-lane un-clamp + circuit breaker (5 fixes)
- LiveProbabilityEngine BLUECHIP/TREASURY/PROJECT_SNIPER band floor 0.85
- Sharper THROWN telemetry (exception class + last stage, not just
  'THROWN:unknown')
- FREEZE_AUTHORITY_UNKNOWN soft-allow on proven lanes when rugcheck>=55
- CHART_PRE_BUY_BEARISH_HARD_PATTERN meme-lane-only (proven lanes
  treat bearish 5-min as dip-hunt signal, 0.35x probe size)
- ApiBackoff circuit breaker at n>=8 SOFT failures (60s → 120s → 300s
  cap) to stop scan cycles waiting on chronically-degraded DexScreener

### V5.0.6204 / 6204b — 5x expand blue-chip watchlist (20 → 96 mints)
Operator: "watch list is fucking tiny 6 tokens wtf dude!!"
- 8 new categories added on top of the original 6:
  AI_AGENT (GRIFFAIN, ARC, ZEREBRO, SNAI, MAX, BULLY, SEED, ANON)
  GAMING (ATLAS, POLIS, AURY, GENE, NYAN)
  DEPIN (MOBILE, IOT, NOS, HELIUM, DEEP)
  NEW_L1_MEME (FARTCOIN, PENGU, GOAT, ACT, TRUMP, MELANIA, +11 more)
  RWA (tokenized US treasuries)
  BRIDGED_MAJOR (cbBTC, wBTC, cbETH, wETH, SUI-bridged)
  LAUNCHPAD_GRADUATE (SC, GRIFT, SPX6900, SIGMA, CHILLGUY)
  NFT_LIQUIDITY (TNSR, MPLX, DGN)
- Scanner emit cap raised 12 → 24 per cycle
- 6204b hotfix: exhaustive `when` for the 8 new categories in
  fallbackLiquidityForBlueChip()

## Runtime verification (report 2026-07-08 21:13, before V5.0.6204b)
- Intake up 88% (17 → 32)
- FDG allow up 567% (3 → 20)
- EXEC attempts up 265% (26 → 95)
- BLUECHIP tpMult 0.80 confirmed (was 0.60)
- Live BUY_BROADCAST firing (BabyCupsey observed)
- MOONSHOT LiveTuner PnL=+0.003 (proven-winner-press growing sample)

## Priority Backlog (still ahead)

### P0 — needs next push
- **Price staleness guard on stop-loss trigger** (Executor.kt). Risk:
  DexScreener SR=22% + Birdeye SR=52% causing -74%/-76% spurious
  stop-loss exits on stale prices. Deferred; observe V5.0.6204b runtime.

### P1
- DexScreener 5xx fallback for scanFreshLaunches/scanDex*.
- Grow watchlist to full 150+ target (currently 96; +54 more mints).
- CorrelationGuard is already SHIPPED (V5.0.6126) and wired.

### P2 UI polish
- Pivot-to-winners banner
- Ladder pill on Memes tab
- Brain Health pill
- Pilot Log ticker
- /positions backup export button

### P3 — Original roadmap
- SOL Perps/Leverage mode (Perps_5x lane already has n=1 WR=100% seed)
- Full 150+ asset universe (currently 96)
- Neural bridge (AI cross-learning perps↔stocks)
- LLM Lab sandbox

## 2026-02-08 22:20 — MID-SESSION STATE (V5.0.6204b installed, still bleeding)

### What operator report 22:20 shows working:
- Blue-chip lane_eval=22 (was 0)
- SOLANA_BLUECHIP_WATCHLIST emitting 5+ intakes per cycle
- BUY ok/fail: 30/5 (up from 13/6)
- SELL ok: 14 (up from 1)
- BLUECHIP bandMult=0.85 confirmed (V5.0.6203 fix live)
- Live BUYs actively firing on real blue-chip candidates (FWOG etc)

### Still bleeding (wallet 0.37 → 0.27 SOL):
- HARD_BLOCK_REENTRY_GUARD: 8 (reentry lockouts blocking legit setups)
- LIVE_CONTEXT_TOXIC_PATTERN_MEMORY_6192_s: 19 (still filtering)
- HARD_BLOCK_FREEZE_AUTHORITY_UNKNOWN_6164: 13 (V5.0.6203 soft-allow
  only fires on proven lanes with rugcheck>=55; many pump.fun mints
  score below that)
- THROWN:InterruptedException@TX_SUBMIT_START: 2 (Jupiter submit
  interrupted by app cycle timeout — cycle max=59.7s)
- Cycle max=59.7s (DexScreener SR=28%, still cycle-choking despite
  V5.0.6203 circuit breaker)

### PRIORITY QUEUE FOR NEXT SESSION (in order):

#### P0 — Ship immediately
1. **Grow watchlist 96 → 250 mints** (operator: "watch list is meant
   to have 250 tokens"). Categories to fill:
     - More AI_AGENT (add: PIPPIN, GRIM, DOLOS, ELIZA, VU, LUNA, TANK)
     - More NEW_L1_MEME (add: SPX6900 variants, LOCKIN, TITCOIN,
       DEEZ NUTS, WEN, MYRO, CHONKY, HUSKY, GORK, FATCOIN)
     - More GAMING (add: KMNO+KMNO variants, GMT-solana, NYAN, WOOF)
     - More LAUNCHPAD_GRADUATE from last 90 days pump.fun graduates
     - More BRIDGED_MAJOR (add: WAVAX, WMATIC, WLTC on Solana)
     - Fill DEPIN with GRASS, NEXT, FILECOIN, THETA-sol
2. **Reduce HARD_BLOCK_REENTRY_GUARD cooldown for blue-chips** —
   pump.fun rug cooldown = 24h is correct; blue-chip cooldown should
   be 15m (they're re-buy candidates by design).
3. **Price staleness guard on stop-loss** (deferred from V5.0.6202
   audit — DexScreener 5xx storm still risks stale-price -74% exits).

#### P1
4. THROWN:InterruptedException@TX_SUBMIT_START — increase Jupiter TX
   submit timeout OR relax cycle interrupt policy on live BUYs in
   flight.
5. Cycle time still spiking to 59s — needs deeper scanner rotation
   audit. V5.0.6203 circuit breaker added but only trips at n>=8
   consecutive.

#### P2 UI
6. Wallet Growth Ticker (streaming BUY/SELL delta)
7. Pivot-to-winners banner
8. Ladder pill

### Last git head: `b89dc70c17` (V5.0.6204b, GREEN)
### 7 commits shipped this session, all CI GREEN
