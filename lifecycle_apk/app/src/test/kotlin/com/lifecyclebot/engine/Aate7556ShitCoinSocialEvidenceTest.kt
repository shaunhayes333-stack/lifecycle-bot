package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7556ShitCoinSocialEvidenceTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun authoritative_bridge_consumes_cached_social_presence_only() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        val shared=src("engine/SpecialistInputParity8527.kt")
        assertTrue(b.contains("SpecialistInputParity8527.shitcoinEvidence8527(ts, marketInputs8522)"))
        assertTrue(shared.contains("val socialMeta7556 = try { BirdeyeMetaDataProvider.peekCached(ts.mint) }"))
        assertTrue(shared.contains("socialMeta7556?.website, socialMeta7556?.twitter, socialMeta7556?.telegram"))
        assertTrue(b.contains("hasWebsite=shitcoinEvidence8527.hasWebsite"))
        assertTrue(b.contains("hasTwitter=shitcoinEvidence8527.hasTwitter"))
        assertTrue(b.contains("hasTelegram=shitcoinEvidence8527.hasTelegram"))
        val bot=src("engine/BotService.kt")
        assertTrue(bot.contains("hasWebsite = shitcoinEvidence8527.hasWebsite"))
        assertTrue(bot.contains("hasTwitter = shitcoinEvidence8527.hasTwitter"))
        assertTrue(bot.contains("hasTelegram = shitcoinEvidence8527.hasTelegram"))
        assertTrue(b.contains("hasGithub=false"))
        assertFalse(b.contains("maybePrefetch(ts.mint"))
    }

    @Test fun bridge_cache_identity_tracks_social_hydration() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        val fp=b.substringAfter("private fun fp").substringBefore("fun evaluate(ts:TokenState)")
        assertTrue(fp.contains("BirdeyeMetaDataProvider.peekCached(ts.mint)"))
        assertTrue(fp.contains("it.twitter.isNotBlank()"))
        assertTrue(fp.contains("it.telegram.isNotBlank()"))
        assertTrue(fp.contains("it.website.isNotBlank()"))
    }

    @Test fun canonical_metadata_does_not_expose_github_so_bridge_does_not_invent_it() {
        val m=src("engine/BirdeyeMetaDataProvider.kt")
        val meta=m.substringAfter("data class Meta(").substringBefore("private data class Cached")
        assertTrue(meta.contains("val twitter: String"))
        assertTrue(meta.contains("val telegram: String"))
        assertTrue(meta.contains("val website: String"))
        assertFalse(meta.contains("val github: String"))
    }

    @Test fun native_shitcoin_social_feature_is_presence_based() {
        val s=src("v3/scoring/ShitCoinTraderAI.kt")
        val block=s.substringAfter("// 6. SOCIAL SIGNALS").substringBefore("// 7. DEX BOOST")
        assertTrue(block.contains("if (hasTwitter) socialBonus += 5"))
        assertTrue(block.contains("if (hasWebsite) socialBonus += 4"))
        assertTrue(block.contains("if (hasTelegram) socialBonus += 3"))
    }
}
