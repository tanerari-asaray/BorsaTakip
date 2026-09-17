package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopQuote
import tr.borsatakip.v5.model.NewsItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.time.YearMonth

class BackendProvider(context: Context) {
    private val s = SettingsStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun readJson(path: String, label: String): JSONObject {
        val base = s.baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "HTTPS veri sağlayıcı adresi Ayarlar bölümünde tanımlanmalıdır." }
        val request = Request.Builder()
            .url(base + path)
            .header("Accept", "application/json")
            .apply { if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey}") }
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "$label HTTP ${response.code} döndürdü." }
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "$label boş yanıt döndürdü." }
            return JSONObject(body)
        }
    }

    suspend fun loadViop(): Result<List<ViopContract>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = readJson("/v1/viop/contracts", "VİOP veri sağlayıcısı")
            val array = root.optJSONArray("items") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val x = array.optJSONObject(i) ?: continue
                    val symbol = x.optString("symbol").trim().uppercase()
                    if (symbol.isBlank()) continue
                    val underlying = x.optString("underlying").trim().uppercase()
                    val expiry = x.optString("expiry").trim()
                    val tick = x.optDouble("tickSize", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                    val multiplier = x.optDouble("multiplier", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                    val dataTimestamp = x.optLong("dataTimestamp", 0L)
                    val realtime = x.optBoolean("realtime", false)
                    val delay = if (x.has("delaySeconds") && !x.isNull("delaySeconds")) x.optInt("delaySeconds") else null
                    val currentSession = x.optBoolean("currentSessionIncluded", false)
                    val receivedAt = System.currentTimeMillis()
                    val mode = when {
                        realtime && currentSession && delay != null && delay in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.REALTIME
                        delay != null && delay > RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.DELAYED
                        else -> DataMode.UNVERIFIED
                    }
                    val expiryOk = runCatching { YearMonth.parse(expiry) >= YearMonth.now() }.getOrDefault(false)
                    val validity = when {
                        !expiryOk -> SignalValidity.REJECTED
                        underlying.isBlank() || tick == null || multiplier == null -> SignalValidity.INSUFFICIENT
                        dataTimestamp <= 0L -> SignalValidity.REJECTED
                        mode != DataMode.REALTIME -> SignalValidity.WATCH
                        else -> SignalValidity.VALID
                    }
                    val reason = when (validity) {
                        SignalValidity.VALID -> "Vade, dayanak, tickSize, multiplier ve veri kökeni doğrulandı."
                        SignalValidity.WATCH -> "Sözleşme parametreleri mevcut ancak gerçek zamanlı veri modu doğrulanmadı."
                        SignalValidity.INSUFFICIENT -> "Dayanak, tickSize veya multiplier zorunlu alanlarından biri eksik."
                        SignalValidity.REJECTED -> if (!expiryOk) "Vade geçersiz veya sona ermiş." else "Piyasa veri zamanı eksik/geçersiz."
                    }
                    add(ViopContract(
                        symbol=symbol, underlying=underlying.ifBlank { "-" }, expiry=expiry.ifBlank { "-" },
                        contractType=x.optString("contractType").ifBlank { "Vadeli İşlem" },
                        lastPrice=x.optDouble("lastPrice", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                        bid=x.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                        ask=x.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                        dailyChangePct=x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
                        tickSize=tick, multiplier=multiplier,
                        openInterest=x.optLong("openInterest", -1L).takeIf { it >= 0L },
                        volume=x.optDouble("volume", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                        liquidity=x.optString("liquidity").takeIf { it.isNotBlank() },
                        rollover=x.optString("rollover").takeIf { it.isNotBlank() },
                        providerId="backend", providerLabel=x.optString("source").ifBlank { "Ana Backend" },
                        isManual=false, currency=x.optString("currency").takeIf { it.isNotBlank() },
                        status=when(validity){ SignalValidity.VALID->"Doğrulanmış sözleşme"; SignalValidity.WATCH->"İzleme"; SignalValidity.INSUFFICIENT->"Yetersiz veri"; SignalValidity.REJECTED->"Reddedildi" },
                        dataTimestamp=dataTimestamp, isRealtime=realtime, delaySeconds=delay,
                        currentSessionIncluded=currentSession, receivedAt=receivedAt, dataMode=mode,
                        validity=validity, validityReason=reason,
                        lastTradingAt=x.optLong("lastTradingAt", 0L).takeIf { it > 0L },
                        expiryAt=x.optLong("expiryAt", 0L).takeIf { it > 0L },
                        exchangeTimezone=x.optString("exchangeTimezone").takeIf { it.isNotBlank() },
                        settlementType=x.optString("settlementType").takeIf { it.isNotBlank() }
                    ))
                }
            }
        }
    }

    suspend fun loadViopQuote(symbol: String): Result<ViopQuote> = withContext(Dispatchers.IO) {
        runCatching {
            val requestedSymbol = symbol.trim().uppercase()
            require(requestedSymbol.isNotBlank()) { "QUOTE_ERROR: İstenen VİOP sembolü boş." }
            val safe = URLEncoder.encode(requestedSymbol, "UTF-8")
            val x = readJson("/v1/viop/quote/$safe", "VİOP quote")
            val responseSymbol = x.optString("symbol").trim().uppercase()
            require(responseSymbol.isNotBlank()) { "QUOTE_ERROR: Quote cevabında sembol kimliği yok." }
            require(responseSymbol == requestedSymbol) { "QUOTE_ERROR: Sembol kimliği uyuşmuyor." }
            val price = x.optDouble("price", Double.NaN)
            require(price.isFinite() && price > 0.0) { "QUOTE_ERROR: Geçerli son fiyat yok." }
            val exchangeTs = x.optLong("exchangeTimestamp", 0L)
            require(exchangeTs > 0L) { "STALE_DATA: Piyasa veri zamanı eksik." }
            val realtime = x.optBoolean("realtime", false)
            val delay = if (x.has("delaySeconds") && !x.isNull("delaySeconds")) x.optInt("delaySeconds") else null
            val currentSession = x.optBoolean("currentSessionIncluded", false)
            val now = System.currentTimeMillis()
            require(realtime && currentSession) { "STALE_DATA: Gerçek zamanlı/seans verisi doğrulanmadı." }
            require(delay != null && delay in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS) { "STALE_DATA: Gecikme eşiği aşıldı veya bildirilmedi." }
            require(exchangeTs <= now + RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS) { "STALE_DATA: Piyasa zamanı gelecekte." }
            require(now - exchangeTs <= RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) { "STALE_DATA: Quote güncel değil." }
            ViopQuote(
                symbol=responseSymbol, price=price,
                bid=x.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                ask=x.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                dailyChangePct=x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
                volume=x.optDouble("volume", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                openInterest=x.optLong("openInterest", -1L).takeIf { it >= 0L },
                exchangeTimestamp=exchangeTs, receivedAt=x.optLong("receivedAt", System.currentTimeMillis()),
                source=x.optString("source").ifBlank { "Ana Backend" }, realtime=realtime,
                delaySeconds=delay, currentSessionIncluded=currentSession
            )
        }
    }

    suspend fun loadViopHistory(symbol: String): Result<List<Candle>> = loadViopHistoryFlexible(symbol, "1y", "1d", 220)

    suspend fun loadViopHistoryFlexible(symbol: String, range: String, interval: String, minimumBars: Int = 0): Result<List<Candle>> = withContext(Dispatchers.IO) {
        runCatching {
            require(range.matches(Regex("[0-9]+[dmy]"))) { "HISTORY_ERROR: Geçersiz range." }
            require(interval in setOf("1m","3m","5m","15m","60m","1d")) { "HISTORY_ERROR: Geçersiz interval." }
            val safe = URLEncoder.encode(symbol.uppercase(), "UTF-8")
            val requestedSymbol = symbol.trim().uppercase()
            require(requestedSymbol.isNotBlank()) { "HISTORY_ERROR: İstenen VİOP sembolü boş." }
            val root = readJson("/v1/viop/history/$safe?range=$range&interval=$interval", "VİOP history")
            val responseSymbol = root.optString("symbol").trim().uppercase()
            require(responseSymbol.isNotBlank()) { "HISTORY_ERROR: History cevabında sembol kimliği yok." }
            require(responseSymbol == requestedSymbol) { "HISTORY_ERROR: Sembol kimliği uyuşmuyor." }

            val responseInterval = root.optString("interval").trim()
            require(responseInterval.isNotBlank()) { "HISTORY_ERROR: History cevabında interval metadata yok." }
            require(responseInterval.equals(interval, ignoreCase = true)) {
                "HISTORY_ERROR: İstenen interval=$interval, backend=$responseInterval."
            }

            require(root.has("lastBarClosed") && !root.isNull("lastBarClosed")) {
                "HISTORY_ERROR: History cevabında lastBarClosed metadata yok."
            }
            require(root.optBoolean("lastBarClosed")) {
                "HISTORY_ERROR: Karar fiyatı için son history mumu kapalı değil."
            }

            val array = root.optJSONArray("candles") ?: error("HISTORY_ERROR: candles alanı yok.")
            val candles = ArrayList<Candle>(array.length())
            var previousTimestamp = Long.MIN_VALUE
            for (i in 0 until array.length()) {
                val x = array.optJSONObject(i)
                    ?: error("HISTORY_ERROR: candles[$i] nesne değil.")
                val timestamp = x.optLong("timestamp", 0L)
                require(timestamp > 0L) { "HISTORY_ERROR: candles[$i] timestamp geçersiz." }
                require(timestamp > previousTimestamp) {
                    if (timestamp == previousTimestamp) "HISTORY_ERROR: Duplicate timestamp: $timestamp"
                    else "HISTORY_ERROR: History kronolojik sırada değil."
                }
                val c = Candle(
                    timestamp,
                    x.optDouble("open", Double.NaN),
                    x.optDouble("high", Double.NaN),
                    x.optDouble("low", Double.NaN),
                    x.optDouble("close", Double.NaN),
                    x.optDouble("volume", Double.NaN)
                )
                require(listOf(c.open, c.high, c.low, c.close, c.volume).all { it.isFinite() }) {
                    "HISTORY_ERROR: candles[$i] sonlu olmayan OHLCV içeriyor."
                }
                require(c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0) {
                    "HISTORY_ERROR: candles[$i] fiyat alanları pozitif olmalı."
                }
                require(c.volume >= 0.0) { "HISTORY_ERROR: candles[$i] hacmi negatif." }
                require(c.high >= maxOf(c.open, c.close) && c.low <= minOf(c.open, c.close) && c.high >= c.low) {
                    "HISTORY_ERROR: candles[$i] OHLC aralığı tutarsız."
                }
                candles += c
                previousTimestamp = timestamp
            }

            val candleList = candles.toList()
            RealTimeIntegrityPolicy.validateCandles(candleList)?.let { error("HISTORY_ERROR: $it") }
            val lastBarTimestamp = candleList.lastOrNull()?.timestamp ?: 0L
            if (root.has("lastBarTimestamp") && !root.isNull("lastBarTimestamp")) {
                require(root.optLong("lastBarTimestamp", 0L) == lastBarTimestamp) {
                    "HISTORY_ERROR: lastBarTimestamp ile son mum timestamp'i uyuşmuyor."
                }
            }
            if(minimumBars>0) require(candleList.size>=minimumBars){"INSUFFICIENT_HISTORY: ${candleList.size} mum; minimum $minimumBars."}
            candleList
        }
    }

    suspend fun loadNews(symbol: String? = null, category: String = "ALL"): Result<List<NewsItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val symbolPart = symbol?.takeIf { it.isNotBlank() }?.let { "&symbol=" + URLEncoder.encode(it.uppercase(), "UTF-8") }.orEmpty()
            val cat = URLEncoder.encode(category.uppercase(), "UTF-8")
            val root = readJson("/v1/news?category=$cat$symbolPart", "Haber sağlayıcısı")
            val array = root.optJSONArray("items") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val x = array.optJSONObject(i) ?: continue
                    val title = x.optString("title").trim(); val source = x.optString("source").trim(); val published = x.optLong("publishedAt", 0L)
                    if (title.isBlank() || source.isBlank() || published <= 0L) continue
                    add(NewsItem(
                        id=x.optString("id").ifBlank { "$source:$published:$i" },
                        symbol=x.optString("symbol").takeIf { it.isNotBlank() }?.uppercase(),
                        category=x.optString("category").ifBlank { "GENEL" }.uppercase(),
                        title=title, summary=x.optString("summary").takeIf { it.isNotBlank() }, source=source, publishedAt=published,
                        url=x.optString("url").takeIf { it.startsWith("https://") }, verified=x.optBoolean("verified", false),
                        receivedAt=x.optLong("receivedAt", 0L).takeIf { it > 0L },
                        availableAt=x.optLong("availableAt", 0L).takeIf { it > 0L },
                        eventTime=x.optLong("eventTime", 0L).takeIf { it > 0L },
                        claimKey=x.optString("claimKey").takeIf { it.isNotBlank() },
                        eventKey=x.optString("eventKey").takeIf { it.isNotBlank() },
                        sourceType=x.optString("sourceType").takeIf { it.isNotBlank() },
                        originSourceId=x.optString("originSourceId").takeIf { it.isNotBlank() },
                        supportsClaim=x.optBoolean("supportsClaim", true)
                    ))
                }
            }.sortedByDescending { it.publishedAt }
        }
    }

}
