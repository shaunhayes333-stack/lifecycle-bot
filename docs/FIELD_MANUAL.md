# The Crypto Trader's Field Manual

Operator-supplied baseline doctrine, kept verbatim below. Added in V5.0.7715 with the
instruction: "I want this used as a baseline logic helper. so from trade one live or
paper there is a real trading brain to build on. this goes everywhere."

Where it lives in code (`lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/FieldManual7715.kt`):

| Manual section | Code |
|---|---|
| §1 trading loop, §9 plan card, §16 decision card | `FieldManual7715.cardFor` / `evaluate` / `decide`, called from `FinalDecisionGate.evaluate` for every candidate in both modes |
| §3.1 regime map | `FieldManual7715.regimeOf` |
| §4 setup playbook A–J, §12 specialist mandates | `SetupFamily`, `LaneMandate`, `mandateFor`, `triggerFor` |
| §7.1 all-in cost model | `allInCostPct`, `impactRoundTripPct` |
| §8.1 position size from the stop | `sizeFromStop`, `riskCapSol` (applied in `OrderSizeResolver6441.resolve`) |
| §8.3 expectancy, profit factor, R | `expectancy`, `profitFactor`, `rMultiple`, `rewardToRisk` |
| §9 pass conditions | hard refusals in `evaluate` (`FIELD_MANUAL_PASS_7715`), evidence gaps (`FIELD_MANUAL_WAIT_7715` on live, `SMALL_PROBE` on paper) |
| §10 exit discipline | `classifyExit` / `noteExit`, called from `Executor.requestSell` |
| §12 shared expert baseline for AI | `doctrineForLlm` / `withDoctrine7715`, prepended to every `GeminiCopilot` system prompt |
| Report | `statusLine` under "Field manual (§7715)" in the pipeline health report |

---

The Crypto Trader’s Field Manual
A practical playbook for markets, setups, execution, and risk
Edition: 2 October 2026
Audience: discretionary traders and systematic trading teams, from liquid spot markets to on-chain launches
Purpose: a repeatable framework for finding, sizing, executing, and learning from trades. This is education and a decision aid, not individualized financial advice or a promise of profit.
The professional’s edge is not predicting every move. It is taking a defined risk when evidence and payoff are favorable, keeping losses survivable, executing at realistic prices, and learning from clean outcomes. A spectacular chart, a sophisticated model, or a high win rate does not establish positive expectancy.
1. The trading loop
A sound trade passes through this sequence:
Protect the account and wallet. Confirm the trading mode, available balance, permissions, custody, and maximum loss limits.
Identify the market and the asset. Verify the exact pair or mint, venue, quote currency, contract or token program, and data freshness.
Classify the environment. Decide whether the broad market and the asset are trending, ranging, expanding in volatility, or reacting to an event.
Name a setup. Specify the pattern or behavior, context, trigger, invalidation, target logic, and expected holding period.
Price the trade realistically. Estimate entry and exit costs, spread, depth, price impact, slippage, fees, funding, and failure risk.
Set the risk before the order. Choose the maximum acceptable loss, calculate size from the invalidation distance, then cap it for liquidity and concentration.
Execute and manage. Use the order type and route that fit the market. Monitor the thesis and the ability to exit; do not let a model, alert, or narrative override a broken setup.
Close and reconcile. Confirm the actual fills, fees, remaining inventory, and realized result. A submitted order is not a fill; a displayed mark is not necessarily executable value.
Review the unique trade outcome. Record one economic outcome per position, separate signal quality from execution quality, and update the right cohort only after the result is final.
Trade or pass? The default is pass when identity, exitability, trigger, invalidation, or cost-adjusted payoff cannot be established. Cash is a position. A missed move does not create an obligation to chase it.
2. Decide what kind of participant you are
These activities have different goals and should not share the same evidence or scorecard.
Activity
Core question
Typical horizon
What success means
Long-term investing
Is this asset worth owning through a broad thesis and drawdowns?
Months to years
Risk-adjusted portfolio outcome and thesis durability
Swing trading
Is there a defined multi-day or multi-week opportunity?
Days to weeks
Net expectancy and controlled drawdown
Intraday trading
Is there a repeatable short-horizon setup with sufficient liquidity?
Minutes to hours
Net edge after spread, fees, slippage, and missed fills
Launch/micro-cap trading
Can the asset be identified, bought, and sold safely while the market is changing quickly?
Seconds to hours
Avoiding catastrophic execution and supply risk while taking bounded opportunities
Market making/arbitrage
Is there a measurable spread or price discrepancy that survives fees, inventory, latency, and settlement risk?
Seconds to days
Realized net spread after hedging and operational costs
Derivatives trading
Is the directional or volatility thesis worth the funding, margin, liquidation, and basis risks?
Seconds to months
Return after financing and liquidation risk, not just price prediction
Do not compare a launch scalper’s win rate with a long-term investor’s return, or combine paper fills with live fills as if they were interchangeable. Define the strategy, venue, instrument, market regime, and horizon first.
3. Read the market from broad context down to the trade
3.1 A simple regime map
Classify both the benchmark market (for example BTC and ETH, or SOL for Solana-native assets) and the candidate asset. They may be in different regimes.
Regime
Typical evidence
Tactics that may fit
Tactics to treat cautiously
Uptrend
Higher swing highs and lows; pullbacks hold prior pivots; broad participation
Trend continuation, pullback, breakout and retest
Blindly fading strength
Downtrend
Lower highs and lows; failed reclaims; rallies sold
Reduce long exposure; wait for a base; short only with supported borrow and risk controls
Calling each lower low “oversold”
Range
Repeatedly defended edges; no sustained acceptance outside; declining or stable volatility
Edge-to-edge mean reversion, failed breakout/reclaim
Buying the middle; trend strategies without confirmation
Volatility expansion
Range breaks with larger realized range and participation
Breakout continuation or post-break retest, with smaller size
Assuming every spike will continue
Event/launch market
New information, listing, unlock, launch, exploit, or token migration changes the distribution
Event-specific plan with strict size and exitability checks
Applying ordinary historical averages or stale levels
Disorderly / impaired
Gaps, missing quotes, market-wide liquidation, pool withdrawal, stale data, venue outage
Reduce risk, cancel unsafe orders, protect exit/custody
Opening new risk because prices “look cheap”
Regime is a working hypothesis, not a label to fit the preferred trade. Reclassify after material structure changes. If the broad market is selling hard, a small token’s apparent strength may be temporary; if the asset materially outperforms, record that relative strength rather than assuming it will persist.
3.2 Structure and levels
Mark the last confirmed swing high and low, the active range, and the nearest meaningful supply and demand zones. A level is an area where price previously changed behavior, not a magical line.
Define a trend by the sequence of pivots and follow-through. A moving-average cross alone does not prove a trend.
A breakout needs acceptance: sustained trade or a close beyond a level, often followed by a hold or successful retest. A wick through resistance is not automatically a breakout.
A reclaim occurs when price loses a level and recovers it. The reclaim is more useful when buyers defend the level and create a higher low.
A failed breakout returns inside the prior range and cannot quickly recover the boundary. That failure can be more informative than the breakout attempt.
Levels are more meaningful when different timeframes and actual traded volume converge. Do not count the same price level on four timeframes as four independent signals.
3.3 Multiple timeframes without signal inflation
Use the higher timeframe to locate regime, structure, and major levels; use the execution timeframe to define a trigger and risk point. The lower timeframe supplies timing, not a veto-proof prophecy. A 1-minute bullish candle does not erase a broken 4-hour structure. A daily uptrend does not guarantee that a launch token has safe liquidity.
If signals conflict, state the conflict and either reduce risk or wait for resolution. Do not vote-count correlated indicators as if they were independent evidence.
3.4 Volume, flow, and momentum
Compare volume or traded value to the same asset’s own baseline and to the time of day or launch age. Absolute numbers are not comparable across assets.
Volume shows activity, not necessarily genuine demand. Wash trading, recycled wallets, self-trading, bots, and one large wallet can distort the picture.
For order books, inspect spread, depth on both sides, replenishment, cancellations, and likely slippage for the intended size. A visible order can disappear.
For DEX pools, inspect reserves and quote impact at the actual order size. Pool liquidity is not equal to market capitalization.
RSI, MACD, moving averages, VWAP, OBV, and similar tools summarize price/volume behavior. They are not independent votes and can remain “overbought” or “oversold” during strong moves. Use them to describe context and trigger rules, not as standalone buy/sell commands.
Funding, open interest, and liquidations describe positioning and derivatives conditions. They can inform squeeze or crowded-trade risk; they do not reveal a guaranteed future direction.
4. Setup playbook
Every setup needs five things: context, trigger, invalidation, exit plan, and cost-adjusted payoff. If one is missing, it is an observation, not a trade plan.
A. Trend pullback / continuation
Context: clear sequence of higher highs and higher lows (for a long), reasonable broad-market alignment, and a pullback that does not destroy the prior structure. The retracement often has less aggressive selling than the impulse had buying, but that is evidence to test rather than a fixed law.
Trigger: price forms a higher low and reclaims a local pivot, VWAP, or prior breakout area; alternatively, it breaks a small consolidation in the trend direction and holds.
Invalidation: below the higher low or failed reclaim level, chosen before entry. If the trend level is so far away that the position size is negligible, do not move the stop closer to make the trade look attractive.
Management: take some risk off at nearby resistance only if partials cover their own costs and the remainder still has a defined stop. Trail below confirmed higher lows for a long, not below every noisy candle.
Failure mode: buying the first red candle in a decline and relabelling it a pullback before buyers have demonstrated a higher low.
B. Base breakout and retest
Context: a clearly bounded base or range, room to the next supply area, and liquidity sufficient for the planned entry and exit. A tighter range can precede expansion, but compression alone does not tell you the direction.
Trigger: a sustained break and acceptance above the boundary, or a break followed by a successful retest. Confirm that a normal-sized order can still achieve acceptable entry and exit prices after the move.
Invalidation: price returns inside the base and fails to reclaim the boundary, or the expected cost has risen enough to remove the edge.
Management: if the initial break is already extended, consider waiting for the retest rather than paying a poor price. A stop-market may slip; a stop-limit may fail to fill. Know which risk you prefer.
Failure mode: buying a single wick, manufactured volume, or a breakout directly into higher-timeframe resistance.
C. Range mean reversion
Context: an established two-sided range with repeated reactions at its edges and no convincing catalyst or trend break. Prefer trades near the edge; the middle generally offers poor reward-to-risk.
Trigger: rejection of an edge, failed push beyond it, or a reclaim back into the range. A reversal candle is more meaningful at a level than in the middle of nowhere.
Invalidation: sustained acceptance outside the range. A range trader must accept that a real breakout invalidates the fade; repeatedly averaging into the break is not mean reversion.
Target: range midpoint or opposite edge only when expected net reward justifies the risk. Take account of the possibility that the range is narrowing before a breakout.
Failure mode: continuing to fade a strong trend or major information shock.
D. Failed breakdown / liquidity sweep and reclaim
Context: a visible support or prior low is breached, but selling fails to extend and price rapidly returns above the level. “Sweep” is a description of price action, not proof of stop hunting.
Trigger: a reclaim that holds on retest, ideally followed by a higher low or improving buy/sell flow.
Invalidation: below the sweep low or the failed-reclaim pivot. If price revisits the low and cannot recover, the reversal thesis weakens.
Failure mode: buying every new low and inventing a sweep explanation before an actual reclaim exists.
E. Momentum continuation
Context: a genuine catalyst or strong relative performance, orderly pullbacks, expanding participation, and enough depth for entry and exit. Momentum can persist longer than expected, but late entry often has poor asymmetry.
Trigger: a fresh consolidation break, a higher-low continuation, or a post-event level reclaim. Require evidence that participation remains after the first impulse.
Invalidation: the continuation pivot fails, price loses the consolidation, or flow and liquidity deteriorate.
Failure mode: chasing an extended vertical candle after most of the move has already occurred.
F. Event, listing, or token launch
Context: exact token identity and venue are verified; transfer and sell behavior are understood; actual liquidity and supply are checked; there is enough time and route capacity to exit. Launch conditions change rapidly, so stale data has unusually high cost.
Trigger: defined post-launch structure or confirmed continuation with buyers still present. Avoid a blind purchase based only on launch, influencer mention, a projected market cap, or a countdown.
Invalidation: launch structure low, failed continuation, suspicious supply changes, liquidity removal, sell restrictions, route failure, or loss of reliable identity/data.
Sizing: use a deliberately small, separately budgeted risk allocation. Assume the displayed valuation may not represent realizable value. Predefine the largest acceptable loss including transaction failure and the possibility that no exit route is available.
Failure modes: insiders distributing into retail demand; concentrated holdings; fake volume; removable or shallow liquidity; bundled wallets; a token that can be bought but not sold; stale supply/market-cap data; copycat mint addresses; and failed transactions during congestion.
G. Relative-strength rotation
Context: one asset is outperforming its benchmark or peer group across a defined lookback, with improving structure and sufficient liquidity. Relative strength means outperformance over a chosen sample; it is not an intrinsic quality rating.
Trigger: a pullback or breakout in the stronger asset while the benchmark remains stable enough for the thesis. Define the comparison pair and time horizon in advance.
Invalidation: loss of the relative-strength structure, a benchmark shock, or the asset’s own support failure.
Failure mode: buying an asset just because it has recently risen more, without accounting for volatility, liquidity, or crowded positioning.
H. Funding / basis / cash-and-carry
Context: the spot/futures or perpetual price difference and funding stream are measurable, accessible, and large enough to pay for fees, financing, borrow, margin, and operational risk. This is not risk-free arbitrage.
Trigger: execute both legs with controlled legging risk and verified venue balances. Recalculate when basis, funding, borrow, or collateral conditions change.
Invalidation: basis convergence against the position, funding reversal, borrow recall, margin deterioration, venue or transfer failure, or hedge mismatch.
Failure mode: annualizing one attractive funding print as if it were stable, ignoring liquidation basis, exchange credit risk, fees, and inability to move collateral.
I. Cross-venue / pool arbitrage
Context: prices are comparable after token decimals, quote assets, fees, transfer constraints, withdrawal time, pool depth, and inventory are included. A displayed price difference is not a guaranteed fill.
Trigger: trades can be executed or hedged within the quote lifetime. Include latency, transaction ordering, failed transactions, and adverse selection.
Invalidation: expected net spread falls below the full cost and risk allowance, or one venue/pool becomes impaired.
Failure mode: treating an unexecutable quote or stale pool as arbitrage profit.
J. Shorting and derivatives
Only use products the system can safely open, monitor, and close. Before entry, know the mark/index/last-price trigger rules, liquidation price, maintenance margin, funding, borrow, auto-deleveraging rules, and what happens during venue outage. Leverage magnifies losses and can liquidate a position before a longer-term thesis is proven. A spot-only system should not simulate a short by selling inventory it does not own.
5. Chart patterns: useful shorthand, weak standalone forecasts
Flags and pennants: consolidation after an impulse. They become actionable only with a continuation trigger, defined invalidation, and enough remaining room.
Triangles and wedges: converging ranges. Direction is not known from the shape alone; trade the confirmed break or the retest.
Double top/bottom: two tests of an area. The pattern is not complete until relevant support/resistance breaks or reclaims; the second touch can also strengthen the level.
Head and shoulders / inverse head and shoulders: a possible shift in swing structure. The neckline break and retest matter more than drawing a perfect silhouette after the move.
Engulfing candles, dojis, hammers, long wicks: single-bar descriptions. Their meaning depends on where they occur, the preceding trend, liquidity, and follow-through.
Support/resistance flip: a broken level later acts as support or resistance. A successful retest strengthens the case; it does not guarantee continuation.
Pattern names should compress observations, not replace a testable trade plan. Research on cryptocurrency technical rules has found results that vary by asset, period, out-of-sample interval, bubble regime, and transaction-cost assumptions. Backtests need realistic fees and execution, and should not be selected only because one pattern looks persuasive on a chart.
6. Fundamental, token, and on-chain diligence
6.1 Start with identity and exitability
Verify the exact contract or mint from a trusted source. Ticker, logo, project name, and search result are not identity.
Verify the exact pool or order book, quote asset, chain, venue, and decimals. Make sure every data source refers to the same asset and market.
Check that a route can sell the actual position size. Get an executable quote when possible, not only a last price or mark.
Inspect pool depth and expected price impact in both directions. A small buy can be cheap while a larger exit is catastrophic.
Confirm transfer, sell, and token-program behavior. An authority scan is a set of risk indicators, not a “safe” certificate.
Treat stale, conflicting, or dimensionally inconsistent supply, market cap, holder, price, and volume data as unknown. Unknown is not zero risk.
6.2 Supply, concentration, and permissions
Review circulating and total supply definitions; scheduled emissions; unlocks; vesting; treasury and team allocations; mint/freeze or equivalent authorities; upgrade and pause controls; transfer taxes; blacklist/whitelist rules; permanent delegates; liquidity withdrawal rights; and major holder concentration. Check whether apparent holders are linked wallets or exchange/pool accounts before drawing conclusions. On Solana, Token-2022 extensions may change transfer, freeze, pause, or delegation behavior; inspect the actual mint and extensions. Revoked authorities reduce a particular control risk but do not prove the token is safe, fairly distributed, or liquid.
6.3 Project and catalyst research
For non-meme assets, ask:
What problem is being solved, and why does the token capture value?
Is there verifiable product use, recurring demand, or only an announcement and roadmap?
Who can change the protocol, and how are upgrades governed?
What competing assets offer similar exposure?
Is the catalyst scheduled, already priced, or dependent on a rumor?
Could an unlock, emissions change, exploit, legal event, or migration overwhelm demand?
For meme and community tokens, substitute “verifiable market structure and distribution” for unsupported product claims. Social engagement is easy to manufacture. Consider source independence, wallet concentration, creator/insider behavior, and whether activity continues without paid promotion. Do not confuse popularity with exit liquidity.
6.4 Security and operational risk
Check wallet and contract permissions, token approvals, phishing and counterfeit links, bridge/custodian risks, exchange withdrawal status, and chain congestion. Separate trading wallets from long-term custody where appropriate. Test the intended transaction path with small amounts when the risk justifies it. Never disclose seed phrases or sign an unexplained transaction. A strategy with positive price forecasts can still lose through custody, smart-contract, counterparty, or operational failure.
7. Execution: trade the price you can get
7.1 All-in cost model
For each candidate, estimate:
all_in_cost = entry_fee + exit_fee
            + spread_paid + entry_price_impact + exit_price_impact
            + expected_slippage + funding_or_borrow
            + priority/gas costs + expected failed-route cost

net_expected_value = expected_gross_profit - all_in_cost
                     - execution/uncertainty allowance
Use the position size you intend to trade when estimating price impact. Do not subtract only the platform fee. On DEXs, pool curvature means your own trade changes the price; slippage is the difference between the expected quote and realized execution. On order books, spread and available depth matter. In thin markets, the quoted mark can be far above liquidation value.
If exact cost is unavailable, use a conservative range. If the plausible cost range consumes most of the expected move, pass or reduce size. A model should never turn missing costs into zero costs.
7.2 Order types in plain language
Order
Useful for
Main risk
Market
Urgent exit or fill priority in a sufficiently liquid market
Price can be materially worse than the visible quote
Limit
Price control and patient entry/exit
May not fill; queue position and adverse selection matter
Stop-market
Exit after a trigger when execution matters more than price
Gaps and thin liquidity can cause slippage
Stop-limit
Price boundary after a trigger
Can trigger but remain unfilled while price moves away
Bracket/OCO
Linking protective stop and target where supported
Venue behavior, partial fills, and cancellation logic must be understood
DEX swap with slippage bound
Atomic on-chain exchange within transaction constraints
May fail or execute near the bound; route and pool state can change before inclusion
Know whether triggers use last, mark, index, oracle, or pool price. Confirm how partial fills, rejected orders, retries, and cancel-replace work. A client timeout does not prove the order failed; reconcile before resubmitting to avoid duplicates.
7.3 When transaction speed matters
Fast execution is valuable only when the signal and route are valid. In a launch or fast market, stale quotes and latency can create adverse selection: the trade fills because the market moved against the quote. Measure quote age, route confidence, confirmation/finality state, and actual-versus-quoted fill. More attempts do not automatically create more edge.
8. Risk sizing and portfolio survival
8.1 Position size from the stop
Choose an account risk budget first. Then derive the position size from the distance to the structural invalidation, including likely costs and slippage. Do not choose an arbitrary position and then invent a stop that makes the position appear acceptable.
risk_budget = account_equity × risk_fraction
loss_fraction = expected loss from entry to invalidation, including costs
position_notional ≤ risk_budget / loss_fraction
Example (illustrative only): account equity A$10,000; planned trade risk 0.5% = A$50; entry-to-invalidation loss including a conservative cost allowance is 8% of notional. The risk-based notional ceiling is A$50 / 0.08 = A$625. If executable depth, a portfolio limit, or venue constraints imply a lower size, use the lower number. If the stop can gap or the token can become unsellable, the true loss may exceed A$50; reduce size accordingly.
A stop is an instruction, not insurance. In a gap, outage, failed route, or thin pool, realized loss may exceed planned risk. For micro-cap tokens, consider a separate “can lose the whole position” risk budget rather than assuming an orderly stop.
8.2 Portfolio-level controls
Set a maximum loss per trade, per day/session, and per strategy. Define when trading pauses after a drawdown or operational fault.
Limit total open risk, correlated exposure, venue/custody concentration, and illiquid inventory. Ten tokens that all depend on SOL liquidity or meme sentiment may be one macro position in practice.
Count pending orders and partial fills as exposure. Reconcile held positions against authoritative fills and balances.
Reserve cash for fees, exits, and expected volatility. Do not spend the entire balance on entries and leave no room to manage them.
Set a maximum number of simultaneous positions based on monitoring and exit capacity, not only scanner throughput.
Avoid martingale behavior: no automatic size increase to recover a loss, and no widening stops because the trade is losing.
Avoid leverage until worst-case liquidation, funding, gaps, and venue failure are understood and modeled.
8.3 Expectancy, payoff, and drawdown
expectancy_per_trade = win_rate × average_net_win
                     − loss_rate × average_net_loss

profit_factor = sum(net_wins) / abs(sum(net_losses))

R_multiple = realized_net_PnL / initial_planned_risk
Expectancy should be measured net of actual fees, impact, slippage, funding, failed transactions, and all partials. A strategy can have a high win rate and still lose if losses are much larger than wins. A strategy with positive historical expectancy can still be unusable if drawdowns, tail losses, or capacity exceed the account’s tolerance.
Sample size matters. Ten wins in a row do not prove a durable edge; a small number of live outcomes gives a wide uncertainty interval. Track the distribution and confidence, not just the point estimate. Report open/unrealized positions separately from closed positions and do not treat a favorable mark as realized profit.
8.4 Partial profit-taking and trailing stops
Partial exits make sense only when the benefit (risk reduction, securing gains, or matching the strategy’s tested distribution) outweighs extra fees, impact, and reduced exposure to winners. Each partial must update the remaining quantity, cost basis, stop risk, and realized result correctly. A “runner” is just the remaining position and still needs a thesis, a stop, and an exit route.
Move a trailing stop only according to the setup’s rule, such as after a confirmed higher low or a meaningful favorable excursion. Do not let a fixed percentage force an early exit from every normal fluctuation, or allow a trailing rule to ignore a structural failure.
9. The trading plan card
Complete this before entry. If a field cannot be answered, use WAIT/PASS until it can.
Instrument / exact asset identity:
Venue, chain, pool or order book:
Trading mode: LIVE / PAPER / SHADOW
Market regime and benchmark context:
Setup family and why it is present now:
Trigger and intended entry method:
Expected entry price and quote freshness:
Structural invalidation:
Exit route and expected exit cost:
Expected holding horizon:
Target / reward distribution:
Fees, spread, impact, slippage, funding, failure allowance:
Net expectancy estimate and uncertainty:
Risk budget, position size, portfolio exposure after entry:
What evidence would cancel the trade before stop?
Selected strategy owner / tactic:
Decision: ENTER / SMALL PROBE / WAIT / PASS
One sentence: what would prove this idea wrong?
Pre-trade questions
Am I trading the exact asset I intended to trade?
Is the quote fresh, executable, and for this venue and size?
What is the setup and trigger, specifically?
Where is the thesis invalidated, and can I actually exit there?
What is the next likely supply/resistance area, and is there enough room after costs?
Am I late, chasing, revenge trading, or trying to make back a loss?
What happens if the order times out, fills partially, or the route fails?
Is there already correlated exposure or an open position in this asset?
What observation would make me cancel or close even before the planned stop?
“Pass” conditions
Pass or reduce risk if any of these are true:
asset, market, chain, or quote identity is unresolved;
data is stale, contradictory, or dimensionally invalid;
position cannot be sold through a tested or sufficiently reliable route;
costs or impact are unknown and potentially larger than the expected move;
there is no clear trigger or no structural invalidation;
upside is blocked by nearby resistance while invalidation is far away;
the position would breach risk, concentration, or open-slot limits;
a safety, custody, venue, or network problem impairs execution;
the only thesis is a social post, high score, vague “undervalued” claim, or recent pump.
10. Exit discipline
Structural exit: close or reduce when the setup’s defining structure fails. The stop belongs to the thesis, not to the trader’s hope.
Integrity exit: act when asset identity, sellability, route, liquidity, supply, or data quality becomes untrustworthy. Safety conditions may need immediate action without another model vote.
Target exit: use planned levels or a tested distribution. Do not assume a target is reached because the asset has already risen substantially.
Time exit: if the expected move does not occur within the strategy’s horizon, reassess or close. Capital and attention have opportunity costs.
Market-regime exit: if the broad regime changes and the strategy is not designed for it, reduce risk or reclassify the setup.
Operational exit: reconcile before retrying ambiguous transactions. Avoid duplicate buys or sells from blind retries.
No revenge exit/entry: after a loss, do not immediately increase size or loosen rules to get back to breakeven.
Never turn an invalidated short-term trade into a long-term investment without a new, separately evaluated thesis and risk plan.
11. Strategy research and honest testing
A strategy should be evaluated as a complete decision and execution process, not just a predictor.
11.1 Define the experiment
Freeze a clear strategy definition: eligible assets, regime, setup, trigger, entry, invalidation, exit, costs, and sizing.
Keep training, validation, and final test periods separated in time. Use walk-forward evaluation so each decision only sees information that would have been available then.
Include delisted, migrated, rugged, abandoned, and failed assets where relevant; do not test only today’s survivors.
Include realistic spreads, fees, price impact, failed orders, partial fills, latency, and quote age. Avoid same-bar look-ahead (using a candle’s close to assume a fill earlier inside that candle).
Compare to simple baselines such as buy-and-hold, cash, and a regime-matched benchmark. A complex strategy should beat the relevant baseline after cost and risk.
Record every trial and parameter choice. Repeatedly trying variants and reporting only the winner creates selection bias.
11.2 Measure the right outcomes
Track net expectancy, average win/loss, payoff ratio, profit factor, maximum drawdown, worst streak, tail loss, turnover, exposure time, capacity, fill rate, slippage, fees, route failure rate, and confidence intervals. Break results down by asset class, venue, strategy, setup, market regime, and liquidity band. Do not fragment into tiny cohorts and call noise “edge.”
11.3 Separate four questions
Was the observation valid? Correct identity, timestamp, price, supply, market, and feature values?
Was the forecast useful? Did it estimate direction, magnitude, or distribution with calibration?
Was the decision good? Did the setup offer positive, risk-bounded net expectancy at that moment?
Was execution good? Did the real order match the intended route, size, and price assumptions?
A failed transaction should train execution reliability, not count as a losing market forecast if no position was opened. A good signal with a bad fill is still a bad realized trade, but its diagnosis differs. An unconfirmed order is not a settled win or loss.
12. A shared expert baseline and specialist strategies
For a multi-lane or AI-assisted trading system, begin with one stamped evidence snapshot for each unique candidate. Make each specialist explain its mandate and add strategy-specific interpretation, rather than having every subsystem independently recalculate the same facts or vote on the same correlated features.
Shared baseline fields
exact asset, market, venue, chain, and source identity;
timestamp, freshness, confidence, and data-quality flags for every input;
benchmark and asset regime, volatility, and relative strength;
setup family, phase, trigger, invalidation, and holding horizon;
executable liquidity/depth, route confidence, and expected entry/exit prices;
fees, spread, impact, slippage, funding, and failure allowance;
supply, authority, holder, and security-risk evidence where applicable;
estimated return distribution, uncertainty, and assumptions;
portfolio risk, correlated exposures, and risk budget after the proposed trade.
Specialist responsibilities
Each specialist (for example trend, range, launch, liquidity, or event trader) should say:
what market behavior it is designed to exploit;
the conditions under which it is active;
the trigger and invalidation it requires;
the holding horizon and exit behavior;
the evidence that differentiates its edge from the baseline;
what it will do when data is missing or the regime is outside its mandate.
The ensemble should resolve ownership explicitly. One selected strategy owns the live trade and receives its realized outcome. Other lane votes may be retained as counterfactual or shadow evidence but must not all be credited as independent live trades. Use hierarchical learning: global evidence informs regime/setup cohorts; those inform lane/tactic cohorts. Small samples should remain uncertain and shrink toward broader evidence rather than becoming hard rules after one or two trades.
Keep LIVE, PAPER, and SHADOW results separate. Paper can help test logic, but paper execution does not establish live edge. The predictive model can advise with uncertainty; execution controls must be based on verified balances, unique orders, fills, inventory, and safe exit paths. Missing evidence is uncertainty, not evidence of positive expectancy or automatic evidence of negative expectancy.
13. Common traps and the professional response
Trap
Why it hurts
Better response
Chasing a vertical move
Entry is late; invalidation is far; slippage and reversal risk rise
Wait for a base/retest or pass
“It is down, so it is cheap”
Price decline can reflect permanent impairment or supply pressure
Require a reversal trigger and thesis
Averaging down by default
Increases exposure while evidence worsens
Add only at a preplanned level with total risk recalculated
Treating a green P&L mark as cash
Marks may be stale or impossible to realize at size
Use executable exit quotes and settled fills
Believing market cap equals liquidity
Valuation metric does not say what you can sell for
Model route depth and price impact
Counting scans as trades
Inflates evidence and makes weak samples look large
Count unique finalized position outcomes
High win rate as proof
Small wins may be overwhelmed by rare large losses and costs
Review expectancy, tail loss, and drawdown
Treating all indicators as independent
Correlated transformations add little new evidence
Group related features and calibrate their incremental value
Backtest optimization
Many trials can fit noise; future data differs
Walk forward, log all trials, test simple baselines
Fixed percentage stops everywhere
Volatility differs across assets and setups
Use structural invalidation, then size from the distance
Blindly increasing trade count or size
More exposure can amplify negative expectancy
Improve evidence and execution before scaling
Overconfident narratives
Conviction is not a probability estimate
Write the disconfirming evidence first
“Guaranteed” returns or secret signals
Reliable markets contain uncertainty; guarantees often signal fraud
Verify sources, custody, venue, and claims independently
14. Daily operating rhythm
Before the session
Verify wallet/account balances, open orders, held inventory, and system mode.
Check venue, chain, oracle, quote, and RPC health; verify clock and data freshness.
Review major market levels, scheduled catalysts, unlocks, and known operational risks.
Set session risk limits and decide what conditions will pause entries.
Confirm that protective exit and reconciliation paths are operating.
During the session
Recheck candidate identity, quote age, depth, and costs at decision time.
Prevent duplicate entries in the same asset unless pyramiding is explicitly part of the tested strategy.
Update exposure after fills and partials; account for pending orders.
Prioritize held-position monitoring and exit execution over discovering more candidates.
Pause new entries when balances, fills, or order status cannot be reconciled reliably.
After the session
Reconcile journal, actual fills, fees, balances, and open inventory.
Classify every outcome as closed, open, failed-before-fill, partially filled, or unresolved.
Review losses for process, signal, execution, data, or safety causes; do not force every loss into a model label.
Review unusual fills, failed routes, and divergence between quoted and realized price.
Change strategy parameters only through a measured versioned process, then re-test.
15. Quick glossary
Basis: price difference between related instruments or venues.
Breakout acceptance: price remains beyond a range boundary rather than briefly wicking through it.
Drawdown: decline from a prior account or strategy peak.
Expectancy: average net outcome per trade over a defined cohort.
Funding: periodic payment between holders of perpetual futures positions; it can change direction and magnitude.
Impact / price impact: movement in market price caused by the trade itself.
Liquidity / depth: ability to trade size without excessive price movement; it can disappear.
Mark price: valuation reference that may differ from the price available for an actual exit.
Open interest: outstanding derivatives contracts, not a direct measure of bullishness.
R: the initial planned risk of a trade; results can be compared in multiples of this risk.
Reclaim: price returns above or below a previously lost level and holds it.
Slippage: difference between expected and realized execution price.
Spread: difference between best bid and best ask.
Unrealized P&L: marked estimate on an open position; not a settled result.
Walk-forward test: repeated out-of-sample test where models are fit only on information available before each test period.
16. One-page decision card
IDENTITY
Exact asset / mint / contract confirmed?                     YES / NO
Correct venue, pair, quote asset, and route?                   YES / NO
Data timestamp fresh and sources consistent?                   YES / NO

SETUP
Regime (benchmark + asset):
Named setup and why it applies now:
Trigger and planned entry:
Invalidation and expected holding horizon:

EXECUTION
Executable exit route confirmed for intended size?             YES / NO
Estimated fees + spread + impact + slippage + funding + failures:
Quote age / route confidence:

RISK
Account risk budget:
Loss fraction to invalidation, including costs:
Position size from risk formula:
Portfolio / correlated exposure after entry:
Worst plausible loss if stop or route fails:

EDGE
Expected gross outcome distribution:
Conservative net expectancy and uncertainty:
What evidence would change the decision?

OWNER AND ACTION
Selected lane / trader / tactic:
Decision: ENTER / SMALL PROBE / WAIT / PASS
Reason in one sentence:

If a required YES/NO field is unresolved, do not convert missing information into a positive signal.
Sources and further reading
The following are useful references for mechanics and risk; they do not establish that a particular strategy is profitable.
CFTC, Understand the Risks of Virtual Currency Trading
CFTC, Beware Virtual Currency Pump-and-Dump Schemes
CFTC, Use Caution When Buying Digital Coins or Tokens
SEC Investor.gov, Crypto Asset Custody Basics for Retail Investors
Solana, Token basics and authorities
Solana, Token-2022 extensions
Solana, Set token authority
Uniswap Support, Price impact
Uniswap Support, Price impact vs. price slippage
Uniswap Developers, Swaps
Coinbase, Order types
CME Group, Proper position size
CME Group, Risk management and your trade plan
Hudson & Urquhart, Technical Trading and Cryptocurrencies
Svogun & Bazán-Palomino, Technical analysis in cryptocurrency markets: Do transaction costs and bubbles matter?
Frömmel & Deprez, Are Simple Technical Trading Rules Profitable in Bitcoin Markets?
