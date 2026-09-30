import asyncio
import time
import csv
import io
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

APP_VERSION = "V26-REAL-FEATURE-DATA-ENGINE-3"
PUBLIC_API = "https://nepseapi.surajrimal.dev"
STATIC_API = "https://shubhamnpk.github.io/yonepse/data"
OPEN_DATA = "https://raw.githubusercontent.com/socrateai-official/nepse-open-data/main"
CACHE_TTL = {
    "market": 25,
    "index": 25,
    "floorsheet": 35,
    "company": 300,
    "history": 75,
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
    for x in deep_rows(m.get("summary") if isinstance(m,dict) else {}):
        label=str(pick(x,["detail","label","name","title"],"" )).lower()
        val=num(pick(x,["value","amount","total","turnover","totalTurnover","volume","transactions","scripsTraded"]))
        if 'turnover' in label: summary['turnover']=val
        elif 'traded shares' in label or 'volume' in label: summary['volume']=val
        elif 'transactions' in label or 'trade count' in label: summary['transactions']=val
        elif 'scrips' in label or 'securities' in label: summary['scripsTraded']=val
    # Some market_summary versions are a single object rather than rows.
    ms=m.get("summary") if isinstance(m,dict) else {}
    if isinstance(ms,dict):
        summary.setdefault('turnover', num(pick(ms,["turnover","totalTurnover","totalTradeValue"])))
        summary.setdefault('volume', num(pick(ms,["volume","totalTradedQuantity","totalTradeQuantity","tradedShares"])))
        summary.setdefault('transactions', num(pick(ms,["transactions","totalTransactions","totalTrades"])))
        summary.setdefault('scripsTraded', num(pick(ms,["scripsTraded","totalScrips","totalSecuritiesTraded"])))

    live=deep_rows(m.get('live') if isinstance(m,dict) else {})
    # get_market normally fills live with today_price when live_market is empty.
    # If a provider still returns no rows, make one direct, paginated attempt here.
    if not live:
        try:
            live=deep_rows(await nepse_call(["today_price"], page=1, size=500))
        except Exception as e:
            errors.append(f"breadth today_price: {e}")

    def row_change_pct(x):
        p=num(pick(x,["perChange","percentageChange","percentChange","changePercent","pChange","changePercentage"]))
        if p is not None:
            return p
        change=num(pick(x,["change","pointChange","difference"]))
        prev=num(pick(x,["previousClose","previousPrice","prevClose","previousLtp","previousLtpPrice"]))
        if change is not None and prev not in (None,0):
            return (change/prev)*100
        ltp=num(pick(x,["lastTradedPrice","lastPrice","ltp","LTP","closePrice","price"]))
        if ltp is not None and prev not in (None,0):
            return ((ltp-prev)/prev)*100
        return None

    breadth={"advancing":0,"declining":0,"unchanged":0}
    counted=0
    for x in live:
        p=row_change_pct(x)
        if p is None:
            continue
        counted += 1
        if p>0: breadth['advancing']+=1
        elif p<0: breadth['declining']+=1
        else: breadth['unchanged']+=1

    # If full live rows are unavailable, use provider summary breadth when it
    # exists. Do not fabricate unchanged counts from a capped 50-row mover list.
    if counted == 0:
        ms=m.get("summary") if isinstance(m,dict) else {}
        breadth["advancing"] = int(num(pick(ms,["advancing","advancers","advance"])) or 0) if isinstance(ms,dict) else 0
        breadth["declining"] = int(num(pick(ms,["declining","decliners","decline"])) or 0) if isinstance(ms,dict) else 0
        breadth["unchanged"] = int(num(pick(ms,["unchanged","unchangedCount"])) or 0) if isinstance(ms,dict) else 0

    def clean(xs): return deep_rows(xs)[:50]

    # Build a symbol -> live row index so top lists can be enriched with the
    # actual volume/value/transaction fields when their endpoint omits them.
    live_by_symbol={}
    for row in live:
        sym=pick(row,["symbol","ticker","securitySymbol","stockSymbol"])
        if sym: live_by_symbol[str(sym).upper()]=row

    def first_num(row, keys):
        for key in keys:
            n=num(pick(row,[key]))
            if n is not None:
                return n
        return None

    def activity_rows(xs, kind):
        source=clean(xs)
        out=[]
        for row in source:
            if not isinstance(row,dict): continue
            symbol=pick(row,["symbol","ticker","securitySymbol","stockSymbol"])
            live_row=live_by_symbol.get(str(symbol).upper()) if symbol else None
            merged={}
            if isinstance(live_row,dict): merged.update(live_row)
            merged.update(row)
            quantity=first_num(merged,["sharesTraded","shareTraded","totalTradeQuantity","totalTradedQuantity","tradeQuantity","quantity","volume","tradedShares"])
            value=first_num(merged,["turnover","totalTurnover","totalTradeValue","totalTradedValue","tradedValue","amount","value","totalAmount"])
            transactions=first_num(merged,["transactions","totalTransactions","totalTrades","transactionCount","trades"])
            item=dict(row)
            if symbol is not None: item["symbol"]=symbol
            if quantity is not None:
                item.update({"quantity":quantity,"volume":quantity,"sharesTraded":quantity})
            if value is not None:
                item.update({"value":value,"turnover":value,"totalTradeValue":value})
            if transactions is not None:
                item.update({"transactions":transactions,"totalTransactions":transactions,"totalTrades":transactions})
            out.append(item)
        # If the dedicated volume endpoint returned rows without usable volume,
        # derive Top Volume from the full live snapshot instead.
        if kind=="volume" and (not out or sum(1 for r in out if num(r.get("volume")) not in (None,0))<min(5,len(out))):
            derived=[]
            for row in live:
                q=first_num(row,["sharesTraded","shareTraded","totalTradeQuantity","totalTradedQuantity","tradeQuantity","quantity","volume","tradedShares"])
                if q is None: continue
                item=dict(row)
                sym=pick(row,["symbol","ticker","securitySymbol","stockSymbol"])
                val=first_num(row,["turnover","totalTurnover","totalTradeValue","totalTradedValue","tradedValue","amount","value","totalAmount"])
                if sym is not None: item["symbol"]=sym
                item["quantity"]=q; item["volume"]=q; item["sharesTraded"]=q
                if val is not None: item["value"]=val; item["turnover"]=val; item["totalTradeValue"]=val
                derived.append(item)
            derived.sort(key=lambda r:num(r.get("volume")) or 0, reverse=True)
            if derived: return derived[:50]
        return out

    turnover_rows=activity_rows(results.get('turnover') or m.get('topTurnover'), 'turnover')
    volume_rows=activity_rows(results.get('volume') or m.get('topTraded'), 'volume')
    transaction_rows=activity_rows(results.get('transactions') or m.get('topTransactions'), 'transactions')
    gainers_rows=clean(results.get('gainers')) or clean(m.get('gainers'))
    losers_rows=clean(results.get('losers')) or clean(m.get('losers'))

    # Sector snapshot: prefer real sub-index rows. If unavailable, enrich live
    # rows with company sector metadata and aggregate a verified snapshot from
    # those constituent rows.
    sector_payload=results.get('sectors') or {}
    sector_rows=deep_rows(sector_payload, ("data","content","sectorIndices","subIndices"))
    companies=deep_rows(m.get("companies") if isinstance(m,dict) else {})
    company_by_symbol={}
    for c in companies:
        sym=pick(c,["symbol","ticker","securitySymbol","stockSymbol"])
        if sym: company_by_symbol[str(sym).upper()]=c
    sector_groups={}
    for row in live:
        sym=pick(row,["symbol","ticker","securitySymbol","stockSymbol"])
        c=company_by_symbol.get(str(sym).upper(),{}) if sym else {}
        sec=pick(row,["sector","sectorName","industry"]) or pick(c,["sector","sectorName","sectorNameEnglish","industry","indexName"])
        if not sec: continue
        key=str(sec).strip(); sector_groups.setdefault(key,[]).append(row)

    normalized_sectors=[]
    for x in sector_rows:
        name=pick(x,["indexName","sectorName","sector","name","index","symbol"],"—")
        normalized_sectors.append({
            **x, "sector":name, "name":name,
            "change":num(pick(x,["pointChange","difference","change"])),
            "changePercent":num(pick(x,["perChange","percentageChange","percentChange","changePercent"])),
            "turnover":num(pick(x,["turnover","totalTurnover","totalTradeValue","totalTradedValue"])),
            "volume":num(pick(x,["volume","totalTradedQuantity","tradedShares","sharesTraded"])),
        })
    if not normalized_sectors:
        for name, rows in sector_groups.items():
            changes=[row_change_pct(r) for r in rows if row_change_pct(r) is not None]
            turnover=sum(first_num(r,["turnover","totalTurnover","totalTradeValue","totalTradedValue","tradedValue","amount","value"]) or 0 for r in rows)
            volume=sum(first_num(r,["sharesTraded","totalTradeQuantity","totalTradedQuantity","tradeQuantity","quantity","volume"]) or 0 for r in rows)
            normalized_sectors.append({
                "sector":name,"name":name,
                "change":sum(changes)/len(changes) if changes else None,
                "changePercent":sum(changes)/len(changes) if changes else None,
                "turnover":turnover,"volume":volume,"stocks":len(rows),
            })
    else:
        # Attach constituent counts where available.
        for s in normalized_sectors:
            name=str(s.get("name") or s.get("sector") or "").lower()
            match=next((rows for key,rows in sector_groups.items() if key.lower()==name),[])
            if match:
                s["stocks"]=len(match)

    broker_payload=results.get('brokers') or {}
    broker_rows=deep_rows(broker_payload, ("data","content"))

    return {"ok":True,"updatedAt":now_iso(),"summary":summary,"breadth":breadth,"nepse":idx,"movers":{"gainers":gainers_rows,"losers":losers_rows},"activity":{"turnover":turnover_rows,"volume":volume_rows,"transactions":transaction_rows},"sectors":normalized_sectors,"brokers":broker_rows,"counts":{"live":len(live),"gainers":len(gainers_rows),"losers":len(losers_rows),"sectors":len(normalized_sectors)},"diagnostics":{"errors":errors,"marketSource":m.get('source') if isinstance(m,dict) else None,"breadthRows":counted}}

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
