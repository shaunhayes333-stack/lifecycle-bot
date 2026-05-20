# CHANGELOG — AATE Bot

Progressive change log. Newer entries on top. PRD.md holds the static problem
statement + architecture; this file is the working log of fixes & decisions.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1027 — Orphan bot-loop exit (kill duplicate post-rescue coroutines) (Feb 2026, CI ⏳)

V5.9.1026 cap landed (chunk=32 confirmed in operator snapshot) but the
new snapshot revealed something deeper: loop=17 fired SEVEN
SUPERVISOR_CHUNK_TIMEOUTs and TWO POST_SUPERVISOR events. Cycle-ms
pattern was strictly alternating short/long
(`[12282, 675, 12545, 80, 12800, 180, 12421, 806, 11973, 694]`) — proof
that TWO botLoop coroutines were running concurrently on the dedicated
single-thread dispatcher, alternating at every suspension.

Root cause: V5.9.1023 rescue uses `observedDeadJob?.cancel(...)` which
is cooperative. A corpse wedged in a non-cancellable JNI socket-read
keeps running. When it unwedges later it resumes its botLoop alongside
the replacement — two concurrent supervisors, two PRE/POST_SUPERVISOR
pairs per "loopCount", chunks scheduled twice → throughput halved and
results discarded twice.

Fix: at botLoop boot, capture `currentCoroutineContext()[Job]` as
`myJob`. At the top of every while iteration, compare against the
canonical `loopJob` field. If they differ, emit `BOTLOOP_ORPHAN_EXIT`
and `return`. The fresh replacement is the sole authority; corpses
yield at their next safe checkpoint.

Touched: BotService.kt around L7745 (botLoop entry) and L7807 (while
loop top).

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1026 — Cap supervisor parallelism 96→32 to escape IO-pool contention (Feb 2026, CI ✅✅)

V5.9.1025 harvest fix landed but operator snapshot still showed
processed=0 deferred=96 every cycle. The new ForensicLog message
"abandoning 96 straggler(s); harvesting completed" CONFIRMED the harvest
path ran — but `active=96` at every timeout meant NO worker completed
within the 2.5s chunk budget.

Root cause: 96 parallel async workers launched on `Dispatchers.IO`
contend for only 64 default IO threads + OkHttp's per-host connection
pool (~10/host). Many workers can't even START running within 2.5s.
V5.9.175 set this to 96 assuming a 50-100 token watchlist, but the
operator snapshot shows 1056 tokens (PumpPortal "egg" spam — 14×
intaken in 7 seconds, EGG 12×, Sharpton 9×).

Fix: cap maxParallel at 32 in memeBootstrap mode (was 96).
- 32 fits inside the IO thread pool
- 32 fits inside per-host OkHttp connection budgets
- Each worker now has resources to complete a real `processTokenCycle`
  (Birdeye + Helius + V3 + lane evals ≈ 1.5-2s) inside the 2.5s budget
- 96 tokens → 3 chunks of 32 ≈ 7.5s, well inside the 15s paper-mode batch deadline

Touched: BotService.kt L9495 — the bootstrap-tier when{} mapping.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1025 — Harvest completed supervisor work on chunk timeout (Feb 2026, CI ✅✅)

V5.9.1024 ApiBackoff worked beautifully — DexScreener went from 49% SR →
99% SR, API_BACKOFF_ARMED fired 4×, paid-tier credit burn ended. But the
operator V5.9.1024 snapshot still showed `processed=0 deferred=96` on
EVERY supervisor cycle, with the watchlist exploding to 720 tokens
NEVER getting re-evaluated.

Root cause: the supervisor chunk failure path discarded ALL 96 jobs'
work when ANY job ran past the 2.5s chunk budget. Specifically:

```kotlin
withTimeoutOrNull(chunkBudgetMs) { jobs.awaitAll() } ?: List(jobs.size) { false }
```

`awaitAll()` is all-or-nothing — if even ONE job wedges past the timeout,
it returns null and we mark ALL 96 as deferred. With 96 parallel
`processTokenCycle` calls (each doing V3 + FDG + lane eval + safety +
network), losing the whole chunk to one straggler is the default state.
Result: watchlist of 720 tokens never re-evaluated by supervisor (only
freshly-discovered tokens via SCAN_CB direct path got any attention).

Fix: harvest each job's completion state independently AFTER the bulk
timeout. Completed jobs contribute their `await()` result (counted as
`processed`). Cancelled jobs are deferred. Stragglers still active get
canceled and marked deferred. Even if 1 job wedges and 95 complete, we
now report `processed=95 deferred=1` instead of `processed=0 deferred=96`.

Touched: `BotService.kt` runSupervisorPhase chunk loop (~L10110).

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1024 — Reactive per-host backoff (ApiBackoff) on 4xx/5xx (Feb 2026, CI ✅✅)

V5.9.1023 fix worked — bot is alive (82 BOT_LOOP_TICK in 311s uptime, normal
phase cycling, zero RESCUE_LAUNCHING wedge). But operator V5.9.1023 snapshot
exposed the next blocker:

```
SUPERVISOR_CHUNK_TIMEOUT firing every cycle, processed=0 deferred=96
dexscreener sr= 49%  4xx=406    (paid-tier rate-limit storm)
groq        sr=  0%  4xx=13
TokenMetaCache hit rate: 33.0%
```

Every supervisor chunk launches 96 parallel processTokenCycle workers; each
hits DexScreener. With DS at 49% success the chunk's 2.5s budget expires
before any of the 96 finish. Result: watchlist of 314 tokens NEVER scored
by the supervisor (trades trickle through only via SCAN_CB direct intake).

Existing RateLimiter is PROACTIVE only — it counts our own requests in a
sliding window but does NOT react to actual 429/403 responses. We keep
hammering the rate-limited endpoint, burning paid credits.

**Fix — ApiBackoff (new file)**

New `engine/ApiBackoff.kt`:
- Per-host consecutive-failure counter + lockout timestamp.
- Backoff schedule: 5s → 15s → 30s → 60s → 120s → 300s cap (consecutive).
- 429 and 403 jump to ≥30s on first occurrence (paid-tier / auth refused
  are the strongest "stop calling me" signals).
- 2xx success resets the counter immediately.
- Forensic events: `API_BACKOFF_ARMED`, `API_BACKOFF_CLEARED`.

**Wire — HealthAwareHttp.kt**

All keyless REST calls already route through `HealthAwareHttp.execute()`.
One edit covers DexScreener, PumpFun, Birdeye REST, Jupiter, and any
future host:
- Before sending: short-circuit with synthetic 503 if locked out (callers
  already handle `!resp.isSuccessful` as null → no call-site changes).
- 2xx → `ApiBackoff.markSuccess(host)`.
- 4xx/5xx → `ApiBackoff.markFailure(host, code)`.

Touched: `engine/HealthAwareHttp.kt`, NEW `engine/ApiBackoff.kt`.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1023 — Dedicated bot-loop dispatcher + stale-price PnL corroboration (Feb 2026, CI ✅✅)

Operator V5.9.1022 snapshot showed the bot completely dead — phase wedged
in `RESCUE_LAUNCHING` for the entire 10-minute log window. Every 180s the
heartbeat fired `HEARTBEAT_RESCUE_IDLE_PHASE_TIMEOUT` → `performService
ScopeRescue` → `scope.launch(Dispatchers.IO) { botLoop() }` with
`newJobActive=true`. But botLoop's FIRST line — `markProgress("BOTLOOP_
BOOT")` — never ran. No `BOTLOOP_STARTED`, no `BOTLOOP_RESCUE_THREW`. The
coroutine was queued but never got CPU time. CryptoAltTrader and the
PumpPortal WS kept running fine because they live on independent scopes.

**Q1 (THE bug — bot completely dead)**

Root cause: `Dispatchers.IO` thread-pool starvation. The supervisor phase
launches up to 96 parallel OkHttp `.execute()` calls on Dispatchers.IO.
When Helius/Birdeye/DexScreener wedge in JNI socket reads, those threads
ignore `cancel()` (native code is uncancellable). Each 2-min rescue
cancels the corpse and launches a NEW botLoop on the SAME saturated
pool. After 2-3 rescues all 64 default IO threads are wedged and new
launches queue indefinitely.

Fix: dedicated OS thread via `Executors.newSingleThreadExecutor`
wrapped as a `CoroutineDispatcher`. The thread is daemon + named
`AATE-BotLoop-Dedicated` + priority NORM+1. It is exclusive to botLoop
dispatch (startBot AND rescue paths). Even if every Dispatchers.IO
thread is wedged in JNI, this thread is alive and ready to execute the
first `markProgress("BOTLOOP_BOOT")` within milliseconds.

Note: `Dispatchers.IO.limitedParallelism(1)` was the troubleshoot-agent's
initial suggestion but does NOT solve the problem — that view shares
the underlying IO scheduler workers, so when the scheduler is saturated
the limited view also has zero free threads.

Touched: BotService.kt L376 (field decl), L3211 (startBot launch),
L7502 (rescue launch).

**Q2 (stale-price phantom-rug nukes)**

Operator V5.9.1022 also reported "first 60 trades are instant death".
Snapshot showed `STALE_LIVE_PRICE_RUG_ESCAPE` firing on positions where
the API feed went dark for 90-180s — DexScreener at 48% HTTP success
rate, Birdeye/Helius hitting paid-tier limits. A dark feed alone is
NOT a rug.

Comment block at L5971-5987 already documented the intended TWO-condition
rule: `(a) price age threshold AND (b) PnL from last-known price is NOT
actively winning`. But the actual code only checked (a). Added the (b)
gate: if last-known PnL > 0 (we were green before the feed died), the
rug hypothesis is weaker than the API-throttle hypothesis — emit
`STALE_LIVE_PRICE_HOLD_WINNER` forensic and ride out the dark period.

Touched: BotService.kt around L5993.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1022 — Triage round 4: whale spam + catastrophe + double-sell + live-sol (Feb 2026, CI ✅ build, smoke pending)

Operator V5.9.1021 install (build 2976, latest green APK). Bot finally
trading after V5.9.1021 SUPERVISOR fix, BUT three severe new symptoms:

**Q3 (HIGHEST PRIORITY — direct credit-burn driver)**

Whale-tx WS firehose at BotService.kt:5778. Operator log dump showed 200+
'🐳 PUSH: whale tx X… (0 accounts)' events in 4 s — EVERY ONE with zero
matching accounts. Each event ran InsiderWalletTracker.scanForSignals()
which hits Birdeye + DexScreener + Helius. Direct paid-tier drain
(operator burned $300 AUD this way). Fix: early-return on
accounts.isEmpty().

**Q1 (rapid-catastrophe firing on noise)**

BotService.kt:6072 catastropheThreshold was -14% for all modes. Operator
saw RAPID_CATASTROPHE_STOP firing 30-53 s after BUY on pump.fun launches
(price quantization 8.3E-5 → 1E-4 = +20% instant + paper-mode 18%
simulated slippage = -14% reached by pure noise). 31-loss cold streak.
Fix: paper-mode → -25%; live keeps tight -14%.

**Q2 (double-sell race)**

Executor.kt:519 — same mint 4vRgJ7 sold twice within 118 ms via
RAPID_CATASTROPHE_STOP then CASHGEN_STOP_LOSS. Lock releases on
completion + getOrPut creates fresh AtomicBoolean; second sell 118 ms
later re-acquires cleanly. Fix: new lastPaperSellCompletedMs
ConcurrentHashMap + 2 s cooldown. acquirePaperSellLock checks cooldown
FIRST. releasePaperSellLock writes timestamp BEFORE removing the lock.

**Q4 (bonus — live sol semantic)**

Executor.kt:11643 — V5.9.1018c fixed paperSell's `sol = pos.costSol` to
proceeds. The LIVE sell at line 11643 had the SAME bug. Fixed: sol =
solBack (Jupiter actual proceeds). Caught before going live.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1021 — extract runSupervisorPhase to free botLoop 64KB cap (Feb 2026, CI ✅✅)

V5.9.1020's inline withTimeoutOrNull + hard-timeout-log pushed botLoop
OVER the JVM 64 KB method-size cap (RELEASE compile: 'Method code too
large'). Extracted the entire V5.9.1020 SUPERVISOR body into private
suspend fun runSupervisorPhase(...). Returns SupervisorPhaseResult
(processed, deferred, hardTimedOut). botLoop now contains a 20-line call
site. Zero behaviour change vs V5.9.1020; only bytecode layout differs.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1020 — kill lying progressTicker + hard 20s SUPERVISOR outer timeout (Feb 2026, RELEASE compile FAILED → V5.9.1021)

Operator V5.9.1018c+V5.9.1019 snapshot showed botLoop stuck in
phase=SUPERVISOR for 652 s straight — no cycle in 10+ min, no trades.
ANR was solved but bot wedged.

Root cause (triage agent):
  1. progressTicker fired markProgress("SUPERVISOR") every 10 s
     UNCONDITIONALLY → freeze detector was LIED TO; heartbeat rescue
     never triggered.
  2. supervisorAbort elapsed-check lived INSIDE the chunk forEach. When
     the FIRST chunk hangs (Helius RPC dead, OkHttp blocks in JNI
     socket-read — withTimeoutOrNull cannot interrupt native), the
     forEach never advances → abort check never re-runs.
  3. V5.9.1012 detached workers + V5.9.1014 non-supervisorScope: each
     necessary but insufficient. No outer time fence stopped the forEach.

Fix: HARD outer time fence (withTimeoutOrNull maxBatchMillis+5s),
killed progressTicker, SUPERVISOR_HARD_TIMEOUT forensic event.
Behaviour-equivalent re-landed in V5.9.1021.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1019 — Structural: async universal SL sweep + deferred MainActivity UI (Feb 2026, CI ✅ build)

Operator V5.9.1018(c) snapshot symptoms:
  - Bot loop cycles oscillating [237, 8, 11531, 179, 18790, 54, 22674, 14, 44108, 575]ms
  - LOOP_HEARTBEAT_ALARM sinceLastTickSec=142 s
  - MainActivity.onCreate dominant ANR (11/20 samples) — 757-2154ms sub-frames
  - "Going into Pipeline still freezes the bot"
  - User mandate: "non-patchwork real fixes only"

**Fix A — async-ify runUniversalSlSafetyNetSweep**

Per-cycle universal SL safety-net sweep at end of botLoop was running
SYNCHRONOUSLY. For each open position it can invoke executor.requestSell()
→ paperSell() → full learning fanout. 5-10 open positions × ~5s each =
30-50 s sync work — matches the 44s max cycle exactly. Next cycle finds
positions closed → 8 ms — matches the alternation.

New `slSafetyNetInFlight: AtomicBoolean` + `launchUniversalSlSweepAsync()`
helper modelled exactly on V5.9.1010's `launchExitSweepAsync`: coroutine
worker on Dispatchers.IO, single-flight gate, 3 s hard watchdog releasing
the gate even if paperSell IO is blocked. New events:
UNIVERSAL_SL_SWEEP_START/_DONE/_SKIPPED/_TIMEOUT/_LATE_DONE.

**Fix B — Defer heavy MainActivity UI setup past first frame**

Extracted from sync onCreate path:
  - setupChartControls (1321 ms ANR — setOnClickListener → ImeFocusController)
  - setupApiKeyHelpLinks
  - setupChart (2154 ms — chart Matrix init via Cleaner.create)
  - setupSettings

Now run inside the existing `window.decorView.post {}` block after
bindViews() (which stays sync because it does cheap findViewById and
downstream code reads `chart`/button fields). decorView.post yields one
vsync (~16 ms) so the initial frame draws first, then heavy work runs
while user is still in the splash → main transition.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1018c — Triage round 3: PNL display bug + GeminiCopilot ANR (Feb 2026, CI ✅✅)

Operator V5.9.1017 snapshot complaints:
  • "buys are down 98% in the first seconds for no reason"
  • "ANR errors like gang signs" (15.8% stall, 33 s maxFrameGap)
  • Top ANR sites: GeminiCopilot.resetAllProviderState (14×) + MainActivity.onCreate (15×)

**Fix 1 — PaperSell sol-field semantic mismatch (the "98 % loss" lie)**

`Executor.paperSell` line 9131 was building the SELL Trade with
`sol = pos.costSol` (the *cost basis*, including all top-ups). Every
other SELL constructor in the file uses *gross proceeds*. Operator's
buRrYi case:
  - `BUY paper buRrYi sol=0.685` (initial entry, only one in 30-row window)
  - `SELL paper buRrYi sol=1.370 pnl=-1.115 reason=CASHGEN_STOP_LOSS`
The 1.370 is the post-top-up cost basis (V5.9.808 scale-in landed 2 top-ups
earlier than the visible window). Real proceeds were ~0.255 SOL. The SELL
*looked* like a 98 % rug only because the journal `sol` column was lying.

Patch: `sol = pos.costSol` → `sol = value` (gross proceeds, slippage- and
liquidity-capped). bumpLifetimeFor / WR classification only use pnlSol +
pnlPct → no impact on W-L counters. CanonicalLearning.exitSol is now
fed correct proceeds → learning signal corrected too.

**Fix 2 — GeminiCopilot @Synchronized + Thread.sleep deadlock-ish ANR**

`enforceCallSpacing` (line 1160) was `@Synchronized` AND called
`Thread.sleep(MIN_CALL_INTERVAL_MS − elapsed)` *while holding the
GeminiCopilot singleton monitor*. Any other @Synchronized method on the
singleton (notably `resetAllProviderState` called by BotService.startBot)
queued behind the sleep. ANR sampler caught the main thread frozen
inside resetAllProviderState 14 × per session — that was monitor
contention, not the function itself.

Removed `@Synchronized` from both methods:
  - `lastCallTimeByProvider` / `rateLimitedUntilByProvider` /
    `consecutive429ByProvider` are all ConcurrentHashMap.
  - `lastBlipDiagnostic` is @Volatile.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1018b — CI compile fix (Feb 2026, CI ✅✅)

V5.9.1018 build failed with:
  e: PipelineHealthActivity.kt:191:42 Expecting ','
  e: PipelineHealthActivity.kt:192:3  Expecting ')'

Cause: splitDumpIntoSections() wrote a literal newline INSIDE single-quote
char delimiters:
    current.append(line).append('
    ')

Fix: `'\n'` escape.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.999b — Bounded RPC + SP-prewarm (Feb 2026, CI ✅)

Operator V5.9.998: bot loop dies after 3 ticks + 33 ANR storm.

Root cause:
  `SolanaWallet.getTokenAccountsWithDecimals()` is a synchronous JSON-RPC
  with 3 × 2-program internal retries + 300/600 ms backoffs — worst case
  ~150 s per call. `botLoop()` is a single suspend coroutine on
  Dispatchers.IO. The 60 s pendingVerify watchdog (BotService.kt:7904)
  called this RPC synchronously, wedging the whole loop. The method body
  is at the JVM 64 KB cap so wrapping the loop body in `withTimeoutOrNull`
  isn't possible.

Fix:
  1. New `SolanaWallet.getTokenAccountsWithDecimalsBounded(timeoutMs)`
     — wraps the parent on a dedicated daemon executor (not IO, not
     ForkJoinPool.commonPool) with hard `Future.get` ceiling. On timeout
     returns emptyMap, hitting the V5.9.467 RPC-EMPTY rescue path.
  2. Bulk renamed all 48 sync sites across Executor, BotService,
     UniversalBridgeEngine, AntiChokeManager, all sell/execution
     reconcilers, MarketsLiveExecutor, CryptoUniverseExecutor,
     TokenLifecycleTracker, PositionWalletReconciler, etc.
  3. `CurrencyManager.prefs` is now `by lazy` + AATEApp.onCreate kicks
     a daemon SP-prewarm thread that calls `.all` on the 5 hot SP files
     so `awaitLoadedLocked()` is done off-main before any Activity reads.

═══════════════════════════════════════════════════════════════════════════════

## Earlier sessions (pre-fork)

See PRD.md (V5.9.671–V5.9.680b history) and the git log
(V5.9.807–V5.9.998 was authored by previous agent across multiple commits;
V5.9.1000–V5.9.1018 was authored externally between sessions).

Notable post-V5.9.810 milestones:
  - V5.9.999  — initial bot-loop-death fix (ML training + Killswitch IO wrap)
  - V5.9.1000 — pilot split of BotService into BotServiceLifecycleExt.kt
  - V5.9.1001-1003 — intake/scanner/supervisor extraction (broken)
  - V5.9.1004 — REVERT of the broken extraction, keep 3 real fixes
  - V5.9.1005 — soften pre-FDG V3 skip gates (FDG=0 regression)
  - V5.9.1006 — remove heartbeat redeadlock self-stop
  - V5.9.1007 — fix supervisorScope ticker deadlock (3-loop stop)
  - V5.9.1008 — harden supervisor ticker cleanup with try/finally
  - V5.9.1009 — async single-flight exit sweep; unblock POST_SUPERVISOR
  - V5.9.1010 — hard-timeout exit sweep gate + paperSell breadcrumbs
  - V5.9.1011 — fast paperSell journal async + main-thread stats guard
  - V5.9.1012 — DETACH supervisor token workers (stuck RPC cannot pin loop)
  - V5.9.1013 — remove MainActivity first-frame black-screen blockers
  - V5.9.1014 — remove structured supervisor wrapper hostage path
  - V5.9.1015 — lifecycle autosave must not restart/stop bot
  - V5.9.1016 — report navigation must not autosave or stop bot
  - V5.9.1017 — cap dashboard renders so UI cannot starve bot cycles
  - V5.9.1018 — full Pipeline report with sectioned rendering (broke CI char-literal)
