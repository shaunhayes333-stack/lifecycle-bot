package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7608TwelveLaneNativeLivenessTelemetryTest {
    private fun src(rel:String)=File("src/main/kotlin/com/lifecyclebot/"+rel).readText()
    @Test fun nativeBridgeExposesLastOpinionAndCounts() {
        val s=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(s.contains("private val lastOpinion=ConcurrentHashMap<String,Opinion>()"))
        assertTrue(s.contains("fun laneRuntime7542("))
    }
    @Test fun livenessRowsExposeNativeDisposition() {
        val s=src("engine/ToolkitSignalSheet.kt")
        val b=s.substringAfter("fun designatedRoleLivenessReport6599()")
        assertTrue(b.contains("SpecialistBrainBridge7542.laneRuntime7542(lane)"))
        assertTrue(b.contains("nativeCalled="))
        assertTrue(b.contains("nativeReject="))
        assertTrue(b.contains("nativeReason="))
    }
}
