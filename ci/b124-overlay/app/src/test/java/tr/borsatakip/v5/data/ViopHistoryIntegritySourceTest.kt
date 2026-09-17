package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViopHistoryIntegritySourceTest {
    private fun read(path: String) = File(path).readText()

    @Test fun quoteAndHistoryRequireResponseSymbolIdentity() {
        val s = read("src/main/java/tr/borsatakip/v5/data/BackendProvider.kt")
        assertTrue(s.contains("Quote cevabında sembol kimliği yok."))
        assertTrue(s.contains("History cevabında sembol kimliği yok."))
        assertFalse(s.contains("ifBlank { symbol }.uppercase()"))
    }

    @Test fun historyRequiresIntervalMetadata() {
        val s = read("src/main/java/tr/borsatakip/v5/data/BackendProvider.kt")
        assertTrue(s.contains("History cevabında interval metadata yok."))
        assertTrue(s.contains("İstenen interval=$interval, backend=$responseInterval."))
    }

    @Test fun historyRequiresClosedLastBar() {
        val s = read("src/main/java/tr/borsatakip/v5/data/BackendProvider.kt")
        assertTrue(s.contains("History cevabında lastBarClosed metadata yok."))
        assertTrue(s.contains("Karar fiyatı için son history mumu kapalı değil."))
    }

    @Test fun duplicateAndOutOfOrderCandlesFailClosed() {
        val s = read("src/main/java/tr/borsatakip/v5/data/BackendProvider.kt")
        assertTrue(s.contains("Duplicate timestamp"))
        assertTrue(s.contains("History kronolojik sırada değil."))
        assertFalse(Regex("\\.distinctBy\\s*\\{\\s*it\.timestamp").containsMatchIn(s))
        assertFalse(Regex("\\.sortedBy\\s*\\{\\s*it\.timestamp").containsMatchIn(s))
    }

    @Test fun retryUsesJitter() {
        val s = read("src/main/java/tr/borsatakip/v5/data/IntervalMarketDataProvider.kt")
        assertTrue(s.contains("retryDelayWithJitter"))
        assertTrue(s.contains("RETRY_JITTER_MAX_MS = 180L"))
    }

    @Test fun decisionPriceDoesNotUseLiveFallback() {
        val s = read("src/main/java/tr/borsatakip/v5/analysis/DecisionPricePolicy.kt")
        assertFalse(s.contains("liveFallback"))
    }
}
