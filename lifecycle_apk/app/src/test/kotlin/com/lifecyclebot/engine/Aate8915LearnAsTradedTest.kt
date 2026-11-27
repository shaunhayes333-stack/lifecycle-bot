package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BasePocket8904
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8915 — the edge model learns a meme label as the lane trades it (stop first, then the doctrine line), an unwitnessed 10x
 * mark no longer deletes its observation, and the crypto twin persists only the records a close changed.
 */
class Aate8915LearnAsTradedTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aMemeLabelIsLearnedUnderTheLanesExits() {
        val held = -50.0 - 3.0
        val y = ForwardReturnLabeler7731.edgeTarget8915("EXPRESS", -50.0, -50.0, 2.0, 3.0, held)
        assertEquals(BasePocket8904.doctrineNet8910("EXPRESS", -50.0, -50.0, 2.0, 3.0, held), y, 1e-9)
        assertTrue("the stop (at most 40 %) sold it before the label's -50 %", y > held)
        assertEquals("a crypto lane learns the held net", held, ForwardReturnLabeler7731.edgeTarget8915("CRYPTO_ALT", -50.0, -50.0, 2.0, 3.0, held), 0.0)
        assertTrue(ForwardReturnLabeler7731.edgeTargetLine8915().startsWith("edgeTarget8915[memeLabels="))
    }

    @Test fun wired() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue("the booked outcome uses the exit recipe frozen with its decision",
            l.contains("val edgeNet8915 = edgeTarget8915(o.lane, gross, o.minPct7962, o.heldPeak8071, o.costPct, net, o.labelRecipe9043)"))
        assertTrue(l.contains("val recipe9043 = try { freezeLabelRecipe9043(it) }") &&
            l.contains("it.labelRecipe9043 = recipe9043?.takeIf { r -> r.valid9043() }"))
        assertTrue(l.contains("EdgeModel8537.learn(it, edgeNet8915, witness)"))
        val target = l.substringAfter("internal fun edgeTarget8915(").substringBefore("fun edgeTargetLine8915()")
        assertTrue("new observations use the frozen doctrine; only legacy observations use the current lane fallback",
            target.contains("recipe9043?.edgeNet9043(gross, minPct, heldPeakPct, costPct, net)") &&
                target.contains("?: BasePocket8904.doctrineNet8910(lane, gross, minPct, heldPeakPct, costPct, net)"))
        val extreme = l.substring(l.indexOf("if (status8386 == \"OBSERVED_ONLY_UNWITNESSED_EXTREME\") {"))
        val block = extreme.substring(0, extreme.indexOf("continue"))
        assertFalse("the observation is kept", block.contains("pending.remove(key, o)"))
        assertTrue(block.contains("unwitnessedKept8915.incrementAndGet()"))
        val twin = src("perps/CryptoTwin8883.kt")
        assertTrue(twin.contains("persist8883(filed8915)"))
        assertTrue(twin.contains("for (k in keys) { val q = closes[k] ?: continue; e.putString(k, synchronized(q) { encode8883(q.toList()) }) }"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("ForwardReturnLabeler7731.edgeTargetLine8915()"))
        assertTrue(File("../ci/cortex-jvm/run.sh").readText().contains("https://maven-central.storage-download.googleapis.com/maven2"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 103. Learn what the lane trades (8915)"))
    }
}
