# Static code audit — lifecycle-bot (Android/Kotlin Solana meme-coin bot)

*Snapshot:* `origin/main` **5.0.8757** (`916e8eb71`), app code `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot`.
*Note:* commit `1b0567ecc` (5.0.8758) landed on the local branch while this audit ran; it touches 2 files
(`engine/truth/PaperMirror8757.kt` + one call site) and does not change any number below materially.
*Method:* read-only. A purpose-built Kotlin lexer (comments/strings separated, line-preserving, brace/paren balance verified on all
1832 files) feeds a declaration index (27,921 units: top-level declarations + depth-1 member functions/nested types), a
name-based reachability walk from the Android entry points (both manifests' Application/Activities/Service/Receivers + every
`com.lifecyclebot.*` name in `res/**.xml`), and regex scanners for flags, catches and pitfalls. Tests (`app/src/test`, `ci/`)
were indexed separately and **do not count as use**. Scripts and pickles are in the scratchpad next to this file
(`lex.py`, `index.py`, `dead*.py`, `flags.py`, `rot.py`, `dups.py`, `concepts.py`, `catches.py`, `pitfalls.py`, `size.py`, `report.py`).

**Precision notes.** Reachability is by simple name, so a name collision keeps code alive: dead-code numbers are a *lower bound*
(false negatives likely, false positives rare). Spot checks of the top 20 dead items with `grep -w` all confirmed (only hits were
comments, strings, or other dead code). Member functions were indexed one level deep (class/object bodies); nested local functions
are counted inside their parent. Properties (vals/vars) are not counted as dead units.

## Totals

| Metric | Value |
|---|---|
| Kotlin files / lines | 1,832 / 638,333 |
| Comment lines | 166,325 (26% of all lines) |
| **Dead lines (estimate, removable)** | **≈33,969** (5.3%) = 13,361 unreachable top-level decls (236) + 19,611 unreachable member functions inside live types (2,104) + ≈997 flag/constant-disabled |
| Whole files with no live declaration | 95 files, 12,697 lines |
| Dead-but-test-referenced ("kept alive by tests") | 164 top-level decls (8,304 lines) + 853 members (4,479 lines) |
| `*ForTest*` hooks shipped in production code | 510 functions (1,836 lines); 437 unreachable from production |
| **Duplicate-concept groups** | **14** (§3) + 137 exact-clone groups (1,395 redundant lines) + 123 structural-clone groups (1,758 redundant lines) |
| **Patch-rot score** | **26,842** (42 per kloc); 22,841 version tags in comments, 455 superseded/retired markers, 504 revert/re-enable/restore markers, 323 disabled markers |
| Comment lines carrying a version tag | 22,674 |
| Versioned top-level types (`Name####`) | 1,275 of 2,292 types (56 %) |
| catch blocks / swallowed | 19,953 / 17,213 (86 %): 10,492 empty `{}`, 5,838 return-a-default, 770 log-only, 113 return/continue |
| Functions > 500 / > 200 / > 100 lines | 49 / 203 / 587 |

Rot score formula (per file): `version_tags + 3·superseded + 3·flipflop + 2·temporary/hotfix + 2·TODO + 2·disabled + 3·versioned-variant functions`.

## Top 25 actionable items (ranked: trading-outcome risk first, then lines removed per hour of work)

| # | Item | Evidence | Action |
|---|---|---|---|
| 1 | **V3 `FinalDecisionEngine` reads liquidity from a reason string that never contains it** — `liquidityUsd` is always `0.0`, so `ToxicModeCircuitBreaker.checkEntryAllowed` fails the per-mode liquidity floor on every non-lenient (live) evaluation with a trading mode | `v3/decision/DecisionEngine.kt:253-256` parses `reason.substringAfter("liq=")`; `LiquidityAI` (`v3/scoring/ScoringModules.kt:143-190`) emits "Strong liquidity base", "Good liquidity"…, never `liq=`; floor check `engine/ToxicModeCircuitBreaker.kt:244` | Pass `candidate.liquidityUsd` through; confirm how much live entry the V3 verdict gates |
| 2 | **Fail-open safety reads on the live buy path** — a throwing check silently means "safe" | `Executor.liveBuy` `RugMintBlacklist.isBlacklisted … catch { false }` (Executor.kt:19223), `ScannerLaneBridge.shouldRoute` toxic (19298); `Executor.doBuy` rug blacklist (14608); `PreTradeHardGate.requireLiveBuyAllowed` `RebuyLockout8019.refusal8019 … catch { null }` (PreTradeHardGate.kt:189); `CandidateAdmission8136.candidateFor8136` `holderBlock8684 … { null }` (1797); `LiveEdgeGate7877.canonicalRefusal8392` `earlyLaunchRefusal8048`/`setupRefusal8083` (701, 788); `liveRefusalCore7970` (1385, 1460); `Executor.flatChartRefused8027` (14045, 14048: `FlatChart8027`, `OnChainDepth8038.buyRefusal8038`); `ExecutableOpenGate.canOpenExecutablePosition` wave veto / bleeder probation (2784, 2870); `ExecutionRouteGuard.requireLiveRoute` `rugcheckScore < 0 … { false }` (65); `BotService.laneEvaluationAllowed7951` `LaneAutoPauseGuard.isPaused … { false }` (15507, 15622) | One `liveSafe { }` helper: on exception in LIVE return a named refusal (`*_CHECK_THREW`), count it |
| 3 | **Same predicate, opposite failure mode**: `WalletTokenAccountStateAuthority7253.isFrozen` | `catch { true }` in `Exits8363.kt:212`, `sell/StopLatencyWiring8153.kt:103,171,177`; `catch { false }` in `sell/FrozenHoldingGuard8136.kt:27`, `Executor.frozenByIssuer8122` (Executor.kt:27714), `OpenPositionPanel7807.kt:138` | Single `FrozenHoldingGuard8136.isFrozen8136` with an explicit tri-state (FROZEN/NOT/UNKNOWN) |
| 4 | **Divergent numeric substitutes when cost/stop/size code throws** | cost: `6.0` LiveRiskPolicy7807.kt:534, `5.0` NetEdge8030.kt:119, `7.0` ScalpExit8351.kt:50, `3.0` ExitTruthArbiterLive8144.kt:60; stop: `15.0` StopAuthority7887.kt:79,122 vs `10.0` ScalpExit8351.kt:51; size multipliers default to `1.0` (full size) when a damper throws: FinalDecisionGate.evaluate (1999, 2277, 4712, 4718, 6225), SmartSizer.calculate (800, 817, 851, 863), OrderSizeResolver6441.resolve (307, 310, 365, 406, 700); Kelly fraction `0.0` FDG.resolveExecutableSize7835 (6414–6473) | Return NaN/`null` and refuse in LIVE; one constant per concept if a default is kept |
| 5 | **Lane-name normalisers disagree** (≥25 private copies) — the same lane is keyed as `SHITCOIN_EXPRESS` in one store and `EXPRESS` in another (acknowledged in `CanonicalLaneIdentity6506.kt:139-142`) | `RuntimeConfigOverlay.normalizeLane` and `SpecialistCandidateBooks7803.canonicalLane` fold to EXPRESS (the latter also `BLUE_CHIP→BLUECHIP`); `CanonicalLaneIdentity6506.canonical` does not; `UnifiedPolicyHead`/`UnifiedExitPolicyHead.normalizeLane` blank→`STANDARD`, `CellAllocator7962.canonLane` blank→`UNKNOWN`+`take(24)`, `LosingStreakReflex6439`, `AICrossTalk.canonLane7329` = `uppercase()` only | Fold `expressAlias8392` into `CanonicalLaneIdentity6506.canonical`; delete the private copies (§3.6) |
| 6 | **"Buying the top" door flip-flopped 4× and exists in ≥8 definitions** | `truth/LaneAuthority8577.kt:40-45` (8582 bound INTO_HIGH/CHASING_SPIKE/CHASE_EXTENDED/NEAR_HIGH; 8604 un-bound "LIVE decides like paper"), :100-103 (8626 restored as `memeEntryRefusal8626`), :106+ (8706 rewritten); 8757/8758 again mirror paper for MOONSHOT. Thresholds: `LiveEdgeGate7877.chasing8065` 80 %/0.88/+100 % 5m, `chasingRunner8468` 40 %/0.92/+60 %, `MoonshotTraderAI.nearHighRefused8555` 40 %/0.92 (same as 8468), `LaneAuthority8577` 0.97/5 %, `LaunchChase7280.isChase` 3×/180 s, `NetEdge8030.overextended8054`, `LaneHunter7297.chasePenalty7797` | One `EntryTiming` authority with per-lane parameters + its own forward record; delete the rest |
| 7 | **240-line `if (false && …)` block** in the legacy buy path | `Executor.maybeAct`, Executor.kt:12494–12733 (path is already hard-blocked at 12487–12492) | Delete |
| 8 | **Largest unreferenced private functions** | `Executor.realisticEntrySize6867` 393 lines (Executor.kt:3785, zero callers; comments in ExecutableOpenGate/LiveGrowthDoctrine still describe it as binding); `BotService.runSupervisorPhase` 152 (BotService.kt:23426); `MainActivity.updateStocksPositionsList` 187; `HostWalletTokenTracker.recordBuyConfirmed` 104 | Delete; fix the comments that claim the clamp still runs |
| 9 | **95 files with no live declaration (12,697 lines)** | voice stack: `PersonalityQuoteBanks` 641, `voice/PersonalityVoiceRegistry` 538, `voice/PersonaSpeaker` 284, `voice/ElevenLabsApi` 285, `voice/VoiceTextProcessor` 224; `ExitManager` 434; `TelegramBot` 356 (self-declared SIDECAR); `UnifiedNarrativeAI` 255; 6xxx truth generation kept alive only by tests (`CanonicalLedger6387`, `CanonicalEntryPipeline6398`, `DataProviderFaultCircuits6468`, `PerpsHandoffIdempotency6632`, `MemeExecutionIntent6621`, `BuyResult6621`, …) | Delete with their tests (list in §1.2) |
| 10 | **Subsystems permanently off in production, on only in tests** | `truth/EvidenceHold8336.enabled8336 = false` (no prod writer, 18 test writes; still read by `FdgGate8365.kt:165`, `LiveEdgeGate7877.kt:708`); `LiveNoPaper8184.enforced8184 = false` (6 test writes; read by `CanonicalPositionAuthority6441.kt:249`); `truth/ExploreLive8733 ENABLED_8736 = false` (still wired into PreTradeHardGate.kt:356-360, BuyRouteSafety8522.kt:131,157); `HunterWindow8293.forceLeaders8631`; `FinalDecisionGate.useKellySizing` (23-line block at 5406) | Delete, or turn into a real persisted setting; tests must not cover a prod-unreachable path |
| 11 | **Mega-functions** | `BotService.processTokenCycle` 7,698 lines / ≈1,450 branch points / 297 swallowed catches; `FinalDecisionGate.evaluate` 4,727 / 1,014 / 154; `Executor.liveBuy` 3,614 / 727 / 269; `BotService.startBot` 3,045; `PipelineHealthCollector.dumpText` 2,987; `Executor.liveSellInScope7314` 2,939 | Split by stage (intake → admission → sizing → execution); each stage returns a typed verdict |
| 12 | **Swallowed-exception density** | 17,213 swallowed of 19,953 catches; BotService 2,048 (58/kloc), Executor 1,835 (56/kloc), LiveEdgeGate7877 99/kloc, LearningPersistence 172/kloc | `swallow(tag) { }` helper that counts into PipelineHealthCollector; forbid bare `catch (_: Throwable) {}` in `engine/` via detekt |
| 13 | **Cost model implemented ≥10×** | §3.3 | Survivor `NetEdge8030.costPct8030`; others delegate or go |
| 14 | **Stop distance from ≥15 sources** | §3.2 | Survivor `cortex/StopAuthority7887.stopFor8418` (returns `ExitThresholds8418.Binding8418`) |
| 15 | **Token age read from the watchlist-add time** in live decisions | `BotService.kt:27929` `tokenAgeMinutes = now − addedToWatchlistAt`, :29861 `entryAgeMinutes`, :26141, :26768, :21870; and `perps/CryptoLaneDesk7391.kt:109`, `perps/crypto/MultiChainMemeLane7962.kt:508` *write* a synthetic birth into `addedToWatchlistAt` | Survivor `CanonicalTokenBirthTime7440.launchAgeMs7767` (§3.4) |
| 16 | **Three live fill-lot ledgers** | `FillLotLedger6344` (294), `truth/FillLotLedger6386` (107), `truth/FillLotLedger6504` (408) — all reachable | Keep 6504, migrate callers |
| 17 | **Report-only digests on the sell path; source-reading sentinels** | `Executor.kt:5741` (trade fan-out in `recordTrade`) calls `OperatorKpiCloseoutReport.emit()` on every SELL/PARTIAL_SELL record → >100 digest status strings; 48 `engine/Operator*.kt` files (1,727 lines) are report-only; `MemeLaneParitySentinel`, `UiAnrDecouplingSentinel`, `MemeTraderFullAuditSweeper`, `AsiSsiReauditSweeper`, `SymbolicInvariantProver` read `File(root,"src/main/kotlin/…")` — absent on a device | Move to tests/CI; remove from the sell path |
| 18 | **Crypto brain is a copy-paste of the meme brain** | `perps/crypto/brain/*` 1,817 lines; `CryptoRugMintBlacklist` (92 lines) mirrors `RugMintBlacklist` (92) with structurally identical `init`/`restore`; `CryptoLaneTimeoutGate`/`LaneTimeoutGate`, `CryptoLivePauseButton`/`LivePauseButton`, `CryptoScannerLaneBridge`/`ScannerLaneBridge` share exact-clone bodies (`restoreAll`, `recordOutcome`, `persist`, `init`) | Parameterise by asset class |
| 19 | **Commodities/Forex/Metals traders triplicated** (4,276 lines) | ≥20 identical or structurally identical functions: `monitorPositions`, `monitorSinglePosition` (33 lines ×3), `saveState`, `loadState`, `liveWalletSol7212`, `closePositionManual`, `initContext`, `resetCounters`, `purgeAllPositions`, `start` (59 lines ×2), `setLiveMode` (43 ×2), `persistTradeToTurso` (42 ×2) | Extract `SimpleMarketTrader` base |
| 20 | **"Proven" has 269 different bars** | 269 `MIN_N`/`PROOF`/`MIN_TRADES`/`MIN_SAMPLES` constants in 154 files (values 3…100; most common 30, 20, 5, 8, 100); one Wilson bound in the repo (`cortex/CortexWalkForward8144.wilsonLower8144`) | One `ProofBar(n, lcb)` used everywhere (§3.5) |
| 21 | **≈40 price/quote freshness windows** (10 s … 10 min) | §3.7 | Two constants: mark (`CanonicalPriceMark6522`) and quote (`StalePriceFillGate6427`) |
| 22 | **Lamports truncated, used in IDs** | 26 sites `(sol * 1e9).toLong()`; event/idempotency IDs `JournalEconomicReplay6619.kt:940,1167` embed `(basis*1e9).toLong()`; swap inputs `Executor.kt:13634, 20350` | `roundToLong()`; IDs from receipt lamports, not doubles |
| 23 | **Constant-true / dead switches** | `SolanaMarketScanner.kt:1552` `if (!backpressure || true)` (and `val backpressure = false` 1537); `BirdeyeBudgetGate.EMERGENCY_CONSERVATION_MODE` const false (6 branches); `CloudLearningSync.SECURE_HIVE_GATEWAY_READY_6637`; `CryptoAltTrader.LEVERAGE_VENUE_AVAILABLE_7183` (6 branches); `RuntimeConfigOverlay.HARD_QUALITY_ONLY`; `BotService.MARKET_TRADER_KILL_SWITCH`; `TradeAuthorizer.bypassRugcheck` local `= false` (636, read 664/675); `FinalDecisionGate.lowWrBypass = false` (2456) | Inline the constant, delete dead branches |
| 24 | **Test hooks in production** | 510 `*ForTest*` functions (1,836 lines), `resetForTest` in 174 files, 437 unreachable from prod | `internal` + `@VisibleForTesting`, or test fixtures |
| 25 | **Version-history comments** | 22,674 comment lines carry a `V5.x.NNNN`/`§NNNN` tag; Executor.kt 910 and BotService.kt 833 `superseded by`/`V5.0.NNNN —` markers; 26 % of all lines are comments | Move history to `CHANGELOG.md`; keep only current rationale beside code |

## 1. Dead code

### 1.1 Summary

* Unreachable top-level declarations: **236** (13,361 lines). Of these, 156 (9,308 lines) are referenced by **nothing** in production; 80 (4,053 lines) are referenced only from other dead code (transitively dead).
* Unreachable member functions inside live classes/objects: **2,104** (19,611 lines).
* By area (dead lines): engine/truth 12,581 · engine/ (root) 10,539 · perps 1,966 · engine/voice 1,414 · ui 1,345 · v3/scoring 1,057 · engine/quant 460 · network 456.
* Files with the most dead lines: Executor.kt 674 · PersonalityQuoteBanks.kt 617 · voice/PersonalityVoiceRegistry.kt 507 · ui/CryptoAltActivity.kt 505 · truth/LiveContinuity6392.kt 499 · ui/MainActivity.kt 425 · truth/CanonicalSettlement6389.kt 422 · truth/ForensicFinalityAndTuner6393.kt 397 · ExitManager.kt 395.
* Self-declared: `engine/SmartSystemRuntimeRegistry.kt` already lists `TelegramBot` (SIDECAR, "not auto-wired") and `ProviderHealthGate` (DEPRECATED, "superseded by ApiHealthMonitor…") — both confirmed dead.

### 1.2 The 40 biggest dead items

| # | Lines | Kind | Name | Location | Why dead |
|---|---|---|---|---|---|
| 1 | 617 | top object | `PersonalityQuoteBanks` | `engine/PersonalityQuoteBanks.kt:24` | 1 reference(s), all from dead code |
| 2 | 507 | top object | `PersonalityVoiceRegistry` | `engine/voice/PersonalityVoiceRegistry.kt:31` | 4 reference(s), all from dead code |
| 3 | 395 | top object | `ExitManager` | `engine/ExitManager.kt:40` | no references |
| 4 | 393 | member fun | `Executor.realisticEntrySize6867` | `engine/Executor.kt:3785` | no references |
| 5 | 316 | top object | `TelegramBot` | `engine/TelegramBot.kt:40` | no references |
| 6 | 274 | top object | `CanonicalLedger6387` | `engine/truth/CanonicalLedger6387.kt:18` | no references; referenced by tests only |
| 7 | 260 | top object | `PersonaSpeaker` | `engine/voice/PersonaSpeaker.kt:24` | 1 reference(s), all from dead code |
| 8 | 258 | top object | `ElevenLabsApi` | `engine/voice/ElevenLabsApi.kt:27` | 2 reference(s), all from dead code |
| 9 | 253 | top object | `CanonicalEntryPipeline6398` | `engine/truth/CanonicalEntryPipeline6398.kt:29` | no references; referenced by tests only |
| 10 | 240 | top object | `UnifiedNarrativeAI` | `engine/UnifiedNarrativeAI.kt:15` | no references |
| 11 | 203 | top object | `VoiceTextProcessor` | `engine/voice/VoiceTextProcessor.kt:21` | 1 reference(s), all from dead code |
| 12 | 196 | top object | `DataProviderFaultCircuits6468` | `engine/truth/DataProviderFaultCircuits6468.kt:35` | no references; referenced by tests only |
| 13 | 189 | top object | `RunnerReplay8153` | `engine/RunnerReplay8153.kt:14` | no references; referenced by tests only |
| 14 | 187 | member fun | `MainActivity.updateStocksPositionsList` | `ui/MainActivity.kt:13100` | no references |
| 15 | 182 | member fun | `TrailingStopManager.calculateAdaptiveStop` | `engine/TrailingStopManager.kt:46` | 1 reference(s), all from dead code |
| 16 | 178 | top object | `InvariantSelfCheck6430` | `engine/truth/InvariantSelfCheck6430.kt:29` | no references |
| 17 | 165 | top object | `PerpsHandoffIdempotency6632` | `engine/truth/PerpsHandoffIdempotency6632.kt:52` | no references; referenced by tests only |
| 18 | 158 | top object | `MemeExecutionIntent6621` | `engine/truth/MemeExecutionIntent6621.kt:53` | 3 reference(s), all from dead code; referenced by tests only |
| 19 | 155 | top object | `PaperEvBucketGate6405` | `engine/truth/PaperEvBucketGate6405.kt:42` | 1 reference(s), all from dead code; referenced by tests only |
| 20 | 152 | member fun | `BotService.runSupervisorPhase` | `engine/BotService.kt:23426` | no references |
| 21 | 147 | top object | `ProjectSniperPhantomGuard6846` | `engine/truth/ProjectSniperPhantomGuard6846.kt:44` | no references |
| 22 | 141 | member fun | `PerpsMarketScanners.scanStockQuality` | `perps/PerpsMarketScanners.kt:430` | no references |
| 23 | 136 | top object | `BuyResult6621` | `engine/truth/BuyResult6621.kt:29` | no references; referenced by tests only |
| 24 | 130 | top object | `LaneEdgeConcentrator6334` | `engine/LaneEdgeConcentrator6334.kt:52` | no references |
| 25 | 130 | member fun | `SolanaArbAI.scanForOpportunity` | `v3/scoring/SolanaArbAI.kt:256` | no references |
| 26 | 118 | top object | `AsiSsiReauditSweeper` | `engine/AsiSsiReauditSweeper.kt:14` | no references; referenced by tests only |
| 27 | 117 | top object | `PriceIntegrityAuthority6405` | `engine/truth/PriceIntegrityAuthority6405.kt:38` | 1 reference(s), all from dead code; referenced by tests only |
| 28 | 115 | top object | `ExecutablePriceStopPreflight6345` | `engine/ExecutablePriceStopPreflight6345.kt:26` | no references; referenced by tests only |
| 29 | 115 | top object | `MaintenanceBudgetGovernor6469` | `engine/truth/MaintenanceBudgetGovernor6469.kt:42` | no references; referenced by tests only |
| 30 | 113 | top object | `SymbolicInvariantProver` | `engine/SymbolicInvariantProver.kt:15` | no references; referenced by tests only |
| 31 | 113 | member fun | `MainActivity.showPerpsSignalsDialog` | `ui/MainActivity.kt:12300` | no references |
| 32 | 112 | top object | `BotLoopStageTiming6402` | `engine/truth/BotLoopStageTiming6402.kt:30` | no references; referenced by tests only |
| 33 | 112 | top object | `EarlyLaunchBypass6394` | `engine/truth/EarlyLaunchBypass6394.kt:21` | no references; referenced by tests only |
| 34 | 111 | top object | `TicketAuthorityContinuity6624` | `engine/truth/TicketAuthorityContinuity6624.kt:46` | no references; referenced by tests only |
| 35 | 104 | top object | `OperatorFullLearningSystemAuditDigest` | `engine/OperatorFullLearningSystemAuditDigest.kt:13` | no references |
| 36 | 104 | member fun | `HostWalletTokenTracker.recordBuyConfirmed` | `engine/HostWalletTokenTracker.kt:794` | no references |
| 37 | 103 | top object | `ContaminationIsolationMigration6737` | `engine/truth/ContaminationIsolationMigration6737.kt:39` | no references; referenced by tests only |
| 38 | 103 | member fun | `CryptoAltActivity.buildHiveMindPanel` | `ui/CryptoAltActivity.kt:1693` | no references |
| 39 | 102 | top object | `WalletBalanceProof6393` | `engine/truth/ForensicFinalityAndTuner6393.kt:121` | 1 reference(s), all from dead code; referenced by tests only |
| 40 | 100 | top object | `CanonicalLearningContract6346` | `engine/CanonicalLearningContract6346.kt:42` | no references; referenced by tests only |

**Whole files with no live declaration** (top 40 of 95; lines incl. header):

| Lines | File | Declarations |
|---|---|---|
| 641 | `engine/PersonalityQuoteBanks.kt` | `PersonalityQuoteBanks` |
| 538 | `engine/voice/PersonalityVoiceRegistry.kt` | `PersonalityVoiceRegistry` |
| 434 | `engine/ExitManager.kt` | `ExitManager` |
| 356 | `engine/TelegramBot.kt` | `TelegramBot` |
| 292 | `engine/truth/CanonicalLedger6387.kt` | `CanonicalLedger6387` |
| 285 | `engine/voice/ElevenLabsApi.kt` | `ElevenLabsApi` |
| 284 | `engine/voice/PersonaSpeaker.kt` | `PersonaSpeaker` |
| 282 | `engine/truth/CanonicalEntryPipeline6398.kt` | `CanonicalEntryPipeline6398` |
| 255 | `engine/UnifiedNarrativeAI.kt` | `UnifiedNarrativeAI` |
| 241 | `engine/truth/V3VerdictContract6622.kt` | `V3VerdictContract6622`, `MemeLane6622`, `PostHocHealingAudit6622` |
| 231 | `engine/truth/DataProviderFaultCircuits6468.kt` | `DataProviderFaultCircuits6468` |
| 224 | `engine/voice/VoiceTextProcessor.kt` | `VoiceTextProcessor` |
| 217 | `engine/truth/PerpsHandoffIdempotency6632.kt` | `PerpsHandoffIdempotency6632` |
| 213 | `engine/truth/BuyResult6621.kt` | `BuyResult6621`, `PositionEntryLaneRegistry6621` |
| 211 | `engine/truth/MemeExecutionIntent6621.kt` | `MemeExecutionIntent6621` |
| 207 | `engine/truth/InvariantSelfCheck6430.kt` | `InvariantSelfCheck6430` |
| 203 | `engine/RunnerReplay8153.kt` | `RunnerReplay8153` |
| 197 | `engine/truth/PaperEvBucketGate6405.kt` | `PaperEvBucketGate6405` |
| 191 | `engine/truth/ProjectSniperPhantomGuard6846.kt` | `ProjectSniperPhantomGuard6846` |
| 182 | `engine/LaneEdgeConcentrator6334.kt` | `LaneEdgeConcentrator6334` |
| 157 | `engine/truth/TicketAuthorityContinuity6624.kt` | `TicketAuthorityContinuity6624` |
| 157 | `engine/truth/MaintenanceBudgetGovernor6469.kt` | `MaintenanceBudgetGovernor6469` |
| 155 | `engine/truth/PriceIntegrityAuthority6405.kt` | `PriceIntegrityAuthority6405` |
| 146 | `engine/truth/MemeSellFinality6620.kt` | `MemeSellFinality6620` |
| 142 | `engine/truth/ContaminationIsolationMigration6737.kt` | `ContaminationIsolationMigration6737` |
| 142 | `engine/truth/BotLoopStageTiming6402.kt` | `BotLoopStageTiming6402` |
| 142 | `engine/CanonicalLearningContract6346.kt` | `CanonicalLearningContract6346` |
| 141 | `engine/ExecutablePriceStopPreflight6345.kt` | `ExecutablePriceStopPreflight6345` |
| 133 | `engine/truth/EarlyLaunchBypass6394.kt` | `EarlyLaunchBypass6394` |
| 132 | `engine/truth/LaneAdaptiveDamping6472.kt` | `LaneAdaptiveDamping6472` |
| 132 | `engine/truth/CanaryReleaseGate6386.kt` | `CanaryReleaseGate6386` |
| 132 | `engine/AsiSsiReauditSweeper.kt` | `AsiSsiReauditSweeper` |
| 128 | `engine/truth/MemeEntryCoordinator6621.kt` | `MemeEntryCoordinator6621` |
| 128 | `engine/SymbolicInvariantProver.kt` | `SymbolicInvariantProver` |
| 124 | `engine/ExecutableQuoteGate.kt` | `ExecutableQuoteGate` |
| 123 | `engine/truth/StartupReconciliation6635.kt` | `StartupReconciliation6635` |
| 123 | `engine/truth/RiskExitPriorityDomain6461.kt` | `RiskExitPriorityDomain6461` |
| 121 | `engine/truth/ExitPendingOrphanGuard6402.kt` | `ExitPendingOrphanGuard6402` |
| 120 | `engine/execution/RouteValidator.kt` | `RouteValidator` |
| 117 | `engine/OperatorFullLearningSystemAuditDigest.kt` | `OperatorFullLearningSystemAuditDigest` |

### 1.3 Runtime-flag-disabled subsystems and constant conditions

| Switch | Declared | Prod writers | Reads / gated code | Status |
|---|---|---|---|---|
| `EvidenceHold8336.enabled8336 = false` (`@Volatile var`) | truth/EvidenceHold8336.kt:41 | none (18 writes in tests) | 9 reads: EvidenceHold8336.kt:180,196,376,401; FdgGate8365.kt:165; LiveEdgeGate7877.kt:708 — entire 464-line hold/rehearsal machinery | dead in prod |
| `LiveNoPaper8184.enforced8184 = false` | LiveNoPaper8184.kt:48 | none (6 test writes) | LiveNoPaper8184.kt:51,98; CanonicalPositionAuthority6441.kt:249 | dead in prod |
| `ExploreLive8733.ENABLED_8736 = false` (const) | truth/ExploreLive8733.kt:37 | — | `eligible8733` returns false (60) ⇒ PreTradeHardGate.kt:356-360 explore path, BuyRouteSafety8522.kt:131,157 relabel, budget/spend code | dead (turned off 3 versions after it shipped) |
| `HunterWindow8293.forceLeaders8631 = false` | HunterWindow8293.kt:35 | none | HunterWindow8293.kt:39 | dead switch |
| `FinalDecisionGate.useKellySizing = false` | FinalDecisionGate.kt:380 | none | 23-line Kelly block at FinalDecisionGate.kt:5406 | dead |
| `BehaviorLearning.strategyLearningFromLegacy = false` | BehaviorLearning.kt:58 | none | BehaviorLearning.kt:249, LearningCounterActivity.kt:216 | dead switch |
| `BirdeyeBudgetGate.EMERGENCY_CONSERVATION_MODE = false` (const) | BirdeyeBudgetGate.kt:56 | — | 101, 180, 182, 202, 254, 367 | dead branches |
| `CloudLearningSync.SECURE_HIVE_GATEWAY_READY_6637 = false` (const) | CloudLearningSync.kt:22 | — | 177 (`&&` chain never true) | dead branch |
| `CryptoAltTrader.LEVERAGE_VENUE_AVAILABLE_7183 = false` | perps/CryptoAltTrader.kt:194 | — | 2020, 2032, 2070, 2090, 2410, 2413 | dead branches |
| `RuntimeConfigOverlay.HARD_QUALITY_ONLY = false` (const) | RuntimeConfigOverlay.kt:8 | — | 46, 49, 74 | dead branches |
| `BotService.MARKET_TRADER_KILL_SWITCH = false` (const) | BotService.kt:191 ("Flip to false to re-enable" / "V5.9.362 — reinstated") | — | 218, 301, 4443, 4706, 6750 | dead branches |
| `PumpFunWS.TRADE_STREAM_DEFAULT_8354 = false` | network/PumpFunWS.kt:389 | — | default only, setting overrides | OK (real default) |
| `if (false && shouldActOnBuy && …)` | Executor.kt:12494 | — | **240 lines** to 12733 | unreachable |
| `if (!backpressure || true)` with `val backpressure = false` | SolanaMarketScanner.kt:1537/1552 | — | always true; `backpressure` meaningless | constant condition |
| `val bypassRugcheck = false` | TradeAuthorizer.kt:636 | — | 664, 675 | constant |
| `val lowWrBypass = false // V5.9.809: revoked` | FinalDecisionGate.kt:2456 | — | 2472 | constant |
| `val provenDead = false` | BrainConsensusBridge6329.kt:84 | — | 105, 108 (label never incremented, verdict field always false) | constant |
| `val snipeMode = false // no snipeMode flag yet` | BotService.kt:20685 | — | 20686 | constant |

Code after `return`/`throw` in the same block: the scanner's 176 raw hits all reduced to multi-line conditions / braceless `if` bodies
on review — **0 confirmed** (the Kotlin compiler would warn anyway).

### 1.4 "Kept alive by tests" and test hooks

* 164 unreachable top-level declarations (8,304 lines) and 853 unreachable members (4,479 lines) are named in tests — the tests
  exercise code production never runs (e.g. `CanonicalLedger6387`, `CanonicalEntryPipeline6398`, `DataProviderFaultCircuits6468`,
  `RunnerReplay8153`, `PerpsHandoffIdempotency6632`, `MemeExecutionIntent6621`, `BuyResult6621`, `PriceIntegrityAuthority6405`,
  `ExecutablePriceStopPreflight6345`, `MaintenanceBudgetGovernor6469`, `SymbolicInvariantProver`, `BotLoopStageTiming6402`,
  `EarlyLaunchBypass6394`, `TicketAuthorityContinuity6624`, `ContaminationIsolationMigration6737`, `CanonicalLearningContract6346`,
  `CanaryReleaseGate6386`, `MemeSellFinality6620`, `V3VerdictContract6622`, `MemeEntryCoordinator6621`).
* 510 `*ForTest*` functions ship in production (`resetForTest` in 174 files, `clearForTest` ×8 versions in 65 files).
* Self-auditing source readers (no effect on device): `MemeTraderFullAuditSweeper`, `AsiSsiReauditSweeper`, `SymbolicInvariantProver`
  (dead) and `MemeLaneParitySentinel`, `UiAnrDecouplingSentinel` (live, but read `File(root,"src/main/kotlin/…")`, which does not exist in an APK).

## 2. Patch rot

### 2.1 Counts

* `superseded by`: 133 occurrences in 73 files. `V5.0.NNNN —` headers: 11,146. Any `V5.x.NNNN` tag: 21,969. Comment lines with a tag: 22,674.
* Revert/re-enable/restore/reinstate/revoked/"was:" markers: 504; disabled/turned-off/no-op markers: 323; temporary/hotfix/workaround/band-aid: 119; TODO/FIXME: 3 (history is written as prose, not TODOs).
* Versioned identifiers: 1,275 of 2,292 top-level types carry a 4-digit version suffix; 159 function stems exist in ≥3 versioned variants
  (e.g. `count×22`, `read×15`, `classify×13`, `verdict×12`, `onClose×10`, `fresh×10`, `refusal×8`, `provenLosing×7`, `proven×6`).

Per-file counts of the two markers the brief names (`superseded by` + `V5.0.NNNN —`): Executor.kt 910 · BotService.kt 833 ·
truth/ForwardReturnLabeler7731.kt 300 · cortex/Cortex7885.kt 234 · PipelineHealthCollector.kt 190 · cortex/LanePlaybook7907.kt 189 ·
FinalDecisionGate.kt 159 · truth/CandidateAdmission8136.kt 143 · truth/LiveEdgeGate7877.kt 140 · perps/CryptoAltTrader.kt 132 ·
ui/MainActivity.kt 131 · ExecutableOpenGate.kt 101 · TradeHistoryStore.kt 100.

### 2.2 The 30 worst files (rot score)

| # | Rot | /kloc | Lines | Version tags | Superseded | Flip-flop | Temp/hotfix | Disabled | Versioned variants | File |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2612 | 74 | 35,250 | 2314 | 25 | 43 | 4 | 43 | 0 | `engine/BotService.kt` |
| 2 | 1984 | 61 | 32,763 | 1802 | 12 | 34 | 6 | 15 | 0 | `engine/Executor.kt` |
| 3 | 558 | 40 | 14,072 | 507 | 4 | 7 | 2 | 7 | 0 | `ui/MainActivity.kt` |
| 4 | 508 | 87 | 5,830 | 402 | 18 | 6 | 1 | 4 | 8 | `engine/cortex/Cortex7885.kt` |
| 5 | 492 | 139 | 3,550 | 433 | 10 | 3 | 6 | 1 | 2 | `engine/truth/ForwardReturnLabeler7731.kt` |
| 6 | 432 | 65 | 6,617 | 376 | 11 | 5 | 0 | 4 | 0 | `engine/FinalDecisionGate.kt` |
| 7 | 370 | 61 | 6,078 | 343 | 1 | 4 | 0 | 3 | 2 | `perps/CryptoAltTrader.kt` |
| 8 | 363 | 72 | 5,056 | 344 | 3 | 0 | 2 | 3 | 0 | `engine/PipelineHealthCollector.kt` |
| 9 | 299 | 66 | 4,521 | 245 | 7 | 9 | 0 | 3 | 0 | `engine/ExecutableOpenGate.kt` |
| 10 | 297 | 109 | 2,713 | 261 | 8 | 2 | 0 | 0 | 2 | `engine/cortex/LanePlaybook7907.kt` |
| 11 | 234 | 124 | 1,885 | 213 | 4 | 3 | 0 | 0 | 0 | `engine/truth/CandidateAdmission8136.kt` |
| 12 | 226 | 148 | 1,526 | 203 | 3 | 4 | 0 | 1 | 0 | `engine/truth/LiveEdgeGate7877.kt` |
| 13 | 222 | 45 | 4,886 | 178 | 9 | 3 | 0 | 4 | 0 | `engine/SolanaMarketScanner.kt` |
| 14 | 201 | 62 | 3,263 | 182 | 2 | 1 | 0 | 2 | 2 | `engine/TradeHistoryStore.kt` |
| 15 | 182 | 95 | 1,916 | 155 | 5 | 4 | 0 | 0 | 0 | `v3/scoring/MoonshotTraderAI.kt` |
| 16 | 152 | 70 | 2,176 | 146 | 1 | 1 | 0 | 0 | 0 | `v3/scoring/ShitCoinTraderAI.kt` |
| 17 | 145 | 50 | 2,885 | 127 | 1 | 5 | 0 | 0 | 0 | `v3/scoring/FluidLearningAI.kt` |
| 18 | 144 | 45 | 3,183 | 125 | 0 | 3 | 3 | 2 | 0 | `v3/scoring/EducationSubLayerAI.kt` |
| 19 | 140 | 89 | 1,581 | 99 | 6 | 5 | 1 | 0 | 2 | `engine/truth/ExploreShadow8347.kt` |
| 20 | 136 | 110 | 1,242 | 119 | 5 | 0 | 0 | 1 | 0 | `engine/truth/LiveRiskPolicy7807.kt` |
| 21 | 130 | 53 | 2,475 | 112 | 0 | 4 | 0 | 0 | 2 | `engine/truth/CanonicalPositionAuthority6441.kt` |
| 22 | 120 | 68 | 1,758 | 92 | 3 | 5 | 1 | 1 | 0 | `engine/GlobalTradeRegistry.kt` |
| 23 | 117 | 69 | 1,688 | 102 | 3 | 2 | 0 | 0 | 0 | `engine/LiveCanonicalRecovery6686.kt` |
| 24 | 113 | 63 | 1,784 | 95 | 3 | 1 | 0 | 3 | 0 | `network/KeylessLlmClient.kt` |
| 25 | 111 | 46 | 2,430 | 85 | 0 | 6 | 0 | 4 | 0 | `engine/HostWalletTokenTracker.kt` |
| 26 | 109 | 74 | 1,465 | 99 | 2 | 0 | 0 | 2 | 0 | `engine/DataOrchestrator.kt` |
| 27 | 109 | 45 | 2,436 | 101 | 0 | 0 | 2 | 2 | 0 | `network/SolanaWallet.kt` |
| 28 | 105 | 86 | 1,220 | 95 | 0 | 2 | 1 | 1 | 0 | `network/PumpFunWS.kt` |
| 29 | 101 | 103 | 982 | 59 | 6 | 8 | 0 | 0 | 0 | `engine/HandoffReceiverTasks8153.kt` |
| 30 | 100 | 43 | 2,304 | 87 | 0 | 3 | 0 | 2 | 0 | `perps/TokenizedStockTrader.kt` |

Densest (rot per kloc, ≥900 lines): truth/LiveEdgeGate7877.kt 148 · truth/ForwardReturnLabeler7731.kt 139 · truth/CandidateAdmission8136.kt 124 ·
truth/LiveRiskPolicy7807.kt 110 · cortex/LanePlaybook7907.kt 109 · HandoffReceiverTasks8153.kt 103 · v3/scoring/QualityTraderAI.kt 98 · v3/scoring/MoonshotTraderAI.kt 95.

### 2.3 Successive versioned layers (A → A8xxx → A8yyy)

Same-object variant chains where a later version calls/overrides the earlier one (36 found; deepest):

| Chain | File |
|---|---|
| `shadowFlushDue8402 → shadowFlushDue8396 → shadowFlushDue8377` (3 deep) | cortex/Cortex7885.kt |
| `registerStores8144 → {8289, 8305, 8309, 8418}` (fan-out of 4 later versions) | MemoryGuard7977.kt |
| `calibratedRead8366 / 8372 → calibratedRead8325` | cortex/Cortex7885.kt |
| `suspends8366 / 8372 → suspends8322` | cortex/CortexCalibration7901.kt |
| `vetoRefusesWinners8006 → 7953` | cortex/Cortex7885.kt |
| `stopMult7948 → stopMult7877` (+ `stopMultFor`) | truth/ExitRegret7752.kt |
| `allInCostPct8350 → allInCostPct → roundTripCostPct7766`, `labelCostPct8534` | truth/FieldManual7715.kt |
| `floor8363 → floor8370` | Exits8363.kt (GreenFloor8363) |
| `codexCandles8169 → 8521`, `raydiumSkipWhy8189 → 8315`, `victim8738 → 8398`, `forEachLaunch8744 → 8756` | chart/ChartSources7955, sell/StopRoute8189, WatchlistEviction8398, truth/LaunchHour8628 |
| `send → send8085`, `get → get8095` | network/HeliusSender.kt, network/DexscreenerApi.kt |

Co-existing versioned object families (all generations still live unless marked): `FillLotLedger6344/6386/6504` (all live),
`RunnerFunnel8297/8331/8535` (all live), `PositionStateLedger6427/6454`, `PositionIdentity6395/6411`, `CanonicalEntryAuthority6540/6551`,
`AccountingIntegrity7948/8169`, `StopSlip8327/8756`, `MemoryBounds8144/8289`, `HunterMandates8031/8293`, `RootCauseClassifier6460/6471`,
`EarlyLaunchBypass6394 (dead)/6396`, `PriceIntegrityAuthority6387/6405 (both dead)`, `CanonicalFill6387/6393 (both dead)`.

Decision-path layering: the live buy passes through versioned doors in sequence — `Executor.liveBuy` alone references **51 distinct
versioned objects** and 60 versioned functions; `BotService.processTokenCycle` 32/66; `CandidateAdmission8136.candidateFor8136` 32/54;
`FinalDecisionGate.evaluate` 23/32; `LiveEdgeGate7877.canonicalRefusal8392` 21/43; `Executor.requestSellCore7948` 21/32.

### 2.4 Flip-flops (a later version re-enables what an earlier one disabled)

| Feature | History (from in-code comments) | Where |
|---|---|---|
| "Buying the top" live doors | 8582 bound INTO_HIGH / CHASING_SPIKE / CHASE_EXTENDED / NEAR_HIGH / MEME_BUYS_THE_HIGH_8582 → 8604 removed ("LIVE decides like the paper book") → 8626 restored as `memeEntryRefusal8626` ("live went on buying tops: LOSS_BOUGHT_TOP=40 of 63") → 8706 rewritten ("live runner admits LOSS_BOUGHT_TOP=76 of 110") → 8757 live MOONSHOT mirrors the paper book again → 8758 re-adds cost/price/proof checks to the mirror | truth/LaneAuthority8577.kt:40-45, 100-110; truth/PaperMirror8757.kt |
| Explore live buys | 8733 shipped → 8736 `ENABLED_8736 = false` (bought unsellable token) — wiring kept | truth/ExploreLive8733.kt:30-37 |
| Market-trader kill switch | added → "V5.9.362 — reinstated; switches in Settings now drive …" → const `false` | BotService.kt:189-191 |
| Moonshot bootstrap cap / hard entry gates | V5.9.218 cap reduction → V5.9.316 REVERT; V5.9.235 base44 hard gates → V5.9.316 REVERTED | v3/scoring/MoonshotTraderAI.kt:105, 572 |
| Paper-mode lane unchoke | V5.0.6304 bypass → V5.0.6307 REVERTED ("just revert the lane unchoke") | FinalDecisionGate.kt:4635-4636 |
| Intake `addedAt` refresh | V5.9.1328 "ROOT FIX B: refresh addedAt on duplicate intake" → V5.0.8631 "FIRST admission time, never refreshed"; V5.0.4597 intake blackout → V5.0.4598 REVERTED | GlobalTradeRegistry.kt:246, 411, 431-433 |
| Scanner backpressure / spacing | V5.9.693 backpressure disabled (`|| true`), V5.9.1479 reverted in-lock sleep, "Reverting to V5.9.626", Gecko/CoinGecko feeders "re-enabled" | SolanaMarketScanner.kt:879, 1537-1552, 1618, 4109 |
| Low-WR bypass | V5.9.809 "revoked (was: systemWrForBypass < 0.30)" → kept as `val lowWrBypass = false` | FinalDecisionGate.kt:2456 |
| Retired compatibility modes | V5.0.8326 "retired compatibility mode" left in place at two doors | FinalDecisionGate.kt:777, 1209 |

### 2.5 Representative "temporary"/"band-aid" markers

`BotService.kt:5256` "GlobalScope band-aid that masked the cause"; `BotService.kt:24688` "forcing the watchdog band-aid";
`Executor.kt:2187, 4618` "operator hotfix spec/§13"; `GlobalTradeRegistry.kt:1108`; 119 in total — almost all describe a fix
that was layered beside, not instead of, the previous one.

## 3. Duplicates (same concept, several implementations)

Survivor = the implementation that should remain; everything else delegates to it or is deleted.

### 3.1 Chase / "buying the top" — **8 entry-timing definitions + 4 slippage-chase definitions**
* Entry-timing refusals: `truth/LiveEdgeGate7877.chasing8065` (runup ≥80 % & ≥0.88 of peak, or +100 %/5 m), `chasingRunner8468` (≥40 % & ≥0.92, or +60 %/5 m), `runnerChaseApplies8468`; `v3/scoring/MoonshotTraderAI.nearHighRefused8555` (≥0.92 & >40 % — a copy of 8468); `truth/LaneAuthority8577` `memeBuysTheHigh8582` (≥0.97 & ≥5 %) and stage door `memeEntryRefusal8626`/8706; `truth/LaunchChase7280.isChase` (≥3× within 180 s); `truth/NetEdge8030.overextended8054`; `market/LaneHunter7297.chasePenalty7797` (h1 move bands); `cortex/CortexGradeBook8144.markLateEntry8153`.
* Fill-vs-decision "chase" (slippage): `truth/EntryChase7961.chasePct7961` + `lanePenaltyPct7961`, `truth/ShadowFill8364.chasePct8364`/`laneChasePct8364` (delegates), `Exits8363 ScalpStop8363.chasePct8363` (delegates), `truth/EntryEconomics8329.chaseCapPct8329` (per-setup cap).
* **Survivor:** entry timing → one `LiveEdgeGate7877` runner bound driven by LaneAuthority's per-stage record (8706); slippage → `EntryChase7961`.

### 3.2 Stop sources — **≥15**
`cortex/StopAuthority7887.stopFor8418`/`stopPctFor`/`laneStopMag8029`; `truth/FieldManual7715.stopPct8392`/`laneStopPct8392`; `Exits8363 ScalpStop8363.stopPct8363`; `RunnerDoctrine8584.stopFor8719`/`paperStopLevel8584`; `v3/scoring/MoonshotTraderAI.stopFor`; `ExitThresholds8418.catastropheOf8418`/`catastropheMag8418`; `ExitDoctrine8169.catastropheMag8169`/`rapidCatastrophePct8169`; `BotService.getCatastropheThreshold`; `truth/CostBandExit8536.stopFloorPct`; `truth/ShadowAlign8366.liveStopLevel8366`; `getFluidStopLoss` ×5 (BlueChip, DipHunter, FluidLearningAI, Quality, ShitCoin); `FluidLearningAI.getMarketsStopLossPct`; `BehaviorAI.getStopLossModPct`; `truth/ExitRegret7752.stopMult7877/7948/stopMultFor`; `truth/MoonshotHoldProfileRegistry6415.trailStopPctFromPeak`; `TrailingStopManager.calculateAdaptiveStop` (dead); `StopSlip8327`/`StopSlip8756`.
**Survivor:** `StopAuthority7887.stopFor8418` (already returns the binding authority name).

### 3.3 Cost models — **≥10**
`truth/FieldManual7715.allInCostPct`/`allInCostPct8350`/`roundTripCostPct7766`/`labelCostPct8534`; `truth/NetEdge8030.costPct8030` (composes `CostTrim8151.componentsPct8151` + `RoundTrip8418` + `CostLedger7962`); `CostTrim8151.roundTripPct8151`/`breakEvenMovePct8418`/`CostMeter8151.roundTripFixedSol8151`; `truth/CostLedger7962.roundTripCostPct7962` (measured); `truth/LiveRiskPolicy7807.roundTripCostPct` (own fallback `BASE+SLIP+MAX_IMPACT×0.25`, `6.0` on error); `truth/LaneShadowProof7307.roundTripCostPct`; `Exits8363 GreenFloor8363.costPct8363` (60 s cache of NetEdge); `RunnerExitLive8136.sellCostPct8136`; `perps/crypto/MultiChainMemeLane7962.roundTripCostPct7962`; `perps/crypto/CryptoBridgeAdapter.roundTripCostFrac7316`; `LiveRestoreExecutionPolicy.breakEvenCheck`.
**Survivor:** `NetEdge8030.costPct8030` (it is the only one that sees measured receipts); FieldManual becomes its pure helper.

### 3.4 Age / birth readers — **≥20**
`truth/CanonicalTokenBirthTime7440.launchAgeMs7767` (canonical, used by 50 files) wrapped again by `truth/LaunchBirth8169.launchBirth8169`, `LaneAgeLiquidityTruth8140.chainAgeMinutes8140`/`launchAgeMinutesOr8140`, `LaneAgeLiquidityScope8140.laneAgeMinutes8140`, `IntakeFirstSight8317.chainAgeMs8317`, `FreshLaunchIntake8303.earliestBirth8303`/`createBirthMs8303`, `truth/LaunchPhaseAuthority7401.birthRead8535`, `truth/PriceShapeBars8287.ageMin8287`, `truth/TradePlan7739.ageMin8317`, `truth/HeliusSwapCandles7819.ageMin8317`, `truth/MarketCohort8701.ageMin8717`, `HydrationReeval8343.ageMinutes`, `truth/MoonshotFreshLaunchAdmission7044.ageMinutes`, `market/MarketSweep7297.chainBirthHours8418`, `cortex/CortexVotersWide8004.ageFromBirth8527`, `truth/LaunchHour8628.birth`; independent readers `truth/PoolCreationTime7385.createdAtMs/ageSecs`, `network/PumpCurveKeys7269.createdAtMs7280`, `CanonicalFeaturesBuilder.estimateTokenAgeAtEntryMs`, `FluidLearningAI.getFreshTokenAgeMinutes`, `truth/FreshLaunchSelector7737.unresolvedLaunchAgeMs7738`; and raw `now − addedToWatchlistAt` in BotService (21870, 25609, 26141, 26768, 27929, 29861) and `CurveShapeFeed8236.kt:44`.
**Survivor:** `CanonicalTokenBirthTime7440.launchAgeMs7767` returning `Long?` (null = unknown, never the watchlist time).

### 3.5 "Proven" statistical bars — **269 constants in 154 files**
Examples: `CortexTiming7900 MIN_N=40, PROOF_PCT=2.0`; `CortexExit7897 MIN_N=30, PROOF_PCT=2.0`; `CortexScoreboard7885 MIN_N_PAPER=20, MIN_N_LIVE=40, PROOF_MARGIN_PCT=2.0`; `LaneCheatSheet8323 FAVOUR_MIN_N=20, AVOID_MIN_N=12, PROVEN_N=30, HIVE_FAVOUR_MIN_N=100`; `StopLearner8040 MIN_N=100`; `CortexCalibration7901 MIN_N=100, EARLY_MIN_N=20, ISO_MIN_N=60`; `RunnerGrab7967 PROVEN_MIN_N=20, PROVEN_MARGIN_PCT=20`; `LanePlaybook7907 NONE_PROOF_N=40, MEASURED_MIN_N=5, COLD_MIN_N=12, SEED_MIN_N=10`; `MultiChainMemeLane7962 MIN_N_LIVE=20`; `StrategyTrustAI MIN_TRADES_FOR_TRUST=5`. Proof predicates: `LiveEdgeGate7877.cellProven8060/kindProven8063/cellWinsProven8094/cellOrKindProven8094`, `TailHunter7996.proven/tailProven7996/laneRuleTailProven8097/refusalTailProven8094`, `EvidenceHold8336.explorationProven8347/laneWideProven8392/shadowProven8344`, `ManagedBands8719.proven8719`, `LaunchReplay8744.proven8748`, `BaseFamily8754.proven8754`, `LaneTrigger8468.proven8468`, `MemeStrategies8546.measuredProven8692/followProven8756`, `MarketCohort8701.measuredProven8701`, `StrategyLab8755.baseProven8755/launchProven8755`, `SignalSourceProof7291.isProven`, `FinalDecisionGate.exactSetupProven8080/toxicExemptProven8080/laneMixExemptProven8080`. Only one confidence bound exists: `cortex/CortexWalkForward8144.wilsonLower8144` (plus `HeliusCadence8361.lcbOf8362`, `ExploreShadow8347.lowerBound8364`).
**Survivor:** a single `ProofBar` (n, Wilson/t lower bound vs cost) built on `CortexWalkForward8144.wilsonLower8144`, with per-use thresholds named in one table.

### 3.6 Lane-name normalisers — **≥25**
`truth/CanonicalLaneIdentity6506.canonical`/`desk8392`/`expressAlias8392` (canonical, 129 call sites) and private re-implementations: `AdaptiveLaneReproof6684.canonicalLane`, `ExecutableOpenGate.canonicalLane`, `ExpertTraderKnowledge7813.canonicalLane`, `LiveGrowthDoctrine.canonicalLane`, `market/SpecialistCandidateBooks7803.canonicalLane` (BLUE_CHIP→BLUECHIP, SHITCOIN_EXPRESS→EXPRESS), `truth/BleederLaneProbation6747.canonicalLane`, `truth/LiveRiskPolicy7807.canonicalLane`, `LiveLayerGateRelaxer.canonicalLaneKey`, `CellAllocator7962.canonLane`, `LaneAutoPauseGuard.canonLane`, `truth/EpisodeTrace8395.canonLane`, `AICrossTalk.canonLane7329`, `truth/LiveAdmissionProof8290.canonLane8290`, `truth/AdaptiveVetoConsensusAuthority6728.normaliseLane`, `RuntimeConfigOverlay.normalizeLane` (→EXPRESS), `UnifiedExitPolicyHead.normalizeLane`, `UnifiedPolicyHead.normalizeLane` (blank→STANDARD), `truth/CanonicalIdentityModel6464.normalizeLane`, `truth/LosingStreakReflex6439.normalizeLane`, `truth/CausalFeedbackAuthority6715.normLane`, `truth/LaneCapitalFairness6732.normLane`, `learning/LanePolicy.laneKey`, `truth/LearningData8332.canonicalLane8332`, `BleederMemoryRouter.canon`, `Cortex7885.canon`.
**Survivor:** `CanonicalLaneIdentity6506.canonical` with the EXPRESS alias folded in.

### 3.7 Price / quote freshness — **≈40 windows, 10 s … 10 min**
Marks: `CanonicalPriceMark6522 MARK_FRESHNESS_WINDOW_MS_6739=120 s`, `Cortex7885 MARK_STALE_MS=180 s`, `CortexExit7897 120 s`, `CortexTiming7900 90 s`, `CrossAssetCortex7931 120 s`, `FirstSight8026 30 s`, `MissingMarkExitVeto6835 60 s`, `ExitRegret7752 120 s`, `ForwardReturnLabeler7731 45 s / 90 s / 20 s`, `MemePaperTrader8544 20 s`, `PositionDisplayTruth8078 30 s`, `HeldHotMarkAuthority7419 60 s / 20 s`, `CapitalDrawdown7948 120 s`, `CrossAssetMarkRouter6530 60 s`, `DeepRedConfirm8116 30 s`, `ExecutableEntryFloor8521 20 s`, `EvidenceShadow8344 45 s`, `LearningData8332 60 s`, `MemeChokes7951 10 min`, `LiveCanonicalRecovery6686 10 min`, `OpenPnlSanity 120 s`, `MultiChainMemeLane7962 45 s`, `CryptoAltTrader 60 s`, `PerpsMarketDataFetcher 60 s`. Quotes: `StalePriceFillGate6427 buy 20 s / risk 45 s / terminal 15 s`, `BuyRouteSafety8522 30 s`, `HeldExitQuote8189 20 s`, `SellLanding8368 10 s`, `ExpressExitPriceIntegrity6831 90 s`, `FieldManual7715 180 s`. Plus 10 `fresh####` functions (7968…8521). And `Executor.kt:65` inlines its own `-5 s..120 s`.
**Survivor:** `CanonicalPriceMark6522` (marks, by purpose) and `StalePriceFillGate6427` (quotes, by intent).

### 3.8 Fill-lot / position ledgers
`FillLotLedger6344`, `truth/FillLotLedger6386`, `truth/FillLotLedger6504` (all live); `PositionStateLedger6427` vs `6454`; `PositionIdentity6395` vs `6411`; `CanonicalEntryAuthority6540` vs `6551`. **Survivor:** the highest version of each (6504, 6454, 6411, 6551).

### 3.9 Frozen-token predicate
`WalletTokenAccountStateAuthority7253.isFrozen` wrapped with opposite fallbacks (item 3). **Survivor:** `sell/FrozenHoldingGuard8136.isFrozen8136` with tri-state result.

### 3.10 Crypto brain vs meme brain (copy-paste)
`perps/crypto/brain/CryptoRugMintBlacklist`↔`RugMintBlacklist` (92/92 lines, `init`/`restore` structural clones), `CryptoLaneTimeoutGate`↔`LaneTimeoutGate`, `CryptoLivePauseButton`↔`LivePauseButton`, `CryptoScannerLaneBridge`↔`ScannerLaneBridge`, `CryptoFluidLearning`↔`FluidLearning`, `CryptoLosingPatternMemory`↔`LosingPatternMemory`, `CryptoTacticSwitcher`↔`learning/TacticSwitcher`, `CryptoCanonicalLearning`↔`CanonicalLearning`, `CryptoLaneExitTuner`↔`learning/LaneExitTuner`. **Survivor:** the engine/ versions, keyed by asset class.

### 3.11 Commodities / Forex / Metals / TokenizedStock traders
≥20 identical function bodies across `perps/CommoditiesTrader.kt` (1,411), `ForexTrader.kt` (1,488), `MetalsTrader.kt` (1,377) — see clone table. **Survivor:** a shared base class; `TokenizedStockTrader` shares `stop`, `setBalance`, `isHealthy`.

### 3.12 Sizing multiplier stacks
`Executor.kt:3486-3563` (12 `*SizeMult != 1.0` terms), `SmartSizer.calculate` (874-881, 8 terms), `FinalDecisionGate.evaluate` (tiered*Mult 5074-5261, metaCog/learnedBucket/divergence), `OrderSizeResolver6441.resolve`, `v3/sizing/SmartSizerV3.compute`, `CanonicalSizingBridge6532.resolve`, `FinalDecisionGate.resolveExecutableSize7835`. **Survivor:** `OrderSizeResolver6441.resolve` as the only place multipliers compose.

### 3.13 Status / formatting helpers
`statusLine` defined in 321 files, `fmt` in 56, `snapshot` in 112; exact clones such as `cortex/CortexTailEv8324.fmt` = `truth/CandidateAdmission8136.fmt` = `truth/StrategyRank8153.fmt`; `parseLong` ×3 (CollectiveLearning, DataPipeline, JupiterApi); `exportJson`/`importJson` ×5 (SuperEvidence*/SuperExpert*); `attach` ×3 (FillLotLedger6504, IdempotencyKeyStore6437, PortfolioStore6405); `buyPhase` ×6; `evictGhost` ×6 v3 traders; `getWinRatePct` ×4; `getActivePositionsForMode` ×5. **Survivor:** one `util/Fmt.kt` + one persisted-store base.

### 3.14 Operator digests
48 `engine/Operator*Digest*.kt` / `*Sentinel*` files including `OperatorLongTailMechanismDigest` … `13` whose `status` bodies are exact clones (12-way). **Survivor:** one table-driven digest, test-side.

### 3.15 Largest exact / structural clone groups (cross-file, generated)

| Copies | Lines each | Function | Locations |
|---|---|---|---|
| 2 | 45 | `getTopModes` | `collective/CollectiveLearning.kt:2790`; `collective/CollectiveLearning.kt:2836` |
| 12 | 4 | `status` | `engine/OperatorLongTailMechanismDigest10.kt:22`; `engine/OperatorLongTailMechanismDigest11.kt:25`; `engine/OperatorLongTailMechanismDigest12.kt:21`; `engine/OperatorLongTailMechanismDigest13.kt:14`; `engine/OperatorLongTailMechanismDigest2.kt:59` |
| 3 | 15 | `exportJson` | `engine/SuperEvidenceContextTrust7656.kt:78`; `engine/SuperExpertCoalition7661.kt:85`; `engine/SuperExpertTrust7660.kt:50` |
| 3 | 13 | `attach` | `engine/truth/FillLotLedger6504.kt:59`; `engine/truth/IdempotencyKeyStore6437.kt:56`; `engine/truth/PortfolioStore6405.kt:66` |
| 6 | 5 | `init` | `perps/PerpsTradeHeatmap.kt:328`; `v3/scoring/BlueChipTraderAI.kt:122`; `v3/scoring/ProjectSniperAI.kt:115`; `v3/scoring/QualityTraderAI.kt:124`; `v3/scoring/ShitCoinExpress.kt:117` |
| 3 | 12 | `parseLong` | `collective/CollectiveLearning.kt:3529`; `engine/DataPipeline.kt:666`; `network/JupiterApi.kt:1228` |
| 3 | 12 | `importJson` | `engine/SuperEvidenceContextTrust7656.kt:94`; `engine/SuperExpertCoalition7661.kt:99`; `engine/SuperExpertTrust7660.kt:64` |
| 3 | 12 | `monitorPositions` | `perps/CommoditiesTrader.kt:857`; `perps/ForexTrader.kt:902`; `perps/MetalsTrader.kt:830` |
| 2 | 23 | `fetchFmp` | `perps/PriceAggregator.kt:1270`; `perps/PriceAggregator.kt:1294` |
| 3 | 11 | `liveWalletSol7212` | `perps/CommoditiesTrader.kt:1397`; `perps/ForexTrader.kt:1474`; `perps/MetalsTrader.kt:1363` |
| 4 | 7 | `init` | `engine/LaneTimeoutGate.kt:43`; `engine/ScannerLaneBridge.kt:45`; `perps/crypto/brain/CryptoLaneTimeoutGate.kt:34`; `perps/crypto/brain/CryptoScannerLaneBridge.kt:34` |
| 2 | 20 | `computeLiveTerminalLeaderboard` | `engine/StrategyTelemetry.kt:435`; `engine/StrategyTelemetry.kt:520` |
| 3 | 10 | `initContext` | `perps/CommoditiesTrader.kt:284`; `perps/ForexTrader.kt:302`; `perps/MetalsTrader.kt:280` |
| 3 | 10 | `saveState` | `perps/CommoditiesTrader.kt:1225`; `perps/ForexTrader.kt:1281`; `perps/MetalsTrader.kt:1192` |
| 3 | 10 | `loadState` | `perps/CommoditiesTrader.kt:1247`; `perps/ForexTrader.kt:1303`; `perps/MetalsTrader.kt:1215` |
| 6 | 4 | `evictGhost` | `v3/scoring/BlueChipTraderAI.kt:304`; `v3/scoring/CashGenerationAI.kt:1285`; `v3/scoring/ManipulatedTraderAI.kt:512`; `v3/scoring/MoonshotTraderAI.kt:1320`; `v3/scoring/QualityTraderAI.kt:877` |
| 4 | 6 | `stop` | `perps/CommoditiesTrader.kt:295`; `perps/ForexTrader.kt:313`; `perps/MetalsTrader.kt:291`; `perps/TokenizedStockTrader.kt:702` |
| 2 | 17 | `restoreAll` | `engine/LaneTimeoutGate.kt:51`; `perps/crypto/brain/CryptoLaneTimeoutGate.kt:42` |
| 2 | 17 | `save` | `v3/scoring/BlueChipTraderAI.kt:144`; `v3/scoring/ShitCoinTraderAI.kt:197` |
| 3 | 8 | `trueHardTicketKill` | `engine/ExecutableOpenGate.kt:1216`; `engine/ExecutionAttemptLease.kt:61`; `engine/PositionCloseLedger.kt:320` |
| 3 | 8 | `init` | `engine/LivePauseButton.kt:54`; `engine/MoonshotAdaptiveGate.kt:68`; `perps/crypto/brain/CryptoLivePauseButton.kt:35` |
| 2 | 16 | `restore` | `engine/LivePauseButton.kt:63`; `perps/crypto/brain/CryptoLivePauseButton.kt:44` |
| 2 | 16 | `recordOutcome` | `engine/LivePauseButton.kt:92`; `perps/crypto/brain/CryptoLivePauseButton.kt:72` |
| 2 | 16 | `restoreAll` | `engine/ScannerLaneBridge.kt:53`; `perps/crypto/brain/CryptoScannerLaneBridge.kt:42` |
| 3 | 8 | `closePositionManual` | `perps/CommoditiesTrader.kt:1203`; `perps/ForexTrader.kt:1259`; `perps/MetalsTrader.kt:1170` |

*Structural (identifiers abstracted), ≥6 lines, cross-file:*

| Copies | Lines | Function | Locations |
|---|---|---|---|
| 3 | 33 | `monitorSinglePosition` | `perps/CommoditiesTrader.kt:870`; `perps/ForexTrader.kt:913`; `perps/MetalsTrader.kt:843` |
| 5 | 15 | `exportJson` | `engine/SuperEvidenceContextTrust7656.kt:78`; `engine/SuperEvidenceInteraction7653.kt:132`; `engine/SuperEvidenceReliability7652.kt:61`; `engine/SuperExpertCoalition7661.kt:85`; `engine/SuperExpertTrust7660.kt:50` |
| 2 | 59 | `start` | `perps/CommoditiesTrader.kt:222`; `perps/MetalsTrader.kt:218` |
| 5 | 12 | `importJson` | `engine/SuperEvidenceContextTrust7656.kt:94`; `engine/SuperEvidenceInteraction7653.kt:148`; `engine/SuperEvidenceReliability7652.kt:77`; `engine/SuperExpertCoalition7661.kt:99`; `engine/SuperExpertTrust7660.kt:64` |
| 2 | 43 | `setLiveMode` | `perps/ForexTrader.kt:1351`; `perps/MetalsTrader.kt:1263` |
| 2 | 42 | `persistTradeToTurso` | `perps/ForexTrader.kt:1202`; `perps/MetalsTrader.kt:1118` |
| 7 | 7 | `getSizingMultiplier` | `perps/crypto/brain/CryptoBehavior.kt:96`; `perps/crypto/brain/CryptoFluidLearning.kt:93`; `perps/crypto/brain/CryptoFluidLearning.kt:101`; `perps/crypto/brain/CryptoFluidLearning.kt:109`; `perps/crypto/brain/CryptoFluidLearning.kt:117` |
| 6 | 7 | `init` | `engine/LaneTimeoutGate.kt:43`; `engine/RugMintBlacklist.kt:28`; `engine/ScannerLaneBridge.kt:45`; `perps/crypto/brain/CryptoLaneTimeoutGate.kt:34`; `perps/crypto/brain/CryptoRugMintBlacklist.kt:31` |
| 2 | 27 | `executeLiveTradeAtSize` | `perps/ForexTrader.kt:872`; `perps/MetalsTrader.kt:799` |
| 3 | 13 | `attach` | `engine/truth/FillLotLedger6504.kt:59`; `engine/truth/IdempotencyKeyStore6437.kt:56`; `engine/truth/PortfolioStore6405.kt:66` |
| 3 | 12 | `parseLong` | `collective/CollectiveLearning.kt:3529`; `engine/DataPipeline.kt:666`; `network/JupiterApi.kt:1228` |
| 3 | 12 | `monitorPositions` | `perps/CommoditiesTrader.kt:857`; `perps/ForexTrader.kt:902`; `perps/MetalsTrader.kt:830` |
| 5 | 6 | `getActivePositionsForMode` | `v3/scoring/BlueChipTraderAI.kt:346`; `v3/scoring/CashGenerationAI.kt:1245`; `v3/scoring/CashGenerationAI.kt:1767`; `v3/scoring/MoonshotTraderAI.kt:1312`; `v3/scoring/ShitCoinTraderAI.kt:429` |
| 3 | 11 | `liveWalletSol7212` | `perps/CommoditiesTrader.kt:1397`; `perps/ForexTrader.kt:1474`; `perps/MetalsTrader.kt:1363` |
| 4 | 7 | `persist` | `engine/LaneTimeoutGate.kt:69`; `engine/ScannerLaneBridge.kt:70`; `perps/crypto/brain/CryptoLaneTimeoutGate.kt:60`; `perps/crypto/brain/CryptoScannerLaneBridge.kt:59` |

## 4. Size / complexity hotspots

### 4.1 Largest files

| Lines | Code | Comment | Swallowed catches | /kloc | File |
|---|---|---|---|---|---|
| 35,250 | 21,208 | 12,944 | 2048 | 58 | `engine/BotService.kt` |
| 32,763 | 22,182 | 9,609 | 1835 | 56 | `engine/Executor.kt` |
| 14,072 | 9,851 | 2,912 | 425 | 30 | `ui/MainActivity.kt` |
| 6,617 | 4,452 | 1,897 | 223 | 34 | `engine/FinalDecisionGate.kt` |
| 6,078 | 4,231 | 1,608 | 283 | 47 | `perps/CryptoAltTrader.kt` |
| 5,830 | 4,154 | 1,366 | 129 | 22 | `engine/cortex/Cortex7885.kt` |
| 5,056 | 3,455 | 1,434 | 139 | 27 | `engine/PipelineHealthCollector.kt` |
| 4,886 | 3,595 | 912 | 109 | 22 | `engine/SolanaMarketScanner.kt` |
| 4,564 | 3,707 | 506 | 39 | 9 | `ui/CryptoAltActivity.kt` |
| 4,521 | 2,994 | 1,440 | 181 | 40 | `engine/ExecutableOpenGate.kt` |
| 4,020 | 2,552 | 1,182 | 24 | 6 | `engine/LifecycleStrategy.kt` |
| 3,559 | 2,738 | 183 | 46 | 13 | `collective/CollectiveLearning.kt` |
| 3,550 | 2,408 | 981 | 166 | 47 | `engine/truth/ForwardReturnLabeler7731.kt` |
| 3,263 | 2,212 | 875 | 110 | 34 | `engine/TradeHistoryStore.kt` |
| 3,183 | 1,809 | 1,176 | 34 | 11 | `v3/scoring/EducationSubLayerAI.kt` |
| 3,164 | 2,469 | 425 | 93 | 29 | `ui/MultiAssetActivity.kt` |
| 2,885 | 1,377 | 1,385 | 20 | 7 | `v3/scoring/FluidLearningAI.kt` |
| 2,713 | 1,855 | 718 | 127 | 47 | `engine/cortex/LanePlaybook7907.kt` |
| 2,475 | 1,651 | 744 | 91 | 37 | `engine/truth/CanonicalPositionAuthority6441.kt` |
| 2,436 | 1,627 | 702 | 83 | 34 | `network/SolanaWallet.kt` |

Top 20 files hold 23.6 % of all lines. BotService.kt + Executor.kt alone = 68,013 lines (10.7 %).

### 4.2 Largest functions

| Lines | Branch points | Swallowed catches | Function | Location |
|---|---|---|---|---|
| 7,698 | 1450 | 297 | `BotService.processTokenCycle` | `engine/BotService.kt:26064` |
| 4,727 | 1014 | 154 | `FinalDecisionGate.evaluate` | `engine/FinalDecisionGate.kt:1670` |
| 3,635 | 727 | 269 | `Executor.liveBuy` | `engine/Executor.kt:18819` |
| 3,045 | 468 | 164 | `BotService.startBot` | `engine/BotService.kt:6733` |
| 2,987 | 480 | 98 | `PipelineHealthCollector.dumpText` | `engine/PipelineHealthCollector.kt:1765` |
| 2,939 | 546 | 159 | `Executor.liveSellInScope7314` | `engine/Executor.kt:27764` |
| 2,438 | 368 | 137 | `BotService.botLoop` | `engine/BotService.kt:20064` |
| 1,867 | 395 | 52 | `MainActivity.updateUi` | `ui/MainActivity.kt:3337` |
| 1,669 | 313 | 122 | `Executor.paperBuy` | `engine/Executor.kt:15887` |
| 1,578 | 275 | 110 | `Executor.paperSell` | `engine/Executor.kt:26066` |
| 1,511 | 370 | 76 | `ExecutableOpenGate.canOpenExecutablePositionInternal` | `engine/ExecutableOpenGate.kt:3009` |
| 1,503 | 439 | 83 | `Executor.recordTrade` | `engine/Executor.kt:4612` |
| 1,482 | 336 | 77 | `Executor.runManageOnly` | `engine/Executor.kt:8679` |
| 1,474 | 208 | 131 | `BotService.onCreate` | `engine/BotService.kt:3502` |
| 1,426 | 334 | 64 | `BotService.admitProtectedMemeIntake` | `engine/BotService.kt:16181` |
| 1,183 | 203 | 58 | `BotService.rapidStopLossMonitor` | `engine/BotService.kt:11441` |
| 1,082 | 199 | 56 | `BotService.openPositionTickLoop` | `engine/BotService.kt:12660` |
| 1,020 | 158 | 42 | `Executor.executeProfitLockSell` | `engine/Executor.kt:7507` |
| 957 | 150 | 24 | `ShitCoinTraderAI.evaluate` | `v3/scoring/ShitCoinTraderAI.kt:707` |
| 941 | 200 | 29 | `MainActivity.renderOpenPositions` | `ui/MainActivity.kt:5767` |
| 940 | 220 | 18 | `LifecycleStrategy.evaluate` | `engine/LifecycleStrategy.kt:121` |
| 907 | 131 | 41 | `PredictiveEntryOracle6915.evaluate` | `engine/truth/PredictiveEntryOracle6915.kt:790` |
| 898 | 169 | 29 | `Executor.riskCheck` | `engine/Executor.kt:11324` |
| 854 | 175 | 18 | `TokenSafetyChecker.check` | `engine/TokenSafetyChecker.kt:354` |
| 842 | 136 | 11 | `SmartSizer.calculate` | `engine/SmartSizer.kt:98` |
| 828 | 143 | 33 | `Executor.requestPartialSellWithDelivery8527` | `engine/Executor.kt:24233` |
| 811 | 114 | 38 | `CryptoAltTrader.executeSignal` | `perps/CryptoAltTrader.kt:3352` |
| 809 | 122 | 17 | `Executor.getActualPrice` | `engine/Executor.kt:865` |
| 798 | 192 | 36 | `CryptoAltTrader.runDynamicTokenScan` | `perps/CryptoAltTrader.kt:1326` |
| 779 | 133 | 47 | `Executor.checkPartialSell` | `engine/Executor.kt:10181` |

### 4.3 Swallowed exceptions

19,953 catch blocks; 17,213 swallow (10,492 empty, 5,838 return a default value, 770 only log, 113 return/continue).
Most-swallowing functions: `BotService.processTokenCycle` 297 · `Executor.liveBuy` 269 · `BotService.startBot` 164 · `Executor.liveSellInScope7314` 159 ·
`FinalDecisionGate.evaluate` 154 · `BotService.botLoop` 137 · `BotService.onCreate` 131 · `Executor.paperBuy` 122 · `Executor.paperSell` 110 ·
`PipelineHealthCollector.dumpText` 98 · `Executor.recordTrade` 83 · `Executor.runManageOnly` 77 · `ExecutableOpenGate.canOpenExecutablePositionInternal` 76 ·
`Executor.requestSellCore7948` 62 · `BotService.rapidStopLossMonitor` 58 · `BotService.openPositionTickLoop` 56.

Classified default-returning swallows in trading files (risk keyword in the tried expression): 425 —
fail-open null-refusal 29, fail-open false-risk-flag 35, fail-open true-permission 11, numeric substitute 108, null substitute 135, fail-closed false 107.

#### Functions where a swallowed exception can silently change a trading outcome

Tables below are generated by a heuristic classifier (fallback value × risk keyword in the tried expression) and then
filtered to reachable functions; each row is a real swallow site, but a few rows are diagnostic-only (e.g. `Cortex7885.capture`,
`ExecutableOpenGate.kt:4275` inside a log string) or fail-closed on closer reading (`LiveEdgeGate7877.kt:1386`: a throwing
`vetoRefusesWinners8006` keeps the veto). Items 2-4 of the top-25 list are the hand-verified subset.

Entry / admission

| Function | Line | Fallback | Expression |
|---|---|---|---|
| `Executor.liveBuy` | `engine/Executor.kt:19223` | FAIL-OPEN(false risk-flag => allowed) | `val rugBL4134 = try { com.lifecyclebot.engine.RugMintBlacklist.isBlacklisted(ts.mint) } catch (_: Throwable) { false }` |
| `Executor.liveBuy` | `engine/Executor.kt:19298` | FAIL-OPEN(false risk-flag => allowed) | `val bridgeToxic4134 = try { !com.lifecyclebot.engine.ScannerLaneBridge.shouldRoute(srcTag4134, laneTag4134) } catch (_: Throwable) { false }` |
| `Executor.liveBuy` | `engine/Executor.kt:20065` | FAIL-OPEN(null refusal => allowed) | `} catch (_: Throwable) { null }` |
| `BotService.laneEvaluationAllowed7951` | `engine/BotService.kt:15411` | FAIL-OPEN(false risk-flag => allowed) | `val safeEnough = try { !ts.safety.isBlocked && ts.safety.hardBlockReasons.isEmpty() } catch (_: Throwable) { false }` |
| `BotService.laneEvaluationAllowed7951` | `engine/BotService.kt:15440` | FAIL-OPEN(false risk-flag => allowed) | `val safeEnough = try { !ts.safety.isBlocked && ts.safety.hardBlockReasons.isEmpty() } catch (_: Throwable) { false }` |
| `BotService.laneEvaluationAllowed7951` | `engine/BotService.kt:15507` | FAIL-OPEN(false risk-flag => allowed) | `val paused6014 = try { LaneAutoPauseGuard.isPaused(l) } catch (_: Throwable) { false }` |
| `BotService.laneEvaluationAllowed7951` | `engine/BotService.kt:15622` | FAIL-OPEN(false risk-flag => allowed) | `val laneIsPaused4598 = try { LaneAutoPauseGuard.isPaused(l) } catch (_: Throwable) { false }` |
| `BotService.selectOrderedMintsForCycle` | `engine/BotService.kt:18828` | FAIL-OPEN(false risk-flag => allowed) | `val banned = try { com.lifecyclebot.engine.BannedTokens.isBanned(mint) } catch (_: Throwable) { false }` |
| `BotService.selectOrderedMintsForCycle` | `engine/BotService.kt:18833` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `ExecutableOpenGate.canOpenExecutablePositionInternal` | `engine/ExecutableOpenGate.kt:3395` | FAIL-OPEN(null refusal => allowed) | `} catch (_: Throwable) { null }` |
| `ExecutableOpenGate.canOpenExecutablePositionInternal` | `engine/ExecutableOpenGate.kt:4167` | FAIL-OPEN(null refusal => allowed) | `} catch (_: Throwable) { null }` |
| `ExecutableOpenGate.canOpenExecutablePositionInternal` | `engine/ExecutableOpenGate.kt:4275` | FAIL-OPEN(null refusal => allowed) | `"taxonomy=$taxonomyReason6579 resolvedSize=$effectiveResolvedSize6497 minimum=$minExecutable6491 sealedSize=${try { com.lifecyclebot.engine.truth.Seal` |
| `Executor.doBuy` | `engine/Executor.kt:14504` | FAIL-OPEN(true permission) | `} catch (_: Throwable) { true }` |
| `Executor.doBuy` | `engine/Executor.kt:14608` | FAIL-OPEN(false risk-flag => allowed) | `val rugBlacklisted4133 = try { com.lifecyclebot.engine.RugMintBlacklist.isBlacklisted(ts.mint) } catch (_: Throwable) { false }` |
| `LiveEdgeGate7877.canonicalRefusal8392` | `engine/truth/LiveEdgeGate7877.kt:701` | FAIL-OPEN(null refusal => allowed) | `if (!paper) (try { OnChainDepth8038.earlyLaunchRefusal8048(ts) } catch (_: Throwable) { null })?.let { observeTail7996(ts, lane, it, nowMs); return it` |
| `LiveEdgeGate7877.canonicalRefusal8392` | `engine/truth/LiveEdgeGate7877.kt:788` | FAIL-OPEN(null refusal => allowed) | `(try { LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs) } catch (_: Throwable) { null })?.let { r8083 ->` |
| `LiveEdgeGate7877.liveRefusalCore7970` | `engine/truth/LiveEdgeGate7877.kt:1385` | FAIL-OPEN(null refusal => allowed) | `(try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenLosingNamedSetupRefusal(l8079, boundSetup) } catch (_: Throwable) { null })?.let { r ->` |
| `LiveEdgeGate7877.liveRefusalCore7970` | `engine/truth/LiveEdgeGate7877.kt:1386` | FAIL-OPEN(false risk-flag => allowed) | `val retired = try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(r, l8079) } catch (_: Throwable) { false }` |
| `LiveEdgeGate7877.liveRefusalCore7970` | `engine/truth/LiveEdgeGate7877.kt:1460` | FAIL-OPEN(null refusal => allowed) | `val shape = try { TradeShapeLearner7883.shapeRefusal(ts, l) } catch (_: Throwable) { null }` |
| `BotService.startBot` | `engine/BotService.kt:8587` | FAIL-OPEN(false risk-flag => allowed) | `val v4132_rug = try { com.lifecyclebot.engine.RugMintBlacklist.isBlacklisted(identity.mint) } catch (_: Throwable) { false }` |
| `BotService.startBot` | `engine/BotService.kt:8589` | FAIL-OPEN(false risk-flag => allowed) | `val v4132_toxicPair = try { !com.lifecyclebot.engine.ScannerLaneBridge.shouldRoute(source.name, "MEME") } catch (_: Throwable) { false }` |
| `BrainConsensusGate.evaluate` | `engine/BrainConsensusGate.kt:143` | FAIL-OPEN(false risk-flag => allowed) | `val toxicLane = try { LiveProbabilityEngine.isLaneLearnedToxic6379(lane) } catch (_: Throwable) { false }` |
| `BrainConsensusGate.evaluate` | `engine/BrainConsensusGate.kt:177` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `ExecutableOpenGate.canOpenExecutablePosition` | `engine/ExecutableOpenGate.kt:2784` | FAIL-OPEN(null refusal => allowed) | `val waveVeto = try { WaveEntryQualityGate6382.evaluate(ts, entryScore) } catch (_: Throwable) { null }` |
| `ExecutableOpenGate.canOpenExecutablePosition` | `engine/ExecutableOpenGate.kt:2870` | FAIL-OPEN(null refusal => allowed) | `val bleederReason6747b = try { com.lifecyclebot.engine.truth.BleederLaneProbation6747.evaluate(laneUpper6747b, effectiveSize6747b) } catch (_: Throwab` |
| `Executor.costExceedsEdge7162` | `engine/Executor.kt:4456` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `Cortex7885.capture` | `engine/cortex/Cortex7885.kt:1275` | FAIL-OPEN(null refusal => allowed) | `val ctxCold8178 = try { decisionCtx8144(ts, canon(labelLane), admitted, reason, incumbent8506) } catch (_: Throwable) { null }` |
| `Cortex7885.capture` | `engine/cortex/Cortex7885.kt:1289` | FAIL-OPEN(null refusal => allowed) | `val ctx8174 = try { decisionCtx8144(ts, a8174.lane, admitted, reason, incumbent8506) } catch (_: Throwable) { null }` |
| `BotService.laneQualifiedBuyDecisionCore` | `engine/BotService.kt:14417` | FAIL-OPEN(false risk-flag => allowed) | `val manipLaneQuarantined6011 = try { LaneAutoPauseGuard.isPaused("MANIPULATED") } catch (_: Throwable) { false }` |
| `BotService.shouldRunBuyLaneForCycle` | `engine/BotService.kt:15060` | FAIL-OPEN(null refusal => allowed) | `(try { SpecialistElection8136.gateReasonOf8153(ts.mint, lane) } catch (_: Throwable) { null }) ?: "LANE_CYCLE_NOT_ALLOWED")` |
| `ExecutionRouteGuard.requireLiveRoute` | `engine/ExecutionRouteGuard.kt:65` | FAIL-OPEN(false risk-flag => allowed) | `val safetyPending = try { ts.safety.rugcheckScore < 0 } catch (_: Throwable) { false }` |
| `Executor.liveBuyDeferred` | `engine/Executor.kt:14061` | FAIL-OPEN(null refusal => allowed) | `val taxonomy = try { RejectTaxonomy.classify(reason, null) } catch (_: Throwable) { null }` |
| `Executor.paperBuy` | `engine/Executor.kt:16153` | FAIL-OPEN(null refusal => allowed) | `} catch (_: Throwable) { null }` |
| `FinalDecisionGate.edgeVetoRetired7985` | `engine/FinalDecisionGate.kt:757` | FAIL-OPEN(false risk-flag => allowed) | `try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006("EDGE_VETO_ACTIVE", lane.orEmpty()) } catch (_: Throwable) { false }` |
| `FinalDecisionGate.specialistAdvisoryBlock7845` | `engine/FinalDecisionGate.kt:1038` | FAIL-OPEN(null refusal => allowed) | `} catch (_: Throwable) { null }` |
| `FinalDecisionGate.consensusVetoRetired8690` | `engine/FinalDecisionGate.kt:1668` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false })` |
| `PreTradeHardGate.requireLiveBuyAllowed` | `engine/PreTradeHardGate.kt:189` | FAIL-OPEN(null refusal => allowed) | `(try { RebuyLockout8019.refusal8019(mint, now) } catch (_: Throwable) { null })?.let { return block(ts, it, "site=$callSite") }` |
| `Cortex7885.shadowInvertedRefusal8310` | `engine/cortex/Cortex7885.kt:4211` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `Cortex7885.entryRefusal` | `engine/cortex/Cortex7885.kt:4255` | FAIL-OPEN(null refusal => allowed) | `?: try { CortexTiming7900.waitRefusal(a) } catch (_: Throwable) { null }` |
| `Cortex7885.overrulesEdgeRefusal` | `engine/cortex/Cortex7885.kt:4318` | FAIL-OPEN(null refusal => allowed) | `(try { LanePlaybook7907.provenLosingSetupRefusal8079(ts, a.lane) } catch (_: Throwable) { null }) != null \|\|` |
| `CandidateAdmission8136.candidateFor8136` | `engine/truth/CandidateAdmission8136.kt:1797` | FAIL-OPEN(null refusal => allowed) | `(try { com.lifecyclebot.engine.PreTradeHardGate.holderBlock8684(ts, nowMs) } catch (_: Throwable) { null })` |
| `FdgGate8365.demoteNow8365` | `engine/truth/FdgGate8365.kt:198` | FAIL-OPEN(false risk-flag => allowed) | `((try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRetiresAt100_8365(reason, l) } catch (_: Throwable) { false }) \|\|` |
| `LiveEdgeGate7877.verdictFor` | `engine/truth/LiveEdgeGate7877.kt:200` | FAIL-OPEN(null refusal => allowed) | `catch (_: Throwable) { null }?.let { return Verdict(false, Source.CELL, 0.0, it) }` |
| `LiveEdgeGate7877.liveRefusal` | `engine/truth/LiveEdgeGate7877.kt:541` | FAIL-OPEN(null refusal => allowed) | `val niche = if (paper \|\| laneWide) null else shadowAdvisoryOnLive8683(l, try { ExploreShadow8347.nicheRefusal8351(ts, l, EvidenceHold8336.startMs833` |
| `LiveEdgeGate7877.watchFirst7994` | `engine/truth/LiveEdgeGate7877.kt:1289` | FAIL-OPEN(false risk-flag => allowed) | `if (try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(why, l) } catch (_: Throwable) { false }) {` |

Exit / sell / frozen

| Function | Line | Fallback | Expression |
|---|---|---|---|
| `Executor.requestSellCore7948` | `engine/Executor.kt:23741` | FAIL-OPEN(false risk-flag => allowed) | `if (try { FrozenHeldUnsellableLive8141.refusesSell8141(ts, reason) } catch (_: Throwable) { false }) return SellResult.WAITING_BALANCE_PROOF` |
| `Executor.requestSellCore7948` | `engine/Executor.kt:23763` | FAIL-OPEN(false risk-flag => allowed) | `if (try { BasisBreak8019.refuses8019(ts, reason, getActualPrice(ts)) } catch (_: Throwable) { false }) {` |
| `Executor.requestSellCore7948` | `engine/Executor.kt:23922` | FAIL-OPEN(null refusal => allowed) | `val guard = try { PaperPositionCloseAuthority.preSellGuard("PAPER", ts.mint, ts.symbol, requestReason) } catch (_: Throwable) { null }` |
| `Executor.runManageOnly` | `engine/Executor.kt:9170` | FAIL-OPEN(true permission) | `} catch (_: Throwable) { true }` |
| `Executor.runManageOnly` | `engine/Executor.kt:10005` | FAIL-OPEN(false risk-flag => allowed) | `val reentryBlocked7714 = try { com.lifecyclebot.engine.sell.ExitProviderHealth.reentryBlockedNow(ts.mint) } catch (_: Throwable) { false }` |
| `BotService.rapidStopLossMonitor` | `engine/BotService.kt:11601` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `Executor.getActualPrice` | `engine/Executor.kt:1337` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `StopLatencyWiring8153.hotPathBalances8153` | `engine/sell/StopLatencyWiring8153.kt:171` | FAIL-OPEN(true permission) | `if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { true }) return null` |
| `StopLatencyWiring8153.hotPathBalances8153` | `engine/sell/StopLatencyWiring8153.kt:177` | FAIL-OPEN(true permission) | `if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { true }) return null` |
| `Executor.isNonRouteSellWait` | `engine/Executor.kt:25069` | FAIL-OPEN(false risk-flag => allowed) | `} catch (_: Throwable) { false }` |
| `Executor.frozenByIssuer8122` | `engine/Executor.kt:27714` | FAIL-OPEN(false risk-flag => allowed) | `val frozen = try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(ts.mint) } catch (_: Throwable) { false }` |
| `Executor.frozenHandOff8122` | `engine/Executor.kt:27729` | FAIL-OPEN(false risk-flag => allowed) | `if (try { FrozenHeldUnsellableLive8141.settle8141(ts, reason) } catch (_: Throwable) { false }) return SellResult.WAITING_BALANCE_PROOF` |
| `StopBroadcast8363.eligible8363` | `engine/Exits8363.kt:212` | FAIL-OPEN(true permission) | `if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(ts.mint) } catch (_: Throwable) { true }) return null` |
| `RunnerExitLive8136.budget8136` | `engine/RunnerExitLive8136.kt:49` | FAIL-OPEN(true permission) | `return if (try { ExitTruthArbiterLive8144.admitTranche8144(ts, reason) } catch (_: Throwable) { true }) allowed8144 else 0.0` |
| `RunnerExitLive8136.recoveryTranche8136` | `engine/RunnerExitLive8136.kt:128` | FAIL-OPEN(true permission) | `if (fraction > 0.0 && !(try { ExitTruthArbiterLive8144.admitTranche8144(ts, RECOVERY_TRANCHE_8144) } catch (_: Throwable) { true })) return 0.0` |
| `BalanceProofPoller.onZeroRead7733` | `engine/sell/BalanceProofPoller.kt:309` | FAIL-OPEN(false risk-flag => allowed) | `if (try { com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.heldUnproven8144(entry.mint) } catch (_: Throwable) { false }) {` |
| `FrozenHoldingGuard8136.isFrozen8136` | `engine/sell/FrozenHoldingGuard8136.kt:27` | FAIL-OPEN(false risk-flag => allowed) | `try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { false }` |
| `SellRouteProbe8144.blockhashValid8153` | `engine/sell/SellRouteProbe8144.kt:104` | FAIL-OPEN(null refusal => allowed) | `} catch (_: Throwable) { null }` |
| `StopLatencyWiring8153.stopHolding8288` | `engine/sell/StopLatencyWiring8153.kt:103` | FAIL-OPEN(true permission) | `if (try { com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.isFrozen(mint) } catch (_: Throwable) { true }) return none` |
| `OpenPositionPanel7807.protectiveRowsNotShown7807` | `engine/truth/OpenPositionPanel7807.kt:138` | FAIL-OPEN(false risk-flag => allowed) | `!(try { WalletTokenAccountStateAuthority7253.isFrozen(p.mint) } catch (_: Throwable) { false }) &&` |
| `StopSlip8756.noteLiveTrigger8756` | `engine/truth/StopSlip8756.kt:39` | FAIL-OPEN(null refusal => allowed) | `val level = try { com.lifecyclebot.engine.StopMarkConfirm8418.let { it.stopDepthPct8489(reason) ?: it.impliedStopDepthPct8681(reason) } } catch (_: Th` |

Size / cost / stop numeric substitutes

| Function | Line | Fallback | Expression |
|---|---|---|---|
| `Executor.liveBuy` | `engine/Executor.kt:20284` | NUMERIC-SUBSTITUTE | `metaCogMult = try { MetaCognitionExecutorBridge.sizeMultiplierForLane(routedLaneTag) } catch (_: Throwable) { 1.0 },` |
| `Executor.liveBuy` | `engine/Executor.kt:21282` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 },` |
| `Executor.liveBuy` | `engine/Executor.kt:21285` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 },` |
| `Executor.requestSellCore7948` | `engine/Executor.kt:23892` | NUMERIC-SUBSTITUTE | `val edgeExitPx4532 = try { ts.lastPrice.takeIf { it > 0.0 } ?: ts.position.entryPrice } catch (_: Throwable) { 0.0 }` |
| `Executor.requestSellCore7948` | `engine/Executor.kt:23893` | NUMERIC-SUBSTITUTE | `val edgeExitPnl4532 = try { if (ts.position.entryPrice > 0.0 && edgeExitPx4532 > 0.0) ((edgeExitPx4532 - ts.position.entryPrice) / ts.position.entryPr` |
| `Executor.requestSellCore7948` | `engine/Executor.kt:23895` | NUMERIC-SUBSTITUTE | `val edgeExitHoldMs4532 = try { (System.currentTimeMillis() - ts.position.entryTime).coerceAtLeast(0L) } catch (_: Throwable) { 0L }` |
| `OrderSizeResolver6441.resolve` | `engine/truth/OrderSizeResolver6441.kt:307` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `OrderSizeResolver6441.resolve` | `engine/truth/OrderSizeResolver6441.kt:310` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `OrderSizeResolver6441.resolve` | `engine/truth/OrderSizeResolver6441.kt:365` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `OrderSizeResolver6441.resolve` | `engine/truth/OrderSizeResolver6441.kt:372` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `OrderSizeResolver6441.resolve` | `engine/truth/OrderSizeResolver6441.kt:406` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `OrderSizeResolver6441.resolve` | `engine/truth/OrderSizeResolver6441.kt:700` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `Executor.runManageOnly` | `engine/Executor.kt:9601` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `Executor.runManageOnly` | `engine/Executor.kt:9990` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `Executor.runManageOnly` | `engine/Executor.kt:10153` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { -25.0 }` |
| `FinalDecisionGate.evaluate` | `engine/FinalDecisionGate.kt:1999` | NUMERIC-SUBSTITUTE | `val metaCogMult6025 = try { MetaCognitionExecutorBridge.sizeMultiplierForLane(laneName) } catch (_: Throwable) { 1.0 }` |
| `FinalDecisionGate.evaluate` | `engine/FinalDecisionGate.kt:2277` | NUMERIC-SUBSTITUTE | `val symSizeAdj = try { symCtx?.getSizeAdjustment() ?: 1.0 } catch (_: Exception) { 1.0 }` |
| `FinalDecisionGate.evaluate` | `engine/FinalDecisionGate.kt:4712` | NUMERIC-SUBSTITUTE | `val learnedBucketMult = try { LosingPatternMemory.recommendedSizeMult(laneName, laneScoreBanded) } catch (_: Throwable) { 1.0 }` |
| `FinalDecisionGate.evaluate` | `engine/FinalDecisionGate.kt:4718` | NUMERIC-SUBSTITUTE | `val divergenceMult = try { LosingPatternMemory.paperLiveDivergenceMult(laneName, laneScoreBanded) } catch (_: Throwable) { 1.0 }` |
| `FinalDecisionGate.evaluate` | `engine/FinalDecisionGate.kt:6225` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `FinalDecisionGate.resolveExecutableSize7835` | `engine/FinalDecisionGate.kt:6414` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 } else 0.0` |
| `FinalDecisionGate.resolveExecutableSize7835` | `engine/FinalDecisionGate.kt:6426` | NUMERIC-SUBSTITUTE | `try { com.lifecyclebot.engine.truth.LiveRiskPolicy7807.edgeKellyFracFor8057(ts, lane) } catch (_: Throwable) { 0.0 }` |
| `FinalDecisionGate.resolveExecutableSize7835` | `engine/FinalDecisionGate.kt:6439` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 } else 0.0` |
| `FinalDecisionGate.resolveExecutableSize7835` | `engine/FinalDecisionGate.kt:6473` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `SmartSizer.calculate` | `engine/SmartSizer.kt:800` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `SmartSizer.calculate` | `engine/SmartSizer.kt:817` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `SmartSizer.calculate` | `engine/SmartSizer.kt:851` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `SmartSizer.calculate` | `engine/SmartSizer.kt:863` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `Executor.doBuy` | `engine/Executor.kt:14586` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `BotService.rapidStopLossMonitor` | `engine/BotService.kt:12253` | NUMERIC-SUBSTITUTE | `positionCostSol = try { ts.position.costSol } catch (_: Throwable) { 0.0 },` |
| `Executor.getActualPrice` | `engine/Executor.kt:1408` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `Executor.costExceedsEdge7162` | `engine/Executor.kt:4368` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 1.0 }` |
| `Executor.riskCheck` | `engine/Executor.kt:12021` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `ScalpExit8351.offer8351` | `engine/truth/ScalpExit8351.kt:50` | NUMERIC-SUBSTITUTE | `val cost = try { NetEdge8030.costPct8030(lane, pos.costSol, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { 7.0 }` |
| `ScalpExit8351.offer8351` | `engine/truth/ScalpExit8351.kt:51` | NUMERIC-SUBSTITUTE | `val stop = try { FieldManual7715.stopPct8392(lane, cost) } catch (_: Throwable) { 10.0 } // V5.0.8392 — never inside the round trip` |
| `BotService.hotExitWatchdogTick8418` | `engine/BotService.kt:867` | NUMERIC-SUBSTITUTE | `val open = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().size } catch (_: Throwable) { 0 }` |
| `BotService.ensureExitSweepCoordinator` | `engine/BotService.kt:23104` | NUMERIC-SUBSTITUTE | `"openPositions=${try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().size } catch (_: Throwable) { -1 }} " +` |
| `Executor.paperPartialFeeSol7287` | `engine/Executor.kt:707` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.25 }` |
| `Executor.checkPartialSell` | `engine/Executor.kt:10491` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `Executor.capitalRotation7948` | `engine/Executor.kt:15149` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `Executor.paperSell` | `engine/Executor.kt:26642` | NUMERIC-SUBSTITUTE | `val pnlPctForLedger6498 = try { OpenPnlSanity.inspectPosition(pos, price, "Executor.close_ledger_stamp_6038_6498/${ts.symbol}/${ts.mint.take(8)}", emi` |
| `ExitIntelligence.evaluateExit` | `engine/ExitIntelligence.kt:200` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `ExitTruthArbiterLive8144.inputsFor8144` | `engine/ExitTruthArbiterLive8144.kt:60` | NUMERIC-SUBSTITUTE | `val sellCost = try { RunnerExitLive8136.sellCostPct8136(ts) } catch (_: Throwable) { 3.0 }` |
| `SymbolicExitReasoner.assess` | `engine/SymbolicExitReasoner.kt:383` | NUMERIC-SUBSTITUTE | `} catch (_: Exception) { 0.5 }` |
| `Cortex7885.gradeBookSize8169` | `engine/cortex/Cortex7885.kt:881` | NUMERIC-SUBSTITUTE | `fun gradeBookSize8169(): Long = try { synchronized(this) { (gradeBook8144.size() + gradeBook8144.tradeCount()).toLong() } } catch (_: Throwable) { 0L ` |
| `CortexExit7897.ledgerBytes8309` | `engine/cortex/CortexExit7897.kt:119` | NUMERIC-SUBSTITUTE | `fun ledgerBytes8309(): Long = ledger.estBytes8309().coerceAtLeast(0L) + (try { books.size } catch (_: Throwable) { 0 }) * 1_200L + truthState8521.esti` |
| `CortexExit7897.onClosePartials8153` | `engine/cortex/CortexExit7897.kt:346` | NUMERIC-SUBSTITUTE | `val fallback = if (mode == "LIVE") try { com.lifecyclebot.engine.RecoveryFallbackLedger8141.confirmedFallbackSol8141(env.positionId) } catch (_: Throw` |
| `StopAuthority7887.stopFor8418` | `engine/cortex/StopAuthority7887.kt:79` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 15.0 }` |
| `StopAuthority7887.laneStopMag8029` | `engine/cortex/StopAuthority7887.kt:122` | NUMERIC-SUBSTITUTE | `val base = learned8040 ?: LANE_BASE[l] ?: try { kotlin.math.abs(com.lifecyclebot.engine.HoldingLogicLayer.getHoldParams(l).stopLossPct) } catch (_: Th` |
| `FieldManual7715.riskCapSol` | `engine/truth/FieldManual7715.kt:835` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `LiveRiskPolicy7807.edgeKellyFracFor8057` | `engine/truth/LiveRiskPolicy7807.kt:214` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `LiveRiskPolicy7807.laneRealisedProofFrac8188` | `engine/truth/LiveRiskPolicy7807.kt:303` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `LiveRiskPolicy7807.roundTripCostPct` | `engine/truth/LiveRiskPolicy7807.kt:534` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 6.0 }` |
| `LiveRiskPolicy7807.liveEquitySol` | `engine/truth/LiveRiskPolicy7807.kt:967` | NUMERIC-SUBSTITUTE | `} catch (_: Throwable) { 0.0 }` |
| `LiveRiskPolicy7807.runtimeInputs` | `engine/truth/LiveRiskPolicy7807.kt:1048` | NUMERIC-SUBSTITUTE | `?: try { com.lifecyclebot.engine.BotService.status.tokens[mint]?.let { edgeKellyFracFor8057(it, effectiveLane7811) } ?: 0.0 } catch (_: Throwable) { 0` |
| `LiveRiskPolicy7807.noteSealedEconomics8153` | `engine/truth/LiveRiskPolicy7807.kt:1197` | NUMERIC-SUBSTITUTE | `val impactOneWay = try { FieldManual7715.impactRoundTripPct(sizeUsd, i.liquidityUsd) / 2.0 } catch (_: Throwable) { 0.0 }` |
| `NetEdge8030.costPct8030` | `engine/truth/NetEdge8030.kt:119` | NUMERIC-SUBSTITUTE | `else try { FieldManual7715.roundTripCostPct7766(size, if (impactLiq8337 > 0.0) sizeUsd else 0.0, impactLiq8337) } catch (_: Throwable) { 5.0 }` |
| `MarketsLiveExecutor.configuredSlippageBps` | `perps/MarketsLiveExecutor.kt:132` | NUMERIC-SUBSTITUTE | `} catch (_: Exception) { 100 }` |
| `SmartSizerV3.compute` | `v3/sizing/SmartSizerV3.kt:331` | NUMERIC-SUBSTITUTE | `} catch (e: Exception) {` |

## 5. Kotlin pitfalls

| Check | Raw hits | Confirmed / notable | Notes |
|---|---|---|---|
| Operator at end of line followed only by a `//` comment | 114 lines end in `&&`/`\|\|`/`+`/`-`/`?:` then a comment (16 with `&&`/`\|\|`) | **0 bugs** | In every case the expression continues on the next code line as written; 48 have prose comments between (no commented-out operand clauses). |
| Operator inside a comment (commented-out clause) | 96 own-line/inline comments starting with an operator | **0 bugs** | Prose about percentages (`// -29.4 % …`) and quoted code in explanations (`ExecutableOpenGate.kt:3742-3743`, `ToolkitSignalSheet.kt:1461`). |
| Line starting with `+ "` / `- x` after a complete expression (dead unary statement) | 23 statement-level candidates | **0 bugs** | All are `when`-branch values (`-1 -> …`) or block-result values (`-8 // …` as last expression). No `+ "` string lines exist (they would not compile). |
| `==`/`!=` on Doubles | 377 vs literals (`!= 0.0` 151, `== 0.0` 101, `!= 1.0` 70, `== 1.0` 37) + 40 between price/pct/sol variables | ~12 worth fixing | Intentional sentinel detection by exact equality `price6728 == 0.05/0.10/0.50/1.00/5.00/10.00` (`truth/CanonicalPriceMark6522.kt:179-180`, OK for parsed literals, misses 0.1000001); `currentPrice == entryPx` "still at entry" (`Executor.kt:9940`); `px == ts.lastPrice` as mark identity (`LifecycleHold8065.kt:339,381`, `truth/ForwardReturnLabeler7731.kt:341,2199,3173`); cache keys on doubles `v.costSol == costSol` (`truth/ExitQuoteCorroboration7928.kt:80`), `cached.priceUsd == intent… && cached.liquidityUsd == …` (`SealedEntryContinuity7863.kt:25`); `previous.pnlPct == o.pnlPct` dedupe (`ScannerSourceEpisodes8372.kt:84,102`); `leverage == 1.0` ×22 in Forex/Metals (benign, assigned constants); `!= 1.0` multiplier logging guards (benign). |
| `toInt()`/`toLong()` truncation of prices/amounts | 270 (264 are display/log) | 6 + 26 | No price itself is truncated in a decision. `ts.lastMcap.toInt()` saturates at 2.1 B (`BotService.kt:30830`, `ToolkitSignalSheet.kt:342-343`). Lamports: 26 `(sol * 1e9).toLong()` truncations incl. swap `amountInRaw` (`Executor.kt:13634, 20350`), proceeds (`Executor.kt:26540`, `truth/CanonicalPaperTerminalBridge6469.kt:356`, `truth/CanonicalPaperTransaction6486.kt:269,431`) and **event IDs** (`truth/JournalEconomicReplay6619.kt:940,1167`). |
| Locale-less `format` | 2,909 `"%.Nf".format(…)`/`String.format` without Locale (221 sites do pass `Locale.ROOT/US`) | 3 key-like | No format output is parsed back with `toDouble` (checked). Formatted doubles are embedded in identity strings: `truth/AutoPipelineAdvisor6462.kt:247` (`shape7172` key), `v4/meta/PortfolioHeatAI.kt:214` (`evidenceId`), `ExitDoctrine8169.kt:213`. Reason strings are parsed elsewhere (`v3/decision/DecisionEngine.kt:255`) — that one is item 1. |
| `!!` on network JSON | 255 `!!` total | 1 | `network/CurveTicks7968.kt:285` `trade!!.isBuy` (inside a swallowing try). JSON is read with `opt*`/`?.` almost everywhere. |
| Constant conditions | 2 | 2 | `Executor.kt:12494` `if (false && …)`, `SolanaMarketScanner.kt:1552` `if (!backpressure \|\| true)`. |

### 5.1 Related string-parsing pitfall (found while checking 5)
`v3/decision/DecisionEngine.kt:253-256` derives `liquidityUsd` from `scoreCard.components["liquidity"].reason.substringAfter("liq=")`;
`substringAfter` returns the whole string when the delimiter is missing, `toDoubleOrNull()` → null → `0.0`. `LiquidityAI.score`
never writes `liq=`, so the value is always 0 and `ToxicModeCircuitBreaker.checkEntryAllowed` (`engine/ToxicModeCircuitBreaker.kt:232-247`)
returns `LIQUIDITY_BELOW_FLOOR_*` whenever gates are not lenient. `LaneAuthority8577` likewise binds refusals by substring
(`"DATA_"`, `"STALE"`, `"_FAILED"`, `"RUG"`, `"T22_"` …, `truth/LaneAuthority8577.kt:25-40`) — any new reason name containing one of those
fragments silently becomes a hard door.


## Appendix — reproduce

```
cd <scratchpad>
python3 -I lex.py lexed.pkl          # lex 1,832 main + 1,540 test files
python3 -I index.py lexed.pkl idx.pkl # declarations + reachability
python3 -I deadsum.py; python3 -I flags.py; python3 -I rot.py; python3 -I dups.py
python3 -I catches.py; python3 -I pitfalls.py; python3 -I size.py; python3 -I report.py
python3 -I concepts.py chase/top stop cost age/birth proven lane-norm price-fresh   # concept listings
```
