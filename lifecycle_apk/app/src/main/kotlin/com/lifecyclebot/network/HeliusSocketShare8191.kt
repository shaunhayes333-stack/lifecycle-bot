package com.lifecyclebot.network

import okhttp3.WebSocket

/**
 * V5.0.8191 §ONE_HELIUS_SOCKET_PER_KEY.
 *
 * ChainLaunchFeed8179 opened a second standard WebSocket to the same Helius endpoint on the same key as
 * HeliusWebSocket's per-mint stream. Helius plans bound concurrent sockets per key, and a second upgrade also met the
 * host cool-down (FAIL_599_ProtocolException, 0 subscriptions in 5.0.8186). The program-level logsSubscribe streams now
 * ride HeliusWebSocket's open socket: HeliusWebSocket attaches the socket here when it opens and detaches it when it
 * dies; every frame it reads is first offered to the feed's [router] (a cheap id / subscription-id test), which claims
 * only the replies to its own requests and the notifications of its own subscriptions. The feed keeps its dedicated
 * socket only as the fallback for a session with no open HeliusWebSocket.
 */
object HeliusSocketShare8191 {
    @Volatile private var socket: WebSocket? = null
    /** Returns true when the frame was the feed's (consumed: HeliusWebSocket must not parse it). */
    @Volatile var router: ((String) -> Boolean)? = null
    /** Called when a new shared socket opens or the shared socket dies (the feed's subscriptions died with it). */
    @Volatile var onChange: ((attached: Boolean) -> Unit)? = null

    fun attach8191(ws: WebSocket) {
        socket = ws
        try { onChange?.invoke(true) } catch (_: Throwable) {}
    }

    fun detach8191(ws: WebSocket?) {
        if (ws == null || socket !== ws) return
        socket = null
        try { onChange?.invoke(false) } catch (_: Throwable) {}
    }

    fun attached8191(): Boolean = socket != null

    fun send8191(text: String): Boolean = try { socket?.send(text) == true } catch (_: Throwable) { false }

    /** HeliusWebSocket.onMessage: true when the feed claimed [text]. Never throws. */
    fun offer8191(text: String): Boolean = try { router?.invoke(text) == true } catch (_: Throwable) { false }

    private val ID_RE_8191 = Regex("\"id\"\\s*:\\s*(\\d{1,10})")
    private val SUB_RE_8191 = Regex("\"subscription\"\\s*:\\s*(\\d{1,10})")

    /** Pure: the numeric JSON-RPC "id" of a reply frame (no "method"), or null. Reads only the frame's first 200 chars. */
    fun replyId8191(text: String): Long? {
        val head = if (text.length > 200) text.substring(0, 200) else text
        if (head.contains("\"method\"")) return null
        return ID_RE_8191.find(head)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Pure: the server subscription id of a notification frame, or null. The method name leads the frame; the id closes
     * it (`"params":{"result":{...},"subscription":N}}`), so only the frame's head and tail are read, never the logs.
     */
    fun subscriptionId8191(text: String): Long? {
        val head = if (text.length > 120) text.substring(0, 120) else text
        if (!head.contains("Notification\"")) return null
        val tail = if (text.length > 120) text.substring(text.length - 120) else text
        return (SUB_RE_8191.find(tail) ?: SUB_RE_8191.find(head))?.groupValues?.get(1)?.toLongOrNull()
    }
}
