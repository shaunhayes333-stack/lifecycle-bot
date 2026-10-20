# V5.0.8251 runtime (`are runtime 454.txt`, 977 s, POST_STOP_SNAPSHOT) → V5.0.8252

## Session
15 sealed? No — this run: FDG sealed 6, EXEC 20 attempts, BUY ok 2 / fail 4 (all MINT_REENTRY_COOLDOWN_6312), SELL ok 1
(cTHXie RAPID_CATASTROPHE_STOP −4.4%), open 1 (JDnbcX, MOONSHOT, wallet=canon=66,023,748,310 MATCHED, protect=ARMED,
route READY PumpSwap). Canonical N=7, 1W/6L, PF 0.05. Wallet 0.3714 SOL, liveDD 0.8%. No crash. Shadow book 13 closes
1W/12L. Reconciler 11/11 OK.

## Specialist trace — qualification → ownership → FDG → size → ticket → exec (from MEME SPECIALIST ROLE LIVENESS + funnel)

| Lane | discovered→qualified | owner | FDG | sized | ticket | exec | Stopping line (dominant, counts) | Verdict |
|---|---|---|---|---|---|---|---|---|
| MOONSHOT | 1200→1129 | 5 | 5 allow | 5 | 5 | 2 | pre-size: CAUSAL_FEEDBACK_6715 revalidate=1, COOLDOWN=1, MINT_REENTRY_COOLDOWN=1; execRefused7868=2; backlog pending=2 | ACTIVE — the only lane trading |
| SHITCOIN | 712→707 | 1 | 1 allow | 1 | 1 | 0 | ticket→Executor: MINT_REENTRY_COOLDOWN_6312 (the 4 BUY fails; same mints MOONSHOT just traded: cTHXie/JDnbcX; arbiter liveContests=4 decisions=0) | EXEC_CHOKED — a second lane's ticket on an already-traded mint dies at the executor, not at election |
| QUALITY | 1960→1470 | 0 | 0 | — | — | — | NATIVE_READY=1128 then FDG CANDIDATE_ADMISSION CELL_PROVEN_LOSING_8136=620 (QUALITY record: RS_IN_WEAK_MARKET −16%, BUYERS_DOMINATING −14%, NEW_HIGH_PUSH −34%, QUIET_ACC −12%; only COLOUR_SEQUENCE +17%) | evidence refusal — correct; needs a positive QUALITY setup to fire |
| BLUECHIP | 2332→1623 | 0 | 0 | — | — | — | NATIVE_READY=1284 then FDG DATA_INCOMPLETE_DEFER=186, LIVE_PROVEN_SETUP_ONLY_8059=170 (latest: chose QUIET_ACCUMULATION −7.9%, missing tape) | data + no proven setup fired (OPP_CONTINUATION +72% auth .67 exists but rarely fires) |
| CASHGEN | 978→941 | 0 | 0 | — | — | — | NATIVE_READY=2035 then FDG TIMING_ADVERSE=390, DATA_INCOMPLETE=306, SETUP_RECORD_LOSING=260 | evidence refusal — its setups are all ≤0 except NEW_HIGH_PUSH n9 |
| CORE | 493→493 | 0 | — | — | — | — | NATIVE_CORE_YIELD_CLEAR_SPECIALIST=6722 (veto audit: refused forward −7.5%, "ok") | by design yields to specialists; yielding was right |
| CYCLIC | 769→664 | 0 | — | — | — | — | NATIVE_SELLABILITY_REJECT=213, CYCLIC_SCORE_BELOW_FLOOR=128, PRICE_NOT_FRESH=21; READY=134 never elected (SHADOW_NOT_PRIMARY n99/−0.1%) | loses election to primary lanes; refused set went nowhere (−0.1%) |
| EXPRESS | 115→103 | 0 | — | — | — | — | NATIVE_LANE_LIQ_BELOW_FLOOR=1549, NATIVE_NO_CONTINUATION=1401 (veto audit: NO_CONTINUATION refused set +9.8% / exec +6.5% — the refusal is costing), LIQ_UNHYDRATED_DEFER=931; Cortex INVERTED | **native NO_CONTINUATION refuses winners** — next target with the chase/continuation record |
| PROJECT_SNIPER | **1**→1 | 0 | — | — | — | — | NATIVE_PHASE_HANDOFF=1539, NATIVE_BIRTH_METADATA_HYDRATING=1098 (launch tape RECOVERY_BIRTH_UNKNOWN_8168=194), SNIPER_TERMINAL=243 | best record in the book (SPECIALIST_EDGE n56 +206%, LADDER n262 +131%) and it sees one candidate: birth metadata arrives after the phase has handed off |
| MANIPULATED | 20→10 | 0 | — | — | — | — | NATIVE_MCAP_TOO_LOW=2043, NATIVE_MCAP_UNHYDRATED_DEFER=1375, TOKEN_TOO_OLD=743, NO_KNOWN_MANIPULATION_EVIDENCE=300 (scanner handed it 4 coins at 0.2–1.2 min, $8–13k) | mcap floor above what discovery hands it; hydration defer |
| TREASURY | 1732→1183 | 0 | — | — | — | — | NATIVE_TREASURY_ROLE_FLOOR=2334 (veto audit −3.4% "ok"), NEUTRAL_MOMENTUM=527, WEAK_MOMENTUM=499 | role floor correct on this tape |
| DIP_HUNTER | 6→0 | 0 | — | — | — | — | NATIVE_NOT_A_DIP=1306, LIQ_RATIO_LOW=593, MCAP_TOO_HIGH=569 | discovery-only; no dips qualified |

Cross-lane: ownerLaneChangedAfterSelection=0, crossLaneExecutionRewrite=0, SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME=0.
FDG block top: DATA_INCOMPLETE_DEFER 577 · SETUP_RECORD_LOSING 379 · CELL_PROVEN_LOSING 315 · TIMING_ADVERSE 291 ·
LIVE_PROVEN_SETUP_ONLY 280 · **AT_LAUNCH_CAP_8047 195** · EXECUTION_ECONOMICS 166 · SIZE_NOT_EXECUTABLE 69.

## Runner capture
`runners>=+400%: caught=0 missed=2 byReason=[AT_LAUNCH_CAP_8047=2]` — MF (SHITCOIN) +2,201%, hllwn (MOONSHOT) +2,343%.
Both passed CURVE_NOBODY_BOUGHT_8046 (real SOL ≥ 3 in the curve) and were refused by the launch-cap PROXY, whose cap
is the API's trusted mcap lagging a curve being bought. **8252**: `OnChainDepth8038.curveBuyersProven8252` — when the
fresh chain reading states ≥ 3 real SOL, the proxy stands down (both buy door and LiveEdgeGate early refusal); the
direct measurement and every later gate stay binding.

## Data completeness — supply vs join
Supply this session: Helius tape 10,972 prints · curve ticks 4,519 (33 subs) · DexScreener 15/15 ok, pair fetches 944 ·
sweep 271 rows · PumpPortal 94 prints · pump.fun REST 530 backoff · Codex none · Birdeye trade-data 11 calls.
Join: features8144 complete 1,184/17,513; core (bp/chg/shape/liq) 7,494/17,513; missing tape 15,011 evals / 235 mints,
swaps 12,653 (tape8100:NO_PRINTS=12,092), bp 5,011 (`primary:DEFAULT_UNOBSERVED|tape8100:NO_PRINTS|dexPair:NOT_FETCHED|
sweep:NOT_LISTED` = curve coins with no pair and no stream).
Root cause: the 4,519 curve ticks reached the tape PRICE-ONLY (`notePrice8169`), so they never stated bp, swaps or
flow, though every accountNotification carries the reserves after a trade. **8252**: `CurveTicks7968.tradeBetween8252`
— two snapshots of the same account are the trade (ΔvSol, ΔvTok → side, SOL, tokens, exact); `DataOrchestrator.
onCurveTrade8252` tapes it (`note8100`, SRC_CURVE) and walks the one tape path (launch tape, chart reader, whales,
candle, buy pressure) unless PumpPortal / Helius delivered the mint inside the 20 s priority window (nothing twice).
Curve subscriptions: near-decision candidates first, 32 → 48 (MAX_SUBS 72 → 88).
Still open: Helius WS log streams capped at 8 (bandwidth/credits — by design); PumpPortal status line truncated in this
snapshot (94 prints — verify the keyed stream after funding); shape for non-curve coins still needs candles (Codex none).

## Other
- 8250 fix not exercised this session (no absent-wallet exit); DqPWFV no longer present.
- `heliusRps8191 waited=1235/1,060,412 ms` — RPC budget contention is real; curve accountSubscribe costs no RPC calls.
- `TAIL_UNFUNDED_NOT_SET_ASIDE_8136=39`, `PENDING_BACKLOG pending=2 (MOONSHOT)` — carry to the next log.
