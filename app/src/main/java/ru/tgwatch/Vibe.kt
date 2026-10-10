package ru.tgwatch

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Вибросигналы: тревога при потере связи и короткий импульс при восстановлении. */
object Vibe {

    private val RECOVERY = longArrayOf(0, 120, 80, 120)

    fun alarm(ctx: Context) = play(ctx, Prefs.alarmPattern(ctx).timings())

    fun recovery(ctx: Context) = play(ctx, RECOVERY)

    private fun play(ctx: Context, pattern: LongArray) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return

        val effect = VibrationEffect.createWaveform(pattern, -1)
        // Помечаем вибрацию как «будильник»: так Android не глушит её в фоне.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, attrs)
        }
    }
}
