// Technical indicator series for the advanced chart, replay and comparison tools.
// Every series has the same length as its input; entries are null until the
// indicator has enough history. Nothing is extrapolated or invented.

fun closesOf(bars: List<Bar>): List<Double> = bars.map { it.close }

fun smaSeries(values: List<Double>, period: Int): List<Double?> {
    val out = ArrayList<Double?>(values.size)
    if (period <= 0) {
        for (i in values.indices) out.add(null)
        return out
    }
    var sum = 0.0
    for (i in values.indices) {
        sum += values[i]
        if (i >= period) sum -= values[i - period]
        out.add(if (i >= period - 1) sum / period else null)
    }
    return out
}

fun emaSeries(values: List<Double>, period: Int): List<Double?> {
    val out = ArrayList<Double?>(values.size)
    if (period <= 0) {
        for (i in values.indices) out.add(null)
        return out
    }
    var previous: Double? = null
    val k = 2.0 / (period + 1.0)
    for (i in values.indices) {
        val prior = previous
        val next = when {
            prior != null -> values[i] * k + prior * (1.0 - k)
            i + 1 >= period -> values.subList(0, i + 1).average()
            else -> null
        }
        previous = next
        out.add(next)
    }
    return out
}

fun rsiSeries(bars: List<Bar>, period: Int = 14): List<Double?> {
    val out = ArrayList<Double?>(bars.size)
    if (bars.size <= period) {
        for (i in bars.indices) out.add(null)
        return out
    }
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val delta = bars[i].close - bars[i - 1].close
        if (delta >= 0) gain += delta else loss -= delta
    }
    var avgGain = gain / period
    var avgLoss = loss / period
    for (i in bars.indices) {
        if (i <= period) {
            out.add(null)
            continue
        }
        val delta = bars[i].close - bars[i - 1].close
        avgGain = (avgGain * (period - 1) + maxOf(delta, 0.0)) / period
        avgLoss = (avgLoss * (period - 1) + maxOf(-delta, 0.0)) / period
        out.add(if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss))
    }
    return out
}

data class MacdSeries(
    val macd: List<Double?>,
    val signal: List<Double?>,
    val histogram: List<Double?>
)

fun macdSeries(values: List<Double>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): MacdSeries {
    val fastEma = emaSeries(values, fast)
    val slowEma = emaSeries(values, slow)
    val macd = ArrayList<Double?>(values.size)
    val defined = ArrayList<Double>()
    val definedAt = ArrayList<Int>()
    for (i in values.indices) {
        val a = fastEma.getOrNull(i)
        val b = slowEma.getOrNull(i)
        val value = if (a != null && b != null) a - b else null
        macd.add(value)
        if (value != null) {
            defined.add(value)
            definedAt.add(i)
        }
    }
    val signalDefined = emaSeries(defined, signalPeriod)
    val signal = ArrayList<Double?>(values.size)
    val histogram = ArrayList<Double?>(values.size)
    for (i in values.indices) {
        signal.add(null)
        histogram.add(null)
    }
    for (j in defined.indices) {
        val value = signalDefined.getOrNull(j) ?: continue
        val index = definedAt[j]
        signal[index] = value
        val macdValue = macd[index]
        histogram[index] = if (macdValue != null) macdValue - value else null
    }
    return MacdSeries(macd, signal, histogram)
}

data class BollingerSeries(
    val upper: List<Double?>,
    val middle: List<Double?>,
    val lower: List<Double?>
)

fun bollingerSeries(values: List<Double>, period: Int = 20, multiplier: Double = 2.0): BollingerSeries {
    val middle = smaSeries(values, period)
    val upper = ArrayList<Double?>(values.size)
    val lower = ArrayList<Double?>(values.size)
    for (i in values.indices) {
        val mid = middle.getOrNull(i)
        if (mid == null || i < period - 1) {
            upper.add(null)
            lower.add(null)
            continue
        }
        var sum = 0.0
        for (j in i - period + 1..i) {
            val diff = values[j] - mid
            sum += diff * diff
        }
        val deviation = kotlin.math.sqrt(sum / period)
        upper.add(mid + deviation * multiplier)
        lower.add(mid - deviation * multiplier)
    }
    return BollingerSeries(upper, middle, lower)
}

fun vwapSeries(bars: List<Bar>): List<Double?> {
    val out = ArrayList<Double?>(bars.size)
    var cumulativeValue = 0.0
    var cumulativeVolume = 0.0
    for (bar in bars) {
        val typical = (bar.high + bar.low + bar.close) / 3.0
        cumulativeValue += typical * bar.volume
        cumulativeVolume += bar.volume
        out.add(if (cumulativeVolume > 0.0) cumulativeValue / cumulativeVolume else null)
    }
    return out
}

// ---------------------------------------------------------------- returns and risk

/** Simple period returns of a close series, oldest first. */
fun returnsOf(values: List<Double>): List<Double> {
    val out = ArrayList<Double>()
    for (i in 1 until values.size) {
        val previous = values[i - 1]
        if (previous != 0.0) out.add((values[i] - previous) / previous)
    }
    return out
}

fun standardDeviation(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    var sum = 0.0
    for (value in values) {
        val diff = value - mean
        sum += diff * diff
    }
    return kotlin.math.sqrt(sum / (values.size - 1))
}

/** Annualised volatility in percent from daily returns. */
fun annualisedVolatility(dailyReturns: List<Double>): Double =
    standardDeviation(dailyReturns) * kotlin.math.sqrt(252.0) * 100.0

fun correlationOf(a: List<Double>, b: List<Double>): Double? {
    val size = minOf(a.size, b.size)
    if (size < 3) return null
    val left = a.subList(a.size - size, a.size)
    val right = b.subList(b.size - size, b.size)
    val meanLeft = left.average()
    val meanRight = right.average()
    var covariance = 0.0
    var varianceLeft = 0.0
    var varianceRight = 0.0
    for (i in 0 until size) {
        val dl = left[i] - meanLeft
        val dr = right[i] - meanRight
        covariance += dl * dr
        varianceLeft += dl * dl
        varianceRight += dr * dr
    }
    if (varianceLeft <= 0.0 || varianceRight <= 0.0) return null
    return covariance / kotlin.math.sqrt(varianceLeft * varianceRight)
}

/** Maximum peak-to-trough drawdown in percent (a negative number). */
fun maxDrawdown(equity: List<Double>): Double {
    if (equity.isEmpty()) return 0.0
    var peak = equity[0]
    var worst = 0.0
    for (value in equity) {
        if (value > peak) peak = value
        if (peak > 0.0) {
            val drawdown = (value - peak) / peak * 100.0
            if (drawdown < worst) worst = drawdown
        }
    }
    return worst
}

/** Return over the trailing lookback bars, in percent. */
fun periodReturn(values: List<Double>, lookback: Int): Double? {
    if (values.size < 2) return null
    val from = values[maxOf(0, values.size - 1 - lookback)]
    if (from == 0.0) return null
    return (values.last() - from) / from * 100.0
}
