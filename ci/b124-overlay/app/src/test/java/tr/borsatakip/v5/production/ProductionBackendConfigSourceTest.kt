package tr.borsatakip.v5.production

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionBackendConfigSourceTest {
    @Test
    fun buildProvidesPublicProductionBackendUrlAndRuntimeOverridesRemainPossible() {
        val root = locateRoot()
        val gradle = File(root, "app/build.gradle.kts").readText()
        val settings = File(root, "app/src/main/java/tr/borsatakip/v5/data/SettingsStore.kt").readText()
        assertTrue(gradle.contains("PRODUCTION_BACKEND_URL"))
        assertTrue(gradle.contains("BORSA_BACKEND_URL"))
        assertTrue(settings.contains("BuildConfig.PRODUCTION_BACKEND_URL"))
        assertTrue(settings.contains("p.contains(\"base_url\")"))
        assertTrue(settings.contains("p.edit().putString(\"base_url\", normalized).apply()"))
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
