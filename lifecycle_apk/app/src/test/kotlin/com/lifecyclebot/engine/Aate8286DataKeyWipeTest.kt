package com.lifecyclebot.engine

import com.lifecyclebot.data.ConfigStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8286 — a blank market-data key field never erases a saved key unless the screen showed the saved keys. */
class Aate8286DataKeyWipeTest {
    @Test fun blankNeverErasesASavedKeyWithoutExplicitClear() {
        assertTrue(ConfigStore.keepSavedKey8286(saved = "codex-key", incoming = "", allowClear = false))
        assertFalse(ConfigStore.keepSavedKey8286(saved = "codex-key", incoming = "", allowClear = true))
        assertFalse(ConfigStore.keepSavedKey8286(saved = "old", incoming = "new", allowClear = false))
        assertFalse(ConfigStore.keepSavedKey8286(saved = "", incoming = "", allowClear = false))
    }

    @Test fun onlyTheSettingsSheetMayClear() {
        val sheet = File("src/main/kotlin/com/lifecyclebot/ui/SettingsBottomSheet.kt").readText()
        assertTrue(sheet.contains("allowClear8286 = true"))
        val card = File("src/main/kotlin/com/lifecyclebot/ui/DataKeysCard7962.kt").readText()
        assertFalse(card.contains("allowClear8286 = true"))
    }
}
