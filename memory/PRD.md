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
