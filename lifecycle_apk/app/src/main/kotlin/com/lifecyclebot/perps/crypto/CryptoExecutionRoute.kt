package com.lifecyclebot.perps.crypto

/**
 * V5.9.495z30 — CryptoUniverseTrader: route classification.
 *
 * Each crypto asset signal is resolved to exactly one of these routes.
 * The trader must NOT use the generic
 *   "no tx (mint missing, bridge fail, or insufficient SOL)"
 * failure anymore. Replace with a precise diagnostic so the operator
 * can see whether the issue is "no executor wired" vs "tx actually
 * failed".
 *
 * Spec items A–J (operator brief):
 *   A. Solana meme tokens go to MemeTrader.
 *   B. SPL/wrapped crypto with valid Jupiter route executes here.
 *   C. BTC w/o wrapped & no CEX  -> CEX_REQUIRED (NOT BUY_FAILED).
 *   D. XMR w/o route             -> CEX_REQUIRED / NO_ROUTE_AVAILABLE.
 *   E. PAXG w/ price but no exec -> WRAPPED_ROUTE_MISSING.
 *   F. AXS / RENDER / TNSR       -> precise route classification.
 *   G. Route-discovery failures do NOT consume live failure count.
 *   H. Markets/Yahoo/Commodities scans don't block this lane.
 *   I. SOL→USDC→target works when an executable route exists.
 *   J. Wallet lifecycle keys by mint/order-id, not ticker.
 */
enum class CryptoExecutionRoute {
    SOLANA_SPL_DIRECT,           // SOL/USDC -> Solana SPL representation
    JUPITER_ROUTABLE,            // Jupiter can route the token directly
    PUMPFUN_MEME,                // belongs to MemeTrader, NOT CryptoUniverseTrader
    RAYDIUM_ROUTABLE,
    METEORA_ROUTABLE,
    BRIDGED_WRAPPED_ASSET,       // wrapped BTC, wrapped ETH, tokenized gold, etc.
    CEX_REQUIRED,                // native BTC, native XMR, native TON, etc.
    BRIDGE_REQUIRED,             // cross-chain bridge route required
    PERP_ONLY,
    PAPER_ONLY,
    NO_ROUTE_AVAILABLE,
    ROUTE_DISABLED,
    INSUFFICIENT_SOL,
}

/** Precise diagnostic codes. These replace BUY_FAILED for route-discovery
 *  outcomes. Only true broadcast/order-execution failures should keep the
 *  legacy BUY_FAILED phase. */
object CryptoUniverseDiagCodes {
    const val ROUTE_SOLANA_SPL          = "CRYPTO_ROUTE_SOLANA_SPL"
    const val ROUTE_JUPITER             = "CRYPTO_ROUTE_JUPITER"
    const val ROUTE_BRIDGE_REQUIRED     = "CRYPTO_ROUTE_BRIDGE_REQUIRED"
    const val ROUTE_CEX_REQUIRED        = "CRYPTO_ROUTE_CEX_REQUIRED"
    const val ROUTE_NO_WRAPPED_ASSET    = "CRYPTO_ROUTE_NO_WRAPPED_ASSET"
    const val ROUTE_NO_EXECUTOR         = "CRYPTO_ROUTE_NO_EXECUTOR"
    const val ROUTE_INSUFFICIENT_SOL    = "CRYPTO_ROUTE_INSUFFICIENT_SOL"
    const val TX_BUILD_FAILED           = "CRYPTO_TX_BUILD_FAILED"
    const val BRIDGE_QUOTE_FAILED       = "CRYPTO_BRIDGE_QUOTE_FAILED"
    const val CEX_ORDER_FAILED          = "CRYPTO_CEX_ORDER_FAILED"
    const val ROUTE_DISCOVERY_FAILED    = "CRYPTO_ROUTE_DISCOVERY_FAILED"
    const val ROUTE_MEME_REJECT         = "CRYPTO_ROUTE_MEME_REJECT"
    const val ROUTE_PAPER_ONLY          = "CRYPTO_ROUTE_PAPER_ONLY"
}

/**
 * V5.0.7005 §REMOVE_ANYTHING_THAT_CANNOT_BE_TRADED_IN_REAL_LIFE.
 *
 * Operator, verbatim: "remove anything that cant be traded in real life!"
 *
 * THE EVIDENCE. V5.0.7003 snapshot:
 *
 *     CRYPTO_ALT  candidate=148 submit=74 fdgAllow=74 fdgBlock=0
 *                 sized=74 intent=74 dispatch=50 open=50
 *     paper-only unavailable live route = 164
 *     live-routable candidates          = 58
 *     CRYPTO_LEV  n=33  EV=-7.80%/trade  PnL=-2.7313 SOL   <- largest loser
 *
 * fdgBlock=0. Not one cross-asset candidate was ever refused. Meanwhile the
 * app's own registry had already counted 164 candidates as having no live
 * route — it knew, wrote it down, and opened them anyway. The positions that
 * follow are `base|0x…`, `eth|0x…`, `polygon_pos|…`, `perps:…` and a symbol
 * literally named `unresolved`. This bot holds a Solana wallet and routes
 * through Jupiter. It cannot swap on Base or Ethereum, it has no CEX account,
 * no bridge is authorized, and the perps venue is a sandbox
 * (perpsSandbox enabled=true, txSubmitted=0). None of it is real.
 *
 * WHY IT IS WORTH REMOVING RATHER THAN IGNORING. The operator's doctrine for
 * this system is that paper exists to seed live: "paper is meant to seed the
 * live trading engine only with things that can transfer and be used."
 * A paper fill on an unroutable asset breaks that in three ways at once —
 * it consumes a position slot against the cap, it consumes shared paper cash
 * that a routable candidate could have used, and every learner trains on an
 * outcome that can never occur with real money. CRYPTO_LEV being the single
 * largest loser while holding assets it could never have bought is that cost
 * made explicit.
 *
 * The distinction was always in the type system — PAPER_ONLY, CEX_REQUIRED,
 * BRIDGE_REQUIRED, PERP_ONLY, NO_ROUTE_AVAILABLE are named, distinct values.
 * Nothing consulted them at the open. This is the predicate that does.
 *
 * V5.0.7006 — BRIDGE_REQUIRED IS NOW TRADEABLE, by operator decision.
 *
 * 7005 refused it and said why: "the cross-chain bridge is an open operator
 * decision, not a shipped capability". The operator has now made that
 * decision — "turn on the cross chain bridge dude!!! we have a multi network
 * capable wallet we need the bridge!" — so the premise no longer holds and
 * the refusal goes with it.
 *
 * This is not a stub being switched on blind. CryptoUniverseExecutor already
 * branches on `route == BRIDGE_REQUIRED && resolution.executable`, and the
 * resolver only stamps BRIDGE_REQUIRED as executable when an adapter exists
 * for that chain — so an asset with no usable bridge adapter still falls
 * through to NO_ROUTE_AVAILABLE and stays refused by this same predicate.
 * What changes is that a bridged asset WITH a working adapter is now allowed
 * to open instead of being dropped at the gate.
 *
 * WORTH THE OPERATOR KNOWING: bridging moves real funds across chains, which
 * is slower and costlier than a same-chain swap and adds a failure mode a
 * Jupiter swap does not have — funds in flight between two chains. The exit
 * path inherits that too: a bridged position cannot be closed as fast as a
 * Solana one. Refusals for every other unroutable class are unchanged.
 */
/**
 * V5.0.7157 §PAPER_ONLY MEANS SIMULATE IT.
 *
 * Operator: "there's a cross chain bridge. its paper it should be
 * simulating the trade." Both halves are correct and this predicate was
 * wrong on the second.
 *
 * CryptoUniverseRouteResolver:128 returns PAPER_ONLY as its fallback — a
 * route whose entire meaning is "no live route proven; this is simulatable
 * but not live-tradeable". CryptoBridgeAdapter.buySolToEvm says the same
 * thing in its own rejection text: "paper-only/unavailable". Every layer
 * beneath this predicate is saying SIMULATE, and 7005 grouped PAPER_ONLY
 * with CEX_REQUIRED and NO_ROUTE_AVAILABLE under "needs something this app
 * does not have" and refused it — in paper mode, where nothing is needed
 * because nothing is bought.
 *
 * That produced the operator's "crypto trader is making 0 trades":
 * CRYPTO_ENTRY_REFUSED_NOT_REAL_TRADEABLE_7005 = 92 against candidate=16
 * submit=8 open=0, and a crypto lane that has learned nothing because it is
 * not allowed to practise.
 *
 * 7005's actual mandate was "remove anything that cant be traded in real
 * life" — a statement about REAL LIFE, i.e. about spending money. It is
 * preserved exactly: PAPER_ONLY is still refused in live, every other
 * unroutable class is still refused in both modes, and the bridge stays
 * fail-closed. What changes is that the simulator is allowed to simulate.
 *
 * ON THE BRIDGE, PLAINLY: CryptoBridgeAdapter is real deBridge DLN code, but
 * FULL_ROUND_TRIP_IMPLEMENTED is false because the destination signer, the
 * ERC-20 approval, the sell-back submission, the receipt proof and the
 * canonical close coordinator are not wired end to end. So LIVE bridging is
 * not available, and 7006's decision to allow BRIDGE_REQUIRED could never
 * take effect anyway: the resolver gates that branch on
 * cryptoUniverseAllowBridgeAdapters, which is declared false and is never
 * assigned true anywhere in the tree. Those assets fall through to
 * PAPER_ONLY, so this change is what lets them be simulated. I am NOT
 * flipping that flag — it would authorise real cross-chain money movement
 * through an executor that returns ROUND_TRIP_EXECUTOR_INCOMPLETE.
 */
fun CryptoExecutionRoute.isRealTradeable7005(): Boolean = when (this) {
    // V5.0.7182 §PAPER_ONLY_IS_THE_WHOLE_THING_7005_WAS_BUILT_TO_REFUSE.
    //
    // V5.0.7157 made this `paperMode7157` — "in paper that is a green light
    // by definition" — which reverted 7005 for the only mode this bot
    // actually runs in, and reopened every hole the header above describes.
    //
    // The header's own argument is the refutation. A paper fill on an
    // unroutable asset consumes a position slot against the cap, consumes
    // shared paper cash a routable candidate could have used, and trains
    // every learner on an outcome that can never occur with real money. None
    // of those three costs is smaller in paper — they are ONLY paid in paper.
    // And the operator's doctrine quoted above is explicit: "paper is meant
    // to seed the live trading engine only with things that can transfer and
    // be used."
    //
    // The damage measured on 5.0.7176, twenty-six minutes:
    //
    //   paper-only unavailable live route = 608   live-routable = 40
    //   CRYPTO_ALT candidate=44 submit=22 fdgAllow=22 fdgBlock=0 open=22
    //   CRYPTO_LEV n=18 EV=+51.09%/trade PnL=+5.6738 SOL
    //
    // That +5.67 was 83% of the session's entire reported profit, booked on
    // `base|0x…`, `eth|0x…`, `polygon_pos|…` and a symbol named `unresolved`,
    // with qty=1.000 sentinels (CanonicalPositionAuthority6441:1079) whose
    // percentages are a ratio of two junk prices. The same lane measured
    // -7.80%/trade at 7003 and +7.06% at 6915. An expectancy that swings 59
    // points across builds with no strategy change is measuring a mark bug,
    // not an edge — and it is the number the operator would most reasonably
    // have scaled up.
    //
    // Refused in BOTH modes now, so the parameter is gone rather than left
    // unused. The 40 genuinely routable candidates below are unaffected;
    // this removes the 608 that cannot be filled with real money.
    CryptoExecutionRoute.PAPER_ONLY -> false

    // Reachable right now with a Solana wallet routing through Jupiter.
    CryptoExecutionRoute.SOLANA_SPL_DIRECT,
    CryptoExecutionRoute.JUPITER_ROUTABLE,
    CryptoExecutionRoute.RAYDIUM_ROUTABLE,
    CryptoExecutionRoute.METEORA_ROUTABLE,
    CryptoExecutionRoute.PUMPFUN_MEME,
    // A wrapped asset lives on Solana as an SPL token — genuinely swappable.
    CryptoExecutionRoute.BRIDGED_WRAPPED_ASSET,
    // V5.0.7006 — operator turned the bridge on. The wallet is multi-network
    // capable and CryptoUniverseExecutor already routes this case; the
    // resolver only marks it executable when a chain adapter exists, so an
    // asset with no usable bridge still lands in NO_ROUTE_AVAILABLE below.
    CryptoExecutionRoute.BRIDGE_REQUIRED -> true

    // Needs something this app does not have: an exchange account, a real
    // perps venue, or any route at all.
    CryptoExecutionRoute.CEX_REQUIRED,
    CryptoExecutionRoute.PERP_ONLY,
    // PAPER_ONLY moved to the mode-aware branch above (V5.0.7157).
    CryptoExecutionRoute.NO_ROUTE_AVAILABLE,
    CryptoExecutionRoute.ROUTE_DISABLED,
    // Not a permanent property of the asset, but the order still cannot be
    // placed, so it must not become a paper fill either.
    CryptoExecutionRoute.INSUFFICIENT_SOL -> false
}
