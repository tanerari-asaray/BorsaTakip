package tr.borsatakip.v5.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.model.Candle

/**
 * Fresh-only MTF history cache.
 *
 * Cache validity requires both load-time freshness and a plausible exchange timestamp. This keeps
 * a recently loaded but market-stale history packet from being treated as fresh scan data.
 */
object MtfHistoryCache {
    data class Entry(
        val candles: List<Candle>,
        val loadedAt: Long,
        val lastExchangeTimestamp: Long,
        val sourceKey: String,
        val symbol: String,
        val timeframe: String
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun loadFresh(
        sourceKey: String,
        symbol: String,
        timeframe: String,
        ttlMs: Long,
        nowMs: () -> Long = { System.currentTimeMillis() },
        loader: suspend () -> List<Candle>
    ): List<Candle> {
        require(ttlMs > 0L) { "MTF cache TTL pozitif olmalı." }
        val normalizedSymbol = symbol.trim().uppercase()
        val normalizedTimeframe = timeframe.trim().lowercase()
        val key = "${sourceKey.trim()}|$normalizedSymbol|$normalizedTimeframe"
        val now = nowMs()
        entries[key]?.takeIf { isFresh(it, now, ttlMs) }?.let { return it.candles }

        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val lockedNow = nowMs()
            entries[key]?.takeIf { isFresh(it, lockedNow, ttlMs) }?.let {
                return@withLock it.candles
            }

            val clean = OhlcvResampler.sanitize(loader())
            if (clean.isEmpty()) {
                entries.remove(key)
                return@withLock emptyList()
            }
            require(clean.first().timestamp <= lockedNow + MAX_FUTURE_SKEW_MS) {
                "MTF cache future timestamp içeriyor."
            }
            require(clean.last().timestamp > 0L) {
                "MTF cache exchange timestamp eksik."
            }
            entries[key] = Entry(
                candles = clean,
                loadedAt = lockedNow,
                lastExchangeTimestamp = clean.last().timestamp,
                sourceKey = sourceKey.trim(),
                symbol = normalizedSymbol,
                timeframe = normalizedTimeframe
            )
            clean
        }
    }

    private fun isFresh(entry: Entry, nowMs: Long, ttlMs: Long): Boolean {
        if (nowMs < entry.loadedAt || nowMs - entry.loadedAt > ttlMs) return false
        if (entry.lastExchangeTimestamp <= 0L) return false
        if (entry.lastExchangeTimestamp > nowMs + MAX_FUTURE_SKEW_MS) return false
        val intervalMs = timeframeToMs(entry.timeframe)
        val exchangeAgeLimit = maxOf(ttlMs, intervalMs * 3L)
        return nowMs - entry.lastExchangeTimestamp <= exchangeAgeLimit
    }

    private fun timeframeToMs(timeframe: String): Long {
        val normalized = timeframe.trim().lowercase()
        return when {
            normalized.endsWith("m") -> normalized.removeSuffix("m").toLongOrNull()?.coerceAtLeast(1L)?.times(60_000L) ?: 60_000L
            normalized.endsWith("h") -> normalized.removeSuffix("h").toLongOrNull()?.coerceAtLeast(1L)?.times(3_600_000L) ?: 3_600_000L
            normalized.endsWith("d") -> normalized.removeSuffix("d").toLongOrNull()?.coerceAtLeast(1L)?.times(86_400_000L) ?: 86_400_000L
            else -> 60_000L
        }
    }

    fun peek(sourceKey: String, symbol: String, timeframe: String): Entry? {
        val key = "${sourceKey.trim()}|${symbol.trim().uppercase()}|${timeframe.trim().lowercase()}"
        return entries[key]
    }

    fun clear() {
        entries.clear()
        locks.clear()
    }

    private const val MAX_FUTURE_SKEW_MS = 120_000L
}
