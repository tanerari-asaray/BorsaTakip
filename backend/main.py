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
DEFAULT_SCAN_INTERVAL = os.getenv("SCANNER_INTERVAL", "5m").strip() or "5m"
SCANNER_HISTORY_BARS = int(os.getenv("SCANNER_HISTORY_BARS", "120"))
SCANNER_BATCH_SIZE = int(os.getenv("SCANNER_BATCH_SIZE", "60"))
CAPABILITY_CACHE_SECONDS = int(os.getenv("CAPABILITY_CACHE_SECONDS", "10"))
SUPPORTED_SCAN_INTERVALS = {"1m", "3m", "5m", "15m", "30m", "60m", "240m", "1D"}

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
@app.get("/v1/health")
async def health(authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    return {
        "ok": True,
        "service": APP_NAME,
        "upstreamConfigured": bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN),
        "timestamp": int(time.time() * 1000),
    }



_bist_cache: dict[str, Any] = {"at": 0.0, "items": []}
_capability_cache: dict[str, Any] = {"at": 0.0, "value": None}


def _unwrap_symbol_map(payload: Any) -> dict[str, Any]:
    value = unwrap_json(payload)
    return value if isinstance(value, dict) else {}


async def discover_bist_quotes(force: bool = False) -> list[dict[str, Any]]:
    now = time.time()
    if not force and _bist_cache["items"] and now - float(_bist_cache["at"]) < 1.0:
        return list(_bist_cache["items"])

    response = await upstream_get(
        "/api/v1/market-data/last-price/details",
        {"all": "true"},
    )
    records = _unwrap_symbol_map(response.json())
    items: list[dict[str, Any]] = []
    for symbol, raw in records.items():
        if not isinstance(symbol, str):
            continue
        normalized = extract_price_record(symbol, raw)
        items.append({
            **normalized,
            "market": "BIST",
            "assetType": "STOCK",
            "name": symbol.upper(),
        })
    items.sort(key=lambda x: x["symbol"])
    _bist_cache["at"] = now
    _bist_cache["items"] = items
    return list(items)


@app.get("/v1/bist/symbols")
async def bist_symbols(authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    items = await discover_bist_quotes()
    symbols = [x["symbol"] for x in items if x.get("symbol")]
    usable = [x for x in items if float(x.get("price", 0) or 0) > 0]
    return {
        # V5.4.13 Android contract: items is a plain string array and count
        # must exactly equal items.length. Detailed provider data remains
        # available under detailedItems for diagnostics/backward tooling.
        "items": symbols,
        "count": len(symbols),
        "symbolCount": len(symbols),
        "usableCount": len(usable),
        "detailedItems": [
            {
                "symbol": x["symbol"],
                "name": x["name"],
                "market": "BIST",
                "assetType": "STOCK",
                "price": x["price"],
                "dataTimestamp": x["timestamp"],
                "realtime": x["realtime"],
                "delaySeconds": x["delaySeconds"],
            }
            for x in items
        ],
        "source": "TradeWize",
        "receivedAt": int(time.time() * 1000),
    }


@app.get("/v1/bist/quote/{symbol}")
async def bist_quote(symbol: str, authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    safe = symbol.strip().upper()
    if not safe or len(safe) > 64:
        raise HTTPException(status_code=400, detail="BIST_QUOTE_ERROR: Geçersiz sembol.")

    response = await upstream_get(
        "/api/v1/market-data/last-price/details",
        {"symbols": safe},
    )
    records = _unwrap_symbol_map(response.json())
    if safe not in records:
        raise HTTPException(status_code=404, detail="BIST_QUOTE_ERROR: Sembol bulunamadı.")

    normalized = extract_price_record(safe, records[safe])
    if normalized["price"] <= 0:
        raise HTTPException(status_code=503, detail="BIST_QUOTE_ERROR: Kullanılabilir fiyat yok.")
    if normalized["timestamp"] <= 0:
        raise HTTPException(status_code=503, detail="BIST_QUOTE_ERROR: Fiyat zaman damgası yok.")

    # TradeWize son-fiyat kaydı, olayın gerçek zaman damgasını korur ve eski
    # fiyatları da döndürebilir. Uygulamanın quote sözleşmesi ise karar fiyatı
    # için güncel/seans içi veri ister. Eski kayıt varsa, son 1-5 saniyelik
    # gerçek fiyat olaylarından en yeni tick'i kullanıyoruz. Bu değer "last
    # price" ile aynı semantiğe sahiptir; açık mum kapanışını quote diye
    # göstermiyoruz.
    if not normalized["realtime"]:
        tick_response = await upstream_get(
            "/api/v1/market-data/recent-ticks",
            {"symbols": safe, "seconds": 5},
        )
        tick_payload = unwrap_json(tick_response.json())
        ticks = tick_payload.get("ticks", []) if isinstance(tick_payload, dict) else []
        candidates = []
        for tick in ticks:
            if not isinstance(tick, dict):
                continue
            tick_symbol = str(tick.get("symbol", "")).strip().upper()
            try:
                tick_price = float(tick.get("price", 0))
                tick_ts = normalize_timestamp(tick.get("timestampMs", tick.get("timestamp")))
            except (TypeError, ValueError):
                continue
            if tick_symbol == safe and tick_price > 0 and tick_ts > 0:
                candidates.append((tick_ts, tick_price))
        if candidates:
            tick_ts, tick_price = max(candidates, key=lambda item: item[0])
            tick_realtime, tick_delay = freshness(tick_ts)
            if tick_realtime:
                return {
                    "symbol": safe,
                    "price": tick_price,
                    "exchangeTimestamp": tick_ts,
                    "receivedAt": int(time.time() * 1000),
                    "source": "TradeWize/recent-ticks",
                    "realtime": True,
                    "delaySeconds": tick_delay,
                    "currentSessionIncluded": True,
                    "providerReady": True,
                }

        age_seconds = normalized["delaySeconds"]
        raise HTTPException(
            status_code=503,
            detail=(
                "BIST_QUOTE_ERROR: Upstream quote is not live/current-session "
                f"(age={age_seconds}s); recent tick bulunamadı."
            ),
        )

    return {
        "symbol": safe,
        "price": normalized["price"],
        "exchangeTimestamp": normalized["timestamp"],
        "receivedAt": int(time.time() * 1000),
        "source": "TradeWize",
        "realtime": normalized["realtime"],
        "delaySeconds": normalized["delaySeconds"],
        "currentSessionIncluded": normalized["currentSessionIncluded"],
        "providerReady": normalized["realtime"],
    }



_viop_cache: dict[str, Any] = {"at": 0.0, "items": []}


async def discover_viop_quotes(force: bool = False) -> list[dict[str, Any]]:
    response = await upstream_get(
        "/api/v1/market-data/viop/last-price/details",
        {"all": "true"},
    )
    records = _unwrap_symbol_map(response.json())
    items: list[dict[str, Any]] = []
    for symbol, raw in records.items():
        if not isinstance(symbol, str):
            continue
        normalized = extract_price_record(symbol, raw)
        if normalized["price"] <= 0:
            continue
        items.append({
            **normalized,
            "market": "VIOP",
            "assetType": "FUTURE",
            "name": symbol.upper(),
            "underlying": symbol.upper(),
        })
    items.sort(key=lambda x: x["symbol"])
    _viop_cache["at"] = time.time()
    _viop_cache["items"] = items
    return list(items)


async def provider_capabilities(force: bool = False) -> dict[str, Any]:
    now = time.time()
    if (
        not force
        and _capability_cache["value"] is not None
        and now - float(_capability_cache["at"]) < CAPABILITY_CACHE_SECONDS
    ):
        return dict(_capability_cache["value"])

    result: dict[str, Any] = {
        "provider": "TradeWize",
        "providerConfigured": bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN),
        "providerReady": False,
        "multiMarketReady": False,
        "checkedAt": int(time.time() * 1000),
        "markets": {},
        "errors": [],
    }

    try:
        bist = await discover_bist_quotes(force=force)
        bist_usable = [x for x in bist if float(x.get("price", 0) or 0) > 0]
        bist_live = [x for x in bist_usable if x.get("realtime")]
        result["markets"]["BIST"] = {
            "supported": True,
            "discovery": True,
            "symbolCount": len(bist),
            "usableCount": len(bist_usable),
            "liveCount": len(bist_live),
            "realtime": bool(bist_live),
            "ready": bool(bist_live),
        }
    except HTTPException as exc:
        result["markets"]["BIST"] = {"supported": True, "ready": False, "error": str(exc.detail)}
        result["errors"].append({"market": "BIST", "reason": str(exc.detail)})

    try:
        viop = await discover_viop_quotes()
        viop_usable = viop
        viop_live = [x for x in viop if x["realtime"]]
        result["markets"]["VIOP"] = {
            "supported": True,
            "discovery": True,
            "symbolCount": len(viop_records),
            "usableCount": len(viop_usable),
            "liveCount": len(viop_live),
            "realtime": bool(viop_live),
            "ready": bool(viop_live),
        }
    except HTTPException as exc:
        result["markets"]["VIOP"] = {"supported": True, "ready": False, "error": str(exc.detail)}
        result["errors"].append({"market": "VIOP", "reason": str(exc.detail)})

    for market in ("COMMODITY", "GOLD", "SILVER", "FX", "INDEX"):
        result["markets"][market] = {
            "supported": False,
            "discovery": False,
            "ready": False,
            "reason": "UPSTREAM_ENDPOINT_NOT_DOCUMENTED",
        }

    result["multiMarketReady"] = bool(
        result["markets"].get("BIST", {}).get("ready")
        and result["markets"].get("VIOP", {}).get("ready")
    )
    result["providerReady"] = result["multiMarketReady"]
    _capability_cache["at"] = now
    _capability_cache["value"] = result
    return result


@app.get("/v1/provider/capabilities")
async def provider_capabilities_endpoint(
    force: bool = Query(False),
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    return await provider_capabilities(force=force)


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


def _bar_timestamp_ms(bar: dict[str, Any]) -> int:
    raw = bar.get("timestamp", bar.get("timestampMs", bar.get("time", bar.get("openTimeUnix", 0))))
    ts = normalize_timestamp(raw)
    if ts > 0:
        return ts
    for key in ("timeUtc", "openTimeUtc", "closeTimeUtc"):
        value = bar.get(key)
        if isinstance(value, str) and value.strip():
            try:
                return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
            except ValueError:
                continue
    return 0


async def _fetch_bist_history(
    symbol: str,
    interval: str = "1d",
    range_value: str = "1y",
    from_ms: int | None = None,
    to_ms: int | None = None,
) -> list[dict[str, Any]]:
    allowed = {"1m", "3m", "5m", "15m", "30m", "60m", "240m", "1d"}
    if interval not in allowed:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz interval.")

    safe = symbol.strip().upper()
    if not safe:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz sembol.")

    if interval == "1d":
        upstream_interval = "1D"
    else:
        upstream_interval = interval

    params: dict[str, Any] = {
        "symbol": safe,
        "interval": upstream_interval,
        "includeOpenBar": "false",
    }

    if from_ms is not None and to_ms is not None:
        params["fromUtc"] = datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")
        params["toUtc"] = datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    else:
        try:
            range_units = int(range_value[:-1])
            unit = range_value[-1]
        except (TypeError, ValueError):
            range_units, unit = 1, "y"
        if interval == "1d":
            count_back = min(1000, max(50, range_units * (365 if unit == "y" else 30 if unit == "m" else 1)))
        else:
            count_back = min(1000, max(50, range_units * (240 if unit == "d" else 30 if unit == "m" else 5)))
        params["countBack"] = count_back

    response = await upstream_get("/api/v1/market-data/bars", params)
    payload = unwrap_json(response.json())
    bars = payload.get("bars", payload.get("items", [])) if isinstance(payload, dict) else payload if isinstance(payload, list) else []

    candles: list[dict[str, Any]] = []
    for bar in bars:
        if not isinstance(bar, dict):
            continue
        ts = _bar_timestamp_ms(bar)
        if ts <= 0:
            continue
        try:
            o = float(bar.get("open"))
            h = float(bar.get("high"))
            l = float(bar.get("low"))
            close = float(bar.get("close"))
            v = float(bar.get("volume", 0))
        except (TypeError, ValueError):
            continue
        if min(o, h, l, close) <= 0 or v < 0:
            continue
        candles.append({"timestamp": ts, "open": o, "high": h, "low": l, "close": close, "volume": v})

    candles.sort(key=lambda x: x["timestamp"])
    if not candles:
        raise HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: Backend kapanmış BIST mum döndürmedi.")
    return candles


@app.get("/v1/bist/history/{symbol}")
async def bist_history(
    symbol: str,
    range: str = Query("1y", pattern=r"^[0-9]+[dmy]$"),
    interval: str = Query("1d"),
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    candles = await _fetch_bist_history(symbol, interval, range)
    safe = symbol.strip().upper()
    return {
        "symbol": safe,
        "name": safe,
        "market": "BIST",
        "interval": interval,
        "exchangeTimezone": "Europe/Istanbul",
        "sessionId": None,
        "currentSessionIncluded": False,
        "lastBarClosed": True,
        "lastBarTime": candles[-1]["timestamp"],
        "lastBarTimestamp": candles[-1]["timestamp"],
        "previousClose": candles[-2]["close"] if len(candles) >= 2 else None,
        "candles": candles,
    }


@app.get("/v1/bist/history-window/{symbol}")
async def bist_history_window(
    symbol: str,
    from_time: int = Query(..., alias="from"),
    to_time: int = Query(..., alias="to"),
    interval: str = Query("5m"),
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    if from_time <= 0 or to_time <= from_time:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz zaman aralığı.")
    candles = await _fetch_bist_history(symbol, interval, from_ms=from_time, to_ms=to_time)
    filtered = [x for x in candles if from_time <= x["timestamp"] <= to_time]
    if not filtered:
        raise HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: İstenen zaman aralığında kapanmış mum yok.")
    return {
        "symbol": symbol.strip().upper(),
        "market": "BIST",
        "interval": interval,
        "exchangeTimezone": "Europe/Istanbul",
        "lastBarClosed": True,
        "lastBarTime": filtered[-1]["timestamp"],
        "candles": filtered,
    }


@app.get("/v1/preflight")
async def bist_preflight(authorization: str | None = Header(default=None)):
    require_app_auth(authorization)
    started = int(time.time() * 1000)
    authentication = {"ok": bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN), "message": "TradeWize kimlik doğrulama yapılandırıldı." if (TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN) else "TradeWize erişim anahtarı yapılandırılmamış."}
    symbols = {"ok": False, "message": "BIST sembol evreni doğrulanmadı."}
    history = {"ok": False, "message": "BIST history doğrulanmadı."}
    quote = {"ok": False, "code": "UNAVAILABLE", "message": "BIST quote doğrulanmadı."}
    symbol_count = 0
    sample_symbol = None

    try:
        discovered = await discover_bist_quotes(force=True)
        usable = [x for x in discovered if x.get("symbol") and float(x.get("price", 0) or 0) > 0]
        symbol_count = len(discovered)
        sample_symbol = usable[0]["symbol"] if usable else (discovered[0]["symbol"] if discovered else None)
        symbols = {
            "ok": symbol_count > 0,
            "message": f"{symbol_count} BIST sembolü bulundu." if symbol_count > 0 else "BIST sembol evreni boş.",
        }
    except HTTPException as exc:
        symbols["message"] = str(exc.detail)
    except Exception as exc:
        symbols["message"] = str(exc)

    if sample_symbol:
        try:
            candles = await _fetch_bist_history(sample_symbol, "1d", "1y")
            history = {
                "ok": len(candles) >= 50,
                "message": f"{len(candles)} kapanmış günlük mum doğrulandı.",
                "lastBarTimestamp": candles[-1]["timestamp"],
            }
        except HTTPException as exc:
            history["message"] = str(exc.detail)
        except Exception as exc:
            history["message"] = str(exc)

        try:
            record = next((x for x in discovered if x["symbol"] == sample_symbol), None)
            if record and record["price"] > 0 and record["timestamp"] > 0 and record["realtime"]:
                quote = {
                    "ok": True,
                    "code": "NONE",
                    "message": "BIST quote güncel ve seans içi.",
                    "exchangeTimestamp": record["timestamp"],
                    "delaySeconds": record["delaySeconds"],
                }
            else:
                tick_response = await upstream_get(
                    "/api/v1/market-data/recent-ticks",
                    {"symbols": sample_symbol, "seconds": 5},
                )
                tick_payload = unwrap_json(tick_response.json())
                ticks = tick_payload.get("ticks", []) if isinstance(tick_payload, dict) else []
                candidates = []
                for tick in ticks:
                    if not isinstance(tick, dict):
                        continue
                    tick_symbol = str(tick.get("symbol", "")).strip().upper()
                    tick_ts = normalize_timestamp(tick.get("timestampMs", tick.get("timestamp")))
                    try:
                        tick_price = float(tick.get("price", 0))
                    except (TypeError, ValueError):
                        tick_price = 0.0
                    if tick_symbol == sample_symbol and tick_price > 0 and tick_ts > 0:
                        candidates.append((tick_ts, tick_price))
                if candidates:
                    tick_ts, _ = max(candidates, key=lambda item: item[0])
                    live, delay = freshness(tick_ts)
                    if live:
                        quote = {"ok": True, "code": "NONE", "message": "BIST quote recent-ticks üzerinden güncel.", "exchangeTimestamp": tick_ts, "delaySeconds": delay}
                    else:
                        quote = {"ok": False, "code": "STALE_DATA", "message": f"Upstream quote güncel değil (age={record.get('delaySeconds')}s); recent tick de güncel değil.", "exchangeTimestamp": record.get("timestamp"), "delaySeconds": record.get("delaySeconds")}
                else:
                    quote = {"ok": False, "code": "STALE_DATA", "message": f"Upstream quote güncel değil (age={record.get('delaySeconds')}s); recent tick bulunamadı.", "exchangeTimestamp": record.get("timestamp"), "delaySeconds": record.get("delaySeconds")}
        except HTTPException as exc:
            quote = {"ok": False, "code": "STALE_DATA" if "recent-tick" in str(exc.detail).lower() else "QUOTE_ERROR", "message": str(exc.detail)}
        except Exception as exc:
            quote = {"ok": False, "code": "QUOTE_ERROR", "message": str(exc)}

    analysis_mode = (
        "REALTIME"
        if quote["ok"] and history["ok"]
        else "DELAYED_ANALYSIS_AVAILABLE"
        if history["ok"] and quote.get("code") == "STALE_DATA"
        else "UNAVAILABLE"
    )
    ok = bool(authentication["ok"] and symbols["ok"] and history["ok"] and (quote["ok"] or analysis_mode == "DELAYED_ANALYSIS_AVAILABLE"))
    return {
        "ok": ok,
        "provider": "TradeWize",
        "authentication": authentication,
        "symbols": symbols,
        "quote": quote,
        "history": history,
        "symbolCount": symbol_count,
        "sampleSymbol": sample_symbol,
        "analysisMode": analysis_mode,
        "serverTime": started,
        "elapsedMs": int(time.time() * 1000) - started,
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



SCANNER_SYMBOLS = os.getenv("SCANNER_SYMBOLS", "")
SCANNER_MAX_SYMBOLS = int(os.getenv("SCANNER_MAX_SYMBOLS", "1000"))
SCANNER_BATCH_SIZE = int(os.getenv("SCANNER_BATCH_SIZE", "60"))


def scanner_symbols(raw: str | None, market: str = "BIST", offset: int = 0, limit: int | None = None) -> tuple[list[str], int]:
    requested = (raw or "").strip()
    if requested:
        candidates = requested.split(",")
    elif market == "BIST":
        candidates = [x["symbol"] for x in _bist_cache.get("items", []) if isinstance(x, dict)]
    elif market == "VIOP":
        candidates = [x["symbol"] for x in _viop_cache.get("items", []) if isinstance(x, dict)]
    elif market == "ALL":
        candidates = (
            [x["symbol"] for x in _bist_cache.get("items", []) if isinstance(x, dict)]
            + [x["symbol"] for x in _viop_cache.get("items", []) if isinstance(x, dict)]
        )
    else:
        candidates = SCANNER_SYMBOLS.split(",") if SCANNER_SYMBOLS else []

    seen: set[str] = set()
    result: list[str] = []
    for item in candidates:
        safe = item.strip().upper()
        if not safe or len(safe) > 64 or safe in seen:
            continue
        seen.add(safe)
        result.append(safe)

    result = result[max(0, offset):]
    cap = min(limit or SCANNER_BATCH_SIZE, SCANNER_MAX_SYMBOLS)
    return result[:cap], max(0, len(result) - cap)


def _normalize_bars(payload: Any) -> list[dict[str, Any]]:
    value = unwrap_json(payload)
    if isinstance(value, dict):
        bars = value.get("bars", value.get("items", []))
    elif isinstance(value, list):
        bars = value
    else:
        bars = []

    normalized: list[dict[str, Any]] = []
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
        ts = normalize_timestamp(
            bar.get("timestamp", bar.get("timestampMs", bar.get("time", bar.get("openTimeUnix", 0))))
        )
        if ts <= 0 or min(o, h, l, c) <= 0 or v < 0:
            continue
        normalized.append({
            "timestamp": ts,
            "open": o,
            "high": h,
            "low": l,
            "close": c,
            "volume": v,
        })
    normalized.sort(key=lambda x: x["timestamp"])
    return normalized


def _scan_from_bars(symbol: str, bars: list[dict[str, Any]], interval: str, metadata: dict[str, Any] | None = None) -> dict[str, Any] | None:
    if len(bars) < 30:
        return None

    closes = [float(x["close"]) for x in bars]
    volumes = [float(x.get("volume", 0.0)) for x in bars]
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

    latest_ts = normalize_timestamp(bars[-1].get("timestamp"))
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

    metadata = metadata or {}
    return {
        "symbol": symbol,
        "name": metadata.get("name", symbol),
        "market": metadata.get("market", "BIST"),
        "assetType": metadata.get("assetType", "STOCK"),
        "underlying": metadata.get("underlying", symbol),
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
        "openInterest": metadata.get("openInterest"),
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
        "timeframe": interval,
        "source": "TradeWize",
        "engineVersion": "V5.4.13",
        "mode": "REMOTE",
        "calculationVersion": "V5.4.13",
    }


async def _scan_symbol(symbol: str, interval: str, market: str) -> tuple[dict[str, Any] | None, str | None]:
    try:
        response = await upstream_get(
            "/api/v1/market-data/bars",
            {
                "symbol": symbol,
                "interval": interval,
                "countBack": SCANNER_HISTORY_BARS,
                "includeOpenBar": "false",
            },
        )
        bars = _normalize_bars(response.json())
        metadata = {"market": market, "assetType": "STOCK" if market == "BIST" else "FUTURE"}
        item = _scan_from_bars(symbol, bars, interval, metadata)
        if item is None:
            return None, "INSUFFICIENT_HISTORY"
        return item, None
    except HTTPException as exc:
        return None, str(exc.detail)
    except Exception as exc:
        return None, f"SCANNER_SYMBOL_ERROR: {type(exc).__name__}"


@app.get("/v1/scanner/opportunities")
async def scanner_opportunities(
    symbols: str | None = Query(default=None),
    market: str = Query("BIST"),
    assetType: str = Query("STOCK"),
    timeframe: str = Query(DEFAULT_SCAN_INTERVAL),
    interval: str | None = Query(default=None),
    limit: int = Query(60, ge=1, le=1000),
    minScore: float = Query(0.0, ge=0.0, le=100.0),
    includeWatch: bool = Query(True),
    offset: int = Query(0, ge=0),
    authorization: str | None = Header(default=None),
):
    require_app_auth(authorization)
    selected_interval = (interval or timeframe).strip()
    if selected_interval not in SUPPORTED_SCAN_INTERVALS:
        raise HTTPException(status_code=400, detail=f"SCANNER_ERROR: Desteklenmeyen timeframe: {selected_interval}")

    market = market.strip().upper()
    assetType = assetType.strip().upper()
    if market not in {"BIST", "VIOP", "ALL"}:
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: Geçersiz market.")
    if assetType not in {"STOCK", "FUTURE", "ALL"}:
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: Geçersiz assetType.")

    if market == "BIST":
        await discover_bist_quotes()
    elif market == "VIOP":
        await discover_viop_quotes()
    elif market == "ALL":
        await discover_bist_quotes()
        await discover_viop_quotes()

    requested, remaining = scanner_symbols(
        symbols,
        "BIST" if market in {"BIST", "ALL"} else market,
        offset,
        limit,
    )

    if not requested:
        return {
            "items": [],
            "opportunities": [],
            "engineVersion": "V5.4.13",
            "mode": "REMOTE",
            "source": "TradeWize",
            "receivedAt": int(time.time() * 1000),
            "scannedSymbols": 0,
            "universeCount": len(_bist_cache.get("items", [])) if market in {"BIST", "ALL"} else 0,
            "remainingSymbols": 0,
            "coverageComplete": True,
            "timeframe": selected_interval,
            "failures": [],
            "failedCount": 0,
            "providerReady": False,
        }

    opportunities: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    semaphore = asyncio.Semaphore(2)

    async def run_one(symbol: str):
        async with semaphore:
            return symbol, await _scan_symbol(
                symbol,
                selected_interval,
                "BIST" if market == "BIST" else market,
            )

    results = await asyncio.gather(*(run_one(symbol) for symbol in requested))
    for symbol, (item, error) in results:
        if error:
            failures.append({"symbol": symbol, "reason": error})
        elif item is not None:
            if includeWatch or item["decision"] in {"LONG", "SHORT"}:
                if abs(float(item["score"])) >= minScore:
                    opportunities.append(item)

    opportunities.sort(key=lambda x: abs(float(x["score"])), reverse=True)
    capability = await provider_capabilities()
    return {
        "items": opportunities,
        "opportunities": opportunities,
        "engineVersion": "V5.4.13",
        "mode": "REMOTE",
        "source": "TradeWize",
        "receivedAt": int(time.time() * 1000),
        "scannedSymbols": len(requested),
        "universeCount": (
            len(_bist_cache.get("items", [])) + len(_viop_cache.get("items", []))
            if market == "ALL"
            else len(_bist_cache.get("items", []))
            if market == "BIST"
            else len(_viop_cache.get("items", []))
        ),
        "returnedCount": len(opportunities),
        "remainingSymbols": remaining,
        "coverageComplete": remaining == 0,
        "timeframe": selected_interval,
        "failures": failures,
        "failedCount": len(failures),
        "providerReady": bool(capability.get("providerReady")),
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
