package com.klabcc.piersocial.crypto

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64 as BcBase64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cryptographic engine providing:
 * - Ed25519 identity keypair generation & packet signature verification
 * - X25519 ECDH + HKDF-SHA256 + AES-256-GCM end-to-end encrypted direct messages
 * - Android Keystore wrapping of private keys on disk
 * - SHA-256 public key fingerprinting & handle derivation
 */
class CryptoManager(
    private val identityFile: File?,
    private val keyWrapper: KeyWrappingProvider
) {
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedIdentity: IdentityKeys? = null

    constructor(context: Context) : this(
        identityFile = File(context.filesDir, "keystore_identity.json"),
        keyWrapper = AndroidKeystoreWrapper()
    )

    @Synchronized
    fun getOrCreateIdentity(): IdentityKeys {
        cachedIdentity?.let { return it }

        val file = identityFile
        if (file != null && file.exists()) {
            runCatching {
                val stored = json.decodeFromString<StoredEncryptedIdentity>(file.readText())
                val iv = decodeBase64(stored.ivBase64)
                val ciphertext = decodeBase64(stored.ciphertextBase64)
                val privateBlob = keyWrapper.unwrap(iv, ciphertext)
                require(privateBlob.size == 64) { "Invalid wrapped private key blob size" }
                val edPriv = privateBlob.copyOfRange(0, 32)
                val xPriv = privateBlob.copyOfRange(32, 64)
                val edPub = decodeBase64(stored.ed25519PublicBase64)
                val xPub = decodeBase64(stored.x25519PublicBase64)
                val loaded = buildIdentity(edPub, edPriv, xPub, xPriv)
                cachedIdentity = loaded
                return loaded
            }
        }

        val created = generateNewIdentityKeys()
        if (file != null) {
            val combinedPriv = created.ed25519PrivateKey + created.x25519PrivateKey
            val (iv, ciphertext) = keyWrapper.wrap(combinedPriv)
            val stored = StoredEncryptedIdentity(
                ivBase64 = encodeBase64(iv),
                ciphertextBase64 = encodeBase64(ciphertext),
                ed25519PublicBase64 = encodeBase64(created.ed25519PublicKey),
                x25519PublicBase64 = encodeBase64(created.x25519PublicKey)
            )
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(stored))
        }
        cachedIdentity = created
        return created
    }

    fun generateNewIdentityKeys(): IdentityKeys {
        val edPrivate = Ed25519PrivateKeyParameters(random)
        val edPublic = edPrivate.generatePublicKey()
        val xPrivate = X25519PrivateKeyParameters(random)
        val xPublic = xPrivate.generatePublicKey()

        return buildIdentity(
            edPub = edPublic.encoded,
            edPriv = edPrivate.encoded,
            xPub = xPublic.encoded,
            xPriv = xPrivate.encoded
        )
    }

    private fun buildIdentity(
        edPub: ByteArray,
        edPriv: ByteArray,
        xPub: ByteArray,
        xPriv: ByteArray
    ): IdentityKeys {
        val peerId = derivePeerId(edPub)
        val fingerprint = formatFingerprint(edPub)
        val handle = "@pier-${peerId.take(8)}"
        return IdentityKeys(
            peerId = peerId,
            handle = handle,
            fingerprint = fingerprint,
            ed25519PublicKey = edPub,
            ed25519PrivateKey = edPriv,
            x25519PublicKey = xPub,
            x25519PrivateKey = xPriv
        )
    }

    fun sign(message: ByteArray, ed25519PrivateKey: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(ed25519PrivateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun signString(message: String, ed25519PrivateKey: ByteArray): String =
        encodeBase64(sign(message.toByteArray(Charsets.UTF_8), ed25519PrivateKey))

    fun verify(message: ByteArray, signature: ByteArray, ed25519PublicKey: ByteArray): Boolean {
        return !(ed25519PublicKey.size != 32 || signature.size != 64) && runCatching {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(ed25519PublicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)
    }

    fun verifyString(message: String, signatureBase64: String, ed25519PublicKeyBase64: String): Boolean {
        return runCatching {
            verify(
                message = message.toByteArray(Charsets.UTF_8),
                signature = decodeBase64(signatureBase64),
                ed25519PublicKey = decodeBase64(ed25519PublicKeyBase64)
            )
        }.getOrDefault(false)
    }

    /**
     * Encrypts a DM using ephemeral X25519 ECDH + HKDF-SHA256 + AES-256-GCM,
     * and signs the ciphertext + metadata with the sender's Ed25519 private key.
     */
    fun encryptDirectMessage(
        plaintext: String,
        recipientX25519PubKey: ByteArray,
        senderIdentity: IdentityKeys
    ): EncryptedDmPayload {
        val ephemeralPriv = X25519PrivateKeyParameters(random)
        val ephemeralPub = ephemeralPriv.generatePublicKey()

        val sharedSecret = computeX25519SharedSecret(ephemeralPriv.encoded, recipientX25519PubKey)
        val aesKey = deriveSymmetricKey(sharedSecret, ephemeralPub.encoded + recipientX25519PubKey)

        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val ciphertext = aesGcmEncrypt(aesKey, nonce, plaintext.toByteArray(Charsets.UTF_8))

        val ephPubB64 = encodeBase64(ephemeralPub.encoded)
        val nonceB64 = encodeBase64(nonce)
        val cipherB64 = encodeBase64(ciphertext)
        val signedBody = "$ephPubB64|$nonceB64|$cipherB64"
        val sigB64 = signString(signedBody, senderIdentity.ed25519PrivateKey)

        return EncryptedDmPayload(
            ephemeralX25519PubKeyBase64 = ephPubB64,
            nonceBase64 = nonceB64,
            ciphertextBase64 = cipherB64,
            signatureBase64 = sigB64
        )
    }

    fun decryptDirectMessage(
        payload: EncryptedDmPayload,
        senderEd25519PubKey: ByteArray,
        recipientIdentity: IdentityKeys
    ): String? {
        val signedBody = "${payload.ephemeralX25519PubKeyBase64}|${payload.nonceBase64}|${payload.ciphertextBase64}"
        val verified = verify(
            message = signedBody.toByteArray(Charsets.UTF_8),
            signature = decodeBase64(payload.signatureBase64),
            ed25519PublicKey = senderEd25519PubKey
        )
        if (!verified) return null

        return runCatching {
            val ephPub = decodeBase64(payload.ephemeralX25519PubKeyBase64)
            val nonce = decodeBase64(payload.nonceBase64)
            val ciphertext = decodeBase64(payload.ciphertextBase64)

            val sharedSecret = computeX25519SharedSecret(recipientIdentity.x25519PrivateKey, ephPub)
            val aesKey = deriveSymmetricKey(sharedSecret, ephPub + recipientIdentity.x25519PublicKey)
            val plainBytes = aesGcmDecrypt(aesKey, nonce, ciphertext)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun sharedSessionFingerprint(peerX25519PubKey: ByteArray, localIdentity: IdentityKeys): String {
        val secret = computeX25519SharedSecret(localIdentity.x25519PrivateKey, peerX25519PubKey)
        return formatFingerprint(secret).take(19)
    }

    private fun computeX25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), secret, 0)
        return secret
    }

    private fun deriveSymmetricKey(sharedSecret: ByteArray, info: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, "piersocial-dm-v1".toByteArray(), info))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }

    private fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    companion object {
        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        fun sha256Hex(bytes: ByteArray): String =
            sha256(bytes).joinToString("") { "%02x".format(it) }

        fun derivePeerId(ed25519PublicKey: ByteArray): String =
            sha256Hex(ed25519PublicKey).take(16)

        fun formatFingerprint(publicKey: ByteArray): String {
            val hex = sha256Hex(publicKey).uppercase().take(24)
            return hex.chunked(4).joinToString(":")
        }

        fun encodeBase64(bytes: ByteArray): String =
            String(BcBase64.encode(bytes), Charsets.US_ASCII)

        fun decodeBase64(encoded: String): ByteArray =
            BcBase64.decode(encoded)
    }
}
