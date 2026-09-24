# AATE Technical Description

**AATE — Autonomous Algorithmic Trading Engine** · 5.0.7288 · for technical due diligence

AATE is an autonomous trading engine that runs entirely inside a native Android app. It decides, sizes, executes, exits and learns on the device. It was built by one developer in six months, from a phone, with no team and no big budget. GitHub Actions compiles and gates every build.

This document states what the code does and what enforces it. Where a number is measured it says so. Where a result comes from paper trading it is labelled **PAPER**. Source paths are relative to `app/src/main/kotlin/com/lifecyclebot/` unless stated otherwise. `ARCHITECTURE.md` has the system diagrams.

> Trading crypto is high risk. Paper results are not live results. Nothing here is financial advice, and no return is promised.

---

## 1. Design doctrine

Three rules explain most of the code:

1. **Real data and forensic accounting. No imagined gains. No inferred values.** A missing mark is held at cost basis and labelled stale; it is never extrapolated. Paper fills are charged venue costs (§5). Learners train on authoritative marks only (`CanonicalCapitalAuthority6450.Snapshot.authoritativeOpenMarketValueSol`).
2. **One authority per fact.** Every economic quantity has exactly one writer. Other stores are read-only mirrors. CI scans enforce this (§9).
3. **Everything is counted.** Every refusal, fallback, relaunch and stale mark increments a labelled counter in `engine/PipelineHealthCollector.kt`, and the Pipeline Health screen shows it. Diagnoses are made from counters, not from inference.

Each fix in the code carries a header that quotes the device snapshot that motivated it. The source doubles as an engineering log.

## 2. Authorities and their invariants

| Authority | File | Invariant it holds |
|---|---|---|
| Position authority | `engine/truth/CanonicalPositionAuthority6441.kt` | One position store for paper and live, executor, exits, journal, learner, reconciler and UI. Every mutation (buy, partial sell, full sell, quarantine) runs under one `ReentrantLock` and needs an idempotency key, so a replayed callback cannot mutate twice. Paper cash can never go negative. Quantities are raw `BigInteger` with explicit decimals. |
| Capital authority | `engine/truth/CanonicalCapitalAuthority6450.kt` | The only read view of capital. It computes cash, reserved, open cost basis, open market value, unrealized, realized, fees and total equity, and checks conservation on every audit tick: `startingCapital + realized − fees ≈ cash + reserved + openCostBasis`. Stale or fallback marks are counted separately, and an "authoritative equity" excludes them. The UI must not show cash as equity. |
| Finalized-trade bus | `engine/truth/CanonicalTradeFinalizedBus6450.kt` | Exactly one close event per position (WIN, LOSS or BREAKEVEN, with return fraction). Streak counters, learners and the oracle grader all subscribe to it. Closes that settled while the app was down are replayed at startup (`engine/truth/CanonicalFinalityPersistence6486.kt`). |
| Executable entry authority | `engine/truth/ExecutableEntryAuthority6450.kt` | The single gate immediately before capital reservation, for every executable route. No pid, source or lane alias bypasses it; attempts are counted by `recordBypass`. |
| Learned admission | `engine/truth/LearnedAdmissionAuthority6846.kt` | A pure function of its `Inputs`, which `engine/truth/LearnedAdmissionInputs6909.kt` assembles. A DENY must not fall back to a duplicate ALLOW path. |
| Lane identity | `engine/truth/CanonicalLaneIdentity6506.kt` | One alias table, applied on every read and write. Lane strings are compared for equality to decide who owns sealed authority, so two normalisers that disagree would void it. `ci/lane_identity_authority_scan.py` fails the build if a copy appears. |
| Enabled traders | `engine/EnabledTraderAuthority.kt` | The atomic set of enabled traders among 16 (`MEME, SHITCOIN, MOONSHOT, EXPRESS, QUALITY, TREASURY, CASHGEN, BLUECHIP, MANIPULATED, DIP_HUNTER, PROJECT_SNIPER, CYCLIC, CRYPTO_ALT, MARKETS_STOCKS, PERPS, SHADOW_PAPER`). Every scanner, engine and journal writer checks it on each tick. |

### 2.1 ExecutableEntryAuthority in detail

It has two entry points:

- `gate(inputs)` evaluates `LearnedAdmissionAuthority6846`. DENY returns `DENY_LEARNED_NEGATIVE_6846` with size 0. Otherwise it falls through to the streak gate below. If the learned authority throws, the gate fails open and counts `EXECUTABLE_ENTRY_ORACLE_ERROR_FAIL_OPEN_7263`.
- `gate(lane, mint, size)` applies **size shaping, not refusal**. Streaks are keyed per `mode|lane` (a paper SHITCOIN loss never suppresses a live BLUECHIP entry). When live has less history, it inherits the paper streak. One loss gives ×0.65 and two or more give ×0.35. A 60 s cooldown after a loss also gives ×0.35. Where the learned `LaneExpectancyDamper` has an opinion, it replaces the raw streak prior. The enum still declares `DENY_LOSING_STREAK`, `DENY_COOLDOWN` and `DENY_DAILY_LOSS_CAP`, but no code path returns them in 5.0.7288. Hard halts live in `engine/LiveSafetyCircuitBreaker.kt`: refuse live trading below 0.10 SOL, and halt on a 10% session drawdown.

## 3. The Predictive Entry Oracle

File: `engine/truth/PredictiveEntryOracle6915.kt`. It answers one question per candidate: **is expected value, net of cost, positive?** Since 5.0.7287 the verdict is binary, `ADMIT` or `REFUSE`. The old quarter-size "probe" was removed because at the fee floor the fixed network cost alone is about 1.5% of a ticket, so a quarter-size probe pays roughly four times the cost share for the same information.

### 3.1 Hierarchical shrinkage

Evidence is sparse per cell (lane × score band × regime is hundreds of cells) and dense at the lane and book level. So the oracle uses empirical-Bayes shrinkage over three levels:

| Level | Key | Source |
|---|---|---|
| CELL | lane × score bucket | `ScoreExpectancyTracker` raw bucket mean |
| LANE | lane | `LiveProbabilityEngine` lane snapshot, **or** the full journal when it holds more closes (`OracleTradeHistory7287`) |
| GLOBAL | whole book | the same, at book level |

```
wᵢ = nᵢ / (nᵢ + K),  K = 6
E_blend    = Σ wᵢ·μᵢ / Σ wᵢ                      (expectancy, % per trade)
pWin_blend = Σ wᵢ·pᵢ / Σ wᵢ                      (levels with a win rate)
confidence = 0.5·max(wᵢ) + 0.5·mean(wᵢ)          (clamped 0..1)
```

`engine/truth/OracleTradeHistory7287.kt` reduces the terminal journal to per-lane and whole-book statistics: expectancy net of fees per position (`netPnlSol / entryCostSol`), and win rate. It reads at most 5,000 clean terminal SELL rows from `TradeHistoryStore` (paper and live) and recomputes at most once a minute. Rows booked before 7287 carry the older, harsher paper fee model, so early history reads pessimistic.

### 3.2 Bounded stack adjustments

The oracle does not add a new model. It reads existing learners, and each contributes a bounded adjustment in percentage points:

| Contributor | Adjustment |
|---|---|
| `AutonomousMetaPolicy.conviction` | `(conv − 1) × 12` |
| `UnifiedPolicyHead.predictWinProb` | `(p − 0.5) × 20`, clamped ±10 |
| `SemanticPatternGraph.entryBias` | `(sizeMult − 1) × 10` |
| `SsiPilotCouncil.sizeMultiplierForLane` | `(m − 1) × 10` |
| `SourceFamilyOpportunityScorecard` (n ≥ 3) | `meanPnl% / 100 × 8`, clamped ±10 |

The sum is clamped to ±25 pp. A separate "brain network" tier of previously unread modules is clamped to ±18 pp. `E_final = E_blend + adj + brain`. Every contribution is listed in the verdict line for the operator.

### 3.3 Verdict

```
predictive pWin = 0.20·pWin_blend + 0.40·candidateConfidence + 0.40·policyHead_p
refuseConfidence = confidence computed over CELL and LANE only (GLOBAL excluded)
evidencedNegative = E_final ≤ −8%  AND  refuseConfidence ≥ 0.45
policySupports    = policy head not binding  OR  policyHead_p > 0.5
ADMIT  ⇔  ¬evidencedNegative  AND  E_final > 0  AND  policySupports
REFUSE otherwise
```

- The book-wide average can move the estimate, but it **cannot by itself authorise a refusal** (7174). Otherwise one bad run would refuse every future candidate in every lane.
- **Recorded-fact safety refusal.** A serial-rugger creator (a recorded count in the rug ledger) or a tier-B risk read gives `REFUSE` with `hardSafety7287 = true`. This bypasses the confidence gate and is honoured in every tier.
- **Cold start.** When no level has evidence, the current candidate is judged on its own. It is admitted only if its probability is above 0.5, quality and phase are not weak, the policy head agrees, and the brain delta is at least −5.
- **Degeneracy.** `LearnedPolicyDegeneracyWatch7102` watches the raw verdict stream. An estimator whose output has collapsed to one answer is flagged non-binding until the raw stream discriminates again.
- **Runner-friendly by construction.** Expectancy is mean PnL, so a fat-tailed lane with a low win rate and a positive mean scores *higher*.

### 3.4 Edge proof: the oracle earns its authority

File: `engine/truth/OracleEdgeProof7263.kt`. Every forecast is stamped per mint with its verdict, pWin and time. When the finalized bus publishes a close for that mint within 6 h of the stamp, the forecast is scored into the ADMIT or REFUSE pile (count, wins, sum of returns, sum of squared errors). While ADVISORY, REFUSEs still trade; that is how the REFUSE pile fills with real outcomes.

**PROVEN** requires all of the following at once:

| Condition | Threshold |
|---|---|
| ADMIT closes | ≥ 20 |
| REFUSE closes | ≥ 10 |
| ADMIT mean return | > 0 (net of cost) |
| ADMIT mean − REFUSE mean | ≥ 2 percentage points |
| ADMIT win rate | ≥ REFUSE win rate |
| ADMIT Brier score, `mean((pWin − 1{win})²)` | ≤ 0.25 |

When the tier is PROVEN and the estimator is not degenerate, `LearnedAdmissionAuthority6846` makes the oracle's verdict the admission decision in paper and live alike: ADMIT trades at the requested size (`ORACLE_PROVEN_ADMIT_7287`) and REFUSE does not trade (`ORACLE_PROVEN_REFUSE_7287`). The tier is recomputed on every scored close and demotes itself as soon as any condition fails. Tallies and live stamps persist in SharedPreferences (`aate_oracle_edge_proof_7287`), so the proof accumulates across restarts (7287).

### 3.5 Learned admission while ADVISORY

Order inside `LearnedAdmissionAuthority6846.evaluate`:

1. oracle hard-safety REFUSE → DENY;
2. policy head HARD_BLOCK → DENY;
3. proven, non-degenerate oracle → its verdict;
4. otherwise the evidence rules. Cohorts need at least 8 terminal closes before they can deny (`MATURITY_MIN_N = 8`). A DUMP regime with a mature negative cohort is denied. Proven-dead cohorts, source-family suspicion and capital-target overshoot come after that.

"No evidence yet" is never a refusal.

## 4. Sizing

1. `engine/SmartSizer.kt` produces the base size. Lane, learner and streak multipliers shape it.
2. `Executor.realisticEntrySize6867` applies the same policy in both modes: wallet-percent floor and cap, liquidity-impact cap, spendable and reserve limits. Since 7280 the binding per-mint cap is stamped and also bounds the paper ticket.
3. `engine/truth/FeeAwareSizeFloor7277.kt`: `floor = ceil_lamports(0.00161 SOL / 0.015)` ≈ **0.107 SOL**. Rounding up to a whole lamport (7281) fixed a 3×10⁻¹² comparison that had been rolling back every ticket promoted to the floor.
4. `engine/truth/LaunchChase7280.kt`: `entryMultiple ≥ 3.0` over the create price within 180 s of the create sets the size to the floor.
5. Cost-edge gate: forecast edge must exceed modeled cost × 1.15 (`COST_EDGE_MARGIN_7162`).

## 5. Paper fee model (`engine/truth/PaperVenueCost7287.kt`)

The venue is classified per mint. A pump mint with reported liquidity under $15k is `BONDING_CURVE`; a graduated pump mint is `PUMPSWAP`; anything else is `AMM`.

| Venue | Venue fee per side |
|---|---|
| pump.fun bonding curve | 1.25% |
| PumpSwap | 0.25% + creator fee by market cap: 0.95% (< $300k), 0.50% (< $1M), 0.20% (< $20M), 0.05% (≥ $20M) |
| AMM (Raydium / Meteora etc.) | 0.25% |

```
fixed per side   = 0.000805 SOL                         (priority + tip + base)
depthSol         = (liquidityUsd / 2) / solUsd           (SOL side of the pool)
depthSol(curve)  = max(depthSol, 30 virtual SOL)
impact%          = clip / (depthSol + clip) × 100,  capped at 15%   (2% if depth unknown)
app fee          = 0.5% per side (Executor.MEME_TRADING_FEE_PERCENT; 1% for leverage)
```

A curve round trip costs about 5–6% and a graduated pool 2–3%. The fixed leg matches `FeeAwareSizeFloor7277`, so the sizer and the ledger agree. The model before 7287 double-charged fee and slippage (about 12–20% per round trip), so paper results from earlier builds are not comparable.

## 6. Exit mechanics

- **Sliding profit lock** (`engine/PeakDrawdownLock.kt`, 7282). Allowed give-back as a fraction of the peak gain, interpolated linearly:

  | Peak | < +50% | +100% | +300% | +1,000% | ≥ +3,000% |
  |---|---|---|---|---|---|
  | give-back | 0.40 | 0.30 | 0.18 | 0.12 | 0.08 (floor) |

  Example: a +1,408% peak now locks at about +1,251% instead of +478%. A lane-learned multiplier (`FluidLearningAI.exitBandMultiplier7267`) may narrow the band but never above 0.40. The same curve drives the 1 Hz tick lock and the give-back stop.
- **Runner profiles** (`engine/RunnerExitProfile7277.kt`). On runner lanes, give-back locks arm only after a +50% peak. Take-profit is never tuned below neutral (`TP_MULT_FLOOR = 1.0`). A position at −20% within 120 s is cut on the first strike.
- **Trailing stops** (`engine/TrailingStopManager.kt`) widen with profit and volatility and tighten with age.
- **Learned exits.** `engine/UnifiedExitPolicyHead.kt` is a per-lane exit-now-or-hold head over six features (pnl, peak pnl, normalised age, momentum, liquidity erosion and others), with Brier-calibrated authority tiers. `engine/learning/LaneExitTuner.kt` tunes each lane's take-profit and stop-loss ladder from realised outcomes.
- **Floors.** The tick hard floor is −10% (`BotService.TICK_HARD_FLOOR_PCT`). The lane hard floor is −15% (−9% for fresh memes) in `engine/Executor.kt`. The universal SL sweep is a backstop across all lanes.
- **LLM exit advice** (live only, gain ≥ 15%). An `IMMEDIATE` verdict at confidence ≥ 70 exits. `SOON` at confidence ≥ 80 with gain ≥ 30% exits. Symbolic-patience logic can veto either.

## 7. Execution path

```
Executor ── Jupiter quote/swap (network/JupiterApi.kt)
        └─ sign on device (ed25519, network/SolanaWallet.kt)
           ├─ 1. Helius Sender  (network/HeliusSender.kt; tip transfer added by HeliusSenderEnvelope7250)
           ├─ 2. Jito bundle    (JitoMEVProtection, tip from network/JitoTipFetcher.kt) — if enabled
           └─ 3. RPC ladder     (Helius first, then public RPCs; RuntimeProviderAuthority6685)
pump.fun sell fallback ── PumpPortal trade-local (network/PumpFunDirectApi.kt), after Jupiter escalation fails
```

After a live sell, the balance-proof path confirms the result from the wallet before finality (`engine/sell/BalanceProof.kt`, `engine/sell/TxMetaSellFinalizer.kt`). A signed transaction that Helius Sender misses rotates to Jito or RPC, never back to a fresh Jupiter build.

## 8. Resilience

| Mechanism | Where | Behaviour |
|---|---|---|
| RPC ladder | `engine/RuntimeProviderAuthority6685.kt` | Authenticated Helius first, then the saved RPC and public RPCs, de-duplicated |
| Per-rung backoff | `network/ParallelMarkFanout7088.kt` | Six-rung curve-read ladder; a rung in backoff is skipped without a request (7281) |
| Parallel marks | `network/ParallelMarkFanout7088.kt` | All feeds queried at once; agreement wins |
| Host circuits | `network/HostCircuitInterceptor.kt` | Provider-wide circuit and quota hard-stop on the shared HTTP client |
| Supervised mark loop | `BotService.superviseOpenPositionTickLoop7283` | Stall (≥ 30 s in flight, matched by sequence since 7288) or dead job: record phase and frames, cancel, relaunch as a new generation (at most once per 60 s) |
| Off-loop sells | `BotService.requestSellOffLoop7288` | Tick sells run on the IO pool, one per mint, retryable after 60 s |
| Single-flight sweeps | exit coordinator | Pending flags coalesce requests; durations are measured in `ExitSweepTiming7264` |
| ANR watchdog | `engine/PipelineHealthCollector.kt` | Pings the main thread every 250 ms and samples its stack while it is still blocked |
| LLM isolation | `AsyncGeminiNarrativeCache6478`, `AsyncGeminiExitAdviceCache6479` | Background fetch with a cache; the hot path never waits on a provider |

## 9. CI validator suite

`.github/workflows/build.yml` runs 16 Python validators from `ci/` before Gradle. Each one is a hard failure.

| # | Validator | Enforces |
|---|---|---|
| 1 | `ci/comment_balance.py` | Nested Kotlin block comments are balanced (a `/*` inside KDoc opens a new level) |
| 2 | `ci/golden_tape_literal_scan.py` | Assertions in the golden-tape test use literals that compile and search the intended string |
| 3 | `ci/authority_contradiction_scan.py` | An entry lane keeps one identity from creation → FDG → authorizer → executor → position/journal, including failure paths |
| 4 | `ci/patch_rot_scan.py` | One canonical writer per economic mutation domain; retired patches stay at zero references |
| 5 | `ci/economic_units_scan.py` | Unit discipline (for example no USD divided by SOL, no omitted divisors in proceeds) |
| 6 | `ci/res_validate.py` | Android resources: well-formed XML, no duplicate names, valid gradients (fails in seconds rather than after a 14-minute Gradle run) |
| 7 | `ci/layout_contract.py` | Every `R.id` a Kotlin screen binds exists in its inflated layout |
| 8 | `ci/palette_drift.py` | One palette: code constants may not drift from `app/src/main/res/values/colors.xml` |
| 9 | `ci/static_call_check.py` | `Name.method()` only where `Name` is an `object`, not a `class` (diff-scoped) |
| 10 | `ci/return_telemetry_check.py` | A "this returned or refused" counter is emitted only on a path that actually returns (diff-scoped) |
| 11 | `ci/new_dead_code.py` | New declarations must have a caller (diff-scoped) |
| 12 | `ci/qualified_reference_check.py` | Every fully-qualified `com.lifecyclebot.*` reference resolves |
| 13 | `ci/kotlin_expression_body_return.py` | No `return` inside an expression-body function |
| 14 | `ci/lane_identity_authority_scan.py` | No second copy of the lane alias table |
| 15 | `ci/kotlin_local_function_modifiers.py` | No visibility modifiers on local functions |
| 16 | `ci/kotlin_val_assignment.py` | No reassignment of `val` properties |

After the gates come `./gradlew assembleRelease` (R8 minification on) and the unit suite. `.github/workflows/runtime-test.yml` boots an emulator and runs `ci/runtime-test.sh` from the repository root as a runtime smoke test.

## 10. Testing

- **2,699 `@Test` cases** in 339 files under `app/src/test/` (≈47,000 lines), all pure JVM.
- **Golden-tape regression tests.** `app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt` holds 649 tests that pin behavioural contracts. Examples: an unknown or pending safety state reduces size but never blacklists; a live buy waits for authoritative balance proof; `V5_0_7259_oracle_admit_is_required_for_every_canonical_entry`; `V5_0_7263_oracle_is_advisory_until_edge_is_proven_on_closes`. `app/src/test/kotlin/com/lifecyclebot/engine/Directive6344Through6348GoldenTapeTest.kt` sits beside it.
- **Honest caveat.** In CI the unit suite runs with `continue-on-error: true`. Some legacy source-layout tests still assert pre-refactor file placement, so test results are uploaded and reviewed but do not block the APK. The 16 static validators are the hard gates.

## 11. Security

- **Keys** are stored in `EncryptedSharedPreferences` (AES-256-GCM values, AES-256-SIV keys, `MasterKey` in the Android Keystore): `data/BotConfig.kt`, `engine/TreasuryManager.kt`, `engine/MultiChainWalletVault6546.kt` (ETH, BSC and BTC recovery vault). Transactions are signed on the device (`network/SolanaWallet.kt`). The collective database schema (`collective/CollectiveSchema.kt`) has no key-material tables.
- **Biometric lock**: `ui/SecurityActivity.kt` (BIOMETRIC_STRONG).
- **App hardening**: `android:allowBackup="false"`, a network security config, and R8 minification with ProGuard rules on release.
- **Live guards**: `LiveSafetyCircuitBreaker` (0.10 SOL minimum, 10% session drawdown halt), live preflight (`engine/truth/LivePreflight7222.kt`), freeze-authority hard block, and stocks and forex quarantined in live.

## 12. Measured evidence (PAPER)

**PAPER run, 5.0.7288, 24 Sep 2026, about 11.5 minutes, 79 closed trades.** Equity went from about 10 to 31.34 SOL. Realized PnL was +20.52 SOL after 0.89 SOL of fees. Profit factor 7.59; per-position win rate 52.6%. The crypto spot lane made +7.85 SOL and the Project Sniper lane +4.86 SOL. The mark loop ran 469 ticks in 687 s, with 29 of 30 positions fresh and 0 stale resets. Groq LLM calls succeeded 100 of 100 times.

This is one short paper session with a small sample. Paper is not live, and it is not evidence of live returns.

## 13. Honest roadmap

1. Sustained paper profitability.
2. A small live calibration run to verify real fills and fees against paper.
3. Expand live lanes one at a time.
4. Oracle reaches PROVEN and guides admission.
5. iOS and web monitoring later.

The stated goal is $50 → $1,000,000. That is a goal, not a result.
