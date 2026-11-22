import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import kotlin.math.max
import kotlin.math.min

/** Symbol chosen on another screen and handed to the replay tool. */
var replaySymbolSeed: String = ""

private var replayTimer: Int = -1

fun stopReplayPlayback() {
    if (replayTimer >= 0) {
        window.clearInterval(replayTimer)
        replayTimer = -1
    }
}

fun replayScreen(): HTMLElement {
    stopReplayPlayback()
    val root = el("div", "screen")
    val body = el("div", "screen-body")
    root.appendChild(pageHead(
        "HISTORICAL REPLAY",
        "Chart replay",
        "Step through real daily candles one bar at a time. Future candles stay hidden, so every decision is made with the information available on that day.",
        listOf(actionButton("Advanced charts", "secondary", "chart") { go("charts") })
    ))
    root.appendChild(body)

    var symbol = replaySymbolSeed.ifBlank { currentSymbol.ifBlank { "NABIL" } }
    replaySymbolSeed = ""
    var allBars: List<Bar> = emptyList()
    var index = 0
    var playing = false
    var speedMs = 300

    val chartHost = el("div", "kchart-host")
    val chart = NpKChart(chartHost, 400, intraday = false)
    val chartCard = card("chart-card")
    val chartHead = el("div", "chart-head")
    val chartTitle = el("div", "chart-title", symbol)
    val chartMeta = el("div", "chart-meta", "Replay · Daily")
    chartHead.add(chartTitle, chartMeta)
    chartCard.appendChild(chartHead)
    chartCard.appendChild(chartHost)

    val statusLine = el("p", "muted small", "Load a symbol to begin.")
    val positionLabel = el("span", "replay-position", "Bar 0 of 0")
    val barInfo = el("div", "replay-bar")
    val playButton = actionButton("Play", "primary", "arrowUp") { }
    val slider = document.createElement("input") as HTMLInputElement
    slider.type = "range"
    slider.min = "0"
    slider.max = "1"
    slider.step = "1"
    slider.value = "0"
    slider.className = "range replay-range"
    val speedHost = el("div", "replay-speeds")

    val symbolInput = field("NEPSE symbol", "search", symbol)
    val dateInput = document.createElement("input") as HTMLInputElement
    dateInput.type = "date"
    dateInput.className = "field"
    dateInput.value = ""

    val loadButton = actionButton("Load series", "primary", "download") { }

    val searchRow = el("div", "toolbar")
    searchRow.appendChild(symbolInput)
    searchRow.appendChild(dateInput)
    searchRow.appendChild(loadButton)
    searchRow.appendChild(actionButton("Latest", "ghost", "refresh") {
        dateInput.value = ""
        symbolInput.value = symbol
        loadButton.asDynamic().click()
    })

    var stepFn: (Int) -> Unit = {}
    var resetFn: () -> Unit = {}
    var startPlayFn: () -> Unit = {}

    val controls = el("div", "replay-controls")
    controls.appendChild(actionButton("Step −1", "ghost", "arrowDown") { stepFn(-1) })
    controls.appendChild(playButton)
    controls.appendChild(actionButton("Step +1", "ghost", "arrowUp") { stepFn(1) })
    controls.appendChild(actionButton("Reset", "ghost", "refresh") { resetFn() })
    controls.appendChild(positionLabel)

    val sliderRow = el("div", "replay-slider")
    sliderRow.appendChild(el("span", "muted small", "Timeline"))
    sliderRow.appendChild(slider)

    val toolbar = card("toolbar-card")
    toolbar.appendChild(searchRow)
    toolbar.appendChild(controls)
    toolbar.appendChild(speedHost)
    toolbar.appendChild(sliderRow)
    toolbar.appendChild(statusLine)
    body.appendChild(toolbar)
    body.appendChild(chartCard)
    body.appendChild(barInfo)
    body.appendChild(el("p", "muted tiny",
        "Replay only reveals candles up to the selected bar. Data comes from /api/tv/history; if the provider returns no_data for an interval, nothing is fabricated."))

    fun paintPlayButton() {
        playButton.innerHTML = ""
        playButton.appendChild(icon(if (playing) "clock" else "arrowUp", 15))
        playButton.appendChild(el("span", "", if (playing) "Pause" else "Play"))
        playButton.classList.toggle("btn-primary", !playing)
        playButton.classList.toggle("btn-secondary", playing)
    }

    fun paintSpeeds() {
        speedHost.innerHTML = ""
        speedHost.appendChild(el("span", "muted small", "Speed"))
        for (speed in listOf(200, 300, 500, 1000)) {
            speedHost.appendChild(chipButton(speed.toString() + " ms", speed == speedMs) {
                speedMs = speed
                paintSpeeds()
                if (playing) startPlayFn()
            })
        }
    }

    fun currentBar(): Bar? {
        if (allBars.isEmpty() || index <= 0) return null
        return allBars.getOrNull(min(index, allBars.size) - 1)
    }

    fun paintBarInfo() {
        barInfo.innerHTML = ""
        val bar = currentBar()
        if (bar == null) {
            barInfo.appendChild(emptyState("No candle revealed yet", "Step forward once the series has loaded."))
            return
        }
        val previous = allBars.getOrNull(min(index, allBars.size) - 2)?.close
        val change = if (previous != null && previous != 0.0) (bar.close - previous) / previous * 100.0 else null
        val grid = el("div", "stat-grid")
        grid.appendChild(statTile("Replay date", barIsoDate(bar.time), "Daily candle"))
        grid.appendChild(statTile("Close", price(bar.close), percent(change), toneClass(change)))
        grid.appendChild(statTile("Open", price(bar.open)))
        grid.appendChild(statTile("High", price(bar.high)))
        grid.appendChild(statTile("Low", price(bar.low)))
        grid.appendChild(statTile("Volume", integer(bar.volume), "Shares traded"))
        barInfo.appendChild(grid)
    }

    fun paint() {
        chart.setReveal(index)
        if (allBars.isEmpty()) {
            positionLabel.textContent = "Bar 0 of 0"
            slider.max = "1"
            slider.value = "0"
        } else {
            positionLabel.textContent = "Bar " + index + " of " + allBars.size
            slider.max = max(allBars.size, 1).toString()
            slider.value = index.toString()
        }
        paintPlayButton()
        paintBarInfo()
    }

    fun pause() {
        playing = false
        stopReplayPlayback()
        paintPlayButton()
    }

    stepFn = { delta ->
        pause()
        if (allBars.isNotEmpty()) {
            index = (index + delta).coerceIn(1, allBars.size)
            paint()
        }
    }

    resetFn = {
        pause()
        if (allBars.isNotEmpty()) {
            index = max(1, allBars.size / 3)
            paint()
        }
    }

    startPlayFn = play@{
        if (allBars.isEmpty()) return@play
        if (index >= allBars.size) index = max(1, allBars.size / 3)
        playing = true
        stopReplayPlayback()
        replayTimer = window.setInterval({
            if (!playing) {
                stopReplayPlayback()
            } else if (index < allBars.size) {
                index++
                paint()
            } else {
                playing = false
                stopReplayPlayback()
                paintPlayButton()
            }
            null
        }, speedMs)
        paintPlayButton()
    }

    fun togglePlay() {
        if (playing) {
            pause()
        } else {
            if (allBars.isEmpty()) {
                toast("Load a symbol before starting playback.", "error")
            } else {
                startPlayFn()
            }
        }
    }

    playButton.onClick { togglePlay() }
    slider.addEventListener("input", {
        val next = slider.value.toIntOrNull() ?: index
        index = next.coerceIn(1, max(allBars.size, 1))
        chart.setReveal(index)
        positionLabel.textContent = "Bar " + index + " of " + allBars.size
        paintBarInfo()
    })

    fun loadSeries() {
        stopReplayPlayback()
        playing = false
        val clean = symbolInput.value.trim().uppercase().ifBlank { symbol }
        symbol = clean
        chartTitle.textContent = clean
        statusLine.textContent = "Loading daily history for " + clean + "…"
        fetchBars(clean, "D", 1500) { bars, error ->
            if (bars.isEmpty()) {
                allBars = emptyList()
                index = 0
                chart.setData(emptyList())
                statusLine.textContent = error ?: "No candles returned."
                paint()
                return@fetchBars
            }
            allBars = bars
            val chosen = dateInput.value.trim()
            var start = max(1, bars.size - 120)
            if (chosen.isNotBlank()) {
                val found = bars.indexOfFirst { barIsoDate(it.time) >= chosen }
                if (found >= 0) start = found + 1
            }
            index = start.coerceIn(1, bars.size)
            chart.symbol = clean
            chart.rangeLabel = "Replay"
            chart.intervalLabel = "D"
            chart.setData(bars)
            chart.setReveal(index)
            dateInput.value = barIsoDate(bars.last().time)
            statusLine.textContent = integer(bars.size.toDouble()) + " daily candles · " + barIsoDate(bars.first().time) +
                " → " + barIsoDate(bars.last().time) + " · replaying from " + barIsoDate(bars[max(index - 1, 0)].time)
            paint()
        }
    }

    loadButton.onClick { loadSeries() }
    symbolInput.addEventListener("keydown", { event ->
        if (eventKey(event) == "Enter") loadSeries()
    })

    speedHost.appendChild(el("span", "muted small", "Speed"))
    paintSpeeds()
    paint()
    loadSeries()
    return root
}
