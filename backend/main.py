import asyncio
import os
import time
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Query

APP_NAME = "BorsaTakip Production Backend"
UPSTREAM = os.getenv("TRADEWIZE_BASE_URL", "https://api.tradewize.com.tr").rstrip("/")
APP_API_KEY = os.getenv("APP_API_KEY", "").strip()
UPSTREAM_ACCESS_TOKEN = os.getenv("TRADEWIZE_ACCESS_TOKEN", "").strip()
TRADEWIZE_API_KEY = os.getenv("TRADEWIZE_API_KEY", "").strip()
MAX_AGE_MS = int(os.getenv("MAX_DATA_AGE_MS", "30000"))

app = FastAPI(title=APP_NAME, version="1.0.0")


def require_app_auth(authorization: str | None) -> None:
    if not APP_API_KEY:
        return
    expected = f"Bearer {APP_API_KEY}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="BACKEND_AUTH_INVALID")


_token_lock = asyncio.Lock()
_cached_access_token = UPSTREAM_ACCESS_TOKEN
_cached_access_token_expires_at = (time.time() + 300) if UPSTREAM_ACCESS_TOKEN else 0.0


async def get_upstream_access_token(force_refresh: bool = False) -> str:
    global _cached_access_token, _cached_access_token_expires_at

    if not force_refresh and _cached_access_token and time.time() < _cached_access_token_expires_at - 60:
        return _cached_access_token

    if not TRADEWIZE_API_KEY:
        if UPSTREAM_ACCESS_TOKEN:
            return UPSTREAM_ACCESS_TOKEN
        raise HTTPException(status_code=503, detail="UPSTREAM_NOT_CONFIGURED")

    async with _token_lock:
        if not force_refresh and _cached_access_token and time.time() < _cached_access_token_expires_at - 60:
            return _cached_access_token

        timeout = httpx.Timeout(15.0, connect=8.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                f"{UPSTREAM}/oauth/token",
                headers={
                    "X-API-Key": TRADEWIZE_API_KEY,
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
                json={"grant_type": "api_key"},
            )
        if response.status_code >= 400:
            detail = response.text[:500]
            raise HTTPException(status_code=502, detail=f"UPSTREAM_AUTH_HTTP_{response.status_code}: {detail}")

        payload = response.json()
        token = payload.get("access_token") if isinstance(payload, dict) else None
        if not token:
            raise HTTPException(status_code=502, detail="UPSTREAM_AUTH_RESPONSE_INVALID")

        expires_in = payload.get("expires_in", 86400)
        try:
            expires_in = max(300, int(expires_in))
        except (TypeError, ValueError):
            expires_in = 86400

        _cached_access_token = str(token)
        _cached_access_token_expires_at = time.time() + expires_in
        return _cached_access_token


async def upstream_get(path: str, params: dict[str, Any] | None = None) -> httpx.Response:
    token = await get_upstream_access_token()
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
        "User-Agent": "BorsaTakip-Production-Backend/1.0",
    }
    timeout = httpx.Timeout(15.0, connect=8.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.get(f"{UPSTREAM}{path}", params=params, headers=headers)

        if response.status_code == 401 and TRADEWIZE_API_KEY:
            token = await get_upstream_access_token(force_refresh=True)
            headers["Authorization"] = f"Bearer {token}"
            response = await client.get(f"{UPSTREAM}{path}", params=params, headers=headers)

    if response.status_code >= 400:
        detail = response.text[:500]
        raise HTTPException(status_code=502, detail=f"UPSTREAM_HTTP_{response.status_code}: {detail}")
    return response


def unwrap_json(payload: Any) -> Any:
    if isinstance(payload, dict) and isinstance(payload.get("data"), (dict, list)):
        return payload["data"]
    return payload


def freshness(timestamp_ms: int) -> tuple[bool, int | None]:
    if timestamp_ms <= 0:
        return False, None
    age_ms = max(0, int(time.time() * 1000) - timestamp_ms)
    return age_ms <= MAX_AGE_MS, int(age_ms / 1000)


def normalize_timestamp(value: Any) -> int:
    if value is None:
        return 0
    try:
        n = int(value)
        if n < 10_000_000_000:
            n *= 1000
        return n
    except (TypeError, ValueError):
        return 0


def extract_price_record(symbol: str, raw: Any) -> dict[str, Any]:
    item = raw if isinstance(raw, dict) else {}
    price = item.get("price", item.get("last", item.get("lastPrice", 0)))
    ts = normalize_timestamp(item.get("timestamp", item.get("exchangeTimestamp", item.get("timestampMs"))))
    try:
        price = float(price)
    except (TypeError, ValueError):
        price = 0.0
    realtime, delay = freshness(ts)
    return {
        "symbol": symbol.upper(),
        "price": price,
        "timestamp": ts,
        "realtime": realtime,
        "delaySeconds": delay,
        "currentSessionIncluded": realtime,
        "source": "TradeWize",
    }


@app.get("/health")
async def health(authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    return {
        "ok": True,
        "service": APP_NAME,
        "upstreamConfigured": bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN),
        "timestamp": int(time.time() * 1000),
    }


@app.get("/v1/viop/contracts")
async def viop_contracts(authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    response = await upstream_get("/api/v1/market-data/viop/last-price/details", {"all": "true"})
    payload = unwrap_json(response.json())
    if not isinstance(payload, dict):
        raise HTTPException(status_code=502, detail="UPSTREAM_CONTRACT_RESPONSE_INVALID")

    items = []
    for symbol, record in payload.items():
        if not isinstance(symbol, str):
            continue
        normalized = extract_price_record(symbol, record)
        if normalized["price"] <= 0:
            continue
        items.append({
            "symbol": normalized["symbol"],
            "underlying": "",
            "expiry": "",
            "contractType": "VİOP",
            "lastPrice": normalized["price"],
            "bid": None,
            "ask": None,
            "dailyChangePct": None,
            "tickSize": None,
            "multiplier": None,
            "openInterest": None,
            "volume": None,
            "liquidity": None,
            "rollover": None,
            "source": normalized["source"],
            "currency": "TRY",
            "dataTimestamp": normalized["timestamp"],
            "realtime": normalized["realtime"],
            "delaySeconds": normalized["delaySeconds"],
            "currentSessionIncluded": normalized["currentSessionIncluded"],
        })
    return {"items": items, "receivedAt": int(time.time() * 1000)}


@app.get("/v1/viop/quote/{symbol}")
async def viop_quote(symbol: str, authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    safe = symbol.strip().upper()
    if not safe or len(safe) > 64:
        raise HTTPException(status_code=400, detail="QUOTE_ERROR: Geçersiz sembol.")

    response = await upstream_get(
        "/api/v1/market-data/viop/last-price/details",
        {"symbols": safe},
    )
    payload = unwrap_json(response.json())
    if not isinstance(payload, dict) or safe not in payload:
        raise HTTPException(status_code=404, detail="QUOTE_ERROR: Sembol bulunamadı.")

    normalized = extract_price_record(safe, payload[safe])
    if normalized["price"] <= 0 or normalized["timestamp"] <= 0:
        raise HTTPException(status_code=503, detail="STALE_DATA: Kullanılabilir fiyat zamanı yok.")
    return {
        "symbol": safe,
        "price": normalized["price"],
        "bid": None,
        "ask": None,
        "dailyChangePct": None,
        "volume": None,
        "openInterest": None,
        "exchangeTimestamp": normalized["timestamp"],
        "receivedAt": int(time.time() * 1000),
        "source": normalized["source"],
        "realtime": normalized["realtime"],
        "delaySeconds": normalized["delaySeconds"],
        "currentSessionIncluded": normalized["currentSessionIncluded"],
    }


@app.get("/v1/viop/history/{symbol}")
async def viop_history(
    symbol: str,
    range: str = Query("1y", pattern=r"^[0-9]+[dmy]$"),
    interval: str = Query("1d"),
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    allowed = {"1m", "3m", "5m", "15m", "60m", "1d"}
    if interval not in allowed:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz interval.")

    # TradeWize countBack works for intraday and daily bars. The Android client
    # requests a minimum number of bars; map the requested range to a safe count.
    range_units = int(range[:-1])
    unit = range[-1]
    if interval == "1d":
        count_back = min(1000, max(50, range_units * (365 if unit == "y" else 30 if unit == "m" else 1)))
        upstream_interval = "1D"
    else:
        count_back = min(1000, max(50, range_units * (240 if unit == "d" else 30 if unit == "m" else 5)))
        upstream_interval = interval

    safe = symbol.strip().upper()
    response = await upstream_get(
        "/api/v1/market-data/bars",
        {"symbol": safe, "interval": upstream_interval, "countBack": count_back, "includeOpenBar": "false"},
    )
    payload = unwrap_json(response.json())
    if isinstance(payload, dict):
        bars = payload.get("bars", payload.get("items", []))
    elif isinstance(payload, list):
        bars = payload
    else:
        bars = []

    candles = []
    for bar in bars:
        if not isinstance(bar, dict):
            continue
        ts = normalize_timestamp(bar.get("timestamp", bar.get("timestampMs", bar.get("time", bar.get("openTimeUnix", 0)))))
        if ts <= 0:
            continue
        try:
            o = float(bar.get("open"))
            h = float(bar.get("high"))
            l = float(bar.get("low"))
            c = float(bar.get("close"))
            v = float(bar.get("volume", 0))
        except (TypeError, ValueError):
            continue
        if min(o, h, l, c) <= 0 or v < 0:
            continue
        candles.append({"timestamp": ts, "open": o, "high": h, "low": l, "close": c, "volume": v})

    candles.sort(key=lambda x: x["timestamp"])
    if not candles:
        raise HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: Backend kapanmış mum döndürmedi.")

    return {
        "symbol": safe,
        "interval": interval,
        "lastBarClosed": True,
        "lastBarTimestamp": candles[-1]["timestamp"],
        "candles": candles,
    }


SCANNER_SYMBOLS = os.getenv(
    "SCANNER_SYMBOLS",
    "THYAO,ASELS,AKBNK,EREGL,SISE,TUPRS,BIMAS,KCHOL,SAHOL,TCELL,PGSUS,TOASO,FROTO,GARAN,ISCTR,YKBNK,HALKB,VAKBN,KOZAL,KOZAA",
)
SCANNER_MAX_SYMBOLS = int(os.getenv("SCANNER_MAX_SYMBOLS", "30"))


def scanner_symbols(raw: str | None) -> list[str]:
    source = raw if raw is not None else SCANNER_SYMBOLS
    seen: set[str] = set()
    result: list[str] = []
    for item in source.split(","):
        safe = item.strip().upper()
        if not safe or len(safe) > 32 or safe in seen:
            continue
        seen.add(safe)
        result.append(safe)
        if len(result) >= SCANNER_MAX_SYMBOLS:
            break
    return result


def _ema(values: list[float], period: int) -> float:
    if not values:
        return 0.0
    period = max(1, min(period, len(values)))
    seed = sum(values[:period]) / period
    multiplier = 2.0 / (period + 1)
    value = seed
    for price in values[period:]:
        value = (price - value) * multiplier + value
    return value


def _rsi(values: list[float], period: int = 14) -> float:
    if len(values) <= period:
        return 50.0
    gains = []
    losses = []
    for i in range(1, len(values)):
        delta = values[i] - values[i - 1]
        gains.append(max(delta, 0.0))
        losses.append(max(-delta, 0.0))
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period
    for i in range(period, len(gains)):
        avg_gain = ((avg_gain * (period - 1)) + gains[i]) / period
        avg_loss = ((avg_loss * (period - 1)) + losses[i]) / period
    if avg_loss == 0:
        return 100.0 if avg_gain > 0 else 50.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def _atr_percent(bars: list[dict[str, Any]], period: int = 14) -> float:
    if len(bars) < 2:
        return 0.0
    trs: list[float] = []
    for i, bar in enumerate(bars):
        high = float(bar["high"])
        low = float(bar["low"])
        if i == 0:
            trs.append(max(0.0, high - low))
            continue
        previous_close = float(bars[i - 1]["close"])
        trs.append(max(high - low, abs(high - previous_close), abs(low - previous_close)))
    window = trs[-min(period, len(trs)):]
    close = float(bars[-1]["close"])
    return (sum(window) / len(window)) / close * 100.0 if close > 0 else 0.0


def _scan_from_bars(symbol: str, bars: list[dict[str, Any]]) -> dict[str, Any] | None:
    if len(bars) < 30:
        return None

    closes = [float(x["close"]) for x in bars]
    volumes = [float(x.get("volume", 0.0)) for x in bars]
    last = bars[-1]
    price = closes[-1]
    daily_change = ((price / closes[-2]) - 1.0) * 100.0 if closes[-2] else 0.0
    ema20 = _ema(closes, 20)
    ema50 = _ema(closes, 50)
    rsi14 = _rsi(closes, 14)
    atr_pct = _atr_percent(bars, 14)

    recent_volumes = volumes[-21:-1]
    avg_volume = sum(recent_volumes) / len(recent_volumes) if recent_volumes else 0.0
    volume_available = avg_volume > 0 and volumes[-1] >= 0
    volume_ratio = volumes[-1] / avg_volume if avg_volume > 0 else None

    trend_component = 35.0 if ema20 > ema50 else -35.0 if ema20 < ema50 else 0.0
    momentum_component = max(-30.0, min(30.0, daily_change * 6.0))
    rsi_component = max(-20.0, min(20.0, (rsi14 - 50.0) * 0.8))
    price_component = 15.0 if price > ema20 else -15.0
    raw_score = max(-100.0, min(100.0, trend_component + momentum_component + rsi_component + price_component))

    latest_ts = normalize_timestamp(last.get("timestamp", last.get("timestampMs", last.get("time", 0))))
    realtime, delay = freshness(latest_ts)

    confidence = 0
    confidence += 25 if realtime else 0
    confidence += 20 if len(bars) >= 60 else 12
    confidence += 15 if price > 0 else 0
    confidence += 15
    confidence += 15
    confidence += 10 if volume_available else 0

    threshold = 45.0 if confidence >= 80 else 55.0 if confidence >= 60 else 999.0
    if confidence < 40:
        decision = "INVALID"
        verification = "BLOCKED"
    elif abs(raw_score) >= threshold:
        decision = "LONG" if raw_score > 0 else "SHORT"
        verification = "VERIFIED_OPPORTUNITY"
    else:
        decision = "WATCH"
        verification = "WATCH"

    return {
        "symbol": symbol,
        "underlying": symbol,
        "decision": decision,
        "signal": decision,
        "verificationStatus": verification,
        "score": round(raw_score, 2),
        "signalScore": round(raw_score, 2),
        "dataConfidence": confidence,
        "dataConfidenceBand": "NORMAL" if confidence >= 80 else "DEGRADED" if confidence >= 60 else "LOW" if confidence >= 40 else "BLOCKED",
        "riskCoveragePercent": 75 if volume_available else 65,
        "penalty": 0.0,
        "currentPrice": price,
        "price": price,
        "dailyChangePct": round(daily_change, 4),
        "volume": volumes[-1],
        "openInterest": None,
        "ema20": round(ema20, 6),
        "ema50": round(ema50, 6),
        "rsi14": round(rsi14, 4),
        "atrPct": round(atr_pct, 4),
        "volumeRatio": round(volume_ratio, 4) if volume_ratio is not None else None,
        "dataTimestamp": latest_ts,
        "exchangeTimestamp": latest_ts,
        "delaySeconds": delay,
        "realtime": realtime,
        "currentSessionIncluded": realtime,
        "lastBarClosed": True,
        "source": "TradeWize",
        "engineVersion": "V5.3.2",
        "mode": "REMOTE",
        "calculationVersion": "V5.3.2",
    }


@app.get("/v1/scanner/opportunities")
async def scanner_opportunities(
    symbols: str | None = Query(default=None),
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    requested = scanner_symbols(symbols)
    if not requested:
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: Tarama sembol listesi boş.")

    opportunities: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []

    for symbol in requested:
        try:
            response = await upstream_get(
                "/api/v1/market-data/bars",
                {"symbol": symbol, "interval": "1D", "countBack": 120, "includeOpenBar": "false"},
            )
            payload = unwrap_json(response.json())
            if isinstance(payload, dict):
                bars = payload.get("bars", payload.get("items", []))
            elif isinstance(payload, list):
                bars = payload
            else:
                bars = []

            normalized_bars: list[dict[str, Any]] = []
            for bar in bars:
                if not isinstance(bar, dict):
                    continue
                try:
                    o = float(bar.get("open"))
                    h = float(bar.get("high"))
                    l = float(bar.get("low"))
                    c = float(bar.get("close"))
                    v = float(bar.get("volume", 0))
                except (TypeError, ValueError):
                    continue
                ts = normalize_timestamp(bar.get("timestamp", bar.get("timestampMs", bar.get("time", bar.get("openTimeUnix", 0)))))
                if ts <= 0 or min(o, h, l, c) <= 0 or v < 0:
                    continue
                normalized_bars.append({"timestamp": ts, "open": o, "high": h, "low": l, "close": c, "volume": v})

            normalized_bars.sort(key=lambda x: x["timestamp"])
            item = _scan_from_bars(symbol, normalized_bars)
            if item is not None:
                opportunities.append(item)
            else:
                failures.append({"symbol": symbol, "reason": "INSUFFICIENT_HISTORY"})
        except HTTPException as exc:
            failures.append({"symbol": symbol, "reason": str(exc.detail)})
        except Exception as exc:
            failures.append({"symbol": symbol, "reason": f"SCANNER_SYMBOL_ERROR: {type(exc).__name__}"})

    opportunities.sort(key=lambda x: abs(float(x["score"])), reverse=True)
    return {
        "items": opportunities,
        "opportunities": opportunities,
        "engineVersion": "V5.3.2",
        "mode": "REMOTE",
        "source": "TradeWize",
        "receivedAt": int(time.time() * 1000),
        "scannedSymbols": len(requested),
        "returnedCount": len(opportunities),
        "failedCount": len(failures),
        "failures": failures,
        "providerReady": len(opportunities) > 0,
    }


@app.get("/v1/news")
async def news(
    category: str = "ALL",
    symbol: str | None = None,
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    # TradeWize market-data API does not provide a news endpoint. Returning an
    # explicit empty result is safer than fabricating news.
    return {
        "items": [],
        "category": category.upper(),
        "symbol": symbol.upper() if symbol else None,
        "source": "NO_NEWS_PROVIDER_CONFIGURED",
    }
