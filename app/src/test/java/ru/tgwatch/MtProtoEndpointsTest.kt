package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

/** A catalog without an address family reports false outages on networks that only have the other. */
class MtProtoEndpointsTest {
    @Test fun catalogCoversBothAddressFamilies() {
        val (v6, v4) = MtProtoEndpoints.all.partition {
            InetAddress.getByName(it.address) is Inet6Address
        }
        assertEquals("One IPv4 and one IPv6 address per DC", v4.size, v6.size)
        assertTrue("Expected every production DC in both families", v4.size >= 5)
    }

    @Test fun endpointNamesAreUniqueAndPairUpByDataCentre() {
        val names = MtProtoEndpoints.all.map { it.name }
        assertEquals("Probe results are matched by endpoint name", names.size, names.toSet().size)
        val v4Dcs = MtProtoEndpoints.all.filterNot { InetAddress.getByName(it.address) is Inet6Address }
            .map { it.name.removePrefix("MTProto ") }
        val v6Dcs = MtProtoEndpoints.all.filter { InetAddress.getByName(it.address) is Inet6Address }
            .map { it.name.removePrefix("MTProto ").removeSuffix(" (IPv6)") }
        assertEquals("Both families cover the same data centres", v4Dcs.toSet(), v6Dcs.toSet())
    }

    @Test fun addressesArePlainTextLiterals() {
        MtProtoEndpoints.all.forEach {
            val address = InetAddress.getByName(it.address)
            assertTrue("${it.name} must not trigger DNS", it.address == address.hostAddress ||
                it.address.contains(':'))
            assertEquals(it.group, ProbeGroup.MTPROTO)
        }
    }
}
