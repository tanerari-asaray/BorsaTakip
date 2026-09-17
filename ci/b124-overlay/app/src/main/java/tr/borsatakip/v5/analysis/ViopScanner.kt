package tr.borsatakip.v5.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.data.MtfHistoryCache
import tr.borsatakip.v5.data.ViopStrategyDecisionService
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.RiskPlan
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopScanError
import tr.borsatakip.v5.model.ViopScanProgress
import tr.borsatakip.v5.model.ViopScanResult
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class ViopScanner(
    private val backend: BackendProvider,
    private val marketProvider: MarketDataProvider? = null,
    private val strategyDecisionService: ViopStrategyDecisionService? = null
) {
    companion object {
        const val MIN_HISTORY_BARS = 220
        const val MIN_VOLUME = 1.0
        const val MIN_OPEN_INTEREST = 1L
        const val MAX_DATA_AGE_MS = 60_000L
        const val MTF_CONCURRENCY = 4
        const val MTF_TIMEOUT_MS = 12_000L
        const val MTF_1M_CACHE_TTL_MS = 30_000L
        const val MTF_1H_CACHE_TTL_MS = 5 * 60_000L

        fun isPastLastTradingAt(contract: ViopContract, nowMs: Long = System.currentTimeMillis()): Boolean =
            contract.lastTradingAt?.let { it <= nowMs } == true

        fun sortOpportunities(items: List<ViopOpportunity>): List<ViopOpportunity> =
            items.sortedWith(
                compareByDescending<ViopOpportunity> { it.rankingScore }
                    .thenByDescending { it.finalScore }
                    .thenBy { it.riskScore }
                    .thenBy { it.contract.symbol }
            )
    }

    suspend fun scan(onProgress: (ViopScanProgress) -> Unit = {}): ViopScanResult {
        val startedAt = System.currentTimeMillis()
        strategyDecisionService?.refreshOutcomes()
        val universeResult = backend.loadViop()
        if (universeResult.isFailure) {
            val err = ViopScanError("-", codeOf(universeResult.exceptionOrNull(), "CONTRACT_ERROR"), universeResult.exceptionOrNull()?.message ?: "VİOP sözleşme evreni alınamadı.")
            return ViopScanResult(emptyList(), listOf(err), ViopScanProgress(failed = 1), startedAt, System.currentTimeMillis(), ScanRunStatus.FAILED)
        }
        val universe = universeResult.getOrDefault(emptyList()).filter { !it.isManual }
        if (universe.isEmpty()) {
            val err = ViopScanError("-", "CONTRACT_ERROR", "Aktif Production VİOP sözleşme evreni boş.")
            return ViopScanResult(emptyList(), listOf(err), ViopScanProgress(), startedAt, System.currentTimeMillis(), ScanRunStatus.FAILED)
        }

        var p = ViopScanProgress(total = universe.size)
        val opportunities = mutableListOf<ViopOpportunity>()
        val errors = mutableListOf<ViopScanError>()
        onProgress(p)

        for (contract in universe) {
            if (isPastLastTradingAt(contract, startedAt)) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "EXPIRED", "Son işlem zamanı geçmiş sözleşme taramaya alınmadı.")
                onProgress(p)
                continue
            }
            if (contract.validity == SignalValidity.REJECTED) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "EXPIRED", contract.validityReason)
                onProgress(p)
                continue
            }

            val quoteResult = backend.loadViopQuote(contract.symbol)
            if (quoteResult.isFailure) {
                p = p.copy(failed = p.failed + 1)
                errors += ViopScanError(contract.symbol, codeOf(quoteResult.exceptionOrNull(), "QUOTE_ERROR"), quoteResult.exceptionOrNull()?.message ?: "Quote alınamadı.")
                onProgress(p)
                continue
            }
            val quote = quoteResult.getOrThrow()
            p = p.copy(quoteSuccess = p.quoteSuccess + 1)
            onProgress(p)

            val nowQuote = System.currentTimeMillis()
            val rawAge = nowQuote - quote.exchangeTimestamp
            if (rawAge < -tr.borsatakip.v5.data.RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "FUTURE_TIMESTAMP", "Quote piyasa zamanı gelecekte.")
                onProgress(p); continue
            }
            val age = rawAge.coerceAtLeast(0L)
            if (quote.delaySeconds == null || quote.delaySeconds !in 0..tr.borsatakip.v5.data.RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS || age > MAX_DATA_AGE_MS) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "STALE_DATA", "Quote veri yaşı ${age} ms; limit $MAX_DATA_AGE_MS ms.")
                onProgress(p)
                continue
            }

            val historyResult = backend.loadViopHistory(contract.symbol)
            if (historyResult.isFailure) {
                val code = codeOf(historyResult.exceptionOrNull(), "HISTORY_ERROR")
                p = if (code == "INSUFFICIENT_HISTORY") p.copy(insufficient = p.insufficient + 1) else p.copy(failed = p.failed + 1)
                errors += ViopScanError(contract.symbol, code, historyResult.exceptionOrNull()?.message ?: "History alınamadı.")
                onProgress(p)
                continue
            }
            val candles = historyResult.getOrThrow()
            p = p.copy(historySuccess = p.historySuccess + 1)
            onProgress(p)

            if (candles.size < MIN_HISTORY_BARS) {
                p = p.copy(insufficient = p.insufficient + 1)
                errors += ViopScanError(contract.symbol, "INSUFFICIENT_HISTORY", "${candles.size} mum; minimum $MIN_HISTORY_BARS.")
                onProgress(p)
                continue
            }

            val volume = quote.volume ?: contract.volume
            val oi = quote.openInterest ?: contract.openInterest
            if ((volume == null || volume < MIN_VOLUME) || (oi == null || oi < MIN_OPEN_INTEREST)) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "LOW_LIQUIDITY", "Hacim/açık pozisyon eşiği sağlanmadı.")
                onProgress(p)
                continue
            }

            val publishAge = System.currentTimeMillis() - quote.exchangeTimestamp
            if (publishAge < -tr.borsatakip.v5.data.RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS || publishAge > MAX_DATA_AGE_MS) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "STALE_DATA", "History sonrası quote tazeliğini kaybetti.")
                onProgress(p); continue
            }
            if (contract.lastTradingAt == null) {
                p = p.copy(insufficient = p.insufficient + 1)
                errors += ViopScanError(contract.symbol, "EXPIRY_UNVERIFIED", "Gerçek son işlem zamanı sağlayıcı tarafından verilmedi.")
                onProgress(p); continue
            }

            val decisionPrice = DecisionPricePolicy.historyBarPrice(candles)
            if (decisionPrice == null) {
                p = p.copy(failed = p.failed + 1)
                errors += ViopScanError(contract.symbol, "HISTORY_ERROR", "Karar fiyatı için geçerli kapanmış history mumu yok.")
                onProgress(p)
                continue
            }
            val technical = TechnicalAnalyzer.analyze(candles)
            val opportunity = score(contract, quote, candles, technical, publishAge.coerceAtLeast(0L), decisionPrice)
            p = p.copy(
                analyzed = p.analyzed + 1,
                longCount = p.longCount + if (opportunity.direction == "LONG") 1 else 0,
                shortCount = p.shortCount + if (opportunity.direction == "SHORT") 1 else 0
            )
            opportunities += opportunity
            onProgress(p)
        }

        val regime = loadMarketRegime()
        val contextual = applyDecisionContext(opportunities, regime)
        val optimized = try {
            strategyDecisionService?.apply(contextual) ?: contextual
        } catch (_: Throwable) {
            contextual.map { it.copy(ensembleStatus = "FALLBACK_COMBINED • OPTIMIZATION_ERROR") }
        }
        val sorted = sortOpportunities(optimized)
        strategyDecisionService?.record(sorted)
        val completedAt = System.currentTimeMillis()
        val status = when {
            sorted.isEmpty() && p.failed > 0 -> ScanRunStatus.FAILED
            p.failed > 0 || p.insufficient > 0 || p.eliminated > 0 -> ScanRunStatus.PARTIAL
            else -> ScanRunStatus.COMPLETE
        }
        return ViopScanResult(sorted, errors, p, startedAt, completedAt, status)
    }

    private fun score(
        contract: ViopContract,
        quote: tr.borsatakip.v5.model.ViopQuote,
        candles: List<Candle>,
        t: tr.borsatakip.v5.model.TechnicalSnapshot,
        age: Long,
        price: Double
    ): ViopOpportunity {
        var longTech = 0
        var shortTech = 0
        if (t.ema20 != null && t.ema50 != null && t.ema200 != null) {
            if (t.ema20 > t.ema50 && t.ema50 > t.ema200 && price >= t.ema20) longTech += 25
            if (t.ema20 < t.ema50 && t.ema50 < t.ema200 && price <= t.ema20) shortTech += 25
        }
        if (t.rsi14 != null) {
            if (t.rsi14 in 50.0..72.0) longTech += 10
            if (t.rsi14 in 28.0..50.0) shortTech += 10
        }
        if (t.macd != null && t.macdSignal != null) {
            if (t.macd > t.macdSignal) longTech += 10 else if (t.macd < t.macdSignal) shortTech += 10
        }
        val momentum = if (t.ema20 != null && t.ema20 != 0.0) ((price - t.ema20) / abs(t.ema20)) else 0.0
        val momentumLong = when { momentum > 0.03 -> 15; momentum > 0.0 -> 8; else -> 0 }
        val momentumShort = when { momentum < -0.03 -> 15; momentum < 0.0 -> 8; else -> 0 }
        val structure = ViopBreakoutPolicy.evaluate(candles, t.volumeRatio)
        val volumeAnomalyPct = structure.volumeAnomalyPct
        val breakoutState = structure.state
        val breakoutConfirmed = structure.confirmed
        val breakoutLong = structure.longContribution
        val breakoutShort = structure.shortContribution
        val liquidity = liquidityScore(quote.volume ?: contract.volume, quote.openInterest ?: contract.openInterest)
        val volatility = volatilityScore(t.atr14, price)
        val dataQuality = when {
            age <= 5_000L -> 10
            age <= 30_000L -> 8
            age <= MAX_DATA_AGE_MS -> 5
            else -> 0
        }
        val oiScore = openInterestScore(quote.openInterest ?: contract.openInterest)
        val expiryRisk = expiryRisk(contract.lastTradingAt)
        val expiryContribution = ((100 - expiryRisk) * 15 / 100.0).roundToInt()
        val liquidityContribution = (liquidity * 15 / 100.0).roundToInt()
        val longScore = (longTech + momentumLong + breakoutLong + liquidityContribution + volatility + dataQuality + oiScore + expiryContribution).coerceIn(0, 100)
        val shortScore = (shortTech + momentumShort + breakoutShort + liquidityContribution + volatility + dataQuality + oiScore + expiryContribution).coerceIn(0, 100)
        val direction = if (longScore >= shortScore) "LONG" else "SHORT"
        val final = maxOf(longScore, shortScore)
        val technicalScore = maxOf(longTech + momentumLong, shortTech + momentumShort).coerceIn(0, 100)
        val risk = ((100 - liquidity).coerceAtLeast(0) * 0.25 + expiryRisk * 0.45 + (10 - dataQuality) * 3.0).roundToInt().coerceIn(0,100)
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val stop = atr?.let { if (direction == "LONG") price - 1.25 * it else price + 1.25 * it }
        val target1 = atr?.let { if (direction == "LONG") price + 1.5 * it else price - 1.5 * it }
        val target2 = atr?.let { if (direction == "LONG") price + 2.5 * it else price - 2.5 * it }
        val riskAmount = stop?.let { abs(price - it) }?.takeIf { it > 1e-9 }
        val rr1 = if (riskAmount != null && target1 != null) abs(target1 - price) / riskAmount else null
        val rr2 = if (riskAmount != null && target2 != null) abs(target2 - price) / riskAmount else null
        val riskPlan = RiskPlan(price, stop, target1, target2, rr1, rr2)
        val spreadOk = quote.bid != null && quote.ask != null && quote.ask >= quote.bid && quote.bid > 0.0
        val dataConfidence = (
            25 + 20 +
                (if (candles.size >= MIN_HISTORY_BARS) 20 else 0) +
                (if ((quote.volume ?: contract.volume ?: 0.0) > 0.0) 10 else 0) +
                (if ((quote.openInterest ?: contract.openInterest ?: 0L) > 0L) 10 else 0) +
                (if (contract.lastTradingAt != null) 10 else 0) +
                (if (spreadOk) 5 else 0)
            ).coerceIn(0, 100)
        val decisionState = if (final >= 70 && risk <= 60 && dataConfidence >= 75) DecisionState.VERIFIED_OPPORTUNITY else DecisionState.WATCH
        val rankingScore = (final * 0.50 + dataConfidence * 0.20 + liquidity * 0.15 + (100 - risk) * 0.10 + dataQuality * 10.0 * 0.05).roundToInt().coerceIn(0, 100)
        val reason = buildList {
            add("$direction skoru $final/100")
            if (t.ema20 != null && t.ema50 != null && t.ema200 != null) add("EMA20/50/200 trendi değerlendirildi")
            if (t.rsi14 != null) add("RSI14=${"%.1f".format(t.rsi14)}")
            if (t.macd != null && t.macdSignal != null) add("MACD yönü değerlendirildi")
            add("Likidite=$liquidity/100")
            add("Vade riski=$expiryRisk/100")
            add("Veri güveni=$dataConfidence/100")
            volumeAnomalyPct?.let { add("Hacim anomalisi=${"%+.0f".format(it)}%") }
            if (breakoutState != "YOK") add("$breakoutState${if (breakoutConfirmed) " • hacim teyitli" else " • hacim teyitsiz"}")
            add("Sıralama=$rankingScore/100")
            add("Veri yaşı=${age/1000}s")
        }.joinToString(" • ")
        return ViopOpportunity(
            contract=contract, quote=quote, candles=candles, technical=t,
            technicalScore=technicalScore, riskScore=risk, liquidityScore=liquidity,
            expiryRisk=expiryRisk, longScore=longScore, shortScore=shortScore,
            finalScore=final, direction=direction, signalReason=reason,
            validity=SignalValidity.VALID, dataAgeMs=age, historyCandleCount=candles.size,
            decisionState=decisionState, dataConfidenceScore=dataConfidence, riskPlan=riskPlan, rankingScore=rankingScore,
            volumeAnomalyPct=volumeAnomalyPct, breakoutState=breakoutState, breakoutConfirmed=breakoutConfirmed,
            decisionPrice=price
        )
    }

    private suspend fun loadMarketRegime(): MarketRegimeEngine.Result? {
        val provider = marketProvider ?: return null
        for (symbol in listOf("XU100", "XU100.IS")) {
            val candles = try { provider.fetchDailyHistory(symbol, maximumRange = false) } catch (_: Throwable) { emptyList() }
            if (candles.size >= 60) return MarketRegimeEngine.evaluate(symbol, candles)
        }
        return null
    }

    private suspend fun applyDecisionContext(
        items: List<ViopOpportunity>,
        regime: MarketRegimeEngine.Result?
    ): List<ViopOpportunity> = supervisorScope {
        if (items.isEmpty()) return@supervisorScope items

        val semaphore = Semaphore(MTF_CONCURRENCY)
        val evaluated = items.map { opportunity ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val mtf = withTimeoutOrNull(MTF_TIMEOUT_MS) {
                        val symbol = opportunity.contract.symbol
                        val oneMin = MtfHistoryCache.loadFresh(
                            sourceKey = "VIOP_BACKEND",
                            symbol = symbol,
                            timeframe = "1m",
                            ttlMs = MTF_1M_CACHE_TTL_MS
                        ) {
                            backend.loadViopHistoryFlexible(symbol, "7d", "1m", 0).getOrDefault(emptyList())
                        }
                        val oneHour = MtfHistoryCache.loadFresh(
                            sourceKey = "VIOP_BACKEND",
                            symbol = symbol,
                            timeframe = "60m",
                            ttlMs = MTF_1H_CACHE_TTL_MS
                        ) {
                            backend.loadViopHistoryFlexible(symbol, "120d", "60m", 0).getOrDefault(emptyList())
                        }
                        val clean1 = OhlcvResampler.sanitize(oneMin)
                        MtfConsensusEngine.evaluate(
                            listOf(
                                MtfConsensusEngine.Input("1 DK", clean1, 0.08),
                                MtfConsensusEngine.Input("3 DK", OhlcvResampler.aggregate(clean1, 1, 3), 0.12),
                                MtfConsensusEngine.Input("5 DK", OhlcvResampler.aggregate(clean1, 1, 5), 0.15),
                                MtfConsensusEngine.Input("15 DK", OhlcvResampler.aggregate(clean1, 1, 15), 0.20),
                                MtfConsensusEngine.Input("1 SA", OhlcvResampler.sanitize(oneHour), 0.20),
                                MtfConsensusEngine.Input("1 GÜN", OhlcvResampler.sanitize(opportunity.candles), 0.25)
                            )
                        )
                    }
                    opportunity.contract.symbol to mtf
                }
            }
        }.map { it.await() }.toMap()

        items.map { opportunity ->
            val mtf = evaluated[opportunity.contract.symbol]
            val mtfAdj = mtf?.takeIf { it.availableCount >= 3 }
                ?.let { MtfConsensusEngine.rankingAdjustment(opportunity.direction, it) } ?: 0
            val regimeAdj = regime?.let { MarketRegimeEngine.rankingAdjustment(opportunity.direction, it) } ?: 0
            val suffix = buildList {
                if (mtf != null && mtf.availableCount >= 3) add("MTF=${mtf.label} (${mtf.score})")
                if (regime != null && regime.regime != MarketRegimeEngine.Regime.UNKNOWN) add("Rejim=${regime.regime.label} (${regime.confidence})")
            }.joinToString(" • ")
            opportunity.copy(
                rankingScore = (opportunity.rankingScore + mtfAdj + regimeAdj).coerceIn(0, 100),
                mtfConsensusScore = mtf?.takeIf { it.availableCount >= 3 }?.score,
                mtfConsensusLabel = mtf?.takeIf { it.availableCount >= 3 }?.label ?: "MTF VERİ YOK",
                marketRegime = regime?.regime?.label ?: "VERİ YOK",
                marketRegimeConfidence = regime?.confidence ?: 0,
                signalReason = if (suffix.isBlank()) opportunity.signalReason else opportunity.signalReason + " • " + suffix
            )
        }
    }

    private fun liquidityScore(volume: Double?, oi: Long?): Int {
        val v = volume ?: 0.0
        val o = oi ?: 0L
        val volumePct = when { v >= 100_000 -> 100; v >= 50_000 -> 85; v >= 10_000 -> 70; v >= 1_000 -> 45; v > 0 -> 20; else -> 0 }
        val oiPct = when { o >= 50_000 -> 100; o >= 25_000 -> 85; o >= 10_000 -> 70; o >= 1_000 -> 45; o > 0 -> 20; else -> 0 }
        return ((volumePct + oiPct) / 2).coerceIn(0, 100)
    }

    private fun openInterestScore(oi: Long?): Int = when (oi ?: 0L) {
        in 100_000L..Long.MAX_VALUE -> 10
        in 25_000L until 100_000L -> 8
        in 5_000L until 25_000L -> 5
        in 1L until 5_000L -> 2
        else -> 0
    }

    private fun volatilityScore(atr: Double?, price: Double): Int {
        if (atr == null || atr <= 0 || price <= 0) return 0
        val pct = atr / price
        return when {
            pct in 0.005..0.04 -> 10
            pct in 0.002..0.08 -> 6
            else -> 2
        }
    }

    private fun expiryRisk(lastTradingAt: Long?): Int {
        val at = lastTradingAt ?: return 100
        val days = ChronoUnit.DAYS.between(LocalDate.now(), java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()).toInt()
        return when { days < 0 -> 100; days <= 5 -> 90; days <= 10 -> 75; days <= 20 -> 55; days <= 40 -> 35; else -> 20 }
    }

    private fun codeOf(t: Throwable?, fallback: String): String {
        val text = t?.message.orEmpty().uppercase()
        return listOf("AUTH_ERROR","CONTRACT_ERROR","QUOTE_ERROR","HISTORY_ERROR","INSUFFICIENT_HISTORY","STALE_DATA","LOW_LIQUIDITY","EXPIRED","RATE_LIMIT","SERVER_ERROR").firstOrNull { text.contains(it) } ?: fallback
    }
}
