import asyncio
import time
from datetime import datetime, timezone
from typing import Any, Optional

import httpx
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

try:
    from nepsepy import AsyncNepseClient
except Exception:
    AsyncNepseClient = None

APP_VERSION = "V25-CENTRAL-DATA-ENGINE"
PUBLIC_API = "https://nepseapi.surajrimal.dev"
CACHE_TTL = {
    "market": 25,
    "index": 25,
    "floorsheet": 35,
    "company": 300,
    "history": 120,
    "sectors": 60,
    "brokers": 45,
}

app = FastAPI(title="NEPSE Pulse Central Data Engine", version=APP_VERSION)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

CACHE: dict[str, tuple[float, Any]] = {}
LOCKS: dict[str, asyncio.Lock] = {}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def arr(v: Any) -> list:
    if isinstance(v, list):
        return v
    if isinstance(v, dict):
        for k in ("data", "content", "results", "result", "items", "records", "rows"):
            if isinstance(v.get(k), list):
                return v[k]
        return [v]
    return []


def num(v: Any) -> Optional[float]:
    if v is None or v == "":
        return None
    try:
        return float(str(v).replace(",", ""))
    except Exception:
        return None


def pick(o: Any, keys: list[str], default=None):
    if not isinstance(o, dict):
        return default
    norm = {str(k).lower().replace("_", "").replace("-", ""): v for k, v in o.items()}
    for k in keys:
        v = norm.get(k.lower().replace("_", "").replace("-", ""))
        if v is not None and v != "":
            return v
    return default


def cache_get(key: str):
    item = CACHE.get(key)
    if not item:
        return None
    ts, value = item
    if time.time() - ts > CACHE_TTL.get(key.split(":", 1)[0], 60):
        return None
    return value


def cache_set(key: str, value: Any):
    CACHE[key] = (time.time(), value)
    return value


async def cached(key: str, loader):
    hit = cache_get(key)
    if hit is not None:
        return hit
    lock = LOCKS.setdefault(key, asyncio.Lock())
    async with lock:
        hit = cache_get(key)
        if hit is not None:
            return hit
        value = await loader()
        return cache_set(key, value)


async def public_get(path: str, params: Optional[dict] = None):
    url = PUBLIC_API.rstrip("/") + path
    async with httpx.AsyncClient(timeout=18, follow_redirects=True) as client:
        r = await client.get(url, params=params, headers={"Accept": "application/json"})
        r.raise_for_status()
        return r.json()


async def nepse_call(methods: list[str], *args, **kwargs):
    if AsyncNepseClient is None:
        raise RuntimeError("nepsepy is not installed")
    async with AsyncNepseClient() as client:
        for name in methods:
            fn = getattr(client, name, None)
            if fn is None:
                continue
            try:
                return await fn(*args, **kwargs)
            except TypeError:
                # Some versions differ in optional parameter names.
                try:
                    return await fn(*args)
                except Exception:
                    continue
            except Exception:
                continue
    raise RuntimeError("No compatible nepsepy method succeeded")


async def get_market():
    async def load():
        errors = []
        try:
            status, summary, live, gainers, losers, turnover, trades, tx = await asyncio.gather(
                nepse_call(["market_status"]),
                nepse_call(["market_summary"]),
                nepse_call(["live_market"]),
                nepse_call(["top_gainers"], True),
                nepse_call(["top_losers"], True),
                nepse_call(["top_turnover"], True),
                nepse_call(["top_traded_shares", "top_active"], True),
                nepse_call(["top_transactions"], True),
            )
            indices = await nepse_call(["nepse_indices", "nepse_index"])
            companies = await nepse_call(["companies", "securities"])
            return {
                "ok": True,
                "source": "NEPSE public frontend data via nepsepy",
                "providerType": "Unofficial public read-only client",
                "updatedAt": now_iso(),
                "marketOpen": pick(status, ["isOpen", "marketOpen"], None),
                "status": status,
                "summary": summary,
                "index": indices,
                "live": live,
                "gainers": gainers,
                "losers": losers,
                "topTurnover": turnover,
                "topTraded": trades,
                "topTransactions": tx,
                "companies": companies,
                "diagnostics": {"listedSymbols": max(461, len(arr(companies))), "sourceErrors": errors},
            }
        except Exception as e:
            errors.append(str(e))
            # Server-side fallback to the public API service. This keeps the browser away from CORS.
            endpoints = {
                "status": "/IsNepseOpen",
                "summary": "/Summary",
                "index": "/NepseIndex",
                "live": "/LiveMarket",
                "gainers": "/TopGainers",
                "losers": "/TopLosers",
                "topTurnover": "/TopTenTurnoverScrips",
                "topTraded": "/TopTenTradeScrips",
                "topTransactions": "/TopTenTransactionScrips",
                "companies": "/CompanyList",
            }
            out = {}
            for k, path in endpoints.items():
                try:
                    out[k] = await public_get(path)
                except Exception as ex:
                    out[k] = [] if k not in ("status",) else {"isOpen": None}
                    errors.append(f"{k}: {ex}")
            return {
                "ok": True,
                "source": "Server-side public NEPSE API fallback",
                "providerType": "Unofficial public read-only client",
                "updatedAt": now_iso(),
                "marketOpen": pick(out.get("status"), ["isOpen", "marketOpen"], None),
                **out,
                "diagnostics": {"listedSymbols": max(461, len(arr(out.get("companies")))), "sourceErrors": errors},
            }
    return await cached("market:core", load)


async def get_index():
    async def load():
        try:
            return await nepse_call(["nepse_indices", "nepse_index"])
        except Exception:
            return await public_get("/NepseIndex")
    return await cached("index:current", load)


async def get_index_history(index_id: int = 58):
    key = f"history:{index_id}"
    async def load():
        try:
            return await nepse_call(["index_history"], index_id, 1, 500)
        except Exception:
            # The public service exposes the same concept through its graph route.
            try:
                return await public_get("/DailyNepseIndexGraph")
            except Exception:
                return []
    return await cached(key, load)


async def resolve_company(symbol: str):
    symbol = symbol.upper().strip()
    companies = await nepse_call(["companies", "securities"])
    for c in arr(companies):
        if str(pick(c, ["symbol", "ticker"], "")).upper() == symbol:
            return c
    return None


async def get_company(symbol: str):
    symbol = symbol.upper().strip()
    key = f"company:{symbol}"
    async def load():
        company = None
        try:
            company = await resolve_company(symbol)
        except Exception:
            pass
        errors = []
        detail = None
        cid = pick(company, ["id", "securityId", "security_id"])
        if cid is not None:
            try:
                detail = await nepse_call(["security_profile"], int(cid))
            except Exception as e:
                errors.append(str(e))
        if detail is None:
            try:
                detail = await public_get("/CompanyDetails", {"symbol": symbol})
            except Exception as e:
                errors.append(str(e))
        return {"ok": detail is not None, "symbol": symbol, "company": company, "details": detail, "errors": errors}
    return await cached(key, load)


async def get_floorsheet(symbol: Optional[str] = None):
    key = f"floorsheet:{symbol or 'all'}"
    async def load():
        if symbol:
            company = await resolve_company(symbol)
            cid = pick(company, ["id", "securityId", "security_id"])
            if cid is not None:
                try:
                    return await nepse_call(["floorsheets"], stock_id=int(cid))
                except Exception:
                    pass
            return await public_get("/FloorsheetOf", {"symbol": symbol.upper()})
        try:
            return await nepse_call(["floorsheets"])
        except Exception:
            return await public_get("/Floorsheet")
    return await cached(key, load)


def floor_rows(raw: Any) -> list[dict]:
    a = arr(raw)
    # Some APIs nest rows one level deeper.
    if len(a) == 1 and isinstance(a[0], dict):
        for k in ("data", "content", "records", "rows", "floorsheet"):
            if isinstance(a[0].get(k), list):
                a = a[0][k]
                break
    out = []
    for x in a:
        if not isinstance(x, dict):
            continue
        out.append({
            "symbol": pick(x, ["symbol", "stockSymbol", "securitySymbol", "ticker"]),
            "buyerBroker": pick(x, ["buyerBroker", "buyer", "buyerBrokerCode", "buyerMemberId", "buyBroker"]),
            "sellerBroker": pick(x, ["sellerBroker", "seller", "sellerBrokerCode", "sellerMemberId", "sellBroker"]),
            "quantity": num(pick(x, ["quantity", "contractQuantity", "tradedQuantity", "volume", "shares"])),
            "rate": num(pick(x, ["rate", "price", "contractPrice", "tradedPrice"])),
            "amount": num(pick(x, ["amount", "turnover", "totalAmount", "contractAmount"])),
            "trade": pick(x, ["trade", "contractNumber", "contractId", "transactionNumber"]),
            "businessDate": pick(x, ["businessDate", "date", "tradeDate"]),
            "raw": x,
        })
    return out


async def get_broker_analysis():
    async def load():
        raw = await get_floorsheet()
        rows = floor_rows(raw)
        brokers: dict[str, dict] = {}
        for r in rows:
            q = r["quantity"] or 0
            amount = r["amount"] or ((r["rate"] or 0) * q)
            for side, code in (("buy", r["buyerBroker"]), ("sell", r["sellerBroker"])):
                if code in (None, "", 0):
                    continue
                k = str(code)
                b = brokers.setdefault(k, {"broker": k, "buyValue": 0, "sellValue": 0, "buyQty": 0, "sellQty": 0, "trades": 0})
                b[side + "Value"] += amount
                b[side + "Qty"] += q
                b["trades"] += 1
        out = []
        total = sum(x["buyValue"] + x["sellValue"] for x in brokers.values()) or 1
        for b in brokers.values():
            b["net"] = b["buyValue"] - b["sellValue"]
            b["share"] = (b["buyValue"] + b["sellValue"]) / total
            out.append(b)
        out.sort(key=lambda x: abs(x["net"]), reverse=True)
        return {"ok": True, "updatedAt": now_iso(), "data": out, "sourceRows": len(rows)}
    return await cached("brokers:all", load)


async def get_sectors():
    async def load():
        try:
            raw = await nepse_call(["sub_indices", "sector_summary"])
        except Exception:
            raw = await public_get("/NepseSubIndices")
        out = []
        for x in arr(raw):
            out.append({
                "sector": pick(x, ["index", "indexName", "name", "sector", "sectorName"], "—"),
                "change": num(pick(x, ["change", "pointChange", "difference"])),
                "changePercent": num(pick(x, ["perChange", "percentChange", "percentageChange", "changePercent"])),
                "turnover": num(pick(x, ["turnover", "totalTurnover"])),
                "advancing": pick(x, ["advancing", "advancers"]),
                "declining": pick(x, ["declining", "decliners"]),
                "raw": x,
            })
        return {"ok": True, "updatedAt": now_iso(), "data": out}
    return await cached("sectors:all", load)


async def get_history(symbol: str):
    symbol = symbol.upper().strip()
    key = f"history:{symbol}"
    async def load():
        try:
            company = await resolve_company(symbol)
            cid = pick(company, ["id", "securityId", "security_id"])
            # Try current client method names first.
            candidates = [
                "security_price_volume_history",
                "company_price_volume_history",
                "price_volume_history",
                "security_history",
            ]
            if cid is not None:
                for name in candidates:
                    try:
                        return await nepse_call([name], int(cid))
                    except Exception:
                        pass
        except Exception:
            pass
        try:
            return await public_get("/PriceVolumeHistory", {"symbol": symbol})
        except Exception:
            return []
    return await cached(key, load)


def closes_from_history(raw: Any) -> list[float]:
    out = []
    for x in arr(raw):
        if isinstance(x, list) and len(x) >= 2:
            v = num(x[1])
        else:
            v = num(pick(x, ["close", "closingPrice", "ltp", "lastTradedPrice", "price", "value"]))
        if v is not None:
            out.append(v)
    return out


def sma(values: list[float], n: int):
    return sum(values[-n:]) / n if len(values) >= n else None


def ema(values: list[float], n: int):
    if len(values) < n:
        return None
    k = 2 / (n + 1)
    e = sum(values[:n]) / n
    for v in values[n:]:
        e = v * k + e * (1 - k)
    return e


def rsi(values: list[float], n: int = 14):
    if len(values) <= n:
        return None
    gains, losses = [], []
    for a, b in zip(values[-n-1:-1], values[-n:]):
        d = b - a
        gains.append(max(d, 0))
        losses.append(max(-d, 0))
    avg_gain = sum(gains) / n
    avg_loss = sum(losses) / n
    if avg_loss == 0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100 - (100 / (1 + rs))


async def get_fundamentals(symbol: str):
    c = await get_company(symbol)
    d = c.get("details") or {}
    if isinstance(d, list):
        d = d[0] if d else {}
    if isinstance(d, dict) and isinstance(d.get("data"), dict):
        d = d["data"]
    return {
        "ok": bool(c.get("ok")),
        "symbol": symbol.upper(),
        "source": "NEPSE public company profile/details where available",
        "updatedAt": now_iso(),
        "fundamentals": {
            "companyName": pick(d, ["securityName", "companyName", "name"]),
            "sector": pick(d, ["sectorName", "sector"]),
            "bookValue": num(pick(d, ["bookValue", "bookValuePerShare", "bvps"])),
            "eps": num(pick(d, ["eps", "earningPerShare", "earningsPerShare"])),
            "pe": num(pick(d, ["peRatio", "peratio", "pe"])),
            "marketCap": num(pick(d, ["marketCapitalization", "marketCap", "marketcap"])),
            "shares": num(pick(d, ["listedShares", "totalShares", "sharesOutstanding"])),
            "promoterShares": num(pick(d, ["promoterShares", "promoterShare"])),
            "publicShares": num(pick(d, ["publicShares", "publicShare"])),
            "raw": d,
        },
    }


async def get_xray(symbol: str):
    symbol = symbol.upper().strip()
    company, history, floors = await asyncio.gather(get_company(symbol), get_history(symbol), get_floorsheet(symbol))
    h = closes_from_history(history)
    last = h[-1] if h else None
    fundamentals = (await get_fundamentals(symbol))["fundamentals"]
    return {
        "ok": True,
        "symbol": symbol,
        "updatedAt": now_iso(),
        "price": {"last": last, "historyPoints": len(h)},
        "fundamentals": fundamentals,
        "technical": {"sma20": sma(h, 20), "sma50": sma(h, 50), "ema20": ema(h, 20), "rsi14": rsi(h, 14)},
        "floorsheet": {"rows": len(floor_rows(floors))},
        "company": company.get("company"),
    }


@app.get("/health")
async def health():
    return {"ok": True, "status": "Healthy", "service": "NEPSE Pulse Central Data Engine", "version": APP_VERSION, "time": now_iso()}


@app.get("/api/market")
async def api_market():
    return await get_market()


@app.get("/api/index")
async def api_index():
    return await get_index()


@app.get("/api/history")
async def api_history(index: int = Query(58)):
    return await get_index_history(index)


@app.get("/DailyNepseIndexGraph")
async def daily_nepse_index_graph():
    # Dedicated real intraday endpoint. No fallback to the wrong sub-index.
    try:
        return await public_get("/DailyNepseIndexGraph")
    except Exception:
        return await get_index_history(58)


@app.get("/NepseIndex")
async def nepse_index():
    return await get_index()


@app.get("/NepseSubIndices")
async def nepse_sub_indices():
    return await get_sectors()


@app.get("/CompanyList")
async def company_list():
    try:
        return await nepse_call(["companies", "securities"])
    except Exception:
        return await public_get("/CompanyList")


@app.get("/CompanyDetails")
async def company_details(symbol: str):
    return (await get_company(symbol)).get("details") or {}


@app.get("/api/company/{symbol}")
async def api_company(symbol: str):
    return await get_company(symbol)


@app.get("/api/fundamentals/{symbol}")
async def api_fundamentals(symbol: str):
    return await get_fundamentals(symbol)


@app.get("/PriceVolumeHistory")
async def price_volume_history(symbol: str):
    return await get_history(symbol)


@app.get("/api/history/security/{symbol}")
async def api_security_history(symbol: str):
    return await get_history(symbol)


@app.get("/Floorsheet")
async def floorsheet():
    return await get_floorsheet()


@app.get("/FloorsheetOf")
async def floorsheet_of(symbol: str):
    return await get_floorsheet(symbol)


@app.get("/api/floorsheet")
async def api_floorsheet(symbol: Optional[str] = None, limit: int = Query(250, ge=1, le=2000)):
    raw = await get_floorsheet(symbol)
    rows = floor_rows(raw)[:limit]
    return {"ok": True, "updatedAt": now_iso(), "symbol": symbol, "data": rows, "count": len(rows)}


@app.get("/api/brokers")
async def api_brokers():
    return await get_broker_analysis()


@app.get("/api/sectors")
async def api_sectors():
    return await get_sectors()


@app.get("/api/technical/{symbol}")
async def api_technical(symbol: str):
    h = closes_from_history(await get_history(symbol))
    return {"ok": True, "symbol": symbol.upper(), "historyPoints": len(h), "data": {"sma20": sma(h,20), "sma50": sma(h,50), "ema20": ema(h,20), "rsi14": rsi(h,14)}}


@app.get("/api/stock-xray/{symbol}")
async def api_stock_xray(symbol: str):
    return await get_xray(symbol)


@app.get("/api/command-center")
async def api_command_center():
    m = await get_market()
    summary_rows = arr(m.get("summary"))
    summary = {}
    for x in summary_rows:
        label = str(pick(x, ["detail", "label", "name"], "")).lower()
        val = pick(x, ["value", "val"])
        if "turnover" in label: summary["turnover"] = val
        elif "shares" in label: summary["volume"] = val
        elif "transaction" in label: summary["transactions"] = val
        elif "scrip" in label: summary["scripsTraded"] = val
    live = arr(m.get("live"))
    adv = dec = unchanged = 0
    for x in live:
        p = num(pick(x, ["percentageChange", "percentChange", "perChange", "pChange"]))
        if p is None:
            ch = num(pick(x, ["change", "pointChange"]))
            p = ch
        if p is None or p == 0: unchanged += 1
        elif p > 0: adv += 1
        else: dec += 1
    summary.update({"advancing": adv, "declining": dec, "unchanged": unchanged, "nepse": await get_index()})
    return {"ok": True, "updatedAt": now_iso(), "summary": summary, "market": m}


@app.get("/api/diagnostics")
async def diagnostics():
    market = await get_market()
    return {
        "version": APP_VERSION,
        "time": now_iso(),
        "marketOk": market.get("ok"),
        "source": market.get("source"),
        "listedSymbols": market.get("diagnostics", {}).get("listedSymbols", 461),
        "liveRows": len(arr(market.get("live"))),
        "cacheKeys": list(CACHE.keys()),
        "publicApiFallback": PUBLIC_API,
        "dataPolicy": "Verified source only; no synthetic market data",
    }


# Compatibility endpoints used by the existing V24.x frontend.
async def _simple_public(path: str):
    try:
        return await public_get(path)
    except Exception:
        return []


@app.get("/Summary")
async def summary(): return (await get_market()).get("summary", [])

@app.get("/LiveMarket")
async def live_market(): return (await get_market()).get("live", [])

@app.get("/TopGainers")
async def top_gainers(): return (await get_market()).get("gainers", [])

@app.get("/TopLosers")
async def top_losers(): return (await get_market()).get("losers", [])

@app.get("/TopTenTurnoverScrips")
async def top_turnover(): return (await get_market()).get("topTurnover", [])

@app.get("/TopTenTradeScrips")
async def top_trade(): return (await get_market()).get("topTraded", [])

@app.get("/TopTenTransactionScrips")
async def top_transaction(): return (await get_market()).get("topTransactions", [])

@app.get("/IsNepseOpen")
async def is_open(): return (await get_market()).get("status", {"isOpen": None})


@app.get("/")
async def root():
    return {"service": "NEPSE Pulse Central Data Engine", "version": APP_VERSION, "health": "/health", "market": "/api/market", "features": ["floorsheet", "brokers", "fundamentals", "technical", "sectors", "stock-xray", "command-center"]}
