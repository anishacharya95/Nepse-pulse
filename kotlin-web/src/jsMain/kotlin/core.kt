import kotlinx.browser.window
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong

const val API_BASE = "https://nepse-pulse-1.onrender.com"
const val APP_VERSION = "3.0.0"
const val BRAND = "NEPSE Pulse"

private val objectKeys: dynamic = js("Object.keys")
private val jsonParse: dynamic = js("JSON.parse")
private val jsonStringify: dynamic = js("JSON.stringify")
private val consoleRef: dynamic = js("console")

fun encode(value: String): String = js("encodeURIComponent")(value) as String
fun nowMs(): Double = js("Date.now")() as Double
fun logError(scope: String, error: dynamic) {
    consoleRef.error("[NEPSE] $scope", error)
}

fun errText(error: dynamic): String {
    if (error == null || error == undefined) return "Unknown error"
    val message = error.message
    val text = if (message != null && message != undefined) message.toString() else error.toString()
    if (text.contains("abort", ignoreCase = true)) return "Request timed out. The market service did not respond."
    if (text.contains("Failed to fetch", ignoreCase = true)) return "Network unavailable. Check your connection and retry."
    return text
}

fun toastLater(message: String, tone: String) {
    if (message.isBlank()) return
    UIBridge.notify?.invoke(message, tone)
}

fun keysOf(value: dynamic): List<String> {
    if (value == null || value == undefined) return emptyList()
    val names = objectKeys(value)
    val count = num(names.length)?.toInt() ?: 0
    val out = ArrayList<String>(count)
    for (i in 0 until count) out.add(names[i].toString())
    return out
}

// ---------------------------------------------------------------- numbers

fun num(value: dynamic): Double? {
    if (value == null || value == undefined) return null
    if (value is Number) {
        val d = value.toDouble()
        return if (d.isNaN() || d.isInfinite()) null else d
    }
    val cleaned = value.toString().replace(",", "").replace("%", "").replace("Rs.", "").trim()
    if (cleaned.isEmpty()) return null
    val parsed = cleaned.toDoubleOrNull() ?: return null
    return if (parsed.isNaN() || parsed.isInfinite()) null else parsed
}

fun text(value: dynamic): String? {
    if (value == null || value == undefined) return null
    val s = value.toString().trim()
    return if (s.isEmpty() || s == "null" || s == "undefined") null else s
}

fun keyNorm(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

fun pick(obj: dynamic, vararg keys: String): dynamic {
    if (obj == null || obj == undefined) return null
    val wanted = keys.map { keyNorm(it) }.toSet()
    val names = objectKeys(obj)
    val count = num(names.length)?.toInt() ?: 0
    for (i in 0 until count) {
        val name = names[i].toString()
        if (keyNorm(name) in wanted) {
            val value = obj[name]
            if (value != null && value != undefined && value.toString().isNotBlank()) return value
        }
    }
    return null
}

fun rowsOf(value: dynamic): List<dynamic> {
    if (value == null || value == undefined) return emptyList()
    val length = num(value.length)?.toInt() ?: return emptyList()
    val list = ArrayList<dynamic>(length)
    for (i in 0 until length) list.add(value[i])
    return list
}

fun fixed(value: Double, digits: Int): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    val negative = value < 0
    val absolute = abs(value)
    val factor = 10.0.pow(digits.toDouble())
    val scaled = round(absolute * factor).toLong()
    val whole = scaled / factor.toLong()
    val result = if (digits == 0) {
        whole.toString()
    } else {
        val fraction = scaled % factor.toLong()
        whole.toString() + "." + fraction.toString().padStart(digits, '0')
    }
    return if (negative && result != "0") "-$result" else result
}

fun groupThousands(value: String): String {
    val negative = value.startsWith("-")
    val unsigned = if (negative) value.substring(1) else value
    val dot = unsigned.indexOf('.')
    val whole = if (dot >= 0) unsigned.substring(0, dot) else unsigned
    val fraction = if (dot >= 0) unsigned.substring(dot) else ""
    if (whole.length <= 3) return (if (negative) "-" else "") + whole + fraction
    val tail = whole.substring(whole.length - 3)
    var head = whole.substring(0, whole.length - 3)
    val groups = ArrayList<String>()
    while (head.length > 2) {
        groups.add(0, head.substring(head.length - 2))
        head = head.substring(0, head.length - 2)
    }
    if (head.isNotEmpty()) groups.add(0, head)
    return (if (negative) "-" else "") + groups.joinToString(",") + "," + tail + fraction
}

fun price(value: Double?): String = if (value == null) "—" else groupThousands(fixed(value, 2))

fun changePoints(value: Double?): String {
    if (value == null) return "—"
    val sign = if (value > 0) "+" else ""
    return sign + groupThousands(fixed(value, 2))
}

fun percent(value: Double?): String {
    if (value == null) return "—"
    val sign = if (value > 0) "+" else ""
    return "$sign${fixed(value, 2)}%"
}

fun compact(value: Double?): String {
    if (value == null) return "—"
    val absolute = abs(value)
    return when {
        absolute >= 1_000_000_000 -> fixed(value / 1_000_000_000, 2) + "B"
        absolute >= 1_000_000 -> fixed(value / 1_000_000, 2) + "M"
        absolute >= 1_000 -> fixed(value / 1_000, 1) + "K"
        else -> fixed(value, 2)
    }
}

fun rupees(value: Double?): String = if (value == null) "—" else "Rs " + compact(value)

fun integer(value: Double?): String = if (value == null) "—" else groupThousands(fixed(value, 0))

fun toneOf(value: Double?): String = when {
    value == null -> "neutral"
    value > 0 -> "up"
    value < 0 -> "down"
    else -> "flat"
}

fun clockTime(value: String?): String {
    if (value == null || value.isBlank()) return "—"
    val part = if (value.contains("T")) value.substringAfter("T") else value
    return part.take(8)
}

fun shortDate(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    val raw = value.trim()
    if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') {
        val year = raw.substring(0, 4)
        val month = raw.substring(5, 7).toIntOrNull() ?: return raw
        val day = raw.substring(8, 10)
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "$day ${names.getOrElse(month - 1) { "" }} $year"
    }
    return raw.take(16)
}

// ---------------------------------------------------------------- local store

object Store {
    fun get(key: String): String? {
        return try {
            window.localStorage.getItem("np.$key")
        } catch (_: Throwable) {
            null
        }
    }

    fun put(key: String, value: String) {
        try {
            window.localStorage.setItem("np.$key", value)
        } catch (_: Throwable) {
        }
    }

    fun remove(key: String) {
        try {
            window.localStorage.removeItem("np.$key")
        } catch (_: Throwable) {
        }
    }

    fun getJson(key: String): dynamic {
        val raw = get(key) ?: return null
        return try {
            jsonParse(raw)
        } catch (_: Throwable) {
            null
        }
    }

    fun putJson(key: String, value: dynamic) {
        try {
            put(key, jsonStringify(value) as String)
        } catch (_: Throwable) {
        }
    }
}

class Cached(val at: Double, val body: dynamic)

object Http {
    private val cache = HashMap<String, Cached>()
    private val inFlight = HashMap<String, MutableList<Pair<(dynamic) -> Unit, (String) -> Unit>>>()

    fun get(
        path: String,
        ttlMs: Double = 60_000.0,
        force: Boolean = false,
        timeoutMs: Int = 45_000,
        onData: (dynamic) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val hit = cache[path]
        val stamp = nowMs()
        if (!force && hit != null && stamp - hit.at < ttlMs) {
            onData(hit.body)
            return
        }
        if (!force) {
            val waiting = inFlight[path]
            if (waiting != null) {
                waiting.add(Pair(onData, onError))
                return
            }
            inFlight[path] = mutableListOf(Pair(onData, onError))
        }
        val url = if (path.startsWith("http")) path else API_BASE + path
        try {
            val init = js("({cache:'no-store',headers:{'Accept':'application/json'}})")
            val controller = js("new AbortController()")
            init.signal = controller.signal
            window.setTimeout({ controller.abort() }, timeoutMs)
            val fetchRef = window.asDynamic().fetch
            val promise = fetchRef(url, init)
            promise
                .then({ response: dynamic ->
                    if (response.ok != true) {
                        throw IllegalStateException("Market API HTTP " + response.status.toString())
                    }
                    response.json()
                })
                .then(
                    { body: dynamic ->
                        cache[path] = Cached(nowMs(), body)
                        val waiting = inFlight.remove(path)
                        onData(body)
                        if (waiting != null) for (entry in waiting) entry.first(body)
                        null
                    },
                    { error: dynamic ->
                        logError("request $path", error)
                        val waiting = inFlight.remove(path)
                        val message = errText(error)
                        onError(message)
                        if (waiting != null) for (entry in waiting) entry.second(message)
                        null
                    }
                )
        } catch (error: dynamic) {
            inFlight.remove(path)
            onError(errText(error))
        }
    }

    fun clear() {
        cache.clear()
    }
}

// ---------------------------------------------------------------- models

data class Quote(
    val symbol: String,
    val name: String,
    val ltp: Double?,
    val change: Double?,
    val pct: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val prev: Double?,
    val volume: Double?,
    val turnover: Double?,
    val sector: String = ""
) {
    val isUp: Boolean get() = (pct ?: change ?: 0.0) > 0
    val isDown: Boolean get() = (pct ?: change ?: 0.0) < 0
}

data class Bar(
    val time: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

fun quoteOf(row: dynamic): Quote {
    val symbol = text(pick(row, "symbol", "stockSymbol", "ticker", "securitySymbol", "code", "scrip"))?.uppercase() ?: "—"
    val name = text(pick(row, "companyName", "securityName", "company", "name", "description")) ?: symbol
    val ltp = num(pick(row, "lastTradedPrice", "ltp", "closingPrice", "closePrice", "lastPrice", "price", "close"))
    val prev = num(pick(row, "previousClose", "previousDayClosePrice", "prevClose", "prev", "previousDayClose", "cp"))
    val change = num(pick(row, "pointChange", "change", "changeValue", "difference"))
        ?: if (ltp != null && prev != null) ltp - prev else null
    val pct = num(pick(row, "percentageChange", "percentChange", "perChange", "changePercent", "pChange"))
        ?: if (ltp != null && prev != null && prev != 0.0) (ltp - prev) / prev * 100.0 else null
    return Quote(
        symbol = symbol,
        name = name,
        ltp = ltp,
        change = change,
        pct = pct,
        open = num(pick(row, "openPrice", "open")),
        high = num(pick(row, "highPrice", "high")),
        low = num(pick(row, "lowPrice", "low")),
        prev = prev,
        volume = num(pick(row, "totalTradeQuantity", "volume", "tradedShares", "quantity", "lastTradedVolume")),
        turnover = num(pick(row, "totalTradeValue", "turnover", "amount", "totalTradedAmount")),
        sector = text(pick(row, "sector", "sectorName", "indexName")) ?: ""
    )
}

fun quotesOf(value: dynamic): List<Quote> = rowsOf(value).map { quoteOf(it) }

object Ctx {
    var market: dynamic = null
    var marketAt: Double = 0.0
    var quotes: List<Quote> = emptyList()
    var sectors: List<dynamic> = emptyList()
    var sectorsAt: Double = 0.0
    var sectorNames: Map<Int, String> = emptyMap()
    var online: Boolean = true
    var lastMarketError: String? = null
}

fun summaryMap(payload: dynamic): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for (row in rowsOf(payload?.summary)) {
        val label = keyNorm(text(pick(row, "detail", "label", "name", "title", "key")) ?: continue)
        val value = num(pick(row, "value", "amount", "count", "total", "val")) ?: continue
        out[label] = value
    }
    return out
}

fun summaryDouble(payload: dynamic, vararg labels: String): Double? {
    val map = summaryMap(payload)
    for (label in labels) {
        val target = keyNorm(label)
        for ((key, value) in map) {
            if (key.contains(target)) return value
        }
    }
    return null
}

fun marketBreadth(payload: dynamic): Triple<Int, Int, Int> {
    val breadth = payload?.breadth
    val advancing = num(pick(breadth, "advancing", "advances", "advance"))?.toInt()
    val declining = num(pick(breadth, "declining", "declines", "decline"))?.toInt()
    val unchanged = num(pick(breadth, "unchanged", "neutral"))?.toInt()
    if (advancing != null && declining != null) {
        return Triple(advancing, declining, unchanged ?: 0)
    }
    var up = 0
    var down = 0
    var flat = 0
    for (quote in Ctx.quotes) {
        val pct = quote.pct ?: continue
        when {
            pct > 0 -> up++
            pct < 0 -> down++
            else -> flat++
        }
    }
    return Triple(up, down, flat)
}

fun loadMarket(force: Boolean = false, onDone: (Boolean, String?) -> Unit) {
    val path = if (force) "/api/market?force=true" else "/api/market"
    Http.get(path, ttlMs = 25_000.0, force = force, timeoutMs = 55_000, onData = { payload ->
        if (payload == null || payload == undefined || payload.ok == false) {
            val message = text(pick(payload, "error", "message")) ?: "Market service returned no data"
            Ctx.online = false
            Ctx.lastMarketError = message
            onDone(false, message)
        } else {
            Ctx.market = payload
            Ctx.marketAt = nowMs()
            Ctx.quotes = rowsOf(payload.live).map { row ->
                val base = quoteOf(row)
                val sector = sectorFor(row)
                if (sector.isBlank()) base else base.copy(sector = sector)
            }
            Ctx.online = true
            Ctx.lastMarketError = null
            onDone(true, null)
        }
    }, onError = { message ->
        Ctx.online = false
        Ctx.lastMarketError = message
        onDone(false, message)
    })
}

fun loadSectors(onDone: (List<dynamic>) -> Unit = {}) {
    val fresh = Ctx.sectors.isNotEmpty() && nowMs() - Ctx.sectorsAt < 180_000.0
    if (fresh) {
        onDone(Ctx.sectors)
        return
    }
    Http.get("/api/sectors", ttlMs = 180_000.0, onData = { payload ->
        val rows = rowsOf(payload?.data)
        if (rows.isNotEmpty()) {
            Ctx.sectors = rows
            Ctx.sectorsAt = nowMs()
            val names = HashMap<Int, String>()
            for (row in rows) {
                val id = num(pick(row, "indexId", "id"))?.toInt() ?: continue
                val name = text(pick(row, "sector", "name", "index")) ?: continue
                names[id] = name
            }
            Ctx.sectorNames = names
        }
        onDone(Ctx.sectors)
    }, onError = {
        onDone(Ctx.sectors)
    })
}

fun sectorFor(quote: dynamic): String {
    val direct = text(pick(quote, "sector", "sectorName"))
    if (direct != null) return direct
    val indexId = num(pick(quote, "indexId"))?.toInt() ?: return ""
    return Ctx.sectorNames[indexId] ?: ""
}

// ---------------------------------------------------------------- OHLCV

fun parseBars(payload: dynamic): List<Bar> {
    val bars = ArrayList<Bar>()
    if (payload == null || payload == undefined) return bars
    val times = rowsOf(payload.t)
    if (times.isNotEmpty() && payload.s == "ok") {
        val opens = rowsOf(payload.o)
        val highs = rowsOf(payload.h)
        val lows = rowsOf(payload.l)
        val closes = rowsOf(payload.c)
        val volumes = rowsOf(payload.v)
        for (i in times.indices) {
            val t = num(times[i]) ?: continue
            val o = num(opens.getOrNull(i)) ?: continue
            val h = num(highs.getOrNull(i)) ?: continue
            val l = num(lows.getOrNull(i)) ?: continue
            val c = num(closes.getOrNull(i)) ?: continue
            bars.add(Bar(t * 1000.0, o, h, l, c, num(volumes.getOrNull(i)) ?: 0.0))
        }
        return bars
    }
    for (row in rowsOf(payload.data)) {
        val stamp = text(pick(row, "date", "time", "timestamp")) ?: continue
        val millis = num(js("Date.parse")(stamp)) ?: 0.0
        val o = num(pick(row, "open", "o")) ?: continue
        val h = num(pick(row, "high", "h")) ?: continue
        val l = num(pick(row, "low", "l")) ?: continue
        val c = num(pick(row, "close", "c")) ?: continue
        bars.add(Bar(if (millis > 0.0) millis else 0.0, o, h, l, c, num(pick(row, "volume", "v")) ?: 0.0))
    }
    return bars
}

fun loadHistory(
    symbol: String,
    resolution: String = "D",
    countback: Int = 500,
    onData: (List<Bar>) -> Unit,
    onError: (String) -> Unit = {}
) {
    val isIndex = symbol.startsWith("INDEX:")
    val path = if (isIndex) {
        "/api/history?index=" + encode(symbol.removePrefix("INDEX:"))
    } else {
        "/api/tv/history?symbol=" + encode(symbol) + "&resolution=" + encode(resolution) + "&countback=" + countback
    }
    Http.get(path, ttlMs = 120_000.0, timeoutMs = 40_000, onData = { payload ->
        val bars = parseBars(payload)
        if (bars.isEmpty()) onError("No price history returned for $symbol") else onData(bars)
    }, onError = onError)
}

fun loadIntraday(onData: (List<Bar>) -> Unit, onError: (String) -> Unit = {}) {
    Http.get("/api/index/intraday", ttlMs = 60_000.0, timeoutMs = 35_000, onData = { payload ->
        val bars = parseBars(payload)
        if (bars.isEmpty()) onError("Intraday index history is unavailable") else onData(bars)
    }, onError = onError)
}

// ---------------------------------------------------------------- analysis

fun sma(values: List<Double>, period: Int): Double? {
    if (values.size < period) return null
    var sum = 0.0
    for (i in values.size - period until values.size) sum += values[i]
    return sum / period
}

fun rsi(bars: List<Bar>, period: Int = 14): Double? {
    if (bars.size <= period) return null
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val delta = bars[i].close - bars[i - 1].close
        if (delta >= 0) gain += delta else loss -= delta
    }
    var avgGain = gain / period
    var avgLoss = loss / period
    for (i in period + 1 until bars.size) {
        val delta = bars[i].close - bars[i - 1].close
        avgGain = (avgGain * (period - 1) + max(delta, 0.0)) / period
        avgLoss = (avgLoss * (period - 1) + max(-delta, 0.0)) / period
    }
    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return 100.0 - 100.0 / (1.0 + rs)
}

fun pivotHighs(bars: List<Bar>, span: Int = 3): List<Int> {
    val out = ArrayList<Int>()
    if (bars.size < span * 2 + 1) return out
    for (i in span until bars.size - span) {
        var isPivot = true
        for (j in i - span..i + span) {
            if (j == i) continue
            if (bars[j].high >= bars[i].high) {
                isPivot = false
                break
            }
        }
        if (isPivot) out.add(i)
    }
    return out
}

fun pivotLows(bars: List<Bar>, span: Int = 3): List<Int> {
    val out = ArrayList<Int>()
    if (bars.size < span * 2 + 1) return out
    for (i in span until bars.size - span) {
        var isPivot = true
        for (j in i - span..i + span) {
            if (j == i) continue
            if (bars[j].low <= bars[i].low) {
                isPivot = false
                break
            }
        }
        if (isPivot) out.add(i)
    }
    return out
}

data class TrendLine(val slope: Double, val fromIndex: Int, val toIndex: Int, val fromValue: Double, val toValue: Double) {
    fun project(index: Int): Double = fromValue + slope * (index - fromIndex)
}

fun fitLine(bars: List<Bar>, indices: List<Int>, highSide: Boolean): TrendLine? {
    if (indices.size < 2) return null
    val a = indices[indices.size - 2]
    val b = indices[indices.size - 1]
    if (b == a) return null
    val va = if (highSide) bars[a].high else bars[a].low
    val vb = if (highSide) bars[b].high else bars[b].low
    return TrendLine((vb - va) / (b - a).toDouble(), a, b, va, vb)
}

data class Analysis(
    val rsi: Double?,
    val sma20: Double?,
    val sma50: Double?,
    val sma200: Double?,
    val support: Double?,
    val resistance: Double?,
    val trend: String,
    val volumeRatio: Double?,
    val high52: Double?,
    val low52: Double?,
    val last: Double?,
    val changePct: Double?,
    val pattern: String,
    val score: Int,
    val notes: List<String>
)

fun analyzeBars(bars: List<Bar>): Analysis {
    val closes = bars.map { it.close }
    val last = closes.lastOrNull()
    val previous = if (closes.size >= 2) closes[closes.size - 2] else null
    val changePct = if (last != null && previous != null && previous != 0.0) (last - previous) / previous * 100.0 else null
    val rsiValue = rsi(bars)
    val s20 = sma(closes, 20)
    val s50 = sma(closes, 50)
    val s200 = sma(closes, 200)
    val highs = pivotHighs(bars)
    val lows = pivotLows(bars)
    val resistance = fitLine(bars, highs, true)?.project(bars.size - 1)
    val support = fitLine(bars, lows, false)?.project(bars.size - 1)
    val lookback = bars.takeLast(min(bars.size, 252))
    val high52 = lookback.maxOfOrNull { it.high }
    val low52 = lookback.minOfOrNull { it.low }
    val avgVolume = if (bars.size >= 21) {
        val slice = bars.subList(bars.size - 21, bars.size - 1)
        slice.sumOf { it.volume } / slice.size
    } else null
    val latestVolume = bars.lastOrNull()?.volume
    val volumeRatio = if (avgVolume != null && avgVolume > 0.0 && latestVolume != null) latestVolume / avgVolume else null

    var score = 50
    val notes = ArrayList<String>()
    if (last != null && s20 != null) {
        if (last > s20) {
            score += 6
            notes.add("Trading above the 20-day average")
        } else {
            score -= 6
            notes.add("Trading below the 20-day average")
        }
    }
    if (s20 != null && s50 != null) {
        if (s20 > s50) {
            score += 7
            notes.add("Short-term average above the 50-day average")
        } else {
            score -= 7
            notes.add("Short-term average below the 50-day average")
        }
    }
    if (rsiValue != null) {
        when {
            rsiValue < 30 -> {
                score += 4
                notes.add("RSI ${fixed(rsiValue, 1)} — oversold zone")
            }
            rsiValue > 70 -> {
                score -= 6
                notes.add("RSI ${fixed(rsiValue, 1)} — overbought zone")
            }
            else -> notes.add("RSI ${fixed(rsiValue, 1)} — neutral zone")
        }
    }
    if (volumeRatio != null) {
        if (volumeRatio > 1.5) {
            score += 5
            notes.add("Volume ${fixed(volumeRatio, 2)}× the 20-day average")
        } else if (volumeRatio < 0.6) {
            notes.add("Volume ${fixed(volumeRatio, 2)}× the 20-day average — quiet session")
        }
    }
    val trend = when {
        s20 != null && s50 != null && s200 != null && s20 > s50 && s50 > s200 -> "Uptrend"
        s20 != null && s50 != null && s200 != null && s20 < s50 && s50 < s200 -> "Downtrend"
        s20 != null && s50 != null && s20 > s50 -> "Mild uptrend"
        s20 != null && s50 != null -> "Mild downtrend"
        else -> "Insufficient history"
    }
    val pattern = when {
        bars.size >= 40 -> {
            val recentHigh = bars.takeLast(20).maxOfOrNull { it.high } ?: 0.0
            val priorHigh = bars.dropLast(20).takeLast(20).maxOfOrNull { it.high } ?: 0.0
            val recentLow = bars.takeLast(20).minOfOrNull { it.low } ?: 0.0
            val priorLow = bars.dropLast(20).takeLast(20).minOfOrNull { it.low } ?: 0.0
            when {
                recentHigh > priorHigh && recentLow > priorLow -> "Higher highs and higher lows"
                recentHigh < priorHigh && recentLow < priorLow -> "Lower highs and lower lows"
                recentHigh <= priorHigh * 1.01 && recentLow >= priorLow * 0.99 -> "Range compression"
                else -> "Mixed structure"
            }
        }
        else -> "Not enough candles"
    }
    return Analysis(
        rsi = rsiValue,
        sma20 = s20,
        sma50 = s50,
        sma200 = s200,
        support = support,
        resistance = resistance,
        trend = trend,
        volumeRatio = volumeRatio,
        high52 = high52,
        low52 = low52,
        last = last,
        changePct = changePct,
        pattern = pattern,
        score = score.coerceIn(0, 100),
        notes = notes
    )
}

fun verdictFor(analysis: Analysis): String = when {
    analysis.score >= 68 -> "Constructive"
    analysis.score >= 56 -> "Mildly constructive"
    analysis.score >= 44 -> "Neutral"
    analysis.score >= 32 -> "Cautious"
    else -> "Weak"
}

// ---------------------------------------------------------------- portfolio, alerts, watchlist

data class Holding(val symbol: String, val quantity: Double, val average: Double)

fun loadHoldings(): MutableList<Holding> {
    val out = ArrayList<Holding>()
    val raw = Store.getJson("holdings")
    for (row in rowsOf(raw)) {
        val symbol = text(pick(row, "symbol"))?.uppercase() ?: continue
        val qty = num(pick(row, "quantity", "qty")) ?: continue
        val avg = num(pick(row, "average", "avg", "price")) ?: continue
        out.add(Holding(symbol, qty, avg))
    }
    return out
}

fun saveHoldings(holdings: List<Holding>) {
    val array = js("[]")
    for (holding in holdings) {
        val item = js("({})")
        item.symbol = holding.symbol
        item.quantity = holding.quantity
        item.average = holding.average
        array.push(item)
    }
    Store.putJson("holdings", array)
}

data class PriceAlert(val id: String, val symbol: String, val kind: String, val value: Double, val createdAt: Double)

fun loadAlerts(): MutableList<PriceAlert> {
    val out = ArrayList<PriceAlert>()
    val raw = Store.getJson("alerts")
    for (row in rowsOf(raw)) {
        val id = text(pick(row, "id")) ?: continue
        val symbol = text(pick(row, "symbol"))?.uppercase() ?: continue
        val kind = text(pick(row, "kind")) ?: "above"
        val value = num(pick(row, "value")) ?: continue
        out.add(PriceAlert(id, symbol, kind, value, num(pick(row, "createdAt")) ?: 0.0))
    }
    return out
}

fun saveAlerts(alerts: List<PriceAlert>) {
    val array = js("[]")
    for (alert in alerts) {
        val item = js("({})")
        item.id = alert.id
        item.symbol = alert.symbol
        item.kind = alert.kind
        item.value = alert.value
        item.createdAt = alert.createdAt
        array.push(item)
    }
    Store.putJson("alerts", array)
}

fun localWatchlist(): MutableList<String> {
    val raw = Store.getJson("watchlist")
    val out = ArrayList<String>()
    for (row in rowsOf(raw)) {
        val symbol = if (row == null || row == undefined) null else row.toString().trim().uppercase()
        if (symbol != null && symbol.isNotBlank()) out.add(symbol)
    }
    return out
}

fun saveLocalWatchlist(symbols: List<String>) {
    val array = js("[]")
    for (symbol in symbols.distinct()) array.push(symbol)
    Store.putJson("watchlist", array)
}

fun quoteFor(symbol: String): Quote? = Ctx.quotes.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

fun searchQuotes(query: String, limit: Int = 8): List<Quote> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return Ctx.quotes
        .filter { it.symbol.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
        .sortedByDescending { it.symbol.startsWith(q, ignoreCase = true) }
        .take(limit)
}
