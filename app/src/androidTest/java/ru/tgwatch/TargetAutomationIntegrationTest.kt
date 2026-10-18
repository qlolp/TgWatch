package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetAutomationIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var saved: Map<String, *>
    private lateinit var credentials: Map<String, *>
    private lateinit var token: String
    @Before fun prepare() {
        HistoryTestStorage.awaitReady()
        saved = Prefs.sp(ctx).all.toMap()
        credentials = ctx.getSharedPreferences(AutomationSettings.FILE, Context.MODE_PRIVATE).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        check(ctx.getSharedPreferences(AutomationSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit())
        check(AutomationSettings.setEnabled(ctx, true))
        token = checkNotNull(AutomationSettings.token(ctx))
        AutomationReceiver.invalidateState()
    }
    @After fun clean() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::saved.isInitialized) restore(Prefs.sp(ctx), saved)
        if (::credentials.isInitialized) restore(ctx.getSharedPreferences(AutomationSettings.FILE, Context.MODE_PRIVATE), credentials)
        AutomationReceiver.invalidateState()
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }

    @Test fun v2RejectsUnknownTargetAndBadTokenWithoutState() {
        val unknown = request(TargetAutomation.ACTION_GET_TARGET_STATE, "untrusted")
        assertEquals(AutomationReceiver.RESULT_INVALID, unknown.first)
        assertNull(unknown.second)
        val unauthorized = request(TargetAutomation.ACTION_GET_TARGET_STATE, "matrix", "0".repeat(64))
        assertEquals(AutomationReceiver.RESULT_UNAUTHORIZED, unauthorized.first)
        assertNull(unauthorized.second)
        val missing = request(TargetAutomation.ACTION_GET_TARGET_STATE, null)
        assertEquals(AutomationReceiver.RESULT_INVALID, missing.first)
        assertNull(missing.second)
    }

    @Test fun v2RepliesUseOnlyRequestedGoalAndNeverBorrowLegacyCache() {
        val now = System.currentTimeMillis()
        TargetStateStore.persist(ctx, TargetSnapshot(TargetId.MATRIX, TargetStatus.DOWN, now, latencyMs = 50))
        TargetStateStore.persist(ctx, TargetSnapshot(TargetId.WHATSAPP, TargetStatus.OK, now, latencyMs = 20))
        val legacy = request(AutomationReceiver.ACTION_GET_STATE, "matrix")
        assertEquals(AutomationReceiver.RESULT_OK, legacy.first)
        assertEquals(1, legacy.second!!.getInt("api_version"))
        assertFalse(legacy.second!!.containsKey("target_id"))
        val matrix = request(TargetAutomation.ACTION_GET_TARGET_STATE, "matrix")
        assertEquals(AutomationReceiver.RESULT_OK, matrix.first)
        assertEquals("matrix", matrix.second!!.getString("target_id"))
        assertEquals(2, matrix.second!!.getInt("api_version"))
        assertEquals("DOWN", matrix.second!!.getString("status"))
        assertEquals(50L, matrix.second!!.getLong("latency_ms"))
        assertFalse(matrix.second!!.getBoolean("running"))
        assertTrue(matrix.second!!.getBoolean("stale"))
        val whatsapp = request(TargetAutomation.ACTION_GET_TARGET_STATE, "whatsapp")
        assertEquals(AutomationReceiver.RESULT_OK, whatsapp.first)
        assertEquals("OK", whatsapp.second!!.getString("status"))
        assertEquals("whatsapp", whatsapp.second!!.getString("target_id"))
    }

    @Test fun v1IsStoppedWhileMatrixKeepsTheCommonServiceRunning() {
        check(Prefs.sp(ctx).edit().putBoolean("enabled", true)
            .putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), false)
            .putBoolean(TargetPreferences.key(TargetId.MATRIX, "enabled"), true).commit())
        MonitorService.start(ctx)
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!MonitorService.running && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue(MonitorService.running)
        assertEquals(AutomationReceiver.RESULT_STOPPED, request(AutomationReceiver.ACTION_CHECK_NOW, "matrix").first)
        val legacy = request(AutomationReceiver.ACTION_GET_STATE, "matrix")
        assertEquals(AutomationReceiver.RESULT_OK, legacy.first)
        assertFalse(legacy.second!!.getBoolean("running"))
        assertTrue(legacy.second!!.getBoolean("stale"))
        assertNull(AutomationSettings.lastCheckElapsed(ctx))
        assertEquals(AutomationReceiver.RESULT_OK, request(TargetAutomation.ACTION_CHECK_TARGET_NOW, "matrix").first)
        assertEquals(AutomationReceiver.RESULT_RATE_LIMITED,
            request(TargetAutomation.ACTION_CHECK_TARGET_NOW, "matrix").first)
        assertNotNull(AutomationSettings.lastCheckElapsed(ctx, TargetId.MATRIX))
        assertNull(AutomationSettings.lastCheckElapsed(ctx))
    }

    @Test fun disabledV2CheckDoesNotStartServiceOrConsumeItsThrottle() {
        assertEquals(AutomationReceiver.RESULT_STOPPED, request(TargetAutomation.ACTION_CHECK_TARGET_NOW, "custom").first)
        assertNull(AutomationSettings.lastCheckElapsed(ctx, TargetId.CUSTOM))
        assertFalse(MonitorService.running)
    }

    @Test fun stopImmediatelyInvalidatesAnAlreadyCachedRunningTarget() {
        check(Prefs.sp(ctx).edit().putBoolean("enabled", true)
            .putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), true)
            .putBoolean(TargetPreferences.key(TargetId.MATRIX, "enabled"), true).commit())
        MonitorService.start(ctx)
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!MonitorService.running && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue(MonitorService.running)
        val active = request(TargetAutomation.ACTION_GET_TARGET_STATE, "matrix")
        assertEquals(AutomationReceiver.RESULT_OK, active.first)
        assertTrue(active.second!!.getBoolean("running"))
        TargetPreferences.stop(ctx, TargetId.MATRIX)
        var stopped = request(TargetAutomation.ACTION_GET_TARGET_STATE, "matrix")
        // Cache invalidation does not release a finishing compute's physical
        // slot. A busy response may contain no state; a successful response must
        // already describe the stopped target, even on the first request.
        val stopDeadline = SystemClock.elapsedRealtime() + 2_000
        while (stopped.first == AutomationReceiver.RESULT_RATE_LIMITED &&
            SystemClock.elapsedRealtime() < stopDeadline) {
            assertNull("Throttled reply must not expose the invalidated state", stopped.second)
            SystemClock.sleep(25)
            stopped = request(TargetAutomation.ACTION_GET_TARGET_STATE, "matrix")
        }
        assertEquals(AutomationReceiver.RESULT_OK, stopped.first)
        assertFalse(stopped.second!!.getBoolean("running"))
        assertTrue(stopped.second!!.getBoolean("stale"))
        assertTrue(TargetPreferences.definition(ctx, TargetId.TELEGRAM).enabled)
    }

    private fun request(action: String, target: String?, supplied: String = token): Pair<Int, Bundle?> {
        val done = CountDownLatch(1)
        var reply: Pair<Int, Bundle?>? = null
        ctx.sendOrderedBroadcast(Intent(ctx, AutomationReceiver::class.java).setAction(action)
            .putExtra(AutomationReceiver.EXTRA_TOKEN, supplied).putExtra(TargetAutomation.EXTRA_TARGET_ID, target),
            null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    reply = resultCode to getResultExtras(false); done.countDown()
                }
            }, Handler(Looper.getMainLooper()), -1, null, null)
        assertTrue("Ordered API response timed out", done.await(12, TimeUnit.SECONDS))
        return checkNotNull(reply)
    }
    private fun restore(store: SharedPreferences, saved: Map<String, *>) {
        val edit = store.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(edit.commit())
    }
}
