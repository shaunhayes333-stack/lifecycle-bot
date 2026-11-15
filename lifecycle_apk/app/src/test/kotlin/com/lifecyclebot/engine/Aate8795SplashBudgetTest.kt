package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guard the startup animation budget after the 8793 smoke ANR. */
class Aate8795SplashBudgetTest {
    @Test fun splashHasBoundedParticlesAndStopsOffscreenAnimation() {
        val source = File("src/main/kotlin/com/lifecyclebot/ui/SplashActivity.kt").readText()
        assertTrue(source.contains("private const val PARTICLE_COUNT       = 10"))
        val paused = source.substringAfter("override fun onPause()").substringBefore("override fun onDestroy()")
        assertTrue(paused.contains("stopParticleAnimation()"))
        assertTrue(paused.contains("super.onPause()"))
    }
}
