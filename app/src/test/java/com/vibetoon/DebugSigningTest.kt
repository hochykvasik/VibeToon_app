package com.vibetoon

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

class DebugSigningTest {
    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }
    @Test fun checkedInDebugCertificateIsStableAndConfiguredForDebugOnly() {
        val store = KeyStore.getInstance("PKCS12")
        file("debug-signing/vibetoon-debug.p12").inputStream().use { store.load(it, "android".toCharArray()) }
        assertTrue(store.isKeyEntry("vibetoon-debug"))
        val digest = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("vibetoon-debug").encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("02e8a1d469bb32a6cd30e43659e927068a5daa752bd7055b16a81a36f3b88003", digest)
        val build = file("debug-signing/vibetoon-debug.p12").absoluteFile.parentFile.parentFile.resolve("build.gradle.kts").readText()
        assertTrue(build.contains("applicationId = \"com.vibetoon\""))
        assertTrue(build.contains("debug-signing/vibetoon-debug.p12"))
        assertTrue(build.contains("getByName(\"debug\")"))
    }
    @Test fun workflowVerifiesPackageAndCertificateBeforeUploadingDirectApk() {
        val workflow = listOf(File("../.github/workflows/android-build.yml"), File(".github/workflows/android-build.yml")).first { it.exists() }.readText()
        assertTrue(workflow.contains("02e8a1d469bb32a6cd30e43659e927068a5daa752bd7055b16a81a36f3b88003"))
        assertTrue(workflow.contains("package: name='com.vibetoon'"))
        assertTrue(workflow.indexOf("Verify debug package and signing certificate") < workflow.indexOf("Upload direct APK without ZIP"))
        assertTrue(workflow.contains("archive: false"))
        assertTrue(workflow.contains("path: vibetoon-debug.apk"))
    }
}
