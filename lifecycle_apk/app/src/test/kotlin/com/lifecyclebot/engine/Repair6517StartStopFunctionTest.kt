package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6517StartStopFunctionTest {
    private val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
    private val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
    private val vm = java.io.File("src/main/kotlin/com/lifecyclebot/ui/BotViewModel.kt").readText()

    @Test
    fun `start stop listener is bound and enabled during bindViews independent of rendering`() {
        val view = main.indexOf("btnToggle       = findViewById(R.id.btnToggle)")
        val bind = main.indexOf("bindRuntimeToggleListener6517()", view)
        val render = main.indexOf("private fun renderRuntimeBar(")
        assertTrue(view > 0 && bind > view && bind < render)
        assertTrue(main.contains("UI_RUNTIME_TOGGLE_TAP_6517"))
        assertFalse(main.contains("btnToggle.setOnClickListener { /* state-bound in updateUi */ }"))
        assertFalse(main.contains("btnToggle.isEnabled = false"))
    }

    @Test
    fun `deferred operator intent is visible retained cancellable and cannot wait on null job`() {
        assertTrue(service.contains("serviceStartRequested6517.set(true)"))
        assertTrue(service.contains("START_REQUEST_RETAINED_DURING_BOOTSTRAP_6517"))
        assertTrue(service.contains("val bootstrap = serviceBootstrapJob6516"))
        assertTrue(service.contains("if (bootstrap == null)"))
        assertTrue(service.contains("SERVICE_BOOTSTRAP_JOB_MISSING_6517"))
        assertFalse(service.contains("while (!serviceBootstrapReady6516)"))
        assertTrue(service.contains("serviceStartRequested6517.getAndSet(false)"))
        assertTrue(service.contains("START_RESUMED_AFTER_SERVICE_BOOTSTRAP_6517"))
        val resumed = service.indexOf("START_RESUMED_AFTER_SERVICE_BOOTSTRAP_6517")
        val owned = service.indexOf("startInProgress = true", resumed)
        val actualStart = service.indexOf("startBot()", owned)
        val released = service.indexOf("startInProgress = false", actualStart)
        assertTrue(resumed > 0 && owned > resumed && actualStart > owned && released > actualStart)
        val stopAccepted = service.indexOf("LIFECYCLE_STOP_ACCEPTED")
        val cancel = service.indexOf("serviceStartRequested6517.set(false)", stopAccepted)
        assertTrue(stopAccepted > 0 && cancel > stopAccepted)
        assertTrue(service.contains("DEFERRED_START_CANCELLED_BY_STOP_6517"))
    }

    @Test
    fun `UI renders pending and failure truth and dispatch has fallback telemetry`() {
        assertTrue(main.contains("isStartPending6517()"))
        assertTrue(main.contains("Cancel Start"))
        assertTrue(main.contains("STARTING · loading persisted state off main thread"))
        assertTrue(main.contains("START FAILED ·"))
        assertTrue(vm.contains("UI_START_DISPATCHED_6517"))
        assertTrue(vm.contains("UI_START_FALLBACK_DISPATCHED_6517"))
        assertTrue(vm.contains("UI_START_DISPATCH_FAILED_6517"))
        assertTrue(vm.contains("ctx.startForegroundService(intent)"))
        assertTrue(vm.contains("ctx.startService(intent)"))
    }
}
