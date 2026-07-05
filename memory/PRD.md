# AATE (Adaptive Agentic Trading Engine) — PRD

## Original Problem Statement
Native Kotlin Android Solana trading bot upgrading toward V5.7+ (currently V5.0.6070+). Building a super-smart SOL Perps/Leverage trading system with tokenized stocks, multi-asset trading, insider wallet tracker, live readiness gauge, continuous auto-replay learning system, sentient AI personality, and LLM Lab sandbox.

**NO LOCAL COMPILER** — every change goes through GitHub Actions CI.

## User Persona
Solo operator running live SOL on-device. Extremely frustrated by losses and instability. Values transparency, brevity, and no-BS engineering. Never runs paper mode by default (learned during this session — now enabled for brain rebuild).

## Session Changelog (V5.0.6065 → V5.0.6070)

### V5.0.6065 — API REDUNDANCY EXPLOSION ✅ CI GREEN + ON-DEVICE
- Added 5 free Solana RPC fallbacks to `WalletManager.FALLBACK_RPCS`:
  BlastAPI, BlockPI, OmniaTech, Jito, BlockEden. Total: 12 → 17 layers.
- Added 6 keyless crypto price sources to `PriceAggregator`:
  GeckoTerminal, DIA Data, Jupiter Lite, Raydium v3, CoinPaprika, CoinCap.

### V5.0.6066 — FLIP-TO-GREEN sizing floor ✅ CI GREEN + ON-DEVICE
- `Executor.kt`: raised compound sizing floor for MOONSHOT/STANDARD from 0.25 → 0.45
  when lane is not "healthy" tier. Winners now size 2× larger.

### V5.0.6067 — Emergency lane pause + UI recreate fix ✅ CI GREEN + ON-DEVICE
- `LaneAutoPauseGuard`: `ZERO_WIN_MIN_SAMPLE 15→8`, `TOXIC_MIN_SAMPLE 20→12`,
  `TOXIC_EV_PCT -40→-20`. Hard-seeded PRESALE_SNIPE + QUALITY pauses.
- `AndroidManifest.xml`: MainActivity `configChanges` added keyboard, keyboardHidden,
  navigation — fixes UI recreation loop that cleared all tiles.

### V5.0.6068 — Position preservation + inverted score fixes ✅ CI GREEN + ON-DEVICE
- `BotService.forceStartupGhostReconcile`: two-read wallet consensus + API-health gate.
  Positions no longer drop on install-over when Helius is 429ing.
- Startup sweep: 5s → 90s grace + second-price confirmation.
- `LiveStrategyTuner.asymmetric_runner_exempt`: removed the `&& sol >= 0.0` gate
  that inverted the exempt. Lanes with EV≥20% or avgWin≥50% or PF≥4 now exempt
  from bleeder logic regardless of temporary net-SOL variance.
- `LiveStrategyTuner.runner_lane_exempt`: sample gate 30 → 15.
- `RegimeDetector.laneAwareSizeMultiplier`: DUMP no longer squashes proven-winner
  lanes uniformly. Winners stay at ≥0.80, priority lanes at ≥0.70.
- `Executor`: wired lane-aware regime mult into size stack.
- `MainActivity`: UI stickiness — tiles don't flash blank on 0-read.

### V5.0.6069 — Paper mode = learn everything ✅ CI GREEN + ON-DEVICE
- `LaneAutoPauseGuard.isPaused()`: paper mode bypasses all pauses.
- `EnabledTraderAuthority.isEnabled()`: paper mode returns true for all Traders.
- `BotService.isMarketsLaneEnabled()`: paper enables Markets universally.
- Cyclic ring: paper mode forces every tick.
- Empirical result on-device (10 min uptime): WR 13% → 29%, 5 winners at 318% avg,
  53 patterns learned, MOONSHOT WR=50%, real +100% winners firing.

### V5.0.6070 — LANE_EVAL visibility widening ✅ CI GREEN
- `BotService` line ~10131: expanded shadow LANE_EVAL emit set from
  `{QUALITY, MOONSHOT}` to the full doctrine surface (SHITCOIN, EXPRESS,
  SHITCOIN_EXPRESS, PROJECT_SNIPER, MANIPULATED, DIP_HUNTER, BLUECHIP,
  TREASURY, CASHGEN, STANDARD, CYCLIC, MARKETS, CRYPTO_ALT, STOCK).
- Fix scope: visibility only. Deeper "why doesn't SHITCOIN emit a BUY when
  routed as primary" question requires fresh-context session.
- ⚠️ Original push was RED — `GoldenTapeRegressionTest.botService_4489QualityMoonshotAndCoreVisibilityCannotDisappear`
  fails because the widened `setOf(...)` no longer contains the literal
  `setOf("QUALITY", "MOONSHOT")` needle. Fixed in V5.0.6071.

### V5.0.6071 — PAPER SELL CHOKE FIX + 4489 regression restore ✅ CI GREEN (c7df9f9c)
- `PaperPositionCloseAuthority`: `STUCK_CLOSE_TTL_MS = 120_000L` — 2-min TTL
  releases CLOSE_REQUESTED / CLOSING states so paper mints no longer block
  future sells forever after a silent mid-sell failure. Terminal `CLOSED`
  still an absolute block.
- `Executor`: `FAILED_RETRY_TTL_MS = 20_000L` frees orphaned `paperSellLocks`.
- `BotService` line ~10141: introduced `qualityMoonshotFloor4489 = setOf("QUALITY","MOONSHOT")`
  as a named val (preserves 4489 golden-tape invariant literal) plus
  `widenedLaneReadFloor4489 = qualityMoonshotFloor4489 + setOf(...)` for the
  actual 6070 widening. Semantics identical to 6070, regression restored.
- Redacted GitHub PAT from `memory/PRD.md` (previous auto-commit
  `da9f61c` had inlined it; push was blocked by GH secret protection).

## Hivemind bootstrap on fresh install — VERIFIED (no code change needed)
Fresh installs already receive the shared knowledge boost:
1. `BotService.onCreate()` calls `CollectiveLearning.init(appContext)` (line 5495)
2. `init()` → `downloadAll()` pulls:
   - Blacklist (mints with ≥ 3 reports)
   - `collective_patterns` (all patterns with ≥ 10 trades)
   - `mode_performance` (modes with ≥ 20 trades)
   - `whale_effectiveness` (whales with ≥ 5 follows)
   - `token_mints`
3. `CollectiveIntelligenceAI.refresh()` populates `patternQualityCache`,
   `modePerformanceCache`, `tokenPredictionCache`, `consensusCache`.
4. `AdaptiveLearningEngine.applyHiveGenomeNudge()` (BotService line 5539)
   applies proven-peer weight nudges from ALL positive-performing peers.
Turso URL + token defaults are hardcoded in `TursoDefaults` so the boost
works with zero user configuration on every fresh install.

## P0 / P1 / P2 Backlog

### P0 (next session)
- Trace why SHITCOIN, EXPRESS, MANIPULATED, DIP_HUNTER, PROJECT_SNIPER, CYCLIC
  lanes get selected as `CYCLE_PRIMARY_LANE` but never emit a BUY. Suspects:
  `INTAKE_PROBATION_ONLY` routing, V3 scoring gate rejecting these lanes,
  or `nonMemeSpecialist`/affinity gating in the owner-rotation router.
- Auto-close the Codex zombie -90% position that survives across restarts.
- LiveStrategyTuner still shows `size×=0.40 label=toxic_reclaim_tactic_pivot`
  for MOONSHOT — 6068's fix may not be firing due to `mean` field mismatch
  between leaderboard EV and tuner meanPnlPct. Needs verify + likely
  additional condition using `avgWinPct` directly.

### P1
- MainActivity ANR: onCreate hits 5+ times as top blocker. Config-changes
  fix in 6067 may not be complete — investigate whether background service
  is causing Activity leaks or unnecessary lifecycle events.
- FDG_FANOUT_EXPLOSION: FDG_decisions/intake=3.08 (target ≤ 1.0).
- `jupiter_send sr=0%` when helius_sender is dead — needs alt broadcast path.

### P2
- Add "Ladder" status pill on Memes tab (e.g., `🟡 TIER 2 · target 24.6% · actual 10.6%`).
- Strategy Leaderboard tile on main UI (top-3 strategies by live expectancy).
- "Brain Health" pill next to sentiment badge.
- "Tune History" UI tab under Behavior.
- `/positions backup` export button.

## 3rd Party Integrations
- GitHub Actions CI (PAT in `/tmp` bash calls)
- Helius (rate-limited 429), Birdeye, Jupiter, DexScreener, CoinGecko
- + V5.0.6065: GeckoTerminal, DIA Data, Raydium v3, CoinPaprika, CoinCap
- + V5.0.6065: BlastAPI, BlockPI, OmniaTech, Jito, BlockEden (RPCs)

## Files of reference (latest edits)
- `app/src/main/kotlin/com/lifecyclebot/engine/BotService.kt` (10,000+ lines, careful edits)
- `app/src/main/kotlin/com/lifecyclebot/engine/Executor.kt` (compound sizing + 45s post-buy)
- `app/src/main/kotlin/com/lifecyclebot/engine/LiveStrategyTuner.kt` (asymmetric exempt)
- `app/src/main/kotlin/com/lifecyclebot/engine/RegimeDetector.kt` (lane-aware sizing)
- `app/src/main/kotlin/com/lifecyclebot/engine/LaneAutoPauseGuard.kt` (paper bypass + thresholds)
- `app/src/main/kotlin/com/lifecyclebot/engine/EnabledTraderAuthority.kt` (paper trader bypass)
- `app/src/main/kotlin/com/lifecyclebot/engine/WalletManager.kt` (RPC fallback fleet)
- `app/src/main/kotlin/com/lifecyclebot/perps/PriceAggregator.kt` (keyless price sources)
- `app/src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt` (UI stickiness)
- `app/src/main/AndroidManifest.xml` (configChanges)

## Session 2026-07-06 — SpikeGuard + ANR kill + WR tuning (V5.0.6120→6120c)

- **6120 ✅ CI GREEN**: `SpikeGuardExit.kt` — chunked wick-capture ladder. Operator report showed ansem/RUNNER +3358% mark peak collapsing to -87% while position stayed open. Root cause traced: `peakGainPct` ratchets off Jupiter-verified full-bag price (thin pool → lags wick by 10-100x), so parabolic tick never fires exits. Also `WS_TICK_FILTER` rejected any tick jumping >100x as feed glitch. Fix: (a) new SpikeGuardExit tracks per-mint mark peak from raw WS tick, arms at +500%, full-exit at +1000%, also fires on 30% give-back; (b) chunked 4-partial ladder (30/30/25/15) with 250ms spacing on a daemon thread so tick loop never blocks; each partial goes through executor's existing Jupiter Ultra → Metis → PumpPortal → PumpFunDirect escalation, so 25% chunk fills where 100% sell would price-impact-abort; (c) WS_TICK_FILTER now passes upward wicks 100x-100000x on OPEN positions through to SpikeGuardExit; (d) tick-loop hook fires guard on every fresh WS tick using RAW priceUsd. Forensic events: SPIKE_GUARD_FIRE_6120, SPIKE_GUARD_CHUNK_FIRE_6120, WS_TICK_UPWARD_WICK_PASS_6120.

- **6120b 🟡 building**: `TradeHistoryStore.kt` main-thread cache guard on `getAllSells`, `getAllValidTradesSnapshot`, `getAllTradesFromDb` (worst — full SQLite cursor scan), and `getAssetBreakdown`. Operator report showed 3 ANR hints, max frame gap 49087ms, total stall 53362ms (3.9% of uptime) with sampler pinning the blame on `TradeHistoryStore.getAllSells` + `MainActivity.onCreate`. Applied same main-thread cache template already used by `getRecentValidClosedTradesRaw` / `rollingWinRatePct`: 3-5s TTL, async IO-dispatcher refresh, per-cache in-flight guard. Background callers still do full scan and update cache. Expected: ANR_HINTS → 0, MainActivity.onCreate < 200ms even with 5000+ trades.

- **6120c 🟡 pushed**: `TokenWinMemory.patternEdgeForToken` + `LosingPatternMemory.isDangerZone` — WR/EV tuning based on operator July 2026 pattern data. TWO bugs fixed:
  (1) **Goose thresholds too conservative on current 1500-trade dataset**: `theme_dog n=14 WR=0%` and `theme_pump n=14 WR=0%` were TOXIC-only (n<15 for CATA), so bot kept buying them. `theme_elon n=9 WR=22% avgWin=132%`, `theme_baby n=9 WR=22% avgWin=80%`, `theme_frog n=8 WR=37% avgWin=48%` were NEUTRAL (n<10 and WR<70%) despite +29% / +18% / +18% EV per attempt. Fix: lowered inclusion n>=10→n>=8; CATASTROPHIC to WR<=10%/n>=10; TOXIC to WR<=15%/n>=8; GOLD to WR>=60%/n>=8 OR EV>=20% (evGoldHit path with avgWin>=30% guard); WINNER to WR>=50%/n>=8.
  (2) **False-positive danger buckets**: `LosingPatternMemory.isDangerous` is a pure loss-rate flag ignoring `meanPnl`. `recommendedSizeMult` already had the "positive-EV bucket is not a bleeder" guard at line 213 — but `isDangerZone()` (consumed by BrainConsensusGate/LaneToxicityGuard/MoonshotArbiter) skipped it. So `MOONSHOT|S41-60` (16L/6W meanPnl=+61.5%) and `QUALITY|S61+` (9L/3W meanPnl=+10.5%) were damped away despite being POSITIVE-EV moonshot bands. Fix: `isDangerZone()` now requires meanPnl <= 0. Positive-EV buckets keep firing.

### PENDING (still on the board)
- P1: `MainActivity.onCreate` `AsyncLayoutInflater`/`ViewStub` for `activity_main.xml` inflation (6120b addresses the SQLite side; layout side still on main thread).
- P1: Supervisor worker timeouts (138 in the 22-min report window). Cycle avg 17s / max 2m16s — well over the 30s "overload" threshold. Blocking hot-path API calls (Birdeye 67% success, CoinGecko 16% success) inside 9s worker budget.
- P1: Collective blacklist auto-feed pipe — report shows 0 blacklisted despite 77 losing patterns in PatternMemory. Some sync between LosingPatternMemory and CollectiveLearning is broken.
- P1: STANDARD lane: 47% WR but -8% EV (losses ~2x wins on average). Bleeder recovery pivot is applied (size ×0.51, tp ×1.16) but hasn't turned it profitable yet.
- P2: SsiPilotCouncil `exitPatience` not yet consumed by exit layers.
- P2: Ladder status pill / Strategy Leaderboard tile / Brain Health pill / positions backup export / Tune History tab / Hivemind startup gate.
- P3: Wire 4 direct-DEX Jupiter-restricted parallel-race quotes for spike sells (currently the ladder uses sequential fallback).

## Critical rules for next agent
1. **BRACE/PAREN COUNT before every push.** Use `git diff | grep -o '(' | wc -l` deltas.
2. **NO LOCAL COMPILER** — CI is the compiler. Wait for green before declaring done.
3. **DO NOT UNINSTALL to update** — install-over only. Uninstall wipes the learning DB.
4. **Paper mode learns without spending SOL** — encourage user to use it after installs.
5. **User is emotionally exhausted** — be brief, honest, and never over-promise.

## Known workflow
- Test credentials: N/A (self-managed by user's device install)
- GitHub PAT: (stored in local env only — never commit to files)
- Repo: `shaunhayes333-stack/lifecycle-bot` on `main` branch


## Session 2026-07-03 — Deep Audit + SSI Pilot + Per-Lane Liberation (V5.0.6072→6076b, all CI GREEN)
- **6072**: PRESALE_SNIPE danger-zone re-armed (live: S<30 or danger band blocked; paper: proven-toxic only). PROJECT_SNIPER→PRESALE_SNIPE canon merge in LaneAutoPauseGuard. BLUECHIP hard-seed pause (0/7). Supervisor throttle arms at 15s (was 30s). Paper parity: PROTECTIVE_PEAK_PARTIAL / ULTRA_RUNNER_BANK / WALLET_GROWTH_HARVEST now fire in paper via executeProfitLockSellPaperOrLive. CashGen unchoke: treasury liq floor 12K/25K → 5K/10K (router admitted ≥2K, AI rejected <25K — lane was dead). TREASURY/CASHGEN rescue extended to paper rotation.
- **6073**: NEW SsiPilotCouncil (engine/SsiPilotCouncil.kt) — LLM pilot fuses SymbolicContext + lane truth + MetaCognition + Lab summary → bounded directive every 5min (sizeBias, laneFocus/Avoid, exitPatience, note). Live clamps 0.85-1.15, paper 0.70-1.35, TTL 45min fail-open. Lane resume autonomous in PAPER, proposal-only in LIVE (control tower). Wired into Executor sizing stack ("ssiPilot"). SHADOW ALWAYS-ON: all 4 gates removed (toggle/meme-only/paper-mode). Lab promotion now lifts LaneAutoPauseGuard too. Mistral joined LLM council (key in DefaultKeys XOR, verified live).
- **6074**: CI regression fixes (golden-tape literals via NAME_SHADOWING locals; AATE_VERSION must stay "5.0" — patch derives from CI run number). Alchemy Solana RPC added as #2 fallback layer (key verified: getHealth ok).
- **6075**: PER-LANE DAMPENER LIBERATION — (1) LiveStrategyTuner net_positive_lane_floor_6075 (sol>0, n≥5 never sized <1.0); (2) LiveProbabilityEngine RAPID_PIVOT exempts positive-EV lanes; (3) RegimeDetector DUMP mult per-lane (net-positive → 1.0); (4) RealizedWalletCompoundingGovernor.sizeMultiplierForLane (defensive squeeze lifted for net-positive lanes).
- **6076/6076b**: WalletReconciler orphan recovery consults PositionPersistence FIRST — original lane/entry/cost restored instead of zero-basis WALLET_RECOVERED stub.

### PENDING (next batch)
- P1: Blank UI on update — async inflation (AsyncLayoutInflater/ViewStub) for activity_main.xml + "Loading positions…" state + UI reads persisted store during service warmup. NOT DONE YET.
- P1: Cycle-time deep fix (avg 9.5s, max 77s) — throttle now arms at 15s but root-cause IO wedge in supervisor batch not yet traced.
- P2: SsiPilotCouncil exitPatience is exposed but not yet consumed by exit layers. Wire into runner-hold patience next.
- P2: Ladder status pill / Strategy Leaderboard tile / Brain Health pill / positions backup export / Tune History tab / Hivemind startup gate.

### CRITICAL REMINDERS
- NO local compiler — push to main triggers GitHub Actions; AATE_VERSION file must stay "5.0".
- Golden tape needles are file-content literals — never rename `executeProfitLockSell(ts, wallet, sellFraction...` call shapes; use NAME_SHADOWING locals for null-safety.
- NEVER write the GitHub PAT to any file.
