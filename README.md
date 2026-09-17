# BorsaTakip V5.2.5 B124

B124 FULL-FIX kaynak kodu ve GitHub Actions debug APK build sistemi.

## Build
- JDK 17
- Gradle 8.11.1
- compileSdk / targetSdk 35
- minSdk 26
- applicationId: tr.borsatakip.v5
- versionCode: 124
- versionName: 5.2.5

## VİOP veri bütünlüğü
- Production backend URL build-time doğrulaması
- Quote/history sembol kimliği fail-closed
- History interval metadata zorunlu
- Son history mumunun kapalı olduğu zorunlu
- Bozuk, duplicate ve kronolojik olarak hatalı mumlar reddedilir
- Karar fiyatında canlı quote fallback'i kullanılmaz
- MTF cache freshness exchange timestamp ile de doğrulanır
- Retry jitter uygulanır

API anahtarı ve özel imzalama bilgileri kaynak koda gömülmez; GitHub yapılandırması/secret mekanizmaları kullanılır.
