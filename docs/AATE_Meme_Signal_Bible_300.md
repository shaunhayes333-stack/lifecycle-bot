# AATE MEME TRADER — 300 HANDWRITTEN SIGNALS & SETUPS

**Research specification • 17 November 2026 • Solana spot meme tokens**

**Scope:** 240 distinct observable signals plus 60 fully described, multi-signal trade setups. The signals are hand-authored hypotheses for discovery, entry timing, position management, and protection. **They are not established sources of profit, measured win rates, backtest results, or live-trading authorization.**

## How AATE should interpret this book

1. **One strategy owns an entry.** Raw signals S001–S240 produce signed evidence: positive, negative, neutral, or unavailable. They do not each veto a trade. Recipes T241–T300 are candidate strategies, not executable orders until tested.
2. **Avoid duplicate evidence.** Ten variations of rising buy volume are one *flow* family, not ten independent confirmations. Use family-level caps and record the underlying event IDs.
3. **Separate timing and provenance.** A launch-second scalp, a 15-minute consolidation, a post-migration reclaim and a 4-hour trend continuation have different baselines. Tag each signal with event-time, receive-time, age, venue, market cap, liquidity, signal horizon, source, completeness, and confidence.
4. **Two-stage authority.** Soft evidence adjusts setup score and sizing; genuine hard safety failures (untradeable token, malicious permissions, impossible/no executable sell path, compromised pricing, exceeding capital exposure) stop or reduce execution. Missing optional history is **UNKNOWN**, not automatically **BEARISH**.
5. **Pricing is protocol-aware.** Derive Pump curve quotes only while the verified active venue is that curve. After PumpSwap / Raydium migration, use the verified executable pool/route. Verify mint, token decimals, base/quote orientation and fees. A mint-address suffix alone is never authoritative evidence of the current venue.
6. **Measure the sell, not the chart.** Maintain last trustworthy traded price, depth-weighted mark, fresh executable buy quote, fresh executable sell quote, confirmed entry basis, after-fee liquidation value and realized proceeds as distinct values. Reject stale, impossible and single-print phantom peaks from ATR, drawdown and training labels.
7. **Data is time-ordered.** Backfill history first or reconcile streaming overlap by signature/slot; ingest fast streams without discarding events; de-duplicate them; record source freshness. Features and labels must use information actually available by decision time.
8. **No fixed universal market-cap floor.** Research bands include ~US$2k–10k, 10k–30k, 30k–100k, 100k–500k and 500k+, adjusted for token age, venue, float, quote liquidity, fees, volatility and observed sample size. A low market cap is not evidence of safety or upside.
9. **Tune per lane.** The numbers in examples are exploratory seeds, never global requirements. Use rolling percentiles, matched historical cohorts and after-cost outcomes; maintain immutable control sets and rollback conditions. Distinguish paper-only, shadow, and canonical live fills.
10. **Exit is independent.** Entry rejection does not block protective selling. Sell-path quotes, position identity and reconciliation outrank score debates.

### Vocabulary / measurement contract

- **Birth:** verified mint or first successful trade, recorded separately. **Age:** since first trade unless specified. **P/C/M:** Pump curve / other bonding curve / live AMM. **Early:** 0–2m; **launch:** 2–15m; **developing:** 15–60m; **established:** 1h–1d; **mature:** older.
- **Buy/sell:** on-chain swap direction from *the trader's point of view*, not token transfer direction. **Net quote flow:** genuine buy-side quote paid less genuine sell-side quote received, net of known transfers, split routes, duplicate events and internal recycling. **Distinct buyers:** distinct economic wallets, with linked-funding clusters estimated separately.
- **VWAP:** volume-weighted average on verified swaps. **R:** initial distance between executed entry and structural invalidation, in expected after-cost terms. **Expected edge:** predicted exit proceeds minus executable purchase cost, expected liquidation/slippage, priority charges, platform fees and adverse selection, with uncertainty interval.
- **Rolling baseline:** same age, cap, venue, market-regime and time-of-day cohort; compare 15s/30s/1m/5m/15m windows without future knowledge. Count only independently confirmed samples; report sample size.
- **Signals have an ON and OFF condition.** ON means observable evidence exists, never an order by itself. OFF means evidence has expired, reversed, or become untrustworthy; it is not necessarily a short signal.
- **Signal provenance:** `CHAIN` (validated protocol events), `DERIVED` (computed from trusted events), `EXT` (social/metadata/source outside chain), `EXEC` (executable route/quotes), `AUDIT` (data quality and lineage). Social and wallet-label intelligence cannot silently become deterministic hard rules.

---

## Part I — 240 individually authored signals

### A. Launch ignition / first 120 seconds — S001–S012

- **S001 — Genuine opening bids.** ON: independent wallets execute at least three successful purchases in the first 20s, with rising net quote paid; OFF: apparent buys are transfers, same-funder hops or failed transactions.
- **S002 — First-fifteen-second expansion.** ON: verified 15s positive return and quote inflow both exceed the token's opening 15s baseline; OFF: either falls back below baseline or first print fails validation.
- **S003 — Second-wave arrival.** ON: a new cluster of buyers arrives 20–60s after the earliest buyers without repeating the same funding wallets; OFF: activity remains confined to the initial cluster.
- **S004 — No-launch-panic absorption.** ON: the first meaningful sell is absorbed without breaking the pre-sell level and distinct bids continue; OFF: the level breaks on persistent independent selling.
- **S005 — Rising participation density.** ON: unique buyers per ten-second bucket rise across three consecutive buckets; OFF: buyer count stagnates while activity comes from repeated wallets.
- **S006 — Open-high retest.** ON: price breaks its verified first-30s high, pulls back to it and holds with net positive flow; OFF: a valid close and sell pressure reclaim the range underneath.
- **S007 — Early flat-to-fast transition.** ON: a first-minute balance of two-sided trading transitions into expanding positive return and genuine buy notional; OFF: spike immediately returns into the balance range.
- **S008 — Initial rug-sale survival.** ON: a significant early seller exits, yet price makes a higher low as new buyers replace their quote flow; OFF: the apparent higher low is sustained by one wallet alone.
- **S009 — First-minute accumulation staircase.** ON: three successively higher 10–20s lows occur with expanding independent buy participation; OFF: a lower low prints on credible volume.
- **S010 — Early bid quality.** ON: median incoming buy size rises while buyer count is stable or increasing and quote liquidity supports the sizes; OFF: one isolated whale produces the entire increase.
- **S011 — Launch volatility compression.** ON: verified traded range contracts over 30–60s while net inflow stays positive; OFF: compression resolves downward or volume evaporates completely.
- **S012 — Fresh launch reactivation.** ON: a token dormant for 30–90s produces a new high with a new cohort of buyers; OFF: the move is a recycled-wallet burst without genuine turnover.

### B. Bonding-curve-specific — S013–S024

- **S013 — Curve reserve acceleration.** ON: actual quote-reserve increase per 15s accelerates on validated curve swaps; OFF: apparent growth is caused by wrong reserve fields or duplicate events.
- **S014 — Curve step-up with breadth.** ON: two successive curve price advances coincide with two increases in independent buyer count; OFF: the curve rises from one concentrated purchase.
- **S015 — Curve progress consistency.** ON: progression toward verified graduation threshold rises steadily for at least four buckets; OFF: reserve progress reverses on net sells.
- **S016 — Curve sell-shock recovery.** ON: a sell-induced curve drawdown recovers at least half its impact through broad new buying; OFF: recovery is a single self-funded wallet.
- **S017 — Curve quote-edge realism.** ON: executable expected buy and sell amounts agree directionally with reserve math and actual fees; OFF: wrong decimals, reserve orientation or incompatible curve version breaks agreement.
- **S018 — Curve buy-size ladder.** ON: early independent buyers move from exploratory small buys to multiple medium buys without a single wallet dominating flow; OFF: size growth becomes highly concentrated.
- **S019 — Curve stall relief.** ON: progress stalls, seller count declines and new net quote inflow resumes before price breaks its base; OFF: fresh sells restart before a reclaim.
- **S020 — Late-curve breadth test.** ON: with graduation approaching, new entrants outnumber repeat buyers and buy flow remains positive; OFF: only first-minute insiders keep topping up.
- **S021 — Curve impact moderation.** ON: a standard AATE test order has manageable estimated entry-and-exit price impact relative to expected opportunity; OFF: the quote impact consumes expected edge.
- **S022 — Curve pressure divergence.** ON: price briefly consolidates while curve-side independent quote inflow keeps rising; OFF: price cannot recover and net flow turns negative.
- **S023 — Curve sell-exhaustion wedge.** ON: successive curve sell bursts produce smaller price declines while organic buying stays steady; OFF: a new seller breaks the supported floor.
- **S024 — Verified curve-completion event.** ON: on-chain completion flag and reserves confirm transition readiness; OFF: unverified UI progress or a theoretical threshold alone is all that exists.

### C. Graduation / migration transition — S025–S036

- **S025 — Verified migration.** ON: exact token's completed curve and new pool linkage are verified on-chain; OFF: only an anticipated graduation timestamp or ticker match exists.
- **S026 — Migration continuity.** ON: initial AMM price and reserves reconcile with the completed curve within accounted fees/rounding; OFF: discontinuity cannot be explained by pool math.
- **S027 — Post-migration first buyers.** ON: independent buyers trade the actual new pool within its first minute with net positive quote inflow; OFF: only migration transfers are misclassified as buys.
- **S028 — Opening-pool high reclaim.** ON: price reclaims the verified first-minute AMM high after testing it; OFF: the reclaim fails with widening sell pressure.
- **S029 — Migration dip defended.** ON: AMM sells briefly undercut opening value but a new higher low forms with fresh buying; OFF: the opening value remains overhead and sellers accelerate.
- **S030 — Liquidity continuity check.** ON: post-graduation quote liquidity and executable routes are live and stable after pool registration; OFF: route points to closed curve or zeroed reserve.
- **S031 — Post-migration volume handoff.** ON: new-pool swap turnover exceeds the final curve's comparable period without dominant internal recycling; OFF: supposed volume is only bookkeeping transfers.
- **S032 — Migration pause completion.** ON: during a quote/route pause, independent on-chain activity resumes and both buy and sell simulation succeed; OFF: sell simulation fails.
- **S033 — Post-migration second wave.** ON: after first opening burst, a distinct group of new buyers drives a second push through opening high; OFF: no new participation supports it.
- **S034 — Graduation trap warning.** ON: new AMM opens with a fast markup but immediate repeated large holder selling and falling breadth; OFF: sellers are absorbed and breadth recovers.
- **S035 — Curve-to-pool reset.** ON: mark the venue switch and start pool-local VWAP/high/low series without carrying false old-pool ticks; OFF: identity mapping is ambiguous.
- **S036 — Migration-level retest.** ON: price revisits the verified pool-opening value and rebounds on net buying; OFF: closes below it with continued net outflow.

### D. New AMM pool / price discovery — S037–S048

- **S037 — First honest traded range.** ON: a few dozen verified swaps form a usable initial high/low rather than one isolated print; OFF: range comes from stale or low-size outliers.
- **S038 — Pool open-drive.** ON: three consecutive event-time windows show higher marks and positive independent net flow; OFF: a failed test sell or one-print jump explains the move.
- **S039 — First VWAP acceptance.** ON: price holds above first viable pool VWAP across two meaningful pullbacks; OFF: falls below VWAP on repeated net selling.
- **S040 — New-pool price-discovery balance.** ON: fast launch spike settles into lower volatility while quote liquidity and breadth remain; OFF: turnover collapses with no credible bid flow.
- **S041 — Pool-depth strengthening.** ON: executable sell-out proceeds for a fixed test size improve over three samples at similar market price; OFF: improvement disappears after quote refresh.
- **S042 — Opening-range reclaim.** ON: verified opening high is recaptured after a false breakdown with active participation; OFF: retest fails immediately.
- **S043 — First genuine higher low.** ON: an AMM low forms above opening low with independent purchases and no matching wash cluster; OFF: new low breaks despite comparable buying.
- **S044 — Pool volume diversification.** ON: traded notional becomes spread across more wallets and order sizes, not one repeated actor; OFF: a linked cluster dominates.
- **S045 — Pool resiliency to sell.** ON: expected liquidation value for a fixed position recovers promptly after a moderate sell without extra liquidity withdrawal; OFF: pool quotes remain impaired.
- **S046 — Pool route redundancy.** ON: two independently computed valid sell paths produce compatible outputs after accounting for fees; OFF: one path quotes through stale or wrong token accounts.
- **S047 — Opening-pool breakout acceptance.** ON: price spends a meaningful event-time window above early resistance with continuing volume; OFF: returns under resistance with negative flow.
- **S048 — New-pool second base.** ON: first push cools, holds above opening range and produces fresh accumulation; OFF: second base resolves below opening value.

### E. Price momentum and acceleration — S049–S060

- **S049 — Three-horizon agreement.** ON: verified returns for 15s, 1m and 5m are positive without being dominated by one tick; OFF: two horizons reverse on credible volume.
- **S050 — Return acceleration.** ON: later equal-duration price gain exceeds earlier gain while buyer breadth expands; OFF: acceleration is just a low-liquidity print.
- **S051 — Clean higher-high structure.** ON: three confirmed swing highs and two swing lows rise in sequence; OFF: most recent confirmed swing low breaks.
- **S052 — Momentum after consolidation.** ON: positive 5m momentum resumes after a low-range hold above local VWAP; OFF: follow-through fails by the setup's timeout.
- **S053 — Slope improvement.** ON: 1m trend slope steepens while trades remain distributed across wallets; OFF: slope flattens as sell notional grows.
- **S054 — Real breakout impulse.** ON: return is accompanied by excess traded quote volume and actual liquidation-value improvement; OFF: quoted sell value fails to improve.
- **S055 — Acceleration without exhaustion.** ON: fast rise still has widening buyer breadth and no increasing upper-wick ratio; OFF: volume peaks but breadth contracts.
- **S056 — Trend through volatility.** ON: successive swings produce positive net advances despite larger intrabar noise; OFF: noise becomes directional selling.
- **S057 — Price/flow agreement.** ON: rising mark, net buying and distinct buyers agree in same event-time window; OFF: price rises while quote flow turns net negative.
- **S058 — Momentum pullback tolerance.** ON: correction stays inside historical pullback distribution for same age/volatility cohort; OFF: drawdown crosses structural level, not arbitrary percentage.
- **S059 — Multi-venue normalized momentum.** ON: when legitimate pools exist, independently derived executable marks trend together; OFF: one tiny pool alone makes the move.
- **S060 — Trend re-ignition.** ON: after velocity decays, a new positive velocity burst breaks the latest confirmed swing high; OFF: price cannot exceed that high.

### F. Candle shape / chart grammar — S061–S072

- **S061 — Demand close.** ON: verified candle closes in top quarter of its genuine range with increasing quote-volume and few late sells; OFF: reconstructed ticks were invalid.
- **S062 — Lower-wick defence.** ON: deep intrabar dip finishes above its midpoint with genuine buyers supplying the recovery; OFF: recovery is a lone dust print.
- **S063 — Higher-low three-candle.** ON: three adjacent candles have rising lows, stable/increasing buy volume and higher final closes; OFF: next candle closes below the middle low.
- **S064 — Bullish engulfing with volume.** ON: current body engulfs prior red body and matched-time quote volume rises; OFF: candle contains only one whale fill.
- **S065 — Inside-bar expansion.** ON: inside range resolves above parent high with fresh buyer breadth; OFF: price re-enters parent range before holding.
- **S066 — Two-candle reclaim.** ON: failed breakdown below support is followed by close back above support on positive net flow; OFF: second attempt breaks the low.
- **S067 — Short-upper-wick breakout.** ON: breakout candle retains at least most of its body at close and has multiple genuine trades near highs; OFF: wick expands on seller domination.
- **S068 — Staircase candles.** ON: four or more small positive bodies rise without a terminal blow-off candle, alongside orderly flow; OFF: sharp reversal closes below two prior bodies.
- **S069 — Volume-led doji.** ON: indecision candle at support has large genuine turnover followed by an upside break; OFF: break is downward or absent.
- **S070 — Red-candle absorption.** ON: red body shrinks while sell volume remains similar and independent dip buys rise; OFF: next sell wave makes a new low.
- **S071 — High-tight flag.** ON: small pullback candles hold near a genuine impulse high while turnover contracts; OFF: closes below impulse midpoint on net selling.
- **S072 — Reversal-cluster authenticity.** ON: candle reversal is supported by actual swaps, multiple fills and reliable marks; OFF: it exists only in an OHLC feed with phantom extremes.

### G. Volume / turnover and participation — S073–S084

- **S073 — Volume ignition.** ON: genuine quote turnover exceeds matched-cohort expected volume for two adjacent windows; OFF: duplicated signatures or internal transfers explain spike.
- **S074 — Volume ladder.** ON: 15s, 30s and 1m quote turnover increase progressively, without buyer concentration spike; OFF: one address accounts for most of the ladder.
- **S075 — Up-volume dominance.** ON: verified buy-side quote notional grows faster than sell-side notional over two windows; OFF: sell-side overtakes buyers.
- **S076 — High-volume narrow pullback.** ON: a red or flat interval handles substantial selling without substantial price damage; OFF: price breaks the defended level.
- **S077 — Dry-up before next leg.** ON: consolidation volume contracts while support persists, then real buy turnover re-expands; OFF: market remains inactive without trigger.
- **S078 — Turnover per holder growth.** ON: actual turnover rises faster than the active-holder count but is distributed among multiple unrelated wallets; OFF: wash-like churn dominates.
- **S079 — New-money acceleration.** ON: net inflowing quote from previously inactive wallets increases in consecutive periods; OFF: inflow is recycled proceeds from linked wallets.
- **S080 — Absorption volume shelf.** ON: repeated high-volume tests occur at same price shelf and lower lows stop forming; OFF: level breaks on renewed genuine outflow.
- **S081 — Breakout volume confirmation.** ON: move above a well-observed range coincides with relative volume expansion and high participation; OFF: breakout has no additional new-money flow.
- **S082 — Sell-volume contraction.** ON: successive pullbacks feature declining executed sell notional at similar depth; OFF: a fresh expansion produces lower lows.
- **S083 — Sustained turnover.** ON: positive quote turnover remains above matched baseline for at least three windows instead of one transient spike; OFF: later windows return to baseline.
- **S084 — Volume divergence warning.** ON: fresh price highs occur on falling real turnover and fewer distinct buyers; OFF: fresh demand restores breadth.

### H. Buyer breadth and cohort behaviour — S085–S096

- **S085 — Buyer-count acceleration.** ON: distinct buying wallet count increases across three windows after clustering linked wallets; OFF: de-duplication removes the apparent acceleration.
- **S086 — New buyers lead price.** ON: genuinely new wallets increase before price breaks its range; OFF: buying arrives only after price already extended.
- **S087 — Broad small-ticket wave.** ON: many distinct small buys accumulate meaningful net quote flow instead of one big buyer; OFF: transactions are bots splitting one funded order.
- **S088 — First-to-second cohort handoff.** ON: wallets entering in minute 2–5 supply more fresh buys than wallets from first minute; OFF: early wallets are the only remaining buyers.
- **S089 — Broad dip participation.** ON: buyers from at least two independent entry cohorts defend a pullback; OFF: only underwater early holders support it.
- **S090 — Buyer retention.** ON: early non-clustered buyers avoid mass selling while new demand expands; OFF: first cohorts distribute faster than new ones arrive.
- **S091 — Repeat-buyer conviction.** ON: independently funded prior winners add on a new higher low and new buyers also appear; OFF: repeat buys alone are propping up price.
- **S092 — Buyer-size diversity.** ON: a spread of small, medium and large economically independent buys emerges; OFF: size distribution collapses to one controller.
- **S093 — Buyer/seller breadth improvement.** ON: unique net buyers increasingly outnumber unique net sellers across comparable windows; OFF: count rises only because each buyer transacts dust.
- **S094 — First-time-holder formation.** ON: unique beneficial holders with non-dust balances rise after excluding program accounts; OFF: new accounts empty immediately or are linked.
- **S095 — Buyer recovery after red candle.** ON: new-buyer arrivals recover faster than price after a sell shock; OFF: arrivals stop at the next attempt to reclaim.
- **S096 — Participation through break.** ON: active buyer cohort expands on both breakout and retest; OFF: retest loses new participants.

### I. Order flow, aggressor imbalance and sequencing — S097–S108

- **S097 — Positive net executed flow.** ON: verified buyer-paid quote exceeds seller-received quote across an event-time window; OFF: net flow turns negative.
- **S098 — Increasing positive imbalance.** ON: buyer/seller quote-notional imbalance strengthens over three short windows; OFF: normalization reveals only a one-off block trade.
- **S099 — Buy-after-sell resilience.** ON: independent buy orders arrive promptly after repeated sells and restore executable sell value; OFF: response time worsens as sells persist.
- **S100 — Bid streak quality.** ON: successive positive swaps span independent wallets and real quote notional; OFF: one signer or linked funding generates the streak.
- **S101 — Aggressive-sell exhaustion.** ON: sell trades become smaller and less frequent while price stops declining; OFF: new heavy sellers emerge.
- **S102 — Buy sequence maturation.** ON: buys move from isolated tests to sustained, spaced demand rather than a single transaction burst; OFF: sequence disappears after initial block.
- **S103 — Buyer initiative into resistance.** ON: buyers repeatedly pay higher executable marginal prices with broad participation near resistance; OFF: sellers absorb every attempt without progress.
- **S104 — Sell absorption near prior low.** ON: several genuine sells clear at or above prior tested low and are followed by new buys; OFF: liquidity disappears below that low.
- **S105 — Net-flow/price divergence positive.** ON: price is flat over short period yet cumulative independent buy flow increases; OFF: level breaks before a reclaim.
- **S106 — Net-flow/price divergence negative.** ON: price rises while cumulative independent quote outflow grows; OFF: subsequent independent buying reverses the divergence.
- **S107 — Late-buyer follow-through.** ON: buys in final third of the candle continue into next window; OFF: buyers are replaced immediately by distribution.
- **S108 — False positive buy-count audit.** ON: high reported buys fail economic-direction checks, with seller-side token release or self-transfer; OFF: independently decoded swaps corroborate the buys.

### J. Wallet intelligence / funding provenance — S109–S120

- **S109 — Competent-wallet early arrival.** ON: multiple independently funded wallets with *out-of-sample*, after-cost positive histories enter early; OFF: performance claim relies on unrealized peaks or tiny sample.
- **S110 — Independent smart-wallet consensus.** ON: two unrelated successful-wallet clusters buy within a short interval; OFF: clusters have common funders or copy the same upstream signal.
- **S111 — Profitable-wallet add.** ON: a historically effective wallet adds after price consolidates, with corroborating broad flow; OFF: wallet's trades are actually its own liquidity operations.
- **S112 — Informed entry before attention.** ON: reputable wallet cluster enters *before* social and price expansion; OFF: discovery arrived too late to be actionable.
- **S113 — Smart-flow retention.** ON: early skilled wallets retain economic exposure through first pullback; OFF: they distribute the majority of position.
- **S114 — Cross-token wallet rotation.** ON: profitable wallet group rotates out of losing token and into rising token alongside broader confirmation; OFF: rotation is only internal rebalance.
- **S115 — Whale buyer with safe concentration.** ON: a large genuine buyer arrives without dominating control of float or pool; OFF: its single sell could erase the entire expected edge.
- **S116 — Whale dip-defense repeat.** ON: unrelated large holders independently defend the same low across two tests; OFF: holdings are controlled by a single entity.
- **S117 — Creator sale detection.** ON: verified creator/associated beneficial entity sells an abnormal share into buying; OFF: association is unproven or sale is routine protocol payout.
- **S118 — Clustered insider exit.** ON: linked early-funded wallets sell in coordinated sequence before breadth drops; OFF: evidence of a common controller is absent.
- **S119 — Wallet-label staleness.** ON: a historical 'smart wallet' ranking no longer predicts after-cost outcomes in recent samples; OFF: performance recovers in holdout tests.
- **S120 — Copy-trade latency spread.** ON: price has materially moved since observed reference wallet's actual fill, eroding executable copy edge; OFF: fresh quote still leaves tested positive margin.

### K. Holder structure / supply distribution — S121–S132

- **S121 — Beneficial-holder growth.** ON: estimated independent holders with non-dust economic balances rise across several windows; OFF: growth is sybil-created token accounts.
- **S122 — Top-holder concentration falling.** ON: concentration among non-program beneficial holders falls as quote inflow and breadth rise; OFF: supply is merely split across linked addresses.
- **S123 — Early bag distribution absorbed.** ON: first-cohort holdings decline gradually while new holders grow faster and price remains supported; OFF: selling overwhelms genuine new-money inflow.
- **S124 — Creator allocation stability.** ON: verified creator-related allocation is stable during an upward trend; OFF: linked wallets move tokens to selling venues.
- **S125 — Healthy mid-tier formation.** ON: count and aggregate holdings of unrelated mid-sized wallets increase without top-whale control; OFF: wallets are commonly funded or instantly drain.
- **S126 — Holder loss before price loss.** ON: meaningful independent holders begin exiting before the chart breaks; OFF: holders stabilize and price reclaims the previous structure.
- **S127 — New-holder retention.** ON: buyers over last several minutes retain a meaningful fraction into next consolidation; OFF: new holders rapidly flip out and stop returning.
- **S128 — Concentration event.** ON: one holder/cluster's tradable supply grows to a level able to overwhelm current liquidity; OFF: economic control or supply estimate is revised downward with proof.
- **S129 — Supply arithmetic coherence.** ON: total supply, decimals, token-account balances and excluded pool/program vaults reconcile; OFF: unexplained mint/burn/supply movements appear.
- **S130 — Distribution under resistance.** ON: large older holders reduce balances repeatedly near each new price high; OFF: new demand absorbs sales and older balances stabilize.
- **S131 — Broad holder recovery.** ON: after a correction, unique non-dust holders recover before price's prior high; OFF: counts recover only from duplicated empty accounts.
- **S132 — Holder churn without net growth.** ON: high apparent holder turnover produces no increase in independent economic exposure; OFF: new retained owners demonstrably replace churn.

### L. Liquidity, executable depth and transaction economics — S133–S144

- **S133 — Sell-depth improvement.** ON: a fixed-position executable sell quote improves relative to observed mark over repeated samples; OFF: route grows stale or worsens at next refresh.
- **S134 — Two-sided quote availability.** ON: valid buy and sell routes for same verified mint/venue remain executable across adjacent checks; OFF: sell route or simulation fails.
- **S135 — Price-impact/alpha headroom.** ON: expected after-cost opportunity exceeds round-trip price impact, fees and adverse-fill estimate with tested margin; OFF: execution costs erase margin.
- **S136 — Liquidity expansion with demand.** ON: usable sell depth improves while independent buying and turnover grow; OFF: pool balance increase is illusory or unrelated to sell depth.
- **S137 — Shock recovery in liquidation quote.** ON: after moderate trade-size sale, same-size sell quote recovers promptly without phantom marks; OFF: executable exit remains impaired.
- **S138 — Liquidity concentration risk.** ON: most observed volume relies on a single shallow or removable pool; OFF: resilient independent routes demonstrably exist.
- **S139 — Fee-adjusted microcap viability.** ON: small-token expected move still exceeds minimum plausible round-trip costs at actual proposed position size; OFF: fixed fees/slippage dominate.
- **S140 — Graduated-pool impact reset.** ON: fresh post-migration quotes materially improve capacity at same proposed size; OFF: estimates mix pre- and post-migration routes.
- **S141 — Trade-size elasticity.** ON: reducing proposed size sharply improves *net* expected return per risk unit; OFF: edge remains negative at all useful sizes.
- **S142 — Pool reserve removal alarm.** ON: credible withdraw/drain reduces exit depth materially; OFF: transaction proves routine protocol migration with working successor pool.
- **S143 — Liquidity/turnover mismatch.** ON: extraordinary reported turnover occurs on minimal real reserves with no matching legitimate swap flow; OFF: independent decoded events reconcile it.
- **S144 — Exit-quote deterioration.** ON: actual sell quote collapses relative to reliable last-traded mark while mark looks unchanged; OFF: quote recovers and both sources reconcile.

### M. Pullbacks, reloads and continuation bases — S145–S156

- **S145 — First pullback higher low.** ON: first meaningful retracement after an impulse stays above its starting base and buyers return; OFF: base breaks with increased selling.
- **S146 — VWAP reclaim.** ON: price pierces below verified short-term VWAP but closes back above with positive flow; OFF: retest fails from below.
- **S147 — Falling-volume correction.** ON: pullback has progressively less authentic sell notional without destructive depth withdrawal; OFF: sell notional expands into lower lows.
- **S148 — Dip-buy breadth turn.** ON: net buyers and distinct new buyers accelerate near support before price flips upward; OFF: buy activity vanishes on retest.
- **S149 — Two-test support.** ON: same structural region survives two independent sell attacks and second low is not deeper; OFF: third test fails with negative flow.
- **S150 — Impulse midpoint defence.** ON: correction holds above midpoint of *verified* impulse price move with stronger buy flow on recovery; OFF: midpoint breaks on meaningful turnover.
- **S151 — Sideways reload.** ON: price moves laterally for multiple windows as sell volume fades and new buyers accumulate; OFF: balance breaks down.
- **S152 — Shallow retest of breakout.** ON: post-breakout pullback holds at former resistance and executes fresh buys; OFF: former resistance becomes resistance again.
- **S153 — Flag top acceptance.** ON: after brief declining channel, price closes above flag upper bound with volume recovery; OFF: closes back into channel.
- **S154 — Local seller exhaustion.** ON: each descending push has smaller sell imbalance and shorter duration; OFF: fresh lower low coincides with larger sell wave.
- **S155 — Higher-low plus seller exit.** ON: recurring early seller appears finished and a higher low forms on independent buyers; OFF: wallet resumes large disposal.
- **S156 — Reclaim before aging out.** ON: token regains support within the strategy-specific expected recovery window; OFF: prolonged drift breaches timing thesis even without a price stop.

### N. Breakouts / expansion / runaway continuation — S157–S168

- **S157 — Verified range break.** ON: traded marks move above resistance formed by several valid swaps and hold with new buyer flow; OFF: immediate close back inside range.
- **S158 — Break-and-retest.** ON: old resistance becomes support with follow-through buying; OFF: retest rejects below resistance.
- **S159 — Multi-timeframe expansion.** ON: 1m breakout coincides with rising 5m swing structure and positive 15m flow; OFF: larger timeframe already in active distribution.
- **S160 — Volume-leading breakout.** ON: independent buy turnover accelerates *before* breakout rather than chasing one candle; OFF: burst dissipates before price confirms.
- **S161 — Breadth-backed new high.** ON: new verified high is accompanied by additional genuinely new buyers; OFF: each new high has fewer buyers.
- **S162 — Measured range release.** ON: compressed price range expands upward beyond its own volatility-normalized threshold; OFF: rapidly returns to the compression range.
- **S163 — Trend re-entry after flush.** ON: healthy earlier uptrend resumes above recent swing high after one contained sell flush; OFF: large holders keep distributing.
- **S164 — Repeated breakout attack.** ON: each resistance test reaches closer to high while sellers' absorbed size declines; OFF: attempts get weaker and lows descend.
- **S165 — Breakout with improving exit value.** ON: executable sell value at a fixed stake rises in step with observed breakout; OFF: headline price advances but exit proceeds stagnate.
- **S166 — New-high hold test.** ON: after break, price spends two completed event windows above old high rather than one wick; OFF: loses the level on real volume.
- **S167 — Runner continuation stair-step.** ON: after major gain, pullbacks remain above successive structural lows and fresh flow repeatedly returns; OFF: sequence breaks on net outflow.
- **S168 — Late-stage breakout caution.** ON: a breakout occurs far above tested reference value after parabolic expansion with declining breadth; OFF: market rebuilds a genuine support base.

### O. Flushes, capitulations, reversals — S169–S180

- **S169 — Genuine capitulation print.** ON: unusually large confirmed sell volume causes sharp drop, followed by a decisive loss of sell intensity; OFF: sell pressure continues unabated.
- **S170 — Failed breakdown reversal.** ON: price undercuts established support then quickly reclaims it with distinct new buyers; OFF: retest loses support again.
- **S171 — Double-bottom demand.** ON: second test of verified low has less selling and more independent demand; OFF: second bottom is lower on stronger selling.
- **S172 — V-reversal with volume.** ON: after a brief real drawdown, price crosses its starting level on greater buy turnover than sell turnover; OFF: crosses by a dust tick only.
- **S173 — Exhausted seller cluster.** ON: identified heavy sellers stop transacting and new independent wallets buy the remaining supply; OFF: linked sellers reappear.
- **S174 — Failed rug-look recovery.** ON: post-panic token demonstrates a *working sell route*, stable permissions and broad restoration of liquidity; OFF: price recovers only on an unexecutable chart.
- **S175 — First higher low after decline.** ON: confirmed downtrend produces a higher swing low then breaks most recent lower high; OFF: higher low fails.
- **S176 — Momentum reversal in positive flow.** ON: price still down but net quote inflow turns positive and persists through another test; OFF: flow flips negative again.
- **S177 — Recovery VWAP reclaim.** ON: token regains breakdown-window VWAP on credible turnover; OFF: VWAP becomes resistance.
- **S178 — Dead-cat-bounce warning.** ON: bounce occurs on falling unique-buyer count, repeated selling by prior holders and shallow sell depth; OFF: new breadth and depth actually return.
- **S179 — Reclaim without bagholder overhead.** ON: rebound clears level where prior new holders were trapped, without strong distribution; OFF: trapped holders sell heavily into reclaim.
- **S180 — Reversal expiry.** ON: reversal thesis fails to reach its first structural objective within strategy's allowed event-time window; OFF: objective arrives with viable flow before expiry.

### P. Market regime / relative strength / rotation — S181–S192

- **S181 — Relative return leader.** ON: token's reliable after-cost indicative return exceeds matched cohort while market-wide meme returns are flat or weaker; OFF: outperformance vanishes on normalization.
- **S182 — Relative volume leader.** ON: token captures growing share of genuine cohort quote volume and independent buyers; OFF: share only rises because other feeds go missing.
- **S183 — SOL-adjusted strength.** ON: token gains in SOL-denominated executable value and USD-denominated mark; OFF: apparent USD appreciation is solely SOL/USD movement.
- **S184 — Leader survives market dip.** ON: broad meme cohort sells off while candidate holds support and fresh money enters; OFF: candidate follows market lower with lag.
- **S185 — Hot-cap-band breakout.** ON: candidate outperforms peers in same microcap band and launch age, not mature bluechips; OFF: advantage disappears after matched-cohort comparison.
- **S186 — Attention rotation early.** ON: independent new-money flows leave exhausted hot names and enter a newer candidate *before* its breakout; OFF: candidate is late recipient after acceleration.
- **S187 — Sector theme confirmation.** ON: several independently launched theme-linked names strengthen, with candidate leading on flow; OFF: one token alone or false theme labels drive result.
- **S188 — Regime-adjusted risk appetite.** ON: broad market buy breadth, launch survival and liquidity improve across cohorts; OFF: widespread failed launches and spreads worsen.
- **S189 — Risk-off contrarian outlier.** ON: during negative regime, candidate shows unusually robust organic buyers plus sell depth; OFF: apparent robustness is from low event coverage.
- **S190 — Fast-lane opportunity cost.** ON: candidate's tested edge and fill latency compare favorably to another executable setup competing for same capital; OFF: a stronger alternative remains available.
- **S191 — Market-wide exhaustion warning.** ON: many cohort tokens spike but buyer breadth and retained gains deteriorate in parallel; OFF: broad net new money persists.
- **S192 — Regime-transition follow-through.** ON: market-wide flow flips positive and candidate develops a second independently validated bullish window; OFF: regime turns back negative before confirmation.

### Q. Narrative / attention / social demand — S193–S204

- **S193 — Authentic first mention.** ON: traceable independent accounts discuss the token before substantial price breakout; OFF: observed mentions are recycled promotions or timestamped after move.
- **S194 — Attention velocity.** ON: unique independent social authors referencing exact mint increase over several windows; OFF: only bot repost volume grows.
- **S195 — Attention-to-wallet conversion.** ON: new social interest is followed by distinct net-buying wallets with plausible timing; OFF: attention never converts to economic activity.
- **S196 — Theme-onset leader.** ON: candidate is among first genuine tokens in emerging theme and its buyers broaden; OFF: earlier stronger originals dominate it.
- **S197 — Community retention.** ON: unique engaged participants remain after first hype burst and real holders persist; OFF: engagement stops when paid posts stop.
- **S198 — Influencer-timing caution.** ON: high-reach post follows major appreciation and smart-wallet selling has started; OFF: post precedes movement and flow is independently healthy.
- **S199 — Symbol-confusion warning.** ON: ticker/name duplicates obscure which exact mint social traffic references; OFF: contract-mint references are unambiguous.
- **S200 — Multi-platform organic spread.** ON: independently authored mentions grow across two distinct communities and on-chain buyers subsequently increase; OFF: cross-posting originates from one campaign.
- **S201 — Narrative half-life.** ON: new mentions decay faster than holder/flow growth, suggesting brief fad; OFF: genuine repeat interest persists.
- **S202 — Meme remix momentum.** ON: independently created derivatives/edits increase while original token retains net buying; OFF: cloned campaigns inflate counts without genuine interest.
- **S203 — Event catalyst authenticity.** ON: a verifiable external event precedes flow shift in exact mint; OFF: claimed catalyst is fabricated, old or not connected to token.
- **S204 — Social-onchain decoupling.** ON: social excitement grows while distinct buyers, quote inflow and executable mark fall; OFF: flow independently catches up.

### R. Manipulation, exploitability, structural danger — S205–S216

- **S205 — Wash-loop suspicion.** ON: same linked wallets repeatedly alternate buys and sells with little net exposure change; OFF: independent funding and lasting positions contradict common control.
- **S206 — Funding-cluster dominance.** ON: nominally distinct buyers share recent common funding and dominate volume; OFF: economic independence can be established.
- **S207 — Creator/insider dump.** ON: proven creator-linked wallets remove substantial tradable supply into outside purchases; OFF: association or sale interpretation is disproved.
- **S208 — Mint authority hazard.** ON: unexpected live mint authority can expand supply beyond strategy's accepted token design; OFF: on-chain authority is revoked or risk is intentionally modelled.
- **S209 — Freeze/transfer restriction hazard.** ON: permissions/extensions may prevent selling or freeze balances, confirmed by program state or simulation; OFF: verified normal transfers and sell simulation resolve issue.
- **S210 — Malicious transfer-fee exposure.** ON: Token-2022 or custom token transfer fees materially exceed price model / policy; OFF: verified actual transfer fee is acceptable and priced.
- **S211 — Liquidity drain.** ON: sudden real sell-depth collapse accompanies vault removal or abnormal pool operation; OFF: verified migration points to healthy successor liquidity.
- **S212 — Phantom peak.** ON: a tick implies implausible price multiple absent from reserves, executed proceeds and neighboring actual trades; OFF: independent pool math and meaningful fills verify the level.
- **S213 — Spoofed volume.** ON: reported volume lacks corresponding on-chain swap notional or repeatedly double-counts split routes; OFF: all signatures and balance deltas reconcile.
- **S214 — Honeypot / unsellable route.** ON: a normal-size test sell fails on a verified active venue despite apparent buys; OFF: fresh executable test sell and real fills succeed.
- **S215 — Pool impersonation.** ON: token name/symbol matches but mint/program/pool identity does not; OFF: cryptographic mint and verified venue match intended asset.
- **S216 — Post-dump prolonged decay.** ON: token is old relative to its lane, price below valid anchored VWAP, new buyers shrinking and no reversal structure; OFF: credible new demand and structure appear.

### S. Exit / hold / profit protection — S217–S228

- **S217 — Structure trailing exit.** ON: closes breach latest confirmed higher low with real sell pressure; OFF: structure remains intact, so continue managing thesis.
- **S218 — Momentum rollover.** ON: price acceleration turns negative for successive windows as positive flow decays; OFF: speed and breadth recover above local base.
- **S219 — Volume climax distribution.** ON: extreme genuine turnover prints a wide upper wick and net sellers dominate after it; OFF: new highs are accepted with restored demand.
- **S220 — Flow-led scale-out.** ON: price holds a high while early profitable holders distribute and independent new buyers no longer offset them; OFF: new-money inflow covers sales.
- **S221 — Exit-value drawdown.** ON: size-adjusted executable net sell proceeds fall from prior *trusted executable* peak by tested amount; OFF: only untrusted chart prints show drawdown.
- **S222 — Thesis time stop.** ON: expected breakout/recovery has not occurred within matched setup lifespan and after-cost edge decays; OFF: a fresh qualifying setup explicitly replaces old thesis.
- **S223 — Protect capital after expansion.** ON: position has achieved an after-cost profit milestone and structural conditions justify partial de-risking; OFF: test shows premature scaling harms expected payoff.
- **S224 — Runner hold authorization.** ON: genuine higher lows, positive net flow, new buyers and viable exit quotes persist; OFF: two independent evidence families fail together.
- **S225 — Liquidity-first emergency exit.** ON: significant executable sell-depth deterioration threatens ability to liquidate; OFF: healthy sell path and normalized depth return.
- **S226 — Exit route escape.** ON: primary sell execution fails or expires while alternate independently verified route is usable; OFF: no valid path exists, triggering incident management.
- **S227 — Stop re-entry whipsaw.** ON: recently stopped token retakes former level with fresh independent flow and a genuinely new setup; OFF: only price bounce without new evidence.
- **S228 — After-cost profit-lock logic.** ON: current size-adjusted confirmed liquidation proceeds exceed entry basis and available downside exceeds selected risk budget; OFF: reported P&L relies on displayed midpoint rather than executable proceeds.

### T. Price integrity, data completeness and executor controls — S229–S240

- **S229 — Verified program/venue identity.** ON: exact mint, active program, state and pool mapping match authoritative accounts; OFF: suffix, symbol or vendor label is the only evidence.
- **S230 — Reserve-price cross-check.** ON: computed marginal and size-specific AMM/curve prices reconcile with fetched reserve state and fees; OFF: mismatch exceeds model and timestamp tolerance.
- **S231 — Freshness within horizon.** ON: receive-time delay plus processing time fits strategy's time-to-live; OFF: opportunity has expired even if historical setup remains profitable.
- **S232 — Event-order completion.** ON: historical seed, live stream and gap replay reconcile by slot/signature without reordering causal features; OFF: late old events contaminate current decision.
- **S233 — Missing-vs-negative discriminator.** ON: absent candles/wallet history are tagged unknown with completeness score, not zero flow or price collapse; OFF: valid negative observations replace unknown state.
- **S234 — Two independent price measurements.** ON: reliable reserve-derived mark and actual executable sell quote directionally agree at same slot/size; OFF: one source is stale or wrong venue.
- **S235 — Buy quote confirmation.** ON: buy quote reflects actual input size, destination mint, fees and current state within allowed deviation; OFF: transaction would be materially worse than thesis.
- **S236 — Sell-path readiness.** ON: position's exact token quantity can be simulated/quoted for a sell via actual available venue; OFF: simulation or route is absent.
- **S237 — Entry-basis immutability.** ON: position basis equals independently reconciled confirmed fills plus costs and remains immutable; OFF: paper mark or later snapshot overwrites real fill.
- **S238 — Canonical position reconciliation.** ON: wallet balance, sellable quantity, position ledger, signatures and pending transactions converge; OFF: orphan transfer or unknown holding remains unresolved.
- **S239 — Prediction provenance.** ON: a model's feature snapshot, version, timestamp and realized forward label are reproducible without lookahead; OFF: labels use phantom highs or decision-time future events.
- **S240 — Protective-exit independence.** ON: stop/protective sell can execute even when entry model or FDG has a disagreement; OFF: the sell is improperly blocked by an entry-only score/gate.

---

## Part II — 60 complete, hand-designed trade setups

**Reading the recipes:** These are explicit *candidate* entry or exit playbooks, not proof of profitability. `Trigger` is the earliest legal decision time for that recipe; `Fail` invalidates its trade thesis; `Manage` describes how to observe the next state. A candidate requires a current executable quote, real fees, verifiable venue and reconciled open-position state. Signal references in brackets are traceability links, **not mandatory all-of gates**; the setup owner chooses sufficient independent evidence.

### U. Launch ignition and $2k–$30k discovery — T241–T246

- **T241 — Organic first-wave ignition** `[0–45s; launch curve; microcap]` **Trigger:** unrelated first buyers grow across several ten-second slices; genuine net quote inflow rises and reserves validate [S001 S005 S097]. **Fail:** linked wallets create the wave or two subsequent windows turn net-sell. **Manage:** take a size supported by round-trip economics; trail the first confirmed higher low, not an arbitrary early profit percentage.
- **T242 — Initial sell absorbed** `[15–120s; launch curve]` **Trigger:** a meaningful early holder sells, level holds, and at least a second independent buyer cohort takes over [S004 S008 S089]. **Fail:** that sell-defined low breaks on new outflow. **Manage:** watch seller's remaining supply; preserve exposure while new buyers refill the book-equivalent curve demand.
- **T243 — Flat start, genuine acceleration** `[30s–3m; microcap]` **Trigger:** quiet first minute establishes honest support; new wallets start funding buys, volume expands and first micro-range breaks [S007 S079 S081]. **Fail:** breakout re-enters base without independent bids. **Manage:** do not buy a sudden one-wallet candle; allow short retest if the actual price and fresh quote agree.
- **T244 — Launch staircase** `[20s–4m; curve]` **Trigger:** rising 15–30s lows, widening buyer cohort and increasing genuine flow coincide [S009 S085 S098]. **Fail:** latest higher low fails and buy imbalance flips negative. **Manage:** potential runner hold while successive lows stay higher; scale only if executable sell depth supports expansion.
- **T245 — False opening crash** `[30s–5m; curve]` **Trigger:** early dump creates a lower wick, trade price and sell quote recover, and new independent buyers initiate [S062 S099 S170]. **Fail:** second sell attack undercuts flush low. **Manage:** treat as reversal setup with a short time limit; never assume that every dump is a buying opportunity.
- **T246 — Launch second-wave handoff** `[1–8m; microcap]` **Trigger:** first buyers pause or partially exit while new wallet cohort drives a confirmed high and stays net-buying [S003 S088 S161]. **Fail:** only originals trade and later buyers stop entering. **Manage:** evaluate continued trend; do not penalize ordinary first-wave profit-taking if net new money absorbs it.

### V. Curve accumulation and graduation approach — T247–T252

- **T247 — Curve reserve staircase** `[1–15m; verified bonding curve]` **Trigger:** true quote reserves rise in a steady sequence with broad buys; price remains near legitimate trend [S013 S015 S022]. **Fail:** reserve growth reverses and market flow stays negative. **Manage:** size using curve-specific two-sided quotes and update expected graduation timing dynamically.
- **T248 — Curve stall-and-restart** `[1–20m; curve]` **Trigger:** reserve progression stalls in narrow price range, selling contracts, and independent new-money flow restarts [S019 S023 S077]. **Fail:** genuine sell activity pushes below stall low. **Manage:** first objective is previous curve high; continue only if demand actually carries through.
- **T249 — Curve sell-sponge** `[1–15m; curve]` **Trigger:** a sizeable insider/early bag exit is visible, yet price damage diminishes across sells as newcomers buy [S016 S076 S123]. **Fail:** identified supply overhang continues to exceed new-money absorption. **Manage:** monitor insider balances and execute the sell path at planned size before commitment.
- **T250 — Graduating demand broadens** `[progress near verified threshold, not fixed market cap]` **Trigger:** completion probability rises because actual reserve state approaches threshold while later cohorts expand [S020 S024 S085]. **Fail:** flow dies or verified completion state disagrees with prediction. **Manage:** explicitly transition pricing/route state on migration; do not carry curve quotes into the AMM.
- **T251 — Curve pullback-to-progress shelf** `[2–30m; curve]` **Trigger:** a temporary reserve setback creates a support shelf, repeated tests see smaller sell bursts, then quote inflow returns [S023 S080 S149]. **Fail:** shelf fails with negative net flow. **Manage:** target prior progress zone, then reassess: no automatic graduation bet.
- **T252 — Curve trend with efficient size** `[15s–30m; any cap with viable curve]` **Trigger:** early verified trend remains intact, but large proposed entry loses edge; a smaller size retains positive after-cost margin [S021 S135 S141]. **Fail:** *all* useful sizes have nonpositive executable margin. **Manage:** downsize rather than invent a high fixed market-cap floor or add arbitrary admission gates.

### W. Graduation and new-pool opportunities — T253–T258

- **T253 — Verified migration opening drive** `[0–90s after migration]` **Trigger:** new pool is cryptographically linked and live; distinct new-pool buyers move price and executable liquidation value higher [S025 S027 S038]. **Fail:** pool/venue identity unresolved, sell simulation fails or opening low breaks. **Manage:** start separate pool-local chart and watch first-minute opening high.
- **T254 — Migration dip defended** `[1–10m after migration]` **Trigger:** real new-pool sells briefly undercut opening value, then fresh buyers reclaim it with working exit route [S029 S032 S036]. **Fail:** recovery cannot hold pool-open price. **Manage:** first partial objective opening high; runner hold only if new-pool breadth expands.
- **T255 — First opening-range break** `[1–15m after migration]` **Trigger:** a verified new-pool range builds; buy volume and new holders expand through its high [S037 S047 S081]. **Fail:** price returns beneath the range with sell-flow dominance. **Manage:** trail new AMM swing lows; ignore historical curve-only phantom highs.
- **T256 — Pool VWAP hold and second leg** `[2–20m after migration]` **Trigger:** opening impulse cools above pool-local VWAP and a fresh independently funded buyer wave breaks out [S039 S048 S033]. **Fail:** VWAP breaks and cohorts reverse. **Manage:** hold continuation, not a perpetual graduation thesis.
- **T257 — Liquidity-upgrade entry** `[after confirmed venue switch]` **Trigger:** fresh AMM makes real sell depth materially better, healthy buy activity continues and round-trip economics improve [S030 S041 S140]. **Fail:** purported depth is stale or pool quote collapses. **Manage:** recalculate trade size from post-migration impact, not pre-migration market cap.
- **T258 — Graduation trap avoidance / later reclaim** `[opening 1–30m]` **Trigger:** initial markup was sold by holders, but a *later* new base forms and independent buys recover opening resistance [S034 S042 S048]. **Fail:** distribution resumes before confirmed reclaim. **Manage:** no trade on the initial spike; only open once the second structure exists.

### X. Momentum continuation and clean breakouts — T259–T264

- **T259 — One-minute range release** `[2–60m; curve or AMM]` **Trigger:** compressed real range breaks above resistance with volume leading and broad buyers [S162 S160 S161]. **Fail:** next window trades back inside range on net sells. **Manage:** first risk anchor is inside-range invalidation; continue only if expansion has actual sell-value gains.
- **T260 — 1m/5m trend alignment** `[15m–4h]` **Trigger:** 1m new high occurs inside rising 5m swing pattern and positive genuine 15m net flow [S049 S051 S159]. **Fail:** 5m structural low breaks. **Manage:** trail on 5m structure, not a tight 15s stop if the thesis is multi-minute.
- **T261 — High-tight flag release** `[3m–2h; trending token]` **Trigger:** prior true impulse, shallow narrowing pullback, sell contraction and fresh upper-bound break [S071 S147 S153]. **Fail:** closes below flag body and sellers expand. **Manage:** permit running if new highs attract new holders; scale out on distribution, not nominal +5% alone.
- **T262 — Three-attempt resistance break** `[5m–3h]` **Trigger:** repeated attempts approach old high while sellers' absorption weakens; third effort clears level on real turnover [S103 S164 S166]. **Fail:** re-entered range and lower high. **Manage:** if breakout accepts and quote value rises, adjust structural low upward.
- **T263 — Momentum restart after cooling** `[2m–1h]` **Trigger:** positive trend pauses, velocity cools, then new broad buy impulse clears latest swing high [S052 S060 S107]. **Fail:** the new impulse produces upper wick and turns negative immediately. **Manage:** treat as a new decision with updated basis and costs, not a justification to average losers.
- **T264 — Runaway continuation with authentic highs** `[5m–24h; rare high-velocity trend]` **Trigger:** repeated higher lows, fresh holders and positive sellable value survive each acceleration [S167 S224 S165]. **Fail:** higher-low sequence breaks alongside negative flow. **Manage:** avoid a fixed small take-profit ceiling; use partials only where after-cost replay supports their benefit.

### Y. Pullbacks, reloads, retests — T265–T270

- **T265 — First pullback to VWAP** `[2–30m; established early uptrend]` **Trigger:** pullback tests valid session/launch VWAP, sellers dry up and price reclaims on broad buys [S145 S146 S148]. **Fail:** VWAP rejection plus lower low. **Manage:** prior high is first test; consider holding further if second push has fresh buyers.
- **T266 — Breakout resistance turns support** `[2m–4h]` **Trigger:** token breaks real consolidation high then retests it without destroying sell depth; new-money buys appear [S152 S158 S136]. **Fail:** old ceiling becomes ceiling again. **Manage:** entry after reclaim, not while price falls through support.
- **T267 — Two-tap seller exhaustion** `[3m–2h]` **Trigger:** two comparable dips into support show contracting sell imbalance and increasing absorption [S149 S154 S104]. **Fail:** subsequent test produces stronger selling and lower low. **Manage:** use low of defended region as structural reference, sized against executable slippage.
- **T268 — Sideways high-base reload** `[5m–4h]` **Trigger:** initial climb is followed by tight sideways trade, net buying survives and volume expands on upside release [S151 S077 S081]. **Fail:** high base breaks on real outflow. **Manage:** do not buy every candle in base; wait for genuine renewed initiative.
- **T269 — Smart but not solitary reload** `[3m–3h]` **Trigger:** historically validated profitable wallet adds near higher low, plus independently funded dip buyers participate [S111 S091 S155]. **Fail:** only copy wallets trade and price cannot reclaim range. **Manage:** smart-wallet observation is confirmation, never sole buy authority.
- **T270 — Controlled deep retrace** `[5m–6h; volatile token]` **Trigger:** deeper-than-normal pullback nevertheless keeps key impulse origin, shows diminishing sells and verified reversal close [S150 S070 S066]. **Fail:** impulse origin breaks or liquidity vanishes. **Manage:** smaller size than shallow pullback; first objective is middle of original impulse.

### Z. Flush, undercut and recovery setups — T271–T276

- **T271 — Support undercut reclaim** `[3m–4h]` **Trigger:** genuine low is broken briefly, then regained with positive independent quote flow and new buyers [S170 S176 S095]. **Fail:** price retests low and cannot reclaim. **Manage:** short time-stop; do not call a continuing collapse 'oversold' to force entry.
- **T272 — Double-bottom with dwindling supply** `[5m–6h]` **Trigger:** second bottom has fewer aggressive sells, credible buyer absorption and no fresh dangerous insider sell [S171 S101 S173]. **Fail:** third break occurs with increased outflow. **Manage:** initial objective neckline; hold only if it clears.
- **T273 — High-volume flush then reclaim VWAP** `[2m–4h]` **Trigger:** real capitulation decays, verified sell route survives, price reclaims breakdown-window VWAP [S169 S174 S177]. **Fail:** VWAP acts as resistance or liquidity leaves. **Manage:** risk is elevated; only authorize after coherent pricing and small-size executability.
- **T274 — First higher-low trend change** `[10m–12h]` **Trigger:** downtrend forms higher swing low, breaks lower high and buyer breadth improves [S175 S131 S148]. **Fail:** higher low breaks and new holders exit. **Manage:** first target prior range midpoint; do not assume former all-time high is reachable.
- **T275 — Panic survivor reactivation** `[15m–1d]` **Trigger:** token was sold hard, but independently funded new buyers and real exit depth return, then a new consolidation breaks [S174 S131 S157]. **Fail:** sell path deteriorates or bounce is a thin order-flow illusion. **Manage:** this is a new setup; quarantined security facts must be reverified, never overridden by enthusiasm.
- **T276 — Bounce-trap rejection** `[after >1 structural breakdown]` **Trigger:** alleged rebound lacks new buyers, true depth stays poor and trapped holders sell into every uptick [S178 S179 S144]. **Fail for short thesis:** genuinely new buying and resilient depth appear. **Manage:** **NO LONG ENTRY**; this is a negative setup used to prevent repeated bad dip purchases.

### AA. Relative strength, cohort leaders and capital rotation — T277–T282

- **T277 — Microcap cohort leader** `[30s–30m; ~$2k–$30k research cohort]` **Trigger:** token leads matched fresh-launch peers on authentic buyer and quote-flow growth, with viable exit economics [S185 S182 S139]. **Fail:** apparent lead is caused by missing peers or one sponsored wallet. **Manage:** trade setup rather than arbitrary minimum cap; adapt sizing to actual depth.
- **T278 — Strong token in weak meme tape** `[5m–4h]` **Trigger:** market declines but token holds verified structure with new inflow and usable depth [S184 S189 S133]. **Fail:** it joins widespread selloff with no new buyers. **Manage:** preserve smaller defensive size; never equate relative strength with invulnerability.
- **T279 — Sector rotation first receiver** `[1m–2h]` **Trigger:** theme cohort expands, candidate gets earlier independent flow than laggards and starts first range breakout [S187 S186 S086]. **Fail:** leader status reverses as attention chases newer tokens. **Manage:** re-evaluate relative rank every decision window.
- **T280 — Market risk-on transition** `[5m–6h]` **Trigger:** broader launch survival and market-wide net buying improve, while candidate forms new high base [S188 S192 S151]. **Fail:** market turns risk-off before release. **Manage:** allow larger eligible opportunity set but never relax contract safety or after-cost thresholds.
- **T281 — SOL-denominated true breakout** `[2m–1d]` **Trigger:** token's sellable SOL value and reliable USD mark both rise, with genuine share-of-volume improvement [S183 S181 S182]. **Fail:** USD-only lift collapses when denominated in SOL. **Manage:** performance reports distinguish SOL P&L from mark-to-USD effects.
- **T282 — Better-opportunity substitution** `[all horizons; competing positions]` **Trigger:** a new setup has substantially stronger independently validated after-cost edge and executability than a stale candidate [S190 S222 S135]. **Fail:** new edge depends on stale quote or missing history. **Manage:** optional exit/reallocation only if actual expected switching costs are justified; never churn for small score differences.

### AB. Wallet and narrative backed entries — T283–T288

- **T283 — Two independent skilled entrants** `[15s–15m]` **Trigger:** separately funded historically positive after-cost wallets enter before price impulse, and organic buyers follow [S109 S110 S086]. **Fail:** copied funding or later performance correction removes skill. **Manage:** wallet intelligence is additive evidence, not a veto over chart and flow.
- **T284 — Validated whale and crowd** `[30s–2h]` **Trigger:** sizeable new buyer arrives, but growing independent crowd takes over buying and liquidity is sufficient for exit [S115 S087 S136]. **Fail:** whale still dominates and starts unloading. **Manage:** monitor whale concentration and reduce exposure before catastrophic single-holder dependence.
- **T285 — Organic meme spread** `[1m–6h]` **Trigger:** unique social authors discuss exact mint before broad on-chain entry, followed by new-money growth and price acceptance [S193 S194 S195]. **Fail:** social signal is spam or buyers do not appear. **Manage:** classify social as optional early discovery; chain and real quotes control timing.
- **T286 — Attention breakout after real consolidation** `[5m–12h]` **Trigger:** community remains active after launch hype, token has held a base, and second independent attention wave converts to new buys [S197 S200 S151]. **Fail:** repost campaign has no buyer conversion. **Manage:** trailing low based on consolidation structure; do not chase engagement counts.
- **T287 — Creator-sale absorbed by newcomers** `[1m–12h; higher-risk]` **Trigger:** creator-associated disposal is verified and finite, new broad buyers refill it, concentration falls and support holds [S117 S123 S122]. **Fail:** disposal continues or holder concentration remains deceptive. **Manage:** separate warning from disqualification; never waive a genuine contract/sellability hazard.
- **T288 — Influencer after-the-top counter-signal** `[all horizons]` **Trigger:** high-profile post arrives after parabolic rise while old holders distribute and new buyers decline [S198 S130 S084]. **Fail for warning:** fresh independent demand forms a stable new base. **Manage:** **NO LATE CHASE**; an earlier position can protect profit if structural exit evidence appears.

### AC. Developing and established runner structures — T289–T294

- **T289 — One-hour trend continuation** `[1h–12h; AMM]` **Trigger:** several 5m higher lows, an actual 1h range break and rising sustained buyer flow [S051 S159 S083]. **Fail:** 5m structural sequence breaks with outflow. **Manage:** use multi-minute trailing invalidation and longer thesis lifetime than launch scalps.
- **T290 — Four-hour consolidation expansion** `[4h–3d; liquid enough AMM]` **Trigger:** multiple hours of real base, declining sell turnover and high-volume upward release [S151 S077 S157]. **Fail:** returns inside base and makes lower low. **Manage:** runner management keyed to 1h/4h structure with executable quotes refreshed each decision.
- **T291 — Late second-life trend** `[1h–3d]` **Trigger:** previously faded token develops genuinely new holders and a higher-low trend after long base [S216 S131 S175]. **Fail:** activity returns to stagnation or bagholders distribute under old resistance. **Manage:** treat as new discovery; avoid reusing old launch labels.
- **T292 — Liquid leader stair-step** `[15m–2d]` **Trigger:** established token gains through a series of verified highs and higher lows, sell depth strengthens and new buyers stay engaged [S167 S041 S096]. **Fail:** broad buyer participation rolls over and first major swing low fails. **Manage:** support infrequent larger moves; do not scalp away the runner by inherited meme micro-scalp rules.
- **T293 — Major resistance acceptance** `[30m–2d]` **Trigger:** established price ceiling is broken, held across multiple real trading windows and new independent buyers buy retest [S157 S166 S158]. **Fail:** returns beneath resistance on institutional-sized/holder selling. **Manage:** range width is initial context, never an automatic price target guarantee.
- **T294 — Failed old-coin breakout exclusion** `[>1h after first trade]` **Trigger:** repeated break attempts fail, buyer breadth weakens, price remains under anchored VWAP and no new cohort enters [S216 S084 S164]. **Fail for exclusion:** credible new base and broad flow emerge. **Manage:** **NO LONG ENTRY**; prevent waste on day-old coins without their own fresh setup.

### AD. Position management / exit decisions — T295–T300

- **T295 — Let the genuine 10× runner breathe** `[open winning position]` **Trigger to hold:** real higher lows, widening new-money participation and positive executable liquidation value persist [S224 S167 S165]. **Fail:** structure breaks *and* buyers turn sellers; high-risk liquidity shock overrides both. **Manage:** use tested trailing structure and conditional partials; no forced 5% profit ceiling.
- **T296 — Sell into distribution climax** `[winning position; any age]` **Trigger:** wide authentic upper wick plus heavy sell turnover and older holders distributing [S219 S220 S130]. **Fail:** after-cost sell thesis weakens when new buyers absorb sales and re-accept high. **Manage:** compare partial vs full liquidations by actual net proceeds and downside risk.
- **T297 — Time-decay exit from dead setup** `[stagnant open position]` **Trigger:** setup expiry passes without anticipated momentum and quote-flow/relative strength deteriorate [S222 S180 S190]. **Fail:** wholly new setup emerges and earns independent authority. **Manage:** stop confusing hope or old model predictions with a valid ongoing thesis.
- **T298 — Executable profit protection** `[profitable position]` **Trigger:** trusted sell-proceeds peak suffers a structural retrace while seller pressure rises [S221 S228 S217]. **Fail:** reported drawdown is only phantom chart highs. **Manage:** protect actual after-cost SOL proceeds with pretested trailing policy and route-aware size.
- **T299 — Safety-first protective sell** `[any position, even losing]` **Trigger:** valid on-chain hazard, sell-depth drain or clearly broken structure threatens recoverability [S209 S211 S225]. **Fail for hazard:** trusted state and quotes prove false alarm. **Manage:** exits bypass entry gates and model disputes; log each attempt, signature, fill and remaining balance.
- **T300 — Route failure / orphan position recovery** `[any position; operational incident]` **Trigger:** a sell fails, route expires or wallet and canonical ledger disagree [S226 S238 S240]. **Fail for resolved state:** signatures, balances and actual proceeds reconcile. **Manage:** retry only through fresh verified route with duplicate-order prevention, then mark final realized/unrealized exposure honestly.

---

## Part III — The important part: turning the library into better decisions

### 1. Setup ownership and scoring

Each T-setup gets its own `setup_id`, `owner_lane`, `time_horizon`, `venue_state`, `cap_band`, `phase`, `entry_trigger`, `expiry`, `structural_invalidation`, `exit_policy`, and explicit `source_quality`. A token can have several **competing legitimate setups** but only one transaction proposal wins an auction for a given capital allocation. Scoring should use a small number of independent evidence families, for example:

| Family | Positive examples | Counter-evidence | Typical data |
|---|---|---|---|
| Real price structure | higher lows, accepted break, VWAP reclaim | failed break, lower lows | CHAIN, DERIVED |
| Economic participation | independent new buyers, positive net quote inflow | linked-wallet flow, buyer attrition | CHAIN, DERIVED |
| Volume and liquidity | real volume expansion, improving sell quote | wash churn, shallow exit | CHAIN, EXEC |
| Lifecycle alignment | current curve, verified migration or AMM stage | wrong pool/price model | CHAIN, AUDIT |
| Holder behaviour | broad retention, absorbed supply | concentrated insider sell | CHAIN, DERIVED |
| Regime / attention | true cohort leadership, early organic interest | late paid hype, broad market decay | DERIVED, EXT |

Suggested conceptual scoring model: `strategy_score = base_set_up_quality + capped_independent_evidence - proven_counter_evidence - execution_cost_penalty - uncertainty_penalty`. **Unknown observations carry uncertainty, not a false bearish reading.** Negative evidence must be assigned to the question it actually answers: a risk-of-dump warning should not automatically erase positive price momentum, nor does good momentum make an unsafe contract safe.

For conflict resolution: (a) safety/position reconciliation owns sellability; (b) data-quality layer owns facts and provenance; (c) strategy owner owns entry thesis; (d) portfolio allocator owns cash contention and sizing; (e) position manager owns protective exit. The FDG can validate safety and costs; it must not silently rescore and overrule a qualified strategy on a duplicate philosophy gate.

### 2. Example decision: runner vs trap

**Candidate A:** $8k launch-age token, real 15s/30s quote inflow, 21 distinct buyers after linked-wallet consolidation, rising 10-second lows, verified working curve buy/sell quotes, adequate round-trip expected edge. It may qualify for T241/T244 despite small market cap. *Those numbers are an illustrative scenario, not a real trade or rule.*

**Candidate B:** $80k token with one dramatic +70% last-print candle, recycled-wallet volume, negative net quote flow and a sell quote 25% worse than displayed mark. It should not qualify for a momentum entry despite nominally larger market cap or a higher technical candle score. The chart must not be taught as a successful breakout.

### 3. Replay and training protocol (mandatory before live authority)

1. **Rebuild point-in-time event traces.** Reconcile historical seed with live stream from verified signatures; construct *event-time* and *decision-time* candles; deduplicate and flag missing windows. Do not let a 19-minute-late print rewrite a 2-minute decision.
2. **Run raw signals in observation mode.** Produce an ON/OFF/UNKNOWN stream for S001–S240 with exact supporting events and confidence; no entry authority until a setup earns it.
3. **Replay each T-setup as counterfactual.** Record all instances, including ignored and rejected candidates, with entry time, hypothetical route at that time, fill/fee model, first adverse excursion, maximum favorable excursion only from trusted prices, and realizable exit path.
4. **Measure setups independently.** Evaluate early-launch, late curve, migration, post-launch, 1m/5m, 1h/4h, and rug-look reversals in matched cohorts. Key metrics: after-cost expectancy, net SOL P&L, profit factor, win rate, maximum drawdown, 2×/5×/10× hit/hold/capture rate, missed qualified runners, time-to-decision, entry slippage, exit latency, sample count and uncertainty.
5. **Compare historic snapshots without blind reversion.** For AATE, compare known better historical build periods (including 5.0.1983, 6482 and 5.0.8096) with recent code and logs using **the same replay inputs**. Identify which price facts, discovery events, setup triggers, exit holds and safety rules changed. Historical results provided by the owner are hypotheses until independently reconciled to real fills.
6. **Test family ablation.** Remove one evidence family at a time to discover which actually predicts forward *executable* profit. Detect inverted scoring and accidental double penalties. A high raw count of ON signals is not inherently an edge.
7. **Use walk-forward holdouts.** Tune on earlier data, test later, segment by cohort and volatility, then run shadow/paper with real quotes. Require sufficient samples and uncertainty bounds; avoid building thresholds directly from one spectacular coin.
8. **Require live parity on identical signals.** At the same decision time, compare shadow-vs-live token, venue, quote, predicted edge, chosen lane, FDG action, actual transaction submission, landing, fill, basis and exit. Every missed qualified runner needs one precise cause code: `NOT_DISCOVERED`, `DATA_LATE`, `FEATURE_UNKNOWN`, `NO_OWNER`, `SCORE_INVERSION`, `ARBITRATION_LOST`, `DUPLICATE_GATE`, `CAPITAL`, `QUOTE_UNECONOMIC`, `TX_FAILED`, or `OTHER_PROVEN`.
9. **Keep losing traders from poisoning learners.** Paper, simulated, wallet-confirmed live and invented peak values must have separate immutable lineage. A subtrader with poor actual execution cannot teach false negative/positive forward labels into every other lane. Offline validation precedes authority increases.
10. **Ship only after operational acceptance.** Confirm the verified commit, replay parity, genuine current buy/sell marks, reconciliation of all positions, protective exits, nonregression tests and one clean installable APK from main. None of these tests are claimed to have run in this specification.

### 4. Minimum test cases grounded in AATE's known failure modes

- **Phantom peak contamination:** inject an isolated 500× tick while real pool price steadily rises; genuine marks, drawdown, entries, exits and training labels must remain unaffected.
- **Wrong venue/source:** price a completed Pump curve or non-Pump AMM as if it were an active Pump bonding curve; correct behavior is venue mismatch and fresh quote reconstruction, not a -96% drawdown.
- **Market-cap mismatch:** legitimate low-cap and higher-cap candidates with otherwise identical after-cost evidence must be evaluated by their respective cohort setups rather than blocked by one global floor.
- **Slow decisions:** launch token passes T241 at 15s but pipeline processes at 60s; record missed entry caused by `DATA_LATE`/`DECISION_EXPIRED` rather than blaming token quality.
- **Paper-wins/live-misses:** identical observed features and setup triggers must produce identical pre-execution decisions; differences must be explained by real capital, quote, safety or transaction facts.
- **Wrong-wave buys:** token tops on dramatic single candle but buyer breadth falls and early-holder selling rises; no entry on a false S054 breakout.
- **Protective exit choke:** force FDG entry disagreement while a live held position hits valid structural/route exit; sell authority must remain independent.
- **Price display profitable, manual sell losing:** verify confirmed fill basis, token quantity, fee schedule, SOL/USD treatment and executable entire-position sell proceeds; UI P&L must not come from untradeable midpoint/phantom print.
- **Missing pre-stream history:** inject late history after streams begin; overlap reconciliation must not double count or fabricate price movement.
- **Orphan trade:** create confirmed wallet token balance with missing canonical lineage; detect, reconcile, and recover exit ability without falsely calling it paper.

### 5. Required log schema for every observed setup

`token_mint, chain, pool_id, venue_program, lifecycle_state, token_decimals, quote_mint, first_trade_time, event_time, receive_time, evaluated_time, source_slot, completeness_by_field, true_price_source, executable_buy_quote, executable_sell_quote, quote_timestamp, live_fee_model, bid_side_value, ask_side_cost, market_cap_method, circulating_supply_basis, distinct_buyer_count, linked_wallet_count, net_quote_flow, quote_volume, exit_depth, signal_ids_on, signal_ids_off, signal_ids_unknown, setup_id, owner_lane, setup_quality, alternative_setup_ids, predicted_after_cost_edge, expected_uncertainty, risk_state, hard_safety_cause, arbitration_result, decision, decision_expiry, wallet_availability, tx_signature, tx_status, confirmed_fill_qty, fill_cost_sol, position_id, exit_thesis, realized_sell_proceeds_sol, realized_net_pnl_sol, forward_label_time, forward_label_price_proof, data_version, model_version, build_sha`.

All tradeable price and P&L values should be reproducible from *specific valid signatures, reserve snapshots or real-time executable quotes* with timestamps and pool identifiers. Retrospective candle extremes never count as realizable exits without an actual matched route and opportunity at that time.

---

## Sources used to ground mechanics (not the profitability of any strategy)

These are protocol/developer sources for how venues, tokens, transaction streams and swaps work. The 300 signals and setups are original research hypotheses created for this brief, **not claims that the sources endorse them**.

1. [Pump.fun — bonding-curve pricing, graduation and fees](https://solana.pump.fun/docs/bonding-curve).
2. [Pump.fun public program documentation — curve state, completion and migration](https://github.com/pump-fun/pump-public-docs/blob/main/docs/PUMP_PROGRAM_README.md).
3. [Raydium — LaunchLab pre-graduation lifecycle and CPMM migration](https://github.com/raydium-io/raydium-docs-v1/blob/main/products/launchlab/overview.mdx).
4. [Solana — token mint accounts, decimals and authorities](https://solana.com/docs/tokens).
5. [Helius — Parsed Streams, Parsed Events, CPI decoding and historical/live parity](https://www.helius.dev/blog/parsed-events-and-streams).
6. [Jupiter — current Swap API order and execute lifecycle](https://github.com/jup-ag/docs/blob/main/swap/order-and-execute.mdx).
7. [Solana — transaction confirmation, blockhashes and expiration](https://solana.com/developers/cookbook/transactions/confirmation).

**Status:** Research specification only. No AATE source code has been altered; no historical logs or main-branch build have been tested by producing this document. No strategy has been licensed for live trading.

