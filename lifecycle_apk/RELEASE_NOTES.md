# AATE V4.1 Release Notes

## Version 4.1.2 - December 2025

### Overview

V4.1.2 fixes critical state management bugs and adds coordination between multiple scanners and AI layers.

---

## What's New (V4.1.2)

### GlobalTradeRegistry - Thread-Safe Watchlist (Critical Fix)

**Problem:** Watchlist randomly resetting from 31 tokens to 1 due to:
- Multiple threads reading/writing cfg.watchlist
- ConfigStore.save() called with stale data
- No synchronization between scanners and AI layers

**Solution:** New `GlobalTradeRegistry.kt` singleton:
- ConcurrentHashMap for thread-safe watchlist storage
- Single source of truth for all token tracking
- Duplicate suppression with 5-minute cooldown
- Position tracking across ALL layers (V3, Treasury, BlueChip, ShitCoin)
- Automatic pruning when full (100 token limit)
- Periodic sync to ConfigStore every 50 seconds

---

### Module Initialization Timing (P1 Fix)

**Problem:** Trading started BEFORE all AI layers were initialized, causing undefined behavior.

**Solution:**
- Added `allTradingLayersReady` flag
- Trading blocked until ALL layers successfully init
- Error logging for failed layer initializations
- Clear status messages during startup

---

### TokenMergeQueue - Scanner Coordination (P2 Fix)

**Problem:** Multiple scanners (DEX_BOOSTED, PUMP_FUN, V3_SCANNER) finding same token simultaneously caused duplicates.

**Solution:** New `TokenMergeQueue.kt`:
- Batches discoveries within 5-second window
- Merges same token found by multiple scanners
- Multi-scanner detection = confidence boost
- Scanner rankings: DEX_BOOSTED (90), V3_PREMIUM (85), PUMP_FUN (80), etc.
- Emits single merged token per batch

---

## Version 4.1.1 - December 2025

### Overview

V4.1.1 fixes critical orchestration bugs where multiple AI subsystems (Treasury, V3, BlueChip, ShitCoin) were executing trades independently without coordination.

---

## What's New (V4.1.1)

### FinalExecutionPermit - Unified Execution Authority (Critical Fix)

**Problem:** Multiple AI "brains" were trading the same token simultaneously:
- Treasury buying while V3 rejects
- BlueChip and ShitCoin layers executing duplicates
- No coordination between subsystems

**Solution:** New `FinalExecutionPermit.kt` singleton acts as a gate:
1. V3 decisions register as APPROVAL or REJECTION
2. Treasury/BlueChip/ShitCoin must check permit before executing
3. First-come-first-served prevents duplicate executions
4. 60-second cooldown on V3 rejections

**Result:** Treasury cannot buy tokens that V3 rejected. All layers coordinate.

---

### Treasury Take Profit Adjusted (User Request)

| Setting | Old | New |
|---------|-----|-----|
| Take Profit Target | 4% | 3.5% |
| Min Profit | 3.5% | 3% |
| Max Profit | 8% | 7% |

**Why:** User requested tighter scalps at 3.5%, max 7%.

---

### Stop Bot Button - Full Position Closing (Bug Fix)

**Problem:** Stop button only closed `status.tokens` positions. Treasury, BlueChip, and ShitCoin positions remained open.

**Solution:** `stopBot()` now closes ALL position types:
- Main positions (status.tokens)
- Treasury positions (CashGenerationAI.activePositions)
- BlueChip positions (BlueChipTraderAI)
- ShitCoin positions (ShitCoinTraderAI)

---

## Technical Changes (V4.1.1)

### Files Modified
- `BotService.kt` - FinalExecutionPermit integration, stopBot fix
- `CashGenerationAI.kt` - Take profit 3.5-7%

### New Files
- `FinalExecutionPermit.kt` - Unified execution authority singleton

### Functions Added
- `FinalExecutionPermit.registerRejection()` - V3 blocks a token
- `FinalExecutionPermit.registerApproval()` - V3 approves a token
- `FinalExecutionPermit.canExecute()` - Check if layer can trade
- `FinalExecutionPermit.tryAcquireExecution()` - Reserve execution slot
- `FinalExecutionPermit.releaseExecution()` - Release after trade
- `FinalExecutionPermit.clearCycleState()` - Clean up at loop start

---

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
