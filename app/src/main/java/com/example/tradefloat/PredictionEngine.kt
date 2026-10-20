
package com.example.tradefloat

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * TradeFloat prediction analysis.
 *
 * Input contract:
 * - Bars must be ordered oldest to newest.
 * - Prices must use a consistent scale.
 * - Higher values must mean higher market prices.
 * - Input bars must come from the chart detector, not arbitrary
 *   red/green pixel fragments.
 *
 * This is a heuristic directional assessment, not a validated
 * forecasting model or a guarantee of trading accuracy.
 */
object PredictionEngine {

    enum class Direction {
        UP, DOWN, UNCLEAR
    }

    enum class Strength {
        WEAK, MODERATE, STRONG, UNAVAILABLE
    }

    data class CandleBar(
        val timestampMs: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    ) {
        fun isValid(): Boolean =
            timestampMs > 0 &&
            open.isFinite() &&
            high.isFinite() &&
            low.isFinite() &&
            close.isFinite() &&
            high >= low &&
            high >= max(open, close) &&
            low <= minOf(open, close)
    }

    data class Result(
        val direction: Direction,
        val strength: Strength,
        val horizonSeconds: Int,
        val barsUsed: Int,
        val emaTrend: Direction,
        val rsiValue: Double?,
        val macdHistogram: Double?,
        val momentum: Direction,
        val swingTrend: Direction,
        val volatility: Double?,
        val explanation: String
    )

    private data class Metrics(
        val ema9: Double,
        val ema21: Double,
        val rsi: Double,
        val macdHistogram: Double,
        val momentum: Double,
        val swing: Double,
        val volatility: Double
    )

    /**
     * Analyze a sequence of detected candles for the selected horizon.
     *
     * candleIntervalSeconds should reflect the chart's candle interval,
     * not the screen capture interval.
     *
     * Examples:
     * - 5-second candles, 30-second horizon
     * - 5-second candles, 60-second horizon
     */
    fun analyze(
        input: List<CandleBar>,
        horizonSeconds: Int,
        candleIntervalSeconds: Int
    ): Result {

        val horizon = horizonSeconds.coerceIn(30, 60)
        val interval = candleIntervalSeconds.coerceAtLeast(1)

        val bars = input
            .filter { it.isValid() }
            .sortedBy { it.timestampMs }
            .distinctBy { it.timestampMs }

        if (bars.size < 30) {
            return unavailable(
                horizon,
                bars.size,
                "Need at least 30 valid candles before assessing direction."
            )
        }

        // Repeated timestamps or malformed OHLC should not be silently
        // interpreted as independent market observations.
        if (bars.size < 30) {
            return unavailable(
                horizon,
                bars.size,
                "Insufficient valid candle history."
            )
        }

        val closes = bars.map { it.close }

        if (closes.any { !it.isFinite() }) {
            return unavailable(
                horizon,
                bars.size,
                "Invalid close values."
            )
        }

        val recentBars = bars.takeLast(30)
        val recentCloses = recentBars.map { it.close }

        val metrics = calculateMetrics(recentBars)
            ?: return unavailable(
                horizon,
                bars.size,
                "Indicators could not be calculated."
            )

        val emaTrend = when {
            metrics.ema9 > metrics.ema21 -> Direction.UP
            metrics.ema9 < metrics.ema21 -> Direction.DOWN
            else -> Direction.UNCLEAR
        }

        val momentum = when {
            metrics.momentum > 0 -> Direction.UP
            metrics.momentum < 0 -> Direction.DOWN
            else -> Direction.UNCLEAR
        }

        val swingTrend = when {
            metrics.swing > 0 -> Direction.UP
            metrics.swing < 0 -> Direction.DOWN
            else -> Direction.UNCLEAR
        }

        val rsiDirection = when {
            metrics.rsi > 55.0 -> Direction.UP
            metrics.rsi < 45.0 -> Direction.DOWN
            else -> Direction.UNCLEAR
        }

        val macdDirection = when {
            metrics.macdHistogram > 0 -> Direction.UP
            metrics.macdHistogram < 0 -> Direction.DOWN
            else -> Direction.UNCLEAR
        }

        // Avoid treating a flat market or negligible movement as a signal.
        val movementScale = recentCloses
            .zipWithNext { a, b -> abs(b - a) }
            .average()

        val priceScale = recentCloses.last().let {
            max(abs(it), 1e-10)
        }

        val normalizedVolatility =
            metrics.volatility / priceScale

        if (
            !movementScale.isFinite() ||
            movementScale <= 0.0 ||
            !normalizedVolatility.isFinite()
        ) {
            return unavailable(
                horizon,
                bars.size,
                "Price movement is insufficient or invalid."
            )
        }

        val votes = listOf(
            emaTrend,
            momentum,
            swingTrend,
            rsiDirection,
            macdDirection
        )

        val upVotes = votes.count { it == Direction.UP }
        val downVotes = votes.count { it == Direction.DOWN }

        val direction = when {
            upVotes >= 4 && downVotes == 0 -> Direction.UP
            downVotes >= 4 && upVotes == 0 -> Direction.DOWN
            else -> Direction.UNCLEAR
        }

        // Short-horizon signals are particularly vulnerable to noise.
        // Require meaningful recent movement relative to recent variability.
        val recentMovement = abs(metrics.momentum)
        val movementIsMeaningful =
            recentMovement > movementScale * 0.50

        val strength = when {
            direction == Direction.UNCLEAR ->
                Strength.WEAK

            !movementIsMeaningful ->
                Strength.WEAK

            upVotes >= 5 || downVotes >= 5 ->
                Strength.STRONG

            else ->
                Strength.MODERATE
        }

        // Do not report UP/DOWN when the available evidence is weak.
        val displayedDirection = when {
            direction == Direction.UNCLEAR -> Direction.UNCLEAR
            strength == Strength.WEAK -> Direction.UNCLEAR
            else -> direction
        }

        val explanation = buildString {
            append("Horizon: ${horizon}s\n")
            append("Valid candles: ${bars.size}\n")
            append("Directional evidence: UP $upVotes / DOWN $downVotes\n")
            append("EMA trend: $emaTrend\n")
            append("Momentum: $momentum\n")
            append("Swing trend: $swingTrend\n")
            append("RSI: ${format(metrics.rsi)}\n")
            append("MACD histogram: ${format(metrics.macdHistogram)}\n")
            append("Evidence strength: $strength\n")

            if (displayedDirection == Direction.UNCLEAR) {
                append("No sufficiently aligned directional estimate.")
            } else {
                append("Directional estimate only; not a guaranteed forecast.")
            }
        }

        return Result(
            direction = displayedDirection,
            strength = strength,
            horizonSeconds = horizon,
            barsUsed = bars.size,
            emaTrend = emaTrend,
            rsiValue = metrics.rsi,
            macdHistogram = metrics.macdHistogram,
            momentum = momentum,
            swingTrend = swingTrend,
            volatility = metrics.volatility,
            explanation = explanation
        )
    }

    private fun calculateMetrics(
        bars: List<CandleBar>
    ): Metrics? {
        if (bars.size < 30) return null

        val closes = bars.map { it.close }

        val ema9 = ema(closes, 9)
        val ema21 = ema(closes, 21)

        val rsiValue = rsi(closes, 14)

        val macdLine = emaSeries(closes, 12)
            .zip(emaSeries(closes, 26)) { fast, slow ->
                fast - slow
            }

        val signalLine = ema(macdLine, 9)
        val macdHistogram = macdLine.last() - signalLine

        val recent = closes.takeLast(6)
        val momentum = recent.last() - recent.first()

        val swing = linearSlope(closes.takeLast(12))

        val changes = closes
            .zipWithNext { a, b -> b - a }

        val mean = changes.average()

        val variance = changes
            .map { change ->
                val difference = change - mean
                difference * difference
            }
            .average()

        val volatility = sqrt(max(0.0, variance))

        val values = listOf(
            ema9,
            ema21,
            rsiValue,
            macdHistogram,
            momentum,
            swing,
            volatility
        )

        if (values.any { !it.isFinite() }) return null

        return Metrics(
            ema9 = ema9,
            ema21 = ema21,
            rsi = rsiValue,
            macdHistogram = macdHistogram,
            momentum = momentum,
            swing = swing,
            volatility = volatility
        )
    }

    private fun ema(
        values: List<Double>,
        period: Int
    ): Double {
        return emaSeries(values, period).last()
    }

    private fun emaSeries(
        values: List<Double>,
        period: Int
    ): List<Double> {
        if (values.isEmpty()) return emptyList()

        val alpha = 2.0 / (period + 1.0)
        val output = ArrayList<Double>(values.size)

        var current = values.first()
        output.add(current)

        for (i in 1 until values.size) {
            current =
                alpha * values[i] + (1.0 - alpha) * current
            output.add(current)
        }

        return output
    }

    private fun rsi(
        closes: List<Double>,
        period: Int
    ): Double {
        if (closes.size <= period) return Double.NaN

        var gains = 0.0
        var losses = 0.0

        for (i in closes.size - period until closes.size) {
            val change = closes[i] - closes[i - 1]

            if (change > 0) {
                gains += change
            } else {
                losses -= change
            }
        }

        if (gains == 0.0 && losses == 0.0) return 50.0
        if (losses == 0.0) return 100.0
        if (gains == 0.0) return 0.0

        val averageGain = gains / period
        val averageLoss = losses / period
        val rs = averageGain / averageLoss

        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun linearSlope(
        values: List<Double>
    ): Double {
        if (values.size < 2) return 0.0

        val meanX = (values.size - 1) / 2.0
        val meanY = values.average()

        var numerator = 0.0
        var denominator = 0.0

        values.forEachIndexed { index, value ->
            val dx = index - meanX
            numerator += dx * (value - meanY)
            denominator += dx * dx
        }

        return if (denominator == 0.0) {
            0.0
        } else {
            numerator / denominator
        }
    }

    private fun unavailable(
        horizon: Int,
        count: Int,
        reason: String
    ): Result {
        return Result(
            direction = Direction.UNCLEAR,
            strength = Strength.UNAVAILABLE,
            horizonSeconds = horizon,
            barsUsed = count,
            emaTrend = Direction.UNCLEAR,
            rsiValue = null,
            macdHistogram = null,
            momentum = Direction.UNCLEAR,
            swingTrend = Direction.UNCLEAR,
            volatility = null,
            explanation = "Horizon: ${horizon}s\n$reason"
        )
    }

    private fun format(value: Double): String =
        if (value.isFinite()) {
            "%.5f".format(java.util.Locale.US, value)
        } else {
            "N/A"
        }
}
