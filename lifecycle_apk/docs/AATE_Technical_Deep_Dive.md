# AATE: A Trading Engine That Has to Prove Itself

**AATE — Autonomous Algorithmic Trading Engine** · 5.0.7288 · September 2026

AATE is a native Android app that runs a complete autonomous trading engine on the phone. Scanning, safety checks, scoring, admission, sizing, execution, exits and learning all happen on the device. It trades Solana first. One developer built it in six months, from a phone, with no team and no big budget, and GitHub Actions compiles it.

The Telegram and web bots in this space (Photon, BullX, Trojan, GMGN, Banana Gun, BonkBot) execute the user's clicks. AATE makes its own decisions, which puts a higher bar on it: it has to show its decisions are better than chance before it is allowed to act on them. This piece explains how.

> Trading crypto is high risk. Paper results are not live results. Nothing here is financial advice.

---

## 1. The numbers

| | |
|---|---|
| Production Kotlin | 1,200 files, ≈457,000 lines |
| Tests | 2,699 `@Test` cases in 339 files |
| CI static validators | 16, all hard gates before Gradle |
| Traders in one engine | 16 (meme lanes, crypto alts, tokenized stocks, commodities, metals, forex, perps, shadow paper) |
| Screens | 22 Activities |

## 2. One service, many supervised loops

Everything runs inside one foreground service, `BotService`. Its work is split across coroutines on separate dispatchers:

- the **bot loop** (scan → score → gate → buy) on its own single-thread dispatcher;
- a **1 Hz mark loop** that prices every open position and runs the fast exits;
- a **supervisor** that relaunches the mark loop when an iteration has been in flight for 30 s or more. Since 5.0.7288 a stall is detected by matching start and end sequence numbers rather than by clock gaps;
- an **exit coordinator** running full exit sweeps plus a universal stop-loss sweep across every lane;
- **off-loop sells**: since 5.0.7288, sells triggered by the mark loop run on the IO pool, one per mint. A hung sell can no longer freeze pricing for the rest of the book.

That last change came from a device log. On 5.0.7287 the mark loop called a sell synchronously, the sell never returned, 57 of 61 positions went stale, and no exit could fire on a stale mark. The fix was to take the sell off the loop.

## 3. The decision pipeline

```
candidate → HardRugPreFilter → TokenSafetyChecker (SAFE/CAUTION/HARD_BLOCK)
          → lane scoring AI → FinalDecisionGate
          → PredictiveEntryOracle6915 (ADMIT / REFUSE)
          → LearnedAdmissionAuthority6846 → ExecutableEntryAuthority6450
          → sizing (realistic cap, fee-aware floor, launch-chase cap)
          → Executor → CanonicalPositionAuthority6441
```

Every stage can refuse, and every refusal is counted by reason on the in-app Pipeline Health screen.

## 4. The oracle

The core question is simple: **is the expected value of this trade, net of cost, positive?**

The difficulty is that evidence is thin where it matters. A lane × score band × regime grid has hundreds of cells and a young journal has a few hundred closes. Lowering thresholds would only make the bot confident on n = 1. The oracle uses empirical-Bayes shrinkage instead:

```
w = n / (n + 6)
E = Σ w·μ / Σ w      over { cell (lane × score bucket), lane, whole book }
```

An empty cell falls back to its lane, and an empty lane falls back to the book. Lane and book statistics come from the **full trade journal**, up to 5,000 terminal closes, whenever it holds more evidence than the session learner. A restart therefore does not wipe what the oracle knows.

Bounded votes from the learned stack are then added: the per-lane policy head, meta-policy conviction, the pattern graph, source-family expectancy and the SSI council. The total is capped at ±25 percentage points, with a further ±18 for a "brain network" tier.

The verdict is binary:

- **ADMIT** if expectancy is positive, is not strongly negative with candidate-specific evidence, and a calibrated policy head does not predict a loss;
- **REFUSE** otherwise.

There are no "probe" trades. At the fee floor the fixed network cost alone is about 1.5% of a ticket, so a quarter-size probe pays roughly four times the cost share for the same information.

Two further rules:

- The book-wide average cannot authorise a refusal on its own. Otherwise one bad run would refuse every future candidate and prevent the trades that could change the average.
- Recorded facts, such as a creator wallet with repeated rugs, refuse regardless of confidence. A fact does not become truer with a bigger sample.

## 5. The oracle has to earn its authority

A forecaster whose yes-pile and no-pile settle the same has no edge, however confident its numbers look. So the oracle starts **ADVISORY**. While it is advisory, its REFUSEs still trade. That is deliberate: it is the only way to learn what a refusal would have settled at.

Every forecast is stamped and later graded against its close. It becomes **PROVEN** only when all of these hold:

| Condition | Bar |
|---|---|
| ADMIT closes | ≥ 20 |
| REFUSE closes | ≥ 10 |
| ADMIT mean return | > 0 |
| ADMIT mean − REFUSE mean | ≥ 2 pp |
| ADMIT win rate | ≥ REFUSE win rate |
| ADMIT Brier score | ≤ 0.25 |

Once PROVEN, its verdict *is* the admission decision in paper and live: ADMIT trades and REFUSE does not. The tier is recomputed on every graded close and demotes itself when the edge fades. Since 5.0.7287 the proof persists across restarts.

## 6. Paper that pays real costs

Paper trading is only useful if it costs what live costs. Since 5.0.7287 every paper fill is charged by venue:

| Venue | Fee per side |
|---|---|
| pump.fun bonding curve | 1.25% |
| PumpSwap | 0.25% + creator fee tier (0.95% under $300k market cap, down to 0.05% above $20M) |
| AMM pools | 0.25% |
| Network (priority + tip + base) | 0.000805 SOL fixed |
| AATE app fee | 0.5% |

Price impact is modelled as `clip / (depth + clip)`, with a floor of 30 virtual SOL of depth on a bonding curve and a 15% cap. A curve round trip costs about 5–6% and a graduated pool 2–3%. The sizer uses the same fixed-cost number, so its minimum order (≈0.107 SOL, the size at which the fixed round trip is 1.5% of the ticket) matches what the ledger charges.

## 7. Exits: we don't cap wins

The profit lock **slides up with the peak**. The share of the peak gain it may give back shrinks as the peak grows:

| Peak | < +50% | +100% | +300% | +1,000% | ≥ +3,000% |
|---|---|---|---|---|---|
| Max give-back | 40% | 30% | 18% | 12% | 8% |

On a +1,408% runner, the lock now sits near +1,251%. Under the previous curve it was +478%.

Tail-hunting lanes (moonshot, sniper, express and similar) use a runner profile. Locks do not arm until a +50% peak. Take-profit is never tuned below neutral. A launch that is −20% inside two minutes is cut on the first strike, because it did not launch.

Protective exits: a −10% hard floor checked every second, a lane hard floor of −15% in the sweep path, adaptive trailing stops, a learned per-lane exit head, and a universal stop-loss sweep.

## 8. Execution

Swaps are built with Jupiter and signed on the device. Submission order:

1. Helius Sender (SWQOS), with a tip transfer added to the message;
2. Jito bundle for MEV protection, with a dynamic tip;
3. the RPC ladder, with the authenticated Helius endpoint ahead of public nodes.

For pump.fun sells that Jupiter cannot route, PumpPortal's `trade-local` builds the transaction. Bonding-curve prices are read directly from curve accounts in batched calls over a six-rung RPC ladder, and a rung in backoff is skipped without a request.

## 9. One authority per fact

Most bugs in a trading engine are accounting bugs: two stores disagree about how much cash exists. AATE's rule is one writer per economic fact.

- The **position authority** is the only position store. Every mutation runs under one lock with an idempotency key.
- The **capital authority** is the only capital view. It checks a conservation invariant on every audit tick: `start + realized − fees ≈ cash + reserved + open cost`.
- The **finalized-trade bus** publishes exactly one close per position. Every learner and grader listens to it.
- The **lane identity authority** holds the only alias table, and CI fails the build if a copy appears.

## 10. CI that catches the mistakes the team keeps making

All 16 validators are Python scans written after a real red build or a real defect. Each one names the incident in its docstring:

- unbalanced nested comments;
- unresolved fully-qualified references;
- `return` inside expression-body functions;
- `val` reassignment and illegal local-function modifiers;
- counters that report refusals on paths that do not refuse;
- newly added code that nothing calls;
- USD-over-SOL unit errors;
- layout-id contract breaks and palette drift;
- lane identity drift between authority layers;
- more than one writer per economic domain.

They run in seconds, before a 14-minute Gradle build.

## 11. Measured, labelled PAPER

**PAPER session, 5.0.7288, 24 Sep 2026, about 11.5 minutes, 79 closed trades.** Equity went from about 10 to 31.34 SOL. Realized PnL was +20.52 SOL after 0.89 SOL of fees. Profit factor 7.59; per-position win rate 52.6%. The mark loop ran 469 ticks in 687 s with 0 stale resets.

This is one short paper session with a small sample. It is not a live result, and it is not a forecast.

## 12. What's next

1. Sustained paper profitability.
2. A small live calibration run to compare real fills and fees with paper.
3. Live lanes enabled one at a time.
4. The oracle reaching PROVEN.

The stated goal is $50 → $1,000,000. It is a goal, not a result.

---

*Trading crypto is high risk. Paper results are not live results. Not financial advice.*
