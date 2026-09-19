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
MAX_AGE_MS = int(os.getenv("MAX_DATA_AGE_MS", "30000"))

app = FastAPI(title=APP_NAME, version="1.0.0")


def require_app_auth(authorization: str | None) -> None:
    if not APP_API_KEY:
        return
    expected = f"Bearer {APP_API_KEY}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="BACKEND_AUTH_INVALID")


def upstream_headers() -> dict[str, str]:
    if not UPSTREAM_ACCESS_TOKEN:
        raise HTTPException(status_code=503, detail="UPSTREAM_NOT_CONFIGURED")
    return {
        "Authorization": f"Bearer {UPSTREAM_ACCESS_TOKEN}",
        "Accept": "application/json",
        "User-Agent": "BorsaTakip-Production-Backend/1.0",
    }


async def upstream_get(path: str, params: dict[str, Any] | None = None) -> httpx.Response:
    headers = upstream_headers()
    timeout = httpx.Timeout(15.0, connect=8.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
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
        "upstreamConfigured": bool(UPSTREAM_ACCESS_TOKEN),
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
