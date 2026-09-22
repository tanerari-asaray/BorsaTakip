# BorsaTakip Production Backend

Android V5 backend contract for:

- GET /health
- GET /v1/viop/contracts
- GET /v1/viop/quote/{symbol}
- GET /v1/viop/history/{symbol}?range=1y&interval=1d
- GET /v1/news

## Production data source

This backend uses the TradeWize Developer API as the upstream market-data provider. The upstream access token is supplied only through the server environment variable `TRADEWIZE_API_KEY` (tercih edilen) veya `TRADEWIZE_ACCESS_TOKEN`; it is not committed to Git or embedded in the Android APK.

The Android application's API key field is mapped to `APP_API_KEY` at the backend boundary. If `APP_API_KEY` is empty, the backend accepts the requests without an application-level bearer check.

## Important

A valid TradeWize Developer API credential is required for market data. The backend deliberately does not fabricate real-time, bid/ask, volume, tick-size or multiplier metadata when the upstream does not provide it.

The Android V5 client therefore may display a contract as "İzleme/Yetersiz veri" until the contract metadata source is expanded. Quote and closed-bar endpoints remain available when upstream data is present.


### Kimlik doğrulama

Backend, tercihen `TRADEWIZE_API_KEY` ile çalışır. Sunucu API anahtarını istemciye açmadan TradeWize `/oauth/token` üzerinden JWT access token alır ve süresi yaklaşınca yeniler. TradeWize dokümantasyonuna göre API anahtarı istemci tarafına gömülmemelidir.

`APP_API_KEY` ise Android uygulamasının backend'e erişimini sınırlamak için ayrı bir uygulama sırrıdır. Bu değer de yalnızca Render ortam değişkeninde tutulmalıdır.


### Dynamic market provider / scanner

The production backend now exposes:

- GET /v1/provider/capabilities
- GET /v1/bist/symbols
- GET /v1/bist/quote/{symbol}
- GET /v1/scanner/opportunities
- GET /v1/viop/contracts
- GET /v1/viop/quote/{symbol}

BIST discovery uses the documented TradeWize PAY last-price/details endpoint with `all=true`; the backend no longer treats the old 20-symbol list as the BIST universe. VİOP discovery uses the documented VİOP last-price/details endpoint with `all=true`. TradeWize documents these price endpoints as one request per second per product group, so the backend keeps discovery separate from the heavier historical-bar scan. citeturn0search0turn2search0

The scanner accepts `market`, `assetType`, `timeframe`/ `interval`, `limit`, `offset`, `minScore` and `includeWatch`. The default scan timeframe is 5m. Historical bars are requested from the documented `/api/v1/market-data/bars` endpoint with `includeOpenBar=false`; live/open bars are a separate upstream capability. citeturn2search1

The response reports `universeCount`, `scannedSymbols`, `remainingSymbols`, `coverageComplete`, `dataConfidence`, `verificationStatus`, `timeframe` and failure details. A partial batch is never represented as a complete full-market scan.

### Live-data readiness

`/v1/provider/capabilities` checks BIST and VİOP discovery plus whether usable prices have timestamps within `MAX_DATA_AGE_MS`. Therefore an old upstream price can still prove provider connectivity while correctly keeping `providerReady=false`.

This distinction is intentional. TradeWize documents that last-price timestamps are the original price-event time and that old prices may be returned when no current record is available. Live Borsa İstanbul data is a separately licensed data service. citeturn0search0turn0search5

### Commodity limitation

The current documented TradeWize Developer API market-data group exposes PAY and VİOP price/bar endpoints; it does not document a generic gold/silver/commodity quote endpoint in the same group. The backend therefore reports COMMODITY/GOLD/SILVER/FX/INDEX as unsupported rather than inventing symbols or prices. When a real provider endpoint is available, it should be added as a separate adapter. citeturn0search1

No mock prices or fabricated market data are returned.

