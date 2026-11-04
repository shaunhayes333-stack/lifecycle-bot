# AATE repair register — 47 audit items (directive of 5 Nov 2026)

Source registers: `docs/audits/AATE_8530_Lane_Setup_Strategy_Audit.md` (31) and
`docs/audits/AATE_8530_Audit_Continuation_Scoring_Crypto.md` (16). Baseline `main` 86a06e970 (5.0.8530); runtime 8529.
Work branch: `claude/fix-traders-buying-vw6Oc`. **Nothing reaches `main` until every row is FIXED or EVIDENCE-REFUTED with
operator approval.** Status values: OPEN / IN PROGRESS / FIXED / EVIDENCE-REFUTED / BLOCKED.

Prior work on the branch that touches items (not counted as closure until the row's evidence is complete):
8529 remaining-move (P0-04 partial: launch window still exempt), 8529 stop route / quote rule (P0-12 partial),
8532 minimum-gate defers (P1-10 partial), 8533 Helius-first / DAS supply (P0-01 inputs), 8534 label cost (P0-01 cost basis).

| ID | Title | Original source | Root cause | Repair | Upstream verified | Downstream verified | Regression test | Runtime / replay | Status |
|---|---|---|---|---|---|---|---|---|---|
| P0-01 | Forward price-label delivery failure | `engine/truth/ForwardReturnLabeler7731.kt:62-68,118-130,167-191,2068-2071,2174-2315,2894-2898; engine/truth/KeylessMarks8359.kt` | — | — | — | — | — | — | OPEN |
| P0-02 | Downtime recovery does not recover queued labels | `ForwardReturnLabeler7731.kt:2553-2665,2706-2721; DowntimeRecovery8430.kt` | — | — | — | — | — | — | OPEN |
| P0-03 | Generic NO_RECLAIM blocks valid early pullbacks | `LanePlaybook7907.kt:294-581; CandidateAdmission8136.kt:463-553` | — | — | — | — | — | — | OPEN |
| P0-04 | Fresh-launch exemption ignores moves already consumed | `StrategyRunner8153.kt:50-128` | — | — | — | — | — | — | OPEN |
| P0-05 | Native specialist scoring uses artificial defaults | `SpecialistBrainBridge7542.kt:337-415; SpecialistInputParity8527.kt:59-130` | — | — | — | — | — | — | OPEN |
| P0-06 | Native setup labels disagree with fired playbook strategies | `SpecialistBrainBridge7542.kt:13-45,388-398; CandidateAdmission8136.kt:1370-1404; StrategyRank8153.kt:150-180` | — | — | — | — | — | — | OPEN |
| P0-07 | Native scoring clips MOONSHOT conviction; nativeAllow counted pre-review | `SpecialistBrainBridge7542.kt:38-45,387-445; MoonshotTraderAI.kt:785,804-825,927` | — | — | — | — | — | — | OPEN |
| P0-08 | Specialist ownership / handoff lose candidates | `SpecialistOwnership7951.kt:224-255,391-445,561-710; HandoffReceiverTasks8153.kt; LiveEdgeGate7877.kt:479-525` | — | — | — | — | — | — | OPEN |
| P0-09 | PROJECT_SNIPER misses birth metadata in its entry window | `ProjectSniperAI.kt:241-296; AdmissionBirth8144.kt; TokenBirthHydrator7441.kt` | — | — | — | — | — | — | OPEN |
| P0-10 | DIP_HUNTER cannot evaluate dips without price history | `HunterMandates8031.kt:10-33; LaneHunter7297.kt:175-187; DipHunterAI.kt:329-392` | — | — | — | — | — | — | OPEN |
| P0-11 | Cortex polarity authority cannot become operational | `CortexOpportunityPolarity8522.kt:72-109; CortexScoreboard7885.kt:29-58,183-195; Cortex7885.kt:398-415` | — | — | — | — | — | — | OPEN |
| P0-12 | Emergency sell latency exceeds the SLA | `engine/sell/SellRouteSpine8144.kt; Executor.kt dispatch` | — | — | — | — | — | — | OPEN |
| P1-01 | Multiple setups positively score near-top entries | `LanePlaybook7907.kt:275-308,422-424,486-492` | — | — | — | — | — | — | OPEN |
| P1-02 | LAUNCH_CONTINUATION omits IGNITION | `LanePlaybook7907.kt:287-290` | — | — | — | — | — | — | OPEN |
| P1-03 | Token-quality conditions masquerade as entry setups (8383 shapes) | `LanePlaybook7907.kt:555-591` | — | — | — | — | — | — | OPEN |
| P1-04 | MOONSHOT / MANIPULATED pressure scoring can reward exhaustion | `MoonshotTraderAI.kt:565-636; ShitCoinTraderAI.kt:824-885; ManipulatedTraderAI.kt:199-255` | — | — | — | — | — | — | OPEN |
| P1-05 | CASHGEN / TREASURY reward late momentum inconsistently | `CashGenerationAI.kt:550-583,715-766,895-914; TreasuryBrain.kt:43-119` | — | — | — | — | — | — | OPEN |
| P1-06 | Scalp economics demand historical movement, not forward opportunity | `NetEdge8030.kt:385-439` | — | — | — | — | — | — | OPEN |
| P1-07 | Falling-hour rules wrongly apply to rebound strategies | `StrategyRunner8153.kt:29-40,97-129` | — | — | — | — | — | — | OPEN |
| P1-08 | PROJECT_SNIPER assumes list order = chronological order | `ProjectSniperAI.kt:381-393` | — | — | — | — | — | — | OPEN |
| P1-09 | CORE / CYCLIC READY can lack an actionable setup | `CyclicTradeEngine.kt:188-207; CyclicEntryPolicy8527.kt:22-46; SpecialistBrainBridge7542.kt:437-440` | — | — | — | — | — | — | OPEN |
| P1-10 | Default native opinions inflate selection | `SpecialistBrainBridge7542.kt:337-415; LanePlaybook7907.kt:168-181,238-239,1490-1550` | — | — | — | — | — | — | OPEN |
| P1-11 | Model costs do not reconcile with observed economics | `NetEdge8030.kt:300-439; StrategyRunner8153.kt:50-95` | — | — | — | — | — | — | OPEN |
| P1-12 | Exceptional runners detected, not captured | `LanePlaybook7907.kt:266-492; CandidateAdmission8136.kt:475-817; LiveEdgeGate7877.kt:479-541; ForwardReturnLabeler7731.kt:2833-2850` | — | — | — | — | — | — | OPEN |
| P1-13 | Missing buyer pressure can satisfy setup predicates | `LanePlaybook7907.kt:255-260,419-472` | — | — | — | — | — | — | OPEN |
| P1-14 | MOONSHOT RugCheck comments and handling disagree | `MoonshotTraderAI.kt:420-424,516-547` | — | — | — | — | — | — | OPEN |
| P1-15 | Strategy ranks cannot mature because labels are missing | `StrategyRank8153.kt:56-143; LanePlaybook7907.kt:1492-1525` | — | — | — | — | — | — | OPEN |
| P1-16 | Crypto alternatives never progress beyond their own FDG | `CryptoAltTrader.kt; CryptoLaneDesk7391.kt; LiveEdgeGate7877.kt` | — | — | — | — | — | — | OPEN |
| P2-01 | Missing joined decision evidence | `docs/audits/8528-pipeline-observability.md:7-28` | — | — | — | — | — | — | OPEN |
| P2-02 | DGrade proposal tracker uses unsynchronized mutable state | `v3/eligibility/EligibilityGate.kt:53-129; FinalDecisionGate.kt:1950-1961` | — | — | — | — | — | — | OPEN |
| P2-03 | Counters mix incompatible cohorts and epochs | `SpecialistBrainBridge7542.kt:38-45,400-415; SpecialistOwnership7951.kt:224-255; Cortex7885.kt; ForwardReturnLabeler7731.kt:75-84` | — | — | — | — | — | — | OPEN |
| C01 | Negative risk contributions vanish through integer rounding | `v3/scoring/UnifiedScorer.kt:1087-1111,144-158` | per-vote `(v*scale).toInt()` truncated toward zero at 5 cap sites + FLUID/GENEQ scales | `SignedCap8535.apportion/capNegatives/scaleGroup`, all 7 sites | votes come from the same component lists (unchanged producers) | `ScoreCard.total` sums the apportioned values; consumers unchanged | `Aate8535ScoreMathTest` (14×-3 → -10; [-12,-6,-3,-3] → -10 any order) | no replay yet | FIXED (pending CI) |
| C02 | MetaCognition compresses the full V3 score onto ±100 | `MetaCognitionAI.kt:1026-1060; UnifiedScorer.kt:1156-1173,298-315,645-665` | `adjustScore` clamped the aggregate to ±100; callers booked `adjusted - base` as a vote | `metaDelta8535`: bounded ±40 delta on the raw aggregate; `adjustScore = raw + delta`; the three scorer paths unchanged and now correct | predictions/trust unchanged | `metacognition` component = bounded delta; 8527 clamp test superseded | `Aate8535ScoreMathTest` (80/120/180/250 monotonic, distinct, neutral = identity) | no replay yet | FIXED (pending CI) |
| C03 | MetaCognition forecasts keyed by mint | `MetaCognitionAI.kt:307-318,371-387,446-458,1254-1294` | `pendingPredictions[mint]` replaced by any scan, consumed by any close (paper/live/shadow), dedupe by mint | staging keyed (mode, mint); settlement rebuilt from the entry's sealed components by positionId (`sealedForecast8535`), staging only as fallback; one learn per position / trade id; shadow closes settle their own cohort | Executor close fan-out, EducationSubLayerAI, ShadowLearningEngine pass position/mode/trade id | `onCanonicalOutcome` dedupes by trade id | `Aate8535ForecastIdentityTest` | no replay yet | FIXED (pending CI); note: shadow MetaCog learning now needs its own staging (not previously correct either) |
| C04 | V3 counts buyer pressure twice | `v3/scoring/ScoringModules.kt:17-30,44-79` | `MomentumAI` base score from `buyPressurePct` bands; `EntryAI` scores the same reading | `MomentumAI` graded on `momentumScore` (price history) / observed 5-min change; buyers confirm ±2 only when both observed | `V3Adapter` already publishes `momentumScore` / `priceMomentumPct` | `ScoreCard` momentum component; MetaCog layer predictions now from distinct sources | `Aate8535ForecastIdentityTest` (bp 75, no price data → entry 8, momentum 0) | no replay yet | FIXED (pending CI) |
| C05 | Playbook expected-return score saturates at +5% | `LanePlaybook7907.kt:1313-1332` | — | — | — | — | — | — | OPEN |
| C06 | Crypto READY can be overwritten by WATCHING | `perps/CryptoStrategyCandidateBooks7803.kt:55-115` | — | — | — | — | — | — | OPEN |
| C07 | Crypto candidate version is a wall-clock bucket | `CryptoAltTrader.kt:1540,1964-1984; CryptoStrategyCandidateBooks7803.kt:31-43,69-125` | — | — | — | — | — | — | OPEN |
| C08 | Crypto losing-pattern mean uses a wrong denominator | `perps/crypto/brain/CryptoLosingPatternMemory.kt:22-52` | — | — | — | — | — | — | OPEN |
| C09 | Crypto tactics learn from the wrong strategy | `CryptoTacticSwitcher.kt:19-64; CryptoAltTrader.kt:1277-1315,5164-5179` | — | — | — | — | — | — | OPEN |
| C10 | Crypto learning has contradictory win/loss definitions | `CryptoBrain.kt:117-149; CryptoFluidLearning.kt:54-61,168-184` | — | — | — | — | — | — | OPEN |
| C11 | Crypto tactics accept defaulted buyer pressure | `CryptoAltTrader.kt:1270-1326,1558-1584` | — | — | — | — | — | — | OPEN |
| C12 | Crypto volatility / BTC dominance wired wrong | `CryptoAltTrader.kt:2454-2485; CryptoAltStrategy.kt:41-150` | — | — | — | — | — | — | OPEN |
| C13 | PERPS missing OI and RSI inputs | `PerpsTraderAI.kt:451-471; PerpsStrategy.kt:44-155` | — | — | — | — | — | — | OPEN |
| C14 | FOREX changes strategy between analysis and execution | `ForexTrader.kt:465-499,733-769; ForexStrategy.kt:137-223` | — | — | — | — | — | — | OPEN |
| C15 | Metals / commodities inputs and exits disconnected | `MetalsTrader.kt; CommoditiesTrader.kt; MetalsStrategy.kt; CommoditiesStrategy.kt` | — | — | — | — | — | — | OPEN |
| C16 | Forex session classification uses wrong static boundaries | `ForexStrategy.kt:66-85,162-175` | — | — | — | — | — | — | OPEN |

## Newly discovered during the repair (must be resolved before release)
| ID | Title | Source | Status |
|---|---|---|---|
| N-01 | DexScreener EXIT-class budget hog: 508 calls / 6 min for one held coin starves the bulk lane | `network/DexHostBudget8287.kt`, callers under `ExitHttpScope7314` | OPEN |
| N-02 | Supply request in-flight gate wedge (uncounted silent return) | `engine/truth/OnChainSupplyAuthority7075.kt` | FIXED 8533 (TTL + counters) — pending runtime evidence |
