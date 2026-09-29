package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7453SingleVersionAuthorityTest {
    @Test fun build_and_runtime_use_lifecycle_apk_version_only() {
        val build = File("../.github/workflows/build.yml").readText()
        val runtime = File("../ci/runtime-test.sh").readText()
        assertTrue(build.contains("VERSION_NAME=\"$(tr -d \'[:space:]\' < AATE_VERSION)\""))
        assertFalse(build.contains("NESTED_VERSION="))
        assertFalse(build.contains("< ../AATE_VERSION"))
        assertTrue(runtime.contains("PRODUCTION_VERSION=\"$(tr -d \'[:space:]\' < AATE_VERSION)\""))
        assertFalse(runtime.contains("< ../AATE_VERSION"))
    }
}
