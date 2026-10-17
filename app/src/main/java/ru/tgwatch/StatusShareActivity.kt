package ru.tgwatch

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** The user reviews one frozen card; sending it never captures a newer measurement. */
class StatusShareActivity : Activity() {
    private var target = TargetId.TELEGRAM
    private var request = 0L
    private var card: PreparedStatusCard? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        target = TargetId.fromWire(intent.getStringExtra(TargetAutomation.EXTRA_TARGET_ID).orEmpty())
            ?: TargetId.TELEGRAM
        setContentView(R.layout.activity_share_status)
        ScreenInsets.apply(this, findViewById(R.id.shareStatusRoot))
        findViewById<Button>(R.id.shareStatusText).setOnClickListener { send(false) }
        findViewById<Button>(R.id.shareStatusPng).setOnClickListener { send(true) }
        findViewById<Button>(R.id.shareStatusRetry).setOnClickListener { capture() }
        findViewById<Button>(R.id.shareStatusDone).setOnClickListener { finish() }
        val retained = lastNonConfigurationInstance as? PreparedStatusCard
        if (retained == null) capture() else bind(retained)
    }
    private fun capture() {
        request++
        findViewById<Button>(R.id.shareStatusText).isEnabled = false
        findViewById<Button>(R.id.shareStatusPng).isEnabled = false
        findViewById<TextView>(R.id.shareStatusPreview).setText(R.string.history_loading)
        if (!StatusShareJobs.submit(this, target, request))
            findViewById<TextView>(R.id.shareStatusPreview).setText(R.string.share_status_busy)
    }
    internal fun accept(revision: Long, result: Result<PreparedStatusCard>): Boolean {
        if (revision != request) return false
        val value = result.getOrNull()
        if (value == null) findViewById<TextView>(R.id.shareStatusPreview).setText(R.string.history_load_failed)
        else bind(value)
        return value != null
    }
    private fun bind(value: PreparedStatusCard) {
        if (card !== value) card?.bitmap?.recycle()
        card = value
        findViewById<TextView>(R.id.shareStatusPreview).text = value.text
        findViewById<ImageView>(R.id.shareStatusImage).apply {
            visibility = if (value.bitmap == null) View.GONE else View.VISIBLE
            setImageBitmap(value.bitmap)
        }
        findViewById<Button>(R.id.shareStatusText).isEnabled = true
        findViewById<Button>(R.id.shareStatusPng).isEnabled = value.file != null && value.bitmap != null
    }
    private fun send(png: Boolean) {
        val frozen = card ?: return
        val intent = if (png) pngIntent(frozen) else Intent(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, frozen.text)
        if (intent != null) try { startActivity(Intent.createChooser(intent, getString(R.string.share_status_title))) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.incident_share_unavailable, Toast.LENGTH_LONG).show()
        }
    }
    private fun pngIntent(frozen: PreparedStatusCard): Intent? {
        val file = frozen.file
        return if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.share_status_expired, Toast.LENGTH_LONG).show()
            null
        } else {
            val uri = StatusShareCache.uri(this, file)
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).apply {
                clipData = ClipData.newRawUri("TgWatch status", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    @Deprecated("Activity retention API is used for the immutable preview in this Views screen")
    override fun onRetainNonConfigurationInstance(): Any? = card
    override fun onDestroy() {
        request++
        if (!isChangingConfigurations) card?.bitmap?.recycle()
        super.onDestroy()
    }
    companion object {
        fun open(ctx: Context, id: TargetId) {
            ctx.startActivity(Intent(ctx, StatusShareActivity::class.java)
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, id.wire).apply {
                    if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
        }
    }
}
