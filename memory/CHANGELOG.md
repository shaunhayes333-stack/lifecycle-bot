# CHANGELOG — AATE Bot

Progressive change log. Newer entries on top. PRD.md holds the static problem
statement + architecture; this file is the working log of fixes & decisions.

## 2026-07-03 — V5.0.6063 → 6064 🔴 CRITICAL: SETTLE-EXIT + PROTECTIVE PARTIAL + PHANTOM HEAL

Operator screenshots — two catastrophic profit-locker failures across two
runtime cycles:

1. ERMINE peaked +1183% (lock band advertised +1172%) then bled to -53.9%
   INSIDE the 45s settle window. rocketnigg peaked +1077% then -87.5%
   same window. Root cause: PeakDrawdownLock's give-back lock and MFE
   floor were downstream of the settle-return in manage_only, so ONLY
   checkProfitLock + trySweepTakeProfitExit ever bypassed settle. Fixed
   in V5.0.6063 by running PeakDrawdownLock.shouldFloorLock and
   shouldLock BEFORE checkProfitLock inside settle — any position that
   ever peaked >= +35% now cannot bleed back through its MFE floor.
   New forensic labels: SETTLE_MFE_FLOOR_FIRED_6063,
   SETTLE_PEAK_DRAWDOWN_FIRED_6063 (confirmed firing in V5.0.6063
   runtime snapshot).

2. RUNNER peaked +3358% ($59 unrealized) with UI tag 'route pending';
   Jupiter/pump-direct sell stalled under provider degradation
   (helius_rpc sr=14%, birdeye sr=49%) while meme rug-snapped. Fill
   landed at dust → -87.3%. V5.0.6064 addresses this two ways:
   - **PROTECTIVE PEAK PARTIAL**: the MOMENT peakGainPct first crosses
     +500%, fire an immediate 25% partial via executeProfitLockSell.
     Only runs on positions that haven't already partialled — normal
     laddered rides untouched. Locks a slice of SOL before any Jupiter
     stall. Forensic: PROTECTIVE_PEAK_PARTIAL_FIRED_6064.
   - **PHANTOM QTY HEAL**: when PHANTOM_MULTIPLE_GUARD fires
     (rawGainMultiple > 5x price move), qtyToken had a decimals-
     corrupted value from rehydrate. Now we heal it in place:
     qtyToken := (costSol * priceMoveMultiple) / actualPrice. Future
     ticks compute the correct multiple natively — no more repeated
     clamp on every tick. Forensic: PHANTOM_QTY_HEALED_6064.

Also this session: V5.0.6060 REVERT of the disastrous V5.0.6058 9pm
Sydney daily fee flush (drained bucket in one shot per operator
report). Threshold flipped 1.0 SOL → 0.0001 SOL so tryFlush drains
every scan cycle — effective live per-trade fee transfer, bucket kept
only as a per-cycle safety net.



## 2026-07-03 — V5.0.6054 → 6058 🟢 AGI/SSI ANTI-SUFFOCATION + DAILY FEE FLUSH

Operator directives (post V5.0.6053 runtime report):
1. Route-lock is firing but over-blocking legitimate ticks when
   entryPriceSource is a symbolic label like LIVE_PROOF_COST_BASIS.
2. "flick the memetrader green the right way!!! quality fix up and
   downstream. no new chokes no new butterflies!!!"
3. "set it to do a daily flush at 9pm Australian Eastern standard time"

**V5.0.6054 — ROUTE-LOCK REFINEMENT**: SYNTHETIC-SOURCE BYPASS +
SELF-HEAL. Positions whose entryPriceSource is a recovery label
(LIVE_PROOF_COST_BASIS, RESTORED_LIVE_BASIS_UNKNOWN,
WALLET_REHYDRATE_BASIS_UNKNOWN, SYNTH_COST_DIV_QTY, UNKNOWN) skip
route-lock entirely. Real whitelisted sources (DEXSCREENER_WS/PAIR_POLL,
BIRDEYE*, PUMP_FUN*, ORACLE_*, etc) still enforce. First real on-route
tick self-heals entryPriceSource in place. New log: 🩹 ROUTE_LOCK_SELF_HEAL.

**V5.0.6055 — LaneExpectancyDamper P0.a**: HEALTHY-MEAN GUARD +
MODERATE-CATASTROPHIC tier. MOONSHOT was being damped ×0.18 despite
meanPnl=+14.93% because the pf-edge proxy came out slightly negative.
Guard: skip pf-bleeder detection when meanPnlPct >= +5%. New tier
between BLEEDER (0.18) and CATASTROPHIC (0.08): MODERATE_CATASTROPHIC
(0.15 floor) fires at n>=25, mean<=-8%, WR<=30%, totalSol<0 — targets
QUALITY-shape tumors.

**V5.0.6056 — Executor P0.b**: POSITIVE-EV LANE FLOOR. Final coerceIn
lifts from 0.25 → 0.50 for lanes with meanPnl >= +5% & n >= 8, OR
LaneExpectancyDamper mult >= 1.0. Losing lanes keep 0.25 floor.
Prevents proven winners from stacking to the hard floor when 19
upstream multipliers compound.

**V5.0.6057 — StrategyHypothesisEngine P1**: COLD-START SEED. On
attachContext, warm control arms of active hypotheses from up to 200
recent closed trades (bucketed lane|scoreBand|NORMAL). Fires forensic
HYPOTHESIS_ENGINE_COLD_START_SEEDED_6057 on non-zero seed. Variants
still fill only from real live A/B assignments. Unblocks the A/B
engine (had ctrl=0 var=0 across 5 active hypotheses).

**V5.0.6058 — FeeAccumulator DAILY FLUSH**: 9pm Australia/Sydney
(DST-aware). Bypasses the 1.0 SOL threshold once per local day —
0.351 SOL was stuck accruing at 35% of threshold. yyyy*1000+DAY_OF_YEAR
stamp guards against double-fire. New log: ⏰ SCHEDULED_DAILY_FLUSH.



## 2026-07-03 — V5.0.6052 / 6053 🟢 ROUTE-LOCK DOCTRINE + API CASCADE FAIL-FAST

Operator mandates (session end V5.0.6051):
1. *"the tokens are meant to come in a leave via the same route!!!
   the whole route unknown is bullshit!!!"* — ANSEM incident: entered via
   DEXSCREENER_PAIR_POLL, ticked via a crashed alt source at -82%, forced
   a real on-chain stop-loss.
2. *"133s cycle stalls"* — Birdeye 401 → Jupiter Quote 4xx → Helius 429
   cascade eating minutes per cycle.

**V5.0.6052 — ROUTE-LOCK DOCTRINE** (Models.kt +15, Executor.kt +51):
LIVE positions read exit-critical prices via `Executor.getActualPrice()`,
which now enforces route-lock: an off-route tick (ts.lastPriceSource !=
pos.entryPriceSource) never returns the alt-source price. Instead we
return the last on-route tick if <60s old, else fall back to
pos.entryPrice (neutral 0% PnL). New transient fields on Position:
`lastRoutePrice`, `lastRoutePriceTs`, `routeLockRejects`. New logs
🔒 ROUTE_LOCK_REJECT / ROUTE_LOCK_STALE (rate-limited 1-in-20). Paper
positions unchanged — rebase logic still applies.

**V5.0.6053 — API CASCADE FAIL-FAST** (JupiterApi.kt +18/-2): the
jupiter_quote GET retry loop went from 3 attempts × (20s read + 1.5s×n
sleep) worst-case ~65s down to 2 attempts × (20s + 0.3s×n) worst-case
~40s, PLUS a pre-loop `ApiBackoff.isLockedOut("jupiter_quote")` short-
circuit that returns immediately when the host is already known-down.
Callers naturally fall through to DexScreener + jupiter_send routes
(unaffected).



## 2026-07-03 — V5.0.6042 → 6049 🟢 CI GREEN — FLIP-LANES-GREEN + THROUGHPUT DOCTRINE

Operator ask: "flip all lanes green in-role", "FDG is meant to be a final safety
sanity gate — trade flows must align for throughput unless explicitly blocked
(rugs)", "the bot should be well ahead of its starting balance — this has to be
captured, no exceptions", "cash gen needs to be allowed to trade FFS", "stop
patching, fix things", "expand the runtime report".

**V5.0.6042** — Fixed base44 compile error: `svc?.walletManager` → `BotService.walletManager` (companion object access).

**V5.0.6043 — UNCHOKE:** `PROVIDER_PROOF_HOLDER_CASCADE_BLIND` dominated the funnel
(21 hits) when Doctor state=DEGRADED subsystem=api/providers. New bypass
`apiDegradedHolderBypass6042`: when providers are down AND liq≥$5K, holder-proof
cascade fails open. Downstream gates (LP-lock, RUGCHECK_FLOOR, EXEC_GATE
reentry-lockout, LiveSafetyCircuitBreaker) still enforce trade safety.
**Result on next report**: PROVIDER_PROOF_HOLDER_CASCADE_BLIND dropped 21 → 7.

**V5.0.6044 — FLIP-LANES-GREEN + PRE-FDG THROUGHPUT DOCTRINE** (4 bundled):
- Pre-FDG throughput doctrine header on `consultEntryAdvisors`: this function
  MUST return (true, ...) except for RUG_PREFILTER_HARD_FAIL. All other signal
  concerns MUST fire via `softAdvisor()` only. Downstream gates handle real
  safety. Codifies existing behavior so future edits don't regress.
- BLUECHIP -8% hard SL clamp at position open (was riding to -80%+ catastrophes).
- QUALITY WR-based auto-raise: entry floor +10 while clean-truth WR<40% AND
  sample≥5. Releases automatically when WR recovers to ≥40%.
- MOONSHOT volume relief -5 (unless AGI AUTHORITATIVE) to drive proven-EV lane.
- LaneExitTuner MIN_SAMPLE 20→8, RECALC 10→5 — unlocks closed-loop tuning for
  BLUECHIP (n=9), MOONSHOT (n=17), SHITCOIN (n=7) which were stuck at neutral.
- **Result on next report**: FDG 22→68 (3× throughput), BUY ok 14→28 (2× volume),
  MOONSHOT PnL +0.0054 SOL (positive!), BLUECHIP auto-sized to 40% by tuner.

**V5.0.6045 — STALE-RUNNER FORCE-HARVEST:** Operator screenshot showed BOB at
+1797% locked +1786% sitting UNREALIZED indefinitely. Root: existing WALLET_GROWTH_HARVEST
requires either RealPriceLock.verifyUltraRunnerBank OR route-real fallback;
when Jupiter providers degraded, BOTH fail and runners are trapped.
New fallback: when both proof paths fail AND position age≥120s AND peak
sustained (currentPeak ≥85% of historical, historical≥300%) AND profit≥5%
of wallet, force a 25% probe-sell. If route fills, real profit lands and
next cycle unlocks remaining tranche.

**V5.0.6046 — MANIP overlay always dust-probe:** Removed the "MANIP-lane-quarantined"
gate on the dust-probe fallback (V5.0.6011). MANIP overlay is a safety signal,
not a rug — should always soft-shape via dust-probe, never hard-reject.
Eliminates the FDG=11 hard-rejects observed at 05:58.

**V5.0.6047 — ROOT CAUSE FIX: CASHGEN/TREASURY had wrong (BLUECHIP-tier) proof gate.**
Report showed CASHGEN 65 lane_evals with ZERO trades. Root: gated by
`qualityLaneProofOk()` which required liq≥$15K + mcap≥$25K (BLUECHIP-tier).
CashGen doctrine is scalping SMALL tokens for 3-5% cashflow — 100+ trades/day
wallet compounder. New `cashGenProofOk()`: route+safety+liq≥$2K (scalp-executable).
Wired into 3 gates: primary-lane block, owner-pool filter, profitable-rescue.

**V5.0.6048 — EXPANDED REPORT + BLUECHIP AUTO-HALT** (2 bundled):
- `ReportingHub.MAX_UNIFIED_REPORT_CHARS`: 24_000 → 100_000 (~4×). Per-section
  budgets ×5. Error log limit 24 → 80 rows. Envelope tag PASTE_SAFE_V4487 →
  PASTE_SAFE_V6048. Full operator context now captured; no more base44 trimming
  cost.
- LaneQuarantineController `DYN_MAX_MEAN_PNL_PCT`: -40.0 → -8.0. BLUECHIP was
  n=12 WR=14.3% EV=-16.91% — hit WR floor but escaped quarantine because EV
  was 'only' -16.91%. New threshold auto-halts bleeders. Existing 30% WR
  hysteresis for auto-release preserved.

**V5.0.6049 — golden-tape test alignment** for V5.0.6048 envelope changes (3
assertions updated: MAX_UNIFIED_REPORT_CHARS, PASTE_SAFE_V6048, limit=80).



## 2026-07-02 — V5.0.6011 🟢 CI GREEN — P1 TRIAGE BUNDLE

**Three P1 fixes bundled after RCA (issues 2 & 3 from handoff + TokenWinMemory phantom purge).**

1. **TokenSafetyChecker: MANIPULATED_ONLY overlay tightened** — prior condition
   fired on ANY single risk flag (singleHolder OR unverified OR holderConc),
   which caught nearly every fresh pump.fun launch because 'unverified' is
   universal on new launches. With MANIPULATED lane V5.0.4588-quarantined,
   ~27 legit candidates died per cycle. Now requires >=2 signals OR
   (any 1 + redFlagCount>=3).

2. **BotService: MANIP overlay dust-probe fallback** — new label
   `MANIP_OVERLAY_DUST_PROBE_FALLBACK_6011` + `MANIP_OVERLAY_DUST_PROBE_SCORE_PENALTY_6011`.
   When MANIP lane is auto-paused AND overlay fires on a non-MANIP lane, do NOT
   hard reject. Fall through with heavy `entryScore -= 40` penalty so downstream
   sizing shrinks trade to a dust probe. Enables learning-data collection and
   routes legit unverified memes through STANDARD/MOONSHOT. Full-reject path
   preserved when MANIP lane is NOT quarantined (token routes to MANIP where
   it belongs).

3. **TokenWinMemory: PHANTOM_PNL_PCT_HARD_CEILING_6011 = 50_000.0** — prior
   ceiling used LearningPnlSanitizer.MAX_TRAINABLE_PNL_PCT (100_000%). No
   legit exit on this scanner has cleared 50k% — those were basis-switch /
   bonding-curve phantom prints poisoning pattern recognition. Load-time
   `saneWinner()` now purges >50k% winners on next boot.

## 2026-07-02 — V5.0.6010 🟢 CI GREEN — GOLDEN TAPE TEST ALIGNED

V5.0.6009 CI failed on `executor_4514CentralTerminalPolicyFanoutUsesClosedLearningGate`
because the golden-tape test still asserted the OLD (buggy) label
`pnlForHeads4514 > -5.0`. Updated regression to lock in the V5.0.6009 correct label:
  - STOP_LOSS/STRICT_SL/STOPLOSS  -> exitWasOptimal = false (never optimal)
  - TAKE_PROFIT/TRAILING_STOP     -> true  (managed profitable exits)
  - pnlForHeads4514 >= 2.0        -> true  (real banked win floor)
  - everything else               -> false (scratches + all losses)

Also added forward-guard: test now asserts `UNIFIED_EXIT_POLICY_HEAD_LABEL_FIX_6009`
marker so any regression back to the -5.0 threshold immediately fails CI.

## P2 BACKLOG AUDIT — ALL ITEMS ALREADY IMPLEMENTED

Handoff summary listed several P2 items, but audit against MainActivity/layout XML
+ engine confirms they're all live:

| P2 Item | Status | Where |
|---|---|---|
| Brain Health pill | ✅ Wired | `MainActivity.kt:2899` (tvBrainHealthPill), V5.9.14 |
| Ladder / TIER status pill | ✅ Wired | `MainActivity.kt:2940` (tvLadderPill), V5.9.462+ |
| Guards status strip | ✅ Wired | `MainActivity.kt:2999` (tvGuardsStrip), V5.9.471 |
| Strategy Leaderboard tile | ✅ Wired | `MainActivity.kt:3079` (tvStrategyLeaderboard) |
| LLM Lab strategy → auto-resume quarantined lanes | ✅ Wired | `LaneQuarantineController.maybeScanLabForAutoResume()`, V5.0.6002 |

No further P2 UI work required until user identifies new gaps.




═══════════════════════════════════════════════════════════════════════════════

## V5.0.4116 — fix unresolved PARTIAL_TP → TAKE_PROFIT_CHUNK (Feb 2026)
V5.0.4115 build failed: `ExitReason.PARTIAL_TP` does not exist. The
canonical partial-take-profit enum is `TAKE_PROFIT_CHUNK`. Swapped the
4 MONSTER_LOCK tiers (T1–T4) to use the correct enum. Full-exit tier
keeps `TAKE_PROFIT_FULL`. CI: Build ✅.

═══════════════════════════════════════════════════════════════════════════════

## V5.0.4115 — fix-forward V5.0.4114 + take-wins cascade + UI price (Feb 2026)

**V5.0.4114 went red** because the `liveBuy` parameter rename to `solIn`
broke named-argument callers (`sol = ` at 5 sites). Reverted to `sol`
and applied the last-mile floor INSIDE the existing `var sol` chain
(after all multipliers, before broadcast).

### GUARANTEED PROFIT-LOCK CASCADE (operator: "take wins have to fire")

Journal evidence: a position hit peak +30,075,290% and exited at -13.7%
— the trail let the moonshot round-trip to a loss. CHUNGUS in operator
screenshot stayed at +24,570% without firing TP.

`AdvancedExitManager.evaluateExit` now layers a hard partial-exit ladder
ABOVE TP and BELOW the trail, once-per-tier (alreadySoldPct gates):
- +500%   → sell 25% (lock initial capital × 1.25)
- +1,500% → sell to 50% total (lock 7.5× initial)
- +5,000% → sell to 75% total (lock 37.5× initial)
- +15,000% → sell to 90% total (lock 135× initial)
- +30,000% → FULL EXIT (capture before round-trip)

### OPEN-POSITION UI PRICE FALLBACK

`MainActivity.mainUiCurrentPrice` extended fallback chain: when
`status.tokens[mint]` is empty for a held position, cascade into
`HostWalletTokenTracker.getEntry(mint)?.currentPriceUsd` before giving
up. Combined with V5.0.4113 held-token immunity, the tile shows real
price as long as the wallet tracker has seen it.

### LiveSizingProfile bumps (carried from V5.0.4114)
- `MIN_ENTRY_SOL`: 0.025 → 0.040
- `BASE/STRONG/ALPHA wallet%`: 2/4/7.5 → 3/6/10
- `GAS_RESERVE_SOL`: 0.075 → 0.030 (lets small wallets enforce the floor)
- New `lastMileEntryFloor()` applied at the SINGLE choke point inside
  `liveBuy()`, AFTER every size-multiplier (wrRecovery × style ×
  providerQuorum × laneCapital), guaranteeing live entries are at least
  `max(MIN_ENTRY_SOL, walletSol × BASE_WALLET_PCT)` whenever the wallet
  is > 0.030 SOL.

Operator mandate: "if it catching huge wins it needs to make big wins."
Next +24,570% runner now converts to ~10 SOL realized (≈\$1,400) rather
than \$0.33.



═══════════════════════════════════════════════════════════════════════════════

## V5.0.4113 — LayerBrain compile fix + HELD-TOKEN WATCHLIST IMMUNITY (Feb 2026)

V5.0.4111 build failed with `'internal' function exposes its 'private-in-class'
parameter type Brain`. The fix bumps the nested `Brain` class from `private` to
`internal` so the `Handle` constructor signature stops leaking a stricter type.

### HELD-TOKEN IMMUNITY (operator mandate)
> "the watchlist isnt meant to drop held tokens ever. maybe consider a separate
> held tokens lane that only flushes from the watchlist if its sold not pruned
> via the time ticker"

Root cause of the WALLET_RECOVERED phantom-PnL cluster (fixed in V5.0.4112)
traced upstream: when SAFETY / RUG / HONEYPOT / SCAM verdicts fired,
`GlobalTradeRegistry.registerRejection` silently nuked the mint from the
watchlist (line 1008) with NO active-position guard. Once evicted, price
polling died, the WalletReconciler later "recovered" it as orphan with
`costSol=0`, and the +99% phantom-win loop began.

New canonical helper `GlobalTradeRegistry.isMintHeldAnywhere(mint)` consults
THREE orthogonal sources — any one = held:
- `activePositions` (registry fast path)
- `BotService.status.tokens[mint].position.isOpen` (in-memory truth)
- `HostWalletTokenTracker.hasOpenPosition(mint)` (wallet truth)

Wired into every eviction path:
- `removeFromWatchlist`  (escalated from activePositions-only)
- `removeFromWatchlistForced`  (was UNGUARDED — bug)
- `registerRejection` initial guard
- the SAFETY / CONFIRMED RUG / HONEYPOT branch (now skips watchlist removal
  if held, while still recording the rejection memory for execution gating)

### TUNING-DATA POISONING CONTAINMENT
> "with the journal data being compromised reconsider tuning data. because
> its currently skewed towards things as winners but are literally just
> recovered dropped tokens"

Even with V5.0.4112 forcing `pnl=0` on recovered closes, downstream tuners
(LiveStrategyTuner, LaneExitTuner, PatternAutoTuner, MetaCognition,
SessionEdgeAI, KillSwitch, OnDeviceMLEngine, behavior learners) would still
ingest the row and inflate sample / EV denominators — explaining the
`STANDARD:compounding_runner WR=100% n=25 size×=1.41` line in the
leaderboard which was UP-sizing real negative-EV trades.

`Executor.recordTrade` now computes `isRecoveredScratch = (tradingMode |
entryPhase | reason ∈ WALLET_RECOVERED family) && (costSol ≤ 0 &&
entryCostSol ≤ 0)`. For these rows `accountingTrainable` is forced `false`,
gating every downstream learning fan-out site (Executor.kt lines 2746, 2801,
2842, 2867). Recovered closes still journal for audit but are INVISIBLE to
learning. Forensic emit: `LEARNING_EXCLUDED_RECOVERED_SCRATCH`.

CI: Build ✅ success.

═══════════════════════════════════════════════════════════════════════════════

## V5.0.4112 — PHANTOM PnL FIX + compound-aware treasury split (Feb 2026)

Operator: *"are the returns being calculated correctly or is it calculating
the investment return... you got 96% of what you invested or is it
displaying the actual profit?"*

### Phantom PnL — confirmed bug + fix
- `WalletReconciler.recoverOrphanPosition` sets `costSol=0.0` (unknown cost).
- `Executor.liveSellAccountingAuthority` then computed
  `pnlSol = proceeds - 0 = proceeds` — the entire sell amount became profit.
- `Executor.recordTrade` journal normalizer fallback chain used
  `tradeWithMint.sol` (proceeds) as basis, giving `netPct = proceeds/proceeds
  ≈ 100%` for every recovered close. This is the +99.6/8/9% cluster.

Fix:
- `liveSellAccountingAuthority`: cost ≤ 0 + recovered → force `pnlSol=0,
  pnlPct=0`, scratch.
- Journal normalizer: refuse to use proceeds as basis on recovered rows;
  emit `RECOVERED_PHANTOM_PNL_NORMALIZED`.

### Compound-aware treasury split
Operator: *"treasury split is dragging the sustainability down, especially
with such tiny returns"*. Scale `MEME_SELL_TREASURY_PCT` by wallet USD:
- `<$50`  → 5% (microcap: keep 95% to compound)
- `<$150` → 10%
- `<$500` → 15%
- `≥$500` → 25% (standard regime)

CI: Build failed (inherited V5.0.4111 LayerBrain compile error) — fixed
forward in V5.0.4113.

═══════════════════════════════════════════════════════════════════════════════

## V5.0.4111 — LayerBrain framework + 13 heuristic AIs promoted (Feb 2026)

Generic, allocation-cheap, NEVER-LOCKING per-layer online-learning
framework (`engine/LayerBrain.kt`). 4-tier authority (BOOTSTRAP →
ADVISORY@40 → LEARNED@100 → AUTHORITATIVE@250) with Brier-calibrated
demote and soft-shape multiplicative bias.

Promoted 13 of ~19 heuristic AIs: MEVDetectionAI · OrderbookImbalancePulseAI ·
FundingRateAwarenessAI · NewsShockAI · StablecoinFlowAI · CorrelationHedgeAI ·
LiquidityExitPathAI · SocialVelocityAI · FearGreedAI · DrawdownCircuitAI ·
OrderFlowImbalanceAI · LiquidityCycleAI · VolatilityRegimeAI.

CI: Build FAILED — Kotlin `'internal' function exposes its 'private-in-class'
parameter type Brain`. Fixed forward in V5.0.4113.



═══════════════════════════════════════════════════════════════════════════════

## V5.0.4110 — WR booster: high-precision Confirmed Loss Cut (Feb 2026)

Bot at ~42% WR with PF=1.00 (barely positive). Journals show recurring
pattern: positions grind through the wick-survival window, then sit at
-3% to -8% with collapsing momentum + falling liquidity until base SL
(~10%) finally fires, eating R:R and bleeding net PnL.

Added a SINGLE high-precision early-exit gate inside
`AdvancedExitManager.evaluateExit()` — fires ONLY when ALL FOUR
death-confirmations align:
  (a) `holdMinutes >= 1` (past wick-survival window)
  (b) `pnl <= -3%` (real loss, not noise)
  (c) `momentum < -8` (active selling pressure)
  (d) `liq drop >= 15%` (real distribution, not chart wobble)

Three independent signals must agree before cutting. Will not fire on
isolated wicks, will not gate entries, will not interfere with the meme
trader's volume mandate ("never choke itself out"). Targets the
bleed-by-thousand-cuts regime dragging WR.

CI: Build ✅ success.

═══════════════════════════════════════════════════════════════════════════════

## V5.0.4109 — DEADLOCK FIX P0 + LockDiagnosticsTracker (Feb 2026)

Operator reported 1000+ ANRs in 6 hours with the app "completely froze."
Stack frames showed classic `jdk.internal.misc.Unsafe.park` — kotlinx
coroutine Mutex + runBlocking deadlock pattern.

**Root cause:** `HoldingLogicLayer.evaluatePosition()` was a `suspend fun`
wrapped in `kotlinx.coroutines.sync.Mutex().withLock { ... }`, BUT its
body had zero real suspending calls (no I/O, no delay — pure compute on
inputs). `Executor.kt` called it as `runBlocking { evaluatePosition(...) }`
per-token with NO timeout. Every concurrent token evaluation serialized
through one coroutine mutex while parking its host worker thread on
`Unsafe.park`. With 20+ tokens evaluated concurrently the IO dispatcher
pool drained and the supervisor / exit coordinator threads froze.

**Fix:**
  * `HoldingLogicLayer.evaluatePosition`: dropped the coroutine Mutex,
    converted to regular synchronous `fun` (no behavior change — body is
    pure compute).
  * `Executor.kt`: call `evaluatePosition()` directly (removed `runBlocking`).
  * `LockDiagnosticsTracker`: new lightweight telemetry that wraps critical
    sections and emits `LOCK_LONG_HOLD` / `LOCK_ALERT_HOLD` forensics with
    owner thread + hold ms so the next operator dump pinpoints any surviving
    contention site (warn >2s, alert >10s, rate-limited).
  * Defensive: added `withTimeoutOrNull(1500-2000ms)` to remaining unbounded
    `runBlocking { ... }` price fetches in `FluidLearning` + `BotService` so
    a hung RPC can never park a caller thread indefinitely.
  * Bump GoldenTapeRegressionTest version assertion to 5.0.4109/5.0.4110.

CI: Build ✅ success. Runtime Smoke Test ✅ success.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1067 — Fix MainActivity recreation cascade + double-collector ANR + journal diagnostic (Feb 2026)

Triage-agent RCA after operator V5.9.1065 panic snapshot: 28 ANR samples on
`MainActivity.onCreate` in a single 30-sample window, max frame gap 12.9 s,
stall 6.3 %, max bot cycle 27.5 s. ROOT CAUSE: opening PipelineHealthActivity
and returning recreated MainActivity from scratch; the OLD `vm.ui.collect`
coroutine was still alive emitting `updateUi()` while the NEW onCreate
launched a SECOND collector. Two concurrent updateUi flows hammered
`renderTreasuryPositions` → SimpleDateFormat.initialize → ICU Locale.clone
/ Bidi until Main froze.

(1) **AndroidManifest** — added `configChanges="orientation|screenSize|
    screenLayout|smallestScreenSize|fontScale|keyboard|keyboardHidden"` to
    `PipelineHealthActivity`. Stops Android from killing & recreating
    MainActivity when the user toggles the panel.

(2) **MainActivity `vm.ui.collect` → `repeatOnLifecycle(STARTED)`.**
    `onCreate` line ~787. Wrapped the existing 2.5 s collect loop in
    `repeatOnLifecycle(Lifecycle.State.STARTED)` so the collector dies
    at `onStop` and restarts at `onStart` — exactly one collector ever
    alive. Added the `androidx.lifecycle.repeatOnLifecycle` import.

(3) **`renderTreasuryPositions` SimpleDateFormat promoted to class field.**
    The local `val sdf = SimpleDateFormat("HH:mm", Locale.US)` was
    allocating fresh on every render (ICU/Bidi clone → 5+ second
    freeze in V5.9.1065 stack). Now `private val treasuryTimeSdf` is
    constructed once at class-init. The function aliases it as `sdf`
    so no further changes required.

(4) **Journal wipe diagnostic.** Operator reports "trade journal is gone
    AGAIN". Forensic search confirmed only two paths can wipe SQLite —
    `clearAllTrades()` (Journal "Clear" button) and `fullResetIncluding-
    Lifetime()` (BehaviorActivity reset). The V5.9.1063 stopBot paper-
    purge is gone. V5.9.1066 back-fill ONLY modifies tradingMode,
    never deletes. Added an `ErrorLogger.info` at the top of `init()`
    that logs the SQLite row count + DB file path + DB byte size on
    every init — the next snapshot's ErrorLog tail will prove whether
    SQLite is genuinely empty (fresh install / Android system "Clear
    data") or whether something is killing the load path.

Build tag bumped to V5.9.1067. Brace/paren deltas validated balanced.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1066 — Lift FDG bootstrap-only restriction · dumpText micro-opt · back-fill SELL tradingMode (Feb 2026)

Operator V5.9.1065 snapshot triage. Picked a + d + f from the proposed plan.

(a) **FDG EXECUTION_FLOOR: lifted bootstrap-only restriction.**
    `FinalDecisionGate.kt:1392-1407`. Previously the BC-only fallback
    (`exitCapacityUsd ← lastLiquidityUsd` when no Raydium/Jupiter pool
    is observed) only fired during `learningProgress < 0.5`. After
    bootstrap, 369 of 395 FDG blocks (93 %) in the V5.9.1065 snapshot
    were `LIQUIDITY_BELOW_EXECUTION_FLOOR` because PumpPortal streams
    fire nothing but bonding-curve-only tokens (exitCap=0). Operator
    decided the bot must trade these anyway and let learning self-
    adjust per lane. BC-fallback now always fires when exitCap≤0,
    regardless of bootstrap state. Live mode still has its own
    pre-flight pool check in the executor (separate from FDG).

(d) **PipelineHealthActivity dumpText micro-opt.**
    `PipelineHealthActivity.kt:onCreate`. The ~16 KB section text
    triggered `BREAK_STRATEGY_HIGH_QUALITY` + auto-hyphenation on
    every refresh — visible in V5.9.1065 as a 514 + 263 ms render-
    back. Set `breakStrategy = BREAK_STRATEGY_SIMPLE`,
    `hyphenationFrequency = HYPHENATION_FREQUENCY_NONE`, and
    `setTextIsSelectable(false)` once at field init. Drops the
    per-refresh TextView layout cost to ~150-200 ms.

(f) **Back-fill SELL `tradingMode` from matching BUY.**
    `TradeHistoryStore.recordTrade()`. The 294-trade / -33 SOL gap
    between Strategy Expectancy (+19 SOL on 618 binned trades) and
    Performance Analytics (-13.7 SOL on 912 closed trades) was
    entirely unbinned fallback exits: `[SELL_OPT] Stop Loss`,
    `v8_stop_loss`, `fluid_stop_loss`, `FALLBACK_ORPHAN_HARD_FLOOR`.
    These exit reasons carry no lane affinity but the position they
    are closing DOES — every BUY records its `tradingMode`. Patch:
    when a SELL arrives with blank tradingMode, scan the in-memory
    trades list (reversed) for the most recent matching BUY and
    inherit its `tradingMode`. The two reports will now reconcile.

Build tag bumped to V5.9.1066. Brace/paren deltas validated.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1065 — Rip SessionSafetyHalt · defer PipelineHealthActivity onCreate (Feb 2026)

Operator directive (verbatim): *"everything has to have a chance to learn
then self adjust into the best lane for winrate and profit. never fucking
pause or disable. thats so off fucking task"*.

(a) **REMOVED `SessionSafetyHalt`** (V5.9.1049). It paused paper entries
    after 50 trades with WR<25 % — that IS a pause/disable and the
    operator has now explicitly forbidden it. Bot must keep trading;
    learning weights self-adjust per-lane via existing TradingCopilot
    + FluidLearning + losing-pattern memory. Removed: the entire
    `Executor.paperBuy()` halt gate, the `recordPaperBuy()` call next
    to FluidLearning recording, and `BotService.startBot()` reset.
    Deleted: `SessionSafetyHalt.kt`.

(b) **`PipelineHealthActivity.onCreate` ANR purge.** V5.9.1064 snapshot
    showed 4 consecutive 250 ms+ frame hits (1010 + 757 + 505 + 251 ms
    ≈ 2.5 s) every time the panel opens — that's the "black screen
    hang" the operator hits. Stack: Button.<init> → Paint.<init> →
    NativeAllocationRegistry. The XML inflate via `setContentView`
    is unavoidable, but the 8× findViewById + 7× setOnClickListener
    chain is queued behind `window.decorView.post { }` so the
    initial layout paints on the next vsync (~16 ms) and listener
    wiring runs while the user already sees the panel.

Build tag bumped to V5.9.1065.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1049 — Triage: journal/MainActivity ANR purge · drawdown overflow · 50-trade session halt (Feb 2026) — partially rolled back V5.9.1065

Operator panic snapshot (V5.9.1040, build 5.0.3010): **27 217 ms max
frame gap**, 11 % stall%, top ANR offenders `MainActivity.
renderTreasuryPositions`, `ErrorLogActivity.exportLogs`, and the
Journal export path; bot blew past 50 paper trades to 161 journal
records with WR=12.5 % and -1.4081 SOL realized PnL while Max
Drawdown showed an absurd **459 621.4 %**. Five surgical fixes
(a/b/c/d/e), no butterflies, no refactors:

(a) **`renderTreasuryPositions` view leak + per-tick coil.load**
    `MainActivity.kt:3902-4046`. Previous code (V5.9.730 dirty-skip)
    correctly guarded `addView(row)` behind `!samePositions` but
    still appended a divider every tick AND constructed throwaway
    `LinearLayout`/`ImageView`/`TextView` per position with a fresh
    `load("https://cdn.dexscreener.com/…")` call inside coil's
    Bitmap cache — even when the position list was unchanged.
    Net effect: dividers leaked unbounded into `llTreasuryPositions`
    and the per-tick coil flush was the #1 main-thread stall.
    Fix: when `samePositions == true` we still compute
    `childrenUnrealizedSum` (cheap math) but `return@forEach`
    before any view construction or `addView` — full inflate only
    on real list changes.

(b) **`ErrorLogActivity.exportLogs` synchronous SQLite stringify**
    `ErrorLogActivity.kt:172`. `ErrorLogger.exportToText()` walks
    the entire SQLite log table and stringifies every entry while
    sitting on Main. Move to a background `Thread`, post the
    AlertDialog + clipboard write back via `Handler(mainLooper)`.
    Toast "Preparing logs…" gives the user immediate feedback.

(c) **`JournalActivity` export coroutines defaulted to Main**
    `JournalActivity.kt:235-321`. `lifecycleScope.launch` with no
    dispatcher defaults to `Dispatchers.Main.immediate`, so every
    `journal.exportPaperCsv(tokens)` / `exportPdf` / `exportAll`
    ran on the UI thread — these methods walk `TradeJournal.
    buildJournal` (full SQLite scan), build thousands of CSV rows
    and write to `cacheDir` synchronously. Fix: explicit
    `Dispatchers.IO`, wrap the final `startActivity(...)`/`Toast`
    in `runOnUiThread`. Toast "Preparing export…" up front.

(d) **Max Drawdown math overflow (459 621.4 %)**
    `PerformanceAnalytics.calculateDrawdown` line 209-228.
    Equity starts at 0.0 (cumulative PnL, not actual balance), so
    the first small positive equity becomes the "peak" — a single
    larger loss later divides by that microscopic peak and yields
    six-figure percentages. Fix: ignore peaks below a 0.05 SOL
    floor, and clamp `maxDdPct` / `currentDdPct` at 100 % (by
    definition a 100 % DD = full wipeout, nothing worse exists
    on the percentage scale).

(e) **SessionSafetyHalt — 50-trade circuit breaker** (NEW)
    Operator verbatim: *"it should be stopping trading after 50
    trades either mate!!!"*. New `SessionSafetyHalt.kt` object:
    after 50 successful paper buys this session, if
    `FluidLearning.getWinRate()` < 25 %, latch the halt and refuse
    new paper entries (exits and live trades are NEVER blocked).
    Reset on every `BotService.startBot()`. Wired at the top of
    `Executor.paperBuy()` (single canonical fence covering all 7
    sub-trader fallback call sites) and recorded on successful
    paper buys right next to `FluidLearning.recordPaperBuy`.

Build tag bumped to V5.9.1049 (`PipelineHealthCollector.BUILD_TAG`).
Brace/paren counts validated as balanced deltas relative to HEAD.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1048 — 5-fix pass: STANDARD note · V3 reason · EXEC counter · Birdeye backoff · moonshot throttle (Feb 2026, CI ✅ green)

Operator V5.9.1047 dump surfaced 5 follow-ups. All addressed:

(a) **STANDARD bin glossary note** — added a one-line clarifier
    below the strategy expectancy block: `STANDARD = V3 default
    (no lane affinity, TokenMemory.kt fallback), partly survivor-
    biased since promotions reclassify mid-trade`. Demystifies the
    suspicious-good n=32 WR=100% +207%/trade reading.

(b) **V3 reject reason histogram** — extract `.reason` from
    `V3Decision.Rejected` and `V3Decision.Blocked`, not just
    `BlockFatal`. V5.9.1046's REJECTED_FATAL_V3 lifecycle event
    silently dropped 128 Rejected-class reasons because the
    extractor only consulted the BlockFatal subclass. Histogram
    should now bucket meaningfully across V3 sub-reasons.

(c) **EXEC_BUY counter key mismatch** — `execBuy` was reading
    legacy `EXEC/PAPER_BUY`+`EXEC/LIVE_BUY` keys while
    `TradeHistoryStore.recordExec` writes `EXEC_BUY`/`EXEC_SELL`.
    `execSell` already matched; only `execBuy` was wrong, so the
    snapshot showed EXEC_BUY=0 while logs proved many actual BUYs.

(d) **Birdeye 429 backoff** — wired `ApiBackoff.isLockedOut("birdeye")`
    check into `BirdeyeApi.get()` AND `markFailure(code)` /
    `markSuccess()` on every response. Operator V5.9.1047 dump
    showed birdeye sr=59% 4xx=344 — Birdeye was being hammered
    through 429s because the existing ApiBackoff infrastructure
    wasn't engaged. Now: consecutive 4xx escalates the lockout
    schedule (5s→5min cap).

(e) **renderMoonshotPositions throttle** — added 8s minimum
    render interval guard (same `OPEN_POS_MIN_RENDER_INTERVAL_MS`
    as renderOpenPositions). Operator V5.9.1047 dump showed 509ms
    ANR; structural hash skipped no-change rebuilds but moonshot
    open/close still fired Coil image loads + 4 rows inline on
    Main. Rapid sequences now collapse to one rebuild.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1047 — 4-file UI ANR purge (Feb 2026, CI ✅ green)

Operator V5.9.1046 dump showed engine throughput up 267% but UI
stall spiked 4% → 28% with a single 42s freeze. Four-file targeted
purge of the new top ANR offenders:

(a) **BrainNetworkView** — `drawEngineDot`/`drawBrain` were the
    top main-thread offenders (200-1278ms per onDraw). Throttled
    animator 200ms → 1000ms (5fps → 1fps; still visually animated,
    5× less Main CPU load), enabled `setLayerType(LAYER_TYPE_HARDWARE,
    null)` so the canvas is cached as a GPU texture between
    invalidates.

(b) **PipelineHealthActivity eager bgThread** — dropped `by lazy`
    on `bgThread`; HandlerThread.start() now runs at class-field
    init, before `renderSnapshotAsync` is called from `onCreate`.
    V5.9.1045's daemon pre-warm lost the race (snapshot showed
    793ms on Main walking Thread.<init>); eager init guarantees
    `Handler(bgThread.looper)` is instant. Daemon pre-warm code
    removed.

(c) **BotViewModel pollLoop on Dispatchers.Default** — `viewModelScope
    .launch` was using the default Main dispatcher, so the entire
    pollLoop body — including indirect resource lookups that
    inflate VectorDrawables — ran on Main. The trace showed
    `VectorDrawable.nCreateFullPath` beneath pollLoop at 1059ms.
    Switched launch context to `Default`; StateFlow.value is
    thread-safe so UI consumers still observe updates correctly.

(d) **JournalActivity buildJournal data-prep async** — split
    `buildJournal` into two methods: data prep
    (`journal.buildJournal` + `journal.getStatsFiltered`) on
    `Dispatchers.IO`, view rendering on Main via `runOnUiThread`.
    Disk reads no longer block UI thread; only the View inflation
    loop (which must be on Main) stays there.

Expected combined impact: stall % drops 28% → <5%, max frame gap
collapses, BrainNetworkView/JournalActivity disappear from ANR top
sites.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1046 — supervisor decouple + tile BG + V3 reject histogram (Feb 2026, CI ✅ green)

Three quality-of-life upgrades on top of V5.9.1045:

(a) **BotService SLOT-RELEASE DECOUPLE**. V5.9.1045 dump showed
    12 SUPERVISOR_WORKER_TIMEOUTs firing per 4min (10s timeout
    working) but ~36 slots still held between resets. Root cause:
    `withTimeoutOrNull` SUSPENDS the outer coroutine until
    `runInterruptible`'s inner block returns, and
    `processTokenCycle`'s outer try/catch SWALLOWS
    `InterruptedException` → runaway thread keeps running, slot
    stays held. Fix: spawn a separate watchdog coroutine that
    delays for `SUPERVISOR_WORKER_TIMEOUT_MS + 500ms` then
    unconditionally releases the slot via `AtomicBoolean.compareAndSet`.
    Worker's `finally` also calls `release()`; the CAS ensures
    only one decrement happens. Slot is now guaranteed to free
    within timeout+0.5s regardless of inner thread state.
    Expected: `SUPERVISOR_POOL_RESET` drops to 0/min.

(b) **MainActivity pipelineTileRefresh BG snapshot**. V5.9.1045
    dump showed `pipelineTileRefresh$1.run` hitting 503ms on
    Main because `PipelineHealthCollector.snapshot()` walks 12+
    ConcurrentHashMaps inline on the UI thread every 5s. Now:
    dedicated single-thread executor builds the formatted
    string off-main; only the final `setText`/`setTextColor`
    stays on Main.

(c) **PipelineHealthCollector V3 reject reason histogram**.
    Operator V5.9.1045 dump showed `REJECTED_FATAL_V3=132` but
    the bare counter doesn't tell which V3 sub-gate dominates.
    New `v3RejectReasonCounts` map parses the `reason=` field
    from each `REJECTED_FATAL_V3` lifecycle event, normalises
    to the first two colon-segments (so `V3:RUG_FATAL` collapses
    across sub-reasons), and surfaces a "Top V3 reject reasons"
    section in the snapshot dump. Operator can now triage the
    biggest contributor in one glance instead of grepping logs.
    Zero behaviour change in trading paths.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1045 — supervisor timeout 10s + 2 UI ANR fixes (Feb 2026, CI ✅ green)

Operator V5.9.1044 snapshot confirmed runInterruptible is real (14
SUPERVISOR_WORKER_TIMEOUTs fired vs 0 before) but 6
SUPERVISOR_POOL_RESETs still kicked in over 6min, plus two new
top UI ANR offenders surfaced. Triple-fix:

(a) **`SUPERVISOR_WORKER_TIMEOUT_MS 20s → 10s`**. 10s ≈ 2×
    the avg 5.7s tick cadence and ≈1× OkHttp 15s read timeout.
    Workers that exceed one round-trip get reaped immediately;
    next tick's WATCHLIST_RR re-picks them — no signal loss.
    Expected impact: SUPERVISOR_POOL_RESET drops to near-zero,
    SUPERVISOR_WORKER_TIMEOUT rises proportionally (telemetry
    signal, not bug).

(c) **`PipelineHealthActivity` bgHandler pre-warm**. The
    `by lazy` accessor on `bgHandler` triggered
    `HandlerThread.getLooper()` (a synchronized `Object.wait()`)
    on Main when first accessed in `onCreate`. V5.9.1044's #1
    ANR was 930ms on this exact call site. Now pre-warmed on
    a daemon worker thread so the lazy wait completes off-main.

(d) **`SplashActivity` logo pulse**. Replaced the custom
    ValueAnimator + addUpdateListener pattern (which boxed a
    Float and dispatched the lambda on Main every frame —
    causing the SplashActivity.onCreate\$lambda\$2\$lambda\$1
    Float.valueOf hotspot in 5+ ANR_HINTs per snapshot) with
    two pure ObjectAnimators on the view's hardware-accelerated
    scaleX/scaleY properties. Per-frame work now runs on
    RenderThread.

Skipped:
- (e) FDG CONFIDENCE_FLOOR_22% relaxation by design: operator
  V5.9.809 mandate explicitly revoked wide-open mode. Recent
  regime=NORMAL wr=40.6% suggests learning is on track without
  lowering the floor.
- (b) deep processTokenCycle interrupt-yield insertion:
  runInterruptible + 10s timeout already covers the leak
  symptom; modifying a 10k-line function is high-risk for low
  marginal gain.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1044 — runInterruptible workers (REAL pool fix, not band-aid) (Feb 2026, CI ✅ green)

Operator V5.9.1042 snapshot proved the watchdog band-aid works
(bot trading, 6192 LANE_EVALs, 60+ exits) but ALSO proved the
underlying leak is severe: `SUPERVISOR_POOL_RESET` fired 29 times
in 20 min (every ~42s). Investigation pinpointed why:

```
private fun processTokenCycle(...)   ← NOT suspend
                                       NO suspension points
                                       withTimeoutOrNull(20s) NEVER cancels
```

Coroutine cooperative cancellation only triggers at suspension
points. `processTokenCycle` is a plain blocking function — OkHttp
socket reads, synchronized SQLite writes, Birdeye/DexScreener
HTTP calls all run to completion regardless of the outer
`withTimeoutOrNull`. So the 20s budget was effectively dead code;
workers ran their natural duration (often >>20s with degraded
API health), leaking pool slots until V5.9.1042's watchdog
band-aid kicked in every 30s.

Fixed:
- Wrapped the worker body in
  `runInterruptible(Dispatchers.IO) { processTokenCycle(...) }`.
- `runInterruptible` upgrades coroutine cancellation into a real
  `Thread.interrupt()` on the worker thread.
- Blocking I/O (OkHttp, SQLite, Thread.sleep, NIO channels) honors
  thread interrupt → workers actually die when their 20s budget
  expires → `finally` block decrements `supervisorActive` → pool
  stays healthy without the watchdog.

V5.9.1042's watchdog stays in place as a safety net. Expected
behavior in next snapshot: `SUPERVISOR_POOL_RESET` drops to ~0,
`supervisorLifetimeWorkerTimeouts` rises to match the actual
non-cooperative hang rate, and `SCAN_CB` (completed cycles)
climbs sharply because slots free up much faster.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1043 — collapse legacy bin names at read time (Feb 2026, CI ✅ green)

Operator V5.9.1041 snapshot still showed `BLUECHIP` (n=134) AND
`BLUE_CHIP` (n=25) as separate strategy expectancy bins despite the
write-side normalization shipped in V5.9.1038. Trades persisted to
SQLite BEFORE V5.9.1038 still carry the legacy `BLUE_CHIP` string,
and `StrategyTelemetry.computeLeaderboard()` groups raw values
verbatim.

Fixed:
- Exposed `TradeHistoryStore.normalizeTradeModeName()` as public so
  read-side aggregators can call it.
- `StrategyTelemetry.computeLeaderboard()` now normalizes each
  trade's `tradingMode` at `groupBy` time → legacy BLUE_CHIP merges
  into BLUECHIP, identical to newly-recorded trades.

Read-only telemetry path; no entry/exit logic touched.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1042 — silent-supervisor pool watchdog (UNFREEZE) (Feb 2026, CI ✅ green)

Operator V5.9.1041 ~30min uptime dump showed the pool RE-SATURATED:

```
SUPERVISOR_INFLIGHT_CAP: 335 events  · spawned=0 skipped=96 active=48 cap=48
last paper BUY = 26+ minutes before snapshot capture
EXEC = 0  ·  bot visually "frozen" (loop healthy, executions choked)
```

V5.9.1039's `withTimeoutOrNull(20s)` is COOPERATIVE — workers stuck
in non-cooperative blocking ops (SQLite write / native socket / JNI)
never observe the cancellation, the slot stays held forever, and
`supervisorActive` never decrements. V5.9.1041 added the
`supervisorLastSpawnAt` + `supervisorLifetimePoolResets` fields and
described a watchdog in inline comments, but the watchdog logic was
NEVER actually coded.

Fixed (V5.9.1042 ships the missing logic):
- At `fireSupervisorWorkers()` entry, if `active >= cap` AND
  `(now - supervisorLastSpawnAt) >= SUPERVISOR_POOL_STALL_MS (30s)`,
  force-reset `supervisorActive` to 0, bump
  `supervisorLifetimePoolResets`, and emit `SUPERVISOR_POOL_RESET`
  via ForensicLogger so the reset is visible in pipeline snapshots.
- On every successful worker spawn, update `supervisorLastSpawnAt`
  so the stall detector resets correctly under healthy operation.
- Truly-stuck workers eventually decrement `supervisorActive` into
  negative territory; safe — the cap check uses `get() < cap`, so
  negative just means extra headroom.

If `SUPERVISOR_POOL_RESET` fires frequently in future dumps, that's
the signal to chase the deeper non-cooperative-block hotspot
(probably SQLite write contention or a synchronous emitter). The
bot keeps trading either way.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1039 — per-worker timeout (silent-supervisor pool saturation fix) (Feb 2026, CI ✅✅ green)

Operator V5.9.1038 4h-uptime dump revealed a critical bug introduced by
V5.9.1037's silent supervisor refactor:

```
SUPERVISOR_INFLIGHT_CAP: 2238  ← every cycle for 4 hours
spawned=0  skipped=96  active=48  cap=48
EXEC=2 only  ·  projected execs/day: 166 🛑 CRITICAL
```

The 48-worker pool was PERMANENTLY saturated by hung workers. V5.9.1037
removed the chunk-level `withTimeoutOrNull`, but forgot to add a
per-worker safety net. When a worker blocks on a slow Birdeye/DexScreener
call, nothing cancels it — `supervisorActive` never decrements → the
slot is occupied forever → after 48 hung workers accumulate, every
cycle spawns ZERO new workers.

Fixed:
- Wrap `processTokenCycle` + `markProcessed` in `withTimeoutOrNull(20s)`
  inside the worker. 20s is ~3× normal p95 so legitimate work completes;
  stuck workers cancel and the `finally` block decrements
  `supervisorActive` normally.
- New `supervisorLifetimeWorkerTimeouts` counter.
- New `SUPERVISOR_WORKER_TIMEOUT` forensic event with mint+budget tag.

Other operator findings (not regressions, just visibility):
- 956 ANR_HINTS scary-looking but stall % only 6.1% — IMPROVED from
  V5.9.1037's 8.7%. Top blockers were system-idle
  (`MessageQueue.nativePollOnce`, `nativeGetLatestVsyncEventData`), not
  real bot blocks.
- INTAKE_BURST_REJECT=640 and INTAKE_LIQ_ZERO_REJECT=392 — V5.9.1035
  filters working perfectly at scale.
- BLUECHIP=39 vs BLUE_CHIP=25 still split because V5.9.1038's
  `normalizeTradeModeName` only applies to NEW trades; legacy journal
  entries retain old casing. Converges naturally over time.



## V5.9.1038 — TRIAGE FIXES: TradeHistoryStore dedupe + mode normalize + reason fallback (Feb 2026, CI ⏳)

Triage agent (called per operator request after V5.9.1037 snapshot showed
recovered cycle time but persistent counter inflation) identified 3 root
causes from the operator's V5.9.1037 dump:

ROOT CAUSE 1 — same close recorded TWICE per position. Operator snapshot
showed back-to-back sells for the SAME mint within one second under
different reasons (CASHGEN_STOP_LOSS + TREASURY_TIME_EXIT). CashGen +
Treasury are independently closing the same position. PositionExitArbiter
(Executor) only catches Executor-path duplicates; V3JournalRecorder's 5s
LRU only catches V3-path duplicates. Cross-path duplicates slip through.

ROOT CAUSE 2 — strategy bin fragmentation. Same trade binned as BLUECHIP
(n=5), BLUE_CHIP (n=23), STANDARD (n=10) in StrategyTelemetry because
Executor and V3JournalRecorder set inconsistent casing/spelling on
Trade.tradingMode.

ROOT CAUSE 3 — 64% of canonical outcomes still featuresIncomplete.
Operator's AURAMAXX MOONSHOT_STOP_LOSS trade shows source=UNKNOWN
despite V5.9.1035's lite-rich bridge. Some exit paths
(sweepUniversalExits, rapid-monitor closes) create Trade objects with
reason='MOONSHOT_STOP_LOSS' but blank tradingMode, so normalizeMode
returns UNKNOWN BEFORE the V3 fallback can fire.

Fixed:
1. `TradeHistoryStore.recordTrade` choke-point dedupe LRU keyed on
   "${mint}_${ts}_SELL" with 5s TTL window. SELL-only. Logs
   `TRADEJRNL_DEDUP_SKIP` when a duplicate is caught.
2. `TradeHistoryStore.normalizeTradeModeName` canonicalizes mode strings
   at the choke point so StrategyTelemetry bins converge.
3. `CanonicalLearning.publishFromLegacyTrade` reason fallback — when
   tradingMode is blank, infer from reason (MOONSHOT_STOP_LOSS →
   MOONSHOT, CASHGEN_* → CASHGEN, RAPID_/FLUID_ → STANDARD).
4. `Executor.recordTrade` inherits `ts.position.tradingMode` whenever
   the Trade's is blank (source-of-truth fix).

Expected impact: TradeHistoryStore.size matches canonical settled count;
StrategyTelemetry bins converge; richFeatureOutcomes 36% → ~90%+;
strategy learners finally train on every close.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1037 — SILENT SUPERVISOR (fire-and-forget; bot loop never awaits) (Feb 2026, CI ✅ green, deployed)

Operator V5.9.1037 verified: cycles dropped from ~20s avg to ~5s avg
(max 6.5s). `SUPERVISOR_INFLIGHT_CAP` events firing at the new 48-worker
cap. `SUPERVISOR_CHUNK_TIMEOUT` gone from the labelled counters.
ANR_HINTS dropped to 13 (almost entirely UI-side now: PipelineHealthActivity
+ Splash animation). richFeatureOutcomes 27 → 340 (3% → 36%) thanks to
V5.9.1035's lite-rich bridge — but 617 still incomplete (fixed in V5.9.1038).



## V5.9.1037 — SILENT SUPERVISOR (fire-and-forget; bot loop never awaits) (Feb 2026, CI ⏳)

Operator V5.9.1036 snapshot showed bot loop wedged 14-20s per cycle on
`SUPERVISOR_CHUNK_TIMEOUT` (23 chunk timeouts in 150s), with EVERY cycle
logging `processed=0 deferred=96` despite trades still executing via the
SCAN_CB direct path.

Root cause: legacy `runSupervisorPhase` chunks the watchlist into groups
of 32, spawns `GlobalScope.async` workers, then awaits each chunk via
`withTimeoutOrNull(4.5s) { jobs.awaitAll() }`. If even ONE worker blocks
past 4.5s the await trips, ALL 32 jobs get cancelled, and the bot logs
`SUPERVISOR_CHUNK_TIMEOUT`. With 96 mints × 3 chunks × 4.5s = ~14s per
cycle wasted in pure dead waiting.

Critical insight: the per-worker side effect (`processTokenCycle` +
`markProcessed`) already runs on detached `GlobalScope.async(IO)` — the
bot loop's await was burning cycles to populate `processed/deferred`
counts that ONLY feed forensic log strings. No downstream control flow
depends on them. So the await is pure dead waiting.

Fixed:
- New `fireSupervisorWorkers` helper: spawns workers via
  `GlobalScope.launch` (no `async`, no `awaitAll`, no `withTimeoutOrNull`).
- Bounded in-flight concurrency via `supervisorActive` AtomicInteger
  with `SUPERVISOR_MAX_INFLIGHT=48` cap (mints over cap are skipped and
  re-evaluated next cycle from a fresh ordering).
- Atomic counters surface lifetime supervisor health:
  `supervisorLifetimeSpawned / Processed / Skipped`.
- OkHttp dispatcher's `maxRequestsPerHost=16` (V5.9.1032) remains the
  real API rate-limit floor.
- New `SUPERVISOR_INFLIGHT_CAP` forensic event fires when skip>0.
- `runSupervisorPhase` kept as dead code for rollback safety.

Expected impact:
- Cycles drop from ~15-20s to ~2-5s (bot loop now sees ~0ms supervisor cost)
- `SUPERVISOR_CHUNK_TIMEOUT` events drop to zero
- SCAN_CB / V3 / FDG / EXEC counters ramp 3-5×

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1036 — ANR fixes (onCreate off-main) + botLoop bytecode reclaim + tighter dust gate (Feb 2026, CI ✅ green, deployed)

Operator V5.9.1034b snapshot showed 29.9% main-thread stall with
maxFrameGap=32570ms and `TradeLessonRecorder.exportState` topping the ANR
chart (14 samples). `botLoop` also approaching JVM 64KB cap again
(V5.9.1035 release passed but DEBUG smoke failed with `Couldn't transform
method node: botLoop`).

Fixed:

1. `LearningPersistence.init` — split synchronous DB open (fast, required
   for putBlob/getBlob immediately) from `loadAll()` (background IO). The
   ~3000-lesson `TradeLessonRecorder.importState` JSON parse (1934ms ANR)
   plus 12 other brain-state blob restores now run off-main. Kills the
   #1 ANR offender.

2. `MemeMintRegistry.init` — `restoreFromDisk` (2185ms ANR parsing 2557
   mints / 511KB JSON) moved to background scope. `appCtx` stays sync
   so `touch()` / `scheduleSave()` work immediately.

3. `BotService.botLoop` reclaim — extracted two large inline blocks:
   - `runPendingVerifyWatchdog(wallet)`     → -110 lines of bytecode
   - `run180TickTelemetry(cfg)`             → -109 lines of bytecode
   Net: ~219 lines reclaimed inside botLoop's outer try{}.

4. Intake dust gate tightened — V5.9.1035's INTAKE_LIQ_ZERO_REJECT
   required strictly liq=$0 && mcap=$0 && single-source. Operator
   snapshot showed pump.fun spam landing with liq=$0.001 mcap=$0.01
   sources=2 via the MULTI-SCANNER BYPASS path. Tightened to liq<$1 &&
   mcap<$10 (no source restriction).

Operator V5.9.1036 verified:
- ANR stall 29.9% → 8.7% (3.4× better)
- maxFrameGap 32570ms → 9106ms
- INTAKE_LIQ_ZERO_REJECT=120 / INTAKE_BURST_REJECT=3 firing correctly
  (HENRY caught at 5 distinct mints/60s — clone-storm hard reject)
- TradeLessonRecorder.exportState + MemeMintRegistry.restoreFromDisk
  both gone from ANR top-N list
- Trading active: EXEC_BUY=3, EXEC_SELL=99, 165 journal writes

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1035 — counter-drift fix + lite-rich legacy bridge + Part 2 intake spam filter (Feb 2026, Build ✅ Smoke ❌ [JVM 64KB, fixed in V5.9.1036])

Operator screenshot showed `AdaptiveLearning Δ=-424` and `BehaviorLearning
Δ=-414` against the canonical settled baseline (427 trades) — strategy
learning "useless". Combined with the user's standing Part 2 mandate to
hard-reject intake spam at the door.

Fixed:

1. Counter drift display — `LearningCounterActivity` now reads
   `AdaptiveLearningEngine.getTradeCount()` and
   `BehaviorLearning.getCanonicalAlignedTradeCount()` (both already
   canonical-aligned to settledWins+settledLosses). Previous display
   read session-only / feature-gated raw counters that lag by design.

2. Lite-rich legacy bridge — `publishFromLegacyTrade` now builds a
   minimal `CandidateFeatures` from the Trade record alone (mode-derived
   trader/venue/route/assetClass) and emits `featuresIncomplete=false`
   for any known mode. Strategy learners (AdaptiveLearning,
   BehaviorLearning) finally train on every settled trade instead of
   skipping 97% of them.

3. `inferAssetClassAndSource` extended: STANDARD / PROJECT_SNIPER /
   DIP_HUNTER / COMMUNITY → (MEME, TradeSource.V3) so the venue/route
   fallback resolves to PUMP_FUN_BONDING/PUMP_NATIVE instead of UNKNOWN.

4. Part 2 intake filter:
   - INTAKE_LIQ_ZERO_REJECT: hard-reject liq=$0 + mcap=$0 + single-source
     + not user/restore (later tightened to liq<$1 mcap<$10 in V5.9.1036).
   - INTAKE_BURST_REJECT: hard-reject when ≥5 DIFFERENT mints land with
     the same symbol in <60s (clone-storm guaranteed rugs).

Known regression: DEBUG-compile botLoop hit the JVM 64KB method size
cap. Fixed in V5.9.1036 by extracting `runPendingVerifyWatchdog` and
`run180TickTelemetry` helpers (-219 lines of botLoop bytecode).



## V5.9.1034b — Fix cap-evict to drain unseen pool (the real overflow) (Feb 2026, CI ⏳)

Operator V5.9.1034 snapshot (build 2995, tag V5.9.1034) verified two wins:
  • WATCHLIST_CAP_EVICT firing correctly (evicted=31 sizeBefore=1694, etc)
  • ANR_HINTS=0 Stall=0% (was 13.1% — UI is healthy again)
  • TokenMetaCache hit rate 16.4% → 50.6%

But the cap wasn't actually enforcing: watchlist still climbed back to
1693 after the evictions. Why?

Root cause: the V5.9.1034 cap-evict pool scanned ONLY `cold`. Operator
dump showed `cold=0 unseen=1649`. Pump.fun spam adds processCount=0
mints faster than the supervisor can graduate them to cold (supervisor
itself is wedged on 4.8s chunk timeouts), so cold stays near-empty
while unseen pile up. The cap loop had nothing to evict.

**Surgical fix**: extend the eviction pool to include `unseen` too.
Sort by lastProcessedAt (cold) + addedAt (unseen) ASC, take oldest
`excess` count. Drop evicted mints from BOTH `coldAfterCap` AND
`unseen` so the picker doesn't try to process tokens we just removed.

forcedOpenMints (open positions) and fresh-60s window remain exempt.

Also: V5.9.1034 unexpectedly fixed the Runtime Smoke Test 🟢 — the
extra helper code in selectOrderedMintsForCycle freed enough botLoop
bytecode that the Debug JVM 64KB cap is no longer hit. First green
smoke since V5.9.1027b.

Touched: `engine/BotService.kt` (selectOrderedMintsForCycle cap-evict).
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1034 → V5.9.1034b.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1034 — Watchlist cap 250 + 5min stale eviction (Part 1) (Feb 2026, CI ✅)

**Operator mandate**: "the watch list is now obviously way too big as well.
the bots never had more than 100 open positions maybe we reduce the
watchlist size to 250 and prune stale or non moving tokens after 5
minutes... once a token is stored it doesn't need to be rescanned unless
specifically being interacted with by the watchlist or is an open position.
we are burning a lot of data there on every loop."

V5.9.1031 snapshot evidence:
  • watchlist `total=1316` (was supposed to be ~100-200)
  • SUPERVISOR_CHUNK_TIMEOUT 96/96 every cycle
  • Birdeye SR=83% (430 × 4xx rate-limits in 748s)
  • TokenMetaCache hit rate=16.4% (84% of fetches are fresh API hits)

**Surgical changes (no logic deletions, no behavioural reversals)**:

1. Two new constants in `selectOrderedMintsForCycle()`:
   ```kotlin
   val STALE_AGE_MS = 5L * 60_000L     // 5 minutes idle
   val MAX_ACTIVE_WATCHLIST = 250      // hard cap
   ```

2. New eviction pass — TIME-BASED stale drain.
   Walks `cold` after the existing V5.9.961 process-count filter. Any
   entry with `processCount >= 1` AND `(now - lastProcessedAt) > 5min`
   AND NOT in `forcedOpenMints` is removed via `GlobalTradeRegistry
   .removeFromWatchlist(mint, "STALE_5MIN")`. Forensic:
   `WATCHLIST_STALE_EVICT_TIME evicted=N ageMs=300000`.

3. New eviction pass — HARD CAP at 250.
   After the time-stale pass, if `getWatchlistEntries().size > 250`,
   evict the oldest `excess` cold non-position entries by
   `lastProcessedAt` ascending. Forensic:
   `WATCHLIST_CAP_EVICT evicted=N cap=250 sizeBefore=...`.

**Untouched** (per operator "no butterfly effect regressions"):

  • All 9 trader lanes (CashGen / Moonshot / Shitcoin / Bluechip /
    Treasury / Quality / Manipulated / Dip Hunter / Project Sniper).
  • Scanner intake — still receives everything (we throttle downstream).
  • Open-position protection — `forcedOpenMints` set is honoured in
    BOTH the stale-time and cap-evict passes (an open position can
    never be evicted by this code).
  • V5.9.961 existing process-count eviction stays as belt-and-braces.
  • Fresh / unseen / cold categorisation logic unchanged — only the
    cold pool is reduced.

**Expected dump deltas** (based on V5.9.1031 baseline):

  • watchlist total:                1316 → ~250 (hard cap)
  • SUPERVISOR_CHUNK_TIMEOUT/cycle:    3 → 0-1 (fewer tokens to chew)
  • Birdeye SR:                      83% → 95%+
  • TokenMetaCache hit rate:         16% → 60%+
  • Cycle time:                  22-28s → 12-15s
  • daily Birdeye CU burn:        ~50%   → ~25%

Touched: `engine/BotService.kt` `selectOrderedMintsForCycle()`.
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1033 → V5.9.1034.

**Part 2 (deferred)**: per-lane fluid scanner learning (Moonshot vs
Bluechip vs Shitcoin pattern recognition + hard-reject guaranteed-rug
tokens at intake). Requires care in lane-scoring weights — separate push.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1033 — Reliable Stop button: hard-cancel loopJob + abort in-flight HTTP (Feb 2026, CI ⏳)

**P0 — Operator emergency**: V5.9.1031 snapshot showed `ACTION_STOP_RECEIVED
source=ui_stop_button` at 04:03:26 but bot kept running 10+ minutes after.
Stop button effectively dead. Start button also unresponsive (because the
previous loopJob was still wedged).

Root cause: `stopBot()` ONLY set `status.running = false`. That flag is
read at the TOP of the next `botLoop` iteration. With supervisor cycles
running 22-28s (95%+ chunks timing out at 4.8s each) and 32 OkHttp
workers per chunk blocked inside synchronous `.execute()` which does
NOT honour coroutine cancellation, stop was invisible to the user
for an entire cycle PLUS however long it takes 32 sockets to time
out (4s readTimeout + connect). Worst-case: 30-60 seconds. Operator
hit a wedged variant (sockets stuck in CONNECT_WAIT for 10+ min).

**Fix**:

1. `stopBot()` now also calls:
   ```kotlin
   loopJob?.cancel(CancellationException("stopBot:$source"))
   SharedHttpClient.cancelAllRequests()
   ```
   `loopJob.cancel()` wakes any suspended state-machine branch at its
   next suspension point. `SharedHttpClient.cancelAllRequests()`
   delegates to `Dispatcher.cancelAll()` — **every** in-flight AND
   queued OkHttp call across the shared dispatcher is interrupted
   immediately. The supervisor's `awaitAll()` returns within ms with
   IOException("Canceled") instead of waiting for socket timeouts.

2. New `SharedHttpClient.cancelAllRequests()` helper:
   ```kotlin
   fun cancelAllRequests() {
       try { sharedDispatcher.cancelAll() } catch (_: Throwable) {}
   }
   ```
   Idempotent, swallows throwables. Existing callers in flight see
   `IOException("Canceled")` and unwind their try/catch normally.

Net effect: pressing Stop reacts inside ~200ms instead of ~30-60s
(or ∞ in the wedged variant). The startBot V5.9.730 STUCK-LOOP RESCUE
path remains as a fallback if a stale loopJob ever lingers.

Touched: `engine/BotService.kt:4848` (stopBot escape hatch),
`network/SharedHttpClient.kt` (new `cancelAllRequests` helper).
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1032 → V5.9.1033.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1032 — Rate-limit balance after dispatcher un-choke (Feb 2026, CI ⏳)

V5.9.1030's `maxRequestsPerHost=32` worked — operator V5.9.1031 snapshot
showed supervisor `processed=7 of 96` (was permanently 0/96), TokenMetaCache
hit rate 0% → 59.7%. **BUT** we over-hammered the upstream APIs:

  • Birdeye:     SR 99% → 56%   (424 × 4xx rate-limit responses in 99s)
  • DexScreener: SR 99% → 71%   (37  × 4xx)

Cascade: with Birdeye throttling, the bot can't fetch liquidity data, so
86% of FDG rejections are now `low_liquidity` or `zero_liquidity`
(RejectStats 5m: 62 + 49 of 129).

Fix: dial `maxRequestsPerHost` 32 → 16 (and `maxRequests` 128 → 64).
Still 3× the OkHttp default of 5, so the supervisor stays un-choked,
but half the per-minute burden on Birdeye's free-tier 100 RPM cap.

Expected dump deltas:
  • Birdeye SR: 56% → 90%+
  • DexScreener SR: 71% → 95%+
  • SUPERVISOR processed: 7/96 stays the same or improves (per-host
    queue still 3× larger than the original V5.9.1029 baseline)
  • low/zero_liquidity reject rate: 86% → 30-40%

Touched: `network/SharedHttpClient.kt` (per-host dispatcher cap).
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1031 → V5.9.1032.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1031b — Fix CI: hoist HARD_FLOOR_STOP_PCT inside helper (Feb 2026, CI ⏳)

V5.9.1031 introduced `evaluateRapidMonitorExit` but referenced
`HARD_FLOOR_STOP_PCT` — a function-local val declared inside the
parent `rapidStopLossMonitor()`. The helper lost access to that
scope → `Unresolved reference: HARD_FLOOR_STOP_PCT` at BotService.kt:7834.

Fix: declare a local mirror `val HARD_FLOOR_STOP_PCT_CONST = 15.0`
inside the helper — exact same numeric value the original block
compared against. Behaviour-preserving.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1031 — Extract rapid-monitor exit block to fit Debug 64KB cap (Feb 2026, CI ⏳)

V5.9.1030 Build APK ✅ but Runtime Smoke Test ❌ (Debug compile JVM 64KB cap
on `botLoop` STILL exceeded). V5.9.1029's `getCatastropheThreshold` extraction
was not enough — the 4-branch `when` block with 3 suspending
`executor.requestSell` call sites kept botLoop's coroutine state machine
over the cap on Debug builds (Release strips coroutine debug info more
aggressively).

Fix: extract the entire rapid-monitor exit ladder into a new private
suspend helper:

  `evaluateRapidMonitorExit(ts, pnlPct, cfg, wallet, effectiveBalance): Boolean`

The 3 `executor.requestSell` suspension points + the `isCatastrophe` /
`giveBackTrigger` / `hardFloor` decision logic now live in the helper.
Caller pattern collapses to:

  `if (evaluateRapidMonitorExit(...)) continue`

Behaviour identical to V5.9.1030 (settle-in → skip; catastrophe →
RAPID_CATASTROPHE_STOP; give-back → RAPID_DRAWDOWN_FROM_PEAK_STOP;
hard floor → RAPID_HARD_FLOOR_STOP). Removed the unreferenced
`neverWinner` local variable (legacy from pre-V5.9.687 — was only
computed, never gated against).

Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1030 → V5.9.1031.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1030 — Un-choke OkHttp dispatcher + fail-fast read timeouts (Feb 2026, CI ⏳)

Operator V5.9.1029 snapshot (build 5.0.2988, tag V5.9.1029 — bumped correctly
this time): the V5.9.1029 chunk-budget widening (2.5s → 4.5s) didn't help.
SUPERVISOR_CHUNK_TIMEOUT still fired every chunk with `active=32 budgetMs=4800`,
processed=0 deferred=96 EVERY cycle. The 32 workers were genuinely blocking
past 4.8s — but not for any compute reason.

**Q1 — Root cause: OkHttp Dispatcher.maxRequestsPerHost = 5 (default)**

`SharedHttpClient.base` used the OkHttpClient default Dispatcher. Its
defaults:
  • `maxRequests = 64`
  • `maxRequestsPerHost = 5`

The supervisor launches 32 parallel `processTokenCycle` workers; each
calls `dex.getBestPair(mint)` (DexScreener) and Birdeye lookups. With
maxRequestsPerHost=5, ONLY 5 of 32 workers' HTTP requests run truly
in parallel against api.dexscreener.com — the other 27 queue inside
OkHttp's dispatcher. Add a 4-7% timeout tail (Birdeye SR=93%, DS SR=96%)
where one stuck socket blocks for up to 15s (readTimeout) and every
worker that needs that host wedges behind it.

**Fix A — Install a shared Dispatcher with higher concurrency**

  `Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 32 }`

Installed in `SharedHttpClient.base`. Every existing call site that
uses `SharedHttpClient.builder()` inherits the bump automatically —
DexScreener, Birdeye, Jupiter, Helius, pump.fun WS, etc. The operator
is on paid DexScreener / Birdeye tiers, so concurrent-request quota
is no longer the binding constraint.

**Fix B — readTimeout: fail fast or never finish at all**

  • `DexscreenerApi`  readTimeout 15s → 4s  (connectTimeout 10s → 5s)
  • `BirdeyeApi`      readTimeout 12s → 4s  (connectTimeout 8s → 5s)

A single 15s-stuck socket is enough to wedge every supervisor worker
that touches that host. 4s gives ample room for healthy 300-400ms
responses while ensuring no worker can stay past the 4.5s chunk
budget. Cache layers (DexScreener 45s TTL + ApiBackoff) make dropped
fetches recoverable on the next cycle.

**Expected dump deltas**

  • SUPERVISOR_CHUNK_TIMEOUT: should drop from ~28/cycle to near-zero.
  • POST_SUPERVISOR processed:  0 → 90+ (the 96 mints actually get
    re-evaluated each cycle).
  • Watchlist of 545 tokens gets full coverage every 6 cycles instead
    of never. Fluid stops + telemetry get fresh price updates.

Touched: `network/SharedHttpClient.kt`, `network/DexscreenerApi.kt`,
`network/BirdeyeApi.kt`.

Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1029 → V5.9.1030.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1029 — Supervisor un-choke + Debug compile fit + lane re-enable (Feb 2026, CI ⏳)

Operator V5.9.1028b snapshot (build 5.0.2987, tag V5.9.1018 stale): bot
"frozen" — zero trades for 16 minutes despite bot-loop ticking healthily
at 12s cycles. Three problems, one surgical push.

**Q1 (the freeze — supervisor never harvests work)**

Every cycle: `SUPERVISOR_CHUNK_TIMEOUT loop=N chunk=32 active=32 budgetMs=2500`
followed by `POST_SUPERVISOR processed=0 deferred=96 total=96`. The V5.9.1025
harvest fix correctly walks each Deferred after timeout, but `active=32`
means NO worker has completed when the budget expires — there's nothing
to harvest. With 580 tokens in the watchlist and supervisor delivering 0
processed per cycle, only the SCAN_CB direct intake path (~6 evals/cycle)
fed FDG, and recent pump.fun spam (PUMP/OPAI/Veil ~$2K liq) fails the
quality floor. Net: bot looks dead.

Root cause: `chunkBudgetMs = min(perTokenTimeoutMs * 2L = 2400, remaining)
                              .coerceAtLeast(2_500L)`
With paper-mode `perTokenTimeoutMs = 1200`, the floor pins the budget at
2.5s. That's not enough for 32 parallel workers each running a real
`processTokenCycle` (V3 + safety + lane evals + a 300-700ms network call).

Fix: widen to `(perTokenTimeoutMs * 4L).coerceAtLeast(4_500L)` = 4.5s
floor (was 2.5s). 3 chunks × 4.5s = 13.5s fits inside `maxBatchMillis=15s`
paper deadline with margin.

Touched: BotService.kt L10218-L10221 (runSupervisorPhase chunkBudgetMs).

**Q2 (Debug compile JVM 64KB cap)**

V5.9.1028b Build APK ✅ but Runtime Smoke Test ❌:
  `e: Back-end (JVM) Internal error: Couldn't transform method node: botLoop`

The Debug compile (assembleDebug used by the smoke test) keeps Kotlin
coroutine state-machine debug info that the Release build strips, and
V5.9.1028's inline `rawCatastrophe + try/catch` for the AI-fluid
catastrophe threshold pushed botLoop's bytecode over the cap again.

Fix: extract to `private fun getCatastropheThreshold(paperMode): Double`
helper. Call site collapses from a 5-line try/catch to a single
INVOKESPECIAL (~10 bytes). Same behaviour as V5.9.1028.

Touched: BotService.kt L6144-L6156 (botLoop call site) + L7818 (new helper).

**Q3 (lane re-enable — option c)**

Operator confirmed "and c" — clear stale auto-disabled strategies. The
V5.9.806 telemetry auto-retires strategies with ≥50 trades AND mean PnL
≤ -5%; ANY retirements made BEFORE V5.9.1028's fluid-stop fix were based
on phantom losses from paper-mode slippage. Clean slate per start lets
SHITCOIN / TREASURY / PRESALE_SNIPE re-prove themselves on honest data.

Fix: call `StrategyTelemetry.clearDisabled()` at the top of every
`startBot()`. Idempotent — safe to call when nothing is disabled.
Emits `STRATEGY_TELEMETRY_DISABLED_CLEARED reason=fresh_start` forensic.

Touched: BotService.kt startBot() L2604.

Plus: bumped `PipelineHealthCollector.BUILD_TAG` V5.9.1018 → V5.9.1029
so future snapshots show the actual installed build.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1028 — Paper settle-in restored + AI-fluid STRICT_SL & catastrophe thresholds (Feb 2026, CI ⏳)

V5.9.1027b operator snapshot: every paper trade exits within 350-1000ms
of entry at STRICT_SL_-10 / RAPID_CATASTROPHE_STOP / CASHGEN_STOP_LOSS.
No token drops -10% in 700ms — this was a paper-mode simulation
artifact, not real behaviour.

Root cause: paperBuy (Executor.kt L6540) applies +12% slippage on entry
and paperSell (L9111) applies -18% slippage on exit, for tokens with
<$5k liquidity (every pump.fun launch). Round-trip tax is -26.8%
before ANY price movement → every fresh paper position is born at
-26.8% PnL and BOTH the strict SL (-10%) AND the catastrophe gate
(-25%) fire instantly. MOONSHOT / SHITCOIN / TREASURY get gutted in
under a second, learning data is corrupted with phantom losses, and
lanes get distrust-paused for "bleeding" they never actually did.

Operator mandate: "settle in period plus all the trader lanes all 9
and the tools are meant to have ai calculated hold times stops take
wins etc in a fluid state. the strict and rapid stops are still meant
to be a fluid learnt thing as well. everything is meant to be."

Three surgical fixes:

1. **Paper-mode settle-in for STRICT_SL** (Executor.kt ~L4040).
   In paper mode, suppress STRICT_SL for a per-lane settle-in window
   sourced from `FluidLearningAI.getFluidMinHoldMinutes(lane)`, with an
   absolute 30s floor so the slippage band has time to mean-revert.
   Live mode untouched — real slippage IS real cost.

2. **Paper-mode settle-in for RAPID_CATASTROPHE_STOP** (BotService.kt
   ~L6135). Same per-lane gate added as a new `when` arm BEFORE the
   `isCatastrophe` branch. Other stops (give-back, dynamic floor,
   trailing) continue evaluating during settle-in so genuine rugs
   still get caught.

3. **AI-fluid stop thresholds**. STRICT_SL's `hardFloor` and the
   catastrophe gate's threshold now both flow through
   `FluidLearningAI.getFluidStopLoss(modeStop)` — they lerp from a
   -15% bootstrap floor (capital protection while learning) to the
   trader's mature mode stop. Falls open to the original hardcoded
   value on any failure so the safety net is never lost.

Touched: `Executor.kt` STRICT_SL block (~L4040-4108), `BotService.kt`
catastrophe `when` block (~L6135-6200).

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1027 — Orphan bot-loop exit (kill duplicate post-rescue coroutines) (Feb 2026, CI ⏳)

V5.9.1026 cap landed (chunk=32 confirmed in operator snapshot) but the
new snapshot revealed something deeper: loop=17 fired SEVEN
SUPERVISOR_CHUNK_TIMEOUTs and TWO POST_SUPERVISOR events. Cycle-ms
pattern was strictly alternating short/long
(`[12282, 675, 12545, 80, 12800, 180, 12421, 806, 11973, 694]`) — proof
that TWO botLoop coroutines were running concurrently on the dedicated
single-thread dispatcher, alternating at every suspension.

Root cause: V5.9.1023 rescue uses `observedDeadJob?.cancel(...)` which
is cooperative. A corpse wedged in a non-cancellable JNI socket-read
keeps running. When it unwedges later it resumes its botLoop alongside
the replacement — two concurrent supervisors, two PRE/POST_SUPERVISOR
pairs per "loopCount", chunks scheduled twice → throughput halved and
results discarded twice.

Fix: at botLoop boot, capture `currentCoroutineContext()[Job]` as
`myJob`. At the top of every while iteration, compare against the
canonical `loopJob` field. If they differ, emit `BOTLOOP_ORPHAN_EXIT`
and `return`. The fresh replacement is the sole authority; corpses
yield at their next safe checkpoint.

Touched: BotService.kt around L7745 (botLoop entry) and L7807 (while
loop top).

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1026 — Cap supervisor parallelism 96→32 to escape IO-pool contention (Feb 2026, CI ✅✅)

V5.9.1025 harvest fix landed but operator snapshot still showed
processed=0 deferred=96 every cycle. The new ForensicLog message
"abandoning 96 straggler(s); harvesting completed" CONFIRMED the harvest
path ran — but `active=96` at every timeout meant NO worker completed
within the 2.5s chunk budget.

Root cause: 96 parallel async workers launched on `Dispatchers.IO`
contend for only 64 default IO threads + OkHttp's per-host connection
pool (~10/host). Many workers can't even START running within 2.5s.
V5.9.175 set this to 96 assuming a 50-100 token watchlist, but the
operator snapshot shows 1056 tokens (PumpPortal "egg" spam — 14×
intaken in 7 seconds, EGG 12×, Sharpton 9×).

Fix: cap maxParallel at 32 in memeBootstrap mode (was 96).
- 32 fits inside the IO thread pool
- 32 fits inside per-host OkHttp connection budgets
- Each worker now has resources to complete a real `processTokenCycle`
  (Birdeye + Helius + V3 + lane evals ≈ 1.5-2s) inside the 2.5s budget
- 96 tokens → 3 chunks of 32 ≈ 7.5s, well inside the 15s paper-mode batch deadline

Touched: BotService.kt L9495 — the bootstrap-tier when{} mapping.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1025 — Harvest completed supervisor work on chunk timeout (Feb 2026, CI ✅✅)

V5.9.1024 ApiBackoff worked beautifully — DexScreener went from 49% SR →
99% SR, API_BACKOFF_ARMED fired 4×, paid-tier credit burn ended. But the
operator V5.9.1024 snapshot still showed `processed=0 deferred=96` on
EVERY supervisor cycle, with the watchlist exploding to 720 tokens
NEVER getting re-evaluated.

Root cause: the supervisor chunk failure path discarded ALL 96 jobs'
work when ANY job ran past the 2.5s chunk budget. Specifically:

```kotlin
withTimeoutOrNull(chunkBudgetMs) { jobs.awaitAll() } ?: List(jobs.size) { false }
```

`awaitAll()` is all-or-nothing — if even ONE job wedges past the timeout,
it returns null and we mark ALL 96 as deferred. With 96 parallel
`processTokenCycle` calls (each doing V3 + FDG + lane eval + safety +
network), losing the whole chunk to one straggler is the default state.
Result: watchlist of 720 tokens never re-evaluated by supervisor (only
freshly-discovered tokens via SCAN_CB direct path got any attention).

Fix: harvest each job's completion state independently AFTER the bulk
timeout. Completed jobs contribute their `await()` result (counted as
`processed`). Cancelled jobs are deferred. Stragglers still active get
canceled and marked deferred. Even if 1 job wedges and 95 complete, we
now report `processed=95 deferred=1` instead of `processed=0 deferred=96`.

Touched: `BotService.kt` runSupervisorPhase chunk loop (~L10110).

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1024 — Reactive per-host backoff (ApiBackoff) on 4xx/5xx (Feb 2026, CI ✅✅)

V5.9.1023 fix worked — bot is alive (82 BOT_LOOP_TICK in 311s uptime, normal
phase cycling, zero RESCUE_LAUNCHING wedge). But operator V5.9.1023 snapshot
exposed the next blocker:

```
SUPERVISOR_CHUNK_TIMEOUT firing every cycle, processed=0 deferred=96
dexscreener sr= 49%  4xx=406    (paid-tier rate-limit storm)
groq        sr=  0%  4xx=13
TokenMetaCache hit rate: 33.0%
```

Every supervisor chunk launches 96 parallel processTokenCycle workers; each
hits DexScreener. With DS at 49% success the chunk's 2.5s budget expires
before any of the 96 finish. Result: watchlist of 314 tokens NEVER scored
by the supervisor (trades trickle through only via SCAN_CB direct intake).

Existing RateLimiter is PROACTIVE only — it counts our own requests in a
sliding window but does NOT react to actual 429/403 responses. We keep
hammering the rate-limited endpoint, burning paid credits.

**Fix — ApiBackoff (new file)**

New `engine/ApiBackoff.kt`:
- Per-host consecutive-failure counter + lockout timestamp.
- Backoff schedule: 5s → 15s → 30s → 60s → 120s → 300s cap (consecutive).
- 429 and 403 jump to ≥30s on first occurrence (paid-tier / auth refused
  are the strongest "stop calling me" signals).
- 2xx success resets the counter immediately.
- Forensic events: `API_BACKOFF_ARMED`, `API_BACKOFF_CLEARED`.

**Wire — HealthAwareHttp.kt**

All keyless REST calls already route through `HealthAwareHttp.execute()`.
One edit covers DexScreener, PumpFun, Birdeye REST, Jupiter, and any
future host:
- Before sending: short-circuit with synthetic 503 if locked out (callers
  already handle `!resp.isSuccessful` as null → no call-site changes).
- 2xx → `ApiBackoff.markSuccess(host)`.
- 4xx/5xx → `ApiBackoff.markFailure(host, code)`.

Touched: `engine/HealthAwareHttp.kt`, NEW `engine/ApiBackoff.kt`.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1023 — Dedicated bot-loop dispatcher + stale-price PnL corroboration (Feb 2026, CI ✅✅)

Operator V5.9.1022 snapshot showed the bot completely dead — phase wedged
in `RESCUE_LAUNCHING` for the entire 10-minute log window. Every 180s the
heartbeat fired `HEARTBEAT_RESCUE_IDLE_PHASE_TIMEOUT` → `performService
ScopeRescue` → `scope.launch(Dispatchers.IO) { botLoop() }` with
`newJobActive=true`. But botLoop's FIRST line — `markProgress("BOTLOOP_
BOOT")` — never ran. No `BOTLOOP_STARTED`, no `BOTLOOP_RESCUE_THREW`. The
coroutine was queued but never got CPU time. CryptoAltTrader and the
PumpPortal WS kept running fine because they live on independent scopes.

**Q1 (THE bug — bot completely dead)**

Root cause: `Dispatchers.IO` thread-pool starvation. The supervisor phase
launches up to 96 parallel OkHttp `.execute()` calls on Dispatchers.IO.
When Helius/Birdeye/DexScreener wedge in JNI socket reads, those threads
ignore `cancel()` (native code is uncancellable). Each 2-min rescue
cancels the corpse and launches a NEW botLoop on the SAME saturated
pool. After 2-3 rescues all 64 default IO threads are wedged and new
launches queue indefinitely.

Fix: dedicated OS thread via `Executors.newSingleThreadExecutor`
wrapped as a `CoroutineDispatcher`. The thread is daemon + named
`AATE-BotLoop-Dedicated` + priority NORM+1. It is exclusive to botLoop
dispatch (startBot AND rescue paths). Even if every Dispatchers.IO
thread is wedged in JNI, this thread is alive and ready to execute the
first `markProgress("BOTLOOP_BOOT")` within milliseconds.

Note: `Dispatchers.IO.limitedParallelism(1)` was the troubleshoot-agent's
initial suggestion but does NOT solve the problem — that view shares
the underlying IO scheduler workers, so when the scheduler is saturated
the limited view also has zero free threads.

Touched: BotService.kt L376 (field decl), L3211 (startBot launch),
L7502 (rescue launch).

**Q2 (stale-price phantom-rug nukes)**

Operator V5.9.1022 also reported "first 60 trades are instant death".
Snapshot showed `STALE_LIVE_PRICE_RUG_ESCAPE` firing on positions where
the API feed went dark for 90-180s — DexScreener at 48% HTTP success
rate, Birdeye/Helius hitting paid-tier limits. A dark feed alone is
NOT a rug.

Comment block at L5971-5987 already documented the intended TWO-condition
rule: `(a) price age threshold AND (b) PnL from last-known price is NOT
actively winning`. But the actual code only checked (a). Added the (b)
gate: if last-known PnL > 0 (we were green before the feed died), the
rug hypothesis is weaker than the API-throttle hypothesis — emit
`STALE_LIVE_PRICE_HOLD_WINNER` forensic and ride out the dark period.

Touched: BotService.kt around L5993.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1022 — Triage round 4: whale spam + catastrophe + double-sell + live-sol (Feb 2026, CI ✅ build, smoke pending)

Operator V5.9.1021 install (build 2976, latest green APK). Bot finally
trading after V5.9.1021 SUPERVISOR fix, BUT three severe new symptoms:

**Q3 (HIGHEST PRIORITY — direct credit-burn driver)**

Whale-tx WS firehose at BotService.kt:5778. Operator log dump showed 200+
'🐳 PUSH: whale tx X… (0 accounts)' events in 4 s — EVERY ONE with zero
matching accounts. Each event ran InsiderWalletTracker.scanForSignals()
which hits Birdeye + DexScreener + Helius. Direct paid-tier drain
(operator burned $300 AUD this way). Fix: early-return on
accounts.isEmpty().

**Q1 (rapid-catastrophe firing on noise)**

BotService.kt:6072 catastropheThreshold was -14% for all modes. Operator
saw RAPID_CATASTROPHE_STOP firing 30-53 s after BUY on pump.fun launches
(price quantization 8.3E-5 → 1E-4 = +20% instant + paper-mode 18%
simulated slippage = -14% reached by pure noise). 31-loss cold streak.
Fix: paper-mode → -25%; live keeps tight -14%.

**Q2 (double-sell race)**

Executor.kt:519 — same mint 4vRgJ7 sold twice within 118 ms via
RAPID_CATASTROPHE_STOP then CASHGEN_STOP_LOSS. Lock releases on
completion + getOrPut creates fresh AtomicBoolean; second sell 118 ms
later re-acquires cleanly. Fix: new lastPaperSellCompletedMs
ConcurrentHashMap + 2 s cooldown. acquirePaperSellLock checks cooldown
FIRST. releasePaperSellLock writes timestamp BEFORE removing the lock.

**Q4 (bonus — live sol semantic)**

Executor.kt:11643 — V5.9.1018c fixed paperSell's `sol = pos.costSol` to
proceeds. The LIVE sell at line 11643 had the SAME bug. Fixed: sol =
solBack (Jupiter actual proceeds). Caught before going live.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1021 — extract runSupervisorPhase to free botLoop 64KB cap (Feb 2026, CI ✅✅)

V5.9.1020's inline withTimeoutOrNull + hard-timeout-log pushed botLoop
OVER the JVM 64 KB method-size cap (RELEASE compile: 'Method code too
large'). Extracted the entire V5.9.1020 SUPERVISOR body into private
suspend fun runSupervisorPhase(...). Returns SupervisorPhaseResult
(processed, deferred, hardTimedOut). botLoop now contains a 20-line call
site. Zero behaviour change vs V5.9.1020; only bytecode layout differs.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1020 — kill lying progressTicker + hard 20s SUPERVISOR outer timeout (Feb 2026, RELEASE compile FAILED → V5.9.1021)

Operator V5.9.1018c+V5.9.1019 snapshot showed botLoop stuck in
phase=SUPERVISOR for 652 s straight — no cycle in 10+ min, no trades.
ANR was solved but bot wedged.

Root cause (triage agent):
  1. progressTicker fired markProgress("SUPERVISOR") every 10 s
     UNCONDITIONALLY → freeze detector was LIED TO; heartbeat rescue
     never triggered.
  2. supervisorAbort elapsed-check lived INSIDE the chunk forEach. When
     the FIRST chunk hangs (Helius RPC dead, OkHttp blocks in JNI
     socket-read — withTimeoutOrNull cannot interrupt native), the
     forEach never advances → abort check never re-runs.
  3. V5.9.1012 detached workers + V5.9.1014 non-supervisorScope: each
     necessary but insufficient. No outer time fence stopped the forEach.

Fix: HARD outer time fence (withTimeoutOrNull maxBatchMillis+5s),
killed progressTicker, SUPERVISOR_HARD_TIMEOUT forensic event.
Behaviour-equivalent re-landed in V5.9.1021.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1019 — Structural: async universal SL sweep + deferred MainActivity UI (Feb 2026, CI ✅ build)

Operator V5.9.1018(c) snapshot symptoms:
  - Bot loop cycles oscillating [237, 8, 11531, 179, 18790, 54, 22674, 14, 44108, 575]ms
  - LOOP_HEARTBEAT_ALARM sinceLastTickSec=142 s
  - MainActivity.onCreate dominant ANR (11/20 samples) — 757-2154ms sub-frames
  - "Going into Pipeline still freezes the bot"
  - User mandate: "non-patchwork real fixes only"

**Fix A — async-ify runUniversalSlSafetyNetSweep**

Per-cycle universal SL safety-net sweep at end of botLoop was running
SYNCHRONOUSLY. For each open position it can invoke executor.requestSell()
→ paperSell() → full learning fanout. 5-10 open positions × ~5s each =
30-50 s sync work — matches the 44s max cycle exactly. Next cycle finds
positions closed → 8 ms — matches the alternation.

New `slSafetyNetInFlight: AtomicBoolean` + `launchUniversalSlSweepAsync()`
helper modelled exactly on V5.9.1010's `launchExitSweepAsync`: coroutine
worker on Dispatchers.IO, single-flight gate, 3 s hard watchdog releasing
the gate even if paperSell IO is blocked. New events:
UNIVERSAL_SL_SWEEP_START/_DONE/_SKIPPED/_TIMEOUT/_LATE_DONE.

**Fix B — Defer heavy MainActivity UI setup past first frame**

Extracted from sync onCreate path:
  - setupChartControls (1321 ms ANR — setOnClickListener → ImeFocusController)
  - setupApiKeyHelpLinks
  - setupChart (2154 ms — chart Matrix init via Cleaner.create)
  - setupSettings

Now run inside the existing `window.decorView.post {}` block after
bindViews() (which stays sync because it does cheap findViewById and
downstream code reads `chart`/button fields). decorView.post yields one
vsync (~16 ms) so the initial frame draws first, then heavy work runs
while user is still in the splash → main transition.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1018c — Triage round 3: PNL display bug + GeminiCopilot ANR (Feb 2026, CI ✅✅)

Operator V5.9.1017 snapshot complaints:
  • "buys are down 98% in the first seconds for no reason"
  • "ANR errors like gang signs" (15.8% stall, 33 s maxFrameGap)
  • Top ANR sites: GeminiCopilot.resetAllProviderState (14×) + MainActivity.onCreate (15×)

**Fix 1 — PaperSell sol-field semantic mismatch (the "98 % loss" lie)**

`Executor.paperSell` line 9131 was building the SELL Trade with
`sol = pos.costSol` (the *cost basis*, including all top-ups). Every
other SELL constructor in the file uses *gross proceeds*. Operator's
buRrYi case:
  - `BUY paper buRrYi sol=0.685` (initial entry, only one in 30-row window)
  - `SELL paper buRrYi sol=1.370 pnl=-1.115 reason=CASHGEN_STOP_LOSS`
The 1.370 is the post-top-up cost basis (V5.9.808 scale-in landed 2 top-ups
earlier than the visible window). Real proceeds were ~0.255 SOL. The SELL
*looked* like a 98 % rug only because the journal `sol` column was lying.

Patch: `sol = pos.costSol` → `sol = value` (gross proceeds, slippage- and
liquidity-capped). bumpLifetimeFor / WR classification only use pnlSol +
pnlPct → no impact on W-L counters. CanonicalLearning.exitSol is now
fed correct proceeds → learning signal corrected too.

**Fix 2 — GeminiCopilot @Synchronized + Thread.sleep deadlock-ish ANR**

`enforceCallSpacing` (line 1160) was `@Synchronized` AND called
`Thread.sleep(MIN_CALL_INTERVAL_MS − elapsed)` *while holding the
GeminiCopilot singleton monitor*. Any other @Synchronized method on the
singleton (notably `resetAllProviderState` called by BotService.startBot)
queued behind the sleep. ANR sampler caught the main thread frozen
inside resetAllProviderState 14 × per session — that was monitor
contention, not the function itself.

Removed `@Synchronized` from both methods:
  - `lastCallTimeByProvider` / `rateLimitedUntilByProvider` /
    `consecutive429ByProvider` are all ConcurrentHashMap.
  - `lastBlipDiagnostic` is @Volatile.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1018b — CI compile fix (Feb 2026, CI ✅✅)

V5.9.1018 build failed with:
  e: PipelineHealthActivity.kt:191:42 Expecting ','
  e: PipelineHealthActivity.kt:192:3  Expecting ')'

Cause: splitDumpIntoSections() wrote a literal newline INSIDE single-quote
char delimiters:
    current.append(line).append('
    ')

Fix: `'\n'` escape.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.999b — Bounded RPC + SP-prewarm (Feb 2026, CI ✅)

Operator V5.9.998: bot loop dies after 3 ticks + 33 ANR storm.

Root cause:
  `SolanaWallet.getTokenAccountsWithDecimals()` is a synchronous JSON-RPC
  with 3 × 2-program internal retries + 300/600 ms backoffs — worst case
  ~150 s per call. `botLoop()` is a single suspend coroutine on
  Dispatchers.IO. The 60 s pendingVerify watchdog (BotService.kt:7904)
  called this RPC synchronously, wedging the whole loop. The method body
  is at the JVM 64 KB cap so wrapping the loop body in `withTimeoutOrNull`
  isn't possible.

Fix:
  1. New `SolanaWallet.getTokenAccountsWithDecimalsBounded(timeoutMs)`
     — wraps the parent on a dedicated daemon executor (not IO, not
     ForkJoinPool.commonPool) with hard `Future.get` ceiling. On timeout
     returns emptyMap, hitting the V5.9.467 RPC-EMPTY rescue path.
  2. Bulk renamed all 48 sync sites across Executor, BotService,
     UniversalBridgeEngine, AntiChokeManager, all sell/execution
     reconcilers, MarketsLiveExecutor, CryptoUniverseExecutor,
     TokenLifecycleTracker, PositionWalletReconciler, etc.
  3. `CurrencyManager.prefs` is now `by lazy` + AATEApp.onCreate kicks
     a daemon SP-prewarm thread that calls `.all` on the 5 hot SP files
     so `awaitLoadedLocked()` is done off-main before any Activity reads.

═══════════════════════════════════════════════════════════════════════════════

## Earlier sessions (pre-fork)

See PRD.md (V5.9.671–V5.9.680b history) and the git log
(V5.9.807–V5.9.998 was authored by previous agent across multiple commits;
V5.9.1000–V5.9.1018 was authored externally between sessions).

Notable post-V5.9.810 milestones:
  - V5.9.999  — initial bot-loop-death fix (ML training + Killswitch IO wrap)
  - V5.9.1000 — pilot split of BotService into BotServiceLifecycleExt.kt
  - V5.9.1001-1003 — intake/scanner/supervisor extraction (broken)
  - V5.9.1004 — REVERT of the broken extraction, keep 3 real fixes
  - V5.9.1005 — soften pre-FDG V3 skip gates (FDG=0 regression)
  - V5.9.1006 — remove heartbeat redeadlock self-stop
  - V5.9.1007 — fix supervisorScope ticker deadlock (3-loop stop)
  - V5.9.1008 — harden supervisor ticker cleanup with try/finally
  - V5.9.1009 — async single-flight exit sweep; unblock POST_SUPERVISOR
  - V5.9.1010 — hard-timeout exit sweep gate + paperSell breadcrumbs
  - V5.9.1011 — fast paperSell journal async + main-thread stats guard
  - V5.9.1012 — DETACH supervisor token workers (stuck RPC cannot pin loop)
  - V5.9.1013 — remove MainActivity first-frame black-screen blockers
  - V5.9.1014 — remove structured supervisor wrapper hostage path
  - V5.9.1015 — lifecycle autosave must not restart/stop bot
  - V5.9.1016 — report navigation must not autosave or stop bot
  - V5.9.1017 — cap dashboard renders so UI cannot starve bot cycles
  - V5.9.1018 — full Pipeline report with sectioned rendering (broke CI char-literal)

### V5.9.1567 — FDG mode contamination + SafetyChecker live parity + Gecko headers (Feb 2026)
Three surgical fixes that finally unblock LIVE execution and lift the
GeckoTerminal hit rate. CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both
GREEN on commit 5868295ba.

(a) FinalDecisionGate.evaluate() — FDG MODE CONTAMINATION FIX.
    The BotConfig passed in could carry a stale paperMode flag (same
    regression previously patched only inside ToxicMode circuit breaker
    @ V5.9.1119). ~40 downstream config.paperMode reads inherited the
    stale value and routed LIVE trades through PAPER branches. Fix:
    read RuntimeModeAuthority.isPaper() once at the top of evaluate()
    and shadow the local `config` with `.copy(paperMode = authority)`
    via a local `configIn` indirection. Every downstream config.paperMode
    read transparently picks up the authoritative mode. config.paperMode
    is fallback only.

(b) TokenSafetyChecker — DOCTRINE OF PARITY (Live = Paper Twin).
    * isPaperMode now reads RuntimeModeAuthority.isPaper() first
      (cfg().paperMode fallback), same pattern as (a).
    * Removed the V5.9.1523 "controlled live band" penalty stack:
      28/20/12-point soft penalty between $150–$1200 and +6 penalty
      between $1200–$2000 — live-only. Live size now mirrors paper.
      Tape proved this was the dominant blocker of the fresh pump.fun
      graduation universe ($1.2–2.0K liquidity).
    * Kept genuine physical-safety constraints unchanged:
      MIN_EXECUTABLE_LIQ_USD = $150 hard block (below this an exit
      cannot route at all) and UNKNOWN-liquidity hard block. Standard
      mode-agnostic safety branches above (LP lock, rugcheck, holder
      concentration) untouched.

(c) GeckoTerminal headers.
    Plain `Accept: application/json` held the v2 endpoint at ~47%
    success rate. v2 prefers the version-pinned Accept header.
    * PriceResolverFallback.fetchGeckoTerminalPrice: now sets
      `Accept: application/json;version=20230302` + real desktop UA +
      Accept-Language (was missing both; some Android-default UAs get
      403/406'd).
    * SolanaMarketScanner.getGecko: passes the versioned Accept via
      extraHeaders on the shared get() path so trending / new_pools /
      pools / dexes calls all get the v2-correct header without
      disturbing other hosts.

No new gates added. No surrounding cleanup. Doctrine: Live === Paper
unless a physical constraint genuinely blocks execution.



### V5.0.3676 — TUNING ONLY RECOVERY PATCH, phase 1 (Feb 2026)
Operator runtime tuning patch responding to the V5.0.3676 failure signature
(scannerActive=false / LANE_FANOUT_EXPLOSION / supervisor LEASE_FORCE_RELEASED
storm / forcedOpen=41 / 0 paper-sell counters). Phase 1 lands the 4
highest-leverage tunings. Phase 2 (scanner partial-active, slot reconcile
cadence, paper-sell telemetry, lane fanout dedupe, UI/ANR) is queued for
the next push. CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on
commit 5e26086ac.

§2 Supervisor anti-thrash — BotService.kt
  • SUPERVISOR_MAX_INFLIGHT  96 → 24 (spec normalCap=24). 96 was routing
    candidates into a 24-worker pressure window → 72 guaranteed skips/cycle
    feeding the LEASE_FORCE_RELEASED storm.
  • SUPERVISOR_WORKER_TIMEOUT_MS 8000 → 9000 (spec workerTtlMs=9000) —
    one extra second of lease so per-token p95 completes inside the wall.
  • SUPERVISOR_TIMEOUT_COOLDOWN_MS 45000 → 20000 (spec
    cooldownAfterTimeoutMs=20000) — chronic-mint quarantine halved.

§2 SupervisorAdmissionPlanner.kt
  Pressure-band targets rewritten to spec:
      healthy            → maxCap (unchanged on clean runtime)
      live_cap_near_full → min(maxCap, 24)
      live_cap_saturated → min(maxCap, 16)
      moderate_timeout   → min(maxCap, 24)
      heavy_timeout      → min(maxCap, 12)
      severe_timeout     → min(maxCap,  6)
  No more 'maxOf(live * 2, 24)' inflation under pressure.

§7 Entry / FDG tuning — FinalDecisionGate.kt
  LOW_CONFIDENCE in PAPER mode → SIZE PENALTY (dust probe), not hard block:
      dustMult: conf<5 → 0.20, conf<12 → 0.30, else → 0.45
  LIVE mode still hard-blocks below the confidence floor. Route/liquidity
  hard safety (TokenSafetyChecker) is unchanged.

§8 API / backoff tuning — FinalDecisionGate.kt + ApiHealthMonitor.kt
  Groq narrative is now health-gated: when Groq is in ApiBackoff lockout
  or rolling success rate < 50%, FDG skips the network call entirely and
  proceeds with narrativeAdjustment=0 (neutral). Spec §8 verbatim.
  Added ApiHealthMonitor.successRate(host) convenience helper (fail-open).

Mandatory catastrophic safety intact:
  Mint authority / freeze authority / LP lock / rugcheck / holder
  concentration / liquidity hard floors / LIVE confidence floor /
  Zero-confidence LIVE shadow-block all unchanged.

Phase 2 queue (next push):
  §1 Scanner partial-active recovery state.
  §3 Lane fanout cap + cross-lane dedupe.
  §4 Slot/close ledger 30s reconcile + emergency forcedOpen cleanup.
  §5 UI/ANR — MainActivity onCreate audit + render throttling.
  §6 Paper sell telemetry counter (TradeHistoryStore-backed).

═══════════════════════════════════════════════════════════════════════════
V5.0.3681 — DAILY-LOSS CB UNCHOKE + SPARSE-LAYER RELIEF + WATCHDOG COOLDOWN
═══════════════════════════════════════════════════════════════════════════
Date: 2026-06-14
Status: ✅ CI green (Build + Runtime Smoke Test both passed)

P0 — SecurityGuard.recordTrade paper bypass
  Paper trades NO LONGER accumulate into dailyLossSol / consecutiveLosses.
  Doctrine of Parity (mirrors V5.0.3679 treasury-drain fix). Authoritative
  paper-ness from trade.mode (not cfg.paperMode which can drift mid-trade).
  Live trading is now unblocked: checkBuy() sees only real live PnL when
  computing the 20% daily-loss halt threshold.

P1 — V3 score-floor sparse-layer relief
  FluidLearningAI.getSparseLayerRelief() returns ramped score-floor reduction
  (0-12 pts) based on % of AI layers with <20 trades. DecisionEngine subtracts
  it from effectiveMinScore. Aggressive hard floors UNCHANGED — quality at
  the top end preserved. Breaks the can't-trade-can't-learn deadlock.

P2 — Scanner watchdog restart cooldown
  BotService.runScannerHeartbeat now enforces SCANNER_WATCHDOG_RESTART_COOLDOWN_MS
  (30s) between auto bootMemeScanner calls. Manual operator restarts bypass.

═══════════════════════════════════════════════════════════════════════════
V5.0.3682 — RUNTIME GENERATION GUARD + MEME-ONLY AUTHORITY + SELL AUTHORITY
═══════════════════════════════════════════════════════════════════════════
Date: 2026-06-14
Status: ✅ Build green; Runtime Smoke Test in progress

DEEP OPERATOR AUDIT — Source-of-truth fixes (no thresholds touched).

P0 — Scanner generation guard (BotService.kt × 2 sites)
  bootMemeScanner + startBot scanner now capture currentGeneration() at
  construction; onTokenFound checks against the live generation + runtime
  state on every fire. Stale-scanner callbacks return silently.
  No more SCANNER_CALLBACK_FIRE / INTAKE_BLOCKED_RUNTIME_STOPPED spam.

P0 — RUNTIME_AUTH_SNAPSHOT forensic
  admitProtectedMemeIntake INTAKE_BLOCKED branch now emits the full
  runtime authority surface (gen/state/loop/scanner/enabledTraders).

P0 — Sell amount authority (Executor.kt + SellAmountAuthority.kt)
  • LIVE_BUY_LANDED now persists (mint, rawAmount, decimals, buySig)
    into SellAmountAuthority.recordTxParseBalance.
  • New canBroadcastLiveOrEmergency() allows FRESH_TX_PARSE for
    catastrophic exits (strict_sl / hard_floor / rug / shutdown /
    liquidate / emergency / manual_emergency) within 90s of buy when
    requested amount ≤ recorded amount.

P0 — Stop sell spam on closed/zero tracker rows
  liveSell now consults HostWalletTokenTracker.getEntry FIRST.
  CLOSED + ui<=0 + wallet ZERO    → ALREADY_CLOSED + forensic
                                     SELL_ABORT_ALREADY_CLOSED_RECONCILED.
  CLOSED + ui<=0 + wallet UNKNOWN → reconcileNow + FAILED_RETRYABLE +
                                     forensic SELL_PAUSED_TRACKER_CLOSED_WALLET_UNKNOWN.
  No repeated LIVE SELL START for already-closed mints.

P1 — Restored meme-only trader authority
  EnabledTraderAuthority.isMemeLiveOnly() now reads published set (truly
  meme-only when {MEME} alone). BotService.startBot publish now COMPUTES
  the enabled set from cfg:
    • tradingMode == 0 + memeOn → ONLY MEME
    • Mixed/full → MEME + (CRYPTO_ALT/MARKETS/PERPS) per toggles +
                   MARKET_LANES_QUARANTINED. CYCLIC/SHADOW_PAPER opt-in.


═══════════════════════════════════════════════════════════════════════════
V5.0.3683 — ACCOUNTING LEDGER REPAIR (no strategy changes)
═══════════════════════════════════════════════════════════════════════════
Date: 2026-06-14
Status: ✅ Build + Runtime Smoke Test both PASSED

DEEP OPERATOR AUDIT — AATE_All_Trades CSV produced impossible numbers
($333M gains, 0 Net Gain on partials, 1000x sell quantities). Fixed at
the SOURCE — three accounting bugs.

1) trade.sol semantics standardised in Executor.kt
   The live full-SELL leg (line 4193) stored solBack (PROCEEDS).
   Every other partial/profit-lock site stored allocated COST BASIS.
   Standardised to COST BASIS at all sell sites:
     • SELL (live)              4193  solBack         → pos.costSol*sellFraction
     • PARTIAL_SELL capital_rec 3515  sellSol         → pos.costSol*sellFraction
     • PARTIAL_SELL profit_lock 3575  sellSol         → pos.costSol*sellFraction
   Canonical equation now holds at every emit site:
     proceedsSol = trade.sol + trade.pnlSol
     gainLossSol = trade.pnlSol
     netGainSol  = trade.pnlSol - trade.feeSol

2) Paper partial-sell paths populate feeSol / netPnlSol
   Lines 3515, 3575, 4781, 9724 had been calling Trade(...) without
   feeSol/netPnlSol → exporter wrote Net Gain = 0 on 1078/1078 partials
   despite multi-SOL realized gains. Now:
     feeSol  = costBasisAllocated * MEME_TRADING_FEE_PERCENT (0.5%)
     netPnl  = pnlSol - feeSol

3) Partial percentage display > 100% cap
   All four 'partial_NNpct' label sites wrapped with
   .toInt().coerceAtMost(100) — display-only cap, sizing unchanged.

CSV EXPORT REWRITE (TradeJournal.kt)
  • exportCsv() now defaults to LIVE-only per operator P0 directive.
    exportCombinedDiagnosticCsv() retained for non-tax diagnostic.
  • New deriveRowAccounting() is the SINGLE canonical ledger calculator.
    Footer totals derived strictly from emitted rows that pass
    invariants — single source of truth.
  • Hard invariants per operator spec written into 'Invariants' column:
      BUY  : proceeds==0 ∧ gainLoss==0 ∧ netGain==0
      SELL : |gainLoss − (proceeds−cost)| ≤ $0.01
             |netGain − (gainLoss − fee)|  ≤ $0.01
             ¬ (proceeds==0 ∧ gainLoss>0)
  • rowType column added (TRADE / SUMMARY) so footer/summary lines
    cannot be misread as malformed trade rows.
  • LIVE Net Realized / PAPER Simulated Net broken out separately —
    paper rows can never pollute tax math.


═══════════════════════════════════════════════════════════════════════════
V5.0.4097 — V5.0.4105 — SUPER AGI WAVES (lane starvation kill + aggressive
compound + sell-failure P0 patch + TOKEN_MAP unchoke)
                                                              2026-06-24
═══════════════════════════════════════════════════════════════════════════

V5.0.4097  KILL LANE STARVATION
  • new CoinGeckoSolanaTopMcap feeder (top-100 mcap Solana tokens, 30min cache)
  • TokenSource.COINGECKO_ESTABLISHED + scanCoinGeckoEstablished
  • new ScannerSourceBrain — per-source intake AGI (BOOTSTRAP→AUTHORITATIVE)
  • starvation detector: BLUECHIP+DIP_HUNTER+QUALITY ≤5 → 60s 1.5x boost
  • intakeMultiplier shapes scanner emit() score multiplicatively

V5.0.4098  AGGRESSIVE COMPOUND sizing floor (Wave 1)
  • LiveSizingProfile — central config: floors 0.025/0.040/0.070/0.120 SOL,
    walletPct 2/4/7.5%, max-initial 10%, gas reserve 0.075
  • SmartSizer.calculate end-stage compound floor lift (LIVE only, paper
    untouched). Conviction tiers from (entryScore, setupQuality).

V5.0.4099  Gate→Size soft-shape + lane-aware compound (Wave 2)
  • Convert FDG C_GRADE_CONFIDENCE_FLOOR_27% from HARD_KILL → soft ×0.65
  • Convert FDG CIRCUIT_BREAKER (non-emergency) → soft ×0.55
  • Lane-aware floors: MOONSHOT/STANDARD/WALLET_RECOVERED/established
  • Per-mint thread-local pendingShape consumed by SmartSizer

V5.0.4100  Daily realized-PnL ramp + buy idempotency (Wave 3)
  • LiveSizingProfile.recordRealizedClose wired into LIVE close path
    (paper closes excluded). RESTORED-basis closures excluded from ramp.
  • Daily mults: ≥10% → 1.15x, ≥25% → 1.30x, ≥50% → 1.50x. UTC reset.
  • POSITION_OPENED_DURING_CONFIRMATION_WAIT reclassified as
    BUY_OK_LATE_CONFIRMED (idempotent, never trains as fail).

V5.0.4101  P0 Wave A — strict ExitIntentClassifier
  • new sell/ExitIntentClassifier — FULL_EXIT / PARTIAL_PROFIT / etc.
  • Replace 7-keyword skip-blob in tryPumpPortalSell. EXIT-RESCUE,
    ORPHAN-SWEEP, RECOVERED, STRICT_SL, RUG_EXIT all reach PumpPortal again.

V5.0.4102  P0 Wave B — provider circuit breakers
  • new sell/ExitProviderHealth — Jupiter sell-side 503 cooldown (90s after
    2x in 30s, probe-once on expiry). Pump direct 0x1788 per-mint
    suppression (60s after 2nd strike, route-cache invalidation).
  • Wired into Jupiter quote ladder (skip when degraded) + 0x1788 sim
    failure + Pump rescue branch (skip if suppressed).

V5.0.4103  P0 Wave C — punch-through expansion + severity table
  • new sell/SellIntentSeverity — 100/90/80/70/60/50/40/30/10 tiers.
  • blockIfSellInFlight punch-through reasons extended:
    + STRICT_SL, HARD_STOP, STOP_LOSS, EXIT-RESCUE, EXIT_RESCUE,
      EXIT-DRAIN-RESCUE, RAPID_CATASTROPHE, LIQUIDITY_REMOVED,
      WALLET_DRAIN, HONEYPOT, DEV_DUMP, DEV_SELL.
  • Punch-through also calls CloseLease.raiseIntent so workers read
    the new emergency reason.

V5.0.4104  P0 Wave D — RecoveredHoldGuard 15-min hold-grace
  • new RecoveredHoldGuard — operator §4+§13 spec.
  • markRecovered(mint) wired into WalletReconciler.recoverOrphanPosition.
  • shouldSuppress gate at top of blockIfSellInFlight — non-emergency
    sells held during the 15-min window. Emergency reasons override.

V5.0.4105  P0 Wave E — TOKEN_MAP_INCOMPLETE unchoke
  • Live forensic showed FDG block 24/25 = 96% on TOKEN_MAP_INCOMPLETE.
  • Was: required BOTH pairAddress AND poolAddress (broke pump.fun
    pre-migration tokens which only have bonding curve route).
  • Now: hard-block ONLY when no usable route at all. Accepts
    pairAddress OR poolAddress OR (pumpFunBondingCurveAddress +
    pumpFunExecutable + !migratedOrGraduated).

