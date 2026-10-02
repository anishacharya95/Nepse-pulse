import asyncio
import time
import csv
import io
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import httpx
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

try:
    from nepsepy import AsyncNepseClient
except Exception:
    AsyncNepseClient = None

# Production NEPSE data layer.  Keep the existing FastAPI surface, but route
# core data operations through the reusable production SDK below.
try:
    from nepse import NEPSE as ProductionNEPSE
except Exception:
    ProductionNEPSE = None

PRODUCTION_NEPSE = None
PRODUCTION_SDK_ENABLED = False
PRODUCTION_SDK_ERROR = None
PRODUCTION_SDK_INIT_LOCK = asyncio.Lock()

async def ensure_production_sdk():
    global PRODUCTION_NEPSE, PRODUCTION_SDK_ENABLED, PRODUCTION_SDK_ERROR
    if PRODUCTION_NEPSE is not None:
        return PRODUCTION_NEPSE
    if ProductionNEPSE is None:
        PRODUCTION_SDK_ENABLED = False
        PRODUCTION_SDK_ERROR = "nepse.py could not be imported"
        raise RuntimeError(PRODUCTION_SDK_ERROR)
    async with PRODUCTION_SDK_INIT_LOCK:
        if PRODUCTION_NEPSE is not None:
            return PRODUCTION_NEPSE
        try:
            # Initialize lazily so a transient NEPSE/bootstrap/network problem
            # can never prevent FastAPI itself from starting.
            client = await asyncio.to_thread(ProductionNEPSE, cache_ttl=30)
            PRODUCTION_NEPSE = client
            PRODUCTION_SDK_ENABLED = True
            PRODUCTION_SDK_ERROR = None
            return client
        except Exception as exc:
            PRODUCTION_SDK_ENABLED = False
            PRODUCTION_SDK_ERROR = f"{type(exc).__name__}: {exc}"
            raise

async def production_call(method: str, *args, **kwargs):
    client = await ensure_production_sdk()
    fn = getattr(client, method, None)
    if fn is None:
        raise RuntimeError(f"nepse.py method not available: {method}")
    return await asyncio.to_thread(fn, *args, **kwargs)


APP_VERSION = "V33-CONSISTENT-NEPSE-DATA"
PUBLIC_API = "https://nepseapi.surajrimal.dev"
STATIC_API = "https://shubhamnpk.github.io/yonepse/data"
OPEN_DATA = "https://raw.githubusercontent.com/socrateai-official/nepse-open-data/main"
YONEPSE_API = "https://shubhamnpk.github.io/yonepse/data"
YONEPSE_RAW = "https://raw.githubusercontent.com/Shubhamnpk/yonepse/main/data"
NEPSE_INDEX_CSV = "https://raw.githubusercontent.com/binayabaral/nepal-market-data/main/data/nepse/NEPSE_INDEX.csv"
CACHE_TTL = {
    "market": 60,
    "index": 120,
    "floorsheet": 35,
    "company": 300,
    "history": 600,
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

# Keep ONE AsyncNepseClient alive for the whole FastAPI process.
# nepsepy stores the temporary NEPSE token inside the client and serializes
# requests per client. Creating a fresh client for every endpoint call throws
# away that session and can trigger repeated bootstraps/rate limits.
NEPSE_CLIENT: Optional[AsyncNepseClient] = None
NEPSE_CLIENT_INIT_LOCK = asyncio.Lock()
NEPSE_CALL_LOCK = asyncio.Lock()


async def get_nepse_client() -> AsyncNepseClient:
    global NEPSE_CLIENT
    if AsyncNepseClient is None:
        raise RuntimeError("nepsepy is not installed")
    if NEPSE_CLIENT is None:
        async with NEPSE_CLIENT_INIT_LOCK:
            if NEPSE_CLIENT is None:
                NEPSE_CLIENT = AsyncNepseClient()
    return NEPSE_CLIENT


@app.on_event("shutdown")
async def close_nepse_client():
    global NEPSE_CLIENT
    client = NEPSE_CLIENT
    NEPSE_CLIENT = None
    if client is not None:
        try:
            await client.close()
        except Exception:
            pass



@app.on_event("shutdown")
async def close_production_nepse():
    if PRODUCTION_NEPSE is not None:
        try:
            await asyncio.to_thread(PRODUCTION_NEPSE.close)
        except Exception:
            pass

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


def deep_rows(v: Any, preferred_keys: tuple[str, ...] = ()) -> list[dict]:
    """Extract the actual row list from NEPSE's nested response wrappers."""
    seen = set()
    def walk(x: Any, depth: int = 0):
        if depth > 6:
            return []
        if isinstance(x, list):
            rows = [r for r in x if isinstance(r, dict)]
            if rows:
                return rows
            return []
        if not isinstance(x, dict):
            return []
        oid = id(x)
        if oid in seen:
            return []
        seen.add(oid)
        keys = list(preferred_keys) + [
            "content", "data", "results", "result", "items", "records", "rows",
            "floorsheets", "floorSheets", "sectorIndices", "subIndices",
            "sectors", "indices", "index", "payload"
        ]
        for key in keys:
            child = x.get(key)
            if isinstance(child, (list, dict)):
                found = walk(child, depth + 1)
                if found:
                    return found
        # Some responses put the rows under an otherwise unknown single key.
        for child in x.values():
            if isinstance(child, (list, dict)):
                found = walk(child, depth + 1)
                if found:
                    return found
        return []
    return walk(v)


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


async def static_get(path: str):
    url = STATIC_API.rstrip("/") + path
    async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
        r = await client.get(url, headers={"Accept": "application/json"})
        r.raise_for_status()
        return r.json()


async def yonepse_get(path: str):
    """Static fallback feed maintained by the public YONEPSE dataset.

    It is only used when the primary NEPSE session/API does not return a
    usable dataset.  This prevents the UI from becoming a blank dashboard
    during a temporary upstream authentication/rate-limit failure.
    """
    last_error = None
    for base in (YONEPSE_API, YONEPSE_RAW):
        try:
            url = base.rstrip("/") + "/" + path.lstrip("/")
            async with httpx.AsyncClient(timeout=12, follow_redirects=True) as client:
                r = await client.get(url, headers={"Accept": "application/json"})
                r.raise_for_status()
                return r.json()
        except Exception as exc:
            last_error = exc
    raise RuntimeError(f"YONEPSE fallback failed for {path}: {last_error}")


async def yonepse_index_history():
    """Return the long-running NEPSE index OHLCV archive as normalized rows."""
    async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
        r = await client.get(NEPSE_INDEX_CSV, headers={"Accept": "text/csv"})
        r.raise_for_status()
        reader = csv.DictReader(io.StringIO(r.text))
        rows = []
        for row in reader:
            close = num(row.get("close"))
            if close is None:
                continue
            op = num(row.get("open")) or close
            hi = num(row.get("high")) or max(op, close)
            lo = num(row.get("low")) or min(op, close)
            rows.append({
                "date": row.get("published_date") or "",
                "open": op, "high": hi, "low": lo, "close": close,
                "volume": num(row.get("traded_quantity")),
                "turnover": num(row.get("traded_amount")),
                "perChange": num(row.get("per_change")),
            })
        return rows


async def first_ok(callables):
    errors=[]
    for label, fn in callables:
        try:
            value=await fn()
            if value is not None:
                return value, errors, label
        except Exception as e:
            errors.append(f"{label}: {e}")
    return None, errors, None


async def nepse_call(methods: list[str], *args, **kwargs):
    client = await get_nepse_client()
    errors = []
    # Let the shared nepsepy client manage its own session/request safety.
    # A second application-wide lock made independent dashboard calls run
    # strictly one after another and caused very slow first paint.
    for name in methods:
        fn = getattr(client, name, None)
        if fn is None:
            continue
        try:
            return await fn(*args, **kwargs)
        except TypeError:
            try:
                return await fn(*args)
            except Exception as e2:
                errors.append(f"{name}: {e2}")
        except Exception as e:
            errors.append(f"{name}: {e}")
    detail = "; ".join(errors[-4:])
    raise RuntimeError("No compatible nepsepy method succeeded" + (f": {detail}" if detail else ""))


NPT = timezone(timedelta(hours=5, minutes=45))


def normalize_open(v: Any) -> Optional[bool]:
    """NEPSE returns isOpen as strings like "OPEN"/"CLOSE". The frontend only
    treats a real boolean True as open, so always hand it True/False/None."""
    if isinstance(v, bool):
        return v
    if v is None:
        return None
    t = str(v).strip().upper().replace(" ", "_")
    if t in ("OPEN", "TRUE", "1", "MARKET_OPEN"):
        return True
    if t in ("CLOSE", "CLOSED", "FALSE", "0", "PRE_OPEN", "PRE_CLOSE", "MARKET_CLOSE", "MARKET_CLOSED"):
        return False
    return None


def schedule_open() -> bool:
    """Regular NEPSE hours: Sunday-Thursday, 11:00-15:00 Nepal time.
    Does not know public holidays, so it is only a fallback."""
    n = datetime.now(NPT)
    return n.weekday() in (6, 0, 1, 2, 3) and 11 <= n.hour < 15


async def _sdk_first(methods, *args, **kwargs):
    """Call the first available nepsepy method and return its raw payload."""
    errors = []
    client = await get_nepse_client()
    for name in methods:
        fn = getattr(client, name, None)
        if fn is None:
            continue
        try:
            return await fn(*args, **kwargs), name, errors
        except TypeError:
            try:
                return await fn(*args), name, errors
            except Exception as exc:
                errors.append(f"{name}: {type(exc).__name__}: {exc}")
        except Exception as exc:
            errors.append(f"{name}: {type(exc).__name__}: {exc}")
    raise RuntimeError("; ".join(errors[-6:]) or "No compatible nepsepy method available")


async def _fallback_breadth_from_yonepse():
    """Fetch current static market summary/live rows only when primary breadth is absent."""
    try:
        summary = await yonepse_get("market/summary.json")
    except Exception:
        summary = None
    found = _find_breadth_values(summary)
    if len(found) == 3:
        return found
    try:
        live = await yonepse_get("market/live.json")
    except Exception:
        live = None
    rows = deep_rows(live, ("content", "data", "results", "rows"))
    if rows:
        out = {"advancing": 0, "declining": 0, "unchanged": 0}
        for row in rows:
            p = num(pick(row, ["perChange", "percentageChange", "percentChange", "changePercent", "pChange", "percentage"]))
            if p is None:
                ch = num(pick(row, ["change", "pointChange", "difference"]))
                prev = num(pick(row, ["previousClose", "previousPrice", "prevClose", "previousLtp"]))
                if ch is not None and prev not in (None, 0):
                    p = ch / prev * 100
            if p is None:
                continue
            if p > 0: out["advancing"] += 1
            elif p < 0: out["declining"] += 1
            else: out["unchanged"] += 1
        if sum(out.values()) > 0:
            return out
    return {}

async def get_market():
    """Authoritative dashboard feed.

    IMPORTANT: dashboard movers/activity are never calculated from an archived
    snapshot and never taken from the blocked surajrimal API.  They come from
    nepsepy's public NEPSE session and its dedicated ranking methods.
    """
    async def load():
        errors = []

        async def one(label, methods, *args, **kwargs):
            try:
                raw, method, method_errors = await _sdk_first(methods, *args, **kwargs)
                errors.extend(method_errors)
                return raw, method
            except Exception as exc:
                errors.append(f"{label}: {type(exc).__name__}: {exc}")
                return None, None

        # Fetch independent datasets concurrently. The old version awaited
        # every dataset serially, multiplying network latency.
        core = await asyncio.gather(
            one("market_status", ["market_status"]),
            one("market_summary", ["market_summary", "summary"]),
            one("nepse_index", ["nepse_index", "get_nepse_index", "indices"]),
            one("live_market", ["live_market"]),
            one("top_gainers", ["top_gainers"]),
            one("top_losers", ["top_losers"]),
            one("top_turnover", ["top_ten_turnover_scrips", "top_turnover"]),
            one("top_trade", ["top_ten_trade_scrips", "top_trade"]),
            one("top_transaction", ["top_ten_transaction_scrips", "top_transaction"]),
        )
        (
            (status_raw,status_method),(summary_raw,summary_method),
            (index_raw,index_method),(live_raw,live_method),
            (gainers_raw,gainers_method),(losers_raw,losers_method),
            (turnover_raw,turnover_method),(trade_raw,trade_method),
            (transaction_raw,transaction_method)
        ) = core

        if not deep_rows(live_raw):
            live_raw, live_method = await one("today_price", ["today_price"], page=1, size=500)

        # If the primary session is empty (for example after a token/rate-limit
        # failure), load a single static snapshot from YONEPSE in parallel.
        # This is a fallback only; primary NEPSE data remains authoritative.
        fallback_used = False
        if not deep_rows(live_raw) or index_raw is None or summary_raw is None:
            try:
                static_index, static_summary, static_live, static_top = await asyncio.gather(
                    yonepse_get("market/indices.json"),
                    yonepse_get("market/summary.json"),
                    yonepse_get("market/live.json"),
                    yonepse_get("market/top_stocks.json"),
                    return_exceptions=True,
                )
                if not deep_rows(live_raw) and not isinstance(static_live, Exception):
                    live_raw, live_method = static_live, "yonepse_static_live"
                    fallback_used = True
                if (index_raw is None or not normalize_index(index_raw)) and not isinstance(static_index, Exception):
                    index_raw, index_method = static_index, "yonepse_static_indices"
                    fallback_used = True
                if summary_raw is None and not isinstance(static_summary, Exception):
                    summary_raw, summary_method = static_summary, "yonepse_static_summary"
                    fallback_used = True
                if not deep_rows(gainers_raw) and not isinstance(static_top, Exception) and isinstance(static_top, dict):
                    gainers_raw = static_top.get("top_gainer") or static_top.get("top_gainers") or []
                    gainers_method = "yonepse_static_top_gainers"
                    fallback_used = True
                if not deep_rows(losers_raw) and not isinstance(static_top, Exception) and isinstance(static_top, dict):
                    losers_raw = static_top.get("top_loser") or static_top.get("top_losers") or []
                    losers_method = "yonepse_static_top_losers"
                    fallback_used = True
                if not deep_rows(turnover_raw) and not isinstance(static_top, Exception) and isinstance(static_top, dict):
                    turnover_raw = static_top.get("top_turnover") or []
                    turnover_method = "yonepse_static_top_turnover"
                    fallback_used = True
            except Exception as exc:
                errors.append(f"yonepse fallback: {exc}")

        # Company directory is lazy: it is not needed to paint the dashboard.
        # Showing derived/static values would make the dashboard look live when
        # it is not. The frontend can display LIVE DATA UNAVAILABLE instead.
        gainers = deep_rows(gainers_raw) or []
        losers = deep_rows(losers_raw) or []
        turnover = deep_rows(turnover_raw) or []
        trade = deep_rows(trade_raw) or []
        transactions = deep_rows(transaction_raw) or []
        live_rows = deep_rows(live_raw, ("content", "data", "results", "rows")) or []

        companies = []
        company_method = None

        market_open = normalize_open(pick(status_raw, ["isOpen", "marketOpen", "open"], None))
        if market_open is None:
            market_open = normalize_open(pick(summary_raw, ["marketOpen", "isOpen", "open"], None))
        status = status_raw if isinstance(status_raw, (dict, list)) else {"isOpen": market_open}

        source_methods = {
            "status": status_method,
            "summary": summary_method,
            "index": index_method,
            "live": live_method,
            "gainers": gainers_method,
            "losers": losers_method,
            "turnover": turnover_method,
            "trade": trade_method,
            "transaction": transaction_method,
        }
        failed = [k for k,v in source_methods.items() if k not in ("companies",) and v is None]
        source = "YONEPSE static fallback" if fallback_used else "nepsepy public NEPSE session"
        stale = bool(fallback_used)

        explicit_breadth = _find_breadth_values(summary_raw)
        if len(explicit_breadth) < 3:
            explicit_breadth.update({k:v for k,v in _find_breadth_values(status_raw).items() if k not in explicit_breadth})
        if len(explicit_breadth) < 3:
            derived = {"advancing": 0, "declining": 0, "unchanged": 0}
            for row in live_rows:
                p = num(pick(row, ["perChange","percentageChange","percentChange","changePercent","pChange"]))
                if p is None:
                    ch = num(pick(row, ["change","pointChange","difference"]))
                    prev = num(pick(row, ["previousClose","previousPrice","prevClose","previousLtp"]))
                    if ch is not None and prev not in (None, 0): p = ch / prev * 100
                if p is None: continue
                if p > 0: derived["advancing"] += 1
                elif p < 0: derived["declining"] += 1
                else: derived["unchanged"] += 1
            explicit_breadth = derived

        if sum(int(explicit_breadth.get(k, 0) or 0) for k in ("advancing", "declining", "unchanged")) == 0:
            fb = await _fallback_breadth_from_yonepse()
            if len(fb) == 3:
                explicit_breadth = fb

        try:
            authoritative_index = await get_index()
        except Exception:
            authoritative_index = normalize_index(index_raw)

        return {
            "ok": bool(summary_raw is not None or live_rows or gainers or losers or authoritative_index),
            "source": source,
            "stale": stale,
            "providerType": "nepsepy public read-only NEPSE session",
            "updatedAt": now_iso(),
            "marketOpen": market_open,
            "status": status or {},
            "summary": summary_raw if summary_raw is not None else {},
            "summarySource": source,
            "breadth": explicit_breadth,
            "index": authoritative_index,
            "live": live_rows,
            "gainers": gainers,
            "losers": losers,
            "topTurnover": turnover,
            "topTraded": trade,
            "topTransactions": transactions,
            "companies": companies,
            "diagnostics": {
                "listedSymbols": max(461, len(companies)) if companies else 461,
                "coveredRows": len(live_rows),
                "sourceMethods": {**source_methods, "companies": company_method},
                "sourceErrors": errors,
                "rankingPolicy": "Dedicated nepsepy rankings only; no derived/static dashboard rankings",
                "failedSources": failed,
                "fallbackUsed": fallback_used,
            },
        }
    return await cached("market:core", load)


def normalize_index(raw: Any):
    """Return ONLY the headline NEPSE Index (id 58), never Sensitive/Float/etc."""
    def is_nepse(row: dict) -> bool:
        rid = pick(row, ["id", "indexId", "index_id", "exchangeIndexId"], None)
        name = str(pick(row, ["index", "symbol", "indexName", "name"], "") or "").strip().upper()
        try:
            if rid is not None and int(float(rid)) == 58:
                return True
        except Exception:
            pass
        return name in {"NEPSE", "NEPSE INDEX"} or name.startswith("NEPSE INDEX")

    def rows_from(x):
        if isinstance(x, list):
            return [r for r in x if isinstance(r, dict)]
        if isinstance(x, dict):
            for key in ("data", "content", "results", "result", "items", "records", "rows", "indices"):
                v = x.get(key)
                if isinstance(v, list):
                    rows = [r for r in v if isinstance(r, dict)]
                    if rows:
                        return rows
                if isinstance(v, dict) and is_nepse(v):
                    return [v]
            if is_nepse(x):
                return [x]
        return []

    for row in rows_from(raw):
        if is_nepse(row):
            return row
    return {}


def _latest_archive_index(rows: list[dict]) -> dict:
    """Return the newest completed NEPSE session from the verified index archive."""
    if not rows:
        return {}
    r = rows[-1]
    close = num(r.get("close"))
    if close is None:
        return {}
    prev = None
    if len(rows) >= 2:
        prev = num(rows[-2].get("close"))
    change = num(r.get("perChange"))
    point = (close - prev) if prev is not None else None
    if point is not None and change is None and prev:
        change = point / prev * 100
    return {
        "id": 58,
        "index": "NEPSE Index",
        "indexName": "NEPSE Index",
        "close": close,
        "currentValue": close,
        "value": close,
        "open": num(r.get("open")),
        "high": num(r.get("high")),
        "low": num(r.get("low")),
        "previousClose": prev,
        "change": point,
        "perChange": change,
        "businessDate": r.get("date") or "",
        "generatedTime": r.get("date") or "",
        "source": "verified NEPSE_INDEX.csv completed-session archive",
    }


def _archive_is_recent(rows: list[dict], max_age_days: int = 7) -> bool:
    if not rows:
        return False
    raw = str(rows[-1].get("date") or "")[:10]
    try:
        d = datetime.fromisoformat(raw).date()
        return (datetime.now(NPT).date() - d).days <= max_age_days and d <= datetime.now(NPT).date()
    except Exception:
        return False

async def _verified_closed_index() -> dict:
    try:
        rows = await yonepse_index_history()
        if _archive_is_recent(rows, 7):
            return _latest_archive_index(rows)
    except Exception:
        pass
    return {}


def _find_breadth_values(value: Any) -> dict:
    """Find explicit Adv/Dec/Unchanged counts without mixing stock rows."""
    aliases = {
        "advancing": (
            "advancing", "advancers", "advance", "advanced", "advancedscrips",
            "adv", "advances", "advancescrips", "advancedscrips", "positive", "positivecount",
        ),
        "declining": (
            "declining", "decliners", "decline", "declined", "decliningscrips",
            "dec", "declines", "declinescrips", "negative", "negativecount",
        ),
        "unchanged": (
            "unchanged", "unchangedcount", "unchangedstocks", "unchangedscrips",
            "unch", "unchanges", "unchangedsecurities", "unchangedcount",
        ),
    }
    found = {}
    def walk(x):
        if isinstance(x, dict):
            norm = {str(k).lower().replace("_", "").replace("-", ""): v for k,v in x.items()}
            for out, keys in aliases.items():
                if out in found:
                    continue
                for k in keys:
                    nk = k.lower().replace("_", "").replace("-", "")
                    if nk in norm:
                        v = num(norm[nk])
                        if v is not None:
                            found[out] = int(v)
                            break
            for v in x.values():
                if len(found) == 3:
                    break
                walk(v)
        elif isinstance(x, list):
            for v in x:
                if len(found) == 3:
                    break
                walk(v)
    walk(value)
    return found

async def get_index():
    """Return a consistent headline NEPSE snapshot.

    The index endpoint itself is preferred for both open and closed sessions.
    A completed-session archive is only a fallback, preventing a stale archive
    from overriding a correct current NEPSE value.
    """
    async def load():
        errors = []
        try:
            raw = await nepse_call(["nepse_indices", "nepse_index", "get_nepse_index", "indices"])
            idx = normalize_index(raw)
            if idx:
                return idx
            errors.append("nepsepy index response did not contain NEPSE Index id 58")
        except Exception as exc:
            errors.append(f"nepsepy: {exc}")

        try:
            raw = await public_get("/NepseIndex")
            idx = normalize_index(raw)
            if idx:
                return idx
            errors.append("public /NepseIndex response did not contain NEPSE Index id 58")
        except Exception as exc:
            errors.append(f"public: {exc}")

        # Static YONEPSE snapshot is preferred to the older CSV archive when
        # the live adapters fail. It is updated independently by GitHub Actions.
        try:
            raw = await yonepse_get("market/indices.json")
            idx = normalize_index(raw)
            if idx:
                idx = dict(idx)
                idx.setdefault("source", "YONEPSE market/indices.json")
                return idx
        except Exception as exc:
            errors.append(f"yonepse indices: {exc}")

        archived = await _verified_closed_index()
        if archived:
            return archived
        return {}
    return await cached("index:current", load)

def normalize_index_history_rows(raw: Any, wanted_id: int = 58) -> list[dict]:
    """Normalize and validate ONLY the requested headline NEPSE index series."""
    rows = deep_rows(raw, ("content", "data", "results", "result", "records", "rows", "history", "index"))
    if not rows and isinstance(raw, list):
        rows = raw

    def matches(r: dict) -> bool:
        rid = pick(r, ["id", "indexId", "index_id", "exchangeIndexId"])
        name = str(pick(r, ["index", "indexName", "name", "symbol"], "") or "").strip().upper()
        try:
            if rid is not None and int(float(rid)) == int(wanted_id):
                return True
        except Exception:
            pass
        return name in {"NEPSE", "NEPSE INDEX"} or name.startswith("NEPSE INDEX")

    dict_rows=[r for r in rows if isinstance(r,dict)]
    labelled=[r for r in dict_rows if any(pick(r,[k]) is not None for k in ["id","indexId","index","indexName","name"])]
    candidates=[r for r in labelled if matches(r)] if any(matches(r) for r in labelled) else dict_rows
    out=[]
    for r in candidates:
        date=pick(r,["date","businessDate","publishedDate","generatedTime","tradingDate","tradeDate","timestamp","time","datetime","dateTime"])
        o=num(pick(r,["open","openingPrice","openPrice"]))
        h=num(pick(r,["high","highPrice","dayHigh"]))
        l=num(pick(r,["low","lowPrice","dayLow"]))
        c=num(pick(r,["close","closingPrice","currentValue","indexValue","value","ltp","lastPrice","price"]))
        if c is None: continue
        if o is None: o=c
        if h is None: h=max(o,c)
        if l is None: l=min(o,c)
        out.append({"date":str(date or ""),"open":o,"high":h,"low":l,"close":c,"volume":None})
    seen=set(); clean=[]
    for r in sorted(out,key=lambda x:str(x.get("date") or "")):
        k=(r["date"],r["open"],r["high"],r["low"],r["close"])
        if k not in seen: seen.add(k); clean.append(r)
    return clean


async def get_index_history(index_id: int = 58):
    try: index_id=int(index_id)
    except Exception: index_id=58
    key=f"history:{index_id}"
    async def load():
        errors=[]
        try:
            raw=await nepse_call(["index_history"], index_id, 1, 1000)
            rows=normalize_index_history_rows(raw,index_id)
            if rows:
                return {"ok":True,"source":"NEPSE via nepsepy:index_history","data":rows,"errors":errors,"updatedAt":now_iso()}
            errors.append("nepsepy index_history returned no usable NEPSE rows")
        except Exception as e:
            errors.append(f"nepsepy.index_history: {e}")
        try:
            raw=await public_get("/DailyNepseIndexGraph")
            rows=normalize_index_history_rows(raw,index_id)
            if rows:
                return {"ok":True,"source":"NEPSE public DailyNepseIndexGraph","data":rows,"errors":errors,"updatedAt":now_iso()}
            errors.append("public graph returned no usable NEPSE rows")
        except Exception as e:
            errors.append(f"public graph: {e}")
        if int(index_id) == 58:
            try:
                # First try the current YONEPSE market history feed.
                raw = await yonepse_get("market/history.json")
                rows = normalize_index_history_rows(raw, index_id)
                if rows:
                    return {"ok":True,"source":"YONEPSE market/history.json","data":rows,"errors":errors,"updatedAt":now_iso()}
                errors.append("YONEPSE market/history.json returned no usable NEPSE rows")
            except Exception as e:
                errors.append(f"YONEPSE market/history.json: {e}")
            try:
                rows = await yonepse_index_history()
                if rows:
                    return {"ok":True,"source":"NEPSE_INDEX.csv headline NEPSE index archive","data":rows,"errors":errors,"updatedAt":now_iso()}
                errors.append("NEPSE_INDEX.csv returned no rows")
            except Exception as e:
                errors.append(f"NEPSE_INDEX.csv: {e}")
        return {"ok":False,"source":None,"data":[],"errors":errors,"updatedAt":now_iso()}
    return await cached(key,load)


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


async def get_fundamentals(symbol: str):
    """Load the full public company/fundamental tabs for one NEPSE security.

    NEPSE exposes fundamentals across several public company-tab endpoints:
    profile/detail, financial reports, corporate actions, dividends, AGM,
    board, company news, and the market/security snapshot.  Keep all returned
    records in the response so the UI can show fields added by NEPSE without
    hard-coding a small subset of metrics.
    """
    symbol = symbol.upper().strip()
    key = f"fundamentals:{symbol}"

    async def load():
        errors: list[str] = []
        try:
            company = await resolve_company(symbol)
        except Exception as e:
            company = None
            errors.append(f"companies: {e}")

        cid = pick(company, ["id", "securityId", "security_id"])
        if cid is None:
            # CompanyDetails can still provide useful data when the directory
            # lookup is temporarily unavailable.
            try:
                c = await get_company(symbol)
                company = c.get("company") or company
                cid = pick(company, ["id", "securityId", "security_id"])
            except Exception as e:
                errors.append(f"company: {e}")

        if cid is None:
            return {
                "ok": False, "symbol": symbol, "company": company,
                "profile": {}, "market": {}, "valuation": {},
                "financials": [], "financialReports": [],
                "corporateActions": [], "dividends": [], "board": [],
                "agm": [], "companyNews": [], "errors": errors + ["Security id not found"]
            }

        async def call(label, methods, *args):
            try:
                return label, await nepse_call(methods, *args)
            except Exception as e:
                errors.append(f"{label}: {e}")
                return label, None

        results = await asyncio.gather(
            call("profile", ["security_profile", "security_detail"], int(cid)),
            call("detail", ["security_detail", "security_profile"], int(cid)),
            call("financialReports", ["financial_reports"], int(cid)),
            call("corporateActions", ["corporate_actions"], int(cid)),
            call("dividends", ["dividends"], int(cid)),
            call("board", ["board_of_directors"], int(cid)),
            call("agm", ["agm"], int(cid)),
            call("companyNews", ["security_company_news"], int(cid)),
            call("market", ["market_security", "security_market_picture"], int(cid)),
            call("marketPicture", ["security_market_picture", "market_security"], int(cid)),
        )
        raw = {k: v for k, v in results if v is not None}

        profile = raw.get("profile") or raw.get("detail") or {}
        detail = raw.get("detail") or {}
        market = raw.get("market") or raw.get("marketPicture") or {}

        # Financial report rows can be wrapped in content/data/results or can
        # arrive as a direct list depending on the NEPSE response version.
        reports = deep_rows(raw.get("financialReports"))
        if not reports:
            reports = arr(raw.get("financialReports"))

        def normalize_report(x):
            if not isinstance(x, dict):
                return {"value": x}
            return {
                "fiscalYear": pick(x, ["fiscalYear", "fiscalyear", "fiscal_year", "year"]),
                "quarter": pick(x, ["quarter", "quarterName", "period"]),
                "publishedDate": pick(x, ["publishedDate", "published_date", "reportDate", "date"]),
                "eps": num(pick(x, ["eps", "earningPerShare", "earningsPerShare"])),
                "bookValue": num(pick(x, ["bookValue", "book_value", "netWorthPerShare"])),
                "pe": num(pick(x, ["pe", "peRatio", "priceEarningRatio"])),
                "pb": num(pick(x, ["pb", "pbRatio", "priceBookRatio"])),
                "roe": num(pick(x, ["roe", "returnOnEquity"])),
                "netProfit": num(pick(x, ["netProfit", "profit", "netIncome"])),
                "revenue": num(pick(x, ["revenue", "totalRevenue", "income"])),
                "assets": num(pick(x, ["totalAssets", "assets"])),
                "liabilities": num(pick(x, ["totalLiabilities", "liabilities"])),
                "equity": num(pick(x, ["totalEquity", "equity", "shareholdersEquity"])),
                "raw": x,
            }

        financials = [normalize_report(x) for x in reports]

        # Flatten likely top-level profile/market fields into a stable summary
        # while retaining the original objects for every other field.
        def first_num(*objects, keys):
            for obj in objects:
                v = num(pick(obj, keys))
                if v is not None:
                    return v
            return None

        latest = financials[0] if financials else {}
        last_price = first_num(market, detail, keys=["lastTradedPrice", "ltp", "lastPrice", "close", "closingPrice"])
        listed_shares = first_num(profile, detail, keys=["listedShares", "totalListedShares", "numberOfListedShares", "totalShares", "shareOutstanding"])
        market_cap = first_num(market, profile, detail, keys=["marketCapitalization", "marketCap", "marketCapitalizationValue", "marCap"])
        if market_cap is None and last_price is not None and listed_shares is not None:
            market_cap = last_price * listed_shares

        eps = latest.get("eps")
        book_value = latest.get("bookValue")
        pe = latest.get("pe")
        pb = latest.get("pb")
        roe = latest.get("roe")
        if pe is None and last_price is not None and eps not in (None, 0): pe = last_price / eps
        if pb is None and last_price is not None and book_value not in (None, 0): pb = last_price / book_value
        dividend_rows = deep_rows(raw.get("dividends")) or arr(raw.get("dividends"))
        latest_dividend = dividend_rows[0] if dividend_rows else {}
        cash_div = num(pick(latest_dividend, ["cashDividend", "cash", "cashPercentage"]))
        bonus_div = num(pick(latest_dividend, ["bonusDividend", "bonus", "bonusPercentage"]))
        dividend_yield = (cash_div / last_price * 100) if cash_div is not None and last_price not in (None, 0) else None

        return {
            "ok": bool(profile or detail or financials or market or raw.get("dividends")),
            "symbol": symbol,
            "securityId": int(cid),
            "company": company or {},
            "profile": profile if isinstance(profile, dict) else {"data": profile},
            "detail": detail if isinstance(detail, dict) else {"data": detail},
            "market": market if isinstance(market, dict) else {"data": market},
            "valuation": {
                "ltp": last_price, "marketCap": market_cap,
                "listedShares": listed_shares, "eps": eps,
                "bookValue": book_value, "pe": pe, "pb": pb,
                "roe": roe, "cashDividend": cash_div,
                "bonusDividend": bonus_div, "dividendYield": dividend_yield,
            },
            "fundamentals": {
                "eps": eps, "bookValue": book_value, "pe": pe, "pb": pb,
                "roe": roe, "marketCap": market_cap, "listedShares": listed_shares,
                "dividendYield": dividend_yield,
            },
            "financials": financials,
            "financialReports": raw.get("financialReports") or [],
            "corporateActions": raw.get("corporateActions") or [],
            "dividends": raw.get("dividends") or [],
            "board": raw.get("board") or [],
            "agm": raw.get("agm") or [],
            "companyNews": raw.get("companyNews") or [],
            "sources": {
                "profile": "NEPSE public company profile",
                "financialReports": "NEPSE public financial reports",
                "corporateActions": "NEPSE public corporate actions",
                "dividends": "NEPSE public dividend data",
                "market": "NEPSE public market/security data",
            },
            "errors": errors,
            "updatedAt": now_iso(),
        }

    return await cached(key, load)


async def get_floorsheet(symbol: Optional[str] = None):
    """Return the complete current-session floorsheet, normalized to a flat list.

    NEPSE paginates the floor sheet.  nepsepy exposes the same page/size
    parameters used by the website, so fetch every page and cache the result
    for the current session.  The browser never has to make dozens of NEPSE
    requests itself.
    """
    key = f"floorsheet:{symbol.upper().strip() if symbol else 'all'}"

    async def load():
        errors = []
        wanted = symbol.upper().strip() if symbol else None

        async def fetch_all_with_nepse():
            company_id = None
            if wanted:
                company = await resolve_company(wanted)
                company_id = pick(company, ["id", "securityId", "security_id"])

            page_size = 500
            kwargs = {"page": 1, "size": page_size}
            if company_id is not None:
                kwargs["stock_id"] = int(company_id)
            first = await nepse_call(["floorsheets"], **kwargs)
            first_rows = floor_rows(first)
            wrapper = first.get("floorsheets") if isinstance(first, dict) else None
            total_pages = 1
            total_trades = None
            if isinstance(wrapper, dict):
                try: total_pages = max(1, int(wrapper.get("totalPages") or 1))
                except Exception: total_pages = 1
                try: total_trades = int(wrapper.get("totalElements"))
                except Exception: pass
            if isinstance(first, dict):
                try: total_trades = int(first.get("totalTrades")) if total_trades is None else total_trades
                except Exception: pass

            rows = list(first_rows)
            # Guard against a malformed server response claiming an absurd page count.
            total_pages = min(total_pages, 500)
            for page in range(2, total_pages + 1):
                kwargs = {"page": page, "size": page_size}
                if company_id is not None:
                    kwargs["stock_id"] = int(company_id)
                data = await nepse_call(["floorsheets"], **kwargs)
                page_rows = floor_rows(data)
                if not page_rows:
                    break
                rows.extend(page_rows)
                # Stop if the API reports the complete expected count.
                if total_trades is not None and len(rows) >= total_trades:
                    break
            if wanted:
                rows = [r for r in rows if str(r.get("symbol") or "").upper() == wanted]
            return rows

        try:
            rows = await fetch_all_with_nepse()
            if rows:
                return rows
            errors.append("NEPSE floorsheet returned no rows")
        except Exception as e:
            errors.append(f"NEPSE floorsheet: {e}")

        # Compatibility API fallback.
        for path, params in (
            ("/FloorsheetOf", {"symbol": wanted} if wanted else None),
            ("/Floorsheet", None),
        ):
            if not wanted and path == "/FloorsheetOf":
                continue
            try:
                raw = await public_get(path, params)
                rows = floor_rows(raw)
                if rows:
                    if wanted:
                        rows = [r for r in rows if str(r.get("symbol") or "").upper() == wanted]
                    return rows
            except Exception as e:
                errors.append(f"{path}: {e}")

        # Historical open-data archive is a final fallback after the live feed.
        try:
            today = datetime.now().strftime("%Y-%m-%d")
            raw = await static_get(f"/floor_sheet/daily/{today}.json")
            rows = floor_rows(raw)
            if wanted:
                rows = [r for r in rows if str(r.get("symbol") or "").upper() == wanted]
            if rows:
                return rows
        except Exception as e:
            errors.append(f"daily archive: {e}")

        return []

    return await cached(key, load)


def floor_rows(raw: Any) -> list[dict]:
    # NEPSE's current payload is commonly:
    # {"floorsheets": {"content": [...]}}.  Older wrappers may use data/content
    # directly. Walk those wrappers until the actual row list is reached.
    def unwrap(value: Any, depth: int = 0) -> list:
        if isinstance(value, list):
            return value
        if not isinstance(value, dict) or depth > 4:
            return []
        for k in (
            "floorsheets", "floorSheets", "data", "content", "results",
            "result", "items", "records", "rows", "floorSheet", "floorsheet"
        ):
            child = value.get(k)
            if isinstance(child, list):
                return child
            if isinstance(child, dict):
                found = unwrap(child, depth + 1)
                if found:
                    return found
        return [value] if any(k in value for k in ("stockSymbol", "symbol", "contractId")) else []

    a = unwrap(raw)
    out = []
    for x in a:
        if not isinstance(x, dict):
            continue
        # Prefer broker names when present; fall back to member/broker IDs.
        buyer = pick(x, [
            "buyerBrokerName", "buyerBroker", "buyer", "buyerBrokerCode",
            "buyerMemberId", "buyBroker"
        ])
        seller = pick(x, [
            "sellerBrokerName", "sellerBroker", "seller", "sellerBrokerCode",
            "sellerMemberId", "sellBroker"
        ])
        out.append({
            "symbol": pick(x, ["stockSymbol", "symbol", "securitySymbol", "ticker"]),
            "buyerBroker": buyer,
            "sellerBroker": seller,
            "buyerBrokerName": pick(x, ["buyerBrokerName", "buyerBroker", "buyer"]),
            "sellerBrokerName": pick(x, ["sellerBrokerName", "sellerBroker", "seller"]),
            "buyerBrokerId": pick(x, ["buyerMemberId", "buyerBrokerCode", "buyerBroker"]),
            "sellerBrokerId": pick(x, ["sellerMemberId", "sellerBrokerCode", "sellerBroker"]),
            "quantity": num(pick(x, ["contractQuantity", "quantity", "tradedQuantity", "volume", "shares"])),
            "rate": num(pick(x, ["contractRate", "rate", "price", "tradedPrice"])),
            "amount": num(pick(x, ["contractAmount", "amount", "turnover", "totalAmount"])),
            "trade": pick(x, ["contractId", "trade", "contractNumber", "transactionNumber"]),
            "businessDate": pick(x, ["businessDate", "date", "tradeDate"]),
            "tradeTime": pick(x, ["tradeTime", "time"]),
            "securityId": pick(x, ["stockId", "securityId", "id"]),
            "securityName": pick(x, ["securityName", "name"]),
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
        errors = []
        raw = None
        # Method names have changed across nepsepy releases; try the known
        # sector/sub-index variants before using the public/static fallbacks.
        for methods in (
            ["sub_indices"], ["sector_indices"], ["nepse_sub_indices"],
            ["nepse_subindices"], ["sector_summary"], ["sector_indices_summary"],
        ):
            try:
                raw = await nepse_call(methods)
                rows = deep_rows(raw, ("content", "data", "subIndices", "sectorIndices"))
                if rows:
                    break
            except Exception as e:
                errors.append(f"{methods[0]}: {e}")
        if not deep_rows(raw):
            try:
                raw = await public_get("/NepseSubIndices")
            except Exception as e:
                errors.append(f"public: {e}")
        rows = deep_rows(raw, ("content", "data", "subIndices", "sectorIndices"))
        if not rows:
            try:
                raw = await static_get("/market/sector_indices.json")
                rows = deep_rows(raw)
            except Exception as e:
                errors.append(f"static: {e}")

        out = []
        for x in rows:
            name = pick(x, [
                "indexName", "index_name", "sectorName", "sector", "name",
                "index", "symbol", "description"
            ], "—")
            change = num(pick(x, [
                "perChange", "percentageChange", "percentChange", "changePercent",
                "percentage_change", "percent_change", "change", "pointChange", "difference"
            ]))
            index_value = num(pick(x, [
                "currentValue", "indexValue", "value", "index", "close", "lastValue"
            ]))
            out.append({
                "sector": name,
                "name": name,
                "change": num(pick(x, ["pointChange", "difference", "change"])) or 0 if change is None else change,
                "changePercent": change,
                "indexValue": index_value,
                "turnover": num(pick(x, ["turnover", "totalTurnover", "turnoverValue", "totalTradeValue"])),
                "volume": num(pick(x, ["volume", "totalTradedQuantity", "tradedShares", "sharesTraded"])),
                "advancing": num(pick(x, ["advancing", "advancers", "advance"])),
                "declining": num(pick(x, ["declining", "decliners", "decline"])),
                "unchanged": num(pick(x, ["unchanged", "unchangedCount"])),
                "raw": x,
            })
        return {"ok": bool(out), "updatedAt": now_iso(), "data": out, "errors": errors}
    return await cached("sectors:all", load)


def normalize_history_rows(raw: Any) -> list[dict]:
    """Normalize NEPSE/backup chart responses into compact OHLCV rows."""
    rows = deep_rows(raw, ("content", "data", "results", "result", "records", "rows", "history"))
    if not rows and isinstance(raw, list):
        rows = raw
    out = []
    for i, x in enumerate(rows):
        if isinstance(x, dict):
            date = pick(x, [
                "date", "businessDate", "publishedDate", "tradingDate", "tradeDate",
                "timestamp", "time", "datetime", "dateTime", "businessdate"
            ], "")
            o = num(pick(x, ["open", "openingPrice", "openPrice"]))
            h = num(pick(x, ["high", "highPrice", "dayHigh"]))
            l = num(pick(x, ["low", "lowPrice", "dayLow"]))
            c = num(pick(x, ["close", "closingPrice", "ltp", "lastPrice", "lastTradedPrice", "price", "value"]))
            v = num(pick(x, ["volume", "tradedQuantity", "tradedShares", "sharesTraded", "quantity", "totalTradedQuantity"]))
        elif isinstance(x, (list, tuple)):
            # Common chart payloads: [date, open, high, low, close, volume]
            # and compact payloads: [timestamp, close].
            date = x[0] if len(x) else i
            o = num(x[1]) if len(x) > 4 else None
            h = num(x[2]) if len(x) > 4 else None
            l = num(x[3]) if len(x) > 4 else None
            c = num(x[4]) if len(x) > 4 else (num(x[1]) if len(x) > 1 else None)
            v = num(x[5]) if len(x) > 5 else None
        else:
            continue
        if c is None and o is None:
            continue
        if c is None:
            c = o
        if o is None:
            o = c
        if h is None:
            h = max(o, c)
        if l is None:
            l = min(o, c)
        out.append({"date": str(date or ""), "open": o, "high": h, "low": l, "close": c, "volume": v})

    def sort_key(r):
        d = str(r.get("date") or "")
        try:
            return float(d)
        except Exception:
            return d

    # Stable sort + de-duplicate by date so the browser never receives
    # repeated candles from overlapping endpoint fallbacks.
    out.sort(key=sort_key)
    dedup = {}
    for r in out:
        dedup[str(r["date"])] = r
    return list(dedup.values())


async def static_daily_history(symbol: str) -> list[dict]:
    """Fast public static OHLCV archive used when NEPSE history endpoints fail."""
    urls = [
        f"https://binayabaral.github.io/nepal-market-data/data/nepse/{symbol}.csv",
        f"https://raw.githubusercontent.com/binayabaral/nepal-market-data/main/data/nepse/{symbol}.csv",
    ]
    for url in urls:
        try:
            async with httpx.AsyncClient(timeout=8, follow_redirects=True) as client:
                r = await client.get(url, headers={"Accept": "text/csv"})
                if r.status_code != 200 or not r.text.strip():
                    continue
                reader = csv.DictReader(io.StringIO(r.text))
                out=[]
                for row in reader:
                    c=num(row.get("close"))
                    if c is None: continue
                    o=num(row.get("open")) or c
                    h=num(row.get("high")) or max(o,c)
                    l=num(row.get("low")) or min(o,c)
                    out.append({"date":row.get("published_date") or row.get("date") or "","open":o,"high":h,"low":l,"close":c,"volume":num(row.get("traded_quantity")),"turnover":num(row.get("traded_amount"))})
                if out:
                    return out
        except Exception:
            continue
    return []

async def github_daily_history(symbol: str) -> list[dict]:
    """Fast OHLCV fallback used when the live chart endpoint lacks OHLC fields."""
    url = f"https://raw.githubusercontent.com/binayabaral/nepal-market-data/main/data/nepse/{symbol}.csv"
    try:
        async with httpx.AsyncClient(timeout=10, follow_redirects=True) as client:
            r = await client.get(url, headers={"Accept": "text/csv"})
            if r.status_code != 200:
                return []
            reader = csv.DictReader(io.StringIO(r.text))
            out = []
            for row in reader:
                c = num(row.get("close"))
                if c is None:
                    continue
                out.append({
                    "date": row.get("published_date") or "",
                    "open": num(row.get("open")),
                    "high": num(row.get("high")),
                    "low": num(row.get("low")),
                    "close": c,
                    "volume": num(row.get("traded_quantity")),
                    "turnover": num(row.get("traded_amount")),
                })
            return out
    except Exception:
        return []


async def get_history(symbol: str):
    symbol = symbol.upper().strip()
    key = f"history:{symbol}"
    async def load():
        errors = []
        try:
            company = await resolve_company(symbol)
            cid = pick(company, ["id", "securityId", "security_id"])
            if cid is not None:
                for name in [
                    "security_price_volume_history",
                    "company_price_volume_history",
                    "price_volume_history",
                    "security_history",
                ]:
                    try:
                        raw = await nepse_call([name], int(cid))
                        rows = normalize_history_rows(raw)
                        if rows and any(r["open"] != r["close"] or r["high"] != r["low"] for r in rows):
                            return {"ok": True, "symbol": symbol, "source": f"NEPSE via nepsepy:{name}", "data": rows, "updatedAt": now_iso(), "errors": errors}
                        if rows:
                            errors.append(f"{name}: returned close-only history")
                    except Exception as e:
                        errors.append(f"{name}: {e}")
        except Exception as e:
            errors.append(f"company: {e}")

        try:
            raw = await public_get("/PriceVolumeHistory", {"symbol": symbol})
            rows = normalize_history_rows(raw)
            if rows and any(r["open"] != r["close"] or r["high"] != r["low"] for r in rows):
                return {"ok": True, "symbol": symbol, "source": "NEPSE public PriceVolumeHistory", "data": rows, "updatedAt": now_iso(), "errors": errors}
            if rows:
                errors.append("public PriceVolumeHistory returned close-only history")
        except Exception as e:
            errors.append(f"public history: {e}")

        backup = await static_daily_history(symbol)
        if not backup:
            backup = await github_daily_history(symbol)
        if backup:
            return {"ok": True, "symbol": symbol, "source": "Daily OHLCV archive fallback", "data": backup, "updatedAt": now_iso(), "errors": errors}
        return {"ok": False, "symbol": symbol, "source": None, "data": [], "updatedAt": now_iso(), "errors": errors}
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


def closes_from(raw: Any) -> list[float]:
    rows=arr(raw)
    out=[]
    for x in rows:
        if isinstance(x,(list,tuple)) and len(x)>1:
            v=num(x[-1])
        else:
            v=num(pick(x,["close","closingPrice","ltp","lastPrice","price","value"]))
        if v is not None: out.append(v)
    return out

def sma(values,n):
    return sum(values[-n:])/n if len(values)>=n else None

def ema(values,n):
    if len(values)<n:return None
    k=2/(n+1); e=sum(values[:n])/n
    for v in values[n:]: e=v*k+e*(1-k)
    return e

def rsi(values,n=14):
    if len(values)<=n:return None
    gains=[]; losses=[]
    for a,b in zip(values[-n-1:-1],values[-n:]):
        d=b-a; gains.append(max(d,0)); losses.append(max(-d,0))
    ag=sum(gains)/n; al=sum(losses)/n
    if al==0:return 100.0
    return 100-(100/(1+ag/al))

def technical_from(values):
    if not values:return {"sma20":None,"sma50":None,"ema20":None,"rsi14":None,"trend":"Unavailable","signal":"Unavailable"}
    last=values[-1]; s20=sma(values,20); s50=sma(values,50); e20=ema(values,20); r=rsi(values,14)
    trend='Bullish' if s20 is not None and last>s20 and (s50 is None or s20>s50) else ('Bearish' if s20 is not None and last<s20 and (s50 is None or s20<s50) else 'Neutral')
    signal='Overbought' if r is not None and r>=70 else ('Oversold' if r is not None and r<=30 else trend)
    return {"last":last,"sma20":s20,"sma50":s50,"ema20":e20,"rsi14":r,"trend":trend,"signal":signal}

legacy_get_floorsheet = get_floorsheet
legacy_get_broker_analysis = get_broker_analysis
legacy_get_history = get_history
legacy_get_fundamentals = get_fundamentals

# ---------------------------------------------------------------------------
# Production SDK integration
# ---------------------------------------------------------------------------
# Existing UI routes below continue to work, but these core functions now use
# the production nepse.py layer.  Sync SDK calls are moved to a worker thread
# so FastAPI's event loop remains responsive.

async def get_floorsheet(symbol: Optional[str] = None):
    try:
        return await production_call("trades", symbol=symbol, max_pages=1000, size=500)
    except TypeError:
        # Older generated SDK signature: fetch all trades then filter.
        rows = await production_call("trades", max_pages=1000, size=500)
        if symbol:
            s = symbol.upper().strip()
            rows = [r for r in rows if str(r.get("symbol", "")).upper() == s]
        return rows
    except Exception:
        # Preserve the existing backend fallback if the production layer is
        # temporarily unavailable.
        return await legacy_get_floorsheet(symbol)

async def get_broker_analysis():
    try:
        rows = await production_call("trades", max_pages=1000, size=500)
        analysis = await production_call("broker_analysis", rows)
        flow = await production_call("broker_flow_by_symbol", rows)
        return {"ok": True, "updatedAt": now_iso(), "data": analysis,
                "bySymbol": flow, "sourceRows": len(rows),
                "source": "nepse.py production data layer"}
    except Exception:
        return await legacy_get_broker_analysis()

async def get_history(symbol: str):
    try:
        rows = await production_call("history_ohlcv", symbol)
        if rows:
            return {"ok": True, "symbol": symbol.upper(),
                    "source": "nepse.py production history", "data": rows,
                    "updatedAt": now_iso(), "errors": []}
    except Exception as e:
        production_history_error = str(e)
    # Keep existing multi-source chart fallback.
    return await legacy_get_history(symbol)

async def get_fundamentals(symbol: str):
    try:
        snap = await production_call("company_snapshot", symbol)
        if snap:
            return {"ok": True, "symbol": symbol.upper(),
                    **snap, "source": "nepse.py production company data",
                    "updatedAt": now_iso()}
    except Exception:
        pass
    return await legacy_get_fundamentals(symbol)

# Preserve original implementations as explicit fallbacks.
@app.get("/api/floorsheet")
async def api_floorsheet(symbol: Optional[str]=None, limit:int=Query(100000,ge=1,le=100000)):
    rows=await get_floorsheet(symbol)
    rows=rows[:limit]
    return {"ok":bool(rows),"source":"NEPSE floorsheet","symbol":symbol,"data":rows,"count":len(rows),"updatedAt":now_iso()}

@app.get("/api/brokers")
async def api_brokers():
    return await get_broker_analysis()

@app.get("/api/sectors")
async def api_sectors():
    return await get_sectors()

@app.get("/api/technical/{symbol}")
async def api_technical(symbol:str):
    h=await get_history(symbol)
    vals=closes_from(h.get("data") if isinstance(h,dict) else h)
    return {"ok":bool(vals),"symbol":symbol.upper(),"historySource":h.get("source") if isinstance(h,dict) else None,"technical":technical_from(vals),"updatedAt":now_iso(),"errors":h.get("errors",[]) if isinstance(h,dict) else []}

@app.get("/api/stock-xray/{symbol}")
async def api_stock_xray(symbol:str):
    f,h,t=await asyncio.gather(get_fundamentals(symbol),get_history(symbol),api_technical(symbol))
    return {"ok":bool(f.get("ok") or h.get("ok") or t.get("ok")),"symbol":symbol.upper(),"fundamentals":f,"history":h,"technical":t,"updatedAt":now_iso()}

@app.get("/api/production/status")
async def production_status():
    try:
        await ensure_production_sdk()
        cache = await production_call("cache_info")
        return {"ok": True, "sdk": "nepse.py", "cache": cache, "error": None}
    except Exception as exc:
        return {"ok": False, "sdk": "nepse.py", "cache": {}, "error": PRODUCTION_SDK_ERROR or str(exc)}

@app.get("/api/production/market")
async def production_market():
    try:
        vals = await asyncio.gather(
            production_call("market_status"), production_call("market_summary"),
            production_call("live_market"), production_call("indices"),
            production_call("top_gainers"), production_call("top_losers"),
            production_call("top_turnover"), production_call("top_trade"),
            production_call("top_transaction"), production_call("companies"),
        )
        keys = ["status","summary","live","indices","gainers","losers","turnover","trades","transactions","companies"]
        return {"ok": True, "source":"nepse.py production data layer", **dict(zip(keys, vals)), "updatedAt": now_iso()}
    except Exception as exc:
        # Never blank the main feed just because the optional production SDK
        # layer is unavailable. The proven central market loader remains the
        # authoritative fallback.
        data = await get_market()
        if isinstance(data, dict):
            data.setdefault("diagnostics", {})["productionSdkError"] = str(exc)
        return data

@app.get("/api/production/company/{symbol}")
async def production_company(symbol: str):
    return await production_call("company_snapshot", symbol)

@app.get("/api/production/depth/{symbol}")
async def production_depth(symbol: str):
    depth, supply = await asyncio.gather(production_call("depth", symbol), production_call("supply_demand", symbol))
    return {"ok": True, "symbol": symbol.upper(), "depth": depth, "supplyDemand": supply, "updatedAt": now_iso()}

@app.get("/api/production/technical/{symbol}")
async def production_technical(symbol: str):
    rows = await production_call("technical_history", symbol)
    return {"ok": bool(rows), "symbol": symbol.upper(), "data": rows, "updatedAt": now_iso()}

@app.get("/api/production/notices")
async def production_notices():
    return await production_call("notices")

@app.get("/api/production/disclosures")
async def production_disclosures():
    return await production_call("disclosures")

@app.get("/api/production/holidays")
async def production_holidays():
    return await production_call("holidays")

@app.get("/api/production/reports")
async def production_reports():
    return await production_call("reports")

@app.get("/api/production/events")
async def production_events():
    return await production_call("events")

# TradingView UDF-shaped adapter for the NEPSE Pulse chart frontend.
@app.get("/api/tv/config")
async def tv_config():
    return await production_call("tv_config")

@app.get("/api/tv/time")
async def tv_time():
    return await production_call("tv_time")

@app.get("/api/tv/search")
async def tv_search(q: str = Query(""), limit: int = Query(30, ge=1, le=100)):
    return await production_call("tv_search", q, limit)

@app.get("/api/tv/symbols")
async def tv_symbols(symbol: str):
    return await production_call("tv_symbol", symbol)

@app.get("/api/tv/history")
async def tv_history(symbol: str, resolution: str = "D", from_: int = Query(0, alias="from"), to: int = Query(0), countback: int = Query(500, ge=1, le=5000)):
    return await production_call("tv_history", symbol, resolution=resolution, from_ts=from_, to_ts=to, countback=countback)

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
    errors=[]
    try:
        raw=await public_get("/DailyNepseIndexGraph")
        rows=normalize_index_history_rows(raw,58)
        if rows:
            return {"ok":True,"source":"NEPSE public DailyNepseIndexGraph","data":rows,"updatedAt":now_iso(),"errors":errors}
        errors.append("public graph returned no usable NEPSE rows")
    except Exception as e:
        errors.append(f"public graph: {e}")
    try:
        raw=await nepse_call(["index_history"],58,1,1000)
        rows=normalize_index_history_rows(raw,58)
        if rows:
            return {"ok":True,"source":"NEPSE via nepsepy:index_history","data":rows,"updatedAt":now_iso(),"errors":errors}
        errors.append("nepsepy index_history returned no usable rows")
    except Exception as e:
        errors.append(f"nepsepy index_history: {e}")
    # Closed-session fallback: show verified daily history instead of a blank
    # chart. The response is explicitly marked intraday=False.
    try:
        raw=await yonepse_get("market/history.json")
        rows=normalize_index_history_rows(raw,58)
        if rows:
            return {"ok":True,"source":"YONEPSE daily NEPSE history fallback","data":rows[-96:],"updatedAt":now_iso(),"errors":errors,"intraday":False}
    except Exception as e:
        errors.append(f"yonepse daily history: {e}")
    try:
        rows=await yonepse_index_history()
        if rows:
            return {"ok":True,"source":"NEPSE_INDEX.csv daily history fallback","data":rows[-96:],"updatedAt":now_iso(),"errors":errors,"intraday":False}
    except Exception as e:
        errors.append(f"NEPSE_INDEX.csv daily history: {e}")
    return {"ok":False,"source":None,"data":[],"updatedAt":now_iso(),"errors":errors}


@app.get("/NepseIndex")
async def nepse_index():
    return await get_index()


@app.get("/NepseSubIndices")
async def nepse_sub_indices():
    return await get_sectors()


@app.get("/CompanyList")
async def company_list():
    async def load():
        try:
            return await nepse_call(["companies", "securities", "company_list"])
        except Exception:
            return await public_get("/CompanyList")
    return await cached("companies:all", load)

@app.get("/api/companies")
async def api_companies():
    return await company_list()


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
async def api_security_history(symbol: str, limit: int = Query(5000, ge=20, le=5000)):
    d = await get_history(symbol)
    if isinstance(d, dict) and isinstance(d.get("data"), list):
        d = {**d, "data": d["data"][-limit:], "count": min(len(d["data"]), limit)}
    return d


@app.get("/Floorsheet")
async def floorsheet():
    return await get_floorsheet()


@app.get("/FloorsheetOf")
async def floorsheet_of(symbol: str):
    return await get_floorsheet(symbol)


@app.get("/api/command-center")
async def command_center():
    # Reuse the central cached market snapshot instead of starting a second
    # ingestion pipeline whenever the Command Center opens.
    market, idx, sectors, brokers = await asyncio.gather(
        get_market(), get_index(), get_sectors(), get_broker_analysis()
    )
    m = market or {}
    live = deep_rows(m.get("live"))

    def row_change_pct(x):
        p = num(pick(x, ["perChange","percentageChange","percentChange","changePercent","pChange","changePercentage"]))
        if p is not None:
            return p
        change = num(pick(x, ["change","pointChange","difference"]))
        prev = num(pick(x, ["previousClose","previousPrice","prevClose","previousLtp","previousLtpPrice"]))
        if change is not None and prev not in (None, 0):
            return change / prev * 100
        ltp = num(pick(x, ["lastTradedPrice","lastPrice","ltp","LTP","closePrice","price"]))
        if ltp is not None and prev not in (None, 0):
            return (ltp - prev) / prev * 100
        return None

    summary = m.get("summary") if isinstance(m, dict) else {}
    breadth = m.get("breadth") if isinstance(m, dict) else None
    if not isinstance(breadth, dict) or sum(num(breadth.get(k)) or 0 for k in ("advancing","declining","unchanged")) == 0:
        breadth = _find_breadth_values(summary)
    if len(breadth) < 3:
        breadth = {"advancing": 0, "declining": 0, "unchanged": 0}
        for row in live:
            p = row_change_pct(row)
            if p is None:
                continue
            if p > 0: breadth["advancing"] += 1
            elif p < 0: breadth["declining"] += 1
            else: breadth["unchanged"] += 1

    return {
        "ok": bool(m.get("ok") or live or idx),
        "updatedAt": now_iso(),
        "summary": summary if isinstance(summary, (dict, list)) else {},
        "breadth": breadth,
        "nepse": idx or m.get("index") or {},
        "movers": {
            "gainers": deep_rows(m.get("gainers"))[:50],
            "losers": deep_rows(m.get("losers"))[:50],
        },
        "activity": {
            "turnover": deep_rows(m.get("topTurnover"))[:50],
            "volume": deep_rows(m.get("topTraded"))[:50],
            "transactions": deep_rows(m.get("topTransactions"))[:50],
        },
        "sectors": (sectors or {}).get("data", []) if isinstance(sectors, dict) else [],
        "brokers": (brokers or {}).get("data", []) if isinstance(brokers, dict) else [],
        "counts": {"live": len(live)},
        "diagnostics": {
            "marketSource": m.get("source") if isinstance(m, dict) else None,
            "breadthRows": sum(breadth.values()),
            "sourceErrors": m.get("diagnostics", {}).get("sourceErrors", []) if isinstance(m, dict) else [],
        },
    }

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

# ---------------------------------------------------------------------------
# Historical floorsheet and broker analytics (date-scoped, chart-ready)
# ---------------------------------------------------------------------------
from calendar import monthrange as _monthrange

def _subtract_months(dt: datetime, months: int) -> datetime:
    month = dt.month - months
    year = dt.year + (month - 1) // 12
    month = (month - 1) % 12 + 1
    day = min(dt.day, _monthrange(year, month)[1])
    return dt.replace(year=year, month=month, day=day)

def _row_date(row: dict) -> Optional[str]:
    value = row.get("businessDate") or pick(row.get("raw", {}), ["businessDate", "tradeDate", "date"])
    if value is None:
        return None
    text_value = str(value).strip()
    # Common NEPSE date formats and ISO timestamps.
    for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%d-%m-%Y", "%d/%m/%Y"):
        try:
            return datetime.strptime(text_value[:19], fmt).date().isoformat()
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(text_value.replace("Z", "+00:00")).date().isoformat()
    except Exception:
        return None

async def _historical_floor_rows(start_date: datetime.date, end_date: datetime.date, symbol: Optional[str] = None):
    """Load dated daily archive files; never relabel today's live feed as history."""
    wanted = symbol.upper().strip() if symbol else None
    dates = []
    d = start_date
    while d <= end_date:
        dates.append(d)
        d += timedelta(days=1)
    sem = asyncio.Semaphore(8)
    async def fetch_day(day):
        async with sem:
            try:
                raw = await static_get(f"/floor_sheet/daily/{day.isoformat()}.json")
                rows = floor_rows(raw)
                out = []
                for row in rows:
                    rd = _row_date(row) or day.isoformat()
                    if rd != day.isoformat():
                        continue
                    row["businessDate"] = rd
                    if not wanted or str(row.get("symbol") or "").upper() == wanted:
                        out.append(row)
                return out
            except Exception:
                return []
    batches = await asyncio.gather(*(fetch_day(day) for day in dates))
    return [row for batch in batches for row in batch]

def _build_broker_report(rows: list[dict]):
    brokers = {}
    by_symbol = {}
    daily = {}
    for r in rows:
        q = r.get("quantity") or 0
        amount = r.get("amount") or ((r.get("rate") or 0) * q)
        symbol = str(r.get("symbol") or "UNKNOWN").upper()
        day = _row_date(r) or "unknown"
        for side, code in (("buy", r.get("buyerBroker")), ("sell", r.get("sellerBroker"))):
            if code in (None, "", 0):
                continue
            key = str(code)
            b = brokers.setdefault(key, {"broker": key, "buyValue": 0, "sellValue": 0, "buyQty": 0, "sellQty": 0, "buyTrades": 0, "sellTrades": 0})
            b[side + "Value"] += amount
            b[side + "Qty"] += q
            b[side + "Trades"] += 1
            bs = by_symbol.setdefault(symbol, {}).setdefault(key, {"broker": key, "symbol": symbol, "buyValue": 0, "sellValue": 0, "buyQty": 0, "sellQty": 0})
            bs[side + "Value"] += amount
            bs[side + "Qty"] += q
            ds = daily.setdefault(day, {}).setdefault(key, {"broker": key, "date": day, "buyValue": 0, "sellValue": 0, "buyQty": 0, "sellQty": 0})
            ds[side + "Value"] += amount
            ds[side + "Qty"] += q
    broker_rows = []
    for b in brokers.values():
        b["netValue"] = b["buyValue"] - b["sellValue"]
        b["netQty"] = b["buyQty"] - b["sellQty"]
        b["tradeCount"] = b["buyTrades"] + b["sellTrades"]
        broker_rows.append(b)
    broker_rows.sort(key=lambda x: abs(x["netValue"]), reverse=True)
    symbol_rows = []
    for sym, entries in by_symbol.items():
        for b in entries.values():
            b["netValue"] = b["buyValue"] - b["sellValue"]
            b["netQty"] = b["buyQty"] - b["sellQty"]
            symbol_rows.append(b)
    daily_rows = []
    for day, entries in sorted(daily.items()):
        for b in entries.values():
            b["netValue"] = b["buyValue"] - b["sellValue"]
            b["netQty"] = b["buyQty"] - b["sellQty"]
            daily_rows.append(b)
    return broker_rows, symbol_rows, daily_rows

@app.get("/api/brokers/history")
async def api_brokers_history(months: int = Query(3, ge=1, le=12), symbol: Optional[str] = None,
                              start: Optional[str] = None, end: Optional[str] = None):
    """Historical broker flow from archived floorsheets, with data for charts."""
    today = datetime.now().date()
    try:
        end_day = datetime.strptime(end, "%Y-%m-%d").date() if end else today
        start_day = datetime.strptime(start, "%Y-%m-%d").date() if start else _subtract_months(datetime.combine(end_day, datetime.min.time()), months).date()
    except ValueError:
        raise HTTPException(status_code=400, detail="start/end must use YYYY-MM-DD")
    if start_day > end_day:
        raise HTTPException(status_code=400, detail="start must be on or before end")
    cache_key = f"brokers-history:{start_day}:{end_day}:{(symbol or 'all').upper()}"
    async def load():
        rows = await _historical_floor_rows(start_day, end_day, symbol)
        broker_rows, symbol_rows, daily_rows = _build_broker_report(rows)
        dates = sorted({x["date"] for x in daily_rows if x["date"] != "unknown"})
        chart = []
        for day in dates:
            day_rows = [x for x in daily_rows if x["date"] == day]
            chart.append({"date": day, "buyValue": sum(x["buyValue"] for x in day_rows),
                          "sellValue": sum(x["sellValue"] for x in day_rows),
                          "netValue": sum(x["netValue"] for x in day_rows),
                          "buyQty": sum(x["buyQty"] for x in day_rows),
                          "sellQty": sum(x["sellQty"] for x in day_rows)})
        return {"ok": bool(rows), "source": "NEPSE daily floorsheet archive", "startDate": start_day.isoformat(),
                "endDate": end_day.isoformat(), "monthsRequested": months, "symbol": symbol.upper() if symbol else None,
                "dataCoverage": {"tradeRows": len(rows), "daysWithData": len(dates)}, "brokers": broker_rows,
                "bySymbol": symbol_rows, "dailyByBroker": daily_rows, "chart": chart,
                "holdingNote": "Net buy/sell is transaction flow over this period, not a broker's current demat holding.",
                "updatedAt": now_iso()}
    return await cached(cache_key, load)
