package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeCatalogTest {
    @Test
    fun telegramEndpoints_includeMtProtoAndWeb() {
        val endpoints = ProbeCatalog.telegramEndpoints()
        assertTrue(endpoints.any { it.group == ProbeGroup.WEB })
        assertTrue(endpoints.any { it.group == ProbeGroup.MTPROTO })
        assertTrue(endpoints.any { it.address == "149.154.167.51" || it.name.contains("DC2") })
    }

    @Test
    fun controlEndpoints_areControlGroup() {
        assertTrue(ProbeCatalog.controlEndpoints().all { it.group == ProbeGroup.CONTROL })
    }

    @Test
    fun extraService_titlesLocalized() {
        Lang.ru = true
        assertEquals("Matrix", ExtraService.MATRIX.title())
        Lang.ru = false
        assertEquals("Custom URL", ExtraService.CUSTOM.title())
    }
}
