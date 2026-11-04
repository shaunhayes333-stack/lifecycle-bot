# Audit — timing, runner capture, lanes, Cortex, learning (5.0.8532)

Evidence: 8527 log (85 min live, `9e8568cc…`) and 8529 log (6 min live, `9ed3daba…`). (F) = read from the logs or code,
(H) = hypothesis still to prove. Status: **AUDIT IN PROGRESS — fixes listed are not yet made unless marked DONE.**

## 1. The headline failure: it sees runners and does not buy them

(F) 8527 `Runner capture §8017`: **35 coins ran 4x+ in 24 h, held 2, missed 33.** Funnel: not evaluated 1, **gate refused 27**,
admitted-not-bought 5, sold early 1. The bot's own trace of 200 runners: **wrongly refused 80**, correct at decision 91, data gap 19.

| Missed runner | Run | Door that refused it |
|---|---|---|
| FI | +15,606 % | `NATIVE_CORE_OWN_ENSEMBLE_BELOW` |
| (unnamed) | +2,916 % | `NATIVE_LANE_LIQ_BELOW_FLOOR` |
| VERSUS | +2,552 % | `NATIVE_MCAP_TOO_LOW` |
| buck | +2,497 % | `NATIVE_PHASE_HANDOFF` |
| DEM | +1,569 % | `CORTEX_SHADOW_REFUSE` |
| GfHUct | +897 % | `NATIVE_TREASURY_ROLE_FLOOR` |
| DUMOCRATS | +896 % | `NATIVE_THRESHOLD_FAIL_SCORE` |
| TRIPLET | +909 % | bought — **at +704 % of its run** (banked −101 % of the run) |

Wrong refusals by door (of 80): **`CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136` 28**, `LIVE_PROVEN_SETUP_ONLY_8059` 5,
`ENTRY_NO_PHASE_TRIGGER_8506` 4, `CHASING_SPIKE_8065` 4, `ENTRY_NO_RECLAIM_8506` 3, `NATIVE_MCAP_TOO_LOW` 3, rest 1–2 each.

Entries that were bought: `LOSS_BOUGHT_TOP` 31, `LOSS_GAVE_BACK` 10, `WIN` 11. By peak position at entry: `nearHigh` 6, `atHigh` 6,
`pullback` 1. **The bot buys at the high and refuses the early read.**

## 2. Why the early lanes never fire (8529, live)

| Lane | ready | top refusals |
|---|---|---|
| PROJECT_SNIPER | **0** | `BIRTH_METADATA_HYDRATING` 1,261 · `PHASE_HANDOFF` 90 · `SNIPER_TERMINAL` 57 · `TOO_FRESH` 14 · `PREIGNITION_FLOW_NOT_PROVEN` 9 |
| SHITCOIN | 315 | `LANE_LIQ_UNHYDRATED_DEFER` 1,077 · `NATIVE_AGE_UNOBSERVED_DEFER` 855 · `TOO_OLD` 729 |
| EXPRESS | 337 | `LANE_LIQ_UNHYDRATED_DEFER` 1,077 · `NATIVE_MOMENTUM_UNOBSERVED_DEFER` 502 · `CHASE_EXTENDED_MOM` 326 |
| MOONSHOT | 2,157 | `NATIVE_MCAP_UNHYDRATED_DEFER` 1,381 · `SCORE` 228 |
| MANIPULATED | 30 | `MCAP_UNHYDRATED_DEFER` 1,381 · `TOKEN_TOO_OLD` 1,021 |

(F) The launch feed works while running: PumpPortal socket open, 10,411 frames, 100 create-frame births in 6 min. The
8527 zeros (`frames=0`) were read after the bot stopped.
(F) Data sources down in 8527: **Birdeye 0 % success, pump.fun API 0 %, pump.fun candles 0 %** — the sources for creation
time, cap and early candles of new coins. Birth walks resolved 385 / 2,737 (14 %); 4,458 sniper candidates undated.
(F) The "unhydrated" defer has been patched five times (8162, 8399, 8400, 8439, 8521); each patch added one source to one
lane. The lanes read one stored field each and defer when it is empty.

## 3. Cortex lanes and setups that are damaged (8527)

- (F) CORE setups: **MATCHED n71 −13.3 %** vs NO_MATCH n97 −1.0 % (5-min forward). A fired setup is worse than none.
- (F) Cortex "strong" read negative in nearly every lane: EXPRESS −22.1 % (n68), MOONSHOT −74.0 % (n116), SHITCOIN −35.9 % (n34),
  CORE −10.2 % (n189). TREASURY, CASHGEN, EXPRESS SUSPENDED. CORE and EXPRESS `FILTER_INVERTED`.
- (F) MOONSHOT forward **−65.9 % on n212**; MOONSHOT and EXPRESS share the identical LAUNCH row (n6 / −49.5 %).
  (H) labels booked under several lanes and/or measured from a wrong basis. Must be proven before any setup is re-graded.
- (F) Label marks priced 66 % (8527, steady state); 815 horizons missed after the 8529 restart; downtime recovery gave up 117 / 149.

## 4. Cheat-sheet bands (common sense)

| Lane | Band (USD cap) | Note |
|---|---|---|
| SHITCOIN | 500 – 500k | |
| EXPRESS | 1k – 300k | |
| MANIPULATED | 2k – 300k | |
| MOONSHOT | fluid floor (1.5k boot) – **500k** | was 5M until 8532 (owner) |
| PROJECT_SNIPER | sniper band | 0 ready |
| QUALITY | 75k – 1M | |
| BLUECHIP | 1M – ∞ | |
| CORE / CYCLIC | cheat-sheet constants – 5M | |
| TREASURY / CASHGEN | treasury floor – ∞ | |

## 5. Learning loop

- (F) Live learning is session-scoped (lane exit tuner lifetime 1–4, policy head trained 16): too few closes to steer.
- (F) The 33 missed runners are traced (`admission_runner_traces_8144.jsonl`, `wrongBy`) but no consumer moves the door that
  refused them; the trace is reporting only. (H) — to verify each door's veto-audit (`doorBinds8393`) inputs.

## 6. Fix order (to do, each end to end with tests)

1. Label integrity: cross-lane booking and basis for MOONSHOT / EXPRESS rows (blocks every Cortex number).
2. One input resolver for cap / liquidity / age / momentum shared by every lane, with the full source chain
   (curve → create frame → pool vault → DexScreener → Jupiter → Birdeye → pump.fun) and a health-skipping order.
3. Sniper: create-frame birth as the birth for its own launches; `TOO_FRESH` / pre-ignition rules against the launch record.
4. `STRUCTURAL_RISK_8136`: the 28 wrong refusals — which structural reads fired on runners.
5. Missed-runner trace → door feedback: a door proven wrong on runners loosens through `doorBinds8393`, never by hand.
6. Setup re-grade on clean labels; inverted setups stop counting as buy evidence (rotation, no deletion).
7. Entry timing: buy the early read (BASE / first breakout), refuse at-high entries (D2 remaining-move, done in 8529).

DONE so far: 8529 remaining-move, stop-out lockout, stop quote rule, route demotion; 8532 Moonshot 500k, lockout at the
sell row, minimum-gate defers, trading age from the pool.

## 7. Data chain findings (8533, owner: "helius mate")

- (F) 8529: `discoveryMcap8400 unresolved=1,397` — `NO_VERIFIED_SUPPLY@jupiter_strict=1,050 / @market_hunt=325`; `uncappedBySource{BRIDGE=1,381}` =
  MOONSHOT / MANIPULATED `NATIVE_MCAP_UNHYDRATED_DEFER=1,381`. The cap chain was curve reserves → price × verified supply → (pump only) chain read;
  established DEX coins have no curve and their supply was never verified.
- (F) Supply ladder (8359) ran keyless nodes first: publicnode 575 calls / 13 answered (http503=347), api.mainnet-beta 112 / 97; Helius 100 %,
  74.9k of 400k daily credits used. `requested=131` against ~1,375 misses; the request's silent early returns (resolved / chain-read / in-flight
  gate) had no counters; `notSolanaKey8458=6,731` (shape now counted — EVM ids from the cross-asset desk are the likely source).
- (F) The Helius DAS `getAssetBatch` the mark fan-out already called carried `token_info.supply` / `decimals` for every asset and parsed only the price.
- (F) The sweep row carries `mcapUsd` / `liquidityUsd` and the DexScreener pair carries `fdv` / `liquidity`; the cap chain read neither.
- (F) DexScreener host budget 180/min: `taken{exit=508, near=357, bulk=170} refused{bulk=1,404}` — 508 EXIT-class calls in six minutes for ONE held
  position starved the bulk lane that prices waiting coins. NOT changed in 8533 (exit-time path; the held mark is already chain-sourced); to measure
  the caller (`ExitHttpScope7314.active()` covers every fetch under the exit scope) before coalescing.
- DONE 8533: paid rungs first for market reads (`order8359`), DAS supply kept, bounded DAS supply batch for cap-pending mints, gate TTL + counters,
  provider cap rung (non-curve, ≤15 min) replaced by price × verified supply when it lands, pair `fdv` kept on the flow row.
