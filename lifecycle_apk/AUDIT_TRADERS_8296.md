# AUDIT_TRADERS_8296: the traders and the confidence-to-size loop

Branch `wt-atrade-8296`, base `origin/main` 7c147e519 (5.0.8286). Evidence: live 5.0.8286. All 4 closes lost, every entry was ROUTE_MIN ~0.028 SOL and UNPROVEN. Top FDG blocks: LIVE_PROVEN_SETUP_ONLY_8059 751, EXECUTION_ECONOMICS 598. admitted60 n17 +1.8% vs refused60 n300 -24.0%.

Owner complaint: "it went from a money printer (doubling the wallet, confidence growing, trades getting bigger) to just losing money."

## 1. Traders: who trades live, what they admit, and why they are blocked

| Trader / lane | Live path | State on 8286 evidence |
|---|---|---|
| Meme spine: SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER, MANIPULATED, QUALITY, BLUECHIP, DIP_HUNTER, CASHGEN, TREASURY, CYCLIC (`v3/scoring/*TraderAI`, `SpecialistBrainBridge7542`) | `FinalDecisionGate` → `LiveRiskPolicy7807.decide` → `OrderSizeResolver6441` → Executor | Trading live, but only at the route minimum. SHITCOIN lost 2 of 2 (BREAKOUT_RUNNER -33.8%, SOCIAL_LAUNCH -16.7%). MOONSHOT lost 2 of 2 on the BLUECHIP/CORE setup `MAINSTREAM_CRYPTO_SWING` (-10.6%, -9.2%). PROJECT_SNIPER is dead (candidateN=0; the hunters/data agents own this). |
| CryptoAltTrader (`perps/CryptoAltTrader.kt`) | Its own route; live size comes from the real wallet (`:2943`) | Not in the 8286 closes. No paper-into-live sizing found. It feeds the shared AutoCompoundEngine (fixed below). |
| TokenizedStock / Forex / Metals / Commodities | `EnabledTraderAuthority.marketLanesQuarantined()` (`TokenizedStockTrader.kt:587`) | Quarantined in LIVE by design (old record: n82, WR 0%). Paper only. |
| Perps (`PerpsTraderAI`, `PerpsPositionSizer`) | Separate | Not live in this evidence. |

**Blockers:**
- **LIVE_PROVEN_SETUP_ONLY_8059** (`engine/truth/LiveEdgeGate7877.kt:599`). This gate is doing its job: refused60 is -24.0% against admitted60 +1.8%. Loosening it would buy losers, so I left it alone.
- **The real admission leak is `kindProven8063=663`.** It lets through kind books flagged OUTLIER_SUSPECT, for example NARR|SHITCOIN|UNKNOWN at +328%. This belongs to the admission agent (CandidateAdmission8136 kindProven).
- **EXECUTION_ECONOMICS_8136** (`CandidateAdmission8136.kt:441`). This needs gross ≥ 1.5 × the round trip. The cost is `max(model, measured lane median)` (`NetEdge8030.kt:108`), and that median only binds once the lane has 8 or more measured live trades.
  - The CostLedger "SHITCOIN sellSide 50%" figure is a median over very few sell legs (one rug exit). It reaches exits only through `sellSidePct8189`, and `ProfitExitTruth8163` ignores it below 5 legs (`MIN_SLIP_N_8189`, `:43`). So it does not currently kill entries.
  - PumpPortal's ~30% buy land rate is an execution problem, not a cost-model input. The exits/Jupiter agent owns it.

## 2. Confidence and size: findings and fixes

**F1. Confidence never decayed after live losses; an old bleed kept a recovered lane shrunk.**
- `LiveRiskPolicy7807.laneLive` (`:834`) feeds `expectancyMultiplier` (`:146`, growth up to 1.5x) from `StrategyTelemetry.computeCleanLiveTerminalLeaderboard()`, which covers the last 2,500 clean live closes. A profitable era kept growth at full while the newest closes all lost. A negative era capped a recovered lane at 0.85x for thousands of trades.
- **Fix:** new `engine/truth/LiveConfidence8296.kt`. It keeps the last 12 live whole-position closes per lane, only those verified on-chain (LIVE_FINALIZED / LIVE_RECONCILED, net of fees). It is fed by the CanonicalTradeFinalizedBus6450 subscription (`LiveRiskPolicy7807.kt:787`), persisted, and never reset.
  - `decide()` now uses `recencyAdjusted8296` (`:615`, inputs `:958`).
  - When the lane's recent net SOL is negative, or it has 3 losses in a row, growth is capped at 1.0. The multiplier also shrinks by the recent mean (floor 0.5; at most 0.8 on a streak), with the label `RECENT_LIVE_LOSSES_SHRINK_8296`.
  - A full, statistically positive window (mean − SE > 0, net > 0) may grow the lane to 1.25x even if older history is negative.

**F2. Compounding kept sizing up while the newest live closes, and the setup's own live closes, were losing.**
- `edgeKellyFracFor8057` (`:194`) capped the label share by the lane's whole realised record (`laneRealisedProofFrac8188`, `:277`).
- A fresh losing run never revoked that share. A setup whose own live closes lose still compounded on its labels. Example: LAUNCH_LADDER_PROVEN, labels n824 +83.5% auth=1.00, realised all losses.
- **Fix:** `recentKellyGate8296` (`:221`) sets the share to zero when the lane's recent record is losing, or when the selected setup has 2 or more live closes (`Cortex7885.setupRealised8187`) averaging a loss. This also drops the FDG proof tier from PROVEN (25% wallet share) back to baseline.

**F3. Paper results sized live orders up (SmartSizer).**
- With fewer than 10 live trades, the live perf multiplier read FluidLearning's **paper** win rate and could grow a live order 1.15–1.30x.
- Every switch from paper to live also seeded the live win/loss ring and streaks from paper.
- **Fix:** `LiveConfidenceSizing8296.paperFallbackLiveMult8296` caps the paper fallback at 1.0 for live orders (`SmartSizer.kt:491`), so paper can only shrink a live order. Live memory is no longer seeded from paper (`:1022`). Paper memory itself is untouched.

**F4. AutoCompoundEngine: one pool shared by paper and live, and losses never shrank it.**
- This is the growth-mandate finding; see the section below.

## 3. Learning feedback (are live outcomes the authority?)

- Live realised closes reach sizing through three paths:
  - the 6450 bus subscription: the lane's 24h daily-loss shrink, and now LiveConfidence8296 plus the live compound pool;
  - `CanonicalFinalizedTradeBus6464.terminalEnvelopes7863` for the realised Kelly proof and maturity;
  - the persistent clean-live leaderboard.
- All three are net of fees and live-only. LiveConfidence8296 also requires on-chain finality, and it de-duplicates by positionId.
- "LEARNING_ACK" here is only a funnel stage name (`FeatureFunnel8283`). The real acknowledgement is per-consumer acks on the 6464 bus. The Cortex lost-label gap (LABEL_LOST_GAP_8187=657) belongs to the Cortex agent.
- Because labels can be lost, F5 below lets real live wins compound without them.

## Growth target (2x–5x daily)

How the daily target, auto-compound and live-sizing path is wired today, end to end:

- **`LiveSizingProfile`** (12–25% wallet floors, a daily realised ramp of up to 1.5x).
  - Since 8057 it is **paper-only** (`SmartSizer.kt:931`, pinned by `Aate8057CompoundingSizerTest`). It used to lift every live order to 12% of the wallet regardless of edge. That is "aggression spent on losing setups", and turning it back on would re-create today's losses at 4x the size. I did not restore it.
  - `recordRealizedClose` still runs (`Executor.kt:29958`), but its ramp is now only a scoreboard.
- **Daily target** (`LiveRiskPolicy7807.dailyScoreLine8057`, `CapitalPreservationCreed6439`, `BotService.kt:18149` → `LearningTrajectoryGovernor6380`) is reporting only. That is deliberate (8057: "the target never sizes"). Chasing a target with size is how a small wallet gets ruined.
- **The live compounding engine is `LiveRiskPolicy7807`.**
  - Size is a quarter-Kelly share of **current equity** for a proven edge, so it rises as the wallet doubles.
  - It is bounded by the lane cap (20–30% of equity), pool depth, the hard 4–8% loss at the stop, and a 25% wallet share for PROVEN candidates.
  - It is wired and live. On a 0.25–0.5 SOL wallet, an unproven risk size (≈1.5–2% equity ÷ (stop+cost)) sits below the route minimum, so every unproven order is ROUTE_MIN. That matches the 8286 evidence. Growth only engages on proven edge.
- **F4 (fixed): `AutoCompoundEngine` leaked paper into live and never decayed.**
  - `processWin` is called from both the paper path (`Executor.kt:26250`) and the live path (`:29569`) on one global state.
  - `getSizeMultiplier()` (up to 3x) is consumed by ShitCoin, Moonshot, Express, Sniper, Manipulated, BlueChip and CashGen. So paper wins grew live proposals.
  - `processLoss` (`:180`) only resets the streak, so the pool never drains.
  - **Fix:** in LIVE, `getSizeMultiplier()` (`:190`) returns `LiveConfidence8296.liveCompoundMultiplier8296()`. That pool is fed only by verified live closes: a win adds 45%, a loss drains its full amount, and it keeps the same curve (+0.45x per 0.15 SOL, max 3x). Paper keeps the old state.
  - The live pool starts at 0 because the shared pool cannot be split into paper and live after the fact. The old state is preserved for paper; nothing was wiped.
- **F5 (fixed): real live wins could not compound without forward labels.**
  - `validatedKellyFrac8188` returns 0 when no setup, cell or kind label share is proven, even when the lane's realised proof is mature and positive.
  - With 657 lost labels, a setup paying on-chain could stay flat at ROUTE_MIN.
  - **Fix:** `realisedOnlyFrac8296` gives half the lane's realised-proof stake to a setup with 3 or more of its own live closes averaging a gain. F2's recency and losing-setup gates still apply, along with every hard cap.

**Net effect:**
- Size and confidence now rise from on-chain wins: the realised Kelly stake, recent-window growth, and the live compound pool.
- They fall quickly after on-chain losses: recent shrink, Kelly revoked, and the pool drained in full.
- Paper no longer moves live size up anywhere I found.
- No hard safety was loosened.

## Tests

- `app/src/test/.../engine/Aate8296LiveConfidenceSizingTest.kt`: recent record, shrink and growth, Kelly gate, verified-only capture, `decide()` integration, paper caps, wiring pins.
- `app/src/test/.../engine/Aate8296LiveCompoundGrowthTest.kt`: pool growth and drain, multiplier curve, realised-only share, AutoCompound wiring.
- `val2.sh` validated; `pins.py` reports broken 0.
- There is no local compiler, so CI is the first compile.

## Remaining (not fixed here)

1. **SmartSizer counts a profit-lock partial sell as a live "win"** (`Executor.kt:8237`), and the terminal close then records the remainder. One position can show win + loss. The streak multiplier needs a whole-position outcome. I did not just delete the call, because the terminal row only covers the remainder, and that would bias the streak toward losses.
2. **The admission agent should cap kindProven8063 / cellProven8060 on OUTLIER_SUSPECT books** (8286: kindProven=663 admissions, every close lost).
3. **MOONSHOT trading `MAINSTREAM_CRYPTO_SWING`** (a BLUECHIP/CORE swing setup) is a lane/setup identity mix-up. It belongs to the specialist-lane agent.
4. **`setupExpectedGrossPct8153` reads the raw label mean** when no admission proof exists (capped at 1000%). The admission proof is the normal basis.
5. **The 4 closes in the evidence were unproven route-minimum probes.** They fall under the 8259 exploration slot and the admission gates, not sizing.
