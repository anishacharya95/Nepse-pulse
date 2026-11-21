import org.w3c.dom.HTMLElement

/**
 * Company fundamentals for NEPSE securities.
 *
 * Source: the existing Render endpoint `/api/fundamentals/{symbol}`, which
 * exposes NEPSE financial reports (EPS, P/E, paid-up capital, profit and net
 * worth per share), trading data and the provider's field availability map.
 *
 * Nothing here is invented: a value is only computed when every input is
 * present, otherwise the row renders "N/A". Periods are kept explicit so a
 * quarterly figure is never presented as an annual one.
 */
data class FinancialReport(
    val kind: String,
    val quarter: String,
    val fiscalYear: String,
    val fiscalYearNepali: String,
    val eps: Double?,
    val pe: Double?,
    val paidUpCapital: Double?,
    val netProfit: Double?,
    val bookValue: Double?,
    val submitted: String
) {
    val isAnnual: Boolean get() = kind.contains("annual", ignoreCase = true)
    val fiscalYearStart: Int
        get() = fiscalYear.take(4).toIntOrNull() ?: 0
    val quarterOrder: Int
        get() = when {
            quarter.contains("fourth", ignoreCase = true) -> 4
            quarter.contains("third", ignoreCase = true) -> 3
            quarter.contains("second", ignoreCase = true) -> 2
            quarter.contains("first", ignoreCase = true) -> 1
            isAnnual -> 4
            else -> 0
        }
    val periodLabel: String
        get() {
            val pieces = ArrayList<String>()
            if (kind.isNotBlank()) pieces.add(kind)
            if (quarter.isNotBlank()) pieces.add(quarter)
            if (fiscalYear.isNotBlank()) pieces.add("FY " + fiscalYear)
            return if (pieces.isEmpty()) "period not published" else pieces.joinToString(" · ")
        }
}

data class Fundamentals(
    val symbol: String,
    val securityName: String,
    val sector: String,
    val ltp: Double?,
    val previousClose: Double?,
    val high52: Double?,
    val low52: Double?,
    val tradingDate: String,
    val lastUpdated: String,
    val latest: FinancialReport?,
    val annual: FinancialReport?,
    val ttmEps: Double?,
    val ttmNetProfit: Double?,
    val ttmPeriod: String,
    val reports: List<FinancialReport>,
    val shares: Double?,
    val marketCap: Double?,
    val pe: Double?,
    val pb: Double?,
    val roe: Double?,
    val roeLabel: String,
    val dividendPerShare: Double?,
    val dividendYield: Double?,
    val cashDividendPercent: Double?,
    val bonusPercent: Double?,
    val equity: Double?,
    val reserves: Double?,
    val revenue: Double?,
    val operatingProfit: Double?,
    val assets: Double?,
    val liabilities: Double?,
    val netProfit: Double?,
    val netMargin: Double?,
    val fieldStatus: Map<String, String>,
    val sourceNote: String
) {
    val hasAnyReport: Boolean get() = reports.isNotEmpty()
}

private const val NA = "N/A"

private fun labelValue(value: String?): String = if (value.isNullOrBlank()) NA else value

private fun numText(value: Double?, digits: Int = 2): String =
    if (value == null) NA else fixed(value, digits)

private fun moneyText(value: Double?): String =
    if (value == null) NA else "Rs " + compact(value)

private fun percentText(value: Double?): String =
    if (value == null) NA else fixed(value, 2) + "%"

private fun timesText(value: Double?): String =
    if (value == null) NA else fixed(value, 2) + "×"

private fun reportComparator(a: FinancialReport, b: FinancialReport): Int {
    val submitted = b.submitted.compareTo(a.submitted)
    if (submitted != 0) return submitted
    val year = b.fiscalYearStart.compareTo(a.fiscalYearStart)
    if (year != 0) return year
    return b.quarterOrder.compareTo(a.quarterOrder)
}

private fun reportFromPublic(raw: dynamic): FinancialReport? {
    if (raw == null || raw == undefined) return null
    val document = rowsOf(pick(raw, "documents")).firstOrNull()
    val submitted = text(pick(document, "submitted_date", "submittedDate", "date")) ?: ""
    val kind = text(pick(raw, "type", "reportName", "report_type")) ?: ""
    val quarter = text(pick(raw, "quarter", "quarterName")) ?: ""
    val fy = text(pick(raw, "fy", "fiscalYear", "fiscal_year")) ?: ""
    if (kind.isBlank() && fy.isBlank()) return null
    return FinancialReport(
        kind = kind,
        quarter = quarter,
        fiscalYear = fy,
        fiscalYearNepali = text(pick(raw, "fy_nepali", "fyNepali")) ?: "",
        eps = num(pick(raw, "eps")),
        pe = num(pick(raw, "pe")),
        paidUpCapital = num(pick(raw, "paid_up_capital", "paidUpCapital")),
        netProfit = num(pick(raw, "profit", "netProfit", "profitAmount")),
        bookValue = num(pick(raw, "net_worth_per_share", "netWorthPerShare", "bookValue")),
        submitted = submitted
    )
}

private fun reportFromNepse(raw: dynamic): FinancialReport? {
    if (raw == null || raw == undefined) return null
    val fiscal = pick(raw, "fiscalReport") ?: raw
    val yearNode = pick(fiscal, "financialYear")
    val quarterNode = pick(fiscal, "quarterMaster")
    val typeNode = pick(fiscal, "reportTypeMaster")
    val documents = rowsOf(pick(raw, "applicationDocumentDetailsList"))
    val submitted = documents.mapNotNull { text(pick(it, "submittedDate", "submitted_date")) }
        .maxOrNull() ?: ""
    val kind = text(pick(typeNode, "reportName")) ?: ""
    val quarter = text(pick(quarterNode, "quarterName")) ?: ""
    val fy = text(pick(yearNode, "fyName")) ?: ""
    if (kind.isBlank() && fy.isBlank()) return null
    return FinancialReport(
        kind = kind,
        quarter = quarter,
        fiscalYear = fy,
        fiscalYearNepali = text(pick(yearNode, "fyNameNepali")) ?: "",
        eps = num(pick(fiscal, "epsValue", "eps")),
        pe = num(pick(fiscal, "peValue", "pe")),
        paidUpCapital = num(pick(fiscal, "paidUpCapital")),
        netProfit = num(pick(fiscal, "profitAmount", "profit")),
        bookValue = num(pick(fiscal, "netWorthPerShare", "bookValue")),
        submitted = submitted
    )
}

private fun parseReports(payload: dynamic): List<FinancialReport> {
    val collected = ArrayList<FinancialReport>()
    val publicGroups = rowsOf(pick(pick(payload, "publicData"), "financials"))
    for (group in publicGroups) {
        for (raw in rowsOf(pick(group, "reports"))) {
            reportFromPublic(raw)?.let { collected.add(it) }
        }
    }
    if (collected.isEmpty()) {
        for (raw in rowsOf(pick(payload, "financialReports"))) {
            reportFromNepse(raw)?.let { collected.add(it) }
        }
    }
    if (collected.isEmpty()) {
        for (raw in rowsOf(pick(payload, "financials"))) {
            reportFromNepse(raw)?.let { collected.add(it) }
        }
    }
    // One row per period; keep the most recently submitted duplicate.
    val deduped = LinkedHashMap<String, FinancialReport>()
    for (report in collected.sortedWith(Comparator { a, b -> reportComparator(a, b) })) {
        val key = report.kind.lowercase() + "|" + report.quarter.lowercase() + "|" + report.fiscalYear
        if (!deduped.containsKey(key)) deduped[key] = report
    }
    return deduped.values.sortedWith(Comparator { a, b -> reportComparator(a, b) })
}

private fun dividendPercentFromNews(payload: dynamic): Double? {
    val pattern = Regex("cash\\s*dividend\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%", RegexOption.IGNORE_CASE)
    for (key in listOf("agm", "dividends", "companyNews")) {
        for (entry in rowsOf(pick(payload, key))) {
            val news = pick(entry, "companyNews") ?: entry
            val body = text(pick(news, "newsBody")) ?: continue
            val match = pattern.find(body)
            if (match != null) {
                val value = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                if (value != null && value > 0) return value
            }
        }
    }
    return null
}

fun parseFundamentals(symbol: String, payload: dynamic): Fundamentals {
    val core = pick(payload, "fundamentals") ?: payload
    val company = pick(core, "company") ?: pick(payload, "company")
    val profile = pick(core, "profile") ?: pick(payload, "profile")
    val detail = pick(core, "detail") ?: pick(payload, "detail")
    val mcs = pick(detail, "securityMcsData") ?: detail
    val security = pick(detail, "securityData") ?: pick(payload, "security")
    val market = pick(core, "market") ?: pick(payload, "market")
    val marketSecurity = pick(market, "security") ?: pick(pick(payload, "publicData"), "security")
    val performance = pick(payload, "performance") ?: pick(core, "performance")

    val reports = parseReports(payload)
    val latest = reports.firstOrNull()
    // A fourth-quarter report carries the full fiscal-year result, whether the
    // provider labels it "Annual Report" or "Quarterly Report".
    val annual = reports.firstOrNull { it.isAnnual || it.quarterOrder == 4 }
    val latestBook = latest?.bookValue ?: annual?.bookValue ?: num(pick(performance, "bookValue"))
    val latestPaidUp = latest?.paidUpCapital ?: annual?.paidUpCapital
    val faceValue = num(pick(marketSecurity, "faceValue")) ?: 100.0
    val shares = if (latestPaidUp != null && latestPaidUp > 0 && faceValue > 0) latestPaidUp / faceValue else null

    val ltp = num(pick(mcs, "lastTradedPrice", "closePrice")) ?: num(pick(market, "lastTradedPrice", "closePrice"))
    val previousClose = num(pick(mcs, "previousClose")) ?: num(pick(market, "previousDayClosePrice"))
    val high52 = num(pick(mcs, "fiftyTwoWeekHigh")) ?: num(pick(market, "fiftyTwoWeekHigh")) ?: num(pick(performance, "high52"))
    val low52 = num(pick(mcs, "fiftyTwoWeekLow")) ?: num(pick(market, "fiftyTwoWeekLow")) ?: num(pick(performance, "low52"))

    // Trailing twelve months: latest year-to-date cumulative result, plus the
    // previous full year, minus the same period of the previous year.
    var ttmNetProfit: Double? = null
    var ttmEps: Double? = null
    var ttmPeriod = NA
    if (latest != null && latest.isAnnual) {
        ttmNetProfit = latest.netProfit
        ttmEps = latest.eps
        ttmPeriod = latest.periodLabel
    } else if (latest != null && latest.netProfit != null) {
        val previousAnnual = reports.firstOrNull {
            (it.isAnnual || it.quarterOrder == 4) && it.fiscalYearStart == latest.fiscalYearStart - 1
        }
        val previousSame = reports.firstOrNull {
            !it.isAnnual && it.fiscalYearStart == latest.fiscalYearStart - 1 &&
                it.quarter.equals(latest.quarter, ignoreCase = true)
        }
        if (previousAnnual?.netProfit != null && previousSame?.netProfit != null) {
            ttmNetProfit = latest.netProfit + previousAnnual.netProfit - previousSame.netProfit
            if (shares != null && shares > 0) ttmEps = ttmNetProfit / shares
            ttmPeriod = "TTM to " + latest.periodLabel
        }
    }

    val eps = latest?.eps ?: annual?.eps ?: num(pick(performance, "eps"))
    val pe = latest?.pe ?: annual?.pe ?: num(pick(performance, "pe")) ?: if (ltp != null && eps != null && eps > 0) ltp / eps else null
    val bookValue = latestBook
    val pb = num(pick(performance, "pb"))
        ?: if (ltp != null && bookValue != null && bookValue > 0) ltp / bookValue else null
    val marketCap = if (ltp != null && shares != null) ltp * shares else null

    val equity = if (bookValue != null && shares != null) bookValue * shares else null
    val reserves = if (equity != null && latestPaidUp != null) equity - latestPaidUp else null

    val annualNetProfit = annual?.netProfit ?: if (latest != null && latest.isAnnual) latest.netProfit else null
    val annualBook = annual?.bookValue
    val annualEquity = if (annualBook != null && shares != null) annualBook * shares else equity
    val roe = if (annualNetProfit != null && annualEquity != null && annualEquity > 0) {
        annualNetProfit / annualEquity * 100.0
    } else if (latest?.netProfit != null && equity != null && equity > 0 && !latest.isAnnual) {
        latest.netProfit / equity * 100.0
    } else null
    val roeLabel = if (annualNetProfit != null && annualEquity != null && annualEquity > 0) {
        "Annual (" + (annual?.periodLabel ?: "latest annual report") + ")"
    } else if (roe != null) {
        "Quarter, not annualized (" + (latest?.periodLabel ?: "") + ")"
    } else {
        "Requires net profit and equity"
    }

    val firstAction = rowsOf(pick(payload, "corporateActions")).firstOrNull()
    val cashPercent = num(pick(performance, "cashDividend"))
        ?: (if (firstAction != null) num(pick(firstAction, "cashDividend")) else null)
        ?: dividendPercentFromNews(payload)
    val bonusPercent = num(pick(performance, "bonusDividend"))
        ?: (if (firstAction != null) num(pick(firstAction, "bonusPercentage")) else null)
    val cashPerShare = if (cashPercent != null) cashPercent / 100.0 * faceValue else null
    val dividendYield = if (cashPerShare != null && ltp != null && ltp > 0) cashPerShare / ltp * 100.0 else null

    // These fields are not published by the connected NEPSE source. They are
    // read from the payload when a richer provider adds them, otherwise N/A.
    val revenue = num(pick(payload, "revenue"))
    val operatingProfit = num(pick(payload, "operatingProfit"))
    val assets = num(pick(payload, "assets"))
    val liabilities = num(pick(payload, "liabilities"))
    val netProfit = latest?.netProfit
    val netMargin = if (netProfit != null && revenue != null && revenue > 0) netProfit / revenue * 100.0 else null

    val fieldStatus = LinkedHashMap<String, String>()
    for (key in keysOf(pick(payload, "fieldStatus"))) {
        fieldStatus[key] = text(pick(pick(payload, "fieldStatus"), key)) ?: ""
    }

    return Fundamentals(
        symbol = text(pick(core, "symbol")) ?: text(pick(company, "symbol")) ?: symbol.uppercase(),
        securityName = text(pick(company, "securityName")) ?: text(pick(profile, "companyName"))
            ?: text(pick(security, "securityName")) ?: NA,
        sector = text(pick(payload, "sector")) ?: text(pick(security, "sector"))
            ?: text(pick(marketSecurity, "sector")) ?: NA,
        ltp = ltp,
        previousClose = previousClose,
        high52 = high52,
        low52 = low52,
        tradingDate = text(pick(mcs, "businessDate")) ?: text(pick(market, "businessDate")) ?: "",
        lastUpdated = text(pick(mcs, "lastUpdatedDateTime")) ?: text(pick(market, "lastUpdatedTime"))
            ?: text(pick(payload, "updatedAt")) ?: "",
        latest = latest,
        annual = annual,
        ttmEps = ttmEps,
        ttmNetProfit = ttmNetProfit,
        ttmPeriod = ttmPeriod,
        reports = reports,
        shares = shares,
        marketCap = marketCap,
        pe = pe,
        pb = pb,
        roe = roe,
        roeLabel = roeLabel,
        dividendPerShare = cashPerShare,
        dividendYield = dividendYield,
        cashDividendPercent = cashPercent,
        bonusPercent = bonusPercent,
        equity = equity,
        reserves = reserves,
        revenue = revenue,
        operatingProfit = operatingProfit,
        assets = assets,
        liabilities = liabilities,
        netProfit = netProfit,
        netMargin = netMargin,
        fieldStatus = fieldStatus,
        sourceNote = "NEPSE company reports via the NEPSE Pulse Render API"
    )
}

private fun sectionCard(title: String, hint: String, rows: List<Pair<String, String>>): HTMLElement {
    val node = card("fund-section")
    node.appendChild(cardHead(title, hint))
    val grid = el("div", "kv-grid")
    for ((label, value) in rows) grid.appendChild(pair(label, value))
    node.appendChild(grid)
    return node
}

/** Full company fundamentals used by the X-Ray Fundamentals tab. */
fun fundamentalsView(symbol: String, payload: dynamic): HTMLElement {
    val root = el("div", "fundamentals")
    val f = parseFundamentals(symbol, payload)
    val period = f.latest?.periodLabel ?: "No financial report published"

    val header = card("fund-head")
    val head = el("div", "fund-head-row")
    val copy = el("div")
    copy.appendChild(el("div", "eyebrow", "COMPANY FUNDAMENTALS"))
    copy.appendChild(el("h2", "fund-name", f.securityName))
    copy.appendChild(el("p", "muted small", f.symbol + " · " + f.sector + " · Latest report: " + period))
    head.appendChild(copy)
    val stamp = el("div", "fund-stamp")
    stamp.appendChild(el("div", "fund-stamp-value", price(f.ltp)))
    stamp.appendChild(el("div", "muted small", "As of " + shortDate(f.tradingDate.ifBlank { f.lastUpdated })))
    head.appendChild(stamp)
    header.appendChild(head)
    root.appendChild(header)

    if (!f.hasAnyReport) {
        root.appendChild(emptyState(
            "No financial reports published",
            "The connected NEPSE source returned no financial reports for " + f.symbol + ". Figures are not estimated or fabricated."
        ))
    }

    root.appendChild(sectionCard(
        "Valuation",
        "Reported per-share figures · " + period,
        listOf(
            "EPS (latest report)" to (numText(f.latest?.eps) + if (f.latest?.eps != null) " · " + (if (f.latest?.isAnnual == true) "annual" else "as reported") else ""),
            "EPS (TTM)" to (numText(f.ttmEps) + if (f.ttmEps != null) " · " + f.ttmPeriod else ""),
            "EPS (latest annual)" to numText(f.annual?.eps),
            "P/E Ratio" to numText(f.pe),
            "Book Value Per Share" to numText(f.latest?.bookValue ?: f.annual?.bookValue),
            "P/B Ratio" to timesText(f.pb),
            "Dividend Yield" to (if (f.dividendYield == null) NA else percentText(f.dividendYield) +
                (if (f.cashDividendPercent != null) " · cash " + numText(f.cashDividendPercent) + "%" else "")),
            "Market Capitalization" to moneyText(f.marketCap)
        )
    ))

    root.appendChild(sectionCard(
        "Profitability",
        "Period figures reported to NEPSE · " + period,
        listOf(
            "Net Profit" to moneyText(f.netProfit),
            "Net Profit (TTM)" to moneyText(f.ttmNetProfit),
            "Net Profit Margin" to percentText(f.netMargin),
            "Return on Equity (ROE)" to (percentText(f.roe) + if (f.roe != null) " · " + f.roeLabel else ""),
            "Return on Assets (ROA)" to NA,
            "Operating Profit" to moneyText(f.operatingProfit),
            "Revenue" to moneyText(f.revenue)
        )
    ))

    root.appendChild(sectionCard(
        "Financial Position",
        "Balance-sheet position derived from reported net worth · " + period,
        listOf(
            "Total Assets" to moneyText(f.assets),
            "Total Liabilities" to moneyText(f.liabilities),
            "Total Equity" to moneyText(f.equity),
            "Reserves and Surplus" to moneyText(f.reserves),
            "Paid-up Capital" to moneyText(f.latest?.paidUpCapital ?: f.annual?.paidUpCapital),
            "Shares Outstanding" to (if (f.shares != null) integer(f.shares) else NA)
        )
    ))

    root.appendChild(sectionCard(
        "Trading and Company Information",
        "Daily market data is shown separately from company financials",
        listOf(
            "Current Market Price" to price(f.ltp),
            "Previous Closing Price" to price(f.previousClose),
            "52-Week High" to price(f.high52),
            "52-Week Low" to price(f.low52),
            "Sector" to f.sector,
            "Security Name" to f.securityName,
            "Symbol" to f.symbol,
            "Trading Date" to shortDate(f.tradingDate),
            "Last Updated" to (shortDate(f.lastUpdated) + " " + clockTime(f.lastUpdated)).trim()
        )
    ))

    if (f.reports.size > 1) {
        val history = card("fund-history")
        history.appendChild(cardHead("Reported periods", "Most recent company filings, newest first"))
        val table = dataTable(listOf("PERIOD", "FISCAL YEAR", "EPS", "P/E", "NET PROFIT", "BOOK VALUE"), setOf(2, 3, 4, 5))
        for (report in f.reports.take(10)) {
            table.row(listOf(
                el("div", "small", report.kind + if (report.quarter.isNotBlank()) " · " + report.quarter else ""),
                el("div", "mono", report.fiscalYear.ifBlank { NA }),
                el("div", "mono", numText(report.eps)),
                el("div", "mono", numText(report.pe)),
                el("div", "mono", moneyText(report.netProfit)),
                el("div", "mono", numText(report.bookValue))
            ))
        }
        history.appendChild(table.root.parentElement as HTMLElement)
        root.appendChild(history)
    }

    val notes = card("fund-notes")
    notes.appendChild(cardHead("About these figures", "What is reported, derived and unavailable"))
    val list = el("ul", "note-list")
    list.appendChild(el("li", "", "Reported values come from NEPSE company filings: EPS, P/E, net profit, paid-up capital and net worth per share. The reporting period is shown next to each section."))
    list.appendChild(el("li", "", "TTM is calculated only when the current year-to-date report, the previous annual report and the same quarter of the previous year are all present. Otherwise it is N/A."))
    list.appendChild(el("li", "", "P/B and dividend yield are calculated from the latest price and reported per-share figures. Market capitalisation is price × shares outstanding (paid-up capital ÷ face value)."))
    list.appendChild(el("li", "", "Equity is net worth per share × shares outstanding; reserves and surplus subtract paid-up capital. Revenue, operating profit, total assets and total liabilities are not published by the connected source, so they show N/A rather than an estimate."))
    list.appendChild(el("li", "", "Source: " + f.sourceNote + ". Last provider update: " + (shortDate(f.lastUpdated) + " " + clockTime(f.lastUpdated)).trim() + "."))
    notes.appendChild(list)
    root.appendChild(notes)
    return root
}

/** Compact valuation strip shown on the X-Ray overview. */
fun fundamentalsSummary(payload: dynamic): HTMLElement {
    val f = parseFundamentals("", payload)
    val node = card("fund-summary")
    node.appendChild(cardHead(
        "Company fundamentals",
        if (f.latest != null) "Latest report · " + f.latest.periodLabel else "No financial report published"
    ))
    val grid = el("div", "kv-grid")
    grid.appendChild(pair("EPS", numText(f.latest?.eps ?: f.annual?.eps)))
    grid.appendChild(pair("P/E", numText(f.pe)))
    grid.appendChild(pair("Book value", numText(f.latest?.bookValue ?: f.annual?.bookValue)))
    grid.appendChild(pair("P/B", timesText(f.pb)))
    grid.appendChild(pair("Dividend yield", percentText(f.dividendYield)))
    grid.appendChild(pair("Market cap", moneyText(f.marketCap)))
    grid.appendChild(pair("Net profit", moneyText(f.netProfit)))
    grid.appendChild(pair("ROE", percentText(f.roe)))
    node.appendChild(grid)
    node.appendChild(el("p", "muted small", "Open the Fundamentals tab for valuation, profitability, financial position and the reported periods. N/A means the connected source does not publish that field."))
    return node
}
