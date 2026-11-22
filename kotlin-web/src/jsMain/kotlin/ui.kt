import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.Node
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- dom helpers

fun el(tag: String, cls: String = "", label: String? = null): HTMLElement {
    val node = document.createElement(tag) as HTMLElement
    if (cls.isNotBlank()) node.className = cls
    if (label != null) node.textContent = label
    return node
}

fun HTMLElement.add(vararg children: Node?): HTMLElement {
    for (child in children) if (child != null) appendChild(child)
    return this
}

fun HTMLElement.attr(name: String, value: String): HTMLElement {
    setAttribute(name, value)
    return this
}

fun HTMLElement.onClick(handler: () -> Unit): HTMLElement {
    addEventListener("click", { handler() })
    return this
}

fun textNode(value: String): HTMLElement = el("span", "", value)

fun clear(node: HTMLElement) {
    node.innerHTML = ""
}

// ---------------------------------------------------------------- icons

private fun iconMarkup(name: String): String = when (name) {
    "dashboard" -> """<rect x="3" y="3" width="7" height="9" rx="1.6"/><rect x="14" y="3" width="7" height="5" rx="1.6"/><rect x="14" y="12" width="7" height="9" rx="1.6"/><rect x="3" y="16" width="7" height="5" rx="1.6"/>"""
    "market" -> """<path d="M3 3v18h18"/><path d="M7 14l3-4 3 2 5-6"/><path d="M18 6h-3M18 6v3"/>"""
    "xray" -> """<circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/><path d="M8 11h6M11 8v6"/>"""
    "screener" -> """<path d="M4 5h16l-6 8v6l-4 2v-8L4 5Z"/>"""
    "quant" -> """<rect x="5" y="5" width="14" height="14" rx="3"/><path d="M9.5 9.5h5v5h-5z"/><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/>"""
    "floorsheet" -> """<path d="M8 6h13M8 12h13M8 18h13"/><circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/>"""
    "brokers" -> """<path d="M4 21V8l8-5 8 5v13"/><path d="M9 21v-6h6v6"/><path d="M4 21h16"/>"""
    "sectors" -> """<rect x="3" y="3" width="8" height="8" rx="2"/><rect x="13" y="3" width="8" height="8" rx="2"/><rect x="3" y="13" width="8" height="8" rx="2"/><rect x="13" y="13" width="8" height="8" rx="2"/>"""
    "structure" -> """<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 13 9 5 9-5"/>"""
    "traders" -> """<path d="M13 2 4 14h7l-1 8 9-12h-7l1-8Z"/>"""
    "portfolio" -> """<path d="M12 3a9 9 0 1 0 9 9h-9V3Z"/><path d="M14 3.5A9 9 0 0 1 20.5 10H14V3.5Z"/>"""
    "watchlist" -> """<path d="m12 3 2.9 6 6.6.9-4.8 4.6 1.2 6.5L12 17.8 6.1 21l1.2-6.5L2.5 9.9 9.1 9 12 3Z"/>"""
    "alerts" -> """<path d="M18 8a6 6 0 0 0-12 0c0 7-3 8-3 8h18s-3-1-3-8"/><path d="M10.3 21a2 2 0 0 0 3.4 0"/>"""
    "corporate" -> """<rect x="3" y="4" width="18" height="17" rx="2.5"/><path d="M8 2v4M16 2v4M3 10h18"/><path d="M8 15h3"/>"""
    "ipo" -> """<path d="M4.6 16.4c-1.4 1.3-2 5.1-2 5.1s3.8-.6 5.1-2c.8-.8.8-2 0-2.8-.8-.8-2-.8-3.1-.3Z"/><path d="M12 15 9 12c.6-3.1 2.1-6 5.1-8.1C16.1 2.6 19 2 21 2c0 2-.6 4.9-1.9 6.9C17 12 14.1 13.6 11 14.2"/><path d="M9 12H5.5L9 8.5M12 15v3.5l3.5-3.5"/>"""
    "ai" -> """<path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/><circle cx="12" cy="12" r="3.2"/>"""
    "profile" -> """<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7"/>"""
    "sun" -> """<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/>"""
    "moon" -> """<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>"""
    "refresh" -> """<path d="M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6"/>"""
    "search" -> """<circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/>"""
    "close" -> """<path d="M18 6 6 18M6 6l12 12"/>"""
    "plus" -> """<path d="M12 5v14M5 12h14"/>"""
    "trash" -> """<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>"""
    "arrowUp" -> """<path d="M12 19V5M5 12l7-7 7 7"/>"""
    "arrowDown" -> """<path d="M12 5v14M19 12l-7 7-7-7"/>"""
    "external" -> """<path d="M14 3h7v7M21 3l-9 9M19 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5"/>"""
    "check" -> """<path d="m20 6-11 11-5-5"/>"""
    "info" -> """<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>"""
    "menu" -> """<path d="M4 7h16M4 12h16M4 17h16"/>"""
    "logout" -> """<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>"""
    "phone" -> """<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.4 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2Z"/>"""
    "mail" -> """<rect x="2" y="4" width="20" height="16" rx="2.5"/><path d="m2 7 10 6 10-6"/>"""
    "lock" -> """<rect x="4" y="10" width="16" height="11" rx="2.5"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>"""
    "shield" -> """<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>"""
    "chart" -> """<path d="M3 3v18h18"/><path d="M7 15l3-4 3 2 5-7"/>"""
    "clock" -> """<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>"""
    "users" -> """<circle cx="9" cy="8" r="3"/><path d="M2 21c0-3.5 3-6 7-6s7 2.5 7 6"/><path d="M17 5a3 3 0 0 1 0 6M22 21c0-2.8-1.7-5-4-5.7"/>"""
    "layers" -> """<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 13 9 5 9-5M3 17l9 5 9-5"/>"""
    "download" -> """<path d="M12 3v12M7 10l5 5 5-5M4 21h16"/>"""
    "filter" -> """<path d="M3 5h18l-7 8v6l-4 2v-8L3 5Z"/>"""
    "spark" -> """<path d="M12 3v4M12 17v4M3 12h4M17 12h4"/><circle cx="12" cy="12" r="2.5"/>"""
    "news" -> """<path d="M4 5h13v14H6a2 2 0 0 1-2-2V5Z"/><path d="M17 9h3v8a2 2 0 0 1-2 2M8 9h5M8 13h5"/>"""
    else -> """<circle cx="12" cy="12" r="8"/>"""
}

fun icon(name: String, size: Int = 18): HTMLElement {
    val span = el("span", "icon")
    span.innerHTML = """<svg viewBox="0 0 24 24" width="$size" height="$size" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${iconMarkup(name)}</svg>"""
    return span
}

// ---------------------------------------------------------------- theme

object Theme {
    var current: String = "dark"
        private set

    fun init() {
        val saved = Store.get("theme")
        current = if (saved == "light" || saved == "dark") saved else {
            val prefersLight = window.asDynamic().matchMedia("(prefers-color-scheme: light)").matches
            if (prefersLight == true) "light" else "dark"
        }
        apply()
    }

    fun apply() {
        document.documentElement?.setAttribute("data-theme", current)
    }

    fun toggle(): String {
        current = if (current == "dark") "light" else "dark"
        Store.put("theme", current)
        apply()
        return current
    }
}

// ---------------------------------------------------------------- primitives

fun badge(label: String, tone: String = "neutral"): HTMLElement =
    el("span", "badge badge-$tone", label)

fun actionButton(
    label: String,
    variant: String = "ghost",
    iconName: String? = null,
    handler: () -> Unit
): HTMLElement {
    val button = el("button", "btn btn-$variant")
    if (iconName != null) button.appendChild(icon(iconName, 15))
    button.appendChild(el("span", "", label))
    button.setAttribute("type", "button")
    button.onClick(handler)
    return button
}

fun iconButton(name: String, title: String, handler: () -> Unit): HTMLElement {
    val button = el("button", "icon-btn")
    button.appendChild(icon(name, 17))
    button.setAttribute("title", title)
    button.setAttribute("aria-label", title)
    button.onClick(handler)
    return button
}

fun field(placeholder: String, type: String = "text", value: String = ""): HTMLInputElement {
    val input = document.createElement("input") as HTMLInputElement
    input.type = type
    input.placeholder = placeholder
    input.value = value
    input.className = "field"
    return input
}

fun selectField(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit): HTMLSelectElement {
    val select = document.createElement("select") as HTMLSelectElement
    select.className = "field select"
    for ((key, label) in options) {
        val option = document.createElement("option") as HTMLElement
        option.setAttribute("value", key)
        option.textContent = label
        select.appendChild(option)
    }
    select.value = value
    select.addEventListener("change", { onChange(select.value) })
    return select
}

fun chipButton(label: String, active: Boolean, handler: () -> Unit): HTMLElement {
    val chip = el("button", if (active) "chip chip-active" else "chip", label)
    chip.onClick(handler)
    return chip
}

fun segmented(options: List<Pair<String, String>>, active: String, onChange: (String) -> Unit): HTMLElement {
    val wrap = el("div", "segmented")
    for ((key, label) in options) {
        wrap.appendChild(chipButton(label, key == active) { onChange(key) })
    }
    return wrap
}

// ---------------------------------------------------------------- layout components

fun pageHead(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    actions: List<HTMLElement> = emptyList()
): HTMLElement {
    val head = el("div", "page-head")
    val copy = el("div", "page-head-copy")
    copy.appendChild(el("div", "eyebrow", eyebrow))
    copy.appendChild(el("h1", "page-title", title))
    if (subtitle != null) copy.appendChild(el("p", "page-sub", subtitle))
    head.appendChild(copy)
    if (actions.isNotEmpty()) {
        val actionWrap = el("div", "page-actions")
        for (action in actions) actionWrap.appendChild(action)
        head.appendChild(actionWrap)
    }
    return head
}

fun card(cls: String = ""): HTMLElement = el("section", ("card " + cls).trim())

fun cardHead(title: String, hint: String? = null, trailing: HTMLElement? = null): HTMLElement {
    val head = el("div", "card-head")
    val copy = el("div", "card-head-copy")
    copy.appendChild(el("h2", "card-title", title))
    if (hint != null) copy.appendChild(el("p", "card-hint", hint))
    head.appendChild(copy)
    if (trailing != null) head.appendChild(trailing)
    return head
}

fun skeleton(height: Int = 110): HTMLElement {
    val box = el("div", "skeleton")
    box.style.height = height.toString() + "px"
    return box
}

fun loadingState(message: String = "Loading market data…"): HTMLElement {
    val wrap = el("div", "state-panel")
    val spinner = el("div", "spinner")
    wrap.appendChild(spinner)
    wrap.appendChild(el("p", "state-title", message))
    wrap.appendChild(el("p", "state-copy", "Connecting to the NEPSE Pulse data engine on Render."))
    return wrap
}

fun emptyState(title: String, message: String, actionLabel: String? = null, handler: (() -> Unit)? = null): HTMLElement {
    val wrap = el("div", "state-panel")
    wrap.appendChild(icon("info", 26))
    wrap.appendChild(el("p", "state-title", title))
    wrap.appendChild(el("p", "state-copy", message))
    if (actionLabel != null && handler != null) wrap.appendChild(actionButton(actionLabel, "secondary", "refresh", handler))
    return wrap
}

fun errorState(message: String, retry: () -> Unit): HTMLElement {
    val wrap = el("div", "state-panel state-error")
    wrap.appendChild(icon("info", 26))
    wrap.appendChild(el("p", "state-title", "Market data is unavailable"))
    wrap.appendChild(el("p", "state-copy", message))
    wrap.appendChild(actionButton("Try again", "secondary", "refresh", retry))
    return wrap
}

fun progressBar(value: Double, tone: String = "accent"): HTMLElement {
    val track = el("div", "progress")
    val fill = el("div", "progress-fill progress-$tone")
    fill.style.width = value.coerceIn(0.0, 1.0).times(100.0).roundToInt().toString() + "%"
    track.appendChild(fill)
    return track
}

fun statTile(
    label: String,
    value: String,
    detail: String? = null,
    tone: String = "",
    spark: List<Double>? = null
): HTMLElement {
    val tile = el("article", "stat")
    tile.appendChild(el("div", "stat-label", label))
    val row = el("div", "stat-row")
    row.appendChild(el("div", "stat-value " + tone, value))
    if (spark != null && spark.size > 2) {
        val mini = el("div", "spark")
        mini.innerHTML = sparklineSvg(spark, tone)
        row.appendChild(mini)
    }
    tile.appendChild(row)
    if (detail != null) tile.appendChild(el("div", "stat-detail", detail))
    return tile
}

fun breadthBar(up: Int, down: Int, flat: Int): HTMLElement {
    val wrap = el("div", "breadth")
    val total = max(1, up + down + flat)
    val bar = el("div", "breadth-bar")
    val upBar = el("div", "breadth-up")
    upBar.style.width = (up.toDouble() / total * 100.0).roundToInt().toString() + "%"
    val flatBar = el("div", "breadth-flat")
    flatBar.style.width = (flat.toDouble() / total * 100.0).roundToInt().toString() + "%"
    val downBar = el("div", "breadth-down")
    downBar.style.width = (down.toDouble() / total * 100.0).roundToInt().toString() + "%"
    bar.add(upBar, flatBar, downBar)
    wrap.appendChild(bar)
    val legend = el("div", "breadth-legend")
    legend.add(
        el("span", "up", "$up advancing"),
        el("span", "flat", "$flat unchanged"),
        el("span", "down", "$down declining")
    )
    wrap.appendChild(legend)
    return wrap
}

// ---------------------------------------------------------------- tables

fun ctx2d(canvas: HTMLCanvasElement): dynamic = js("(function(c){return c.getContext('2d')})")(canvas)

fun eventKey(event: org.w3c.dom.events.Event): String = js("(function(e){return (e && e.key) ? String(e.key) : ''})")(event)

class DataTable(val root: HTMLElement, private val numeric: Set<Int>) {
    private val head = el("div", "tr th")
    private val body = el("div", "tbody")

    init {
        root.appendChild(head)
        root.appendChild(body)
    }

    fun headers(labels: List<String>) {
        head.innerHTML = ""
        labels.forEachIndexed { index, label ->
            head.appendChild(el("div", if (index in numeric) "td num" else "td", label))
        }
    }

    fun row(cells: List<HTMLElement>, className: String = "", onClick: (() -> Unit)? = null): HTMLElement {
        val rowNode = el("div", "tr " + className)
        cells.forEachIndexed { index, cell ->
            if (!cell.classList.contains("td")) cell.classList.add("td")
            if (index in numeric) cell.classList.add("num")
            rowNode.appendChild(cell)
        }
        if (onClick != null) {
            rowNode.classList.add("clickable")
            rowNode.setAttribute("role", "button")
            rowNode.setAttribute("tabindex", "0")
            rowNode.onClick(onClick)
        }
        body.appendChild(rowNode)
        return rowNode
    }

    fun empty(message: String) {
        body.appendChild(el("div", "table-empty", message))
    }
}

fun dataTable(headers: List<String>, numeric: Set<Int> = setOf(1, 2, 3, 4)): DataTable {
    val wrap = el("div", "table-wrap")
    val root = el("div", "table")
    wrap.appendChild(root)
    val table = DataTable(root, numeric)
    table.headers(headers)
    return table
}

fun pair(label: String, value: String): HTMLElement {
    val row = el("div", "pair")
    row.appendChild(el("span", "pair-label", label))
    row.appendChild(el("span", "pair-value", value))
    return row
}

fun toneClass(value: Double?): String = when (toneOf(value)) {
    "up" -> "up"
    "down" -> "down"
    else -> "flat"
}

// ---------------------------------------------------------------- sparkline

fun sparklineSvg(values: List<Double>, tone: String = ""): String {
    if (values.size < 2) return ""
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 0.0
    val span = if (maxValue - minValue == 0.0) 1.0 else maxValue - minValue
    val width = 92.0
    val height = 28.0
    val points = values.mapIndexed { index, value ->
        val x = index.toDouble() / (values.size - 1) * width
        val y = height - (value - minValue) / span * (height - 4) - 2
        fixed(x, 2) + "," + fixed(y, 2)
    }
    val stroke = when (tone) {
        "up", "positive" -> "#22c58b"
        "down", "negative" -> "#f4566d"
        else -> "#4f9cf9"
    }
    return """<svg viewBox="0 0 92 28" width="92" height="28" fill="none" preserveAspectRatio="none"><polyline points="${points.joinToString(" ")}" stroke="$stroke" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round"/></svg>"""
}

// ---------------------------------------------------------------- charts

private fun cssVar(name: String, fallback: String): String {
    val value = window.asDynamic().getComputedStyle(document.documentElement).getPropertyValue(name)
    val text = if (value == null || value == undefined) "" else value.toString().trim()
    return if (text.isEmpty()) fallback else text
}

private val chartDateFactory: dynamic = js("(function(t){return new Date(t)})")

fun chartTime(ms: Double, intraday: Boolean): String {
    val date = chartDateFactory(ms)
    return if (intraday) {
        date.toLocaleTimeString("en-GB", js("({hour:'2-digit',minute:'2-digit'})")) as String
    } else {
        date.toLocaleDateString("en-GB", js("({day:'2-digit',month:'short'})")) as String
    }
}

fun chartDay(ms: Double): String {
    val date = chartDateFactory(ms)
    return date.toLocaleDateString("en-GB", js("({day:'2-digit',month:'short',year:'numeric'})")) as String
}

private fun drawGrid(ctx: dynamic, left: Double, top: Double, width: Double, height: Double, minValue: Double, maxValue: Double, color: String, font: String): List<Double> {
    val steps = 4
    val span = max(maxValue - minValue, 0.0001)
    ctx.strokeStyle = color
    ctx.fillStyle = color
    ctx.font = font
    ctx.lineWidth = 1
    val levels = ArrayList<Double>()
    for (i in 0..steps) {
        val y = top + height - height * i / steps
        val value = minValue + span * i / steps
        ctx.beginPath()
        ctx.moveTo(left, y)
        ctx.lineTo(left + width, y)
        ctx.stroke()
        ctx.fillText(price(value), left + width + 8, y + 3.5)
        levels.add(value)
    }
    return levels
}

private fun mountCanvas(host: HTMLElement, height: Int): HTMLCanvasElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.className = "chart-canvas"
    canvas.style.width = "100%"
    canvas.style.height = height.toString() + "px"
    host.appendChild(canvas)
    return canvas
}

private fun sizeCanvas(canvas: HTMLCanvasElement, height: Int): dynamic {
    val width = max((canvas.parentElement as? HTMLElement)?.clientWidth ?: 600, 280).toDouble()
    val ratio = window.asDynamic().devicePixelRatio ?: 1.0
    val dpr = if (ratio == null || ratio == undefined) 1.0 else ratio.toString().toDoubleOrNull() ?: 1.0
    canvas.width = (width * dpr).toInt()
    canvas.height = (height * dpr).toInt()
    val ctx = ctx2d(canvas)
    ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
    ctx.clearRect(0.0, 0.0, width, height.toDouble())
    return ctx
}

fun candleChart(bars: List<Bar>, height: Int = 330, intraday: Boolean = false): HTMLElement {
    val wrap = el("div", "chart")
    val canvas = mountCanvas(wrap, height)
    val tooltip = el("div", "chart-tip")
    tooltip.style.display = "none"
    wrap.appendChild(tooltip)
    var hoverIndex = -1

    fun draw() {
        if (bars.isEmpty()) return
        val ctx = sizeCanvas(canvas, height)
        val width = ((canvas.parentElement as? HTMLElement)?.clientWidth ?: 600).toDouble()
        val left = 6.0
        val right = 62.0
        val top = 10.0
        val bottom = 24.0
        val volumeHeight = height * 0.18
        val plotWidth = max(width - left - right, 50.0)
        val plotHeight = height - top - bottom - volumeHeight - 8.0
        val visible = bars.takeLast(min(bars.size, 120))
        val minLow = visible.minOf { it.low }
        val maxHigh = visible.maxOf { it.high }
        val pad = max((maxHigh - minLow) * 0.06, 0.01)
        val minValue = minLow - pad
        val maxValue = maxHigh + pad
        val gridColor = cssVar("--grid", "rgba(148,163,184,.16)")
        val axisColor = cssVar("--muted", "#8ba0b8")
        val upColor = cssVar("--up", "#22c58b")
        val downColor = cssVar("--down", "#f4566d")
        val font = "10px Inter, system-ui, sans-serif"
        drawGrid(ctx, left, top, plotWidth, plotHeight, minValue, maxValue, gridColor, font)
        val slot = plotWidth / visible.size
        val bodyWidth = max(slot * 0.62, 1.0)
        for (i in visible.indices) {
            val bar = visible[i]
            val x = left + slot * i + slot / 2
            val yHigh = top + (maxValue - bar.high) / (maxValue - minValue) * plotHeight
            val yLow = top + (maxValue - bar.low) / (maxValue - minValue) * plotHeight
            val yOpen = top + (maxValue - bar.open) / (maxValue - minValue) * plotHeight
            val yClose = top + (maxValue - bar.close) / (maxValue - minValue) * plotHeight
            val rising = bar.close >= bar.open
            ctx.strokeStyle = if (rising) upColor else downColor
            ctx.fillStyle = ctx.strokeStyle
            ctx.lineWidth = 1
            ctx.beginPath()
            ctx.moveTo(x, yHigh)
            ctx.lineTo(x, yLow)
            ctx.stroke()
            val bodyTop = min(yOpen, yClose)
            val bodyHeight = max(abs(yClose - yOpen), 1.0)
            ctx.fillRect(x - bodyWidth / 2, bodyTop, bodyWidth, bodyHeight)
        }
        val volumeTop = top + plotHeight + 12
        val maxVolume = visible.maxOf { it.volume }.coerceAtLeast(1.0)
        for (i in visible.indices) {
            val bar = visible[i]
            val x = left + slot * i
            val h = bar.volume / maxVolume * volumeHeight
            ctx.fillStyle = if (bar.close >= bar.open) upColor else downColor
            ctx.globalAlpha = 0.35
            ctx.fillRect(x + slot * 0.18, volumeTop + volumeHeight - h, max(slot * 0.64, 1.0), h)
            ctx.globalAlpha = 1.0
        }
        val last = visible.last()
        val lastY = top + (maxValue - last.close) / (maxValue - minValue) * plotHeight
        val lineColor = if (last.close >= last.open) upColor else downColor
        ctx.strokeStyle = lineColor
        ctx.globalAlpha = 0.75
        ctx.setLineDash(js("[4,4]"))
        ctx.beginPath()
        ctx.moveTo(left, lastY)
        ctx.lineTo(left + plotWidth, lastY)
        ctx.stroke()
        ctx.setLineDash(js("[]"))
        ctx.globalAlpha = 1.0
        ctx.fillStyle = lineColor
        val label = price(last.close)
        ctx.fillRect(left + plotWidth + 2, lastY - 8, right - 6, 16)
        ctx.fillStyle = cssVar("--on-accent", "#04121f")
        ctx.font = "bold 10px Inter, system-ui, sans-serif"
        ctx.fillText(label, left + plotWidth + 6, lastY + 3.5)
        ctx.fillStyle = axisColor
        ctx.font = font
        val ticks = 4
        for (i in 0..ticks) {
            val index = ((visible.size - 1).toDouble() * i / ticks).roundToInt().coerceIn(0, visible.size - 1)
            val x = left + slot * index + slot / 2
            val label = chartTime(visible[index].time, intraday)
            val w = ctx.measureText(label).width as Double
            ctx.fillText(label, min(max(x - w / 2, 0.0), width - right - w), height - 8.0)
        }
        if (hoverIndex in visible.indices) {
            val bar = visible[hoverIndex]
            val x = left + slot * hoverIndex + slot / 2
            ctx.strokeStyle = axisColor
            ctx.globalAlpha = 0.5
            ctx.beginPath()
            ctx.moveTo(x, top)
            ctx.lineTo(x, volumeTop + volumeHeight)
            ctx.stroke()
            ctx.globalAlpha = 1.0
        }
        val onMove = { event: dynamic ->
            val rect = canvas.getBoundingClientRect()
            val px = (event.clientX.toString().toDouble() - rect.left.toString().toDouble())
            val index = ((px - left) / slot).toInt()
            if (index in visible.indices) {
                hoverIndex = index
                val bar = visible[index]
                tooltip.style.display = "block"
                tooltip.innerHTML = "<strong>${chartDay(bar.time)}</strong>" +
                    "<span>O ${price(bar.open)}</span><span>H ${price(bar.high)}</span>" +
                    "<span>L ${price(bar.low)}</span><span>C ${price(bar.close)}</span>" +
                    "<span>V ${integer(bar.volume)}</span>"
                val tipX = min(max(px + 12, 8.0), width - 150.0)
                tooltip.style.left = tipX.toString() + "px"
                tooltip.style.top = "12px"
                draw()
            } else {
                tooltip.style.display = "none"
            }
            null
        }
        val canvasDynamic = canvas.asDynamic()
        canvasDynamic.onmousemove = onMove
        canvasDynamic.onmouseleave = {
            hoverIndex = -1
            tooltip.style.display = "none"
            draw()
            null
        }
    }

    window.setTimeout({ draw() }, 30)
    var lastWidth = 0.0
    window.setInterval({
        val width = ((canvas.parentElement as? HTMLElement)?.clientWidth ?: 0).toDouble()
        if (abs(width - lastWidth) > 8.0 && width > 0.0) {
            lastWidth = width
            draw()
        }
    }, 700)
    return wrap
}

fun areaChart(bars: List<Bar>, height: Int = 220, intraday: Boolean = true): HTMLElement {
    val wrap = el("div", "chart")
    val canvas = mountCanvas(wrap, height)

    fun draw() {
        if (bars.size < 2) return
        val ctx = sizeCanvas(canvas, height)
        val width = ((canvas.parentElement as? HTMLElement)?.clientWidth ?: 600).toDouble()
        val left = 6.0
        val right = 56.0
        val top = 12.0
        val bottom = 22.0
        val plotWidth = max(width - left - right, 50.0)
        val plotHeight = height - top - bottom
        val visible = bars.takeLast(min(bars.size, 160))
        val minValue = visible.minOf { it.close }
        val maxValue = visible.maxOf { it.close }
        val pad = max((maxValue - minValue) * 0.12, 0.01)
        val low = minValue - pad
        val high = maxValue + pad
        val gridColor = cssVar("--grid", "rgba(148,163,184,.16)")
        val axisColor = cssVar("--muted", "#8ba0b8")
        val rising = visible.last().close >= visible.first().close
        val accent = if (rising) cssVar("--up", "#22c58b") else cssVar("--down", "#f4566d")
        drawGrid(ctx, left, top, plotWidth, plotHeight, low, high, gridColor, "10px Inter, system-ui, sans-serif")
        ctx.beginPath()
        visible.forEachIndexed { index, bar ->
            val x = left + plotWidth * index / (visible.size - 1)
            val y = top + (high - bar.close) / (high - low) * plotHeight
            if (index == 0) ctx.moveTo(x, y) else ctx.lineTo(x, y)
        }
        val gradient = ctx.createLinearGradient(0.0, top, 0.0, top + plotHeight)
        gradient.addColorStop(0.0, accent + "55")
        gradient.addColorStop(1.0, accent + "00")
        ctx.strokeStyle = accent
        ctx.lineWidth = 2
        ctx.stroke()
        ctx.lineTo(left + plotWidth, top + plotHeight)
        ctx.lineTo(left, top + plotHeight)
        ctx.closePath()
        ctx.fillStyle = gradient
        ctx.fill()
        ctx.fillStyle = axisColor
        ctx.font = "10px Inter, system-ui, sans-serif"
        val ticks = 3
        for (i in 0..ticks) {
            val index = ((visible.size - 1).toDouble() * i / ticks).roundToInt().coerceIn(0, visible.size - 1)
            val x = left + plotWidth * index / (visible.size - 1)
            val label = chartTime(visible[index].time, intraday)
            val w = ctx.measureText(label).width as Double
            ctx.fillText(label, min(max(x - w / 2, 0.0), width - right - w), height - 6.0)
        }
    }

    window.setTimeout({ draw() }, 30)
    window.setInterval({
        if (((canvas.parentElement as? HTMLElement)?.clientWidth ?: 0) > 0) draw()
    }, 4000)
    return wrap
}

// ---------------------------------------------------------------- toast and modal

private fun toastHost(): HTMLElement {
    val existing = document.getElementById("np-toasts") as? HTMLElement
    if (existing != null) return existing
    val host = el("div", "toast-host")
    host.id = "np-toasts"
    document.body?.appendChild(host)
    return host
}

fun toast(message: String, tone: String = "info") {
    if (message.isBlank()) return
    val host = toastHost()
    val item = el("div", "toast toast-$tone")
    item.appendChild(icon(if (tone == "error") "info" else "check", 15))
    item.appendChild(el("span", "", message))
    host.appendChild(item)
    window.setTimeout({
        item.classList.add("toast-out")
        window.setTimeout({ item.remove() }, 320)
        null
    }, 3600)
}

object UIBridge {
    var notify: ((String, String) -> Unit)? = null
}

fun installBridge() {
    UIBridge.notify = { message, tone -> toast(message, tone) }
}

fun modal(title: String, build: (HTMLElement) -> Unit): HTMLElement {
    val overlay = el("div", "modal-overlay")
    val sheet = el("div", "modal")
    val head = el("div", "modal-head")
    head.appendChild(el("h2", "", title))
    head.appendChild(iconButton("close", "Close") { overlay.remove() })
    sheet.appendChild(head)
    val body = el("div", "modal-body")
    build(body)
    sheet.appendChild(body)
    overlay.appendChild(sheet)
    overlay.addEventListener("click", { event -> if (event.target == overlay) overlay.remove() })
    document.body?.appendChild(overlay)
    return overlay
}

fun confirmModal(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) {
    val overlay = modal(title) { body ->
        body.appendChild(el("p", "modal-copy", message))
        val actions = el("div", "modal-actions")
        actions.appendChild(actionButton("Cancel", "ghost") {
            (body.parentElement?.parentElement as? HTMLElement)?.remove()
        })
        actions.appendChild(actionButton(confirmLabel, "danger") {
            (body.parentElement?.parentElement as? HTMLElement)?.remove()
            onConfirm()
        })
        body.appendChild(actions)
    }
    overlay.setAttribute("data-confirm", title)
}

fun externalLink(label: String, href: String): HTMLElement {
    val link = el("a", "link", label)
    link.setAttribute("href", href)
    link.setAttribute("target", "_blank")
    link.setAttribute("rel", "noopener")
    return link
}
