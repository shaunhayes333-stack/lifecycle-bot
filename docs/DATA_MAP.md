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
| **Facts** | `TokenState.facts8551` — one `TokenFacts.Fact(value, source, rank, observedAtMs)` per migrated field. |
| **Write path** | `engine/truth/TokenFacts.kt` — the **only** code that sets a migrated field. One writer per field (`writeLiquidity`, …). |
| **Legacy fields** | Mirrors of the fact (`ts.lastLiquidityUsd`, `ts.tokenMap.liquidityUsd`). Every existing reader keeps working and reads the store. |
| **Guard** | `Aate8551TokenFactsTest.noDirectLiquidityWritesOutsideTheStore` fails the build on any new direct write. |
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

Observation time: the provider's / chain's own time where the producer has it (vault read `atMs`, curve frame / account
`atMs`, route cache `updatedAtMs`, entry snapshot `capturedAtMs`); a clock more than 5 s ahead is clamped to now.

### Ranks

| Rank | Value | Sources |
|---|---|---|
| `CHAIN` | 3 | pool vault balances (`CHAIN_VAULT_8038`, `CHAIN_VERIFIED_8038`, `POOL_VAULT_8418`), pump.fun curve (`CURVE_ACCOUNT_7951`, `CURVE_FRAME_7951`, `CURVE_READ_8046`) |
| `PROVIDER` | 2 | `DEXSCREENER_WS`, `DEX_PAIR_POLL`, `DEX_BATCH_8460`, `BIRDEYE_OVERVIEW` |
| `AGGREGATE` | 1 | `INTAKE` (scanner figure), `OBSERVED_FILL_8037` (resolver / token map), `RAYDIUM_TVL_8128` (cached TVL stand-in), `CURVE_PRICE_IMPLIED_8173`, `TOKEN_MAP_CACHE_6513`, `ENTRY_SNAPSHOT_7382` |
| `RESTORE` | 0 | `META_CACHE_RESTORE`, `HIVE_RESTORE`, `POSITION_RESTORE` |

## 3. Fields

Status: **STORE** = migrated (one writer, guard on); **LEGACY** = still written in several places, scheduled below.

| Field | Canonical slot | Status | Producers (write through) | Decision readers |
|---|---|---|---|---|
| Liquidity (USD) | `facts8551[LIQUIDITY_USD]` → mirrors `lastLiquidityUsd`, `tokenMap.liquidityUsd` | **STORE (8551)** | `TokenFacts.writeLiquidity` ← `HydrationReeval8343.writeTick8345` (WS / poll / batch / Birdeye / Raydium / intake / verify), `onDepthRead8343` (every vault read on arrival), `LiquidityTruth8037` (curve frames, pool vaults, `hydrate8037`), restores | `LiquidityTruth8037.hydrate8037` = `TokenMapAuthority.observedLiquidityUsd` = the raw field (V3Adapter, HardRugPreFilter, ExecutableOpenGate, Executor, FDG, lanes, Field Manual) |
| Price (USD) + observation time | `lastPrice`, `lastPriceUpdate`, `lastPriceSource` | LEGACY | ~45 write sites | everything |
| Buy pressure, 5 m / 1 h change | `lastBuyPressurePct`, `lastPriceChange5m/1h` (+ `*ObservedAtMs8084`) | LEGACY | DexScreener pair / WS, PairFlow8169, DexBatchLane8287 (flow not written), TradeTape8100 | LaneInputTruth8236 (120 s), SetupFeatureResolver8169 (15 min) |
| Market cap / FDV | `lastMcap`, `lastFdv`, `tokenMap.marketCap` | LEGACY | scanners, TrustedMcap8019 (writes back) | lanes, TrustedMcap8019 |
| Birth / age | several | LEGACY | AdmissionBirth8144 witnesses, CanonicalTokenBirthTime7440, TokenBirthHydrator7441 | `readFor8144`, `resolvedAgeMinutes`, `launchAgeMs7767` |
| Top-10 holder share | `topHolderPct`, `tokenMap.topHolderConcentrationPct` | LEGACY | JupiterHolders8128, OnChainHolderConcentration7379 | 5 different orders |
| Mint / freeze authority | `ts.safety.*AuthorityDisabled` | proof lives in `safety` | OnChainMintAuthorityProof7248, TokenSafetyChecker | FDG, CanonicalFeaturesBuilder, Executor collapse guard (fixed 8551) |
| Candles | `ts.history`, `CandleCoverage8284.store1m` | LEGACY | candle producers | lanes |
| Curve / graduation / pool | `tokenMap.migratedOrGraduated`, `PumpCurveKeys7269.isGraduated7392`, pool fields | LEGACY | curve / pool readers | lanes, exits |
| Mint supply | `ts.mintSupply8418` | already single-record (the template for this store) | MintSupply8418 | cap, PnL |

## 4. Disagreements found (8551 audit) and where they stand

| # | Disagreement | Status |
|---|---|---|
| D1 | Liquidity: `TokenMapAuthority.observedLiquidityUsd` (curve → tick → map → reserves, no vault) vs `LiquidityTruth8037.hydrate8037` (curve → vault → tick → resolver) vs the raw tick vs the resolver | **Fixed 8551** — one fact, one rule, all three read it |
| D2 | Buy pressure / change: LaneInputTruth8236 120 s window vs SetupFeatureResolver8169 15 min; batch-lane flow never reaches the row | Phase 3 |
| D3 | Top-10 holder share read in 5 different orders | Phase 6 |
| D4 | Age: `AdmissionBirth8144.readFor8144` vs `CanonicalTokenBirthTime7440.resolvedAgeMinutes` vs `launchAgeMs7767` | Phase 5 |
| D5 | Price age: Executor stamps now; BotService route-cache stamps the cache time; others unclear | Phase 2 |
| D6 | Market cap: TrustedMcap8019 writes back into the row | Phase 4 |
| D7 | Executor collapse guard read `tokenMap.mintAuthority / freezeAuthority`, which **nothing writes** — the guard never saw a live authority | **Fixed 8551** — reads `ts.safety.*AuthorityDisabled == false` |
| D8 | Graduation: `tokenMap.migratedOrGraduated` vs `PumpCurveKeys7269.isGraduated7392` | Phase 9 |
| D9 | Candles: `ts.history` vs `CandleCoverage8284.store1m` | Phase 8 |
| D10 | The decision snapshot freezes TokenState fields only (not the fact's source / time) | follows each phase |

## 5. Migration order

One build per field family. Each build: the field gets a `TokenFacts` writer, every producer offers to it with source /
rank / time, the duplicate ladders are retired, every reader reads the fact (or its mirror), the guard test covers the
field, this map is updated, then tests + CI on main.

1. **Liquidity** — 8551 ✅
2. Price + observation time (D5)
3. Flow / changes (D2)
4. Cap / FDV (D6)
5. Birth / age (D4)
6. Holders (D3)
7. Safety authorities (D7 reader fixed in 8551; writer consolidation)
8. Candles (D9)
9. Curve / graduation / pool (D8)
10. Supply cleanup (fold MintSupply8418 into the same store)

## 6. Adding a producer or a reader

- **Producer**: call the field's `TokenFacts.write…(ts, value, "<SOURCE_NAME>", Rank.<X>, <observedAtMs>)`. Use the
  provider's / chain's own timestamp. Pick the rank from the table above; never call a cache or an archive anything but
  `RESTORE`. Add the source to the rank table here.
- **Reader**: read the field (or the reader function named in §3). Do not build a new fallback ladder; if a reading is
  missing, add its source as a producer instead.
- **Never** assign a migrated field directly. The guard test will fail and name the line.
