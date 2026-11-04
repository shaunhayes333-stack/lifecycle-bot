# AATE 5.0.8530 — source audit continuation: V3 polarity, exact strategy learning and crypto/markets contracts

**Diagnosis-only supplementary repair register, 5 November 2026 (Brisbane).**

**Read with** `AATE_8530_Lane_Setup_Strategy_Audit.md` (the original 31-item P0–P2 register, 12 lanes, 89 declared Solana setups). The items below are **new** and not a replacement for the first report.

**Repository:** `shaunhayes333-stack/lifecycle-bot`, `main` `86a06e970ef2eaa2bff167bad92e8737ffb2e3de`, build 5.0.8530. **Runtime comparison:** `aate_pipeline_20261105_020555_878.txt` from 5.0.8529, 346-second window, so the source and runtime are adjacent but *not identical commits*. The crypto, forex, metals, commodities and leveraged desks had insufficient/no observed executing traffic in that window. Every exact line below refers to the pinned main source. **No source changes, builds or pushes were performed.**

Prefix each source path below with `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. `[C]` = exact code contradiction provable; `[H]` = strategy/economic hypothesis needing forward replay; `[E]` = live consequence observed, but cannot causally attribute without per-decision receipts.

## New P0 defects — directly broken signal/learning contracts

### C01 [C] UNIFIED V3 soft caps can erase all negative risk votes

**Source:** `v3/scoring/UnifiedScorer.kt:1087-1111` (UNIFIED inner and outer caps), `:144-158` (CLASSIC analogue), `v3/scoring/ScoreCard.kt:22-32`.

Outer-ring `outerTotalNeg` is intended to cap combined negative contribution at **-10**. It multiplies **each** negative component by `-10.0/outerTotalNeg` and calls `.toInt()` before summing. Mathematical counterexample: 14 different outer risk votes of -3 each sum to -42. The scale is `10/42`; each `(-3 * 10/42).toInt()` is **0**, so the final aggregate negative **0**, not -10. Multiple small adverse signals can disappear altogether. The classic/inner caps have the same integer-rounding defect; this is not a subjective prediction.

**Required repair:** preserve signed doubles until final aggregate accounting, or apportion the exact integer budget using a deterministic remainder scheme; ensure residual negative evidence remains negative. Do not make hard rug/sellability controls soft. **Test:** 14 x -3 outer => final -10; `[-12,-6,-3,-3]` => total -10, no sign flips, order independent, and zero/positive votes unaffected. Compare live-score snapshots before/after with same fixed feature frame.

### C02 [C] MetaCognition clamps an *aggregate 44-layer total* to an incompatible ±100 scale

**Source:** `v3/scoring/UnifiedScorer.kt:1156-1173` (same pattern `:298-315` CLASSIC, `:645-665` MODERN); `v3/scoring/MetaCognitionAI.kt:1026-1060`; `v3/scoring/ScoreCard.kt:22-25`.

Once `MetaCognitionAI` has counted at least **20** outcomes (`MetaCognitionAI.kt:303,1028`), the scorer supplies the **sum** of all base components (`baseTotal`) to `adjustScore`; the latter returns `(adjusted+symNudge).toInt().coerceIn(-100,100)`. The scorer then adds a `metacognition` component equal to **cappedTotal - baseTotal**. Hence with 44 layers and base=180, even neutral model adjustment turns into **-80** solely due to the arbitrary output clamp. Base=250 similarly becomes **-150**. Scores 180 and 250 both collapse to 100 before downstream modifiers. This is **scale compression**, not evidence a bearish layer should become bullish. It directly destroys rank separation between high-scoring candidates, and can cause a large *negative-looking* MetaCog component despite positive individual votes.

**Required repair:** keep V3's raw vote aggregate, calibrated adjustment and final display-normalized score as **separate, explicitly typed scales**; bound the *delta* (in matching units) before adding it, not the entire raw sum, or normalize the raw aggregate with a documented invertible mapping before trust adjustment. Apply consistently in CLASSIC, MODERN, UNIFIED. **Test:** base=80, 120, 180, 250 with neutral forecasts must remain monotonic (or saturate only in explicitly documented display output); changing one positive observed component must not manufacture a negative MetaCog prediction. Record raw/adjusted/normalized totals.

### C03 [C] Entry MetaCog forecasts and dedup are keyed by mint, not the executed position/decision

**Source:** `v3/scoring/MetaCognitionAI.kt:307-318,371-387,446-458,1254-1265,1285-1294`; `v3/scoring/UnifiedScorer.kt:1156-1159`; `v3/scoring/EducationSubLayerAI.kt:969-998`.

Every V3 candidate score invokes `recordFromScoreCard`, which replaces `pendingPredictions[mint]` whenever no canonical position is already open. At trade close `recordTradeOutcome(mint,...)` consumes that mint key once. A repeated score **before** an entry may replace the actual signal's forecast; paper and live scopes, parallel candidates, re-entries and partials on one mint cannot be represented independently. `recentlyCountedMints` also deduplicates settled trade exposure by **mint**. Two distinct position IDs on the same mint can therefore have their learned votes/settlement counts incorrectly merged, missed or attributed to the wrong pre-entry timestamp. Existing `EducationSubLayerAI.restoreSealedScores8491` explicitly seals by position/mode; MetaCog's parallel map has not adopted the same key.

**Required repair:** immutable forecast record indexed by `(positionId or decisionId, mint, lane, setupId, accountMode, candidateVersion)`; freeze at admitted entry, not latest scan. Close each exact position/partial according to canonical settlement semantics; deduplicate by canonical trade or fill ID, never mint alone. Preserve refused/shadow forecast cohorts separately. **Test:** same mint with paper+live concurrent, two re-entries, two lane candidates, pre-buy rescore, interleaved closes and restart; each outcome updates only its own frozen votes once.

### C04 [C] Shared V3 treats one buy-pressure feature as two apparently independent intelligence votes

**Source:** `v3/scoring/ScoringModules.kt:17-30` (`EntryAI` buy pressure), `:44-79` (`MomentumAI` expressly substitutes `candidate.buyPressurePct` for missing momentum score), `v3/scoring/UnifiedScorer.kt:936-957`, `v3/scoring/MetaCognitionAI.kt:352-357,475-479`.

Buy pressure at 75% contributes **+8** as `entry` and **+8** as `momentum` even if no price momentum was observed; downstream MetaCog sees **two layer predictions** as if they were independent signals. For missing price momentum, the score may literally mean two votes for the same trade-count input. `momentumKnown` prevents some unknown cases, but the normal, observed-pressure-only path remains double-counted. This is false confluence, particularly important where scorers and Cortex are supposed to learn distinct timing evidence.

**Required repair:** make directional `momentum` use observed, timestamped price/flow **acceleration** and chart velocity; buy pressure stays with order-flow/buyer balance and may be contextual, not an independent duplicate. Preserve source lineage and compute cross-layer correlation. **Test:** pressure=75% with flat/unknown prices must not produce two independent bullish confirmations; genuine accelerating price+buyers may justify both only with separate observed sources.

### C05 [C/H] Lane playbook converts all expected-net returns above roughly +5% to essentially the same score

**Source:** `engine/cortex/LanePlaybook7907.kt:1313-1332`; test `app/src/test/kotlin/com/lifecyclebot/engine/Aate7936EntryBarsTest.kt:37-41` (test path relative to `lifecycle_apk/`, intentionally asserts saturation at +8%).

`scoreOf7937(expectedPct,extraSetups) = clamp(50 + 10*expectedPct + 3*extraSetups,0,100)`. A +5%, +15% and +100% expected-return setup each maps to **100** without extra confluence; negative returns <= -5% collapse to 0. The classifier still tracks raw expected net separately, but whenever this summarized playbook score ranks candidates, economically distinct opportunities tie and near-cap ranking can be decided by irrelevant order/tie-breaks. The saturation itself is provable; whether it changes executed order needs consumer-level fixed replay.

**Required repair:** retain expected net %, uncertainty interval and 500% runner-tail probability on their native scale for strategy arbitration. Normalize only to a bounded UI/status score using a shape that preserves useful range. **Test:** +2/+5/+15/+50% distinct candidates remain distinguishable by conservative executable net after cost while measured risk and tail are preserved. Do not simply multiply every return by fewer points without out-of-sample calibration.

## New P0/P1 defects — crypto resident strategies and outcome feedback

### C06 [C] Crypto resident state machine permits WATCHING/QUALIFIED to downgrade READY

**Source:** `perps/CryptoStrategyCandidateBooks7803.kt:55-89,96-115`; callers `perps/CryptoAltTrader.kt:1542-1575,1966-1984`; `engine/market/ResidentHunterWorker7807.kt:188-205`.

Every `put()` indiscriminately sets `state=state`, while a resident-hunter tick can call `watch` on an asset already READY and another scan calls `qualify` before `ready` again. This permits a **lower-confidence lifecycle update** to erase a previously valid READY for the same candidate version before downstream `bestReady` reads it. Whether the observed crypto desk loses a specific candidate this way depends on concurrent timing; the state-transition defect is certain.

**Required repair:** version-bound monotonic readiness or explicit invalidation with evidence; state transitions validated atomically. A new observation must not downgrade an existing READY unless the exact setup expires, becomes unsafe, or the signal revision changes. **Test:** WATCH after READY, QUALIFY after READY, old-version WATCH after new READY, concurrent hunter/desk events; one correct terminal reason and no accidental downgrade.

### C07 [C] Crypto candidate version is a 30-second wall-clock bucket, not a decision revision

**Source:** `perps/CryptoAltTrader.kt:1540,1964-1984,2875-2876`; `perps/CryptoStrategyCandidateBooks7803.kt:31-43,69-85,111-125`.

`candidateVersion = currentTimeMillis()/30000`. A READY entry can have a nominal **45-second** TTL yet become invisible to `bestReady(asset, candidateVersion)` as soon as the next arbitrary 30-second boundary passes; an event stamped a fraction of a second before that boundary is effectively expired immediately if the new cycle supplies a different bucket. Conversely multiple different substantive signals inside one 30-second bucket share a version and can overwrite each other's scores/setup state.

**Required repair:** version by actual immutable source/feature/strategy revision; TTL by observed setup trigger time/quote freshness, not clock bucket. **Test:** candidate fires at `:29.999`, is read at `:30.001` and retains valid state if inputs are unchanged; changed signal version invalidates exactly the old state.

### C08 [C] Crypto losing-pattern scratch outcomes distort the mean-loss denominator

**Source:** `perps/crypto/brain/CryptoLosingPatternMemory.kt:22-33,46-52`; `perps/crypto/brain/CryptoBrain.kt:117-149`.

For return between -1% and +1%, `record` increments **neither wins nor losses** but **does add pnlPct to pnlSum**. `Bucket.meanPnl = pnlSum/(wins+losses)`, so scratch outcomes contribute numerator without denominator. For example one -4% settled loss, then 10 scratches at -0.9% yield `n=1, mean=-13%` instead of -1.18% over 11 closes, corrupting size/shadow recommendations. Same problem works in the opposite direction with positive scratches.

**Required repair:** track `settledN`, `trainableN`, `scratchN`, `pnlSumAll`, `pnlSumTrainable` separately; use a consistently defined denominator in each danger/EV rule, with exact fee-adjusted basis. **Test:** win, loss, zero and ±0.9% scratches; mean equals arithmetic average for the declared cohort; counters and risk tier agree across restart.

### C09 [C] Crypto tactic rotation learns losses by score tier, not by the executed tactic

**Source:** `perps/crypto/brain/CryptoTacticSwitcher.kt:19-64`; `perps/CryptoAltTrader.kt:1277-1315,5164-5179`; `perps/crypto/brain/CryptoBrain.kt:108-134`.

The chosen tactic genuinely changes the entry shape, but the close hook calls `CryptoBrain.onTradeClose(tier,score,pnlPct,...)` **without a tactic or setup ID**, then `CryptoTacticSwitcher.onOutcome(tier,score,win)` updates whatever tactic the cell currently holds. If overlapping positions enter under different tactics, the outcome of the earlier one can rotate or reset the later tactic. This is a direct **strategy-attribution** gap. Likewise `LAB_PROPOSED` currently accepts the OR of hand-coded pullback/breakout/mean-revert predicates (`CryptoAltTrader.kt:1313-1314`) rather than requiring an identifiable lab hypothesis, so the label is not a genuinely learned lab setup.

**Required repair:** seal `(tacticId,setupId,revision,entrySignalTime)` into each position and feed exact closed trade outcome into the matching tactic model; rotate only on paired evidence and trainable samples. A LAB_PROPOSED entry must carry a validated, executable lab hypothesis ID or be recorded under the actual predicate fired. **Test:** concurrent old/new tactics, close order reversed, scratch closes, LAB id absent, reopened position; no counterfactual credit leakage.

### C10 [C] Crypto maturity and tactic stats apply three incompatible scratch/win definitions

**Source:** `perps/crypto/brain/CryptoBrain.kt:117-149`; `CryptoFluidLearning.kt:54-61,168-184`; `CryptoTacticSwitcher.kt:42-55`.

`win = pnlPct > 0` for fluid wins/losses and tactic rotation, but `trainable = abs(pnlPct)>=1` for canonical learning. A +0.1% fee/scratch is a win in one brain, a -0.1% scratch a tactic-changing loss, and both inconclusive in canonical stats. `CryptoFluidLearning.tradesTotal` advances on **open** (`recordTradeStart`) rather than on completed, gradeable outcomes, so unfinished positions accelerate maturity/score floors. This makes the model's “learned” phase differ from the number of settled strategy labels.

**Required repair:** unify explicit `NET_WIN`, `NET_LOSS`, `SCRATCH`, `PENDING`, `UNKNOWN` outcome definitions and a separate open-count; advance evidence maturity on settled, attributable samples only. Keep paper and live cohorts separately observable. **Test:** open 100 unclosed, then close 10 scratches and 10 trainable positions; no artificial training-maturity jump, exact denominator agreement across brains.

### C11 [C/H] Crypto native actionable check can admit a defaulted buyer percentage without its own truth gate

**Source:** `perps/CryptoAltTrader.kt:1270-1326,1558-1584`.

The `MEAN_REVERT` path accepts buy pressure **>=50** and an observed bounce. Elsewhere the caller explicitly knows `bp=50` may be the neutral **zero-count default** and tests `cryptoInputsUnobserved8236` only in the **non-actionable** branch (`:1580-1584`). Thus the early `actionableLong` condition is not intrinsically protected against a synthetic 50 if the short price tape satisfies rebound criteria. Other downstream gates may detect it; first-hop signal correctness is broken. This is separate from the Solana native-bridge default problem in the first report.

**Required repair:** supply observed/unknown buyer state to each crypto tactic; require real source-proven buyer reversal, or explicitly allow and grade a price-only tactic with lower confidence and exact provenance. **Test:** four observed bouncing prices with 0/0 trade counts: no buy-pressure contribution or fabricated confirmed-buyer result; with actual buys/sells the proper shape fires.

## New P1 defects — market-specific strategies do not receive or retain their intended inputs

### C12 [C] CRYPTO_ALT classifies 24h *displacement* as 24h *volatility*, and never reads real BTC dominance

**Source:** `perps/CryptoAltTrader.kt:2454-2485`; `perps/strategy/CryptoAltStrategy.kt:41-52,67-84,87-110,122-150`.

The `volatility24h` argument is `abs(change)`; actual high/low/range or realized variance is **not** passed. A monotonic +12% day and a 12%-amplitude choppy day enter the same HIGH/EXTREME regime. On EXTREME, `bias = -bias*0.5`: an original momentum/Bitcoin bullish bias becomes **negative**, creating a potential short/standdown, and a strong bearish price move may turn into a contrarian spot LONG before any bounce. BTC dominance is explicitly `0.0`, so `ALT_SEASON` and `BTC_SEASON` are impossible in this caller. These are definite mismatches of named variables and feature expectations. Whether contrarian entries pay is unproven.

**Required repair:** measure and source-tag realized volatility over the advertised horizon; feed observed BTC dominance or explicitly disable that submodel when missing. For high-vol mean reversal require independent reversal/exitability evidence and remaining-net move instead of unconditional sign inversion. **Test:** same 24h change with different realized volatility; actual BTC.D up/down/unknown; trending panic versus verified reclaim; spot cannot short, but must not turn unknown into a safe long.

### C13 [C] PERPS native strategy is missing open-interest and RSI evidence, and uses displacement as volatility

**Source:** `perps/PerpsTraderAI.kt:451-471`; `perps/strategy/PerpsStrategy.kt:44-56,91-103,122-155`.

The caller sends `volatility24h=abs(priceChange24hPct)`, `rsi=null`, and **omits `openInterestChange24h` entirely**, whose default is null. The code's promised `OI + price` liquidation-crowding strategies (`PerpsStrategy.kt:91-103`) cannot fire through this path; RSI reversal rules likewise cannot fire. This leaves funding, L/S ratio and lagging 24h change to determine direction, with leverage based on a mislabeled volatility input. This is a definite inactive-feature/wiring issue, not evidence any profitable OI setup has been missed in 8529 (no executed perps traffic).

**Required repair:** source/validate OI change, sampled funding, true realized volatility and timestamped RSI; mark absent feeds unavailable, do not claim strategy-active. Bind the chosen `PerpsSetup` to executed TP/SL/leverage and grade the exact setup after real fills. **Test:** funding-only; independent OI surge plus price rally; price rally without OI; real vol high versus price displacement high; a change in unused OI must change the strategy result when expected.

### C14 [C] FOREX drops RSI/reversal inputs when recalculating TP/SL and loses the selected setup contract

**Source:** `perps/ForexTrader.kt:465-499,733-769`; `perps/strategy/ForexStrategy.kt:137-223`.

At analysis, `ForexStrategy.decide` receives RSI and boolean reversal flags and freezes a direction. At execution it calls `decide` **again without them** and uses only the new TP/SL pips, leaving the original direction. The second call may choose a different direction, conviction, leverage or return null, in which case generic SmartExitOptimizer targets replace the originally selected forex stop/target. This is a definite strategy-contract break even with perfectly reliable quotes.

**Required repair:** persist original ForexSetup with the signal and frozen event clock; at fill reprice its original distance and invalidate if setup expired or direction changed; do not recompute a different thesis to extract its stops. **Test:** oversold RSI flips LONG against negative 24h momentum at analysis, then execution w/o RSI would be SHORT; verify original or explicit refreshed-cancel contract, never mixed-side TP/SL.

### C15 [C] METALS and COMMODITIES call specialist strategies but do not supply DXY/VIX, and do not use their risk contracts

**Source:** `perps/MetalsTrader.kt:448-463,679-695`; `perps/CommoditiesTrader.kt:470-484,700-711`; `perps/strategy/MetalsStrategy.kt:71-129`; `perps/strategy/CommoditiesStrategy.kt:119-176`.

`MetalsStrategy.decide` accepts DXY and VIX (core USD-inverse / safe-haven components) but caller supplies neither. `CommoditiesStrategy.decide` accepts DXY and caller omits it. The returned `MetalSetup`/`CommoditySetup` has TP/SL/leverage calibrated by class and conviction; the traders' execution route instead computes generic `FluidLearningAI` plus `SmartExitOptimizer` TP/SL, and searched consumers do not read `setup.tpPct`, `setup.slPct` or `setup.leverage` in these traders. Therefore the advertised strategy's *entry thesis* is not bound to its *exit contract*. Strategy intent to help selection is real, but the specialist inputs and stop targets are dormant.

**Required repair:** load truthful source-timestamped DXY/VIX where available or mark unavailable; store the specialist plan with signal, quote and sealed position; use that plan for exits/sizing after independent cost and safety validation, or explicitly classify it advisory-only. **Test:** identical metal price with VIX 18 vs 35 and verified DXY change; same commodity trend with DXY moving opposite; matching executed TP/SL for each selected strategy. No assertion of runtime profits without active traffic.

### C16 [C/H] Forex “session” windows rely on static hour boundaries with missing granularity at Asia open

**Source:** `perps/strategy/ForexStrategy.kt:66-85,162-175`. Comments say Tokyo opens **23:00 UTC**, but `currentSession()` maps **21:00-23:00** to `ASIA`, so 21:00 and 22:00 receive Asia-specific conviction before its documented open. The `21-23` assumption also abstracts over real pair-specific liquidity, daylight shifts and scheduled events. A time-profile mismatch is definite; the profitability sign and size require session-conditioned forward labels.

**Required repair:** use exchange/calendar-appropriate session source, distinct pair liquid hours, explicit open/close/grace windows and event calendar; no hard universal profitability assumption. **Test:** Friday 21:00, Sunday 21:59/22:00, UTC 21/22/23 weekday across major and JPY cross.

## Cross-stack acceptance conditions and repair order

1. **V3 arithmetic invariants first:** property tests for signed caps, score-scale compression, monotonicity, no signal sign flip from rounding, separate displayed versus executable expected-net scale. Run CLASSIC/MODERN/UNIFIED with identical frozen candidate inputs.
2. **Immutable strategy identity:** all native Solana, playbook, crypto tactic, Forex/Metal/Commodity/Perps plans provide `(candidateRevision, strategyId, setupId, entryWitnessAt, firstExecutableQuote, chosen direction, risk contract)`, with source quality and no rescore replacing the admitted thesis.
3. **Resident continuity:** no stale WATCHING overwrites READY, candidateVersion must represent signal revision, and ready expiry is measured from a valid event timestamp. Every update includes explicit terminal reason and exact stage receipt.
4. **Learning arithmetic:** canonical 1%/fee-adjusted result classification; separate scratches, pending and unavailable from trainable wins/losses; each outcome updates exactly the *executed* strategy's parameters, not an arbitrary tier score. Every model's N, mean, WR and PnL denominator must reconcile to position/fill IDs.
5. **Feature authenticity:** separate `price change`, `true volatility`, `momentum acceleration`, `buyer pressure`, `BTC dominance`, `DXY`, `VIX`, `funding`, `OI` and `RSI`, with observed-at and data-quality tags. UNKNOWN contributes neither pretend bullishness nor bearishness and does not invent a tactic.
6. **Replay + measured trading outcomes:** test positive, negative, flat, missing and stale inputs across all 12 Solana lanes and the distinct static/dynamic cross-asset strategies; verify selected/filled/closed/learned coherence. Compare net executable forward payoff at the right strategy clock before claiming improved selection. Keep real rug, sellability, exposure and fund safety controls.

**Priority order:** C01/C02/C03/C04 → C06/C07/C08/C09/C10 → C11/C12/C13 → C14/C15/C16 → C05 and walk-forward calibration. Implement as coherent upstream/downstream repairs, remove duplicate policies where a new owner replaces them, and add source-level failure fixtures. **This is a source audit, not an implemented fix or a guarantee of profitability.**


Repository: `shaunhayes333-stack/lifecycle-bot`  
Read-only source reference: `main` at `86a06e970ef2eaa2bff167bad92e8737ffb2e3de` (5.0.8530)  
Runtime evidence: `aate_pipeline_20261105_020555_878.txt`, build **5.0.8529** at `2c480956c`, runtime 346 seconds.  
Scope: all 12 Solana native lanes, their common playbook of **89 declared setups**, specialist bridge, V3 score, admission/FDG timing and economics, Cortex/label feedback, and the observed crypto/markets extension. **No files in the repository were modified. No build or trading tests were run.**
