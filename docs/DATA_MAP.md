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
