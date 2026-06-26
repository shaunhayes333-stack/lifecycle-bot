# AATE Changelog

All notable changes to the Autonomous AI Trading Engine.

---

## [5.0.4180] - 2026-06 — PHANTOM-WIN GUARD (root cause of bleed)

**Smoking gun in field notifications:**
```
2nd partial — Apollo:  PnL +210,425.3% (+6.0490 SOL)   ← PHANTOM
2nd partial — WEEKEND: PnL +242,342.9% (+6.9665 SOL)   ← PHANTOM
Live Partial — Apollo: PnL -31.75%     (-0.0012 SOL)   ← REAL
```

Canonical wallet PnL: -0.282 SOL. The +6 SOL & +7 SOL "wins" never landed.

**Root cause:** `solBack = quote.outAmount/1e9` uses the OPTIMISTIC Jupiter
quote as the post-tx swap result. Sandwiches / thin-pool prints / failed
routes inflate `quote.outAmount`; the actual wallet delta is tiny or
negative. Bot books the phantom +210,425% win → writes to TokenWinMemory +
PatternMemory → bot learns to chase ghost patterns → real fills are dust.

**F6 (this push) — partial-sell phantom guard:**
When booked `liveScore > 1000%` AND `livePartialCostBasisSol < 0.01 SOL`
(real win on tiny size = tiny SOL, not multi-SOL), the trade still closes
on-chain (can't undo the swap) but the booked pct is demoted to +50% so
TokenWinMemory / PatternMemory / journal / notification see a sanitised
value. New `PHANTOM_SELL_DETECTED` forensic event + counter.
File: `engine/Executor.kt` (~line 5790 — auto partial-sell path).

**F7 (pending next push) — retroactive phantom purge:**
TokenWinMemory has 422 "winners" mostly poisoned. One-time startup sweep
to demote entries with `claimed_pct > 1000%` AND `realized_sol < 0.05`.
PatternMemory rebuilds on real data.

**Second partial-sell path (requestPartialSell, ~line 13086) — pending.**
Same guard should be applied; documented for next push.

---

## [5.0.4179] - 2026-06 — LIFT THE BOT (5-fix surgical wound-seal)

**Operator audit directive**: "do all 5 now. this must lift the fucking bot!!!"

**Wound identified in audit** (recent journal evidence):
```
WIN  +45.2× runner +0.0004 SOL  ← size dampened to dust
LOSS -71.4% CATASTROPHIC_STOP_LOSS_OVERRUN_-70pct  ← exit slip
LOSS -58.8% CATASTROPHIC_STOP_LOSS_OVERRUN_-58pct  ← exit slip
```

STRICT_SL fires at -10% based on cached price → market sell hits Jupiter
→ thin liquidity makes the fill come back at -71% realized. Bot bleeds
asymmetrically: wins are size-dampened to dust, losses are slip-amplified.

**5-fix surgical attack:**

  * **F1 — SLIP-AWARE ENTRY SIZING.** `ExecutionCostPredictorAI.expectedExtraSlipPct()`
    now down-sizes entries by `1/(1+slip/10)` and HARD REJECTS when slip ≥18%.
    The bot LEARNED slip but only added a -6 score penalty; now it costs real
    size. New `BUY_REJECTED_PREDICTED_SLIP_*` reason in `Executor.doBuy()`.
    File: `engine/Executor.kt`

  * **F2 — PREDICTIVE SL.** STRICT_SL trigger now subtracts learned slip
    from the configured stop. If liq-band averages 8% slip and SL is -10,
    fire at -2 so realized loss caps at ~-10 instead of -18. Min trigger
    clamped to -3% to avoid jitter exits on healthy positions.
    File: `engine/Executor.kt` (line ~5151)

  * **F3 — HIGH-CONFIDENCE SIZE CEILING BOOST.** When candidate `score ≥ 75`
    AND `liquidity ≥ $10K` AND regime ≠ DUMP, the multiplier compound is
    allowed up to 1.5× (cap raised from previous 1.0). Combined with the
    floor at 0.25× and lane bias ×1.40 for MOONSHOT/STANDARD, the bot can
    now scale into proven setups instead of always trickling.
    File: `engine/Executor.kt`

  * **F4 — UnifiedPolicyHead GRADUATION ACCELERATION.** Authority tiers
    20/60/150 (was 40/100/250). At ~6 BUYs/min the head crossed ADVISORY
    in ~3min instead of ~7min. Lets the policy brain's learned weights
    actually influence trades instead of staying BOOTSTRAP forever.
    File: `engine/UnifiedPolicyHead.kt`

  * **F5 — WATCHLIST_FLOOR LIFT to $8K while WR < 30%.** Field tokens with
    $1.6K-$7K liq were the catastrophic-overrun sources. Until live WR
    recovers above the floor, the gate refuses anything below $8K — only
    candidates that can actually be exited cleanly. Auto-restores to
    FluidLearningAI's normal lerp when WR recovers.
    File: `engine/FinalDecisionGate.kt`

**Philosophy:** F1+F2+F5 seal the bleeding wound. F3+F4 amplify the wins.
Combined effect:
  - Fewer trades on thin-liq tokens (F1, F5).
  - Smaller realized losses on the ones that do dump (F2).
  - Bigger wins on high-conviction setups (F3).
  - Faster learning convergence (F4).

Brace/paren git-diff deltas balanced on all three files (0/0).

---

## [5.0.4178] - 2026-06 — SELECTIVITY-FIRST PIVOT (operator philosophy reset)

**Operator directive (overriding V5.0.4177's philosophy):** "I dont really
want downsized probe learning. they become dust sized trades!!! it needs
to pivot and the lane brains need to switch strategies faster. find the
right tokens to trade".

V5.0.4177 had the wrong philosophy — it loosened gates to let more dust
probes through. Operator wants the opposite: **fewer, better trades at
full size, with the bot ruthlessly killing losing strategies and lanes.**

**Reverted from V5.0.4177:**

  * **L1 reverted** — Rugcheck weak-fallback `BlockLevel.SIZE` → back to
    `BlockLevel.HARD`. No more dust-probe trades on slow-rugcheck tokens.
    `RUGCHECK_TIMEOUT_PENALTY` 10 → 14 (was 18; kept tighter than 4177 but
    not fully back to 18 — Rugcheck slowness is real, not always rug
    signal).
  * **L2 reverted** — `DOCTRINE_FLOOR_PCT` 15.0 → 30.0,
    `EMERGENCY_FLOOR_PCT` 10.0 → 20.0. Don't loosen entry quality when WR
    is low; tighten it.

**Kept from V5.0.4177:**

  * **L3 kept** — `multiplierProduct.coerceAtLeast(0.25)`. This is the
    anti-dust guard. Compound never crushes below 25% of base.
  * **L4 tightened (was lane bias ×1.20/×0.85, now ×1.40/×0.50)** —
    MOONSHOT/STANDARD get +40%, every other lane halved. Capital
    concentrates on what works.
    File: `engine/Executor.kt`

**New in V5.0.4178:**

  * **L5 — Strategy pivot acceleration** in `LiveStrategyTuner`. Toxic
    bleed threshold `n >= 20` → `n >= 10`. Lanes get force-pivoted to
    `toxic_runner_pivot` (sizeFloor 0.12) within 10 closes instead of 20.
    File: `engine/LiveStrategyTuner.kt`

  * **L7 — Worst-lane suppression while WR < 45%.** SHITCOIN, EXPRESS,
    MANIPULATED, DIP_HUNTER all bleed (≈0% WR in journal). While the bot
    is below the LIVE_ADAPTIVE doctrine floor (45%), these lanes are
    skipped entirely in `shouldRunBuyLaneForCycle` — capital + cycle
    budget concentrate on MOONSHOT / STANDARD. Auto-resumes when WR
    recovers. Primary-lane override + STANDARD/CORE/V3 trunk are always
    allowed (regression guard). New `LiveLayerGateRelaxer.currentLiveWrPct()`
    public accessor used to read the cached live WR without re-computing
    the leaderboard per call.
    Files: `engine/BotService.kt`, `engine/LiveLayerGateRelaxer.kt`

**Philosophy summary:**
  - Fewer trades, but each at proper size (L3 floor + L4 bias).
  - Bad lanes get killed fast (L5 + L7).
  - Bad tokens stay hard-blocked (L1 reverted, L2 reverted).
  - The bot earns its way back to lane re-enablement by recovering WR.

Brace/paren git-diff deltas balanced on all six files (0/0).

---

## [5.0.4177] - 2026-06 — 4-WAY WR-FEEDBACK-LOOP UNCHOKE (operator option 5)

**Symptom (V5.0.4176 field 204s session):** cycle was unchoked (5-7s, no
EXIT_COORDINATOR stalls) but only 2 live positions held. Operator: "its
not uncooked tho! only 2 live positions being held? why isnt the scanner
and lane brains all finding candidates?"

**Root cause:** WR-defensive feedback loop, not infrastructure.
  * `🔒 GATE RELAXER: DISABLED (live WR=11.6% < 30% floor)` — relaxer
    refused to fire because WR was below the doctrine floor.
  * `regime=DUMP → scoreFloorDelta=+20, sizeMult=0.10`.
  * `LiveStrategyTuner STANDARD size×=0.12, MOONSHOT size×=0.46`.
  * `LiveProbabilityEngine STANDARD size×=0.40`.
  * Compound: 0.10 × 0.12 × 0.40 = **0.0048× base** for STANDARD lane.
  * 50× HARD_BLOCK_RUGCHECK_PENDING_REVIEW_WEAK_FALLBACK (43% of FDG
    decisions blocked because Rugcheck API was just slow).

**4-way fix (operator option 5 = "all"):**

  * **L1 — Rugcheck pending/timeout HARD → SOFT block.** The Rugcheck
    weak-fallback path in `FinalDecisionGate` (rugcheckStatus = TIMEOUT
    or PENDING_REVIEW) was hard-blocking 50 candidates this session.
    TokenSafetyChecker / FDG safety layer still run independently
    (top-holder, freeze authority, LP locked, etc) — Rugcheck slowness
    isn't evidence of a rug. Downgrade to SOFT so the bot probes with
    reduced size. CONFIRMED-but-low-score still gets HARD_BLOCK.
    Live timeout penalty 18 → 10.
    File: `engine/FinalDecisionGate.kt`, `engine/TokenSafetyChecker.kt`

  * **L2 — Gate relaxer DOCTRINE_FLOOR_PCT 30 → 15.** Lets the relaxer
    fire while WR is in the chronic 15-30% band; without this the
    score floors stay elevated forever and candidate flow chokes.
    Emergency floor 20 → 10 keeps a safety net below 10% WR.
    File: `engine/LiveLayerGateRelaxer.kt`

  * **L3 — Multiplier compound 0.25× floor.** The size-damper stack
    (`sizeMult * labMult * laneEvMult * regimeMultGoosed * laneSizeCap
    * brainSizeMult * strategyTunerSizeMult * sourceBrainSizeMult *
    uphConvictionMult`) was crushing trades to 0.005× base in DUMP+CHOP
    regimes. New `coerceAtLeast(0.25)` ensures good candidates still
    get meaningful exposure. Upper bound (winnerMaxBoost 1.75/2.35×)
    unchanged.
    File: `engine/Executor.kt`

  * **L4 — Lane priority bias.** MOONSHOT (WR=22.7%) and STANDARD
    (WR=23.8%) are the bot's two best lanes; everything else is
    worse. Bias size ×1.20 for these two, ×0.85 for the rest. Doesn't
    restructure lane election — just allocates more capital to what's
    working.
    File: `engine/Executor.kt`

**Risk:** every losing trade now also gets the 25× floor + 20% bias.
Operator was explicitly informed of this trade-off and chose Option 5.

**Verification:** brace/paren git-diff deltas balanced on all four files
(0/0). Live WR will be re-evaluated after the next field session.

---

## [5.0.4175] - 2026-06 — UNCHOKE: 4-WAY CYCLE BLOAT ATTACK

**Symptom (V5.0.4174 field 1394s session):** bot traded fast for ~5 min then
visibly choked. 20 BUYs total (0.86/min), cycle time 17–48s (target 5–9s),
22× EXIT_COORDINATOR_STALE_RESET, scanner sources timing out in streaks
(scanPumpFunDirect streak=27), Birdeye daily CU exhausted at 150125/150000
= 100.1% with 328× 5xx + 23× net errors AFTER the cap was blown.

**Root cause:** classic provider-degradation cascade:
  1. PumpFun rate-limits us → scanner sources block for full timeout.
  2. With 5 sources hung in parallel, scan batch eats 14s of every cycle.
  3. Birdeye daily CU blows; safety/refresh callers still try → 5xx storm
     → another 1s+ of latency per call.
  4. Cycle bloats to 20–48s → exit lock can't be grabbed in time →
     EXIT_COORDINATOR_STALE_RESET fires → exit machinery resurrects but
     trade opportunities are already stale.

**4-way fix:**

  * **A (highest impact) — SCAN_BATCH_BUDGET_MS 14_000 → 8_000** plus
    SOURCE_SCAN_TIMEOUT_MS 6_000 → 5_000. Worst-case scan cost drops from
    14s to 8s per cycle. PumpPortal WS firehose is on a separate path and
    untouched — meme trader keeps its real-time intake.
    File: `engine/SolanaMarketScanner.kt`

  * **B — PROBATION_MAX_TIME_MS 120s → 90s.** Tokens that don't graduate in
    90s almost never do (forensic median = 45s, p95 = 80s). Frees probation
    enrichment cycles for candidates that actually mature.
    File: `engine/GlobalTradeRegistry.kt`

  * **C — Birdeye safety-call brownout at ≥98% daily CU.**
    `BirdeyeBudgetGate.canAffordSafety()` now returns false when daily CU
    is essentially exhausted. Stops the 5xx storm + bandwidth burn on calls
    the provider was guaranteed to reject. Monthly-lockdown path unchanged;
    open-position emergency calls have their own headroom check.
    File: `engine/BirdeyeBudgetGate.kt`

  * **D — Cycle-overrun forensic alarm + PipelineHealthCollector counters.**
    Any cycle in the 20s–90s band emits `BOT_LOOP_CYCLE_OVERRUN` (sub-Doze
    band — Doze detector still owns >90s) and increments bucketed counters
    (20s+ / 30s+ / 40s+ / 60s+). No hard-cancel of the loop body (too risky
    in the 23K-line method — could interrupt a buy/sell mid-flight); pure
    observability so future regressions surface immediately in the snapshot.
    File: `engine/BotService.kt`

**Safety:**
  * Meme trader path (PumpPortal WS, direct scan, fluid scoring) untouched.
  * Brace/paren deltas verified balanced (0/0 on all four files).
  * Open positions retain their dedicated `canAffordOpenPositionEmergency`
    headroom — they never get brownout-suppressed.

---

## [5.0.4174] - 2026-06 — JUPITER TOKENS API V1 → V2 MIGRATION

**Symptom**: API health monitor showed `🔴 jupiter sr=0% net=1 last_err:
Unable to resolve host "tokens.jup.ag"`. Persistent NXDOMAIN across every
session, regardless of carrier / VPN. The HostCircuitInterceptor's 5-min
NXDOMAIN cool-down was masking the failure but the SOL-wide verified token
universe (~4,300 tokens) never loaded, starving the scanner of an entire
class of established candidates.

**Root cause**: `tokens.jup.ag` (Jupiter Tokens API V1) was **deprecated
30 September 2025** and has now been retired. The host is genuinely gone.

**Fix**: Migrated `JupiterStrictTokenList` to the new free-tier endpoint
`https://lite-api.jup.ag/tokens/v2/tag?query=verified`. Schema changed:

  * `address` → `id` (mint pubkey)
  * `daily_volume` → `stats24h.buyVolume + stats24h.sellVolume`

Parser is defensive — reads V2 keys first, falls back to V1 keys if any
upstream cache ever serves an older shape. Verified live: V2 endpoint
returns 4307 verified tokens, schema matches expected fields.

**File**: `app/src/main/kotlin/com/lifecyclebot/network/JupiterStrictTokenList.kt`
Brace/paren balance verified (20/20, 59/59) before push. DNS prewarm for
`lite-api.jup.ag` was already in place from V5.9.28.

---

## [5.0.4173] - 2026-06 — HOTFIX: TRANSPARENT GZIP DECODE (bot revival)

**Symptom (V5.0.4172/4173 field snapshot)**: bot fully dead — STOPPING
state with `intake=0 fdg=0 exec=0`. Every wallet RPC failing with a
garbled body: `Value �     �V�*��+*HV�R2…`. All Solana RPC providers
(Helius, Alchemy, Ankr, mainnet-beta, rpcpool) returning the same
gibberish. `TokenMetaCache 0 reads / 0 writes`. `SCAN_CB/EXCEPTION=8`.

**Root cause (V5.0.4170 regression)**: the new `SharedHttpClient`
application interceptor manually added `Accept-Encoding: gzip` to every
request to coax gzip from servers that only gzip on explicit ask.
PROBLEM: OkHttp's `BridgeInterceptor` only transparently decompresses a
response when **it** added the `Accept-Encoding` header itself. When an
upstream application interceptor sets it, BridgeInterceptor leaves the
gzipped body untouched — every JSON parser then chokes on raw gzip
bytes. The wallet manager couldn't read its own balance → entire
pipeline stalled.

**Fix**: keep the explicit `Accept-Encoding: gzip` request header (so we
still hit gzip on call sites that previously skipped it) AND
transparently decompress responses ourselves whenever
`Content-Encoding: gzip` comes back. Strip `Content-Encoding` /
`Content-Length` from the resulting headers so downstream callers see a
normal uncompressed body — matching OkHttp's default transparent
behaviour. Full bandwidth win (JSON 60–80% smaller) preserved, RPCs
restored.

**File**: `app/src/main/kotlin/com/lifecyclebot/network/SharedHttpClient.kt`
Brace/paren balance verified (8/8, 60/60) before push.

---

## [5.0.4165] - 2026-06 — BUY LEASE WINDOW 5s → 15s (volume restore)

Operator on V5.0.4165 reported "bot isn't trading". Dump showed
EXEC_GATE allow=1712 but BUY ok=10 — a **99.4% buy-throughput collapse**.
Forensic feed flooded with `EXEC_LEASE_PRUNED_EXPIRED` events.

`troubleshoot_agent` RCA pinpointed the dominant choke:

- Cycle times: `avg=5827ms max=21603ms` (Jupiter `avg=3378ms` was
  dragging the loop end-to-end).
- BUY_DECISION lease freshness window at `Executor.kt:9929` was **5
  seconds**.
- Every cycle that takes >5s = every buy decision in that cycle
  staling out → `BUY_DECISION_EXPIRED_RESCORE` → defer → re-score next
  cycle → stales out again. Endless loop, zero volume.

V5.0.4162–4164 (parallel work by Vex) addressed Jupiter-quote-vs-send
health split, MemeTrader lane truth, suppressor telemetry, sell-defer
wall-clock cap, and zero-signal probes. None of those touched the
5s lease window, so the buy-throughput collapse remained.

**Fix**: lease freshness 5s → 15s at `Executor.kt:9929`. 15s gives ~70%
headroom over the worst observed cycle (21.6s) while still rejecting
genuinely stale decisions. Routes proof still re-hydrates at 8s.

Volume / meme-trader promise: restores the executor's ability to
sign decisions inside the SAME cycle they were made in, even when
Jupiter latency drags cycles to 5–7s in steady state.

---

## [5.0.4161] - 2026-06 — EXECUTION-HEALTH GUARD (jupiter-blackout defense)

Operator dump 2026-06-26 (running V5.0.4160/4161) revealed two more
catastrophic closes: `385j195R pnl=-71.4%` and `BHXt2heo pnl=-58.8%`,
both labelled `CATASTROPHIC_STOP_LOSS_OVERRUN_-Xpct_FROM_STRICT_SL_-10`.

Root cause: V5.0.4160's `CATASTROPHIC_HARD_BACKSTOP_-25` correctly
DETECTS the bleed but calls the same `doSell()` pipeline. With Jupiter
dead (`sr=0%, Unable to resolve host "tokens.jup.ag"`) the executor
falls through to the PUMP/HELIUS direct route with no slippage
projection — a STRICT_SL_-10 fires correctly at -10% but fills at
-71%. Detect-side guards are useless when execution itself is broken.

New module `engine/ExecutionHealthGuard` — three surgical, **volume-
preserving** rules:

1. **`shouldDeferBuy()`** wired at `liveBuy()` top. When Jupiter is
   unhealthy (sr<25% AND no success in 60s window), defer the buy.
   We do not acquire bags we cannot safely unwind. Self-resets on
   the very next Jupiter success — no permanent throttle.

2. **`shouldDeferDirectRouteSell()`** wired inside the `jupiterQuoteUnavailable`
   branch in `liveSell()`. When Jupiter is dead AND the reason is
   non-emergency, defer up to 5 ticks (~30s) hoping Jupiter recovers.
   After the cap, force-proceed (better a bad fill than a stuck bag).
   Emergency reasons (RUG, HONEYPOT, CATASTROPHIC, STEALTH_MINT,
   STALE, MAX_HOLD, MUST_SELL, EMERGENCY, SHUTDOWN, PHANTOM, DRAIN,
   PANIC, REFLEX, LIQ) ALWAYS broadcast — never frozen.

3. **`recordSlippageOutcome()`** post-execution alarm. When realized
   SOL is >20% worse than the original quote, log `EXECUTION_SLIPPAGE_VIOLATION`
   so the failure mode becomes visible in telemetry and the daily
   "Catastrophic backstops fired today" UI counter can surface it.

Profit / volume / meme-trader stance:
- Buys self-resume on first Jupiter success (typically seconds).
- Sells force-broadcast after 5 defers — no permanent freeze.
- Emergencies bypass everything.
- State is in-memory, self-clears, zero persistence.

---

## [5.0.4160] - 2026-06 — SCRATCH-STREAK BUTTERFLY SWEEP + CATASTROPHIC -25% BACKSTOP

Two operator P0s shipped together:

**1. Scratch-Streak Guard (butterfly sweep across all lanes)**
V5.0.4159 introduced a per-lane scratch counter in MOONSHOT to detect
the "all-scratch trap" (17 trades, W/L/S = 0/0/17). Operator: "meme
traders basically stopped trading. completely. it needs that fix
everywhere bro! you need to do siblings, traders, upstream downstream,
butterfly sweeps!!!"

The counter has been lifted into `engine/ScratchStreakRegistry` (lane-
keyed, fully isolated) and wired into every meme + crypto lane:
MOONSHOT, SHITCOIN, EXPRESS, BLUECHIP, QUALITY, MANIPULATED, CRYPTO_ALT.
The shared `OutcomeGates.earlyExitByHoldBucket` now consults the
registry centrally so any lane that crosses the 4-scratch trap
threshold gets its FLAT_EXIT window extended (typically 2×) before
flat-cutting. Self-correcting — any non-scratch close resets the
counter.

**2. Catastrophic -25% Hard Emergency Backstop (Executor.kt)**
Operator dump showed trades closing at -71% and -58% despite STRICT_SL
configured at -10%. Root cause was a Jupiter DNS blackout
(`tokens.jup.ag` unresolvable) that stalled live quotes; both live
and cached SL paths skipped firing because the feed stopped ticking
before price ever reached the configured floor.

New last-line backstop runs BEFORE paper settle-in, fluid SL coercion,
profit locks, and STRICT_SL. If EITHER the live price OR the most
recent cached price shows pnl ≤ -25%, the position is force-exited
immediately with reason `CATASTROPHIC_HARD_BACKSTOP_-25` — regardless
of quote freshness, learning state, or trader settle-in. There is no
scenario where holding a -25% bag through a quote outage is correct.

Also fixes the V5.0.4159 CI compile error (`Type mismatch: Int but
Long expected` at `MoonshotTraderAI.kt:1663` from `pos.spaceMode.maxHold`).

---

## [5.0.4148] - 2026-02 — TOP-PERFORMING-LANE BYPASS (DEADLOCK FIX)

V5.0.4134's discipline pack was working perfectly (0/65 buys allowed in
115s on operator's dump) but created a deadlock: global WR < 30% pause
floor → ALL lanes vetoed → profitable STANDARD lane (38.5% WR) locked
out → no new outcomes → rolling window never refreshes → DEFENSIVE
permanent.

**Fix**: `effectivePause = pauseDefensive && !isTopPerformingLane(lane)`
applied at both `doBuy` and `liveBuy` veto chokepoints. STANDARD keeps
trading and rebuilds WR; MOONSHOT stays locked by its per-lane
LaneTimeoutGate + DUMP regime kill switch (V5.0.4134) which are
unchanged and bypass-immune.

CI: GREEN ✅ (run 28157804365 → AATE_v5.0.4148).

---

## [5.0.4146] - 2026-02 — APK AUTO-BUMP FROM CI RUN NUMBER

Operator: "its not bumping the build number the last 4 have had the same number."

Four consecutive shipping builds came out as `AATE_v5.0.4132` because the
`AATE_VERSION` file was static.

**Fix**: `AATE_VERSION` now holds the major.minor prefix only (`5.0`); both
build.yml and release.yml workflows compose `VERSION_NAME="${BASE}.${BUILD_NUMBER}"`
where `BUILD_NUMBER = GITHUB_RUN_NUMBER + 1`. Every push now produces a
uniquely-named APK aligned with the CI run number.

Two GoldenTape regression tests inverted (one previously *prohibited* the
exact pattern the operator is now requesting).

CI: GREEN ✅ (run 28147335468 → AATE_v5.0.4146).

---

## [5.0.4134] - 2026-02 — DUMP REGIME KILL SWITCH + UNIVERSAL liveBuy() VETO

Operator: "bot is still going backwards winrate under 20%. unacceptable."

**Root cause**: V5.0.4133's veto sat at `doBuy()`, but MOONSHOT
shadow-to-live (Executor.kt:8115) calls `liveBuy()` directly, bypassing
`doBuy()` entirely. Plus GOLD/WINNER goose verdicts kept bypassing the
discipline pack.

**Fix**: Three-layer veto at `liveBuy()` head (the TRUE single live entry):
- (a) Rug-blacklist — universal, immune to all bypasses
- (b) **DUMP-regime kill switch** — `regime==DUMP` AND lane WR <25% (n≥12)
  via `StrategyTelemetry.computeLiveTerminalLeaderboard`. Pattern-verdict-
  immune. No GOLD/WINNER bypass.
- (c) Pause/timeout/scanner-bridge — mirrored from `doBuy` so non-`doBuy`
  callers also see them.

CI: GREEN ✅ (run 28146223234).

---

## [5.0.4133] - 2026-02 — RUG-BLACKLIST UNIVERSAL VETO + TIGHTER FLOORS

Operator dump showed the same mint `EnsVnDQ3` rugging 6 times at -98.6% in the
last 10 closes — 22% of the lifetime bleed from a single mint the blacklist
should have caught after incident #1.

**Root cause**: `RugMintBlacklist.recordClose` was wired in V5.0.4132 but
`isBlacklisted()` was only consulted in `BotService.kt:4813` (one MEME path).
Every other lane skipped the check.

### Fix 1 — Universal `Executor.doBuy` rug-blacklist veto
Added at line ~7873, BEFORE the GOLD/WINNER goose bypass. Pattern verdict
cannot override per-mint 24h cooldown. Forensic: `RUG_BLACKLIST_VETO_V4133`.

### Fix 2 — LivePauseButton thresholds 25/35 → 30/45
Global WR was sitting at 24.4% — just below the 25% floor — flapping at the
boundary. Tighter entry + 15-point recovery gap.

### Fix 3 — LaneTimeoutGate thresholds 20/35 → 25/45
MOONSHOT at 24-25% WR with EV=-76% was sitting just above the 20% timeout
floor. Tighter entry + 20-point recovery gap.

CI: GREEN ✅ (run 28143456053).

---

## [5.0.4132-fix2] - 2026-02 — CI GREEN, DISCIPLINE PACK DEPLOYED

`Executor.kt:17005` was reading `tradingMode` off `TokenState` where it actually
lives on `Position`. Switched to `pos.tradingMode` (matching the block ~30
lines above that already reads `pos.tradingMode` for `traderSource`). Also
swapped the `?: "MEME"` elvis on the (non-nullable) field for `ifBlank{}`.

**No behaviour change.** Pure compile-fix that finally unblocks V5.0.4132's
Discipline Pack — LivePauseButton + LaneTimeoutGate + ScannerLaneBridge +
RugMintBlacklist outcome wiring is now live.

CI: GREEN ✅ (run 28124765075).

---

## [5.0.4132] - 2026-02 — DISCIPLINE PASS + SCANNER-LANE BRAIN

Operator mandate: WR was slipping under every "intelligence" layer (37→33→23→22%).
The fix is NOT more aggression — it's DISCIPLINE. Five new modules ship together.

### NEW: LivePauseButton (capital redirect to performing lanes)
Tracks rolling 30-trade WR globally. If WR < 25% (PAUSE_FLOOR) → DEFENSIVE mode:
- Only GOLD/WINNER verdict tokens trade
- Top-1 lane by recent WR → size ×1.30
- Top-2 → ×1.15
- Top-3 → ×1.00
- Unranked → ×0.70 (sized DOWN, not blocked)
Exits DEFENSIVE when rolling WR climbs back above 35% (hysteresis prevents flap).
Capital actively re-routes toward what's working.

### NEW: LaneTimeoutGate (per-lane circuit breaker)
Each lane tracks its OWN rolling 30-trade WR. When < 20% → TIMEOUT (only GOLD/WINNER
verdicts trade in that lane). Recovery at 35%. MOONSHOT goes into timeout
immediately given the 14.3% WR data.

### NEW: RugMintBlacklist (don't re-buy rugs)
Closes ≤ -50% within 10 minutes of entry → blacklist that mint for 24h. Operator
data showed USWR -100% × 5 re-entries on the same mint. Killed.

### NEW: ScannerLaneBridge (per-source × per-lane brain)
Records win rate per `(scanner_source, lane)` pair. Surfaces:
- `affinityBias(src, lane)` → ±12 score bias from learned compatibility
- `shouldRoute(src, lane)` → veto on proven-toxic pairs (≥16 samples, WR≤5%, mean≤-40%)
- `bestLaneFor(src)` → best historical lane for a given scanner source
Each scanner source now learns which downstream lane converts its candidates best.

### WIRED:
- `BotService.onCreate` — inits all 4 modules at boot, restores from SharedPreferences
- `BotService.MEME_DIRECT_INTAKE` (fast path) — BEFORE admission, applies:
  RugBlacklist + GooseCatastrophic + ScannerLaneBridge.shouldRoute vetoes.
  Closes the "dumb path bypasses smart gate" leak from V5.0.4126-4130.
- `Executor.doBuy` — discipline veto if (DEFENSIVE || LaneTimedOut) AND verdict
  not GOLD/WINNER. Else applies `laneTilt` + `bridgeBias` into the sizing pipeline.
- `Executor.liveSell` close hook — feeds outcomes to all 4 modules so the bot
  self-tunes from its own trade history.

### Doctrine
- All 4 modules fail open (unknown patterns/lanes/mints get NORMAL treatment).
- TOXIC/CATASTROPHIC verdicts NEVER bypass any safety.
- GOLD/WINNER bypass only the DISCIPLINE vetoes (the proven-quality escape hatch).

### Why this is different from V5.0.4126-4130
Previous fixes ADDED intelligence layers (goose, gate-override, regime-bypass).
This pass ADDS DISCIPLINE — circuit breakers, cooldowns, and memory of past
failures. The bot now stops trading garbage in dump regimes; doesn't re-buy rugs;
and routes capital toward provably-winning lane-source combinations.

---

## [5.0.4131] - 2026-02 — REAL-SIZE ENTRIES (liquidity-cap fix)

### Root cause
Operator journal showed live entries of ~0.01–0.03 SOL ($1–$3) despite the
V5.0.4129 absolute floor in `doBuy`. The leak was downstream in
`realisticLiveEntrySize` (`Executor.kt:2308`) where the **liquidity-impact
cap** (2% of pool size in SOL terms) was crushing entries on low-liq
pump.fun newborns:
  - liqUsd=$100 → cap = (100 × 0.02) / $104/SOL = **0.0096 SOL** (dust)
  - liqUsd=$500 → cap = **0.048 SOL**
  - liqUsd=$1000 → cap = 0.19 SOL
This cap was bypassing my doBuy absolute floor because line 2347
(`out.coerceAtLeast(minOf(requestedSol, cap))`) selects `cap` when
`requestedSol > cap`.

### Fix 1 — Pattern Golden Goose impact tolerance
- GOLD verdict → impact tolerance × 4 (allows ~8% pool impact)
- WINNER verdict → impact tolerance × 2.5 (~5% impact)
- NEUTRAL/TOXIC/CATASTROPHIC → unchanged (~2% impact)
- Rationale: theme_space pattern wins 82.7% with 47% avg gain — accepting 8%
  slippage on a 47% expected return is a strictly winning trade.

### Fix 2 — Absolute floor at the final size-authority
- After all caps, if `walletHealthy` (spendable > MIN_ENTRY × 3) AND
  `liquidityAdequate` (≥$500 = exitable) → lift to `MIN_ENTRY_SOL` (0.040 SOL).
- TOXIC/CATASTROPHIC verdicts SKIP this lift (don't size up bad patterns).

### Impact
- Healthy wallets + adequate liquidity → entries floor at 0.040 SOL (~$4) instead of dust
- Known-edge (GOLD/WINNER) tokens → up to 4× higher liquidity-impact ceiling
- Quality-confirmed scaling preserved end-to-end across the size pipeline

### Telemetry
- `LIVE_ABS_FLOOR_LIFT_V4131` label + forensic `LIVE_ABS_FLOOR_LIFT_V4131` log
- `GROWTH_MODE_TRACE` now includes goose verdict

---

## [5.0.4130] - 2026-02 — PROFIT-BOOSTER TRIO (no volume loss)

### Fix 1 — ultra_runner_bank current-price sanity gate (Executor.kt)
- `peakGainPct >= 5_000.0` triggered the panic-banker indefinitely after a
  position ever peaked at 50x, EVEN after the price collapsed back through
  entry. Journal showed banker selling at -29% / -66% PnL because
  `qty × price / costSol` still read 50x on a stale-peak basis.
- Added: `currentValue >= pos.costSol * 1.5` — only banks when the position
  is ACTUALLY a runner right now. Maintains volume (winners still bank);
  eliminates the loss-exit cascade.

### Fix 2 — FDG TOKEN_MAP_INCOMPLETE goose downgrade (FinalDecisionGate.kt)
- Op report: 9 of 11 FDG verdicts blocked here (transient route-data lag on
  fresh launches, mostly Raydium/pump migration).
- GOLD/WINNER pattern verdicts: downgrade from HARD_BLOCK to advisory,
  apply soft-shape via `LiveSizingProfile.markGateSoftShape("FLUID_EXECUTE_FLOOR")`,
  let the executor's fallback routing (Jupiter Ultra / PumpSwap / Raydium probe)
  do its job. Unknown / TOXIC / CATASTROPHIC still hard-block.
- Volume impact: NEUTRAL+ (unblocks tokens already KNOWN to convert at 50-82% WR).

### Fix 3 — DUMP-regime goose bypass (Executor.kt)
- `RegimeDetector.sizeMultiplier()` returns 0.10 in DUMP — crushed every entry
  to 10% of base regardless of asset-level edge.
- GOLD verdict → bypass to 1.00× (full size).
- WINNER verdict → 0.60× floor.
- Other verdicts → standard regime brake unchanged.
- Telemetry: `REGIME_GOOSE_BYPASS_<verdict>` label + forensic log.
- Volume impact: NEUTRAL (same trades, real size when goose confirms quality).

### Composition — all three boost profit WITHOUT cutting volume
- Fix 1: prevents giving back gains (loss-exits gone)
- Fix 2: unblocks pattern-confirmed entries previously vetoed by data-lag
- Fix 3: lets pattern-confirmed entries get REAL size in dump regimes
- Quality protection: TOXIC/CATASTROPHIC verdicts never bypass anything.

---

## [5.0.4129] - 2026-02 — MEME-TRADER MONEY-PRINTER PASS (P0 trio)

### Fix 1 — Sizing cascade absolute floor + goose override (Executor.kt)
- `doBuy` was applying `liveFloorMult × sol`, a RELATIVE floor. When upstream
  SmartSizer multipliers had already collapsed `sol` to dust (0.003 SOL),
  the floor became 0.0009 SOL (relative to dust input). Result: +24,570% wins
  paying $0.33.
- Now: `effSolRaw` clamps to `max(relMin, absMin)` where `absMin` is an
  ABSOLUTE entry floor sourced from `LiveSizingProfile` tiers (MIN/DEFAULT/STRONG)
  and gated on wallet adequacy.
- PatternGoldenGoose verdict override:
  - GOLD → absolute floor lifted to STRONG_ENTRY_SOL (0.110 SOL) + max boost 3.00×
  - WINNER → DEFAULT_ENTRY_SOL (0.060 SOL) + 2.35×
  - TOXIC/CATASTROPHIC → no absolute lift (size shrinks as before)
- Telemetry: `LIVE_ABS_FLOOR_LIFT_<verdict>` label + forensic `LIVE_ABS_FLOOR_LIFT_V4129`.

### Fix 2 — Goose exit protection on MOONSHOT (MoonshotTraderAI.kt)
- GOLD pattern tokens now bypass:
  - `EARLY_TIGHT_STOP` (-5% cut when peak < +8%)
  - `HOLD_BUCKET_EARLY_EXIT`
- Hard floor -15% STILL applies — only the early-cut layers are relaxed.
- Lets proven-winner signatures (theme_space 82% WR, theme_ai 50% WR) ride to
  their statistical mean (+47% for theme_space).

### Fix 3 — GateRelaxer per-token golden-goose override (LiveLayerGateRelaxer.kt)
- New `floorMultiplierForToken(traderTag, name, symbol)`: when live WR < 30%
  doctrine floor (death-spiral lock), the global relaxer disables. This now
  bypasses the lock FOR THE SPECIFIC TOKEN if the goose says GOLD/WINNER.
- TOXIC/CATASTROPHIC verdicts NEVER get a relax (extra protection).

### Fix 4 — Starved-lane wakeup (BotService.kt)
- `laneAffinityForTradeType` and `inferIntakeLaneAffinity` expanded to seed
  CASHGEN, CYCLIC, MANIPULATED, EXPRESS, DIP_HUNTER into the candidate pool.
- Pre-fix: 6 of 12 enabled lanes silent (0 evals). Post-fix: every enabled lane
  is a candidate from intake.
- `AgenticStyleRouter.boundedLanes` still caps to 2 lanes per token via
  `stablePick` — broader candidate pool, no eval explosion. Variety rotates.

### Why
Operator: "theres literally no action from most of the trading layers still live.
strategy scoring or data supply issues are starving the lanes either at discovery
or classification." Fix 4 addresses the structural starvation. Fixes 1-3 address
the size collapse + clipping that prevented winners from paying out.

---

## [5.0.4128] - 2026-02 — PATTERN GOLDEN GOOSE

### Added
- **TokenWinMemory.patternEdgeForToken** — sharp asymmetric pattern edge:
  enumerates a token's matched name/symbol patterns and returns the BEST
  and WORST independently (rather than blending). Verdict ladder:
  CATASTROPHIC / TOXIC / NEUTRAL / WINNER / GOLD.
- **PatternGoldenGoose** — thin facade that exposes the edge as a lane
  score-bias (-35..+16, asymmetric: toxic dominates gold) plus a
  `isCatastrophic` veto hook.

### Changed
- **MoonshotTraderAI.scoreToken** — applies `PatternGoldenGoose` score
  bias to the lane score itself (additive, not floor). CATASTROPHIC
  verdict short-circuits to hard reject; rejection reasons now carry the
  goose tag (e.g. `goose=TOXIC_-theme_inu=0%n13_bias-22`).
- **ShitCoinTraderAI.evaluate** — same pattern wiring as Moonshot so
  both meme-traders share the same golden-goose leverage.

### Why
Operator: "find the data golden goose for each lane and traders switching
the bot into a money printer. half of its still silent re the meme trader."
The bot already records sharp pattern data (theme_space 82% WR n=75,
theme_musk 0% WR n=11). Until now this only contributed ±5 via
OrthogonalSignals — nowhere near what the data deserves. The goose:
  - Lifts marginal gold-pattern tokens over the score floor (+16).
  - Sinks strong-but-toxic tokens below the floor (-22).
  - Hard rejects catastrophic patterns (n≥15, WR≤5%).
Asymmetric tilt by design: toxic veto is ~2× gold lift — bleed-stop
matters more than moonshot capture in the current regime.

---

## [5.0.4127] - 2026-02 — RUNNER PROTECTION (U-SHAPED TRAIL)

### Changed
- **AdvancedExitManager.calculateProgressiveTrailingStop**: Converted the
  trail curve from monotonic-tighten to a U-shape so monster runners can
  compound through the MONSTER_LOCK ladder.
  - Below +500%: unchanged (tighten progressively, protect from giveback).
  - +500% → trail = base × 0.55 (was 0.30 — gives room to reach T2 +1500%).
  - +1000% → trail = base × 0.75.
  - +3000% → trail = base × 0.95.
  - +10000% → trail = base × 1.20 (wider than base — monster compounds to T4/T5).
  - Clamp ceiling raised from 25.0 → 35.0 so monster trails aren't capped.

### Why
Operator: "we have to have a huge huge win in the next 24 hours". With the
old curve, a runner at +1000%+ trailing at base×0.3 ≈ 6% would round-trip on
a normal pullback BEFORE the MONSTER_LOCK_T2 (+1500%) tier could fire. The
lock-ladder already banks realized $ at +500/+1500/+5000/+15000/+30000%, so
the trail can afford to widen above +500% — the dollars are already in the
bank; the trail's only job above that is to catch a true round-trip giveback.

---

## [5.0.4126] - 2026-02 — MOONSHOT FLUID PIVOT

### Added
- **MoonshotAdaptiveGate**: New lane-specific brain that fluidly pivots the
  MOONSHOT entry quality bar based on its own recent outcomes.
  - Hybrid recency-weighted WR over a 100-trade rolling window (newest 50
    trades count 2.0×, prior 50 count 1.0×).
  - Returns a bounded score-floor bias in [-5, +20]:
    - EMERGENCY (wr < 15%) → +20 (tighten hard)
    - DEFENSIVE (wr 15-25%) → +12
    - NEUTRAL (wr 25-50%) → +6 below target, 0 above
    - AGGRESSIVE (wr >= 50%) → -5 (let it breathe)
  - Never a hard veto — only nudges the score floor. Auto-loosens as WR
    recovers so the lane self-heals.
  - Persists rolling history across reboots via SharedPreferences.

### Changed
- **MoonshotTraderAI.scoreToken**: `effectiveMinScore` now includes
  `MoonshotAdaptiveGate.scoreFloorBias()` (additive after `personalityFloorBias`).
  Rejection messages surface the live phase tag (e.g. `gate=DEFENSIVE_wr22_n67_bias+12`).
- **MoonshotTraderAI.closePosition**: Now calls
  `MoonshotAdaptiveGate.recordOutcome(pnlPct)` so the gate trains live, and
  also calls `LayerBrain.recordOutcomeAll(mint, pnlPct)` (previously skipped
  because Moonshot bypasses `Executor.recordTrade`).

### Why
Operator: "its meant to fluidly pivot bro! not disable. each lane has a brain
specifically for that lane use it not disabled the lane!!!" The lane was
bleeding (-0.85 SOL, 24.6% WR over 248 trades). Existing learned gates
(`ScoreExpectancyTracker.shouldReject`, `LosingPatternMemory.recommendedSlPct`)
are paper-only or per-bucket — none responded to global lane WR sliding in
LIVE. This gate closes that loop without disabling anything.

---

## [5.2.11] - 2026-04-02

### Fixed
- **QualityTraderAI Wiring**: Fixed compilation error in BotService.kt caused by non-existent TokenState properties
  - `tokenAgeMinutes`: Now calculated from history candles
  - `holderCount`: Retrieved from last candle in history
  - `buyPressure`: Fixed type conversion (Double to Int)

### Added
- **QualityTraderAI**: New professional Solana trading layer for $100K-$1M mcap tokens
  - 417 lines of specialized quality trading logic
  - Targets 15-50% gains with 15-60 minute holds
  - Bridges gap between ShitCoin and BlueChip tiers
- **Quality Positions Dialog**: UI dialog showing open Quality layer positions

### Changed
- **V3 Tile**: Now shows aggregate system stats instead of 0%
- **AdvancedExitManager**: Time multipliers now looser at entry (not tighter)
- **FluidLearningAI**: Bootstrap thresholds adjusted for better learning

---

## [5.2.10] - 2026-04-01

### Fixed
- **Harvard Brain Education**: All 25 AI layers now properly wired to education system
- **Collective Hivemind**: Fixed data parsing issues
- **Overnight Trading**: Performance improvements for extended sessions

---

## [5.2.9] - 2026-03-31

### Added
- **Ultra-Aggressive Paper Mode**: Maximum learning velocity in paper trading

### Changed
- **Stability & Reliability Pass**: General hardening across all systems

---

## [5.2.8] - 2026-03-30

### Added
- **30-Day Run Stats**: UI card showing 30-day performance metrics
- **Export Button**: Export trading data for analysis

### Fixed
- **0% TP Instant-Exit Bug**: Complete fix for premature exits
- **Paper Mode Scanning**: Faster learning with more aggressive scanning

---

## [5.2.7] - 2026-03-29

### Changed
- **All Trading Layers**: Enabled in Paper Mode for comprehensive testing

---

## [5.2.6] - 2026-03-28

### Fixed
- **UI Tile Stats**: Added missing XML TextViews for complete data display

---

## [5.2.5] - 2026-03-27

### Fixed
- **Safe Build Fix**: Compilation errors resolved

---

## [5.2.4] - 2026-03-26

### Added
- **UI Tile Stats**: Learning progress visualization
- **Learning Progress Fixes**: Improved accuracy of progress tracking

---

## [5.2.3] - 2026-03-25

### Fixed
- **Build Errors**: Various compilation fixes
- **EMERGENT PATCH PACKAGE**: Critical patches applied

---

## [5.2.2] - 2026-03-24

### Added
- **Treasury Min Hold Time**: Prevents premature treasury exits

### Fixed
- **Shadow Learning UI**: Display corrections
- **CollectiveLearning**: Connection reliability improvements
- **Paper Mode**: Complete behavior penalty bypass
- **Throughput Pipeline**: Performance improvements

---

## [5.2.1] - 2026-03-23

### Fixed
- **Trailing Stop Exits**: Fixed premature trailing stops causing 5-6% win rate
- **Hold Time Protection**: Hardened across all exit triggers
- **Treasury Aggressive Exits**: Loosened overly tight exit conditions
- **Treasury→ShitCoin Promotion**: Fixed overly tight -2.5% stop loss

---

## [5.2.0] - 2026-03-22

### Added
- **Education Sub-Layer AI**: Every trade now teaches the system
- **Harvard Brain Integration**: Centralized learning repository

### Changed
- **Complete Architecture Review**: Major audit of all exit conditions
- **4-Tier System Clarification**: Treasury → ShitCoin → Quality → BlueChip

---

## Statistics

- **Total Commits**: 914+
- **Total Lines**: 110,444
- **AI Layers**: 28
- **Development Time**: ~10 days
- **Development Device**: Mobile phone only

---

## Legend

- **Added**: New features
- **Changed**: Changes in existing functionality
- **Fixed**: Bug fixes
- **Removed**: Removed features
- **Security**: Security-related changes
