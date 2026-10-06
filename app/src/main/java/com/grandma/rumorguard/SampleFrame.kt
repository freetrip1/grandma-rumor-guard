package com.grandma.rumorguard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** A fabricated health-rumor video frame for the setup screen's "test AI" button. */
object SampleFrame {
    const val SCREEN_TEXT = "养生堂王医生 | 每天吃3个生鸡蛋，降血压比吃药还管用！转发给家人 | 关注 | 分享"

    fun dataUrl(): String {
        val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF37474F.toInt())
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            textSize = 64f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("震惊！医生不愿说的秘密", 360f, 300f, small)
        listOf("每天吃3个生鸡蛋", "降血压比吃药还管用", "高血压从此不用吃药").forEachIndexed { i, line ->
            canvas.drawText(line, 360f, 520f + i * 110f, big)
        }
        canvas.drawText("转发给家人，功德无量", 360f, 1000f, small)
        canvas.drawText("@养生堂王医生", 360f, 1180f, small)
        return ImageEncoder.toJpegDataUrl(bmp, null).also { bmp.recycle() }
    }
}
