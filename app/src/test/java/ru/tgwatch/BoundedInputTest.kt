package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedInputTest {
    @Test fun exactBoundAndShortReadsPreserveBytes() {
        val bytes = ByteArray(1024) { it.toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }
        assertArrayEquals(bytes, BoundedInput.read(input, bytes.size))
    }
    @Test fun oversizeStreamStopsAtFirstExcessByte() {
        var read = 0
        val input = object : InputStream() { override fun read(): Int { read++; return 7 } }
        try { BoundedInput.read(input, 100); fail("Must reject over limit") }
        catch (_: IllegalArgumentException) { assertEquals(101, read) }
    }
    @Test fun zeroLengthReadDoesNotHangOrDropData() {
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), BoundedInput.read(input, 3))
    }
}
