# AATE V5.7 — Product Requirements & Session Notes

## Original Problem Statement
Native Kotlin Android Solana paper trading bot ("AATE"). Build must go
through GitHub Actions CI (no local compiler). Two overarching operator
mandates:

1. **Source-Level Authority Convergence** — fix bugs at their source
   authority; never stack overlays or add new patch layers.
2. **Full-Stack Authority Consolidation** (Feb 2026 directive) — the AATE
   hive mind thinks together. Specialists contribute expertise. Learning
   changes real decisions. AATE may WAIT / REJECT / change strategy /
   exit when its thesis breaks. No legacy throughput patch may force a
   losing trade through.

## Session February 2026 — Shipped Fixes

**Prior slices (context):**
- V5.0.6768 – V5.0.6781 — journal parity, sizing/compound math on equity,
  exit turnover, sticky provider lock, APK-update hero fix.

**Authority-consolidation slices (this session):**
- **V5.0.6782** slice 1: Sealed cognitive decision + downstream
  resurrection removal — retired PROVEN_DEAD_PROBE canonical dust probe,
  zero-conf → REJECT (paper+live), LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW /
  STALE_CANDIDATE_SOFT_ALLOW / MISSING_FINAL_CANDIDATE_SOFT_ALLOW removed,
  forceAdaptiveRelaxation neutered.
- **V5.0.6783** slice 2: Symbolic universe block authoritative in ALL modes
  (removed paper-only advisory downgrade), stale safety = WAIT (removed
  FDG_SAFETY_STALE_SOFT_SHAPED_6341 0.3× continuation), COPY_TRADE /
  WHALE_FOLLOW lane-forced micro-probes retired.
- **V5.0.6784** slice 3: EarlyLaunchBypass6394/6396 below-floor bypass
  retired — scout tier no longer forces 0.30× micro-probe execution.
- **V5.0.6785** — golden-tape test alignment for 6782/6783 removals.
- **V5.0.6786** slice 4 + PER_TRADE_FEE_SEND:
    - BotService lane-eval zero-signal + weak-wait now emit WAIT
      (retired LANE_WAIT_OVERRIDE_ZERO_SIGNAL_DUST_PROBE_4164 and
      LANE_WAIT_OVERRIDE_DUST_PROBE PROBE_ONLY fallbacks).
    - Executor.sendFeeSplit + MarketsLiveExecutor.collectTradingFee now
      send each fee share DIRECTLY per trade to the two coded fee
      wallets (A8QPQr…kkpd + 82CAPB…hygA) via wallet.sendSol —
      no accumulator, no batching. FeeRetryQueue owns transient
      failures. Two-wallet 50/50 split preserved.
- **V5.0.6787** — 5 more brittle golden-tape tests aligned to 6786.
- **V5.0.6788** §CANONICAL_MARK_AUTHORITY P0 (Feb 2026 directive):
    - CANONICAL_MARK_SENTINEL_SHAPE_QUARANTINE_6728 now admits marks
      that carry canonical identity provenance (TOKEN_MAP-verified DEX/
      pump route). Round-shape fingerprint alone no longer starves
      execution when route is proven.

## Remaining V5.0.6787+ Directive Backlog (P0 / P1 / P2)

**P0**
- SINGLE_SEALED_ENTRY_AUTHORITY — one candidateVersion × mint × mode
  must have exactly one sealed owner / FDG outcome / executable intent.
  Remove any EXEC_INTENT_REUSED_6734 crossing owner/verdict boundaries.
  Target: FDG_ALLOW_WITHOUT_EXEC_INTENT = 0.
- OWNER_ATTRIBUTION — eliminate UNRESOLVED_OWNER_6741. Bind
  laneOwner + candidateVersion + sealedFdgId + intentId into
  CanonicalPosition at open commit; never infer owner at sell time.

**P1**
- LEARNED_BLEEDER_AUTHORITY — statistically decisive catastrophic cohorts
  (e.g., EXPRESS 0/29, PROJECT_SNIPER catastrophic score bands) must not
  retain normal capital authority; convert to shadow exploration until
  recovery criteria met.
- REMOVE_MIN_NOTIONAL_RESURRECTION — OK_MIN_PROMOTED_6600 must only
  promote benign rounding cases, never resurrect a deliberately
  suppressed 0.002× multiplier stack into 0.050 SOL exposure.
  Consolidate all size multipliers once, then clamp once.
- TTL_SINGLE_SOURCE — remove specialist hard-coded 30s TTL; every
  ticket/reservation must consume AdaptiveTicketTTL authority.

**P2**
- LEARNING_PURITY — do not train lane heads from unresolved-owner or
  economically invalid closes; attribute every reward to immutable entry
  provenance.
- Runner Compound Widget (dashboard).
- Perps Neural Bridge (perps↔stocks cross-learning).
- LLM Lab sandbox.

## Design Notes (Not Bugs)
- 100-position hard cap (ExitThroughputAuthority6727) is intentional.
  Sizing + exit turnover should keep pace so the cap is rarely hit.
- Paper trading is the production mode. Live wiring kept but paper is
  the operator's active target.

## Architecture
- Native Kotlin Android app, event-sourced.
- Canonical registries under `com.lifecyclebot.engine.truth.*`.
- Build/test via GitHub Actions CI only. No local compiler.
- Version bumped in BOTH `/app/AATE_VERSION` and
  `/app/lifecycle_apk/AATE_VERSION` on every commit.

## Test Credentials
See `/app/memory/test_credentials.md` (none used — bot is standalone,
no auth). GitHub PAT authenticated via `gh` CLI.
