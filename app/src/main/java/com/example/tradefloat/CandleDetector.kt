
package com.example.tradefloat

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects candle-like shapes in a cropped chart image.
 *
 * IMPORTANT:
 * - Input must contain only the plot area, excluding price labels,
 *   buttons, volume bars, and unrelated UI.
 * - Screen pixels cannot establish true market OHLC values.
 * - The resulting values are normalized visual coordinates.
 * - Do not treat these estimates as verified prices or a reliable signal.
 */
class CandleDetector {

    enum class CandleColor {
        GREEN, RED, UNKNOWN
    }

    data class DetectedCandle(
        val centerX: Int,
        val bodyTop: Int,
        val bodyBottom: Int,
        val wickTop: Int,
        val wickBottom: Int,
        val color: CandleColor,
        val quality: Double
    ) {
        val bodyHeight: Int
            get() = bodyBottom - bodyTop + 1

        val centerY: Double
            get() = (bodyTop + bodyBottom) / 2.0
    }

    data class DetectionResult(
        val candles: List<DetectedCandle>,
        val coloredCoverage: Double,
        val usable: Boolean,
        val message: String
    )

    /**
     * Detect bodies from a chart-only bitmap.
     *
     * This uses color and geometry heuristics, not computer-vision
     * model inference. It can fail on unusual chart themes.
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height

        if (width < 80 || height < 80) {
            return emptyResult("Chart crop is too small.")
        }

        val sampleStep = 1
        val totalPixels = width * height
        var coloredPixels = 0

        val colorMap = Array(width) { IntArray(height) }

        for (x in 0 until width step sampleStep) {
            for (y in 0 until height step sampleStep) {
                val color = classifyColor(bitmap.getPixel(x, y))
                colorMap[x][y] = color.ordinal

                if (color != CandleColor.UNKNOWN) {
                    coloredPixels++
                }
            }
        }

        val coverage =
            coloredPixels.toDouble() / totalPixels.coerceAtLeast(1)

        if (coverage < 0.001) {
            return DetectionResult(
                emptyList(),
                coverage,
                false,
                "Insufficient colored chart pixels."
            )
        }

        val rawBodies = mutableListOf<DetectedCandle>()

        // Find vertically connected colored segments in each column.
        for (x in 0 until width) {
            var y = 0

            while (y < height) {
                val color = CandleColor.values()[colorMap[x][y]]

                if (color == CandleColor.UNKNOWN) {
                    y++
                    continue
                }

                val start = y
                var end = y
                var sameColorCount = 0

                while (
                    end + 1 < height &&
                    CandleColor.values()[colorMap[x][end + 1]] == color
                ) {
                    end++
                    sameColorCount++
                }

                val segmentHeight = end - start + 1

                // Exclude tiny fragments and tall UI bars.
                if (
                    segmentHeight in 3..(height * 0.18).toInt().coerceAtLeast(3)
                ) {
                    rawBodies.add(
                        DetectedCandle(
                            centerX = x,
                            bodyTop = start,
                            bodyBottom = end,
                            wickTop = start,
                            wickBottom = end,
                            color = color,
                            quality = sameColorCount.toDouble() /
                                segmentHeight.coerceAtLeast(1)
                        )
                    )
                }

                y = end + 1
            }
        }

        if (rawBodies.isEmpty()) {
            return DetectionResult(
                emptyList(),
                coverage,
                false,
                "No candle-like bodies detected."
            )
        }

        val grouped = mergeAdjacentSegments(
            rawBodies,
            width,
            height
        )

        val finalCandles = grouped
            .filter { it.bodyHeight >= 3 }
            .sortedBy { it.centerX }

        val distinct = mutableListOf<DetectedCandle>()

        for (candle in finalCandles) {
            val previous = distinct.lastOrNull()

            if (
                previous == null ||
                candle.centerX - previous.centerX >= 3
            ) {
                distinct.add(candle)
            } else if (candle.quality > previous.quality) {
                distinct[distinct.lastIndex] = candle
            }
        }

        val usable = distinct.size >= 8

        return DetectionResult(
            candles = distinct,
            coloredCoverage = coverage,
            usable = usable,
            message = if (usable) {
                "Detected ${distinct.size} candle-like bodies. OHLC is not verified."
            } else {
                "Only ${distinct.size} candidate bodies detected; more chart data needed."
            }
        )
    }

    /**
     * Convert pixel coordinates to normalized visual values.
     *
     * This is NOT a conversion to market prices. Screen Y increases
     * downward, so the vertical coordinate is inverted.
     */
    fun toVisualSeries(
        detection: DetectionResult,
        chartHeight: Int
    ): List<Double> {
        if (!detection.usable || chartHeight <= 0) {
            return emptyList()
        }

        return detection.candles.map { candle ->
            1.0 - (
                candle.centerY / chartHeight.toDouble()
            ).coerceIn(0.0, 1.0)
        }
    }

    private fun mergeAdjacentSegments(
        segments: List<DetectedCandle>,
        width: Int,
        height: Int
    ): List<DetectedCandle> {
        val sorted = segments.sortedWith(
            compareBy<DetectedCandle> { it.centerX }
                .thenBy { it.bodyTop }
        )

        val output = mutableListOf<DetectedCandle>()

        for (segment in sorted) {
            val previous = output.lastOrNull()

            val canMerge = previous != null &&
                previous.color == segment.color &&
                segment.centerX - previous.centerX <= 2 &&
                verticalGap(previous, segment) <=
                    max(2, height / 150)

            if (canMerge && previous != null) {
                val top = min(previous.bodyTop, segment.bodyTop)
                val bottom = max(previous.bodyBottom, segment.bodyBottom)

                output[output.lastIndex] = DetectedCandle(
                    centerX = (previous.centerX + segment.centerX) / 2,
                    bodyTop = top,
                    bodyBottom = bottom,
                    wickTop = min(previous.wickTop, segment.wickTop),
                    wickBottom = max(
                        previous.wickBottom,
                        segment.wickBottom
                    ),
                    color = previous.color,
                    quality = (
                        previous.quality + segment.quality
                    ) / 2.0
                )
            } else {
                output.add(segment)
            }
        }

        return output
    }

    private fun verticalGap(
        a: DetectedCandle,
        b: DetectedCandle
    ): Int {
        return when {
            b.bodyTop > a.bodyBottom ->
                b.bodyTop - a.bodyBottom

            a.bodyTop > b.bodyBottom ->
                a.bodyTop - b.bodyBottom

            else -> 0
        }
    }

    private fun classifyColor(pixel: Int): CandleColor {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        val high = max(r, max(g, b))
        val low = min(r, min(g, b))

        if (high < 100 || high - low < 45) {
            return CandleColor.UNKNOWN
        }

        return when {
            r > g * 1.22 && r > b * 1.12 ->
                CandleColor.RED

            g > r * 1.10 && g > b * 1.05 ->
                CandleColor.GREEN

            else ->
                CandleColor.UNKNOWN
        }
    }

    private fun emptyResult(message: String) =
        DetectionResult(
            candles = emptyList(),
            coloredCoverage = 0.0,
            usable = false,
            message = message
        )
}
