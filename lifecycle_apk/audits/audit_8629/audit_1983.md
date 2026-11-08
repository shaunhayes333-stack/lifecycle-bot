# Audit: how APK 5.0.1983 (code V5.9.66, commit `98faee2e9`, 2026-04-19) actually traded

All references are to the code at commit `98faee2e9`. Paths are relative to
`lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/` (abbreviated `L/`). Line numbers are from that commit.
Config defaults come from `L/data/BotConfig.kt`.

Read this first. The report describes what the code did, including several places where it did
something other than what its comments say. Those places are marked **[EFFECTIVE BEHAVIOUR]**.
Some of them are probably part of why 1983 let winners run (see §6.0 and §9).

---

## 0. Top-level runtime shape

| Loop | Where | Cadence |
|---|---|---|
| Market scanner `scanLoop()` | `L/engine/SolanaMarketScanner.kt:943-1053` | Runs 12 source scans one after another, then sleeps `max(scanIntervalSecs*1000, 10_000)` = **10 s**, because `scanIntervalSecs=8` (BotConfig:148). A full cycle is therefore 10 s plus the time the HTTP calls take. |
| Bot loop `botLoop()` | `L/engine/BotService.kt:2352-3515` | Each pass: merge queue, then probation, then chunked per-token processing (**15 s batch budget, 2.5 s per token, 2-4 tokens in parallel**, BotService:3382-3390), then `delay(pollSeconds=8 s)` (:3507). A pass takes about **8-23 s**. |
| Rapid stop monitor | `BotService.kt:2247-2350` | Every **500 ms** over all open positions, using the last stored price. |
| PumpPortal / Helius / DexScreener WebSockets | `L/engine/DataOrchestrator.kt:56-66` | Event-driven. |
| Watchlist cleanup | `BotService.kt:2555-2564` → `cleanupWatchlist()` :3527 | Every 5 loops. |

Price source for every decision: `DexscreenerApi.getBestPair()` with a **45 s per-instance cache**
(`L/network/DexscreenerApi.kt:33`). Calls are throttled by a global `RateLimiter` of **300 req/min, 25 ms spacing**
(`L/engine/RateLimiter.kt:31`). The scanner and the bot loop share that limit.

---

## 1. Token discovery

### 1.1 Scanner sources, in execution order (`SolanaMarketScanner.kt:974-999`)

| # | Function (line) | Endpoint | Per-cycle caps | Hard filters | Score bonus | `TokenSource` |
|---|---|---|---|---|---|---|
| 1 | `scanPumpFunDirect` (1129) | `frontend-api.pump.fun/coins`: 5 calls (newest 0-100, newest 100-200, last-trade 100, mcap 50, replies 50) | up to 100 coins per URL, **30 found per URL** (up to 150) | symbol not blank, **age ≤ 48 h**. The DexScreener pair is optional; without it, liq = `usd_market_cap*0.1` | age <15 min +50, <30 min +45, <1 h +40, <3 h +30, <6 h +20, else +10 | `PUMP_FUN_NEW` |
| 2 | `scanPumpFunActive` (1055) | DexScreener `token-profiles/latest/v1` | 100 checked, 25 found | pair required, **liq ≥ $2,000** | age <0.5 h +35 … >24 h +5 | `RAYDIUM_NEW_POOL` |
| 3 | `scanPumpGraduates` (1621) | DexScreener `token-boosts/top/v1` | 50 / 15 | liq ≥ $2,500 (Birdeye or mcap×10% fallback) | <1 h +30 … +10 | `PUMP_FUN_GRADUATE` |
| 4 | `scanDexBoosted` (1575) | `token-boosts/top/v1` | 12 | liq > 0 (fallbacks) | +15 | `DEX_BOOSTED` (emitted **without** a rugcheck call) |
| 5 | `scanFreshLaunches` (1314) | `token-profiles/latest/v1` | 30 / 15 | **age ≤ 8 h**, liq ≥ $500 | <5 min +40, <10 +35, <15 +30, <30 +25, <60 +20, else +15 | `RAYDIUM_NEW_POOL` |
| 6 | `scanDexTrending` (1400) | `token-profiles/latest/v1` (the same endpoint; it is not a trending feed) | 50 | liq > 0 | none | `DEX_TRENDING` |
| 7 | `scanDexGainers` (1467) | `token-profiles/latest/v1` | 30 / 15 | liq ≥ $2,000 | age bonus | age <6 h `PUMP_FUN_NEW`, <24 h `PUMP_FUN_GRADUATE`, else `RAYDIUM_NEW_POOL` |
| 8 | `scanBirdeyeTrending` (1710) | `public-api.birdeye.so/defi/token_trending` | 40 | skipped when `birdeyeApiKey` is blank, which is the default | none | `BIRDEYE_TRENDING` |
| 9 | `scanTopVolumeTokens` (1752) | `token-profiles/latest/v1` | 50 / 15 | liq ≥ $1,000 | volume bonus | `DEX_TRENDING` |
| 10 | `scanPumpFunVolume` (1239) | `token-boosts/top/v1` | 50 / 15 | liq ≥ $2,000 | +20 | `DEX_BOOSTED` |
| 11 | `scanRaydiumNewPools` (1892) | GeckoTerminal `networks/solana/new_pools` | 30 / 12 | no USDC/USDT pairs | none | `RAYDIUM_NEW_POOL` |
| 12 | `scanCoinGeckoTrending` (1827) | GeckoTerminal `trending_pools` | 30 / 15 | same | none | `COINGECKO_TRENDING` |

`narrativeScanEnabled=false` by default (BotConfig:168).

Five of the twelve scans hit the same DexScreener "latest token profiles" endpoint (1/2/5/6/7/9 hit
`token-profiles/latest`), and three hit `token-boosts/top`. In practice the scanner saw three real
streams: **pump.fun newest/active, DexScreener latest-profiles plus boosts, and GeckoTerminal new/trending pools**.

Raw score (`scoreToken`, :1989-2048) is liquidity tier (5-45, peaks at $25-100K), plus vol/liq ratio (0-25), plus
tx/h (0-20), plus a price-change term (always 0 because 0.0 is passed), plus age (2-20), clamped to 0-100. `emit()` (:2320)
adds the BotBrain source boost (±15) and `ScannerLearning` bonus. **None of this matters downstream**: the only
threshold that uses the score is "score ≥ 1" (BotService:1093).

### 1.2 Dedup and throttling inside the scanner

- `seenMints` TTL **20 s**, `rejectedMints` TTL **8 s** (:717, :720), saturation after 20 cooldown hits lasts 30 s (:724-725).
  A token still on the feed is **re-emitted every ~20 s**.
- `isSeen()` also returns true for any mint in `cfg.watchlist` (:2251). That list is the GlobalTradeRegistry watchlist,
  synced every 10 loops (BotService:2572).
- `EfficiencyLayer.shouldFullProcess` sets a **10 s** re-process cooldown per mint, bypassed by a ≥20% liquidity change,
  a ≥15-point score change or a better source (`L/engine/EfficiencyLayer.kt:57-60, 73-146`).
- Self-heal: if 60 s pass with no new token, the scanner soft-resets (:788-803). If the watchlist has ≤10 tokens, the
  cooldown and saturation maps are cleared (:1024-1032).

### 1.3 WebSockets (`L/engine/DataOrchestrator.kt`)

- **PumpPortal** `wss://pumpportal.fun/api/data`, `subscribeNewToken` (`L/network/PumpFunWebSocket.kt:73-84`).
  `create` events go to `handleNewPumpToken` (DataOrchestrator:262), which runs a Helius creator check when a key is set,
  then calls `onNewTokenDetected`. That callback (BotService:974-987) **adds the mint to `cfg.watchlist` only if
  `autoAddNewTokens && wl.size < 20`**.
  **[EFFECTIVE BEHAVIOUR]** The bot loop trades from `GlobalTradeRegistry`. `cfg.watchlist` is only a fallback when the
  registry is empty (BotService:3335-3341). Every 10 loops the registry overwrites `cfg.watchlist` (GlobalTradeRegistry:764).
  So PumpPortal launches never reached a lane. Two side effects: once the registry held ≥20 tokens, auto-add stopped; and
  until the next sync, a PumpPortal-added mint was hidden from the scanner, because `isSeen` treats watchlist mints as seen.
  PumpPortal contributed **trade events** (whale detection, synthetic candles), not discovery.
- **DexScreener WS** (`io.dexscreener.com`) overwrites `lastPrice`, mcap, liquidity, 5-minute buy pressure and the
  vol/press meta for subscribed tokens (DataOrchestrator:334-368).
  **[EFFECTIVE BEHAVIOUR]** `shouldPoll()` returns false when a WS event arrived in the last **15 s**
  (DataOrchestrator:485-488). The bot loop then **skips `processTokenCycle` for that token entirely**
  (BotService:3407), and that includes open positions.
- Helius WS only runs when a key is configured.

### 1.4 How fast a fresh launch reached a decision (code path)

1. **Scanner sighting**: up to one full scanner cycle (≥10 s plus serial HTTP time). Pump.fun sorted by
   `created_timestamp` returns launches roughly 0-60 s old.
2. `emitWithRugcheck`: rugcheck.xyz summary with a 2 s timeout (:2467). Blocks only `rugged=true` or score 0
   (:2397-2465). On error the token passes.
3. `onTokenFound` (BotService:1044-1172): liq ≥ **$500 paper / $2,000 live** (:1090-1091), blacklist (live only),
   score ≥ 1, then `TokenMergeQueue.enqueue`.
4. **Merge queue**: 5 s merge window, processed at most every 2 s, and **only inside `botLoop`**, which runs every
   8-23 s (`L/engine/TokenMergeQueue.kt:28-31`; BotService:3210).
5. **Confidence and probation** (`TokenMergeQueue.kt:34-47, 224-258`; `GlobalTradeRegistry.kt:265-363`).
   Confidence = best scanner's base + 5 if single-source, or +25/+35/+40 for 2/3/4+ sources, +15 fast-track
   (single BOOSTED/TRENDING with liq ≥ $8K), clamped to 20-95.
   - Base confidences: `DEX_BOOSTED` 60, `DEX_TRENDING` 45, `RAYDIUM_NEW_POOL` 40, `PUMP_FUN_GRADUATE` 35. Everything not
     in the map is 20: **`PUMP_FUN_NEW`, `BIRDEYE_TRENDING`, `COINGECKO_TRENDING`**.
   - Probation applies when conf < **30** (paper, or live with "proven edge") or < **50** (strict live). Strict live also
     sends single-source tokens with conf < 60 to probation.
   - **[EFFECTIVE BEHAVIOUR]** A single-source **pump.fun-new token scores 25, so it always starts in probation, in paper
     too.** It is promoted when it is seen again (any scanner, including the same one, after the 20 s seen-TTL), via
     `addWithProbation` → `promoteFromProbation("MULTI_SCANNER_CONFIRM")` (:292-301). The timer path needs ≥60 s and a
     price/RC update. `updateProbationPrice/RC` are **never called**, so the timer path only ever rejects at 5 min.
   - `RAYDIUM_NEW_POOL` (45) and `DEX_BOOSTED` (65-80) go straight to the watchlist in paper.
6. **First `processTokenCycle`** on the next loop.
7. **Earliest lane entries**: Sniper needs **≥30 s** since watchlist add. Bootstrap force-entry needs ≥15 s. Express
   needs ≥3% momentum, which takes ≥2 price snapshots. Strategy momentum needs ≥8 snapshots.

Typical latency from launch to first possible buy: about **20-60 s** on the Raydium/boost path and about
**45-120 s** on the pump.fun-new path (probation plus re-sighting plus loop cadence). This is reconstructed from code
timings, not measured.

### 1.5 Tokens per minute

- **Discovery**: the per-cycle "found" caps add up to about 380 candidates (150 from pump.fun). The real limit is the shared
  **300 DexScreener calls/min**. Every candidate other than pump.fun-direct needs a `getBestPair`, and when the limiter
  denies a call with no cache, the result is `null` and the candidate is dropped (DexscreenerApi:47-55). Each watchlist
  token also costs about 1 call per 45 s. Ceiling: a few hundred candidates enriched per minute.
- **Evaluation**: every watchlist token is processed every loop (8-23 s, so 2.6-7 passes/min) inside the 15 s budget.
  The watchlist has no size cap (`MAX_WATCHLIST_SIZE=150` is declared but unused, GlobalTradeRegistry:90). With 40-60
  tokens that is about **100-300 full evaluations per minute**, each running V3 plus up to 9 lanes. The price behind
  them refreshes only every 45 s.

---

## 2. Pre-lane filtering and ranking

In order, before any lane sees a token:

| Stage | Rule | Ref |
|---|---|---|
| Scanner `passesFilter` | EfficiencyLayer liquidity suppression and 10 s cooldown, `BannedTokens`, mcap < 0, scam words (`test, fake, scam, rug, honeypot, xxx, porn`), reserved symbols (`sol, wsol, usdt, usdc, ray, jup`), infra-name impersonation. V5.9.34 comment: **"SCANNER IS WIDE OPEN"**. The liquidity floor, non-ASCII filter and typosquat filter had been removed. | SolanaMarketScanner:2073-2185 |
| Rugcheck | Only `rugged` or score 0 blocks. Score 1 (pending) and 2-9 pass. Errors pass. | :2397-2465 |
| AI skip | `TradingMemory.getTokenLossHistory(mint).lossCount ≥ 2` | :2064-2071 |
| Eligibility | liq ≥ $500 paper / $2,000 live, live blacklist, score ≥ 1 | BotService:1090-1110 |
| Registry | duplicate, 20 s rejection cooldown, probation (§1.4) | GlobalTradeRegistry:265-363 |
| Per-cycle data guards | DexScreener pair must exist. **Decimal-corruption filter (V5.9.62)**: on first ingest, implied supply <1 or >1e15 or FDV/MCAP >1000 is rejected; afterwards any tick that jumps >100× or <0.01× is dropped. Pair base-token mismatch guard. Live: `BannedTokens`. | BotService:3690-3779, 3851-3860 |
| Rug learning | price -50% AND liquidity -70% across 3 snapshots: learn the rug and blacklist | :3862-3901 |
| Safety check | `TokenSafetyChecker.check` runs **async**, cached 10 min (:3906-3944). HARD_BLOCK is rugcheck 0 in both modes; in **live**, also mint/freeze authority active or LP lock <50%. Paper turns these into penalties (`L/engine/TokenSafetyChecker.kt:299-420`). HARD_BLOCK blacklists the token, and bans it permanently if the reason mentions rug/honeypot/scam. | |
| HardRugPreFilter | Lenient mode (paper or proven-edge live) blocks **only zero liquidity**, after a 60 s grace. Strict live: fluid liq floor by age ($300→1,500 under 5 min, $500→2,500 under 30 min, $800→3,500 older), top holder >85% (or >70% after 10 min), volume collapse with liq < $5K, average buy ratio < 25%. | `L/engine/HardRugPreFilter.kt` `filter()` |
| DistributionFadeAvoider | Blocks during a **3-minute post-stop-loss cooldown**, or after 2+ distribution hits. Otherwise only score multipliers. | `L/engine/DistributionFadeAvoider.kt` `evaluate()` |
| Blacklist / PAUSED auto-mode / dedupe / fatal suppression | `TokenBlacklist`; AutoMode PAUSED; `TradeLifecycle.canPropose` (15 s between proposals, max 10 per 2 min); `isFatalSuppression` | BotService:4168-4218 |

Computed but **advisory only** (results never gate a buy): `LiquidityBucketRouter`, `ModeRouter.classify`
(the `adjustedModeConfidence` and `finalSizeMultiplier` values are never used), `ModeSpecificScanners.scanAll`,
`SmartChartScanner`, `MomentumPredictorAI`, sentiment refresh (feeds only the legacy strategy).

**Watchlist eviction** (`cleanupWatchlist`, BotService:3527-3680): safety-blocked, blacklisted, idle phase
(>10 min paper / 5 min live with <5 candles), bad phase (`dying`, `dead`, `rug_likely`, `distribution`), stale
(no candle for 5 min paper / 3 min live), live liq < $200, **timeout 30 min paper / 15 min live with no trade**,
`WAIT` signal >5/2 min, live "flat" (<1.5% range and <2 buys over 6 candles).

**Ranking**: the watchlist is sorted by `1000*open + min(liq/1000,100) + entryScore + 0.5*momScore + 0.3*volScore`,
with open positions processed first (BotService:3347-3375).

---

## 3. Lanes and their entry rules

All lanes are evaluated **in sequence inside one `processTokenCycle`**, after a single V3 call. The first lane that opens a
position wins, because every later lane checks `!ts.position.isOpen`. Order (BotService:4339-5675):
**Treasury → Quality → BlueChip → Moonshot → ShitCoin → Manipulated → Express → Sniper → DipHunter → V3-core EXECUTE.**

### 3.1 V3 routing that decides which lanes can fire (`L/v3/V3EngineManager.kt:261-295`)

- `isShitCoinCandidate` = mcap **$1K-$100K** and watchlist age < 6 h (`ShitCoinTraderAI.kt:1148-1151, 64-65, 112`).
  Since the watchlist evicts at 30 min, this is every token under $100K.
- `isV3QualityCandidate` = mcap **$5K-$50K** with at least 3 of: liq ≥ 10% of mcap, buy pressure ≥45%, top holder
  ≤40% (defaults to 20, so true), watchlist age ≥0.5 h (`LayerTransitionManager.kt:228-250`).
- A shitcoin candidate that is not a quality candidate gets `Rejected("SHITCOIN_CANDIDATE")`. Below $5K and not a quality
  candidate gets `Rejected("MCAP_TOO_LOW")`.

**[EFFECTIVE BEHAVIOUR]** Treasury (:4425) and ShitCoin (:5105) both treat **any** `V3Decision.Rejected` as a hard veto,
including the routing reject `SHITCOIN_CANDIDATE` that V3 intends to mean "let ShitCoin handle it" (comment at :5833-5841).
So **ShitCoin and Treasury could only buy tokens that V3 fully scored and returned as Watch, Execute or ShadowOnly**:
$5-50K tokens passing the V3-quality test, or tokens ≥$100K. Moonshot, Quality, BlueChip, Manipulated, Express, Sniper
and DipHunter do **not** check the V3 verdict. They only check `FinalExecutionPermit`, which in **paper is a pure bypass
apart from "position already open"** (`L/engine/FinalExecutionPermit.kt:168-190`). In live it blocks only after a V3
BlockFatal or Blocked.

### 3.2 Per-lane entry rules (p = FluidLearningAI learning progress, §7.1)

| Lane | Mcap band | Liquidity | Age (watchlist) | Signal rule | Ref |
|---|---|---|---|---|---|
| **Treasury** (CashGenerationAI) | any | ≥ lerp($3K→$10K) | none | Score = liq tier 0-25 + buy pressure 0-30 + momentum -10..20 + volatility 0-15 + holder -20..0 + mode -5..10, must be ≥ lerp(15→32). Conf (4×5/15/25) ≥ lerp(30→45). Buy pressure ≥ lerp(35→50). Top holder ≤ lerp(40→25). Momentum ≥ -5. Max 15 positions. **Also needs V3 not Rejected / BlockFatal / Blocked and no coordinated dump.** OR **bootstrap force** (p<0.5: raw score ≥45 paper / 55 live, liq ≥ $5K, bp ≥40/45%, age ≥15 s). | CashGenerationAI.kt:451-760; BotService:4349-4585 |
| **Quality** | $100K-$1M (BotService also needs ≥$100K) | ≥ $5K + p×$12K, capped at $10K | ≥10 min if p<0.5, else **≥30 min** (first candle) | bp ≥ 25+15p, holders ≥ 10+40p if known, top holder ≤30%. Score (mcap/liq/age/bp/v3>20,>40/holders/top) ≥ 24+16p. | QualityTraderAI.kt:212-330 |
| **BlueChip** | ≥ $1M | ≥ lerp($5K→$8K) | none | Score (v3 tier 5-30 + mcap 10-20 + liq 8-20 + bp 5-15 + momentum -10..15 + holder -15..0) ≥ lerp(22→35). Conf ≥ lerp(10→45) + boost. Max 8. | BlueChipTraderAI.kt:421+, 677-688 |
| **Moonshot** | **$10K-$100M** | ≥ $5K (own p<0.5) or $15K | none | **rugcheck ≥10 paper / ≥20 live**. Unchecked tokens default to -1 and fail. Score = mcap tier 12-25 + liq 5-20 + "volume" 0-20 (actually `lastV3Score`) + bp 0-18 (>50%) + v3 entry 0-15 + phase 5-15 + collective ≤20. Must be ≥ **28/35/43/52 paper** (50/55/60/65 live) by Moonshot's own learning progress. Max 10. Space mode by mcap: ORBITAL <$100K, LUNAR $100-500K, MARS $0.5-2M, JUPITER ≥$2M. | MoonshotTraderAI.kt:50-88, 353-475; BotService:4847-4985 |
| **ShitCoin** | **$1K-$100K** | ≥ lerp($3K→$5K) | ≤6 h | Score = liq 3-15 + bp 3-20 + momentum -15..20 + age 5-15 + platform 2-10 + social 0-15 + dex 0-10 + graduation 0-15 + holder -15..10 + dev 0-10, must be ≥ lerp(20→40). Conf (5×10/20) ≥ lerp(15→50) + 15(1-p). Max 12. Plus the V3 veto above. OR bootstrap force. Plus the bootstrap gate. | ShitCoinTraderAI.kt:496-861, 1018-1052; BotService:5002-5260 |
| **Manipulated** | $5K-$300K | ≥ lerp($1.5K→$3K) | ≤ lerp(12→8) min | manipScore = bundle ≥40/60% (+20/30) + bp ≥70/80 (+15/20) + momentum ≥10/15 (+15/20) + pump source +10 + age <3 min +10 − 20 if rc<1, must be ≥ lerp(15→28). | ManipulatedTraderAI.kt:55-79, 218-300 |
| **Express** | $2K-$300K | ≥ $1K | none | Pre-filter: **momentum ≥3%** (price vs 5 snapshots earlier) **and bp ≥50%**. Score (momentum 0-30, bp 0-25, volChange fixed at 1.5 → 8, price 0-15, trend/boost 0-10, age 3-10) ≥ lerp(12→25). Ride type: MOONSHOT_EXPRESS (≥25, momentum ≥15, bp ≥70, target 100%), MOMENTUM_RIDE (≥15, momentum ≥10, target 50%), QUICK_FLIP (target 30%). 30 min re-ride cooldown, max 20 rides. | ShitCoinExpress.kt:50-76, 232-380; BotService:5379-5470 |
| **Sniper** | $5K-$200K | $3K-$100K | **30-180 s** | bp ≥52%, price change since first snapshot between -5% and +50%. Max 5 missions, 50/day. | ProjectSniperAI.kt:47-75, 208+ |
| **DipHunter** | $50K-$5M | n/a | **≥2 h** | **[EFFECTIVE BEHAVIOUR] never fires**: the watchlist evicts at 30/15 min. | BotService:5583 |
| **V3 core** | V3-scored tokens only (§3.1) | V3 eligibility ≥ $500, exec floor $3K paper | **≤30 min** (TOO_OLD) | §4 | V3EngineManager / BotOrchestrator |

---

## 4. The decision "intelligence"

### 4.1 V3 pipeline (`L/v3/core/BotOrchestrator.kt:48-188`)

1. **EligibilityGate** (`L/v3/eligibility/EligibilityGate.kt`; config at V3EngineManager:113-127): liq > 0, **watchlist age ≤ 30 min**, liq ≥ $500, not cooling down, not already open, exposure below max (**25 slots paper**, `min(maxConcurrent,10)` live, 70% exposure). Failing returns Rejected.
2. **Pre-score memory kill** (:195-232): `TokenWinMemory` score ≤ **-25 paper / -12 live** returns Watch.
   **[EFFECTIVE BEHAVIOUR]** memory scores bottom out at **-20** (TokenWinMemory:448-462), so **this never fires in paper**.
3. **UnifiedScorer.score** (`L/v3/scoring/UnifiedScorer.kt:63-233`): the sum of 20 modules.
   - source: BOOSTED 4, RAYDIUM 7, PUMP 5, TRENDING 3, plus a lateness penalty (ScoreCard.kt:64)
   - entry: bp ≥70 +8, ≥60 +4, <35 -8; RSI<30 +5; momentum up +4; higher lows +3; clamp ±20
   - momentum: pump building +10, momentum up +4, weak -8; clamp ±15
   - liquidity: ≥40K +8, ≥15K +5, <3K -8, draining -2..-8
   - volume: accumulation +8, expanding +5, sell cluster -8
   - holders: 0 holders -2, >80 +4, top ≥20% -12, bundled ≥25% -10
   - narrative: +6 or -2
   - **memory: always 0**, because the adapter hard-codes `memoryScore=0` (V3Adapter:304)
   - regime: +1 at NEUTRAL; time-of-day -2..+3; copytrade 0; suppression 0 to about -25; fear&greed; social;
     volatility, orderflow, smart money, hold time, liquidity cycle (each a small ±); insider tracker (-15..+20)
   - Soft-penalty cap: holders, narrative, social and holdtime negatives are capped at **-5 combined**
   - **MetaCognition**: after 20 recorded trades, score × 0.72-1.15 plus trust adjustments (MetaCognitionAI:859)
   - **BehaviorAI**: tilt -15 or a streak adjustment scaled by p
   - CollectiveIntelligenceAI is computed but **not included in the final ScoreCard** (:232)
   - The meta-strategy inputs (pressScore, momScore, volScore, RSI, phase) come from `LifecycleStrategy` over the
     **per-poll snapshot history**. A snapshot is DexScreener's **h1** buy/sell counts, refreshed every 45 s.
     `momentumScore` stays neutral (50) until 8 snapshots exist (LifecycleStrategy:2311-2324). V5.9.65 comments put
     realistic V3 totals at **14-40**.
4. **FatalRiskChecker** (`L/v3/risk/FatalRiskChecker.kt:76-115`): `rawRiskScore in 0..5` gives BLOCK_FATAL
   "EXTREME_RUG_CRITICAL". Also FATAL_SUPPRESSION, liq ≤ $250, unsellable, RugModel ≥90.
   **[EFFECTIVE BEHAVIOUR]** The adapter fills `rawRiskScore` with `safety.entryScorePenalty`, a *penalty*
   (V3Adapter:148), not the rugcheck score. Two consequences: tokens whose async safety check has not returned yet
   (penalty 0), and the **cleanest tokens (penalty ≤5)**, are BLOCK_FATAL. Only tokens with some risk penalty pass.
5. **ConfidenceEngine** (`L/v3/decision/DecisionEngine.kt:21-83`): effective = 0.50·statistical + 0.35·structural +
   0.15·operational.
   - statistical = 10 + min(trades/10, 20) + clamp((WR-50)/5, ±10)
   - structural = 30 + clamp(total/2, -20..35) + 2·positive − negative modules
   - operational ≈ 90 when healthy
   - Typical result: 40-50.
6. **Pre-proposal kill** (:236-270): a C setup (score < lerp(20→35)) with conf < 10+30p, or bad memory, returns
   **ShadowOnly**.
7. **FinalDecisionEngine.decide** (DecisionEngine:116-430):
   - Score floor = `min(FluidLearningAI.getExecuteFloor()=lerp(25→45), V3ConfidenceConfig(20)=20)`, so **20 in paper**.
     Live forces it to **≥34** (:262-273).
   - Conf floor = `min(25+25p, 35)`.
   - C-grade (score < floor) is banned to WATCH if conf < 20+25p, memory ≤ -20+10p, AI degraded,
     (`early_unknown` and p>0.7), or (score<20 with momentum ≤0 and volume ≤0).
   - Directional gate: momentum <0 **and** volume <0 blocks execution.
   - Starvation relief: -2 after 50 non-executes, -4 after 100.
   - **Bands (paper)**: AGGRESSIVE score ≥26 and conf ≥ floor+10; STANDARD ≥20; SMALL ≥14 with conf ≥ 20+20p;
     WATCH ≥5; otherwise REJECT.
   - **Live**: 44 / 34 / 23.
   - `ToxicModeCircuitBreaker` is skipped because `tradingMode` is blank.
8. **Liquidity floor** (:292-345): execution bands need **$3K paper**, $7.5K (B) or $10K (C) live; below that becomes Watch.
9. **C-grade looper** check (only after p ≥ 0.25).
10. **SmartSizerV3** (§5).

### 4.2 Gates around V3 and lanes

- **Bootstrap score gate** (`FluidLearningAI.kt:337-370`): during the first 50 trades it blocks scores 1-24.
  Bypassed once `RunTracker30D.totalTrades ≥ 50`.
  **[EFFECTIVE BEHAVIOUR]** When it fires inside the Treasury or ShitCoin branch, it executes `return`, which ends the
  whole token cycle and skips every later lane (BotService:4442, :5124). Treasury's 5-minute post-close cooldown also
  does `return` (:4436).
- **TradeAuthorizer.authorize** (`L/engine/TradeAuthorizer.kt:115-300`):
  - banned: REJECT
  - rugcheck ≤1: REJECT live (paper and Manipulated lane bypass)
  - rugcheck 2-5: SHADOW live
  - live liq < $2,000: REJECT
  - same-book already open: REJECT
  - **promotion gate**: paper allows everything except F-grade with conf<5. Live allows A/A+, B only at conf ≥40,
    C only at conf ≥25, and **anything else becomes SHADOW_ONLY** (:305-350).
  - **[EFFECTIVE BEHAVIOUR] In live**, Moonshot passes `"Orbital"`/`"Lunar"`… as quality, Express `"EXPRESS"`, Sniper
    `"SNIPER"`, Manipulated `"MANIPULATED"`, Dip `"DIP"`, and V3-core passes `finalQuality` (which becomes "SKIP" on an
    edge veto). All of those become **shadow-only in live**. Only Treasury (B/C), ShitCoin ("C", conf ≥25), V3-core with
    an A-C setup, plus Quality and BlueChip (which never call the authorizer) could open live positions.
- `FinalExecutionPermit.tryAcquireExecution`; `EmergentGuardrails.shouldBlockMultiLayerEntry`; in live also wallet
  funding, keypair integrity, quote/slippage guard and simulation (Executor.liveBuy, :3367-3500).

### 4.3 Gate count for a live buy (sequential refusal points)

- **ShitCoin, live**: scanner filters (8), rugcheck, TradingMemory, registry/eligibility (5), probation, pair/decimal/
  pollution/banned (4), HardRugPreFilter, DistFade, blacklist, PAUSED, dedupe, fatal suppression, V3 call
  (eligibility, routing, memory, fatal, pre-proposal, band, liquidity floor, looper: about 8, and V3 must not say
  Rejected), Permit, candidate rule, ShitCoin internal gates (8), dump check, bootstrap gate, TradeAuthorizer (5),
  acquire permit, Executor live checks (about 6). **About 55 distinct refusal points, about 30 of them evaluated on every
  cycle.**
- **Moonshot, paper**: about 40 points. The V3 result is ignored and Permit/Authorizer are near-bypasses in paper.
- **V3-core, live**: about 45 points.

### 4.4 Legacy strategy and FDG

`LifecycleStrategy.evaluateWithDecision` (LifecycleStrategy:1033-1222) still runs every cycle. It produces the legacy
`decision` (setup quality, EdgeOptimizer quality/confidence). FDG runs only on the legacy path, as a **comparison
logger** while V3 controls execution (BotService:6068-6210). The legacy edge confidence matters in one important way:
it gates exit management (§6.0).

---

## 5. Sizing

| Lane | Formula | Typical (paper 11.76 SOL default, BotConfig:17) |
|---|---|---|
| V3 core | tradeable × base (SMALL 3%, STANDARD 6%, AGGRESSIVE 9%) × conf (0.40/0.55/0.75/1.0/1.1) × probe 0.5 (SMALL) × liq (0.40 under $3K … 1.05 at ≥$40K) × drawdown × **paper 0.5** × V3ConfidenceConfig; capped at 12% (`L/v3/sizing/SmartSizerV3.kt:47-133`) | STANDARD at $10K liq and conf 45: about 0.21 SOL |
| Treasury | max(0.15, **8% of wallet**) × (1+0.15·streak≤5) + daily compounding, ×1.25 when conf ≥75 and score ≥50, × mode (0.5-1.5), cap 2 SOL × (1+bal·0.05 ≤10), × AutoCompound; then × bootstrap multiplier (CashGenerationAI:676-728) | about 0.75-1.4 SOL |
| ShitCoin | max(0.05, 2% of ShitCoin's internal balance, which starts at 1 SOL) × (0.6+0.8·conf) ≤ risk cap (0.05-0.15) + ≤0.05 compounding, clamp 0.02-0.30, × AutoCompound, × bootstrap multiplier (ShitCoinTraderAI:780-818) | 0.05-0.2 SOL |
| Moonshot | base 0.08 × (1/1.25/1.5/2 by mode) × (1/1.5/2/2.5 at score >70/85/100), cap 0.40 | 0.08-0.40 SOL |
| Quality | 0.08 × 1.0/1.2/1.5, cap 0.25 | |
| BlueChip | base 0.15, cap 0.5 | |
| Express | 0.05 × (0.5+conf) × 1.0/1.2/1.5 | about 0.04-0.11 SOL |
| Manipulated | lerp(0.015→0.05) | |
| Sniper | 0.08-0.25 | |

Bootstrap size multiplier (applies to Treasury and ShitCoin only): 0.10 for p<0.05, 0.25 for <0.15, 0.50 for <0.30,
0.75 for <0.50, otherwise 1.0, times the SymbolicContext adjustment, clamped 0.05-1.25 (FluidLearningAI:305-330).
Paper fills add 0.4-3% simulated slippage plus a 0.5% fee (Executor:2850-2862).

---

## 6. Exits per lane

### 6.0 Which exits actually ran (critical)

- Exit management for open positions sits in the `else if (ts.position.isOpen)` branch at BotService:6409.
  **Before it**, the legacy promotion gate at **:5948** returns when `edge == SKIP && !(bootstrap || provenEdge)` **or
  `edgeConfidence < getPaperConfidenceFloor()`** (lerp 15→45). The C-grade gate at :5995 does the same.
  Neither gate checks `!ts.position.isOpen`.
  **[EFFECTIVE BEHAVIOUR]** On every cycle where the legacy edge confidence of a held token was below the floor (or the
  strategy hard-blocked it, which makes confidence 0), **none of the lane exits, layer promotions, SellOptimizationAI or
  `Executor.riskCheck` ran.**
- If the DexScreener WS was live for the token, `shouldPoll` skipped the cycle entirely (§1.3).
- The **only exit guaranteed to run** was the 500 ms `rapidStopLossMonitor` (BotService:2247-2350): hard floor **-15%**,
  plus `FluidLearningAI.getDynamicFluidStop`.
- **[EFFECTIVE BEHAVIOUR]** `getDynamicFluidStop`'s profit-trailing branch (FluidLearningAI:1165-1190) returns
  `-maxOf(minLock, trailingStop)`, a negative number such as -80 for a +100% peak. It is compared as `pnl <= stop`, so
  **it can never fire while the position is in profit.** The same function is used in `Executor.riskCheck` (:1660-1690).
  Its live branches are loss-side only: -15/-12% during the first 60 s, -12/-8% in the -5..0% zone, otherwise
  -10% × volatility multiplier.
- **Net effect in 1983: a position in profit had no time limit and no trailing exit until a cycle got past the
  edge-confidence gate.** That is the most plausible code-level reason a few +600% and +1,100% paper prints survived.

### 6.1 Lane exits (when reached)

| Lane | Exit rules | Ref |
|---|---|---|
| Treasury | **TP +4% paper / +3% live (full)**, cap +8%, SL -5% (fluid), trail 2% once >5%, time 45 min if negative. Promoted to Moonshot when ≥+200% (checked before the TP, so only on a price gap). | CashGenerationAI:845-880; BotService:6473-6615 |
| ShitCoin | UltraFastRug instant (price -30%, liq -40%), hard floor -20%, -50% within 5 min, SL lerp(-5→-6), **25% partial at TP lerp(8→20%)**, trail from entry: 8% (12% at ≥+100%, 10% at ≥+500%, 8% at ≥+1000%), flat exit at 8 min if -5..+10%, dead exit at 20 min if < -1%, **early full TP at ≥5 min if +20..+50%**. Promoted to Moonshot at ≥+200%. | ShitCoinTraderAI:881-1000; BotService:6620-6735 |
| Express | **full TP at +100%**, +50% after ≥3 min, +30% after ≥2 min, SL -8%, trail 5% once >15%, momentum <0 while in profit, 20 min stall | ShitCoinExpress:491-540 |
| Manipulated | TP +25%, SL -5%, **force exit at 4 min**, 15% trail from high-water mark once ≥+10% | ManipulatedTraderAI:75-79, 305+ |
| **Moonshot** | rug (-40% within 15 min), SL by mode (-10/-12/-15/-20 fluid; promotions use -5%), **single partial: 50% at min(TP/2, 75%)** (ORBITAL TP 50 → +25%; LUNAR+ → +75%), **trail only once >+30%**: mode trail 15/12/10/8%, 10% at ≥+100%, 8% at ≥+200%, 6% at ≥+500%, 5% at ≥+1000%. Flat exit at maxHold/3 if -5..+20%. Timeout at maxHold (45/60/120/240 min) **only if <+50%**. The mode upgrades as mcap grows, which raises TP and maxHold. **There is no full take-profit.** | MoonshotTraderAI:60-63, 820-880 |
| Quality | promote to BlueChip (mcap >$1M and ≥+15%), promote to Moonshot at ≥+100%, TP lerp(15→50%), SL lerp(-8→-12), 6% trail once >10% | QualityTraderAI:380-420, 515-523 |
| BlueChip | TP fixed at entry (**4/6/9%** by V3 conf), SL lerp(-4→-7), 30 min time exit if <+8% | BlueChipTraderAI:376-400; BotService:4769-4774 |
| Sniper | Its mission exits sit inside an `!isOpen` block (BotService:5484), so they **never run**. It falls back to the generic exits. | |
| Generic (all positions, via `maybeActWithDecision`, BotService:7193; Executor:2213-2330) | PrecisionExit quick check (fast rug >8% in <10 s, -10% in first 30 s, -8% in first 60 s, then SL 10%); **partials of 25% at +200/+500/+2,000/+10,000/+50,000%** (`partialSellTriggerPct` etc., BotConfig:124-129; Executor:1430-1560); `riskCheck` (Executor:1561-1810): coordinated dump, ExitIntelligence emergency, Gemini (live, ≥+15%), PrecisionExit evaluate including **PROFIT_PROTECTION: >+20% and >10% below the high since entry → full exit** (PrecisionExitLogic:250-270), -15% hard floor, liquidity collapse >50%, velocity dumps, SL 10%, and the **smart trailing floor**: base 8% × (0.85 under +30% … 1.5 at +100%, 2.0 at +300%, 2.5 at +500%, 3.0 at +1000%, 3.5 at +2000%) × health 0.7-1.6 × house-money 1.0-1.8 × learned 0.8-2.0 × regime (Executor:982-1106). | |
| SellOptimizationAI | SL lerp(-8→-10); after a peak >20%, lock lerp(50→70%) of the peak; chunk sells at lerp(15→50%), lerp(30→100%), lerp(50→200%); full exit at lerp(75→300%) (`SellOptimizationAI.kt:106-121`) | BotService:7096-7160 |

### 6.2 How +600% / +1,100% winners could run

1. Moonshot never takes a full TP. It sells 50% once and trails 5-10% only after +30%. Its mode upgrades extend
   `maxHold` to as much as 240 min, and the timeout ignores anything ≥+50%.
2. ShitCoin and Quality positions that reached +200% / +100% were **promoted to Moonshot** instead of being sold.
3. The generic trailing floor widens with gain: about 20% trail at +500%, about 24% at +1000%, up to ×1.8 house money.
4. Most importantly (§6.0), on many cycles **no profit-side exit was evaluated at all**, and the always-on monitor
   cannot sell a position that is in profit.
5. Paper P&L is marked to a DexScreener price that is up to 45 s stale and cached. The V5.9.62 comment records phantom
   "+440,000,000%" wins from decimal errors before that filter existed. Some 1983-era outliers may be paper artefacts;
   only the trade journal can settle that.

---

## 7. Learning and adaptation

### 7.1 FluidLearningAI, the master dial (`L/v3/scoring/FluidLearningAI.kt:476-556, 779-800`)

- Progress p = f(`TradeHistoryStore` total + session trades): 0→0.5 over **200 trades**, 0.8 at **500**, 1.0 at **2,000**.
- Win rate adjusts it. Under 500 trades: WR >60% ×1.1, WR <40% ×0.85. Over 500 trades: WR <50/40/30% subtracts
  0.1/0.2/0.3, with floors of 0.45-0.60 and higher past 2,000 trades.
- BehaviorAI's modifier shifts p by up to ±15% in `lerp()`.
- p drives every lerped threshold above: execute floor 25→45, paper confidence floor 15→45 (which also controls the
  exit gate in §6.0), C-grade floors, lane score/conf/liq floors, Treasury limits, TP/SL (bootstrap SL capped at 4%,
  bootstrap TP capped at 15% except Moonshot/Treasury/BlueChip), entry protection 15→12%, chunk thresholds, Quality's
  10→30 min age gate.
- **Strength**: large. Going from 0 to 500 trades moves the paper confidence floor from 15 to 39 and the C-ban
  confidence from 20 to 40.
- Bootstrap mechanics: forced entries while p<0.5; size multiplier 0.10→1.0; score gate during the first 50 trades.

### 7.2 Other learners and what they change

| Learner | Changes | Strength |
|---|---|---|
| MetaCognitionAI (after 20 trades) | V3 score × 0.72-1.15 plus per-layer trust ±; fatal veto only when meta conf <20 | ±10-28% of score |
| BehaviorAI | V3 `behavior` component: tilt -15, discipline/streak scaled by p; also shifts p | small |
| TokenWinMemory | per-mint memory -20..+50, used in V3 pre-kill and the C-ban. **Not** passed to MemoryAI (always 0). Paper pre-kill can't fire. | live only, in practice |
| EdgeLearning | Edge minimums: paper buy% 35 (range 30-45), volume 3 (1-10); live 55 (50-65), 15 (10-25). ±1 per outcome. Feeds edge quality/confidence, so it affects the legacy gate and therefore exit gating. | small steps, but sits on the exit-gate path |
| ExitIntelligence (after 20 exits) | trailing floor × 0.8-2.0 from the learned trail distance | moderate |
| PatternAutoTuner / AdaptiveLearningEngine | strategy entryScore × 0.82-1.3; 10-20% blend after 40+ trades (LifecycleStrategy:786-830). Affects legacy confidence only. | indirect |
| BotBrain | source boosts (scanner score, no effect); learned live hard-block thresholds (rugcheck 15, buy pressure 15, top holder 70); `shouldSkipTrade` (live legacy path); drought watchdog | live / legacy only |
| ScannerLearning / ModeLearning | discovery bonus | no effect (score gate is 1) |
| TradingMemory | ≥2 losses on a mint skips it in the scanner; rug patterns get blacklisted | hard filter |
| MoonshotTraderAI | its own learningProgress +0.005-0.05 per trade sets the 28→52 score tier and the $5K→$15K liq floor | step changes |
| Collective (Turso) | Moonshot collective winners +≤20; collective blacklist (live hard block); CollectiveIntelligenceAI not in the final score | small |
| Recorded but not read on the decision path | ShadowLearningEngine (both), QuantMetrics, RunTracker30D (except bootstrap bypass and live leniency), EducationSubLayerAI, many `recordOutcome` sinks (Executor recordTrade/paperSell fan-out to about 50 modules) | none |

ModeLeniency (`L/engine/ModeLeniency.kt:37-60`): live gates become paper-lenient once `TradeHistoryStore` shows
≥300 W+L trades at ≥50% WR, or RunTracker30D has ≥300 trades with current balance ≥ start.

---

## 8. Cheat sheets, field guides, playbooks

**None exist in 1983's code or decision path.** The only match is a comment header "TECHNICAL ANALYSIS (Trader's Cheat
Sheet)" in `L/v3/scoring/UltraFastRugDetectorAI.kt:26, 334`. It describes the detector's own EMA, RSI and dead-cat
logic, and that logic is used only in ShitCoin's exit check. `git ls-tree 98faee2e9` has no cheat-sheet or playbook
files.

For comparison, current HEAD adds `docs/EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md`, `lifecycle_apk/LANE_CHEAT_SHEETS_8323.md`,
`engine/CommonSenseTradePlaybook.kt`, `engine/cortex/LaneCheatSheet8323.kt`, `LanePlaybook7907.kt` and
`LanePlaybookCheckpoint8372.kt`. **1983 traded without any of these.**

---

## 9. What made 1983 behave as it did (code-level)

1. **A wide, fast funnel.** Scanner filters were essentially only scam words and impersonation. Liquidity ≥ $500 paper.
   No watchlist cap. Every token re-evaluated every 8-23 s.
2. **Fixed-threshold lanes running in parallel, mostly independent of V3.** In paper, Moonshot, Express, Manipulated and
   Sniper ignored V3 and were not blocked by Permit or Authorizer. V3 itself executed at a score of just **≥14-26 in paper**.
3. **Asymmetric exits.** Loss side: always-on -15% floor, plus -8 to -12% fluid stops at 500 ms. Profit side: Moonshot's
   one 50% partial and trails starting at +30%, promotions instead of sells, and gain-scaled trails. All of that ran
   only on cycles that passed the legacy edge-confidence gate. The always-on monitor's profit trail was inert because of
   the sign issue. Winners were left almost untouched.
4. **Simple learning effect.** One dial (trade count and WR) moved every threshold. Most other "AI" layers were advisory
   or recording-only.
5. **Paper/live split.** In live, the authorizer's promotion gate made Moonshot, Express, Sniper and Manipulated
   shadow-only, and strict-live probation/liq/age rules applied. The 1983 results the owner remembers would most
   plausibly come from **paper or proven-edge-lenient** runs.

When comparing to today, the items to check are: whether the current build still lets Moonshot-style runners skip
profit exits; whether lanes are now forced through V3 or cheat-sheet vetoes; whether execute floors or confidence floors
went up; whether profit trails now actually fire (the sign fix); and the pump.fun discovery path (probation, PumpPortal).
