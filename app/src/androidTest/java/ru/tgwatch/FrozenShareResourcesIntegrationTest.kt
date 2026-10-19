package ru.tgwatch

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class FrozenShareResourcesIntegrationTest {
    private fun context(language: String): Context {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        })
    }
    private fun snapshot(tag: String) = StatusShareSnapshot(TargetId.CUSTOM, TargetStatus.UNKNOWN,
        1000, 0, 180, true, false, null, null, emptyList(), 7, localeTag = tag)

    @Test fun pendingCaptureAndActualCanvasKeepOneLocaleAfterLanguageChanges() {
        val previous = Locale.getDefault()
        try {
            val pending = StatusShareJobs.captureContext(context("ru"))
            Locale.setDefault(Locale.ENGLISH)
            val doc = StatusShareDocument.prepare(context("en"), snapshot(pending.resources.configuration.locales[0].toLanguageTag()))
            assertTrue(doc.text.contains("Мониторинг остановлен"))
            assertTrue(doc.text.contains("Нет данных истории"))
            assertFalse(doc.text.contains("Monitoring stopped"))
            assertFalse(doc.text.contains("100%"))
            assertFalse(doc.text.contains("0%"))
            val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
            try {
                val drawn = mutableListOf<String>()
                val canvas = object : Canvas(bitmap) {
                    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                        drawn += text
                        super.drawText(text, x, y, paint)
                    }
                }
                StatusCardRenderer.draw(doc, canvas)
                assertEquals(doc.labels.joinToString(""), drawn.joinToString(""))
                assertEquals(doc.text, StatusCardRenderer.text(doc))
            } finally { bitmap.recycle() }
            val output = ByteArrayOutputStream()
            StatusCardRenderer.png(doc, output)
            val png = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())!!
            try { assertEquals(1024, png.width); assertEquals(768, png.height) } finally { png.recycle() }
            val next = StatusShareDocument.prepare(context("ru"), snapshot("en"))
            assertTrue(next.text.contains("Monitoring stopped"))
            assertFalse(next.text.contains("Мониторинг остановлен"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun documentLabelsAreImmutableAndNeverContainPrivateSourceFields() {
        val doc = StatusShareDocument.prepare(context("en"), snapshot("en"))
        for (secret in listOf("https://private.example/token", "192.0.2.10", "Bearer abc", "secret-ssid"))
            assertFalse(doc.text.contains(secret))
        try {
            (doc.labels as MutableList<String>).add("arbitrary label")
            fail("Prepared labels must be immutable")
        } catch (_: UnsupportedOperationException) { }
        assertTrue(StatusShareDocument::class.java.declaredConstructors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) || it.isSynthetic })
    }
    @Test fun shareControlsAndPreviewRemainAccessibleAtDoubleFontSize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val source = context("ru")
            val large = source.createConfigurationContext(Configuration(source.resources.configuration).apply { fontScale = 2f })
            val root = android.view.LayoutInflater.from(large).inflate(R.layout.activity_share_status, null)
            val preview = root.findViewById<android.widget.TextView>(R.id.shareStatusPreview)
            preview.text = StatusShareDocument.prepare(large, snapshot("ru")).text
            val width = (360 * large.resources.displayMetrics.density).toInt()
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            for (id in listOf(R.id.shareStatusText, R.id.shareStatusPng, R.id.shareStatusRetry, R.id.shareStatusDone)) {
                val button = root.findViewById<android.widget.Button>(id)
                assertTrue(button.height >= (48 * large.resources.displayMetrics.density).toInt())
                assertTrue(button.height >= button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom)
            }
            assertTrue(preview.text.contains("Мониторинг остановлен"))
            assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE, preview.accessibilityLiveRegion)
            assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                root.findViewById<android.widget.ImageView>(R.id.shareStatusImage).importantForAccessibility)
        }
    }
}
