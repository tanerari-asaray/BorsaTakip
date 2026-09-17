package tr.borsatakip.v5.analysis

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.MtfHistoryCache
import tr.borsatakip.v5.data.ViopHistoryIntegrityPolicy
import tr.borsatakip.v5.model.Candle

class B119StabilityPerformanceTest {
    private val aligned = 1_699_999_920_000L

    private fun candle(i: Int, stepMinutes: Int = 1): Candle {
        val close = 100.0 + i
        return Candle(
            timestamp = aligned + i * stepMinutes * 60_000L,
            open = close - 0.2,
            high = close + 0.5,
            low = close - 0.5,
            close = close,
            volume = 1000.0 + i
        )
    }

    @After fun clearCache() = MtfHistoryCache.clear()

    @Test fun explicitSourceIntervalBuildsCompleteThreeMinuteBucket() {
        val out = OhlcvResampler.aggregate((0..2).map(::candle), 1, 3)
        assertEquals(1, out.size)
        assertEquals(candle(2).close, out.single().close, 0.0001)
    }

    @Test fun explicitFiveMinuteSourceBuildsFifteenMinuteBucket() {
        val aligned15m = aligned - (aligned % (15 * 60_000L))
        val base = (0..2).map { i ->
            val close = 100.0 + i
            Candle(
                timestamp = aligned15m + i * 5 * 60_000L,
                open = close - 0.2,
                high = close + 0.5,
                low = close - 0.5,
                close = close,
                volume = 1000.0 + i
            )
        }
        val out = OhlcvResampler.aggregate(base, 5, 15)
        assertEquals(1, out.size)
        assertEquals(base.sumOf { it.volume }, out.single().volume, 0.0001)
    }

    @Test fun incompatibleSourceAndTargetIntervalsFailClosed() {
        assertTrue(OhlcvResampler.aggregate((0..3).map(::candle), 3, 5).isEmpty())
    }

    @Test fun decisionPricePrefersHistoryBarOverLiveQuote() {
        val history = listOf(candle(0), candle(1))
        val decision = DecisionPricePolicy.historyBarPrice(history)
        assertEquals(history.last().close, decision!!, 0.0001)
        assertNotEquals(999.0, decision, 0.0001)
    }

    @Test fun decisionPriceNeverFallsBackToLiveQuote() {
        assertEquals(null, DecisionPricePolicy.historyBarPrice(emptyList()))
    }

    @Test fun viopHistoryExchangeTimestampRejectsStaleIntradayData() {
        val now = System.currentTimeMillis()
        assertTrue(ViopHistoryIntegrityPolicy.validateExchangeFreshness(now - 33 * 60_000L, now, "15m") != null)
    }

    @Test fun viopHistoryExchangeTimestampAcceptsFreshIntradayData() {
        val now = System.currentTimeMillis()
        assertEquals(null, ViopHistoryIntegrityPolicy.validateExchangeFreshness(now - 10 * 60_000L, now, "15m"))
    }

    @Test fun viopHistoryRejectsBackendClosedFlagWhenLastCandleIsActuallyOpen() {
        val now = System.currentTimeMillis()
        val openCandle = candle(0).copy(timestamp = now - 5 * 60_000L)
        assertTrue(ViopHistoryIntegrityPolicy.validateLastBarClosed(listOf(openCandle), now, "15m") != null)
    }

    @Test fun viopHistoryAcceptsTimeBasedClosedLastCandle() {
        val now = System.currentTimeMillis()
        val closedCandle = candle(0).copy(timestamp = now - 16 * 60_000L)
        assertEquals(null, ViopHistoryIntegrityPolicy.validateLastBarClosed(listOf(closedCandle), now, "15m"))
    }

    @Test fun viopDailyFreshnessAllowsWeekendGap() {
        val friday = 1_767_343_200_000L
        val monday = friday + 72L * 60L * 60L * 1000L
        assertEquals(null, ViopHistoryIntegrityPolicy.validateExchangeFreshness(friday, monday, "1d"))
    }

    @Test fun mtfCacheReturnsFreshEntryWithoutReloading() = runBlocking {
        var loads = 0
        var now = System.currentTimeMillis()
        val loader: suspend () -> List<Candle> = {
            loads += 1
            listOf(candle(loads).copy(timestamp = now - 60_000L))
        }
        val first = MtfHistoryCache.loadFresh("P", "THYAO", "1m", 1_000L, { now }, loader)
        now += 500L
        val second = MtfHistoryCache.loadFresh("P", "THYAO", "1m", 1_000L, { now }, loader)
        assertEquals(1, loads)
        assertEquals(first, second)
    }

    @Test fun mtfCacheRejectsExchangeDataThatIsOlderThanItsFrameWindow() = runBlocking {
        var now = System.currentTimeMillis()
        var loads = 0
        val stale = listOf(candle(0, 1).copy(timestamp = now - 3 * 60 * 60 * 1000L))
        val fresh = listOf(candle(1, 1).copy(timestamp = now - 4 * 60 * 1000L))
        val first = runCatching {
            MtfHistoryCache.loadFresh("P", "XU030", "60m", 5 * 60_000L, { now }) { loads++; stale }
        }
        assertTrue(first.isFailure)
        val second = MtfHistoryCache.loadFresh("P", "XU030", "60m", 5 * 60_000L, { now }) { loads++; fresh }
        assertEquals(2, loads)
        assertEquals(fresh, second)
    }

    @Test fun mtfCacheNeverReturnsExpiredEntryAsFresh() = runBlocking {
        var loads = 0
        var now = System.currentTimeMillis()
        val loader: suspend () -> List<Candle> = {
            loads += 1
            listOf(candle(loads).copy(timestamp = now - 60_000L))
        }
        val first = MtfHistoryCache.loadFresh("P", "GARAN", "60m", 1_000L, { now }, loader)
        now += 1_001L
        val second = MtfHistoryCache.loadFresh("P", "GARAN", "60m", 1_000L, { now }, loader)
        assertEquals(2, loads)
        assertNotEquals(first.single().close, second.single().close, 0.0001)
    }
}
