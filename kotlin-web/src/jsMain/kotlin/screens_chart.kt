import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement

data class ChartRange(val key: String, val label: String, val resolution: String, val countback: Int)

val CHART_RANGES = listOf(
    ChartRange("1D", "1D", "5", 400),
    ChartRange("5D", "5D", "15", 600),
    ChartRange("1M", "1M", "D", 30),
    ChartRange("3M", "3M", "D", 90),
    ChartRange("6M", "6M", "D", 180),
    ChartRange("YTD", "YTD", "D", 320),
    ChartRange("1Y", "1Y", "D", 365),
    ChartRange("5Y", "5Y", "W", 300),
    ChartRange("MAX", "MAX", "M", 360)
)

val CHART_INDICATORS = listOf("EMA20", "EMA50", "SMA200", "BB", "VWAP", "SR", "RSI", "MACD")

private val dateFromMs: dynamic = js("(function(t){return new Date(t)})")

fun barIsoDate(ms: Double): String {
    val date = dateFromMs(ms)
    val year = date.getFullYear().toString().toInt()
    val month = date.getMonth().toString().toInt() + 1
    val day = date.getDate().toString().toInt()
    return year.toString() + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

fun startOfYearMs(): Double =
    js("(function(){var d=new Date();return new Date(d.getFullYear(),0,1).getTime()})()") as Double

fun todayIso(): String {
    val d = js("new Date()")
    val year = d.getFullYear().toString().toInt()
    val month = d.getMonth().toString().toInt() + 1
    val day = d.getDate().toString().toInt()
    return year.toString() + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

/**
 * Loads OHLCV candles for a symbol. The history service returns {s:"no_data"}
 * for intervals it does not publish; that case is reported instead of drawing
 * a fabricated series.
 */
fun fetchBars(
    symbol: String,
    resolution: String,
    countback: Int,
    done: (List<Bar>, String?) -> Unit
) {
    val clean = symbol.trim().uppercase()
    if (clean.isEmpty()) {
        done(emptyList(), "Enter a NEPSE symbol first.")
        return
    }
    val isIndex = clean.startsWith("INDEX:")
    val path = if (isIndex) {
        "/api/history?index=" + encode(clean.removePrefix("INDEX:"))
    } else {
        "/api/tv/history?symbol=" + encode(clean) + "&resolution=" + encode(resolution) + "&countback=" + countback
    }
    Http.get(path, ttlMs = 60_000.0, timeoutMs = 45_000, onData = { payload ->
        val bars = parseBars(payload)
        val status = text(pick(payload, "s"))
        if (bars.isEmpty()) {
            val message = if (status == "no_data") {
                "The history service returns no candles for $clean at interval $resolution. Intraday intervals (1m–1h) are frequently unavailable; no data has been invented."
            } else {
                "No price history was returned for $clean."
            }
            done(emptyList(), message)
        } else {
            done(bars, null)
        }
    }, onError = { message ->
        done(emptyList(), message)
    })
}

fun advancedChartScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "VISUAL ANALYSIS",
        "Advanced charts",
        "Interactive candlesticks with EMA, SMA, Bollinger bands, VWAP, support and resistance, RSI and MACD — computed from the NEPSE history service.",
        listOf(actionButton("Chart replay", "secondary", "clock") { go("replay") })
    ))
    root.appendChild(body)

    var symbol = currentSymbol.ifBlank { "NABIL" }
    var rangeKey = "1Y"
    var intervalKey = ""
    val indicatorSet = linkedSetOf("EMA20", "EMA50", "SR")

    val statusLine = el("p", "muted small", "Loading price history…")
    val chartHost = el("div", "kchart-host")
    val chart = NpKChart(chartHost, 420, intraday = false)
    val chartCard = card("chart-card")
    val chartHead = el("div", "chart-head")
    val chartTitle = el("div", "chart-title", "NABIL")
    val chartMeta = el("div", "chart-meta", "1Y · D")
    chartHead.add(chartTitle, chartMeta)
    chartCard.appendChild(chartHead)
    chartCard.appendChild(chartHost)
    chartCard.appendChild(statusLine)

    val rangeHost = el("div", "chart-ranges")
    val indicatorHost = el("div", "chart-toggles")

    var load: () -> Unit = {}

    fun activeResolution(): String {
        if (intervalKey.isNotBlank()) return intervalKey
        return CHART_RANGES.firstOrNull { it.key == rangeKey }?.resolution ?: "D"
    }

    fun activeCountback(): Int {
        if (intervalKey.isNotBlank()) {
            return when (intervalKey) {
                "1m", "5m", "15m", "30m", "1h" -> 600
                "D" -> 400
                "W" -> 320
                else -> 300
            }
        }
        return CHART_RANGES.firstOrNull { it.key == rangeKey }?.countback ?: 365
    }

    fun paintRanges() {
        rangeHost.innerHTML = ""
        for (option in CHART_RANGES) {
            rangeHost.appendChild(chipButton(option.label, option.key == rangeKey) {
                rangeKey = option.key
                intervalKey = ""
                paintRanges()
                load()
            })
        }
    }

    fun paintIndicators() {
        indicatorHost.innerHTML = ""
        for (name in CHART_INDICATORS) {
            indicatorHost.appendChild(chipButton(name, name in indicatorSet) {
                if (name in indicatorSet) indicatorSet.remove(name) else indicatorSet.add(name)
                chart.setIndicators(indicatorSet)
                paintIndicators()
            })
        }
    }

    val symbolInput = field("NEPSE symbol, e.g. NABIL", "search", symbol)
    symbolInput.addEventListener("keydown", { event ->
        if (eventKey(event) == "Enter") {
            symbol = symbolInput.value.trim().uppercase()
            load()
        }
    })
    val intervalSelect = selectField(
        listOf("" to "Auto (per range)") + listOf(
            "1m" to "1 minute", "5m" to "5 minutes", "15m" to "15 minutes",
            "30m" to "30 minutes", "1h" to "1 hour", "D" to "Daily",
            "W" to "Weekly", "M" to "Monthly"
        ),
        intervalKey,
        { value ->
            intervalKey = value
            load()
        }
    )

    val searchRow = el("div", "toolbar")
    searchRow.appendChild(symbolInput)
    searchRow.appendChild(actionButton("Load", "primary", "chart") {
        symbol = symbolInput.value.trim().uppercase().ifBlank { symbol }
        load()
    })
    searchRow.appendChild(intervalSelect)
    searchRow.appendChild(iconButton("refresh", "Reload candles") { load() })

    val zoomRow = el("div", "chart-controls-row")
    zoomRow.appendChild(actionButton("Zoom in", "ghost", "plus") { chart.zoom(1.35) })
    zoomRow.appendChild(actionButton("Zoom out", "ghost", null) { chart.zoom(0.74) })
    zoomRow.appendChild(actionButton("Reset view", "ghost", "refresh") { chart.resetView() })
    zoomRow.appendChild(actionButton("Replay this symbol", "ghost", "clock") {
        replaySymbolSeed = symbol
        go("replay")
    })

    val toolbar = card("toolbar-card")
    toolbar.appendChild(searchRow)
    toolbar.appendChild(rangeHost)
    toolbar.appendChild(indicatorHost)
    toolbar.appendChild(zoomRow)
    body.appendChild(toolbar)
    body.appendChild(chartCard)

    load = {
        val clean = symbol.trim().uppercase().ifBlank { "NABIL" }
        symbol = clean
        chart.symbol = clean
        chart.rangeLabel = rangeKey
        chart.intervalLabel = activeResolution()
        chart.setIndicators(indicatorSet)
        chartTitle.textContent = clean
        chartMeta.textContent = rangeKey + " · " + activeResolution()
        statusLine.textContent = "Loading " + clean + " candles…"
        fetchBars(clean, activeResolution(), activeCountback()) { bars, error ->
            if (bars.isEmpty()) {
                chart.setData(emptyList())
                statusLine.textContent = error ?: "No candles returned."
                return@fetchBars
            }
            val filtered = if (rangeKey == "YTD" && intervalKey.isBlank()) {
                bars.filter { it.time >= startOfYearMs() }
            } else {
                bars
            }
            val data = if (filtered.size >= 5) filtered else bars
            chart.setData(data)
            val first = data.first()
            val last = data.last()
            val change = if (first.close != 0.0) (last.close - first.close) / first.close * 100.0 else null
            statusLine.textContent = integer(data.size.toDouble()) + " candles · " + barIsoDate(first.time) +
                " → " + barIsoDate(last.time) + " · " + percent(change) + " over the window"
        }
    }

    paintRanges()
    paintIndicators()
    load()
    return root
}
