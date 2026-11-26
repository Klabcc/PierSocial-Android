package com.klabcc.piersocial.crypto

import kotlinx.serialization.Serializable

data class IdentityKeys(
    val peerId: String,
    val handle: String,
    val fingerprint: String,
    val ed25519PublicKey: ByteArray,
    val ed25519PrivateKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val x25519PrivateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKeys) return false
        return peerId == other.peerId &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            x25519PublicKey.contentEquals(other.x25519PublicKey)
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + x25519PublicKey.contentHashCode()
        return result
    }
}

@Serializable
data class StoredEncryptedIdentity(
    val ivBase64: String,
    val ciphertextBase64: String,
    val ed25519PublicBase64: String,
    val x25519PublicBase64: String
)

@Serializable
data class EncryptedDmPayload(
    val ephemeralX25519PubKeyBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val signatureBase64: String
)

interface KeyWrappingProvider {
    fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> // iv to ciphertext
    fun unwrap(iv: ByteArray, ciphertext: ByteArray): ByteArray
}
