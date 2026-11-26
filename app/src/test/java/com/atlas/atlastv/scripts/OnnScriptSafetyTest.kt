package com.atlas.atlastv.scripts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnnScriptSafetyTest {
    @Test
    fun onnScriptsRefuseEmulatorAndDoNotLaunchAvdFallbacks() {
        val setup = script("test-onn-user-setup-flow.ps1")
        val performance = script("test-onn-real-sync-performance.ps1")
        val debugCommand = script("run-debug-command.ps1")
        val connectedDebugTests = script("run-connected-debug-tests.ps1")
        val fastOnnSmoke = script("run-fast-onn-smoke.ps1")

        listOf(setup, performance, debugCommand, connectedDebugTests).forEach { text ->
            assertTrue(text.contains("Physical Onn device is required"))
            assertTrue(text.contains("Refusing to run"))
            assertTrue(text.contains("emulator-*"))
            assertFalse(text.contains("emulator.exe"))
            assertFalse(text.contains("-avd"))
            assertFalse(text.contains("Television_4K"))
        }
        assertFalse(fastOnnSmoke.contains("emulator.exe"))
        assertFalse(fastOnnSmoke.contains("-avd"))
        assertFalse(fastOnnSmoke.contains("Television_4K"))
    }

    @Test
    fun noPowerShellScriptRequiresEmulatorOrAvdLaunch() {
        val scriptsDir = File(projectRoot(), "scripts")
        scriptsDir.walkTopDown()
            .filter { it.isFile && it.extension == "ps1" }
            .forEach { file ->
                val text = file.readText(Charsets.UTF_8)
                assertFalse("${file.name} must not launch emulator.exe", text.contains("emulator.exe"))
                assertFalse("${file.name} must not launch an AVD", text.contains("-avd"))
                assertFalse("${file.name} must not require Television_4K", text.contains("Television_4K"))
                assertFalse("${file.name} must not contain emulator resolver helpers", text.contains("Get-RunningEmulator"))
                assertFalse("${file.name} must not contain emulator wait helpers", text.contains("Wait-ForEmulator"))
            }
    }

    private fun script(name: String): String =
        File(projectRoot(), "scripts/$name").readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(current, "settings.gradle.kts").exists()) return current
            current = current.parentFile ?: error("Project root not found")
        }
        error("Project root not found")
    }
}
