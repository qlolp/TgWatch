package ru.tgwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MaintenanceScheduleTest {
    private val everyDay = MaintenanceWindow("all", startMinuteOfDay = 8 * 60, endMinuteOfDay = 10 * 60)
    private val overnight = MaintenanceWindow("night", startMinuteOfDay = 23 * 60, endMinuteOfDay = 7 * 60)
    private val monOnly = MaintenanceWindow("mon", 0, 1440, daysMask = 0b0000001)

    @Test fun halfOpenMinuteBoundaries() {
        assertTrue(MaintenanceSchedule.active(listOf(everyDay), 8 * 60))
        assertTrue(MaintenanceSchedule.active(listOf(everyDay), 10 * 60 - 1))
        assertFalse(MaintenanceSchedule.active(listOf(everyDay), 10 * 60))
        assertFalse(MaintenanceSchedule.active(listOf(everyDay), 8 * 60 - 1))
    }

    @Test fun overnightWindowCrossesMidnight() {
        assertTrue(MaintenanceSchedule.active(listOf(overnight), 23 * 60))
        assertTrue(MaintenanceSchedule.active(listOf(overnight), 0))
        assertTrue(MaintenanceSchedule.active(listOf(overnight), 7 * 60 - 1))
        assertFalse(MaintenanceSchedule.active(listOf(overnight), 7 * 60))
        assertFalse(MaintenanceSchedule.active(listOf(overnight), 12 * 60))
    }

    @Test fun dayMaskSelectsWeekdaysMondayZero() {
        assertTrue(MaintenanceSchedule.active(listOf(monOnly), 0)) // Monday 00:00
        assertTrue(MaintenanceSchedule.active(listOf(monOnly), 60))
        assertFalse(MaintenanceSchedule.active(listOf(monOnly), 1440)) // Tuesday
        val sun = MaintenanceWindow("sun", 0, 60, daysMask = 0b1000000)
        assertTrue(MaintenanceSchedule.active(listOf(sun), 6 * 1440))
        assertFalse(MaintenanceSchedule.active(listOf(sun), 5 * 1440))
    }

    @Test fun disabledOrEqualEndpointsNeverActivate() {
        val off = everyDay.copy(enabled = false)
        assertFalse(MaintenanceSchedule.active(listOf(off), 8 * 60))
        val empty = MaintenanceWindow("eq", 10, 10)
        assertFalse(MaintenanceSchedule.active(listOf(empty), 10))
    }

    @Test fun zeroMaskMeansEveryDayAndEmptyListStaysQuiet() {
        assertFalse(MaintenanceSchedule.active(emptyList(), 0))
        assertTrue(MaintenanceSchedule.active(listOf(everyDay.copy(daysMask = 0)), 8 * 60))
    }

    @Test fun rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException::class.java) { MaintenanceWindow("", 0, 60) }
        assertThrows(IllegalArgumentException::class.java) { MaintenanceWindow("x", -1, 60) }
        assertThrows(IllegalArgumentException::class.java) { MaintenanceWindow("x", 0, 1441) }
        assertThrows(IllegalArgumentException::class.java) { MaintenanceWindow("x", 0, 60, daysMask = 128) }
    }
}
