package com.grandma.rumorguard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object ImageEncoder {
    /** ~1280 px on the long side keeps subtitles readable at roughly 700 image tokens. */
    private const val MAX_SIDE = 1280
    private const val QUALITY = 70

    /** Downscales, blacks out [mask] (our own overlay, in source pixels) and returns a JPEG data URL. */
    fun toJpegDataUrl(src: Bitmap, mask: Rect?): String {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * scale).roundToInt()
        val h = (src.height * scale).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        if (mask != null) {
            val black = Paint().apply { color = Color.BLACK }
            canvas.drawRect(mask.left * scale, mask.top * scale, mask.right * scale, mask.bottom * scale, black)
        }
        val bytes = ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
            bos.toByteArray()
        }
        out.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
