# V5.0.6701 — Meme price-basis / phantom mega-PnL repair

## Operator evidence

The Meme Trader Open Positions screen showed several unrelated PAPER positions simultaneously around +93,000,000% to +100,000,000%, with header unrealized PnL around +396,385 SOL from roughly 20 SOL at risk. The top affected positions had entry/current ratios clustered around 0.93M–1.00M×, which is characteristic of a 10^6 raw-token/UI-token unit discontinuity rather than independent market movement.

## Source faults found

1. `DataOrchestrator.startDexScreenerWebSocket()` wrote `ts.lastPrice = priceUsd` without atomically replacing `lastPriceSource`, `lastPricePoolAddr`, and `lastPriceDex`. A new numeric mark could therefore inherit stale same-provider/same-pool proof from the previous writer.
2. `OpenPnlSanity` already rejected many astronomical basis mismatches, but stale same-pool metadata could still make a 10^decimals discontinuity appear comparable.
3. A bad mark could poison mutable `Position.peakGainPct`, `highestPrice`, and route high-water state. Even after later PnL rejection, the UI/exit stack could continue to display or consume the poisoned peak/lock.
4. `MainActivity` still contains a legacy canonical-fill recompute after the shared verdict. It is downstream of `basisTrusted`, so 6701 makes the shared decimal-scale verdict terminal before that block can execute. This remains a patch-rot surface for future consolidation.

## 6701 repairs

- DexScreener WS now stamps numeric price and provenance as one coherent mutation and mirrors the same mark into `tokenMap`.
- `OpenPnlSanity` rejects a narrow token-decimal-scale discontinuity before source/pool equality can waive it.
- Missing decimal metadata uses a conservative fallback only for sub-micro-dollar entries at common Solana token scales (10^6 / 10^9).
- On the exact decimal-scale rejection, the shared authority clears poisoned `peakGainPct`, `highestPrice`, `lastRoutePrice`, `lastRoutePriceTs`, and tick-floor state. Trusted runner PnL is not capped.
- Regression coverage locks the screenshot-class ratio, unknown-decimal fallback, 500× genuine same-pool runner, peak self-heal, and DexScreener atomic provenance ordering.

## Required runtime readback

A healthy 6701 run must show:
- no new +~100M% simultaneous Meme PnL cluster;
- `OPEN_PNL_BASIS_REJECTED_TOKEN_DECIMAL_SCALE_DISCONTINUITY_6701` on contaminated marks rather than economic PnL mutation;
- `MEME_DECIMAL_SCALE_PEAK_SELF_HEALED_6701` only when an already-poisoned high-water mark is cleared;
- header unrealized PnL matching trusted row-level contributions;
- no profit-lock / trailing exit or learning credit generated from rejected decimal-scale marks.
