package com.prism.music.connect

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The Prism link's security (1.8.0, plan-1.8.0 §4): what lets a phone send its sign-in to a TV across a home network.
 * - Pairing: an X25519 key agreement, confirmed by the six-digit code shown on the TV in twenty rounds of commitments
 *   (Bluetooth's passkey entry), so someone in the middle can't try codes offline: each wrong bit is caught live.
 * - Every connection after: fresh X25519 keys, both sides proving they hold the pairing's key, then every message
 *   sealed with AES-GCM under per-direction keys and a counter (a replayed, reordered or altered message is refused).
 */

/** X25519 (RFC 7748), in plain Kotlin: Android's own is only on 12 and later, and many TVs run 9 to 11. */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121_665)
    private val BASE = ByteArray(32).also { it[0] = 9 }
    private val random = SecureRandom()

    fun newPrivateKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    /** The shared secret of [privateKey] and the other side's [publicKey]; null for a key that would make it all zeros. */
    fun shared(privateKey: ByteArray, publicKey: ByteArray): ByteArray? =
        scalarMult(privateKey, publicKey).takeUnless { s -> s.all { it == 0.toByte() } }

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = scalar.copyOf().also { it[0] = (it[0].toInt() and 248).toByte(); it[31] = ((it[31].toInt() and 127) or 64).toByte() }
        val x1 = decode(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() })
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private fun decode(le: ByteArray) = BigInteger(1, le.reversedArray())
    private fun encode(n: BigInteger): ByteArray {
        val be = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32).also { out -> be.reversedArray().copyInto(out) }
    }
}

object LinkKeys {
    private val random = SecureRandom()

    fun nonce(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF with SHA-256 (RFC 5869): [length] bytes of key from [ikm]. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            out.write(t); i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray? = runCatching {
        require(s.length % 2 == 0)
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    /** The pairing's key, from the key agreement and everything both sides said ([transcript]). */
    fun pairingKey(shared: ByteArray, transcript: String): ByteArray = hkdf(shared, "prism-pair".toByteArray(), "prism-pair|$transcript", 32)

    /** One connection's two keys (caller → receiver, receiver → caller), from its key agreement and the pairing key. */
    fun sessionKeys(shared: ByteArray, pairingKey: ByteArray, transcript: String): Pair<ByteArray, ByteArray> {
        val k = hkdf(shared, pairingKey, "prism-session|$transcript", 64)
        return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
    }

    /** Proof of holding [pairingKey] in this connection ([side] "c" for the caller, "s" for the receiver). */
    fun proof(pairingKey: ByteArray, side: String, transcript: String): String = hex(hmac(pairingKey, "prism-auth|$side|$transcript".toByteArray()))
}

/**
 * Pairing by code (Bluetooth's passkey entry): the code's twenty bits are confirmed one by one, each side committing to a
 * fresh random number and the bit before either reveals. Someone in the middle has to commit before learning a bit,
 * so each round catches them with even odds: 2⁻²⁰ to get through all twenty, live, once per try (and tries are
 * limited). [side] is "c" for the caller, "s" for the receiver; [own] and [other] are the two public keys.
 */
object Passkey {
    const val ROUNDS = 20

    fun bit(code: String, round: Int): Int = ((code.toIntOrNull() ?: 0) shr round) and 1

    fun commit(nonce: ByteArray, side: String, own: ByteArray, other: ByteArray, bit: Int): String =
        LinkKeys.hex(LinkKeys.hmac(nonce, "$side|".toByteArray() + own + other + byteArrayOf(bit.toByte())))

    /** Whether [revealed] opens the other side's [commitment] with [bit] (the bit of the code this side holds). */
    fun opens(commitment: String, revealed: ByteArray, side: String, theirs: ByteArray, mine: ByteArray, bit: Int): Boolean =
        LinkKeys.same(commit(revealed, side, theirs, mine, bit).toByteArray(), commitment.toByteArray())
}

/**
 * One connection's sealing: each message is AES-GCM under this direction's key, its nonce the message's number. The
 * receiving side expects the numbers in order, so a message played again, dropped or swapped fails to open.
 */
class SealedChannel(private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    private var sent = 0L
    private var received = 0L

    @Synchronized fun seal(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, sendKey, sent++).doFinal(plain)

    /** The message, or null if it doesn't open (altered, replayed, out of order, or not for this key). */
    @Synchronized fun open(sealed: ByteArray): ByteArray? =
        runCatching { cipher(Cipher.DECRYPT_MODE, receiveKey, received).doFinal(sealed) }.getOrNull()?.also { received++ }

    private fun cipher(mode: Int, key: ByteArray, counter: Long): Cipher {
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (counter shr (56 - 8 * i)).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
    }
}
