package com.example.studiocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer

/**
 * Handles background removal (ML Kit selfie segmentation) and
 * auto lighting correction to approximate a "studio shot" look.
 */
object ImageProcessor {

    private val segmenter: Segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    /**
     * Full pipeline: auto lighting correction -> background removal ->
     * composite onto solid white studio background.
     */
    fun processToStudioLook(original: Bitmap): Bitmap {
        val lit = autoLightingCorrection(original)
        val mask = getForegroundMask(lit)
        return compositeOnWhite(lit, mask)
    }

    /**
     * Runs ML Kit selfie segmentation synchronously (blocking call,
     * must be called from a background thread) and returns a
     * confidence mask scaled to the bitmap's dimensions.
     */
    private fun getForegroundMask(bitmap: Bitmap): FloatArray {
        val image = InputImage.fromBitmap(bitmap, 0)
        val task = segmenter.process(image)
        val result = Tasks.await(task) // blocking - call from background thread

        val maskBuffer: ByteBuffer = result.buffer
        val maskWidth = result.width
        val maskHeight = result.height

        maskBuffer.rewind()
        val maskValues = FloatArray(maskWidth * maskHeight)
        for (i in maskValues.indices) {
            maskValues[i] = maskBuffer.float
        }

        // If mask resolution differs from bitmap, scale-sample it.
        if (maskWidth == bitmap.width && maskHeight == bitmap.height) {
            return maskValues
        }

        val scaled = FloatArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) {
            val srcY = (y * maskHeight / bitmap.height).coerceIn(0, maskHeight - 1)
            for (x in 0 until bitmap.width) {
                val srcX = (x * maskWidth / bitmap.width).coerceIn(0, maskWidth - 1)
                scaled[y * bitmap.width + x] = maskValues[srcY * maskWidth + srcX]
            }
        }
        return scaled
    }

    /**
     * Composites the subject (using the confidence mask as alpha)
     * onto a solid white background, producing the "studio" backdrop.
     */
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

    /**
     * Simple automatic exposure / contrast / white balance correction
     * to approximate even, bright studio lighting.
     *
     * - Computes average luminance and shifts brightness toward a
     *   target mid-bright value.
     * - Slightly boosts contrast and saturation.
     * - Applies a mild per-channel white balance correction based on
     *   average channel values (gray-world assumption).
     */
    private fun autoLightingCorrection(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Sample for performance on large images
        val sampleStep = maxOf(1, (width * height) / 50000)
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

        // Target brightness for a "studio" look
        val targetLum = 180f
        val brightnessShift = (targetLum - avgLum).coerceIn(-60f, 80f)

        // Gray world white balance: scale each channel so averages match overall gray average
        val grayAvg = (avgR + avgG + avgB) / 3f
        val rGain = (grayAvg / avgR).coerceIn(0.85f, 1.2f)
        val gGain = (grayAvg / avgG).coerceIn(0.85f, 1.2f)
        val bGain = (grayAvg / avgB).coerceIn(0.85f, 1.2f)

        val contrast = 1.12f // mild contrast boost
        val saturation = 1.08f // mild saturation boost

        // Build combined color matrix: white balance gains -> contrast -> brightness
        val cm = ColorMatrix()

        // Saturation matrix
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(saturation)

        // White balance + contrast + brightness matrix
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
