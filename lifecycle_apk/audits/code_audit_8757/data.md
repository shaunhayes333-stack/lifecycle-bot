# Data-integrity audit: price / mark / fact sources and labels

Repo: `/home/user/lifecycle-bot` @ `916e8eb71` (5.0.8757), read-only. Paths below are relative to
`lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.

Already-fixed causes, not re-reported: 8627 (intake cap/supply seed used as the label basis at `observe()`) and 8657/8658
(pump.fun curve math on other launchpads' PumpPortal frames). Where a finding is a leftover of one of these, it says so.

## 0. Limits of this audit

* **I could not run the dataset analysis.** The edge export
  (`ff64e556-aate_edge_dataset_8537_1794244417905.jsonl.txt`) is not on disk. The upload directory holds only the
  pipeline log, and `.saved-uploads.jsonl` lists only that log. No search of the filesystem found it. Every
  dataset-based claim below is therefore a **hypothesis traced to code**, plus a script (section 5) that confirms or
  rejects it in one run once the file is attached.
* Runtime evidence comes from the pipeline log (`aa487cd1-…_150239_790.txt`, build 5.0.8756, uptime 333 s, LIVE).

### Log facts used as evidence

| Line / section | Fact |
|---|---|
| G INPUTS, `tokenFacts8551` (l.79) | liq refused `OLDER_THAN_HELD=1486`, `HELD_BY_STRONGER=974`. Price refused `OLDER_THAN_HELD=434`, `HELD_BY_STRONGER=379`. Price accepted `PUMP_FUN_BC_SYNTHETIC=24`, `CHAIN_SUPPLY_CAP_SEED_7280=16`. Liq accepted `CHAIN_VAULT_8038=26970`, `CURVE_READ_8046=7076`, `CURVE_ACCOUNT_7951=2965`, `CURVE_FRAME_7951=688`, `DEX_PAIR=349`, `CURVE_PRICE_IMPLIED_8173=104` |
| label coverage | `labelCoverage8535[h1m=663/854 h5m=6/159 …]`: only 6 of 159 five-minute reads were priced this session |
| quiet curve | `quietCurve8750[served=164 streamGaps=0 continuousForS=320]`: 164 marks fabricated as "now" in 5 minutes |
| print witness | `printWitness8750[reserves=6791 witnessed=1653 out_of_band=10 unwitnessed=1330 …]` |
| birth | `TOKEN_BIRTH_HYDRATION_PENDING_7440: 215,024` against ~46.7k resolved (`PUMP_CREATE_EVENT 29,170`, `FIRST_POOL 8,792`, `META 8,756`); `sniper … undated=826` |
| decision price age | `decisionPriceAge8549` MOONSHOT `{122/533/334/49/5/0}`: 388 decisions on 15 s–5 min old prices |
| stage records | PEAK and DUMP are `n1300` (the cap) in every meme lane. LAUNCH/BASE/BREAKOUT are much smaller |
| edge | `admitted[n1300/+25.5%/wr17%/run4%]` "RUNNER-CARRIED"; BLUECHIP "runners ≥ +236.2 %, non-runners ≤ +5.9 %" |
| fan-out | `quotesBySource=[PUMP_CURVE_RPC=3406,DEXSCREENER=1312,RAYDIUM=791,DEFILLAMA=175,HELIUS_DAS=81,JUPITER=6]`, `MARK_CONTESTED_NO_AGREEMENT_7088:7` |
| seeds | `INTAKE_PRICE_SEEDED_FROM_CHAIN_SUPPLY_7089:15`, `INTAKE_PUMPFUN_SOURCE_NATIVE_SEED_6401:2` |
| runtime | `OutOfMemoryError` crash at 259 min. Heap pressure means dropped and backlogged WebSocket frames (relevant to findings 1, 7 and 20) |

---

## 1. P0: corrupts decisions or learning

### P0-1. QuietCurve8750 makes up a chain-witnessed mark stamped "now" from a stale print, so flat −cost labels get booked
* `engine/truth/QuietCurve8750.kt:43-50`: `mark8750` returns `tp.first to nowMs`. The last tape print's price is
  re-stamped with the current clock.
* `engine/truth/ForwardReturnLabeler7731.kt:2176-2182`: this mark enters `typedMark8386` with `independentBasis = true`.
  That means it counts as chain proof: it wins `liveMark8454`, satisfies `independent8418`, and passes the ≥10x witness test.
* `ForwardReturnLabeler7731.kt:1707-1708` (`flat8322 && liveMark8454(...) -> flat8322 = false`) and `:2004-2005`.
  A flat path read from this mark is **booked** as `net = −cost`, not recorded as a lost mark. 8337/8342 fixed exactly
  this case for token-state copies.
* The premise "the program stream saw every trade" is only checked globally (`noteProgramPrint8750`: any decoded print
  from any coin within 20 s). Coin-level gaps all pass that check:
  * logsSubscribe truncation;
  * decode failures;
  * `NetParseGuard8305.dropOom8305` (`network/HeliusCurveTape8354.kt:634`);
  * the bot's own OOM.
* **Effect:** a decision priced from the last print and then "read" at 5 min from the same print books exactly
  `−costPct`. `costPct` is the per-lane constant: `FieldManual7715.labelCostPct8534`, base 1.5 % + slippage
  (`CostLedger7962.costOr7962` returns the estimate). The result is **identical nets across distinct mints within a
  lane**, booked as real outcomes into the cells, EdgeModel, PocketLedger, TreeRanker and the Cortex. Log: `served=164`
  in 320 s.
* **Fix:**
  * Return the print's own `observedAtMs`, and treat a standing price as `liveMark=false, independentBasis=false`.
  * Never let it clear `flat8322`.
  * Gate the standing price on the coin's own signals: per-mint subscription health plus a fresh curve-account read
    (`CurveReserves7951.accountObservation8522`) at or after the horizon that equals the print's reserves.

### P0-2. TreeRanker8737 (the active predictor) trains on rows the edge model refuses: unfilled, pre-8660 venue-poisoned, and mixed-horizon
* `engine/truth/TreeRanker8737.kt:106-129` (`rowsOf8737`) checks only `net` finite and ≥ −100, synthetic lane
  prefixes, and `plausible8617`. It never checks:
  * `"fill"`;
  * `venueScopeEpoch` / `unscopedSolana8661` (the 8657/8660 phantom-curve rows);
  * `horizonMs == targetHorizon(lane)`.
* Compare `EdgeModel8537.kt:597-611` (`replayAccept8642`), which PocketLedger, ManagedBands and PayingCell all reuse.
* `EdgeModel8537.kt:401-418`: `predict8737` returns `Mind8746` first, then the **tree**, then the ridge. So the
  verdicts and the 8715 refusal authority come from a model trained on:
  * fill=false rows, whose entry price was never buyable (`recordUnfilled8546`, `EdgeModel8537.kt:733-737`);
  * pre-8660 non-pump rows carrying pump.fun curve marks (the 8657 bug the replay was built to exclude);
  * a mix of 5-min, 15-min and legacy 60-min targets.
* `engine/lab/StrategyLab8755.kt:73-83` (`row8755`) has the same gap.
* **Fix:** call `EdgeModel8537.replayAccept8642(o)` at the top of `rowsOf8737` and `row8755`. Retrain from scratch,
  and bump `TreeRanker` and `Mind8746` model keys so out-of-sample authority is earned again.

### P0-3. Seed prices still reach the label basis and outcome through TOKEN_STATE (8627 leftover)
* The 8627 guard is applied once, at `observe()`: `ForwardReturnLabeler7731.kt:1219` → `seedBasis8627` (`:322-327`).
  It turns a seed basis into `UNKNOWN`.
* In `tick()`, an UNKNOWN basis adopts the first typed mark. That happens through `adoptBasis8418` (`:3151-3155`) and,
  after the window, through `adoptLateBasis8524` (`:3159-3164`). `typedMark8386` still offers the token-state price
  as a `"TOKEN_STATE"` candidate with **no seed check** (`:2196-2201`, only `ts.lastPrice == px`). With no live market
  mark in age, that candidate is chosen, so the seed becomes the basis anyway.
* The seed is re-stamped "now" on every WS sighting:
  * `engine/BotService.kt:17345-17372` (`writePrice(ts, newPrice, null, AGGREGATE, now)` keeps the seed label);
  * the `TokenFacts.kt:182-185` comment.
  
  Because of that re-stamp it passes the "new path event" (`:3171`), rebase-window (`:3194-3215`) and
  `entryFill8521` (`:1803-1806`) checks.
* `TokenFacts.seedPrice8559` (`engine/truth/TokenFacts.kt:196`) matches only two exact labels. It misses
  `PUMP_FUN_SOURCE_NATIVE_MCAP_SEED_6401`, written at `BotService.kt:26236-26238` (log
  `INTAKE_PUMPFUN_SOURCE_NATIVE_SEED_6401:2`). So that seed also escapes the 8559 seed-yield rule and 8627.
* Seeds that are off by ≥ 10x are dropped as `UNWITNESSED_EXTREME`. **Seeds off by 2–9.9x book**, and nothing
  downstream catches them (see P0-5).
* **Fix:**
  * Add `!TokenFacts.seedPrice8559(ts.lastPriceSource)` to the TOKEN_STATE candidate.
  * Make `seedPrice8559` match by substring (`SYNTHETIC`, `CAP_SEED`, `MCAP_SEED`).
  * Never re-stamp a seed: call `writePrice` with the original observation time, or use `correctPrice`.

### P0-4. Hour-lane 15-minute outcomes are booked into the "5-minute" slot and published as "5-min executable net"
* `ForwardReturnLabeler7731.kt:1984` → `bookEntryTarget8521` → `:1790` `book(o, 60, net, …)`. For an hour-target lane,
  the 15-minute net goes into the `n60` / "5-minute" tallies, and again into `book(o, 240, …)` (`:1999`).
* `engine/cortex/CortexForwardEpoch8521.kt:40-41, 61-62`: `HOUR_MS = 15 min`, `SCALP_MS = 5 min`.
* Readers that treat slot 60 as "5 minutes":
  * the `DiagnosisBrief8438.kt:227` header ("forward 5-min executable net");
  * the lane, cell, stage and door records (`GateOutcome8418.book8418`, `:1376-1378`);
  * `MemeRegime8546` (`:1360`);
  * every `cellStat` consumer.
* The dataset `net` is a 5-min read for scalp lanes and a 15-min read for hour lanes. `horizonMs` is in the row, but
  TreeRanker and StrategyLab ignore it (P0-2), and the owner's description of the export ("net = 5-min") is wrong for
  hour lanes.
* **Fix:**
  * Book hour targets only into a dedicated `n_target` slot keyed by horizon. Key cells by `(horizonMs, …)`.
  * Rename the 15/60/240 slot labels to 1m/5m/60m.
  * Filter learners by `horizonMs`.

### P0-5. No plausibility check covers the 2x–10x band at a 5–15 minute horizon for non-curve coins
* The guards that exist:
  * `LabelEvidence8386.kt:36`: proof is required only at ≥ 10x;
  * `ForwardReturnLabeler7731.kt:212`: `BASIS_MAX_RATIO_7738 = 20` (rebase only);
  * the curve witness works only for **active pump curves** (`:251-258`);
  * `EdgeModel8537.kt:477-487` `plausible8617`: unwitnessed caps of +5,000 % (mcap < $100k), +1,200 % (≥ $100k),
    +400 % (unknown). There is no horizon term and no large-cap tier.
* The 8627 artifacts (+2,600–2,930 % at 5 min) were **below** the +5,000 % cap. Unit or venue errors of 2–10x
  (P0-3 seeds, P1-3 cross-venue, the 2x liquidity scale in P1-1 feeding cost, quote-token mis-valuation) all reach
  every learner.
* `plausible8617` also allows +1,200 % in 5 minutes for a $50M BLUECHIP with no witness.
* **Fix:** make the bound depend on mcap band and horizon (for example ≥ $1M: |net| ≤ 100 % per 5 min unless
  witnessed; ≥ $10M: ≤ 30 %). Require `independent8418` on both marks for any move ≥ 3x (not 10x). Add a same-venue
  check (P1-3).

---

## 2. P1

### P1-1. LIQUIDITY_USD mixes three definitions in one field: curve real SOL ×1, pool SOL side ×2, DexScreener total
* Curve: `LiquidityTruth8037.kt:45-46` and `:87` write `realSol * solUsd` (one side).
* Pool: `OnChainDepth8038.kt:138-139` `depthUsd8038 = 2 * quoteSol * solUsd` (both sides).
* DEX pair (`TokenFacts.kt:382`): DexScreener `liquidity.usd`. This is both sides, and for a pool quoted against a
  thin token it is valued at that token's price (see the 8560 note in `DexscreenerApi.kt:461-466`).
* Every reader treats these as one scale:
  * EdgeModel `LIQ_EDGES` and the age×liq / top×liq crosses (`EdgeModel8537.kt:120, 146`);
  * PocketLedger liq bands (`PocketLedger8610.kt:52`);
  * the PayingCell key (`PayingCell8723.kt:56-60`);
  * `liqBandKey8539` (`ForwardReturnLabeler7731.kt:668`);
  * floors and the label cost.
* Graduation alone doubles "liquidity" at constant SOL. The log shows the store accepts all three
  (CHAIN_VAULT 26,970 / CURVE_* 10,729 / DEX_PAIR 349+193).
* **Fix:** one definition. I suggest `sellableQuoteSideUsd` (curve real SOL; pool quote side ×1; DexScreener ÷2 only
  for SOL- or stable-quoted pools). Store the definition with the Fact (`Fact.unit`) and refuse mixed units.

### P1-2. Received-at stamps make cached values look fresh: DexScreener and Jupiter answers count as live marks; Jupiter `blockId` is ignored
* `ForwardReturnLabeler7731.kt:2295-2298`, `:2626-2627`, `:2642`, `:2535`: off-watch answers are stamped with receipt
  time. The code's own comment says DexScreener "returns a snapshot without a trade timestamp".
* `:2019-2021` `liveMark8454` treats `"OFFWATCH"` as a live market read, so the flat guard is bypassed, the same way
  as in P0-1.
* `engine/sell/PriceResolverFallback.kt:409-421` reads Jupiter v3 `usdPrice` but drops `blockId` (and
  `priceChange24h`), so a dead coin's last trade price comes back as fresh.
* The `KeylessMarks8359.kt:155` pair lane, the hot loop (`BotService.kt:13117`, PROVIDER at `now`) and
  `ORACLE_FALLBACK` / `BIRDEYE_OVERVIEW` / `PAIR_FALLBACK` / `PUMP_FUN_FRONTEND_API` (`BotService.kt:11500, 11786,
  34616-34861`) are all stamped `System.currentTimeMillis()`.
* **Effect:** an untraded coin's unchanged price books as a flat −cost label (identical-nets cluster #2), and stale
  provider values win the "newest" price rule.
* **Fix:**
  * Use the provider's time when it has one (Jupiter `blockId` → slot time; Birdeye `updateUnixTime`).
  * Without one, stamp `receipt − providerCacheTtl` and mark `liveMark=false`.
  * Require a price *change*, or an on-chain read, to count a flat read.

### P1-3. Forward labels mix venues; the venue check covers only drops of 0.1x or worse
* `ForwardReturnLabeler7731.kt:2143-2216`: `typedMark8386` picks the newest candidate across the registry, the curve,
  OFFWATCH (venue `""`), tape (venue `""`), vault (`vr.pool`) and TOKEN_STATE.
* `LabelEvidence8386.kt:28-29`: `venuesDisagree8681` runs only when `ratio <= 0.1`, and an empty venue "proves
  nothing". So every move between 0.1x and 10x can compare pool A's basis with pool B's or an aggregator's mark.
* `OnChainDepth8038.kt:311-319`, `:419-431`: the vault mark comes from the first two pool hints
  (DexPools-chosen / meta cache / derived PumpSwap PDA), keyed per mint, not per basis venue.
* **Fix:**
  * Carry the venue (pool address) on every mark. OFFWATCH should carry the DexPools-chosen `pairAddress`.
  * Require `outcome.venue == basis.venue` unless a migration event lies between them.
  * Otherwise treat the mark as OBSERVED_ONLY.

### P1-4. The TokenFacts store mixes clocks, so fresh observations are refused as "older"
* `TokenFacts.kt:74`, `:188`, `:297-298` follow the rule "older never replaces newer". Producers stamp different
  clocks:
  * receipt-now: PumpPortal trade marks (`BotService.kt:25740, 25761`), curve frames (`PumpFunWS.kt:1001` default
    `atMs`), all PROVIDER fallbacks (P1-2);
  * request start: vault reads (`OnChainDepth8038.kt:424`);
  * provider batch time: DEX pair flow;
  * block time: tape prints (`TradeTape8100.kt:140-141`).
* Log: liq `OLDER_THAN_HELD=1486`, price `OLDER_THAN_HELD=434`. Real chain reads lose to receipt-stamped caches.
* **Fix:** one observation clock, the chain or event time. Use the event time for frames (`frameEventMs8285` already
  exists, `PumpFunWS.kt:1188`) and the response slot or block time for RPC. Record receipt time separately.

### P1-5. Coin "age" falls back to pool-creation time, a documented lower bound, and is exported as the coin's age
* `engine/cortex/LanePlaybook7907.kt:851-855, 925-929`: `tradingAgeMin8531` returns the birth or
  `AdmissionBirth8144.poolFloorMs8417`.
* `AdmissionBirth8144.kt:199-200` and `CanonicalTokenBirthTime7440.kt:37`: "A pool proves the token existed then, not
  that its mint was created then."
* The log shows about 215k unresolved birth lookups against about 47k resolved, so most rows take the fallback. That
  feeds:
  * the dataset `age`;
  * EdgeModel `AGE_EDGES` and the age×liq cross;
  * PocketLedger `<1m/<5m` bands;
  * the PayingCell key;
  * StrategyLab.
* A graduated, migrated or re-pooled old coin reads minutes old.
* **Fix:** export `ageMin` (verified birth or NaN) and `poolAgeMin` separately. Never put the pool floor in the age
  feature or in the age cohort.

### P1-6. Stage router defaults unobserved buy/sell pressure to 50/50 (and liquidity to 0), and those values classify stage records
* `engine/TokenMetricStageRouter.kt:94-95`: `bp/sp = 50.0` when there is no flow read and no fresh book. `:100`
  sets `liq = 0.0`. `:101` uses `mcap ?: FDV`.
* The default 50 satisfies:
  * `bp < 52` → `dumping` (`:148`) and `peakExhaustion` (`:146-147`);
  * `bp >= 50` → `midAccum` (`:151`).
* The stage is frozen into every label (`ForwardReturnLabeler7731.kt:1308`), into STAGE cells (stage records, the
  `LIVE_STAGE_LOSES_8626` door) and into `signature8051`. Log: PEAK and DUMP sit at the n1300 cap in every lane.
* `LanePlaybook7907.kt:177` already recognises `bp==50&&sp==50` as `DEFAULT_UNOBSERVED`; the router doesn't.
* **Fix:** carry `bookObserved8464` into every bp/sp test and return `Stage.UNKNOWN` when it is false. Never default
  the values.

### P1-7. Unwitnessed and cross-venue tape prints mark the shadow and paper books
* `TradeTape8100.kt:127`: only `OUT_OF_BAND` is refused, so `UNWITNESSED` prints are taped (log: 1,330).
* `ShadowMark8360.kt:59-62, 115-116` reads `TradeTape8100.lastPrint8360` (newest print of **any** venue on the tape)
  with no `PrintWitness8750.witnessedPrint8750` check. The labeler does check it (`ForwardReturnLabeler7731.kt:2173`).
* `ShadowMark8360.kt:45, 89`: TICK reads `ts.lastPrice` with no seed check (P0-3).
* `MemePaperTrader8544` (the "paper beside LIVE" book: −8.303 SOL realised) and the shadow outcomes are graded on
  these prints.
* **Fix:** in `ShadowMark8360`, require `witnessedPrint8750` plus same-venue (`venueOf8285`), and exclude seed TICKs.

### P1-8. MintDecimalsAuthority6392 is seeded with 6 decimals by source string, and the seed is immutable
* `engine/BotService.kt:17457-17466`: any mint whose `joinedSources` contains `PUMP_FUN` / `PUMP_PORTAL` / `PUMPFUN`
  gets `resolveAndCache(mint, 6)`.
* `engine/truth/LiveContinuity6392.kt:142-146` (`putIfAbsent`): the first write wins forever. The doc calls it
  "chain-resolved".
* `BotService.kt:26225-26229` itself records that PumpPortal relays non-pump mints (an xStocks token).
* It is used for executable quotes (`StopMarkConfirm8418.kt:190, 324`, `?: 6` default) and the token-meta archive
  (`BotService.kt:17491-17495`).
* **Fix:** accept only `MintSupply8418` decimals (mint account or getTokenSupply). Never seed. Treat a missing value
  as unknown: no quote until read.

### P1-9. Seed prices are re-stamped "now" on every WebSocket tick, so a derived value passes as a fresh observation
* `BotService.kt:17345-17372`: "Synthetic price refresh" writes `trustedMarketCapUsd6492 / supply` at AGGREGATE,
  stamped now, with `source = null`, which keeps the seed label.
* `TrustedMcap8019`, `EdgeModel` `mcap` and the freshness sentinels then read a cap-derived price as a current
  reading.
* **Fix:** a seed keeps its first `observedAtMs`, is excluded from `PriceAge8549` freshness, and is never offered to a
  learner as a price feature.

### P1-10. The global EdgeModel ridge and the labeler's lane/AGG books count one coin once per lane (up to 12x)
* `EdgeModel8537.kt:518`: `global.learn(x, y)` for every (mint, lane) observation.
* The labeler AGG/LANEDEC/SRC books (`ForwardReturnLabeler7731.kt:656-666`) do the same.
* A single runner is learned up to 12 times, which shrinks the standard errors that gate authority. The 8715 note in
  `DiagnosisBrief8438.kt:219-221` already observed this.
* TreeRanker weights each row 1/n per mint-window (`TreeRanker8737.kt:127-128`); the ridge doesn't.
* **Fix:** weight the global and AGG learning 1/k per (mint, decision-window), or learn once per mint and pass lane as
  a feature only.

---

## 3. P2

| # | Finding | file:line | Evidence | Fix |
|---|---|---|---|---|
| P2-1 | The dataset field `labelObservedAtMs` holds the write time, not the mark time | `EdgeModel8537.kt:742` | `datasetLine(s, net, filled, witnessed, System.currentTimeMillis())` | Pass `o.lastMark8386.observedAtMs` through `learn` / `recordUnfilled8546` |
| P2-2 | Label window grace is half the horizon: a 1-min read can land at 2 min, a 5-min read at 7.5 min, a 15-min read at 22.5 min | `ForwardReturnLabeler7731.kt:87`, `:2376-2377` | `graceFor7946 = (h/2).coerceIn(60s, 10m)` | Grace ≤ 20 % of the horizon. Export the actual label age |
| P2-3 | Up to six different SOL/USD clocks: `lastKnownSolPrice` (no age check, restored from prefs at boot) vs `labelSolPrice8580` / `freshSolPrice8163` | `BotService.kt:25736`, `LiquidityTruth8037.kt:43, 81`, `OnChainDepth8038.kt:155`, `ParallelMarkFanout7088.kt:636-638`, `FieldManual7715.kt:315-335`, `LaunchCohort8173` | entry TOKEN_STATE priced at one rate, the curve or tape mark at another | Price everything from one rate observed at the mark's time (`SolRateHistory8543` at `observedAtMs`) |
| P2-4 | Fan-out writes a single uncorroborated feed (DAS `price_info`, DefiLlama, Raydium API, all cached) or a cross-venue median as the held mark, at PROVIDER rank, stamped at the pass | `ParallelMarkFanout7088.kt:182, 288-341`; `BotService.kt:2038-2081`, `:13117` | `MARK_SINGLE_SOURCE_UNCORROBORATED_7088`; `quotesBySource` | Single-source cached feeds become an observation-only fact, never the held mark |
| P2-5 | Curve frames are stamped at receipt even though the frame carries `timestamp`; backlogged frames (heap pressure, OOM) look fresh and can override a newer account read | `PumpFunWS.kt:1001, 1095` → `CurveReserves7951.kt:64, 150-151` | default `atMs = System.currentTimeMillis()` | Pass `frameEventMs8285(j.optLong("timestamp"))` |
| P2-6 | Active-curve liquidity and real SOL stay "good" for 60 minutes ("reserves only move on trades"), which assumes the stream saw every trade | `CurveReserves7951.kt:34, 190-194, 239-245` | `CURVE_TTL_MS = 60 min` | 90–120 s TTL unless a fresh account read confirms |
| P2-7 | A stop with no recorded low fills at exactly `−stopMag`, a made-up fill that gives identical nets | `HonestExit8366.kt:65`; `LaneParticipation8029.playGross8029` (non-honest observations) | `else -stopMag` | No fill means OBSERVED_ONLY, never a label |
| P2-8 | PocketLedger t-stat: a cell with se = 0 gets ±5 (the maximum), so identical-net clusters (P0-1, P1-2, P2-7) make maximally "proven" cells | `PocketLedger8610.kt:126` | `if (se > 0) m/se else sign(m)*T_CLIP` | Require se > 0 and a count of distinct values ≥ 5 |
| P2-9 | `stampOf`: an unknown observation time becomes "now" for every non-restore rank; TrustedMcap writes at `ts.lastPriceUpdate`, which can be 0 | `TokenFacts.kt:80-85`; `TrustedMcap8019.kt:91` | `observedAtMs <= 0L -> nowMs` | Unknown time → RESTORE rank (0) |
| P2-10 | `notePairFlow`: with no 5-min trades (a real 0 reading) the hour's counts are substituted for buy/sell pressure and tx/min | `TokenFacts.kt:364-376` | `m5 > 0 -> …; h1 > 0 -> h1/60` | Write `TX_PER_MIN = 0`; leave pressure unknown, not the hour's figure |
| P2-11 | Entry basis can be up to 90 s old (TOKEN_STATE). Unfilled rows (fill=false) keep that basis and are trained on by TreeRanker (P0-2). FreshLaunchSelector learns from the unrebased decision price | `ForwardReturnLabeler7731.kt:101, 1196-1197, 1328` | `ENTRY_MARK_MAX_AGE_MS_7735 = 90_000` | ≤ 10 s for any basis that is written out |
| P2-12 | The DexPools chooser sticks to the "preferred" pool while it reports any liquidity > 0, so a dead pre-migration pool keeps pricing the coin | `DexscreenerApi.kt:682-686` | `liquidityUsd > 0.0` | Keep it sticky only while it is ≥ 25 % of the deepest SOL/stable pool |
| P2-13 | Slot names lie: "15/60/240" are 1 min / 5 min / 60 min (or 15 min), and reader docs say "60-minute record" | `ForwardReturnLabeler7731.kt:62-64, 661-662, 670, 683` | `H60_MS_7731 = 5 min` | Rename. Audit every `meanNet60Pct` reader |
| P2-14 | The stage router uses FDV for mcap when mcap is missing; the error snapshot defaults mcapToLiq = 9999 and top = −1 | `TokenMetricStageRouter.kt:101, 222` | `?: ts.lastFdv` | NaN, not a stand-in |
| P2-15 | `priceSol7951` builds vTok from the launch invariant (30 × 1.073e9 / vSol) when vTok is unknown; `ShadowMark` and `curveWitness` take that as an observed curve price | `CurveReserves7951.kt:56-61`; `ShadowMark8360.kt:112` | invariant fallback | Return 0 (unknown) when vTok is not observed |
| P2-16 | The realised-PnL fallback assumes 9 decimals when there is no lot (pump mints are 6) | `Executor.kt:29628, 29633` → `RealizedPnlConduit6344` (`:103`) | `?: 9` | Use `MintSupply8418` decimals, else fail |
| P2-17 | Unfilled meme 5-minute reads feed `MemeRegime8546` (regime breadth gates live) | `ForwardReturnLabeler7731.kt:1781-1783`, `:1360` | by design, but its entry prices were never buyable | Feed regime breadth from filled or chain-priced reads only |
| P2-18 | `CurveTicks7968.tradeBetween8252`: merging two snapshots turns several trades (or missed notifications) into one made-up "trade" | `network/CurveTicks7968.kt:126-134` | snapshot diff | Mark as price-only always (no side), or use the Helius program event |

---

## 4. Likely artifact clusters in the dataset (to confirm with section 5)

| Cluster | Signature in the export | Code cause |
|---|---|---|
| **A. identical −cost nets** | many distinct mints in one lane with `net == −cost` (to 1e-6) | P0-1 QuietCurve flat mark; P1-2 unchanged OFFWATCH/Jupiter price counted as live |
| **B. identical −stop nets** | `net == −laneStop − cost` repeated across mints | P2-7 made-up stop fill; the PocketLedger se=0 → t=±5 effect (P2-8) |
| **C. 2–10x "runners" with no witness** | `witness=false`, `net` 100–900 %, any mcap; especially mcap ≥ $1M | P0-3 seed basis via TOKEN_STATE; P1-3 cross-venue; P0-5 missing bound |
| **D. "fresh" large caps** | `age < 5` with `mcap ≥ $1M` or `src` in WATCHLIST / BLUECHIP / TREASURY | P1-5 pool-floor age |
| **E. horizon mix** | the same lane/feature cell with `horizonMs` 300000 vs 900000 (and legacy 3600000) rows trained together | P0-2 / P0-4 |
| **F. unfilled training rows** | `fill=false` share of TreeRanker's last 25k lines | P0-2 |
| **G. one coin, 12 lanes** | the same `mint` and `t` (±1 s) on many lanes; `net` differences match only cost and stop | P1-10 |

## 5. Dataset analysis script (run when the export is available)

Save it somewhere outside the upload directory and run
`python3 -I audit_edge.py /path/to/aate_edge_dataset_8537_*.jsonl.txt`.

```python
import sys, json, math, collections
rows = []
with open(sys.argv[1], encoding="utf-8", errors="replace") as fh:
    for ln in fh:
        try: rows.append(json.loads(ln))
        except Exception: pass
def f(r, k):
    v = r.get(k); return v if isinstance(v, (int, float)) and math.isfinite(v) else float("nan")
print("rows", len(rows), "fill=false", sum(1 for r in rows if r.get("fill") is False))
print("horizonMs", collections.Counter(r.get("horizonMs") for r in rows).most_common())
print("venueScopeEpoch", collections.Counter(r.get("venueScopeEpoch") for r in rows).most_common())
# A/B: identical nets across distinct mints (per lane)
by = collections.defaultdict(set)
for r in rows:
    n = f(r, "net")
    if math.isfinite(n): by[(r.get("lane"), round(n, 6))].add(r.get("mint"))
top = sorted(((len(m), k) for k, m in by.items()), reverse=True)[:25]
print("identical nets (distinct mints, lane, net):"); [print(" ", c, k) for c, k in top]
negcost = sum(1 for r in rows if math.isfinite(f(r,"net")) and math.isfinite(f(r,"cost")) and abs(f(r,"net") + f(r,"cost")) < 1e-6)
print("net == -cost rows:", negcost)
# C: unwitnessed multi-x moves by mcap band
bands = [(0,1e5),(1e5,1e6),(1e6,1e7),(1e7,1e12)]
for lo, hi in bands:
    sel = [r for r in rows if lo <= f(r,"mcap") < hi]
    big = [r for r in sel if f(r,"net") >= 100 and not r.get("witness")]
    print(f"mcap {lo:.0e}-{hi:.0e}: n={len(sel)} unwitnessed>=+100%={len(big)} max={max((f(r,'net') for r in sel), default=float('nan')):.0f}")
# D: fresh large caps
d = [r for r in rows if f(r,"age") < 5 and f(r,"mcap") >= 1e6]
print("age<5min & mcap>=1M:", len(d), collections.Counter(r.get("src","")[:30] for r in d).most_common(8))
# G: same coin across lanes
g = collections.defaultdict(list)
for r in rows: g[(r.get("mint"), int(f(r,"t")//1000) if math.isfinite(f(r,"t")) else 0)].append(r)
multi = [v for v in g.values() if len(v) >= 5]
print("mint-second groups with >=5 lanes:", len(multi))
# impossible moves: |net| > 100% in 5 min on >= $1M caps; floor hits
print("net<=-100:", sum(1 for r in rows if f(r,"net") <= -99.999))
print("mcap>=1M & |net|>100 & horizon=5m:", sum(1 for r in rows if f(r,"mcap")>=1e6 and abs(f(r,"net"))>100 and r.get("horizonMs")==300000))
```

## 6. Top 20, ranked

1. P0-1 QuietCurve8750 stamps stale prints "now" with chain-witness rank, so quiet or unseen coins book exactly −cost (`QuietCurve8750.kt:50`, `ForwardReturnLabeler7731.kt:2176-2182, 1707-1708`).
2. P0-2 TreeRanker8737 and StrategyLab8755 train on fill=false, pre-8660 venue-poisoned and mixed-horizon rows; the tree and Mind outrank the ridge (`TreeRanker8737.kt:106-129`, `EdgeModel8537.kt:401-418`).
3. P0-3 Seed prices still become the label basis or outcome via TOKEN_STATE adoption; `seedPrice8559` misses `…MCAP_SEED_6401` (`ForwardReturnLabeler7731.kt:2196-2201, 3151-3164`; `TokenFacts.kt:196`).
4. P0-4 Hour lanes' 15-min outcomes are booked into the "5-min" slot and the dataset `net`, and published as "5-min executable net" (`ForwardReturnLabeler7731.kt:1790, 1984, 1999`).
5. P0-5 No check covers 2x–10x moves at 5–15 min for non-curve coins; the +5,000 % / +1,200 % caps would not have caught the 8627 artifacts (`EdgeModel8537.kt:477-487`, `LabelEvidence8386.kt:36`).
6. P1-1 LIQUIDITY_USD mixes curve real SOL ×1, pool SOL ×2 and DexScreener totals in one field and one set of bands (`LiquidityTruth8037.kt:45`, `OnChainDepth8038.kt:138`).
7. P1-2 Received-at stamping makes cached DexScreener and Jupiter prices (blockId ignored) and PROVIDER fallbacks look fresh, and OFFWATCH counts as a live mark (`ForwardReturnLabeler7731.kt:2296, 2020`; `PriceResolverFallback.kt:409-421`; `BotService.kt:13117`).
8. P1-3 Labels compare marks across venues; the venue check runs only at ≤ 0.1x and an empty venue passes (`ForwardReturnLabeler7731.kt:2213-2215`; `LabelEvidence8386.kt:28-29`).
9. P1-4 TokenFacts mixes receipt, request-start and block clocks, so real chain reads are refused as older (log: OLDER_THAN_HELD liq 1,486 / price 434) (`TokenFacts.kt:74, 188`).
10. P1-5 Age falls back to pool creation, a documented lower bound, and is exported as the coin's age (`LanePlaybook7907.kt:851-855, 925-929`).
11. P1-6 The stage router defaults buy/sell pressure to 50/50 and liquidity to 0, which classifies DUMP/PEAK/midAccum stage records (`TokenMetricStageRouter.kt:94-95, 146-151`).
12. P1-7 ShadowMark8360 marks the shadow and paper books from unwitnessed (1,330), cross-venue tape prints and seed TICKs (`ShadowMark8360.kt:45, 60-62, 115`; `TradeTape8100.kt:127`).
13. P1-8 MintDecimalsAuthority6392 is seeded immutably with 6 decimals because the source string says pump (`BotService.kt:17457-17466`; `LiveContinuity6392.kt:142-146`).
14. P1-9 The synthetic seed price is re-stamped "now" on every WS tick (`BotService.kt:17345-17372`).
15. P1-10 The global ridge and AGG/lane books learn one coin once per lane (up to 12x), so standard errors look smaller than they are (`EdgeModel8537.kt:518`).
16. P2-1 / P2-2 `labelObservedAtMs` is the write time; label windows accept a read up to 1.5x the horizon (`EdgeModel8537.kt:742`; `ForwardReturnLabeler7731.kt:87`).
17. P2-3 Unaged `lastKnownSolPrice` is used for chain marks and liquidity while labels use a different SOL rate (`BotService.kt:25736`, `OnChainDepth8038.kt:155`).
18. P2-4 The fan-out writes a single cached uncorroborated feed or a cross-venue median as the held mark at PROVIDER rank (`ParallelMarkFanout7088.kt:288-341`; `BotService.kt:2038-2081`).
19. P2-7 / P2-8 Made-up −stop fills plus PocketLedger's se=0 → t=±5 turn identical-net clusters into maximally "proven" cells (`HonestExit8366.kt:65`; `PocketLedger8610.kt:126`).
20. P2-5 / P2-6 Curve frames are stamped at receipt (event `timestamp` ignored) and curve liquidity is trusted for 60 minutes (`PumpFunWS.kt:1001`; `CurveReserves7951.kt:34`).
