package tr.borsatakip.v5.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import kotlin.math.ceil

/**
 * Applies an explicit scan timeframe to the underlying market provider without silently changing
 * the requested interval. Retry waits include small random jitter to avoid synchronized retries.
 */
class IntervalMarketDataProvider(
    private val delegate: MarketDataProvider,
    private val timeframe: ScanTimeframe,
    private val minimumBars: Int = RealTimeIntegrityPolicy.MIN_HISTORY_BARS
) : MarketDataProvider {

    data class ProviderScanDiagnostics(
        val noDataSymbols: List<String> = emptyList(),
        val errorSymbols: List<String> = emptyList(),
        val messages: Map<String, String> = emptyMap()
    )

    @Volatile
    var diagnostics: ProviderScanDiagnostics = ProviderScanDiagnostics()
        private set

    override val id: String = "${delegate.id}:${timeframe.apiInterval}"
    override val displayName: String = "${delegate.displayName} • ${timeframe.label}"

    override suspend fun fetchAll(symbols: List<String>, onProgress: (Int, Int) -> Unit): List<Stock> = coroutineScope {
        val total = symbols.size
        if (total == 0) return@coroutineScope emptyList()
        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENCY)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val noData = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val messages = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        val rows = symbols.map { symbol ->
            async(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    try {
                        fetchOne(symbol)?.let { reframe(it) }
                    } catch (pe: ProviderException) {
                        when (pe.code) {
                            ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.STALE_DATA, ProviderFailureCode.BIST_HISTORY_ERROR -> {
                                noData += symbol
                            }
                            else -> errors += symbol
                        }
                        messages[symbol] = pe.message
                        logSymbolFailure(symbol, pe.code.name, pe.message)
                        null
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        errors += symbol
                        messages[symbol] = t.message ?: t.javaClass.simpleName
                        logSymbolFailure(symbol, "DATA_ERROR", t.message)
                        null
                    }
                } finally {
                    onProgress(completed.incrementAndGet().coerceAtMost(total), total)
                    semaphore.release()
                }
            }
        }.awaitAll().filterNotNull()
        diagnostics = ProviderScanDiagnostics(noData.distinct(), errors.distinct(), messages.toMap())
        if (rows.isEmpty()) {
            val detail = messages.values.firstOrNull() ?: "Seçilen periyotta OHLCV alınamadı."
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "${timeframe.label} tarama yapılamadı. $detail")
        }
        rows
    }

    override suspend fun fetchOne(symbol: String): Stock? = delegate.fetchOne(symbol)?.let { reframe(it) }
    override suspend fun listSymbols(): List<String> = delegate.listSymbols()

    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> =
        delegate.fetchHistory(symbol, fromTime, toTime, intervalMinutes)

    override suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean): List<Candle> =
        delegate.fetchDailyHistory(symbol, maximumRange)

    private suspend fun reframe(stock: Stock): Stock {
        val selected = if (timeframe.isDaily) {
            val existing = OhlcvResampler.sanitize(stock.candles)
            if (stock.interval.equals("1d", true) && existing.size >= minimumBars) existing
            else OhlcvResampler.sanitize(fetchDailyWithRetry(stock.symbol))
        } else {
            val toTime = maxOf(System.currentTimeMillis(), stock.exchangeTimestamp)
            val fromTime = toTime - lookbackMs(timeframe.storedMinutes, minimumBars)
            if (timeframe.storedMinutes in DIRECT_INTERVALS) {
                OhlcvResampler.sanitize(fetchHistoryWithRetry(stock.symbol, fromTime, toTime, timeframe.storedMinutes))
            } else {
                val oneMinute = OhlcvResampler.sanitize(fetchHistoryWithRetry(stock.symbol, fromTime, toTime, 1))
                OhlcvResampler.aggregate(oneMinute, 1, timeframe.storedMinutes)
            }
        }

        if (selected.size < minimumBars) {
            throw ProviderException(
                ProviderFailureCode.EMPTY_DATA,
                "${stock.symbol} için ${timeframe.label} periyotta en az $minimumBars mum gerekli; ${selected.size} alındı."
            )
        }

        val candles = selected.takeLast(MAX_ANALYSIS_BARS)
        val last = candles.last()
        if (!timeframe.isDaily && stock.isRealtime) {
            val barAgeMs = stock.exchangeTimestamp - last.timestamp
            val maxAllowedBarAgeMs = (timeframe.storedMinutes + 2L) * 60_000L
            if (barAgeMs < -RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS || barAgeMs > maxAllowedBarAgeMs) {
                throw ProviderException(
                    ProviderFailureCode.STALE_DATA,
                    "${stock.symbol} ${timeframe.label} OHLCV güncel seansı temsil etmiyor; son mum yaşı ${barAgeMs.coerceAtLeast(0L) / 1000L} sn."
                )
            }
        }

        return stock.copy(
            candles = candles,
            dataTimestamp = if (!timeframe.isDaily && !stock.isRealtime) last.timestamp else stock.dataTimestamp,
            quotePrice = if (!timeframe.isDaily && !stock.isRealtime) last.close else stock.quotePrice,
            interval = timeframe.apiInterval,
            lastBarTime = last.timestamp,
            lastBarClosed = if (timeframe.isDaily) {
                stock.lastBarClosed ?: isBarClosed(last.timestamp, 24L * 60L * 60L * 1000L)
            } else {
                isBarClosed(last.timestamp, timeframe.storedMinutes * 60_000L)
            }
        )
    }

    private fun isBarClosed(barTimestamp: Long, frameMs: Long, nowWall: Long = System.currentTimeMillis()): Boolean =
        barTimestamp > 0L && frameMs > 0L && nowWall >= barTimestamp + frameMs

    private suspend fun fetchHistoryWithRetry(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> {
        var last: Throwable? = null
        repeat(MAX_FETCH_ATTEMPTS) { index ->
            try {
                return delegate.fetchHistory(symbol, fromTime, toTime, intervalMinutes)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                last = t
                val retryable = (t as? ProviderException)?.code in RETRYABLE_CODES
                if (!retryable || index == MAX_FETCH_ATTEMPTS - 1) {
                    val code = (t as? ProviderException)?.code ?: ProviderFailureCode.BIST_HISTORY_ERROR
                    logSymbolFailure(symbol, code.name, t.message, "history-window/${intervalMinutes}m")
                    throw if (t is ProviderException) t else ProviderException(
                        ProviderFailureCode.BIST_HISTORY_ERROR,
                        "$symbol ${intervalMinutes} DK geçmiş verisi alınamadı: ${t.message ?: t.javaClass.simpleName}",
                        t
                    )
                }
                delay(retryDelayWithJitter(index))
            }
        }
        throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$symbol geçmiş verisi alınamadı.", last)
    }

    private suspend fun fetchDailyWithRetry(symbol: String): List<Candle> {
        var last: Throwable? = null
        repeat(MAX_FETCH_ATTEMPTS) { index ->
            try {
                return delegate.fetchDailyHistory(symbol, maximumRange = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                last = t
                val retryable = (t as? ProviderException)?.code in RETRYABLE_CODES
                if (!retryable || index == MAX_FETCH_ATTEMPTS - 1) {
                    val code = (t as? ProviderException)?.code ?: ProviderFailureCode.BIST_HISTORY_ERROR
                    logSymbolFailure(symbol, code.name, t.message, "daily-history/1d")
                    throw if (t is ProviderException) t else ProviderException(
                        ProviderFailureCode.BIST_HISTORY_ERROR,
                        "$symbol gerçek 1 GÜN geçmiş verisi alınamadı: ${t.message ?: t.javaClass.simpleName}",
                        t
                    )
                }
                delay(retryDelayWithJitter(index))
            }
        }
        throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$symbol günlük geçmiş verisi alınamadı.", last)
    }

    private fun retryDelayWithJitter(index: Int): Long {
        val base = RETRY_BASE_DELAY_MS * (index + 1L)
        val jitter = kotlin.random.Random.nextLong(0L, RETRY_JITTER_MAX_MS + 1L)
        return base + jitter
    }

    private fun logSymbolFailure(symbol: String, code: String, message: String?, endpoint: String = "selected-timeframe") {
        Log.w(
            TAG,
            "SYMBOL=$symbol TIMEFRAME=${timeframe.apiInterval} DATA_SOURCE=${delegate.id} ENDPOINT=$endpoint " +
                "ERROR_CODE=$code ERROR_MESSAGE=${message ?: "unknown"} TIMESTAMP=${System.currentTimeMillis()}"
        )
    }

    companion object {
        private const val TAG = "SCAN_INTERVAL"
        private const val MAX_CONCURRENCY = 6
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 450L
        private const val RETRY_JITTER_MAX_MS = 180L
        private const val MAX_ANALYSIS_BARS = 800
        private const val TRADING_MINUTES_PER_DAY = 480.0
        private val DIRECT_INTERVALS = setOf(1, 5, 15, 30, 60)
        private val RETRYABLE_CODES = setOf(
            ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.RATE_LIMIT,
            ProviderFailureCode.SERVER_ERROR, ProviderFailureCode.NETWORK_ERROR
        )

        fun lookbackMs(intervalMinutes: Int, bars: Int = RealTimeIntegrityPolicy.MIN_HISTORY_BARS): Long {
            val safeMinutes = intervalMinutes.coerceIn(1, 239)
            val tradingDays = (bars.coerceAtLeast(1) * safeMinutes) / TRADING_MINUTES_PER_DAY
            val calendarDays = ceil(tradingDays * 1.9 + 7.0).toLong().coerceIn(10L, 365L)
            return calendarDays * 24L * 60L * 60L * 1000L
        }
    }
}
