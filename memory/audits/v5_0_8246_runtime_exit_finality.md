# V5.0.8246 runtime → V5.0.8250 (CI green: Build AATE APK run 39374144110, commit 994ec56f3)

## Operator snapshot (5.0.8246 after reset)
FDG sealed 15, live buys 4, live sells 2, completed 2 (SHITCOIN −4.6%, MOONSHOT −14.5%), open 2, canonical −0.0056 SOL.

## Fault traced: DqPWFV
- Canonical 48,664,757,393 raw, protect=LATCHED_CATASTROPHE, redispatched=8, awaitingExec=1.
- `SELL_ROUTE_BALANCE_PROBE_8144` CONFIRMED_ABSENT (two providers, owner+mint, confirmed commitment).
- Tracker STALE_RECOVERY_UNPROVEN ("two-provider wallet=0 but no finality").
- "486,647" is the same quantity as a UI amount at 5 decimals (48,664,757,393 / 10^5), not a second reading.

Chain of cause (source-confirmed):
1. `WALLET_TOKEN_2022_OPTIONAL_FAILED` → `WalletSnapshotCompleteness7140` partial.
2. `SellAmountAuthority.resolve`: mint absent from a PARTIAL snapshot → `Resolution.Unknown` and the 7733
   complete-absence record is cleared.
3. `BalanceProofPoller.pollOne` Unknown branch → `absentFromCompleteIsZeroRead7733` false forever → `scheduleNextPoll`.
4. No zero read → no streak of two → `zeroCorroborated8144` never asked → no `ZERO_BALANCE_CONFIRMED` →
   tracker close / 7736 canonical quarantine never ran → `pruneClosed7176` never dropped the latch → risk clock
   redispatched `requestSell` on the 7807 emergency schedule, each attempt re-entering the wait.

## Fix 8250 (`BalanceProofPoller`)
`multiProviderAbsentIsZeroRead8250`: on Unknown, after the 7733 path, ask `SellRouteProbe8144.balance8144`
(15 s cached else fresh, ≤2.5 s). CONFIRMED_ABSENT (≥2 distinct providers) is ONE zero read when: no signed SELL
for the mint is unresolved (its confirmation decides, with proceeds) and the buy is older than the 180 s grace.
HELD → `heldRefutesAbsence8144` (proof-ready, wait cleared, emergency resell). Finality unchanged: streak 2 →
`zeroCorroborated8144` re-ask → frozen guards → tracker `confirmZeroBalanceClose` / 7736 quarantine. No sale invented.

## Not changed / still open
- `PROTECTIVE_EXIT_ARMED_STALL_7213=1`: one episode of open inventory with no fresh mark compared for 15 s (DqPWFV
  had no executable price); separate from the exit-finality stall above.
- Feature completeness 177/7035 (tape/swaps/structure/context): same-generation data recovery not yet repaired.
- QUALITY FDG-approved mark did not open; 10 specialists with no owner-selected executable intents — needs the
  next runtime log's lane trace (no code change without evidence).
- PumpPortal stream health after the operator funded the key wallet — verify `PUMP_TRADE_EVENT_7278` rises.
