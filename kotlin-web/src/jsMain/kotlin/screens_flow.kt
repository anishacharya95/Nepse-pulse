import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- technical screener data

data class TaRow(
    val symbol: String,
    val name: String,
    val price: Double?,
    val change: Double?,
    val rsi: Double?,
    val sma20: Double?,
    val sma50: Double?,
    val sma200: Double?,
    val relVolume: Double?,
    val volume: Double?,
    val high52: Double?,
    val low52: Double?,
    val trend: String,
    val adx: Double?,
    val macdHistogram: Double?
)

fun taRowOf(row: dynamic): TaRow = TaRow(
    symbol = text(pick(row, "symbol"))?.uppercase() ?: "—",
    name = text(pick(row, "companyName", "securityName", "name")) ?: "—",
    price = num(pick(row, "lastTradedPrice", "price", "ltp")),
    change = num(pick(row, "percentageChange", "change")),
    rsi = num(pick(row, "rsi")),
    sma20 = num(pick(row, "sma20")),
    sma50 = num(pick(row, "sma50")),
    sma200 = num(pick(row, "sma200")),
    relVolume = num(pick(row, "relativeVolume", "relVolume")),
    volume = num(pick(row, "volume", "totalTradeQuantity")),
    high52 = num(pick(row, "high52")),
    low52 = num(pick(row, "low52")),
    trend = text(pick(row, "trend")) ?: "—",
    adx = num(pick(row, "adx")),
    macdHistogram = num(pick(row, "macdHistogram"))
)

object TaStore {
    var rows: List<TaRow> = emptyList()
    var at: Double = 0.0
    var universe: Int = 0
    var source: String = ""
    var updated: String = ""
    var error: String? = null
}

fun loadTechnical(force: Boolean = false, done: (Boolean, String?) -> Unit) {
    if (!force && TaStore.rows.isNotEmpty() && nowMs() - TaStore.at < 180_000.0) {
        done(true, null)
        return
    }
    Http.get("/api/technical-screener?limit=250&technical=true&timeframe=D", ttlMs = 300_000.0, force = force, timeoutMs = 120_000, onData = { payload ->
        if (payload == null || payload == undefined || payload.ok == false) {
            TaStore.error = text(pick(payload, "error")) ?: "Technical screener returned no data"
            done(false, TaStore.error)
            return@get
        }
        val rows = rowsOf(payload.data).map { taRowOf(it) }.filter { it.symbol != "—" }
        TaStore.rows = rows
        TaStore.at = nowMs()
        TaStore.universe = num(pick(payload, "universeCount"))?.toInt() ?: rows.size
        TaStore.source = text(pick(payload, "source")) ?: "NEPSE technical screener"
        TaStore.updated = text(pick(payload, "updatedAt")) ?: ""
        TaStore.error = null
        done(true, null)
    }, onError = { message ->
        TaStore.error = message
        done(false, message)
    })
}

// ---------------------------------------------------------------- screener

fun screenerScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "QUANTITATIVE SCREENING",
        "Stock screener",
        "Multi-factor technical scans over the full NEPSE universe using provider OHLCV history."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var preset = "all"
    var trendFilter = "all"
    var minRsi = ""
    var maxRsi = ""
    var sortBy = "change"

    val presets = listOf(
        "all" to "All stocks",
        "oversold" to "RSI oversold",
        "overbought" to "RSI overbought",
        "momentum" to "Momentum",
        "volume" to "Volume surge",
        "above50" to "Above SMA 50",
        "below20" to "Below SMA 20"
    )

    fun paint() {
        body.innerHTML = ""
        val host = el("div")
        host.appendChild(loadingState("Building technical history — the free provider needs up to a minute…"))
        body.appendChild(host)
        loadTechnical { ok, error ->
            host.innerHTML = ""
            if (!ok) {
                host.appendChild(errorState(error ?: "Technical screener is unavailable.") { paint() })
                return@loadTechnical
            }
            val wrap = el("div", "screener")
            val controlCard = card("toolbar-card")
            val chips = el("div", "toolbar-chips wrap")
            for ((key, label) in presets) {
                chips.appendChild(chipButton(label, preset == key) { preset = key; paint() })
            }
            controlCard.appendChild(chips)
            val row = el("div", "toolbar")
            val minInput = field("Min RSI", "number", minRsi)
            minInput.addEventListener("input", { minRsi = minInput.value })
            val maxInput = field("Max RSI", "number", maxRsi)
            maxInput.addEventListener("input", { maxRsi = maxInput.value })
            val trendSelect = selectField(
                listOf("all" to "Any trend", "Uptrend" to "Uptrend", "Mild uptrend" to "Mild uptrend", "Mild downtrend" to "Mild downtrend", "Downtrend" to "Downtrend", "Mixed" to "Mixed"),
                trendFilter
            ) { value -> trendFilter = value; paint() }
            val sortSelect = selectField(
                listOf("change" to "Sort: % change", "rsi" to "Sort: RSI", "volume" to "Sort: relative volume", "price" to "Sort: price"),
                sortBy
            ) { value -> sortBy = value; paint() }
            row.add(minInput, maxInput, trendSelect, sortSelect, actionButton("Apply", "primary", "filter") { paint() })
            controlCard.appendChild(row)
            controlCard.appendChild(el(
                "p", "muted small",
                "Universe: " + integer(TaStore.universe.toDouble()) + " securities · " + TaStore.source +
                    (if (TaStore.updated.isNotBlank()) " · updated " + updatedTime(TaStore.updated) else "")
            ))
            wrap.appendChild(controlCard)

            fun passes(row: TaRow): Boolean {
                if (preset == "oversold" && (row.rsi ?: 100.0) >= 35.0) return false
                if (preset == "overbought" && (row.rsi ?: 0.0) <= 65.0) return false
                if (preset == "momentum" && !((row.change ?: 0.0) > 2.0 && (row.relVolume ?: 0.0) > 1.15)) return false
                if (preset == "volume" && (row.relVolume ?: 0.0) < 1.5) return false
                if (preset == "above50" && (row.price ?: 0.0) <= (row.sma50 ?: Double.MAX_VALUE)) return false
                if (preset == "below20" && (row.price ?: Double.MAX_VALUE) >= (row.sma20 ?: 0.0)) return false
                if (trendFilter != "all" && !row.trend.contains(trendFilter.replace("Mild ", ""), true)) return false
                val low = minRsi.toDoubleOrNull()
                val high = maxRsi.toDoubleOrNull()
                if (low != null && (row.rsi ?: -1.0) < low) return false
                if (high != null && (row.rsi ?: 999.0) > high) return false
                return true
            }

            var rows = TaStore.rows.filter { passes(it) }
            rows = when (sortBy) {
                "rsi" -> rows.sortedBy { it.rsi ?: Double.MAX_VALUE }
                "volume" -> rows.sortedByDescending { it.relVolume ?: 0.0 }
                "price" -> rows.sortedByDescending { it.price ?: 0.0 }
                else -> rows.sortedByDescending { it.change ?: -999.0 }
            }
            val resultCard = card()
            resultCard.appendChild(cardHead(
                "Screen results",
                integer(rows.size.toDouble()) + " securities match the active filters",
                actionButton("Reset", "ghost", "refresh") {
                    preset = "all"; trendFilter = "all"; minRsi = ""; maxRsi = ""; sortBy = "change"; paint()
                }
            ))
            val table = dataTable(listOf("SYMBOL", "LTP", "CHG %", "RSI", "SMA 20", "SMA 50", "REL VOL", "TREND"), setOf(1, 2, 3, 4, 5, 6))
            if (rows.isEmpty()) {
                table.empty("No securities match these filters. Widen the RSI range or choose another preset.")
            } else {
                for (row in rows.take(120)) {
                    val symbolCell = el("div", "symbol-cell")
                    symbolCell.appendChild(el("strong", "", row.symbol))
                    symbolCell.appendChild(el("small", "", row.name.take(34)))
                    val rsiCell = el("div", "mono", row.rsi?.let { fixed(it, 1) } ?: "—")
                    if ((row.rsi ?: 50.0) < 30.0) rsiCell.classList.add("tone-up")
                    if ((row.rsi ?: 50.0) > 70.0) rsiCell.classList.add("tone-down")
                    table.row(listOf(
                        symbolCell,
                        el("div", "mono", price(row.price)),
                        el("div", "mono tone-" + toneOf(row.change), percent(row.change)),
                        rsiCell,
                        el("div", "mono", price(row.sma20)),
                        el("div", "mono", price(row.sma50)),
                        el("div", "mono", row.relVolume?.let { fixed(it, 2) + "×" } ?: "—"),
                        el("div", "small", row.trend)
                    )) { openSymbol(row.symbol) }
                }
            }
            resultCard.appendChild(table.root.parentElement as HTMLElement)
            wrap.appendChild(resultCard)
            host.appendChild(wrap)
        }
    }
    paint()
    registerRefresh { }
    return root
}

fun updatedTime(stamp: String): String {
    if (stamp.length < 16) return "—"
    return stamp.take(16).replace("T", " ")
}

// ---------------------------------------------------------------- quant

fun quantScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "SCREENER & QUANT",
        "Quant workspace",
        "Market-wide quantitative context, saved quant watchlists and cloud screening."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)

    fun paint() {
        withMarket(body) {
            val wrap = el("div", "quant")
            val payload = Ctx.market
            val quotes = Ctx.quotes
            val changes = quotes.mapNotNull { it.pct }
            val median = changes.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val average = if (changes.isEmpty()) null else changes.sum() / changes.size
            val volatility = if (changes.size < 2) null else {
                val mean = changes.sum() / changes.size
                val variance = changes.sumOf { (it - mean) * (it - mean) } / changes.size
                kotlin.math.sqrt(variance)
            }
            val stats = el("div", "stat-grid")
            stats.appendChild(statTile("MEDIAN MOVE", percent(median), "Across " + quotes.size + " live securities"))
            stats.appendChild(statTile("AVERAGE MOVE", percent(average), "Session mean percentage change"))
            stats.appendChild(statTile("DISPERSION", volatility?.let { fixed(it, 2) + "%" } ?: "—", "Standard deviation of changes"))
            stats.appendChild(statTile("TURNOVER", rupees(summaryDouble(payload, "turnover")), "Provider-reported session value"))
            wrap.appendChild(stats)

            val scanCard = card()
            scanCard.appendChild(cardHead("Quant signals", "Computed from provider technical history", actionButton("Run scan", "secondary", "spark") { paint() }))
            val scanHost = el("div")
            scanHost.appendChild(skeleton(100))
            scanCard.appendChild(scanHost)
            wrap.appendChild(scanCard)
            loadTechnical { ok, _ ->
                scanHost.innerHTML = ""
                if (!ok) {
                    scanHost.appendChild(emptyState("Technical scan unavailable", TaStore.error ?: "The provider did not return technical history."))
                    return@loadTechnical
                }
                val rows = TaStore.rows
                val oversold = rows.filter { (it.rsi ?: 100.0) < 35.0 }.sortedBy { it.rsi ?: 100.0 }
                val overbought = rows.filter { (it.rsi ?: 0.0) > 65.0 }.sortedByDescending { it.rsi ?: 0.0 }
                val volume = rows.filter { (it.relVolume ?: 0.0) > 1.5 }.sortedByDescending { it.relVolume ?: 0.0 }
                val grid = el("div", "grid-3")
                grid.appendChild(signalList("Oversold candidates", "RSI below 35", oversold, "rsi"))
                grid.appendChild(signalList("Overbought candidates", "RSI above 65", overbought, "rsi"))
                grid.appendChild(signalList("Unusual volume", "Relative volume above 1.5×", volume, "volume"))
                scanHost.appendChild(grid)
            }

            val watchCard = card()
            watchCard.appendChild(cardHead("Quant watchlist", "Fast access to your priority symbols"))
            val rail = el("div", "symbol-rail")
            rail.appendChild(skeleton(40))
            watchCard.appendChild(rail)
            wrap.appendChild(watchCard)
            val localSymbols = localWatchlist()
            val watchSymbols = if (localSymbols.isNotEmpty()) localSymbols else listOf("NICA", "NABIL", "SCB", "NTC")
            rail.innerHTML = ""
            for (symbol in watchSymbols.take(10)) {
                val quote = quoteFor(symbol)
                val tile = el("button", "symbol-tile")
                tile.appendChild(el("span", "symbol-tile-name", symbol))
                tile.appendChild(el("span", "symbol-tile-price", price(quote?.ltp)))
                tile.appendChild(el("span", "symbol-tile-change " + toneClass(quote?.pct), percent(quote?.pct)))
                tile.onClick { openSymbol(symbol) }
                rail.appendChild(tile)
            }
            val addRow = el("div", "inline-actions")
            addRow.appendChild(actionButton("Add symbol", "ghost", "plus") { promptAddWatchlist { paint() } })
            watchCard.appendChild(addRow)

            val directory = el("div", "grid-2")
            val topMomentum = quotes.filter { (it.pct ?: 0.0) > 0 }.sortedByDescending { (it.pct ?: 0.0) * (it.volume ?: 1.0) }.take(8)
            val topWeakness = quotes.filter { (it.pct ?: 0.0) < 0 }.sortedBy { (it.pct ?: 0.0) * (it.volume ?: 1.0) }.take(8)
            directory.appendChild(moversCard("Risk-on momentum", "Change weighted by session volume", topMomentum, true))
            directory.appendChild(moversCard("Risk-off pressure", "Change weighted by session volume", topWeakness, false))
            wrap.appendChild(directory)
            return@withMarket wrap
        }
    }
    paint()
    registerRefresh { }
    return root
}

fun signalList(title: String, hint: String, rows: List<TaRow>, kind: String): HTMLElement {
    val panel = card("signal-card")
    panel.appendChild(cardHead(title, hint))
    if (rows.isEmpty()) {
        panel.appendChild(el("p", "muted small", "No securities currently qualify."))
        return panel
    }
    for (row in rows.take(6)) {
        val line = el("button", "signal-row")
        line.appendChild(el("span", "signal-symbol", row.symbol))
        val value = when (kind) {
            "rsi" -> row.rsi?.let { "RSI " + fixed(it, 1) } ?: "—"
            else -> row.relVolume?.let { fixed(it, 2) + "×" } ?: "—"
        }
        line.appendChild(el("span", "signal-value", value))
        line.appendChild(el("span", "signal-change " + toneClass(row.change), percent(row.change)))
        line.onClick { openSymbol(row.symbol) }
        panel.appendChild(line)
    }
    return panel
}

// ---------------------------------------------------------------- floorsheet

object FloorsheetStore {
    var rows: List<dynamic> = emptyList()
    var at: Double = 0.0
    var businessDate: String = ""
    var error: String? = null
}

fun loadFloorsheet(force: Boolean = false, done: (Boolean, String?) -> Unit) {
    if (!force && FloorsheetStore.rows.isNotEmpty() && nowMs() - FloorsheetStore.at < 120_000.0) {
        done(true, null)
        return
    }
    Http.get("/api/floorsheet?limit=500", ttlMs = 120_000.0, force = force, timeoutMs = 45_000, onData = { payload ->
        val rows = rowsOf(payload.data)
        if (rows.isEmpty()) {
            FloorsheetStore.error = "The provider returned no executed contracts for this session."
            done(false, FloorsheetStore.error)
            return@get
        }
        FloorsheetStore.rows = rows
        FloorsheetStore.at = nowMs()
        FloorsheetStore.businessDate = text(pick(payload, "businessDate")) ?: ""
        FloorsheetStore.error = null
        done(true, null)
    }, onError = { message ->
        FloorsheetStore.error = message
        done(false, message)
    })
}

data class BrokerFlow(val id: String, val name: String, val buy: Double, val sell: Double, var trades: Int) {
    val net: Double get() = buy - sell
    val total: Double get() = buy + sell
}

fun brokerFlows(rows: List<dynamic>): List<BrokerFlow> {
    val merged = LinkedHashMap<String, BrokerFlow>()
    for (row in rows) {
        val amount = num(pick(row, "amount", "contractAmount")) ?: 0.0
        val buyerId = text(pick(row, "buyerBrokerId", "buyerMemberId")) ?: "?"
        val sellerId = text(pick(row, "sellerBrokerId", "sellerMemberId")) ?: "?"
        val buyerName = text(pick(row, "buyerBrokerName", "buyerBroker")) ?: "Broker $buyerId"
        val sellerName = text(pick(row, "sellerBrokerName", "sellerBroker")) ?: "Broker $sellerId"
        val buyEntry = merged[buyerId]
        merged[buyerId] = if (buyEntry == null) {
            BrokerFlow(buyerId, buyerName, amount, 0.0, 1)
        } else {
            BrokerFlow(buyEntry.id, buyEntry.name, buyEntry.buy + amount, buyEntry.sell, buyEntry.trades + 1)
        }
        val sellEntry = merged[sellerId]
        merged[sellerId] = if (sellEntry == null) {
            BrokerFlow(sellerId, sellerName, 0.0, amount, 1)
        } else {
            BrokerFlow(sellEntry.id, sellEntry.name, sellEntry.buy, sellEntry.sell + amount, sellEntry.trades + 1)
        }
    }
    return merged.values.sortedByDescending { it.total }
}

// ---------------------------------------------------------------- broker analytics

fun brokerAnalyticsScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "BROKER INTELLIGENCE",
        "Broker analytics",
        "Net flow, turnover concentration and the counterparties driving today's contracts."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var view = "flow"

    fun paint() {
        body.innerHTML = ""
        val host = el("div")
        host.appendChild(loadingState("Aggregating broker flow from the floorsheet…"))
        body.appendChild(host)
        loadFloorsheet { ok, error ->
            host.innerHTML = ""
            if (!ok) {
                host.appendChild(errorState(error ?: "Broker flow is unavailable.") { paint() })
                return@loadFloorsheet
            }
            val wrap = el("div", "brokers-screen")
            val flows = brokerFlows(FloorsheetStore.rows)
            val total = flows.sumOf { it.total }
            val buys = flows.sumOf { it.buy }
            val sells = flows.sumOf { it.sell }
            val stats = el("div", "stat-grid")
            stats.appendChild(statTile("BROKERS ACTIVE", integer(flows.size.toDouble()), "Distinct members in the sample"))
            stats.appendChild(statTile("TOTAL BUY VALUE", rupees(buys), "Sum of buyer-side contract value"))
            stats.appendChild(statTile("TOTAL SELL VALUE", rupees(sells), "Sum of seller-side contract value"))
            stats.appendChild(statTile(
                "NET FLOW",
                rupees(buys - sells),
                "Positive means net buying pressure",
                if (buys - sells >= 0) "up" else "down"
            ))
            wrap.appendChild(stats)

            val tabs = el("div", "tabs")
            for ((key, label) in listOf("flow" to "Net flow", "turnover" to "Turnover", "pairs" to "Counterparties")) {
                tabs.appendChild(chipButton(label, view == key) { view = key; paint() })
            }
            wrap.appendChild(tabs)

            if (view == "pairs") {
                val pairMap = LinkedHashMap<String, Double>()
                for (row in FloorsheetStore.rows) {
                    val buyer = text(pick(row, "buyerBrokerId")) ?: "?"
                    val seller = text(pick(row, "sellerBrokerId")) ?: "?"
                    val key = "$buyer→$seller"
                    pairMap[key] = (pairMap[key] ?: 0.0) + (num(pick(row, "amount")) ?: 0.0)
                }
                val names = HashMap<String, String>()
                for (row in FloorsheetStore.rows) {
                    text(pick(row, "buyerBrokerId"))?.let { names[it] = text(pick(row, "buyerBrokerName")) ?: "Broker $it" }
                    text(pick(row, "sellerBrokerId"))?.let { names[it] = text(pick(row, "sellerBrokerName")) ?: "Broker $it" }
                }
                val cardNode = card()
                cardNode.appendChild(cardHead("Most active counterparties", "Broker-to-broker contract value in this sample"))
                val table = dataTable(listOf("BUYER", "SELLER", "VALUE", "SHARE"), setOf(2, 3))
                val pairTotal = pairMap.values.sum().coerceAtLeast(1.0)
                for ((key, value) in pairMap.entries.sortedByDescending { it.value }.take(30)) {
                    val parts = key.split("→")
                    table.row(listOf(
                        el("div", "small", names[parts.getOrNull(0)] ?: key),
                        el("div", "small", names[parts.getOrNull(1)] ?: "—"),
                        el("div", "mono", rupees(value)),
                        el("div", "mono", fixed(value / pairTotal * 100.0, 1) + "%")
                    ))
                }
                cardNode.appendChild(table.root.parentElement as HTMLElement)
                wrap.appendChild(cardNode)
            } else {
                val sorted = if (view == "turnover") flows.sortedByDescending { it.total } else flows.sortedByDescending { it.net }
                val cardNode = card()
                cardNode.appendChild(cardHead(
                    if (view == "turnover") "Broker turnover ranking" else "Net flow ranking",
                    if (view == "turnover") "Total reported buy and sell value" else "Net buying (positive) versus net selling (negative)"
                ))
                val table = dataTable(listOf("BROKER", "BUY", "SELL", "NET", "SHARE"), setOf(1, 2, 3, 4))
                for (flow in sorted.take(30)) {
                    val name = el("div", "broker-cell")
                    name.appendChild(el("strong", "", "Broker " + flow.id))
                    name.appendChild(el("small", "", flow.name.take(36)))
                    table.row(listOf(
                        name,
                        el("div", "mono", rupees(flow.buy)),
                        el("div", "mono", rupees(flow.sell)),
                        el("div", "mono tone-" + toneOf(flow.net), rupees(flow.net)),
                        el("div", "mono", if (total > 0) fixed(flow.total / total * 100.0, 1) + "%" else "—")
                    ))
                }
                cardNode.appendChild(table.root.parentElement as HTMLElement)
                wrap.appendChild(cardNode)
            }
            host.appendChild(wrap)
        }
    }
    paint()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- traders zone

data class ScanDef(val id: String, val name: String, val description: String, val icon: String, val test: (TaRow) -> Boolean)

fun traderScans(): List<ScanDef> = listOf(
    ScanDef("breakout", "52-week breakout", "Price within 3% of the 52-week high", "arrowUp") {
        val price = it.price ?: 0.0
        val high = it.high52 ?: 0.0
        high > 0.0 && price >= high * 0.97
    },
    ScanDef("nearlow", "Near 52-week low", "Price within 3% of the 52-week low", "arrowDown") {
        val price = it.price ?: 0.0
        val low = it.low52 ?: 0.0
        low > 0.0 && price <= low * 1.03
    },
    ScanDef("oversold", "Oversold reversal watch", "RSI below 35 with price above the 20-day average", "spark") {
        (it.rsi ?: 100.0) < 35.0 && (it.price ?: 0.0) > (it.sma20 ?: Double.MAX_VALUE)
    },
    ScanDef("overbought", "Overbought exhaustion", "RSI above 70 and price below the 20-day average", "spark") {
        (it.rsi ?: 0.0) > 70.0 && (it.price ?: Double.MAX_VALUE) < (it.sma20 ?: 0.0)
    },
    ScanDef("volumesurge", "Volume surge", "Relative volume above 2× the 20-day average", "chart") {
        (it.relVolume ?: 0.0) > 2.0
    },
    ScanDef("strongtrend", "Strong trend", "ADX above 25 with price above SMA 50", "arrowUp") {
        (it.adx ?: 0.0) > 25.0 && (it.price ?: 0.0) > (it.sma50 ?: Double.MAX_VALUE)
    },
    ScanDef("macd", "MACD momentum", "Positive MACD histogram with price above SMA 20", "chart") {
        (it.macdHistogram ?: 0.0) > 0.0 && (it.price ?: 0.0) > (it.sma20 ?: Double.MAX_VALUE)
    },
    ScanDef("compression", "Average compression", "20-day average within 1% of the 50-day average", "layers") {
        val a = it.sma20 ?: 0.0
        val b = it.sma50 ?: 0.0
        b > 0.0 && abs(a - b) / b < 0.01
    },
    ScanDef("uptrend", "Uptrend continuation", "Price above the 20, 50 and 200-day averages", "arrowUp") {
        val price = it.price ?: 0.0
        price > 0.0 && price > (it.sma20 ?: Double.MAX_VALUE) && price > (it.sma50 ?: Double.MAX_VALUE) && price > (it.sma200 ?: Double.MAX_VALUE)
    },
    ScanDef("downtrend", "Downtrend pressure", "Price below the 20, 50 and 200-day averages", "arrowDown") {
        val price = it.price ?: 0.0
        price > 0.0 && price < (it.sma20 ?: 0.0) && price < (it.sma50 ?: 0.0) && price < (it.sma200 ?: 0.0)
    },
    ScanDef("strength", "Session strength", "Up more than 4% today", "traders") { (it.change ?: 0.0) > 4.0 },
    ScanDef("weakness", "Session weakness", "Down more than 3% today", "traders") { (it.change ?: 0.0) < -3.0 }
)

fun tradersScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "TRADERS ZONE",
        "Scan desk",
        "Twelve price, volume and trend scans computed from provider OHLCV history."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var active = "breakout"

    fun paint() {
        body.innerHTML = ""
        val host = el("div")
        host.appendChild(loadingState("Preparing the scan universe — provider history can take up to a minute…"))
        body.appendChild(host)
        loadTechnical { ok, error ->
            host.innerHTML = ""
            if (!ok) {
                host.appendChild(errorState(error ?: "Scans need technical history that is currently unavailable.") { paint() })
                return@loadTechnical
            }
            val wrap = el("div", "traders")
            val grid = el("div", "scan-grid")
            val scans = traderScans()
            for (scan in scans) {
                val results = TaStore.rows.filter(scan.test)
                val tile = el("button", "scan-tile" + if (scan.id == active) " scan-active" else "")
                val top = el("div", "scan-top")
                top.appendChild(icon(scan.icon, 16))
                top.appendChild(el("span", "scan-count", results.size.toString()))
                tile.appendChild(top)
                tile.appendChild(el("span", "scan-name", scan.name))
                tile.appendChild(el("span", "scan-desc", scan.description))
                tile.onClick { active = scan.id; paint() }
                grid.appendChild(tile)
            }
            wrap.appendChild(grid)

            val scan = scans.firstOrNull { it.id == active } ?: scans.first()
            val results = TaStore.rows.filter(scan.test).sortedByDescending { it.change ?: 0.0 }
            val panel = card()
            panel.appendChild(cardHead(scan.name, scan.description + " · " + results.size + " matches"))
            val table = dataTable(listOf("SYMBOL", "LTP", "CHG %", "RSI", "REL VOL", "TREND"), setOf(1, 2, 3, 4))
            if (results.isEmpty()) {
                table.empty("No securities currently qualify for this scan.")
            } else {
                for (row in results.take(80)) {
                    val cell = el("div", "symbol-cell")
                    cell.appendChild(el("strong", "", row.symbol))
                    cell.appendChild(el("small", "", row.name.take(34)))
                    table.row(listOf(
                        cell,
                        el("div", "mono", price(row.price)),
                        el("div", "mono tone-" + toneOf(row.change), percent(row.change)),
                        el("div", "mono", row.rsi?.let { fixed(it, 1) } ?: "—"),
                        el("div", "mono", row.relVolume?.let { fixed(it, 2) + "×" } ?: "—"),
                        el("div", "small", row.trend)
                    )) { openSymbol(row.symbol) }
                }
            }
            panel.appendChild(table.root.parentElement as HTMLElement)
            wrap.appendChild(panel)
            host.appendChild(wrap)
        }
    }
    paint()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- AI tools

fun aiToolsScreen(initialSymbol: String = ""): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "AI RESEARCH",
        "AI tools",
        "Trendline detection and an evidence-backed stock detective built on real price and volume history."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var symbol = initialSymbol.uppercase()
    var tool = "detective"
    var buildTrendTool: (List<Bar>, Analysis) -> Unit = { _, _ -> }
    var buildDetective: (List<Bar>, Analysis) -> Unit = { _, _ -> }

    fun paint() {
        body.innerHTML = ""
        val controls = card("toolbar-card")
        val row = el("div", "toolbar")
        val input = field("Symbol, for example NABIL", "search", symbol)
        row.appendChild(input)
        row.appendChild(actionButton("Run analysis", "primary", "ai") {
            val next = input.value.trim().uppercase()
            if (next.isBlank()) {
                toast("Enter a NEPSE symbol first.", "error")
            } else {
                symbol = next
                paint()
            }
        })
        row.appendChild(segmented(listOf("detective" to "Stock detective", "trend" to "Trendlines"), tool) {
            tool = it
            paint()
        })
        controls.appendChild(row)
        body.appendChild(controls)

        if (symbol.isBlank()) {
            body.appendChild(emptyState(
                "Pick a symbol to investigate",
                "The detective studies daily candles, moving averages, RSI, volume and swing structure, then explains what it sees without promising outcomes."
            ))
            return
        }

        val loading = card()
        loading.appendChild(skeleton(180))
        body.appendChild(loading)
        loadHistory(symbol, countback = 500, onData = { bars ->
            loading.remove()
            if (bars.size < 30) {
                body.appendChild(emptyState("Not enough history", "At least 30 daily candles are required for this analysis."))
                return@loadHistory
            }
            val analysis = analyzeBars(bars)
            if (tool == "trend") buildTrendTool(bars, analysis) else buildDetective(bars, analysis)
        }, onError = { message ->
            loading.remove()
            body.appendChild(errorState(message) { paint() })
        })
    }

    buildTrendTool = { bars, analysis ->
        val panel = card()
        panel.appendChild(cardHead("Trendline detection", symbol + " · " + bars.size + " daily candles"))
        panel.appendChild(candleChart(bars, 340))
        val grid = el("div", "kv-grid")
        grid.appendChild(pair("Projected resistance", price(analysis.resistance)))
        grid.appendChild(pair("Projected support", price(analysis.support)))
        grid.appendChild(pair("Last close", price(analysis.last)))
        grid.appendChild(pair("Structure", analysis.pattern))
        grid.appendChild(pair("Trend", analysis.trend))
        grid.appendChild(pair("Volume confirmation", analysis.volumeRatio?.let { fixed(it, 2) + "× 20-day average" } ?: "Unavailable"))
        panel.appendChild(grid)
        val note = el("p", "muted small", "Trendlines are fitted to the most recent confirmed swing highs and lows. A projection is a reference level, not a breakout signal on its own.")
        panel.appendChild(note)
        body.appendChild(panel)
    }

    buildDetective = { bars, analysis ->
        val panel = card("detective")
        panel.appendChild(cardHead("AI stock detective", symbol + " · evidence-backed read"))
        val head = el("div", "detective-head")
        val score = el("div", "score-ring big")
        score.style.setProperty("--score", analysis.score.toString() + "%")
        score.appendChild(el("strong", "", analysis.score.toString()))
        score.appendChild(el("span", "", "of 100"))
        head.appendChild(score)
        val copy = el("div")
        copy.appendChild(el("div", "eyebrow", "VERDICT"))
        copy.appendChild(el("h2", "score-verdict", verdictFor(analysis)))
        copy.appendChild(el("p", "muted", "Evidence is drawn from the provider's daily OHLCV series. This is a research aid, not investment advice."))
        head.appendChild(copy)
        panel.appendChild(head)

        val evidence = el("div", "evidence-grid")
        evidence.appendChild(evidenceCard("Trend", analysis.trend, analysis.notes.take(2)))
        evidence.appendChild(evidenceCard(
            "Momentum",
            analysis.rsi?.let { "RSI " + fixed(it, 1) } ?: "Unavailable",
            listOfNotNull(
                if ((analysis.rsi ?: 50.0) < 30.0) "Oversold territory" else null,
                if ((analysis.rsi ?: 50.0) > 70.0) "Overbought territory" else null,
                analysis.changePct?.let { "Latest session " + percent(it) }
            )
        ))
        evidence.appendChild(evidenceCard(
            "Volume",
            analysis.volumeRatio?.let { fixed(it, 2) + "×" } ?: "Unavailable",
            listOfNotNull(
                if ((analysis.volumeRatio ?: 0.0) > 1.5) "Above-normal participation" else null,
                if ((analysis.volumeRatio ?: 1.0) < 0.6) "Quiet participation" else null,
                "Compared with the 20-day average"
            )
        ))
        evidence.appendChild(evidenceCard(
            "Range position",
            if (analysis.high52 != null && analysis.low52 != null && analysis.last != null) {
                val span = (analysis.high52 - analysis.low52).coerceAtLeast(0.0001)
                fixed((analysis.last - analysis.low52) / span * 100.0, 0) + "% of 52w range"
            } else "Unavailable",
            listOfNotNull("52w high " + price(analysis.high52), "52w low " + price(analysis.low52))
        ))
        evidence.appendChild(evidenceCard("Support", price(analysis.support), listOf("Projected from recent swing lows")))
        evidence.appendChild(evidenceCard("Resistance", price(analysis.resistance), listOf("Projected from recent swing highs")))
        panel.appendChild(evidence)

        val notes = el("ul", "note-list")
        for (note in analysis.notes) notes.appendChild(el("li", "", note))
        panel.appendChild(notes)

        val risk = el("div", "risk-note")
        risk.appendChild(icon("info", 16))
        risk.appendChild(el("p", "", "Risk check: verify liquidity, corporate actions and broader market context before acting. The model does not know your position size, horizon or risk tolerance."))
        panel.appendChild(risk)

        val actions = el("div", "inline-actions")
        actions.appendChild(actionButton("Open X-Ray", "secondary", "xray") { openSymbol(symbol) })
        actions.appendChild(actionButton("Add alert", "ghost", "alerts") { openAlertDialog(symbol) })
        panel.appendChild(actions)
        body.appendChild(panel)
    }

    paint()
    registerRefresh { }
    return root
}

fun evidenceCard(title: String, value: String, bullets: List<String>): HTMLElement {
    val cardNode = el("div", "evidence")
    cardNode.appendChild(el("div", "eyebrow", title.uppercase()))
    cardNode.appendChild(el("div", "evidence-value", value))
    for (bullet in bullets) cardNode.appendChild(el("div", "evidence-line", bullet))
    return cardNode
}
