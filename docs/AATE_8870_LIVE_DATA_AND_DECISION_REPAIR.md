# 5.0.8870 — live data interpretation and decision recovery

Date: 20 November 2026, Australia/Brisbane. Base: main `ea3fb4f9c2f2445ba950a9db11d9157e59b0c7e6` / 5.0.8869.

## Objective and release contract

Make correctly identified, correctly scaled, genuinely dated evidence reach the existing decision and execution authorities. A valid observation must not become “missing” because a handoff loses its clock, a decoder reads the wrong field, or a memo survives the arrival of new evidence. An old observation must not become live because an HTTP request or background task just finished.

Crypto retains its independent classic signal engine. Meme retains its existing specialist strategies. Shared repairs concern facts, evidence delivery, execution identity and outcome quality; they do not combine the two strategies' performance histories or promote paper gains into live execution authority. Existing V3/V4, FDG, wallet, position, impact, solvency, hard-rug and loss-budget controls remain authoritative. The existing crypto backing allowance is four admissions per hour with its existing loss stop; this repair does not enlarge or refund that allowance.

Release acceptance requires the exact main revision to pass source guards, relevant behavior tests, the required regression suite, release APK build, upgrade checks and runtime smoke. A compiled APK alone is not runtime acceptance. No source repair or offline test establishes positive live expectancy.

## Evidence from the supplied 8869 run

The pipeline attachment is `aate_pipeline_20261120_021402_406.txt`. Its runtime identifies 5.0.8869, despite an accompanying heap filename containing 8866. The edge export is `aate_edge_dataset_8537_1795104855994.jsonl.txt`.

| Observation | Engineering interpretation |
| --- | --- |
| Crypto: 416 candidates/submissions, four allows, 412 blocks, four dispatches, three open events | Discovery and signal production work; reaching and completing execution are separate measurements. |
| Crypto blocks include 195 nonpositive-expectancy, 75 data-never-arrived, 49 incomplete-data defers, 41 curve-nobody-bought, 28 unavailable routes | Repair false data failures and retries before attributing every block to the strategy. Route availability and genuinely negative outcomes are different questions. |
| 388 live-routable candidates, 28 paper-only unsupported routes | Paper observations across a broad universe do not prove live execution support for every asset. |
| All 252 crypto price-age observations landed in the last histogram bucket | The old bucket combines older-than-five-minutes **and unknown**. This does not prove all 252 prices were five minutes old. The handoff can lose the timestamp. |
| Meme: four sealed allows, one confirmed buy; the confirmed position stopped after about 39.5 seconds at a loss | A candidate allow is not a successful buy, and buying a runner label is not proof of a profitable executable trade. |
| Rolling live governor: 26 closes, eight wins, 18 losses, profit factor 0.34, expectancy about -0.0022 SOL per close | The available realized result is negative. Background improvement does not override it. This window is not the same as the current session's close count. |
| Curve RPC: 1,968 attempts; no reported no-data, short-account, zero-reserve or exception failures; 24,761 curve marks | The snapshot does not support a general base64 failure in the main curve reader. It does not rule out individual adapter or schema defects. |
| One exit recorded quote/build 13.215 seconds and trigger-to-broadcast 13.676 seconds | Exit latency remains a concrete risk to measure and repair; entry-data changes do not establish that this latency is solved. |

## Implemented data and decoding repairs

### 1. Nullable token amounts are not zero balances

Files: `network/SolanaTokenAmounts8870.kt`, `network/HeliusPushSwapParser7277.kt`, `engine/HeliusCadence8361.kt`.

The old transaction readers used only `uiAmount`. Solana's token amount structure also carries exact raw `amount`, `decimals`, and `uiAmountString`; `uiAmount` can be null. A valid post-balance could therefore become zero, suppress a buy, or falsely imply a full sale. The push parser feeds copy-trade detection and smart-money sell exits, so this is an operational defect rather than a display issue.

The shared reader now prefers exact unsigned raw units and the stated decimals, then the decimal string, retaining numeric-only legacy payload compatibility. It aggregates multiple accounts for the same owner/mint with decimal arithmetic. A malformed observed amount remains unknown. Absence from a successfully read complete balance array may represent a new or closed account and retains the existing zero semantics. Missing entire arrays do not establish zero balances. Failed transactions cannot create successful buy/sell observations.

Acceptance examples: a null-UI raw increase still produces a buy; a 25% sale remains 25%; an unread post-balance cannot become a 100% sale; raw unsigned 64-bit values and zero-decimal assets remain correctly scaled; WSOL is read with its stated decimals.

### 2. PumpSwap effective reserves follow the event schema

File: `network/HeliusWebSocket.kt`, `PumpSwapEvents8191`.

Base64 decoding itself succeeds for the affected fixture. The price sanity check then compared the trade with only real quote reserves. Current official PumpSwap events can append signed `i128 virtual_quote_reserves`. A pool with 100 SOL real reserves and 100 SOL virtual reserves can produce an approximately 0.2002 SOL trade for 1,000 tokens from a one-million-token reserve. Relative to effective reserves that is about 0.1% movement; relative to real reserves alone it looks like about 100%, so the old 25% check rejects it.

The decoder now locates the extension through the actual BuyEvent variable-length string layout or SellEvent fixed tail, decodes signed little-endian i128, and adds it to unsigned real reserves before conversion to floating point. It retains the existing 25% sanity limit. Recognized legacy tails imply zero virtual reserves. A partially present field is not silently treated as zero. Existing chain-proven pool/mint/WSOL publication checks remain.

This proves a schema compatibility defect with reproducible fixtures. The supplied report contains no raw boosted event establishing its frequency in that session. The existing supported-domain assumption of Pump base-token decimals is not a general mint-account decimals proof. Arbitrary quote-mint and token-decimal support is a separate contract below.

### 3. Prices retain their identity and clock

Files: `NativeDecisionEnrollment8372`, `DynamicAltTokenRegistry`, `CryptoAltTrader`, `CanonicalAssetEntryContract6551`, `CrossAssetCortex7931`, `CrossAssetMarkRouter6530`, `CryptoAltActivity`, `PriceAge8549`.

A price observation is the inseparable tuple `(asset identity, value, source, observedAt)`. The captured crypto signal now carries that tuple through canonical entry. A later registry value changing from the captured value no longer erases the captured value's timestamp. Wrong-asset, unknown-clock and future-clock evidence cannot qualify as a dated observation.

Registry refresh decisions use the price clock rather than a metadata row's update time. A fanout rescue carries its existing clock instead of storing zero. Held-price readers never substitute metadata time for an unknown price time. Older HTTP replies and concurrent metadata upserts merge against the current row atomically, so a newly landed stream observation survives. An invalid future clock cannot pin a row against subsequent real observations.

The same rule covers short-window and daily inputs: keep values and their own observation times together, retain measured zero, do not replace a newer measured value with an older/undated snapshot, and do not date cached daily counts at the time the cache is read. Price-age telemetry separately reports unknown observations while preserving existing bucket compatibility.

The active crypto candidate refresh targets 15 seconds through the existing bounded provider path. This is a refresh target, not a promise that every provider supplies a tick of that age. Existing admission age policy remains separate from the final executable quote. Same-mint shared TokenFacts liquidity can reach the crypto candidate with its original rank, source and time.

### 4. Provider timestamps and chain aliases survive adapters

Files: `network/KeylessPriceSources6996.kt`, `network/ParallelMarkFanout7088.kt`, `engine/BotService.kt`, `network/DexscreenerApi.kt`, `DynamicAltTokenRegistry`.

DefiLlama's Unix-seconds timestamp is decoded to milliseconds and carried through the cross-asset mark path, fanout and held-mark rescue. A successful HTTP response does not reset that clock. The dated adapter exposes the observation; each consumer applies its existing age contract. The mark-rescue queue's three-second delivery lifetime is distinct from the market observation's age. The final fact writer and quote-freshness record retain the observation time.

Fanout must date the prices that actually contributed to its selected result. A rejected old outlier must not make a fresh agreeing cluster appear old. A retained old contributor must not be silently redated. The corroborated reference retains the selected mark's clock as well.

For providers without a wall-clock price field, request-start time remains a receipt bound, not independent proof of the last trade's time. In particular, a Jupiter Price API response is not an executable swap quote. This repair does not claim complete market-event timestamps across every provider.

Gecko pool payloads now retain available m5 counts, volume, change, and their request observation time. Registry identities translate provider-specific network aliases through the existing execution chain map: `eth → ethereum`, `polygon_pos → polygon`, `avax → avalanche`. This prevents a provider network identifier being reused as the wrong Dex endpoint or a second canonical asset identity. Existing persisted alias-key positions are not automatically migrated. The supplied active registry did not establish that these aliases caused its Solana blockage.

Dex's explicit short-age refresh cannot silently return an expired cached pair on a rate-limit or synthetic failure. It returns no refreshed answer and permits the existing rescue path. Legacy callers keep their existing cache policy. No rate-limit budget or worker cap is raised.

### 5. A chain observation keeps chain rank

File: `engine/truth/CandidateMarkFresh8094.kt`.

The keyless curve callback read real curve reserves but submitted the price as `PROVIDER`. TokenFacts correctly protects a recent chain fact from a weaker provider, so the newer chain read could be refused behind its own older chain price. The chain callback now supplies `CHAIN`; aggregator callbacks retain `PROVIDER`. The mark refresh count and derived market-cap update happen only after the price writer accepts the observation. Existing position, sanity and provider-versus-chain protections stay in force.

The bounded candidate-refresh executor now throws a rejected submission to the existing cleanup handler. Previously silent rejection could leave `inFlight=true` with no worker, stopping future refreshes indefinitely.

### 6. Refresh an aged executable quote before sending

Files: `CryptoLiveQuoteBoundary8870`, `BuyRouteSafety8522`, `UniversalBridgeEngine`, `CryptoUniverseExecutor`, `RaydiumSellRoute7311`.

The crypto path requested a fresh wallet-bound quote, but subsequent wallet scans and transaction construction could age it without another time check. The crypto-only boundary now reuses the existing 30-second route-age rule before build and immediately before the send call. It checks the actual built quote, preserves exact input/output mint and raw amount, and retains the existing crypto impact ceiling after a refresh. A stale unsigned quote or pre-send 422 can cause at most one fresh quote/build attempt. No timestamp is reset to make the original quote pass.

An unsigned refresh wait is a typed, retryable route deferral. It releases the captured evaluation generation and canonical deferred intent without inventing a strategy loss or refunding the live quota. Once send starts, exceptions and uncertain signatures cannot return to the quote/build retry loop. The submitted transaction's actual quote owns expected output.

Raydium validates its actual route mint, amount and clock and rechecks before transaction sends. If a setup transaction has already completed and a later step stops, its signature is retained under verification; the route is not rebuilt or replayed. This preserves submission safety, but does not add a new partial multi-transaction resumption engine. Existing reconciliation must resolve setup-only pending outcomes. The repair enforces the pre-send boundary; it does not claim a measured maximum network broadcast latency.

## Decision recovery and learning repairs

### Evidence arrival must trigger a real reassessment

Files: `CandidateAdmission8136`, `LiveEdgeGate7877`, `NetEdge8030`.

Memo invalidation now matches the real cache-key layouts, including policy/mode prefixes, instead of assuming the mint immediately follows the mode. Cached results also carry the feature revision captured before assessment; a coalesced revision or a late in-flight cache write cannot preserve a decision made from old facts. The held/coalesced-defer layer follows the same revision rule.

Hydration dependencies list the facts actually binding the decision. Optional unobserved features remain diagnostics and do not acquire mandatory retry dependencies. This avoids waiting for data that the decision did not require.

Data-wait state belongs to `(mode, authority, canonical lane, question owner, mint)`. Admission and move/economics questions cannot spend each other's wait budget. A resolved question clears its own wait state; a later distinct missing-data episode receives a fresh bounded attempt. Repeated defers and notifications do not renew the budget, and a late completion cannot erase a newer episode. Existing attempt/time ceilings and shared request coalescing remain unchanged.

Crypto's temporary capacity wait is separate from negative expectancy. A retryable unsigned wait releases only the original captured evaluation generation; it cannot release a newer attempt. Signed or uncertain execution remains under canonical pending/finality ownership. Existing quota and loss controls remain.

### Obtain horizon marks before a slow provider consumes the window

File: `ForwardReturnLabeler7731`.

Off-curve vault requests are dispatched before serial/blocking HTTP pricing, through the existing bounded, deduplicated worker. A vault read retains pool identity and `Read.atMs`; it is not restamped at the end of HTTP work. SOL-to-USD conversion uses the existing dated rate contract. Missing conversion is not a zero return.

The change can start vault work for mints an HTTP source subsequently prices, so it may add bounded RPC work. Runtime verification must check queue pressure and held-position latency. There is no new unbounded queue, thread-per-mint loop or rate-limit increase.

### Learning evidence must remain interpretable

The supplied export has 60,593 valid JSON rows, with no malformed rows: 46,345 `fill=true`, 14,248 `fill=false`. Here `fill=true` is modeled reachable entry, not proof of a blockchain fill. Its targets are 6,829 five-minute and 53,764 fifteen-minute rows; it is not the full one-hour/four-hour/day trajectory store.

The separate longer-horizon store reported 388 qualified one-hour, 17 four-hour and zero day outcomes, with 8,306 no-mark-in-window outcomes. The quoted positive admitted rolling-book figure uses different cohorts and coverage from the export and from realized live closes. It cannot be presented as current live profitability. `labelObservedAtMs` in this export is append/booking time, not necessarily the source market timestamp.

Cross-strategy learning should share validated data contracts and qualified outcomes while retaining lane, setup, venue, horizon, costs and strategy identity. Do not transfer an unconditional crypto return average into meme permission. Missing outcomes remain censored/unobserved rather than zero or fabricated winners.

## Acceptance tests and forward verification

The `Aate8870*` required suite covers immutable decision clocks, same-asset liquidity, future/unknown clocks, stale carry, concurrent registry merges, valid zero versus missing inputs, Gecko aliases, memo and held-defer invalidation, separate wait episodes, chain rank, provider timestamps, selected-cluster clocks, nullable token amounts, legacy/current PumpSwap events, and horizon vault clocks. Execution-boundary tests must additionally prove stale unsigned quotes refresh within a bounded attempt, wrong identity/amount cannot send, and a started or uncertain send is never replayed.

The forward run must report discovery → actionable signal → admission → dispatch → send → confirmed fill → managed exit, with counts and latency for each transition. Compare actual binding data failures and retry recovery on equivalent workload, not just the number of background marks. Preserve reason/source/age so an absent response, malformed field, stale observation, expired retry and genuine strategy rejection are distinguishable.

Success requires fewer false data failures, faster reassessment after facts land, no duplicated submissions, no stale quote represented as fresh, continued exit management, and independently positive realized returns after fees/slippage over an adequate forward sample. More orders alone is not acceptance. No result from this patch is a guarantee of profit or an assertion of zero regressions before runtime verification.

## Explicit remaining work

1. **EVM confirmed quantity units.** `EvmSwapExecutor7962` returns a confirmed raw delta without a persisted decimal witness, and `MultiChainMemeLane7962` defaults a failed decimals read to 18. Raw 1,000,000 at six decimals is one token; at eighteen it becomes 1e-12 tokens and corrupts a $1 fill into a $1e12 unit basis. A safe repair must collect and persist chain/token decimals before new submission, carry the witness through Prepared/Filled/restart, and keep legacy confirmed receipts with unknown units under managed custody. Simply returning on a failed post-fill metadata read would strand confirmed inventory. Required states are NEW → UNIT_PROVEN → PREPARED → SENT/CONFIRMING → CONFIRMED_RAW → OPEN, with CONFIRMED_UNITS_PENDING for old receipts, no second buy, no local timeout pretending the asset is closed, and protective raw-quantity exits when required. The supplied multichain report had zero live closes, so this is a proven latent bug, not a demonstrated cause of the current Solana missed trades.
2. **Full newer Pump account support.** Official current curve accounts include a quote mint; legacy/default-zero quote means SOL and must remain compatible. Reading only the old 49-byte prefix cannot independently identify arbitrary quote units. Full support needs owner/discriminator/length checks, the full relevant account fields, verified base/quote decimals, dated conversion prices and paired buy/sell/mark fixtures. PumpSwap vault pricing also needs effective virtual reserves for boosted pools; fixing event decoding alone does not certify every pool mark. Do not guess quote units or blanket-reject legacy SOL curves.
3. **Exit latency and conversion availability.** The observed 13.676-second exit path and repeated missing dated SOL conversion need forward evidence and dedicated bounded-path analysis. Pyth authentication failures are not repaired by an encoding change. Do not widen quote age, invent a SOL/USD clock or describe this entry patch as an exit-latency cure.

## Primary wire references

- [Solana RPC JSON structures](https://solana.com/docs/rpc/json-structures) and [getTokenAccountBalance](https://solana.com/docs/rpc/http/gettokenaccountbalance).
- [Official Pump program IDL](https://github.com/pump-fun/pump-public-docs/blob/main/idl/pump.json) and [PumpSwap IDL](https://github.com/pump-fun/pump-public-docs/blob/main/idl/pump_amm.json). These establish current schema compatibility, not the deployed-event mix in the attached run.
- [DefiLlama token pricing guidance](https://docs.llama.fi/list-your-project/how-to-request-token-pricing): provider timestamps, chain aliases, units and missing coverage are distinct.
- [Jupiter's explanation of token prices](https://developers.jup.ag/blog/how-jupiter-prices-a-token): a reference price and an executable trade quote serve different purposes.
