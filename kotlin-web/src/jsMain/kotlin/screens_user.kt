import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- watchlist actions

fun addSymbolEverywhere(symbol: String, done: ((String) -> Unit)? = null) {
    val clean = symbol.trim().uppercase()
    if (clean.isBlank()) return
    val local = localWatchlist()
    if (!local.contains(clean)) {
        local.add(clean)
        saveLocalWatchlist(local)
    }
    if (Auth.signedIn) {
        Auth.addToWatchlist(clean) { error, message ->
            if (error != null) {
                toast("Saved on this device. Cloud sync failed: $error", "error")
            } else {
                toast(message ?: "Added $clean to your watchlist.", "success")
            }
            done?.invoke(clean)
        }
    } else {
        toast("Added $clean to your device watchlist. Sign in to sync it.", "success")
        done?.invoke(clean)
    }
}

fun promptWatchlist(symbol: String) {
    val clean = symbol.trim().uppercase()
    if (clean.isBlank()) return
    modal("Add $clean to watchlist") { body ->
        body.appendChild(el("p", "modal-copy", "Save this security to your watchlist. Cloud syncing uses your existing Supabase account and the public.watchlists table."))
        val row = el("div", "inline-actions")
        row.appendChild(actionButton("Add to watchlist", "primary", "plus") {
            addSymbolEverywhere(clean)
            (body.parentElement?.parentElement as? HTMLElement)?.remove()
        })
        row.appendChild(actionButton("Open Stock X-Ray", "secondary", "xray") {
            (body.parentElement?.parentElement as? HTMLElement)?.remove()
            openSymbol(clean)
        })
        body.appendChild(row)
        if (!Auth.signedIn) {
            body.appendChild(el("p", "muted small", "You are signed out, so this symbol is stored on this device only. Sign in from Profile to sync across devices."))
        }
    }
}

fun promptAddWatchlist(onDone: () -> Unit) {
    modal("Add a symbol") { body ->
        body.appendChild(el("p", "modal-copy", "Enter a NEPSE trading symbol."))
        val input = field("Symbol, for example NABIL")
        body.appendChild(input)
        body.appendChild(actionButton("Add", "primary", "plus") {
            val symbol = input.value.trim().uppercase()
            if (symbol.isBlank()) {
                toast("Enter a symbol first.", "error")
            } else {
                addSymbolEverywhere(symbol)
                (body.parentElement?.parentElement as? HTMLElement)?.remove()
                onDone()
            }
        })
    }
}

fun openTradingView(symbol: String) {
    window.open("https://www.tradingview.com/chart/?symbol=NEPSE%3A" + encode(symbol.uppercase()), "_blank")
}

// ---------------------------------------------------------------- watchlist screen

fun watchlistScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "PERSONAL",
        "Watchlist",
        "Your focus list, synced to Supabase when you are signed in."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var symbols: List<String> = emptyList()
    var source = "device"
    var loading = true
    var error: String? = null
    var load: () -> Unit = { }
    var removeSymbol: (String, () -> Unit) -> Unit = { _, _ -> }

    fun render() {
        body.innerHTML = ""
        val toolbar = card("toolbar-card")
        val row = el("div", "toolbar")
        val input = field("Add a symbol, for example NICA", "search", "")
        row.appendChild(input)
        row.appendChild(actionButton("Add symbol", "primary", "plus") {
            val symbol = input.value.trim().uppercase()
            if (symbol.isBlank()) {
                toast("Enter a NEPSE symbol first.", "error")
            } else {
                addSymbolEverywhere(symbol) { load() }
            }
        })
        row.appendChild(actionButton("Refresh", "ghost", "refresh") { load() })
        if (!Auth.signedIn) {
            row.appendChild(actionButton("Sign in to sync", "secondary", "profile") { go("profile") })
        }
        toolbar.appendChild(row)
        toolbar.appendChild(badge(if (Auth.signedIn) "SYNCED WITH SUPABASE" else "STORED ON THIS DEVICE", if (Auth.signedIn) "live" else "neutral"))
        body.appendChild(toolbar)

        if (loading) {
            body.appendChild(loadingState("Loading your watchlist…"))
            return
        }
        if (error != null) {
            body.appendChild(errorState(error!!) { load() })
            return
        }
        if (symbols.isEmpty()) {
            body.appendChild(emptyState(
                "Your watchlist is empty",
                "Add a NEPSE symbol to follow its price, change and liquidity at a glance."
            ))
            return
        }

        val panel = card()
        panel.appendChild(cardHead(
            "Tracked symbols",
            integer(symbols.size.toDouble()) + " securities · " + source,
            actionButton("Clear list", "ghost", "trash") {
                confirmModal("Clear watchlist", "Remove all symbols from this watchlist? Cloud rows are removed one by one.", "Clear") {
                    val remaining = symbols.toList()
                    for (symbol in remaining) removeSymbol(symbol) { }
                    symbols = emptyList()
                    render()
                }
            }
        ))
        val table = dataTable(listOf("SYMBOL", "LTP", "CHANGE", "% CHANGE", "VOLUME", ""), setOf(1, 2, 3, 4))
        for (symbol in symbols) {
            val quote = quoteFor(symbol)
            val remove = iconButton("trash", "Remove $symbol") {
                removeSymbol(symbol) {
                    symbols = symbols.filter { it != symbol }
                    render()
                }
            }
            table.row(listOf(
                el("div", "symbol-cell", symbol),
                el("div", "mono", price(quote?.ltp)),
                el("div", "mono tone-" + toneOf(quote?.change), changePoints(quote?.change)),
                el("div", "mono tone-" + toneOf(quote?.pct), percent(quote?.pct)),
                el("div", "mono", integer(quote?.volume)),
                remove
            )) { openSymbol(symbol) }
        }
        panel.appendChild(table.root.parentElement as HTMLElement)
        body.appendChild(panel)

        if (Auth.signedIn) {
            val syncCard = card()
            syncCard.appendChild(cardHead("Device to cloud sync", "Push symbols saved on this device into your Supabase watchlist"))
            syncCard.appendChild(actionButton("Sync device symbols", "secondary", "refresh") {
                val device = localWatchlist()
                if (device.isEmpty()) {
                    toast("No device symbols to sync.", "info")
                    return@actionButton
                }
                for (symbol in device) {
                    Auth.addToWatchlist(symbol) { _, _ -> }
                }
                toast("Syncing " + device.size + " symbols to Supabase.", "info")
                window.setTimeout({ load() }, 1200)
            })
            body.appendChild(syncCard)
        }
    }

    removeSymbol = { symbol, done ->
        val local = localWatchlist()
        local.remove(symbol)
        saveLocalWatchlist(local)
        if (Auth.signedIn) {
            Auth.removeFromWatchlist(symbol) { errorMessage, _ ->
                if (errorMessage != null) toast("Removed locally. Cloud removal failed: $errorMessage", "error")
                done()
            }
        } else {
            done()
        }
    }

    load = {
        loading = true
        error = null
        render()
        if (Auth.signedIn) {
            Auth.loadWatchlist { cloud, message ->
                loading = false
                if (cloud != null) {
                    symbols = cloud
                    source = "Supabase cloud watchlist"
                } else {
                    symbols = localWatchlist()
                    source = "device storage"
                    error = message
                }
                render()
            }
        } else {
            symbols = localWatchlist()
            source = "device storage"
            loading = false
            render()
        }
    }

    load()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- portfolio

fun portfolioScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "PERSONAL",
        "Portfolio",
        "Holdings valued against the live session. Positions are stored on this device."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var holdings = loadHoldings()
    var openHoldingDialog: () -> Unit = { }

    fun render() {
        body.innerHTML = ""
        val addButton = actionButton("Add holding", "primary", "plus") { openHoldingDialog() }
        val headCard = card("toolbar-card")
        val row = el("div", "toolbar")
        row.appendChild(addButton)
        row.appendChild(actionButton("Refresh prices", "ghost", "refresh") {
            loadMarket(force = true) { _, _ -> render() }
        })
        headCard.appendChild(row)
        headCard.appendChild(el("p", "muted small", "Live prices come from the NEPSE Pulse feed. Portfolio positions are saved in this browser/app storage, not in Supabase."))
        body.appendChild(headCard)

        if (holdings.isEmpty()) {
            body.appendChild(emptyState(
                "No holdings yet",
                "Add the shares you own with your average cost to track live value and profit or loss.",
                "Add your first holding"
            ) { openHoldingDialog() })
            return
        }

        var invested = 0.0
        var current = 0.0
        var dayChange = 0.0
        var priced = 0
        for (holding in holdings) {
            val quote = quoteFor(holding.symbol)
            invested += holding.quantity * holding.average
            if (quote?.ltp != null) {
                current += holding.quantity * quote.ltp
                dayChange += holding.quantity * (quote.change ?: 0.0)
                priced += 1
            } else {
                current += holding.quantity * holding.average
            }
        }
        val profit = current - invested
        val profitPct = if (invested > 0) profit / invested * 100.0 else null

        val stats = el("div", "stat-grid")
        stats.appendChild(statTile("INVESTED", rupees(invested), integer(holdings.size.toDouble()) + " positions"))
        stats.appendChild(statTile("CURRENT VALUE", rupees(current), integer(priced.toDouble()) + " priced live"))
        stats.appendChild(statTile("UNREALISED P/L", rupees(profit), profitPct?.let { percent(it) } ?: "—", toneClass(profit)))
        stats.appendChild(statTile("DAY CHANGE", rupees(dayChange), "Based on session point change", toneClass(dayChange)))
        body.appendChild(stats)

        val panel = card()
        panel.appendChild(cardHead("Holdings", "Quantities, average cost and live valuation"))
        val table = dataTable(listOf("SYMBOL", "QTY", "AVG COST", "LTP", "VALUE", "P/L", ""), setOf(1, 2, 3, 4, 5))
        for (holding in holdings) {
            val quote = quoteFor(holding.symbol)
            val value = (quote?.ltp ?: holding.average) * holding.quantity
            val pl = ((quote?.ltp ?: holding.average) - holding.average) * holding.quantity
            val plText = if (quote?.ltp == null) "—" else changePoints(pl)
            val symbolCell = el("div", "symbol-cell")
            symbolCell.appendChild(el("strong", "", holding.symbol))
            symbolCell.appendChild(el("small", "", quote?.name ?: "Price unavailable"))
            table.row(listOf(
                symbolCell,
                el("div", "mono", integer(holding.quantity)),
                el("div", "mono", price(holding.average)),
                el("div", "mono", price(quote?.ltp)),
                el("div", "mono", rupees(value)),
                el("div", "mono tone-" + toneOf(pl), plText),
                iconButton("trash", "Remove holding") {
                    holdings = holdings.filter { it.symbol != holding.symbol || it.average != holding.average }.toMutableList()
                    saveHoldings(holdings)
                    render()
                }
            )) { openSymbol(holding.symbol) }
        }
        panel.appendChild(table.root.parentElement as HTMLElement)
        body.appendChild(panel)

        val allocation = card()
        allocation.appendChild(cardHead("Allocation", "Share of current portfolio value"))
        val values = holdings.map { holding ->
            val quote = quoteFor(holding.symbol)
            holding.symbol to holding.quantity * (quote?.ltp ?: holding.average)
        }.sortedByDescending { it.second }
        val totalValue = values.sumOf { it.second }.coerceAtLeast(1.0)
        for ((symbol, value) in values.take(8)) {
            val line = el("div", "share-row")
            line.appendChild(el("span", "share-name", symbol))
            line.appendChild(progressBar(value / totalValue, "accent"))
            line.appendChild(el("span", "share-value", fixed(value / totalValue * 100.0, 1) + "%"))
            allocation.appendChild(line)
        }
        body.appendChild(allocation)
    }

    openHoldingDialog = {
        modal("Add holding") { bodyNode ->
            val symbolInput = field("Symbol, for example NABIL")
            val qtyInput = field("Quantity", "number")
            val priceInput = field("Average cost per share", "number")
            bodyNode.add(symbolInput, qtyInput, priceInput)
            val status = el("p", "muted small", "Positions are stored on this device.")
            bodyNode.appendChild(status)
            bodyNode.appendChild(actionButton("Save holding", "primary", "check") {
                val symbol = symbolInput.value.trim().uppercase()
                val qty = qtyInput.value.toDoubleOrNull()
                val avg = priceInput.value.toDoubleOrNull()
                if (symbol.isBlank() || qty == null || avg == null || qty <= 0 || avg <= 0) {
                    status.textContent = "Enter a symbol, a positive quantity and a positive average cost."
                } else {
                    holdings = holdings.filter { it.symbol != symbol }.toMutableList()
                    holdings.add(Holding(symbol, qty, avg))
                    saveHoldings(holdings)
                    (bodyNode.parentElement?.parentElement as? HTMLElement)?.remove()
                    if (quoteFor(symbol) == null) {
                        toast("Holding saved. $symbol is not in the live rows, so it is valued at cost.", "info")
                    } else {
                        toast("Holding saved for $symbol.", "success")
                    }
                    render()
                }
            })
        }
    }

    if (Ctx.market == null) {
        loadMarket { _, _ -> render() }
    }
    render()
    registerRefresh { if (Ctx.quotes.isNotEmpty()) render() }
    return root
}

// ---------------------------------------------------------------- alerts

fun alertTriggered(alert: PriceAlert): Boolean {
    val quote = quoteFor(alert.symbol) ?: return false
    return when (alert.kind) {
        "above" -> (quote.ltp ?: return false) >= alert.value
        "below" -> (quote.ltp ?: return false) <= alert.value
        "pctup" -> (quote.pct ?: return false) >= alert.value
        "pctdown" -> (quote.pct ?: return false) <= -abs(alert.value)
        else -> false
    }
}

fun alertKindLabel(kind: String): String = when (kind) {
    "above" -> "Price at or above"
    "below" -> "Price at or below"
    "pctup" -> "Gain of at least"
    "pctdown" -> "Fall of at least"
    else -> kind
}

fun openAlertDialog(prefillSymbol: String = "") {
    modal("Create a price alert") { body ->
        val symbolInput = field("Symbol, for example NICA", "text", prefillSymbol.uppercase())
        val kindSelect = selectField(listOf(
            "above" to "Price at or above",
            "below" to "Price at or below",
            "pctup" to "Gain of at least (%)",
            "pctdown" to "Fall of at least (%)"
        ), "above") { }
        val valueInput = field("Trigger value", "number")
        body.add(symbolInput, kindSelect, valueInput)
        val status = el("p", "muted small", "Alerts are evaluated in the app against live session prices and stored on this device.")
        body.appendChild(status)
        body.appendChild(actionButton("Create alert", "primary", "bell") {
            val symbol = symbolInput.value.trim().uppercase()
            val value = valueInput.value.toDoubleOrNull()
            if (symbol.isBlank() || value == null || value <= 0) {
                status.textContent = "Enter a valid symbol and a positive trigger value."
            } else {
                val alert = PriceAlert(
                    id = "a" + nowMs().toLong().toString() + (1000..9999).random(),
                    symbol = symbol,
                    kind = kindSelect.value,
                    value = value,
                    createdAt = nowMs()
                )
                val alerts = loadAlerts()
                alerts.add(alert)
                saveAlerts(alerts)
                (body.parentElement?.parentElement as? HTMLElement)?.remove()
                toast("Alert created for $symbol.", "success")
                go("alerts")
            }
        })
    }
}

fun alertsScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "PERSONAL",
        "Price alerts",
        "Watch trigger levels without staring at the tape."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)

    fun render() {
        body.innerHTML = ""
        val toolbar = card("toolbar-card")
        val row = el("div", "toolbar")
        row.appendChild(actionButton("New alert", "primary", "plus") { openAlertDialog() })
        row.appendChild(actionButton("Re-check live prices", "ghost", "refresh") {
            loadMarket(force = true) { _, _ -> render() }
        })
        toolbar.appendChild(row)
        toolbar.appendChild(el("p", "muted small", "Alerts live on this device. Cloud sync for alerts is not part of the existing Supabase schema."))
        body.appendChild(toolbar)

        val alerts = loadAlerts()
        if (alerts.isEmpty()) {
            body.appendChild(emptyState(
                "No alerts yet",
                "Create a price or percentage alert and the app will flag it during market refresh."
            ))
            return
        }
        var triggeredCount = 0
        for (alert in alerts) {
            if (alertTriggered(alert)) triggeredCount += 1
        }
        val stats = el("div", "stat-grid")
        stats.appendChild(statTile("ACTIVE ALERTS", integer(alerts.size.toDouble()), "Stored on this device"))
        stats.appendChild(statTile("TRIGGERED", integer(triggeredCount.toDouble()), "Matched by current live price", if (triggeredCount > 0) "up" else ""))
        body.appendChild(stats)

        val panel = card()
        panel.appendChild(cardHead("Your alerts", "Evaluated against the latest session data"))
        val table = dataTable(listOf("SYMBOL", "CONDITION", "TARGET", "LIVE", "STATUS", ""), setOf(2, 3))
        for (alert in alerts) {
            val quote = quoteFor(alert.symbol)
            val triggered = alertTriggered(alert)
            table.row(listOf(
                el("div", "symbol-cell", alert.symbol),
                el("div", "small", alertKindLabel(alert.kind)),
                el("div", "mono", if (alert.kind == "above" || alert.kind == "below") price(alert.value) else fixed(alert.value, 1) + "%"),
                el("div", "mono", if (alert.kind == "above" || alert.kind == "below") price(quote?.ltp) else percent(quote?.pct)),
                badge(if (triggered) "Triggered" else "Watching", if (triggered) "up" else "neutral"),
                iconButton("trash", "Delete alert") {
                    val remaining = alerts.filter { it.id != alert.id }
                    saveAlerts(remaining)
                    render()
                }
            )) { openSymbol(alert.symbol) }
        }
        panel.appendChild(table.root.parentElement as HTMLElement)
        body.appendChild(panel)
    }

    render()
    registerRefresh { render() }
    return root
}

// ---------------------------------------------------------------- corporate actions and IPO

fun eventMatches(item: FeedItem, pattern: Regex): Boolean =
    pattern.containsMatchIn(item.title) || pattern.containsMatchIn(item.detail)

fun eventsScreen(kind: String): HTMLElement {
    val ipo = kind == "ipo"
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "PRIMARY MARKET",
        if (ipo) "IPO center" else "Corporate actions",
        if (ipo) "Primary issues, FPOs, rights, debentures and fund offers reported by the connected provider."
        else "Dividends, bonus shares, rights, book closures and company meetings reported by the provider."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var query = ""
    var checkedAt = ""

    fun paint(force: Boolean = false) {
        body.innerHTML = ""
        val host = el("div")
        host.appendChild(loadingState("Collecting provider disclosures…"))
        body.appendChild(host)
        val pattern = if (ipo) {
            Regex("\\b(ipo|fpo|right|debenture|mutual fund|primary issue|public issue|auction|initial public offering|further public offering|unit issue)\\b", RegexOption.IGNORE_CASE)
        } else {
            Regex("\\b(dividend|bonus|right|book close|book closure|corporate action|agm|egm|annual general|extraordinary general|merger|acquisition|split|share issuance|cash dividend|share register)\\b", RegexOption.IGNORE_CASE)
        }
        loadFeed(listOf(
            "/api/production/events" to "Events",
            "/api/production/notices" to "Notices",
            "/api/production/disclosures" to "Disclosures",
            "/api/production/announcements" to "Announcements"
        )) { items, errors ->
            checkedAt = js("new Date()").toLocaleTimeString() as String
            host.innerHTML = ""
            val wrap = el("div", "events")
            var renderList: () -> Unit = { }
            val toolbar = card("toolbar-card")
            val row = el("div", "toolbar")
            val input = field("Filter by company or keyword", "search", query)
            input.addEventListener("input", {
                query = input.value
                renderList()
            })
            row.appendChild(input)
            row.appendChild(actionButton("Reload", "ghost", "refresh") { paint(true) })
            toolbar.appendChild(row)
            val relevant = items.filter { eventMatches(it, pattern) }
            val meta = el("p", "muted small", integer(relevant.size.toDouble()) + " matching records · checked " + checkedAt +
                (if (errors.isNotEmpty()) " · some sources failed" else " · all sources reached"))
            toolbar.appendChild(meta)
            wrap.appendChild(toolbar)
            val listHost = el("div", "event-list")
            wrap.appendChild(listHost)

            renderList = {
                listHost.innerHTML = ""
                val filtered = relevant.filter {
                    query.isBlank() || it.title.contains(query, true) || it.detail.contains(query, true)
                }
                if (filtered.isEmpty()) {
                    listHost.appendChild(emptyState(
                        if (relevant.isEmpty()) "No matching provider records" else "No records match your filter",
                        if (relevant.isEmpty()) {
                            if (errors.isEmpty()) "The connected provider returned no qualifying records for this category right now."
                            else "Provider sources failed: " + errors.first()
                        } else "Adjust the filter to see more records."
                    ))
                } else for (item in filtered.take(80)) {
                    val cardNode = card("event-card")
                    val head = el("div", "event-head")
                    val copy = el("div")
                    copy.appendChild(el("div", "event-title", item.title))
                    if (item.detail.isNotBlank() && item.detail != item.title) {
                        copy.appendChild(el("p", "event-detail", item.detail.take(320)))
                    }
                    head.appendChild(copy)
                    val side = el("div", "event-side")
                    side.appendChild(badge(item.source, "neutral"))
                    side.appendChild(el("span", "event-date", shortDate(item.date)))
                    head.appendChild(side)
                    cardNode.appendChild(head)
                    listHost.appendChild(cardNode)
                }
            }
            renderList()
            body.appendChild(wrap)

            if (!ipo) {
                val dividendCard = card()
                dividendCard.appendChild(cardHead("Dividend history lookup", "Query the provider archive for a specific symbol"))
                val dividendRow = el("div", "toolbar")
                val symbolInput = field("Symbol, for example NICA")
                dividendRow.appendChild(symbolInput)
                val resultHost = el("div")
                dividendRow.appendChild(actionButton("Look up", "secondary", "search") {
                    val symbol = symbolInput.value.trim().uppercase()
                    if (symbol.isBlank()) {
                        toast("Enter a symbol first.", "error")
                        return@actionButton
                    }
                    resultHost.innerHTML = ""
                    resultHost.appendChild(skeleton(60))
                    Http.get("/api/dividend-history/" + encode(symbol), ttlMs = 300_000.0, onData = { payload ->
                        resultHost.innerHTML = ""
                        val rows = rowsOf(payload?.data).ifEmpty { rowsOf(payload?.dividendHistory) }
                        if (rows.isEmpty()) {
                            resultHost.appendChild(el("p", "muted small", text(pick(payload, "note")) ?: "No dividend records were returned for $symbol."))
                        } else {
                            val table = dataTable(listOf("FISCAL YEAR", "CASH %", "BONUS %", "TOTAL %", "ANNOUNCED"), setOf(1, 2, 3))
                            for (d in rows) {
                                table.row(listOf(
                                    el("div", "small", text(pick(d, "fiscalYear", "year", "fiscal_year")) ?: "—"),
                                    el("div", "mono", text(pick(d, "cashDividend", "cash_dividend")) ?: "—"),
                                    el("div", "mono", text(pick(d, "bonusDividend", "bonus_dividend")) ?: "—"),
                                    el("div", "mono", text(pick(d, "totalDividend", "total_dividend")) ?: "—"),
                                    el("div", "small", shortDate(text(pick(d, "announcedDate", "date")) ?: ""))
                                ))
                            }
                            resultHost.appendChild(table.root.parentElement as HTMLElement)
                        }
                    }, onError = { message ->
                        resultHost.innerHTML = ""
                        resultHost.appendChild(el("p", "muted small", message))
                    })
                })
                dividendCard.appendChild(dividendRow)
                dividendCard.appendChild(resultHost)
                body.appendChild(dividendCard)
            }
        }
    }
    paint()
    registerRefresh { }
    return root
}

// ---------------------------------------------------------------- profile and auth

fun profileScreen(): HTMLElement {
    val root = el("div", "screen")
    root.appendChild(pageHead(
        "ACCOUNT",
        "Profile & settings",
        "Your NEPSE Pulse identity, watchlist sync, theme and privacy controls."
    ))
    val body = el("div", "screen-body")
    root.appendChild(body)
    var mode = "email"
    var renderAuth: () -> Unit = { }
    var renderAccount: () -> Unit = { }
    var renderAppInfo: () -> Unit = { }

    fun render() {
        body.innerHTML = ""
        if (!Auth.signedIn) {
            renderAuth()
        } else {
            renderAccount()
        }
    }

    renderAuth = {
        body.innerHTML = ""
        val panel = card("auth-card")
        panel.appendChild(cardHead("Sign in to NEPSE Pulse", "Use the existing Supabase project — no new account system"))
        val tabs = el("div", "tabs")
        for ((key, label) in listOf("email" to "Email", "phone" to "Phone OTP", "reset" to "Reset password")) {
            tabs.appendChild(chipButton(label, mode == key) { mode = key; renderAuth() })
        }
        panel.appendChild(tabs)

        val status = el("p", "auth-status", if (Auth.client == null) "Supabase is not configured for this build." else "Secure session with Supabase Auth.")
        if (mode == "email") {
            val emailInput = field("Email address", "email")
            emailInput.autocomplete = "email"
            val passwordInput = field("Password", "password")
            passwordInput.autocomplete = "current-password"
            panel.add(emailInput, passwordInput)
            val actions = el("div", "inline-actions")
            actions.appendChild(actionButton("Sign in", "primary", "check") {
                val email = emailInput.value.trim()
                if (email.isEmpty() || passwordInput.value.isEmpty()) {
                    status.textContent = "Enter your email and password."
                } else {
                    status.textContent = "Signing in…"
                    Auth.signIn(email, passwordInput.value) { error, message ->
                        status.textContent = error ?: message ?: "Signed in."
                        if (error == null) {
                            toast("Signed in successfully.", "success")
                            render()
                        }
                    }
                }
            })
            actions.appendChild(actionButton("Create account", "secondary", "plus") {
                val email = emailInput.value.trim()
                if (email.isEmpty() || passwordInput.value.length < 6) {
                    status.textContent = "Enter an email and a password of at least 6 characters."
                } else {
                    status.textContent = "Creating your account…"
                    Auth.signUp(email, passwordInput.value) { error, message ->
                        status.textContent = error ?: message ?: "Account request completed."
                        if (error == null) toast("Account request completed.", "success")
                    }
                }
            })
            panel.appendChild(actions)
        } else if (mode == "phone") {
            val phoneInput = field("Phone with country code, e.g. +9779800000000", "tel")
            val otpInput = field("SMS verification code", "text")
            otpInput.inputMode = "numeric"
            panel.add(phoneInput, otpInput)
            val actions = el("div", "inline-actions")
            actions.appendChild(actionButton("Send code", "primary", "phone") {
                val phone = phoneInput.value.trim()
                if (phone.length < 8) {
                    status.textContent = "Enter a phone number with country code."
                } else {
                    status.textContent = "Requesting a verification code…"
                    Auth.sendOtp(phone) { error, message ->
                        status.textContent = error ?: message ?: "Code requested."
                    }
                }
            })
            actions.appendChild(actionButton("Verify code", "secondary", "check") {
                val phone = phoneInput.value.trim()
                val token = otpInput.value.trim()
                if (phone.isEmpty() || token.isEmpty()) {
                    status.textContent = "Enter the phone number and the code you received."
                } else {
                    status.textContent = "Verifying…"
                    Auth.verifyOtp(phone, token) { error, message ->
                        status.textContent = error ?: message ?: "Verified."
                        if (error == null) {
                            toast("Phone sign-in successful.", "success")
                            render()
                        }
                    }
                }
            })
            panel.appendChild(actions)
        } else {
            val emailInput = field("Account email", "email")
            panel.appendChild(emailInput)
            panel.appendChild(actionButton("Send reset email", "primary", "mail") {
                val email = emailInput.value.trim()
                if (email.isEmpty()) {
                    status.textContent = "Enter the email on your account."
                } else {
                    status.textContent = "Requesting a reset link…"
                    Auth.resetPassword(email) { error, message ->
                        status.textContent = error ?: message ?: "Reset email requested."
                    }
                }
            })
        }
        panel.appendChild(status)

        val divider = el("div", "divider")
        divider.appendChild(el("span", "", "or continue with"))
        panel.appendChild(divider)
        val oauth = el("div", "oauth-row")
        val google = el("button", "btn btn-oauth google")
        google.appendChild(el("span", "oauth-mark google-mark", "G"))
        google.appendChild(el("span", "", "Google"))
        google.onClick {
            status.textContent = "Opening Google…"
            Auth.startOAuth("google") { error ->
                if (error != null) status.textContent = error else status.textContent = "Complete sign-in in the opened window."
            }
        }
        val facebook = el("button", "btn btn-oauth facebook")
        facebook.appendChild(el("span", "oauth-mark facebook-mark", "f"))
        facebook.appendChild(el("span", "", "Facebook"))
        facebook.onClick {
            status.textContent = "Opening Facebook…"
            Auth.startOAuth("facebook") { error ->
                if (error != null) status.textContent = error else status.textContent = "Complete sign-in in the opened window."
            }
        }
        oauth.add(google, facebook)
        panel.appendChild(oauth)
        panel.appendChild(el("p", "muted small", "On Android, provider sign-in returns through the com.nepsepulse.app://login-callback deep link."))
        body.appendChild(panel)
    }

    renderAccount = {
        val identity = card("identity-card")
        val head = el("div", "identity-head")
        val avatar = el("div", "avatar", Auth.initials())
        head.appendChild(avatar)
        val copy = el("div")
        copy.appendChild(el("div", "eyebrow", "SIGNED IN"))
        copy.appendChild(el("h2", "identity-name", Auth.displayName()))
        copy.appendChild(el("p", "muted", Auth.provider()))
        head.appendChild(copy)
        head.appendChild(badge("CONNECTED", "live"))
        identity.appendChild(head)
        val grid = el("div", "kv-grid")
        grid.appendChild(pair("Email", Auth.email()))
        grid.appendChild(pair("Phone", Auth.phone()))
        grid.appendChild(pair("User ID", text(Auth.user?.id)?.take(8)?.plus("…") ?: "—"))
        grid.appendChild(pair("Joined", shortDate(text(Auth.user?.created_at)?.take(10) ?: "")))
        identity.appendChild(grid)
        body.appendChild(identity)

        val sync = card()
        sync.appendChild(cardHead("Cloud watchlist", "Read and manage your Supabase watchlist"))
        val status = el("p", "muted small", "Loading your cloud watchlist…")
        val listHost = el("div", "watchlist-chips")
        sync.add(listHost, status)
        sync.appendChild(actionButton("Sync device symbols", "secondary", "refresh") {
            val device = localWatchlist()
            if (device.isEmpty()) {
                toast("No device symbols to sync.", "info")
            } else {
                for (symbol in device) Auth.addToWatchlist(symbol) { _, _ -> }
                toast("Syncing " + device.size + " symbols.", "info")
                window.setTimeout({ render() }, 1200)
            }
        })
        body.appendChild(sync)
        Auth.loadWatchlist { symbols, message ->
            listHost.innerHTML = ""
            if (symbols == null) {
                status.textContent = message ?: "Cloud watchlist is unavailable."
            } else if (symbols.isEmpty()) {
                status.textContent = "No cloud symbols yet. Add securities from Stock X-Ray or the Watchlist screen."
            } else {
                status.textContent = integer(symbols.size.toDouble()) + " symbols synced."
                for (symbol in symbols) {
                    val chip = el("button", "watch-chip", symbol)
                    chip.onClick { openSymbol(symbol) }
                    listHost.appendChild(chip)
                }
            }
        }

        val settings = card()
        settings.appendChild(cardHead("Preferences", "Appearance and local data"))
        val themeRow = el("div", "setting-row")
        val themeCopy = el("div")
        themeCopy.appendChild(el("div", "setting-title", "Theme"))
        themeCopy.appendChild(el("div", "muted small", "Currently using the " + Theme.current + " theme"))
        themeRow.appendChild(themeCopy)
        themeRow.appendChild(actionButton("Switch theme", "ghost", if (Theme.current == "dark") "sun" else "moon") {
            Theme.toggle()
            applyThemeToBody()
            render()
        })
        settings.appendChild(themeRow)
        val cacheRow = el("div", "setting-row")
        val cacheCopy = el("div")
        cacheCopy.appendChild(el("div", "setting-title", "Cached market data"))
        cacheCopy.appendChild(el("div", "muted small", "Clear cached API responses to force a fresh fetch"))
        cacheRow.appendChild(cacheCopy)
        cacheRow.appendChild(actionButton("Clear cache", "ghost", "refresh") {
            Http.clear()
            toast("Market cache cleared.", "success")
        })
        settings.appendChild(cacheRow)
        val alertsRow = el("div", "setting-row")
        val alertsCopy = el("div")
        alertsCopy.appendChild(el("div", "setting-title", "Device data"))
        alertsCopy.appendChild(el("div", "muted small", "Portfolio and alerts are stored locally in this app"))
        alertsRow.appendChild(alertsCopy)
        alertsRow.appendChild(actionButton("Manage alerts", "ghost", "alerts") { go("alerts") })
        settings.appendChild(alertsRow)
        body.appendChild(settings)

        val privacy = card()
        privacy.appendChild(cardHead("Data & privacy", "Policies and account lifecycle"))
        val links = el("div", "inline-actions")
        val privacyLink = el("a", "btn btn-ghost", "Privacy policy")
        privacyLink.setAttribute("href", "privacy-policy.html")
        privacyLink.setAttribute("target", "_blank")
        privacyLink.setAttribute("rel", "noopener")
        links.appendChild(privacyLink)
        links.appendChild(actionButton("Request account deletion", "danger", "trash") {
            Auth.openDeleteAccount()
        })
        privacy.appendChild(links)
        privacy.appendChild(el("p", "muted small", "Account deletion is processed by the operator after email verification, as described on the deletion page. The app does not delete your Supabase auth account on its own."))
        body.appendChild(privacy)

        body.appendChild(actionButton("Sign out", "secondary", "logout") {
            Auth.signOut { error ->
                if (error != null) toast(error, "error") else {
                    toast("Signed out.", "success")
                    render()
                }
            }
        })
    }

    renderAppInfo = {
        val info = card("app-info")
        info.appendChild(cardHead("About this build", "Migration and data source details"))
        val grid = el("div", "kv-grid")
        grid.appendChild(pair("App version", APP_VERSION))
        grid.appendChild(pair("Platform", "Kotlin/JS + Capacitor Android"))
        grid.appendChild(pair("Backend", "nepse-pulse-1.onrender.com"))
        grid.appendChild(pair("Supabase", if (Auth.client != null) "Connected" else "Not configured"))
        grid.appendChild(pair("Feed status", if (Ctx.online) "Responding" else "Unreachable"))
        grid.appendChild(pair("Last market update", updatedLabel()))
        info.appendChild(grid)
        val health = el("div", "health-row")
        val healthText = el("span", "muted small", "Checking the Render service…")
        health.appendChild(healthText)
        health.appendChild(actionButton("Ping service", "ghost", "refresh") {
            healthText.textContent = "Checking…"
            Http.get("/health", ttlMs = 0.0, timeoutMs = 25_000, onData = { payload ->
                val version = text(pick(payload, "version")) ?: "unknown"
                val status = text(pick(payload, "status")) ?: "unknown"
                healthText.textContent = "Service " + status + " · " + version
            }, onError = { message ->
                healthText.textContent = message
            })
        })
        info.appendChild(health)
        info.appendChild(el("p", "muted small", "NEPSE Pulse presents market data from connected providers. It is not a broker and does not execute trades."))
        body.appendChild(info)
    }

    render()
    Auth.onChange { if (document.body?.contains(root) == true) render() }
    registerRefresh { }
    return root
}
