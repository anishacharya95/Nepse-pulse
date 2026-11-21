import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise

private const val API_BASE = "https://nepse-pulse-1.onrender.com"

private fun el(tag: String, className: String = "", text: String = ""): HTMLElement {
    val node = document.createElement(tag) as HTMLElement
    node.className = className
    node.textContent = text
    return node
}

private fun number(value: dynamic): Double? {
    if (value == null || value == undefined) return null
    val parsed = value.toString().replace(",", "").toDoubleOrNull()
    return parsed
}

private fun normKey(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun pick(obj: dynamic, vararg keys: String): dynamic {
    if (obj == null || obj == undefined) return null
    val wanted = keys.map { normKey(it) }.toSet()
    val names = js("Object.keys(obj)") as Array<String>
    for (name in names) {
        if (normKey(name) in wanted) {
            val value = obj[name]
            if (value != null && value != undefined && value.toString().isNotBlank()) return value
        }
    }
    return null
}

private fun breadth(rows: List<dynamic>): Triple<Int?, Int?, Int?> {
    val seen = mutableSetOf<String>()
    var advancing = 0
    var declining = 0
    var unchanged = 0
    for (row in rows) {
        val symbol = pick(row, "symbol", "stockSymbol", "securitySymbol", "ticker", "scrip", "code", "security_code")
            ?.toString()?.trim()?.uppercase() ?: continue
        if (symbol.isBlank() || !seen.add(symbol)) continue
        val close = number(pick(row, "ltp", "closePrice", "closingPrice", "lastTradedPrice", "lastPrice", "close", "price", "currentPrice"))
        val previous = number(pick(row, "prev", "previousDayClosePrice", "previousClose", "previousClosingPrice", "prevClose", "previousPrice", "previousDayClose", "previousDayClosingPrice"))
        if (close == null || previous == null || close <= 0.0 || previous <= 0.0) continue
        when {
            close > previous -> advancing++
            close < previous -> declining++
            else -> unchanged++
        }
    }
    return if (advancing + declining + unchanged > 0) Triple(advancing, declining, unchanged)
    else Triple(null, null, null)
}

private fun summaryValue(summary: dynamic, vararg labels: String): Double? {
    val entries = rowsOf(summary).ifEmpty {
        if (summary != null && summary != undefined) listOf(summary) else emptyList()
    }
    val targets = labels.map { normKey(it) }
    for (item in entries) {
        val label = pick(item, "detail", "label", "name", "title", "key", "metric")?.toString() ?: continue
        if (targets.any { normKey(label).contains(it) }) {
            val value = number(pick(item, "value", "amount", "count", "total", "val"))
            if (value != null) return value
        }
    }
    return null
}

private fun fmt(value: Double?): String {
    if (value == null) return "—"
    return value.toLong().toString().reversed().chunked(3).joinToString(",").reversed()
}

private fun fixed(value: Double): String = js("Number(value).toFixed(2)") as String

private fun pct(value: Double?): String =
    if (value == null) "—" else (if (value > 0) "+" else "") + fixed(value) + "%"

private fun rowsOf(value: dynamic): List<dynamic> {
    if (value == null || value == undefined) return emptyList()
    val length = number(value.length)?.toInt() ?: return emptyList()
    return (0 until length).map { value[it] }
}

private fun render(root: HTMLElement, payload: dynamic, search: String = "") {
    val data = if (payload.data != null && payload.data != undefined) payload.data else payload
    val live = rowsOf(data.live)
    val indexRows = rowsOf(data.index)
    val index = if (indexRows.isNotEmpty()) indexRows.first() else data.index
    val indexValue = number(pick(index, "currentValue", "latest", "current", "value", "indexValue", "close"))
    val indexChange = number(pick(index, "change", "pointChange", "changeValue", "difference"))
    val indexPct = number(pick(index, "perChange", "percentChange", "percentageChange", "changePercent", "pChange"))
    val summary = data.summary
    val marketOpen = data.marketOpen
    val marketLabel = when {
        marketOpen == true -> "MARKET OPEN"
        marketOpen == false -> "MARKET CLOSED"
        else -> "MARKET STATUS"
    }

    root.innerHTML = ""
    val shell = el("main", "shell")
    val header = el("header", "topbar")
    val identity = el("div", "identity")
    identity.appendChild(el("div", "brand-mark", "P"))
    val brandText = el("div", "brand-copy")
    brandText.appendChild(el("div", "brand", "NEPSE PULSE"))
    brandText.appendChild(el("div", "tagline", "NEPAL STOCK MARKET INTELLIGENCE"))
    identity.appendChild(brandText)
    header.appendChild(identity)
    val state = el("div", "live-state")
    state.appendChild(el("span", "pulse-dot"))
    state.appendChild(el("span", "live-label", if (live.isNotEmpty()) "LIVE DATA" else "CONNECTING"))
    header.appendChild(state)
    shell.appendChild(header)

    val hero = el("section", "hero")
    val heroCopy = el("div", "hero-copy")
    heroCopy.appendChild(el("div", "eyebrow", "MARKET OVERVIEW  /  NEPAL"))
    heroCopy.appendChild(el("h1", "", "Your market.\nIn focus."))
    heroCopy.appendChild(el("p", "subhead", "A clearer view of NEPSE — live market activity, stock movements and the signals behind them."))
    hero.appendChild(heroCopy)
    val indexCard = el("div", "index-card")
    indexCard.appendChild(el("div", "card-kicker", "NEPSE INDEX"))
    indexCard.appendChild(el("div", "index-value", fmt(indexValue)))
    val change = el("div", if ((indexChange ?: 0.0) >= 0) "positive index-change" else "negative index-change",
        (if ((indexChange ?: 0.0) > 0) "+" else "") + (indexChange?.let { fixed(it) } ?: "—") + "  ·  " + pct(indexPct))
    indexCard.appendChild(change)
    indexCard.appendChild(el("div", "market-state", marketLabel))
    hero.appendChild(indexCard)
    shell.appendChild(hero)

    val metrics = el("section", "metric-grid")
    fun metric(label: String, value: String, detail: String, tone: String = "") {
        val card = el("article", "metric-card $tone")
        card.appendChild(el("div", "metric-label", label))
        card.appendChild(el("div", "metric-value", value))
        card.appendChild(el("div", "metric-detail", detail))
        metrics.appendChild(card)
    }
    metric("MARKET MOVERS", live.size.toString(), "Live symbols received")
    val breadthCounts = breadth(live)
    metric("ADVANCING", breadthCounts.first?.toString() ?: "—", if (breadthCounts.first != null) "Verified from session prices" else "No verified price pairs", "positive")
    metric("DECLINING", breadthCounts.second?.toString() ?: "—", if (breadthCounts.second != null) "Verified from session prices" else "No verified price pairs", "negative")
    val turnover = number(pick(summary, "totalTurnover", "turnover", "totalAmount"))
        ?: summaryValue(summary, "turnover", "total traded amount")
    metric("TURNOVER", turnover?.let { "Rs. " + fmt(it) } ?: "—", "Provider-reported session value")
    shell.appendChild(metrics)

    val sectionHead = el("div", "section-head")
    val sectionTitle = el("div")
    sectionTitle.appendChild(el("div", "eyebrow", "MARKET WATCH"))
    sectionTitle.appendChild(el("h2", "", "Live market"))
    sectionHead.appendChild(sectionTitle)
    val searchWrap = el("label", "search-wrap")
    val searchInput = document.createElement("input") as HTMLInputElement
    searchInput.type = "search"
    searchInput.placeholder = "Search symbol or company"
    searchInput.value = search
    searchWrap.appendChild(searchInput)
    sectionHead.appendChild(searchWrap)
    shell.appendChild(sectionHead)

    val tableCard = el("section", "table-card")
    val table = el("div", "market-table")
    val tableHeader = el("div", "table-row table-header")
    listOf("SYMBOL / COMPANY", "LTP", "CHANGE", "% CHANGE", "VOLUME").forEach {
        tableHeader.appendChild(el("div", "table-cell", it))
    }
    table.appendChild(tableHeader)
    val filtered = live.filter { row ->
        val symbol = pick(row, "symbol", "stockSymbol", "ticker", "code")?.toString() ?: ""
        val company = pick(row, "company", "companyName", "name", "securityName")?.toString() ?: ""
        (symbol + " " + company).contains(search, ignoreCase = true)
    }.take(60)
    filtered.forEach { row ->
        val symbol = pick(row, "symbol", "stockSymbol", "ticker", "code")?.toString() ?: "—"
        val company = pick(row, "company", "companyName", "name", "securityName")?.toString() ?: ""
        val ltp = number(pick(row, "ltp", "lastTradedPrice", "close", "price"))
        val delta = number(pick(row, "change", "pointChange", "changeValue"))
        val changePct = number(pick(row, "percentChange", "percentageChange", "changePercent", "perChange"))
        val volume = number(pick(row, "volume", "tradedShares", "quantity"))
        val rowNode = el("div", "table-row")
        val nameCell = el("div", "table-cell stock-name")
        nameCell.appendChild(el("strong", "", symbol))
        nameCell.appendChild(el("small", "", company))
        rowNode.appendChild(nameCell)
        rowNode.appendChild(el("div", "table-cell numeric", fmt(ltp)))
        rowNode.appendChild(el("div", "table-cell numeric " + if ((delta ?: 0.0) >= 0) "positive" else "negative",
            delta?.let { (if (it > 0) "+" else "") + fixed(it) } ?: "—"))
        rowNode.appendChild(el("div", "table-cell numeric " + if ((changePct ?: 0.0) >= 0) "positive" else "negative", pct(changePct)))
        rowNode.appendChild(el("div", "table-cell numeric", fmt(volume)))
        table.appendChild(rowNode)
    }
    if (filtered.isEmpty()) table.appendChild(el("div", "empty-state", if (live.isEmpty()) "Market data is not available yet. Retrying automatically…" else "No symbols match your search."))
    tableCard.appendChild(table)
    shell.appendChild(tableCard)

    val footer = el("footer", "footer")
    footer.appendChild(el("span", "", "NEPSE PULSE  ·  MARKET DATA PROVIDED BY CONNECTED FEEDS"))
    footer.appendChild(el("span", "", "Auto refresh: 30 seconds"))
    shell.appendChild(footer)
    root.appendChild(shell)

    searchInput.addEventListener("input", { _ -> render(root, payload, searchInput.value) })
}

private fun showError(root: HTMLElement, message: String) {
    root.innerHTML = ""
    val shell = el("main", "shell")
    shell.appendChild(el("div", "brand", "NEPSE PULSE"))
    val panel = el("section", "error-panel")
    panel.appendChild(el("h1", "", "Connecting to the market"))
    panel.appendChild(el("p", "", "The Render market service did not return data. The app will retry automatically."))
    panel.appendChild(el("small", "", message))
    shell.appendChild(panel)
    root.appendChild(shell)
}

private fun refresh(root: HTMLElement) {
    val url = "$API_BASE/api/market?force=true"
    val request = js("window.fetch(url, { cache: 'no-store', headers: { 'Accept': 'application/json' } })") as Promise<dynamic>
    request.then { response: dynamic ->
        if (response.ok != true) throw IllegalStateException("Market API HTTP " + response.status.toString())
        response.json() as Promise<dynamic>
    }.then { payload: dynamic ->
        if (payload.ok == false) throw IllegalStateException(pick(payload, "error")?.toString() ?: "Market service returned an error")
        render(root, payload)
        null
    }.catch { error: Throwable ->
        showError(root, error.message ?: "Network error")
        null
    }
}

fun main() {
    val root = document.getElementById("app") as? HTMLElement ?: return
    refresh(root)
    window.setInterval({ refresh(root) }, 30000)
}
