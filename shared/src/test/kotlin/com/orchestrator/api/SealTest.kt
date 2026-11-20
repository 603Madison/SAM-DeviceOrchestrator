package com.orchestrator.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealTest {
    @Test
    fun `what's sealed for a device opens there and nowhere else`() {
        val phone = Seal.newKeyPair()
        val laptop = Seal.newKeyPair()
        val sealed = Seal.seal(Payload.Clipboard("hello"), Seal.publicKeyText(phone.public), "phone-1")
        assertEquals(Payload.Clipboard("hello"), Seal.openPayload(sealed, phone, "phone-1"))
        assertTrue(runCatching { Seal.open(sealed, laptop, "phone-1") }.isFailure)
        // Nor on the right device under another id (a replay to a different registration).
        assertTrue(runCatching { Seal.open(sealed, phone, "phone-2") }.isFailure)
        assertTrue(runCatching { Seal.open(sealed.copy(ct = sealed.ct.reversed()), phone, "phone-1") }.isFailure)
    }

    @Test
    fun `keys survive being written down`() {
        val keys = Seal.newKeyPair()
        val again = Seal.keyPair(Seal.privateKeyText(keys.private), Seal.publicKeyText(keys.public))
        val sealed = Seal.seal("x".toByteArray(), Seal.publicKeyText(keys.public), "d")
        assertArrayEquals("x".toByteArray(), Seal.open(sealed, again, "d"))
    }

    @Test
    fun `a point off the curve is not a key`() {
        val bad = java.util.Base64.getEncoder().encodeToString(byteArrayOf(4) + ByteArray(64) { 1 })
        assertTrue(runCatching { Seal.publicKey(bad) }.isFailure)
        assertTrue(runCatching { Seal.publicKey("nonsense") }.isFailure)
    }

    @Test
    fun `a kind this version doesn't know is skipped, not an error`() {
        val keys = Seal.newKeyPair()
        val sealed = Seal.seal("""{"type":"from-the-future","x":1}""".toByteArray(), Seal.publicKeyText(keys.public), "d")
        assertNull(Seal.openPayload(sealed, keys, "d"))
    }

    /** The vector in docs/PROTOCOL.md: every client must open it. */
    @Test
    fun `the published test vector opens`() {
        val keys = Seal.keyPair(VECTOR_PRIVATE, VECTOR_PUBLIC)
        val sealed = Sealed(VECTOR_EPK, VECTOR_NONCE, VECTOR_CT)
        assertEquals("""{"type":"clipboard","text":"hello from the aurora"}""", String(Seal.open(sealed, keys, "device-1")))
    }

    companion object {
        const val VECTOR_PRIVATE = "fPVfWTT+lmpkhYZJbYmgJjnb2Yk2z5N40jybPxGus/g="
        const val VECTOR_PUBLIC = "BLjLkiLeA9LQO1HSvascpiMSjqDzL48RyznPjsAfHRkwvfHZWmiCDUIt1/tIu0HcMP5IJjCNMrVxG2v6lxnHJic="
        const val VECTOR_EPK = "BCb8iyUOM3J9h1vLpRI9Fb3lmP+DrST3xeOkuCKM5hRqMMj7XwcscLCPmJQH1FOH5SM9QZNPDYbkJI6ZTey43pQ="
        const val VECTOR_NONCE = "od4tYckzNodlv4sX"
        const val VECTOR_CT = "wy4elJA1eGU8oFGPEuH4GkjWfSz0kD/j2Eq6oP97idy//cg4UoNeWX//frfkREIWSOpTXNp+tYXjJqaMcXGOB+hiqw=="
    }
}
