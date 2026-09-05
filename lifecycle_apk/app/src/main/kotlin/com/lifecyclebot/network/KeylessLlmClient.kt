package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6672 — Keyless free-tier LLM fallback chain.
 *
 * Operator directive: "LLM is gone... add new free LLM providers with backups
 * — outside Emergent preferably. I want free keyless providers."
 *
 * This client tries a sequence of public, no-authentication LLM endpoints
 * and returns the first successful text response. Every call is fail-open:
 * if every provider is offline or rate-limits, `runChat(...)` returns null
 * and the caller falls back to its own default (usually "PROCEED"). No
 * silent trade blocking on LLM downtime.
 *
 * Providers (in priority order):
 *   1. Pollinations.ai — POST https://text.pollinations.ai/openai
 *      (OpenAI-compatible; anonymous per-IP allowed; each Android device
 *      has its own IP so per-IP throttling is a non-issue in practice).
 *   2. DuckDuckGo AI Chat — POST https://duckduckgo.com/duckchat/v1/chat
 *      (Uses a lightweight X-Vqd-4 handshake; anonymous.)
 *   3. Operator-supplied keys (Groq / OpenRouter / Anthropic) if any exist
 *      in BotConfig. This layer is added so a paid key, when configured,
 *      strictly upgrades reliability without ever being *required*.
 *
 * Design constraints:
 *  - No third-party SDK dependency (raw OkHttp + JSON only).
 *  - Rotating provider index so we don't hammer one endpoint on every call.
 *  - Aggressive per-provider timeouts (5s connect / 10s read / 12s call)
 *    so a single dead provider never stalls the hot loop.
 */
object KeylessLlmClient {
    private const val TAG = "KeylessLlmClient"

    private val httpClient: OkHttpClient by lazy {
        SharedHttpClient.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    // Rotating start index across providers so we spread load.
    private val startIdx = AtomicInteger(0)

    // Provider health decay: when a provider errors we skip it for
    // COOLDOWN_MS to avoid re-hitting a dead endpoint on every call.
    private const val COOLDOWN_MS = 60_000L
    private val cooldownUntil = mutableMapOf<String, Long>()

    // Optional operator keys (fed in by EmergentLlmClient/BotService).
    @Volatile private var operatorGroqKey: String = ""
    @Volatile private var operatorOpenRouterKey: String = ""
    @Volatile private var operatorAnthropicKey: String = ""

    fun setOperatorKeys(groq: String = "", openRouter: String = "", anthropic: String = "") {
        operatorGroqKey = groq.trim()
        operatorOpenRouterKey = openRouter.trim()
        operatorAnthropicKey = anthropic.trim()
    }

    /**
     * Single-turn chat. Returns null on total failure (caller decides fallback).
     */
    fun runChat(system: String, user: String, maxTokens: Int = 256): String? {
        val providers = buildProviderList()
        if (providers.isEmpty()) return null

        val n = providers.size
        val start = startIdx.getAndIncrement() % n
        val now = System.currentTimeMillis()

        for (offset in 0 until n) {
            val p = providers[(start + offset) % n]
            val until = cooldownUntil[p.name] ?: 0L
            if (until > now) continue
            try {
                val text = p.call(system, user, maxTokens)
                if (!text.isNullOrBlank()) {
                    return text
                }
                // Empty response → soft cooldown
                cooldownUntil[p.name] = now + 15_000L
            } catch (e: Exception) {
                cooldownUntil[p.name] = now + COOLDOWN_MS
                ErrorLogger.debug(TAG, "provider=${p.name} err=${e.message?.take(120)}")
            }
        }
        return null
    }

    private data class Provider(val name: String, val call: (String, String, Int) -> String?)

    private fun buildProviderList(): List<Provider> {
        val list = mutableListOf<Provider>()

        // 1. Pollinations.ai — always available, no key
        list.add(Provider("pollinations") { sys, usr, mt -> callPollinations(sys, usr, mt) })

        // 2. DuckDuckGo AI Chat — always available, no key
        list.add(Provider("duckduckgo")   { sys, usr, mt -> callDuckDuckGo(sys, usr, mt) })

        // 3. Operator-supplied paid keys (if any) — strict upgrade
        if (operatorGroqKey.isNotBlank()) {
            list.add(Provider("groq")     { sys, usr, mt -> callGroq(sys, usr, mt) })
        }
        if (operatorOpenRouterKey.isNotBlank()) {
            list.add(Provider("openrouter") { sys, usr, mt -> callOpenRouter(sys, usr, mt) })
        }
        if (operatorAnthropicKey.isNotBlank()) {
            list.add(Provider("anthropic") { sys, usr, mt -> callAnthropic(sys, usr, mt) })
        }
        return list
    }

    // ── Provider 1: Pollinations (keyless, OpenAI-compat) ──────────────────
    private fun callPollinations(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "openai-fast")
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            // Referrer identifies this app to Pollinations for prioritization.
            put("referrer", "aate-lifecycle-bot")
        }
        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .header("Content-Type", "application/json")
            .header("User-Agent", "AATE-LifecycleBot/5.0.6672")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            val choices = j.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val msg = first.optJSONObject("message") ?: return null
            return msg.optString("content", "").trim().ifBlank { null }
        }
    }

    // ── Provider 2: DuckDuckGo AI Chat (keyless, X-Vqd-4 handshake) ────────
    @Volatile private var ddgVqd: String = "4-1"
    private fun refreshDdgVqd(): String {
        return try {
            val req = Request.Builder()
                .url("https://duckduckgo.com/duckchat/v1/status")
                .header("x-vqd-accept", "1")
                .header("User-Agent", "Mozilla/5.0 (AATE)")
                .get().build()
            httpClient.newCall(req).execute().use { resp ->
                resp.header("x-vqd-4")?.also { ddgVqd = it } ?: ddgVqd
            }
        } catch (_: Exception) { ddgVqd }
    }
    private fun callDuckDuckGo(system: String, user: String, @Suppress("UNUSED_PARAMETER") maxTokens: Int): String? {
        val vqd = if (ddgVqd == "4-1") refreshDdgVqd() else ddgVqd
        val combined = if (system.isBlank()) user else "$system\n\n$user"
        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", combined)))
        }
        val req = Request.Builder()
            .url("https://duckduckgo.com/duckchat/v1/chat")
            .header("x-vqd-4", vqd)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (AATE)")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            // DDG rotates its vqd — capture the new one for next call.
            resp.header("x-vqd-4")?.let { ddgVqd = it }
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            // DDG streams SSE lines; when non-streaming it returns concatenated JSON.
            val sb = StringBuilder()
            for (line in body.lineSequence()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val jsonStr = trimmed.removePrefix("data:").trim()
                if (jsonStr == "[DONE]" || jsonStr.isEmpty()) continue
                try {
                    val j = JSONObject(jsonStr)
                    val msg = j.optString("message", "")
                    if (msg.isNotEmpty()) sb.append(msg)
                } catch (_: Exception) { /* skip malformed line */ }
            }
            return sb.toString().trim().ifBlank { null }
        }
    }

    // ── Provider 3: Groq (operator key) ────────────────────────────────────
    private fun callGroq(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $operatorGroqKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
        }
    }

    // ── Provider 4: OpenRouter (operator key) ──────────────────────────────
    private fun callOpenRouter(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "meta-llama/llama-3.3-70b-instruct:free")
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $operatorOpenRouterKey")
            .header("HTTP-Referer", "https://github.com/shaunhayes333-stack/lifecycle-bot")
            .header("X-Title", "AATE Lifecycle Bot")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
        }
    }

    // ── Provider 5: Anthropic (operator key) ───────────────────────────────
    private fun callAnthropic(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "claude-sonnet-4-5-20250929")
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", operatorAnthropicKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("content")?.optJSONObject(0)
                ?.optString("text", "")?.trim()?.ifBlank { null }
        }
    }
}
