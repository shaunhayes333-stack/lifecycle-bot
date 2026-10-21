# AUDIT_SCANNER_8292: scanner and intake audit

Scope: SolanaMarketScanner, intake (scanner callback, TokenMergeQueue, emitWithRugcheck, admitProtectedMemeIntake,
probation and watchlist), ScannerSourceBrain, intake dedup, source tags, and the discovery feeds (PUMP_FUN_NEW,
RAYDIUM_NEW_POOL, DEX_TRENDING, DEX_BOOSTED, ChainLaunchFeed8179/Codex, Telegram/Groq scouts).
Evidence: the 5.0.8286 19-minute report (`/tmp/claude-0/audit8286.md`). Base main 5.0.8286 @7c147e519.
Tests: `lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate8292ScannerAuditTest.kt`.
New code: `engine/ScannerIntake8292.kt`, with three objects: ScannerSourceTags8292, ScannerSourceQuality8292 and ScannerReemitGate8292.

File:line references point at the patched tree unless marked "base".

---

## 1. Source attribution: intake pathways were learned and counted as sources (FIXED)

**Finding.** Every scanner sighting reached intake up to three times:

| call | source | allSources |
|---|---|---|
| direct (BotService startBot `onTokenFound`) | `SCANNER_DIRECT_<X>` | `{X, "SCANNER_DIRECT"}` |
| duplicate hydrate, when already watched (base BotService:8337-8354) | `X` | `{X, "REGISTRY_DUPLICATE_HYDRATE"}` |
| merge-queue emit, 2-5 s later (BotService `processTokenMergeQueue`) | `X` | merged scanners |

Similarly, the chain launch feed tags `{l.source, "CHAIN_LAUNCH_8179"}` (BotService `chainLaunchIntake8179`) and the
heal path tags `{X, "SCANNER_HEAL"}`.

Impact:
- **Polluted source learning.** `ScannerSourceBrain.recordOutcome` splits the compound `ts.source` into components
  (V5.0.6856 `componentKeys6856`), so `SCANNER_DIRECT` was learned as a source. That key is the union of every
  scanner. The report line "SCANNER_DIRECT n=139 WR 16% LEARNED" describes the intake path, not any single feed.
  The dedupe refresh path also appended `REGISTRY_DUPLICATE_HYDRATE` to `ts.source` (base BotService ~16697), which
  created a second fake source.
- **Brain throttle defeated.** The admission shaping (base BotService:16559) used `allSources.size >= 2` as
  "multi-source confirmed". `{X, SCANNER_DIRECT}` always has size 2, so
  `sourceBrainProbationOnly = ... && !multiSourceConfirmed && mult < 0.65 ...` could **never** fire for a
  scanner sighting. The brain knew a source was bad but could not act on it at intake.
- `sourceBrainMult` took the max over every tag, pathway keys included.

**Fix.**
- `ScannerSourceTags8292` (ScannerIntake8292.kt): pathway tags `SCANNER_DIRECT / SCANNER_HEAL /
  REGISTRY_DUPLICATE_HYDRATE`; `canonicalTag8292` reads `SCANNER_DIRECT_X` as X; `independentSourceCount8292`
  ignores pathways, the `CHAIN_LAUNCH_8179` and `PROBATION` wrappers, and the alias `PUMP_PORTAL_WS` = `PUMP_PORTAL`.
- `ScannerSourceBrain.normalise` now uses the canonical compound (ScannerSourceBrain.kt:433), and component keys
  are built from it (:171). A pure pathway resolves to nothing (neutral). The stored legacy pathway keys are **kept**
  (no learning reset). They are no longer read, and `summary()` reports them as `legacyPathwayKeys8292`.
- BotService:16540-16541: `multiSourceConfirmed` counts independent sources only, and `sourceBrainMult` is
  `ScannerSourceQuality8292.admissionMultFor8292` (best brain multiplier over real sources, capped at 0.60 when the
  primary source's forward record is proven losing; see section 2).
- The duplicate-hydrate second intake call is removed (BotService:8332). The direct call just before it always lands
  in the dedupe refresh, so the second call was a pure repeat (it only added a fake source and one "repeat" per watched
  sighting).
- The forward-label cell family was already correct, because `ForwardReturnLabeler7731.sourceFamily` takes the first
  component and strips `SCANNER_DIRECT_`. The pollution was in the brain and in the confirmation counting.

## 2. Quality of what enters: does the source brain down-weight bad sources? (FIXED, ranking and throttles)

**Finding.** In practice, no. The brain's `intakeMultiplier` (WR-only; SCANNER_DIRECT at WR 16% gives 0.60) touched:
- the scanner emit score (`SolanaMarketScanner.emit`). This only scales the `confidence` passed to intake and does
  not change order;
- the admission shaping, which section 1 shows never fired;
- `shouldSkipIntake` (ScannerSourceBrain.kt:99), which has **no caller**. It was reverted at V5.0.4598
  (GlobalTradeRegistry ~387).

`TokenMergeQueue.processQueue` ranks ready entries by a **static** per-scanner table (TokenMergeQueue.kt:39
`DEX_BOOSTED` 72, the highest after INSIDER_SHARK) plus a +15 "lagging market source" fast-track for liq >= $8k.
So paid DexScreener boosts went first regardless of their record. The forward labels already keep a per-source
record (`SRC|<family>` in ForwardReturnLabeler7731), but nothing at intake read it.

**Fix (ranking and throttling, no safety check removed).**
- `ForwardReturnLabeler7731.sourceStat8292` (truth/ForwardReturnLabeler7731.kt:377) is a read-only accessor for
  the `SRC|family` record (local + hive, robust read).
- `ScannerSourceQuality8292.rankAdj8292`: merge-queue points in [-20, +10]. It needs n >= 30 and a mean beyond one
  standard error; an outlier-suspect (8185) win counts for nothing; shrinkage is n/(n+60). TokenMergeQueue.kt:287-291
  adds this to the ranking for the entry's primary scanner, cached for 30 s.
- `admissionMult8292`: the admission multiplier is capped at 0.60 for a proven-losing primary source (mean + SE <
  -5% at n >= 30). The brain's probation shaping can now fire for bad single-source feeds.
- The DEX_BOOSTED +15 score bonus (SolanaMarketScanner `scanDexBoostedFrom8179`) now applies only while
  DEX_BOOSTED's forward record is not proven losing.
- Not changed: the static confidence values themselves. Downstream gates read `confidence`, so the learned
  adjustment acts only on the order.

## 3. Probation feeds lanes that lose (FIXED)

**Finding.**
- `GlobalTradeRegistry.updateProbationPrice` and `updateProbationRC` have **no callers**, so PRICE_UP and RC_OK
  promotions never fire. Promotion is therefore either MULTI_CONFIRM or `TIMEOUT_AUTO_PROMOTE`, which promotes
  every parked row with any liquidity after 90 s (PROBATION_MAX_TIME_MS) on **no evidence at all**
  (GlobalTradeRegistry.kt:1038 `processProbation`). These rows are the cold, single-source rows that admission
  deliberately parked. Promoted, they become the cell family `PROBATION`, which the 5.0.8286 report shows at
  -36% to -42% in every lane.
- Promotions entered intake as `allSources = {"PROBATION"}` (base BotService:17629). The scanner that found the coin
  was lost to all learning.
- `updateProbationScanner` (GlobalTradeRegistry.kt:991) added the raw incoming string, so the row's own scanner
  under another spelling (`SCANNER_DIRECT_X` vs `X`) or a pathway tag counted as a second scanner and promoted the
  row instantly. `normalizeProbationSource7819` had the same gap.

**Fix.**
- GlobalTradeRegistry.kt:1110-1111: `TIMEOUT_AUTO_PROMOTE` is withheld while the PROBATION pathway's own forward
  record is proven losing (`ScannerSourceQuality8292.timeoutPromoteWithheld8292`, logged once per row,
  `PROBATION_TIMEOUT_PROMOTE_WITHHELD_LOSING_PATHWAY_8292`). The row stays observable and still promotes on a
  distinct scanner. The throttle heals itself: once multi-confirmed promotions lift the PROBATION record, timeouts
  resume. With no record, the old behaviour applies.
- `normalizeProbationSource7819` reads `SCANNER_DIRECT_X` as X and a bare pathway as nothing (GlobalTradeRegistry.kt:1200).
  `updateProbationScanner` applies the 7475/7819 identity rule (:993-996).
- `ProbationResult` carries `source` / `addedBy` (`withSource8292`, :1165-1169). The promotion intake uses
  `ScannerSourceTags8292.probationPromotionSources8292` (BotService:17611), which gives `{"PROBATION", <real scanners>}`.
  PROBATION stays first, so the forward-label family (and the record the throttle reads) continues. The brain also
  credits the real scanner.

## 4. Discovery freshness: newShare 14% (216 new vs 1300 repeat in 10 min) (FIXED)

**Root causes.**
1. **The scanner re-served watched mints every poll.** `isSeen()` remembers a sighting for only 12 s
   (`getSeenTtl`, SolanaMarketScanner.kt:914). After that, a mint already on the watchlist is emitted again by
   every list that still carries it (DEX boosted top + **latest** since 8179, trending, gainers). Each emit cost a
   RugCheck call plus two or three intake calls.
2. **Each sighting was counted 2-3 times.** The direct call counted "new"; the duplicate-hydrate call and the
   merge-queue echo 2-5 s later counted "repeat". Even a perfectly fresh feed could not exceed ~33-50% newShare.
3. Hunter requeues of seen-but-unwatched rows (lane hunters, LLM/Telegram scouts) go straight into the merge queue
   every 10 minutes. Hunter delivery belongs to another agent and is left as is.

**Fix.**
- `ScannerReemitGate8292` plus `SolanaMarketScanner.watchedReemitSuppressed8292` (SolanaMarketScanner ~4375; called
  at the top of `emit` and of `emitWithRugcheck`, **before** the 2 s RugCheck): a watched mint re-served by the
  **same** source within 5 minutes is suppressed (`SCANNER_WATCHED_REEMIT_SUPPRESSED_8292`). A different source is
  new evidence and passes. This matches V5.0.7984's 5-minute intake window for watched mints, but stops the work at
  the source.
- The duplicate-hydrate call is removed (section 1).
- TokenMergeQueue: a mint emitted in the last 10 minutes ranks 12 points lower, so new mints are evaluated first
  (`mergeRankDelta8292`).
- Metric honesty: `DiscoveryRate7984.noteRepeat8292` counts an intake within 15 s of the mint's last accepted intake
  as an **echo** (`echo8292=` on the discovery line), not a repeat (BotService:13958).

## 5. Intake losses

**NO_PAIR=117 / PAIR_PENDING_HYDRATION=117.** The processTokenCycle no-pair branch (BotService ~26105-26240) is
correct: it keeps the token hot while hydration is pending, and demotes only on canonical `PAIR_HARD_UNAVAILABLE`
after at least 4 passes and more than 120 s, with an extended hold when liq >= $500. A demoted row
(`PROBATION_DEMOTE:NO_PAIR_NO_FALLBACK_AGED|X`) is HELD in probation (not rejected or stamped) and returns on a
distinct-scanner sighting. A DEX/pair source (trending, boosted, Raydium) seeing it means a pair now exists. Not
dropped forever, but the return path depends on that distinct sighting: the same pump source re-sighting the coin is
"same source" by design. Section 3's identity fix stops the pathway-tag echo from faking that return. No change
beyond that.

**HARD_RUG=94 (FIXED, two over-broad screening arms).** `HardRugPreFilter.filter` (strict/live screening):
- `NO_HISTORY` hard-failed a priced, liquid token whose candles had simply not landed yet. That is the same
  "absence recorded as a rug" class that V5.0.7147 fixed for the grace arm. Screening now passes it as SOFT_FAIL
  `NO_HISTORY_HYDRATING_8292`; the buy door (`spendAuthorization=true`, Executor ~18408) still hard-fails
  (HardRugPreFilter.kt:280-290).
- `LIQUIDITY_COLLAPSE_PATTERN` compared the volume of the last 3 candles, including tick-appended synthetic candles
  (V5.0.7389, no OHLCV volume). A real-to-synthetic step read as a 100% volume collapse. It now uses real candles
  only (strict :296-300, lenient mirror :415-417).
- Kept: confirmed hard safety, rugcheck 0, true zero liquidity, top holder > 85%, pure sell pressure, price collapse.
  Noted, not changed: `HIGH_CONCENTRATION` (70-85% after 10 min) returns `pass=false` with severity SOFT_FAIL. The
  BotService screening caller blocks on `!pass`, so this "soft" fail is hard in screening. It is left as is
  (conservative while realised trades are losing); see open items.

**BLACKLIST=28.** `TokenBlacklist.block` admits only true mint-level reasons (`isTrueBlacklistReason`: confirmed
rug, honeypot, cannot sell, freeze/mint authority, LP unlocked, zero liquidity, holder concentration).
Pending/partial/low-liquidity reasons are penalty-only, and older entries are rehabilitated on read. The count is
consistent with real hard blocks. Not over-broad. One gap in the **safe** direction: DataOrchestrator's
`HIGH_RUG_RATE dev=...` reason matches no true-reason token and is dropped as PENALTY_ONLY (`KNOWN_RUGGER ...
rugged=` does match). It is reported, not changed.

**Veto bypass (FIXED).** The scanner callback's V5.0.4132 veto (rug-mint blacklist, catastrophic pattern) only
skipped the direct call. The duplicate-hydrate call and the merge-queue enqueue still admitted the vetoed mint. A
vetoed sighting now returns before both (BotService:8337).

## 6. Fabricated discovery liquidity (FIXED, hard rule)

Seven DexScreener-pair scanners (pump volume, fresh launches, DEX trending, DEX gainers, DEX boosted, pump
graduates, Birdeye trending) set `liq = mcap * 10%` when neither DexScreener nor Birdeye had a liquidity value. The
narrative scan did the same from Birdeye mcap, and `scanCoinGeckoEstablished` floored every coin at **$50k**
(`(mcap*0.10).coerceAtLeast(50_000)`). These values flow into intake `liquidityUsd`. That is max-take into
`ts.lastLiquidityUsd` (registry `initialLiquidityUsd`), and it drives:
- the merge-queue fast-track (liq >= $8k: +15);
- cold-pump and brain probation shaping (< $1.5k / < $7.5k);
- the live intake safety precheck (liq <= $5k);
- BLUECHIP affinity.

A dust pool could therefore look deep. All eight sites now leave an unmeasured depth at 0 (unknown); real
DexScreener/Birdeye readings are unchanged.

## 7. Changes since ~5.0.8150 that degraded what gets bought (scanner/intake)

| commit | change | effect |
|---|---|---|
| 4352d0cc5 (8163) | GeckoTerminal name filter changed from "skip X/USDC, X/USDT pools" to "skip only quote assets"; Raydium api-v3 and GeckoTerminal new_pools both run every pass | Wider discovery, including every USDC/USDT-paired pool and both venue lists each pass. More rows and more re-serves of the same pools (feeds root cause 4.1). Not reverted; the re-emit gate absorbs the churn. |
| 59521182d (8179) | `scanDexBoosted` also reads `token-boosts/latest` (20 rows across all chains, filtered to Solana) | More **paid-boost** rows. They rank first in the merge queue (static 72 + 15 fast-track) with a +15 score bonus, whatever their record (the "DEX_BOOSTED intake 68" line). Addressed by section 2 (learned rank, bonus gated on the record) and section 6 (the fallback liquidity behind the fast-track). |
| ea0bc89f7 / c6be6211c (8179/8191) | ChainLaunchFeed8179 intake `allSources = {l.source, "CHAIN_LAUNCH_8179"}`; Codex poller tags CODEX_NEW_<VENUE> | The wrapper tag made every chain launch "multi-source confirmed" (section 1). Fixed by `independentSourceCount8292`. Codex source names are proper per-venue sources. |
| 017528266 (8169) | `LaneHunter7297.noteRequeued8169` on hunter requeue; hunter rows in the requeue cooldown rank last | Hunter-side; neutral for intake. |
| 1b2bc1016 (8196) | No-pair branch reads hydration state from the dated TokenMap (`resolveFromToken8196`) instead of fabricated "providers attempted" receipts | Correct. Rows are demoted only on real PAIR_HARD_UNAVAILABLE (source of the PAIR_PENDING_HYDRATION counts). |
| 6f8d5bcdf (8165) | Promoted launches admitted with real cap/depth | Correct. |
| 223727663 / c797ffe7c (8225/8226) | Intake trace writer moved out of admitProtectedMemeIntake | Telemetry only. |

Older issues that remain the main loss drivers (fixed above): TIMEOUT_AUTO_PROMOTE on no evidence (V5.9.1328 /
V5.0.4175), WR-only brain with no intake effect, pathway tags (V5.9.642b, V5.0.6856), and mcap x 10% liquidity.

## 8. Open items (outside this scope, or deliberately not changed)

- `MARKET_HUNT_SHITCOIN|DIP_HUNTER` -72%: a SHITCOIN-hunted coin traded by the DIP_HUNTER lane. Lane choice belongs
  to the lane hunters and admission (other agents). `ScannerLaneBridge.shouldRoute(source, "MEME")` at the scanner
  veto is dead, because no outcome is ever recorded under lane "MEME". Recommendation: have lane admission read the
  forward-label `source|lane` cell (`ForwardReturnLabeler7731.cellKey`).
- Hunter/scout requeue paths (SolanaMarketScanner `emitResidentHunt7807` / `emitLlmScout7830` /
  `emitTelegramCall7830`) re-queue hard-rejected mints (isSeen is true for ScannerHardRejectStore rows), and
  intake then refuses them. This is wasted work that belongs to hunter delivery.
- `updateProbationPrice` / `updateProbationRC` are unwired, so PRICE_UP / RC_OK promotion is dead. They were left
  unwired: wiring a +5% price promotion would reintroduce a chase promotion.
- `HardRugPreFilter` HIGH_CONCENTRATION severity mismatch (section 5).
- `scanCoinGeckoEstablished` still stamps `pairCreatedHoursAgo = 24*30` and `dexId = "raydium"` (identity-ish
  placeholders that are re-resolved downstream).
- TokenBlacklist drops `HIGH_RUG_RATE` creator blocks as penalty-only.

## 9. Pins

No existing assertion was invalidated (`pins.py 7c147e519` reports broken 0). The superseded behaviours carry
"superseded by V5.0.8292" comments at the change sites: the duplicate-hydrate call, the $50k CoinGecko liquidity
floor, the NO_HISTORY screening hard-fail, and the mcap x 10% estimates.
