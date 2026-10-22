# AUDIT_DATA_8307 — data that was paid for and thrown away (live 5.0.8306)

Owner: "PumpPortal is funded!! ... There's sufficient data already being provided and thrown away." and "Can we not use
all the data Helius is sending ... It's on a paid plan, just don't go stupid."
Evidence: in 21,336 of 24,271 feature evals the tape was missing; Kimchi, LEONIDA, KIMOTHY and Cook were lost to
DATA_INCOMPLETE_DEFER_8136; Helius swap candles were budget-skipped 835 of 836 times; getTokenAccountsByOwner used
38.5k of 65.5k credits (1,416 of 1,871 reads were duplicates within 10 s); Codex was over quota; GeckoTerminal returned
429s; pump.fun returned 530s.

Paths are relative to `app/src/main/kotlin/com/lifecyclebot/`.

## 1. PumpPortal PumpSwap refusal was treated as a refusal of the whole stream

**Finding.** `network/PumpFunWS.kt` `isTradeAuthRefusal7432` (V5.0.8181) matched `"minimum balance not met"`. That
covers PumpPortal's *"Minimum balance not met for PumpSwap websocket data"*, so the handler at `onMessage` (now
:731-732) did all of this: latched `tradeAuthDenied7432`, cleared `tradeSubscriptions7278`, stopped the stream from
counting as observed, and started the reprobe/reconnect ladder. A single setup watch on a migrated mint
(`watchForSetup8191`, called from `DataOrchestrator.refreshMissingInputs8087`) could therefore take the bonding-curve
tape away from every coin. Clearing the subscription set also caused every migrated mint to be subscribed again on every
tick.

**Fix (V5.0.8307).**
- `isTradeAuthRefusal7432` (:166) no longer matches PumpSwap messages. A new check, `isPumpSwapRefusal8307` (:200),
  catches them, and it runs first in `onMessage`.
- `notePumpSwapRefusal8307` (:257) handles them. It parks only mints that have migration evidence: a migrate frame on
  this socket (`noteMigrationFrame8307`, :891) or a completed curve (`PumpCurveKeys7269.isGraduated7392`). It does not
  reconnect, does not set AUTH_DENIED and does not clear the subscription set.
- A mint that was in the refused frame but has no migration evidence stays subscribed. It is only marked
  `coverageSuspect8307` until its first print, so `tradeStreamCoversMint8136` never reports "no trades" for a mint that
  may simply not be covered.
- `admitForSubscribe8307` (:239) filters every subscribe path: `syncTradeSubscriptions7278`, `watchForSetup8191`,
  `registerFreshLifecycle7420` and the reconnect re-arm. Each parked mint is counted once and is not re-sent every tick.
- PumpSwap is retried on a slow ladder (15 min, doubling up to 4 h). A real PumpSwap frame resets the ladder.
- PumpSwap frames (`pool=pump-amm`) are now priced from their own SOL and token sizes and go to the tape only
  (`ammFrame8307`, :287). Before, they were dropped because they carry no curve reserves.
- The report shows `pumpswap=UNPROBED|OBSERVED|REFUSED_MIN_BALANCE|RETRY_DUE pumpSwap8307[refusals nextRetryS parked
  skippedSubs frames coverageSuspect migratedSeen]`, separately from `tradeStream=`.

## 2. PumpSwap / post-migration tape from data we already pay for

**Places where swaps or prints were received and then dropped:**

| Where | What was dropped | Now |
|---|---|---|
| `network/ChainLaunchFeed8179.kt` `handle8179` (`swapsFiltered`) | Every PumpSwap program-stream swap until the stream was parked | Swaps on a pool whose mint is known go to `PumpSwapTape8307.onProgramLogs8307` and then to TradeTape8100 (`swapsTaped8307`). Pools with an unknown mint are counted and never guessed. |
| `network/HeliusWebSocket.kt` PumpSwap decode (8191) | Pool and block time; the print reached the tape with no venue | `PumpSwapEvent8191` now carries `pool` and `tsSec`. `PumpSwapTape8307.noteDecoded8307` keys them by signature, and `ProofDataSwapTape8144` writes the print with pool and event time. |
| `engine/truth/HeliusSwapCandles7819.kt` `runJob` | The paid Enhanced-history page's swaps were thrown away once candles were built | `tapeBackfill8307` writes every swap to the tape (signature, venue, side, SOL, quantity, block time). A newly evaluated coin gets 30 minutes of bp/chg/shape straight away. |
| `network/PumpFunWS.kt` PumpSwap frames | Dropped at the reserves check | Taped (see §1). |
| Helius Enhanced socket (`network/HeliusEnhancedWS.kt`) | Used only for whale wallets | A second, bounded `transactionSubscribe` (accountInclude = held + near-decision mints) decodes pump.fun TradeEvent and PumpSwap Buy/SellEvent lines into the tape (`PumpSwapTape8307.onEnhancedTx8307`). |
| DexScreener batch (`network/DexscreenerApi.kt`) | Already routed in 8169/8285 (txns m5/h1, priceChange m5/h1 and liquidity reach PairFlow8169 and SetupFeatureResolver8169) | PumpSwap rows also register pool → mint, so program-stream swaps can be attributed to a mint. |
| `PUMP_TRADE_MARK_REGISTRY_REFUSED_7279` (`engine/BotService.kt` ~25675) | A refusal to promote a price into the canonical mark registry. The same frame already reaches TradeTape8100. | Nothing to route. This is a mark-registry decision, not a dropped print. |
| PumpFunWS frames for mints that are not subscribed | Not dropped: every trade frame is processed regardless of subscription | No change. |
| LaunchTape7921 buyer data | Not dropped. It is a launch-only (45 min) crowd tape by design, so "tape missing" for coins older than 45 minutes is expected and is not a core input (CORE_8084 = bp, chg, shape, liq). | No change. The core inputs now come from TradeTape8100. |

**Helius focus.** `DataOrchestrator.rotateHeliusFocus8269` now orders the near-decision mints so that migrated coins
PumpPortal cannot stream come first (`PumpSwapTape8307.migratedFirst8307`).

**Helius inventory (owner directive).**
- **Standard WS (HeliusWebSocket):** per-mint `logsSubscribe`, capped at 8 (held mints pinned plus near-decision focus).
  It decodes curve TradeEvent and PumpSwap events, and `accountSubscribe` covers wallets. ChainLaunchFeed8179 shares
  that socket for program `logsSubscribe`. Firehose streams are parked by the governor (40 per 10 s); PUMPSWAP is
  normally parked.
- **Enhanced WS (HeliusEnhancedWS, atlas):** `transactionSubscribe` for whale wallets, plus the new tape subscription.
- **Plan capability** is detected from the subscribe acknowledgement or error (`capabilityFromError8307`: -32601 or a
  plan/"not available" error means UNSUPPORTED_PLAN, in which case the tape subscription is not sent again; 401/403 is
  shown as REFUSED_code). When the capability is missing, the per-mint `logsSubscribe` decode and the one-page history
  backfill still feed the tape.
- **Enhanced Transactions** (`/v0/addresses/{a}/transactions`): HeliusSwapCandles7819. History is one bounded job per
  coin (2 pages, retried no sooner than every 5 min); its swaps now go to the tape as well.
- **DAS:** new `network/HeliusDasAuthority8307.kt`, a shared, batched (getAssetBatch, up to 100 mints), cached (30 min)
  read of mint authority, freeze authority, token program, decimals, supply and Token-2022 extensions. An unstated field
  stays null; it is never reported as "renounced". It is warmed for the tape set. **The selection agent can call
  `HeliusDasAuthority8307.request8307(mint)` then `peek8307(mint)`.**
- **Already in use elsewhere:** getTokenLargestAccounts (OnChainHolderConcentration7379), getPriorityFeeEstimate
  (PriorityFee8009), Sender (HeliusSender).
- **Report line:** `helius8307[capabilities{enhancedWs, whaleSub, tapeSub, tapeMints, tapeResubs, lastError}
  tape{width tx prints curve amm printsLastMin noSwap unattributed failedTx} das{...} credits{today projectedDay}]`. It
  appears in the chart-sources diagnostics through `PumpSwapTape8307.statusLine8307()`. Credits per method stay in the
  HeliusCreditEconomy7881 "top methods" line.

**Cost limits ("don't go stupid").**
- The tape subscription has its own meter stream, `HELIUS_TAPE_8307`, so it never draws on the whale stream's share.
- Its width is set by a byte governor: above ~1 MB/min it halves, below ~0.4 MB/min it grows by 2, with a maximum of 12
  mints. Held coins are always kept. Once the day's pace is exceeded, only held coins remain.
- It is re-subscribed at most once per minute, and only when the set has actually changed.

## 3. Helius credits: wallet token reads

**Finding.** `network/SolanaWallet.kt` already had a 5 s cache plus the WalletTokenReadGate8268 single-flight, but a
caller that hit IN_FLIGHT threw at once. Callers then fell into their own fallbacks: the bounded wrapper's six-way
known-mint per-mint fan-out, retries, or per-mint reads. Separately, about half of the "duplicates within 10 s" are the
paired Tokenkeg and Token-2022 reads of one snapshot: the same method and owner key in LoadTelemetry8169. Those are not
real duplication.

**Fix.**
- `sharedTokenRead8307` (:1514) makes every caller of the strict reader join the read in flight. It waits up to 4.5 s
  (`WalletTokenReadGate8268.awaitIdle8307`) and then takes the published snapshot.
- `getTokenAccountsWithDecimalsFresh8307(notBeforeMs)` serves only a snapshot observed after `notBeforeMs` and joins only
  a read that started after it. Concurrent forced reads therefore collapse into one.
- After a transaction, `bustNow` invalidates the snapshot as before, so the read in flight is superseded and exactly one
  new read runs.
- The TTL stays at 5 s on purpose: post-buy polling must not see a stale snapshot for longer than before.

**Freed budget goes to decision coins.** `HeliusCreditEconomy7881.admitPriority8307` (:370) lets a held or
near-decision `HeliusSwapCandles7819` job (`admitCredits8307`) use the day's on-pace headroom beyond the candles' own 8%
share. It never goes over the paced daily budget. The count is shown as `priorityAdmit8307=`.

## 4. Codex quota, GeckoTerminal 429, pump.fun 530

- **Codex:** `engine/chart/ChartSources7955.kt` `call()` now detects a used-up plan quota (`quotaExhausted8307`:
  "usage limit" or "quota", including inside a 200 GraphQL body, or 402). It rests the provider until
  `quotaResetMs8307`, which is the provider's reset header, then Retry-After, then the next UTC day. `claimWithin`
  returns at once while the provider is resting, so no call or retry is made in between. ChainLaunchFeed8179 skips the
  poll entirely and reports `codex8179[state=QUOTA_EXHAUSTED quotaSkips8307 resetsInMin8307]`.
- **GeckoTerminal 429:** ChartSources7955 rests for Retry-After when the response gives one (`rateLimitRestMs8307`) and
  doubles that provider's own slot gap (`nextPaceMs8307`, up to 6× the base gap; each run of 10 successes halves the
  excess). `SolanaOhlcvFeed6916.cooldownMs8307` also honours Retry-After. HostCircuitInterceptor already honoured it.
- **pump.fun 530:** `network/HostCircuitInterceptor.kt` `pumpFunLabel8307` gives each pump.fun host its own lockout
  label. frontend-api keeps `pumpfun`; others become `pumpfun_swapapi`, `pumpfun_advancedapiv2` and so on. A 530 on one
  host no longer locks the others out or counts toward SellOnlySafeMode's pump quorum.
- All rests are per provider or per host. No other source is blocked.

## Tests

- `app/src/test/kotlin/com/lifecyclebot/engine/Aate8307DataTest.kt`: scoped refusal; slow ladder; a curve mint is never
  parked; no reconnect or deny; AMM frame pricing; PumpSwap pool and time decoding; Helius PumpSwap print keeps its pool;
  program-stream attribution only for a known pool; migrated mints first in the focus; parsed stream decoding (curve and
  PumpSwap), plus refusal of failed or excluded transactions; width governor; tape set keeps held coins;
  re-subscribe rate limit; capability classification; history page goes to the tape; DAS parse treats unstated as
  unknown; wallet-read join and fresh semantics; priority admission never passes the day's pace; Codex quota and reset;
  Retry-After and pacing; per-host pump.fun labels.
- `Aate8181PumpPortalKeyTest` was updated on purpose (the PumpSwap refusal is no longer a stream refusal).
  `Aate8169DataTest` was updated for the new `note8285` call shape.

## Remaining

- PumpSwap prints from PumpPortal AMM frames use venue `pump.amm`, while Helius prints use the pool address. Only one of
  them is live at a time (PumpPortal currently refuses PumpSwap), but if both arrive the tape reads the newest venue.
- The Enhanced tape subscription needs the plan to allow `transactionSubscribe`. When it is unsupported, the fallback is
  the 8-slot per-mint `logsSubscribe` plus the history backfill. Raising that cap is a memory and bandwidth decision
  (see 8066/8094).
- Whether a candidate can trade with only "optional" inputs missing is decided by admission (gsel), not here. This build
  only makes the inputs exist.
- Projected credits per day are now reported (`helius8307 credits{projectedDay}`). The live run needs to confirm the
  pace is back under 300k/day.
