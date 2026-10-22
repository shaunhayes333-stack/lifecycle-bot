# Lane cheat sheets (V5.0.8323)

Owner: "It's meant to favour the good setups and rotate away from the bad ones. Common sense. All specialists,
traders and Cortex lanes are meant to have an individual tailored cheat sheet that the lane, trader, specialist,
token hunter, scanner and brains are meant to reference."

`engine/cortex/LaneCheatSheet8323.kt` holds one sheet per lane: the twelve playbook lanes SHITCOIN, MOONSHOT,
EXPRESS, PROJECT_SNIPER, MANIPULATED, QUALITY, BLUECHIP, DIP_HUNTER, TREASURY, CASHGEN, CYCLIC and CORE. Each sheet
is built from the source that already owns each number. It keeps no second copy, with two small exceptions:

- CYCLIC/CORE bands and the EXPRESS hunter liquidity floor had no owner except literals in LaneHunter7297. They
  moved into the sheet, and the hunter now reads them from there.
- The ideal hunter ages moved into the sheet in the same way.

## What a sheet holds

| Part | Source |
|---|---|
| Stages | `TokenMetricStageRouter.LANE_STAGE_SHEET_7928` |
| Mcap band | The trader's own constants (`ShitCoinTraderAI`, `QualityTraderAI`, `BlueChipTraderAI`, `DipHunterAI`, `MoonshotTraderAI`, `TreasuryScannerFeed`, `ShitCoinExpress`, `ProjectSniperAI`, `ManipulatedTraderAI`). At routing time they are widened by `LaneHunter7297.floorFor/ceilingFor` wherever the lane's closes earned a wider band |
| Liquidity floor | The trader's fluid floor where it has one (ShitCoin, BlueChip, Manipulated, Moonshot via `HunterMandates8293`), otherwise its constant |
| Birth-age band | The trader's age rule (ShitCoin 72h bootstrap, sniper 15s–180s window, Manipulated fluid 12→8 min, Quality 5 min+, DipHunter 2h+, Cyclic 12 min+) |
| Venues | Curve / PumpSwap / Raydium / Meteora for each lane |
| Setup menu | `LanePlaybook7907` menu: each setup's cheat-sheet prior plus its record (see below) |
| Verdicts | FAVOUR / NEUTRAL / AVOID, rotated automatically |
| Entry timing | Early-buyers bar `LaunchTape7921.earlyPass8165` / `priorPass` on launch desks, stage-from-tape `TapeStage8316`, chase rule `timingBand8094` (PEAK avoided by runner lanes, PEAK/EXTENDED by the rest; dip and quality desks buy PULLBACK) |
| Exit doctrine | Runner vs scalp (`RunnerExitProfile7277`), stop and target (`FieldManual7715.mandateFor`), trail arm (`giveBackArmPct7791`) |
| Hunter mandate | Band, age/liquidity fit, ideal freshness |
| Scanner sources | `SOURCE_LANES_8323`, the source→lane map moved out of `TokenMergeQueue.inferLaneAffinity` |

## Records and ranking

- **Forward labels.** The record of each setup is `LanePlaybook7907.sheetRecords8323`. This is the same executable,
  8295-governed read that every authority uses: the lane's label-to-bank offset is taken off, losing live closes are
  blended in, and the hive is capped.
- **Live closes.** These come from `Cortex7885.setupRealised8187` and are read on a LIVE account only. Paper closes
  never reach a favour list.
- **Runner average.** Every graded runner label (`LanePlaybook7907.onLabel` → `noteLabel8323`) feeds the runner average.
  The "non-runner average" (the median side of the record) is derived from it for display.
- **Ranking.** Setups are ranked by a tail-aware expectancy: the shrunk mean net of executable cost, never the median. A
  runner lane's edge is its tail.

## Rotation (`judge8323`, with hysteresis)

- **FAVOUR** requires all of these:
  - 20 or more labels;
  - mean − SE above max(prior, 0) + cost;
  - a record that is not outlier-suspect;
  - the setup's live closes not losing.

  A favoured setup stays favoured while its mean stays above cost.
- **AVOID** on a clear loss, meaning any of these:
  - mean + 2 SE < 0 on 12 or more labels;
  - mean + SE < 0 on 30 or more labels;
  - the setup's own live closes losing: 2 or more closes with a negative mean, matching the 8291/8295 governors.

  An avoided setup stays avoided until its measured mean is back above cost.
- **NEUTRAL** otherwise. In that case the existing rules decide.
- **Re-probe.** An avoided setup re-probes on the lane's exploration slot (`reprobe8323`):
  - in paper the slot is always free; in live the slot is `MinPromotion8188.explorationSlotFree8316`;
  - at most one coin per (lane, setup) every 20 minutes;
  - the grant holds for that coin for 120 s.
- **Persistence.** Verdicts are saved under `LANE_CHEAT_SHEET_8323` so that hysteresis survives a restart. The records
  stay in their own books.

## Consumers

| Consumer | Reads |
|---|---|
| `TokenMergeQueue.inferLaneAffinity` | `SOURCE_LANES_8323`, then `routeLanes8323`. A coin is offered only to lanes whose mandate fits: mcap band with 20% tolerance, liquidity at least half the floor. A sub-$10k launch no longer reaches QUALITY |
| `LaneHunter7297` | Every profile's band is `mcapMin8323/mcapMax8323`. Rows must pass `hunterFits8323` (age band and liquidity floor now). Freshness ranking uses `idealMaxAgeHours8323` |
| `LanePlaybook7907` classifier, rerank and `bestSetup8049` | `choose8323`: favoured fired setups first, avoided ones dropped (unless nothing else fired) |
| `LanePlaybook7907.strategyPick8153` → each lane trader's strategy identity | `pickWith8323`: best evidenced non-avoided setup with FAVOUR first, else a favoured setup, else the pick. An avoided pick rotates to an alternative |
| `CandidateAdmission8136` | `sheet8323`: FAVOUR supports (`sheetFavour8323`: favour bar on the record past the realised shortfall, timing band not losing). AVOID refuses (`SHEET_AVOID_8323`) unless a supported or favoured alternative exists. NEUTRAL falls through |
| `TacticSwitcher.rotate` | `rotationTarget8323`: rotates to a favoured setup's tactic, skipping shapes that only avoided setups trade as |
| Cortex | One lane-free voter per lane, `SHEET_FAVOUR_<LANE>` (in `CortexVotersWide8004`): +1 if a favoured setup fired, −1 if the coin's setup is avoided, 0 otherwise. It is graded like any voter: an input, not an authority |
| Report | `cheatSheet8323[<lane>: cap… liq… age… stages… runner/scalp stop… favour=[…] avoid=[…] rotated=…]`, one line per lane, 420 characters at most. Written under "lane playbooks (§7907)" in the Cortex scoreboard (a protected section) |

## Per lane

| Lane | Cap band | Liquidity floor | Age | Stages | Venues | Exit |
|---|---|---|---|---|---|---|
| SHITCOIN | $500–$500k | fluid $300→$2k | ≤72h (bootstrap) | FRESH_LAUNCH, BASE_START | curve, PumpSwap, Raydium, Meteora | runner, stop 15%, trail armed at +20% |
| MOONSHOT | $1.5k (fluid → $10k)–$5M | fluid $2k→$15k | — | FRESH_LAUNCH, CONTROLLED_MARKUP | all | runner, stop 15%, trail armed at +50% |
| EXPRESS | $1k–$300k | $1k | — | FRESH_LAUNCH, BASE_START | all | runner exits, stop 10% (momentum mandate) |
| PROJECT_SNIPER | $3k–$500k | $2k–$250k | 15s–3 min | FRESH_LAUNCH, BASE_START | curve, PumpSwap | runner, stop 15% |
| MANIPULATED | $5k–$300k | fluid $1.5k→$3k | ≤12→8 min (fluid) | — | all | runner, stop 15% |
| QUALITY | $75k–$1M | $3k | ≥5 min | MID_ACCUMULATION, CONTROLLED_MARKUP | PumpSwap, Raydium, Meteora | scalp, stop 10%, buys PULLBACK |
| BLUECHIP | ≥$1M | fluid $7k→$15k | — | MID_ACCUMULATION | PumpSwap, Raydium, Meteora | scalp, stop 10%, buys PULLBACK |
| DIP_HUNTER | $50k–$5M | $10k and 10% of cap | ≥2h | MID_ACCUMULATION (PEAK_EXHAUSTION/DUMPING only with a reclaim) | PumpSwap, Raydium, Meteora | runner exits, stop 10%, buys PULLBACK |
| TREASURY | ≥$1M | $50k | — (prefers 7d+) | MID_ACCUMULATION | PumpSwap, Raydium, Meteora | scalp, stop 5% |
| CASHGEN | ≥$1M | $50k | — (prefers 7d+) | — | PumpSwap, Raydium, Meteora | scalp, stop 5% |
| CYCLIC | $10k–$5M | $5k | ≥12 min | — | PumpSwap, Raydium, Meteora | scalp, stop 10% |
| CORE | $1.5k–$5M | — | — | all clean stages | all | runner exits (ensemble), stop 10% |

The values in this table are the cold-start constants. The live sheet reads the fluid floors and the earned band
widening at run time, and the report line shows the numbers actually in use.

## Not touched

- Hard safety (rug, authority, sellability, liquidity floors, identity), the route minimum, execution economics and
  `FinalDecisionGate.evaluate()`.
- `Cortex7885`, `CortexLedger7885`, `CortexVoters7885`, `ForwardReturnLabeler7731` and `RobustLabelRead8185`. The
  voters were registered in `CortexVotersWide8004` instead.
- BotService and Executor.

The only change to the traders' constants is visibility (`private` → `internal`), so the sheet can read them.
