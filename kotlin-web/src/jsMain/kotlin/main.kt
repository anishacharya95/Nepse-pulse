import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement

data class Route(val id: String, val label: String, val icon: String, val group: String, val nav: Boolean = true)

val ROUTES = listOf(
    Route("dashboard", "Dashboard", "dashboard", "Markets"),
    Route("market", "Market overview", "market", "Markets"),
    Route("sectors", "Sector heatmap", "sectors", "Markets"),
    Route("structure", "Market structure", "structure", "Markets"),
    Route("xray", "Stock X-Ray", "xray", "Research"),
    Route("screener", "Stock screener", "screener", "Research"),
    Route("quant", "Screener & Quant", "quant", "Research"),
    Route("ai", "AI tools", "ai", "Research"),
    Route("traders", "Traders zone", "traders", "Research"),
    Route("floorsheet", "Floorsheet & brokers", "floorsheet", "Flow"),
    Route("brokers", "Broker analytics", "brokers", "Flow"),
    Route("tools", "Tools hub", "layers", "Tools"),
    Route("charts", "Advanced charts", "chart", "Tools"),
    Route("replay", "Chart replay", "clock", "Tools"),
    Route("risk", "Risk calculator", "shield", "Tools", false),
    Route("portfolio-risk", "Portfolio risk", "portfolio", "Tools", false),
    Route("scenario", "Scenario simulator", "spark", "Tools", false),
    Route("hypo", "Hypothetical positions", "layers", "Tools", false),
    Route("compare", "Stock comparison", "market", "Tools", false),
    Route("portfolio", "Portfolio", "portfolio", "Personal"),
    Route("watchlist", "Watchlist", "watchlist", "Personal"),
    Route("alerts", "Price alerts", "alerts", "Personal"),
    Route("corporate", "Corporate actions", "corporate", "Primary market"),
    Route("ipo", "IPO center", "ipo", "Primary market"),
    Route("profile", "Profile & settings", "profile", "Account")
)

private val groups = listOf("Markets", "Research", "Flow", "Tools", "Personal", "Primary market", "Account")

var currentRoute: String = "dashboard"
private var authGateActive: Boolean = true
var currentSymbol: String = ""
private var refreshHook: (() -> Unit)? = null
private var shellView: HTMLElement? = null

fun registerRefresh(hook: () -> Unit) {
    refreshHook = hook
}

fun applyThemeToBody() {
    Theme.apply()
    val button = document.getElementById("theme-toggle") as? HTMLElement
    if (button != null) {
        button.innerHTML = ""
        button.appendChild(icon(if (Theme.current == "dark") "sun" else "moon", 17))
        button.setAttribute("title", if (Theme.current == "dark") "Switch to light theme" else "Switch to dark theme")
    }
}

private fun brandMark(): HTMLElement {
    val mark = el("div", "brand-mark")
    mark.innerHTML = "<span>N</span>"
    return mark
}

private fun navLink(route: Route): HTMLElement {
    val link = el("button", "nav-link")
    link.setAttribute("data-route", route.id)
    link.appendChild(icon(route.icon, 18))
    link.appendChild(el("span", "nav-label", route.label))
    link.onClick { go(route.id) }
    return link
}

private fun buildSidebar(): HTMLElement {
    val aside = el("aside", "sidebar")
    val brand = el("div", "brand")
    brand.appendChild(brandMark())
    val brandCopy = el("div")
    brandCopy.appendChild(el("div", "brand-name", "NEPSE PULSE"))
    brandCopy.appendChild(el("div", "brand-tag", "MARKET INTELLIGENCE"))
    brand.appendChild(brandCopy)
    aside.appendChild(brand)

    val nav = el("nav", "nav")
    for (group in groups) {
        val routes = ROUTES.filter { it.group == group && it.nav }
        if (routes.isEmpty()) continue
        nav.appendChild(el("div", "nav-group-label", group.uppercase()))
        for (route in routes) nav.appendChild(navLink(route))
    }
    aside.appendChild(nav)

    val footer = el("div", "sidebar-foot")
    footer.appendChild(el("span", "brand-tag", "v" + APP_VERSION + " · KOTLIN/JS"))
    val themeButton = iconButton("sun", "Switch theme") {
        Theme.toggle()
        applyThemeToBody()
        render()
    }
    themeButton.id = "theme-toggle"
    footer.appendChild(themeButton)
    aside.appendChild(footer)
    return aside
}

private fun buildTopbar(): HTMLElement {
    val bar = el("header", "topbar")
    val mobileBrand = el("div", "topbar-brand")
    mobileBrand.appendChild(brandMark())
    mobileBrand.appendChild(el("span", "brand-name", "NEPSE PULSE"))
    bar.appendChild(mobileBrand)

    val searchWrap = el("div", "global-search")
    val searchIcon = icon("search", 16)
    searchWrap.appendChild(searchIcon)
    val input = document.createElement("input") as HTMLInputElement
    input.type = "search"
    input.placeholder = "Search any NEPSE symbol…"
    input.className = "search-input"
    searchWrap.appendChild(input)
    val results = el("div", "search-results")
    results.style.display = "none"
    searchWrap.appendChild(results)
    bar.appendChild(searchWrap)

    fun closeResults() {
        results.style.display = "none"
        results.innerHTML = ""
    }

    fun choose(symbol: String) {
        closeResults()
        input.value = ""
        openSymbol(symbol)
    }

    fun paintResults(items: List<Quote>) {
        results.innerHTML = ""
        if (items.isEmpty()) {
            closeResults()
            return
        }
        results.style.display = "block"
        for (quote in items) {
            val row = el("button", "search-result")
            row.appendChild(el("span", "search-symbol", quote.symbol))
            row.appendChild(el("span", "search-name", quote.name))
            row.appendChild(el("span", "search-price", price(quote.ltp)))
            row.onClick { choose(quote.symbol) }
            results.appendChild(row)
        }
    }

    fun remoteSearch(query: String) {
        Http.get("/api/tv/search?query=" + encode(query), ttlMs = 300_000.0, timeoutMs = 20_000, onData = { payload ->
            val rows = rowsOf(payload)
            if (rows.isEmpty()) return@get
            results.innerHTML = ""
            results.style.display = "block"
            for (row in rows.take(7)) {
                val symbol = text(pick(row, "symbol", "ticker")) ?: continue
                val description = text(pick(row, "description", "full_name", "name")) ?: "NEPSE listed security"
                val button = el("button", "search-result")
                button.appendChild(el("span", "search-symbol", symbol))
                button.appendChild(el("span", "search-name", description))
                button.onClick { choose(symbol) }
                results.appendChild(button)
            }
        })
    }

    input.addEventListener("input", {
        val query = input.value.trim()
        if (query.isEmpty()) {
            closeResults()
        } else {
            val local = searchQuotes(query, 7)
            if (local.isNotEmpty()) {
                paintResults(local)
            } else {
                results.innerHTML = ""
                results.style.display = "none"
                remoteSearch(query)
            }
        }
    })
    input.addEventListener("keydown", { event ->
        if (eventKey(event) == "Enter") {
            val query = input.value.trim().uppercase()
            if (query.isNotEmpty()) choose(query)
        }
        if (eventKey(event) == "Escape") closeResults()
    })
    input.addEventListener("blur", {
        window.setTimeout({ closeResults() }, 180)
    })

    val actions = el("div", "topbar-actions")
    val status = el("div", "market-pill")
    status.appendChild(el("span", "pulse-dot"))
    status.appendChild(el("span", "market-pill-text", "CONNECTING"))
    status.id = "market-pill"
    actions.appendChild(status)

    val refreshButton = iconButton("refresh", "Refresh market data") {
        Http.clear()
        toast("Refreshing market data…", "info")
        render()
    }
    actions.appendChild(refreshButton)

    val themeButton = iconButton(if (Theme.current == "dark") "sun" else "moon", "Switch theme") {
        Theme.toggle()
        applyThemeToBody()
        render()
    }
    themeButton.id = "theme-toggle"
    actions.appendChild(themeButton)

    val account = el("button", "account-chip")
    account.id = "account-chip"
    account.onClick { go("profile") }
    actions.appendChild(account)
    updateAccountChip()
    bar.appendChild(actions)
    return bar
}

fun updateAccountChip() {
    val chip = document.getElementById("account-chip") as? HTMLElement ?: return
    chip.innerHTML = ""
    if (Auth.signedIn) {
        chip.appendChild(el("span", "avatar small", Auth.initials()))
        chip.appendChild(el("span", "account-label", Auth.displayName()))
    } else {
        chip.appendChild(icon("profile", 16))
        chip.appendChild(el("span", "account-label", "Sign in"))
    }
}

fun updateMarketPill() {
    val pill = document.getElementById("market-pill") as? HTMLElement ?: return
    pill.innerHTML = ""
    val dot = el("span", "pulse-dot")
    if (Ctx.market == null || !Ctx.online) dot.classList.add("pulse-idle")
    pill.appendChild(dot)
    pill.appendChild(el("span", "market-pill-text", if (Ctx.market == null) "CONNECTING" else marketStatusLabel().uppercase()))
    val pct = indexValues()["pct"]
    if (pct != null) {
        pill.appendChild(el("span", "market-pill-change tone-" + toneOf(pct), percent(pct)))
    }
}

private fun buildBottomNav(): HTMLElement {
    val nav = el("nav", "bottom-nav")
    val items = listOf(
        "dashboard" to ("Home" to "dashboard"),
        "market" to ("Market" to "market"),
        "xray" to ("X-Ray" to "xray"),
        "watchlist" to ("Watchlist" to "watchlist"),
        "more" to ("More" to "menu")
    )
    for ((routeId, info) in items) {
        val button = el("button", "bottom-link")
        if (routeId == currentRoute) button.classList.add("active")
        button.appendChild(icon(info.second, 19))
        button.appendChild(el("span", "", info.first))
        button.onClick {
            if (routeId == "more") openMoreSheet() else go(routeId)
        }
        nav.appendChild(button)
    }
    return nav
}

private fun openMoreSheet() {
    modal("All sections") { body ->
        for (group in groups) {
            val routes = ROUTES.filter { it.group == group && it.nav }
            if (routes.isEmpty()) continue
            body.appendChild(el("div", "nav-group-label", group.uppercase()))
            val grid = el("div", "more-grid")
            for (route in routes) {
                val tile = el("button", "more-tile")
                tile.appendChild(icon(route.icon, 18))
                tile.appendChild(el("span", "", route.label))
                tile.onClick {
                    (body.parentElement?.parentElement as? HTMLElement)?.remove()
                    go(route.id)
                }
                grid.appendChild(tile)
            }
            body.appendChild(grid)
        }
    }
}

fun openSymbol(symbol: String) {
    currentSymbol = symbol.trim().uppercase()
    go("xray")
}

fun go(route: String) {
    if (!Auth.ready || !Auth.signedIn) {
        currentRoute = "profile"
        currentSymbol = ""
        render()
        return
    }
    currentRoute = if (ROUTES.any { it.id == route }) route else "dashboard"
    if (currentRoute != "xray") currentSymbol = ""
    refreshHook = null
    try {
        window.location.hash = if (currentRoute == "xray" && currentSymbol.isNotBlank()) "#xray/$currentSymbol" else "#$currentRoute"
    } catch (_: Throwable) {
    }
    render()
}

fun render() {
    stopReplayPlayback()
    NpChartRegistry.reset()
    val appRoot = document.getElementById("app") as? HTMLElement
    if (!Auth.ready || !Auth.signedIn) {
        authGateActive = true
        currentRoute = "profile"
        currentSymbol = ""
        appRoot?.classList?.add("auth-required")
    } else {
        appRoot?.classList?.remove("auth-required")
        if (authGateActive) {
            currentRoute = "dashboard"
            currentSymbol = ""
            authGateActive = false
        }
    }
    val view = shellView ?: return
    view.innerHTML = ""
    val screen = when (currentRoute) {
        "market" -> marketOverviewScreen()
        "xray" -> stockXrayScreen(currentSymbol)
        "screener" -> screenerScreen()
        "quant" -> quantScreen()
        "floorsheet" -> floorsheetV10Screen()
        "brokers" -> brokerAnalyticsScreen()
        "tools" -> toolsHubScreen()
        "charts" -> advancedChartScreen()
        "replay" -> replayScreen()
        "risk" -> riskCalculatorScreen()
        "portfolio-risk" -> portfolioRiskScreen()
        "scenario" -> scenarioSimulatorScreen()
        "hypo" -> hypotheticalScreen()
        "compare" -> comparisonScreen()
        "sectors" -> sectorsScreen()
        "structure" -> marketStructureScreen()
        "traders" -> tradersScreen()
        "portfolio" -> portfolioScreen()
        "watchlist" -> watchlistScreen()
        "alerts" -> alertsScreen()
        "corporate" -> eventsScreen("corporate")
        "ipo" -> eventsScreen("ipo")
        "ai" -> aiToolsScreen(currentSymbol)
        "profile" -> profileScreen()
        else -> dashboardScreen()
    }
    view.appendChild(screen)
    val activeLabel = ROUTES.firstOrNull { it.id == currentRoute }?.label ?: "Dashboard"
    document.title = activeLabel + " · NEPSE Pulse"
    view.scrollTop = 0.0
    window.asDynamic().scrollTo(0, 0)
    val navLinks = document.querySelectorAll(".nav-link")
    for (index in 0 until navLinks.length) {
        val element = navLinks.item(index) as? HTMLElement ?: continue
        if (element.getAttribute("data-route") == currentRoute) {
            element.classList.add("active")
        } else {
            element.classList.remove("active")
        }
    }
    val bottomLinks = document.querySelectorAll(".bottom-link")
    for (index in 0 until bottomLinks.length) {
        val element = bottomLinks.item(index) as? HTMLElement ?: continue
        element.classList.remove("active")
    }
    updateMarketPill()
    updateAccountChip()
}

private fun buildShell() {
    val root = document.getElementById("app") as? HTMLElement ?: return
    root.innerHTML = ""
    root.className = "app-shell"
    val sidebar = buildSidebar()
    root.appendChild(sidebar)
    val main = el("div", "main")
    main.appendChild(buildTopbar())
    val view = el("main", "view")
    view.id = "view"
    shellView = view
    main.appendChild(view)
    main.appendChild(buildBottomNav())
    root.appendChild(main)
}

private fun parseHash() {
    val hash = window.location.hash.removePrefix("#")
    if (hash.isBlank()) return
    val parts = hash.split("/")
    val route = parts[0]
    if (ROUTES.any { it.id == route }) {
        currentRoute = route
        currentSymbol = if (route == "xray" && parts.size > 1) parts[1].uppercase() else ""
    }
}

fun main() {
    Theme.init()
    installBridge()
    val capacitor = window.asDynamic().Capacitor
    val native = capacitor != null && capacitor != undefined &&
        capacitor.isNativePlatform != null && capacitor.isNativePlatform() == true
    if (native) document.body?.classList?.add("native-app")
    Auth.init()
    Auth.onChange {
        updateAccountChip()
        // Re-apply the auth gate immediately if a session expires or the user signs out elsewhere.
        if (!Auth.signedIn || currentRoute == "profile") render()
    }
    parseHash()
    buildShell()
    applyThemeToBody()
    render()

    loadMarket { _, _ ->
        updateMarketPill()
        if (currentRoute == "dashboard" || currentRoute == "structure") render()
    }
    loadSectors {
        if (currentRoute == "structure" || currentRoute == "sectors") render()
    }
    // Warm the technical history cache once so screening screens open with data.
    loadTechnical { _, _ -> }

    window.setInterval({
        val hidden = document.asDynamic().hidden == true
        if (!hidden) {
            refreshHook?.invoke()
        }
        null
    }, 30_000)

    window.setInterval({
        updateMarketPill()
        null
    }, 15_000)

    // Keep every mounted chart in step with layout changes.
    window.setInterval({
        NpChartRegistry.tick()
        null
    }, 900)

    window.addEventListener("hashchange", {
        val previous = currentRoute
        val previousSymbol = currentSymbol
        parseHash()
        if (currentRoute != previous || currentSymbol != previousSymbol) render()
    })
}
