package com.example.studiocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max

/**
 * Handles background removal (color/edge based, offline, no AI) and
 * auto lighting correction to approximate a "studio shot" look.
 */
object ImageProcessor {

    fun processToStudioLook(original: Bitmap): Bitmap {
        val lit = autoLightingCorrection(original)
        val mask = getForegroundMask(lit)
        return compositeOnWhite(lit, mask)
    }

    private fun getForegroundMask(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val sampleSize = max(1, minOf(width, height) / 20)
        val bgColor = sampleBackgroundColor(pixels, width, height, sampleSize)
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        val threshold = 40f
        val softRange = 25f

        val mask = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val dr = Color.red(p) - bgR
            val dg = Color.green(p) - bgG
            val db = Color.blue(p) - bgB
            val dist = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()

            mask[i] = when {
                dist <= threshold -> 0f
                dist >= threshold + softRange -> 1f
                else -> (dist - threshold) / softRange
            }
        }

        return mask
    }

    private fun sampleBackgroundColor(
        pixels: IntArray,
        width: Int,
        height: Int,
        sampleSize: Int
    ): Int {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        val regions = listOf(
            0 to 0,
            (width - sampleSize) to 0,
            0 to (height - sampleSize),
            (width - sampleSize) to (height - sampleSize)
        )

        for ((startX, startY) in regions) {
            for (y in startY until startY + sampleSize) {
                if (y < 0 || y >= height) continue
                for (x in startX until startX + sampleSize) {
                    if (x < 0 || x >= width) continue
                    val p = pixels[y * width + x]
                    rSum += Color.red(p)
                    gSum += Color.green(p)
                    bSum += Color.blue(p)
                    count++
                }
            }
        }

        if (count == 0) return Color.WHITE
        return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    private fun compositeOnWhite(bitmap: Bitmap, mask: FloatArray): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val confidence = mask[i].coerceIn(0f, 1f)
            val pixel = pixels[i]
            val alpha = (confidence * 255).toInt()
            pixels[i] = Color.argb(
                alpha,
                Color.red(pixel),
                Color.green(pixel),
                Color.blue(pixel)
            )
        }

        val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fgBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(fgBitmap, 0f, 0f, paint)

        return output
    }

    private fun autoLightingCorrection(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val sampleStep = max(1, (width * height) / 50000)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var lumSum = 0L
        var count = 0

        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            rSum += r
            gSum += g
            bSum += b
            lumSum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            count++
            i += sampleStep
        }

        val avgR = rSum.toFloat() / count
        val avgG = gSum.toFloat() / count
        val avgB = bSum.toFloat() / count
        val avgLum = lumSum.toFloat() / count

        val targetLum = 150f
        val brightnessShift = (targetLum - avgLum).coerceIn(-15f, 15f)

        val grayAvg = (avgR + avgG + avgB) / 3f
        val rGain = (grayAvg / avgR).coerceIn(0.95f, 1.05f)
        val gGain = (grayAvg / avgG).coerceIn(0.95f, 1.05f)
        val bGain = (grayAvg / avgB).coerceIn(0.95f, 1.05f)

        val contrast = 1.03f
        val saturation = 1.0f

        val cm = ColorMatrix()

        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(saturation)

        val translate = 128f * (1 - contrast) + brightnessShift
        val wbContrastBrightness = ColorMatrix(
            floatArrayOf(
                contrast * rGain, 0f, 0f, 0f, translate,
                0f, contrast * gGain, 0f, 0f, translate,
                0f, 0f, contrast * bGain, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        cm.postConcat(satMatrix)
        cm.postConcat(wbContrastBrightness)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return output
    }
}
