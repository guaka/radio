package org.guaka.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class AssistantDiscoveryTest {
    @Test fun displaysAdvertisedFriendlyNameInsteadOfServiceId() {
        val result = parseDiscoveredAssistant("opaque-id", "_mass._tcp.", InetAddress.getByName("192.168.1.42"), 8095,
            mapOf("server_id" to "opaque-id".toByteArray(), "name" to "Home Music Assistant".toByteArray()))
        assertEquals("Home Music Assistant", result?.name)
    }

    @Test fun parsesMusicAssistantTxtIdAndBuildsUrlFromResolvedEndpoint() {
        val result = parseDiscoveredAssistant(
            "Living Room MA",
            "_mass._tcp.local.",
            InetAddress.getByName("192.168.1.42"),
            8095,
            mapOf(
                "server_id" to "ma-server-1".toByteArray(),
                // Discovery metadata may advertise URLs. The local candidate uses the resolved
                // address and port so a TXT value cannot redirect authentication elsewhere.
                "base_url" to "https://attacker.example".toByteArray(),
            ),
        )

        assertEquals(DiscoveredAssistant("Living Room MA", "http://192.168.1.42:8095", "ma-server-1"), result)
    }

    @Test fun ignoresOtherServicesAndIncompleteEndpoints() {
        val host = InetAddress.getByName("192.168.1.42")
        val validId = mapOf("server_id" to "server-1".toByteArray())
        assertNull(parseDiscoveredAssistant("Printer", "_ipp._tcp.local.", host, 631, validId))
        assertNull(parseDiscoveredAssistant("Fake MA", "_mass._tcp.evil.", host, 8095, validId))
        assertNull(parseDiscoveredAssistant("MA", "_mass._tcp.local.", host, 0, validId))
        assertNull(parseDiscoveredAssistant("MA", "_mass._tcp.local.", host, 8095, emptyMap()))
        assertNull(parseDiscoveredAssistant("MA", "_mass._tcp.local.", null, 8095, validId))
        assertNull(parseDiscoveredAssistant("Public", "_mass._tcp.local.", InetAddress.getByName("203.0.113.9"), 8095, validId))
    }

    @Test fun bracketsResolvedIpv6AddressForUrl() {
        val result = parseDiscoveredAssistant(
            "MA", "_mass._tcp.local.", InetAddress.getByName("fd00::12"), 8095,
            mapOf("server_id" to "server-1".toByteArray()),
        )
        assertEquals("http://[fd00::12]:8095", result?.url)
    }
}
