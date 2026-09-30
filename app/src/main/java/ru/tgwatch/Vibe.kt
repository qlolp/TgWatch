package ru.tgwatch

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Вибросигнал «связь пропала»: три длинных импульса. */
object Vibe {

    private val PATTERN = longArrayOf(0, 700, 300, 700, 300, 700)

    fun alarm(ctx: Context) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return

        val effect = VibrationEffect.createWaveform(PATTERN, -1)
        // Помечаем вибрацию как «будильник»: так Android не глушит её,
        // когда приложение работает в фоне.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, attrs)
        }
    }
}
