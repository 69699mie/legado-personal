package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import coil3.size.Size
import coil3.transform.Transformation

internal class MangaEInkTransformation(private val threshold: Int) : Transformation() {
    override val cacheKey: String = "manga-eink-v1-$threshold"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val result = input.filteredBitmap(ColorMatrix().apply { setSaturation(0f) })
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        pixels.indices.forEach { index ->
            pixels[index] = if (Color.red(pixels[index]) < threshold) Color.BLACK else Color.WHITE
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }
}

/**
 * 3×3 反锐化掩模：以原图为基础减去邻域均值，恢复被 Coil 降采样削掉的边缘。
 *
 * strength 0..100 映射到卷积强度 0..100；分母取 400 让整个卷积用整数运算完成。
 * 边界像素直接沿用原值，避免加黑边。
 */
internal class MangaSharpenTransformation(private val strength: Int) : Transformation() {
    override val cacheKey: String = "manga-sharpen-v1-$strength"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 3 || h < 3) return input
        val kInt = strength.coerceIn(0, 100)
        if (kInt == 0) return input
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        // 复制边界
        for (x in 0 until w) {
            out[x] = pixels[x]
            out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            out[y * w] = pixels[y * w]
            out[y * w + w - 1] = pixels[y * w + w - 1]
        }
        val centerWeight = 400 + 4 * kInt
        for (y in 1 until h - 1) {
            val rowBase = y * w
            for (x in 1 until w - 1) {
                val i = rowBase + x
                val center = pixels[i]
                val up = pixels[i - w]
                val down = pixels[i + w]
                val left = pixels[i - 1]
                val right = pixels[i + 1]

                val r = sharpenChannel(
                    (center shr 16) and 0xFF,
                    (up shr 16) and 0xFF, (down shr 16) and 0xFF,
                    (left shr 16) and 0xFF, (right shr 16) and 0xFF,
                    centerWeight, kInt,
                )
                val g = sharpenChannel(
                    (center shr 8) and 0xFF,
                    (up shr 8) and 0xFF, (down shr 8) and 0xFF,
                    (left shr 8) and 0xFF, (right shr 8) and 0xFF,
                    centerWeight, kInt,
                )
                val b = sharpenChannel(
                    center and 0xFF,
                    up and 0xFF, down and 0xFF,
                    left and 0xFF, right and 0xFF,
                    centerWeight, kInt,
                )
                val a = (center ushr 24) and 0xFF
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun sharpenChannel(
        center: Int, up: Int, down: Int, left: Int, right: Int,
        centerWeight: Int, kInt: Int,
    ): Int {
        val numerator = center * centerWeight - kInt * (up + down + left + right)
        val v = numerator / 400
        return when {
            v < 0 -> 0
            v > 255 -> 255
            else -> v
        }
    }
}

private fun Bitmap.filteredBitmap(matrix: ColorMatrix): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(output).drawBitmap(this, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
    })
    return output
}
