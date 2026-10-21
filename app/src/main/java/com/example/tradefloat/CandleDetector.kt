package com.example.tradefloat

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CandleDetector {

    enum class CandleColor { GREEN, RED, UNKNOWN }

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

    private data class Segment(
        val x: Int,
        val top: Int,
        val bottom: Int,
        val color: CandleColor
    )

    fun detect(bitmap: Bitmap): DetectionResult {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth < 60 || originalHeight < 60) {
            return empty("Chart crop is too small")
        }

        // Bound processing cost on high-resolution screenshots.
        val scale = min(1.0, 320.0 / originalWidth)
        val width = max(1, (originalWidth * scale).toInt())
        val height = max(1, (originalHeight * scale).toInt())

        val workBitmap =
            if (width != originalWidth || height != originalHeight) {
                Bitmap.createScaledBitmap(bitmap, width, height, false)
            } else {
                bitmap
            }

        try {
            // One byte per pixel instead of a large 2D IntArray.
            val map = ByteArray(width * height)
            var coloredPixels = 0

            val pixels = IntArray(width)
            for (y in 0 until height) {
                workBitmap.getPixels(pixels, 0, width, 0, y, width, 1)

                val offset = y * width
                for (x in 0 until width) {
                    val type = classify(pixels[x])
                    map[offset + x] = type.toByte()

                    if (type != 0) coloredPixels++
                }
            }

            val coverage =
                coloredPixels.toDouble() / (width * height).coerceAtLeast(1)

            if (coverage < 0.001) {
                return empty("No clear red/green chart pixels", coverage)
            }

            val segments = ArrayList<Segment>()

            // Detect vertically connected colored runs.
            for (x in 0 until width) {
                var y = 0

                while (y < height) {
                    val type = map[y * width + x].toInt()

                    if (type == 0) {
                        y++
                        continue
                    }

                    val top = y
                    while (
                        y + 1 < height &&
                        map[(y + 1) * width + x].toInt() == type
                    ) {
                        y++
                    }

                    val bottom = y
                    val runHeight = bottom - top + 1

                    if (
                        runHeight >= 3 &&
                        runHeight <= max(4, (height * 0.18).toInt())
                    ) {
                        segments.add(
                            Segment(
                                x = x,
                                top = top,
                                bottom = bottom,
                                color = if (type == 1) {
                                    CandleColor.GREEN
                                } else {
                                    CandleColor.RED
                                }
                            )
                        )
                    }
                    y++
                }
            }

            if (segments.isEmpty()) {
                return empty("No candle-like segments found", coverage)
            }

            // Cluster segments that continue across adjacent columns.
            val groups = ArrayList<MutableList<Segment>>()

            for (segment in segments) {
                var matched: MutableList<Segment>? = null

                for (group in groups.asReversed()) {
                    val last = group.last()

                    if (segment.x - last.x > 2) break

                    val sameColor = segment.color == last.color
                    val verticalGap = when {
                        segment.top > last.bottom ->
                            segment.top - last.bottom
                        last.top > segment.bottom ->
                            last.top - segment.bottom
                        else -> 0
                    }

                    if (sameColor && verticalGap <= max(3, height / 120)) {
                        matched = group
                        break
                    }
                }

                if (matched != null) {
                    matched.add(segment)
                } else {
                    groups.add(mutableListOf(segment))
                }
            }

            val candidates = groups.mapNotNull { group ->
                val left = group.minOf { it.x }
                val right = group.maxOf { it.x }
                val top = group.minOf { it.top }
                val bottom = group.maxOf { it.bottom }

                val bodyWidth = right - left + 1
                val bodyHeight = bottom - top + 1

                if (
                    bodyWidth < 2 ||
                    bodyWidth > max(12, width / 8) ||
                    bodyHeight < 3 ||
                    bodyHeight > height * 0.18
                ) {
                    null
                } else {
                    DetectedCandle(
                        centerX = (left + right) / 2,
                        bodyTop = top,
                        bodyBottom = bottom,
                        wickTop = top,
                        wickBottom = bottom,
                        color = group.first().color,
                        quality = min(
                            1.0,
                            bodyWidth.toDouble() / 5.0
                        )
                    )
                }
            }.sortedBy { it.centerX }

            // Remove overlapping duplicate candidates.
            val unique = ArrayList<DetectedCandle>()
            for (candidate in candidates) {
                val previous = unique.lastOrNull()

                if (
                    previous == null ||
                    abs(candidate.centerX - previous.centerX) >= 4
                ) {
                    unique.add(candidate)
                } else if (candidate.quality > previous.quality) {
                    unique[unique.lastIndex] = candidate
                }
            }

            val usable = unique.size >= 8

            return DetectionResult(
                candles = unique,
                coloredCoverage = coverage,
                usable = usable,
                message = if (usable) {
                    "Found ${unique.size} candidate shapes; OHLC unverified"
                } else {
                    "Insufficient candle-like shapes"
                }
            )
        } finally {
            if (workBitmap !== bitmap) {
                workBitmap.recycle()
            }
        }
    }

    fun toVisualSeries(
        detection: DetectionResult,
        chartHeight: Int
    ): List<Double> {
        if (!detection.usable || chartHeight <= 0) {
            return emptyList()
        }

        return detection.candles.map {
            1.0 - (it.centerY / chartHeight).coerceIn(0.0, 1.0)
        }
    }

    private fun classify(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        if (max(r, max(g, b)) < 100) return 0
        if (max(r, max(g, b)) - min(r, min(g, b)) < 45) return 0

        return when {
            r > g * 1.22 && r > b * 1.12 -> 2
            g > r * 1.10 && g > b * 1.05 -> 1
            else -> 0
        }
    }

    private fun empty(
        message: String,
        coverage: Double = 0.0
    ) = DetectionResult(
        candles = emptyList(),
        coloredCoverage = coverage,
        usable = false,
        message = message
    )
}
