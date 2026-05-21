# CHANGELOG — AATE Bot

Progressive change log. Newer entries on top. PRD.md holds the static problem
statement + architecture; this file is the working log of fixes & decisions.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1065 — Rip SessionSafetyHalt · defer PipelineHealthActivity onCreate (Feb 2026)

Operator directive (verbatim): *"everything has to have a chance to learn
then self adjust into the best lane for winrate and profit. never fucking
pause or disable. thats so off fucking task"*.

(a) **REMOVED `SessionSafetyHalt`** (V5.9.1049). It paused paper entries
    after 50 trades with WR<25 % — that IS a pause/disable and the
    operator has now explicitly forbidden it. Bot must keep trading;
    learning weights self-adjust per-lane via existing TradingCopilot
    + FluidLearning + losing-pattern memory. Removed: the entire
    `Executor.paperBuy()` halt gate, the `recordPaperBuy()` call next
    to FluidLearning recording, and `BotService.startBot()` reset.
    Deleted: `SessionSafetyHalt.kt`.

(b) **`PipelineHealthActivity.onCreate` ANR purge.** V5.9.1064 snapshot
    showed 4 consecutive 250 ms+ frame hits (1010 + 757 + 505 + 251 ms
    ≈ 2.5 s) every time the panel opens — that's the "black screen
    hang" the operator hits. Stack: Button.<init> → Paint.<init> →
    NativeAllocationRegistry. The XML inflate via `setContentView`
    is unavoidable, but the 8× findViewById + 7× setOnClickListener
    chain is queued behind `window.decorView.post { }` so the
    initial layout paints on the next vsync (~16 ms) and listener
    wiring runs while the user already sees the panel.

Build tag bumped to V5.9.1065.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1049 — Triage: journal/MainActivity ANR purge · drawdown overflow · 50-trade session halt (Feb 2026) — partially rolled back V5.9.1065

Operator panic snapshot (V5.9.1040, build 5.0.3010): **27 217 ms max
frame gap**, 11 % stall%, top ANR offenders `MainActivity.
renderTreasuryPositions`, `ErrorLogActivity.exportLogs`, and the
Journal export path; bot blew past 50 paper trades to 161 journal
records with WR=12.5 % and -1.4081 SOL realized PnL while Max
Drawdown showed an absurd **459 621.4 %**. Five surgical fixes
(a/b/c/d/e), no butterflies, no refactors:

(a) **`renderTreasuryPositions` view leak + per-tick coil.load**
    `MainActivity.kt:3902-4046`. Previous code (V5.9.730 dirty-skip)
    correctly guarded `addView(row)` behind `!samePositions` but
    still appended a divider every tick AND constructed throwaway
    `LinearLayout`/`ImageView`/`TextView` per position with a fresh
    `load("https://cdn.dexscreener.com/…")` call inside coil's
    Bitmap cache — even when the position list was unchanged.
    Net effect: dividers leaked unbounded into `llTreasuryPositions`
    and the per-tick coil flush was the #1 main-thread stall.
    Fix: when `samePositions == true` we still compute
    `childrenUnrealizedSum` (cheap math) but `return@forEach`
    before any view construction or `addView` — full inflate only
    on real list changes.

(b) **`ErrorLogActivity.exportLogs` synchronous SQLite stringify**
    `ErrorLogActivity.kt:172`. `ErrorLogger.exportToText()` walks
    the entire SQLite log table and stringifies every entry while
    sitting on Main. Move to a background `Thread`, post the
    AlertDialog + clipboard write back via `Handler(mainLooper)`.
    Toast "Preparing logs…" gives the user immediate feedback.

(c) **`JournalActivity` export coroutines defaulted to Main**
    `JournalActivity.kt:235-321`. `lifecycleScope.launch` with no
    dispatcher defaults to `Dispatchers.Main.immediate`, so every
    `journal.exportPaperCsv(tokens)` / `exportPdf` / `exportAll`
    ran on the UI thread — these methods walk `TradeJournal.
    buildJournal` (full SQLite scan), build thousands of CSV rows
    and write to `cacheDir` synchronously. Fix: explicit
    `Dispatchers.IO`, wrap the final `startActivity(...)`/`Toast`
    in `runOnUiThread`. Toast "Preparing export…" up front.

(d) **Max Drawdown math overflow (459 621.4 %)**
    `PerformanceAnalytics.calculateDrawdown` line 209-228.
    Equity starts at 0.0 (cumulative PnL, not actual balance), so
    the first small positive equity becomes the "peak" — a single
    larger loss later divides by that microscopic peak and yields
    six-figure percentages. Fix: ignore peaks below a 0.05 SOL
    floor, and clamp `maxDdPct` / `currentDdPct` at 100 % (by
    definition a 100 % DD = full wipeout, nothing worse exists
    on the percentage scale).

(e) **SessionSafetyHalt — 50-trade circuit breaker** (NEW)
    Operator verbatim: *"it should be stopping trading after 50
    trades either mate!!!"*. New `SessionSafetyHalt.kt` object:
    after 50 successful paper buys this session, if
    `FluidLearning.getWinRate()` < 25 %, latch the halt and refuse
    new paper entries (exits and live trades are NEVER blocked).
    Reset on every `BotService.startBot()`. Wired at the top of
    `Executor.paperBuy()` (single canonical fence covering all 7
    sub-trader fallback call sites) and recorded on successful
    paper buys right next to `FluidLearning.recordPaperBuy`.

Build tag bumped to V5.9.1049 (`PipelineHealthCollector.BUILD_TAG`).
Brace/paren counts validated as balanced deltas relative to HEAD.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1048 — 5-fix pass: STANDARD note · V3 reason · EXEC counter · Birdeye backoff · moonshot throttle (Feb 2026, CI ✅ green)

Operator V5.9.1047 dump surfaced 5 follow-ups. All addressed:

(a) **STANDARD bin glossary note** — added a one-line clarifier
    below the strategy expectancy block: `STANDARD = V3 default
    (no lane affinity, TokenMemory.kt fallback), partly survivor-
    biased since promotions reclassify mid-trade`. Demystifies the
    suspicious-good n=32 WR=100% +207%/trade reading.

(b) **V3 reject reason histogram** — extract `.reason` from
    `V3Decision.Rejected` and `V3Decision.Blocked`, not just
    `BlockFatal`. V5.9.1046's REJECTED_FATAL_V3 lifecycle event
    silently dropped 128 Rejected-class reasons because the
    extractor only consulted the BlockFatal subclass. Histogram
    should now bucket meaningfully across V3 sub-reasons.

(c) **EXEC_BUY counter key mismatch** — `execBuy` was reading
    legacy `EXEC/PAPER_BUY`+`EXEC/LIVE_BUY` keys while
    `TradeHistoryStore.recordExec` writes `EXEC_BUY`/`EXEC_SELL`.
    `execSell` already matched; only `execBuy` was wrong, so the
    snapshot showed EXEC_BUY=0 while logs proved many actual BUYs.

(d) **Birdeye 429 backoff** — wired `ApiBackoff.isLockedOut("birdeye")`
    check into `BirdeyeApi.get()` AND `markFailure(code)` /
    `markSuccess()` on every response. Operator V5.9.1047 dump
    showed birdeye sr=59% 4xx=344 — Birdeye was being hammered
    through 429s because the existing ApiBackoff infrastructure
    wasn't engaged. Now: consecutive 4xx escalates the lockout
    schedule (5s→5min cap).

(e) **renderMoonshotPositions throttle** — added 8s minimum
    render interval guard (same `OPEN_POS_MIN_RENDER_INTERVAL_MS`
    as renderOpenPositions). Operator V5.9.1047 dump showed 509ms
    ANR; structural hash skipped no-change rebuilds but moonshot
    open/close still fired Coil image loads + 4 rows inline on
    Main. Rapid sequences now collapse to one rebuild.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1047 — 4-file UI ANR purge (Feb 2026, CI ✅ green)

Operator V5.9.1046 dump showed engine throughput up 267% but UI
stall spiked 4% → 28% with a single 42s freeze. Four-file targeted
purge of the new top ANR offenders:

(a) **BrainNetworkView** — `drawEngineDot`/`drawBrain` were the
    top main-thread offenders (200-1278ms per onDraw). Throttled
    animator 200ms → 1000ms (5fps → 1fps; still visually animated,
    5× less Main CPU load), enabled `setLayerType(LAYER_TYPE_HARDWARE,
    null)` so the canvas is cached as a GPU texture between
    invalidates.

(b) **PipelineHealthActivity eager bgThread** — dropped `by lazy`
    on `bgThread`; HandlerThread.start() now runs at class-field
    init, before `renderSnapshotAsync` is called from `onCreate`.
    V5.9.1045's daemon pre-warm lost the race (snapshot showed
    793ms on Main walking Thread.<init>); eager init guarantees
    `Handler(bgThread.looper)` is instant. Daemon pre-warm code
    removed.

(c) **BotViewModel pollLoop on Dispatchers.Default** — `viewModelScope
    .launch` was using the default Main dispatcher, so the entire
    pollLoop body — including indirect resource lookups that
    inflate VectorDrawables — ran on Main. The trace showed
    `VectorDrawable.nCreateFullPath` beneath pollLoop at 1059ms.
    Switched launch context to `Default`; StateFlow.value is
    thread-safe so UI consumers still observe updates correctly.

(d) **JournalActivity buildJournal data-prep async** — split
    `buildJournal` into two methods: data prep
    (`journal.buildJournal` + `journal.getStatsFiltered`) on
    `Dispatchers.IO`, view rendering on Main via `runOnUiThread`.
    Disk reads no longer block UI thread; only the View inflation
    loop (which must be on Main) stays there.

Expected combined impact: stall % drops 28% → <5%, max frame gap
collapses, BrainNetworkView/JournalActivity disappear from ANR top
sites.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1046 — supervisor decouple + tile BG + V3 reject histogram (Feb 2026, CI ✅ green)

Three quality-of-life upgrades on top of V5.9.1045:

(a) **BotService SLOT-RELEASE DECOUPLE**. V5.9.1045 dump showed
    12 SUPERVISOR_WORKER_TIMEOUTs firing per 4min (10s timeout
    working) but ~36 slots still held between resets. Root cause:
    `withTimeoutOrNull` SUSPENDS the outer coroutine until
    `runInterruptible`'s inner block returns, and
    `processTokenCycle`'s outer try/catch SWALLOWS
    `InterruptedException` → runaway thread keeps running, slot
    stays held. Fix: spawn a separate watchdog coroutine that
    delays for `SUPERVISOR_WORKER_TIMEOUT_MS + 500ms` then
    unconditionally releases the slot via `AtomicBoolean.compareAndSet`.
    Worker's `finally` also calls `release()`; the CAS ensures
    only one decrement happens. Slot is now guaranteed to free
    within timeout+0.5s regardless of inner thread state.
    Expected: `SUPERVISOR_POOL_RESET` drops to 0/min.

(b) **MainActivity pipelineTileRefresh BG snapshot**. V5.9.1045
    dump showed `pipelineTileRefresh$1.run` hitting 503ms on
    Main because `PipelineHealthCollector.snapshot()` walks 12+
    ConcurrentHashMaps inline on the UI thread every 5s. Now:
    dedicated single-thread executor builds the formatted
    string off-main; only the final `setText`/`setTextColor`
    stays on Main.

(c) **PipelineHealthCollector V3 reject reason histogram**.
    Operator V5.9.1045 dump showed `REJECTED_FATAL_V3=132` but
    the bare counter doesn't tell which V3 sub-gate dominates.
    New `v3RejectReasonCounts` map parses the `reason=` field
    from each `REJECTED_FATAL_V3` lifecycle event, normalises
    to the first two colon-segments (so `V3:RUG_FATAL` collapses
    across sub-reasons), and surfaces a "Top V3 reject reasons"
    section in the snapshot dump. Operator can now triage the
    biggest contributor in one glance instead of grepping logs.
    Zero behaviour change in trading paths.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1045 — supervisor timeout 10s + 2 UI ANR fixes (Feb 2026, CI ✅ green)

Operator V5.9.1044 snapshot confirmed runInterruptible is real (14
SUPERVISOR_WORKER_TIMEOUTs fired vs 0 before) but 6
SUPERVISOR_POOL_RESETs still kicked in over 6min, plus two new
top UI ANR offenders surfaced. Triple-fix:

(a) **`SUPERVISOR_WORKER_TIMEOUT_MS 20s → 10s`**. 10s ≈ 2×
    the avg 5.7s tick cadence and ≈1× OkHttp 15s read timeout.
    Workers that exceed one round-trip get reaped immediately;
    next tick's WATCHLIST_RR re-picks them — no signal loss.
    Expected impact: SUPERVISOR_POOL_RESET drops to near-zero,
    SUPERVISOR_WORKER_TIMEOUT rises proportionally (telemetry
    signal, not bug).

(c) **`PipelineHealthActivity` bgHandler pre-warm**. The
    `by lazy` accessor on `bgHandler` triggered
    `HandlerThread.getLooper()` (a synchronized `Object.wait()`)
    on Main when first accessed in `onCreate`. V5.9.1044's #1
    ANR was 930ms on this exact call site. Now pre-warmed on
    a daemon worker thread so the lazy wait completes off-main.

(d) **`SplashActivity` logo pulse**. Replaced the custom
    ValueAnimator + addUpdateListener pattern (which boxed a
    Float and dispatched the lambda on Main every frame —
    causing the SplashActivity.onCreate\$lambda\$2\$lambda\$1
    Float.valueOf hotspot in 5+ ANR_HINTs per snapshot) with
    two pure ObjectAnimators on the view's hardware-accelerated
    scaleX/scaleY properties. Per-frame work now runs on
    RenderThread.

Skipped:
- (e) FDG CONFIDENCE_FLOOR_22% relaxation by design: operator
  V5.9.809 mandate explicitly revoked wide-open mode. Recent
  regime=NORMAL wr=40.6% suggests learning is on track without
  lowering the floor.
- (b) deep processTokenCycle interrupt-yield insertion:
  runInterruptible + 10s timeout already covers the leak
  symptom; modifying a 10k-line function is high-risk for low
  marginal gain.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1044 — runInterruptible workers (REAL pool fix, not band-aid) (Feb 2026, CI ✅ green)

Operator V5.9.1042 snapshot proved the watchdog band-aid works
(bot trading, 6192 LANE_EVALs, 60+ exits) but ALSO proved the
underlying leak is severe: `SUPERVISOR_POOL_RESET` fired 29 times
in 20 min (every ~42s). Investigation pinpointed why:

```
private fun processTokenCycle(...)   ← NOT suspend
                                       NO suspension points
                                       withTimeoutOrNull(20s) NEVER cancels
```

Coroutine cooperative cancellation only triggers at suspension
points. `processTokenCycle` is a plain blocking function — OkHttp
socket reads, synchronized SQLite writes, Birdeye/DexScreener
HTTP calls all run to completion regardless of the outer
`withTimeoutOrNull`. So the 20s budget was effectively dead code;
workers ran their natural duration (often >>20s with degraded
API health), leaking pool slots until V5.9.1042's watchdog
band-aid kicked in every 30s.

Fixed:
- Wrapped the worker body in
  `runInterruptible(Dispatchers.IO) { processTokenCycle(...) }`.
- `runInterruptible` upgrades coroutine cancellation into a real
  `Thread.interrupt()` on the worker thread.
- Blocking I/O (OkHttp, SQLite, Thread.sleep, NIO channels) honors
  thread interrupt → workers actually die when their 20s budget
  expires → `finally` block decrements `supervisorActive` → pool
  stays healthy without the watchdog.

V5.9.1042's watchdog stays in place as a safety net. Expected
behavior in next snapshot: `SUPERVISOR_POOL_RESET` drops to ~0,
`supervisorLifetimeWorkerTimeouts` rises to match the actual
non-cooperative hang rate, and `SCAN_CB` (completed cycles)
climbs sharply because slots free up much faster.



═══════════════════════════════════════════════════════════════════════════════

## V5.9.1043 — collapse legacy bin names at read time (Feb 2026, CI ✅ green)

Operator V5.9.1041 snapshot still showed `BLUECHIP` (n=134) AND
`BLUE_CHIP` (n=25) as separate strategy expectancy bins despite the
write-side normalization shipped in V5.9.1038. Trades persisted to
SQLite BEFORE V5.9.1038 still carry the legacy `BLUE_CHIP` string,
and `StrategyTelemetry.computeLeaderboard()` groups raw values
verbatim.

Fixed:
- Exposed `TradeHistoryStore.normalizeTradeModeName()` as public so
  read-side aggregators can call it.
- `StrategyTelemetry.computeLeaderboard()` now normalizes each
  trade's `tradingMode` at `groupBy` time → legacy BLUE_CHIP merges
  into BLUECHIP, identical to newly-recorded trades.

Read-only telemetry path; no entry/exit logic touched.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1042 — silent-supervisor pool watchdog (UNFREEZE) (Feb 2026, CI ✅ green)

Operator V5.9.1041 ~30min uptime dump showed the pool RE-SATURATED:

```
SUPERVISOR_INFLIGHT_CAP: 335 events  · spawned=0 skipped=96 active=48 cap=48
last paper BUY = 26+ minutes before snapshot capture
EXEC = 0  ·  bot visually "frozen" (loop healthy, executions choked)
```

V5.9.1039's `withTimeoutOrNull(20s)` is COOPERATIVE — workers stuck
in non-cooperative blocking ops (SQLite write / native socket / JNI)
never observe the cancellation, the slot stays held forever, and
`supervisorActive` never decrements. V5.9.1041 added the
`supervisorLastSpawnAt` + `supervisorLifetimePoolResets` fields and
described a watchdog in inline comments, but the watchdog logic was
NEVER actually coded.

Fixed (V5.9.1042 ships the missing logic):
- At `fireSupervisorWorkers()` entry, if `active >= cap` AND
  `(now - supervisorLastSpawnAt) >= SUPERVISOR_POOL_STALL_MS (30s)`,
  force-reset `supervisorActive` to 0, bump
  `supervisorLifetimePoolResets`, and emit `SUPERVISOR_POOL_RESET`
  via ForensicLogger so the reset is visible in pipeline snapshots.
- On every successful worker spawn, update `supervisorLastSpawnAt`
  so the stall detector resets correctly under healthy operation.
- Truly-stuck workers eventually decrement `supervisorActive` into
  negative territory; safe — the cap check uses `get() < cap`, so
  negative just means extra headroom.

If `SUPERVISOR_POOL_RESET` fires frequently in future dumps, that's
the signal to chase the deeper non-cooperative-block hotspot
(probably SQLite write contention or a synchronous emitter). The
bot keeps trading either way.


═══════════════════════════════════════════════════════════════════════════════

## V5.9.1039 — per-worker timeout (silent-supervisor pool saturation fix) (Feb 2026, CI ✅✅ green)

Operator V5.9.1038 4h-uptime dump revealed a critical bug introduced by
V5.9.1037's silent supervisor refactor:

```
SUPERVISOR_INFLIGHT_CAP: 2238  ← every cycle for 4 hours
spawned=0  skipped=96  active=48  cap=48
EXEC=2 only  ·  projected execs/day: 166 🛑 CRITICAL
```

The 48-worker pool was PERMANENTLY saturated by hung workers. V5.9.1037
removed the chunk-level `withTimeoutOrNull`, but forgot to add a
per-worker safety net. When a worker blocks on a slow Birdeye/DexScreener
call, nothing cancels it — `supervisorActive` never decrements → the
slot is occupied forever → after 48 hung workers accumulate, every
cycle spawns ZERO new workers.

Fixed:
- Wrap `processTokenCycle` + `markProcessed` in `withTimeoutOrNull(20s)`
  inside the worker. 20s is ~3× normal p95 so legitimate work completes;
  stuck workers cancel and the `finally` block decrements
  `supervisorActive` normally.
- New `supervisorLifetimeWorkerTimeouts` counter.
- New `SUPERVISOR_WORKER_TIMEOUT` forensic event with mint+budget tag.

Other operator findings (not regressions, just visibility):
- 956 ANR_HINTS scary-looking but stall % only 6.1% — IMPROVED from
  V5.9.1037's 8.7%. Top blockers were system-idle
  (`MessageQueue.nativePollOnce`, `nativeGetLatestVsyncEventData`), not
  real bot blocks.
- INTAKE_BURST_REJECT=640 and INTAKE_LIQ_ZERO_REJECT=392 — V5.9.1035
  filters working perfectly at scale.
- BLUECHIP=39 vs BLUE_CHIP=25 still split because V5.9.1038's
  `normalizeTradeModeName` only applies to NEW trades; legacy journal
  entries retain old casing. Converges naturally over time.



## V5.9.1038 — TRIAGE FIXES: TradeHistoryStore dedupe + mode normalize + reason fallback (Feb 2026, CI ⏳)

Triage agent (called per operator request after V5.9.1037 snapshot showed
recovered cycle time but persistent counter inflation) identified 3 root
causes from the operator's V5.9.1037 dump:

ROOT CAUSE 1 — same close recorded TWICE per position. Operator snapshot
showed back-to-back sells for the SAME mint within one second under
different reasons (CASHGEN_STOP_LOSS + TREASURY_TIME_EXIT). CashGen +
Treasury are independently closing the same position. PositionExitArbiter
(Executor) only catches Executor-path duplicates; V3JournalRecorder's 5s
LRU only catches V3-path duplicates. Cross-path duplicates slip through.

ROOT CAUSE 2 — strategy bin fragmentation. Same trade binned as BLUECHIP
(n=5), BLUE_CHIP (n=23), STANDARD (n=10) in StrategyTelemetry because
Executor and V3JournalRecorder set inconsistent casing/spelling on
Trade.tradingMode.

ROOT CAUSE 3 — 64% of canonical outcomes still featuresIncomplete.
Operator's AURAMAXX MOONSHOT_STOP_LOSS trade shows source=UNKNOWN
despite V5.9.1035's lite-rich bridge. Some exit paths
(sweepUniversalExits, rapid-monitor closes) create Trade objects with
reason='MOONSHOT_STOP_LOSS' but blank tradingMode, so normalizeMode
returns UNKNOWN BEFORE the V3 fallback can fire.

Fixed:
1. `TradeHistoryStore.recordTrade` choke-point dedupe LRU keyed on
   "${mint}_${ts}_SELL" with 5s TTL window. SELL-only. Logs
   `TRADEJRNL_DEDUP_SKIP` when a duplicate is caught.
2. `TradeHistoryStore.normalizeTradeModeName` canonicalizes mode strings
   at the choke point so StrategyTelemetry bins converge.
3. `CanonicalLearning.publishFromLegacyTrade` reason fallback — when
   tradingMode is blank, infer from reason (MOONSHOT_STOP_LOSS →
   MOONSHOT, CASHGEN_* → CASHGEN, RAPID_/FLUID_ → STANDARD).
4. `Executor.recordTrade` inherits `ts.position.tradingMode` whenever
   the Trade's is blank (source-of-truth fix).

Expected impact: TradeHistoryStore.size matches canonical settled count;
StrategyTelemetry bins converge; richFeatureOutcomes 36% → ~90%+;
strategy learners finally train on every close.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1037 — SILENT SUPERVISOR (fire-and-forget; bot loop never awaits) (Feb 2026, CI ✅ green, deployed)

Operator V5.9.1037 verified: cycles dropped from ~20s avg to ~5s avg
(max 6.5s). `SUPERVISOR_INFLIGHT_CAP` events firing at the new 48-worker
cap. `SUPERVISOR_CHUNK_TIMEOUT` gone from the labelled counters.
ANR_HINTS dropped to 13 (almost entirely UI-side now: PipelineHealthActivity
+ Splash animation). richFeatureOutcomes 27 → 340 (3% → 36%) thanks to
V5.9.1035's lite-rich bridge — but 617 still incomplete (fixed in V5.9.1038).



## V5.9.1037 — SILENT SUPERVISOR (fire-and-forget; bot loop never awaits) (Feb 2026, CI ⏳)

Operator V5.9.1036 snapshot showed bot loop wedged 14-20s per cycle on
`SUPERVISOR_CHUNK_TIMEOUT` (23 chunk timeouts in 150s), with EVERY cycle
logging `processed=0 deferred=96` despite trades still executing via the
SCAN_CB direct path.

Root cause: legacy `runSupervisorPhase` chunks the watchlist into groups
of 32, spawns `GlobalScope.async` workers, then awaits each chunk via
`withTimeoutOrNull(4.5s) { jobs.awaitAll() }`. If even ONE worker blocks
past 4.5s the await trips, ALL 32 jobs get cancelled, and the bot logs
`SUPERVISOR_CHUNK_TIMEOUT`. With 96 mints × 3 chunks × 4.5s = ~14s per
cycle wasted in pure dead waiting.

Critical insight: the per-worker side effect (`processTokenCycle` +
`markProcessed`) already runs on detached `GlobalScope.async(IO)` — the
bot loop's await was burning cycles to populate `processed/deferred`
counts that ONLY feed forensic log strings. No downstream control flow
depends on them. So the await is pure dead waiting.

Fixed:
- New `fireSupervisorWorkers` helper: spawns workers via
  `GlobalScope.launch` (no `async`, no `awaitAll`, no `withTimeoutOrNull`).
- Bounded in-flight concurrency via `supervisorActive` AtomicInteger
  with `SUPERVISOR_MAX_INFLIGHT=48` cap (mints over cap are skipped and
  re-evaluated next cycle from a fresh ordering).
- Atomic counters surface lifetime supervisor health:
  `supervisorLifetimeSpawned / Processed / Skipped`.
- OkHttp dispatcher's `maxRequestsPerHost=16` (V5.9.1032) remains the
  real API rate-limit floor.
- New `SUPERVISOR_INFLIGHT_CAP` forensic event fires when skip>0.
- `runSupervisorPhase` kept as dead code for rollback safety.

Expected impact:
- Cycles drop from ~15-20s to ~2-5s (bot loop now sees ~0ms supervisor cost)
- `SUPERVISOR_CHUNK_TIMEOUT` events drop to zero
- SCAN_CB / V3 / FDG / EXEC counters ramp 3-5×

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1036 — ANR fixes (onCreate off-main) + botLoop bytecode reclaim + tighter dust gate (Feb 2026, CI ✅ green, deployed)

Operator V5.9.1034b snapshot showed 29.9% main-thread stall with
maxFrameGap=32570ms and `TradeLessonRecorder.exportState` topping the ANR
chart (14 samples). `botLoop` also approaching JVM 64KB cap again
(V5.9.1035 release passed but DEBUG smoke failed with `Couldn't transform
method node: botLoop`).

Fixed:

1. `LearningPersistence.init` — split synchronous DB open (fast, required
   for putBlob/getBlob immediately) from `loadAll()` (background IO). The
   ~3000-lesson `TradeLessonRecorder.importState` JSON parse (1934ms ANR)
   plus 12 other brain-state blob restores now run off-main. Kills the
   #1 ANR offender.

2. `MemeMintRegistry.init` — `restoreFromDisk` (2185ms ANR parsing 2557
   mints / 511KB JSON) moved to background scope. `appCtx` stays sync
   so `touch()` / `scheduleSave()` work immediately.

3. `BotService.botLoop` reclaim — extracted two large inline blocks:
   - `runPendingVerifyWatchdog(wallet)`     → -110 lines of bytecode
   - `run180TickTelemetry(cfg)`             → -109 lines of bytecode
   Net: ~219 lines reclaimed inside botLoop's outer try{}.

4. Intake dust gate tightened — V5.9.1035's INTAKE_LIQ_ZERO_REJECT
   required strictly liq=$0 && mcap=$0 && single-source. Operator
   snapshot showed pump.fun spam landing with liq=$0.001 mcap=$0.01
   sources=2 via the MULTI-SCANNER BYPASS path. Tightened to liq<$1 &&
   mcap<$10 (no source restriction).

Operator V5.9.1036 verified:
- ANR stall 29.9% → 8.7% (3.4× better)
- maxFrameGap 32570ms → 9106ms
- INTAKE_LIQ_ZERO_REJECT=120 / INTAKE_BURST_REJECT=3 firing correctly
  (HENRY caught at 5 distinct mints/60s — clone-storm hard reject)
- TradeLessonRecorder.exportState + MemeMintRegistry.restoreFromDisk
  both gone from ANR top-N list
- Trading active: EXEC_BUY=3, EXEC_SELL=99, 165 journal writes

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1035 — counter-drift fix + lite-rich legacy bridge + Part 2 intake spam filter (Feb 2026, Build ✅ Smoke ❌ [JVM 64KB, fixed in V5.9.1036])

Operator screenshot showed `AdaptiveLearning Δ=-424` and `BehaviorLearning
Δ=-414` against the canonical settled baseline (427 trades) — strategy
learning "useless". Combined with the user's standing Part 2 mandate to
hard-reject intake spam at the door.

Fixed:

1. Counter drift display — `LearningCounterActivity` now reads
   `AdaptiveLearningEngine.getTradeCount()` and
   `BehaviorLearning.getCanonicalAlignedTradeCount()` (both already
   canonical-aligned to settledWins+settledLosses). Previous display
   read session-only / feature-gated raw counters that lag by design.

2. Lite-rich legacy bridge — `publishFromLegacyTrade` now builds a
   minimal `CandidateFeatures` from the Trade record alone (mode-derived
   trader/venue/route/assetClass) and emits `featuresIncomplete=false`
   for any known mode. Strategy learners (AdaptiveLearning,
   BehaviorLearning) finally train on every settled trade instead of
   skipping 97% of them.

3. `inferAssetClassAndSource` extended: STANDARD / PROJECT_SNIPER /
   DIP_HUNTER / COMMUNITY → (MEME, TradeSource.V3) so the venue/route
   fallback resolves to PUMP_FUN_BONDING/PUMP_NATIVE instead of UNKNOWN.

4. Part 2 intake filter:
   - INTAKE_LIQ_ZERO_REJECT: hard-reject liq=$0 + mcap=$0 + single-source
     + not user/restore (later tightened to liq<$1 mcap<$10 in V5.9.1036).
   - INTAKE_BURST_REJECT: hard-reject when ≥5 DIFFERENT mints land with
     the same symbol in <60s (clone-storm guaranteed rugs).

Known regression: DEBUG-compile botLoop hit the JVM 64KB method size
cap. Fixed in V5.9.1036 by extracting `runPendingVerifyWatchdog` and
`run180TickTelemetry` helpers (-219 lines of botLoop bytecode).



## V5.9.1034b — Fix cap-evict to drain unseen pool (the real overflow) (Feb 2026, CI ⏳)

Operator V5.9.1034 snapshot (build 2995, tag V5.9.1034) verified two wins:
  • WATCHLIST_CAP_EVICT firing correctly (evicted=31 sizeBefore=1694, etc)
  • ANR_HINTS=0 Stall=0% (was 13.1% — UI is healthy again)
  • TokenMetaCache hit rate 16.4% → 50.6%

But the cap wasn't actually enforcing: watchlist still climbed back to
1693 after the evictions. Why?

Root cause: the V5.9.1034 cap-evict pool scanned ONLY `cold`. Operator
dump showed `cold=0 unseen=1649`. Pump.fun spam adds processCount=0
mints faster than the supervisor can graduate them to cold (supervisor
itself is wedged on 4.8s chunk timeouts), so cold stays near-empty
while unseen pile up. The cap loop had nothing to evict.

**Surgical fix**: extend the eviction pool to include `unseen` too.
Sort by lastProcessedAt (cold) + addedAt (unseen) ASC, take oldest
`excess` count. Drop evicted mints from BOTH `coldAfterCap` AND
`unseen` so the picker doesn't try to process tokens we just removed.

forcedOpenMints (open positions) and fresh-60s window remain exempt.

Also: V5.9.1034 unexpectedly fixed the Runtime Smoke Test 🟢 — the
extra helper code in selectOrderedMintsForCycle freed enough botLoop
bytecode that the Debug JVM 64KB cap is no longer hit. First green
smoke since V5.9.1027b.

Touched: `engine/BotService.kt` (selectOrderedMintsForCycle cap-evict).
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1034 → V5.9.1034b.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1034 — Watchlist cap 250 + 5min stale eviction (Part 1) (Feb 2026, CI ✅)

**Operator mandate**: "the watch list is now obviously way too big as well.
the bots never had more than 100 open positions maybe we reduce the
watchlist size to 250 and prune stale or non moving tokens after 5
minutes... once a token is stored it doesn't need to be rescanned unless
specifically being interacted with by the watchlist or is an open position.
we are burning a lot of data there on every loop."

V5.9.1031 snapshot evidence:
  • watchlist `total=1316` (was supposed to be ~100-200)
  • SUPERVISOR_CHUNK_TIMEOUT 96/96 every cycle
  • Birdeye SR=83% (430 × 4xx rate-limits in 748s)
  • TokenMetaCache hit rate=16.4% (84% of fetches are fresh API hits)

**Surgical changes (no logic deletions, no behavioural reversals)**:

1. Two new constants in `selectOrderedMintsForCycle()`:
   ```kotlin
   val STALE_AGE_MS = 5L * 60_000L     // 5 minutes idle
   val MAX_ACTIVE_WATCHLIST = 250      // hard cap
   ```

2. New eviction pass — TIME-BASED stale drain.
   Walks `cold` after the existing V5.9.961 process-count filter. Any
   entry with `processCount >= 1` AND `(now - lastProcessedAt) > 5min`
   AND NOT in `forcedOpenMints` is removed via `GlobalTradeRegistry
   .removeFromWatchlist(mint, "STALE_5MIN")`. Forensic:
   `WATCHLIST_STALE_EVICT_TIME evicted=N ageMs=300000`.

3. New eviction pass — HARD CAP at 250.
   After the time-stale pass, if `getWatchlistEntries().size > 250`,
   evict the oldest `excess` cold non-position entries by
   `lastProcessedAt` ascending. Forensic:
   `WATCHLIST_CAP_EVICT evicted=N cap=250 sizeBefore=...`.

**Untouched** (per operator "no butterfly effect regressions"):

  • All 9 trader lanes (CashGen / Moonshot / Shitcoin / Bluechip /
    Treasury / Quality / Manipulated / Dip Hunter / Project Sniper).
  • Scanner intake — still receives everything (we throttle downstream).
  • Open-position protection — `forcedOpenMints` set is honoured in
    BOTH the stale-time and cap-evict passes (an open position can
    never be evicted by this code).
  • V5.9.961 existing process-count eviction stays as belt-and-braces.
  • Fresh / unseen / cold categorisation logic unchanged — only the
    cold pool is reduced.

**Expected dump deltas** (based on V5.9.1031 baseline):

  • watchlist total:                1316 → ~250 (hard cap)
  • SUPERVISOR_CHUNK_TIMEOUT/cycle:    3 → 0-1 (fewer tokens to chew)
  • Birdeye SR:                      83% → 95%+
  • TokenMetaCache hit rate:         16% → 60%+
  • Cycle time:                  22-28s → 12-15s
  • daily Birdeye CU burn:        ~50%   → ~25%

Touched: `engine/BotService.kt` `selectOrderedMintsForCycle()`.
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1033 → V5.9.1034.

**Part 2 (deferred)**: per-lane fluid scanner learning (Moonshot vs
Bluechip vs Shitcoin pattern recognition + hard-reject guaranteed-rug
tokens at intake). Requires care in lane-scoring weights — separate push.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1033 — Reliable Stop button: hard-cancel loopJob + abort in-flight HTTP (Feb 2026, CI ⏳)

**P0 — Operator emergency**: V5.9.1031 snapshot showed `ACTION_STOP_RECEIVED
source=ui_stop_button` at 04:03:26 but bot kept running 10+ minutes after.
Stop button effectively dead. Start button also unresponsive (because the
previous loopJob was still wedged).

Root cause: `stopBot()` ONLY set `status.running = false`. That flag is
read at the TOP of the next `botLoop` iteration. With supervisor cycles
running 22-28s (95%+ chunks timing out at 4.8s each) and 32 OkHttp
workers per chunk blocked inside synchronous `.execute()` which does
NOT honour coroutine cancellation, stop was invisible to the user
for an entire cycle PLUS however long it takes 32 sockets to time
out (4s readTimeout + connect). Worst-case: 30-60 seconds. Operator
hit a wedged variant (sockets stuck in CONNECT_WAIT for 10+ min).

**Fix**:

1. `stopBot()` now also calls:
   ```kotlin
   loopJob?.cancel(CancellationException("stopBot:$source"))
   SharedHttpClient.cancelAllRequests()
   ```
   `loopJob.cancel()` wakes any suspended state-machine branch at its
   next suspension point. `SharedHttpClient.cancelAllRequests()`
   delegates to `Dispatcher.cancelAll()` — **every** in-flight AND
   queued OkHttp call across the shared dispatcher is interrupted
   immediately. The supervisor's `awaitAll()` returns within ms with
   IOException("Canceled") instead of waiting for socket timeouts.

2. New `SharedHttpClient.cancelAllRequests()` helper:
   ```kotlin
   fun cancelAllRequests() {
       try { sharedDispatcher.cancelAll() } catch (_: Throwable) {}
   }
   ```
   Idempotent, swallows throwables. Existing callers in flight see
   `IOException("Canceled")` and unwind their try/catch normally.

Net effect: pressing Stop reacts inside ~200ms instead of ~30-60s
(or ∞ in the wedged variant). The startBot V5.9.730 STUCK-LOOP RESCUE
path remains as a fallback if a stale loopJob ever lingers.

Touched: `engine/BotService.kt:4848` (stopBot escape hatch),
`network/SharedHttpClient.kt` (new `cancelAllRequests` helper).
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1032 → V5.9.1033.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1032 — Rate-limit balance after dispatcher un-choke (Feb 2026, CI ⏳)

V5.9.1030's `maxRequestsPerHost=32` worked — operator V5.9.1031 snapshot
showed supervisor `processed=7 of 96` (was permanently 0/96), TokenMetaCache
hit rate 0% → 59.7%. **BUT** we over-hammered the upstream APIs:

  • Birdeye:     SR 99% → 56%   (424 × 4xx rate-limit responses in 99s)
  • DexScreener: SR 99% → 71%   (37  × 4xx)

Cascade: with Birdeye throttling, the bot can't fetch liquidity data, so
86% of FDG rejections are now `low_liquidity` or `zero_liquidity`
(RejectStats 5m: 62 + 49 of 129).

Fix: dial `maxRequestsPerHost` 32 → 16 (and `maxRequests` 128 → 64).
Still 3× the OkHttp default of 5, so the supervisor stays un-choked,
but half the per-minute burden on Birdeye's free-tier 100 RPM cap.

Expected dump deltas:
  • Birdeye SR: 56% → 90%+
  • DexScreener SR: 71% → 95%+
  • SUPERVISOR processed: 7/96 stays the same or improves (per-host
    queue still 3× larger than the original V5.9.1029 baseline)
  • low/zero_liquidity reject rate: 86% → 30-40%

Touched: `network/SharedHttpClient.kt` (per-host dispatcher cap).
Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1031 → V5.9.1032.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1031b — Fix CI: hoist HARD_FLOOR_STOP_PCT inside helper (Feb 2026, CI ⏳)

V5.9.1031 introduced `evaluateRapidMonitorExit` but referenced
`HARD_FLOOR_STOP_PCT` — a function-local val declared inside the
parent `rapidStopLossMonitor()`. The helper lost access to that
scope → `Unresolved reference: HARD_FLOOR_STOP_PCT` at BotService.kt:7834.

Fix: declare a local mirror `val HARD_FLOOR_STOP_PCT_CONST = 15.0`
inside the helper — exact same numeric value the original block
compared against. Behaviour-preserving.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1031 — Extract rapid-monitor exit block to fit Debug 64KB cap (Feb 2026, CI ⏳)

V5.9.1030 Build APK ✅ but Runtime Smoke Test ❌ (Debug compile JVM 64KB cap
on `botLoop` STILL exceeded). V5.9.1029's `getCatastropheThreshold` extraction
was not enough — the 4-branch `when` block with 3 suspending
`executor.requestSell` call sites kept botLoop's coroutine state machine
over the cap on Debug builds (Release strips coroutine debug info more
aggressively).

Fix: extract the entire rapid-monitor exit ladder into a new private
suspend helper:

  `evaluateRapidMonitorExit(ts, pnlPct, cfg, wallet, effectiveBalance): Boolean`

The 3 `executor.requestSell` suspension points + the `isCatastrophe` /
`giveBackTrigger` / `hardFloor` decision logic now live in the helper.
Caller pattern collapses to:

  `if (evaluateRapidMonitorExit(...)) continue`

Behaviour identical to V5.9.1030 (settle-in → skip; catastrophe →
RAPID_CATASTROPHE_STOP; give-back → RAPID_DRAWDOWN_FROM_PEAK_STOP;
hard floor → RAPID_HARD_FLOOR_STOP). Removed the unreferenced
`neverWinner` local variable (legacy from pre-V5.9.687 — was only
computed, never gated against).

Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1030 → V5.9.1031.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1030 — Un-choke OkHttp dispatcher + fail-fast read timeouts (Feb 2026, CI ⏳)

Operator V5.9.1029 snapshot (build 5.0.2988, tag V5.9.1029 — bumped correctly
this time): the V5.9.1029 chunk-budget widening (2.5s → 4.5s) didn't help.
SUPERVISOR_CHUNK_TIMEOUT still fired every chunk with `active=32 budgetMs=4800`,
processed=0 deferred=96 EVERY cycle. The 32 workers were genuinely blocking
past 4.8s — but not for any compute reason.

**Q1 — Root cause: OkHttp Dispatcher.maxRequestsPerHost = 5 (default)**

`SharedHttpClient.base` used the OkHttpClient default Dispatcher. Its
defaults:
  • `maxRequests = 64`
  • `maxRequestsPerHost = 5`

The supervisor launches 32 parallel `processTokenCycle` workers; each
calls `dex.getBestPair(mint)` (DexScreener) and Birdeye lookups. With
maxRequestsPerHost=5, ONLY 5 of 32 workers' HTTP requests run truly
in parallel against api.dexscreener.com — the other 27 queue inside
OkHttp's dispatcher. Add a 4-7% timeout tail (Birdeye SR=93%, DS SR=96%)
where one stuck socket blocks for up to 15s (readTimeout) and every
worker that needs that host wedges behind it.

**Fix A — Install a shared Dispatcher with higher concurrency**

  `Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 32 }`

Installed in `SharedHttpClient.base`. Every existing call site that
uses `SharedHttpClient.builder()` inherits the bump automatically —
DexScreener, Birdeye, Jupiter, Helius, pump.fun WS, etc. The operator
is on paid DexScreener / Birdeye tiers, so concurrent-request quota
is no longer the binding constraint.

**Fix B — readTimeout: fail fast or never finish at all**

  • `DexscreenerApi`  readTimeout 15s → 4s  (connectTimeout 10s → 5s)
  • `BirdeyeApi`      readTimeout 12s → 4s  (connectTimeout 8s → 5s)

A single 15s-stuck socket is enough to wedge every supervisor worker
that touches that host. 4s gives ample room for healthy 300-400ms
responses while ensuring no worker can stay past the 4.5s chunk
budget. Cache layers (DexScreener 45s TTL + ApiBackoff) make dropped
fetches recoverable on the next cycle.

**Expected dump deltas**

  • SUPERVISOR_CHUNK_TIMEOUT: should drop from ~28/cycle to near-zero.
  • POST_SUPERVISOR processed:  0 → 90+ (the 96 mints actually get
    re-evaluated each cycle).
  • Watchlist of 545 tokens gets full coverage every 6 cycles instead
    of never. Fluid stops + telemetry get fresh price updates.

Touched: `network/SharedHttpClient.kt`, `network/DexscreenerApi.kt`,
`network/BirdeyeApi.kt`.

Bumped: `PipelineHealthCollector.BUILD_TAG` V5.9.1029 → V5.9.1030.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1029 — Supervisor un-choke + Debug compile fit + lane re-enable (Feb 2026, CI ⏳)

Operator V5.9.1028b snapshot (build 5.0.2987, tag V5.9.1018 stale): bot
"frozen" — zero trades for 16 minutes despite bot-loop ticking healthily
at 12s cycles. Three problems, one surgical push.

**Q1 (the freeze — supervisor never harvests work)**

Every cycle: `SUPERVISOR_CHUNK_TIMEOUT loop=N chunk=32 active=32 budgetMs=2500`
followed by `POST_SUPERVISOR processed=0 deferred=96 total=96`. The V5.9.1025
harvest fix correctly walks each Deferred after timeout, but `active=32`
means NO worker has completed when the budget expires — there's nothing
to harvest. With 580 tokens in the watchlist and supervisor delivering 0
processed per cycle, only the SCAN_CB direct intake path (~6 evals/cycle)
fed FDG, and recent pump.fun spam (PUMP/OPAI/Veil ~$2K liq) fails the
quality floor. Net: bot looks dead.

Root cause: `chunkBudgetMs = min(perTokenTimeoutMs * 2L = 2400, remaining)
                              .coerceAtLeast(2_500L)`
With paper-mode `perTokenTimeoutMs = 1200`, the floor pins the budget at
2.5s. That's not enough for 32 parallel workers each running a real
`processTokenCycle` (V3 + safety + lane evals + a 300-700ms network call).

Fix: widen to `(perTokenTimeoutMs * 4L).coerceAtLeast(4_500L)` = 4.5s
floor (was 2.5s). 3 chunks × 4.5s = 13.5s fits inside `maxBatchMillis=15s`
paper deadline with margin.

Touched: BotService.kt L10218-L10221 (runSupervisorPhase chunkBudgetMs).

**Q2 (Debug compile JVM 64KB cap)**

V5.9.1028b Build APK ✅ but Runtime Smoke Test ❌:
  `e: Back-end (JVM) Internal error: Couldn't transform method node: botLoop`

The Debug compile (assembleDebug used by the smoke test) keeps Kotlin
coroutine state-machine debug info that the Release build strips, and
V5.9.1028's inline `rawCatastrophe + try/catch` for the AI-fluid
catastrophe threshold pushed botLoop's bytecode over the cap again.

Fix: extract to `private fun getCatastropheThreshold(paperMode): Double`
helper. Call site collapses from a 5-line try/catch to a single
INVOKESPECIAL (~10 bytes). Same behaviour as V5.9.1028.

Touched: BotService.kt L6144-L6156 (botLoop call site) + L7818 (new helper).

**Q3 (lane re-enable — option c)**

Operator confirmed "and c" — clear stale auto-disabled strategies. The
V5.9.806 telemetry auto-retires strategies with ≥50 trades AND mean PnL
≤ -5%; ANY retirements made BEFORE V5.9.1028's fluid-stop fix were based
on phantom losses from paper-mode slippage. Clean slate per start lets
SHITCOIN / TREASURY / PRESALE_SNIPE re-prove themselves on honest data.

Fix: call `StrategyTelemetry.clearDisabled()` at the top of every
`startBot()`. Idempotent — safe to call when nothing is disabled.
Emits `STRATEGY_TELEMETRY_DISABLED_CLEARED reason=fresh_start` forensic.

Touched: BotService.kt startBot() L2604.

Plus: bumped `PipelineHealthCollector.BUILD_TAG` V5.9.1018 → V5.9.1029
so future snapshots show the actual installed build.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1028 — Paper settle-in restored + AI-fluid STRICT_SL & catastrophe thresholds (Feb 2026, CI ⏳)

V5.9.1027b operator snapshot: every paper trade exits within 350-1000ms
of entry at STRICT_SL_-10 / RAPID_CATASTROPHE_STOP / CASHGEN_STOP_LOSS.
No token drops -10% in 700ms — this was a paper-mode simulation
artifact, not real behaviour.

Root cause: paperBuy (Executor.kt L6540) applies +12% slippage on entry
and paperSell (L9111) applies -18% slippage on exit, for tokens with
<$5k liquidity (every pump.fun launch). Round-trip tax is -26.8%
before ANY price movement → every fresh paper position is born at
-26.8% PnL and BOTH the strict SL (-10%) AND the catastrophe gate
(-25%) fire instantly. MOONSHOT / SHITCOIN / TREASURY get gutted in
under a second, learning data is corrupted with phantom losses, and
lanes get distrust-paused for "bleeding" they never actually did.

Operator mandate: "settle in period plus all the trader lanes all 9
and the tools are meant to have ai calculated hold times stops take
wins etc in a fluid state. the strict and rapid stops are still meant
to be a fluid learnt thing as well. everything is meant to be."

Three surgical fixes:

1. **Paper-mode settle-in for STRICT_SL** (Executor.kt ~L4040).
   In paper mode, suppress STRICT_SL for a per-lane settle-in window
   sourced from `FluidLearningAI.getFluidMinHoldMinutes(lane)`, with an
   absolute 30s floor so the slippage band has time to mean-revert.
   Live mode untouched — real slippage IS real cost.

2. **Paper-mode settle-in for RAPID_CATASTROPHE_STOP** (BotService.kt
   ~L6135). Same per-lane gate added as a new `when` arm BEFORE the
   `isCatastrophe` branch. Other stops (give-back, dynamic floor,
   trailing) continue evaluating during settle-in so genuine rugs
   still get caught.

3. **AI-fluid stop thresholds**. STRICT_SL's `hardFloor` and the
   catastrophe gate's threshold now both flow through
   `FluidLearningAI.getFluidStopLoss(modeStop)` — they lerp from a
   -15% bootstrap floor (capital protection while learning) to the
   trader's mature mode stop. Falls open to the original hardcoded
   value on any failure so the safety net is never lost.

Touched: `Executor.kt` STRICT_SL block (~L4040-4108), `BotService.kt`
catastrophe `when` block (~L6135-6200).

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
