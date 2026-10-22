# AUDIT_TICKET_EXEC_8304 — why sealed tickets did not execute (live 5.0.8302)

Evidence: FDG sealed allows=43. MOONSHOT 11 tickets -> 2 exec (execRefused7868=10), SHITCOIN 13 -> 0 (no execRefused line), EXPRESS 2 -> 0.
Wallet 0.2537 SOL, routableMin 0.028, lane ask ~0.020. Second report: WILLOW / KIMCHINU runners MISSED with SIZE_NOT_EXECUTABLE_7835.

## 1. SIZE_ROUTE_MIN_PROMOTION_REFUSED_{NEGATIVE,UNMEASURED}_EDGE_8188 (SHITCOIN 14 + 8): not a dropped ticket, a mislabel

Order of authorities for a live specialist entry: trader evaluate (advisory size) -> FDG.evaluate -> `resolveExecutableSize7835`
(LiveRiskPolicy7807.decide, which runs MinPromotion8188 on the admission's own gross, AdmissionEdge8180.Proof8198) -> seal
(SpecialistPreauthSeal7834.ensure) -> TICKET -> election -> finality -> executor.

- The `SIZE_` prefix is written only by `OrderSizeResolver6441.resolve` when it has a causal id. The only meme path that supplies
  one is `CanonicalSizingBridge6532`, called from `ShitCoinTraderAI.evaluate` (via TraderSizingBridge6444) every cycle,
  before the FDG. The bridge borrowed the attemptId of whatever live intent existed for (mode, mint, version), of ANY lane, so
  a SHITCOIN advisory re-size of a coin already sealed (its own in-flight ticket, or MOONSHOT's) was stamped SIZE_REJECT on
  that ticket and counted as a SHITCOIN pre-size refusal.
- That advisory size does not gate anything: BotService floors it (`coerceAtLeast(0.01)`) and the FDG re-sizes from scratch.
  A legitimate promotion refusal happens inside the FDG (LiveRiskPolicy7807 -> MinPromotion8188) and ends the candidate as
  `SIZE_NOT_EXECUTABLE_7835` BEFORE any seal — no ticket, slot or cooldown is consumed. Route minimum stays real: the FDG
  still refuses a sealed size below `sealedMinimum8269`.
- UNMEASURED is not a contradiction of admission: the advisory read is pre-FDG, by a lane (SHITCOIN) whose own proof did not
  exist for that version (the sealed intent was another lane's, or the proof expired); it then falls back to the setup record.
- Fix: a sealed ticket (positive FDG size) is never this advisory resize's record (`CanonicalSizingBridge6532`, counted as
  `ADVISORY_SIZE_ON_SEALED_TICKET_8304_*` / `ADVISORY_SIZE_REFUSAL_NOT_A_TICKET_DROP_8304_*`). No sizing rule was loosened.

## 1b. SIZE_NOT_EXECUTABLE_7835 (WILLOW, KIMCHINU)

Pre-seal (FDG), so no ticket was wasted, but the label hid four different zeros. One is a data bug: `resolveExecutableSize7835`
read depth only from `ts.lastLiquidityUsd`; a fresh bonding-curve runner often has none on the tick while its curve reserves
are observed (TokenMapAuthority.observedLiquidityUsd — the same read the seal and the executor's entry snapshot accept).
`depthCap = 0` then zeroed the size ("no wallet-share substitute for unknown depth"). Fix: FDG sizing uses the observed depth
when the tick has none (`FDG_SIZE_DEPTH_FROM_OBSERVED_8304`); truly unknown depth still sizes 0. Every zero is now named:
`FDG_SIZE_ZERO_8304_<lane>_{RISK_<LiveRiskPolicy reason>|DEPTH_UNKNOWN|RISK_SIZED_ZERO|RESOLVER_<reason>}`.

## 2. PREAUTH_LANE_TELEMETRY_ONLY_PRIMARY_SHITCOIN (MOONSHOT 8, EXPRESS 1): one coin, two owners

TradeAuthorizer seals the requesting lane's intent (TICKET stamped) and THEN asks LaneExecutionCoordinator.canRequestExecution.
The coordinator bound the version to `sealedFdgOwnerLane6679` = the executable FDG snapshot with the highest authorityVersion
for the version, of any lane. Snapshots are put-if-absent per lane, so SHITCOIN's earlier allow (newer than MOONSHOT's first
snapshot) kept owning the version while the ONE live intent (activeExecutionIntents6519, keyed mode:mint:version) was MOONSHOT's.
The freshly sealed MOONSHOT ticket was terminated as telemetry-only. Fix: the lane holding the live, sized, FDG-BUY intent for
the version is its sealed owner (`TicketExec8304.sealedOwner8304`, `LANE_OWNER_FROM_LIVE_SEALED_INTENT_8304`); the snapshot is
the fallback. A later lane cannot seal a claimed version (OWNED_BY_*_7868, pre-ticket). Also: an unclaimed intent retired by
the cycle primary (7819) used to vanish without a terminal; it now ends `RESEALED_TO_PRIMARY_<lane>_7819`.

## 3. MISSING_FEEDBACK_STAMP_REVALIDATE_6715 / COOLDOWN_MISSING_FEEDBACK_STAMP

The stamp is written only in `registerCanonicalIntent6554`. It is removed (with the reservation) by a same-lane terminal
(onTerminal invalidation), a superseding reservation, the 60 s sweep or a stale admit, while the intent itself can stay live.
The next fresh FDG BUY reuses that intent (`reuseSealedIntent7871`, or recordFdg's dedup skip) without re-registering, so
finality saw a non-cold scope with no stamp, refused, and left a cooldown. Fix (stamping, not the check): after a FRESH FDG BUY
returns an intent, `SpecialistPreauthSeal7834.ensure` stamps it if unstamped (`TicketExec8304.ensureCausalStamp8304`,
`CAUSAL_STAMP_ON_FRESH_RESEAL_8304_*`). An existing stamp is never replaced; staleness is still judged at finality.

## 4. FLATLINE_8027 (1)

Correct refusal (FlatChart8027: >=5 real candles in 6 min inside 2%) — but it ran in liveBuy, after the ticket. The same pure
DEAD_RUNNER / FLATLINE verdict now refuses in TradeAuthorizer before the seal (`PRESEAL_<why>_8304`); the executor check stays.

## 5. SHITCOIN 13 tickets, 0 exec, no terminal

`shitCoinBuy` (transport for SHITCOIN/EXPRESS/MANIPULATED/PROJECT_SNIPER) and `moonshotBuy` returned false on a live buy that
did not open without terminalizing the ticket (only blueChipBuy did); liveBuy's staged aborts/defers bypass emitLiveBuyFail.
Both now end the ticket `LIVE_BUY_NOT_OPENED_7948:<inner stop>` (same as blueChipBuy), so the next report names the stop.

## 6. FIRSTJET / CC: fully sold, still funded quarantine

The wallet reader returns only positive balances, so after both coins were sold the complete read was an EMPTY map.
`ReconcileRunner8144.noteWalletSnapshot8144` discarded empty maps -> no snapshot -> chain incomplete -> PARTIAL_CHAIN_OBSERVED,
proof8189 CHAIN_UNREAD, and the 8264 finalized-sell ghost close (which needs a complete read) never ran. Fix: an empty read is
kept only when WalletAccountCache proves THIS read complete (both token programs, same owner, <60 s); then the lots are
CANONICAL_NOT_ON_CHAIN and a chain-proven LIVE sell covering the remaining quantity closes them (or the 8288 balance-proof
poller does). Also the 7807 two-sighting zero retirement compared the cache's tokens (with USDC) to the reconciler's map
(USDC removed): a wallet holding USDC could never retire. Frozen mints stay FROZEN_HELD; a partial read still proves nothing.

## Counters for the next report

Per lane in the causal funnel: `<LANE> ticketExec8304 tickets= exec= droppedAfterTicket= inFlight= conv= drops=[...]`
(drops = every `terminalizeUnexecutedTicket7948` reason, inner stop kept). Plus the labels named above.

## Not changed

FinalDecisionGate.evaluate() body; MinPromotion8188 rules; route minimum; any hard safety; the causal stamp check.
