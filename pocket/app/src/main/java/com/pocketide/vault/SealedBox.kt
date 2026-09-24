package com.pocketide.vault

/**
 * libsodium's `crypto_box_seal`, which GitHub requires for Actions secrets: an ephemeral X25519
 * key, nonce = BLAKE2b-192(ephemeral public ‖ recipient public), XSalsa20-Poly1305.
 * Output: ephemeral public key (32) ‖ MAC (16) ‖ ciphertext.
 */
object SealedBox {
    fun seal(recipientPublicKey: ByteArray, message: ByteArray): ByteArray {
        require(recipientPublicKey.size == 32) { "An X25519 public key is 32 bytes" }
        throw UnsupportedOperationException("stub: ${message.size}")
    }
}
