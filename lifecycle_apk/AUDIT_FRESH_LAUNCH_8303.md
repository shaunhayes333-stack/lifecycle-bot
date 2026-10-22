# AUDIT 5.0.8303 — fresh PumpPortal launches (the paying pond) vs PROJECT_SNIPER / intake / live gates

Evidence: live 5.0.8302 (2207 s, then the post-crash 21 s report). Paths are relative to
`lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`. Line numbers are as of this commit.

## TL;DR

The create frame's birth time is **not** lost. Every map the sniper and the launch tape read holds it. What gets lost is
the **mint**. Since 8048, live intake parks every PumpPortal create in probation. A parked create has no token row, so
no lane, no sniper and no INTAKE counter ever sees it. That one routing bug explains three things:

- `PROJECT_SNIPER candidateN=0`;
- `sniperScope8169[inWindow=0]`;
- no `PUMP_PORTAL_WS` row in intake-by-source.

Fixed in `engine/FreshLaunchIntake8303.kt`, with one-line call sites. The pond's own forward record was also hidden from
PumpPortal launches that came back in through the launch-tape or probation pathways. Those rows were filed as
`LAUNCH_HEAT_7921|…` / `PROBATION|…`, not as `PUMP_PORTAL_WS|…`. That source-tag mismatch is fixed too.

## 1. Trace of a create frame (file:line)

| step | where | what happens |
|---|---|---|
| frame arrives | `network/PumpFunWS.kt:672` (`txType == "create"`) | operator, curve key, create price |
| birth witnessed | `PumpFunWS.kt:709` `AdmissionBirth8144.noteCreateFrame8144(mint)` | before the throttle, so every frame. Also `PumpCurveKeys7269.rememberCreate7280` (frames with reserves) and `PumpBandEntry8118.noteCreate8118` (`:719`) |
| throttle | `PumpFunWS.kt:720` `PumpPortalThrottle.allowCreate` | floor 10 SOL (25 SOL with the watchlist ≥200). 868/874 passed (`PUMP_CREATE_MARK_EMITTED_7279=868` is counted at `:726`, after `onNewTokenCb` at `:724`) |
| intake callback | `engine/BotService.kt:11171ff` | `PoolCreationTime7385.record`, generation guard, `ScannerFanoutDedupe6374`, `LaunchTape7921.onCreate` (`:11253`), then `admitProtectedMemeIntake(source="PUMP_PORTAL_WS", liquidityUsd=estLiq)` (`:11258`) |
| **depth estimate** | `BotService.kt:11232` `estLiq = curveRealSolFromCap8048(mcapSol) * solUsd` | for a 28 SOL create, `sqrt(28·30·1.073) − 30 ≈ 0.02 SOL` ≈ **$3** |
| **park** | `BotService.kt:16554` `coldPumpBase = … isPumpPortalWs … && liquidityUsd < 1_500.0` | the $1,500 cut was calibrated by 7384 on the old estimate `cap × 0.85` (~$4.8k, "only dust launches wait"). After 8048 **every** live create is under it. A second park, `sourceBrainProbationOnly`, also fires: the PumpPortal SRC record is dominated by sub-$10k labels, so 8292 caps the multiplier at 0.60 |
| parked | `BotService.kt:16564–16590` `GlobalTradeRegistry.addToProbationOnly` → `INTAKE_PROBATION_ONLY` → return | **no token row**. The INTAKE phase event `src=` (`BotService.kt:16795`, counted at `PipelineHealthCollector.kt:497`) is never reached. That is why no `PUMP_PORTAL_WS` intake row appears |
| fast lane | `BotService.kt:11274` `if (estLiq >= 2_000.0)` | never true for a create since 8048 |
| probation exit | `GlobalTradeRegistry.processProbation` | timeout at 5 min (withheld by 8292 while the PROBATION pathway loses). PRICE_UP has no callers. RC / second-scanner exits are rare. Every exit lands after the sniper's 180 s window. A promoted row was filed under `PROBATION,…`, which is the worst cell (`PROBATION|*|MC_LT10K|AGE_LT15M ≈ −33..−35 %`) |
| band entry ($10k+) | `BotService.kt:25836` `bandEntry8118` | the only way a create-framed mint got a row under `PUMP_PORTAL_WS`. These rows are the source of the `n=704` pond cell |

Where the sniper and the launch tape read the birth. All of these do see a create frame:

- `CanonicalTokenBirthTime7440.resolve` reads `AdmissionBirth8144.pumpCreateMs8144` (`engine/truth/CanonicalTokenBirthTime7440.kt:38`).
- The sniper's first age check is `ProjectSniperAI.kt:257–259`.
- `LaunchPhaseAuthority7401.snapshot` sets `birthResolved` (`engine/truth/LaunchPhaseAuthority7401.kt:264`). The sniper reads it at `ProjectSniperAI.kt:268`.
- `LaunchTape7921.onCreate` and `LaunchBirth8169.launchBirth8169` read the same maps.

So the 8287 claim holds: "a coin whose create frame we saw resolves its birth immediately". The coin was simply never
put in front of the sniper.

Who the sniper's 1,099 `NATIVE_BIRTH_METADATA_HYDRATING` reads were (`DecisionReasons8031` strips the `_7440`/`_7449`
suffix):

- They were launch-feed rows without a create frame: Codex `CODEX_NEW_*`, `PUMP_LIVESTREAM_7973`, chain launches.
- `launchAgeMs7767` gives them a watchlist-age lower bound, but `snapshot.birthResolved` stays false until
  `TokenBirthHydrator7441` resolves them. Second report: `requests=55 launched=7 resolved=5`.
- Codex `createdAt` is deliberately not a witness (`network/ChainLaunchFeed8179.kt:820`).
- `sniperScope8169[pastWindowSkipped=28 inWindow=0]`: no in-window launch ever reached the sniper's scope.

`RECOVERY_BIRTH_UNKNOWN_8168=4280` counts trade **events**, not mints (`engine/market/LaunchTape7921.kt:157`). They are
unified-tape trades on mints with no direct pump birth, mostly older or graduated coins. That is expected and not a key
bug. Every create that reaches `onCreate` already has a launch record.

## 2. Fixes (V5.0.8303)

1. **A witnessed create inside the sniper window goes hot.** `FreshLaunchIntake8303.park8303` at `BotService.kt:16555`
   (one line).
   - The cold-pump / source-brain park no longer applies when the source is `PUMP_PORTAL_WS` and a create witness
     (`PumpCurveKeys7269.createdAtMs7280` or `AdmissionBirth8144.pumpCreateMs8144`) is ≤180 s old.
   - It is limited to 24 per rolling minute. The observed rate was ~23/min, so bursts are clipped and a create over the
     budget is parked as before (`FRESH_CREATE_PARKED_BUDGET_8303`).
   - Unchanged: the supervisor-pressure park, the hot-watchlist cap (220) and its pump ≤50 % share with LRU eviction,
     every intake reject, safety / rug / authority / sellability, liquidity floors, FDG and the buy door.
   - The row now gets its first mark from the create frame (`onTradeCb` after intake). Labels `FRESH_CREATE_HOT_8303` and
     `FRESH_CREATE_PARKED_NO_WITNESS_8303` are published in `birth8144[… freshLaunch8303[…]]`.
2. **An evicted row no longer blocks the band entry** (`BotService.kt:25837`, `rowWatched8303`).
   - `GlobalTradeRegistry` LRU eviction (`GlobalTradeRegistry.kt:544`) removes the watchlist entry but not
     `status.tokens`. The band entry used to stand down on any row, so a launch evicted before $10k could never be
     re-admitted when it crossed.
   - A row now blocks only while it is watched or held (`PUMP_BAND_ENTRY_STALE_ROW_READMIT_8303`).
3. **The band entry reads any witnessed create** (`BotService.kt:25841`, `createBirthMs8303`), not only the curve-key
   create of a frame that carried reserves.
4. **Source-tag fixes, so the pond's own cell is visible.** The cell key is `sourceFamily(ts.source)`, which is the first
   allSources entry.
   - Launch-tape promotion (`BotService.kt:25758`) used `setOf("LAUNCH_HEAT_7921","PUMP_PORTAL_WS")`, so the cell read
     `LAUNCH_HEAT_7921|…`. A mint with a witnessed create frame now leads with `PUMP_PORTAL_WS`.
   - Probation promotion (`ScannerIntake8292.probationPromotionSources8292`): an entry whose real sources include
     `PUMP_PORTAL_WS` now leads with it. Every other promotion keeps PROBATION first, and the 8292 assertions still hold.
     PROBATION is still a wrapper, not a second source.
5. **Robust report line** `tail8303:` under `best60` (`engine/truth/CellTail8303.kt`, labeler status). It is a report
   only, never a gate.

Tests: `app/src/test/kotlin/com/lifecyclebot/engine/Aate8303FreshLaunchTest.kt` (9 tests). Validation:
`val2.sh` → validated. `pins.py 7c147e519` → broken 0. ART budget: `admitProtectedMemeIntake` uses 6 of its 19 spare
tokens, with no line added.

## 3. Why the pond is not bought live — a pond mint through the gates

A pond candidate is a `PUMP_PORTAL_WS` row, usually from a band entry at $10k+.

1. **Delivery.** Before 8303, only band entries and tape promotions reached a lane, and tape promotions were mis-tagged.
   The sniper got nothing (`inWindow=0`).
2. **Lane native check.** These counts are global, not per-source:
   - MOONSHOT refused `MOONSHOT_FRESH_DECLINED_7044 LIQ_UNKNOWN=225 / LIQ_BELOW_FLOOR=84 / NO_DEMAND_SIGNAL=83`.
   - A runner was missed on `NATIVE_MCAP_TOO_LOW=5` (glazecoin +2142 %) and `NATIVE_LANE_LIQ_BELOW_FLOOR=2`.
   - These are floors and were left as they are.
3. **`LiveEdgeGate7877.liveRefusal` → `CandidateAdmission8136`** (`LiveEdgeGate7877.kt:498`).
   - `EXECUTION_ECONOMICS_8136=1394` (`CandidateAdmission8136.kt:452`): the selected setup's expected gross does not clear
     `EDGE_MULT ×` the round trip.
   - `DATA_INCOMPLETE_DEFER_8136=259`. Runner misses Kimchi / LEONIDA / KIMOTHY were all this defer.
   - The cell evidence there is `ForwardReturnLabeler7731.cellStatFor` (`CandidateAdmission8136.kt:1025`), the same key
     as the writer: source family, lane, `TrustedMcap8019` cap, and `cellAgeMs8208` (direct birth, else `AGE_UNKNOWN`).
     A `PUMP_PORTAL_WS` row with a create frame keys exactly `PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M`, so there
     is no writer/reader mismatch for correctly-tagged rows.
   - The positive side reads the post-epoch "fresh" cell (`cellLoss8169`, `GRADING_VERSION 8210`), not the all-time +231 %.
4. **`LIVE_PROVEN_SETUP_ONLY_8059=1863`** (`LiveEdgeGate7877.kt:601`). trace8144 has 62 wrongly refused runners, 58 of
   them `QUALIFIED_LANE_STOPPED`, mostly this door. The door opens on `measuredEdge8060` (`:886`), which accepts the
   coin's own cell when `LiveAdmissionProof8290.cellSurvives8290` passes: n≥30, not suspect, mean − SE − lane shortfall
   (≤60) > 0. Taking the reported numbers at face value, the pond cell survives that test (+231.7 − SE(~15–25) − ≤60 > 0)
   for a correctly keyed candidate. So the 1,863 refusals were mostly candidates whose cell is **not** the pond:
   - other sources;
   - `MC_LT10K`;
   - `AGE_UNKNOWN` (no direct birth);
   - before this fix, tape / probation re-entries filed as `LAUNCH_HEAT_7921|…` / `PROBATION|…`.
   The 8298 restore doors (`restore8298[earned=none]`) bind admissions to the 8114 doors, because no lane has mature
   positive live closes (SHITCOIN live −6.6 % n43, MOONSHOT −8.9 % n50).
5. **FDG → sealed ticket → sizing.** SHITCOIN had 13 sealed tickets and executed 0. The killers were
   `SIZE_ROUTE_MIN_PROMOTION_REFUSED_NEGATIVE_EDGE_8188=14` and `UNMEASURED_EDGE_8188=8`. **Keying gap, owned by the
   parallel agent (`/tmp/claude-0/fexec`), not changed here:**
   - `MinPromotion8188.runtimeRefusal8188` takes its gross from the admission proof (`AdmissionEdge8180.proof8198`), else
     from `LiveRiskPolicy7807.setupExpectedGrossForPromotion8188`, a **setup** record (`MinPromotion8188.kt:98`).
   - A candidate admitted on its **cell** (`measuredEdge8060`) has no setup-specific gross. It is refused "unmeasured" or
     "negative" at the only routable size: wallet 0.2537 SOL, recommended 0.020 < routable minimum 0.028.
   - The pond's cell edge is invisible to that step.
   - Also in that agent's scope: MOONSHOT/EXPRESS `PREAUTH_LANE_TELEMETRY_ONLY_PRIMARY_SHITCOIN` and the 6715 stamp.

Net: before 8303 the pond reached the lanes late, and some re-entries under the wrong family. When it did reach them,
the live tickets died at route-minimum promotion sizing, not at a proof gate.

## 4. Robust read of the pond cell (honest picture)

`PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M n=704 net=+231.7 % wr=43 % run=33 %`

- **What the label is.** The labeler's "60" slot is the 5-minute first read (`read=5m(slot60)`). It is then **revised
  progressively up to 7 days** to what the exits would have banked on the path, using spike tiers on the observed peak
  (`ForwardReturnLabeler7731` 7997/8013). It is not a 5-minute return, and not what the live stops (−8 %, ~4 s) bank.
  The cell also merges hive tallies (`HiveEdge8000`).
- **Not OUTLIER_SUSPECT, correctly.** The 8185 test fires only when the mean is impossible under the +1,000 % per-label
  cap. Here the winners average about +539 %, which is under the cap.
- **Median.** wr 43 % < 50 %, so the median label is ≤ 0. The aggregates (n, sum, sumSq, wins, runners) cannot give a
  median or trimmed mean exactly.
- **Bounds the aggregates do give** (`CellTail8303`):
  - every label that is not a runner (gross < +50 %) averages **at most +7.5 %**;
  - the 33 % runners must average **at least +687 %** net.
  - The cell is **TAIL_DEPENDENT**: its whole edge is a runner tail sitting near the label cap. It is not "a few
    outliers", but it is not a broad edge either.
- **Live relevance.** The edge only pays with an exit policy that holds runners. With 1,000 %-capped labels and runners
  ≥ +687 %, many labels are at or near the cap, so the mean is as much a statement about the cap as about the coins.
- The new `tail8303:` report line prints this for every best60 cell on each dump.

## 5. Remaining / not changed

- **8188 route-minimum promotion reads a setup gross, not the cell edge** that admitted the candidate. Owner: the fexec
  agent (see §3.5).
- **TailHunter7996 and CellAllocator7962 key their own books by discovery age** (`addedToWatchlistAt`,
  `TailHunter7996.kt:185/269/313/352`, `CellAllocator7962.kt:369/571`). The labeler uses `cellAgeMs8208` (direct birth,
  else `AGE_UNKNOWN`). Each book is internally consistent, but a days-old coin found a minute ago reads their
  `AGE_LT15M` row. Not re-keyed, because that would orphan their history. It should be migrated together with a book
  version bump.
- **Volume / memory.** Up to 24 hot creates per minute add rows, enrichment and trade-stream subscriptions. The second
  report shows an OkHttp-callback OOM after 62 min (512 MB heap). Watch `freshLaunch8303[hot=…]`, cycle time and heap on
  the next live run. If needed, lower `HOT_PER_MIN_8303` (one constant).
- **Sniper flow evidence.** A hot create still needs ≥3 buyers, accelerating, ≥60 % buy share in 60 s
  (`ProjectSniperAI` 7794) from the keyed trade stream (`PumpFunWS.watchLaunch8190`). Expect `PREIGNITION_FLOW_NOT_PROVEN`
  for most creates. That is correct behaviour, not a bug.
- **Non-PumpPortal launch births** (Codex / livestream / chain) still wait on the RPC hydrator (55 requests, 7 launched,
  5 resolved in the second report). This is the remaining source of `NATIVE_BIRTH_METADATA_HYDRATING`.
- The PROBATION cell family stops receiving PumpPortal-origin promotions. Its existing history is kept, and no learning
  is reset.
