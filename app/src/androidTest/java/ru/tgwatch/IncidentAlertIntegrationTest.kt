package ru.tgwatch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncidentAlertIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)
    private var previous: Boolean? = null

    @Before fun isolate() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        previous = Prefs.sp(ctx).all["group_incident_alerts"] as? Boolean
        IncidentAlertPreferences.setEnabled(ctx, false)
        IncidentAlertCoordinator.resetForTests()
        clear()
        EventNotifications(ctx, manager)
    }

    @After fun restore() {
        IncidentAlertPreferences.setEnabled(ctx, false)
        clear()
        val edit = Prefs.sp(ctx).edit()
        if (previous == null) edit.remove("group_incident_alerts") else edit.putBoolean("group_incident_alerts",
            previous == true)
        assertTrue(edit.commit())
        IncidentAlertCoordinator.resetForTests()
    }

    @Test fun defaultUpgradeBehaviorKeepsIndependentCards() {
        assertFalse(IncidentAlertPreferences.enabled(ctx))
        EventNotifications(ctx, manager).outage("Telegram", false, true)
        EventNotifications(ctx, manager, targetId = TargetId.MATRIX).outage("Matrix", false, true)
        await { notification(TargetEventNotifications.id(TargetId.TELEGRAM, "outage")) != null &&
            notification(TargetEventNotifications.id(TargetId.MATRIX, "outage")) != null }
        assertNull(notification(IncidentAlertCoordinator.GROUP_ID))
    }

    @Test fun secondTargetReplacesSinglesWithOnePrivacySafeSummary() {
        IncidentAlertPreferences.setEnabled(ctx, true)
        EventNotifications(ctx, manager).outage("Telegram", false, true)
        EventNotifications(ctx, manager, targetId = TargetId.MATRIX).outage("Matrix", false, true)
        val group = awaitNotification(IncidentAlertCoordinator.GROUP_ID)
        awaitAbsent(TargetEventNotifications.id(TargetId.TELEGRAM, "outage"))
        awaitAbsent(TargetEventNotifications.id(TargetId.MATRIX, "outage"))
        val text = group.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains(TargetNames.label(ctx, TargetId.TELEGRAM)))
        assertTrue(text.contains(TargetNames.label(ctx, TargetId.MATRIX)))
        assertFalse(text.contains("https://"))
    }

    @Test fun recoveryBelowTwoTargetsCancelsSummaryWithoutResurrectingStaleSingles() {
        IncidentAlertPreferences.setEnabled(ctx, true)
        val telegram = EventNotifications(ctx, manager)
        val matrix = EventNotifications(ctx, manager, targetId = TargetId.MATRIX)
        telegram.outage("Telegram", false, true)
        matrix.outage("Matrix", false, true)
        awaitNotification(IncidentAlertCoordinator.GROUP_ID)
        matrix.clearOutage()
        awaitAbsent(IncidentAlertCoordinator.GROUP_ID)
        assertNull(notification(TargetEventNotifications.id(TargetId.TELEGRAM, "outage")))
        assertNull(notification(TargetEventNotifications.id(TargetId.MATRIX, "outage")))
    }

    @Test fun optingOutCancelsSummaryAndRestoresNormalFuturePresentation() {
        IncidentAlertPreferences.setEnabled(ctx, true)
        val telegram = EventNotifications(ctx, manager)
        telegram.outage("Telegram", false, true)
        EventNotifications(ctx, manager, targetId = TargetId.MATRIX).outage("Matrix", false, true)
        awaitNotification(IncidentAlertCoordinator.GROUP_ID)
        IncidentAlertPreferences.setEnabled(ctx, false)
        awaitAbsent(IncidentAlertCoordinator.GROUP_ID)
        EventNotifications(ctx, manager, targetId = TargetId.WHATSAPP).outage("WhatsApp", false, true)
        awaitNotification(TargetEventNotifications.id(TargetId.WHATSAPP, "outage"))
    }

    private fun notification(id: Int): Notification? = manager.activeNotifications.firstOrNull { it.id == id }
        ?.notification
    private fun awaitNotification(id: Int): Notification {
        await { notification(id) != null }
        return notification(id)!!
    }
    private fun awaitAbsent(id: Int) = await { notification(id) == null }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue(condition())
    }
    private fun clear() {
        manager.cancel(IncidentAlertCoordinator.GROUP_ID)
        TargetId.entries.forEach { id -> listOf("outage", "partial", "recovery").forEach {
            manager.cancel(TargetEventNotifications.id(id, it))
        } }
    }
}
