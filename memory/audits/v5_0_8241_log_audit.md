# 5.0.8241 runtime log audit (aate_pipeline_20261020_120503, 221 s, LIVE, wallet 0.2211 SOL)

Operator principles (binding):
- One clear admission path once a trade is selected; multi-strategy appraisal on discovery; the strategy with the
  proven forward edge is selected and traded. Safeties (hard safety, sellability, liquidity, economics, risk) stand.
- AATE is a council of brains: one brain's objection is advisory in the last mile; abandon only when all brains,
  the Cortex and the FDG say no. Hard safety / economics / route are not council members.
- Runner capture already identifies early runners; 1000–20,000% runners must be caught regularly.

## Findings (source-confirmed) and fixes
| # | Evidence in log | Root cause | Fix |
|---|---|---|---|
| 1 | 62 LIVE ADMITs, 0 FDG allows; ZFROG +129,601%: natives MOONSHOT 80 / SHITCOIN 37, admission ADMIT@SHITCOIN, FDG ran as MOONSHOT → DATA_INCOMPLETE_DEFER; trace8144 wrongly_refused=33 (QUALIFIED_LANE_STOPPED=31) | Owner election (`LaneExecutionCoordinator.electionScore7803`) by native conviction; CandidateAdmission is per-lane and never informed the election | **8243** `StrategyElection8243`: tiers ADMIT-specific > unjudged > specific REFUSE, conviction breaks ties; rescue slot marked proven |
| 2 | CUyZ +1774% CHASING_SPIKE after ADMIT; RIPPOOOR +513% FRESH_LAUNCH_SETUP_STOPS_FIRST after qualified | Lane-prior heuristics re-vetoed a candidate-specific ADMIT | **8243** spike rule and learned fresh-launch prior stand down for ADMIT-specific (structural refusals stay) |
| 3 | Trunk path: Field Manual / TradePlan / Council each could block alone | veto chain | **8244** unanimous refusal only; dissent counted `FDG_COUNCIL_DISSENT_*_8244` |
| 4 | CellProofLadder7731 re-read the cell prior after admission | duplicate prior | **8244** stands down for ADMIT-specific |
| 5 | dexscreener `backoff 418s (429)`, OHLCV_SEED_SKIPPED_PROVIDER_COOLDOWN=299, shape/chg unobserved | 429 → 10 min first-strike rest | **8244** 1 min, doubling, 30 min cap |
| 6 | MONPAD +30584%, KimWhale +2962% CELL_PROVEN_LOSING with SUPPORTED setup | cell prior over setup | **8240** (+ test alignment 8245) |
| 7 | ZFROG/CopyPad DATA_INCOMPLETE while RunnerGrab7967 conditions held | grab consulted only after admission | **8246** grab = admission evidence (`runnerGrab8245`, `grabSupported8245`) |
| 8 | Live book N=21 5W/16L pf 0.62; losses were generic-prior admits | V3 floor 15 admits with no record | **8238** live needs proven evidence |
| 9 | Shadow book closes=8 wins=0 avg −29.8% | unobserved entry basis | **8239** ShadowBasisTruth |
| 10 | Cortex CORE STRONG mean +27.5% median negative | in-sample means | **8238** walk-forward + positive median |

## Still open (not fixed in code)
- PUMP_TOKEN_TRADE_STREAM_UNAVAILABLE_AUTH=2 → operator funded the PumpPortal key wallet (verify PUMP_TRADE_EVENT rises).
- Helius throttled: waited 393 s total, provider429=16, refusedLocal=193; tape prints helius=152 only → bp/chg/tape
  missing for DEX tokens (features complete 1091/4804). Needs a tx-stream source for non-curve tokens.
- `dexPair:NOT_FETCHED`=1131: DexScreener pair never fetched for bp/chg (PairFlow8169 passive). DataDeferFetch8180
  request path should fetch the pair when bp/chg missing — verify after 8244 backoff change.
- SUPERSEDED election terminals (SHITCOIN X124/Q291, CORE X158): version buckets roll before FDG judges; records
  re-register next bucket — telemetry noise, not loss, unless FDG never reaches the mint.
- Historical BUY|PUMPPORTAL landRate 50% (sent 14, landed 7), SELL|RAYDIUM 0/2 — fee/tip escalation unverified.
- OPENROUTER success=0 failure=6; coinmarketcap/cryptocompare/moralis/solanatracker/codex/bitquery keys missing.
- JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647=1, WALLET_TOKEN_2022_OPTIONAL_FAILED=2 (one-offs).
- Exit/hold side council principle not yet applied (stop authority / ProfitExitTruth / Cortex exit hold act alone).
- Running APK 5.0.8241@08c9cb099 is from `repair/8239-trade-quality` (failing CI); none of 8237–8246 are in it.
