package com.lifecyclebot.network

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import com.lifecyclebot.validator.ValidatorFeed as Wire
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import java.io.InputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Processed discovery and confirmed evidence on separate Yellowstone subscriptions, with bounded demand and TLS credentials scoped to its configured host. */
internal object ValidatorFeed8506 {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "validator-8506").apply { isDaemon = true } }
    private var timer: ScheduledFuture<*>? = null
    private var channel: ManagedChannel? = null
    private var call: ClientCall<Wire.Request, Wire.Update>? = null
    private var preview: ClientCall<Wire.Request, Wire.Update>? = null
    private var endpoint = ""
    private var token = ""
    @Volatile private var retryAt = 0L
    private var failures = 0
    private var lastMessage = 0L
    private var refresh: ScheduledFuture<*>? = null
    private var selected = emptyList<String>()
    @Volatile private var processedFrames = 0L
    @Volatile private var confirmedFrames = 0L
    @Volatile private var malformedFrames = 0L
    @Volatile private var refusedStream = ""
    @Volatile private var refusalCount = 0L
    private var lastWarningAtMs = 0L
    fun statusLine(): String = "$state processedFrames=$processedFrames confirmedFrames=$confirmedFrames malformed=$malformedFrames " +
        "refusals=$refusalCount lastRefused=${refusedStream.ifBlank { "-" }} retryInSec=${((retryAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)}"
    @Volatile private var lastWarned8509 = ""
    @Volatile var state = "OFF"
        private set
    private val recent = LinkedHashMap<String, Long>()
    internal const val MINT_AUTHORITY = "TSLvdd1pWpHVjahSpsvCXUbgwsL3JAcvokwaKt1eokM"
    private fun <T : MessageLite> marshaller(parser: Parser<T>) = object : MethodDescriptor.Marshaller<T> {
        override fun stream(value: T): InputStream = value.toByteArray().inputStream()
        override fun parse(stream: InputStream): T = parser.parseFrom(stream)
    }
    private val method = MethodDescriptor.newBuilder<Wire.Request, Wire.Update>()
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING).setFullMethodName("geyser.Geyser/Subscribe")
        .setRequestMarshaller(marshaller(Wire.Request.parser())).setResponseMarshaller(marshaller(Wire.Update.parser())).build()

    fun start(url: String, key: String) { worker.execute {
        if (timer != null && url == endpoint && key == token) return@execute
        close()
        endpoint = url.trim(); token = key.trim()
        if (endpoint.isEmpty() || token.isEmpty()) {
            state = if (endpoint.isEmpty()) "NO_ENDPOINT" else "NO_TOKEN"
            if (endpoint.isNotEmpty()) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("VALIDATOR_TOKEN_MISSING_8509")
                com.lifecyclebot.engine.ErrorLogger.warn("ValidatorFeed8506", "stream=both state=NO_TOKEN; configure an entitled Yellowstone x-token")
            }
            return@execute
        }
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host == null || uri.userInfo != null ||
            uri.rawQuery != null || uri.fragment != null || uri.path !in setOf("", "/") || uri.port == 0 || uri.port > 65535) {
            state = "INVALID_ENDPOINT"; return@execute
        }
        timer = worker.scheduleWithFixedDelay({
            try { tick(uri) } catch (_: Exception) { disconnect("STREAM_ERROR") }
        }, 0, 2, TimeUnit.SECONDS)
    } }

    fun stop() { worker.execute { close(); endpoint = ""; token = "" } }
    private fun close() {
        timer?.cancel(false); timer = null
        refresh?.cancel(false); refresh = null
        disconnect("OFF"); retryAt = 0; failures = 0
        recent.clear(); ValidatorTx8506.reset()
    }
    private fun disconnect(reason: String, streamKind: String = "both") {
        val old = call; call = null
        old?.cancel("stream closed", null)
        val early = preview; preview = null; early?.cancel("stream closed", null)
        channel?.shutdownNow(); channel = null
        failures = (failures + 1).coerceAtMost(7)
        retryAt = System.currentTimeMillis() + (1_000L shl failures)
        state = reason
        if (reason in setOf("UNAUTHENTICATED", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED")) {
            retryAt = System.currentTimeMillis() + 15L * 60_000L
            refusedStream = streamKind
            refusalCount++
            // V5.0.8509 — an authorisation refusal is surfaced (once per state change), not only a state string.
            if (lastWarned8509 != "$streamKind:$reason") {
                lastWarned8509 = "$streamKind:$reason"
                // Provider descriptions and endpoint strings can carry credentials.
                try { com.lifecyclebot.engine.ErrorLogger.warn("ValidatorFeed8506", "gRPC validator feed refused: stream=$streamKind state=$reason; retry in 900s") } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("VALIDATOR_FEED_AUTH_REFUSED_8509_$reason") } catch (_: Throwable) {}
            }
        } else if (reason != "OFF" && System.currentTimeMillis() - lastWarningAtMs > 60_000L) {
            lastWarningAtMs = System.currentTimeMillis()
            try { com.lifecyclebot.engine.ErrorLogger.warn("ValidatorFeed8506", "stream=$streamKind state=$reason; retryInSec=${((retryAt - lastWarningAtMs).coerceAtLeast(0L) / 1000L)}") } catch (_: Throwable) {}
        }
    }

    private fun tick(uri: URI) {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > 180_000L }
        val heldAndNear = PumpSwapTape8307.validatorWatch8506()
        val fresh = recent.keys.reversed()
        val watch = (heldAndNear.take(8) + fresh.take(12) + heldAndNear + fresh).distinct().take(20)
        val active = call
        if (active != null) {
            if (now - lastMessage > 60_000L) disconnect("IDLE_RETRY")
            else if (selected != watch) { active.sendMessage(subscription(watch)); preview?.sendMessage(subscription(watch, true)); selected = watch }
            return
        }
        if (now < retryAt) return
        val ch = OkHttpChannelBuilder.forAddress(uri.host, if (uri.port < 0) 443 else uri.port)
            .useTransportSecurity().maxInboundMessageSize(2 * 1024 * 1024).build()
        channel = ch
        state = "CONNECTING"; lastMessage = now; selected = watch
        open(ch, uri, watch, false)
        open(ch, uri, watch, true)
    }

    private fun open(ch: ManagedChannel, uri: URI, watch: List<String>, processed: Boolean) {
        val stream = ch.newCall(method, CallOptions.DEFAULT.withExecutor(worker))
        if (processed) preview = stream else call = stream
        val headers = Metadata()
        if (token.isNotEmpty()) headers.put(Metadata.Key.of("x-token", Metadata.ASCII_STRING_MARSHALLER), token)
        fun active(): Boolean = (if (processed) preview else call) === stream
        stream.start(object : ClientCall.Listener<Wire.Update>() {
            override fun onMessage(msg: Wire.Update) {
                if (!active()) return
                lastMessage = System.currentTimeMillis(); failures = 0
                lastWarned8509 = ""
                if (msg.hasTransaction()) { if (processed) processedFrames++ else confirmedFrames++ }
                if (!processed) state = "RECEIVING"
                try {
                    if (msg.hasPing()) stream.sendMessage(Wire.Request.newBuilder().setPing(Wire.Ping.newBuilder().setId(1)).build())
                    else ValidatorTx8506.accept(msg, lastMessage, processed) { mint ->
                        val first = mint !in recent
                        recent.remove(mint); recent[mint] = lastMessage
                        if (first) com.lifecyclebot.engine.truth.MintSupply8418.wholeOrRequest8418(mint)
                        if (refresh == null) refresh = worker.schedule({
                            refresh = null
                            try { if (active()) tick(uri) } catch (_: RuntimeException) { disconnect("FILTER_RETRY") }
                        }, 150L, TimeUnit.MILLISECONDS)
                        if (recent.size > 64) recent.entries.iterator().apply { next(); remove() }
                    }
                } catch (_: RuntimeException) {
                    malformedFrames++
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("VALIDATOR_MALFORMED_TX_8506")
                } finally { if (active()) stream.request(1) }
            }
            override fun onClose(status: Status, trailers: Metadata) {
                if (active()) disconnect(status.code.name, if (processed) "processed" else "confirmed")
            }
        }, headers)
        stream.request(1); stream.sendMessage(subscription(watch, processed))
    }

    internal fun subscription(watch: List<String>, processed: Boolean = false): Wire.Request {
        fun filter(keys: List<String>) = Wire.Filter.newBuilder().setVote(false).setFailed(false).addAllAccountInclude(keys)
        val targets = ChainLaunchFeed8179.TARGETS_8179
        val request = Wire.Request.newBuilder().setCommitment(if (processed) 0 else 1)
        if (!processed) request.putBlocksMeta("clock", Wire.Empty.getDefaultInstance())
        request.putTransactions("mint_launch", filter(listOf(MINT_AUTHORITY) + targets.filter { it.mention != it.programId }.map { it.mention }).build())
        // Scope swap-heavy programs to transactions also invoking System (pool account allocation).
        request.putTransactions("pool_launch", filter(targets.filter { it.mention == it.programId }.map { it.programId })
            .addAccountRequired(SolanaSwapEvidence8506.SYSTEM).build())
        watch.chunked(10).forEachIndexed { i, keys -> request.putTransactions("tape_$i", filter(keys).build()) }
        return request.build()
    }
}
