package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    @Test fun missingPortableRestoreImplementation() {
        // Initial RED must run on Android before the store is added.
        Class.forName("ru.tgwatch.BackupStore")
    }
}
