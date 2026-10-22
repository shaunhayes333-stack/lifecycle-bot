# AUDIT_SELECTION_8310 — what is bought, what is missed, and why (live 5.0.8306)

Base: origin/main 403a672d9 (5.0.8306). Branch wt-gsel-8310. Owner: "It's still buying rubbish mostly. Where have the 500%+
winners gone?" Evidence: evidence8306 (42 runners/24 h, held 7, missed 35; SHITCOIN labels +38.8 % vs live -6.0 %; Cortex
refuses SHITCOIN|REFUSE|LAUNCH_LADDER_PROVEN n11 +90.7 %; EXPRESS INVERTED; SHITCOIN ticket->exec 9 %, 9 of 10 drops
safety-proof deferrals; 2bNrYy MOONSHOT POST_MIGRATION_HOLD at $23k, -85.7 % in 95 s = 85 % of the session loss) and
evidence8302 (best pond PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M n704 +231.7 % wr 43 % run 33 %).
No live forensic log was attached, so the door that admitted each live loser is inferred from code + the counters named.

## 1. The live admit path and its doors

`LiveEdgeGate7877.liveRefusal` (live) -> early launch refusal -> CandidateAdmission8136 (ADMIT / REFUSE / DEFER_DATA,
RunnerDefer8297 releases confirmed runs past optional-input defers) -> live-close setup refusal (LiveTruth8064) -> timing /
picked-setup / tactic negatives (bind unless the lane earned them, 8298) -> **proven-only door (8059)**, which admits on ANY of:

| Door | Evidence it rests on | Real-record loop before 8310 |
|---|---|---|
| proven8059 (playbook setup 30+ labels, mean-SE>0) | labels, governed by LaneRealisedGovernor8295 (shrunk when the lane/setup bank loses) | yes (8295) |
| measuredEdge8060 (cell / kind / runner cell / specialist / expert copy) | labels, read past the lane shortfall (LiveAdmissionProof8290) | partial |
| candidate-specific ADMIT (8136) | label setup records; stand-downs need the earned lane (8298) | partial |
| generic native ADMIT (8211) | admission economics | partial |
| **RunnerTicket8073** — unproven buy of any graduated PumpSwap coin at $10k-$100k | none: "paid/died" counted in memory, reset on restart, never read | **none** |
| runnerForming8113 (RunnerPlay8018 ticket / RunnerGrab7967 grab) | an observed run (+100 % with structure) / proven runner cell firming; own graded loops | yes |
| shadowAudit8113, coldStart8080 (3 / 6 h) | veto audit / cold-start setup | bounded |

Then the spike-chase rule (8065; stands down for grabs, its own tail audit, **and any label cell/kind proof**), watch-first,
the Cortex (`liveRefusalCore7970`: Cortex entry refusal binds first), NetEdge, lane fit, FDG, sizing, seal, ticket, buy door.

### What the live losers are
- **2bNrYy (MOONSHOT POST_MIGRATION_HOLD, $23k, -85.7 %)**: the shape of a RunnerTicket8073 (graduated PumpSwap, $10k-$100k,
  any lane; the ticket bypasses watch-first and is a positive admit). The ticket is the one door with no real-record check at
  all; the PumpSwap tape for that coin was also blind (PumpPortal "Minimum balance not met", data agent). The -85.7 % past the
  -40 % floor is execution (expiring PumpPortal route, gexit agent).
- Label-only admits in lanes whose live closes lose: SHITCOIN label +38.8 % vs live -6.0 % (40-pt shortfall). The best cells are
  TAIL_DEPENDENT (CellTail8303): the pond's mean is the runners (non-runners <= +7.5 %, runners >= +687 %). An early buy of such
  a cell is a lottery ticket; at a ~6 % round trip it only pays if exits capture the tail — the live bank says they did not.
- A setup with losing live closes kept trading on its labels: LiveTruth8064's refusal needs 5 closes inside 6 h — never reached
  at a handful of live trades a day. (The same setup's compounding was already revoked at 2 closes, 8296.)
- Chase entries at local highs: the 8065 chase rule stood down for any label cell/kind proof, including in losing lanes.

### What refuses the runners
- DATA_INCOMPLETE_DEFER_8136 (Cook/X, Kimchi, LEONIDA, KIMOTHY): tape missing in 21,336 of 24,271 evals. Confirmed runs are
  already released past optional-input defers (RunnerDefer8297); the remaining defers are the tape itself (data agent) and
  `formingNative8260` (unlearned, kept deferring).
- **Cortex REFUSE power on lanes/setups where REFUSE beats STRONG** (fixed, §2).
- **Safety-proof deferrals after the ticket** (fixed, §3): one deferral ends the ticket (`LIVE_BUY_NOT_OPENED_7948:<stop>`).

## 2. Cortex: a REFUSE column that beats STRONG no longer binds live (fixed)

`Cortex7885.entryRefusal` -> `constitutionRefusal` C3 refuses a REFUSE-bucket read when the REFUSE book is proven below the
lane's pooled rest (NEUTRAL+STRONG). It stood down only for a joint setup cell proven on 30+ grades, a label-proven setup or a
proven cell. So SHITCOIN|REFUSE|LAUNCH_LADDER_PROVEN n11 +90.7 % (above the lane's STRONG) was still refused, and an INVERTED
lane (STRONG below REFUSE) kept its REFUSE veto while only its STRONG side was suspended.

Fix — `engine/cortex/CortexInversion8310.kt` + `Cortex7885.refuseShadow8310`:
- LIVE, rules C3_PROVEN_NEGATIVE_EDGE / C3_RELATIVE_REFUSE_8019 only: when the lane is proven inverted
  (`CortexScoreboard7885.inverted7948`), or its REFUSE column reads above its STRONG column (REFUSE n>=10, STRONG n>=5,
  REFUSE mean > STRONG mean), or the selected setup's joint REFUSE cell (n>=10) reads above the setup's STRONG cell (lane STRONG
  when the setup cell has <5), the refusal is kept in shadow (`CORTEX_REFUSAL_SHADOWED_REFUSE_BEATS_STRONG_8310_<lane>_<why>`),
  and the candidate continues to every other gate. Measured each read; it binds again by itself when REFUSE stops beating STRONG.
- Size-up: the same lane gets no Cortex STRONG conviction size-up (`cortexStake7893`, `SUSPENDED_REFUSE_BEATS_STRONG_SIZE_UP_8310`)
  and no stack-shrink overrule (`sizeShape`).
- Untouched: PAPER, C1 hard safety, C5 slot saving, C6 dead setup, C4 timing wait. Nothing here admits a coin.
- Status: `inversion8310[...]` on the Cortex card.

## 3. Safety-proof deferrals: the report that never refreshed (fixed)

Which proof stayed pending: the **safety report freshness**, not a missing authority fetch.
- `PreTradeHardGate.requireLiveBuyAllowed` (8269) requires `ts.safety.checkedAt` within 120 s, else
  `LIVE_SAFETY_REPORT_NOT_FRESH_8269` -> DEFER_SAFETY_PROOF -> SafetyRefreshQueue.
- BotService's explicit refresh is synchronous, but `TokenSafetyChecker.check` returns its **cached report for 10 minutes with
  the cached checkedAt**, while BotService stamps `ts.lastSafetyCheck = now`. The FDG (LiveBuyAdmissionGate reads
  lastSafetyCheck) saw a fresh report and sealed the ticket; the buy door (reads checkedAt) saw it 2-10 min old and deferred —
  on every retry until the cache aged out. 3 deferrals in 5 min end as DEFERRED_DATA_SAFETY_PROOF_8270, and since 8304 even the
  first deferral terminalizes the ticket. That is "9 of 10 SHITCOIN drops".

Fix — `engine/SafetyProofPriority8310.kt`:
- `TokenSafetyChecker.check(maxCacheAgeMs = ...)`: a caller that needs a current proof gets a fresh run instead of a cached report
  older than 90 s (under the buy door's 120 s).
- BotService safety step (runs before the FDG): fresh proof on the explicit refresh a deferred buy queued, and ahead of the buy
  for a LIVE prospect (AdmissionProspect8136 — a candidate at the FDG door — a RunnerPlay ticket, or a proof-deferred mint)
  whose report is already >90 s old (once per 30 s per mint). The sealed ticket meets a fresh proof.
- The mint read inside that run goes **Helius-first** (owner directive: use the paid plan): `OnChainMintAuthorityProof7248.resolve(
  liveGate8094 = true)`, a single `getAccountInfo(jsonParsed)` on the mint giving mint + freeze authority, token program,
  Token-2022 extensions and supply in one call, instead of the public-first scan ladder (public nodes answered 0-33 % in 8127).
  Scan-time checks keep the public-first ladder, so credits are spent only on near-buy mints. No DAS helper was needed: the
  jsonParsed mint account already carries every field the proof needs, and the existing resolver caches clean proofs.
  (The authority prefetch AuthorityProofPrefetch8136 already submits FDG-door prospects at LIVE priority, Helius-first.)
- `PreTradeHardGate.deferSafetyProof` notes the mint; it rides the next cycle's priority window (`HandoffThroughput8190`).
- Not changed: every proof check at the buy door (120 s freshness, mint/freeze authority, Token-2022 hostility, RugCheck rug,
  holder distribution, liquidity floors). A coin without the proof is still never bought.

## 4. Real-record checks on the doors that admit losers (fixed) — `engine/truth/SelectionDoors8310.kt`

- **D1 RunnerTicket8073**: ticket closes are persisted from verified live closes (LIVE_FINALIZED / LIVE_RECONCILED). Once 3+
  tickets have closed and the last 10 net <= 0 SOL, the door is suspended with one probe ticket per 6 h so the record can
  recover; one runner that pays for the losers keeps it open. (Starts empty: closes before this build were never recorded.)
- **D2 setup live record negative**: a setup with 2+ verified live closes averaging a loss (Cortex7885.setupRealised8187) is not
  bought again on its labels (`SETUP_LIVE_RECORD_NEGATIVE_8310`).
- **D3 tail cell, unconfirmed, losing lane**: in a lane whose recent verified live closes lose (LiveConfidence8296.losing8296), a
  coin whose label cell is TAIL_DEPENDENT is not bought before its run confirms (`TAIL_CELL_UNCONFIRMED_LOSING_LANE_8310`).
  The run is bought when RunnerPlay8018 / RunnerGrab7967 confirm it (runner doors, which 8297 and §2-§3 widen).
- **D4 chase on a label proof**: in such a losing lane a label-only cell/kind proof no longer stands the 8065 chase rule down;
  developing runner grabs and the rule's own tail audit still do.
- Runner-confirmed entries (active RunnerPlay ticket / RunnerGrab grab) are exempt from D2/D3. All doors reopen as the bank turns.
- Placement: one line after the proven-only door in LiveEdgeGate7877 (live only); the chase block sits before the 8065 check.
  Status: `selection8310[...]` and `safetyProof8310[...]` on the LiveEdgeGate status line.

## 5. Runner tail economics (exits) — audited, no change

Consistent with 8297/8298, no widening:
- Runner-admitted positions: ProfitEscalation8189.runnerProtected8297 keeps them from being sold whole at the first uneconomic
  rung; RunnerGrab7967 ladder (half at +100 %, 35 % at +400 % / +1000 %), soft exits deferred while structure stands and green;
  peak capture (-35 % from a +200 % peak), from-entry giveback, LifecycleHold8065 trend break / +300 % peak giveback. Trails ride.
- Non-runner losers cut fast: a red runner is held only in its first 3 min above -15 % (holdsThrough7991); LifecycleHold8109
  releases a hold that never reached +25 % after 45 min red or at -20 %; metric collapse releases; 8298 restored the 15 % hard
  floor / 9 % fresh-meme / 15 % MOONSHOT first-12-min backstops until a lane earns them. The ticket's -40 % floor applies only
  with runner evidence (LifecycleEvidence8189) and 8109 still cuts it at -20 %.
- The -85.7 % on 2bNrYy was exit execution (first send EXPIRED, 33.6 s confirm) — gexit agent.

## Growth target (2x–5x daily)

- Wired and live (checked): LiveRiskPolicy7807 quarter-Kelly on equity with the 8296 recency gate and realised-only stake;
  AutoCompoundEngine in LIVE returns LiveConfidence8296.liveCompoundMultiplier8296 (verified live closes only); pyramiding of
  runners (RunnerGrab7967.structureAdd7973 -> topUpSizeSol up to 3x / 25 % wallet per add). Daily target = scoreboard only (8057).
- The mandate's defect in my scope was aggression spent on losing doors (unproven tickets, label admits the bank contradicts)
  while the doors to the edge (confirmed runners) were refused by an inverted Cortex and an unrefreshable safety proof. This
  change moves both: losing doors close on real records; runner and sealed-ticket doors stop being refused for non-safety
  reasons. Size still grows only from on-chain wins (Kelly, live compound pool) — no paper, no labels, no loosened safety.
- Expected effect: fewer early lottery buys in losing lanes; more confirmed-run entries reaching execution; ticket->exec up as
  the proof stays fresh. Watch `selection8310`, `inversion8310`, `safetyProof8310`, `ticketExec8304`, `funnel8297`.

## Tests
`app/src/test/kotlin/com/lifecyclebot/engine/Aate8310SelectionTest.kt`: ticket door (open/probe/suspended/recovery), setup
loss threshold, tail dependence on the 8302 pond, Cortex refuse-beats-strong / shadow reasons / rule scope, safety cache
usability and forced-fresh conditions, wiring pins. `val2.sh` validated; `pins.py 7c147e519` broken 0.

## Remaining
1. Tape for post-migration coins (PumpSwap websocket refusal latching the keyed stream, Helius candles budget-skipped): data agent.
2. Emergency sells on expiring PumpPortal routes, ECONOMICS_QUARANTINED closes excluded from learning: gexit agent.
3. `formingNative8260` still defers on optional inputs (no closed loop of its own).
4. A sealed ticket still ends on its first buy-door deferral (8304); with §3 the deferral should be rare. If
   `SAFETY_PROOF_DEFER_8310_*` stays high, keep the ticket alive across one proof refresh instead.
