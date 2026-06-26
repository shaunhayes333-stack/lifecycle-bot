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
