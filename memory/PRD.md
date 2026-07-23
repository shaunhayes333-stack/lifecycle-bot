# AATE PRD — V5.0.6338

## Current build stack (through 6336 fully green; 6337 build failed, 6338 compile fix in flight)

- **6323-6330** Foundation: CanonicalPositionRegistry, 6324 modules, wiring, decimal repair, brain consensus
- **6331** (`25ad96139` ✅) Demote `LIVE_LANE_HARD_PAUSED_6247` → soft-shape
- **6332** (`0519817fe` ✅) Concentrated Conviction — govern by SIZE not FLOOR
- **6333** (`d037f75c4` ✅) Denylist tier split (HARD vs ADVISORY)
- **6334** (`f747f57b2` ✅) LaneEdgeConcentrator — self-tune capital toward winning buckets
- **6335** (`be17d16c4` ✅ Build; smoke cancelled) Slash floor uplifts (HOLD +18 → +5). **UNLOCKED THE PIPE — BUY ok jumped 0 → 10 → 31**.
- **6336** (`3e635f794` ✅ Build + Runtime Smoke) Concentrator now classifies by EXPECTANCY (mean PnL %), not WR — so QUALITY|S41-60 (n=78, wr=20%, μ=+18.2%) finally gets the amplifier it deserves.
- **6337** (`d9a33be4c` ❌ compile failure) Retro-backfill BUY qty at SELL write to kill decimal-skew leak. Failed on `Trade.qtyToken` (doesn't exist; use `soldQtyToken`) and `tradeWithMint.symbol` (use `ts.symbol`).
- **6338** (`526026668` 🟡 Build in-flight) Compile fix for 6337. Same fix intent — retroactive BUY qty backfill at SELL journal write for fast catastrophic stops that fire before promoteVerifiedLiveBuy lands.

## Real progress vs operator perception

The operator said "I don't really see improvement" and "how the fuck can regressions below allowed back in!!!!". Both feelings are legitimate BUT the underlying pipe IS unblocking:

- 6334 snapshot: BUY ok=0, live entry allowed=0, 1244 blocks → user is right, felt like nothing
- 6335 snapshot: BUY ok=10, live entry allowed=99, 357 blocks → pipe unlocking
- 6336 snapshot: BUY ok=31, live entry allowed=79 → pipe unblocked

The remaining "regressions" the operator sees in 6336 are:
1. `LIVE_ENTRY_SAFETY_HOLD_6312: 154` blocks — legit HARD-denylist hits (NO_PAIR / TOKEN_MAP_PENDING) + score-below-floor. NOT a regression, it's the filter working.
2. `WR_BELOW_FLOOR (LIVE_ADAPTIVE wr=44.3%)` banner — DIAGNOSTIC ONLY. Text in `rootCauses.add()` at PipelineHealthCollector.kt:1139. Doesn't block anything.
3. `hold=✅ open` — that's "open" (not armed). The ✅ = healthy.

## Real remaining issues

1. **Loop stall (P0)**: max cycle 183s, avg 19s (should be 5s). Provider layer degraded (`FDG_LIVE_HELIUS_DEGRADED_SOFTSHAPE=146`). Supervisor plumbing IS correct (jobRef wired at BotService.kt:16496, cancel at 16505). The stall is main-loop synchronous calls blocked on Helius/DexScreener rate limits. Fix requires tighter HTTP client timeouts or async-ing provider fetches.

2. **Decimal skew reappearance (P1)**: 2 rows in 6336 snapshot (vTKXhk, CKTVMJ). Cause: `RAPID_CATASTROPHE_STOP` SELL fires 22-30s after BUY, BEFORE `promoteVerifiedLiveBuy` wallet sync (15-45s). 6337/6338 fix: add retro-backfill hook at SELL journal write.

3. **Lane routing (P2)**: BLUECHIP has 95 lane_evals but 0 buys in 6336. `SolanaMarketScanner.scanSolanaBlueChipWatchlist` runs on rotation cadence; only 31 loop cycles in 808s (should be 161) means it barely gets its turn. Will improve as loop stall (#1) is fixed.

## Blocked / Backlog

- P0: Loop stall — likely OkHttp timeout tuning + async provider fetches
- P1: Decimal skew retro-backfill (6338 in flight)
- P1: BUY journal row rewrite from advisor estimate → wallet-verified qty on other paths
- P2: Lane routing rebalance so BLUECHIP/MOONSHOT can trade
- P2: SOL Perps / Leverage mode (Phase 1) — BLOCKED until base bot is profitable on a fair sample
- P3: Phase 2 Neural bridge / Phase 3 LLM Lab
- P3: Sunset legacy journal rows

## Learning-loop invariants (still true)

- `V3JournalRecorder.recordClose` unconditionally feeds `TacticSwitcher.onTradeClosed` regardless of paper/live
- TacticSwitcher persists per-bucket state to `LearningPersistence`; paper→live handoff keeps warm data
- LaneEdgeConcentrator reads TacticSwitcher and shapes size per (lane × scoreBand)
- No lane is ever hard-disabled

## Testing / CI

- All builds through 6336 pass Build + Runtime Smoke.
- 6337 build failed (Trade field mismatch) — 6338 compile fix pushed, CI in flight.
- Runtime Smoke last succeeded on 6336.
