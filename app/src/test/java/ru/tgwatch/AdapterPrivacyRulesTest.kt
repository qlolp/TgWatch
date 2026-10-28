package ru.tgwatch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Broker, topic and credentials live in credential-protected files that platform backup must exclude. */
class AdapterPrivacyRulesTest {
    private val xml = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }

    @Test fun mqttPrivateFileIsExcludedFromEveryPlatformBackupPath() {
        val file = "${MqttSettings.FILE}.xml"
        assertTrue(File(xml, "automation_backup_rules.xml").readText().contains("path=\"$file\""))
        val extraction = File(xml, "automation_data_extraction_rules.xml").readText()
        assertTrue(extraction.split("path=\"$file\"").size == 3)
    }
}
