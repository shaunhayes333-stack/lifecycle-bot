# AATE V5.7 — Product Requirements & Session Notes

## Original Problem Statement
Native Kotlin Android Solana paper trading bot ("AATE") — build must go through
GitHub Actions CI (no local compiler). Target: 2x-5x DAILY paper wallet growth
via "Source-Level Authority Convergence". Fix bugs at their source authority,
never stack overlays.

## Session February 2026 — Shipped Fixes

- **V5.0.6768** (TERMINAL_SELL_LEDGER_PARITY): JournalEconomicReplay6619 no
  longer rejects terminal SELLs with precision-drift residuals. Root cause of
  hero ACCOUNT UNAVAILABLE / ACCOUNTING ERROR: journal orphaned lots →
  reconciled=false → hero painted red on healthy account.
- **V5.0.6768b** (GLOBAL_RECONCILIATION_STATUS): CanonicalEconomicEvent6635
  status no longer requires `open == 0`. In-flight commits are normal, not
  faults. Fault semantics preserved via PENDING/STUCK only.
- **V5.0.6772** (COMPOUND_LADDER_READS_EQUITY_NOT_CASH):
  OrderSizeResolver6441 now feeds totalEquitySol() to
  RunnerCompoundingLadder6440. Ladder tier ratchets with equity growth,
  not with dry-powder cash residual — so realized wins compound INTO the
  wallet balance as designed.
- **V5.0.6774** (INLINE_SUPERSESSION_WITHOUT_CARRY_ESTABLISHMENT):
  JournalEconomicReplay6619 detects CI-seed / carry-hydrated boot scenarios
  inline via replayCarry6489 signal, without triggering
  establishReplayCarry6489 side effect. Test isolation preserved.
  Result: 245 divergence events → 0 in 180s smoke run.
- **V5.0.6775** (FDG_LANE_CAP_EQUITY + EXIT_COORDINATOR_TURNOVER):
  FinalDecisionGate seal now uses `equity * 0.12` for lane cap (was cash),
  unblocking PROJECT_SNIPER + runner lanes at saturation.
  BotService EXIT_COORDINATOR_FULL_MIN_MS 30s → 5s — normal-exit turnover
  6x faster.

## Known Remaining P0 / P1 (not yet shipped)

- **F2 · CORE post-sizing funnel** (65 sized → 2 exec, 98% attrition after
  sizing). Suspects: STALE_FEEDBACK_EPOCH_REVALIDATE_6715 / ONE_EXECUTABLE_
  BUY_PER_MINT_VERSION. Requires deeper source trace.
- **F5 · SHITCOIN exit choke** (lane marked EXIT_CHOKED). Requires
  SHITCOIN-specific exit config audit.
- **F6 · Provider fallback** (Birdeye dead, CoinGecko 1%, Groq rate-limited;
  DexScreener 100%). Requires provider ordering rewire.
- **CI acceptance witness NO_COMPLETED_PASSING_CURRENT_WINDOW**: 4 DELTA
  divergences GONE (fixed by 6768/6774), but the 120s window doesn't close
  cleanly in a 180s smoke run. Likely benign — post-6775 the window should
  emit an OK verdict on the next successful reconcile.

## Design Notes (Not Bugs)

- PROBE_ONLY is an approved dust-size probe (see FinalDecisionGate.kt
  lines 35-46). It legitimately reaches execution — this is bootstrap data
  gathering for warming heads.
- 100-position hard cap (ExitThroughputAuthority6727) is intentional.
  Operator does not want caps reduced; the intent is to make sizing +
  exit turnover keep pace so the cap is rarely hit.

## Architecture
- Native Kotlin Android app, event-sourced.
- Canonical registries under `com.lifecyclebot.engine.truth.*`.
- Build/test via GitHub Actions CI only. No local compiler.
- Version bumped in BOTH `/app/AATE_VERSION` and
  `/app/lifecycle_apk/AATE_VERSION` on every commit.

## Test Credentials
See `/app/memory/test_credentials.md` (none used — bot is standalone,
no auth). GitHub PAT authenticated via `gh` CLI.
