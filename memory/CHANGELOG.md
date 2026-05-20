# CHANGELOG — AATE Bot

Progressive change log. Newer entries on top. PRD.md holds the static problem
statement + architecture; this file is the working log of fixes & decisions.

═══════════════════════════════════════════════════════════════════════════════

## V5.9.1019 — Structural: async universal SL sweep + deferred MainActivity UI (Feb 2026, CI ✅ build, smoke pending)

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
