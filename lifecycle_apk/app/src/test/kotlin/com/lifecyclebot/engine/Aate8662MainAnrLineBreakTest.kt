package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.0.8662 — regression for the actual 8661 Splash→Main ANR thread stack. */
class Aate8662MainAnrLineBreakTest {
    private val path = "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
    private val source by lazy { File(path).readText() }

    @Test fun dashboardUsesSimpleLineBreakBeforeFirstMeasure() {
        val inflate = source.indexOf("setContentView(R.layout.activity_main)")
        val tune = source.indexOf("findViewById<View>(R.id.mainScrollView)?.let(::useFastDashboardTextLayout8662)", inflate)
        val bind = source.indexOf("bindViews()", inflate)
        assertTrue("optimize static TextViews after inflate, before UI bind", inflate >= 0 && tune > inflate && bind > tune)
        assertTrue(source.contains("view.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE"))
        assertTrue(source.contains("view.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE"))
    }

    @Test fun dynamicDashboardLabelsRetainLowCostLayoutWithoutTouchingInputs() {
        val helper = source.substringAfter("private fun android.widget.TextView.setTextIfChanged(value: CharSequence)")
            .substringBefore("private fun setDecisionLogTextBounded4280")
        assertTrue(helper.contains("this !is EditText"))
        assertTrue(helper.contains("breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE"))
        assertTrue(helper.contains("hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE"))
        assertTrue(helper.contains("this.text?.toString() != value.toString()"))
    }
}
