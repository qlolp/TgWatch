package ru.tgwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.IOException
import java.io.OutputStream

/** Text and PNG use precisely the same frozen labels. No live state or private source is consulted. */
internal object StatusCardRenderer {
    private const val WIDTH = 1024
    private const val HEIGHT = 768
    private const val MARGIN = 48f
    private const val TEXT_SIZE = 28f
    private const val TITLE_SIZE = 36f
    private const val LINE_HEIGHT = 38f
    private const val CHART_TOP = 600f
    private const val CHART_HEIGHT = 80f
    private const val PNG_QUALITY = 100
    private const val COLOR_OK = 0xff0878ad.toInt()
    private const val COLOR_DOWN = 0xffd32f2f.toInt()
    private const val COLOR_PARTIAL = 0xffb45300.toInt()
    private const val COLOR_BACKGROUND = 0xfff2f4f7.toInt()
    fun text(document: StatusShareDocument): String = document.text
    fun png(document: StatusShareDocument, output: OutputStream) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            draw(document, Canvas(bitmap))
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
                throw IOException("Cannot encode status card")
        } finally { bitmap.recycle() }
    }
    internal fun draw(document: StatusShareDocument, canvas: Canvas) {
        val snapshot = document.snapshot
        canvas.drawColor(COLOR_BACKGROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = TEXT_SIZE }
        paint.color = Color.WHITE
        canvas.drawRoundRect(MARGIN / 2, MARGIN / 2, WIDTH - MARGIN / 2, HEIGHT - MARGIN / 2,
            MARGIN / 2, MARGIN / 2, paint)
        var y = MARGIN * 2
        for ((index, label) in document.labels.withIndex()) {
            paint.color = if (index == 0) COLOR_OK else Color.BLACK
            paint.textSize = if (index == 0) TITLE_SIZE else TEXT_SIZE
            var rest = label
            while (rest.isNotEmpty()) {
                val count = paint.breakText(rest, true, WIDTH - MARGIN * 2, null).coerceAtLeast(1)
                canvas.drawText(rest.substring(0, count), MARGIN, y, paint)
                rest = rest.substring(count)
                y += LINE_HEIGHT
            }
        }
        paint.color = Color.LTGRAY
        canvas.drawRect(MARGIN, CHART_TOP, WIDTH - MARGIN, CHART_TOP + CHART_HEIGHT, paint)
        snapshot.chart.forEachIndexed { index, status ->
            paint.color = when (status) {
                TargetStatus.OK -> COLOR_OK
                TargetStatus.DOWN -> COLOR_DOWN
                TargetStatus.PARTIAL, TargetStatus.NO_NETWORK -> COLOR_PARTIAL
                TargetStatus.UNKNOWN -> Color.LTGRAY
            }
            val width = (WIDTH - MARGIN * 2) / snapshot.chart.size
            canvas.drawRect(MARGIN + index * width, CHART_TOP, MARGIN + (index + 1) * width,
                CHART_TOP + CHART_HEIGHT, paint)
        }
    }
}
