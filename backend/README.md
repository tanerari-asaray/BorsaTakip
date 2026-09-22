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


### BIST Scanner

The production backend now exposes:

- GET /v1/scanner/opportunities
- Optional query parameter: `symbols=THYAO,ASELS,...`
- Default symbols are configured with `SCANNER_SYMBOLS`
- The scanner uses closed daily OHLCV bars from TradeWize and computes deterministic EMA20/EMA50, RSI14, ATR%, momentum and data-confidence fields.
- The response includes `engineVersion=V5.3.2`, `mode=REMOTE`, `decision`, `signalScore`, `dataConfidence`, `verificationStatus` and failure details.
- No mock prices or fabricated market data are returned. If the upstream does not return usable bars, that symbol is reported under `failures`.

The scanner is intentionally fail-closed for insufficient history and low data confidence. Closed daily bars should not be represented as live tick data; `realtime` and `delaySeconds` are derived from the upstream timestamp.

