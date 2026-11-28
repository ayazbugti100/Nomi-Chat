package com.example.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PasswordHasher {
    private const val SALT_BYTES = 16
    private const val LEGACY_ITERATIONS = 1000
    private const val PBKDF2_ITERATIONS = 65_536
    private const val PBKDF2_KEY_BITS = 256
    private const val V2_PREFIX = "v2_"
    private val secureRandom = SecureRandom()

    fun generateSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(bytes)
        return V2_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateUsernameUid(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return "usr_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPassword(password: String, salt: String): String {
        if (salt.startsWith(V2_PREFIX)) {
            val rawSalt = salt.removePrefix(V2_PREFIX).toByteArray(Charsets.UTF_8)
            val derived = runCatching {
                val spec = PBEKeySpec(password.toCharArray(), rawSalt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            }.getOrElse {
                pbkdf2HmacSha256Compat(password.toByteArray(Charsets.UTF_8), rawSalt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS / 8)
            }
            return derived.joinToString("") { "%02x".format(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var current = "$salt:$password".toByteArray(Charsets.UTF_8)
        repeat(LEGACY_ITERATIONS) {
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

    private fun pbkdf2HmacSha256Compat(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        val hLen = mac.macLength
        val blockCount = (dkLen + hLen - 1) / hLen
        val output = ByteArray(blockCount * hLen)
        val saltPlusBlock = ByteArray(saltBytes.size + 4)
        System.arraycopy(saltBytes, 0, saltPlusBlock, 0, saltBytes.size)
        for (block in 1..blockCount) {
            saltPlusBlock[saltBytes.size] = ((block ushr 24) and 0xff).toByte()
            saltPlusBlock[saltBytes.size + 1] = ((block ushr 16) and 0xff).toByte()
            saltPlusBlock[saltBytes.size + 2] = ((block ushr 8) and 0xff).toByte()
            saltPlusBlock[saltBytes.size + 3] = (block and 0xff).toByte()

            var u = mac.doFinal(saltPlusBlock)
            val t = u.clone()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, output, (block - 1) * hLen, hLen)
        }
        return output.copyOf(dkLen)
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
