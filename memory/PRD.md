# AATE PRD — V5.0.6507a (POSITION/EXIT TRUTH + FILL-LOT AUTHORITY GREEN)

**Status:** PAPER TRADING ONLY. Live routing intentionally disabled
until the 26-point correctness mandate + 10-point POSITION/EXIT
TRUTH REPAIR mandate + 15-point CANONICAL ENTRY + ECONOMIC TRUTH
mandate + 12-point CANONICAL EXECUTION + RUNTIME REPAIR mandate
are 100 % green across consecutive operator dumps.

**Operator mantra:** "$50 → $1M thru Autonomous Intelligent Trading."
Data integrity enforced at the SOURCE (FillLotLedger6504 immutable
SQLite lots), never by strangling flow. Correctness surfaces are
write-time invariants + startBot purge/rebuild, not enforcement
gates.

**Compile / test / ship contract:** NO LOCAL COMPILER. Every change
lands via `git push` → GitHub Actions CI. Verification is the pair
(`Build AATE APK` green, `Runtime Smoke Test` green) on the head
SHA.


## V5.0.6507a (Feb 2026) — EXIT FINALITY HEAL + QTY INVARIANT + ADVISOR COOLDOWN ✅ CI GREEN

`71e7acd3a` Build ✅ + Runtime Smoke Test ✅.

Methodical 4-item subset from the 12-point CANONICAL EXECUTION +
RUNTIME REPAIR mandate:

- **§P0 EXIT FINALITY** — `Executor.paperSell` QTY_DIVERGES_FROM_CANONICAL
  block now heals `pos.qtyToken` from `FillLotLedger6504.canonicalQtyOf`
  BEFORE rejecting. Emits `EXIT_FINALITY_HEAL_FROM_LOTS_6507`.
- **§P0 QUANTITY AUTHORITY** — `Executor.paperBuy.atomic6485`
  enforces `abs(reconstructedNotionalSol − costSol) <= tolerance`
  before persistence. Rolls back malformed lots via
  `rollbackPaperEntry6485`.
- **§P1 ADVISOR INTERLOCK** — `AutoPipelineAdvisor6462` R2 rule
  memoises `lastSeenReplayDivergence6507`; historical divergences
  no longer indefinitely extend `entryCooldownSec`.
- **§P2 UI OFF-MAIN** — Audit confirmed `getLifetimeStats` is O(1)
  @Volatile in-memory; `HeatmapRenderCache6374` already on
  `Dispatchers.Default`. No code change needed.


## STAGED (next sessions)

**V5.0.6507b/c (from CANONICAL EXECUTION + RUNTIME REPAIR mandate)**
- P0 QUANTITY AUTHORITY full: single `CanonicalFillBuilder` wrapping
  every entry path (NORMAL/CYCLIC/TOP_UP/PROBE/ADD_MORE)
- P0 SAME-MINT / ENTRY AUTHORITY: immutable ExecutionTicket
  post-FDG + explicit expiry/invalidation
- P0 EXIT FINALITY atomic 6-store terminal mutation
- P1 ECONOMIC DATA: strategy/reward statistics rebuild from
  corrected canonical economic events; quarantined rows excluded
  from WR/EV/tactic/advisor learning
- P1 RUNTIME: hard cycle budget + single-flight scanner coalescing
  + POST_LEARNING_MAINTENANCE async
- P2 UI: full MainActivity report/heatmap off-Main

**V5.0.6506b (from CANONICAL ENTRY + ECONOMIC TRUTH mandate)**
- P0-2 full atomic election snapshot stamping
- P0-4 UNVERIFIED valuation partition
  (`authoritativeOpenValue` / `unverifiedOpenValue` /
  `fallbackMarkCount` / `missingCostBasisCount`)
- P1 provider fail-open final audit



# AATE PRD — V5.0.6504 (POSITION/EXIT TRUTH REPAIR — P0 CORE GREEN)

**Status:** PAPER TRADING ONLY. Live execution intentionally disabled
until the 26-point correctness mandate + the 10-point POSITION/EXIT
TRUTH REPAIR mandate are 100 % green across consecutive operator
dumps.

**Operator mantra:** "$50 → $1M thru Autonomous Intelligent Trading".
Every canonical authority protects capital first, preserves every
side-effect door at its originating layer, and keeps learning purity
so runner compounding is monotonic.

**Compile / test / ship contract:** NO LOCAL COMPILER. Every change
lands via `git push` → GitHub Actions CI. Verification is the pair
(`Build AATE APK` green, `Runtime Smoke Test` green) on the head SHA.


## V5.0.6504a (Feb 2026) — POSITION/EXIT TRUTH REPAIR — P0 CORE ✅ CI GREEN

Twenty-sixth beat. CI green on commit `2e7764671`:
Build AATE APK ✅ + Runtime Smoke Test ✅.

Bundled the P0 items from the 10-point POSITION/EXIT TRUTH REPAIR
mandate plus §6/§7 P1 entry-bridge scaffolding.

- **§1 FillLotLedger6504** — SQLite WAL immutable `(mint, lotId,
  side, qty_token_raw, lamports, finalized)` ledger. INSERT-ONLY
  except finalized-flag flip. Wired at `paperBuy.atomic6485` (BUY)
  and full `paperSell` after `CanonicalPaperTerminalBridge6469`
  (SELL). `canonicalQtyOf(mint) = Σ finalized BUY − Σ finalized
  SELL` is the immutable qty truth.
- **§10 PURGE + REBUILD** — `FillLotLedger6504.rebuildRealizedSol`
  FIFO lamport-matches per mint, produces pure realized SOL. On
  |delta| > 0.001 SOL vs ledger, atomically overwrites via
  `PaperAccountLedger6430.overrideRealizedFromFillLots6504`.
  Emits `FILL_LOT_REALIZED_DIVERGES_FROM_LEDGER_6504` /
  `PAPER_LEDGER_OVERRIDE_FROM_FILL_LOTS_6504`.
- **§5 ONE-SHOT ZOMBIE LATCH** — `BotService.paperStaleZombieLatch6504`
  keyed by `mint:entryTime`. PAPER_STALE_ZOMBIE_SCRATCH_EXIT emit +
  `executor.requestSell` fires EXACTLY ONCE per eligible position.
- **§10 EconomicPurityGate6504** — read surface unioning local
  untrusted-set with `QuantityInvariantAuthority6500` +
  `LearningQuarantineGate6470`.
- **§11 UniversalSlSentinel6504** — `noteStart / noteDone /
  noteReset / sweep(onTimeout)` with 10 s TTL.
- **§6 FDG_BUY_TO_AUTH counter** at `Executor.doBuy` spine.
- **§7 NON-BUY GUARD** — `Executor.doBuy` short-circuits BEFORE
  sizing when `ts.signal` ∉ {BUY, PROBE, PROBE_ONLY, EXECUTE, ""}.


## V5.0.6503a (Feb 2026) — LEDGER REBUILD WIRE + HERO OFF-MAIN + TAXONOMY + BIRDEYE 401 STICKY ✅ CI GREEN

`e253c15dd` Build ✅ + Runtime Smoke Test ✅.

- **§1** Wired `PaperAccountLedger6430.rebuildRealizedFromCanonicalEvents6502()`
  in `BotService.startBot` (finishes V5.0.6502).
- **§2** New `HeroSnapshotAuthority6503` — 500 ms Dispatchers.Default
  loop publishing `(openCount, exposure, unrealized, equity, cash,
  realized)` via AtomicReference. `MainActivity
  .precomputeMainRenderModelAsync` reuses the cached values to kill
  the 3017 ms Main-thread frame gap. Paper + live parity.
- **§3** `ExecutableOpenGate` `EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY`
  now reports `SIGNAL_NOT_BUY:<signal>` — no more
  `EXEC_GATE/UNKNOWN` rows.
- **§4** `BirdeyeApi.getRaw` emits one loud
  `BIRDEYE_KEY_DEAD_401_STICKY_6503` line + counter.


## FOLLOW-UPS (staged for V5.0.6504b)

- Sibling funnel counters at their convergence sites:
  `AUTH_TO_SIZE_6504` at `OrderSizeResolver6441.resolve`,
  `SIZE_TO_ROUTE_6504` at `RouteResolver6411.resolve`,
  `ROUTE_TO_TICKET_6504` at `ExecutionTicketMachine6411.create`,
  `TICKET_TO_OPEN_6504` at the executor open finalization.
- Auto-repair loop for QTY_DIVERGES_FROM_CANONICAL: on divergence
  call `FillLotLedger6504.canonicalQtyOf(mint)`, retry once with
  the corrected qty, quarantine only if lots themselves disagree.
- `UniversalSlSentinel6504` producer wiring at the actual
  `sl.start / sl.done` sites (currently the sentinel is available
  but unwired).
- `EconomicPurityGate6504` consumer wiring at RewardPurityGate6441,
  StrategyTelemetry, MathEdge, Governor, HypothesisEngine ingress.
- Close-atomicity clears (slot occupancy, forced-open, exitPending)
  bundled into `PositionCloseLedger.markClosedFull`.
- Paper routing repair: ROUTE_FAILED_PAPER=887 / PAPER_BUY_NOT_OPENED=888
  needs concrete reason attribution + drop the live-proof
  requirement in the paper path.



# AATE PRD — V5.0.6503 (V5.7+ FINISH DEFERRED ITEMS)

**Status:** PAPER TRADING ONLY. Live execution intentionally disabled.

**Product goal (verbatim operator mantra):** "$50 to a million thru
Autonomous intelligent trading". Every canonical authority is tuned
to (a) protect capital, (b) preserve every side-effect door at its
originating layer, and (c) keep learning purity so runner
compounding is monotonic. The 26-point correctness mandate stays
green before any live routing enables.

**Compile / test / ship contract:** NO LOCAL COMPILER. Every change
lands via `git push` → GitHub Actions CI. Verification is the pair
(`Build AATE APK` green, `Runtime Smoke Test` green) on the head
SHA.


## V5.0.6503 (Feb 2026) — LEDGER REBUILD WIRE + HERO OFF-MAIN + TAXONOMY + BIRDEYE 401 STICKY

Twenty-fifth beat. Bundled ship of every item the previous fork
deferred/skipped:

- **§1** — Wired `PaperAccountLedger6430.rebuildRealizedFromCanonicalEvents6502()`
  into `BotService.startBot()` right after the 6500 invariant sweep.
  This finishes V5.0.6502 (phantom-realized reject third leg).
- **§2** — New `HeroSnapshotAuthority6503`. Background 500 ms
  Dispatchers.Default loop publishes immutable
  `(openCount, exposure, unrealized, equity, cash, realized)` for
  hero-tile readers. Started idempotently from `startBot`. Consumed
  in `MainActivity.precomputeMainRenderModelAsync` to eliminate the
  duplicate Σ passes that produced the 3017 ms Main-thread frame
  gaps. Applies to paper AND live modes.
- **§3** — `ExecutableOpenGate` `EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY`
  blocks now report `SIGNAL_NOT_BUY:<signal>` so the pipeline dump
  no longer contains 143 `EXEC_GATE/UNKNOWN` rows.
- **§4** — `BirdeyeApi.getRaw` 401 path emits one loud
  `BIRDEYE_KEY_DEAD_401_STICKY_6503` lifecycle line + counter so the
  operator sees the dead-key state clearly. All existing
  KeyValidator + ProviderCircuitBreaker6402 sticky-DEAD gating
  untouched — this is a visibility upgrade only.



# AATE PRD — V5.0.6497 (V5.7+ Entry Finality Repair)

**Status:** PAPER TRADING ONLY. Live execution intentionally disabled
until every mandate point remains green across consecutive operator
dumps. Do NOT tune profitability while correctness surfaces are
still stabilising.

**Product goal:** $50 → $1M mindset. Every canonical authority is
tuned to (a) protect capital, (b) preserve every side-effect door at
its originating layer (SOURCE-LEVEL AUTHORITY CONVERGENCE), and
(c) keep learning purity so runner compounding is monotonic.

**Compile / test / ship contract:** NO LOCAL COMPILER. Every change
lands via `git push` → GitHub Actions CI. Verification is the pair
(`Build AATE APK` green, `Runtime Smoke Test` green) on the head SHA.


## V5.0.6497 (Feb 2026) — ENTRY FINALITY REPAIR

Twenty-fourth beat. CI green on commit `951fb7a2a` (Build AATE APK
+ Runtime Smoke Test both green).

Operator's 6496 dump: 168 BUY verdicts, 270 FDG allows, **0
executor invocations, 0 paper buys**. Entry handoff broken. 6496 §4
over-constrained snapshot rejected 39 legit candidates. MER stuck
at −99.9 % generating 554 zombie retries. Sizing authority
contradiction: canonical `resolve()` returned `final=2.00 SOL` but
EXEC_GATE saw `resolvedSize=0.01`.

Six source-level fixes:

- **§1 SealedOrderSizeAuthority6497** — `TraderSizingBridge6444`
  seals `(mint, finalSizeSol)` on executable resolution;
  `ExecutableOpenGate` consults `authoritativeSize(mint, local)`;
  emits `EXEC_SIZE_AUTHORITY_MISMATCH_6497`.
- **§2 PaperEntryFinalityAuthority6497** — per-attempt latch on
  paperBuy; every LANE_BUY_INTENT event resolves to `PAPER_BUY_OK`
  or `PAPER_ENTRY_FINALITY_REJECT_<reason>`; sweep flushes stale
  as `PAPER_ENTRY_FINALITY_MISSING_TERMINAL_6497`.
- **§3 Relaxed ExecutionSnapshotAuthority6496** — new tuple
  `(primaryLane, safetyAuthorityTier, canonicalOccupancy,
  resolvedOrderSizeSol)`; volatile fields refresh rather than
  reject; safety only drifts on RUG/NO_BUY/UNKNOWN; order size
  only drifts on >20 % material shrink.
- **§4 PaperCatastrophicCloseIdempotency6497** — one-shot latch
  per mint gates the zombie retry storm in paper mode. First
  claim → one `requestSell`; subsequent claims → short-circuit
  `continue`. Throw → `quarantineOnce` via
  `HistoricalEconomicQuarantine6496`.
- **§5 RootCauseClassifier6471 ENTRY_FINALITY tier** — new tier
  between EXECUTION_FINALITY and RUNTIME_STALL; covers the four
  6497 labels.
- **§6 ROUTE_FAILED sub-reasons** — `ROUTE_FAILED_PAPER` /
  `ROUTE_FAILED_LIVE` sub-events; flat aggregate preserved for
  dashboards.


## V5.0.6496 (Feb 2026) — INTEGRITY CLEANUP

Twenty-third beat. CI green on commit `279cacca6`. Five source-level
authorities + provider fixes: fallback-mark contamination gated,
historical fault mints quarantined from learners, root-cause
banner uses active deltas, execution snapshot immutability, UI
snapshot off Main. Groq model migrated from
`llama-3.3-70b-versatile` (unavailable) to `llama-3.1-70b-versatile`.



# AATE PRD — V5.0.6495 (V5.7+ Growth-Centric Runner Compounding)

**Status:** PAPER TRADING ONLY. Live execution intentionally disabled
until the 26-point correctness mandate reaches 100 % green across
consecutive operator dumps. Do NOT tune profitability while
correctness surfaces are still stabilising.

**Product goal:** $50 → $1M mindset. Every canonical authority is
tuned to (a) protect capital, (b) preserve every side-effect door at
its originating layer (SOURCE-LEVEL AUTHORITY CONVERGENCE — no
passive read-only mirrors), and (c) keep learning purity so runner
compounding is monotonic.

**Compile / test / ship contract:** NO LOCAL COMPILER. Every change
lands via `git push` → GitHub Actions CI. Verification is the pair
(`Build AATE APK` green, `Runtime Smoke Test` green) on the head SHA.


## V5.0.6495 (Feb 2026) — SOURCE-FIX: paper→LIVE mistag + root-cause priority + laneCap sentinel

Twenty-second beat. CI green on commit `abe50b9d5`
(Build AATE APK + Runtime Smoke Test both green).

Four concrete source-fixes prompted by the 6494 pipeline snapshot
showing 0 paper mints / 10 LIVE mints in a paper session, root
cause stuck on provider-degradation, and resolver reporting
`laneCap=1.79e+308`:

- **Paper→LIVE mistag killed at source** —
  `ExecutorCanonicalMirror6442.mirrorBuyAttempt/mirrorBuyFill` was
  hardcoding `paperMode=false` on the canonical open call. Now the
  caller's `paperMode` is forwarded, so
  `activeMintProjections6490('paper')` routes correctly.
- **Root cause priority classifier now wins display precedence** —
  `PipelineHealthCollector` render pass consults
  `RootCauseClassifier6471.classify()`; verdicts at tier
  `ECONOMIC_INTEGRITY` / `EXECUTION_FINALITY` prepend to the display
  list. Provider- / advisory-tier verdicts never mask capital
  breaches again.
- **laneCap sentinel replaced** — `OrderSizeResolver6441.resolve()`
  default of `Double.MAX_VALUE` replaced with named constant
  `DEFAULT_LANE_RISK_CAP_SOL = 5.0` (real callers still pass their
  own cap). Diagnostic no longer renders "1.79e+308 SOL / $926M".
- **USD/SOL mark scaling fix** — `BotService.kt` mark-provider
  callback now divides USD price by `solPrice` before publishing to
  `CanonicalCapitalAuthority6450.installMarkProvider`. Equity
  snapshots stay SOL-denominated.

Companion earlier commit `919fb489a` shipped impossible-outcome
quarantine + provider circuit enforcement across every read site.

**Deferred to 6496+** — non-linear `rem` values on the same `id` in
partial-sell rows (`Executor.kt` / `PaperAccountLedger` audit
pending); MOONSHOT exit-quality tuning (still gated on ≥20 fresh
clean closes); BotService runtime routing through
`BackgroundTradingAuthority6469`.


## V5.0.6494 / V5.0.6494a (Feb 2026) — PRESERVE IMMUTABLE LANE ELECTION

CI green (`43dfb89a7` + test-alignment `c8c12bec9`).

- `canRequestExecution` now picks primary from
  `qualifiedLanesFor(mint, laneUpper)` at the first claim and seals
  it. Under `MintAffinity → SHITCOIN`, MOONSHOT's first call
  correctly resolves to SHITCOIN.
- `LaneExecutionCoordinatorSmokeTest ›
  affinity_lane_can_upgrade_non_affinity_higher_base_lane` updated
  to assert sealed-election immutability under repeated MOONSHOT
  attempts.


## V5.0.6493 (Feb 2026) — MINT-AUTHORITATIVE TOKEN IDENTITY + MARKET DATA

Crypto token identity and market data keyed on canonical mint rather
than downstream aliases. Terminates the token-map alias-merge class
of bugs at ingest.


## V5.0.6492 (Feb 2026) — UNIFY INVENTORY / VALUATION / FINALITY / MARKET-CAP TRUTH

Four adjacent read surfaces (inventory qty, valuation SOL, terminal
finality, market-cap classification) collapsed onto a single
canonical projection.


## V5.0.6491 / V5.0.6491a (Feb 2026) — SIZE-BEFORE-AUTH + LANE FANOUT COLLAPSE

Resolve size before authorization; collapse lane fanout so a mint
cannot enter more than one lane's admission path in parallel.
Authority verdict now carries the resolved size.


## V5.0.6490 / V5.0.6490a (Feb 2026) — UNIFIED EXECUTABLE SIZING + CANONICAL INVENTORY

Single executable sizing surface; canonical inventory is the sole
authority for "how much do I own?". Paper minimum acceptance reason
string aligned with the new size resolver output.


## V5.0.6489 (Feb 2026) — RESTORE THROUGHPUT + CANONICAL POSITION TRUTH

Paper throughput recovered by removing residual defensive probes
funded through global streak shutdowns. Canonical position truth
is the sole basis for open-mint accounting.


## V5.0.6488 (Feb 2026) — RUNTIME THROUGHPUT + LANE-LOCAL AUTHORITY

- Offload post-learning maintenance.
- Remove global streak shutdowns (lane-local pauses remain).
- Atomically project canonical positions.
- Fix parity math and escalating-stall diagnosis.
- Preserve paper capital authority.
- Close release lint.


## V5.0.6487 (Feb 2026) — EXECUTABLE THROUGHPUT + CANONICAL RUNTIME TRUTH

Entry/streak authority moved before FDG ticket creation; one
executable route per mint; funded defensive probes removed;
`PaperAccountLedger` becomes the sole paper capital authority;
atomic debit/idempotency semantics repaired; bridge quantity overflow
rejects instead of fabricating execution proof.


## V5.0.6486 (Feb 2026) — TYPED FINALITY + CANONICAL ACCOUNTING CLOSURE

Paper/live transaction truth closed across Markets, Perps, Crypto
Universe, meme partials, orphan refunds, durable learning finality,
pending policy persistence, bridge output proof, and money-path
reporting. Golden Tape contracts added for growth-safe execution
truth.


## V5.0.6485 (Feb 2026) — ATOMIC PAPER TRANSACTIONS + CANONICAL FINALITY

- One shared size minimum; fail-closed sizing.
- Paper BUY state committed atomically across cash / canonical
  lifecycle / lot / occupancy / projections; rollback on failure.
- Unfunded startup rows purged without manufacturing OPEN state.
- Terminal publication unified through the rich 6450 bus.
- All 8 finalized consumers wired with truthful ACKs.
- Reconciler cadence aligned; stale AutoMode PAUSED hard choke
  removed.
- 1624 release tests green.


## V5.0.6484 (Feb 2026) — REMOVE DORMANT POST-FDG LANE HARD ABORT

Dead-code path removed. Was not consulted anywhere but blocked
future migrations.


## V5.0.6483 (Feb 2026) — STOP LEARNED LANE PAUSES DISABLING TRADERS

Learned lane pauses no longer propagate to trader-level disable.
Lane damping continues via `LaneAdaptiveDamping6472`.


## V5.0.6482 (Feb 2026) — REMOVE DUPLICATE LEARNED HARD VETOES

Two independent hard-veto paths collapsed to one to prevent double
disable of the same lane.


## V5.0.6481 (Feb 2026) — PIVOT TOXIC LANES INSTEAD OF CHOKING VOLUME

Toxic-lane detection now pivots capital to healthier lanes rather
than reducing total volume — preserves the V5.7 growth mandate.


## V5.0.6480 (Feb 2026) — ATOMIC WATCHLIST CAP AUTHORITY

Single atomic authority replaces the three separate cap checks that
were fighting each other.


## V5.0.6479 (Feb 2026) — REMOVE PROVIDER WAITS FROM SIZING + EXITS

Sizing and exit hot paths no longer block on provider I/O.


## V5.0.6478 (Feb 2026) — REMOVE PROVIDER IO FROM FDG HOT PATH

FDG reads only from canonical caches / substrate. Provider refresh
runs on background cadence.


## V5.0.6477 (Feb 2026) — ISOLATE PROOF-CONFIRMED LIVE TOKEN LEARNING

Only tokens with chain-verified fill + settlement feed live-token
learners. Unconfirmed candidates quarantined.


## V5.0.6476 (Feb 2026) — REPAIR CAPITAL REPLAY FINALITY MUX + OCCUPANCY TRUTH

Capital replay finality multiplexer now correctly attributes replayed
events to the right terminal channel; occupancy truth updates on the
same tick.


## V5.0.6475 (Feb 2026) — CANONICAL PAPER CAPITAL + REGISTRY REPAIR

Paper capital and registry read surfaces converged onto the canonical
authority; legacy divergences repaired at their originating write
sites.


## V5.0.6474 (Feb 2026) — ROUTE FULL PAPER SELL THROUGH CANONICAL CLOSE REDUCER

Every full paper sell flows through the same close reducer as the
canonical terminal pipeline. Last "paper-only" bypass of the
V5.0.6469 canonical terminal fanout removed.



# AATE PRD — V5.0.6405 §1-§16 Crash-Safe Portfolio Substrate + Full Executor Wire-Up


## V5.0.6473 (Feb 2026) — WIRE THE DEFERRED 6472 ITEMS

Eighth beat — call-site migrations for the 6472 truth surfaces. CI
green (sha `845ddcb1`).

- **LaneAdmissionGate6473** wired into `Executor.paperBuy.pre_mutation`.
  SHITCOIN at -57 % EV now takes 25 %-size probes at 180 s cadence
  instead of being hard-disabled. When damping shrinks below the
  paper minimum, V5.0.6471 clamp handles the clean SKIP.
- **CanonicalInstanceIdentity6472** stamps appended to
  `RootCauseClassifier6471`, `UnifiedReconcilerHealth6470`,
  `TelemetryIntegrityHold6472`, `LaneAdmissionGate6473`,
  `WatchlistHardCapInvariant6473` status lines. Operator can now
  match `instanceId=<prefix>` across every panel.
- **WatchlistHardCapInvariant6473** wired into the 30-loop parity
  audit. Non-mutating; emits `WATCHLIST_HARDCAP_OVERRUN_6473` with
  overrun magnitude when the observed size exceeds the configured cap.

**Deferred to 6474** — EconomicOutcome consumer migration; off-main
PipelineHealthActivity snapshot; token map single-flight
consolidation; BotService runtime routing through
BackgroundTradingAuthority6469; watchlist size emitter wire.



## V5.0.6472 (Feb 2026) — CANONICAL TRUTH SURFACES + RUNTIME DEAMPLIFICATION

Seventh beat — canonical instance identity, typed economic units,
adaptive lane damping, telemetry integrity hold. CI green (Build APK
+ Runtime Smoke Test, sha `13de8b53`).

- **CanonicalInstanceIdentity6472** — process-scoped
  `(instanceId, runId, epoch)`. Every panel stamps its diagnostics
  with the triple so an operator can prove report panels are looking
  at the same authority instance.
- **EconomicOutcome6472** — immutable typed value:
  `proceedsSol / costBasisSol / feesSol / realizedPnlSol /
   unrealizedPnlSol / returnFraction / returnPct`.
  `ofSell(...)` factory derives realized + returnFraction from
  proceeds/cost/fees so consumers cannot introduce fraction-into-SOL
  bugs. Consumers migrate incrementally in 6473.
- **LaneAdaptiveDamping6472** — replaces `LANE_AUTO_PAUSED_SHITCOIN`
  hard block with smooth EV-based damping (level 0…4). Even the
  deepest level keeps a 10 % probe path open. Losing tactics never
  equal permanent lane death.
- **TelemetryIntegrityHold6472** — cross-checks sibling counters
  (reconciler split-brain / bus-vs-terminal / identity-vs-conservation
  / parity-vs-projection). Emits `TELEMETRY_INTEGRITY_HOLD_6472` on
  disagreement. Wired into the 30-loop parity audit.

**Deferred to 6473** — Wire `CanonicalInstanceIdentity6472.stamp()`
into every diagnostic panel; migrate `EconomicOutcome` consumers;
wire `LaneAdaptiveDamping6472` into the FDG/admission path; watchlist
hard cap invariant; off-main `PipelineHealthActivity` snapshot; token
map single-flight consolidation; BotService runtime routing through
`BackgroundTradingAuthority6469`.



## V5.0.6471 (Feb 2026) — SOURCE-FIX: SIZE INFLATION KILL + MARKET DATA PROVENANCE + PARITY DOMAIN + ROOT CAUSE PRIORITY

Sixth beat — economic truth + entry authority repair. CI green
(Build APK + Runtime Smoke Test, sha `f346a044`).

- **Size re-inflation KILLED at the source** — `Executor.kt.clampPaperTradeSol`
  no longer uses `coerceIn(min, max)`. Safety clamp reduces only.
  Below `minSol` → skip. Above `maxSol` → clamp down. The 6470
  "cash-cap → forced 0.05 SOL" upsizing is structurally impossible.
- **Market data provenance** — `MarketDataProvenance6471` classifies
  the tuple `(price, mcap, liquidity, source, poolAddress)` and
  exposes `isExecutable(...)` as the single truth surface. Template
  tuple `0.05025/50m/5m`, MINT_ROUTE sentinel pool, UNKNOWN source →
  NON_AUTHORITATIVE. Non-authoritative data cannot authorize a trade.
- **Position parity domain fix** — `PositionParityDomainAudit6471`
  compares same-domain populations only. Active (OPEN + PARTIALLY_CLOSED)
  ↔ occupancy OPEN. CLOSED / PENDING / QUARANTINED never trigger
  OPEN divergence again.
- **Root cause priority classifier** — `RootCauseClassifier6471`
  walks probes in mandated priority order. Economic integrity
  outranks provider degradation. Capital breaches will never again
  be masked by 401/429/404.

**Deferred to 6472** — Wire MarketDataProvenance into every mint-entry
snapshot site; SHITCOIN adaptive damping; slot health canonical
rebuild; reconciler generation-ownership guard; BotService runtime
routing through BackgroundTradingAuthority6469.



## V5.0.6470 (Feb 2026) — SOURCE-FIX: LIFECYCLE CONVERGENCE + ACCOUNTING TRUTH + LEARNING QUARANTINE

Fifth beat — "canonical lifecycle convergence / accounting source-fix"
ship. CI green (Build APK + Runtime Smoke Test, sha 36de3816).

- **One position authority** — `CanonicalLifecycleAuthority6470` audits
  canonical CLOSED vs occupancy OPEN contradiction and quarantines
  offending mints via `LearningQuarantineGate6470`.
- **Lot quantity invariant AT THE SOURCE** —
  `CanonicalLotQuantity6464.onSellFilled` now quarantines mutations
  where the lot is missing, `bought <= 0`, or the sell would oversell.
  Root cause: 6469 SELL wires passed `ts.mint` directly instead of
  `ExecutorCanonicalMirror6442.positionIdOf(mint)`; that mismatch
  created phantom lots with `bought=0`. Fixed at both the guard and
  every paper sell call site.
- **Canonical economic identity** — `CanonicalEconomicIdentity6470`
  enforces the ONE equation
  `startCap + realized - fees == cash + openCost`. NON-CLAMPING;
  emits `CAPITAL_IDENTITY_BREACH_6470` when broken. Complements the
  6469 tracer with the correct fee-inclusive invariant.
- **Unified reconciler health** — `UnifiedReconcilerHealth6470`
  aggregates §6441 / §6454 / §6459 / §6467 into a single snapshot;
  emits `RECONCILER_SPLIT_BRAIN_6470` when siblings contradict the
  ground-truth heartbeat.
- **Learning quarantine gate** — `LearningQuarantineGate6470` is
  consulted by `FinalizedBusConsumerBridge6465.deliver()` before
  every learner dispatch. Corrupted lots never train learners;
  Dashboard is not gated (not a learning target).

**Deferred to 6471** — Reconciler service rip-and-replace; BotService
runtime routing through BackgroundTradingAuthority6469; Groq
permanent-disable-on-invalid-model; supervisor hot-path partition;
MOONSHOT exit-quality tuning (requires ≥20 fresh clean closes first).



## V5.0.6469 (Feb 2026) — SOURCE-FIX: BACKGROUND RUNTIME + CANONICAL TERMINAL PIPELINE + CAPITAL CONSERVATION TRACER

Fourth beat — "source-fix mandate" ship. CI green (Build APK +
Runtime Smoke Test, sha 0112f312). Landed in response to the 6468
operator dump showing 0 canonical SELLs, finalizedBus=0,
capital conservation delta=-1.53, and screen-off UI inactivations
correlating with heartbeat rescue relaunches.

- **Canonical paper terminal pipeline (root cause)** — every paper
  SELL/PARTIAL in Executor.kt bypassed the canonical event graph.
  `CanonicalPaperTerminalBridge6469` now wires every paper sell site
  to the full 7-step canonical fanout (LotQuantity → Idempotency →
  MutationAuthority → EconomicEvent → FinalizedBus + Consumers →
  Occupancy → SnapshotVersion). 3 executor call sites migrated.
- **Background trading authority** — `BackgroundTradingAuthority6469`
  rejects UI-lifecycle mutations. Every runtime-active mutation and
  job registration now names its caller; UI callers get
  `UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED`.
- **Capital conservation tracer** — `CapitalConservationTracer6469`
  reconciles `baseline+realized ⇔ cash+openCost` on every parity
  audit. NON-CLAMPING — emits `CAPITAL_CONSERVATION_DELTA` with the
  full mutation history when the identity breaks.
- **Maintenance budget governor** — `MaintenanceBudgetGovernor6469`
  provides `tryAcquire(workKey) / release(workKey) / withBudget`
  for heavy maintenance work keys. Shipped as surface; specific
  offenders (LabUniverseTick, HOT_WATCHLIST) migrated in 6470.
- **Forensic counters shipped** (mandated acceptance list):
  `BACKGROUND_RUNTIME_SCREEN_OFF_TICKS`,
  `BACKGROUND_RUNTIME_UI_ABSENT_TICKS`,
  `UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED`,
  `RUNTIME_JOB_REPLACEMENTS`,
  `CANONICAL_TERMINAL_SELL`,
  `CANONICAL_TERMINAL_PARTIAL`,
  `FINALIZED_BUS_PUBLISHED`,
  `REGISTRY_CANONICAL_PARITY`,
  `CAPITAL_CONSERVATION_DELTA`.

**Deferred to 6470** — BotService START/STOP/rescue routing through
BackgroundTradingAuthority6469; LabUniverseTick / hot-watchlist migration
onto the governor; off-main PipelineHealthActivity snapshot; reconciler
split-brain unification; operator-run screen-off ≥10-min runtime test.



## V5.0.6468 (Feb 2026) — UPSTREAM DEDUP + PROVIDER FAULT CIRCUITS + INVARIANT AUDIT

Third beat of the Correctness Completion Patch (items 15-ext, 16, 17, 18).
CI green (Build APK + Runtime Smoke Test, sha 590ac919).

- **Advisor firewall extended** — `AdvisorIntegrityHold6466` now holds on
  EventStreamReplay6467 divergence, OrderSizeResolver invariant violations,
  and DataProvider auth lockouts. No learning on top of a broken environment.
- **OrderSizeResolverInvariant6468** — post-condition guard called at the
  tail of `OrderSizeResolver6441.resolve()`. Guards non-negative final,
  cash/lane ceilings, executable⇔positive-size, non-executable⇔reason.
- **ForcedCloseSlotSweeper6468** — reconciles CanonicalMintOccupancyRegistry
  against CanonicalPositionAuthority every 30 loops. Explicit
  `onForcedClose(mode, mint, reason)` for any forced/synthetic close path.
- **DataProviderFaultCircuits6468** — read-side circuit breakers for
  Birdeye, Groq, Helius, Solscan, generic. Distinct handling for 401/403
  (AUTH_LOCKOUT, no auto-clear), 404 (cache-only), 429 (backoff),
  5xx/IO (cache-only). Provider faults cannot crash the bot loop.

**V5.0.6469 (exit tuning) — INTENTIONALLY DEFERRED**  
Blocked per operator mandate: requires ≥20 post-fix MOONSHOT closes with
clean parity / replay / conservation metrics before exit tuning applies.


## V5.0.6467 (Feb 2026) — REPLAY + CAPITAL + RECONCILER HEARTBEAT

Second beat of the Correctness Completion Patch (items 9, 10, 12, 13).
CI green (Build APK + Runtime Smoke Test, sha 23c105b).

- **EventStreamReplay6467** — deterministic replay of the same
  `EconomicEventSchema6464` events the terminal path writes, reports the
  FIRST divergent event id (not just aggregate deltas). Wired into the
  30-loop parity audit next to `CanonicalPaperReplay6464`.
- **PaperEquityCalculator6467** — single equity calculator: 
  `equity = cash + markedOpenValue`; realized already embedded in cash.
  Consumes canonical `openMarketValueSol` from `CanonicalCapitalAuthority6450`.
- **ReconcilerHeartbeat6467** — single heartbeat surface. `ageMs` returns
  `-1` when uninitialized (no MAX_VALUE leakage). `WallClockReconciler6454`
  feeds it on every quick/full start+success.


## V5.0.6466 (Feb 2026) — POSITION AUTHORITY + TERMINAL FINALIZATION + ADVISOR INTEGRITY FIREWALL

First beat of the Correctness Completion Patch (items 1-8 + narrow 15).
CI green.



## V5.0.6465 (Feb 2026) — BUY PATH PUBLISH + CONSUMER FANOUT + REGISTRY AUTO-HEAL + IDENTITY WIRE

Four operator directives from the 6464 follow-up landed together.

**Buy path publish (paper + live)**: every confirmed executor buy now
publishes to `CanonicalLotQuantity6464`, `EconomicEventSchema6464`,
and `CanonicalMintOccupancyRegistry6464` at the same convergence point
that already ran `ExecutorCanonicalMirror6442.mirrorBuyFill`. Live path
keys on `mode="live"` — live routing stays fully functional.

**Consumer fanout ack**: `CanonicalFinalizedTradeBus6464.publish()`
auto-acks all registered consumers so a disconnected learner cannot
sit at zero. New `deliverToConsumers` returns per-consumer verdicts;
refused deliveries remove the ack. New `FinalizedBusConsumerBridge6465`
routes envelopes to each of the 8 consumers — `LosingStreakReflex`
now receives real `onTradeClosed` deliveries.

**Registry auto-heal**: `PositionRegistryParityAudit6464` fires
`healRegistryFromCanonical()` after 3 consecutive divergent audits.
Rebuilds the legacy `EmergentGuardrails` registry to match canonical.
Non-destructive to canonical state.

**Identity enforcement**: `EmergentGuardrails.registerPosition` now
records the 5 identity fields via `CanonicalIdentityModel6464.record`
at every paper open. `normalizeLane` at ingest → alias merges for new
positions approach zero.

CI: Build AATE APK + Runtime Smoke Test both green.



## V5.0.6464 (Feb 2026) — CORRECTNESS COMPLETION PATCH + EXPORT ONE-LINE-BUG FIX

11 new canonical modules covering 10 operator sections + a targeted
UX fix for the pipeline report export.

**Export one-line bug (operator report: "just the title")** — the
share intent stuffed a one-line description into `EXTRA_TEXT`.
Receivers that read `EXTRA_TEXT` (SMS, Signal, notes) showed only
that placeholder. Fixed in both `PipelineReportFileExporter6401`
and `ForensicReportExporter`: `EXTRA_TEXT` now carries the actual
report text (bounded to a Binder-safe 500_000 chars); `EXTRA_STREAM`
still attaches the full file.

**§P0 (7 items)**
- `CanonicalMintOccupancyRegistry6464` — (mode, mint) → occupancy;
  admission gate before hydration; live/paper isolated.
- `PositionRegistryParityAudit6464` — per-state breakdown + ID
  divergence lists; every 30 loops.
- `CanonicalLotQuantity6464` — sellableQty = bought − sold − reserved;
  clamps/rejects sells pre-executor.
- `TerminalSellIdempotency6464` — CAS on (sellExecutionId | fillId |
  signature) at `SellFinalizationCoordinator.finalize`.
- `EconomicEventSchema6464` — typed BUY/SELL rows (all fields explicit).
- `CanonicalPaperReplay6464` — replays typed events, emits
  `PAPER_REPLAY_PARITY_6464`.
- `CanonicalFinalizedTradeBus6464` — 8 consumers registered on startup;
  per-consumer ack set; zero-consumers surface as `FINALIZED_BUS_ZERO_CONSUMERS_6464`.

**§P1 (4 items)**
- `CanonicalIdentityModel6464` — persists 5 identity fields per
  positionId; refuses rewrite of canonicalOriginLane.
- `StopLatencyClasses6464` — NORMAL/TRAILING/HARD/CATASTROPHIC buckets;
  catastrophic >1000ms fires alert.
- `RootCauseTtl6464` — 5-min TTL by default; cleared on healthy
  terminal sell.
- `AuthoritySnapshotVersion6464` — monotonic epoch; executor validates
  version before mutation; mismatch → revalidate (kills AUTHZ_RACE).

**Live route preserved** — every 6464 module keys on `(mode, mint)`
so paper open cannot block live entry and vice versa. Live routing
stays fully functional when the operator switches modes.

**Follow-ups**
- Executor buy path event publish (parallel to sell path already wired)
- LearnerRewardBridge / LosingStreakReflex / GrowthRewardShaper /
  TacticSwitcher consumers pulling from `CanonicalFinalizedTradeBus6464`
  and calling `ack(consumer, tradeId)`
- Extend `PositionRegistryParityAudit6464` to auto-heal legacy
  EmergentGuardrails registry when divergence stabilises
- Wire `CanonicalIdentityModel6464.record` at every position-creation
  site (currently substrate is available, not yet mandatory)

CI: Build AATE APK + Runtime Smoke Test both green.



## V5.0.6463 (Feb 2026) — REVERT-ON-REGRESSION + ADVISOR TIMELINE + PARTIAL-SELL VALIDATOR + PERPS SANDBOX

Four operator directives from the 6462 follow-up landed together.

**Revert-on-Regression** (`AdvisorRegressionMonitor6463`)
- Every AutoPipelineAdvisor6462 auto-apply registers a pending audit
  with baseline WR/realizedPnl/trade count.
- `checkAll(ctx)` runs every 12 loops via MaintenanceWorker6448. After
  the 20-min window: if WR drops >5pp or realizedPnl drops >0.03 SOL
  with ≥3 decisive trades, a negated `<<TUNE>>` block is synthesised
  and routed through LlmParameterTuner (same safety gates as apply).
- Emits `ADVISOR_REVERTED_6463` and records a REVERTED entry in the
  timeline.

**Advisor UI Timeline** (`AdvisorDecisionHistory6463`)
- Ring buffer of the last 20 decisions with per-brain vote breakdown
  (name/agree/weight), old→new values, action taken.
- Attached to `PipelineHealthCollector.dumpText()` — visible in the
  existing UI without new layout.

**Executor Partial-Sell Migration** (`PartialSellCorrectness6463`)
- Wraps `SellFinalizationCoordinator.finalize()` — the single
  convergence point every partial + full sell flows through.
- Validates SOL slots through the Fi4FaM firewall + cross-checks
  arithmetic. Divergence emits `PARTIAL_SELL_ARITH_DIVERGENCE_6463`.
- Zero touches to Executor.kt's 24k lines — avoids 64KB compile
  limit regression.

**SOL Perps Sandbox** (`PerpsSandbox6463`)
- Paper-only leverage substrate with hard live-mode gate.
- 1x–10x leverage, `EXIT_LIQUIDATION` at
  `underlyingDropPct × leverageX ≥ 80%`.
- Config toggle: `BotConfig.perpsSandboxEnabled` (default OFF).
- BotService syncs `PerpsSandbox6463.setEnabled` on start.

**Follow-ups**
- Real perps route wiring (still no live execution — sandbox only).
- Extend the timeline UI with tap-to-apply / tap-to-dismiss controls.
- Wire the RiskExitPriorityDomain6461 lane-stats snapshot bus feed
  from ChronicBleederScout for full off-hot-path scout consumption.

CI: Build AATE APK + Runtime Smoke Test both green.



## V5.0.6462 (Feb 2026) — AUTONOMOUS PIPELINE ADVISOR (ALL-BRAINS + LLM)

Fixed the "advisor never returns suggestions" bug at the source. The old
`SelfHealingAdvisor.maybeAutoAdvise` was defined but never called from
BotService (dead code) and silently failed when the LLM returned null.

**New `AutoPipelineAdvisor6462`**
- Rules-first: 8 deterministic health rules produce candidates even with
  zero LLM connectivity (Fi4FaM, replay divergence, ledger invariant,
  risk latency, PENDING_ENTRY leaks, API degradation, terminal-sell
  duplicates, chronic bleeders).
- Consults every brain: MetaCognitionExecutorBridge, SuperBrainEnhancements,
  CapitalEfficiencyBrain, SentienceOrchestrator, BrainConsensusGate,
  RiskExitPriorityDomain6461. Weighted brain-agreement scores gate
  auto-apply.
- GeminiCopilot is now one voice among many, not the single point of
  failure. When it responds, LLM re-ranks and adds candidates.
- Auto-apply routes through the existing `LlmParameterTuner`
  (phase-gated ≥50 trades, freerange-scaled step cap, allowlist,
  min/max bounds) — no new safety code needed.

**Auto-apply invariants**
- `config.autoPipelineAdvisorEnabled` (default true, persists as
  `auto_pipeline_advisor_enabled` in shared prefs).
- `brainAgreement >= 0.55`.
- `severity in {med, high}`.
- Per-key cooldown 10 min.
- Max 2 auto-applies per tick.
- Every applied change emits `AUTO_PIPELINE_ADVISOR_APPLIED_6462` with
  key/old/new/reason/agree/source for full audit.

**Cadence**: `maybeTick(ctx)` fires from `BotService.botLoop` every 12
loops (~2 min), dispatched to `Dispatchers.IO`. Rate-limited to 90s min
between successful runs. Failed runs don't consume the interval.

**Follow-ups**
- UI surface in PipelineHealthActivity to show live advisor history
  + apply/dismiss decisions.
- Revert-on-regression: if WR/EV drops within N minutes of an applied
  change, auto-revert.

CI: Build AATE APK green + Runtime Smoke Test green.



## V5.0.6461 (Feb 2026) — PARTIAL PNL UNIT ISOLATION + PENDING_ENTRY SWEEP + PAPER REPLAY + RISK DOMAIN

Bundled the four remaining P0 items from the 6457 emergency dump into a
single CI-green ship. Build and Runtime Smoke Test both passed.

**§P0-#1 Fi4FaM unit-corruption fix** (`PartialSellUnitTypes6461`)
- Deleted `realizedPnl = pnlPct.toDouble()` at `LivePositionCloseAuthority
  .finalizeClosed` — a percentage was being written into a SOL slot,
  corrupting the finalized-trade bus.
- New `RealizedSol` / `ReturnRatio` / `ReturnPct` inline value classes
  make future mixups a compile-time error.
- `PaperAccountLedger6430.onSell` now firewalled: values |x| > 30 SOL
  clamp to 0 and emit `FI4FAM_UNIT_CORRUPTION_6461`.

**§P0-#2 PENDING_ENTRY → OPEN collapse** (`PendingEntryProjectionGuard6461`)
- `pendingEntryPositions6461()` iterator + `cancelStalePendingEntries6461(ttlMs)`
  with 90s default TTL. Quarantines pending rows and refunds placeholder
  paper cash. `assertNotInOpenSet()` regression trap.
- Sweep runs every 6 loops through `MaintenanceWorker6448` (1.5s budget).

**§P0-#3 Rebuild paper account from economic events** (`PaperAccountReplay6461`)
- Replays `TradeHistoryStore` (BUY/PARTIAL/FULL) oldest-first to
  reconstruct cash/openCost/realizedPnl/fees. Emits
  `PAPER_REPLAY_DIVERGENCE_6461` when ledger disagrees by > 0.01 SOL.
- Skips Fi4FaM-poisoned rows. Non-mutating.
- Audit every 30 loops (paper mode) through `MaintenanceWorker6448` (3s).

**§P0-#4 Risk exit priority domain** (`RiskExitPriorityDomain6461`)
- HIGH (risk) vs LOW (scout/learner) execution domains with latency
  instrumentation. `RISK_DOMAIN_HIGH_LATENCY_ALERT_6461` at > 1000ms.
- Immutable lane-stats snapshot bus for scouts.

**CI locks (`PartialPnlUnitCorrectnessTest6461`)**
- SOL vs ReturnPct compile-time distinctness.
- Fi4FaM firewall clamps −500.0 → 0.0.
- `PaperAccountLedger6430.onSell` cash cannot absorb a Fi4FaM injection.
- PENDING_ENTRY excluded from openPositions; TTL sweep quarantines.
- Replay reflects starting cash on empty ledger.
- High-priority latency alert fires above 1000ms.

**Follow-ups**
- Migrate remaining partial-sell paths in Executor.kt to the new inline
  value classes.
- Extend `PaperAccountReplay6461` to auto-heal (currently non-mutating).
- Move `ChronicBleederScout` fully onto `RiskExitPriorityDomain6461`'s
  lane-stats snapshot bus (currently reads directly from
  StrategyTelemetry via a budget guard).

CI: Build AATE APK green + Runtime Smoke Test green.



## V5.0.6453 (Feb 2026) — COMPLETE CONVERGENCE

Deleted obsolete writers. Single reward owner installed as bus subscriber.

**Deletions (obsolete writers)**
- `PositionCloseLedger.markClosed` — no longer calls shaper directly.
- `PositionCloseLedger.markClosedFull` — no longer calls shaper directly.

**Additions**
- `CanonicalRewardBootstrap6453` — installs shaper + purity gate as
  bus subscribers exactly once.
- Atomic `MintWorkCoordinator6450.acquireOrAttach` (putIfAbsent).
- Executor `getActualPrice` stamps `QuoteFreshnessGuard6452` with
  typed Provenance + real age.

**CI locks**
- `ConvergenceAcceptanceTest` — atomic mint coord, bootstrap
  idempotency, subscriber invocation.

CI: Build AATE APK green (run 31950418267). Runtime Smoke Test green
(run 31951126380).



## V5.0.6452 (Feb 2026) — SOURCE-LEVEL AUTHORITY CONVERGENCE

Operator mandate: fix defects at their source, add HARD CI assertions.

**Source fixes**
- Fee double-count in `PaperAccountLedger6430.onSell`: cash now credits
  `(G − f_s)`, realized stores GROSS `(G − C)`, fees tracked separately.
  Invariant `startingCash + realized − fees == cash + openCost` holds
  algebraically.
- `CanonicalTradeFinalizedBus6450.Event` gains typed `grossRealizedPnlSol`
  + `returnFraction`.
- `clampToRemainingStrict` returns `UNKNOWN_POSITION` (qty=0) — never
  fail-opens.
- No fake mark price in cycle heartbeat (markPx=0 = ping only).
- `QuoteFreshnessGuard6452` primitive with typed Provenance.

**Hard CI assertions**
`CapitalInvariantAcceptanceTest` under `testReleaseUnitTest`:
- capital conservation δ=0 on winning + losing round-trips
- realized PnL is GROSS
- duplicate SELL rejected by terminal latch
- bus publishes exactly once per positionId
- unknown canonical sell state = qty=0

**Follow-ups**
- Migrate all sell paths from `clampToRemaining` → `clampToRemainingStrict`.
- Wire quote producers to `QuoteFreshnessGuard6452.note()`.
- Subscribe shaper + purity gate to the bus (single owner).

CI: Build AATE APK green (run 31947796912). Runtime Smoke Test green
(run 31947831859).

## V5.0.6451 (Feb 2026) — SL HOT PATH + ENTRY GATE + WALLET UI SPLIT

- Scheduler `evaluate()` at top of `Executor.riskCheck` (per-tick trigger
  latching independent of scanner/learner).
- Entry authority gate at top of paperBuy + liveBuy before capital
  reservation.
- Wallet UI shows 5 canonical surfaces via contentDescription + dedicated
  pipeline dump block.



## V5.0.6450 (Feb 2026) — CAPITAL / EXIT / QUALITY REGRESSION REPAIR

Twelve canonical authorities under `engine/truth/` addressing every P0/P1
in the operator's 6450 mandate. Each module is wired at its originating
layer and reports via `statusLine()` in the pipeline dump.

**P0 authorities**
- `CanonicalCapitalAuthority6450` — CASH/RESERVED/OPEN_MV/UNREALIZED/
  REALIZED/EQUITY read surface + invariant audit.
- `TerminalCloseIdempotencyLatch6450` — duplicate-SELL guard.
- `ProtectiveExitScheduler6450` — monotonic trigger latch + heartbeat.
- `PostLearningOffloader6450` — priority-tagged offload façade.
- `EntryStrategySnapshot6450` — immutable entry lane/pid/tactic.
- `CanonicalTradeFinalizedBus6450` — one W/L/BE event per positionId.
- Reconciler watchdog wrapping (quickCheck + fullReconstruct).

**P1 authorities**
- `ClassificationProvenanceGuard6450` — BLUECHIP/QUALITY provenance.
- `QualityIntakeTrace6450` — 7-stage funnel + terminal attrition reason.
- `MintWorkCoordinator6450` — intake dedup + open-mint BUY block.
- `ExecutableEntryAuthority6450` — single loss-streak / cooldown gate.
- `LearningQuarantine6450` — implausible cohort quarantine.
- `RunnerLedgerHealthGate6450` — capital-safe compounding gate.

**Follow-ups after next operator dump**
- Wire `ProtectiveExitScheduler6450.evaluate()` into every price-tick
  site in `Executor.kt` so SL/TP triggers latch synchronously.
- Wire `ExecutableEntryAuthority6450.gate()` at every executable BUY
  route immediately before capital reservation (paperBuy, liveBuy,
  copyTradeBuy, expressBuy, etc.).
- Wire `MintWorkCoordinator6450.acquireOrAttach()` at each discovery
  source (PumpPortal / DEX / Raydium / CoinGecko / scanner heal /
  scanner direct).
- Wire `PostLearningOffloader6450.offload()` around every heavy
  learning/aggregation/report block that still runs on the trading
  cycle in `BotService.botLoop`.
- Wire `ClassificationProvenanceGuard6450.qualifyBluechip/Quality()`
  into the classifier so tags cannot be assigned from fallback data.

CI: Build AATE APK green (run 31935749040). Runtime Smoke Test green
(run 31936271335). PAPER MODE ONLY.



## V5.0.6449 (Feb 2026) — SELL QTY SOURCE LOCK + CASH AUDIT + REAL CLOSE FUNNEL + TRADER_SYNC ASYNC

Operator (V5.0.6448 dump — 4 P0s): oversell (2x), −0.809 SOL cash leak,
Canonical/Registry drift, and 102s TRADER_SYNC stall cascade.

### §1 Real Close Funnel
- `PositionCloseLedger.markClosed` (compact API) now emits
  `GrowthAlignedRewardShaper6439.shape` + `RewardPurityGate6441.acceptFinalizedClose`
  on first insertion. Every compact close writer now feeds the reward stack.
- TTL-dedup prevents double-fire when `markClosedFull` follows.

### §2 Paper Cash Conservation Audit
- `CanonicalIntegrityGuards6449.auditConservation` reads
  `PaperAccountLedger6430` (the wired authority) and emits
  `CAPITAL_CONSERVATION_VIOLATION_6449` with first-offending mint when
  `startingCash + realized − fees == cash + openCost` breaks.
- Wired into `runCanonicalCycleEndHooks6443` every 12 loops (~2 min),
  paired with `PaperAccountLedger6430.assertInvariant`.

### §3 Sell Qty Source Lock
- `CanonicalIntegrityGuards6449.clampToRemaining` — single sell qty
  source: `CanonicalPositionAuthority6441.remainingQtyRaw`. Wired at:
  - `paperSell` full-exit (Trade row + mirror derive from `soldQtyToken6449`).
  - Autonomous partial sell.
  - Manual `executeProfitLockSell` partial.
  - `recordTrade` journal prefers `trade.entryQtyToken` for SELL rows.
- Oversell is now structurally impossible.

### §4 Trader Sync Async
- `POST_LEARNING_TRADER_SYNC` routed through
  `MaintenanceWorker6448.submit` (4s deadline). Kills the 102s stall
  cascade that appeared after 6448 unblocked `POST_LEARNING_MAINTENANCE`.

CI: Build AATE APK green (run 31928822433). Runtime Smoke Test green
(run 31929384706). PAPER MODE ONLY — live execution remains disabled.

### Next up (P1, gated on operator pipeline dump proving 0 leak / 0 stall)
- SOL Perps/Leverage paper-only toggle.
- Continue extraction from `Executor.kt` / `BotService.kt` into `engine/truth/`.
- Neural bridge (perps↔stocks cross-learning) — P2.
- LLM Lab sandbox — P3.



## V5.0.6442-6443 (Feb 2026) — CONSUMER MIGRATION + JVM METHOD-SIZE HOTFIX

Operator: V5.0.6441 next actions completed. All 5 consumer migrations
shipped via additive-mirror pattern.

### §1 Executor writer migration
- `CanonicalPositionAuthority6441` gained PENDING_ENTRY lifecycle +
  `promotePendingToOpen` for fills.
- `ExecutorCanonicalMirror6442` — mirrorBuyAttempt / mirrorBuyFill /
  mirrorSell shared helpers. Wired in Executor.paperBuy.

### §1 Sizing migration
- `EdgeOptimizer.calculatePositionSize` output routed through
  `OrderSizeResolver6441.resolve` for canonical audit trail.

### §4 Scanner intake gate
- `ScannerFanoutDedupe6374.admit` consults `SameMintDedupAuthority6441`
  after TTL dedupe — blocks entry work on already-open mints.

### §6 Reward bus migration
- `PositionCloseLedger.markClosedFull` mirrors sell → canonical, then
  fires `RewardPurityGate6441.acceptFinalizedClose`.

### §5 Journal schema migration
- `ForensicRowMirror6442` emits canonical rows at every close, verifies
  invariant, buffers 512 for FULL reconciler diffs.

### V5.0.6443 hotfix
- V5.0.6442 pushed botLoop past JVM 64KB method limit; extracted the
  cycle hooks into private helpers (behaviour preserved 1:1).

CI: Build AATE APK green (run 31676111762).

## V5.0.6441 (Feb 2026) — SOURCE-FIRST PIPELINE CORRECTION (AUTHORITIES ESTABLISHED)

Operator: 12-domain source-first mandate. Fix defects at their originating
state/authority boundary. No lane disabling, PAPER execution-faithful to
LIVE, no background trading.

### Modules created (all engine/truth/)
- `CanonicalPositionAuthority6441` — §1/§2/§3 single mutable truth.
- `OrderSizeResolver6441` — §1 mandatory sizing pipeline.
- `SameMintDedupAuthority6441` — §4 source-first mint dedup.
- `ForensicExecutionRow6441` — §5 immutable 17-field schema.
- `RewardPurityGate6441` — §6 canonical W/L/BE bus.
- `LearnerRuntimeBudgetGuard6441` — §7 bounded slices.
- `CanonicalReconciler6441` — §8 QUICK + FULL modes.
- `RootCauseTelemetry6441` — §10 subsystem attribution.
- `StartupInvariantGate6441` — §11 reconstruction gate.
- `AcceptanceInvariantAudit6441` — §12 acceptance runner.

### Wiring
- Cycle begin: dedup + root-cause reset.
- Cycle end: root-cause classify, QUICK reconcile @loop%9, audit @loop%6.
- AATEApp: startup gate opens after storage attach.
- Pipeline dump: 9 new status lines.

### Deferred to V5.0.6442+ (migration phases)
- Executor paper/live BUY/SELL paths → `CanonicalPositionAuthority6441`.
- Executor sizing sites → `OrderSizeResolver6441`.
- Learner subscribers → `RewardPurityGate6441`.
- Scanner intake → `SameMintDedupAuthority6441`.
- Journal writers → `ForensicExecutionRow6441`.

CI: Build AATE APK green (run 31672054299).

## V5.0.6440 (Feb 2026) — SHAPER→LEARNERS + RUNNER LADDER + RUNTIME HEALTH

Operator V5.0.6439 follow-up: (1) wire shaper into SentienceAutoTune /
AdaptiveLearning / LabUniverse, (2) runner compounding ladder so
\$50→\$500 trades 10x bigger than \$50→\$100, (3) foreground service
so trading survives Doze.

### Wire shaper into learners
- `LearnerRewardBridge6440`: side-effect free doctrine derivation.
- `AdaptiveLearningEngine.adjustWeights` uses `adjustmentFactorShaped`
  = classic factor × bridge multiplier.

### Runner compounding ladder
- `RunnerCompoundingLadder6440`: 11-tier ladder doubling every rung.
- `EdgeOptimizer.calculatePositionSize` blends confidence size with
  ladder floor, clamps to maxPositionPct.

### Trading runtime health
- BotService is already a foreground service with WakeLock + WifiLock +
  AlarmManager keepalive. `TradingRuntimeHealthWatchdog6440` emits
  60s heartbeat + Doze enter/exit lifecycle events.

CI: Build AATE APK green (run 31660294508).

## V5.0.6439 (Feb 2026) — FEE OBSERVABILITY + CAPITAL PRESERVATION CREED + REWARD SHAPING

Operator: (1) live fees not landing at the two coded wallets, (2) full
live-trading correctness sweep, (3) meta-cognition/AGI must prioritise
wallet growth + capital protection aligned with $50→$1M mindset, bad
behaviour must NEVER be reinforced as good, (4) paper→live learning
must transfer at trade ~1000 with zero retraining.

### P0 — Fee flow observability + force-flush
- `FeeAccrualObservability6439`: emits `FEE_ACCRUE_6439` + `FEE_FLUSH_6439`
  so we can prove exactly where the pipe leaks.
- `Executor.sendFeeSplit` wired to noteAccrue BEFORE FeeAccumulator.accrue.
- `BotService` cycle drain now runs whenever `WalletManager.getWallet()`
  returns a live wallet (no more `!cfg.paperMode` gate).
- Boot-time `FEE_WALLET_DIVERGENCE_CHECK_6439` prints self vs both fee
  wallets so a self-loop is loud at startup.

### P0 — Capital preservation creed
- `CapitalPreservationCreed6439`: 5% daily / 30% weekly compounding
  targets, 8%/18% DD ceilings, 3 max consec losses, 1.15x min EV.
- `LosingStreakReflex6439.shouldBlockNewBuys()` inserted at the TOP of
  `FinalExecutionPermit.canExecute` — every buy gate consults it.

### P0 — Meta-cognition / AGI alignment
- `GrowthAlignedRewardShaper6439.shape()`: single reward function.
  Break-even = negative reward; losses amplify up to 3x by hold time.
  Wired at `PositionCloseLedger.markClosedFull` so every real close
  funnels through it.
- `AntiRewardHackingGuard6439.canExpandRisk()`: vetoes any risk
  expansion while wallet below its 24h high.

### P0 — Paper↔live parity
- `PaperLiveParityCreed6439`: enumerates 10 mandatory-parity learning
  artefacts. Boot emits `PAPER_LIVE_PARITY_6439` per artefact.

CI: Build AATE APK green (run 31658938759). Awaiting operator dump.


## V5.0.6438 (Feb 2026) — POST_LEARNING BISECTION MARKERS

## V5.0.6437 (Feb 2026) — ANR DIAGNOSTIC + IDEMPOTENCY-KEY PERSISTENCE

Operator V5.0.6436 dump: cycles spiking to 75,029ms with workerTimeout=62
despite supervisor being hard-bounded to 20s. The overshoot lives OUTSIDE
runSupervisorPhase — either the pre-supervisor learning fanout or one of
the 1500-line ENTER→PRE_SUPERVISOR unchecked blocks that had zero
markProgress markers, making the wedge invisible.

### P0 — Slow cycle diagnostic + pre-supervisor learning budget guard
- `SlowCycleDiagnostic6437` — passive telemetry that attributes wall-clock
  time to each markProgress phase. Emits `SLOW_CYCLE_DIAGNOSTIC_6437`
  with top-3 phase spend whenever a cycle >30s so the operator sees
  EXACTLY which block wedged.
- `PreSupervisorBudgetGuard6437.runBudgeted(name, block)` — wraps
  ChronicBleederScout / SentienceAutoTune / LabUniverseTick with
  per-learner wall-clock measurement + a 5s cycle-level fanout budget.
  `LEARNING_FANOUT_SLOW_6437` fires when a learner exceeds 2s;
  subsequent learners in the same cycle are skipped when the budget is
  exhausted. Belt on top of prevCycleWasSlow6421.
- `LEARNING_DONE` markProgress marker inserted after the learner
  fanout so the diagnostic can distinguish learner wedges from
  reconcile / watchdog / watchlist-rebuild wedges.

### P1 — Idempotency-key SQLite persistence
- `IdempotencyKeyStore6437` — SQLite WAL-backed store (same pattern as
  PortfolioStore6405). `checkAndReserve(key)` returns NEW or DUPLICATE
  atomically via `INSERT OR IGNORE`. Key formats:
  ```
  BUY:runId:positionId
  SELL:runId:positionId:generation
  ```
  Attached in `AATEApp.onCreate()` alongside PortfolioStore6405 so the
  in-memory ExecutionTicketMachine6411 gate has a durable belt beneath
  it — a mid-transaction process restart can no longer resubmit a
  live order.

CI: Build AATE APK green (run 31621971780). Awaiting operator runtime
telemetry to confirm cycle-time recovery.

## V5.0.6414 (Feb 2026) — GOVERNOR RECOVERY AUTO-UNSTICK

Operator report showed V5.0.6411/6412 authorising 176 buys but submitting
0 (LIVE_BUY_ABORTED=176). Route resolver worked (EXEC_ADAPTER_SELECTED_6411=176)
but every trade died at LaneEntryContract6342 because GovernorRecovery6388
was stuck in BLOCKED_INFRASTRUCTURE for the entire uptime. Root cause:
SELL_RECONCILER_LIVE_STARTUP_HARD_FAIL kept `sellReconcilerStarted=false`
forever, so `infra.healthy()=false` forever, so HOLD_PROBATION never
activated, so probation-sized escape trades could not run.

- `GOVERNOR_RECOVERY_AUTO_UNSTICK_6414`: after 120s in BLOCKED_INFRASTRUCTURE
  with governor HOLD AND every infra signal HEALTHY EXCEPT sell-reconciler,
  force-promote to HOLD_PROBATION. Bounded — ProbationEntryLimiter6388
  still caps to 1 min-size buy per throttle window. Real infra faults
  (wallet unavailable, canonical discrepancy, unresolved fill) still
  block via their own signals.

## V5.0.6413 (Feb 2026) — PAPER-BALANCE WIPE GUARD + DIVERGENCE EMIT THROTTLE

- `PAPER_WALLET_WIPE_GUARD_HELD_6413`: `botPrefs.contains("paper_wallet_sol")`
  distinguishes "key never written" from "racy read returned default 0".
  Fresh-install seed now REQUIRES key absence; a corrupted 0-read HOLDS
  the incoming zero rather than overwriting the balance.
- `LIVE_PAPER_DIVERGENCE_DUST_PROBE_6279` now routes through
  `ForensicEmitRateLimiter6356` per-lane. Kills the 8-in-a-tick BLUECHIP
  spam that choked the loop back to 42s avg / 257s max.

## V5.0.6412 (Feb 2026) — POSITION COST-BASIS REPAIR + PHANTOM -100% GUARD

Operator screenshot: ANTHROPIC "-100.0% Size 0.0000◎" while the wallet
held 4667 tokens at $0.000148 — a +277% winner. Chud showed similar
"Size 0.0000◎" corruption. Root cause: pos.costSol had gone to zero
while qtyToken and entryPrice remained intact, so gainPct blew up to
-100% and Size = 0.

- `PositionCostBasisRepair6412`: read-side authority reconstructs
  costSol from qtyToken × entryPriceUsd ÷ solPriceUsd when the ledger
  value is missing. Emits POSITION_COST_BASIS_REPAIRED_6412 with the delta.
- `UI_PHANTOM_LOSS_SUPPRESSED_6412`: MainActivity render refuses -100%
  when the wallet holds real token balance and the mark is zero/stale;
  downgrades to "basis wait" so exit coordinator + learning don't act
  on the phantom.



## V5.0.6411 (Feb 2026) — LIVE_EXECUTION_RECOVERY_AND_PIPELINE_HARDENING

Build 6410 authorised 501 live buys and SUBMITTED ZERO. This build's
sole objective was to resume trading while preserving safety controls,
lane diversity, and forensic auditability. Shipped as 5 landable
commits (§A-§E), each independently green in CI.

### §A — LIVE EXECUTION RECOVERY (P0) ✅ Build+Smoke green
- **ExecutableVenue6411 + AdapterCapability6411**: venue enum
  (PUMP_FUN_BONDING_CURVE, PUMPSWAP, RAYDIUM_CPMM/CLMM, ORCA_WHIRLPOOL,
  JUPITER_ONLY, MULTI_VENUE, UNKNOWN_PENDING, UNSUPPORTED) with an
  explicit capability matrix per adapter.
- **ProviderDomainCircuits6411**: per-adapter circuit breakers
  (CLOSED/HALF_OPEN/OPEN, 3-fail streak / 2-ratelimit streak / 5-in-60s
  rolling, 20s min open, 5min max backoff). Jupiter open no longer
  blocks PUMP_FUN_DIRECT.
- **RouteResolver6411**: classifies mint→venue, selects highest-
  preference healthy adapter. Emits typed terminals
  EXEC_VENUE_RESOLVED_6411, EXEC_ROUTE_RESOLVED_6411,
  EXEC_ADAPTER_SELECTED_6411, EXEC_ADAPTER_SKIPPED_CIRCUIT_6411,
  EXEC_ROUTE_TERMINAL_6411, ROUTE_JUPITER_SKIPPED_CIRCUIT_OPEN_6411.
- **Executor.liveBuy**: replaced the §4161 global
  `shouldDeferBuy()` with `RouteResolver6411.resolve(ts)`. When no
  adapter is available a typed BUY_DEFERRED_NO_HEALTHY_ADAPTER_6411
  fires with venue + candidates + skipped. Legacy §4161 signal
  retained as advisory counter only.
- **ExecutionAttemptJournal6411**: forensic row per intent even when
  no chain tx is sent. Bounded ring (cap=512). Emits
  EXEC_ATTEMPT_JOURNAL_WRITTEN_6411.
- **LiveExecutionReadiness6411**: split readiness tile (policy /
  route / rpc / wallet / journal / reconcile) + deterministic
  bottleneck inference per §19 precedence. Build 6410 now surfaces
  AUTHORISED_NOT_ROUTED instead of masking as "LIVE ENTRY AUTHORITY open".

### §B — TICKET STATE MACHINE + CANARY MODE ✅ Build green
- **ExecutionTicketMachine6411**: full ticket state enum
  (CREATED..OPEN_CONFIRMED..TICKET_EXPIRED), CAS-guarded transitions,
  idempotency key (wallet|mint|side|decisionId|5sBucket), bounded
  age budgets (meme=25s, standard=60s), sweepExpired() to force
  terminals. Terminal states cannot revive.
- **LiveCanaryMode6411**: post-install canary
  (maxConcurrentLiveBuys=1, forceMinSize=true), ramp
  CANARY_ACTIVE→RAMP_2→NORMAL after 2+2 clean confirms; any
  invariant failure forces back to CANARY_ACTIVE.

### §C — SCANNER DEDUPE + TOKEN-MAP VERSIONS + CYCLE PROFILER ✅ Build green
- **ScannerCanonicalDedupe6411**: pre-enrichment dedupe by
  mint|pool|source-family|5s-epoch (TTL 5min, cap 4096). Source-
  family normalisation collapses ~18x per-callback fan-out.
- **TokenMapVersionGuard6411**: monotonic mappingVersion +
  laneRoutingVersion; stale metric writes drop with
  TOKEN_MAP_VERSION_STALE_DROP_6411 /
  LANE_ROUTING_VERSION_STALE_DROP_6411.
- **CycleProfiler6411**: per-phase timing with dominant-phase
  attribution on slow cycles (>12s). Emits
  CYCLE_SLOW_PHASE_ATTRIBUTION_6411.

### §D — WORKER-POOL DOMAINS + SAFETY-PROOF DEGRADATION ✅ Build green
- **WorkerPoolDomainRegistry6411**: priority-ordered domain pools
  (EXIT_RECONCILE p1 through UI_REPORT p8). canDegradedEntry()
  guarantees exits + reconciliation stay operational when entry
  pools are disabled (§22 containment).
- **SafetyProofDegradation6411**: CONFIRMED_SAFE / CONFIRMED_UNSAFE /
  UNKNOWN_PROVIDER_DEGRADED / CACHED_WITHIN_TTL taxonomy; unknown
  proof yields 0.40x size + 12 floor + short TTL (60s meme /
  5min established).

### §E — LANE QUARANTINE SCOPING + EXIT INVARIANT + POSITION IDENTITY ✅ Build+Smoke pending
- **LaneQuarantineRegistry6411**: scoped
  (TOKEN/POOL/LANE/PROVIDER/EXECUTION_ADAPTER) with explicit
  expiry + owner + evidence + releaseCondition. Rejects
  no-expiry records unless permanentOverride=true.
- **ExitPipelineInvariant6411**: EXIT_PIPELINE_CRITICAL_6411 fires
  when openLivePositions>0 AND no successful sweep in 60s.
  Auto-recovers on next sweep.
- **PositionIdentity6411**: canonicalKey = wallet|mint|paper/live
  (pool/symbol excluded). guardMerge() enforces 5 preconditions
  (wallet/mint/mode/tokenProgram/history).

### Deferred to V5.0.6412+
- Concrete wiring of ScannerCanonicalDedupe/TokenMapVersionGuard
  into scanner + TOKEN_MAP_START call sites
- Concrete wiring of ExecutionTicketMachine + LiveCanaryMode into
  Executor.liveBuy (size clamp + concurrency gate)
- Alias-merge migration to PositionIdentity6411.guardMerge
- Bot loop hooks for CycleProfiler6411 (phase brackets) and
  ExitPipelineInvariant6411 (sweep hooks)
- §7.3 per-phase time budgets, §13 no-pair bounded recovery,
  §17 UI throttling, §25 automated acceptance tests



## V5.0.6410 (Feb 2026) — LOOP CHOKE HOTFIX ✅ CI GREEN

Operator emergency dump (V5.0.6308 fmt) showed the bot loop stalled at 65-226s per cycle:
`reason=full_builder_timeout_8s anrHints=656 JOURNAL_UNMAPPED_TAG_6405=143 766 (~82/sec)`
`LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_4550=15 815 (~9/sec)`.

- **§A ForensicLogger bridge fast-exit**: skip `JournalMigrationAdapter6405.map()`
  entirely unless the tag contains one of the six canonical prefixes
  (BUY_/SELL_/POSITION_TERMINAL/CLOSED_/DECIMAL_INTEGRITY_HARD_BLOCK/
  PRICE_INTEGRITY_HARD_BLOCK/DUPLICATE_EXIT_BLOCKED). Killed the 82/sec
  10-branch scan + atomic labelInc churn on every lifecycle emit.
- **§B held-block emit throttle**: rate-limit LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_4550
  to at most one emit per 5s. Protection still runs on every pass; only
  the log line is throttled. Companion counter
  LIVE_HELD_SOURCE_REBALANCE_EVICT_BLOCKED_THROTTLED_6410 shows suppressed volume.

## V5.0.6409 (Feb 2026) — Growth Dashboard + Kelly + Small-Wallet Turbo + EV Roll-Up ✅ CI GREEN

- **§1 Growth Dashboard** (`GrowthDashboardSnapshot6409`): compact tile in
  OperatorAuxiliaryStatusDigest showing capRelax + eliteBoost + flowBoost +
  loserCooldown + evGate + rollup counters at a glance.
- **§2 Kelly-Aware Elite** (`PaperEvBucketGate6405.sizeMultiplier`): elite
  bucket (trades>=10 AND WR>=70%) now scales with actual edge via a half-Kelly
  proxy `eliteMult = (1.5 + evProxy.coerceIn(0,0.25)*4.0).coerceIn(1.5, 2.5)`
  instead of a fixed 2.0×.
- **§3 Small-Wallet Turbo** (`Executor.determineRealisticLiveSize`):
  when `spendable < 0.20 SOL AND runnerBoost > 1.0` the doctrine walletCap is
  bypassed; cap becomes `minOf(liquidityCapSol, spendable)` so early growth
  compounds from a tiny bankroll while still respecting pool depth.
- **§4 Realised-EV Roll-Up** (`RealisedEvRollUp6409`): every 10 closed trades
  logs baseline SOL / current SOL / deltaSol / deltaPct for the $50→$1M
  trajectory visibility. Wired into the sell terminal path right after
  EV_GATE_LEARNING_LOOP_6405.
- **CI fix**: GoldenTapeRegressionTest.aate4573CommonSensePlaybookIsWired... at
  line 7080 now accepts `agiCeiling6090|6406|6409` after V5.0.6406/6407/6408
  legitimately extended the ceiling variable name.


## Session shipping stack (V5.0.6405, 16-section directive)

- **6405 sell coverage + replay + invariant runner** (`f5b48e367`)
  - Sell-verify wired to all THREE SELL_TX_PARSE_OK sites:
    executeProfitLockSell full (`~6178`), executeProfitLockSell
    partial (`~17333`), and liveSell full-exit (`~20655`).
  - AATEApp.onCreate loads `PortfolioStore6405.openPositions()` on a
    background thread, rehydrates `CheckpointRecoveryAuthority6405`,
    seeds `PositionGenerationBridge6405` + raw-qty ledger, and runs
    `replay()`. Restarts now recover from the ACID store, not
    SharedPreferences.
  - New daemon thread runs `PortfolioInvariants6405.verify()` every
    30s so I3 (over-sold) violations surface within one tick.

- **6405 wire-up** (`9e4b7145e` + `620842ff9` + `e96bdf177`)
  Executor buy-verify + sell-verify + attach + ForensicLogger bridge.

- **6405 §1+§2** (`c32b35da1` ✅) CRASH-SAFE PORTFOLIO STORE (SQLite
  WAL + ACID transactions).

- **6405 §5** (`0e57103db` ✅) DECIMAL INTEGRITY HARD BLOCK.

- **6405 §3/§4/§6/§7** (`01bc607e5`, superseded — code shipped)
  Checkpoint recovery, terminal finality, price/pair integrity,
  canonical event stream.

- **6405 §8/§9/§10/§11** (`ac0922108`, retried) Paper/live parity,
  compounding, global entry policy, multi-horizon holding.

- **6405 §12-§16** (`d4ae9f0a5`, retried) Lane profiles, entry
  timing, journal migration, invariants, capital recycling.

## Session shipping stack (6402 → 6404 §A, all CI GREEN)

- **6404 §A** (`644b4a727e` + `09206065e5` ✅ Build) STRATEGY_CLEAN
  COUNTER DEDUPE. Gates STRATEGY_CLEAN_TERMINAL_ROWS,
  STRATEGY_PARTIAL_NOT_TERMINAL, STRATEGY_TERMINAL_DEDUPED and
  STRATEGY_MINT_CLOSE_WINDOW_DEDUPED_4494 behind a bounded
  (8,192-entry LRU) lifetime seenTerminalKeysLifetime set. Kills
  the 2,464,929 event / 6.16h storm (~111/sec) that drove the
  210s bot-loop stalls. Testing agent iteration_8 verified all 4
  claims PASS; zero critical/minor issues.

- **6403** (`629c294e7` ✅) FULL EXIT SWEEP start/done +
  hard-deadline 5000ms.
- **6402b** (`e698e09d8` ✅) SAME-MINT CANDIDATE EPOCH WIRE.
- **6402a** (`07668c202` ✅) V5.0.6402 SUBSTRATE (Universal SL
  lease registry + provider circuit breakers + stage timing +
  same-mint epoch + exit-pending orphan guard). 22 invariants.


## Session shipping stack (6388 → 6402b, all CI GREEN)

- **6402b** (`e698e09d8d` ✅ Build) SAME-MINT CANDIDATE EPOCH WIRE.
  * ExecutableOpenGate PAPER same-mint gate now routes through
    `SameMintCandidateEpoch6402.shouldSuppress` before emitting a
    full lifecycle row. First hit gets the loud row; subsequent
    hits within the 2s cooldown are silently deduped into a single
    counter. Kills the 68 PAPER_SAME_MINT + 11 V3_SAME_MINT
    lifecycle duplicates the 6401 snapshot showed.
  * `EmergentGuardrails.unregisterPosition` bumps the mint's epoch
    via `SameMintCandidateEpoch6402.onStateChange` so the next
    candidate is admitted right after position close.

- **6402a** (`07668c202a`) V5.0.6402 SUBSTRATE + surgical wires.
  * `UniversalSlLeaseRegistry6402` — acquire()/release()/
    reapStaleLeases() with 10s TTL. `BotService.exit-coordinator`
    now wraps the universal sweep in try/finally around acquire/
    release so the 6401 snapshot's slStart=7 slDone=6 gap becomes
    structurally impossible.
  * `ProviderCircuitBreaker6402` — Birdeye 401/403 → permanent
    auth-terminal circuit; Helius 429 → exponential shared backoff
    honouring Retry-After; 5xx → 2s transient cool-down; success
    → clears transient state (auth-terminal untouched). Wired at
    BirdeyeApi.getRaw + HeliusCreatorHistory.postRaw — every
    caller pre-checks `shouldSkip`, response codes route to
    onAuthTerminal/onRateLimited/onServerError/onSuccess.
  * `BotLoopStageTiming6402` — substrate ready for wire-in around
    every top-level loop stage. Stage enum + `time(cid, stage) { }`
    that emits START/DONE (or EXCEPTION) with elapsedMs.
  * `SameMintCandidateEpoch6402` — substrate for §H.
  * `ExitPendingOrphanGuard6402` — pure verdict surface for §G
    (NotPending / HealthyPending / Orphaned / StaleIntent).
  * Bundle6402SubstrateTest — 22 invariants covering acquire/release
    cycles, stale-lease reap after 10s, Birdeye 401 auth-terminal
    persistence, Helius 429 exponential backoff + Retry-After,
    stage timing exception rethrow, same-mint suppression + cooldown
    + epoch bump, all four exit-pending verdicts.

- **6401d** (`6f052a763` ✅) INTAKE NO-PAIR FALLBACK + async cold-start.
- **6401c** (`4ca7c6bcf` ✅) ANR-safe report share +
  `SellIntentQuantityAuthority6401`.
- **6401b** (`ade11f566` ✅) compile fix.
- **6401a §4** (`2c1e66d9f` ✅) STARTUP EXIT-ONLY LATCH wired.
- **6401 P1** (`755075b2d` ✅) FDG parity terminal wire.
- **6400a** (`158c364fb` ✅) HARD SCORE FLOOR ERADICATED.
- **6399** (`9ce60f077` ✅) ENTRY AUTHORITY ORDERING + SPLIT-BRAIN.
- **6398a** (`1576f1153` ✅) CANONICAL FLUID ENTRY AUTHORITY REPAIR.
- **6397a/b** (`61d1d2c85` ✅) ADAPTIVE FLOOR BRAIN.
- **6396** (`b43f74167` ✅) LIVE SCORE-SCALE REALIGNMENT.
- **6395a** (`9fc222307` ✅) EXECUTABLE RUNNER + POSITION IDENTITY.
- **6394c** (`b3efddd20` ✅) EarlyLaunchBypass wired into FDG.

## Snapshot expectations for the V5.0.6402b APK

1. `Universal SL start/done` reads N/N (never N/N-1). Any
   orphaned lease is auto-reaped at 10s with
   `UNIVERSAL_SL_STALE_LEASE_RESET_6402`.
2. Birdeye 401 fires once → circuit opens →
   `PROVIDER_CIRCUIT_OPENED_BIRDEYE_AUTH_TERMINAL_6402` in the
   forensic log; every subsequent request short-circuits with
   `PROVIDER_CIRCUIT_SKIP_BIRDEYE_AUTH_TERMINAL_6402`.
3. Helius 429 activates one shared backoff (base 5s → cap 60s)
   instead of per-mint retries;
   `PROVIDER_CIRCUIT_RATE_LIMITED_HELIUS_6402` counter reflects
   real 429 events.
4. `EXEC_OPEN_SAME_MINT_ALREADY_OPEN_COOLDOWN_6371` lifecycle row
   count drops sharply; excess dedup is captured by
   `SAME_MINT_CANDIDATE_SUPPRESSED_6402`.
5. `SAME_MINT_EPOCH_BUMPED_6402` fires each time a position
   closes, admitting the next candidate.

## Next backlog (from V5.0.6402 directive)

### 🔴 P0 remaining directive sections
- **§A wire** — instrument every top-level bot-loop stage with
  `BotLoopStageTiming6402.time(cid, Stage.X) { … }`. Substrate is
  ready; the wire pass covers scannerDrain, intakeNormalization,
  canonicalMintDedupe, laneEvaluation, v3Evaluation,
  finalDecisionGate, executionDrain, exitSweep,
  universalStopLoss, positionReconciliation, journalFlush,
  telemetrySnapshot.
- **§B PriceSnapshotRepository** — continuous background refresh
  publishing immutable price marks so the bot loop reads
  synchronously without awaiting provider I/O.
- **§C parallel Universal SL** — copy positions under a short
  lock then evaluate 6-8 in parallel with per-position 250ms
  deadline; whole-sweep hard deadline 5000ms.
- **§E CanonicalOpenPositionRegistry** — one source of truth for
  localOpen / paperRunners / slotHealth.open / per-lane counts.
  Directive requires 43/10/4 to reconcile or be scope-labelled.
- **§F Position Reconciliation** — GHOST_OPEN, CLOSED_SLOT_STILL_HELD,
  EXIT_PENDING_WITHOUT_INTENT, INTENT_WITHOUT_POSITION,
  PARTIAL_REMAINDER_MISMATCH, DUPLICATE_OPEN_SAME_POSITION_ID,
  DUPLICATE_MINT_DISTINCT_VALID_POSITION,
  PAPER_POSITION_WITHOUT_OPEN_FINALITY,
  OPEN_POSITION_WITH_CLOSE_FINALITY classifications.
- **§G ExitPendingOrphanGuard wire** — substrate is landed; wire
  it at every exitPending read site.
- **§I Scanner backpressure** — bounded conflated mint queue
  keyed by mint; max 25 unique/cycle, callback max synchronous
  work 5ms.
- **§J Token meta cache** — normalise keys, stale-while-revalidate,
  target hit rate > 80%.
- **§K Dispatcher** — move CryptoAltTrader.loadFromSharedPrefs,
  PatternClassifier.load, large report construction off Main.
- **§L Doctor** — classify Birdeye 401 AUTH_TERMINAL,
  Helius 429 RATE_LIMITED, DexScreener 5xx DEGRADED.

### 🟠 P1 verification
- **Live Session Validation** on V5.0.6402b APK against
  acceptance tests N.1–N.22.




# (Legacy) AATE PRD — V5.0.6393

## Session shipping stack (6388 → 6399, all CI GREEN)

- **6399** (`9ce60f077` ✅ Build) ENTRY AUTHORITY ORDERING + SPLIT-BRAIN
  REMOVAL. Root-cause repair for 6398 signature (blocked/shadow
  candidates still reaching MEME_LIVE_EXEC_ENTRY / EXEC_TICKET_CREATED
  alongside BUY_FAILED + ENTRY_REJECTED_SCORE_FLOOR).
  * RouteMode6399 (LIVE / SHADOW_READ_ONLY / DEFERRED / BLOCKED)
    resolved BEFORE ticket creation.
  * FdgTerminalOutcome6399 — 6 canonical terminals; exactly one per evalId.
  * AuthorityInvariants6399 — hard-check assertAllowLiveBeforeTicket
    (outcome=ALLOW_LIVE && score>=floor && route=LIVE && !denylist &&
    !shadow). Violations throw + emit AUTHORITY_INVARIANT_FAILURE_6399
    / SHADOW_ENTERED_LIVE_PATH_6399 / DENYLISTED_ENTERED_LIVE_PATH_6399.
  * CounterParityLedger6399 — funnel counters DERIVED from canonical
    terminals; parity guards enforce live_tickets==FDG_ALLOW_LIVE,
    exec<=tickets, BUY_ATTEMPT<=exec, BUY_FAILED<=BUY_ATTEMPT.
  * RuntimeDoctor6399 — authority-aware diagnosis; SPLIT_BRAIN /
    TICKET_BEFORE_ALLOW / PARITY / POLICY_MISFILE / LANE_DISABLED /
    RECONCILER_NOT_RUNNING all OVERRIDE HEALTHY.
  * CanonicalEntryPipeline6398.issueAndRegister now enforces the
    invariants before minting; shadow / denylisted / non-live-route
    callers return null. Every live ticket increments the parity
    ledger so drift is auditable in real time.
  * BotService.processTokenCycle calls
    ScannerHeatPublisher6398.onHydratedCandidate at cycle entry —
    fluid floor now sees real-time market temperature.
  * Bundle6399EntryAuthorityOrderingTest covers all 12 regression
    tests A..L + parity fail-loud + healthy diagnosis.

- **6398a** (`1576f1153` ✅) CANONICAL FLUID ENTRY AUTHORITY REPAIR —
  immutable envelopes, pipeline, gate-block cache, pair hydration
  states, scanner-heat + cognitive-advisory + lane-performance
  publishers.
- **6397a/b** (`61d1d2c85` ✅) ADAPTIVE FLOOR BRAIN — fluid [12, 22].
- **6396** (`b43f74167` ✅) LIVE SCORE-SCALE REALIGNMENT (55/56 → 15/17/20).
- **6395a** (`9fc222307` ✅) EXECUTABLE RUNNER + POSITION IDENTITY REPAIR.
- **6394c** (`b3efddd20` ✅) EarlyLaunchBypass wired into FDG.
- **6394b** (`358316d9a` ✅) 25%-divergence quarantine test fix.

## Next backlog

- **P1 LiveEntrySafetyHold_6312 migration** — remove residual score-
  floor logic and switch to
  CanonicalEntryPipeline6398.validateTicket() only. The substrate is
  fully in place; the callsite refactor lands next.
- **P1 Buy-lease acquisition migration** — same treatment: ticket-in,
  ticket-validate-out, no independent score/floor recompute.
- **P1 FDG terminal-outcome emission** — wire real FDG to
  CounterParityLedger6399.recordTerminal so parity is auditable
  end-to-end.
- **P1 Journal correction pass** — historical LIVE_BUY_FAIL_TELEMETRY
  rows reclassified as ENTRY_REJECTED_SCORE_FLOOR (write-only, never
  mutate original evidence).
- **P2 ANR Killer**, **P2 Fill Ledger LiveExecutor extension**,
  **P3 SOL Perps/Leverage (Phase 1)**.

# (Legacy) AATE PRD — V5.0.6393

## Session shipping stack (6388 → 6398a, all CI GREEN)

- **6398a** (`1576f1153` ✅ Build) CANONICAL FLUID ENTRY AUTHORITY REPAIR.
  Single live-entry pipeline (SCANNER → INTAKE → HYDRATION →
  LANE_EVAL → SCORE_ENVELOPE → FLOOR_ENVELOPE → FDG → TICKET →
  LEASE → EXECUTOR). Every stage carries the SAME evaluationId.
  Legacy 55/56 mechanically unreachable at every layer.
  * `EntryAuthorityModels6398` — 4 immutable data classes
    (EntryScoreEnvelope, DynamicFloorEnvelope, EntryAuthorityDecision,
    EntryAuthorityTicket) + enums (TraderLane, TraderId, EntryTactic,
    LifecycleStage, DiscoverySource, EntryOutcome).
  * `CanonicalEntryPipeline6398` — buildScoreEnvelope,
    buildFloorEnvelope, decide, mintTicket, issueAndRegister,
    validateTicket. Bayesian shrinkage caps: <8=±2, 8..24=±5, >=25=±8.
  * `EntryGateBlockCache6398` — fingerprint dedupe
    (mint+lane+trader+tactic+modelVers+roundedFloor+dataVersion).
    Reeval TTL 30s or +2pt material score change. Duplicates emit
    only ENTRY_GATE_BLOCK_DUPLICATE_SUPPRESSED — never BUY_FAILED
    and never an executor call.
  * `PairHydrationState6398` — replaces NO_PAIR_NO_FALLBACK with
    PAIR_CONFIRMED / PAIR_SOURCE_NATIVE / ROUTE_CONFIRMED_WITHOUT_PAIR
    / PAIR_PENDING_HYDRATION / PAIR_HARD_UNAVAILABLE. Preferred order:
    Helius > DexScreener > Birdeye > PumpFun > Raydium scanner
    pool > watchlist > Jupiter route. DexScreener degradation never
    erases source-native pairs.
  * `ScannerHeatPublisher6398` — rolling 30s hydrated candidates/sec
    → 0..1 percentile → AdaptiveFloorBrain6397.postScannerHeat.
  * `CognitiveAdvisoryBridge6398` — postSuperAgi/postSsi/postLlm
    accept conviction in [-1, +1] → signed advisory delta (±3 per
    channel, combined cap ±5).
  * `LanePerformancePublisher6398` — ingest(rowId, lane, wasWin,
    pnlSol) guards through CanonicalPerformanceFilter6395 (rejects
    all 6395 quarantine reasons) → AdaptiveFloorBrain6397.postLaneStat.
  Tests: Bundle6398 covers A..J regression suite + all 3 wire-ups
  + legacy-anchor unreachability + Bayesian shrinkage caps.

- **6397a/b** (`61d1d2c85` ✅) ADAPTIVE FLOOR BRAIN — [12, 22] envelope
  is fluid inside itself. Scanner heat / dist p50 nudge / lane
  learning / advisory stack.
- **6396** (`b43f74167` ✅) LIVE SCORE-SCALE REALIGNMENT (55/56 → 15/17/20).
- **6395a** (`9fc222307` ✅) EXECUTABLE RUNNER + POSITION IDENTITY REPAIR.
- **6394c** (`b3efddd20` ✅) EarlyLaunchBypass wired into FDG.
- **6394b** (`358316d9a` ✅) 25%-divergence quarantine test fix.

## Next backlog

- **P1 Executor migration** — remove residual score/floor calculations
  inside LiveEntrySafetyHold_6312 and the buy-lease path; make
  them ticket-validation only (CanonicalEntryPipeline6398.validateTicket).
- **P1 Scanner intake hook** — call ScannerHeatPublisher6398.onHydratedCandidate
  from Scanner intake.
- **P1 Journal correction pass** — historical LIVE_BUY_FAIL_TELEMETRY rows
  with reason=SCORE_BELOW_LIVE_FLOOR reclassified as
  ENTRY_REJECTED_SCORE_FLOOR via EntryRejectionTelemetry6396.
  recordForensicCorrection.
- **P2 ANR Killer** — TradeHistoryStore + MainActivity.onCreate → IO.
- **P2 Fill Ledger** — extend BuyFillLedger6388 / SellFillLedger6388
  persistence to LiveExecutor paths (V3JournalRecorder already covered).
- **P3 SOL Perps/Leverage (Phase 1)** — gated on live WR + 2..5× daily.

# (Legacy) AATE PRD — V5.0.6393

## Session shipping stack (6388 → 6393, all CI GREEN)

- **6393** (`d49a61d20` ✅) FORENSIC FINALITY + TRADE-1 TUNER + WEEKLY GROWTH.
  Trade1AdaptiveTuner6393 (real n=1 shaping), CanonicalFill6393 (BigDecimal
  round-trip), PositionStateMachine6393 (exactly-once CLOSED_SETTLED),
  WalletBalanceProof6393 (HELD/ZERO/UNKNOWN + zero-close policy),
  ManagedVsRecoveredCounters6393 (21/21 regression), ExecutionTelemetrySemantics6393
  (SCORE_FLOOR ≠ BUY_FAIL), AsymmetricExitStructure6393, WeeklyGrowthMode6393,
  PositionSizing6393, GovernorEpoch6393 (STRATEGY_EPOCH_6393).
- **6392** (`a8ab1a76` ✅) LIVE CONTINUITY: CONTINUE_CLEAN_TRADING + PER_MINT_QUARANTINE.
  CanonicalWalletPosition6392 (one per wallet+mint), MintDecimalsAuthority6392,
  SafeSellCalculator6392, ExitMutex6392, BroadcastLiability6392, CanonicalWalletParity6392,
  VerifiedBluechipIdentity6392, ExternalRugClassification6392, EntrySizingProgression6392.
- **6391** (`028a46b0` ✅) UNBLOCK sell-only hold. OwnershipClassification6391 (6-class),
  SellOnlyHold6391 (release-first), EffectiveLiveAuthority6391, ExecutionCircuitBreakers6391
  (8-namespace), ExitRoutePlan6391, PumpRescueUnifiedBuilder6391.
- **6390** (`eaf4d492` ✅) EARLY-ENTRY + PEAK CAPTURE. EarlyEntryScout6390 (CHEEMS-class
  46.5K MC detector), PeakAdaptiveTrail6390, PeakSlipExit6390, WinnerLadderExit6390
  (25/25/25/25), PeakCaptureAuthority6390.
- **6389** (`104743a7` ✅) CANONICAL SETTLEMENT + COHORT + ANR HARDENING.
  CanonicalCloseFinality6389 (3 legal sources + unique key), AuthoritativePnl6389,
  HardSettlementInvariants6389 (8-clause quarantine), KnownMintHistoricalRepair6389
  (BELKA/MICHI/Cygnets), JournalCohort6389, CanonicalUnitTypes6389,
  PreExecPolicyRedirectTaxonomy6389, UnknownBroadcastPolling6389,
  MainThreadHardening6389, SellOnlyForensicHold6389 (default null).
- **6388** (`af4595a1` + `104743a7` ✅) GOVERNOR RECOVERY STATE MACHINE.
  BLOCKED_INFRASTRUCTURE → HOLD_PROBATION → SOFT_TIGHT → BASELINE → EXPANSION.
  Auto promotion/demotion driven by PostFixEvidenceCollector6388.

## Wire-sites live in production paths

- `LaneEntryContract6342`: ALLOW_LIVE_PROBATION verdict; PolicyBlockDedup6388 on HOLD.
- `Executor.kt`: SellOnlyHold6391 gate; probation size clamp; PRE_EXEC_POLICY_REDIRECT
  taxonomy replaces LIVE_LANE_CONTRACT_6383 BUY_FAIL inflation.
- `sell/SellReconciler.tickOnce()`: GovernorRecovery6388.onReconcilerTick per pass.
- `V3JournalRecorder.recordClose()`: PostFixEvidenceCollector6388 + auto
  promote/demote inline on every canonical close.
- `TradeHistoryStore.clearAllTrades()`: JournalCohort6389.beginNewCohort().

## Next backlog (post-CI validation)

- **Live Session Validation**: Run V5.0.6393 APK; confirm Trade1AdaptiveTuner picks up
  first clean canonical close (Pigeooon-class) and tuner leaves neutral n=0 state.
- **Fill Ledger Wire-Up**: Persist BuyFillLedger6388 + SellFillLedger6388 records at
  every finalized fill on the live BUY / SELL executor paths.
- **Peak Capture Wire-Up**: Have every open position poll PeakCaptureAuthority6390.decide
  each hot-exit tick so the CHEEMS 26x scenario banks 75% via ladder + trailing 25%.
- **ANR Killer**: Move TradeHistoryStore + MainActivity.onCreate DB reads to
  Dispatchers.IO to kill the 50-second UI freeze.

# (Legacy) AATE PRD — V5.0.6388

## CURRENT MAJOR PROGRAM

**LIVE EXECUTION TRUTH AND COMPOUNDING FOUNDATION** — 13-section repair directive shipped in Bundles 6385 + 6386 + 6387 + 6388. Full text preserved in the operator message log; core acceptance criterion:
```
SUM(finalized sell lamport deltas) − SUM(finalized buy lamport deltas) == observed wallet change adjusted for deposits, withdrawals, fees.
```

## Current build stack

- **6388** (`104743a7b` ✅ CI green) **GOVERNOR RECOVERY STATE MACHINE + FULL 27-SECTION DIRECTIVE**
  - Substrate: `GovernorRecovery6388` (state machine) + `GovernorRecoverySubstrate6388.kt` (12 modules covering S12-S26).
  - States: BLOCKED_INFRASTRUCTURE → EXIT_ONLY → HOLD_PROBATION → SOFT_TIGHT → BASELINE → EXPANSION.
  - Auto promotion driven by `PostFixEvidenceCollector6388` (5/10/20 rolling windows, evidence-epoch=6388).
  - Auto demotion on infra faults (SellReconciler stopped/stale) or performance floor breach.
  - **S5** ProbationEntryLimiter6388: 1 open, 3/hr, 180s spacing, size clamp 0.005–0.010 SOL / 10% normal.
  - **S13** PolicyBlockDedup6388: governor HOLD emits `LIVE_ENTRY_POLICY_BLOCKED_6388` with 60s TTL — no more BUY_FAIL inflation.
  - **S14** ReconcilerLivenessAuthority6388: mandatory `isStarted && totalTicks>0 && tickAge<=configured`.
  - **S15** WalletPositionAuthority6388: 6-way (CANONICAL_OPEN, RESTORED_KNOWN_BASIS, RESTORED_UNKNOWN_BASIS, EXTERNAL, DUST, QUARANTINED) with balance-equation invariant.
  - **S16/17** BuyFillLedger6388 + SellFillLedger6388: immutable records keyed by signature, dedup rejected.
  - **S18** CanonicalTradeAggregator6388: many partial fills → one canonical lifecycle.
  - **S19** PartialExitStateMachineFull6388: 14-state ladder + 30s partial cooldown.
  - **S20** HardStopFullExit6388: HARD_STOP / RUG_CONFIRMED / etc → 100% liquidation (ROUTE_CHUNKED_FULL_EXIT preserves single exitIntentId).
  - **S21** SellLeaseIntegrity6388: one lease per (gen, positionId, exitIntentId); overlap rejected; premature release rejected.
  - **S22** ForensicExportMode6388: PRIMARY vs FALLBACK; fallback cannot pass forensic regression guard.
  - **S23** JournalEpoch6388: archive-not-delete; new epoch references previous checksum.
  - **S24** RecoveryHealthSnapshot6388: single dump authority (entry-authority, governor, probation, reconciler, position-authority, journal, export).
  - Wire-sites: LaneEntryContract6342 (ALLOW_LIVE_PROBATION verdict); Executor (probation size clamp inside sol-resolution + no-BUY_FAIL policy-block path); SellReconciler.tickOnce (onReconcilerTick every pass); V3JournalRecorder.recordClose (evidence collector + evaluatePromotion/Demotion inline).
  - Tests: Bundle6388GovernorRecoveryTest.kt (30+ invariants, every Section 25 named test + end-to-end HOLD→BASELINE→EXIT_ONLY→SOFT_TIGHT acceptance from Section 26).

- **6387b** (`3573a0490` ✅) **LIVE TRUTH, HOT EXIT AND NET-EDGE AUTHORITY (P0-P12 directive)** — LiveExitOnlyMode6387, ReconcilerZeroProof6387, PriceIntegrityAuthority6387, HotExitSupervisorContract6387, FeeAwareExecution6387, PartialExitStateMachine6387.
- **6387** (`2b8daf0a3` ✅) Canonical Trade Ledger + Price Identity.
- **6386b** (`e7a2809c0` ✅) Migrate legacy write/read sites onto truth substrate.
- **6386** (`ae60e6598` ✅) TRUTH REPAIR Bundle 2/2 (Sections 2/4/5/6/7/8/9/10/12/13).
- **6385** (`7e3a2b7b8` ✅) LIVE ACCOUNTING TRUTH REPAIR (Bundle 1/2).
- **6384** (`3b5ddbd6e` ✅) Governor "profitable low-WR" escape hatch.
- **6383** (`3f917e217` ✅) Live volume recovery + FLAT_EXIT protection.

## What operator will see on V5.0.6388 build

- `LIVE_LANE_CONTRACT_6383` counter DROPS SHARPLY — governor-HOLD rejections no longer inflate BUY_FAIL taxonomy.
- `LIVE_ENTRY_POLICY_BLOCKED_6388` NEW counter — policy blocks routed via dedup channel.
- `LANE_ENTRY_CONTRACT_ALLOW_PROBATION_6388` — first probation entries authorised while governor is HOLD.
- `LIVE_BUY_PROBATION_AUTHORIZED_6388` + `LIVE_BUY_PROBATION_SIZE_CLAMPED_6388` — probation trades executing at 0.005-0.010 SOL.
- `GOVERNOR_AUTO_PROMOTED_6388_HOLD_PROBATION_TO_SOFT_TIGHT` when 5 clean canonical closes prove healthy trading.
- `GOVERNOR_AUTO_PROMOTED_6388_SOFT_TIGHT_TO_BASELINE` when 10 clean closes maintain PF≥1.20 and expectancy>0.
- On reconciler stall: `GOVERNOR_AUTO_DEMOTED_6388_BASELINE_TO_EXIT_ONLY` — automatic capital protection.

## Next steps

### 🟠 P0 — Awaits live-run validation
- Live-run the V5.0.6388 APK. Verify the HOLD deadlock breaks and probation lifecycles begin.
- Confirm auto-promotion HOLD_PROBATION → SOFT_TIGHT → BASELINE within a live session.

### 🔵 P1 — Truth-substrate adoption (multi-bundle work behind the CI shield)
- Complete Executor's live BUY path to write `BuyFillLedger6388` records at each finalized fill.
- Complete Executor's live SELL path to write `SellFillLedger6388` records + call `CanonicalTradeAggregator6388.aggregate` at position close.
- Move `TradeHistoryStore.clearAllTrades/getTotalTradeCount` + `MainActivity.onCreate` DB reads to `Dispatchers.IO` (kills 50s ANR).
- Promote `CanaryReleaseGate6386` from LOCKED → CANARY once operator validates.

### 🟢 P2 — After 100 clean finalized trades
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`).
- Phase 2: Neural bridge (perps ↔ stocks cross-learning).
- Phase 3: LLM Lab sandbox.


  - **Section 2** ExecutionIntent6386: immutable intent + atomic registry (one outstanding BUY/mint/side; one mint cannot create competing lane tickets).
  - **Section 4** AmountTypes6386: `Lamports`/`RawTokenAmount` value classes on BigInteger; `MintDecimals` sealed (Known/Unknown) — never coerced to zero; `SolAmount`/`UiTokenAmount`/`UsdAmount`/`UsdPerToken`/`SolPerToken`.
  - **Section 5** FinalizedBuyProof6386.validate — quantity = post − pre (never ATA total).
  - **Section 6** FillLotLedger6386 — immutable lots keyed by (wallet, mint, buySig); re-entry = new lot; FIFO consumption.
  - **Section 7** FinalizedSellProof6386.validate — sold = pre − post; proceeds = lamport delta (no Jupiter/mark).
  - **Section 8** classifyPartial — no finalized proof ⇒ PendingReconciliation ⇒ no truth contribution.
  - **Section 9** ProofState6386 sealed with 8 states. `contributesToTruth()` true ONLY for FinalizedProofComplete.
  - **Section 10** HistoricalQuarantine6386.runOnce wired into BotService.onCreate (12 corruption criteria; emits _ROW_TAGGED / _TOTAL_ROWS / _REASON counters).
  - **Section 12** Bundle6386TruthRepairTest.kt — 21 invariants incl. decimals property tests (0/1/5/6/8/9) and 9 fixture assertions from the directive.
  - **Section 13** CanaryReleaseGate6386: LOCKED→CANARY→PROBATION→FULL, size 0.003–0.005 SOL, 20 clean RT to advance, invariant-failure reset. `promoteToCanary()` internal — awaiting operator green-light.

- **6385** (`7e3a2b7b8` ✅ CI green) **TRUTH REPAIR Bundle 1/2 — Sections 1, 3, 11**
  - **Section 1** LiveAccountingRepairMode6385 — hard-blocks all new live BUY signatures (LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385). Paper, shadow, monitoring, exits unaffected.
  - **Section 3** QUALITY_REJECTS_MINT_ROUTE_6342 removed; MINT_ROUTE now advisory (`LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385`).
  - **Section 11** EXEC_PROVIDER_TRY only fires for adapterWired && supported providers; unwired emit EXEC_PROVIDER_SKIPPED_6385.

- **6384** (`3b5ddbd6e` ✅) Governor "profitable low-WR" escape hatch (deprecated with substrate replacement in 6386).
- **6383** (`a4c2a3229` ✅) Live volume recovery (LIVE_MODE_DESYNC 795→0) + FLAT_EXIT winner protection.
- **6382** (`20c7fb7ec` ✅) Win-rate integrity + Wave Entry Quality Gate.
- **6381** (`556d3fa29…bfca89e0e` ✅) LIVE TRADER UNBLOCK — LIVE_MODE_DESYNC auto-promote.

## What operator will see on the V5.0.6386 build

- `LIVE_BUY_BLOCKED_ACCOUNTING_REPAIR_MODE_6385` — every live BUY attempt hard-blocked.
- `HISTORICAL_QUARANTINE_6386_TOTAL_ROWS_<N>` — count of legacy live rows tagged as excluded from truth stats on first boot.
- `HISTORICAL_QUARANTINE_6386_REASON_*` — per-criterion breakdown (LIVE_BROADCAST_WITHOUT_FINALIZATION, MISSING_TX_SIGNATURE, PNL_MISMATCH_GT_2PCT, POST_ATA_TOTAL_USED_AS_BUY_QUANTITY, RECOVERED_WALLET_UNKNOWN_BASIS, ALIAS_MERGE_OR_PHANTOM_HEAL, PARTIAL_WITHOUT_FINALIZED_PROOF, PHANTOM_SCRATCH_SELL_NO_DELTA).
- `EXEC_PROVIDER_TRY` counter drops sharply (only real, wired attempts now).
- `EXEC_PROVIDER_SKIPPED_6385` appears (unwired provider audit event, non-inflating).
- `LANE_ENTRY_QUALITY_MINT_ROUTE_ADVISORY_6385` replaces the old REJECTED counter.
- CanaryReleaseGate6386.snapshot(): `mode=LOCKED …` — awaits operator promote.

## Next steps

### 🟠 P0 — Awaits operator green-light
- **Promote to canary**: On the next live-connected build, once the operator verifies the substrate (`Bundle6386TruthRepairTest.kt` passing + HISTORICAL_QUARANTINE counters look reasonable + no false quarantines), we can flip `CanaryReleaseGate6386.promoteToCanary()`. Then LiveAccountingRepairMode6385.disable() ONLY through the canary orchestrator after 20 clean round trips.

### 🔵 P1 — Truth-substrate adoption (multi-bundle work behind the CI shield)
- Migrate Executor's live BUY path to open a FillLot6386 keyed by (wallet, mint, buySig) instead of writing via CanonicalBuyFillRegistry.
- Migrate live SELL path to compute realised proceeds from `FinalizedSellProof6386.validate()` only.
- Migrate partials to `FinalizedSellProof6386.classifyPartial` — no more phantom +50% sanitization.
- Route `EXEC_PROVIDER_TRY` gauge to a startup-once dump instead of every stack call.
- Replace `Double`-backed cost basis / proceeds in TradeHistoryStore with `Lamports` fields (schema migration).

### 🟢 P2 — Only after 100 clean finalized trades
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`).
- Phase 2: Neural bridge (perps ↔ stocks cross-learning).
- Phase 3: LLM Lab sandbox.

- **6383** (`a4c2a3229` ✅ CI green) **Live Volume Recovery + Live-Winner Protection**.
  Stale paper/shadow flag auto-clear in `Executor.kt` (recovers 92% of dying buys), lane-contract counter split onto `LIVE_LANE_CONTRACT_6383`, MoonshotTraderAI FLAT_EXIT protection for live positions with `peakPnlPct≥3%` OR `holdMinutes<15`.

- **6382** (`20c7fb7ec` ✅ CI green) Win-Rate Integrity + Wave Entry Quality Gate.

- **6381** (`556d3fa29…bfca89e0e` ✅) LIVE TRADER UNBLOCK — LIVE_MODE_DESYNC auto-promote + Rugcheck tier recalibration.

- **6380** (`09906d856` ✅) Paper wallet continuity hole #2 + Learning Trajectory Governor.

- **6377-6379x** (✅) Forensic Reconciler + Learned Toxic Lane Hard Veto + cache-bucketing.

- **6376** (`94ab099ef` ✅) Paper Wallet Continuity + Screen-Off Proof-of-Life.

- **6372** (`0a1eb8cfc` ✅) UNIVERSAL 2×–5× daily compound target (AATE core doctrine).

## Expected V5.0.6384 signature

1. **`LIVE_BUY_REDIRECTED_GOVERNOR_HOLD_6342` drops sharply** when live PF≥2.0 and expectancy is positive (which is what the current live journal shows: PF=7.58, exp=+0.0024).
2. **`LIVE_CONFIDENCE_GOVERNOR_BASELINE` counter increases** — governor stays in baseline for profitable strategies.
3. **`LANE_ENTRY_CONTRACT_ALLOWED_6342` counter increases** — more live buys reach FDG allow.
4. **`FIRST_TRADE_READINESS_6348.GOVERNOR_NOT_HOLD` pillar flips ✓** (was `P1` in V5.0.6383 dump).

## Backlog

### 🟠 P0 — Deferred forensic-repair spec items
- P0-4: Immutable price identity hash + PRICE_IDENTITY_CONFLICT
- P0-5: tokenDecimals nullable + decimalsKnown boolean
- P0-6: performanceDomain enum (LIVE_CANONICAL / PAPER_PARITY / SHADOW_RESEARCH)
- P0-7: PAPER_PARITY score-floor parity with LIVE governor
- P0-8: LaneEligibilityContract
- P0-10: Quarantine corrupted history + rebuild tactic stats
- P0-11: Full forensic journal fields + CANONICAL_INTEGRITY_STATUS section
- **Phantom REALIZED_SCRATCH_AFTER_RISK_EXIT_SIGNAL at sol=0 cost>0**: same-mint alias collision fires SELL rows that never actually broadcast realized SOL. Distorts WR.
- **Empty UI panels**: ALL TRADERS, 30-DAY PROOF RUN, lane cards show 0.

### 🔵 P1 — After live WR stabilization + 2x–5x growth benchmark hit
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`)
- Phase 2: Neural bridge (AI cross-learning perps↔stocks)
- Phase 3: LLM Lab sandbox

- **6382** (`20c7fb7ec` ✅ CI green) Win-Rate Integrity + Wave Entry Quality Gate.
  1. `TradeHistoryStore.isValidAccountingTrade` whitelists `EXTERNAL_RUG_CLOSE` rows.
  2. `StartupReconciler` synth rugSell inherits `buyRow.tradingMode`.
  3. `TacticSwitcher.rederiveFromRawJournal6382()` cold-boot repair of phantom μ drift.
  4. `WaveEntryQualityGate6382` rejects parabolic top-tick / ejection / extended chases.

- **6381** (`556d3fa29…bfca89e0e` ✅) LIVE_MODE_DESYNC auto-promote + Rugcheck tier recalibration + Paper size floor fix + Golden Tape refresh.

- **6380** (`09906d856` ✅) Paper wallet continuity hole #2 + Learning Trajectory Governor.

- **6379x** (`5c9d19554…2f504c4f7` ✅) Learned Toxic Lane Hard Veto + cache-bucketing.

- **6377** (`1575a03f1` ✅) Forensic Reconciler (11-item correctness spec).

- **6376** (`94ab099ef` ✅) Paper Wallet Continuity + Screen-Off Proof-of-Life.

- **6374-6375** (✅) Scanner Fanout Throttle + Heatmap ANR fix.

- **6373x** (✅) Trade-1 catastrophic rotation + phantom pnl% recompute + ghost purge neutralization.

- **6372** (`0a1eb8cfc` ✅) UNIVERSAL 2×–5× daily compound target (AATE core doctrine).

## What operator should see on V5.0.6383

1. **`LIVE_MODE_DESYNC` counter drops dramatically** — the 795 count from V5.0.6382 was almost entirely stale-flag garbage. Now only real desync (`runtimePaper=true` requesting a LIVE buy) fires this counter.
2. **`LIVE_MODE_STALE_FLAG_AUTO_CLEARED_6383` counter appears** — this is the recovered volume. Expect it to be similar in magnitude to the prior `LIVE_MODE_DESYNC` number.
3. **`LIVE_LANE_CONTRACT_6383` counter appears** — routing hygiene (BLUECHIP rejecting Pump.fun mints, etc.), working as intended.
4. **`LIVE_WINNER_PROTECT_FLAT_EXIT_SUPPRESSED_6383` counter appears** — every time a live moonshot position was saved from a hair-trigger flat exit.
5. **Live BUY ok/fail ratio significantly improved** — no longer 31/862 (3.5%).
6. **Live SELLs no longer showing pnl=+0 sol=0 patterns** repeated on the same mint.

## Backlog

### 🟠 P0 — Deferred forensic-repair spec items
- P0-4: Immutable price identity hash + PRICE_IDENTITY_CONFLICT
- P0-5: tokenDecimals nullable + decimalsKnown boolean
- P0-6: performanceDomain enum (LIVE_CANONICAL / PAPER_PARITY / SHADOW_RESEARCH)
- P0-7: PAPER_PARITY score-floor parity with LIVE governor
- P0-8: LaneEligibilityContract
- P0-10: Quarantine corrupted history + rebuild tactic stats
- P0-11: Full forensic journal fields + CANONICAL_INTEGRITY_STATUS section
- **Empty UI panels**: ALL TRADERS, 30-DAY PROOF RUN, lane cards show 0.

### 🔵 P1 — After live WR stabilization + 2x–5x growth benchmark hit
- Phase 1: SOL Perps / Leverage mode (`PerpsLaneGate.kt`)
- Phase 2: Neural bridge (AI cross-learning perps↔stocks)
- Phase 3: LLM Lab sandbox

## Current build stack (old)

- **6377** (pending push) **Forensic Reconciler (11-item correctness spec)** — additive read-only module runs 11 named cross-domain reconciliation checks against the trade journal on every ~50th bot loop tick. Emits `FORENSIC_OK_6377|<CHECK>` / `FORENSIC_MISMATCH_6377|<CHECK>|<summary>` counters. Pipeline dump gains a dedicated FORENSIC RECONCILER section listing pass/fail + per-check summaries. TacticSwitcher exposes new read-only `dumpForensicSnapshot6377()` so persisted μ can be cross-checked against journal μ per lane.
  Checks: WALLET_VS_JOURNAL, JOURNAL_ROW_PARITY, BUY_SELL_QTY_SKEW, COST_BASIS, PNL_PCT_VS_SOL, SELL_REASON_PRESENCE, PRICE_IMMUTABILITY, TACTIC_MU_VS_JOURNAL, DUPLICATE_JOURNAL_ROWS, ORPHAN_SELL, CANONICAL_VS_REGISTRY.
  `Bundle6377ForensicReconcilerTest.kt` (14 assertions).

- **6376** (`94ab099ef` ✅) Paper Wallet Continuity + Screen-Off Proof-of-Life.

- **6375** (`e4bfc98f9` ✅) Shadow lane-read telemetry split from active fanout.
- **6374b** (`5e4dea126` ✅) Runtime test scenario reshaped to bypass healthy-window reset (agg-bad-band gate).
- **6374a** (`a3e7c850c` ✅) Test qualified `HeatmapRenderCache6374` package reference.
- **6374** (`22006c59c` ⚠ compile-fixed by 6374a) Scanner Fanout Throttle + Aggregate-Bad-Band Rotation + Heatmap ANR fix bundle.

- **6373g** (`406bd6809` ✅) Fix compile: TokenState.mcap field removal from log line.
- **6373f** (`b93130fc6` ✅) Hard-block PRESALE_SNIPE/RESALE_SNIPE/FRESH_LAUNCH at paperBuy source.
- **6373e** (`aa94bb744` ✅) Fix V5.0.6373d compile (Trade field name reconciliation).
- **6373d** (`0b2f7d5e0` ✅) Wide bundle A+B+C: phantom pnl% recompute + phantom-retry dedupe + Trade.pnlPct mutable.
- **6373c** (`71c768be4` ✅) **Ghost Paper Purge NEUTRALIZED — primary regression fix.** Operator's V5.0.6364 baseline showed everything working (display, volume, WR/EV). V5.0.6366 F3 ghost purge whitelisted only 5 V3 sub-traders (ShitCoin/Moonshot/BlueChip/Quality/CashGen) → paper buys under WHALE_FOLLOW / COPYTRADE / PRESALE_SNIPE / MICRO_CAP / TREASURY / CYCLIC / MOMENTUM_SWING / LAB got mis-classified as ghosts and got `ts.position = Position()` reset every reconcile tick. Ghost predicate replaced with positive-existence check against TradeHistoryStore latest-buy. V5.0.6372 universal 2×–5× daily wallet-growth compound target KEPT per operator ("thats aate policy!").

- **6373b** (`81788486d` ✅) Canonical Position Sentinel at paperSell entry (source-of-creation P0-1+P0-2+P0-3 minimum). Blocks phantom sells when `ts.position` disagrees with `TradeHistoryStore` latest-buy by >2× cost/qty or `pos.costSol < 0.05 && buy.entryCostSol >= 0.05`. Emits `SELL_BLOCKED_NO_CANONICAL_POSITION_6373` and returns `FAILED_RETRYABLE` without touching real position or journaling.
- **6373a** (`f93efe655` ✅) Compile fix for V5.0.6373's V3 pre-empt if/else structure.
- **6373** (`94a84c8b1` ✅) V3 execute route same-mint pre-empt + trade-1 catastrophic rotation (≥90%) + skew-taint learning quarantine + CryptoAlt content-diff render skip.
- **6372a** (`596d9054f` ✅) Fix stale 6371 Golden Tape order assertion
- **6372** (`0a1eb8cfc` ✅) UNIVERSAL 2×–5× daily compound target (AATE core doctrine — KEPT)
- **6371** (`af01b13c4` ✅) OpenGate same-mint cooldown + ghost-zero family unlock
- **6370b** (`48c730a68` ✅) Golden Tape 6369 accepts renamed PAPER_BUY_OPENED_6370 label
- **6370a** (`199080580` ✅) Fix paper alias guard telemetry compile
- **6370** (`a5cfae95d` ✅) Global same-mint paper open guard
- **6369** (`e0a485ccb` ✅) ExecutionAttemptLease race-claim on paperBuy
- **6368a** (`dde34de6d` ✅) Fix Bundle6368 test imports
- **6368** (`7cd19c16f` ✅) Magnitude Downstream + ForensicLogger locale-free/zero-liq quarantine + 20s report watchdog
- **6367a** (`825f9d460` ✅) Restrict magnitude trigger to initial MOMENTUM only (later overridden by V5.0.6373 trade-1 catastrophic path)
- **6367** (`da24c9694` ✅) Self-learning from trade 1
- **6366c** (`84adffdf5` ✅) Golden-tape refresh for 6366 F4
- **6366b** (`f721b8113` ✅) STALE_FLAT_CULL_6366
- **6366** (`d17e05e55` ⚠️ *F3 neutralized in 6373c*) Worker-timeout raise + ghost paper purge (F3 replaced) + learning-ceiling raise
- **6365** (`362ecd6a4` ✅) REVERT V5.0.6361 canonical learning shim
- **6364** (`fd8331cf0` ✅) Operator's known-good baseline before regression

## V5.0.6364 → V5.0.6373c regression audit

| Version | Change | Verdict at V5.0.6373c |
|---|---|---|
| **6366 F3** | **Ghost paper purge whitelist** | **NEUTRALIZED in 6373c — root cause of held-tokens-invisible + phantom sells** |
| 6366 F1a | Worker timeout 9s → 15s | KEEP — external-API tolerance |
| 6366 F4 | Learning-eligibility ceiling → 20 SOL | KEEP — learning-only ceiling |
| 6366b F5 | STALE_FLAT_CULL_6366 | KEEP — real exit path, not corruption |
| 6367 | Trade-1 magnitude + LanePolicy early demote | KEEP |
| 6367a | Restricted magnitude to MOMENTUM only | KEEP but superseded by 6373 trade-1 catastrophic (any tactic ≥90%) |
| 6368 | Locale-free helpers + zero-liq quarantine + magnitude downstream | KEEP |
| 6369 | ExecutionAttemptLease race-claim | KEEP |
| 6370 | GLOBAL SAME_MINT paper open guard | KEEP |
| 6371 | OpenGate same-mint cooldown | KEEP |
| **6372** | **UNIVERSAL 2×–5× compound target** | **KEEP — AATE core doctrine, not a regression** |
| 6373 | V3 pre-empt + trade-1 catastrophic + skew-taint + content-diff skip | KEEP (my push) |
| 6373b | Canonical Position Sentinel at paperSell | KEEP (my push) |
| 6373c | Ghost purge positive-existence predicate | KEEP (my push) |

## What operator should see after V5.0.6373c lands

1. **Held tokens visible again**: paper positions under WHALE_FOLLOW / COPYTRADE / PRESALE_SNIPE / MICRO_CAP / TREASURY / CYCLIC / MOMENTUM_SWING / LAB no longer vanish between reconcile ticks.
2. **Phantom sells STOP**: any sell path that would read a default `ts.position` gets blocked by V5.0.6373b canonical sentinel; the ghost-purge that CREATED those defaults is neutralized in 6373c.
3. **Volume returns**: EmergentGuardrails position registry stays populated, so `V3_EXEC_SAME_MINT_PREEMPT_6373` fires only for genuine duplicates instead of ghost-purged holes.
4. **Universal 2×–5× compound target intact**: sizing still pushes all lanes to hit the daily growth doctrine.

## Backlog

### 🟠 P0 — Deferred forensic-repair spec items
- P0-4: Immutable price identity hash + PRICE_IDENTITY_CONFLICT
- P0-5: tokenDecimals nullable + decimalsKnown boolean
- P0-6: performanceDomain enum (LIVE_CANONICAL / PAPER_PARITY / SHADOW_RESEARCH)
- P0-7: PAPER_PARITY score-floor parity with LIVE governor
- P0-8: LaneEligibilityContract
- P0-10: Quarantine corrupted history + rebuild tactic stats
- P0-11: Full forensic journal fields + CANONICAL_INTEGRITY_STATUS section
- **Empty UI panels**: ALL TRADERS, 30-DAY PROOF RUN, lane cards show 0 when top card shows 181 trades. Investigate `journalParityStatsSnapshot6085()` null bailout at MainActivity:2765.

### 🔵 P1 — After stabilization
- Phase 1: SOL Perps/Leverage mode → `PerpsLaneGate.kt`
- Move chart rendering + trade-list diffing off main thread

### 🟣 P2/P3
- Phase 2: Neural bridge (perps↔stocks cross-learning)
- Phase 3: LLM Lab sandbox


## V5.0.6360 → V5.0.6365 regression hunt

| Version | Change | Verdict at V5.0.6365 |
|---|---|---|
| 6361 (executor) | Paper full-exit qty preservation | KEEP — legit fix at correct layer |
| **6361 (V3 recorder)** | **Canonical contract shim wrap** | **REVERTED — root cause of WR/wallet regression** |
| 6362 | Locale-free formatter | KEEP — cures ANR |
| 6362 | Supervisor throttle re-arm (worker-timeout path) | KEEP |
| 6362 | Supervisor throttle re-arm (cycle-time path) | REVERTED in V5.0.6364 (self-reinforcing loop) |
| 6363 | Scanner circuit breaker | KEEP |
| 6363 | Brain multiplier floor at 0.50 | KEEP — safety floor on dust-crush from small-sample tuples |
| 6363 | Quarantine log rate-limit | KEEP |
| 6363 | Throttle heartbeat | REMOVED in V5.0.6364 (behavior no longer applies) |
| 6364 | Probation zero-liq HELD | KEEP |
| 6364 | Cycle-time throttle-arm removal | KEEP |

## What operator should see after V5.0.6365 lands

1. **Learning aggregators receive every close again** — same behaviour as V5.0.6360. No `CANONICAL_LEARNING_AGGREGATOR_SKIPPED_6361` events (that label is gone).
2. **`TacticSwitcher / ColdStreakDamper / LanePolicy / RetrainingDecay` should quickly re-learn from the recent close waves** — expected effect: bleeding lanes get demoted, tactics rotate, sizing adjusts within the first ~50 closes post-deploy.
3. **Open-position count should shrink** as sells/exits process normally, learning kicks in, and the bot stops opening losers.

## Backlog

### 🟠 P0 — Still deferred
- Move locale-free formatting inside `ForensicLogger` (fix once, all callers safe).
- Verify sell dispatcher isolation.
- Enforce canonical eligibility at the CORRECT layer (Executor sell path / FillLotLedger6344, both have real qty).

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD.
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode (`PerpsLaneGate.kt`)

### 🟢 P3+ — Off-thread rendering, neural bridge, LLM Lab

## Testing / CI

V5.0.6344 → V5.0.6365 all ✅ SUCCESS on GH Actions. V5.0.6365 restores the V5.0.6360 learning fanout exactly.

- **6362** (`b52f383dc` ✅) Locale-free formatter (ANR cure) + Supervisor emergency throttle RE-ARM

- **6361** (`0749a6404` ✅) Paper full-exit qty preservation + CanonicalLearningContract end-to-end wire-up

## Full V5.0.6350 → V5.0.6364 stack

- **6350-6353** Paper close retry-loop drain, readiness fix, cycle evict, log aggregation
- **6354→6355** Scanner router wire-up (compile red → fix)
- **6356** LiveProbabilityEngine forensic rate-limit
- **6357** LaneBucketPivot whole-lane fallback removal (WR -20% root cause)
- **6358** StrategyTruthLedger TTL cache + rate-limit
- **6359** Foundation Policy live-wire
- **6360** Paper close force-RESET (not terminal)
- **6361** Paper full-exit qty preservation + Learning Contract E2E
- **6362** Locale-free formatter + Supervisor throttle RE-ARM
- **6363** Scanner circuit breaker + brain-mult floor + throttle observability
- **6364** SOURCE-OF-CREATION: probation zero-liq HELD + cycle-time arm removed

## V5.0.6363 → V5.0.6364 root causes fixed

| Symptom | Real source | Fix |
|---|---|---|
| Cycles 87s-171s, sells starving | PROBATION zero-liq churn: 1278 dead tokens force-promoted through intake every 5min | Widen noPairCold HELD guard to cover all dead-signal entries |
| 2099 emergency-throttle arms/96min → permanent clamp | Cycle-time arm was self-reinforcing (arm→clamp→starve exits→slower cycle→arm) | Remove cycle-time trigger; keep worker-timeout arm |
| ANR maxFrameGapMs=22245 back | Locale.clone from remaining ~700 String.format sites | (Deferred — extended locale-free migration is separate P0 workstream) |
| WR 80% → 32% | V5.0.6363 brain floor is band-aid; underlying is dust-crush from small-sample tuples | (Deferred — brain-floor kept for now, needs deeper audit) |

## Backlog (priority-ordered)

### 🔴 P0 — Deferred from V5.0.6364 (still needed but at the RIGHT layer)
- **Locale-free logging at the source** (not per-callsite): move formatting INSIDE `ForensicLogger.lifecycle/exec/etc` so all 700 sites are safe by default. One fix, not 700 migrations.
- **CanonicalLearningContract close-hook audit**: V5.0.6361 wired assess() into every close; verify it's on Dispatchers.IO not the main loop.
- **Sell-path dispatcher isolation**: verify sells actually run on their own dispatcher (V5.0.6362 comment claims this but the code path isn't visibly enforced).

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD.
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode (`PerpsLaneGate.kt`)

### 🟢 P3+ — Off-thread rendering, neural bridge, LLM Lab

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- CanonicalPnLAuthority6343 sole legal realized-SOL calculator
- FillLotLedger6344 sole legal cost-basis source
- ScannerHydrationQueues6347 router of record
- **V5.0.6364: processProbation HOLDS any zero-signal entry; never force-promotes dead tokens through intake**
- **V5.0.6364: supervisor emergency throttle arms ONLY on real worker timeouts, never on cycle time**
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

V5.0.6344 → V5.0.6364 all ✅ SUCCESS on GH Actions.

- **6323-6341** Foundation: canonical registry, WADDLE decimal repair, brain consensus, safety-hold demotion, loop stall fixes
- **6342** (`7a6e23639` ✅) **Lane Entry Contract** — governor HOLD veto + BLUECHIP/QUALITY identity
- **6343** (`dd4f2d0a2` ✅) **Canonical PnL Authority** — single source of realized-SOL truth
- **6344** (`1f85cb2f9` ✅) **Immutable FillLotLedger + strong unit types + Canonical PnL Conduit**
- **6345** (`45f6665bf` ✅) **Foundation Policy (PRE_ENTRY_DECISION_RECORD) + Executable-Price Stop Preflight**
- **6346** (`9ac9ec6bb` ✅) **Canonical Learning Contract** — P0-4
- **6347** (`4907b225c` ✅) **Scanner/Hydration Queue Separation** — P1-1
- **6348** (`61585afef` ✅) **FIRST-TRADE READINESS health block + priority ranking** — P1-2
- **6349** (`dfc1faa2a` ✅) **Golden-tape guard for 6344→6348**
- **6350** (`49daf75f9` ✅) **Paper close retry-loop drain**
- **6351** (`9dd292ecd` ✅) **FIRST_TRADE_READINESS false-alarm fix**
- **6352** (`7ac752277` ✅) **Loop-cycle emergency evict**
- **6353** (`d69ad98e5` ✅) **Rebalance held-block log aggregation**
- **6354** (`04dd83524` ❌) → **6355** (`7f7ea7ed3` ✅) Wire scanner emit + executor lane contract into router
- **6356** (`f9c3abdf8` ✅) **Rate-limit LiveProbabilityEngine ForensicLogger spam**
- **6357** (`4039664ba` ✅) **LaneBucketPivot: remove whole-lane fallback (WR -20% root cause)**
- **6358** (`46d5a037a` ✅) **StrategyTruthLedger TTL cache + rate-limit QUALITY_BOOST / DUST_STACK**
- **6359** (`cebc20c9d` ✅) **Foundation Policy live-wire (PreEntryDecisionRecord6345.emit on every LaneEntryContract pass)**
- **6360** (`029d677b4` ✅) **Paper close force-RESET (not terminal)**
- **6361** (`0749a6404` ✅) **Paper full-exit qty preservation + CanonicalLearningContract end-to-end**
- **6362** (`b52f383dc` ✅) **Locale-free formatter (ANR cure) + Supervisor emergency throttle RE-ARM**

## Emergency triage delivered from V5.0.6361 pipeline snapshot

| Symptom | Fix | Version |
|---|---|---|
| Max frame gap 25610ms (main-thread ANR stalls) | Locale-free integer-arithmetic formatter kills `Locale.clone` path | V5.0.6362 |
| ForensicLogger post-per-event took MessageQueue lock on main thread | Batched drain: one Runnable per ~128 events | V5.0.6362 |
| 300+ worker timeouts/10min while `SUPERVISOR_EMERGENCY_THROTTLE_OBSERVED_DISARMED` just logged | Emergency throttle re-armed: 5-min clamp window, cap→16 while active | V5.0.6362 |

## Backlog (priority-ordered)

### 🔴 P0 — Full locale-free migration (deferred from V5.0.6362)
- 702 remaining `String.format`/`"%.Nf".format(x)` sites across the engine. Requires golden-tape re-baseline (numeric strings identical but reached via new path). Do lane-by-lane with per-directory tests.

### 🟠 P1 — Hard-enforcement flip
- Turn `RealizedPnlConduit6344` from SHADOW to HARD (block sell path when authority quarantines).
- Turn `PreEntryDecisionRecord6345.Verdict.VETO` into a hard block on the buy ticket.

### 🟢 P2 — Phase 1 SOL Perps/Leverage mode
- Resume `PerpsLaneGate.kt` now that the pipeline is stable.

### 🟢 P3+ — Off-thread rendering (chart + trade-list diffing), neural bridge, LLM Lab

## Learning-loop invariants (all still true)

- V3JournalRecorder.recordClose feeds TacticSwitcher.onTradeClosed regardless of paper/live
- TacticSwitcher persists per-bucket state to LearningPersistence
- LaneEdgeConcentrator amplifies per-bucket (lane × scoreBand) by expectancy
- LaneEntryContract6342 hard-vetoes on governor HOLD + enforces lane identity
- CanonicalPnLAuthority6343 is the sole legal realized-SOL calculator
- FillLotLedger6344 is the sole legal cost-basis source (immutable, first-write-wins)
- CanonicalLearningContract6346 is the sole legal canonical-eligibility gate
- ScannerHydrationQueues6347 is the router of record; drained on cycle overrun via LoopCycleEmergencyEvict6352
- FirstTradeReadiness6348 has advisory-vs-hard pillar distinction so the tile does not lie
- PaperPositionCloseAuthority force-RESETS (not terminals) after 3 stuck retries via V5.0.6360
- **V5.0.6362: SupervisorEmergencyThrottle re-armed — cap actually clamps under sustained overload**
- **V5.0.6362: All main-thread decimal formatting on hot paths is locale-free (no `Locale.clone` stall)**
- Never blocks a trade for strategy bleed, never hard-disables a lane

## Testing / CI

All V5.0.6344 → V5.0.6362 ✅ SUCCESS on GH Actions.
V5.0.6362 required a one-commit follow-up (`6362a`) to restore a golden-tape literal after refactoring the tier logic into a pure helper.

