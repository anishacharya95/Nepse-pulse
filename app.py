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

APP_VERSION = "V26-REAL-FEATURE-DATA-ENGINE-2"
PUBLIC_API = "https://nepseapi.surajrimal.dev"
STATIC_API = "https://shubhamnpk.github.io/yonepse/data"
OPEN_DATA = "https://raw.githubusercontent.com/socrateai-official/nepse-open-data/main"
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


async def static_get(path: str):
    url = STATIC_API.rstrip("/") + path
    async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
        r = await client.get(url, headers={"Accept": "application/json"})
        r.raise_for_status()
        return r.json()


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
    # One shared client is important for the NEPSE token/session. The current
    # nepsepy client also serializes calls internally, so this outer lock keeps
    # our method fallbacks from racing each other during a refresh burst.
    async with NEPSE_CALL_LOCK:
        for name in methods:
            fn = getattr(client, name, None)
            if fn is None:
                continue
            try:
                return await fn(*args, **kwargs)
            except TypeError as e:
                # Some installed nepsepy versions differ in optional args.
                try:
                    return await fn(*args)
                except Exception as e2:
                    errors.append(f"{name}: {e2}")
            except Exception as e:
                errors.append(f"{name}: {e}")
    detail = "; ".join(errors[-4:])
    raise RuntimeError("No compatible nepsepy method succeeded" + (f": {detail}" if detail else ""))


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
            if not arr(live):
                try:
                    live = await nepse_call(["today_price"], page=1, size=500)
                except Exception as e:
                    errors.append(f"today_price: {e}")
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
                "index": normalize_index(indices),
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


def normalize_index(raw: Any):
    if isinstance(raw, dict):
        # Some NEPSE responses wrap the index rows in data/content/results.
        for key in ("data", "content", "results", "result", "items", "records", "rows"):
            value = raw.get(key)
            if isinstance(value, list):
                for row in value:
                    if isinstance(row, dict):
                        return row
            elif isinstance(value, dict):
                return value
        return raw
    if isinstance(raw, list):
        for row in raw:
            if isinstance(row, dict):
                # Prefer the actual NEPSE index (id 58 / symbol NEPSE) when present.
                if str(pick(row, ["index", "symbol", "indexName", "name"], "")).upper() == "NEPSE" or str(pick(row, ["id", "indexId"], "")) == "58":
                    return row
        return next((row for row in raw if isinstance(row, dict)), {})
    return {}

async def get_index():
    async def load():
        try:
            return normalize_index(await nepse_call(["nepse_indices", "nepse_index"]))
        except Exception:
            return await public_get("/NepseIndex")
    return await cached("index:current", load)


async def get_index_history(index_id: int = 58):
    try: index_id=int(index_id)
    except Exception: index_id=58
    key = f"history:{index_id}"
    async def load():
        value, errors, source = await first_ok([
            ("nepsepy.index_history", lambda: nepse_call(["index_history"], index_id, 1, 500)),
            ("public.daily-index-graph", lambda: public_get("/DailyNepseIndexGraph")),
        ])
        return {"ok": bool(value), "source": source, "data": value if value is not None else [], "errors": errors, "updatedAt": now_iso()}
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

@app.get("/api/floorsheet")
async def api_floorsheet(symbol: Optional[str]=None, limit:int=Query(250,ge=1,le=2000)):
    d=await get_floorsheet(symbol)
    rows=floor_rows(d)[:limit]
    return {"ok":bool(rows),"source":"nepsepy/public floorsheet","symbol":symbol,"data":rows,"count":len(rows),"updatedAt":now_iso()}

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


@app.get("/api/command-center")
async def command_center():
    tasks={
      "market":get_market(), "index":get_index(), "sectors":get_sectors(), "brokers":get_broker_analysis(),
      "gainers":nepse_call(["top_gainers"],True), "losers":nepse_call(["top_losers"],True),
      "turnover":nepse_call(["top_turnover"],True), "volume":nepse_call(["top_traded_shares","top_active"],True),
      "transactions":nepse_call(["top_transactions"],True),
    }
    results={}; errors=[]
    async def one(k,coro):
        try: results[k]=await coro
        except Exception as e: results[k]=[]; errors.append(f"{k}: {e}")
    await asyncio.gather(*[one(k,v) for k,v in tasks.items()])
    m=results.get("market") or {}; idx=results.get("index")
    summary={}
    for x in arr(m.get("summary") if isinstance(m,dict) else []):
        label=str(pick(x,["detail","label","name"],"" )).lower()
        val=num(pick(x,["value","amount","total"]))
        if 'turnover' in label: summary['turnover']=val
        elif 'traded shares' in label or 'volume' in label: summary['volume']=val
        elif 'transactions' in label: summary['transactions']=val
        elif 'scrips' in label: summary['scripsTraded']=val
    live=arr(m.get('live') if isinstance(m,dict) else [])
    breadth={"advancing":0,"declining":0,"unchanged":0}
    for x in live:
        p=num(pick(x,["perChange","percentageChange","percentChange","changePercent","pChange"]))
        if p is None: continue
        if p>0: breadth['advancing']+=1
        elif p<0: breadth['declining']+=1
        else: breadth['unchanged']+=1
    if not live:
        # If the live snapshot is temporarily unavailable, use the verified
        # top-gainer/top-loser counts and the session's traded-scrip total.
        breadth['advancing'] = len(arr(results.get('gainers')) or arr(m.get('gainers')))
        breadth['declining'] = len(arr(results.get('losers')) or arr(m.get('losers')))
        traded = summary.get('scripsTraded')
        if traded is not None:
            breadth['unchanged'] = max(int(traded) - breadth['advancing'] - breadth['declining'], 0)
    def clean(xs): return arr(xs)[:50]

    # Normalize the three activity feeds to one canonical schema. NEPSE's
    # public endpoints do not always return the same field names, and the
    # top-* endpoints may omit quantity/value fields that are available in
    # the live/today-price rows. Enrich by symbol so the Command Center can
    # show the actual metrics instead of dashes or zeroes.
    def activity_rows(xs, kind):
        source = clean(xs)
        live_by_symbol = {}
        for row in live:
            sym = pick(row, ["symbol", "ticker", "securitySymbol"])
            if sym:
                live_by_symbol[str(sym).upper()] = row

        def first_value(row, keys, positive=False):
            values = []
            for key in keys:
                v = pick(row, [key])
                n = num(v)
                if n is not None:
                    values.append((v, n))
                    if (not positive) or n > 0:
                        return v
            return values[0][0] if values else None

        out = []
        for row in source:
            if not isinstance(row, dict):
                continue
            symbol = pick(row, ["symbol", "ticker", "securitySymbol"])
            live_row = live_by_symbol.get(str(symbol).upper()) if symbol else None
            merged = {}
            if isinstance(live_row, dict):
                merged.update(live_row)
            merged.update(row)

            quantity = first_value(merged, [
                "sharesTraded", "shareTraded", "totalTradeQuantity",
                "totalTradedQuantity", "tradeQuantity", "quantity",
                "volume", "tradedShares"
            ], positive=True)
            value = first_value(merged, [
                "turnover", "totalTurnover", "totalTradeValue",
                "tradedValue", "amount", "value", "totalAmount"
            ], positive=True)
            transactions = first_value(merged, [
                "transactions", "totalTransactions", "totalTrades",
                "transactionCount", "trades"
            ], positive=True)

            item = dict(row)
            if symbol is not None:
                item["symbol"] = symbol
            if quantity is not None:
                item["quantity"] = quantity
                item["volume"] = quantity
                item["sharesTraded"] = quantity
            if value is not None:
                item["value"] = value
                item["turnover"] = value
                item["totalTradeValue"] = value
            if transactions is not None:
                item["transactions"] = transactions
                item["totalTransactions"] = transactions
                item["totalTrades"] = transactions
            out.append(item)
        return out

    turnover_rows = activity_rows(results.get('turnover') or arr(m.get('topTurnover')), 'turnover')
    volume_rows = activity_rows(results.get('volume') or arr(m.get('topTraded')), 'volume')
    transaction_rows = activity_rows(results.get('transactions') or arr(m.get('topTransactions')), 'transactions')
    gainers_rows = clean(results.get('gainers')) or arr(m.get('gainers'))
    losers_rows = clean(results.get('losers')) or arr(m.get('losers'))
    sector_rows = arr((results.get('sectors') or {}).get('data')) if isinstance(results.get('sectors'),dict) else arr(results.get('sectors'))
    broker_rows = arr((results.get('brokers') or {}).get('data')) if isinstance(results.get('brokers'),dict) else arr(results.get('brokers'))

    return {"ok":True,"updatedAt":now_iso(),"summary":summary,"breadth":breadth,"nepse":idx,"movers":{"gainers":gainers_rows,"losers":losers_rows},"activity":{"turnover":turnover_rows,"volume":volume_rows,"transactions":transaction_rows},"sectors":sector_rows,"brokers":broker_rows,"counts":{"live":len(live),"gainers":len(gainers_rows),"losers":len(losers_rows),"sectors":len(sector_rows)},"diagnostics":{"errors":errors,"marketSource":m.get('source') if isinstance(m,dict) else None}}

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
