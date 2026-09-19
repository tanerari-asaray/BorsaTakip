# BorsaTakip Production Backend

Android V5 backend contract for:

- GET /health
- GET /v1/viop/contracts
- GET /v1/viop/quote/{symbol}
- GET /v1/viop/history/{symbol}?range=1y&interval=1d
- GET /v1/news

## Production data source

This backend uses the TradeWize Developer API as the upstream market-data provider. The upstream access token is supplied only through the server environment variable `TRADEWIZE_ACCESS_TOKEN`; it is not committed to Git or embedded in the Android APK.

The Android application's API key field is mapped to `APP_API_KEY` at the backend boundary. If `APP_API_KEY` is empty, the backend accepts the requests without an application-level bearer check.

## Important

A valid TradeWize Developer API credential is required for market data. The backend deliberately does not fabricate real-time, bid/ask, volume, tick-size or multiplier metadata when the upstream does not provide it.

The Android V5 client therefore may display a contract as "İzleme/Yetersiz veri" until the contract metadata source is expanded. Quote and closed-bar endpoints remain available when upstream data is present.
