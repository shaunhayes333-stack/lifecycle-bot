# AUDIT_MEMORY_8305: the 5.0.8302 OutOfMemoryError

Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. Line numbers are for branch `wt-fmem-8305`.

## What happened

- 5.0.8302 crashed at uptime 62 min (`uptimeAtCrashMs=3710340`). The heap was at its 512 MB limit (`target footprint 536845280, growth limit 536870912`).
- The allocation that failed was 48 bytes, on the "OkHttp Dispatcher" thread: `String.substring <- JSONTokener.nextString/readObject (deeply nested) <- JSONObject.<init>`.
- After the restart the heap was 91 MB: census 23 MB, unattributed 68 MB. 5.0.8286 had reached 511 MB with 448 MB unattributed, and 3 of its 9 hard trims recovered nothing.

### Which code ran on that thread

There is no `Call.enqueue` / `Callback.onResponse` anywhere in the app code. Every `.enqueue(` hit is `TokenMergeQueue`, `FeeRetryQueue` or `ScannerHydrationQueues6347`. OkHttp still runs some app code on its dispatcher threads:
- **every WebSocket reader** (`RealWebSocket.connect` enqueues the upgrade call, then `loopReader()` runs inside `onResponse`);
- **the SSE reader** (`PythHermesStream`).

OkHttp 4.12 renames the dispatcher thread to `OkHttp <url>` while a call runs, and restores the name in a `finally`. An Error that escapes the listener therefore reaches the crash handler on a thread named "OkHttp Dispatcher".

These listeners build a JSONObject from each frame and catch only `Exception`, so an `OutOfMemoryError` escaped and killed the process:

| Stream | Where | Frame |
|---|---|---|
| `HeliusEnhancedWS` (smart-money push) | `network/HeliusEnhancedWS.kt:169` | `transactionSubscribe`, `encoding=jsonParsed`, `transactionDetails=full`, `commitment=processed`: a deeply nested full transaction, one frame per tracked-wallet tx. **This matches the stack best** ("deeply nested", readObject recursion). |
| `PumpFunWS` | `network/PumpFunWS.kt:576` | about 19k trade frames in 37 min |
| `HeliusWebSocket` (mint logs / wallets) | `network/HeliusWebSocket.kt:606` | logs notifications |
| `DexScreenerWebSocket`, `PumpFunWebSocket`, `PythHermesStream` | `:180` / `:118` / `:113` | pair updates / creates / prices |

`GradTicks8018`, `CurveTicks7968` and `ChainLaunchFeed8179` already caught `Throwable`. They swallowed an OOM silently, with no trim and no count.

The failing frame was only the victim: the heap was already full when it arrived. The most likely heavy allocator is tied to that same socket (see F1).

## Findings

Estimates are order-of-magnitude, worked out from payload shapes. Nothing was measured on the device.

### F1: every smart-money push ran a full whale-wallet scan (acute, most likely trigger)

- **Where:** `engine/BotService.kt:11375` (push callback) calls `scope.launch { InsiderWalletTracker.scanForSignals() }` **once per pushed transaction**.
- **What a scan does** (`perps/InsiderWalletTracker.kt:540`): it iterates every active insider wallet (23 built in, plus copy wallets). For each one it calls `rpcGetTokenAccounts` (`:642`): `getTokenAccountsByOwner`, `jsonParsed`, both token programs, against `api.mainnet-beta.solana.com`. It reads the whole body with `body.string()` and builds the `JSONObject` tree. It then does a Birdeye / DexScreener price lookup for every non-zero holding.
- **Why the cache did not help:** holdings are cached only after a scan finishes. A burst of pushes started N identical scans at once, and all of them missed the cache.
- **Cost:** a whale with about 3-5k token accounts returns about 2-4 MB. That is roughly 10-30 MB transient per parse (okio buffer + String + tree). Up to 64 `Dispatchers.IO` threads parse at the same time, so a burst can take hundreds of MB.
- **Fit with the evidence:** heap that a trim cannot free (it is held by running coroutines, which matches the "ineffective hard trims" in 8286), allocated in step with the crashing socket.
- **Also unbounded:** `tokenPriceCache` (`:692`), one entry per dust mint, never refreshed.
- **Fix:** `scanForSignals` (`:523`) is single-flight and does not start again within the 60 s holdings TTL. A scan inside the TTL reads the cache and finds no delta, so no signal is lost; skipped scans are counted as `coalesced`. The price cache is capped at 3,000 entries (`putPrice8305`, `:719`). The result shows as `insiderScan8305[run= coalesced= inFlight=]` in the memory line, and the store is in the census as `insiderTracker8305`.

### F2: the Jupiter verified token list was re-fetched every 5 minutes (periodic big transient)

- **Where:** `perps/DynamicAltTokenRegistry.kt:1902`, called on every 5-minute established discovery pass (`ESTABLISHED_DISCOVERY_MS_6544`).
- **Cost:** `lite-api.jup.ag/tokens/v2/tag?query=verified` returns thousands of tokens, each a v2 object of about 2 KB with stats and audit fields. The body is roughly 5-15 MB, and the `JSONArray` tree built from it is 30-100 MB, alive while the loop runs. The fetch only adds identities and logos the registry does not already have.
- **Fix:** read once per 6 h after a successful read (`jupiterListDue8305`, `:1903`); a failed read retries on the next pass. The body is also capped at 12 MB (F3), and the registry is in the census as `dynAltRegistry8305`.

### F3: no response body had a size limit (every HTTP path)

Every caller reads `resp.body?.string()` and then builds a `JSONObject`/`JSONArray`. Nothing bounded the body. This applies to Helius RPC / enhanced transactions / `getProgramAccounts` / `getSignaturesForAddress` / `getTokenLargestAccounts`, Birdeye, DexScreener, GeckoTerminal, Codex, RugCheck, Raydium/Meteora pool lists (paged at 60-100, small), Jupiter, PumpPortal and the LLMs. A body of N bytes costs about 1N (okio) + 1-2N (String) + 5-10N (tree) while it is parsed.

**Fix: `network/HttpBodyCap8305.kt`.** It is installed as the **outermost** interceptor of `SharedHttpClient.base` (`network/SharedHttpClient.kt:89`), so it counts decompressed bytes.
- Every body is read through a counting source. The first read past the endpoint's cap throws `BodyTooLargeException8305`, an IOException that every caller already treats as a failed request, so an oversize body never becomes a String or a tree.
- A declared `Content-Length` over the cap is refused before any byte is read.
- 101 upgrades and `text/event-stream` responses pass through untouched.

| Endpoint group | Cap |
|---|---|
| RPC | 8 MB |
| Market data (DexScreener / Birdeye / Gecko / Codex / RugCheck / pump / Raydium / Meteora) | 4 MB |
| LLM | 2 MB |
| Jupiter token lists | 12 MB |
| Everything else | 6 MB |

Execution answers (quote, swap, signature, transaction, balance) are a few KB to a few hundred KB, so no cap changes a sell, fill or balance proof.

The memory line reports refusals per endpoint, the largest body read per endpoint and the bytes being read right now, as `bodyCap8305[refused= read= inFlightKB= largest=[...]]`. In-flight bytes are also a census store, `httpBodiesInFlight8305`.

### F4: an OOM on an expendable stream killed the process

**Fix: `network/NetParseGuard8305.kt`.** All 9 streams now parse through `parseObject8305(stream, text, cap)`:

1. A frame over its cap is dropped before parsing:

   | Frame type | Cap |
   |---|---|
   | Transaction / logs | 512 K chars |
   | PumpPortal, ticks, Pyth | 256 K chars |
   | DexScreener WS | 2 M chars |

2. An `OutOfMemoryError` while parsing drops the frame (its half-built tree is garbage once we return), counts it, and calls `MemoryGuard7977.emergencyTrim8305` (`engine/MemoryGuard7977.kt:177`).
3. Handlers that caught only `Exception` now also catch `OutOfMemoryError` thrown while processing a frame (`dropOom8305`).

`emergencyTrim8305` runs `trimAll(hard)` at CRITICAL retention right away, instead of waiting up to 15 s for the next tick, and then requests a GC.
- It runs at most once every 10 s; a burst of failing frames is counted as `coalesced`.
- The previous pressure level is restored afterwards, and the next tick judges whether the trim recovered headroom.
- `trimAll` is now serialized (`trimLock8305`) so the guard tick and an emergency trim never trim at the same time.

**Not applied to proof paths:** `Executor`, `SolanaWallet` and `SellAmountAuthority` keep their own error handling; the test pins this. HTTP proof reads only meet the body cap, whose limits are far above their answers.

### F5: stores that grew per mint or event and are now bounded or counted

| Store | Where | Problem | Size estimate | Fix |
|---|---|---|---|---|
| `SpecialistContributorMerge6612.byMint` | `engine/truth/SpecialistContributorMerge6612.kt:57` | One list per mint ever qualified, never dropped (contributions are readable for only 5 s) | about 400 B per mint, roughly 1-5 MB per hour | Past 2,000 mints, mints with no readable contribution go (`boundMints8305`, `:91`); census `contribMerge8305` |
| `TokenMergeQueue.pendingDiscoveries` | `engine/TokenMergeQueue.kt:77` | Removed only when emitted (96 per pass, from the first `maxScan` entries walked in hash order); entries no pass reaches stay for the process | about 600 B per entry | `pruneStale8305` (`:491`) drops entries not re-discovered for 30 min (soft) / 10 min (hard); census `mergeQueue8305` |
| `HeliusWebSocket.logStats8096` | `network/HeliusWebSocket.kt:785` | One row per mint ever subscribed | about 120 B per mint | Past 4,096 rows only subscribed mints keep theirs (`:798`) |
| `InsiderWalletTracker` caches | see F1 | | | Bounded; census `insiderTracker8305` |

**Checked and already bounded (no change):**
- `PumpFunWS` per-mint maps: setup TTL, lifecycle max, `framesByMint` retain at 2,000, daily reset.
- `LaunchTape7921.recs`: cap 3,000 / TTL 45 min. At about 24 creates/min that is about 1,080 recs. `buyerSol` per rec is unbounded; see below.
- `PairFlow8169`: cap 5,000 + TTL 30 min.
- `SignedTxLedger8085`: 400 rows.
- `HeliusSocketShare8191` / `HeliusRateGate8191`: no buffers; the token bucket is O(1).
- `ProviderLanes8285`, Cortex assess/pending/graded, forward labeler: 8289 caps, already in the census.
- `SmartMoneyFeed6394`: 5-minute deque.
- `ChainLaunchFeed8179` fetch pool and `HeliusWebSocket` callback pools: bounded `ThreadPoolExecutor` queues with AbortPolicy.
- `DexScreener` `dexFeedCache7381`: keyed by feed URL, so only a few entries.

**OkHttp dispatcher queue:** the app makes no async `enqueue` calls. The shared dispatcher's async slots are held only by the long-lived WebSocket readers (one per socket). Its queue therefore cannot grow per request, and the memory line now shows it: `dispatcher8305[queued= running= max=96/24]`, where `running` includes synchronous calls.

## Memory line

`MemoryGuard7977.statusLine7977()` now ends with:

```
net8305[dispatcher8305[queued=.. running=.. max=96/24] bodyCap8305[refused=..(endpoint:n,..) read=.. inFlightKB=.. largest=[endpoint:KB,..]]
        frames8305[oversizeDropped=..(stream:n) parseOomDropped=..(stream:n)] insiderScan8305[run=.. coalesced=.. inFlight=..]
        emergencyTrims8305=.. coalesced=.. last=..] stores8305=[every census store: name:count/KB, largest first]
```

`stores8305` lists every registered store, not only the census top 8.

## Tests: `Aate8305MemoryNetTest`

- **Body cap:**
  - caps per endpoint;
  - an oversize streamed body (no `Content-Length`) is refused while it is read, and in-flight bytes go back to 0;
  - an under-cap body reads whole and is measured;
  - a declared oversize length is refused before reading;
  - 101 and SSE responses pass through;
  - the cap is the shared client's first interceptor.
- **Frames:**
  - an oversize frame is dropped and counted; malformed JSON returns null;
  - an OOM inside the guard returns null, is counted and calls the trim hook (`dropOom8305` as well);
  - other exceptions are not swallowed;
  - the emergency-trim cadence (pure check) and its wiring;
  - every stream parses through the guard and none can die of an OOM;
  - proof files are untouched.
- **Bounds:**
  - insider scan admission (single-flight, TTL);
  - Jupiter list cadence;
  - contributor lists held under the cap after 2x the cap;
  - merge queue stale prune keeps fresh entries and removes stale ones;
  - the new census stores are registered and the memory-line keys are present;
  - `storesLine8305` formatting.

## Still unknown

- **What exactly filled the heap.** There is no heap dump. The census now attributes the network side (in-flight bodies, insider caches, merge queue, contributor lists, registry). The next report's `unattributedMb` together with `bodyCap8305 largest/refused`, `insiderScan8305 coalesced` and `frames8305` should tell whether F1/F2 were the holders. If `unattributedMb` still climbs while all of those stay flat, the next step is a heap dump (`Debug.dumpHprofData` on CRITICAL pressure) rather than more guessing.
- **Frame sizes.** OkHttp assembles a whole WebSocket message as a String before `onMessage`; that cannot be capped from the listener. A pathological multi-MB frame still costs its String once, but no longer its tree.
- **The 68 MB unattributed at restart.** Likely static and restored state: SharedPreferences maps (6.4 MB of XML, held as parsed maps), class data, TFLite / ONNX native-backed objects. Not investigated.
- **Per-launch buyer maps.** `LaunchTape7921.Rec.buyerSol` is a per-launch buyer map with no per-rec cap (a busy launch can have thousands of buyers). It is bounded by the 3,000-rec cap. Left alone because the fresh-launch agent owns `LaunchTape7921`; worth counting in the census next.
- **Other per-mint maps that are only pruned by an explicit clear call** (`DistributionDetector.exitScoreHistory`, `TokenSafetyChecker.liquidityHistory`, `SentimentEngine.mentionHistory`, `OrderbookImbalancePulseAI.history`, which is never written). These are small per entry and their growth was not measured; not changed.
