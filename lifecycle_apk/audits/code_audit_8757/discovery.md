# Code audit: discovery and the main loop, 5.0.8757 (origin/main 916e8eb71)

This audit is read-only: I did not modify the repository. Paths are relative to
`lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/` unless stated. "Log" means
`aate_pipeline_20261115_150239_790.txt`, captured on build 5.0.8756 with 333 s of uptime, in LIVE mode, after an OkHttp
TaskRunner `OutOfMemoryError` that happened at 4.9 h of uptime. The earlier audit is
`audits/audit_8629/audit_discovery_now.md` (D1 to D12). I did not repeat it. Section 1 records which of its defects are now fixed.

Severity scale: **P0** = crash, stall or money; **P1** = large trading or throughput impact; **P2** = waste, latent race or hygiene.

---

## 1. Status of the 8629 defects at HEAD

| 8629 | Status at 8757 | Evidence |
|---|---|---|
| D1 re-sighting resets `addedAt` | **Fixed for watched coins, bypassed for evicted coins** | `GlobalTradeRegistry.kt:248` (`val addedAt`), `:442` `noteResighting8631`. An evicted coin comes back as a brand-new entry. See **F1**. |
| D2 selector blind to stage and age; PUMP pinned second-to-last | **Not fixed** | `BotService.kt:19064-19079`: rank = score + 0.5·conf + ln-liq + 4·affinity. `ScannerDiversityBandit.kt:36-41` still pins PUMP before OTHER. |
| D3 runner lanes see old coins | **Fixed** (known age only) | `BotService.kt:15079-15086`, `:15183`. Log: `RUNNER_LANE_TOKEN_TOO_OLD_8631=87`. |
| D4 forced opportunity leaders; trending-list hunters | **Half fixed** | Leaders are no longer forced (`HunterWindow8293.kt:35`, `forceLeaders8631=false`). The sweep still reads the same post-move lists (`MarketSweep7297.kt:335-351`) and the hunter profiles are unchanged. |
| D5 fast lane keyed on estimated liquidity; pump share | **Mostly fixed** | `BotService.kt:11178` and `:15107` (witnessed create). The pump-share exemption only arms on the cold-pump path. See **F17**. |
| D6 one feed under many labels | **Relabelled, not de-duplicated** | `SourceTruth8633`. Five readers of `token-profiles/latest` and three of `token-boosts/top` still run every batch (**F8**). The CryptoAlt hand-off is still labelled `DEX_TRENDING` (**F9**). The selector bucketing was not updated (**F14**). |
| D7 losing sources never throttled | **Not fixed** | The admission park still needs `mult<0.65 && liq<7.5k && vol<=0` (`BotService.kt:16592-16593`). `StrategyLab8755.sourceFactor8755` only reshapes the score (`SolanaMarketScanner.kt:4494-4495`). Log: MARKET_HUNT_* is 81 of 209 intakes (39%). |
| D8 restored held and high-touch rows re-enter as candidates | **Fixed** | `HotConvictionWarmup.kt:80-81`, `:116`, `:214`. |
| D9 throughput and OOM | **Open; new crash** | See **F2**, **F3** and **F7**. |
| D10 owner (PumpPortal key, chain feed) | Key funded (`cost8192` framesToday=83,250). The daily budget is nearly spent (**F24**). | |
| D12 cross-asset rows in meme lanes | **Open, mislabelled** | See **F9**. |

---

## 2. Findings, ranked

### F1 (P1) An evicted or stale-evicted coin is re-admitted as brand new, so D1's fix is bypassed for exactly the re-listed coins

- **Where:**
  - `GlobalTradeRegistry.kt:550-629` (eviction) and `:632-644` (a fresh `WatchlistEntry(addedAt = now, processCount = 0)`).
  - No eviction memory exists anywhere in the registry.
  - `SolanaMarketScanner.kt:4313-4344`: `isSeen` ignores its own seen, rejected and saturated memory when the registry no longer has the mint ("INTAKE_REPAIR … allowing re-emit").
  - `ScannerIntake8292.kt:178-179`: the re-emit gate only suppresses *watched* mints.
  - `BotService.kt:35248-35249`: the intake dedupe for an unwatched mint is 30 s, not 5 min.
  - `BotService.kt:18915-18961`: stale evictions at pc≥96 or after 30 min idle.
- **Mechanism.** LRU, ranker or stale eviction removes coin X. The next profiles, boosts, bluechip or hunt list that still carries X re-emits it after 30 s. X is re-added with a new `addedAt` and `processCount=0`, so it lands in the selector's **fresh tier** (`BotService.kt:18905`). It is also **un-evictable for 5 minutes** as "undecided" (`WatchlistEviction8398.kt:53-54`, `MIN_EVALS_8398=3`, `MAX_UNDECIDED_AGE_MS_8398=300 s`). Its arrival evicts another coin, often a one-off launch that nobody re-lists. The trending coins therefore keep cycling back into the top tier with eviction protection.
- **Evidence:** log `WATCHLIST_LRU_EVICT_6287=30` in 333 s with the watchlist pinned at 200 (`watchTokens=200`).
- **Fix:**
  - Keep a bounded `evictedAt8758: mint -> (addedAt, processCount, evictedAtMs)` in `GlobalTradeRegistry`, with a 30-60 min TTL and a cap of about 4k.
  - In `addToWatchlist`, a mint evicted less than N minutes ago keeps its original `addedAt` and `processCount`. It goes to probation unless the sighting brings new evidence: a new endpoint (`SourceTruth8633.sameEndpointAsAny8633`), a price change of more than X% since eviction, or a witnessed create.
  - In `isSeen`, stop bypassing the seen and rejected memory just because the registry dropped the mint. That bypass is what lets an evicted mint flood back.

### F2 (P0) OutOfMemoryError: allocation churn of 2-5 GB/min plus 157 MB the census cannot attribute; verified contributors on this path

- **Log evidence:**
  - Last crash: `OkHttp TaskRunner | OutOfMemoryError … growth limit 536870912` at an uptime of 17,564 s. The allocating thread is just the victim.
  - HEAP TIMELINE: `alloc=2175-4854MB/min`, GC +2.3-4.4 s per minute. `HeapTaskDaemon` is the top CPU thread at 15.9 s/min.
  - `attributed8169=[censusMb=89 unattributedMb=157]`: memory outside every registered store.
  - Largest bodies read: `superbrain…:4347KB, api.coingecko.com/api:3904KB, data-api.binance.vision:1864KB, api.helius.xyz:1662KB`.
- **Verified contributors in scope:**
  1. Unclosed error responses (**F6**). Each one pins a connection, okio segments and the `CappedBody8305` wrapper until finalisation.
  2. The same `token-profiles/latest` body (cached String) is parsed into a full `JSONArray` by five parallel readers every batch, plus `token-boosts/top` by three (**F8**).
  3. `BirdeyeApi(key)` and `DexscreenerApi()` are built per call, and each constructs an `OkHttpClient` (**F19**).
  4. `ErrorLogger.debug(...)` is called with interpolated strings on every hot path. DEBUG is dropped *after* the string is built (`engine/ErrorLogger.kt:96`, `:133`), and there are about 700 call sites.
  5. Every scanner emit calls `onLog(...)`, which builds the UI line (`SolanaMarketScanner.kt:4510-4512`).
  6. `rcScoreCache` is unbounded (**F21**).
- **Fix:**
  - Fix F6, F8 and F19 first.
  - Make `ErrorLogger.debug` take a lambda (`inline fun debug(c: String, m: () -> String)`) so the message is built only when DEBUG is enabled.
  - Stream-parse the large feed bodies with `JsonReader`, or parse once and share the parsed rows (**F8**).
  - Register `HttpBodyCap8305.inFlightBytes` and the per-endpoint largest-body counters as census entries, so "unattributed" shrinks.

### F3 (P1) The 7.8 s "cycle" is the loop sleeping 8 s, not a choke in the loop body

- **Where:**
  - `BotService.kt:34-38`: `POLL_MAX_S_8450 = 8`.
  - `:1545`: `pollMs8450`.
  - `:22477`: `delay(pollMs8450(cfg))` after a fire-and-forget supervisor.
  - `:18005`: `prevCycleMs` is measured tick to tick, so it includes the sleep.
  - `engine/LlmParameterTuner.kt:76-80`: the tuner may write `pollSeconds` up to 8, with `maxStep=5`.
  - `ui/MainActivity.kt:10641`: the settings fallback is `?: 8`, while the BotConfig default is 5.
- **Evidence:**
  - Last 10 cycles were 8,026 to 8,067 ms (a 41 ms spread).
  - `Slow cycle diag slowCycles=0` with a 5 s threshold (`truth/SlowCycleDiagnostic6437.kt:39`).
  - `fireSupervisorWorkers` returns immediately (`BotService.kt:24562-24838`).
  - So the body is a few tens of ms and the persisted `pollSeconds` sits at the 8 s clamp.
  - Every price refresh, entry and label waits on this sleep. This is the same defect class as 8450 (30 s), now hidden by the clamp.
- **Fix:**
  - Run the loop at a fixed rate: `delay((targetMs - (now - cycleStart)).coerceIn(250, targetMs))` with `targetMs = 5000`.
  - Take `pollSeconds` out of the LLM tuner (`AdvisorKeyAuthority8353.ADVISORY_ONLY_KEYS_8353`) or drop `POLL_MAX_S_8450` to 5.
  - Fix the `?: 8` fallback.

### F4 (P1) The scanner runs about once a minute: the LLM tuner can set `scanIntervalSecs` to 60 s, and nothing clamps it

- **Where:**
  - `engine/LlmParameterTuner.kt:145-149`: `scanIntervalSecs` min 3, **max 60**, `maxStep=10`, auto-applied. It is not in `ADVISORY_ONLY_KEYS_8353` (`AdvisorKeyAuthority8353.kt:34`).
  - `SolanaMarketScanner.kt:1483`: `scanIntervalMs = maxOf(c.scanIntervalSecs*1000L, 10_000L)`, with no upper bound.
  - `:1719`: `delay(scanIntervalMs)` after the batch.
- **Evidence:**
  - `LIFECYCLE/SCAN_FULL_SOLANA_SOURCE_BREADTH_4560:6` and `SCANNER_TIER_INTERLEAVED_7152:6` mean **6 deep-scan batches in 333 s**, about 55 s per iteration.
  - `SCANNER_SOURCE_DONE=100` plus `SCANNER_SOURCE_TIMEOUT=8` is about 18 sources per batch, so the batches completed and the gap is sleep.
  - The resident hunter ran 7 times on its 45 s clock, so the process was not stalled.
  - The intended cadence is 8-10 s (`data/BotConfig.kt:254`), so every non-WS feed arrives about 5x late.
- **Fix:**
  - Clamp the interval in `scanLoop` (for example 8..15 s) as 8450 did for `pollSeconds`.
  - Remove `scanIntervalSecs` from the tuner.
  - Run at a fixed rate (subtract the batch duration).

### F5 (P1) PumpPortal create intake runs synchronously on the OkHttp WebSocket reader thread and contends on global monitors

- **Where:**
  - `network/PumpFunWS.kt:1105`: `onNewTokenCb?.invoke(...)` inside `onMessage`.
  - `BotService.kt:11070-11180`: the callback calls `admitProtectedMemeIntake` (`BotService.kt:16181`, about 1,000 lines). That function takes `synchronized(status.tokens)` several times (`:16713`, `:16964`, `:17010`) and calls `GlobalTradeRegistry.addToWatchlist`, which is `@Synchronized` (**F10**).
  - Trade frames also run `bandEntry8118` (`:11005`, which can itself call `admitProtectedMemeIntake`), `applyPumpTradeMark7278` and `PumpBandEntry8118.maybeSweep8136()` on every frame (`PumpFunWS.kt:924`).
  - `PumpPortalThrottle.allowCreate` copies the whole watchlist per create (`engine/PumpPortalThrottle.kt:58-61`, `getWatchlistEntries().size`).
  - `persistFrames8691` writes a file on the reader thread every 250 frames (`PumpFunWS.kt:652-660`).
- **Effect.** While a scanner thread or the bot loop holds the registry or `status.tokens` monitor, the single PumpPortal reader blocks. Frames queue in the socket, so creates and trade marks arrive late, which hurts the 180 s sniper window and the held-coin marks. The log shows 83,250 trade frames today on this one thread.
- **Fix:**
  - The WS listener should only parse and push a compact event onto a bounded `Channel` (`capacity=4096`, `DROP_OLDEST` for trades, never drop creates).
  - A dedicated single consumer coroutine does the admission work.
  - Replace `getWatchlistEntries().size` with `GlobalTradeRegistry.size()`.
  - Move the frame persistence onto the side-effect dispatcher.

### F6 (P1) HTTP error responses are never closed on the discovery path (leaked sockets and buffers)

- **Where:**
  - `SolanaMarketScanner.kt:4832`: `val resp = http.newCall(...).execute()`. The non-2xx branch at `:4860-4867` logs and returns `null` without `resp.close()`. This is the shared `get()` used by every scanner source.
  - `SolanaMarketScanner.kt:4580-4584`: `quickRugcheck` returns `true` on `!resp.isSuccessful` without closing.
  - `network/BirdeyeApi.kt:641-716`: the 401/403, 429, 5xx and `else` branches never close `resp`.
  - `network/CoinGeckoSolanaTopMcap.kt:165`: `if (resp.isSuccessful) resp.body?.string() else null`.
- **Effect.** Every 429, 403 or 5xx keeps a pooled connection allocation and its body source until GC finalises the call. This happens under exactly the storms the app sees: Gecko provider429=6, Birdeye 401/429, and pump.fun 5xx with the circuit open. OkHttp logs "A connection … was leaked". The connection pool (24 idle) fills with zombies and the cost lands on the unattributed heap.
- **Fix:** wrap every `execute()` in `.use { resp -> … }` (`MarketSweep7297.kt:694` and `DexscreenerApi.kt:597` already do this). Add a lint or detekt rule that bans a bare `.execute()`.

### F7 (P1) The supervisor admits 48 leases a cycle onto a 16-thread pool; queue time counts against the 15 s worker budget

- **Where:**
  - `BotService.kt:681-684`: `supervisorExecutor6647 = newFixedThreadPool(16)`.
  - `:24258`: `SUPERVISOR_HEALTHY_MEME_MAX_INFLIGHT = 48`.
  - `:23683`: `SUPERVISOR_BASE_MAX_WORKERS = 48`.
  - `:24672` and `:24694-24700`: the worker is `scope.launch(supervisorDispatcher6647)` and then `withTimeoutOrNull(15 s) { runInterruptible(supervisorDispatcher6647) { processTokenCycle } }`.
  - `:24781-24785`: the watchdog fires at 15.75 s regardless of whether the worker got a thread (`SUPERVISOR_WORKER_QUEUE_EXPIRED_8418` exists because of this).
- **Evidence:** log `SUPERVISOR_INFLIGHT_CAP` in 42 of 42 cycles (`Supervisor cap/sat: 42 / 0`) and `AATE-Entry:16` threads. Roughly two thirds of each cycle's selection can only wait. The old leases are still held when the next cycle selects, so the selector ranks 48 coins and the pool serves about 16.
- **Fix:**
  - Size the per-cycle cap to the real pool: `PER_CYCLE_CAP = min(planner, poolThreads - activeLeases)`.
  - Alternatively raise the pool to 32 and keep the cap at 32.
  - Start the worker's timeout only once the block runs (measure from inside `runInterruptible`).
  - Give the watchdog the same rule.

### F8 (P1) Duplicate readers: five scanners read `token-profiles/latest` and three read `token-boosts/top` every batch, concurrently; six more modules read the same feeds outside the cache

- **Where:**
  - Profiles readers: `SolanaMarketScanner.kt:1830` (`scanPumpFunActive`), `:2150` (`scanFreshLaunches`), `:2431` (`scanDexTrending`), `:2502` (`scanDexGainers`) and `:2776` (`scanTopVolumeTokens`). All are labelled `DEX_PROFILES` since 8633.
  - Boosts readers: `:2082` (`scanPumpFunVolume`), `:2600` (`scanDexBoosted`) and `:2652` (`scanPumpGraduates`).
  - All of them start together in `runScanBatch` (`:1320-1326`). Each walks the same list in the same order and calls `dex.getBestPair(mint)` per row (`:1853`, `:2169`, `:2457`, `:2531`, `:2797`, `:2100`, `:2623`, `:2670`).
  - `isSeen` does not mark a mint as seen. Only `emit` does, at `:4501`, after the pair fetch and the rugcheck. Five readers therefore miss together and fetch the same pair concurrently, because `DexscreenerApi.getBestPairInternal` has a cache but no single-flight (`network/DexscreenerApi.kt:117-157`).
  - The 30 s body cache `dexFeedCache7381` (`:4701-4728`) saves the wire but not the five `JSONArray` parses.
  - Outside the scanner, with different URLs (so the cache never helps): `perps/DynamicAltTokenRegistry.kt:1751`, `:1770`; `perps/crypto/MultiChainMemeLane7962.kt:610`; `engine/HistoricalChartScanner.kt:282`; `engine/chart/ChartSources7955.kt:549`; `network/DexScreenerSocialSource.kt:99`, `:112`, `:124`; `v3/scoring/SocialVelocityAI.kt:41`.
- **Effect.** The feeds' own 60/min limit and the shared 180/min DexScreener bucket are spent on duplicates. Log `dexHost8287 … refused{bulk=244}` in 333 s: BULK pair reads are refused. The parsing is also churn (F2).
- **Fix:**
  - Keep one `DexFeeds8758` reader per endpoint. It is polled once per batch, parses once, and hands the parsed rows to the per-purpose filters, so the five scanners become filters over one list.
  - Every module reads through that object.
  - Claim the mint before the pair fetch with an atomic `seenMints.putIfAbsent`.
  - Add single-flight per mint (a `ConcurrentHashMap<String, Deferred<PairInfo?>>`) in `getBestPairInternal`.

### F9 (P1) The CryptoAlt desk's hand-offs enter meme intake labelled `DEX_TRENDING`; this is now the largest pre-FDG source

- **Where:**
  - `perps/CryptoAltTrader.kt:860-865`: `TokenMergeQueue.enqueue(..., scanner = "DEX_TRENDING", toolAffinity = CRYPTO_HANDOFF_8045)`. The 8633 relabel missed this site.
  - Also `BotService.kt:26441-26446`, where an unknown source is fabricated as `DEX_TRENDING`.
- **Evidence:** log intake `DEX_TRENDING: 14` (no `SCANNER_DIRECT_` prefix, so it came through the merge path), `SourceChoke4584 preFdg=[SRC|DEX_TRENDING=422 …]` (the top pre-FDG source), and `source decisions: DEX_TRENDING|SCORER_NEUTRAL n=159`.
- **Effect:**
  - The desk's coins (established alts) are graded under a DexScreener cell, which poisons source learning (`ScannerSourceQuality8292`, `EdgeModel8537.SOURCES`).
  - In the selector they get the **DEX family**, which is first in the round-robin: `bucketFor` matches `DEX_TREND` at `BotService.kt:19110`.
- **Fix:**
  - Use the honest tag `CRYPTO_DESK_HANDOFF` and add it to `ScannerSourceTags8292.WRAPPER_TAGS_8292`.
  - Route it only to the established lanes (QUALITY, BLUECHIP, TREASURY), per the old D12.
  - Make the unknown-source fallback `UNKNOWN`, not `DEX_TRENDING`.

### F10 (P1) `GlobalTradeRegistry.addToWatchlist` is a global `@Synchronized` section that does heavy work on every admission at cap

- **Where:** `GlobalTradeRegistry.kt:377-653`. At cap (always, at 200-220), every admission does all of this under the registry monitor:
  - `CanonicalPositionAuthority6441.openPositions()` (`:552`);
  - builds Rows for all 220 entries (`:559-561`);
  - counts the pump share over all entries (`:571`);
  - and in `WatchlistEviction8398.victim8738` (`:573-579`) runs **up to 24** `TreeRanker8737.predict8737(EdgeModel8537.rawFor8610(ts…))`. Each call computes a full `LanePlaybook7907.features(ts)` frame (`truth/EdgeModel8537.kt:439-440`).
- **Callers:** the PumpPortal WS thread (F5), 8-20 parallel scanner coroutines, the hunter emitter, the merge drain on the bot loop, probation promotion and the rebalancer.
- **Fix:**
  - Compute the victim outside the lock: snapshot, rank, then CAS-remove under a short lock, and re-validate.
  - Cache the ranker score per mint per feature revision (`FeatureRevision8283`).
  - Keep only the size check and the insert under the monitor.

### F11 (P1) `TokenMergeQueue` mutates a non-thread-safe set from many threads and races on check-then-put

- **Where:**
  - `engine/TokenMergeQueue.kt:142-228`: `pendingDiscoveries[mint]` is checked, then `pendingDiscoveries[mint] = MergeEntry(...)` is set, with no `compute`/`putIfAbsent`.
  - `MergeEntry.scanners = mutableSetOf(scanner)` (a `LinkedHashSet`, `:220`) is mutated at `:148` by concurrent scanner, hunter, CryptoAlt and Treasury callers.
  - It is iterated on the bot loop at `:303`, `:328` and `:330` (`endpointCount8633(it.scanners)`, `entry.scanners.toSet()`).
  - `discoveryCount++` and the field writes are unsynchronised (`:160-190`).
- **Effect:**
  - Two scanners seeing a new mint together both put, and one overwrites the other's scanners. That loses the second-source evidence the merge exists to collect.
  - A `ConcurrentModificationException` during `processQueue` is swallowed by `BotService.kt:21773-21774`. That pass's `toRemove` never runs, and a selection may be dropped.
- **Fix:**
  - Use `pendingDiscoveries.compute(mint) { _, e -> … }`.
  - Make `scanners` a `ConcurrentHashMap.newKeySet()`.
  - Make the counters atomic.

### F12 (P1) Fast-lane evaluations run with an un-normalised, sometimes stale, config; `paperMode` can diverge from the runtime mode

- **Where:**
  - The supervisor normalises the mode: `cfg.copy(paperMode = RuntimeModeAuthority.isPaper())` at `BotService.kt:24697` and `:23476`.
  - The fast lane does not: `fastLaneEvaluate7277(mint, liveCfg, …)` at `:11179` (raw ConfigStore), and `processTokenCycle(mint, cfg, …)` at `:26009` and `:26023`.
  - `bandEntry8118` passes the `cfg` captured once in `wireExternalStreams` (`:10984`, `:11005`, `:11013`, `:25992`). That snapshot is from stream start and is stale for the whole session.
  - `processTokenCycle` reads `cfg.paperMode` **84 times**, including live-funding gates (for example `:28889`, `canFundLive`, and `:28156`).
- **Fix:**
  - Normalise in one place: a `cycleCfg8758()` that loads ConfigStore and applies RuntimeModeAuthority.
  - Use it in the supervisor and in both fast-lane passes.
  - Never pass the stream-wiring snapshot.

### F13 (P1) `TradeLifecycle.proposalTrackers` is a plain HashMap written by 16 supervisor threads, and it is unbounded

- **Where:** `engine/TradeLifecycle.kt:169` (`mutableMapOf`), with `getOrPut` at `:204`, `:272`, `:288` and `:298`. It is called from `processTokenCycle` (`BotService.kt:27635`, `:14064`, `:32268`) on the 16-thread pool and the fast lane.
- **Patch rot:** 8440 converted `lifecycles` to `ConcurrentHashMap` for exactly this CME (`:151-154`) and missed the sibling map.
- **Effect:** concurrent `HashMap` puts can lose entries (so dedupe misfires and duplicate proposals get through), throw a CME into the cycle, or corrupt the table. Entries are removed only on `noSignal` and `clear`, so every proposed mint is retained.
- **Fix:** use `ConcurrentHashMap` with `compute`, and prune entries older than `PROPOSAL_WINDOW_MS` in `pruneOldLifecycles`.

### F14 (P2) Patch rot from 8633: the selector's source bucketing does not know the new names; the bandit does

- **Where:** `BotService.kt:19109-19118`. `bucketFor` matches only `DEXSCREENER|DEX_SCREENER|DEX_TREND|DEX_GAIN|DEX_BOOST` for the DEX family. The new names fall through:
  - `SCANNER_DIRECT_DEX_PROFILES` goes to **SCANNER**;
  - `SCANNER_DIRECT_MARKET_SWEEP` goes to SCANNER;
  - `METEORA_POOL` goes to RAYDIUM.
- Meanwhile `ScannerDiversityBandit.kt:18` scores `DEX_PROFILES` as DEX, so the bandit orders families on one mapping while the selector fills buckets with another. `isFreshSolanaSource` (`:18789-18799`) has the same hard-coded vocabulary.
- **Fix:** derive the family from `SourceTruth8633.endpointOf8633(...)` through one family table shared by the bandit, the selector and `ProtectedIntakeAdmissionGate.kt:25-26`.

### F15 (P1) Non-pump launches (PumpPortal bonk/LaunchLab, chain Meteora/DBC) have no venue-native price; they block on DexScreener indexing and still take selector slots

- **Where:**
  - `BotService.kt:11129-11135`: a non-pump PumpPortal create gets `mcapUsd = NaN, estLiq = NaN` (by design since 8657).
  - In `processTokenCycle`, `dex.getBestPair` returns null (not indexed yet), `curveVenueSeeded7951` is false, and `lastPrice == 0`. The result is `PAIR_PENDING_HYDRATION` and the fire-and-forget `requestEntryHydration6647` (`:26192-26304`).
  - `ENTRY_HYDRATION_FANOUT_EMPTY_7273=13` against `PRICED=5`.
- **Evidence:**
  - `INTAKE/PAIR_PENDING_HYDRATION:35` (the third-largest block reason).
  - `discoveryMcap8400 unresolved=16 {NO_PRICE@PUMP_PORTAL_WS=8, NO_PRICE@CHAIN_LAUNCH_METEORA=5, NO_PRICE@METEORA_POOL=3}`.
  - These coins are re-selected each cycle and re-blocked: `markProcessed` runs on the early return, so they age into `cold` with no data.
- **Fix:**
  - Price these from the venue itself: the PumpPortal frame's `solAmount`/`tokenAmount` for its `pool`, the LaunchLab or DBC pool account (the reserves are already decoded for the chain feed in `ChainLaunchFeed8179.quoteDepthUsd8179`), or the Jupiter quote route.
  - Until a price exists, hold the coin in a "hydrating" set that the selector skips, and re-enter it on the hydration callback (`HydrationReeval8343` already exists). Do not burn a slot each cycle.

### F16 (P2) The scanner's "hard" budgets are not hard: blocking OkHttp, `Thread.sleep` retries and the budget sleep defeat `withTimeout`

- **Where:**
  - `SolanaMarketScanner.kt:1159` (`withTimeout(5 s)`) and `:1320-1328` (`withTimeoutOrNull(8 s)`) wrap coroutines that call the blocking `get()`.
  - `getWithRetry` has `Thread.sleep(150/250 ms)` (`:4766`, `:4776`).
  - The DexScreener budget wait is `Thread.sleep(wait)` of up to 1.5 s inside the interceptor (`network/DexHostBudget8287.kt:214`).
  - Cancellation is only observed at the next `withContext` boundary, so a source can run to `callTimeout` (12 s) × `maxRetries` (2) per request. `withTimeoutOrNull` waits for its children.
  - `ResidentHunterWorker7807.kt:139` (70 s) wraps blocking `execute()` too.
- **Fix:**
  - Use `suspendCancellableCoroutine` around `call.enqueue` (`call.cancel()` on cancellation), or `runInterruptible`, for scanner HTTP.
  - Replace `Thread.sleep` with `delay`.
  - Per source, set `callTimeout` = the source budget.

### F17 (P2) The 8631 pump-share exemption arms only on the cold-pump path

- **Where:**
  - `FreshLaunchIntake8303.kt:110-130`: `noteHot8631` runs only when `wouldPark` is true. That is the live cold-pump base or the source-brain park (`BotService.kt:16598-16599`).
  - In PAPER (`lenientIntake`), or for a live create already worth $1.5k or more, `park8303` returns early and `hotAt8631` is never set.
  - `GlobalTradeRegistry.kt:488-489` and the rebalancer at `BotService.kt:16021` then apply the 35% share to those witnessed fresh creates and divert them to probation.
- **Fix:** record `hotAt8631` for every witnessed in-window PUMP_PORTAL_WS create that intake admits hot, independent of the park decision.

### F18 (P2) Merge-queue double admission: every scanner sighting is admitted directly and then re-admitted from the queue on the bot loop

- **Where:**
  - `BotService.kt:8602` (direct `admitProtectedMemeIntake`) and `:8766` (`TokenMergeQueue.enqueue`).
  - The bot loop drains up to 96 merged entries per pass synchronously (`:17608-17631` via `:21773`).
  - Each one re-enters `admitProtectedMemeIntake` and lands in the dedupe path (`:16700-16766`). That path still takes `synchronized(status.tokens)` and can call `addToWatchlist` again.
  - The self-heal scanner does the same (`:6597`, `:6611`).
- **Fix:** keep one path. Either the scanner callback only enqueues (the merge gives the real multi-source signal and the D7 ranking), or the direct path stops enqueuing. Move the drain off the loop thread.

### F19 (P2) An OkHttp client is constructed per call or per access on hot paths

- **Where:**
  - `SolanaMarketScanner.kt:913-914`: `private val birdeye get() = BirdeyeApi(cfg().birdeyeApiKey)`. A new `OkHttpClient` (`network/BirdeyeApi.kt:32-40`) is built on every scanner Birdeye call (9 uses).
  - `engine/DataOrchestrator.kt:41`: the same getter.
  - `BotService.kt:28008-28014`: `scope.launch { DexscreenerApi().getBestPair(...) }` per token per cycle while caches are cold.
  - `truth/ForwardReturnLabeler7731.kt:2540`, `:2614`; `sell/SellReconciler.kt:417`; `BotService.kt:34630`.
  - They share the pool and dispatcher, but each `build()` copies the interceptor lists and route DB, which adds churn.
- **Fix:** cache one `BirdeyeApi` per key (`@Volatile` + key compare) and one `DexscreenerApi` singleton.

### F20 (P2) `PumpFunWS` (also `HeliusEnhancedWS` and `PythHermesStream`) bypasses `SharedHttpClient`; restart and reconnect hygiene

- **Where:**
  - `network/PumpFunWS.kt:869-872`: a raw `OkHttpClient.Builder()`, which means its own ConnectionPool, Dispatcher and thread pool, no `HttpBodyCap8305` and no circuit. A new client is built on each `start()`.
  - `stop()` (`:876-883`) shuts down the dispatcher executor but never calls `connectionPool.evictAll()`.
  - `scheduleReconnect` starts a raw `Thread` with `Thread.sleep` per attempt (`:1151-1159`).
  - `onFailure` and `onClosed` treat `ws == null` as "current" (`:1136`, `:1144`). In the window inside `connect()` (`:891` sets `ws = null` before `newWebSocket`), the replaced socket's close can schedule a second reconnect.
  - The same patterns are in `network/HeliusEnhancedWS.kt:197`, `:247-253`, `:428-440`.
- **Fix:**
  - Use `SharedHttpClient.builder().pingInterval(...).readTimeout(0)` once per process.
  - Reconnect through a single `scope.launch { delay(); connect() }` guarded by an `AtomicBoolean reconnectPending`.
  - Track the current socket with a generation id instead of null checks.

### F21 (P2) Unbounded or slow-leak maps on the discovery path

- `SolanaMarketScanner.kt:908`: `rcScoreCache` gets one put per rugchecked mint (`:4592`) and is never pruned.
- `TradeLifecycle.kt:169`: see F13.
- `network/PumpCurveKeys7269.kt:153-156`: at cap, each new create scans 6,000 entries with `minByOrNull`. It is bounded, but O(n) per create on the WS thread.
- `BotService.kt:16820-16831`: the intake maps are pruned only when the size is over 1,024, by a full scan per intake while above the cap. With about 1,000 new mints in 5 min this runs on every intake.
- **Fix:** use a bounded LRU (`LinkedHashMap(accessOrder)` + `removeEldestEntry`, synchronised) or a TTL sweep on a timer instead of per call.

### F22 (P2) The re-emit gate runs after the expensive per-mint work

- **Where:** `ScannerReemitGate8292` is checked in `emit`/`emitWithRugcheck` (`SolanaMarketScanner.kt:4429`, `:4646`). That is after each scanner's `dex.getBestPair` (for example `:1853`) and after `isSeen`'s 12 s window.
- **Effect:** every watched mint on a list pays a pair lookup (and a BULK budget token on a cache miss) every batch, only to be suppressed.
- **Fix:** call `ScannerReemitGate8292.shouldSuppress8292(mint, source, watched)` immediately after `isSeen`, before the pair fetch.

### F23 (P2) A hunted row is labelled by whichever lane iterates first

- **Where:** `SolanaMarketScanner.kt:2253-2265`. Picks are iterated per lane, the first lane emits the mint as `MARKET_HUNT_<lane>`, and later lanes are skipped (`MULTI_LANE_CLAIM`). `lanesByMint8293` carries all the lanes only on the requeue branch (`:2290`), not on the emit branch.
- **Effect:** the source books (`SRC|MARKET_HUNT_*`) attribute a coin to an arbitrary lane in map order. QUALITY and BLUECHIP rows hunted by both appear as either.
- **Fix:** emit once with source `MARKET_SWEEP` and `laneAffinity = lanesByMint8293[mint]`, so the per-lane cell comes from the affinity.

### F24 (P2) The PumpPortal paid-frame budget is nearly exhausted early in the UTC day

- **Evidence:** `cost8192[framesToday=83250 … budget=100000 … launchSubsSkippedBudget=426]` at 15:02. `noteTradeFrame8192` (`PumpFunWS.kt:656-681`) unsubscribes every non-held mint at 100k, so the launch tape and flow for fresh creates go dark for the rest of the day, and the shape and buy-pressure inputs the lanes need disappear.
- **Fix:**
  - Pace the budget across the day (a frames-per-hour allowance).
  - Cap per-launch frames lower for coins that failed their first evaluation.
  - Unsubscribe a launch as soon as it leaves the watchlist (F1's eviction).

### F25 (P2) The create throttle is permanently in its "saturated" state

- **Where:** `engine/PumpPortalThrottle.kt:44-67`. `WATCHLIST_SATURATION_SIZE=200` is below the canonical cap of 220, and the watchlist always sits at 200-220, so the 25 SOL threshold is always active.
- **Effect:** it is nearly inert for pump.fun, whose creates start at about 28 SOL. It silently drops non-pump launchpads whose `marketCapSol` is computed differently.
- **Fix:** compare against `GlobalTradeRegistry.MAX_WATCHLIST_SIZE` with hysteresis, and apply the mcap floor only to pump mints.

### F26 (P2) OkHttp dispatcher limits do not bound what the comments say they bound

- **Where:** `network/SharedHttpClient.kt:43-65`. `maxRequests=96 / maxRequestsPerHost=24` apply only to `enqueue()`. Almost every caller (scanner, `HealthAwareHttp`, Birdeye, DexScreener) uses synchronous `execute()`, so per-host concurrency is bounded only by thread counts (IO 64 + supervisor 16 + service 6 + others).
- **Effect:** the comments' claim that this "queues 32 of 48 supervisor workers behind DexScreener sockets" is false, and the real burst control is missing.
- **Fix:** put an explicit per-host `Semaphore` in `HealthAwareHttp.execute`, or move to `enqueue` with a suspend wrapper (which also fixes F16).

### F27 (P2) `status.tokens` is a ConcurrentHashMap used as a global monitor, with unlocked check-then-put elsewhere

- **Where:**
  - `data/Models.kt:680` (ConcurrentHashMap).
  - Long `synchronized(status.tokens)` sections in intake (`BotService.kt:16713-16733`, `:17010`), the rebalancer (`:16023`) and the pruner (`MemoryGuard7977.kt:120`).
  - `BotService.kt:26425-26437` does `if (!containsKey) status.tokens[mint] = TokenState(...)` without the lock, which can overwrite a row intake just created.
- **Fix:** use `computeIfAbsent` everywhere, and use per-mint locking (a `synchronized(ts)`, which already exists at `:26237`) instead of the whole-map monitor.

### F28 (P2) `markProcessed` loses counts

- **Where:** `GlobalTradeRegistry.kt:1468-1474`. `processCount++` is a plain field increment, racing between the supervisor and the fast lane. The fast lane never calls `markProcessed` (`BotService.kt:26009`, `:26023`).
- **Effect:** the eviction "undecided" rule (`processCount < 3`) and the selector tiers read wrong counts.
- **Fix:** use an `AtomicInteger` and call it from the fast lane too.

---

## 3. Notes on the runtime log (what is not a bug)

- The heap was 246-282/512 MB at the snapshot. The crash came about 4.9 h into the previous process, so this snapshot is 5 minutes into a fresh process. The fast-growing stores in it (`cortexLedgers8309 +9.9MB`, `exploreShadow8397 +4MB`, `playbookCache8309 +2.4MB` in 5 min) are outside discovery. They are worth a 1-hour slope check, but this audit does not rank them.
- `discovery new=165 repeat=26 (newShare 86%)` is far better than 8622's 26%. Part of that is a fresh process, and part is D1. F1 is the path by which it will decay over hours.
- `RESIDENT_HUNTER_7807 runs=7 durMs=3157 emitTimeouts=0`: the hunter is healthy, and its cadence is not a choke.

---

## 4. Suggested order

1. F2 + F6 (stop the leak and the churn) and F5 (take intake off the WS thread). These address the crash and the stalled feed.
2. F3 + F4 (cadence: the loop at a fixed 5 s, the scanner at 8-15 s, both out of the LLM tuner). This is the cheapest large latency win.
3. F1 (eviction memory) and F15 (venue-native price for non-pump launches). These address fresh-launch starvation.
4. F7, F10, F11, F12, F13 (threading and money correctness).
5. F8, F9, F14, F22, F23 (one reader per endpoint, honest labels, one family table).
6. The rest.

How to verify:
- `Avg cycle ms` falls to about 5,000 with the body measured separately.
- `SCAN_FULL_SOLANA_SOURCE_BREADTH_4560` reaches 20 or more per 5 min.
- `unattributedMb` is flat over 1 h.
- No "connection leaked" lines in logcat.
- `SUPERVISOR_INFLIGHT_CAP` is under 10% of cycles.
- `PAIR_PENDING_HYDRATION` falls and `discoveryMcap8400 unresolved` drops.
- `WATCHLIST_LRU_EVICT` re-admissions within 30 min are close to 0.
- `SRC|DEX_TRENDING` contains no CryptoAlt hand-offs.
