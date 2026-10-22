# AUDIT 8325: 8323 went 2 / 12 live (PF 0.23); the owner stopped the bot

Base: main 5.0.8323 @6c2f99adb. Branch wt-fix-8325. Tag V5.0.8325.
Evidence: /tmp/claude-0/evidence8323.md (31 minutes live), AUDIT_INVERSION_8324.md, LANE_CHEAT_SHEETS_8323.md.
Tests: `Aate8325FavourTest`, `Aate8325LiveLossTest`, `Aate8325CortexLockTest`, `Aate8325RunOddsTest`.
Paths are relative to `lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/`.

There is no local compiler. Nothing here has been compiled or run; CI is the only check. The owner's device data (grade book,
admission traces) is not in this container, so the per-lane "after" verdicts are what to read on the next report, not
measured numbers.

Not touched: hard safety, the route minimum, execution economics, `FinalDecisionGate.evaluate()`, the 8295 / 8296 live-close
governors (still binding; the new live guard reads the same lane record they read), BotService, Executor, the traders' own
constants and native rules.

## 1. What let the losers in, and what was undone

### 1a. Cheat-sheet FAVOUR is the local record only

How 8323 favoured losers (`LaneCheatSheet8323.judge8323`):
- **Hysteresis with tail credit.** A setup that was FAVOUR (persisted verdict) stayed FAVOUR while
  `mean − cost + missedRunnerPts8324 > 0`. On a thin runner-lane record the missed-runner credit is tens of points, so
  CROWD_FORMING n2 −10.1 and RS_LEADER n30 −1.2 stayed favoured. One live close (live1) never counted: the live rule needed 2.
- **Hive favour (8324).** `hiveFavour8324` granted FAVOUR on a strong network record confirmed by 5 local labels.
- **Merged record.** The 20-label bar read the merged record (local plus up to 60 lent hive labels).
- **Admission.** `CandidateAdmission8136.sheetFavour8323` accepted a FAVOUR on 10 labels (8324 lowered it from 20).

Now:
- FAVOUR is earned only on **this install's own executable record** (`Record8323.local8325`, built by
  `LanePlaybook7907.localExecutable8325`: local labels only, the lane's label-to-bank offset off, governed by the live closes
  like every authority read). It needs n ≥ 20 and local mean − SE > max(prior, 0) + cost (the 8323 rule).
- A favoured setup **keeps** favour only while its local record (20+) stays above cost. There is no tail credit.
- **Any** losing live close of the setup (1 or more, mean < 0) blocks FAVOUR (`liveAnyLosing8325`).
- `hiveFavour8324` is no longer read by the verdict. The hive still ranks and priors (`expected`, the merged record).
- Admission reads FAVOUR on 20+ labels again.
- Every lane's sheet uses the same pure functions. The test applies the owner's examples to all twelve lanes.

### 1b. The runner credit is out of every live loss test

- `CandidateAdmission8136.decide8136`: the setup, timing, cell and stage loss tests read the records on their own labels.
  `tailCredited8324` is no longer called by any admission test.
- `LanePlaybook7907` PICKED_SETUP_RECORD_NEGATIVE / TIMING_RECORD_NEGATIVE: `st.mean()` again. `tailCredit8324` is kept but has
  no caller on a live path.
- `LaneCheatSheet8323.clearLoss8323` and the AVOID hysteresis exit: no credit. A setup losing on its own labels is AVOID.
  An avoided setup still **re-probes on the lane's exploration slot** (`reprobe8323`: paper always, live
  `MinPromotion8188.explorationSlotFree8316`, one coin per 20 minutes). That is the only place a thin runner-lane record gets
  another chance. It needs no tail credit, so the credit was not kept there.

### 1c. Other 8322 / 8324 paths that admitted more live entries

| Path | Before | Now |
|---|---|---|
| Cortex STRONG cohort door (`strongCohortEvidence8200`, tail door `CortexTailDoor8322` on runner lanes) | A STRONG-cohort read alone was a SPECIFIC_EDGE admit | The read still supports. Live, it admits only if the chosen setup's own record (past the realised shortfall) is positive on 12+ labels (`liveLocalGuard8325`, `LIVE_TAIL_EVIDENCE_ALONE_8325`) |
| Labeler cell OUTLIER flag-not-veto (8322: a real-tail cell reads raw and can be SUPPORTED) | A cell edge alone admitted, even over a thin losing setup (the 8290 test fixture shows exactly that) | Same guard: a cell or runner-grab edge admits live only with the chosen setup's own record positive |
| Veto retirement on a raw tail read (`vetoRobustProof8185`, 8324) | A refused-winners rule retired on its raw tail | Withdrawn. The winsorised proof binds again |
| Sheet favour → admission support (8324, 10 labels) | See 1a | 20 labels on a locally earned FAVOUR |
| Any live admit (edge, native, generic) | Lane evidence not read | The lane's own live closes must not be losing (3+, mean < 0: `LaneRealisedGovernor8295.tailRefuted8295`, `LIVE_LANE_REALISED_LOSING_8325`). The lane's own graded labels (20+, executable-net pond, `Cortex7885.lanePond8325`) must be positive (`LIVE_LANE_LABELS_NOT_POSITIVE_8325`) |

Notes on these guards:
- They run after the hard-safety, loss, economics and data steps. Paper and pure fixtures (null lane fields) are unchanged.
- A supported setup record (KIND_SETUP, mean − SE above cost on 30+) is itself the setup's local evidence.
- **Open decision for the owner.** The lane-close guard does not decay. The lane's live record
  (`CortexGradeBook8144.realisedBySetup8187`, live trades only, up to 600 kept) changes only with new live closes, so a lane
  whose live closes lose (SHITCOIN n7 at 8323) refuses live admits until that record turns. The 8295 governors already kept
  such a lane's setup records under the 30-label proof. 8325 closes the cohort / cell / favour doors that bypassed them.
  If a recovery probe is wanted, the natural place is the lane's exploration slot with a time gap, as the sheet re-probe does.
- Known gap: that record is the merged one. The hive may lend at most clamp(localN, 10, 60) labels (8291), so a
  20-local-label setup can reach 30. Not changed here.

The 8114 live decision being built elsewhere (wt-e8114-8326) will run this verdict in shadow. The edits in
CandidateAdmission8136 and LaneCheatSheet8323 are kept small: one guard function, two candidate fields, two adapter lines.
LiveEdgeGate7877 was not edited.

## 2. Cortex lock and latency

The voter reads were already outside the lock (`CortexVoters7885.readAll` before `synchronized`). The slow voters
(FORWARD_MODEL_EV 55 ms and the others) cost compute, not lock time. What held the monitor:

1. **`onCanonicalClose` read the close receipt from SQLite while holding the lock.** The boot replays every historical close
   (8168: 1,345 in 9 minutes), and each one did a store read under the monitor. Those reads queue behind the multi-MB save
   transactions.
   - **Fix:** the receipt is read before the lock (`storedReceipt8325`). Receipts are only ever added, so a stale "absent"
     simply re-checks.
2. **`persistBlocking8094` was `@Synchronized`.** The Cortex monitor was held across the SQLite writes of the whole book.
   - **Fix:** saves serialise on `persistLock8325`.
   - The snapshot (ledger, board, calibration, pending, grades) is taken in ONE short Cortex-locked block, which re-checks
     that no close grade awaits its receipt.
   - `durableCloseCommit8198` takes `persistLock8325` too, so an older periodic snapshot can never be written after a newer
     close commit (the 8198 invariant). Lock order is always persistLock → Cortex monitor.
3. **`refreshExecutable8187` ran under the lock, inside `onLabel` and forced on every close.** It called CostLedger7962,
   NetEdge8030, EntryChase7961 and ForwardReturnLabeler7731 (their own locks) and rebuilt the ponds.
   - **Fix:** it is called after the caller's lock. The external cost reads run lock-free, the grade-book reads and book
     writes run in one locked block, and the ponds and ranking are computed after it. One refresh runs at a time.
4. **The report and the panel ranked under the lock.** `statusLine` called six other components' status lines
   (LanePlaybook7907, LaunchTape7921 and others) and `inversionLine8324` (Spearman sorts per lane) while holding the monitor.
   `ui8069` ranked every lane under it.
   - **Fix:** the externals and the ranking are read before the lock.
   - The ranking and the ponds are copied under the lock in one pass (`CortexGradeBook8144.rankRowsByLane8325` /
     `pondRowsByLane8325`), computed outside it and cached for 30 s (`CortexRank8325.TtlCache8325`). A concurrent reader gets
     the previous value and never waits.
5. **A failed load retried on every call.** After a VM error or an unreadable row, every `ensureLoaded` re-decoded the whole
   book under the lock.
   - **Fix:** a retry waits at least 30 s.
6. **`assess` took the lock three times** (cross votes, fusion, calibration).
   - **Fix:** one critical section reads the cross votes, the fusion over the seats and the calibration slope (`Locked8325`).
   - The tail EV, buckets and ranking verdict read lock-free maps. The verdict is only peeked, never computed, on the hot path.

Target: assess p50 under 50 ms and lock wait max under 1 s. Read `assessLockWait8324[...]` and `assessMs8187` on the next
device report. The fusion itself is about 400 predictions plus an O(seated²) correlation pass. It stays under the lock
because it reads the ledger's mutable seats.

Tests (`Aate8325CortexLockTest`):
- `assess` has exactly one `synchronized(this)` block, with no voter read, pond, ranking, sheet, playbook, persistence or
  refresh call inside it.
- No Cortex-locked block outside the boot load touches LearningPersistence.
- The refresh's external reads sit outside its locked blocks, and onLabel and close refresh after their lock.
- The TTL cache computes once per window, and the ranking reads the ordering it buckets on.

## 3. Cortex ranking per lane

### Why the fused edge could not rank the runners (runner lanes)

- **Skill is R² on raw returns.** A seat's weight measures how well it fits a few huge labels, not whether it sorts runners.
  SWEEP_VOLUME_ACCEL +79.8% "skill" did not translate into rank order.
- **Bins are read through `predictMean8322`, the runner-aware upper bound.** A bin that holds the runners is clipped toward
  the bound, so a voter's runner bin can read below its moderate bins.
- **The pull is damped three times.** Each voter's pull is shrunk by 1/(1 + Σw), clamped to ±max(10, 3 SD), and shrunk
  toward a negative bounded lane mean.
- **The 8324 tail EV barely moved.** It shifted the lane's runner share only by the fused runner-rate deviation, damped the
  same way, so nearly every coin read the pond mean. Hence TAIL_EV_REBUCKET_8324_SHITCOIN_NEUTRAL = 436 and SEEN_STRONG = 20.
- **The anti-runner demotion only zeroes seats that point the wrong way.** It never made the right-pointing seats count.

### Fix: p(run) from the voters' bins (`CortexRunOdds8325`, `CortexLedger7885.runOdds8325`)

How it works:
- Every seat bin already counts runs (`Stat.runners / n`).
- A seat's **run skill** is the between-bin share of the run indicator's variance (shrunk rates, minus the share noise
  explains).
- The coin's **p(run)** is the lane share moved, in log-odds, by the skill-weighted average of the run odds of the bins the
  coin's top 8 run-skilled voters put it in. It is averaged, not summed, so correlated voters are not double-counted.
- The tail EV is p(run) × E[run] + (1 − p(run)) × E[non-run] on the lane's own pond (`CortexRunOdds8325.ev8325`). The 8324
  deviation read is the fallback.
- STRONG is the tail EV above the lane's own top-fifth bar (`strongBar8324`), so bucket = rank on runner lanes.

How ranking is read:
- `inversion8324` now judges each lane on the ordering it buckets on (`order8325=tailEv|edge`), over its most recent 300
  rows (`CortexRank8325.rankLane8325`). The old full-history `ic` / `tailIc` stay beside it.

Test: on a synthetic pond, one voter flags coins that run 25% of the time (unflagged: 1%) and a noise voter is random. On
every runner lane, held-out p(run) reads rank IC > 0.2 with the top fifth above the bottom fifth (RANKS). The noise voter's
run skill is under a quarter of the signal's.

### Scalp lanes: ordering around the all-regime prior (`calibratedRead8325`)

- The fused edge is `m_regime + Σw·d/(1 + Σw)`. On a lane with few seated voters, every coin of one regime shares
  `m_regime`. The lane's order is then mostly a bet on the regime's past mean, which mean-reverts.
- Scalp lanes now read `m_all + slope × (edge − m_regime)`: the voters' deviation, around the lane's all-regime prior.
  Calibration still learns on the regime read.

### Two guards on every lane

- **Bucket = rank (`strongInTopFifth8325`).** A scalp lane's STRONG must sit in the top fifth of its own recent calibrated
  reads once 50 are known (runner lanes: the tail-EV bar already is). The CORE case was STRONG n11 −23% while its top fifth
  paid +216%.
- **Inverted order is silenced (`guardedBucket8325`).** A lane whose own recent ordering is measured INVERTED (60+ rows)
  buckets NEUTRAL: an inverted order neither STRONGs nor REFUSEs. Counted as `BUCKET_RANK_8325_<lane>_<from>_TO_<to>`.

The Cortex's authority is unchanged: shadow and bounded. The ranking change grants no admission. A STRONG-cohort read
still has to pass the live guard in §1c.

## 4. Per lane

Reading the table:
- "Admitted its losers" says which door could carry the trade. The evidence gives the lane P&L, not each loser's admission
  reason. The reason is in the admission trace (`Decision8136.reason`, `liveLocal8325=` in the trace from this build on).
- "After" is what to read on the next report. It has not been measured.

| Lane | Live 8323 | Ranking before (inversion8324) | What could admit its losers | Changed for this lane |
|---|---|---|---|---|
| SHITCOIN (runner) | n7, 1 win, PF 0.15, −0.022 SOL, streakL5. Entries at $28k–49k mcap; stops at −15..−85% | ic +0.01, top / bottom fifth −3.5% / +400.6%, tailIc −0.09; pond p 0.048, run +1,795%; TAIL_EV_REBUCKET NEUTRAL 436 | Favour on CROWD_FORMING / RS_LEADER (losing local and live); runner credit on setup / timing / cell / stage and PICKED_SETUP / TIMING doors; Cortex NEUTRAL n6 −10.5% on the six entries (no Cortex door) | Favour local-only (both setups out); no runner credit; lane live closes losing (n7, mean < 0) → live admits refused until the lane's bank recovers (`LIVE_LANE_REALISED_LOSING_8325`); p(run) ordering and STRONG |
| MOONSHOT (runner) | n4, 1 win; 3KM7dv −16% | RANKS ic +0.18 (top −11.7% / bottom −26.5%) | Runner credit on loss tests; Cortex STRONG tail door; favour | Same guards; lane losing at 3+ closes refuses live; p(run) ordering |
| EXPRESS (runner) | 1 loss | FLAT (top −17.3% / bottom −12.2%) | Favour IGNITION_5M n52 +1.9 and BASE_BREAKOUT_FLOW n20 +0.9 (below cost by one SE); runner credit | Neither is favoured on its local record; no credit; p(run) ordering (FLAT → judged on tailEv) |
| PROJECT_SNIPER (runner) | — | RANKS but every quintile negative | Pond negative → no edge | Lane-label guard: live admits refused while the lane's own labels are not positive |
| MANIPULATED (runner) | — | INVERTED ic −0.13 (top −14.9% / bottom +23.6%) | Edge order anti-ranked its runners (same mechanism as SHITCOIN) | p(run) ordering; inverted-order guard silences STRONG / REFUSE until its recent order reads RANKS |
| DIP_HUNTER (runner) | — | RANKS | — | p(run) ordering; guards |
| CORE (runner) | — | ic +0.09, top / bottom +216.4% / −28.2%, but STRONG n11 −23.2% vs REFUSE n107 +99.1% | STRONG read the tail EV (≈ pond mean for every coin) while the rank read the edge: bucket ≠ rank | STRONG and the ordering both read the p(run) tail EV (top-fifth bar); the ranking line judges CORE on that order |
| QUALITY (scalp) | 1 loss: 4vT4tr $214k, RAPID_FLUID_STOP | FLAT | Not the runner credit (scalp lane). Candidates: a favour at n10 (8324), a Cortex STRONG cohort read (median door), a native-qualified opinion | Favour local-only and n20 at admission; cohort / cell reads need the setup's own record positive live; lane-label and lane-close guards; regime-neutral order |
| BLUECHIP (scalp) | — | FLAT | — | Regime-neutral order; top-fifth STRONG; guards |
| TREASURY (scalp) | — | INVERTED ic −0.33 | The edge varied mostly by the regime prior (few seated voters) | Regime-neutral order; inverted-order guard |
| CASHGEN (scalp) | 1 loss | RANKS | As QUALITY | As QUALITY |
| CYCLIC (scalp) | — | FLAT | — | Regime-neutral order; guards |

Native entry rules (QualityTraderAI, CashGen and the meme traders' own constants) were read for the sheet in 8323 and are
not changed here. A native-qualified admit (`NATIVE_QUALIFIED_ADMIT_8259`) now passes the same live lane guards.

**Multi-asset traders.** CryptoAltTrader, the EVM / MultiChainMemeLane7962 lanes, PerpsTraderAI, Forex, Metals and
Commodities:
- None of them reads the loosened paths: no cheat sheet, no tail pond, no tail credit, no Cortex STRONG cohort, no veto
  retirement. Checked with grep over `perps/`.
- Cross-asset lanes are kept out of the meme Cortex's cross-lane priors (`CrossAssetCortex7931`).
- Nothing changed for them.

## 5. Pins (superseded by V5.0.8325)

- `Aate8187CortexCompleteTest`: the calibrated read moved into `calibratedRead8325` (offset still subtracted once).
- `Aate8324RestoreProvenanceTest`:
  - `fuseWithSnapshot8324(lane, baseVotes8325 + cross, regime)`;
  - the pond is computed from copied rows;
  - the veto does not retire on a raw tail;
  - the hive prior is no longer FAVOUR;
  - admission reads FAVOUR on `FAVOUR_MIN_N_8323`.
- `Aate8324InversionTest`:
  - the ranking line is read before the lock;
  - the live doors carry no tail credit;
  - a thin runnerless record refuses `SETUP_RECORD_LOSING_8136`;
  - the sheet AVOIDs on its own labels.
- `Aate8094EmergencyExitDeterminismTest`, `Aate8198CortexCloseDeliveryTest`: `persistBlocking8094` takes `persistLock8325`,
  not `@Synchronized`.

## 6. What to watch on device

- `assessLockWait8324[mean= max= n=]`, `assessMs8187` p50 / p90, `ASYNC_DROPPED`, capture pool queued.
- `inversion8324[... order8325=tailEv|edge ic= ... RANKS|FLAT|INVERTED]` per lane.
- Counters:
  - `TAIL_EV_RUN_ODDS_8325_<lane>` (p(run) in use);
  - `BUCKET_RANK_8325_*`;
  - `TAIL_EV_REBUCKET_8324_*_STRONG` (should rise on runner lanes).
- Live refusals:
  - `LIVE_LANE_REALISED_LOSING_8325_<lane>`;
  - `LIVE_LANE_LABELS_NOT_POSITIVE_8325_<lane>`;
  - `LIVE_TAIL_EVIDENCE_ALONE_8325_<lane>`.
- `cheatSheet8323 favour=[...]`: only setups with 20+ local labels clearing prior + cost, and no losing live close.
