# AATE 5.0.8833 — dynamic crypto selection and live-authority repair

The 8832 base change is completed by the 8833 discovery-time short-window provenance integration.

## Evidence and scope

Source: operator-supplied 2026-11-17 pipeline, 51,757-line edge JSONL and 642,724-line launch-hour JSONL. Baseline is clean `main` `34b7c059527313130565af21da0c73c784652e24` (5.0.8831).

- CRYPTO_ALT canonical funnel: **964 candidates, 964 submissions, zero FDG approvals, zero intents, zero dispatches**. Report also shows `cryptoSpotPaperBacking8788` **4** approvals despite those zero registered intents. This was a counter and authority ordering contradiction, not proof of four actual buys.
- `CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244`: **4,682** evaluation dispositions. `CRYPTO_PRICE_UNAVAILABLE_RETRY_7418`: **2,959**. The crypto scoring path combined source-undated registry scans with daily buy/volume aggregates.
- Observed live profit was negative in the supplied snapshot. The raw edge file contains repeated token/lane/time projections; they are not independent fills. The launch-hour export contains many rows with no terminal return label. Neither supports a claim that the new selection logic will be profitable.

## Root causes and code-level repairs

1. **Five-minute provider fields discarded:** `DexscreenerApi.PairInfo` already parses m5 buys, sells, volume and price change, but `DynamicAltTokenRegistry` kept only h24. It now carries the m5 fields with their original pair observation clock on **initial discovery**, both merge/upsert routes and subsequent bounded refresh. A newer observation wins; a delayed older pair or an undated metadata update cannot erase current flow. A missing field is unknown (`-1`/`NaN`), while a real zero stays zero. There is no invented unique-buyer count: provider m5 buys measure transactions, not distinct wallets.
2. **Wrong token timing:** `CryptoAltTrader` used the older candidate price when a newer registry row existed, and scored the CryptoBrain *before* recording its current market observation. It now re-reads the registry price, records a source-dated tick first, then scores. Both discovery upsert paths and direct hydration also reject delayed source prices that would rewind an already newer price observation. `CryptoLaneDesk7391` rejects undated, duplicate, stale and out-of-order ticks; scan repetition cannot create fake 20-second bars.
3. **Selecting yesterday's activity:** `CryptoShortWindow8832` requires recent provider evidence for buyer flow, price change and short-window volume. Dynamic score uses m5 momentum, m5 buy/sell imbalance and m5 volume acceleration against a weak day baseline; h24 is weak context only. `CryptoTacticEvidence8535` will not call stale h24 buyer pressure momentum. A genuinely sourced rising m5 leg can qualify an early/continuing candidate; an actual falling local tape takes precedence. The existing safety and finality paths are unchanged.
4. **Missing signals not hydrated:** bounded registry hydration now requests evidence when short-window activity or price observations are missing, even if a cached display price or h24 data exists. It uses the existing 12-request/scan, 3-in-flight, 120s/token controls. DEX-routable chains share that budget; anonymous/statics and CoinGecko placeholders do not invoke a nonexistent DEX route. This is best-effort; provider outages still cause data deferrals.
5. **Crypto spot falsely blocked twice:** `CanonicalEntryAuthority6551` first recognized a paper-backed exception to uncalibrated EV, then refused it at the later Oracle advisory check. Proven Oracle evidence and hard safety still bind; a supported inverted refusal can now advance past the redundant advisory test. Paper-backed permits reserve only after all other canonical gates and executable sizing; they consume the four/hour quota only on an immutable registered intent. Registration/conflict failure releases the permit. Hourly timestamps persist across restart.
6. **Paper outcomes changed live authority:** paper result buckets previously rotated live tactics, shrank live sizes, shadow-banned live score bands, altered live sentiment/tilt, learned TP levels, entry floors and confidence, and influenced live exit tuning. `CryptoBrain` now selects separate PAPER and LIVE state at those reads and writes. Legacy mode-mixed strategy state is retained as research, not promoted to live proof. The persisted tilt timeout remains intact for safety. Live outcomes can still learn and change live decisions.

## Acceptance invariants

- Repeated reads of one price observation produce one bar. A new source observation may update a bar and a source observation at least 20s later may add a bar.
- A stale, undated, missing or seller-dominated m5 provider row cannot be interpreted as fresh buyers. A fresh source-observed m5 rise with current buy transactions may activate a momentum shape; a falling latest candle does not.
- Paper-only streaks, bucket losses and tactic rotation cannot affect live entry grade, shadow recommendation, sizing, confidence, stops or TP maturity.
- The spot paper exception cannot bypass hard safety, wallet/route restrictions, proven Oracle refusal, earned Cortex refusal, or executable-size checks. The exception is counted only when the exact canonical intent is registered and the four/hour limit remains enforced.
- All new `Aate8832*` and `Aate8833*` regressions are included in the GitHub release workflow's mandatory targeted unit group; existing `Aate7244`, `Aate7676`, `Aate8535`, `Aate8788`, and related source-contract assertions were updated for intentional mode provenance changes.

## Deployment and post-build evidence needed

Android Gradle/Kotlin toolchains are absent in the scratch environment and Gradle distribution DNS is blocked. Release validity requires a green GitHub Actions **build and unit test**, plus an actual signed APK artifact. Do **not** mark this as compiled, deployed, profitable or validated on chain until those events are observed.

On first-run telemetry, require: (a) `CRYPTO_ALT` FDG approvals/intent/dispatch/open counts to reconcile, (b) actual `CRYPTO_SPOT_PAPER_BACKED_8788` increments no faster than registered intents and at most 4/hour, (c) `CRYPTO_DESK_UNDATED_PRICE_NOT_CHARTED_8832` and enrichment hit-rate/latency, (d) live entry trades priced from same-asset executable routes and net-after-cost closes with peak/exit truth, and (e) per-strategy live WR/PF/expectancy on adequately sized independent settled cohorts before increasing capital.

This is a code-path integrity and selection-evidence repair, not a guarantee of positive returns. Other producers (PERPS, STOCKS, MARKET) starting at zero in the snapshot may still be disabled by their own runtime/venue/toggle conditions; this change does not silently enable leverage or equities.
