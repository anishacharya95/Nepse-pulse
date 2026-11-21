import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise

private const val API_BASE = "https://nepse-pulse-1.onrender.com"
private var supabaseClient: dynamic = null

private fun initializeSupabase() {
    val host = window.asDynamic()
    val config = host.NEPSE_PULSE_AUTH
    val library = host.supabase
    if (config != null && config != undefined && library != null && library != undefined) {
        supabaseClient = library.createClient(config.supabaseUrl, config.supabaseAnonKey)
    }
}

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

private fun render(root: HTMLElement, payload: dynamic, search: String = "", selectedSymbol: String = "", filterMode: String = "all") {
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
    val headerActions = el("div", "header-actions")
    val accountButton = el("button", "account-toggle", "Account")
    headerActions.appendChild(accountButton)
    val state = el("div", "live-state")
    state.appendChild(el("span", "pulse-dot"))
    state.appendChild(el("span", "live-label", if (live.isNotEmpty()) "LIVE DATA" else "CONNECTING"))
    header.appendChild(state)
    header.appendChild(headerActions)
    shell.appendChild(header)

    val accountPanel = el("section", "account-panel")
    accountPanel.style.display = "none"
    accountPanel.appendChild(el("div", "eyebrow", "SUPABASE ACCOUNT"))
    accountPanel.appendChild(el("h2", "", "Sign in to NEPSE Pulse"))
    accountPanel.appendChild(el("p", "account-copy", "Use your existing NEPSE Pulse account to access cloud features."))
    val emailInput = document.createElement("input") as HTMLInputElement
    emailInput.type = "email"
    emailInput.placeholder = "Email address"
    emailInput.autocomplete = "email"
    val passwordInput = document.createElement("input") as HTMLInputElement
    passwordInput.type = "password"
    passwordInput.placeholder = "Password"
    passwordInput.autocomplete = "current-password"
    val accountFields = el("div", "account-fields")
    accountFields.appendChild(emailInput)
    accountFields.appendChild(passwordInput)
    accountPanel.appendChild(accountFields)
    val accountStatus = el("div", "account-status", if (supabaseClient == null) "Supabase client is unavailable. Check your connection." else "Checking your session…")
    accountPanel.appendChild(accountStatus)
    fun accountAction(label: String, style: String, action: () -> Unit) {
        val button = el("button", style, label)
        button.addEventListener("click", { _ -> action() })
        accountPanel.appendChild(button)
    }
    accountAction("Sign in", "account-action primary") {
        if (supabaseClient == null) accountStatus.textContent = "Supabase is not configured."
        else {
            val credentials = js("({})")
            credentials.email = emailInput.value.trim()
            credentials.password = passwordInput.value
            (supabaseClient.auth.signInWithPassword(credentials) as Promise<dynamic>).then { result: dynamic ->
                accountStatus.textContent = if (result.error != null) result.error.message.toString() else "Signed in successfully."
                null
            }.catch { error: Throwable ->
                accountStatus.textContent = error.message ?: "Sign-in failed."
                null
            }
        }
    }
    accountAction("Create account", "account-action") {
        if (supabaseClient == null) accountStatus.textContent = "Supabase is not configured."
        else {
            val credentials = js("({})")
            credentials.email = emailInput.value.trim()
            credentials.password = passwordInput.value
            (supabaseClient.auth.signUp(credentials) as Promise<dynamic>).then { result: dynamic ->
                accountStatus.textContent = if (result.error != null) result.error.message.toString() else "Account request sent. Check your email if confirmation is enabled."
                null
            }.catch { error: Throwable ->
                accountStatus.textContent = error.message ?: "Sign-up failed."
                null
            }
        }
    }
    accountAction("Continue with Google", "account-action oauth") {
        if (supabaseClient == null) accountStatus.textContent = "Supabase is not configured."
        else {
            val options = js("({})")
            options.provider = "google"
            val oauthOptions = js("({})")
            oauthOptions.redirectTo = window.location.href
            options.options = oauthOptions
            (supabaseClient.auth.signInWithOAuth(options) as Promise<dynamic>).then { result: dynamic ->
                if (result.error != null) accountStatus.textContent = result.error.message.toString()
                null
            }.catch { error: Throwable ->
                accountStatus.textContent = error.message ?: "Google sign-in failed."
                null
            }
        }
    }
    accountAction("Reset password", "account-action") {
        if (supabaseClient == null) accountStatus.textContent = "Supabase is not configured."
        else {
            val options = js("({})")
            options.redirectTo = window.location.href
            (supabaseClient.auth.resetPasswordForEmail(emailInput.value.trim(), options) as Promise<dynamic>).then { result: dynamic ->
                accountStatus.textContent = if (result.error != null) result.error.message.toString() else "Password reset email requested."
                null
            }.catch { error: Throwable ->
                accountStatus.textContent = error.message ?: "Password reset failed."
                null
            }
        }
    }
    accountAction("Sign out", "account-action") {
        if (supabaseClient == null) accountStatus.textContent = "Supabase is not configured."
        else (supabaseClient.auth.signOut() as Promise<dynamic>).then {
            accountStatus.textContent = "Signed out."
            null
        }.catch { error: Throwable ->
            accountStatus.textContent = error.message ?: "Sign-out failed."
            null
        }
    }
    accountButton.addEventListener("click", { _ ->
        accountPanel.style.display = if (accountPanel.style.display == "none") "block" else "none"
    })
    if (supabaseClient != null) {
        (supabaseClient.auth.getSession() as Promise<dynamic>).then { result: dynamic ->
            val user = result.data?.session?.user
            accountStatus.textContent = if (user != null) "Signed in as " + user.email.toString() else "Not signed in."
            null
        }.catch { _ -> null }
    }
    shell.appendChild(accountPanel)

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

    val movers = el("section", "movers-grid")
    fun moverPanel(title: String, items: List<dynamic>, positive: Boolean) {
        val panel = el("article", "mover-panel")
        panel.appendChild(el("div", "eyebrow", if (positive) "TOP GAINERS" else "TOP LOSERS"))
        panel.appendChild(el("h3", "", title))
        if (items.isEmpty()) {
            panel.appendChild(el("div", "mover-empty", "No verified mover list in this response"))
        } else {
            items.take(5).forEach { item ->
                val symbol = pick(item, "symbol", "stockSymbol", "securitySymbol", "ticker", "code")?.toString() ?: "—"
                val company = pick(item, "company", "companyName", "name", "securityName")?.toString() ?: ""
                val last = number(pick(item, "ltp", "lastTradedPrice", "close", "price", "currentPrice"))
                val changePercent = number(pick(item, "percentChange", "percentageChange", "changePercent", "perChange", "pChange"))
                val line = el("div", "mover-line")
                val left = el("div", "mover-name")
                left.appendChild(el("strong", "", symbol))
                left.appendChild(el("small", "", company))
                line.appendChild(left)
                val right = el("div", "mover-quote")
                right.appendChild(el("strong", "", last?.let { fmt(it) } ?: "—"))
                right.appendChild(el("small", if (positive) "positive" else "negative", pct(changePercent)))
                line.appendChild(right)
                panel.appendChild(line)
            }
        }
        movers.appendChild(panel)
    }
    moverPanel("Stocks gaining today", rowsOf(data.gainers), true)
    moverPanel("Stocks declining today", rowsOf(data.losers), false)
    shell.appendChild(movers)

    val sectionHead = el("div", "section-head")
    val sectionTitle = el("div")
    sectionTitle.appendChild(el("div", "eyebrow", "MARKET WATCH"))
    sectionTitle.appendChild(el("h2", "", "Live market"))
    sectionHead.appendChild(sectionTitle)
    val filters = el("div", "market-filters")
    listOf("all" to "All", "gainers" to "Gainers", "losers" to "Losers").forEach { (mode, label) ->
        val chip = el("button", if (filterMode == mode) "filter-chip active" else "filter-chip", label)
        chip.addEventListener("click", { _ -> render(root, payload, search, selectedSymbol, mode) })
        filters.appendChild(chip)
    }
    sectionHead.appendChild(filters)
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
    val moverSymbols = when (filterMode) {
        "gainers" -> rowsOf(data.gainers).mapNotNull { pick(it, "symbol", "stockSymbol", "ticker", "code")?.toString()?.uppercase() }.toSet()
        "losers" -> rowsOf(data.losers).mapNotNull { pick(it, "symbol", "stockSymbol", "ticker", "code")?.toString()?.uppercase() }.toSet()
        else -> emptySet()
    }
    val filtered = live.filter { row ->
        val symbol = pick(row, "symbol", "stockSymbol", "ticker", "code")?.toString() ?: ""
        val company = pick(row, "company", "companyName", "name", "securityName")?.toString() ?: ""
        val matchesSearch = (symbol + " " + company).contains(search, ignoreCase = true)
        val matchesFilter = filterMode == "all" || symbol.uppercase() in moverSymbols
        matchesSearch && matchesFilter
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
        rowNode.addEventListener("click", { _ -> render(root, payload, search, symbol, filterMode) })
        rowNode.setAttribute("role", "button")
        rowNode.setAttribute("tabindex", "0")
        table.appendChild(rowNode)
    }
    if (filtered.isEmpty()) table.appendChild(el("div", "empty-state", if (live.isEmpty()) "Market data is not available yet. Retrying automatically…" else "No symbols match your search."))
    tableCard.appendChild(table)
    shell.appendChild(tableCard)

    if (selectedSymbol.isNotBlank()) {
        val selected = live.firstOrNull { row ->
            pick(row, "symbol", "stockSymbol", "ticker", "code")?.toString()?.equals(selectedSymbol, true) == true
        }
        if (selected != null) {
            val detail = el("section", "stock-detail")
            val detailHead = el("div", "stock-detail-head")
            val title = el("div")
            title.appendChild(el("div", "eyebrow", "STOCK X-RAY"))
            title.appendChild(el("h2", "", pick(selected, "symbol", "stockSymbol", "ticker", "code")?.toString() ?: selectedSymbol))
            title.appendChild(el("p", "stock-company", pick(selected, "company", "companyName", "name", "securityName")?.toString() ?: "Company details from live feed"))
            detailHead.appendChild(title)
            val closeButton = el("button", "detail-close", "Close")
            closeButton.addEventListener("click", { _ -> render(root, payload, search, "", filterMode) })
            detailHead.appendChild(closeButton)
            detail.appendChild(detailHead)

            val fields = listOf(
                "LAST TRADED PRICE" to pick(selected, "ltp", "lastTradedPrice", "close", "price"),
                "POINT CHANGE" to pick(selected, "change", "pointChange", "changeValue"),
                "PERCENT CHANGE" to pick(selected, "percentChange", "percentageChange", "changePercent", "perChange"),
                "OPEN" to pick(selected, "open", "openPrice"),
                "HIGH" to pick(selected, "high", "highPrice"),
                "LOW" to pick(selected, "low", "lowPrice"),
                "PREVIOUS CLOSE" to pick(selected, "previousClose", "previousDayClosePrice", "prevClose"),
                "VOLUME" to pick(selected, "volume", "tradedShares", "quantity"),
                "TURNOVER" to pick(selected, "turnover", "totalTradedAmount", "amount"),
                "TRADES" to pick(selected, "trades", "totalTrades", "numberOfTrades")
            )
            val fieldGrid = el("div", "stock-detail-grid")
            fields.forEach { (label, value) ->
                val cell = el("div", "detail-field")
                cell.appendChild(el("div", "metric-label", label))
                val numeric = number(value)
                val display = if (numeric != null) fmt(numeric) else value?.toString() ?: "—"
                cell.appendChild(el("div", "detail-value", display))
                fieldGrid.appendChild(cell)
            }
            detail.appendChild(fieldGrid)

            val chartTitle = el("div", "chart-heading")
            chartTitle.appendChild(el("div", "eyebrow", "TRADINGVIEW CHART"))
            chartTitle.appendChild(el("h3", "", "Price history · Daily"))
            detail.appendChild(chartTitle)
            val chartFrame = document.createElement("iframe")
            chartFrame.setAttribute("title", "TradingView chart for $selectedSymbol")
            chartFrame.setAttribute("loading", "lazy")
            chartFrame.setAttribute("allowtransparency", "true")
            chartFrame.setAttribute("frameborder", "0")
            chartFrame.setAttribute("src", "https://www.tradingview.com/widgetembed/?symbol=NEPSE%3A$selectedSymbol&interval=D&theme=dark&style=1&locale=en&hide_top_toolbar=false&hide_side_toolbar=true&allow_symbol_change=true&save_image=false&calendar=false&support_host=https%3A%2F%2Fwww.tradingview.com")
            chartFrame.className = "tradingview-frame"
            detail.appendChild(chartFrame)
            shell.appendChild(detail)
        }
    }

    val footer = el("footer", "footer")
    footer.appendChild(el("span", "", "NEPSE PULSE  ·  MARKET DATA PROVIDED BY CONNECTED FEEDS"))
    footer.appendChild(el("span", "", "Auto refresh: 30 seconds"))
    shell.appendChild(footer)
    root.appendChild(shell)

    searchInput.addEventListener("input", { _ -> render(root, payload, searchInput.value, selectedSymbol, filterMode) })
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
    initializeSupabase()
    val root = document.getElementById("app") as? HTMLElement ?: return
    refresh(root)
    window.setInterval({ refresh(root) }, 30000)
}
