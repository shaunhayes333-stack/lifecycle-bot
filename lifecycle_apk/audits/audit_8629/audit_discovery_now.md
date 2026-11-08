# Audit: token discovery and selection, 5.0.8627 (origin/main 2be34b02a)

I read the code only and did not modify the repository. All paths are relative to
`lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.

Evidence used:
- **Runtime log:** `aate_pipeline_20261108_141215_897.txt`. It was captured on build **5.0.8622**, ran for 8,598 s (2.4 h) in LIVE mode, and finished with the heap at 100% after an OOM.
- **Edge dataset:** `aate_edge_dataset_8537_1794111138511.jsonl` from the same session. It has 33,008 forward-labelled lane decisions over 64 h. `age` is in minutes and `net` is the 5-minute executable net %. Its means are wrecked by outliers up to +10^7 %, so I quote medians, clipped means (-100..+300) and the 2x rate.

Builds 8623 to 8627 came after the log. Their two discovery-relevant changes are 8626 (refuse PEAK_EXHAUSTION/DUMPING) and 8627 (refuse coins older than 24 h). Both act at the **live admission gate** (`engine/truth/LaneAuthority8577.kt:137-144`, `:141`). Neither changes what the scanner, watchlist, selector or lanes are offered, so everything below still applies to HEAD.

---

## 0. Bottom line

The intelligence is not the main fault. The fault is upstream:

- **The feeds are momentum lists.** The bot builds its universe from "what already moved" lists: Jupiter top-trending and top-traded, DexScreener paid boosts and profiles, Gecko and Birdeye trending, and Raydium 24 h volume. A coin enters these lists after it has run.
- **Re-sightings keep old coins first in line.** Every time a list serves a coin again, the watchlist treats it as freshly admitted (`addedAt = now`). Coins on those lists therefore stay in the "fresh" tier that is processed first, and they are protected from eviction.
- **New launches are throttled.** Genuinely new launches are rate-limited and parked in probation, ranked by liquidity, and put in a source family that is pinned second-to-last.
- **Every lane sees every coin.** The runner lanes are offered QUALITY, BLUECHIP, CYCLIC and DIP coins and day-old trending coins.

The forward record shows what this costs:

- About 3/4 of what the meme lanes evaluate is PEAK or DUMP stage (log l.54-59).
- The pool loses about 13% over 5 minutes (l.39).
- Coins older than 24 h make about 0.2% 2x moves, against 2.6-2.9% for coins under 5 minutes old (dataset).

The ranking layers on top (V3, EdgeModel, Cortex, scanner score, conviction rank) show no measurable separation of winners. The one learned model that claims separation, `PocketLedger8610`, is used only as a live veto at the last gate, never to choose what gets looked at.

---

## 1. Discovery sources: cadence and share

The intake counts are the log's "Intake by source" (l.405-425). They include repeats, and the total INTAKE count is 7,112. Decision share is the dataset's meme-lane rows (SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER, MANIPULATED, DIP_HUNTER, CORE): 64 h n=19,532, and last 2.4 h n=506. The 5-minute forward numbers are from log l.60.

### 1a. Real-time push feeds

| Source | Code | Cadence | Intake | Decision share | 5-min fwd (log) |
|---|---|---|---|---|---|
| PumpPortal create WS (`PUMP_PORTAL_WS`) | `network/PumpFunWS.kt:925-977` -> `engine/BotService.kt:11047-11151` | push. About 1,000 creates per session (`fromPumpCreate=1041`, l.1628) | 744 (10%) | **38%** (64 h), 38.5% (2.4 h) | n300 -11.8% wr9% run3% |
| PumpPortal migration | `BotService.kt:11157-11190` | push, then 20 s delay and pair read | small | - | - |
| PumpBandEntry8118: a create crossing the $10k-$100k band | `BotService.kt` `bandEntry8118` (just above :25973) | on trade marks | small | - | - |
| ChainLaunchFeed8179: Helius `logsSubscribe` on Raydium, Meteora, LaunchLab, DBC and PumpSwap pool creation, plus Codex | `network/ChainLaunchFeed8179.kt` | push | **0**. `state=BACKOFF`, Helius exhausted (429), Codex quota exhausted (l.1597) | 0 | - |
| PumpPortal per-coin trade stream | `PumpFunWS.kt` | - | **DENIED**: the key's wallet holds less than 0.02 SOL (l.86, l.1592) | - | - |

### 1b. SolanaMarketScanner deep-scan batch

- **Loop:** `engine/SolanaMarketScanner.kt:1471-1711`. It runs every `scanIntervalSecs=8` (`data/BotConfig.kt:254`).
- **Budget:** all sources run in parallel. Each source gets 5 s (`:976`) and the whole batch gets 8 s (`:989`). The batch is assembled at `:1577-1629`.
- **Seen-memory:** 12 s (`:932`), so the same mint can be re-emitted every 12 s.
- **Re-emit limit:** a watched mint is only limited per (mint, source) pair for 5 minutes (`engine/ScannerIntake8292.kt:177-192`).

| Function (label it emits) | Endpoint actually read | Cadence |
|---|---|---|
| `scanPumpFunDirect` (`PUMP_FUN_NEW`) `:1897` | pump.fun frontend, sorted by market cap, replies, last trade and created | every cycle (circuit `:4656`) |
| `scanPumpFunActive` (**`RAYDIUM_NEW_POOL`**) `:1819`, `:1871` | DexScreener **token-profiles/latest** (paid profiles) | every cycle |
| `scanFreshLaunches` (**`RAYDIUM_NEW_POOL`**) `:2137`, `:2190` | DexScreener **token-profiles/latest** | every cycle |
| `scanDexGainers` (**`PUMP_FUN_NEW` / `PUMP_FUN_GRADUATE` / `RAYDIUM_NEW_POOL` by age**) `:2489`, `:2549-2553` | DexScreener **token-profiles/latest** | every cycle |
| `scanDexTrending` (`DEX_TRENDING`) `:2418` | DexScreener **token-profiles/latest** (not a trending list) | every cycle |
| `scanTopVolumeTokens` (`DEX_TRENDING`) `:2765` | token-profiles/latest, then Gecko trending | every cycle |
| `scanDexBoosted` (`DEX_BOOSTED`) `:2589-2594` | DexScreener token-boosts top and latest (paid boosts) | every cycle |
| `scanPumpFunVolume` (`DEX_BOOSTED`) `:2069` | token-boosts/top | every cycle |
| `scanPumpGraduates` (**`PUMP_FUN_GRADUATE`**) `:2641`, `:2690` | token-boosts/top | every cycle |
| `scanRaydiumNewPools` + `scanRaydiumApiNewPools8128` (`RAYDIUM_NEW_POOL`) `:3658`, `:3619` | Gecko new_pools and the Raydium API new pools (the only genuinely new-pool scanners) | every cycle |
| `scanSolanaBlueChipWatchlist` (`SOLANA_BLUECHIP_WATCHLIST`) `:3000` | hard-coded JUP/WIF/BONK/RENDER... | every cycle |
| `scanGeckoTrendingPools`, `TopPoolsByVolume`, `scanMeteoraPoolsViaGecko` (**`RAYDIUM_NEW_POOL`**, `:3893`), `scanCoinGeckoTrending` and `Established` | Gecko and CoinGecko | 1 in 4 cycles each (`:1614-1622`) |
| Birdeye trending, meme list, markets, new listing and narratives | Birdeye | every cycle when the budget allows (`:1623-1629`). `BIRDEYE_SEED_SKIPPED_BUDGET=1014` |

So the DexScreener profiles feed (`token-profiles/latest`) is read by **five** scanners under **four** different labels, and boosts/top by **three** scanners under three labels. Section 5, D6, covers what this does.

### 1c. Resident hunters

The chain is `engine/market/ResidentHunterWorker7807.kt` (cadence 45 s, `:46`), then `MarketSweep7297.sweep`, then `LaneHunter7297.hunt`, then `SolanaMarketScanner.emitResidentHunt7807` (`:2228-2323`).

- **What the sweep reads** (`engine/market/MarketSweep7297.kt:313-331`):
  - Jupiter `/toptrending/1h`, `/toptraded/1h`, `/toporganicscore/1h`, `/recent`, `/toptrending/5m`, `/toptraded/24h`, `/toporganicscore/24h`.
  - Raydium pools sorted by `volume24h`.
  - GMGN smart money.
  - Helius swaps (hourly).
- **Staleness:** each list refreshes every 1, 3 or 10 minutes (`:388-393`), and a failed list serves its last rows for up to **20 minutes** (`CARRY_MS_8124`, `:385`).
- **Picks:** each of 12 lane profiles takes 12 rows (`LaneHunter7297.kt:68`, profiles `:159-295`).
- **Volume:** in this session there were 126 hunts. Hunter delivery emitted 3,999, requeued 710, and found 3,690 already watched (l.1499).
- **Share:** **MARKET_HUNT_\* = 3,624 of 7,112 intakes (51%).** In the dataset: MH_SHITCOIN 9.6%, MH_MOONSHOT 6.3% and MH_MANIPULATED 3.3% of 64 h meme decisions, and MH_MOONSHOT 14.6% of the last 2.4 h.
- **Forward record (log):** MH_SHITCOIN **-32.5%**, MH_MOONSHOT -20.4%, MH_MANIPULATED -21.4%, MH_CORE -8.6%, MH_QUALITY +2.7% (l.60).

### 1d. Other feeds

| Source | Code | Cadence | Evidence |
|---|---|---|---|
| Telegram call sweeper (`TELEGRAM_CALL`) | `engine/market/TelegramCallSweeper7830.kt:48` | 60 s | emitted=25 (l.1514). Dataset n107, 2x 4.7% |
| Groq LLM web scout (`LLM_SCOUT`) | `engine/market/GroqTokenScout7830.kt:62` | 10 min | emitted=3 |
| `TREASURY_FEED_7299` | `engine/TreasuryScannerFeed.kt` (recirculated from hunts) | per hunt | 227 intakes |
| Config watchlist (`WATCHLIST`) | `BotService.kt:26412` | static | 4.5% of meme decisions. Median age at first decision 31 min, p90 345 days |
| Restore on boot: `LIVE_HELD`, `WIN_MEMORY`, `WATCHLIST_HIGH_TOUCH` | `engine/HotConvictionWarmup.kt:100-160`, admitted at `BotService.kt:8406-8427` with confidence 55 | every boot | LIVE_HELD -21.6% at 5 min (l.60) |
| `MEME_REGISTRY_RESTORE` | `BotService.kt:8371-8380` | boot | quarantined (no intake) |
| Probation promotion (`PROBATION`) | `engine/GlobalTradeRegistry.kt:1046-1161` | every cycle | 177 intakes |
| Cross-asset (`XC_*`, `jupiter_strict`, `MULTICHAIN`) | `engine/cortex/CrossAssetCortex7931.kt:167`, `perps/DynamicAltTokenRegistry.kt:1978` | - | these rows appear under the SHITCOIN, MOONSHOT, EXPRESS and CORE lanes in the dataset (n148 SHITCOIN, n91 MOONSHOT...) |

### 1e. Discovery rate

- `discovery new=2075 repeat=5889`, so **newShare is 26%** (last 10 minutes: 21%) (l.85, l.1656). "New" means not seen in the last hour (`engine/truth/DiscoveryRate7984.kt`; noted at `BotService.kt:13839`).
- Top re-intaked symbols (l.457-472): HABIBI 139, KIN 99, USELESS 90, RENDER 81... These are established coins re-served by lists every few minutes.

---

## 2. From discovery to a lane decision

```
feed ─► SolanaMarketScanner.emit/emitWithRugcheck (:4378/:4593; 2 s rugcheck)
      │   or PumpFunWS create ─► BotService onNewToken (:11047)
      ▼
BotService scanner callback (:8487-8605)
      ├─ admitProtectedMemeIntake(...) DIRECT  (:8581)            ◄── real admission
      └─ TokenMergeQueue.enqueue(...)          (:8745)            ◄── echo, source-ranked queue
admitProtectedMemeIntake (:16176-~17000)
  hard-reject / quarantine / held-bypass / safety cache peek (:16222-16437)
  dedupe window (5 min for list re-serves) → "meaningful refresh" ⇒ addToWatchlist again (:16695-16761)
  live cold-pump park: PumpPortal liq<$1.5k & vol=0 → probation (:16593)
     except FreshLaunchIntake8303 witnessed creates ≤180 s, ≤24/min (FreshLaunchIntake8303.kt:41,:91-115)
  source-brain park only if mult<0.65 AND liq<$7.5k AND vol≤0 (:16587)
  ProtectedIntakeAdmissionGate supervisor-pressure park (:16569)
  IntakeFirstSight8317: near-decision priority if chain age <30 min or unknown (IntakeFirstSight8317.kt:35-48)
GlobalTradeRegistry.addToWatchlist (GlobalTradeRegistry.kt:354-620)
  duplicate ⇒ addedAt = now (:416)                                   ◄── D1
  pump share ≥35% of hot ⇒ divert to probation unless "strong" (:146-148, :219-236, :462-483)
  cap 220 (:136): evict oldest-addedAt evictable (WatchlistEviction8398.kt:58-71); all protected ⇒ probation (:573-587)
Probation: min 30 s, promote on +5% / 2nd scanner / RC≥2, timeout 90 s (:117-118, :1046-1161)
Bot loop (avg 6.3 s, l.330) → selectOrderedMintsForCycle (BotService.kt:18705-19036)
  mustInclude = pending live buys + hand-off targets + ≤4 opportunity leaders revisited every 15 s (HunterWindow8293.kt:15-27)
  budget PER_CYCLE_CAP = 48 healthy / 12-24 under pressure (SupervisorAdmissionPlanner.kt:35-57; pressure in 1,168 of 1,363 cycles, l.1843)
  tiers: fresh (now-addedAt<120 s) > unseen (pc=0) > cold (oldest lastProcessed) (:18898-18903, :19022-19025)
  inside each tier: round-robin over families DEX>COINGECKO>CMC>RAYDIUM>BIRDEYE>SCANNER>PUMP>OTHER (:19081-19083; PUMP pinned 2nd-last ScannerDiversityBandit.kt:33-41)
  inside a family: candidateConvictionRank6930 = entryScore + 0.5·conf + ln-liquidity(≤40) + 4·affinity (:19059-19074)
  stale eviction: pc≥96 or untouched 30 min (:18757-18956)
processTokenCycle (BotService.kt:26044) → dex.getBestPair / synth pair from curve (:26172-26280)
  → safety → V3EngineManager.processToken (:~28087) → per-lane gates shouldRunBuyLaneForCycle (:15107)
  → TokenMetricStageRouter stage/laneFit (TokenMetricStageRouter.kt:58-200, :406-457)
  → SpecialistElection8136 primary + ≤1 rescue → FDG/CandidateAdmission8136 → LaneAuthority8577 (8626 stage, 8627 age)
Fast lane: PumpPortal create with estLiq ≥ $2k → processTokenCycle immediately, twice (BotService.kt:11149, :25973-26025)
```

### Hydration

- **Pair, liquidity and mcap** come from DexScreener `getBestPair` within a host budget of 180/min (`network/DexHostBudget8287.kt:31`). BULK requests are refused while fewer than 2 tokens remain (`:41`). Result: **`DEX_HOST_BUDGET_REFUSED_8287_BULK=202,879`** and liquidity `stillUnknown=5,795` (l.1638).
- **Curve and pool depth** come from chain reads (`OnChainDepth8038`).
- **Supply:** resolved 1,523 of 2,009.
- **Birth (age):**
  - PumpPortal creates are dated by their create frame.
  - Everything else goes through birth walks: `launched=6269 resolved=239 noRpcAnswer=949` (l.82).
- **Flow (buy pressure, tape):** the PumpPortal trade tape is denied, and Helius `admission8386` is throttled. `SETUP_FEATURE_MISSING_8084_launchTape=49,771`. The `hydrationReeval8343` line shows `LIQUIDITY_STILL_UNREAD=12,381` (l.1629).

### Latency from launch to first lane decision

Measured on the dataset as the age at a mint's first decision, all lanes:

| First source | n mints | p10 | median | p90 |
|---|---|---|---|---|
| PUMP_PORTAL_WS | 2,228 | 0.1 min | **0.5 min** | 1.9 min |
| MARKET_HUNT_SHITCOIN | 563 | 1.0 min | 2.2 min | 54 min |
| MARKET_HUNT_MANIPULATED | 162 | 0.9 min | 1.8 min | 3.6 min |
| RAYDIUM_NEW_POOL | 162 | 4 min | **45 min** | 5.5 h |
| PUMP_FUN_NEW | 62 | 12 min | 39 min | 2 h |
| DEX_TRENDING | 66 | 30 min | 103 min | 490 days |
| MARKET_HUNT_MOONSHOT | 210 | 1 min | **18 h** | 94 days |
| MARKET_HUNT_EXPRESS | 72 | 1.6 min | **23 days** | 273 days |

- **PumpPortal creates that get a decision get it quickly.** But the first pass usually happens before the inputs exist: the shape needs 3 readings at least 10 s apart (`WatchlistEviction8398.kt:7-12`), and the tape is denied. The fast lane only fires when the estimated liquidity is at least $2k, and a fresh create reads about 0.02 SOL (about $3) real (`FreshLaunchIntake8303.kt:9-12`). Result: **129 fast-lane evaluations in 2.4 h** (l.805), against about 1,000 creates.
- **PROJECT_SNIPER (180 s window):** `pastWindow=7,586` against `inWindow=1,618` evaluations (l.82). Sniper scope is offered stale coins about 4 times as often as in-window ones. Election funnel: PROJECT_SNIPER `D609>Q0` (l.1644).
- **"New pool" labels are not new pools.** RAYDIUM_NEW_POOL and PUMP_FUN_NEW are mostly re-labelled DexScreener-profile coins (section 1b), hence medians of 39-45 minutes.

---

## 3. Why the pool is dominated by peaked, dumping and old coins

**Measured:**
- Stage records (l.54-59): PEAK plus DUMP make up SHITCOIN 73/96 (76%), MOONSHOT 79/102 (77%) and EXPRESS 77/106 (73%). PEAK runs -20 to -52% and DUMP -33 to -40%. LAUNCH is the positive cell: MOONSHOT +114%, EXPRESS +73%, n=5-7.
- Old coins: 505 of 3,073 runner-lane mints are more than 24 h old, but they produce 3,185 of 14,428 rows (22%), about **6.3 decisions per old coin against 3.6 per young coin**.

These are the causes, ordered by contribution.

1. **The feeds are post-move lists by construction.**
   - Jupiter toptrending and toptraded rank coins by a move that already happened (`MarketSweep7297.kt:313-324`).
   - DexScreener boosts and profiles are paid promotion, which typically follows a run (`SolanaMarketScanner.kt:2589-2594`, `:1819`, `:2418`).
   - Gecko and Birdeye trending are the same kind of list.
   - Hunter ranks reward activity, turnover and the 1 h move:
     - SHITCOIN `fits = { true }` and ranks on activity plus turnover (`LaneHunter7297.kt:160-164`).
     - MOONSHOT and EXPRESS rank adds `priceChangeH1/8` (`:201`, `:237`).
     - The only chase guard is a multiplier (`:124-133`), never a filter.

2. **Opportunity leaders are forced into every cycle.**
   - `OpportunitySignal` is a pure momentum score: relative 1 h strength, 5-minute price, volume and tx acceleration, buy pressure (`MarketSweep7297.kt:586-599`).
   - The top 4 with score at least 80 and a "constructive" setup (BREAKOUT_EXPANSION, CONTINUATION, ...; `HunterRepair8293.kt:184-199`) are put into `mustInclude` every 15 s (`HunterWindow8293.kt:22-27`) ahead of the budget.
   - These are, by definition, coins at their 5-minute high. The pocket ledger marks the matching cells as losing: `M|phase=IGNITION&pxPeak>=0.98` -13.8%, `M|bp>=80&chg5m<-10` -13.8% (l.224-236).

3. **Re-sightings keep peaked coins "fresh" and unevictable (D1).**
   - Any re-intake of a watched mint sets `existing.addedAt = now` (`GlobalTradeRegistry.kt:416`). This happens directly, and through the 6566 "meaningful refresh" path (`BotService.kt:16733-16738`).
   - The selector's first tier is `now - addedAt < 120 s` (`BotService.kt:18711`, `:18900`).
   - Eviction needs `now - addedAt >= 60 s` and picks the oldest `addedAt` first (`WatchlistEviction8398.kt:27`, `:62`, `:65`).
   - About 15 sources can each re-serve a coin every 5 minutes (12 MARKET_HUNT lanes, DEX boosted, profiles, bluechip, treasury feed). A coin on those lists is therefore nearly always "fresh" and never the eviction victim.
   - A one-off pump launch that nobody re-serves ages out of the fresh tier after 2 minutes and becomes the LRU victim. The bias is structural and favours exactly the coins already on trending lists.

4. **Every lane evaluates every coin in the cycle.**
   - Hunt rows for QUALITY, BLUECHIP, CYCLIC, DIP and CORE (established coins by mandate) are offered to the runner lanes too.
   - Sources of the 3,319 runner-lane decisions on coins more than 24 h old: MH_MOONSHOT 17%, **MH_QUALITY 10%**, MH_EXPRESS 9%, DEX_BOOSTED 8%, WATCHLIST 8%, LIVE_HELD 6%, XC cross-asset 6%, **MH_CYCLIC 6%**, DEX_TRENDING 5%, **MH_DIP 5%**, MH_CORE 3%, MH_BLUECHIP 3%.
   - The MOONSHOT and EXPRESS hunters have **no age ceiling**: `ageBandMin8323` falls to `else -> 0 to +inf` (`engine/cortex/LaneCheatSheet8323.kt:254-269`). The cheat sheet's own `idealMaxAgeHours8323` (MOONSHOT 1 h, EXPRESS 0.2 h, `:290-296`) is used only as a 1.12x/0.88x rank multiplier (`LaneHunter7297.kt:135-140`).

5. **Ranking favours size over freshness or stage.**
   - `scoreToken` (`SolanaMarketScanner.kt:3954-4024`) gives +15 for h1 above +100%, +45 for $25k-100k liquidity, and only +5 for age under 30 minutes (against +20 at 2-6 h).
   - That score becomes intake confidence, which feeds `candidateConvictionRank6930` (`BotService.kt:19059-19074`): liquidity up to 40 points plus 0.5 x confidence. No stage or age term exists.
   - PumpPortal creates enter at confidence 22-45 (`BotService.kt:11121-11126`) with about $3-1.5k of liquidity.
   - The PUMP family is pinned second-to-last in the round-robin regardless of its record (`ScannerDiversityBandit.kt:33-41`).

6. **Fresh launches are throttled and parked.**
   - Pump may hold at most 35% of the hot list (`GlobalTradeRegistry.kt:146-148`, `:219-236`). Above that, a create goes to probation (min 30 s, timeout 90 s).
   - FreshLaunchIntake8303 lets only 24 creates per minute go hot (`:41`).
   - LaneParticipation8029 evicts pump coins first when they exceed 50% (`GlobalTradeRegistry.kt:542-547`).
   - The create throttle raises the minimum create mcap to 25 SOL whenever the watchlist has at least 200 entries (`PumpPortalThrottle.kt:41-68`).

7. **The loop is too slow for the pool size.**
   - avg cycle 6.3 s, max 44.7 s; `workerTimeout=1999`; heap at 100% with OOM (l.3, l.28, l.330, l.339).
   - Under pressure the selector gets 12-24 slots per cycle for a 220-coin list (`SupervisorAdmissionPlanner.kt:44-57`).
   - DexScreener bulk hydration was refused 202,879 times.
   - Result: a new coin reaches complete inputs several cycles after admission, while the repeats are already hydrated and win the slots.

8. **The launch-stage inputs are missing.**
   - The chain launch feed is down (Helius and Codex exhausted).
   - The PumpPortal trade tape is denied.
   - Many launch decisions therefore have `phase=?`/UNKNOWN. In the dataset, 41% of the last 2.4 h rows have no `pxPeak`. Those rows run -25.5% clipped mean with wr 4%.

---

## 4. The intelligence that ranks candidates, and whether it separates winners

| Layer | Where it acts | Evidence of separation |
|---|---|---|
| Scanner `scoreToken` + AI source boost + ScannerLearning + ScannerSourceBrain multiplier | `SolanaMarketScanner.kt:3954`, `:4409-4447` (shapes intake confidence only) | ScannerLearning WRs are 18-43% (l.1528-1533). The score rewards the 1 h chase (+15 above +100%). In the dataset, chg1h of 200% or more has median -22.5% |
| Hunter profile rank x brain x opportunity x commonSense x timing x ExpertTrader x KineticFlow | `LaneHunter7297.kt:518-547` | Hunt sources lose -8.6 to -32.5% at 5 min (l.60). MH_QUALITY is the only positive one (+2.7%) |
| OpportunityIntelligence7777 (momentum score, top 16, ≤4 forced per cycle) | `MarketSweep7297.kt:525-635`, `HunterWindow8293.kt` | no forward record is published. The matching pocket cells lose (see §3.2) |
| `candidateConvictionRank6930` (selector order) | `BotService.kt:19059` | no stage or age input. It favours liquidity, while the paying pockets are `liq<10k` (l.206-208) |
| V3 score (`V3EngineManager.processToken`) | `BotService.kt` processTokenCycle (V3 phase), FDG floor `CANONICAL_V3_SCORE_FLOOR_7243` (389 blocks, l.379) | dataset (last 2.4 h): v3 0-9 wr8% med -5.0; 10-29 wr33% med -2.9; **30-59 wr7% med -23.2**; 60+ n4 wr0%. Non-monotonic over 64 h as well (v3 60-100 wr 0-16%, 2x 0%) |
| EdgeModel8537 `pred` | FDG/admission | dataset 64 h: pred ≥ +5 wr12% 2x1.7% med -3.5 against pred < -5 wr12% 2x1.8% med -4.3. **No separation.** Log `oosPicks`: picks worse than "all" for SHITCOIN (-100.6 vs -76.1), TREASURY and CASHGEN; better only for EXPRESS (l.36) |
| Cortex7885 (conviction, entryRefusal) | FDG and sizing | graded only **127 of 9,086** assessed; label marks priced 7% (l.81). TREASURY and CASHGEN are **INVERTED** (strong cohort worse than refused); every other lane is SUSPENDED or INSUFFICIENT (l.276-301) |
| LanePlaybook7907 / LaneCheatSheet8323 setups | LiveEdgeGate | the live setups listed are nearly all negative (l.1655, `liveTruth8064`) |
| PocketLedger8610 (cells by phase, age, liquidity, mcap, bp, chg, pxPeak, top) | **live admission only** (`CandidateAdmission8136.kt:1501`, `LaneAuthority8577.kt:61-66`, `:212`) | its replay claims picks at +19.9% ±5.1. The paying cells are fresh micro-caps: `EXPANDING&liq<10k` +50.7% t=3.95; `age<1m&liq<10k` +23.7%; `PRE_IGNITION&mcap<50k` +28.3% (l.206-220). **It is never used to choose or order what gets evaluated** |
| Admission doors and forward record | FDG | admitted n300 -12.7% wr18% run5% against refused n300 -12.8% wr10% run2% (l.39). No mean separation, a small run-rate separation, and coverage of 37%/8% makes this UNRELIABLE |
| Runner capture | - | 140 coins ran 4x+ in 24 h. Only 2 were not evaluated; **109 were gate-refused** (top reasons NATIVE_CORE_OWN_ENSEMBLE_BELOW 35, NATIVE_MCAP_TOO_LOW 18; l.1628). Of the 63 live runner admits, LOSS_BOUGHT_TOP=40 |

**Verdict.** None of the ranking layers measurably separates winners from losers in the forward record. The "intelligence" mostly re-orders a pool that is ~75% PEAK or DUMP, so even a perfect lane scorer would see few good coins.

The signals that do separate are simple and upstream:
- age under 5 minutes, or under 3 h with chg5m of 30% or more;
- liquidity under $10k;
- EXPANDING or PRE_IGNITION phase;
- `pxPeak` between 0.80 and 0.95 (pulled back 5-20%: last 2.4 h n49, clipped +23.6%, wr45%, 2x 12%).

Two of these (`pxPeak 0.8-0.95`, `age<1m & liq<10k`) are the opposite of what the selector and hunters reward.

---

## 5. Defects at their source, with proposed fixes

Ranked by trading impact.

### D1. A re-sighting resets `addedAt`, so repeats monopolise the "fresh" tier and cannot be evicted (highest impact)

- **Source:** `engine/GlobalTradeRegistry.kt:416` (`existing.addedAt = now`); `engine/BotService.kt:16733-16738` (the 6566 refresh calls `addToWatchlist` again); the selector's fresh tier at `BotService.kt:18711`, `:18900`; eviction at `engine/WatchlistEviction8398.kt:27`, `:62-65`.
- **Effect:** newShare 26%. Trending, boosted and hunted coins (mostly PEAK or DUMP) get re-evaluated every few cycles, while one-off launches lose their slot after 120 s and become LRU victims. Old coins get about 1.8x the decisions of young ones.
- **Fix:**
  - Make `addedAt` immutable (first admission) and add `lastSightedAt` for re-serves.
  - Fresh tier = first admission under 120 s **and** a resolved chain age under the runner window (or a witnessed create).
  - Evict by `lastEvaluatedAt`, plus "no new evidence since last evaluation".
  - Re-select a cold coin only when its evidence changed. Use a monotone feature revision (`FeatureRevision8283` already exists), a stage change, or a price move of more than X% since its last evaluation, not a re-listing.

### D2. Selection and hunting are blind to stage and age; the stage veto sits only at the live gate

- **Source:**
  - `BotService.kt:19059-19074` (rank = liquidity + score + confidence + affinity) and `:19081-19083`.
  - `ScannerDiversityBandit.kt:33-41` (PUMP pinned second-last).
  - `SolanaMarketScanner.kt:3954-4024` (`scoreToken` rewards h1 above +100% and big liquidity).
  - 8626/8627 act only at `LaneAuthority8577.kt:137-144`.
- **Effect:** about 3/4 of lane evaluations are PEAK or DUMP; the 12-24 per-cycle slots go to them.
- **Fix:**
  - Order each tier by a learned value: the `PocketLedger8610` clipped-t score, which it already computes per coin (`verdict8610` at `PocketLedger8610.kt:124`), computed for the `market` key.
  - Add a stage prior from the lanes' own stage records (TokenMetricStageRouter stage).
  - Move PEAK_EXHAUSTION and DUMPING coins to a "reclaim watch" list. Re-evaluate them only on a reclaim trigger (`entryWait8506` already names NO_RECLAIM, `TokenMetricStageRouter.kt:469-475`) instead of every cycle.
  - Let the bandit learn the PUMP family's position from forward labels instead of pinning it.
  - Drop the h1 chase bonus and the liquidity hump from `scoreToken`, or retire `scoreToken` as confidence.

### D3. Runner lanes are offered established coins: every lane evaluates every coin, and MOONSHOT/EXPRESS have no age ceiling

- **Source:**
  - `LaneCheatSheet8323.kt:254-269` (MOONSHOT and EXPRESS fall to `0..+inf`; `idealMaxAgeHours8323` at `:290-296` is used only as a rank multiplier in `LaneHunter7297.kt:135-147`).
  - `laneEvaluationAllowed7951` (`BotService.kt:15130+`) does not scope runner lanes by age or by hunter lane.
  - Hunt rows carry `laneAffinity`, but every lane still evaluates them.
- **Effect:**
  - MH_MOONSHOT median age at first decision is 18 h, and MH_EXPRESS 23 days.
  - 22% of runner-lane rows are on coins more than 24 h old. Those coins made 0.2% 2x moves (2 in 1,541 per 8627; 7 in 3,319 in the 64 h set).
  - QUALITY, CYCLIC, DIP and BLUECHIP hunt rows feed 24% of the old-coin runner decisions.
- **Fix:**
  - In `laneEvaluationAllowed7951`, skip SHITCOIN, MOONSHOT, EXPRESS, PROJECT_SNIPER and MANIPULATED for coins whose birth age (or proven lower bound) exceeds the lane's cap. Suggested caps: 1-6 h (SHITCOIN 6 h per `ageBandMin8323`, MOONSHOT 1-3 h, EXPRESS 0.2-1 h).
  - Give `ageBandMin8323` real ceilings for MOONSHOT and EXPRESS so the hunters' `hunterFits8323` filters them.
  - Do not let a row hunted for an established lane (QUALITY, BLUECHIP, CYCLIC, DIP_HUNTER, TREASURY, CASHGEN) reach the runner lanes.

### D4. Hunters and opportunity leaders sample trending lists and force top-of-move coins into every cycle

- **Source:**
  - `MarketSweep7297.kt:313-324` (1 h, 5 m and 24 h toptrending and toptraded) and `:385` (rows carried up to 20 minutes).
  - `LaneHunter7297.kt:160-164` (SHITCOIN `fits={true}`) and `:186-239` (MOONSHOT and EXPRESS rank on the 1 h move).
  - `HunterWindow8293.kt:22-27` plus `HunterRepair8293.kt:184-199` (top-4 momentum leaders forced every 15 s).
- **Effect:**
  - MARKET_HUNT is 51% of intake. MH_SHITCOIN -32.5%, MH_MOONSHOT -20.4% and MH_MANIPULATED -21.4% at 5 min.
  - The paying pockets are the opposite shape (pulled back from the peak, young, small).
- **Fix:**
  - **Runner-lane hunters:** draw only from `JUP_RECENT`, new pools and creates. Add hard chase filters in `fits`: h1 at most about +60%, chg5m at least -5%, and the row's price/peak below 0.95 when known. Require row age under 2 minutes; do not serve carried rows to runner lanes.
  - **Opportunity leaders:** gate on their own forward record (open a forward cell `OPP_LEADER|setup`). Until it is positive, take them out of `mustInclude`.
  - **Throttle by the source's record (D7):** cap each MARKET_HUNT_\* lane's emissions to its forward record.

### D5. The fresh-launch path is starved: the fast lane never fires, pump is share-capped, and the inputs arrive late

- **Source:**
  - `BotService.kt:11149` (fast lane only when the estimated liquidity is at least $2k; a create is about $3 real, per `FreshLaunchIntake8303.kt:9-12`).
  - `GlobalTradeRegistry.kt:146-148`, `:219-236`, `:462-483` (35% pump share, then probation).
  - `FreshLaunchIntake8303.kt:41` (24/min).
  - `PumpPortalThrottle.kt:57-68` (25 SOL minimum when the list is full).
- **Effect:**
  - 129 fast-lane evaluations against about 1,000 creates.
  - The sniper sees mostly past-window coins (7,586 against 1,618).
  - The best 2x source (PUMP_PORTAL_WS 2.7-3.6%; tail-proven `P|SHITCOIN|grad<25|src=PP` n30 +978%, l.1653) competes for one family slot.
- **Fix:**
  - Fast-lane trigger = `FreshLaunchIntake8303.freshCreate8303` (a witnessed create inside the window and under budget), not estimated liquidity.
  - Exempt 8303-hot creates from the 35% pump share. The share cap was meant to stop dust, which the cold-pump park already handles.
  - Schedule a second and third evaluation at +15 s and +30 s so that shape and buy pressure exist (the fast lane already does one +1.5 s pass at `:25993`).
  - Keep the create throttle at 10 SOL regardless of list size.

### D6. Source identities are mislabelled and the same feed counts as several "independent" sources

- **Source:**
  - DexScreener `token-profiles/latest` is read by `scanDexTrending` (`DEX_TRENDING`, `SolanaMarketScanner.kt:2418`), `scanDexGainers` (`PUMP_FUN_NEW`, `PUMP_FUN_GRADUATE` or `RAYDIUM_NEW_POOL` by age, `:2549-2553`), `scanPumpFunActive` (`RAYDIUM_NEW_POOL`, `:1871`), `scanFreshLaunches` (`RAYDIUM_NEW_POOL`, `:2190`) and `scanTopVolumeTokens` (`DEX_TRENDING`).
  - `token-boosts/top` is read by `scanDexBoosted`, `scanPumpFunVolume` (`DEX_BOOSTED`) and `scanPumpGraduates` (`PUMP_FUN_GRADUATE`, `:2690`).
  - Meteora pools are labelled `RAYDIUM_NEW_POOL` (`:3893`).
  - `ScannerSourceTags8292.independentSourceCount8292` (`ScannerIntake8292.kt:47-51`) counts the labels as separate feeds.
- **Effect:**
  - "Multi-source confirmation" is manufactured from one feed. It hot-admits rows (`BotService.kt:16586`, `GlobalTradeRegistry.hasNonPumpConfirmation`) and promotes probation rows (`MULTI_CONFIRM`).
  - Source learning (ScannerSourceBrain, ScannerSourceQuality8292, ScannerLearning) is attributing to fictional sources. For example, RAYDIUM_NEW_POOL has median age 45 minutes and PUMP_FUN_NEW WR 18%.
- **Fix:**
  - One reader per endpoint, with honest enum values: `DEX_PROFILES_LATEST`, `DEX_BOOSTS_TOP`, `DEX_BOOSTS_LATEST`, `GECKO_NEW_POOLS`, `RAYDIUM_API_NEW_POOLS`, `METEORA_POOLS`, `PUMPFUN_FRONTEND_<sort>`.
  - Map aliases of the same endpoint to one source in `independentSourceCount8292`.
  - Re-key the learned source tables after relabelling.

### D7. A proven-losing source is never actually throttled

- **Source:**
  - `ScannerSourceQuality8292` only re-ranks the TokenMergeQueue (`ScannerIntake8292.kt` doc block). But the scanner callback admits **directly** at `BotService.kt:8581`, before the merge-queue enqueue at `:8745`, so the ranking is moot.
  - The admission park needs `mult < 0.65 && liq < 7.5k && vol <= 0` (`BotService.kt:16587-16588`), which a hunted or listed coin with liquidity and volume never satisfies.
- **Effect:** MH_SHITCOIN (-32.5%), MH_MANIPULATED, RAYDIUM_NEW_POOL and LIVE_HELD (about -21%) keep full intake share.
- **Fix:**
  - Put a per-source token bucket at `SolanaMarketScanner.emit` (`:4378`), sized from the source's forward record: proven-losing sources get a probe rate of, say, 1 per minute, and paying sources are uncapped.
  - Drop the liquidity and volume conditions from the source-brain park for proven-losing sources.

### D8. Restored and held rows re-enter as discovery candidates on every boot

- **Source:** `HotConvictionWarmup.kt:100-160`, which seeds LIVE_HELD (conviction 1000), WIN_MEMORY, and WATCHLIST_HIGH_TOUCH. The last of these is ranked by **processCount**, i.e. the most-repeated and oldest coins. They are admitted with confidence 55 at `BotService.kt:8406-8427`, with a 24 h TTL (`:37`).
- **Effect:** LIVE_HELD runs -21.6% at 5 min (log) and WATCHLIST_HIGH_TOUCH runs median -3.3% with 2x 0.5%. These rows feed 8% of the old-coin runner-lane decisions.
- **Fix:**
  - Restore held positions to the exit supervisor only (they are not entry candidates).
  - Drop WATCHLIST_HIGH_TOUCH.
  - Let WIN_MEMORY into established lanes only, and only with a fresh price move.

### D9. Throughput and hydration starvation (amplifies D1-D5)

- **Source:**
  - avg cycle 6.3 s, `workerTimeout=1999`, heap 100% with OOM in `ExploreShadow8347` (l.3-17).
  - `DexHostBudget8287.kt:31`, `:41` (BULK refused 202,879).
  - Five scanners poll the same DexScreener feed every 8 s cycle (D6).
  - Selector cap 12-24 under pressure.
- **Effect:** new coins wait several cycles for pair, liquidity, shape and tape. By then they are past the launch window, while repeats are already hydrated.
- **Fix:**
  - Collapsing D1 and D6 removes most repeat work.
  - Give every first sighting `NEAR` priority (IntakeFirstSight8317 already marks under-30-minute coins; extend it to a guaranteed first pair fetch).
  - Cut the hot watchlist (220) to what the loop can cycle at least every 30 s.
  - Fix the OOM (`ExploreShadow8347.netsSinceUncached8398`).

### D10. Missing launch inputs (owner action plus a fault)

- **PumpPortal:** the keyed trade stream is denied because the key's wallet holds less than 0.02 SOL (l.86). Fund that wallet.
- **ChainLaunchFeed8179:** stood down (Helius exhausted, Codex quota exhausted, l.1597). Raydium, Meteora and LaunchLab launches therefore arrive only via aggregators, 45 minutes or more late.
- **Birth walks:** resolve 3.8% (239 of 6,269).
- **Fix:** fund the key, and give the chain feed a keyless or public-RPC fallback rung, as KeylessRpc8359 does for reads.

### D11. The ranking intelligence does not separate winners but still gates and orders (low direct impact, high noise)

- **Source:** V3 score floor (`CANONICAL_V3_SCORE_FLOOR_7243` 389 blocks), EdgeModel `pred` (no separation), Cortex (graded 127 of 9,086; INVERTED on TREASURY and CASHGEN), `scoreToken` and `candidateConvictionRank6930`.
- **Fix:**
  - Stop using any of these as a rank or gate until it shows out-of-sample separation (2x rate, or median net by decile) on at least 300 labels.
  - Fix label pricing first (7% priced, l.81).
  - Make the pocket ledger (the only layer with a positive held-back replay) the single ranker used upstream (D2), with its OOS check published in the brief.

### D12. Cross-asset rows are evaluated in meme lanes (minor)

- **Source:** `XC_*` rows (`CrossAssetCortex7931.kt:167`) and `jupiter_strict` rows (`perps/DynamicAltTokenRegistry.kt:1978`) appear under SHITCOIN, MOONSHOT and EXPRESS in the edge set (n148 SHITCOIN, n91 MOONSHOT, n37 EXPRESS).
- **Fix:** exclude non-meme asset classes from meme-lane evaluation (`HunterAssetClass8169` already classifies them for hunters).

---

## 6. Suggested order of work

1. **D1:** make `addedAt` immutable, with evidence-gated re-selection.
2. **D3:** add an age scope for runner lanes at `laneEvaluationAllowed7951`, and age ceilings in `ageBandMin8323`.
3. **D2:** use the pocket ledger plus a stage prior as the selector rank, and send PEAK and DUMP coins to a reclaim watch.
4. **D5:** trigger the fast lane on a witnessed create, exempt it from the pump share, and schedule two re-passes.
5. **D4 and D7:** runner hunters draw only from recent and new rows, add chase filters, gate opportunity leaders on their record, and add per-source budgets at emit.
6. **D6:** give each source an honest identity and dedupe endpoints.
7. **D8, D9, D10:** fix the restore path, throughput and the OOM, and fund the PumpPortal key.

How to measure the result:
- the PEAK plus DUMP share of meme-lane evaluations (target under 35%);
- newShare (target over 60%);
- the 2x rate of evaluated coins;
- sniper `inWindow/pastWindow` above 1;
- the forward mean of admitted against refused coins.
