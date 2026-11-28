# 9063 — runtime, native evidence and crypto price recovery

Based on 9062 (`a673aa840`), the code in the supplied handset build, including its previously unmerged price-proof, journal-unit and held-exit repairs.

## Evidence and scope

The 28 November 17:40 Brisbane report records heap pressure at 486/512 MB, an earlier OOM, high allocation/GC activity, and LaneHunter.claimsFor7803 among hot application frames. Zone research has 5,431 stored observations but zero models/predictions; 4,000/5,563 completed windows are censored. CRYPTO_ALT submitted 340 candidates, dispatched zero, and also recorded 309 pre-submit price waits. These counts cover different populations and must not be added.

The 13 session closes, grouped by immutable entryLane and using grossOut minus soldBasis, are EXPRESS +0.009831 SOL (2W/1L), CORE +0.003219 (1W/2L), MOONSHOT -0.038887 (1W/6L). This is not proof of a profitable policy or a sufficient sample for a blanket MOONSHOT parameter change. No target/stop widening, forced model authority, new account connection, or invented executable route is justified by this export.

## Repairs

- Hunter ownership reads use one exact key per native lane rather than traversing every token's claim and constructing a key for each. Native fluid bands, multiple lane ownership and TTL semantics are preserved. Global cleanup remains in the hunt; reads expire only their own token's claims.
- Shadow write memory census maintains metadata bytes when keys enter/leave the queue, avoiding a full pending-metadata traversal on every memory read. Ordering, retries and deletion epochs are unchanged.
- The full strategy miner no longer runs on HARD/CRITICAL heap pressure merely because an hour elapsed; worker-time pressure is checked again. Journal saves continue on the existing worker.
- Native zone turns run at most once per minute under NORMAL pressure, on that same serial worker, independently of hourly full mining. The journal is indexed incrementally (256 lines / approximately 1 MiB per pass; an oversize line is consumed without retaining it). Only at most 10,000 compact identities/offsets remain indexed; only the selected scope's at-most-1,200 rows are expanded for fitting. Partial lines retry, truncation/compaction resets offsets, UTF-8 byte positions are preserved, and unchanged rows are not reparsed for every training turn.
- The old first-seen 2,048-scope cap excluded later scopes forever. Exact bounded lexical pages rotate through all setup scopes. Lane, source, market, direction and setup boundaries remain intact; there is no population pooling or lower independent-support requirement.
- The existing price collector delivers all admissible witnessed candidates, ordered by event time, to active windows. A newer observation from pool B no longer suppresses a window's valid pool A observation. Asset, pool, decimals, independent source and original clock checks remain; gaps still censor, never fabricate outcomes. No additional market subscription or polling is introduced.
- Crypto live entry reads the existing exact-mint chain evidence before repeating an HTTP registry refresh. Both bare and solana-prefixed mint identities reach the same fanout fallback. Event-time freshness and all quote/finality boundaries still apply. The existing coalesced chain request handles a remaining miss.
- Crypto execution-failure cooldowns use the exact case-sensitive Solana mint at both resolver and executor. One token's failed transaction cannot cool down a different token with the same ticker. Unsupported bridge/perp routes are not declared executable.

## Verification

267 baseline standalone production-model tests passed before changes; 273 passed after these changes. New behavioral coverage exercises incremental journal reading, partial/oversize rows, bounded retention, UTF-8 offsets, compaction reset, later setup pages, independent venue windows, mint-specific cooldown and exact memory accounting. Additional Android integration tests exercise actual hunter ownership/expiry and pressure/data-path wiring. Existing regression method identities remain present; the old forced-after-one-hour expectation is deliberately replaced by continued deferral under critical pressure.

Full Android compilation, release signing, required/historical regressions, device runtime and upgrade continuity must pass on the final revision before acceptance. Native models remain research challengers until independently supported; these repairs do not themselves demonstrate profitable green/yellow-zone trading or complete coverage of non-Solana desks.
