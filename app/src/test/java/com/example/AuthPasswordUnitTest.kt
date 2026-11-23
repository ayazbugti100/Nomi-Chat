package com.example

import com.example.data.repository.NomiChatRepository
import com.example.data.security.PasswordHasher
import com.example.data.security.PendingAuthCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPasswordUnitTest {

    @Test
    fun `password hashing produces 64-char hex digest and verifies matching password`() {
        val salt1 = PasswordHasher.generateSalt()
        val salt2 = PasswordHasher.generateSalt()
        assertNotEquals(salt1, salt2)

        val hash = PasswordHasher.hashPassword("SecretPass!9", salt1)
        assertEquals(64, hash.length)
        assertTrue(PasswordHasher.verifyPassword("SecretPass!9", salt1, hash))
        assertFalse(PasswordHasher.verifyPassword("WrongPass", salt1, hash))
        assertFalse(PasswordHasher.verifyPassword("SecretPass!9", salt2, hash))
    }

    @Test
    fun `pending auth credentials consumes once and clears cleanly`() {
        PendingAuthCredentials.setPassword("MyPass123")
        assertEquals("MyPass123", PendingAuthCredentials.consumePassword())
        assertEquals("", PendingAuthCredentials.consumePassword())

        PendingAuthCredentials.setPassword("TempPass")
        PendingAuthCredentials.clear()
        assertEquals("", PendingAuthCredentials.consumePassword())
    }

    @Test
    fun `normalizeUsername trims lowercases and strips whitespace`() {
        assertEquals("nomiuser7", NomiChatRepository.normalizeUsername("  Nomi User 7  "))
    }
}
