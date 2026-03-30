# AATE V4.1 Release Notes

## Version 4.1.0 - December 2025

### Overview

AATE V4.1 is a major stability and performance release that fixes critical compiler issues and introduces smarter trading logic. This version successfully builds on GitHub Actions CI after resolving a persistent Kotlin compiler StackOverflow issue.

---

## What's New

### Build Stability (Critical Fix)

**Problem:** The Kotlin compiler was crashing with StackOverflowError during coroutine transformation because `botLoop()` was 2600+ lines with a massive inline lambda.

**Solution:**
- Extracted `processTokenCycle()` as a separate non-suspend function
- `botLoop()` reduced from 2600 to 825 lines
- `processTokenCycle()` is 1960 lines but doesn't go through coroutine transformation
- Added `initTradingModes()` and `tryFallbackPriceData()` helpers

**Result:** CI builds successfully and reliably.

---

### Trading Layer Adjustments

**ShitCoin Layer Range Fix:**
- OLD: $0 - $500K (way too wide!)
- NEW: $0 - $30K (true micro-caps only)

**Layer Boundaries:**
| Layer | Market Cap Range |
|-------|------------------|
| ShitCoin | $0 - $30K |
| ShitCoin Express | $0 - $30K |
| V3 Quality | $30K - $1M |
| Blue Chip | $1M+ |

---

### Treasury Mode Optimization

**Profit Targets Lowered:**
| Setting | Old | New |
|---------|-----|-----|
| Take Profit | 7% | 4% |
| Min Profit | 5% | 3.5% |
| Max Profit | 10% | 8% |

**Why:** Catching more quick trades instead of waiting for bigger moves that might reverse.

---

### Loss Prevention (New)

**Velocity Detection:**
- Exit if price dropping >10% in 3 candles
- Exit if at -10% loss AND still accelerating
- Block entry during rapid dumps (>5% drop in 3 candles)

**Trailing Stops Improved:**
| Peak Profit | Minimum Locked |
|-------------|----------------|
| 8%+ | 2% guaranteed |
| 15%+ | 5% guaranteed |
| 25%+ | 10% guaranteed |

---

### Re-entry System (Balanced)

Previous version was too strict (10min cooldown). Now balanced:

| Setting | Value |
|---------|-------|
| Cooldown | 2 minutes |
| Score threshold | 65% |
| Max attempts | 2 |
| Penalty per attempt | -5 score |
| 1st attempt size | 50% |
| 2nd attempt size | 30% |
| Block if collapsed | >25% since failure |

---

### DipHunter Safety

**New Limits Added:**
| Setting | Value |
|---------|-------|
| Daily Max Loss | 0.2 SOL |
| Daily Max Hunts | 15 |
| Base Position | 0.05 SOL |
| Max Position | 0.15 SOL |
| Max Concurrent | 3 dips |

---

## Technical Changes

### Files Modified
- `BotService.kt` - Major refactor (extracted functions)
- `ReentryRecoveryMode.kt` - Balanced re-entry logic
- `FluidLearningAI.kt` - Improved trailing stops
- `Executor.kt` - Velocity detection
- `CashGenerationAI.kt` - Lower profit targets
- `ShitCoinTraderAI.kt` - $30K max mcap
- `ShitCoinExpress.kt` - $30K max mcap
- `DipHunterAI.kt` - Safety limits
- `LayerTransitionManager.kt` - Updated layer boundaries

### New Functions
- `processTokenCycle(mint, cfg, wallet, lastSuccessfulPollMs)` - Main token processing
- `initTradingModes(cfg)` - Layer initialization
- `tryFallbackPriceData(mint, ts)` - Birdeye/pump.fun fallback

---

## Upgrade Notes

1. **Clean Install Recommended** - Due to major structural changes
2. **Paper Mode First** - Test the new logic before going live
3. **Watch Bootstrap** - New instances start with tight limits

---

## Known Limitations

- No local compiler - must use GitHub Actions CI
- Collective Learning requires Turso database setup
- Telegram notifications require bot token

---

## What's Next (Roadmap)

- [ ] LayerTransitionManager live testing
- [ ] BehaviorUI visualization screen
- [ ] Web-based monitoring portal
- [ ] Play Store beta release

---

*AATE V4.1.0 - Stable Build Release*
