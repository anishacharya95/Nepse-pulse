import org.w3c.dom.HTMLElement
import kotlin.math.abs

fun renderTrades(target: HTMLElement, rows: List<dynamic>, filter: String) {
    val trades = rows.filter { filter.isBlank() || (text(pick(it, "symbol")) ?: "").contains(filter, true) }
    val pageSize = 100
    var page = 0

    fun paintPage() {
        target.innerHTML = ""
        val table = dataTable(listOf("SYMBOL", "BUYER", "SELLER", "QTY", "RATE", "AMOUNT", "TIME"), setOf(3, 4, 5, 6))
        if (trades.isEmpty()) {
            table.empty("No contracts match this filter.")
        } else {
            val startIndex = page * pageSize
            for (trade in trades.drop(startIndex).take(pageSize)) {
                val symbol = text(pick(trade, "symbol"))
                table.row(listOf(
                    el("div", "symbol-cell", symbol ?: "—"),
                    el("div", "small", text(pick(trade, "buyerBrokerName", "buyerBroker")) ?: "—"),
                    el("div", "small", text(pick(trade, "sellerBrokerName", "sellerBroker")) ?: "—"),
                    el("div", "mono", integer(num(pick(trade, "quantity", "contractQuantity")))),
                    el("div", "mono", price(num(pick(trade, "rate", "contractRate")))),
                    el("div", "mono", rupees(num(pick(trade, "amount", "contractAmount")))),
                    el("div", "mono small", clockTime(text(pick(trade, "tradeTime"))))
                )) { if (symbol != null) openSymbol(symbol) }
            }
        }
        target.appendChild(table.root.parentElement as HTMLElement)
        if (trades.isNotEmpty()) {
            val totalPages = ((trades.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            val controls = el("div", "toolbar pagination-controls")
            controls.appendChild(el("span", "muted small", "Showing " + (page * pageSize + 1) + "–" + minOf((page + 1) * pageSize, trades.size) + " of " + trades.size + " trades"))
            controls.appendChild(actionButton("Previous", "ghost", "chevron-left") {
                if (page > 0) { page--; paintPage() }
            })
            controls.appendChild(actionButton("Next", "ghost", "chevron-right") {
                if (page + 1 < totalPages) { page++; paintPage() }
            })
            target.appendChild(controls)
        }
    }
    paintPage()
}

fun renderBrokerAggregates(target: HTMLElement, rows: List<dynamic>, filter: String) {
    val flows = brokerFlows(rows).filter {
        filter.isBlank() || it.name.uppercase().contains(filter) || it.id.contains(filter)
    }
    val table = dataTable(listOf("BROKER", "BUY VALUE", "SELL VALUE", "NET FLOW", "CONTRACTS"), setOf(1, 2, 3, 4))
    if (flows.isEmpty()) {
        table.empty("No broker aggregation available for this filter.")
    } else {
        val maxTotal = flows.maxOf { it.total }.coerceAtLeast(1.0)
        for (flow in flows.take(40)) {
            val nameCell = el("div", "broker-cell")
            nameCell.appendChild(el("strong", "", "Broker " + flow.id))
            nameCell.appendChild(el("small", "", flow.name.take(38)))
            nameCell.appendChild(progressBar(flow.total / maxTotal, "accent"))
            table.row(listOf(
                nameCell,
                el("div", "mono", rupees(flow.buy)),
                el("div", "mono", rupees(flow.sell)),
                el("div", "mono tone-" + toneOf(flow.net), rupees(flow.net)),
                el("div", "mono", integer(flow.trades.toDouble()))
            ))
        }
    }
    target.appendChild(table.root.parentElement as HTMLElement)
    val concentration = card("concentration-card")
    concentration.appendChild(cardHead("Concentration", "Share of reported contract value handled by the most active brokers"))
    val total = flows.sumOf { it.total }
    val topFive = flows.take(5).sumOf { it.total }
    val share = if (total > 0) topFive / total else 0.0
    concentration.appendChild(el("div", "big-share", fixed(share * 100.0, 1) + "%"))
    concentration.appendChild(progressBar(share, "accent"))
    concentration.appendChild(el("p", "muted small", "The five most active brokers account for " + fixed(share * 100.0, 1) + "% of reported broker turnover in this floorsheet sample."))
    target.appendChild(concentration)
}

fun renderBulk(target: HTMLElement, rows: List<dynamic>, filter: String) {
    val bulk = rows.filter {
        val matches = filter.isBlank() || (text(pick(it, "symbol")) ?: "").contains(filter, true)
        val amount = num(pick(it, "amount", "contractAmount")) ?: 0.0
        val quantity = num(pick(it, "quantity", "contractQuantity")) ?: 0.0
        matches && (amount >= 5_000_000.0 || quantity >= 10_000.0)
    }.sortedByDescending { num(pick(it, "amount")) ?: 0.0 }
    val table = dataTable(listOf("SYMBOL", "BUYER", "SELLER", "QTY", "RATE", "AMOUNT"), setOf(3, 4, 5))
    if (bulk.isEmpty()) {
        table.empty("No contracts above the bulk threshold (Rs 50 lakh or 10,000 shares) in this sample.")
    } else {
        for (trade in bulk.take(60)) {
            table.row(listOf(
                el("div", "symbol-cell", text(pick(trade, "symbol")) ?: "—"),
                el("div", "small", text(pick(trade, "buyerBrokerName", "buyerBroker")) ?: "—"),
                el("div", "small", text(pick(trade, "sellerBrokerName", "sellerBroker")) ?: "—"),
                el("div", "mono", integer(num(pick(trade, "quantity")))),
                el("div", "mono", price(num(pick(trade, "rate")))),
                el("div", "mono", rupees(num(pick(trade, "amount"))))
            ))
        }
    }
    target.appendChild(table.root.parentElement as HTMLElement)
}

fun renderUnusual(target: HTMLElement, rows: List<dynamic>, filter: String) {
    val averages = HashMap<String, Pair<Double, Int>>()
    for (trade in rows) {
        val symbol = text(pick(trade, "symbol")) ?: continue
        val rate = num(pick(trade, "rate")) ?: continue
        val current = averages[symbol]
        averages[symbol] = if (current == null) Pair(rate, 1) else Pair(current.first + rate, current.second + 1)
    }
    val unusual = rows.mapNotNull { trade ->
        val symbol = text(pick(trade, "symbol")) ?: return@mapNotNull null
        if (filter.isNotBlank() && !symbol.contains(filter, true)) return@mapNotNull null
        val rate = num(pick(trade, "rate")) ?: return@mapNotNull null
        val average = averages[symbol] ?: return@mapNotNull null
        val mean = average.first / average.second
        if (mean <= 0.0) return@mapNotNull null
        val deviation = (rate - mean) / mean * 100.0
        if (abs(deviation) < 2.5) null else Pair(trade, deviation)
    }.sortedByDescending { abs(it.second) }
    val table = dataTable(listOf("SYMBOL", "RATE", "SESSION AVG", "DEVIATION", "AMOUNT"), setOf(1, 2, 3, 4))
    if (unusual.isEmpty()) {
        table.empty("No contract deviates more than 2.5% from its security's average traded rate in this sample.")
    } else {
        for (entry in unusual.take(60)) {
            val trade = entry.first
            val deviation = entry.second
            val symbol = text(pick(trade, "symbol"))
            val average = symbol?.let { averages[it] }
            table.row(listOf(
                el("div", "symbol-cell", symbol ?: "—"),
                el("div", "mono", price(num(pick(trade, "rate")))),
                el("div", "mono", price(average?.let { it.first / it.second })),
                el("div", "mono tone-" + toneOf(deviation), percent(deviation)),
                el("div", "mono", rupees(num(pick(trade, "amount"))))
            ))
        }
    }
    target.appendChild(table.root.parentElement as HTMLElement)
}

fun floorsheetScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "MARKET FLOW",
        "Floorsheet & brokers",
        "Executed contracts, broker concentration and unusual activity from the NEPSE floorsheet."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var tab = "trades"
    var filter = ""

    val tableHost = el("div", "table-card")
    val metaLine = el("p", "muted small", "Loading contracts…")
    val searchInput = field("Filter by symbol or broker", "search", "")
    val tabs = el("div", "tabs")

    fun paintTable() {
        if (FloorsheetStore.rows.isEmpty()) return
        tableHost.innerHTML = ""
        when (tab) {
            "brokers" -> renderBrokerAggregates(tableHost, FloorsheetStore.rows, filter)
            "bulk" -> renderBulk(tableHost, FloorsheetStore.rows, filter)
            "unusual" -> renderUnusual(tableHost, FloorsheetStore.rows, filter)
            else -> renderTrades(tableHost, FloorsheetStore.rows, filter)
        }
    }

    fun paintTabs() {
        tabs.innerHTML = ""
        for ((key, label) in listOf(
            "trades" to "Trades",
            "brokers" to "Brokers",
            "bulk" to "Bulk & block",
            "unusual" to "Unusual"
        )) {
            tabs.appendChild(chipButton(label, tab == key) {
                tab = key
                paintTabs()
                paintTable()
            })
        }
    }

    searchInput.addEventListener("input", {
        filter = searchInput.value.trim().uppercase()
        paintTable()
    })

    fun load(force: Boolean = false) {
        body.innerHTML = ""
        body.appendChild(loadingState("Loading the executed floorsheet…"))
        loadFloorsheet(force) { ok, error ->
            body.innerHTML = ""
            if (!ok) {
                body.appendChild(errorState(error ?: "Floorsheet is unavailable.") { load(true) })
                return@loadFloorsheet
            }
            val wrap = el("div", "floorsheet")
            paintTabs()
            wrap.appendChild(tabs)

            val toolbar = card("toolbar-card")
            val row = el("div", "toolbar")
            row.appendChild(searchInput)
            row.appendChild(actionButton("Refresh", "ghost", "refresh") { load(true) })
            toolbar.appendChild(row)
            metaLine.textContent = integer(FloorsheetStore.rows.size.toDouble()) + " contracts" +
                (if (FloorsheetStore.businessDate.isNotBlank()) " · business date " + shortDate(FloorsheetStore.businessDate) else "") +
                " · source NEPSE floorsheet"
            toolbar.appendChild(metaLine)
            wrap.appendChild(toolbar)
            wrap.appendChild(tableHost)
            body.appendChild(wrap)
            paintTable()
        }
    }
    load()
    registerRefresh { }
    return root
}
