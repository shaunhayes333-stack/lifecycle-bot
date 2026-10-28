# Brain influence proof — V5.0.8418 (item 16)

Each row says which decision a brain claims to change, whether a JVM counterfactual test proves the change, the test, and the
code the test runs plus where production consumes it. "Proven" means: one candidate held fixed, one input or state of that brain
flipped, and the decision output changed. Counter increments do not count as proof.

Test file: `app/src/test/kotlin/com/lifecyclebot/engine/Aate8418BrainInfluenceTest.kt` (class `Aate8418BrainInfluenceTest`).
Paths below are relative to `app/src/main/kotlin/com/lifecyclebot/engine/`.

| Brain / family | Intended decision | Proven influence | Test | Code under test · production consumer |
|---|---|---|---|---|
| Cortex voters (seated) | admit / refuse (verdict bucket) | **yes**: a seated voter's read flips STRONG to REFUSE | `cortexSeatedVoterFlipsTheVerdictBucketForTheSameCandidate8418` | `cortex/CortexLedger7885.kt:659` fuse · `cortex/CortexScoreboard7885.kt:45` bucketOf · consumed in `cortex/Cortex7885.kt:158` assess |
| Cortex voters (unseated, < MIN_SCORED) | none until seated | **no, by design**: receives data, does not move the fusion | `cortexUnseatedVoterReceivesDataButCannotMoveTheFusion8418` | `cortex/CortexLedger7885.kt:122` cleanAuthority8007 |
| Cortex conviction (Kelly × authority) | order size | **yes**: authority 0 / 0.5 / 1 gives size multiple 1.0 / 1.75 / 2.5 | `cortexConvictionAuthorityChangesTheSizeMultiple8418` | `cortex/Cortex7885.kt:3642,3677` · `truth/TraderSizingBridge6444.kt:122` |
| CellAllocator7962 (cell posterior) | size | **yes**: a winning cell sizes up, a losing cell sizes down, through the combined chain | `cellAllocatorPosteriorFlipsSizeAndTheCombinedChain8418` | `CellAllocator7962.kt:196,217` · `truth/TraderSizingBridge6444.kt:121` |
| CellAllocator7962 (slot / rotation) | admit on the last slot; early exit | **yes** | `cellAllocatorFlipsLastSlotAdmissionAndEarlyRotationExit8418` | `CellAllocator7962.kt:228,236` · consumed at `CellAllocator7962.kt:497,525` |
| SpecialistMiner7972 | admit (promotion) + size floor | **yes** | `specialistMinerFloorChangesSizeAndPromotionChangesAdmission8418` | `truth/SpecialistMiner7972.kt:95,422` · `truth/LiveEdgeGate7877.kt:994,1056,1217`, `truth/TraderSizingBridge6444.kt:121` |
| Timing cortex (CortexTiming7900) | entry wait (C4_WAIT_FOR_DIP) | **partial**: the proof bar flips (`waitProven`); the full `waitRefusal(Assessment)` needs the private dip book and timing ledger | `timingCortexDipRecordFlipsTheEntryWait8418` | `cortex/CortexTiming7900.kt:54,175` · `cortex/Cortex7885.kt:3393` |
| Exit cortex (CortexExit7897) | hold veto / own sell | **partial**: the forward bucket and the hold/sell proof bars flip; `holdVeto` / `sellReason` read a live TokenState mark and private books | `exitCortexForwardReadAndRecordFlipHoldAndSell8418` | `cortex/CortexExit7897.kt:69,79,83,389,437` · `Executor.kt:24909`, `BotService.kt:1147` |
| TacticSwitcher | refuse a losing tactic | **yes** (the predicate behind `losingTacticFor8094`) | `tacticSwitcherLosingRecordFlipsTheLosingTacticRefusal8418` | `learning/TacticSwitcher.kt:274,283` · `truth/CandidateAdmission8136.kt:1297`, `truth/LiveEdgeGate7877.kt:660` |
| ExpertWallets7962 | source promotion (signal proof) | **yes** | `expertWalletRecordFlipsSourcePromotion8418` | `ExpertWallets7962.kt:315` · `truth/SignalSourceProof7291.kt:172,200` |
| Lane-brain votes (LaneBrainVotes8366) | votes enter the Cortex read only while fresh | **yes** (the freshness gate) | `laneBrainVotesOnlyInfluenceWhileFresh8418` | `cortex/LaneBrainVotes8366.kt:45,80` · `cortex/CortexVoters7885.kt:429` |
| Lane cheat sheet (LaneCheatSheet8323) | discovery routing (lane admit) | **yes** | `cheatSheetMandateFlipsDiscoveryRouting8418` | `cortex/LaneCheatSheet8323.kt:326,337` · `TokenMergeQueue.kt:455` |
| Size chain (FirstSight, RunnerPlay, TailHunter, RunnerGrab, SpecialistMiner, CellAllocator, Cortex) | size | **wired** (source pin; no behaviour test for the four runner multipliers) | `sizingChainCarriesEveryClaimedSizeBrain8418` | `truth/TraderSizingBridge6444.kt:120-125` |
| ScannerSourceBrain.shouldSkipIntake | skip intake from a losing source | **no: receives data, does not influence intake**. The function has no caller (`GlobalTradeRegistry.kt:387-393` says it was reverted in V5.0.4598) | `scannerSourceBrainSkipIntakeHasNoCaller8418` | `ScannerSourceBrain.kt:118` |
| ScannerSourceBrain.intakeMultiplier | scanner score shape | **not proven in JVM** (wired: source pin). The cohort state comes from Android-persisted outcomes; the multiplier stays 1.0 at BOOTSTRAP | `scannerSourceBrainSkipIntakeHasNoCaller8418` (pin only) | `ScannerSourceBrain.kt:328` · `SolanaMarketScanner.kt:4425`, `ScannerIntake8292.kt:149`, `LiveStylePivotRouter.kt:186` |
| UnifiedPolicyHead (UPH) | FDG size | **not proven in JVM**. Wired in `FinalDecisionGate.kt:5829-5867`, which is not edited. Conviction is exactly 1.0 until the global or lane head is trained. Process-global training state would leak between tests | — | `UnifiedPolicyHead.kt:360,377` |
| PredictiveEntryOracle6915 | admission inputs / entry contract | **not proven in JVM**. `evaluate` is 1,000+ lines over live TokenState / market singletons | — | `truth/PredictiveEntryOracle6915.kt:790` · `BotService.kt:31396`, `truth/CanonicalAssetEntryContract6551.kt:179`, `truth/LearnedAdmissionInputs6909.kt:347` |
| LanePlaybook7907 (setup selection) | strategy / setup the coin trades as | **not proven here**. `selectedSetup8094` needs a TokenState with live tape. The cheat-sheet verdict it consults is covered above | — | `cortex/LanePlaybook7907.kt:1440,1514` |

## Findings

1. `ScannerSourceBrain.shouldSkipIntake` (`ScannerSourceBrain.kt:118`) receives data but influences no decision: it has no caller. Left unwired by design: V5.0.4598 reverted the V5.0.4597 intake blackout on the operator directive "we aren't meant to choke or reduce things as a bandwidth" (`GlobalTradeRegistry.kt` comment); toxic sources are shaped by `intakeMultiplier` instead.
2. `CortexGradeRecord8144.revisions8272` is not in the grade-book encoding (`cortex/CortexGradeBook8144.kt` decode, lines 708-750), so the count restarted at 0 after every restart. Nothing reads it (diagnostic only), so no decision changes. Fixed in int-8418-leftovers: the count is field 46 of the encoding (`Aate8418GradeRevisionsPersistTest`).
3. `FieldManual7715.mandateFor("CYCLIC")` (`truth/FieldManual7715.kt:257-279`) has no CYCLIC branch, so CYCLIC falls through to the GENERIC mandate (BASE_BREAKOUT, +20 % expected gross / 10 % invalidation). This matters for item 14 (CYCLIC's strategy / holding contract), which another agent owns. The tests here do not pin it.
4. Test seam: `LearningPersistence.saveTogetherVerified8386` dereferenced `Looper.getMainLooper()` without a null check. That call cannot return null on a device. In a JVM test it does return null, and the NPE made the Cortex periodic snapshot silently write nothing. It is now `?.thread`, matching `Commit8090` in the same file. Production behaviour is unchanged.
