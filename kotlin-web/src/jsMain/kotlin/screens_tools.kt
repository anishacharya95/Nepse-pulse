import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------- shared helpers

private fun toolField(label: String, input: HTMLElement): HTMLElement {
    val wrap = el("div", "tool-field")
    wrap.appendChild(el("span", "tool-label", label))
    wrap.appendChild(input)
    return wrap
}

private fun numberField(placeholder: String, value: String = ""): HTMLInputElement {
    val input = field(placeholder, "number", value)
    input.step = "any"
    input.className = "field"
    return input
}

private fun toolGrid(vararg items: HTMLElement): HTMLElement {
    val grid = el("div", "tool-grid")
    for (item in items) grid.appendChild(item)
    return grid
}

private fun noteLine(message: String, tone: String = "neutral"): HTMLElement =
    el("p", "tool-note tone-" + tone, message)

private fun loadSeriesBatch(symbols: List<String>, done: (Map<String, List<Bar>>) -> Unit) {
    val out = LinkedHashMap<String, List<Bar>>()
    fun step(index: Int) {
        if (index >= symbols.size) {
            done(out)
            return
        }
        val symbol = symbols[index]
        fetchBars(symbol, "D", 300) { bars, _ ->
            if (bars.isNotEmpty()) out[symbol] = bars
            step(index + 1)
        }
    }
    step(0)
}

// ---------------------------------------------------------------- tools hub

data class ToolEntry(val route: String, val title: String, val copy: String, val iconName: String)

val TOOL_ENTRIES = listOf(
    ToolEntry("replay", "Chart replay", "Replay real candles bar by bar without look-ahead.", "clock"),
    ToolEntry("risk", "Risk calculator", "Position size, stop distance and reward-to-risk for a trade.", "shield"),
    ToolEntry("portfolio-risk", "Portfolio risk", "Drawdown, volatility, concentration and correlation for your holdings.", "portfolio"),
    ToolEntry("scenario", "Scenario simulator", "Shock a stock or your portfolio and read the arithmetic outcome.", "spark"),
    ToolEntry("hypo", "Hypothetical positions", "Model trades with no money and track their live profit and loss.", "layers"),
    ToolEntry("compare", "Stock comparison", "Normalised performance and risk for up to four securities.", "market")
)

fun toolsHubScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "DECISION TOOLS",
        "Tools",
        "Six focused tools for planning, sizing and stress-testing positions. Every number is computed from live quotes and the history service.",
        listOf(actionButton("Advanced charts", "secondary", "chart") { go("charts") })
    ))
    body.appendChild(pageHead("", "Trading and risk", "Open a tool. Nothing here places an order or moves money."))
    val grid = el("div", "tool-hub")
    for (entry in TOOL_ENTRIES) {
        val tile = el("button", "tool-tile")
        val iconWrap = el("span", "tool-tile-icon")
        iconWrap.appendChild(icon(entry.iconName, 20))
        tile.appendChild(iconWrap)
        tile.appendChild(el("span", "tool-tile-title", entry.title))
        tile.appendChild(el("span", "tool-tile-copy", entry.copy))
        tile.appendChild(el("span", "tool-tile-link", "Open →"))
        tile.onClick { go(entry.route) }
        grid.appendChild(tile)
    }
    body.appendChild(grid)
    root.appendChild(body)
    return root
}

// ---------------------------------------------------------------- risk calculator

fun riskCalculatorScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "POSITION SIZING",
        "Risk calculator",
        "Work out the capital at risk, the reward-to-risk ratio and a position size that respects your risk budget.",
        listOf(actionButton("All tools", "secondary", "layers") { go("tools") })
    ))
    root.appendChild(body)

    var side = "long"
    val entry = numberField("e.g. 520.50")
    val stop = numberField("e.g. 495.00")
    val target = numberField("e.g. 580.00")
    val quantity = numberField("Optional — computed when blank")
    val capital = numberField("", "100000")
    val riskPercent = numberField("", "1")
    val riskHost = el("div", "risk-panel")
    val sideHost = el("div", "segmented")

    val form = card("tool-card")
    form.appendChild(cardHead("Trade inputs", "Long or short, with an optional reward target"))
    form.appendChild(sideHost)
    form.appendChild(toolGrid(
        toolField("Entry price", entry),
        toolField("Stop loss", stop),
        toolField("Target (optional)", target),
        toolField("Quantity (optional)", quantity),
        toolField("Capital", capital),
        toolField("Risk per trade (%)", riskPercent)
    ))
    form.appendChild(el("div", "toolbar"))
    form.appendChild(riskHost)
    body.appendChild(form)

    var compute: () -> Unit = {}

    fun paintSide() {
        sideHost.innerHTML = ""
        sideHost.appendChild(chipButton("Long", side == "long") {
            side = "long"
            paintSide()
            compute()
        })
        sideHost.appendChild(chipButton("Short", side == "short") {
            side = "short"
            paintSide()
            compute()
        })
    }

    compute = calc@{
        riskHost.innerHTML = ""
        val entryValue = num(entry.value)
        val stopValue = num(stop.value)
        if (entryValue == null || stopValue == null) {
            riskHost.appendChild(noteLine("Enter both an entry price and a stop loss.", "neutral"))
            return@calc
        }
        val perShare = abs(entryValue - stopValue)
        if (perShare <= 0.0) {
            riskHost.appendChild(noteLine("The stop loss must differ from the entry price.", "down"))
            return@calc
        }
        val targetValue = num(target.value)
        val validStop = if (side == "long") stopValue < entryValue else stopValue > entryValue
        val validTarget = targetValue == null || (if (side == "long") targetValue > entryValue else targetValue < entryValue)
        if (validTarget && targetValue != null && targetValue == entryValue) {
            riskHost.appendChild(noteLine("The target equals the entry price, so reward is zero.", "neutral"))
        }
        if (!validStop) {
            riskHost.appendChild(noteLine(
                if (side == "long") "For a long trade the stop must sit below the entry." else "For a short trade the stop must sit above the entry.",
                "down"
            ))
            return@calc
        }

        val capitalValue = num(capital.value) ?: 100000.0
        val riskPctValue = num(riskPercent.value) ?: 1.0
        val riskBudget = capitalValue * riskPctValue / 100.0
        val suggested = if (riskBudget > 0.0) floor(riskBudget / perShare) else 0.0
        val manual = num(quantity.value)
        val qty = if (manual != null && manual > 0.0) manual else suggested
        if (qty <= 0.0) {
            riskHost.appendChild(noteLine("The risk budget is too small for one share at this stop distance.", "down"))
            return@calc
        }
        val riskTotal = perShare * qty
        val positionValue = entryValue * qty
        val rewardPerShare = if (targetValue != null) abs(targetValue - entryValue) else null
        val rewardTotal = rewardPerShare?.let { it * qty }
        val ratio = rewardPerShare?.let { it / perShare }

        val grid = el("div", "stat-grid")
        grid.appendChild(statTile("Suggested size", integer(suggested), "Shares at " + percent(riskPctValue) + " risk"))
        grid.appendChild(statTile("Position size", integer(qty), "Shares used below"))
        grid.appendChild(statTile("Position value", rupees(positionValue), "At entry"))
        grid.appendChild(statTile("Capital at risk", rupees(riskTotal), percent(riskTotal / max(capitalValue, 1.0) * 100.0), "down"))
        grid.appendChild(statTile("Risk per share", price(perShare), percent(perShare / entryValue * 100.0)))
        if (rewardTotal != null && ratio != null) {
            grid.appendChild(statTile("Reward at target", rupees(rewardTotal), percent(rewardTotal / max(riskTotal, 1.0) * 100.0), "up"))
            grid.appendChild(statTile("Reward : risk", fixed(ratio, 2) + " : 1", when {
                ratio >= 3.0 -> "Strong asymmetry"
                ratio >= 2.0 -> "Healthy asymmetry"
                ratio >= 1.0 -> "Modest asymmetry"
                else -> "Poor asymmetry"
            }, if (ratio >= 2.0) "up" else "down"))
        }
        grid.appendChild(statTile("Stop distance", percent(perShare / entryValue * 100.0), if (side == "long") "Below entry" else "Above entry"))
        riskHost.appendChild(grid)

        if (!validTarget) {
            riskHost.appendChild(noteLine(
                if (side == "long") "The target sits below the entry for a long trade, so it is ignored." else "The target sits above the entry for a short trade, so it is ignored.",
                "down"
            ))
        }
        riskHost.appendChild(noteLine("Sizing is arithmetic only. It ignores brokerage, slippage, circuit limits and liquidity.", "neutral"))
    }

    entry.addEventListener("input", { compute() })
    stop.addEventListener("input", { compute() })
    target.addEventListener("input", { compute() })
    quantity.addEventListener("input", { compute() })
    capital.addEventListener("input", { compute() })
    riskPercent.addEventListener("input", { compute() })
    paintSide()
    compute()
    return root
}

// ---------------------------------------------------------------- portfolio risk

fun portfolioRiskScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "RISK ANALYTICS",
        "Portfolio risk",
        "Volatility, drawdown, concentration and correlation computed from up to 260 daily closes for each holding in your portfolio.",
        listOf(actionButton("Open portfolio", "secondary", "portfolio") { go("portfolio") })
    ))
    root.appendChild(body)

    val holdings = loadHoldings()
    if (holdings.isEmpty()) {
        body.appendChild(emptyState(
            "No holdings yet",
            "Add holdings in the portfolio screen and this tool will measure their combined risk.",
            "Open portfolio"
        ) { go("portfolio") })
        return root
    }
    body.appendChild(loadingState("Computing portfolio risk from daily history…"))

    loadSeriesBatch(holdings.map { it.symbol }.distinct()) { series ->
        body.innerHTML = ""
        val usable = holdings.mapNotNull { holding ->
            val bars = series[holding.symbol] ?: return@mapNotNull null
            if (bars.size < 30) null else holding to bars
        }
        if (usable.isEmpty()) {
            body.appendChild(emptyState("History unavailable", "The history service returned no usable daily series for these holdings."))
            return@loadSeriesBatch
        }
        val length = usable.minOf { it.second.size }.coerceAtMost(260)
        val values = usable.map { (holding, _) ->
            val live = quoteFor(holding.symbol)?.ltp ?: holding.average
            holding.quantity * live
        }
        val totalValue = values.sum().let { if (it <= 0.0) 1.0 else it }
        val weights = values.map { it / totalValue }

        val closesBySymbol = LinkedHashMap<String, List<Double>>()
        for ((holding, bars) in usable) {
            closesBySymbol[holding.symbol] = bars.takeLast(length).map { it.close }
        }
        val returnsBySymbol = LinkedHashMap<String, List<Double>>()
        for ((symbol, closes) in closesBySymbol) returnsBySymbol[symbol] = returnsOf(closes)

        val days = length - 1
        val portfolioReturns = ArrayList<Double>(days)
        for (day in 0 until days) {
            var value = 0.0
            for (i in usable.indices) {
                val symbol = usable[i].first.symbol
                val seriesReturns = returnsBySymbol[symbol] ?: continue
                val r = seriesReturns.getOrNull(day) ?: 0.0
                value += weights[i] * r
            }
            portfolioReturns.add(value)
        }
        val equity = ArrayList<Double>()
        var running = 100000.0
        equity.add(running)
        for (r in portfolioReturns) {
            running *= 1.0 + r
            equity.add(running)
        }

        val totalReturn = (equity.last() / equity.first() - 1.0) * 100.0
        val drawdown = maxDrawdown(equity)
        val volatility = annualisedVolatility(portfolioReturns)
        val bestDay = (portfolioReturns.maxOrNull() ?: 0.0) * 100.0
        val worstDay = (portfolioReturns.minOrNull() ?: 0.0) * 100.0
        val effectiveN = 1.0 / weights.sumOf { it * it }
        var pairCount = 0
        var pairSum = 0.0
        for (i in usable.indices) {
            for (j in i + 1 until usable.size) {
                val a = returnsBySymbol[usable[i].first.symbol] ?: continue
                val b = returnsBySymbol[usable[j].first.symbol] ?: continue
                val value = correlationOf(a, b) ?: continue
                pairSum += value
                pairCount += 1
            }
        }
        val averageCorrelation = if (pairCount > 0) pairSum / pairCount else null

        val wrap = el("div", "portfolio-risk")
        val metrics = el("div", "stat-grid")
        metrics.appendChild(statTile("Portfolio value", rupees(totalValue), integer(usable.size.toDouble()) + " holdings"))
        metrics.appendChild(statTile("Total return", percent(totalReturn), integer(length.toDouble()) + " sessions", toneClass(totalReturn)))
        metrics.appendChild(statTile("Max drawdown", percent(drawdown), "Peak to trough", "down"))
        metrics.appendChild(statTile("Volatility", percent(volatility), "Annualised", if (volatility > 35) "down" else "neutral"))
        metrics.appendChild(statTile("Effective holdings", fixed(effectiveN, 2), "1 / Σ weight²"))
        metrics.appendChild(statTile("Average correlation", if (averageCorrelation == null) "—" else fixed(averageCorrelation, 2), "Pairwise daily returns"))
        metrics.appendChild(statTile("Best day", percent(bestDay), "Weighted portfolio", "up"))
        metrics.appendChild(statTile("Worst day", percent(worstDay), "Weighted portfolio", "down"))
        wrap.appendChild(metrics)

        val chartCard = card("chart-card")
        chartCard.appendChild(cardHead("Portfolio equity curve", "Weighted holdings rebased to Rs 100,000"))
        val curveBars = ArrayList<Bar>()
        val baseBars = usable[0].second.takeLast(length)
        for (i in equity.indices) {
            val time = baseBars.getOrNull(min(i, baseBars.size - 1))?.time ?: i.toDouble()
            curveBars.add(Bar(time, equity[i], equity[i], equity[i], equity[i], 0.0))
        }
        chartCard.appendChild(areaChart(curveBars, 220, intraday = false))
        wrap.appendChild(chartCard)

        val tableCard = card()
        tableCard.appendChild(cardHead("Holding risk", "Weight, volatility and drawdown per security"))
        val table = dataTable(listOf("SYMBOL", "WEIGHT", "VOLATILITY", "MAX DD", "RETURN", "CORRELATION"), setOf(1, 2, 3, 4, 5))
        for (i in usable.indices) {
            val holding = usable[i].first
            val returns = returnsBySymbol[holding.symbol] ?: emptyList()
            val closes = closesBySymbol[holding.symbol] ?: emptyList()
            val seriesEquity = ArrayList<Double>()
            var value = 100000.0
            seriesEquity.add(value)
            for (r in returns) {
                value *= 1.0 + r
                seriesEquity.add(value)
            }
            val correlation = if (returns.isNotEmpty()) correlationOf(returns, portfolioReturns) else null
            table.row(listOf(
                el("div", "symbol-cell", holding.symbol),
                el("div", "mono", percent(weights[i] * 100.0)),
                el("div", "mono", percent(annualisedVolatility(returns))),
                el("div", "mono tone-down", percent(maxDrawdown(seriesEquity))),
                el("div", "mono tone-" + toneOf((seriesEquity.last() / 100000.0 - 1.0) * 100.0), percent((seriesEquity.last() / 100000.0 - 1.0) * 100.0)),
                el("div", "mono", if (correlation == null) "—" else fixed(correlation, 2))
            )) { openSymbol(holding.symbol) }
        }
        tableCard.appendChild(table.root.parentElement as HTMLElement)
        wrap.appendChild(tableCard)

        wrap.appendChild(noteLine(
            "Weights use the latest quoted price where available and your average cost otherwise, and are not rebalanced. Volatility uses 252 trading days; drawdown is measured on the weighted equity curve.",
            "neutral"
        ))
        body.appendChild(wrap)
    }
    return root
}

// ---------------------------------------------------------------- scenario simulator

fun scenarioSimulatorScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "STRESS TESTING",
        "Scenario simulator",
        "Apply market, sector and stock shocks to a position or to your whole portfolio and read the arithmetic outcome. This is not a forecast.",
        listOf(actionButton("All tools", "secondary", "layers") { go("tools") })
    ))
    root.appendChild(body)

    var mode = "stock"
    val symbol = field("NEPSE symbol", "search", currentSymbol.ifBlank { "NABIL" })
    val positionValue = numberField("", "100000")
    val marketShock = numberField("", "0")
    val sectorShock = numberField("", "0")
    val stockShock = numberField("", "-5")
    val modeHost = el("div", "segmented")
    val output = el("div", "scenario-output")

    val form = card("tool-card")
    form.appendChild(cardHead("Shock inputs", "Percentages: negative for a fall, positive for a rise"))
    form.appendChild(modeHost)
    form.appendChild(toolGrid(
        toolField("Security", symbol),
        toolField("Position value", positionValue),
        toolField("Market shock (%)", marketShock),
        toolField("Sector shock (%)", sectorShock),
        toolField("Stock-specific shock (%)", stockShock)
    ))
    form.appendChild(output)
    body.appendChild(form)

    var compute: () -> Unit = {}

    fun paintMode() {
        modeHost.innerHTML = ""
        modeHost.appendChild(chipButton("Single stock", mode == "stock") {
            mode = "stock"
            paintMode()
            compute()
        })
        modeHost.appendChild(chipButton("My portfolio", mode == "portfolio") {
            mode = "portfolio"
            paintMode()
            compute()
        })
    }

    compute = {
        output.innerHTML = ""
        val base = num(positionValue.value) ?: 100000.0
        val market = num(marketShock.value) ?: 0.0
        val sector = num(sectorShock.value) ?: 0.0
        val specific = num(stockShock.value) ?: 0.0
        val totalShock = market + sector + specific
        val projected = base * (1.0 + totalShock / 100.0)
        val delta = projected - base

        val grid = el("div", "stat-grid")
        grid.appendChild(statTile("Exposure", rupees(base), if (mode == "stock") "Single position" else "Portfolio"))
        grid.appendChild(statTile("Combined shock", percent(totalShock), "Market + sector + stock", toneClass(totalShock)))
        grid.appendChild(statTile("Projected value", rupees(projected), "After the shock"))
        grid.appendChild(statTile("Change", rupees(delta), percent(totalShock), toneClass(delta)))
        output.appendChild(grid)

        if (mode == "stock") {
            val quote = quoteFor(symbol.value.trim().uppercase())
            if (quote?.ltp != null && quote.ltp != 0.0) {
                val shockedPrice = quote.ltp * (1.0 + totalShock / 100.0)
                output.appendChild(noteLine(
                    "At the current quote of " + price(quote.ltp) + ", a " + percent(totalShock) +
                        " move implies approximately " + price(shockedPrice) + " per share.",
                    "neutral"
                ))
            } else {
                output.appendChild(noteLine("No live quote for this symbol yet; the exposure figures still apply to the value you entered.", "neutral"))
            }
        }

        val tableCard = card("tool-card")
        tableCard.appendChild(cardHead("Shock ladder", "The same exposure under a range of stock-specific shocks"))
        val table = dataTable(listOf("STOCK SHOCK", "COMBINED", "PROJECTED VALUE", "CHANGE"), setOf(0, 1, 2, 3))
        for (step in listOf(-20.0, -10.0, -5.0, 0.0, 5.0, 10.0, 20.0)) {
            val combined = market + sector + step
            val value = base * (1.0 + combined / 100.0)
            table.row(listOf(
                el("div", "mono", percent(step)),
                el("div", "mono tone-" + toneOf(combined), percent(combined)),
                el("div", "mono", rupees(value)),
                el("div", "mono tone-" + toneOf(value - base), rupees(value - base))
            ))
        }
        tableCard.appendChild(table.root.parentElement as HTMLElement)
        output.appendChild(tableCard)
        output.appendChild(noteLine(
            "Shocks are applied arithmetically and independently. Real markets correlate, gap and halt; use this to frame a range, not to predict a price.",
            "neutral"
        ))
    }

    for (input in listOf(positionValue, marketShock, sectorShock, stockShock)) {
        input.addEventListener("input", { compute() })
    }
    symbol.addEventListener("input", { compute() })
    paintMode()
    compute()
    return root
}

// ---------------------------------------------------------------- hypothetical positions

data class HypoPosition(val symbol: String, val quantity: Double, val entry: Double)

fun loadHypoPositions(): MutableList<HypoPosition> {
    val out = ArrayList<HypoPosition>()
    for (row in rowsOf(Store.getJson("hypo"))) {
        val symbol = text(pick(row, "symbol"))?.uppercase() ?: continue
        val quantity = num(pick(row, "quantity")) ?: continue
        val entry = num(pick(row, "entry")) ?: continue
        out.add(HypoPosition(symbol, quantity, entry))
    }
    return out
}

fun saveHypoPositions(positions: List<HypoPosition>) {
    val array = js("[]")
    for (position in positions) {
        val item = js("({})")
        item.symbol = position.symbol
        item.quantity = position.quantity
        item.entry = position.entry
        array.push(item)
    }
    Store.putJson("hypo", array)
}

fun hypotheticalScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "PAPER TRADING",
        "Hypothetical positions",
        "Model trades without committing money. Prices update from the live feed and positions are stored on this device only.",
        listOf(actionButton("All tools", "secondary", "layers") { go("tools") })
    ))
    root.appendChild(body)

    val positionsHost = el("div", "hypo-positions")
    var paint: () -> Unit = {}
    val form = card("tool-card")
    form.appendChild(cardHead("Add a hypothetical position", "Nothing is sent to a broker"))
    val symbolInput = field("NEPSE symbol", "search", "")
    val qtyInput = numberField("Quantity")
    val entryInput = numberField("Entry price")
    form.appendChild(toolGrid(
        toolField("Symbol", symbolInput),
        toolField("Quantity", qtyInput),
        toolField("Entry price", entryInput)
    ))
    form.appendChild(el("div", "toolbar").add(actionButton("Add position", "primary", "plus") {
        val symbol = symbolInput.value.trim().uppercase()
        val quantity = num(qtyInput.value)
        val entry = num(entryInput.value)
        if (symbol.isBlank() || quantity == null || quantity <= 0.0 || entry == null || entry <= 0.0) {
            toast("Enter a symbol, a quantity above zero and an entry price.", "error")
        } else {
            val positions = loadHypoPositions()
            positions.add(HypoPosition(symbol, quantity, entry))
            saveHypoPositions(positions)
            symbolInput.value = ""
            qtyInput.value = ""
            entryInput.value = ""
            toast(symbol + " added to hypothetical positions.", "success")
            paint()
        }
    }))
    body.appendChild(form)
    body.appendChild(positionsHost)

    paint = paintNow@{
        positionsHost.innerHTML = ""
        val positions = loadHypoPositions()
        if (positions.isEmpty()) {
            positionsHost.appendChild(emptyState(
                "No hypothetical positions",
                "Add a symbol above to model a trade and track its live profit and loss."
            ))
            return@paintNow
        }
        var invested = 0.0
        var current = 0.0
        val table = dataTable(listOf("SYMBOL", "QTY", "ENTRY", "LTP", "VALUE", "P/L", "P/L %", ""), setOf(1, 2, 3, 4, 5, 6))
        for ((index, position) in positions.withIndex()) {
            val quote = quoteFor(position.symbol)
            val ltp = quote?.ltp
            val value = ltp?.let { it * position.quantity }
            val cost = position.entry * position.quantity
            invested += cost
            current += value ?: cost
            val profit = value?.let { it - cost }
            val profitPct = if (cost != 0.0 && profit != null) profit / cost * 100.0 else null
            val remove = el("button", "icon-btn")
            remove.appendChild(icon("trash", 14))
            remove.setAttribute("aria-label", "Remove position")
            remove.onClick {
                val next = loadHypoPositions()
                if (index in next.indices) {
                    next.removeAt(index)
                    saveHypoPositions(next)
                    paint()
                }
            }
            table.row(listOf(
                el("div", "symbol-cell", position.symbol),
                el("div", "mono", integer(position.quantity)),
                el("div", "mono", price(position.entry)),
                el("div", "mono", price(ltp)),
                el("div", "mono", if (value == null) "—" else rupees(value)),
                el("div", "mono tone-" + toneOf(profit), if (profit == null) "—" else rupees(profit)),
                el("div", "mono tone-" + toneOf(profitPct), if (profitPct == null) "—" else percent(profitPct)),
                remove
            )) { openSymbol(position.symbol) }
        }
        val totalProfit = current - invested
        val grid = el("div", "stat-grid")
        grid.appendChild(statTile("Invested", rupees(invested), integer(positions.size.toDouble()) + " positions"))
        grid.appendChild(statTile("Market value", rupees(current), "At the latest quotes"))
        grid.appendChild(statTile("Open P/L", rupees(totalProfit), if (invested > 0) percent(totalProfit / invested * 100.0) else "—", toneClass(totalProfit)))
        positionsHost.appendChild(grid)
        positionsHost.appendChild(el("div", "table-card").add(table.root.parentElement as HTMLElement))
        positionsHost.appendChild(el("div", "toolbar").add(
            actionButton("Clear all", "danger", "trash") {
                confirmModal("Clear hypothetical positions", "This removes every hypothetical position stored on this device.", "Clear all") {
                    saveHypoPositions(emptyList())
                    paint()
                }
            },
            actionButton("Refresh prices", "ghost", "refresh") { paint() }
        ))
    }

    paint()
    registerRefresh { paint() }
    return root
}

// ---------------------------------------------------------------- stock comparison

fun comparisonScreen(): HTMLElement {
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "RELATIVE ANALYSIS",
        "Stock comparison",
        "Compare up to four securities on normalised performance, trailing returns and risk from daily closes.",
        listOf(actionButton("All tools", "secondary", "layers") { go("tools") })
    ))
    root.appendChild(body)

    val symbolsInput = field("NABIL, NTB, GBIME", "search", "NABIL, NTB, GBIME")
    val resultHost = el("div", "comparison")

    fun run() {
        val symbols = symbolsInput.value.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().take(4)
        if (symbols.size < 2) {
            resultHost.innerHTML = ""
            resultHost.appendChild(emptyState("Two symbols needed", "Enter at least two symbols separated by commas to compare them."))
            return
        }
        resultHost.innerHTML = ""
        resultHost.appendChild(loadingState("Loading daily history for " + symbols.joinToString(", ") + "…"))
        loadSeriesBatch(symbols) { series ->
            resultHost.innerHTML = ""
            val usable = symbols.mapNotNull { symbol ->
                val bars = series[symbol] ?: return@mapNotNull null
                if (bars.size < 20) null else symbol to bars
            }
            if (usable.size < 2) {
                resultHost.appendChild(emptyState("History unavailable", "At least two symbols need history to compare."))
                return@loadSeriesBatch
            }
            val length = usable.minOf { it.second.size }.coerceAtMost(260)
            val closes = usable.map { it.first to it.second.takeLast(length).map { bar -> bar.close } }
            val normalized = closes.map { it.first to normalizedSeries(it.second) }
            val missing = symbols.filter { symbol -> usable.none { it.first == symbol } }
            if (missing.isNotEmpty()) {
                resultHost.appendChild(noteLine(
                    "No usable daily history was returned for " + missing.joinToString(", ") + ", so it is not charted.",
                    "neutral"
                ))
            }
            val chartCard = card("chart-card")
            chartCard.appendChild(cardHead("Normalised performance", "Both series rebased to 100 at the start of the window"))
            chartCard.appendChild(multiLineChart(normalized, 300))
            resultHost.appendChild(chartCard)

            val tableCard = card()
            tableCard.appendChild(cardHead("Returns and risk", integer(length.toDouble()) + " sessions of daily closes"))
            val table = dataTable(listOf("SYMBOL", "LAST", "1W", "1M", "3M", "6M", "1Y", "VOLATILITY", "MAX DD"), setOf(1, 2, 3, 4, 5, 6, 7, 8))
            for ((symbol, values) in closes) {
                val returns = returnsOf(values)
                val equity = ArrayList<Double>()
                var running = 100000.0
                equity.add(running)
                for (r in returns) {
                    running *= 1.0 + r
                    equity.add(running)
                }
                table.row(listOf(
                    el("div", "symbol-cell", symbol),
                    el("div", "mono", price(values.last())),
                    el("div", "mono tone-" + toneOf(periodReturn(values, 5)), percent(periodReturn(values, 5))),
                    el("div", "mono tone-" + toneOf(periodReturn(values, 21)), percent(periodReturn(values, 21))),
                    el("div", "mono tone-" + toneOf(periodReturn(values, 63)), percent(periodReturn(values, 63))),
                    el("div", "mono tone-" + toneOf(periodReturn(values, 126)), percent(periodReturn(values, 126))),
                    el("div", "mono tone-" + toneOf(periodReturn(values, 252)), percent(periodReturn(values, 252))),
                    el("div", "mono", percent(annualisedVolatility(returns))),
                    el("div", "mono tone-down", percent(maxDrawdown(equity)))
                )) { openSymbol(symbol) }
            }
            tableCard.appendChild(table.root.parentElement as HTMLElement)
            resultHost.appendChild(tableCard)

            if (usable.size >= 2) {
                val correlation = correlationOf(returnsOf(closes[0].second), returnsOf(closes[1].second))
                resultHost.appendChild(noteLine(
                    "Correlation between " + closes[0].first + " and " + closes[1].first + ": " +
                        (if (correlation == null) "not enough overlapping history" else fixed(correlation, 2)) +
                        ". Returns ignore dividends, bonuses and splits unless the history service reports adjusted closes.",
                    "neutral"
                ))
            }
        }
    }

    val form = card("tool-card")
    form.appendChild(cardHead("Securities", "Comma separated, up to four symbols"))
    val compareButton = actionButton("Compare", "primary", "chart") { run() }
    form.appendChild(el("div", "toolbar").add(symbolsInput, compareButton))
    body.appendChild(form)
    body.appendChild(resultHost)

    symbolsInput.addEventListener("keydown", { event ->
        if (eventKey(event) == "Enter") run()
    })
    run()
    return root
}
