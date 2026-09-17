package tr.borsatakip.v5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViopProviderFallbackPolicySourceTest {
    @Test
    fun providerErrorsDoNotSilentlyBecomeUnderlyingScan() {
        val source = locateRoot().resolve("app/src/main/java/tr/borsatakip/v5/ui/ViopActivity.kt").readText()
        assertTrue(source.contains("VİOP provider doğrulanamadı"))
        assertFalse(source.contains("""else runUnderlyingScan(\"VİOP provider doğrulanamadı"))
    }

    private fun locateRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
