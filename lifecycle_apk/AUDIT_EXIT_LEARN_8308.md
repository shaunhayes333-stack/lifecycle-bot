# AUDIT 5.0.8308 — emergency exits, POST_MIGRATION_HOLD admission, learning from losses, basis

Branch `wt-gexit-8308`, from origin/main 403a672d9 (live 5.0.8306). Evidence: owner report summary only
(`evidence8306.md`, `evidence8302.md`); the full forensic log was not attached, so per-trade facts below are taken from
the report and every code finding is from source. Tests: `app/src/test/.../engine/Aate8308ExitLearnTest.kt`.

---

## 1. Emergency sell routing (2bNrYy: -85.7 % in 95 s, catastrophic exit 26.7 s, broadcast->confirm 33.6 s, first send EXPIRED_PROVEN)

### What the code did
| Step | Code | Effect on an emergency sell |
|---|---|---|
| First attempt | `exitPumpFirst8189` -> `jupiterFirstFor8189` | Stops (rank 1-4) ask Jupiter first **only if** `SellRouteEndpoint8144.peek(JUPITER)` is ALLOWED for the mint. Otherwise `shouldTryPumpDirectFirst6099` can put PumpPortal first. |
| Retry after a failed emergency attempt | `skipQuoteLadder8009` -> `emergencyRouteEscalated7807` (7807 B4) | Attempt >= 2 **skips the Jupiter quote ladder** whenever `directRouteViable8144` (PumpPortal admissible for the mint). The attempt then goes PumpPortal -> Raydium -> PumpPortal rescue: the one route that was expiring. |
| Route health | `SellRouteEndpoint8144` | Per-mint memory only (an UNLANDED send backs PumpPortal off 45 s **for that mint**). The route-wide PumpPortal land rate (40 %, 11/25 expired) was never read for sells; 8288's `PumpLanding8288.skipPumpPortalBuy8288` covers buys only. |
| Blockhash | `tryPumpPortalSell` | Signed PumpPortal's build-time blockhash (8288's `freshen8288` was buy-only). A stale blockhash is the classic "expired unseen" shape. |
| Expiry | `SolanaWallet.awaitConfirmation` | Expiry can only be proven after >30 s (signature unseen + blockhash invalid). After `TxExpiredProven8085` the PumpPortal step returns null; with no Jupiter quote in hand (ladder skipped) the attempt fell to Raydium / PumpPortal rescue (rescue backed off by the UNLANDED record) -> `NO_SIGNATURE` -> next attempt after the emergency retry delay. That is the 30 s+ broadcast->confirm leg in the report. |

### Fix (`engine/sell/EmergencySellRoute8308.kt`, call sites one line each)
* **Route land rate from the signed-tx ledger** (`SignedTxLedger8085` rows, chain-resolved only: CONFIRMED vs EXPIRED_PROVEN / FAILED_ON_CHAIN, both sides, 20-min window). A route with >= 5 resolved sends and < 60 % confirmed is NOT_LANDING. A SELL of the same mint proven expired in the last 180 s is JUST_EXPIRED.
* **Emergencies (ProtectiveExitClass7807 ranks 1-3: capital preservation, structural, hard SL) never go first to such a route**:
  * `Executor.tryPumpPortalSell`: `skipPumpPortalSell8308(mint, exitReason, labelTag)` returns null for a non-rescue emergency send -> race (Raydium/Helius Sender) / Jupiter ladder. The rescue step (after every other route) is still allowed.
  * `Executor.skipQuoteLadder8009`: `keepAggregator8308` keeps the Jupiter ladder (Ultra / Metis via Helius Sender or Jito) for the escalated emergency instead of skipping it.
* **Fresh blockhash on PumpPortal sells**: `PumpLanding8288.freshen8288(wallet, built.txBase64, "SELL")` before the Helius Sender envelope (labels `PUMP_SELL_BLOCKHASH_*_8288`).
* **On EXPIRED, re-send at once via the other route**: liveSell's "first route missed" line now calls `raceOrResend8308` (token-neutral swap for the ART budget). When the racing Raydium build is not used, the reason is an emergency, the PumpPortal sell of this mint was just PROVEN expired, no Jupiter quote is in hand and **no other sell of the mint is unresolved in the ledger**, it builds one Jupiter sell now: fresh quote at the emergency rung, fresh blockhash, urgent Sender tip / Jito tip. `SignedTxLedger8085` still refuses to sign while any (mint, SELL) row is unresolved, and the re-send happens only after chain-proven expiry, so there is never a second signed sell in flight.
* **Priority fee**: catastrophic / structural exits already run as drain exits (`fastLand7980`: 0.0005 SOL CU price, urgent tip); a mint with an unlanded sell pays urgent (`sellUnlanded8164`); the expired re-send pays urgent. Hard-SL keeps the normal fee unless its previous send did not land (unchanged; paying urgent on every -10 % stop is ~1.4 % of a 0.028 SOL position).

### Measurement (trigger -> broadcast -> confirm per route)
`SignedTxLedger8085.transition` feeds every resolved SELL row to `EmergencySellRoute8308.onSellState8308`: trigger time from the sell spine (`SellRouteSpine8144`), broadcast = first submit, confirm = chain resolution. Printed in `stopRoute8189[...] emergencyRoute8308[land20m{PUMPPORTAL=a/b JUPITER=.. RAYDIUM=..} pumpAvoided ladderKept expiredResentJupiter timing{EMRG|JUPITER|t2b=n/avg/max b2c=.. t2c=..} outcomes{EMRG|PUMPPORTAL|EXPIRED_PROVEN=..}]` (Position proof line) and per emergency row as `EMERGENCY_SELL_ROUTE_TIMING_8308`.
The 5.0.8306 numbers (26.7 s / 33.6 s) are the only per-route figures available; the per-route split for 8308 will be in the next live report under the line above.

---

## 2. POST_MIGRATION_HOLD admission (2bNrYy at $23k cap right after migration)

### Audit
`LanePlaybook7907.POST_MIGRATION_HOLD` (MOONSHOT and PROJECT_SNIPER menus) fired on: token age <= 60 min, liquidity >= $15k, 5-min change >= 0, buy pressure >= 55, cap/liquidity <= 6. It did **not** check:
* that the curve had actually completed (the name says post-migration; nothing read `TokenMap.migratedOrGraduated`);
* where the price sat against its peak — a pump.fun migration completes near the top of the curve, so a $23k cap minutes later is a collapse already in progress (the 5-min change can be >= 0 on a dead-cat bounce);
* holder concentration (`top`), the dev's sells (`tapeDevSold`), a single buyer dominating (`tapeLargest`), or the creator's rug record.
Record / LP: live entries also need the setup's proven record (LIVE_PROVEN_SETUP_ONLY_8059, forward-label based — the report shows labels overstate live by ~40 points; that is the selection agent's area). LP state is not observable for this setup beyond liquidity (pump.fun's PumpSwap migration burns LP; `lpLock` is unknown for most), and post-migration tape is mostly missing because PumpPortal refuses PumpSwap data (data agent).

### Fix (`postMigrationProven8308`, AND-ed into the setup; nothing else loosened)
Every input must be OBSERVED (unknown fails closed): migrated (route truth) · price within 30 % of its observed peak and drawdown <= 30 % · largest holder <= 30 % (the pool's own ~21 % passes) · launch tape shows the dev has not sold · no buyer above 35 % of the crowd · creator not a known rugger and rug rate (when known) < 30 %. The 2bNrYy shape (deep under the migration peak, no PumpSwap tape) no longer matches. `F.migrated8308` is part of the feature fingerprint.

---

## 3. Losses quarantined from learning (EhuiZV, 2bNrYy ECONOMICS_QUARANTINED; MemeCausalLearning retired; ExactStrategyPerformance 6/6 refused)

### Root cause (source)
* `FinalizedLearningReconciler7423` names a CLOSED canonical row with a non-blank `quarantineReason` ECONOMICS_QUARANTINED. The only producer of such a row is `CanonicalPositionAuthority6441.applyProtectiveSellFill7807`: a confirmed sell on a mint owned only by a funded protective **BASIS_UNCERTAIN_7807** row closes it "no PnL, no cash, no learning" and never publishes it to the finalized bus.
* A live bot buy becomes that row whenever its verification could not commit the fill immediately — `Executor.protectLandedLiveBuy7807` with `TX_TRUTH_DEFERRED` (getTransaction not parsed inside 15 s — likely with Helius over budget), `PROOF_INCOMPLETE`, `INVALID_PROOF`, `ENTRY_PRICE_PROOF_DEFERRED`, `CANONICAL_COMMIT_REJECTED_6486`. **Nothing retried the proof**, so a 95 s rug closed before anyone looked again. It is not a basis mismatch, partial fill or fee issue: the buy's tx meta (token delta, SOL spent, fee) and the sell's proceeds are both on chain; the bot simply never re-read the buy.
* The same path never wrote an `EntryStrategySnapshot6450` (only after a successful commit). `MemeCausalLearning6568` refuses any close without one and `FinalizedBusConsumerBridge6465.retireIfPermanentlyRefused7169` retires it after 3 refusals; `ExactStrategyPerformance7429` refuses on missing trade type / setup / style / tactic. Both silently (no cause printed).
* The protective row is also put in `LearningQuarantineGate6470` (permanent per position).

### Fix
* `ProtectiveBasisProof8308` (engine/truth): the protective path records the bot's own buy signature; the proof is retried off-thread at 4 / 12 / 30 / 75 / 150 s with `TradeVerifier.verifyBuy` (the live path's own tx-meta reader), and once more, bounded (5 s), in `SellFinalizationCoordinator` right before `applyProtectiveSellFill7807` would close the row without economics. A LANDED proof promotes the **same positionId** through `promotePendingToOpen` (the path a verified fill already takes for BASIS_UNCERTAIN rows) with tx-meta quantity, cost (SOL spent - fee) and fee, entry USD = cost / tokens x SOL/USD observed when the buy landed (source `LIVE_TX_META_LATE_PROOF_8308`). The sell then books real economics through the normal canonical path, publishes the close and the learners get it.
* Only provable rows are promoted: live, BASIS_UNCERTAIN_7807, **never sold** (a protective row already partly sold has lost its slice proceeds) and holding exactly the buy's delta (no remnant mixed in). Everything else stays quarantined — genuinely unprovable.
* `LearningQuarantineGate6470.releaseProvenBasis8308`: the one release, for BASIS_UNCERTAIN_7807 holds only, after promotion.
* `Executor.protectiveIdentity8308`: the protective path writes the entry identity snapshot (sealed lane, candidate version, tactic / trade type / setup / style from the decision's policy snapshot). Identity is who decided, not economics.
* No silent retirement: `FinalizedBusConsumerBridge6465.statusLine` prints `retired8308=[consumer:cause=n]`, `exactRefusals8308=[cause=n]` (`EXACT_STRATEGY_REFUSED_8308_<cause>` labels: NO_ENTRY_SNAPSHOT, ATTRIBUTION_INCOMPLETE, PERSISTENCE_NOT_RESTORED, COMMIT_RETRY, ...) and `protectiveProof8308[noted promoted pending unprovable txFailed]`; the reconcile detail marks `protectiveCloseNoEconomics8308=true`.

---

## 4. Basis

### Capital creed now=0.1493 (0.66x) vs equity 0.3481
`LiveRiskPolicy7807.runtimeInputs` fed the daily scoreboard (`noteEquity8057`) with `liveEquitySol(walletSol)`, and the FDG passes `walletSol = WalletCapacitySeal7868.freeCashFor(...)`: wallet SOL **minus every other candidate's sealed reservation**, plus held cost. That is a sizing input, not equity. The scoreboard now reads `CapitalDrawdown7948.liveMarkedEquitySol7948(status.walletSol)` (whole wallet + held cost + unrealised; the number the drawdown line and KillSwitch print) via `scoreboardEquity8308`; sizing is unchanged. The day start is stored under a new key (`day_start_equity_8308`) so the corrected "now" is not divided by a free-cash start.

### 6mYfg1 runtime entry 5.93e-4 vs canonical 6.72e-4
Exits (STRICT_SL, partial / profit-lock levels, risk clock, high/peak writers) compute P&L from `TokenState.position.entryPrice`; the canonical row's `entryPriceUsd` (locked from the tx-meta fill; weighted on top-ups) is used only by `PositionDisplayTruth8078`, which logged `DISPLAY_RUNTIME_BASIS_DIVERGES_8078` "so the engine basis can be repaired at source" — nothing repaired it. A runtime entry 12 % under the fill makes each price stop fire ~12 points late. `RuntimeBasisSync8308.sync8308` (called from `manageableForExit7146`, i.e. every manage tick, zero tokens added to `runManageOnly`) sets the runtime entry to the canonical entry when they differ by > 2 % and the canonical row is live, open and carries a clean chain basis (`learningUncleanReason7807 == null`). Only the entry price moves. Which writer drifted 6mYfg1's runtime entry cannot be identified without its log; the divergence is now repaired and logged as `RUNTIME_ENTRY_SYNCED_TO_CANONICAL_FILL_8308` (old/new/gap/source). SOL P&L on the bus was already canonical (`WholePositionEconomics7835`).

---

## Constraints honoured
`FinalDecisionGate.evaluate()` untouched. ART budget: `liveSellInScope7314` 19998 / 20002 tokens (one call swapped, net -1), `runManageOnly` unchanged, `liveBuy` +5 short argument lists. No safety check loosened, no price / fill / proceeds invented, no learning or ledger reset, AATE_VERSION not bumped.
