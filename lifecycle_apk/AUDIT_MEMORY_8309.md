# AUDIT_MEMORY_8309: 473/512 MB, 406 MB unattributed, hard trims free nothing

Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. Branch `wt-gmem-8309`, from 5.0.8306 (`403a672d9`).

## Live evidence

- **5.0.8306:** heap 473/512 MB, peak 100%, pressure HARD. Hard trims recover nothing, and the census attributes about 67 MB, so 406 MB is unattributed. The census names `hiveBook`, `insiderTracker` and `chartLibrary` as untrimmed or unreached.
- **5.0.8302:** died of an OOM at 62 min. After the restart the heap was 91 MB, with miner=8551, colours=965, tokenArchiveResident=4011, mintRegistry=5004 and prefsTotal=6.4 MB.

## Why the hard trims freed nothing

1. **The trims reached only stores that were already small.** The three stores the census named are small and mostly fixed:
   - `chartLibrary` is a preallocated 40,000 x 137 B array, about 5.5 MB, and it never grows.
   - `hiveBook` was trimmed to its HARD cap of 30k keys. It was already under that cap, so it freed 0.
   - `insiderTracker` had no trim.

   They head the "unfreed" list only because the list is sorted by size. The heap was held by structures the census never counted.
2. **A pruned token row was not freed memory.** `pruneTokens8025` / `prunePressure8136` take the row out of `BotService.status.tokens`. They never touched the per-mint caches the evaluation path had filled for that mint (see the residue table below). Some caches keep their own copy of the row's candles: `SmartChartScanner.priceCache` holds 200 `PriceCandle` per mint. Their `clear*` / `cleanup()` helpers have no caller anywhere:
   - `SmartChartScanner.clearCache` / `clearAllCaches`
   - `RegimeTransitionAI.clearHistory`
   - `DistributionDetector.clearHistory`
   - `TokenSafetyChecker.clearLiquidityHistory`
   - `VolatilityRegimeAI.cleanup`, `OrderFlowImbalanceAI.cleanup`, `SmartMoneyDivergenceAI.cleanup`
   - `DataPipeline.cleanup`, `DistributionFadeAvoider.cleanup`
3. **Trim success was measured in entries, not bytes.** A store that dropped 5,000 small keys read as "freed". One that kept 300 mints with 14 KB each read as nothing. No report said how many MB each store released.
4. **The residue refilled continuously.** The 300-row token map turns over every 10 min. Every new mint fills the same caches, so the residue grows in step with intake: several thousand mints an hour with PumpPortal funded. It never reached a cap, because those caches had none.
5. **Possible holder that was never measured: queued launches.** `BotService` runs `scope.launch { … }` on fixed thread pools (6 service threads, 16 entry threads, 3 exit-policy threads) that sit on **unbounded** `LinkedBlockingQueue`s. A backlog keeps every queued closure alive, including the `TokenState` it captured, even after the row was pruned. Its depth was never reported; it is now (`execQ8309`, census `serviceQueues8309`).

## Per-mint residue: what each evaluated mint left behind

These caches are written on the evaluation path for each mint, and nothing removed them. Per-mint sizes are typical depths on ART. The "1k mints" column is what 1,000 departed mints held.

| Cache | Where | Per mint | 1k mints |
|---|---|---|---|
| Chart candle copy (200 x `PriceCandle`) | `engine/SmartChartScanner.kt` `priceCache` | ~14 KB | ~14 MB |
| Order-flow deques (3 x 100 boxed doubles) | `v3/scoring/OrderFlowImbalanceAI.kt` `tokenFlow` | ~5.5 KB | ~5.5 MB |
| Regime snapshots (50) + transition | `v3/scoring/RegimeTransitionAI.kt` | ~3.2 KB | ~3.2 MB |
| Smart-money deques (3 x 30) | `v3/scoring/SmartMoneyDivergenceAI.kt` | ~1.8 KB | ~1.8 MB |
| ATR deque (50) | `v3/scoring/VolatilityRegimeAI.kt` | ~1.1 KB | ~1.1 MB |
| Exit-score history (10 pairs) | `engine/DistributionDetector.kt` | ~0.7 KB | ~0.7 MB |
| Common-sense snapshot | `engine/CommonSenseTradePlaybook.kt` `cache` | ~0.4 KB | ~0.4 MB |
| Pair hydration snapshot | `engine/truth/PairHydrationState6398.kt` | ~0.3 KB | ~0.3 MB |
| Structure classification | `v3/modes/MarketStructureRouter.kt` | ~0.26 KB | ~0.26 MB |
| Scanner queue row | `engine/ScannerHydrationQueues6347.kt` (drained only by the emergency evict) | ~0.2 KB | ~0.2 MB |
| Candle synthesis bucket, alpha cache | `engine/truth/LocalCandleSynthesis7055.kt`, `engine/DataPipeline.kt` | ~0.3 KB | ~0.3 MB |

That is about 28 KB per departed mint. At the 8306 intake rate (thousands of mints an hour) this adds tens of MB per hour and is never released. It also explains why each hard trim recovered nothing. This is an estimate, not a heap dump. The next report's `mem8309[residue8309[keys= estKB= swept=]]` will confirm the size or rule it out.

## Fixes

### 1. Per-mint residue sweep: `engine/MintResidue8309.kt` (new)

- **Registration:** each residue cache registers its map through a one-line internal accessor (`residueMap8309()` etc.) in its owner file.
- **The sweep:** it removes every key whose mint is not live. It runs:
  - as a registry trim at any pressure (per store, `res8309:<name>`);
  - every 2 min at NORMAL pressure (`MemoryGuard7977.periodicResidue8309`, under `trimLock8305`).
- **Live** means any of the following (`MemoryGuard7977.liveMints8309`):
  - a token row, a watchlist entry, or an open position (runtime or canonical live);
  - an FDG seal, an active admission, a hand-off, a runner ticket or a balance-proof wait;
  - a wallet-tracked mint;
  - a key shorter than a Solana mint. Crypto-lane symbols share `VolatilityRegimeAI` and the others, and are never swept.
- **Fail-safe:**
  - an unreadable watchlist or live projection sweeps nothing;
  - a predicate that throws keeps the key.
- **`SmartChartScanner.priceCache`** is also capped on write at 400 mints (`capMap8309`). Its only reader is `getScanSummary`, which has no callers.
- **Scanner queue rows:** rows older than 30 min (10 min under HARD) go. Rows with an unexpired reject TTL and rows of live mints stay (`ScannerHydrationQueues6347.pruneStale8309`).
- **Owner cleanups:** the time-based cleanups their owners wrote but never called now run every sweep and every trim: `VolatilityRegimeAI` / `OrderFlowImbalanceAI` / `SmartMoneyDivergenceAI` / `DistributionFadeAvoider` / `DataPipeline.cleanup()`.

**Deliberately not swept** (safety, execution, identity or learning):
- `TokenSafetyChecker.liquidityHistory` (liquidity-collapse evidence);
- `ReflexAI.liquiditySamples` (drain detector);
- `DataLegitimacyAuthority7077`, `PriceIntegrityAuthority6405`, `MarkIdentityRepairAuthority7236`;
- `QuoteFreshnessGuard6452` (read by Executor);
- `SellAmountAuthority.txParseCache`;
- `MarkPriceFreshnessTelemetry6832` (crypto exit veto);
- `TradeStateMachine`, cooldowns and lockouts;
- `SuperBrainEnhancements.chartInsights`, whose outcomes feed pattern stats;
- `SentimentEngine`, whose maps are not thread-safe.

### 2. Every census store now trims or is exempt by a named kind

`MemoryRegistry8144` (`engine/MemoryCensus8144.kt`) gains `markKind8309(name, kind)`. An exempt store is not "untrimmed", and it is never "unfreed". `MemoryGuard7977.untrimmedStores8309()` is empty, and the test pins this.

| Store | Before | Now |
|---|---|---|
| `insiderTracker8305` | no trim | `InsiderWalletTracker.trim8309`: holdings and tx caches past their 60 s TTL always go (a whale's holdings list is thousands of rows). Under HARD the price and symbol caches go too. The previous-holdings snapshot is the delta baseline and is never touched. Real byte estimate `estBytes8309`. |
| `hiveBook` | HARD cap 30k, freed 0 | Under HARD it trims to the CRITICAL cap of 15k keys. Own keys are protected, and evicted keys page back in at the next boot. Estimated at ~290 B per key across the network book and the uploaded baseline, instead of 170 B. |
| `chartLibrary` | "unreached" | kind `fixed`: a preallocated array that never grows. Shrinking it would discard learned motifs. |
| `tokenRows`, `tokenArchive`, `fdgSeals` | "untrimmed" | kind `guardPass`: trimmed by the guard's own passes before the dispatch. What those passes freed is now booked as `tokenRows(guard)` / `tokenArchive(guard)` with bytes before and after. |
| `labeler`, `policyHeadStamps`, `metaPolicyStamps` | untrimmed | kind `ledger`: learning evidence, never reset by memory pressure. |
| `pipelineLabels` | untrimmed | kind `capped` (20k keys at insert). |
| `sideEffectQueue`, `cortexQueue` | untrimmed | kind `queue`: dropping one would drop a save or a side effect. |
| `dynAltRegistry8305` | untrimmed | kind `universe` (the crypto trading universe). |
| `httpBodiesInFlight8305` | untrimmed | kind `transient`. |

### 3. Bytes per store in every trim

- **`TrimResult8144`** gains `beforeBytes` / `afterBytes` / `freedBytes` / `exempt`. The defaults keep the old positional constructor working.
- **`trimAll8144`** estimates each store before and after its trim.
- **`MemoryRegistry8144.trimMbLine8309`** formats it as `total=xMB [store:beforeKB->afterKB,...]`, largest release first. It appears in the memory line as `lastTrimMb8309=[...]`.

### 4. Attribution of what the census never counted

These are new census rows. Learning books are counted, never trimmed.

| Census row | Estimator |
|---|---|
| `res8309:*` (12 caches) | `MintResidue8309.bytesOf8309`; the chart candles use a real walk (`SmartChartScanner.residueBytes8309`) |
| `cortexLedgers8309` | `CortexLedger7885.estBytes8309` (seats x (bins + 2) Stats + lanes + voter pairs) summed over `Cortex7885.ledgerBytes8309` (seat ledger + per-mode truth ledgers), `CortexExit7897` and `CortexTiming7900` |
| `lanePlaybook8309`, `playbookCache8309` | `LanePlaybook7907.estBytes8309` / `cacheBytes8309` (pending labels, timing stamps, books, classify cache, input stamps) |
| `cellAllocator8309` | `CellAllocator7962.estBytes8309` (nodes, refs, lanes, waits, demand, priority) |
| `tradeShape8309` | `TradeShapeLearner7883.estBytes8309` |
| `behaviorLearning8309` | `BehaviorLearning.estBytes8309` (pattern lists + stats) |
| `launchTape8309` | `LaunchTape7921.estBytes8309`: recs plus **every per-buyer SOL map entry** (unbounded per rec, flagged in 8305) |
| `pairFlow8309` | `PairFlow8169.count8309` x 170 B |
| `scannerQueueRows8309` | row count x 200 B (trimmed) |
| `prefsMirror8309` | 2 x SharedPreferences XML bytes. Android keeps each opened file as a parsed map of UTF-16 strings, about 13 MB for the 6.4 MB on disk. Refreshed every 60 s from `census8031`. |
| `serviceQueues8309` | queued tasks on the BotService executors x 1 KB (`BotService.executorQueues8309`); per-executor depths in `execQ8309=[service,entry,exit,exitPolicy]` |

Already attributed and checked:
- token rows (candles x 104 B + 6 KB a row), the archive (4k resident rows x 2 KB, no candles: archived rows carry metadata only), miner, colours, mint registry, chart and structure tapes;
- Cortex assessments, labeler pending, cells and seen keys;
- the 8289 stores and the 8305 network stores.

Not on the Java heap:
- Bitmaps (minSdk 26: pixel data is native).

No Gson anywhere in the app. `org.json` trees are transient per parse, and their bodies are capped by 8305.

## Memory line

`MemoryGuard7977.statusLine7977()` now ends with:

```
mem8309[residue8309[keys=.. estKB=.. sweeps=.. lastSwept=.. swept=[cache:n,..]]
        lastTrimMb8309=[total=xMB [store:beforeKB->afterKB,..]] prefsMirrorKB=.. execQ8309=[svc,entry,exit,exitPolicy]
        exempt8309=[name(kind),..]]
```

`stores8305=[...]` lists every census row, the new ones included.

## Kept intact

The following are untouched:
- open positions (runtime and canonical), active admissions, FDG seals, hand-offs, runner tickets, balance-proof waits and wallet-tracked mints. These are all "live" to the sweep.
- every learning ledger: Cortex seats and truth ledgers, the lane playbook, the cell allocator nodes, the trade-shape and behaviour learners, the labeler and the policy-head stamps.
- the insider delta baseline and the hive's own keys.

## Tests: `Aate8309MemoryResidueTest`

- **The sweep:**
  - drops only non-live mints;
  - resolves `mint|suffix` keys to their mint;
  - keeps a key whose predicate throws.
- **Bounds hold past their caps:**
  - the write cap holds past 10x its cap and never drops a live mint;
  - the chart candle cache stays at or under 400 across 1,200 scanned mints, and a sweep empties it;
  - scanner queue rows are pruned while reject TTLs and live mints stay.
- **Registered caches:** each is counted, estimated and swept by name. The periodic cadence is 2 min.
- **Registry:** exempt kinds are neither untrimmed nor unfreed; trims carry bytes before and after; the `trimMbLine8309` format.
- **Guard wiring:**
  - `untrimmedStores8309()` is empty;
  - every new census row is registered;
  - learning books are exempt as `ledger`;
  - `hiveBook` and `insiderTracker8305` trim (not exempt);
  - the memory line keys are present.
- **What must not be dropped:**
  - the live predicate covers rows, watchlist, open positions, seals, admissions, hand-offs, critical mints and crypto symbols, and fails safe;
  - the insider trim never touches the delta baseline;
  - learning ledgers are estimated, not trimmed.

## Still unknown

- **Whether the residue is all of the 406 MB.** The estimates say tens of MB per hour, which fits a heap that climbs over an hour. The next report decides it:
  - if `residue8309 swept` is large and `unattributedMb` falls, it was the residue;
  - if `execQ8309` shows a deep service queue, the backlog is the holder, and the fix is to shed per-token telemetry launches when the queue is deep. That is a call-site change inside `processTokenCycle`, which is at its ART method budget, so it was left for a follow-up;
  - if neither moves, the next step is a heap dump.
- **A heap dump on CRITICAL pressure** (`Debug.dumpHprofData`) was not added. It writes about 500 MB and freezes the process for seconds, which is not acceptable while positions are open.
