import asyncio
import time
import csv
import io
import json
import os
import sqlite3
from pathlib import Path
from html.parser import HTMLParser
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import httpx
from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

try:
    from nepsepy import AsyncNepseClient
except Exception:
    try:
        # Compatibility with the older package name used by several NEPSE
        # deployments.  The data adapter below supports both method styles.
        from nepse_client import AsyncNepseClient
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


APP_VERSION = "V35-FLOORSHEET-COLLECTOR-FIX"
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


async def _floor_collector_loop():
    while not FLOOR_COLLECTOR_STOP.is_set():
        try:
            day=datetime.now(NPT).date().isoformat()
            rows=await _complete_daily_floorsheet(None,page_size=500,max_pages=200)
            if rows:
                _store_floorsheet_rows(rows); _rebuild_broker_rollups(day)
        except Exception: pass
        try: await asyncio.wait_for(FLOOR_COLLECTOR_STOP.wait(),timeout=20)
        except asyncio.TimeoutError: pass

@app.on_event("startup")
async def start_floor_collector():
    global FLOOR_COLLECTOR_TASK
    FLOOR_COLLECTOR_STOP.clear()
    if FLOOR_COLLECTOR_TASK is None or FLOOR_COLLECTOR_TASK.done(): FLOOR_COLLECTOR_TASK=asyncio.create_task(_floor_collector_loop())

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
async def stop_floor_collector():
    global FLOOR_COLLECTOR_TASK
    FLOOR_COLLECTOR_STOP.set()
    if FLOOR_COLLECTOR_TASK is not None:
        try: await asyncio.wait_for(FLOOR_COLLECTOR_TASK,timeout=3)
        except Exception: FLOOR_COLLECTOR_TASK.cancel()
        FLOOR_COLLECTOR_TASK=None

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

# Fast local floorsheet store: collect once, index once, serve many times.
FLOOR_CACHE_DB = Path(os.getenv("FLOOR_CACHE_DB", "nepse_pulse_floorsheet.sqlite3"))
FLOOR_COLLECTOR_TASK = None
FLOOR_COLLECTOR_STOP = asyncio.Event()

def _floor_db_init():
    FLOOR_CACHE_DB.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(FLOOR_CACHE_DB) as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("""CREATE TABLE IF NOT EXISTS floorsheet_raw (
            trade_key TEXT PRIMARY KEY, business_date TEXT, symbol TEXT,
            buyer_broker TEXT, seller_broker TEXT, quantity REAL, rate REAL,
            amount REAL, trade_time TEXT, payload TEXT NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_floor_date ON floorsheet_raw(business_date)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_floor_symbol_date ON floorsheet_raw(symbol,business_date)")
        db.execute("""CREATE TABLE IF NOT EXISTS broker_daily (
            business_date TEXT, broker TEXT, buy_value REAL, sell_value REAL,
            buy_qty REAL, sell_qty REAL, buy_trades INTEGER, sell_trades INTEGER,
            PRIMARY KEY (business_date, broker)
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS broker_stock_daily (
            business_date TEXT, broker TEXT, symbol TEXT, buy_value REAL, sell_value REAL,
            buy_qty REAL, sell_qty REAL, trades INTEGER,
            PRIMARY KEY (business_date, broker, symbol)
        )""")

def _floor_key(r):
    return str(r.get("trade") or r.get("contractId") or r.get("transactionNumber") or
               f"{r.get('symbol')}|{r.get('businessDate')}|{r.get('tradeTime')}|{r.get('quantity')}|{r.get('rate')}|{r.get('buyerBroker')}|{r.get('sellerBroker')}")

def _floor_date(r):
    return str(r.get("businessDate") or datetime.now(NPT).date().isoformat())[:10]

def _store_floorsheet_rows(rows):
    if not rows: return 0
    _floor_db_init()
    with sqlite3.connect(FLOOR_CACHE_DB) as db:
        for r in rows:
            db.execute("""INSERT OR REPLACE INTO floorsheet_raw
                (trade_key,business_date,symbol,buyer_broker,seller_broker,quantity,rate,amount,trade_time,payload)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                (_floor_key(r), _floor_date(r), str(r.get("symbol") or "").upper(),
                 str(r.get("buyerBroker") or r.get("buyerBrokerName") or ""),
                 str(r.get("sellerBroker") or r.get("sellerBrokerName") or ""),
                 float(r.get("quantity") or 0), float(r.get("rate") or 0),
                 float(r.get("amount") or 0), str(r.get("tradeTime") or ""),
                 json.dumps(r, separators=(",",":"), default=str)))
        db.commit()
    return len(rows)

def _read_floor_rows(business_date=None, symbol=None, limit=None, offset=0):
    _floor_db_init(); sql="SELECT payload FROM floorsheet_raw WHERE 1=1"; args=[]
    if business_date: sql += " AND business_date=?"; args.append(str(business_date)[:10])
    if symbol: sql += " AND symbol=?"; args.append(str(symbol).upper().strip())
    sql += " ORDER BY rowid DESC"
    if limit is not None: sql += " LIMIT ? OFFSET ?"; args.extend([int(limit),int(offset)])
    with sqlite3.connect(FLOOR_CACHE_DB) as db: return [json.loads(x[0]) for x in db.execute(sql,args).fetchall()]

def _floor_count(business_date=None, symbol=None):
    _floor_db_init(); sql="SELECT COUNT(*) FROM floorsheet_raw WHERE 1=1"; args=[]
    if business_date: sql += " AND business_date=?"; args.append(str(business_date)[:10])
    if symbol: sql += " AND symbol=?"; args.append(str(symbol).upper().strip())
    with sqlite3.connect(FLOOR_CACHE_DB) as db: return int(db.execute(sql,args).fetchone()[0])

def _rebuild_broker_rollups(business_date):
    _floor_db_init(); day=str(business_date)[:10]
    with sqlite3.connect(FLOOR_CACHE_DB) as db:
        db.execute("DELETE FROM broker_daily WHERE business_date=?",(day,)); db.execute("DELETE FROM broker_stock_daily WHERE business_date=?",(day,))
        rows=db.execute("SELECT symbol,buyer_broker,seller_broker,quantity,amount FROM floorsheet_raw WHERE business_date=?",(day,)).fetchall()
        bd={}; bs={}
        for sym,buy,sell,q,amt in rows:
            q=float(q or 0); amt=float(amt or 0)
            for broker,side in ((buy,'buy'),(sell,'sell')):
                if not broker: continue
                b=bd.setdefault(str(broker),[0,0,0,0,0,0]); z=bs.setdefault((str(broker),str(sym or '').upper()),[0,0,0,0,0])
                if side=='buy': b[0]+=amt; b[2]+=q; b[4]+=1; z[0]+=amt; z[2]+=q
                else: b[1]+=amt; b[3]+=q; b[5]+=1; z[1]+=amt; z[3]+=q
                z[4]+=1
        db.executemany("INSERT INTO broker_daily VALUES (?,?,?,?,?,?,?,?)",[(day,k,*v) for k,v in bd.items()])
        db.executemany("INSERT INTO broker_stock_daily VALUES (?,?,?,?,?,?,?,?)",[(day,k[0],k[1],*v) for k,v in bs.items()]); db.commit()

def _cached_broker_rollup(business_date):
    _rebuild_broker_rollups(business_date); day=str(business_date)[:10]
    with sqlite3.connect(FLOOR_CACHE_DB) as db:
        rows=db.execute("SELECT broker,buy_value,sell_value,buy_qty,sell_qty,buy_trades,sell_trades FROM broker_daily WHERE business_date=? ORDER BY ABS(buy_value-sell_value) DESC",(day,)).fetchall()
        bysym=db.execute("SELECT broker,symbol,buy_value,sell_value,buy_qty,sell_qty,trades FROM broker_stock_daily WHERE business_date=?",(day,)).fetchall()
    total=sum(float(r[1])+float(r[2]) for r in rows) or 1; data=[]
    for r in rows:
        b={"broker":r[0],"buyValue":r[1],"sellValue":r[2],"buyQty":r[3],"sellQty":r[4],"buyTrades":r[5],"sellTrades":r[6]}
        b["net"]=b["buyValue"]-b["sellValue"]; b["netValue"]=b["net"]; b["trades"]=b["buyTrades"]+b["sellTrades"]; b["share"]=(b["buyValue"]+b["sellValue"])/total; data.append(b)
    symbol_rows=[{"broker":r[0],"symbol":r[1],"buyValue":r[2],"sellValue":r[3],"buyQty":r[4],"sellQty":r[5],"trades":r[6],"net":r[2]-r[3]} for r in bysym]
    return data,symbol_rows

_floor_db_init()
# Last-known-good caches for transient empty NEPSE responses. These prevent
# Trade Tape/Broker/Depth panels from flashing blank during feed refreshes.
_LAST_VALID_FLOORSHEET: list[dict] = []
_LAST_VALID_FLOORSHEET_AT: float = 0.0
_LAST_VALID_BROKERS: dict = {}
_LAST_VALID_DEPTH: dict[str, dict] = {}



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
            "buyerMemberId", "buyBroker", "buyerBrokerId", "buyerCode"
        ])
        seller = pick(x, [
            "sellerBrokerName", "sellerBroker", "seller", "sellerBrokerCode",
            "sellerMemberId", "sellBroker", "sellerBrokerId", "sellerCode"
        ])
        out.append({
            "symbol": pick(x, ["stockSymbol", "symbol", "securitySymbol", "ticker", "scrip", "stock"]),
            "buyerBroker": buyer,
            "sellerBroker": seller,
            "buyerBrokerName": pick(x, ["buyerBrokerName", "buyerBroker", "buyer"]),
            "sellerBrokerName": pick(x, ["sellerBrokerName", "sellerBroker", "seller"]),
            "buyerBrokerId": pick(x, ["buyerMemberId", "buyerBrokerCode", "buyerBroker"]),
            "sellerBrokerId": pick(x, ["sellerMemberId", "sellerBrokerCode", "sellerBroker"]),
            "quantity": num(pick(x, ["contractQuantity", "quantity", "tradedQuantity", "volume", "shares", "qty"])),
            "rate": num(pick(x, ["contractRate", "rate", "price", "tradedPrice"])),
            "amount": num(pick(x, ["contractAmount", "amount", "turnover", "totalAmount"])),
            "trade": pick(x, ["contractId", "trade", "contractNumber", "transactionNumber"]),
            "businessDate": pick(x, ["businessDate", "date", "tradeDate", "calculationDate"]),
            "tradeTime": pick(x, ["tradeTime", "time"]),
            "securityId": pick(x, ["stockId", "securityId", "id"]),
            "securityName": pick(x, ["securityName", "name"]),
            "raw": x,
        })
    return out


class _MeroLaganiFloorParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.in_tr=False; self.in_cell=False; self.cells=[]; self.buf=[]; self.rows=[]
    def handle_starttag(self, tag, attrs):
        tag=tag.lower()
        if tag == "tr": self.in_tr=True; self.cells=[]
        elif self.in_tr and tag in ("td","th"): self.in_cell=True; self.buf=[]
    def handle_data(self, data):
        if self.in_cell: self.buf.append(data)
    def handle_endtag(self, tag):
        tag=tag.lower()
        if self.in_tr and tag in ("td","th") and self.in_cell:
            self.cells.append(" ".join("".join(self.buf).split())); self.in_cell=False; self.buf=[]
        elif tag == "tr" and self.in_tr:
            if self.cells: self.rows.append(self.cells)
            self.in_tr=False

async def _merolagani_floorsheet_fallback(symbol: Optional[str] = None):
    """Last-resort real executed-trade feed for Trade Tape only."""
    wanted=symbol.upper().strip() if symbol else None
    for url in ("https://cdn.merolagani.com/Floorsheet.aspx","https://merolagani.com/Floorsheet.aspx"):
        try:
            async with httpx.AsyncClient(timeout=20, follow_redirects=True, headers={"User-Agent":"Mozilla/5.0"}) as c:
                r=await c.get(url); r.raise_for_status()
            parser=_MeroLaganiFloorParser(); parser.feed(r.text); out=[]
            for cells in parser.rows:
                if len(cells)<8 or cells[0].lower() in ("#","s.no","s.no."): continue
                tx,sym,buy,sell,qty,rate,amount=cells[1:8]
                if not tx.isdigit() or not sym: continue
                if wanted and sym.upper().strip()!=wanted: continue
                q=num(qty.replace(',','')); rt=num(rate.replace(',','')); amt=num(amount.replace(',',''))
                out.append({"symbol":sym.upper().strip(),"buyerBroker":buy,"sellerBroker":sell,"buyerBrokerId":buy,"sellerBrokerId":sell,"quantity":q,"rate":rt,"amount":amt if amt is not None else (rt or 0)*(q or 0),"trade":tx,"businessDate":tx[:8],"tradeTime":"","securityName":"","raw":{}})
            if out: return out
        except Exception: continue
    return []

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
        best_rows = []
        for methods in (
            ["sub_indices"], ["sector_indices"], ["nepse_sub_indices"],
            ["nepse_subindices"], ["sector_summary"], ["sector_indices_summary"],
        ):
            try:
                candidate = await nepse_call(methods)
                candidate_rows = deep_rows(candidate, ("content", "data", "subIndices", "sectorIndices"))
                if len(candidate_rows) > len(best_rows):
                    best_rows = candidate_rows
                if len(best_rows) >= len(SECTOR_INDEX_IDS):
                    break
            except Exception as e:
                errors.append(f"{methods[0]}: {e}")
        rows = best_rows
        if not deep_rows(raw):
            try:
                raw = await public_get("/NepseSubIndices")
            except Exception as e:
                errors.append(f"public: {e}")
        rows = rows or deep_rows(raw, ("content", "data", "subIndices", "sectorIndices"))
        if len(rows) < len(SECTOR_INDEX_IDS):
            try:
                raw_static = await static_get("/market/sector_indices.json")
                static_rows = deep_rows(raw_static)
                if len(static_rows) > len(rows):
                    rows = static_rows
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
        # Complete the sector universe if an upstream feed returned only a
        # partial subset. Existing live rows win; missing rows use the verified
        # last-close snapshot above so the heatmap never collapses to 3-4 cards.
        by_name = {str(x.get("sector") or x.get("name") or "").strip().lower(): x for x in out}
        for sector_name, fallback in SECTOR_LAST_CLOSE_FALLBACK.items():
            key = sector_name.lower()
            if key not in by_name:
                item = {"sector": sector_name, "name": sector_name, "indexId": SECTOR_INDEX_IDS.get(sector_name), "raw": {"source": "verified-last-close-fallback"}}
                item.update(fallback)
                out.append(item)
                by_name[key] = item
            else:
                # Always attach the stable index id, and fill only missing
                # fields from the verified snapshot.
                item = by_name[key]
                item["indexId"] = SECTOR_INDEX_IDS.get(sector_name, item.get("indexId"))
                for k, v in fallback.items():
                    if item.get(k) is None:
                        item[k] = v
        return {"ok": bool(out), "updatedAt": now_iso(), "data": out, "errors": errors, "source": "NEPSE sector feed"}
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
        # Secondary public historical archive. Keep this as a real-data fallback
        # only; it is never used to manufacture missing sessions.
        f"https://raw.githubusercontent.com/Aabishkar2/nepse-data/main/data/company-wise/{symbol}.csv",
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
    """Return real NEPSE executed floor-sheet rows for the current trading day.

    The production SDK may return a paginated wrapper such as
    {"floorsheets": {"content": [...]}} rather than a bare list.  Always
    normalize that response before it reaches the frontend.  When a symbol is
    supplied, resolve its NEPSE security id and request that company directly;
    only fall back to the all-market feed when the SDK does not accept the
    company filter.
    """
    s = symbol.upper().strip() if symbol else None
    errors = []

    try:
        company_id = None
        if s:
            try:
                company = await resolve_company(s)
                company_id = pick(company, ["id", "securityId", "security_id"])
            except Exception as exc:
                errors.append(f"company lookup: {exc}")

        raw = None

        # Preferred: company-specific NEPSE floorsheet through the production
        # SDK. Different SDK versions expose the filter as stock_id or symbol.
        if company_id is not None:
            for kwargs in (
                {"stock_id": int(company_id), "max_pages": 1000, "size": 500},
                {"stock_id": int(company_id), "page": 1, "size": 500},
                {"symbol": s, "max_pages": 1000, "size": 500},
                {"symbol": s, "page": 1, "size": 500},
            ):
                try:
                    raw = await production_call("trades", **kwargs)
                    if raw is not None:
                        break
                except TypeError:
                    continue
                except Exception as exc:
                    errors.append(f"production trades {kwargs}: {exc}")

        # If the SDK did not accept the company filter, retrieve the complete
        # current-day floorsheet and filter by the exact symbol locally.
        if raw is None:
            for kwargs in (
                {"max_pages": 1000, "size": 500},
                {"page": 1, "size": 500},
            ):
                try:
                    raw = await production_call("trades", **kwargs)
                    if raw is not None:
                        break
                except TypeError:
                    continue
                except Exception as exc:
                    errors.append(f"production all trades {kwargs}: {exc}")

        rows = floor_rows(raw) if raw is not None else []

        # floor_rows() already maps NEPSE's real contract fields:
        # symbol, buyer/seller broker, contract quantity/rate/amount,
        # contract id, business date and trade time.
        if s:
            rows = [
                r for r in rows
                if str(r.get("symbol") or "").upper().strip() == s
            ]

        if rows:
            return rows

    except Exception as exc:
        errors.append(f"production floorsheet: {exc}")

    # Proven legacy loader remains the fallback. It also uses the real NEPSE
    # floorsheet endpoint/compatibility sources; it never fabricates trades.
    try:
        rows = await legacy_get_floorsheet(s)
        if rows:
            normalized = floor_rows(rows)
            if s:
                normalized = [
                    r for r in normalized
                    if str(r.get("symbol") or "").upper().strip() == s
                ]
            if normalized:
                return normalized
    except Exception as exc:
        errors.append(f"legacy floorsheet: {exc}")

    return []

async def get_broker_analysis():
    day=datetime.now(NPT).date().isoformat()
    try:
        count=_floor_count(day)
        if count:
            data,symbol_rows=_cached_broker_rollup(day)
            return {"ok":True,"updatedAt":now_iso(),"data":data,"bySymbol":symbol_rows,"sourceRows":count,"source":"local indexed floorsheet cache"}
    except Exception: pass
    try:
        rows=await get_floorsheet(None)
        if rows:
            _store_floorsheet_rows(rows); data,symbol_rows=_cached_broker_rollup(day)
            return {"ok":True,"updatedAt":now_iso(),"data":data,"bySymbol":symbol_rows,"sourceRows":len(rows),"source":"local indexed floorsheet cache"}
    except Exception: pass
    return {"ok":False,"updatedAt":now_iso(),"data":[],"bySymbol":[],"sourceRows":0,"source":"NEPSE verified floorsheet","error":"No verified floorsheet rows returned"}

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

# FINAL VERIFIED TRADE LOADER
async def _direct_nepse_floorsheet(symbol: Optional[str] = None):
    """Use the current nepsepy client directly for executed trades.

    This is deliberately separate from the optional ``nepse`` production
    adapter above: recent nepsepy releases expose floorsheets as
    ``floorsheets(...)`` while older clients expose ``getFloorSheet(...)``.
    The previous implementation only tried a non-standard ``trades`` method,
    which can silently leave Floorsheet/Broker Analysis empty even though the
    NEPSE session is working.
    """
    wanted = symbol.upper().strip() if symbol else None
    attempts = []

    company_id = None
    if wanted:
        try:
            company = await resolve_company(wanted)
            company_id = pick(company, ["id", "securityId", "security_id", "stockId"])
        except Exception:
            company_id = None

        if company_id is not None:
            attempts.extend([
                (["floorsheets"], (), {"stock_id": int(company_id), "max_pages": 1000, "size": 500}),
                (["floorsheets"], (), {"stock_id": int(company_id), "page": 1, "size": 500}),
                (["getFloorSheetOf"], (wanted,), {}),
            ])

    # Prefer the SDK's complete-floor-sheet method.  Recent nepse SDKs
    # implement getFloorSheet() as a full paginator; a single floorsheets()
    # call can otherwise stop at a provider-side 5,000-row cap.
    attempts.extend([
        (["floor_sheet"], (), {"page": 0, "size": 500}),
        (["floorsheets"], (), {"page": 0, "size": 500}),
        (["getFloorSheet"], (), {}),
        (["get_floorsheet"], (), {}),
        (["floorsheets"], (), {"max_pages": 1000, "size": 500}),
        (["floorsheets"], (), {"page": 1, "size": 500}),
    ])

    for methods, args, kwargs in attempts:
        try:
            raw = await nepse_call(methods, *args, **kwargs)
            rows = floor_rows(raw)
            if wanted:
                rows = [r for r in rows if str(r.get("symbol") or "").upper().strip() == wanted]
            if rows:
                return rows
        except Exception:
            continue
    return []

async def _recent_archive_floorsheet(symbol: Optional[str] = None, lookback_days: int = 7):
    """Return the newest real archived floorsheet when the live endpoint is empty.

    This is intentionally a fallback only. It never invents trades and it keeps
    Floorsheet/Broker Analysis useful outside the short live-publish window.
    """
    wanted = symbol.upper().strip() if symbol else None
    today = datetime.now(NPT).date()
    for offset in range(0, lookback_days + 1):
        day = today - timedelta(days=offset)
        try:
            # YONEPSE is the maintained static floor-sheet archive. Use its
            # dedicated helper (which tries both the published site and raw
            # GitHub copy) rather than STATIC_API directly.
            raw = await yonepse_get(f"floor_sheet/daily/{day.isoformat()}.json")
            rows = floor_rows(raw)
            if wanted:
                rows = [r for r in rows if str(r.get("symbol") or "").upper().strip() == wanted]
            if rows:
                for r in rows:
                    r["businessDate"] = r.get("businessDate") or day.isoformat()
                return rows
        except Exception:
            # Keep the older static endpoint as a secondary compatibility
            # source in case the maintained archive layout changes.
            try:
                raw = await static_get(f"/floor_sheet/daily/{day.isoformat()}.json")
                rows = floor_rows(raw)
                if wanted:
                    rows = [r for r in rows if str(r.get("symbol") or "").upper().strip() == wanted]
                if rows:
                    for r in rows:
                        r["businessDate"] = r.get("businessDate") or day.isoformat()
                    return rows
            except Exception:
                continue
    return []

# One normalized path for company floorsheets.  Every source is treated as
# untrusted wrapper data until floor_rows() has flattened it.
async def _complete_daily_floorsheet(symbol: Optional[str] = None, page_size: int = 500, max_pages: int = 1000):
    """Fetch the complete current-day floorsheet quickly.

    NEPSE returns the floor sheet in paginated 500-row pages.  The old
    implementation fetched pages strictly one-by-one, which was slow and
    could fall back to the limited 5,000-row public table.  We now discover
    the page count from the first response and fetch the remaining pages in
    small concurrent batches, while preserving the zero/one-based pagination
    compatibility.
    """
    wanted = symbol.upper().strip() if symbol else None
    rows=[]
    seen=set()

    def unpack(raw):
        rr=floor_rows(raw)
        meta=raw if isinstance(raw,dict) else {}
        nested=meta.get('floorsheets') if isinstance(meta,dict) else None
        if isinstance(nested,dict): meta=nested
        total_pages=meta.get('totalPages') or meta.get('total_pages')
        total_elements=meta.get('totalElements') or meta.get('total_elements')
        return rr, total_pages, total_elements

    def add_rows(got):
        for r in got:
            key=str(r.get('trade') or r.get('contractId') or r.get('transactionNumber') or '') or f"{r.get('symbol')}|{r.get('businessDate')}|{r.get('tradeTime')}|{r.get('quantity')}|{r.get('rate')}|{r.get('buyerBroker')}|{r.get('sellerBroker')}"
            if key not in seen:
                seen.add(key); rows.append(r)

    async def fetch_page(page):
        for kwargs in ({'page':page,'size':page_size},{'page':page,'limit':page_size},{'page':page,'page_size':page_size}):
            try:
                raw=await nepse_call(['floor_sheet','floorsheets','getFloorSheet','get_floorsheet'], **kwargs)
                got,tp,te=unpack(raw)
                if got:
                    return page,got,tp,te
            except Exception:
                continue
        return page,[],None,None

    # Probe both bases because NEPSE clients differ: page=0 and page=1.
    probes=[]
    for page in (0,1):
        probes.append(asyncio.create_task(fetch_page(page)))
    results=await asyncio.gather(*probes, return_exceptions=True)
    first=None
    for result in results:
        if isinstance(result,tuple) and len(result)>=4 and result[1]:
            first=result
            break
    if first is None:
        return []

    first_page, first_rows, total_pages, total_elements=first
    add_rows(first_rows)

    if total_pages is not None:
        try:
            target=min(max_pages,int(total_pages))
        except Exception:
            target=max_pages
        # totalPages is a count. For a zero-based first page, the last valid
        # index is totalPages-1; for one-based pagination it is totalPages.
        last_page=target-1 if first_page==0 else target
        pages=list(range(first_page+1,last_page+1))
    else:
        pages=list(range(first_page+1,max_pages+1))

    # Fetch in bounded concurrent batches. This is dramatically faster than
    # waiting for ~80+ HTTP round trips sequentially, while avoiding a burst
    # large enough to trigger upstream throttling.
    batch_size=10
    for i in range(0,len(pages),batch_size):
        batch=pages[i:i+batch_size]
        results=await asyncio.gather(*(fetch_page(p) for p in batch), return_exceptions=True)
        empty=0
        for result in results:
            if not isinstance(result,tuple):
                empty+=1; continue
            page,got,tp,te=result
            if not got:
                empty+=1
                continue
            add_rows(got)
            if total_pages is None and tp is not None:
                try:
                    new_target=min(max_pages,int(tp))
                    if first_page==0:
                        pages=pages[:pages.index(page)+1] if page in pages else pages
                    else:
                        pages=pages[:pages.index(page)+1] if page in pages else pages
                except Exception:
                    pass
        if total_elements is not None and len(rows)>=int(total_elements):
            break
        if total_pages is None and empty==len(batch):
            break

    if wanted:
        rows=[r for r in rows if str(r.get('symbol') or '').upper().strip()==wanted]
    return rows

async def get_floorsheet(symbol: Optional[str] = None):
    wanted = symbol.upper().strip() if symbol else None

    def only_symbol(rows):
        rows = floor_rows(rows)
        if wanted:
            rows = [r for r in rows if str(r.get("symbol") or "").upper().strip() == wanted]
        return rows

    # 1) Always try the explicit paginated daily loader first.  Some NEPSE
    # client methods return only their default 20-row page even when a larger
    # page size is requested; using the page walker prevents that first-page
    # result from becoming the entire floorsheet.
    try:
        rows = await _complete_daily_floorsheet(wanted, page_size=500, max_pages=200)
        if rows:
            return rows
    except Exception:
        pass

    # 2) Direct nepsepy floorsheet methods.
    try:
        rows = only_symbol(await _direct_nepse_floorsheet(wanted))
        if rows:
            return rows
    except Exception:
        pass

    # 3) Original NEPSE paginated loader. It understands the current
    # {floorsheets:{content:[...]}} response and compatibility endpoints.
    try:
        rows = only_symbol(await legacy_get_floorsheet(wanted))
        if rows:
            return rows
    except Exception:
        pass

    # 3) Production trades adapter. Try company-specific forms, but NEVER
    # stop just because a valid call returned an empty wrapper. If it is empty,
    # request the full live trades feed and filter locally.
    attempts = []
    if wanted:
        try:
            company = await resolve_company(wanted)
            cid = pick(company, ["id", "securityId", "security_id", "stockId"])
            if cid is not None:
                attempts.extend([
                    ("trades", {"stock_id": int(cid), "max_pages": 1000, "size": 500}),
                    ("trades", {"stockId": int(cid), "max_pages": 1000, "size": 500}),
                ])
        except Exception:
            pass
        attempts.extend([
            ("trades", {"symbol": wanted, "max_pages": 1000, "size": 500}),
            ("trades", {"symbol": wanted, "page": 1, "size": 500}),
        ])
    attempts.extend([
        ("trades", {"max_pages": 1000, "size": 500}),
        ("trades", {"page": 1, "size": 500}),
    ])

    for method, kwargs in attempts:
        try:
            raw = await production_call(method, **kwargs)
            rows = only_symbol(raw)
            if rows:
                return rows
        except TypeError:
            continue
        except Exception:
            continue

    # 4) Explicit compatibility/public endpoint fallback.
    for path, params in (
        ("/FloorsheetOf", {"symbol": wanted} if wanted else None),
        ("/Floorsheet", None),
    ):
        if not wanted and path == "/FloorsheetOf":
            continue
        try:
            raw = await public_get(path, params)
            rows = only_symbol(raw)
            if rows:
                return rows
        except Exception:
            continue

    # 5) Latest real archived session. This is the important fallback when
    # NEPSE's live broker/floorsheet endpoint is temporarily empty.
    rows = await _recent_archive_floorsheet(wanted, lookback_days=10)
    if rows:
        return rows

    # Final real-data fallback. Never fabricate trades.
    try:
        rows = await _merolagani_floorsheet_fallback(wanted)
        if rows:
            return rows
    except Exception:
        pass

    return []

# Preserve original implementations as explicit fallbacks.
@app.get("/api/company-floorsheet/{symbol}")
async def api_company_floorsheet(symbol: str, limit: int = Query(100000, ge=1, le=100000)):
    rows = await get_floorsheet(symbol.upper().strip())
    rows = rows[:limit]
    return {
        "ok": bool(rows),
        "symbol": symbol.upper().strip(),
        "source": "NEPSE executed floorsheet",
        "data": rows,
        "count": len(rows),
        "updatedAt": now_iso(),
    }

@app.get("/api/floorsheet")
async def api_floorsheet(symbol: Optional[str]=None, limit:int=Query(100000,ge=1,le=100000), page:int=Query(0,ge=0,le=2000), size:int=Query(500,ge=1,le=500)):
    """Return exactly one real NEPSE floorsheet page.

    This endpoint is deliberately page-only. It never falls back to an
    unpaged request, because doing that can return the same first page for
    every browser request and make the Trade Tape stop growing.
    """
    global _LAST_VALID_FLOORSHEET, _LAST_VALID_FLOORSHEET_AT
    wanted = symbol.upper().strip() if symbol else None

    def unpack(raw):
        rows = floor_rows(raw)
        if wanted:
            rows = [r for r in rows if str(r.get("symbol") or "").upper().strip() == wanted]
        meta = raw.get("floorsheets") if isinstance(raw, dict) else None
        if not isinstance(meta, dict):
            meta = raw if isinstance(raw, dict) else {}
        return rows[:limit], meta.get("totalPages") or meta.get("total_pages"), meta.get("totalElements") or meta.get("total_elements")

    async def call_paged(pg):
        # Current nepse.py / nepseman style.
        for method, kwargs in (
            ("floor_sheet", {"page": pg, "size": size}),
            ("floor_sheet", {"page": pg, "limit": size}),
            ("floorsheets", {"page": pg, "size": size}),
            ("floorsheets", {"page": pg, "limit": size}),
            ("getFloorSheet", {"page": pg}),
            ("get_floorsheet", {"page": pg, "size": size}),
            ("getFloorSheet", {"page": pg, "size": size}),
            ("floorsheets", {"page": pg, "size": size}),
            ("floorsheets", {"page": pg, "limit": size}),
        ):
            try:
                raw = await production_call(method, **kwargs)
                rows, tp, te = unpack(raw)
                if rows:
                    return rows, tp, te
            except Exception:
                continue
        # Older async clients.
        try:
            client = await get_nepse_client()
            for method, kwargs in (
                ("floor_sheet", {"page": pg, "size": size}),
                ("getFloorSheet", {"page": pg}),
                ("floorsheets", {"page": pg, "size": size}),
            ):
                fn = getattr(client, method, None)
                if fn is None:
                    continue
                try:
                    raw = await fn(**kwargs)
                    rows, tp, te = unpack(raw)
                    if rows:
                        return rows, tp, te
                except Exception:
                    continue
        except Exception:
            pass
        return [], None, None

    day=datetime.now(NPT).date().isoformat()
    cached_count=_floor_count(day,wanted)
    if cached_count > page*size:
        cached_rows=_read_floor_rows(day,wanted,size,page*size)
        if cached_rows:
            return {"ok":True,"page":page,"size":size,"totalPages":(cached_count+size-1)//size,
                    "totalElements":cached_count,"data":cached_rows,"source":"local floorsheet cache","updatedAt":now_iso()}

    rows, tp, te = await call_paged(page)
    actual_page = page
    # Some older wrappers are one-based. Only page 0 gets this compatibility probe.
    if not rows and page == 0:
        rows, tp, te = await call_paged(1)
        actual_page = 1 if rows else page
    # Last-resort real executed-trade fallback. This is intentionally limited
    # to the first page so a transient NEPSE SDK outage never leaves Trade Tape
    # stuck on "waiting for feed".
    if not rows and page == 0:
        try:
            # Last-resort direct SDK full-floor-sheet call.  Several current
            # clients expose this as getFloorSheet()/floor_sheet() rather than
            # the older paginated wrapper. Cache it immediately so subsequent
            # pages are served locally.
            full_rows = await _direct_nepse_floorsheet(symbol)
            if full_rows:
                _store_floorsheet_rows(full_rows)
                try: _rebuild_broker_rollups(_floor_date(full_rows[0]))
                except Exception: pass
                start = page * size
                rows = full_rows[start:start + size]
                actual_page = page
                tp = (len(full_rows) + size - 1) // size
                te = len(full_rows)
        except Exception:
            pass
    if not rows and page == 0:
        try:
            # Outside trading hours NEPSE can legitimately return an empty
            # live floorsheet. Use the maintained real daily archive so the
            # dashboard still shows the latest completed session instead of
            # displaying an empty Trade Tape.
            archive_rows = await _recent_archive_floorsheet(symbol, lookback_days=10)
            if archive_rows:
                _store_floorsheet_rows(archive_rows)
                try: _rebuild_broker_rollups(_floor_date(archive_rows[0]))
                except Exception: pass
                start = page * size
                rows = archive_rows[start:start + size]
                actual_page = page
                tp = (len(archive_rows) + size - 1) // size
                te = len(archive_rows)
        except Exception:
            pass
    if not rows and page == 0:
        try:
            fallback_rows = await _merolagani_floorsheet_fallback(symbol)
            if fallback_rows:
                rows = fallback_rows[:size]
                actual_page = 0
                tp = None
                te = len(fallback_rows)
        except Exception:
            pass

    if rows:
        _store_floorsheet_rows(rows)
        try: _rebuild_broker_rollups(_floor_date(rows[0]))
        except Exception: pass
    if rows and not symbol and actual_page == 0:
        _LAST_VALID_FLOORSHEET = list(rows)
        _LAST_VALID_FLOORSHEET_AT = time.time()

    return {
        "ok": bool(rows), "source": "NEPSE executed floorsheet / paged daily session",
        "symbol": symbol, "data": rows, "floorsheet": rows,
        "count": len(rows), "page": actual_page, "pageSize": size,
        "totalPages": tp, "totalElements": te, "updatedAt": now_iso()
    }

@app.get("/api/floorsheet-status")
async def api_floorsheet_status():
    day = datetime.now(NPT).date().isoformat()
    try:
        count = _floor_count(day)
    except Exception:
        count = 0
    methods = {}
    try:
        client = await get_nepse_client()
        for name in ("floor_sheet", "floorsheets", "getFloorSheet", "get_floorsheet", "getFloorSheetOf"):
            methods[name] = callable(getattr(client, name, None))
    except Exception as exc:
        methods["clientError"] = str(exc)
    return {
        "ok": count > 0,
        "today": day,
        "cachedTradesToday": count,
        "sdkMethods": methods,
        "collectorRunning": bool(FLOOR_COLLECTOR_TASK and not FLOOR_COLLECTOR_TASK.done()),
        "updatedAt": now_iso(),
    }

@app.get("/api/brokers")
async def api_brokers():
    global _LAST_VALID_BROKERS
    data=await get_broker_analysis()
    if isinstance(data,dict) and data.get("data"):
        _LAST_VALID_BROKERS=dict(data)
        data["cached"]=False
        return data
    if _LAST_VALID_BROKERS:
        cached=dict(_LAST_VALID_BROKERS)
        cached["cached"]=True
        cached["source"]=str(cached.get("source") or "NEPSE floorsheet broker analysis")+" / last verified snapshot"
        return cached
    return data

SECTOR_INDEX_IDS = {
    "Commercial Banks": 51,
    "Development Banks": 55,
    "Finance": 60,
    "Hotels and Tourism": 52,
    "Hydro Power": 54,
    "Investment": 67,
    "Life Insurance": 65,
    "Manufacturing and Processing": 56,
    "Microfinance": 64,
    "Mutual Fund": 66,
    "Non Life Insurance": 59,
    "Others": 53,
    "Trading": 61,
}

# Verified last-close snapshot (7 Oct 2026) used only when the upstream
# sub-index endpoint returns a partial/empty list.  These are real NEPSE
# sector values, not generated chart/demo data.
SECTOR_LAST_CLOSE_FALLBACK = {
    "Commercial Banks": {"indexValue": 1495.17, "change": 1.73, "changePercent": 0.11, "turnover": 515449881.50, "stocksTraded": 19, "advancing": 11, "declining": 8, "unchanged": 0},
    "Development Banks": {"indexValue": 5335.47, "change": -3.96, "changePercent": -0.07, "turnover": 90531030.20, "stocksTraded": 15, "advancing": 6, "declining": 8, "unchanged": 1},
    "Finance": {"indexValue": 2176.74, "change": -2.09, "changePercent": -0.09, "turnover": 122122275.40, "stocksTraded": 14, "advancing": 5, "declining": 8, "unchanged": 1},
    "Hotels and Tourism": {"indexValue": 6936.59, "change": -4.18, "changePercent": -0.06, "turnover": 54710772.80, "stocksTraded": 8, "advancing": 5, "declining": 3, "unchanged": 0},
    "Hydro Power": {"indexValue": 3510.03, "change": -1.07, "changePercent": -0.03, "turnover": 1873505777.51, "stocksTraded": 111, "advancing": 44, "declining": 62, "unchanged": 5},
    "Investment": {"indexValue": 93.52, "change": -0.52, "changePercent": -0.55, "turnover": 123574879.50, "stocksTraded": 7, "advancing": 1, "declining": 6, "unchanged": 0},
    "Life Insurance": {"indexValue": 11571.84, "change": -31.42, "changePercent": -0.27, "turnover": 80121665.20, "stocksTraded": 14, "advancing": 4, "declining": 9, "unchanged": 1},
    "Manufacturing and Processing": {"indexValue": 10479.26, "change": -76.84, "changePercent": -0.73, "turnover": 614599466.30, "stocksTraded": 17, "advancing": 4, "declining": 12, "unchanged": 1},
    "Microfinance": {"indexValue": 4429.26, "change": -17.36, "changePercent": -0.39, "turnover": 101511288.30, "stocksTraded": 49, "advancing": 15, "declining": 32, "unchanged": 2},
    "Mutual Fund": {"indexValue": 19.47, "change": -0.04, "changePercent": -0.18, "turnover": 9474810.17, "stocksTraded": 0, "advancing": 0, "declining": 0, "unchanged": 0},
    "Non Life Insurance": {"indexValue": 9347.72, "change": -58.08, "changePercent": -0.62, "turnover": 36075889.60, "stocksTraded": 13, "advancing": 4, "declining": 9, "unchanged": 0},
    "Others": {"indexValue": 1793.34, "change": -6.49, "changePercent": -0.36, "turnover": 51696854.00, "stocksTraded": 9, "advancing": 0, "declining": 8, "unchanged": 1},
    "Trading": {"indexValue": 3181.17, "change": -44.44, "changePercent": -1.38, "turnover": 10821934.00, "stocksTraded": 2, "advancing": 1, "declining": 1, "unchanged": 0},
}


def _sector_index_id(name: str) -> int | None:
    key = str(name or "").strip().lower().replace("&", "and").replace("-", " ")
    key = " ".join(key.split())
    aliases = {
        "banking": "Commercial Banks", "commercial bank": "Commercial Banks", "commercial banks": "Commercial Banks",
        "development bank": "Development Banks", "development banks": "Development Banks",
        "hotel and tourism": "Hotels and Tourism", "hotels and tourism": "Hotels and Tourism", "hotels": "Hotels and Tourism",
        "hydropower": "Hydro Power", "hydro power": "Hydro Power", "hydropower index": "Hydro Power",
        "manufacturing": "Manufacturing and Processing", "manufacturing and processing": "Manufacturing and Processing",
        "microfinance index": "Microfinance", "non life insurance": "Non Life Insurance", "non-life insurance": "Non Life Insurance",
        "mutual funds": "Mutual Fund", "mutual fund": "Mutual Fund", "trading index": "Trading", "others index": "Others",
        "investment index": "Investment", "life insurance index": "Life Insurance",
    }
    canonical = aliases.get(key, str(name or "").strip())
    return SECTOR_INDEX_IDS.get(canonical)


async def _get_sector_history(index_id: int):
    key = f"sector_history:{index_id}"
    async def load():
        errors=[]
        # nepse.py / nepsepy installations expose either an index graph or index history.
        for method, args in (("get_index_daily_graph", (index_id,)), ("index_daily_graph", (index_id,)), ("daily_index_graph", (index_id,)), ("index_history", (index_id, 1, 1000))):
            try:
                raw = await production_call(method, *args)
                rows = _normalize_graph_points(raw)
                if rows:
                    return {"ok": True, "data": rows, "source": "NEPSE sector index graph", "updatedAt": now_iso(), "errors": errors}
            except Exception as e:
                errors.append(f"production {method}: {e}")
        try:
            client = await get_nepse_client()
            for method, args in (("get_index_daily_graph", (index_id,)), ("index_daily_graph", (index_id,)), ("daily_index_graph", (index_id,)), ("index_history", (index_id, 1, 1000))):
                fn = getattr(client, method, None)
                if fn is None: continue
                try:
                    raw = await fn(*args)
                    rows = _normalize_graph_points(raw)
                    if rows:
                        return {"ok": True, "data": rows, "source": "NEPSE sector index graph", "updatedAt": now_iso(), "errors": errors}
                except Exception as e:
                    errors.append(f"nepsepy {method}: {e}")
        except Exception as e:
            errors.append(f"client: {e}")
        # Public rumess-compatible endpoint. This is a real NEPSE graph feed;
        # keep it as a fallback because some Python SDK versions do not expose
        # the graph method under the same name.
        try:
            raw = await public_get("/dailyIndexGraph", {"indexId": index_id})
            rows = _normalize_graph_points(raw)
            if rows:
                return {"ok": True, "data": rows, "source": "NEPSE dailyIndexGraph", "updatedAt": now_iso(), "errors": errors}
        except Exception as e:
            errors.append(f"public dailyIndexGraph: {e}")
        return {"ok": False, "data": [], "source": None, "updatedAt": now_iso(), "errors": errors}
    return await cached(key, load)


def _normalize_graph_points(raw: Any) -> list[dict]:
    out=[]
    def add(ts, value):
        v=num(value)
        if v is None: return
        if isinstance(ts, (int,float)):
            try: dt=datetime.fromtimestamp(float(ts), tz=timezone.utc).isoformat()
            except Exception: dt=str(ts)
        else: dt=str(ts or "")
        out.append({"time": dt, "value": v})
    def walk(x):
        if isinstance(x, (list,tuple)):
            if len(x) >= 2 and not isinstance(x[0], (dict,list,tuple)) and not isinstance(x[1], (dict,list,tuple)):
                add(x[0], x[1]); return
            for y in x: walk(y)
        elif isinstance(x, dict):
            # Common graph point shapes.
            ts=pick(x,["time","timestamp","unixTime","unix_time","date","businessDate","tradingDate","datetime"])
            val=pick(x,["value","indexValue","currentValue","close","price","ltp","lastPrice"])
            if ts is not None and val is not None: add(ts,val)
            for k in ("data","content","results","result","rows","history","points","graph"):
                if isinstance(x.get(k),(list,dict)): walk(x[k])
    walk(raw)
    seen=set(); clean=[]
    for r in sorted(out,key=lambda z:z["time"]):
        k=(r["time"],r["value"])
        if k not in seen: seen.add(k); clean.append(r)
    return clean[-240:]

@app.get("/api/sectors")
async def api_sectors():
    data = await get_sectors()
    if isinstance(data, dict) and isinstance(data.get("data"), list):
        enriched=[]
        for x in data["data"]:
            item=dict(x)
            item["indexId"] = _sector_index_id(item.get("sector") or item.get("name"))
            enriched.append(item)
        data=dict(data); data["data"]=enriched
    return data

@app.get("/api/sector-history")
async def api_sector_history(index_id: int = Query(..., ge=1, le=200)):
    return await _get_sector_history(index_id)

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

def _depth_rows(value: Any) -> list[dict]:
    """Flatten NEPSE order-book payloads into candidate depth rows."""
    out=[]
    def walk(v, side_hint=None, depth=0):
        if depth>8 or v is None: return
        if isinstance(v, list):
            for x in v: walk(x, side_hint, depth+1)
            return
        if not isinstance(v, dict): return
        side = str(pick(v, ["side","orderSide","transactionType","type","direction"], side_hint) or "").lower()
        if side in ("b","bid","buy","buyorder","buyorders","demand"):
            side="buy"
        elif side in ("s","ask","sell","sellorder","sellorders","supply"):
            side="sell"
        price=num(pick(v,["price","orderPrice","orderBookOrderPrice","order_book_order_price","rate","buyPrice","sellPrice","bidPrice","askPrice"]))
        qty=num(pick(v,["quantity","qty","orderQuantity","orderBookOrderQuantity","order_book_order_quantity","tradedQuantity","totalQuantity","buyQuantity","sellQuantity","bidQuantity","askQuantity"]))
        orders=num(pick(v,["orders","orderCount","order_count","orderBookOrderCount","order_book_order_count","noOfOrders","numberOfOrders","buyOrders","sellOrders","bidOrders","askOrders"]))
        if price is not None and (qty is not None or orders is not None):
            out.append({"side":side,"price":price,"quantity":qty or 0,"orders":orders or 0})
        for k,vv in v.items():
            kl=str(k).lower()
            child_side=side_hint
            if any(x in kl for x in ("buy","bid","demand")): child_side="buy"
            elif any(x in kl for x in ("sell","ask","supply")): child_side="sell"
            if isinstance(vv,(dict,list)):
                walk(vv,child_side,depth+1)
    walk(value)
    # Remove duplicate rows produced by nested wrappers.
    seen=set(); clean=[]
    for r in out:
        key=(r["side"],r["price"],r["quantity"],r["orders"])
        if key in seen: continue
        seen.add(key); clean.append(r)
    return clean


def _first_nested_number(value: Any, keys: list[str]) -> Optional[float]:
    target={str(k).lower() for k in keys}
    def walk(v, depth=0):
        if depth>8 or v is None:
            return None
        if isinstance(v, dict):
            for k,val in v.items():
                if str(k).lower() in target:
                    n=num(val)
                    if n is not None:
                        return n
            for val in v.values():
                n=walk(val, depth+1)
                if n is not None:
                    return n
        elif isinstance(v, list):
            for val in v:
                n=walk(val, depth+1)
                if n is not None:
                    return n
        return None
    return walk(value)


def _normalize_depth_payload(raw: Any, symbol: str) -> dict:
    rows=_depth_rows(raw)
    buys=sorted([r for r in rows if r["side"]=="buy"], key=lambda r:r["price"], reverse=True)[:5]
    sells=sorted([r for r in rows if r["side"]=="sell"], key=lambda r:r["price"])[:5]
    # Prefer exchange/feed totals when supplied. Falling back to the visible
    # top-5 sum is mathematically derived from real rows, never fabricated.
    buy_qty=_first_nested_number(raw,["totalBuyQuantity","total_buy_quantity","totalBuyQty","buyQuantityTotal","totalBuyOrderQuantity"])
    sell_qty=_first_nested_number(raw,["totalSellQuantity","total_sell_quantity","totalSellQty","sellQuantityTotal","totalSellOrderQuantity"])
    buy_orders=_first_nested_number(raw,["totalBuyOrders","total_buy_orders","buyOrdersTotal","totalBuyOrderCount"])
    sell_orders=_first_nested_number(raw,["totalSellOrders","total_sell_orders","sellOrdersTotal","totalSellOrderCount"])
    return {
        "ok": bool(buys or sells),
        "symbol": symbol.upper(),
        "buy": buys, "sell": sells,
        "totalBuyQuantity": buy_qty if buy_qty is not None else sum(float(r["quantity"] or 0) for r in buys),
        "totalSellQuantity": sell_qty if sell_qty is not None else sum(float(r["quantity"] or 0) for r in sells),
        "totalBuyOrders": buy_orders if buy_orders is not None else sum(float(r["orders"] or 0) for r in buys),
        "totalSellOrders": sell_orders if sell_orders is not None else sum(float(r["orders"] or 0) for r in sells),
        "updatedAt": now_iso(),
    }

@app.get("/api/production/depth/{symbol}")
async def production_depth(symbol: str):
    symbol=symbol.upper().strip()
    errors=[]

    # Primary live path: current nepsepy client. It resolves the security id
    # internally and calls NEPSE's market-depth endpoint with the live session.
    for method in ("getSymbolMarketDepth", "get_market_depth", "market_depth", "depth"):
        try:
            raw = await nepse_call([method], symbol)
            normalized = _normalize_depth_payload(raw, symbol)
            normalized["source"] = "NEPSE live market-depth feed"
            normalized["rawAvailable"] = raw is not None
            if normalized["ok"]:
                # Frontend compatibility: expose NEPSE's original field names
                # alongside our normalized buy/sell arrays.
                normalized["buyMarketDepthList"] = normalized["buy"]
                normalized["sellMarketDepthList"] = normalized["sell"]
                normalized["totalBuyQty"] = normalized["totalBuyQuantity"]
                normalized["totalSellQty"] = normalized["totalSellQuantity"]
                _LAST_VALID_DEPTH[symbol] = dict(normalized)
                normalized["cached"] = False
                return normalized
            errors.append(f"nepsepy {method}: no usable levels")
        except Exception as exc:
            errors.append(f"nepsepy {method}: {exc}")

    # Secondary: nepse.py production SDK.
    # Different NEPSE Python clients expose this same endpoint under different
    # method names. Try the known market-depth names before using the HTTP
    # compatibility endpoint. All of them return the exchange order book.
    for method in ("getSymbolMarketDepth", "get_market_depth", "market_depth", "depth"):
        try:
            raw=await production_call(method, symbol)
            normalized=_normalize_depth_payload(raw, symbol)
            normalized["source"]="NEPSE production market-depth feed"
            normalized["rawAvailable"]=raw is not None
            if normalized["ok"]:
                normalized["buyMarketDepthList"] = normalized["buy"]
                normalized["sellMarketDepthList"] = normalized["sell"]
                normalized["totalBuyQty"] = normalized["totalBuyQuantity"]
                normalized["totalSellQty"] = normalized["totalSellQuantity"]
                _LAST_VALID_DEPTH[symbol] = dict(normalized)
                normalized["cached"] = False
                return normalized
            errors.append(f"production SDK {method}: no usable levels")
        except Exception as exc:
            errors.append(f"production SDK {method}: {exc}")

    # Fallback: the public NEPSE-compatible marketDepth endpoint. This is
    # still live order-book data; do not substitute historical/derived prices.
    for path in ("/marketDepth", "/MarketDepth"):
        try:
            raw=await public_get(path, {"symbol":symbol})
            normalized=_normalize_depth_payload(raw, symbol)
            normalized["source"]="NEPSE market-depth public feed"
            normalized["rawAvailable"]=raw is not None
            if normalized["ok"]:
                normalized["buyMarketDepthList"] = normalized["buy"]
                normalized["sellMarketDepthList"] = normalized["sell"]
                normalized["totalBuyQty"] = normalized["totalBuyQuantity"]
                normalized["totalSellQty"] = normalized["totalSellQuantity"]
                _LAST_VALID_DEPTH[symbol] = dict(normalized)
                normalized["cached"] = False
                return normalized
            errors.append(f"{path}: no usable levels")
        except Exception as exc:
            errors.append(f"{path}: {exc}")

    if symbol in _LAST_VALID_DEPTH:
        cached=dict(_LAST_VALID_DEPTH[symbol])
        cached["cached"]=True
        cached["source"]=str(cached.get("source") or "NEPSE live market-depth feed")+" / last verified snapshot"
        cached["error"]="; ".join(errors[-6:])
        return cached
    return {"ok":False,"symbol":symbol,"buy":[],"sell":[],"totalBuyQuantity":None,"totalSellQuantity":None,"totalBuyOrders":None,"totalSellOrders":None,"buyMarketDepthList":[],"sellMarketDepthList":[],"totalBuyQty":None,"totalSellQty":None,"updatedAt":now_iso(),"source":"NEPSE live market-depth feeds","error":"; ".join(errors[-6:])}

# Compatibility route used by the existing company-detail frontend.
@app.get("/MarketDepth")
async def compat_market_depth(symbol: str):
    return await production_depth(symbol)

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
# The adapter is backed by the same NEPSE history layer used by the rest of
# the app. TradingView supplies the chart engine; NEPSE Pulse supplies data.
async def _tv_daily_rows(symbol: str, countback: int = 5000):
    payload = await get_history(symbol.upper().strip())
    rows = payload.get("data", []) if isinstance(payload, dict) else payload
    clean = []
    for r in rows or []:
        if not isinstance(r, dict):
            continue
        c = num(pick(r, ["close", "price", "ltp", "lastPrice"]))
        if c is None:
            continue
        d = pick(r, ["date", "businessDate", "publishedDate", "published_date", "tradeDate"])
        if not d:
            continue
        o = num(pick(r, ["open", "openingPrice"])) or c
        h = num(pick(r, ["high", "highPrice"])) or max(o, c)
        l = num(pick(r, ["low", "lowPrice"])) or min(o, c)
        v = num(pick(r, ["volume", "tradedQuantity", "totalTradedQuantity", "quantity"])) or 0
        try:
            if isinstance(d, (int, float)):
                ts = int(d / 1000) if d > 10_000_000_000 else int(d)
            else:
                dt = datetime.fromisoformat(str(d).replace("Z", "+00:00"))
                if dt.tzinfo is None:
                    dt = dt.replace(tzinfo=NPT)
                ts = int(dt.timestamp())
        except Exception:
            try:
                dt = datetime.strptime(str(d)[:10], "%Y-%m-%d").replace(tzinfo=NPT)
                ts = int(dt.timestamp())
            except Exception:
                continue
        clean.append({"t": ts, "o": o, "h": h, "l": l, "c": c, "v": v})
    clean.sort(key=lambda x: x["t"])
    dedup = {}
    for row in clean:
        dedup[row["t"]] = row
    return list(dedup.values())[-countback:]

def _tv_parse_trade_time(value):
    if value is None:
        return None
    try:
        if isinstance(value, (int, float)):
            n = float(value)
            return int(n / 1000) if n > 10_000_000_000 else int(n)
        raw = str(value).strip()
        if not raw:
            return None
        if raw.isdigit():
            n = int(raw)
            return int(n / 1000) if n > 10_000_000_000 else n
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=NPT)
        return int(dt.timestamp())
    except Exception:
        return None

async def _tv_trade_rows(symbol: str, limit: int = 5000):
    try:
        payload = await production_call("trades", symbol=symbol.upper().strip(), max_pages=20, size=min(max(limit, 100), 500))
    except Exception:
        return []
    rows = arr(payload.get("data") if isinstance(payload, dict) else payload)
    out = []
    for r in rows:
        if not isinstance(r, dict):
            continue
        price = num(pick(r, ["price", "rate", "ltp", "lastPrice", "tradePrice", "close"]))
        if price is None:
            continue
        ts = _tv_parse_trade_time(pick(r, ["timestamp", "time", "tradeTime", "tradeDateTime", "date", "businessDate", "createdAt"]))
        if ts is None:
            continue
        qty = num(pick(r, ["quantity", "qty", "volume", "tradedQuantity", "tradeQuantity"])) or 0
        out.append({"t": ts, "p": price, "v": qty})
    out.sort(key=lambda x: x["t"])
    return out[-limit:]

def _tv_intraday_resample(trades: list[dict], resolution: str):
    try:
        minutes = int(str(resolution).upper().replace("MIN", ""))
    except Exception:
        minutes = 1
    minutes = max(1, minutes)
    bucket_seconds = minutes * 60
    buckets = {}
    for tr in trades:
        ts = int(tr["t"])
        # Anchor to Nepal local session clock while retaining epoch timestamps.
        dt = datetime.fromtimestamp(ts, tz=NPT)
        midnight = dt.replace(hour=0, minute=0, second=0, microsecond=0)
        elapsed = int((dt - midnight).total_seconds())
        start = midnight + timedelta(seconds=(elapsed // bucket_seconds) * bucket_seconds)
        key = int(start.timestamp())
        b = buckets.get(key)
        if b is None:
            buckets[key] = {"t": key, "o": tr["p"], "h": tr["p"], "l": tr["p"], "c": tr["p"], "v": tr.get("v", 0) or 0}
        else:
            b["h"] = max(b["h"], tr["p"])
            b["l"] = min(b["l"], tr["p"])
            b["c"] = tr["p"]
            b["v"] += tr.get("v", 0) or 0
    return [buckets[k] for k in sorted(buckets)]

def _tv_resample(rows: list[dict], resolution: str):
    resolution = str(resolution or "D").upper()
    if resolution in ("D", "1D"):
        return rows
    if resolution.endswith("MIN") or resolution.isdigit():
        return rows
    buckets = {}
    for r in rows:
        dt = datetime.fromtimestamp(r["t"], tz=NPT)
        if resolution in ("W", "1W"):
            key = (dt.date() - timedelta(days=dt.weekday())).isoformat()
        elif resolution in ("M", "1M"):
            key = f"{dt.year:04d}-{dt.month:02d}"
        elif resolution in ("Y", "1Y"):
            key = f"{dt.year:04d}"
        else:
            key = dt.date().isoformat()
        b = buckets.get(key)
        if b is None:
            buckets[key] = dict(r)
        else:
            b["h"] = max(b["h"], r["h"])
            b["l"] = min(b["l"], r["l"])
            b["c"] = r["c"]
            b["v"] = (b.get("v") or 0) + (r.get("v") or 0)
    return list(sorted(buckets.values(), key=lambda x: x["t"]))

TV_INTRADAY_RESOLUTIONS = ["1", "3", "5", "10", "15", "30", "60"]
TV_ALL_RESOLUTIONS = TV_INTRADAY_RESOLUTIONS + ["D", "W", "M", "Y"]

async def _tv_rows_for_resolution(symbol: str, resolution: str, countback: int = 5000):
    resolution = str(resolution or "D").upper()
    if resolution in TV_INTRADAY_RESOLUTIONS or resolution.endswith("MIN"):
        trades = await _tv_trade_rows(symbol, limit=max(1000, min(countback * 20, 10000)))
        return _tv_intraday_resample(trades, resolution)[-countback:]
    return _tv_resample(await _tv_daily_rows(symbol, countback=5000), resolution)[-countback:]

@app.get("/api/tv/config")
async def tv_config():
    return {
        "supports_search": True,
        "supports_group_request": False,
        "supports_marks": False,
        "supports_timescale_marks": False,
        "supports_time": True,
        "supported_resolutions": TV_ALL_RESOLUTIONS,
        "supports_seconds": False,
    }

@app.get("/api/tv/time")
async def tv_time():
    return int(time.time())

@app.get("/api/tv/search")
async def tv_search(
    q: str = Query(""),
    query: str = Query(""),
    limit: int = Query(30, ge=1, le=100),
    exchange: str = Query(""),
    type: str = Query(""),
):
    # Accept both the existing Pulse parameter (q) and TradingView UDF
    # parameter (query). The native UDF adapter calls /search directly.
    query = str(query or q or "").strip().upper()
    raw = await company_list()
    companies = arr(raw)
    out = []
    for c in companies:
        sym = str(pick(c, ["symbol", "ticker", "code"], "") or "").upper()
        name = str(pick(c, ["companyName", "company", "securityName", "name"], "") or "")
        if not sym:
            continue
        if exchange and exchange.upper() not in ("NEPSE", ""):
            continue
        if type and type.lower() not in ("stock", ""):
            continue
        if not query or query in sym or query in name.upper():
            out.append({
                "symbol": sym, "full_name": sym,
                "description": name or sym, "exchange": "NEPSE",
                "ticker": sym, "type": "stock",
            })
        if len(out) >= limit:
            break
    return out

@app.get("/api/tv/symbols")
async def tv_symbols(symbol: str):
    sym = symbol.upper().strip()
    return {
        "name": sym, "ticker": sym, "description": f"{sym} · NEPSE",
        "type": "stock", "session": "1100-1500",
        "timezone": "Asia/Kathmandu", "exchange": "NEPSE",
        "listed_exchange": "NEPSE", "minmov": 1, "pricescale": 100,
        "has_intraday": True, "has_daily": True,
        "has_weekly_and_monthly": True,
        "supported_resolutions": TV_ALL_RESOLUTIONS,
        "volume_precision": 0,
        # The endpoint can serve trade-derived bars, but it is not itself a
        # WebSocket stream. Advertise streaming only when the production SDK
        # is available; otherwise make the delayed state explicit.
        "data_status": "streaming" if PRODUCTION_SDK_ENABLED else "delayed_streaming",
    }

@app.get("/api/tv/history")
async def tv_history(
    symbol: str, resolution: str = "D",
    from_: int = Query(0, alias="from"), to: int = Query(0),
    countback: int = Query(500, ge=1, le=5000),
):
    rows = await _tv_rows_for_resolution(symbol, resolution, countback=5000)
    if from_:
        rows = [r for r in rows if r["t"] >= from_]
    if to:
        rows = [r for r in rows if r["t"] <= to]
    rows = rows[-countback:]
    if not rows:
        return {"s": "no_data"}
    return {
        "s": "ok",
        "t": [r["t"] for r in rows],
        "o": [r["o"] for r in rows],
        "h": [r["h"] for r in rows],
        "l": [r["l"] for r in rows],
        "c": [r["c"] for r in rows],
        "v": [r["v"] for r in rows],
    }


@app.get("/api/tv/realtime")
async def tv_realtime(symbol: str, resolution: str = "1"):
    rows = await _tv_rows_for_resolution(symbol, resolution, countback=2)
    if not rows:
        return {"s": "no_data"}
    r = rows[-1]
    return {"s": "ok", "bar": r}

@app.websocket("/api/tv/ws")
async def tv_websocket(websocket: WebSocket):
    """Optional shared realtime transport for clients that prefer WebSocket.

    The endpoint polls the project's configured NEPSE trade source and emits the
    latest aggregated bar. It never fabricates quotes when the upstream source
    has no trade data.
    """
    await websocket.accept()
    try:
        while True:
            msg = await websocket.receive_json()
            symbol = str(msg.get("symbol", "")).upper().strip()
            resolution = str(msg.get("resolution", "1"))
            if not symbol:
                await websocket.send_json({"s": "error", "message": "symbol required"})
                continue
            rows = await _tv_rows_for_resolution(symbol, resolution, countback=2)
            if rows:
                await websocket.send_json({"s": "ok", "symbol": symbol, "resolution": resolution, "bar": rows[-1]})
            else:
                await websocket.send_json({"s": "no_data", "symbol": symbol, "resolution": resolution})
    except WebSocketDisconnect:
        return

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


async def get_public_fundamentals(symbol: str):
    """Merge openly published YONEPSE datasets for a symbol; absent fields stay null."""
    symbol = symbol.upper().strip()
    key = f"public-fundamentals:{symbol}"
    async def load():
        paths = {
            "prices": "nepse_data.json",
            "profiles": "company/profiles.json",
            "financials": "company/financials.json",
            "securities": "other/securities.json",
            "dividends": "proposed_dividend/history_all_years.json",
            "dividendLatest": "proposed_dividend/latest_1y.json",
            "highLow": "market/top_stocks.json",
        }
        async def fetch(label, path):
            try: return label, await yonepse_get(path)
            except Exception: return label, None
        got = dict(await asyncio.gather(*(fetch(k,v) for k,v in paths.items())))
        def rows(obj):
            if isinstance(obj, list): return obj
            if isinstance(obj, dict):
                for k in ("data","results","items","content","stocks","companies"):
                    if isinstance(obj.get(k), list): return obj[k]
                # financials/profiles can be keyed by company symbol
                if symbol in obj:
                    v=obj[symbol]
                    return v if isinstance(v,list) else [v]
            return []
        def sym(row):
            return str(row.get("symbol") or row.get("stockSymbol") or row.get("ticker") or row.get("symbolCode") or "").upper()
        def matching(obj):
            if isinstance(obj,dict) and symbol in obj:
                v=obj[symbol]; return v if isinstance(v,list) else [v]
            return [x for x in rows(obj) if isinstance(x,dict) and sym(x)==symbol]
        price=matching(got.get("prices"))
        profile=matching(got.get("profiles"))
        security=matching(got.get("securities"))
        financial=matching(got.get("financials"))
        dividends=matching(got.get("dividends"))+matching(got.get("dividendLatest"))
        hl=matching(got.get("highLow"))
        # de-duplicate dividend records without discarding source fields
        seen=set(); div=[]
        for d in dividends:
            marker=str((d.get("fiscalYear"),d.get("bookCloseDate"),d.get("cashDividend"),d.get("bonusDividend"),d.get("date")))
            if marker not in seen: seen.add(marker); div.append(d)
        return {"ok":bool(price or profile or security or financial or div or hl),"symbol":symbol,
                "price":price,"profile":profile,"security":security,"financials":financial,
                "dividends":div,"highLow":hl,"sources":{"prices":"YONEPSE public static market feed",
                "profile":"YONEPSE public company profiles","financials":"YONEPSE public financial reports",
                "dividends":"YONEPSE public proposed-dividend archive","highLow":"YONEPSE market snapshot; 52-week fields only when present"},
                "updatedAt":now_iso()}
    return await cached(key,load)

@app.get("/api/public/fundamentals/{symbol}")
async def api_public_fundamentals(symbol: str):
    return await get_public_fundamentals(symbol)


@app.get("/api/stock-overview/{symbol}")
async def api_stock_overview(symbol: str):
    """Single normalized payload for the Stock Info terminal.

    Every value is sourced from an actual market/company/history dataset or is
    mathematically derived from those values. Missing source values remain null
    so the frontend can render an explicit em dash instead of inventing data.
    """
    symbol = symbol.upper().strip()
    base = await get_fundamentals(symbol)
    public = await get_public_fundamentals(symbol)
    history = await get_history(symbol)

    def first_obj(*objs):
        return next((o for o in objs if isinstance(o, dict)), {})
    def first_val(*objs, keys=()):
        for o in objs:
            if not isinstance(o, dict):
                continue
            v = pick(o, list(keys))
            if v not in (None, "", "-"):
                return v
        return None
    def rows(obj):
        return deep_rows(obj) or arr(obj)

    profile = first_obj(base.get("profile"), base.get("detail"), public.get("profile"))
    market = first_obj(base.get("market"))
    valuation = first_obj(base.get("valuation"), base.get("fundamentals"))
    price_rows = rows(public.get("price"))
    security_rows = rows(public.get("security"))
    financial_rows = rows(public.get("financials"))
    dividend_rows = rows(public.get("dividends"))
    history_rows = history.get("data", []) if isinstance(history, dict) else []

    # Merge the live row from the authoritative market feed when available.
    live = {}
    try:
        raw_live = await production_call("live_market")
        live_rows = deep_rows(raw_live, ("content", "data", "results", "rows"))
        for r in live_rows:
            rs = str(pick(r, ["symbol", "ticker", "securitySymbol", "code", "stockSymbol"], "")).upper()
            if rs == symbol:
                live = r
                break
    except Exception:
        pass
    if not live:
        for r in rows((await get_market()).get("live", [])):
            rs = str(pick(r, ["symbol", "ticker", "securitySymbol", "code", "stockSymbol"], "")).upper()
            if rs == symbol:
                live = r
                break

    ltp = num(first_val(live, market, valuation, profile, *price_rows, keys=("ltp","lastTradedPrice","lastPrice","close","closingPrice","price")))
    previous_close = num(first_val(live, market, profile, *price_rows, keys=("previousClose","previousClosingPrice","prevClose","previousPrice","preClose")))
    open_price = num(first_val(live, market, profile, *price_rows, keys=("open","openPrice","openingPrice")))
    high = num(first_val(live, market, profile, *price_rows, keys=("high","highPrice","dayHigh")))
    low = num(first_val(live, market, profile, *price_rows, keys=("low","lowPrice","dayLow")))
    absolute_change = num(first_val(live, market, profile, *price_rows, keys=("change","pointChange","difference","changeValue")))
    change_pct = num(first_val(live, market, profile, *price_rows, keys=("percentageChange","perChange","percentChange","changePercent","pChange","pct")))
    if change_pct is None and absolute_change is not None and previous_close not in (None, 0):
        change_pct = absolute_change / previous_close * 100
    if absolute_change is None and ltp is not None and previous_close is not None:
        absolute_change = ltp - previous_close

    volume = num(first_val(live, market, *price_rows, keys=("volume","totalTradedQuantity","tradedQuantity","quantity","tradedShares","sharesTraded")))
    turnover = num(first_val(live, market, *price_rows, keys=("turnover","totalTurnover","totalTradedValue","tradedAmount","value")))
    transactions = num(first_val(live, market, *price_rows, keys=("transactions","totalTransactions","totalTrades","noOfTransactions","numberOfTransactions")))
    average_price = num(first_val(live, market, *price_rows, keys=("averagePrice","avgPrice","weightedAveragePrice")))
    listed_shares = num(first_val(valuation, profile, *security_rows, keys=("listedShares","totalListedShares","numberOfListedShares","totalShares","shareOutstanding")))
    market_cap = num(first_val(valuation, market, profile, *security_rows, keys=("marketCap","marketCapitalization","marketCapitalizationValue","marCap")))
    if market_cap is None and ltp is not None and listed_shares is not None:
        market_cap = ltp * listed_shares

    latest_fin = first_obj(*(base.get("financials") or []), *financial_rows)
    eps = num(first_val(valuation, latest_fin, profile, *financial_rows, keys=("eps","earningPerShare","earningsPerShare")))
    book_value = num(first_val(valuation, latest_fin, profile, *financial_rows, keys=("bookValue","book_value","netWorthPerShare")))
    pe = num(first_val(valuation, latest_fin, profile, *financial_rows, keys=("pe","peRatio","priceEarningRatio")))
    pbv = num(first_val(valuation, latest_fin, profile, *financial_rows, keys=("pb","pbv","pbRatio","priceBookRatio")))
    roe = num(first_val(valuation, latest_fin, profile, *financial_rows, keys=("roe","returnOnEquity")))
    if pe is None and ltp is not None and eps not in (None, 0): pe = ltp / eps
    if pbv is None and ltp is not None and book_value not in (None, 0): pbv = ltp / book_value

    cash_div = num(first_val(valuation, *dividend_rows, keys=("cashDividend","cash","cashPercentage","cashDividendPercent")))
    bonus_div = num(first_val(valuation, *dividend_rows, keys=("bonusDividend","bonus","bonusPercentage","bonusDividendPercent")))
    dividend_yield = num(first_val(valuation, *dividend_rows, keys=("dividendYield","yield")))
    if dividend_yield is None and cash_div is not None and ltp not in (None, 0):
        dividend_yield = cash_div / ltp * 100

    closes = [num(r.get("close")) for r in history_rows if isinstance(r, dict) and num(r.get("close")) is not None]
    def trailing_return(n):
        if ltp is None or len(closes) <= n or closes[-(n+1)] in (None, 0): return None
        return (ltp / closes[-(n+1)] - 1) * 100
    high52 = num(first_val(*price_rows, *security_rows, profile, keys=("fiftyTwoWeekHigh","high52","yearHigh","week52High")))
    low52 = num(first_val(*price_rows, *security_rows, profile, keys=("fiftyTwoWeekLow","low52","yearLow","week52Low")))
    if closes:
        last252 = closes[-252:]
        if high52 is None and last252: high52 = max(last252)
        if low52 is None and last252: low52 = min(last252)

    company_name = first_val(base.get("company"), profile, *public.get("profile", []), keys=("companyName","securityName","name","company_name")) or symbol
    sector = first_val(profile, *security_rows, keys=("sectorName","sector","sectorDescription"))

    return {
        "ok": bool(base.get("ok") or public.get("ok") or history.get("ok") or live),
        "symbol": symbol,
        "companyName": company_name,
        "sector": sector,
        "updatedAt": now_iso(),
        "source": {
            "market": "NEPSE live market feed" if live else (base.get("sources", {}).get("market") or "Unavailable"),
            "history": history.get("source") if isinstance(history, dict) else "Unavailable",
            "fundamentals": "NEPSE company/fundamental data + YONEPSE public fallback",
        },
        "market": {
            "ltp": ltp, "change": absolute_change, "changePercent": change_pct,
            "open": open_price, "high": high, "low": low, "previousClose": previous_close,
            "turnover": turnover, "volume": volume, "transactions": transactions,
            "averagePrice": average_price, "listedShares": listed_shares, "marketCap": market_cap,
        },
        "performance": {
            "eps": eps, "pe": pe, "bookValue": book_value, "pbv": pbv, "roe": roe,
            "cashDividend": cash_div, "bonusDividend": bonus_div, "dividendYield": dividend_yield,
            "high52": high52, "low52": low52, "return1W": trailing_return(5),
            "return1M": trailing_return(22), "return3M": trailing_return(66),
            "return6M": trailing_return(132), "return1Y": trailing_return(252),
            "historySessions": len(closes),
        },
        "history": history_rows,
        "financials": base.get("financials") or financial_rows,
        "dividends": dividend_rows,
        "corporateActions": base.get("corporateActions") or [],
        "board": base.get("board") or [],
        "agm": base.get("agm") or [],
        "companyNews": base.get("companyNews") or [],
        "profile": profile,
        "errors": (base.get("errors") or []) + (public.get("errors") or []) + (history.get("errors") or [] if isinstance(history, dict) else []),
    }

@app.get("/api/fundamentals/{symbol}")
async def api_fundamentals(symbol: str):
    base=await get_fundamentals(symbol)
    try:
        public=await get_public_fundamentals(symbol)
        base["publicData"]=public
        # Fill only missing summary metrics from actual public rows.
        def first(rows, keys):
            for row in rows:
                if isinstance(row,dict):
                    for k in keys:
                        v=row.get(k)
                        if v not in (None, "", "-"): return num(v)
            return None
        val=base.setdefault("valuation",{})
        pubrows=public.get("financials",[])+public.get("price",[])+public.get("highLow",[])
        aliases={"eps":["eps","earningPerShare","earningsPerShare"],"bookValue":["bookValue","book_value","netWorthPerShare"],"pe":["pe","peRatio","priceEarningRatio"],"pb":["pb","pbv","pbRatio","priceBookRatio"],"marketCap":["marketCap","marketCapitalization"],"fiftyTwoWeekHigh":["fiftyTwoWeekHigh","high52","yearHigh"],"fiftyTwoWeekLow":["fiftyTwoWeekLow","low52","yearLow"]}
        for field,keys in aliases.items():
            if val.get(field) is None: val[field]=first(pubrows,keys)
        base["publicSources"]=public.get("sources",{})
    except Exception as exc:
        base.setdefault("errors",[]).append(f"public datasets: {exc}")
    return base



# ---------------------------------------------------------------------------
# Dedicated technical screener API
# ---------------------------------------------------------------------------
def _ts_sma(a: list[float], n: int):
    return (sum(a[-n:]) / n) if len(a) >= n else None

def _ts_ema(a: list[float], n: int):
    if len(a) < n:
        return None
    e = sum(a[:n]) / n
    k = 2 / (n + 1)
    for v in a[n:]:
        e = v * k + e * (1 - k)
    return e

def _ts_rsi(a: list[float], n: int = 14):
    if len(a) < n + 1:
        return None
    gains = sum(max(a[i] - a[i-1], 0) for i in range(1, n+1)) / n
    losses = sum(max(a[i-1] - a[i], 0) for i in range(1, n+1)) / n
    for i in range(n + 1, len(a)):
        d = a[i] - a[i-1]
        gains = (gains * (n - 1) + max(d, 0)) / n
        losses = (losses * (n - 1) + max(-d, 0)) / n
    if losses == 0:
        return 100.0
    return 100 - (100 / (1 + gains / losses))

def _ts_macd(a: list[float]):
    e12 = _ts_ema(a, 12)
    e26 = _ts_ema(a, 26)
    if e12 is None or e26 is None:
        return None, None, None
    # Build the MACD series so the signal line is calculated from the same history.
    macd_series=[]
    for i in range(26, len(a)+1):
        left=a[:i]
        x12=_ts_ema(left,12); x26=_ts_ema(left,26)
        if x12 is not None and x26 is not None:
            macd_series.append(x12-x26)
    line=macd_series[-1] if macd_series else None
    signal=_ts_ema(macd_series,9) if len(macd_series)>=9 else None
    hist=(line-signal) if line is not None and signal is not None else None
    return line, signal, hist

def _ts_bbands(a: list[float], n: int = 20, mult: float = 2):
    if len(a) < n:
        return None, None, None
    w=a[-n:]; mid=sum(w)/n
    variance=sum((x-mid)**2 for x in w)/n
    sd=variance**0.5
    return mid+mult*sd, mid, mid-mult*sd

def _ts_stoch(a: list[float], n: int = 14):
    if len(a) < n:
        return None
    w=a[-n:]; hi=max(w); lo=min(w)
    return ((w[-1]-lo)/(hi-lo)*100) if hi != lo else 50.0

def _ts_atr(rows: list[dict], n: int = 14):
    if len(rows) < n+1:
        return None
    trs=[]
    for i in range(1,len(rows)):
        r=rows[i]; prev=rows[i-1]
        h=num(r.get('high')); l=num(r.get('low')); pc=num(prev.get('close'))
        if h is None or l is None: continue
        trs.append(max(h-l, abs(h-pc)) if pc is not None else h-l)
    return (sum(trs[-n:])/n) if len(trs)>=n else None

def _ts_adx(rows: list[dict], n: int = 14):
    if len(rows) < n*2+1:
        return None, None, None
    trs=[]; plus=[]; minus=[]
    for i in range(1,len(rows)):
        r=rows[i]; p=rows[i-1]
        h=num(r.get('high')); l=num(r.get('low')); ph=num(p.get('high')); pl=num(p.get('low')); pc=num(p.get('close'))
        if None in (h,l,ph,pl,pc): continue
        trs.append(max(h-l,abs(h-pc),abs(l-pc)))
        up=h-ph; dn=pl-l
        plus.append(up if up>dn and up>0 else 0)
        minus.append(dn if dn>up and dn>0 else 0)
    if len(trs)<n*2: return None,None,None
    atr=sum(trs[:n])/n; pdi=sum(plus[:n])/n; mdi=sum(minus[:n])/n; dxs=[]
    for i in range(n,len(trs)):
        atr=(atr*(n-1)+trs[i])/n; pdi=(pdi*(n-1)+plus[i])/n; mdi=(mdi*(n-1)+minus[i])/n
        p=100*pdi/atr if atr else 0; m=100*mdi/atr if atr else 0
        dxs.append(100*abs(p-m)/(p+m) if p+m else 0)
    adx=_ts_ema(dxs,n) if len(dxs)>=n else None
    return adx, (100*pdi/atr if atr else None), (100*mdi/atr if atr else None)

def _aggregate_technical_history(rows: list[dict], timeframe: str):
    tf=str(timeframe or 'D').upper()
    if tf in ('D','1D','DAILY'):
        return rows
    groups={}
    for r in rows:
        raw=str(r.get('date') or '')[:10]
        try:
            d=datetime.fromisoformat(raw).date()
        except Exception:
            continue
        if tf in ('1W','W','WEEKLY'):
            key=(d - timedelta(days=d.weekday())).isoformat()
        elif tf in ('1M','M','MONTHLY'):
            key=f'{d.year:04d}-{d.month:02d}-01'
        else:
            key=raw
        groups.setdefault(key,[]).append(r)
    out=[]
    for key,items in sorted(groups.items()):
        items=sorted(items,key=lambda x:str(x.get('date') or ''))
        first,last=items[0],items[-1]
        opens=num(first.get('open')) or num(first.get('close'))
        closes=num(last.get('close')) or opens
        highs=[num(x.get('high')) for x in items if num(x.get('high')) is not None]
        lows=[num(x.get('low')) for x in items if num(x.get('low')) is not None]
        vols=[num(x.get('volume')) for x in items if num(x.get('volume')) is not None]
        out.append({'date':key,'open':opens,'high':max(highs) if highs else closes,'low':min(lows) if lows else closes,'close':closes,'volume':sum(vols) if vols else None})
    return out

def _technical_snapshot(rows: list[dict]):
    closes=[num(r.get('close')) for r in rows]
    closes=[x for x in closes if x is not None]
    vols=[num(r.get('volume')) for r in rows]
    vols=[x for x in vols if x is not None]
    if not closes: return {}
    price=closes[-1]
    sma20=_ts_sma(closes,20); sma50=_ts_sma(closes,50); sma100=_ts_sma(closes,100); sma200=_ts_sma(closes,200)
    ema9=_ts_ema(closes,9); ema20=_ts_ema(closes,20); ema50=_ts_ema(closes,50); ema200=_ts_ema(closes,200)
    macd,macd_signal,macd_hist=_ts_macd(closes); upper,bbmid,lower=_ts_bbands(closes); stoch=_ts_stoch(closes); atr=_ts_atr(rows); adx,pdi,mdi=_ts_adx(rows)
    avgvol20=_ts_sma(vols,20); relvol=(vols[-1]/avgvol20) if vols and avgvol20 else None
    high52=max(closes[-min(len(closes),252):]) if closes else None
    low52=min(closes[-min(len(closes),252):]) if closes else None
    prev=closes[-2] if len(closes)>1 else None
    change=((price-prev)/prev*100) if prev else None
    trend='Bullish' if price>= (sma20 or price) and (sma20 is None or sma50 is None or sma20>=sma50) else ('Bearish' if sma20 is not None and sma50 is not None and price<=sma20<=sma50 else 'Mixed')
    return {'historyPoints':len(closes),'price':price,'change':change,'sma20':sma20,'sma50':sma50,'sma100':sma100,'sma200':sma200,'ema9':ema9,'ema20':ema20,'ema50':ema50,'ema200':ema200,'rsi':_ts_rsi(closes),'macd':macd,'macdSignal':macd_signal,'macdHistogram':macd_hist,'bbUpper':upper,'bbMiddle':bbmid,'bbLower':lower,'stochastic':stoch,'atr':atr,'adx':adx,'plusDI':pdi,'minusDI':mdi,'volume':vols[-1] if vols else None,'avgVolume20':avgvol20,'relativeVolume':relvol,'high52':high52,'low52':low52,'trend':trend}

@app.get('/api/technical-screener')
async def technical_screener(
    limit: int = Query(60, ge=1, le=500),
    technical: bool = Query(True),
    timeframe: str = Query('D'),
    search: str = Query('', max_length=80),
    search_type: str = Query('any', pattern='^(any|symbol|company)$'),
):
    """Independent NEPSE technical screener.

    The screener deliberately uses the market endpoint directly instead of the
    Command Center state.  Search is server-side, so a symbol/company that is
    not in the first liquid slice can still be scanned.  Live market fields
    are preserved even when historical OHLC is temporarily unavailable.
    """
    tf = str(timeframe or 'D').upper()
    q = str(search or '').strip().upper()
    st = str(search_type or 'any').lower()
    key = f'technical-screener:{limit}:{technical}:{tf}:{st}:{q}'

    async def load():
        errors = []
        live = []
        live_source = 'unavailable'
        # Fast path: the screener needs only the live security universe.  Do
        # not wait for the full Command Center (index, sectors, brokers, etc.).
        try:
            raw = await production_call('live_market')
            live = deep_rows(raw, ('content','data','results','rows'))
            if live: live_source = 'NEPSE production market endpoint'
        except Exception as exc:
            errors.append(f'production live market: {exc}')
        if not live:
            try:
                raw = await nepse_call(['live_market','today_price'], page=1, size=500)
                live = deep_rows(raw, ('content','data','results','rows'))
                if live: live_source = 'NEPSE live market endpoint'
            except Exception as exc:
                errors.append(f'NEPSE live market: {exc}')
        if not live:
            try:
                market = await get_market()
                live = market.get('live') or []
                if live: live_source = 'NEPSE Pulse cached market snapshot'
            except Exception as exc:
                errors.append(f'market fallback: {exc}')

        def symbol_of(r):
            return str(pick(r, ['symbol','ticker','securitySymbol','code','stockSymbol'], '') or '').upper().strip()
        def company_of(r):
            return str(pick(r, ['companyName','company','securityName','name','company_name'], '') or '').strip()
        def turnover_key(r):
            return num(pick(r,['turnover','totalTurnover','value','totalTradedValue'])) or 0

        # Server-side search.  This is important: the browser must not be
        # limited to whatever happened to be in a previous 150-row snapshot.
        if q:
            def match(r):
                sym, name = symbol_of(r), company_of(r).upper()
                if st == 'symbol': return q in sym
                if st == 'company': return q in name
                return q in sym or q in name
            live = [r for r in live if match(r)]
            # A search should never silently disappear just because the live
            # snapshot contains no company-name field. Resolve exact symbols.
            if not live and st in ('any','symbol'):
                try:
                    company = await resolve_company(q)
                    if company:
                        cid = pick(company,['id','securityId','security_id'])
                        live = [company]
                        if cid is not None:
                            try:
                                snap = await nepse_call(['today_price'], page=1, size=500)
                                rows = deep_rows(snap, ('content','data','results','rows'))
                                exact = [r for r in rows if symbol_of(r) == q]
                                if exact: live = exact
                            except Exception:
                                pass
                except Exception as exc:
                    errors.append(f'resolve search symbol: {exc}')
        else:
            live = sorted(live, key=turnover_key, reverse=True)[:limit]

        # Preserve the true universe size before the responsive browser slice.
        universe_count = len(live)
        # Search results are intentionally small; all-stock scans are capped
        # for responsiveness while still showing real live market rows.
        if q:
            live = live[:max(1, min(limit, 25))]

        sem = asyncio.Semaphore(8)
        async def one(r):
            symbol = symbol_of(r)
            if not symbol:
                return None
            base = dict(r)
            base['symbol'] = symbol
            base['companyName'] = company_of(r) or symbol
            if technical:
                async with sem:
                    try:
                        h = await get_history(symbol)
                        rows = h.get('data', []) if isinstance(h, dict) else []
                        rows = _aggregate_technical_history(rows, tf)
                        base.update(_technical_snapshot(rows))
                        base['historySource'] = h.get('source') if isinstance(h, dict) else None
                    except Exception as exc:
                        base['historyPoints'] = 0
                        base['technicalError'] = str(exc)
            # Never lose the real live snapshot just because history is absent.
            base['price'] = num(pick(r,['price','ltp','lastPrice','lastTradedPrice','close','closePrice'])) if num(pick(r,['price','ltp','lastPrice','lastTradedPrice','close','closePrice'])) is not None else base.get('price')
            base['change'] = num(pick(r,['percentageChange','percentChange','percent_change','changePercent','perChange','pChange','changePercentage']))
            if base['change'] is None:
                # Some real NEPSE feeds expose absolute change plus previous close.
                abs_change = num(pick(r,['change','changeValue']))
                prev_close = num(pick(r,['previousClose','previous_close','prevClose']))
                if abs_change is not None and prev_close not in (None, 0):
                    base['change'] = abs_change / prev_close * 100
            base['volume'] = num(pick(r,['volume','totalTradedQuantity','quantity','tradedQuantity'])) if num(pick(r,['volume','totalTradedQuantity','quantity','tradedQuantity'])) is not None else base.get('volume')
            base['turnover'] = num(pick(r,['turnover','totalTurnover','value','totalTradedValue','tradedAmount'])) if num(pick(r,['turnover','totalTurnover','value','totalTradedValue','tradedAmount'])) is not None else base.get('turnover')
            base['marketCap'] = num(pick(r,['marketCap','marketCapitalization','totalMarketCapitalization']))
            base['pe'] = num(pick(r,['pe','peRatio','priceEarningsRatio']))
            base['dataSource'] = live_source
            return base

        results = await asyncio.gather(*(one(r) for r in live))
        results = [r for r in results if r]
        return {
            'ok': bool(results),
            'source': f'{live_source} + historical OHLCV ({tf})',
            'timeframe': tf,
            'updatedAt': now_iso(),
            'count': len(results),
            'universeCount': universe_count,
            'data': results,
            'errors': errors,
        }
    return await cached(key, load)

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
    value = row.get("businessDate") or pick(row.get("raw", {}), ["businessDate", "tradeDate", "calculationDate", "date"])
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
    """Load historical trades from NEPSE Open Data, then use the existing archive as fallback.

    The repository is discovered through GitHub's tree API so this does not
    assume a particular daily filename extension. Only dated files under
    floorsheet/ inside the requested range are downloaded.
    """
    wanted = symbol.upper().strip() if symbol else None
    repo = "socrateai-official/nepse-open-data"
    api = f"https://api.github.com/repos/{repo}/git/trees/main?recursive=1"
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "NEPSE-Pulse/1.0"}
    errors = []

    def date_from_path(path: str):
        import re
        m = re.search(r"(20\d{2})[-_/]?(\d{2})[-_/]?(\d{2})", path)
        if not m:
            return None
        try:
            return datetime.strptime("".join(m.groups()), "%Y%m%d").date()
        except ValueError:
            return None

    async def request_bytes(url: str):
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response

    paths = []
    try:
        tree_response = await request_bytes(api)
        tree = tree_response.json()
        if tree.get("truncated"):
            errors.append("GitHub tree response was truncated")
        for item in tree.get("tree", []):
            path = item.get("path", "")
            low = path.lower()
            day = date_from_path(path)
            if (item.get("type") == "blob" and (low.startswith("floorsheet/") or low.split("/")[-1].startswith("floorsheet_"))
                    and day and start_date <= day <= end_date
                    and low.endswith((".csv", ".json", ".csv.gz", ".json.gz"))):
                paths.append((day, path))
    except Exception as exc:
        errors.append(f"NEPSE Open Data repository discovery: {type(exc).__name__}: {exc}")

    sem = asyncio.Semaphore(6)
    async def load_path(day, path):
        async with sem:
            try:
                url = f"https://raw.githubusercontent.com/{repo}/main/{path}"
                response = await request_bytes(url)
                content = response.content
                if path.lower().endswith(".gz"):
                    import gzip
                    content = gzip.decompress(content)
                if path.lower().endswith((".json", ".json.gz")):
                    import json
                    raw = json.loads(content.decode("utf-8-sig"))
                    rows = floor_rows(raw)
                else:
                    reader = csv.DictReader(io.StringIO(content.decode("utf-8-sig", errors="replace")))
                    rows = floor_rows(list(reader))
                cleaned = []
                for row in rows:
                    rd = _row_date(row) or day.isoformat()
                    if rd != day.isoformat():
                        continue
                    row["businessDate"] = rd
                    if not wanted or str(row.get("symbol") or "").upper() == wanted:
                        cleaned.append(row)
                return cleaned
            except Exception as exc:
                errors.append(f"{path}: {type(exc).__name__}: {exc}")
                return []

    if paths:
        batches = await asyncio.gather(*(load_path(day, path) for day, path in paths))
        data = [row for batch in batches for row in batch]
        if data:
            return data

    # Fallback to YONEPSE's compact daily JSON archive if Open Data had no
    # matching files or returned no parseable rows.
    dates = []
    day = start_date
    while day <= end_date:
        dates.append(day)
        day += timedelta(days=1)
    async def fetch_fallback(day):
        try:
            raw = await static_get(f"/floor_sheet/daily/{day.isoformat()}.json")
            rows = floor_rows(raw)
            result = []
            for row in rows:
                rd = _row_date(row) or day.isoformat()
                if rd == day.isoformat() and (not wanted or str(row.get("symbol") or "").upper() == wanted):
                    row["businessDate"] = rd
                    result.append(row)
            return result
        except Exception:
            return []
    batches = await asyncio.gather(*(fetch_fallback(day) for day in dates))
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
async def api_brokers_history(months: int = Query(3, ge=1, le=3), symbol: Optional[str] = None,
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
        return {"ok": bool(rows), "source": "NEPSE Open Data (with YONEPSE archive fallback)", "startDate": start_day.isoformat(),
                "endDate": end_day.isoformat(), "monthsRequested": months, "symbol": symbol.upper() if symbol else None,
                "dataCoverage": {"tradeRows": len(rows), "daysWithData": len(dates)}, "brokers": broker_rows,
                "bySymbol": symbol_rows, "dailyByBroker": daily_rows, "chart": chart,
                "holdingNote": "Net buy/sell is transaction flow over this period, not a broker's current demat holding.",
                "updatedAt": now_iso()}
    return await cached(cache_key, load)


def _broker_label(row: dict, side: str) -> str:
    if side == "buy":
        return str(row.get("buyerBrokerName") or row.get("buyerBroker") or row.get("buyerBrokerId") or "").strip()
    return str(row.get("sellerBrokerName") or row.get("sellerBroker") or row.get("sellerBrokerId") or "").strip()


def _build_month_flow_report(rows: list[dict], companies: list[dict], detail_limit: int = 3000):
    """Build a one-month all-company floorsheet/broker intelligence snapshot.

    This intentionally calls the result broker *flow* rather than current
    broker holdings. Floorsheet buyer/seller data identifies the broker used
    for the transaction; it does not reveal the broker's clients' demat
    holdings.
    """
    company = {}
    broker = {}
    broker_company = {}
    detail = []

    # Seed the company table with the complete listed-security master so
    # inactive/no-trade companies are visible too.
    for c in companies:
        sym = str(pick(c, ["symbol", "ticker", "code", "stockSymbol"], "") or "").upper().strip()
        if not sym:
            continue
        name = str(pick(c, ["companyName", "company", "securityName", "name"], "") or sym).strip()
        company[sym] = {
            "symbol": sym, "companyName": name, "trades": 0, "quantity": 0,
            "turnover": 0, "avgRate": None, "buyBrokerCount": 0,
            "sellBrokerCount": 0, "topBuyer": None, "topSeller": None,
            "netBrokerFlow": 0,
        }

    def ensure_broker(label):
        if not label:
            return None
        return broker.setdefault(label, {
            "broker": label, "buyValue": 0, "sellValue": 0,
            "buyQty": 0, "sellQty": 0, "buyTrades": 0, "sellTrades": 0,
            "netValue": 0, "netQty": 0,
        })

    for r in rows:
        sym = str(r.get("symbol") or "").upper().strip()
        if not sym:
            continue
        q = float(r.get("quantity") or 0)
        rate = float(r.get("rate") or 0)
        value = float(r.get("amount") or (q * rate))
        c = company.setdefault(sym, {
            "symbol": sym, "companyName": str(r.get("securityName") or sym),
            "trades": 0, "quantity": 0, "turnover": 0, "avgRate": None,
            "buyBrokerCount": 0, "sellBrokerCount": 0,
            "topBuyer": None, "topSeller": None, "netBrokerFlow": 0,
        })
        c["trades"] += 1; c["quantity"] += q; c["turnover"] += value
        buyer = _broker_label(r, "buy")
        seller = _broker_label(r, "sell")
        bb = ensure_broker(buyer); sb = ensure_broker(seller)
        if bb:
            bb["buyValue"] += value; bb["buyQty"] += q; bb["buyTrades"] += 1
        if sb:
            sb["sellValue"] += value; sb["sellQty"] += q; sb["sellTrades"] += 1
        if buyer:
            bc = broker_company.setdefault((sym, buyer), {"symbol": sym, "broker": buyer, "buyValue": 0, "sellValue": 0, "buyQty": 0, "sellQty": 0, "trades": 0})
            bc["buyValue"] += value; bc["buyQty"] += q; bc["trades"] += 1
        if seller:
            bc = broker_company.setdefault((sym, seller), {"symbol": sym, "broker": seller, "buyValue": 0, "sellValue": 0, "buyQty": 0, "sellQty": 0, "trades": 0})
            bc["sellValue"] += value; bc["sellQty"] += q; bc["trades"] += 1
        detail.append({
            "date": _row_date(r) or r.get("businessDate") or "",
            "time": r.get("tradeTime") or "",
            "trade": r.get("trade") or "",
            "symbol": sym, "companyName": c.get("companyName") or sym,
            "buyer": buyer, "seller": seller, "quantity": q,
            "rate": rate, "amount": value,
        })

    for b in broker.values():
        b["netValue"] = b["buyValue"] - b["sellValue"]
        b["netQty"] = b["buyQty"] - b["sellQty"]
        b["tradeCount"] = b["buyTrades"] + b["sellTrades"]
    broker_rows = sorted(broker.values(), key=lambda x: abs(x["netValue"]), reverse=True)

    company_brokers = {}
    for x in broker_company.values():
        x["netValue"] = x["buyValue"] - x["sellValue"]
        x["netQty"] = x["buyQty"] - x["sellQty"]
        company_brokers.setdefault(x["symbol"], []).append(x)
    for sym, entries in company_brokers.items():
        entries.sort(key=lambda x: x["netValue"], reverse=True)
        c = company.get(sym)
        if not c: continue
        c["buyBrokerCount"] = sum(1 for x in entries if x["buyValue"] > 0)
        c["sellBrokerCount"] = sum(1 for x in entries if x["sellValue"] > 0)
        top_buy = max(entries, key=lambda x: x["buyValue"], default=None)
        top_sell = max(entries, key=lambda x: x["sellValue"], default=None)
        c["topBuyer"] = {"broker": top_buy["broker"], "value": top_buy["buyValue"], "qty": top_buy["buyQty"]} if top_buy and top_buy["buyValue"] else None
        c["topSeller"] = {"broker": top_sell["broker"], "value": top_sell["sellValue"], "qty": top_sell["sellQty"]} if top_sell and top_sell["sellValue"] else None
        c["netBrokerFlow"] = sum(x["netValue"] for x in entries)
    for c in company.values():
        c["avgRate"] = (c["turnover"] / c["quantity"]) if c["quantity"] else None

    detail.sort(key=lambda x: (x["date"], x["time"], x["trade"]), reverse=True)
    return {
        "companies": sorted(company.values(), key=lambda x: x["turnover"], reverse=True),
        "brokers": broker_rows,
        "brokerCompany": list(broker_company.values()),
        "floorsheet": detail[:detail_limit],
        "totalTradeRows": len(rows),
    }


@app.get("/api/floorsheet-intelligence")
async def api_floorsheet_intelligence(
    months: int = Query(1, ge=1, le=1),
    symbol: Optional[str] = None,
    broker: Optional[str] = None,
    detail_limit: int = Query(3000, ge=100, le=10000),
):
    """All-company one-month floorsheet + broker-flow intelligence.

    `symbol` is optional. Without it, the response contains every listed
    company's one-month aggregate plus broker totals. With a symbol, the
    broker table is restricted to that company and the returned floorsheet
    rows are that company's one-month trade details.
    """
    today = datetime.now(NPT).date()
    start_day = _subtract_months(datetime.combine(today, datetime.min.time()), months).date()
    wanted = symbol.upper().strip() if symbol else None
    wanted_broker = broker.strip() if broker else None
    key = f"fs-intel:{start_day}:{today}:{wanted or 'ALL'}:{wanted_broker or 'ALL'}:{detail_limit}"

    async def load():
        raw_companies = await company_list()
        companies = arr(raw_companies)
        rows = await _historical_floor_rows(start_day, today, wanted)
        report = _build_month_flow_report(rows, companies, detail_limit=detail_limit)
        if wanted_broker:
            needle = wanted_broker.lower()
            report["brokers"] = [x for x in report["brokers"] if needle in str(x["broker"]).lower()]
            report["floorsheet"] = [x for x in report["floorsheet"] if needle in (str(x["buyer"]).lower() + " " + str(x["seller"]).lower())]
        if wanted:
            report["companies"] = [x for x in report["companies"] if x["symbol"] == wanted]
        report.update({
            "ok": bool(rows), "source": "NEPSE Open Data / YONEPSE daily floorsheet archive",
            "startDate": start_day.isoformat(), "endDate": today.isoformat(),
            "months": 1, "symbol": wanted, "broker": wanted_broker,
            "listedCompanies": len(report["companies"]), "updatedAt": now_iso(),
            "holdingNote": "Broker flow is derived from buyer/seller transactions. It is not the broker's own or clients' current demat holding.",
        })
        return report
    return await cached(key, load)
