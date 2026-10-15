# AATE repair ledger: 5.0.8128 → 5.0.8142

Baseline: 5.0.8127 (`097284d69`). Every build below was merged onto `main` and `claude/fix-traders-buying-vw6Oc` in order; no earlier repair was reverted.

**Verification key**

- **T**: covered by new JVM behaviour tests that run in CI.
- **S**: covered by source/wiring assertions only.
- **L**: needs new live closes to establish profitability. Historical replay was not possible because forward labels and the live ledger live on the device, not in the repo.

## CI status

| Build | Result |
|---|---|
| 8129–8138, 8140 | green |
| 8139 | Emulator start-up timeout only. Its code was confirmed healthy by 8140, which passed the same emulator step. |
| 8141 | One defective new test (stale 7388 moonbag numbers). Fixed in 8142. |

## 8128–8135: mark integrity, entry hygiene, crypto funnel

| Defect | Cause | Fix | Verification |
|---|---|---|---|
| Phantom peaks (SAC 0 → +1988% on one tick) | The peak ratcheted from a single feed | `PositionDisplayTruth8078.peakStepCorroborated8128`: a step that more than doubles past +100% needs a two-feed price | T |
| The same phantom wrote every other high | Only `ratchetPeak8080` was gated | 8133 `highStepOk8133` / `acceptedHigh8133` added to Executor highestPrice (×3), ExitIntelligence, TradeIdentity, ShadowLearning, ProjectSniper, LaneShadowProof, CallSweeper, universal sweep, TailHunter, ForwardReturnLabeler | T + S |
| Single-source data blind spots | One provider per capability | 8129: Raydium pools, Jupiter holders, local tape bars, emergency sweep rows | T |
| Re-buys into a downtrend; phantom 1.0777 SOL drawdown peak; FREEZE_UNVERIFIED buys | No loser memory; marked peak; public RPC first | 8130: `BIG_LOSER` / `FALLING_KNIFE`, gain-free drawdown peak plus re-anchor, Helius-first live freeze proof | T |
| Runners refused for being the wrong shape (TREASURY role floor, phase hand-off) | A fit refusal was terminal | 8131/8132: `laneFitRefusal8132` hands the coin to the lane that wants it. Judgement refusals never hand off. | T |
| Chart-BUY path skipped the rebuy lockout | Lockout ran only inside LiveEdgeGate | 8134: lockout at `PreTradeHardGate.requireLiveBuyAllowed` | S |
| Crypto-alt paper/live read 3× per signal; silent drops | — | 8135: mode flip fails the sealed intent; `CRYPTO_DROPPED_8135_*`; real lockout labels | S |

## 8136: authority proofs, frozen holdings, memory, telemetry

| Defect | Fix | Verification |
|---|---|---|
| A null proof could mean unasked, unanswered or stale; mutable Token-2022 authority cached as clean | `AuthorityProofState8136` (CLEAN / HOSTILE / UNANSWERED / STALE / CONTRADICTED); mutable extensions get a 60 s TTL; contradiction blocks; `SingleFlight8136`; bounded `AuthorityProofPrefetch8136` | T |
| Frozen account ghost-closed as zero balance | `FrozenHoldingGuard8136` in BalanceProofPoller | T |
| 511/512 MB heap with no pruning | `RetentionPolicy8136`: pressure-ranked pruning protects open and admission candidates; escalates when a hard trim recovers nothing | T |
| False `FDG_FANOUT_EXPLOSION` from fallback counters | `FdgFanoutContract8136`: only unique decisions can raise the fault | T |
| A DNS failure locked the host for 5 min; 429 bursts climbed the ladder | Stepped DNS backoff; cooldowns only extend | T |

## 8137: admission record and launch observation

| Defect | Fix | Verification |
|---|---|---|
| Generic score floor refused proven coin-specific edges, and the executor floor refused them again | `CandidateAdmission8136`: one ADMIT / REFUSE / DEFER_DATA decision. A supported specific edge clears the FDG floor and records lane admission. A specific refusal is never bypassed lane-wide. | T |
| DATA_INCOMPLETE treated as a refusal | `DATA_INCOMPLETE_DEFER_8136`: bounded (40 re-checks / 10 min, resets after 30 min). Local-tape plan bars come from real trades only. | T |
| Repeated price prints counted as buyers; unknown buyer flow read as zero | `LaunchEvidence8136` provenance (swap vs price-only); trade frames de-duplicated; unknown ≠ 0 | T |
| Band crossing invisible when PumpPortal trades were AUTH_DENIED | Curve-reserve / trade-mark sweep (fixed 1B pump supply, at most 30 s old) fires the same `bandEntry8118` | T |
| A failed intake used up the mint's only band attempt | PENDING / ACKNOWLEDGED / RETRYABLE / TERMINAL claim states, settled by real intake outcome | T |
| IGNITION checked before PRE_IGNITION | `LaunchLifecycle8136`: ordered, never regresses. The sniper assesses PRE_IGNITION and proven IGNITION; everything else gets a named hand-off. | T |

## 8138: learning attribution and equity

| Defect | Fix | Verification |
|---|---|---|
| `specialistLearningMissing` after restart | `AateEntryIdentity8136`: sealed entry identity persisted and restored at finality. Unverifiable closes are kept out of strategy learning but their economics still count. | T |
| Duplicate terminal results possible per position | `PositionTerminalOnce8136` in the finalized bus | T |
| Cortex STRONG kept authority despite losing realised closes | `CortexRealisedAuthority8136`: authority requires a realised whole-position record | T |
| Deposits read as profit; one-reading marks moved drawdown | `CashEquity8136` component split; gain-free drawdown basis; external-flow log | T |
| Any positive Kelly waived the drawdown shrink | The waiver requires a mature realised edge (n ≥ 10, mean − SE > 0, net SOL > 0) | T |
| No record of which cap set the size | `bindingConstraintOf8136` on the LiveRiskPolicy status line | T |

## 8139: specialist election and runner exits

| Defect | Fix | Verification |
|---|---|---|
| READY proposals vanished (SHADOW_NOT_PRIMARY) | `SpecialistElection8136`: one record per candidate version; every proposal ends in one of 7 named terminals; per-lane D > Q > E/F/R/S/X/DD/I counts | T |
| Many rescues; raw cross-model scores compared directly | One rescue slot (`rescueClaim8136`); lane-relative `normalised8136` | T |
| Every meme-lane position treated as a runner from entry | `runnerBasis8136`: style sealed at entry, or a verified +100% peak | T |
| Hold overlays could suppress emergencies | `tierOf8136` / `arbitrate8136`: 8 tiers; tier 1 never held | T |
| 50% sold at 2× did not recover cost after fees, so the recovery sale repeated | `recoveryTranche8136` sized net of costs and confirmed proceeds | T |
| Overlapping ladders sold the residual away | `budget8136` keeps 20% of the original quantity for confirmed runners | T |
| Losing exits left an unprotected 25% tail | `TailBag8034.funded8136` | T |

The replay model inside the tests gives indicative numbers only, not live proof (L):

| Path | Net, old → new |
|---|---|
| +40% spike, then collapse | −1.8% → +25.3% |
| Sustained +500% | +136% → +183% (sealed runner +266%) |
| +100% reversal | +69.7% → +34.5% (the old policy wins here) |

## 8140: age and liquidity by lane ("not only a pump.fun bot")

| Defect | Fix | Verification |
|---|---|---|
| V3 refused anything older than 6 h in live before scoring, blocking QUALITY / BLUECHIP / DIP_HUNTER / TREASURY / CORE / MOONSHOT / CYCLIC / CASHGEN | `LaneAgeLiquidityScope8140`: the age window refuses only when the real pool is < $2k | T |
| Launch lanes judged age by first-seen time | True chain birth; an unknown birth defers | T |
| An unread pool was read as zero or low | Fetch first (LiquidityTruth8037, Raydium TVL), then `LIQ_UNHYDRATED_DEFER_8140`; lane floors labelled `LANE_LIQ_BELOW_FLOOR_8140_<LANE>`; the $500 exit-safe floor and TRUE_ZERO are unchanged | T |

## 8141/8142: integration gaps

| Defect | Fix | Verification |
|---|---|---|
| A MOONSHOT moonbag could hold TOKEN_FROZEN / LIQUIDITY_PULLED | `Tier1Deference8141` added to every Executor/BotService hold gate | T + S |
| Frozen holding kept its slot forever; the 8122 buy block was silently soft | `FROZEN_HELD_UNSELLABLE_8141`: one terminal with zero recoverable value, slot released, real blacklist; a thaw re-opens the sell | T |
| A second rescue could come through `selectOneRescue` | `boundedRescue8141` goes through the election | T |
| Legacy-fallback profit-lock proceeds missing from the recovery tranche | `RecoveryFallbackLedger8141` (confirmed LANDED fills only) | T |

## Still open or external

- **PumpPortal trade feed:** AUTH_DENIED until the key's wallet holds ≥ 0.02 SOL. This is the owner's action.
- **Historical replay:** requires exporting the device's forward-label and ledger files. No profitability claim is made until then.
- **Frozen registry:** in memory only. After a restart, a thaw is not auto-detected.
- **FDG zero-liquidity check:** `FinalDecisionGate.evaluate()` still reads unread liquidity as zero. That body is off-limits to edits; upstream fetching and deferral cover it.
- **Pinned lane-level runner rules:** `LanePlaybook7907.setupHoldsLong8080` and `ForwardReturnLabeler7731.runner8106` still use the lane-level runner rule because those lines are pinned.
- **Crypto-alt pre-FDG verdict:** a WATCH-score candidate is still refused under `CRYPTO_DISCIPLINE_REFUSED_7835`, which names the wrong cause.
