# LIFECYCLE BOT V5.2
## Changelog & Release Notes

---

# Version 5.2 - "To The Moon" Edition
**Release Date**: March 2026

## Headline Features

### Moonshot Trading Layer
The most requested feature is here! A dedicated layer for hunting asymmetric opportunities.

**Space-Themed Trading Modes:**
- **🛸 ORBITAL** ($100K-$500K): Early launch detection, 100% base target
- **🌙 LUNAR** ($500K-$2M): Building momentum plays, 200% base target
- **🔴 MARS** ($2M-$5M): High conviction setups, 500% base target
- **🪐 JUPITER** ($5M-$50M): Mega plays from collective intelligence, 1000% target

**Key Features:**
- Dynamic trailing stops that tighten as gains increase
- Automatic mode upgrades as token grows
- Milestone tracking (10x, 100x, 1000x achievements)
- Lifetime and daily stats

### Cross-Trading Promotion Pathway
When a position in Treasury, ShitCoin, or BlueChip hits +200%:
- Automatically evaluated for Moonshot promotion
- If eligible, position transfers to Moonshot layer
- Trailing stops take over to maximize gains
- No more selling winners too early!

### Collective Learning Integration
Network-wide intelligence sharing for Moonshot:
- 10x+ winners flagged across the network
- JUPITER mode prioritizes collective winners
- Up to +20 bonus score for network-validated tokens

---

## Improvements

### Behavior AI Tilt Protection (Bootstrap-Aware)
The bot no longer over-blocks during its learning phase:

| Learning Progress | Loss Threshold | Cooldown |
|-------------------|----------------|----------|
| 0-10% | 15 losses | 30 sec |
| 10-20% | 12 losses | 30 sec |
| 20-40% | 10 losses | 45 sec |
| 40-60% | 8 losses | 45 sec |
| 60%+ | 5 losses | 60 sec |

This prevents the "extreme fear" death spiral during early paper trading.

### ShitCoin Layer Market Cap Fix
- Corrected from <$500K to <$30K
- Proper separation from Moonshot layer
- No more overlap in evaluation order

---

## Bug Fixes

### API Keys Backup/Restore
**Fixed**: API keys were being read from wrong SharedPreferences file
- Export now uses ConfigStore.load() (correct encrypted source)
- Import now uses ConfigStore.save() (correct encrypted destination)
- All keys properly backup: Helius, Birdeye, Groq, Gemini, Jupiter, wallet

### Moonshot Evaluation Order
**Fixed**: Moonshot layer was being bypassed
- Moved evaluation BEFORE ShitCoin (different mcap ranges, no conflict)
- Added proper TradeAuthorizer and FinalExecutionPermit checks
- Now properly executes Moonshot trades

---

## UI Updates

### New V3 Core Tile
- Added to dashboard alongside other layers
- Displays win/loss ratio
- Shows learning progress
- Tap for detailed stats

### New Moonshot Tile
- Space mode distribution (🛸 🌙 🔴 🪐)
- 10x/100x/1000x counters
- Active positions by mode
- Tap for full Moonshot dialog

### Reorganized Trading Mode Rows
- Row 1: V3 Core, Treasury, BlueChip
- Row 2: ShitCoin, Moonshot
- Better visual scaling on all device sizes

---

## Technical Changes

### TradeAuthorizer.kt
- Added `MOONSHOT` to ExecutionBook enum

### LayerTransitionManager.kt
- Added MOONSHOT, ORBITAL, LUNAR, MARS, JUPITER to TradingLayer enum
- Updated mcap ranges for all layers

### BotService.kt
- New Moonshot evaluation block (lines 3400-3520)
- Cross-trading promotion logic in exit handlers
- Fixed property references (lastV3Score, costSol, etc.)

### MoonshotTraderAI.kt
- Complete rewrite with space-themed architecture
- 600+ lines of new moonshot-specific logic

### BehaviorAI.kt
- Bootstrap-aware checkTiltProtection()
- Scaled thresholds based on FluidLearningAI progress

### PersistentLearning.kt
- Fixed exportBackup() to use ConfigStore
- Fixed importBackup() to use ConfigStore

### MainActivity.kt
- showV3ModeDialog() added
- showMoonshotModeDialog() added
- New tile click handlers

### activity_main.xml
- New trading mode layout with stats
- V3 and Moonshot tile additions

---

## Migration Notes

### From V5.1
- Automatic migration, no action required
- Paper trading progress preserved
- Learning data preserved

### API Keys
- If you had backup issues before, re-export after updating
- All keys should now properly backup/restore

---

## Known Issues

- First few Moonshot evaluations may show in logs before first trade
- Collective learning requires network connectivity
- JUPITER mode most effective with active collective network

---

## Coming in V5.3

- iOS version (in development)
- Multi-chain support (Base, Arbitrum)
- Advanced backtesting mode
- Custom strategy builder
- Performance analytics dashboard

---

# Version History

## V5.1 (February 2026)
- DipHunter layer improvements
- BlueChip momentum tracking
- Enhanced rug detection
- Bug fixes

## V5.0 (January 2026)
- Complete V3 engine rewrite
- 25+ AI layers
- Fluid learning system
- New dashboard design

## V4.20 (December 2025)
- ShitCoin layer launch
- BlueChip layer launch
- Mode orchestrator
- Layer transition system

## V4.0 (November 2025)
- Treasury mode (Cash Generation AI)
- Trade authorizer system
- Final execution permit
- Multi-layer architecture

## V3.0 (October 2025)
- V3 scoring engine
- AI cross-talk layer
- Entry/Exit AI
- Momentum AI
- Liquidity AI

## V2.0 (September 2025)
- Paper trading mode
- Basic AI layers
- Scanner improvements

## V1.0 (August 2025)
- Initial release
- Basic trading functionality
- Single-layer operation

---

*Full changelog available in git history*
