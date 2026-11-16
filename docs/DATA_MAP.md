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
