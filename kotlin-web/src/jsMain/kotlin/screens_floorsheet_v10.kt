import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import kotlin.math.max
import kotlin.math.min

// Complete NEPSE floorsheet: true server-side paging for browsing, and a bounded
// full-day scan when advanced filters or sorting need the whole session.
// Every row comes from /api/floorsheet-cache; nothing is mocked.

data class FsTrade(
    val contract: String,
    val symbol: String,
    val buyerId: String,
    val buyer: String,
    val sellerId: String,
    val seller: String,
    val quantity: Double,
    val rate: Double,
    val amount: Double,
    val date: String,
    val time: String
)

data class FsFilters(
    val symbol: String,
    val contract: String,
    val buyer: String,
    val seller: String,
    val minRate: Double?,
    val minQty: Double?,
    val minAmount: Double?
)

data class FsPage(val rows: List<FsTrade>, val total: Int, val businessDate: String)

fun fsTradeOf(row: dynamic): FsTrade {
    val raw = row.raw
    fun value(vararg keys: String): dynamic {
        val direct = pick(row, *keys)
        return if (direct != null) direct else pick(raw, *keys)
    }
    return FsTrade(
        contract = text(value("trade", "contractId", "tradeBookId", "contract")) ?: "",
        symbol = (text(value("symbol", "stockSymbol")) ?: "").uppercase(),
        buyerId = text(value("buyerBrokerId", "buyerMemberId")) ?: "",
        buyer = text(value("buyerBrokerName", "buyerBroker")) ?: "",
        sellerId = text(value("sellerBrokerId", "sellerMemberId")) ?: "",
        seller = text(value("sellerBrokerName", "sellerBroker")) ?: "",
        quantity = num(value("quantity", "contractQuantity")) ?: 0.0,
        rate = num(value("rate", "contractRate")) ?: 0.0,
        amount = num(value("amount", "contractAmount")) ?: 0.0,
        date = text(value("businessDate")) ?: "",
        time = text(value("tradeTime")) ?: ""
    )
}

fun loadFsPage(
    offset: Int,
    limit: Int,
    date: String,
    symbol: String,
    done: (FsPage?, String?) -> Unit
) {
    var path = "/api/floorsheet-cache?limit=" + limit + "&offset=" + offset
    if (date.isNotBlank()) path += "&business_date=" + encode(date)
    if (symbol.isNotBlank()) path += "&symbol=" + encode(symbol)
    Http.get(path, ttlMs = 25_000.0, timeoutMs = 120_000, onData = { payload ->
        val rows = rowsOf(payload?.data)
        if (rows.isEmpty()) {
            done(null, "The provider returned no executed contracts for this selection.")
            return@get
        }
        val total = num(pick(payload, "total"))?.toInt() ?: rows.size
        val businessDate = text(pick(payload, "businessDate")) ?: date
        done(FsPage(rows.map { fsTradeOf(it) }, total, businessDate), null)
    }, onError = { message ->
        done(null, message)
    })
}

fun scanFsDay(date: String, onProgress: (Int, Int) -> Unit, done: (List<FsTrade>, String?) -> Unit) {
    val collected = LinkedHashMap<String, FsTrade>()
    var offset = 0
    var total = 0
    var guard = 0
    var emptyStreak = 0

    fun step() {
        loadFsPage(offset, 5000, date, "") { page, error ->
            // The provider occasionally repeats rows across offsets and can report
            // an inconsistent running total. Keep paging while each page still
            // adds new contracts instead of trusting the total alone.
            if (page == null) {
                if (collected.isNotEmpty() && offset < total && guard < 60) {
                    offset += 5000
                    guard += 1
                    onProgress(collected.size, total)
                    step()
                    return@loadFsPage
                }
                done(collected.values.toList(), if (collected.isEmpty()) error else null)
                return@loadFsPage
            }
            val before = collected.size
            total = max(total, page.total)
            for (row in page.rows) {
                val key = row.contract + "|" + row.symbol + "|" + row.rate.toString() + "|" +
                    row.quantity.toString() + "|" + row.time
                if (!collected.containsKey(key)) collected[key] = row
            }
            val added = collected.size - before
            emptyStreak = if (added == 0) emptyStreak + 1 else 0
            offset += 5000
            guard += 1
            onProgress(collected.size, total)
            // Page on progress rather than the reported total: the provider can
            // repeat rows at an offset and report a shrinking total mid-ingest.
            val hasMore = (added > 0 || page.rows.size >= 5000) && emptyStreak < 2
            if (hasMore && guard < 63) step() else done(collected.values.toList(), null)
        }
    }
    step()
}

private fun fsMatches(trade: FsTrade, filters: FsFilters): Boolean {
    if (filters.symbol.isNotBlank() && trade.symbol != filters.symbol) return false
    if (filters.contract.isNotBlank() && !trade.contract.contains(filters.contract)) return false
    if (filters.buyer.isNotBlank() &&
        !trade.buyer.lowercase().contains(filters.buyer) && !trade.buyerId.contains(filters.buyer)
    ) return false
    if (filters.seller.isNotBlank() &&
        !trade.seller.lowercase().contains(filters.seller) && !trade.sellerId.contains(filters.seller)
    ) return false
    if (filters.minRate != null && trade.rate < filters.minRate) return false
    if (filters.minQty != null && trade.quantity < filters.minQty) return false
    if (filters.minAmount != null && trade.amount < filters.minAmount) return false
    return true
}

private fun fsSorted(trades: List<FsTrade>, sort: String): List<FsTrade> {
    if (sort.isBlank()) return trades
    val ascending = sort.endsWith("-asc")
    val sorted = when (sort.substringBeforeLast('-')) {
        "amount" -> trades.sortedBy { it.amount }
        "quantity" -> trades.sortedBy { it.quantity }
        "rate" -> trades.sortedBy { it.rate }
        else -> trades
    }
    return if (ascending) sorted else sorted.reversed()
}

data class FsSymbolStat(
    val symbol: String,
    var trades: Int = 0,
    var quantity: Double = 0.0,
    var amount: Double = 0.0,
    var minRate: Double = Double.MAX_VALUE,
    var maxRate: Double = 0.0
) {
    val averageRate: Double get() = if (quantity > 0.0) amount / quantity else 0.0
}

fun fsSymbolStats(trades: List<FsTrade>): List<FsSymbolStat> {
    val map = LinkedHashMap<String, FsSymbolStat>()
    for (trade in trades) {
        val stat = map.getOrPut(trade.symbol) { FsSymbolStat(trade.symbol) }
        stat.trades += 1
        stat.quantity += trade.quantity
        stat.amount += trade.amount
        if (trade.rate > 0.0) {
            stat.minRate = min(stat.minRate, trade.rate)
            stat.maxRate = max(stat.maxRate, trade.rate)
        }
    }
    return map.values.sortedByDescending { it.amount }
}

fun fsBrokerFlows(trades: List<FsTrade>): List<BrokerFlow> {
    val merged = LinkedHashMap<String, BrokerFlow>()
    for (trade in trades) {
        val buyerId = trade.buyerId.ifBlank { "?" }
        val sellerId = trade.sellerId.ifBlank { "?" }
        val buyEntry = merged[buyerId]
        merged[buyerId] = if (buyEntry == null) {
            BrokerFlow(buyerId, trade.buyer.ifBlank { "Broker " + buyerId }, trade.amount, 0.0, 1)
        } else {
            BrokerFlow(buyEntry.id, buyEntry.name, buyEntry.buy + trade.amount, buyEntry.sell, buyEntry.trades + 1)
        }
        val sellEntry = merged[sellerId]
        merged[sellerId] = if (sellEntry == null) {
            BrokerFlow(sellerId, trade.seller.ifBlank { "Broker " + sellerId }, 0.0, trade.amount, 1)
        } else {
            BrokerFlow(sellEntry.id, sellEntry.name, sellEntry.buy, sellEntry.sell + trade.amount, sellEntry.trades + 1)
        }
    }
    return merged.values.sortedByDescending { it.total }
}

fun floorsheetV10Screen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "MARKET FLOW",
        "Floorsheet",
        "Every executed contract stored for the selected trading date with server-side paging, filters, sorting and full-day stock and broker analysis.",
        listOf(actionButton("Broker analytics", "secondary", "brokers") { go("brokers") })
    ))
    root.appendChild(body)

    var date = ""
    var pageSize = 25
    var page = 1
    var total = 0
    var pageRows: List<FsTrade> = emptyList()
    var scanned: List<FsTrade> = emptyList()
    var busy = false
    var scannedDate = ""
    var reload: () -> Unit = {}
    var paint: () -> Unit = {}

    val statusLine = el("p", "muted small", "Loading the executed floorsheet…")
    val tableHost = el("div", "fs-table")
    val pagerHost = el("div", "pager")
    val analysisHost = el("div", "fs-analysis")
    val rowCountLabel = el("span", "pager-count", "")

    val dateInput = document.createElement("input") as HTMLInputElement
    dateInput.type = "date"
    dateInput.className = "field"
    val sizeSelect = selectField(
        listOf("25" to "25 per page", "50" to "50 per page", "100" to "100 per page"),
        "25",
        { value ->
            pageSize = value.toIntOrNull() ?: 25
            page = 1
            paint()
        }
    )
    val symbolInput = field("Symbol e.g. NABIL", "search", "")
    val contractInput = field("Contract id", "search", "")
    val buyerInput = field("Buyer broker", "search", "")
    val sellerInput = field("Seller broker", "search", "")
    val minRateInput = field("Min rate", "number", "")
    val minQtyInput = field("Min quantity", "number", "")
    val minAmountInput = field("Min amount", "number", "")
    val sortSelect = selectField(
        listOf(
            "" to "Provider order",
            "amount-desc" to "Amount — high to low",
            "amount-asc" to "Amount — low to high",
            "quantity-desc" to "Quantity — high to low",
            "quantity-asc" to "Quantity — low to high",
            "rate-desc" to "Rate — high to low",
            "rate-asc" to "Rate — low to high"
        ),
        "",
        { page = 1; reload() }
    )

    fun filters(): FsFilters = FsFilters(
        symbol = symbolInput.value.trim().uppercase(),
        contract = contractInput.value.trim(),
        buyer = buyerInput.value.trim().lowercase(),
        seller = sellerInput.value.trim().lowercase(),
        minRate = num(minRateInput.value),
        minQty = num(minQtyInput.value),
        minAmount = num(minAmountInput.value)
    )

    fun hasAdvanced(f: FsFilters): Boolean =
        f.contract.isNotBlank() || f.buyer.isNotBlank() || f.seller.isNotBlank() ||
            f.minRate != null || f.minQty != null || f.minAmount != null

    fun paintTable() {
        tableHost.innerHTML = ""
        val table = dataTable(listOf("CONTRACT", "SYMBOL", "BUYER", "SELLER", "QTY", "RATE", "AMOUNT", "TIME"), setOf(4, 5, 6, 7))
        if (pageRows.isEmpty()) {
            table.empty("No contracts match this selection.")
        } else {
            for (trade in pageRows) {
                val buyerCell = el("div", "small")
                buyerCell.appendChild(el("strong", "", if (trade.buyerId.isBlank()) "—" else trade.buyerId))
                buyerCell.appendChild(el("span", "broker-name", trade.buyer.ifBlank { "Unknown broker" }))
                val sellerCell = el("div", "small")
                sellerCell.appendChild(el("strong", "", if (trade.sellerId.isBlank()) "—" else trade.sellerId))
                sellerCell.appendChild(el("span", "broker-name", trade.seller.ifBlank { "Unknown broker" }))
                table.row(listOf(
                    el("div", "mono small", trade.contract.ifBlank { "—" }),
                    el("div", "symbol-cell", trade.symbol.ifBlank { "—" }),
                    buyerCell,
                    sellerCell,
                    el("div", "mono", integer(trade.quantity)),
                    el("div", "mono", price(trade.rate)),
                    el("div", "mono", rupees(trade.amount)),
                    el("div", "mono small", clockTime(trade.time))
                )) { if (trade.symbol.isNotBlank()) openSymbol(trade.symbol) }
            }
        }
        tableHost.appendChild(table.root.parentElement as HTMLElement)
    }

    fun paintPager() {
        pagerHost.innerHTML = ""
        val pages = max(1, (total + pageSize - 1) / pageSize)
        page = page.coerceIn(1, pages)
        rowCountLabel.textContent = integer(total.toDouble()) + " contracts · page " + page + " of " + pages
        pagerHost.appendChild(rowCountLabel)
        pagerHost.appendChild(actionButton("First", "ghost", null) {
            if (page != 1) { page = 1; paint() }
        })
        pagerHost.appendChild(actionButton("Previous", "ghost", null) {
            if (page > 1) { page -= 1; paint() }
        })
        pagerHost.appendChild(actionButton("Next", "ghost", null) {
            if (page < pages) { page += 1; paint() }
        })
        pagerHost.appendChild(actionButton("Last", "ghost", null) {
            if (page != pages) { page = pages; paint() }
        })
        val jump = field("Page", "number", page.toString())
        jump.className = "field pager-jump"
        jump.addEventListener("keydown", { event ->
            if (eventKey(event) == "Enter") {
                page = (jump.value.toIntOrNull() ?: page).coerceIn(1, pages)
                paint()
            }
        })
        pagerHost.appendChild(jump)
    }

    paint = paintNow@{
        if (scanned.isNotEmpty()) {
            val filtered = fsSorted(scanned.filter { fsMatches(it, filters()) }, sortSelect.value)
            total = filtered.size
            val pages = max(1, (total + pageSize - 1) / pageSize)
            page = page.coerceIn(1, pages)
            pageRows = filtered.drop((page - 1) * pageSize).take(pageSize)
            paintTable()
            paintPager()
            return@paintNow
        }
        if (busy) return@paintNow
        busy = true
        val f = filters()
        statusLine.textContent = "Loading contracts…"
        loadFsPage((page - 1) * pageSize, pageSize, date, f.symbol) { result, error ->
            busy = false
            if (result == null) {
                tableHost.innerHTML = ""
                tableHost.appendChild(errorState(error ?: "Floorsheet unavailable.") { reload() })
                pagerHost.innerHTML = ""
                statusLine.textContent = error ?: "Floorsheet unavailable."
                return@loadFsPage
            }
            total = result.total
            pageRows = result.rows
            if (date.isBlank() && result.businessDate.isNotBlank()) {
                date = result.businessDate
                dateInput.value = result.businessDate
            }
            if (scannedDate.isBlank()) scannedDate = result.businessDate
            statusLine.textContent = "Business date " + (result.businessDate.ifBlank { date.ifBlank { "latest" } }) +
                " · server page " + page + " · " + integer(result.total.toDouble()) + " records for the session"
            paintTable()
            paintPager()
        }
    }

    fun scan(f: FsFilters, then: (List<FsTrade>) -> Unit) {
        if (busy) return
        busy = true
        statusLine.textContent = "Scanning the full trading day…"
        scanFsDay(date.ifBlank { scannedDate }, { fetched, available ->
            statusLine.textContent = "Scanned " + integer(fetched.toDouble()) + " of " +
                (if (available > 0) integer(available.toDouble()) else "?") + " contracts…"
        }) { rows, error ->
            busy = false
            if (rows.isEmpty()) {
                tableHost.innerHTML = ""
                tableHost.appendChild(errorState(error ?: "The full-day scan returned no contracts.") { reload() })
                statusLine.textContent = error ?: "No contracts returned."
                return@scanFsDay
            }
            scanned = rows
            statusLine.textContent = integer(rows.size.toDouble()) + " contracts scanned for the session."
            then(rows)
        }
    }

    fun applyFilters() {
        page = 1
        val f = filters()
        if (hasAdvanced(f) || sortSelect.value.isNotBlank()) {
            scan(f) {
                paint()
            }
        } else {
            scanned = emptyList()
            paint()
        }
    }

    reload = {
        scanned = emptyList()
        paint()
    }

    fun renderAnalysis(rows: List<FsTrade>) {
        analysisHost.innerHTML = ""
        val symbols = fsSymbolStats(rows)
        val brokers = fsBrokerFlows(rows)
        val totalAmount = rows.sumOf { it.amount }
        val totalQuantity = rows.sumOf { it.quantity }

        val head = card()
        head.appendChild(cardHead("Session analysis", "Aggregated from " + integer(rows.size.toDouble()) + " scanned contracts"))
        val stats = el("div", "stat-grid")
        stats.appendChild(statTile("Contracts", integer(rows.size.toDouble()), "Full day"))
        stats.appendChild(statTile("Shares", integer(totalQuantity), "Total quantity"))
        stats.appendChild(statTile("Turnover", rupees(totalAmount), "Contract amount"))
        stats.appendChild(statTile("Securities", integer(symbols.size.toDouble()), "Symbols traded"))
        stats.appendChild(statTile("Brokers", integer(brokers.size.toDouble()), "Active members"))
        head.appendChild(stats)
        analysisHost.appendChild(head)

        val symbolCard = card()
        symbolCard.appendChild(cardHead("Stock analysis", "Top securities by traded value", actionButton("Compare", "ghost", "chart") { go("compare") }))
        val symbolTable = dataTable(listOf("SYMBOL", "TRADES", "QTY", "AMOUNT", "AVG RATE", "LOW", "HIGH"), setOf(1, 2, 3, 4, 5, 6))
        for (stat in symbols.take(20)) {
            symbolTable.row(listOf(
                el("div", "symbol-cell", stat.symbol),
                el("div", "mono", integer(stat.trades.toDouble())),
                el("div", "mono", integer(stat.quantity)),
                el("div", "mono", rupees(stat.amount)),
                el("div", "mono", price(stat.averageRate)),
                el("div", "mono", if (stat.minRate == Double.MAX_VALUE) "—" else price(stat.minRate)),
                el("div", "mono", if (stat.maxRate == 0.0) "—" else price(stat.maxRate))
            )) { openSymbol(stat.symbol) }
        }
        symbolCard.appendChild(symbolTable.root.parentElement as HTMLElement)
        analysisHost.appendChild(symbolCard)

        val brokerCard = card()
        brokerCard.appendChild(cardHead("Broker analysis", "Buy value, sell value and net flow"))
        val brokerTable = dataTable(listOf("BROKER", "NAME", "BUY", "SELL", "NET FLOW", "CONTRACTS"), setOf(2, 3, 4, 5))
        for (flow in brokers.take(20)) {
            brokerTable.row(listOf(
                el("div", "mono", flow.id),
                el("div", "small", flow.name.take(34)),
                el("div", "mono", rupees(flow.buy)),
                el("div", "mono", rupees(flow.sell)),
                el("div", "mono tone-" + toneOf(flow.net), rupees(flow.net)),
                el("div", "mono", integer(flow.trades.toDouble()))
            ))
        }
        brokerCard.appendChild(brokerTable.root.parentElement as HTMLElement)
        analysisHost.appendChild(brokerCard)
        analysisHost.appendChild(el("p", "muted tiny",
            "Analysis aggregates every contract the full-day scan returned, deduplicated, not only the visible page. The provider repeats rows at high offsets, so the scanned count can be below the session total. Figures are contract amounts reported by the exchange."))
    }

    fun analyse() {
        if (busy) return
        // Reuse a scan the filters or sorting already produced for this day.
        if (scanned.isNotEmpty()) {
            renderAnalysis(scanned)
            return
        }
        scan(filters()) { rows -> renderAnalysis(rows) }
    }

    val toolbar = card("toolbar-card")
    val topRow = el("div", "toolbar")
    topRow.appendChild(dateInput)
    topRow.appendChild(sizeSelect)
    topRow.appendChild(actionButton("Latest session", "primary", "refresh") {
        date = ""
        dateInput.value = ""
        page = 1
        scanned = emptyList()
        paint()
    })
    topRow.appendChild(actionButton("Refresh", "ghost", "refresh") {
        Http.clear()
        paint()
    })
    toolbar.appendChild(topRow)

    val filterRow = el("div", "toolbar toolbar-filters")
    filterRow.appendChild(symbolInput)
    filterRow.appendChild(contractInput)
    filterRow.appendChild(buyerInput)
    filterRow.appendChild(sellerInput)
    filterRow.appendChild(minRateInput)
    filterRow.appendChild(minQtyInput)
    filterRow.appendChild(minAmountInput)
    toolbar.appendChild(filterRow)

    val actionRow = el("div", "toolbar")
    actionRow.appendChild(sortSelect)
    actionRow.appendChild(actionButton("Apply filters", "primary", "filter") { applyFilters() })
    actionRow.appendChild(actionButton("Clear", "ghost", null) {
        symbolInput.value = ""
        contractInput.value = ""
        buyerInput.value = ""
        sellerInput.value = ""
        minRateInput.value = ""
        minQtyInput.value = ""
        minAmountInput.value = ""
        sortSelect.value = ""
        scanned = emptyList()
        page = 1
        paint()
    })
    actionRow.appendChild(actionButton("Analyse full day", "secondary", "chart") { analyse() })
    toolbar.appendChild(actionRow)
    toolbar.appendChild(statusLine)
    body.appendChild(toolbar)
    body.appendChild(el("div", "table-card").add(tableHost))
    body.appendChild(pagerHost)
    body.appendChild(analysisHost)

    for (input in listOf(symbolInput, contractInput, buyerInput, sellerInput, minRateInput, minQtyInput, minAmountInput)) {
        input.addEventListener("keydown", { event ->
            if (eventKey(event) == "Enter") applyFilters()
        })
    }
    dateInput.addEventListener("change", {
        date = dateInput.value
        scanned = emptyList()
        page = 1
        paint()
    })

    paint()
    registerRefresh { applyFilters() }
    return root
}
