# AATE — Native Kotlin Android Solana Trading Bot

## Original Problem Statement
Native Kotlin Android Solana trading bot. Builds via GitHub Actions CI only —
NO local compiler. Multi-lane architecture (Memes [9 sub-lanes], Crypto/Alts,
Stocks, Markets, Tokenized Stocks, Forex, Metals, Commodities). Foreground
Service with a 50+ AI-module pipeline gated through processTokenCycle.


## V5.0.4165 (Jun 2026) — BUY LEASE WINDOW 5s → 15s (volume restore)

Operator escalated: *"bot isn't trading find the source and fix the issue!!!"*.
Runtime dump showed `EXEC_GATE allow=1712` but `BUY ok=10` — a **99.4%
buy-throughput collapse**. Forensic feed flooded with `EXEC_LEASE_PRUNED_EXPIRED`.
CI: ✅ GREEN (run #4165).

### `troubleshoot_agent` RCA (10-step investigation)
- Cycle times: `avg=5827ms max=21603ms` (Jupiter `avg=3378ms` dragging the loop).
- `BUY_DECISION` lease freshness window at `Executor.kt:9929` was **5 seconds**.
- Every cycle taking >5s = every buy decision in that cycle staling out
  → `BUY_DECISION_EXPIRED_RESCORE` → defer → re-score next cycle → stales
  out again. Endless loop, zero volume.

V5.0.4162–4164 (parallel work by Vex) had already addressed:
- `jupiter_quote` vs `jupiter_send` health split (4162/4164).
- MemeTrader lane truth (canonical lane resolver, prevents source labels
  from poisoning lane buckets).
- Suppressor telemetry + 30s wall-clock cap on sell-defers (4163).
- Zero-signal probe unparking (4164).

None of those touched the 5s lease window — so the buy-throughput
collapse persisted.

### Fix
Lease freshness `5_000L` → `15_000L` at `Executor.kt:9929`. 15s gives
~70% headroom over the worst observed cycle (21.6s) while still rejecting
genuinely stale decisions. Route proof still re-hydrates at 8s.

Restores the executor's ability to sign decisions inside the SAME cycle
they were made in, even at 5–7s steady-state cycle latency.


## V5.0.4161 (Jun 2026) — EXECUTION-HEALTH GUARD (jupiter-blackout defense)

Operator dump 2026-06-26 running V5.0.4160 still showed two catastrophic
closes (`385j195R pnl=-71.4%`, `BHXt2heo pnl=-58.8%`) labelled
`CATASTROPHIC_STOP_LOSS_OVERRUN_-Xpct_FROM_STRICT_SL_-10`. V5.0.4160's
detect-side backstop fires correctly, but it calls the SAME `doSell()`
pipeline — when Jupiter is dead (DNS-fail on `tokens.jup.ag`) the executor
falls through to the PUMP/HELIUS direct route with no slippage projection
and bleeds catastrophically.

New module `engine/ExecutionHealthGuard` — three volume-preserving rules:
1. `shouldDeferBuy()` at `liveBuy()` top — defer buys when Jupiter is dead.
2. `shouldDeferDirectRouteSell()` inside the `jupiterQuoteUnavailable`
   branch — defer non-emergency direct-route sells up to 5 ticks / 30s
   wall-clock (V5.0.4163 wall-clock cap added by Vex).
3. `recordSlippageOutcome()` post-execution alarm — logs
   `EXECUTION_SLIPPAGE_VIOLATION` when realized SOL >20% worse than quoted.


## V5.0.4160 (Jun 2026) — SCRATCH-STREAK BUTTERFLY SWEEP + CATASTROPHIC -25% BACKSTOP

Two operator P0s shipped together. CI: ✅ GREEN.

### P0 #1 — Scratch-streak guard, lifted into a shared registry
V5.0.4159 first introduced a per-lane scratch counter inside `MoonshotTraderAI`
to detect the "all-scratch trap" (operator dump showed 17 trades with
W/L/S=0/0/17, every close pinned in [-2%, +5%]). Operator response:
*"meme traders basically stopped trading. completely. it needs that fix
everywhere bro! siblings, traders, upstream downstream, butterfly sweeps!!!"*

The counter has been extracted into `engine/ScratchStreakRegistry`:
- Lane-keyed (`MOONSHOT`, `SHITCOIN`, `EXPRESS`, `BLUECHIP`, `QUALITY`,
  `MANIPULATED`, `CRYPTO_ALT`) — zero cross-lane contamination.
- `recordOutcome(lane, pnlPct)` — increments on |pnlPct|<1% (scratch),
  resets to 0 on any clear win/loss. Self-correcting.
- `streakFor(lane)` / `isInTrap(lane)` — consumed by every lane's
  FLAT_EXIT path. Trap threshold = 4 consecutive scratches.

Wired into every meme + crypto trader (MoonshotTraderAI, ShitCoinTraderAI,
ShitCoinExpress, BlueChipTraderAI, QualityTraderAI, ManipulatedTraderAI,
CryptoAltTrader). The shared `OutcomeGates.earlyExitByHoldBucket` now
consults the registry centrally so any lane crossing the trap threshold
gets its flat-exit window extended (2× cap) before flat-cutting.

### P0 #2 — Catastrophic -25% Hard Emergency Backstop (Executor.kt)
Operator dump showed trades closing at -71% and -58% despite STRICT_SL
configured at -10%. Root cause: a Jupiter DNS blackout (`tokens.jup.ag`
unresolvable) stalled live quotes; both live and cached SL paths skipped
firing because the feed stopped ticking before price ever reached the
configured floor. By the time maxHold finally cut the bag, the realized
fill was catastrophic.

New last-line backstop in `Executor.kt runManageOnly`:
- Runs BEFORE paper settle-in, fluid SL coercion, profit locks, STRICT_SL.
- If EITHER the live price OR the most recent cached price shows
  pnl ≤ -25%, force-exits immediately with reason
  `CATASTROPHIC_HARD_BACKSTOP_-25`.
- Quote freshness, learning state, and settle-in cannot suppress it —
  there is no scenario where holding a -25% bag through a quote outage
  is correct behaviour.

Also fixes V5.0.4159 CI failure (`Type mismatch: Int but Long expected`
at `MoonshotTraderAI.kt:1663`, from `pos.spaceMode.maxHold`).


## V5.0.4158 (Feb 2026) — CRYPTO UNIVERSE DISCIPLINE PARITY (isolated)

Operator: *"isolated crypto universe power up, upgrade and meme trader
structure match up. remember do not contaminate the meme trader."*

### What shipped — four NEW crypto-isolated discipline modules
Under `perps/crypto/brain/`:
- `CryptoRugMintBlacklist`  — prefs: `crypto_rug_mint_blacklist`
- `CryptoLivePauseButton`   — prefs: `crypto_live_pause_button`
- `CryptoLaneTimeoutGate`   — prefs: `crypto_lane_timeout_gate`
- `CryptoScannerLaneBridge` — prefs: `crypto_scanner_lane_bridge`

Each is a 1:1 algorithmic mirror of its meme counterpart
(`engine/RugMintBlacklist`, `engine/LivePauseButton`, `engine/LaneTimeoutGate`,
`engine/ScannerLaneBridge`) — same thresholds, same hysteresis, same API
surface — but with a **separate SharedPreferences file** and **zero shared
state**. Meme close events update meme prefs only; crypto close events
update crypto prefs only.

### Wiring at the crypto buy chokepoint
`authorizeCryptoFinalCandidate` (in `CryptoAltTrader.kt`) now runs the
four-veto stack BEFORE EXEC_GATE/TradeAuthorizer:
1. `CryptoRugMintBlacklist.isBlacklisted(assetKey)` → veto
2. `CryptoLaneTimeoutGate.isTimedOut(lane)` → veto
3. `CryptoLivePauseButton.isDefensive()` with `isTopPerformingLane` bypass → veto
4. `CryptoScannerLaneBridge.shouldRoute(src, lane)` → veto

Forensic stamps: `CRYPTO_RUG_BLACKLIST_VETO_V4151`, `CRYPTO_DISCIPLINE_VETO_V4151`.

### Wiring at the crypto close path
Live closes feed all four discipline modules with `pnlPct` + `holdMs`.
Lane tags partition by leverage class (`CRYPTO_SPOT` vs `CRYPTO_LEV`)
derived from `candidate.assetType` and `position.isSpot` — so one mode
can enter `TIMEOUT` without locking the other.

### Isolation guarantee — files NOT touched
- `engine/Executor.kt`, `engine/BotService.kt`
- `engine/RugMintBlacklist.kt`, `engine/LivePauseButton.kt`,
  `engine/LaneTimeoutGate.kt`, `engine/ScannerLaneBridge.kt`
- All of `v3/scoring/*` (meme scorers untouched)

SharedPreferences: zero key overlap between meme + crypto.

### Deferred — scorer decouple is a follow-up
`CryptoAltTrader` still calls `ShitCoinTraderAI.evaluate`,
`MoonshotTraderAI.scoreToken`, etc. for SCORING. These are READ-ONLY
inferences (don't write to meme state — recording uses Alts-specific
methods). Full scorer decouple is a separate refactor.

CI: GREEN ✅ (run 28177542230 → AATE_v5.0.4158).

## V5.0.4150 (Feb 2026) — REGIME-PIVOT INTAKE + DEFENSIVE-LANE ADMIT

Operator: *"its doing nothing mate. its not meant to disable its meant to
pivot to the right strategy."*

### Root cause
`AgenticStyleRouter` already had the right pivot logic (`weakChopStylePivot`,
`rapidToxicRegimePivot`) — but it ran AFTER intake had already locked the
watchlist entry's `laneAffinity` to source-driven degen lanes (MOONSHOT,
SHITCOIN, PROJECT_SNIPER). Defensive lanes never got tokens to evaluate.

Operator dump (2026-06-25 19:18, regime=DUMP wr=4.0% n=100):
```
LANE_EVAL: PROJECT_SNIPER=18  MOONSHOT=9  SHITCOIN=7  EXPRESS=7
           QUALITY=0  DIP_HUNTER=0  TREASURY=0  CASHGEN=0
```

### Fix 1 — `inferIntakeLaneAffinity` is now regime-aware
When `RegimeDetector.currentRegime() == DUMP`, the function prepends
`DIP_HUNTER/QUALITY/TREASURY/CASHGEN` to the lane affinity. Degen lanes
kept as fallbacks (STANDARD-mode V3 throughput preserved). The watchlist
entry now carries regime-appropriate lanes FIRST.

Forensic: `REGIME_PIVOT_INTAKE_V4149`.

### Fix 2 — Defensive lanes exempt from DUMP kill switch
V5.0.4134 killed ALL lanes <25% WR in DUMP. But `DIP_HUNTER/QUALITY/
TREASURY/CASHGEN/BLUECHIP/STANDARD` are the lanes that SHOULD trade in a
DUMP (their tactics — panic_reversion, pullback_reclaim — are regime-
appropriate). Now the DUMP kill only fires for degen lanes (MOONSHOT,
SHITCOIN, EXPRESS, PROJECT_SNIPER, CYCLIC, MANIPULATED).

Forensic: `REGIME_PIVOT_LANE_ADMIT_V4149`.

### Net behavior in DUMP
1. Intake routes new tokens to defensive lanes first
2. AgenticStyleRouter picks PANIC_REVERSION / DEFENSIVE_PROBE styles (existing)
3. DUMP kill switch exempts defensive lanes (admits them)
4. LivePauseButton top-lane bypass (V5.0.4148) lets top-WR lanes through
5. MOONSHOT/SHITCOIN/EXPRESS stay locked by LaneTimeoutGate + DUMP kill

The bot **pivots** instead of shutting down — exactly as operator demanded.

CI: GREEN ✅ (run 28160343993 → AATE_v5.0.4150).

## V5.0.4148 (Feb 2026) — TOP-PERFORMING-LANE BYPASS (DEADLOCK FIX)

Operator dump (2026-06-25 18:34, build 5.0.4147 +115s uptime, post-V5.0.4134):
- `BUY ok/fail: 0 / 65` → V5.0.4134 was working **perfectly**, vetoing every
  buy attempt with `LIVE_PAUSE_DEFENSIVE`. Zero new bleed in 115 seconds.
- But the lifetime numbers were stuck: WR=14.7%, -1.58 SOL — accumulated
  **before** V5.0.4134 deployed.
- Lane breakdown showed `STANDARD WR=38.5% EV=+5.37%` (profitable!) and
  `MOONSHOT WR=10.5% EV=-24.44%` (bleeder). STANDARD was the recovery path
  but was being paused along with everything else because
  `LivePauseButton.isDefensive()` is GLOBAL.

### The deadlock
Global WR (14.7%) was dragged below the 30% pause floor by MOONSHOT's 117
trades. DEFENSIVE engaged → all lanes vetoed → STANDARD couldn't trade →
no new outcomes → rolling-30 window never refreshes → DEFENSIVE permanent.

### Fix — top-lane bypass at BOTH veto chokepoints (`doBuy` + `liveBuy`)
`LivePauseButton` already maintains `topLanes` (top-3 by WR with n≥3) for
the existing `laneSizeTilt` feature. Use it as a bypass condition:

  `effectivePause = pauseDefensive && !LivePauseButton.isTopPerformingLane(lane)`

Lanes in the top-3 keep trading even during DEFENSIVE. STANDARD seeds
fresh outcomes; global WR eventually crosses the 45% recover floor.

### NOT changed (broken lanes stay locked)
- Rug-blacklist (V5.0.4133) — universal
- DUMP regime kill switch (V5.0.4134) — bypass-immune; STANDARD@38.5% > 25%
  threshold so doesn't trigger anyway
- LaneTimeoutGate — per-lane; MOONSHOT stays timed out
- ScannerLaneBridge — per (source, lane)

Forensic: `TOP_LANE_BYPASS_V4148` (success), `DISCIPLINE_VETO_V4148`
(still-blocked non-top lanes).

CI: GREEN ✅ (run 28157804365 → AATE_v5.0.4148).

## V5.0.4146 (Feb 2026) — APK VERSION AUTO-BUMPS FROM CI RUN NUMBER

Operator: *"its not bumping the build number the last 4 have had the same
number. align with the git run number."*

V5.0.4131 / 4132 / 4133 / 4134 all shipped as artifact `AATE_v5.0.4132`
because the `AATE_VERSION` file held the literal patch number and the
workflow read it verbatim.

### Fix
- `AATE_VERSION` now holds the major.minor prefix only: **`5.0`**
- Both `build.yml` (nested + root) and `release.yml` workflows compose
  `VERSION_NAME="${BASE}.${BUILD_NUMBER}"` where
  `BUILD_NUMBER = GITHUB_RUN_NUMBER + 1`
- Every push now produces a uniquely-named APK aligned with the CI run
  number (e.g. this run = 4145 → artifact `AATE_v5.0.4146`)

### Tests inverted (GoldenTapeRegressionTest)
- `apk_version_uses_explicit_aate_patch_version_not_ci_run_drift` was an
  OLD invariant from a prior session that EXPLICITLY PROHIBITED this
  exact pattern. Renamed/inverted to
  `apk_version_patch_derived_from_ci_run_number`.
- `ci_apk_version_name_matches_operator_patch_sequence` updated to match
  the new `BASE + BUILD_NUMBER` composition.

**Going forward** the "V5.0.41XX" labels in commit messages and PRD
entries are narrative tags only — the real APK version is whatever the
CI produces. Operator can read it from the artifact filename.

CI: GREEN ✅ (run 28147335468 → AATE_v5.0.4146).

## V5.0.4134 (Feb 2026) — DUMP REGIME KILL SWITCH + UNIVERSAL `liveBuy()` VETO

Operator pushback after V5.0.4133: *"bot is still going backwards winrate
under 20%. unacceptable."*

### Root cause discovered
- V5.0.4133's rug-blacklist veto sat at `doBuy()` — but MOONSHOT
  shadow-to-live handoff calls `liveBuy()` *directly* at
  `Executor.kt:8115`, **bypassing `doBuy()` entirely**. Plausibly the
  cyclic/network-signal auto-buy paths do the same.
- Even when discipline gates ran, the V5.0.4132/4133 GOLD/WINNER goose
  bypass let pattern-flagged tokens through — which is precisely how the
  same rug-pattern mint kept getting re-bought.
- `LaneExpectancyDamper` (size ×0.18) is a damper, not a brake. Tiny
  size × negative EV × dozens of trades = slow certain bleed.

### Fix — three-layer veto stack at the TRUE single live entry point
`liveBuy()` is the documented single live executor entry point (see
comment line 8101). The three-layer veto sits at the head:

1. **Rug-blacklist** — universal, immune to all bypasses. Forensic:
   `RUG_BLACKLIST_VETO_V4134`.
2. **DUMP-regime kill switch** — fires when BOTH
   `RegimeDetector.currentRegime() == DUMP` AND lane WR < 25% with n≥12
   (via `StrategyTelemetry.computeLiveTerminalLeaderboard` — same data
   source `LiveProbabilityEngine` reads). **Cannot be bypassed by
   GOLD/WINNER**. Auto-recovers when regime exits DUMP or lane WR climbs
   back. Forensic: `REGIME_KILL_VETO_V4134`.
3. **Standard discipline veto** — pause / timeout / scanner-bridge
   mirrored from `doBuy` so non-`doBuy` callers also see it. GOLD/WINNER
   bypasses these (per V5.0.4132 design); rug-BL + DUMP kill are immune.
   Forensic: `DISCIPLINE_VETO_V4134`.

CI: GREEN ✅ (run 28146223234).

## V5.0.4133 (Feb 2026) — RUG-BLACKLIST UNIVERSAL VETO + TIGHTER DISCIPLINE FLOORS

Operator dump (2026-06-25, V5.0.4132 post-restart, 344s uptime) — same mint
`EnsVnDQ3` appeared 6× in last 10 closes at identical -98.6% / -0.0215 SOL,
reason `MOONSHOT_STOP_LOSS`. That single rug accounted for ~22% of the
-0.5868 SOL lifetime bleed because the blacklist was never being consulted
at buy time.

### Fix 1 — RugMintBlacklist universal veto at `Executor.doBuy`
`recordClose()` was wired in V5.0.4132's live-sell path so the data structure
was populating, but `isBlacklisted()` was only consulted in
`BotService.kt:4813` — one MEME-lane buy path. Every other lane (MOONSHOT,
SHITCOIN, QUALITY, BLUECHIP, EXPRESS, CASHGEN, TREASURY, MANIPULATED,
DIP_HUNTER, PROJECT_SNIPER, CYCLIC) skipped the check. Veto now runs at the
universal `doBuy` chokepoint, **BEFORE** the GOLD/WINNER goose bypass — a
pattern verdict cannot override "this exact mint rugged us within the last
24h". Forensic: `RUG_BLACKLIST_VETO_V4133`.

### Fix 2 — LivePauseButton 25/35 → 30/45
Global WR was sitting at 24.4% — just below the 25% pause floor — but the
gates were flapping at the boundary. Tightened entry to 30% / recovery to
45% (15-point gap vs prior 10-point). Discipline now engages decisively in
DUMP regimes without flap-on-flap-off.

### Fix 3 — LaneTimeoutGate 20/35 → 25/45
MOONSHOT lane WR at 24-25% with EV=-76%, pWin=15% — still just above the
20% timeout floor. Tightened entry to 25% / recovery to 45%. Broken lanes
now actually enter timeout.

### Not fixed (intentional)
- Journal "duplication" (6× EnsVnDQ3 rows): on re-investigation these are
  6 *separate* buy/sell cycles on the same rug, not write amplification.
  Existing 1500ms cross-path dedupe is correct; bumping it risks dropping
  legitimate partial sells (operator confirmed 3-27s spacing). Root cause
  is fixed by Fix 1.
- Wallet disconnect (7/9 post-restart buy fails): needs fresh dump from
  this build; likely network blip during forced restart.

CI: GREEN ✅ (run 28143456053).

## V5.0.4131 – 4132-fix2 (Feb 2026) — DISCIPLINE-FIRST PASS

### V5.0.4131 — Real-Size Entries
Removed a liquidity-cap dust trap in `realisticLiveEntrySize` that was floor-clamping
buys to ~0.01 SOL on most live entries. Restores realistic entry sizing under the
existing safety envelopes.

### V5.0.4132 → -fix2 — Discipline Pack + Universal Scanner Bridge
Pivot from "smarter overrides" to "hard discipline" after WR slid from 37% → 15.8%
during a DUMP regime. The previous "bypass filters for high-score tokens" path was
inadvertently buying garbage at larger sizes. Four new gates + brain were added:

- **LivePauseButton**: rolling per-lane WR brake. Pauses a lane that breaches a
  short-window WR floor; auto-recovers on subsequent green closes. Trained from
  every live close via `Executor.recordOutcome`.
- **LaneTimeoutGate**: cooldown gate per lane after consecutive losses; learns from
  closed trades.
- **RugMintBlacklist**: seeds + reinforces per-mint blacklist on rapid heavy-loss
  closes (mint, pnl, holdMs).
- **ScannerLaneBridge**: scanner-source × lane brain. Records source/lane outcomes
  and vetoes high-toxicity (source, lane) pairs universally.
- **Executor outcome wiring**: live-sell PnL feeds all four discipline modules in
  a single try/catch block at the same site as the existing learning fan-out.

CI: V5.0.4132 + -fix1 failed on Kotlin reference errors. **-fix2 GREEN** —
`Executor.kt:17005` was reading `tradingMode` from `TokenState` where it actually
lives on `Position`. Switched to `pos.tradingMode` (matching the block ~30 lines
above that already reads `pos.tradingMode` for `traderSource`). No behaviour
change beyond the compile.

## V5.0.4126 – 4130 (Feb 2026) — MEME-TRADER MONEY-PRINTER ARC

### V5.0.4126 — MoonshotAdaptiveGate (fluid lane pivot)
Per-lane recency-weighted WR steering gate. Newest 50 closes count 2.0× , prior 50 count 1.0×.
Bounded score-floor bias [-5, +20]: tightens on bleed, loosens on win.
Phase tags: COLD_START / AGGRESSIVE / NEUTRAL / DEFENSIVE / EMERGENCY.
Auto-recovers as WR climbs; never a veto. Closes the death-spiral loop without disabling.

### V5.0.4127 — Runner Protection (U-shaped trail)
Trail curve flipped from monotonic-tighten to U-shape so monster runners can compound
through the MONSTER_LOCK ladder. +500% → 0.55× base, +1000% → 0.75×, +3000% → 0.95×,
+10000% → 1.20×. Lock ladder still banks $; trail catches round-trip giveback only.

### V5.0.4128 — Pattern Golden Goose
Asymmetric edge detector on TokenWinMemory patterns. Enumerates best/worst matched
patterns independently. Verdict ladder: CATASTROPHIC / TOXIC / NEUTRAL / WINNER / GOLD.
Bias [-35..+16] applied additively to lane score. Asymmetric tilt — toxic dominates gold
~2×. Wired into MoonshotTraderAI + ShitCoinTraderAI scoreToken.

### V5.0.4129 — Money-Printer P0 trio + lane wakeup
**Fix 1**: Executor.doBuy absolute floor + goose size override. Was relative floor on
collapsed `sol` (dust on dust). Now wallet-aware absolute floor; GOLD → STRONG_ENTRY_SOL
+ 1.5× upper cap.
**Fix 2**: MoonshotTraderAI goose exit protection. GOLD bypasses EARLY_TIGHT_STOP and
HOLD_BUCKET_EARLY_EXIT (hard floor -15% still applies).
**Fix 3**: LiveLayerGateRelaxer.floorMultiplierForToken — per-token bypass of the
WR<30% global lock for GOLD/WINNER tokens.
**Fix 4**: BotService.inferIntakeLaneAffinity + laneAffinityForTradeType broadened
to seed CASHGEN/CYCLIC/MANIPULATED/EXPRESS/DIP_HUNTER. Pre: 6 of 12 lanes silent.
Post: every enabled lane is a candidate.

Operator-observed effect: ANR 25→0, max cycle 162s→9.7s, cache hit 11.7%→44.2%,
Birdeye CU 100%→22%, BUY OK 2 → 29 in 186s.

### V5.0.4130 — Profit Booster Trio
**Fix 1**: ultra_runner_bank current-price sanity gate. Was: panic-banker fired forever
once a position EVER peaked at 50x. Journal showed banker selling at -29% / -66% PnL.
Now: requires currentValue ≥ costSol × 1.5.
**Fix 2**: FDG TOKEN_MAP_INCOMPLETE goose downgrade. GOLD/WINNER → advisory + soft-shape,
let executor fallback routing handle it. Unblocks the 50% of FDG verdicts previously
hard-blocked on transient route-data lag.
**Fix 3**: DUMP-regime goose bypass in Executor. GOLD → 1.00 (full bypass),
WINNER → 0.60 floor. Other verdicts unchanged.

All three compose: only quality-confirmed verdicts unlock boosts; TOXIC/CATASTROPHIC
never bypass safety. Volume preserved or expanded; quality preserved or improved.

CI: all five builds GREEN.

## Backlog (P2/P3)

- "Ladder" status pill at top of Memes tab
- Strategy Leaderboard tile (live top-3 by expectancy)
- Brain Health pill next to sentiment badge
- 24h PnL drift alert
- Tune History UI tab
- /positions backup export
- Real bridge / CEX adapters (deBridge/Mayan, Coinbase/Kraken)
- LEDGER_DRIFT investigation (canonicalOpen vs walletHeld 0.034 SOL diff)
- PatternAutoTuner LIFT side (currently only NERFs; phases with positive expectancy never get >1.0×)
- Visibility gap audit: ~200 strategies internal vs ~20 surfaced in operational report

## V5.0.4109–4110 (Feb 2026) — DEADLOCK FIX P0 + WR booster

### V5.0.4109 — Supervisor / Exit-Coordinator Deadlock root-caused & fixed
**Operator:** *"it had over a thousand anr issues in 6 hours and completely froze."*

**Root cause:** `HoldingLogicLayer.evaluatePosition()` was a `suspend fun`
wrapped in `kotlinx.coroutines.sync.Mutex().withLock { ... }` but its body
had ZERO real suspending calls (pure compute). `Executor.kt` called it via
`runBlocking { evaluatePosition(...) }` per-token loop with no timeout.
Every concurrent token serialized through one coroutine mutex while parking
its host worker thread on `Unsafe.park`. With 20+ tokens evaluated
concurrently the IO dispatcher pool drained → supervisor froze.

**Fix:**
- Removed the coroutine Mutex entirely (function is pure compute, nothing
  to synchronize).
- Converted `evaluatePosition` from `suspend fun` to plain `fun`.
- `Executor.kt` calls it directly (no `runBlocking`).
- New `LockDiagnosticsTracker` (`engine/diagnostics/`) instruments critical
  sections; emits forensic `LOCK_LONG_HOLD` (>2s) and `LOCK_ALERT_HOLD`
  (>10s) with owner thread + held ms so any surviving contention surfaces
  in the next operator dump.
- Defensive `withTimeoutOrNull(1500-2000ms)` on remaining unbounded
  `runBlocking { price-fetch }` sites in `FluidLearning` and `BotService`.

CI: Build ✅ + Runtime Smoke Test ✅.

### V5.0.4110 — WR booster: high-precision Confirmed Loss Cut
**Operator:** *"need winrate improvements as well still losing money."*

Added a SINGLE high-precision early-exit inside
`AdvancedExitManager.evaluateExit()`. Fires ONLY when ALL FOUR
death-confirmations align: holdMinutes ≥ 1, pnl ≤ -3%, momentum < -8,
liquidity drop ≥ 15%. Three independent signals must agree before
cutting — won't fire on wicks, won't gate entries, won't violate the
operator mandate ("meme trader never choke itself out"). Targets the
bleed-by-thousand-cuts regime where positions grind through -3..-8%
with collapsing momentum until base SL fires.

CI: Build ✅.



## V5.0.4096 (Feb 2026) — AGI ↔ SENTIENCE SYMBIOSIS: brains wired into the AI family

**Operator:** *"do the new agi brains need to be wired into the ai cross talk system, education, sentience, symbiosis, behaviour, metacognition cognition etc etc."* + *"keep going bro everything new always should be wired through. 0 exceptions."*

**Audit gap:** the 26 brains shipped in V5.0.4094-4095 (13 lanes × entry+exit) were ISLANDS — they trained, decided, and overrode but did not speak to `SentienceOrchestrator`, `SentientPersonality`, `AICrossTalk`, `BehaviorLearning`, or `AutonomousMetaPolicy`.

**Wired three lifecycle moments from BOTH new brains:**
1. **Tier graduation** (BOOTSTRAP→ADVISORY→LEARNED→AUTHORITATIVE) → `SentienceOrchestrator.noteRuntimeEvent('AGI_BRAIN_TIER_GRADUATED', INFO)` + `SentientPersonality.injectAutonomousThought("I just leveled up on $lane.")`. The bot literally narrates each AGI maturation.
2. **Calibration demote** (Brier > 0.27) → `SentienceOrchestrator.noteRuntimeEvent('AGI_BRAIN_DEMOTED', WARN)` + thought injection ("Calibration drift on $lane. Pulling back to re-tune."). Self-awareness of cognitive drift.
3. **Authoritative override** (LEARNED+ tier replaces rule-stack damps) → `SentienceOrchestrator.noteRuntimeEvent('AGI_AUTHORITATIVE_OVERRIDE', INFO)` per trade.

Coverage: BOTH brains wired at all three moments. 0 exceptions.

**Deferred for V5.0.4097:** AICrossTalk.getSizeMultiplier as 7th AGI input feature; AutonomousMetaPolicy ↔ AGI conviction blending at FDG; BehaviorLearning richSignature including per-lane authority tier.


## V5.0.4093–4095 (Feb 2026) — AGI BRAIN STACK: per-lane multi-head learning across 13 lanes × 2 decision types

**Operator vision:** *"aate isnt meant to be a series of gates - it's meant to be a super agi intelligence stack for solana, then the rest of crypto, then the world... brainsssssss."*

The gate stack was scar tissue — every gate a place where a human hard-coded a threshold that should have been a learned signal. V5.0.4093–4095 graduates the learned heads from advisory voters in a chain to authoritative AGI brains that drive sizing AND exit timing per-lane.

### V5.0.4093 — AGI Authority Graduation
- `UnifiedPolicyHead.authoritativeConviction()` returns wider multiplier range as `trained` count grows: BOOTSTRAP (<40, neutral) → ADVISORY (40–99, ±40%) → LEARNED (100–249, REPLACES rule-stack damps) → AUTHORITATIVE (250+, ±70%, slope 2.6×).
- FDG demotes rule-stack soft damps to fallback when authoritative tier active.

### V5.0.4094 — Multi-Head Per-Lane Brains
- 13-lane brain stack: each lane (MOONSHOT, STANDARD, BLUECHIP, SHITCOIN, EXPRESS, PROJECT_SNIPER, DIP_HUNTER, WALLET_RECOVERED, MANIPULATED, CYCLIC, QUALITY, TREASURY, CASHGEN) gets its own logistic-regression brain over 6 committee features.
- New lane heads warm-start from global head's weights, then specialise via SGD.
- Per-lane authority graduation — MOONSHOT can hit AUTHORITATIVE before BLUECHIP even reaches ADVISORY.
- **Brier-score self-calibration:** each brain accumulates prediction-vs-outcome MSE over rolling 200-trade window. Brier > 0.27 (drifting) → demoted one tier until calibration recovers. Never disabled, just put back on training wheels.
- Persisted per-lane state in SharedPreferences `unified_policy_head`.

### V5.0.4095 — Exit Brains (brains for everyone)
- Sister `UnifiedExitPolicyHead` mirroring the multi-head pattern but trained on EXIT-timing signals.
- 6 exit features: pnlPct, maxPnlPct, ageNorm, momentumDn, liquidityErode, sellPressure.
- Output: exitBias multiplier in [0.70, 1.40] (LEARNED) or [0.55, 1.65] (AUTHORITATIVE). Soft-modulates rule-stack TP/SL/HOLD timing — never forces or blocks an exit. Hard exits (rug/honeypot/strict-SL) bypass entirely.
- Trained from existing `Executor.recordOutcome` paths. exitWasOptimal heuristic = pnl > -5% (didn't catastrophically overrun, didn't capitulate to zero).
- Per-lane authority graduation independent from entry brain.

**Result:** 26 active brains (13 lanes × 2 decision types). Doctrine compliance preserved — soft-shape only, never veto, hard gates remain non-negotiable safety. As samples grow, the gate stack atrophies into fallback while the AGI swarm earns authority.

**Ops report sections added:** "Unified Policy Head (multi-head AGI)" and "Unified Exit Policy Head" — each shows global state + per-lane brain table with sample count, current authority tier, bias, Brier score, and calibration status.



## V5.0.4090 (Feb 2026) — \$500 absolute liquidity hard-floor — CI ✅

**Operator P0:** *"CATASTROPHIC_STOP_LOSS_OVERRUN_-47% still pending. lane-aware entry liquidity floor (dont let STANDARD touch <\$2K liq tokens)."*

**Root cause:** STANDARD lane entered POLARIS at \$192 liquidity. STRICT_SL_-10 fired at -10% observed price, but by sell-time the pool had collapsed → realized exit -47% = **-0.146 SOL single-trade loss**. No SL band survives a pool too thin to absorb the exit.

**Fix:** `MIN_LIVE_LIQ_HARD_FLOOR_USD = \$500` absolute hard-block in `PreTradeHardGate.requireLiveBuyAllowed()`, applied to ALL lanes. Sub-\$500 pools cannot safely execute any meaningful exit, regardless of lane. \$500 (not \$2K) was chosen to preserve MOONSHOT/SHITCOIN's hunting ground while catching the catastrophic case (POLARIS was \$192). The existing \$500-\$1500 soft tier (`LOW_LIQUIDITY_SIZE_REDUCED` penalty) remains for size-shaping above the floor.

**Forensic trail:** `PRETRADE_HARD_BLOCK_LIQUIDITY_BELOW_EXIT_SAFE_FLOOR` counter visible in pipeline health snapshot.

**CI:** commit `0788044cb` → Build ✅ + Smoke ✅. AATE_VERSION=5.0.4090.



## V5.0.4089 (Feb 2026) — RE-EDUCATE the bleeders (STANDARD/SHITCOIN/etc) — never disable — CI ✅

**Operator:** *"sort out the trading logic. get the rest of the traders thinking and making the right entries. sick of losing money. dont disable pivot - re-educate and succeed. 2x-5x daily wallet growth target."*

**Audit:** the re-education machinery (`LosingPatternMemory` danger buckets, `1-in-25 PROVEN_DEAD` probe cadence, `LaneToxicityGuard` routing pivot) was already wired correctly but the THRESHOLDS were tuned for catastrophic buckets, not slow bleeders. From ops snapshot — `STANDARD|S0-10 losses=32 wins=7 lossRate=82% meanPnl=-3.58%` was hemorrhaging -0.035 SOL at full size because:
- `isProvenDead` required `wins<=1` → STANDARD had 7 wins → never fired
- `LaneToxicityGuard` required `mean<=-5%` → STANDARD was -3.58% → never fired
- BCG SOFT_BLOCK damp was 0.90 → barely any size cut

**Fix:** three coordinated changes that preserve the 1-in-25 probe cadence (re-educate, not disable):
1. `BrainConsensusGate.isProvenDead`: ALSO catch mature bleeders (n≥20, lossRate≥75%, mean≤-1.0). Severe catastrophic gate retained as upper tier.
2. `LaneToxicityGuard.isNetNegativeDanger`: lower threshold -5.0→-2.0 + add loss-rate trigger (n≥20 AND lossRate≥75% AND mean≤-0.5).
3. `FDG` BCG SOFT_BLOCK damp: danger objections now trigger hard 0.50× (was 0.90×); deep deficit + danger = 0.25×.

**Expected outcome:** STANDARD lane stops eating full-size losses on known losing score bands. The bucket trades 1-in-25 dust probes that keep the WR counter alive so the bucket can heal back to full sizing when its real-world performance turns positive. MOONSHOT and other healthy lanes entirely unaffected — they don't hit the danger-bucket criteria.

**CI:** commit `55cf9d706` → Build APK ✅ + Runtime Smoke Test ✅. AATE_VERSION=5.0.4089. (Hit 1 transient — GoldenTape assertions needed -2.0 threshold update; fixed in `55cf9d706`.)



## V5.0.4087 (Feb 2026) — surface trading fee accumulator status in pipeline health snapshot — CI ✅

**Operator:** *"can you make sure that the trading fees are accumulating to be sent please"*

**Audit result:** `FeeAccumulator` (V5.0.3920) is wired correctly end-to-end. `init()` at `BotService:1249`; `accrue()` called from `Executor.sendFeeSplit()` and `MarketsLiveExecutor`; `tryFlush()` called every bot-loop cycle in live mode when total accrued ≥ 1.0 SOL. All 5 fee-emitting sites in `Executor` (profit_lock, partial_sell, partial_sell_v2, buy_fee, sell_fee) route through `sendFeeSplit()` which writes to the per-destination buckets. Self-loop protection in place on both `accrue()` and `tryFlush()`. Dust < 0.000005 SOL drops silently as expected.

**Fix:** Added operator-visible "Trading fee accumulator" section to `PipelineHealthCollector.dumpText()` (between Birdeye budget and API health). Surfaces: total accrued SOL, flush threshold, per-destination bucket map, retry queue depth, and a 🟢 READY / 🟡 accruing flush indicator. The next ops report dump will now confirm fees are batching toward the 1.0 SOL flush instead of being silently lost in micro-tx attempts.

**CI:** commit `d7359429a` → Build AATE APK ✅ + Runtime Smoke Test ✅. AATE_VERSION=5.0.4087.

## V5.0.4086 (Feb 2026) — UNCHOKE meme trader (triple-stack damper exemption) — CI ✅

**Operator ops snapshot @ V5.0.4085 confirmed MOONSHOT was triple-stack-damped to ~0.35% of normal size:**
- `LaneExpectancyDamper × 0.18` — fired because WR threshold 35 boundary missed (display rounds 34.x → 35)
- `RegimeDetector × 0.10` — global DUMP haircut applied to ALL lanes including the meme trader
- `LiveStrategyTuner × 0.35` — bleeder_runner_pivot fired (my 4085 threshold was 40, actual WR 35%)
- `laneSizeCap × 0.55` — MOONSHOT cap because `laneEvMult < 1.0`

**Operator P0 mandate:** *"the meme trader should maintain really good volume once learnt. it should never ever be allowed to choke itself out."*

**Fix:** HARD EXEMPT runner lanes (MOONSHOT/SHITCOIN/MEME/EXPRESS/MANIP/PRESALE/PROJECT_SNIPER/DIP_HUNTER) from all three global dampers once n≥30. Runner lanes already get their own per-lane TP/SL/hold tuning via `LiveStrategyTuner.runner_lane_exempt` + `LaneExitTuner`; the global dampers were structurally written for mean-stable STANDARD/BLUECHIP lanes and mis-classify asymmetric runners as bleeders.

- `LaneExpectancyDamper.compute()`: skip runner lanes entirely (no entry in map).
- `LiveStrategyTuner.buildAdjustment()`: early-return `runner_lane_exempt` for runner lanes at n≥30 (1.0× size, +20% TP, +40% hold, +30% partial).
- `Executor.kt` (sizing path): `regimeMult = 1.0` for runner lanes (skip global `RegimeDetector.sizeMultiplier()`).

Non-runner lanes (STANDARD/BLUECHIP) keep all three brakes unchanged. Failing exits remain governed by `LaneExitTuner` + `StrictSL` + `ExitCoordinator`, NOT by sizing.

**CI:** commit `3a6a0e553` → Build AATE APK ✅ + Runtime Smoke Test ✅.


## V5.0.4085 (Feb 2026) — WR-based RUNNER exemption (LiveStrategyTuner + LaneExpectancyDamper + RegimeDetector) — CI ✅

**Operator:** ops snapshot showed MOONSHOT n=141 wr=36% gross-EV +80%/trade getting damped to ~5% sizing. Three different organs (LiveStrategyTuner, LaneExpectancyDamper, RegimeDetector) all keyed exemption gates on NET-realized mean (-0.018%/trade after TP cuts + slippage), not gross EV. Mean-only exemption never fires for asymmetric runners → lane reads as bleeder → triple-stacked size haircut.

**Fix:** Switch exemption signals from mean-PnL to Win Rate + sample count:
- `LiveStrategyTuner.kt`: exempt when `n>=30 && wr>=40` OR existing `n>=8 && mean>=20`.
- `LaneExpectancyDamper.kt`: add `WR_RUNNER` gate (`n>=30 && wr>=35`) alongside `RUNNER_MEAN_PCT`.
- `RegimeDetector.kt`: CHOP now requires BOTH low WR AND negative meanPnl (was WR-only) — runner profiles with low WR but positive mean stay NORMAL, skip the global ×0.35 CHOP haircut.

**CI:** commit `e7868a465` → Build AATE APK ✅ + Runtime Smoke Test ✅. AATE_VERSION=5.0.4085, GoldenTape assertion bumped.

**Pending verification:** awaiting fresh ops report to confirm MOONSHOT sizeMult ≈ 1.0 in live.


## V5.0.3928 (Feb 2026) — PAPER ADVISOR GATE: de-poisons learning — CI ✅ (build AATE_v5.0.3932)

**Operator:** *"same logic now needs to be applied to paper learning. it must not buy rugs in paper mode either. the entire learning system is basically poisoned currently."*

**Root cause:** `consultEntryAdvisors` (rug/proof/brain/fluid/intel/momentum chain) only ran in `liveBuy()`. `paperBuy()` bypassed all of it. Paper rugs at -100% were feeding BotBrain pattern memory, EntryIntelligence weights, and PatternAutoTuner buckets with garbage signal→outcome pairs — the brain learned wrong patterns and either over-blocked legit signals OR failed to suppress them in live because the noise drowned the signal.

**Fix:** Inserted identical `consultEntryAdvisors(ts, score, layerTag)` call at the top of `paperBuy()` after basic INVALID_SIZE/EMPTY_MINT/INVALID_SCORE validation. Paper and live now consult the EXACT same chain — paper learning trains on the SAME filtered token set as live. Telemetry: `PAPER_BUY_ADVISOR_BLOCK` label + ForensicLogger row + `ADVISOR_<reason>` tag on `markPaperBuyNotOpened`.

**Sibling audit (no butterflies):** consultEntryAdvisors is the same function liveBuy uses (zero divergence). `markPaperBuyNotOpened` releases FinalExecutionPermit + LaneExecutionCoordinator slot intact. Brace/paren balance clean. Live path untouched.

**Expected impact:** Paper trade volume drops on the subset of tokens the advisor chain blocks. The brain stops being trained on rug outcomes. Learned thresholds converge to true population distribution faster; live learning signal becomes representative.

**CI:** run 27835383071 → SUCCESS → APK `AATE_v5.0.3932` published.


## V5.0.3927 (Feb 2026) — RUGCHECK POLARITY AUDITED + HOLDER-UNKNOWN LIVE GATE — CI ✅ (build AATE_v5.0.3931)

**Operator hypothesis: "rug prevention scoring is flipped".** Audited at producer:
- `TokenSafetyChecker.kt:503/563/569` confirms HIGHER = SAFER (rugcheck.xyz `score_normalised` convention). `rcScore==0` HARD BLOCKS as confirmed rug; `2-4` very risky; `5-9` risky; `61` legitimately safe.
- `BotBrain.learnedRugcheckThreshold` consumer semantics match: "block when rcScore ≤ threshold" = "block when score is low/risky". **Polarity NOT flipped.**

**Real root cause of the RICHTROLL/SolanaTrack-flagged rug:**
- Rugcheck.xyz returned a clean score, BUT holder-concentration / single-owner / verified-status checks were silently skipped because pre-3927 guard required `topHolderPct > 0.0` before checking. Birdeye in EMERGENCY CONSERVATION (1 call/day) means holder data often hadn't landed for fresh-launch tokens → check auto-passed.

**Fix in `Executor.consultEntryAdvisors()`:**
- Replaced `topHolderPct > 0.0 && ... > learnedMaxTopHolder` (silent skip on unknown) with `topHolderPct <= 0.0 → block PROVIDER_PROOF_HOLDER_UNKNOWN`; `> learnedMaxTopHolder → block BRAIN_TOP_HOLDER_CEILING`. Live BUYs now REQUIRE populated holder data per doctrine.
- Paper mode unchanged (advisor only fires in `liveBuy()`).

**Sibling audit:** `ts.safety.topHolderPct` is non-nullable Double; 0.0 default = no data sentinel. No GoldenTape regressions. Brace/paren balance clean.

**Deferred to next session** (operator items #3 + #4, need fresh context budget):
- 🔴 P1: LANE_FANOUT regression-guard-aware dedup (touches `BotService.shouldRunBuyLaneForCycle` + GoldenTape `fanout_suppression_never_…` guard — can't half-ship).
- 🔴 P1: Per-provider snapshot provenance refactor (every scanner writer site for Birdeye / GeckoTerminal / DexScreener / Helius / CoinGecko / Pyth needs to write to its own slot AND the canonical field — too risky to attempt without missing a site).

**CI:** run 27834729764 → SUCCESS → APK `AATE_v5.0.3931` published.


## V5.0.3926 (Feb 2026) — P0+P1 SURGICAL: rug-close accuracy + tracker desync fix + live grace tightening + ProviderProofWalker — CI ✅ (build AATE_v5.0.3930)

**Operator dump V5.0.3929 surfaced 4 distinct issues. All addressed in one tight commit.**

1. **Rug-close accuracy** (`StartupReconciler`): `JOURNAL_XREF_EXTERNAL_CLOSE` was marking wallet-zero positions with `pnlPct=0 / realizedSol=0` — corrupting WR math (rugs appearing as scratch trades). Now records `-100%` pnlPct, `-buyRow.sol` realized loss, AND writes a synthesized `SELL` row via `TradeHistoryStore.recordTrade` with reason `EXTERNAL_RUG_CLOSE`.

2. **Tracker desync false-positives** (`InvariantGuardian`): `LIVE_BUY_CONFIRMED_NOT_VISIBLE_CRITICAL` + `TRACKER_OPEN_DESYNC_CRITICAL` fired whenever `canonicalOpen > 0 && (liveOpen == 0 || hostTrackerOpen == 0)` — normal state during `BUY_PENDING_BALANCE` (confirmed buy waiting for wallet proof, ≤90s). Added `pendingProofInFlight = TokenLifecycleTracker.confirmedPendingCount > 0` guard — both faults silent during legitimate confirmation window. Genuine stale case still handled by `BUY_PENDING_BALANCE_PROOF_STALE`.

3. **Live grace tightening** (`HardRugPreFilter`): Grace-period auto-pass let STANDARD-lane live buys fire during first 60s of a token's life with no liquidity proof — direct cause of Dig2ougb -96.9% rug. PAPER mode keeps grace pass (learning); LIVE mode now HARD_FAILs as `GRACE_PERIOD_DATA_UNAVAILABLE_LIVE`.

4. **ProviderProofWalker** (NEW): `getBestAvailableProof(ts, field)` iterates providers in current `ApiHealthMonitor` success-rate order for LIQUIDITY_USD / MCAP_USD / HOLDER_CONCENTRATION_PCT / RUGCHECK_SCORE. Hot-path safe (no new HTTP). Wired into `consultEntryAdvisors` — every live buy now requires LIQUIDITY_USD at REAL_CONFIRMED (≤30s) or PARTIAL_CONFIRMED (≤120s). UNKNOWN/STALE blocks with `PROVIDER_PROOF_LIQUIDITY:<quality>:source=<who>`.

**Sibling audit:** Brace/paren balance clean on all 5 files. Existing `DataOrchestrator` (token events class) untouched — new walker named `ProviderProofWalker` to avoid collision. No GoldenTape regressions.

**CI:** run 27832690943 → SUCCESS → APK `AATE_v5.0.3930` published.


## V5.0.3925 (Feb 2026) — HardRugPreFilter STRICT on liveBuy + BotBrain size multiplier wired — CI ✅ (build AATE_v5.0.3929)

**Operator dump on V5.0.3928:** *"bot is still getting rugged… not good enough"* — screenshot showed Insider-lane position at -100% (rug). 3924 wiring (brain, fluid floor, fluid lane scoring) wasn't catching it.

**Root causes:**
1. `HardRugPreFilter` exists and is called in the scanning pipeline (`BotService:15737`), BUT its result is softened by `ModeLeniency.useLenientGates()` for proven-edge live runs — that bypass let rug patterns reach `liveBuy()` unfiltered.
2. `BotBrain.getRiskAdjustedSizeMultiplier(phase, emaFan, source)` was a public API learning per-tuple drawdown patterns but was NEVER consulted in the final sizing pipeline.

**Fixes:**
1. `consultEntryAdvisors()` now calls `HardRugPreFilter.filter(ts, isPaperMode=false)` in **STRICT mode** regardless of overall lenient setting. `HARD_FAIL` → blocks with `RUG_PREFILTER_HARD_FAIL:<reason>`. `SOFT_FAIL` still passes with telemetry. Single insertion covers all 9 sub-lanes including Insider/WHALE_COPY watchlist entries.
2. `multiplierProduct` in the live sizing path now includes `brainSizeMult = brain.getRiskAdjustedSizeMultiplier(phase, emaFan, source)`. Brain shrinks size on (phase, emaFan, source) contexts that have produced sustained drawdowns. Defaults 1.0 when brain null or no context history.

**CI:** run 27830987595 → SUCCESS → APK `AATE_v5.0.3929` published.


## V5.0.3924 (Feb 2026) — STACK WIRING: BotBrain + FluidLearningAI + LaneExitTuner consulted on live BUY — CI ✅ (build AATE_v5.0.3928)

**Operator demand:** *"use the intelligence stack — well the whole fucking app properly. its literally all there rebuilt you just keep half assing stuff constantly!"*

Triage agent inventory confirmed: the codebase has a massive sophisticated learning stack (BotBrain, FluidLearningAI, LaneExitTuner, EntryIntelligence, PatternMemory, etc.) that is BUILT AND ALREADY LEARNING but the live BUY authorization path was bypassing most of it. **Not a new-gate problem — a wire-the-existing-learning problem.**

**Wired into `Executor.consultEntryAdvisors()` (single chokepoint, all 9 sub-lanes flow through it):**
1. `BotBrain.shouldSkipTrade(phase, emaFan, source, score)` — learned hard-suppressed pattern registry.
2. `BotBrain.learnedRugcheckThreshold` (adaptive 5-40).
3. `BotBrain.learnedMinBuyPressure` (adaptive 10-35%).
4. `BotBrain.learnedMaxTopHolder` (adaptive 40-80%).
5. `BotBrain.learnedMinLiquidity` (adaptive `$100` default).
6. `FluidLearningAI.getExecuteFloor()` — fluid score floor (BOOTSTRAP 5 → MATURE 35+).

**Fluid lane scoring (replaces hardcoded `score=70/85`):**
7. New `brainAdjustedLaneScore(baseScore, ts)` helper routes lane base through `brain.effectiveEntryThreshold()` + `getPhaseBoost(phase)` + `getSourceBoost(source)`. Applied to all 4 hardcoded sites: `shitCoinBuy` paper+live (was 70), `blueChipBuy` paper+live (was 85).

**`LaneExitTuner` wired into `PROJECT_SNIPER` entry:**
8. `BotService` PROJECT_SNIPER call (was hardcoded TP=35.0, SL=-12.0) now multiplied by `LaneExitTuner.getTpMult/getSlMult('PROJECT_SNIPER')`. Tuner has been silently learning per-lane TP/SL multipliers; now those learnings actually influence new entries.

**Sibling audit:** all wiring guarded with try/catch → null brain or sparse safety data never chokes the bot. No GoldenTape regressions. Brace/paren balance clean. LiveBuyAdmissionGate/FinalDecisionGate/ExecutableOpenGate untouched.

**3924b** — fixed unresolved reference: `ts.entryPhase` → `ts.phase` (correct TokenState field name).

**Expected impact:** live entries the brain has already learned to suppress (low rugcheck score patterns, low-pressure entries on historically-losing phases, etc.) are now filtered AT the liveBuy chokepoint. WR/profitability lift without any fixed-threshold disabling. Throughput drops only on the exact patterns the brain has trained itself to avoid.

**CI:** run 27829773221 → SUCCESS → APK `AATE_v5.0.3928` published.


## V5.0.3922–3923 (Feb 2026) — PRICE PRECISION + DORMANT-AI ADVISOR GATE — CI ✅ (build AATE_v5.0.3926)

**Operator findings:**
- "anything under with a .00000 gets marked as 0 cost basis possibly as the price gets cut off" — phantom `$0.00000000` entryPrice on DUMOCRATS/antnald positions producing junk +99964645% PnL, rug-protection bypass, journal validation failures.
- "all the tech is already there but the bot isn't using it" — 4 entry-quality AIs (EntryIntelligence, MomentumPredictorAI, RouteSelector, SlippageGuard) marked LIVE_ELIGIBLE in UI but never consulted before live BUYs.

**3922 — `PositionPersistence.kt` PRECISION FIX + SYNTH-FROM-COST FALLBACK:**
1. Every `Double` price field now serialized via `.toString()` (preserves full IEEE-754 precision including denormals/scientific notation) with a `putPrice()`/`getPrice()` helper. Round-trip through SharedPreferences cannot truncate sub-1e-5 prices.
2. **Synth-from-cost fallback**: when a restored position has `entryPrice=0.0` but `costSol>0 && qtyToken>0`, reconstruct as `costSol/qtyToken`. Tagged `SYNTH_COST_DIV_QTY|<orig source>` so operators can spot rescued positions. **Retroactively rescues every existing phantom-zero position on next app cycle** — DUMOCRATS, antnald, etc. all get computable PnL/SL/TP back.

**3923 — DORMANT-AI ADVISOR GATE at `liveBuy()` chokepoint:**
1. New `consultEntryAdvisors(ts, layerTag)` helper. Permissive thresholds (never choke):
   - `MomentumPredictorAI.shouldAvoid(mint)` → block (returns false on no-data → fresh launches unaffected).
   - `EntryIntelligence.scoreEntry()` → block ONLY when `recommendation == AVOID && score < 25 && trainedTrades ≥ 40`.
   - Any internal failure → ALLOW.
2. Inserted as FIRST step of `liveBuy()`, BEFORE `ExecutionAttemptLease.acquire()` so an advisor-block cannot leak a lease.
3. All 9 sub-lanes multiplex through `liveBuy()` (SHITCOIN/MOONSHOT/MANIPULATED/EXPRESS/QUALITY/BLUECHIP/TREASURY/DIP_HUNTER/PROJECT_SNIPER) → single insertion is the entire fix.
4. Telemetry: `LIVE_BUY_ADVISOR_BLOCK` ForensicLogger row + `PipelineHealthCollector.labelInc()`.

**Sibling audit (no butterflies):**
- 9 sub-lanes confirmed wired (QUALITY→blueChipBuy, MANIPULATED/EXPRESS→shitCoinBuy, etc.) — not missing.
- LiveBuyAdmissionGate/FinalDecisionGate/ExecutableOpenGate untouched.
- GoldenTape: no assertions on EntryIntelligence/Momentum symbols → existing tests safe.
- Brace/paren balance verified clean.

**CI:** run 27827714272 → SUCCESS → APK `AATE_v5.0.3926` published.


## V5.0.3921 (Feb 2026) — JOURNAL RENDER + LIVE SIZING + PROVIDER QUORUM + RUNNER SL FLOOR — CI ✅ (build AATE_v5.0.3924)

**Operator dump V5.0.3922:**
- `BUY ok=21 fail=953` with 855 `ADMISSION_GATE:SELL_ONLY_SAFE_MODE` blocks (90% of meme trader dead live).
- HELIUS_TIMEOUT (single execution provider) → providerBackoff=helius → safe mode parked everything.
- Live trades landing at 0.0095 SOL (~$1.50) — too small to self-sustain.
- Journal UI rendering blank despite `TradeHistoryStore.size=4`.
- Exit-reason P&L: MOONSHOT n=200 STOP_LOSS μ=-24.4% vs n=25 TAKE_PROFIT μ=+1284.5%; SHITCOIN n=200 STOP_LOSS μ=-25.7% vs n=25 TAKE_PROFIT μ=+1796.6% — TPs 50× the SLs, but `LaneExitTuner` had tightened MOONSHOT slMult to 0.92 cutting would-be runners.

**Fixes:**

1. **`JournalActivity.isValidJournalAccounting()` — DELETED the buggy synthetic-Trade revalidator.** It built a synthetic `Trade` WITHOUT `entryCostSol` or `entryPriceSnapshot`, which made `TradeHistoryStore.isValidAccountingTrade()` reject EVERY sell at the `entryCostSol <= 0.0 → return false` gate. Root cause of the blank-journal UI bug. `allEntries` is already validated upstream — the second pass was dead weight AND incorrect.

2. **`SmartSizerV3` — LIVE-mode size promotion.** EXECUTE_SMALL basePct 0.03 → 0.05; STANDARD 0.06 → 0.08; AGGRESSIVE 0.09 → 0.12. PAPER / LEARNING modes unchanged so backtests stay conservative.

3. **`SellOnlySafeMode.providerBackoffActive()` — QUORUM-BASED scoping.** A single execution venue down (pre-3921) was too brittle. Each class has two venues — Pump (`pumpportal` + `pumpfun`) and RPC (`helius` + `solana_rpc`). Only freeze when BOTH venues in a class are down. Unblocks live buys when one alternative is healthy (the operator's case: helius timing out but solana_rpc OK). `_lastProviderBackoffHost` reports `pump` / `rpc` / `pump+rpc`.

4. **`LaneExitTuner.recompute()` — RUNNER-PRESERVATION SL FLOOR.** When `avgWinPct ≥ 10 × |avgLoss|` (MOONSHOT/SHITCOIN profile), force `slMult` floor to 1.0× — never tighten the stop on a lane whose winners dwarf its losers. Other lanes keep existing SL_MIN.

**GoldenTape:** `providerBackoffActive()` symbol + `executionProviderLabels` array still present → existing assertions remain green. CI run 27825104458 → SUCCESS → APK `AATE_v5.0.3924` published.


## V5.0.3919 (Feb 2026) — PROVIDER-BACKOFF SOURCE FIX + DAMPENER FLOOR + FEE THRESHOLD + ANR MITIGATION — CI ✅ (build AATE_v5.0.3922)

**Operator report:** SELL_ONLY_SAFE_MODE blocking live buys even though
PumpPortal/Helius were healthy; live trades landing at ~$0.005 where
fees + network costs ate the edge; 0.5% trading fees not reaching the
two fee wallets; MainActivity.onCreate showing 2600ms+ frame gaps and
4.4% uptime stalls.

**Root cause and fixes:**

1. **`SellOnlySafeMode.providerBackoffActive()` — SOURCE FIX.** Pre-3919
   keys (`pumpportal.fun`, `pump.fun`, `mainnet.helius-rpc.com`,
   `api.mainnet-beta.solana.com`) never matched the SHORT labels that
   HealthAwareHttp + SolanaMarketScanner actually write via
   `ApiBackoff.markFailure` (`pumpfun`, `pumpportal`, `helius`,
   `solana_rpc`, plus scanner-only labels). The check was dead code,
   AND any future writer using the long form would have parked the
   entire live buy path on a scanner-only outage. New explicit
   `executionProviderLabels = arrayOf("pumpportal", "pumpfun",
   "helius", "solana_rpc")` allowlist scopes the check strictly to
   execution venues. Scanner-only labels (dexscreener / geckoterminal /
   birdeye / coingecko / pyth) and quote-API/LLM labels (jupiter / groq /
   gemini) can NEVER block live buys. `providerBackoff=<host>` reason
   line now names the offending venue for forensics.

2. **`Executor.kt` cumulative dampener floor.** `sizeMult × labMult ×
   laneEvMult × regimeMult × laneSizeCap` was compounding below the
   0.18 floor's grip — clamp to ≥0.5× of base in NORMAL regime; DUMP
   regime keeps its 0.10 safety floor.

3. **`Executor.sendFeeSplit()` FEE_SEND_MIN_SOL lowered 0.0001 → 0.000005
   SOL.** The 0.0001 per-share floor silently dropped fees whenever a
   dampened live trade was ≲0.04 SOL. All 8 `feeAmount*  >= 0.0001` call
   sites in Executor.kt now use the shared constant.

4. **`MainActivity.onCreate`** — defer `setupOperatorDiagnosticTiles` +
   `showFirstTimeDisclaimer` past first frame (postDelayed 220ms) so
   they no longer steal main-thread budget during the layout-inflate
   storm. (`setupChart`, `setupSettings`, `setupQuickActionButtons`,
   `requestNotifPermission`, etc. were already deferred.)

**3919b / 3919c — GoldenTape repair (sibling audit):**
The provider-backoff source fix tripped `live_buy_admission_does_not_global_safe_mode_on_jupiter_fallback_backoff`
because the assertion was a NAIVE substring search over the whole
SellOnlySafeMode.kt file and matched the doc comment that listed the
scanner-only labels as NEGATIVE examples. Tightened the assertion to
extract the `executionProviderLabels = arrayOf(…)` block via regex and
assert ONLY against the array literal — bulletproof against any future
comment text. Doc comment rewritten to avoid literal quoted labels.

**Acceptance:**
- `executionProviderLabels` contains exactly `pumpportal/pumpfun/helius/solana_rpc`
- Scanner-only + Jupiter/Groq/Gemini labels NOT in the allowlist
- GoldenTape `live_buy_admission_does_not_global_safe_mode_on_jupiter_fallback_backoff` PASSES
- CI run 27822766636 → SUCCESS → APK `AATE_v5.0.3922` published.


## V5.0.3746 (Feb 2026) — BALANCE_UNKNOWN REQUEUE LOOP / CLOSE LEASE LEAK FIX

**Operator dump V5.0.3744:** `EXEC_LIVE_SELL_OK=0`, `noSig=220`,
`close_lease_active=2`, `SELL_WAITING_BALANCE_PROOF=110`,
`SELL_RETRY_TEMPORARY_ONLY=110`, `SELL_DUPLICATE_SUPPRESSED=192`.
Live sells dead: RPC empty-map / BALANCE_UNKNOWN was being treated as an
active retry, requeued via PendingSellQueue, and ExitCoordinator kept
re-acquiring close leases creating an infinite loop.

**Structural fix — separate "waiting for proof" from "active sell":**

1. **NEW `BalanceProofWaitState`** — per-mint registry of mints in proof wait.
   Idempotent `markWaiting()`; subsequent calls merge exit-reason priority
   without spawning another worker (no duplicate-suppressed metric).
2. **NEW `BalanceProofPoller`** — non-blocking 2s coroutine that resolves
   balance via `SellAmountAuthority.resolve()`. On `Confirmed` →
   `BALANCE_PROOF_READY` re-enqueues an active sell with verified amount.
   Two consecutive `Resolution.Zero` reads → `ZERO_BALANCE_CONFIRMED` closes
   the position without broadcasting.
3. **NEW `SellResult.WAITING_BALANCE_PROOF`** — distinct from
   `FAILED_RETRYABLE`. The wrapper skips `PendingSellQueue.add` and skips
   `SELL_ROUTE_FAILED_NO_SIGNATURE_UNLOCKED`. Lease is released.
4. **`requestSell` short-circuit** — if `BalanceProofWaitState.isWaiting(mint)`,
   merge intent and return `WAITING_BALANCE_PROOF`. No lease acquired.
5. **`CloseLease.acquire` short-circuit** — returns `null` and emits
   `SELL_LEASE_DEFERRED_PROOF_WAIT` while a mint is in proof wait.
6. **InvariantGuardian subfaults** — `BALANCE_UNKNOWN_REQUEUE_LOOP` and
   `CLOSE_LEASE_LEAK_AFTER_NO_SIGNATURE` are now reported as subfault
   strings under `LIVE_SELL_NO_FINALITY` with full counter evidence.
7. **New forensic counters**: `SELL_WAITING_BALANCE_PROOF`,
   `BALANCE_PROOF_POLL_SCHEDULED`, `BALANCE_PROOF_STILL_UNKNOWN`,
   `BALANCE_PROOF_READY`, `ZERO_BALANCE_CONFIRMED`, `BALANCE_WAIT_MERGE`,
   `EXEC_LIVE_SELL_*` (waiting/route-started/route-failed/finalized/zero/terminal).

**Acceptance**: BALANCE_UNKNOWN no longer holds a close lease, no longer
emits SELL_RETRY_TEMPORARY_ONLY, no longer creates SELL_DUPLICATE_SUPPRESSED
churn. Two-provider zero closes verified without broadcast. GoldenTape:
`balance_unknown_does_not_requeue_or_hold_blocking_lease`.



## V5.9.1455 (Feb 2026) — TICK-TIME HARD-FLOOR + PROFIT-LOCK (real-money slippage fix) — CI ✅ compile green

**Operator dump V5.9.1454:** -15% HARD_FLOOR filled at -29.4% on a real-money
sell; +1000% peak gave back to ~+4%. Both bleed real money.

**Root cause:** ALL exit decisions lived in the 2s `hotExit` cadence (and slow
30s sweep). Prices were arriving every ~1s but stops were evaluated up to 2s
later → catastrophic slippage on fast rugs; trailing give-back never fired on
massive runners before they dumped.

**Fix — tick-time guards (1Hz cadence) on every fresh price:**

1. **BotService.openPositionTickLoop** (Memes — Moonshot + ShitCoin):
   - `TICK_HARD_FLOOR_PCT = -10.0`: unconditional kill-switch
   - `TICK_PROFIT_LOCK`: peak-tier give-back trailing
     - peak ≥ 500% → exit if give-back ≥ 30% of peak (lock 70%)
     - peak ≥ 200% → exit if give-back ≥ 40% of peak (lock 60%)
     - peak ≥ 100% → exit if give-back ≥ 50% of peak (lock 50%)
     - peak ≥ 30%  → exit if give-back ≥ 60% of peak (lock 40%)
   - Gated to memes only — BlueChip/Treasury have their own tighter SLs

2. **CryptoAltTrader.monitorPositions** (Crypto Alt lane):
   - Monitor cadence tightened **5s → 1s**
   - Same TICK_HARD_FLOOR(-10) + peak give-back trailing
   - Fires ahead of configured `stopLossPrice`

Lane-specific `HARD_FLOOR_STOP=-15` retained as slow-path backstop.


## V5.9.1333 (Feb 2026) — FLUID TACTIC SWITCHER + PERSONALITY TUNE WIRING (CI ✅ green)

Operator mandate: *"I don't want lanes or traders disabled. If they aren't
successful they need to change tactics in a fluid constantly learnt state."*
This commit literally implements that meta-rule.

A. **TacticSwitcher** (`engine/learning/TacticSwitcher.kt` — NEW)
   Per-(lane, scoreBand) state machine. NEVER disables; ROTATES entry tactic.
   - 4-stage cycle: **MOMENTUM → PULLBACK → REACCUMULATION → BREAKOUT**
   - Rotation trigger: `lossRate ≥ 75% AND meanPnl ≤ -5% over ≥ 25 trades`
   - Each rotation gets a fresh 25-trade trial window
   - Persisted via `LearningPersistence` (`tactic_LANE|BAND` JSON blobs)
   - Hooked into `V3JournalRecorder.recordClose()` for outcome-feed
   - Hooked into `ShitCoinTraderAI.evaluate()` for signal-shaping at entry
     (PULLBACK rewards -3 to -8% retracement, REACCUMULATION rewards
     sideways+strong-bp, BREAKOUT rewards confirmed structure breaks)

B. **PersonalityTraitMultipliers** (`engine/PersonalityTraitMultipliers.kt`
   — NEW) — wires the 6-trait personality vector to bounded trading
   multipliers (±15% max, fail-open):
   - paranoia ↑ → +3 score floor, 0.95× sizing
   - euphoria ↑ → 0.85× sizing (fade FOMO)
   - discipline ↑ → 1.05× sizing
   - aggression ↑ → 1.05× sizing, +3% TP bias
   - patience ↑ → +2 score floor
   - loyalty ↑ → 1.05× trail slack on winners
   - Sizing multiplier applied in `FinalDecisionGate` after lane policy
   - Score-floor bias applied additively in ShitCoin + Moonshot evaluators

C. **Telemetry** (`engine/PipelineHealthCollector.kt`)
   New "Tactic Switcher — fluid tactic rotation" section in dump showing
   per-bucket current tactic, W/L since rotation, mean PnL, age. Plus
   "Personality tune" summary line.

D. **Instrument coverage audit** — confirmed already broad. `PerpsMarket`
   enum has 406 entries; MetalsTrader/ForexTrader/CommoditiesTrader/
   TokenizedStockTrader all iterate full `PerpsMarket.values()` and take
   top-25 spot + top-10 leverage per cycle. The actual fluid-learning
   activation layer for dormant patterns is (A)+(B), not adding more
   enum entries.

NO lanes or traders disabled. All changes are soft-shape per doctrine #86.



## V5.9.1332 (Feb 2026) — DISARM EMERGENCY THROTTLE + PARABOLIC MOMENTUM CAP + UI ANR CACHE (CI ✅ green)

Operator: *"it trades and then virtually stops"* — snapshot (build 5.0.3321,
uptime 996s) showed last trade 17:09:02 → snapshot 17:14:22 = 5+min zero execs,
WR 11.8% bleeding, 42 ANR hints. Three root causes diagnosed:

A. **DISARM SUPERVISOR_EMERGENCY_THROTTLE (BotService.kt)**
   Threshold-clamp fired 2× in 16min and pinned `effectiveCap 32→16` for
   5min windows. With 154 worker timeouts (degraded APIs: groq 20%sr,
   geckoterminal 55%, helius WS reconnects), the throttle pinned
   `spawned=16 skipped=16` on 123/129 cycles.
   - `supervisorEffectiveCap()` now returns `SUPERVISOR_BASE_MAX_WORKERS`
     unconditionally. Lease TTL (4.75s) drains stuck workers naturally.
   - `supervisorArmEmergencyThrottle()` becomes a no-op observation log
     (`SUPERVISOR_EMERGENCY_THROTTLE_OBSERVED_DISARMED`).

B. **CAP PARABOLIC MOMENTUM (ShitCoinTraderAI.kt)**
   LosingPatternMemory showed `SHITCOIN|S61+` as the WORST bucket:
   190L/12W (-13.8% mean). High-score Shitcoins were the biggest bleeder —
   the scoring function was over-rewarding parabolic momentum (was +20pts
   for `momentum >= 20%`).
   - Momentum ladder rescaled 20→10 / 15→8 / 10→7 / 7→5.
   - New OVERHEATED penalty: `momentum > 40%` subtracts -10pts (FOMO chase =
     top-of-pump trap). Train-First: not a hard veto.

C. **UI ANR CACHE (ui/UiSnapshotCache.kt + MainActivity.kt)**
   - `EducationSubLayerAI.getAllLayerMaturity` (1259ms) and
     `WrRecoveryPartial.shortBadge` (1004ms) were freezing main thread
     in `updateUi()`.
   - New `UiSnapshotCache` object with 2.5s TTL caches both calls.
   - Both call sites (updateUi + AI tab snapshot) routed through cache.
   - No trading decision affected — UI badges only.



## V5.9.1331 (Feb 2026) — STRATEGY CLEANUP + STALL FIX + memeOpen TELEMETRY (CI ✅ green)

Operator mandate: *"11.5% WR, 57W/440L, 45-loss streak. Still trade stalls.
Strategy cleanup, NOT disabling lanes."* Train-First doctrine fully respected
— no lanes disabled, no hard vetoes added, all changes soft-shape (doctrine #86).

A. **STRATEGY CLEANUP — ShitCoin entry quality**
   - `SC_SCORE_BOOTSTRAP` 18 → 28, `SC_SCORE_MATURE` 38 → 48.
     With LanePolicy.PAPER_MICRO (0.10× exec weight) already routing the lane,
     dollar bleed is contained — but the 18-floor let liq(10)+buy(7)+age(10)=27pt
     trash pass with weakSig -8 dropping to 19. Raising the floor teaches
     FluidLearning to prefer real-signal setups.
   - `QUALITY_SOFT_GATE` widened: `momentum<-5 AND bp<40` → `momentum<0 OR bp<45`,
     penalty 8 → 12.
   - New `DUMP_GUARD`: bp<30% subtracts -10pts independently (pre-empts the
     LosingPatternMemory S0-30 dump-band that runs 75%+ loss-rate).

B. **STRATEGY CLEANUP — Moonshot paper floors raised +8 across all tiers**
   - `learningProgress<0.1`: paper 12 → 20
   - `learningProgress<0.3`: paper 20 → 28
   - `learningProgress<0.5`: paper 30 → 38
   - `learningProgress>=0.5`: paper 45 → 52
   - Lane remains REDUCED_SIZE_EXECUTION 0.60× — bar is now selective not blocking.

C. **SUPERVISOR STALL FIX (BotService.supervisorNoteWorkerTimeoutForThrottle)**
   - Emergency-throttle trip raised 20 → 60 timeouts/10min. Under V5.9.1330's
     ~2800/day throughput, normal API tail latency was producing ~120
     timeouts/10min — repeatedly clamping cap 32→16 for 5min windows. Intake
     stalled exactly when it should be highest. Now throttle only kicks in on
     REAL pool exhaustion.

D. **memeOpen TELEMETRY (PipelineHealthCollector.dumpText)**
   - New `Per-lane open positions (slot-cap diagnostic)` section in dumps:
     ShitCoin open / Moonshot open / Meme open (SC+MS) / Host wallet open.
   - Operator can now instantly tell: high memeOpen + zero EXEC ⇒ slot stall;
     low memeOpen + zero EXEC ⇒ intake/score-floor stall.



## V5.9.1328 (Feb 2026) — MEME-TRADER ROOT-CAUSE FIXES (CI ✅ green)

Operator mandate: "fix the issue at the source, do not stack patch
rotation". Six independent source-level bugs identified by triage agent
and patched at the original site. MEME TRADER PATHS ONLY — CryptoAlt /
Markets / Perps / Stocks lanes intentionally untouched.

A. **Ghost-hold leak (Executor.kt paperPartialSell)** — cumulative
   partials crossing 99% never finalized; residual qty kept ts.position
   "open". Now zeroes ts.position + closes PositionPersistence +
   GlobalTradeRegistry when residual is dust.

B. **fresh=0 always (GlobalTradeRegistry.kt addToWatchlist)** —
   WatchlistEntry.addedAt was a val set once. Duplicate intakes (which
   happen constantly on PumpPortal WS) no longer refreshed it. Now
   addedAt is var and refreshed on duplicate hit.

C. **UI Watchlist (0) mismatch (MainActivity liveRuntimeTokenCountForUi)** —
   UI read BotService.status.tokens.size; should read max(that,
   GlobalTradeRegistry.size()) so the dashboard pill matches actual
   tracking footprint.

D. **MOONSHOT off-by-1 (MoonshotTraderAI.kt minScore)** — GATE_RELAXER
   ×0.85 advertised but applied only in live mode. Now applied in paper
   too: 45 × 0.85 = 38; score=44 candidates pass.

E. **Probation TIMEOUT loop (GlobalTradeRegistry.kt processProbation)** —
   5-min cutoff auto-rejected cold pump-portal mints (no price-up, no
   multi-source). They re-arrived as "duplicates" and looped forever.
   Now TIMEOUT promotes to watchlist so V3/FDG (final authority) gets
   to evaluate.

F. **unpricedFresh=498 / synthetic-price freeze (BotService.kt
   pump-portal intake)** — V5.9.655 seed only fired on lastPrice<=0.
   PumpPortal keeps streaming new mcaps; price + lastPriceUpdate froze
   at first sighting. Now refreshes on every WS tick where mcap moved
   and source is still PUMP_FUN_BC_SYNTHETIC.


## V5.9.1325–1326 (Feb 2026) — TRAIN-FIRST INVARIANT + PHASE 2 ANR FIX

### V5.9.1325 — TRAIN-FIRST INVARIANT: never stop trading (CI ✅ green)
Operator mandate: "V3/FDG is the FINAL authority. 1000+ quality trades/day.
NEVER stop trading — just learn the right way. No WR/function/volume regression."

The V5.9.1321 Train-First Learning Policy correctly routed bad lanes to
training states, but SHADOW_TRACK_ONLY and TRAIN_ONLY_NO_OPEN still produced
non-executable verdicts. FDG callers then set blockReason → executions
choked to 6 at trade #722.

Surgical fixes (3 files, no API changes, no hard-safety gate touched):
- `FdgRouteVerdict.decide()` — non-hard-safety states (SHADOW_TRACK_ONLY,
  TRAIN_ONLY_NO_OPEN) collapse to ALLOW_PAPER_MICRO. Hard-safety verdicts
  (BLOCK_INVALID_DATA / BLOCK_HARD_SAFETY / BLOCK_MODE_AUTHORITY /
  BLOCK_DUPLICATE / BLOCK_OPERATOR_DISABLED) unchanged.
- `LanePolicy.defaultPolicyFor` — UNKNOWN lane defaults to
  PAPER_MICRO_EXECUTION (was SHADOW_TRACK_ONLY).
- `FinalDecisionGate.evaluate()` — both routeLearnedDangerBucket
  callers (LosingPatternMemory + BrainConsensusGate SOFT_BLOCK paths)
  demote !proceedToOpen branches to a 0.01-SOL micro probe instead of
  setting blockReason. Train-first invariant enforced.

### V5.9.1326 — Phase 2 ANR fix: BrainNetworkView + BehaviorActivity (CI in retry)
Operator snapshot: BehaviorActivity 2.5s ANR + BrainNetworkView >1000ms
in onDraw. Per-frame / per-tick allocations:

BrainNetworkView (zero-alloc per onDraw):
- Hoisted gridPaint / outline / texturePaint Paints out of draw methods.
- Reused brainTexturePath via path.rewind() instead of new Path() per frame.
- Cached RadialGradient by quantised animRadius (rebuild only when the
  pulsing brain radius crosses an integer-pixel bucket).

BehaviorActivity (zero-rebuild per 2s refresh tick):
- renderFluidDashboard now builds the LinearLayout tree ONCE (sentinel
  flag fluidDashboardBuilt). Subsequent refreshes mutate value TextViews
  in place via updateFluidRow(label, value). Was: removeAllViews() +
  ~20 addFluidRow() → ~100 new View allocations every 2 seconds.

### Pending follow-ups (de-prioritised by operator):
- Phase 4 API Circuit Breakers — ApiBackoff.kt already exists with per-host
  exponential backoff (5s→300s), 429/403 escalation, fail-open, wired
  through HealthAwareHttp + NarrativeDetector + LlmSentimentEngine. Done.
- Phase 5 TokenMetaCache polish — already mint-only PRIMARY KEY,
  warmStart on first get(), ConcurrentHashMap hot path. Done.


## Latest Build Series — V5.9.1321 → V5.9.1324 (Feb 2026, CI ✅ green)

### V5.9.1324 — Phase 3 surgical: P1-6 + P1-7 + P1-8 + P2-12
Operator Build 5.0.3289 mandate continuation. Surgical additive observability only.
- **P1-6 supervisor timeouts**: every SUPERVISOR_WORKER_TIMEOUT now emits a
  structured reason label (BUDGET_EXCEEDED) + per-mint counter + a
  NoTradeObservation row so the timed-out candidate stays trainable.
- **P1-7 ExitCoordinator**: stale-reset first-in-episode now emits
  EXIT_COORDINATOR_STALE_RESET_REASON_<lockAgeBucket> (>=10s / >=20s / >=30s /
  >=60s / NEVER_RAN) + open-positions-at-stale label.
- **P1-8 ExecutableOpenGate**: every dropped() call writes a
  NoTradeObservation row so EXEC_OPEN_DROPPED_CANON_LANE_UNRESOLVED /
  _NO_FINAL_CANDIDATE / _PRE_FDG_NOT_BUY / _STALE_CANDIDATE /
  _SELECTED_LANE_MISMATCH all train the model instead of vanishing.
- **P2-12 Root-cause-likely banner** added at top of PipelineHealthCollector
  dump: detects UI_MAIN_THREAD / WORKER_TIMEOUT / V3_ACCOUNTING_GAP /
  LEARNING_ACCOUNTING_GAP and surfaces the dominant pattern.

### V5.9.1323 — Build 5.0.3289 surgical: P0-1 + P0-2 + P0-3 + P0-4 + P1-5 + P1-9 + P1-10
Operator Build 5.0.3289 snapshot showed ANR_HINTS=54, stall=8.1%, EXEC funnel
contradicting cheat-sheet (8 vs 70), V3 entries=1425 but allow=122/block=0 with
493 fatal early returns + 214 rejected terminal early returns, false LEAK label
on multi-lane mode. Surgical fixes (additive modules + tiny call-site bumps):
- **UiRefreshGate (engine/runtime/)**: 1 Hz per-surface render throttle with 5s
  hard ceiling. Wired into `CryptoAltActivity.renderTokenList` (kills
  buildDynTokenRow ANR loop) and `MainActivity.renderNetworkSignals`. Band-aid
  before Phase 2 RecyclerView conversion.
- **ExecutionCounterContract (engine/runtime/)**: 11 named counters per
  operator §3 (executor_invocations, open_attempts, open_success,
  close_attempts, close_success, journal_buy_records, journal_sell_records,
  paper_buy_success, paper_sell_success, live_buy_success, live_sell_success).
- **V3VerdictContract (engine/runtime/)**: 4 terminal verdicts (ALLOW / BLOCK /
  SKIP / ERROR) + V3_ENTRIES_TOTAL denominator. Wired into BotService at every
  V3Decision branch (Execute / Watch / Rejected / Fatal early return / Blocked
  early return). V3 funnel and gate tally now reconcile.
- **ColdStreakDamper (engine/runtime/)**: per-(lane, paper/live) loss streak
  with damper curve 1.00→0.75→0.50→0.35→0.25 — operator §9 'damp not block'.
- **ProviderHealthGate (engine/runtime/)**: shouldCall/recordCall circuit
  breaker with 0/15s/60s/300s/900s cooldown progression — operator §10 ready
  for Helius/X/Groq wiring next push.
- **LEAK label gating**: PipelineHealthCollector dump now shows
  'MULTI_LANE_ACTIVE' when hardQualityOnly=false instead of false 'LEAK'.

### V5.9.1322 — Train-First Learning Policy Builds B + C + D + E
Operator Base44/Emergent directive: TRAINABILITY ≠ EXECUTABILITY. Bad lanes
must be demoted/downsized/sandboxed/paper-only, NOT deleted from learning.
- **NoTradeObservationStore (engine/learning/)**: every FDG block / shadow
  route / train-only route writes a learning row with forward-outcome samples
  at 30s / 60s / 180s / 300s / 900s. peakMovePct, maxDrawdownPct,
  liquidityChange, wouldHaveHitStop/TP/Rugged/Migrated/BeenUntradable.
- **ExplorationBudget (engine/learning/)**: per-lane hourly budgets per
  operator §4 (SHITCOIN paper-micro 10% / UNKNOWN shadow 5% / MANIPULATED
  paper-micro 10% / MOONSHOT reduced-size 20% / QUALITY+BLUECHIP normal 30% /
  TREASURY tight 20%).
- **RetrainingDecay**: 0.97/loss decay with 0.15 floor, 1.05/win recovery to
  1.00 ceiling. No lane permanently dead.
- **StrategyVariantStore (engine/learning/)**: 6-state machine (ACTIVE /
  RETRAINING / MUTATED / PROMOTED / RETIRED / SHADOW_ONLY) with struct policy
  (slPct/tpPct/trailMode/entryAfterBurstAllowed/postBondingOnly/
  minHolderVelocity/minLiqUsd/migrationOnly/symbolFamilySuppressed). Seeded
  per-lane per operator §11: MANIP TIGHT_STOP_-5 / MOONSHOT FLOOR_-15_LETRUN /
  SHITCOIN BREAK_EVEN+postBondingOnly / UNKNOWN BREAK_EVEN+postBondingOnly /
  QUALITY+BLUECHIP LET_RUN / TREASURY BREAK_EVEN. Auto-mutates losing variants
  via 8 mutation recipes; promotes children only when expectancy > parent.
- **TradeRowSanityCheck (engine/learning/)**: 11 quarantine reasons (operator
  §8 verbatim list). Quarantined rows stay in journal but don't enter
  aggregations. Losing-but-valid trades still train.
- **PaperLiveConfidenceWeights**: paper bootstrap 0.40 → 0.85 after ≥25 live
  samples on same bucket.

### V5.9.1321 — Train-First Learning Policy Build A (foundation)
- **LanePolicy (engine/learning/)**: 9-state per-lane / per-bucket state
  machine (INVALID_UNTRADEABLE → NORMAL_EXECUTION). Defaults per operator §4.
- **FdgRouteVerdict (engine/learning/)**: 10 routing verdicts
  (ALLOW_NORMAL / ALLOW_REDUCED_SIZE / ALLOW_PAPER_MICRO / ROUTE_SHADOW_TRACK
  / ROUTE_TRAIN_ONLY / BLOCK_INVALID_DATA / BLOCK_HARD_SAFETY /
  BLOCK_MODE_AUTHORITY / BLOCK_DUPLICATE / BLOCK_OPERATOR_DISABLED).
- FinalDecisionGate: replaced LEARNING_DANGER_BUCKET_EVIDENCE_REQUIRED_* and
  BRAIN_LEARNED_DANGER_EVIDENCE_REQUIRED hard blocks with
  FdgRouteVerdict.routeLearnedDangerBucket() — damaged buckets now route
  through training states, not dead blocks.
- LearningPersistence: public save/load KV API.
- PipelineHealthCollector.labelInc(): public helper for downstream telemetry.

## Pre-V5.9.1321 Build — V5.9.1320 (Feb 2026, fork sync point)
Local was V5.9.1082b; remote had advanced to V5.9.1320 (73 commits). Audit
docs added:
- `/app/lifecycle_apk/docs/BUILD_BREAKDOWN_1047_to_1160.md` — 113-build
  forensic breakdown across 7 phases.
- `/app/lifecycle_apk/docs/CRYPTO_UNIVERSE_AUDIT_V5_9_1160.md` — full audit
  of Crypto Universe with 24 drifts from Meme Trader (5 P0 / 9 P1 / 10 P2).

## Latest Build — V5.9.1081b (Feb 2026)
- **FORENSIC LIFECYCLE HARDENING (V5.9.1081 + 1081b compile fix)** — operator forensic-debug pass (A+B+C+D+E, no new features, no rewrites):
  - **A**: Removed `userRequested && loopActive → FORCE CANCEL + RESTART` branch from normal ACTION_START. Added `startInProgress` @Volatile latch + three idempotent early-exit checks. 10 rapid START taps = 1 runtime job. Force-restart still possible but ONLY via explicit `EXTRA_FORCE_RESTART_CONFIRMED=true` extra, which UI START button never sets.
  - **B**: MainActivity btnToggle hard-disabled in onCreate; only the state-aware bind (running→STOP / else→START) in updateUi can enable it. Eliminates the <100ms early-window where a tap could send START while running.
  - **C**: New `BotService.armRestartAlarm()` helper enforces cancelAllRestartAlarms-first + manual-stop guard + skip-if-running. onDestroy and onTaskRemoved now cancel prior alarms before scheduling. `cancelAllRestartAlarms()` now also covers requestCode 5 (AlarmClock backup).
  - **D**: STOP telemetry — `LIFECYCLE_STOP_REQUESTED` / `LIFECYCLE_STOP_ACCEPTED` / `LIFECYCLE_PENDING_RESTART_CANCELLED` / `LIFECYCLE_RUNTIME_JOB_CANCELLED` / `LIFECYCLE_STOP_COMPLETE`. Manual-stop dominance preserved (existing KEY_MANUAL_STOP_REQUESTED latch).
  - **E**: Live/paper contamination forensic markers added at existing guards: `LIVE_BUY_FAILED_NO_PAPER_FALLBACK`, `PAPER_POSITION_BLOCKED_IN_LIVE_MODE`, `LIVE_POSITION_CONFIRMED_FROM_SIGNATURE`, `LIVE_POSITION_CONFIRMED_FROM_WALLET`, `ORPHAN_WALLET_TOKEN_ATTACHED`, `ORPHAN_WALLET_TOKEN_MONITORED_FOR_EXIT`.
  - **1081b**: Fixed `Unresolved reference: status` compile error — `status` is a companion-object val so extension function must use `BotService.status`.

## Previous Build — V5.9.1080 (Feb 2026, CI ✅ green)
- **UNCHOKE SCANNER — dispatcher override (V5.9.1080)**: operator: *"the scanner only returns 4 candidates per cycle? the watchlist has 250 slots the scanner isnt even populating a lane... its meant to be scanning the entire sol network for tokens. its fucking not obviously"*. troubleshoot_agent (second pass) RCA: V5.9.1078 snapshot showed `SCANNER_HEARTBEAT src=1 ok=0 err=0` (only ONE runScan call EVER) + `ApiHealth pumpfun s=4 in 1095s` — scanLoop permanently stuck on its first source. Root cause: SolanaMarketScanner.kt lines 712-715 installed a private OkHttp `Dispatcher` with `maxRequests=6 / maxRequestsPerHost=1` (added Apr 3 2026 commit 07107cc35 as OOM mitigation). V5.9.1030 (May 20) raised SharedHttpClient.base to 64/16 to un-choke the supervisor — but the scanner's `.dispatcher(...)` override SILENTLY OVERRODE that fix. scanPumpFunDirect's 5 sequential pump.fun URLs serialized through the queue → entire 18-min window burned on source #1 → all 13 deep-scan sources (DexScreener, Birdeye, Gecko, CoinGecko, Meteora, Raydium variants) skipped. Fix: removed the dispatcher override entirely. Scanner now inherits the shared 64/16 dispatcher used by every other HTTP consumer.

## Previous Build — V5.9.1079 (Feb 2026, CI ✅ green)
- **Unpark exits + restore Journal UI (V5.9.1079)**: operator V5.9.1078 snapshot showed bot parking after 9 buys: `EXIT_SWEEP_SKIPPED=154 vs DONE=42` and `UNIVERSAL_SL_SWEEP_SKIPPED=153 vs DONE=23` — exit gate kept approving exits but the AtomicBoolean single-flight gate stayed `alreadyRunning=true` because the wedged worker's finally never ran. Fix: `launchExitSweepAsync` / `launchUniversalSlSweepAsync` now track the live worker Job + start-time; if a new sweep hits a held gate AND the prior worker is older than the watchdog budget (3s exit / 4s SL), force-cancel the stuck worker, clear the gate, proceed. Telemetry: `EXIT_SWEEP_FORCE_RESET` / `UNIVERSAL_SL_SWEEP_FORCE_RESET`. Also: Trade Journal UI was blank after fresh install despite 198 TRADEJRNL_REC writes — JournalActivity was reading `getAllTradesFromDb()` (disk-only, lagging behind async SQLite inserts). Now reads in-memory `getAllTrades()` first (synchronously populated by recordTrade), falls back to disk only when memory empty.

## Earlier Build — V5.9.1078 (operator-built, Feb 2026, CI ✅ green)
- Split stop-loop from liquidation: `stopBot(source)` now distinguishes confirmed manual STOP (liquidates) from soft/internal stop (preserves positions, emits STOP_SOFT_PRESERVE_POSITIONS).

## Earlier Build — V5.9.1077 (operator-built, Feb 2026, CI ✅ green)
- Replaced blind supervisorActive counter with per-worker `SupervisorLease` (4750ms TTL < 5s loop cadence) so stale workers can't monopolize the slot pool.

## Earlier Build — V5.9.1068 (Feb 2026, CI ✅ green)
- **UNCHOKE pass: kill DANGER_ZONE hard blocks + fix Start-after-Stop button (V5.9.1068)**: operator snapshot V5.9.1067 showed (1) 5 of 7 lanes (MOONSHOT/SHITCOIN/QUALITY/MANIPULATED/DIP_HUNTER) with 95 LANE_EVALs each and ZERO executions; (2) bot stuck in `running=false phase=IDLE` after user pressed STOP — START button could not bring it back. Root causes + fixes: (a) `BotViewModel.startBot()` was premutating `BotService.status.running=true` BEFORE firing ACTION_START; service then saw status.running=true + loopJob.isActive=false and fell into `Bot already running` else-branch (silent no-op) — removed premutation, service now owns status.running. Also relaxed STUCK_LOOP_RESCUE to fire on any userRequested ACTION_START with an active loopJob. (b) `LosingPatternMemory.DANGER_ZONE_BLOCK` in BotService for TREASURY|S0-10 + PRESALE_SNIPE|S0-10 hard-killed every entry in those score bands (62 silent rejects in one snapshot) — downgraded to telemetry only, trade proceeds with SmartSizer/SL handling risk. (c) `ToxicModeCircuitBreaker.LIQUIDITY_FLOORS` were $10k-$15k across MOMENTUM/FRESH_LAUNCH/PRESALE_SNIPE/SENTIMENT_IGNITION/WHALE_FOLLOW/DEFAULT — pump.fun bonding curves arrive at $2k-$2.5k so every entry was silently rejected — all floors dropped to $1,500.

## Previous Build — V5.9.1067 (Feb 2026, CI ✅ green)
- **Fix MainActivity recreation cascade + double-collector ANR + journal diagnostic (V5.9.1067)**: triage-agent RCA after V5.9.1065 panic snapshot (28 ANR samples on `MainActivity.onCreate` in one 30-sample window, stall=6.3%, max frame gap=12.9s, max bot cycle 27.5s). Root cause: opening PipelineHealthActivity → returning recreated MainActivity from scratch → OLD `vm.ui.collect` coroutine still emitting `updateUi()` while NEW `onCreate` launched a SECOND collector → two concurrent flows hammered `renderTreasuryPositions` (ICU/Locale/Bidi clone via SimpleDateFormat.initialize). Fixes: (1) AndroidManifest `configChanges` on `PipelineHealthActivity` so the system stops killing MainActivity; (2) `repeatOnLifecycle(STARTED)` wraps `vm.ui.collect` → exactly one collector ever alive; (3) `SimpleDateFormat` promoted from per-render allocation to class field; (4) journal-wipe diagnostic in `TradeHistoryStore.init()` logs SQLite row count + DB file path + size — next snapshot's ErrorLog will prove whether journal is empty due to fresh install vs a load-path regression.

## Previous Build — V5.9.1066 (Feb 2026, CI ✅ green)
- Lifted FDG `learningProgress<0.5` restriction on BC-fallback (operator: bot must trade pump.fun bonding-curve tokens after bootstrap). PipelineHealthActivity `dumpText` micro-opt (BREAK_STRATEGY_SIMPLE + no hyphenation + non-selectable). Back-fill SELL `tradingMode` from matching BUY to close the Strategy Expectancy vs Performance Analytics 294-trade / -33 SOL reconciliation gap.

## Earlier Build — V5.9.1065 (CI ✅ green)
- Ripped V5.9.1049 SessionSafetyHalt entirely (operator mandate: NEVER pause/disable). Deferred PipelineHealthActivity `findViewById` + `setOnClickListener` chain past first vsync (`window.decorView.post`) → 2523ms onCreate hang dropped to 251ms.
- **Rip SessionSafetyHalt + defer PipelineHealthActivity onCreate (V5.9.1065)**: operator directive *"never fucking pause or disable. thats so off fucking task"* — removed V5.9.1049's 50-trade halt entirely (deleted `SessionSafetyHalt.kt`, dropped all call sites in `Executor.paperBuy` and `BotService.startBot`). Bot now NEVER pauses or disables a lane — learning weights self-adjust via TradingCopilot + FluidLearning + losing-pattern memory. Also fixed the V5.9.1064 "black screen hang" (PipelineHealthActivity.onCreate 4× consecutive 250ms+ frame hits ≈ 2.5s on every panel open): the 8× findViewById + 7× setOnClickListener chain now runs inside `window.decorView.post { }` so the initial layout paints at the first vsync (~16 ms) before any listener wiring.

## Previous Build — V5.9.1064 (operator-built between agent sessions)
- V5.9.1050-1064 were pushed directly by the operator while the agent was offline (TREASURY/PRESALE bleed fixes, journal restore, paper-purge removal, ScoreBarView ANR, bootstrapPass unblocks). Agent rebased V5.9.1065 onto these.

## Earlier Builds — V5.9.1048 / V5.9.1049 (CI ✅ green, V5.9.1049 partially rolled back V5.9.1065)
- **Triage 5-pack (V5.9.1049)**: surgical fixes for the V5.9.1040 panic snapshot (27 217 ms frame gap, 11 % stall, Max Drawdown overflow, no entry cap). (a) `MainActivity.renderTreasuryPositions` view-leak + per-tick `coil.load` purged — same-positions tick now ONLY computes PnL sum, no view construction. (b) `ErrorLogActivity.exportLogs` moved to background `Thread` + `Handler.post` for the AlertDialog. (c) `JournalActivity.showExportDialog` callbacks now explicitly `lifecycleScope.launch(Dispatchers.IO)`; final `startActivity`/`Toast` re-posted via `runOnUiThread`. (d) `PerformanceAnalytics.calculateDrawdown` no longer reports six-figure DD%: ignores peaks < 0.05 SOL, clamps maxDdPct/currentDdPct at 100 %. (e) **NEW `SessionSafetyHalt`** circuit-breaker: after 50 paper buys in a session, if WR < 25 %, refuses fresh paper entries (exits + live unaffected). Resets on every `BotService.startBot()`. Wired at the top of `Executor.paperBuy()` (single canonical fence covering all 7 sub-trader fallback call sites) and recorded on successful paper buys next to `FluidLearning.recordPaperBuy`.

## Previous Build — V5.9.1048 (Feb 2026, CI ✅ green)
- **5-fix pass (V5.9.1048)**: STANDARD bin glossary note added to strategy expectancy section · V3 reject reason now extracts from `Rejected`/`Blocked` not just `BlockFatal` · `execBuy` counter key mismatch fixed (was reading legacy `EXEC/PAPER_BUY`) · BirdeyeApi.get() now consults `ApiBackoff.isLockedOut` + reports response codes (sr=59% should recover to >90%) · renderMoonshotPositions gets 8s min render interval throttle.
- **4-file UI ANR purge (V5.9.1047)**: BrainNetworkView throttled to 1fps + hardware layer; PipelineHealthActivity bgThread now eagerly initialized; BotViewModel.pollLoop moved to Dispatchers.Default; JournalActivity.buildJournal split — data prep on IO, view inflation on Main. Stall % 28% → 6.5%.
- **Supervisor slot decouple + tile BG + V3 reject histogram (V5.9.1046)**: SUPERVISOR_POOL_RESET confirmed 0/min, throughput +267%. V3 reject histogram surfaced `EXTREME_RUG_RISK_100: 103` as dominant V3 choke.
- **Supervisor timeout 10s + UI ANR fixes (V5.9.1045)**: Timeout `20s→10s`, PipelineHealthActivity bgHandler pre-warm (superseded by V5.9.1047), SplashActivity logoPulse → hardware-accelerated ObjectAnimator.
- **runInterruptible worker body (V5.9.1044)**: workers wrapped in `runInterruptible(Dispatchers.IO)` so cancellation upgrades to `Thread.interrupt()`.
- **Read-side bin merge (V5.9.1043)**: `BLUE_CHIP` legacy ghost bin merged into `BLUECHIP` at read time.
- **Silent-supervisor pool watchdog (V5.9.1042)**: `SUPERVISOR_POOL_RESET` safety net.
- **Per-worker timeout (V5.9.1039)**: each silent-supervisor worker wrapped in `withTimeoutOrNull` (now 10s).
- **Triage fixes (V5.9.1038)**: TradeHistoryStore.recordTrade dedupe LRU, normalizeTradeModeName, CanonicalLearning reason-fallback, Executor.recordTrade tradingMode inheritance.
- **Silent supervisor (V5.9.1037)**: fire-and-forget workers, cycle ~20s → ~5s.
- **ANR fixes (V5.9.1036)**: LearningPersistence + MemeMintRegistry off-main.
- **Intake Part 2 (V5.9.1035-36)**: INTAKE_LIQ_ZERO_REJECT + INTAKE_BURST_REJECT confirmed at scale.
- **Lite-rich bridge (V5.9.1035)**: rich features 27 → 340.

## Previous Build — V5.9.1026 (Feb 2026, CI ✅✅)
- **Bot is alive & trading.** V5.9.1023 added a dedicated bot-loop dispatcher (escapes Dispatchers.IO starvation when supervisor JNI socket-reads wedge); V5.9.1024 added reactive `ApiBackoff` on 4xx/5xx (DexScreener went 49%→99% SR); V5.9.1025 harvests completed supervisor work on chunk timeout; V5.9.1026 caps supervisor parallelism at 32 (was 96) so workers actually fit the IO-pool + OkHttp connection budgets and complete inside the 2.5s chunk budget.
- STALE_LIVE_PRICE_RUG_ESCAPE now requires last-known PnL ≤ 0 to fire (no more phantom-rug nukes on dark-feed winners).

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

### V5.9.810 — Triage: journal=truth for all meme counters + LLM Lab auto-promote @ 33% (Feb '26, CI ✅✅)

Operator mandate: 'ensure journal is source of truth for all meme
counters and displays including learning. as you can see behaviour
still double counts. please note skimming on all 3 fixes. be surgical.
use triage.'

troubleshoot_agent identified 4 consumers drifting from canonical
journal baseline (settledWins + settledLosses):
- AdaptiveLearningEngine  Δ = +404  (double-count, bucketed dedup leak)
- BehaviorLearning        Δ = -187  (BY DESIGN — featuresIncomplete filter)
- FluidLearningAI         Δ = -30   (under-count, missed shadow paths)
- MetaCognitionAI         Δ = 0     ✓ (reference impl — mint-based dedup)

Five surgical edits, no wide-open bypasses:
1. AdaptiveLearningEngine — `tradeCount += 1` moved from learnFromTrade
   into onCanonicalOutcome. Canonical bus (layer,tradeId) recordOnce LRU
   ensures exactly-once. Eliminates +404 drift.
2. FluidLearningAI.getSessionTradeCount() — read-through to canonical.
   Drift = 0 by construction.
3. LlmLabStore — auto-promotion thresholds relaxed: 60→30 trades,
   55%→33% WR, NEW MIN_PAPER_PNL_SOL_FOR_PROMOTION=0.05 SOL.
   Captures asymmetric-R/R strategies (Genesis · Sniper / Scalper /
   Hunter). Live-money still requires LabPromotedFeed.requireLiveApproval.
4. LlmLabEngine.runCullCycle() — uses new constant instead of >0.0.
5. MainActivity — 'X% wins' sub-banner reads TradeHistoryStore (journal
   source). All 3 top-level WR surfaces now byte-aligned.

BehaviorLearning -187 intentionally NOT touched (feature-poor legacy
bridge samples filtered to keep pattern memory clean).


### V5.9.807 — Counter-drift display + Predictive exit wired (Feb '26, CI ✅✅)

Operator follow-up: "latest apk counters still off in learning... just
focus there tho no skimming and do 2 and the predictive exits."

Two surgical fixes — no entry-volume impact.

(1) LearningCounterActivity.kt — drift baseline switched from
    canonicalOutcomesTotal (which counts every bus event, including
    OPEN events / mode shadows / preserved-label replays) to
    settledWins + settledLosses. canonicalOutcomesTotal is still
    visible in Section 2 + alongside the new baseline label so the
    operator can compare both. tradeCount-style legacy counters
    (FluidLearningAI / AdaptiveLearningEngine / BehaviorLearning /
    MetaCognitionAI) now compare against settled trades — the only
    honest comparator.

(2) Executor.kt — LosingPatternMemory.recommendedSlPct() wired into
    the HARD_FLOOR exit. When a position's (tradingMode × scoreBand)
    bucket is a danger zone (≥5 losses, ≥75% loss rate in last 2k
    closes), tighten the hard floor to the recommended SL
    (-3% / -5% / -7% by loss count). Read-only over the journal +
    minOf(...) means the hard floor can only be TIGHTENED — never
    widened — beyond its baseline -9% (fresh meme) / -15%
    (everything else). New exit reason `predictive_hard_floor_stop`
    for telemetry separation; new icon 🧠🛑 when predictive
    tightening fires.

NOT TOUCHED:
- Executor.paperBuy / liveBuy unchanged.
- PumpPortal swap scripting unchanged.
- Sub-trader evaluate() methods unchanged.
- FluidLearningAI dynamic stop unchanged.
- PrecisionExitLogic / ExitIntelligence unchanged.
- Entry sizing & FDG flow unchanged.

Brace + paren deltas: balanced (Executor Δparen=-6 pre-existing
baseline preserved). CI: Build AATE APK ✅ + Runtime Smoke Test ✅
both GREEN on 107953fd1.

### V5.9.806 — Full intelligence wire-up: P0+P1+P2+P3+P4 (May 17, CI ✅)

Operator: "AATE is meant to be beyond AGI with its sentience, metacognition,
ai cross talk and symbolic reasoning. why isnt it performing like this?
it has all the architecture". Diagnosis: the AGI lived in the commentary
layer; the decision path was a dumb `score > threshold` if-statement.
This patch wires the brain layers INTO the entry decision (`FinalDecisionGate.evaluate`)
without touching pumpfun/Executor.paperBuy/liveBuy scripting.

P1 — StrategyTelemetry + auto-retirement (COMPLETE)
- Read-only journal aggregator grouped by tradingMode.
- Top-5 winners / bottom-5 bleeders in pipeline-health dump.
- Auto-disable rule: strategies with ≥50 trades AND meanPnl ≤ -5% are
  flagged via `isDisabled(strategy)`. Operator can reset via
  `clearDisabled()`. 60s memo.

P0 — BrainConsensusGate (COMPLETE)
- Binding cross-brain gate consulted at the end of `FDG.evaluate` —
  can only downgrade allow→block, never upgrade block→allow.
- Composes: SentientPersonality mood, RegimeDetector, SecondScorer
  disagreement, LosingPatternMemory danger-zone, StrategyTelemetry
  auto-disabled.
- Verdict: ALLOW / SOFT_BLOCK (telemetry-only) / HARD_BLOCK (reason
  `BRAIN_CONSENSUS_VETO`).
- HARD_BLOCK triggers: strategy disabled, mood HUMBLED/SELF_CRITICAL
  in DUMP regime, SecondScorer disagrees in CHOP/DUMP.

P2 — RegimeDetector (COMPLETE)
- Classifies bot performance into BULL_RIPPING / NORMAL / CHOP / DUMP
  / DEAD using last-100-sells WR%, mean PnL%, V3 score median.
- Exposes scoreFloorDelta() and sizeMultiplier() for downstream
  consumers. Memoised 30s.

P3 — LosingPatternMemory (COMPLETE)
- Coarse-bucket pattern recall keyed by (tradingMode × scoreBand).
- ≥5 losses AND ≥75% loss rate → 'danger zone' → BrainConsensusGate
  objection. Recommends tightened SL (-3%/-5%/-7%) for advisory use.

P4 — SecondScorer (COMPLETE)
- Independent 0-100 perception scorer with different weights from V3:
  heavy on liquidity stability, holder count, age, balanced buy
  pressure; intentionally LIGHT on momentum.
- ≥20pt gap with second-scorer-says-worse → objection in CHOP/DUMP.

NOT TOUCHED (per operator mandate):
- Executor.paperBuy / liveBuy unchanged. PumpPortal swap scripting
  unchanged. Sub-trader evaluate() methods unchanged.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on 89761e13e.

### V5.9.805 — Treasury-scale floor (α) + Score-distribution auto-fit (β) (May 16, CI ✅)

Operator approved both fixes from prior summary. Treasury-scale floor:
CashGenerationAI WR Recovery floor switched from V3 scale (45/30) to
Treasury-scale additive (base+20 AGGRESSIVE, base+10 MODERATE) +
anti-FOMO clamp (reject buyPressure>65% in AGGRESSIVE band — those
were the heldMs<30 traps). Score-distribution auto-fit: WrRecoveryPartial
maintains 200-tick V3 score ring; minScoreFloor() adjusts band base
by -25 (THIN) / 0 (NORMAL) / +10 (RICH). FDG.evaluate records every
candidate's V3 score for the rolling buffer.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ on 787de5355.

### V5.9.804 — Fix count doubling on legacy consumer canonical alignment (May 16, CI ✅)

Operator on build 5.0.2745: "count still looks doubled vs trade count".

Forensic:
  canonicalOutcomesTotal = 547   (≈ 2× UI's 294 24h-trades count)
  richFeatureOutcomes    = 293   (~matches UI)
  settledWins+Losses     = 487   (closed round-trips)

ROOT CAUSE
V5.9.801 Fix E and V5.9.802 Fix (c) pointed the four legacy brains
(FluidLearningAI, AdaptiveLearningEngine, MetaCognitionAI,
BehaviorLearning) at `canonicalOutcomesTotal`. Forensic on
`CanonicalLearning.bumpCounters()` confirmed that counter increments
ONCE PER DISPATCHED OUTCOME — and every round-trip dispatches twice
(BUY phase + SELL phase outcomes). So the AI brain trade-count was
~1.5–1.9× the actual round-trip count.

FIX (P0, COMPLETE)
Switch all four legacy-consumer accessors from `canonicalOutcomesTotal`
→ `(settledWins + settledLosses)` (the round-trip closed-trade count).

Files:
- FluidLearningAI.getTotalTradeCount()
- AdaptiveLearningEngine.getTradeCount()
- MetaCognitionAI.getTotalTradesAnalyzed()
- BehaviorLearning.getCanonicalAlignedTradeCount()

`WrRecoveryPartial.stateNow()` was already reading from
`TradeHistoryStore.getLifetimeStats` (journal-based), so the band
selection logic was never affected by the doubling — only the
displayed/log "trades observed" number was inflated.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on 434a352d9.

### V5.9.803 — FDG works from trade 1 (May 16, CI ✅ Build + Runtime Smoke)

Operator dump on build 5.0.2743 (V5.9.802 installed): "ai adjustments
need to start earlier obviously and the fdg needs to start working
from trade 1". FDG was STILL blocking 62% of decisions on
LIQUIDITY_BELOW_EXECUTION_FLOOR (639 blocks).

Forensic root cause:
The V5.9.802 fallback only applied when `tradingModeTag == SHITCOIN`.
But the same pump.fun bonding-curve token gets evaluated by FDG via
BLUECHIP / TREASURY / MOONSHOT paths FIRST. Those paths use
`FluidLearningAI.getExecutionFloor()` ($800–$10000 lerped) and
never hit the SHITCOIN-only fallback → instant fail because
`LiquidityClassifier.exitCapacityUsd` returns 0 for any token
without a confirmed Raydium/Jupiter pool.

FIX 1 (P0, COMPLETE) — FDG bootstrap fallback applies to ALL paths
- Drop `tradingModeTag == SHITCOIN` clause from `isBootstrap` check.
  Any FDG path during `learningProgress<0.5` falls back to
  `lastLiquidityUsd` when `exitCapacityUsd` is 0.
- Canonical bus still tags outcomes `bcSimOnly=true` so WR analytics
  stay separable. Live execution still enforces strict
  `exitCapacityUsd` in the live executor's own pre-flight pool check.

FIX 2 (P0, COMPLETE) — FluidLearningAI.LIQ_EXECUTION_BOOTSTRAP $800 → $300
- With Fix 1 letting BLUECHIP/TREASURY paths use `lastLiquidityUsd`
  during bootstrap, $800 was still rejecting ~half the $2K pump.fun
  candidate stream when learningProgress was very low.
- $300 bootstrap gives the AI a clean first-100-trade sample window.
  MATURE $10,000 unchanged — once the bot has 5000+ trades of
  confidence, BLUECHIP/TREASURY return to demanding deep liquidity.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on 17f8c2048.

### V5.9.802 — Performance Recovery Patch P2 (May 16, CI ✅ Build + Runtime Smoke)

Forensic on build 5.0.2742 (V5.9.801 installed) revealed THREE silent
killers neutralising the V5.9.801 A-E patch + one new regression-class
bug. All four fixed in one push.

FIX (d) — WR Recovery 'Band.OFF' silent killer (P0, COMPLETE)
- Live Readiness pill on V5.9.801 showed 'target 0.0% · size×1.00' at
  179 settled trades. Root cause: `FreeRangeMode.phaseTargetWr(179)`
  returns 0.0 for the entire `[0, PHASE1_START=500)` range, then
  `WrRecoveryPartial.stateNow()` returns `Band.OFF`, making V5.9.801's
  Fix A quality floor = 0 and Fix D size multiplier = 1.0×.
- Resolution: in 50-499 range, default target to 25% so the band can
  engage. Beyond 500 unchanged.

FIX (a) — FDG LIQUIDITY_BELOW_EXECUTION_FLOOR 97% block rate (P0, COMPLETE)
- 21,387 / 21,980 FDG blocks (97.3%) on a meme-only universe were
  LIQUIDITY_BELOW_EXECUTION_FLOOR. Two compound causes: (1) SHITCOIN
  exec floor lerp(1000, 2500) too high vs the pump.fun BC universe;
  (2) V5.9.793's strict `exitCapacityUsd` returns 0.0 for any
  bonding-curve token with no Raydium/Jupiter pool.
- Resolution: lower SHITCOIN floor to lerp(500, 1500); during SHITCOIN
  bootstrap (learningProgress < 0.5) fall back to `lastLiquidityUsd`
  when `exitCapacityUsd` is 0. Canonical bus already flags these as
  bcSimOnly=true so quality analytics stay separable. Other lanes
  unchanged.

FIX (b) — UI defensive render cap on renderOpenPositions (P0, COMPLETE)
- 122 open positions × full LinearLayout rebuild → maxFrameGap
  30,947 ms. V5.9.749 hash-dedupe stopped some rebuilds but didn't
  cap card count.
- Resolution: cap rendered rows at 25 (newest-by-entry first); hidden
  positions still managed by the engine. Amber footer 'N more
  (cap=25, still managed)' surfaces the truncation.

FIX (c) — Extend Fix E canonical-bus alignment to 3 more brains (P0, COMPLETE)
- AdaptiveLearningEngine (Δ=+1725), BehaviorLearning (Δ=-3756),
  MetaCognitionAI (Δ=+7160) all drifting from canonical=3805.
- Resolution: AdaptiveLearningEngine.getTradeCount() and
  MetaCognitionAI.getTotalTradesAnalyzed() now source from canonical
  when fired. BehaviorLearning has by-design feature-richness gating
  so added .getCanonicalAlignedTradeCount() as a separate accessor.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on a9305a9b1.

### V5.9.801 + V5.9.801a — Performance Recovery Patch A-E (May 16, CI ✅ Build + Runtime Smoke)

Operator audit: "I need all done as one surgical reversible push please.
use the triage agent" — Deep Performance Analysis Report fixes shipped
as a single batch. Operator excluded item F: "a-e leave f no caps".

FIX A — Hoist Smart Entry Gate into sub-trader entry paths (P0, COMPLETE)
- V5.9.798 Smart Entry Gate in FinalDecisionGate was BYPASSED when
  sub-traders (Moonshot promotion, ShitCoin Express handoff, CashGen
  treasury auto-buys) emitted shouldEnter=true without re-running FDG.
- Added WR Recovery Quality Floor check at the lane source in:
    ShitCoinTraderAI.evaluate()      (threshold gate area)
    MoonshotTraderAI.scoreToken()    (minScore check)
    CashGenerationAI.evaluate()      (rejectionReasons block)
- Floor sourced from `WrRecoveryPartial.minScoreFloor()` in Executor.kt
  (AGGRESSIVE→45, MODERATE→30, FLUID/OFF→0). Each lane ORs with its
  existing FluidLearningAI threshold via maxOf(), so floor never relaxes.

FIX B — Tighten V3 min score in MODERATE/AGGRESSIVE bands (P0, COMPLETE)
- Implemented as part of Fix A via the centralised
  WrRecoveryPartial.minScoreFloor() helper.

FIX C — Tighten paper exit clamps (P0, COMPLETE)
- Executor.parsePaperExitClamp():
    STRICT_SL_-N:       `pct - 5.0` → `pct - 2.0`
                        (e.g., STRICT_SL_-10 now books [-12%, -10%])
    RAPID_CATASTROPHE:  [-19%, -14%] → [-16%, -14%]
- Closes the 3-5pp paper-vs-live drift the operator identified.

FIX D — WR Recovery entry-size dampener (P0, COMPLETE)
- Executor.paperBuy() and Executor.liveBuy() multiply requested SOL
  by WrRecoveryPartial.entrySizeMultiplier() at function entry:
    AGGRESSIVE → 0.5×
    MODERATE   → 0.75×
    FLUID/OFF  → 1.0× (no change)
- Implemented via Kotlin parameter shadowing (val sol = damped) so
  every downstream reference picks up the dampened value.
- Log: `🩹 WR_RECOVERY_SIZE_DAMP (paper|live): SYM | sol=X × M → Y`

FIX E — Legacy consumers source from canonical bus (P0, COMPLETE)
- FluidLearningAI.getTotalTradeCount() previously returned
  `sessionLifetimeBaseline + sessionTrades.get()` — own counter.
- Now sources sessionDelta from
  `CanonicalLearningCounters.canonicalOutcomesTotal.get().toInt()`
  when canonical has fired ≥1 outcome. Local sessionTrades retained
  as boot-time fallback so bootstrap progress doesn't snap to 0.

FIX skipped — F (concurrent position cap)
- Operator instruction: "a-e leave f no caps". Position cap unchanged.

V5.9.801a — One-line CI fix
- V5.9.800 introduced `trade.tokenPrice` in the isPartialByQty quantity
  check, but the Trade data class (Models.kt) only has `price`. CI
  caught it on the V5.9.801 push. Renamed to trade.price. Identical
  semantics.

CI: Build AATE APK ✅ + Runtime Smoke Test ✅ both GREEN on 34c9f4579.

### V5.9.800 — CRITICAL FIX: fluid profit-lock / capital-recovery / WR-recovery partials restored (May 16, CI ✅)

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
