# AATE 8851 — All meme lanes and crypto desks: three-export audit

2026-11-18 (Brisbane). Inputs exported by the phone:

- `aate_pipeline_20261118_180313_683.txt` — **5.0.8848**, captured at 18:03 with **0 seconds uptime**.
- `aate_edge_dataset_8537_1794989001424.jsonl.txt` — **69,362** forward-labelled rows, decision timestamps 2026-11-15 00:44 to 2026-11-18 05:50 UTC.
- `aate_launch_hour_1794989107636.jsonl.txt` — **550,764** lines covering **21,546** born mints and **523,671** lane-decision rows; one metadata row, **2,773** `PATH_8741` rows and **2,773** `FACTS_8840` rows.

This is a *retrospective 8848/earlier export*, **not a witnessed 8851 live session**. The 0-second pipeline's 0 session buys, 0 FDG decisions, 0 learning and 0 live closes say nothing about a full running session. Its separate lifetime register contains only 15 chain-finalised closes (CASHGEN 5, CORE 2, MOONSHOT 5, SHITCOIN 2, TREASURY 1), all five lanes with negative expectancy. The meme paper ledger is *not* canonical money: the mirrored `P8572|`/`P8623|` per-lane rows refer to the same book and must not be summed. Its positive SHITCOIN paper P&L is runner-carried, not proof of a typical profitable entry. Wallet snapshot is 0 SOL; **there is no proof of a new settled 8851 live return**.

## Every native meme lane — fill-qualified historical forward labels

The `fill=true` field is an **independently fill-rebased forward label**, *not proof the wallet purchased*. Rows may repeat a mint at different times, so counts are **decision rows**, not independent trades. Net medians and positive percentages are after the dataset's modelled cost, at each lane's **own** frozen 5m or 15m horizon. `A` means that historical decision was labelled `admitted`, `R` refused. The raw dataset also has 14,691 `fill=false` rows: they cannot be used as fill-qualified outcomes.

| Native meme lane | A labels: n, median, positive | R labels: n, median, positive | Audit interpretation |
|---|---:|---:|---|
| CORE | 646; -8.6%; 20% | 6,090; -3.5%; 15% | Higher win *rate*, worse median; inspect late-run/CORE election |
| CYCLIC | 113; -2.5%; 24% | 1,473; -3.5%; 19% | Modest relative selection; no positive median |
| TREASURY | 47; -2.6%; 6% | 3,675; -3.2%; 13% | Cost-sensitive scalp; poor admitted win rate |
| CASHGEN | 452; -2.6%; 7% | 1,358; -2.9%; 15% | Admitted median is bad, low wins; no reason to loosen |
| QUALITY | **0 admits** | 5,061; -3.5%; 16% | Cannot assess selected quality; gate/election coverage hole |
| BLUECHIP | 5; -2.7%; 0% | 4,173; -3.3%; 15% | Too few selected outcomes |
| MOONSHOT | 1,878; -5.1%; 24% | 4,002; -3.8%; 10% | Runner capture vs median loser; late fills require proof |
| SHITCOIN | 741; -8.2%; 17% | 5,261; -4.1%; 14% | Runner-carried tail cannot validate common entry |
| MANIPULATED | 3; -74.8%; 0% | 4,726; -5.1%; 14% | Too thin, toxic selected sample; do not extrapolate |
| EXPRESS | 1,611; -9.6%; 20% | 3,774; -4.0%; 12% | Large late-entry median downside despite more rare wins |
| PROJECT_SNIPER | 21; -23.7%; 14% | 2,284; -8.2%; 10% | Very few selected; launch/birth window and evidence |
| DIP_HUNTER | **0 admits** | 1,465; -2.9%; 16% | Cannot assess selected bounce; history/handoff coverage |

## Every crypto-native desk (plus separate crypto-alt)

The nine supported overlays are scoped by `SpecialistBrainBridge7542.CRYPTO_DESK_SCOPE_8549` / `CryptoLaneDesk7391`: they are **not** the same wallet/venue execution as the 12 Solana meme lanes. `CRYPTO_ALT` is shown separately. The export has **5,418** nine-desk rows and **147** extra crypto-alt rows; all these records have `fill=true` forward labels (again, not wallet fills).

| Crypto desk | A labels: n, median, positive | R labels: n, median, positive | Audit interpretation |
|---|---:|---:|---|
| CRYPTO_CORE | 57; -3.5%; 21% | 1,148; -3.3%; 17% | Weak selected median; check native eligibility and route |
| CRYPTO_EXPRESS | 221; -3.7%; 33% | 726; -3.5%; 15% | More positive tails, but still negative typical net |
| CRYPTO_DIP_HUNTER | **0 admits** | 183; -2.8%; 17% | No executable selection evidence |
| CRYPTO_TREASURY | 2; -2.0%; 0% | 349; -2.8%; 14% | Thin admitted sample |
| CRYPTO_CASHGEN | 44; -3.1%; 18% | 41; -2.4%; 29% | Admitted group worse in this small sample |
| CRYPTO_QUALITY | **0 admits** | 479; -2.9%; 24% | Handoff/qualified-door proof missing |
| CRYPTO_BLUECHIP | **0 admits** | 253; -3.0%; 19% | Handoff/qualified-door proof missing |
| CRYPTO_SHITCOIN | 24; -22.9%; 21% | 915; -3.4%; 19% | Severe admitted median loss; small sample |
| CRYPTO_MOONSHOT | 172; -3.6%; 31% | 804; -3.4%; 14% | Tail selection, no positive typical return |
| CRYPTO_ALT (separate) | 2; -2.4%; 0% | 145; -2.6%; 1% | Almost no useful admitted proof |

The separate EVM labelled cohort is only **44** rows (ETH/BSC/Arbitrum/Base combined). Perpetuals have a different execution/finality spine: the zero-uptime pipeline reports `PERPS candidate=0`, not a completed proof or a basis for asserting they are broken. Do not promote crypto spot/paper, EVM or perps outcomes to settled live SOL.

## Launch-hour evidence and missingness — material for *every* lane

- All **21,546** `ALL_LAUNCHES` rows report the birth's hour elapsed, but only **7,041** have *any* first-hour print; only **2,773** have the 20 prints required for exported 10-second `PATH_8741` bars and `FACTS_8840`. The on-device replay itself accepts **three** prints (`LaunchHour8628.REPLAY_MIN_PRINTS_8788=3`); the downloadable 20-print path export is a **survivor-biased subset** of the device replay. Short-lived launches cannot be reconstructed from the downloaded path bars.
- Only **5,727 of 545,217** launch/decision rows (`~1.05%`) contain `hourNetPct`, despite all first-hour clocks elapsed. This is an **observation/coverage limitation**: `LaunchHour8628.ObservedPath.grade` requires an observed print in the **first 60 seconds after each decision**, another in the **last 60 seconds of the birth hour**, and the latter strictly after the former. Missing marks are **null, not losing trades**. Multiple setups and repeated decisions further inflate the row denominator.
- Example per-lane admitted first-hour decision rows with actual hour-end net: CORE **293/15,579**, EXPRESS **426/23,538**, MOONSHOT **476/21,469**, SHITCOIN **360/17,408**, CASHGEN **0/106**, PROJECT_SNIPER **0/322**, CRYPTO_CORE **1/112**, CRYPTO_EXPRESS **2/373**. These are **not** fills. `QUALITY` and `DIP_HUNTER` have zero admitted decision rows in this export.
- **4,352** first-hour rows have `costPct >= 100%`, including **81** marked admitted upstream. The cost comes from `FieldManual7715.labelCostPct8534` and reflects uneconomic/missing-liquidity estimates; the `admitted` flag is upstream lane observation, **not evidence FDG placed such orders**. Confirm that every downstream FDG/economics gate refuses the same cases and that source liquidity ages are valid.
- `droppedThisProcess=0` and `unitMismatchRejected8642=0` on the export header: useful counters **for that process only**, not evidence complete historical event intake. A zero mismatch count does not imply observed path completeness.

## Source-to-acceptance repair register

1. **P0 — independent outcome coverage and fresh provenance.** A sizeable launch and model cohort is unpriceable; inspect age-at-source, venue, missed print/mark, rebased fill, off-watch 5m/15m reads, 1h/4h/24h qualified-grades and confirmed outcome delivery. Attribute *why* each horizon is unpriced. Never fill gaps with synthetic prices or mark missing as a loss. Match exactly the same decision/venue/basis before grading a strategy or changing authority.
2. **P0 — remaining conflicting shared input path.** In `engine/DataOrchestrator.kt`, `TokenFacts.writeSnapshot(BUY_PRESSURE_PCT)` is correctly guarded on `txns5m > 0`, but the following `ts.meta.copy` **unconditionally writes `pressScore=50.0` when `txns5m=0`** (and writes a new WS score even if ranked TokenFacts declined the frame). `FinalDecisionGate.kt` still reads `ts.meta.pressScore` for several buyer/flow controls. Reconcile the legacy meta score to a **fresh accepted sourced fact** or an explicit unknown state, then test empty windows, rejected lower-rank WS, and downstream native/FDG for every lane. This source bug is not proof any particular historical trade passed a stale score.
3. **P0 — labelled source admission versus actual execution.** Distinguish `native_fired -> lane_eligible -> ready_final -> owner_offered -> owner_selected -> FDG -> economic_size -> ticket -> chain-confirmed fill -> confirmed exit -> delivered label`, with reason codes, per lane and per crypto venue. The 8848 boot snapshot has every live funnel counter zero; it cannot close the handoff investigation. Specifically test the zero-admit QUALITY/DIP_HUNTER and crypto QUAL/BLUE/DIP desks, without forcing a buy for activity's sake.
4. **P1 — timing and setup matching.** Replay named first-base, 15-second demand/reclaim, pullback/retest, early dip and true continuation using only facts **available at decision time**. Median negative admitted returns in EXPRESS, SHITCOIN, MOONSHOT and CORE demand cost-adjusted pre-peak and post-peak separation, not automatic score inversion. Re-evaluate per stage and model epoch; 69k decision rows are correlated, selection-biased observations, not an A/B test.
5. **P1 — crypto venue separation.** Preserve nine `CRYPTO_*` overlay identities and `CRYPTO_ALT`, separately measure spot, alt, EVM and perps auth/route/finality and live-versus-paper ledger. Never count a shadow or forward `fill=true` as canonical banked return.
6. **Build acceptance.** Release only when every required regression, the same-SHA cold-boot emulator runtime, background/doze/screens/restart/device checks, signed APK verification and upgrade tests pass. A compiled but unverified APK is not the requested deliverable.

### Interpretation limit

The tables are *descriptive*, not a causal ranking of the lanes: different strategies fire at different horizons and market phases; repeat observations of the same mint are dependent, labels sometimes lack an executable entry, and a small number of right-tail runners can move means heavily while medians stay negative. Do not loosen safety, fabricate profitable closes, or claim live positive returns on this evidence.
