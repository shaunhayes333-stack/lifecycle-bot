package com.lifecyclebot.data

/**
 * V5.0.6693 — restore operator-owned provider defaults removed by V5.0.6637.
 *
 * The operator explicitly requires these provider credentials to ship with this
 * private build so a clean install / cleared encrypted preferences still boots
 * with working data + LLM providers. Values remain XOR-obfuscated in source and
 * are decoded lazily at runtime. User-entered encrypted Settings values still
 * win through ConfigStore's existing fallback-when-blank load path.
 *
 * Do not replace these defaults with blank constants as part of generic
 * hardening: doing so silently disconnects provider and LLM routes and violates
 * the application-level credential authority contract for this build.
 */
internal object DefaultKeys {
    // Solana RPC + token data
    private const val HELIUS_X     = "6d3f39683f6f6a62776f396f68776e623b6c77626d6c6b776d6263696869686e6368683f"
    private const val HELIUS_M     = 0x5A
    private const val BIRDEYE_X    = "0051030303510e565653000e03525354560201020e56560552050f5405010f06"
    private const val BIRDEYE_M    = 0x37
    private const val JUPITER_X    = "0e59555b555b0d5b415b550f0a41580f0d0941550d550f410e595d0d0809090a5855095c"
    private const val JUPITER_M    = 0x6C

    // LLM fallback chain
    private const val GROQ_X       = "faeef6c2a9d2d8f1d5e4a4a8f6aed3fcd5d8defecacac9cacadaf9e4ffaedbc4a5f8acfed4d4abe4cdfce9cddcf0f6ecaccbfbafdfeec9d5"
    private const val GROQ_M       = 0x9D
    private const val OPENROUTER_X = "b7afe9abb6e9b2f5e9f1f1f3f3fcf1a5a2f7a1a7fda5fca6f6a7fdf2f6fda0a2a2f3f3a1f7f5f6f1a6a5f7fcfda0f0a1f5a6fda1a2fca2a5f6f5f6f3fca1a5a6f0f0a7f0a0a5a2f3f2"
    private const val OPENROUTER_M = 0xC4
    private const val CEREBRAS_X   = "c2d2ca8ccacfc4c499c59298c59592c995d793d6c797d7c7cfcfd9ccd9d398cb9594c4cbd9c9d8c7979492c5d999cbd1ccd19494"
    private const val CEREBRAS_M   = 0xA1
    private const val MISTRAL_X    = "84f7dad6dfe081c9f6fcfbd7e6e1c581f8d6d7e6dadc83f9f4d0e98bdde6ead6"
    private const val MISTRAL_M    = 0xB3

    // Solana RPC fallback
    private const val ALCHEMY_X    = "8a90808586a2bf9d80b39e82a19ea4aab08188ffaf"
    private const val ALCHEMY_M    = 0xC7

    val HELIUS:     String by lazy { dec(HELIUS_X, HELIUS_M) }
    val BIRDEYE:    String by lazy { dec(BIRDEYE_X, BIRDEYE_M) }
    val JUPITER:    String by lazy { dec(JUPITER_X, JUPITER_M) }
    val GROQ:       String by lazy { dec(GROQ_X, GROQ_M) }
    val OPENROUTER: String by lazy { dec(OPENROUTER_X, OPENROUTER_M) }
    val CEREBRAS:   String by lazy { dec(CEREBRAS_X, CEREBRAS_M) }
    val MISTRAL:    String by lazy { dec(MISTRAL_X, MISTRAL_M) }
    val ALCHEMY:    String by lazy { dec(ALCHEMY_X, ALCHEMY_M) }

    private fun dec(hex: String, mask: Int): String {
        require(hex.length % 2 == 0) { "obfuscated provider key has odd hex length" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "obfuscated provider key contains non-hex data" }
            out[i / 2] = (((hi shl 4) or lo) xor mask).toByte()
            i += 2
        }
        return String(out, Charsets.UTF_8)
    }
}
