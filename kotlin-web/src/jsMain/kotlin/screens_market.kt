import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- shared helpers

fun withMarket(host: HTMLElement, force: Boolean = false, build: () -> HTMLElement) {
    host.innerHTML = ""
    host.appendChild(loadingState())
    loadMarket(force) { ok, error ->
        host.innerHTML = ""
        if (ok) {
            try {
                host.appendChild(build())
            } catch (failure: dynamic) {
                logError("render market", failure)
                host.appendChild(errorState("The dashboard could not render: " + errText(failure)) {
                    withMarket(host, true, build)
                })
            }
        } else {
            host.appendChild(errorState(error ?: "The market service did not respond.") {
                withMarket(host, true, build)
            })
        }
    }
}

fun marketStatusLabel(): String {
    val payload = Ctx.market ?: return "Connecting"
    return if (payload.marketOpen == true) "Market open" else "Market closed"
}

fun updatedLabel(): String {
    val payload = Ctx.market ?: return "—"
    val stamp = text(pick(payload, "updatedAt"))
    if (stamp == null) return "—"
    val clock = if (stamp.length >= 16 && stamp.contains("T")) stamp.substring(11, 16) else "—"
    return shortDate(stamp.take(10)) + " · " + clock
}

fun indexValues(): Map<String, Double?> {
    val index = Ctx.market?.index
    val close = num(pick(index, "close", "currentValue", "indexValue", "value", "lastTradedPrice"))
    val prev = num(pick(index, "previousClose", "previousDayClose", "prevClose", "previousClosing"))
    val change = num(pick(index, "change", "pointChange", "difference"))
        ?: if (close != null && prev != null) close - prev else null
    val pct = num(pick(index, "perChange", "percentageChange", "percentChange", "changePercent"))
        ?: if (close != null && prev != null && prev != 0.0) (close - prev) / prev * 100.0 else null
    return mapOf(
        "close" to close,
        "change" to change,
        "pct" to pct,
        "prev" to prev,
        "high" to num(pick(index, "high", "dayHigh")),
        "low" to num(pick(index, "low", "dayLow")),
        "open" to num(pick(index, "open", "openValue"))
    )
}

fun sectorRows(): List<dynamic> = Ctx.sectors

fun sectorTone(value: Double?): String = when {
    value == null -> "flat"
    value > 0 -> "up"
    value < 0 -> "down"
    else -> "flat"
}

fun sectorAnswer(): Double = sectorRows().sumOf { num(pick(it, "turnover")) ?: 0.0 }

fun marketStatCards(payload: dynamic): HTMLElement {
    val grid = el("div", "stat-grid")
    val turnover = summaryDouble(payload, "turnover", "total traded amount")
    val shares = summaryDouble(payload, "total traded shares", "traded shares")
    val transactions = summaryDouble(payload, "total transactions", "transactions")
    val scrips = summaryDouble(payload, "total scrips traded", "scrips")
    val marketCap = summaryDouble(payload, "total market capitalization", "market capitalization")
    val floatCap = summaryDouble(payload, "total float market capitalization", "float market capitalization")
    grid.appendChild(statTile("TURNOVER", rupees(turnover), "Reported traded value this session"))
    grid.appendChild(statTile("TRANSACTIONS", integer(transactions), "Executed contracts"))
    grid.appendChild(statTile("SHARES TRADED", integer(shares), "Total traded quantity"))
    grid.appendChild(statTile("SCRIPS TRADED", integer(scrips), "Listed securities active"))
    grid.appendChild(statTile("MARKET CAP", rupees(marketCap), "Total listed capitalisation"))
    grid.appendChild(statTile("FLOAT CAP", rupees(floatCap), "Free-float capitalisation"))
    return grid
}

fun moverRow(quote: Quote): HTMLElement {
    val row = el("div", "mover-row")
    row.appendChild(el("span", "mover-symbol", quote.symbol))
    row.appendChild(el("span", "mover-name", quote.name))
    row.appendChild(el("span", "mover-price", price(quote.ltp)))
    row.appendChild(el("span", "mover-change " + if (quote.isUp) "up" else if (quote.isDown) "down" else "flat", percent(quote.pct)))
    row.onClick { openSymbol(quote.symbol) }
    return row
}

fun moversCard(title: String, hint: String, rows: List<Quote>, positive: Boolean): HTMLElement {
    val panel = card("movers-card")
    panel.appendChild(cardHead(title, hint, badge(if (positive) "GAINERS" else "LOSERS", if (positive) "up" else "down")))
    if (rows.isEmpty()) {
        panel.appendChild(emptyState("No verified movers", "The provider did not return a verified ${if (positive) "gainer" else "loser"} list for this session."))
    } else {
        for (quote in rows.take(6)) panel.appendChild(moverRow(quote))
    }
    return panel
}

// ---------------------------------------------------------------- volume & transaction leaders

object TransactionStats {
    var counts: Map<String, Int> = emptyMap()
    var at: Double = 0.0
    var loading: Boolean = false
    var error: String? = null
    private val waiting = ArrayList<(Boolean, String?) -> Unit>()

    fun isFresh(): Boolean = counts.isNotEmpty() && nowMs() - at < 300_000.0

    fun addListener(listener: (Boolean, String?) -> Unit) {
        waiting.add(listener)
    }

    fun finish(ok: Boolean) {
        at = nowMs()
        loading = false
        val listeners = waiting.toList()
        waiting.clear()
        for (listener in listeners) listener(ok, error)
    }
}

fun transactionCountFor(symbol: String): Int? = TransactionStats.counts[symbol.uppercase()]

/**
 * Exact per-symbol transaction counts from the executed NEPSE floorsheet cache.
 * The market snapshot carries no contract count, so every traded symbol is
 * resolved with /api/floorsheet-cache?symbol=…&limit=1 and its reported total is
 * used. Requests run with bounded concurrency and are cached for five minutes;
 * nothing is estimated or synthesised.
 */
fun loadTransactionCounts(force: Boolean = false, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
    val symbols = Ctx.quotes
        .filter { (it.volume ?: 0.0) > 0.0 && it.symbol != "—" }
        .map { it.symbol.uppercase() }
        .distinct()
    if (symbols.isEmpty()) {
        onDone(false, "No traded securities in this market snapshot")
        return
    }
    if (!force && TransactionStats.isFresh()) {
        onDone(true, null)
        return
    }
    TransactionStats.addListener(onDone)
    if (TransactionStats.loading) return
    TransactionStats.loading = true
    TransactionStats.error = null
    val found = LinkedHashMap<String, Int>()
    var index = 0
    var active = 0
    var settled = false

    fun settle() {
        if (index < symbols.size || active > 0 || settled) return
        settled = true
        TransactionStats.counts = found.toMap()
        TransactionStats.error = if (found.isEmpty()) "The floorsheet cache did not return contract totals." else null
        TransactionStats.finish(found.isNotEmpty())
    }

    fun release() {
        active -= 1
        settle()
    }

    lateinit var pump: () -> Unit
    pump = {
        while (active < 6 && index < symbols.size) {
            val symbol = symbols[index]
            index += 1
            active += 1
            Http.get(
                "/api/floorsheet-cache?symbol=" + encode(symbol) + "&limit=1",
                ttlMs = 600_000.0,
                force = force,
                timeoutMs = 25_000,
                onData = { payload ->
                    val total = num(pick(payload, "total", "count"))?.toInt()
                    if (total != null && total >= 0) found[symbol] = total
                    release()
                    pump()
                },
                onError = {
                    release()
                    pump()
                }
            )
        }
        settle()
    }
    pump()
}

data class FlowRow(val quote: Quote, val metric: String)

fun volumeLeaders(): List<FlowRow> = Ctx.quotes
    .filter { (it.volume ?: 0.0) > 0.0 }
    .sortedByDescending { it.volume ?: 0.0 }
    .map { FlowRow(it, integer(it.volume)) }

fun transactionLeaders(): List<FlowRow> = Ctx.quotes
    .mapNotNull { quote -> transactionCountFor(quote.symbol)?.let { quote to it } }
    .sortedByDescending { it.second }
    .map { FlowRow(it.first, integer(it.second.toDouble())) }

fun flowCard(
    title: String,
    hint: String,
    metricHeader: String,
    rows: List<FlowRow>,
    loading: Boolean = false,
    error: String? = null,
    emptyMessage: String = "",
    viewAll: (() -> Unit)? = null
): HTMLElement {
    val panel = card("flow-card")
    val trailing = if (viewAll == null || rows.isEmpty()) null else actionButton("View all", "ghost", null) { viewAll() }
    panel.appendChild(cardHead(title, hint, trailing))
    if (loading) {
        panel.appendChild(skeleton(132))
        panel.appendChild(el("p", "muted small", "Reading executed contracts from the NEPSE floorsheet…"))
        return panel
    }
    if (rows.isEmpty()) {
        panel.appendChild(emptyState(
            if (error != null) "Transaction counts unavailable" else "No securities to rank",
            error ?: emptyMessage
        ))
        return panel
    }
    val table = dataTable(listOf("SYMBOL / COMPANY", "LTP", metricHeader, "% CHANGE"), setOf(1, 2, 3))
    for (row in rows.take(6)) {
        val quote = row.quote
        val symbolCell = el("div", "symbol-cell")
        symbolCell.appendChild(el("strong", "", quote.symbol))
        symbolCell.appendChild(el("small", "", quote.name.take(34)))
        table.row(listOf(
            symbolCell,
            el("div", "mono", price(quote.ltp)),
            el("div", "mono", row.metric),
            el("div", "mono tone-" + toneOf(quote.pct), percent(quote.pct))
        )) { openSymbol(quote.symbol) }
    }
    panel.appendChild(table.root.parentElement as HTMLElement)
    panel.appendChild(el("p", "muted small", integer(rows.size.toDouble()) + " ranked securities"))
    return panel
}

fun openFlowList(title: String, metricHeader: String, rows: List<FlowRow>) {
    modal(title) { body ->
        val host = el("div")
        body.appendChild(host)
        var visible = 60
        lateinit var paintList: () -> Unit
        paintList = {
            host.innerHTML = ""
            val table = dataTable(listOf("SYMBOL / COMPANY", "LTP", metricHeader, "% CHANGE"), setOf(1, 2, 3))
            for (row in rows.take(visible)) {
                val quote = row.quote
                val symbolCell = el("div", "symbol-cell")
                symbolCell.appendChild(el("strong", "", quote.symbol))
                symbolCell.appendChild(el("small", "", quote.name.take(40)))
                table.row(listOf(
                    symbolCell,
                    el("div", "mono", price(quote.ltp)),
                    el("div", "mono", row.metric),
                    el("div", "mono tone-" + toneOf(quote.pct), percent(quote.pct))
                )) { openSymbol(quote.symbol) }
            }
            host.appendChild(table.root.parentElement as HTMLElement)
            host.appendChild(el("p", "muted small",
                "Showing " + integer(min(visible, rows.size).toDouble()) + " of " + integer(rows.size.toDouble()) + " securities"))
            if (rows.size > visible) {
                host.appendChild(actionButton("Show more", "secondary", "plus") {
                    visible += 100
                    paintList()
                })
            }
        }
        paintList()
    }
}

// ---------------------------------------------------------------- news feed

data class FeedItem(val title: String, val detail: String, val date: String, val source: String)

fun feedItemsFrom(payload: dynamic, source: String): List<FeedItem> {
    val out = ArrayList<FeedItem>()
    val rows = rowsOf(payload?.data).ifEmpty {
        rowsOf(payload?.results).ifEmpty {
            rowsOf(payload?.items).ifEmpty {
                if (payload != null && payload != undefined && keysOf(payload).size > 3) listOf(payload) else emptyList()
            }
        }
    }
    for (row in rows) {
        val title = text(pick(
            row, "title", "subject", "headline", "companyName", "securityName", "name",
            "noticeTitle", "announcementTitle", "eventTitle", "type", "category"
        )) ?: continue
        val detail = text(pick(
            row, "description", "details", "remarks", "message", "summary", "purpose",
            "body", "announcementBody", "companyName", "securityName"
        )) ?: ""
        val date = text(pick(
            row, "publishedDate", "announcementDate", "date", "eventDate", "bookCloseDate",
            "openingDate", "createdAt", "updatedAt", "businessDate"
        )) ?: ""
        out.add(FeedItem(title, detail, date, source))
    }
    return out
}

fun loadFeed(
    paths: List<Pair<String, String>>,
    done: (List<FeedItem>, List<String>) -> Unit
) {
    val results = ArrayList<FeedItem>()
    val errors = ArrayList<String>()
    var remaining = paths.size
    if (paths.isEmpty()) {
        done(results, listOf("No provider source configured"))
        return
    }
    fun finish() {
        remaining -= 1
        if (remaining <= 0) {
            val seen = HashSet<String>()
            val unique = results
                .filter { it.title.isNotBlank() }
                .sortedByDescending { it.date }
                .filter { seen.add(it.title.lowercase() + "|" + it.date) }
            done(unique, errors)
        }
    }
    for ((path, label) in paths) {
        Http.get(path, ttlMs = 240_000.0, timeoutMs = 30_000, onData = { payload ->
            results.addAll(feedItemsFrom(payload, label))
            finish()
        }, onError = { message ->
            errors.add("$label: $message")
            finish()
        })
    }
}

object NewsCache {
    var items: List<FeedItem> = emptyList()
    var errors: List<String> = emptyList()
    var at: Double = 0.0
    var loaded: Boolean = false
}

fun newsCard(): HTMLElement {
    val panel = card("news-card")
    panel.appendChild(cardHead("Market news & updates", "Latest notices and disclosures"))
    val body = el("div", "news-body")
    body.appendChild(skeleton(90))
    panel.appendChild(body)
    fun paint(items: List<FeedItem>, errors: List<String>) {
        body.innerHTML = ""
        if (items.isEmpty()) {
            body.appendChild(emptyState(
                "No provider updates available",
                if (errors.isEmpty()) "The provider returned no announcements for this session."
                else "The provider notices feed is not responding. " + errors.first()
            ))
            return
        }
        for (item in items.take(4)) {
            val row = el("div", "news-item")
            val copy = el("div", "news-copy")
            copy.appendChild(el("div", "news-title", item.title))
            if (item.detail.isNotBlank() && item.detail != item.title) {
                copy.appendChild(el("div", "news-detail", item.detail.take(150)))
            }
            row.appendChild(copy)
            val meta = el("div", "news-meta")
            meta.appendChild(badge(item.source, "neutral"))
            meta.appendChild(el("span", "news-date", shortDate(item.date)))
            row.appendChild(meta)
            body.appendChild(row)
        }
    }
    fun load() {
        body.innerHTML = ""
        body.appendChild(skeleton(70))
        if (NewsCache.loaded && nowMs() - NewsCache.at < 300_000.0) {
            paint(NewsCache.items, NewsCache.errors)
            return
        }
        loadFeed(listOf(
            "/api/production/announcements" to "Announcements",
            "/api/production/notices" to "Notices"
        )) { items, errors ->
            NewsCache.items = items
            NewsCache.errors = errors
            NewsCache.at = nowMs()
            NewsCache.loaded = true
            paint(items, errors)
        }
    }
    load()
    return panel
}

// ---------------------------------------------------------------- dashboard

fun dashboardScreen(): HTMLElement {
    val root = el("div", "screen")
    var rebuild: (Boolean) -> Unit = { }
    val refresh = actionButton("Refresh", "secondary", "refresh") { rebuild(true) }
    root.appendChild(pageHead(
        "MARKET INTELLIGENCE",
        "Dashboard",
        "A live read on the NEPSE session, breadth, liquidity and the names moving the market.",
        listOf(refresh)
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)

    fun rebuildScreen(force: Boolean = false) {
        withMarket(body, force) {
            val wrap = el("div", "dashboard")
            val payload = Ctx.market
            val index = indexValues()
            val close = index["close"]
            val change = index["change"]
            val pct = index["pct"]
            val breadth = marketBreadth(payload)

            val hero = el("div", "dashboard-hero")
            val indexCard = card("index-card")
            val indexTop = el("div", "index-top")
            val indexCopy = el("div")
            indexCopy.appendChild(el("div", "eyebrow", "NEPSE INDEX"))
            val valueRow = el("div", "index-value-row")
            valueRow.appendChild(el("div", "index-value", price(close)))
            val tone = toneClass(pct)
            valueRow.appendChild(el("div", "index-delta $tone", changePoints(change) + "  ·  " + percent(pct)))
            indexCopy.appendChild(valueRow)
            indexTop.appendChild(indexCopy)
            val statusWrap = el("div", "index-status")
            statusWrap.appendChild(badge(marketStatusLabel(), if (payload.marketOpen == true) "live" else "neutral"))
            statusWrap.appendChild(el("span", "index-updated", "Updated " + updatedLabel()))
            indexTop.appendChild(statusWrap)
            indexCard.appendChild(indexTop)

            val chartHost = el("div", "index-chart")
            chartHost.appendChild(skeleton(150))
            indexCard.appendChild(chartHost)
            val range = el("div", "index-range")
            range.appendChild(pair("Day high", price(index["high"])))
            range.appendChild(pair("Day low", price(index["low"])))
            range.appendChild(pair("Previous close", price(index["prev"])))
            indexCard.appendChild(range)
            hero.appendChild(indexCard)

            val side = card("breadth-card")
            side.appendChild(cardHead("Market breadth", "Advancing versus declining securities"))
            val breadthStats = el("div", "breadth-stats")
            val upTile = el("div", "breadth-stat up")
            upTile.appendChild(el("span", "breadth-num", breadth.first.toString()))
            upTile.appendChild(el("span", "breadth-label", "Advancing"))
            val flatTile = el("div", "breadth-stat flat")
            flatTile.appendChild(el("span", "breadth-num", breadth.third.toString()))
            flatTile.appendChild(el("span", "breadth-label", "Unchanged"))
            val downTile = el("div", "breadth-stat down")
            downTile.appendChild(el("span", "breadth-num", breadth.second.toString()))
            downTile.appendChild(el("span", "breadth-label", "Declining"))
            breadthStats.add(upTile, flatTile, downTile)
            side.appendChild(breadthStats)
            side.appendChild(breadthBar(breadth.first, breadth.second, breadth.third))
            val ratio = if (breadth.second > 0) breadth.first.toDouble() / breadth.second else null
            val insights = el("div", "breadth-insights")
            insights.appendChild(pair("Advance / decline ratio", if (ratio == null) "—" else fixed(ratio, 2)))
            insights.appendChild(pair("Session coverage", integer(Ctx.quotes.size.toDouble()) + " symbols"))
            insights.appendChild(pair(
                "Breadth read",
                when {
                    ratio == null -> "Awaiting data"
                    ratio > 1.5 -> "Broad participation"
                    ratio > 1.0 -> "Mildly positive"
                    ratio > 0.7 -> "Mildly negative"
                    else -> "Broad weakness"
                }
            ))
            side.appendChild(insights)
            hero.appendChild(side)
            wrap.appendChild(hero)

            loadIntraday({ bars ->
                chartHost.innerHTML = ""
                chartHost.appendChild(areaChart(bars, 156, intraday = true))
            }, {
                chartHost.innerHTML = ""
                chartHost.appendChild(emptyState("Intraday chart unavailable", "The index snapshot series has no intraday points yet."))
            })

            wrap.appendChild(marketStatCards(payload))

            val movers = el("div", "grid-2")
            val gainers = Ctx.quotes.filter { (it.pct ?: 0.0) > 0 }.sortedByDescending { it.pct ?: 0.0 }
            val losers = Ctx.quotes.filter { (it.pct ?: 0.0) < 0 }.sortedBy { it.pct ?: 0.0 }
            val apiGainers = quotesOf(payload?.gainers)
            val apiLosers = quotesOf(payload?.losers)
            movers.appendChild(moversCard("Top gainers", "Strongest verified moves this session", if (apiGainers.isNotEmpty()) apiGainers else gainers, true))
            movers.appendChild(moversCard("Top losers", "Weakest verified moves this session", if (apiLosers.isNotEmpty()) apiLosers else losers, false))
            wrap.appendChild(movers)

            val volumeRows = volumeLeaders()
            val flowCards = el("div", "grid-2")
            flowCards.appendChild(flowCard(
                title = "Top volume",
                hint = "Most actively traded securities by share quantity",
                metricHeader = "VOLUME",
                rows = volumeRows,
                emptyMessage = "The provider did not report traded volume for this session.",
                viewAll = { openFlowList("Top volume", "VOLUME", volumeRows) }
            ))
            val transactionHost = el("div", "flow-host")
            flowCards.appendChild(transactionHost)
            fun paintTransactions() {
                transactionHost.innerHTML = ""
                val rows = transactionLeaders()
                transactionHost.appendChild(flowCard(
                    title = "Top transactions",
                    hint = "Highest number of executed contracts this session",
                    metricHeader = "TRADES",
                    rows = rows,
                    loading = TransactionStats.loading && rows.isEmpty(),
                    error = TransactionStats.error,
                    emptyMessage = "No executed contracts were reported for this session.",
                    viewAll = { openFlowList("Top transactions", "TRADES", rows) }
                ))
            }
            paintTransactions()
            if (!TransactionStats.isFresh()) {
                loadTransactionCounts { _, _ ->
                    if (transactionHost.parentElement != null) paintTransactions()
                }
            }
            wrap.appendChild(flowCards)

            val flow = el("div", "grid-2-wide")
            val turnoverCard = card("turnover-card")
            turnoverCard.appendChild(cardHead("Turnover leaders", "Liquidity concentration by traded value", actionButton("All stocks", "ghost", null) { go("market") }))
            val turnTable = dataTable(listOf("SYMBOL", "TURNOVER", "LTP", "CHANGE"), setOf(1, 2, 3))
            val leaders = quotesOf(payload?.topTurnover).ifEmpty {
                Ctx.quotes.sortedByDescending { it.turnover ?: 0.0 }.take(6)
            }
            if (leaders.isEmpty()) {
                turnTable.empty("No turnover data available for this session.")
            } else {
                for (quote in leaders.take(6)) {
                    turnTable.row(listOf(
                        el("div", "symbol-cell", quote.symbol),
                        el("div", "", rupees(quote.turnover)),
                        el("div", "", price(quote.ltp)),
                        el("div", "tone-" + toneOf(quote.pct), percent(quote.pct))
                    )) { openSymbol(quote.symbol) }
                }
            }
            turnoverCard.appendChild(turnTable.root.parentElement as HTMLElement)
            flow.appendChild(turnoverCard)
            flow.appendChild(newsCard())
            wrap.appendChild(flow)

            val sectorCard = card("sector-strip-card")
            sectorCard.appendChild(cardHead("Sector pulse", "Index performance across NEPSE sectors", actionButton("Heatmap", "ghost", null) { go("sectors") }))
            val strip = el("div", "sector-strip")
            strip.appendChild(skeleton(80))
            sectorCard.appendChild(strip)
            wrap.appendChild(sectorCard)
            loadSectors { sectors ->
                strip.innerHTML = ""
                if (sectors.isEmpty()) {
                    strip.appendChild(emptyState("Sector data unavailable", "The provider did not return sector index data."))
                } else {
                    for (row in sectors.sortedByDescending { abs(num(pick(it, "changePercent")) ?: 0.0) }.take(6)) {
                        val name = text(pick(row, "sector", "name", "index")) ?: "Sector"
                        val pctValue = num(pick(row, "changePercent", "change"))
                        val tile = el("button", "sector-chip " + sectorTone(pctValue))
                        tile.appendChild(el("span", "sector-chip-name", name))
                        tile.appendChild(el("span", "sector-chip-value", percent(pctValue)))
                        tile.onClick { go("sectors") }
                        strip.appendChild(tile)
                    }
                }
            }
            wrap.appendChild(sectorCard)

            val toolsCard = card("tools-strip-card")
            toolsCard.appendChild(cardHead("Decision tools", "Replay, risk and comparison computed from real market data", actionButton("All tools", "ghost", "layers") { go("tools") }))
            val toolsStrip = el("div", "tools-strip")
            for (entry in listOf(
                "replay" to "Chart replay",
                "risk" to "Risk calculator",
                "scenario" to "Scenario",
                "compare" to "Compare stocks"
            )) {
                val chip = el("button", "sector-chip tools-chip")
                chip.appendChild(el("span", "sector-chip-name", entry.second))
                chip.onClick { go(entry.first) }
                toolsStrip.appendChild(chip)
            }
            toolsCard.appendChild(toolsStrip)
            wrap.appendChild(toolsCard)
            return@withMarket wrap
        }
    }
    rebuild = { force -> rebuildScreen(force) }
    rebuild(false)
    root.setAttribute("data-refresh", "market")
    registerRefresh { rebuild(true) }
    return root
}

// ---------------------------------------------------------------- market overview

fun marketOverviewScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "MARKET WATCH",
        "Market overview",
        "Every listed security in the live session, with sector filters and liquidity detail."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var search = ""
    var mode = "all"
    var sector = "all"
    var limit = 30
    var paintList: () -> Unit = { }

    fun paint() {
        withMarket(body) {
            val wrap = el("div", "market-screen")
            val toolbar = card("toolbar-card")
            val toolbarRow = el("div", "toolbar")
            val searchInput = field("Search symbol or company", "search", search)
            searchInput.addEventListener("input", { search = searchInput.value; paintList() })
            toolbarRow.appendChild(searchInput)
            val sectorOptions = ArrayList<Pair<String, String>>()
            sectorOptions.add("all" to "All sectors")
            val names = Ctx.sectorNames.values.sorted()
            for (name in names) sectorOptions.add(name to name)
            if (names.isEmpty()) {
                for (row in Ctx.sectors) {
                    val name = text(pick(row, "sector", "name", "index")) ?: continue
                    if (sectorOptions.none { it.first == name }) sectorOptions.add(name to name)
                }
            }
            val sectorSelect = selectField(sectorOptions, sector) { value -> sector = value; paintList() }
            toolbarRow.appendChild(sectorSelect)
            val chips = el("div", "toolbar-chips")
            for ((key, label) in listOf("all" to "All", "gainers" to "Gainers", "losers" to "Losers", "active" to "Most active")) {
                chips.appendChild(chipButton(label, mode == key) { mode = key; paint() })
            }
            toolbarRow.appendChild(chips)
            toolbar.appendChild(toolbarRow)
            val meta = el("div", "toolbar-meta")
            val countLabel = el("span", "muted")
            meta.appendChild(countLabel)
            toolbar.appendChild(meta)
            wrap.appendChild(toolbar)

            val tableCard = card("table-card")
            val tableHost = el("div")
            tableCard.appendChild(tableHost)
            wrap.appendChild(tableCard)

            fun selectedRows(): List<Quote> {
                var rows = Ctx.quotes
                if (sector != "all") rows = rows.filter { it.sector.equals(sector, true) || Ctx.sectorNames.values.contains(it.sector) }
                when (mode) {
                    "gainers" -> rows = rows.filter { (it.pct ?: 0.0) > 0 }.sortedByDescending { it.pct ?: 0.0 }
                    "losers" -> rows = rows.filter { (it.pct ?: 0.0) < 0 }.sortedBy { it.pct ?: 0.0 }
                    "active" -> rows = rows.sortedByDescending { it.turnover ?: it.volume ?: 0.0 }
                    else -> rows = rows.sortedByDescending { it.turnover ?: it.volume ?: 0.0 }
                }
                val q = search.trim()
                if (q.isNotEmpty()) rows = rows.filter { it.symbol.contains(q, true) || it.name.contains(q, true) }
                return rows
            }

            paintList = {
                val rows = selectedRows()
                countLabel.textContent = integer(rows.size.toDouble()) + " securities · " + marketStatusLabel()
                tableHost.innerHTML = ""
                val table = dataTable(listOf("SYMBOL / COMPANY", "LTP", "CHANGE", "% CHANGE", "VOLUME", "TURNOVER"), setOf(1, 2, 3, 4, 5))
                if (rows.isEmpty()) {
                    table.empty(if (Ctx.quotes.isEmpty()) "No live rows received from the provider." else "No securities match this filter.")
                } else {
                    for (quote in rows.take(limit)) {
                        val symbolCell = el("div", "symbol-cell")
                        symbolCell.appendChild(el("strong", "", quote.symbol))
                        symbolCell.appendChild(el("small", "", quote.name))
                        table.row(listOf(
                            symbolCell,
                            el("div", "mono", price(quote.ltp)),
                            el("div", "mono tone-" + toneOf(quote.change), changePoints(quote.change)),
                            el("div", "mono tone-" + toneOf(quote.pct), percent(quote.pct)),
                            el("div", "mono", integer(quote.volume)),
                            el("div", "mono", rupees(quote.turnover))
                        )) { openSymbol(quote.symbol) }
                    }
                }
                tableHost.appendChild(table.root.parentElement as HTMLElement)
                if (rows.size > limit) {
                    tableHost.appendChild(actionButton("Show more securities", "secondary", "plus") {
                        limit += 40
                        paintList()
                    })
                }
            }

            paintList()
            return@withMarket wrap
        }
    }
    paint()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- stock x-ray

fun flattenPairs(value: dynamic, prefix: String = "", depth: Int = 0): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    if (value == null || value == undefined || depth > 2) return out
    val names = keysOf(value)
    val count = names.size
    for (i in 0 until count) {
        val key = names[i].toString()
        val item = value[key]
        if (item == null || item == undefined) continue
        if (item is String || item is Number || item is Boolean) {
            val label = key.replace("_", " ").replace(Regex("([a-z])([A-Z])"), "$1 $2")
            val rendered = if (item is Number) price(item.toDouble()) else item.toString()
            if (rendered.length < 90) out.add(label to rendered)
        } else if (depth < 1) {
            out.addAll(flattenPairs(item, prefix + key.replace("_", " ") + " · ", depth + 1))
        }
    }
    return out
}

/**
 * Loads the fundamentals bundle, falling back to the Stock X-Ray endpoint when
 * the richer fundamentals route is unavailable. Both routes are served by the
 * existing Render backend; no new data service is introduced.
 */
fun loadFundamentalsPayload(symbol: String, onData: (dynamic) -> Unit, onError: (String) -> Unit) {
    Http.get("/api/fundamentals/" + encode(symbol), ttlMs = 600_000.0, onData = onData, onError = {
        Http.get("/api/stock-xray/" + encode(symbol), ttlMs = 600_000.0, onData = onData, onError = onError)
    })
}

fun stockXrayScreen(symbol: String = ""): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "STOCK RESEARCH",
        "Stock X-Ray",
        "Fundamentals, technical structure, broker flow and price history for a single security."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var activeSymbol = symbol.uppercase()
    var tab = "overview"
    var resolution = "D"

    fun paint() {
        body.innerHTML = ""
        val searchCard = card("xray-search")
        val searchRow = el("div", "toolbar")
        val input = field("Enter a NEPSE symbol, for example NICA", "search", activeSymbol)
        searchRow.appendChild(input)
        searchRow.appendChild(actionButton("Open X-Ray", "primary", "xray") {
            val next = input.value.trim().uppercase()
            if (next.isNotEmpty()) {
                activeSymbol = next
                tab = "overview"
                paint()
            }
        })
        val quick = el("div", "quick-symbols")
        for (name in listOf("NICA", "NABIL", "SCB", "NTC", "SHIVM", "HDL")) {
            quick.appendChild(chipButton(name, activeSymbol == name) {
                activeSymbol = name
                tab = "overview"
                paint()
            })
        }
        searchRow.appendChild(quick)
        searchCard.appendChild(searchRow)
        body.appendChild(searchCard)

        if (activeSymbol.isBlank()) {
            body.appendChild(emptyState(
                "Choose a security",
                "Search any NEPSE symbol to open its X-Ray: price action, fundamentals, technicals and broker flow."
            ))
            return
        }

        val headCard = card("xray-head")
        headCard.appendChild(skeleton(96))
        body.appendChild(headCard)

        val quote = quoteFor(activeSymbol)
        val tabsHost = el("div", "tabs-host")
        body.appendChild(tabsHost)
        var paintTabs: () -> Unit = { }
        var loadOverview: (HTMLElement) -> Unit = { _ -> }
        var loadFundamentals: (HTMLElement) -> Unit = { _ -> }
        var loadTechnicals: (HTMLElement) -> Unit = { _ -> }
        var loadTrades: (HTMLElement) -> Unit = { _ -> }
        var loadChart: (HTMLElement) -> Unit = { _ -> }

        fun renderHead(bars: List<Bar>, analysis: Analysis?) {
            headCard.innerHTML = ""
            val last = bars.lastOrNull()
            val priceValue = quote?.ltp ?: last?.close
            val pctValue = quote?.pct ?: analysis?.changePct
            val changeValue = quote?.change
            val titleRow = el("div", "xray-title")
            val titleCopy = el("div")
            titleCopy.appendChild(el("div", "eyebrow", "NSE · " + activeSymbol))
            titleCopy.appendChild(el("h2", "xray-symbol", activeSymbol))
            titleCopy.appendChild(el("p", "xray-name", quote?.name ?: "Company profile from the fundamentals feed"))
            titleRow.appendChild(titleCopy)
            val priceBlock = el("div", "xray-price")
            priceBlock.appendChild(el("div", "xray-ltp", price(priceValue)))
            priceBlock.appendChild(el("div", "xray-change tone-" + toneOf(pctValue), changePoints(changeValue) + " · " + percent(pctValue)))
            titleRow.appendChild(priceBlock)
            val actions = el("div", "xray-actions")
            actions.appendChild(actionButton("Watchlist", "secondary", "watchlist") { promptWatchlist(activeSymbol) })
            actions.appendChild(actionButton("Chart", "ghost", "chart") { tab = "chart"; paintTabs() })
            titleRow.appendChild(actions)
            headCard.appendChild(titleRow)

            val stats = el("div", "xray-stats")
            if (last != null) {
                stats.appendChild(pair("Open", price(last.open)))
                stats.appendChild(pair("High", price(last.high)))
                stats.appendChild(pair("Low", price(last.low)))
                stats.appendChild(pair("Volume", integer(last.volume)))
            }
            if (analysis != null) {
                stats.appendChild(pair("RSI (14)", analysis.rsi?.let { fixed(it, 1) } ?: "—"))
                stats.appendChild(pair("20-day average", price(analysis.sma20)))
                stats.appendChild(pair("50-day average", price(analysis.sma50)))
                stats.appendChild(pair("52-week high", price(analysis.high52)))
                stats.appendChild(pair("52-week low", price(analysis.low52)))
                stats.appendChild(pair("Structure", analysis.pattern))
            }
            headCard.appendChild(stats)
        }

        paintTabs = {
            tabsHost.innerHTML = ""
            val tabBar = el("div", "tabs")
            for ((key, label) in listOf(
                "overview" to "Overview",
                "fundamentals" to "Fundamentals",
                "technical" to "Technicals",
                "trades" to "Trades",
                "chart" to "Chart"
            )) {
                tabBar.appendChild(chipButton(label, tab == key) { tab = key; paintTabs() })
            }
            tabsHost.appendChild(tabBar)
            val pane = el("div", "tab-pane")
            tabsHost.appendChild(pane)
            when (tab) {
                "fundamentals" -> loadFundamentals(pane)
                "technical" -> loadTechnicals(pane)
                "trades" -> loadTrades(pane)
                "chart" -> loadChart(pane)
                else -> loadOverview(pane)
            }
        }

        fun loadHistoryOnce(done: (List<Bar>) -> Unit) {
            loadHistory(activeSymbol, countback = 400, onData = { bars ->
                renderHead(bars, analyzeBars(bars))
                done(bars)
            }, onError = {
                renderHead(emptyList(), null)
                done(emptyList())
            })
        }

        loadOverview = { pane ->
            pane.appendChild(skeleton(120))
            val requestSymbol = activeSymbol
            val profileHost = el("div")
            profileHost.appendChild(skeleton(120))
            pane.appendChild(profileHost)
            val summaryHost = el("div")
            summaryHost.appendChild(skeleton(90))
            pane.appendChild(summaryHost)
            Http.get("/api/company/" + encode(requestSymbol), ttlMs = 600_000.0, onData = { payload ->
                if (activeSymbol != requestSymbol) return@get
                profileHost.innerHTML = ""
                val profileCard = card()
                profileCard.appendChild(cardHead("Company profile", "Provider-reported listing information"))
                val details = payload?.details ?: payload?.company
                val fields = flattenPairs(details).filter { (label, _) ->
                    !label.lowercase().contains("id") && !label.lowercase().contains("active status")
                }
                if (fields.isEmpty()) {
                    profileCard.appendChild(emptyState("No profile details", "The company endpoint returned no profile fields."))
                } else {
                    val grid = el("div", "kv-grid")
                    for ((label, value) in fields.take(12)) grid.appendChild(pair(label.replaceFirstChar { it.uppercase() }, value))
                    profileCard.appendChild(grid)
                }
                profileHost.appendChild(profileCard)
            }, onError = { message ->
                if (activeSymbol != requestSymbol) return@get
                profileHost.innerHTML = ""
                profileHost.appendChild(emptyState("Company profile unavailable", message))
            })
            loadFundamentalsPayload(requestSymbol, { payload ->
                if (activeSymbol != requestSymbol) return@loadFundamentalsPayload
                summaryHost.innerHTML = ""
                summaryHost.appendChild(fundamentalsSummary(payload))
            }, {
                if (activeSymbol != requestSymbol) return@loadFundamentalsPayload
                summaryHost.innerHTML = ""
            })
            val watch = card()
            watch.appendChild(cardHead("Track this stock", "Your watchlist syncs with Supabase when signed in"))
            val row = el("div", "inline-actions")
            row.appendChild(actionButton("Add to watchlist", "secondary", "plus") { promptWatchlist(activeSymbol) })
            row.appendChild(actionButton("Open TradingView", "ghost", "external") { openTradingView(activeSymbol) })
            watch.appendChild(row)
            pane.appendChild(watch)
        }

        loadFundamentals = { pane ->
            val requestSymbol = activeSymbol
            pane.innerHTML = ""
            pane.appendChild(skeleton(160))
            loadFundamentalsPayload(requestSymbol, { payload ->
                if (activeSymbol != requestSymbol || tab != "fundamentals") return@loadFundamentalsPayload
                pane.innerHTML = ""
                pane.appendChild(fundamentalsView(requestSymbol, payload))
            }, { message ->
                if (activeSymbol != requestSymbol) return@loadFundamentalsPayload
                pane.innerHTML = ""
                pane.appendChild(errorState(message) { tab = "fundamentals"; paintTabs() })
            })
        }

        loadTechnicals = { pane ->
            pane.appendChild(skeleton(140))
            loadHistory(activeSymbol, countback = 500, onData = { bars ->
                pane.innerHTML = ""
                if (bars.size < 20) {
                    pane.appendChild(emptyState("Not enough price history", "At least 20 daily candles are required for technical analysis."))
                    return@loadHistory
                }
                val analysis = analyzeBars(bars)
                val summary = card("analysis-card")
                summary.appendChild(cardHead("Technical read", bars.size.toString() + " daily candles analysed"))
                val scoreRow = el("div", "score-row")
                val scoreRing = el("div", "score-ring")
                scoreRing.style.setProperty("--score", analysis.score.toString() + "%")
                scoreRing.appendChild(el("strong", "", analysis.score.toString()))
                scoreRing.appendChild(el("span", "", "score"))
                scoreRow.appendChild(scoreRing)
                val verdict = el("div", "score-copy")
                verdict.appendChild(el("div", "eyebrow", "MODEL VERDICT"))
                verdict.appendChild(el("div", "score-verdict", verdictFor(analysis)))
                verdict.appendChild(el("p", "muted", "Computed from price structure, moving averages, RSI and volume. This is analysis, not investment advice."))
                scoreRow.appendChild(verdict)
                summary.appendChild(scoreRow)
                val grid = el("div", "kv-grid")
                grid.appendChild(pair("Trend", analysis.trend))
                grid.appendChild(pair("RSI (14)", analysis.rsi?.let { fixed(it, 2) } ?: "—"))
                grid.appendChild(pair("SMA 20", price(analysis.sma20)))
                grid.appendChild(pair("SMA 50", price(analysis.sma50)))
                grid.appendChild(pair("SMA 200", price(analysis.sma200)))
                grid.appendChild(pair("Support (projected)", price(analysis.support)))
                grid.appendChild(pair("Resistance (projected)", price(analysis.resistance)))
                grid.appendChild(pair("Relative volume", analysis.volumeRatio?.let { fixed(it, 2) + "×" } ?: "—"))
                grid.appendChild(pair("52-week range", price(analysis.low52) + " — " + price(analysis.high52)))
                grid.appendChild(pair("Volume ratio", analysis.volumeRatio?.let { fixed(it, 2) + "× 20-day average" } ?: "—"))
                summary.appendChild(grid)
                val notes = el("ul", "note-list")
                for (note in analysis.notes) notes.appendChild(el("li", "", note))
                summary.appendChild(notes)
                pane.appendChild(summary)
                val chart = card()
                chart.appendChild(cardHead("Price structure", "Daily candles with volume"))
                chart.appendChild(candleChart(bars, 320))
                pane.appendChild(chart)
            }, onError = { message ->
                pane.innerHTML = ""
                pane.appendChild(errorState(message) { loadTechnicals(pane) })
            })
        }

        loadTrades = { pane ->
            pane.appendChild(skeleton(120))
            Http.get("/api/company-floorsheet/" + encode(activeSymbol), ttlMs = 120_000.0, onData = { payload ->
                pane.innerHTML = ""
                val rows = rowsOf(payload?.data)
                val cardNode = card()
                cardNode.appendChild(cardHead("Recent transactions", "Executed contracts for " + activeSymbol))
                val table = dataTable(listOf("BUYER", "SELLER", "QTY", "RATE", "AMOUNT", "TIME"), setOf(2, 3, 4))
                if (rows.isEmpty()) {
                    table.empty("No executed contracts returned for this security.")
                } else {
                    for (row in rows.take(60)) {
                        table.row(listOf(
                            el("div", "small", text(pick(row, "buyerBrokerName", "buyerBroker")) ?: "—"),
                            el("div", "small", text(pick(row, "sellerBrokerName", "sellerBroker")) ?: "—"),
                            el("div", "mono", integer(num(pick(row, "quantity", "contractQuantity")))),
                            el("div", "mono", price(num(pick(row, "rate", "contractRate")))),
                            el("div", "mono", rupees(num(pick(row, "amount", "contractAmount")))),
                            el("div", "mono small", clockTime(text(pick(row, "tradeTime"))))
                        ))
                    }
                }
                cardNode.appendChild(table.root.parentElement as HTMLElement)
                pane.appendChild(cardNode)
            }, onError = { message ->
                pane.innerHTML = ""
                pane.appendChild(emptyState("Transaction history unavailable", message))
            })
        }

        loadChart = { pane ->
            pane.appendChild(skeleton(200))
            val controls = el("div", "chart-controls")
            pane.appendChild(controls)
            val host = el("div")
            pane.appendChild(host)
            fun draw() {
                controls.innerHTML = ""
                controls.appendChild(segmented(
                    listOf("D" to "Daily", "W" to "Weekly", "M" to "Monthly"),
                    resolution
                ) { value -> resolution = value; draw() })
                controls.appendChild(actionButton("TradingView", "ghost", "external") { openTradingView(activeSymbol) })
                host.innerHTML = ""
                host.appendChild(skeleton(280))
                loadHistory(activeSymbol, resolution = resolution, countback = if (resolution == "D") 400 else 260, onData = { bars ->
                    host.innerHTML = ""
                    if (bars.isEmpty()) {
                        host.appendChild(emptyState("No candles", "The UDF datafeed returned no candles for this resolution."))
                        return@loadHistory
                    }
                    host.appendChild(candleChart(bars, 360))
                }, onError = { message ->
                    host.innerHTML = ""
                    host.appendChild(errorState(message) { draw() })
                })
            }
            draw()
        }

        loadHistoryOnce { }
        paintTabs()
    }

    paint()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- sectors

fun sectorsScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "SECTOR INTELLIGENCE",
        "Sector heatmap",
        "Index performance, breadth and turnover across every NEPSE sector."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    val detailHost = el("div", "sector-detail")
    var selected = ""
    var paintDetail: (String) -> Unit = { _ -> }

    fun paint() {
        body.innerHTML = ""
        val grid = el("div", "heatmap-grid")
        grid.appendChild(skeleton(200))
        body.appendChild(grid)
        loadSectors { sectors ->
            grid.innerHTML = ""
            if (sectors.isEmpty()) {
                grid.appendChild(errorState("Sector data unavailable") { paint() })
                return@loadSectors
            }
            for (row in sectors.sortedByDescending { num(pick(it, "changePercent")) ?: 0.0 }) {
                val name = text(pick(row, "sector", "name", "index")) ?: "Sector"
                val pctValue = num(pick(row, "changePercent", "change"))
                val indexValue = num(pick(row, "indexValue", "value"))
                val advancing = num(pick(row, "advancing"))
                val declining = num(pick(row, "declining"))
                val tile = el("button", "heat-tile " + sectorTone(pctValue))
                val intensity = min(abs(pctValue ?: 0.0) / 3.0, 1.0) * 0.32 + 0.06
                val base = if ((pctValue ?: 0.0) >= 0) "34,197,139" else "244,86,109"
                tile.style.background = "linear-gradient(150deg, rgba($base,$intensity), rgba(255,255,255,0.02))"
                tile.appendChild(el("span", "heat-name", name))
                tile.appendChild(el("span", "heat-value", percent(pctValue)))
                tile.appendChild(el("span", "heat-meta", price(indexValue)))
                if (advancing != null && declining != null) {
                    tile.appendChild(el("span", "heat-breadth", integer(advancing) + " up · " + integer(declining) + " down"))
                }
                tile.onClick { selected = name; paintDetail(name) }
                grid.appendChild(tile)
            }
        }
        body.appendChild(detailHost)
        if (selected.isNotEmpty()) paintDetail(selected) else {
            detailHost.innerHTML = ""
            detailHost.appendChild(emptyState("Select a sector", "Tap any sector tile to review its constituents and breadth."))
        }
    }

    paintDetail = { name ->
        detailHost.innerHTML = ""
        val panel = card("sector-constituents")
        panel.appendChild(cardHead(name, "Constituent performance in the live session", actionButton("Close", "ghost", "close") {
            selected = ""
            detailHost.innerHTML = ""
            detailHost.appendChild(emptyState("Select a sector", "Tap any sector tile to review its constituents and breadth."))
        }))
        detailHost.appendChild(panel)
        val loader = el("div")
        loader.appendChild(skeleton(120))
        panel.appendChild(loader)
        Http.get("/api/sector-constituents?sector=" + encode(name), ttlMs = 120_000.0, onData = { payload ->
            loader.innerHTML = ""
            val rows = rowsOf(payload?.data)
            val table = dataTable(listOf("SYMBOL", "COMPANY", "PRICE", "CHANGE", "VOLUME"), setOf(2, 3, 4))
            if (rows.isEmpty()) {
                table.empty("No constituents returned for this sector.")
            } else {
                for (row in rows.sortedByDescending { num(pick(it, "change")) ?: 0.0 }) {
                    val symbol = text(pick(row, "symbol")) ?: "—"
                    table.row(listOf(
                        el("div", "symbol-cell", symbol),
                        el("div", "small", text(pick(row, "companyName", "name")) ?: "—"),
                        el("div", "mono", price(num(pick(row, "price", "ltp")))),
                        el("div", "mono tone-" + toneOf(num(pick(row, "change"))), percent(num(pick(row, "change")))),
                        el("div", "mono", integer(num(pick(row, "volume"))))
                    )) { openSymbol(symbol) }
                }
            }
            loader.appendChild(table.root.parentElement as HTMLElement)
        }, onError = { message ->
            loader.innerHTML = ""
            loader.appendChild(emptyState("Constituents unavailable", message))
        })
    }

    paint()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- market structure

fun marketStructureScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "MARKET STRUCTURE",
        "Participation & breadth",
        "How far the session's move is spread across sectors, and where the liquidity sits."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    withMarket(body) {
        val wrap = el("div", "structure-screen")
        val payload = Ctx.market
        val breadth = marketBreadth(payload)
        val sectors = Ctx.sectors

        val top = el("div", "grid-2")
        val breadthCard = card()
        breadthCard.appendChild(cardHead("Market internals", "Advancing versus declining securities"))
        breadthCard.appendChild(breadthBar(breadth.first, breadth.second, breadth.third))
        val breadthGrid = el("div", "kv-grid")
        breadthGrid.appendChild(pair("Advancing", integer(breadth.first.toDouble())))
        breadthGrid.appendChild(pair("Declining", integer(breadth.second.toDouble())))
        breadthGrid.appendChild(pair("Unchanged", integer(breadth.third.toDouble())))
        breadthGrid.appendChild(pair("Coverage", integer(Ctx.quotes.size.toDouble()) + " securities"))
        breadthCard.appendChild(breadthGrid)
        top.appendChild(breadthCard)

        val liquidityCard = card()
        liquidityCard.appendChild(cardHead("Liquidity profile", "Turnover concentration across reported sectors"))
        val totalSectorTurnover = sectors.sumOf { num(pick(it, "turnover")) ?: 0.0 }
        if (totalSectorTurnover <= 0.0) {
            liquidityCard.appendChild(emptyState("Sector turnover unavailable", "The provider did not report turnover for sector indices in this session."))
        } else {
            val bars = el("div", "share-list")
            for (row in sectors.sortedByDescending { num(pick(it, "turnover")) ?: 0.0 }.take(6)) {
                val name = text(pick(row, "sector", "name", "index")) ?: "Sector"
                val value = num(pick(row, "turnover")) ?: 0.0
                val share = value / totalSectorTurnover
                val line = el("div", "share-row")
                line.appendChild(el("span", "share-name", name))
                line.appendChild(progressBar(share, "accent"))
                line.appendChild(el("span", "share-value", rupees(value)))
                bars.appendChild(line)
            }
            liquidityCard.appendChild(bars)
        }
        top.appendChild(liquidityCard)
        wrap.appendChild(top)

        val tableCard = card()
        tableCard.appendChild(cardHead("Sector breadth table", "Advance/decline counts reported by sector indices"))
        val table = dataTable(listOf("SECTOR", "INDEX", "CHANGE", "ADVANCING", "DECLINING", "TURNOVER"), setOf(1, 2, 3, 4, 5))
        if (sectors.isEmpty()) {
            table.empty("Sector data is unavailable right now.")
        } else {
            for (row in sectors) {
                val name = text(pick(row, "sector", "name", "index")) ?: "Sector"
                val pctValue = num(pick(row, "changePercent", "change"))
                table.row(listOf(
                    el("div", "symbol-cell", name),
                    el("div", "mono", price(num(pick(row, "indexValue")))),
                    el("div", "mono tone-" + toneOf(pctValue), percent(pctValue)),
                    el("div", "mono", integer(num(pick(row, "advancing")))),
                    el("div", "mono", integer(num(pick(row, "declining")))),
                    el("div", "mono", rupees(num(pick(row, "turnover"))))
                ))
            }
        }
        tableCard.appendChild(table.root.parentElement as HTMLElement)
        wrap.appendChild(tableCard)

        val sectorPulse = card()
        sectorPulse.appendChild(cardHead("Sector dispersion", "Distance from the flat line tells you how lopsided the session is"))
        val strip = el("div", "sector-bars")
        for (row in sectors.sortedByDescending { num(pick(it, "changePercent")) ?: 0.0 }) {
            val name = text(pick(row, "sector", "name", "index")) ?: "Sector"
            val pctValue = num(pick(row, "changePercent")) ?: 0.0
            val line = el("div", "dispersion-row")
            line.appendChild(el("span", "dispersion-name", name))
            val track = el("div", "dispersion-track")
            val marker = el("div", "dispersion-marker " + sectorTone(pctValue))
            marker.style.left = (50.0 + pctValue.coerceIn(-6.0, 6.0) / 6.0 * 50.0).toString() + "%"
            track.appendChild(marker)
            line.appendChild(track)
            line.appendChild(el("span", "dispersion-value " + toneClass(pctValue), percent(pctValue)))
            strip.appendChild(line)
        }
        sectorPulse.appendChild(strip)
        wrap.appendChild(sectorPulse)
        loadSectors { if (Ctx.sectors.isNotEmpty() && sectors.isEmpty()) go("structure") }
        return@withMarket wrap
    }
    registerRefresh { }
    return root
}
