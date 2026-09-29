import asyncio
import inspect
import logging
import os
import time
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from nepsepy import AsyncNepseClient

LOG = logging.getLogger("nepse-pulse-feed")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

app = FastAPI(title="NEPSE Pulse Central Feed", version="20.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[x.strip() for x in os.getenv("CORS_ORIGINS", "*").split(",") if x.strip()],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

CACHE: dict[str, Any] = {"market": None, "saved_at": 0.0}
CACHE_TTL = int(os.getenv("CACHE_TTL_SECONDS", "30"))


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def as_list(value: Any) -> list:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        for key in ("content", "data", "results", "items", "records"):
            if isinstance(value.get(key), list):
                return value[key]
    return []


def extract_rows(value: Any) -> list:
    rows = as_list(value)
    if rows:
        return rows
    if isinstance(value, dict):
        for key in ("content", "data", "results", "items", "records"):
            if isinstance(value.get(key), list):
                return value[key]
    return []


def safe_call(obj: Any, names: list[str], *args, **kwargs):
    for name in names:
        fn = getattr(obj, name, None)
        if callable(fn):
            return fn(*args, **kwargs)
    raise AttributeError(f"None of these methods exist: {', '.join(names)}")


async def maybe_await(value: Any) -> Any:
    if inspect.isawaitable(value):
        return await value
    return value


async def all_prices(client: Any) -> list:
    """Fetch all paginated today's prices without inventing missing rows."""
    first = await maybe_await(safe_call(client, ["today_price"], page=1, size=500))
    if isinstance(first, list):
        return first
    if not isinstance(first, dict):
        return []
    rows = extract_rows(first)
    total_pages = int(first.get("totalPages") or first.get("total_pages") or 1)
    total_pages = max(1, min(total_pages, 10))
    if total_pages == 1:
        return rows
    for page in range(2, total_pages + 1):
        try:
            part = await maybe_await(safe_call(client, ["today_price"], page=page, size=500))
            rows.extend(extract_rows(part))
        except Exception as exc:
            LOG.warning("price page %s failed: %s", page, exc)
            break
    return rows


async def call_optional(client: Any, names: list[str], *args, **kwargs):
    try:
        return await maybe_await(safe_call(client, names, *args, **kwargs))
    except Exception as exc:
        return {"__error__": str(exc)}


async def build_market(force: bool = False) -> dict:
    if not force and CACHE["market"] and time.time() - CACHE["saved_at"] < CACHE_TTL:
        cached = dict(CACHE["market"])
        cached["cache"] = {"hit": True, "ageSeconds": round(time.time() - CACHE["saved_at"], 1)}
        return cached

    errors: list[str] = []
    async with AsyncNepseClient() as client:
        # Keep requests controlled: the library itself handles the public session/token.
        status = await call_optional(client, ["market_status"])
        summary = await call_optional(client, ["market_summary", "get_market_summary"])
        index = await call_optional(client, ["nepse_index", "get_nepse_index"])
        prices = await call_optional(client, ["today_price"], page=1, size=500)
        if isinstance(prices, dict) and "__error__" in prices:
            prices = []
        elif isinstance(prices, dict):
            # Re-fetch through the pagination helper only if needed.
            try:
                prices = await all_prices(client)
            except Exception as exc:
                errors.append(f"prices: {exc}")
                prices = extract_rows(prices)

        # Top lists are useful, but market core remains valid if one optional call fails.
        gainers, losers, sectors, turnover, transactions, trades = await asyncio.gather(
            call_optional(client, ["top_gainers"]),
            call_optional(client, ["top_losers"]),
            call_optional(client, ["nepse_subindices", "sub_indices", "get_sub_indices"]),
            call_optional(client, ["top_turnover"]),
            call_optional(client, ["top_transaction"]),
            call_optional(client, ["top_trade"]),
        )

    def clean(name: str, value: Any):
        if isinstance(value, dict) and "__error__" in value:
            errors.append(f"{name}: {value['__error__']}")
            return []
        return value

    status = clean("status", status)
    summary = clean("summary", summary)
    index = clean("index", index)
    gainers = clean("gainers", gainers)
    losers = clean("losers", losers)
    sectors = clean("sectors", sectors)
    turnover = clean("turnover", turnover)
    transactions = clean("transactions", transactions)
    trades = clean("trades", trades)

    rows = extract_rows(prices)
    result = {
        "ok": bool(rows) or bool(index),
        "source": "NEPSE public frontend data via nepsepy",
        "providerType": "Unofficial public read-only client",
        "updatedAt": now_iso(),
        "marketOpen": status.get("isOpen") if isinstance(status, dict) else None,
        "status": status,
        "summary": summary,
        "index": index,
        "live": rows,
        "companies": [],
        "gainers": as_list(gainers),
        "losers": as_list(losers),
        "subindices": as_list(sectors),
        "turnover": as_list(turnover),
        "transactions": as_list(transactions),
        "trades": as_list(trades),
        "diagnostics": {
            "listedSymbols": 461,
            "coveredRows": len(rows),
            "errors": errors,
            "backend": "NEPSE Pulse V20 self-hosted",
        },
        "cache": {"hit": False, "ageSeconds": 0},
    }
    if result["ok"]:
        CACHE["market"] = result
        CACHE["saved_at"] = time.time()
    return result


@app.get("/")
async def root():
    return {"service": "NEPSE Pulse Central Feed", "version": "20.0.0", "status": "ok", "docs": "/docs"}


@app.get("/health")
async def health():
    return {"status": "healthy", "service": "nepse-pulse-feed", "version": "20.0.0"}


@app.get("/api/market")
async def market(force: bool = Query(False)):
    try:
        data = await build_market(force=force)
        return JSONResponse(data, status_code=200 if data.get("ok") else 503)
    except Exception as exc:
        LOG.exception("market feed failed")
        return JSONResponse({"ok": False, "error": str(exc), "updatedAt": now_iso()}, status_code=502)


@app.get("/api/gainers")
async def gainers():
    data = await build_market()
    return {"ok": data.get("ok", False), "data": data.get("gainers", []), "updatedAt": data.get("updatedAt")}


@app.get("/api/losers")
async def losers():
    data = await build_market()
    return {"ok": data.get("ok", False), "data": data.get("losers", []), "updatedAt": data.get("updatedAt")}


@app.get("/api/history")
async def history(index: str = "nepse"):
    try:
        async with AsyncNepseClient() as client:
            value = await call_optional(client, ["index_history", "get_index_history"], index)
        if isinstance(value, dict) and "__error__" in value:
            return JSONResponse({"ok": False, "error": value["__error__"]}, status_code=502)
        return {"ok": True, "index": index, "data": value, "updatedAt": now_iso()}
    except Exception as exc:
        return JSONResponse({"ok": False, "error": str(exc)}, status_code=502)


@app.get("/api/floorsheet")
async def floorsheet():
    try:
        async with AsyncNepseClient() as client:
            value = await call_optional(client, ["floorsheets", "floor_sheets", "get_floor_sheet"])
        if isinstance(value, dict) and "__error__" in value:
            return JSONResponse({"ok": False, "error": value["__error__"]}, status_code=502)
        return {"ok": True, "data": value, "updatedAt": now_iso()}
    except Exception as exc:
        return JSONResponse({"ok": False, "error": str(exc)}, status_code=502)


# Compatibility endpoints used by the existing NEPSE Pulse frontend.
@app.get("/Summary")
async def compat_summary():
    d = await build_market(); return d.get("summary", {})

@app.get("/NepseIndex")
async def compat_index():
    d = await build_market(); return d.get("index", {})

@app.get("/LiveMarket")
async def compat_live():
    d = await build_market(); return d.get("live", [])

@app.get("/TopGainers")
async def compat_gainers():
    d = await build_market(); return d.get("gainers", [])

@app.get("/TopLosers")
async def compat_losers():
    d = await build_market(); return d.get("losers", [])

@app.get("/NepseSubIndices")
async def compat_subindices():
    d = await build_market(); return d.get("subindices", [])

@app.get("/TopTenTurnoverScrips")
async def compat_turnover():
    d = await build_market(); return d.get("turnover", [])

@app.get("/TopTenTransactionScrips")
async def compat_transactions():
    d = await build_market(); return d.get("transactions", [])

@app.get("/TopTenTradeScrips")
async def compat_trades():
    d = await build_market(); return d.get("trades", [])

@app.get("/IsNepseOpen")
async def compat_open():
    d = await build_market(); return d.get("marketOpen")

@app.get("/CompanyList")
async def compat_companies():
    try:
        async with AsyncNepseClient() as client:
            value = await call_optional(client, ["companies", "company_list"])
        if isinstance(value, dict) and "__error__" in value:
            return JSONResponse({"ok": False, "error": value["__error__"]}, status_code=502)
        return value
    except Exception as exc:
        return JSONResponse({"ok": False, "error": str(exc)}, status_code=502)

@app.get("/SecurityList")
async def compat_securities():
    try:
        async with AsyncNepseClient() as client:
            value = await call_optional(client, ["securities", "security_list"])
        if isinstance(value, dict) and "__error__" in value:
            return JSONResponse({"ok": False, "error": value["__error__"]}, status_code=502)
        return value
    except Exception as exc:
        return JSONResponse({"ok": False, "error": str(exc)}, status_code=502)

@app.get("/Floorsheet")
async def compat_floorsheet():
    return await floorsheet()

@app.get("/DailyNepseIndexGraph")
async def compat_daily_index_graph():
    return await history("nepse")
