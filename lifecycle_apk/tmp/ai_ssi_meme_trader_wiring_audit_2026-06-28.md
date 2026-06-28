# AI / SSI / Meme Trader Full Wiring Audit — 2026-06-28

Scope: Harvard Brain (`EducationSubLayerAI`), AI cross-talk, Mega/SuperBrain, SSI/AGI synthetic stack, LLM/sentience layers, autonomous meta-policy, full Meme Trader lane wiring, and closed-loop learning/data quality required for daily compounding.

## Closed-loop contract

A functioning system must close this loop:

1. scanner/source family admits candidate with source + route + lane context
2. UnifiedScorer + specialist lane brain produce component scores
3. Harvard Brain records the exact entry score snapshot keyed by mint/position
4. FDG/sizer consumes bounded AI/SSI/Harvard/SuperBrain signals without hard-choking non-safety volume
5. Executor opens with event-local lane/source/mode/trade identity
6. terminal close emits validated, trainable outcome only after true terminal finality
7. Education/SSI/CrossTalk/LearningPersistence fanout consumes the terminal outcome with correct lane/source/position attribution
8. next entry/sizing/exit decision reads the corrected learned state

If any step records under one key and reads another, or records only post-hoc without decision consumption, the loop is theatre.

## Confirmed findings

### F1 — Harvard route-label alias drift caused dedicated meme lanes to bypass learned mute/boost

Confirmed files:
- `EducationSubLayerAI.kt`
- `ShitCoinTraderAI.kt`
- `ShitCoinExpress.kt`

Evidence:
- `ShitCoinTraderAI` calls `EducationSubLayerAI.applyMuteBoost("SHITCOIN_TRADER", shitScore)`.
- `ShitCoinExpress` calls `EducationSubLayerAI.applyMuteBoost("SHITCOIN_EXPRESS", expressScore)`.
- Outcome learning is stored under registered layer names: `ShitCoinTraderAI`, `ShitCoinExpress`.
- Pre-4303 `applyMuteBoost` looked up `layerPerformance[layerName]` directly, without canonicalizing.

Risk:
- Harvard Brain could be initialized and recording, but the dedicated meme lane readback returned `NORMAL` forever for those route labels.
- This breaks the loop: outcome learning did not reliably shape the next ShitCoin/Express lane decision.

Patch:
- 4303 canonicalizes layer aliases (`shitcoin_trader`, `shitcoin_express`, etc.) before accuracy/maturity/mute/boost lookup.

### F2 — Meme-specific registered Harvard layers defaulted to generic lane learning

Confirmed files:
- `EducationSubLayerAI.kt`
- `LayerLaneRegistry.kt`

Evidence:
- Registered layers include `QualityTraderAI`, `ProjectSniperAI`, `ShitCoinExpress`, `UltraFastRugDetectorAI`, `SocialVelocityAI`.
- Pre-4303 `LayerLaneRegistry` did not assign these, so they defaulted to GENERIC.

Risk:
- Meme-specific heads can absorb unrelated non-meme outcomes and distort accuracy/expectancy.
- This lowers data quality and can poison mute/boost/approval memory.

Patch:
- 4303 gates Quality, ProjectSniper, ShitCoinExpress, UltraFastRugDetector to MEME; SocialVelocity to MEME_ALT.

### F3 — Rapid profit capture path was advisory instead of executable

Confirmed files:
- `BotService.kt`

Evidence:
- Pre-4301 rapid TP used `RAPID TAKE_PROFIT_DELEGATE` and called `executor.runManageOnly()`.
- Operator screenshot showed live Pride at `TARGET Peak +991% lock +982%` while still open around +10%.

Risk:
- Basic meme-trading law violated: seen peak profit must be monetized immediately.

Patch:
- 4301 adds `RAPID_INSTANT_PROFIT_CAPTURE_4301` and `RAPID_PEAK_LOCK_BREACH_4301`.

### F4 — Warmup/entry-lock could override positive profit-lock

Confirmed files:
- `FluidLearningAI.kt`
- `Executor.kt`

Evidence:
- Pre-4302 `getDynamicFluidStop()` returned first-60s entry protection before profit-lock.
- Pre-4302 Executor 40s entry-lock could hold dynamic stop even when `dynamicStopPct` was positive.

Risk:
- Instant pumps inside the first minute missed dynamic profit lockers.

Patch:
- 4302 makes profit-lock beat warmup and makes Executor entry-lock hold only negative stops (`dynamicStopPct <= 0.0`).


### F5 — AI CrossTalk credited close-time recomputation instead of entry-time signal

Confirmed files:
- `AICrossTalk.kt`
- `MemeCrossTalkEntryBridge.kt`
- `Executor.kt`

Evidence:
- Entry cross-talk bridge shaped confidence/size from `AICrossTalk.analyzeCrossTalk(...)`, but did not stamp which signal influenced the entry.
- Terminal sell fanout recomputed `AICrossTalk.analyzeCrossTalk(ts, isOpenPosition=false)` at close, then recorded outcome against that newly computed signal.

Risk:
- Cross-talk could punish/reward the wrong teacher because liquidity/regime/narrative/whale state at close is different from entry.
- This breaks signal quality and makes cross-talk self-training noisy instead of profitable.

Patch:
- 4304 stamps entry cross-talk by `mint:lane` and terminal fanout credits `recordStampedEntryOutcome(...)` using the executed lane.


### F6 — StrategyHypothesisEngine double-counted one promotion event

Confirmed file:
- `StrategyHypothesisEngine.kt`

Evidence:
- The promotion branch had duplicate promotion accounting in the audited source lineage; current patch pins one increment per true promoted variant.

Risk:
- Corrupts the self-directed experiment clock used for next mutation cadence/dimension alternation.
- Makes the autonomous learner behave as if two promotions happened, distorting the scientific A/B loop.

Patch:
- 4305 pins promotion accounting to one increment per true promotion with Golden Tape coverage.


### F7 — SocialVelocityAI performed synchronous DexScreener HTTP from scorer path

Confirmed file:
- `SocialVelocityAI.kt`

Evidence:
- `score()` calls `getBoostAmount()`.
- Pre-4306 `getBoostAmount()` called `refreshBoostedTokens()`, which executed OkHttp synchronously against DexScreener when cache expired.

Risk:
- Free DexScreener social/boost alpha is valuable, but a synchronous network fetch inside UnifiedScorer can wedge scanner/FDG flow and reduce throughput.

Patch:
- 4306 changes score-time reads to cached boost data and kicks stale refresh in a background IO coroutine. Blocking refresh remains only for explicit background/startup callers.

## Candidate risks requiring next pass

### C1 — Synthetic score components may be tracked poorly or treated as generic theatre

Potential components:
- `approval_memory`
- `source`
- `v4_crosstalk`
- `fresh_launch_bonus`

Risk:
- If these materially move scores but are not represented in the Harvard registry or attributed to a real accountable brain, outcome learning cannot correctly reward/punish them.

### C2 — AI cross-talk bridge must be audited for directional correctness

Files:
- `AICrossTalk.kt`
- `MemeCrossTalkEntryBridge.kt`
- `UnifiedScorer.kt`
- specialist lanes

Required check:
- Cross-talk must shape score/size softly, not hard-kill non-safety flow.
- It must consume current source/lane/regime and feed terminal outcomes back into the same named signals.

### C3 — SSI council is present but not yet proven as one coherent headmaster bus

Present pieces:
- `SemanticPatternGraph`
- `CounterfactualReplayEngine`
- `StrategyHypothesisEngine`
- `AsyncStrategyLab`
- `ReflectiveOptimizerGEPA`
- `MultiAgentCriticStack`
- `ResearchScout`
- `UnifiedPolicyHead`
- `UnifiedExitPolicyHead`
- `SentienceHooks` / `SentienceOrchestrator`

Required check:
- insight → symbolic review → bounded bias → attribution ledger → terminal outcome credit.
- No hot-path provider/API calls.
- No unbounded synchronized scans on FDG/executor path.

### C4 — Sell authority / balance-proof deferrals under profit pressure

Observed labels:
- `PROFIT_LOCK_DEFERRED`
- `CAPITAL_RECOVERY_DEFERRED`
- `PARTIAL_SELL_WAITING_BALANCE_PROOF`
- `SELL_WAITING_BALANCE_PROOF`

Required check:
- profit exits must enqueue urgent recovery, not passively wait.
- cached owner-delta/proof must be used only when safe, but sell priority must beat buy/scanner work.

## Golden Tape coverage added

- `rapidProfitCapture4301DoesNotDelegateLiveRunnersAndMissPeaks`
- `profitLock4302BeatsWarmupAndEntryLock`
- `harvardHeadmaster4303CanonicalAliasesCloseMemeLoop`

## Next audit order

1. CrossTalk/AICrossTalk correctness: entry scoring, lane mapping, terminal feedback.
2. SSI council closed loop: semantic/counterfactual/hypothesis/critic/GEPA influence and attribution.
3. Synthetic component accountability: source, v4_crosstalk, approval_memory, fresh_launch_bonus.
4. Profit-pressure sell authority: balance proof/wallet null/urgent queue behavior.
5. Full nine-lane meme trader parity: SHITCOIN, EXPRESS, MOONSHOT, QUALITY, BLUECHIP, MANIPULATED, DIP_HUNTER, PROJECT_SNIPER, TREASURY/CASHGEN.


## 2026-06-28 C1-C5 implementation closeout

- C1 synthetic component accountability: implemented in V5.0.4378 via `SyntheticComponentAccountability`; UnifiedScorer entry-score paths now annotate synthetic score movers with lane/source/mint/build context before Harvard entry recording. V5.0.4389 fixes Golden Tape interpolation assertions for this contract.
- C2 cross-talk directional correctness: implemented in V5.0.4380; AICrossTalk entry stamps now carry event-local source/mode/positionId/build and terminal feedback credits the stamped entry signal rather than close-time recomputation.
- C3 SSI council coherent bus proof: implemented in V5.0.4381 via `SsiCouncilClosedLoopSentinel`; report-only chain documents SemanticPatternGraph + CounterfactualReplay + GEPA/Critic + AsyncStrategyLab reviewed bias + UnifiedPolicy/Exit outcome loops.
- C4 profit-pressure sell authority: implemented in V5.0.4384; wallet-null live capital recovery/profit lock now enqueues urgent PendingSellQueue recovery, marks BalanceProofWaitState, and emits `PROFIT_PRESSURE_SELL_RECOVERY_ENQUEUED_4384` without fake-closing or paper-booking live exits.
- C5 nine-lane meme parity: implemented in V5.0.4388 via `MemeLaneParitySentinel`; pins active-map restore coverage and lane-local recovery-probe doctrine across SHITCOIN, EXPRESS, MOONSHOT, QUALITY, BLUECHIP, MANIPULATED, DIP_HUNTER, PROJECT_SNIPER, and TREASURY/CASHGEN.

Next unresolved audit surface should move beyond C1-C5 into the broader mechanism/file queue: dormant low-ref helpers, perps sidecars, UI/ANR decoupling proof, and any KPI-visible report-only status surfaces that remain hidden from OperatorKpiCloseoutReport.
