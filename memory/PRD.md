# AATE — Autonomous AI Trading Engine
## Product Requirements & Current State

### Original problem statement (verbatim user direction)

Upgrading a Native Kotlin Android Solana trading bot. SOL Perps + tokenized
stocks, Insider wallet tracker, live readiness gauge, continuous auto-replay
learning. "The meme trader is meant to be a money printer."

### Hard infrastructure constraints

* **NO LOCAL COMPILER.** Builds go through GitHub Actions CI via `git push`.
* Brace/paren balance MUST be verified via `git diff` counter before push.
* MEME TRADER MUST NOT BE CHOKED.
* Operator philosophy: **fewer better trades at proper size**, ruthlessly
  kill losing strategies/lanes — *not* dust-probe more candidates.

---

## Implemented this session (Feb 2026 fork)

| Version | What |
|---|---|
| 5.0.4173 | gzip transparent decode hotfix. Bot revival from total RPC death. |
| 5.0.4174 | Jupiter Tokens V1→V2 (`tokens.jup.ag` deprecated). |
| 5.0.4175 | 4-way cycle-bloat unchoke (SCAN_BATCH 14→8s, PROBATION 120→90s, Birdeye CU brownout at ≥98%, cycle-overrun forensic). |
| 5.0.4177 | (Wrong philosophy) 4-way WR-feedback unchoke — reverted next push. |
| 5.0.4178 | Selectivity-first reset. Reverted 4177 L1/L2, tightened lane bias ×1.40/×0.50, L5 strategy pivot accel (toxic threshold n>=10), L7 worst-lane suppression while WR<45%. |
| 5.0.4179 | Lift the bot — F1 slip-aware entry sizing, F2 predictive SL, F3 high-conviction size ceiling 1.5×, F4 UnifiedPolicyHead graduation accel (20/60/150 tiers), F5 WATCHLIST_FLOOR lifted to $8K while WR<30%. CI build #4180 green. |
| 5.0.4180 | F6 sell-side PHANTOM_WIN guard on partial sells (Executor.kt:5817+). CI red on 4181 — `Val cannot be reassigned`. |
| **5.0.4182** | **REAL PRICE LOCK (new) + CI red fix.** liveScore val→var (unblocks 4180 phantom guard). New `RealPriceLock.kt`: for any TP delegate >+500% or runner-bank >20x, issue a Jupiter quote on 1% of position and compare implied gain vs claimed gain (≥40% ratio = real, bank; below = phantom, defer). Failure-soft (never blocks real winners on Jupiter errors). FDG WATCHLIST_FLOOR lift made lane-aware (don't lift to $8K for SHITCOIN — its own $500-1.5K floor suffices; lift was choking 76% of FDG decisions per V5.0.4181 dump). CI #4182 GREEN. |
| **5.0.4184** | **F1 SLIP UNCHOKE (operator P0).** V5.0.4183 dump showed 188 EXEC attempts → 0 BUY ok because `F1_SLIP_HARD_REJECT` was silently rejecting every buy with `expectedSlip=11,726,049%`. Predictor bands had been poisoned by V5.0.4181-era phantom sells feeding millions-of-percent slip samples into `ExecutionCostPredictorAI.learn()`. Fix (4-part): (a) `learn()` rejects slip > 200% at source, (b) `expectedExtraSlipPct()` caps return at 50%, (c) one-shot boot-time `purgePoisonedBands()` resets any band with avg > 200%, (d) F1_SLIP_HARD_REJECT now surfaces to `onLog` + per-liq-band counter for visibility. CI #4184 GREEN. |
| **5.0.4185** | **RUGCHECK UNCHOKE (operator P0).** V5.0.4184 dump: 29 buys in first minute then DEAD silent — 110/119 FDG blocks were `HARD_BLOCK_RUGCHECK_PENDING_REVIEW_WEAK_FALLBACK`. Birdeye at 90.9% CU → rugcheck timing out → `PENDING_REVIEW` on nearly every fresh mint. The fallback required liq≥$5K + press≥60 + 2-of-3 signals — the pump.fun firehose lives at $2-5K with press<60. Fix: lower liq bar $5K→$2.5K, press bar 60→45, allow 1-of-3 with single strong (press≥70 OR liq≥$10K). criticalBothWeak still HARD-blocks unsupportable mints. CI #4185 GREEN. |
| **5.0.4186** | **BIRDEYE = BACKUP ONLY (operator P0).** Operator: "we can get the data birdeye provides free. its meant to be deprioritised to basically just be a back up". 6 Birdeye providers bypassed `BirdeyeBudgetGate` and burned CU on every cycle — chiefly `DataOrchestrator.seedCandleHistory` (3-4 calls per new token × 500+ pump.fun tokens/session = ~2K calls just for seeding). All 6 now gate on `canAffordScannerLane()` BEFORE issuing the call. When throttled (≥60% daily / ≥75% monthly), they SKIP and let DexScreener/PumpFun-WS/HeliusWS provide the data via existing free-source seeders. `BirdeyeMintBurnMonitor` (open-position safety) left alone — protected by `canAffordSafety()` tier. CI #4186 GREEN. |

---

## 🚨 P0 BLOCKER (newest finding, not yet fixed): PHANTOM-WIN POISONING

**Evidence from field notifications:**

```
2nd partial — Apollo:   sold 25% | PnL +210425.3% (+6.0490 SOL)   ← PHANTOM
2nd partial — WEEKEND:  sold 25% | PnL +242342.9% (+6.9665 SOL)   ← PHANTOM
Live Partial — Apollo:  sold 25% | PnL -31.75%   (-0.0012 SOL)   ← REAL
Live Partial — WEEKEND: sold 25% | PnL -66.98%   (-0.0026 SOL)   ← REAL
```

Wallet says -0.282 SOL canonical PnL. The +6 SOL / +7 SOL "wins" never
landed. The bot's partial-sell PnL uses oracle price (which spikes on
sandwiches/thin-pool prints), not actual Jupiter swap output.

`PHANTOM_MULTIPLE_GUARD` exists and works at ENTRY (`raw=70× >> priceMove=1.02×`
→ blocked). Same guard is NOT applied at SELL-side PnL booking. Result:
TokenWinMemory has 422 "winners" but most are likely phantoms. Pattern
memory's `theme_space 81.6% WR avgWin=47.5%` is phantom-poisoned. Bot
learns to chase ghosts → real fills are dust → bleed.

**Pending P0 fix (V5.0.4180):**
* **F6 — SELL-SIDE PHANTOM GUARD.** Compare oracle PnL vs realized SOL
  delta on every partial/full sell. If `oraclePnL > 1000%` AND
  `realized < 0.10 SOL`, override booked PnL with realized delta, tag
  journal `PHANTOM_SELL_PRICE_SUPPRESSED`, suppress phantom notification,
  do NOT write to TokenWinMemory/PatternMemory as winner.
* **F7 — Retroactive purge of phantom winners.** Startup pass: demote any
  TokenWinMemory entry with `claimed_pnl_pct > 1000%` AND `realized_sol
  < 0.05 SOL`. PatternMemory rebuilds on real data.

---

## ✅ V5.0.4182 — REAL PRICE LOCK landed (verification pending)

**Operator mandate (V5.0.4181 dump response):** "meme coins absurd gains
CAN be realised. I just want real pricing data locked in so if it
happens it's legit and the bot takes the profits."

**Approach:** No PnL caps. Real meme moonshots can do 1000x+ — we want
to BANK those. Phantom signals come from oracle blips that don't
survive a real swap (V5.0.4181: bot saw 45x gain, Jupiter route only
filled +19.4%). New `RealPriceLock` issues a tiny Jupiter probe on 1%
of position to verify implied gain matches claimed gain before banking.

**Trigger points wired through RealPriceLock:**
* `BotService.rapidStopLossMonitor` — for `RAPID TAKE_PROFIT_DELEGATE`
  when claimed PnL ≥ +500%
* `Executor.checkRapidProfitLock` — for `ULTRA_RUNNER_BANK` when
  gainMultiple ≥ 20x

**Failure-soft contract:** any Jupiter error returns `true` → bank.
The phantom-cost is one missed cycle. The real-cost would be missing
a 100x moonshot. Per-mint 3s cache prevents Jupiter spam.

**FDG floor lane-aware:** V5.0.4181 dump showed 22/29 FDG blocks were
LIQUIDITY_BELOW_WATCHLIST_FLOOR. The $8K WR-weak lift was choking
SHITCOIN (which scans pump.fun's $2-5K firehose). Lift no longer
applies to SHITCOIN — its own EXECUTION_FLOOR ($500-$1.5K) is the
proper gate for that lane.

**Pending user verification:** install V5.0.4182, share next unified
operational report. Look for:
1. `REAL_PRICE_LOCK_CONFIRMED` / `REAL_PRICE_LOCK_REJECT` forensics
2. No more `ultra_runner_bank_45.2x` style closes that realize +19% PnL
3. FDG allow/block ratio recovering above 0/29
4. WR drifting back above the 30% gate-relaxer floor

---

## Pending audit findings (lower priority)

* `jupiter_quote` 4xx storm — Ultra v2 endpoint requires API key; bot falls
  back to v6 OK. UX noise only.
* PumpFun rate-limits us periodically — self-heals.
* Backlog UI items: Ladder pill, Strategy Leaderboard, Brain Health pill,
  `/positions backup` export, Tune History tab.

---

## File map (most-touched this session)

```
app/src/main/kotlin/com/lifecyclebot/
├── engine/
│   ├── BotService.kt                 (23.7K lines, L7 lane suppression)
│   ├── Executor.kt                   (F1, F2, F3 sizing + slip-aware SL)
│   ├── FinalDecisionGate.kt          (F5 floor lift, rugcheck HARD restore)
│   ├── BirdeyeBudgetGate.kt          (V5.0.4175 brownout)
│   ├── GlobalTradeRegistry.kt        (PROBATION 90s)
│   ├── LiveLayerGateRelaxer.kt       (DOCTRINE_FLOOR 30, public WR accessor)
│   ├── LiveStrategyTuner.kt          (toxic threshold n>=10)
│   ├── TokenSafetyChecker.kt         (RUGCHECK_TIMEOUT_PENALTY=14)
│   ├── UnifiedPolicyHead.kt          (graduation 20/60/150)
│   └── SolanaMarketScanner.kt        (SCAN_BATCH 8s)
└── network/
    ├── SharedHttpClient.kt           (gzip transparent decode V5.0.4173)
    └── JupiterStrictTokenList.kt     (V2 endpoint V5.0.4174)
```
