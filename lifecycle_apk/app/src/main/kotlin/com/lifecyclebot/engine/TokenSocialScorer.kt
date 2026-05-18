package com.lifecyclebot.engine

import com.lifecyclebot.network.PairInfo

/**
 * V5.9.911 — TOKEN SOCIAL CREDIBILITY SCORER.
 *
 * Background:
 *   DexScreener's /token-pairs/v1/solana/{mint} response includes an
 *   "info" block with "socials" (twitter/telegram/discord/etc) and
 *   "websites" arrays for every token. These signals were being parsed
 *   ALL THE WAY into the response and then silently dropped at the
 *   PairInfo construction step — a textbook "dropped signal = dropped
 *   AGI sample" gap per memory #87 #1.
 *
 *   V5.9.911 surfaces them into PairInfo (no new network requests) and
 *   this scorer turns them into a soft-shape trust multiplier.
 *
 * What it measures:
 *   Tokens with verifiable social presence — Twitter, Telegram, Discord,
 *   a website, an image — have radically different rug-risk profiles
 *   than tokens with empty info blocks. A meme project that bothered to
 *   set up a Twitter and Telegram is rarely the kind that pulls
 *   liquidity in the first hour.
 *
 *   This is NOT a guarantee — scammers do set up fake socials. But the
 *   ABSENCE of socials is a strong negative signal, and the PRESENCE is
 *   a mild positive. The scorer treats it asymmetrically:
 *
 *     0 socials, 0 websites, no image  → 0.85  (mild penalty, signal-poor)
 *     1 social   only                   → 0.95
 *     2+ socials OR 1 social + website  → 1.00 (neutral, default)
 *     3+ socials AND website            → 1.05 (mild bonus, signal-rich)
 *     Premium signals (medium blog,
 *       coingecko_id via extensions)    → up to 1.08
 *
 * DOCTRINE
 *   - Memory #86 (help, don't hinder): multiplier in [0.85, 1.08].
 *     Never veto. Even maximum social-absence ONLY shaves 15% off size.
 *   - Memory #87 #1 (no dropped AGI samples): we now CAPTURE this
 *     signal; the bot learns whether social presence correlates with
 *     survival.
 *   - Memory #87 #4 (real data only): every signal sourced from the
 *     real DexScreener response — no synthesis, no defaults masquerading
 *     as truth. Missing info block = empty lists = signal-poor score.
 *
 * Cost: ZERO new network requests. The data is already arriving on every
 * existing DexScreener call; this just stops throwing it away.
 */
object TokenSocialScorer {

    /** Mild penalty floor for signal-poor tokens. */
    private const val FLOOR = 0.85

    /** Mild bonus ceiling for signal-rich tokens. */
    private const val CEILING = 1.08

    /** Premium platforms — disproportionate trust signal. */
    private val PREMIUM_PLATFORMS = setOf("medium", "github", "coingecko_id")

    /** Standard platforms — typical social presence. */
    private val STANDARD_PLATFORMS = setOf("twitter", "telegram", "discord", "x")

    /**
     * Returns trust multiplier in [FLOOR, CEILING] based on social presence
     * harvested from the PairInfo info block.
     *
     * Safe to call from any thread. Pure function, no I/O.
     */
    fun getTrust(info: PairInfo?): Double {
        if (info == null) return 1.0  // no data → neutral (fail-open)

        val socials = info.socials
        val websites = info.websites
        val hasImage = info.hasImage

        val socialCount = socials.count { it in STANDARD_PLATFORMS }
        val premiumCount = socials.count { it in PREMIUM_PLATFORMS }
        val hasWebsite = websites.isNotEmpty()

        // Build a base score
        var score = when {
            // Completely empty info block — strongest negative signal
            socials.isEmpty() && websites.isEmpty() && !hasImage -> FLOOR
            // Has only one standard social, no website
            socialCount == 1 && !hasWebsite -> 0.95
            // 2+ socials or 1 social + website — typical legit meme
            socialCount >= 2 || (socialCount == 1 && hasWebsite) -> 1.00
            // Has website only, no socials — minor positive
            hasWebsite && socialCount == 0 -> 0.98
            // Only image, nothing else — minor positive (someone bothered)
            hasImage && socialCount == 0 && !hasWebsite -> 0.92
            else -> 1.00
        }

        // Premium platform bonus (capped at CEILING)
        if (premiumCount > 0) {
            score = (score + 0.03 * premiumCount.coerceAtMost(2)).coerceAtMost(CEILING)
        }

        // Three-or-more standard socials + website — signal-rich legitimate project
        if (socialCount >= 3 && hasWebsite) {
            score = (score + 0.03).coerceAtMost(CEILING)
        }

        return score.coerceIn(FLOOR, CEILING)
    }

    /**
     * Returns a human-readable summary of the social presence for logging
     * / telemetry. Format: "tw,tg,dc|web|img" or "—" if signal-poor.
     */
    fun summary(info: PairInfo?): String {
        if (info == null) return "—"
        val s = info.socials
        val w = info.websites
        val img = info.hasImage
        if (s.isEmpty() && w.isEmpty() && !img) return "—"
        val parts = mutableListOf<String>()
        if (s.isNotEmpty()) parts += s.take(4).joinToString(",")
        if (w.isNotEmpty()) parts += "web×${w.size}"
        if (img) parts += "img"
        return parts.joinToString("|")
    }
}
