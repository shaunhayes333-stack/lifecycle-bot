package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.484 — pump.fun WebSocket firehose via PumpPortal.
 *
 * Catches new pump.fun launches and migrations 5-30 seconds before
 * DexScreener / Birdeye see them — massive edge for the meme lane.
 *
 * Endpoint: wss://pumpportal.fun/api/data
 * Free subscriptions: subscribeNewToken + subscribeMigration
 * Keyed subscriptions: subscribeTokenTrade, subscribeAccountTrade — the
 * socket said so on 5.0.7281: "only available when connecting with an API
 * key funded with at least 0.02 SOL." (V5.0.7278 read them as free; that
 * was wrong, and it cost three builds of zero trade frames.) With a key in
 * BotConfig.pumpPortalApiKey the socket connects as ?api-key= and the held
 * curves are marked on every trade; without one, launches only.
 *
 * Usage:
 *   PumpFunWS.start(
 *     onNewToken = { mint, symbol, name, marketCapSol -> ... },
 *     onMigration = { mint -> ... },
 *   )
 *   PumpFunWS.stop()
 *
 * Auto-reconnects on disconnect with exponential backoff capped at 30s.
 * One persistent connection (per pump.fun rate limits — keep below 15
 * total connections per IP).
 */
object PumpFunWS {
    private const val TAG = "PumpFunWS"
    private const val URL = "wss://pumpportal.fun/api/data"

    private val running = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0L)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var onNewTokenCb: ((String, String, String, Double) -> Unit)? = null
    @Volatile private var onMigrationCb: ((String) -> Unit)? = null

    // V5.0.7278 §THE CURVE PRICES ITSELF ON EVERY TRADE.
    //
    // 5.0.7277: 31 of 32 open positions had no fresh mark. They were
    // bonding-curve launches the fast lane bought within seconds of the
    // create event; no aggregator lists them, the pump.fun frontend answered
    // 9% of calls, and the curve read from chain produced nothing. Yet the
    // same socket that announced each launch streams every buy and sell on
    // it, with the curve's virtual reserves in the payload — the spot price,
    // sub-second. V5.0.7284: 7278 called `subscribeTokenTrade` a free
    // subscription; the server's own reply on 5.0.7281 says it needs an API
    // key funded with 0.02 SOL. The header above is right now; 7278 was not.
    @Volatile private var onTradeCb: ((mint: String, priceSolPerToken: Double, marketCapSol: Double, isBuy: Boolean) -> Unit)? = null
    private val tradeSubscriptions7278 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val firstUntypedLogged7279 = AtomicBoolean(false)
    @Volatile private var lastUntypedFrame7280: String = ""
    private val untypedFrames7280 = AtomicLong(0L)
    // V5.0.7284 — the data key the socket connected with; blank = no trade stream.
    @Volatile private var apiKey7284: String = ""
    private val tradeSubscribeSkippedNoKey7284 = AtomicLong(0L)
    // V5.0.7485 — unsupported subscription demand is state, not an event every
    // supervisor tick. Track wanted mints while trade-stream capability is
    // unavailable so repeated syncs do not re-expand the same work/counters.
    private val unsupportedTradeDemand7485 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val unsupportedTradeDemandCoalesced7485 = AtomicLong(0L)
    private val tradeAuthDenied7432 = AtomicBoolean(false)
    // V5.0.8094 — owner: "it is funded". The denial latched for the whole process: a key funded AFTER PumpPortal's
    // "only available when ... funded with at least 0.02 SOL" refusal stayed locked out (8093: tradeStream=AUTH_DENIED,
    // subscribeSkippedNoKey=1014). The same key is re-probed every [AUTH_REPROBE_MS_8094].
    @Volatile private var tradeAuthDeniedAtMs8094 = 0L
    @Volatile private var connectedKeyed8094 = false
    internal const val AUTH_REPROBE_MS_8094 = 10L * 60_000L

    /** Pure (V5.0.8094): has a trade-stream auth denial at [deniedAtMs] aged out for a re-probe at [nowMs]? */
    internal fun authReprobeDue8094(denied: Boolean, deniedAtMs: Long, nowMs: Long, delayMs: Long = AUTH_REPROBE_MS_8094): Boolean =
        denied && deniedAtMs > 0L && nowMs - deniedAtMs >= delayMs

    // V5.0.8182 — a refusal no longer locks the stream for 10 minutes: re-check on a fresh connection after 1, 2, 4, 8,
    // then every 10 minutes; the first real trade frame resets the ladder.
    private val authDenials8182 = AtomicLong(0L)
    @Volatile private var tradeConfirmedAtMs8182 = 0L
    private val tradeFramesSinceConnect8182 = AtomicLong(0L)

    /** Pure: the wait before re-checking after the [denials]th consecutive refusal (1 → 60 s, doubling, capped at 10 min). */
    internal fun reprobeDelayMs8182(denials: Long): Long =
        (60_000L shl (denials - 1).coerceIn(0L, 4L).toInt()).coerceAtMost(AUTH_REPROBE_MS_8094)

    private fun maybeReprobeAuth8094(nowMs: Long = System.currentTimeMillis()) {
        refreshKey8181(nowMs)
        if (apiKey7284.isBlank() || !authReprobeDue8094(tradeAuthDenied7432.get(), tradeAuthDeniedAtMs8094, nowMs,
                reprobeDelayMs8182(authDenials8182.get()))) return
        tradeAuthDenied7432.set(false)
        tradeAuthDeniedAtMs8094 = 0L
        unsupportedTradeDemand7485.clear()
        authReprobes8181.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TOKEN_TRADE_AUTH_REPROBE_8094") } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "re-probing the token trade stream with the configured key")
        // V5.0.8181 — PumpPortal checks the key's linked-wallet balance when the socket CONNECTS; 8094 re-subscribed on the
        // same keyed socket, so a wallet funded after start stayed refused all session (5.0.8175: reconnects=0, AUTH_DENIED).
        // Every re-probe reconnects keyed.
        try { ws?.close(1000, "reprobe keyed 8181") } catch (_: Throwable) {}
    }

    // ── V5.0.8181 — the key is re-read from settings: start() read it once, so a key saved later never reached the socket ──
    @Volatile private var keySupplier8181: (() -> String)? = null
    @Volatile private var keyReadAtMs8181 = 0L
    private val authReprobes8181 = AtomicLong(0L)
    private val keyChanges8181 = AtomicLong(0L)
    private const val KEY_REREAD_MS_8181 = 60_000L

    fun setKeySupplier8181(supplier: () -> String) { keySupplier8181 = supplier }

    /** Pure: the settings key differs from the connected one (blank settings never erase a working key mid-session). */
    internal fun keyChanged8181(current: String, fromSettings: String): Boolean =
        fromSettings.isNotBlank() && fromSettings != current

    private fun refreshKey8181(nowMs: Long) {
        val supplier = keySupplier8181 ?: return
        if (nowMs - keyReadAtMs8181 < KEY_REREAD_MS_8181) return
        keyReadAtMs8181 = nowMs
        val fresh = try { supplier().trim() } catch (_: Throwable) { return }
        if (!keyChanged8181(apiKey7284, fresh)) return
        apiKey7284 = fresh
        keyChanges8181.incrementAndGet()
        tradeAuthDenied7432.set(false)
        tradeAuthDeniedAtMs8094 = 0L
        authDenials8182.set(0L)
        unsupportedTradeDemand7485.clear()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TOKEN_TRADE_KEY_RELOADED_8181") } catch (_: Throwable) {}
        ErrorLogger.info(TAG, "PumpPortal data key changed in settings — reconnecting keyed")
        try { ws?.close(1000, "key reloaded 8181") } catch (_: Throwable) {}
    }

    /** Pure: what the owner must know about the trade stream's key, without printing the key. */
    internal fun keyState8181(key: String, denied: Boolean, connectedKeyed: Boolean): String = when {
        key.isBlank() -> "NONE(enter the PumpPortal data key in Settings)"
        denied -> "SET(..${key.takeLast(4)}) connectedKeyed=$connectedKeyed ACTION=PumpPortal says the wallet LINKED to this key " +
            "(created with the key, not the trading wallet) is under 0.02 SOL; re-checked by a fresh keyed connection every 10m"
        else -> "SET(..${key.takeLast(4)}) connectedKeyed=$connectedKeyed"
    }
    private val createStreamObserved7432 = AtomicBoolean(false)
    private val migrationStreamObserved7432 = AtomicBoolean(false)
    private val tokenTradeStreamObserved7432 = AtomicBoolean(false)
    private val rpcFallbackObserved7432 = AtomicBoolean(false)
    fun tokenTradeAuthDenied7432(): Boolean = tradeAuthDenied7432.get()
    fun observeRpcFallback7432() { if (tradeAuthDenied7432.get()) rpcFallbackObserved7432.set(true) }
    fun tokenTradeStreamActive7432(): Boolean = running.get() && ws != null &&
        !tradeAuthDenied7432.get() && tokenTradeStreamObserved7432.get()
    /**
     * V5.0.8136 — true only while the keyed trade stream is demonstrably live AND [mint] is on it: only then is an
     * empty swap window an observed zero. AUTH_DENIED, no key, a dropped socket or an unsubscribed mint = unknown.
     */
    fun tradeStreamCoversMint8136(mint: String): Boolean =
        mint.isNotBlank() && tokenTradeStreamActive7432() && tradeSubscriptions7278.contains(mint)
    internal fun isTradeAuthRefusal7432(message: String): Boolean {
        val e = message.lowercase()
        return ((e.contains("subscribetokentrade") || e.contains("subscribeaccounttrade")) &&
            (e.contains("api key") || e.contains("funded") || e.contains("only available"))) ||
            e.contains("minimum balance not met")  // V5.0.8181 — the PumpSwap data refusal is the same funding check
    }
    // V5.0.8192 — PumpPortal meters trade data (0.01 SOL / 10k messages). 96 launches x 15 min streamed ~450-500k
    // messages a day (~$50). Fewer, shorter launch subscriptions, a per-launch frame cap and a daily budget below.
    private const val LIFECYCLE_MAX_7420 = 24
    private const val LIFECYCLE_TTL_MS_7420 = 6L * 60_000L
    private val lifecycleMints7420 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val subRequested7420 = AtomicLong(0L)
    private val subOk7420 = AtomicLong(0L)
    private val subFail7420 = AtomicLong(0L)
    private val eventBuy7420 = AtomicLong(0L)
    private val eventSell7420 = AtomicLong(0L)
    private val unsub7420 = AtomicLong(0L)

    private fun tradeStreamKeyed7284(): Boolean = apiKey7284.isNotBlank() && !tradeAuthDenied7432.get()
    fun lifecycleStreamCapable7420(): Boolean = tokenTradeStreamActive7432()
    fun lifecycleSubscribedMints7420(): Int = lifecycleMints7420.size
    fun lifecycleEvents7420(): Long = eventBuy7420.get() + eventSell7420.get()
    fun lifecycleSubscriptionFailures7420(): Long = subFail7420.get()

    // V5.0.7286 — 5.0.7284: keyed, 30 subscribe frames sent, 53 message
    // frames, one error frame, zero trade frames, and the only text kept was
    // the newest ("Unsubscribed."). The server's answer to the subscribe — the
    // acknowledgement or the refusal — had scrolled off. The last error frame
    // is kept on its own, and the last four DISTINCT untyped texts are kept,
    // so the report shows what the socket said to the subscription.
    @Volatile private var lastErrorFrame7286: String = ""
    private val recentUntyped7286 = java.util.ArrayDeque<String>()
    private const val RECENT_UNTYPED_KEEP_7286 = 4

    private fun rememberUntyped7286(text: String, isError: Boolean) {
        val t = text.take(160).replace('\n', ' ')
        if (isError) lastErrorFrame7286 = t
        synchronized(recentUntyped7286) {
            if (recentUntyped7286.contains(t)) return
            recentUntyped7286.addLast(t)
            while (recentUntyped7286.size > RECENT_UNTYPED_KEEP_7286) recentUntyped7286.removeFirst()
        }
    }

    /** V5.0.7280 — one line for the pipeline report: what the socket is doing. */
    fun status7280(): String {
        val recent = synchronized(recentUntyped7286) { recentUntyped7286.toList() }
        return "${costLine8192()} key8181=${keyState8181(apiKey7284, tradeAuthDenied7432.get(), connectedKeyed8094)} reprobes8181=${authReprobes8181.get()} keyReloads8181=${keyChanges8181.get()} " +
            "denials8182=${authDenials8182.get()} nextRecheckS8182=${if (tradeAuthDenied7432.get()) ((tradeAuthDeniedAtMs8094 + reprobeDelayMs8182(authDenials8182.get()) - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) else 0L} " +
            "tradeFramesThisConnection8182=${tradeFramesSinceConnect8182.get()} tradeConfirmed8182=${if (tradeConfirmedAtMs8182 > 0L) "YES" else "NO"} " +
            "running=${running.get()} socket=${if (ws != null) "open" else "none"} reconnects=${reconnectAttempt.get()} " +
            "tradeStream=${if (tradeAuthDenied7432.get()) "AUTH_DENIED" else if (tokenTradeStreamActive7432()) "OBSERVED" else if (tradeStreamKeyed7284()) "KEYED_UNVERIFIED" else "NO_KEY_LAUNCHES_ONLY"} " +
            "PUMP_LIFECYCLE_STREAM_CAPABLE=${lifecycleStreamCapable7420()} " +
            "CREATE_STREAM_AVAILABLE=${running.get() && ws != null && createStreamObserved7432.get()} " +
            "MIGRATION_STREAM_AVAILABLE=${running.get() && ws != null && migrationStreamObserved7432.get()} " +
            "TOKEN_TRADE_STREAM_AVAILABLE=${tokenTradeStreamActive7432()} ACCOUNT_TRADE_STREAM_AVAILABLE=false " +
            "PUMP_TOKEN_TRADE_FALLBACK_ACTIVE=${tradeAuthDenied7432.get() && rpcFallbackObserved7432.get()} " +
            "tradeSubscribedMints=${tradeSubscriptions7278.size} lifecycleMints=${lifecycleMints7420.size} " +
            "unsupportedDemand7485=${unsupportedTradeDemand7485.size} coalescedDemand7485=${unsupportedTradeDemandCoalesced7485.get()} " +
            "subscriptionFailures=${subFail7420.get()} subscribeSkippedNoKey=${tradeSubscribeSkippedNoKey7284.get()} " +
            "untypedFrames=${untypedFrames7280.get()} " +
            "lastUntyped=${lastUntypedFrame7280.ifBlank { "-" }} " +
            "lastError7286=${lastErrorFrame7286.ifBlank { "-" }} " +
            "recentDistinct7286=${recent.joinToString(" | ").ifBlank { "-" }}"
    }

    fun setOnTrade7278(cb: (mint: String, priceSolPerToken: Double, marketCapSol: Double, isBuy: Boolean) -> Unit) {
        onTradeCb = cb
    }

    /**
     * V5.0.7743 — the rest of the trade frame. 7278 kept only the price; the
     * trader wallet, SOL size, side and the seller's remaining balance were
     * dropped, so WhaleDetector's launch tape, the real-time candle builder and
     * the dev-sell exit (all built for exactly this data) never received any.
     * [soldFractionOfHolding] is tokenAmount / (tokenAmount + newTokenBalance)
     * on a sell, else 0.
     */
    @Volatile private var onTradeDetailCb7743: ((mint: String, wallet: String, solAmount: Double, isBuy: Boolean, soldFractionOfHolding: Double, vSolInCurve: Double) -> Unit)? = null

    fun setOnTradeDetail7743(cb: (mint: String, wallet: String, solAmount: Double, isBuy: Boolean, soldFractionOfHolding: Double, vSolInCurve: Double) -> Unit) {
        onTradeDetailCb7743 = cb
    }

    /**
     * V5.0.7744 — pump.fun curves start with 30 SOL of virtual reserves, so the
     * SOL a curve has actually raised is vSolInBondingCurve minus this.
     */
    const val PUMP_INITIAL_VIRTUAL_SOL_7744 = 30.0

    // ── V5.0.8192 — trade-stream cost control ──
    /** Daily trade-frame budget (0.01 SOL per 10k): 100k ~= 0.10 SOL/day. Held positions are always kept on the stream. */
    internal const val DAILY_FRAME_BUDGET_8192 = 100_000L
    /** A launch (not held) is unsubscribed after this many frames: early-launch evidence is complete; curve RPC carries on. */
    internal const val LAUNCH_FRAME_CAP_8192 = 250L
    private val heldMints8192 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val framesByMint8192 = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val framesToday8192 = AtomicLong(0L)
    @Volatile private var frameDay8192 = 0L
    private val launchSubsSkippedBudget8192 = AtomicLong(0L)
    private val launchCapUnsubs8192 = AtomicLong(0L)
    private val budgetUnsubs8192 = AtomicLong(0L)

    /** Pure: may a NEW launch be subscribed with [framesToday] already streamed (stop at 80% to leave room for holds)? */
    internal fun launchSubAllowed8192(framesToday: Long, budget: Long = DAILY_FRAME_BUDGET_8192): Boolean =
        framesToday < budget * 8 / 10

    /** Pure: should a non-held launch leave the stream after [mintFrames] frames, or because the day's budget is spent? */
    internal fun dropLaunch8192(held: Boolean, mintFrames: Long, framesToday: Long, budget: Long = DAILY_FRAME_BUDGET_8192): Boolean =
        !held && (mintFrames >= LAUNCH_FRAME_CAP_8192 || framesToday >= budget)

    /** Pure: estimated SOL cost of [frames] metered messages. */
    internal fun frameCostSol8192(frames: Long): Double = frames / 10_000.0 * 0.01

    private fun rollDay8192(nowMs: Long) {
        val day = nowMs / 86_400_000L
        if (day != frameDay8192) { frameDay8192 = day; framesToday8192.set(0L) }
    }

    private fun noteTradeFrame8192(mint: String) {
        val now = System.currentTimeMillis()
        rollDay8192(now)
        val today = framesToday8192.incrementAndGet()
        if (framesByMint8192.size > 2_000) framesByMint8192.keys.retainAll(tradeSubscriptions7278 + heldMints8192)
        val mintFrames = framesByMint8192.computeIfAbsent(mint) { AtomicLong(0L) }.incrementAndGet()
        val held = mint in heldMints8192
        if (!dropLaunch8192(held, mintFrames, today)) return
        if (today >= DAILY_FRAME_BUDGET_8192) {
            // Budget spent: everything but held positions leaves the stream until the UTC day rolls.
            val drop = (tradeSubscriptions7278 - heldMints8192).toList()
            if (drop.isEmpty()) return
            tradeSubscriptions7278.removeAll(drop.toSet()); lifecycleMints7420.keys.removeAll(drop.toSet())
            try { ws?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray(drop)).toString()) } catch (_: Throwable) {}
            budgetUnsubs8192.addAndGet(drop.size.toLong())
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_BUDGET_UNSUB_8192") } catch (_: Throwable) {}
            return
        }
        if (!tradeSubscriptions7278.remove(mint)) return
        lifecycleMints7420.remove(mint)
        try { ws?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray().put(mint)).toString()) } catch (_: Throwable) {}
        launchCapUnsubs8192.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_LAUNCH_CAP_UNSUB_8192") } catch (_: Throwable) {}
    }

    fun costLine8192(): String {
        rollDay8192(System.currentTimeMillis())
        val f = framesToday8192.get()
        return "cost8192[framesToday=$f estSolToday=${"%.4f".format(frameCostSol8192(f))} budget=$DAILY_FRAME_BUDGET_8192 " +
            "(${"%.2f".format(frameCostSol8192(DAILY_FRAME_BUDGET_8192))} SOL) held=${heldMints8192.size} launchCapUnsubs=${launchCapUnsubs8192.get()} " +
            "budgetUnsubs=${budgetUnsubs8192.get()} launchSubsSkippedBudget=${launchSubsSkippedBudget8192.get()} maxLaunches=$LIFECYCLE_MAX_7420 ttlMin=${LIFECYCLE_TTL_MS_7420 / 60_000L}]"
    }

    private fun registerFreshLifecycle7420(mint: String) {
        if (mint.isBlank()) return
        rollDay8192(System.currentTimeMillis())
        if (!launchSubAllowed8192(framesToday8192.get())) {
            launchSubsSkippedBudget8192.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_LAUNCH_SKIPPED_BUDGET_8192") } catch (_: Throwable) {}
            return
        }
        val now = System.currentTimeMillis()
        val expired = lifecycleMints7420.entries.filter { now - it.value > LIFECYCLE_TTL_MS_7420 }.map { it.key }
        if (expired.isNotEmpty()) {
            lifecycleMints7420.keys.removeAll(expired.toSet())
            tradeSubscriptions7278.removeAll(expired.toSet())
            if (tradeStreamKeyed7284()) ws?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray(expired)).toString())
            unsub7420.addAndGet(expired.size.toLong())
            try { repeat(expired.size) { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_UNSUBSCRIBED") } } catch (_: Throwable) {}
        }
        if (lifecycleMints7420.size >= LIFECYCLE_MAX_7420 && !lifecycleMints7420.containsKey(mint)) {
            val oldest = lifecycleMints7420.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                lifecycleMints7420.remove(oldest)
                tradeSubscriptions7278.remove(oldest)
                if (tradeStreamKeyed7284()) ws?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray().put(oldest)).toString())
                unsub7420.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_UNSUBSCRIBED") } catch (_: Throwable) {}
            }
        }
        lifecycleMints7420[mint] = now
        subRequested7420.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUB_REQUESTED") } catch (_: Throwable) {}
        if (!tradeStreamKeyed7284()) {
            tradeSubscribeSkippedNoKey7284.incrementAndGet()
            return
        }
        if (tradeSubscriptions7278.add(mint)) {
            val sent = ws?.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray().put(mint)).toString()) == true
            if (sent) {
                subOk7420.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUB_OK") } catch (_: Throwable) {}
            } else {
                subFail7420.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUB_FAIL") } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Keep the trade subscription equal to [mints]: subscribe the new ones,
     * unsubscribe the ones no longer held. Idempotent; safe every tick.
     */
    fun syncTradeSubscriptions7278(mints: Set<String>) {
        val wanted = mints.filter { it.isNotBlank() }.toSet()
        // V5.0.8192 — held positions are exempt from the launch cap and the daily budget.
        heldMints8192.retainAll(wanted); heldMints8192.addAll(wanted)
        maybeReprobeAuth8094()

        // V5.0.7485 — when token-trade capability is unavailable there is no
        // executable subscription set to diff against. Maintain the desired
        // unsupported set directly: count newly wanted mints once, remove
        // demand that is no longer held, and return before frame construction.
        if (!tradeStreamKeyed7284()) {
            var newlyWanted7485 = 0
            for (mint in wanted) {
                if (unsupportedTradeDemand7485.add(mint)) newlyWanted7485++
                else unsupportedTradeDemandCoalesced7485.incrementAndGet()
            }
            unsupportedTradeDemand7485.removeIf { it !in wanted }
            if (newlyWanted7485 > 0) {
                tradeSubscribeSkippedNoKey7284.addAndGet(newlyWanted7485.toLong())
                try {
                    repeat(newlyWanted7485) {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUBSCRIBE_SKIPPED_NO_KEY_7284")
                    }
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_UNSUPPORTED_DEMAND_NEW_7485")
                } catch (_: Throwable) {}
            }
            return
        }

        // Capability became available: the real subscription set takes over.
        unsupportedTradeDemand7485.clear()
        val add = wanted - tradeSubscriptions7278
        // V5.0.7743 — fresh-launch subscriptions (registerFreshLifecycle7420) are
        // not the held set and expire on their own 15-minute TTL. This diff used
        // to drop them on the next tick of the open-position loop (it passes held
        // curve mints only), which is why 5.0.7741 showed tradeSubscribedMints=3
        // against lifecycleMints=96 and every launch tape was empty.
        val drop = tradeSubscriptions7278 - wanted - lifecycleMints7420.keys
        if (add.isEmpty() && drop.isEmpty()) return
        val sock = ws
        if (add.isNotEmpty()) {
            tradeSubscriptions7278.addAll(add)
            val sent7279 = sock?.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray(add.toList())).toString()) == true
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUBSCRIBED_7278")
                // V5.0.7279 — 7278 counted sync calls (17) and could not say how
                // many mints were on the stream, nor whether the frame reached a
                // live socket. Mints and frames are counted separately now.
                repeat(add.size) { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUBSCRIBED_MINTS_7279") }
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    if (sent7279) "PUMP_TRADE_SUBSCRIBE_FRAME_SENT_7279" else "PUMP_TRADE_SUBSCRIBE_FRAME_QUEUED_NO_SOCKET_7279",
                )
            } catch (_: Throwable) {}
        }
        if (drop.isNotEmpty()) {
            tradeSubscriptions7278.removeAll(drop)
            sock?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray(drop.toList())).toString())
        }
    }

    fun start(
        onNewToken: (mint: String, symbol: String, name: String, marketCapSol: Double) -> Unit,
        onMigration: (mint: String) -> Unit,
        apiKey7284: String = "",
    ) {
        if (!running.compareAndSet(false, true)) {
            // V5.0.8182 — a restart with a different key used to be ignored while the socket ran; now it reconnects keyed.
            if (keyChanged8181(this.apiKey7284, apiKey7284.trim())) {
                this.apiKey7284 = apiKey7284.trim()
                keyChanges8181.incrementAndGet()
                tradeAuthDenied7432.set(false)
                tradeAuthDeniedAtMs8094 = 0L
                authDenials8182.set(0L)
                unsupportedTradeDemand7485.clear()
                ErrorLogger.info(TAG, "already running — key changed, reconnecting keyed")
                try { ws?.close(1000, "key changed 8182") } catch (_: Throwable) {}
            } else ErrorLogger.warn(TAG, "already running — start() ignored")
            return
        }
        onNewTokenCb = onNewToken
        onMigrationCb = onMigration
        if (this.apiKey7284 != apiKey7284.trim()) {
            tradeAuthDenied7432.set(false)
            rpcFallbackObserved7432.set(false)
            unsupportedTradeDemand7485.clear()
        }
        this.apiKey7284 = apiKey7284.trim()
        tokenTradeStreamObserved7432.set(false)
        createStreamObserved7432.set(false)
        migrationStreamObserved7432.set(false)
        ErrorLogger.info(TAG, "trade stream ${if (tradeStreamKeyed7284()) "KEYED" else "NO_KEY — launches only (Settings > PumpPortal data key)"}")
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        connect()
    }

    fun stop() {
        running.set(false)
        try { ws?.close(1000, "client stop") } catch (_: Exception) {}
        ws = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        client = null
        ErrorLogger.info(TAG, "stopped")
    }

    private fun connect() {
        if (!running.get()) return
        // V5.0.7284 — the key rides the connection URL, never a log line.
        val url7284 = if (tradeStreamKeyed7284()) "$URL?api-key=${java.net.URLEncoder.encode(apiKey7284, "UTF-8")}" else URL
        val req = Request.Builder().url(url7284).build()
        // V5.0.8182 — PumpPortal allows one connection: a socket being replaced is closed first, never left open beside it.
        ws?.let { old -> ws = null; try { old.close(1000, "replaced 8182") } catch (_: Throwable) {} }
        tradeFramesSinceConnect8182.set(0L)
        connectedKeyed8094 = tradeStreamKeyed7284()
        ErrorLogger.info(TAG, "🔌 connecting to $URL${if (tradeStreamKeyed7284()) " (keyed)" else ""} (attempt ${reconnectAttempt.get() + 1})")
        ws = client?.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            tokenTradeStreamObserved7432.set(false)
            ErrorLogger.info(TAG, "✅ connected — subscribing to subscribeNewToken + subscribeMigration (free)")
            webSocket.send(JSONObject().put("method", "subscribeNewToken").toString())
            webSocket.send(JSONObject().put("method", "subscribeMigration").toString())
            // V5.0.7278 — re-arm the held-mint trade stream after a reconnect.
            val held7278 = tradeSubscriptions7278.toList()
            if (held7278.isNotEmpty() && tradeStreamKeyed7284()) {
                webSocket.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray(held7278)).toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // V5.0.8136 — every frame is also the band watch's clock (throttled inside): a launch that crosses into the
            // $10k-$100k band on an executed-trade mark or the curve's reserves is admitted even with no trade stream.
            try { com.lifecyclebot.engine.truth.PumpBandEntry8118.maybeSweep8136() } catch (_: Throwable) {}
            try {
                val j = JSONObject(text)
                val txType = j.optString("txType", "")
                // A socket write is not a subscription acceptance. An auth/funding
                // refusal disables keyed methods for this credential state, while
                // free create/migration and the independent RPC curve ladder remain.
                val error7432 = listOf(j.optString("message"), j.optString("error"),
                    j.optString("errors")).joinToString(" ").lowercase()
                if (!tradeAuthDenied7432.get() && isTradeAuthRefusal7432(error7432)) {
                    tradeAuthDenied7432.set(true)
                    tradeAuthDeniedAtMs8094 = System.currentTimeMillis()
                    authDenials8182.incrementAndGet()
                    tokenTradeStreamObserved7432.set(false)
                    tradeSubscriptions7278.clear()
                    subFail7420.incrementAndGet()
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TOKEN_TRADE_STREAM_UNAVAILABLE_AUTH_7432")
                        ErrorLogger.warn(TAG, "token trade subscription unavailable for current key; RPC curve ladder remains independent")
                    } catch (_: Throwable) {}
                }
                // V5.0.7279 — every frame is counted by its own type, so a stream
                // that delivers no buy/sell frames is distinguishable from one
                // whose frames arrive under a name this parser does not read.
                // 5.0.7278: 17 subscriptions, 0 trade events, and nothing to say
                // which of the two it was.
                try {
                    val kind7279 = if (txType.isBlank()) {
                        if (j.has("message")) "message" else if (j.has("errors")) "error" else "untyped"
                    } else txType.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(16)
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_WS_FRAME_7279_$kind7279")
                    if (txType.isBlank() && firstUntypedLogged7279.compareAndSet(false, true)) {
                        ErrorLogger.info(TAG, "first untyped frame: ${text.take(220)}")
                    }
                    // V5.0.7280 — 5.0.7279: 85 `message` frames, 50 mints subscribed,
                    // zero trade frames, and the frame text never reached the
                    // snapshot. The last one is kept for the status line.
                    if (txType.isBlank()) {
                        lastUntypedFrame7280 = text.take(200).replace('\n', ' ')
                        untypedFrames7280.incrementAndGet()
                        rememberUntyped7286(text, isError = j.has("errors") || j.has("error"))
                    }
                } catch (_: Throwable) {}
                when {
                    // V5.0.7278 — a trade on a held curve is a mark.
                    txType == "buy" || txType == "sell" -> {
                        val mint = j.optString("mint", "")
                        if (mint.isBlank()) return
                        noteTradeFrame8192(mint)  // V5.0.8192 — metered: per-launch cap and daily budget
                        try { PumpCurveKeys7269.remember(mint, j.optString("bondingCurveKey", "")) } catch (_: Throwable) {}
                        val vSol = j.optDouble("vSolInBondingCurve", 0.0)
                        val vTok = j.optDouble("vTokensInBondingCurve", 0.0)
                        val mcapSol = j.optDouble("marketCapSol", 0.0)
                        if (!vSol.isFinite() || !vTok.isFinite() || vSol <= 0.0 || vTok <= 0.0) return
                        // V5.0.7951 — the curve's reserves, kept by mint even before a token row exists.
                        try { com.lifecyclebot.engine.truth.CurveReserves7951.noteFrame7951(mint, vSol, vTok) } catch (_: Throwable) {}
                        val priceSol = vSol / vTok
                        if (!priceSol.isFinite() || priceSol <= 0.0) return
                        if (!tradeAuthDenied7432.get()) tokenTradeStreamObserved7432.set(true)
                        // V5.0.8182 — an actual trade frame is the only proof the paid stream works.
                        tradeFramesSinceConnect8182.incrementAndGet()
                        if (tradeConfirmedAtMs8182 == 0L) tradeConfirmedAtMs8182 = System.currentTimeMillis()
                        authDenials8182.set(0L)
                        // V5.0.8136 — one swap is one swap: a repeated or re-sent frame (same signature, or the same
                        // signer, side, size and post-trade reserves) stops here, before any buyer tape or counter.
                        val swapKey8136 = com.lifecyclebot.engine.truth.LaunchEvidence8136.dedupKey8136(
                            mint, j.optString("signature", ""), j.optString("traderPublicKey", ""), txType == "buy",
                            j.optDouble("solAmount", 0.0), vSol, vTok,
                        )
                        if (!com.lifecyclebot.engine.truth.LaunchEvidence8136.noteSwap8136(mint, swapKey8136)) {
                            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_DUPLICATE_FRAME_8136") } catch (_: Throwable) {}
                            return
                        }
                        try {
                            if (tokenTradeStreamActive7432())
                                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TOKEN_TRADE_STREAM_ACTIVE_7432")
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_7278")
                            if (txType == "buy") {
                                eventBuy7420.incrementAndGet()
                                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_BUY")
                            } else {
                                eventSell7420.incrementAndGet()
                                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_SELL")
                            }
                        } catch (_: Throwable) {}
                        onTradeCb?.invoke(mint, priceSol, mcapSol, txType == "buy")
                        try {
                            val sol7743 = j.optDouble("solAmount", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                            val tok7743 = j.optDouble("tokenAmount", 0.0)
                            val left7743 = j.optDouble("newTokenBalance", -1.0)
                            val soldFrac7743 = if (txType == "sell" && tok7743.isFinite() && tok7743 > 0.0 && left7743.isFinite() && left7743 >= 0.0)
                                tok7743 / (tok7743 + left7743) else 0.0
                            onTradeDetailCb7743?.invoke(mint, j.optString("traderPublicKey", ""), sol7743, txType == "buy", soldFrac7743, vSol)
                            // V5.0.8100 — the executed-trade tape (buy pressure / change / shape for a coin no provider covers yet).
                            com.lifecyclebot.engine.truth.TradeTape8100.note8100(mint, priceSol, sol7743, txType == "buy")
                        } catch (_: Throwable) {}
                    }
                    txType == "create" || j.has("name") && j.has("symbol") && j.has("mint") -> {
                        val mint = j.optString("mint", "")
                        val symbol = j.optString("symbol", "?")
                        val name = j.optString("name", "?")
                        val marketCapSol = j.optDouble("marketCapSol", 0.0)
                        if (mint.isBlank()) return
                        // V5.0.7401 — creator identity is launch timing evidence.
                        // Persist it on the fastest create socket BEFORE any
                        // adaptive create throttle or downstream enrichment.
                        try {
                            com.lifecyclebot.engine.OperatorRegistry.set(
                                mint, j.optString("traderPublicKey", "")
                            )
                        } catch (_: Throwable) {}
                        // V5.0.7269 — the curve address rides in this payload; keep
                        // it so the mark fan-out can read the curve from chain state
                        // when no aggregator lists the mint. Recorded before the
                        // throttle so a held mint is never left without its key.
                        try {
                            PumpCurveKeys7269.remember(mint, j.optString("bondingCurveKey", ""))
                        } catch (_: Throwable) {}
                        // V5.0.4168 — PumpPortal WS adaptive throttle. Drops
                        // dust-mcap creates BEFORE they trigger the cascade
                        // (provider proofs, rugcheck, Birdeye/DexScreener
                        // enrichment) that drives the bulk of mobile-data
                        // burn. Threshold rises when watchlist saturates.
                        // V5.0.7279 — the create frame carries the curve's virtual
                        // reserves after the dev buy: the spot price at t=0, from
                        // the same field pair every trade frame carries. Delivered
                        // through the trade callback after intake has created the
                        // token row, so the first mark exists before the first
                        // evaluation instead of after the first aggregator poll.
                        // V5.0.7280 — and remembered as the launch price before the
                        // throttle, so a later ticket's multiple over it is known.
                        val vSol0 = j.optDouble("vSolInBondingCurve", 0.0)
                        val vTok0 = j.optDouble("vTokensInBondingCurve", 0.0)
                        val priceSol0 = if (vSol0.isFinite() && vTok0.isFinite() && vSol0 > 0.0 && vTok0 > 0.0) vSol0 / vTok0 else 0.0
                        try { com.lifecyclebot.engine.truth.AdmissionBirth8144.noteCreateFrame8144(mint) } catch (_: Throwable) {}  // V5.0.8144 — every frame's birth, before the throttle
                        try { com.lifecyclebot.engine.truth.LaunchCohort8173.noteCreateIntake8173(mint, priceSol0.isFinite() && priceSol0 > 0.0) } catch (_: Throwable) {}  // V5.0.8173 — no reserves in the frame: read the curve now
                        if (priceSol0.isFinite() && priceSol0 > 0.0) {
                            try { PumpCurveKeys7269.rememberCreate7280(mint, priceSol0, System.currentTimeMillis()) } catch (_: Throwable) {}
                            // V5.0.7951 — the create frame's reserves are the curve's first liquidity reading.
                            try { com.lifecyclebot.engine.truth.CurveReserves7951.noteFrame7951(mint, vSol0, vTok0) } catch (_: Throwable) {}
                            // V5.0.8136 — the create frame is a PRICE observation (the dev's seed buy is no crowd buyer).
                            try { com.lifecyclebot.engine.truth.LaunchEvidence8136.notePrice8136(mint) } catch (_: Throwable) {}
                        }
                        // V5.0.8118 — remembered for a later admission when the launch enters the proven $10k-$100k band.
                        try { com.lifecyclebot.engine.truth.PumpBandEntry8118.noteCreate8118(mint, symbol, name) } catch (_: Throwable) {}
                        if (!com.lifecyclebot.engine.PumpPortalThrottle.allowCreate(marketCapSol)) return
                        createStreamObserved7432.set(true)
                        registerFreshLifecycle7420(mint)
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_CREATE_STREAM_ACTIVE_7432") } catch (_: Throwable) {}
                        onNewTokenCb?.invoke(mint, symbol, name, marketCapSol)
                        if (priceSol0.isFinite() && priceSol0 > 0.0) {
                            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_CREATE_MARK_EMITTED_7279") } catch (_: Throwable) {}
                            // V5.0.8136 — a mark only: the trade callback's side flag is ignored by its reader and this
                            // emission never reaches the buyer tape (onTradeDetailCb7743 / TradeTape8100 get swaps only).
                            onTradeCb?.invoke(mint, priceSol0, marketCapSol, true)
                        }
                    }
                    txType == "migrate" || j.optString("event", "") == "migration" -> {
                        val mint = j.optString("mint", j.optString("address", ""))
                        if (mint.isBlank()) return
                        com.lifecyclebot.engine.PumpPortalThrottle.allowMigrate()
                        migrationStreamObserved7432.set(true)
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_MIGRATION_STREAM_ACTIVE_7432") } catch (_: Throwable) {}
                        onMigrationCb?.invoke(mint)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "parse error: ${e.message?.take(80)}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) { ws = null; tokenTradeStreamObserved7432.set(false) }
            ErrorLogger.info(TAG, "closing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val current8182 = ws === webSocket || ws == null
            if (ws === webSocket) { ws = null; tokenTradeStreamObserved7432.set(false) }
            ErrorLogger.warn(TAG, "❌ failure: ${t.message?.take(100)} — will reconnect")
            if (current8182) scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // V5.0.8182 — only the CURRENT socket's close reconnects; a replaced one closing must not open a second.
            val current8182 = ws === webSocket || ws == null
            if (ws === webSocket) { ws = null; tokenTradeStreamObserved7432.set(false) }
            ErrorLogger.info(TAG, "closed code=$code")
            if (running.get() && current8182) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = (1000L * (1 shl (attempt.coerceAtMost(5).toInt()))).coerceAtMost(30_000L)
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            connect()
        }.start()
    }
}
