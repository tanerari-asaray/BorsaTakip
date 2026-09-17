# BorsaTakip V5.2.5 B124 CORRECTED-V4

B124 FULL-FIX kaynak kodu ve GitHub Actions debug APK build sistemi.

## Build
- JDK 17
- Gradle 8.11.1
- compileSdk / targetSdk 35
- minSdk 26
- applicationId: tr.borsatakip.v5
- versionCode: 124
- versionName: 5.2.5

Yerel/CI komutları:

```bash
./gradlew clean
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
```

GitHub Actions workflow:

`.github/workflows/android-build.yml`

Workflow `workflow_dispatch` ile manuel, ayrıca `main` branch push işlemlerinde çalışır. Başarılı build sonrasında gerçek DEBUG APK `BorsaTakip-B124-CORRECTED-V4-debug` artifact'i olarak yüklenir.

## VİOP veri bütünlüğü
- Production backend URL build-time doğrulaması
- Quote/history sembol kimliği fail-closed
- History interval metadata zorunluluğu
- Backend `lastBarClosed=true` zorunluluğu
- Son history mumunun timestamp + interval ile bağımsız kapanış doğrulaması
- Bozuk, duplicate ve kronolojik olarak hatalı mumların reddedilmesi
- Karar fiyatında canlı quote fallback kullanılmaması
- History exchange timestamp freshness kontrolü
- MTF cache freshness kontrolünün exchange timestamp ile yapılması
- Günlük timeframe için kontrollü hafta sonu/tatil toleransı
- Gerçek cache hit/miss telemetry
- VİOP processed telemetry'nin terminal sonuçlara göre hesaplanması
- Retry jitter

API anahtarı ve özel imzalama bilgileri kaynak koda gömülmez; GitHub yapılandırması/secret mekanizmaları kullanılır.

## Doğrulama sınırı
Kaynak kodda statik olarak 283 JVM + 3 Android instrumentation = 286 `@Test` bulunduğu raporlanmıştır. Bu sayı, testlerin çalıştırıldığı anlamına gelmez. Gerçek test sonucu ve APK yalnızca GitHub Actions Gradle build çıktısı başarılı olduğunda doğrulanmış kabul edilir.

Production backend E2E ve fiziksel cihaz testi bu workflow kapsamında çalıştırılmaz.
