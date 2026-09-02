package com.lifecyclebot.data

/**
 * No production credential may ship in the APK or source tree.
 *
 * Provider keys are supplied by the operator through the encrypted settings
 * store. Empty defaults deliberately make a missing credential visible to the
 * provider-health and execution gates instead of silently sharing one key
 * across every installation.
 */
internal object DefaultKeys {
    const val HELIUS: String = ""
    const val BIRDEYE: String = ""
    const val JUPITER: String = ""
    const val GROQ: String = ""
    const val OPENROUTER: String = ""
    const val CEREBRAS: String = ""
    const val MISTRAL: String = ""
    const val ALCHEMY: String = ""
}
