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
  Like every market-cell coin, a LIVE pocket coin is also probed at the buy door and filed under its LP state
  (`LpStateRecord8725`: PROVEN / PENDING / LP_BLOCKED / OTHER_BLOCKED), so the record shows whether the LP-lock rule's
  PENDING proof is what keeps these coins unbought (8731: 29 of 32 paying-cell coins were PENDING there).
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

## 65. Where the evidence coins go (8876)

**Plan W10 (trace and report).** Owner: "there is no guessing". 8723-8875 give a coin admitting evidence when a record it
sits in has proven to pay (a paying cell, the launch sniper's rule, a proven pattern, the runner model, the Lab, the
agreement pocket, the LLM's band). The LIVE report could say how many coins carried each (`carried{...}`) and what the
admission said of paying-cell coins (`payCell8732`), but not where any of them stopped after the admission.

**Changed.** `DecisionEpisode8535` (the per-candidate join of native score, admission, FDG and chain stages) records the
evidence kind the admission carried (`CandidateAdmission8136` passes `marketCell8723?.kind`) and counts each kind once per
candidate at every step its coins reach: COINS, the admission's terminal (ADMIT / REFUSE / DEFER_DATA ...), FDG_ALLOW /
FDG_BLOCK, then OWNER / INTENT / TICKET / EXEC / CLOSE from `OwnershipChain8364`, with the reasons of refusals and blocks.
The brief carries `evidenceFunnel8876[KIND{COINS=.. ADMIT=.. FDG_ALLOW=.. TICKET=.. EXEC=.. why{..}} ...]`.

**Not changed.** Counted, never acted on: no decision reads it.

**Proof to read.** For each evidence kind, the step where COINS falls away and the commonest reason there: that is the
next door to examine on its own graded record.

## 66. Every decision is read at the hour (8877)

**Owner, 20 Nov.** "its just aate doesn't trade past 5 minutes for some reason" / "there are 60,000% profit opportunities
out there". Plan W8, first step.

**Before.** Every meme learner is taught at the lane's 5- or 15-minute target; the edge dataset holds that one horizon.
`ForwardReturnLabeler7731` already reads every eligible decision at 60 minutes (its "240" slot) with the peak and the
highest level held for 20 s, but those reads reached only the lane and stage tallies, so no export could show which
decisions became runners within the hour or whether an hour-graded learner would rank them differently.

**Changed.** `ResearchRows8877`: each 60-minute read also writes one row (decision time, lane, admitted, stage, setup,
forecast, V3 score, cap, pool, cost, the refusal it met, the hour's net and gross, peak, held peak and deepest dip) to
`edge_research_8877.jsonl` on its own single-thread writer, trimmed to the newest 45,000 lines past 60,000. The report
export saves it beside the edge dataset (`aate_edge_research_8877_<ms>.jsonl`). A lost mark writes nothing. The brief
carries `research8877[written=..]`.

**Not changed.** No learner reads it, and the edge dataset's 60,000-line window is untouched. The V3 score is kept on the
observation for the row; it is not checkpointed, so a decision restored after a restart writes it as null.

**Proof to read.** `research8877[written>0 failed=0]`, then the export: forecast and V3 against the hour's peak multiple.

## 67. The evidence shadow trades the live doctrine (8878)

**Owner, 20 Nov.** "the shadow trading engine is meant to basically be exactly the same as the live trader in all forms and
functions for the meme and crypto traders just building authority driving learning etc to unlock the lanes". Plan W1.

**Before.** `EvidenceShadow8344` trades, in LIVE, every candidate the live decision admitted but a hold refused, and its
record lifts LIVE admission vetoes and unlocks lanes (`EvidenceHold8336.laneWideProven8392`, `shadowProven8344`). On the six
doctrine lanes (SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER, MANIPULATED, DIP_HUNTER) LIVE exits on the runner doctrine,
but this book sold them on the scalp tiers (+40 % sells 60 %, +120 % / +400 % sell 70 % of the rest), a give-back floor at
40 % of the peak, and from +100 % the lifecycle hold's own ladder. A lane was judged on an exit it never trades: a runner
live rides to 10x was booked here at the first +40 %. The meme paper book (8584) already ran the doctrine, but it is off in
LIVE since 8872.

**Changed.** On a doctrine lane (`RunnerDoctrine8584.rides8584`) the evidence shadow exits by the doctrine's own functions,
as the meme paper book does:
- the ladder rung on the gross: `MemePaperTrader8544.rung8544` over `RunnerGrab7967.TIERS_7967`;
- the level `RunnerDoctrine8584.paperStopLevel8584` (the lane stop until the peak reaches +10 %, then the doctrine line),
  with a breach confirmed on a second observed mark (`MemePaperTrader8544.paperStopConfirmed8683`; -60 % confirms at once);
- the clock only where the doctrine lets a time exit pass (`RunnerDoctrine8584.holds8584`: peak under +50 %, or at/under
  the line).
A doctrine-lane position is no longer promoted into the lifecycle hold here. Fills keep this book's live sell latency. The
status line names the doctrine exits and counts breaches waiting for their second mark (`breachWaits8878`).

**Not changed.** Every other lane's exits; entries; costs; the 8394 close contract. A doctrine lane's record turns over to
the doctrine's exits as new closes land (300 a lane) instead of being reset, so no lane that has proven re-locks. A position
restored from before 8878 keeps its tier count from the old ladder. `ExploreShadow8347`'s RUNNER leg still uses the old
levers: its closes are also Cortex shadow outcomes, so it follows once that coupling is checked.

**Also.** `AgreementPocket8874.inPocket8874` now requires a Solana mint: the edge dataset also labels the EVM desks' coins
(`bsc|0x...`), which were being filed under their CRYPTO lanes' pocket records.

**Proof to read.** `evidenceShadow8344[... doctrine lanes (8878) ... breachWaits8878=..]`, closes labelled
`EVIDENCE_SHADOW_CLOSE_8344_DOCTRINE_TRAIL_8878`, and the doctrine lanes' shadow means as the record turns over.

## 68. The exploration book's runner leg trades the live doctrine (8879)

**Owner, 20 Nov.** "the shadow trading engine is meant to basically be exactly the same as the live trader in all forms and
functions". Plan W1, second step (8878 was the evidence shadow).

**Before.** `ExploreShadow8347`'s RUNNER leg exited on the scalp tiers (+40 % sells 60 %), the give-back floor, the live
fluid-stop level and, from +100 %, the lifecycle hold, on every lane. On the six doctrine lanes LIVE rides the runner
doctrine, so this book (whose closes are also the Cortex's shadow outcomes and its variant / cohort books' records) was
grading a doctrine lane's runners on an exit LIVE never takes. And both shadow books dropped, at restart, any position
older than its lane clock unless it was in the lifecycle hold, so a doctrine-held runner (whose clock the doctrine holds)
would have been lost on the first app restart after its clock ran.

**Changed.**
- On a doctrine lane (`RunnerDoctrine8584.rides8584`) the RUNNER leg exits by `doctrineLegWhy8879`, the same functions as
  `EvidenceShadow8344.doctrineWhy8878`: the `TIERS_7967` rung on the gross (`MemePaperTrader8544.rung8544`), the doctrine
  line `RunnerDoctrine8584.paperStopLevel8584` confirmed on a second observed mark (`paperStopConfirmed8683`; -60 % at once),
  and the clock only where `RunnerDoctrine8584.holds8584` lets a time exit pass. Slices and full exits keep this book's
  8394 fill contract (a new observation after the live sell latency).
- `EvidenceShadow8344.doctrineHoldsClock8879(lane, peakPct)`: a doctrine lane at a +50 % peak or more (where `holds8584`
  stops passing the clock). Such a position keeps the lifecycle hold's restore window in both books
  (`EvidenceShadow8344.restoreOpen8417`, `ExploreShadow8347.restoreOpen8355`), and the leg's Cortex maturity horizon is the
  hold's window (`step8347`); under the 8521 frozen targets that horizon does not move a forecast's due time.

**Not changed.** The SCALP leg; every other lane's RUNNER leg; entries, costs, the cohort and variant books. A leg restored
from before 8879 keeps its tier count from the old ladder (as 8878's positions do).

**Test pin superseded.** `Aate8394FillContractTest.actualEvidenceShadowUsesFreshFillObservationAndRetainsPendingSale` drove
a MOONSHOT evidence position to a +45 % scalp slice; MOONSHOT rides the doctrine since 8878, which leaves that position
whole at +45 % and -5 %, so the same fill contract is now pinned on QUALITY, whose exits are unchanged.

## 69. 8879's restore window compiles (8880)

8879 failed to compile: the exploration book's restore loop read `r.lane` inside the legs lambda, and `r` is a `var`
there, so Kotlin cannot smart-cast it. The lane is now read once before the lambda (`lane8879`). Behaviour is 8879's
(§68) unchanged.

## 70. Meteora DBC and Raydium LaunchLab curves are proven in custody (8881)

**Owner.** "the meme trader isnt a pumpfun trader exclusively is a solana network trader. the full network" and "dont buy
unlocked drain coins". Plan W9, first step.

**Before.** The LP rule (`LiquidityOwnership8522`) exempts a coin still on its bonding curve, because a curve has no LP to
pull: its quote sits in a vault only the curve program can move. The exemption needs a fresh read of the curve account,
and only pump.fun curves were read (`CurveReserves7951`). A coin on a Meteora Dynamic Bonding Curve (Believe, Bags,
Jupiter Studio and the other DBC launchpads) or a Raydium LaunchLab curve (bonk.fun) could never be proven, so it waited
PENDING at the buy door, or was refused on a provider's "LP unlocked" finding, for its whole curve life. 8731: 29 of 32
LIVE paying-cell coins were PENDING at the door (+36.3 % forward, 34 % runners). On the launch-hour exports, Meteora-sourced
coins under five minutes made +5.3 % (n1645, 5.8 % ran 100 %+); pump.fun's made -15.7 %.

**Changed.** `truth.CurveCustody8881` proves those two curves from their own accounts, never from a label:
- the pool account is owned by the curve program, carries its Anchor discriminator, and names this coin as its base mint;
- it is still a curve: DBC `is_migrated` = 0 and `migration_progress` = 0; LaunchLab `status` = 0 (funding) with a
  wrapped-SOL quote mint;
- its quote vault (jsonParsed) is an initialised wrapped-SOL token account owned by the program's vault authority PDA;
- the SOL in custody (the smaller of the vault and the curve's own quote reserve) clears the curve exit floor (0.5 SOL,
  the pump.fun curve's bar). The proof is good for 30 s, the pump.fun account proof's window.
`LiquidityOwnership8522.activeCurve8881(mint, migrated, now)` gives (active, address): the pump.fun proof first, unchanged,
then this one; never for a migrated coin. In `assess`, the pool at the proven curve's own address is that curve whatever
the provider labels it. The hard gate (`PreTradeHardGate`) and the route check (`BuyRouteSafety8522`) use it, and ask for a
custody read (async) of any pool they could not prove. `TokenSafetyChecker`'s "LP unlocked" overlay exempts such a curve as
it exempts a pump.fun curve (7384); in LIVE it reads the custody before it builds the report so the two agree.

**Layouts.** DBC VirtualPool (zero-copy, 416 bytes after the discriminator; meteora-sdk 0.7.0 `src/dbc.rs`): base_mint @136,
quote_vault @200, quote_reserve @240, is_migrated @305, migration_progress @308; discriminator sha256("account:VirtualPool").
LaunchLab PoolState (Borsh; raydium-launchlab-sdk 0.1.5 `idl.json`): status @17, real_quote @61, base_mint @205,
quote_mint @237, quote_vault @301; discriminator [247,237,227,245,215,195,222,70]. Vault authorities from
`OnChainHolderConcentration7379.VENUE_AUTHORITIES_8418`. A wrong offset fails the mint, discriminator or vault check, so a
misread leaves a coin PENDING as before.

**Not changed.** The 70 % lock rule for every ordinary pool; a provider's unlocked finding on an ordinary pool; a curve
cannot excuse an unprotected ordinary pool beside it; the pump.fun local buy keeps its own pump-only proof (`Executor`);
sellability (8736), authority, holder and every other buy-door proof. USDC-quoted curves are not read.

**Read on the next report.** `curveCustody8881[asked proven byVenue{METEORA_DBC RAYDIUM_LAUNCHLAB} notCurve belowFloor
vaultRefused rpcFailed]`, `lpState8725` PENDING falling for Meteora / LaunchLab coins, and live buys of those coins.

## 71. Where a launch's first seconds go (8882)

**Why.** The launch-hour replay of every export (20 Nov) under the live doctrine: bought at a launch's first print +13.7 %
a trade, ten seconds later +1.7 %, thirty seconds later -7.5 %, sixty seconds later -8.9 % (paths with six bars or more,
so optimistic). The first lane decision on a launch comes a median 41 s after its birth, 25 % within 10 s (37,410
launches). Plan W9 (speed) starts by measuring where those seconds go.

**Changed.** `truth.DecisionLatency8882`: at the first lane decision on a launch inside its first hour
(`LaunchHour8628.observeDecision`), the delay is split into birth -> watchlist (`TokenState.addedToWatchlistAt`, "heard")
and watchlist -> decision ("decide"), per discovery source (the first entry of `TokenState.source`), last 600 per source.
The brief prints `decisionLatency8882[SOURCE{n heard50/90 decide50/90 within10s}]`. Measurement only.

## 72. The crypto twin (8883)

**Owner, 20 Nov.** "paper is meant to turn off when live is running close out all trades and the shadow trader runs while
live does!" and "the shadow trading engine is meant to basically be exactly the same as the live trader in all forms and
functions for the meme and crypto traders". Plan W1, crypto.

**Before.** Since 8872 paper is off in LIVE, so the crypto lanes' own paper record (the record the owner chose to let them
trade live on: `CryptoSpotPaperBacking8788`, 8806) stopped growing. It could no longer stop backing a lane that stops
paying, nor start backing one that starts. The crypto exits lived inline in `CryptoAltTrader.monitorPositions` and its
eviction pass, so no shadow could run them. The meme paper mirror (8757) likewise read a meme paper book that stands down
in LIVE.

**Changed.**
- One crypto exit. The eviction pass's decision is `CryptoAltTrader.evict8883` and the tick's is
  `CryptoAltTrader.exitTick8883`: the same checks in the same order (tick floor with its two reads under -50 %, the peak
  give-back lock, the desk lane's exits, the hard SL and TP, the peak, the adaptive hold, the symbolic exit reasoner, the
  fluid lock, the peak drawdown, the TP safety; the partial-take rung, the fluid trail, deadweight, stagnant, fast loser),
  the same reason strings, logs and labels (through `ExitSink8883`). The live monitor calls them and acts as it acted
  inline. The position a candidate gets is built once (`entryPosition8883`).
- `perps.CryptoTwin8883`. Beside LIVE only, every crypto candidate the live decision opened, and every one a learned door
  refused (not an integrity refusal, `CryptoSignalEngine8857.integrityReason8857`: those bind both engines), is held as a
  virtual position with the live book's entry, size, TP, SL, leverage, reasons and learned hold. Each monitor pass, on its
  own coroutine, it runs `evict8883` on the previous mark and `exitTick8883` on a fresh one, read as the monitor reads one
  (`twinMark8883`: the registry row by exact identity, refreshed past a minute; a static market from its feed with the
  spike guard). Partials are deferred, as LIVE defers them. A close is booked at the mark that fired it, gross, as the
  paper book booked its closes. A twin with no fresh mark for 10 minutes is dropped, never booked. At most 60 open, one per
  asset; the closes (400 per lane) persist, open twins do not.
- The twin's record per lane is graded by the leaderboard's own computation, lifted out unchanged as
  `StrategyTelemetry.metricOf8883`. `CryptoSpotPaperBacking8788` reads it in LIVE with paper off once it holds 20 decisive
  closes (`record8883`); until then the paper record as it stood when paper stopped. The status line marks a twin record.
- `PaperMirror8757.bookLosing8883`: in LIVE with paper off, the MOONSHOT book's losing test reads the lane's twin (the
  evidence shadow, on the live doctrine since 8878) once it holds 30 closes; until then, and in PAPER, the paper book's own.

**Not changed.** Every live exit, its order, reasons and side effects; live entries, sizing, the hourly cap and the
-0.1 SOL stop of the backing; paper mode.

**Read on the next report.** `cryptoTwin8883[open opened records{CRYPTO_ALT{...} CRYPTO_SPOT{...}} exits{...}]`, and
`cryptoSpotPaperBacking8788 records{... twin n...}` once a lane's twin is decisive.

**Test pins superseded.** `GoldenTapeRegressionTest` (7264 floor close, 7332 hold max) and `Aate8867HeapOwnersTest` read
the floor close and the hold check where they now live (`exitTick8883`); `Aate8865ForecastLossLadderTest` reads the
mirror's check through `bookLosing8883`.

## 73. Every decision is read again at 4 h and 24 h (8884)

**Owner.** "there are 60,000% profit opportunities out there literally every day" and "fuck 4x. I want it to look far far
beyond that". Plan W8.

**Before.** 8877 wrote each decision's 60-minute read as a research row. The coins behind a day's 100x are still running
long after the hour, and no record read them there.

**Changed.** `truth.ResearchFollow8884`: each decision that wrote its hour row (`ForwardReturnLabeler7731.bookTwoForty7944`,
never a flat / lost mark) waits for two more reads, 4 h and 24 h after the decision. Once a minute the due decisions are
priced by the Jupiter price API (`lite-api.jup.ag/price/v3`, keyless, 50 mints a call, at most 4 calls a minute) in USD
per token, the entry's own unit (`LabelMark8386`), and each read is written to the research file
(`ResearchRows8877`, exported as `aate_edge_research_8877_*.jsonl`) with its `horizonMs`: the decision's facts, then net
and gross against the decision's entry. A coin Jupiter cannot price is written with net null and reason
`NO_PRICE_JUP_8884:`; a read more than 6 h past its horizon is marked `LATE_READ_8884:`. Peak and dip after the hour are
not known and are null. At most 30,000 decisions wait (oldest dropped); the list survives restarts in
`research_follow_8884.jsonl`. Measurement only.

**Read on the next report.** `researchFollow8884[waiting4h waiting24h written unpriced late failed dropped]`, and rows
with `horizonMs` 14400000 / 86400000 in the research export.

## 74. Moonshot and Boop launches are heard at birth (8885)

**Owner.** "the meme trader isnt a pumpfun trader exclusively is a solana network trader. the full network." Plan W11.

**Before.** `ChainLaunchFeed8179` heard pool and curve creation on Raydium (v4, CPMM, LaunchLab), Meteora (DLMM, DAMM v1,
DAMM v2, DBC) and PumpSwap; pump.fun creates come through PumpPortal and the validator feed. Moonshot and Boop launches
reached the bot only once an indexer listed them.

**Changed.** Two more targets, each matched only by its own program's creation log:
- `MOONSHOT`: `MoonCVVNZFSYkqNXP6bxHLPL6QQJiMagDL3qcqUQTrG` (carbon-moonshot-decoder `PROGRAM_ID`), "Instruction: TokenMint"
  (Bitquery's Moonshot new-token filter is Method "tokenMint").
- `BOOP`: `boop8hVGQGqehUK2iVEMEnMrL5RbjywRzHKBmBE7ry4` (carbon-boop-decoder `PROGRAM_ID`), "Instruction: CreateToken"
  (Bitquery's Boop create Method is "create_token"; Anchor logs an instruction by its PascalCase name).
Both are curves (`curve = true`, venue OTHER). They hold their quote as native SOL, so no WSOL vault pair exists to read:
after the layout and the vault-pair reads find nothing, `nativeCurveMint8885` takes the one mint the creation transaction
initialised, quoted in SOL (never when two mints were initialised, never a quote mint). Their subscriptions mention the
program id, so their buys arrive too; the feed's governor parks a stream that floods, as for every target.

**Not changed.** Every existing target and its reads; the intake every launch enters (safety, LP rule, FDG, lanes).


## 75. The crypto trend cell (8886)

**Owner / plan.** W7: "keeps the classic signal engine. Adds a multi-horizon price and volume trend score for majors, with
order-flow imbalance for entry timing. Twin coverage."

**Before.** A crypto candidate carried its classic signal, its 24 h change and its five-minute set. The registry dropped
the providers' 1 h / 6 h / 7 d changes at parse time, and nothing graded the crypto lanes by trend shape.

**Data added (registry, `DynToken`).** `change1hPct8886`, `change6hPct8886`, `change7dPct8886` under one clock,
`trendObservedAtMs8886` — the set of ONE provider observation; a newer observation replaces the whole set (the five-minute
set's rule), an older one never rolls it back; NaN where the provider did not carry a value; not persisted (as the
five-minute set). Sources: DexScreener pair `priceChange.h1` / `h6` (new `PairInfo.priceChangeH6`, parsed last so positional
constructions stay valid); GeckoTerminal pool `price_change_percentage.h1` / `h6`; CoinGecko `/coins/markets` now asks
`price_change_percentage=1h,24h,7d` and reads `price_change_percentage_1h_in_currency` / `_7d_in_currency` under the row's
`last_updated` (unparseable = clock 0 = no authority).

**The read (`CryptoTrend8886.read8886`).** On the candidate's registry row as it stands at submission (else the row its
evaluation owns): horizons 5 m (CryptoShortWindow8832's own 120 s freshness), 1 h, 6 h, 24 h, 7 d, each counted while its age
is at most half its horizon. Band: TU (< 2 read), T3 (all rising), T0 (all falling), T2 (more rising), T1 (otherwise). Flow:
F1 / F0 / FU (five-minute buys vs sells). Volume: V1 / V0 / VU (five-minute volume vs the day's five-minute mean). Key =
band + flow + volume; graded cell = band + flow.

**Where it goes.** Evidence `trend8886` (the cell) on every crypto canonical candidate; reason `TREND8886=<key>` on the
position the live book or the twin builds (entryPosition8883). The crypto twin files each close under its lane and under
`lane|cell` (same 400-close window, same store).

**What it decides.** `CryptoSpotPaperBacking8788.availability8870(lane, now, cell)`, in LIVE with paper off only:
- a cell whose twin record passes the lane rule (`paperBacks8788`) AND whose mean exceeds `familyZ8886(k)` standard errors
  backs its candidates where the lane record does not; `k` = the lane's cells holding 20+ decisive closes; `familyZ8886(k)`
  is the z with Φ(z) = Φ(1)^(1/k) (Šidák: the codebase's one-SE confidence, held across the family; k = 1 is one SE);
- a cell proven losing (`MemePaperTrader8544.provenLosing8608`: n ≥ 30, mean + SE < 0, PF < 1) withdraws the lane's backing
  from its candidates;
- otherwise the lane record decides, as before. The hourly cap (4) and the −0.1 SOL loss stop stay shared.

**Not changed.** The classic signal engine, its scores and floors; every hard-safety, route, toggle, wallet and integrity
refusal; the lane rule; volume is recorded on the key and not graded on its own yet.

**Brief.** `cryptoTrend8886[cells{...} horizonsRead{n0..n5} ...]` and `cryptoCells8886[read backing{...} losing{...}
reservedByCell ...]`.

## 76. The hourly authority table (8887)

**Plan.** W2: "every hour, replays the last 48 h of decisions ... with no look-ahead and an embargo. It grades each door,
score band, setup ... on ex-top-3 mean, median and halves, deflated for the number tried. It publishes one authority table."

**Rows.** `edge_research_8877.jsonl` (ResearchRows8877): every meme decision's read at 1 h (8877) and again at 4 h and 24 h
(8884), with the decision's lane, admitted flag, refusal, stage, setup, forecast, V3, cap and pool, and its net after the
round trip. 8884's `LATE_READ` rows are not that horizon's read and are left out; its `NO_PRICE` rows stay in their cells as
unpriced (net unknown).

**Pass (`AuthorityTable8887`).** Ten minutes after the first research row of a run, then hourly, on its own thread
(`JobCost8813` background job). Per horizon (1 h, 4 h, 24 h): decisions of the last 48 h whose read is in (`t + horizon <= now`:
the embargo; only the line head is read for rows outside the window).

**Cells, within each lane.** `lane=ALL`; `admitted=Y|N`; `door=<FdgGate8365.gateKey8392(reason)>` (refused rows); `stage`;
`setup`; `v3|pred|mcap|liq = Q1..Q5` — quintiles of that lane's window, cut points published with the table.

**Per cell.** n, unpriced, mean and median of the priced reads, the mean without the three best rows, the standard error,
the earlier- and later-half means (decision order). Deflation: `k` = cells of the horizon with 20+ rows;
`z = CryptoTrend8886.familyZ8886(k)` (Šidák on the one-SE confidence).

**Verdicts.** PAYS: n >= 20, ex-top-3 mean − z·SE > 0 and both halves > 0, with every unpriced read counted as a total loss
(−100 %). LOSES: 20+ priced reads, mean + z·SE < 0 and both halves < 0, on the priced reads alone. Otherwise UNPROVEN.
Neither verdict can be flattered by a coin the price source could not price.

**Published.** The brief line `authority8887[... 1h{rows graded z pays loses best[...] worst[...]} 4h{...} 24h{...}]`; the
file `authority_table_8887.json` (every cell with 20+ rows, its stats and verdict, and the cut points), saved with the report
beside the research rows; `AuthorityTable8887.verdict8887(horizon, lane, factor, value)` and `bandOf8887(...)` for the
authority consumers. Nothing admits or refuses on it in this build.

**Not covered.** Venue (the research row does not carry it); crypto decisions (they are graded on the crypto twin, §72/§75);
the launch tapes and lab strategies keep their own replays (LaunchReplay8744, StrategyLab8755). A decision whose mark was
lost writes no research row, so neither the table nor anything else books it.

## 77. Each doctrine lane grades its own give-back (8888)

**Plan.** W5: "lane exits keep a runner's remainder, with learned give-back per lane graded hourly". The default stays the
doctrine (RunnerGrab7967.TIERS_7967 rungs, a 45 % give-back from a 2x peak).

**Data.** LaunchHour8628's store: every pump.fun launch's first-hour prints (48 h) and every lane decision on it. New read
`firstDecisions8888(now)`: each lane's FIRST decision per launch (`SELECT m,l,t,a,c FROM decision WHERE t >= now-48h ORDER BY
m,l,t`, earliest row per mint x lane): its time, admitted flag and the round trip it was priced at.

**Grade (`LaneExitGrade8888`, inside every LaunchReplay8744 pass, 30 min).** For each doctrine lane's decision whose time
falls inside its launch's stored prints (spikes removed, clean8748), the live doctrine is run on those prints exactly as the
evidence shadow runs it (EvidenceShadow8344.doctrineWhy8878): entry at the decision's print plus the lane's measured chase
(ShadowFill8364.laneEntryPx8364); the lane stop FieldManual7715.stopPct8392(lane, cost) in 3..40 %; level
`RunnerDoctrine8584.paperStopLevel8584(stop, peak, keep)`, sold on a second print at or under it (or at once under −60 %);
TIERS_7967 rungs on the gross (MemePaperTrader8544.rung8544), the clock not read on a rung's print; the lane clock
(CortexLabelHygiene8144.horizonMsFor8468) only where RunnerDoctrine8584.holds8584 lets a time exit pass; each sale filled
where the curve stood the live sell latency (ShadowAlign8366.sellLatencyMs8366) after its print, less the shadow's exit slip;
whatever is held at the hour's last print marked there; less the decision's own round trip.

**Shares.** `KEEPS_8888 = 0.70, 0.55 (doctrine), 0.40` of a 2x-plus peak (give-back 30 / 45 / 60 %). RunnerDoctrine8584's
`line8584`, `lineWithStop8719` and `paperStopLevel8584` take the kept share as a parameter defaulting to `KEEP_8584 = 0.55`:
every live call is unchanged bit for bit.

**Verdicts.** Per lane, each other share's paired difference against the doctrine (mean, sample SE): BETTER when mean − z·SE
> 0, WORSE when mean + z·SE < 0, otherwise SAME; n >= 100 (LaunchReplay8744.PROVEN_MIN_N_8748); z = CryptoTrend8886.familyZ8886
over lanes graded x two shares. The brief line `laneExits8888[...]` prints, per lane, n, admitted, the doctrine's mean /
ex-top-3 / median net, and each share's difference ± SE, verdict and how many decisions it changed.
`provenKeep8888(lane)` names a BETTER share; nothing live reads it in this build.

**Not covered.** The prints after the first hour (a runner still held is marked at the hour's end, as every launch-replay
grade is); launches the store has no lane decision on; lanes the doctrine does not own.

## 78. 8888's lane exit grade compiles (8889)

8888 failed to compile: `LaneExitGrade8888.grade8888` found the doctrine's share with `DoubleArray.indexOf`, which this
Kotlin does not offer for floating-point arrays. It is now `indexOfFirst { it == RunnerDoctrine8584.KEEP_8584 }` (the same
exact comparison). Behaviour is 8888's (§77) unchanged.

## 79. The mirror keeps 5.0.8882's stop (8890)

**Owner.** "make sure we dont fuck with the behaviour thats made money!" (5.0.8882: DQKmfa on SHITCOIN sold +102 / +402 /
+1,114 % on the doctrine; 5.0.8889 live n10 PF 8.64, SHITCOIN n5 80 % PF 52).

**Every change since 5.0.8882, checked against the meme live path.** 8883 crypto twin (crypto only); 8884 later reads
(measurement); 8885 Moonshot / Boop births (more launches heard, judged by the unchanged lanes); 8886 trend cell (reads only
`lane8788`, which meme candidates do not carry: their backing answers are unchanged); 8887 authority table and 8888 lane exit
grade (measurement; RunnerDoctrine8584's line functions bit-identical by default). One change reached a meme live decision:
8883's `PaperMirror8757.bookLosing8883` REPLACED the paper MOONSHOT book's record with the evidence shadow's once the shadow
held 30 closes, so a shadow that stopped proving a loss would have switched the mirror back on (live MOONSHOT admits 5.0.8882
never made: mirrored=0, book proven losing 1,678 times; MOONSHOT live n3, all losses).

**Changed.** `bookLosing8883` = `stops8890(paperLosing, shadowN, shadowLosing)` = paper record proven losing, OR (LIVE with
paper off, 30+ shadow closes, shadow proven losing). The paper book's own record decides exactly as in 5.0.8882; the shadow
can only add a stop, never lift one.

## 80. Why an hour read stopped moving (8891)

**Found (owner's 20 Nov export).** At 11:00:36 eight lanes decided DQKmfa (the coin the doctrine sold at +102 / +402 /
+1,114 %). EXPRESS's hour read saw the run (peak +767.8 %, net +20.0 %); the other seven read peak +0.78 %, net −2.33 %: the
price minutes after the decision. In that export, of 37 same-moment multi-lane decisions whose best read saw 2x+, 10 had a
lane under half that multiple (37 of 214 rows). The hour read is the label the learners are taught.

**Not yet changed.** The label rules (LabelEvidence8386 venue / extreme checks, the 8133 jump guard, the 8165 / 8337 curve
checks) decide what the learners learn: no rule changes until the rows show which one froze these reads.

**Added (measurement only).** Each ForwardReturnLabeler7731 observation counts the marks it refused at each check
(`skips`: ELIGIBILITY, CACHE, COPY, CURVE_CONTRADICTED, CURVE_HELD, JUMP, INELIGIBLE), the marks it accepted, the last
non-routine refusal's status, venue and source, its basis venue and source, how long before booking its last accepted mark
was (`lastAcceptAgoS`) and how the hour was booked (`via`: TICK or RECOVERED:<source>). The hour's research row carries them
as `diag8891`; no learner reads research rows. The brief's research line counts hour reads booked on a last mark older than
the labeler's own 45 s price age bar, by that last refusal (`staleHour8891{...}`). Not persisted: a restored observation
counts from its restore.

## 81. What the CPU is doing (8892)

**Why.** 20 Nov: 5–7 GB allocated a minute from the first minutes, the collector on ~31 s of CPU a minute, cycle 3.5–5 s,
heap at 100 % after 25 minutes on 5.0.8882. The heap map (§57, 8866) names what HOLDS the heap; the 8889 map (295 MB) caught
16 MB of garbage — too little to name what churns it. JobCost8813 meters nine batch jobs, as an upper bound.

**Added.** `CpuSample8892`: after a report Save, the memory-map dialog also offers "CPU sample (30s)". On that tap only:
`android.os.Debug.startMethodTracingSampling(cache/cpu_sample_8892/cpu_<t>.trace, 32 MB, 20,000 µs)` for 30 s (ART samples
every thread's stack every 20 ms), stopped in a `finally`, saved to Downloads/AATE as `aate_cpu_sample_8892_<t>.trace`, then
deleted from the app; a file left by a process that died mid-sample is deleted at start. It holds thread names, class and
method names and sample times — no field or variable value. The brief line `cpuSample8892[...]` names the last outcome.

**Not changed.** Every trading path. The sample pauses the app's threads briefly at each sample; the interval keeps it short.

## 82. 8891's test reads the stale-hour line wherever its reason sorts (8893)

8891 and 8892 failed one required test of their own: `Aate8891LabelStallTest` expected the reason it noted to be the first
entry of `staleHour8891{...}`, but other tests of the same run had already counted stale hour reads, which sort first by count.
The test now finds the reason anywhere in the braces. No app code changed: 8891's and 8892's behaviour (§80, §81) is unchanged.

## 83. The phone or the gates (8894)

**Why.** Owner, 20 Nov 18:08 on 5.0.8893 (10 minutes up): "its kinda dead hey. guessing fdg is blocking pretty much
everything". The FDG admitted 0 of 554 decisions; on 5.0.8882 it admitted 1 of 1,942 (the DQKmfa buy), on 5.0.8862 11 of
7,794 and on 5.0.8864 1 of 8,958, so 0 of 554 is what the 5.0.8882 rate predicts (0.3 admits). No meme buy gate changed in
5.0.8883–5.0.8893; the crypto backing's trend cell (8886) read 10 cells and changed no answer (`backing{-} losing{-}
reservedByCell=0`), the hourly cap of 4 binding as on 5.0.8882. What did change in those 10 minutes was the network: jupiter answered 5 % of its calls (150 transport
failures), jupiter_price_keyed 10 % (112), dexscreener 67 % (58), raydium 68 % (55), helius 70 % (44), at 2–9x the latency of
12:48; held-quantity quotes 0 of 260 (5.0.8882: 517 of 911); the wallet read failed long enough for 69 WALLET_BLIND_8074
refusals. At 12:48 on 5.0.8889 every one of those hosts answered 100 %.

The 5.0.8889 and 5.0.8893 CPU census (§8815) also put one of this session's own reads at the top of the app: 8888's
`LaunchHour8628.firstDecisions8888` at 9.4 % and 8.9 % of every on-CPU stack. It sorted every decision row of 48 h
(595,822 in the 20 Nov export) by mint, lane and time and made strings of each, at start and every 30 minutes, for a grade
that reads only the replayed launches.

**Changed.** `firstDecisions8888(now, mints)` reads only the launches the replay graded, each by its own mint on the decision
table's primary key (m,t,l,k,a): `SELECT l,MIN(t),a,c FROM decision WHERE m=? AND t >= ? GROUP BY l ORDER BY l` — the
earliest row of each lane (with one min(), SQLite takes the other columns from that row). Same first decisions, same
values, for the same launches. `laneExits8888[...]` prints the read's cost as `read8894{mints withDecisions lanes ms}`.

**Added (read-only).** `NetworkVerdict8894`: on the brief's A RUN section, `network8894[OK|PROVIDER|DEGRADED ...]` from
ApiHealthMonitor's recent (decayed) counters. A host is failing on transport when at least 25 % of its last 20 or more
calls got no answer at all; three or more distinct providers failing together is DEGRADED (the device's network, not a
provider or a gate) and is ranked as a FAULT fix. Nothing trades on it.

**Not changed.** Every gate, size, exit and the LP rule.

## 84. Selection, additive: the lock read on chain, the pocket on coins (8895)

**Why.** Owner, 20 Nov: "how do we improve the trade selection logic?? ... you have to stop saying it cant find runners";
"every time you make a change you must forensically examine the impact of that change. it cant be a regression or another
stacked gate". The 17-20 Nov edge dataset, first decision per coin, net after cost:

| coins | n | mean | median | without top 3 | won |
|---|---|---|---|---|---|
| pump.fun mints (85 % of what the bot judged) | 5,644 | -8.4 % | -3.7 % | -10.6 % | 11 % |
| not pump.fun, $5k-$20k cap | 284 | +18.1 % +-3.9 | +8.7 % | +14.9 % | 57 % |
| same, V3 >= 23 and forecast >= +10 % (the agreement pocket) | 46 | +42.8 % +-10.2 | +30.7 % | +28.9 % | 80 % |

None of the 46 was bought. On 5.0.8893: the 8874 pocket had carried no coin (its per-lane proof needs 30 labels; the
largest lane held 25); paying-cell coins were decided DATA_INCOMPLETE_DEFER 305 times and LIVE_SETUP_LOSES_8714 72 times,
admitted 0 times; all 46 pocket coins were under five minutes old, 42 with no LP reading and 4 under 70 %, and the LP rule
reads the lock only from RugCheck.

**Changed — each one only adds a yes the record or the chain proves; no refusal is added anywhere.**

1. `PoolLock8895` reads the LP lock from the pool's own accounts. Meteora DAMM v2: `permanent_lock_liquidity / liquidity`
   (pool.rs, Pool @360 / @552, 1,104 bytes + discriminator). Meteora DLMM: every position of the pair (program
   discriminator + pair filter, extended positions included), locked when `lock_release_point` is past the chain clock +
   24 h (slot or unix time by the pair's activation_type); the locked share is the smallest locked fraction of any bin.
   `LiquidityOwnership8522.assess(..., chainLock8895)`: 70 %+ on the coin's selected pool turns an answer that would be
   PENDING into PROVEN (`LP_LOCK_CHAIN_PROVEN_8895`). *Impact:* every BLOCKED is decided before it is consulted (a provider
   report of unlocked liquidity, a pool under 70 %, the old report's number); an unlocked or unreadable pool stays PENDING;
   PreTradeHardGate's on-chain RugCheck substitute (8471: mint revoked, holders read and under the fatal limit, an executable
   exit, LP proven) can now complete for a fresh coin RugCheck has not indexed, as 8471 designed; the 8736 sell-back quote,
   freeze / Token-2022 checks, holder distribution, depth and every other door are unchanged. The unread-LP explore path
   (8733) stays off. Cost: one getAccountInfo per pending pool per 30 s (keyless first); DLMM adds the Clock and one or two
   getProgramAccounts (Helius, 10 credits each); a pool of another program is not asked again for 30 minutes; one worker,
   queue of 16.
2. `AgreementPocket8874` coin record: the last 200 pocket coins, each held once at its WORST meme-lane label of its first
   15 minutes, proven on the paying cell's bar (30 coins, mean - 2 SE > 0). A meme lane carries it when its own pocket
   record is not proven losing. *Impact:* the same evidence kind with the same precedence as 8874 (it answers the lane-wide
   priors 8724 lists); hard safety, LP lock, sellability, economics, avoid shapes and named setups still decide. On the
   exported dataset from the phone's learning line the record holds 50 coins, +33.9 % at the worst lane, mean - 2 SE +17.0 %.
3. `LaneAuthority8577.residualAnswered8895`: LIVE_SETUP_LOSES_8714 refusing a coin that carries proven pocket / paying-cell
   evidence is answered when the losing "setup" is the lane's NO-SETUP cohort (NO_TRIGGER or OUTSIDE_ENVELOPE_8383 — every
   coin of the lane that matched nothing). *Impact:* a named losing setup still refuses, the expert avoid shapes still refuse,
   a coin without proven evidence is unchanged. Counted per lane and cohort (`residual8895{...}`).
4. `MarketCohort8701.cohortShare8895`: the watchlist adds the coin's origin x age x pool cohort's rank among cohorts graded
   on 30+ labels (its own forward record) times the same 510 span the stage record uses. *Impact:* order only — every coin is
   still looked at; a coin of an ungraded cohort sits in the middle; read once per coin per 10 s from the coin's own fields.
5. The evidence funnel (8876) names what an evidence coin's data deferral waited for (`wait{...}`, the admission's own 8700
   causes). Measurement only.

**Not changed.** Every gate, size, exit, the LP-lock rule's 70 % bar, sellability, balance. Unlocked pools are not bought.

## 85. 8895's test compiles; a chain-proven lock stays fresh (8896)

8895's own test called `CandidateAdmission8136.supported8136` without its package (the class lives in engine.truth), so the
unit-test compile failed and no test ran; the release APK itself had compiled. The reference is now qualified. One addition,
from the 8895 impact review: a lock proven from the chain (`LP_LOCK_CHAIN_PROVEN_8895`) holds 60 s and was re-read only once
it had lapsed, so a coin could be deferred for one look every minute while it was read again. PreTradeHardGate now also asks
for a re-read while the chain proof holds (still at most once per pool every 30 s). Same reads, same rules; no gate changed.

## 86. 8896 rebuilt: the smoke's accounting witness met a mid-read state change (8897)

8896's unit tests passed; its runtime smoke failed the 120-second execution-spine witness with every accounting delta 0.000000
(cash 9.519884 = 9.519884, realized 0.121216 = 0.121216, open cost 2.254 = 2.254, quantity 0) but `accountingCurrent=false`:
ForensicReconciliation6635 counts a reconciliation as current only when the accounting state key is the same before and after
the read, and the emulator's paper book closed a position during it (TICK_HARD_FLOOR at 09:59:02). 8895's smoke, on the same
app code but one condition, passed that witness. The run cannot be re-run from here, so 8897 rebuilds 8896 unchanged.

## 87. Read the data right (8898)

Two lines at the top of the 20:53 report (5.0.8894) were wrong about the data, and every later item reads them.

**The network.** `network8894[DEGRADED failingProviders=3 ...]` and FIX #2 "device network degraded" were raised by three
providers timing out: helius-enhanced (81 of 147 calls no answer, 4.8 s average), defillama (41 of 110) and the public Solana
RPC (32 of 115). The other providers in use answered 90-100 %: dexscreener, geckoterminal, groq, helius, helius_curve, the
Meteora APIs, Jupiter's keyed price and quote. Device-wide now also needs at least half of the providers in use
(`active8898`, any host with 20+ recent calls) failing together: 3 of 9 reads PROVIDER; 17:57 on 5.0.8893 (4 of 4) stays DEGRADED.
Impact: brief only (the test of 8894 still proves no gate, size or exit reads it).

**Label coverage.** FIX #1 "label coverage too low (admitted 23 %, refused 35 %)" read AGG|ADMITTED n60=1300 lost=4269.
The same report's labeler priced 1941 of 2916 five-minute reads this session (67 %) and 1739 of 1843 hour reads (94 %).
Cause, from the code: `book()` counts a coin once per cross-lane key (AGG|*, SRC|*; 8776) and files an hour-target lane's read
under its own horizon (AGG|ADMITTED@15M; 8766); `markLost()` did neither, so one coin lost by every lane that judged it added one
loss per lane, under the five-minute key, against one booking. And a tally's lost count fades only as new labels book, so a
slow cohort carries earlier sessions' losses. Now:
- `markLost` files under `horizonScoped8766(...)` and counts a cross-lane key once per coin decision (`firstLost8898`).
- `recentCoverage8898(admitted)`: each coin decision counted once priced / once lost at most, faded over the last 600 events per
  side; the brief prints `coverageNow8898{admitted=..%/n.. refused=..%/n..}` on the C EDGE line.
- The brief's verdict, its FIX text, and `LiveEdgeGate7877.bookCovered8505` read `coveredNow8898` — the recent share once it has
  100 events, else the lifetime tally exactly as before.

Impact, forensic: `bookCovered8505` was written (8505) to stand watch-first's band / cell verdicts down while the labeler's
CURRENT pricing is poor. Read correctly, a band or cell that is itself covered (its own resolvedShare >= 50 %, per-lane keys,
unchanged) and proven losing (20+ labels, mean + SE < 0) refuses again inside watch-first. The 20:53 report shows no
watch-first event at all (the FDG refused first), so no buy that report saw would have changed. Nothing else reads the cross-lane
lost counts: ScannerIntake8292 / LiveEdgeGate7877.sourceRefusal8536 read source n, mean, SE and the outlier flag only.

## 88. What held the heap (8899)

The 21 Nov 00:05 report (5.0.8898, 73 min) was "dead" for memory, not for the gates: the heap sat at 459 of 512 MB, ART's
blocking collections ran 1,042 times for 3,620 s of 71 minutes (8894 at 41 min: 394 times), the bot loop fell from 31 to 17
ticks a minute, 901 coin cycles ran out their 20-second budget, and the labeler priced 28 % of due marks (8894: 83 %), so the
FDG deferred on stale prices. The owner's heap map at 01:08 (uptime 137 min, 454 MB used) names what holds it. Two holders
keep data nobody reads:

- `KeylessRpc8359.largestCache8359` — 15.3 MB of parsed getTokenLargestAccounts JSON. Answers live 60 s, but an expired entry
  left the map only when the same mint was read again or the map reached its 4,000 cap, so it sat at the cap. `put8359` now
  sweeps expired entries at most once per TTL (`sweepDue8899`). A served answer is unchanged: an expired one never was served.
- `CanonicalFinalityPersistence6486.excluded8094` — 16.1 MB, one String object per excluded close for its reason. Reasons are
  now interned at load and at record: equal text, so every reader (hasExclusion8858, excludedIds6734, the blob) is unchanged.

Forensic impact: no gate, size, exit, ack or exclusion decision changes. Expected saving about 15 MB from the first (the cache
holds about one TTL of answers) and a few MB from the second, of a 512 MB heap. The larger holders in the same map are the Cortex
ledgers (CortexLedger7885$Stat 41.7 MB, 455,938 objects, 299,182 at 8889's start; CortexTiming7900 the largest) and the
garbage of each Cortex snapshot encode (33.8 MB). They hold learning, so they are next, from the CPU sample that names the
allocation, not from a guess.

## 89. 8899 rebuilt: the smoke runner's emulator image failed to download (8900)

8899's build and unit tests passed. Its runtime smoke never started the app: the runner's `sdkmanager --install
'system-images;android-30;google_apis;x86_64'` failed ("Error reading Zip content from a SeekableByteChannel"), no emulator
booted (adb: connection refused), and every later check reported NOT_RUN. The run cannot be re-run from here, so 8900 rebuilds
8899 unchanged.

## 90. Audit of 20-21 Nov, and the playbook save paced (8901)

Owner, 21 Nov 02:59: "still blocked ... bought tokens that then it couldn't sell due to no liquidity ... has the new changes
sent things backwards? picking the wrong tokens again?" Read from the four reports (20:53 on 8894, 00:05 / 01:08 on 8898, 02:09
on 8899), the research files, the 01:08 heap map and the 02:09 CPU sample.

**The unsellable token.** 蚂蚁 (PDXr5bCZnQ...) was held 160 minutes at 00:04 (STALE_FLAT_CULL_7353), so it was bought about
21:24 on 20 Nov, on 5.0.8894, before 8895-8900 were installed. Its decision rows (edge research) read mcap $54k and pool
liquidity $87k (POOL_RESERVES_VAULT_8571). By 00:05 its pool mark was 8.6e-07 with no sell quote: the liquidity was pulled after
the buy. About 0.043 SOL lost.

**What 8895-8900 bought or refused.** Live buys since 8895: 7uGMix (MOONSHOT 23:41, sold 23:47 at -1 %) and CYBERLEEK
(CASHGEN 00:05, $2.5M cap). One buy refused at the route: TTWO, price impact 59.2 % > 3 % (the safety held). None of the new
decision paths changed a buy or a refusal in these reports: poolLock8895 read 27 pools, 14 Meteora DAMM v2 UNLOCKED and 0
locked, so it proved nothing; residual8895 never answered; no COINS_AGREE evidence was used; no watch-first event fired after
8898's coverage read.

**Why it went quiet.** FDG evaluations per minute: 8894 at 41 min 76 (13 allowed); 8898 at 73 min 12.5 (3 allowed), at 136
min 8.4 (4 allowed); 8899 at 6 min 55. The share allowed per evaluation is unchanged (0.3-0.4 %); the number of evaluations
fell six to nine times as the heap filled (§88). The deferral causes are the same as on 8894 (MOVE_UNREAD, ENTRY_WAIT_*,
PRICE_STALE, FALLING_HOUR).

**The CPU sample (02:09, 30 s, fresh start, heap 54 %).** 69 s of thread CPU: HeapTaskDaemon 14.8 s; the report save 14.4 s
(LaunchHour8628.export, the owner's Save only); AATE-Entry 7.6 s (processTokenCycle: SpecialistBuild8522 / ToolkitSignalSheet
election 3.6 s); AatePlaybookCheckpoint8372 4.9 s. The last runs all the time: deliverIndependent8871 requested a save for every
forward outcome and the worker saved back to back, each save re-encoding every lane book, 600 grades, the timing books and the
episode sidecar to apply at most 32 deliveries.

**8901.** The playbook checkpoint's next save waits four times the last save's measured cost, at most 10 s
(`COST_MULT_8901`, `CHECKPOINT_PACE_MS_8901`; Cortex7885's 8652 measured-cost pace): requests inside the wait coalesce; each save
applies up to 320 waiting deliveries (`SAVE_BATCH_8901`); a shutdown flush (persistNow7930) skips the wait. The brief prints
`playbookCheckpoint8901[saves lastCostMs waiting]`. A first 8901 commit paced a fixed 10 s; its own CI failed nine
Aate8871PlaybookDeliveryTest cases (their 10-second worker flush timed out behind the sleep), so the pace is measured instead:
a save of a few ms waits a few ms, a 400 ms save leaves 1.6 s free. Forensic impact: what a save writes, its atomicity and receipt
order are unchanged; a delivery becomes durable at most 10 s later, and the producer re-delivers anything unacknowledged, as it
already did for a save that failed.

## 91. 8902 rebuilt; a paper ledger/journal defect the smoke caught (8903)

8902's build and every unit test passed (the 8871 playbook tests included). Its runtime smoke failed the execution-spine witness
on a real paper-accounting divergence, not on 8902's change: at 18:01 on the emulator, paper position EYX45DKQCM took two
partial sells whose journal commits each logged PAPER_ATOMIC_COMMIT_JOURNAL_DUPLICATE_6632; its full close was then refused by
the ledger (LEDGER_REJECTED_QUARANTINED_CLOSE_6502 invariantBroken=true gross=0.118783 basis=0.064400
action=refuse_cash_and_realized_mutation) while the journal wrote the lot's residual basis off
(JOURNAL_TERMINAL_SELL_RESIDUAL_WRITTEN_OFF_6868 residualBasisSol=0.064400). From then on the ledger's open cost exceeded the
journal's by exactly 0.0644 SOL (FORENSIC_OPEN_COST_DELTA_6635), and the witness reported openCostDelta=0.064400. Nothing 8895-8902
changed touches PaperAccountLedger6430, TradeHistoryStore or the journal write-off (diff 8900..8902: LanePlaybook7907, its test,
DiagnosisBrief8438's line, the docs). It needs a market sequence of partial sells on one paper lot, which 8900's smoke did not meet.
The defect stands: a quarantined close leaves the ledger holding a basis the journal has written off. It is paper accounting only
(live closes are chain-proven, §8041), and it is the next accounting item. 8903 rebuilds 8902 to re-run CI.

## 92. Audit of 21 Nov, and the base pocket (8904)

Owner, 21 Nov 14:07: "do the suggested work and audit the data". The 13:48 report (5.0.8903, uptime 79 min), its research and
authority files, and the earlier reports of the day.

**8903 on the phone.** FDG evaluations 2,320 in 79 min (29 a minute; 8898 at 73 min 12.5, 8894 fresh 76); allowed 3. Heap 83 %
(SOFT), average cycle 5.2 s. The run before it crashed at 3.5 h with OutOfMemoryError in android.app.QueuedWork (the
SharedPreferences write-back queue). The playbook checkpoint saved 202 times in 79 min at 1.7 s a save (8901's pace: the next
waits 4x that, so it now runs about a fifth of the time instead of continuously). Live: 20 closes in the governor window,
profit factor 3.27, +0.0057 SOL a trade; wallet 0.2224 SOL; nothing open. Refusals: EDGE_MODEL_NEGATIVE 978, DATA_INCOMPLETE
502, EXECUTION_ECONOMICS 377, SAFETY 133, LIVE_STAGE_LOSES 83.

**What the refused coins did (hour reads, one per coin per hour, executable net after cost, 26.6 h, 4,833 reads).** BLUECHIP's
MCAP_TOO_LOW refusals n786 -8.8 % at the hour (median -3.4 %), -14.8 % at four hours without the top three; LIQ_BAND refusals
n193 -2.9 %. Off-pump.fun coins with a $5k-$20k pool n77 -5.6 % (four hours n34 -13.8 %), $20k-$100k n111 -4.5 %. By the stage
at the decision: FRESH_LAUNCH n59 -20.8 %, DUMPING n259 -28.4 %, PEAK_EXHAUSTION n158 -14.0 %, MID_ACCUMULATION n47 -5.0 %,
CONTROLLED_MARKUP n65 -9.9 %, unknown n1201 -6.8 %; BASE_START n164 +4.0 % (four hours n41 -38.3 %). The five-minute +6.5 % /
+31 % reads the brief prints for these cohorts fade inside the hour; opening those doors would have bought losing coins.

**The one pocket that points up.** BASE_START, not minted on pump.fun, pool $20k-$100k: 51 coins, +18.3 % mean, +14.6 % median,
68 % winners at the hour (pump.fun mints in that band n21 -14.7 %). Under the bot's own proof rule (each coin once at its worst
meme-lane read, the gain kept to the pocket cap): n49, +13.3 % +- 8.6, both halves positive (+6.6 / +19.9), lower bound -3.9 %
— not proven.

**8904.** `BasePocket8904` keeps that record: filed from every hour research row as it is written (ResearchRows8877.note8877)
and seeded from the device's research file, the newest 200 coins. Once proven (30+ coins, mean above zero by two standard
errors) a coin in the pocket carries it as market-cell evidence (CandidateAdmission8136, after the agreement pocket, before the
LLM band) with the agreement pocket's precedence: it answers the lane-wide priors (LaneAuthority8577.answersPriors8874) and the
aggregate forecast (a specific record, 8843). Safety, structure, sellability, the LP-lock rule, executable economics, the
coin's own chart shapes and the losing record of the setup it trades as still outrank it. The brief prints
`basePocket8904[PROVEN|UNPROVEN n/mean±se/lower/run loaded learned served rule]`. Forensic impact: on the data it was chosen from
it does not prove, so it buys nothing until the coins that follow tighten its record; then it admits only coins in the pocket.
The pocket was chosen after looking at many cells, so its forward record (the coins after this build) is the one that counts.

## 93. The base watch and the Cortex census (8905)

Owner, 21 Nov 14:15: "do the next up now" — the memory crash, and keeping base candidates watched.

**The base watch.** Coins left the hot watchlist by two stale demotions (13:48 report: WATCHLIST_STALE_EVICT_TIME 762,
WATCHLIST_STALE_EVICT 108): 30 minutes untouched, or 96 cycles processed. A base forms after the launch and the dump, often
later than that, so the base pocket's coins were read less (probation) by the time they based. A coin the pocket could buy —
Solana mint, not pump.fun, a $20k-$100k pool — now skips both demotions for its first 6 hours on the watchlist
(`BasePocket8904.watches8904`, counted WATCHLIST_BASE_WATCH_KEPT_8904). Impact: the 20-21 Nov hour rows hold about four such
coins an hour, so the hot list keeps a few dozen more entries at most; the 500 hard cap, the banned / quarantine purge and
every gate are unchanged; nothing is bought by being kept.

**The memory crash.** The 13:48 report's previous run died at 3.5 h of OutOfMemoryError while android.app.QueuedWork wrote
SharedPreferences (the allocation that failed was 24 bytes: the heap was full, the writer was only where it ran out). Its
memory census: cortexLedgers8309 43.6 MB (the largest store, and growing: CortexLedger7885$Stat 299,182 objects at 8889's start,
455,938 at 01:08), prefsMirror 12.9 MB, learnerAttribution 9.2 MB (bounded: 10k prior + 20k current per learner), exploreShadow
7.1 MB, insiderTracker 6.2 MB, and 310 MB unattributed (mostly garbage between collections). A Cortex seat is keyed
voter|lane or voter|lane@regime, so the ledger grows with each new voter, lane or regime — but which one is not in any report.
The brief now prints `cortexSeats8904[timing{...} main{...} LIVE{...} PAPER{...} exit{...}]` (seats, voters, lanes, regimes,
regime seats, empty seats: a seat with nothing in it reads exactly like a missing one). The next report names the growth, and
the fix goes there. Read-only.

## 94. The lab process, stage 1 (8906)

Owner, 21 Nov 14:21: "we cant move any thing into helps or co routines to reduce the memory" — "yees". A thread or coroutine
shares the trading process's 512 MB heap; a second Android process has its own. `LabProcess8906` is that process
(android:process=":lab8906"). AATEApp.onCreate returns at once there, before any sweep, crash mark, learning reset, memory
guard, journal or bot start, so nothing that trades or writes trading state runs in it.

Stage 1 moves the launch-hour export (the owner's Save; 02:09 CPU sample on 5.0.8899: LaunchHour8628.export 14.4 s of CPU in
30 s, its garbage landing while the report is captured). The lab opens the database read-only (as the export always did) and
writes the file under cacheDir/lab8906; the one in-process value the export prints, the unit-mismatch counter, travels in the
intent. The trading process waits up to 120 s for the done marker, saves the file as before (LAB_8906_EXPORT_OK), and exports
it itself if the lab does not answer (LAB_8906_EXPORT_FALLBACK); leftovers older than 10 minutes are swept. The runner-study
export stays in the trading process: it reads RunnerRouteQuotes8853's in-memory pending rows, which need a file handoff first.
Next stages, each with a file handoff: the 30-minute launch replay (9.5 s of CPU a run), the hourly strategy lab, the edge
model's retraining.

8904 failed to compile (BasePocket8904.add: a synchronized block ending in an if without else, used as its value); 8905
carried it. 8906 fixes it (the block ends in Unit), so 8906 carries 8904 and 8905.

## 95. The lab process, stage 2: the base family (8907)

Owner, 21 Nov 14:37: "do the next stage". LaunchReplay8744's 30-minute run grades ~8 families on the same launch paths, and
their results feed the live sniper and the evidence chain in the trading process (BaseFamily8754, SmartMoney8756, SignalReplay8828,
RunnerModel8861, LaneExitGrade8888, ColourShape8807, the forward grade) — each needs its own handoff, one at a time. The heaviest
is first: the 04:38 CPU sample on 5.0.8900 put BaseFamily8754.simulate at 6.4 s of the 30 s, inside the replay thread's 9.5 s.

`BaseFamily8754.evaluate8754` now asks the lab (LabProcess8906.baseFamily8906): the paths and the run's inputs (SOL price,
cost, the bot's latency) are written as one binary file, streamed; the lab reads them, runs the same pure grade
(`grade8906`, split out of evaluate8754 unchanged), and writes each graded rule as its index in `rules8906(need)` and its nine
numbers; the trading process reads them back into the same Graded rows and sets last / proven / shadow as before. If the lab
does not answer in 90 s, or a rule index does not resolve, the replay thread grades here exactly as before. The status says which
ran: `baseFamily8754[... where8906=lab|here]`. Forensic impact: the proven / shadow rule and every graded number are the same
function of the same paths (Aate8907 round-trips both files and compares the lab's rows with the local grade); the sniper reads
them as before. The other replay families stay in the trading process for now.

## 96. 8907's compile fix (8908)

8907 failed to compile: LabProcess8906.baseFamily8906 was public and took List<LaunchReplay8744.Path8748>, an internal type.
It is internal now (its only caller, BaseFamily8754, is in the module). 8908 carries 8906 and 8907 unchanged otherwise.

## 97. The reversal cell (8909)

Owner, 21 Nov 17:28: "the market is down big today. there should be calculated buying opportunities on majors to capture
reversals surely". The crypto twin at 13:48 (5.0.8903): CRYPTO_ALT n400 mean -1.4 % (pfEdge -3.1), CRYPTO_SPOT n24 -0.3 %;
cells proven losing on CRYPTO_ALT: T1F0, T1F1, T1FU, T2F0, T2F1, T3FU; nothing backing. A reclaim after a down day had no cell of
its own: CryptoTrend8886's band put it in T1 / T2 (mixed) with every other mixed reading, and T1F1 is proven losing (n49);
T0F1 (all down, buyers leading) has 3 closes. So nothing in the bot's data says a reversal buy pays yet.

`CryptoTrend8886.reversal8909`: 24 h read and below zero, 5 m and 1 h read and above zero → band TR (cells TRF1 / TRF0 / TRFU).
The twin files every crypto close under lane|cell as before, so TR is graded from today's moves forward; it backs a candidate
only by its own record (cellBacks8886: the lane rule and the mean above the family-deflated margin) and withdraws backing when
proven losing (cellLosing8886), like every cell. Forensic impact: a candidate that read T1 / T2 with that shape now reads TR; the
T1 / T2 records keep their history and grade only the rest from here; nothing is bought until TR's own twin record backs it.

## 98. The majors playbook, strategy records, and the base graded on its exits (8910)

Owner, 21 Nov: "all 5 now please one build. again meme and crypto trader are choked ... we are now ignoring pumpfun completely
which is wrong and the crypto trader should be seeing alot better trades same as the meme trader".

**What the 21 Nov rows say (26.6 h, 34,641 research rows, each coin once at its worst meme-lane hour read).** Graded the way
the lane actually exits — the lane stop if the hour's low reached it (taken first, the order worst for the coin), else the
doctrine line from a +10 % held peak if the hour ended under it, else the hour:
- off pump.fun, BASE_START, $20k-$100k pool: n41 +17.8 % ±5.7, lower bound +6.5 % (stop 10 %; +5.9 % at 8 %, +6.6 % at 15 %);
  both halves positive (+13.2 / +22.2). Held to the hour (the 8904 record) it was +13.3 % ±8.6, lower -3.9 %: unproven.
- pump.fun, every cut: all n1798 -6.1 %, admitted n199 -6.9 %, BASE_START n26 -3.4 %, FRESH_LAUNCH n108 -5.2 %, GE100K n80
  -3.5 %. With an optimistic order (target first) the best pump.fun cut is still -1.3 %. The refusals of pump.fun coins on
  these rows protected money; nothing here admits pump.fun coins today.

**Meme (BasePocket8904).** The record files each hour row at `doctrineNet8910` (FieldManual7715.stopPctQuiet8830 at the row's
cost; RunnerDoctrine8584.paperStopLevel8584), keeps the hold read beside it (`hold8910{}` in the brief), and the same base on
pump.fun is its own record (`BASE_PUMP_20K_100K`, `pump8910{}`), filed and proven by the same rule and carried as the same
market-cell evidence once proven. Forensic impact: the off-pump.fun record goes from unproven to proven on the owner's rows, so
coins in the pocket that the native lanes already admit (SHITCOIN / MOONSHOT / EXPRESS / CORE: 127 of 289 pocket lane rows were
NATIVE_READY) carry proven evidence that answers the lane-wide priors and the aggregate forecast (EDGE_MODEL_NEGATIVE); hard
safety, sellability, the LP-lock rule, executable economics and the setup's own losing record still outrank it. The record is
seeded from the device's research file, so it proves or fails on the device's own coins.

**Crypto.** The data hole first: every PerpsMarketData producer synthesised the 24 h range and returned funding / OI as 0.0.
`MajorTape8910` reads the majors' real hourly candles (data-api.binance.vision klines: OHLC, volume, taker-buy volume; every 5
min, background thread, closed candles only) and, only while a tape shows a flush in its last 6 h, funding and the 4 h OI change
(Bybit v5, then OKX v5 public data; unobserved stays null). `MajorSetups8910` (pure) reads five setups on it: capitulation
reclaim (flush >= -1.5 % on >= 2x median volume, day >= 3 % off its high, higher low, reclaim of the last hour's high or the
day's VWAP, buyers leading; stop under the flush low, target the breakdown level), funding / OI-flush reversal (the same with
funding <= -0.01 % or OI -4 %), range low reclaim (48 h range >= 4 %, bottom quarter, higher low; target the middle), breakout
retest (close above the prior 48 h high in the last 6 h, back within 0.5 %, holding; 2R), and RS rotation (BTC UP / RECLAIM, a
top-3 leader >= +2 % ahead of BTC, green buyer-led hour above VWAP; 2R). Each needs its target >= 2x the round trip (1 % assumed)
and >= 1.2R. A fired setup is a LONG spot candidate (strategy `MAJ8910_<NAME>`, its own stop / target / hold, score and
confidence at the crypto brain's floors) beside the classic analyser's. The crypto twin files every close under
lane|S=<strategy> and, for the brief, @session and @BTC_<regime>; CryptoSpotPaperBacking8788 reads a strategy record exactly
like a trend cell (backs on its own proof, withdraws when proven losing; `effective8910`, equal to `effective8886` with no
strategy read). Forensic impact: new candidates only; in LIVE they are refused on the learned expectancy door until their own
strategy record backs, which opens the twin with the setup's levels; existing desks gain their own strategy records (a desk
proven losing on its own twin record now withdraws the lane backing for that desk only). Network: at most ~36 kline calls per 5
min to Binance's public mirror, derivatives calls only on flushes; no Helius credits.
The majors the SOL perps engine covers (BTC, ETH, BNB, XRP, ADA, DOGE, AVAX, DOT, LINK, LTC, ATOM, UNI, ARB, OP, APT, SUI,
INJ, JUP, NEAR, PYTH, RAY, JTO, TAO) were excluded from the static alt scan, and that engine (PerpsTraderAI) has been retired
since 8797 (RetiredMarkets8797: it opens nothing): no spot major had any path at all. They now get a setup-only pass (spot via their wrapped Solana
mint; SOL itself excluded, the classic analyser not run on them).

## 99. Following a coin through its migration (8911)

Owner, 21 Nov 18:08: "it cant be grading the coins correctly. also do we correctly follow coins off bonding curve to main
curve? meteora to raydium? pumpfun to pumpfun to dex?"

**What was true.** Trading followed a pump.fun graduation (PumpPortal migration event, DEX WS migration and the curve account's
complete flag all set TokenFacts.markGraduated; the sell route leaves the curve; a held coin is priced from its PumpSwap vaults,
GradTicks8018). Grading did not: LabelEvidence8386's 8783 rule refuses a forward-label mark read on another named venue than
the decision's unless the mark is a chain read, and only held coins get chain reads of the new pool. A coin graduating inside
its graded hour stopped being followed at the migration. On the 20-21 Nov research rows (34,641 rows) 294 pump.fun hour reads
and 33 others ended on OBSERVED_ONLY_VENUE_MISMATCH_8783. Meteora DBC (-> DAMM) and Raydium LaunchLab (-> CPMM) had no
migration detector at all: CurveCustody8881 read their migration flags only to refuse a custody proof.

Also measured on the same rows (reported, not changed here): 232 pump.fun hour reads ended on UNCORROBORATED_JUMP_8133 with a
booked mean of +83 % (runs whose bigger prints were refused as unproven jumps), and 35 ended on CURVE_HELD_8337 with a median of
-100 %. Both are the next grading items.

**Change.** `LaunchpadMigration8911`: a venue change is the coin's own migration, and the new pool's mark is the coin's price,
when its graduation is proven (`migrated8911`: the pump.fun graduation set, the row's migratedOrGraduated flag, or a launchpad
read) and the mark is not on the pump.fun curve (a completed curve is no witness, 8165). On a venue refusal for a non-pump.fun
coin whose decision venue is a pool address, that pool's account is read once (keyless public RPC, no Helius credits, one
thread, bounded queue, each pool at most every 15 min): a DBC VirtualPool of the coin with is_migrated set, or a LaunchLab
PoolState of the coin with status 2, sets TokenFacts.markGraduated (both graduation truths, so the router and curve readers
agree too). Every other evidence rule stands: identity, units, decimals, time order; a <= 0.1x move on another venue still needs
a chain read; a >= 10x move still needs both marks chain-witnessed. The rule lives in LabelEvidence8386.status, so the forward
labels, the runner study and the Cortex grade book read migrations the same way.

**Forensic impact.** Only marks that were refused as a venue mismatch change, and only for coins with a proven graduation: their
hour (and 4 h / 24 h) reads now follow the coin on its new pool instead of stopping at the migration. Records built on those
reads (stage, cohort, pocket, lane) grade graduated coins on what they did after graduating. Nothing is admitted by this change
itself. Counted as `migration8911[carried= asked= found{DBC=,LAUNCHLAB=}]` in the brief.

## 100. Grading truth: the dead curve and the chain-read step (8912)

Owner, 21 Nov 18:16: "fix the other issues". The two grading defects §99 measured on the 20-21 Nov research rows:

**The dead curve (35 hour reads, median -100 %).** Example HF6woTT9: decided at a $151k-$211k cap (past anything pump.fun's curve
can quote: it completes near 85 real SOL, ~$53k at SOL $130), one accepted mark, -98 %, while the market's own price (OFFWATCH)
was held by CURVE_HELD_8337 because a curve read disagreed with it. A curve read of a coin that has left its curve is not its
price. Change: `LaunchpadMigration8911.offCurve8912` (pump.fun's own invariant, CurveReserves7951.realSolFromPrice8418 > 85.02 x 1.05);
a curve-family mark (`curveMark8912`: venue pump.curve, CURVE_ACCOUNT, CURVE_FRAME, PUMP_CURVE_RPC, PUMP_PORTAL_TRADE_WS) of a
pump.fun coin whose decision price is past completion, or whose graduation is proven, is refused as DEAD_CURVE_8912 before it can
book, contradict or hold; and the curve witness (curveWitnessAt8337) does not hold or contradict a mark that is itself past
completion (it is a pool's price).

**The unproven jump (232 hour reads, booked mean +83 %).** PositionDisplayTruth8078.highStepOk8133 refuses a step that more than
doubles the multiple past +100 % unless a two-feed aggregator price agrees within 25 %; on the pump.fun curve the aggregators lag
or have no pair, so real runs were clipped at their last step. Change, in the forward labeler only: a mark that is a chain read
of the coin's own price (LabelEvidence8386.independent8418: curve account / curve print / PumpPortal trade print / pool vaults /
parsed swap — an executed price) is its own corroboration. A >= 10x move still needs both marks chain-witnessed; an aggregator's
step still needs the agreement; position highs and exits are unchanged.

**Forensic impact.** Hour / 4 h / 24 h reads of pump.fun coins no longer book dead-curve prices or lose their chain-printed run
steps; records built on them (stage, cohort, pocket, lane, the base pocket's pump.fun record) re-grade from new rows. Counted as
FORWARD_LABEL_DEAD_CURVE_8912 / FORWARD_LABEL_CHAIN_STEP_8912 and `deadCurve8912=` in the migration8911 line. Nothing is
admitted by this change itself.

## 101. 8911's compile fix (8913)

8911 and 8912 did not build: the cortex JVM harness (ci/cortex-jvm/run.sh) compiles LabelEvidence8386 with 43 other pure files
and no Android, and 8911 had LabelEvidence8386 call LaunchpadMigration8911 (which reads BotService, the RPC client and the curve
store). LabelEvidence8386 is pure again: the carry rule (`migrationCarries8911`) lives there, and the live migration read is a hook
(`migrationHook8911`) that LaunchpadMigration8911 installs on first use and that ForwardReturnLabeler7731.tick installs every
pass. Unset, nothing is carried (the 8783 rule as before). Behaviour is otherwise exactly §99 and §100.

## 102. 8913 rebuild (8914)

8913's build stopped before compiling: the cortex JVM harness's compiler download (kotlin-compiler-embeddable-1.9.24.jar) returned
HTTP 404 from the Maven mirror on that run (the same URL served 8911's run eight minutes earlier). No code change; CI reruns are
not permitted, so this version is the rebuild.

## 103. Learn what the lane trades (8915)

Owner, 21 Nov 18:25: "fix all 12. bundle together as makes sense please" (the twelve defects listed at 18:24). This bundle: learning
truth and the build itself.

- **CI (8913 and 8914 never compiled):** Maven Central's CDN answered 404 for kotlin-compiler-embeddable-1.9.24.jar twice in a row
  in ci/cortex-jvm/run.sh. The fetch now tries repo.maven.apache.org, repo1.maven.org, then Google's Maven Central mirror; the
  zip test still verifies the jar.
- **The edge model learned the wrong outcome (defect 1).** EdgeModel8537 learned a meme decision's held net, while the lanes sell at
  their stop and on the doctrine line; on the 20-21 Nov rows the coins the lanes admitted off pump.fun read
  +9.1 % (n86, lower bound +2.5 %) under the exits. ForwardReturnLabeler7731.edgeTarget8915: a meme lane's label is learned at
  BasePocket8904.doctrineNet8910 over the observation's own low and held peak (stop first: the order worst for the coin); every
  other book (setup, stage, cohort, lane records, research rows) keeps the held net; crypto lanes learn the held net. Forensic
  impact: the model retrains forward (its own forgetting); EDGE_MODEL_NEGATIVE then refuses what loses as traded. Brief line
  `edgeTarget8915[memeLabels= changedByExits=]`.
- **The runners grading dropped (defect 3).** An unwitnessed 10x mark deleted its whole observation unbooked (and told Cortex
  BASIS-lost). Now only the mark is skipped: the observation stays pending, a chain-witnessed mark books the run (8912), and with
  none it expires on its last accepted in-window mark. A real basis error still never books. Counted `unwitnessed10xKept=`.
- **Memory (defect 7, one cause).** CryptoTwin8883 rewrote every record into SharedPreferences on every close; with 8910's
  strategy / session / regime records that is up to ~250 strings per close through QueuedWork (the write-back queue the 3.5 h
  OutOfMemoryError died in). It now writes only the records the close changed.

## 104. 8910's three test fixes (8916)

8910 compiled; three unit tests failed, all in the tests, not the change: Aate8806 and Aate8857 pin the exact backing call lines
that 8910 extended with the candidate's strategy (now `availability8870(lane, nowMs, cell, strategy)` and the contract's
`candidate.evidence["trend8886"], candidate.evidence["strategy7803"])`); Aate8910's kline sample started at open time 0, which
parseKlines8910 rightly refuses as no candle. 8911-8915 carried the same failures (and 8911-8914 never compiled: §101, §103), so
8916 is the first build of the whole chain.

## 105. 8916's test fix (8917)

8916 built; one unit test failed: Aate8187CortexCompleteTest pinned the labeler's `Cortex7885.onLabelLost8187(... "BASIS" ...)` call,
which 8915 removed on purpose (§103: an unwitnessed 10x mark is skipped, its observation kept; it books on a witnessed mark or ends
through the durable unavailable terminal, which still releases Cortex). The pin now asserts the kept observation.

## 106. The send judges the move (8918)

Owner, 21 Nov 23:47 (report 23:11 on 5.0.8917, 24 min up): "still very very choked ... it was making money yesterday".

**What the reports say.** Admissions per hour of uptime: 8894 (20 Nov 20:53) 47/h, 8898 (00:05) 21/h, 8898 (01:08) 14/h, 8900
14/h, 8903 (13:48) 17/h, **8917 208/h** (84 in 24 min). The live record is still profitable (n21, PF 3.13, +0.0054 SOL/trade).
The choke on 8917 is downstream of admission: FDG allowed 8, the executor made 4 buy attempts, 2 filled. Of the failed live
buys (§8726 list): ENTRY_CHASE_DECISION_STALE x3 (DPRK, price 1-5 s old; the 45 s LIVE ticket had expired),
ENTRY_CHASE_CONSUMED x3 (decision-to-send latency 11.5-19.7 s; MAYA expected 5.68 % cost 3.03 % moved 1.59 %; PUNANI 6.98 /
3.43 / 1.86 %; DPRK SHITCOIN 19.11 % expected against 22.80 % cost), one safety hard block, one unproven LP proof. The base
pocket (PROVEN n73 +31.9 % ±5.6 lower +20.7 % under the lane's exits; +22.9 % lower +8.8 % even held to the hour) carried 34
coins: 2 admitted; DATA_DEFER_FALLING_HOUR_8418 deferred 8, EDGE_MODEL_NEGATIVE 8, SAFETY_REFUSED 9, safety report not fresh 5.

**Changes.**
- EntryEconomics8329.preBroadcastRefusal8329: a LIVE ticket past its 45 s life is still sent within `LATE_SEND_GRACE_MS_8918`
  (75 s more) when the decision's mark is fresh; the re-quote check that follows measures the move since the decision against
  the fresh executable price, which is the staleness that matters.
- EntryEconomics8329.consumed8329: when the expected move would not clear the margin even unmoved, the move did not consume it
  (the admission's EXECUTION_ECONOMICS_8136 approved those economics on its own proof); the send then refuses only when what
  remains after the move is at or under the round trip. A true chase (cleared unmoved, consumed by the move), an exhausted
  rally, a move over the cap and a cost above the expected move are refused as before.
- CandidateAdmission8136: a proven market cell (8723 chain: the base pocket, the agreement pocket) answers the UNPROVEN
  falling-hour wait (its record holds coins decided in every hour) with its own after-cost lower bound as the move, as the 8171
  fallback does for a proven setup; a falling hour PROVEN losing still refuses.
Counted: `sendBoundary8918[lateSent= admissionEconomicsStood=]`, FALLING_HOUR_ANSWERED_BY_MARKET_CELL_8918_<lane>.

## 107. Where the seconds go (8919)

Owner, 22 Nov 00:05: "fix it!!!" — the 11-20 s between a decision's mark and the live send (8917's EDGE_CONSUMED buys moved
1.6-1.9 % in that time). The chase detail only measured mark -> send. The CPU sample (23:11, 31.5 s) shows AATE-Entry-6647 at
15.2 s of thread time inside processTokenCycle (SpecialistBrainBridge7542.evaluate 6.1 s, ToolkitSignalSheet 7.3 s,
LanePlaybook7907.selectedSetup8094 5.0 s), and the decision price ages (decisionPriceAge8549) put about half of MOONSHOT's
decisions on a mark older than 5 s. Each send now files the two parts — mark -> seal (the decision made on an old price) and
seal -> send (the ticket waiting for the executor) — as p50/p90 over the last 64 sends in `sendBoundary8918[markToSeal8919=
sealToSend8919=]`, so the next change is aimed at whichever part is slow instead of guessed. 8918's send-boundary fixes (late
ticket grace, the send judging the move) are in this build.

## 108. 8919's test fix (8920)

Aate8918's quantile sample expected the 3rd of ten values as the median; quantiles8919 takes index (n-1) x p (the 5th: 5.0 s).
The test now expects "5.0/9.0s". No code change.

## 109. The base pays from five thousand (8921)

Owner, 22 Nov 00:13, with the live journal: "why are we [not] repeating these big wins?!" The journal (19-21 Nov): 47 decisive
live sells, +0.056 SOL net — and one coin made all of it: DQKmfaSk (SHITCOIN, 20 Nov 11:03 Brisbane, off pump.fun, decided at
BASE_START, $129k cap on a $3.9k pool), +0.164 SOL over three SPIKE_CAPTURE_7943 partials (T1 +102 %, T2 +402 %, T3 +1,114 %)
and the close (+356 %). The other 46 sells: -0.108 SOL. The lanes' many small stops are the bleed; the runner is the edge.

Where that runner lives, on the 20-21 Nov rows (each coin once at its worst meme-lane hour read under the lane's exits, stop
first): off-pump.fun BASE_START by pool — $5k-$20k n26 +29.8 % ±7.7 lower +14.3 % (halves +25.5 / +34.0; held to the hour
+15.3 % ±12.0); $20k-$100k n60 +26.2 % lower +16.6 %; under $5k n39 +0.9 % (DQK's own pool size, which is not where it pays —
the stage is). By cap: under $30k n80 +27.5 % lower +18.8 %; $100k-$300k n29 +1.6 %.

Change: BasePocket8904's pool floor is $5k (was $20k); the record ids are BASE_OTHER_5K_100K / BASE_PUMP_5K_100K. The base watch
(both stale demotions skip a coin that could base into the pocket) follows the same floor. Everything else in §92 / §98 stands:
proof at 30+ coins and mean - 2 SE > 0, the lane's exits, and safety, sellability, the LP-lock rule and executable economics
outranking it.

## 110. 8918's test fix (8922)

8918 built; Aate8418EntryContinuityTest pinned a ticket expiring at the send instant as ENTRY_CHASE_DECISION_STALE, which 8918
changed on purpose (§106: a re-quoted grace of LATE_SEND_GRACE_MS_8918). The test now expects stale past the grace and the
re-quote inside it. 8919-8921 carried the same failure; 8922 is the first build of §106-§109.

## 111. A price no pool can hold is no basis (8923)

Owner, 22 Nov 00:31-00:35: "what about coins that ran 1000% plus?", then "do 1 and 2 as well" (1: this fix; 2: study the real
launch runners).

**1. The bad basis (defect 10).** On the 20-22 Nov research rows 58 coins read 4x+ after the bot's first decision; 15 were
decided at a cap under $1.5k (as low as $3-$80, pools $0-$9: no live pump.fun curve quotes under ~$2k at SOL $70), and others
read +73,000 % to +192,000 % at 4 h / 24 h (a $2k coin at $400M). The decision price was not the coin's, and the Jupiter re-read
against it booked a run that never happened (the same inflates the report's "runners missed"). Change: a decision on a pool
under $100 (the bot's own minimum to buy) writes no hour research row and is not followed at 4 h / 24 h
(`untradeable8923`); a follow-up re-read past 100x the decision price is booked unpriced as IMPLAUSIBLE_RATIO_8923
(`implausible8923`), never as a return. Counted in the researchFollow8884 line.

**2. The real launch runners (study, no code).** Fresh pump.fun decisions at a $1.5k-$10k cap on a pool of $100+, best read
4x-100x within 24 h: 12 of 495 (2.4 %). At the decision they look like the rest: median V3 0 vs 0, edge forecast -15.1 % vs
-16.3 %, cap $3,954 vs $3,321, pool $842 vs $676, cost 2.9 % vs 3.0 %; no setup id fires on them more often (FRESH_LAUNCH_MOMENTUM
1/16, PEAK_RECLAIM 1/17, NO_TRIGGER 6/122); lane-admitted 7/228 (3.1 %) vs not admitted 5/267 (1.9 %). On 878 launch-cap
decisions, buying every one with a -25 % stop and banking half the best run is -0.2 % ± 3.6 a trade. Nothing the research rows
record at the decision tells a launch runner from the rest; the rows carry no buyer-flow fields (distinct buyers, buy share,
signed demand), which is the next data hole to fill before a launch-runner setup can be graded.

## 112. All five (8924)

Owner, 22 Nov 00:39: "do all 5 now". One build, five changes, each additive (nothing is gated harder; nothing is removed).

**1. Buyer flow on the research rows (the hole §111 named).** Each labeller observation reads `BuyerFlow8666.read(mint)` at the
decision and the hour row carries it as `flow8924{buyers60,new15,perMin,buyShare,netSol60,buyTx15,buyTxPrev15,repeat60,txPerMin,
accel,sustained}` (`ResearchRows8877.flowJson8924`). A coin with no prints writes no field. This is what a launch-runner setup can
be graded on next; no decision reads it yet.

**2. The stranded paper basis (bug 12).** A terminal paper sale refused by the quarantine (no phantom credit, 6502) left its
basis in the ledger's openCost while the canonical position went terminal and the journal booked the loss (21 Nov 23:11:
openCost 0.0064 against a replay of 0). The refused sale is remembered (position, basis, why); once that position is QUARANTINED
or CLOSED and the refused basis is exactly the ledger's open cost (within 1e-6, no receipt unpaired), the basis is written off as
a realised loss, openCost and realised together, so the identity holds and nothing is credited
(`PaperAccountLedger6430.sweepStranded8924`, run on the bridge's refusal and on each coalesced economic pass). Any other shape
is reported in `Stranded basis (§8924)` and kept. Paper only; the live book is untouched.

**3. The crypto hour cap, graded (bug 4).** ADAPTIVE_HOLD_MAX was the twin's most common exit (40 of ~90). Each hold-max twin
close is marked again an hour later with the twin's own mark; the pair (pnl at the cap, pnl an hour on) is filed per lane and
persisted (`crypto_twin_hold_ext_8924`). Once a lane's extra hour is proven (30+ pairs, mean gain - 2 SE > 0,
`CryptoTwin8883.holdExtends8924`) the desk's cap for that lane gains the hour (at most two hours); a setup's own hold (8910) is
unchanged. Shown in `holdCap8924[...]`.

**4. Memory and timeouts (bugs 7, 8).** A Cortex seat's above/below split is only filled when the voter is scored on it; the
regime and all-lane seats (26,352 of 50,719 on 21 Nov 23:11) carried two empty Stats each. The sides are made on first write; a
read of an unmade side reads an empty book, and the stored string of an unmade side is the empty book's, so persisted, restored
and detached books read exactly as before (census: `sidesMade8924=made/possible`). The scanner's 339 source timeouts and 110
breaker trips in 24 minutes named no source; each source now files its timeouts, completed-run mean and slowest durations, trips
and skips (`sourceTimeouts8924[...]`). Telemetry only: no source is timed or skipped differently.

**5. Lost labels and shadow outcomes (bugs 2, 11).** MARK_FANOUT_RETRYABLE_SATURATION_8527 ran 13,804 in 24 minutes: price
passes found the background mark pool's 24 / 36 queue full and the rejected jobs' coins went unpriced (labels and shadow marks
lost). The workers stay 12 / 12; the queue holds 96 / 144 jobs, and a queued job past its pass's deadline still never reaches a
provider (`task.scope.remainingMs()` before the call).

## 113. The LP lock read on chain: DAMM v2 vesting, Raydium CPMM (8925)

Owner, 22 Nov 01:06-01:11: "why aren't we simply reading the data we need on chain?", "rug check is good yeah?", "go".

**Why.** On 21 Nov 23:11 the paying-cell coins the buy door left waiting on an unread LP proof paid +33.7 % (n94; +28.7 % and
+36.5 % in the two reports before), the proven ones -0.1 %. PoolLock8895 (§84) read 13 pools: every DAMM v2 pool UNLOCKED (8)
and 5 of programs it did not read.

**DAMM v2 vesting.** Meteora DBC graduates into DAMM v2 with `permanent_lock_position` and `lock_inner_position`
(dynamic-bonding-curve migrate_damm_v2_initialize_pool.rs): a config's vested share is a schedule on the position. 8895 counted
only the permanent part. When the permanent share is under 70 %, the pool's positions are read (getProgramAccounts on the DAMM v2
program, Position discriminator, pool @8; damm-v2 state/position.rs: vested_liquidity @168, permanent_locked_liquidity @184,
inner_vesting @312) and, for a position whose vested liquidity its own schedule does not explain, its external Vesting accounts
(position @8, inner_vesting @40; at most 4). What a schedule holds at a point is its total less InnerVesting::
get_max_unlocked_liquidity; the point is the pool's own clock (activation_type @480) 24 h past now, as DLMM locks are read.
Locked share = (each position's permanent + what its schedules hold then, at most its remaining vested liquidity) / pool
liquidity. Schedules are cached 5 minutes per pool (Helius getProgramAccounts is 10 credits).

**Raydium CPMM** (where Raydium LaunchLab graduates). LP is burned (an SPL burn lowers the LP mint's supply; PoolState.lp_supply
does not move) and LaunchLab's platform share is locked through Burn & Earn, held in the LP mint's token account of the lock
authority 3f7GcQFG397GAaEnv51zR6tsTVihYRydnydDD1cXekxH (raydium-sdk-V2 makeCpmmLockInstruction: lockLpVault =
ATA(lockAuthProgram, lpMint); a Burn & Earn lock has no withdraw). PoolState (packed, LEN 637): lp_mint @136, mints @168 / @200,
lp_supply u64 @333. Locked share = ((lp_supply - LP mint supply) + LP the lock authority holds among the LP mint's 20 largest
accounts) / lp_supply. Other lockers are not counted. The burned + locked amount only grows, so it is cached 5 minutes and read
against a fresh lp_supply.

**Unchanged.** >= 70 % turns an unread LP proof into PROVEN and nothing else; under 70 % or unreadable stays unproven and is not
bought; a provider finding of unlocked liquidity still refuses; RugCheck stays a required proof. Raydium AMM v4, CLMM and Orca
pools are not read yet (their LP lock still comes from RugCheck alone).

**With 8924's test fixes.** 8924 built and passed the runtime smoke; two tests pinned lines it changed on purpose:
Aate8891LabelStallTest (the hour row now passes `o.flow8924`, §112.1) and Aate8905BaseWatchCensusTest (the census now ends
`sidesMade8924=made/possible`, §112.4). Both now expect the new lines. 8925 is the first build carrying §112 that can go green.

## 114. Liquidity pulled, we leave (8926)

Owner, 22 Nov 01:39-02:14: "take the good ideas and create it as our own source code version", "go. if it gets liq pulled
im going to be very pissed of", then "its been liq pulled 3 times".

**The LP-pull exit (LpPull8926).** Nothing read a held coin's pool for liquidity leaving; a rug showed only later, as a price or
depth collapse. A pool's liquidity units move only on an add or a withdrawal, never on a swap: Meteora DAMM v2 Pool.liquidity
(u128 @360), Raydium CPMM PoolState.lp_supply (u64 @333), Raydium AMM v4 AmmInfo.lp_amount (u64 @720, 752-byte packed account,
raydium-amm state.rs). Each held position's pool (tokenMap pool, else the price pool, else the pair) is read every 10 s
(getAccountInfo, keyless first); the most units seen since watching began is the baseline. 30 % or more below it is liquidity
pulled: BotService sells the whole position at once with `LP_PULL_LIQ_DRAIN_STOP_8926_DROP<n>PCT`, which ProtectiveExitClass7807
classes as a structural emergency (2 s retry rung, never held by a plan, runner or minimum-hold rule). A 5 %+ withdrawal is
counted and logged (LP_WITHDRAWAL_8926), never acted on below 30 %. A pool of another program (a pump.fun curve, DLMM, CLMM,
Orca) is not watched and is re-asked after 30 minutes. Brief: `lpPull8926[...]` (and the counters LP_PULL_DETECTED_8926 /
LP_PULL_EXIT_ACTED_8926).

**Raydium AMM v4's lock.** PoolLock8895 reads a v4 pool's burned LP: (lp_amount - LP mint supply) / lp_amount, the same proof
as CPMM's. 70 % still proves; less stays unproven.

**Who bought is who paid (BundleDetector).** Each Helius token transfer was an acquisition by its receiver and a disposal by
its sender, and the first block's totals summed both, so the bonding curve or pool paying buyers out read as the first block's
biggest buyer, and a creator moving supply to a second wallet read as a buy. A transaction's balance changes now say it
(idea: luizdcastro/bundled-core's signer-net buys, MIT; our own code on Helius's parsed rows): its fee payer and every account
its SOL (or wrapped SOL) put the coin into bought, when the payer itself lost none of it; the payer's coin falling while its
SOL rose is a sale; coin leaving the payer without SOL back is a transfer, neither. First-block totals count acquisitions only.

**The crypto desk's RSI and MACD, textbook (owner 02:16: "do tbe rsi work as well").** PerpsAdvancedAI's RSI was the plain mean
of the last 14 changes (Cutler's oscillator); it is now Wilder's (averages seeded on the first 14 changes, smoothed over every
later one: `wilderAverages8926`), with its guards unchanged (50 under 28 points, 50 when flat, the one-directional ramp to
95 / 5, the 5-95 clamp). Its MACD had no signal line (the comment said "simplified") and called a zero-line cross a signal
cross; it is now MACD(12, 26) against its 9-period signal line (`macdSignal8926`): a cross is the MACD line crossing the signal
line on the last point, BULLISH / BEARISH its side of it; with 26-33 points (no signal line yet) the MACD line's side of zero,
never a cross. The idea's reference is ta4j / TA-Lib; the code is ours. The tick series it runs on is unchanged.

**The stranded sweep off the caller's thread.** 8924's paper stranded-basis sweep (§112.2) ran inline in the economic publish
and the canonical bridge, taking the ledger monitor from inside their work; it now runs on its own single thread
(`requestSweep8926`) and returns at once when nothing is refused.

## 115. 8926's compile fix (8927)

8926 did not compile: BundleDetector's `rows8926(tx, mint)?.let { results += it; continue }` uses `continue` inside an inline
lambda, experimental on Kotlin 1.9. It is a plain null check now; nothing else changed. 8927 is the first build of §114.

## 116. The pocket, carefully (8928)

Owner, 22 Nov 02:47-02:52: "bro you have so much data ... you can't build it?", then on the two options: "1 yup 2 it has to pass
10k - 15k and actually show forward volume holder growth and more positive signs than negative. and if it does buy maybe be
very very careful".

**The data.** 20-22 Nov research rows (three exports, 38.9 h, 1,827 coins, one row per coin at its first decision, pool $100+):
an exhaustive search of every one-, two- and three-feature cell (origin, cap, pool, stage, V3, forecast, pool/cap, lane,
admitted), kept only when mean - 2 SE > 0 and both time halves positive, puts the same pocket at the top every time: not
pump.fun, $5k-$20k, forecast +10 % or more (n108, +40.5 % +-7.5 at the hour; with a 25 % stop +46.3 %). By cap: $5k-$10k n22
+42.2 % with 4 pulled (-60 % or worse); $10k-$15k n29 +43.0 %, 1 pulled; $15k-$20k n56 +30.3 %, 4 pulled; $20k-$30k n21
+18.5 % (not proven). It is AgreementPocket8874's rule (§65); 59 of its coins were lane-ready and none was bought: they wait
on an LP proof that does not come (lpState8725 PENDING n114 +31.6 %).

**1 (yes).** The 70 % rule stands; 8925 / 8927 prove the lock on chain (DAMM v2 incl. vesting, CPMM, AMM v4).

**2 (ExploreLive8733 back on, for this pocket only).** An UNREAD LP (never one reported unlocked or under the lock: those are
BLOCKED before this) may be bought when all of these hold:
- the agreement pocket at a cap of $10k (CAP_LO_USD_8928) up to its $20k ceiling, in a lane whose pocket record (or the coin
  record) is proven, on that lane's own quiet forecast (`AgreementPocket8874.provenLaneFor8928`);
- RugCheck has REPORTED: the explore path no longer stands in for it (PreTradeHardGate's chain substitute takes only a PROVEN
  LP), and a report's findings (LP unlocked, single holder, top-10, unverified ...) refuse upstream as before;
- a classic SPL Token mint (OnChainMintAuthorityProof7248 `classicToken8928`: owner Tokenkeg, no Token-2022 extension can
  exist), mint and freeze revoked; every Jupiter buy still proves its exact tokens quote back to SOL first (8736);
- forward flow and holders (`flowPasses8928`): buying rising (buy transactions this 15 s over the 15 s before, or volume
  acceleration >= 55), holder count growing, and more positive signs than negative (`signs8928`: transactions rising /
  falling, acceleration >= 55 / <= 45, buy share >= 55 % / <= 45 %, net SOL in the minute + / -, new buyers in 15 s >= 2 / 0,
  holders growing / shrinking, sustained demand);
- one explore position at a time, 6 a day, at most 0.05 SOL each; the LP-pull exit (§114) watches it from its first tick.
The brief's `explore8733[... asked{...}]` names why each look was refused (BUDGET / NOT_IN_POCKET / NOT_CLASSIC_OR_AUTHORITY /
FLOW_OR_HOLDERS) or ELIGIBLE.

## 117. The tape is down (8929)

Owner, 22 Nov 04:12-04:16: "still choked out to shit mate. paper traders hitting 1000% trades again tho. so why isn't the meme
and crypto teaders. I reset learning to clear bad learnt data", then "theres no reason it cant be trading raydium x stocks",
"we are actually forensically calculating the journal data with chain backed proof at time of buy and sells? partials and top
up included".

**What the 04:08 capture (5.0.8928, 21 min up) showed.**
- Helius: every call "max usage reached", the socket refused 429 (chainLaunch8179, Helius WS subs serverTokenSubs=0, DAS 125/125
  failed). The PumpPortal trade stream is off (paid per frame, §8354). So no executed print reached the bot (tape8285
  executed=0, buyerFlow8790 accepted=0, emptyReads=268,660, memeFamilies funnel flow=known:0/unknown:5,596): every flow signal
  was blind. An account matter (credits / key), not code.
- The learning reset's line made the dataset replay refuse all 101,408 rows (datasetSeed8863 parsed=0); the agreement pocket
  restarted at zero and 8928's path refused all 119 looks as NOT_IN_POCKET.
- Paper (meme, every candidate the paper way, §8618): 5,640 closes, 44 runs of +100 % or more, net -21.3 SOL. The crypto paper
  "1000 %" rows were JOURNAL_REBUILT_FROM_RECEIPT_7360 rows whose proceeds did not belong to the lot (7GCihg 0.0171 SOL basis
  -> 0.218 back; CTg3Zg 0.0123 -> 0.165; 89dGsH 0.0303 -> 0.390), and the CRYPTO_SPOT paper record those rows carried
  (n202, +25.2 %/trade) is the record CryptoSpotPaperBacking8788 reads while the twin's CRYPTO_SPOT (n39, -0.6 %) is too short.
- xStocks (XsDesk, §8802, 24/7, no hours): quotes ok=0 fail=35 on the observation Jupiter client, so every stock was charged the
  2 % unmeasured placeholder per side plus the 0.5 % app fee; every family graded losing and the desk stayed BRAIN_NOT_ARMED.

**Changes.**
- `AgreementPocket8874` research coin record (`research8929`): each pocket coin's hour research row (ResearchRows8877, which a
  learning reset leaves), once per coin at its worst meme-lane read within 15 min of its first, last 200 coins; seeded from the
  file, filed from every row written. On 20-21 Nov rows: 62 coins +40.8 % +-8.1 (lower bound +24.7 %). It serves only
  `provenLaneFor8928` (the careful path); the full-size admission (`evidence8874`) is unchanged.
- `ExploreLive8733`: with no BuyerFlow read the pool's own DexScreener counts decide (`pairFlowPasses8929`: an answer at most
  120 s old; buys in the last 5 min above the pool's own earlier rate, h1 less m5 over min(age, 60) - 5 min; holders growing;
  more positive signs than negative over transactions, buys, 5-min buy share, 5-min move, holders). A pool under 10 min old, or
  one with no served creation time, never passes on counts. asked{} names NO_TAPE_NO_FRESH_POOL_COUNTS /
  NO_TAPE_POOL_TOO_YOUNG_FOR_COUNTS / POOL_COUNTS_OR_HOLDERS / PASSED_ON_POOL_COUNTS_8929.
- `JournalEconomicReplay6619` receipt rebuild: rebuilt only when the unjournaled receipts sell exactly the open lot (raw tokens
  equal, allocated cost within 1e-9 SOL or 0.01 %, `receiptsMatchLot8929`); otherwise counted
  JOURNAL_RECEIPT_REBUILD_SKIPPED_7360_RECEIPT_LOT_MISMATCH_8929 and left as it is.
- `LearningPnlSanitizer.inspectTrade`: a JOURNAL_REBUILT_FROM_RECEIPT_7360 row is refused as RECEIPT_REBUILD_NOT_EVIDENCE_8929
  for every record and learner (StrategyTelemetry leaderboard, so the CRYPTO_SPOT paper backing record; canonical learning;
  compounding); the accounting check (`inspectAccountingTrade8167`) keeps it, so cash is unchanged.
- `XsApi.roundTrip`: a failed observation round trip is asked once more on the trade-grade Jupiter client
  (`retriedOnTradeClient8929=` on the xstocks8802 line).

**The journal (owner's question).** Live rows are written from the finalised transaction (proof=LIVE_FINALIZED, the signature,
consumedRaw / soldBasis / grossOut; 58 chain-proven rows kept, ForensicReconciler6377 11/11, journal coverage confirmed=1
journaled=1). Partials carry their own consumed raw and allocated basis; top-ups extend the canonical lot (CanonicalPositionAuthority6441
§TOP_UP_ATOMICITY). The hole was the paper receipt rebuild above.

## 118. Shares before their tokens; the hive counted (8930)

Owner, 22 Nov 04:53, on 8929's list: "1 fixed. 2 ok it takes time cool. but we also should have a huge database of trade
data in the hive it could build edge off it the data is clean. 3 ok. 4 fix. 5 ok".

**xStocks (4).** XsDesk grades on a 420-day panel, first half train, second half test (HlBrain.evaluate). The token's history
is GeckoTerminal's, and its free API returns about the last six months of daily bars (CoinGecko changelog "extended
historical data for onchain OHLCV": older is paid). The train half was empty: on the 04:08 capture every family read
`train{n210d +0.000±0.000%/d ... turn0.00}`, no family earned in training, the blend stayed empty and the desk could not arm
whatever its costs. `XsUnderlying8930`: an xStock is its share held 1:1, so before the token's first own bar the panel takes
the share's split- and dividend-adjusted daily closes (Yahoo chart v8, range 2y, read at most once a day per ticker), set on
the token's price level by the median token/share close ratio over their common days, only when there are 30+ common days and
90 % of them lie within 2 % of that median (else NOT_TRACKING / SHORT_OVERLAP and the token's own bars alone). Spliced days
carry no volume, a day the exchange was shut carries the last close, and the stored token history (HlStore) is never changed:
the splice is the panel's copy (`XsDesk.panelHistory8930`), stocks only. Listing still needs 60 of the token's own days.
`underlying8930[asked answered failed {SPLICED / NOT_TRACKING / SHORT_OVERLAP / NO_UNDERLYING / OWN_COVERS}]` on the
xstocks8802 line. With 8929's trade-client quote retry, the cost each family is charged is the measured round trip again.

**The hive (2).** `CollectiveLearning.hiveCensus8930`, hourly in the hive sync, read-only, on the brief as `hiveCensus8930[...]`:
collective_trades (rows, installs, live, sells, rows with a mint, rows with |pnl| > 1000 %, span), its live sells (n, mean,
wins, |pnl| <= 1000 %), and the clean hive ledger hive_edge_8076 (keys, builds, labels, first write). collective_trades rows
carry pnl, hold, source, liquidity bucket and entry score, but no entry state (cap, origin, stage, forecast, flow), and were
written by every build since before the 8028 / 8064 clean-hive resets; the count says how much there is before any edge is
built on it.

## 119. The crypto slot an unfilled intent spent (8931)

Owner, 22 Nov 04:59: "dont abandon the current work. but crypto should be trading with its dual live trade models surely. add
that investigation from the last data sent to the current work. add the meme trader to same checks".

**Crypto (04:08 capture).** Both live paths were open: the CRYPTO_ALT record backs (cryptoSpotPaperBacking8788 CRYPTO_ALT =
twin n400 W118/L190 +2.9 %, +0.109 SOL, pfEdge +2.7; the signal engine 8857 stood 194 on integrity doors). Of 521 CRYPTO_ALT
candidates none was allowed: 248 waited on CRYPTO_PAPER_BACKING_CAPACITY_8870 — the 4-an-hour cap read hourly=4 at 21 minutes
up, with no live crypto position held and none opened (backed=0, liveCloses cryptoEngine=n0). A slot was spent when the intent
was registered (`commit8832`) and held for the hour whatever the intent became (price moved 22, price refresh wait 55, expiry,
wallet lock ...). Now `CryptoSpotPaperBacking8788.intentEnded8930`: an admitted intent that ends before it was ever dispatched
(CanonicalEntryAuthority6551 FAILED / DEFERRED / CANCELLED, or EXPIRED undispatched) gives its slot back
(CRYPTO_BACKING_SLOT_REFUNDED_UNFILLED_8930, `refundedUnfilled8930=` on the line); a confirmed fill keeps it, and so does a
dispatched swap whatever its end (its transaction could still land). Cap 4/hour and the -0.1 SOL stop unchanged. The rest of
the refusals were integrity doors (BIG_LOSER 55, DATA_INCOMPLETE 79, DATA_NEVER_ARRIVED 44) and LIVE_NON_POSITIVE_EXPECTANCY
41 on lanes not backed. 8929's sanitizer change takes the receipt-rebuilt rows out of the CRYPTO_SPOT paper record.

**Meme, the same check (research rows 20-21 Nov, each coin x lane at its first hour read, cells n >= 40, ranked by mean - 2 SE):**
the only refusal whose refused coins pay is LPSTATE8725_PENDING:DEFER_SAFETY_PROOF (n107, +20.3 % +-6.6, +14.7 % without the
best three, 8 runs of +100 %) — the agreement pocket waiting on its LP proof, the path 8925-8929 work on. Every other door's
refused coins lose at the hour (e.g. MOONSHOT NATIVE_MCAP_TOO_HIGH n120 -1.8 %, SHITCOIN NATIVE_TOO_OLD n327 -7.2 %), and the
lane-admitted coins lose too (MOONSHOT n692 -4.7 %, SHITCOIN n390 -4.1 %, CORE n321 -2.1 %). The paper book's 44 runs of +100 %
sit inside 5,640 closes netting -21.3 SOL (§117). With Helius restored the tape, buyer flow and the 9 meme families read again.

## 120. Live shows the live book (8932)

Owner, 22 Nov 05:11, with a screenshot of Treasury Scalps (1.61 SOL lots, 34.042 SOL exposure, beside a 0.29 SOL live wallet):
"all these are paper positions displaying open in the live trader".

**Why.** V5.9.495z17 made every lane card (Treasury, BlueChip, Quality, ShitCoin, Moonshot, the unified list and the lane
count) show the paper book plus the live book, so a mode toggle never hid a held lot. Since 8872 paper does not run in LIVE:
its lots are to be closed out at observed marks, not held. The 04:08 capture: frozenCanonicalPaper=96,
closeOut8872[passes=6 pending=100] — not one closed in 21 minutes, and the line could not say why.

**Changes.**
- `MainActivity.uiBook8932`: in LIVE a card shows the live book only; in PAPER both books (a live lot still held is real money).
  All ten paper+live sites and both Treasury sites go through it; the stop guard's held count still counts both books.
- `LiveNoPaper8184.noteCloseStages8932` (from `BotService.closePaperStopIntent8521`): each close-out pass records how far its
  lots got — requested, projected onto a Solana token the meme executor can sell, held by a markets trader, held by neither
  (no owner), attempted with a fresh observed mark (Executor's 30 s authoritative-mark rule), closed — on the brief as
  `closeOut8872[... lastPass8932{...}]`. The next capture says exactly where the 100 lots stop; the close-out rule itself is
  unchanged (never closed on an unobserved price).

## 121. A flaky slowest-build pin (8933)

8931's CI build failed on `Aate8864NoReaderWaitsTest.aReaderIsNotQueuedBehindAnotherThreadsRebuild` (8864's snapshot cache, no
8931 file involved): the status pin expects the slowest build to be the deliberately held rebuild, but the first build (the
test thread's first call, with class loading) took 6 ms and the held rebuild 1 ms, so `by=` named the test thread. The test
now holds the rebuild 50 ms before releasing it. No production code changes; 8929-8932 carry over unchanged.

## 122. Each lane its own pocket (8934)

Owner, 22 Nov 05:44: "are all of the specialists, traders, lanes being fed coins that can make money within their designed
intent?", then 06:35: "fix it properly. that means all lanes specialists and traders".

**Feeding.** The lane hunters already deal each lane coins in its own band (LaneHunter7297, 04:08 capture: SHITCOIN 0-500k,
QUALITY 75k-1.0M, BLUECHIP 1.0M+, DIP_HUNTER 50k-5.0M, MOONSHOT 1k-500k, TREASURY / CASHGEN 1.0M-5.0M, EXPRESS 1k-300k,
PROJECT_SNIPER 2k-500k, MANIPULATED 2k-300k, CYCLIC 10k-5.0M, CORE 1k-5.0M), and BLUECHIP evaluates only $1M+ (95 LANE_EVAL).
The research rows hold every lane's verdict on every coin (labels for learning), not the lane's feed.

**What pays inside each band (20-21 Nov research rows, each coin once per lane at its first read in the lane's band, at the
lane's hold; single features and pairs of origin / stage / setup / V3 / forecast / pool / cap; proven = 30+ coins, both halves
positive, mean without the best three > z SE, z = CryptoTrend8886.familyZ8886 over the lane's cells with 30+ coins):**
CORE 7 (best off pump.fun + forecast >= +10 %: n98 +39.2 %, ex3 +33.7 %), MANIPULATED 6, SHITCOIN 4 (its LAUNCH_CONTINUATION
setup at $10k-$20k n36 +48.6 %), MOONSHOT 4, EXPRESS 4, DIP_HUNTER 1 (off pump.fun, pool < $5k, n46 +8.1 %). None: QUALITY
(n301 in band, -6.1 %), BLUECHIP (n123, -3.7 %), TREASURY / CASHGEN (n45, -6.5 %), PROJECT_SNIPER (n736), CYCLIC (n425), and
every CRYPTO_* lane (CORE n1504 ... TREASURY n108).

**Changes.**
- `LanePocket8934`: that search on the device — seeded from the research file, filed from every row as written
  (ResearchRows8877.note8877), mined hourly; one coin once per lane (first in-band read, last 3,000 coins per lane); BLUECHIP
  read at 4 h (its 8 h hold), every other lane at 1 h (QUALITY 60 min, TREASURY / CASHGEN 45 min, the meme lanes within the hour).
  A meme lane's coin inside one of its proven cells carries that cell as market-cell evidence in CandidateAdmission8136, after
  the base pocket, and answers the same priors (LaneAuthority8577 PRIOR_ANSWERING_KINDS); hard safety, the LP-lock rule,
  sellability, executable economics, avoid shapes and the losing setup record still decide; the size is the lane's own.
  CRYPTO_* lanes are mined and printed only (their door is CanonicalAssetEntryContract6551 and their twin records).
  `lanePocket8934[... coins{} proven{} served{}]` on the brief.
- `ResearchFollow8884`: the 4 h / 24 h re-reads had written nothing (waiting4h=15,213, written=0, failed=20 of 20 passes):
  batches carried non-Solana ids, which make Price v3 refuse the whole batch, and only the keyless host was asked. Now only
  Solana ids are sent (JupiterPriceIds8418.ids8418; non-Solana decisions are written unpriced as before), the keyed host
  (DefaultKeys.JUPITER) first, the keyless host as fallback; `calls8934{keyed keyless notSolanaIds}` on its line. Those rows
  are what BLUECHIP's 4 h pocket and any longer-hold judgement read.

## 123. The Tuning board off the main thread (8935)

8934's unit tests all passed (Aate8934 included); its runtime smoke failed on the screen walk: "ANR in com.lifecyclebot.aate
(TuningActivity) — Input dispatching timed out ... Waited 5001ms for FocusEvent", the app at 281 % CPU, load 32, the main thread
idle at the dump. TuningActivity.renderAll ran StrategyTelemetry.computeLeaderboard() on the main thread every 4 s (the uncached
path reads up to 2,500 closed trades and sanitises each), the one heavy read left on that thread after V5.9.1332 moved the lane
replay off it. The leaderboard is now computed the same way: a daemon thread, at most every 12 s, renderAll reads the cached
board. 8934's code ships unchanged in 8935.

## 124. Clean Token-2022 mints on the careful path (8936)

22 Nov 13:59 capture (8932, 2.2 h up): the careful explore path (8928, agreement pocket at $10k-$20k) bought nothing; its top
refusal was 8928's own classic-SPL requirement (252), ahead of NOT_IN_POCKET (229) and NO_TAPE_POOL_TOO_YOUNG (49). The owner
never asked for classic SPL only, and many of the pocket's coins are Token-2022 mints with only metadata extensions.
ExploreLive8733.tokenRefusal8936 now names each refusal: NO_MINT_PROOF, MINT_AUTHORITY_LIVE, FREEZE_AUTHORITY_LIVE, then — for a
Token-2022 mint only — TOKEN2022_HOSTILE_EXTENSION (OnChainMintAuthorityProof7248.hostileExtension8123: permanent delegate,
pause, transfer hook, frozen default state, non-transferable), TOKEN2022_MUTABLE_EXTENSION (mutableExtension8136: a live fee or
hook authority) and TOKEN2022_TRANSFER_FEE (Proof.transferFeeBps8936 > 0, the larger of the older / newer fee). A classic SPL
mint and a clean Token-2022 mint pass; mint and freeze authority must be revoked on both, as before. RugCheck, the LP rule, the
pocket, the flow test, 1 open / 6 a day / 0.05 SOL and the LP-pull exit are unchanged. The reasons read in explore8733 asked{}.

## 125. The LP lock is the pool's own (8937)

Owner, 22 Nov 14:24: "ive asked you to research the lp lock and come up with a smarter strategy". The 70 % rule stays; what
changes is where its number comes from, and that its bind is measured.

What the rule guards: an LP holder withdrawing. Pulling a share u of a constant-product pool and selling the withdrawn tokens
into what is left leaves the price at (1 - u)^2 of where it was: 70 % locked still allows a -51 % worst case, 0 % locked -100 %.
Where no LP can be withdrawn there is nothing to guard. By venue (sources: pump.fun docs and help centre; Meteora DBC docs and
README; Raydium LaunchLab docs):
- bonding curves (pump.fun, Raydium LaunchLab, Meteora DBC): no LP; the curve's custody is read on chain (8881) — unchanged.
- PumpSwap, where every pump.fun coin graduates: the migration burns every LP token it mints ("liquidity cannot be removed
  manually"). Read now on chain (PoolLock8895.readPumpSwap8937): burned share = (Pool.lp_supply @203 - LP mint supply) /
  lp_supply; a later deposit's LP is its depositor's and counts as unlocked. Pump.fun mints were skipped by the reader before.
- Raydium CPMM (LaunchLab graduates: LP burned or platform-locked through Burn & Earn) and AMM v4: read since 8925 / 8926.
- Meteora DAMM v2 (DBC graduates): permanent + vested locks, which DBC configs split between claimable and locked LP — read
  since 8895 / 8925. DLMM: locked positions (8895).
- any pool someone made by hand: the drain risk the rule exists for. 70 % stays.

Changes: (1) a pool proven 70 %+ locked from its own accounts counts at the proven figure over the provider's lower figure for
the SAME pool, in TokenSafetyChecker (LiquidityOwnership8522.lockedAt8937; the report may carry a proof up to 10 minutes old,
PoolLock8895.reportProof8937 — burned and permanent LP only grows) and at the buy door (LiquidityOwnership8522.chainProven8937,
the door's own 60 s proof); the provider's named unlocked-LP finding is answered only when every pool with depth is so proven
(allDepthPoolsProven8937). A provider figure under 70 % for a pool not yet read asks the chain now, and a newly proven lock
re-makes the coin's safety report (SafetyRefreshQueue). The reader's lower figure never replaces the provider's (it counts no
third-party locker), the reader never blocks, an unproven pool still refuses, RugCheck stays required. (2) Every LIVE coin whose
only hard safety finding is the LP rule is filed at intake under LPSTATE8725_LP_BLOCKED by band (LT30, 30_70, UNLOCKED_FLAG),
once per coin per 15 minutes, and read forward like every refused decision (research rows: net, peak, held peak, dip), so
lpState8725 LP_BLOCKED says whether what the rule keeps out drains or pays (8725 probed paying-cell coins only, which an
LP-blocked coin never reaches: LP_BLOCKED was n0). (3) poolLock8895 counts providerCorrected8937{venue:band} (the provider
under 70 % where the pool's own accounts prove it locked) and otherPrograms8937 (pools of venues not yet read: the next to add).

## 126. The door waives on the report's proof (8938)

A fault in 8937 found on review: TokenSafetyChecker let a chain-proven lock stand over the provider's lower figure for up to 10
minutes (PoolLock8895.reportProof8937), but the buy door (LiquidityOwnership8522.chainProven8937) waived the provider's
under-70 % pool only on its own 60 s proof. A pool proven 61 s to 10 minutes earlier passed the report and was BLOCKED
(LP_LOCK_BELOW_REQUIRED_8522) at the door, and a block asks no re-read, so it stayed blocked until the report's own window
lapsed and it asked again. The door now waives on the same 10-minute proof; PROVEN still needs the fresh 60 s read, so between
the two the door answers LP_OWNERSHIP_PROOF_PENDING_8522, which re-reads the pool (PreTradeHardGate / BuyRouteSafety8522) and
proves it on the next look. Past 10 minutes the provider's figure stands, as before 8937.

## 127. The careful path takes its proven cohort (8939)

Owner, 22 Nov 14:57: "where's the trade volume gone? where are the big winners????". The research rows (20-22 Nov, one row
per coin, the hour's executable net): every lane's ADMITTED coins lose at the hour (MOONSHOT n765 -5.0 %, CORE n401 -3.4 %);
the one refused cohort that pays is the paying-cell coins the buy door left waiting on a safety proof (LPSTATE8725_PENDING
n107 +20.3 % +-6.6). Within it, a +10 %+ forecast at a $10k-$20k cap: n46 +44.2 % +-7.5, median +31.2 %, halves +42.2 / +46.2,
ex-top-3 +34.9 %, 2 % drained (min <= -80 %); V3 >= 23 n24 +55.6 %, V3 under 23 n22 +31.8 % +-8.1. The careful path
(ExploreLive8733, 8928) also asked the coin's V3 >= 23 through the agreement pocket's test, which halves its cohort.
AgreementPocket8874.pocketVerdict8939 gives the pocket's verdict and what it missed (ORIGIN, CAP, V3, NO_LANE_EVIDENCE, FORECAST);
the careful path asks it with requireV3 = false and names a miss as NOT_IN_POCKET_<why> in explore8733 asked{} (it was one
NOT_IN_POCKET, 229 on the 13:59 capture). provenLaneFor8928, whose one caller was this path, is retired into it; the agreement
pocket's own admission evidence (evidence8874) keeps V3 >= 23. Unchanged: unread LP
only (never reported unlocked or under the lock), RugCheck reported, mint + freeze revoked, a classic or clean Token-2022 mint,
the flow test, 1 open, 6 a day, 0.05 SOL, the LP-pull exit. Not known from the rows: which safety proof each PENDING coin waited
on, so the share of the cohort this path can reach (it cannot buy a coin still waiting on RugCheck) is read from the next capture.

## 128. The careful path takes all of its cohort (8940)

Owner, 22 Nov 15:03: "release the kraken bro ffs. its meant to be a fucking money printer", answering whether to raise the
careful path's caps. ExploreLive8733: MAX_OPEN_8733 1 -> 4, MAX_PER_DAY_8733 6 -> 30 (the 8939 cohort arrives at about 27 coins a
day). Unchanged: at most 0.05 SOL a buy, unread LP only, RugCheck reported, mint + freeze revoked, classic or clean Token-2022,
the flow test, the LP-pull exit, and every balance check on the shared wallet.

Not changed, by its own record: the lane liquidity floor (NATIVE_LANE_LIQ_BELOW_FLOOR). Its refusals in the research rows (20-22
Nov, meme lanes, one row per coin and lane, the hour's net): n3120 -17.1 % +-1.0, median -3.6 %, 16 % drained (min <= -80 %),
1 % peaked 400 %+; under $2k pool n1791 -25.6 %, 25 % drained. The runners it turned away (FRIEND +5677 %, PENNY +2678 %, SB
+1756 % on the 13:59 capture) are that 1 %; the floor protects.

## 129. Launch runners ride (8941)

Owner, 22 Nov 15:07: "your entire focus more the moment is now capturing these big winners as early as possible". Deep dive on
every export (write-up: "AATE Runner Deep Dive — 22 Nov"). The launch-hour paths (13 exports, 14-20 Nov, 9,688 pump.fun
launches in 10 s bars; each entered at its own 20th print, so the export's 20-print floor is no hindsight): 9.5 % ran 4x+ after
entry and those made 166 % of all net. RunnerDoctrine8584 as it runs live (rungs 25 % at 2x, 18 % at 5x of what remains; the
trail armed at +25 % on the stop-widened line; a 25 % stop) against the same with its rungs and its trail from 10x:
next-bar fill, 3 % cost +7.3 % -> +11.0 % (n8144); 20 s entry lag +3.5 % -> +6.9 % (n6234); 20 s lag, 4 % cost, every sell a
bar late at that bar's low, 3 % rung slip -8.8 % -> -4.1 %. Both halves agree in all three. Off-pump base coins pay more under
the doctrine (base pocket +37.6 % against +29.1 % held), so they keep it.

A launch rider = a pump.fun mint bought within 15 minutes of its resolved chain birth (CanonicalTokenBirthTime7440; an unresolved
birth is not a rider). For a rider: SpikeCapture7943's rungs below 10x wait and its first sale is the 10x rung's own quarter
(riderRungFloor8941); BotService.doctrineTrail8630 arms the trail at 10x (trailArm8941) on the doctrine's own line; Executor's
doctrine hold (holds8584 rider8941) holds every other profit exit while the price is above the lane stop below a 10x peak, and
lets its time exit pass once the hour is up (4 h reads of these coins fell about -69 %). Unchanged: the lane stop, every safety,
rug, liquidity and LP-pull exit, the kill switch, tier-1 emergencies, every non-rider position. The paper book
(MemePaperTrader8544) keeps the doctrine as it was: live leads.

## 130. The launch is read at its twentieth print (8942)

Owner, 22 Nov 15:28: "keep going" (the deep dive's next step: entries at the 20th print). The launch-hour paths: a launch's
20th print (20-40 s after birth for most) is where an entry pays best, and each 10 s later costs 2-4 points; the lanes' launch
admits entered at a median 46-78 s. A PumpPortal create runs BotService.fastLaneEvaluate7277 at once, when the coin has a print
or two, and that fast lane was then closed to the mint for 60 s; the coin waited for the main loop (5.8 s cycle on the 13:59
capture; decisionLatency8882 PUMP_PORTAL_WS decide50/90 = 14/46 s).

truth/LaunchPrints8942 counts each witnessed, live-timed pump.fun print (TradeTape8100.launchEvidence8750, the prints the export
counts) from the launch's first; at its 20th, 40th and 80th within 15 minutes BotService runs the same full pipeline on it under
its own fast-lane key (fastLaneEvaluate7277 onceKey "<mint>|8942|<n>"), when the coin is on the watchlist. No gate is bypassed;
nothing here admits, sizes or trades. launchPrints8942[...] in the brief: fired per milestone, late (past 15 min), notWatched.

## 131. What refused the evidence coins (8943)

The 13:59 capture's evidence funnel: the proven base pocket (off-pump BASE_START, $10k-$50k: +40.0 % at the hour, n163, the
steadiest edge in the research rows) reached the buy door on 153 coins and was refused on 90 by one undivided
SAFETY_REFUSED_8136, so the next fix could not be chosen from the data. DecisionEpisode8535 now reads the hard finding the
admission's own trace names ("safety=HARD_BLOCK:<first reason>", or the live holder block), its numbers folded to N
(safetyCause8943), once per evidence coin per candidate, and the brief's evidenceFunnel8876 prints it per evidence kind as
safety8943{...}. Counted, never acted on.

## 132. The fast lane's golden pin follows 8942 (8944)

8942 and 8943 built and passed the runtime smoke, but their unit run failed one pin:
GoldenTapeRegressionTest.V5_0_7277_helius_leads_evidence_bar_fee_floor_runner_exits_fast_lane_and_smart_money asserted the old
fastLaneEvaluate7277 signature. 8942 added `onceKey: String = mint` (its default keeps every older trigger's key), so the pin
now reads the new signature, marked superseded. No code change; 8944 carries 8942 and 8943 as they are.

## 133. The unwatched launch, the follow-up's failures, the repeated words (8945)

19:34 capture (8944, 36 min up). launchPrints8942 fired at 417 milestones and found the coin off the watchlist 345 times, so
most launches were never re-read at their 20th print. BotService's milestone listener now sends an unwatched launch, once at its
20th print, through promoteHotLaunchNow7921 — the same protected intake a heat promotion uses (admitProtectedMemeIntake, every
intake gate), with source LAUNCH_PRINTS_8942 and its own fast-lane key — then the same fast lane. A launch the intake refuses is
not asked again at its 40th or 80th. launchPrints8942 counts intakeAdmitted8945. LaunchTape7921.symbolName8945 gives the create's
symbol and name.

researchFollow8884 showed failed=68 with calls8934{keyed=0 keyless=0}: no 4 h or 24 h read has been written, and nothing said
why (Jupiter's migration page: the lite host's rate limit "will be reduced progressively until it is fully retired"). Each failed
read is now counted by host and cause — HTTP status, empty body, the exception's class, or NO_KEY — as readFailed8945{...}.

Heap map 19:34 (337 MB at 36 min): ResearchFollow8884.pending held 9.5 MB for 30,039 waits and LanePocket8934.books 7.0 MB for
15,878 coins, mostly repeated words (lane, stage, setup, reason; seven feature strings per coin). Each such word is now held
once (word8945, bounded pools). Nothing read or decided changes.

## 134. The floor band opens cautiously (8946)

Owner, 22 Nov 20:15: "open the floor band cautiously earlier". The research rows (20-22 Nov, one row per coin, the hour's net):
paying-cell coins the buy door left waiting on a safety proof, with a +10 %+ forecast at a $5k-$10k cap: n19 +59.6 % +-15.0,
median +50.6 %, halves +62.1 / +57.4, ex-top-3 +35.7 %; 2 of the 16 off pump.fun drained (min -100 %). Under the bot's 30-coin
bar, so the band opens under its own limits. ExploreLive8733.eligible8733: a coin the pocket refused only for its cap
(pocketVerdict8939 "CAP") is asked the same pocket test at the band's $5k floor (off pump.fun, a lane carrying the pocket's
evidence, the +10 % forecast); every other check of the careful path stands (unread LP only, RugCheck reported, mint + freeze
revoked, classic or clean Token-2022, the flow test, the LP-pull exit). At most 1 band position open and 8 band buys a day
(withinBand8946), inside the path's 4 open / 30 a day and 0.05 SOL a buy; the executor's spendRefusal8733 holds a band coin to
both. explore8733 shows band8946{today open floor} and asked{ELIGIBLE_BAND_8946, BAND_8946_BUDGET, NOT_IN_POCKET_BAND_*}.

## 135. The proof bar is tiered (8947)

Owner, 22 Nov: "is 30 trades too much or enough?" then "go ahead". ProofBar8947 grades a per-coin record (each coin's outcome
once, oldest first) instead of counting to 30. HOLDS: mean above zero by two SE, still positive without the three best coins,
and both halves (older, newer) positive on their own; the median is shown, not required, because a runner edge (most coins a
small loss, one in ten a multiple) has a negative median. CAUTIOUS = holds on 20+ coins: opens ExploreLive8733's careful path
only (its 0.05 SOL, 4 open / 30 a day; pocketVerdict8939 takes a meme lane whose pocket labels are not proven losing when the
8895 coin record or the 8929 research record grades CAUTIOUS, beside the old bar). FULL = holds on 50+ coins with the newest
20 positive: AgreementPocket8874's full-size coin evidence (coinEvidence8895) and BasePocket8904's evidence now also need FULL.
Re-graded on every read: a record whose newest 20 turn negative drops to cautious. On the 22 Nov 20:00 research rows the careful
pocket (83 coins) grades FULL (+21.6, lower +6.2, ex-top-3 +14.3, halves +25.1/+18.1, newest 20 +31.8) and its first 20 coins
were already CAUTIOUS; the base pocket's first 20 (lower -2.1) were not. Running-sum cells (PayingCell8723, LlmBands8875, the
per-lane agreement cells) cannot be graded per coin and keep the 30-coin bar. agreement8874 shows proof8947{coins research};
basePocket8904 shows proof8947{base pump}.

## 136. A proven coin's data wait is answered directly (8948)

Owner, 22 Nov 23:21 "still badly choked" and 23:31 "we are deferring way too many trades when we could be just confirming the
required information on chain with a direct request". The 23:03 capture (8947, LIVE, 31 min, 0 buys): LANE_POCKET 49 candidate
episodes, all 49 DEFER_DATA (PRICE_STALE 26, buy-door critical proof 19, safety report not fresh 4), 0 admitted; paying-cell
coins DEFER_DATA 142 against ADMIT 7; last trace an EXPRESS lane-pocket coin (origin=OTHER&stage=BASE_START n188 +26.5 %) at
net +26.7 % deferred on a price past the 30 s bar with a safety report 167 s old (bar 120 s). The safety refresh ran only when
the cycle reached the coin again (8.9 s cycles, heap 84-90 %), the price on the mark lane's own schedule, so the two proofs
were never fresh together. Research rows, 24 h to 22:48: the base pocket (off pump.fun, BASE_START, pool $5k-$100k) 222 coins
at +32.0 % mean / +19.8 % median an hour on, 199 lane-admitted, none bought.
EvidenceWait8948: when CandidateAdmission8136 (LIVE) defers a coin carrying market-cell evidence on data, its proofs are
requested directly at once: SafetyRefreshQueue (the next pass runs TokenSafetyChecker uncached: RugCheck and the chain's
mint / freeze authority, holder and LP reads), the coin's own full pipeline pass now through BotService.fastLaneEvaluate7277
(two passes 1.5 s apart, keyed per 20 s slot), a fresh price (CandidateMarkFresh8094) and the authority read
(AuthorityProofPrefetch8136); at most one direct request per 20 s per coin. Between them the coin keeps a cycle slot
(SafetyProofPriority8310.noteDeferred8310 and targets8948 through HandoffPriority8190) for up to 120 s. Every gate, bar and
proof is unchanged; the same admission, buy door and FDG decide. evidenceWait8948 in the brief: waiting, noted, asked,
direct, admitted (mean wait), refused{why}, expired{what was still missing}.

## 137. The launch rule's fires are bought and held (8949)

Owner, 22 Nov 23:43: "we need to be capturing the handful of runners on pump.fun first ... it has to buy them and hold them".
The launch replay's proven rule on the 23:03 capture: cap$5k buys>=3 tp5x stop35% DOCTRINE +20s, n3104, mean +7.1 %,
ex-top-3 +6.5 % +-2.2, 2x+ 7 %, 10x+ 0.3 %. Live: 28 armed fires in 31 min, 50 admission episodes, 1 admitted
(SAFETY_REFUSED 21 of which top-holder concentration 25, DATA_INCOMPLETE 14 with a stale price 13, STRUCTURAL_RISK 8,
LIVE_STAGE_LOSES 7, FDG_BLOCK 17). LaunchSniper8748:
- an armed fire runs the coin's full pipeline at once (onArmed8949 -> BotService.fastLaneEvaluate7277, origin
  LAUNCH_RULE_8949; a coin off the watchlist through promoteHotLaunchNow7921's protected intake first), not when the 8.9 s
  loop's rotation reaches it (PUMP_PORTAL_WS decide p50 34 s against a rule graded at a 20 s entry);
- LaneAuthority8577.PRIOR_ANSWERING_KINDS_8874 holds KIND_8748: the rule's narrower proven group answers the lane-wide entry
  priors (LIVE_STAGE_LOSES_8626 on the LAUNCH stage, origin cohort, buys-the-high, stage unread) as the agreement pocket,
  base pocket and lane pocket do; hard safety (top-holder 35 %, LP, authorities), structure, sellability, economics and the
  coin's own shapes still refuse;
- a position admitted on an armed fire (noteAdmission8949, entered within 10 min) holds on the rule's own graded stop,
  kept inside the ordinary maximum of 25 % (StopAuthority7887 binding LAUNCH_RULE_8949; the hard floor 30 % and the
  catastrophe line unchanged), where the lane's ordinary stop was SHITCOIN 8 % / EXPRESS 10 %; never tighter than before.
sniper8748 shows runNow8949, admitted8949, heldOnRuleStop8949. Also in 8949: regexes compiled per call on hot paths
(DefectSweep8433, PipelineHealthCollector, ForwardReturnLabeler7731, MemeMeta7973, NativeLaneConstraints8521; 742 ms of
the 23:40 30 s CPU sample) are compiled once.
Memory (owner 23:47 "do the memory work in the same build"): the 23:40 CPU sample (30.6 s) had HeapTaskDaemon (GC) at 18.2 s
of CPU, 61 % of the sample. The app's own leading allocators: ShadowCohortBook8397's append path (each close read, decoded
and re-encoded up to 300 rows per cohort key, about 17 % of app CPU: decode, boxed Double per field, encode, SQLite), the
cost breakdown's String.format (756 ms of a new Formatter + DecimalFormatSymbols per cost call), regexes compiled per call
(742 ms). Now: a key not held hot gets the new row appended to its stored text and trimmed to the last 300 rows
(appendRow8949), with no decode or re-encode; decode parses rows in place with no string list or boxing (same rules: a row
needs a parsed time and a finite net, a malformed other field reads 0.0); the breakdown digits use NetEdge8030.fixed8949
(half-up, '.'); the regexes are compiled once. Records and decisions are unchanged.

## 138. What a decision needs is confirmed on chain (8950)

Owner, 22 Nov 23:53-23:57: "we are reading coins totally wrong ... anything flagged UNKNOWN must be confirmed on chain asap
... that goes for everything data related. if we need it to make a trade decision, hold, exit appraisal, confirm it on
chain". Research rows 21-22 Nov (37,658 hour reads): stage UNKNOWN on 64.0 % of meme-lane reads and 83.6 % of crypto-lane
reads. Joined with the dataset's decision-time features (4,488 coins): the UNKNOWN coins lacked run-up / distance from peak /
drawdown on 52 % of reads and 5 m / 1 h change or buy / sell split on ~30 %. Off pump.fun coins under 5 minutes old with no
price path: 545 coins, +21.0 % mean, ex-top-3 +14.8 %, halves +13.0 / +29.0 an hour on. Lanes deciding the same coin in the
same second read different caps on 1,240 of 7,968 decisions (2x+ on 64). Helius at 26 % of the day's budget; swap-history
reads waited 20 s for free sources (FREE_GRACE 323) and were budget-skipped 164 times on the 23:03 capture.
- StageRead8950: every LIVE TokenMetricStageRouter.snapshot that reads UNKNOWN asks the chain for the coin's own swap history
  at once (HeliusSwapCandles7819: no free-source grace, the near-decision tier, one 100-credit page on SWAP_CANDLES' paced
  share, never the priority lease), once per 60 s per coin; when the bars land the coin's full pipeline runs again
  (fast lane STAGE_READ_8950) and the re-read stage is counted (restaged{}).
- ChainConfirm8950: a LIVE decision (LiveEdgeGate7877's data defer, every candidate), a proven coin's wait (EvidenceWait8948),
  an UNKNOWN stage, an exit appraisal without a mark (MissingMarkExitVeto6835) or a held position without a price
  (SellReconciler) reads each missing input from the chain now: price / depth (curve reserves, pool vaults; OnChainDepth8038),
  history / stage (StageRead8950), mint and freeze authority (AuthorityProofPrefetch8136), holder concentration
  (OnChainHolderConcentration7379), LP lock (PoolLock8895 per named pool), safety report (SafetyProofPriority8310); each
  (coin, input) at most every 20 s, off the caller's thread. No rule, bar or gate changes.
The brief shows stageRead8950{unknownSeen chainAsked chainRead restaged{} candles} and chainConfirm8950{use:input=n}.
Not yet: one cap / liquidity / stage reading per coin per moment shared by every lane (next).

## 139. The stack audit: confirm it on chain (8951)

Owner, 23 Nov 00:03: "audit the full aate stack. apply this where it's going to fix issues"; 00:07: "you don't trade btc
like a pump.fun launch coin ... they have lanes, traders, setups and strategies already built". The 23:03 capture's data
waits, by count: TOKEN_BIRTH_HYDRATION_PENDING_7440 273,577 (birth walks launched 1,536, resolved 184, cooldownSkips
19,426), RECOVERY_BIRTH_UNKNOWN_8168 12,843, TOKEN_MAP_PENDING 1,893, INTAKE_LIQ_UNREAD_PROBATION_8418 1,438,
NATIVE_MCAP_UNHYDRATED_DEFER 262 + 230 + 144, DATA_INCOMPLETE_DEFER_8136 1,018, PRICE_STALE 420.
- Birth (age, lifecycle, every launch window): TokenBirthHydrator7441 asks Helius getTransactionsForAddress OLDEST first
  (signatures, succeeded, limit 1; Helius docs: signatures mode 10 credits) before any newest-first page walk; the row is
  proven exactly as a walked one (InitializeMint / InitializeMint2 on this mint) and the walk runs as before when it is
  not answered. birthHydrator7441 shows oldestFirst8951=found/asked.
- Unread intake: a pump.fun coin parked in probation with liquidity and cap unread has its curve read from the chain at once
  and is promoted onto the watchlist when the chain shows real SOL (ChainConfirm8950.probation8951; checks at 3 / 10 / 30 s).
- Lane input defers (SpecialistBrainBridge7542, LaneInputTruth8236.missing8951): an unread cap asks the price and the mint's
  supply (OnChainSupplyAuthority7075), unread momentum / history / buy pressure the coin's swap history.
- Exec gate (ExecutableOpenGate TOKEN_MAP_PENDING): the unread pool's liquidity and the mint's supply are read on chain.
- Established assets (TokenMetricStageRouter.memeLifecycle8951 = not isEstablished7306): an UNKNOWN meme stage on them is
  not chased on chain; their own lanes and desks stage them (establishedLeftToTheirLanes8951).
- CapTruth8951 (measured only): the cap the lanes read vs the chain's (active curve price x verified supply), ratio buckets
  and direction, before any change of the cap's authority.
Not in this build: paper equity's stale last-good marks (STALE_MARK_PROFIT_WITHHELD_7160, paper positions), label marks for
learning (1,284 of 1,744 unpriced), the crypto desks' own feeds.

## 140. pump.fun read in its own units; the paid tape for deserving coins only (8952)

Owner, 23 Nov 00:14: "do all above now" (the cap the lanes read); 00:18: "pumpfun can literally provide the data for pumpfun
coins ... only subscribe to coins it genuinely thinks are deserving not a blanket open feed. deep learn the full pumpfun and
pumpswap world"; 00:31: "add runner sensitive token streaming to the selective list". Sources read: pump-fun/pump-public-docs
(README, CREATE_WITH_PUMP_COIN_QUOTE), the Pump Developer Updates channel (posts 32-48), the program IDLs as Helius parses them.
- The cap from the curve (CurveReserves7951.capFromCurve8952): an account read of an active curve writes MCAP_USD at CHAIN
  rank (curve price x verified mint supply x SOL/USD; source CURVE_CAP_8952, at most every 2 s). CapTruth8951's measured
  disagreement (17.6 % of reads 2x+ high, 12.9 % 1.15-2x high, 7.3 % low) now asks the curve account for that coin.
- Quote mints (QuoteTruth8952): since 21 May 2026 a pump.fun coin can be paired with USDC, and since the depth-1 / depth-2
  releases with an earlier pump.fun coin. The TradeEvent's quote tail (quote_mint, quote_amount, virtual / real quote
  reserves, after ix_name and shareholders) is decoded; a USDC curve's amounts are converted with SOL/USD, a pump-coin quote's
  with that coin's own chain price; an unpriced quote is dropped, never read as lamports. The 30-SOL curve math
  (CurveReserves7951) refuses a non-SOL curve; the CreateEvent's quote_mint marks one at birth; PumpPortal frames of a
  non-SOL curve are not read as SOL. curveTape8354 shows quoteTruth8952{events{quoteLayout legacyLayout} nonSol{kind:priced}}
  and malformed{reason=n} (ZERO_QUOTE / ZERO_TOKENS / ZERO_RESERVES / PRICE / EXEC_VS_RESERVES_<quote> / OUT_OF_BAND).
- PumpSwap (PoolVirtualQuote8952): since 21 Jul 2026 a pool prices on vault + virtual_quote_reserves (i128, can be negative).
  The events were decoded that way (8870); the vault-balance read (OnChainDepth8038) now adds the pool's latest virtual quote
  from its own swap events. poolVirtualQuote8952{nonZero negative vaultReadsAdjusted vaultReadsNoEventYet}.
- Selective paid tape (PumpFunWS.deserve8952): with the blanket switch off and a working key, LIVE only, PumpPortal's
  per-trade stream carries HELD coins, armed LAUNCH_RULE fires (LaunchSniper8748), PROVEN_WAIT coins (EvidenceWait8948),
  RUN_FORMING (RunTape8293, past safety and stand-down) and RUN_FOLLOWED (RunnerPlay8018) coins; at most 16 at once, 15 min
  from the latest reason (held: while held), inside the persisted 100k-frame day budget (new ones stop at 80 %). A lapsed
  coin is unsubscribed on its next frame. The Helius program tape keeps running beside it. selective8952[...] in the
  PumpPortal status shows live / byReason / asked / subscribed / frames / released / skipped.
- Checked, no change: AATE builds pump.fun trades through PumpPortal trade-local and Jupiter, which carry the fee-recipient
  changes (new protocol recipients from 25 Nov); the per-trade price used for the tape already uses each event's reserves.
Not in this build: the PumpSwap pool account's own virtual_quote_reserves offset (not published where it could be read); the
vault-history price (ChainHistoryMark8541) still reads the raw vault ratio.

## 141. The full suite repaired (8953)

CI for 8949-8951 (Complete historical suite): five new failures, no compile error.
- Aate8401DeliveryTest (behaviour, from 8949): a shadow key with nothing stored yet now stays hot after its first cold append
  (it holds exactly that one row), so a reader sees it without a store round trip, as before 8949. Keys already stored keep
  8949's text append without a decode.
- Moved pins, same intent: Aate8439 (the birth hydrator's fourth per-rung health label: 8951's oldest-first read), Aate8549
  (the one-page rule now also covers 8950's UNKNOWN-stage read), Aate8934 (LaunchSniper8748's kind follows LanePocket8934 in
  the prior-answering set), Aate8950 (the stage router passes memeLifecycle since 8951).
The 8949 runtime smoke failure was the forensic accounting witness (cash / open-cost delta 0.11 SOL); 8950 and 8951 passed
the same runtime test, and the same witness failed and then passed on ade91d56b, so it is recorded as that witness's flake.

## 142. An unnamed quote never silences the SOL tape (8954)

8952 dropped a TradeEvent whose quote mint was neither SOL, USDC nor a pump.fun coin. If the event's variable-length middle
(ix_name, shareholders) ever parses differently from the IDL this build read, that quote is noise, and dropping on it would
have emptied the whole Helius curve tape. Such an event now reads exactly as before 8952 and is counted
(quoteTruth8952 nonSol{OTHER:unpriced}); only a named USDC or pump.fun-coin quote is converted or dropped.

## 143. The memory map always reports (8955)

8954's runtime check: "no HEAP_MAP_WRITTEN_8866 line within 480s; raw dump left on disk" (8953, minutes earlier: the whole
map in 63 s, heap 218 MB). No crash, ANR or kill line; the process was the same; nothing said which stage the map had reached.
- Holder tracing (each level one more read of the whole dump, up to 18) stops starting new levels after 180 s
  (HeapMapCore8866.HOLDER_BUDGET_MS_8955); the levels already traced stand, as at the pass limit, and the header says
  holderBudget8955=stoppedAtLevelN. The class, name and object passes always run. The raw dump is deleted on finishing, so
  a slow read no longer leaves it on disk while a wait runs out (on the phone too).
- Every stage is logged (HEAP_MAP_PHASE_8866: dumping, dumped dumpMs fileMb, classes, names, objects, holders N, with the
  elapsed ms); the device check names the last stage reached when no map line arrives.

## 144. 8955's compile repaired (8956)

8955 did not compile (HeapMap8866.kt:103, "inferred type is () -> Any but Long was expected"): the new holder budget
parameter came after the stage callback, so the caller's trailing lambda bound the budget. The budget now precedes the
callback, and the caller passes the callback by name. Behaviour is 8955's.

## §145 — 8957: a chain ping's answer re-runs the coin (owner, 23 Nov)

Owner: "a one time chain ping to fill the data deferral gaps when required. no waiting for the loop to come back."
ChainConfirm8950 asked the chain for each missing input at once, but only the stage read re-ran the coin when its answer
landed. Price/depth, supply, authority, holders, LP and safety answers waited for the scan loop to return to the coin;
EvidenceWait8948's direct re-run fired at 0 s and 1.5 s, before the async reads it had just queued could land.
A LIVE decision, lane, evidence, stage or exec-gate defer that sends at least one ping now re-runs the coin's full
pipeline at 2.5 s and 7 s (fast lane, deduplicated per coin and 20 s slot). Holds and exits keep their own loops.
Pings still respect the 20 s per-(coin, input) gap. No rule, bar or gate changes. Status: chainConfirm8950[reruns8957=
unmapped8957=] (unmapped counts defers whose missing names map to no chain read).

## 146. Runner dispatch and crypto candidate identity (8958)

- Dynamic crypto discovery and the selected top-25 handoff now test open positions by asset and execution mode. A different contract using an open coin's display ticker is no longer labelled POSITION_ALREADY_OPEN. The same Solana mint, bare or chain-qualified, still blocks a duplicate. A legacy position without a key retains a conservative address check. Live cross-book protective inventory remains binding.
- The crypto twin's mark and hold-extension paths use the registry's held-key resolution, then check exact identity before fetching and before accepting a fresh observation. A bare Solana mint can find its own chain-qualified row; no price or symbol fallback is introduced.
- An armed LaunchSniper rule already on the watchlist can wait up to six 100ms intervals for one of the existing six evaluation slots. At most four signals wait, expiry and watch membership are rechecked, and non-rule events retain the original immediate admission. A launch rejected or parked before it has a watch row no longer consumes the fast-lane evaluation key. None of these events authorizes a buy.
- Golden Tape: added source-boundary test V5_0_8958; existing six-permit and conditional second-pass pins remain unchanged. Runtime acceptance is separate: count decisions per armed fire, eligible crypto identities reaching canonical admission, twin marks and closes, independently of later safety/EV refusals. No claim about live net returns from these tests.

## 147. Entry assessment uses market time (8959)

Launch fires carry both the trigger print time and the receipt time. The evidence, fast-lane eligibility and stop-plan admission now share the same 120-second expiry measured from the earlier clock. A late delivery cannot extend a replay signal's life. A LIVE price/depth defer uses the existing batched pool reader with the decision's five-second freshness requirement rather than the background 90-second cache and 60-second pool throttle. Its per-mint/input request remains deduplicated for twenty seconds. An accepted curve-account or vault observation matching an outstanding request wakes that mint's decision path once while fresh. Market-only waits set no reassessment timers. Mixed waits involving other providers retain timed fallback opportunities, never proof. This is not a safety bypass, a BUY or evidence of live profit.

## 148. The fast lane belongs to the signals (8962)

Report 23 Nov 22:07 (5.0.8961, LIVE, 37 min, 1 buy): FAST_LANE_EVALUATED_7277=537 against FAST_LANE_SATURATED_7277=4322. The
six event slots that run a coin's pipeline when a signal fires were refused 89 % of the time. The demand was chain answers:
8957 / 8959 listed the UNKNOWN-stage read among the uses whose price/depth answer re-runs the coin, and that read asks for
133,531 unknown-stage sightings (chainConfirm8950 STAGE:PRICE_DEPTH=7276 against DECISION:PRICE_DEPTH=50; marketAnswers8959=4954,
reruns8957=1802). The same flag let every stage ask read the pool past the 60 s gap. Coins nobody was deciding on took the slots
and the chain reads from coins at the gate: launch prints fired 553 times, LANE_POCKET coins deferred 105 of 173 on data
(PRICE_STALE 68), an admitted MOONSHOT buy reached the send 9.3 s and 52 s late and was refused as a consumed chase.

- ChainConfirm8950.RERUN_USES_8957 = DECISION, LANE, EVIDENCE, EXEC. A stage read asks the chain as before at the ordinary
  cadence; its history answer re-runs the coin through StageRead8950.direct8950 (unchanged since 8950).
- FastLane8962: the chain-answer re-runs that remain hold at most 2 of the 6 slots; every other origin enters as before.
- Brief line `fastLane8962[ran{origin=} noSlot{origin=} chainAnswerYielded=]`: the next report names who used and who lost a slot.

No gate, bar, threshold, size or safety rule changes. Acceptance on the next live report: noSlot{} for LAUNCH_* / EVIDENCE_WAIT
near zero, STAGE:PRICE_DEPTH a fraction of 7,276 per 37 min, buys start->broadcast and decide50/90 lower. Net returns are not
claimed from tests.

## 149. The proven pockets reach execution (8963)

Report 23 Nov 22:58 (5.0.8961, LIVE, 38 min, wallet 0.26 SOL): the proven pockets were found (agreement pocket coins FULL
n148 +38.0%, base pocket PROVEN n129 +42.5%, lane pockets CORE / EXPRESS / MANIPULATED) and almost none were bought —
LANE_POCKET 211 coins -> 11 tickets -> 3 executions; MOONSHOT 14 tickets -> 0 executions, SHITCOIN 15 -> 3.
- Tickets lost after the ticket: COOLDOWN_FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715=21 (a causal revalidation, whose own
  purpose is to decide the coin again, then locked (mint, lane) for 15 s under the cooldown table's default), causal
  blocks 5, BUY_TERMINAL_LIQUIDITY_BELOW_EXIT_SAFE 7 (kept: exit safety). A _REVALIDATE_6715 block now has no cooldown,
  as 7219 / 7255 / 7321 found for their deferrals; the block itself is unchanged. Each lane drift is counted
  CAUSAL_LANE_DRIFT_8963_<stamped>_TO_<executing> so the next report names its cause.
- Pocket coins' first data wait was PRICE_STALE (LANE_POCKET 70, LAB 30, BASE 10): ChainConfirm8950 asked the chain and the
  chain answered, but the curve-account and vault reads wrote liquidity only. ChainPrice8963: an active curve's account
  read and a constant-product vault read (PumpSwap once its virtual quote reserves are known; never a concentrated venue)
  write their price through TokenFacts at CHAIN rank and their own time when newer than the coin's, and wake the coin's
  waiting decisions when its price had gone stale. chainPrice8963[written{CURVE,VAULT} wokeStale notNewer].
- Measured, not changed here: heap 88-100 % with blocking GC 1,680 s of 2,317 s uptime (allocated 102 GB in 38 min, live
  floor 43 MB): the cycle (7.3 s avg, 55 s max), the safety-report refresh (24 expiries) and the buy path (4.8 s) all wait on
  it. The allocator is not in the census; the next CPU sample names it.
- Research (22 Nov dataset, 59,196 rows, 29,437 lane-coins, 51.7 h): no two-factor pocket in any lane pays on the raw
  one-hour hold; under exits (stop / take bracket, stop counted first) the same pocket proves in every meme lane — BASE_START
  off pump.fun, forecast +25 % or more, cap $5k-$20k, liquidity $20k-$100k (e.g. SHITCOIN BASE_START & OTHER n231 +14.0 %,
  ex-top-3 +13.7, halves +17.2 / +13.0 at -20 % / +40 %). No crypto lane pocket proved at the hour.

## 150. A clock is a prompt to reassess, not a sell (8964)

Owner, 23 Nov 23:38: "nothing should be force closed if the token metrics, volume etc support holding". Field Manual §10:
"Time exit: if the expected move does not occur within the strategy's horizon, reassess or close." The stack closed on the
clock alone: the crypto desks' one-hour ADAPTIVE_HOLD_MAX (13 of the twin's 18 exits on 23 Nov 22:58), the xStocks 2 h cap /
45 min loss cut / 30 min flat flush, and every meme time exit reaching the sell door (classifyExit TIME, hold-max, timeout,
underwater-time stop).
- HoldSupport8964.supports8964 (pure): a time exit holds only on fresh metrics with two signs agreeing — in profit: not
  rolled over (keeps half its peak gain), last 15 min flat or up, and the last hour up / buyers >= 50 % / rolling hour volume
  held at >= half its level 30 min ago, none reading against; under water: last 15 min up and (last hour up or buyers 55 %+).
  Unread data is never support.
- Meme: the sell door (after the net-profit hold, before the exit is classified) reads the coin's own 1 m candles, buy
  pressure and rolling volume. Crypto desks and xStocks: a mark ring per position (one point per 30 s, three hours) gives
  the 15 min and 1 h moves (a short's gain is the price falling).
- Operational, safety and price exits are never time exits here (reconcile, manual, sweep, dust, retry, rug, emergency,
  catastrophic, integrity, liquidity, sell-dominant, dump, hard / strict / stop-loss, trail, take-profit, ladder, rung).
- holdSupport8964[held{book:why} released{book}] in the brief.

## 151. A pocket coin is priced by its pocket (8965)

23 Nov 23:47 (5.0.8962, 7.7 min, full data set): LANE_POCKET 137 coins -> 114 DEFER_DATA -> 5 tickets; the top reason a pocket
coin expired unadmitted in evidenceWait8948 was MOVE_HISTORY_SPARSE (10, MOVE_UNREAD 3), and 4 of the 8 BASE_POCKET waits.
A pocket coin is young by definition: its own move history is what it cannot have yet, so the admission's economics test
had no move to judge and the coin waited until its slot expired.
- CandidateAdmission8136.decide8136: when both moves are missing (the selected setup's current move and the original), a coin
  carried by a proven market cell (8723: base pocket, agreement pocket, lane pocket; supported past the live shortfall)
  takes the cell's lower bound as its move — the same contract 8171 (a setup record), 8868 (the model's proven call) and 8918
  (the unproven falling hour) already use. A net-of-cost bound adds the cost back; a gross bound is the gross.
- A known current move still wins (8846). The falling hour's proven-losing refusal still binds (its block now also runs for a
  cell-priced move). Safety, structure, the setup's own losing record, the cheat sheet, the lane's losing cell, timing,
  realised losses and the economics check itself are unchanged.
- cellGross8965[lane=count] in the brief.
- Correction (8966, from 8965's own CI): the 8171 fallback already prices a carried coin from its cell whenever the cell is the
  admission's strongest net-of-cost record, so 8965 adds only the remaining cases (a stronger gross record chosen as the
  edge). It is kept out wherever the edge assembly keeps the cell out (the lane's own cell record losing). The field's
  MOVE_HISTORY_SPARSE expiries therefore have a reason not yet measured; 8966 names it on every such defer
  (cellMoveGap8966: CELL_UNSUPPORTED_RAW / CELL_BELOW_LIVE_SHORTFALL / LANE_CELL_LOSING / EDGE_NOT_NET / OTHER) and the brief
  shows it as cellGross8965[... stillWaiting8966{lane:why=n}].
- Research, 25 h of decision reads (edge_research 24 Nov 00:07, first decision per coin, 1 h): meme BASE_START n170 coins
  +25.8 % median +16.0 win 67 % (halves +27.5 / +22.8); off pump.fun n152 +30.9 %; cap $5k-$20k n101 +35.2 % median +39.6 win
  77 %. Every other stage loses (UNKNOWN -7.6 %, FRESH_LAUNCH -20.1 %, PEAK -7.7 % median -22.5, DUMPING -23.2 %). The same coins
  held to 4 h / 24 h lose (-35 % / -47 %): the edge is the first hour, under the lane's exits.
- Crypto desks, same file: their CRYPTO_CORE / SHITCOIN / MOONSHOT / EXPRESS rows are mostly pump.fun coins (684 of 1,394
  CRYPTO_CORE coins; median cap $41k-$172k) read at a crypto 2.6 % cost; on established coins ($1M+, $100k+ liquidity) the
  gross move is ~0 at 1 h, +1.5 % median at 24 h ($100M+: +1.8 % median, 76 % up, n25, one day) — under the round trip at a
  0.0273 SOL ticket, where the fixed legs alone are ~1.8 %. Not changed here.

## 152. The playbook save is paced to its weight (8966)

24 Nov 00:39 CPU sample (30 s, ART sampling every 20 ms, method names and times only): 51.0 s of thread CPU, of which the
collector (HeapTaskDaemon) 10.9 s (21 %) — the memory churn, priced. Of the app's own string / JSON / collection CPU (6.7 s,
attributed to the nearest app frame), LanePlaybook7907.checkpoint8372 was 2.3 s plus 0.35 s sorting the 600 grades: the largest
single allocator. Each save re-encodes every lane book, the timing books, the forward outcomes and the episode sidecar as one
JSON string, inside the playbook's own lock (every evaluate / record call waits on it); the 8901 pace let a 724 ms save
repeat after 2.9 s.
- checkpointWaitMs8901: the next save waits 19x the last save's cost (~5 % of its thread); a real book (a save of 100 ms+)
  waits at least 30 s; 60 s at most. A light save (a unit test's) still waits only its cost x19; a shutdown flush
  (persistNow7930) and the first save never wait. Each save drains a pace's deliveries (SAVE_BATCH_8901 = 32 x 60).
- Nothing is lost by saving less often: the forward-outcome producer retains and re-delivers every unacknowledged outcome
  (8871), and what a save writes and its verified atomic commit are unchanged.
- Expected: the save's allocation and lock time fall about 4x (30 s instead of ~7 s between saves).
- Seen, not changed here: SpecialistBuild8522 / ToolkitSignalSheet.build 4.0 s CPU (7.9 %), LanePlaybook7907.strategyPick8153
  1.7 s of its own CPU, CryptoLaneDesk7391.qualifyAll7803 1.9 s; DynamicAltTokenRegistry.rescueSolanaPriceBlocking7167 holds a
  worker 28 s of the 30 s (wall), the Telegram sentiment scrape 29 s (wall).

## 153. A rule is traded at the delay it was proven at (8968)

Owner's live export, 24 Nov 01:16 (32 closed live positions, 22-23 Nov): every pump.fun coin bought under a $20k cap lost —
10 of 10, -0.052 SOL, held 0.4-11 min (rug backstop -10, scalp hard stop, tick floor, catastrophe line) — bought at $9.8k-$17.9k
while the armed launch rule fires at a $5k cap. The $20k-$1M buys made +0.062 SOL (8 of 18 won); 22 Nov 8 of 13 won, 23 Nov
2 of 19. Report: 15 W / 23 L, net -0.0196 SOL.
- The bot's own decision reads agree (edge_research 24 Nov 00:07, 25 h, first decision per coin, 1 h): pump.fun under $5k
  n302 mean -16.3 % median -21.2; $5k-$20k n260 -18.0 % median -26.5 (admitted n68 -27.8 %); still -8..-12 % under the rule's own
  stop 35 % / take 5x, and only 1-2 % of them reach 5x inside the hour. Off pump.fun, $5k-$20k: n156 +25.3 % median +16.0
  (admitted n88 +33.1 %, 75 % up).
- 8749 had measured why: entering a launch 10 s after the trigger averaged +26..+50 %, 30 s after -4..-6 % (-23 % without the
  best three). The replay grades the armed rule at its own delay (+20 s), but a fire stayed admission evidence for 120 s,
  through every data wait and the 8948 evidence slot, so coins were admitted at delays the rule was never proven at.
- LaunchSniper8748.window8968: a launch or base rule's fire is market-cell evidence only for its graded delay (capped at the
  old 120 s); a signal rule (no graded delay) keeps 120 s. After the window the coin is judged on its own record like any
  other. The rule still fires, arms, watches and learns; a coin reached inside its delay trades exactly as before.

## 154. What cannot be traded is not drawn from the chain (8969)

Owner, 24 Nov 02:11: "if we know what the garbage is wouldn't it be smarter to not generate it or not draw it on chain?"
23 Nov 23:47 (5.0.8962, 7.7 min): the stage read saw 126,407 UNKNOWN readings, asked the chain 3,632 times, 30 answers landed,
and they restaged 20 UNKNOWN / 5 DUMPING / 1 FRESH_LAUNCH / 1 NO_SETUP: no base found. The lane reads added HISTORY 1,344,
PRICE_DEPTH 1,064, SUPPLY 1,002. A swap-history page is 100 credits, and every answer is parsed into short-lived objects; the
24 Nov CPU sample priced the collector at 21 % of all CPU. Which cohorts those reads went to was not counted; the brief now
counts every skipped read by cohort (§153 for why the pump.fun small-pool cohorts are the expected ones).
- ChainConfirm8950.confirm8950: a DISCOVERY read (uses STAGE and LANE) is skipped for a coin whose own origin x pool x age
  cohort (MarketCohort8701) is CLEARLY losing on the bot's forward record — clearlyLosing8969: n100+, 75 %+ resolved, mean
  + 2 SE below zero (stricter than the 8703 door's one SE). The record is live: a cohort that stops losing is read again.
- Never skipped: a coin inside an armed rule fire's window, a pocket recovery (PocketRecovery8961, watched or not), a held
  position; and never the DECISION, EVIDENCE, EXIT, HOLD, EXEC or RUG_VERIFY reads. Nothing here refuses a buy; the cheap
  price marks the labels learn from are not chain-confirm reads and are unchanged.
- discoverySkipped8969{use:cohort=n} in the brief.

## 155. Each trader scans its own assets (8970)

Owner, 24 Nov 02:16: "you flipped the scanners the wrong way. meme trader is solana network in its entirety plus sol stable
coins. the crypto trader is sol stable coins and everything else considered crypto. markets is stocks, leverage trades, real
stocks the defi markets gold silver etc"; asked, he placed established Solana majors (JUP, RAY, JTO, PYTH, BONK, WIF,
FARTCOIN, LSTs) with the crypto trader, xStocks and gold / silver with Markets, wrapped BTC / ETH and other-chain crypto
with crypto. (This replaces 8789's "the crypto trader trades whatever it wants, including what the meme side watches".)
- Measured: 8789 offered every coin the meme side watched (ingestMemeWatch8789) or hunted (ingestMarketHunt7807) to the
  crypto universe, so its desks spent their scans on meme coins — edge_research 24 Nov: 684 of CRYPTO_CORE's 1,394 coins
  were pump.fun mints, median cap $41k-$172k, judged at a 2.6 % crypto cost and a crypto hold.
- DynamicAltTokenRegistry.cryptoAsset8970: the crypto trader's universe is its curated majors (static listings, wrapped BTC /
  ETH among them), any other chain's coin, stablecoins, and an established Solana coin (NetworkTier8873's tier: $1 M+ cap,
  or a day old and $100 k+; an unknown age counts only through the $1 M cap). A Markets asset (TokenizedAssetRegistry:
  xStocks, PAXG, silver) is never the crypto trader's. A coin the desk already holds is always managed.
- Applied where the crypto trader picks: the blended queue (which also feeds its fresh channel) and the resident channel;
  where it draws: the SOL mark-rescue batch no longer fetches meme coins' prices; and where it ingests: a meme-watch coin
  only from a $100 k+ pool, a hunted coin only once a day old with a $100 k+ pool.
- The meme stack is unchanged. cryptoUniverse8970[crypto memeLeftToMemeTrader ingestRefused] in the brief.

## 156. Every pocket graded under its lane's exits (8971)

Owner, 24 Nov 02:41: "the latest build still has a wall of gates and vetos stopping the good trades getting to buys ... all the
lanes across both traders are meant to have tailored set ups to suit their pockets". 5.0.8968 capture (02:19, 10 min).
- Lane pockets: LanePocket8934 graded every lane's coins on the raw held hour. Research (edge_research 24 Nov 02:39, 27.4 h,
  each coin once per lane, the miner's own rules: single features and pairs, 30+ coins, both halves positive, mean without the
  best three past the family-deflated z) — proven cells, held hour -> under the lane's own exits: CORE 9 -> 28, MOONSHOT
  15 -> 27, SHITCOIN 22 -> 28, EXPRESS 15 -> 28, MANIPULATED 12 -> 27 (best: off pump.fun & forecast +10 %+ n92-99 +49..+54 %,
  ex-top-3 +42..+48; BASE_START & forecast +10 %+; SHITCOIN's LAUNCH_CONTINUATION & forecast +10 %+ n42 +61 %). The coin is
  now filed at its net under the lane's exits (BasePocket8904.doctrineNet8910: the stop first, then the doctrine line from
  +10 %, else the hour, less the round trip), as the base pocket already is. A row without its low / peak read keeps the
  hour's net; crypto lanes keep the held net. exitsGraded8971 in lanePocket8934.
- Not proven, either way, in this file: QUALITY, BLUECHIP, CYCLIC, DIP_HUNTER, PROJECT_SNIPER, TREASURY, CASHGEN and every
  CRYPTO_* lane, at 1 h, 4 h and 24 h (established meme lanes lose: QUALITY 24 h n1,205 -19 %; crypto lanes' medians sit at
  -2.7..-3 %, their own round trip — the coins barely moved and the fee is the loss). Nothing is printed as proven for them.
- A proven pocket answers the lane's own cell prior: pocket coins' moves were withheld because the lane's cell record was
  losing (stillWaiting8966 MOONSHOT 13, CRYPTO_EXPRESS 8, CRYPTO_MOONSHOT 5) and pocket coins refused CELL_PROVEN_LOSING_8136
  (LANE_POCKET 8, FDG 6). The cell (source x lane x cap x age) is a lane-wide prior that names no strategy (8240); an
  agreement / base / lane pocket is proven over a narrower group, so it answers it as 8724 / 8874 already let it answer the
  origin cohort, buys-the-high and stage priors: no CELL_PROVEN_LOSING refusal, the cell is not kept out of the edge, and the
  8965 fallback may price it. The launch rule's fire is not included (its live record 0 of 10, §153). Safety, structure, the
  selected setup's own losing record, timing, economics and realised losses still decide. pocketAnswersCell8971 in the brief.

## 157. The pool fee is the quote's (8972)

Owner, 24 Nov 02:50: the established and crypto lanes "need a complete reassessment and rebuild ... theres money to be made
everywhere". Joined research (edge dataset x edge research, 13,650 decisions with the coin's chart state AND the hour's peak,
low and close; each coin once per lane):
- Under each lane's OWN exits (its stop, then the doctrine line from +10 %) every coin these lanes decided on averaged:
  QUALITY n721 +4.6 %, BLUECHIP n617 +5.2 %, CYCLIC n349 +5.0 % (medians -3.5..-6.4: runner-carried). Their base-forming
  coins not minted on pump.fun paid: QUALITY n32 +23.7 % (median +6.1, 59 % up), BLUECHIP n31 +22.2 % (+6.0, 70 %), CYCLIC n26
  +38.2 % (+17.7, 73 %) — but those are $16k-$118k coins, outside QUALITY's $75k-$1M and BLUECHIP's $1M+ bands.
- Inside their own bands they lose to cost: QUALITY n175 -1.7 % (median -2.9), BLUECHIP n91 -1.9 % (median -2.6); TREASURY /
  CASHGEN n21-23 (too thin); a coin a week old moves less than the round trip in an hour. Crypto lanes: medians -2.7..-3 %,
  their own round trip, at every horizon.
- The 4 h and 24 h reads (ResearchFollow8884) record only the end price — no peak or low — so no exit can be graded at the
  horizons where established and crypto coins actually move. That is the next data step.
- This build: the cost. NetEdge8030.componentsPct8151 charged every non-pump.fun pool a flat 0.25 % a side
  (PaperVenueCost7287 AMM_FEE_PCT) while the deep Orca / Raydium CLMM / Meteora pools these lanes trade charge 0.01-0.05 %.
  Every Jupiter quote names each hop's fee (routePlan[].swapInfo.feeAmount in feeMint units, beside its inAmount /
  outAmount); RealisedSlip8418.poolFeePct8972 sums a single chain of hops (a split route, an unpriced fee mint or a missing
  field gives null and the model stays; floored 0.01 %, capped 5 %), filed per mint for 6 h, and a non-pump.fun pool is
  charged it. The pump.fun curve and PumpSwap keep their published schedule. venueFee8972[quoted modelled] in the cost line.

## 158. The path, not just the end (8973)

The established and crypto lanes trade coins that move over hours. Their research rows at 4 h and 24 h (ResearchFollow8884)
held only the end price: peakPct / minPct / heldPeakPct null on every such row, so no stop, target or trail could be graded
where those lanes actually move (§157: at the hour, in-band QUALITY / BLUECHIP medians sat at their round trip).
- Each waiting decision now keeps its path: the highest and lowest plausible USD price seen since the decision, seeded with
  the labeler's own hour high / low (ForwardReturnLabeler7731 bookTwoForty7944 -> note8884), then sampled every 15 minutes
  (SAMPLE_GAP_MS_8973) — one Jupiter price batch per 50 distinct coins (a coin waiting in several lanes is asked once), the
  longest-unsampled first, at most 6 calls a pass (a pass a minute) — and the read price itself.
- The 4 h and 24 h rows carry it: peakPct = heldPeakPct = the path's high, minPct = its low, gross % from the decision's
  price. It is carried from the 4 h wait to the 24 h wait and persisted with the wait (hi / lo / ps; a wait written before
  8973 starts unsampled).
- Sampled, not tick-exact: a move inside 15 minutes is not seen, so a stop is graded on sampled lows. Stated, not hidden.
- pathSampled8973 in researchFollow8884. Next: the lane pocket miner grades the established and crypto lanes at 4 h / 24 h
  under their own exits once a day of these rows exists.

## 159. 8971's two pinned strings (8974)

8971's CI: two source pins (Aate8723PayingCellTest, Aate8965PocketMoveTest) still named the edge-exclusion and fallback
lines as they were before a proven pocket could answer the lane's own cell prior. Both now name the 8971 lines; the paying
cell (8723) itself is not a POCKET_KINDS_8971 kind and is still kept out of the edge on a losing lane cell. No code change.

## 160. 8972's pool fee called on the wrong object (8975)

The runtime builds of 8972-8974 failed to compile: NetEdge8030 called RoundTrip8418.quotedPoolFeePct8972, but RoundTrip8418.kt
holds two objects and the pool-fee code sits in the second, RealisedSlip8418, beside noteQuote8418 (which Costs8369 already
calls as RealisedSlip8418.noteQuote8418). NetEdge8030, the 8972 test and §157 now name RealisedSlip8418. Every other symbol
added since 8970 (the last green build) was checked to resolve in its own object.

## 161. A crypto ticket that can clear its fees (8976)

Owner, 24 Nov 04:09: "its meant to be sizing aware. it can lift the min size to .05". The crypto desk asked 6 % of the balance
times its conviction multipliers: on a 0.26 SOL wallet ~0.016 SOL, where the fixed legs alone (0.000253 SOL a round trip,
roundTrip8418) were ~1.6 %; established coins move ~0 % in an hour and +1.5-1.8 % in a day (§157). Its admission was also
costed at the meme desk's last ticket (~0.043 SOL), not its own.
- CryptoAltTrader.cryptoTicket8976 (after the CryptoBrain size, before the canonical submit): an ask at or above 0.05 SOL
  (NetEdge8030.CRYPTO_MIN_TICKET_SOL_8976; fixed legs ~0.5 %) is asked as sized; a smaller ask is lifted to 0.05 only when
  the wallet holds four tickets (0.2 SOL+) and the sizer wanted at least a quarter of it. The lifted ask is the planned
  size, so the stop's loss budget is judged on it.
- CanonicalSizingBridge6532: the crypto desk's minimum executable is 0.05 SOL (live and paper alike). A smaller ask — one
  its evidence shrank, or from a wallet too small to hold four tickets — then meets the resolver's own rules (fundability,
  6909 conviction, 7877 live edge, 8188 loss budget) and is skipped rather than bought at a size that cannot pay its fees.
- NetEdge8030.entryCost8209: a crypto lane's admission is costed at the larger of its own ticket and 0.05 SOL.
- CRYPTO_TICKET_LIFTED_8976 / LEFT_BELOW_MIN_8976 / AT_OR_ABOVE_MIN_8976 counters.

## 162. The forward outbox is indexed (8977)

Owner, 24 Nov 04:20 / 04:25: "we need to move things off the main stream that dont need to be on main stream" / "we were
meant to stop the garbage before it starts". The 24 Nov 04:13 CPU sample on 5.0.8970 (30 s): ForwardReturnLabeler7731's
tick 3.99 s, the labeler's largest cost and the app's top allocator:
- completeHour8397 → bookTwoForty7944 → ForwardOutcomeOutbox8404.firstRevisionForHorizon8521, 1.88 s: every hour grade
  walked every held row (capacity 18,000) and built a fresh "<episode>|R|" String per row;
- drainForwardOutcomes8404 → ForwardOutcomeOutbox8404.drain, 1.62 s: every tick copied and sorted every key, then built
  two more lists to rotate it.
ForwardOutcomeOutbox8404 now keeps two indexes beside its rows, maintained by the only two places that add or remove one
(hold8977 / drop8977): sorted8977 (the drain's key order; the batch is the keys after the cursor, then from the start, at
most `limit` of them) and revisions8977 (each episode's revision ids in the order they were held). firstRevisionForHorizon8521,
supersedeRevisions8418 and rowsFor8418 read the episode's own ids. Same rows, same order, same answers (Aate8977 runs a
long random sequence against the pre-8977 outbox); nothing about what the Cortex learns or when changes. An episode or
prefix that is not of the "<episode>|R|" form still takes the old scan.

## 163. The research follow-up runs in the lab (8978)

Owner, 24 Nov 04:20: "we need to move things off the main stream that dont need to be on main stream". ResearchFollow8884
(§73, the 4 h / 24 h re-reads and, since §158, the 15-minute path samples) decides no trade, yet it ran in the trading
process: up to 30,000 waits held (9.5 MB on the 23 Nov 19:34 heap map), the whole list re-encoded as JSON and rewritten about
once a minute (any pass that read a coin or sampled a path marks it dirty), and every Jupiter answer parsed there. An
Android process has its own heap and its own garbage collector, so that work now runs in LabProcess8906's process
(":lab8906", 8906/8907), and its garbage no longer stops the trading threads.
- Trading process: ResearchFollow8884.note8884 writes the wait as one line (encode8884) into a small buffer; each minute
  the buffer is written as one file filesDir/lab8978/follow_in_<ms>_<seq>.rows (written whole, then renamed). The same tick
  files every finished research row the lab sent back (follow_out_*.rows, oldest first, each file deleted once read, so a
  row is filed at most once) through ResearchRows8877.note8877 — the pockets (Base/Agreement/Lane), the authority table and
  edge_research_8877.jsonl see the same rows as before — and asks Android to keep the lab following
  (LabProcess8906.pingFollow8978; a killed lab restarts on the next ask).
- Lab process (kind FOLLOW_8978): ResearchFollow8884.startInLab8978 runs the unchanged pass (load, path samples, due reads,
  the same file research_follow_8884.jsonl) every minute, after taking in the waits sent; rows go to follow_out files; its
  status line goes to follow_status_8978.txt. The service is not stopped while the follow runs.
- Fallback: if the lab writes nothing for 10 minutes (FALLBACK_MS_8978) the trading process marks itself owner for the run
  (the lab then neither passes nor saves), loads the lab's saved waits plus the unsent ones, and follows itself exactly as
  before 8978 (RESEARCH_FOLLOW_LAB_FALLBACK_8978). Each app start tries the lab again. Without an app (unit tests) the
  follow runs in-process as before.
- Brief: the lab's own researchFollow8884[...] line, then followLab8978[where sentIn buffered bufferDropped rowsOut
  rowsBack fellBack pingFailed channelFailed].

## 164. The card shows the exit that fires (8979)

Owner, 24 Nov 05:27 (tur, MOONSHOT, live): "profit locks aren't firing". The card read "STOP / LOCK: lock +60 %" at a +65 %
peak while the position sat at +19.3 %. That lock was FluidLearningAI.getDynamicFluidStop at a 20 % default (the popout,
the open-position card on both its build and reuse paths, and the "TARGET Peak · lock" badge), a number no exit of a meme
position uses:
- since 8630/8769 a meme lane's (RunnerDoctrine8584.MEME_LANES_8584) profit exits are the doctrine's; the tick's fluid lock
  defers to it (BotService doctrineOwns8769), and the doctrine trail (BotService.doctrineTrail8630, in the rapid stop
  monitor) sells at the doctrine line once the peak reaches +25 %: below 2x the lower of half the peak gain and the peak
  less the lane's stop distance (never under +5 %), from 2x a 45 % give-back;
- since 8941 a launch rider (a pump.fun mint bought within 15 minutes of its resolved birth) holds every profit exit above
  its lane stop until a 10x peak, where its trail arms.
The 05:29 capture (5.0.8978) shows it: tur was bought 05:24:26 in EXPRESS (a doctrine lane) at ageMin 6-7, liq $2.9k,
mcap $11.3k — a launch rider; runnerDoctrine8584 held=198, of them FULL:MOONSHOT:RAPID=187 (the rapid monitor's profit exits,
each held above the lane stop below 10x); it was sold 05:28:25 by THIN_LIQ_EARLY_RUG_BACKSTOP_-10 (never held) at +6.16 %.
RunnerDoctrine8584.lockText8979 now gives every card the doctrine's own exit: "rides to 10x · SL x %" (a rider below 10x;
MOONSHOT's tick stop MoonshotTraderAI.stopFor when it has one), "SL x % · trail from +25 %" (not yet armed), "trail +y %"
(armed: the line the trail sells at). The fluid "trail z %" text is not shown for these positions. Lanes the doctrine does
not own show what they showed before. Display only: no exit changes.

The launch replay behind 8941 (13 launch-hour exports, 14-20 Nov, every pump.fun launch entered at its 20th print, 10 s
bars; three fill regimes: next-bar fill 3 % cost / 20 s lag 3 % cost / 20 s lag 4 % cost with every sell a bar late at that
bar's low), average result per trade:
- live 8941 rider (stop 25, rungs and trail from 10x): +11.0 / +6.9 / -4.1 %
- the card's kind of lock (peak less 5 points, all of it, from a +50 % peak): +5.8 / +1.0 / -10.8 %
- the doctrine line from +25 %, all of it: +7.8 / +4.0 / -7.7 %; from +50 %: +8.4 / +4.0 / -7.7 %
- banking a third / half / two thirds at the line from +50 %: +10.1 / +9.7 / +9.2 (ideal), +5.9 / +5.4 / +4.9 (lag),
  -5.3 / -5.9 / -6.5 (harsh)
- a floor at +5 % once the peak reached +50 %: +11.5 / +5.2 / -6.4 %
Entered at tur's age (6 min after birth, n2898): ride +4.2 / +5.4 / +1.5 %, the line from +50 % +3.2 / +3.5 / -0.9 %,
half at the line from +50 % +3.7 / +4.5 / +0.3 %, a +10 % floor from +50 % +3.8 / +4.8 / +0.4 %, the card's kind of lock
+1.1 / +2.2 / -2.2 %.
On these paths no profit lock below 10x beat the rider rule once fills lag; the measured cost of a lock is the runners it
sells on their way to 4x-10x.

## 165. A crypto coin's hour is its pool's hour (8980)

Owner, 24 Nov 05:42: "dig into all of it please" (crypto: no trades). The 05:29 capture (5.0.8978, 11 min): CRYPTO_ALT
canonical blocks DATA_INCOMPLETE_DEFER_8136=196, ROUTE_UNAVAILABLE=21, EXECUTION_ECONOMICS=15, LIVE_NON_POSITIVE_EXPECTANCY=12;
on 5.0.8970 at 57 min DATA_INCOMPLETE=220 and DATA_NEVER_ARRIVED_8418=200 (waits that expired). The brief's deferCause8700
showed only its top 12, all meme lanes, so no crypto cause was named.
The cause in code: CrossAssetCortex7931.tokenStateFor builds a fresh TokenState for each crypto candidate. Its hour move
(NetEdge8030.executableMove8153) reads that state's candles (CryptoLaneDesk7391.observedCandles8834: one per desk scan,
in memory, none for the first 15 minutes after a start) and its hour change (LanePlaybook7907.resolvedChg1h8399). The bridge
(bridgeCryptoObservedContext8834) wrote the pool's five-minute change but never its hour, although the registry holds it
(DynToken.change1hPct8886 / trendObservedAtMs8886, DexScreener / GeckoTerminal h1, read by CryptoTrend8886). So:
- the move had no hour: sparse candles defer (RANGE_SPARSE / HISTORY_UNOBSERVED -> DATA_INCOMPLETE, then DATA_NEVER_ARRIVED);
- every chg1h setup read the 0.0 default as an observed flat hour (chgObs8084 keys on the five-minute stamp).
8980: the bridge writes the pool's h1 as CHANGE_1H_PCT (PROVIDER rank, its own observation time) when the candidate's price
is validated and the h1 is finite, above -100 % and under 15 minutes old (cryptoHourReadable8980); otherwise nothing, as
before. A positive hour then prices the move (basis H1, the same expectedMove8035 every meme coin uses) and the economics
test decides it; a falling hour on a momentum lane is the existing falling-hour wait.
Brief: cryptoHour8980[bridged refused unread], and deferCause8700 now ends with crypto8980{...}, the crypto / EVM lanes' own
top 10 causes.

## 166. 8980's test on a real mint (8981)

8980's CI failed one test only (Aate8980CryptoHourTest.aFreshProviderHourIsTheCandidatesHour: expected 4.2, was 0.0): its
test mints held a "0", which is not base58, so DynamicAltTokenRegistry.solanaMintOf8866 refused them and the candidate's
price was never validated (priceAt 0), exactly as the production bridge refuses an invalid mint. The test now uses valid
base58 mints; no production code changed.

## 167. The heap maps itself before it runs out (8982)

Owner, 24 Nov 12:11: "nope. much worse". The 11:52 capture is 5.0.8978 (8979-8981 not yet installed), 278 s after a
restart: 5.0.8978 died of OutOfMemoryError at 3 h 22 min (HeliusCallbacks7863: a 32-byte allocation, heap at its 512 MB
limit), and its last live close before that was 09:01 (2 h 47 min with no trade while the heap filled). Today's live closes
05:19-09:01: 2 wins in 18, mostly SCALP_HARD_STOP_8351 / THIN_LIQ_EARLY_RUG_BACKSTOP / tick floors on MOONSHOT.
Every heap map so far was taken by hand inside two hours (259-454 MB); none shows what holds the heap at the limit. The
named holders in the eight maps are flat over time (cortex ledgers 10-16 MB, finality exclusions 10-17 MB, grade book
6-9 MB); what grows is the unattributed part ("no holder found" 78 MB at 101 min, loops 39-59 MB) and garbage.
8982:
- MemoryGuard7977.noteHardTrimOutcome8144: a hard trim that freed nothing calls HeapMap8866.maybeAuto8982(fracAfter);
  autoDue8982 = not taken this run, uptime >= 30 min, heap still >= 85 %. The map is taken once a run ("auto-pressure"),
  saved to Downloads/AATE like the owner's, and kept in app storage, so the next report's HEAP MAP section carries it
  after the process dies.
- Every map (the owner's too) is read in the lab process (LabProcess8906 kind HEAP_MAP_8982: the dump path and header go
  over, the map text and a stats line come back; the lab deletes the dump it read). If the lab does not answer in 8 min,
  the owner's map is read here as before; an automatic one is deleted unread (LAB_NO_ANSWER_8982), never read in a full
  trading heap. The raw dump stays a secret exactly as in 8866: private cache, deleted in a finally and at every start.
- Taking the dump stops the app (8.3 s at 320 MB on 24 Nov 05:29): once a run, on a heap already collecting most of each
  second. Brief: heapMapAuto8982[taken readInLab] under the HEAP MAP section.

## 168. A dead coin is a research row (8983)

Owner, 24 Nov 12:11: "nope. much worse". Live closes 05:19-09:01: 2 wins in 18. The admissions behind them: runnerAdmits8297
byDoor MOONSHOT|SPECIFIC_EDGE_PROVEN_8136 n18 w4 -6.7 %, SHITCOIN n7 w0 -10.3 %; the proven pockets they rest on read
+44 % to +51 % at the hour (lanePocket8934 EXPRESS stage=BASE_START&pred=10+ n83 +48.5 %, MOONSHOT n85 +49.9 %).
The 11:52 authority table (c4efc95a) shows why the two disagree: its 1 h cells hold **0 unpriced reads in every lane**
(MOONSHOT n1158, EXPRESS n901, CORE n1264, SHITCOIN n1042, MANIPULATED n773) against 9 % at 4 h and 15 % at 24 h, while the
labeler's own hour tallies lost 26-28 % of meme decisions (lanes60: MOONSHOT lost=340 of 1300, EXPRESS 356, CORE 353,
MANIPULATED 369; crypto lanes 22-62 %). ForwardReturnLabeler7731 wrote the hour row only for a decision it could price at
the hour: a flat mark returned before ResearchRows8877.note8877 (bookTwoForty7944), and a window released without its hour
read (the pending sweep) only tallied the loss; neither started the 4 h / 24 h wait. Every pocket (LanePocket8934,
BasePocket8904, AgreementPocket8874's research rows) was mined on the coins still priced an hour later. The hour medians in
those same tallies are negative in every meme lane (-3.7 % to -7.9 %); the means are carried by runners.
8983:
- both lost paths call ForwardReturnLabeler7731.lostHour8983 -> ResearchFollow8884.noteLostHour8983: the decision waits
  for the hour (Wait8884.hour8983, encoded "h1"; a wait saved before 8983 is a 4 h wait), is read by Jupiter like every
  4 h / 24 h read, writes its hour row (reason tagged LOSTMARK8983/), then waits for 4 h and 24 h as usual. Only pools a
  buyer could take (tradeable8923). The read is taken when the loss is noticed (at or after the hour), not at the hour's
  exact second; that is stated in the row's tag.
- ResearchRows8877.rowNet8983: a pocket reads a row's net, or -100 % for an unpriced read (NO_PRICE_JUP_8884: Jupiter cannot
  price the coin: nothing could be sold), the convention the authority table already uses for its PAYS verdict. Late reads
  stay out. LanePocket8934.file, BasePocket8904.rowOf8904 and AgreementPocket8874.researchRow8929 use it.
- AgreementPocket8874's label record (coins8895, from the labeler's booked labels) is unchanged and still holds only the
  booked coins; its research record now carries the lost ones.
Expect fewer proven pockets once the lost coins are filed: a pocket stays proven only if it pays with its dead coins
counted. Brief: researchFollow8884[... lostHour8983{noted priced unpriced} ...].

## 169. The shadow does not choose live exits (8984)

Owner, 24 Nov 13:50: "its gone from 50% winrate now down to 21% and losing money where it was making money".
The live journal (every trade export, meme lanes, LIVE closes per position):
- 22 Nov to 23 Nov 01:00 (5.0.8932-8954): 15 closes, 8 won, +0.087 SOL. The winners were the lane doctrine's exits: spike
  capture (GTA6 +113 %, SANTA +164 % / +294 %, KYC +95 %), trailing stops (+28 %, +14 %), the green floor (+16 %).
- 23-24 Nov (5.0.8955-8978): 36 closes, 4 won, -0.148 SOL. Exits: SCALP_* 15 (1 won; SCALP_HARD_STOP 11 of 11 lost,
  -0.048 SOL), thin-pool backstop 6 (1 won), hard floors 4, catastrophic 3.
- The turn is the hour the explore shadow adopted SCALP (ExploreShadow8347.bestFor8351, read lane-wide by
  liveExitStyle8351): MOONSHOT adopt=- at 22 Nov 19:34, PULLBACK/SCALP on the niche M:MC_LT10K at 23 Nov 01:32, NOW/SCALP on
  S:FRESH_DEEP_POOL_8720 from 24 Nov 05:29; EXPRESS NOW/SCALP from 23 Nov 22:58; both still adopted at 24 Nov 12:16. One
  niche's shadow record moved every live position of the lane onto the scalp ladder (+20 % half, +40 % all, 10-minute clock,
  a stop at 0.6 x the lane stop): the +100 %-+400 % captures that made 22 Nov are impossible on it. The same shadow book read
  MOONSHOT NOW/SCALP n300 -9.9 % and EXPRESS NOW/SCALP n300 -3.6 % lane-wide.
- Market regime (memeRegime8546) was NEUTRAL with a -2 % to -13 % 45-minute mean in both periods.

Change: ScalpExit8351.liveScalp8984 — the shadow's SCALP choice is advisory on a LIVE position, as 8683 made its entry
choices: counted (SCALP_EXIT_ADVISORY_8984_<lane>, scalpAdvisory8984[...] in the brief), never traded; the position keeps the
lane doctrine (RunnerDoctrine8584, spike capture, trails), which is what the position card (8979) already shows. The ladder,
its stop (8363) and its rung book are unchanged and still trade the paper book (MemePaperTrader8544) and the shadow's SCALP
legs. The shadow's live-modelling leg (ShadowAlign8366 pairs) now reads the exit live trades (RUNNER).

Measured, not changed here: entries moved down the cap range (22 Nov: 8 of 9 MOONSHOT / EXPRESS buys at $100k+; 23-24 Nov:
14 of 36 meme buys under $30k, 1 won, -0.061 SOL), carried by the lane pockets (served MOONSHOT 41 at 23 Nov 01:32, 1,290
at 24 Nov 05:29). The pockets' proven cells are almost all origin=OTHER (off pump.fun) at the hour; on the 24 Nov 02:41
research rows, off-pump.fun BASE_START coins read at both 1 h and 4 h were unpriced by Jupiter at 4 h in 663 of 802 cases
(pump.fun: 0 of 248). 8983 counts an unpriced read as a dead coin; the next day of rows says whether those pockets still
prove.

## 170. The door answers for its live money (8985)

Owner, 24 Nov 21:44: "trades are only sparse. win sizes are tiny. crypto nothing stocks etc nothing ... how does the bot
interpret this data in a live state and then tune off it?? self discovery and tuning learning and self discipline are an
inherent part of what aate is." The 21:27 capture (5.0.8984, 103 min):
- Live window 69 closes, 19 won, PF 0.59. Paper book filed by what LIVE decided on the same candidate: LIVE_ADMIT negative in
  every meme lane (MOONSHOT n35 -9.5 %, SHITCOIN n5 -22.8 %, EXPRESS n4 -33.2 %, CASHGEN n48 -7.0 %, TREASURY n10 -7.6 %;
  CORE n10 +4.7 % the exception).
- The learners that admit live (lane pockets, base pocket, agreement, lane triggers: SHITCOIN PROVEN admits=1334, EXPRESS
  1221) prove themselves on labels and research rows. The live money of what they admit was graded (EntryReceipt8500 byDoor,
  read-only) and the only live-close refusal, LiveTruth8064's setup book (6 h, n>=5), never reached n on any setup:
  setupRefused=0 at 11:52 and 21:27.
- Cortex grading is starved (label marks priced 436 of 2902, graded 9 of 746) and FDG blocked most qualified coins
  (MOONSHOT 7/249, EXPRESS 1/262, SHITCOIN 0/182); the door table reads those refusals PROTECTING (refused cohorts' forward
  means -1.3 % to -13.7 %).

Change (LiveDiscipline8985): every LIVE admission is filed under its door — lane + admission reason, and the market-cell
evidence it carried (NATIVE when none) — the landed buy (EntryReceipt8500.onLiveBuy8500) holds that door, and the position's
canonical finalized close (honest era) files its realised net under both. A door is judged on its last 20 live closes within
72 h (the fine door once it holds 6, else lane + reason): n>=6 and mean + SE < 0 = PROVEN_LOSING. Such a door keeps exactly one
live position open (the probe, LIVE_DOOR_PROBE_8985_*); another admission through it while the probe is open is refused,
specific, as LIVE_DOOR_PROVEN_LOSING_8985. It re-opens by itself when its closes stop proving a loss. A door with
mean - SE > 0 is named PAYING. Paper and shadow rehearsals are untouched. Brief: liveDiscipline8985[...].

Datasets: every dataset over 24 MB is also saved as "<name>.gz" (the same lines, gzip) beside the plain file in
Downloads/AATE (ReportCopyDialog8198.saveGzip8985): the launch-hour export (~200 MB) and the 75 MB files can be uploaded.

Measured, not changed: 3 of 66 live loss stops sold in profit (MANDY +20.0 %, tur +6.2 %, Trencher +9.7 % on
RAPID_HARD_FLOOR_STOP after the exit quote refuted the same breach twice, SIGNAL_INVALIDATED_QUOTE_8521); crypto twins
CRYPTO_ALT n400 -2.4 %, CRYPTO_SPOT n400 -0.2 %, the Hyperliquid paper desk 222 trips, 60 won, net -$84.50 — their own records
keep them off live.

## 171. A chain read is a price on every venue it can prove (8986)

Owner, 24 Nov 21:57 / 22:02: "last night this morning the bot was finding hundreds of good trading pockets that showed great
profits" ... "its also meant to reprice on chain immediately when ever needed ... go further". The 21:27 capture (5.0.8984):
- The pockets are there (pocket8610 paying 1,868 -> 2,797 cells since 23 Nov; base pocket n137 +35.0 %; agreement n178
  +42.7 %; on the research rows the base pocket's coins read +43.0 % mean, 80 % up, n487 at the hour) and their coins do not
  become trades: LANE_POCKET 244 coins -> 50 admitted -> 7 FDG-allowed -> 7 tickets -> 1 buy; RUNNER_MODEL 184 -> 2 -> 0;
  LAB_STRATEGY 137 -> 1 -> 0; AGREEMENT 13 -> 0.
- Their first wait is PRICE_STALE (LANE_POCKET 89 of 146 waits; all lanes: MOONSHOT 611, SHITCOIN 456, EXPRESS 387, CORE 234).
  The chain is asked every time (ChainConfirm8950) and answers a price only where 8963 writes one: curve account 47,120,
  PumpSwap vault 1,429, Raydium CPMM 385. A PumpSwap vault read was withheld 3,406 times
  (PUMP_AMM_PRICE_AWAITS_VIRTUAL_QUOTE_8963: the pool's virtual quote reserves were known from heard swap events only, 173
  pools), and no concentrated venue (Meteora DLMM, Raydium CLMM, Orca Whirlpool) wrote a price at all, although 8418 decodes
  their state price for the buy door.
- Tickets: 3 of 10 pre-broadcast checks refused PRE_BROADCAST_IMPACT_BEYOND_SEALED_BUDGET_8153 on 0.04-0.07 SOL buys into
  pools of $8.9k-$47k.

Changes:
1. OnChainDepth8038: a PumpSwap vault read carries its pool account (readKeys8418) and pumpVirtualQuote8986 decodes
   virtual_quote_reserves from it (pump-fun/pump-public-docs idl/pump_amm.json, 21 Nov 2026: Pool disc
   [241,154,109,4,17,177,109,188], i128 LE @245; a shorter legacy account reads 0), noted to PoolVirtualQuote8952 before the
   read is priced: every PumpSwap read prices on effective reserves and writes its price; the stop's venue check
   (reserveVerdict8485) reads the same effective reserves.
2. A concentrated pool's decoded state price (ConcentratedPrice8418, layout-proved) is written as the coin's price through
   ChainPrice8963 (source CHAIN_STATE_READ_8986_<venue>, CHAIN rank, the read's time, the pool on it).
3. ChainConfirm8950.gapFor8986: a decision-path price/depth read (DECISION / LANE / EVIDENCE / EXEC) may be asked every 10 s
   (was 20 s against a 15 s hot-launch staleness bar); other inputs keep 20 s.
4. SizingQuoteRecheck8153.impactContradicted8986: the provider's impact field refuses only when the quote's own amounts do
   not contradict it (executable price within the impact budget of the sealed price = market move + impact + fees inside
   the budget). The fresh impact still raises the round trip in the net-EV check. statusLine8153 carries the last refusal's
   numbers (impact, sealed impact, quote vs seal).
Brief: chainReprice8986[...], chainPrice8963 written{STATE=...}; counter CHAIN_REPRICE_NO_KNOWN_POOL_8986 (a decision ping
on a non-pump coin with no pool to read — still named by the batched pair read).

Measured, not changed: of the base pocket's coins with a 4 h read, 98 % had no Jupiter price (n469) — the pocket is proven
at the hour only; 8964 (a clock prompts a reassessment, never a forced sell) stands, so no hour exit is added here.

## 172. The armed runner pick answers the lane priors (8987)

Owner, 24 Nov 22:14: "where's the runner capture gone???? ... tokens and coins moving thousands of percent a day and we still
entirely ignore those". Research rows to 24 Nov, one decision per coin per 10 min, read at 4 h with an unpriced read counted
-100 % (n3,435): mean -19.2 %, 2.6 % at 2x+, 1.2 % at 4x+. The 4x+ coins sit in pump.fun coins under a $5k cap (stage
UNKNOWN n228: +37.3 % mean, 6.6 % 2x+, 5.3 % 4x+; DUMPING <5k n133: +15.7 %, 4.5 % 4x+); every cohort from $5k up is negative
at 4 h and 24 h; off-pump BASE_START is 93-98 % unpriced at 4 h. The runner capture is therefore the launch floor:
RunnerModel8861 (the $5,000 cross) and the launch rule (8748).
21:27 capture: runnerModel8861 ARMED, forward picks n1984 +13.5 % +/- 3.5 (2x+ 9 %), live crosses 59, armed picks 38;
evidence funnel RUNNER_MODEL_8861 COINS=184 ADMIT=2 REFUSE=165 (STRUCTURAL_RISK 37, LIVE_STAGE_LOSES_8626 36, SAFETY 26 —
top-holder concentration) DEFER_DATA=54 (PRICE_STALE 54: 8986) EXEC=0.

Change: RunnerModel8861.KIND_8861 joins LaneAuthority8577.PRIOR_ANSWERING_KINDS_8874, so an armed pick answers the lane-wide
entry-state priors (LIVE_STAGE_LOSES_8626, LIVE_STAGE_UNREAD_8711, LIVE_ORIGIN_COHORT_LOSES_8703, MEME_BUYS_THE_HIGH_8582, the
residual cohort) exactly as the pockets (8874 / 8904 / 8934) and the launch rule (8949) do. The evidence is served only while
the model is armed. Unchanged: safety, RUG_PRONE structure, economics, the buy door, a named losing setup, LP rule, RugCheck.
The bought pick is a launch rider (8941) and its door answers for its live closes (LiveDiscipline8985).

## 173. The base meme ticket is the cost-clearing ticket (8988)

Owner, 24 Nov 22:14: "increase the base meme coin trade size as you see fit. obviously still scales with wallet size."
21:27 capture (5.0.8984): wallet 0.2773 SOL; LiveConcentrationDoctrine7697 positionSol=0.0626 (8714's cost-clearing ticket,
23 % of tradeable), routableMin=0.0428; order size resolver req=0.03240 -> final=0.04280 OK_LIVE_ROUTABLE_MIN_PROMOTED_7841;
binding ROUTE_MIN_PROMOTED=15 of 18 opens; tickets sent 0.044-0.068 SOL. The fixed round trip (0.0016 SOL) is 3.7 % of a
0.0428 ticket and 2.6 % of a 0.0626 one.

Change: FinalDecisionGate's canonical notional (6552) passes its shaped size through
LiveConcentrationDoctrine7697.baseFloored8988: a LIVE meme ticket is not shaped below the doctrine's position; a candidate
whose admission door is proven PAYING on its own live closes (LiveDiscipline8985.doorPaying8988: n>=6, mean - SE > 0) takes
1.25x of it. Bounded by the FDG ceiling (0.5 SOL), MAX_SHARE_7697 (half the wallet), MAX_POSITION_SOL_7697; an absolutely
pinned size (size cap, micro probes) and paper are untouched; depth / impact caps, cash and slot limits downstream are
unchanged. It scales with the wallet through the doctrine's share (the larger of 1/20 and the cost-clearing share).
Brief: Concentration doctrine ... memeBase8988[lifted=.. payingDoorSized=..]. Risk note: at this wallet one ticket is 23 %
of tradeable, so four fill it; a door proven losing on live is held to one probe by 8985.

## 174. A label is marked on every venue the chain proves (8989)

Owner, 24 Nov 22:14: "it has everything already built for self evolution and rediscovery its just not tied in correctly."
Audit of the 21:27 capture (5.0.8984), what feeds what:
- Forward labels feed every learner (Cortex seats, lane playbooks, the edge model, the research rows the pockets mine):
  label marks due 2,902, priced 436 (15 %), noSource 619, late 1,572; Cortex assessed 746, graded 9;
  FORWARD_LABEL_LOST_MARK_7731 2,211. The chain rung marked a label only from a constant-product vault read; a PumpSwap
  read was unpriced until a swap was heard (8986) and a concentrated venue never marked one.
- Live closes fed the setup book (never reaching n) and a read-only door ledger: tied to the admission doors in 8985
  (probe discipline) and to size in 8988 (a paying door sizes up).
- The proven evidence (pockets, launch rule, armed runner model) was refused by the lane-wide priors it is a narrower proof
  of, and waited on a price the chain had already answered: 8986, 8987.
Change here: ConcentratedPrice8418's decoded state price (Raydium CLMM / Orca Whirlpool sqrt price, Meteora DLMM bin, read
with the pool's vaults) is a label mark at its own observation time — ForwardReturnLabeler7731.stateMark8989 in the tick's
candidates and the horizon pricer's chain rung, OnChainDepth8038.statePriceUsd8989 behind chainPriceUsd8540 (the off-watch
batch). Source POOL_STATE_PRICE_8989; counted on horizonPricer8583 (stateMarks8989). No gate reads it; more labels are
graded on the venues the pockets' coins trade on.

## 175. The proven pockets ship with the build (8990)

Owner, 24 Nov 22:14: "the proven pockets. shouldn't they become from trade one spec logic once nailed down?? ... a bot that
starts super super smart and very good at trading then just gets better and better over time."
The three pockets (LanePocket8934, BasePocket8904, AgreementPocket8874) are mined from the research hour rows in
files/edge_research_8877.jsonl. That file is on the device: it survives an update, so the owner's phone restarts with its
pockets, but an install with no file (a new phone, cleared data) starts with none and needs about a day of its own rows.
Change: assets/research_seed_8990.bin (named .jsonl.gz until 8992, §177) — the owner's 13,022 hour rows of the meme and hour lanes, decisions 22 Nov 04:38
to 24 Nov, diagnostics dropped, the same line format (640 KB). ResearchRows8877.forEachSeedLine8990 streams it; each pocket
reads it before the device's file (LanePocket8934: with no device file yet the seed alone makes the book ready). Same
miners, same proof bars (time-split halves, mean - SE, the 8983 unpriced-read rule). Windows keep their newest coins (lane
3,000 a lane, base and agreement 200), so the device's own rows join and then displace the shipped ones.
Brief: researchSeed8990[linesRead=...]. Limits, stated: the seed is two days of one market; a shipped pocket is evidence
through the same gates, and its door answers for its live closes (LiveDiscipline8985). On the same rows the base pocket's
coins were unpriced by Jupiter at 4 h in 98 % of cases (§171): it is an hour-horizon pocket.

## 176. A pocket's range is every horizon it proves at (8991)

Owner, 24 Nov 23:00: "we need to extend the time range of the pockets where appropriate please". Measured on the 24 Nov
research export (64,519 lines) with the lane pocket's own rule (30+ coins, both time halves positive, mean without the
best three > z SE, z deflated over the lane's cells; held net, an unpriced read -100 %, gain cap +400 %):

| horizon | lanes with a proven cell | lane means |
|---|---|---|
| 1 h | MOONSHOT 1 (origin=OTHER&v3=35+ n127 +26.6 %), CORE 1, EXPRESS 2, MANIPULATED 3 | -8 % to -17 % |
| 4 h | none in any lane | -29 % to -46 % |
| 24 h | none in any lane | -27 % to -51 % |

The hour-proven off-pump cohort (off pump.fun, v3 35+ or BASE_START; n202, +25.4 % at 1 h): at 4 h 158 unpriced, the 44
priced -9.2 % mean / -4.9 % median. No pocket is extended on today's evidence.
Two ranges were short and are extended:
1. Data span: ResearchRows8877 kept 45,000-60,000 lines, set when a decision wrote one row; with the 4 h and 24 h rows the
   file spanned 2.3 days, so every pocket saw two days and a 24 h row had a day of history. Now 180,000-210,000 lines
   (about a week, ~90 MB on the device; the export is also saved gzip-compressed since 8985).
2. Horizon: LanePocket8934 files each non-crypto lane's 4 h and 24 h reads in their own books ("LANE@4h", "LANE@24h") and
   mines them hourly by the same rule. A coin in no cell of the lane's own hold that sits in a cell proven at a longer
   horizon carries it as LANE_POCKET evidence, id "LANE8934:<LANE>@<h>:<cell>". Brief: lanePocket8934 ... extended8991{...}.
Unchanged: the hour cells and their grading under the lane's exits (8971), base and agreement pockets (hour), every gate,
and a held position's clock (a time exit holds only on the coin's own live metrics, HoldSupport8964).

## 177. An unpriced read is not a loss (8992)

The 23:24 capture on 5.0.8990 (705 s up): every lane pocket "proven{... none}", base pocket proof NONE (halves +38.4 / -1.9),
agreement research NONE, researchSeed8990[linesRead=0]. Two causes, both this session's own changes:
1. §168 (8983) scored a read Jupiter could not price as -100 %. Measured on that capture's research file, the pockets' own
   cohort (off-pump, v3 >= 35, decided since 24 Nov): 74 rows the labeler priced, mean +25.1 % (69 % up); 46 lost-mark rows
   Jupiter did price, mean +165.9 %, median +2.2 % (57 % up); 169 lost-mark rows Jupiter could not price. Jupiter fails on
   about 79 % of this cohort against about 9 % elsewhere: coverage of young pools, not death. The -100 buried every pocket.
   Change: ResearchRows8877.rowNet8983 returns NaN for an unpriced read — not filed by any pocket; counted in
   researchUnpriced8992[notFiled=...]. Offline, the same file with the lane rule and unpriced reads unfiled: at 1 h
   CORE 11 proven cells, MOONSHOT 10, EXPRESS 10, MANIPULATED 11, SHITCOIN 6 (best: origin=OTHER&stage=BASE_START n98-118,
   +38 % to +45 %, +29 % to +35 % without the best three, 8-9 % at 2x+, 2-4 % dead); QUALITY, CYCLIC, CASHGEN, TREASURY 0.
   At 4 h and 24 h no lane proves a cell on priced reads either (lane means -23 % to -39 %).
2. The seed asset read 0 lines on the device. It is renamed research_seed_8990.bin (no build step treats it as an
   archive), the reader goes by the stream's own first two bytes (gzip or plain), and the line reports the last read:
   researchSeed8990[linesRead=N last=ok | FAILED <exception> | no app context].
Corrections to earlier sections: §168's "a pocket stays proven only if it pays with its dead coins counted", §171's and
§175's "unpriced at 4 h in 98 %" as evidence the coins were dead, and §176's table (computed with unpriced = -100 %) all
rest on the same imputation. What stands: 4 h and 24 h prove nothing on priced reads; what does not: that unpriced means
dead. Limit, stated: priced-only reads can flatter a cohort if the unpriced coins did worse; the fix for that is pricing
them from the chain (the hour mark does since 8989: ForwardReturnLabeler7731.stateMark8989), not a guess of -100.

## 178. The chain prices what Jupiter cannot (8993)

Owner, 24 Nov 23:43: "we have helius and other sources we can reprice off. a one time chain confirmation when needed is
authorised". §177 left an unpriced read unknown; on the 23:24 file that is about 79 % of the pockets' own cohort, so a
pocket proved on the coins Jupiter covers. A coin's price is on the chain whatever an aggregator lists.
Path: ResearchFollow8884.pass8884 (lab process, Jupiter price v3) writes a NO_PRICE row -> it now carries the decision's
USD price and sampled path (e8993, pk8993, lo8993) -> the trading process's sink file8993 holds it and asks the chain once:
ChainConfirm8950.confirmMint8950(use RESEARCH, PRICE), OnChainDepth8038.requestMint8540 (pools known by the mint),
needCurve8039 (pump.fun curve) -> the next minute's tick (settle8993) reads OnChainDepth8038.chainPriceUsd8540 (vaults, or
a concentrated pool's state price) else CurveReserves7951.priceSolFor7951 x SOL/USD, answers at most 180 s old ->
finish8993 writes the row with net / gross against the decision's price less the same cost, reason "CHAIN_8993:<reason>".
No answer inside 110 s, or a ratio over 100x (plausible8923): the row is written unpriced as before.
The asks ride the existing batchers and credit economy (PoolBatch8418, HeliusCreditEconomy7881); at most 2,000 rows held.
AuthorityTable8887 reads a CHAIN_8993 row as the same reason. Brief: chainRead8993[asked priced unpriced implausible
holding overflow]. Stated limits: the chain read is up to two minutes past the horizon; a held row is in memory only; the
15-minute path samples between reads are still Jupiter's; a coin whose pool the bot never learned stays unpriced.

## 179. The base breakout on the launch tape (8994)

Owner, 24 Nov 23:53: "there are real clean multiple hundreds to thousand % trades we are ignoring even the 30% to 200%
trades should be bread and butter for aate". The LP-lock rule stays (owner: "we got drained 5 times").
Measured, clean coins only (LP locked 70 %+ or on the pump.fun curve):
- research hour rows: 1,125 clean coins, 190 peaked +30 %+ inside an hour of a decision; 132 of them lane-admitted. Held to
  the hour the clean coins lose (-11.5 % mean), so the learned doors refuse the movers with the rest.
- edge dataset (36,656 meme decisions, 15 min): 14,431 one- and two-feature cells on clean coins, none proves.
- launch-hour export, 25 Nov (3,302 pump.fun launches, 10 s bars): of launches printing at 30 s, 54 % later peaked +30 %,
  31 % +100 %, 8.7 % +400 %. Fixed targets lose (TP +30 % / stop 20 %: -1.7 %, 36 % winners); letting winners run pays
  (TP +200 % / stop 20 %: +13.1 % +- 1.3, both days positive, 22 % winners); every result flips sign on worst-price fills.
- shapes on those bars (runner line, 2.9 % cost): pullback reclaim +0.1 %, higher-low push +6.4 %, V reclaim +9.2 %, all
  negative on worst-price fills. Base breakout: 16 settings, both halves positive in all, 54-74 % winners; wide n226
  +20.4 % (ex-3 +13.3, halves +20.5 / +20.3, 57 % winners, worst fills +0.2 %); tight n82 +25.7 % (65 %, worst +4.6 %).
What was not tied in: MemeStrategies8546.BASE_BREAKOUT_FLOW reads this shape on 8-20 closed one-minute bars (a launch has
none: m1 empty 2,123 / short 419 / ready 77 on the 23:24 capture) and is paper-only, and paper is off in LIVE.
Change: SignalTape8828 gains B8994W and B8994T (baseBreakout8994 on the tape's 10 s print bars). Path: every pump.fun print
-> LaunchSniper8748.signalPrint8828 -> SignalTape8828.onPrint -> a fire; SignalReplay8828 grades both on the phone's stored
launch hours at the bot's latency with the replay's fills (stops 20 % / 35 %, DOCTRINE ladder) and arms one only when
PROVEN (n >= 100, ex-top-3 mean - SE > 0, both halves positive); an armed fire runs the coin's pipeline at once and is
LAUNCH_RULE-class evidence through admission and every safety door. No gate added or loosened. Brief: signalReplay8828
[... B8994W / B8994T ...], launchSniper8748 signals8828{ARMED|SHADOW ...}.
Stated: the offline figures are 10 s bars, two days, one market; the phone's print-level grade decides, and the same replay
graded the owner's other 15 signals at -8 % to -28 % ex-top-3 on 24 Nov.

## 180. The base breakout past the first hour (8995)

Owner, 25 Nov 01:40: "do that now bro" — the base breakout for graduated and off-pump coins, not only launches.
No offline figure exists for these coins: the exports hold print bars for a launch's first hour only, and nothing on the
phone stores an older coin's prints, so there is no replay. The shape is therefore proven FORWARD on the phone.
Path: every witnessed print (TradeTape8100.launchEvidence8750: pump.fun curve, PumpSwap pools, the DEX feeds) ->
BaseBreakout8995.onPrint8995 (a pump.fun curve coin inside its first hour is left to the launch tape, §179) -> the coin's
SignalTape8828 (10 s bars from the first print heard; B8994W / B8994T) -> a fire opens a VIRTUAL position: entered at the
first print after LaunchSniper8748.requiredDelayMs8749, held on the runner line (stop 20 %; from +10 % the lower of half the
peak gain and the peak less the stop distance, never under +5 %; from +100 % a 45 % give-back), filled at the crossing
print, closed at 60 min or after 10 min without a print, net of FieldManual7715.roundTripCostPct7766 -> the close is filed
under "<CURVE|PUMPSWAP|OTHER>|<setting>" (last 400, persisted as BASE_BREAKOUT_8995) -> graded by SignalReplay8828.stats8828:
PROVEN at n >= 100, ex-top-3 mean - SE > 0, both halves positive.
Only a PROVEN record arms: its fire adds the coin to the watchlist, runs its pipeline (LaunchSniper8748.onArmed8949) and is
market-cell evidence BASE_BREAKOUT_8995 for 120 s (CandidateAdmission8136; LaneAuthority8577.PRIOR_ANSWERING_KINDS_8874).
Hard safety, the LP-lock rule, structure, sellability and economics are untouched; nothing here buys.
Brief: baseBreakout8995[coins prints fires armedFires open closes records{...}].
Stated: a virtual fill is the print's price (no own impact); the ladder's partial sales are not modelled; at most 600 coins
are followed; a coin the bot hears rarely makes few bars. Crypto is not covered: the desk has hourly candles, not prints,
and its own breakout setups (majorSetups8910) are graded on the twin.

## 181. The thin-pool backstop asks the venue (8996)

Owner, 25 Nov 02:32: "cuts trades waaaay to quickly. fires in and out". The live journal since the scalp fix (8984), 11
closes: 6 inside 100 s. Three sold far above the level their stop names:
| time | coin | exit | held | filled |
|---|---|---|---|---|
| 25 Nov 01:15 | DE6jZSwe MOONSHOT | THIN_LIQ_EARLY_RUG_BACKSTOP_-10 | 31 s | -2.2 % |
| 24 Nov 20:53 | BWtNaVHG MOONSHOT | RAPID_HARD_FLOOR_STOP | 97 s | +9.7 % |
| 25 Nov 01:52 | Hb4kF1Ly EXPRESS | RUNNER_STRUCTURE_BREAK_7967_1M_0PCT | 89 s | price +1.8 % (-3.3 % net) |
Cause of the first: Executor's thin-pool backstop fires when the worst of the position's marks reads -10 % / -15 % and the
pool reads under $5k: a price reading. StopMarkConfirm8418.priceLossStop8489 excluded any reason containing "RUG", so this
stop skipped stopRefutedByVenue8489 / sellsWithoutRouteQuote8706 and one bad mark sold the bag. Change: reasons starting
THIN_LIQ_EARLY_RUG_BACKSTOP are price-loss stops; the venue read decides as for every other stop (refuted above half the
depth -> hold and re-ask; unread -> 6 s, own-venue mark, 30 s bound, at once from -60 %). Rug, drain, LP-pull and dev-dump
exits are unchanged. The second predates 8986 (PumpSwap vault-only pricing, fixed there). The third is one instance: the
runner's 1-minute structure break has no gain condition (the 15 s one needs +50 %); left as it is, stated.
Measured on the launch-hour export with a latency-true fill (a level seen at a bar's close fills at the next bar's close):
- stop width barely moves the result on launches: runner line, stop 5 % +12.0 %, 10 % +12.8 %, 20 % +13.4 %, 25 % +14.1 %
  (SE 2.5); 60-75 % of launch trades close inside 120 s at every width (median hold 40-60 s) because most launches die
  fast. Fast exits on losers are this market; a false trigger is the fault.
- CORRECTION to §179: with that fill the base breakout (wide) is +3.4 % +- 5.9 at a 20 % stop, ex-top-3 -3.9 %, halves
  -3.4 / +10.4, 31 % winners. §179's +20.4 % / 57 % winners filled stops at the line itself. It is not shown to beat buying
  the launch blind (+13.4 %, halves +10.0 / +17.0, 29 % winners). B8994 / 8995 stay unarmed until the phone proves them.

## 182. Who holds the pool (8997)

Owner, 25 Nov 03:54: "it was making great trades on friday ... ive now chased that behaviour for 4 days"; 04:06: "approve".
What the journals in this chat show (builds and live windows from every capture):
| capture | build | live window |
|---|---|---|
| Fri 20 Nov 11:22-20:53 | 8882-8894 | PF 5.9-8.6, expectancy +0.009..+0.0145 SOL |
| Sat 21 - Sun 22 02:17 | 8898-8923 | PF 2.8-3.8 |
| Sun 22 13:59-23:03 | 8932-8947 | 13 W / 8 L, PF 4.41 |
| Mon 23 22:58 | 8961 | 15 W / 26 L, PF 0.90 (19 closes, 2 wins since 01:32) |
| Wed 25 03:51 | 8996 | 20 W / 59 L, PF 0.56 |
Friday's result is one trade: INU (DQKmfaSk), SHITCOIN, bought 11:03:59 at a $129k cap, 0.0426 SOL; SPIKE_CAPTURE_7943
banked +98 % (3 min), +404 % (6 min), +927 % (11 min), the rest closed +355 %: +0.164 SOL. The other 16 closes that day lost.
INU: off pump.fun, stage BASE_START, pool $3,918, LP lock unread. The paying-cell LP record: PENDING n63 +37.0 % (20 Nov),
n84 +28.7 % (21 Nov), n185 +39.8 % (22 Nov), LP_BLOCKED n0 on all three. PoolLock8895 (8895 Fri 19:10, 8925, 8937 Sun
14:38) then read those pools: METEORA_DLMM:UNLOCKED 131 of 143 (24 Nov), LP_BLOCKED n193 +30.3 %, PENDING +10.7 %. The
cohort that paid is the one the LP rule now refuses, because its lock can now be read and is not there.
Change (measure only): PoolLock8895.spreadFor8997 -> readSpread8997 reads a DLMM pool's positions with their owners
(PositionV2 owner @40) and its bin arrays (BinArray lb_pair @24, bins @56 x 144, amount_x @0 / amount_y @8; LbPair
token_x_mint @88 / token_y_mint @120; offsets computed from MeteoraAg/dlmm-sdk idls/dlmm.json) and gives the largest share
of the pool's quote side one wallet can remove (unlocked positions; per bin, its shares over the bin's shares).
LpStateRecord8725.noteLpBlocked8937 files each LP-blocked coin as "LPSTATE8725_LP_BLOCKED:<band>/<class>" with class SPREAD
(3+ owners, none over 30 %), CONCENTRATED, ONE_HOLDER (70 %+), NO_QUOTE or UNREAD (not DLMM, over 24 positions, or no answer
in 20 s). The forward labeler follows them and AuthorityTable8887 grades each class as its own door cell.
Brief: poolSpread8997[read{...}] lpBlockedSpread8997{...}. Two getProgramAccounts a pool, only for LP-blocked coins, a read
kept 10 minutes. Nothing is bought; LiquidityOwnership8522 and the 70 % rule are unchanged.
Stated: distinct wallets are not proven distinct people (the owner's objection of 20 Nov); DAMM v2 positions are held by
NFT and are not read here; whether SPREAD pools pay and survive is what the next captures will say.

## 183. The holder read runs on its own (8998)

Owner, 25 Nov 12:03: "still very choked out, losing money ignoring the good trades". The 12:01 capture on 5.0.8997 (34 min up):
poolSpread8997[read{-} held=0 waiting=12], lpBlockedSpread8997{UNREAD=3} against 59 LP refusals at intake. Two faults, both
§182's: (1) the holder read ran only inside a LOCK read of the same pool that started after the LP record asked, but the LP
door asks the lock read first and PoolLock8895 throttles a pool for 30 s, so no holder read ever ran; (2) a coin was filed
only when it came back to intake after the read, and a hard-refused coin rarely comes back.
Change: PoolLock8895.requestSpread8998 / readHolders8998 run on their own queue (one thread, 32 queued, a pool re-asked at
most every minute): the pool account (must be DLMM and paired with SOL/USDC/USDT), the chain clock, the positions' owners
(32-byte slice), then the full positions and bin arrays (readSpread8997) when there are at most 64 positions. A read that
cannot judge still answers with why: NOT_DLMM, NO_QUOTE, NOT_THIS_COIN, NO_POSITIONS, WIDE_UNWEIGHED (over 64 positions,
3+ owners), CONCENTRATED (over 64, under 3 owners). LpStateRecord8725 reads the pool the LP rule blamed (the deepest non-curve
pool of the safety report under 70 % locked, else the coin's pair), holds the coin in pending8998, and files it when the read
answers (spreadReady8998) or as UNREAD after 60 s. The label is "LPSTATE8725_LP_BLOCKED:<band>/<class>", its forward mark
taken when it is filed (up to a minute after intake).
The authority table on the same capture (1 h, 955 cells): ONE door PAYS, LPSTATE8725_LP_BLOCKED_LT30 n157 +33.7 % mean,
+26.4 % median, ex-top-3 +26.5 %, halves +30.9 / +36.5; the same cohort at 4 h: median -25.5 %, ex-top-3 -98.4 %. Every
other door with n >= 20 is UNPROVEN or LOSES (runner-tail cells with large means and negative medians). Nothing is bought.

## 184. A proven launch rule answers the cell prior (8999)

Owner, 25 Nov 14:54 (MINELIUS +22,000 %, MAYA +9,000 %, NOHR +2,000 %, JENSEN / OpenIndex / Singularity / RIEMANN +1,000 %,
from a web summary): "inexcusable that we have missed these let alone the others that have run".
The 12:01 capture (5.0.8997) runner report: 46 coins ran 4x+ in 24 h, held 2 (paper), missed 44, never_detected 0; refused by
safety 13, lane liquidity floor 7, LP_BLOCKED 6, execution economics 3, CORE ensemble 2, TREASURY role floor 2 (JENSEN +684 %).
Live runner admits: bought the top 101, gave back 18, won 22. Live closes bought under a $15k cap (all exports): 12, 1 win,
-0.046 SOL, held 31-259 s, mostly cut by THIN_LIQ_EARLY_RUG_BACKSTOP_-10.
The phone's launch replay: forward8856 cap$5k buys>=3 tp5x stop35% DOCTRINE +20s n5366 mean +6.0 +- 1.7 %, ex-best +5.6 %,
22 % winners, 7 % at 2x+, PROVEN; armed. sniper8748 fires armed=20, armedLanded none. LAUNCH_RULE_8748 evidence funnel:
COINS 20, REFUSE CELL_PROVEN_LOSING_8136 9, DEFER_DATA 6, SAFETY 4 (top-holder concentration), FDG economics 2.
Change: CandidateAdmission8136.POCKET_KINDS_8971 gains LaunchSniper8748.KIND_8748 and BaseBreakout8995.KIND_8995, so an armed
launch fire or an armed base breakout (carried: its record clears cost, supported8136) answers the lane's cell prior.
8971 had excluded the launch rule on its live record, 0 of 10 (23 Nov); P(0 of 10 | 22 % win) = 0.08, and LiveDiscipline8985
(since 8985) holds a door proven losing on live closes to one probe at a time. Safety, data, structure, the selected setup's
own record, timing, economics and live discipline are unchanged; no gate added.

## 185. A curve has no LP to pull (9000)

Owner, 25 Nov 15:01: "fix the back stop". Executor's THIN_LIQ_EARLY_RUG_BACKSTOP (4187 / 4551) sells at -10 % when the pool
reads under $5k in a position's first 10 minutes, or -15 % under $2.5k at any age, to get out before an LP pull. Live it
cut 9 positions (all exports, 20-25 Nov), 7 of them pump.fun mints, at -2 % to -19 % (one at +6 %). Forward labels of the
coins it cut: 4V77Yjpr sold -14 %, 1 h +283 % (peak +286 %), 4 h -65 %; DE6jZSwe sold -2 %, 1 h peak +90 %, 4 h -61 %;
Gs47zYRE sold +6 %, 1 h peak +41 %; HVzypN16 sold -19 %, 1 h -73 %; 6Ncsox6o sold -3 %, 1 h -49 %. (Labels run from the
decision, not the fill.)
A coin on an active pump.fun curve has no LP anyone can withdraw: the curve is the pool and a falling curve reading is
selling, which the position's stop and the -25 % catastrophic backstop (corroborated, every lane) already read.
Change: when CurveReserves7951.realSolIfActive8046(mint) answers (a fresh reading of an active, ungraduated curve) the
backstop does not sell; label THIN_LIQ_BACKSTOP_LEFT_TO_CURVE_STOP_9000. Graduated and off-pump coins keep it, venue-checked
since 8996. The lane hard -15 % stop (MANIPULATED / SHITCOIN / EXPRESS), dev-dump, drain and LP-pull exits are unchanged.


## 186. The proof is read at the coin's own age (9001)

Owner, 25 Nov: "the runner capture needs to stop buying the tops". Source: aate_edge_dataset_8537 exported 25 Nov 12:01
(50,586 decisions labelled at 15 minutes, 6,047 coins, 70 hours) and the four live trade files (113 closed live positions,
43 matched to a decision row within 10 minutes).

| admitted decisions | n | mean net | reach +40 % | halves |
|---|---|---|---|---|
| coin under 15 min, cap $6k-$30k | 1,682 | +30.2 % +- 2.4 | 29.8 % | +27.8 / +33.4 |
| coin under 15 min, cap $30k-$150k | 479 | +37.8 % +- 17.3 (capped +11.5) | 17.5 % | +52.8 / +5.8 |
| coin 15-60 min, cap $6k-$30k | 247 | -5.0 % +- 3.1 | 8.9 % | -4.7 / -5.4 |
| coin 1-24 h (all caps) | 947 | -6 % to -7 % by cap | 2-5 % | all negative but one cell |
| coin over 24 h, cap $6k+ | 1,998 | -2.9 % to -3.9 % | 0.2-1.8 % | all negative |

Coins an hour old or more: 23,448 rows, 1,472 coins, mean -3.0 % +- 0.2; no band of chg5m, chg1h, drawdown, run-up, tx
velocity, buy pressure, liquidity, model prediction or v3 score pays on both halves. Live: 30 of 43 matched buys were
MATURE-phase coins (3 wins, -0.085 SOL); 7 EXPANDING (5 wins, +0.041 SOL). Receipts: 45 of 54 live closes entered through
SPECIFIC_EDGE_PROVEN_8136, 36 of them LOSS_BOUGHT_TOP. The receipt's run-up and peak bands do not separate them
(<15 % run-up: 26 of 32 bought the top); age does.

Where the young edge sits (same export): off-pump pools read LP-unlocked, n 1,600 / 431 coins, +40.4 % +- 2.2, 74 % wins,
5.5 % lose 30 %+ (venue METEORA n 1,012, PUMPSWAP n 478) - the cohort the 70 % lock rule refuses; pump.fun curve coins
under 15 minutes, n 12,084, -0.8 %, except 30-80 unique buyers n 583 / 184 coins +35.8 % +- 8.1 (capped +16.7, halves
+41.1 / +29.2) and 80-200 holders n 343 +21.2 % +- 5.0 (halves +20.9 / +21.4). These two curve cells are candidates, not
rules: found by scanning columns, to be confirmed forward before anything trades on them.

Change (AgeProof9001): per lane, the admitted labels are kept by age band (UNDER_15M, 15M_1H, 1H_24H, OVER_24H; each label
capped at +300 %). gap = lane mean - (band mean + SE), floor 0, cap 25, needs n>=300 lane and n>=100 band. LIVE, the gap
is added to the realised shortfall for records pooled over every age - setup, setup timing, Cortex cohort, runner grab -
at the support tests only (Candidate8136.pooledShortfall9001). The cell (source x lane x cap x age) and the pockets carry
their own age and are not shifted; loss tests read raw records; nothing is added for a band that pays more. No terminal
is added: a record that no longer clears its bar is not proof for that coin, and the candidate continues to the lane's
ordinary checks. Built from EdgeModel8537.appendDataset and the dataset replay at start; memory only.
Report: ageProof9001{...} in the brief; entryReceipts8500 byAge9001{...}; label AGE_PROOF_GAP_9001_<band>.

## 187. Every lane mines where its winners form (9002)

Owner, 25 Nov 15:55: "this shouldn't just be a meme coin thing. this should be a whole aate stack thing. the big winners in
every trader lane specialist etc are what make this worth while". Sources: every aate_edge_research_8877 export in the
chat merged (190,369 rows, 9,241 coins, 20-25 Nov; hour rows 91,861) and every aate_edge_dataset_8537 export merged
(80,375 graded decisions, 9,691 coins, 12-13 and 22-25 Nov). A lane's read of a coin is counted once, at its first read.

Correction to section 186: "a coin under 15 minutes pays" holds on 22-25 Nov only. Admitted decisions on such coins, capped
+300 %: 12 Nov -13.5 % (n 1,262), 13 Nov -12.5 % (n 632), 22 Nov +13.5 %, 23 Nov +17.5 %, 24 Nov +18.4 %, 25 Nov +19.7 %.
Coins an hour old or more are negative on all six days (n 4,851). 9001 uses only the second fact.

Per lane, hour rows, each coin once (net after cost at the hour; "doubles" = peak +100 % inside the hour):

| lane | coins | net 1 h | doubles | peak +400 % |
|---|---|---|---|---|
| CORE | 5,090 | -8.6 % | 6.5 % | 1.3 % |
| MOONSHOT | 4,896 | -10.2 % | 6.6 % | 1.4 % |
| SHITCOIN | 4,812 | -10.6 % | 6.3 % | 1.6 % |
| EXPRESS | 4,806 | -11.3 % | 6.7 % | 1.5 % |
| MANIPULATED | 4,486 | -10.3 % | 7.2 % | 1.6 % |
| QUALITY | 3,724 | -11.7 % | 6.2 % | 1.2 % |
| BLUECHIP | 3,287 | -10.7 % | 5.8 % | 1.2 % |
| CYCLIC | 1,494 | -4.7 % | 8.9 % | 1.5 % |
| TREASURY | 1,367 | -14.0 % | 7.0 % | 1.5 % |
| CASHGEN | 741 | -13.7 % | 9.9 % | 1.8 % |
| CRYPTO_CORE | 2,203 | -6.8 % | 2.0 % | 0.3 % |
| CRYPTO_MOONSHOT | 1,410 | -9.6 % | 2.4 % | 0.1 % |
| CRYPTO_EXPRESS | 1,317 | -8.5 % | 3.1 % | 0.6 % |
| CRYPTO_QUALITY | 996 | -1.2 % | 2.0 % | 0.2 % |
| CRYPTO_BLUECHIP | 432 | -4.4 % | 2.1 % | 0.0 % |

Every lane is shown doublers at 2-10 % of its coins and nets a loss on the set: the winners are in the feed, the selection
and the hold are what lose. Where they form (13,366 lane reads joined to the decision row within 120 s, 2,402 coins,
22-25 Nov; meme lanes n 10,852):

- age: under 5 min 10.7 % double (n 6,213); 5-15 min 9.3 %; 15-60 min 6.1 %; 1-24 h 3.6 %; over 24 h 0.0 % (0 of 1,357).
- cap: under $6k 6.8 %; $6k-$30k 11.8 %; $30k-$150k 5.7 %; $150k-$1M 2.4 %; $1M+ 0.3 %. At 4 h a $1M+ meme coin doubles
  0.8 % of the time and at 24 h 0.9 %; CRYPTO_* reads of $1M+ coins 1.6 % at 4 h and 1.0 % at 24 h.
- holders 1,000+: 0.8 % double.
- stage: BASE_START n 1,572 / 352 coins +28.3 % +- 2.6 (14.0 % double). Outside the LP-unlocked cohort and under 15
  minutes: n 1,022 / 220 coins +21.2 % +- 3.2, halves -1.8 / +38.8 (second half only - not proven). Every lane's
  BASE_START read is positive (CORE +17.3, MOONSHOT +31.8, SHITCOIN +24.5, EXPRESS +18.6, CYCLIC +34.4, QUALITY +15.5,
  BLUECHIP +12.3, TREASURY +19.9), each with a first half at or below zero. CRYPTO_* BASE_START n 64 / 25 coins +57.0.
  Every other stage loses at every age: FRESH_LAUNCH -22.5, DUMPING -30.2, PEAK_EXHAUSTION -28.8 (under 15 minutes).
- the doublers' own low inside the hour (808 outside the LP-unlocked cohort): 63.2 % never traded 10 % under the entry
  read, 66.0 % never 15 % under, 72.8 % never 25 % under, 81.4 % never 50 % under. The order of low and peak is not in the
  row, so a stop at -15 % costs at most a third of them.

What this says about the lanes that hunt old or large coins (BLUECHIP $1M+, TREASURY / CASHGEN $1M-$5M, QUALITY, every
CRYPTO_* lane on established coins): inside a day their band holds almost no doubling coin. Their large wins are not
visible at the horizons the research rows hold (1 h, 4 h, 24 h); nothing here says where they form.

Change. The research hour row now carries the coin's age at the decision ("age", minutes, written only when known; the
labeler's observation holds it as age9002). LanePocket8934.features8934 takes that age (AgeProof9001's bands) and the 60 s
distinct-buyer count the row has carried since 8924 (buyers: <10, 10-30, 30-80, 80+) as two more features, alone and
paired with origin, stage, setup, V3, forecast, pool and cap - 45 cells a coin instead of 28 - for every lane the miner
reads, meme and CRYPTO_*, under the miner's unchanged proof (30+ coins, both time halves positive, the mean without the
best three > z SE). An unread value adds no feature, so the seed and the rows written before 9002 mine as before. A
candidate is matched on its own age (EdgeModel8537 raw) and current buyer count. No lane is handed a cell: each proves
its own from its own rows, and a cell needs rows written from 9002 on, so the first can appear after about a day of them.
The 4 h and 24 h follow rows do not carry the age yet.

## 188. A pocket is proof only where it was earned (9003)

Correction to section 186: it said the pockets "carry their own age". Only the admission cell (source x lane x cap x age)
does. A LanePocket8934 cell is built from origin, stage, setup, V3, forecast, pool and cap; until 9002 no age.

25 Nov 12:01 pipeline, the session's specific proofs let through: LANE_POCKET 2,340 (MOONSHOT 928, EXPRESS 601, SHITCOIN
590, CORE 221), setup 50, lab strategy 83, base pocket 9, launch rule 2. The pocket is the proof behind the live buys.
The same pipeline's funnel for coins carrying pocket evidence: 1,253 coins, 321 admitted, FDG allowed 35, 7 tickets, 1 buy.

The kind of cell a pocket holds, by the coin's age (22-25 Nov hour rows joined to the decision row; CORE, MOONSHOT,
SHITCOIN, EXPRESS, MANIPULATED):

| cell | under 15 min | 15-60 min | 1-24 h | over 24 h |
|---|---|---|---|---|
| origin=OTHER | +20.4 % (n 1,529) | -8.3 % (n 72) | -2.3 % (n 106) | -3.6 % (n 274, none doubled) |
| pool $5k-$20k | +13.6 % (n 731) | -21.8 % (n 157) | -10.7 % (n 206) | -4.5 % (n 213, none doubled) |
| forecast >= +5 % | +23.3 % (n 1,177) | -16.7 % (n 72) | -2.3 % (n 50) | n 23 |
| stage BASE_START | +32.2 % (n 1,090) | n 3 | n 4 | n 4 |
| origin=OTHER and forecast >= +10 % | +39.2 % (n 831) | n 8 | n 2 | n 5 |

Change: LIVE, LanePocket8934.evidence8934 does not serve a cell that names no age to a coin whose age band the lane is
measured to lose in on its own admitted labels (AgeProof9001.bandLoses9003: 100+ labels in the band, mean + SE under
zero). A cell that names the coin's age (9002) is always served. Paper is unchanged. No terminal is added; label
LANE_POCKET_AGE_BLIND_NOT_SERVED_9003_<band>. A band that stops losing is served again.

Other measurements taken for this build, no change made on them:
- exits on 2,527 pump.fun launch paths, bought at 30 s, 2.9 % cost: doctrine line (stop 15 %) +10.7 % +- 2.2; profit lock
  keeping 35 % of the peak from +10 % +9.5 %; keeping 50 % +10.3 %; hold to the hour +5.5 %; the doctrine line with a 5
  minute time exit +5.2 % and with a 15 minute one +7.6 %. 78 % of all profit comes from trades of +100 % or more. A
  trailing lock does not lose the runners; a clock does.
- live sell legs 12-25 Nov (155): median hold 3.2 minutes, 28 winning legs, median win +9.4 %, 2 legs at +40 % or more.
- BASE_START under 15 minutes outside the LP-unlocked cohort, one read per coin (220 coins): hold to the hour +12.1 %;
  with a stop, low assumed first: -10 % stop +23.9 %, -15 % +23.3 %, -25 % +22.5 %, -50 % +17.3 %; all sold at +40 %
  -2.3 %, at +100 % +4.0 %, at +200 % +11.5 %. By venue: Meteora +29.1 % (n 550, halves +13.5 / +40.3), PumpSwap +42.9 %
  (n 220, halves +1.8 / +78.5), pump.fun curve -12.8 % (n 206). Pool under $5k -5.2 %; $5k-$20k +58.3 %; $20k+ +31.7 %.
  Refusals inside the cell that went on to pay: NATIVE_MCAP_TOO_LOW n 149 +13.0 %, NO_KNOWN_MANIPULATION_EVIDENCE n 85
  +19.1 %, CORE_YIELD_CLEAR_SPECIALIST n 65 +27.7 %, SELLABILITY_REJECT n 58 +25.2 %, TREASURY_ROLE_FLOOR n 32 +12.7 %.
- FDG blocks, same pipeline (allow 68 / block 1,835): DATA_INCOMPLETE_DEFER 254, EXECUTION_ECONOMICS 224,
  LANE_REALISED_LOSING_PROBE_OCCUPIED_8424 204, EDGE_MODEL_NEGATIVE 141, SETUP_RECORD_LOSING 116, MOVE_HISTORY 101.

## 189. A lane's live record is read at the coin's own age (9004)

25 Nov 12:01 pipeline: realised8295 lanesLosing{SHITCOIN n6 / -8.6 %, EXPRESS n8 / -4.3 %, MOONSHOT n36 / -6.4 %, CASHGEN
n4 / -0.8 %}. A lane with 3+ live closes averaging under zero is held to one live position at a time
(LaneRealisedProbe8424), and a lane proven losing (10+ closes, mean + 2 SE under zero) to one probe every two hours.
LANE_REALISED_LOSING_PROBE_OCCUPIED_8424 blocked 204 FDG decisions in the session - the largest block that is not one of
the admission's own refusals (the others: NO_PLAN_WAIT 67, V3 floor 61, CHASING_SPIKE 42, brain veto 35, coin too slow 28,
timing record 27). The funnel for coins carrying pocket proof: 1,253 coins, 321 admitted, 35 allowed, 7 tickets, 1 buy.

That record was made at one age. Live buys matched to a decision row (section 186): 30 of 43 on coins past their launch
phase, 3 wins; 7 on coins still expanding, 5 wins.

Change: every graded live close also folds under laneage|<lane desk>|<age band at entry> (EntryReceipt8500, persisted with
the receipt book; report byLaneAge9004{...}). When a lane is shut on its record, a candidate is held to it unless, in the
candidate's own age band, (a) the lane has 3+ live closes averaging at or above zero, or (b) it has fewer than 3 and the
lane's admitted labels pay there (AgeProof9001.pays9004: 100+ labels, mean - SE above zero). A band with 3+ closes
averaging under zero, a band the labels do not show paying, and an unknown age stay on the lane's record: one probe at a
time, as before. Stake is unchanged (a lane without a positive realised record sizes at the route minimum), so the most
a fresh band can cost before its own record holds it is three small-stake closes. Label LANE_SHUT_NOT_AT_THIS_AGE_9004_<lane>.
Hard safety, the LP rule, economics, data and every record door are untouched. The lane-wide read used by the evidence
hold (no coin named) is unchanged.

## 190. The age record is filled at every start (9005)

Fault in 9001, found on the first 9004 capture (25 Nov 18:23, uptime 817 s): `ageProof9001{CRYPTO_CASHGEN[all=n4] CASHGEN
[all=n4]}` and `edge8537[... replay8642=not-needed]`. AgeProof9001 is memory only and 9001 filled it from the dataset
replay, which runs only when the edge model has to be rebuilt. On an ordinary start it began empty and saw 8 labels in 13
minutes, so the 9001 gap, the 9003 pocket rule and the label branch of 9004 (all need 100+ labels in a band) did nothing on
the device, and would have done nothing after any restart. Section 186's "built from ... the dataset replay at start" was
wrong.

Change: on a start that does not replay, EdgeModel8537 reads the labelled dataset once on its own background thread into
AgeProof9001 (seedAgeProof9005: the rows the replay accepts, admitted only). The report line starts `seed9005=` with
seeded:used/seen, replayed, no-dataset, failed or pending.

What the device's own dataset gives when read this way (export 18:23, 62,192 rows; admitted, filled, capped +300 %):

| lane | under 15 min | 15-60 min | 1-24 h | over 24 h |
|---|---|---|---|---|
| MOONSHOT (n 1,662, +6.0 %) | n600 +19.1 +- 2.7 pays | n111 -0.3 +- 4.5 | n306 -0.4 +- 2.0, gap 4.4 | n633 -2.0 +- 0.7 loses, gap 7.3 |
| EXPRESS (n 1,487, +7.4 %) | n879 +13.6 +- 2.0 pays | n94 | n159 -2.4 +- 1.8 loses, gap 8.0 | n343 -2.3 +- 0.8 loses, gap 9.0 |
| SHITCOIN (n 790, +13.6 %) | n655 +16.3 +- 2.6 pays | n59 | n76 | - |
| CORE (n 625, +7.4 %) | n313 +15.6 +- 3.4 pays | n49 | n79 | n182 -1.7 +- 1.1 loses, gap 8.0 |
| CASHGEN (n 330) | - | - | n42 | n270 -2.4 +- 0.3 loses |
| CYCLIC (n 148) | - | - | - | n148 -2.4 +- 0.9 loses |
| CRYPTO_MOONSHOT (n 439) | n39 | n52 | n91 | n205 -3.9 +- 1.4 loses |

Same capture, other readings: the launch rule landed its first live buy (sniper8748 armedLanded{n1 p50=16s}; funnel
LAUNCH_RULE_8748 COINS=21 ADMIT=3 FDG_ALLOW=2 TICKET=1 EXEC=1), and 18 of its 21 coins were refused by the hard holder
rule (top-10 wallets 35 %+ of supply). The holder read excludes the curve and venue vaults by program
(OnChainHolderConcentration7379); the 12 verdict lines in the 22-25 Nov captures are all complete reads. Not changed.

## 191. Unpriced is counted, not assumed (9006)

Owner, 25 Nov 18:32: "keep working thru all the blockers ... all trader and lane mentality across meme and crypto".

Every refusal reason in every lane, graded (all research exports merged: 98,076 hour rows, 8,475 coins, 20-25 Nov; one
row per lane x coin x reason; a reason is "refusing coins that pay" when its mean - SE is above zero and both time halves
are positive with 12+ coins each). Result: one reason of every lane's, meme and CRYPTO_*, meets it -
LPSTATE8725_LP_BLOCKED:LT30, n 194, +40.5 % +- 7.7. No other refusal in any lane refuses coins that went on to pay.
What the lanes ADMIT, same rows, at the hour: CORE n1,374 -5.4 %, MOONSHOT n2,541 -4.6 %, EXPRESS n2,967 -7.5 %,
SHITCOIN n1,650 -2.1 %, CASHGEN n105 -7.7 %, CYCLIC n207 -4.2 %, PROJECT_SNIPER n91 -21.4 %, CRYPTO_CORE n175 -0.1 %,
CRYPTO_EXPRESS n466 -5.2 %, CRYPTO_MOONSHOT n508 -9.8 %, CRYPTO_SHITCOIN n137 +0.6 % +- 7.7, CRYPTO_CASHGEN n62 -3.9 %.
The blockers are not what loses; what is admitted is.

And the one paying refusal is measured on survivors. LP_BLOCKED coins, each once: at the hour 219 priced (+42.2 %,
5.9 % at -80 % or worse) and 106 unpriced (33 %); at 4 h 14 priced, 316 unpriced; at 24 h 10 priced, 312 unpriced. Every
other lane loses 2-3 % of its coins to no price at the hour (CORE 5,234 coins, 5,090 priced). If an unpriced LP_BLOCKED
coin is a total loss the cohort's hour is (219 x 42.2 - 106 x 100) / 325 = -4.2 %. Sections 186-188 called it the largest
measured edge without this; it is the largest edge among the coins that could still be priced.

The 15-minute dataset has the same shape (18:23 export, admitted decisions, 15-minute horizon):

| cohort | n | unpriced | mean of the priced |
|---|---|---|---|
| pump.fun curve, under 15 min | 1,499 | 20.5 % | +5.0 % |
| pump.fun curve, 1-24 h | 798 | 22.2 % | -5.2 % |
| pump.fun curve, over 24 h | 1,444 | 23.5 % | -2.4 % |
| off-pump, over 24 h | 748 | 28.6 % | -2.8 % |
| off-pump LP-unlocked, under 15 min | 748 | 42.9 % | +47.0 % |
| off-pump other, under 15 min | 1,563 | 36.0 % | +14.9 % |

About 22 % unpriced is the feed's ordinary loss (coins days old do not die in 15 minutes). Young off-pump coins lose 14-21
points more. Whether the excess died is not measured: a young coin is rarely decided on twice, so "seen again later" does
not separate dead from unmarked (4-14 % of young coins are seen again whether priced or not). If the excess is counted as
total losses, LP-unlocked young is about +7 % and other off-pump young about -6 %; the pump.fun curve figure is unaffected.

Changes:
- AgeProof9001 counts an admitted decision with no price beside the priced ones (never as a return) and reports it per
  band (`/unpricedN%`); the start-up seed counts them too.
- 9004's label branch is withdrawn: a band is released from a shut lane's one-probe limit only by 3+ of the lane's own
  live closes in that band averaging at or above zero. A label mean over priced decisions does not lift a live limit.
- The spread reader's NOT_DLMM class names the program that owns the blamed pool (NOT_DLMM_<first 8>). Of the first 31
  classes filed by 8998, 20 were NOT_DLMM and 11 UNREAD, none a DLMM pair, though the lock reader found
  METEORA_DLMM:UNLOCKED on 85 selected pools in the same capture: the pool the safety report blames is a different pool
  from the one the gate selects. Which program those pools belong to is what the next capture will say.
9001 and 9003 are unchanged: they act only on bands that lose on priced decisions, where the unpriced share is ordinary.
