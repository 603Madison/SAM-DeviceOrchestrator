package com.orchestrator.api

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing something for one device, so only it can read it (the server in between can't).
 *
 * Each device keeps a P-256 key pair and gives the server its public key. To seal for a device:
 * make a one-time key pair, agree a secret with the device's public key (ECDH), stretch it with
 * HKDF-SHA256 (salt: the one-time public key then the device's, both as 65-byte uncompressed
 * points; info: "samo/v1/seal"), and encrypt with AES-256-GCM (a random 12-byte nonce; the
 * device's id as the associated data, so a message can't be replayed to another device).
 *
 * The same in every client: Kotlin here, Rust in the Linux agent, WebCrypto in the web page.
 * `docs/PROTOCOL.md` has a test vector.
 *
 * This keeps what's sent out of the server's database and logs. It doesn't prove who sent it:
 * the server says which device did.
 */
object Seal {
    private const val INFO = "samo/v1/seal"
    private val random = SecureRandom()
    private val b64: Base64.Encoder = Base64.getEncoder()

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** A public key as devices exchange it: the 65-byte uncompressed point, base64. */
    fun publicKeyText(key: PublicKey): String = b64.encodeToString(point(key))

    /** A private key for keeping (the 32-byte number, base64). Keep it where only the app can read. */
    fun privateKeyText(key: PrivateKey): String = b64.encodeToString(fixed((key as ECPrivateKey).s))

    fun publicKey(text: String): PublicKey {
        val bytes = try {
            Base64.getDecoder().decode(text.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a public key")
        }
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Not a public key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        require(onCurve(x, y)) { "Not a public key" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve))
    }

    fun privateKey(text: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, Base64.getDecoder().decode(text.trim())), curve))

    /** The public half of [private]: so a stored private key is all a device keeps. */
    fun keyPair(privateText: String, publicText: String): KeyPair = KeyPair(publicKey(publicText), privateKey(privateText))

    /** Seals [plain] for the device [deviceId], whose public key is [recipient]. */
    fun seal(plain: ByteArray, recipient: String, deviceId: String, ephemeral: KeyPair = newKeyPair(), nonce: ByteArray = ByteArray(12).also(random::nextBytes)): Sealed {
        val recipientKey = publicKey(recipient)
        val epk = point(ephemeral.public)
        val key = derive(ephemeral.private, recipientKey, epk, point(recipientKey))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(deviceId.toByteArray())
        return Sealed(b64.encodeToString(epk), b64.encodeToString(nonce), b64.encodeToString(cipher.doFinal(plain)))
    }

    /** Opens what was sealed for this device ([deviceId], [keys]). Throws if it wasn't, or was changed. */
    fun open(sealed: Sealed, keys: KeyPair, deviceId: String): ByteArray {
        val decoder = Base64.getDecoder()
        val epk = publicKey(sealed.epk)
        val key = derive(keys.private, epk, point(epk), point(keys.public))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decoder.decode(sealed.nonce)))
        cipher.updateAAD(deviceId.toByteArray())
        return cipher.doFinal(decoder.decode(sealed.ct))
    }

    fun seal(payload: Payload, recipient: String, deviceId: String): Sealed =
        seal(ApiJson.encodeToString(Payload.serializer(), payload).toByteArray(), recipient, deviceId)

    /** The payload sealed for this device, or null for a kind this version doesn't know. */
    fun openPayload(sealed: Sealed, keys: KeyPair, deviceId: String): Payload? {
        val text = String(open(sealed, keys, deviceId))
        return try {
            ApiJson.decodeFromString(Payload.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    private fun derive(private: PrivateKey, public: PublicKey, epk: ByteArray, recipient: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(public, true) }.generateSecret()
        return hkdf(shared, salt = epk + recipient, info = INFO.toByteArray())
    }

    /** HKDF-SHA256 (RFC 5869), one block: 32 bytes. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + 1.toByte())
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun point(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
    }

    private fun fixed(n: BigInteger): ByteArray {
        val bytes = n.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    /** Whether (x, y) is a point of P-256: a key that isn't could leak the private key it's used with. */
    private fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (curve.curve.field as java.security.spec.ECFieldFp).p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.multiply(x).multiply(x).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
        return left == right
    }
}
