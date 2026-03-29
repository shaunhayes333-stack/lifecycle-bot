# AATE Changelog

All notable changes to the Autonomous Algorithmic Trading Engine.

## [v3.3.0] - 2026-03-29

### Added - Sentient Trading Architecture
- **FluidLearningAI (Layer 23)**: Centralized fluidity controller
  - All thresholds now scale from loose (30%) to strict (75-80%) as bot learns
  - Fresh installs get relaxed liquidity barriers to enable trading from Day 1
  - Automatically tightens as trade count increases ("training wheels" concept)
  - Unified lerp() function for all adaptive thresholds

- **Fluid Stop Loss & Take Profit** (NEW in this version)
  - Bootstrap: Wide SL (-10% max), tight TP (+5-8%) - learn safely, secure wins
  - Mature: Mode-specific SL/TP - protect capital, let winners run
  - Each trading mode (SNIPE, RANGE, AGGRESSIVE, etc.) gets fluid parameters
  - AutoModeEngine now uses FluidLearningAI for all stop/trailing calculations

- **SellOptimizationAI (Layer 24)**: Intelligent exit strategy layer
  - Chunk selling: 25%/50%/75% partial exits at profit milestones
  - Trailing stop locks that follow price upward
  - Exit urgency classification: NONE, LOW, MEDIUM, HIGH, CRITICAL
  - Tracks position state for coordinated multi-leg exits

- **Treasury Mode Compounding**: CashGenerationAI now compounds buys
  - Profits from winning trades feed back into position sizing
  - Immediate trading triggers on startup (no warm-up delay)
  - Aggressive mode kicks in after first win streak

- **Journal Data Persistence**: TradeHistoryStore improvements
  - Removed automatic 7-day cleanup (data persists until manual clear)
  - Win rate on MainActivity now calculated from persisted trades only
  - Added "Clear All" manual button in JournalActivity
  - StatsSnapshot includes totalTrades, winRate, avgWinPct

### Fixed
- **UI Trading Mode Labels**: MainActivity now shows actual MarketStructureRouter mode (e.g., "🚀 Fresh Launch") instead of hardcoded age-based labels
- **Collective Brain Instance Count**: Immediate heartbeats on init and screen open for accurate "Active Instances" display
- **JournalActivity refreshTrades()**: Added missing method that was breaking CI builds
- **Collective Learning for New Users**: CollectiveIntelligenceAI.refresh() now triggered on init so new users see shared data immediately

### Changed
- Total AI layers increased from 22 to **24**
- All modes (Paper/Live/Treasury) use fluid thresholds from FluidLearningAI
- UnifiedScorer.moduleNames() updated to include all 24 layers
- AutoModeEngine: All 6 bot modes (SNIPE, RANGE, AGGRESSIVE, DEFENSIVE, COPY, PAUSED) use fluid SL/TP

### Technical Notes
- 181 Kotlin source files
- All builds verified via GitHub Actions CI
- Fluid scaling uses sigmoid-like progression tied to lifetime trade count

---

## [v1.2.0] - 2025-03-27

### Added
- **Turso Collective Learning Integration**
  - Shared knowledge base across all AATE instances
  - Privacy-preserving pattern outcome sharing
  - Token blacklist synchronization (rugs, honeypots)
  - Mode performance stats by market condition
  - Whale wallet effectiveness ratings
  - New `TursoClient.kt` for HTTP REST API communication
  - New `CollectiveLearning.kt` orchestrator
  - New `CollectiveSchema.kt` with SQLite tables

### Changed
- `BotConfig.kt`: Added `tursoDbUrl`, `tursoAuthToken`, `collectiveLearningEnabled`
- `BotService.kt`: Added CollectiveLearning lifecycle management (init/shutdown)
- `TursoClient.kt`: Auto-converts `libsql://` URLs to `https://` for HTTP API

### Technical Details
- Uses Turso/LibSQL HTTP pipeline API (`/v2/pipeline`)
- Background sync every 15 minutes
- Local caching of collective data
- Privacy: No wallet addresses, trade sizes, or personal data shared

---

## [v1.1.0] - 2025-03-27

### Fixed
- **5 Critical Architecture Flaws**
  - Race condition bypassing Final Decision Gate in `Executor.kt`
  - WHALE_FOLLOW mode entering prematurely without confirmation
  - COPY_TRADE exit mapping logic
  - Entry score calculation with proper conviction weighting
  - Mode router priority ordering

- **4 Critical On-Chain Sell Execution Bugs**
  - Null wallet reconnects via `WalletManager`
  - Softened keypair integrity checks for sells (was blocking exits)
  - Jupiter Ultra v6 fallback when Ultra API fails
  - Fresh blockhash before signing transactions

### Added
- `PendingSellQueue.kt` for exit retry management
- VC pitch materials (Pitch Deck, One-Pager, Technical Deep Dive)
- Social media valuation posts
- "Built in under a week" narrative documentation

### Changed
- Release APK packaging for Play Protect compatibility
- GitHub Actions CI workflow improvements

---

## [v1.0.0] - 2025-03-25

### Initial Release

#### Core Features
- **12-Layer AI Consensus System**
  - EdgeLearning: Dynamic threshold adjustment
  - BehaviorLearning: Pattern outcome memory
  - EntryIntelligence: Entry pattern recognition
  - ExitIntelligence: Optimal exit timing
  - WhaleTrackerAI: Smart money flow analysis
  - MarketRegimeAI: Bull/Bear/Crab detection
  - MomentumPredictorAI: Pump probability scoring
  - NarrativeDetectorAI: Trending theme detection
  - TimeOptimizationAI: Optimal trading hours
  - LiquidityDepthAI: Real-time LP monitoring
  - AICrossTalk: Inter-layer signal arbitration
  - FinalDecisionGate: Trade approval checkpoint

- **18 Trading Modes**
  - PUMP_SNIPER, MOMENTUM_RIDE, WHALE_FOLLOW
  - SCALP_QUICK, RANGE_BOUND, RECOVERY_MODE
  - COPY_TRADE, DIAMOND_HANDS, SNIPE_GRADUATE
  - NARRATIVE_PLAY, BLUE_CHIP, SCALP_MICRO
  - DIP_HUNTER, BREAKOUT, MEAN_REVERT
  - NEWS_TRADE, GRID_TRADE, DEFENSIVE

- **Self-Learning Systems**
  - EdgeLearning: Adaptive thresholds
  - BehaviorLearning: Pattern memory
  - ModeLearning: Mode performance tracking
  - ScannerLearning: Source effectiveness

- **Security**
  - AES-256 encryption via Android EncryptedSharedPreferences
  - Hardware-backed Keystore
  - Jito MEV bundle protection
  - Circuit breakers and kill switches
  - Anti-rug protection (RugCheck.xyz integration)

- **UI**
  - Real-time price chart
  - Position PnL tracking
  - Trade history
  - Brain learning indicator
  - Open positions panel
  - Quick stats bar

#### Technical Stats
- 63,000+ lines of production Kotlin code
- 80+ source files
- Native Android (no web wrapper)
- Built from scratch in 7 days

---

## Development Notes

### Testing Protocol
- All changes verified via GitHub Actions CI
- No local Kotlin compiler available in development environment
- Push to GitHub → Check CI → Fix errors → Repeat

### Privacy Commitment
- No telemetry or analytics
- All learning data stored locally by default
- Collective Learning is opt-in only
- No wallet addresses shared (only hashed)
- No trade sizes or personal data transmitted
