package com.prism.music

import com.prism.music.connect.Lan
import com.prism.music.connect.LinkReach
import com.prism.music.connect.LinkReach.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * A phone reaching a Prism TV on the same Wi-Fi (1.8.1, Reddit: "Dr.'s Google TV Streamer" listed, "One moment…", then
 * "Couldn't reach … Is Prism open there, on the same Wi-Fi?"). Every address the TV answers on is kept, the likeliest
 * first, and when none answers the listener is told what went wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LinkReachTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private val phone = listOf(ip("192.168.1.42") to 24, ip("fe80::aa:1") to 64)

    @Test fun theTvsAddressOnThisWifiIsTriedFirstAndLinkLocalLast() {
        // As a TV with Wi-Fi, Ethernet and a Thread radio answers (in the order Android's lookup gave them).
        val resolved = listOf(ip("fe80::1234"), ip("fd11:22::5"), ip("169.254.3.4"), ip("10.0.0.9"), ip("192.168.1.216"))
        val order = LinkReach.order(resolved, emptyList(), phone)
        assertEquals("192.168.1.216", order[0])
        assertEquals("10.0.0.9", order[1])
        assertEquals(ip("fd11:22::5").hostAddress, order[2])
        assertEquals("169.254.3.4", order[3])
        assertEquals(ip("fe80::1234").hostAddress, order[4])
    }

    @Test fun aPhoneToldOnlyAnIpv6AddressStillHasTheTvsIpv4() {
        // Android 13 and older: one address from the lookup, an IPv6 link-local one; the TV says its IPv4 ones itself.
        val order = LinkReach.order(listOf(ip("fe80::1234")), listOf("192.168.1.216", "not an address", "300.1.1.1"), phone)
        assertEquals("192.168.1.216", order.first())
        assertEquals(2, order.size)
    }

    @Test fun anIpv6LinkLocalAddressIsGivenAScope() {
        var asked = 0
        val order = LinkReach.order(listOf(ip("fe80::1234")), emptyList(), phone) { a -> asked++; java.net.Inet6Address.getByAddress("x", a.address, 7) }
        assertEquals(1, asked)
        assertTrue(order.single(), order.single().endsWith("%7"))
    }

    @Test fun repeatsAndThisDevicesOwnKindsOfAddressAreLeftOut() {
        val order = LinkReach.order(listOf(ip("192.168.1.216"), ip("127.0.0.1"), ip("0.0.0.0")), listOf("192.168.1.216"), phone)
        assertEquals(listOf("192.168.1.216"), order)
    }

    @Test fun onlyWrittenOutAddressesAreTaken() {
        assertEquals(ip("192.168.1.216"), LinkReach.literal(" 192.168.1.216 "))
        assertNull(LinkReach.literal("tv.local"))
        assertNull(LinkReach.literal("1.2.3"))
        assertNull(LinkReach.literal("256.1.1.1"))
    }

    @Test fun theSameNetworkByItsPrefix() {
        assertTrue(Lan.sameNetwork(ip("192.168.1.216"), phone))
        assertFalse(Lan.sameNetwork(ip("192.168.2.216"), phone))
        // A router handing out addresses that aren't private ones (some do): still this Wi-Fi.
        assertTrue(Lan.sameNetwork(ip("100.64.0.20"), listOf(ip("100.64.0.7") to 24)))
        assertFalse(Lan.sameNetwork(ip("100.64.0.20"), listOf(ip("fe80::1") to 64)))
    }

    @Test fun howAnAddressDidntAnswer() {
        assertEquals(Failure.TIMEOUT, LinkReach.failure(SocketTimeoutException("failed to connect to /192.168.1.216 (port 47321) after 3000ms")))
        assertEquals(Failure.REFUSED, LinkReach.failure(ConnectException("failed to connect to /192.168.1.216 (port 47321) from /192.168.1.42 (port 40000) after 3000ms: isConnected failed: ECONNREFUSED (Connection refused)")))
        assertEquals(Failure.NO_NETWORK, LinkReach.failure(ConnectException("failed to connect to /fd11::5 (port 47321) after 3000ms: connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(Failure.NO_HOST, LinkReach.failure(NoRouteToHostException("failed to connect to /192.168.1.216 (port 47321): isConnected failed: EHOSTUNREACH (No route to host)")))
    }

    @Test fun theListenerIsToldWhatWentWrong() {
        val tv = "Dr.'s Google TV Streamer"
        // The TV answered, Prism didn't: open Prism there.
        assertEquals(tr(R.string.link_refused, tv), LinkReach.why(tv, listOf(Failure.TIMEOUT, Failure.REFUSED)))
        // Found on this Wi-Fi, yet nothing answers: asleep or closed, or a Wi-Fi that keeps devices apart.
        val noAnswer = LinkReach.why(tv, listOf(Failure.TIMEOUT, Failure.NO_HOST))
        assertEquals(tr(R.string.link_no_answer, tv), noAnswer)
        assertTrue(noAnswer, noAnswer.contains("isolation"))
        // Only addresses of networks this phone isn't on.
        assertEquals(tr(R.string.link_other_network, tv), LinkReach.why(tv, listOf(Failure.NO_NETWORK, Failure.NO_NETWORK)))
        // Nothing to try at all.
        assertEquals(tr(R.string.couldn_t_reach_is_prism_open, tv), LinkReach.why(tv, emptyList()))
    }
}
