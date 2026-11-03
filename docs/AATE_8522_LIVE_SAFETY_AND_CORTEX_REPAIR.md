# AATE 5.0.8522 — live safety, current lane decisions and Cortex capture

## Scope and evidence standard

This repair follows a production report from 5.0.8521. Passing the previous unit and APK checks did not establish correct live safety or trading behavior. The defects below are supported by source paths and targeted regression cases. The attached wallet warning did not identify a token mint, so this audit does not attribute that warning to a particular transaction.

No profitability result is claimed. A missed token's later peak is not proof that its earlier setup was executable or safe. Structural risk, holder concentration, freeze/mint authority, fee/price continuity, lane mandates and late-learning exclusions remain required.

## 1. Live spending requires current, pool-specific evidence

### Policy and evidence clocks

- Safety cache reuse now binds PAPER/LIVE policy and the selected pool. An in-flight result from a different policy or pool is re-evaluated rather than reused for spending.
- A mint/freeze account read updates only those authorities. It cannot refresh the age of RugCheck, LP or holder evidence.
- Live holder reads use a bounded observation with its original source time. The longer informational holder cache cannot repeatedly become a new live proof when a report is reconstructed.
- An undated safe holder value from mutable token state cannot authorize a buy when the current report lacks holder proof. Valid adverse evidence remains conservative. NaN cannot hide another provider's high concentration.
- Missing proof defers and requests hydration; it does not become a permanent blacklist or a fabricated clean report.
- Explicit and forced safety refreshes bypass an earlier cached unknown result, including a same-millisecond cache hit. A waiting caller may share only a matching refresh completed from evidence obtained after its request. Existing provider backoffs and request bounds remain enforced.
- Whitelisted major tokens also acquire real, dated holder evidence through the bounded holder reader. Their verified identity does not fabricate a zero concentration or make unknown holder proof executable.

### LP ownership and liquidity

`LiquidityOwnership8522` preserves the provider's explicit unlocked-LP finding and all reported pool identities. An apparently good score, a later numeric field, or a locked minor pool cannot erase a confirmed adverse finding. Ordinary pools retain the existing minimum locked percentage.

The scanner no longer promotes market-cap-derived liquidity, stored maxima or a synthetic pair into observed safety depth. A direct observation carries the exact pool and source timestamp. A newer observed zero survives safety-cache reuse instead of being replaced by the old higher reading. Missing pool balances remain unknown; explicit zero balances remain zero.

The active-curve exception requires an actual recent account observation and the canonical curve identity. A source label, mint suffix, price-derived reserve estimate or historical curve cannot prove it. The last account observation is retained separately from later trade frames without adopting their timestamps. Graduation and newer unsafe reserves invalidate its use.

### Final signing boundary

`BuyRouteSafety8522` runs after asynchronous build, requote, blockhash and envelope work:

- Jupiter uses the quote bound to the built transaction, including a replacement taker-bound order. Every target-token pool in an AMM split route must have its own proof. A SOL/stablecoin intermediate pool is not the target token's LP proof.
- Only an explicit JupiterZ route is treated as maker inventory. The historical transport flag also covered on-chain routers and is not used as an AMM ownership exemption. JupiterZ is included in managed-execution transport handling so missing maker signatures cannot be self-broadcast.
- Raydium retains the actual compute response's pool identity and request clock. Every transaction in a setup/swap sequence rechecks safety immediately before signing. A prior submitted signature remains a pending liability if a subsequent transaction is refused.
- Pump's local builder uses its explicit curve selector only with fresh canonical account proof. An ordinary opaque auto route continues through a quoted Jupiter/Raydium path whose selected pool can be checked.
- New buys and top-ups use the same final wrappers.

The final route cannot borrow depth from a different historical pool. Both the selected pool's ownership and its exit depth must be observed, using the existing ordinary-pool floor and the separately proven curve exception. The gate also avoids letting a stale, different token-map pool's unknown depth prevent an otherwise currently proven quoted pool from being assessed.

Concurrent safety replacements are rechecked, including policy and authority changes. The report and runtime mode are checked again before returning to the signer. Missing, stale, changed or adverse evidence is logged with a distinct safety refusal, rather than mislabeled as a provider outage.

An unsigned final proof miss retains its pending disposition through the executor and uses the existing bounded hydration/retry budget. A genuine adverse finding remains a terminal safety rejection. A transaction that has already been submitted remains a reconciliation liability and cannot enter an ordinary retry as though nothing was sent.

## 2. Lane qualification and election use the current cycle

The asynchronous advisory sheet could return an empty executable fallback after 2.5 seconds. A trading cycle taking about three seconds could elect from that fallback, then see ready native brains later in the same cycle.

`electionSnapshot8522` computes current local evidence on the trading worker before election. One immutable sheet is pinned across the owner election, ten specialist gates and the CORE/CYCLIC spine. Initial evaluation occurs after the current V3 result is published. Older asynchronous completions cannot overwrite a newer sheet.

Worker reuse remains bounded to 2.5 seconds even when the price is unchanged, since launch age and other eligibility boundaries still move. The UI/advisory path keeps its longer unchanged-input reuse. A mode change during construction produces a non-executable result.

Worker and advisory local builds serialize per mint through their native evaluation and resident READY/LOST publication. The cache is read inside that boundary, so an older asynchronous build cannot erase a newer resident qualification while the sheet still reports it ready. Direct native evaluations share the same guard; unrelated mints progress independently, and idle guards are removed.

`SpecialistMarketInputs8522` supplies both native qualification and the CASHGEN/TREASURY and BLUECHIP entry checks. The actual entry consumes the immutable native read rather than reconstructing it from changing token fields. Inputs include observed price/pressure, fresh signed momentum, canonical birth evidence and real V3 results. Unknown evidence remains unknown. Rejected/blocked V3 placeholders and watchlist-as-birth inputs are removed.

Current momentum producers already write signed percentages. The current defect was disagreement about freshness and time window, not the historical centered-score representation.

Native CASHGEN eligibility now follows its existing $1M capitalization / $50K liquidity mandate. Other lanes keep their own requirements. A candidate lacking native history, birth or flow observations requests those observations through the existing bounded, coalesced hydration path before it qualifies. This resolves the circular missing-history state in which an ineligible lane never requested the evidence it needed.

## 3. Cortex reads frozen decisions and shares only valid work

The previous `TokenState.copy()` was shallow. Queued cold captures shared mutable histories, position data and token-map collections, and lost properties declared outside the primary constructor. A delayed prediction could therefore observe changes made after its decision.

`CortexDecisionSnapshot8522` detaches mutable inputs and preserves the mint-supply and observed pressure/change timestamps. It shares a snapshot only for the same source object and exact decision time. New decisions get new inputs, and eviction never mutates a queued snapshot.

`CortexVoterMemo8522` coalesces lane-independent reads across simultaneous lane captures. Lane-dependent voters remain distinct. A non-core budget abstention in one lane cannot suppress another lane's required seated voter. Supplementing the memo does not mutate an earlier prediction array or refresh its clock.

New assessments use actual read/fusion completion time. Waiting for a shared read or model lock cannot be disguised as a timely prediction. Original decision timestamps, forward targets and late-read exclusions remain unchanged. An incomplete one-hour target is not manufactured into a grade to change the scoreboard.

Hot-cache ordering separately retains the input time. A slow read of an older frozen input cannot overwrite a newer prediction merely because it completed later. Its actual completion time remains intact for the late-read checks.

## 4. Close replay and diagnostic accuracy

`EntryReceipt8500` previously persisted aggregates and consumed open receipts without persisting the already-graded close identity. Restart replay could consequently describe an already-handled position as unmatched.

Terminal identities, aggregate updates and open-receipt removal now share one ordered verified write. Repeated close/fill callbacks are idempotent, re-entries retain separate position identities, genuine new unmatched closes remain visible, and a later actual receipt can resolve an earlier unmatched close. Startup replay waits for successful restoration instead of assuming an empty book.

When an early repeated fill arrives before restoration, the persisted canonical position receipt retains its original entry evidence. A restored graded identity removes any staged duplicate open receipt, while distinct new positions remain intact.

Older aggregates have no recoverable contributing position IDs. Their values remain intact. Missing historical identities are explicitly reported as legacy unknowns, not declared successfully matched. Known non-meme asset classes are outside this receipt cohort; unknown legacy classifications remain compatible.

The held-quote diagnosis now separates cumulative request/miss counts from current live inventory. It prints detailed miss causes even when the live book is flat, and does not label a historical unanswered-quote count as a current held-position conflict. Quote freshness, exact-quantity matching, provider budgets and exit execution policies are unchanged.

## Validation and remaining practical limits

Behavioral regressions cover policy/pool/time binding; numeric and named LP risk; thin/zero/unknown depth; holder freshness and invalid values; curve account chronology; exact route binding and split routes; final authority changes; entry and top-up wiring; current-cycle election; stale asynchronous publication; native input identity; hydration delivery; detached Cortex inputs; concurrent voter fan-out; and persistent close replay.

The 8522 suite is added to the required release test selection. Existing source-wiring fixtures are updated to the new parameter/read locations without removing their underlying contracts. The repository's oversized-method verifier budget remains enforced.

Release acceptance requires compilation, the required regression suite, the full diagnostic suite, APK identity/signature checks and the existing emulator/runtime acceptance gates. Local source checks alone do not establish those results. The accepted build's run records provide the actual results.

On-device follow-up must establish current election-to-intent progression, fresh safety hydration, exact final-pool proof, capture lag/late-exclusion counts and genuinely mature forward grades. These repairs address demonstrated defects; they do not guarantee that every later runner was buyable at its earlier price or that any trade will be profitable.
