# DIRECTIVE LEDGER 8367

V5.0.8367 audit of owner directives and fix-block items from 379ad2fa0 (the profitable 8114 build) to f90c3909d (5.0.8363).
For each one: where it was introduced, whether HEAD still does it (call path reached, constant unchanged, not shadowed by a
later gate), and, if a later commit changed it, whether that commit said so.
Guard: `app/src/test/kotlin/com/lifecyclebot/engine/Aate8367DirectiveRegressionTest.kt` (test `dNN`). Each test calls
the behaviour itself, not a line of text.

**Headline: 32 directives tracked: 28 IN FORCE, 3 WEAKENED (all deliberate), 1 REVERSED (deliberate). No accidental reversal found.** Directives that had been undone earlier and are now back in force: RAPID_FLUID_STOP (8327, restored by 8337), the 15-minute checkpoint (7997, restored by 8357), paper beside live (8184/8216, restored by 8336).

Status: **IN FORCE** = HEAD behaves as directed. **WEAKENED** = still there but narrowed or bypassed. **REVERSED** = undone.
"Deliberate" means the reverting commit's message, or an owner branch, says it chose the change.

## Seed directives

| # | Directive | Introduced | Status at HEAD | Reverting / altering commit | Action |
|---|---|---|---|---|---|
| 1 | Hard safety never loosened (freeze/mint proof, Token-2022, rug, sellability, exit floor) | 8123 7441a45e9, standing rule | IN FORCE. The 8342 rehearsal relaxes only evidence doors, only in PAPER (`Rehearsal8339.evidenceDoorBinds8342`, which always binds LIVE). | none | d08, d17 |
| 2 | No paper into live decisions or sizing | 8184 ffaf5a340, 8216 cb6219554 | WEAKENED (deliberate). 8336 lets paper desks trade beside LIVE again (`LiveNoPaper8184.enforced8184=false`). 8336 EvidenceHold gates live on paper proof (restrictive only). 8344 lets a lane shut by its live losses re-earn LIVE on the shadow record (`Entry8114Mode8326.decide8326`, `shadowProven8344`). Sizing reads live closes or labels, never a paper balance. | e0a3a72d6 / 370a043c0 (owner branch paper-beside-live-8336), 9c1fdeca0 (owner branch evidence-shadow-8344) | recorded. The 8344 unlock is in the live-vs-shadow / FDG agents' files. |
| 3 | 8114 entry mode live (owner chose) | 8324 ffb3010e5 | IN FORCE (`BotConfig.entry8114ModeEnabled=true`). Narrowed: 8364 a1251531b requires a named fired setup for a LIVE admit (`Entry8114Gate8326.liveFiredSetupRefusal8364`). 8114 admitted on `chartAdmits7950` without one. | a1251531b (deliberate: audit 8360 item 2) | d12. Handed to the FDG agent if the owner wants exact 8114 parity. |
| 4 | Curve impact priced on virtual reserves | 8337 FIX 1 d9928a316; 8350 f9cf688d7 grades | IN FORCE: trade path, labels, playbooks, LiveRiskPolicy, CortexExit, LaneShadowProof. | none | d01 |
| 5 | Flat marks never booked as -cost labels | 8337 FIX 2 970ddfe0b; 8342 8c648e653 (every slot) | IN FORCE: 15, 60 and 240 slots. | none | d02 |
| 6 | Profit protection the cost model cannot switch off | 8337 FIX 3 43f280662; 8363 b2c072c20 | IN FORCE: `runnerAwareStop7935`, `runnerProtectFloor8169`, protective pass-through in `exitRefusal8030` / `profitTaking`, `ProfitLock8363`. `RUNNER_PEAK_CAPTURE_7980` passes through `protective8144`. | none | d03 |
| 7 | Runner give-back lock at +25 (was 50) | 8337 FIX 3b | IN FORCE | none (this commit itself changed 8114's 50) | d04 |
| 8 | Runner hard stop: 60 s grace | 8337 FIX 4 | IN FORCE (`hardStopWaits8337` reached in `heldExit7967`) | none | d05 |
| 9 | Entries pay to land (`entryOrdinary8151=false`); landing window 2 h / 3 rows | 8337 FIX 5 6229a52d9 | IN FORCE | none | d06 |
| 10 | 8114 gate: grab-only runner exemption, chase exemption only for early-run AND grab | 8337 FIX 6 | IN FORCE (`Entry8114Gate8326` lines 65, 80, 107) | none | (FDG agent's file; not re-pinned) |
| 11 | Chart BUY goes through the 8114 decision | 8337 FIX 8 | IN FORCE (`TradePlan7739.liveBlockReason` line 414) | none | — |
| 12 | Exit door order: net-profit and uneconomic doors before the share budget | 8337 FIX 9 | IN FORCE (`Executor.requestPartialSell` ~24016) | none | — |
| 13 | RAPID_FLUID_STOP on the normal loss path | 8337 FIX 10b (undid 8327) | IN FORCE | 8327 was the earlier reversal; 8337 restored it | d08 |
| 14 | Runner-from-entry sells the scalp tiers until the plan is sealed AND continuation confirmed | 8337 FIX 10a | IN FORCE | none | d07 |
| 15 | 15-minute grading checkpoint (1m/5m/15m/1h/4h/24h, 48h/72h/7d on tokenomics) | 8357 3fe23cc55 | IN FORCE (`CK_MS_7997`, `checkpointDue8013`) | 7997 had dropped the 15 m slot; 8357 restored it | d09 |
| 16 | PumpPortal per-token trade stream off | 8354 5d1c66898 | IN FORCE. Held curves get marks from HeliusCurveTape8354 (`caresAbout8354`). | none | d10 |
| 17 | Keyless-first market reads | 8359 1e9692a22, 8360/8361 cadence | IN FORCE (recent; pins in Aate8359KeylessDataTest) | none | — |
| 18 | slippage / minLiquidity / walletReserve owner-only | 8353 1d2aec230, 8363 b2c072c20 | IN FORCE. No writer outside the UI and the one-shot restore. SentienceHooks filters through `isAllowedKey`. | none | d11 |
| 19 | Green floor and give-back locks protective | 8363 b2c072c20 | IN FORCE | none | d03 |
| 20 | Log-optimal fractional Kelly | 8365 d0aef91ea | IN FORCE | none | d13 |
| 21 | Cortex single walk-forward authority | 8366 dcc0eb748 | IN FORCE | none | d14 |
| 22 | Evidence hold + shadow unlock | 8336 / 8344 / 8347 / 8351 | IN FORCE (owner branches) | none | (other agents' files) |
| 23 | Runner capture: spike tiers +40/+120/+400, runner ladder 100/400/1000, give-backs 7980/8104 | 8114-era | IN FORCE (constants unchanged, reached via `tiersFor7967`). WEAKENED (deliberate) by the 8363 green floor, which releases a held runner at max(net BE, 50 % of peak); 8114 held to 35 % under the peak price. Also by the 8351 ScalpExit on SCALP-proven lanes, which the runner hold defers while the run stands (`deferSell7967`). | b2c072c20 (audit 8360 item 4), 810f312c3 | d07 |
| 24 | Ticket retry / event-driven resume | 8325/8327, 8330 | IN FORCE (`TicketRetry8327`, resumes registered at the three meme buy paths) | none | d18 |
| 25 | Hand-off resume at every TokenState / liquidity write | 8345 6ec77bc14 | IN FORCE (13 direct writes at 8345 and at HEAD; intake / poll / overview through `HydrationReeval8343`) | none | — |
| 26 | Momentum read unobserved: DEFER | 8348 af8380387 | IN FORCE (`SpecialistBrainBridge7542` line 356) | none | d15 |

## Further directives found in the history

| # | Directive | Introduced | Status | Altering commit | Guard |
|---|---|---|---|---|---|
| 27 | Loss-streak cooldown is a setting, off by default | 8117 372aff553 | IN FORCE | — | d12 |
| 28 | Entry economics 1.5x round trip (owner) | 8197 42b071d67 | IN FORCE (`NetEdge8030.EDGE_MULT`) | — | d12 |
| 29 | 8114 doors and backstops bind until earned on realised live closes (9 % fresh meme, 15 % hard / MOONSHOT early) | 8298 20f802203 | IN FORCE (ExitDoctrine8169, LiveEdgeGate7877, FDG cell / fresh-launch doors) | — | d16 |
| 30 | First executable label is the immutable 5-minute target | 8272 0d72fb641 | REVERSED for runner lanes (deliberate): 8337 10e grades MOONSHOT / SHITCOIN / EXPRESS / PROJECT_SNIPER on the hour slot. 8357 revises at 15 m. | 970ddfe0b, 3fe23cc55 | — |
| 31 | Rehearsal never relaxes LIVE (wallet door, evidence doors) | 8339 / 8342 | IN FORCE | — | d17 |
| 32 | Max-take liquidity semantics (V5.9.769) | V5.9.769 | IN FORCE through `intakeLiquidity8345` | — | — |

## Where HEAD trades differently from 8114, and why

1. **Entry:** live needs a named fired setup (8364, deliberate). The evidence hold keeps unproven lanes out of LIVE (8336, owner). An explore-variant entry wait and niche limit apply (8347/8351, owner branches). 8114 had none of these.
2. **Sizing:** log-optimal Kelly with one shrink (min of the brakes, not their product), deliberate in 8365. 8365 also refuses a stake below 25 % of the route minimum unless a named reason applies (`KELLY_TOO_SMALL_FOR_ROUTE_8365`, `EntryEconomics8329.promotionReasonRefusal8329`). At 8114 a small stake was promoted to the route minimum. This is a deliberate new refusal in the FDG agent's file. Handed off for a parity decision.
3. **Exits:** green floor at 50 % of the peak (8363); give-back lock at +25 (8337); scalp tiers until the run is confirmed (8337); ScalpExit8351 on SCALP-proven lanes; RAPID_FLUID_STOP normal (same as 8114). All deliberate.
4. **Paper beside live:** 8114 traded paper beside live. 8184/8216 forbade it; 8336 restored it (owner branch).

## Accidental reversals

None found outside the other agents' files. Every change to a seed directive after it was introduced is either named in
the altering commit's message or comes from an owner branch. Two suspects were checked and ruled out:
`RUNNER_PEAK_CAPTURE_7980` against the 8163 requote test (already protective through `ExitTruthArbiter8144.protective8144`),
and ScalpExit8351 against grab-held runners (deferred by `RunnerGrab7967.deferSell7967` while the run stands).

## Handed to other agents (no edit made here)

- **FDG agent:** `Entry8114Gate8326.liveFiredSetupRefusal8364` (a1251531b) narrows the owner-chosen 8114 mode. `EntryEconomics8329.promotionReasonRefusal8329` / `Kelly8365.KELLY_TOO_SMALL_8365` (d0aef91ea) refuses small stakes that 8114 promoted. Both are deliberate; they need an owner ruling, not a code fix.
- **Live-vs-shadow agent:** `Entry8114Mode8326.decide8326` `shadowProven8344` (9c1fdeca0) lets the shadow record lift the 8329 realised-live-loss shut. This is owner-directed, but it is the one place a simulated book overrides realised live money. Also confirm that shadow closes do not feed `CostLedger7962` (used in live edge cost).
