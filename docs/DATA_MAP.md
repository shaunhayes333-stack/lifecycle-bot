# AATE meme market data — the map

Owner directive (5.0.8551): *"its meant to have a single data cache that all brains and readers call from and add to.
im not throwing away what ive already built. we need unification and clarification."*

This file is the reference for **where every meme market-data field lives, who writes it, who reads it, and the rule
that decides between two readings**. It changes in the same commit as any code that moves a field.

---

## 1. The store

| | |
|---|---|
| **Where** | The mint's `TokenState` row in `BotService.status.tokens` (fed by DataOrchestrator, the scanners and the chain readers). |
| **Facts** | `TokenState.facts8551` — one `TokenFacts.Fact(value, source, rank, observedAtMs)` per migrated field (`LIQUIDITY_USD`, `PRICE_USD`, and since 8553 the snapshot fields `BUY_PRESSURE_PCT`, `SELL_PRESSURE_PCT`, `CHANGE_5M_PCT`, `CHANGE_1H_PCT`, `MCAP_USD`, `FDV_USD`, `TOP_HOLDER_PCT`, `TX_PER_MIN`). |
| **Write path** | `engine/truth/TokenFacts.kt` — the **only** code that sets a migrated field. One writer per field (`writeLiquidity` / `restoreLiquidity`, `writePrice` / `restorePrice` / `correctPrice`, `writeSnapshot` / `restoreMcap` / `restoreFdv`, `markGraduated`). One reader each where readers disagreed: `topHolderPct(ts)`, `txPerMin(ts)`. |
| **Legacy fields** | Mirrors of the fact (`ts.lastLiquidityUsd`, `ts.tokenMap.liquidityUsd`; `ts.lastPrice`, `lastPriceUpdate`, `lastPriceSource` (+ pool / dex), `ts.tokenMap.priceUsd` / `priceObservedAtMs7858`). Every existing reader keeps working and reads the store. |
| **Guard** | `Aate8551TokenFactsTest.noDirectLiquidityWritesOutsideTheStore`, `Aate8552PriceFactsTest.noDirectPriceWritesOutsideTheStore`, `Aate8553DataBundleTest.noDirectSnapshotWritesOutsideTheStore` / `graduationIsOneTruth` fail the build on any new direct write. |
| **Brief** | `tokenFacts8551[...]` line in the diagnosis brief: facts accepted per source, offers refused per reason. |

Existing components are **producers**, not owners: DexScreener WS / pair poll / batch lane, Birdeye overview, Raydium
TVL, the pump.fun curve (PumpPortal frames, curve account reads), the pool vault reads (OnChainDepth8038, GradTicks8018),
the resolver fill, the meta cache / hive / saved-position restores. They keep their raw buffers (TradeTape8100,
CurveReserves7951, OnChainDepth8038, PairFlow8169, MarketSweep7297, CandleCoverage8284, …) and *offer* their reading to
the store with its source, rank and **own observation time**.

## 2. The rule (same for every field)

`TokenFacts.accepts(prior, offered, legacyHeld)` — pure, unit-tested:

1. A value that is not a reading (NaN, infinite, ≤ 0) is not a fact. "Not reported" never erases an observation.
2. A **RESTORE** (archive, meta cache, hive, saved position — observation time unknown, stored as 0) only fills an empty
   slot or refreshes another restore. Any real observation replaces it. It does not count as a hydration and asks for a
   fresh chain read when a decision reads it.
3. An observation **older** than the held fact never replaces it, whatever its rank.
4. A newer (or same-time) observation of **equal or higher rank** replaces the held fact, up or down.
5. A newer observation of **lower rank** replaces it only after the stronger fact's hold window
   (`holdMs`: **90 s for CHAIN**, 30 s otherwise). The chain's depth is not overwritten by an API figure the next second,
   and a dead chain read does not pin the field forever.

**Price (8552)** uses the same rule with two stated differences (`TokenFacts.acceptsPrice`): a price moves on every
trade, so only a **CHAIN** tick holds the field (**60 s**, the ChainFedMarks8418 window — before 8552 only 2 of 38 writers
respected it); and a **correction** of the held observation (an Executor unit / basis rebase, a capped paper exit) replaces
the value but keeps the observation's own time (`correctPrice`) — a repair never makes a mark look fresher than its reading.
A row created or copied with a price (no fact yet) is read as a legacy fact observed at its `lastPriceUpdate` (a restore
when that is 0), so "older never replaces" holds from the first write.

**Snapshot fields (8553)** — pressure, changes, cap, FDV, top-holder share, transactions per minute — use the price
rule through one writer (`writeSnapshot`) with each field's own validity (`validFor`: a change may be 0 or negative, a
pressure / share is a percentage, a 0 cap is no reading). An unstamped 50 / 0 pressure / change default is not a fact.
Mirrors keep the observation stamps the lanes' freshness windows read (`buyPressureObservedAtMs8084`,
`priceChangeObservedAtMs8084`, `hourChangeObservedAtMs8506`), so a refused or cached offer can no longer re-date them.
Executed swaps (the tape) are CHAIN facts; the pair poll is PROVIDER at the pair's own fetch time (`evidenceAt7271`); the
poll's hour counts standing in for 5 minutes are AGGREGATE.

**Graduation (8553, D8)** is one sticky marker, `TokenFacts.markGraduated`: a curve account read `complete`, the PumpPortal
migration event, the DEX WS migration or an explicit graduated/migrated label sets BOTH truths — `PumpCurveKeys7269`'s
curve-closed set (37 readers: curve depth, curve price witness, sell venue) and `tokenMap.migratedOrGraduated` (31 readers:
route, lane contracts, Cortex). A post-curve AMM venue string alone is an inference and only routes the row.

Observation time: the provider's / chain's own time where the producer has it (vault read `atMs`, curve frame / account
`atMs`, route cache `updatedAtMs`, entry snapshot `capturedAtMs`); a clock more than 5 s ahead is clamped to now.

### Ranks

| Rank | Value | Sources |
|---|---|---|
| `CHAIN` | 3 | pool vault balances (`CHAIN_VAULT_8038`, `CHAIN_VERIFIED_8038`, `POOL_VAULT_8418`), pump.fun curve (`CURVE_ACCOUNT_7951`, `CURVE_FRAME_7951`, `CURVE_READ_8046`) |
| `PROVIDER` | 2 | `DEXSCREENER_WS`, `DEX_PAIR_POLL`, `DEX_BATCH_8460`, `BIRDEYE_OVERVIEW` |
| `AGGREGATE` | 1 | `INTAKE` (scanner figure), `OBSERVED_FILL_8037` (resolver / token map), `RAYDIUM_TVL_8128` (cached TVL stand-in), `CURVE_PRICE_IMPLIED_8173`, `TOKEN_MAP_CACHE_6513`, `ENTRY_SNAPSHOT_7382` |
| `RESTORE` | 0 | `META_CACHE_RESTORE`, `HIVE_RESTORE`, `POSITION_RESTORE` |

Price ranks (8552): **CHAIN** — GradTicks8018 chain ticks (`source8018`), executable route quotes (`EXEC_QUOTE_8237`);
**PROVIDER** — DexScreener WS / pair poll, Jupiter poll, Birdeye overview, pair / oracle fallbacks, fan-out marks, held hot
marks, candidate mark refresh, cross-asset router; **AGGREGATE** — cap ÷ supply seeds, curve-reserve seed, intake WS cap
reprice, entry snapshot, route cache (at its own time), saved position with a source time; **RESTORE** — meta cache, saved
position without a source time, position rehydrate, an undated synthetic pair.

## 3. Fields

Status: **STORE** = migrated (one writer, guard on); **LEGACY** = still written in several places, scheduled below.

| Field | Canonical slot | Status | Producers (write through) | Decision readers |
|---|---|---|---|---|
| Liquidity (USD) | `facts8551[LIQUIDITY_USD]` → mirrors `lastLiquidityUsd`, `tokenMap.liquidityUsd` | **STORE (8551)** | `TokenFacts.writeLiquidity` ← `HydrationReeval8343.writeTick8345` (WS / poll / batch / Birdeye / Raydium / intake / verify), `onDepthRead8343` (every vault read on arrival), `LiquidityTruth8037` (curve frames, pool vaults, `hydrate8037`), restores | `LiquidityTruth8037.hydrate8037` = `TokenMapAuthority.observedLiquidityUsd` = the raw field (V3Adapter, HardRugPreFilter, ExecutableOpenGate, Executor, FDG, lanes, Field Manual) |
| Price (USD) + observation time | `facts8551[PRICE_USD]` → mirrors `lastPrice`, `lastPriceUpdate`, `lastPriceSource` (+ pool / dex), `tokenMap.priceUsd` | **STORE (8552)** | `TokenFacts.writePrice` ← 38 sites (BotService 23, Executor 12, DataOrchestrator, ProfitExitTruth8163, HeldHotMarkAuthority7419, CandidateMarkFresh8094, CrossAssetMarkRouter6530 ×4, MemeChokes7951, PositionPersistence, BotServiceLifecycleExt) | everything (reads the mirrored fields) |
| Buy / sell pressure, 5 m / 1 h change | `facts8551[BUY_PRESSURE_PCT / SELL_PRESSURE_PCT / CHANGE_5M_PCT / CHANGE_1H_PCT]` → mirrors + stamps | **STORE (8553)** | tape flow (DataOrchestrator.applyTapeBuyPressure7773, CHAIN), pair poll (`applyPollFlow7758`, at the pair's fetch time), DEX WS | lanes / LaneInputTruth8236 / SetupFeatureResolver8169 read the mirrors and stamps |
| Transactions per minute | `facts8551[TX_PER_MIN]` | **STORE (8553)** | pair poll 5 m counts (h1 stand-in AGGREGATE); tape on read | `TokenFacts.txPerMin(ts)`: tape → fresh fact (≤ 5 min) → live alpha cache (LanePlaybook F.txVelocity, Cortex TX_VELOCITY, setup authority, TX_VELOCITY_BREAK) |
| Market cap / FDV | `facts8551[MCAP_USD / FDV_USD]` → `lastMcap`, `lastFdv`, `tokenMap.marketCap / fdv` | **STORE (8553)** | 23 cap + 6 FDV writers: chain print × supply (CHAIN), curve reserves (CHAIN, fill), pair poll / WS / Birdeye / pump.fun API (PROVIDER), intake / price × supply / TrustedMcap8019 write-back / mark rescale / entry snapshot (AGGREGATE), meta cache / hive / saved position (RESTORE) | lanes, TrustedMcap8019 |
| Birth / age | side stores keyed by mint (CanonicalTokenBirthTime7440 resolver + `birthMemory7809`) | **ONE RULE (8553)** | AdmissionBirth8144 witnesses, PoolCreationTime7385, StoredBirth8418, TokenBirthHydrator7441 | `launchAgeMs7767` = `resolvedAgeMinutes` = `LaunchPhaseAuthority7401.resolvedAgeMs` (resolved birth, else the earliest birth already evidenced); `AdmissionBirth8144.provenAgeMs8144` stays the lower-bound reader for undated launches |
| Top-holder share | `facts8551[TOP_HOLDER_PCT]` → `topHolderPct`, `tokenMap.topHolderConcentrationPct` | **STORE (8553)** | safety check (at its own observation time) | `TokenFacts.topHolderPct(ts)` (Executor collapse guard + rug signals, CanonicalFeaturesBuilder, AdaptiveEntryWitness8527) |
| Mint / freeze authority | `ts.safety.*AuthorityDisabled` | proof lives in `safety` | OnChainMintAuthorityProof7248, TokenSafetyChecker | FDG, CanonicalFeaturesBuilder, Executor collapse guard (fixed 8551) |
| Candles | `ts.history` (primary series) + `CandleCoverage8284.store1m` (1 m side series for 4 h / held rows) | **BY DESIGN (8553, D9)** | `CandleCoverage8284.merge8284` routes each bar to the right series | the 1 m readers (`coverage8284`, `MultiTimeframeCharts8521.snapshot8521`) merge both with one quality rule |
| Curve / graduation | `PumpCurveKeys7269` closed set + `tokenMap.migratedOrGraduated` | **ONE MARKER (8553)** | `TokenFacts.markGraduated` (curve account complete, migration events, DEX WS, explicit labels) | lanes, exits, curve depth, router |
| Mint supply | `ts.mintSupply8418` | already single-record (the template for this store) | MintSupply8418 | cap, PnL |

## 4. Disagreements found (8551 audit) and where they stand

| # | Disagreement | Status |
|---|---|---|
| D1 | Liquidity: `TokenMapAuthority.observedLiquidityUsd` (curve → tick → map → reserves, no vault) vs `LiquidityTruth8037.hydrate8037` (curve → vault → tick → resolver) vs the raw tick vs the resolver | **Fixed 8551** — one fact, one rule, all three read it |
| D2 | Buy pressure / change: stamped "now" from a cached pair (up to 135 s), the WS wrote NaN changes, the 1 h change took the price's stamp | **Fixed 8553** (each reader keeps its own freshness window over honest stamps) |
| D3 | Top-holder share read in different orders (token map first / row first) | **Fixed 8553** |
| D4 | Age: `resolvedAgeMinutes` / `LaunchPhaseAuthority.resolvedAgeMs` read only the current resolution while `launchAgeMs7767` remembered the earliest birth | **Fixed 8553** |
| D5 | Price age: the pair poll wrote the price but never its time; the meta-cache hydrate stamped an archive price "now" (it passed as a fresh entry quote); the route cache replaced newer marks with older; basis repairs re-dated to now; 36 of 38 writers ignored a recent chain tick | **Fixed 8552** |
| D6 | Market cap: 23 writers, two of them writing 0 unguarded, TrustedMcap8019 writing back unstamped | **Fixed 8553** |
| D7 | Executor collapse guard read `tokenMap.mintAuthority / freezeAuthority`, which **nothing writes** — the guard never saw a live authority | **Fixed 8551** — reads `ts.safety.*AuthorityDisabled == false` |
| D8 | Graduation: two truths written by different producers that never told each other | **Fixed 8553** |
| D9 | Candles: `ts.history` vs `CandleCoverage8284.store1m` | **By design** — two series, merged by every 1 m reader with one rule |
| D10 | The decision snapshot freezes TokenState fields only (not the fact's source / time) | follows each phase |

## 5. Migration order

One build per field family. Each build: the field gets a `TokenFacts` writer, every producer offers to it with source /
rank / time, the duplicate ladders are retired, every reader reads the fact (or its mirror), the guard test covers the
field, this map is updated, then tests + CI on main.

1. **Liquidity** — 8551 ✅
2. **Price + observation time (D5)** — 8552 ✅
3. **Flow / changes (D2)** — 8553 ✅
4. **Cap / FDV (D6)** — 8553 ✅
5. **Birth / age (D4)** — 8553 ✅ (one rule)
6. **Holders (D3)** — 8553 ✅
7. Safety authorities (D7 reader fixed in 8551; writer consolidation)
8. **Candles (D9)** — 8553 ✅ (by design)
9. **Curve / graduation (D8)** — 8553 ✅
10. Supply cleanup (fold MintSupply8418 into the same store)

## 6. Adding a producer or a reader

- **Producer**: call the field's `TokenFacts.write…(ts, value, "<SOURCE_NAME>", Rank.<X>, <observedAtMs>)`. Use the
  provider's / chain's own timestamp. Pick the rank from the table above; never call a cache or an archive anything but
  `RESTORE`. Add the source to the rank table here.
- **Reader**: read the field (or the reader function named in §3). Do not build a new fallback ladder; if a reading is
  missing, add its source as a producer instead.
- **Never** assign a migrated field directly. The guard test will fail and name the line.

## 7. Edge dataset feature gaps (8553, from the owner's 559-row export)

| Feature | Was | Fix |
|---|---|---|
| `txVel` | NaN on 559 / 559 — only source the live alpha cache, filled on the LIVE buy path alone | `TokenFacts.txPerMin`: tape → pair poll fact → alpha cache |
| `volAccel` | 0.0 on 362 — MomentumPredictorAI's default before 5 samples / without an earlier volume | NaN unless `volumeAccelObserved8553` |
| `phase` | "" on 418 — mature and age-unknown both blank | `MATURE` / `UNKNOWN_AGE` (model buckets unchanged) |
| `holderVel` | NaN on 545 — needs candles carrying holder counts | open: no holder-count source on the candles |
| `bp` = 100.0 on 66 | tape share from a handful of buys | kept: a real but thin tape reading (WhaleDetector.tapeBuyPressure7773); open: weight by print count |

## 8. The cache is read AND added to (8554)

The store is the one cache: every brain reads it, and every side cache a reader used to pull a market value from now
**adds** that value to it, at the side cache's own observation time.

| Side cache | Adds to the store | Rank / time |
|---|---|---|
| PairFlow8169 (every DexScreener pair answer: single pair, price batch, pair batch) | buy / sell pressure (5 m counts; hour counts as a stand-in), tx per minute, 5 m / 1 h change, pool liquidity (not for an active pump.fun curve) | PROVIDER (hour stand-in AGGREGATE), the answer's `atMs` |
| MarketSweep7297 rows (rows of coins the bot holds) | 5 m change, observed 1 h change, liquidity (not for an active curve) | AGGREGATE, the row's `observedAtMs8169` (undated rows are not taken) |
| TradeTape8100 reads (LaneInputTruth8236 fallbacks) | buy / sell pressure, 5 m change | CHAIN, the read's time |
| SetupFeatureResolver8169 liquidity fill (`hydrate8037`) | liquidity | AGGREGATE at the vault / pair / sweep observation time (`observedLiquidityTimed8554`); a token-map figure at the map's update time |
| MintSupply8418 | `SUPPLY_WHOLE`, stamped on the row the moment the chain answers | CHAIN, the account read's time |
| Not taken | the pair's cap (a cap comes from verified supply), pair price (prices go through the mark paths) | — |

**D10 (fixed 8554):** the Cortex frozen decision copy (`CortexDecisionSnapshot8522`) keeps the row's facts
(`TokenFacts.freezeInto`), so a decision is graded on the facts it saw, with their sources, ranks and times.

**Shared token map:** a cached map swapped in (`TokenMapAuthority.installShared6492`) is re-mirrored from the store
(`TokenFacts.mirror`: liquidity, price, cap, FDV, top-holder share), so it can never override a fact.

**The archive (TokenMetaCache) round trip:** after an observed write the store refreshes the mint's archive row from its
facts (at most once a minute per mint; SQLite on the archive's own 60 s flush). The raw intake register no longer
overwrites archived store values. A warm boot reads the archive back as RESTORE facts (time 0): an archive value is used
until the first observation and never counts as one.

## 9. Holders (8798)

| | |
|---|---|
| **Slot** | `TokenFacts.holders8798(mint)` — the newest holder count of the mint with its source and observation time, and the one before it (keyed by mint: an answer may land before the mint has a row). |
| **Write path** | `TokenFacts.writeHolders8798(mint, count, source, observedAtMs)` — older or same-time answers change nothing; an answer ≥ 60 s after the newest moves it to "previous". |
| **Producers** | Jupiter search (`JupiterHolders8128`, `JUPITER_SEARCH_8128`; a coin under an hour old is asked every 2 min), the market sweep's Jupiter rows (`JUP_SWEEP_8798`), the Birdeye overview (`BIRDEYE_OVERVIEW`). |
| **Readers** | `LanePlaybook7907.F.holders` (fact first, candle count second) and `F.holderVel7978` (`TokenFacts.holderVelocity8798`: the two facts 1–30 min apart; candle velocity second). |
| **Why** | Before 8798 the count reached the learners only stamped on a candle from a 10-minute cache, so holder velocity read 99 % blank. |

## 10. The learner columns (8798)

`LearnerColumns8798.NAMES` is the one list of learner columns beyond `EdgeModel8537.Raw`'s core: every remaining fact of the
playbook frame (launch tape, buyer flow, opportunity row, socials, creator, holders, narrative, insiders, bundles, LP lock,
volatility, regime, chart flags), the evidence no column carried (proven smart-money buyers in 5 min, launch venue, SOL 1 h /
24 h change, launches born in the last 10 min, hour and weekday, mint / freeze revoked, rugcheck score, first-block supply
and buyers, the launch's own trade count / buy share / run from its low) and each source's **age** in seconds (price, buy
pressure, change, liquidity, holders, last print). Extracted once per decision and frozen into `Raw.ext`; written to the
edge dataset by name (unobserved columns are left out — a missing key reads NaN); read back by name
(`LearnerColumns8798.fromRow`). `TreeRanker8737` trains on the core plus every column (stochastic boosting: half the rows
and half the columns per tree). The brief's `learnerColumns8798[...]` line shows each column's live coverage.

The launch tape (`LaunchTape7921`) is now fed by every decoded program-stream print of a mint no desk watches yet
(`onProgramPrint8798`), so a launch's crowd, dev sell, peak and low exist from its birth; watched mints keep arriving
through `DataOrchestrator` only. Its run from the low and distance from the peak fill the frame's shape when neither the
candles nor the trade tape read one.

## 11. Price integrity (8801)

A price is only as good as the pool it came from. Three rules now hold for every print, in this order:

1. **Only chain maps a pool to its coin** (`PumpSwapTape8307.notePoolMint8307`). A PumpSwap swap event names its pool,
   never its coin. A pool maps only on chain proof: the pool account read by `PumpSwapPools8750` (owned by PumpSwap,
   base a pump.fun coin, quoted in wSOL) or the pool-creation instruction quoted in wSOL. A DexScreener row, a per-mint
   decode or a validator-transaction match is a hint: it asks chain and maps nothing. Swaps on an unproven pool wait in a
   bounded buffer and replay once chain answers. Before 8801 the parsed stream guessed "the one included coin the
   transaction touched", so a multi-hop route put a foreign pool's swaps on a coin's tape permanently. The per-mint stream
   took the first PumpSwap event of a transaction as the subscribed coin's.
2. **Physics** (`PrintWitness8750.physicsRefusal8801`). A coin on an active pump.fun curve prices between 20 and
   1,000 SOL of cap (the curve runs about 28 to 410 SOL). Price × supply × SOL/USD may not sit more than 4× above the cap
   an independent source reported in the last two minutes (`TokenFacts.anchorMcap8801`: DexScreener WS and pair poll,
   Birdeye overview, pump.fun API, provider cap, curve account). The 4× allowance grows by another 4× for each minute of
   the anchor's age. The anchor is one-sided on purpose: a crash must always reach the stops.
3. **Consensus** (8800). A print must agree within 3× with the median of the coin's own last prints. Three agreeing
   prints inside a minute re-price the coin.

Every refusal is counted by feed and reason in the report (`printWitness8750[… refusedBy8801{SRC:REASON=n}]`), so a
broken producer shows up by name.

**Training hygiene.** `EdgeModel8537.column8801` is the one reader of a dataset column for every learner (edge ridge,
trees, learner columns, strategy lab). On a row recorded before epoch 8801, the price-shape columns (runup, pxPeak, dd,
fromPeak, fromFirst, runupFromLow, volatility) read unknown, and labels plus every other column stand.
`MODEL_EPOCH_8642` = 8801 rebuilds the ridge from the dataset under that rule.

## 12. The desks (8802)

Two desks trade outside the meme engine, each on its own thread, each graded by `HlBrain` (families chosen on the first half
of the history, graded on the second, armed only when the second half's mean less one SE is above zero):

| Desk | Instrument | History | Price and cost | Live route |
|---|---|---|---|---|
| `HlDesk` (8797) | Hyperliquid perps and HIP-3 markets, long/short, half-Kelly leverage | exchange daily candles and funding (`HlStore`) | order book walked at the desk's size (`HlCosts`) | `HlExecutor` (IOC limits, API wallet) |
| `XsDesk` (8802) | Solana xStocks, **long or flat, 1x** (`HlBrain.evaluate(..., longOnly = true)`) | the token's own deepest pool, daily (GeckoTerminal, `XsApi.dailyBars`) | an executable Jupiter round trip each hour at the desk's size (`XsApi.roundTrip`) plus the 0.5 % app fee per side | `MarketsLiveExecutor` proof routes: canonical open on the token delta, close on the SOL proceeds |

xStocks are Token-2022 tokens with a Scaled UI multiplier. Corporate actions fold into the multiplier; it is read from the
mint (`XsApi.parseMint`) and shown in the report. The book is SOL in and SOL out, which the multiplier cannot change. The
old tokenized "SHORT" (a SOL → USDC swap) and "spot-scale leverage" (more tokens bought with more capital) are refused by
the executor. Shorts and leverage belong to Hyperliquid.

**The trade book.** `DeskJournal8802` keeps every fill of both desks, paper and live, in USD, at average cost: open lots,
realised, fees, round trips and wins, all in the report. It is deliberately not the SOL trade journal. That journal is the
wallet's economics (hero balances, paper cash, the meme engine's capital and slots), and a USDC perps fill moves no SOL.
xStocks live fills are wallet swaps, so they are ALSO canonical positions (lane `XSTOCKS`, asset class STOCK).

**Pyth.** Hermes requires an API key since 26 Aug 2026 (`Authorization: Bearer`). `PythAuth8802` adds the owner's saved
key to every Hermes call. Without a key it probes once every 30 min and never opens the SSE stream.

## 13. Inferred values are not facts (8803)

The 5.0.8797 dataset audit (56,583 rows, 6,526 coins) looked for values the bot derived and then stored as observations:

| Value | What it was | Rows affected | Now |
|---|---|---|---|
| `curve` (bonding-curve progress) | the market cap inverted through the curve formula | 580 rows "graduated" under 5 min old; 2,081 at 0 % below the opening price | read from the curve itself (graduation, a proven PumpSwap pool, the live curve account), else unknown (`MemeMeta7973.curveProgress8803`) |
| MCAP fact on a live pump curve | any writer, including print × supply and mark rescales | 2,081 rows below the curve's opening price (as low as $212) | refused outside 20–1,000 SOL of cap while the curve is live (`PrintWitness8750.curveCapAllowed8803`) |
| labels with exit mark = entry mark | a feed that never moved, scored as an outcome | 4,553 labels (8.0 %; 3,490 fillable; 1,695 from Meteora pools), all exactly −cost | not an outcome for any learner (`EdgeModel8537.noNewPrice8803`, in `replayAccept8642` and the live learn path) |
| `v3` | 0, the unscored default | 47 % of rows | unknown when V3 never scored the coin |

Still open:
- Only 1.7 % of labels are chain-witnessed.
- 1.5 % of lane rows from the same decision disagree on cap by more than 2×.
- 263 same-coin cap readings jump more than 3× inside a minute.
Each is counted for the report rather than guessed away.

## 14. Candle colours, named candles and chart shapes, graded as trades (8807)

The owner reads charts by the runs of green and red candles against the shape, and by the textbook candles and chart
shapes. `CandleColors7968` already learned five-candle colour sequences, but alone (no shape), scored by a touch
(+2 ATR before −1 ATR), mostly from the bot's own 15-second candles of minutes-old coins, and its setup had no graded
trades. On 611 clean recorded launches, colour sequences chosen on the earlier two thirds made +1.2 % on the later third
against a +1.4 % base.

`ColourShape8807` keys every candle of every downloaded 1-minute and 5-minute meme chart three ways:

| Key | Reads |
|---|---|
| `g1r4g4:G\|HU-` | the last three colour runs and their lengths, the newest candle, then zone in the last 30 candles (L/M/H), leg (U/D/F), run volume (V/v/-) |
| `C:HAMMER\|LD` | a named candle (with its wicks) against the same zone and leg |
| `P:DOUBLE_BOTTOM` | a named chart shape, from swing pivots, at the candle that breaks it |

Each key is scored by what a trade entered there made on the scale-out ladder net of cost, one outcome per chart, in two
halves of charts that share no coin. PROVEN = both halves n ≥ 12 and mean − 2 SE > 0. A proven key completing on a live
one-minute tape opens a paper ladder; its fire is admission evidence only once the learner's own paper record is armed
(`ManualRead8806.armed`). Bearish names are measured as buys, not shorted.

Source of charts: `ChartSources7955`. Solana 1 m / 5 m candle jobs are fetched again under the key prefix `G8807`. The
top 50 Solana meme coins by one-year gain (CoinGecko category list, found on GeckoTerminal by name) and the famous
runners are served before every other download origin.

## 15. The paper book prices its own positions (8808)

The 5.0.8802 report, paper meme book by exit: trailing stops about +4.7 SOL (MOONSHOT +29 % a trade), hard stops about
−15.2 SOL (stops of 4–25 % closing at −23 % to −62 %), and about 800 closes LOST_MARK at exactly −cost. The stop-slip
line put the first sight of a breached stop 15–17 points under its level. The forward labeler priced the same kind of
coins 96 % of the time, so the coins were not dead: the book was not being marked.

Cause: the book asked for marks through the shadow books' set (`KeylessMarks8359.wanted`), which sat at its cap of 400
and feeds 40 curves a read, longest-unmarked first.

Now:
- `KeylessMarks8359.noteBook8808`: the book's open positions are their own class, never evicted by a shadow ticket, and
  read after the wallet's held coins and before every shadow and label mint (`wantedMints8359`, `bookFirst8808`).
- `BotService` subscribes the book's open curves (`CurveTicks7968`) and pools (`GradTicks8018`) after the wallet's held
  coins (`CurveShapeFeed8236.heldWithBook8808`), so a paper stop is checked on every trade of the coin.
- The report's `keylessMarks8359[... book8808{open, noted, curveMarks}]` shows the class being served.

The stop rule itself is unchanged (two observed marks, then the measured live sell latency).

## 16. Header context on every decision (8809)

The owner reads Dex Paid, boosts and socials before the chart. The 5.0.8802 dataset carried `boost` / `socials` on 147
of 60,792 rows, because `DexScreenerSocialSource` only knew three global lists (top boosts, latest profiles, takeovers).

| Column | Source | Unknown reads |
|---|---|---|
| `socials`, `boost` | every DexScreener pair the bot is served (`DexscreenerApi.parsePair` → `observePair8809`): `info.socials` + `info.websites`, `boosts.active`; a coin with none reads 0 | NaN until a pair of the coin was served |
| `dexPaid` | DexScreener orders endpoint, one keyless call per coin under decision, newest first, 1.5 s apart (`wantOrders8809`); 1 = an approved order, 0 = none | NaN until answered |
| `dexPaidAgeMin` | minutes since the earliest approved order's payment | NaN when unpaid or unknown |

Answers are cached: paid 6 h, unpaid 5 min, failed 2 min. The report's `Header context (§8809)` line counts pairs
observed and orders asked / answered / paid / failed / dropped. These are dataset columns for the learners; no gate reads
them directly.

Not recorded yet: KOL count and top-10 holder share as GMGN shows them (GMGN has no public API). The bot's own nearest
columns are `smartBuyers5m`, `top` (largest holder) and `bundle`.

## 17. The bot's own charts teach the colour table; the stop slip splits its causes (8810)

5.0.8809, 20 minutes on the device: `colourShape8807[charts=0]`. Every downloaded meme chart comes from GeckoTerminal,
whose slot the live candle feed owns (`geckoterminal calls=32 ok=6 skips=110 queue=561`). The bot already keeps its own
one-minute chart of every watched coin (`ChartReader7950`, two hours each). When a tape is retired (quiet for two hours,
trimmed for room, or freed under memory pressure) its whole chart is scored once by `ColourShape8807.learnTape8810` as
one chart of that coin, on one background thread (queue 64; a full queue drops and counts). Report:
`ownTapes8810{offered, learned, dropped}`.

`StopSlip8756` now records the gross at the FIRST mark under a paper stop as well as the trigger (the second mark):
level → first is the price already past the stop when first seen, first → trig is the two-mark confirmation, trig → fill
is execution. The 5.0.8809 run showed EXPRESS stops set at −5 % triggering at −26 %, two of five on a tape print, so
faster marks alone (8808) do not close it.

xStocks (same build): 5.0.8809 showed `gecko ok=2 fail=34 last=http 503` while GeckoTerminal's health row had no 5xx —
the 503s were the app's own refusals (host lockout / shared budget). `XsApi` now counts them as `localRefused8810`, and a
day whose history reads went unanswered is asked again every 10 minutes for the first six hours (UTC) instead of being
marked evaluated with every stock `NO_HISTORY`. Report: `history8810{unanswered, passesToday, retryInS}`.

## 18. Mining entry setups from the datasets: what holds, and the lab counts coins (8811)

Owner: "what about building new entry setups and strategies from the data we have?" Offline, on every edge dataset
combined (165,265 rows, 17,649 coins, 9–15 Nov), one outcome per coin per decision minute (15-minute executable net):

- Boosted trees and decision-tree leaves fitted on 9–13 Nov, graded on 14–15 Nov: the best 2 % made +0.6 % ±3.5; leaves
  that paid +4.7 % / +7.5 % on discovery made −2.1 % / −6.0 % held out.
- The lab's own miner, re-run with each coin counted once and discovery sources as scopes: no box was positive on
  discovery after removing its three best coins and one standard error.
- Phase, cap, age and source splits: every bucket negative in both periods except Meteora-found coins under $20k cap
  (+21 % on 51 coins, +19 % on 32 held out) — carried by a few runners; without its three best coins it does not clear
  the bar in either period. A pocket to watch, not a setup.
- The tape and buyer-flow columns that carry the owner's read were on 104–629 of 27,223 decision moments (from 8801 on,
  13 % of rows for buy share); `buyerFlow8790` read empty on 116,481 of 127,329 reads.

The two things that do hold on the bot's own record: the BASE stage (+4 % to +5 % in four lanes, 15–17 % runners) and the
scale-out ladder exit when stops fill near their level.

`StrategyLab8755` now counts each coin once (`perCoin8811`) in discovery scores, held-out records and its brief — a
coin was written once per lane that judged it, every cycle — and mines discovery sources as scopes beside lanes.

## 19. Crypto startup does not wait on the journal reconciler (8812)

CI runtime acceptance failed on 5.0.8805, 8809, the 8810 follow-up and 8811 with the same stall:
`SERVICE_BOOTSTRAP_STALL_8144 phase=TRADER_CRYPTO_ALT_INIT_STARTING stalledMs=42369`, the crypto trader WAITING on
`CanonicalPaperTransaction6486`'s lock while the startup reconcile pass (two journal replays, the cross-asset history
repair, up to four 5 s journal boundaries) held it. `repairCryptoAtStartup8812` runs the crypto trader's startup repair
(cross-asset history + each open paper crypto position's projection) only if the lock is free within 2 s; otherwise the
same work runs on a background thread after the reconcile pass. Counter: `CRYPTO_STARTUP_REPAIR_DEFERRED_8812`.

## 20. What each heavy job costs (8813)

5.0.8809 on the device: an OutOfMemoryError after 2.5 h, then 1.4 GB/min allocated on a 512 MB heap, ~25 s of GC every
two minutes, 330 MB the store census cannot name, a 4.9 s bot cycle, 22 % of forward labels priced. The heap log names the
busy threads (tree-ranker, cortex-capture, cortex-persist, AATE-Entry) but not what one run of a heavy job costs.

`JobCost8813.measure8813(name) { ... }` records per run: wall ms, the job thread's own CPU, and the MB the process
allocated while it ran (ART `art.gc.bytes-allocated`; an upper bound, other threads allocate too). Report line
`Job cost (§8813)`, largest total allocation first. Measured jobs:

| Job | Where |
|---|---|
| `cortexSnapshot`, `cortexCloseCommit` | `Cortex7885.snapshotEncoder8386` (periodic save; every close commit encodes the full books) |
| `treeParse`, `treeTrain` | `TreeRanker8737.maybeRetrain8737` (8809: one training ran 795 s) |
| `strategyLab` | `StrategyLab8755` hourly run (reads the whole dataset) |
| `managedBandsSeed`, `pocketLedgerSeed`, `payingCellSeed` | startup replays of the whole 48 MB dataset |
| `edgeReplay` | `EdgeModel8537` model rebuild from the dataset |
| `coingeckoCoinList` | `CoinGeckoSolanaTopMcap` id → mint map |

`CoinGeckoSolanaTopMcap.idMintMap8813` scans the ~4 MB coin list in place for each coin's id and Solana address instead of
building a 17,000-object JSON tree.

## 21. A setup coin Helius cannot carry takes PumpPortal's reserve (8814)

Report 5815972e (5.0.8813): Helius answered "max usage reached" (helius / helius_curve 0 %, socket reconnects HTTP 429),
so the curve tape had no transport (`perMintUnavailable=1758`). PumpPortal's day had 94,250 of 100,000 frames streamed;
past the 80 % launch mark (`launchSubAllowed8192`) every setup watch went to Helius only (`setupRoutedHelius=1618`),
leaving 0 trade subscriptions, 0 fresh buyer-flow reads and 0 sniper prints while the reserved frames sat unused.

`PumpFunWS.watchForSetup8191` now asks Helius for the coin's tape state (`HeliusCurveTape8354.askState8386`). Delivering:
done. Unavailable and the day under `SETUP_TOP_PCT_8814` (95 %) of frames: the coin takes PumpPortal's keyed stream
(`setupOverflow8814`). Launches still stop at 80 %, held coins keep everything above 95 %, the day's 0.10 SOL cap is unchanged.
Status: `setup8814[overflowToPump unserved topPct]`; counter `PUMP_SETUP_OVERFLOW_8814`. `unserved` counts setup coins
neither source could carry.

## 22. Where the CPU goes; pocket cell keys built once (8815)

Report 5815972e: 1.1-1.4 GB allocated per minute in steady state and GC blocking most of the run; the CPU table names the
thread pool (DefaultDispatcher 32 s CPU/min, AATE-Entry 9 s, launch-replay 7 s) but not the code. `CpuSample8815` reads every
RUNNABLE thread's stack once a second (started from `HeapLog8618.sample8618`, device only) and tallies the top app frame
(with the library leaf, e.g. `<JSONTokener.nextValue>`) and the deepest app frame, per thread family; socket / file / poll
waits count as io. Report line `CPU sample (§8815)`: largest shares of CPU stacks.

`PocketLedger8610.cellKeys8815` returns the same keys as `keys8610(...).map { "$horizon|$it" }` but builds each composed
string once and reuses it (the seed allocated 5.25 GB in 24 s composing ~200 throwaway strings per row). Used by the
ledger's learning (`record`) and its live verdict (`verdict8610`). Cache bounded at 400k strings.

## 23. The setups learn from every chart the bot sees; the honest fill (8816)

Offline on every dataset (175,766 decisions, 19,109 coins, 9-16 Nov), no setup built on the decision snapshot held on
later days once each coin counts once; the average decision fell from -1.8 % (9 Nov) to -9.4 % (16 Nov). The setups are
rebuilt from the candles themselves (ColourShape8807, the owner's read), which had seen 44 charts in a day because
downloaded meme charts arrive from GeckoTerminal at about one a minute.

`OwnBars8816` records every witnessed print (`TradeTape8100.launchEvidence8750`: the Helius program tape decodes every
pump.fun curve trade, the PumpSwap / PumpPortal tapes theirs) as one-minute USD candles: at most 400 coins, 240 candles
each, primitive arrays. A coin quiet for 15 minutes, or full, is handed to `ColourShape8807.learnTape8810` once and never
recorded again; the learner refuses a second chart of a coin (`duplicate8816`). When any key is proven, each closed candle
of every recorded coin is read live (`onBars8807`: paper fires, ARMED evidence) and its prints step the open paper ladders.
Status: `ownBars8816[recording prints handedToLearner tooShort refusedFull liveReads noSolUsd]` inside the §8807 line.

Honest fill: `ColourShape8807.outcome8807` enters at the next candle's open (the read happens when the signal candle
closes); `ManualRead8806.Ladder` fills a stop or trail at the price seen when crossed, never better than its line. On the
device's launch paths, buying at the signal's own price made +1.4 %, buying in the next 10 s bar -9.4 %. The table
restarts in `colour_shape_8816.json` (the 44 charts scored under the old fill are dropped with `colour_shape_8807.json`).

## 24. Batch replays yield to the screen (8817)

CI runtime acceptance on 5.0.8813 and 5.0.8816 failed the same way: SplashActivity ANR with the main thread RUNNABLE in an
ordinary draw, 47 s of its schedstat waiting for a CPU, while the startup dataset replays ran at default priority beside it
(JobCost8813: 1-5 GB and 8-24 s of CPU each). `JobCost8813.measureBackground8817` runs a measured job at Android's
THREAD_PRIORITY_BACKGROUND and restores the thread's priority after; used by pocketLedgerSeed, payingCellSeed,
managedBandsSeed, treeParse, treeTrain, edgeReplay, strategyLab and coingeckoCoinList. The Cortex snapshot / close commit
keeps its priority (a close waits on it). Counter: `backgroundRuns8817` in the §8813 line.

## 25. What the CPU sampler named; a close already carried; the chart store turns over (8818)

Report 494b46bb (5.0.8817, 65 min, 199 GB allocated, GC blocking 1.9M ms):
- `ErrorLogger.writeToDb` inside SQLite insert was the largest single frame (7.4 % of CPU stacks): one auto-committed
  transaction per row into a table trimmed only at app start. Rows now queue and one runnable writes all queued rows in
  one transaction (`flush8818`), the table is trimmed to 500 rows every 1,000 rows by id, the database uses WAL.
  Report line `Error log writes (§8818)`.
- `DynamicAltTokenRegistry.getTokenByMint` scanned and copied every row on each miss (2.4 %): an address -> key index,
  rebuilt when the row count changes or every 5 s, each hit checked against the live row.
- `ShadowCohortBook8397.decode` parsed with Kotlin's regex-screened toDoubleOrNull (2.2 %): `num8818` parses directly.
- `TradeHistoryStore.normalizeTradeModeName` (regex per leaderboard row; also in the 8816 CI ANR trace): memoised.
- Cortex close commits: 59 in 65 min, each re-encoding the graph (pending 9.9 MB, up to 240 s). A close whose receipt an
  earlier snapshot already committed (`LearningPersistence.receiptCommitted8818`) skips its encode; counters
  `closeCommits8818{encoded joined}` in the §8386 snapshot line.
- `OwnBars8816` handed 0 charts (recording 372/400, refusedFull 197, noSolUsd 8,620): the SOL rate is read as of now, a
  full store retires its stalest coin (quiet 2 min) for a new one (`evictedForRoom8818`), a chart is handed in at 120
  candles.

## 26. The lot is remade from clean data (8819)

Owner: "the setups were built off data with bugs. so they need to be remade. the lanes strategies hunters setups the
lot" (choice: clean rebuild, old tables archived). `CleanRebuild8819.armOnce8819`, first thing in
`LearningResetSweep7781.applyPendingAtBoot` (before any learner loads), once per install of version 8819:
1. copies every learning store the full reset knows (LEARNING_PREFS_7781 prefs files, LEARNING_DBS_7975 with their
   WAL / journal, LEARNING_FILES_7975) to `files/learning_archive_8819/{shared_prefs,databases,files}`;
2. sets the learning epoch (`LearningEpoch8164.set8819`) to `CLEAN_FROM_MS_8819` = 2026-11-16 08:30 UTC (after the Helius
   plan was restored): finalized closes before it never reach a learner, and `EdgeModel8537.replayAccept8642` (the one
   dataset acceptance rule of the edge model, tree ranker, pocket ledger, paying cells, managed bands and strategy lab)
   rejects rows recorded before it — which also fixes a reset re-seeding those learners from the whole file;
3. arms the full reset, which completes in the same boot.
The reset now also clears `edge_model_8722.bin` and `colour_shape_8816.json`. Untouched: wallet, keys, config, positions,
fill / finality / idempotency ledgers, balances, journal, canonical economic records, safety facts, hard checks.
Status: `cleanRebuild8819[...]` on the learning-reset line.

## 27. A restore duplicate names its lots (8820)

CI runtime acceptance on 5.0.8819 failed once on `duplicateMints=1 after restore` (first time in the recorded runtime
history) and nothing said which positions made the duplicate. `CanonicalPaperTransaction6486.duplicateDetail8820` lists,
per duplicate group, each open paper lot's lane / asset class / position id / open time / remaining quantity / lifecycle;
it is appended to `DUPLICATE_SAME_MINT_INVENTORY_REPAIRED_6490` and, as `dupLots8820=`, to the
`CANONICAL_BOOTSTRAP_READY_6515` line after the fields the device check reads.

## 28. Every paper coin keeps a price; the log cannot flood the heap (8821)

Report bc4c42e1 (5.0.8820 after the clean rebuild): the paper book closed most of its coins LOST_MARK (CASHGEN 254 of 483,
EXPRESS 374 of 834, three of them past +100 % first). Its marks were tick / labeler / curve / tape / constant-product pool
only: a graduated coin, a concentrated pool (DLMM, CLMM, Whirlpool) or a coin off the watchlist had none, and lane proof
counts priced closes only — every lane was judged on its stopped losers. `BookPrice8821` asks Jupiter
(`KeylessPriceSources6996.jupiterBatch`, keyed then keyless, 50 a call) every 20 s for the book's coins with no other
observation under 30 s (`ShadowMark8360.observedOtherThan8821`); the answer is one more observation (`JUP`) in
`ShadowMark8360.observedTimed8372`, aged and jump-guarded like the rest. Status `bookPrice8821[...]` in the data pipeline line.

ErrorLogger: the same run crashed OutOfMemoryError on ErrorLoggerIO (113,731 rows in 16 min; ERROR rows bypassed the
pressure bound). A hard ceiling of 2,000 queued rows now holds for every level but CRASH (`droppedHard8821`), and the
§8818 line lists the components offering the most rows (`top8821`).

## 29. Every stored launch hour is a chart for the setup learner (8822)

The remade setups are ColourShape8807's (colour runs and shapes, proven out of sample on the honest fill). 5.0.8820 had 21
charts: OwnBars8816 lives in memory and the run crashed after 2.7 h. `LaunchReplay8744` already reads every stored launch
(LaunchHour8628, 48 h, up to 6,000, spikes removed) every 30 minutes; each launch whose first hour is complete is passed
to `ColourShape8807.learnLaunch8822`, built into one-minute candles (`minuteBars8822`) and learned once when it has
LOOK + MIN_FUTURE + 1 candles. Status `launches8822{learned short}` in the §8807 line.

## 30. The dump a stop cannot save is a cost (8823)

Clean-epoch labels (from 16 Nov 08:30 UTC; 9,792 15-minute decisions, 1,521 coins): the median coin moved -0.6 % before
costs, -3.7 % after; decisions losing more than 50 % summed to the whole net loss. Under $10k liquidity 13-24 % of
decisions lost more than half in 15 minutes (both halves of the window), from $50k up 0-7 %; 679 of 1,226 admitted
decisions were in those bands at -15 % each. `PocketLedger8610` keeps per horizon and liquidity band (its own bands) the
observations, the dumps (net <= -50 %) and their summed loss, from the same labels as the cells (seed + live, one count
per observation). `gapCostPct8823(lane, liquidity, stop)` = (sumDumpLoss - dumps x stop) / n (n >= 40, else 0), and
`NetEdge8030.preTradeCostPct8329` adds it to every entry's cost (admission economics, live edge gate, promotion,
runner grab, entry economics). Report line `Gap cost (§8823)`.

## 31. The $10k crossers that keep going (8824)

Owner: "focus in on the coins moving from 10k up ... grabbing 100-500% profits and getting out". The owner's launch export
(2,354 clean launches, 645 crossed $10k): bought blind at the cross the best exit averaged -1.4 %; crossers that had
printed >= 44 buys since birth (n163) averaged +13.5 % under the DOCTRINE ladder with a 35 % stop, positive in both halves.
`LaunchReplay8744.Rule.totalBuys` is the live buys since birth before the cross; `trigger8748` counts it beside the 60 s
window, `TOTAL_FAMILY_8824` (360 rules: $10k / $15k, 1 / 8 window buys, 25 / 45 / 80 total, 20 / 35 / 50 % stops,
DOCTRINE / SCALE_OUT / HALF_TRAIL x3, 10 / 20 s latency) joins the grid and is proven under the same bar (DOCTRINE,
latency, n >= 100, ex-top-3 mean - SE > 0). `LaunchSniper8748.St.totalBuys` feeds the same trigger live;
`StrategyLab8755` stores `totalBuys` and the lab prompt offers it. An entry inside an armed fire's 120 s evidence window is
not charged the band gap (`NetEdge8030.gapExempt8824`): the rule's record already is its net outcome on that trigger.
Status `cumulative8824{best vsWindowOnly}` in the §8744 line, `replayFireExempt8824=` in the §8823 line.

## 32. Which way each signal points, and the crossers in front of the loop (8825)

Clean-epoch labels (1,734 coins, first decision each, 15-minute target): every buying-activity reading the playbook's
hand setups trigger on predicts a WORSE outcome, in both halves (newBuyers15s -0.25/-0.26, buyTx15s -0.26/-0.15, txVel
-0.18/-0.21, buyersPerMin, tapeNetSol, runupFromLow, dd); age, secsSincePrint and pxPeak point the other way. The learned
edge model ranks the right way (Spearman +0.35) but its top decile is -3 %: the round trip. 973 of the 1,734 coins were
under a $5k cap and 24 s old; 157 were $10-30k.

`SignalTruth8825` keeps, per signal (20) and age band (5), the first labelled observation of each coin (ring of 200,
fed by `EdgeModel8537.learn` and its boot replay) and calls a direction only when the older and newer halves both pass
1.96/sqrt(n-1). Report line `Signal truth (§8825)`; the strategy lab's prompt receives the agreed directions
(`promptLine8825`). `LaunchReplay8744.attentionOf8825` names the best graded cumulative-family rule; `LaunchSniper8748`
puts every coin meeting its trigger on the watchlist (`LAUNCH_SNIPER_WATCH_8825`, no evidence), so the decision loop and
every learner see the $10k crossers before any rule is armed. Status `attention8825{on fires watchlistAdds}`.

## 33. The owner's 300 handwritten setups reach the Lab as unproven research (8826)

The owner's complete source is `docs/AATE_Meme_Signal_Bible_300.md`: 240 ON/OFF signal descriptions in 20 evidence
families and 60 Trigger/Fail/Manage playbooks in 10 strategy families. `ci/generate_signal_bible_300.py` compiles every
description into `SignalBible300.kt`, validates consecutive IDs and linked references, and rejects source/code drift in
the main-branch APK workflow. It is a catalog of **hypotheses**, not 300 gates or 300 independently proven strategies.

`LlmLabEngine` now presents a bounded rotating sample of six complete recipes per creation cycle to the existing
`StrategyLab8755` hypothesis writer, *after* the measured age-specific `SignalTruth8825` direction evidence. The prompt
requires a representable typed CELL/LAUNCH/BASE/RUNNER hypothesis, causal source coverage and held-out after-cost grading;
unobserved parts of a recipe remain UNKNOWN and cannot be fabricated as a bullish signal. `SignalBible300.LIVE_AUTHORITY`
is **false**. None of the handbook recipes directly touches `LanePlaybook7907`, the V3/V4 entry owners, FDG, position
management or protective sells. This preserves the existing authority doctrine while giving the Lab a larger research
menu. The report line `Handwritten signal research (§8826)` proves the catalog is linked into the APK, including its
content hash and 240/60 coverage. Unit tests check definitions, traceability, research rotation and the absence of an
entry-gate path. Do not report the recipes as validated or live until a distinct, audited implementation proves them.

## 34. What the bot chooses against what pays; one model refusal for both books (8827)

Clean epoch, every final-gate verdict (11,512 decisions): the admission favoured, in both halves, thirteen readings that
predict a worse 15-minute outcome (newBuyers15s, runup, runupFromLow, chg5m, fromFirst, buy shares, turnover, buyers per
minute, tape net SOL and crowd) and disfavoured the bot's own score v3; it ignored the edge model's prediction (admission
+0.01, outcome +0.35) and age (+0.02 / +0.34). 1,126 of 1,432 admitted decisions carried a negative prediction (-11.5 %,
both halves negative); the 306 it kept averaged +4.4 %.

`SignalTruth8825` rings also hold the verdict (admitted 1 / refused 0); each (signal, band) reads the admission's lean on
both halves and classes it INVERTED / IGNORED / ALIGNED against the outcome (`choice8827`, `choiceLine8827`; signals now
include v3, pred, age, fromFirst, buy shares, turnover). `CandidateAdmission8136.edgeModelRefusal8537` binds paper and live
alike once the lane's refusal record is earned (refused coins are still labelled, so the record stays honest); every
negative prediction let through is counted by why (`edgeLetThrough8827`). Report lines `Admission vs outcome (§8827)` and
`Edge refusal (§8827)`.

## 35. The owner's signals graded on the stored launches and traded only once proven (8828)

`SignalTape8828` reads 15 SignalBible300 signals (S001 S003 S005 S006 S009 S010 S011 S012 S014 S016 S023 S049 S050 S051
S052; five need buyer wallets) on one coin's prints as 10-second bars, evaluated when a bar closes; only a live closing
print can turn one on, once per coin. `SignalReplay8828.grade8828` feeds every LaunchReplay8744 path through it (wallet ids,
SOL sizes, live flags), enters each first turn-on at the bot's required latency and unwinds on DOCTRINE with 20 / 35 %
stops (`exitNet8754`). Records: n, mean, SE, median, ex-top-3 mean and SE, win, 10x, half means by birth, median cap.
Proven = n >= 100, ex-top-3 mean - SE > 0, both halves positive; shadow = best unproven with n >= 50. LaunchSniper8748
feeds every first-hour print (with its SOL size and wallet from `TradeTape8100.launchEvidence8750`) into the same tape
(at most 1,000 coins); the proven signal's turn-on is an armed fire (watchlist + admission evidence). Report line
`Signal replay (§8828)`; the strategy lab's prompt gets `labLine8828`.

## 36. The program tape in compact frames; rules graded at the latency the bot has (8829)

5.0.8820 report (6,510 s): the pump.fun program tape sat in its per-mint fallback (`BYTES_OVER_3MB_MIN`, stepDowns=6,
failedTx=106,502 of 158,085 frames) while Helius credits stood at 21 % of the day; the PumpPortal stream was off on its 0.10
SOL daily budget. Prints reached the bot for 1,073 of 27,930 births, the tape lag was p75 30 s, the required latency 38 s —
beyond the replay grid's longest delay (30 s), so no launch or base rule could ever be proven or watched (sniper IDLE).

`HeliusEnhancedWS.sendCurveSubscribe8354` asks for `encoding: base64` (the handler reads only signature, meta.err and
meta.logMessages, which stay JSON). After a fallback hold the tape re-probes on the Enhanced socket (server-side
`failed: false`) when the plan has it, logs otherwise. `HeliusCurveTape8354` meters frames, bytes and failed transactions per
transport (`perTransport8829{enhanced= logs=}`). `LaunchReplay8744.rulesFor8829` / `BaseFamily8754.evaluate8754` grade every
DOCTRINE rule also at the required latency rounded up to 5 s (`atLatency8829`) when the grid lacks it.

## 37. The entry cost's gap read is cheap and silent (8830)

5.0.8823 and 8824 failed the runtime smoke on a SplashActivity ANR (app 158-160 % of the emulator's two cores, the main
thread waiting on CPU); 8822 passed. 8823 added the band gap to `NetEdge8030.preTradeCostPct8329`, which every sizing and
economics loop calls: per call it built the full band map, re-derived the lane's horizon and computed the stop through
`FieldManual7715.stopPct8392`, whose STOP_WIDENED telemetry then counted cost reads as stops set. Now
`PocketLedger8610.gapCostPct8823(lane, liq) { stop }` reads the liquidity band alone (`liqBand8830`, the band `bands8610`
files), the lane's horizon prefix cached a minute per lane, and evaluates the stop only when a seeded band holds a record
past N_MIN; the stop is `FieldManual7715.stopPctQuiet8830` (same value, no telemetry); the replay-fire exemption is read
only when there is a gap.

## 38. The strategy lab's miner counts without copying (8831)

5.0.8820 report: one strategyLab run allocated 25,125 MB (1,370 s). The miner re-filtered the discovery rows and re-hashed
their mints (`perCoin8811`) for every box of every feature in every scope (~6,000 boxes). The runtime smoke kept failing on
SplashActivity ANRs with the main thread SUSPENDED (GC) and the app at 164 % of two cores. `StrategyLab8755.CoinIndex8831`
gives each row a coin id once; `accOver8831` walks a scope's row indices and counts each coin's first accepted row with a
stamp array — the same per-coin semantics, no list or set per box. Scopes are index arrays built once per run.

## 39. Crypto's measured five-minute evidence reaches the canonical gate (8834)

5.0.8833 (17 Nov) showed a crypto producer that was alive (`CRYPTO_ALT` candidate/submit=638/638), but
the canonical gate blocked 638/638. Its source-level upstream repair preserves DexScreener's dated 5m
buys/sells/volume/change in `DynamicAltTokenRegistry` and grades them in `CryptoAltTrader`, yet
`CrossAssetCortex7931.tokenStateFor()` reconstructed a price/mcap/liquidity-only `TokenState` for
`LiveEdgeGate7877`: it discarded the current 5m buyer pressure, movement, volume and the genuine
CryptoLaneDesk price candles. It also restamped the candidate's price at gate evaluation time.

`CrossAssetCortex7931.bridgeCryptoObservedContext8834` now carries the *same source-dated* 5m
observations and genuine 8832 candle snapshot into the canonical `TokenState` before the existing
live-edge gate judges the candidate. Source age is limited by `CryptoShortWindow8832.from` and
`NativeDecisionEnrollment8372.observedPriceAt`. Stale/mismatched/absent observations remain
unobserved; the bridge does **not** infer new buyers from 24h counts, backdate price proof,
or create synthetic scan ticks. `CryptoLaneDesk7391.observedCandles8834` returns an immutable
copy of the already-sampled candles, filtered to the candidate's verified price observation.
`Aate8834CryptoCanonicalEvidenceTest` covers both evidence survival and stale/fabricated cases.

This is an upstream information-loss repair, not a new gate or a blanket crypto override:
the wallet, venue, hard safety, CanonicalEntryAuthority6551, LiveEdgeGate7877, expected-value,
sizing, fee, pending-position and immutable intent requirements remain binding. Telemetry
`CRYPTO_CANONICAL_{PRICE,FLOW,CANDLES}_{PROVEN,UNOBSERVED}_8834` shows what the live gate
actually received. Passing tests proves data continuity, **not** profit or live trade approval;
subsequent chain-finalised after-cost closes must demonstrate improvement.

## 40. Crypto canonical evidence respects the TokenFacts single writer (8835)

The 8834 release APK compiled and its runtime smoke test passed, but three original
`Aate8551TokenFactsTest`, `Aate8552PriceFactsTest` and `Aate8553DataBundleTest`
source-contract tests failed. The 8834 bridge wrote raw price timestamps,
liquidity, market cap and pressure/change directly into a newly created
`TokenState` instead of offering them through `TokenFacts`, the application's
sole market-data writer. The release gate correctly refused publication.

8835 uses `TokenFacts.writePrice` only for the source-proven, price-matched
provider observation. An unmatched or stale candidate price is a RESTORE fact
at timestamp zero; it never acquires a fresh gate-evaluation clock. Candidate
liquidity and registry market cap have no independent source observation clock
here, so they are mirrored as RESTORE facts through `restoreLiquidity` and
`restoreMcap`. Real fresh m5 buy/sell pressure and price movement enter as
dated provider snapshot facts through `writeSnapshot`; missing observations
leave the default neutral values unstamped. The old `LiveEdgeGate` / wallet /
canonical execution rules are unchanged. The 8834 regression tests now assert
the fact store's rank, time, legacy mirrors and stale/zero-flow behavior, and
the existing 8551–8553 tests forbid raw writes outside the store.

## 41. Paper buy journal commit and unfunded sweep cannot race (8836)

8835's full JVM tests and APK compilation passed, but the runtime device restart
test observed one mint restored twice (QUALITY and CYCLIC). The device's own
forensic timeline shows WHY: the QUALITY position opened at 08:18:17, the
unfunded-position maintenance sweep removed that same OPEN lot while its buy
was settling, and `positionDidOpen` consequently failed. The paper executor
rolled back the ledger and position, but it had already written a durable BUY.
Two minutes later CYCLIC opened the mint normally. A restart reconstructed the
aborted QUALITY event as an OPEN lot beside the valid CYCLIC event.

8836 repairs both producers, without suppressing duplicate detection or
loosening the restart gate. `CanonicalPositionAuthority6441` will not purge
recent, positive-cost PAPER PENDING/OPEN rows during the 60-second settlement
grace (the executor buy lease is 20 seconds). It rechecks funded-lot state and
position identity under its write lock before removing truly stale orphans.
`Executor.paperBuy` publishes `EconomicEventSchema6464.recordBuy` only *after*
the canonical position, actual funded lot, occupancy, canonical economic event,
durable fill-lot proof and commit stamps have succeeded. A later rollback
cannot leave an executable-but-aborted BUY in the durable replay journal.
`Aate8836PaperSettlementRaceTest` covers the grace boundary and source-order
invariants; no safety gate, fee, risk setting or live execution path changes.

## 42. The model's refusal is earned by what it refuses (8839)

5.0.8837 report: in every lane the coins the edge model predicted to lose went on to lose (-8.6 % to -17.9 %, SE 1.5-2.8,
n188-983 a lane), yet no lane held the refusal: 8750 also required 30 kept coins separated by two SE, and the model kept
0-15 a lane. 655 negative predictions were bought (edgeRefusal8827 letThrough, all UNEARNED). Owner's dataset to 17 Nov:
89 % of admitted 15-minute decisions carried a negative prediction (-9.5 %); the 676 with a prediction >= 0 averaged
+3.9 % (per coin +1.8 % ± 1.9: flat, not proven). `EdgeModel8537.refusalAuthority8715` now holds when 30+ refused coins'
mean is two SE below zero (`REFUSAL_Z_8839`); the kept side's record still decides the picks' authority.

## 43. Who is behind the launch: creator, dev, bundle, snipers, holders (8840)

5.0.8837 learner coverage: devTokens 0 %, devRugRate 0 %, insider 0 %, launch-tape wallet columns 14-22 %. Offline (3,180
launches) price/flow/count features lifted the +50 %-before--30 % rate from 19 % to ~30 % out of sample; 41.6 % pays.

`ChainLaunchFeed8179.decodeCreateEvent8840` decodes pump.fun's CreateEvent (discriminator sha256("event:CreateEvent")[0..8]
= 27,114,169,77,222,235,99,118; name, symbol, uri as borsh strings, then mint, bonding_curve, user = creator).
`HeliusCurveTape8354.creates8840` reads it from every program-tape transaction (lines prefixed "Program data: G3KpTd7r")
before the transaction's trades; PumpPortal's create frame feeds the same call. `LaunchFacts8840.Fold8840` folds a launch's
prints in chain order: creator initial buy (creation second), held, sold share (all / first 10 min), bundle (non-creator
first buys in the creation second), snipers (within 2 s), top-1 / top-5 net holdings, distinct buyers — % of the 1e9
supply. `DevHistory8840` keeps per creator the launches seen to retire, those reaching a $30k cap inside the hour, those the
creator sold >= 90 % of inside 10 minutes (dev_history_8840.tsv). Live: `TradeTape8100.launchEvidence8750` feeds every
witnessed print; LearnerColumns8798 gains 15 columns; SignalTruth8825 measures 9 of them. Export: LaunchHour8628 stores
`creator(m,c,s,pl,pr,pd,t)` and writes one `FACTS_8840` row per launch with the facts at 15, 30, 60, 120, 300, 600 and
1,800 s from birth (same Fold over the stored prints) and the creator's record as of birth. Report line `Launch facts (§8840)`.

## 44. A launch's traders in primitive arrays (8853)

8840's `LaunchFacts8840.Fold8840` held each launch's traders in a HashMap of wallet strings to boxed records (up to 3,000 live
launches x 600 wallets, ~200 bytes a wallet). The 19 Nov report was the emergency fallback (full builder timed out at 8 s,
anrHints=11, cycle max 43 s), so the heap could not be read; the store was a measurable risk regardless. Wallets are now
64-bit keys (`walletKey8853`, FNV-1a) in an open-addressed table of primitive arrays that grows from 32 to
`WALLET_SLOTS_8853` = 1,024 slots (40 bytes a slot); the creator is matched by key; live launches capped at 1,500. The
facts are unchanged (Aate8853 checks them against a plain per-wallet count).

## 45. The 8096 exit, graded in the launch replay (8854)

APK 5.0.8096 (`cddf86c17`, PF 2.15, ~2.6x balance) and 5.0.8114 (`379ad2fa0`, 32W/47L, 0.195 -> 0.434 SOL) held a
MOONSHOT / QUALITY / BLUECHIP / CORE buy from entry with `LifecycleHold8065`: -40 % floor at any time, half at 2x, 30 % of the
rest at 5x and at 10x, the 45 %-of-peak give-back after +300 %, 15-minute base / trend breaks after two hours. Since 8189 the
hold needs runner evidence first. `LaunchReplay8744.Ladder.LIFECYCLE` is that exit, built from the hold's own constants
(`FLOOR_PCT`, `ladder8071`, `peakGiveback8065`): no trail, no time stop, the rest sold at the hour's last print. Graded on
`LIFECYCLE_8854` (69 rules: every window trigger at every latency, the cumulative family at 10 / 20 s), at the required
latency (`rulesFor8829`), in the base family (`BaseFamily8754.GRID`), and launch by launch against DOCTRINE
(`pairedLadders8788`). Report: `ladders{… LIFECYCLE=…}` and `pairedLadders{… LIFECYCLE=±/n/verdict}` in `Launch replay (§8744)`.
Measured only: `provenOf8748` stays DOCTRINE (the exit live runs). Offline on the owner's export (10 s bars), 1,088 $10k
crossers with 15 buys bought 30 s later read +7.0 % at the bar's close and -14 % at the bar's high under this exit: bars
cannot price the fill; the phone's prints can.

## 46. One grade on the clock the bot follows (8855)

5.0.8853 LIVE: FDG 0 / 322; 179 were `EdgeModel8537`'s refusal, earned per LANE on one 5- or 15-minute label. Stage records in
the same report: BASE +1.0 .. +4.4 % (12-15 % runners) in four lanes; LAUNCH -15.5 .. -22.5 %, PEAK ~-10 %, DUMP -13.7 .. -20 %.
`TrajectoryVerdict8855` keeps ONE outcome per followed decision: `ForwardReturnLabeler7731.gradeTrajectory8851` revises it
at each newly observed read (1 / 5 / 15 min, then 1 / 4 / 24 h while the coin is followed) with `pathOutcome8855` (stopped at
the first read through the lane's stop + round trip, `ManagedBands8719.managed8719`; else the latest read). Obs carries
`pred8855`, `pathNet8855`, `pathStopped8855` (checkpointed); tallies checkpoint as `trajectory_verdict_8855`.
- Refusal: keyed lane|stage. With >= 30 refused decisions the lane-stage's own record decides `Verdict.refuses8715`
  (`authority8855`, same test as `refusalAuthority8715`); below that the lane label record decides. One at a time.
- Looking: `BotService` watchlist order spends the span of the hand freshness (350) + pump-source (160) boosts on the
  stage's rank in the record (`stageShare8855`) once two stages are graded.
- Paper book: `MemePaperTrader8544.entryBasisRefuted8855` voids a position whose entry two independent feeds refute before
  any mark confirmed it (stake returned, not a close); `repair8855` removes stored lane rows whose mean close is beyond one
  unconfirmed mark, with the SOL they added (5.0.8853: CORE n10 +24,099,805.8 %, +240,998 SOL).
- Crypto: `CandidateAdmission8136.priceStale8136` adds the feed's refresh period for crypto-desk lanes
  (`feedPeriodMs8855`, `DynamicAltTokenRegistry.PRICE_REFRESH_MS_8855` = 60 s): the 30 s meme bar made a 60 s-refreshed
  price stale half of every minute (deferCause CRYPTO_MOONSHOT|PRICE_STALE=40).
Report lines: `Trajectory verdict (§8855)`, `Paper book repair (§8855)`.

## 47. Probation watches the floor; gates on the followed clock; proof that accumulates (8856)

5.0.8853 LIVE (5 min after start). Probation (`GlobalTradeRegistry`) was a 30-90 s timer with no price of its own; four of the
six latest missed runners (+325 .. +463 %) got their lane verdict 4.7 - 26.7 s after birth with $3 - $7 in the curve; 22 + 9 of
102 missed 4x runners were refused for liquidity / market cap; the 220-seat watchlist bounced 353 incoming coins and evicted 66.
- `FloorWatch8856`: every witnessed pump.fun launch followed on the market-wide feed (`LaunchSniper8748.onPrint8748`) for its
  first hour. The live print at `FLOOR_CAP_USD_8856` = $5,000 curve cap is the cross: `GlobalTradeRegistry.seatFloorCross8856`
  (WATCHED / PROMOTED / ADDED; at the cap `WatchlistEviction8398.seatVictim8856` frees a seat: an under-floor resident first),
  `HeliusCurveTape8354.ask8354`, and head of the next cycle (`HunterWindow8293` -> `targets8856`). A crosser keeps its seat
  while at or above the floor and printing within one 15-min label (`holding8856`, eviction pool). A followed launch under
  the floor is not timer-promoted (`floorWatched8856` in `processProbation`). `onDecision8856` (labeler `observe`) times
  cross -> first verdict, the price move by then, and the first gate that ruled.
- Gates: `TrajectoryVerdict8855.byGate` holds each buy-path gate's cohort (gate and gate@desk) at the decision's followed
  outcome, revised at every later read (`GateOutcome8418.follow8856` from `gradeTrajectory8851`, once the gate can no longer
  change at 120 s; Obs `gateNet8856`, `gateFlags8856`). `GateOutcome8418.stat8418` serves it in place of the first-grade
  book from n >= 40, so `doorProtects8715`, `selectionGateRetired8696`, `structureBindsByRecord8755` and FdgGate8365's audit
  read the followed clock. Their rules are unchanged.
- Proof: `LaunchReplay8744.Forward8856` — the watched rule (`shadowOf8748`) is nominated when first named; every launch born
  after that, once its hour is complete, is graded for it once and kept (`launch_forward_8856.json`, up to 12 nominees).
  Proven on that record alone: DOCTRINE, the bot's latency, n >= 100, mean - 2 SE > 0, positive without its best launch
  (`forwardProves8856`); `proven = provenOf8748 ?: forwardProven8856`. The 48 h record re-derived ~130 entries for the best
  rule ($10k cross, 3 buys, +2 s: mean +15 %, win 40 %, 2x 13 %) and stripped its 3 best, so it could never arm.
Report lines: `Floor watch (§8856)`, `gates8856{}` in `Trajectory verdict (§8855)`, `forward8856{}` in `Launch replay (§8744)`.

## 48. Two engines: the crypto trader as the simple signal engine (8857)

5.0.8853 LIVE: CRYPTO_ALT paper record n655, W131/L174, +3.0 %/trade, +14.465 SOL; live funnel 139 candidates, 0 allowed
(115 `DATA_INCOMPLETE_DEFER_8136`, the rest learned meme doors); `CryptoSpotPaperBacking8788` backed=0 because it answered only
the expectancy refusal and the candidate then met the meme live edge gate (`CrossAssetCortex7931.liveRefusal` ->
`LiveEdgeGate7877`). `CryptoSignalEngine8857`: at that gate in `CanonicalAssetEntryContract6551.submit`, a refusal binds a LIVE
crypto candidate when it is integrity (`integrityReason8857`: safety, structure, sellability, LP rule, dead launch, identity /
price basis, stale price, route, size, wallet / ledger / duplicates, the bot's own loss locks, the lane's realised live
losses, kill switch, failed evaluations) or a data refusal resting on the executable minimum (`dataIntegrity8857` over the
candidate's own `assess8136`: unknown cost, missing price, a critical fact unobserved; `CrossAssetCortex7931.refusalBinds8857`).
Any other (learned) door is counted (`noteAdvisory8857`), filed on its own record (`noteOverriddenDoor8715`, graded on the
followed clock) and does not refuse while the lane's own record backs the entry (`passes8857` ->
`CryptoSpotPaperBacking8788.backs8788`); the 8788 permit (4 an hour, -0.1 SOL stop) is reserved at the seal for those entries
too. Paper is unchanged. Report line `Crypto signal engine (§8857)`: doors passed, refusals that stood, live closes of each
engine.

## 49. A repaired close is published once (8858)

**Measured (5.0.8853, 315 s after start):** `reward_pop_mismatch closed=2770 bus=2459 missingFromBus=311`,
`BUS_PUBLISH_FAILED=311`, `FINALIZED_BUS_DURABLE_REPAIR_7459=1247`, reconciler cursor 2188 of 2770. Twenty-six earlier
reports show 1,362 to 1,622 repairs in every session; a 10,053 s session read `missing=0`. The 311 were the unfinished
tail of a replay that restarted at every boot, not trades the learners never received.

| Fact | Where it lived | What changed |
|---|---|---|
| A CLOSED position with a durable full SELL and no rich finality row (`final:`; 2,277 rows vs 2,770 closed) | published to the 6464 bus by `FinalizedLearningReconciler7423.repairDurableBusPublishFailures7459`, held only in `canonicalSeen` (memory) | the published envelope is appended to `files/finalized_repair_8858.jsonl` (`FinalizedRepairStore8858.record8858`, only when no rich row exists) |
| The same closes at the next start | derived and published again, 128 per 30 s pass (~6.5 min), audit failing every run meanwhile | `CanonicalFinalityPersistence6486.initAndReplay` publishes them from the file straight after the rich rows (`restore8858`: one envelope per position, the first recorded, skipping what the bus holds); the reconciler's repair waits for that (`settled8858`, at most 90 s) |
| A consumer's durable exclusion | hydrated once per consumer; a consumer registered while the ack store was loading hydrated empty (LearnerRewardBridge, LosingStreakReflex: 3,482 exclusions each per start) | `deliverOne6734` reads the durable exclusion (`hasExclusion8858`) beside the durable ACK |
| The selection ring (`SelectionQualityAuthority6829`) | fed inside `publish()` from every replayed close, never met the 8164 reset line | a close settled before the learning epoch is not recorded |
| A replayed close in `DecisionReasons8031.sell8031` | booked as a sell decision at the current price | only a close inside `ExitRegret7752.FRESH_CLOSE_MS_7752` is a decision made now |

The file is finality evidence: no learning reset removes it. Report: `Finalized ID reconcile (§7423)` ends with
`repairStore8858[held restoredAtStart recordedThisRun alreadyOnBus unreadable writeFailed replayComplete]`.

## 50. Graded reads kept; the reason a read is missed; the curve's own proof of quiet (8859)

**Measured.** 5.0.8853: `trajectory8851[... observed=45897 unavailable=35905 cells=4096]` with 13,321 graded reads held in
the cells — the book was at its cap, evicting its first-created cells. Owner's export of 18 Nov: 55,812 labelled decisions,
12,138 (22 %) recorded unfilled; 8,288 of those had no tape print at the decision, 6,656 were under ten minutes old.

| Fact | Before | Now |
|---|---|---|
| `TrajectoryGrades8851.Book` at 4,096 cells | removed the first cell ever created | access order: removes the cell no read has reached for longest; `evicted8859=cells/reads` reported and persisted |
| A horizon that closes without a read | one `unavailable` total | `trajectoryMiss8859{cause=n}`: `NO_MARK_IN_WINDOW`, or the first requirement an in-window mark failed (`ENTRY_UNFILLED`, `MARK_NOT_LIVE`, `BASIS_INELIGIBLE`, `BASIS_LATE`, `IMPLAUSIBLE`, `VENUE_UNSCOPED`) |
| A pump.fun TradeEvent's reserves and token amount | reserves kept as the price (`CurveReserves7951`) | also compared with the previous print (`CurveContinuity8859.onFrame8859`, before the care filter): reserves before this trade equal to the reserves the last print left prove the curve stood there between them; stretches of 60 s or more are kept (8 a coin) |

`CurveContinuity8859` is **measured only**: `wouldPrice{unfilledEntries=a/b quietReads=c/d}` counts the unfilled entries
(asked when `EdgeModel8537.recordUnfilled8546` files one) and the no-mark horizon reads a proven stretch covers; a question
about a time after the coin's newest print waits for its next print. `continuous / broken` is the stream's own gap rate per
coin; `solSideAgrees` says whether the event's SOL amount is the curve-side amount. No label, fill or decision reads it.

## 51. A wallet's record is kept past the tape (8860)

**Measured (5.0.8853):** SmartMoney8756 graded 6,841 wallets and proved 15, every record re-derived on each run from the
48 hours of launches `LaunchHour8628` holds. A wallet's record could not outlive the tape that taught it.

| Fact | Before | Now |
|---|---|---|
| What following a wallet's first buy returned (n, mean, spread, snipes) | recomputed from the 48 h window each run | a launch about to leave the window (born > 44 h ago; or the oldest fifth when the replay is at its 6,000-launch cap; never inside its first 65 min) is graded once into `files/wallet_ledger_8860.bin` (`WalletLedger8860.archive8860`, 20,000 wallets, fewest-follows / longest-unseen out) and left out of the window's own fit |
| The record SmartMoney8756 proves a wallet on | the window's | the window's plus the kept one (`Acc.with8860`), for the fit, the validation choice, the test grade and the live set — the ledger only holds launches older than every launch in the window, so the time order is unchanged |
| A wallet proven on its kept record that has not bought in 48 h | forgotten | in the live set; fires when it buys again (`provenOnKeptRecordOnly8860`) |
| The creator's record (`LaunchFacts8840.DevHistory8840`) | already permanent: `dev_history_8840.tsv`, 50,000 creators, LRU | unchanged (only the per-launch creator rows in the tape database expire) |

Not a learning store: no reset removes it. Report: `smartMoney8756[... walletLedger8860[wallets n>=3 n>=8 launchesArchived thisRun archivedTo atCap] ...]`.

## 52. The runner model: the tape at the floor cross, kept rows, the whole stored tape, forward proof (8861)

| Fact | Where it comes from | Where it goes |
|---|---|---|
| Every witnessed pump.fun print (price as stored, SOL, side, wallet, chain and receive time) | `TradeTape8100.launchEvidence8750`, beside `LaunchHour8628.trade` | live: `RunnerModel8861.onPrint8861` keeps a launch's live prints (first hour, at most 400) until its $5,000 cross and scores `features8861` there; replay: the same stored prints (`onLaunch8861`) |
| The tape's shape at the cross — 24 readings (`FEATURES_8861`: prints per minute, buy and net SOL shares, unique / largest / top-3 / repeat buyers, first-3-seconds buy share, run-up from the low, 10 / 30 / 60 s returns and buy counts, transaction and volume acceleration, new buyers, sell share) | `features8861`, one pure function for live and replay | one ROW per crossing launch |
| What buying the cross returned | `rowOf8861`: entered where the curve stood after the bot's latency, DOCTRINE ladder, 20 % stop, cost; `doubled8861` = twice the entry before the stop | graded on the whole stored tape: first hour plus `LaunchHour8628.tail8861` (prints up to 4 h from birth — no reader went past the hour before) |
| The rows | written once the 4 h hold window is over | kept in `files/runner_rows_8861.bin` (12,000 rows; the tape itself lasts 48 h) |
| The model | `TreeRanker8737.train8737` on doubled / not; the share it buys chosen on later rows (`choose8861`: oldest 60 % fit, next 20 % choose, newest 20 % read) | live score at the cross |
| The proof | every new row is scored by the model fitted without it, only when its launch is newer than every row that model saw | `forward{picks … of every cross …}`, kept in the same file; ARMED by `proves8861` (n ≥ 100, mean − 2 SE > 0, positive without its best — the launch replay's forward bar) |
| An armed pick | `evidence8861` (3 min) | `CandidateAdmission8136` market cell, after the smart-money evidence; safety, sellability, balance and economics unchanged |

`parity{same differ otherCrossPrint}` compares the features the live path computed at a coin's cross with the row the replay
later writes for it. Unarmed picks are counted (`shadowPicks`). Not a learning store: no reset removes the rows.

## 53. The hive both ways for the runner model: an install census and rows as a union (8862)

**Measured (5.0.8856):** `sharedKeys=93916 mineUploaded=76876 specialistsFromHive=0 seatsInherited=0 runnerSignals[sent=0
pulled=0]`. The hive's existing books (FRL, SPEC, PB, TAIL, CX, DEV, EXP) are already uploaded as deltas and read back as
the rest of the network; nothing on the phone said whether another install exists.

| Fact | Table (collective database) | Rule |
|---|---|---|
| This install is alive | `hive_installs_8862 (instance, build, updated_ms)` | upserted on every `HiveEdge8000.sync8000`; `otherInstalls7d` = other instances seen in 7 days |
| A runner-model row (features at the floor cross, doubled, net, graded past the hour) | `hive_runner_rows_8861 (mint PRIMARY KEY, born, x, doubled, net, past, updated_ms, instance, build)` | `INSERT OR IGNORE`: the first install to grade a launch writes it — a union keyed by launch, never a sum |
| Rows of launches this install did not grade | pulled (`instance != me`, 2,000 a sync) | `RunnerModel8861.offerHive8862` -> the next run; `mine=false`; they train the model and never enter the forward record |
| What this install has sent / pulled | `files/runner_hive_8862.txt` (`sharedToBornMs|pullMs`) | own rows newer than the mark, oldest first |

Wallet and creator records are not pooled: the hive's books are sums, and two installs that saw the same launch would
count it twice. Report: `Hive runner rows (§8862)`; `runnerModel8861[... rowsFromOtherInstalls8862=n ...]`.

## 54. The launch freeze and the start-up load (8863)

**Measured.** Runtime acceptance, 5.0.8854 / 8860 / 8862: `ANR in com.lifecyclebot.aate (SplashActivity) ... Waited 5000ms
for FocusEvent(hasFocus=false)`, process at 257-291 % CPU, main thread inside a dashboard frame. Owner's phone, 5.0.8856,
185 s after start (19 Nov 05:24; dataset export of the same minute: 57,955 rows, 80 MB): job meter `treeParse cpu5624ms`,
`pocketLedgerSeed cpu6782ms`, `payingCellSeed cpu6263ms`, `managedBandsSeed cpu6666ms`, `strategyLab cpu3570ms`;
`errorLog8818[rows=58917 transactions=13643 ... top8821=FORENSIC:36194 ...]`; CPU sampler `ErrorLogger.flush8818
<SQLiteConnection.nativeExecute> 3.3%`, log thread 6.9 % of all CPU stacks.

| What was happening | Where | Now |
|---|---|---|
| The splash's hand-off started the dashboard with a bare intent: over a task that already held a `MainActivity` it built a second one (7,050-line layout) on the main thread | `SplashActivity.dashboardIntent8863` | `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP`: the dashboard that is there comes back; one is created only when the task holds none |
| Five passes over the labelled dataset in the first three minutes, each building a JSONObject for every line it read to have `replayAccept8642` refuse it (57,428 of 57,955 rows: before the learning line, or unfilled) | `EdgeModel8537.refusedUnparsed8863` | a row whose own text shows `"fill":false`, or a first key `"t"` before the learning line, is not parsed (edge replay, tree ranker, strategy lab, the ledgers); anything the text cannot prove is parsed as before |
| Pocket ledger, paying cells and managed bands each read and parsed the same file on its own thread at the same time | `DatasetReplay8863.await8863` / `pass8863` | one read: each line parsed once, the rule asked once, the row given to the three ledgers in the order written; each ledger's worker waits for it, so live labels are still filed after the replay |
| Readers that keep the newest rows counted the file by decoding every line | `EdgeModel8537.countLines8863` | counted on the bytes (`\n`, `\r`, `\r\n`; an unterminated last line counts) |
| Every forensic line (196 a second) was inserted into the error log's SQLite table and shared its pressure bound | `ForensicLogger.write8863` | logcat (`AATE.FORENSIC`, as before) and a ring of the newest 400 lines printed by the forensic summary (`tailText8863`); never a table row |
| 74 commits a second: the flush ran the moment a row arrived | `ErrorLogger.log` / `flush8818` | rows wait up to 250 ms for each other; an ERROR or CRASH row, or 128 waiting rows, is written at once |
| One 500-row cap for every level: an ERROR left the table 500 to 1,500 INFO rows later | `ErrorLogger.trim8818` (also the start-up `cleanup`) | INFO rows among the newest 500 rows; the newest 500 WARN / ERROR / CRASH rows kept on their own |

What is learned does not change: same rows, same acceptance rule, same order, same arithmetic. Report: `Dataset seed
(§8863)` (`lines refusedUnparsed parsed accepted`), `Forensic log (§8863)`, `Error log writes (§8818)`; job meter
`datasetSeed` in place of `pocketLedgerSeed` / `payingCellSeed` / `managedBandsSeed`.

Correction (8864): the 4.8-5.4 GB a minute of allocation and the collector thread's 22 s of CPU a minute in that report
are its first three to four minutes after start, which include the five dataset passes above. It is not a steady-state
figure; a report from fifteen minutes or more of uptime on 8863 or later is needed for that, and for per-site attribution.

## 55. No reader waits for another reader's rebuild (8864)

**Measured.** Runtime acceptance, 5.0.8863 (19 Nov 00:00:42): `ANR in com.lifecyclebot.aate (SplashActivity) ... Waited
5001ms for FocusEvent(hasFocus=false)`, process at 261 % CPU, load average 32. The dashboard was the existing one
(`Activity.performStart`: 8863's splash change held), and its main thread was `Blocked` at `ConfigStore.load` —
`waiting to lock <0x0f336245> (a com.lifecyclebot.data.ConfigStore)` — called from `MainActivity.updateUi` inside the
ON_START dispatch. The printed trace ended before the lock's holder.

| What was happening | Where | Now |
|---|---|---|
| `ConfigStore.load` and `save` were `@Synchronized`, cache check included: every 2 s one of 166 call sites rebuilt the config (17 decrypted values, 137 preferences) inside the monitor and every other caller queued behind it | `SnapshotCache8864` (`ConfigStore.snapshot8864`) | a fresh snapshot is one volatile read; an aged one is rebuilt by the first caller that finds the store free, and a caller that finds it busy is given the held snapshot at once; with nothing held (first read) callers wait for the one build |
| A save held the same monitor for its whole write | `ConfigStore.save` -> `SnapshotCache8864.write` | readers during the write are served the config from before it; then the snapshot is dropped, `RuntimeModeAuthority.publishConfig` runs, and the store is read again before `save` returns: no read that starts after the authority was told sees the old config |
| The cache's age was wall-clock time | `SnapshotCache8864.clock` | the monotonic clock |
| The dashboard's first render ran inside the activity's ON_START dispatch (a StateFlow hands a new collector its value at once), before the looper could return to the input channel | `MainActivity` render collector | `kotlinx.coroutines.yield()` first: start and resume complete, input is read, then the render |
| A freeze on a lock printed 80 lines of monitors, not the holder | `ci/runtime_device_checks_8418.sh` `anr_evidence` | the whole stack of the thread holding the lock the main thread waits for |

Report: `Config store (§8864)` — `builds lastBuildMs maxBuildMs by=<thread/prio> servedWhileBusy waitedWithNothingHeld
writes`. `maxBuildMs` and `by` name the slowest rebuild and the thread that ran it.

## 56. A forecast loss takes the lane's one live position (8865)

**Measured.** Owner's phone, 5.0.8862, LIVE, 19 Nov 10:49 (uptime 7,642 s; 6.8 h after a learning reset), and the dataset
export of the same hour (10,607 labelled rows since the reset, 1,488 coins).

- Live equity 0.2825 -> 0.2104 SOL (-25.5 %); 9 closes, 1 win; 4 open.
- The session's four buys, all `NATIVE_QUALIFIED_ADMIT_8259`:

| Bought | Coin | Lane, stage | Forecast for that lane on the last labelled decision before it | Closed | Realised |
|---|---|---|---|---|---|
| 08:46:49 | Ffsmhb | MOONSHOT, BASE_START | -6.2 % | 09:08:51 `RAPID_HARD_FLOOR_STOP` | -28.9 % |
| 08:52:30 | B2qjRY | MOONSHOT, BASE_START | -6.6 % | 09:12:14 `STRICT_SL_-15` | -19.2 % |
| 08:52:47 | 4rYsDH | CORE, CONTROLLED_MARKUP | -6.5 % | 09:21:06 `STRICT_SL_-15` | -24.3 % |
| 08:53:54 | FML39m | CORE, CONTROLLED_MARKUP | -3.9 % | 08:54:27 `RAPID_HARD_FLOOR_STOP` | -18.1 % |

- `edgeRefusal8827[refused=10825 letThrough{UNEARNED_LIVE:MOONSHOT=959 ... EXPRESS=306 ... SHITCOIN=162 ... CORE=113 ...}]`:
  the model's refusal binds where the coins it forecast to lose are proven losing (two standard errors; per lane-stage
  from 30 of them). Short of that proof live bought them at open flow. The second MOONSHOT and the second CORE buy were
  made while the first was open.
- Dataset, admitted decisions, 15-minute label, one row per coin per 15 minutes (its best-forecast lane):
  forecast loss n382 -5.1 % +/- 2.6, 2.4 % doubled; zero or better n60 +8.7 % +/- 9.8, 10 % doubled. Each coin one vote:
  -7.6 % +/- 2.4 (305 coins) against +9.3 % +/- 10.1 (58 coins). The zero-or-better figure is not proven positive (its
  median is -5.9 %; six coins carry the mean).
- The forecast itself, by the dataset's own `pred` column: from its first value after the reset (04:47) to 08:10 it was
  one slowly moving figure for every coin (-0.9 falling to -6.2 %; never more than five distinct values in five minutes;
  07:35: 113 rows of 34 coins, one value). After the 08:42 restart it was -6 +/- 1 in every lane (none at zero or better)
  to 09:05; from 09:10 it spread (10-25 points) and 11-44 % of forecasts were zero or better. All four buys (08:46-08:54)
  were made while it did not tell coins apart. The spread of 09:10 arrived with the tree model's first fit of the session
  (job meter `treeTrain:runs1`): it needs 2,000 usable labelled rows since the reset (1,285 at 08:45, 2,152 at 09:05) and
  was kept in memory only, so every restart began without it. Not explained from this export: the previous session's
  forecasts also spread between 08:15 and 08:35 (10-17 points in every lane), with 943-1,116 usable rows.
- With the trees fitted (09:10 on; 828 coin-windows): admitted, forecast loss n153 -8.6 % +/- 3.5; zero or better n59
  +9.0 % +/- 9.9; a forecast of +15 or more, n15, +35.2 % +/- 26.0 with 3 doubling. Every decision: loss n595 -8.2 % +/-
  2.8; zero or better n233 -1.3 % +/- 3.2. Rank correlation of forecast and outcome 0.086.

| Forecast, and what the record says of it | Paper | Live before | Live now |
|---|---|---|---|
| zero or better | traded | traded | traded |
| loss; refusal earned (lane-stage or lane proven losing) | refused | refused | refused (`EDGE_MODEL_NEGATIVE_8537`) |
| loss; the coins forecast to lose there have paid (30+, mean - SE > 0: `EdgeModel8537.forecastLossPays8865`) | traded | traded | traded |
| loss; not earned; the candidate's own record supported (8843) | traded | traded | traded |
| loss; not earned; the lane holds no live position | traded | traded | bought, as the lane's one position |
| loss; not earned; the lane holds a live position | traded | bought | refused: `EDGE_FORECAST_LOSS_SLOT_TAKEN_8865` |

The lane's one position is the existing probe slot (`LaneRealisedProbe8424.slotOpen8429`, `Candidate8136.recordProbe8429`)
that the setup, sheet, cell, stage, tactic and timing records already use. The refusal is a verified-record door (lane
authority does not reverse it), is filed on its own forward record (`GateOutcome8418`, key
`ADMISSION8136_EDGE_FORECAST_LOSS_SLOT_TAKEN_8865`) and stands aside by `selectionGateRetired8696` once the coins it
refused are proven to win. No lane is shut by it and nothing waits for proof (directive #37).

**The trained trees survive a restart.** `TreeRanker8737.save8865` writes the fitted model (`files/tree_model_8865.bin`:
the bin edges and 150 trees, stamped with the learning line, the model epoch and the feature count) after each fit;
`restore8865` reads it at the first use after a start. It is the model the bot was deciding with before the restart and is
retrained on the same two-hour clock. A file from another learning line (a reset), model epoch or feature list, or one
holding a malformed tree, is refused (`TREE_RANKER_SAVED_MODEL_REFUSED_8865`): after a reset the trees are earned again
from the first row. Report: `tree8737[restored rows=... trees=... fittedMinAgo=...]`.

**The mirror.** `paperMirror8757[... mirrored=12 ... tripped=false]` while the book it mirrors read
`P8623|MOONSHOT[n43 wr11% -22.9%±3.9 pf0.06]`. The mirror's stop reads live closes only. It now does not follow a book
`MemePaperTrader8544.laneProvenLosing8608` proves losing (30+ priced closes, mean + SE < 0, PF < 1), and follows it again
when that stops holding. Report: `notFollowedBookProvenLosing8865`.

**The tuning report.** `CORE|LIVE_REFUSE:LIVE_STAGE_LOSES_8626 n=13 mean=+18538294.4% ... pnl=+240997.827SOL` is the
refuted-entry close 8855 removed from the paper book; `tuning_8744.json` had kept it under three cuts. The same rule
(`rowImpossible8855`) now removes such a row when the file is restored; the section prints `REPAIRED` when it has.

**Memory, measured but not yet explained.** `heap=432/512MB ... censusMb=105 unattributedMb=327`; 3,632 collections
taking 6,501 s of 7,642 s, 2,175 of them blocking (4,948 s); 2-7.7 GB allocated a minute; cycle 4,958 ms. That is
5.0.8862: 8863 and 8864 (one dashboard, one dataset read, forensic lines out of the error table) are not in it. Two
lines are added to the heap log so the next report says which it is:

| Line | What it reads | What it settles |
|---|---|---|
| `SCREENS` (`ActivityCensus8865`) | per Activity class: alive now / most alive at once / created | whether a screen is alive more than once (the phone's stalls were at `MainActivity.onCreate` x4 and `VMDebug.countInstancesOfClass` x3) |
| `FLOOR` (`HeapLog8618.floor8865`) | the lowest heap used among the samples kept, and the census at that sample | how much of "unattributed" is live and how much is garbage waiting for a collection |

**Curve continuity (8859), result.** `curveContinuity8859[mints=1450 printPairs=48545 continuous=39862(82%) ...
wouldPrice{unfilledEntries=0/1423 quietReads=17/8813 askedOffCurve=9352}]`: pricing an unfilled label from the pump.fun
curve's own reserves would have priced none of 1,423 unfilled entries and 17 of 8,813 quiet reads; 9,352 of the questions
were about coins that stream never printed. It stays a measurement and is not switched on.

**Runner model (8861).** 252 rows, 33 doubled before the stop (13.1 %); not fitted. The line read `waiting rows=252/160`;
the 160 is the bar for the training rows, the oldest six tenths, which were 151. It now prints that
(`RunnerModel8861.waitingFor8865`). `hiveRunnerRows8862[otherInstalls7d=0 ...]`: this is the only install.

## 57. A held crypto position is its trader's to mark and sell; what holds the heap (8866)

**Measured.** Owner's phone, 5.0.8864, LIVE, 19 Nov 15:21 (uptime 13,153 s), SOL $74.52.

Three live holdings, all bought by the crypto trader (`CRYPTO_ALT`) in the previous session, the proof table's own lines:

| Coin | Tokens held | Cost | Canonical held mark (`LOCKED_VENUE_POOL_7392`, 5 s old) | Worth at that mark | Against cost |
|---|---|---|---|---|---|
| ADCY | 45,711 | 0.0425 SOL | $0.00001735 | 0.0106 SOL | -75 % |
| CRIME | 29,648 | 0.0441 SOL | $0.00006186 | 0.0246 SOL | -44 % |
| Tomás | 144,635 | 0.0445 SOL | $0.00001843 | 0.0358 SOL | -20 % |

The report's equity (0.2621 SOL) counts them at cost; at their marks it is about 0.202. The trader's own floor is -10 %
(`TICK_HARD_FLOOR`); none of the three was sold, by anyone, in 3 h 39 min.

- The owner did not evaluate them. `CRYPTO_HELD_STALE_MARK_REFRESH_ONLY_7245` 7,766 (one per held position per 15 s:
  about nine of the trader's eleven open positions, live and paper, for the whole session),
  `CRYPTO_DYN_MARK_IDENTITY_REJECTED_6654` 50,520, `CRYPTO_HELD_MARK_CHAIN_8800` 0,
  `CRYPTO_CANONICAL_POSITION_PROJECTED_7255` 11 (5 in the 10:49 session, in which the buys were made).
- The meme side tried instead and could not. `PROTECTIVE_EXIT_REDISPATCH_7176` 3,409;
  `TERMINAL_SELL_UNKNOWN_POSITION_6454` 3,069 = `LIVE_SELL_TERMINAL_RESERVE_REJECTED_6455` 3,069; sell spine emergency
  outcomes `ALREADY_CLOSED=3060, CLOSED=2`; `protect=LATCHED_CATASTROPHE` (ADCY, CRIME), `LATCHED_STOP_LOSS` (Tomás).

**Why, from the code.**

1. *One asset, two spellings.* The trader keys a position by the registry identity, `solana|<mint>`
   (`cryptoAssetKey`: `dynAssetKey` first). A live buy is written to the canonical ledger under the mint the wallet
   holds, `<mint>` (`targetMint6493 = signal.dynMint`; the proof table prints `p.mint.take(8)` = `PKShUAph`,
   `96WnzbBG`, `5ChyeKeH`). A paper buy is written under the trader's key, so paper never met this. Every comparison
   between the two was a string equality:
   - `syncCanonicalCryptoPositions7255` found the trader's own position "not this row's" and wrote a ledger recovery
     projection over it (its stop, target, strategy and peak gone; keyed by the bare mint);
   - `monitorPositions` then asked the registry for that position's mark and refused the answer on every tick,
     because the mark's identity `solana|<mint>` was not "exactly" the position's `<mint>`;
   - the on-chain fallback (8800) is written through the registry, which refuses a read older than the price time it
     already holds (the row's last write, when its price has no clock of its own); it recorded none.
   With no trusted mark the trader evaluates no stop and `closePosition` defers.
2. *A held coin leaves the registry file.* The file keeps statics and the 3,000 most recently updated rows. A held
   coin whose feeds went quiet is the oldest row of thousands; after the next restart it is not a row, the position's
   identity resolves to nothing, and nothing recreated it (`refreshPriceForMintBlocking` returns 0 for an unknown
   row). The 19 Nov 01:22 report names six paper positions held this way (`cause=IDENTITY_UNRESOLVED`; five
   `asset=solana|...`, one `asset=base|0x...`).
3. *The risk clock sold what it does not own.* 8082 gave `CRYPTO_ALT` rows one exit owner and took them out of the
   exit sweeps and out of the Executor's own position lookup, but not out of the independent risk clock. The clock
   latched their stops on the meme thresholds and dispatched them to the Executor, whose terminal reserve answers
   `REJECTED_UNKNOWN` for a position it was told not to see; the scheduler asked again, for hours.

Which of 1 and 2 held each of the three on a given tick the counters do not separate. Both end at the same line and
both are closed.

**What 8866 does.**

| Before | Now |
|---|---|
| `solana|<mint>` and `<mint>` compared as strings | `DynamicAltTokenRegistry.sameAsset8866`: one Solana mint in either spelling is one asset (only Solana; nothing else is merged). Used by the rehydrate, the saved-position restore, the projection, the monitor's mark identity and the stop-time mark refresh. A live position keeps its own stop, target and strategy, in the session and across a restart |
| a held coin missing from the registry: `IDENTITY_UNRESOLVED` for good | `ensureHeld8866`: found by the position's own key (the key, then the exact `solana|<mint>` identity, then the address index); when there is no row, one is created from the key's own chain and address, price 0. `persistRows8866` writes every held row whatever its age; eviction and the file share one held set |
| mark: registry feeds, then the vault read | first the canonical held mark (`CanonicalPriceMarkRegistry6522.heldRiskMark`: this mint, this base, USD per token, at most 60 s old), taken at its own time; then the registry's own mark; then the vault read or witnessed print (8800). A price-less row no longer refuses a mark dated before its write |
| the risk clock latched and dispatched `CRYPTO_ALT` rows to the Executor | `isMemeExecutorExit8866`: the clock sweeps what the Executor can sell. The trader's 1 Hz monitor is the exit clock of its own rows. The proof table's `protect=` prints, for those rows, what that monitor last did: `CRYPTO_ALT[eval=2s EVALUATED mark=CANONICAL_HELD/5s pnl=-74.9% plan=LEDGER_RECOVERY]`, or the reason it held |
| a live sale that failed was asked again on the next 1 s tick, unnamed | named (`CLOSE_NOT_CONFIRMED:<the executor's own reason>`, label `CRYPTO_LIVE_CLOSE_NOT_CONFIRMED_8866_*`) and asked again after the monitor's refresh interval (15 s); the operator's own close and Stop are never spaced |

**What happens on the first run.** The three positions are ledger recovery rows now (their own plans were
overwritten in the earlier sessions and cannot be restored). They are marked on the first tick. CRIME and Tomás are
below the -10 % floor and are sold on that tick; ADCY is below -50 % and needs a second consecutive read (the phantom
guard, 7264), then is sold. About 0.07 SOL returns for 0.131 paid. Had the floor run when each first crossed it, the
loss would have been near 0.013-0.02 SOL; the difference, about 0.04 SOL, is what the fault cost.

**Not changed, and known.** The registry dates a price that came with no source clock by the row's last write, and a
later merge that carries no price advances that write (8801's fallback). A position marked from the registry alone can
therefore read an old price as fresh. Solana holdings no longer depend on it while the canonical held mark is alive;
other chains still do.

### The tree refit left the background CPU group

`treeParse` 12.5 s of CPU in 2,522 s of wall, `treeTrain` 11.7 s in 1,533 s: the refit ran at Android's background
priority (8817), which is nice 10 and the background CPU group, beside a process whose collector was running 86 % of
the time. `JobCost8813.measureYielding8866` runs the two at nice 5: below every default thread, in the process's own
group. The batch replays nothing waits for (dataset seed, edge replay, strategy lab, the CoinGecko list) stay on
`measureBackground8817`. How much of the 25-42 minutes was the CPU group and how much the collector is not separable
from this report; the next one prints the same two rows.

### The memory map

33 reports (5.0.8680-5.0.8864): the lowest heap in the timeline rises with uptime (120-230 MB in the first quarter
hour, about 360 MB after two hours) while the census stays near 100 MB; at 3.6 h the heap was 462 of 512 MB,
collections had run for 11,263 s of the 13,153 (blocking 9,761 s), the average cycle was 5.8 s and the exit scheduler
missed its cadence 637 times. What accumulates is outside every store the census names.

`HeapMap8866` (owner-requested only: Pipeline report > Save > Take memory map) writes a heap dump into the app's
private cache, reads it with `HeapMapCore8866`, deletes it, and saves text to Downloads/AATE; the next report prints
its first 90 lines under `HEAP MAP (§8866)`.

- What it reads: per class, instances and shallow bytes (the app heap; the zygote and image heaps are listed and left
  out). For a sample of objects taken by size (one per `file size / 20,000` bytes; anything bigger always), the chain
  of holders, one hop per pass over the file, up to a static field or a GC root: `static X.y > X.rows` holds so many
  MB of `byte[] < String.value < Row.json < Object[] < ArrayList.elementData`. A chain of same-class links
  (`Node.next`, `Message.next`) is written once with its length; a list longer than the passes (18) is reported with
  its node class and what the nodes carry, not its owner.
- What leaves the phone: class names, field names, counts and sizes. The reader decodes no value: field data is only
  probed at reference offsets, array and string contents are skipped by length. The raw dump holds the wallet's keys;
  it is under `cache/heapmap_8866/`, deleted in a `finally` and again at every app start.
- What it costs: every thread of the app stops while the runtime writes the heap (seconds to tens of seconds at
  500 MB), then one thread reads the file for a minute or two. It needs free disk of 1.5 x the heap limit + 128 MB and
  refuses by name without it.
- Checked: against a real JVM heap dump with known holders (a 38.1 MB list of byte arrays read as 38.2 MB under
  `static Holder.rows`; a map of 50,000 `long[64]` as 29.5 MB under its static field; a 7.6 MB thread local as
  `ROOT JAVA_FRAME`; two 30,000-200,000 node linked lists reported unresolved, by node class); a synthetic ART-format dump in the unit test
  (values planted in arrays and fields do not appear in the text; an unknown record or a cut file stops it by name);
  and on the CI emulator's real ART dump (device check `heap_map`: read to the end, layouts understood, holders traced,
  raw dump gone).

## 58. What the phone's heap holds and what it throws away (8867)

**Measured.** Owner's phone, 5.0.8866, LIVE, 19 Nov 18:10 (uptime 680 s), with the first phone memory map (taken 18:10:30,
728 s up, 259 MB used, dump 7.6 s, read 23.4 s, status COMPLETE, 5,996,869 objects, 32,604 classes, layoutMismatch 0).

- The three crypto holdings of §57 are gone: liveOpen 0/40, wallet 0.1311 -> 0.2184 SOL (+0.0873). The one sale in this
  session's log (2xCd5q, 17:59:20) returned 0.0187; the remaining 0.0686 matches the three holdings at their 15:21 marks
  (0.071). The sale lines themselves are before this report's start.
- Allocation: `GC count=648 allocatedMB=49058` in 680 s (4-8 GB a minute in the timeline), blocking 185 s. The heap at
  12 minutes: 271 MB in the app heap.
- Owners the map traced (MB at 12 minutes): a ConcurrentHashMap of String keys and values 31.6 (owner hidden, see below);
  `CortexTiming7900.ledger` 10.9; `Cortex7885.ledger` 10.1; `ForwardReturnLabeler7731.pending` 8.7;
  `EntryStrategySnapshot6450.snapshots` 8.1; `Cortex7885.gradeBook8144` 7.9; `LearnerAttribution8418.ledgers` 6.6;
  `EdgeModel8537.byLane` 5.5; `Cortex7885.graded8006` 5.1; `EconomicEventSchema6464.snapshotCache7499` 4.9;
  `ChartLibrary7950.feats` 4.7; unreachable 13.8.
- `entrySnap8332[readOk=2779, missing=15470]`: 15,470 lookups in 11 minutes of positions with no entry snapshot, each
  reading both durable stores again.
- `CRYPTO_ALT_TICK_FLOOR_PHANTOM_DEFERRED_7264=66` in 11 minutes.
- Runner study export (`RUNNER_STUDY_8852`, 1,289 episodes): every crypto nomination, 539 of 539, ended
  `ENTRY_UNAVAILABLE`; 502 of them were keyed `solana|<mint>`. Of the native-ready meme nominations: 115 complete, 337
  entry unavailable, 298 exit unavailable. The 114 complete with a value: $10 held six hours ends at $8.66 (median $9.21);
  its peak averages $13.09 (median $10.82); 10 of 114 reached 2x.

**What 8867 does.**

| Before | Now |
|---|---|
| a map's own view (`ConcurrentHashMap$EntrySetView.map`, `HashMap$Values.this$0`) was taken as the map's holder, so 31.6 MB read "held only by what it holds" | a holder nested in the object's own class is a link (`outerOf8867`): the map is filed under its real owner |
| the dump was taken after a collection; garbage read "no holder found" | no collection first; garbage is printed as `GARBAGE built and dropped, outermost: <class>` with its chains, so the map shows what is allocated and thrown away, by structure. Retained owners are unchanged (garbage has no static or root holder). The CI check measures the traced share over what is still held |
| crypto runner-study coins keyed `solana|<mint>`, unpriced by the Solana labeler | keyed by the mint (`solanaMintOf8866`); a non-Solana asset keeps its key |
| a position with no entry snapshot re-read both durable stores on every lookup | an answered "none" stands for 5 minutes or until `setEntry` / `migrate` for that position (`missStands8867`); only an open store's answer is remembered; `missServed8867` counts the reads saved |
| the crypto floor's two-read confirmation was removed before the sale, so each sale that did not complete waited for a fresh confirmation | the strike stands until the position is gone |

**Not changed, and open.**

- The 4-8 GB a minute is not attributed by this report. The next memory map names it by structure.
- Cortex lane forecasts: most matured decisions have no outcome (MOONSHOT matured 763, missing 607; EXPRESS-family and
  CORE similar), so every lane's positive authority is 0 by coverage. Not explained from this report.
- Launch hour export: 2 % of rows carry an hour result (MOONSHOT admitted 592 of 25,332), so its means are a survivor
  sample.

## 59. A forecast judged at its own strength (8868)

**Measured.** Owner's edge-dataset export of 19 Nov (`edge_dataset_8537`, model epoch 8801, deciding model `MIND8842`,
filled rows, 15-minute labels). Outcome in the model's managed unit (stop at the row's own cost, clipped at +200 % as the
refusal record is), grouped by the forecast frozen when the coin was decided:

| lane | all | picks >= +1 % (the old authority) | >= +5 % | >= +10 % | >= +15 % |
|---|---|---|---|---|---|
| MOONSHOT | -6.9 % | n345 -1.7 +/- 2.3 | n164 +5.1 +/- 3.7 | n84 +11.8 +/- 5.8 | n44 +13.5 +/- 6.6 |
| EXPRESS | -8.9 % | n260 +1.6 +/- 2.8 | n121 +8.6 +/- 4.8 | n63 +20.9 +/- 7.7 | n36 +20.8 +/- 9.5 |
| SHITCOIN | -8.1 % | n360 +1.7 +/- 3.0 | n175 +6.2 +/- 4.0 | n78 +13.6 +/- 7.0 | n51 +18.5 +/- 8.0 |
| CORE | -7.0 % | n367 +0.2 +/- 2.2 | n172 +4.4 +/- 3.8 | n69 +21.3 +/- 6.7 | n42 +31.8 +/- 7.9 |
| QUALITY | -7.8 % | n261 -2.9 +/- 2.4 | n110 +2.6 +/- 4.7 | n47 +10.7 +/- 8.4 | n28 |
| BLUECHIP | -7.4 % | n225 -1.5 +/- 2.4 | n101 +4.1 +/- 4.4 | n37 +6.3 +/- 7.6 | n22 |
| PROJECT_SNIPER | -12.9 % | n101 -11.1 | n56 -11.4 | n36 -12.3 | n23 -19.7 |

- A lane's forecast had authority only when all its picks from +1 % were proven (n >= 30, mean - SE > 0, above the lane's
  average). Marginal +1 to +5 % calls diluted that pool, so none of the five main lanes could act on a +25 % forecast.
- Between 14:51 and 15:22 the model forecast twelve coins at +17 to +44 %; every one paid (+6.8 to +120 %); live bought
  none.
- The model's call was read at admission step 6, after the flow / stage entry wait, the falling-hour defer and the
  unknown-move data wait, each of which parks a coin before its proven call is seen.

**What 8868 does.**

| Before | Now |
|---|---|
| one out-of-sample record per lane and model: the picks from +1 % | also one record per conviction tier (+5 / +10 / +15 / +20 %), same unit, same +200 % clip, same bar; a forecast in a tier that has earned it is a POSITIVE call, read at the highest such tier it reached (`convictionTier8868`) |
| — | the tiers persist as block 8868 of `edge_model_8722.bin`; a file written before them seeds them once from the dataset's own rows (`tierSeedRow8868`: accepted for learning, this model epoch, frozen forecast), status `convSeed8868=` |
| a proven call was parked by `FLOW_UNOBSERVED` / `STAGE_UNRESOLVED` (a read the bot could not take) | it answers that read as the lane specialist does (`edgeModelProven8868`, `overriddenBy=EDGE_MODEL_8868`); an observed wait (dumping, rug-prone, no trigger) still meets its door |
| with both moves unknown a proven call waited for economics | the call's proven lower bound is the move (`grossFromEdgeModelLower8868`, as 8171 does for a setup record); a known current move still wins (8846), and economics judges the bound as any move |
| — | an unproven falling hour gives way to that move; a proven-losing falling hour still refuses |

Unchanged: safety, structure, regime, stale price, every critical fact, execution economics, every learned-loss refusal,
the realised-loss governor, the model's own negative call and the 8865 forecast-loss ladder. Status line per lane:
`conv8868=5:n…/10:n…/15:n…/20:n…`, a tier with authority marked `*`.

**Not changed, and open.**

- Coins lost at QUALIFIED before admission (`IDLE_TTL`; 8864 report MOONSHOT 285, SHITCOIN 112 with no terminal) never
  reach this decision.
- BLUECHIP and the crypto lanes have no tier that pays yet; PROJECT_SNIPER's forecasts lose at every strength.

## 60. The forecast answers a lane's preference, and the timing wait binds (8869)

**Measured.** Owner's edge dataset of 19 Nov (model epoch 8801, about 46,000 labelled coins, 15-minute net after cost,
managed unit) and the 5.0.8864 report of 15:21 (6.9 h LIVE). Rank correlation of each signal with the outcome, every meme
lane on 1,000-5,000 coins:

| signal | MOONSHOT | SHITCOIN | EXPRESS | CORE | QUALITY | CASHGEN | CYCLIC |
|---|---|---|---|---|---|---|---|
| learned forecast | +0.21 | +0.23 | +0.22 | +0.19 | +0.20 | +0.24 | +0.26 |
| buys in the last 15 s | -0.18 | -0.19 | -0.16 | -0.17 | -0.17 | -0.25 | -0.29 |
| run-up from launch | -0.16 | -0.16 | -0.14 | -0.12 | -0.16 | -0.22 | -0.24 |
| seconds since the last print | +0.23 | +0.27 | +0.25 | +0.22 | +0.23 | +0.28 | +0.30 |

- The busiest fifth by buys in the last 15 s loses -13 to -20 % (MOONSHOT -13.8, SHITCOIN -17.6, EXPRESS -16.9) against
  -4 to -8 % for the other four fifths; the biggest run-up fifth -14 to -22 %. Every fifth of every signal is negative after
  cost; only the forecast's +5 / +10 % tiers are positive (§59).
- The lanes' adaptable refusals ask for that tape: EXPRESS NO_CONTINUATION 4,681 and WEAK_BUYS 1,356, SHITCOIN
  THRESHOLD_FAIL_SCORE 2,217 (veto audit: its refused coins +36.3 %, n31), TREASURY WEAK_MOMENTUM 741, CASHGEN REJECTED_BUY 728.
- Cortex's ranking is inverted in 7 of 10 lanes (MOONSHOT top fifth -19.1 % / bottom +13.0 %, n156; STRONG n7 -36.6 %); it
  holds no authority either way and STRONG is already neutral on a lane measured inverted, and its REFUSE bucket loses too
  (MOONSHOT -3.3 %, EXPRESS -5.8 %, CORE -20.5 %), so turning it round would buy losers. Not changed.
- The shadow's entry styles, same coins, same exits: MOONSHOT NOW/RUNNER n300 -10.0 % vs PULLBACK/RUNNER n135 -1.2 % (fill
  26 %); SHITCOIN NOW/RUNNER n68 -17.8 % vs PULLBACK -3.1 %; CORE NOW/SCALP n196 -6.8 % vs PULLBACK/SCALP n91 +1.7 % (fill
  44 %). No arm was positive on its own (mean - 2 SE > +2 %), so live bought now: runner admits 82 bought the top, 20 gave it
  back, 18 won. The timing wait was an unlisted learned door, so 8577 made it advisory.
- 8864 chain: 8,885 candidates closed with no terminal; every printed example in QUALITY / BLUECHIP / TREASURY / CYCLIC /
  CASHGEN is a crypto-desk asset key ("solana|<mint>"); the meme lanes' were offered and their token's cycle returned
  before its lanes (V3_FATAL_EARLY_RETURN 864, SYMBOL_FAMILY_DEDUP 155). The runner trace reads not_evaluated=0 of 200.

**What 8869 does.**

| Before | Now |
|---|---|
| a lane's adaptable no (momentum, continuation, buy pressure, score) stood unless 8048 retired the rule for the whole lane | for a coin the forecast has a proven call on in that lane (EdgeModel8537.callOf POSITIVE), it stands down: `NATIVE_EDGE_MODEL_RETIRED_8869:<forecast>/<tier>:<rule>`; the buy doors (laneDoor8105, the cashflow door) and the FDG score floor honour it. Never safety, rug, dev, liquidity, cap, age, data, lifecycle or lane scope; an operator REFUSE still stands |
| — | the desk reads the forecast once per coin for the lanes that said such a no, without side effects (`quietVerdicts8869`: no analyst request, no retrain check, no mind note), each lane costed at the current ticket (`NetEdge8030.previewCostPct8869`) |
| the timing wait came only from an arm proven positive on its own | also when a waiting entry is proven better than buying now on the lane's exit style: fill x (mean - 2 SE) above buying now's mean + 2 SE, 40+ closes each (`waitBeatsNow8869`), computed with the adopted choice (`relative8869{}` on the shadow line) |
| the wait was advisory | `ENTRY_TIMING_WAIT_` / `ENTRY_TIMING_EXPIRED_` bind (LaneAuthority8577); a candidate admitted on its own proof (SPECIFIC_EDGE_PROVEN_8136, EDGE_MODEL_PROVEN_8537) is not held for the lane-wide verdict |
| crypto-desk stamps opened meme-lane chain records | counted on their own (`chain8869 cryptoDeskNotBooked{}`) |
| a candidate whose cycle returned before its lanes closed as IDLE_TTL with no name | the cycle's gate is named on it (`NOT_EVALUATED_CYCLE_<gate>`, through `processTokenCycleTraced8869`; a named return is `return cycleExit8869(gate)`, nothing added to processTokenCycle besides); a token no longer watched closes as LEFT_WATCHLIST |

The owner chose the binding wait on 19 Nov ("together now") over 8683's "the shadow may unlock live, never restrict it": the
verdict is relative (buying now against waiting on the same coins through the same exits), so the shadow's pessimistic
absolute exits cancel out of it.

**Not changed, and open.**

- Cortex's voters weight the hot-tape reads; its authority stays earned on its own record.
- The forecast has no skill on the crypto lanes (rank correlation +0.01 to +0.04).
- PROJECT_SNIPER's forecasts lose at every strength; BLUECHIP and DIP_HUNTER have no paying tier.

## 61. Paper is off while LIVE runs, and a failed paper close is retried (8872)

**Owner, 20 Nov.** "paper is meant to turn off when live is running close out all trades and the shadow trader runs while
live does! paper is dragging down live trading running at the same time" / "not just steering. having it running in the
background plus the shadow trader plus the live trader is crazy" / "the llms are fine".

**Measured (5.0.8869, one process: 03:29 PAPER report, 04:04 LIVE report).** At 04:04 LIVE: 93 canonical paper positions open
(84 OPEN, 9 PARTIALLY_CLOSED), the meme paper book holding 5 at -3.766 SOL realised, the evidence shadow 0 of 120 opened.
Mark pool rejections 9.0 a second (0.5 on 8864, 0.6 on 8866). Paper closes: 4,494 requested, 1,756 commits, 1,668 refused,
about 100 closed, 1,084 emergency stale-state bypasses; every sampled refusal read `applied=false claimed=false`.

**Changed.**
- `LiveNoPaper8184.enforced8184` is true by default again (8336 had turned it off). The LLM books (LLM Lab open and manage,
  crypto LLM desk) keep trading beside LIVE (`LLM_SOURCES_8872`). PAPER mode is untouched.
- `closeOutInLive8872()`: LIVE with the freeze on. `BotService.ensureLivePaperCloseOut8872` (after the per-loop mode publish,
  at most once a minute) closes every canonical paper lot through the durable operator-stop close (`closePaperStopIntent8521`:
  Solana lots by `Executor.closePaperPositions8521`, markets and crypto desks by `PaperStopLifecycle8521.closeMarkets`) at
  observed, authoritative marks only, retrying unpriced lots after 30 s doubling to 10 min until closed, PAPER or stop.
- `MemePaperTrader8544` opens nothing in LIVE (lane and family entries), and `tick8544` stands every held position down: the
  stake returns, nothing is booked, and the book stops asking for marks. A mode-switch exit is not a strategy exit.
- The wedge: `CanonicalPaperTerminalBridge6469` took a fixed FULL_CLOSE claim and never gave it back. It now releases both
  claims at the three exits where nothing has moved (event not opened, ledger basis refused, position mutation refused; the
  existing `finally` already voids the unapplied fee assessment), and gives back an idempotency key whose terminal claim was
  refused. `CanonicalEconomicEvent6635.openEvent` reopens an event abandoned (8585) with nothing committed, so the retry of
  the same close can open its event id. Exits after the position mutation commits keep their claims.
- `Executor` names the bridge reason in `CANONICAL_PAPER_SELL_COMMIT_6474` (`why=`) and in
  `PAPER_ACCOUNTING_MUTATION_REJECTED`, with a counter per reason (`PAPER_CLOSE_REFUSED_8872_<reason>`).

**Not changed.** The explore and evidence shadows, forward labels and the colour-shape learner's own measuring ladder keep
running in LIVE. The paper mirror (8757) and the crypto spot paper backing (8788) only admit; with paper off their records
freeze, and 8873 points them at the live twin's record. Live positions are never touched by the close-out.

**Proof to read on the next LIVE report.** `Live no-paper (§8184)`: `closeOut8872[passes>=1 pending=0]`; canonical
`paperCanonicalOpen=0`; `memePaper8544 (OFF in LIVE (8872): opens nothing)` with `open{-}`; mark pool rejections per second
of uptime near the 8864 level; `PAPER_CLOSE_REFUSED_8872_*` names whatever still refuses a paper close.

## 62. The runner ladder rides past 10x, and the whole network is measured (8873)

**Owner, 20 Nov.** "there are 60,000% profit opportunities out there. literally everyday" / "fuck 4x. I want it to look far
far beyond that" / "the meme trader isnt a pumpfun trader exclusively is a solana network trader. the full network".

**Measured (the owner's 13 launch-hour exports, 12-20 Nov).** 5,886 one-hour paths after the 8748 spike rule and a unit-jump
filter, two-close peaks, 5 % slip a rung: 1,514 reached 2x, 399 5x, 149 10x, 8 50x inside the hour. A unit held from the 10x
rung on the 45 % doctrine trail was worth 1.87x what the rung sold it for (102 of 149 beat it), 1.77x from 20x (31 of 47);
most of those paths were still running when the hour ended. Widening the trail from 45 % to 60 % above 5x moved nothing
(1.94x vs 1.95x of the rung from 5x; the wider trail sold worse inside the hour), so the trail is unchanged. The +900 % rung
was the cap: it sold two thirds of what remained, leaving 20 % of the original to see 30x-600x (the 8726 test: a 25x move
banked about 8x).

**Changed.**
- `RunnerGrab7967.TIERS_7967`: +100 % sells 25 %, +400 % 18 % (unchanged: the stake is back by 5x, 1.116x after slip), then a
  quarter of what remains at 10x, 30x and 100x and 22 % at 300x. 46 % of the original rides past 10x (was 20 %); 20.24 % rides
  the trail after 300x, above the 8136 20 % residual reserve, so no rung is ever clamped. On the paths: +13.3 % -> +17.1 % a
  position over all 5,886; better on 95 of the 129 paths that peaked at 10x+ (+1.72x a position); worst 10x+ path 2.84x (was
  5.15x). Straight runs that collapse: 10x 6.4 -> 5.2x (the price), 30x 8.5 -> 11.7x, 100x 16.0 -> 28.5x, 300x 37.3 -> 63.9x,
  600x 69.3 -> 95.7x. Every consumer reads the one list (SpikeCapture7943, RunnerFunnel8331, RunnerExitContract8136 replay,
  RunnerReplay8153, MemePaperTrader8544, ForwardReturnLabeler7731 credit, RunnerPlan8153's first rung).
- `SpikeCapture7943.onConfirmedPartial8081` confirms any rung of the longer ladder (it stopped at tier 3).
- `LaunchReplay8744` DOCTRINE is the live ladder itself (`doctrineRungs8873`, shares of the original). It was a hand copy
  that sold 18 % of the original at 5x (live sells 18 % of the remainder) and two thirds at 10x. Forward records of nominated
  rules are kept: entries are unchanged and the exits differ only on paths past 5x.
- `LearnerColumns8798.venueOf` also reads the chosen DexScreener data pool's DEX (`DexPools8285.dataPool8285`, in memory).
  The 17-20 Nov dataset read the venue of 92 % of the off-pump.fun decisions as unknown (12,025 NaN, 946 "other" from the
  chain stamp "solana"). A `...bonk` mint with nothing read is LaunchLab. The learners refit on rows that now carry the venue;
  older rows keep NaN, which the trees treat as missing.
- `NetworkTier8873` (report only, decides nothing): every labelled decision filed by tier (fresh < 1 h, young < 1 day, older
  small, established = $1M+ or a day old and $100k+), venue family and mint origin, with attention share and mean outcome.
  On the brief beside `edge8537`.

**Not changed.** The 45 % give-back and its 1.5x floor, the sub-2x line (8719 / 8784), the two-mark trail (8759), stops,
safety and every never-held exit. LifecycleHold8065's own ladder for its lanes. The 8872 note that the paper mirror (8757) and
the crypto spot paper backing (8788) would follow the live twin in 8873 moves to the twin build.

**Proof to read.** `runnerDoctrine8584[... ladder=+100%:25/+400%:18/+900%:25/+2900%:25/+9900%:25/+29900%:22-of-remainder
(8873)`; `runnerFunnel8331` rung stages for T4-T6; `network8873[tiers{...} venues{...} origin{...}]` with UNREAD falling as
DexPools fills; `launchReplay8744` DOCTRINE rows graded on the new ladder.

## 63. Where the score and the forecast agree, a lane admits on its own record (8874)

**Owner, 20 Nov.** "please finish out the full list of work from our document please and remember this has to boost
profits and return to capturing runners". The plan's first admit path: V3 x forecast agreement.

**Measured (the owner's edge datasets, 9-19 Nov, 178,113 labelled decisions after `EdgeModel8537.replayAccept8642`'s own
rules).** Coins not minted on pump.fun, at a $5k-$20k cap, with a V3 score of 23+ and a forecast of +10 %+, one label per
coin per lane per 15 minutes, gain kept to the pocket cap: 611 lane labels, +35.2 % net each. Per lane (mean, mean less two
standard errors): MANIPULATED n81 +40.5 (+23.8), CORE n71 +44.3 (+24.9), EXPRESS n70 +34.6 (+16.3), MOONSHOT n68 +36.2
(+17.3), SHITCOIN n66 +40.1 (+20.8), CYCLIC n58 +36.7 (+14.9), QUALITY n47 +26.4 (+3.6), BLUECHIP n45 +30.9 (+8.0),
TREASURY n35 +23.2 (+2.7); both halves positive in each; CASHGEN and PROJECT_SNIPER not yet proven. One coin per day:
positive on all ten days. The same rule on pump.fun mints: n47, +0.8 %. Almost all of these coins are under five minutes
old. About three in four already sat in a proven `PayingCell8723` cell when decided; the other quarter (141 lane labels,
+31.4 %) carried no admitting evidence.

**Changed.**
- `AgreementPocket8874`: PayingCell8723's machinery on that pocket. One record per lane (8724: a lane is authorised only by
  its own labels, horizon and cost), decay 0.995 a label, net as printed with no stop assumed, proven at 30+ labels with
  the mean above zero by two standard errors. Seeded by the one dataset read (`DatasetReplay8863`, a fourth sink) and filed
  from every live label after the edge model's own learning rules (`EdgeModel8537.learn`).
- `CandidateAdmission8136`: the edge verdict is read once (`edge8874`) and carried; the pocket is the LAST link of the
  market-cell chain, so every earlier source keeps its precedence. A proven lane's pocket is market-cell evidence with a
  paying cell's standing: it answers the generic unobserved-flow / unread-stage waits and the lane-wide priors
  (`LaneAuthority8577.memeEntryRefusal8626(..., pocket8874)` reads it like `PayingCell8723.pays8723`; the kinds that may are
  listed once, `LaneAuthority8577.answersPriors8874`), and the narrower refusals (the coin's own avoid shapes, the losing
  record of its setup) are read again.
- LIVE: what the admission decided of each coin that carried the pocket is counted (`liveAdmission{...}`), never acted on.
- The brief carries `agreement8874[...]`.

**Not changed.** Hard safety, structure, sellability, the LP-lock rule, balance, executable economics, the FDG and every
later door. Sizing is the lane's own. No refusal was added.

**Proof to read.** `agreement8874[ready=true lanes{MOONSHOT=n../+..%/run..%* ...} carried{...} liveAdmission{...}]`: which
lanes are proven on the device's own data, how many coins carried it, and what the admission said of them in LIVE.

## 64. The LLM analyst earns a seat on its own graded record (8875)

**Owner, 20 Nov.** "the llms are fine. but they needs to be proving a much much bigger boost all round and should be
helping a lot more than they do. especially live."

**Before.** `LlmAnalyst8746` (the council's probability that the coin is up by more than the round trip at the lane's
label horizon; at most 60 calls an hour, asked when the tree rates the coin non-negative, cached 15 minutes) was one input
of the `Mind8746` stacker, graded only by a Brier score. However right it was, it could not admit a coin, and its answer
was never written to the dataset, so no export could grade it.

**Changed.**
- `LlmBands8875`: every labelled decision that carried an answer files its outcome under its lane and the answer's quarter
  of the probability scale (LLM0 < 0.25, LLM1, LLM2, LLM3 >= 0.75), by PayingCell8723's rules: one label per coin per 15
  minutes, decay 0.995 a label, net as printed, gain kept to the pocket cap, proven at 30+ labels with mean - 2 SE > 0.
  No band is picked by hand: whichever band of a lane proves to pay is the one that admits.
- `EdgeModel8537.learn` reads the answer frozen with the decision (`Mind8746.frozenFor8767`, before the mind consumes it),
  files it, and writes it into the dataset row as `"llmP"` (null when the analyst had not answered). The bands are seeded
  from those rows at start (`DatasetReplay8863`, a fifth sink).
- `CandidateAdmission8136`: a coin whose current answer sits in its lane's proven band carries that record as market-cell
  evidence, after every earlier source; `LaneAuthority8577.answersPriors8874` lets it answer the lane-wide priors as a
  paying cell does. The brief carries `llm8875[bands{...} carried{...}]`.

**Not changed.** The analyst's prompt, call budget and providers; the mind's stacking and its own authority rule; hard
safety, structure, sellability, the LP-lock rule, economics, the narrower losing records, FDG and sizing. Nothing is refused
because of the LLM.

**Proof to read.** `llm8875[...]`: each lane's bands with n, mean and runner share; a `*` marks a band that admits, and
`carried{...}` counts the coins it carried. Every export from 8875 on carries `llmP`, so the analyst can be graded offline.
