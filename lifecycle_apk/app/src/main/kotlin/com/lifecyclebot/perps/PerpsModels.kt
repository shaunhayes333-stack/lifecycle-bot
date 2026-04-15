package com.lifecyclebot.perps

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 SOL PERPS & LEVERAGE DATA MODELS - V5.7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * SUPER SMART Leverage Trading System for:
 * - SOL-PERP (native Solana perpetuals)
 * - Tokenized Real-World Assets (AAPL, TSLA, NVDA, etc.)
 * 
 * PHILOSOPHY:
 * - Reuses existing bot infrastructure where possible
 * - Fluid AI sizing intelligence
 * - Strict discipline rules
 * - Paper/Live mode separation
 * - Live Readiness Gauge
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// ═══════════════════════════════════════════════════════════════════════════════
// ENUMS - LEVERAGE DIRECTION
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsDirection(val symbol: String, val emoji: String, val multiplier: Int) {
    LONG("LONG", "📈", 1),
    SHORT("SHORT", "📉", -1),
}

// ═══════════════════════════════════════════════════════════════════════════════
// RISK TIERS - INTELLIGENT LEVERAGE MANAGEMENT
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsRiskTier(
    val emoji: String,
    val displayName: String,
    val maxLeverage: Double,
    val maxPositionPct: Double,
    val stopLossPct: Double,
    val takeProfitPct: Double,
    val color: String,
) {
    SNIPER("🎯", "Sniper", 2.0, 5.0, 3.0, 8.0, "#22C55E"),       // Conservative - Green
    TACTICAL("⚔️", "Tactical", 5.0, 10.0, 5.0, 15.0, "#3B82F6"),  // Moderate - Blue
    ASSAULT("💥", "Assault", 10.0, 15.0, 8.0, 25.0, "#F59E0B"),   // Aggressive - Yellow
    NUKE("☢️", "Nuclear", 20.0, 25.0, 12.0, 50.0, "#EF4444"),     // Degen - Red
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRADEABLE MARKETS - CRYPTO + STOCKS + COMMODITIES + FOREX
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsMarket(
    val symbol: String,
    val emoji: String,
    val displayName: String,
    val isStock: Boolean,
    val maxLeverage: Double,
    val tradingHours: String,
    val color: String,
) {
    // ═══════════════════════════════════════════════════════════════════════════
    // 🪙 MAJOR CRYPTOCURRENCIES
    // ═══════════════════════════════════════════════════════════════════════════
    SOL("SOL", "◎", "Solana", false, 20.0, "24/7", "#14F195"),
    BTC("BTC", "₿", "Bitcoin", false, 20.0, "24/7", "#F7931A"),
    ETH("ETH", "⟠", "Ethereum", false, 20.0, "24/7", "#627EEA"),
    BNB("BNB", "🔶", "BNB", false, 20.0, "24/7", "#F3BA2F"),
    XRP("XRP", "💧", "Ripple", false, 20.0, "24/7", "#23292F"),
    ADA("ADA", "🔵", "Cardano", false, 20.0, "24/7", "#0033AD"),
    DOGE("DOGE", "🐕", "Dogecoin", false, 20.0, "24/7", "#C2A633"),
    AVAX("AVAX", "🔺", "Avalanche", false, 20.0, "24/7", "#E84142"),
    DOT("DOT", "⚫", "Polkadot", false, 20.0, "24/7", "#E6007A"),
    LINK("LINK", "🔗", "Chainlink", false, 20.0, "24/7", "#375BD2"),
    MATIC("MATIC", "💜", "Polygon", false, 20.0, "24/7", "#8247E5"),
    SHIB("SHIB", "🐕", "Shiba Inu", false, 20.0, "24/7", "#FFA409"),
    LTC("LTC", "Ł", "Litecoin", false, 20.0, "24/7", "#BFBBBB"),
    ATOM("ATOM", "⚛️", "Cosmos", false, 20.0, "24/7", "#2E3148"),
    UNI("UNI", "🦄", "Uniswap", false, 20.0, "24/7", "#FF007A"),
    ARB("ARB", "🔵", "Arbitrum", false, 20.0, "24/7", "#28A0F0"),
    OP("OP", "🔴", "Optimism", false, 20.0, "24/7", "#FF0420"),
    APT("APT", "🟢", "Aptos", false, 20.0, "24/7", "#00D4AA"),
    SUI("SUI", "💧", "Sui", false, 20.0, "24/7", "#6FBCF0"),
    SEI("SEI", "🌊", "Sei", false, 20.0, "24/7", "#9B1C1C"),
    INJ("INJ", "💉", "Injective", false, 20.0, "24/7", "#00F2FE"),
    TIA("TIA", "🌌", "Celestia", false, 20.0, "24/7", "#7B2BF9"),
    JUP("JUP", "🪐", "Jupiter", false, 20.0, "24/7", "#00D395"),
    PEPE("PEPE", "🐸", "Pepe", false, 20.0, "24/7", "#3D9B47"),
    WIF("WIF", "🐕", "dogwifhat", false, 20.0, "24/7", "#C8A96A"),
    BONK("BONK", "🦴", "Bonk", false, 20.0, "24/7", "#F8A21A"),
    
    // 🔥 V5.7.6: NEW CRYPTO - Layer 1s & DeFi
    NEAR("NEAR", "🌐", "NEAR Protocol", false, 20.0, "24/7", "#00C08B"),
    FTM("FTM", "👻", "Fantom", false, 20.0, "24/7", "#1969FF"),
    ALGO("ALGO", "🔺", "Algorand", false, 20.0, "24/7", "#000000"),
    HBAR("HBAR", "⬡", "Hedera", false, 20.0, "24/7", "#3A3A3A"),
    ICP("ICP", "∞", "Internet Computer", false, 20.0, "24/7", "#3B00B9"),
    VET("VET", "✓", "VeChain", false, 20.0, "24/7", "#15BDFF"),
    FIL("FIL", "📁", "Filecoin", false, 20.0, "24/7", "#0090FF"),
    RENDER("RENDER", "🎨", "Render", false, 20.0, "24/7", "#FF4F5A"),
    GRT("GRT", "📊", "The Graph", false, 20.0, "24/7", "#6747ED"),
    AAVE("AAVE", "👻", "Aave", false, 20.0, "24/7", "#B6509E"),
    MKR("MKR", "🏛️", "Maker", false, 20.0, "24/7", "#1AAB9B"),
    SNX("SNX", "💎", "Synthetix", false, 20.0, "24/7", "#00D1FF"),
    CRV("CRV", "〰️", "Curve DAO", false, 20.0, "24/7", "#FF5733"),
    RUNE("RUNE", "⚡", "THORChain", false, 20.0, "24/7", "#33FF99"),
    STX("STX", "📚", "Stacks", false, 20.0, "24/7", "#5546FF"),
    IMX("IMX", "🎮", "Immutable", false, 20.0, "24/7", "#00BFFF"),
    SAND("SAND", "🏖️", "The Sandbox", false, 20.0, "24/7", "#04ADEF"),
    MANA("MANA", "🌍", "Decentraland", false, 20.0, "24/7", "#FF2D55"),
    AXS("AXS", "🎮", "Axie Infinity", false, 20.0, "24/7", "#0055D5"),
    ENS("ENS", "🔗", "ENS Domains", false, 20.0, "24/7", "#5298FF"),
    LDO("LDO", "🌊", "Lido DAO", false, 20.0, "24/7", "#00A3FF"),
    RPL("RPL", "🚀", "Rocket Pool", false, 20.0, "24/7", "#FF6B35"),
    PYTH("PYTH", "🔮", "Pyth Network", false, 20.0, "24/7", "#E6DAFE"),
    RAY("RAY", "☀️", "Raydium", false, 20.0, "24/7", "#C7B2FF"),
    ORCA("ORCA", "🐋", "Orca", false, 20.0, "24/7", "#FFD700"),
    MNGO("MNGO", "🥭", "Mango Markets", false, 20.0, "24/7", "#F2C94C"),
    DRIFT("DRIFT", "🌊", "Drift Protocol", false, 20.0, "24/7", "#6366F1"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🪙 V5.8: MAJOR ALTS - High Market Cap
    // ═══════════════════════════════════════════════════════════════════════════
    TRX("TRX", "♻️", "TRON", false, 20.0, "24/7", "#FF0013"),
    TON("TON", "💎", "Toncoin", false, 20.0, "24/7", "#0088CC"),
    BCH("BCH", "🟩", "Bitcoin Cash", false, 20.0, "24/7", "#8DC351"),
    XLM("XLM", "✨", "Stellar Lumens", false, 20.0, "24/7", "#7D00FF"),
    XMR("XMR", "🔒", "Monero", false, 20.0, "24/7", "#FF6600"),
    ETC("ETC", "⟠", "Ethereum Classic", false, 20.0, "24/7", "#328332"),
    ZEC("ZEC", "🛡️", "Zcash", false, 20.0, "24/7", "#F4B728"),
    XTZ("XTZ", "Ꜩ", "Tezos", false, 20.0, "24/7", "#2C7DF7"),
    EOS("EOS", "⚫", "EOS", false, 20.0, "24/7", "#443F54"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🔵 V5.8: DeFi & DEX PROTOCOLS
    // ═══════════════════════════════════════════════════════════════════════════
    CAKE("CAKE", "🥞", "PancakeSwap", false, 20.0, "24/7", "#1FC7D4"),
    GMX("GMX", "🔵", "GMX", false, 20.0, "24/7", "#3B4FF8"),
    DYDX("DYDX", "📊", "dYdX", false, 20.0, "24/7", "#6966FF"),
    ENA("ENA", "💠", "Ethena", false, 20.0, "24/7", "#7A2FEB"),
    PENDLE("PENDLE", "⚡", "Pendle", false, 20.0, "24/7", "#00B2FF"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🚀 V5.8: NEW SOLANA & CROSS-CHAIN ECOSYSTEM
    // ═══════════════════════════════════════════════════════════════════════════
    WLD("WLD", "🌍", "Worldcoin", false, 20.0, "24/7", "#000000"),
    JTO("JTO", "🪐", "Jito", false, 20.0, "24/7", "#01C37D"),
    W("W", "🌀", "Wormhole", false, 20.0, "24/7", "#7B00FF"),
    STRK("STRK", "⚡", "Starknet", false, 20.0, "24/7", "#EC796B"),
    TAO("TAO", "🧠", "Bittensor", false, 20.0, "24/7", "#E6007A"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🐸 V5.8: MEME COINS
    // ═══════════════════════════════════════════════════════════════════════════
    FLOKI("FLOKI", "🐕", "FLOKI", false, 20.0, "24/7", "#E2B100"),
    NOT("NOT", "💬", "Notcoin", false, 20.0, "24/7", "#F7B731"),
    POPCAT("POPCAT", "🐱", "Popcat", false, 20.0, "24/7", "#FF6B35"),
    TRUMP("TRUMP", "🎩", "OFFICIAL TRUMP", false, 20.0, "24/7", "#FF0000"),

    // ═══════════════════════════════════════════════════════════════════════════
    // CRYPTO ALTS — EXPANDED UNIVERSE (Top 200+ non-SOL-meme altcoins)
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Privacy coins ────────────────────────────────────────────────────────
    // ZEC, XMR already listed above

    // ── Established OG alts ──────────────────────────────────────────────────
    THETA("THETA", "θ", "Theta Network", false, 20.0, "24/7", "#2CADCA"),
    EGLD("EGLD", "⚡", "MultiversX", false, 20.0, "24/7", "#1B46C2"),
    ZIL("ZIL", "🟣", "Zilliqa", false, 20.0, "24/7", "#49C1BF"),
    ONE("ONE", "1️⃣", "Harmony", false, 20.0, "24/7", "#00AEE9"),
    IOTA("IOTA", "🔺", "IOTA", false, 20.0, "24/7", "#242424"),
    DASH("DASH", "🔵", "Dash", false, 20.0, "24/7", "#008CE7"),
    ZEN("ZEN", "🛡️", "Horizen", false, 20.0, "24/7", "#0E9DE5"),
    WAVES("WAVES", "〰️", "Waves", false, 20.0, "24/7", "#0055FF"),
    DCR("DCR", "🔷", "Decred", false, 20.0, "24/7", "#2ED6A1"),
    QTUM("QTUM", "🔵", "Qtum", false, 20.0, "24/7", "#2895D8"),
    ONT("ONT", "🦋", "Ontology", false, 20.0, "24/7", "#32A4BE"),
    SC("SC", "🌥️", "Siacoin", false, 20.0, "24/7", "#20EE82"),
    BTT("BTT", "🔵", "BitTorrent", false, 20.0, "24/7", "#CC1C1C"),
    WIN("WIN", "🎰", "WINkLink", false, 20.0, "24/7", "#FF4C4C"),
    JST("JST", "⚡", "JUST", false, 20.0, "24/7", "#FCAD23"),
    KAS("KAS", "⚡", "Kaspa", false, 20.0, "24/7", "#49DECE"),
    COTI("COTI", "💠", "COTI", false, 20.0, "24/7", "#00A9FF"),
    CELR("CELR", "🌐", "Celer Network", false, 20.0, "24/7", "#8EF5C8"),
    XDC("XDC", "🔵", "XDC Network", false, 20.0, "24/7", "#2187DC"),
    ROSE("ROSE", "🌹", "Oasis Network", false, 20.0, "24/7", "#00E2FF"),
    CELO("CELO", "🌿", "Celo", false, 20.0, "24/7", "#FCFF52"),
    FLOW("FLOW", "🌊", "Flow", false, 20.0, "24/7", "#04FECF"),
    KAVA("KAVA", "🟠", "Kava", false, 20.0, "24/7", "#FF5734"),
    FLR("FLR", "🔥", "Flare", false, 20.0, "24/7", "#E62058"),
    ICX("ICX", "🔮", "ICON", false, 20.0, "24/7", "#1FC5C9"),
    ZRX("ZRX", "⬛", "0x Protocol", false, 20.0, "24/7", "#231815"),
    ANKR("ANKR", "🔵", "Ankr", false, 20.0, "24/7", "#2E65F3"),
    SKL("SKL", "⬟", "SKALE", false, 20.0, "24/7", "#000000"),
    GNO("GNO", "🦉", "Gnosis", false, 20.0, "24/7", "#1D6A96"),
    // ── Layer 2 expanded ─────────────────────────────────────────────────────
    METIS("METIS", "⚡", "Metis", false, 20.0, "24/7", "#00D2FF"),
    BLAST("BLAST", "💥", "Blast", false, 20.0, "24/7", "#FCFC03"),
    MANTLE("MANTLE", "🔷", "Mantle", false, 20.0, "24/7", "#000000"),
    MANTA("MANTA", "🐋", "Manta Network", false, 20.0, "24/7", "#1EAAF1"),
    SCROLL("SCROLL", "📜", "Scroll", false, 20.0, "24/7", "#FFEEDA"),
    ZK("ZK", "🔐", "ZKsync", false, 20.0, "24/7", "#8B5CF6"),
    // ── DeFi expanded ────────────────────────────────────────────────────────
    COMP("COMP", "🏦", "Compound", false, 20.0, "24/7", "#00D395"),
    SUSHI("SUSHI", "🍱", "SushiSwap", false, 20.0, "24/7", "#FA52A0"),
    BAL("BAL", "⚖️", "Balancer", false, 20.0, "24/7", "#1E1E1E"),
    OSMO("OSMO", "🔮", "Osmosis", false, 20.0, "24/7", "#750BBB"),
    CVX("CVX", "🔒", "Convex Finance", false, 20.0, "24/7", "#3A3A3A"),
    FXS("FXS", "💲", "Frax Share", false, 20.0, "24/7", "#000000"),
    LQTY("LQTY", "🏦", "Liquity", false, 20.0, "24/7", "#745DDF"),
    SPELL("SPELL", "🧙", "Spell Token", false, 20.0, "24/7", "#7B2BF9"),
    PERP("PERP", "📊", "Perpetual Protocol", false, 20.0, "24/7", "#3CEAAA"),
    DODO("DODO", "🐦", "DODO", false, 20.0, "24/7", "#FEE902"),
    ALPHA("ALPHA", "α", "Alpha Venture DAO", false, 20.0, "24/7", "#1A1A2E"),
    FIDA("FIDA", "🔵", "Bonfida", false, 20.0, "24/7", "#B8A4FF"),
    // ── AI / Compute / Data expanded ─────────────────────────────────────────
    ALT("ALT", "🤖", "AltLayer", false, 20.0, "24/7", "#8A2BE2"),
    IO("IO", "🖥️", "io.net", false, 20.0, "24/7", "#00F5FF"),
    VIRTUAL("VIRTUAL", "🤖", "Virtuals Protocol", false, 20.0, "24/7", "#7C3AED"),
    HYPE("HYPE", "⚡", "Hyperliquid", false, 20.0, "24/7", "#00FF87"),
    MOVE("MOVE", "🔄", "Movement", false, 20.0, "24/7", "#FF6B35"),
    // ── Infrastructure / Interop ─────────────────────────────────────────────
    TNSR("TNSR", "⚡", "Tensor", false, 20.0, "24/7", "#00B4D8"),
    KMNO("KMNO", "💧", "Kamino", false, 20.0, "24/7", "#00D4AA"),
    PIXEL("PIXEL", "🎮", "Pixels", false, 20.0, "24/7", "#FF69B4"),
    PORTAL("PORTAL", "🌀", "Portal Gaming", false, 20.0, "24/7", "#6366F1"),
    // ── Gaming / NFT expanded ────────────────────────────────────────────────
    RON("RON", "⚔️", "Ronin", false, 20.0, "24/7", "#1273EA"),
    MAGIC("MAGIC", "✨", "Treasure", false, 20.0, "24/7", "#DC2626"),
    ENJ("ENJ", "💎", "Enjin Coin", false, 20.0, "24/7", "#624DBF"),
    CHZ("CHZ", "⚽", "Chiliz", false, 20.0, "24/7", "#CD0124"),
    AUDIO("AUDIO", "🎵", "Audius", false, 20.0, "24/7", "#CC0FE0"),
    GALA("GALA", "🎮", "Gala", false, 20.0, "24/7", "#0078FF"),
    // ── Stablecoins / Liquid staking (tradeable) ─────────────────────────────
    WBTC("WBTC", "₿", "Wrapped Bitcoin", false, 20.0, "24/7", "#F7931A"),
    PAXG("PAXG", "🥇", "PAX Gold", false, 20.0, "24/7", "#D4A84B"),
    MSOL("MSOL", "◎", "Marinade SOL", false, 20.0, "24/7", "#00D18C"),
    STETH("STETH", "⟠", "Lido Staked ETH", false, 20.0, "24/7", "#00A3FF"),
    // ── Meme coins — Ethereum / cross-chain ──────────────────────────────────
    BABYDOGE("BABYDOGE", "🐶", "Baby Doge Coin", false, 20.0, "24/7", "#F3BA2F"),
    WOJAK("WOJAK", "😢", "Wojak", false, 20.0, "24/7", "#7CB9E8"),
    TURBO("TURBO", "🚀", "Turbo", false, 20.0, "24/7", "#FF4500"),
    MOG("MOG", "😼", "Mog Coin", false, 20.0, "24/7", "#8A2BE2"),
    NEIRO("NEIRO", "🐕", "Neiro", false, 20.0, "24/7", "#F4A460"),
    BRETT("BRETT", "🐸", "Brett", false, 20.0, "24/7", "#4169E1"),
    DEGEN("DEGEN", "🎩", "Degen", false, 20.0, "24/7", "#A855F7"),
    // ── Newer cycle tokens ───────────────────────────────────────────────────
    JASMY("JASMY", "🔷", "JasmyCoin", false, 20.0, "24/7", "#2775CA"),
    STG("STG", "🌉", "Stargate Finance", false, 20.0, "24/7", "#999999"),
    BLUR("BLUR", "🎨", "Blur", false, 20.0, "24/7", "#FF6600"),

    // ═══════════════════════════════════════════════════════════════════════════
    // TOKENIZED STOCKS - All available on Solana via Pyth Oracle
    // ═══════════════════════════════════════════════════════════════════════════
    
    // 🔥 MEGA TECH (FAANG+)
    AAPL("AAPL", "🍎", "Apple Inc.", true, 10.0, "MARKET", "#A2AAAD"),
    TSLA("TSLA", "🚗", "Tesla Inc.", true, 10.0, "MARKET", "#E31937"),
    NVDA("NVDA", "🖥️", "NVIDIA Corp.", true, 10.0, "MARKET", "#76B900"),
    GOOGL("GOOGL", "🔍", "Alphabet Inc.", true, 10.0, "MARKET", "#4285F4"),
    AMZN("AMZN", "📦", "Amazon.com", true, 10.0, "MARKET", "#FF9900"),
    META("META", "👤", "Meta Platforms", true, 10.0, "MARKET", "#0081FB"),
    MSFT("MSFT", "🪟", "Microsoft Corp.", true, 10.0, "MARKET", "#00A4EF"),
    NFLX("NFLX", "🎬", "Netflix Inc.", true, 10.0, "MARKET", "#E50914"),
    
    // 💎 SEMICONDUCTORS
    AMD("AMD", "🔴", "AMD Inc.", true, 10.0, "MARKET", "#ED1C24"),
    INTC("INTC", "🔵", "Intel Corp.", true, 10.0, "MARKET", "#0071C5"),
    QCOM("QCOM", "📱", "Qualcomm Inc.", true, 10.0, "MARKET", "#3253DC"),
    AVGO("AVGO", "⚡", "Broadcom Inc.", true, 10.0, "MARKET", "#CC092F"),
    MU("MU", "💾", "Micron Tech.", true, 10.0, "MARKET", "#0033A0"),
    TSM("TSM", "🔧", "Taiwan Semi", true, 10.0, "MARKET", "#CC0000"),
    ASML("ASML", "🔬", "ASML Holding", true, 10.0, "MARKET", "#00A9E0"),
    ARM("ARM", "💪", "ARM Holdings", true, 10.0, "MARKET", "#0091BD"),
    MRVL("MRVL", "🔷", "Marvell Tech", true, 10.0, "MARKET", "#E31937"),
    
    // 🚀 GROWTH TECH
    CRM("CRM", "☁️", "Salesforce", true, 10.0, "MARKET", "#00A1E0"),
    ORCL("ORCL", "🔮", "Oracle Corp.", true, 10.0, "MARKET", "#F80000"),
    PLTR("PLTR", "🛡️", "Palantir", true, 10.0, "MARKET", "#101010"),
    SNOW("SNOW", "❄️", "Snowflake", true, 10.0, "MARKET", "#29B5E8"),
    SHOP("SHOP", "🛒", "Shopify", true, 10.0, "MARKET", "#96BF48"),
    SPOT("SPOT", "🎵", "Spotify", true, 10.0, "MARKET", "#1DB954"),
    ZM("ZM", "📹", "Zoom Video", true, 10.0, "MARKET", "#2D8CFF"),
    ROKU("ROKU", "📺", "Roku Inc.", true, 10.0, "MARKET", "#6C3C97"),
    SQ("SQ", "⬜", "Block Inc.", true, 10.0, "MARKET", "#3E4348"),
    TWLO("TWLO", "📞", "Twilio", true, 10.0, "MARKET", "#F22F46"),
    
    // 🤖 AI & CLOUD
    AI("AI", "🤖", "C3.ai", true, 10.0, "MARKET", "#00A3E0"),
    PATH("PATH", "🤖", "UiPath", true, 10.0, "MARKET", "#FA4616"),
    DDOG("DDOG", "🐶", "Datadog", true, 10.0, "MARKET", "#632CA6"),
    NET("NET", "☁️", "Cloudflare", true, 10.0, "MARKET", "#F38020"),
    CRWD("CRWD", "🦅", "CrowdStrike", true, 10.0, "MARKET", "#FA0001"),
    ZS("ZS", "🔒", "Zscaler", true, 10.0, "MARKET", "#049FD9"),
    MDB("MDB", "🍃", "MongoDB", true, 10.0, "MARKET", "#00ED64"),
    
    // 💳 FINTECH & CRYPTO
    COIN("COIN", "🪙", "Coinbase", true, 10.0, "MARKET", "#0052FF"),
    PYPL("PYPL", "💳", "PayPal", true, 10.0, "MARKET", "#003087"),
    V("V", "💳", "Visa Inc.", true, 10.0, "MARKET", "#1A1F71"),
    MA("MA", "💳", "Mastercard", true, 10.0, "MARKET", "#EB001B"),
    JPM("JPM", "🏦", "JPMorgan", true, 10.0, "MARKET", "#117ACA"),
    GS("GS", "🏛️", "Goldman Sachs", true, 10.0, "MARKET", "#7399C6"),
    BAC("BAC", "🏦", "Bank of America", true, 10.0, "MARKET", "#012169"),
    WFC("WFC", "🏦", "Wells Fargo", true, 10.0, "MARKET", "#D71E28"),
    C("C", "🏦", "Citigroup", true, 10.0, "MARKET", "#056DAE"),
    MSTR("MSTR", "₿", "MicroStrategy", true, 10.0, "MARKET", "#D9281C"),
    HOOD("HOOD", "🪶", "Robinhood", true, 10.0, "MARKET", "#00C805"),
    SOFI("SOFI", "💰", "SoFi Tech", true, 10.0, "MARKET", "#00D4AA"),
    NU("NU", "💜", "Nu Holdings", true, 10.0, "MARKET", "#820AD1"),
    
    // 🎯 CONSUMER & TRAVEL
    DIS("DIS", "🏰", "Disney", true, 10.0, "MARKET", "#113CCF"),
    UBER("UBER", "🚕", "Uber", true, 10.0, "MARKET", "#000000"),
    ABNB("ABNB", "🏠", "Airbnb", true, 10.0, "MARKET", "#FF5A5F"),
    NKE("NKE", "👟", "Nike Inc.", true, 10.0, "MARKET", "#111111"),
    SBUX("SBUX", "☕", "Starbucks", true, 10.0, "MARKET", "#00704A"),
    MCD("MCD", "🍔", "McDonald's", true, 10.0, "MARKET", "#FFC72C"),
    CMG("CMG", "🌯", "Chipotle", true, 10.0, "MARKET", "#A81612"),
    LULU("LULU", "🧘", "Lululemon", true, 10.0, "MARKET", "#D31334"),
    
    // 🏭 INDUSTRIAL & RETAIL
    BA("BA", "✈️", "Boeing", true, 10.0, "MARKET", "#0033A0"),
    WMT("WMT", "🛍️", "Walmart", true, 10.0, "MARKET", "#0071CE"),
    HD("HD", "🔨", "Home Depot", true, 10.0, "MARKET", "#F96302"),
    COST("COST", "📦", "Costco", true, 10.0, "MARKET", "#E31837"),
    TGT("TGT", "🎯", "Target", true, 10.0, "MARKET", "#CC0000"),
    LOW("LOW", "🔧", "Lowe's", true, 10.0, "MARKET", "#004990"),
    CAT("CAT", "🚜", "Caterpillar", true, 10.0, "MARKET", "#FFCD11"),
    DE("DE", "🚜", "John Deere", true, 10.0, "MARKET", "#367C2B"),
    LMT("LMT", "🛡️", "Lockheed Martin", true, 10.0, "MARKET", "#00237D"),
    RTX("RTX", "🚀", "RTX Corp", true, 10.0, "MARKET", "#003087"),
    
    // 🧬 HEALTHCARE
    JNJ("JNJ", "💊", "Johnson & Johnson", true, 10.0, "MARKET", "#D51900"),
    PFE("PFE", "💉", "Pfizer", true, 10.0, "MARKET", "#0093D0"),
    UNH("UNH", "🏥", "UnitedHealth", true, 10.0, "MARKET", "#002677"),
    MRNA("MRNA", "🧬", "Moderna", true, 10.0, "MARKET", "#1A1A1A"),
    LLY("LLY", "💊", "Eli Lilly", true, 10.0, "MARKET", "#D52B1E"),
    ABBV("ABBV", "💊", "AbbVie", true, 10.0, "MARKET", "#071D49"),
    TMO("TMO", "🔬", "Thermo Fisher", true, 10.0, "MARKET", "#1E4278"),
    
    // 🥤 CONSUMER STAPLES
    KO("KO", "🥤", "Coca-Cola", true, 10.0, "MARKET", "#F40009"),
    PEP("PEP", "🥤", "PepsiCo", true, 10.0, "MARKET", "#004B93"),
    PG("PG", "🧴", "Procter & Gamble", true, 10.0, "MARKET", "#003DA5"),
    PM("PM", "🚬", "Philip Morris", true, 10.0, "MARKET", "#003366"),
    MO("MO", "🚬", "Altria Group", true, 10.0, "MARKET", "#003366"),
    
    // 📞 TELECOM
    T("T", "📞", "AT&T", true, 10.0, "MARKET", "#00A8E0"),
    VZ("VZ", "📶", "Verizon", true, 10.0, "MARKET", "#CD040B"),
    TMUS("TMUS", "📱", "T-Mobile", true, 10.0, "MARKET", "#E20074"),
    
    // 💊 PHARMA & BIOTECH
    CVS("CVS", "💊", "CVS Health", true, 10.0, "MARKET", "#CC0000"),
    GILD("GILD", "🧬", "Gilead Sciences", true, 10.0, "MARKET", "#003B71"),
    BMY("BMY", "💉", "Bristol-Myers", true, 10.0, "MARKET", "#BE1E2D"),
    BIIB("BIIB", "🧠", "Biogen", true, 10.0, "MARKET", "#003087"),
    REGN("REGN", "🔬", "Regeneron", true, 10.0, "MARKET", "#002B5C"),
    VRTX("VRTX", "🧬", "Vertex Pharma", true, 10.0, "MARKET", "#00A3E0"),
    
    // ⛽ ENERGY STOCKS
    XOM("XOM", "⛽", "Exxon Mobil", true, 10.0, "MARKET", "#ED1B2D"),
    CVX("CVX", "🛢️", "Chevron", true, 10.0, "MARKET", "#0066B2"),
    COP("COP", "🛢️", "ConocoPhillips", true, 10.0, "MARKET", "#ED1C24"),
    OXY("OXY", "🛢️", "Occidental", true, 10.0, "MARKET", "#EE3124"),
    
    // ⚡ CLEAN ENERGY
    ENPH("ENPH", "☀️", "Enphase Energy", true, 10.0, "MARKET", "#F26322"),
    FSLR("FSLR", "☀️", "First Solar", true, 10.0, "MARKET", "#002E5D"),
    PLUG("PLUG", "🔋", "Plug Power", true, 10.0, "MARKET", "#00A651"),
    NEE("NEE", "⚡", "NextEra Energy", true, 10.0, "MARKET", "#006400"),
    
    // 🚗 EV & AUTOMOTIVE
    RIVN("RIVN", "🚗", "Rivian", true, 10.0, "MARKET", "#0C6A35"),
    LCID("LCID", "🚗", "Lucid Motors", true, 10.0, "MARKET", "#44525D"),
    F("F", "🚗", "Ford Motor", true, 10.0, "MARKET", "#003478"),
    GM("GM", "🚗", "General Motors", true, 10.0, "MARKET", "#0170CE"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 📊 INDEX ETFs - Major Market Indices (Pyth-powered, 24/7)
    // ═══════════════════════════════════════════════════════════════════════════
    SPY("SPY", "📈", "S&P 500 ETF", true, 10.0, "24/7", "#00875A"),
    QQQ("QQQ", "💻", "NASDAQ 100 ETF", true, 10.0, "24/7", "#0091D5"),
    DIA("DIA", "🏛️", "Dow Jones ETF", true, 10.0, "24/7", "#003087"),
    IWM("IWM", "📊", "Russell 2000 ETF", true, 10.0, "24/7", "#FF6600"),
    VTI("VTI", "🌎", "Total Stock Market", true, 10.0, "24/7", "#96151D"),
    EEM("EEM", "🌏", "Emerging Markets", true, 10.0, "24/7", "#00A650"),
    EFA("EFA", "🌍", "EAFE Intl ETF", true, 10.0, "24/7", "#0033A0"),
    GLD("GLD", "🥇", "Gold ETF", true, 10.0, "24/7", "#FFD700"),
    SLV("SLV", "🥈", "Silver ETF", true, 10.0, "24/7", "#C0C0C0"),
    TLT("TLT", "📜", "20+ Yr Treasury", true, 10.0, "24/7", "#003366"),
    XLF("XLF", "🏦", "Financial Sector", true, 10.0, "24/7", "#00796B"),
    XLE("XLE", "⛽", "Energy Sector", true, 10.0, "24/7", "#F57C00"),
    XLK("XLK", "💾", "Tech Sector", true, 10.0, "24/7", "#1565C0"),
    XLV("XLV", "💊", "Healthcare Sector", true, 10.0, "24/7", "#2E7D32"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🌿 CANNABIS SECTOR - High Volatility
    // ═══════════════════════════════════════════════════════════════════════════
    TLRY("TLRY", "🌿", "Tilray Brands", true, 10.0, "MARKET", "#00843D"),
    CGC("CGC", "🌿", "Canopy Growth", true, 10.0, "MARKET", "#8DC63F"),
    ACB("ACB", "🌿", "Aurora Cannabis", true, 10.0, "MARKET", "#5C2D91"),
    CRON("CRON", "🌿", "Cronos Group", true, 10.0, "MARKET", "#1E3A5F"),
    SNDL("SNDL", "🌿", "SNDL Inc", true, 10.0, "MARKET", "#228B22"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🚀 HOT MOMENTUM STOCKS - High Risk/Reward
    // ═══════════════════════════════════════════════════════════════════════════
    SMCI("SMCI", "🖥️", "Super Micro", true, 10.0, "MARKET", "#1E90FF"),
    IONQ("IONQ", "⚛️", "IonQ Quantum", true, 10.0, "MARKET", "#6A0DAD"),
    RKLB("RKLB", "🚀", "Rocket Lab", true, 10.0, "MARKET", "#000000"),
    RDDT("RDDT", "👽", "Reddit", true, 10.0, "MARKET", "#FF4500"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🇨🇳 CHINA ADR STOCKS - High Growth / High Volatility
    // ═══════════════════════════════════════════════════════════════════════════
    BABA("BABA", "🇨🇳", "Alibaba Group", true, 10.0, "MARKET", "#FF6A00"),
    BIDU("BIDU", "🇨🇳", "Baidu Inc", true, 10.0, "MARKET", "#2932E1"),
    JD("JD", "🇨🇳", "JD.com", true, 10.0, "MARKET", "#E2231A"),
    NIO("NIO", "🇨🇳", "NIO Inc", true, 10.0, "MARKET", "#00BFFF"),
    XPEV("XPEV", "🇨🇳", "XPeng Inc", true, 10.0, "MARKET", "#00D4AA"),
    LI("LI", "🇨🇳", "Li Auto Inc", true, 10.0, "MARKET", "#000000"),
    PDD("PDD", "🇨🇳", "PDD Holdings", true, 10.0, "MARKET", "#E02E24"),
    TCEHY("TCEHY", "🇨🇳", "Tencent ADR", true, 10.0, "MARKET", "#25A2E0"),
    BILI("BILI", "🇨🇳", "Bilibili Inc", true, 10.0, "MARKET", "#FB7299"),
    TME("TME", "🇨🇳", "Tencent Music", true, 10.0, "MARKET", "#00B2A9"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🇯🇵 JAPAN ADR STOCKS - Quality Large Caps
    // ═══════════════════════════════════════════════════════════════════════════
    SONY("SONY", "🇯🇵", "Sony Group", true, 10.0, "MARKET", "#000000"),
    TM("TM", "🇯🇵", "Toyota Motor", true, 10.0, "MARKET", "#EB0A1E"),
    NTDOY("NTDOY", "🇯🇵", "Nintendo ADR", true, 10.0, "MARKET", "#E60012"),
    MUFG("MUFG", "🇯🇵", "Mitsubishi UFJ", true, 10.0, "MARKET", "#C8102E"),
    HMC("HMC", "🇯🇵", "Honda Motor", true, 10.0, "MARKET", "#CC0000"),
    SNE("SNE", "🇯🇵", "Sony Corp ADR", true, 10.0, "MARKET", "#003791"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🇪🇺 EUROPE ADR STOCKS - Blue Chips
    // ═══════════════════════════════════════════════════════════════════════════
    SAP("SAP", "🇩🇪", "SAP SE", true, 10.0, "MARKET", "#0070C0"),
    NVO("NVO", "🇩🇰", "Novo Nordisk", true, 10.0, "MARKET", "#0066CC"),
    SHEL("SHEL", "🇬🇧", "Shell PLC", true, 10.0, "MARKET", "#FFD500"),
    BP("BP", "🇬🇧", "BP PLC", true, 10.0, "MARKET", "#009900"),
    UL("UL", "🇬🇧", "Unilever PLC", true, 10.0, "MARKET", "#1F36C7"),
    DEO("DEO", "🇬🇧", "Diageo PLC", true, 10.0, "MARKET", "#AA8B56"),
    GSK("GSK", "🇬🇧", "GSK PLC", true, 10.0, "MARKET", "#F36633"),
    AZN("AZN", "🇬🇧", "AstraZeneca", true, 10.0, "MARKET", "#830051"),
    BHP("BHP", "🇦🇺", "BHP Group", true, 10.0, "MARKET", "#F26722"),
    RIO("RIO", "🇦🇺", "Rio Tinto", true, 10.0, "MARKET", "#E4002B"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ⛏️ GOLD & SILVER MINERS - Precious Metal Exposure
    // ═══════════════════════════════════════════════════════════════════════════
    NEM("NEM", "⛏️", "Newmont Corp", true, 10.0, "MARKET", "#FFD700"),
    GOLD("GOLD", "⛏️", "Barrick Gold", true, 10.0, "MARKET", "#FFD700"),
    AEM("AEM", "⛏️", "Agnico Eagle", true, 10.0, "MARKET", "#CFB53B"),
    FNV("FNV", "⛏️", "Franco-Nevada", true, 10.0, "MARKET", "#D4AF37"),
    WPM("WPM", "⛏️", "Wheaton Precious", true, 10.0, "MARKET", "#C0C0C0"),
    KGC("KGC", "⛏️", "Kinross Gold", true, 10.0, "MARKET", "#B8860B"),
    AGI("AGI", "⛏️", "Alamos Gold", true, 10.0, "MARKET", "#DAA520"),
    EGO("EGO", "⛏️", "Eldorado Gold", true, 10.0, "MARKET", "#FFD700"),
    HL("HL", "⛏️", "Hecla Mining", true, 10.0, "MARKET", "#C0C0C0"),
    PAAS("PAAS", "⛏️", "Pan American Silver", true, 10.0, "MARKET", "#A8A9AD"),
    AG("AG", "⛏️", "First Majestic Silver", true, 10.0, "MARKET", "#C4CACE"),
    CDE("CDE", "⛏️", "Coeur Mining", true, 10.0, "MARKET", "#708090"),
    FSM("FSM", "⛏️", "Fortuna Silver", true, 10.0, "MARKET", "#B0C4DE"),
    MAG("MAG", "⛏️", "MAG Silver", true, 10.0, "MARKET", "#D3D3D3"),
    SVM("SVM", "⛏️", "Silvercorp Metals", true, 10.0, "MARKET", "#BEC0C2"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🏭 EMERGING JUNIOR MINERS - High Risk/High Reward
    // ═══════════════════════════════════════════════════════════════════════════
    BTG("BTG", "⛏️", "B2Gold Corp", true, 10.0, "MARKET", "#E5C100"),
    NGD("NGD", "⛏️", "New Gold Inc", true, 10.0, "MARKET", "#CC9900"),
    GATO("GATO", "⛏️", "Gatos Silver", true, 10.0, "MARKET", "#A9A9A9"),
    SILV("SILV", "⛏️", "SilverCrest Metals", true, 10.0, "MARKET", "#C0C0C0"),
    DRD("DRD", "⛏️", "DRDGOLD Ltd", true, 10.0, "MARKET", "#FFD700"),
    HMY("HMY", "⛏️", "Harmony Gold", true, 10.0, "MARKET", "#B8860B"),
    AU("AU", "⛏️", "AngloGold Ashanti", true, 10.0, "MARKET", "#DAA520"),
    SSRM("SSRM", "⛏️", "SSR Mining", true, 10.0, "MARKET", "#C0C0C0"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🎨 V5.8: SOFTWARE & DESIGN
    // ═══════════════════════════════════════════════════════════════════════════
    ADBE("ADBE", "🎨", "Adobe Inc.", true, 10.0, "MARKET", "#FF0000"),
    NOW("NOW", "☁️", "ServiceNow", true, 10.0, "MARKET", "#62D84E"),
    WDAY("WDAY", "☁️", "Workday Inc.", true, 10.0, "MARKET", "#0A7AFF"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 📱 V5.8: SOCIAL MEDIA & APPS
    // ═══════════════════════════════════════════════════════════════════════════
    SNAP("SNAP", "👻", "Snap Inc.", true, 10.0, "MARKET", "#FFFC00"),
    PINS("PINS", "📌", "Pinterest", true, 10.0, "MARKET", "#E60023"),
    RBLX("RBLX", "🎮", "Roblox Corp.", true, 10.0, "MARKET", "#E8192C"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🌎 V5.8: GLOBAL GROWTH - LatAm, SE Asia, India
    // ═══════════════════════════════════════════════════════════════════════════
    MELI("MELI", "🛒", "MercadoLibre", true, 10.0, "MARKET", "#FFE600"),
    SE("SE", "🌊", "Sea Limited", true, 10.0, "MARKET", "#E00000"),
    GRAB("GRAB", "🚗", "Grab Holdings", true, 10.0, "MARKET", "#01B14F"),
    INFY("INFY", "🇮🇳", "Infosys ADR", true, 10.0, "MARKET", "#007CC2"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🏛️ V5.8: ALTERNATIVE ASSET MANAGERS & EXCHANGES
    // ═══════════════════════════════════════════════════════════════════════════
    SPGI("SPGI", "📊", "S&P Global", true, 10.0, "MARKET", "#286EB4"),
    BX("BX", "🏦", "Blackstone", true, 10.0, "MARKET", "#000000"),
    KKR("KKR", "🏦", "KKR & Co", true, 10.0, "MARKET", "#1A1A1A"),
    CME("CME", "🔔", "CME Group", true, 10.0, "MARKET", "#003082"),
    IBKR("IBKR", "📈", "Interactive Brokers", true, 10.0, "MARKET", "#D6001C"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🧬 V5.8: ADDITIONAL BIOTECH
    // ═══════════════════════════════════════════════════════════════════════════
    AMGN("AMGN", "🧬", "Amgen Inc.", true, 10.0, "MARKET", "#0065B3"),
    ISRG("ISRG", "🤖", "Intuitive Surgical", true, 10.0, "MARKET", "#002B60"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🛸 V5.8: DEFENSE & AEROSPACE
    // ═══════════════════════════════════════════════════════════════════════════
    NOC("NOC", "🛡️", "Northrop Grumman", true, 10.0, "MARKET", "#003087"),
    GD("GD", "✈️", "General Dynamics", true, 10.0, "MARKET", "#003082"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 💳 V5.8: CONSUMER FINTECH
    // ═══════════════════════════════════════════════════════════════════════════
    AFRM("AFRM", "💳", "Affirm Holdings", true, 10.0, "MARKET", "#0FA0EA"),

    // ═══════════════════════════════════════════════════════════════════════════
    // 🛢️ COMMODITIES - Energy (24/7 trading via Pyth)
    // ═══════════════════════════════════════════════════════════════════════════
    BRENT("BRENT", "🛢️", "Brent Crude Oil", false, 15.0, "24/7", "#000000"),
    WTI("WTI", "🛢️", "WTI Crude Oil", false, 15.0, "24/7", "#1A1A1A"),
    NATGAS("NATGAS", "🔥", "Natural Gas", false, 15.0, "24/7", "#4169E1"),
    RBOB("RBOB", "⛽", "Gasoline RBOB", false, 15.0, "24/7", "#FF4500"),
    HEATING("HEATING", "🏠", "Heating Oil", false, 15.0, "24/7", "#8B0000"),
    
    // 🌾 COMMODITIES - Agricultural
    CORN("CORN", "🌽", "Corn", false, 15.0, "24/7", "#FFD700"),
    WHEAT("WHEAT", "🌾", "Wheat", false, 15.0, "24/7", "#DEB887"),
    SOYBEAN("SOYBEAN", "🫘", "Soybeans", false, 15.0, "24/7", "#228B22"),
    COFFEE("COFFEE", "☕", "Coffee", false, 15.0, "24/7", "#6F4E37"),
    COCOA("COCOA", "🍫", "Cocoa", false, 15.0, "24/7", "#7B3F00"),
    SUGAR("SUGAR", "🍬", "Sugar", false, 15.0, "24/7", "#FFFFFF"),
    COTTON("COTTON", "🧶", "Cotton", false, 15.0, "24/7", "#F5F5DC"),
    LUMBER("LUMBER", "🪵", "Lumber", false, 15.0, "24/7", "#8B4513"),
    OJ("OJ", "🍊", "Orange Juice", false, 15.0, "24/7", "#FFA500"),
    CATTLE("CATTLE", "🐄", "Live Cattle", false, 15.0, "24/7", "#8B4513"),
    HOGS("HOGS", "🐖", "Lean Hogs", false, 15.0, "24/7", "#FFB6C1"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 🥇 PRECIOUS METALS (24/7 trading via Pyth)
    // ═══════════════════════════════════════════════════════════════════════════
    XAU("XAU", "🥇", "Gold", false, 15.0, "24/7", "#FFD700"),
    XAG("XAG", "🥈", "Silver", false, 15.0, "24/7", "#C0C0C0"),
    XPT("XPT", "⚪", "Platinum", false, 15.0, "24/7", "#E5E4E2"),
    XPD("XPD", "💎", "Palladium", false, 15.0, "24/7", "#CED0DD"),
    
    // 🔩 INDUSTRIAL METALS
    XCU("XCU", "🔶", "Copper", false, 15.0, "24/7", "#B87333"),
    XAL("XAL", "🔷", "Aluminum", false, 15.0, "24/7", "#848789"),
    XNI("XNI", "⬜", "Nickel", false, 15.0, "24/7", "#727472"),
    XTI("XTI", "⚫", "Titanium", false, 15.0, "24/7", "#878681"),
    ZINC("ZINC", "🔘", "Zinc", false, 15.0, "24/7", "#7D7D7D"),
    LEAD("LEAD", "⚫", "Lead", false, 15.0, "24/7", "#2F4F4F"),
    TIN("TIN", "🪙", "Tin", false, 15.0, "24/7", "#D3D3D3"),
    IRON("IRON", "🔩", "Iron Ore", false, 15.0, "24/7", "#A52A2A"),
    COBALT("COBALT", "🔵", "Cobalt", false, 15.0, "24/7", "#0047AB"),
    LITHIUM("LITHIUM", "🔋", "Lithium", false, 15.0, "24/7", "#87CEEB"),
    URANIUM("URANIUM", "☢️", "Uranium", false, 15.0, "24/7", "#32CD32"),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 💱 FOREX - Major Pairs (24/5 trading)
    // ═══════════════════════════════════════════════════════════════════════════
    EURUSD("EURUSD", "🇪🇺", "EUR/USD", false, 50.0, "24/5", "#003399"),
    GBPUSD("GBPUSD", "🇬🇧", "GBP/USD", false, 50.0, "24/5", "#012169"),
    USDJPY("USDJPY", "🇯🇵", "USD/JPY", false, 50.0, "24/5", "#BC002D"),
    AUDUSD("AUDUSD", "🇦🇺", "AUD/USD", false, 50.0, "24/5", "#00008B"),
    USDCAD("USDCAD", "🇨🇦", "USD/CAD", false, 50.0, "24/5", "#FF0000"),
    USDCHF("USDCHF", "🇨🇭", "USD/CHF", false, 50.0, "24/5", "#D52B1E"),
    NZDUSD("NZDUSD", "🇳🇿", "NZD/USD", false, 50.0, "24/5", "#00247D"),
    
    // 💱 FOREX - Cross Pairs
    EURGBP("EURGBP", "🇪🇺", "EUR/GBP", false, 50.0, "24/5", "#003399"),
    EURJPY("EURJPY", "🇪🇺", "EUR/JPY", false, 50.0, "24/5", "#003399"),
    GBPJPY("GBPJPY", "🇬🇧", "GBP/JPY", false, 50.0, "24/5", "#012169"),
    AUDJPY("AUDJPY", "🇦🇺", "AUD/JPY", false, 50.0, "24/5", "#00008B"),
    CADJPY("CADJPY", "🇨🇦", "CAD/JPY", false, 50.0, "24/5", "#FF0000"),
    CHFJPY("CHFJPY", "🇨🇭", "CHF/JPY", false, 50.0, "24/5", "#D52B1E"),
    EURCHF("EURCHF", "🇪🇺", "EUR/CHF", false, 50.0, "24/5", "#003399"),
    GBPCHF("GBPCHF", "🇬🇧", "GBP/CHF", false, 50.0, "24/5", "#012169"),
    EURCAD("EURCAD", "🇪🇺", "EUR/CAD", false, 50.0, "24/5", "#003399"),
    EURAUD("EURAUD", "🇪🇺", "EUR/AUD", false, 50.0, "24/5", "#003399"),
    GBPAUD("GBPAUD", "🇬🇧", "GBP/AUD", false, 50.0, "24/5", "#012169"),
    AUDCAD("AUDCAD", "🇦🇺", "AUD/CAD", false, 50.0, "24/5", "#00008B"),
    AUDNZD("AUDNZD", "🇦🇺", "AUD/NZD", false, 50.0, "24/5", "#00008B"),
    NZDJPY("NZDJPY", "🇳🇿", "NZD/JPY", false, 50.0, "24/5", "#00247D"),
    
    // 💱 FOREX - Emerging Markets
    USDMXN("USDMXN", "🇲🇽", "USD/MXN", false, 30.0, "24/5", "#006847"),
    USDBRL("USDBRL", "🇧🇷", "USD/BRL", false, 30.0, "24/5", "#009C3B"),
    USDINR("USDINR", "🇮🇳", "USD/INR", false, 30.0, "24/5", "#FF9933"),
    USDCNY("USDCNY", "🇨🇳", "USD/CNY", false, 30.0, "24/5", "#DE2910"),
    USDZAR("USDZAR", "🇿🇦", "USD/ZAR", false, 30.0, "24/5", "#007749"),
    USDTRY("USDTRY", "🇹🇷", "USD/TRY", false, 30.0, "24/5", "#E30A17"),
    USDRUB("USDRUB", "🇷🇺", "USD/RUB", false, 30.0, "24/5", "#0039A6"),
    USDSGD("USDSGD", "🇸🇬", "USD/SGD", false, 30.0, "24/5", "#EF3340"),
    USDHKD("USDHKD", "🇭🇰", "USD/HKD", false, 30.0, "24/5", "#DE2910"),
    USDKRW("USDKRW", "🇰🇷", "USD/KRW", false, 30.0, "24/5", "#0047A0"),
    
    ;  // End of enum
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ASSET TYPE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    val isCrypto: Boolean get() = !isStock && tradingHours == "24/7" && !isCommodity && !isMetal
    // V5.8: True SOL Perps tab markets — only the tokens Jupiter Perps actually supports
    val isSolPerp: Boolean get() = isCrypto && symbol in listOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX", "DOT", "LINK",
        "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP", "APT", "SUI", "INJ", "JUP",
        "PEPE", "WIF", "BONK", "NEAR", "TIA", "PYTH", "RAY", "ORCA", "DRIFT",
        "WLD", "JTO", "W", "STRK", "TAO", "GMX", "DYDX", "ENA", "PENDLE"
    )
    val isCommodity: Boolean get() = symbol in listOf("BRENT", "WTI", "NATGAS", "RBOB", "HEATING", 
        "CORN", "WHEAT", "SOYBEAN", "COFFEE", "COCOA", "SUGAR", "COTTON", "LUMBER", "OJ", "CATTLE", "HOGS")
    val isMetal: Boolean get() = symbol in listOf("XAU", "XAG", "XPT", "XPD", "XCU", "XAL", "XNI", "XTI",
        "ZINC", "LEAD", "TIN", "IRON", "COBALT", "LITHIUM", "URANIUM")
    val isForex: Boolean get() = tradingHours == "24/5" && !isStock
    val isPreciousMetal: Boolean get() = symbol in listOf("XAU", "XAG", "XPT", "XPD")
    val isIndustrialMetal: Boolean get() = isMetal && !isPreciousMetal
    val isEnergyCommodity: Boolean get() = symbol in listOf("BRENT", "WTI", "NATGAS", "RBOB", "HEATING")
    val isAgriCommodity: Boolean get() = symbol in listOf("CORN", "WHEAT", "SOYBEAN", "COFFEE", "COCOA", "SUGAR", "COTTON", "LUMBER", "OJ", "CATTLE", "HOGS")
    
    // V5.7.7: FOREIGN MARKET HELPERS
    val isChinaStock: Boolean get() = symbol in listOf("BABA", "BIDU", "JD", "NIO", "XPEV", "LI", "PDD", "TCEHY", "BILI", "TME")
    val isJapanStock: Boolean get() = symbol in listOf("SONY", "TM", "NTDOY", "MUFG", "HMC", "SNE")
    val isEuropeStock: Boolean get() = symbol in listOf("SAP", "NVO", "SHEL", "BP", "UL", "DEO", "GSK", "AZN", "BHP", "RIO", "ASML")
    val isForeignStock: Boolean get() = isChinaStock || isJapanStock || isEuropeStock
    
    // V5.7.7: MINING SECTOR HELPERS
    val isGoldMiner: Boolean get() = symbol in listOf("NEM", "GOLD", "AEM", "FNV", "KGC", "AGI", "EGO", "BTG", "NGD", "DRD", "HMY", "AU")
    val isSilverMiner: Boolean get() = symbol in listOf("WPM", "HL", "PAAS", "AG", "CDE", "FSM", "MAG", "SVM", "GATO", "SILV", "SSRM")
    val isPreciousMetalMiner: Boolean get() = isGoldMiner || isSilverMiner
    val isJuniorMiner: Boolean get() = symbol in listOf("BTG", "NGD", "GATO", "SILV", "DRD", "HMY", "AU", "SSRM", "AGI", "EGO", "CDE", "FSM", "MAG", "SVM")
    
    // V5.7.7: SECTOR SCANNER HELPERS
    val isETF: Boolean get() = symbol in listOf("SPY", "QQQ", "DIA", "IWM", "VTI", "EEM", "EFA", "GLD", "SLV", "TLT", "XLF", "XLE", "XLK", "XLV")
    val isCannabis: Boolean get() = symbol in listOf("TLRY", "CGC", "ACB", "CRON", "SNDL")
    val isSemiconductor: Boolean get() = symbol in listOf("NVDA", "AMD", "INTC", "QCOM", "AVGO", "MU", "TSM", "ASML", "ARM", "MRVL")
    val isTech: Boolean get() = symbol in listOf("AAPL", "MSFT", "GOOGL", "AMZN", "META", "NFLX", "CRM", "ORCL", "PLTR", "SNOW", "SHOP", "SPOT", "ZM", "ROKU", "SQ", "TWLO")
    val isAI: Boolean get() = symbol in listOf("NVDA", "MSFT", "GOOGL", "AI", "PATH", "DDOG", "NET", "CRWD", "ZS", "MDB", "PLTR")
    val isEV: Boolean get() = symbol in listOf("TSLA", "RIVN", "LCID", "NIO", "XPEV", "LI", "F", "GM")
}

// ═══════════════════════════════════════════════════════════════════════════════
// POSITION STATUS
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsPositionStatus(val emoji: String, val displayName: String) {
    OPEN("🟢", "Open"),
    CLOSED("⚫", "Closed"),
    LIQUIDATED("💀", "Liquidated"),
    STOPPED("🛑", "Stopped"),
    TP_HIT("🎯", "Take Profit"),
    PARTIAL("⬛", "Partial"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// POSITION DATA - Core position tracking
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsPosition(
    val id: String,
    val market: PerpsMarket,
    val direction: PerpsDirection,
    val entryPrice: Double,
    var currentPrice: Double,
    val sizeSol: Double,
    val sizeUsd: Double,
    val leverage: Double,
    val marginUsd: Double,
    val liquidationPrice: Double,
    val entryTime: Long,
    val isPaper: Boolean,
    val riskTier: PerpsRiskTier,
    
    // Risk management
    var takeProfitPrice: Double? = null,
    var stopLossPrice: Double? = null,
    var trailingStopPct: Double? = null,
    var highestPrice: Double = 0.0,
    var lowestPrice: Double = Double.MAX_VALUE,
    
    // AI metadata
    val entryScore: Int = 0,
    val entryConfidence: Int = 0,
    val aiLeverage: Double = 1.0,
    val aiReasoning: String = "",
    
    // Tracking
    var status: PerpsPositionStatus = PerpsPositionStatus.OPEN,
    var partialCloseCount: Int = 0,
    var lastUpdateTime: Long = System.currentTimeMillis(),
) {
    fun getUnrealizedPnlPct(): Double {
        val raw = ((currentPrice - entryPrice) / entryPrice * 100) * direction.multiplier
        return raw * leverage
    }
    
    fun getUnrealizedPnlUsd(): Double {
        return marginUsd * (getUnrealizedPnlPct() / 100)
    }
    
    // V5.7.6: Aliases for MultiAssetActivity compatibility
    fun getPnlPercent(): Double = getUnrealizedPnlPct()
    fun getPnlSol(): Double = sizeSol * (getUnrealizedPnlPct() / 100)
    
    // V5.7.6b: Aliases for position card compatibility
    val size: Double get() = sizeSol
    val openTime: Long get() = entryTime
    val takeProfit: Double get() = takeProfitPrice ?: (entryPrice * if (direction == PerpsDirection.LONG) 1.05 else 0.95)
    val stopLoss: Double get() = stopLossPrice ?: (entryPrice * if (direction == PerpsDirection.LONG) 0.97 else 1.03)
    
    fun getDistanceToLiquidation(): Double {
        return when (direction) {
            PerpsDirection.LONG -> ((currentPrice - liquidationPrice) / currentPrice * 100)
            PerpsDirection.SHORT -> ((liquidationPrice - currentPrice) / currentPrice * 100)
        }
    }
    
    fun isNearLiquidation(thresholdPct: Double = 15.0): Boolean = getDistanceToLiquidation() < thresholdPct
    
    fun shouldTakeProfit(): Boolean {
        takeProfitPrice?.let { tp ->
            return when (direction) {
                PerpsDirection.LONG -> currentPrice >= tp
                PerpsDirection.SHORT -> currentPrice <= tp
            }
        }
        return false
    }
    
    fun shouldStopLoss(): Boolean {
        stopLossPrice?.let { sl ->
            return when (direction) {
                PerpsDirection.LONG -> currentPrice <= sl
                PerpsDirection.SHORT -> currentPrice >= sl
            }
        }
        return false
    }
    
    fun getHoldDurationMinutes(): Long = (System.currentTimeMillis() - entryTime) / 60_000
    
    fun getDisplayPnl(): String {
        val pnl = getUnrealizedPnlPct()
        return "${if (pnl >= 0) "+" else ""}${String.format("%.2f", pnl)}%"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MARKET DATA - Real-time price info
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsMarketData(
    val market: PerpsMarket,
    val price: Double,
    val indexPrice: Double,
    val markPrice: Double,
    val fundingRate: Double,
    val fundingRateAnnualized: Double,
    val nextFundingTime: Long,
    val openInterestLong: Double,
    val openInterestShort: Double,
    val volume24h: Double,
    val high24h: Double,
    val low24h: Double,
    val priceChange24hPct: Double,
    val lastUpdate: Long = System.currentTimeMillis(),
) {
    fun getLongShortRatio(): Double = if (openInterestShort > 0) openInterestLong / openInterestShort else 1.0
    fun isFundingFavorableLong(): Boolean = fundingRate < 0
    fun isFundingFavorableShort(): Boolean = fundingRate > 0
    fun isVolatile(): Boolean = kotlin.math.abs(priceChange24hPct) > 5.0
    fun getTrend(): String = when {
        priceChange24hPct > 3.0 -> "BULLISH"
        priceChange24hPct < -3.0 -> "BEARISH"
        else -> "NEUTRAL"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRADE SIGNAL - AI-generated trading signals
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsSignal(
    val market: PerpsMarket,
    val direction: PerpsDirection,
    val score: Int,
    val confidence: Int,
    val recommendedLeverage: Double,
    val recommendedSizePct: Double,
    val recommendedRiskTier: PerpsRiskTier,
    val takeProfitPct: Double,
    val stopLossPct: Double,
    val reasons: List<String>,
    val aiReasoning: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun shouldTrade(): Boolean = confidence >= 60 && score >= 50
    fun isHighConfidence(): Boolean = confidence >= 80
    fun getSignalStrength(): String = when {
        score >= 85 -> "STRONG"
        score >= 70 -> "MODERATE"
        score >= 50 -> "WEAK"
        else -> "NO_TRADE"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TRADE HISTORY - Completed trades
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsTrade(
    val id: String,
    val market: PerpsMarket,
    val direction: PerpsDirection,
    val side: String,                    // "OPEN" or "CLOSE"
    val entryPrice: Double,
    val exitPrice: Double,
    val sizeSol: Double,
    val leverage: Double,
    val pnlUsd: Double,
    val pnlPct: Double,
    val openTime: Long,
    val closeTime: Long,
    val closeReason: String,
    val isPaper: Boolean,
    val aiScore: Int,
    val aiConfidence: Int,
    val riskTier: PerpsRiskTier,
)

// ═══════════════════════════════════════════════════════════════════════════════
// LIVE READINESS - Track when paper trading is ready for live
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsLiveReadiness(
    val paperTrades: Int,
    val paperWinRate: Double,
    val paperPnlPct: Double,
    val averageLeverage: Double,
    val maxDrawdownPct: Double,
    val consecutiveLosses: Int,
    val disciplineScore: Int,        // How well it follows rules (0-100)
    val readinessScore: Int,         // Overall readiness (0-100)
    val phase: ReadinessPhase,
    val recommendation: String,
) {
    fun isReadyForLive(): Boolean = readinessScore >= 75 && paperTrades >= 50 && paperWinRate >= 45.0
    fun getProgressPct(): Int = (readinessScore).coerceIn(0, 100)
}

enum class ReadinessPhase(val emoji: String, val displayName: String, val color: String) {
    LEARNING("📚", "Learning", "#F59E0B"),
    PRACTICING("🏋️", "Practicing", "#3B82F6"),
    READY("✅", "Ready", "#22C55E"),
    CAUTION("⚠️", "Caution", "#EF4444"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// PERPS STATE - Overall system state
// ═══════════════════════════════════════════════════════════════════════════════

data class PerpsState(
    var isPaperMode: Boolean = true,
    var isEnabled: Boolean = false,
    var hasAcknowledgedRisk: Boolean = false,
    
    // Balances
    var paperBalanceSol: Double = 5.0,
    var liveBalanceSol: Double = 0.0,
    
    // Daily stats
    var dailyTrades: Int = 0,
    var dailyWins: Int = 0,
    var dailyLosses: Int = 0,
    var dailyPnlSol: Double = 0.0,
    var dailyPnlPct: Double = 0.0,
    
    // Lifetime stats
    var lifetimeTrades: Int = 0,
    var lifetimeWins: Int = 0,
    var lifetimeLosses: Int = 0,
    var lifetimePnlSol: Double = 0.0,
    var lifetimeBest: Double = 0.0,
    var lifetimeWorst: Double = 0.0,
    
    // Learning
    var learningProgress: Double = 0.0,  // 0.0 to 1.0
    var maxConsecutiveWins: Int = 0,
    var maxConsecutiveLosses: Int = 0,
    var currentStreak: Int = 0,          // Positive = wins, negative = losses
    
    // AI confidence
    var aiConfidence: Int = 50,
    var lastSignalTime: Long = 0,
)

// ═══════════════════════════════════════════════════════════════════════════════
// EXIT SIGNALS
// ═══════════════════════════════════════════════════════════════════════════════

enum class PerpsExitSignal(val emoji: String, val displayName: String) {
    HOLD("⏳", "Hold"),
    STOP_LOSS("🛑", "Stop Loss"),
    TAKE_PROFIT("🎯", "Take Profit"),
    TRAILING_STOP("📉", "Trailing Stop"),
    PARTIAL_TAKE("💰", "Partial Take"),
    LIQUIDATION_RISK("💀", "Liquidation Risk"),
    TIMEOUT("⏰", "Timeout"),
    MARKET_CLOSE("🔒", "Market Close"),
    AI_EXIT("🤖", "AI Exit"),
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER EXTENSIONS
// ═══════════════════════════════════════════════════════════════════════════════

fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
fun Double.formatUsd(): String = "$${String.format("%.2f", this)}"
fun Double.formatPct(): String = "${String.format("%.2f", this)}%"
fun Double.formatLeverage(): String = "${String.format("%.1f", this)}x"

