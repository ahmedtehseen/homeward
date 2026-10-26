package dev.personal.backup

import org.bouncycastle.crypto.digests.SHA256Digest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class IntegrityTest {
    @Test fun resumedHashMatchesIndependentHashAcrossUnalignedCheckpoints() {
        val bytes = ByteArray(1024 * 1024 + 137) { (it * 31).toByte() }
        var digest = SHA256Digest()
        var offset = 0
        for (end in listOf(1, 63, 64, 65, 10003, 999999, bytes.size)) {
            digest.update(bytes, offset, end - offset)
            digest = SHA256Digest(digest.encodedState)
            offset = end
        }
        val actual = ByteArray(32); digest.doFinal(actual, 0)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), actual)
    }
    @Test fun cleanupRequiresCurrentBytesVersionLibraryAndServerProof() {
        fun eligible(hash: String = "abc", savedHash: String = "abc", size: Long = 20, savedSize: Long = 20, version: String = "v1", savedVersion: String = "v1", library: String = "home", returned: String = "home", verified: Boolean = true) =
            Rules.cleanupAllowed(hash, savedHash, size, savedSize, version, savedVersion, library, returned, verified)
        assertTrue(eligible())
        assertFalse(eligible(hash = "changed"))
        assertFalse(eligible(size = 21))
        assertFalse(eligible(version = "v2"))
        assertFalse(eligible(returned = "other-mac"))
        assertFalse(eligible(verified = false))
        assertFalse(eligible(hash = "", savedHash = ""))
        assertFalse(eligible(library = "", returned = ""))
    }
    @Test fun retryBackoffIsBoundedAndJittered() {
        val values = (0..100).map { Rules.retryDelay(it, 1.0) }
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(values.all { it in 1..900000L })
        assertEquals(900000L, values.last())
        assertTrue(Rules.retryDelay(3, 0.0) < Rules.retryDelay(3, 1.0))
    }
}
