import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Redraw registry: one shared interval in main() keeps every mounted chart in
// sync with layout changes without leaking one timer per screen.
object NpChartRegistry {
    private val drawers = ArrayList<() -> Unit>()

    fun register(drawer: () -> Unit) {
        drawers.add(drawer)
    }

    fun reset() {
        drawers.clear()
    }

    fun tick() {
        for (drawer in drawers.toList()) {
            try {
                drawer()
            } catch (_: Throwable) {
            }
        }
    }
}

private fun chartCss(name: String, fallback: String): String {
    val computed = window.asDynamic().getComputedStyle(document.documentElement)
    val raw = computed.getPropertyValue(name)
    val value = if (raw == null || raw == undefined) "" else raw.toString().trim()
    return if (value.isEmpty()) fallback else value
}

private fun chartCanvasCtx(canvas: HTMLCanvasElement, width: Double, height: Int): dynamic {
    val ratio = window.asDynamic().devicePixelRatio ?: 1.0
    val dpr = ratio.toString().toDoubleOrNull() ?: 1.0
    canvas.width = (width * dpr).toInt()
    canvas.height = (height * dpr).toInt()
    val ctx = ctx2d(canvas)
    ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
    ctx.clearRect(0.0, 0.0, width, height.toDouble())
    return ctx
}

/**
 * Interactive candlestick chart with volume, EMA/SMA overlays, Bollinger bands,
 * VWAP, support/resistance, RSI and MACD panes. Supports wheel zoom, drag pan,
 * a crosshair readout and a reveal limit used by chart replay.
 */
class NpKChart(
    private val wrap: HTMLElement,
    private val height: Int = 380,
    private val intraday: Boolean = false
) {
    private val canvas = document.createElement("canvas") as HTMLCanvasElement
    private val legend = el("div", "kchart-legend")
    private val ohlcLine = el("div", "kchart-ohlc")
    private val tip = el("div", "chart-tip")
    private val note = el("div", "kchart-note")

    var symbol: String = ""
    var rangeLabel: String = ""
    var intervalLabel: String = "D"

    private var bars: List<Bar> = emptyList()
    private var indicatorSet: Set<String> = linkedSetOf("EMA20", "VOL")
    private var viewCount = 0
    private var viewEnd = 0
    private var hoverIndex = -1
    private var hoverX = 0.0
    private var dragging = false
    private var dragStartX = 0.0
    private var dragStartEnd = 0
    private var lastWidth = 0.0
    private var destroyed = false
    private var revealLimit = -1

    init {
        canvas.className = "chart-canvas kchart-canvas"
        canvas.style.height = height.toString() + "px"
        canvas.style.width = "100%"
        wrap.appendChild(legend)
        wrap.appendChild(canvas)
        wrap.appendChild(ohlcLine)
        wrap.appendChild(tip)
        wrap.appendChild(note)
        tip.style.display = "none"
        note.style.display = "none"
        wireEvents()
        NpChartRegistry.register {
            if (!destroyed) {
                val width = wrap.clientWidth.toDouble()
                if (width > 0.0 && abs(width - lastWidth) > 6.0) {
                    lastWidth = width
                    draw()
                }
            }
        }
        window.setTimeout({ draw() }, 40)
    }

    fun setData(list: List<Bar>, reset: Boolean = true) {
        bars = list
        if (reset || viewCount <= 0) {
            viewCount = min(bars.size, if (bars.size > 260) 140 else max(bars.size, 1))
            viewEnd = bars.size
        } else {
            viewEnd = min(viewEnd, bars.size)
            viewCount = min(viewCount, bars.size)
        }
        note.style.display = "none"
        draw()
    }

    fun setIndicators(next: Set<String>) {
        indicatorSet = next
        draw()
    }

    /** Reveal only the first [count] bars; -1 shows every bar. */
    fun setReveal(count: Int) {
        revealLimit = count
        val limit = if (count < 0) bars.size else count.coerceIn(0, bars.size)
        viewEnd = limit
        viewCount = min(max(limit, 0), if (limit > 260) 140 else max(limit, 1))
        draw()
    }

    fun resetView() {
        viewEnd = if (revealLimit >= 0) min(revealLimit, bars.size) else bars.size
        viewCount = min(max(viewEnd, 1), if (bars.size > 260) 140 else max(bars.size, 1))
        draw()
    }

    fun zoom(factor: Double) {
        val total = if (revealLimit >= 0) min(revealLimit, bars.size) else bars.size
        if (total <= 0) return
        val next = (viewCount / factor).roundToInt().coerceIn(20, total)
        viewCount = next
        draw()
    }

    fun destroy() {
        destroyed = true
    }

    private fun wireEvents() {
        val node = canvas.asDynamic()
        node.onmousemove = { event: dynamic ->
            if (bars.isEmpty()) {
                null
            } else {
                val bounds = canvas.getBoundingClientRect()
                val px = event.clientX.toString().toDouble() - bounds.left.toString().toDouble()
                hoverX = px
                if (dragging) {
                    val shift = ((px - dragStartX) / max(canvas.clientWidth.toDouble() / max(viewCount, 1), 1.0)).toInt()
                    val total = if (revealLimit >= 0) min(revealLimit, bars.size) else bars.size
                    viewEnd = (dragStartEnd - shift).coerceIn(min(viewCount, total), total)
                } else {
                    hoverIndex = indexAt(px)
                }
                draw()
                null
            }
        }
        node.onmouseleave = {
            hoverIndex = -1
            dragging = false
            tip.style.display = "none"
            draw()
            null
        }
        node.onmousedown = { event: dynamic ->
            dragging = true
            dragStartX = event.clientX.toString().toDouble()
            dragStartEnd = viewEnd
            null
        }
        node.onmouseup = {
            dragging = false
            null
        }
        node.onwheel = { event: dynamic ->
            if (bars.isNotEmpty()) {
                event.preventDefault()
                val delta = event.deltaY.toString().toDoubleOrNull() ?: 0.0
                val total = if (revealLimit >= 0) min(revealLimit, bars.size) else bars.size
                val next = (viewCount * (if (delta > 0) 1.15 else 0.87)).roundToInt().coerceIn(20, max(total, 20))
                viewCount = next
                draw()
            }
            null
        }
        var touchStartX = 0.0
        var touchStartEnd = 0
        node.ontouchstart = { event: dynamic ->
            val touches = event.touches
            if (touches != null && touches.length > 0) {
                touchStartX = touches[0].clientX.toString().toDouble()
                touchStartEnd = viewEnd
            }
            null
        }
        node.ontouchmove = { event: dynamic ->
            val touches = event.touches
            if (touches != null && touches.length > 0) {
                event.preventDefault()
                val px = touches[0].clientX.toString().toDouble()
                val shift = ((touchStartX - px) / max(canvas.clientWidth.toDouble() / max(viewCount, 1), 1.0)).toInt()
                val total = if (revealLimit >= 0) min(revealLimit, bars.size) else bars.size
                viewEnd = (touchStartEnd + shift).coerceIn(min(viewCount, total), total)
                hoverIndex = -1
                draw()
            }
            null
        }
    }

    private fun indexAt(px: Double): Int {
        val width = wrap.clientWidth.toDouble()
        val plotLeft = 8.0
        val plotRight = width - 64.0
        if (px < plotLeft || px > plotRight || viewCount <= 0) return -1
        val slot = (plotRight - plotLeft) / viewCount
        val visible = visibleBars()
        val index = ((px - plotLeft) / max(slot, 1.0)).toInt()
        return if (index in visible.indices) index else -1
    }

    private fun visibleBars(): List<Bar> {
        if (bars.isEmpty()) return emptyList()
        val end = viewEnd.coerceIn(0, bars.size)
        val start = max(0, end - viewCount)
        return if (end <= start) emptyList() else bars.subList(start, end)
    }

    private fun visibleStart(): Int {
        val end = viewEnd.coerceIn(0, bars.size)
        return max(0, end - viewCount)
    }

    private fun updateReadout(bar: Bar?, index: Int) {
        legend.textContent = ""
        val parts = ArrayList<String>()
        if (symbol.isNotBlank()) parts.add(symbol)
        if (rangeLabel.isNotBlank()) parts.add(rangeLabel)
        parts.add(intervalLabel)
        legend.textContent = parts.joinToString(" · ")
        if (bar == null) {
            ohlcLine.textContent = ""
            return
        }
        val previous = if (index > 0) bars.getOrNull(index - 1)?.close else null
        val change = if (previous != null && previous != 0.0) (bar.close - previous) / previous * 100.0 else null
        ohlcLine.textContent = ""
        ohlcLine.appendChild(el("span", "kchart-ohlc-item", "O " + price(bar.open)))
        ohlcLine.appendChild(el("span", "kchart-ohlc-item", "H " + price(bar.high)))
        ohlcLine.appendChild(el("span", "kchart-ohlc-item", "L " + price(bar.low)))
        ohlcLine.appendChild(el("span", "kchart-ohlc-item", "C " + price(bar.close)))
        ohlcLine.appendChild(el("span", "kchart-ohlc-item tone-" + toneOf(change), percent(change)))
        ohlcLine.appendChild(el("span", "kchart-ohlc-item", "V " + integer(bar.volume)))
    }

    private fun draw() {
        if (destroyed) return
        val width = wrap.clientWidth.toDouble()
        if (width < 20.0) return
        lastWidth = width
        val ctx = chartCanvasCtx(canvas, width, height)
        val visible = visibleBars()
        if (visible.isEmpty()) {
            updateReadout(null, -1)
            tip.style.display = "none"
            note.style.display = "block"
            note.textContent = if (bars.isEmpty()) "No price series loaded." else "Not enough candles for this view."
            return
        }
        note.style.display = "none"

        val gridColor = chartCss("--grid", "rgba(148,163,184,.16)")
        val axisColor = chartCss("--muted", "#8ba0b8")
        val upColor = chartCss("--up", "#22c58b")
        val downColor = chartCss("--down", "#f4566d")
        val accent = chartCss("--accent", "#4f9cf9")
        val warn = chartCss("--warn", "#f2b544")
        val purple = chartCss("--ai", "#a78bfa")
        val font = "10px Inter, system-ui, sans-serif"

        val padLeft = 8.0
        val padRight = 64.0
        val padTop = 8.0
        val padBottom = 20.0
        val lowerKeys = ArrayList<String>()
        if ("RSI" in indicatorSet) lowerKeys.add("RSI")
        if ("MACD" in indicatorSet) lowerKeys.add("MACD")
        val lowerPaneHeight = if (lowerKeys.isEmpty()) 0.0 else 62.0 * lowerKeys.size + (if (lowerKeys.size > 1) 6.0 else 0.0)
        val plotWidth = max(width - padLeft - padRight, 40.0)
        val bodyHeight = max(height - padTop - padBottom - lowerPaneHeight, 120.0)
        val volumeHeight = bodyHeight * 0.18
        val priceHeight = bodyHeight - volumeHeight - 6.0
        val priceTop = padTop
        val volumeTop = priceTop + priceHeight + 6.0

        val start = visibleStart()
        val closes = closesOf(bars)
        val overlayValues = ArrayList<Double>()
        val ema20 = if ("EMA20" in indicatorSet) emaSeries(closes, 20) else null
        val ema50 = if ("EMA50" in indicatorSet) emaSeries(closes, 50) else null
        val sma200 = if ("SMA200" in indicatorSet) smaSeries(closes, 200) else null
        val vwap = if ("VWAP" in indicatorSet) vwapSeries(bars) else null
        val bollinger = if ("BB" in indicatorSet) bollingerSeries(closes, 20, 2.0) else null
        if (bollinger != null) {
            for (i in start until start + visible.size) {
                bollinger.upper.getOrNull(i)?.let { overlayValues.add(it) }
                bollinger.lower.getOrNull(i)?.let { overlayValues.add(it) }
            }
        }
        for (i in start until start + visible.size) {
            vwap?.getOrNull(i)?.let { overlayValues.add(it) }
            sma200?.getOrNull(i)?.let { overlayValues.add(it) }
        }

        var minValue = visible.minOf { it.low }
        var maxValue = visible.maxOf { it.high }
        for (value in overlayValues) {
            if (value.isFinite()) {
                minValue = min(minValue, value)
                maxValue = max(maxValue, value)
            }
        }
        val pad = max((maxValue - minValue) * 0.06, 0.01)
        minValue -= pad
        maxValue += pad
        val span = max(maxValue - minValue, 0.000001)
        val slot = plotWidth / visible.size

        fun xOf(localIndex: Int): Double = padLeft + slot * localIndex + slot / 2.0
        fun yOf(value: Double): Double = priceTop + (maxValue - value) / span * priceHeight

        // grid and price axis
        ctx.font = font
        ctx.lineWidth = 1.0
        val ticks = 4
        for (i in 0..ticks) {
            val value = maxValue - span * i / ticks
            val y = priceTop + priceHeight * i / ticks
            ctx.strokeStyle = gridColor
            ctx.beginPath()
            ctx.moveTo(padLeft, y)
            ctx.lineTo(padLeft + plotWidth, y)
            ctx.stroke()
            ctx.fillStyle = axisColor
            val label = price(value)
            val labelWidth = (ctx.measureText(label).width as Double)
            ctx.fillText(label, width - labelWidth - 4.0, y + 3.5)
        }

        // Bollinger band fill
        if (bollinger != null) {
            ctx.beginPath()
            var started = false
            for (local in visible.indices) {
                val upper = bollinger.upper.getOrNull(start + local) ?: continue
                val x = xOf(local)
                if (!started) {
                    ctx.moveTo(x, yOf(upper))
                    started = true
                } else {
                    ctx.lineTo(x, yOf(upper))
                }
            }
            for (local in visible.indices.reversed()) {
                val lower = bollinger.lower.getOrNull(start + local) ?: continue
                ctx.lineTo(xOf(local), yOf(lower))
            }
            if (started) {
                ctx.closePath()
                ctx.fillStyle = accent + "18"
                ctx.fill()
            }
        }

        // candles
        val bodyWidth = max(slot * 0.62, 1.0)
        for (local in visible.indices) {
            val bar = visible[local]
            val x = xOf(local)
            val yHigh = yOf(bar.high)
            val yLow = yOf(bar.low)
            val yOpen = yOf(bar.open)
            val yClose = yOf(bar.close)
            val rising = bar.close >= bar.open
            val color = if (rising) upColor else downColor
            ctx.strokeStyle = color
            ctx.fillStyle = color
            ctx.lineWidth = 1.0
            ctx.beginPath()
            ctx.moveTo(x, yHigh)
            ctx.lineTo(x, yLow)
            ctx.stroke()
            val bodyTop = min(yOpen, yClose)
            val bodyHeightPx = max(abs(yClose - yOpen), 1.0)
            ctx.fillRect(x - bodyWidth / 2.0, bodyTop, bodyWidth, bodyHeightPx)
        }

        // overlay lines
        fun drawSeries(series: List<Double?>?, color: String, thickness: Double, dashed: Boolean = false) {
            if (series == null) return
            ctx.beginPath()
            var started = false
            for (local in visible.indices) {
                val value = series.getOrNull(start + local) ?: continue
                if (!value.isFinite()) continue
                val x = xOf(local)
                val y = yOf(value)
                if (!started) {
                    ctx.moveTo(x, y)
                    started = true
                } else {
                    ctx.lineTo(x, y)
                }
            }
            if (started) {
                ctx.strokeStyle = color
                ctx.lineWidth = thickness
                if (dashed) ctx.setLineDash(js("[5,4]")) else ctx.setLineDash(js("[]"))
                ctx.stroke()
                ctx.setLineDash(js("[]"))
            }
        }
        drawSeries(ema20, accent, 1.4)
        drawSeries(ema50, warn, 1.4)
        drawSeries(sma200, purple, 1.4)
        drawSeries(vwap, "#f97316", 1.3, dashed = true)
        if (bollinger != null) {
            drawSeries(bollinger.upper, accent + "aa", 1.0, dashed = true)
            drawSeries(bollinger.lower, accent + "aa", 1.0, dashed = true)
        }

        // support / resistance from swing pivots
        if ("SR" in indicatorSet && bars.size > 20) {
            val windowBars = bars.subList(0, start + visible.size)
            val resistance = fitLine(windowBars, pivotHighs(windowBars), true)?.project(windowBars.size - 1)
            val support = fitLine(windowBars, pivotLows(windowBars), false)?.project(windowBars.size - 1)
            fun level(value: Double?, label: String, color: String) {
                if (value == null || !value.isFinite()) return
                val y = yOf(value)
                if (y < priceTop || y > priceTop + priceHeight) return
                ctx.strokeStyle = color
                ctx.setLineDash(js("[6,5]"))
                ctx.beginPath()
                ctx.moveTo(padLeft, y)
                ctx.lineTo(padLeft + plotWidth, y)
                ctx.stroke()
                ctx.setLineDash(js("[]"))
                ctx.fillStyle = color
                ctx.font = "9px Inter, system-ui, sans-serif"
                ctx.fillText(label + " " + price(value), padLeft + 4.0, y - 3.0)
            }
            level(resistance, "R", downColor)
            level(support, "S", upColor)
        }

        // volume pane
        ctx.strokeStyle = gridColor
        ctx.beginPath()
        ctx.moveTo(padLeft, volumeTop)
        ctx.lineTo(padLeft + plotWidth, volumeTop)
        ctx.stroke()
        val maxVolume = visible.maxOf { it.volume }.coerceAtLeast(1.0)
        for (local in visible.indices) {
            val bar = visible[local]
            val barHeight = bar.volume / maxVolume * volumeHeight
            ctx.fillStyle = if (bar.close >= bar.open) upColor else downColor
            ctx.globalAlpha = 0.4
            ctx.fillRect(xOf(local) - bodyWidth / 2.0, volumeTop + volumeHeight - barHeight, bodyWidth, barHeight)
            ctx.globalAlpha = 1.0
        }

        // lower indicator panes
        var paneTop = volumeTop + volumeHeight + 4.0
        fun paneFrame(label: String, top: Double, paneHeight: Double) {
            ctx.fillStyle = axisColor
            ctx.font = "9px Inter, system-ui, sans-serif"
            ctx.fillText(label, padLeft + 2.0, top + 9.0)
            ctx.strokeStyle = gridColor
            ctx.beginPath()
            ctx.moveTo(padLeft, top)
            ctx.lineTo(padLeft + plotWidth, top)
            ctx.stroke()
        }
        for (key in lowerKeys) {
            val paneHeight = 62.0
            paneFrame(key, paneTop, paneHeight)
            if (key == "RSI") {
                val series = rsiSeries(bars)
                fun rsiY(value: Double): Double = paneTop + (100.0 - value) / 100.0 * paneHeight
                for (guide in listOf(30.0, 50.0, 70.0)) {
                    ctx.strokeStyle = if (guide == 50.0) gridColor else axisColor + "55"
                    ctx.setLineDash(js("[3,4]"))
                    ctx.beginPath()
                    ctx.moveTo(padLeft, rsiY(guide))
                    ctx.lineTo(padLeft + plotWidth, rsiY(guide))
                    ctx.stroke()
                    ctx.setLineDash(js("[]"))
                }
                ctx.beginPath()
                var started = false
                for (local in visible.indices) {
                    val value = series.getOrNull(start + local) ?: continue
                    val x = xOf(local)
                    if (!started) {
                        ctx.moveTo(x, rsiY(value))
                        started = true
                    } else {
                        ctx.lineTo(x, rsiY(value))
                    }
                }
                if (started) {
                    ctx.strokeStyle = warn
                    ctx.lineWidth = 1.3
                    ctx.stroke()
                }
                ctx.fillStyle = axisColor
                ctx.font = "8px Inter, system-ui, sans-serif"
                ctx.fillText("70", padLeft + plotWidth + 6.0, rsiY(70.0) + 3.0)
                ctx.fillText("30", padLeft + plotWidth + 6.0, rsiY(30.0) + 3.0)
            } else {
                val macd = macdSeries(closes)
                var maxAbs = 0.0
                for (local in visible.indices) {
                    macd.macd.getOrNull(start + local)?.let { maxAbs = max(maxAbs, abs(it)) }
                    macd.signal.getOrNull(start + local)?.let { maxAbs = max(maxAbs, abs(it)) }
                }
                if (maxAbs <= 0.0) maxAbs = 1.0
                val mid = paneTop + paneHeight / 2.0
                fun macdY(value: Double): Double = mid - value / maxAbs * (paneHeight / 2.0 - 4.0)
                ctx.strokeStyle = gridColor
                ctx.beginPath()
                ctx.moveTo(padLeft, mid)
                ctx.lineTo(padLeft + plotWidth, mid)
                ctx.stroke()
                for (local in visible.indices) {
                    val value = macd.histogram.getOrNull(start + local) ?: continue
                    val y = macdY(value)
                    ctx.fillStyle = if (value >= 0) upColor else downColor
                    ctx.globalAlpha = 0.55
                    ctx.fillRect(xOf(local) - bodyWidth / 2.0, min(y, mid), bodyWidth, max(abs(mid - y), 0.6))
                    ctx.globalAlpha = 1.0
                }
                fun macdLine(series: List<Double?>, color: String) {
                    ctx.beginPath()
                    var started = false
                    for (local in visible.indices) {
                        val value = series.getOrNull(start + local) ?: continue
                        val x = xOf(local)
                        val y = macdY(value)
                        if (!started) {
                            ctx.moveTo(x, y)
                            started = true
                        } else {
                            ctx.lineTo(x, y)
                        }
                    }
                    if (started) {
                        ctx.strokeStyle = color
                        ctx.lineWidth = 1.2
                        ctx.stroke()
                    }
                }
                macdLine(macd.macd, accent)
                macdLine(macd.signal, warn)
            }
            paneTop += paneHeight + 6.0
        }

        // time axis
        ctx.fillStyle = axisColor
        ctx.font = font
        for (i in 0..4) {
            val local = ((visible.size - 1).toDouble() * i / 4.0).roundToInt().coerceIn(0, visible.size - 1)
            val label = chartTime(visible[local].time, intraday)
            val labelWidth = (ctx.measureText(label).width as Double)
            val x = min(max(xOf(local) - labelWidth / 2.0, 0.0), width - padRight - labelWidth)
            ctx.fillText(label, x, height - 6.0)
        }

        // last price tag
        val last = visible.last()
        val lastY = yOf(last.close)
        val lastColor = if (last.close >= last.open) upColor else downColor
        ctx.fillStyle = lastColor
        ctx.fillRect(padLeft + plotWidth + 2.0, lastY - 8.0, padRight - 6.0, 16.0)
        ctx.fillStyle = chartCss("--on-accent", "#04121f")
        ctx.font = "bold 10px Inter, system-ui, sans-serif"
        ctx.fillText(price(last.close), padLeft + plotWidth + 5.0, lastY + 3.5)

        // crosshair
        if (hoverIndex in visible.indices) {
            val bar = visible[hoverIndex]
            val x = xOf(hoverIndex)
            val y = yOf(bar.close)
            ctx.strokeStyle = axisColor
            ctx.globalAlpha = 0.45
            ctx.setLineDash(js("[3,3]"))
            ctx.beginPath()
            ctx.moveTo(x, priceTop)
            ctx.lineTo(x, height - padBottom)
            ctx.stroke()
            ctx.beginPath()
            ctx.moveTo(padLeft, y)
            ctx.lineTo(padLeft + plotWidth, y)
            ctx.stroke()
            ctx.setLineDash(js("[]"))
            ctx.globalAlpha = 1.0
            ctx.fillStyle = axisColor
            ctx.fillRect(padLeft + plotWidth + 2.0, y - 7.0, padRight - 6.0, 14.0)
            ctx.fillStyle = chartCss("--bg", "#070d16")
            ctx.fillText(price(bar.close), padLeft + plotWidth + 5.0, y + 3.0)

            tip.style.display = "block"
            tip.textContent = chartDay(bar.time) + "  O " + price(bar.open) + "  H " + price(bar.high) +
                "  L " + price(bar.low) + "  C " + price(bar.close) + "  V " + integer(bar.volume)
            val tipWidth = 260.0
            tip.style.left = min(max(hoverX + 12.0, 6.0), max(width - tipWidth, 6.0)).toString() + "px"
            tip.style.top = "8px"
            updateReadout(bar, start + hoverIndex)
        } else {
            tip.style.display = "none"
            updateReadout(last, start + visible.size - 1)
        }
    }
}

// ---------------------------------------------------------------- multi-series chart

fun normalizedSeries(values: List<Double>): List<Double> {
    if (values.isEmpty()) return emptyList()
    val base = values.first()
    if (base == 0.0) return values
    return values.map { it / base * 100.0 }
}

fun multiLineChart(
    series: List<Pair<String, List<Double>>>,
    height: Int = 260,
    baseline: Double = 100.0
): HTMLElement {
    val wrap = el("div", "chart kchart")
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.className = "chart-canvas kchart-canvas"
    canvas.style.height = height.toString() + "px"
    canvas.style.width = "100%"
    val legend = el("div", "kchart-legend")
    val colors = listOf("#4f9cf9", "#22c58b", "#f2b544", "#a78bfa", "#f97316")
    series.forEachIndexed { index, entry ->
        val item = el("span", "kchart-legend-item")
        val dot = el("span", "kchart-dot")
        dot.style.background = colors[index % colors.size]
        item.appendChild(dot)
        item.appendChild(el("span", "", entry.first))
        legend.appendChild(item)
    }
    wrap.appendChild(legend)
    wrap.appendChild(canvas)

    fun draw() {
        val width = wrap.clientWidth.toDouble()
        if (width < 20.0) return
        val ctx = chartCanvasCtx(canvas, width, height)
        val usable = series.filter { it.second.size > 1 }
        if (usable.isEmpty()) return
        val padLeft = 8.0
        val padRight = 52.0
        val padTop = 10.0
        val padBottom = 18.0
        val plotWidth = max(width - padLeft - padRight, 40.0)
        val plotHeight = max(height - padTop - padBottom, 60.0)
        val all = usable.flatMap { it.second }
        var minValue = all.minOrNull() ?: baseline
        var maxValue = all.maxOrNull() ?: baseline
        minValue = min(minValue, baseline)
        maxValue = max(maxValue, baseline)
        val pad = max((maxValue - minValue) * 0.05, 0.5)
        minValue -= pad
        maxValue += pad
        val gridColor = chartCss("--grid", "rgba(148,163,184,.16)")
        val axisColor = chartCss("--muted", "#8ba0b8")
        ctx.font = "10px Inter, system-ui, sans-serif"
        for (i in 0..4) {
            val value = maxValue - (maxValue - minValue) * i / 4.0
            val y = padTop + plotHeight * i / 4.0
            ctx.strokeStyle = gridColor
            ctx.beginPath()
            ctx.moveTo(padLeft, y)
            ctx.lineTo(padLeft + plotWidth, y)
            ctx.stroke()
            ctx.fillStyle = axisColor
            val label = fixed(value, 1)
            ctx.fillText(label, padLeft + plotWidth + 5.0, y + 3.5)
        }
        // 100 baseline
        val baseY = padTop + (maxValue - baseline) / (maxValue - minValue) * plotHeight
        ctx.strokeStyle = axisColor + "66"
        ctx.setLineDash(js("[4,4]"))
        ctx.beginPath()
        ctx.moveTo(padLeft, baseY)
        ctx.lineTo(padLeft + plotWidth, baseY)
        ctx.stroke()
        ctx.setLineDash(js("[]"))

        usable.forEachIndexed { index, entry ->
            val values = entry.second
            ctx.beginPath()
            values.forEachIndexed { i, value ->
                val x = padLeft + plotWidth * i / max(values.size - 1, 1).toDouble()
                val y = padTop + (maxValue - value) / (maxValue - minValue) * plotHeight
                if (i == 0) ctx.moveTo(x, y) else ctx.lineTo(x, y)
            }
            ctx.strokeStyle = colors[index % colors.size]
            ctx.lineWidth = 1.8
            ctx.stroke()
        }
    }

    window.setTimeout({ draw() }, 40)
    NpChartRegistry.register {
        val width = wrap.clientWidth.toDouble()
        if (width > 0.0) draw()
    }
    return wrap
}
