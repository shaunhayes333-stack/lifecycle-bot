# Code audit: money and position truth (lifecycle-bot 5.0.8757, origin/main 916e8eb71)

Scope: paper ledger, canonical positions and economic events, journal replay, fill lots, live sell finality and reconciliation, treasury, fees, and the P&L used by the learners and the Diagnosis Brief.
Method: read-only code reading, cross-checked against the runtime log `aate_pipeline_20261115_150239_790.txt` (build 5.0.8756, LIVE mode, uptime 333 s).
App root: `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/` (paths below are relative to it).

Note: the working tree has uncommitted 5.0.8758 edits to `engine/truth/PaperMirror8757.kt` and `engine/truth/CandidateAdmission8136.kt` that I did not make and did not touch. They add an hourly rate cap and an economics check to the mirror. This audit covers HEAD. Finding 9 still applies with those edits.

Severity: **P0** = the money figure is wrong (cash, basis or realized P&L created or lost). **P1** = it goes wrong under realistic conditions, or what the owner sees differs from the wallet. **P2** = latent risk, rot or reporting error.

---

## What the log shows

| Evidence (log) | Meaning |
|---|---|
| `PAPER_LEDGER_REBUILD_FROM_CANONICAL_6502:1`, `FILL_LOT_REALIZED_DIVERGES_FROM_LEDGER_6504:1`, `PAPER_LEDGER_OVERRIDE_FROM_FILL_LOTS_6504:1`, `PAPER_LEDGER_OPEN_COST_RECONCILED_FROM_PROJECTIONS_6508:1`, `PAPER_LEDGER_CASH_REBUILT_FROM_IDENTITY_6505:1` | On this boot all four startup "repairs" fired and rewrote realized P&L, open cost and cash in turn (Findings 1 and 2). |
| Paper ledger `cash=173.5663 openCost=10.3877 realized=+185.2033 fees=7.9959 treasury=5.0134` | Implied starting cash is about 11.76 SOL, so the paper account "grew" 16x on gross realized +185 SOL. The separate paper meme book (`memePaper8544`) shows realised −8.303 SOL, and every lane sweep is negative. |
| `PAPER_ATOMIC_COMMIT_LEDGER_ONLY_6632:566`, `ACCOUNTING_RECONCILIATION_PENDING_6635:566`, `PAPER_ATOMIC_COMMIT_LEDGER_DUPLICATE_6632:2264` | Half-commit receipts are never cleared, and repair projections keep re-stamping (Finding 8). |
| `PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619:33`, `JOURNAL_TERMINAL_SELL_RESIDUAL_WRITTEN_OFF_6868:1`, `JOURNAL_EMBEDDED_ENTRY_RECOVERED_6664:1` | Journal replay does not reproduce the ledger (Findings 6 and 12). |
| `QUANTITY_INVARIANT_CANONICAL_BROKEN_6635:9528`, `RUNTIME_OPEN_REJECTED_INVARIANT_6636\|QUARANTINED:9528` | Quantity-invariant quarantine runs at high volume. Quarantined positions feed Finding 1. |
| `Last crash: OutOfMemoryError` (259 min before capture) | Process restarts reset in-memory stops and receipts (Findings 9 and 17). |
| `sellLanding8368 PUMPPORTAL sent=4 landed=2 EXPIRED_BLOCKHASH=2` | Sells whose verdict is unknown or expired do happen (Finding 7). |
| Brief `memePaper8544 … closed: EXPRESS[…] P8572\|EXPRESS[…] P8623\|EXPRESS[…]` | The same closes are printed three times (Finding 20). |

---

## Ranked findings

### 1. P0: The startup identity rebuild turns removed or quarantined cost basis into spendable paper cash
- `engine/BotService.kt:7105-7121`: 6508 overwrites the ledger's open cost with `activeMintProjections6490("paper")`, then 6505 rebuilds `cash = start + realized − fees − openCost − treasury`.
- `engine/truth/CanonicalPositionAuthority6441.kt:1504-1507` (`isOpenLifecycleWithQty6743`), `:2260`: projections only include OPEN and PARTIALLY_CLOSED rows. QUARANTINED rows drop out.
- `CanonicalPositionAuthority6441.kt:911-937` (`quarantine`) only changes the lifecycle. No ledger or realized entry is written. Callers include `CanonicalPositionAuthority6441.kt:121` (exit eligibility: INVALID_ENTRY_BASIS or INVALID_REMAINING_QUANTITY on a paper close attempt), `engine/Executor.kt:15396`, `:23278`, `engine/BotService.kt:3115`, `engine/FrozenHeldUnsellableLive8141.kt:119,323` and `engine/WalletReconciler.kt:84`.
- `engine/truth/PaperAccountLedger6430.kt:397-413` (`onPositionPurged`) also lowers open cost with no realized loss. It has no callers today, but the next caller would hit the same hole.
- Consequence: at the next boot, any quarantined or removed position is refunded **at cost**, whatever it was worth. A rugged position that was quarantined becomes cash. The code already admits this: `engine/truth/JournalEconomicReplay6619.kt:858-861` ("old Stop paths could remove canonical positions and let the identity rebuild return their basis to ledger cash"). The response was a journal patch that refunds the same basis at zero P&L (`JournalEconomicReplay6619.kt:973-993`, `ORPHANED_STOP_LOT_REFUND_6662`), so the journal now matches the wrong ledger.
- Fix: any transition that removes inventory (quarantine, purge, dust, zero-balance close) must go through `onSellAtomic6632` with real proceeds (0 for dead), so the basis becomes realized loss. Then make 6508 and 6505 report-only: emit the divergence and never write it.

### 2. P0: Every boot rewrites realized P&L and cash with four repairs that use different formulas
- `engine/BotService.kt:7064` (6502) sets realized from `EconomicEventSchema6464` with quarantine filtering. Then `:7079-7092` (6504) replaces it with `FillLotLedger6504.rebuildRealizedSol()` whenever the two differ by more than 0.001. Then 6508 overwrites open cost, and 6505 overwrites cash from the result.
- `engine/truth/FillLotLedger6504.kt:285-357`: the fill-lot rebuild is FIFO per **mint** (not per position), gross, has no quarantine filter, and silently drops sells with no matching buy (`:339`). Buy-lot lamports exclude fees (`engine/Executor.kt:17321`, `engine/truth/CanonicalPaperTransaction6486.kt:264-272`). The table is insert-only, so lots from rolled-back or projected buys stay forever.
- `engine/truth/PaperAccountLedger6430.kt:766-779` (`overrideRealizedFromFillLots6504`) changes realized without touching cash, which breaks the invariant. `:801-845` then "fixes" it by rewriting cash.
- Net effect: the quarantine filtering of 6502 is thrown away immediately. Paper cash is re-derived on every boot from a mint-level FIFO calculation that differs from what the live path credited. Every learner, sizing decision and hero balance downstream sees the result. The log shows all four writes on this boot, and the +185 SOL realized (above) cannot be checked against anything.
- Fix: keep exactly one realized authority (the onSell receipts) and delete the 6502, 6504, 6508 and 6505 writers. Keep them as read-only divergence probes.

### 3. P0: A live final SELL after any partial is never journaled, so the owner's live P&L, the governor and the mirror stop all miss it
- `engine/Executor.kt:29376-29395` (and `:29745-29763`): the full-exit intent uses `entryTokenRaw = entryMeta.entryTokenRawConfirmed` (the **original** buy amount) and `entrySolSpent` = the original cost. `engine/TokenLifecycleTracker.kt:258-275` never updates these after a partial.
- `engine/sell/SellFinalizationCoordinator.kt:301-310` passes them to `EconomicEventSchema6464.recordSell` as `preRemainingQty` and `preRemainingCostBasisSol`.
- `engine/truth/EconomicEventSchema6464.kt:342` sets `remainingQty = pre − sold > 0`, while `partial = false`.
- `engine/truth/JournalEconomicReplay6619.kt:816-819` then fails the check `e.partial != (e.remainingQty.signum() > 0)` and returns null. `projectConfirmedSell8422` drops the row silently (`:844-848`). `repairConfirmedLiveJournals8422` (`:851-856`) retries the same 64 rows forever.
- Only `confirmedSellTrade8422` writes `LIVE_FINALIZED` rows, and `TradeHistoryStore.normalizeProofState` (`engine/TradeHistoryStore.kt:953-968`) gives nothing else that state. So `LiveEntrySafetyHold.LiveConfidenceStats` (`engine/LiveEntrySafetyHold.kt:754-855`), Brief section "B MONEY" (`engine/DiagnosisBrief8438.kt:194-200`) and the PaperMirror8757 −0.1 SOL stop all count the profit-lock partials of a laddered position but **not** its final (usually losing) slice. The same drop happens whenever the confirmed entry raw ≠ the raw consumed by the full exit (transfer-fee tokens, rounding).
- Fix: pass the canonical position's `remainingQtyRaw` and `entryCostSol − soldCostBasisSol` (as the partial path does at `engine/Executor.kt:3612-3613`), or have `recordSell` compute the pre-sell state from `CanonicalPositionAuthority6441`. Add a unit test: partial, then full exit, then assert a LIVE_FINALIZED SELL row exists.

### 4. P1 (P0 in paper): The paper terminal bridge half-commits, never releases its claim, and reports success on exceptions
- `engine/truth/CanonicalPaperTerminalBridge6469.kt:240-253` claims `TerminalSellIdempotency` and `TerminalMutationAuthority` (key for terminals: `paper|pid|gen|FULL_CLOSE`, fixed). The returns at `:278, :287, :323, :328, :350, :362` never call `releaseFailed7835` (compare `engine/sell/SellFinalizationCoordinator.kt:286-289`, which does). Every retry then returns DUPLICATE and the paper position can never close. Cross-asset traders simply return and keep it open (`perps/CryptoAltTrader.kt:4914-4925`, `perps/TokenizedStockTrader.kt:1637-1658`).
- Half-commits after the position was cut (`:325` marks POSITION): `SELL_QTY_COMMIT_FAILED` (`:326-329`), `LEDGER_COMMIT_FAILED` (`:348-351`) and `FILL_LOT_COMMIT_FAILED` (`:360-363`). The position is closed or reduced but cash is never credited: a close with no proceeds. The ledger can still refuse after the 8576/8585 pre-checks, for example a DUPLICATE_IGNORED from the attempt-key stamp or plausibility clamping inside `onSellAtomic6632` (`engine/truth/PaperAccountLedger6430.kt:536-568`).
- `:403-422`: any exception, including one thrown inside `mirrorSell`, `onSellAtomic6632` or FillLot **before** cash moved, returns `applied = true` (`APPLIED_WITH_FANOUT_ERROR`) with full proceeds. Callers then journal a sale the ledger never credited.
- `:255-259`: `PerformanceFees8391.settle` runs after the claim and outside the try block. Its `check(...)` throws `FEE_SETTLEMENT_CONFLICT_8391` (`engine/PerformanceFees8391.kt:73`) on a retry with different proceeds, which strands the claim.
- Fix: release both claims on every return before any mutation. Apply the ledger first (it can refuse) and the position second, or write a compensating reversal. Set `applied = ledgerCommitted`, never true from inside a catch.

### 5. P1: CanonicalPositionAuthority spends idempotency keys on failed mutations, and the executor mirror treats DUPLICATE as success
- `engine/truth/CanonicalPositionAuthority6441.kt:939-948`: `isDuplicate` does `putIfAbsent` before validation. It runs at `:281` (open), `:751` (add), `:809` (app fee) and `:831` (partialSell), ahead of UNKNOWN_POSITION, LIFECYCLE_FORBIDDEN, oversold and INVARIANT_VIOLATION returns. A failed mutation therefore uses up its key.
- `engine/truth/ExecutorCanonicalMirror6442.kt:557` returns `true` for `MutateResult.DUPLICATE`. In `engine/sell/SellFinalizationCoordinator.kt:271-283` (live) and the bridge's non-direct path, a retry with the same sell id after a failed mutation is reported as applied. Fees, receipts, journal and bus then proceed while the canonical position still holds the tokens: the book and the chain disagree, and a later sale counts again.
- Fix: record the key only on APPLIED. In `mirrorSell`, return a tri-state, and treat DUPLICATE as success only if an applied receipt exists for that key.

### 6. P1: Terminal closes leave residual basis, and the paper close helper charges a partial with the full basis
- `engine/truth/CanonicalPositionAuthority6441.kt:847-858` (`partialSell`) takes the caller's `soldCostBasisSol` verbatim even when `newRemaining == 0`. `engine/truth/PaperAccountLedger6430.kt:569-572` lowers open cost by that same caller figure. Any shortfall stays in the ledger's open cost for good and becomes cash at the next boot (Finding 1).
- `engine/truth/CanonicalPaperTransaction6486.kt:811`: `basis = soldCostBasisSol ?: (entryCost − soldCost)`. A caller that passes a partial `soldQtyRaw` without a basis is charged the **whole** remaining basis. The partial books a large loss, and the next close attempt is quarantined as `INVALID_ENTRY_BASIS` (`CanonicalPositionAuthority6441.kt:109-121`), so the remaining tokens are never sold or credited.
- Journal side: `engine/truth/JournalEconomicReplay6619.kt:604-644` writes the residual off open cost **without** charging it to realized. The replay identity then misses by the residual, so `reconcileFromJournal6663` (`engine/truth/PaperAccountLedger6430.kt:869-870`) refuses forever. The log shows `JOURNAL_TERMINAL_SELL_RESIDUAL_WRITTEN_OFF_6868:1`.
- Fix: on a terminal close, force `soldCostBasis = entryCost − soldCostBasis` (and assert it). On a partial without a basis, use the proportional basis (as `CanonicalPaperPartialOperation6510.kt:253` does). Book any residual as realized loss.

### 7. P1: A zero-balance close is booked with no proceeds and no check for an unresolved signed sell
- `engine/sell/BalanceProofPoller.kt:110-111` (`Resolution.Zero`) and `:137` (absent from a complete snapshot) both reach `onZeroRead7733` (`:306-335`). From there, `zeroCorroborated8144` asks the providers only whether the tokens are gone. Only the 8250 path checks `SignedTxLedger8085 … unresolved` (`:220-227`).
- `engine/BotService.kt:3034-3062` → `engine/sell/LivePositionCloseAuthority.kt:248-249` `markClosed(... CONFIRMED_ZERO ...)` with `signature = null`: the close has no proceeds and the P&L is missing or −100%.
- Scenario: a sell is broadcast, `TradeVerifier.verifySell` times out (`engine/TradeVerifier.kt:236`, INCONCLUSIVE_PENDING after 120 s) or the blockhash is reported expired, but the transaction landed. The tokens are gone and the SOL arrived, while the book records a zero-proceeds close. Also, a sell that clears the tokens but nets ≤ 5000 lamports never counts as LANDED (`TradeVerifier.kt:193-196`).
- Fix: before zero finality, require no unresolved signed SELL for the mint, and scan `getSignaturesForAddress(ATA)` since the last buy for a landed sell whose SOL delta becomes the proceeds.

### 8. P1: The atomic-commit witness invents ledger receipts and stays "unpaired" forever, so journal reconciliation never runs
- `engine/truth/PaperEconomicAtomicCommit6632.kt:104`: `hasUnpaired8674()` is true if **any** entry has ever been half-stamped. Entries are evicted only once committed (`:394`), so a single orphan disables `reconcileFromJournal6663` (`engine/truth/PaperAccountLedger6430.kt:858-865`) for the rest of the process.
- `PaperAccountLedger6430.kt:340-353` (`rollbackBuy`) never un-stamps the BUY key that `onBuyAtomic6632` stamped (`engine/truth/CanonicalPaperTransaction6486.kt:611-623`). A retry of the same open then returns DUPLICATE_IGNORED and is misreported as `INSUFFICIENT_CANONICAL_CASH`.
- Fabricated receipts: `CanonicalPaperTransaction6486.kt:254-257` (`stampLedger` "without moving cash") and `:274-281` (marks LEDGER/POSITION/TERMINAL_EXEC committed for a projection), `:440-450` (history repair) and `engine/truth/JournalEconomicReplay6619.kt:958-961` (orphan refund) all stamp the LEDGER side with no ledger mutation. That contradicts `engine/truth/CanonicalEconomicEvent6635.kt:210-211` ("the sweeper is not authority to invent a missing receipt"). The log shows LEDGER_ONLY = 566 and LEDGER_DUPLICATE = 2264.
- Fix: add `unstamp(key)` and call it from `rollbackBuy`. Projections should use a distinct PROJECTION side or key and never the LEDGER witness. `hasUnpaired` should ignore entries that a FAILED or abandoned event has superseded.

### 9. P1: The PaperMirror8757 −0.1 SOL stop is weaker than it reads (realized journal rows only, in memory, partials included)
- `engine/truth/PaperMirror8757.kt:24-26,49-62`: `startMs8757` and `tripped8757` live in memory, so the stop window restarts with every process. The last crash was an OOM, 259 minutes before capture. The stop sums `expectancySol × canonicalN` from `LiveConfidenceStats.byLane8280`. That figure covers closed journal rows only, so open MOONSHOT positions (live sizes of 0.068-0.087 SOL on a 0.239 SOL wallet, per the log's `ORDER_SIZE_RESOLVED_6441`) are not counted. It also misses the final slices dropped in Finding 3 and the recovery rows excluded at `engine/LiveEntrySafetyHold.kt:832-843`.
- The uncommitted 8758 edits add a cap of 4 mirrored entries per hour. That bounds the burst size but fixes none of the gaps above.
- Fix: persist the start time and the tripped state. Measure the stop on wallet SOL change plus the marked value of open mirrored positions since the first mirrored admit, or include their unrealized loss.

### 10. P1: Live per-row net P&L leaves out entry fees
- `engine/truth/JournalEconomicReplay6619.kt:824-831`: `netPnl = gross − allocatedCost − exitFees`. Entry costs are excluded: `engine/Settlement8418.kt:31-38` takes the network and priority fee out of `costSol`, and `CanonicalPositionAuthority6441.kt:805-817` books the app's 0.5% buy fee (BuyAppFee8418) only into `position.feesSol`.
- The wallet pays all of these. Journal-based figures (Brief section B, LiveConfidenceStats expectancy, the governor, the mirror stop, PERFORMANCE ANALYTICS) therefore overstate every live trade by the buy fee plus the app fee. On a 0.03 SOL entry that is about 0.0003 SOL, while the reported expectancy is −0.0010 SOL per trade. The finalized bus (`WholePositionEconomics7835`) does net these fees, so learners and the Brief disagree on the same close.
- Fix: allocate entry fees pro rata to each sold slice in `recordSell`, adding `allocatedEntryFee`, and subtract them in `confirmedSellTrade8422`.

### 11. P1: The 6502 rebuild and the economic schema re-allocate basis independently of the live path
- `engine/truth/EconomicEventSchema6464.kt:333-341` recomputes `allocatedCost = preRemainingCost × sold/preRemaining` in Double. It ignores the caller's basis, which the position and the ledger used. The header (`CanonicalEconomicEvent6635.kt:14-19`) forbids exactly this ("No subsystem may independently manufacture another version").
- `engine/truth/PaperAccountLedger6430.kt:911-922`: the 6502 filter is mint-wide (`LearningQuarantineGate6470.isQuarantined(positionId = null, mint)`), while the live path filters by position (`:441-449`, 8585). Evicted events are folded into a `mint=""` carry (`EconomicEventSchema6464.kt:552-563`) that bypasses the filter. Whether a quarantined trade counts therefore depends on its age.
- Fix: store the caller's allocated basis in the event and never recompute it, and use one quarantine predicate.

### 12. P1: JournalEconomicReplay cannot reproduce the ledger, by construction
- It reads at most 10,000 in-memory rows (`engine/TradeHistoryStore.kt:52`), although it asks for 20,000 (`engine/truth/JournalEconomicReplay6619.kt:213`), and replays them from the original starting cash. It drops both legs of quarantined positions (`:188-221`) and CryptoAlt display sells (`:372-386`), which the ledger applied. Its embedded-entry recovery debits basis without the buy fee (`:532-535`), and residuals are written off without being realized (Finding 6).
- Result: `PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619:33` this session, `reconcileFromJournal6663` blocked, and repeated 6692/6659 "history repair" passes (`engine/truth/CanonicalPaperTransaction6486.kt:30-46`).
- Fix: replay from durable SQLite with a cursor, apply exactly the ledger's predicates, or delete the comparison.

### 13. P1: The reported P&L uses several incompatible definitions
- `engine/TradeHistoryStore.kt:2619-2664` (`getStats` and the owner's "Total P&L") counts `side == "SELL"` rows only. Partial-sell P&L is excluded, and a paper SELL row's `pnlSol` covers the final slice only (`engine/Executor.kt:26484-26495`, `engine/truth/CanonicalPaperTransaction6486.kt:707-713`).
- `engine/LiveEntrySafetyHold.kt:762`: LiveConfidenceStats counts SELL **and** PARTIAL_SELL rows as separate "closes" for win rate, profit factor and expectancy.
- `LiveEntrySafetyHold.kt:845` and `TradeHistoryStore.kt:2628` both use `netPnlSol != 0 ? net : gross`, which mixes gross and net at break-even.
- The paper ledger's realized is gross (`PaperAccountLedger6430.kt:571`), the canonical event's `realizedPnlDeltaSol` is net of fees (`CanonicalPaperTerminalBridge6469.kt:273`), the position's realized is net of exit fees only (`CanonicalPositionAuthority6441.kt:849`), and the finalized bus is net of all fees.
- Fix: one `realizedNetSol(position)` (WholePositionEconomics7835) for every per-trade metric, and one count unit (positions).

### 14. P1: One treasury figure serves paper and live
- `engine/TreasuryManager.kt:627`: `treasurySol += contrib` for paper and live alike. `syncPaperTreasury7294` does nothing in LIVE (`:800-813`). The live wallet state shows this figure (`engine/WalletManager.kt:528-529,574-575`), `tradeableBalance` subtracts it (`TreasuryManager.kt:822`), and SmartSizer tiers read it (`engine/SmartSizer.kt:437,686,952`).
- Paper books keep trading beside LIVE, and the paper ledger holds 5.0134 SOL of treasury. Paper profit therefore inflates the treasury the owner sees, and could shrink live tradeable cash and change sizing tiers.
- Fix: split into `paperTreasury` (mirroring `PaperAccountLedger6430.treasurySol7294`) and `liveTreasury` (on-chain TreasuryWalletManager), keyed by the position's mode.

### 15. P2: Residual tokens after a live close are re-adopted as a new position priced at market
- `engine/LiveCanonicalRecovery6686.kt:714-737` (with `:631-712`): tokens that remain after a recorded full close are re-adopted as a new WALLET_RECOVERED position with cost = mark value. Selling them books `proceeds − markCost`, while the original close already charged the full basis.
- Reported P&L therefore falls short of the wallet by the residual's mark value. Between the close and the re-adoption (a 10-minute window), the book is closed while the chain still holds tokens.
- Fix: reopen the original canonical position (undo the over-allocated basis) instead of opening a new one, or adopt the residual at zero cost with a link to its parent.

### 16. P2: Duplicate cash and proceeds ledgers that can disagree
- Four paper cash figures exist: `CanonicalPositionAuthority6441.paperCashSol` (`:134,366,668,860,902,2320`, with its own debits and credits), `PaperAccountLedger6430.cashPico`, `PaperCapitalAuthority6577` and `BotService.status.paperWalletSol`. `addToPosition6486` never debits the CanonicalPositionAuthority cash; the gap is papered over by `setPaperCash` overwrites.
- A third live proceeds book: `engine/RecoveryFallbackLedger8141.kt` with `engine/ExitTruthProceedsBook8144.kt`. Rows are reconciled to canonical events only by signature-string match (`RecoveryFallbackLedger8141.kt:78-85`) against a schema capped at 8192 events (`EconomicEventSchema6464.kt:161`). A fill that canonical recorded under another key, or whose event was evicted, is counted twice in `effectiveProceeds8141` (canonical + fallback). That feeds the runner capital-recovery tranches (`engine/RunnerExitLive8136.kt:106-117`).
- Fix: one cash authority. Fold the fallback proceeds into canonical through a real `partialSell` and delete the side book.

### 17. P2: Legacy idempotency keys collide or are missing altogether
- `engine/truth/PaperAccountLedger6430.kt:302-308,551-557`: the fallback key is `mint|SIDE|cost_fee` (or `gross_basis_fee`) with **no time component**, and entries live until evicted. A second genuine same-size buy or sell on the same mint is silently refused.
- With `mint = ""` (the `onBuy` legacy path, `engine/Executor.kt:17236`), `keyFromMintSide` returns "" and the BLANK_KEY path runs with **no** idempotency at all (`PaperEconomicAtomicCommit6632.kt:165-166,184-195`).
- Fix: require an explicit attempt id. Refuse unkeyed mutations rather than guessing a key.

### 18. P2: `SolanaWallet.getSolBalance` treats a malformed response as an empty wallet
- `network/SolanaWallet.kt:343-344`: `result?.optLong("value", 0L) ?: 0L` returns and caches 0.0 SOL when the result is missing. `getSolBalancePrimaryOnly6685` (`:396-402`) correctly throws instead.
- The SOL read uses default (finalized) commitment, while token reads use `confirmed` (`:1295, :1968`). The two can come from different slots.
- Fix: throw on a missing `result.value`, and pass an explicit commitment.

### 19. P2: In-memory receipt maps grow without bound
- `engine/truth/CanonicalEconomicEvent6635.kt:108,179-180`: `byMint` is never pruned. Eviction (`:496-503`) removes one COMMITTED event per insert and never FAILED or PENDING ones.
- `engine/truth/PaperEconomicAtomicCommit6632.kt:394` evicts only committed entries, while repairs keep adding unpaired ones (Finding 8).
- These contribute to the OOM in the log (heap 246/512 MB, last crash OutOfMemoryError).
- Fix: TTL eviction of FAILED and abandoned events, prune `byMint` on eviction, and cap unpaired entries with a forensic counter.

### 20. P2: The MemePaperTrader8544 book is misreported and dead, yet it drives live MOONSHOT
- `engine/truth/MemePaperTrader8544.kt:435-443`: each close is written to three keys (lane, `P8623|lane`, `P8572|lane`), and `statusLine8544` (`:530-545`) prints all of them. The Brief shows EXPRESS −3.104 SOL three times.
- `:348`: a LOST_MARK close values the remainder at **entry** (flat). A rugged coin therefore books about −cost% instead of about −100%, and that still reaches `realisedSol` and `balanceSol`.
- The book's balance is 0.097 SOL, below `STAKE_SOL_8544 = 0.1`, so it can no longer open positions (NO_PAPER_BALANCE). Its frozen record is still the evidence that live MOONSHOT mirrors (PaperMirror8757).
- Fix: print one row per lane with the proof variants nested inside it. Value a lost mark at 0 (or the last confirmed print) for P&L. Refill the book or flag its proof as stale.

---

## Other findings (not in the top 20)

- **P2: Fill-lot SELL written twice with different meanings.** `CanonicalPaperTerminalBridge6469.kt:354-359` writes gross lamports; `Executor.kt:26537-26550` writes net with the same `lotId`. The first write wins (`FillLotLedger6504.kt:181-189`), so the executor write and its comment are dead.
- **P2: Treasury split computed on the final slice.** `Executor.kt:26484-26494` uses the slice `close8418.pnl`, not the whole position. A ladder that is net negative still pays the treasury on a green final slice.
- **P2: Ledger notifications inside the ledger monitor.** `rollbackBuy` (`PaperAccountLedger6430.kt:351`), `onPositionPurged` (`:411`), the treasury moves (`:653, :678`) and the reset (`:251`) call the synchronous `notifyEconomicMutation` under the ledger monitor. That is the ANR pattern 8559 removed from `onBuy` and `onSell`.
- **P2: The self-test can wipe the production ledger.** `engine/truth/InvariantSelfCheck6430.kt:152-160` calls `resetForTest()`, `initialize(10.0)` and `onBuy`/`onSell` on the production singleton, and `onBuy` persists (`persistCurrent6487`). `runAll()` is public, so any future caller wipes the durable paper ledger.
- **P2: Dead legacy quantity ledger.** `engine/truth/SellQtyBoundaryClamp6427.kt` still carries a Double quantity ledger with 6 decimals (`clamp`, `registerBuy`) that no production code calls. It mutates `sold` before a sale is confirmed and overflows `Long` above about 9.2e12 tokens. Delete it.
- **P2: Paper reset is partial.** `PaperAccountLedger6430.resetToFreshBalance6618` does not reset the CanonicalPositionAuthority paper cash, the open paper positions, the atomic-commit entries or the `paper:canonical` high-water mark (`PerformanceFees8391.kt:55`). After a reset, the ledger has open cost 0 while positions are still open, and the next boot's 6508 then raises open cost again (it is capped by `fundableOpenCost8169`).
- **P2: Locked token-account rent is outside P&L.** `TradeVerifier.buySolSpent7928` and `sellSolReceived7928` correctly keep rent out of P&L. The wallet still pays about 0.002 SOL of rent per new token account until RentReclaimer runs, so the Brief's wallet figure and settled realized P&L differ by the locked rent (it should be shown as its own line).

## Patch rot summary
- Paper cash has at least nine writers: `onBuy`, `onSell`, `rollbackBuy`, `repairCashFromDisplayed6448`, `migrateLegacyReplayOnce6487`, `rebuildPaperCashFromIdentity6505`, `reconcileFromJournal6663`, `resetToFreshBalance6618` and the treasury moves. Realized P&L has four writers (6502, 6504, 6663, `onSell`) and open cost has three (6508, 6663, the buy and sell paths).
- The journal layer has stacked repairs on repairs: 6662 orphan refund, 6664 embedded entry, 6868 residual write-off, 7078 skipped-economics accounting, 7085 pre-accounting accounting, 7360 rebuild from receipts, 8579 same-receipt dedupe, 8585 rebuild from canonical and 8710 residual claim. Each one names the gap left by the one before. None removes the root causes (Findings 1, 6 and 12).
- Recommended order: (a) route every inventory removal through a sale; (b) force the full remaining basis on terminal closes; (c) delete the startup overwrites (6502, 6504, 6505, 6508); (d) make the journal a pure projection of ledger receipts, written in the same transaction; then (e) remove the journal repair stack.
