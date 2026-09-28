# Specialist Directionality Audit — 2026-09-28

Build target: 5.0.7402

## Purpose

Audit the live entry stack for "thinking backwards": rewarding evidence that appears after a pump/fade when the specialist's job is to identify the move before or during ignition.

## Wired live entry authorities corrected

| Surface | Audit result | 7402 action |
|---|---|---|
| TokenMetricStageRouter | Was using discovery-relative freshness before 7401 | True launch lifecycle retained; post-pump fade cannot be FRESH_LAUNCH |
| ModeRouter | Fresh launch was first-candle age <=15m | True launch lifecycle <=3m; fade explicitly rejects FRESH |
| AgenticStyleRouter | MICRO_SNIPE/QUICK_FLIP used watchlist age | True launch age; fade cannot express as sniper style |
| ToolkitSignalSheet | Launch, volume and social hypotheses could fire after impulse | Degen/volume/social ignition made lifecycle-directional; young fade routes to exhaustion |
| UnifiedScorer | Old fresh bonus rewarded visible recency | 7401 causal ignition/pre-ignition/expansion/fade scoring retained |
| LayerVoteSampler | Token-level fear, dip, moonshot and sniper votes lacked lifecycle context | Launch fear is not bullish; dip requires reclaim; sniper/moonshot follow ignition not source labels |
| MovementPatternSignal | VOLUME_IGNITION was candle-confirmation only | Young post-pump fade becomes exhaustion; no new ignition |
| SecondScorer | Called watchlist age pool age | Uses true market age |
| AutoModeEngine | Could elect SNIPE from discovery recency | SNIPE only PRE_IGNITION/IGNITION/EXPANDING <=3m |
| SniperLowScoreShaper7054 | Freshness evidence used watchlist age | Uses true lifecycle |
| CyclicTradeEngine | Fallback age used watchlist time | Uses true market age |
| MomentumPredictorAI | Many readers, zero live writers | Real normalized 8s trade candles now feed recordPricePoint |
| PredictiveEntryOracle6915 | Larger pooled paper+live journal could overwrite collapsing LIVE lane/book truth | LIVE uses live-only journal lane/book; pooled score prior skipped after live evidence; paper WR bootstrap-only |

## Specialists intentionally reactive — not converted into launch predictors

- **EXPRESS / ShitCoinExpress:** explicitly a chase/scalp engine ("must already be pumping"). Keep it reactive, but do not let it define launch ignition or Project Sniper ownership.
- **DIP_HUNTER:** correctly requires bounce/reclaim confirmation and rejects falling knives.
- **QUALITY:** deliberately established/quality-biased; age, liquidity and holder confirmation are appropriate for its mandate.
- **BLUECHIP:** deliberately large-cap/liquidity/structure-biased; confirmation is appropriate.
- **CYCLIC:** not a first-minute sniper; fresh/unknown sellability caution is appropriate.
- **TREASURY / CASHGEN:** cashflow/reinvestment lane; not the launch-prediction authority.
- **CORE:** ensemble/coordinator role; should not independently create a late launch thesis.
- **LaneExitTuner:** already contains prior anti-inversion logic for fat-tail runners; low WR plus large MFE does not automatically tighten winner capture.
- **TacticSwitcher:** rotates on settled outcomes and does not disable lanes.
- **LaneToxicityGuard:** treatment/priority authority, not silent cross-lane remapping.

## Shared / legacy specialist classes requiring context

- **MoonshotTraderAI:** rewards volume/buy pressure/pump-phase confirmation. Direct source caller is heavily CryptoAlt-related. The live meme path is corrected at Toolkit/ModeRouter/AgenticStyleRouter rather than globally changing this shared class.
- **ManipulatedTraderAI:** rewards high momentum and manipulation evidence; direct usage is also CryptoAlt-heavy. Do not reinterpret this shared engine as pre-pump prediction without an asset-class-specific contract.
- **ShitCoinExpress:** intentionally momentum-following; kept isolated from ignition semantics.

## Predictive machinery found disconnected or partially wired

Repository search found these surfaces are not presently end-to-end production authorities:

- `EarlyEntryScout6390.evaluate()` — test-only caller found.
- `EarlyLaunchBypass6394/6396.evaluate()` — test-only/no live caller found.
- `SmartMoneyFeed6394.onWhaleBuy()` — test-only writer found.
- `SmartMoneyDiscovery7277.start()` — no source caller found.
- `InsiderCopyEngine.copyBuyFromSmartMoney7277()` — no source caller found.
- `ModeSpecificScanners.scanFreshLaunch()` — repository unwired ledger marks it dead/unwired.
- Copy-trade documentation describes an end-to-end smart-money path, but current source/call census does not match that documentation.

These should not be counted as live predictive edge until runtime wiring is proven. They should be connected through the canonical candidate -> safety -> V3 -> FDG path, not through an execution bypass.

## Remaining evidence-domain caution

`ScoreExpectancyTracker` persists paper+live score buckets. 7402 prevents that pooled cell from overriding LIVE inside PredictiveEntryOracle once the lane has live terminal evidence. Other legacy consumers still use pooled score-band history for shaping/proof. Do not convert those all at once into harder live gates; previous 7399 testing showed that broad proof tightening creates throughput chokes. Migrate those consumers individually with mode-aware telemetry first.

## Directional doctrine after 7402

- Launch specialists buy causal **ignition**, not visible historical pump.
- A newly discovered token is not automatically a newly launched token.
- Volume/social/trending confirmation after rollover is **post-event attention**, not ignition.
- Dip/reclaim systems may be reactive by design, but require a reclaim.
- Slower quality/bluechip lanes may require confirmation by design.
- Paper history may seed a cold start but cannot overwrite established negative live reality.
- Predictive signals feed the normal canonical entry spine; no separate safety/FDG bypass is introduced.
