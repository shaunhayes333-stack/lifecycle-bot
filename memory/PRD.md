# AATE — Native Kotlin Android Solana Trading Bot

## Original Problem Statement
Native Kotlin Android Solana trading bot. Builds via GitHub Actions CI only —
NO local compiler. Multi-lane architecture (Memes [9 sub-lanes], Crypto/Alts,
Stocks, Markets, Tokenized Stocks, Forex, Metals, Commodities). Foreground
Service with a 50+ AI-module pipeline gated through processTokenCycle.

## Architecture (Key Files)
- engine/BotService.kt              — central engine, watchlist tick cadence (V5.9.663c)
- engine/Executor.kt                — buy/sell, closeAllPositions, liveSweepWalletTokens
- engine/SmokeTestReceiver.kt       — debug-only CI broadcast entry-point (V5.9.661b)
- engine/TokenLifecycleTracker.kt   — clearAll() V5.9.661c
- engine/HostWalletTokenTracker.kt  — clearAll() V5.9.661c
- engine/LifecycleStrategy.kt       — paper-mode confidence floors (V5.9.662)
- engine/TradeAuthorizer.kt         — UNKNOWN_QUALITY paper-learning floor (V5.9.662b)
- engine/BehaviorLearning.kt        — BIG LOSS log demoted to debug (V5.9.662d)
- engine/TreasuryManager.kt         — paper dust floor 0.0001 SOL (V5.9.663b)
- ui/MainActivity.kt + activity_main.xml — main UI, btnToggle, etActiveToken (sole owner)
- ui/SettingsBottomSheet.kt + dialog_settings.xml — Settings (etActiveToken removed V5.9.663)
- ui/BotViewModel.kt                — UI poll cadence 2500ms (V5.9.663b)
- v3/scoring/MoonshotTraderAI.kt    — minRcScore=1 paper learning (V5.9.662)
- v3/scoring/QualityTraderAI.kt     — paper-bootstrap minAge=1min (V5.9.663b)
- v3/scoring/BehaviorAI.kt          — trade-maturity ramp on paper penalties (V5.9.662d)
- v3/risk/FatalRiskChecker.kt       — paper-learning rugScore bypass (V5.9.662)
- v3/scoring/{ShitCoin,BlueChip,CashGen,Manipulated}TraderAI.kt
- perps/{CryptoAltTrader,MarketsTrader,TokenizedStockTrader,...}.kt
- ci/runtime-test.sh + .github/workflows/runtime-test.yml — Android emulator smoke test

## Implementation History — Recent Sessions

### V5.9.794 — operator audit Item 6 (cap + priority queue) + Item 7 (fresh-meme HARD_FLOOR) (May 16, CI Build ✅)

ITEM 6 — PumpPortal cap + explicit priority queue (P1, COMPLETE)
- `GlobalTradeRegistry.MAX_PUMP_PORTAL_CONCURRENT = 300` hard cap.
  `addToWatchlist()` rejects new PumpPortal/PUMP_FUN_NEW/PUMPFUN_WS
  additions with `PUMP_PORTAL_CAP_REACHED` once the cap is hit.
  Aggressive low-score TTL drains old entries fast.
- `isPumpPortalSource()` / `pumpPortalConcurrentCount()` /
  `pumpPortalRejectionCount()` / `pumpPortalCapMax()` exposed.
- `BotService` prioritized watchlist sort now reads:
  • `realPoolLiquidityUsd` (capped 60.0) + bondingCurveLiquidityEstUsd
    (capped 15.0) so confirmed-pool tokens dominate the head.
  • Volume proxy capped 35.0.
  • Safety freshness: +30/+10/0/-5 based on lastSafetyCheck age.
  • Source reliability: +25.0 when LiquidityClassifier confirms an
    executable pool.
- `UniverseHealthActivity` Execution pillar shows
  "PumpPortal concurrent / cap" with green/amber/red thresholds +
  "PumpPortal cap rejections" counter.

ITEM 7 — fresh-meme HARD_FLOOR -9% with age guard (P1, COMPLETE)
- `Executor.evaluateExitSignal` HARD_FLOOR_STOP_PCT is now position
  age aware:
    • Fresh meme (heldSecs < 300s AND tradingMode in {SHITCOIN,
      MOONSHOT, EXPRESS, PUMP_SNIPER}) → 9.0
    • Matured / non-meme → 15.0 (unchanged)
- Matures correctly: a meme that holds past 5 minutes graduates to
  the looser floor so a one-off drawdown on a long-running winner
  doesn't choke it.

### V5.9.793 — operator audit Item 5 + Item 6 (May 16, CI GREEN)

ITEM 5 — Split liquidity fields + BC-sim exclusion (P1, COMPLETE)
- New `engine/LiquidityClassifier.kt`: derives realPoolLiquidityUsd /
  bondingCurveLiquidityEstUsd / exitCapacityUsd / isBcSimOnly from
  TokenState (no schema change). Pure helper, no allocation per call.
- `CanonicalTradeOutcome` gains `bcSimOnly: Boolean = false`.
  `CanonicalLearningCounters` gains `bcSimOnlyOutcomes` and `bumpCounters`
  increments it on every flagged outcome.
- `Executor.recordTrade` rich-publish path populates the flag from
  `LiquidityClassifier.isBcSimOnly(ts)`.
- `FinalDecisionGate.kt` LIQUIDITY_BELOW_EXECUTION_FLOOR check now reads
  `exitCapacityUsd(ts)` (real-pool only). Pump.fun BC-only tokens with
  no confirmed pool return 0.0 → never pass the execution floor until a
  real pool is observed. Watchlist floor (discovery) still uses raw
  lastLiquidityUsd intentionally.
- `CanonicalSubscribers` FluidLearningAI mirror drops bcSimOnly outcomes
  with WR_FILTERED_BC_SIM_ONLY forensic.

ITEM 6 — Scanner pressure control (P1, partial)
- New `engine/CycleTimingTracker.kt`: 64-cycle rolling window for scan
  cycle duration. Reports avg/p95/max + counts of cycles over the 12s
  target / 30s hard limit.
- `SolanaMarketScanner` records each cycle's duration and warns on
  >30s; CycleTimingTracker.recordCycle() is called at end-of-scan.
- `WatchlistTtlPolicy` aggressive low-score TTL: entries with
  score < 40 expire after 60s independently of saturation TTL.
- `UniverseHealthActivity` Learning section now surfaces
  `bcSimOnlyOutcomes`. New 'Scanner Cycle Timing' panel renders
  avg/p95/max vs the targets with green/amber/red colour coding.

Still pending from Item 6 (planned for V5.9.794):
- Hard cap on concurrent PumpPortal active candidates
- Explicit priority-queue sort (currently scan order is source-based)

### V5.9.791 + V5.9.792 — operator audit Items 1 + 2 + 3 + 4 + 7-partial (May 16, CI GREEN)

Operator audit on build 5.0.2729 identified 7 remaining critical items
once V3 safety gates landed. V5.9.791 (sha=80182aa5e) ships Items 1+2+7;
V5.9.792 (sha=e93248a36) layers Items 3+4 on top. Both: Build ✅ + Smoke ✅.

ITEM 1 + 2 — PositionExitArbiter / CanonicalPositionOutcomeBus (P0)
- New `engine/PositionExitArbiter.kt`. positionKey = "<canonicalMint>_<entryTimeMs>"
  with 5-minute wall-clock bucket fallback. First terminal SELL reason wins.
- `arbitrate()` returns ALLOW/SUPPRESS. Subsequent cascade firings (e.g.
  CASHGEN_STOP_LOSS + STRICT_SL + RAPID_CATASTROPHE_STOP on the same
  Position) emit EXIT_SUPPRESSED_DUPLICATE forensic and never fan out
  to learners.
- PARTIAL_SELL paths never lock the slot; counted via `recordPartial()`.
- `CanonicalLearningCounters` snapshot now includes terminalSells /
  suppressedDuplicates / partialSells / staleSlotEvictions.
- Wiring:
  • `CanonicalOutcomeBus.publish()` arbitrates terminal WIN/LOSS results.
    Partials (reason startsWith "partial") bypass arbitration.
  • New `publishUnchecked()` for callers that already arbitrated locally.
  • `Executor.recordTrade(ts, trade)` arbitrates at the top for terminal
    SELLs — blocks TradeHistoryStore row, PatternClassifier, ToxicMode,
    MetaCognition, RunTracker30D, AND the rich canonical publish in one
    surgical chokepoint. Pre-marks tradeId as rich-published so the
    legacy bridge doesn't false-suppress at the bus layer.
  • Rich publish at Executor.kt:~1600 switched to publishUnchecked().
- UI: `UniverseHealthActivity` Execution pillar renders arbiter counters.

ITEM 7 (partial) — catastrophe stop tightening
- BotService rapid-monitor: `isCatastrophe = pnlPct <= -14.0` (was -25.0).
- Fresh-meme HARD_FLOOR -9% deferred to V5.9.793 (requires age gate).

ITEM 3 — MEME_ONLY_TEST_MODE enforcement
- BotService enabledTrader publish: dropped `(|| cfg.paperMode)` from
  the CYCLIC enable predicate. CYCLIC now requires explicit
  `cfg.cyclicTradeLiveEnabled`.
- CanonicalSubscribers FluidLearningAI mirror: when
  `EnabledTraderAuthority.snapshot() == {MEME}`, drop outcomes whose
  source is not in {SHITCOIN, MOONSHOT, EXPRESS, MANIP, CYCLIC}. Emits
  MEME_TRAINING_FILTERED_NONMEME_SOURCE forensic per filter hit.

ITEM 4 — V3 fatal terminates lane flow
- BotService ShitCoin lane: hoisted V3 hard-reject check ABOVE the
  ShitCoinTraderAI.evaluate() call. V3 BlockFatal / Rejected / Blocked
  short-circuits, emits REJECTED_FATAL_V3 forensic, no qualification
  training sample. Paper-mode rug-tolerated BlockFatal still allowed
  through to match the existing scHardReject parity at line 11604.

Still pending from the 7-point operator audit (planned for V5.9.793+):
- Item 5: Split liquidity fields (realPoolLiquidityUsd /
  bondingCurveLiquidityEstUsd / exitCapacityUsd) and mark BC_SIM_ONLY
  paper outcomes for exclusion from production WR.
- Item 6: Scanner pressure control (cap PumpPortal candidates, TTL
  low-score mints, priority queue, target cycle avg < 12s).
- Item 7 remaining: fresh-meme HARD_FLOOR -9% with position-age guard.

### V5.9.790 — operator audit Critical Fixes 2 + 4 + 5 + 9 (May 16, CI GREEN)

Closes the remaining 9-point audit items beyond V5.9.789 (which landed
Fixes 1+3+6+8). Build AATE APK ✅ + Runtime Smoke ✅ on sha=614093be.

CRITICAL FIX 2 (P1) — sub-classify DEGRADED layers
- `LayerReadiness` enum split: legacy `DEGRADED` retained for back-compat;
  added `DEGRADED_BAD_EV`, `DEGRADED_FEATURE_STARVED`, `DEGRADED_NO_ADAPTER`,
  `DEGRADED_NO_VOTES`.
- `LayerReadinessRegistry.State` gains `richEducationCount` +
  `incompleteEducationCount`; new `recordEducationDetailed()` helper.
- `readinessOf()` returns `DEGRADED_FEATURE_STARVED` when rich==0 &
  incomplete>=500, `DEGRADED_BAD_EV` when rich>0 & lossRatio>=70%.
- New `countersOf(layer) → (settled, rich, incomplete)` for the UI.
- `CanonicalLearningCounters` gains `strategyTrainableOutcomes` (rich +
  EXECUTED) and `executionOnlyOutcomes` (rest).
- `CanonicalSubscribers` all three readiness recorders switched to
  `recordEducationDetailed(isRichSample=!outcome.featuresIncomplete)`.
- `LearningCounterActivity` renders new counters + per-layer
  `n=… rich=… incomplete=…` annotation.

CRITICAL FIX 5 (P1) — bus-only strategy pattern learning
- `BehaviorLearning.strategyLearningFromLegacy = false` (default).
- `recordTrade()` legacy direct path now ALWAYS bumps the new
  `totalLegacyDirectRecorded` counter but returns early before mutating
  pattern memory. Pattern memory writes (`goodPatterns`/`badPatterns`)
  flow exclusively through `onCanonicalOutcome()` (canonical bus).
- Operator can flip the flag back to `true` from a debug hook if the
  bus underdelivers — full back-compat preserved.
- Legacy direct count surfaced in `LearningCounterActivity`.

CRITICAL FIX 4 (P1) — clarify CLASSIC vs MODERN sentience
- `UniverseHealthActivity` Section 2 shows the actual
  `UnifiedScorer.modeLabel()` AND an explicit "Effective sentience mode"
  line:
    CLASSIC → "CLASSIC (full sentient OFF — outer symbolic ring bypassed)"
    MODERN  → "MODERN (sentient symbolic outer ring active)"
- Footer note reminds the operator that CLASSIC = 20-layer build-1920
  pipeline, NOT full symbolic sentient trading.

CRITICAL FIX 9 (P1) — AATE Universe Health screen
- New `UniverseHealthActivity` covers 6 pillars (Runtime / Scoring /
  Learning / Execution / Authority / Wallet), auto-refreshes every 3s.
- Surfaces canonical totals + richness ratio, layer-readiness bucket
  counts, sell-job registry size, `EnabledTraderAuthority` snapshot,
  PROJECT_SNIPER proof-off status, host-wallet truth + reconciler drift.
- Long-press the 🩺 Pipeline tile on MainActivity → opens it.
- Registered in `AndroidManifest.xml`.

### V5.9.789 — operator audit Critical Fixes 1 + 3 + 6 + 8 (May 16, CI GREEN)

Operator dump on build 2727 showed canonicalOutcomesTotal=716,
richFeatureOutcomes=0, incompleteFeatureOutcomes=716 — 100% of outcomes
were skipping strategy learning.

CRITICAL FIX 1 (rich producer) + 6 (forensic): `CanonicalFeaturesBuilder.kt`
- ROOT CAUSE: paper-mode tokens that never got a fresh DEX quote had
  empty `ts.lastPriceDex`/`lastPriceSource` → `inferVenueRoute` returned
  `UNKNOWN` → `isIncomplete=true` on every outcome.
- FIX: source-derived fallback for venue + route + trader when direct
  lookup fails:
    SHITCOIN/MOONSHOT/EXPRESS/CYCLIC → venue=PUMP_FUN_BONDING / route=PUMP_NATIVE
    BLUECHIP/TREASURY/MARKETS         → venue=JUPITER / route=JUPITER
    V3/default                        → trader=STANDARD (still trainable)
- Per-field `missing=[…]` forensic line (`CANONICAL_FEATURES_INCOMPLETE`).

CRITICAL FIX 3 (Sniper proven off):
- `ProjectSniperAI.engageMission` FATAL_AUTH_BREACH guard rejects
  missions if PROJECT_SNIPER isn't in `EnabledTraderAuthority.snapshot()`.
- BotService startup `AUTH_SURFACE_AT_START` line dumps mode + cfg +
  Sniper enable status + full enabledTraders set.

CRITICAL FIX 8 (stop training strategy on feed/execution failures):
- Strategy learners now skip when executionResult ∈ {PHANTOM_UNCONFIRMED,
  STUCK_UNCONFIRMED, FAILED_*}; execution learners still consume those.

CI: Build AATE APK ✅ + Runtime Smoke ✅ on sha=42c458e6.
Operator forensics on build 5.0.2724 reported **zero paper trades after 61min uptime**
despite `FDG_PAPER_ALLOW=36` and `BOTLOOP_RESCUE_THREW=58/60` heartbeats failing.

Root cause (caught by troubleshoot agent RCA): V5.9.780a's JVM 64KB method
extraction helpers in `engine/Executor.kt:279-280` were shipped as
`private fun isPaperRT(): Boolean = isPaperRT()` (self-recursion). Kotlin
accepts a self-call, so CI compiled green — but every runtime invocation
threw StackOverflowError immediately. Impact: `paperBuy()` mode check
(Executor.kt:5185) silently swallowed every approved trade; bot loop mode
checks crashed each heartbeat; rescue handler rethrew because the
StackOverflow propagated.

Fix: restore intended implementation (matches V5.9.780a commit message):
```
private fun isPaperRT(): Boolean = RuntimeModeAuthority.isPaper()
private fun isLiveRT(): Boolean = RuntimeModeAuthority.isLive()
```

The V5.9.781..785a operator-audit waves were INNOCENT — they only made
the regression more visible because the bot loop ran longer and hit the
mode checks more often per cycle.

### V5.9.781..785a — Operator audit Wave 1-5 LANDED (May 16, all CI GREEN)
Comprehensive deep audit response from operator: "Stop adding more 'AI layer'
labels. Make every existing layer consume the same feature-rich canonical
outcome." Five surgical CI-green waves shipped:

**Wave 1 — V5.9.781 truth-layer realignment (audit items G, H, I):**
- engine/LearningCounterActivity.kt: Wallet Truth Digest now reads
  HostWalletTokenTracker as PRIMARY (openCount + actuallyHeldCount), with
  WalletReconciler.knownMints as secondary and a drift line so the
  reconciler-lag bug ("0 mints" while host tracker has open positions) is
  visible.
- v3/scoring/UnifiedScorer.kt: companion modeLabel() exposes
  "CLASSIC (20-layer build ~1920)" vs "MODERN (V5.9.325 outer-ring)" so the
  UI never implies modern symbolic AI is active while classicMode silently
  bypasses the outer ring.
- engine/SentienceHooks.kt: llmStatus() + llmVote(symbol) accessors so the
  dashboard can render "LLM_STATUS=UNAVAILABLE / DEGRADED / READY" and per-
  symbol "ALLOW / VETO / NEUTRAL" instead of pretending the LLM reasoned.

**Wave 2 — V5.9.782 rich canonical outcome (audit items A, C, D, J):**
- engine/CanonicalLearning.kt: CandidateFeatures struct carrying venue,
  route, bondingCurveActive, migrated, ageBucket, liqBucket, mcapBucket,
  volVelocity, holderConcentration, safetyTier, mintAuthority,
  freezeAuthority, slippageBucket, entryPattern, bubbleClusterPattern,
  fdgReasonFamily, symbolicVerdict, exitReasonFamily, holdBucket,
  manualOrExternalClose. featuresIncomplete flag on CanonicalTradeOutcome.
  Counters: richFeatureOutcomes / incompleteFeatureOutcomes.
- engine/BehaviorLearning.kt: onCanonicalOutcome(outcome) — real pattern
  learning replaces no-op onCanonicalSettlement. richSignature() carries
  venue+route+safety+age dimensions so pump.fun bonding-curve setups stop
  colliding with raydium-graduated setups in the pattern table.
- engine/CanonicalSubscribers.kt: wires BehaviorLearning.onCanonicalOutcome
  before the (still-existing) settlement no-op.

**Wave 3 — V5.9.783 / 783a real subscriber adapters (audit item B):**
- engine/AdaptiveLearningEngine.onCanonicalOutcome(outcome): reconstructs
  TradeFeatures from bucketed CandidateFeatures fields and calls existing
  learnFromTrade(). Skips featuresIncomplete=true.
- v3/scoring/MetaCognitionAI.onCanonicalOutcome(outcome): counter + EWMA
  with dedupe via recentlyCountedMints.
- engine/RunTracker30D.onCanonicalOutcome(outcome): ledger adapter ready.
  Not yet auto-wired in CanonicalSubscribers to avoid double-counting; will
  be wired atomically when direct Executor close call sites migrate.
- CanonicalSubscribers wires AdaptiveLearningEngine to the bus.

**Wave 4 — V5.9.784 CandidateSymbolicContext (audit items E, F):**
- engine/CandidateSymbolicContext.kt (NEW file):
  - SymbolicVote enum: ALLOW / CAUTION / VETO / NEUTRAL.
  - SymbolicVerdict struct: vote, confidence, reasons[], affectedLayers[],
    expectedFailureMode (RUG/DUMP/CHOP/DEAD_CAT/TIMEOUT/FREEZE/DUPLICATE).
  - CandidateSymbolicContext: per-token snapshot of global mood slice +
    safety + route + wallet truth + narrative/social signals + final
    structured verdict. Replaces the global-only SymbolicContext for trade
    decisions.
  - CandidateSymbolicContextBuilder.buildFor(...) — pure deterministic, no
    LLM async path so FDG can use it inside the hot loop.

**Wave 5 — V5.9.785 / 785a producer sweep (Wave 5 item):**
- engine/CanonicalFeaturesBuilder.kt (NEW file): single helper that
  constructs feature-rich CandidateFeatures from TokenState + Trade +
  mode/source. Maps lastPriceDex/lastPriceSource → venue+route+bondingCurve,
  SafetyReport → tiers, candles → volVelocity, holders → concentration.
  Returns (features, isIncomplete) so producers populate
  featuresIncomplete=false ONLY when key fields filled.
- engine/Executor.kt rich-canonical-publish path: now populates
  candidate=CandidateFeatures + featuresIncomplete flag via the builder.
  Strategy learners (BehaviorLearning, AdaptiveLearningEngine) will start
  training on venue/route/safety-aware signatures the moment trades close
  through this path. Remaining lanes (perps/MetalsTrader/ForexTrader/etc.)
  continue using publishFromLegacyTrade with featuresIncomplete=true and
  will be incrementally migrated in future waves.

### V5.9.780 / 780a / 780b — Paper realism + JVM 64 KB method fix (May 16, CI GREEN)
Operator forensics on V5.9.774 build 5.0.2711 showed +\$7,218 paper P&L
fantasy: 27% WR, avg-win +383.9%, STRICT_SL_-10 booking at -94%,
RAPID_TAKE_PROFIT_30 booking at +8234%. Paper was lying to the AI
layers about edge — the bot would graduate to LIVE thinking it had a
+37%/trade prior and immediately bleed.

Six surgical paper-realism corrections:
 1) Realistic slippage curve based on liquidity tier
    (<$5k: 12% entry / 18% exit; up to >\$250k: 0.8% / 1.5%)
 2) Exit-price clamp via parsePaperExitClamp() — strategy reason
    label drives the allowed price band so STRICT_SL_-10 fills in
    [-15%, -10%], RAPID_TAKE_PROFIT_30 in [+25%, +30%], etc.
 3) Liquidity-aware return cap — single trade pnl bounded by
    (liq_usd * 0.5 / sol_price) so a \$1 position in a \$4k pool
    can't extract +8234%.
 5) isPaperScratchTrade() helper (deferred wire into RunTracker30D).
 (#4 lane size + #6 SL watchdog deferred — followup once realism
 corrections produce honest WR numbers.)

Plus the entire V5.9.779 backlog finally landed (was stuck on CI
red since the bulk cfg().paperMode → FQN replacement bloated a
method past the JVM 64 KB limit):
 - EnabledTraderAuthority (single trader-set source of truth)
 - PROJECT_SNIPER top-level gate
 - CyclicTradeEngine LIVE-when-wallet>=\$1500 (extracted to
   maybeTickCyclicTradeEngine helper to keep botLoop under 64 KB)
 - paperBuy / paperTopUp LIVE-mode hard block
 - 36 cfg().paperMode call sites → short isPaperRT()/isLiveRT()
   helpers (avoids constant-pool bloat)
 - Shadow → live moonshot full-chain handoff
 - SellReconciler.sellTrigger callback + rehydrateTokenStateFromTracker
 - LiveTradeLogActivity RecyclerView refactor (MAX_VISIBLE_GROUPS=30,
   MAX_EVENTS_PER_GROUP=15, singleton SimpleDateFormat)
 - HostWalletTokenTracker manual-swap detection (CLOSED_SOLD_BY_AATE
   + CLOSED_EXTERNALLY_MANUAL_SWAP statuses, 90 s grace window)
 - MEME_LIVE_BUY_MUTEX serialising live buys per wallet
 - PumpPortal RPC-empty HOST_TRACKER_TX_PARSE fallback
 - MemeVenueRouter (VENUE_RESOLVE forensic) + ROUTE_ATTEMPT logging
 - SELL_AMOUNT_AUTHORITY forensic on every sell path

CI: Build APK ✅ + Runtime Smoke ✅ on sha=7c043e6.

### V5.9.777 / 777a — Live-mode containment surgical fix (May 16, CI GREEN)
Operator forensics_20260516_014510.json on AATE 5.0.2706: 10-section
regression report — zero EXEC counters despite landed live buys, live
positions vanishing on stop/start, wallet truth reconciler dormant,
sells stuck at BALANCE_UNKNOWN, perps auto-replay running with only
Meme enabled.

Triage agent RCA pinned 5 actionable root causes + 1 missing-feature:

ROOT CAUSE A/G — EXEC_LIVE_ATTEMPT counter never wired
File: engine/Executor.kt
liveBuy() and liveTopUp() never called ForensicLogger.exec(), so the
PipelineHealthCollector.onExec funnel showed EXEC=0 while 5 confirmed
buys landed. Fix: emit LIVE_BUY_ATTEMPT + MEME_LIVE_EXEC_ENTRY on
liveBuy/liveTopUp entry, LIVE_BUY_OK at BUY_VERIFIED_LANDED.

ROOT CAUSE C — stopBot wiped live OPEN_TRACKING positions
Files: engine/BotService.kt, engine/HostWalletTokenTracker.kt
WC2026 / early / GOAT vanished after a stop/start cycle because
clearAll() wiped live positions alongside paper. Fix: new
clearPaperOnly() preserves OPEN_STATUSES + SELL_PENDING/VERIFYING.
BotService.stopBot() now calls clearPaperOnly() in LIVE mode and
selectively retains status.tokens entries with live open positions.

ROOT CAUSE D — LiveWalletReconciler never ran a periodic tick
File: engine/sell/LiveWalletReconciler.kt + BotService.kt
Reconciler had only on-demand reconcileNow() (30 s throttled). Fix:
added start(walletProvider) periodic tick (6 s in LIVE) on
Dispatchers.IO; BotService.startBot() starts it when !cfg.paperMode,
stopBot() stops it.

ROOT CAUSE E — Partial / PumpPortal sells bailed on RPC-empty
File: engine/Executor.kt resolveConfirmedSellAmountOrNull()
V5.9.775 added HOST_TRACKER fallback inside liveSell() proper, but
the partial-sell pre-resolver still returned null on empty RPC,
downgrading RPD to the Jupiter slippage ladder. Fix: mirror the same
authoritative-source rules — synthesise ConfirmedSellAmount from
tracker uiAmount/decimals; emit SELL_QTY_SOURCE=HOST_TRACKER with
site=resolveConfirmed.

ROOT CAUSE J — PerpsAutoReplayLearner started unconditionally
File: engine/BotService.kt
Even with only Meme enabled, the perps auto-replay learner kept
ticking. Fix: gated behind marketsLaneOn with TRADER_GATE
PERPS_AUTO_REPLAY enabled/started forensic line.

V5.9.777a hotfix: stopBot() had no local `cfg` — load ConfigStore
inside stopBot() with a try/fallback to PAPER-like behaviour.

NOT CHANGED (per triage):
 - F (paper sells visible in LIVE UI): stale shutdown artefacts from
   pre-V5.9.772 writes. Display filtering is a UX follow-up.
 - H (ANR): UI refactor substantial; operator marked secondary.
 - I (FDG bootstrap policy): feature request, not regression.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on sha=ebc1723.

### V5.9.776 — Live-mode wiring restoration (May 16, CI GREEN)
Operator forensics V5.9.774 AATE 5.0.2705: zero live trades. FDG allow=0
block=65, SAFETY_NOT_READY_MISSING=58. CryptoAltTrader emitted SIGNAL:
HBAR/ICP/VET/FIL/RENDER/GRT/AAVE/MKR/SNX/CRV and MarketsTrader emitted
SIGNAL: GOOGL/AMZN while their UI toggles were OFF.

Triage agent RCA: two root causes, surgical one-push fix:

ROOT CAUSE A — FDG-Safety async race + silent exception swallow
File: engine/BotService.kt processTokenCycle()
The first-ever safety check launched scope.launch{} asynchronously;
the same cycle then continued to FDG which read ts.lastSafetyCheck==0L
and hard-blocked SAFETY_NOT_READY_MISSING. The async job also swallowed
ALL exceptions silently (empty catch on Exception), so when RugCheck
502'd or network timed out, lastSafetyCheck NEVER got stamped and FDG
blocked forever.

Fixes:
 1) FIRST-EVER check (lastSafetyCheck == 0L) runs SYNCHRONOUSLY now.
 2) On ANY exception we stamp lastSafetyCheck = now and write a
    HARD_BLOCK SafetyReport with reason SAFETY_RUN_FAILED; exception
    logged loudly via ErrorLogger + ForensicLogger.
 3) STALE refresh (>10 min) stays async — fast hot loop preserved.
 4) Every successful write emits SAFETY_WRITE key=<canonicalMint>.
 5) FinalDecisionGate emits SAFETY_READ key=<canonicalMint> found=…
    ageMs=… reader=FDG verdict=MISSING/STALE on every miss (existing
    per-mint dedupe), closing the SAFETY_WRITE/SAFETY_READ audit loop.

ROOT CAUSE B — Trader toggle bypass on initial scan
Files: perps/CryptoAltTrader.kt, perps/TokenizedStockTrader.kt, BotService.kt
Both traders' start() launched an UNCONDITIONAL initial runScanCycle()
ignoring isEnabled. BotService called CryptoAltTrader.start()
unconditionally regardless of cfg.cryptoAltsEnabled.

Fixes:
 1) CryptoAltTrader.start() — initial scan gated by isEnabled.get().
 2) TokenizedStockTrader.start() — same gate.
 3) BotService — CryptoAltTrader.start() wrapped in
    if (cfg.cryptoAltsEnabled), stale instance stopped in else.
    Emits TRADER_GATE CRYPTO_ALT enabled=… line.
 4) BotService — TokenizedStockTrader gated branch emits TRADER_GATE
    MARKETS/STOCKS enabled=… and stops stale instances.

CLEARED (not root causes, per triage):
 - Paper contamination: all buy/sell paths properly gated by cfg().paperMode
 - Lane cap pollution: only live positions call WalletPositionLock.recordOpen()
 - MEME_REGISTRY_RESTORE: re-admits to watchlist only, doesn't restore positions

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on sha=df1846d.

### V5.9.775 — Surgical live-sell pipeline restoration (May 16, CI GREEN)
Operator forensics_20260516_001259.json: HODL + GPT live positions
stuck OPEN_TRACKING with wallet_uiAmount>0, last_sell_signature
empty, but every sell attempt returned SELL_BLOCKED_ALREADY_IN_PROGRESS
lock=true. No SELL_TX_BUILT, no SELL_BROADCAST, no SELL_CONFIRMED.

Two root causes — one clean surgical fix:
 1) SellExecutionLocks had no TTL (pure AtomicBoolean). Replaced with
    timestamped ConcurrentHashMap + 60 s TTL; tryAcquire() evicts stale
    entries first, isLocked() lazy-evicts. ageMs()/forceRelease()
    exposed; blockIfSellInFlight() logs ageMs alongside SELL_BLOCKED.
 2) Executor.liveSell() returned FAILED_RETRYABLE on RPC-EMPTY-MAP even
    when HostWalletTokenTracker had wallet_uiAmount>0 with OPEN_TRACKING
    status and last_sell_signature empty. Added HOST_TRACKER fallback:
    synthesise tokenData from tracker uiAmount/decimals, continue into
    PumpPortal-first → Jupiter ladder. New forensic line
    SELL_QTY_SOURCE=HOST_TRACKER (and SELL_QTY_SOURCE=RPC on normal path).

PumpPortal-first routing already in place at Executor.kt:8955 — sells
now actually REACH it instead of returning early on RPC blip.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on sha=489841e.

### V5.9.774 — Live sell triage RCA (NOT a regression; visibility + 1 dust-bug) (May 15)
Operator escalation against forensics_20260508_071749.json claiming
6 unsafe-sell issues + "the triage agent and I had live buying and
selling working perfectly. I want the triage agent to check it again."

Triage agent verdict: NOT a full regression.
  - SellJobRegistry state machine (V5.9.767) intact end-to-end.
  - V5.9.766 upstream FDG safety gate firing correctly (29 blocks
    in operator dump).
  - V5.9.765 per-mint executor dedupe working (21 drops).
  - V5.9.769 max-take liquidity freshness alive.
  - V5.9.773 shadowPaperEnabled now false by default.
  - V5.9.772 Treasury Scalps respect global mode.

Real bugs found (only 2):

1) ForensicReportExporter exports PositionWalletReconciler (phantom-
   detection) stats but NOT SellReconciler (live-wallet sync) stats.
   Operator's "reconciler.totalChecked=0" was a visibility bug; the
   live SellReconciler.tick() is running every 10s. Added new
   `sell_reconciler` block with totalTicks, totalChecked,
   lastTickAtMs, isStarted, activeJobs(=SellJobRegistry size).
   New @Volatile fields on SellReconciler: lastTickAtMs, isStarted.

2) Executor.kt:8709 `tokenUnits = actualRawUnits.coerceAtLeast(1L)`
   could force a 1-raw-unit broadcast when actualBalanceUi was so
   dust that raw floor-divided to 0. Added explicit
   `if (actualRawUnits <= 0L) return FAILED_RETRYABLE` with
   DUST_BALANCE_NO_BROADCAST forensic emit.

Still in play for future cycles (operator's required-changes A-F):
  A. SellAmountAuthority unified resolver class extraction
  B. PumpPortal partial-sell disable + 95% gate
  C. TREASURY_TAKE_PROFIT default-fraction audit
  D. Caller-side RAPID_TRAILING_STOP rate limit
  E. Reconciler hooks on buy-verify + sell-broadcast
  F. STATE_DOWNGRADE_BLOCKED audit

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on 9ca9ea7eb.

### V5.9.773 — "wtf paper or live" definitive UX fix (May 15)
Operator: "I'M LIVE YET ALL I SEE IS PAPER TRADES WHAT THE FUCK"

Troubleshoot agent RCA: bot was in PAPER mode all along
(PipelineHealthCollector modeSnapshot=PAPER). Operator read
"🟢 LIVE READY · Jupiter + Pyth healthy" banner and thought
they were live. Plus shadowPaperEnabled defaulted to true.

Three surgical fixes:
  1. LiveReadinessChecker banner: "LIVE READY" → "APIs READY"
  2. MainActivity.kt:1806 balance bar shows explicit big chip:
     "📝 PAPER MODE ◎ 0.3944" or "🔴 LIVE MODE ◎ 0.3944"
  3. BotConfig.shadowPaperEnabled default true → false. Operator
     must explicitly opt in to shadow learning.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on d42927e74.

### V5.9.772 — Treasury Scalps respect global trade mode (May 15)
Root cause (CyclicTradeEngine.kt:121-122): isLiveMode formula only
checked cyclic-specific flags, never cfg.paperMode. In live mode
without cyclic-live opt-in, Treasury fired paper trades that bled
into live UI.

Fix:
  globalLive = !cfg.paperMode
  if (globalLive && !cyclicLiveOptedIn) skip tick (no paper bleed)
  else isLiveMode = globalLive
New forensic: LIFECYCLE/TREASURY_LIVE_NOT_OPTED_IN

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on ba3930ed6.

### V5.9.771 — Surgical 6-in-1 EMERGENT MEME (May 15)
Operator: "EMERGENT — MEME TRADER ONLY. surgical one push not 7."
All 9 critical findings addressed in a single commit:
  1. data/CanonicalMint.kt new — single mint-key + BLOCKED_MEME_SYMBOLS
  2. admitProtectedMemeIntake blocked-symbol final enforcement at entry
  3. MainActivity.buildUnifiedOpenPositions mode-filtered by
     position.isPaperPosition == state.config.paperMode
  4. SolanaWallet.rpc()/getSolBalance() throw on Dispatchers.Main +
     WALLET_RPC_ON_MAIN_THREAD forensic
  5. Executor.paperBuy() entry hard-block when !cfg.paperMode &&
     !cfg.shadowPaperEnabled
  6. PipelineHealthCollector interpretation derived from actual
     top reasons (FDG block reason, ANR severity from frame gap,
     LIVE-mode + EXEC_PAPER_BUY_OK → MODE CONTAMINATION)

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on 6160642dc.

### V5.9.770 — pivot WR-Recovery counters from in-memory to persisted source (May 15)
Operator screenshots 2026-05-15 21:14 — Main dashboard showed
WR=27% (575W / 1499L / 907S = 2981 trades), Phase 4 target 44.9%.
Guards strip showed "1 streak-block · 4 coaching" but NO
"🚑 WR recovery @9%" tag despite WR being deep below target × 0.85.
Operator question: "check why the winrate/winratio recovery isn't
running to drive back up % ratio."

Root cause:
  WrRecoveryPartial.effectiveTrigger() and statusTag() pulled W/L
  counts from CanonicalLearningCounters.settledWins / settledLosses
  — declared as AtomicLong(0). These are in-memory only and reset
  on every process death. Every UI surface (readiness tile, journal,
  tuning) reads from TradeHistoryStore (SQLite-persisted) — so the
  operator saw 2981 trades / 27% WR everywhere except inside the
  recovery decision, which saw 0 / 0% and skipped on total < 50.

Fix:
  effectiveTrigger() and statusTag() now read from
  TradeHistoryStore.getLifetimeStats().totalWins / totalLosses.
  Wrapped in try/catch for cold-start safety. No trigger math change.

Expected:
  Guards strip immediately shows "🚑 WR recovery @9%" when WR
  (persisted) < phase target × 0.85. Activation survives restarts /
  OEM kills. First-partial trigger drops from normal (~15%) to 9%.

Adjacent observations (NOT fixed — separate tickets):
  - 94.9% main-thread stall, 882s accumulated in 930s uptime.
  - 21,573 mints in watchlist, ANR top sites all MainActivity render
    methods (renderOpenPositions, buildTokenCard, updateCryptoAltsCard).
  - 41s avg cycle, 130s max. UI thread saturation, not bot logic.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on 963df4cbf.

### V5.9.769 — kill stale-liquidity FDG starvation (P1 #2 + V5.9.768 regression fix) (May 15)
Operator dump @ V5.9.767 showed 824/839 (98%) FDG decisions blocked
on LIQUIDITY_BELOW_EXECUTION_FLOOR, including SOLMAN whose intake
events reported liq=$23,579 (well above any FDG floor). Two bugs:

ROOT CAUSE 1 — Stale-seed lock (BotService.kt:5803)
  Original: `if (liquidityUsd > 0.0 && ts.lastLiquidityUsd <= 0.0)`
  This wrote liquidity ONLY on a fresh-from-zero seed. After the
  first PUMP_PORTAL_WS intake landed (typical $2,000-2,400 from
  the bonding-curve quote), subsequent RAYDIUM_NEW_POOL intakes
  with the real $15-25k pool liquidity were silently dropped.
  FDG then evaluated against the stale low value and hard-blocked.
  Same pattern for lastMcap.
  Fix: max-take semantics — higher fresh value always wins.

ROOT CAUSE 2 — V5.9.768 dedupe regression
  My V5.9.768 dedupe early-returns BEFORE the state-hydrate block,
  making the V5.9.769 max-take fix effectively dead code for the
  most common case (same-mint double-emit that drove V5.9.768).
  Fix: even on a dedupe hit, run a tiny `synchronized(status.tokens)`
  block that does the max-take freshness write. Costs one map lookup
  + two field writes — negligible vs the registry-add we skip.

Expected dump deltas:
  - FDG allow/block ratio should swing massively as fresh liquidity
    actually reaches the gate.
  - PHASE/FDG block reason histogram shifts from
    LIQUIDITY_BELOW_EXECUTION_FLOOR dominance to a more diverse mix.
  - INTAKE_DEDUPE_DROP volume unchanged.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on 385572c2b.

### V5.9.768 — INTAKE DEDUPE (P1 watchlist pollution / 7-point list) (May 15)
Operator forensic dump @ V5.9.767 (1688 s uptime):
  - INTAKE = 5478 events ≈ 3.24/sec
  - Top: Ebola=191x, immunecoin=109x, COAR=79x, WORLDCUP=75x
  - Same mints appearing twice at same millisecond
    (src=RAYDIUM_NEW_POOL + src=SCANNER_DIRECT_RAYDIUM_NEW_POOL)
  - Avg bot-loop cycle 62.4 s (target <30 s)

Root cause (BotService.kt):
  Line 3095 emits SCANNER_DIRECT_<source> for every scanner hit.
  Line 3118 ALSO emits with raw <source> when
  GlobalTradeRegistry.isWatching(mint)==true. MemeMintRegistry
  pre-hydration makes every post-restart mint already-watching,
  so the second call always fires. Plus 11 internal call sites
  for TokenMergeQueue / PumpPortal WS / DataOrchestrator hammering
  the same mint over and over.

Fix (BotService.kt admitProtectedMemeIntake):
  - New `intakeLastAcceptMs` + `intakeDedupCount` per-mint maps.
  - 30 s TTL. After the mint-validity check, if (now - prev) < TTL
    return true (idempotent semantics preserved — the function is
    fail-open and downstream registry/state are getOrPut).
  - One `LIFECYCLE/INTAKE_DEDUPE_DROP` forensic per mint per
    window. Subsequent drops silent; counter exposed via the map
    for any future "top dedupe offenders" UI.
  - Opportunistic prune at 1024 entries.

Expected dump deltas:
  - PHASE/INTAKE drops ~10x (5478 → ~500-700).
  - New LIFECYCLE/INTAKE_DEDUPE_DROP counter shows suppression
    volume for operator visibility.
  - BOT_LOOP_TICK avg cycle ms should drop noticeably.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on e18cae53c.

### V5.9.767 — Executor migrated to SellJobRegistry.transitionTo() end-to-end (May 15)
Completes the P1 task from V5.9.766's finish summary: observable
state-machine for every live-sell broadcast.

Background:
  SellJobRegistry was created in V5.9.764 with a getOrCreate() at
  Executor.kt line 8705, but the executor never invoked transitionTo()
  during the broadcast → confirm → verify lifecycle. Jobs stayed in
  IDLE in the registry snapshot. Operator dumps could see
  SELL_JOB_CREATED but no progression; the reconciler tick was the
  only thing that ever flipped status to LANDED.

Fix (Executor.kt):
  PATH 1 — main Jupiter live full sell (~line 8966-9351)
    after SELL_TX_BUILT  → transitionTo(BUILDING)
    after SELL_BROADCAST → transitionTo(BROADCASTING)
    after SELL_CONFIRMED → transitionTo(CONFIRMING)
    before TradeVerifier.verifySell → transitionTo(VERIFYING)
    Outcome.LANDED       → markLanded(sig)
    Outcome.FAILED_CONFIRMED → transitionTo(FAILED_FINAL)
    legacy SELL_VERIFY_TOKEN_GONE success → markLanded(sig)

  PATH 2 — tryPumpPortalSell (~line 11080-11112)
    after SELL_TX_BUILT  → transitionTo(BUILDING)
    after SELL_BROADCAST → transitionTo(BROADCASTING)
    after SELL_CONFIRMED → transitionTo(CONFIRMING)
    LANDED resolved by SellReconciler (already wired at line 219 to
    markLanded when balance reaches zero).

All transitions wrapped in try/catch — registry hiccups must not
abort a live sell. Brace check: Executor.kt 2817/2817 balanced.

Forensic dump deltas: LIFECYCLE/SELL_TX_BUILT, SELL_BROADCAST,
SELL_SIG_CONFIRMED, SELL_VERIFY_STARTED, SELL_CLOSED_TRACKER and
SELL_FAILED_FINAL counters now drive end-to-end. The 60s
LOCK_TTL_MS stale-detection in isLockedAndFresh now sees real
in-flight phases instead of permanent IDLE.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on ae6da3f58.

### V5.9.766 EMERGENT MEME — upstream SafetyReady gate in FDG (May 15)
Completes the deferred ticket from V5.9.765 commit message
("Full upstream gate deferred to V5.9.766+ — needs FDG path audit").

Problem:
  V5.9.765 cut the visible spam (17 BUY_FAILED LIVE_BUY_BLOCKED_RISK
  SAFETY_DATA_MISSING events in 1.28s) by adding a 60s per-mint
  dedupe at the EXECUTOR (LiveBuyAdmissionGate). But the candidate
  was still reaching the executor before being blocked — wasted CPU
  and a periodic block-event emission every 60s.

Fix (FinalDecisionGate.kt):
  - New import: engine.sell.LiveBuyAdmissionGate (reuses
    SAFETY_STALE_MS = 120s — single source of truth).
  - New private dedupe map + shouldEmitSafetyReadyBlock(mint) helper
    (60s cooldown, opportunistic prune above 256 entries).
  - New early-return block in evaluate() right after mode is computed:
    LIVE + (lastSafetyCheck == 0L OR age > SAFETY_STALE_MS) returns
    BLOCKED(HARD, sizeSol=0, SAFETY_NOT_READY_MISSING|STALE) BEFORE
    any symbolic / brain / FDG learning logic runs.
  - PAPER mode intentionally exempt — paper trades treat safety as
    scoring input, not a hard gate. Bot keeps learning when rugcheck
    is slow/down.
  - New forensic event: LIFECYCLE/FDG_BLOCKED_SAFETY_NOT_READY
    (≤1 emit per mint per 60s).

Expected dump deltas:
  - LIVE_BUY_BLOCKED_RISK[liveBuy.main] SAFETY_DATA_MISSING rows
    drop to near-zero (executor never runs).
  - LIVE_BUY_DEDUPE_DROP volume drops (no longer needed for this case).
  - FDG_BLOCKED_SAFETY_NOT_READY surfaces upstream.
  - RejectionTelemetry attributes the block to FDG.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on bd885b45f.

### V5.9.765b — CI hotfix for V5.9.765 (May 15)
Two compile errors in V5.9.765 slipped past pre-push checks because
this codebase has no local compiler. Surgical hotfix only.

1) SellReconciler.kt:136 — DexscreenerApi.batchPriceFetch() called as
   companion-static, but the function is an instance member of
   `class DexscreenerApi`. Fix: `DexscreenerApi().batchPriceFetch(...)`.
   One allocation per 10s reconciler tick = negligible.

2) LiveTradeLogActivity.kt:310 — V5.9.765 added a new Phase enum value
   WATCHLIST_PROTECT_BLACKLISTED_TOKEN but the colorForPhase(...)
   when-expression was not updated, breaking exhaustiveness. Added the
   new value next to its HELD_TOKEN cousin in the blue branch.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on 04f0f9864.

### V5.9.765 EMERGENT MEME — price hydration + dedupe + watchlist rename + forensic anomalies (May 15)
Operator forensics_20260515_161017.json showed:
  - 17 BUY_FAILED LIVE_BUY_BLOCKED_RISK[liveBuy.main] SAFETY_DATA_MISSING
    events in ~1.28s for a handful of mints.
  - host_tracker had 2 OPEN_TRACKING positions (HIM, CABAL) with
    currentPriceUsd = 0.0 — no maxGainPct, no exit lifecycle.
  - reconciler.totalChecked = 0 (the V5.9.764 reconciler was being
    throttled by a 30s startup cooldown).
  - forensics_events = [] despite obvious anomalies.
  - 276 WATCHLIST_PROTECT_HELD_TOKEN events for shadow-banned tokens
    that were NOT actually wallet-held.

Fixes (MEME path only — crypto universe untouched):
  - SellReconciler now emits a one-shot RECONCILER_START forensic per
    service lifecycle so dumps prove the loop is up.
  - SellReconciler now batches DexScreener prices for every
    OPEN_TRACKING mint per tick and pushes results into
    HostWalletTokenTracker.recordPriceUpdate (which already maintains
    currentPriceUsd / maxGainPct / maxDrawdownPct).
  - Per-mint zero-price streak counter — after 2 consecutive ticks
    of priceUsd=0 emits PRICE_STALE_LIVE_POSITION +
    LIVE_POSITION_PRICE_ZERO. Single-tick rate-limit blips don't fire.
  - LiveBuyAdmissionGate gained a 60s per-mint cooldown on block
    emission (LIVE_BUY_DEDUPE_DROP forensic captures suppressed spam).
  - LiveTradeLogStore.Phase split: HELD_TOKEN now reserved for genuine
    wallet-held tokens; new BLACKLISTED_TOKEN value covers the shadow-
    ban / intake-blacklist paths. Both BotService call sites updated.

NOTE: Upstream FDG SafetyReady gate was deferred to V5.9.766 (shipped
above) — see that entry for the completion.

### V5.9.764 EMERGENT CRITICAL — sell-job state machine + qty-guard + reconciler (May 15)
Operator forensics_20260515_151634.json: HIM live full sell qty=122210.6058
retried at qty=1331.4409 (1.1%); CABAL qty=7330.5470 retried at qty=88.5843
(1.2%). `last_sell_signature` empty for both — no sell ever landed. Sell-lock
spammed SELL_BLOCKED_ALREADY_IN_PROGRESS, reconciler.totalChecked = 0. Real
live SOL at risk.

Full EMERGENT spec A–F shipped as ONE surgical push:

NEW FILES
- engine/sell/SellJobRegistry.kt
  - data class SellJob + enums SellJobStatus / SellJobMode per spec.
  - LOCK_TTL_MS = 60s. isLockedAndFresh() auto-demotes to FAILED_RETRYABLE
    and emits SELL_LOCK_STALE_FORCE_RELEASED past TTL.
  - SellQtyGuard.guard(): blocks any FULL_EXIT-class broadcast whose qty
    is < 90% of authoritative wallet balance. Reasons matched:
    RUG, CATASTROPHE, HARD_FLOOR, HARD_STOP, STARTUP_SWEEP,
    FALLBACK_ORPHAN, STRICT_SL, TREASURY_STOP_LOSS,
    STALE_LIVE_PRICE_RUG_ESCAPE, MANUAL, FULL_EXIT, EMERGENCY,
    STOP_LOSS, DRAIN, LIQUIDITY_COLLAPSE. Emits
    SELL_RETRY_QTY_CHANGED_BLOCKED + SELL_RETRY_FULL_BALANCE.

- engine/sell/SellReconciler.kt
  - 10s tick in LIVE mode only.
  - Scans HostWalletTokenTracker.getOpenTrackedPositions(), one wallet
    read per pass via SolanaWallet.getTokenAccountsWithDecimals().
  - balance == 0  -> tracker closed, SELL_VERIFY_BALANCE_ZERO emitted.
  - balance > 0 past TTL -> registry force-release + RECONCILER_EXIT_REQUEUED.
  - Emits RECONCILER_TICK / RECONCILER_OPEN_CHECKED every tick.

MODIFIED
- engine/Executor.kt: SellQtyGuard.guard() called right after the on-chain
  qty override (line 8649). SellJobRegistry.getOrCreate() called next.
  acquireSellLock/releaseSellLock emit SELL_LOCK_SET / SELL_LOCK_RELEASED.
- engine/BotService.kt: startBot launches SellReconciler; stopBot tears down.
- engine/PipelineHealthCollector.kt: BUILD_TAG -> V5.9.764. All new
  LIFECYCLE/SELL_* and LIFECYCLE/RECONCILER_* counters auto-pinned via the
  existing onLifecycle -> labelCounts -> render pipeline (V5.9.677 infra).

CI: Build AATE APK ✅ GREEN on b87d5f06c.

### V5.9.763 — surface live positions from Project Sniper + Manipulated lanes (May 15)
Operator screenshot V5.9.761: HIM held by Project Sniper at -16.5% (live mode)
visible in lane card but absent from unified Open Positions panel. Root cause:
buildUnifiedOpenPositions walked only ShitCoin/Quality/Moonshot.
- ProjectSniperAI getActiveMissions() already existed (line 583).
- MainActivity.buildUnifiedOpenPositions now walks Sniper missions + Manipulated
  positions with proper isPaper flag derivation.

### V5.9.762 EMERGENT CRITICAL #1 — heartbeat/rescue rewrite (May 15)
Operator V5.9.761 dump: 104 RESCUE events, GlobalScope band-aid, BOTLOOP
ripped out of slow-but-healthy cycles by spurious cancels. Surgical rewrite
of ACTION_LOOP_HEARTBEAT:
- New state: loopJobLock (CAS), lastProgressAtMs (volatile), currentPhase
  (volatile), activePhaseSet, rescueProgressGraceMs=180s.
- markProgress(phase) wired into emitBotLoopTick + ENTER + PRE_SUPERVISOR +
  SUPERVISOR + POST_SUPERVISOR + EXIT_SWEEP + IDLE + CYCLE_EXIT.
- New decision tree: HEARTBEAT_OK / HEARTBEAT_SLOW_NO_RESCUE /
  HEARTBEAT_RESCUE_SUPPRESSED_ACTIVE_PHASE / RESCUE_DENIED_EXISTING_JOB /
  RESCUE_RELAUNCHED_SERVICE_SCOPE. NO GlobalScope, NO ACTION_START fallback.

### V5.9.761 — Meme sub-trader paper-mode leak (operator regression) (May 15)
Operator report: "in the meme trader when running live there are still
traders running in paper mode!! check all".

Root cause audit of every v3/scoring/*.kt @Volatile isPaperMode field:

  | Trader                | setTradingMode? | Synced per-loop? |
  |-----------------------|-----------------|------------------|
  | CashGenerationAI      | YES             | YES (line 6759)  |
  | ShitCoinTraderAI      | YES             | YES (line 6763)  |
  | BlueChipTraderAI      | YES             | YES (line 6764)  |
  | MoonshotTraderAI      | YES             | YES (line 6765)  |
  | QualityTraderAI       | YES             | YES (line 6766)  |
  | ShitCoinExpress       | NO              | NO  ← LEAKING    |
  | ManipulatedTraderAI   | NO              | NO  ← LEAKING    |
  | DipHunterAI           | NO              | NO  ← LEAKING    |
  | SolanaArbAI           | NO              | NO  ← LEAKING    |

All four leaking traders have @Volatile isPaperMode set ONCE by their
init() function, itself gated by an `initialized` flag with a
'BLOCKED — already initialized' warning. When the user toggles Paper→Live
in the UI, the main-loop per-tick sync block reaches the 5 properly-synced
traders but never touches these four → they keep placing PAPER trades
while ShitCoin/BlueChip/Moonshot/Quality have moved to LIVE. Exact
operator symptom.

Fix:
- Added `fun setTradingMode(paperMode: Boolean)` to each of the four
  leaking traders. Cheap @Volatile write, no heavy state rebuild.
  Logs only on actual transition.
- Wired all four helpers into the existing per-tick sync block at
  BotService.kt:6755-6766 with a comment block pointing back at this
  fix for future audits.

No behaviour change when the user never toggles. When they do toggle,
all 9 meme sub-trader layers now switch in lock-step within one loop tick.

BUILD_TAG bumped to V5.9.761. CI: Build AATE APK ✅ GREEN on 48fc87650.

### V5.9.760 — Bot loop rescue resiliency (GlobalScope fallback + ACTION_START last resort) (May 15)
Operator V5.9.759 dump diagnosed silent rescue failure: 104 LOOP_HEARTBEAT_RESCUE
events emitted but BOTLOOP_STARTED stayed at 2 and ZERO RESCUE_CANCEL_SENT /
RESCUE_JOIN_OK / RESCUE_RELAUNCHED events fired. The PipelineHealthCollector
pins ALL LIFECYCLE/* counters (V5.9.677), so if RESCUE_RELAUNCHED had fired
once it would appear in the dump. It didn't — meaning every scope.launch(IO)
returned a Job whose body never executed. Most likely cause: BotService's
class-level scope parent Job got cancelled (no scope.cancel() outside
onDestroy, but symptom is identical). Once dead, scope.launch returns an
already-cancelled Job — no exception, no forensic, just silent inactivity.
Bot ran 584 cycles across 2 user-pressed starts then sat zombie for ~5h
while the alarm fired uselessly every 60s and rescue branch entered every
180s without effect. Last successful BUY was 4h48m before the dump.

Fix (BotService.kt rescue path):
- RESCUE_SCOPE_PROBE fires on the heartbeat thread BEFORE the launch — captures
  scope.isActive / .isCancelled / .isCompleted so the next dump definitively
  shows whether scope is dead.
- RESCUE_BODY_ENTERED is the FIRST line of the rescue body — proves whether
  the launch scheduled at all.
- Rescue body migrated from scope.launch to GlobalScope.launch(Dispatchers.IO).
  GlobalScope is process-wide and only dies with the JVM; can't be silently
  cancelled by service code. Relaunched botLoop also runs via GlobalScope.
- Final fallback: 500ms after relaunch, if newJob.isActive is still false
  AND status.running is true, fire ACTION_START intent so Android's
  onStartCommand path runs startBot() from scratch. Emits
  RESCUE_FINAL_FALLBACK_INTENT.

Purely additive — old rescue branch logic preserved verbatim inside the
GlobalScope launch. No behavioural change when scope is healthy.

BUILD_TAG bumped to V5.9.760. CI: Build AATE APK ✅ GREEN on d8f4304d0.

### V5.9.659 → V5.9.660b — JVM 64KB botLoop fix (May 10)
Extracted Markets watchdog + Scanner heartbeat as helpers; fixed signature mismatch.

### V5.9.661 — Unconditional position close on every stop ✅ user-verified (May 10)
Removed `if (cfg.closePositionsOnStop)` gate in stopBot() and onDestroy().

### V5.9.661b — Runtime Smoke Test actually starts the bot (May 10)
New SmokeTestReceiver.kt + UI tap fallback; funnel summary upgrade.

### V5.9.661c — UI "11 Open" stale counter fix (May 10)
TokenLifecycleTracker.clearAll() + HostWalletTokenTracker.clearAll().

### V5.9.662 → V5.9.662d — Unblock Moonshot/Quality/BlueChip/V3 lanes (May 10)
- 662   : MoonshotTraderAI minRcScore 5→1 paper; FatalRiskChecker paper-learning bypass.
- 662b  : TradeAuth UNKNOWN_QUALITY paper floor 5→1.
- 662d  : Replaces 662c (over-zeroed). Trade-maturity RAMP: 5% pre-3000, linear 3000-4999,
          100% from 5000+. Stats counters always increment so learning still happens.
- BIG LOSS log spam → debug.

### V5.9.663 — Settings cleanup (May 10)
Removed ACTIVE TOKEN field from dialog_settings.xml + SettingsBottomSheet.kt.

### V5.9.663b — Quality lane unblock + 70/30 paper dust floor + UI poll throttle (May 10)
- QualityTraderAI: paper-bootstrap minAge 5min → 1min. Was the choke for 9th lane.
- TreasuryManager: split MEME_SELL_MIN_PROFIT_SOL into live (0.003) + paper (0.0001).
  Paper-mode 70/30 split was effectively dead because every win < 0.003 SOL.
- BotViewModel.pollLoop: 1500ms → 2500ms (initial ANR mitigation).

### V5.9.663c — Open-position priority + watchlist 3-tick cadence (May 10) ⭐ operator-designed
- BotService.botLoop: forcedOpenMints (open positions) processed every tick;
  otherMints (watchlist) processed only when `loopCount % 3 == 0` (~3 seconds).
- Drops main-thread contention from cascading status.tokens reads by ~3x for the
  user's ~2400-mint watchlist while preserving fluid stop / TP / partial-sell
  responsiveness on open positions.

## Known Issues / In-Progress (PRIORITY ORDER)

### P0 — Verify on device (V5.9.663c build)
- ANR ("AATE isn't responding"): user-designed fix shipped. Confirm no longer happens.
- 70/30 split: confirm treasury balance grows in paper mode (look for log
  `🪙 70/30 SPLIT: profit=Xsol → treasury +Y` at debug or info level).
- Quality lane: confirm `[⭐ QUALITY ENTRY]` lines start appearing alongside
  ShitCoin entries on fresh launches (~1min old).
- Lane indicator strip: should diversify from `M-only` to `M / A / P / S` populated.

### P1 — Markets lane signals not converting to BUY
- Logs show `📈 SIGNAL: META score=65 conf=50 dir=SHORT` (and ORCL/NFLX similarly)
  but no executions follow. Investigate Executor accept criteria for Markets signals.

### P1 — True leverage for Markets lane (Drift/Parcl/Mango HTTP). Not started.

### P2 — UI Polish
- Strategy Leaderboard tile, PnL streak tile, Brain Health pill, Tune History tab.
- "Ladder" status pill on Memes tab.
- "Maturity" pill next to proof-run header showing TRADES X/5000 + STRICTNESS Y%.

## V5.9.664 — V5.9.670 changelog (May 10–11, 2026)

### V5.9.664 — Scanner ANR throttle
Doubled inter-source delay between deep-scan calls from 100ms to 200ms in
SolanaMarketScanner.kt. Conservative throttle, no logic changes.

### V5.9.665 — restore live trading: 4 surgical regression fixes
Operator forensics_20260511_010802 + live test report:
1. Meme trader did nothing in live mode.
2. Bought TRUMP "didn't land" (Jupiter confirmed but bot rejected swap).
3. Tried SHIB twice → bought USDC (USDC-collateral masquerade).
4. Bot freezes + ANR warnings the moment user switches to live.

Troubleshoot agent RCA identified three regressions. Fixes shipped:
- Fix #1: Clean revert of 758ecca26 (CryptoUniverseRouteResolver,
  CryptoUniverseExecutor, MarketsLiveExecutor). Removes
  executeUsdcCollateralExposure entirely; restores branched
  Bridge/CEX/Paper fallback when no SPL mint resolves.
- Fix #2: CryptoUniverseExecutor.kt verifyWalletDelta extended
  5×3s = 15s → 8×3s = 24s. On timeout, register the buy via
  HostWalletTokenTracker + LiveWalletReconciler.
- Fix #2b: BotService.kt stop path now calls reconcileNow() before
  TokenLifecycleTracker.clearAll() / HostWalletTokenTracker.clearAll().
- Fix #3: WalletPositionLock per-lane reservation caps —
  Meme=50%, CryptoAlt=40%, Stocks=30%, Commodities/Metals/Forex=20%.
- Fix #5 (live-start ANR): STARTUP_SWEEP_HARD_FLOOR wrapped in
  scope.launch with 5s grace + 250ms inter-position pacing.

### V5.9.665b — fix unit test for restored route-resolver contract
CryptoUniverseRouteResolverTest rewritten to validate restored contract
(BTC routes JUPITER_ROUTABLE if wrapped mint registered, else PAPER_ONLY
+ executable=false).

### V5.9.666 — In-app Pipeline Health panel + ANR detector + clipboard export
- PipelineHealthCollector singleton. Mirrors CI funnel via ForensicLogger
  hooks (zero call-site changes for phase counters).
- PipelineHealthActivity: headline grid (LOOP/EXEC/JRNL/MAX FRAME),
  ANR badge, auto-refresh, Copy to Clipboard, Reset.
- Choreographer-based ANR detector: long-frame (>700ms) events
  recorded with delta + counter.
- Hooks: TradeHistoryStore.onTradeJournal, WalletPositionLock events,
  CryptoUniverseExecutor DELTA_LATE_TRUST_SIG events.

### V5.9.666b — Pipeline tile on main UI
Added 🩺 Pipeline tile to row 2 (next to Lab) with click-to-open and
live ANR + EXEC stats badge refreshed every 3s. Long-press on Logs
kept as power-user shortcut.

### V5.9.667 — bad_behaviour penalty stacking + notification spam fix
- BotBrain.getSuppressionPenalty + isHardSuppressed: maturity ramp
  applied at READ time (mirrors V5.9.662d ramp on tilt/discipline).
  At 0 trades: penalty × 0.0; at 5000+: full strength. Sub-2500-trade
  bots cannot hard-suppress anything.
- BotBrain.evaluateBadBehaviours: lastBadStatus map keyed by
  featureKey; only fires onParamChanged on TRANSITION (not every tick).
- BotService.onParamChanged: suppresses system notification for
  bad_behaviour: prefixed keys.

### V5.9.668 — surgical fixes from operator's first Pipeline Health dump
- A) Stack-trace freeze capture: capture main-thread stack on long
  frames (later proven inadequate — captured POST-freeze; superseded
  by V5.9.670 watchdog).
- B) IOOBE crash localisation: append 'at ClassName.method:line'
  for the first com.lifecyclebot frame to GATE_BLOCK reason.
- C) SAFETY → V3 drop-off visibility: emit GATE_BLOCK on PHASE.V3
  with reason 'V3_SKIPPED position_open | v3_disabled | v3_not_ready'.
- D) Lock-free PipelineHealthCollector ring buffer (ConcurrentLinkedDeque
  + AtomicInteger size counter) — pipeline writers never block UI poll.

### V5.9.669 (corrected) — wire V3 as a main trader to the real executor
Operator correction: 'legacy and v3 are meant to work together! v3 is
one of the main traders! legacy feeds into v3s decision matrix. legacy
isnt isnt even wired to the learning system!'

Earlier V5.9.669 attempt was wrong (silenced V3's no-callback error
without wiring real execution). Build failed on Kotlin lambda label
which fortunately prevented broken fix from shipping.

CORRECT FIX:
- Wire V3EngineManager.initialize(onExecute = ...) to a new
  BotService.runV3Execution(req) bridge that:
  * Reuses manualBuy()-style wallet/walletSol resolution.
  * Calls executor.doBuy(ts, sol = req.sizeSol, ..., quality='V3')
    with V3's actual chosen size (was being dropped to ~0.06 SOL via
    legacy backup; V3 wanted ~0.95 SOL, ~15× larger).
  * Returns ExecuteResult so V3's TradeExecutor.executeCallback
    registers the entry into v3Entries / outcome tracker. V3 finally
    learns from every trade it makes.
- Plus: FDG counter wired into pipeline funnel via
  ForensicLogger.phase(PHASE.FDG, ...) + gate(PHASE.FDG,
  allow=fdgDecision.canExecute(), ...).

### V5.9.670 — proper watchdog ANR sampler + maxed-out diagnostic dump
Operator feedback on V5.9.668/669: every ANR_HINT stack trace just
showed captureMainThreadStack itself. The Choreographer-based sampler
captured the stack AFTER the main thread unblocked.

V5.9.670 fix (watchdog-thread sampler):
- Spawn 'ANR_Watchdog' HandlerThread (MIN_PRIORITY).
- Every 250ms watchdog posts no-op Runnable to main Handler that
  updates AtomicLong 'ackTs'. If ackTs hasn't moved in >700ms, sample
  mainThread.stackTrace AT THAT MOMENT — captures the actual
  blocking call site.
- De-dup: same top frame blocking for 10s+ emits ONE ANR_HINT event +
  increments anrStackCounts. Dump shows top 20 grouped.
- Stack filter strips PipelineHealthCollector / VMStack /
  Thread.getStackTrace from operator-visible trace.

Expanded diagnostic dump sections (NEW):
- Bot-loop cycle timing (cycles seen, avg/max/last 10).
- Top block reasons histogram across gate types.
- Intake by source (PUMP_PORTAL_WS / RAYDIUM / DATA_ORCHESTRATOR).
- LANE_EVAL by lane (SHITCOIN / MOONSHOT / QUALITY / BLUECHIP).
- Top intaked symbols (top 15 by hit count).
- Recent executions (last 30 BUY/SELL with mode/size/pnl/reason).
- ANR top blocking call sites (grouped, most frequent first).
- Stall % of uptime metric.

## V5.9.671 — V5.9.672 changelog (May 11, 2026)

### V5.9.671 — fix THE ANR root cause: cache EncryptedSharedPreferences (May 11)
ConfigStore.secrets() previously rebuilt the MasterKey + EncryptedSharedPreferences
on every call, which fired an Android Keystore Binder IPC + AES-GCM operation
init on the main thread per UI poll. Operator dump showed 431 hits on this frame
with stall % 49% of uptime. Fix: @Volatile cachedSecrets + double-checked locking
so the EncryptedSharedPreferences instance is built ONCE per process and reused.
Confirmed by operator: ConfigStore.secrets dropped to 0 hits; bot loop reached
V3 with score>0 for the first time post-regression; first live BUY since the
regression fired (`rmgSDM sol=0.042`).

### V5.9.672 — VOL_GATE live-mode bypass + silent-drop forensics + watchlist render throttle (May 11)
Three surgical fixes on top of V5.9.671 in response to operator's second pipeline
dump (post-cache-fix). LIVE buys regressed because:

1. **VOL_GATE live-mode bypass restored (BotService.kt:8011-8035)**.
   V5.9.606's unknownVolumeButTradable bypass gated on `cfg.paperMode &&`,
   which silently dropped every fresh PumpPortal/Dex hydration in LIVE mode
   (vol1h=0 but liq=$2.3k is the canonical state of a newly-listed meme).
   Operator confirmed live buys worked end-to-end 4 days ago — this was THE
   regression. Drop the paperMode-only guard; the bypass now applies in both
   paper and live, gated by the same liq/mcap thresholds. Downstream V3 +
   scoring still decide whether the token is buy-worthy.

2. **Silent-drop forensics (BotService.kt:7988-7997 + 8024-8032)**.
   The LOSS_STREAK guard and VOL_GATE both returned silently with debug-only
   logs and no ForensicLogger event — so a SAFETY-allowed token would
   disappear without leaving a trace in the pipeline funnel. Added
   ForensicLogger.gate(PHASE.V3, allow=false, reason="V3_SKIPPED loss_streak ...")
   and similar for vol_gate, so both drops now show up in the snapshot's
   gate block tally. Pure additive — no behaviour change.

3. **Watchlist render throttle (MainActivity.kt:356-367 + 2849-2865 + 6424-6432)**.
   Operator's post-cache-fix dump showed `MainActivity.buildTokenCard` as the
   new top ANR blocker (11 hits, stall % 62.2%). The pollLoop ticks every
   2.5s and renderWatchlist tears down + recreates up to 40 cards per tick;
   each card spawns ~10 fresh TextViews, each triggering native
   AssetManager.applyStyle / theme attribute parsing. Fix:
   - 6s throttle on the full renderWatchlist rebuild, bypassed only on
     *structural* change (active mint or open-position count).
   - Token count deliberately excluded from the structural check because
     scanner intake constantly nudges it (3-7/s in operator dumps) — using
     it would defeat the throttle entirely.
   - Row caps lowered: 3-col 24→16, 2-col 32→20, 1-col 40→24. Header still
     surfaces the true full count; visible slice still sorted by active /
     open / entryScore / lastV3Score / lastLiquidityUsd.

### V5.9.673 — CRITICAL: stop orphaning positions on Android system-kill (May 11)
Operator reported: RMG buy verified-landed at 05:29:23 (+353% gain, 436195
tokens) became INVISIBLE to the bot after the bot "randomly stopped and
started". Token sits in wallet untracked → no trailing stop, no take-profit,
no exit logic. ROOT CAUSE found in BotService.onDestroy():

When Android kills the foreground service (OOM / Doze / battery / ANR — all
of which were happening at 49-62% main-thread stall pre-V5.9.671/672),
onDestroy fires with ~5 seconds before SIGKILL. The V5.9.661 mandate
("every stop MUST close all positions") was triggering executor.closeAllPositions
on this path too. But a Jupiter swap needs 10-30s, far longer than the
5s window. Net result:
  1. closeAllPositions tries to liquidate.
  2. Mid-swap, process gets SIGKILLed.
  3. WORSE: position.isOpen was sometimes mutated to false locally BEFORE
     the actual swap landed, so the AlarmManager-scheduled 5s restart
     came back to a state with no open position — even though the tokens
     were still on-chain. ORPHAN.

The V5.9.661 mandate was correct intent for USER-INITIATED stops (which run
through stopBot() and close positions there, then set the manual-stop flag
that onDestroy now reads correctly). For SYSTEM-INITIATED destroys, the new
behaviour is:
  - DO NOT attempt closeAllPositions.
  - Force-save all open positions via PositionPersistence.saveAllPositions(force=true)
    so the 30s rate-limiter doesn't skip this critical pre-death flush.
  - Emit forensic event ONDESTROY_SYSTEM_KILL with openCount and persisted=true.
  - Schedule the 5s restart (unchanged).
  - On restart, the existing PositionPersistence.restorePositions + StartupReconciler
    chain re-adopts the position from on-chain truth.

Also added (StartupReconciler.kt:97-119):
  - Forensic wallet-sweep dump on every reconcile: count of token accounts,
    non-zero non-SOL count, already-tracked count, and a per-mint breakdown
    of every non-zero non-SOL token in the wallet showing alreadyTracked
    status. Emits ForensicLogger.lifecycle("WALLET_SWEEP", ...) so operator
    can verify on-chain RMG (or any orphaned position) is visible to the
    sweep and trace exactly where adoption is failing if it still doesn't
    register.

### V5.9.674 — kill 5-10min START dead-zone + proactive STUCK-LOOP HEARTBEAT (May 11)
The app remains open. I restart the bot and nothing happens for 5-10 minutes.
Or at all. If I leave it then sit for 10 minutes and restart it without closing
the app, when I press Start it finally instantly resumes trading."

Smoking gun: app is in foreground, screen on → this is NOT Doze. The previous
bot-loop coroutine is HUNG (suspended on a network call — Jupiter swap, RPC
fallback chain, DexScreener hydrate, scoring deadlock — that hasn't timed out
yet). `loopJob?.isActive` returns TRUE because suspended ≠ dead. The
onStartCommand ACTION_START branch at line 1139 evaluated `loopJob?.isActive
!= true` as false → fell through to the "Bot already running, just reschedule
keep-alive" branch → did nothing. User saw the START button do literally
nothing for 5-10 minutes until the hung suspend point finally timed out.
THEN the next press worked instantly because loopJob was finally inactive.

Two surgical fixes:

1. **STUCK-LOOP RESCUE (BotService.kt:1140-1175)**.
   New branch in onStartCommand: when `userRequested == true && !status.running
   && loopJob?.isActive == true`, the bot is in a stuck state — the loop is
   alive (suspended) but `status.running` is already false (bot is supposed
   to be stopped, or the loop is hung after a self-stop). Cancel the zombie
   loopJob with kotlinx.coroutines.CancellationException, wait at most 3s via
   withTimeoutOrNull for it to honour cancellation (never block longer — the
   same network call that hung the old loop would hang the join too), then
   force a fresh startBot(). Emits ForensicLogger.lifecycle("STUCK_LOOP_RESCUE",
   ...) so operator can see exactly when this rescue path fires.
   Pre-existing "bot already running" branch preserved for genuine
   double-tap-on-start situations.

2. **Doze-bypass dual-alarm restart (BotService.kt:1234-1278)**.
   The V5.9.673 onDestroy 5s restart used setExactAndAllowWhileIdle, which
   is rate-limited to ~9 MINUTES in Doze for non-priv apps (operator saw
   exactly that: bot died, restart deferred ~10 minutes). Mirror
   onTaskRemoved's two-layer pattern:
     • request code 2 — 1s setExactAndAllowWhileIdle (best-effort fast path
       when not in Doze)
     • request code 5 — 5s setAlarmClock (Doze-bypass guarantee; treated
       as a user-facing alarm so OS does NOT rate-limit it)
   If the fast-path fires first, the backup lands on an already-running
   service and is handled gracefully by the keep-alive branch.

### V5.9.675 — DOZE-PROOF heartbeat + battery-opt banner + SoundManager off main (Feb 2026) ⭐ root cause

Operator's 7h Pipeline Health dump (uptime 25,891s) revealed the REAL root
cause hidden by two screens of stale truncation:
  - BOT_LOOP_TICK = **4** across 7 hours of uptime
  - +2,717 scan callbacks in the 60s between screenshots once screen woke
  - Watchdog samples taken: 13,389 (should have been ~103k @ 250ms cadence)
  - SoundManager only accounted for 85 / 13,389 samples (0.6%)

The handoff's SoundManager theory was a symptom, not the cause. The actual
problem is **Doze suspending the entire bot process** — PARTIAL_WAKE_LOCK
is ignored by Doze for non-priv apps, foreground service alone is not
enough, and the V5.9.674b coroutine heartbeat hibernated along with the
loop it was supposed to watch.

Three surgical fixes:

1. **AlarmManager-driven loop heartbeat** (BotService.kt onStartCommand
   ACTION_LOOP_HEARTBEAT, scheduleLoopHeartbeatAlarm, cancelLoopHeartbeatAlarm).
   Replaced the V5.9.674b `scope.launch { delay(30s); ... }` heartbeat with
   the proven V5.9.674 onTaskRemoved dual-fire pattern:
     • request code 6 — 60s setExactAndAllowWhileIdle (fast path)
     • request code 7 — 65s setAlarmClock (Doze-bypass guarantee — OS treats
       it as a user alarm so it fires THROUGH Doze regardless of state)
   On every alarm fire, the handler checks lastBotLoopTickMs vs wall clock;
   if stale > 180s and loopJob.isActive, cancels the zombie with
   CancellationException + 3s join + relaunches botLoop(). Self-re-arms
   each fire while running == true; explicit cancelLoopHeartbeatAlarm()
   called from both stopBot teardown paths so a system-killed bot does not
   leave the alarm chain re-arming forever.

2. **Battery-optimisation whitelist prompt + persistent banner**
   (BotService.checkAndPromptBatteryOptimisation,
   MainActivity.refreshBatteryOptBanner).
   Even with foreground service + PARTIAL_WAKE_LOCK, Doze suspends the
   process unless the user has explicitly whitelisted the app under
   Settings → Apps → Battery → Unrestricted. On every startBot() we now:
     • Check PowerManager.isIgnoringBatteryOptimizations(packageName).
     • Persist the result to RUNTIME_PREFS["battery_opt_whitelisted"].
     • Emit ForensicLogger.lifecycle("BATTERY_OPT_CHECK", ...).
     • Fire ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS once per process
       if not whitelisted (the system exemption dialog, one-tap fix).
   MainActivity.onResume calls refreshBatteryOptBanner() which prepends
   an amber tap-to-fix TextView above topBarContainer when not whitelisted
   (and removes it once fixed). Banner click re-launches the system
   exemption dialog as a fallback.

3. **SoundManager off main thread + 1s throttle** (SoundManager.kt full
   rewrite, public API identical).
   - Replaced the main-thread `mainHandler` with a dedicated background
     `HandlerThread("AATE-Sound", THREAD_PRIORITY_BACKGROUND)`.
   - Cached single `ToneGenerator` instance (was rebuilt per call — each
     allocation is an AudioFlinger Binder IPC that was visible in the
     ANR top blocking-call-site dump 62× as makeTone).
   - Throttled `playNewToken()` to 1/sec via Volatile lastNewTokenSoundMs.
     Pump.fun fires several intakes per second; queueing thousands of
     synchronous beeps was the visible UI-freeze symptom when the screen
     woke and the bot ripped through the backlog. Other sounds remain
     un-throttled but still run off-main.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN.

### V5.9.676 — bulletproof rescue + CE rethrow + cycle breadcrumbs (Feb 2026) ⭐ root-cause #2

Operator V5.9.675 dump revealed why the heartbeat rescue silently failed
across 9 rescue attempts — `sinceLastTickSec` grew monotonically 208 →
688 while `LIFECYCLE/BOTLOOP_STARTED` counter stayed at 1.

ROOT CAUSE: `catch (e: Exception)` in botLoop swallowed
`CancellationException`. The previous while-loop catch was
`catch (e: Exception)` — and CancellationException IS an Exception. So
when the heartbeat rescue called `lj.cancel(CE)`, the CE propagated up
through the suspended `delay()`, got caught by the outer Exception handler,
logged as "Loop error", then entered another `delay(5000)` — which threw
CE again — which was also swallowed. Infinite resurrection: the old loop
caught its own death and the rescue's `lj.join()` timed out every time.

Four surgical fixes:

1. `CancellationException` now explicitly caught + RETHROWN (BotService.kt
   ~line 7124). `BOTLOOP_CANCELLED` forensic emitted on each clean cancel.
   This is the actual root cause — guarantees `lj.cancel()` terminates
   the coroutine instead of being absorbed as a transient error.

2. Heartbeat rescue is now bulletproof (BotService.kt ACTION_LOOP_HEARTBEAT):
   - Step-by-step forensic events: `RESCUE_CANCEL_SENT`,
     `RESCUE_JOIN_OK`/`RESCUE_JOIN_TIMEOUT`, `RESCUE_RELAUNCHED`,
     `RESCUE_FAILED:<exception>`.
   - `loopJob = null` happens UP FRONT so the next heartbeat can't keep
     trying to kill the same dead reference.
   - `lastBotLoopTickMs` reset BEFORE the rescue work AND post-join to
     prevent cascading re-rescues if relaunch hangs.
   - Rescue body runs on `Dispatchers.IO` so a saturated Default
     dispatcher (where the dead loop is wedged) cannot starve it.
   - Removed the `active` gate from rescue trigger — even if `isActive`
     reports false on the old job, the new heartbeat still relaunches.

3. `CYCLE_PHASE` breadcrumbs at ENTER, PRE_SUPERVISOR, CYCLE_EXIT
   (BotService.kt botLoop). Couldn't safely wrap the cycle body in
   `withTimeoutOrNull` (JVM 64KB method cap on botLoop). Breadcrumbs
   let the next stall dump pinpoint exactly which phase wedged.

4. `mode_maxhold` sell reason now carries `held=Xm max=Ym utc=Zh` so we
   can see WHY the bot dumped 10 positions on V5.9.675 session start
   with reason `mode_maxhold_paused` (Executor.kt:3395 + 3850, both
   gates). Local 15:24 NZST = UTC ~03 — NOT in the default UTC 04-06
   pause window — so something else triggered PAUSED mode. The new
   forensic surfaces enough state to diagnose it next session.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN.

### V5.9.677 — version stamp + pinned lifecycle counters + LANE_EVAL forensics (Feb 2026)

Operator's V5.9.676 dump exposed two diagnostics gaps that wasted a
debugging round and one real silent drop killing every meme buy:

1. **No build version in dump header.** Agent spent the round arguing
   about whether V5.9.675/676 was on device because we had to infer it
   from lifecycle counter presence. Fix: first line of every Pipeline
   Health dump now prints `Build: <BuildConfig.VERSION_NAME>  |  Tag:
   V5.9.677`. The BUILD_TAG const is bumped each release. End of debate.

2. **LIFECYCLE/* counters fell off the bottom-40 cap.** A 15-min session
   with 1,000+ SCAN_CB events buried singleton lifecycle counters
   (BATTERY_OPT_CHECK=1, CYCLE_PHASE=63) below the labelled-counters
   take(40) cut. Fix: pin all `LIFECYCLE/*` and `SNAP/*` entries FIRST
   (sorted by count desc among themselves), then fill remaining slots
   with the highest-count non-pinned counters. Lifecycle visibility now
   guaranteed regardless of event volume.

3. **LANE_EVAL → FDG silent drop surfaced.** V5.9.676 dump showed 14
   LANE_EVAL passes / 0 FDG evaluations — the bot was rejecting every
   meme buy at the FinalExecutionPermit stage, but the rejection only
   hit `ErrorLogger.debug` (off in release). Added
   `ForensicLogger.gate(PHASE.LANE_EVAL, allow=false, reason=...)` at
   the three silent-return points:
     • SHITCOIN lane permit deny → `PERMIT_DENY:<reason> lane=SHITCOIN`
     • MOONSHOT lane permit deny → `PERMIT_DENY:<reason> lane=MOONSHOT`
     • SHITCOIN authorizer race  → `AUTHZ_RACE another-lane lane=SHITCOIN`
   Next dump's funnel allow/block tally will show exactly why each
   LANE_EVAL pass failed to reach the FinalDecisionGate.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN.

### V5.9.679 — Paper-position stop-clear hardening (May 11)
Operator screenshot: 6 paper positions persisted on the UI after pressing Stop.
Root cause: the previous single try-catch wrapped SEVEN lane-trader clears +
LiveWalletReconciler pass + TWO tracker clears in ONE block. If ANY lane trader
threw a defensive exception (CashGen/BlueChip/ShitCoin/etc.), the SHARED catch
at the bottom silently skipped the trackers — and the UI's open-counter does
maxOf(tokens, hostTracker, lifecycleTracker), so the tracker leak alone kept
the badge non-zero.

Fix:
- Each `clearAllPositions()` on the 7 lane traders gets its OWN try/catch
  with a `stop-clear <Layer>` ErrorLogger.warn so any single failure is
  isolated and observable.
- TokenLifecycleTracker.clearAll() + HostWalletTokenTracker.clearAll() moved
  to a DEDICATED final guard block outside the lane-trader try, so they
  ALWAYS run regardless of upstream failures.
- New forensic events: STOP_TRACKERS_CLEARED, STOP_TOKENS_CLEARED,
  STOP_PERSIST_CLEARED, STOP_COMPLETE — next dump shows exactly which
  step of the chain ran end-to-end.

### V5.9.680 — 30-frame rolling pre-freeze main-thread sample buffer (May 11)
P1 ANR diagnostic improvement. Previously the watchdog sampled the
mainThread stack only at the moment a freeze was detected — useful but
gave zero history of what main was doing in the seconds leading up to
the hang.

Implementation (PipelineHealthCollector.kt):
- New `StackSample(tsMs, sinceLastAckMs, topFrame)` data class +
  thread-safe ring buffer capped at 30 entries.
- Every 250ms watchdog tick now captures a sample whether main is
  responsive or not. 30 samples × 250ms = ~7.5s of pre-freeze history.
- Freeze branch reuses the just-captured tick sample (no double walk
  of the stack) and embeds the full 30-sample ring in the ANR_HINT
  event payload.
- New "Pre-freeze rolling main-thread sample" section in dumpText()
  for clipboard export at any time.

### V5.9.680b — CI compile fix (May 11)
TokenLifecycleTracker exposes `openCount()` (no `get` prefix); the V5.9.679
STOP_COMPLETE block called the non-existent `getOpenCount()`. One-line
surgical rename. CI now green.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on bfe3ea95d.

## Critical Operator Mandates
- NO LOCAL COMPILER. All changes via Git → GitHub Actions CI.
- Brace counting before push (grep -c '{' vs '}') is mandatory.
- BotService.kt is at the JVM 64KB cap on botLoop — extract before adding inline blocks.
- Position close on stop must be UNCONDITIONAL (V5.9.661).
- **V5.9.665**: BEFORE clearAll() of trackers on stop, call
  LiveWalletReconciler.reconcileNow() to capture late-landing swaps.
- Smoke test must actually start the bot (V5.9.661b) — UI-only launches don't count.
- All lanes (not just ShitCoin) must collect paper-learning samples (V5.9.662 family).
- Penalty strictness scales with trade maturity (path to 5000 trades) (V5.9.662d).
- Open positions get refresh priority over watchlist scanning (V5.9.663c).
- **V5.9.665**: Never run synchronous network I/O on startBot()'s body —
  always wrap in scope.launch with a startup grace.
- ALWAYS pull and read smoke test artifacts (`logcat_full.txt` + `funnel_summary.txt`)
  before commenting on pipeline state. Do not assume — read.

## 3rd Party Integrations
- GitHub Actions (CI + Android emulator runtime smoke test)
- PumpPortal WS, Birdeye, Pyth, DexScreener, Jupiter API V6, Binance, CoinGecko,
  Yahoo Finance V8 (stocks)

## Tech Stack
- Native Kotlin Android Application
- SQLite (TradeHistoryStore, LearningPersistence)
- SharedPreferences (RUNTIME_PREFS, bot_config, aate_security)
- AGP / Gradle 8.7.0
