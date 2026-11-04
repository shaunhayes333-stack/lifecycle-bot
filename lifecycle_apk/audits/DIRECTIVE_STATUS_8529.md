# AATE full-system directive — status at 5.0.8530

Status: **NOT COMPLETE.** This file records what is verified, what changed, and what remains. Observed facts are marked
(F); hypotheses (H).

## Baseline

- Directive reference: main `fba703a65` (5.0.8510). Work is on current main (5.0.8528 `623b8d857` → 5.0.8529); no
  rollback.
- Runtime evidence used: 8527 pipeline log (2026-11-05 00:38): 16 live closes, 3 W / 13 L, PF 0.36, −0.0020 SOL/trade,
  wallet 0.1307 SOL, admitted forward −12.7 % vs refused −0.5 % (F).

## Defect ledger

| # | Defect (evidence) | Root cause | Change | Test | State |
|---|---|---|---|---|---|
| D1 | Buy-time chase checks fail open (directive §4C) | Verified: on 8528 `preBroadcastRefusal8329`, `jupiterFloorRefusal8521`, `pumpFinalRefusal8521`, Raydium `RouteEconomicsRefused` refuse on missing / stale quote or economics (F) | none | existing 8521 tests | CLOSED, no defect on HEAD |
| D2a | Entries bought the top; STONKS bought after +70 %/h (F) | `StrategyMove8153.expected8153` used the hour's realised move as the move still ahead (F) | `remaining8529` / `hourPosition8529`, wired in `NetEdge8030.executableMove8153`; STRATEGY basis + launch window unchanged | `Aate8529EntryExitTruthTest` | CHANGED, not live-proven |
| D2b | HonEQ5 −1.9 % stop-out, re-bought 14.5 min later, −25 % (F) | 24 h lock only at ≤ −10 % realised (F) | `RebuyLockout8019.stoppedLoss8529`; bus passes `exitReason` | same | CHANGED |
| D3a | 12 emergency stops cancelled by quote; overruns −21/−25/−30 % vs −15 % (F) | `quoteRefutes8521` cancelled on any quote above the line (F) | refute only when quote loss < ½ stop depth | same + 1 superseded 8521 line | CHANGED |
| D3b | Raydium 0/3, PumpPortal 1/3 landed, still first (F) | static venue order (F) | landing-rate demotion (`SellRouteEndpoint8144`), reported as `routesDemoted8529` | same | CHANGED |
| D3c | CATASTROPHIC queue avg 34.5 s / max 198.6 s (F) | unknown — causes recorded but never printed (F) | `ExitStageTiming7876` prints `queueCauses` | — | OPEN (measurement only) |
| D4 | 29jQSL canonical, chain 0; npz2 sold (sig `2A74sz…`) yet chain still holds 272843664 raw (F) | unresolved; needs the transaction (H: sell booked against the wrong row, or a stale / incomplete token read) | none; self-heal paths exist (`ghostZeroHandOff8288`, `requestAdoptionAsync7718`) | — | OPEN (blocked: no Solana RPC from the build sandbox) |
| D5 | Shadow means +3063 % / pf 252–398 | owner: +40,000 % trades are real; the 8529 cap was withdrawn (8530) | none net | pf-at-real-size test | CLOSED (not a defect) |
| CI | main 8528: 3 failed of 8478 | stale source pins of code 8528 replaced (F) | pins superseded to the 8528 contracts | the 3 tests | CHANGED |

## Inventory (directive §2) — tool output, 8529

- `ci/triage_unwired.py`: 4,433 public functions with no external caller — A_PREDICT 77, B_RISK 47, C_EXIT 129,
  D_DISPLAY 137, E_INFILE 2,535, F_DEAD 1,508 (`ci/UNWIRED_LEDGER.tsv`).
- `ci/audit_unread_intelligence.py`: 118 modules, 286 unread analysis functions.
- `lifecycle_apk/ci/v5_0_6711_full_authority_audit.py`: `audits/v5_0_6711_full_authority_audit.md` regenerated.
- Hand-verified so far (a text tool over-reports): `SellOnlySafeMode.blockLiveBuyReason` — intentionally dead since
  4028, hard safety lives in the real gates; `TradingMemory.isCreatorBlacklisted` — superseded by the graded read in
  `PredictiveEntryOracle6915` (6927). Both SUPERSEDED BY DESIGN, not broken.
- Not done: the per-component FILE → … → VERIFIED STATUS table for every decision-relevant component.

## Open (directive)

1. §2 per-component inventory with verified runtime status (remaining A/B/C tiers: 251 functions unverified).
2. §3 money-path traces per trader (first sight → learner acknowledgement), with negative paths.
3. §4A phase gate vs specialist setups: 8528 added `nativePhaseWitness8528` (NO_PHASE_TRIGGER only); the other waits
   (NO_RECLAIM 1021 refusals, NO_BUY_VOLUME) not yet tested against fired setups.
4. §4B strategy-output authority map; §4D label starvation (66 % marks priced); §4E native last-mile (TREASURY +2.1 %
   forward, 0 bought); §4F runner chain end to end.
5. §5 single authority per decision question.
6. §7 replay 8114 / 8510 / repaired on frozen candidates.
7. §8 per-lane end-to-end fixtures beyond the existing 12-lane journey test.
8. §9 cycle time (avg 5.6 s, max 51.6 s), catastrophic queue (D3c), buy CONFIRMED→VERIFY_POLL 2.6 s.
9. D4 on-chain check of `2A74szKP1ZsusBNbbyDWoB6WvxEkKrm7LmTveF3TrttbeCUZLiJ45FUKkwudGeWQcHvqhPjc6FobCoQZvLvN9dgp`.

Live profitability: NOT PROVEN.
