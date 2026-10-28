package com.lifecyclebot.network

/**
 * V5.0.8418 — the free LLM surfaces the council knows by name, with each one's published free-tier budget.
 *
 * Two kinds:
 *  - KEYLESS: answered with no key and no account by the provider's own design. Only [LLM7_BASE_8418] qualifies
 *    today beside the members already in the council (Pollinations legacy text, OVHcloud anonymous).
 *  - KEYED: the operator pastes ONE line into Settings -> extra LLM endpoints, `name|key` (or the 7276 long form
 *    `name|https://base/v1|key`), and the base URL, seed models and budget come from here. No key is ever stored in
 *    source: a preset is a URL and a number, the key is the operator's.
 *
 * rpm is the provider's free-tier requests per minute as published (or a conservative reading of it where the
 * published figure is per model or per day); it sizes that provider's own [CapacityBucket8418], nothing else.
 */
object LlmProviderPresets8418 {

    data class Preset8418(val name: String, val baseUrl: String, val rpm: Int, val maxInFlight: Int, val seedModels: List<String>)

    /** LLM7.io — OpenAI-compatible gateway, anonymous use without a token (~30 rpm/IP; a free email token raises it). */
    const val LLM7_BASE_8418 = "https://api.llm7.io/v1"

    private val PRESETS_8418: Map<String, Preset8418> = listOf(
        Preset8418("llm7", LLM7_BASE_8418, 30, 2, emptyList()),
        Preset8418("sambanova", "https://api.sambanova.ai/v1", 20, 2, listOf("Meta-Llama-3.3-70B-Instruct", "Meta-Llama-3.1-8B-Instruct")),
        Preset8418("together", "https://api.together.xyz/v1", 30, 2, listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo-Free")),
        Preset8418("github", "https://models.github.ai/inference", 10, 2, listOf("openai/gpt-4.1-mini", "meta/Llama-3.3-70B-Instruct")),
        Preset8418("nvidia", "https://integrate.api.nvidia.com/v1", 40, 2, listOf("meta/llama-3.3-70b-instruct")),
        Preset8418("chutes", "https://llm.chutes.ai/v1", 20, 2, emptyList()),
        Preset8418("huggingface", "https://router.huggingface.co/v1", 20, 2, emptyList()),
        Preset8418("deepinfra", "https://api.deepinfra.com/v1/openai", 30, 2, emptyList()),
    ).associateBy { it.name }

    fun preset8418(name: String): Preset8418? = PRESETS_8418[name.trim().lowercase()]

    data class Endpoint8418(val name: String, val baseUrl: String, val key: String, val seedModels: List<String>)

    /**
     * Pure: one Settings line -> an endpoint, or null when it names nothing usable.
     *   `name|https://base/v1|key`   the 7276 form; a known name still contributes its seed models
     *   `name||key` / `name|key`     a preset by name with the operator's key
     *   `name|https://base/v1`       keyless surface
     * Only https URLs are accepted.
     */
    fun parse8418(raw: String): Endpoint8418? {
        val parts = raw.trim().split('|').map { it.trim() }
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val name = parts[0].lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(24)
        if (name.isBlank()) return null
        val preset = PRESETS_8418[name]
        val second = parts.getOrNull(1).orEmpty()
        return when {
            second.startsWith("https://") ->
                Endpoint8418(name, second, parts.getOrNull(2).orEmpty(), preset?.seedModels ?: emptyList())
            preset != null && second.isBlank() ->
                Endpoint8418(name, preset.baseUrl, parts.getOrNull(2).orEmpty(), preset.seedModels)
            preset != null && parts.size == 2 && !second.contains("://") ->
                Endpoint8418(name, preset.baseUrl, second, preset.seedModels)
            else -> null
        }
    }
}
