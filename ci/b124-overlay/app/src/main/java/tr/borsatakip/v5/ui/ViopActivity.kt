package tr.borsatakip.v5.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopScanner
import tr.borsatakip.v5.analysis.ViopUnderlyingScanner
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.AlertEventStore
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.data.ViopStrategyDecisionService
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopQuote
import tr.borsatakip.v5.model.ViopScanProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopActivity : BaseActivity() {
    private lateinit var backend: BackendProvider
    private lateinit var scanner: ViopScanner
    private lateinit var underlyingScanner: ViopUnderlyingScanner
    private lateinit var readiness: ProviderReadinessService
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView
    private lateinit var scanButton: Button
    private var previewContract: ViopContract? = null
    private var previewQuote: ViopQuote? = null
    private var providerAutoTestInFlight = false
    private var lastProviderAutoTestAt = 0L
    private var underlyingScanInFlight = false
    private var underlyingScanCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()
        backend = BackendProvider(this)
        scanner = ViopScanner(backend, ProviderRouter(this), ViopStrategyDecisionService(this, backend))
        underlyingScanner = ViopUnderlyingScanner(ProviderRouter(this, experimentalFallbackOverride = true))
        readiness = ProviderReadinessService(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        scanButton = findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        findViewById<TextView>(R.id.openSettings).setOnClickListener { openViopSettings() }
        findViewById<Button>(R.id.openContracts).setOnClickListener { startActivity(Intent(this, ViopContractsActivity::class.java)) }
        findViewById<Button>(R.id.openExpiries).setOnClickListener { startActivity(Intent(this, ViopContractsActivity::class.java)) }
        findViewById<Button>(R.id.openAnalysis).setOnClickListener {
            previewContract?.let {
                AppSession.selectedViopContract = it
                startActivity(Intent(this, ViopDetailActivity::class.java))
            } ?: run { status.text = "Analiz için önce gerçek bir VİOP sözleşmesi yüklenmelidir." }
        }
        findViewById<Button>(R.id.openSignals).setOnClickListener { startOrValidateScan() }
        scanButton.setOnClickListener { startOrValidateScan() }
        findViewById<Button>(R.id.addContract).visibility = if (BuildConfig.DEBUG && SettingsStore(this).experimentalProvidersEnabled) View.VISIBLE else View.GONE
        refreshProviderState()
        recoverProviderAndLoad()
    }

    override fun onResume() {
        super.onResume()
        refreshProviderState()
        if (::readiness.isInitialized) recoverProviderAndLoad()
    }


    private fun openViopSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_RETURN_TO_VIOP, true))
    }

    private fun recoverProviderAndLoad() {
        if (!::readiness.isInitialized || providerAutoTestInFlight) return
        when (readiness.localConfigState().state) {
            ProviderState.PROVIDER_READY -> if (previewQuote == null) loadDashboardPreview()
            ProviderState.PROVIDER_CONFIGURED, ProviderState.PROVIDER_STALE_READY, ProviderState.PROVIDER_ERROR -> {
                val now = System.currentTimeMillis()
                if (now - lastProviderAutoTestAt < AUTO_TEST_COOLDOWN_MS) return
                lastProviderAutoTestAt = now
                providerAutoTestInFlight = true
                status.text = "VİOP provider otomatik doğrulanıyor: HTTPS → Health → Authentication → Contracts → Quote → History"
                lifecycleScope.launch {
                    val result = readiness.test()
                    providerAutoTestInFlight = false
                    refreshProviderState()
                    if (result.state == ProviderState.PROVIDER_READY) loadDashboardPreview()
                    else status.text = "${result.failureCode} • ${result.message}"
                }
            }
            ProviderState.PROVIDER_NOT_CONFIGURED -> {
                clearPreview()
                if (!underlyingScanInFlight && !underlyingScanCompleted) {
                    runUnderlyingScan("Production VİOP backend bağlı değil")
                }
            }
            ProviderState.PROVIDER_TESTING -> Unit
        }
    }

    private fun startOrValidateScan() {
        when (val s = readiness.localConfigState()) {
            else -> when (s.state) {
                ProviderState.PROVIDER_NOT_CONFIGURED -> runUnderlyingScan("Production VİOP backend bağlı değil")
                ProviderState.PROVIDER_READY -> runOpportunityScan()
                ProviderState.PROVIDER_TESTING -> status.text = "Provider bağlantı testi devam ediyor..."
                ProviderState.PROVIDER_CONFIGURED, ProviderState.PROVIDER_STALE_READY, ProviderState.PROVIDER_ERROR -> testThenScan()
            }
        }
    }

    private fun testThenScan() {
        scanButton.isEnabled = false
        status.text = "Provider doğrulanıyor: HTTPS → Health → Authentication → VİOP Contracts → Quote → History"
        lifecycleScope.launch {
            val r = readiness.test()
            scanButton.isEnabled = true
            refreshProviderState()
            if (r.state == ProviderState.PROVIDER_READY) {
                loadDashboardPreview()
                runOpportunityScan()
            } else {
                clearPreview()
                status.text = "VİOP provider doğrulanamadı • ${r.failureCode} • ${r.message}"
            }
        }
    }

    private fun refreshProviderState() {
        val s = readiness.localConfigState()
        val title = findViewById<TextView>(R.id.providerTitle)
        val dot = findViewById<TextView>(R.id.providerDot)
        val live = s.state == ProviderState.PROVIDER_READY && previewQuote?.realtime == true
        if (live) {
            title.text = "CANLI VERİ"
            title.setTextColor(Color.parseColor("#56F1C1"))
            dot.setTextColor(Color.parseColor("#00F080"))
            providerStatus.text = "Production backend • doğrulanmış gerçek zamanlı VİOP akışı"
        } else {
            title.text = when (s.state) {
                ProviderState.PROVIDER_NOT_CONFIGURED -> "DAYANAK TARAMASI HAZIR"
                ProviderState.PROVIDER_ERROR -> "VİOP VERİ HATASI"
                ProviderState.PROVIDER_TESTING -> "BAĞLANTI TEST EDİLİYOR"
                ProviderState.PROVIDER_STALE_READY -> "VERİ YENİDEN DOĞRULANMALI"
                else -> "VİOP VERİSİ BEKLENİYOR"
            }
            val error = s.state == ProviderState.PROVIDER_ERROR || s.state == ProviderState.PROVIDER_NOT_CONFIGURED
            title.setTextColor(Color.parseColor(if (error) "#FF6B72" else "#AFC2D4"))
            dot.setTextColor(Color.parseColor(if (error) "#FF4545" else "#7F9AB0"))
            providerStatus.text = "${s.failureCode} • ${s.message}"
        }
        scanButton.text = when (s.state) {
            ProviderState.PROVIDER_NOT_CONFIGURED -> if (underlyingScanCompleted) "DAYANAK TARAMASINI YENİLE" else "DAYANAK TARAMASINI BAŞLAT"
            ProviderState.PROVIDER_READY -> "VİOP TARAMASINI BAŞLAT"
            ProviderState.PROVIDER_STALE_READY -> "YENİDEN DOĞRULA"
            ProviderState.PROVIDER_ERROR -> "DAYANAK TARAMASI / TEKRAR DENE"
            ProviderState.PROVIDER_TESTING -> "PROVIDER TEST EDİLİYOR"
            ProviderState.PROVIDER_CONFIGURED -> "BAĞLANTIYI TEST ET"
        }
        scanButton.isEnabled = s.state != ProviderState.PROVIDER_TESTING
    }

    private fun loadDashboardPreview() {
        lifecycleScope.launch {
            status.text = "Gerçek VİOP sözleşme ve yakın vade verisi yükleniyor..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val contracts = backend.loadViop().getOrThrow()
                    val c = contracts.filter { it.validity != SignalValidity.REJECTED }
                        .sortedWith(compareBy<ViopContract> { it.lastTradingAt ?: it.expiryAt ?: Long.MAX_VALUE }.thenByDescending { it.volume ?: 0.0 })
                        .firstOrNull() ?: error("Aktif VİOP sözleşmesi bulunamadı.")
                    val q = backend.loadViopQuote(c.symbol).getOrThrow()
                    val h = backend.loadViopHistory(c.symbol).getOrElse { emptyList() }
                    Triple(c, q, h)
                }
            }
            result.onSuccess { (c, q, h) ->
                previewContract = c
                previewQuote = q
                AppSession.selectedViopContract = c
                renderPreview(c, q, h)
                refreshProviderState()
                status.text = "Gerçek VİOP verisi yüklendi • ${c.symbol} • ${q.source}"
            }.onFailure {
                previewContract = null
                previewQuote = null
                clearPreview()
                refreshProviderState()
                status.text = "VİOP önizlemesi yüklenemedi • ${it.message ?: "Bilinmeyen hata"}"
            }
        }
    }

    private fun renderPreview(c: ViopContract, q: ViopQuote, candles: List<Candle>) {
        val last = candles.lastOrNull()
        findViewById<TextView>(R.id.heroTitle).text = c.underlying.takeIf { it.isNotBlank() && it != "-" } ?: "YAKIN VADE"
        findViewById<TextView>(R.id.heroContract).text = "${c.symbol} • ${c.expiry}"
        findViewById<TextView>(R.id.heroExpiry).text = c.lastTradingAt?.let { "Son işlem ${shortDate(it)}" } ?: "Vade ${c.expiry}"
        findViewById<TextView>(R.id.heroPrice).text = formatPrice(q.price)
        findViewById<TextView>(R.id.heroChange).apply {
            text = q.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "Değişim verisi yok"
            setTextColor(when { q.dailyChangePct == null -> Color.parseColor("#AFC2D4"); q.dailyChangePct!! >= 0 -> Color.parseColor("#00F080"); else -> Color.parseColor("#FF5A66") })
        }
        findViewById<ViopSparklineView>(R.id.heroSparkline).setCandles(candles)
        findViewById<TextView>(R.id.metricOpen).text = "Açılış\n${last?.open?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricHigh).text = "En Yüksek\n${last?.high?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricLow).text = "En Düşük\n${last?.low?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricBid).text = "Alış\n${q.bid?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricAsk).text = "Satış\n${q.ask?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricVolume).text = "Hacim  ${q.volume?.let(::formatCompact) ?: c.volume?.let(::formatCompact) ?: "—"}"
        findViewById<TextView>(R.id.metricOi).text = "Açık Poz.  ${q.openInterest ?: c.openInterest ?: "—"}"
        findViewById<TextView>(R.id.metricBasis).text = "Basis  —"
        findViewById<TextView>(R.id.providerLastUpdate).text = timeOf(q.exchangeTimestamp)
        val age = (System.currentTimeMillis() - q.exchangeTimestamp).coerceAtLeast(0L)
        findViewById<TextView>(R.id.providerDataAge).text = if (age < 1000) "$age ms" else "%.1f sn".format(age / 1000.0)
        findViewById<TextView>(R.id.providerLatency).text = q.delaySeconds?.let { "$it sn" } ?: "—"
        findViewById<TextView>(R.id.marketContracts).text = "Kontrat\n${c.symbol}"
        findViewById<TextView>(R.id.marketTrend).text = "Veri\n${if (q.realtime) "Canlı" else "Gecikmeli"}"
    }

    private fun clearPreview() {
        findViewById<TextView>(R.id.heroTitle).text = "YAKIN VADE"
        findViewById<TextView>(R.id.heroContract).text = "Canlı sözleşme bekleniyor"
        findViewById<TextView>(R.id.heroExpiry).text = "Vade —"
        findViewById<TextView>(R.id.heroPrice).text = "—"
        findViewById<TextView>(R.id.heroChange).text = "Gerçek fiyat verisi bekleniyor"
        findViewById<ViopSparklineView>(R.id.heroSparkline).setCandles(emptyList())
        listOf(R.id.metricOpen to "Açılış", R.id.metricHigh to "En Yüksek", R.id.metricLow to "En Düşük", R.id.metricBid to "Alış", R.id.metricAsk to "Satış").forEach { (id, label) -> findViewById<TextView>(id).text = "$label\n—" }
        findViewById<TextView>(R.id.metricVolume).text = "Hacim  —"
        findViewById<TextView>(R.id.metricOi).text = "Açık Poz.  —"
        findViewById<TextView>(R.id.metricBasis).text = "Basis  —"
        findViewById<TextView>(R.id.providerLastUpdate).text = "—"
        findViewById<TextView>(R.id.providerDataAge).text = "—"
        findViewById<TextView>(R.id.providerLatency).text = "—"
    }

    private fun runOpportunityScan() {
        if (readiness.localConfigState().state != ProviderState.PROVIDER_READY) return
        scanButton.isEnabled = false
        status.text = "Provider READY • aktif sözleşme evreni alınıyor..."
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { scanner.scan { p -> runOnUiThread { status.text = progressText(p) } } }
                AppSession.lastViopOpportunities = r.opportunities
                AlertEventStore(this@ViopActivity).evaluateViop(r.opportunities)
                renderOpportunities(r.opportunities)
                status.text = buildString {
                    append("${r.status.name} • Production VİOP taraması tamamlandı\n")
                    append(progressText(r.progress))
                    append("\nFırsat: ${r.opportunities.size} • LONG: ${r.progress.longCount} • SHORT: ${r.progress.shortCount}")
                    if (r.opportunities.isEmpty()) append("\nKalite koşullarını geçen fırsat yok; sahte sinyal üretilmedi.")
                }
            } catch (t: Throwable) {
                status.text = "VİOP taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
            } finally {
                scanButton.isEnabled = true
                refreshProviderState()
            }
        }
    }

    private fun runUnderlyingScan(reason: String) {
        if (underlyingScanInFlight) return
        underlyingScanInFlight = true
        scanButton.isEnabled = false
        status.text = "$reason • BIST dayanakları teknik olarak taranıyor..."
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { underlyingScanner.scan() }
                list.adapter = ViopUnderlyingOpportunityAdapter(result.items)
                findViewById<TextView>(R.id.emptySignals).visibility = if (result.items.isEmpty()) View.VISIBLE else View.GONE
                val longs = result.items.count { it.underlying.direction.equals("LONG", true) }
                val shorts = result.items.count { it.underlying.direction.equals("SHORT", true) }
                findViewById<TextView>(R.id.signalSummary).text = if (result.items.isEmpty()) "Dayanak verisi alınamadı" else "${result.items.size} dayanak • $longs LONG • $shorts SHORT"
                findViewById<TextView>(R.id.marketSignals).text = "Dayanak\n${result.items.size}"
                findViewById<TextView>(R.id.marketTrend).text = when {
                    longs > shorts -> "Eğilim\nLONG"
                    shorts > longs -> "Eğilim\nSHORT"
                    else -> "Eğilim\nDENGELİ"
                }
                status.text = buildString {
                    append("DAYANAK TARAMASI TAMAMLANDI • VİOP fiyatı üretilmedi\n")
                    append("Denenen ${result.attempted} • Analiz ${result.resolved} • Veri yok ${result.failed}\n")
                    append("Gerçek VİOP fırsatı için production kontrat quote/history gerekir.")
                }
            } catch (t: Throwable) {
                status.text = "Dayanak taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
            } finally {
                underlyingScanInFlight = false
                underlyingScanCompleted = true
                scanButton.isEnabled = true
                refreshProviderState()
            }
        }
    }

    private fun renderOpportunities(items: List<ViopOpportunity>) {
        val sorted = ViopScanner.sortOpportunities(items)
        list.adapter = ViopOpportunityAdapter(sorted) {
            AppSession.selectedViopOpportunity = it
            AppSession.selectedViopContract = it.contract
            startActivity(Intent(this, ViopDetailActivity::class.java))
        }
        findViewById<TextView>(R.id.emptySignals).visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        val strong = sorted.count { it.finalScore >= 85 && it.validity == SignalValidity.VALID }
        val longs = sorted.count { it.direction.equals("LONG", true) }
        val shorts = sorted.count { it.direction.equals("SHORT", true) }
        findViewById<TextView>(R.id.signalSummary).text = if (sorted.isEmpty()) "Kaliteli sinyal yok" else "$strong güçlü • $longs LONG • $shorts SHORT"
        findViewById<TextView>(R.id.marketSignals).text = "Sinyal\n${sorted.size}"
        findViewById<TextView>(R.id.marketTrend).text = when {
            sorted.isEmpty() -> "Durum\nBEKLE"
            longs > shorts -> "Eğilim\nLONG"
            shorts > longs -> "Eğilim\nSHORT"
            else -> "Eğilim\nDENGELİ"
        }
    }

    private fun progressText(p: ViopScanProgress) = "Toplam ${p.total} • Quote ${p.quoteSuccess} • History ${p.historySuccess} • Analiz ${p.analyzed} • Yetersiz ${p.insufficient} • Elenen ${p.eliminated} • Hata ${p.failed}"
    private fun formatPrice(v: Double) = if (v >= 1000.0) "%,.2f".format(v) else "%.2f".format(v)
    private fun formatCompact(v: Double) = when { v >= 1_000_000 -> "%.1f M".format(v / 1_000_000.0); v >= 1_000 -> "%.1f K".format(v / 1_000.0); else -> "%.0f".format(v) }
    private fun timeOf(ms: Long) = if (ms <= 0) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
    private fun shortDate(ms: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))

    companion object {
        private const val AUTO_TEST_COOLDOWN_MS = 30_000L
    }

}
