package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Reaching another Prism on the Wi-Fi (1.8.1, Reddit: a phone listed "Dr.'s Google TV Streamer", said "One moment…",
 * then "Couldn't reach … Is Prism open there, on the same Wi-Fi?"). A Prism found on the Wi-Fi can answer on several
 * addresses (IPv4 and IPv6, its Wi-Fi and its Ethernet, a TV's Thread radio), and the phone kept just one: the first
 * IPv4 address, or on Android 13 and older the one address Android's lookup gave, often an IPv6 link-local one that can't
 * be connected to as it came. Now every address is kept, the likeliest first, and each is tried in turn; and when none
 * answers, the listener is told what actually went wrong.
 */
object LinkReach {
    /** How long one address gets to answer (a TV on the same Wi-Fi answers in milliseconds). */
    const val CONNECT_MS = 3_000
    /** Addresses tried at most, from one lookup. */
    const val MOST = 5

    /** How an address didn't answer. */
    enum class Failure {
        /** Something answered there, but nothing listens on Prism's port: Prism isn't open (or not taking connections). */
        REFUSED,
        /** Nothing came back in time (the device is asleep or gone, or the Wi-Fi keeps devices apart). */
        TIMEOUT,
        /** The device couldn't be found on the network the address belongs to (gone, or the Wi-Fi keeps devices apart). */
        NO_HOST,
        /** This phone has no network the address belongs to (an IPv6 address, another network's). */
        NO_NETWORK,
        OTHER,
    }

    fun failure(t: Throwable): Failure {
        val m = t.message.orEmpty()
        return when {
            t is SocketTimeoutException || "ETIMEDOUT" in m -> Failure.TIMEOUT
            "ECONNREFUSED" in m -> Failure.REFUSED
            "ENETUNREACH" in m || "EADDRNOTAVAIL" in m || "EINVAL" in m -> Failure.NO_NETWORK
            t is NoRouteToHostException || "EHOSTUNREACH" in m -> Failure.NO_HOST
            t is ConnectException -> Failure.OTHER
            else -> Failure.OTHER
        }
    }

    /**
     * The addresses to try for a Prism, the likeliest first: an IPv4 address on this phone's own network, any other
     * private IPv4 one, IPv6 ones (unique-local or global), then the rest, IPv6 link-local last (given this phone's Wi-Fi
     * as their scope, [scoped], without which they can't be connected to). [resolved]: what the lookup gave;
     * [advertised]: the IPv4 addresses the Prism itself says it has (its "a" attribute, for phones whose lookup gives
     * only one, IPv6, address); [own]: this phone's addresses and prefix lengths.
     */
    fun order(
        resolved: List<InetAddress>,
        advertised: List<String>,
        own: List<Pair<InetAddress, Int>>,
        scoped: (Inet6Address) -> InetAddress = { it },
    ): List<String> {
        val all = (resolved + advertised.mapNotNull { literal(it) }).map { a ->
            if (a is Inet6Address && a.isLinkLocalAddress && a.scopeId == 0 && a.scopedInterface == null) scoped(a) else a
        }
        return all.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
            .sortedBy { rank(it, own) }
            .mapNotNull { it.hostAddress }
            .distinct()
            .take(MOST)
    }

    private fun rank(a: InetAddress, own: List<Pair<InetAddress, Int>>): Int = when {
        a is Inet4Address && Lan.sameNetwork(a, own) -> 0
        a is Inet4Address && a.isSiteLocalAddress -> 1
        a is Inet6Address && !a.isLinkLocalAddress -> 2
        a is Inet4Address -> 3
        else -> 4
    }

    /** An IPv4 address written out ("192.168.1.20"), never a name to look up; null otherwise. */
    fun literal(s: String): InetAddress? {
        val parts = s.trim().split('.')
        if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.length > 3 || p.any { !it.isDigit() } || p.toInt() > 255 }) return null
        return runCatching { InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()) }.getOrNull()
    }

    /** What the listener is told when none of a Prism's addresses ([failures], one each) answered: what went wrong. */
    fun why(name: String, failures: List<Failure>): String = when {
        failures.isEmpty() -> tr(R.string.couldn_t_reach_is_prism_open, name)
        // Something there said no: the TV's own system answered, Prism didn't.
        Failure.REFUSED in failures -> tr(R.string.link_refused, name)
        failures.all { it == Failure.NO_NETWORK } -> tr(R.string.link_other_network, name)
        // Found on this Wi-Fi a moment ago, yet nothing answers: asleep or closed, or a Wi-Fi that keeps devices apart.
        failures.any { it == Failure.TIMEOUT || it == Failure.NO_HOST } -> tr(R.string.link_no_answer, name)
        else -> tr(R.string.couldn_t_reach_is_prism_open, name)
    }
}
