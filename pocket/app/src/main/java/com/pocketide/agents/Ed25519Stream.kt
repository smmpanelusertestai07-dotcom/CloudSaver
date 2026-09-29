package com.pocketide.agents

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032 §5.1.7) over a message that arrives in pieces.
 *
 * Open VSX signs the whole .vsix, and a Codex package is about 240 MB: more than an app's heap
 * holds, and every library verifier (BouncyCastle, Conscrypt) buffers the message first. Pure
 * Ed25519 needs the message only once, inside SHA-512(R ‖ A ‖ M), so the digest is fed while the
 * file downloads and the curve check runs at the end. Verification uses only public data, so
 * the arithmetic need not be constant-time. The point formulas are RFC 8032's reference code.
 */
internal class Ed25519Stream(private val publicKey: ByteArray, private val signature: ByteArray) {
    private val digest = MessageDigest.getInstance("SHA-512")

    init {
        require(publicKey.size == KEY_BYTES) { "An Ed25519 public key has 32 bytes" }
        require(signature.size == SIGNATURE_BYTES) { "An Ed25519 signature has 64 bytes" }
        digest.update(signature, 0, KEY_BYTES)
        digest.update(publicKey)
    }

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        digest.update(bytes, offset, length)
    }

    /** True when the signature is valid for everything passed to [update]. Call once. */
    fun verify(): Boolean {
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, KEY_BYTES)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(KEY_BYTES, SIGNATURE_BYTES))
        if (s >= L) return false
        val k = littleEndian(digest.digest()).mod(L)
        return equal(multiply(s, BASE), add(r, multiply(k, a)))
    }

    /** A point in extended coordinates (X, Y, Z, T), x = X/Z, y = Y/Z, x·y = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    companion object {
        const val KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64

        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val TWO = BigInteger.valueOf(2)
        private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inverse(BigInteger.valueOf(121666))).mod(P)
        private val SQRT_MINUS_ONE: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val NEUTRAL = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        private val BASE: Point = run {
            val y = BigInteger.valueOf(4).multiply(inverse(BigInteger.valueOf(5))).mod(P)
            val x = recoverX(y, 0) ?: error("The Ed25519 base point is fixed")
            Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        private fun inverse(value: BigInteger): BigInteger = value.modPow(P.subtract(TWO), P)

        private fun add(p: Point, q: Point): Point {
            val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
            val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
            val c = TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P)
            val d = TWO.multiply(p.z).multiply(q.z).mod(P)
            val e = b.subtract(a)
            val f = d.subtract(c)
            val g = d.add(c)
            val h = b.add(a)
            return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
        }

        private fun multiply(scalar: BigInteger, point: Point): Point {
            var result = NEUTRAL
            var addend = point
            for (i in 0 until scalar.bitLength()) {
                if (scalar.testBit(i)) result = add(result, addend)
                addend = add(addend, addend)
            }
            return result
        }

        private fun equal(p: Point, q: Point): Boolean =
            p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
                p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

        private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
            if (y >= P) return null
            val x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(inverse(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P)
            if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
            var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_MINUS_ONE).mod(P)
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
            if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
            return x
        }

        private fun decompress(bytes: ByteArray): Point? {
            val value = littleEndian(bytes)
            val sign = if (value.testBit(255)) 1 else 0
            val y = value.clearBit(255)
            val x = recoverX(y, sign) ?: return null
            return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
    }
}
