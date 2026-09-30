"""
NEPSE Pulse - Production NEPSE Data SDK
========================================

A production-oriented, read-only data layer on top of the public `nepsepy`
client.  It is designed for FastAPI/Render backends and chart frontends.

Covers the public-data areas exposed by current nepsepy releases:
market/status, prices, live market, top-ten lists, floorsheet/trades,
broker analytics, company/security directories, profiles, financial reports,
dividends, corporate actions, AGM/news, depth, supply-demand, indices,
historical/chart data, notices/disclosures/holidays/reports/events and
TradingView-UDF-shaped chart responses.

Important:
- This package is READ ONLY. It does not place orders or access portfolios.
- TradingView methods are UDF-compatible response adapters for your own
  frontend; they are not TradingView's private/internal API.
- NEPSE data can be delayed/corrected by the exchange.
- Requires Python 3.10+ and nepsepy 1.0.2+.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timezone
import inspect
import math
import threading
import time
from typing import Any, Callable, Iterable, Mapping

from nepsepy import NepseClient

try:
    from nepsepy import RateLimitedError  # type: ignore
except Exception:  # pragma: no cover
    RateLimitedError = Exception  # type: ignore


# ---------------------------------------------------------------------------
# Normalisation helpers
# ---------------------------------------------------------------------------

def num(value: Any, default: float = 0.0) -> float:
    try:
        if value in (None, ""):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def integer(value: Any, default: int = 0) -> int:
    try:
        if value in (None, ""):
            return default
        return int(float(value))
    except (TypeError, ValueError):
        return default


def first(row: Mapping[str, Any] | None, *keys: str, default: Any = None) -> Any:
    if not isinstance(row, Mapping):
        return default
    for key in keys:
        value = row.get(key)
        if value not in (None, ""):
            return value
    return default


def rows(payload: Any) -> list[dict[str, Any]]:
    """Extract list records from common NEPSE pagination/envelope shapes."""
    if isinstance(payload, list):
        return [x for x in payload if isinstance(x, dict)]
    if not isinstance(payload, Mapping):
        return []
    for key in (
        "content", "data", "results", "items", "records", "rows",
        "floorsheets", "floorSheets", "securities", "companies",
    ):
        value = payload.get(key)
        if isinstance(value, list):
            return [x for x in value if isinstance(x, dict)]
    return []


def total_pages(payload: Any) -> int:
    if not isinstance(payload, Mapping):
        return 1
    return max(1, integer(first(
        payload, "totalPages", "total_pages", "pages", "pageCount", default=1
    )))


def total_elements(payload: Any) -> int:
    if not isinstance(payload, Mapping):
        return 0
    return integer(first(
        payload, "totalElements", "total_elements", "totalTrades", "total", "count", default=0
    ))


def timestamp(value: Any) -> int | None:
    if value in (None, ""):
        return None
    if isinstance(value, (int, float)):
        value = float(value)
        if value > 10_000_000_000:
            value /= 1000
        return int(value)
    text = str(value).strip()
    try:
        value = float(text)
        if value > 10_000_000_000:
            value /= 1000
        return int(value)
    except ValueError:
        pass
    candidates = (
        "%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%m/%d/%Y",
    )
    for fmt in candidates:
        try:
            parsed = datetime.strptime(text[:40], fmt)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return int(parsed.timestamp())
        except ValueError:
            continue
    return None


def iso_date(value: Any = None) -> str:
    if value is None:
        return date.today().isoformat()
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return str(value)


def safe_key(value: Any) -> str:
    return str(value).strip().upper()


@dataclass
class CacheItem:
    expires_at: float
    value: Any


class NEPSE:
    """High-level, cached and pagination-aware NEPSE read-only client."""

    # Public nepsepy method names.  The generic dispatcher below means newer
    # nepsepy methods can still be reached without waiting for this wrapper to
    # be updated.
    METHOD_ALIASES = {
        "market_status": ("market_status",),
        "market_summary": ("market_summary", "summary"),
        "live_market": ("live_market",),
        "ticker": ("ticker",),
        "today_price": ("today_price",),
        "top_gainers": ("top_gainers",),
        "top_losers": ("top_losers",),
        "top_turnover": ("top_turnover",),
        "top_trade": ("top_trade",),
        "top_transaction": ("top_transaction",),
        "floorsheets": ("floorsheets", "floor_sheet"),
        "trade_history": ("trade_history",),
        "depth": ("depth", "market_depth"),
        "supply_demand": ("supply_demand",),
        "companies": ("companies",),
        "securities": ("securities",),
        "brokers": ("brokers",),
        "dealers": ("dealers",),
        "sectors": ("sectors",),
        "share_groups": ("share_groups",),
        "promoters": ("promoters",),
        "security_profile": ("security_profile",),
        "financial_reports": ("financial_reports",),
        "dividends": ("dividends",),
        "corporate_actions": ("corporate_actions",),
        "agm": ("agm",),
        "news": ("news", "company_news"),
        "company_news": ("company_news", "news"),
        "notices": ("notices",),
        "disclosures": ("disclosures",),
        "holidays": ("holidays",),
        "reports": ("reports",),
        "events": ("events",),
        "csv": ("csv",),
        "downloads": ("downloads",),
        "indices": ("indices", "index"),
        "index_history": ("index_history", "indices_history"),
        "price_history": ("price_history", "security_history", "history", "chart", "company_chart"),
        "market_chart": ("market_chart",),
    }

    CACHE_TTLS = {
        "market_status": 5,
        "live_market": 5,
        "ticker": 5,
        "market_summary": 10,
        "top": 10,
        "directory": 3600,
        "profile": 1800,
        "history": 300,
        "fundamentals": 900,
        "floorsheet": 30,
    }

    def __init__(
        self,
        cache_ttl: int = 30,
        *,
        client: Any | None = None,
        transient_retries: int = 2,
        retry_backoff: float = 0.8,
    ) -> None:
        self.client = client or NepseClient()
        self.cache_ttl = max(0, int(cache_ttl))
        self.transient_retries = max(0, int(transient_retries))
        self.retry_backoff = max(0.0, float(retry_backoff))
        self._cache: dict[str, CacheItem] = {}
        self._lock = threading.RLock()

    # ---------------- lifecycle/cache ----------------

    def close(self) -> None:
        close = getattr(self.client, "close", None)
        if callable(close):
            close()

    def __enter__(self) -> "NEPSE":
        return self

    def __exit__(self, *_exc: Any) -> None:
        self.close()

    def clear_cache(self, prefix: str | None = None) -> None:
        with self._lock:
            if prefix is None:
                self._cache.clear()
            else:
                self._cache = {k: v for k, v in self._cache.items() if not k.startswith(prefix)}

    def cache_info(self) -> dict[str, Any]:
        now = time.time()
        with self._lock:
            active = sum(item.expires_at > now for item in self._cache.values())
            return {"entries": len(self._cache), "active": active}

    def _cached(self, key: str, fn: Callable[[], Any], ttl: int | None = None) -> Any:
        now = time.time()
        with self._lock:
            item = self._cache.get(key)
            if item and item.expires_at > now:
                return item.value
        value = fn()
        lifetime = self.cache_ttl if ttl is None else max(0, ttl)
        with self._lock:
            self._cache[key] = CacheItem(time.time() + lifetime, value)
        return value

    # ---------------- generic nepsepy dispatch ----------------

    def available_methods(self) -> list[str]:
        """List public callable methods currently exposed by installed nepsepy."""
        return sorted(
            name for name in dir(self.client)
            if not name.startswith("_") and callable(getattr(self.client, name, None))
        )

    def _method(self, *names: str) -> tuple[str, Callable[..., Any]]:
        for name in names:
            fn = getattr(self.client, name, None)
            if callable(fn):
                return name, fn
        raise AttributeError(
            "Installed nepsepy does not expose any of: " + ", ".join(names)
        )

    @staticmethod
    def _filtered_kwargs(fn: Callable[..., Any], kwargs: dict[str, Any]) -> dict[str, Any]:
        """Remove unsupported kwargs for older/newer nepsepy method signatures."""
        try:
            sig = inspect.signature(fn)
        except (TypeError, ValueError):
            return kwargs
        if any(p.kind == inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values()):
            return kwargs
        allowed = set(sig.parameters)
        return {k: v for k, v in kwargs.items() if k in allowed and v is not None}

    def call(self, method: str, **kwargs: Any) -> Any:
        """Call a public nepsepy method by name, with alias resolution.

        This is the escape hatch that keeps the SDK useful as nepsepy grows.
        """
        names = self.METHOD_ALIASES.get(method, (method,))
        name, fn = self._method(*names)
        filtered = self._filtered_kwargs(fn, kwargs)
        last: Exception | None = None
        attempts = 0
        while True:
            attempts += 1
            try:
                return fn(**filtered)
            except TypeError as exc:
                last = exc
                # Common nepsepy APIs accept a numeric security ID positionally.
                sid = kwargs.get("stock_id", kwargs.get("security_id"))
                if sid is not None:
                    try:
                        return fn(sid)
                    except Exception as positional_exc:
                        last = positional_exc
                raise
            except RateLimitedError:
                # Do not retry 429 loops.  The upstream package explicitly
                # surfaces rate limiting; callers should back off at the app layer.
                raise
            except (TimeoutError, ConnectionError, OSError) as exc:
                last = exc
                if attempts > self.transient_retries:
                    raise
                time.sleep(self.retry_backoff * (2 ** (attempts - 1)))
            except Exception as exc:
                last = exc
                # Unknown server/API errors are not blindly retried.
                raise
        if last:  # pragma: no cover
            raise last

    def raw(self, method: str, **kwargs: Any) -> Any:
        """Alias for call(), useful when exposing a thin FastAPI proxy."""
        return self.call(method, **kwargs)

    # ---------------- market ----------------

    def market_status(self) -> Any:
        return self._cached("market_status", lambda: self.call("market_status"), 5)

    def market_summary(self) -> Any:
        return self._cached("market_summary", lambda: self.call("market_summary"), 10)

    def live_market(self) -> Any:
        return self._cached("live_market", lambda: self.call("live_market"), 5)

    def ticker(self) -> Any:
        return self._cached("ticker", lambda: self.call("ticker"), 5)

    def today_prices(self, page: int = 1, size: int = 100) -> Any:
        return self.call("today_price", page=max(1, page), size=max(1, size))

    def all_today_prices(self, size: int = 500, max_pages: int = 1000) -> list[dict[str, Any]]:
        first_page = self.today_prices(1, size)
        result = rows(first_page)
        for page in range(2, min(total_pages(first_page), max_pages) + 1):
            result.extend(rows(self.today_prices(page, size)))
        return result

    def _top(self, method: str, limit: int | None = 10) -> Any:
        if limit is None:
            return self.call(method)
        return self.call(method, limit=limit, size=limit)

    def top_gainers(self, limit: int | None = 10) -> Any:
        return self._cached(f"top:gainers:{limit}", lambda: self._top("top_gainers", limit), 10)

    def top_losers(self, limit: int | None = 10) -> Any:
        return self._cached(f"top:losers:{limit}", lambda: self._top("top_losers", limit), 10)

    def top_turnover(self, limit: int | None = 10) -> Any:
        return self._cached(f"top:turnover:{limit}", lambda: self._top("top_turnover", limit), 10)

    def top_trade(self, limit: int | None = 10) -> Any:
        return self._cached(f"top:trade:{limit}", lambda: self._top("top_trade", limit), 10)

    def top_transaction(self, limit: int | None = 10) -> Any:
        return self._cached(f"top:transaction:{limit}", lambda: self._top("top_transaction", limit), 10)

    # ---------------- pagination ----------------

    def paginate(
        self,
        method: str,
        *,
        page_size: int = 500,
        max_pages: int = 1000,
        start_page: int = 1,
        **kwargs: Any,
    ) -> dict[str, Any]:
        """Exhaust a standard NEPSE paginated endpoint safely."""
        start_page = max(1, int(start_page))
        page_size = max(1, int(page_size))
        max_pages = max(1, int(max_pages))
        first_page = self.call(method, page=start_page, size=page_size, **kwargs)
        all_rows = rows(first_page)
        declared_pages = total_pages(first_page)
        final_page = min(declared_pages, start_page + max_pages - 1)
        for page in range(start_page + 1, final_page + 1):
            payload = self.call(method, page=page, size=page_size, **kwargs)
            page_rows = rows(payload)
            all_rows.extend(page_rows)
            # Protect against broken pagination metadata returning the same page.
            if not page_rows and page >= declared_pages:
                break
        return {
            "content": all_rows,
            "totalPages": declared_pages,
            "totalElements": total_elements(first_page) or len(all_rows),
            "pageSize": page_size,
        }

    # ---------------- floorsheet / trades ----------------

    def floorsheet_page(self, page: int = 1, size: int = 500, stock_id: int | None = None) -> Any:
        kwargs: dict[str, Any] = {"page": max(1, page), "size": max(1, size)}
        if stock_id is not None:
            kwargs["stock_id"] = stock_id
        return self.call("floorsheets", **kwargs)

    def floorsheet(
        self,
        size: int = 500,
        max_pages: int = 1000,
        stock_id: int | None = None,
        *,
        cache: bool = True,
    ) -> dict[str, Any]:
        """Fetch the complete currently available floorsheet, not just page 1."""
        key = f"floorsheet:{size}:{max_pages}:{stock_id or 0}"
        loader = lambda: self.paginate(
            "floorsheets", page_size=size, max_pages=max_pages, stock_id=stock_id
        )
        return self._cached(key, loader, self.CACHE_TTLS["floorsheet"]) if cache else loader()

    @staticmethod
    def normalize_trade(trade: Mapping[str, Any]) -> dict[str, Any]:
        quantity = integer(first(
            trade, "contractQuantity", "quantity", "tradeQuantity",
            "sharesTraded", "totalTradeQuantity", "qty"
        ))
        rate = num(first(trade, "contractRate", "rate", "price", "tradePrice"))
        amount = num(first(
            trade, "amount", "totalAmount", "totalTradeValue", "value", "tradeAmount"
        ), quantity * rate)
        return {
            "contract_no": first(trade, "contractId", "contractNumber", "transactionNumber", "tradeId", "id"),
            "symbol": first(trade, "symbol", "stockSymbol", "securitySymbol"),
            "buyer": first(trade, "buyerMemberId", "buyerBroker", "buyer", "buyerMember"),
            "seller": first(trade, "sellerMemberId", "sellerBroker", "seller", "sellerMember"),
            "quantity": quantity,
            "rate": rate,
            "amount": amount,
            "trade_time": first(trade, "tradeTime", "businessDate", "tradeDate", "date", "timestamp"),
            "raw": dict(trade),
        }

    def trades(self, **kwargs: Any) -> list[dict[str, Any]]:
        return [self.normalize_trade(item) for item in rows(self.floorsheet(**kwargs))]

    def floorsheet_summary(self, trades: Iterable[Mapping[str, Any]] | None = None) -> dict[str, Any]:
        data = list(trades) if trades is not None else self.trades()
        quantity = sum(integer(x.get("quantity")) for x in data)
        amount = sum(num(x.get("amount")) for x in data)
        symbols = {safe_key(x.get("symbol")) for x in data if x.get("symbol")}
        return {
            "trades": len(data),
            "quantity": quantity,
            "turnover": amount,
            "symbols": len(symbols),
        }

    # ---------------- broker analytics ----------------

    def broker_analysis(self, trades: Iterable[Mapping[str, Any]] | None = None) -> list[dict[str, Any]]:
        data = list(trades) if trades is not None else self.trades()
        stats: dict[str, dict[str, Any]] = defaultdict(lambda: {
            "buy_trades": 0, "sell_trades": 0,
            "buy_quantity": 0, "sell_quantity": 0,
            "buy_amount": 0.0, "sell_amount": 0.0,
        })
        for trade in data:
            buyer = str(trade.get("buyer") or "").strip()
            seller = str(trade.get("seller") or "").strip()
            quantity = integer(trade.get("quantity"))
            amount = num(trade.get("amount"))
            if buyer:
                s = stats[buyer]
                s["buy_trades"] += 1; s["buy_quantity"] += quantity; s["buy_amount"] += amount
            if seller:
                s = stats[seller]
                s["sell_trades"] += 1; s["sell_quantity"] += quantity; s["sell_amount"] += amount
        output = []
        for broker, stat in stats.items():
            item = dict(stat)
            item["broker"] = broker
            item["net_amount"] = item["buy_amount"] - item["sell_amount"]
            item["net_quantity"] = item["buy_quantity"] - item["sell_quantity"]
            item["gross_amount"] = item["buy_amount"] + item["sell_amount"]
            item["buy_sell_ratio"] = (
                item["buy_amount"] / item["sell_amount"] if item["sell_amount"] else None
            )
            output.append(item)
        return sorted(output, key=lambda x: x["gross_amount"], reverse=True)

    def broker_flow_by_symbol(self, trades: Iterable[Mapping[str, Any]] | None = None) -> list[dict[str, Any]]:
        data = list(trades) if trades is not None else self.trades()
        grouped: dict[tuple[str, str], dict[str, Any]] = {}
        for trade in data:
            symbol = safe_key(trade.get("symbol"))
            quantity = integer(trade.get("quantity")); amount = num(trade.get("amount"))
            for side, broker_key in (("buy", "buyer"), ("sell", "seller")):
                broker = str(trade.get(broker_key) or "").strip()
                if not broker or not symbol:
                    continue
                key = (symbol, broker)
                item = grouped.setdefault(key, {
                    "symbol": symbol, "broker": broker,
                    "buy_trades": 0, "sell_trades": 0,
                    "buy_quantity": 0, "sell_quantity": 0,
                    "buy_amount": 0.0, "sell_amount": 0.0,
                })
                item[f"{side}_trades"] += 1
                item[f"{side}_quantity"] += quantity
                item[f"{side}_amount"] += amount
        output = []
        for item in grouped.values():
            item["net_amount"] = item["buy_amount"] - item["sell_amount"]
            item["net_quantity"] = item["buy_quantity"] - item["sell_quantity"]
            output.append(item)
        return sorted(output, key=lambda x: abs(x["net_amount"]), reverse=True)

    # ---------------- directories/company resolution ----------------

    def companies(self) -> Any:
        return self._cached("directory:companies", lambda: self.call("companies"), 3600)

    def securities(self) -> Any:
        return self._cached("directory:securities", lambda: self.call("securities"), 3600)

    def brokers(self) -> Any:
        return self._cached("directory:brokers", lambda: self.call("brokers"), 3600)

    def dealers(self) -> Any:
        return self._cached("directory:dealers", lambda: self.call("dealers"), 3600)

    def sectors(self) -> Any:
        return self._cached("directory:sectors", lambda: self.call("sectors"), 3600)

    def share_groups(self) -> Any:
        return self._cached("directory:share_groups", lambda: self.call("share_groups"), 3600)

    def promoters(self) -> Any:
        return self._cached("directory:promoters", lambda: self.call("promoters"), 3600)

    def company(self, symbol_or_id: str | int) -> dict[str, Any]:
        key = safe_key(symbol_or_id)
        source = rows(self.companies())
        if not source:
            source = rows(self.securities())
        for item in source:
            symbol = safe_key(first(item, "symbol", "stockSymbol", "securitySymbol", default=""))
            ident = str(first(item, "id", "securityId", "security_id", default=""))
            if symbol == key or ident == str(symbol_or_id):
                return item
        # If a caller supplied an ID, profile lookup can still work.
        if str(symbol_or_id).isdigit():
            return {"id": int(symbol_or_id)}
        raise KeyError(f"NEPSE security not found: {symbol_or_id}")

    def security_id(self, symbol_or_id: str | int) -> Any:
        item = self.company(symbol_or_id)
        return first(item, "id", "securityId", "security_id", default=symbol_or_id)

    def company_profile(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        return self._cached(
            f"profile:{sid}", lambda: self.call("security_profile", stock_id=sid), 1800
        )

    # ---------------- company fundamentals / corporate data ----------------

    def financial_reports(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        return self.call("financial_reports", stock_id=sid)

    def dividends(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        try:
            return self.call("dividends", stock_id=sid)
        except Exception:
            return self.call("dividends", symbol=safe_key(symbol_or_id))

    def corporate_actions(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        try:
            return self.call("corporate_actions", stock_id=sid)
        except Exception:
            return self.call("corporate_actions", symbol=safe_key(symbol_or_id))

    def agm(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        try:
            return self.call("agm", stock_id=sid)
        except Exception:
            return self.call("agm", symbol=safe_key(symbol_or_id))

    def company_news(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        try:
            return self.call("company_news", stock_id=sid)
        except Exception:
            return self.call("company_news", symbol=safe_key(symbol_or_id))

    def company_snapshot(self, symbol_or_id: str | int) -> dict[str, Any]:
        """Best-effort single-company bundle for Stock X-Ray/Fundamentals UI."""
        result: dict[str, Any] = {"symbol": safe_key(symbol_or_id)}
        result["directory"] = self.company(symbol_or_id)
        for key, fn in (
            ("profile", self.company_profile),
            ("financial_reports", self.financial_reports),
            ("dividends", self.dividends),
            ("corporate_actions", self.corporate_actions),
            ("agm", self.agm),
            ("news", self.company_news),
        ):
            try:
                result[key] = fn(symbol_or_id)
            except Exception as exc:
                result[key] = {"error": str(exc)}
        return result

    # ---------------- history/chart ----------------

    def history(self, symbol_or_id: str | int, **kwargs: Any) -> Any:
        sid = self.security_id(symbol_or_id)
        symbol = safe_key(symbol_or_id)
        cache_key = f"history:{sid}:{sorted(kwargs.items())}"

        def load() -> Any:
            # Try the documented/current names first.  Signature filtering is
            # handled by call(), so this remains compatible across releases.
            for method in ("price_history", "security_history", "history", "chart", "company_chart"):
                try:
                    return self.call(method, stock_id=sid, symbol=symbol, **kwargs)
                except AttributeError:
                    continue
                except TypeError:
                    continue
            raise AttributeError("No compatible NEPSE security-history method is available")

        return self._cached(cache_key, load, self.CACHE_TTLS["history"])

    @staticmethod
    def ohlcv(payload: Any) -> list[dict[str, Any]]:
        """Normalize NEPSE history into chart-ready OHLCV bars."""
        source = rows(payload)
        if not source and isinstance(payload, list):
            source = [x for x in payload if isinstance(x, dict)]
        output: list[dict[str, Any]] = []
        for item in source:
            ts = timestamp(first(item, "date", "businessDate", "timestamp", "time", "tradeDate", "x"))
            close = num(first(item, "close", "closePrice", "lastPrice", "price", "ltp"))
            if ts is None or close <= 0:
                continue
            op = num(first(item, "open", "openPrice"), close)
            hi = num(first(item, "high", "highPrice"), max(op, close))
            lo = num(first(item, "low", "lowPrice"), min(op, close))
            vol = num(first(item, "volume", "totalTradedQuantity", "sharesTraded", "quantity"))
            output.append({"time": ts, "open": op, "high": hi, "low": lo, "close": close, "volume": vol})
        output.sort(key=lambda x: x["time"])
        # Deduplicate bars by timestamp; keep the latest representation.
        dedup: dict[int, dict[str, Any]] = {item["time"]: item for item in output}
        return [dedup[key] for key in sorted(dedup)]

    def history_ohlcv(self, symbol_or_id: str | int, **kwargs: Any) -> list[dict[str, Any]]:
        return self.ohlcv(self.history(symbol_or_id, **kwargs))

    def indices(self) -> Any:
        return self._cached("indices", lambda: self.call("indices"), 10)

    def index_history(self, index: str = "NEPSE", **kwargs: Any) -> Any:
        return self.call("index_history", index=index, **kwargs)

    # ---------------- technical calculations ----------------

    @staticmethod
    def technical(rows_: list[Mapping[str, Any]], periods: tuple[int, ...] = (9, 20, 50)) -> list[dict[str, Any]]:
        """Add SMA/EMA/RSI-style fields without requiring pandas."""
        data = [dict(x) for x in rows_]
        closes = [num(x.get("close")) for x in data]
        for period in periods:
            key = f"sma_{period}"
            for i, item in enumerate(data):
                window = closes[max(0, i - period + 1):i + 1]
                item[key] = sum(window) / len(window) if window else None
        if data:
            gains = [0.0]; losses = [0.0]
            for i in range(1, len(closes)):
                change = closes[i] - closes[i - 1]
                gains.append(max(change, 0.0)); losses.append(max(-change, 0.0))
            p = 14
            for i, item in enumerate(data):
                if i < p:
                    item["rsi_14"] = None
                    continue
                avg_gain = sum(gains[i - p + 1:i + 1]) / p
                avg_loss = sum(losses[i - p + 1:i + 1]) / p
                item["rsi_14"] = 100.0 if avg_loss == 0 else 100 - (100 / (1 + avg_gain / avg_loss))
        return data

    def technical_history(self, symbol_or_id: str | int, **kwargs: Any) -> list[dict[str, Any]]:
        return self.technical(self.history_ohlcv(symbol_or_id, **kwargs))

    # ---------------- depth / supply-demand ----------------

    def depth(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        return self.call("depth", stock_id=sid)

    def supply_demand(self, symbol_or_id: str | int) -> Any:
        sid = self.security_id(symbol_or_id)
        return self.call("supply_demand", stock_id=sid)

    # ---------------- notices / files ----------------

    def notices(self, **kwargs: Any) -> Any:
        return self.call("notices", **kwargs)

    def disclosures(self, **kwargs: Any) -> Any:
        return self.call("disclosures", **kwargs)

    def holidays(self, **kwargs: Any) -> Any:
        return self.call("holidays", **kwargs)

    def reports(self, **kwargs: Any) -> Any:
        return self.call("reports", **kwargs)

    def events(self, **kwargs: Any) -> Any:
        return self.call("events", **kwargs)

    def csv(self, **kwargs: Any) -> Any:
        return self.call("csv", **kwargs)

    def downloads(self, **kwargs: Any) -> Any:
        return self.call("downloads", **kwargs)

    # ---------------- TradingView UDF-compatible adapters ----------------

    def tv_config(self) -> dict[str, Any]:
        return {
            "supports_search": True,
            "supports_group_request": False,
            "supports_marks": False,
            "supports_timescale_marks": False,
            "supports_time": True,
            "supported_resolutions": ["1", "5", "15", "30", "60", "1D", "1W", "1M"],
            "exchanges": [{"value": "NEPSE", "name": "Nepal Stock Exchange", "desc": "NEPSE"}],
            "symbols_types": [{"name": "stock", "value": "stock"}],
        }

    def tv_time(self) -> int:
        return int(time.time())

    def tv_symbol(self, symbol: str) -> dict[str, Any]:
        symbol = safe_key(symbol)
        company = self.company(symbol)
        name = first(company, "name", "companyName", "securityName", default=symbol)
        return {
            "name": symbol,
            "ticker": symbol,
            "description": str(name),
            "type": "stock",
            "session": "1100-1500",
            "timezone": "Asia/Kathmandu",
            "exchange": "NEPSE",
            "listed_exchange": "NEPSE",
            "minmov": 1,
            "pricescale": 100,
            "has_intraday": True,
            "has_daily": True,
            "has_weekly_and_monthly": True,
            "supported_resolutions": ["1D", "1W", "1M"],
            "volume_precision": 0,
            "data_status": "streaming",
        }

    def tv_search(self, query: str, limit: int = 30) -> list[dict[str, Any]]:
        q = safe_key(query)
        matches = []
        for item in rows(self.companies()) or rows(self.securities()):
            symbol = safe_key(first(item, "symbol", "stockSymbol", default=""))
            name = str(first(item, "name", "companyName", "securityName", default=""))
            if q in symbol or q in name.upper():
                matches.append({
                    "symbol": symbol,
                    "full_name": f"NEPSE:{symbol}",
                    "description": name,
                    "exchange": "NEPSE",
                    "ticker": symbol,
                    "type": "stock",
                })
        matches.sort(key=lambda x: (not x["symbol"].startswith(q), x["symbol"]))
        return matches[:max(1, int(limit))]

    def tv_history(
        self,
        symbol: str,
        *,
        from_ts: int | None = None,
        to_ts: int | None = None,
        countback: int | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        bars = self.history_ohlcv(symbol, **kwargs)
        if from_ts is not None:
            bars = [x for x in bars if x["time"] >= int(from_ts)]
        if to_ts is not None:
            bars = [x for x in bars if x["time"] <= int(to_ts)]
        if countback:
            bars = bars[-max(1, int(countback)):]
        if not bars:
            return {"s": "no_data", "nextTime": int(to_ts or time.time())}
        return {
            "s": "ok",
            "t": [x["time"] for x in bars],
            "o": [x["open"] for x in bars],
            "h": [x["high"] for x in bars],
            "l": [x["low"] for x in bars],
            "c": [x["close"] for x in bars],
            "v": [x["volume"] for x in bars],
        }


# Backwards-compatible alias used by the earlier SDK.
Nepse = NEPSE


if __name__ == "__main__":
    with NEPSE(cache_ttl=30) as api:
        print("NEPSE status:", api.market_status())
        print("Top gainers:", api.top_gainers(5))
        print("Available nepsepy methods:", len(api.available_methods()))
