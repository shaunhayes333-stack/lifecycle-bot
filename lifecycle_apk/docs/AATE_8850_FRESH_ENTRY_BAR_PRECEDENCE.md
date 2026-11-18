# AATE 5.0.8850 — current execution bars win at the buy decision

## Verified source defect

`TradePlan7739.barsFrom` returned a six-bar 1-minute history unconditionally. Its fallback
`MarkBars7948.merge7948` intentionally also returned a full historical set unchanged. A
token discovered with enough old provider candles could therefore remain pinned to that
old setup even after live marks and signed executed swaps showed a new drop or failed
reclaim. The 8849 stage checkpoint was correct *for the bars it received*, but the live
entry reader could receive outdated bars. This is a data-priority regression, not a
reason to loosen profit/rug/liquidity rules.

## Repair

* **Entry only:** `MarkBars7948.mergeEntryFresh8850` retains historical provider OHLCV
  for completed minutes, merges any observed newer minutes and takes the live mark for
  the currently forming minute. No fabricated candle or provider-derived birth.
* **Entry only:** a sufficiently populated, fresh, executed-trade 15-second or minute
  series can replace an older historical chart. A stale, thin, price-only or
  not-actually-newer series cannot.
* **Both live signing routes:** if a new observed mark arrives while a Jupiter or
  Pump-first transaction is built, the current sell-dominance/reclaim stage is
  rechecked before broadcast. No new mark means no extra network call or wait.
* **Exits unchanged:** historical completed bars still power structural exit trails;
  full on-chain finality, quote/fee validation, FDG, rug, stage and wallet authority
  remain binding.
* Regression tests cover six historical bars versus fresh falling marks, newer minute
  bars, fresh versus stale signed prints, signing-time freshness, completed provider
  precedence and future timestamp rejection.

## Acceptance evidence needed

The release build and JVM/emulator checks prove compilation and source behaviour,
**not** win rate or income. On the next installed live session, measure the admission
stage at its latest mark, launch age, signed buy/sell pressure, current setup, FDG
ALLOW/REFUSE, quote-to-broadcast latency, confirmed buy/exit fills and net SOL position
outcomes against the profitable 8096/8114 baselines. Only reproducible live net
profit with runner banking and reliable FDG flow would satisfy the owner’s requirement.
