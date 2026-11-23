package com.example.data.security

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {
    private const val SALT_BYTES = 16
    private const val ITERATIONS = 1000
    private val secureRandom = SecureRandom()

    fun generateSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var current = "$salt:$password".toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) {
            digest.reset()
            current = digest.digest(current)
        }
        return current.joinToString("") { "%02x".format(it) }
    }

    fun verifyPassword(password: String, salt: String, expectedHash: String): Boolean {
        if (salt.isBlank() || expectedHash.length != 64) return false
        val actual = hashPassword(password, salt)
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }
}

/**
 * Holds the raw password strictly in volatile memory between AuthScreen submission
 * and authenticated Firestore setup, so plaintext passwords are never persisted to disk.
 */
object PendingAuthCredentials {
    @Volatile
    private var pendingPassword: String? = null

    fun setPassword(password: String?) {
        pendingPassword = password?.takeIf { it.isNotEmpty() }
    }

    fun consumePassword(): String {
        val value = pendingPassword.orEmpty()
        pendingPassword = null
        return value
    }

    fun clear() {
        pendingPassword = null
    }
}
