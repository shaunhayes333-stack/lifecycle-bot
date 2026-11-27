# AATE 9061 — lane populations, pocket context and a $5 cyclic seed

## Scope and evidence

Base: main `f66a21ccd9f496011edefa1724e0589da61685aa` (9060).
Owner correction: lanes and specialists have distinct token populations and timing; cyclic can start at USD 5 and compound beyond a million. Learned pockets must cooperate with lane learning for entries and exits. Detecting an eventual 5,000% runner is not evidence that an executable trading policy captured that return.

## Implemented

- Freeze the receiving lane's native cap, age and liquidity mandate with observed population facts. Exact token age and a minimum proven age are different inputs; a minimum age cannot prove a token is young. Token category means witnessed curve launch, curated bluechip, witnessed pool token or unknown; it does not invent meme/utility classifications.
- Extend the 9060 native learner from 52 to 73 features: 12 population inputs and 9 pocket inputs. Keep each lane/setup/venue/source/runtime-mode/direction scope separate. Raise both bounded model-width limits to 96 so the full vector can actually train; independent-asset chronological splits and support requirements are unchanged.
- Share the lane miner's immutable publication and matching function between admission and timing research. Admission now supplies its actual selected setup rather than reconstructing a potentially different setup through an exit profile. Research freezes the original pocket identity, publication clock, support, mean, margin, runner frequency, holding horizon and managed/endpoint proxy type. It masks a selected setup's pocket when training another simultaneously fired setup. Unknown evidence is distinct from a known no-match.
- Feed that pocket context alongside the lane's existing target, stop, cost and horizon to the native target-before-stop and risk-grid challengers. Four-hour pocket evidence remains four-hour evidence, with its ratio to the native holding horizon explicit. Serialization preserves decision-time evidence; no retrospective lookup at label maturity. Existing journals remain on disk, with a separate `native_zone_9061.jsonl` schema.
- Start new cyclic books at USD 5. Remove the unrelated USD 5,000 wallet scheduler prerequisite. Retain enabled-trader, wallet, execution and economic controls. Bound requested size by the ring and reject downstream expansion beyond its allocated size.
- Compound from the canonical whole-position net close, including partial proceeds and fees. Reconcile a generic close before requiring a token projection or current quote. Never compound from a marked percentage or emit a duplicate synthetic close.
- Persist separate live/paper book state, owned position, seed, loss pause and funding status. An owned cycle retains its execution book across mode changes and restart. Explicit funding status prevents a zero-SOL depleted ring from being reseeded using stale USD equity. Preserve old unscoped preferences untouched: ambiguous mixed-mode historical balances are not promoted into verified live capital. New books start fresh at the owner-declared seed; historical balance migration is not claimed.
- Remove the reset above USD 1.25 million. Count all crossed doubling milestones in one settlement. USD 5 reaches USD 1,310,720 after 18 mathematical doublings; that is arithmetic, not a forecast. A historical milestone is not banked or guaranteed protected principal. UI growth and progress use the actual seed.

## Role coverage and remaining differences

| Family | Population/objective used here | Remaining limitation |
|---|---|---|
| CORE | Current native ensemble mandate and original fired setup | No universal accumulation rule |
| SHITCOIN, PROJECT_SNIPER | Each native launch cap/age/liquidity mandate | No generic pool token taxonomy substituted for a confirmed launch |
| MOONSHOT, EXPRESS, MANIPULATED | Each native continuation/flow mandate and selected setup | Current native exit doctrine retained |
| QUALITY, BLUECHIP | Their own established/deeper-liquidity populations | Long holding clocks still need full native neural path adapters |
| DIP_HUNTER | Native mature pullback population | Does not inherit launch age bands |
| TREASURY, CASHGEN | Current native cashflow/depth mandates | Existing shared mandate and exit differences require further reconciliation |
| CYCLIC | Funded ring, canonical net accounting and existing native ring objective | Current 8418 contract still fixes a scalp hold; opportunistic style, generic exits and labels need a coordinated repair, not an isolated TP change |
| Crypto Solana classic/overlays | Exact native strategy and proposal levels; no inherited meme bands | Lane-pocket views are research-only for crypto; existing market adapters are not universal |
| Perps, EVM, xStocks, forex, commodities | Existing native engines unchanged | No claim of new neural pocket/entry/exit integration for these venues |

## Validation and acceptance

Local production-model suite: 258 tests; final allocation assertions included in the final verification run. Includes full-width fit, unchanged scope chronology, frozen population/pocket carry-through, cross-setup masking, unknown vs no-match, serialization provenance, horizon mismatch visibility, USD 5 / 18 doublings / growth past USD 1.25m / loss accounting. Android integration test added for the actual lane miner's publication, cap population and four-hour horizon. Full Android build/runtime results must be checked on the resulting SHA.

The native neural challenger still has no order authority. This change connects its lane and pocket inputs, not an unearned live neural promotion. Existing live pocket admission continues through native risk and economics. The research grid is not a 5,000% tail-capture policy and does not optimize actual exit receipts. Required next acceptance: measured decision-to-fill latency; native strategy-specific entry/hold/partial/exit replay; canonical execution attribution; 1h/4h/24h and rare-runner coverage; every external desk's price/cost/direction adapters; prospective after-cost evidence before neural authority. No verified live profitability or completed whole-stack repair is claimed.
